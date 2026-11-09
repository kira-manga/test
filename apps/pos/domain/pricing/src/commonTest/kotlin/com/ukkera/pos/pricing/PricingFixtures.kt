package com.ukkera.pos.pricing

import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.ExactDecimal
import com.ukkera.pos.types.ExactRational
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.PercentageRate
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitPrice

internal val EGP = Currency.EGP
internal val unit = id(9_000)
internal fun id(value: Int): DomainId = DomainId("00000000-0000-4000-8000-${value.toString().padStart(12, '0')}")
internal fun money(minor: Long): Money = Money(EGP, minor)
internal fun exact(value: String): ExactRational = ExactDecimal.parse(value).toFraction()
internal fun limits(minor: Long = Long.MAX_VALUE, percent: String = "100"): DiscountLimits =
    DiscountLimits(money(minor), PercentageRate.parse(percent))
internal fun tax(percent: String = "0", index: Int = 8_000): TaxSnapshot =
    TaxSnapshot(id(index), Revision(1), "HYPOTHETICAL-$index", PercentageRate.parse(percent))
internal fun line(
    index: Int,
    price: String,
    quantity: String = "1",
    percent: String = "0",
    inclusion: TaxInclusion = TaxInclusion.EXCLUSIVE,
    cap: DiscountLimits = limits(),
    scale: Int = 6,
): ResolvedPriceLine = ResolvedPriceLine(id(index + 1_000), id(index), Revision(1),
    Quantity.parse(unit, quantity, scale), UnitPrice.parse(EGP, price), inclusion, tax(percent), cap)

internal fun service(
    portions: Set<DomainId>,
    percent: String = "10",
    taxPercent: String = "14",
    index: Int = 7_000,
    treatment: ChargeRefundTreatment = ChargeRefundTreatment.PROPORTIONAL_WITH_ITEMS,
): PricingCharge.Percentage = PricingCharge.Percentage(id(index), Revision(1), ChargeKind.SERVICE,
    PercentageRate.parse(percent), portions, tax(taxPercent, index + 1), treatment)

internal fun fixed(
    minor: Long,
    portions: List<DomainId>,
    index: Int = 6_000,
    cap: DiscountLimits = limits(),
    stacking: StackingPermission = StackingPermission.EXCLUSIVE,
    stage: AdjustmentStage = AdjustmentStage.ORDER_OR_MANUAL_DISCOUNT,
): PriceAdjustment = PriceAdjustment(id(index), Revision(1), stage, DiscountValue.FixedPayable(money(minor)),
    portions.map { EligibleDiscountPortion(it) }, cap, stacking)

internal fun request(
    lines: List<ResolvedPriceLine>,
    charges: List<PricingCharge> = emptyList(),
    adjustments: List<PriceAdjustment> = emptyList(),
    cap: DiscountLimits = limits(),
): PricingRequest = PricingRequest(id(9_999), Revision(7), EGP, lines, charges, adjustments, cap)
