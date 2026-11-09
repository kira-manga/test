package com.ukkera.pos.inventory

import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Canonical normalized-record storage. Reuses the domain's existing private scalar/state DTOs;
 * it neither changes whole-branch v1 nor recreates a plan, authorization or reservation proof.
 * Pool histories may span bounded storage pages; callers must bind the complete ordered page
 * manifest before decoding. The atomic store's per-row and per-command limits still apply.
 */
public object InventoryRecordCodec {
    public const val VERSION: Int = 1
    /** Serial/value-lineage extensions are never mislabeled as the original normalized v1. */
    public const val EXTENDED_VERSION: Int = 2
    /** Paired same-branch value facts and graph-correction bases, never old v1/v2 semantics. */
    public const val OWNED_TRANSFER_VERSION: Int = 3
    public const val MAX_BYTES: Int = InventorySnapshotCodec.MAX_ENCODED_BYTES

    public fun positionKey(key: StockPositionKey): String = encode(StoredPositionKey.from(key), StoredPositionKey.serializer())
    public fun poolKey(key: StockPoolKey): String = encode(StoredPoolKey.from(key), StoredPoolKey.serializer())

    public fun encodePosition(value: StockPosition): String {
        requirePosition(value)
        return encode(LocalStoredPosition(inventoryPositionRecordVersion(value.key), StoredPosition.from(value)), LocalStoredPosition.serializer())
    }

    public fun decodePosition(encoded: String, expected: StockPositionKey): StockPosition = checkedInventorySnapshot {
        val stored = decode(encoded, LocalStoredPosition.serializer())
        inventorySnapshotCheck(stored.version == inventoryPositionRecordVersion(expected), InventorySnapshotFailure.UNSUPPORTED_VERSION)
        stored.position.restore().also {
            inventorySnapshotCheck(it.key == expected, InventorySnapshotFailure.BINDING_MISMATCH)
            requirePosition(it)
        }
    }

    public fun encodePool(value: OwnedValuationPool): String {
        requirePool(value)
        return encode(LocalStoredPool(inventoryPoolRecordVersion(value), StoredOwnedPool.from(value)), LocalStoredPool.serializer())
    }

    public fun decodePool(encoded: String, expected: StockPoolKey, revision: Revision): OwnedValuationPool = checkedInventorySnapshot {
        val stored = decode(encoded, LocalStoredPool.serializer())
        inventorySnapshotCheck(stored.version in VERSION..OWNED_TRANSFER_VERSION, InventorySnapshotFailure.UNSUPPORTED_VERSION)
        stored.pool.restore().also {
            inventorySnapshotCheck(stored.version == inventoryPoolRecordVersion(it), InventorySnapshotFailure.UNSUPPORTED_VERSION)
            inventorySnapshotCheck(it.key == expected && it.value.revision == revision, InventorySnapshotFailure.BINDING_MISMATCH)
            requirePool(it)
        }
    }

    public fun encodeReservation(value: StockReservation, scope: InventoryScope): String {
        requireReservation(value, scope)
        return encode(LocalStoredReservation(inventoryReservationRecordVersion(value), StoredInventoryScope.from(scope), StoredReservation.from(value)),
            LocalStoredReservation.serializer())
    }

    public fun decodeReservation(encoded: String, scope: InventoryScope, id: DomainId, revision: Revision): StockReservation = checkedInventorySnapshot {
        val stored = decode(encoded, LocalStoredReservation.serializer())
        inventorySnapshotCheck(stored.version in VERSION..EXTENDED_VERSION, InventorySnapshotFailure.UNSUPPORTED_VERSION)
        inventorySnapshotCheck(stored.scope.restore() == scope, InventorySnapshotFailure.BINDING_MISMATCH)
        stored.reservation.restore().also {
            inventorySnapshotCheck(stored.version == inventoryReservationRecordVersion(it), InventorySnapshotFailure.UNSUPPORTED_VERSION)
            inventorySnapshotCheck(it.reservationId == id && it.revision == revision, InventorySnapshotFailure.BINDING_MISMATCH)
            requireReservation(it, scope)
        }
    }

    public fun encodeIssue(value: StockIssue, scope: InventoryScope): String {
        requireIssue(value, scope)
        return encode(LocalStoredIssue(inventoryIssueRecordVersion(value), StoredInventoryScope.from(scope), StoredIssue.from(value)), LocalStoredIssue.serializer())
    }

    public fun decodeIssue(encoded: String, scope: InventoryScope, id: DomainId, revision: Revision): StockIssue = checkedInventorySnapshot {
        val stored = decode(encoded, LocalStoredIssue.serializer())
        inventorySnapshotCheck(stored.version in VERSION..EXTENDED_VERSION, InventorySnapshotFailure.UNSUPPORTED_VERSION)
        inventorySnapshotCheck(stored.scope.restore() == scope, InventorySnapshotFailure.BINDING_MISMATCH)
        stored.issue.restore().also {
            inventorySnapshotCheck(stored.version == inventoryIssueRecordVersion(it), InventorySnapshotFailure.UNSUPPORTED_VERSION)
            inventorySnapshotCheck(it.issueId == id && it.revision == revision, InventorySnapshotFailure.BINDING_MISMATCH)
            requireIssue(it, scope)
        }
    }

    /**
     * Every effect, in its original order, from the actual live plan and identical source cut.
     * Includes intermediate reservations, zero-quantity counts, returns, linked cost corrections,
     * kitchen confirmations and collateral safety records. No catch/filter drops unfamiliar facts.
     * This does not renew the plan deadline; propagate the original deadline to the OUTER commit.
     */
    public fun encodeEffects(source: InventorySnapshot, plan: InventoryPlan, nowEpochMillis: Long): String = checkedInventorySnapshot {
        inventorySnapshotCheck(plan.sourceSnapshot === source && plan.nextSnapshot.scope == source.scope &&
            plan.nextSnapshot.revision == source.revision.next(), InventorySnapshotFailure.BINDING_MISMATCH)
        InventoryPlanner.requireCurrent(plan, source, nowEpochMillis)
        checkInventoryStorageBudget(source)
        checkInventoryStorageBudget(plan.nextSnapshot)
        crosswalkLocalEffects(source, plan)
        val version = if (plan.effects.any(::ownedTransferInventoryEffect)) OWNED_TRANSFER_VERSION
            else if (plan.effects.any(::extendedInventoryEffect)) EXTENDED_VERSION else VERSION
        encode(LocalStoredEffects(version, StoredInventoryScope.from(source.scope), plan.operationId,
            plan.effects.map(::storedLocalEffect)), LocalStoredEffects.serializer())
    }

    /**
     * Historical literal facts for a retained producer validator. This does NOT reconstruct a
     * plan/proof, run a transition, infer a missing original source or establish current authority.
     */
    public fun inspectEffects(encoded: String, scope: InventoryScope, operationId: OperationId): InventoryPostedRecords = checkedInventorySnapshot {
        val stored = decode(encoded, LocalStoredEffects.serializer())
        inventorySnapshotCheck(stored.version in VERSION..OWNED_TRANSFER_VERSION, InventorySnapshotFailure.UNSUPPORTED_VERSION)
        inventorySnapshotCheck(stored.scope.restore() == scope && stored.operationId == operationId, InventorySnapshotFailure.BINDING_MISMATCH)
        val movements = mutableListOf<InventoryPostedMovement>()
        val valuations = mutableListOf<InventoryPostedValuation>()
        val reservations = mutableListOf<StockReservation>()
        val issues = mutableListOf<StockIssue>()
        val counts = mutableListOf<InventoryPostedCount>()
        val safety = mutableListOf<Pair<StockPosition, StockPosition>>()
        val registrations = mutableListOf<Pair<StockPosition, StockPosition>>()
        val confirmations = mutableListOf<KitchenCancellationConfirmation>()
        val waste = mutableListOf<InventoryPostedWaste>()
        val releases = mutableListOf<InventoryPostedRelease>()
        for (effect in stored.effects) when (effect) {
            is LocalStoredEffect.Movement -> {
                val position = effect.position.restore()
                inventorySnapshotCheck(position.pool.scope == scope && effect.quantityDelta.scaled != 0L)
                if (position.serialId != null) inventorySnapshotCheck(effect.quantityDelta.scaled == serialUnit(effect.quantityDelta.scale) ||
                    effect.quantityDelta.scaled == -serialUnit(effect.quantityDelta.scale))
                movements += InventoryPostedMovement(effect.sourceId, position, effect.quantityDelta,
                    enumValueOf<StockMovementReason>(effect.reason), effect.sourceAllocationId)
            }
            is LocalStoredEffect.Valuation -> {
                val pool = effect.pool.restore()
                val fact = effect.fact?.restore()
                val linked = effect.linkedAdjustment?.restore()
                inventorySnapshotCheck(pool.scope == scope && pool.ownership == StockOwnership.CompanyOwned &&
                    effect.resultingRevision == effect.requiredRevision.next() &&
                    ((fact != null) != (linked?.changedSource != null || linked?.transferCorrection != null)) &&
                    effect.originalValueDelta.currency == Currency.EGP && effect.carryingValueDelta.currency == Currency.EGP &&
                    effect.carryingValueDelta == effect.originalValueDelta + (linked?.heldDelta ?: Money(Currency.EGP, 0)))
                valuations += InventoryPostedValuation(pool, effect.requiredRevision, effect.resultingRevision, fact,
                    effect.quantityDelta, effect.originalValueDelta, effect.carryingValueDelta, linked)
            }
            is LocalStoredEffect.Reservation -> reservations += effect.value.restore().also { requireReservation(it, scope) }
            is LocalStoredEffect.Release -> {
                val value = effect.value.restore().also { requireReservation(it, scope) }
                inventorySnapshotCheck(value.status == ReservationStatus.RELEASED)
                if (value.binding.exposure != CollectionExposure.UNCOLLECTED) inventorySnapshotCheck(effect.resolution != null)
                effect.resolution?.let { inventorySnapshotCheck(it.orderId == value.binding.orderId && it.collectionId == value.binding.collectionId) }
                reservations += value
                releases += InventoryPostedRelease(value, effect.resolution?.let {
                    CollectionResolutionReference(it.resolutionId, it.orderId, it.collectionId, it.preservedObligationId)
                })
            }
            is LocalStoredEffect.Issue -> issues += effect.value.restore().also { requireIssue(it, scope) }
            is LocalStoredEffect.Count -> {
                val position = effect.position.restore()
                inventorySnapshotCheck(position.pool.scope == scope && effect.counted.scaled >= 0)
                if (position.serialId != null) inventorySnapshotCheck(effect.counted.scaled == 0L || effect.counted.scaled == serialUnit(effect.counted.scale))
                counts += InventoryPostedCount(effect.countId, position, effect.capturedRevision, effect.counted)
            }
            is LocalStoredEffect.Safety -> {
                val before = effect.before.restore().also(::requirePosition)
                val after = effect.after.restore().also(::requirePosition)
                val priorMovement = movements.lastOrNull { it.position == before.key }
                val quantityCorresponds = before.onHand == after.onHand ||
                    (priorMovement?.reason == StockMovementReason.COUNT_SHORTAGE && priorMovement.quantityDelta.scaled < 0 &&
                        before.onHand + priorMovement.quantityDelta == after.onHand)
                inventorySnapshotCheck(before.key.pool.scope == scope && before.key == after.key && quantityCorresponds &&
                    before.expiresAtEpochMillis == after.expiresAtEpochMillis && before.serialRegistrationId == after.serialRegistrationId &&
                    after.safety != StockSafety.CLEAR)
                safety += before to after
            }
            is LocalStoredEffect.SerialRegistration -> {
                val before = effect.before.restore().also(::requirePosition)
                val after = effect.after.restore().also(::requirePosition)
                requireSerialRegistrationTransition(before, after)
                inventorySnapshotCheck(before.key.pool.scope == scope)
                registrations += before to after
            }
            is LocalStoredEffect.KitchenConfirmation -> {
                inventorySnapshotCheck(effect.confirmedStationIds.size in 1..32 &&
                    effect.confirmedStationIds.distinct().size == effect.confirmedStationIds.size)
                confirmations += KitchenCancellationConfirmation(effect.confirmationId, effect.issueId, effect.issueRevision,
                    effect.confirmedBy, effect.confirmedStationIds)
            }
            is LocalStoredEffect.Waste -> {
                inventorySnapshotCheck(effect.originalValue.currency == Currency.EGP && effect.originalValue.minor >= 0)
                waste += InventoryPostedWaste(effect.issueId, effect.originalValue, effect.reasonId)
            }
        }
        val requiredVersion = if (valuations.any { ownedTransferInventoryFact(it.fact) ||
            it.linkedAdjustment?.calculationVersion == LinkedMovingAveragePlanner.CALCULATION_VERSION }) OWNED_TRANSFER_VERSION
            else if (valuations.mapNotNull { it.fact }.any(::extendedInventoryFact) ||
            movements.any { it.position.serialId != null } || counts.any { it.position.serialId != null } ||
            reservations.any { inventoryReservationRecordVersion(it) == 2 } || issues.any { inventoryIssueRecordVersion(it) == 2 } ||
            safety.any { it.first.key.serialId != null } || registrations.isNotEmpty()) EXTENDED_VERSION else VERSION
        inventorySnapshotCheck(stored.version == requiredVersion, InventorySnapshotFailure.UNSUPPORTED_VERSION)
        val kinds = stored.effects.map { effect -> when (effect) {
            is LocalStoredEffect.Movement -> InventoryPostedEffectKind.MOVEMENT
            is LocalStoredEffect.Valuation -> InventoryPostedEffectKind.VALUATION
            is LocalStoredEffect.Reservation -> InventoryPostedEffectKind.RESERVATION
            is LocalStoredEffect.Release -> InventoryPostedEffectKind.RELEASE
            is LocalStoredEffect.Issue -> InventoryPostedEffectKind.ISSUE
            is LocalStoredEffect.Count -> InventoryPostedEffectKind.COUNT
            is LocalStoredEffect.Safety -> InventoryPostedEffectKind.SAFETY
            is LocalStoredEffect.SerialRegistration -> InventoryPostedEffectKind.SERIAL_REGISTRATION
            is LocalStoredEffect.KitchenConfirmation -> InventoryPostedEffectKind.KITCHEN_CONFIRMATION
            is LocalStoredEffect.Waste -> InventoryPostedEffectKind.WASTE
        } }
        InventoryPostedRecords(encoded, kinds, movements, valuations, reservations, issues, counts, safety, registrations, confirmations, waste, releases)
    }

    /**
     * Checks a retained normalized predecessor and the complete immutable appended value history.
     * This is historical correspondence, not transition execution, current authority or a proof.
     */
    public fun requirePostedPoolTransition(
        before: OwnedValuationPool?,
        after: OwnedValuationPool,
        postings: List<InventoryPostedValuation>,
    ): Unit = checkedInventorySnapshot {
        requirePool(after)
        before?.let(::requirePool)
        val previous = before?.value ?: MovingAverageState.empty(after.value.unitId, after.value.scale, after.value.currency)
        inventorySnapshotCheck(before == null || before.key == after.key)
        inventorySnapshotCheck(previous.unitId == after.value.unitId && previous.scale == after.value.scale && previous.currency == after.value.currency &&
            postings.isNotEmpty() && postings.size <= InventoryPlanner.MAX_ALLOCATIONS)
        var revision = previous.revision
        var quantity = previous.onHand
        var value = previous.carryingValue
        var targets = previous.costTargets
        var imports = previous.transferTargets
        val facts = previous.facts.map(StoredValueFact::from).toMutableList()
        val adjustments = previous.adjustments.map(StoredCostAdjustment::from).toMutableList()
        val events = previous.eventIds.toMutableList()
        for (posting in postings) {
            inventorySnapshotCheck(posting.pool == after.key && posting.requiredRevision == revision && posting.resultingRevision == revision.next())
            val fact = posting.fact
            val linked = posting.linkedAdjustment
            val event = fact?.factId ?: linked?.takeIf { it.changedSource != null || it.transferCorrection != null }?.adjustmentId
                ?: throw InventorySnapshotRejectedException(InventorySnapshotFailure.INCONSISTENT_STATE)
            inventorySnapshotCheck(event !in events && (fact != null) != (linked?.changedSource != null || linked?.transferCorrection != null))
            val zeroQuantity = Quantity(previous.unitId, 0, previous.scale)
            val (quantityDelta, originalDelta) = when (fact) {
                is ValuationFact.Receipt -> fact.quantity to fact.originalValue
                is ValuationFact.TransferOut -> -fact.link.quantity to -fact.link.originalValue
                is ValuationFact.TransferIn -> fact.link.quantity to fact.link.originalValue
                is ValuationFact.Issue -> -fact.slices.fold(zeroQuantity) { sum, slice -> sum + slice.quantity } to
                    -fact.slices.fold(Money(previous.currency, 0)) { sum, slice -> sum + slice.originalValue }
                is ValuationFact.Restoration -> fact.restorations.fold(zeroQuantity) { sum, slice -> sum + slice.quantity } to
                    fact.restorations.fold(Money(previous.currency, 0)) { sum, slice -> sum + slice.originalValue }
                null -> zeroQuantity to Money(previous.currency, 0)
            }
            inventorySnapshotCheck(posting.quantityDelta == quantityDelta && posting.originalValueDelta == originalDelta &&
                posting.carryingValueDelta == originalDelta + (linked?.heldDelta ?: Money(previous.currency, 0)))
            fact?.let { facts += StoredValueFact.from(it) }
            if (fact is ValuationFact.TransferIn) imports = (imports + IncomingTransferCostTarget(event, Money(previous.currency, 0)))
                .sortedBy { it.receiptEventId.value }
            linked?.let { adjustment ->
                inventorySnapshotCheck(adjustment.adjustmentId == event && adjustment.basisRevision == revision)
                adjustment.changedSource?.let { target ->
                    inventorySnapshotCheck(targets.none { it.sourceId == target.sourceId && it.receiptId != target.receiptId })
                    targets = (targets.filterNot { it.sourceId == target.sourceId } + target).sortedBy { it.sourceId.value }
                }
                if (adjustment.transferCorrection != null) imports = adjustment.transferTargetBasis
                inventorySnapshotCheck(adjustment.targetBasis == targets && adjustment.transferTargetBasis == imports)
                adjustments += StoredCostAdjustment.from(adjustment)
            }
            events += event
            quantity += quantityDelta
            value += posting.carryingValueDelta
            revision = revision.next()
        }
        inventorySnapshotCheck(after.value.revision == revision && after.value.onHand == quantity && after.value.carryingValue == value &&
            after.value.eventIds == events && after.value.facts.map(StoredValueFact::from) == facts &&
            after.value.adjustments.map(StoredCostAdjustment::from) == adjustments && after.value.costTargets == targets &&
            after.value.transferTargets == imports)
    }

    internal fun requirePosition(value: StockPosition) {
        inventorySnapshotCheck(value.onHand.scaled >= 0 && (value.expiresAtEpochMillis == null ||
            (value.expiresAtEpochMillis >= 0 && value.key.lotId != null)))
        inventorySnapshotCheck(value.safety != StockSafety.EXPIRED || value.expiresAtEpochMillis != null)
        inventorySnapshotCheck(if (value.key.pool.ownership == StockOwnership.CompanyOwned)
            value.key.custody != StockCustody.REJECTED_HELD else value.key.custody != StockCustody.RELEASED)
    }

    internal fun requirePool(value: OwnedValuationPool) {
        // Uses the same conservative history/replay budget before the intrinsic valuation fold.
        checkInventoryStorageBudget(InventorySnapshot(value.key.scope, Revision(0), emptyList(), listOf(value),
            emptyList(), emptyList(), emptyList(), emptyList()))
        inventorySnapshotCheck(value.key.ownership == StockOwnership.CompanyOwned)
        validateStoredValuation(value.value)
        inventorySnapshotCheck(value.value.facts.any { it is ValuationFact.Receipt || it is ValuationFact.TransferIn })
        for (fact in value.value.facts) when (fact) {
            is ValuationFact.TransferOut -> inventorySnapshotCheck(fact.link.sourcePool == value.key)
            is ValuationFact.TransferIn -> inventorySnapshotCheck(fact.link.destinationPool == value.key)
            else -> Unit
        }
        for (adjustment in value.value.adjustments) adjustment.transferCorrection?.let { basis ->
            inventorySnapshotCheck(basis.pools.singleOrNull { it.pool == value.key }?.revision == adjustment.basisRevision &&
                adjustment.changedSource == basis.changedSource.takeIf { basis.sourcePool == value.key })
        }
    }

    internal fun requireReservation(value: StockReservation, scope: InventoryScope) {
        val serials = value.allocations.mapNotNull { it.position.serialId }
        inventorySnapshotCheck(serials.distinct().size == serials.size)
        inventorySnapshotCheck(value.revision.value >= 1 && value.allocations.size in 1..InventoryPlanner.MAX_ALLOCATIONS &&
            value.allocations.map { it.allocationId }.distinct().size == value.allocations.size &&
            value.holds.size <= InventoryPlanner.MAX_ALLOCATIONS * ReservationHoldReason.entries.size + 1 &&
            value.holds.distinct().size == value.holds.size && (value.expiresAtEpochMillis == null || value.expiresAtEpochMillis > 0))
        inventorySnapshotCheck(value.allocations.all { it.position.pool.scope == scope &&
            it.position.pool.ownership == StockOwnership.CompanyOwned && it.position.custody == StockCustody.RELEASED && it.quantity.scaled > 0 })
        inventorySnapshotCheck(when (value.status) {
            ReservationStatus.RESERVED -> value.holds.isEmpty()
            ReservationStatus.HELD -> value.holds.isNotEmpty() && value.revision.value >= 2
            ReservationStatus.ISSUED -> value.holds.isEmpty() && value.revision.value >= 2
            ReservationStatus.RELEASED -> value.revision.value >= 2
        })
        val positions = value.allocations.map { it.position }.toSet()
        for (hold in value.holds) inventorySnapshotCheck(if (hold.reason == ReservationHoldReason.RESERVATION_EXPIRED)
            hold.position == null && value.expiresAtEpochMillis != null else hold.position != null && hold.position in positions)
    }

    internal fun requireIssue(value: StockIssue, scope: InventoryScope) {
        val serials = value.allocations.mapNotNull { it.allocation.position.serialId }
        inventorySnapshotCheck(serials.distinct().size == serials.size && (serials.isEmpty() || value.purpose == ValueIssuePurpose.RETAIL))
        inventorySnapshotCheck(value.revision.value >= 1 && value.allocations.size in 1..InventoryPlanner.MAX_ALLOCATIONS &&
            value.allocations.map { it.allocation.allocationId }.distinct().size == value.allocations.size &&
            (value.purpose == ValueIssuePurpose.RETAIL || value.purpose == ValueIssuePurpose.DIRECT_RECIPE))
        for (row in value.allocations) {
            val allocation = row.allocation
            inventorySnapshotCheck(allocation.position.pool.scope == scope && allocation.position.pool.ownership == StockOwnership.CompanyOwned &&
                allocation.position.custody == StockCustody.RELEASED && allocation.quantity.scaled > 0 &&
                row.originalValue.currency == Currency.EGP && row.originalValue.minor >= 0)
            requireSameUnit(allocation.quantity, row.restoredQuantity)
            inventorySnapshotCheck(row.restoredQuantity.scaled >= 0 && row.restoredQuantity <= allocation.quantity)
            requireSerialQuantity(allocation.position, row.restoredQuantity, allowZero = true)
        }
        inventorySnapshotCheck(value.originalValue.minor >= 0)
        val kitchen = value.kitchen
        inventorySnapshotCheck((value.purpose == ValueIssuePurpose.DIRECT_RECIPE) == (kitchen != null))
        if (kitchen != null) {
            inventorySnapshotCheck(kitchen.requiredStationIds.size in 1..32 &&
                kitchen.requiredStationIds.distinct().size == kitchen.requiredStationIds.size &&
                kitchen.startedStationIds.distinct().size == kitchen.startedStationIds.size &&
                kitchen.startedStationIds.all { it in kitchen.requiredStationIds })
            inventorySnapshotCheck(when (kitchen.disposition) {
                KitchenDisposition.ACTIVE -> value.allocations.all { it.restoredQuantity.scaled == 0L }
                KitchenDisposition.CANCELLED_BEFORE_START -> kitchen.startedStationIds.isEmpty() &&
                    value.allocations.all { it.restoredQuantity == it.allocation.quantity }
                KitchenDisposition.WASTE_AFTER_START -> kitchen.startedStationIds.isNotEmpty() &&
                    value.allocations.all { it.restoredQuantity.scaled == 0L }
            })
        }
    }
}

/** Non-executable historical value records; no source authority or reusable proof is issued. */
public data class InventoryPostedMovement(
    public val sourceId: DomainId, public val position: StockPositionKey, public val quantityDelta: Quantity,
    public val reason: StockMovementReason, public val sourceAllocationId: DomainId?,
)

public data class InventoryPostedValuation(
    public val pool: StockPoolKey, public val requiredRevision: Revision, public val resultingRevision: Revision,
    public val fact: ValuationFact?, public val quantityDelta: Quantity, public val originalValueDelta: Money,
    public val carryingValueDelta: Money, public val linkedAdjustment: LinkedCostAdjustment?,
)

public data class InventoryPostedCount(
    public val countId: DomainId, public val position: StockPositionKey, public val capturedRevision: Revision, public val counted: Quantity,
)

public data class InventoryPostedWaste(public val issueId: DomainId, public val originalValue: Money, public val reasonId: DomainId)

/** Literal retained RELEASE linkage, never authorization to release another reservation. */
public class InventoryPostedRelease internal constructor(public val reservation: StockReservation,
    public val resolution: CollectionResolutionReference?)

public enum class InventoryPostedEffectKind { MOVEMENT, VALUATION, RESERVATION, RELEASE, ISSUE, COUNT, SAFETY, KITCHEN_CONFIRMATION, WASTE, SERIAL_REGISTRATION }

public class InventoryPostedRecords internal constructor(
    /** Canonical literal ordered effects, not an executable plan or authorization. */
    public val literalEffects: String,
    effectKinds: List<InventoryPostedEffectKind>,
    movements: List<InventoryPostedMovement>, valuations: List<InventoryPostedValuation>, reservations: List<StockReservation>,
    issues: List<StockIssue>, counts: List<InventoryPostedCount>, safety: List<Pair<StockPosition, StockPosition>>,
    serialRegistrations: List<Pair<StockPosition, StockPosition>> = emptyList(),
    kitchenConfirmations: List<KitchenCancellationConfirmation> = emptyList(),
    waste: List<InventoryPostedWaste> = emptyList(),
    releases: List<InventoryPostedRelease> = emptyList(),
) {
    /** Every effect kind, including releases/kitchen/waste, in its original position. Nothing is omitted. */
    public val effectKinds: List<InventoryPostedEffectKind> = frozenList(effectKinds)
    public val movements: List<InventoryPostedMovement> = frozenList(movements)
    public val valuations: List<InventoryPostedValuation> = frozenList(valuations)
    public val reservations: List<StockReservation> = frozenList(reservations)
    public val issues: List<StockIssue> = frozenList(issues)
    public val counts: List<InventoryPostedCount> = frozenList(counts)
    public val safety: List<Pair<StockPosition, StockPosition>> = frozenList(safety)
    public val serialRegistrations: List<Pair<StockPosition, StockPosition>> = frozenList(serialRegistrations)
    public val kitchenConfirmations: List<KitchenCancellationConfirmation> = frozenList(kitchenConfirmations)
    public val waste: List<InventoryPostedWaste> = frozenList(waste)
    public val releases: List<InventoryPostedRelease> = frozenList(releases)
    public val newSourceIds: Set<DomainId> get() = (movements.map { it.sourceId } + valuations.mapNotNull {
        it.fact?.factId ?: it.linkedAdjustment?.takeIf { linked -> linked.changedSource != null || linked.transferCorrection != null }?.adjustmentId
    } + counts.map { it.countId }).toSet()
}

private val localInventoryJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
    coerceInputValues = false
    allowSpecialFloatingPointValues = false
}

private fun <T> encode(value: T, serializer: KSerializer<T>): String = checkedInventorySnapshot {
    localInventoryJson.encodeToString(serializer, value).also(::boundLocalInventoryJson)
}

private fun <T> decode(encoded: String, serializer: KSerializer<T>): T = checkedInventorySnapshot {
    boundLocalInventoryJson(encoded)
    val value = localInventoryJson.decodeFromString(serializer, encoded)
    inventorySnapshotCheck(localInventoryJson.encodeToString(serializer, value) == encoded, InventorySnapshotFailure.NON_CANONICAL)
    value
}

private fun boundLocalInventoryJson(text: String) {
    inventorySnapshotCheck(text.length <= InventoryRecordCodec.MAX_BYTES, InventorySnapshotFailure.TOO_LARGE)
    inventorySnapshotCheck(text.encodeToByteArray(throwOnInvalidSequence = true).size <= InventoryRecordCodec.MAX_BYTES,
        InventorySnapshotFailure.TOO_LARGE)
    var quoted = false
    var escaped = false
    var containers = 0
    val stack = mutableListOf<Pair<Char, Int>>()
    for (character in text) {
        if (quoted) {
            if (escaped) escaped = false
            else if (character == '\\') escaped = true
            else if (character == '"') quoted = false
        } else when (character) {
            '"' -> quoted = true
            '{', '[' -> {
                inventorySnapshotCheck(stack.size < InventorySnapshotCodec.MAX_JSON_DEPTH && ++containers <= InventorySnapshotCodec.MAX_JSON_CONTAINERS,
                    InventorySnapshotFailure.TOO_LARGE)
                stack += character to 0
            }
            '}', ']' -> {
                inventorySnapshotCheck(stack.isNotEmpty() && stack.last().first == if (character == '}') '{' else '[',
                    InventorySnapshotFailure.MALFORMED)
                stack.removeAt(stack.lastIndex)
            }
            ',' -> if (stack.isNotEmpty() && stack.last().first == '[') {
                val count = stack.last().second + 1
                inventorySnapshotCheck(count < InventorySnapshotCodec.MAX_ARRAY_ENTRIES, InventorySnapshotFailure.TOO_LARGE)
                stack[stack.lastIndex] = '[' to count
            }
        }
    }
    inventorySnapshotCheck(!quoted && stack.isEmpty(), InventorySnapshotFailure.MALFORMED)
}

@Serializable private data class LocalStoredPosition(val version: Int, val position: StoredPosition)
@Serializable private data class LocalStoredPool(val version: Int, val pool: StoredOwnedPool)
@Serializable private data class LocalStoredReservation(val version: Int, val scope: StoredInventoryScope, val reservation: StoredReservation)
@Serializable private data class LocalStoredIssue(val version: Int, val scope: StoredInventoryScope, val issue: StoredIssue)
@Serializable private data class LocalStoredEffects(
    val version: Int, val scope: StoredInventoryScope, val operationId: OperationId, val effects: List<LocalStoredEffect>,
)

@Serializable
private sealed interface LocalStoredEffect {
    @Serializable @SerialName("movement")
    data class Movement(val sourceId: DomainId, val position: StoredPositionKey, val quantityDelta: Quantity,
        val reason: String, val sourceAllocationId: DomainId?) : LocalStoredEffect
    @Serializable @SerialName("valuation")
    data class Valuation(val pool: StoredPoolKey, val requiredRevision: Revision, val resultingRevision: Revision,
        val fact: StoredValueFact?, val quantityDelta: Quantity, val originalValueDelta: Money,
        val carryingValueDelta: Money, val linkedAdjustment: StoredCostAdjustment?) : LocalStoredEffect
    @Serializable @SerialName("reservation")
    data class Reservation(val value: StoredReservation) : LocalStoredEffect
    @Serializable @SerialName("release")
    data class Release(val value: StoredReservation, val resolution: LocalStoredResolution?) : LocalStoredEffect
    @Serializable @SerialName("safety")
    data class Safety(val before: StoredPosition, val after: StoredPosition) : LocalStoredEffect
    @Serializable @SerialName("serial_registration")
    data class SerialRegistration(val before: StoredPosition, val after: StoredPosition) : LocalStoredEffect
    @Serializable @SerialName("issue")
    data class Issue(val value: StoredIssue) : LocalStoredEffect
    @Serializable @SerialName("count")
    data class Count(val countId: DomainId, val position: StoredPositionKey, val capturedRevision: Revision, val counted: Quantity) : LocalStoredEffect
    @Serializable @SerialName("kitchen_confirmation")
    data class KitchenConfirmation(val confirmationId: DomainId, val issueId: DomainId, val issueRevision: Revision,
        val confirmedBy: DomainId, val confirmedStationIds: List<DomainId>) : LocalStoredEffect
    @Serializable @SerialName("waste")
    data class Waste(val issueId: DomainId, val originalValue: Money, val reasonId: DomainId) : LocalStoredEffect
}

@Serializable
private data class LocalStoredResolution(
    val resolutionId: DomainId, val orderId: DomainId, val collectionId: DomainId, val preservedObligationId: DomainId,
)

private fun storedLocalEffect(effect: InventoryEffect): LocalStoredEffect = when (effect) {
    is InventoryEffect.Movement -> LocalStoredEffect.Movement(effect.sourceId, StoredPositionKey.from(effect.position),
        effect.quantityDelta, effect.reason.name, effect.sourceAllocationId)
    is InventoryEffect.Valuation -> LocalStoredEffect.Valuation(StoredPoolKey.from(effect.pool), effect.plan.requiredRevision,
        effect.plan.next.revision, effect.plan.fact?.let(StoredValueFact::from), effect.plan.quantityDelta,
        effect.plan.originalValueDelta, effect.plan.carryingValueDelta, effect.plan.linkedAdjustment?.let(StoredCostAdjustment::from))
    is InventoryEffect.ReservationRecorded -> LocalStoredEffect.Reservation(StoredReservation.from(effect.reservation))
    is InventoryEffect.ReservationReleased -> LocalStoredEffect.Release(StoredReservation.from(effect.reservation), effect.resolution?.let {
        LocalStoredResolution(it.resolutionId, it.orderId, it.collectionId, it.preservedObligationId)
    })
    is InventoryEffect.SafetyChanged -> LocalStoredEffect.Safety(StoredPosition.from(effect.before), StoredPosition.from(effect.after))
    is InventoryEffect.SerialRegistrationConfirmed -> LocalStoredEffect.SerialRegistration(StoredPosition.from(effect.before), StoredPosition.from(effect.after))
    is InventoryEffect.IssueRecorded -> LocalStoredEffect.Issue(StoredIssue.from(effect.issue))
    is InventoryEffect.CountReconciled -> LocalStoredEffect.Count(effect.countId, StoredPositionKey.from(effect.position),
        effect.capturedRevision, effect.counted)
    is InventoryEffect.KitchenConfirmed -> effect.confirmation.let { LocalStoredEffect.KitchenConfirmation(it.confirmationId,
        it.issueId, it.issueRevision, it.confirmedBy, it.confirmedStationIds) }
    is InventoryEffect.WasteClassified -> LocalStoredEffect.Waste(effect.issueId, effect.originalValue, effect.reasonId)
}

private fun crosswalkLocalEffects(source: InventorySnapshot, plan: InventoryPlan) {
    val after = plan.nextSnapshot
    inventorySnapshotCheck(after.operationIds == source.operationIds + plan.operationId &&
        after.sourceIds.take(source.sourceIds.size) == source.sourceIds && after.sourceIds.distinct().size == after.sourceIds.size)
    val newSources = after.sourceIds.drop(source.sourceIds.size).toSet()
    val representedSources = mutableSetOf<DomainId>()
    val positions = source.positions.associateBy { it.key }.toMutableMap()
    val pools = source.pools.associateBy { it.key }.toMutableMap()
    val reservations = source.reservations.associateBy { it.reservationId }.toMutableMap()
    val issues = source.issues.associateBy { it.issueId }.toMutableMap()
    val lastMovements = mutableMapOf<StockPositionKey, InventoryEffect.Movement>()
    for (effect in plan.effects) when (effect) {
        is InventoryEffect.Movement -> {
            inventorySnapshotCheck(effect.sourceId in newSources && effect.quantityDelta.scaled != 0L)
            representedSources += effect.sourceId
            val old = positions[effect.position]
            val result = after.position(effect.position)
                ?: throw InventorySnapshotRejectedException(InventorySnapshotFailure.INCONSISTENT_STATE)
            val before = old ?: result.copy(onHand = zeroLike(effect.quantityDelta))
            requireSameUnit(before.onHand, effect.quantityDelta)
            positions[effect.position] = before.copy(onHand = before.onHand + effect.quantityDelta)
            lastMovements[effect.position] = effect
        }
        is InventoryEffect.Valuation -> {
            val previous = pools[effect.pool]
            val value = effect.plan
            if (previous == null) inventorySnapshotCheck(value.sourceBasis.revision == Revision(0) &&
                value.sourceBasis.onHand.scaled == 0L && value.sourceBasis.carryingValue.minor == 0L && value.sourceBasis.eventIds.isEmpty())
            else inventorySnapshotCheck(previous.value === value.sourceBasis, InventorySnapshotFailure.BINDING_MISMATCH)
            inventorySnapshotCheck(value.requiredRevision == value.sourceBasis.revision && value.next.revision == value.requiredRevision.next() &&
                value.next.onHand == value.sourceBasis.onHand + value.quantityDelta &&
                value.next.carryingValue == value.sourceBasis.carryingValue + value.carryingValueDelta)
            value.fact?.let { representedSources += it.factId }
            value.linkedAdjustment?.takeIf { it.changedSource != null || it.transferCorrection != null }?.let {
                representedSources += it.adjustmentId
            }
            pools[effect.pool] = OwnedValuationPool(effect.pool, value.next)
        }
        is InventoryEffect.SafetyChanged -> {
            val last = lastMovements[effect.before.key]
            val quantityCorresponds = effect.before.onHand == effect.after.onHand ||
                (last?.reason == StockMovementReason.COUNT_SHORTAGE && last.quantityDelta.scaled < 0 &&
                    effect.before.onHand + last.quantityDelta == effect.after.onHand)
            // Count's before fact is the captured physical position; its preceding movement has
            // already applied the shortage. Do not apply it a second time or discard that fact.
            inventorySnapshotCheck(positions[effect.before.key] == effect.before.copy(onHand = effect.after.onHand) &&
                effect.after.key == effect.before.key && quantityCorresponds &&
                effect.before.expiresAtEpochMillis == effect.after.expiresAtEpochMillis &&
                effect.before.serialRegistrationId == effect.after.serialRegistrationId)
            positions[effect.after.key] = effect.after
        }
        is InventoryEffect.SerialRegistrationConfirmed -> {
            requireSerialRegistrationTransition(effect.before, effect.after)
            inventorySnapshotCheck(positions[effect.before.key] == effect.before)
            positions[effect.after.key] = effect.after
        }
        is InventoryEffect.ReservationRecorded -> reservations[effect.reservation.reservationId] = effect.reservation
        is InventoryEffect.ReservationReleased -> reservations[effect.reservation.reservationId] = effect.reservation
        is InventoryEffect.IssueRecorded -> issues[effect.issue.issueId] = effect.issue
        is InventoryEffect.CountReconciled -> {
            inventorySnapshotCheck(effect.countId in newSources && positions[effect.position]?.onHand == effect.counted)
            representedSources += effect.countId
        }
        is InventoryEffect.KitchenConfirmed -> inventorySnapshotCheck(issues[effect.confirmation.issueId] != null)
        is InventoryEffect.WasteClassified -> inventorySnapshotCheck(issues[effect.issueId]?.originalValue == effect.originalValue)
    }
    inventorySnapshotCheck(representedSources == newSources && positions == after.positions.associateBy { it.key } &&
        pools.keys == after.pools.map { it.key }.toSet() && pools.all { (key, value) -> after.pool(key)?.value === value.value })
    inventorySnapshotCheck(reservations.keys == after.reservations.map { it.reservationId }.toSet() && reservations.all { (id, value) ->
        InventoryRecordCodec.encodeReservation(value, source.scope) == InventoryRecordCodec.encodeReservation(requireNotNull(after.reservation(id)), source.scope)
    })
    inventorySnapshotCheck(issues.keys == after.issues.map { it.issueId }.toSet() && issues.all { (id, value) ->
        InventoryRecordCodec.encodeIssue(value, source.scope) == InventoryRecordCodec.encodeIssue(requireNotNull(after.issue(id)), source.scope)
    })
}
