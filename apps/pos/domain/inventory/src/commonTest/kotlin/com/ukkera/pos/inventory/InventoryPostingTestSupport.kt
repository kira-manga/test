package com.ukkera.pos.inventory

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

internal fun postingBinding(plan: InventoryPlan): InventoryPostingBinding = InventoryPostingBinding(plan.sourceSnapshot.scope,
    plan.operationId, plan.sourceSnapshot.revision, plan.sourceSnapshot.revision.next())

internal fun postingCapture(plan: InventoryPlan, now: Long = 1): InventoryPostingBatch =
    InventoryPosting.fromPlan(plan.sourceSnapshot, plan, postingBinding(plan), now)

internal fun postingRoundTrip(plan: InventoryPlan, now: Long = 1): InventoryPostingBatch {
    val batch = postingCapture(plan, now)
    val encoded = InventoryPostingCodec.encode(batch)
    val decoded = InventoryPostingCodec.decode(encoded, postingBinding(plan))
    assertEquals(encoded, InventoryPostingCodec.encode(decoded))
    assertEquals(batch.binding, decoded.binding)
    assertEquals(batch.effects.size, decoded.effects.size)
    return decoded
}

internal fun postingFailure(failure: InventoryPostingFailure? = null, action: () -> Unit): InventoryPostingRejectedException {
    val result = assertFailsWith<InventoryPostingRejectedException>(block = action)
    if (failure != null) assertEquals(failure, result.failure)
    assertEquals(result.failure.name, result.message)
    assertEquals(null, result.cause)
    return result
}

internal fun postingDecodeFailure(plan: InventoryPlan, text: String, failure: InventoryPostingFailure? = null) {
    postingFailure(failure) { InventoryPostingCodec.decode(text, postingBinding(plan)) }
}

internal class PostingFixture {
    val ids = IdSequence(8_000)
    val key = StockPositionKey(pool, id(800))
    var snapshot: InventorySnapshot = InventorySnapshot.empty(scope)

    fun receipts(receipts: List<StockReceipt>): InventoryPlan = InventoryPlanner.receive(snapshot, snapshot.revision,
        ids.operation(), receipts).also { snapshot = it.nextSnapshot }

    fun receive(q: Long = 10, value: Long = 100, at: StockPositionKey = key, expires: Long? = null): InventoryPlan =
        receipts(listOf(StockReceipt(ids.next(), at, quantity(q), money(value), expires)))

    fun allocation(q: Long = 1, at: StockPositionKey = key): StockAllocation = StockAllocation(ids.next(), ids.next(), at, quantity(q))

    fun reserve(allocations: List<StockAllocation> = listOf(allocation()), exposure: CollectionExposure = CollectionExposure.UNKNOWN,
        expires: Long? = null): ReservationPlan = InventoryPlanner.reserve(snapshot, snapshot.revision, ids.operation(), ids.next(),
        binding(exposure), allocations, 1, expires).also { snapshot = it.plan.nextSnapshot }

    fun issue(reservationId: DomainId, now: Long = 1, recipe: RecipeIssueRequest? = null): ReservationIssueResult {
        val result = InventoryPlanner.issueReservation(snapshot, snapshot.revision, ids.operation(), reservationId,
            snapshot.reservation(reservationId)!!.revision, ids.next(), now,
            if (recipe == null) ValueIssuePurpose.RETAIL else ValueIssuePurpose.DIRECT_RECIPE, recipe)
        snapshot = postingPlan(result).nextSnapshot
        return result
    }

    fun noop(): InventoryPlan = InventoryPlanner.expireReservations(snapshot, snapshot.revision, ids.operation(), 1)
}

internal fun postingPlan(result: ReservationIssueResult): InventoryPlan = when (result) {
    is ReservationIssueResult.Issued -> result.plan
    is ReservationIssueResult.Held -> result.plan
}

internal fun postingForge(
    original: InventoryPlan,
    source: InventorySnapshot = original.sourceSnapshot,
    next: InventorySnapshot = original.nextSnapshot,
    effects: List<InventoryEffect> = original.effects,
    operationId: OperationId = original.operationId,
): InventoryPlan = InventoryPlan(operationId, source, next, effects, original.mustCommitBeforeEpochMillis)

internal fun postingSnapshotCopy(
    source: InventorySnapshot,
    positions: List<StockPosition> = source.positions,
    pools: List<OwnedValuationPool> = source.pools,
    reservations: List<StockReservation> = source.reservations,
    issues: List<StockIssue> = source.issues,
    operations: List<OperationId> = source.operationIds,
    sources: List<DomainId> = source.sourceIds,
    revision: Revision = source.revision,
): InventorySnapshot = InventorySnapshot(source.scope, revision, positions, pools, reservations, issues, operations, sources)

internal fun postingValueCopy(value: MovingAverageState): MovingAverageState = MovingAverageState(value.unitId, value.scale,
    value.currency, value.revision, value.onHand, value.carryingValue, value.facts, value.costTargets,
    value.linkedCorrectionBalances, value.adjustments, value.eventIds)

internal fun postingSimpleReceipt(): InventoryPlan = InventoryPlanner.receive(InventorySnapshot.empty(scope), Revision(0),
    OperationId(id(701).value), listOf(StockReceipt(id(702), StockPositionKey(pool, id(20)), quantity(3), money(100))))

internal fun postingIssueWithExpiry(): ReservationIssueResult.Issued {
    val f = PostingFixture()
    f.receive(expires = 100)
    val other = f.key.copy(lotId = id(801))
    f.receive(at = other, expires = 10)
    val target = f.reserve(listOf(f.allocation(2)))
    f.reserve(listOf(f.allocation(1, other)))
    return assertIs<ReservationIssueResult.Issued>(f.issue(target.proof.reservationId, 10))
}

internal data class PostingHeldFixture(val result: ReservationIssueResult.Held, val target: DomainId, val overlap: DomainId,
    val first: StockPositionKey, val second: StockPositionKey, val zero: StockPositionKey)

internal fun postingHeldFixture(): PostingHeldFixture {
    val f = PostingFixture()
    val second = f.key.copy(lotId = id(802))
    val zero = f.key.copy(lotId = id(803))
    f.receive(5, 500, expires = 10)
    f.receive(5, 500, second, 10)
    f.receive(1, 100, zero, 10)
    val zeroReservation = f.reserve(listOf(f.allocation(1, zero)))
    assertIs<ReservationIssueResult.Issued>(f.issue(zeroReservation.proof.reservationId))
    val target = f.reserve(listOf(f.allocation(2), f.allocation(2, second)))
    val overlap = f.reserve(listOf(f.allocation(1), f.allocation(1, second)))
    return PostingHeldFixture(assertIs<ReservationIssueResult.Held>(f.issue(target.proof.reservationId, 10)),
        target.proof.reservationId, overlap.proof.reservationId, f.key, second, zero)
}
