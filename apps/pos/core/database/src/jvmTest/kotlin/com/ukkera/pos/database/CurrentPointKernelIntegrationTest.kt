package com.ukkera.pos.database

import androidx.room3.PooledConnection
import androidx.room3.Transactor
import androidx.room3.useWriterConnection
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.ActionBinding
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.AuthorizedAction
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Deliberately target-read-free TEST handlers isolate the kernel's proposal validator. */
class CurrentPointKernelIntegrationTest {
    @Test fun fractionalRevisionIsRejectedBeforeResultPersistenceNotMerelyRolledBackLater() = runTest {
        exerciseValidator(ValidatorStorageCase.REAL_REVISION)
    }

    @Test fun sameByteTextPayloadCannotBeSilentlyOverwrittenByAnOtherwiseValidProposal() = runTest {
        exerciseValidator(ValidatorStorageCase.TEXT_PAYLOAD)
    }

    @Test fun blobClaimOwnerIsRejectedBeforeAResultInsertAndFailedRelease() = runTest {
        exerciseValidator(ValidatorStorageCase.BLOB_CLAIM_OWNER)
    }
}

private enum class ValidatorStorageCase { REAL_REVISION, TEXT_PAYLOAD, BLOB_CLAIM_OWNER }
private val VALIDATOR_KEY = AggregateKey("qualification.counter", testId(10))
private val VALIDATOR_CLAIM = ClaimKey("qualification.custody", "validator-custody")

private suspend fun exerciseValidator(case: ValidatorStorageCase) {
    for (corrupt in listOf(false, true)) {
        val f = StoreFixture()
        try {
            f.seed()
            val principal = testPrincipal()
            assertTrue(assertIs<MutationReply.Recorded>(f.store().execute(principal,
                f.request(33_001, CounterInput(claim = VALIDATOR_CLAIM.key)))).committed)
            assertTrue(assertIs<MutationReply.Recorded>(f.store().execute(principal,
                f.request(33_002, CounterInput(expectedRevision = Revision(1))))).committed)
            if (corrupt) f.corruptValidatorStorage(case)
            val before = f.validatorObservation()
            val changes = f.validatorTotalChanges()
            val handler = ValidatorOnlyHandler(f.handler, case)
            var resultInsertions = 0
            val store = AtomicMasterStore(f.database, TEST_SCOPE, TEST_AUTHORITY, listOf(handler),
                f.auth, f.runtime, MasterClock { TEST_TIME }, StoreFaultProbe {
                    if (it == StoreFaultPoint.AFTER_RESULT) resultInsertions++
                })
            val reply = store.execute(principal, f.request(33_003, CounterInput(expectedRevision = Revision(2))))
            assertEquals(1, handler.prepares)
            assertEquals(1, handler.evaluations) // Authorization/recovery and pure proposal really succeeded.
            if (corrupt) {
                assertEquals(DeferredReason.STORAGE_UNCERTAIN, assertIs<MutationReply.Deferred>(reply).reason)
                // Raw DAO validation can return the same uncertainty AFTER a result
                // insert and failed CAS/release. A rollback-only assertion is insufficient.
                assertEquals(0, resultInsertions)
                assertEquals(changes, f.validatorTotalChanges())
                assertEquals(before, f.validatorObservation())
            } else {
                val committed = assertIs<MutationReply.Recorded>(reply)
                assertTrue(committed.committed)
                assertFalse(committed.replayed)
                assertEquals(1, resultInsertions)
                assertTrue(f.validatorTotalChanges() > changes)
                assertEquals(3L, f.count("operation_result"))
                assertEquals(if (case == ValidatorStorageCase.BLOB_CLAIM_OWNER) 0L else 1L, f.count("unique_claim"))
            }
        } finally { f.close() }
    }
}

private class ValidatorOnlyHandler(
    private val original: CounterHandler,
    private val case: ValidatorStorageCase,
) : MasterCommandHandler by original {
    var prepares = 0
    var evaluations = 0
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        prepares++
        // No target or claim reads here. The normal authorization source may read
        // its unrelated healthy AUTH_KEY; only kernel validation sees the bad row.
        return object : PreparedMutation() {
            override val requirement = AuthorizationRequirement(
                Permission(SecurityAction.INVENTORY_ADJUST, PermissionVerb.PERFORM), TEST_SCOPE,
                ActionBinding(VALIDATOR_KEY.id, Revision(2), context.semanticDigest, context.operationId, key.name))
            override val affectedResources = setOf(
                RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, context.originalActorId),
                RecoveryResourceKey(RecoveryResourceKind.STOCK, VALIDATOR_KEY.id))
            override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
                evaluations++
                return if (case == ValidatorStorageCase.BLOB_CLAIM_OWNER) ProposedOutcome.Apply(
                    StoredBytes("released".encodeToByteArray()), emptyList(),
                    listOf(ClaimChange.Release(VALIDATOR_CLAIM, VALIDATOR_KEY.id)))
                else ProposedOutcome.Apply(StoredBytes("3".encodeToByteArray()), listOf(
                    AggregateChange(VALIDATOR_KEY, Revision(2), "counter/v1", StoredBytes("3".encodeToByteArray()))))
            }
        }
    }
}

private suspend fun <T> StoreFixture.withValidatorWriter(action: suspend (PooledConnection) -> T): T =
    database.useWriterConnection { connection ->
        requireDurableWriter(connection)
        connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) { action(connection) }
    }

private suspend fun StoreFixture.corruptValidatorStorage(case: ValidatorStorageCase) = withValidatorWriter { connection ->
    if (case == ValidatorStorageCase.BLOB_CLAIM_OWNER) {
        connection.usePrepared("UPDATE unique_claim SET ownerId=CAST(ownerId AS BLOB) WHERE scope=? AND namespace=? AND `key`=?") {
            it.bindText(1, TEST_SCOPE.storageKey()); it.bindText(2, VALIDATOR_CLAIM.namespace); it.bindText(3, VALIDATOR_CLAIM.key); it.step()
        }
        connection.usePrepared("SELECT typeof(ownerId) FROM unique_claim WHERE scope=? AND namespace=? AND `key`=?") {
            it.bindText(1, TEST_SCOPE.storageKey()); it.bindText(2, VALIDATOR_CLAIM.namespace); it.bindText(3, VALIDATOR_CLAIM.key)
            assertTrue(it.step()); assertEquals("blob", it.getText(0)); assertFalse(it.step())
        }
    } else {
        val ddl = connection.usePrepared("SELECT sql FROM sqlite_master WHERE type='trigger' AND name='aggregate_revision_update'") {
            assertTrue(it.step()); it.getText(0)
        }
        connection.usePrepared("DROP TRIGGER aggregate_revision_update") { it.step() }
        try {
            val assignment = if (case == ValidatorStorageCase.REAL_REVISION) "revision=2.25" else "payload=CAST(payload AS TEXT)"
            connection.usePrepared("UPDATE aggregate_state SET $assignment WHERE scope=? AND kind=? AND id=?") {
                it.bindText(1, TEST_SCOPE.storageKey()); it.bindText(2, VALIDATOR_KEY.kind); it.bindText(3, VALIDATOR_KEY.id.value); it.step()
            }
        } finally { connection.usePrepared(ddl) { it.step() } }
        connection.usePrepared("SELECT typeof(revision),typeof(payload) FROM aggregate_state WHERE scope=? AND kind=? AND id=?") {
            it.bindText(1, TEST_SCOPE.storageKey()); it.bindText(2, VALIDATOR_KEY.kind); it.bindText(3, VALIDATOR_KEY.id.value)
            assertTrue(it.step())
            assertEquals(if (case == ValidatorStorageCase.REAL_REVISION) "real" else "integer", it.getText(0))
            assertEquals(if (case == ValidatorStorageCase.TEXT_PAYLOAD) "text" else "blob", it.getText(1))
            assertFalse(it.step())
        }
        connection.usePrepared("SELECT sql FROM sqlite_master WHERE type='trigger' AND name='aggregate_revision_update'") {
            assertTrue(it.step()); assertEquals(ddl, it.getText(0))
        }
    }
}

private suspend fun StoreFixture.validatorTotalChanges(): Long = withValidatorWriter { connection ->
    connection.usePrepared("SELECT total_changes()") { assertTrue(it.step()); it.getLong(0) }
}

private data class ValidatorObservation(
    val branch: BranchRow, val counts: List<Long>, val aggregate: List<String>, val claims: List<List<String>>,
)

private suspend fun StoreFixture.validatorObservation(): ValidatorObservation = withValidatorWriter { connection ->
    val counts = listOf("operation_result", "journal_envelope", "audit_event", "outbox_intent", "unique_claim", "aggregate_state")
        .map { table -> connection.usePrepared("SELECT COUNT(*) FROM $table") { assertTrue(it.step()); it.getLong(0) } }
    val aggregate = connection.usePrepared(
        "SELECT typeof(revision),quote(revision),typeof(payload),hex(payload),payloadHash,schema FROM aggregate_state WHERE scope=? AND kind=? AND id=?") {
        it.bindText(1, TEST_SCOPE.storageKey()); it.bindText(2, VALIDATOR_KEY.kind); it.bindText(3, VALIDATOR_KEY.id.value)
        assertTrue(it.step()); val values = List(6) { index -> it.getText(index) }; assertFalse(it.step()); values
    }
    val claims = connection.usePrepared("SELECT namespace,`key`,typeof(ownerId),hex(ownerId) FROM unique_claim WHERE scope=? ORDER BY namespace,`key`") {
        it.bindText(1, TEST_SCOPE.storageKey())
        buildList { while (it.step()) add(List(4) { index -> it.getText(index) }) }
    }
    ValidatorObservation(assertNotNull(database.facts().branch(TEST_SCOPE.storageKey())), counts, aggregate, claims)
}
