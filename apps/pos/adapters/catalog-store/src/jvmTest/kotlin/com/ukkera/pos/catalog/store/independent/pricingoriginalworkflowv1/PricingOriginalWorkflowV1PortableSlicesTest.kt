package com.ukkera.pos.catalog.store.independent.pricingoriginalworkflowv1

import com.ukkera.pos.catalog.store.LocalPricePolicyEntryV1
import com.ukkera.pos.catalog.store.LocalPriceV1
import com.ukkera.pos.catalog.store.LocalPricingCodec
import com.ukkera.pos.catalog.store.priceContextId
import com.ukkera.pos.catalog.store.selectedRetailPrice
import com.ukkera.pos.catalog.store.toNativeCompanyPricePolicyProposal
import com.ukkera.pos.pricing.AdjustmentStage
import com.ukkera.pos.pricing.DiscountLimits
import com.ukkera.pos.pricing.DiscountValue
import com.ukkera.pos.pricing.EligibleDiscountPortion
import com.ukkera.pos.pricing.OperationalPricingEngine
import com.ukkera.pos.pricing.PriceAdjustment
import com.ukkera.pos.pricing.PriceComponentKind
import com.ukkera.pos.pricing.PricingFailure
import com.ukkera.pos.pricing.PricingRejectedException
import com.ukkera.pos.pricing.PricingRequest
import com.ukkera.pos.pricing.ResolvedPriceLine
import com.ukkera.pos.pricing.TaxInclusion
import com.ukkera.pos.pricing.TaxSnapshot
import com.ukkera.pos.security.CompanyBusinessPublicationKind
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.ExactRational
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.PercentageRate
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitPrice
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** L1 slices only: no receipt, activation, protected authorization, durable result or RPC. */
class PricingOriginalWorkflowV1PortableSlicesTest {
    private val frozen = FrozenPricingOriginalWorkflowV1

    @Test
    fun sameRuleVersionsAndUnsignedPolicyProposalConserveOriginalData() {
        val base = frozen.fixture("F-BASE")
        val standard = base["standard"]!!.jsonObject
        val revision = frozen.fixture("F-REVISION")
        val old = frozen.price(standard.text("rule"), standard.text("company_revision"), standard.text("company_unit"))
        val oldBytes = frozen.json.encodeToString(LocalPriceV1.serializer(), old).encodeToByteArray()
        val newer = frozen.price(revision.text("new_rule"), revision.text("new_company_revision"), revision.text("new_unit"))

        assertEquals(old.ruleId, newer.ruleId, "C-STD-5/6 are versions of one stable rule")
        assertEquals(priceContextId(frozen.company, frozen.skuId, old.ruleId),
            priceContextId(frozen.company, frozen.skuId, newer.ruleId))
        assertEquals(Revision(5), old.revision)
        assertEquals(Revision(6), newer.revision)
        assertEquals(UnitPrice.parse(Currency.EGP, standard.text("company_unit")), old.unitPrice)
        assertEquals(UnitPrice.parse(Currency.EGP, revision.text("new_unit")), newer.unitPrice)
        assertEquals(old, frozen.json.decodeFromString(LocalPriceV1.serializer(), oldBytes.decodeToString()))
        assertContentEquals(oldBytes, frozen.json.encodeToString(LocalPriceV1.serializer(), old).encodeToByteArray())

        val policy = LocalPricePolicyEntryV1(frozen.company, frozen.id("PRICE-POLICY"),
            frozen.id("DETACHED-POLICY-5"), Revision(base.text("policy_revision").toLong()),
            frozen.skuId, old.ruleId, true, Quantity.parse(frozen.unitId, "1", 0), frozen.effectiveFrom, null)
        val proposal = policy.toNativeCompanyPricePolicyProposal()
        val canonical = LocalPricingCodec.canonicalPolicy(policy)
        assertEquals(frozen.company, proposal.company)
        assertEquals(CompanyBusinessPublicationKind.PRICE_POLICY_V1, proposal.kind)
        assertEquals(policy.publicationId, proposal.publicationId)
        assertEquals(policy.entryId, proposal.entityId)
        assertContentEquals(canonical, proposal.copyCanonicalContent())
        val copied = proposal.copyCanonicalContent()
        copied.fill(0)
        assertContentEquals(canonical, proposal.copyCanonicalContent())
        // Conservation of requested DATA is not a native signing output or an original commit.
    }

    @Test
    fun concreteChannelTierAndNonBoundaryDateRowsSelectFrozenSources() {
        val contexts = frozen.fixture("F-CONTEXTS")
        val supported = setOf("optional channel prices disabled", "enabled channel rules",
            "tier plus standard", "dated plus standard")
        val queries = contexts["context_queries"]!!.jsonArray.map { it.jsonObject }
            .filter { it.text("phase") in supported }
        assertEquals(11, queries.size)
        val defaultAt = UtcInstant.parseCanonical("2026-11-01T14:00:00Z")
        for (query in queries) {
            val label = query.text("id")
            val channel = query.text("channel").let { if (it == "STANDARD") "COUNTER" else it }
            val quantity = Quantity.parse(frozen.unitId, query.text("quantity"), 0)
            val at = query["instant_utc"]?.jsonPrimitive?.content?.let { UtcInstant.parseCanonical(it) } ?: defaultAt
            val prices = frozen.contextPrices(query.text("phase"))
            for (order in listOf(prices, prices.reversed())) {
                val selected = selectedRetailPrice(order, channel, quantity, at)
                assertEquals(frozen.ruleId(query.text("current_source")), selected.ruleId, label)
                assertEquals(Revision(query.text("context_revision").toLong()), selected.revision, label)
                assertEquals(UnitPrice.parse(Currency.EGP, query.text("unit")), selected.unitPrice, label)
                val quote = frozen.calculate(label, selected.unitPrice, quantity, selected.revision)
                assertEquals(frozen.expectedMoney(query.text("payable")), quote.payable, label)
                assertEquals(selected.unitPrice, quote.lines.single().unitPrice, label)
                assertEquals(selected.revision, quote.lines.single().priceRevision, label)
            }
        }
    }

    @Test
    fun exactUnitPricesAndDecodedWeightPostTheFrozenAmounts() {
        val numeric = frozen.fixture("F-NUMERIC")
        for ((index, row) in numeric["supported"]!!.jsonArray.map { it.jsonObject }.withIndex()) {
            val unitPrice = UnitPrice.parse(Currency.EGP, row.text("unit_price"))
            val quantity = Quantity.parse(frozen.unitId, row.text("quantity"), 0)
            val quote = frozen.calculate("exact/$index", unitPrice, quantity)
            assertEquals(row.text("posted_minor").toLong(), quote.payable.minor)
            assertEquals(frozen.expectedMoney(row.text("posted_payable")), quote.payable)
            assertEquals(unitPrice, quote.lines.single().unitPrice)
            assertEquals(unitPrice.toFraction() * quantity.toFraction(), quote.exactBeforeDiscountPayable)
        }
        val weight = frozen.fixture("F-WEIGHT-COST")
        val quantity = Quantity.parse(frozen.id("BASE-KG"), weight.text("decoded_quantity_kg"), 6)
        val oldRevision = Revision(frozen.fixture("F-BASE")["standard"]!!.jsonObject.text("company_revision").toLong())
        val newRevision = Revision(frozen.fixture("F-REVISION").text("new_company_revision").toLong())
        for ((unitKey, totalKey, revision) in listOf(Triple("old_price_per_kg", "old_payable", oldRevision),
            Triple("new_price_per_kg", "new_payable", newRevision))) {
            val unitPrice = UnitPrice.parse(Currency.EGP, weight.text(unitKey))
            val quote = frozen.calculate(unitKey, unitPrice, quantity, revision)
            assertEquals(frozen.expectedMoney(weight.text(totalKey)), quote.payable)
            assertEquals(quantity, quote.lines.single().quantity)
            assertEquals(unitPrice, quote.lines.single().unitPrice)
            assertEquals(revision, quote.lines.single().priceRevision)
        }
    }

    @Test
    fun unsupportedPrecisionNonfiniteInputsAndPostedOverflowAreRefused() {
        val numeric = frozen.fixture("F-NUMERIC")
        for (value in numeric["unsupported_unit_price"]!!.jsonArray.map { it.jsonPrimitive.content }) {
            assertFailsWith<IllegalArgumentException>(value) { UnitPrice.parse(Currency.EGP, value) }
        }
        assertFailsWith<IllegalArgumentException> {
            Quantity.parse(frozen.id("BASE-KG"), numeric.text("unsupported_quantity"), 6)
        }
        val overflow = numeric["overflow"]!!.jsonObject
        val unitPrice = UnitPrice.parse(Currency.EGP, overflow.text("unit_price"))
        val quantity = Quantity.parse(frozen.unitId, overflow.text("quantity"), 0)
        val failure = assertFailsWith<PricingRejectedException> { frozen.calculate("overflow", unitPrice, quantity) }
        assertEquals(PricingFailure.ARITHMETIC_OR_POSTING_LIMIT, failure.failure)
        // No mutator is invoked; durable no-write behavior is a separate integration boundary.
    }

    @Test
    fun mixedInclusiveAndExclusiveTaxKeepFrozenNetTaxAndPayable() {
        val fixture = frozen.fixture("F-TAX")
        val tax = TaxSnapshot(frozen.id("ILLUSTRATIVE-TAX-14"), Revision(5), "ILLUSTRATIVE-14",
            PercentageRate.parse(fixture.text("rate_percent")))
        val rows = fixture["lines"]!!.jsonArray.map { it.jsonObject }
        val lines = rows.map { row -> ResolvedPriceLine(frozen.id(row.text("id")), frozen.id("portion/${row.text("id")}"),
            Revision(5), Quantity.parse(frozen.unitId, row.text("quantity"), 0),
            UnitPrice.parse(Currency.EGP, row.text("unit_price")),
            if (row["tax_included"]!!.jsonPrimitive.boolean) TaxInclusion.INCLUSIVE else TaxInclusion.EXCLUSIVE, tax) }
        val quote = OperationalPricingEngine.calculate(PricingRequest(frozen.id("snapshot/mixed-tax"),
            Revision(1), Currency.EGP, lines))
        assertEquals(frozen.expectedMoney(fixture.text("total_net")).minor,
            quote.components.filter { it.kind == PriceComponentKind.ITEM_NET }.sumOf { it.posted.minor })
        assertEquals(frozen.expectedMoney(fixture.text("total_tax")).minor,
            quote.components.filter { it.kind == PriceComponentKind.ITEM_TAX }.sumOf { it.posted.minor })
        assertEquals(frozen.expectedMoney(fixture.text("total_payable")), quote.payable)
        assertTrue(quote.charges.isEmpty())
        for ((index, row) in rows.withIndex()) {
            val lineId = lines[index].lineId
            assertEquals(lines[index].inclusion, quote.lines.single { it.lineId == lineId }.inclusion)
            assertEquals(frozen.expectedMoney(row.text("gross")), quote.lines.single { it.lineId == lineId }.payable)
            assertEquals(frozen.expectedMoney(row.text("net")).minor,
                quote.components.filter { it.lineId == lineId && it.kind == PriceComponentKind.ITEM_NET }.sumOf { it.posted.minor })
            assertEquals(frozen.expectedMoney(row.text("tax")).minor,
                quote.components.filter { it.lineId == lineId && it.kind == PriceComponentKind.ITEM_TAX }.sumOf { it.posted.minor })
        }
    }

    @Test
    fun fourConcreteManualDiscountRowsApplyBothFrozenCeilings() {
        val fixture = frozen.fixture("F-LIMITS")
        val supported = setOf("BOTH-AT-BOUND", "BOTH-OVER", "AMOUNT-ONLY-OVER", "PERCENT-ONLY-OVER")
        val rows = fixture["rows"]!!.jsonArray.map { it.jsonObject }.filter { it.text("id") in supported }
        assertEquals(4, rows.size)
        for (row in rows) {
            val label = row.text("id")
            val limits = DiscountLimits(frozen.expectedMoney(row.text("amount_ceiling")),
                PercentageRate.parse(row.text("percentage_ceiling")))
            val portionId = frozen.id("portion/$label")
            val request = PricingRequest(frozen.id("snapshot/$label"), Revision(1), Currency.EGP,
                listOf(ResolvedPriceLine(frozen.id("line/$label"), portionId, Revision(5),
                    Quantity.parse(frozen.unitId, fixture.text("quantity"), 0),
                    UnitPrice.parse(Currency.EGP, fixture.text("base_payable")), TaxInclusion.EXCLUSIVE, frozen.taxFree, limits)),
                adjustments = listOf(PriceAdjustment(frozen.id("adjustment/$label"), Revision(1),
                    AdjustmentStage.ORDER_OR_MANUAL_DISCOUNT, DiscountValue.FixedPayable(frozen.expectedMoney(row.text("discount"))),
                    listOf(EligibleDiscountPortion(portionId)), limits)), discountLimits = limits)
            if (row.text("expected") == "ALLOW") {
                val quote = OperationalPricingEngine.calculate(request)
                assertEquals(frozen.expectedMoney(row.text("payable_if_allowed")), quote.payable, label)
                assertEquals(frozen.expectedMoney(row.text("discount")), quote.advertisedDiscount, label)
                assertEquals(PercentageRate.parse(row.text("discount_percentage")).toFraction(),
                    ExactRational.ratio(quote.advertisedDiscount.minor, quote.beforeDiscountPayable.minor), label)
                assertEquals(1, quote.discounts.size, label)
            } else {
                val failure = assertFailsWith<PricingRejectedException>(label) { OperationalPricingEngine.calculate(request) }
                assertEquals(PricingFailure.DISCOUNT_LIMIT_EXCEEDED, failure.failure, label)
            }
        }
        // This calculator does not establish employee/approval authority or branch-deviation semantics.
    }

    @Test
    fun publicSequenceSerializationPreservesExactOrderAndSignedBoundaries() {
        val fixture = frozen.fixture("F-HISTORY-BOUNDS")
        val strings = fixture["exact_sequences"]!!.jsonArray.map { it.jsonPrimitive.content }
        val sequences = strings.map { value ->
            val sequence = JournalSequence(value.toLong())
            val encoded = frozen.json.encodeToString(JournalSequence.serializer(), sequence)
            assertEquals("\"$value\"", encoded)
            frozen.json.decodeFromString(JournalSequence.serializer(), encoded).also { assertEquals(sequence, it) }
        }
        assertTrue(sequences[0] < sequences[1])
        assertEquals(fixture.text("first_sequence_difference").toLong(), sequences[1].value - sequences[0].value)
        assertEquals(sequences[1], sequences[0].next())
        assertFailsWith<ArithmeticException> { sequences.last().next() }
        assertFailsWith<SerializationException> {
            frozen.json.decodeFromString(JournalSequence.serializer(), "\"${fixture.text("unsupported_next_sequence")}\"")
        }
    }
}
