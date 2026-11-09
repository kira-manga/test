package com.ukkera.pos.sales.independent.foodcurrentpayablev3

import com.ukkera.pos.pricing.PricingSnapshot
import com.ukkera.pos.pricing.ResolvedPriceLine
import com.ukkera.pos.pricing.TaxInclusion
import com.ukkera.pos.sales.FoodPreparationState
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.ExactRational

/** Separately frozen FCP3-POSTING-1.0.0 inputs; original V3 expectations are not replaced. */
internal object FoodCurrentPayableV3PostingResolutionInputs {
    private val original = FoodCurrentPayableV3DomainInputs

    val mix = original.mix.map { item ->
        if (item.label == "D") item.copy(net = 333, tax = 47, gross = 380) else item
    }
    val mixExactMinor = mapOf(
        "A" to (ExactRational.ratio(10_000, 1) to ExactRational.ratio(1_400, 1)),
        "B" to (ExactRational.ratio(4_005, 1) to ExactRational.ratio(5_607, 10)),
        "C" to (ExactRational.ratio(149_950, 57) to ExactRational.ratio(20_993, 57)),
        "D" to (ExactRational.ratio(667, 2) to ExactRational.ratio(4_669, 100)),
        "E" to (ExactRational.ratio(50, 1) to ExactRational.ratio(7, 1)),
        "F" to (ExactRational.ratio(10_000, 1) to ExactRational.ratio(1_400, 1)),
    )

    data class RoundPortion(
        val label: String,
        val portionId: DomainId,
        val net: Long,
        val tax: Long,
        val gross: Long,
    )

    // Initial identity-only R1 binding recorded before revised fixture creation or execution.
    // Consecutive canonical IDs follow the unchanged symbolic-label order, not observed postings.
    // R1a/R1b/R1c stay distinct quantity-one portions inside the same saved R1 document.
    val r1Batch = DomainId("73000000-0000-4000-8000-000000000501")
    val r1 = listOf(
        RoundPortion("R1a", DomainId("73000000-0000-4000-8000-000000000521"), 334, 47, 381),
        RoundPortion("R1b", DomainId("73000000-0000-4000-8000-000000000522"), 333, 47, 380),
        RoundPortion("R1c", DomainId("73000000-0000-4000-8000-000000000523"), 333, 47, 380),
    )
    // Preserve the canonical batch/portion binding already used by the original R2 pricing slice.
    val r2Batch = DomainId("73000000-0000-4000-8000-000000000502")
    val r2 = RoundPortion("R2a", DomainId("73000000-0000-4000-8000-000000000512"), 325, 46, 371)

    fun r1Lines(): List<ResolvedPriceLine> = r1.map { original.line(it.portionId, "3.335", TaxInclusion.EXCLUSIVE) }
    fun roundQuotes(): List<PricingSnapshot> = listOf(
        original.quote(r1Batch, r1Lines()),
        original.quote(r2Batch, listOf(original.line(r2.portionId, "3.25", TaxInclusion.EXCLUSIVE))),
    )
    fun roundStates(): List<FoodPreparationState> = listOf(
        original.unsent(r1[0].portionId),
        original.queued(r1[1].portionId),
        original.served(r1[2].portionId),
        original.preparing(r2.portionId),
    )
}
