package com.ukkera.pos.database

import androidx.room3.PooledConnection
import androidx.room3.Transactor
import androidx.room3.useWriterConnection
import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.Revision
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Real temporary SQLite + synthetic principals/appointments. Never live/production evidence. */
class RetainedCommandReadTest {
    @Test fun actualKernelCommitCanBeReadRepeatedlyWithoutAnySqlWritesOrHeadRequirement() = runTest {
        val f = StoreFixture()
        try {
            f.seed()
            val store = f.store()
            val principal = testPrincipal()
            val first = f.request(810)
            val original = assertIs<MutationReply.Recorded>(store.execute(principal, first))
            // An unrelated aggregate operation follows. Original operation need not be the global head.
            assertIs<MutationReply.Recorded>(store.execute(principal, f.request(811, CounterInput(id = testId(812)))))
            val counts = retainedTableCounts(f)
            f.database.useWriterConnection { connection ->
                requireDurableWriter(connection)
                connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                    val changesBefore = connection.changeCount()
                    val view = TransactionView(TEST_SCOPE, f.database.facts())
                    try {
                        repeat(3) {
                            val retained = assertNotNull(view.retainedCommand(first.operationId))
                            assertEquals(original.result, retained.result)
                            assertEquals(original.sequence, retained.sequence)
                            assertEquals(original.authorityEpoch, retained.authorityEpoch)
                            assertEquals(TEST_AUTHORITY.assignmentId, retained.assignmentId)
                            assertEquals(f.handler.normalize(first.payload), retained.normalized)
                            assertEquals(1L, retained.aggregateWrites.single().storageGeneration.value)
                            assertEquals("counter/v1", retained.aggregateWrites.single().schema)
                            assertEquals(1, retained.jobs.size)
                            assertEquals("qualification.receipt", retained.jobs.single().kind)
                        }
                        assertEquals(changesBefore, connection.changeCount())
                        assertEquals(2L, assertNotNull(f.database.facts().branch(TEST_SCOPE.storageKey())).sequence)
                    } finally { view.close() }
                }
            }
            assertEquals(counts, retainedTableCounts(f))
        } finally { f.close() }
    }

    @Test fun actualKernelRejectionIsRetainedWithoutInventingEffectsOrUploadJob() = runTest {
        val f = StoreFixture()
        try {
            f.seed()
            val request = f.request(813, CounterInput(reject = true))
            val original = assertIs<MutationReply.Recorded>(f.store().execute(testPrincipal(), request))
            assertFalse(original.committed)
            f.withRetainedView { view ->
                val retained = assertNotNull(view.retainedCommand(request.operationId))
                assertFalse(retained.committed)
                assertEquals(original.code, retained.code)
                assertEquals(original.result, retained.result)
                assertTrue(retained.aggregateWrites.isEmpty() && retained.claimChanges.isEmpty() && retained.jobs.isEmpty())
            }
            assertEquals(1L, f.count("outbox_intent")) // Automatic upload exists, but is not a requested job.
        } finally { f.close() }
    }

    @Test fun historySurvivesLaterGenerationSchemaPayloadAndDisabledOriginalActor() = runTest {
        val f = StoreFixture()
        try {
            f.seed()
            val request = f.request(814)
            val principal = testPrincipal()
            val identity = f.identity(request)
            val original = assertIs<MutationReply.Recorded>(f.store().execute(principal, request))
            assertIs<MutationReply.Recorded>(f.store().execute(principal,
                f.request(815, CounterInput(expectedRevision = Revision(1), delta = 5))))
            // Synthetic later schema/payload update: the old reader must never hydrate this as its AFTER value.
            f.database.useWriterConnection { connection ->
                requireDurableWriter(connection)
                connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                    val row = assertNotNull(f.database.facts().aggregate(TEST_SCOPE.storageKey(), "qualification.counter", testId(10).value))
                    val unrelated = byteArrayOf(-1, 0, 42)
                    assertEquals(1, f.database.facts().replaceAggregate(row.scope, row.kind, row.id, row.revision, row.revision + 1,
                        "other.future-schema/v99", unrelated, sha256(unrelated).value))
                }
            }
            f.setAuth("disabled")
            f.withRetainedView { view ->
                val retained = assertNotNull(view.retainedCommand(request.operationId))
                assertEquals(original.result, retained.result)
                assertEquals(1L, retained.aggregateWrites.single().storageGeneration.value)
                assertEquals("counter/v1", retained.aggregateWrites.single().schema)
                assertContentEquals("1".encodeToByteArray(), retained.aggregateWrites.single().payload.copyBytes())
                val current = assertNotNull(view.aggregate(AggregateKey("qualification.counter", testId(10))))
                assertEquals(3L, current.revision.value)
                assertEquals("other.future-schema/v99", current.schema)
            }
            // Historic facts do not re-enable the disabled actor's public recovery route.
            assertEquals(DeferredReason.AUTHORIZATION_DENIED,
                assertIs<LookupReply.Deferred>(f.store().lookup(principal, identity)).reason)
        } finally { f.close() }
    }

    @Test fun oldEpochAndAssignmentRemainOriginalAfterReopenAndProducerRemoval() = runTest {
        var f = StoreFixture()
        try {
            f.seed()
            val principal = testPrincipal()
            val request = f.request(816)
            val identity = f.identity(request)
            val original = assertIs<MutationReply.Recorded>(f.store().execute(principal, request))
            val successor = AuthorityIdentity(testId(817), testId(818), Revision(2))
            // Test-only appointment, NOT custody transfer or old-writer fencing qualification.
            f.database.useWriterConnection { connection ->
                requireDurableWriter(connection)
                connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                    connection.usePrepared("UPDATE branch_authority SET assignmentId=?,deviceId=?,epoch=2,sequence=0,revision=revision+1 WHERE scope=?") {
                        it.bindText(1, successor.assignmentId.value); it.bindText(2, successor.deviceId.value)
                        it.bindText(3, TEST_SCOPE.storageKey()); it.step()
                    }
                }
            }
            val next = assertIs<MutationReply.Recorded>(f.store(authority = successor).execute(principal,
                f.request(819, CounterInput(id = testId(820)))))
            assertEquals(1L, next.sequence.value)
            val path = f.path
            f.close()
            f = StoreFixture(path)
            var retained: RetainedCommandEvidence? = null
            val currentFixture = f
            val withoutProducer = AtomicMasterStore(f.database, TEST_SCOPE, successor, emptyList(),
                TransactionAuthorizationSource { p, view ->
                    retained = view.retainedCommand(request.operationId)
                    currentFixture.auth.current(p, view)
                }, f.runtime, MasterClock { TEST_TIME })
            assertIs<LookupReply.Found>(withoutProducer.lookup(principal, identity))
            val old = assertNotNull(retained)
            assertEquals(original.result, old.result)
            assertEquals(1L, old.authorityEpoch.value)
            assertEquals(1L, old.sequence.value)
            assertEquals(TEST_AUTHORITY.assignmentId, old.assignmentId)
            assertEquals(0, f.handler.prepareCount)
        } finally { f.close() }
    }

    @Test fun bothAbsentAndEachOrphanAreDistinctAndNeverHydrateTheOtherBlob() = runTest {
        val absent = StoreFixture()
        try {
            absent.seed()
            val counted = RetainedCountingDao(absent.database.facts())
            absent.withRetainedView(counted) { assertNull(it.retainedCommand(RETAINED_TEST_OPERATION)) }
            assertEquals(2, counted.metadataReads)
            assertEquals(0, counted.blobReads)
        } finally { absent.close() }
        for (missingOperation in listOf(false, true)) {
            val f = StoreFixture()
            try {
                f.seed()
                val pair = retainedTestPair()
                f.database.useWriterConnection { connection ->
                    requireDurableWriter(connection)
                    // Test-only orphan injection in this disposable file. No production
                    // trigger/schema changes; the actual read restores/requires FK=ON.
                    if (missingOperation) connection.usePrepared("PRAGMA foreign_keys=OFF") { it.step() }
                    try {
                        connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                            if (missingOperation) f.database.facts().insertJournal(pair.journal)
                            else f.database.facts().insertOperation(pair.operation)
                        }
                    } finally {
                        if (missingOperation) connection.usePrepared("PRAGMA foreign_keys=ON") { it.step() }
                    }
                }
                val counted = RetainedCountingDao(f.database.facts())
                f.withRetainedView(counted) { view ->
                    assertRetainedReadFailure { view.retainedCommand(RETAINED_TEST_OPERATION) }
                }
                assertEquals(2, counted.metadataReads)
                assertEquals(0, counted.blobReads)
            } finally { f.close() }
        }
    }

    @Test fun sqlPreflightRejectsEitherOversizedOrNonBlobMemberBeforeHydratingEither() = runTest {
        val cases: List<Pair<Map<String, Any>, Map<String, Any>>> = listOf(
            mapOf<String, Any>("result" to ByteArray(65_537)) to emptyMap(),
            emptyMap<String, Any>() to mapOf("envelope" to ByteArray(4_194_305)),
            mapOf<String, Any>("result" to "PRIVATE-TEXT-NOT-BLOB") to emptyMap(),
            emptyMap<String, Any>() to mapOf("envelope" to "PRIVATE-TEXT-NOT-BLOB"),
        )
        for ((operation, journal) in cases) assertPreflightRefuses(operation, journal)
    }

    @Test fun sqlPreflightBoundsTextByBytesIncludingPastNulAndPreventsIntegerNarrowing() = runTest {
        for (operation in listOf(
            mapOf<String, Any>("commandVersion" to 4_294_967_297L), // Room Int would otherwise narrow to 1.
            mapOf("commandVersion" to 2.5), mapOf("epoch" to 2.5), mapOf("sequence" to 0L),
            mapOf("policyRevision" to -1L), mapOf("actorId" to (testId(6).value + "\u0000" + "x".repeat(70_000))),
            mapOf("command" to "ع".repeat(64)), mapOf("committedAt" to "x".repeat(70_000)),
            mapOf("digest" to ByteArray(64)), mapOf("authenticationId" to "x".repeat(37)),
        )) assertPreflightRefuses(operation, emptyMap())
        for (journal in listOf(mapOf<String, Any>("assignmentId" to "x".repeat(37)),
            mapOf("previousHash" to ("0".repeat(64) + "\u0000x")), mapOf("hash" to ByteArray(64)),
            mapOf("epoch" to -1L), mapOf("sequence" to 1.5))) assertPreflightRefuses(emptyMap(), journal)
    }

    @Test fun boundedMalformedStoredTextAndMatchingSizeCorruptionStillFailAfterHydration() = runTest {
        for (operation in listOf(mapOf<String, Any>("command" to RetainedSqlText(byteArrayOf(-1))),
            mapOf("command" to "a\u0000b"), mapOf("actorId" to testId(99).value))) {
            val f = StoreFixture()
            try {
                f.seed()
                f.insertRetainedPair(operationChanges = operation)
                val counted = RetainedCountingDao(f.database.facts())
                f.withRetainedView(counted) { view -> assertRetainedReadFailure { view.retainedCommand(RETAINED_TEST_OPERATION) } }
                // A platform's strict text hydration may refuse invalid UTF-8
                // before the pair decoder. Either path must stay fail-closed.
                if (operation["command"] is RetainedSqlText) assertTrue(counted.blobReads in 1..2)
                else assertEquals(2, counted.blobReads)
            } finally { f.close() }
        }
    }

    @Test fun zeroLengthBlobsAreValidAndMetadataChecksDoNotHideThem() = runTest {
        val f = StoreFixture()
        try {
            f.seed()
            f.insertRetainedPair(retainedTestPair(code = "refused", effects = emptyList(), normalized = byteArrayOf(), result = byteArrayOf()))
            val counted = RetainedCountingDao(f.database.facts())
            f.withRetainedView(counted) { view ->
                val evidence = assertNotNull(view.retainedCommand(RETAINED_TEST_OPERATION))
                assertEquals(0, evidence.result.size)
                assertEquals(0, evidence.normalized.size)
            }
            assertEquals(2, counted.metadataReads)
            assertEquals(2, counted.blobReads)
        } finally { f.close() }
    }

    @Test fun disappearingHydratedPairCannotBecomeAnAuthoritativeOrOrdinaryNull() = runTest {
        val f = StoreFixture()
        try {
            f.seed()
            f.insertRetainedPair()
            for ((missingOperation, missingJournal) in listOf(true to true, true to false, false to true)) {
                val base = f.database.facts()
                val inconsistent = object : MasterDao by base {
                    override suspend fun operation(scope: String, operationId: String): OperationRow? =
                        if (missingOperation) null else base.operation(scope, operationId)
                    override suspend fun retainedJournal(scope: String, operationId: String): JournalRow? =
                        if (missingJournal) null else base.retainedJournal(scope, operationId)
                }
                f.withRetainedView(inconsistent) { view ->
                    assertRetainedReadFailure { view.retainedCommand(RETAINED_TEST_OPERATION) }
                }
            }
        } finally { f.close() }
    }

    @Test fun retainedViewChecksLifetimeAfterEverySuspendingReadAndBeforeFirstRead() = runTest {
        val f = StoreFixture()
        try {
            f.seed()
            f.insertRetainedPair()
            for (closeAt in 0..3) {
                lateinit var view: TransactionView
                var reads = 0
                suspend fun expire() { yield(); if (reads++ == closeAt) view.close() }
                val base = f.database.facts()
                val dao = object : MasterDao by base {
                    override suspend fun retainedOperationMetadata(scope: String, operationId: String): RetainedBlobMetadata? {
                        val row = base.retainedOperationMetadata(scope, operationId); expire(); return row
                    }
                    override suspend fun retainedJournalMetadata(scope: String, operationId: String): RetainedBlobMetadata? {
                        val row = base.retainedJournalMetadata(scope, operationId); expire(); return row
                    }
                    override suspend fun operation(scope: String, operationId: String): OperationRow? {
                        val row = base.operation(scope, operationId); expire(); return row
                    }
                    override suspend fun retainedJournal(scope: String, operationId: String): JournalRow? {
                        val row = base.retainedJournal(scope, operationId); expire(); return row
                    }
                }
                f.withRetainedView(dao) {
                    view = it
                    val failure = assertFailsWith<IllegalStateException> { it.retainedCommand(RETAINED_TEST_OPERATION) }
                    assertEquals("Transaction view has expired", failure.message)
                    assertEquals(closeAt + 1, reads)
                }
                assertFailsWith<IllegalStateException> { view.retainedCommand(RETAINED_TEST_OPERATION) }
            }
            val counted = RetainedCountingDao(f.database.facts())
            val closed = TransactionView(TEST_SCOPE, counted).also { it.close() }
            assertFailsWith<IllegalStateException> { closed.retainedCommand(RETAINED_TEST_OPERATION) }
            assertEquals(0, counted.metadataReads)
        } finally { f.close() }
    }

    @Test fun ordinaryReadsAlsoRecheckLifetimeAfterSuspension() = runTest {
        val f = StoreFixture()
        try {
            f.seed()
            for (claimRead in listOf(false, true)) {
                lateinit var view: TransactionView
                val base = f.database.facts()
                val dao = object : MasterDao by base {
                    override suspend fun aggregate(scope: String, kind: String, id: String): AggregateRow? {
                        val row = base.aggregate(scope, kind, id); yield(); view.close(); return row
                    }
                    override suspend fun currentClaimStorageValid(scope: String, namespace: String, key: String): Boolean? {
                        val metadata = base.currentClaimStorageValid(scope, namespace, key); yield(); view.close(); return metadata
                    }
                }
                f.withRetainedView(dao) {
                    view = it
                    assertFailsWith<IllegalStateException> {
                        if (claimRead) view.claim(ClaimKey("qualification.claim", "missing")) else view.aggregate(AUTH_KEY)
                    }
                }
            }
        } finally { f.close() }
    }

    @Test fun cancellationPassesThroughButDaoErrorDetailsNeverEscapeTheAccessor() = runTest {
        val f = StoreFixture()
        try {
            f.seed()
            val cancelled = CancellationException("synthetic cancelled read")
            val base = f.database.facts()
            val cancelling = object : MasterDao by base {
                override suspend fun retainedOperationMetadata(scope: String, operationId: String): RetainedBlobMetadata? = throw cancelled
            }
            f.withRetainedView(cancelling) { view ->
                assertSame(cancelled, assertFailsWith<CancellationException> { view.retainedCommand(RETAINED_TEST_OPERATION) })
            }
            val failing = object : MasterDao by base {
                override suspend fun retainedOperationMetadata(scope: String, operationId: String): RetainedBlobMetadata? =
                    throw IllegalArgumentException("PRIVATE SQL PAYLOAD", IllegalStateException("PRIVATE cause"))
            }
            f.withRetainedView(failing) { view -> assertRetainedReadFailure { view.retainedCommand(RETAINED_TEST_OPERATION) } }
        } finally { f.close() }
    }

    @Test fun invalidRetainedPairInsideActualPrepareReturnsStorageUncertainWithoutRecordingNewWork() = runTest {
        val f = StoreFixture()
        try {
            f.seed()
            f.database.useWriterConnection { connection ->
                requireDurableWriter(connection)
                connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                    f.database.facts().insertOperation(retainedTestPair().operation)
                }
            }
            val handler = object : MasterCommandHandler by f.handler {
                override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
                    view.retainedCommand(RETAINED_TEST_OPERATION)
                    return f.handler.prepare(normalized, view, context)
                }
            }
            val store = AtomicMasterStore(f.database, TEST_SCOPE, TEST_AUTHORITY, listOf(handler), f.auth, f.runtime, MasterClock { TEST_TIME })
            val before = retainedTableCounts(f)
            assertEquals(DeferredReason.STORAGE_UNCERTAIN,
                assertIs<MutationReply.Deferred>(store.execute(testPrincipal(), f.request(821))).reason)
            assertEquals(before, retainedTableCounts(f))
            assertEquals(0, f.handler.prepareCount)
        } finally { f.close() }
    }
}

private suspend fun <T> StoreFixture.withRetainedView(
    dao: MasterDao = database.facts(),
    action: suspend (TransactionView) -> T,
): T = database.useWriterConnection { connection ->
    requireDurableWriter(connection)
    connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
        val view = TransactionView(TEST_SCOPE, dao)
        try { action(view) } finally { view.close() }
    }
}

private class RetainedCountingDao(private val base: MasterDao) : MasterDao by base {
    var metadataReads = 0
    var blobReads = 0
    override suspend fun retainedOperationMetadata(scope: String, operationId: String): RetainedBlobMetadata? {
        metadataReads++; return base.retainedOperationMetadata(scope, operationId)
    }
    override suspend fun retainedJournalMetadata(scope: String, operationId: String): RetainedBlobMetadata? {
        metadataReads++; return base.retainedJournalMetadata(scope, operationId)
    }
    override suspend fun operation(scope: String, operationId: String): OperationRow? {
        blobReads++; return base.operation(scope, operationId)
    }
    override suspend fun retainedJournal(scope: String, operationId: String): JournalRow? {
        blobReads++; return base.retainedJournal(scope, operationId)
    }
}

private suspend fun assertPreflightRefuses(operation: Map<String, Any>, journal: Map<String, Any>) {
    val f = StoreFixture()
    try {
        f.seed()
        f.insertRetainedPair(operationChanges = operation, journalChanges = journal)
        val counted = RetainedCountingDao(f.database.facts())
        f.withRetainedView(counted) { view -> assertRetainedReadFailure { view.retainedCommand(RETAINED_TEST_OPERATION) } }
        assertEquals(2, counted.metadataReads)
        assertEquals(0, counted.blobReads)
    } finally { f.close() }
}

private suspend fun assertRetainedReadFailure(read: suspend () -> Unit) {
    val failure = assertFailsWith<IllegalStateException> { read() }
    assertTrue(failure.message in RetainedCommandIssue.entries.map { "Retained command unavailable: ${it.name}" })
    assertNull(failure.cause)
    assertTrue(failure.suppressedExceptions.isEmpty())
}

private suspend fun retainedTableCounts(f: StoreFixture): List<Long> =
    listOf("operation_result", "journal_envelope", "outbox_intent", "audit_event", "unique_claim").map { f.count(it) }

private suspend fun PooledConnection.changeCount(): Long = usePrepared("SELECT total_changes()") {
    check(it.step()); it.getLong(0)
}

/** Malformed input injection is local to disposable test files, never a production write path. */
private data class RetainedSqlText(val bytes: ByteArray)

private suspend fun StoreFixture.insertRetainedPair(
    pair: RetainedTestPair = retainedTestPair(),
    operationChanges: Map<String, Any> = emptyMap(),
    journalChanges: Map<String, Any> = emptyMap(),
) = database.useWriterConnection { connection ->
    requireDurableWriter(connection)
    connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
        val op = pair.operation
        val journal = pair.journal
        connection.insertRetainedTestRow("operation_result", linkedMapOf<String, Any>(
            "scope" to op.scope, "operationId" to op.operationId, "actorId" to op.actorId, "command" to op.command,
            "commandVersion" to op.commandVersion.toLong(), "digest" to op.digest, "outcome" to op.outcome,
            "result" to op.result, "resultHash" to op.resultHash, "sequence" to op.sequence, "epoch" to op.epoch,
            "committedAt" to op.committedAt, "authenticationId" to op.authenticationId, "policyRevision" to op.policyRevision,
        ).apply { putAll(operationChanges) })
        connection.insertRetainedTestRow("journal_envelope", linkedMapOf<String, Any>(
            "scope" to journal.scope, "sequence" to journal.sequence, "operationId" to journal.operationId,
            "assignmentId" to journal.assignmentId, "epoch" to journal.epoch, "previousHash" to journal.previousHash,
            "hash" to journal.hash, "envelope" to journal.envelope,
        ).apply { putAll(journalChanges) })
    }
}

private suspend fun PooledConnection.insertRetainedTestRow(table: String, values: Map<String, Any>) {
    val placeholders = values.values.joinToString(",") { if (it is RetainedSqlText) "CAST(? AS TEXT)" else "?" }
    usePrepared("INSERT INTO $table (${values.keys.joinToString(",")}) VALUES ($placeholders)") { statement ->
        values.values.forEachIndexed { index, value ->
            when (value) {
                is String -> statement.bindText(index + 1, value)
                is ByteArray -> statement.bindBlob(index + 1, value)
                is Long -> statement.bindLong(index + 1, value)
                is Double -> statement.bindDouble(index + 1, value)
                is RetainedSqlText -> statement.bindBlob(index + 1, value.bytes)
                else -> error("Unsupported synthetic SQL value")
            }
        }
        statement.step()
    }
}
