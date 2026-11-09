package com.ukkera.pos.inventory

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision

/** One independently cancellable portion. Allocations are authoritative, never recipe selectors from RPC. */
public class FoodIngredientIssue(
    public val reservationId: DomainId,
    public val issueId: DomainId,
    public val binding: CollectionBinding,
    allocations: List<StockAllocation>,
    public val recipe: RecipeIssueRequest,
) {
    public val allocations: List<StockAllocation> = frozenList(allocations)
}

/** Genuine pre-collection protection. No kitchen issue or payment is implied. */
public class FoodIngredientReservation(
    public val reservationId: DomainId,
    public val binding: CollectionBinding,
    allocations: List<StockAllocation>,
) {
    public val allocations: List<StockAllocation> = frozenList(allocations)
}

/** Converts exactly this existing reservation, with its original ingredient allocations. */
public class FoodReservedIngredientIssue(
    public val reservationId: DomainId,
    public val expectedReservationRevision: Revision,
    public val issueId: DomainId,
    public val recipe: RecipeIssueRequest,
)

/**
 * A closed composition of existing inventory rules. There is one OUTER operation and one
 * resulting local-cut revision, no invented committed sub-operation and no alternate ledger.
 * Intermediate reservation/value/issue effects and all original deadlines remain ordered.
 * This is a plan, not a commit or authorization. The normalized repository must project the
 * exact original source object and atomically commit the full commerce/task/result envelope.
 */
public object FoodInventoryPlanner {
    public const val MAX_PORTIONS: Int = 8
    public const val MAX_FINANCIAL_PORTIONS: Int = 32

    public fun reserveBeforeCollection(source: InventorySnapshot, expectedRevision: Revision,
        operationId: OperationId, portions: List<FoodIngredientReservation>, nowEpochMillis: Long): InventoryPlan {
        requireRevision(source.revision, expectedRevision)
        guard(portions.size in 1..MAX_FINANCIAL_PORTIONS && portions.map { it.reservationId }.distinct().size == portions.size,
            InventoryFailure.BOUND_EXCEEDED, "Protect the whole bounded unsent ingredient requirement")
        val allocations = portions.flatMap { it.allocations }
        guard(allocations.size in 1..InventoryPlanner.MAX_ALLOCATIONS && allocations.map { it.allocationId }.distinct().size == allocations.size,
            InventoryFailure.BOUND_EXCEEDED, "Distinct original ingredient allocations are required")
        guard(portions.all { it.binding.exposure == CollectionExposure.UNKNOWN },
            InventoryFailure.COLLECTION_RESOLUTION_REQUIRED, "Before-money protection is conservative, not proof of payment")
        val batch = FoodInventoryBatch(source, operationId)
        for (portion in portions) batch.add { current -> InventoryPlanner.reserve(current, current.revision, operationId,
            portion.reservationId, portion.binding, portion.allocations, nowEpochMillis, expiresAtEpochMillis = null).plan }
        return batch.finish()
    }

    public fun sendReserved(source: InventorySnapshot, expectedRevision: Revision, operationId: OperationId,
        portions: List<FoodReservedIngredientIssue>, nowEpochMillis: Long): InventoryPlan {
        requireRevision(source.revision, expectedRevision)
        guard(portions.size in 1..MAX_PORTIONS && portions.map { it.reservationId }.distinct().size == portions.size &&
            portions.map { it.issueId }.distinct().size == portions.size, InventoryFailure.BOUND_EXCEEDED,
            "Send a bounded distinct selection without allocating replacement stock")
        val batch = FoodInventoryBatch(source, operationId)
        for (portion in portions) batch.add { current ->
            val prior = current.reservation(portion.reservationId)
                ?: throw InventoryRejection(InventoryFailure.INVALID_STATE, "Original protected reservation missing")
            guard(prior.expiresAtEpochMillis == null && prior.binding.exposure != CollectionExposure.UNCOLLECTED,
                InventoryFailure.COLLECTION_RESOLUTION_REQUIRED, "A genuine financial reservation must remain protected")
            when (val issue = InventoryPlanner.issueReservation(current, current.revision, operationId,
                portion.reservationId, portion.expectedReservationRevision, portion.issueId, nowEpochMillis,
                ValueIssuePurpose.DIRECT_RECIPE, portion.recipe)) {
                is ReservationIssueResult.Issued -> issue.plan
                is ReservationIssueResult.Held -> throw InventoryRejection(InventoryFailure.UNSAFE_STOCK,
                    "A held selected portion blocks the whole send; no partial issue or second allocation")
            }
        }
        return batch.finish()
    }

    public fun releaseWhollyUnsent(source: InventorySnapshot, expectedRevision: Revision, operationId: OperationId,
        reservationIds: List<DomainId>, resolution: CollectionResolutionReference): InventoryPlan {
        requireRevision(source.revision, expectedRevision)
        guard(reservationIds.size in 1..MAX_FINANCIAL_PORTIONS && reservationIds.distinct().size == reservationIds.size,
            InventoryFailure.BOUND_EXCEEDED, "Release the exact bounded unsent collection")
        val batch = FoodInventoryBatch(source, operationId)
        for (id in reservationIds) batch.add { current ->
            val prior = current.reservation(id)
                ?: throw InventoryRejection(InventoryFailure.INVALID_STATE, "Original reservation missing")
            guard(prior.binding.exposure != CollectionExposure.UNCOLLECTED && prior.expiresAtEpochMillis == null &&
                prior.retainsStockClaim, InventoryFailure.COLLECTION_RESOLUTION_REQUIRED, "Only protected unsent work can release")
            InventoryPlanner.releaseReservation(current, current.revision, operationId, id, prior.revision, resolution)
        }
        return batch.finish()
    }

    public fun sendUncollected(
        source: InventorySnapshot,
        expectedRevision: Revision,
        operationId: OperationId,
        portions: List<FoodIngredientIssue>,
        nowEpochMillis: Long,
    ): InventoryPlan {
        requireRevision(source.revision, expectedRevision)
        guard(portions.size in 1..MAX_PORTIONS && portions.map { it.issueId }.distinct().size == portions.size &&
            portions.map { it.reservationId }.distinct().size == portions.size,
            InventoryFailure.BOUND_EXCEEDED, "Select a bounded set of distinct portions")
        val allocations = portions.flatMap { it.allocations }
        guard(allocations.size in 1..InventoryPlanner.MAX_ALLOCATIONS &&
            allocations.map { it.allocationId }.distinct().size == allocations.size,
            InventoryFailure.INVALID_ORIGINAL_ALLOCATION, "All recipe allocation identities must be distinct")
        guard(portions.all { it.binding.exposure == CollectionExposure.UNCOLLECTED },
            InventoryFailure.COLLECTION_RESOLUTION_REQUIRED, "Paid or uncertain work must convert its existing protected reservation")
        val batch = FoodInventoryBatch(source, operationId)
        for (portion in portions) {
            batch.add { current -> InventoryPlanner.reserve(current, current.revision, operationId,
                portion.reservationId, portion.binding, portion.allocations, nowEpochMillis).plan }
            batch.add { current ->
                val reserved = checkNotNull(current.reservation(portion.reservationId))
                when (val issued = InventoryPlanner.issueReservation(current, current.revision, operationId,
                    reserved.reservationId, reserved.revision, portion.issueId, nowEpochMillis,
                    ValueIssuePurpose.DIRECT_RECIPE, portion.recipe)) {
                    is ReservationIssueResult.Issued -> issued.plan
                    // No partial issue is exposed. New recipes are checked at this same time
                    // and a held outcome cannot become a successful subset of the send batch.
                    is ReservationIssueResult.Held -> throw InventoryRejection(InventoryFailure.UNSAFE_STOCK,
                        "An unavailable portion blocks the complete explicitly selected send")
                }
            }
        }
        return batch.finish()
    }
}

private class FoodInventoryBatch(private val original: InventorySnapshot, private val operationId: OperationId) {
    private var current: InventorySnapshot = original
    private val effects = mutableListOf<InventoryEffect>()
    private var deadline: Long? = null

    fun add(step: (InventorySnapshot) -> InventoryPlan) {
        val plan = step(current)
        check(plan.sourceSnapshot === current && plan.operationId == operationId)
        val next = plan.nextSnapshot
        effects += plan.effects
        plan.mustCommitBeforeEpochMillis?.let { deadline = deadline?.let { old -> minOf(old, it) } ?: it }
        // Only this uncommitted outer operation is removed from the planning shell. Permanent
        // source/allocation IDs and all physical/value/reservation history are preserved.
        check(next.operationIds == original.operationIds + operationId)
        current = InventorySnapshot(next.scope, next.revision, next.positions, next.pools, next.reservations,
            next.issues, original.operationIds, next.sourceIds)
    }

    fun finish(): InventoryPlan {
        check(effects.isNotEmpty())
        val next = InventorySnapshot(original.scope, original.revision.next(), current.positions, current.pools,
            current.reservations, current.issues, original.operationIds + operationId, current.sourceIds)
        return InventoryPlan(operationId, original, next, effects, deadline)
    }
}
