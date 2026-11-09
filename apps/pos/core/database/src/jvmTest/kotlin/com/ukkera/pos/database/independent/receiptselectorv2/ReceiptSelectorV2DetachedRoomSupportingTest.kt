package com.ukkera.pos.database.independent.receiptselectorv2

import androidx.room3.Room
import androidx.room3.Transactor
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteException
import com.ukkera.pos.database.AggregateKeyPage
import com.ukkera.pos.database.AggregateRow
import com.ukkera.pos.database.BranchRow
import com.ukkera.pos.database.ClaimKeyPage
import com.ukkera.pos.database.ClaimRow
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.MasterDao
import com.ukkera.pos.database.MasterRoomConstructor
import com.ukkera.pos.database.MasterRoomDatabase
import com.ukkera.pos.database.ReadContext
import com.ukkera.pos.database.RuntimeRetailCustomerReceiptEventLocator
import com.ukkera.pos.database.TransactionView
import com.ukkera.pos.database.buildDurableRoom
import com.ukkera.pos.database.queryDigest
import com.ukkera.pos.database.readCurrentClaimKeys
import com.ukkera.pos.database.requireDurableWriter
import com.ukkera.pos.database.storageKey
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptProfileV1
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptQueryV1
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptReadAccessV1
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptReadCodec
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptReadHandler
import com.ukkera.pos.security.OwnerConstraint
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Actual file-backed Room/SQLite and production detached TransactionView/read
 * preparation. Canonical projection copies are synthetic DATA only; no paid-sale
 * original, retained result/journal pair, employee, native owner or admission is
 * installed. There is no protected execute/read/retry/RPC or G513 durable corpus.
 * A test-only SQL claim-range DATA bridge reaches the missing-native-membership
 * refusal; it supplies no authority, completeness proof or native owner.
 * Same-process database reopen is not RSEL-05 Master termination/restart.
 */
class ReceiptSelectorV2DetachedRoomSupportingTest {
    private val f = FrozenReceiptSelectorV2Data
    private val bridge = ReceiptSelectorProductionDataBridge

    @Test
    fun rsel02_projectionCopiesDoNotBecomeNativeMembershipOrProvedAbsenceAcrossDetachedReopen() = runTest {
        val cut = f.selected()
        val room = ReceiptSelectorDetachedRoom.create(cut)
        try {
            room.seedProjectionCopies()
            val baseline = room.snapshot()
            repeat(2) {
                room.withView { connection, actual ->
                    val beforeChanges = connection.receiptSelectorTotalChanges()
                    assertEquals(3, actual.aggregateKeys(LocalRetailCustomerReceiptProfileV1.EVENT, limit = 65).keys.size)
                    for (operation in listOf("E1", "E2", "E3")) {
                        val expected = cut.rows.getValue(f.eventKey(f.operation(operation)))
                        val row = assertNotNull(actual.aggregate(expected.key))
                        assertEquals(expected.payload, row.payload)
                        assertEquals(expected.digest, row.digest)
                        assertNull(actual.retainedCommand(f.operation(operation)))
                    }
                    assertNull(actual.retailCustomerReceiptEventMembership(f.selectedSaleId))
                    assertNull(actual.retailCustomerReceiptEventMembership(f.id("N")))
                    // The detached view has no owned current authorization. Its claim
                    // range guard refuses before the selector can consume membership.
                    val refusal = assertFailsWith<IllegalStateException> { bridge.findHead(actual, cut.original) }
                    assertEquals(IllegalStateException::class.java, refusal.javaClass)
                    assertEquals("Protected local record unavailable", refusal.message)
                    assertEquals(beforeChanges, connection.receiptSelectorTotalChanges())
                }
                // The guard above marks its view failed. This is a separate fresh
                // detached view on the same file, not a revived or admitted view.
                room.withView { connection, actual ->
                    val beforeChanges = connection.receiptSelectorTotalChanges()
                    val data = room.selectedClaimRangeDataView(connection, actual)
                    assertReceiptSourceUnavailable { bridge.findHead(data, cut.original) }
                    assertEquals(listOf(f.membershipPrefix(f.selectedSaleId) to 65), data.claimRanges)
                    val selectedClaims = assertNotNull(data.selectedClaimPage)
                    assertNull(selectedClaims.nextAfter)
                    assertEquals(cut.observations.map { f.membershipClaim(f.selectedSaleId, it.operation) to f.selectedSaleId }
                        .sortedBy { it.first.key }, selectedClaims.entries.map { it.key to it.ownerId })
                    assertEquals(1, data.membershipReads)
                    assertEquals(beforeChanges, connection.receiptSelectorTotalChanges())
                }
                assertEquals(baseline, room.snapshot())
                if (it == 0) room.reopen()
            }
        } finally { room.close() }
    }

    @Test
    fun rsel03_duplicateCanonicalE2ProjectionIsActuallyPreventedByDurableIdentityGuards() = runTest {
        val cut = f.selected()
        val room = ReceiptSelectorDetachedRoom.create(cut)
        try {
            room.seedProjectionCopies()
            val before = room.snapshot()
            room.withView { connection, _ ->
                val changes = connection.receiptSelectorTotalChanges()
                assertFailsWith<SQLiteException> { room.insertDuplicateEvent() }
                assertEquals(changes, connection.receiptSelectorTotalChanges())
            }
            assertEquals(before, room.snapshot())
            // This is genuine prevention by the existing SQL identity guard, NOT
            // a passing execution of the selector's duplicate-evidence branch.
        } finally { room.close() }
    }

    @Test
    fun rsel06_unavailableReadPreparationRetainsExplicitPurposeAndNeedsAuthorityToProject() = runTest {
        val room = ReceiptSelectorDetachedRoom.create(f.selected())
        try {
            room.seedProjectionCopies()
            val before = room.snapshot()
            val handler = LocalRetailCustomerReceiptReadHandler()
            val queries = listOf(
                LocalRetailCustomerReceiptQueryV1.Head(f.selectedSaleId),
                LocalRetailCustomerReceiptQueryV1.Head(f.selectedSaleId, LocalRetailCustomerReceiptReadAccessV1.RECORD),
                LocalRetailCustomerReceiptQueryV1.Head(f.selectedSaleId, LocalRetailCustomerReceiptReadAccessV1.RETURN_LOOKUP),
                LocalRetailCustomerReceiptQueryV1.Event(f.selectedSaleId, f.operation("E1")),
                LocalRetailCustomerReceiptQueryV1.Event(f.selectedSaleId, f.operation("E1"), LocalRetailCustomerReceiptReadAccessV1.RECORD),
                LocalRetailCustomerReceiptQueryV1.Event(f.selectedSaleId, f.operation("E1"), LocalRetailCustomerReceiptReadAccessV1.RETURN_LOOKUP),
            )
            room.withView { connection, actual ->
                val changes = connection.receiptSelectorTotalChanges()
                for (query in queries) {
                    val access = when (query) {
                        is LocalRetailCustomerReceiptQueryV1.Head -> query.access
                        is LocalRetailCustomerReceiptQueryV1.Event -> query.access
                    }
                    val normalized = handler.normalize(LocalRetailCustomerReceiptReadCodec.encode(query))
                    assertEquals(query, LocalRetailCustomerReceiptReadCodec.decodeQuery(normalized))
                    val context = ReadContext(f.scope, f.actorD, handler.key,
                        queryDigest(f.scope, f.actorD, handler.key, normalized))
                    val prepared = handler.prepare(normalized, actual, context)
                    assertEquals(1, prepared.requirements.size)
                    val requirement = prepared.requirements.single()
                    assertEquals(Permission(if (access == LocalRetailCustomerReceiptReadAccessV1.RECORD)
                        SecurityAction.CUSTOMER_RECEIPT_RECORD else SecurityAction.RETURN_RECEIPT_LOOKUP,
                        PermissionVerb.PERFORM), requirement.permission)
                    assertEquals(f.scope, requirement.scope)
                    assertEquals(f.selectedSaleId, assertNotNull(requirement.binding).documentId)
                    assertEquals(Revision(0), assertNotNull(requirement.binding).revision)
                    if (access == LocalRetailCustomerReceiptReadAccessV1.RETURN_LOOKUP) {
                        assertEquals(OwnerConstraint.Unrestricted, requirement.owner)
                    } else {
                        // No verified original exists in this fixture. The refusing
                        // preparation binds the requesting actor, not a fabricated collector.
                        assertEquals(OwnerConstraint.OwnerPrivate(f.actorD), requirement.owner)
                    }
                    val failure = assertFailsWith<IllegalStateException> {
                        prepared.project(emptyList(), f.instant("2026-11-03T11:00:00Z"))
                    }
                    assertEquals("sales.customer_receipt.authorization", failure.message)
                }
                assertEquals(changes, connection.receiptSelectorTotalChanges())
            }
            assertEquals(before, room.snapshot())
            // No current enabled/session/device/policy decision, ANY_OWNER denial,
            // documentary success or revocation race is qualified by preparation.
        } finally { room.close() }
    }
}

private class ReceiptSelectorDetachedRoom private constructor(
    private val directory: Path,
    private val path: Path,
    private val cut: ReceiptSelectorCutData,
    private var database: MasterRoomDatabase,
) : AutoCloseable {
    suspend fun seedProjectionCopies() = withConnection { connection ->
        connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
            val dao = database.facts()
            // An invalid nonwritable FK parent, never a fabricated native appointment.
            dao.insertBranch(BranchRow(cut.sale.scope.storageKey(), "", "", 0, 0, 0, "", false, ""))
            // Copy projections through their actual initial/incrementing SQL guards.
            // This does not execute the receipt command or install retained originals.
            val generations = mutableMapOf<com.ukkera.pos.database.AggregateKey, Revision>()
            for (input in cut.observations) {
                for (write in cut.originals.getValue(input.operation).aggregateWrites) {
                    val previous = generations[write.key]
                    if (previous == null) {
                        assertEquals(Revision(1), write.storageGeneration)
                        dao.insertAggregate(AggregateRow(cut.sale.scope.storageKey(), write.key.kind, write.key.id.value,
                            write.storageGeneration.value, write.schema, write.payload.copyBytes(), write.payloadDigest.value))
                    } else {
                        assertEquals(previous.next(), write.storageGeneration)
                        assertEquals(1, dao.replaceAggregate(cut.sale.scope.storageKey(), write.key.kind, write.key.id.value,
                            previous.value, write.storageGeneration.value, write.schema, write.payload.copyBytes(), write.payloadDigest.value))
                    }
                    generations[write.key] = write.storageGeneration
                }
            }
            for ((key, owner) in cut.claims) dao.insertClaim(ClaimRow(cut.sale.scope.storageKey(), key.namespace, key.key, owner.value))
        }
    }

    /** Called inside the existing loan/transaction; no nested writer is borrowed. */
    suspend fun insertDuplicateEvent() {
        val row = cut.rows.getValue(FrozenReceiptSelectorV2Data.eventKey(FrozenReceiptSelectorV2Data.operation("E2")))
        database.facts().insertAggregate(AggregateRow(cut.sale.scope.storageKey(), row.key.kind, row.key.id.value,
            row.revision.value, row.schema, row.payload.copyBytes(), row.digest.value))
    }

    suspend fun <T> withView(block: suspend (Transactor, TransactionView) -> T): T = withConnection { connection ->
        connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
            val view = TransactionView(cut.sale.scope, database.facts())
            try { block(connection, view) } finally { view.close() }
        }
    }

    suspend fun selectedClaimRangeDataView(connection: Transactor, actual: TransactionView): ReceiptSelectorDetachedClaimRangeDataView {
        check(connection.inTransaction() && actual.scope == cut.sale.scope)
        return ReceiptSelectorDetachedClaimRangeDataView(cut.sale.saleId, actual, database.facts(), connection)
    }

    suspend fun snapshot(): Map<String, List<List<String>>> = withConnection { connection ->
        listOf("branch_authority", "aggregate_state", "unique_claim", "operation_result", "journal_envelope", "audit_event", "outbox_intent")
            .associateWith { table ->
                val columns = connection.usePrepared("PRAGMA table_info($table)") { statement ->
                    buildList { while (statement.step()) add(statement.getText(1)) }
                }
                assertTrue(columns.isNotEmpty() && columns.all { column -> column.all { it.isLetterOrDigit() || it == '_' } })
                val quoted = columns.joinToString(",") { "quote(\"$it\")" }
                connection.usePrepared("SELECT $quoted FROM $table ORDER BY $quoted") { statement ->
                    buildList { while (statement.step()) add(columns.indices.map { statement.getText(it) }) }
                }
            }
    }

    fun reopen() {
        database.close()
        database = open(path)
    }

    private suspend fun <T> withConnection(block: suspend (Transactor) -> T): T =
        database.useWriterConnection { connection ->
            requireDurableWriter(connection)
            block(connection)
        }

    override fun close() {
        try { database.close() } finally { directory.toFile().deleteRecursively() }
    }

    companion object {
        fun create(cut: ReceiptSelectorCutData): ReceiptSelectorDetachedRoom {
            val directory = Files.createTempDirectory("receipt-selector-detached-")
            val path = directory.resolve("fixture.sqlite")
            return try { ReceiptSelectorDetachedRoom(directory, path, cut, open(path)) }
            catch (failure: Throwable) { directory.toFile().deleteRecursively(); throw failure }
        }

        private fun open(path: Path): MasterRoomDatabase = buildDurableRoom(
            Room.databaseBuilder<MasterRoomDatabase>(path.toString(), MasterRoomConstructor::initialize))
    }
}

/**
 * Test-only DATA bridge for the exact persisted selected TEXT claim range in the
 * caller's live IMMEDIATE transaction. The production owned-range guard is tested
 * separately. This scalar SQL read grants no authority or native completeness;
 * aggregate, claim, retained-original and native membership calls stay delegated
 * to the real detached view, whose native membership must remain unavailable.
 */
private class ReceiptSelectorDetachedClaimRangeDataView(
    private val selectedSaleId: DomainId,
    private val actual: TransactionView,
    private val dao: MasterDao,
    private val connection: Transactor,
) : CommandReadView by actual {
    val claimRanges = mutableListOf<Pair<String, Int>>()
    var selectedClaimPage: ClaimKeyPage? = null
        private set
    var membershipReads = 0
        private set

    override suspend fun claimKeys(namespace: String, prefix: String, after: String?, limit: Int): ClaimKeyPage {
        check(namespace == LocalRetailCustomerReceiptProfileV1.NAMESPACE &&
            prefix == FrozenReceiptSelectorV2Data.membershipPrefix(selectedSaleId) && after == null && limit == 65)
        claimRanges += prefix to limit
        check(connection.inTransaction())
        val page = readCurrentClaimKeys(dao, scope, namespace, prefix, after, limit) {
            check(actual.scope == scope)
        }
        check(connection.inTransaction())
        return page.also { selectedClaimPage = it }
    }

    override suspend fun retailCustomerReceiptEventMembership(saleId: DomainId): List<RuntimeRetailCustomerReceiptEventLocator>? {
        check(saleId == selectedSaleId)
        membershipReads++
        return actual.retailCustomerReceiptEventMembership(saleId)
    }

    override suspend fun aggregateKeys(kind: String, after: DomainId?, limit: Int): AggregateKeyPage =
        error("Global aggregate census is not a selected SQL DATA read")
}

private suspend fun Transactor.receiptSelectorTotalChanges(): Long = usePrepared("SELECT total_changes()") {
    check(it.step()); it.getLong(0)
}
