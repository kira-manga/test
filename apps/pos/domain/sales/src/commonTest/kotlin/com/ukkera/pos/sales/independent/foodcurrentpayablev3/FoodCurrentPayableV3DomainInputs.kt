package com.ukkera.pos.sales.independent.foodcurrentpayablev3

import com.ukkera.pos.pricing.OperationalPricingEngine
import com.ukkera.pos.pricing.PricingRequest
import com.ukkera.pos.pricing.PricingSnapshot
import com.ukkera.pos.pricing.ResolvedPriceLine
import com.ukkera.pos.pricing.TaxInclusion
import com.ukkera.pos.pricing.TaxSnapshot
import com.ukkera.pos.sales.FoodPreparation
import com.ukkera.pos.sales.FoodPreparationState
import com.ukkera.pos.sales.FoodPreparationStatus
import com.ukkera.pos.sales.FoodStationDeliveryMode
import com.ukkera.pos.sales.FoodStationStatus
import com.ukkera.pos.sales.FoodStationTask
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.PercentageRate
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitPrice
import com.ukkera.pos.types.UtcInstant

/** FOOD-CURRENT-V3-FIXTURES-1 inputs; domain transitions do not establish native custody. */
internal object FoodCurrentPayableV3DomainInputs {
    data class SavedItem(val label: String, val batchId: DomainId, val portionId: DomainId,
        val unitPrice: String, val inclusion: TaxInclusion, val net: Long, val tax: Long, val gross: Long)

    fun sourceId(number: Int): DomainId = DomainId("73000000-0000-4000-8000-${number.toString().padStart(12, '0')}")
    fun portion(number: Int): DomainId = DomainId("40000000-0000-4000-8000-${number.toString().padStart(12, '0')}")
    fun batch(number: Int): DomainId = DomainId("30000000-0000-4000-8000-${number.toString().padStart(12, '0')}")
    fun fullPortion(number: Int): DomainId = DomainId("63000000-0000-4000-8000-${number.toString().padStart(12, '0')}")
    fun fullBatch(number: Int): DomainId = DomainId("62000000-0000-4000-8000-${number.toString().padStart(12, '0')}")
    val unit = sourceId(1)
    val at = UtcInstant.parseCanonical("2026-11-06T09:00:00Z")
    val tax = TaxSnapshot(sourceId(2), Revision(5), "SYNTHETIC_14_PERCENT", PercentageRate.parse("14"))
    val zeroTax = TaxSnapshot(sourceId(3), Revision(5), "SYNTHETIC_ZERO", PercentageRate.parse("0"))

    val mix = listOf(
        SavedItem("A", batch(1), portion(1), "114.00", TaxInclusion.INCLUSIVE, 10_000, 1_400, 11_400),
        SavedItem("B", batch(2), portion(2), "40.05", TaxInclusion.EXCLUSIVE, 4_005, 561, 4_566),
        SavedItem("C", batch(3), portion(3), "29.99", TaxInclusion.INCLUSIVE, 2_631, 368, 2_999),
        SavedItem("D", batch(4), portion(4), "3.335", TaxInclusion.EXCLUSIVE, 334, 47, 381),
        SavedItem("E", batch(5), portion(5), "0.57", TaxInclusion.INCLUSIVE, 50, 7, 57),
        SavedItem("F", batch(6), portion(6), "100.00", TaxInclusion.EXCLUSIVE, 10_000, 1_400, 11_400),
    )

    fun egp(minor: Long): Money = Money(Currency.EGP, minor)
    fun line(portionId: DomainId, price: String, inclusion: TaxInclusion,
        quantity: Long = 1, priceRevision: Revision = Revision(5), taxSnapshot: TaxSnapshot = tax): ResolvedPriceLine =
        ResolvedPriceLine(portionId, portionId, priceRevision, Quantity(unit, quantity, 0),
            UnitPrice.parse(Currency.EGP, price), inclusion, taxSnapshot)

    fun quote(batchId: DomainId, lines: List<ResolvedPriceLine>): PricingSnapshot =
        OperationalPricingEngine.calculate(PricingRequest(batchId, Revision(0), Currency.EGP, lines))
    fun quote(item: SavedItem): PricingSnapshot = quote(item.batchId, listOf(line(item.portionId, item.unitPrice, item.inclusion)))
    fun unsent(id: DomainId): FoodPreparationState = FoodPreparationState(id, Revision(0), FoodPreparationStatus.NOT_SENT)
    fun cancelUnsent(before: FoodPreparationState): FoodPreparationState =
        FoodPreparation.requestCancellation(before, before.revision, sourceId(4), at)

    fun queued(id: DomainId): FoodPreparationState {
        val before = unsent(id)
        return FoodPreparation.sent(before, before.revision, sourceId(100 + id.value.takeLast(3).toInt()),
            sourceId(200 + id.value.takeLast(3).toInt()),
            listOf(FoodStationTask(sourceId(300 + id.value.takeLast(3).toInt()), sourceId(400),
                FoodStationDeliveryMode.DISPLAY, FoodStationStatus.QUEUED)), at)
    }
    fun preparing(id: DomainId): FoodPreparationState = queued(id).let {
        FoodPreparation.start(it, it.revision, it.tasks.single().stationId, at)
    }
    fun ready(id: DomainId): FoodPreparationState = preparing(id).let {
        FoodPreparation.ready(it, it.revision, it.tasks.single().stationId, at)
    }
    fun served(id: DomainId): FoodPreparationState = ready(id).let { FoodPreparation.serve(it, it.revision, at) }
    fun mixedStates(): List<FoodPreparationState> = listOf(unsent(portion(1)), queued(portion(2)),
        preparing(portion(3)), ready(portion(4)), served(portion(5)), cancelUnsent(unsent(portion(6))))

    fun fullStates(): List<FoodPreparationState> = (1..32).map { number ->
        val id = fullPortion(number)
        when ((number - 1) % 4) {
            0 -> unsent(id)
            1 -> queued(id)
            2 -> preparing(id)
            else -> if ((number / 4) % 2 == 0) served(id) else ready(id)
        }
    }
    fun fullQuotes(eightPerBatch: Boolean): List<PricingSnapshot> =
        if (eightPerBatch) (1..32).chunked(8).mapIndexed { index, members ->
            quote(fullBatch(index + 1), members.map { line(fullPortion(it), "1.00", TaxInclusion.EXCLUSIVE) })
        } else (1..32).map { quote(fullBatch(it), listOf(line(fullPortion(it), "1.00", TaxInclusion.EXCLUSIVE))) }
}
