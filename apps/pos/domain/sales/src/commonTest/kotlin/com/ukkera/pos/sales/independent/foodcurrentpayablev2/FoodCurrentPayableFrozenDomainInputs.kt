package com.ukkera.pos.sales.independent.foodcurrentpayablev2

import com.ukkera.pos.pricing.OperationalPricingEngine
import com.ukkera.pos.pricing.PricingRequest
import com.ukkera.pos.pricing.PricingSnapshot
import com.ukkera.pos.pricing.ResolvedPriceLine
import com.ukkera.pos.pricing.TaxInclusion
import com.ukkera.pos.pricing.TaxSnapshot
import com.ukkera.pos.sales.FoodPreparation
import com.ukkera.pos.sales.FoodPreparationState
import com.ukkera.pos.sales.FoodPreparationStatus
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.PercentageRate
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitPrice
import com.ukkera.pos.types.UtcInstant

/** Logical F0/F1/F2 inputs from the frozen cases; no publication or durable custody is asserted. */
internal object FoodCurrentPayableFrozenDomainInputs {
    fun id(number: Int): DomainId = DomainId("c2800000-0000-4000-8000-${number.toString().padStart(12, '0')}")

    val p1 = id(1)
    val p2 = id(2)
    val p3 = id(3)
    val p4 = id(4)
    val batchA = id(11)
    val batchB = id(12)
    val cancellationReason = id(13)
    val unit = id(14)
    val cancellationAt = UtcInstant.parseCanonical("2026-10-31T09:00:00Z")

    fun egp(minor: Long): Money = Money(Currency.EGP, minor)

    fun resolvedLines(portions: List<DomainId>, unitPrice: String, priceRevision: Revision): List<ResolvedPriceLine> =
        portions.map { portion ->
            ResolvedPriceLine(portion, portion, priceRevision, Quantity(unit, 1, 0),
                UnitPrice.parse(Currency.EGP, unitPrice), TaxInclusion.EXCLUSIVE,
                TaxSnapshot(id(15), Revision(1), "ZERO", PercentageRate.parse("0")))
        }

    fun quote(batchId: DomainId, lines: List<ResolvedPriceLine>): PricingSnapshot =
        OperationalPricingEngine.calculate(PricingRequest(batchId, Revision(0), Currency.EGP, lines))

    fun unsent(portionId: DomainId): FoodPreparationState =
        FoodPreparationState(portionId, Revision(0), FoodPreparationStatus.NOT_SENT)

    fun cancelUnsent(before: FoodPreparationState): FoodPreparationState =
        FoodPreparation.requestCancellation(before, before.revision, cancellationReason, cancellationAt)
}
