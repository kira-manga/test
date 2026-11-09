package com.ukkera.pos.catalog.store.independent.catalogsuccessorv1

import com.ukkera.pos.catalog.CatalogQuantitySnapshot
import com.ukkera.pos.catalog.store.LocalPublishedSkuV1
import com.ukkera.pos.catalog.store.selectedRetailPrice
import com.ukkera.pos.pricing.OperationalPricingEngine
import com.ukkera.pos.pricing.PriceComponentKind
import com.ukkera.pos.pricing.PricingRequest
import com.ukkera.pos.pricing.PricingSnapshot
import com.ukkera.pos.pricing.ResolvedPriceLine
import com.ukkera.pos.pricing.TaxInclusion
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.PercentageRate
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitPrice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Selected immutable values only, not an authoritative quote, collection lock or durable sale. */
class CatalogSuccessorV1SnapshotTest {
    private val fixture = CatalogSuccessorV1Fixture

    @Test
    fun oldAndNewSelectedValuesStayPairedAfterLaterCalculation() {
        // CSR-001/002/003/004 supporting slice: exact old/new conversion and price
        // captures, with all quantities/totals taken from the frozen oracle.
        val old = calculate(fixture.p1, fixture.saleDocument1)
        assertSale(old, 12, "12/1", Revision(1), "3.25", 3900)
        assertEquals(Quantity(fixture.each, 24, 0), fixture.p1.conversions.single()
            .domain(fixture.product).convertExact(Quantity(fixture.carton, 2, 0), 0))

        val successor = calculate(fixture.p2, fixture.saleDocument2)
        assertSale(successor, 10, "10/1", Revision(2), "4.10", 4100)
        assertEquals(Quantity(fixture.each, 20, 0), fixture.p2.conversions.single()
            .domain(fixture.product).convertExact(Quantity(fixture.carton, 2, 0), 0))

        // Creating/evaluating later values must not reinterpret this old result.
        assertSale(old, 12, "12/1", Revision(1), "3.25", 3900)
        for (captured in listOf(old, successor)) {
            assertNotEquals(Money(Currency.EGP, 4920), captured.price.payable)
            assertNotEquals(Money(Currency.EGP, 3250), captured.price.payable)
        }
    }

    private fun calculate(publication: LocalPublishedSkuV1, document: DomainId): CapturedValues {
        val entered = Quantity(fixture.carton, 1, 0)
        val conversion = publication.conversions.single().domain(publication.productId)
        val quantity = CatalogQuantitySnapshot(entered,
            conversion.convertExact(entered, publication.quantityScale), conversion)
        val price = selectedRetailPrice(publication.prices, "COUNTER", quantity.baseQuantity, fixture.selectionAt)
        val priced = OperationalPricingEngine.calculate(PricingRequest(document, Revision(1), Currency.EGP,
            listOf(ResolvedPriceLine(document, document, price.revision, quantity.baseQuantity,
                price.unitPrice, price.inclusion, price.tax))))
        return CapturedValues(quantity, priced)
    }

    private fun assertSale(captured: CapturedValues, base: Long, rate: String, conversionRevision: Revision,
        amount: String, payable: Long) {
        val conversion = checkNotNull(captured.quantity.conversionSnapshot)
        assertEquals(Quantity(fixture.carton, 1, 0), captured.quantity.quantity)
        assertEquals(Quantity(fixture.each, base, 0), captured.quantity.baseQuantity)
        assertEquals(fixture.conversion, conversion.id)
        assertEquals(conversionRevision, conversion.revision)
        assertEquals(fixture.product, conversion.productId)
        assertEquals(fixture.carton, conversion.sourceUnitId)
        assertEquals(fixture.each, conversion.targetUnitId)
        assertEquals(rate, conversion.rate.canonicalString())
        val line = captured.price.lines.single()
        assertEquals(captured.quantity.baseQuantity, line.quantity)
        assertEquals(UnitPrice.parse(Currency.EGP, amount), line.unitPrice)
        assertEquals(TaxInclusion.EXCLUSIVE, line.inclusion)
        assertEquals(PercentageRate.parse("0"), line.tax.rate)
        assertEquals(Money(Currency.EGP, payable), line.payable)
        assertEquals(Money(Currency.EGP, payable), captured.price.payable)
        assertEquals(Money(Currency.EGP, payable), captured.price.beforeDiscountPayable)
        assertEquals(Money(Currency.EGP, payable), captured.price.components
            .filter { it.kind == PriceComponentKind.ITEM_NET }
            .fold(Money(Currency.EGP, 0)) { total, component -> total + component.posted })
        assertEquals(Money(Currency.EGP, 0), captured.price.components
            .filter { it.kind == PriceComponentKind.ITEM_TAX }
            .fold(Money(Currency.EGP, 0)) { total, component -> total + component.posted })
        assertEquals(Money(Currency.EGP, 0), captured.price.advertisedDiscount)
        assertTrue(captured.price.charges.isEmpty())
        assertTrue(captured.price.discounts.isEmpty())
    }

    private data class CapturedValues(val quantity: CatalogQuantitySnapshot, val price: PricingSnapshot)
}
