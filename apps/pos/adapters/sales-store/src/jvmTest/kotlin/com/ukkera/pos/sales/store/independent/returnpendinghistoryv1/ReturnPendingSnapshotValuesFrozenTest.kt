package com.ukkera.pos.sales.store.independent.returnpendinghistoryv1

import com.ukkera.pos.pricing.AdjustmentStage
import com.ukkera.pos.pricing.DiscountLimits
import com.ukkera.pos.pricing.DiscountValue
import com.ukkera.pos.pricing.EligibleDiscountPortion
import com.ukkera.pos.pricing.OperationalPricingEngine
import com.ukkera.pos.pricing.PriceAdjustment
import com.ukkera.pos.pricing.PriceComponentKind
import com.ukkera.pos.pricing.PricingRequest
import com.ukkera.pos.pricing.PricingSnapshot
import com.ukkera.pos.pricing.ResolvedPriceLine
import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.pricing.TaxInclusion
import com.ukkera.pos.pricing.TaxSnapshot
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.PercentageRate
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitPrice
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * RPH-V04 numeric L1 slices only, using the unchanged return-pending-history-v1 freeze.
 * The calculator and stored pricing codec preserve numeric values; the original cursor
 * derives refundable components. None accepts a current policy/publication input.
 * Comparing the supplied current countervalues does not establish overwrite resistance,
 * original provenance, bilingual promise retention, authorization or recovery.
 * RPH-V03's combined claim/evidence/policy/pending assertions remain unbound here.
 */
class ReturnPendingSnapshotValuesFrozenTest {
    @Test
    fun rphV04_originalNumericValues_productionPricingAndStoredCodecSlice() {
        val fixtures = frozenFixtures()
        val expected = fixtures.objectValue("PURE_SNAPSHOT_VALUES")
        val original = fixtures.objectValue(expected.text("originalValues"))
        val current = fixtures.objectValue(expected.text("currentValues"))

        // The independently frozen totals are checked against actual arithmetic first;
        // equality of an object's own encoded/decoded values is not the amount oracle.
        val calculated = originalPricing(original)
        assertFrozenNumericValues(calculated, expected)

        val restored = StoredPricingSnapshotCodec.decode(StoredPricingSnapshotCodec.encode(calculated))
        assertFrozenNumericValues(restored, expected)

        val line = restored.lines.single()
        val discount = assertIs<DiscountValue.Percentage>(restored.discounts.single().value)
        assertNotEquals(price(current.number("unitListPriceMinor")), line.unitPrice)
        assertNotEquals(rate(current.number("taxRateBasisPoints")), line.tax.rate)
        assertNotEquals(rate(current.number("discountBasisPoints")), discount.rate)
        // These are frozen scalar contrasts, not a current-policy mutation/recovery run.
    }

    @Test
    fun rphV04_originalNumericValues_storedReturnCursorComponentSlice() {
        val fixtures = frozenFixtures()
        val expected = fixtures.objectValue("PURE_SNAPSHOT_VALUES")
        val original = fixtures.objectValue(expected.text("originalValues"))
        val restored = StoredPricingSnapshotCodec.decode(
            StoredPricingSnapshotCodec.encode(originalPricing(original)))

        val cursor = restored.openReturnCursor(id("PORTION-RPH-001"))

        assertEquals(expected.number("expectedPreservedPayableMinor"), cursor.remainingPayable.minor)
        assertEquals(expected.number("expectedPreservedNetMinor"),
            cursor.remainingComponents.filter { it.kind == PriceComponentKind.ITEM_NET }.sumOf { it.amount.minor })
        assertEquals(expected.number("expectedPreservedTaxMinor"),
            cursor.remainingComponents.filter { it.kind == PriceComponentKind.ITEM_TAX }.sumOf { it.amount.minor })
        assertEquals(Quantity(id("EACH"), original.number("quantityBase"), 0), cursor.originalQuantity)
        // No consumption, accepted-return history or physical/financial effect is executed.
    }

    private fun originalPricing(original: JsonObject): PricingSnapshot {
        val portionId = id("PORTION-RPH-001")
        val discountRate = rate(original.number("discountBasisPoints"))
        // These are arithmetic caps from the supplied original allocation, not a grant.
        val limits = DiscountLimits(Money(Currency.EGP, original.number("discountAllocationMinor")), discountRate)
        val line = ResolvedPriceLine(id("CLOTHING-RPH-001"), portionId, Revision(0),
            Quantity(id("EACH"), original.number("quantityBase"), 0),
            price(original.number("unitListPriceMinor")),
            if (original.getValue("taxInclusive").jsonPrimitive.boolean) TaxInclusion.INCLUSIVE else TaxInclusion.EXCLUSIVE,
            TaxSnapshot(id("SYNTHETIC-TAX-RPH"), Revision(0), "SYNTHETIC", rate(original.number("taxRateBasisPoints"))),
            limits)
        val adjustment = PriceAdjustment(id("SYNTHETIC-DISCOUNT-RPH"), Revision(0),
            AdjustmentStage.ORDER_OR_MANUAL_DISCOUNT, DiscountValue.Percentage(discountRate),
            listOf(EligibleDiscountPortion(portionId)), limits)
        // Factory-only metadata is inert; these revisions are not production cut aliases.
        return OperationalPricingEngine.calculate(PricingRequest(id("QUOTE-RPH-001"), Revision(0), Currency.EGP,
            listOf(line), adjustments = listOf(adjustment), discountLimits = limits))
    }

    private fun assertFrozenNumericValues(snapshot: PricingSnapshot, expected: JsonObject) {
        val line = snapshot.lines.single()
        val discount = assertIs<DiscountValue.Percentage>(snapshot.discounts.single().value)
        assertEquals(price(expected.number("expectedPreservedUnitPriceMinor")), line.unitPrice)
        assertEquals(rate(expected.number("expectedPreservedTaxRateBasisPoints")), line.tax.rate)
        assertEquals(rate(expected.number("expectedPreservedDiscountBasisPoints")), discount.rate)
        assertEquals(expected.number("expectedPreservedPayableMinor"), snapshot.payable.minor)
        assertEquals(expected.number("expectedPreservedNetMinor"),
            snapshot.components.filter { it.kind == PriceComponentKind.ITEM_NET }.sumOf { it.posted.minor })
        assertEquals(expected.number("expectedPreservedTaxMinor"),
            snapshot.components.filter { it.kind == PriceComponentKind.ITEM_TAX }.sumOf { it.posted.minor })
    }

    private fun frozenFixtures(): JsonObject {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.firstOrNull {
            Files.isRegularFile(it.resolve("qa/pos/freezes/return-pending-history-v1/fixtures.json"))
        } ?: error("Frozen return-pending-history-v1 fixtures are unavailable")
        return Json.parseToJsonElement(Files.readString(
            root.resolve("qa/pos/freezes/return-pending-history-v1/fixtures.json"))).jsonObject
    }

    private fun JsonObject.objectValue(name: String): JsonObject = getValue(name).jsonObject
    private fun JsonObject.number(name: String): Long = getValue(name).jsonPrimitive.long
    private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content
    private fun price(minor: Long): UnitPrice = UnitPrice.parse(Currency.EGP, BigDecimal.valueOf(minor, 2).toPlainString())
    private fun rate(basisPoints: Long): PercentageRate = PercentageRate.parse(BigDecimal.valueOf(basisPoints, 2).toPlainString())
    private fun id(alias: String): DomainId = DomainId(UUID.nameUUIDFromBytes(
        "return-pending-history-v1:$alias".encodeToByteArray()).toString())
}
