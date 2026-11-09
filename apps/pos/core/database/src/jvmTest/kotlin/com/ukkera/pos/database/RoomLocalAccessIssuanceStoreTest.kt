package com.ukkera.pos.database

import androidx.room3.Transactor
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.ukkera.pos.recovery.RecoveryAdmissionState
import com.ukkera.pos.security.*
import com.ukkera.pos.types.Revision
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

/** Actual fifth-table persistence and synthetic JCA AEAD, not an authorized token issuer. */
class RoomLocalAccessIssuanceStoreTest {
    @Test fun immutableIssuanceRoundTripsAndReopensWithoutChangingAnyOriginalTable() = runTest {
        val f = LocalAuthorizationStoreFixture()
        val encoded = ProtectedLocalSecurityCodec.encodeIssuance(f.issuance())
        val after: Map<String, List<List<String>>>
        try {
            f.seed(issuance = null); val before = f.snapshot()
            f.protectedStore.transaction { it.insertIssuance(f.issuance()) }
            after = f.snapshot()
            assertEquals(before.filterKeys { it != ISSUANCE_TABLE }, after.filterKeys { it != ISSUANCE_TABLE })
            assertEquals(1, after.getValue(ISSUANCE_TABLE).size)
            repeat(2) {
                val value = assertNotNull(f.protectedStore.transaction { it.readIssuance(TEST_SCOPE, testId(9110)) })
                assertContentEquals(encoded, ProtectedLocalSecurityCodec.encodeIssuance(value))
                assertEquals(Revision(0), value.revision)
            }
            assertEquals(after, f.snapshot())
            val row = assertNotNull(f.database.localAccessIssuance().read(TEST_SCOPE.storageKey(), testId(9110).value))
            assertEquals(encoded.size + 16, row.ciphertextAndTag.size)
            assertFalse(row.ciphertextAndTag.copyOf(encoded.size).contentEquals(encoded))
        } finally { f.close() }
        val reopened = LocalAuthorizationStoreFixture(f.path)
        try {
            assertContentEquals(encoded, ProtectedLocalSecurityCodec.encodeIssuance(assertNotNull(
                reopened.protectedStore.transaction { it.readIssuance(TEST_SCOPE, testId(9110)) })))
            assertEquals(after, reopened.snapshot()); assertEquals(1, reopened.protection.openCalls)
            assertEquals(0, reopened.protection.sealCalls)
        } finally { reopened.close() }
    }

    @Test fun missingIssuanceReadsNeverInitializeRowsOrInvokeProtection() = runTest {
        val f = LocalAuthorizationStoreFixture()
        try {
            val before = f.snapshot()
            repeat(2) { assertNull(f.protectedStore.transaction { it.readIssuance(TEST_SCOPE, testId(9110)) }) }
            assertEquals(before, f.snapshot()); assertEquals(0, f.protection.openCalls); assertEquals(0, f.protection.sealCalls)
        } finally { f.close() }
    }

    @Test fun duplicateInsertCannotExtendOrRebindEvenAnIdenticalExistingSession() = runTest {
        withCurrentFixture { f ->
            val before = f.snapshot()
            for (replacement in listOf(f.issuance(), f.issuance(duration = 3000),
                f.issuance(actorId = testId(9740)), f.issuance(generation = 6), f.issuance(issuerContextId = testId(9741)))) {
                assertIssuanceStorageUnavailable { f.protectedStore.transaction { it.insertIssuance(replacement) } }
                assertEquals(before, f.snapshot())
            }
        }
    }

    @Test fun caughtDuplicateInsertPoisonsTheTransactionAndRollsBackAnEarlierDistinctIssuance() = runTest {
        withCurrentFixture { f ->
            val before = f.snapshot(); var caught = false
            assertIssuanceStorageUnavailable {
                f.protectedStore.transaction { tx ->
                    tx.insertIssuance(f.issuance(sessionId = testId(9742)))
                    try { tx.insertIssuance(f.issuance()) } catch (_: IllegalStateException) { caught = true }
                }
            }
            assertTrue(caught); assertEquals(before, f.snapshot())
        }
    }

    @Test fun sqliteRejectsUpdateDeleteReplaceAndUpsertRegardlessOfSameValueOrRecursiveTriggerMode() = runTest {
        withCurrentFixture { f ->
            val before = f.snapshot()
            val statements = listOf(
                "UPDATE $ISSUANCE_TABLE SET revision=revision",
                "UPDATE $ISSUANCE_TABLE SET sessionId='${testId(9742).value}'",
                "DELETE FROM $ISSUANCE_TABLE",
                "INSERT OR REPLACE INTO $ISSUANCE_TABLE SELECT * FROM $ISSUANCE_TABLE",
                "INSERT INTO $ISSUANCE_TABLE SELECT * FROM $ISSUANCE_TABLE WHERE 1 ON CONFLICT(scope,sessionId) DO UPDATE SET revision=excluded.revision",
            )
            for (recursive in listOf("OFF", "ON")) {
                f.database.useWriterConnection { it.usePrepared("PRAGMA recursive_triggers=$recursive") { statement -> statement.step() } }
                for (sql in statements) {
                    assertFailsWith<Exception> { executeIssuanceSql(f, sql) }
                    assertEquals(before, f.snapshot(), "$recursive $sql")
                }
            }
        }
    }

    @Test fun schemaAllowsOnlyIntegerZeroForNewImmutableIssuanceRevision() = runTest {
        withCurrentFixture { f ->
            val before = f.snapshot()
            for (revision in listOf("-1", "1", "9223372036854775807", "0.5", "CAST('0' AS BLOB)")) {
                val sql = "INSERT INTO $ISSUANCE_TABLE SELECT scope,'${testId(9742).value}',schemaVersion,formatVersion,algorithm," +
                    "$revision,keyId,nonce,ciphertextAndTag FROM $ISSUANCE_TABLE"
                assertFailsWith<Exception> { executeIssuanceSql(f, sql) }
                assertEquals(before, f.snapshot(), revision)
            }
        }
    }

    @Test fun implicitRowidConflictsCannotDeleteAnotherIssuanceThroughAnyAliasOrScope() = runTest {
        for (victimRowid in listOf(1L, 0L, -1L, -7L)) {
            val f = LocalAuthorizationStoreFixture()
            try {
                f.seed(issuance = null)
                val otherScope = TEST_SCOPE.copy(branchId = testId(9780))
                f.database.useWriterConnection { connection ->
                    requireDurableWriter(connection)
                    connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                        val branch = assertNotNull(f.database.facts().branch(TEST_SCOPE.storageKey()))
                        f.database.facts().insertBranch(branch.copy(scope = otherScope.storageKey(),
                            recoveryJson = Json.encodeToString(RecoveryAdmissionState.initiallyProvisioned(otherScope.branchId))))
                    }
                }
                val sealed = f.security.seal(ProtectedLocalBinding(f.issuanceKey(), Revision(0)),
                    ProtectedLocalSecurityCodec.encodeIssuance(f.issuance()))
                executeIssuanceSql(f, "INSERT INTO $ISSUANCE_TABLE (rowid,scope,sessionId,schemaVersion,formatVersion,algorithm,revision,keyId,nonce,ciphertextAndTag) " +
                    "VALUES ($victimRowid,'${TEST_SCOPE.storageKey()}','${testId(9110).value}',1,1,'AES-256-GCM',0," +
                    "'${f.security.keyReference.keyId.value}',x'${sealed.copyNonce().issuanceHex()}',x'${sealed.copyCiphertextAndTag().issuanceHex()}')")
                val before = f.snapshot()
                for (recursive in listOf("OFF", "ON")) {
                    f.database.useWriterConnection { it.usePrepared("PRAGMA recursive_triggers=$recursive") { statement -> statement.step() } }
                    for (alias in listOf("rowid", "_rowid_", "oid")) for (targetScope in listOf(TEST_SCOPE, otherScope)) {
                        val sql = "INSERT OR REPLACE INTO $ISSUANCE_TABLE ($alias,scope,sessionId,schemaVersion,formatVersion,algorithm,revision,keyId,nonce,ciphertextAndTag) " +
                            "SELECT $victimRowid,'${targetScope.storageKey()}','${testId(9781).value}',schemaVersion,formatVersion,algorithm,revision,keyId,nonce,ciphertextAndTag " +
                            "FROM $ISSUANCE_TABLE WHERE rowid=$victimRowid"
                        assertFailsWith<Exception>("rowid=$victimRowid alias=$alias recursive=$recursive crossScope=${targetScope != TEST_SCOPE}") {
                            executeIssuanceSql(f, sql)
                        }
                        assertEquals(before, f.snapshot())
                        assertNotNull(f.protectedStore.transaction { it.readIssuance(TEST_SCOPE, testId(9110)) })
                    }
                }
                if (victimRowid == -1L) {
                    // SQLite's omitted NEW.rowid sentinel is -1. A deliberately raw -1 row is
                    // outside the normal Room insert path: conservative refusal beats deletion.
                    assertIssuanceStorageUnavailable { f.protectedStore.transaction { it.insertIssuance(f.issuance(sessionId = testId(9782))) } }
                    assertEquals(before, f.snapshot())
                } else {
                    f.protectedStore.transaction { it.insertIssuance(f.issuance(sessionId = testId(9782))) }
                    assertNotNull(f.protectedStore.transaction { it.readIssuance(TEST_SCOPE, testId(9782)) })
                }
            } finally { f.close() }
        }
    }

    @Test fun issuanceForeignKeyRequiresAnExistingBranchAndDoesNotProvisionIt() = runTest {
        withCurrentFixture { f ->
            val otherScope = TEST_SCOPE.copy(branchId = testId(9743)); val before = f.snapshot()
            assertIssuanceStorageUnavailable { f.protectedStore.transaction { it.insertIssuance(f.issuance(scope = otherScope)) } }
            assertEquals(before, f.snapshot())
        }
    }

    @Test fun metadataRejectsBadTypesVersionsSizesAndNonzeroRevisionBeforeCiphertextHydration() = runTest {
        val corruptions = listOf("revision=1", "revision=-1", "revision=0.5", "revision=CAST('0' AS BLOB)",
            "schemaVersion=4294967297", "schemaVersion=1.5", "formatVersion=2",
            "algorithm=CAST('AES-256-GCM' AS BLOB)", "algorithm='AES-256-GCM'||char(0)",
            "keyId=zeroblob(1048576)", "keyId=keyId||char(0)", "nonce=zeroblob(13)", "nonce=CAST(nonce AS TEXT)",
            "ciphertextAndTag=zeroblob(16)", "ciphertextAndTag=CAST(ciphertextAndTag AS TEXT)",
            "ciphertextAndTag=zeroblob(4113)")
        for (corruption in corruptions) {
            val probe = IssuanceHydrationProbe(); val f = LocalAuthorizationStoreFixture(driver = probe)
            try {
                f.seed(); f.security.corrupt(f.issuanceKey(), corruption); val before = f.snapshot(); probe.reset()
                assertIssuanceStorageUnavailable { f.protectedStore.transaction { it.readIssuance(TEST_SCOPE, testId(9110)) } }
                assertEquals(0, probe.fullRows.get(), corruption); assertEquals(0, probe.blobReads.get(), corruption)
                assertEquals(0, f.protection.openCalls, corruption); assertEquals(before, f.snapshot())
            } finally { f.close() }
        }
    }

    @Test fun exactCiphertextCapHydratesButAnInvalidTagStillCannotDecode() = runTest {
        val probe = IssuanceHydrationProbe(); val f = LocalAuthorizationStoreFixture(driver = probe)
        try {
            f.seed(); f.security.corrupt(f.issuanceKey(), "ciphertextAndTag=zeroblob(4112)"); probe.reset()
            val before = f.snapshot()
            assertIssuanceStorageUnavailable { f.protectedStore.transaction { it.readIssuance(TEST_SCOPE, testId(9110)) } }
            assertEquals(1, probe.fullRows.get()); assertEquals(2, probe.blobReads.get()); assertEquals(1, f.protection.openCalls)
            assertEquals(before, f.snapshot())
        } finally { f.close() }
    }

    @Test fun healthyMetadataControlActuallyFetchesAndDecryptsTheSelectedIssuance() = runTest {
        val probe = IssuanceHydrationProbe(); val f = LocalAuthorizationStoreFixture(driver = probe)
        try {
            f.seed(); probe.reset()
            assertNotNull(f.protectedStore.transaction { it.readIssuance(TEST_SCOPE, testId(9110)) })
            assertEquals(1, probe.fullRows.get()); assertEquals(2, probe.blobReads.get()); assertEquals(1, f.protection.openCalls)
        } finally { f.close() }
    }

    @Test fun storedKeyCannotChooseAnotherConfiguredProviderOrBypassTheExpectedKeyReference() = runTest {
        withCurrentFixture { f ->
            f.security.corrupt(f.issuanceKey(), "keyId='${testId(9744).value}'")
            val before = f.snapshot()
            assertIssuanceStorageUnavailable { f.protectedStore.transaction { it.readIssuance(TEST_SCOPE, testId(9110)) } }
            assertEquals(0, f.protection.openCalls); assertEquals(before, f.snapshot())
        }
    }

    @Test fun nonceCiphertextAndTagBitFlipsFailActualAuthenticationAndPreserveTheCorruptEvidence() = runTest {
        for (part in listOf("nonce", "ciphertext", "tag")) withCurrentFixture { f ->
            val row = assertNotNull(f.database.localAccessIssuance().read(TEST_SCOPE.storageKey(), testId(9110).value))
            val bytes = if (part == "nonce") row.nonce else row.ciphertextAndTag
            val index = if (part == "tag") bytes.lastIndex else 0
            bytes[index] = (bytes[index].toInt() xor 1).toByte()
            f.security.corrupt(f.issuanceKey(), "${if (part == "nonce") "nonce" else "ciphertextAndTag"}=x'${bytes.issuanceHex()}'")
            val before = f.snapshot()
            assertIssuanceStorageUnavailable { f.protectedStore.transaction { it.readIssuance(TEST_SCOPE, testId(9110)) } }
            assertEquals(1, f.protection.openCalls); assertEquals(before, f.snapshot())
        }
    }

    @Test fun actualAeadBindsLogicalTableEveryScopeCoordinateAndSessionIdentity() = runTest {
        withCurrentFixture { f ->
            val original = ProtectedLocalBinding(f.issuanceKey(), Revision(0))
            val wrong = listOf(
                original.copy(key = ProtectedLocalRecordKey.Account(TEST_SCOPE, testId(9110))),
                original.copy(key = f.issuanceKey().copy(scope = TEST_SCOPE.copy(organizationId = testId(9745)))),
                original.copy(key = f.issuanceKey().copy(scope = TEST_SCOPE.copy(companyId = testId(9745)))),
                original.copy(key = f.issuanceKey().copy(scope = TEST_SCOPE.copy(branchId = testId(9745)))),
                original.copy(key = f.issuanceKey().copy(sessionId = testId(9745))),
            )
            for (binding in wrong) {
                val record = f.security.seal(binding, ProtectedLocalSecurityCodec.encodeIssuance(f.issuance()))
                f.security.corrupt(f.issuanceKey(), record.issuanceEnvelopeSql())
                val before = f.snapshot(); val calls = f.protection.openCalls
                assertIssuanceStorageUnavailable { f.protectedStore.transaction { it.readIssuance(TEST_SCOPE, testId(9110)) } }
                assertEquals(calls + 1, f.protection.openCalls); assertEquals(before, f.snapshot())
            }
        }
    }

    @Test fun validAeadTagDoesNotAuthorizeWrongInnerSessionOrNoncanonicalPayload() = runTest {
        withCurrentFixture { f ->
            val payloads = listOf(ProtectedLocalSecurityCodec.encodeIssuance(f.issuance(sessionId = testId(9746))),
                (ProtectedLocalSecurityCodec.encodeIssuance(f.issuance()).decodeToString() + " ").encodeToByteArray())
            for (bytes in payloads) {
                var authenticatedPlaintextObserved = false
                f.protection.afterOpen = { authenticatedPlaintextObserved = true }
                val record = f.security.seal(ProtectedLocalBinding(f.issuanceKey(), Revision(0)), bytes)
                f.security.corrupt(f.issuanceKey(), record.issuanceEnvelopeSql()); val before = f.snapshot()
                assertIssuanceStorageUnavailable { f.protectedStore.transaction { it.readIssuance(TEST_SCOPE, testId(9110)) } }
                assertTrue(authenticatedPlaintextObserved, "strict decoding, not a failed AEAD tag, must refuse this payload")
                assertEquals(before, f.snapshot())
            }
        }
    }

    @Test fun unqualifiedKeyContextDeniesBothReadingAndInsertingWithoutChangingAnyRows() = runTest {
        withCurrentFixture { f ->
            f.protection.status = KeyProtectionStatus.UNQUALIFIED_CONTEXT
            val before = f.snapshot(); val seals = f.protection.sealCalls
            assertIssuanceStorageUnavailable { f.protectedStore.transaction { it.readIssuance(TEST_SCOPE, testId(9110)) } }
            assertIssuanceStorageUnavailable { f.protectedStore.transaction { it.insertIssuance(f.issuance(sessionId = testId(9747))) } }
            assertEquals(0, f.protection.openCalls); assertEquals(seals, f.protection.sealCalls); assertEquals(before, f.snapshot())
        }
    }

    @Test fun cancellationAfterOneInsertPreservesTheOriginalCancellationAndRollsBackThePair() = runTest {
        withCurrentFixture { f ->
            val cancellation = LocalStorageCancellationSentinel("synthetic issuance cancellation")
            val before = f.snapshot()
            val thrown = assertFailsWith<LocalStorageCancellationSentinel> {
                f.protectedStore.transaction {
                    it.insertIssuance(f.issuance(sessionId = testId(9748)))
                    f.protection.throwOnSeal = cancellation
                    it.insertIssuance(f.issuance(sessionId = testId(9749)))
                }
            }
            assertSame(cancellation, thrown); assertEquals(before, f.snapshot())
        }
    }

    @Test fun retainedProtectedTransactionCannotReadOrInsertIssuanceAfterItsOwnerCloses() = runTest {
        withCurrentFixture { f ->
            lateinit var retained: RoomLocalSecurityTransaction
            f.protectedStore.transaction { retained = it; assertNotNull(it.readIssuance(TEST_SCOPE, testId(9110))) }
            val before = f.snapshot(); val opens = f.protection.openCalls; val seals = f.protection.sealCalls
            assertIssuanceStorageUnavailable { retained.readIssuance(TEST_SCOPE, testId(9110)) }
            assertIssuanceStorageUnavailable { retained.insertIssuance(f.issuance(sessionId = testId(9750))) }
            assertEquals(opens, f.protection.openCalls); assertEquals(seals, f.protection.sealCalls); assertEquals(before, f.snapshot())
        }
    }
}

private const val ISSUANCE_TABLE = "protected_local_access_issuance"

private suspend fun executeIssuanceSql(f: LocalAuthorizationStoreFixture, sql: String) = f.database.useWriterConnection { connection ->
    requireDurableWriter(connection)
    connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) { usePrepared(sql) { it.step() } }
}

private suspend fun assertIssuanceStorageUnavailable(block: suspend () -> Any?) {
    val error = assertFailsWith<IllegalStateException> { block() }
    assertEquals("Protected local record unavailable", error.message); assertNull(error.cause)
}

private fun ByteArray.issuanceHex(): String = HexFormat.of().formatHex(this)
private fun SealedLocalRecord.issuanceEnvelopeSql(): String =
    "nonce=x'${copyNonce().issuanceHex()}',ciphertextAndTag=x'${copyCiphertextAndTag().issuanceHex()}'"

/** Probe only actual selected issuance row/blob hydration; metadata queries do not fetch blobs. */
private class IssuanceHydrationProbe : SQLiteDriver {
    private val delegate = BundledSQLiteDriver()
    val fullRows = AtomicInteger(0)
    val blobReads = AtomicInteger(0)
    override val hasConnectionPool: Boolean get() = delegate.hasConnectionPool
    fun reset() { fullRows.set(0); blobReads.set(0) }
    override fun open(fileName: String): SQLiteConnection {
        val original = delegate.open(fileName)
        return object : SQLiteConnection by original {
            override fun prepare(sql: String): SQLiteStatement {
                val statement = original.prepare(sql)
                val selected = sql.lowercase().replace("`", "").trimStart().startsWith("select * from $ISSUANCE_TABLE")
                return object : SQLiteStatement by statement {
                    override fun step(): Boolean = statement.step().also { if (it && selected) fullRows.incrementAndGet() }
                    override fun getBlob(index: Int): ByteArray {
                        if (selected) blobReads.incrementAndGet()
                        return statement.getBlob(index)
                    }
                }
            }
        }
    }
}
