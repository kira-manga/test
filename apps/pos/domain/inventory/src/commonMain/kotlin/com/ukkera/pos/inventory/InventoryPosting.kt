package com.ukkera.pos.inventory

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision

/** Independent operation/cut metadata. It is not a storage generation or a source-history digest. */
public data class InventoryPostingBinding(
    public val scope: InventoryScope,
    public val operationId: OperationId,
    public val requiredInventoryRevision: Revision,
    public val resultingInventoryRevision: Revision,
)

public enum class InventoryPostingFailure {
    MALFORMED, TOO_LARGE, NON_CANONICAL, UNSUPPORTED_VERSION, BINDING_MISMATCH,
    UNSUPPORTED_STATE, INCONSISTENT_STATE,
}

/** Stable code only; protected data and parser/arithmetic transcripts are never included. */
public class InventoryPostingRejectedException(public val failure: InventoryPostingFailure) :
    IllegalArgumentException(failure.name)

/**
 * Immutable projected posted facts, NOT InventoryEffect.Valuation's executable source/next plan.
 * This version admits purchase receipts, retail issues and reservation/expiry records only.
 * Fields such as collection exposure remain historical data, never evidence of money or authority.
 */
public sealed interface InventoryPostingEffect {
    public data class Movement internal constructor(
        public val sourceId: DomainId,
        public val position: StockPositionKey,
        public val quantityDelta: Quantity,
        public val reason: StockMovementReason,
        public val sourceAllocationId: DomainId?,
    ) : InventoryPostingEffect

    public data class Valuation internal constructor(
        public val pool: StockPoolKey,
        public val requiredPoolRevision: Revision,
        public val resultingPoolRevision: Revision,
        public val fact: ValuationFact,
        public val quantityDelta: Quantity,
        public val originalValueDelta: Money,
        public val carryingValueDelta: Money,
    ) : InventoryPostingEffect

    public data class ReservationRecorded internal constructor(public val reservation: StockReservation) : InventoryPostingEffect
    public data class SafetyChanged internal constructor(public val before: StockPosition, public val after: StockPosition) : InventoryPostingEffect
    public data class IssueRecorded internal constructor(public val issue: StockIssue) : InventoryPostingEffect
}

/**
 * Ordered historical data, including intermediate reservation revisions. Neither construction nor
 * decoding proves a commit, source provenance, completeness, current frontier or authorization.
 * No deadline capability is retained. Full cuts/receipt origins/claims belong to the outer store.
 */
public class InventoryPostingBatch internal constructor(
    public val binding: InventoryPostingBinding,
    effects: List<InventoryPostingEffect>,
) {
    public val effects: List<InventoryPostingEffect> = frozenList(effects)
}

/**
 * Small intrinsic profile: whole company-owned/released cuts with PURCHASE receipts, unrestored
 * RETAIL issues, RESERVED/HELD/ISSUED reservations and CLEAR/EXPIRED positions. All exposure enum
 * values remain data. Cost targets/adjustments (including zero/cancelled), returns, releases, count,
 * recall, recipe/kitchen and other ownership/custody/origin histories reject the WHOLE cut.
 * This shape restriction is not a producer-family whitelist, receipt-origin crosswalk or sales gate.
 */
public object InventoryPosting {
    /**
     * Checks only the bounded supported shape and internal consistency of the supplied snapshot.
     * This is historical data validation, not provenance, completeness/current-frontier evidence,
     * current-time safety, authorization or commit authority. No plan or proof is reconstructed.
     * Historical CLEAR stock may be expired now; authoritative live revalidation is still owed.
     */
    public fun requireSupportedSnapshot(snapshot: InventorySnapshot): Unit = checkedInventoryPosting {
        checkInventoryStorageBudget(snapshot)
        validateInventoryPostingProfile(snapshot)
        validateStoredInventory(snapshot)
    }

    /**
     * Checks an actual in-process plan against its EXACT complete source object and projects every
     * effect in order. Both cuts must fit the intrinsic purchase/retail/expiry profile. This is a
     * storage correspondence check, not a second planner: it does not select stock, reprice facts,
     * decide collections or reconstruct proofs. Supplied time checks the live plan now, NOT at COMMIT.
     * The caller must still propagate the ORIGINAL plan's deadline into its atomic commit contract.
     * A receipt Movement has no expiry/safety payload: a NEW position's metadata is only result-bound
     * here. The adapter must bind full cut bytes AND the full receipt/lot/expiry/upstream request.
     * These facts alone neither identify that request nor independently establish receipt provenance.
     */
    public fun fromPlan(
        authoritativeSource: InventorySnapshot,
        plan: InventoryPlan,
        expectedBinding: InventoryPostingBinding,
        nowEpochMillis: Long,
    ): InventoryPostingBatch = checkedInventoryPosting {
        inventoryPostingCheck(plan.sourceSnapshot === authoritativeSource, InventoryPostingFailure.BINDING_MISMATCH)
        validateInventoryPostingBinding(expectedBinding)
        inventoryPostingCheck(authoritativeSource.scope == expectedBinding.scope && plan.operationId == expectedBinding.operationId &&
            authoritativeSource.revision == expectedBinding.requiredInventoryRevision &&
            plan.nextSnapshot.scope == expectedBinding.scope && plan.nextSnapshot.revision == expectedBinding.resultingInventoryRevision,
            InventoryPostingFailure.BINDING_MISMATCH)
        InventoryPlanner.requireCurrent(plan, authoritativeSource, nowEpochMillis)
        checkInventoryStorageBudget(authoritativeSource)
        checkInventoryStorageBudget(plan.nextSnapshot)
        validateInventoryPostingProfile(authoritativeSource)
        validateInventoryPostingProfile(plan.nextSnapshot)
        validateStoredInventory(authoritativeSource)
        validateStoredInventory(plan.nextSnapshot)
        checkInventoryPostingPlanBudget(plan)
        val effects = plan.effects.map(::projectInventoryPostingEffect)
        val batch = InventoryPostingBatch(expectedBinding, effects)
        validateInventoryPostingBatch(batch)
        crosswalkInventoryPosting(authoritativeSource, plan, batch, nowEpochMillis)
        // The conservative preflight is not a substitute for the actual UTF-8 encoded-byte check.
        InventoryPostingCodec.encode(batch)
        batch
    }
}
