package com.ukkera.pos.sales.store.supporting.financialresolver

import com.ukkera.pos.database.*
import com.ukkera.pos.pricing.*
import com.ukkera.pos.sales.FoodPriceBatch
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlin.test.*

/** SELF-AUTHORED, NOT independent. Frozen LAN-A07/D04/D06 and FOOD financial source
 * requirements support only: fresh decoding preserves identical complete source values;
 * changed row/frame/full quote is refused. These are comparison DATA controls, no valid
 * native original/financial document, authority, Start or whole-runtime qualification. */
class FoodPaymentFreshCutComparisonSupportingTest {
    private fun id() = DomainId.random()
    private val scope = BranchScope(id(), id(), id())
    private val rowId = id(); private val batchId = id(); private val portion = id(); private val unit = id(); private val tax = id()
    private val operation = OperationId.random()
    private val sourceDigest = sha256("supporting.source.only".encodeToByteArray())
    private fun quote(price: String): PricingSnapshot = OperationalPricingEngine.calculate(PricingRequest(batchId, Revision(0), Currency.EGP,
        listOf(ResolvedPriceLine(portion, portion, Revision(1), Quantity(unit, 1, 0), UnitPrice.parse(Currency.EGP, price),
            TaxInclusion.EXCLUSIVE, TaxSnapshot(tax, Revision(1), "SYNTHETIC_ZERO", PercentageRate.parse("0"))))))
    private fun input(price: String = "10"): FoodFinancialInputV1 {
        val decoded = StoredPricingSnapshotCodec.decode(StoredPricingSnapshotCodec.encode(quote(price)))
        val envelope = FoodFinancialEnvelopeV1(scope, LocalFoodFinancialProfileV1.COLLECTION, rowId, Revision(1), operation,
            FoodFinancialBodyV1.Collection("comparison-data-only", listOf("same-source-frame")), framed = true,
            originalBatches = listOf(FoodPriceBatch(batchId, sourceDigest, decoded)))
        val raw = "same literal row bytes" // Not a valid document; no source/authority join is asserted.
        return FoodFinancialInputV1(envelope.kind, rowId, Revision(1), "comparison-data/v1", sha256(raw.encodeToByteArray()), operation, raw,
            financial = envelope)
    }
    @Test fun freshDecodedBatchesHaveEqualValuesDespiteDistinctObjectIdentity() {
        val first = input(); val second = input()
        assertNotSame(first.financial!!.originalBatches.single(), second.financial!!.originalBatches.single())
        assertTrue(sameFoodPaymentCutInputs(listOf(first), listOf(second)))
    }
    @Test fun completeQuoteAndCompanionFramesAreRequiredBeyondSameIdsAndDigest() {
        val first = input(); val changedQuote = input("11")
        assertFalse(sameFoodPaymentCutInputs(listOf(first), listOf(changedQuote)))
        val envelope = first.financial!!
        val changedFrame = first.copy(financial = envelope.copy(body = FoodFinancialBodyV1.Collection(
            "comparison-data-only", listOf("different-source-frame"))))
        assertFalse(sameFoodPaymentCutInputs(listOf(first), listOf(changedFrame)))
        val changedDigest = first.copy(financial = envelope.copy(originalBatches = listOf(FoodPriceBatch(batchId,
            sha256("other-source".encodeToByteArray()), envelope.originalBatches.single().quote))))
        assertFalse(sameFoodPaymentCutInputs(listOf(first), listOf(changedDigest)))
    }
    @Test fun everyLiteralRowIdentityAndFootprintFieldRemainsExact() {
        val first = input()
        for (changed in listOf(first.copy(id = id()), first.copy(revision = Revision(2)), first.copy(schema = "other-schema/v1"),
            first.copy(digest = sourceDigest), first.copy(operationId = OperationId.random()), first.copy(payload = "different literal row bytes"),
            first.copy(financial = null), first.copy(financial = first.financial!!.copy(framed = false)),
            first.copy(financial = first.financial!!.copy(originalBatches = emptyList()))))
            assertFalse(sameFoodPaymentCutInputs(listOf(first), listOf(changed)))
        assertFalse(sameFoodPaymentCutInputs(listOf(first), emptyList()))
    }
}
