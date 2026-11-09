package com.ukkera.pos.sales.store.independent.foodpricebatchoriginv1

import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.RetainedStreamedCommand
import com.ukkera.pos.pricing.DiscountLimits
import com.ukkera.pos.pricing.OperationalPricingEngine
import com.ukkera.pos.pricing.PricingProfiles
import com.ukkera.pos.pricing.PricingRequest
import com.ukkera.pos.pricing.PromotionResolution
import com.ukkera.pos.pricing.ResolvedPriceLine
import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.pricing.TaxInclusion
import com.ukkera.pos.pricing.TaxPostingPolicy
import com.ukkera.pos.pricing.TaxSnapshot
import com.ukkera.pos.sales.store.FoodBodyV1
import com.ukkera.pos.sales.store.FoodEnvelopeV1
import com.ukkera.pos.sales.store.LocalFoodProfileV1
import com.ukkera.pos.sales.store.LocalSalesPriceSourceV2
import com.ukkera.pos.sales.store.foodEnvelopeBytes
import com.ukkera.pos.sales.store.foodPriceBatchProducerProfile
import com.ukkera.pos.sales.store.requireFoodEnvelope
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.PercentageRate
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitPrice
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Frozen food-price-batch-origin-v1 coverage: FBO-004's B_ADD/null/null row and
 * FBO-009 input preservation for that invocation only. Producer-bearing variants
 * and FBO-010–014 remain unbound; this supplies no native/source/permission proof.
 */
class FoodPriceBatchOriginFrozenTest {
    @Test
    fun fbo004MissingOriginalProducerRefusesAndFbo009PreservesInputs() {
        val scope = BranchScope(
            DomainId("a3100000-0000-0000-0000-000000000001"),
            DomainId("a3100000-0000-0000-0000-000000000002"),
            DomainId("a3100000-0000-0000-0000-000000000003"),
        )
        val p1 = DomainId("a3100000-0000-0000-0000-000000000004")
        val p2 = DomainId("a3100000-0000-0000-0000-000000000104")
        val line = p1
        val order = DomainId("a3100000-0000-0000-0000-000000000100")
        val batchId = DomainId("a3100000-0000-0000-0000-000000000102")
        val quantity = Quantity(DomainId("a3100000-0000-0000-0000-000000000013"), 150, 2)
        val unitPrice = UnitPrice.parse(Currency.EGP, "110.00")
        val tax = TaxSnapshot(
            DomainId("a3100000-0000-0000-0000-000000000110"),
            Revision(1),
            "FOOD",
            PercentageRate.parse("0"),
        )
        val members = listOf(p1, p2)

        // The public pricing factory constructs the frozen input, not its amount oracle.
        val pricing = OperationalPricingEngine.calculate(PricingRequest(
            snapshotId = batchId,
            revision = Revision(0),
            currency = Currency.EGP,
            lines = members.map { portion ->
                ResolvedPriceLine(line, portion, Revision(5), quantity, unitPrice, TaxInclusion.EXCLUSIVE, tax)
            },
        ))
        val retainedQuote = StoredPricingSnapshotCodec.encode(pricing)
        val sources = members.map { portion ->
            LocalSalesPriceSourceV2(
                scope = scope,
                lineId = line,
                portionId = portion,
                skuId = DomainId("a3100000-0000-0000-0000-000000000009"),
                companyPublicationId = DomainId("a3100000-0000-0000-0000-000000000108"),
                companyPriceRuleId = DomainId("a3100000-0000-0000-0000-000000000109"),
                companyPriceRevision = Revision(5),
                companyPriceDigest = PayloadDigest("b3db074c3128d10d016d2277d5bf14a0a682b0e1e71cd4b316a5ada5e29b80da"),
                companyUnitPrice = unitPrice,
                effectiveUnitPrice = unitPrice,
                policyEntryId = null,
                policyEntryRevision = null,
                policyPublicationId = null,
                policyEntryDigest = null,
                branchOverrideEventId = null,
                branchOverrideRevision = null,
            )
        }
        val body = FoodBodyV1.PriceBatch(order, retainedQuote, sources)
        val batch = FoodEnvelopeV1(
            scope,
            LocalFoodProfileV1.PRICE_BATCH,
            batchId,
            Revision(1),
            OperationId("a3100000-0000-0000-0000-000000000102"),
            body,
        )
        val legacyProducer: RetainedCommandEvidence? = null
        val normalizedProducer: RetainedStreamedCommand? = null

        // A fixture-construction/validation failure here is not classifier execution.
        requireFoodEnvelope(batch)
        val retained = StoredPricingSnapshotCodec.decode(body.quote)
        assertEquals(batch.id.value, batch.operationId.value, "Frozen batch/original operation identity")
        assertEquals(batchId, retained.snapshotId)
        assertEquals(Revision(0), retained.revision)
        assertEquals(Currency.EGP, retained.currency)
        assertEquals(PricingProfiles.LINEAR_PROTECTED_V2, retained.profile)
        assertEquals(PromotionResolution.NONE, retained.promotionResolution)
        assertEquals(TaxPostingPolicy.OPERATIONAL_COMPONENT_ALLOCATION, retained.taxPostingPolicy)
        assertEquals(DiscountLimits.none(Currency.EGP), retained.discountLimits)
        assertTrue(retained.charges.isEmpty())
        assertTrue(retained.discounts.isEmpty())
        assertEquals(members, retained.lines.map { it.portionId })
        assertEquals(members, body.sources.map { it.portionId })
        assertEquals(listOf(line, line), retained.lines.map { it.lineId })
        assertEquals(listOf(quantity, quantity), retained.lines.map { it.quantity })
        assertEquals(listOf(unitPrice, unitPrice), retained.lines.map { it.unitPrice })
        assertEquals(listOf(TaxInclusion.EXCLUSIVE, TaxInclusion.EXCLUSIVE), retained.lines.map { it.inclusion })
        assertEquals(listOf(tax, tax), retained.lines.map { it.tax })
        // Frozen arithmetic: 11000 * 150 / 100 = 16500 per member, 33000 total.
        assertEquals(listOf(Money(Currency.EGP, 16500), Money(Currency.EGP, 16500)), retained.lines.map { it.payable })
        assertEquals(Money(Currency.EGP, 33000), retained.beforeDiscountPayable)
        assertEquals(Money(Currency.EGP, 33000), retained.payable)

        val batchBefore = batch.copy(body = body.copy(sources = body.sources.map { it.copy() }))
        val envelopeBytesBefore = foodEnvelopeBytes(batch).copyBytes()
        val quoteBytesBefore = body.quote.encodeToByteArray()
        val sourcesBefore = body.sources.map { it.copy() }
        val linesBefore = retained.lines.toList()
        val legacyBefore = legacyProducer
        val normalizedBefore = normalizedProducer

        // Call the real common classifier; no producer descriptor, native reader or fallback.
        val outcome = runCatching { foodPriceBatchProducerProfile(batch, legacyProducer, normalizedProducer) }

        val bodyAfter = batch.body as FoodBodyV1.PriceBatch
        val envelopeBytesAfter = foodEnvelopeBytes(batch).copyBytes()
        val quoteBytesAfter = bodyAfter.quote.encodeToByteArray()
        val retainedAfter = StoredPricingSnapshotCodec.decode(bodyAfter.quote)
        assertEquals(batchBefore, batch, "FBO-009 complete batch fields remain unchanged")
        assertContentEquals(envelopeBytesBefore, envelopeBytesAfter, "FBO-009 complete envelope encoding")
        assertContentEquals(quoteBytesBefore, quoteBytesAfter, "FBO-009 retained canonical pricing bytes")
        assertEquals(sourcesBefore, bodyAfter.sources, "FBO-009 complete ordered price sources")
        assertEquals(linesBefore, retainedAfter.lines, "FBO-009 complete ordered retained price lines")
        assertEquals(legacyBefore, legacyProducer, "FBO-009 legacy slot remains unchanged")
        assertEquals(normalizedBefore, normalizedProducer, "FBO-009 normalized slot remains unchanged")
        assertNull(legacyProducer)
        assertNull(normalizedProducer)
        assertEquals(Money(Currency.EGP, 33000), retainedAfter.beforeDiscountPayable)
        assertEquals(Money(Currency.EGP, 33000), retainedAfter.payable)
        assertTrue(outcome.isFailure, "FBO-004 B_ADD/null/null must refuse")
        assertNull(outcome.getOrNull(), "FBO-004 refusal supplies no usable classification")
    }
}
