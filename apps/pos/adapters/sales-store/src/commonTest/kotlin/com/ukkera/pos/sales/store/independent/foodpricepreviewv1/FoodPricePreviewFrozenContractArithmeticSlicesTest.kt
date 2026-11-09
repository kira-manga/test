package com.ukkera.pos.sales.store.independent.foodpricepreviewv1

import com.ukkera.pos.pricing.OperationalPricingEngine
import com.ukkera.pos.pricing.PriceComponentKind
import com.ukkera.pos.pricing.PricingRequest
import com.ukkera.pos.pricing.PricingSnapshot
import com.ukkera.pos.pricing.ResolvedPriceLine
import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.pricing.TaxInclusion
import com.ukkera.pos.pricing.TaxSnapshot
import com.ukkera.pos.sales.store.LocalFoodCodecV1
import com.ukkera.pos.sales.store.LocalFoodPortionSelectionV1
import com.ukkera.pos.sales.store.LocalFoodRequestV1
import com.ukkera.pos.sales.store.LocalFoodSelectionGroups
import com.ukkera.pos.sales.store.LocalFoodSelectionPreviewCodecV1
import com.ukkera.pos.sales.store.LocalFoodSelectionPreviewResultV1
import com.ukkera.pos.sales.store.LocalFoodServiceV1
import com.ukkera.pos.sales.store.LocalSalesActivity
import com.ukkera.pos.sales.store.LocalSalesChannel
import com.ukkera.pos.sales.store.localSalesEncode
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.ExactRational
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.PercentageRate
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitPrice
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Frozen food-pre-save-preview-v1 contract/grouping/arithmetic/codec slices only.
 * Read-handler prepare/project, installed source selection and Master authority,
 * native durable zero cuts, same-device/LAN and whole cases remain unbound.
 * Selected major-unit prices are supplied inputs; modifier/rule activation is not proved.
 * LINE-TAX-HALF-UP stays UNBOUND; no alternate fiscal boundary is asserted.
 */
class FoodPricePreviewFrozenContractArithmeticSlicesTest {
    @Test
    fun fqp01And02CanonicalPreviewCreateAddBytesRetainOneOriginalQuantityTwoLineAndNotes() {
        listOf(false, true).forEach { current ->
            val portions = frozenSelections(2, current)
            val catalog = Revision(if (current) 6 else 5)
            val requests = listOf<LocalFoodRequestV1>(
                LocalFoodRequestV1.Create(ORDER, LocalSalesActivity.RESTAURANT,
                    LocalFoodServiceV1(LocalSalesChannel.DINE_IN, "T-02"), catalog, portions),
                LocalFoodRequestV1.Add(ORDER, Revision(7), catalog, portions),
            )
            requests.forEach { original ->
                val read = LocalFoodSelectionPreviewCodecV1.read(original)
                assertEquals(LocalFoodSelectionPreviewCodecV1.QUERY, read.query)
                assertEquals(LocalFoodCodecV1.encode(original), read.payload)
                val decoded = LocalFoodCodecV1.decode(read.payload)
                val saved = when (decoded) {
                    is LocalFoodRequestV1.Create -> {
                        assertEquals(ORDER, decoded.orderId)
                        assertEquals(LocalSalesChannel.DINE_IN, decoded.service.channel)
                        assertEquals("T-02", decoded.service.reference)
                        assertEquals(catalog, decoded.expectedCatalogRevision)
                        decoded.portions
                    }
                    is LocalFoodRequestV1.Add -> {
                        assertEquals(ORDER, decoded.orderId)
                        assertEquals(Revision(7), decoded.expectedOrderRevision)
                        assertEquals(catalog, decoded.expectedCatalogRevision)
                        decoded.portions
                    }
                    else -> error("Preview is not a selection read")
                }
                assertFrozenSelections(saved, 2, current)
                assertEquals(read.payload, LocalFoodSelectionPreviewCodecV1.encode(decoded))
            }
        }
    }

    @Test
    fun fqp01And02OriginalAndCompatibleSelectedUnitArithmeticKeepsExactPreviewCodecAmounts() {
        listOf(
            FrozenPrice(false, "110", TaxInclusion.INCLUSIVE, "0", 11000, 22000, 22000, 0),
            FrozenPrice(true, "140", TaxInclusion.INCLUSIVE, "0", 14000, 28000, 28000, 0),
        ).forEach { expected ->
            val selections = frozenSelections(2, expected.current)
            val request = LocalFoodRequestV1.Add(ORDER, Revision(7), Revision(if (expected.current) 6 else 5), selections)
            val quote = frozenQuote(selections, expected)
            assertFrozenQuote(quote, expected, 2)
            val digest = sha256(LocalFoodSelectionPreviewCodecV1.encode(request).copyBytes())
            val bytes = localSalesEncode(FoodPricePreviewFrozenReplyWire.Available(digest,
                StoredPricingSnapshotCodec.encode(quote)), FoodPricePreviewFrozenReplyWire.serializer())
            val reply = assertIs<LocalFoodSelectionPreviewResultV1.Available>(LocalFoodSelectionPreviewCodecV1.result(bytes))
            assertEquals(digest, reply.requestDigest)
            assertFrozenQuote(reply.quote, expected, 2)
        }
    }

    @Test
    fun fqp03FrozenTierSelectedAmountUsesServingQuantityTwoWithinOneHomogeneousLine() {
        listOf(
            1 to FrozenPrice(false, "110", TaxInclusion.INCLUSIVE, "0", 11000, 11000, 11000, 0),
            2 to FrozenPrice(false, "100", TaxInclusion.INCLUSIVE, "0", 10000, 20000, 20000, 0),
        ).forEach { (quantity, expected) ->
            val selections = frozenSelections(quantity)
            assertFrozenSelections(selections, quantity, current = false)
            assertFrozenQuote(frozenQuote(selections, expected), expected, quantity)
        }
    }

    @Test
    fun fqp04DeclaredInclusiveAndExclusiveFourteenPercentArithmeticKeepsExactNetTaxPayable() {
        listOf(
            FrozenPrice(false, "114", TaxInclusion.INCLUSIVE, "14", 11400, 22800, 20000, 2800),
            FrozenPrice(false, "100", TaxInclusion.EXCLUSIVE, "14", 10000, 22800, 20000, 2800),
        ).forEach { expected ->
            val quote = frozenQuote(frozenSelections(2), expected)
            assertFrozenQuote(quote, expected, 2)
            val decoded = StoredPricingSnapshotCodec.decode(StoredPricingSnapshotCodec.encode(quote))
            assertFrozenQuote(decoded, expected, 2)
        }
    }
}

private val ORDER = DomainId("f0000000-0000-4000-8000-000000000001")
private val LINE = DomainId("f3000000-0000-4000-8000-000000000001")
// Fixed native-equivalent serving and UOM bindings, not task or row-count oracles.
private val SECOND = DomainId("f3000000-0000-4000-8000-000000000002")
private val UNIT = DomainId("f6000000-0000-4000-8000-000000000001")
private val PRODUCT = DomainId("f1000000-0000-4000-8000-000000000001")
private val VARIANT = DomainId("f1000000-0000-4000-8000-000000000002")
private val CHEESE = DomainId("f1000000-0000-4000-8000-000000000003")
private val TAX = DomainId("f7000000-0000-4000-8000-000000000001")
private const val ITEM_NOTE = "no salt / بدون ملح"
private const val SEAT_NOTE = "Seat 02 / المقعد 02"

private data class FrozenPrice(val current: Boolean, val selectedMajor: String,
    val inclusion: TaxInclusion, val ratePercent: String, val unitMinor: Long,
    val payableMinor: Long, val netMinor: Long, val taxMinor: Long)

private fun frozenSelections(quantity: Int, current: Boolean = false): List<LocalFoodPortionSelectionV1> {
    require(quantity in 1..2)
    return listOf(LINE, SECOND).take(quantity).map { id ->
        LocalFoodPortionSelectionV1(id, PRODUCT, VARIANT, Revision(if (current) 2 else 1), CHEESE,
            listOf(CHEESE), Quantity(UNIT, 1, 0), ITEM_NOTE, if (quantity == 2) LINE else null, SEAT_NOTE)
    }
}

private fun assertFrozenSelections(selections: List<LocalFoodPortionSelectionV1>, quantity: Int, current: Boolean) {
    val group = LocalFoodSelectionGroups.group(selections).single()
    assertEquals(LINE, group.lineId)
    assertEquals(Quantity(UNIT, quantity.toLong(), 0), group.totalQuantity)
    assertTrue(group.portions.all {
        it.commercialLineId == LINE && it.itemId == PRODUCT && it.recipeId == VARIANT &&
            it.expectedRecipeRevision == Revision(if (current) 2 else 1) && it.choiceId == CHEESE &&
            it.modifierIds == listOf(CHEESE) && it.itemNote == ITEM_NOTE && it.seatNote == SEAT_NOTE
    })
}

private fun frozenQuote(selections: List<LocalFoodPortionSelectionV1>, input: FrozenPrice): PricingSnapshot {
    val tax = TaxSnapshot(TAX, Revision(1), "ILLUSTRATIVE", PercentageRate.parse(input.ratePercent))
    val lines = selections.map { ResolvedPriceLine(it.commercialLineId, it.portionId,
        Revision(if (input.current) 6 else 5), it.quantity, UnitPrice.parse(Currency.EGP, input.selectedMajor), input.inclusion, tax) }
    return OperationalPricingEngine.calculate(PricingRequest(ORDER, Revision(0), Currency.EGP, lines))
}

private fun assertFrozenQuote(quote: PricingSnapshot, expected: FrozenPrice, quantity: Int) {
    assertEquals(ORDER, quote.snapshotId)
    assertEquals(Revision(0), quote.revision)
    assertEquals(listOf(LINE), quote.lines.map { it.lineId }.distinct())
    assertEquals(Quantity(UNIT, quantity.toLong(), 0), quote.lines.fold(Quantity(UNIT, 0, 0)) { sum, line -> sum + line.quantity })
    assertTrue(quote.lines.all {
        it.unitPrice.toFraction().toMoney(Currency.EGP) == Money(Currency.EGP, expected.unitMinor) && it.inclusion == expected.inclusion
    })
    quote.lines.forEach {
        assertEquals(ExactRational.ratio(expected.unitMinor, 100L), it.unitPrice.toFraction())
    }
    assertEquals(Money(Currency.EGP, expected.payableMinor), quote.payable)
    assertEquals(Money(Currency.EGP, expected.payableMinor), quote.beforeDiscountPayable)
    assertEquals(Money(Currency.EGP, 0), quote.advertisedDiscount)
    assertTrue(quote.charges.isEmpty() && quote.discounts.isEmpty())
    fun posted(kind: PriceComponentKind) = quote.components.filter { it.kind == kind }
        .fold(Money(Currency.EGP, 0)) { sum, component -> sum + component.posted }
    assertEquals(Money(Currency.EGP, expected.netMinor), posted(PriceComponentKind.ITEM_NET))
    assertEquals(Money(Currency.EGP, expected.taxMinor), posted(PriceComponentKind.ITEM_TAX))
    assertEquals(Money(Currency.EGP, 0), posted(PriceComponentKind.CHARGE_NET))
    assertEquals(Money(Currency.EGP, 0), posted(PriceComponentKind.CHARGE_TAX))
}

/** Only a codec binding for the actual private AVAILABLE wire; no Master reply is fabricated. */
@Serializable
private sealed interface FoodPricePreviewFrozenReplyWire {
    @Serializable @SerialName("AVAILABLE")
    data class Available(val requestDigest: PayloadDigest, val pricing: String) : FoodPricePreviewFrozenReplyWire
}
