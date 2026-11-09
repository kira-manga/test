package com.ukkera.pos.database

import androidx.room3.PooledConnection
import androidx.room3.Transactor
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteStatement
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlin.coroutines.cancellation.CancellationException
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/** Real disposable SQLite, genuine counter commits, and explicitly synthetic authority fixtures. */
class CurrentPointReadTest {
    @Test fun genuineSecondGenerationAndClaimReadRepeatedlyWithoutWrites() = runTest {
        withPointFixture { f ->
            f.database.useWriterConnection { connection ->
                requireDurableWriter(connection)
                connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                    val changes = connection.pointChanges()
                    val dao = PointProbeDao(f.database.facts())
                    val access = PointAccess(dao)
                    try {
                        repeat(3) {
                            val row = assertNotNull(access.aggregate())
                            assertEquals(POINT_KEY, row.key)
                            assertEquals(Revision(2), row.revision)
                            assertEquals("counter/v1", row.schema)
                            assertContentEquals("2".encodeToByteArray(), row.payload.copyBytes())
                            assertEquals(sha256(row.payload.copyBytes()), row.digest)
                            assertEquals(POINT_KEY.id, access.claim())
                        }
                        assertEquals(changes, connection.pointChanges())
                        assertEquals(3, dao.count(PointQuery.AGGREGATE_ROW))
                        assertEquals(3, dao.count(PointQuery.CLAIM_ROW))
                    } finally { access.active = false }
                }
            }
            assertEquals(2L, f.count("operation_result"))
        }
    }

    @Test fun missingKeysReturnNullWithoutHydratingRows() = runTest {
        withPointFixture { f ->
            val dao = PointProbeDao(f.database.facts())
            f.withPointAccess(dao) { access ->
                assertNull(access.aggregate(AggregateKey(POINT_KEY.kind, testId(990))))
                assertNull(access.claim(ClaimKey(POINT_CLAIM.namespace, "missing")))
            }
            assertEquals(listOf(PointQuery.AGGREGATE_METADATA, PointQuery.CLAIM_METADATA), dao.queries)
        }
    }

    @Test fun emptyBlobIsPresentNotAnAbsentOrNullPayload() = runTest {
        assertPointBlobRoundTrip(byteArrayOf())
    }

    @Test fun arbitraryBinaryBlobDoesNotRequireUtf8OrTextConversion() = runTest {
        assertPointBlobRoundTrip(byteArrayOf(0, -1, -128, 65, 0, 10, 127))
    }

    @Test fun exactlySixtyFourKiBIsAcceptedWithoutTruncation() = runTest {
        assertPointBlobRoundTrip(ByteArray(65_536) { (it * 37).toByte() })
    }

    @Test fun oneByteBeyondLimitIsRejectedBeforeBlobHydration() = runTest {
        withPointFixture { f ->
            f.replacePointPayload(ByteArray(65_537) { 23 })
            f.withSqlPoint { connection ->
                connection.usePrepared("SELECT typeof(payload), length(payload) FROM aggregate_state WHERE kind='qualification.counter'") {
                    assertTrue(it.step()); assertEquals("blob", it.getText(0)); assertEquals(65_537L, it.getLong(1))
                }
            }
            assertAggregatePreflightRefusal(f)
        }
    }

    @Test fun fractionalRealRevisionCannotBeCoercedToTheGenuineIntegerGeneration() = runTest {
        withPointFixture { f ->
            f.corruptPointAggregate("revision=2.25")
            f.withSqlPoint { connection ->
                connection.usePrepared("SELECT typeof(revision), revision FROM aggregate_state WHERE kind='qualification.counter'") {
                    assertTrue(it.step()); assertEquals("real", it.getText(0))
                    assertEquals(2L, it.getLong(1)) // Demonstrates the coercion the preflight must prevent.
                    assertEquals(2.25, it.getDouble(1))
                }
            }
            assertAggregatePreflightRefusal(f)
        }
    }

    @Test fun zeroNegativeAndTextRevisionsAreRejectedBeforeHydration() = runTest {
        for ((expression, type) in listOf("0" to "integer", "-1" to "integer", "'not-a-counter'" to "text")) {
            withPointFixture { f ->
                f.corruptPointAggregate("revision=$expression")
                assertPointSqlType(f, "revision", type)
                assertAggregatePreflightRefusal(f)
            }
        }
    }

    @Test fun maximumPositiveSqliteIntegerIsReadableButDoesNotGrantAnotherRevision() = runTest {
        withPointFixture { f ->
            f.corruptPointAggregate("revision=9223372036854775807")
            assertPointSqlType(f, "revision", "integer")
            f.withPointAccess { assertEquals(Revision(Long.MAX_VALUE), assertNotNull(it.aggregate()).revision) }
        }
    }

    @Test fun identicalTextPayloadCannotMasqueradeAsTheCommittedBlob() = runTest {
        withPointFixture { f ->
            f.corruptPointAggregate("payload=CAST(payload AS TEXT)")
            assertPointSqlType(f, "payload", "text")
            f.withSqlPoint { connection ->
                connection.usePrepared("SELECT hex(payload), payloadHash FROM aggregate_state WHERE kind='qualification.counter'") {
                    assertTrue(it.step()); assertEquals("32", it.getText(0))
                    assertEquals(sha256("2".encodeToByteArray()).value, it.getText(1))
                }
            }
            assertAggregatePreflightRefusal(f)
        }
    }

    @Test fun blobSchemaAndBlobHashCannotMasqueradeAsText() = runTest {
        for (column in listOf("schema", "payloadHash")) {
            withPointFixture { f ->
                f.corruptPointAggregate("$column=CAST($column AS BLOB)")
                assertPointSqlType(f, column, "blob")
                assertAggregatePreflightRefusal(f)
            }
        }
    }

    @Test fun nulInsideBoundedSchemaAndHashIsRejectedBeforeHydration() = runTest {
        for ((column, text) in listOf("schema" to "counter\u0000/v1", "payloadHash" to ("0".repeat(63) + '\u0000'))) {
            withPointFixture { f ->
                f.corruptPointAggregate("$column=?") { bindText(1, text) }
                f.withSqlPoint { connection ->
                    connection.usePrepared("SELECT length(CAST($column AS BLOB)), instr(CAST($column AS BLOB), x'00') FROM aggregate_state WHERE kind='qualification.counter'") {
                        assertTrue(it.step()); assertEquals(text.encodeToByteArray().size.toLong(), it.getLong(0))
                        assertTrue(it.getLong(1) > 0)
                    }
                }
                assertAggregatePreflightRefusal(f)
            }
        }
    }

    @Test fun nulTailedOversizedMetadataUsesByteLengthNotSqliteTextLength() = runTest {
        for ((column, prefix) in listOf("schema" to "counter/v1", "payloadHash" to "0".repeat(64))) {
            withPointFixture { f ->
                val text = prefix + '\u0000' + "x".repeat(200)
                f.corruptPointAggregate("$column=?") { bindText(1, text) }
                f.withSqlPoint { connection ->
                    connection.usePrepared("SELECT length($column), length(CAST($column AS BLOB)) FROM aggregate_state WHERE kind='qualification.counter'") {
                        assertTrue(it.step()); assertEquals(prefix.length.toLong(), it.getLong(0))
                        assertEquals(text.length.toLong(), it.getLong(1))
                    }
                }
                assertAggregatePreflightRefusal(f)
            }
        }
    }

    @Test fun wrongCanonicalHashIsNotAcceptedMerelyBecauseMetadataIsWellTyped() = runTest {
        withPointFixture { f ->
            f.corruptPointAggregate("payloadHash=?") { bindText(1, "0".repeat(64)) }
            val dao = PointProbeDao(f.database.facts())
            f.withPointAccess(dao) { access -> assertPointUnavailable { access.aggregate() } }
            assertEquals(1, dao.count(PointQuery.AGGREGATE_ROW))
        }
    }

    @Test fun invalidMetadataAndImpossibleLengthsNeverHydrateOrReturnAbsence() = runTest {
        withPointFixture { f ->
            for (metadata in listOf(CurrentAggregateMetadata(1, false), CurrentAggregateMetadata(-1, true),
                CurrentAggregateMetadata(65_537, true), CurrentAggregateMetadata(Long.MAX_VALUE, true))) {
                val dao = PointProbeDao(f.database.facts()).also { it.aggregateMetadata = { metadata } }
                f.withPointAccess(dao) { access -> assertPointUnavailable { access.aggregate() } }
                assertEquals(listOf(PointQuery.AGGREGATE_METADATA), dao.queries)
            }
            val claims = PointProbeDao(f.database.facts()).also { it.claimMetadata = { false } }
            f.withPointAccess(claims) { access -> assertPointUnavailable { access.claim() } }
            assertEquals(listOf(PointQuery.CLAIM_METADATA), claims.queries)
        }
    }

    @Test fun hydratedPayloadMustMatchItsPreflightByteCount() = runTest {
        withPointFixture { f ->
            val dao = PointProbeDao(f.database.facts()).also { probe ->
                probe.aggregateMetadata = { assertNotNull(it).copy(byteCount = 2) }
            }
            f.withPointAccess(dao) { access -> assertPointUnavailable { access.aggregate() } }
            assertEquals(1, dao.count(PointQuery.AGGREGATE_ROW))
        }
    }

    @Test fun positiveMetadataFollowedByMissingHydrationIsUncertaintyNotAbsence() = runTest {
        withPointFixture { f ->
            val dao = PointProbeDao(f.database.facts()).also {
                it.aggregateRow = { null }; it.claimRow = { null }
            }
            f.withPointAccess(dao) { access ->
                assertPointUnavailable { access.aggregate() }
                assertPointUnavailable { access.claim() }
            }
            assertEquals(PointQuery.entries.toList(), dao.queries)
        }
    }

    @Test fun hydratedAggregateRequiresExactScopeKindAndIdBinding() = runTest {
        withPointFixture { f ->
            val changes: List<(AggregateRow) -> AggregateRow> = listOf(
                { it.copy(scope = TEST_SCOPE.copy(branchId = testId(991)).storageKey()) },
                { it.copy(kind = "qualification.other") },
                { it.copy(id = testId(992).value) },
            )
            for (change in changes) {
                val dao = PointProbeDao(f.database.facts()).also { it.aggregateRow = { row -> change(assertNotNull(row)) } }
                f.withPointAccess(dao) { access -> assertPointUnavailable { access.aggregate() } }
                assertEquals(1, dao.count(PointQuery.AGGREGATE_ROW))
            }
        }
    }

    @Test fun hydratedAggregateStillValidatesRevisionSchemaAndDigestWithoutPrivateCauses() = runTest {
        withPointFixture { f ->
            val changes: List<(AggregateRow) -> AggregateRow> = listOf(
                { it.copy(revision = 0) }, { it.copy(revision = -1) },
                { it.copy(schema = "PRIVATE schema") }, { it.copy(schema = "x".repeat(97)) },
                { it.copy(payloadHash = "PRIVATE HASH") }, { it.copy(payloadHash = "A".repeat(64)) },
                { it.copy(payload = ByteArray(65_537)) },
            )
            for (change in changes) {
                val dao = PointProbeDao(f.database.facts()).also { it.aggregateRow = { row -> change(assertNotNull(row)) } }
                f.withPointAccess(dao) { access -> assertPointUnavailable { access.aggregate() } }
                assertEquals(1, dao.count(PointQuery.AGGREGATE_ROW))
            }
        }
    }

    @Test fun returnedPayloadDoesNotShareTheMutableHydratedArray() = runTest {
        withPointFixture { f ->
            var hydrated: AggregateRow? = null
            val dao = PointProbeDao(f.database.facts()).also { it.aggregateRow = { row -> hydrated = row; row } }
            f.withPointAccess(dao) { access ->
                val stored = assertNotNull(access.aggregate())
                assertNotNull(hydrated).payload.fill(0)
                assertContentEquals("2".encodeToByteArray(), stored.payload.copyBytes())
                assertEquals(sha256(stored.payload.copyBytes()), stored.digest)
            }
        }
    }

    @Test fun claimBlobOwnerCannotMasqueradeAsTheExpectedTextUuid() = runTest {
        withPointFixture { f ->
            f.corruptPointClaim("ownerId=CAST(ownerId AS BLOB)")
            f.withSqlPoint { connection ->
                connection.usePrepared("SELECT typeof(ownerId), length(ownerId) FROM unique_claim") {
                    assertTrue(it.step()); assertEquals("blob", it.getText(0)); assertEquals(36L, it.getLong(1))
                }
            }
            assertClaimPreflightRefusal(f)
        }
    }

    @Test fun claimNulAndOversizedOwnersAreRejectedBeforeHydration() = runTest {
        for (owner in listOf(POINT_KEY.id.value.dropLast(1) + '\u0000', POINT_KEY.id.value + '\u0000' + "x".repeat(200))) {
            withPointFixture { f ->
                f.corruptPointClaim("ownerId=?") { bindText(1, owner) }
                f.withSqlPoint { connection ->
                    connection.usePrepared("SELECT length(CAST(ownerId AS BLOB)), instr(CAST(ownerId AS BLOB), x'00') FROM unique_claim") {
                        assertTrue(it.step()); assertEquals(owner.length.toLong(), it.getLong(0)); assertTrue(it.getLong(1) > 0)
                    }
                }
                assertClaimPreflightRefusal(f)
            }
        }
    }

    @Test fun claimOwnerMustBeCanonicalEvenWhenLengthAndStorageTypeAreCorrect() = runTest {
        withPointFixture { f ->
            f.corruptPointClaim("ownerId=?") { bindText(1, "FFFFFFFF-FFFF-4FFF-8FFF-FFFFFFFFFFFF") }
            val dao = PointProbeDao(f.database.facts())
            f.withPointAccess(dao) { access -> assertPointUnavailable { access.claim() } }
            assertEquals(1, dao.count(PointQuery.CLAIM_ROW))
        }
    }

    @Test fun hydratedClaimRequiresExactScopeNamespaceKeyAndCanonicalOwner() = runTest {
        withPointFixture { f ->
            val changes: List<(ClaimRow) -> ClaimRow> = listOf(
                { it.copy(scope = TEST_SCOPE.copy(branchId = testId(991)).storageKey()) },
                { it.copy(namespace = "qualification.other") }, { it.copy(key = "another-key") },
                { it.copy(ownerId = "PRIVATE invalid owner") },
            )
            for (change in changes) {
                val dao = PointProbeDao(f.database.facts()).also { it.claimRow = { row -> change(assertNotNull(row)) } }
                f.withPointAccess(dao) { access -> assertPointUnavailable { access.claim() } }
                assertEquals(1, dao.count(PointQuery.CLAIM_ROW))
            }
        }
    }

    @Test fun exactMaximumClaimKeyIsNotTruncatedOrNormalized() = runTest {
        withPointFixture { f ->
            val key = ClaimKey(POINT_CLAIM.namespace, "x".repeat(256))
            f.withSqlPoint {
                f.database.facts().insertClaim(ClaimRow(TEST_SCOPE.storageKey(), key.namespace, key.key, POINT_KEY.id.value))
            }
            f.withPointAccess { assertEquals(POINT_KEY.id, it.claim(key)) }
        }
    }

    @Test fun expiredViewFailsBeforeAnyQueryAndAfterEachSuspendingQuery() = runTest {
        withPointFixture { f ->
            val closedDao = PointProbeDao(f.database.facts())
            val closed = PointAccess(closedDao).also { it.active = false }
            assertFailsWith<IllegalStateException> { closed.aggregate() }
            assertFailsWith<IllegalStateException> { closed.claim() }
            assertTrue(closedDao.queries.isEmpty())
            for (cut in PointQuery.entries) {
                lateinit var access: PointAccess
                val dao = PointProbeDao(f.database.facts()).also { probe ->
                    probe.after = { query -> yield(); if (query == cut) access.active = false }
                }
                f.withPointAccess(dao) {
                    access = it
                    val failure = assertFailsWith<IllegalStateException> { access.readFor(cut) }
                    assertEquals(POINT_EXPIRED, failure.message)
                    assertEquals(cut, dao.queries.last())
                }
            }
        }
    }

    @Test fun expiryAfterAbsentMetadataCannotPublishNull() = runTest {
        withPointFixture { f ->
            for (cut in listOf(PointQuery.AGGREGATE_METADATA, PointQuery.CLAIM_METADATA)) {
                lateinit var access: PointAccess
                val dao = PointProbeDao(f.database.facts()).also { probe ->
                    probe.aggregateMetadata = { null }; probe.claimMetadata = { null }
                    probe.after = { yield(); access.active = false }
                }
                f.withPointAccess(dao) {
                    access = it
                    val failure = assertFailsWith<IllegalStateException> { access.readFor(cut) }
                    assertEquals(POINT_EXPIRED, failure.message)
                }
                assertEquals(listOf(cut), dao.queries)
            }
        }
    }

    @Test fun originalCancellationIsPropagatedAtAllFourDaoBoundaries() = runTest {
        withPointFixture { f ->
            for (cut in PointQuery.entries) {
                val cancelled = CancellationException("synthetic original cancellation")
                val dao = PointProbeDao(f.database.facts()).also { probe ->
                    probe.before = { if (it == cut) throw cancelled }
                }
                f.withPointAccess(dao) { access ->
                    assertSame(cancelled, assertFailsWith<CancellationException> { access.readFor(cut) })
                }
                assertEquals(cut, dao.queries.last())
            }
        }
    }

    @Test fun daoExceptionsAreSanitizedWithoutTheirPrivateCauseAtEveryBoundary() = runTest {
        withPointFixture { f ->
            for (cut in PointQuery.entries) {
                val dao = PointProbeDao(f.database.facts()).also { probe ->
                    probe.before = { if (it == cut) throw IllegalArgumentException("PRIVATE SQL/PAYLOAD", IllegalStateException("PRIVATE cause")) }
                }
                f.withPointAccess(dao) { access -> assertPointUnavailable { access.readFor(cut) } }
                assertEquals(cut, dao.queries.last())
            }
        }
    }

    @Test fun cancelledJobCannotPublishEvenWhenTheDaoReturnsNormally() = runTest {
        withPointFixture { f ->
            for (cut in PointQuery.entries) {
                var published = false
                val dao = PointProbeDao(f.database.facts()).also { probe ->
                    probe.after = { if (it == cut) currentCoroutineContext().job.cancel(CancellationException("synthetic job cancellation")) }
                }
                f.withPointAccess(dao) { access ->
                    val isolatedJob = Job()
                    try {
                        assertFailsWith<CancellationException> {
                            withContext(isolatedJob) {
                                access.readFor(cut)
                                published = true // A withContext-only exit check must NOT make this test pass.
                            }
                        }
                    } finally { isolatedJob.cancel() }
                }
                assertFalse(published)
                assertEquals(cut, dao.queries.last())
            }
        }
    }
}

private val POINT_KEY = AggregateKey("qualification.counter", testId(10))
private val POINT_CLAIM = ClaimKey("qualification.custody", "point-custody")
private const val POINT_EXPIRED = "Synthetic current view expired"

private enum class PointQuery { AGGREGATE_METADATA, AGGREGATE_ROW, CLAIM_METADATA, CLAIM_ROW }

private class PointAccess(private val dao: MasterDao) {
    var active = true
    private fun checkActive() { check(active) { POINT_EXPIRED } }
    suspend fun aggregate(key: AggregateKey = POINT_KEY): StoredAggregate? =
        readCurrentAggregate(dao, TEST_SCOPE, key, ::checkActive)
    suspend fun claim(key: ClaimKey = POINT_CLAIM): DomainId? =
        readCurrentClaim(dao, TEST_SCOPE, key, ::checkActive)
    suspend fun readFor(query: PointQuery): Any? = when (query) {
        PointQuery.AGGREGATE_METADATA, PointQuery.AGGREGATE_ROW -> aggregate()
        PointQuery.CLAIM_METADATA, PointQuery.CLAIM_ROW -> claim()
    }
}

/** Counts actual full-row DAO calls separately from scalar-only metadata queries. */
private class PointProbeDao(private val base: MasterDao) : MasterDao by base {
    val queries = mutableListOf<PointQuery>()
    var before: suspend (PointQuery) -> Unit = {}
    var after: suspend (PointQuery) -> Unit = {}
    var aggregateMetadata: (CurrentAggregateMetadata?) -> CurrentAggregateMetadata? = { it }
    var aggregateRow: (AggregateRow?) -> AggregateRow? = { it }
    var claimMetadata: (Boolean?) -> Boolean? = { it }
    var claimRow: (ClaimRow?) -> ClaimRow? = { it }
    fun count(query: PointQuery): Int = queries.count { it == query }
    override suspend fun currentAggregateMetadata(scope: String, kind: String, id: String): CurrentAggregateMetadata? {
        val query = PointQuery.AGGREGATE_METADATA; queries += query; before(query)
        val value = base.currentAggregateMetadata(scope, kind, id); after(query)
        return aggregateMetadata(value)
    }
    override suspend fun aggregate(scope: String, kind: String, id: String): AggregateRow? {
        val query = PointQuery.AGGREGATE_ROW; queries += query; before(query)
        val value = base.aggregate(scope, kind, id); after(query)
        return aggregateRow(value)
    }
    override suspend fun currentClaimStorageValid(scope: String, namespace: String, key: String): Boolean? {
        val query = PointQuery.CLAIM_METADATA; queries += query; before(query)
        val value = base.currentClaimStorageValid(scope, namespace, key); after(query)
        return claimMetadata(value)
    }
    override suspend fun claim(scope: String, namespace: String, key: String): ClaimRow? {
        val query = PointQuery.CLAIM_ROW; queries += query; before(query)
        val value = base.claim(scope, namespace, key); after(query)
        return claimRow(value)
    }
}

private suspend fun withPointFixture(action: suspend (StoreFixture) -> Unit) {
    val f = StoreFixture()
    try {
        f.seed()
        val principal = testPrincipal()
        val store = f.store()
        assertTrue(assertIs<MutationReply.Recorded>(store.execute(principal, f.request(980, CounterInput(claim = POINT_CLAIM.key)))).committed)
        assertTrue(assertIs<MutationReply.Recorded>(store.execute(principal, f.request(981, CounterInput(expectedRevision = Revision(1))))).committed)
        action(f)
    } finally { f.close() }
}

private suspend fun <T> StoreFixture.withSqlPoint(action: suspend (PooledConnection) -> T): T =
    database.useWriterConnection { connection ->
        requireDurableWriter(connection)
        connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) { action(connection) }
    }

private suspend fun <T> StoreFixture.withPointAccess(
    dao: MasterDao = database.facts(),
    action: suspend (PointAccess) -> T,
): T = withSqlPoint {
    val access = PointAccess(dao)
    try { action(access) } finally { access.active = false }
}

private suspend fun StoreFixture.replacePointPayload(payload: ByteArray) = withSqlPoint {
    val dao = database.facts()
    val row = assertNotNull(dao.aggregate(TEST_SCOPE.storageKey(), POINT_KEY.kind, POINT_KEY.id.value))
    assertEquals(1, dao.replaceAggregate(row.scope, row.kind, row.id, row.revision, row.revision + 1,
        row.schema, payload, sha256(payload).value))
}

private suspend fun assertPointBlobRoundTrip(payload: ByteArray) {
    withPointFixture { f ->
        f.replacePointPayload(payload)
        val dao = PointProbeDao(f.database.facts())
        f.withPointAccess(dao) { access ->
            val row = assertNotNull(access.aggregate())
            assertEquals(Revision(3), row.revision)
            assertContentEquals(payload, row.payload.copyBytes())
            assertEquals(sha256(payload), row.digest)
        }
        assertEquals(listOf(PointQuery.AGGREGATE_METADATA, PointQuery.AGGREGATE_ROW), dao.queries)
    }
}

private suspend fun assertAggregatePreflightRefusal(f: StoreFixture) {
    val dao = PointProbeDao(f.database.facts())
    f.withPointAccess(dao) { access -> assertPointUnavailable { access.aggregate() } }
    assertEquals(listOf(PointQuery.AGGREGATE_METADATA), dao.queries)
}

private suspend fun assertClaimPreflightRefusal(f: StoreFixture) {
    val dao = PointProbeDao(f.database.facts())
    f.withPointAccess(dao) { access -> assertPointUnavailable { access.claim() } }
    assertEquals(listOf(PointQuery.CLAIM_METADATA), dao.queries)
}

private suspend fun assertPointUnavailable(read: suspend () -> Unit) {
    val failure = assertFailsWith<IllegalStateException> { read() }
    assertEquals("Current stored state unavailable", failure.message)
    assertNull(failure.cause)
    assertTrue(failure.suppressedExceptions.isEmpty())
}

private suspend fun assertPointSqlType(f: StoreFixture, column: String, expected: String) {
    require(column in setOf("revision", "payload", "schema", "payloadHash"))
    f.withSqlPoint { connection ->
        connection.usePrepared("SELECT typeof($column) FROM aggregate_state WHERE kind='qualification.counter'") {
            assertTrue(it.step()); assertEquals(expected, it.getText(0)); assertFalse(it.step())
        }
    }
}

/** Deliberate corruption is confined to one disposable file; the exact existing trigger is restored. */
private suspend fun StoreFixture.corruptPointAggregate(assignment: String, bind: SQLiteStatement.() -> Unit = {}) = withSqlPoint { connection ->
    val guard = connection.usePrepared("SELECT sql FROM sqlite_master WHERE type='trigger' AND name='aggregate_revision_update'") {
        assertTrue(it.step()); it.getText(0)
    }
    connection.usePrepared("DROP TRIGGER aggregate_revision_update") { it.step() }
    try {
        connection.usePrepared("UPDATE aggregate_state SET $assignment WHERE scope='${TEST_SCOPE.storageKey()}' AND kind='${POINT_KEY.kind}' AND id='${POINT_KEY.id.value}'") {
            it.bind(); it.step()
        }
    } finally {
        connection.usePrepared(guard) { it.step() }
    }
    connection.usePrepared("SELECT sql FROM sqlite_master WHERE type='trigger' AND name='aggregate_revision_update'") {
        assertTrue(it.step()); assertEquals(guard, it.getText(0))
    }
}

private suspend fun StoreFixture.corruptPointClaim(assignment: String, bind: SQLiteStatement.() -> Unit = {}) = withSqlPoint { connection ->
    connection.usePrepared("UPDATE unique_claim SET $assignment WHERE scope='${TEST_SCOPE.storageKey()}' AND namespace='${POINT_CLAIM.namespace}' AND `key`='${POINT_CLAIM.key}'") {
        it.bind(); it.step()
    }
}

private suspend fun PooledConnection.pointChanges(): Long = usePrepared("SELECT total_changes()") {
    assertTrue(it.step()); it.getLong(0)
}
