package com.ukkera.pos.inventory.independent.supplierinvoicecapturev2

import com.ukkera.pos.inventory.*
import com.ukkera.pos.types.*
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Supporting L0/L1 bindings of frozen SIDC/SIRD expectations, including the
 * separately frozen context-identity clarification v1.1. These assertions do not
 * exercise a Master, permission, persisted read, receipt/financial effect or retry.
 */
class SupplierInvoiceCaptureV2FrozenExpectationTest {
    private val fixture = SupplierInvoiceCaptureV2FrozenFixture
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    @Test
    fun sidc01_otherUnitKeepsActualTermsSeparateFromOriginalOrder() {
        val order = fixture.order()
        val invoice = fixture.invoice("000073-A/فاتورة", listOf(fixture.documentaryLine(fixture.otherUnit())), fixture.orderLink)
        assertOtherUnit(invoice)
        assertEquals(fixture.orderLink, invoice.internalOrderLink)
        assertUnallocated(invoice, order)
        assertOriginalOrder(order)
    }

    @Test
    fun sidc01_changedConversionKeepsTenUnitsAndActualClaimAmount() {
        val order = fixture.order()
        val invoice = fixture.invoice("000073-B/فاتورة", listOf(fixture.documentaryLine(fixture.changedConversion())), fixture.orderLink)
        assertChangedConversion(invoice)
        assertUnallocated(invoice, order)
        assertOriginalOrder(order)
    }

    @Test
    fun sidc02_mixedGoodsWithOrderContextDoNotAcquireAnAllocation() {
        val order = fixture.order()
        val invoice = mixedInvoice(fixture.orderLink)
        assertMixed(invoice)
        assertEquals(fixture.orderLink, invoice.internalOrderLink)
        assertUnallocated(invoice, order)
        assertOriginalOrder(order)
    }

    @Test
    fun sidc02_mixedGoodsWithoutOrderContextKeepBothQuantitiesAndTotal() {
        val invoice = mixedInvoice()
        assertMixed(invoice)
        assertNull(invoice.internalOrderLink)
        assertNull(invoice.supplierOrderReference)
        assertUnallocated(invoice, null)
    }

    @Test
    fun clarification11_unresolvedSupplierOrderTextPreservesClaimWithoutLink() {
        // Clarification v1.1:12-28 explicitly gives this unresolved literal example.
        val invoice = mixedInvoice(literalOrderReference = "SUP-PO-00999")
        assertEquals("SUP-PO-00999", invoice.supplierOrderReference)
        assertNull(invoice.internalOrderLink)
        assertMixed(invoice)
        assertUnallocated(invoice, null)
    }

    @Test
    fun clarification11_uuidLookingLiteralsDoNotBecomeSelectedIdentities() {
        // Clarification v1.1:12-21,34-38: meaning, not string syntax, asserts a link.
        val invoice = literalIdentityInvoice()
        assertEquals(fixture.orderId.value, invoice.supplierOrderReference)
        assertEquals(fixture.allocationId.value, invoice.lines.single().supplierAllocationReference)
        assertNull(invoice.internalOrderLink)
        assertUnallocated(invoice, fixture.order())
        assertGoods(invoice.lines.single().goods, fixture.skuA, fixture.productA, fixture.carton,
            fixture.each, 12, "2", 0, "120", 24, 24000, "PO-000042")
    }

    @Test
    fun sird01_referencedDifferentItemUnitKeepsContextAndActualTerms() {
        val order = fixture.order()
        val invoice = referencedInvoice(fixture.referencedDifferentItemUnit(), "000084-REF-A/فاتورة")
        assertReferencedContext(invoice)
        assertEquals("000084-REF-A/فاتورة", invoice.reference)
        assertGoods(invoice.lines.single().goods, fixture.skuB, fixture.productB, fixture.tray,
            fixture.each, 5, "4", 0, "57.35", 20, 22940, "000009-B")
        assertEquals(Money(Currency.EGP, 22940), invoice.total())
        assertMaterialVariance(order, invoice)
        assertOriginalOrder(order)
    }

    @Test
    fun sird01_referencedChangedConversionDoesNotInheritTwelveUnitRatio() {
        val order = fixture.order()
        val invoice = referencedInvoice(fixture.referencedDifferentConversion(), "000084-REF-B/فاتورة")
        assertReferencedContext(invoice)
        assertEquals("000084-REF-B/فاتورة", invoice.reference)
        assertGoods(invoice.lines.single().goods, fixture.skuA, fixture.productA, fixture.carton,
            fixture.each, 10, "3", 0, "91.10", 30, 27330, "000008-A")
        assertEquals(Money(Currency.EGP, 27330), invoice.total())
        assertMaterialVariance(order, invoice)
        assertOriginalOrder(order)
    }

    @Test
    fun sidc08_explicitConformingV2SelectionHasNoFalseGoodsVariance() {
        val order = fixture.order()
        val line = PurchaseInvoiceCaptureLineV2(fixture.lineB, fixture.originalGoods(),
            PurchaseInvoiceEntryProvenanceV2.SELECTED_ORDER_LINE, fixture.allocationId)
        val invoice = fixture.invoice("000073-D/فاتورة", listOf(line), fixture.orderLink)
        assertEquals(PurchaseInvoiceEntryProvenanceV2.SELECTED_ORDER_LINE, invoice.lines.single().provenance)
        assertEquals(fixture.allocationId, invoice.lines.single().internalAllocationId)
        assertEquals(Money(Currency.EGP, 24000), invoice.total())
        assertEquals(emptySet(), purchaseInvoiceCaptureDiscrepanciesV2(order, invoice))
        assertOriginalOrder(order)
    }

    @Test
    fun sidc08_originalV1ConformingDomainValuesRemainUsable() {
        // Frozen SIDC-08:196-213; this checks domain behavior, not old wire bytes.
        val order = fixture.order()
        val invoice = PurchaseInvoiceV1(fixture.invoiceId, order.id, fixture.supplier, "000073-D/فاتورة",
            BusinessDate.parseCanonical("2026-11-05"), BusinessDate.parseCanonical("2026-11-05"),
            fixture.sourceEvidenceId, listOf(PurchaseInvoiceLineV1(fixture.lineB, fixture.allocationId, fixture.originalGoods())), emptyList())
        assertEquals("000073-D/فاتورة", invoice.reference)
        assertEquals(Quantity(fixture.each, 24, 0), invoice.lines.single().goods.baseQuantity())
        assertEquals(Money(Currency.EGP, 24000), invoice.total())
        assertEquals(emptySet(), purchaseDiscrepancies(order, invoice))
    }

    @Test
    fun sidc03_zeroQuantityCannotConstructConvertedGoods() {
        assertFails { fixture.otherUnit().copy(quantity = Quantity.parse(fixture.tray, "0", 0)) }
    }

    @Test
    fun sidc03_sird02_zeroDenominatorsAreRejectedWithoutUsingOriginalRatio() {
        // Frozen SIDC-03 6/0 and SIRD-02 5/0; no fallback input is supplied.
        assertFails { UnitConversionRate.ratio(6, 0) }
        assertFails { UnitConversionRate.ratio(5, 0) }
    }

    @Test
    fun sidc03_excessKilogramPrecisionCannotBeRoundedIntoWholeGrams() {
        // Capture fixtures.json:190 declares at most three KG digits and whole G.
        assertFails { Quantity.parse(fixture.kilogram, "2.5001", 3) }
        assertFails { fixture.mixedC().copy(quantity = Quantity.parse(fixture.kilogram, "2.5001", 4)) }
    }

    @Test
    fun sidc01_serializationPreservesOtherUnitSupplierEvidence() {
        val invoice = fixture.invoice("000073-A/فاتورة", listOf(fixture.documentaryLine(fixture.otherUnit())), fixture.orderLink)
        val decoded = roundTrip(invoice)
        assertOtherUnit(decoded)
        assertUnallocated(decoded, fixture.order())
    }

    @Test
    fun sidc01_serializationPreservesChangedConversionSupplierEvidence() {
        val invoice = fixture.invoice("000073-B/فاتورة", listOf(fixture.documentaryLine(fixture.changedConversion())), fixture.orderLink)
        val decoded = roundTrip(invoice)
        assertChangedConversion(decoded)
        assertUnallocated(decoded, fixture.order())
    }

    @Test
    fun sidc02_serializationPreservesMixedNoOrderClaimAndAbsentAllocations() {
        val decoded = roundTrip(mixedInvoice())
        assertMixed(decoded)
        assertNull(decoded.internalOrderLink)
        assertNull(decoded.supplierOrderReference)
        assertUnallocated(decoded, null)
    }

    @Test
    fun sird01_serializationPreservesDocumentaryLinkAndDifferentGoods() {
        val decoded = roundTrip(referencedInvoice(fixture.referencedDifferentItemUnit(), "000084-REF-A/فاتورة"))
        assertReferencedContext(decoded)
        assertGoods(decoded.lines.single().goods, fixture.skuB, fixture.productB, fixture.tray,
            fixture.each, 5, "4", 0, "57.35", 20, 22940, "000009-B")
        assertMaterialVariance(fixture.order(), decoded)
    }

    @Test
    fun clarification11_serializationKeepsSupplierLiteralIdentitiesUnresolved() {
        val decoded = roundTrip(literalIdentityInvoice())
        assertEquals(fixture.orderId.value, decoded.supplierOrderReference)
        assertEquals(fixture.allocationId.value, decoded.lines.single().supplierAllocationReference)
        assertNull(decoded.internalOrderLink)
        assertUnallocated(decoded, fixture.order())
    }

    private fun mixedInvoice(context: PurchaseInvoiceOrderLinkV2? = null,
        literalOrderReference: String? = null): PurchaseInvoiceCaptureV2 = fixture.invoice("000073-C/فاتورة",
        listOf(fixture.documentaryLine(fixture.mixedB(), lineId = fixture.lineB),
            fixture.documentaryLine(fixture.mixedC(), lineId = fixture.lineC)), context, literalOrderReference)

    private fun literalIdentityInvoice(): PurchaseInvoiceCaptureV2 = fixture.invoice("000073-D/فاتورة",
        listOf(fixture.documentaryLine(fixture.originalGoods(), literalReference = fixture.allocationId.value)),
        literalOrderReference = fixture.orderId.value)

    private fun referencedInvoice(goods: PurchaseGoodsV1, reference: String): PurchaseInvoiceCaptureV2 =
        fixture.invoice(reference, listOf(fixture.documentaryLine(goods, fixture.allocationId)), fixture.orderLink)

    private fun roundTrip(invoice: PurchaseInvoiceCaptureV2): PurchaseInvoiceCaptureV2 {
        val bytes = json.encodeToString(PurchaseInvoiceCaptureV2.serializer(), invoice)
        return json.decodeFromString(PurchaseInvoiceCaptureV2.serializer(), bytes).also { assertEquals(invoice, it) }
    }

    private fun assertOtherUnit(invoice: PurchaseInvoiceCaptureV2) {
        assertEquals("000073-A/فاتورة", invoice.reference)
        assertGoods(invoice.lines.single().goods, fixture.skuA, fixture.productA, fixture.tray,
            fixture.each, 6, "5", 0, "50.25", 30, 25125, "00000042-A")
        assertEquals(Money(Currency.EGP, 25125), invoice.total())
    }

    private fun assertChangedConversion(invoice: PurchaseInvoiceCaptureV2) {
        assertEquals("000073-B/فاتورة", invoice.reference)
        assertGoods(invoice.lines.single().goods, fixture.skuA, fixture.productA, fixture.carton,
            fixture.each, 10, "3", 0, "90.25", 30, 27075, "00000042-B")
        assertEquals(Money(Currency.EGP, 27075), invoice.total())
    }

    private fun assertMixed(invoice: PurchaseInvoiceCaptureV2) {
        assertEquals("000073-C/فاتورة", invoice.reference)
        assertEquals(2, invoice.lines.size)
        assertGoods(invoice.lines[0].goods, fixture.skuB, fixture.productB, fixture.carton,
            fixture.each, 12, "2", 0, "120", 24, 24000, "00000043-B")
        assertGoods(invoice.lines[1].goods, fixture.skuC, fixture.productC, fixture.kilogram,
            fixture.gram, 1000, "2.5", 3, "40", 2500, 10000, "00000007-C")
        assertEquals(Money(Currency.EGP, 34000), invoice.total())
    }

    private fun assertOriginalOrder(order: PurchaseOrderV1) {
        assertGoods(order.lines.single().goods, fixture.skuA, fixture.productA, fixture.carton,
            fixture.each, 12, "2", 0, "120", 24, 24000, "PO-000042")
        assertEquals(Money(Currency.EGP, 24000), order.total())
    }

    private fun assertGoods(goods: PurchaseGoodsV1, sku: DomainId, product: DomainId,
        purchaseUnit: DomainId, baseUnit: DomainId, rate: Long, quantity: String, quantityScale: Int,
        unitPrice: String, baseQuantity: Long, expectedMinor: Long, itemReference: String) {
        assertEquals(sku, goods.skuId)
        assertEquals(product, goods.units.productId)
        assertEquals(itemReference, goods.supplierItemReference)
        assertEquals(purchaseUnit, goods.units.purchaseUnitId)
        assertEquals(baseUnit, goods.units.baseUnitId)
        assertEquals(UnitConversionRate.ratio(rate, 1), goods.units.rate)
        assertEquals(Quantity.parse(purchaseUnit, quantity, quantityScale), goods.quantity)
        assertEquals(UnitPrice.parse(Currency.EGP, unitPrice), goods.unitPrice)
        assertEquals(PercentageRate.parse("0"), goods.discount)
        assertEquals(PercentageRate.parse("0"), goods.tax.rate)
        assertEquals(Money(Currency.EGP, 0), goods.taxAmount)
        assertEquals(Quantity(baseUnit, baseQuantity, 0), goods.baseQuantity())
        assertEquals(Money(Currency.EGP, expectedMinor), goods.net)
        assertEquals(Money(Currency.EGP, expectedMinor), goods.calculatedNet())
        assertEquals(Money(Currency.EGP, expectedMinor), goods.total())
    }

    private fun assertUnallocated(invoice: PurchaseInvoiceCaptureV2, order: PurchaseOrderV1?) {
        invoice.lines.forEach {
            assertNull(it.internalAllocationId)
            assertEquals(PurchaseInvoiceEntryProvenanceV2.DOCUMENTARY, it.provenance)
        }
        assertTrue(PurchaseDiscrepancyV1.ORDER_LINE_REQUIRED in purchaseInvoiceCaptureDiscrepanciesV2(order, invoice))
    }

    private fun assertReferencedContext(invoice: PurchaseInvoiceCaptureV2) {
        assertEquals(fixture.orderLink, invoice.internalOrderLink)
        assertEquals(fixture.allocationId, invoice.lines.single().internalAllocationId)
        assertEquals(PurchaseInvoiceEntryProvenanceV2.DOCUMENTARY, invoice.lines.single().provenance)
    }

    private fun assertMaterialVariance(order: PurchaseOrderV1, invoice: PurchaseInvoiceCaptureV2) {
        val discrepancies = purchaseInvoiceCaptureDiscrepanciesV2(order, invoice)
        assertTrue(PurchaseDiscrepancyV1.ITEM_OR_UNIT_VARIANCE in discrepancies)
        assertTrue(PurchaseDiscrepancyV1.PRICE_OR_DISCOUNT_VARIANCE in discrepancies)
    }
}
