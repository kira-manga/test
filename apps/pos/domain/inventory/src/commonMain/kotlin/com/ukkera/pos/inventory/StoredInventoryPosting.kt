package com.ukkera.pos.inventory

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Private wire shapes deliberately reuse immutable state DTOs, never planner/proof DTOs. */
@Serializable
internal data class StoredInventoryPosting(
    val schemaVersion: Int,
    val profile: String,
    val calculationVersion: String,
    val scope: StoredInventoryScope,
    val operationId: OperationId,
    val requiredInventoryRevision: Revision,
    val resultingInventoryRevision: Revision,
    val effects: List<StoredInventoryPostingEffect>,
) {
    fun binding(): InventoryPostingBinding = InventoryPostingBinding(scope.restore(), operationId,
        requiredInventoryRevision, resultingInventoryRevision)

    companion object {
        fun from(batch: InventoryPostingBatch): StoredInventoryPosting = StoredInventoryPosting(
            InventoryPostingCodec.SCHEMA_VERSION, InventoryPostingCodec.PROFILE, MovingAveragePlanner.CALCULATION_VERSION,
            StoredInventoryScope.from(batch.binding.scope), batch.binding.operationId,
            batch.binding.requiredInventoryRevision, batch.binding.resultingInventoryRevision,
            batch.effects.map(StoredInventoryPostingEffect::from))
    }
}

@Serializable
internal sealed class StoredInventoryPostingEffect {
    abstract fun restore(): InventoryPostingEffect

    @Serializable
    @SerialName("movement")
    data class Movement(
        val sourceId: DomainId,
        val position: StoredPositionKey,
        val quantityDelta: Quantity,
        val reason: String,
        val sourceAllocationId: DomainId?,
    ) : StoredInventoryPostingEffect() {
        override fun restore(): InventoryPostingEffect = InventoryPostingEffect.Movement(sourceId, position.restore(),
            quantityDelta, enumValueOf<StockMovementReason>(reason), sourceAllocationId)
    }

    @Serializable
    @SerialName("valuation")
    data class Valuation(
        val pool: StoredPoolKey,
        val requiredPoolRevision: Revision,
        val resultingPoolRevision: Revision,
        val fact: StoredValueFact,
        val quantityDelta: Quantity,
        val originalValueDelta: Money,
        val carryingValueDelta: Money,
    ) : StoredInventoryPostingEffect() {
        override fun restore(): InventoryPostingEffect = InventoryPostingEffect.Valuation(pool.restore(), requiredPoolRevision,
            resultingPoolRevision, fact.restore(), quantityDelta, originalValueDelta, carryingValueDelta)
    }

    @Serializable
    @SerialName("reservation")
    data class ReservationRecorded(val reservation: StoredReservation) : StoredInventoryPostingEffect() {
        override fun restore(): InventoryPostingEffect = InventoryPostingEffect.ReservationRecorded(reservation.restore())
    }

    @Serializable
    @SerialName("safety")
    data class SafetyChanged(val before: StoredPosition, val after: StoredPosition) : StoredInventoryPostingEffect() {
        override fun restore(): InventoryPostingEffect = InventoryPostingEffect.SafetyChanged(before.restore(), after.restore())
    }

    @Serializable
    @SerialName("issue")
    data class IssueRecorded(val issue: StoredIssue) : StoredInventoryPostingEffect() {
        override fun restore(): InventoryPostingEffect = InventoryPostingEffect.IssueRecorded(issue.restore())
    }

    companion object {
        fun from(effect: InventoryPostingEffect): StoredInventoryPostingEffect = when (effect) {
            is InventoryPostingEffect.Movement -> Movement(effect.sourceId, StoredPositionKey.from(effect.position),
                effect.quantityDelta, effect.reason.name, effect.sourceAllocationId)
            is InventoryPostingEffect.Valuation -> Valuation(StoredPoolKey.from(effect.pool), effect.requiredPoolRevision,
                effect.resultingPoolRevision, StoredValueFact.from(effect.fact), effect.quantityDelta,
                effect.originalValueDelta, effect.carryingValueDelta)
            is InventoryPostingEffect.ReservationRecorded -> ReservationRecorded(StoredReservation.from(effect.reservation))
            is InventoryPostingEffect.SafetyChanged -> SafetyChanged(StoredPosition.from(effect.before), StoredPosition.from(effect.after))
            is InventoryPostingEffect.IssueRecorded -> IssueRecorded(StoredIssue.from(effect.issue))
        }
    }
}
