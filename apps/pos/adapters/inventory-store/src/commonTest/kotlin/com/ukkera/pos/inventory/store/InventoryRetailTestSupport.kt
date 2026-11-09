package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.ClaimChange
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.RetainedClaimAction
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.inventory.CollectionBinding
import com.ukkera.pos.inventory.CollectionExposure
import com.ukkera.pos.inventory.InventoryPlan
import com.ukkera.pos.inventory.InventoryPlanner
import com.ukkera.pos.inventory.InventoryPostingBatch
import com.ukkera.pos.inventory.InventorySnapshot
import com.ukkera.pos.inventory.InventorySnapshotCodec
import com.ukkera.pos.inventory.StockAllocation
import com.ukkera.pos.inventory.StockPositionKey
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Pure scalar seams only. No real read view/evidence, authorization or sales-bypassing handlers. */
internal object RetailFixtures {
    val scope = ReceiptFixtures.scope
    val actor = ReceiptFixtures.actor
    val unit = ReceiptFixtures.unit
    const val now: Long = 100
    val compositeCommand = CommandKey("qualification.inventory.retail.composite", 1)
    val compositeBody = StoredBytes("synthetic-composite-body".encodeToByteArray())
    fun id(n: Int): DomainId = ReceiptFixtures.id(n)
    fun op(n: Int): OperationId = ReceiptFixtures.op(n)
    fun request(n: Int = 1, revision: Revision = Revision(0)): InventoryFixtureReceiveRequest = ReceiptFixtures.request(n, revision)

    fun context(key: CommandKey, body: StoredBytes, operation: OperationId, scope: BranchScope = this.scope): ReceiptContext =
        ReceiptContext(scope, actor, operation, key, semanticDigest(scope, actor, key, body))

    fun receiveContext(request: InventoryRetailReceiveBatch, operation: OperationId = op(1_001)): ReceiptContext =
        context(InventoryRetailStoreProfile.RECEIVE_COMMAND, InventoryRetailCommandCodec.encodeReceive(request), operation)

    fun planContext(plan: InventoryPlan): ReceiptContext = context(compositeCommand, compositeBody, plan.operationId)

    fun genesis(): InventoryRetailFixtureProposal = fixtureRetailGenesis(context(InventoryRetailStoreProfile.GENESIS_COMMAND,
        InventoryRetailCommandCodec.genesisRequest(), op(999)))

    fun current(proposal: InventoryRetailFixtureProposal): RetailObservedPair = current(proposal.contribution)

    fun current(contribution: InventoryRetailContribution): RetailObservedPair {
        val rows = contribution.changes.map { change -> ReceiptStoredRow(change.key, (change.expectedRevision ?: Revision(0)).next(),
            change.schema, change.payload, sha256(change.payload.copyBytes())) }
        return decodeRetailPair(scope, rows.single { it.key.kind == InventoryRetailStoreProfile.CUT_KIND },
            rows.single { it.key.kind == InventoryRetailStoreProfile.COMPANION_KIND })
    }

    fun receive(current: RetailObservedPair, receipts: List<InventoryFixtureReceiveRequest> = listOf(request(1, current.snapshot.revision)),
        operation: OperationId = op(1_001)): InventoryRetailFixtureProposal {
        val request = InventoryRetailReceiveBatch(receipts)
        val context = receiveContext(request, operation)
        val plan = InventoryPlanner.receive(current.snapshot, request.expectedRevision, operation, request.receipts.map { it.toReceipt(scope) })
        return fixtureRetailReceive(current, context, request, plan, now)
    }

    fun one(request: InventoryFixtureReceiveRequest = request()): RetailObservedPair = current(receive(current(genesis()), listOf(request)))

    fun project(current: RetailObservedPair, plan: InventoryPlan, time: Long = now,
        receipts: List<InventoryFixtureReceiveRequest> = emptyList()): InventoryRetailContribution =
        projectRetailContribution(current, planContext(plan), plan, time, receipts)

    fun binding(n: Int = 1, exposure: CollectionExposure = CollectionExposure.PAID): CollectionBinding =
        CollectionBinding(id(2_000 + n), Revision(1), id(3_000 + n), actor, exposure)

    fun allocation(n: Int, position: StockPositionKey, scaled: Long): StockAllocation =
        StockAllocation(id(4_000 + n), id(5_000 + n), position, Quantity(unit, scaled, 0))

    fun reserve(current: RetailObservedPair, n: Int = 1, allocations: List<StockAllocation> =
        listOf(allocation(n, current.snapshot.positions.first().key, 1)), expires: Long? = null,
        exposure: CollectionExposure = CollectionExposure.PAID): InventoryPlan = InventoryPlanner.reserve(current.snapshot, current.snapshot.revision,
        op(6_000 + n), id(7_000 + n), binding(n, exposure), allocations, now, expires).plan

    fun rows(contribution: InventoryRetailContribution): List<ReceiptStoredRow> = contribution.changes.map {
        ReceiptStoredRow(it.key, (it.expectedRevision ?: Revision(0)).next(), it.schema, it.payload, sha256(it.payload.copyBytes()))
    }

    fun retained(proposal: InventoryRetailFixtureProposal, normalized: StoredBytes): RetailRetainedFacts = RetailRetainedFacts(true,
        proposal.contribution.companion.scope, proposal.contribution.companion.producer, normalized, proposal.result, rows(proposal.contribution),
        proposal.contribution.claims.map { RetailRetainedClaim(if (it is ClaimChange.Acquire) RetainedClaimAction.ACQUIRE else RetainedClaimAction.RELEASE,
            it.key, it.ownerId) }, emptyList())

    fun withCompanion(current: RetailObservedPair, companion: InventoryRetailCompanion): RetailObservedPair {
        val bytes = InventoryRetailCompanionCodec.encode(companion)
        return decodeRetailPair(current.scope, current.cutRow, current.companionRow.copy(payload = bytes, digest = sha256(bytes.copyBytes())))
    }

    fun withSnapshot(current: RetailObservedPair, snapshot: InventorySnapshot): RetailObservedPair {
        val bytes = StoredBytes(InventorySnapshotCodec.encode(snapshot).encodeToByteArray())
        val companion = copyRetailCompanion(current.companion, cutDigest = sha256(bytes.copyBytes()))
        val encoded = InventoryRetailCompanionCodec.encode(companion)
        return decodeRetailPair(current.scope, current.cutRow.copy(payload = bytes, digest = sha256(bytes.copyBytes())),
            current.companionRow.copy(payload = encoded, digest = sha256(encoded.copyBytes())))
    }
}

internal fun copyRetailCompanion(
    value: InventoryRetailCompanion,
    scope: BranchScope = value.scope,
    id: DomainId = value.id,
    revision: Revision = value.inventoryRevision,
    cutDigest: PayloadDigest = value.cutDigest,
    producer: InventoryReceiptProducer = value.producer,
    previous: InventoryReceiptPreviousPair? = value.previous,
    receipts: List<InventoryReceiptSourceBinding> = value.receipts,
    issueSources: List<InventoryRetailIssueSourceBinding> = value.issueSources,
    latestBatch: InventoryPostingBatch? = value.latestBatch,
): InventoryRetailCompanion = InventoryRetailCompanion(scope, id, revision, cutDigest, producer, previous, receipts, issueSources, latestBatch)

internal fun retailBinding(companion: InventoryRetailCompanion): InventoryRetailCompanionBinding =
    InventoryRetailCompanionBinding(companion.scope, companion.id, companion.inventoryRevision)

internal fun retailFailure(expected: InventoryRetailStoreFailure? = null, block: () -> Unit): InventoryRetailStoreRejected =
    assertFailsWith<InventoryRetailStoreRejected>(block = block).also {
        if (expected != null) assertEquals(expected, it.failure)
        assertEquals(it.failure.name, it.message)
        assertNull(it.cause)
    }
