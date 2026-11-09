package com.ukkera.pos.pricing

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.ExactRational
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision

public data class ReturnedPriceComponent internal constructor(
    public val key: String,
    public val kind: PriceComponentKind,
    public val chargeId: DomainId?,
    public val amount: Money,
)

public class StoredReturnSlice internal constructor(
    public val quantity: Quantity,
    public val payable: Money,
    components: List<ReturnedPriceComponent>,
    public val remaining: StoredReturnCursor,
) {
    public val components: List<ReturnedPriceComponent> = immutableList(components)
}

/**
 * Pure original-allocation cursor, not a transactional return authorization.
 * Sales must persist/serialize consumption under the selling branch's entitlement
 * revision BEFORE refund payment. Reusing an older cursor must be rejected there.
 *
 * Each component uses cumulative floor(originalMinor * consumedQty / originalQty).
 * This is monotone, partition-independent and bounded. The last quantity absorbs
 * its original cents, rather than repricing retained/returned goods or using a
 * potentially non-monotone largest-remainder allocation at every partial return.
 */
public class StoredReturnCursor private constructor(
    public val originalSnapshotId: DomainId,
    public val revision: Revision,
    public val portionId: DomainId,
    public val originalQuantity: Quantity,
    public val consumedQuantity: Quantity,
    originalComponents: List<ReturnedPriceComponent>,
) {
    public val profile: String get() = PricingProfiles.STORED_RETURN_V1
    public val originalComponents: List<ReturnedPriceComponent> = immutableList(originalComponents)
    public val remainingQuantity: Quantity get() = originalQuantity - consumedQuantity

    public val remainingComponents: List<ReturnedPriceComponent>
        get() = immutableList(originalComponents.map { component ->
            component.copy(amount = component.amount - consumedAmount(component, consumedQuantity.scaled))
        })

    public val remainingPayable: Money
        get() = remainingComponents.map { it.amount }.sumMoney(originalComponents.first().amount.currency)

    public fun consume(quantity: Quantity): StoredReturnSlice {
        require(quantity.unitId == originalQuantity.unitId && quantity.scale == originalQuantity.scale) {
            "Return quantity must use the original unit and precision snapshot; convert exactly first"
        }
        require(quantity.scaled > 0 && quantity.scaled <= remainingQuantity.scaled) {
            "Return quantity exceeds remaining original entitlement or is not positive"
        }
        val nextQuantity = consumedQuantity + quantity
        val returned = originalComponents.map { component ->
            val amount = consumedAmount(component, nextQuantity.scaled) - consumedAmount(component, consumedQuantity.scaled)
            check(amount.minor >= 0) { "Cumulative original allocation cannot decrease" }
            component.copy(amount = amount)
        }
        val next = StoredReturnCursor(
            originalSnapshotId, revision, portionId, originalQuantity, nextQuantity, originalComponents,
        )
        val currency = originalComponents.first().amount.currency
        val payable = returned.map { it.amount }.sumMoney(currency)
        check(payable + next.remainingPayable == remainingPayable) { "Original return allocation did not conserve" }
        return StoredReturnSlice(quantity, payable, returned, next)
    }

    private fun consumedAmount(component: ReturnedPriceComponent, scaledQuantity: Long): Money =
        Money(component.amount.currency,
            (ExactRational.ratio(component.amount.minor, 1) *
                ExactRational.ratio(scaledQuantity, originalQuantity.scaled)).floorScaled(0))

    internal companion object {
        fun start(
            snapshot: PricingSnapshot,
            portionId: DomainId,
            explicitlyRefundableChargeIds: Set<DomainId>,
        ): StoredReturnCursor {
            val line = snapshot.lines.singleOrNull { it.portionId == portionId }
                ?: throw IllegalArgumentException("Unknown original sale portion")
            val portionComponents = snapshot.components.filter { it.portionId == portionId }
            require(explicitlyRefundableChargeIds.all { id ->
                portionComponents.any { it.chargeId == id && it.refundTreatment == ChargeRefundTreatment.EXPLICIT_ONLY }
            }) { "Unknown, fixed, automatic or nonrefundable charge in explicit return selection" }
            val eligible = portionComponents.filter { component ->
                component.chargeId == null ||
                    component.refundTreatment == ChargeRefundTreatment.PROPORTIONAL_WITH_ITEMS ||
                    (component.refundTreatment == ChargeRefundTreatment.EXPLICIT_ONLY &&
                        component.chargeId in explicitlyRefundableChargeIds)
            }.map { ReturnedPriceComponent(it.key, it.kind, it.chargeId, it.posted) }
            check(eligible.isNotEmpty()) { "Original portion must contain its net and tax postings" }
            return StoredReturnCursor(snapshot.snapshotId, snapshot.revision, portionId, line.quantity,
                line.quantity.copy(scaled = 0), eligible)
        }
    }
}
