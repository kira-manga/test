package com.ukkera.pos.inventory.independent.supplierinvoicecapturev2

import com.ukkera.pos.inventory.*
import com.ukkera.pos.types.*

/**
 * Numeric inputs come from the frozen supplier-invoice-discrepancy-capture-v1 and
 * supplier-invoice-referenced-discrepancy-v1 fixtures. IDs are synthetic domain
 * bindings only; they are not assigned Master identities or qualified sources.
 */
internal object SupplierInvoiceCaptureV2FrozenFixture {
    val skuA = id(1)
    val skuB = id(2)
    val skuC = id(3)
    val productA = id(4)
    val productB = id(5)
    val productC = id(6)
    val carton = id(7)
    val tray = id(8)
    val each = id(9)
    val kilogram = id(10)
    val gram = id(11)
    val supplier = id(12)
    val orderId = id(13)
    val allocationId = id(14)
    val lineB = id(15)
    val lineC = id(16)
    val invoiceId = id(28)
    val sourceEvidenceId = id(29)
    val orderLink = PurchaseInvoiceOrderLinkV2(orderId, Revision(7))

    // Capture freeze fixtures.json:88-103 and 177-184; referenced fixture:22-39.
    fun originalGoods(): PurchaseGoodsV1 = goods(skuA, productA, carton, each, id(21),
        12, "2", 0, "120", 24000, "PO-000042")

    // Capture freeze fixtures.json:148-155.
    fun otherUnit(): PurchaseGoodsV1 = goods(skuA, productA, tray, each, id(22),
        6, "5", 0, "50.25", 25125, "00000042-A")

    // Capture freeze fixtures.json:157-165.
    fun changedConversion(): PurchaseGoodsV1 = goods(skuA, productA, carton, each, id(23),
        10, "3", 0, "90.25", 27075, "00000042-B")

    // Capture freeze fixtures.json:167-175. EA and G stay separate quantities.
    fun mixedB(): PurchaseGoodsV1 = goods(skuB, productB, carton, each, id(24),
        12, "2", 0, "120", 24000, "00000043-B")

    fun mixedC(): PurchaseGoodsV1 = goods(skuC, productC, kilogram, gram, id(25),
        1000, "2.5", 3, "40", 10000, "00000007-C")

    // Referenced freeze fixtures.json:57-70.
    fun referencedDifferentItemUnit(): PurchaseGoodsV1 = goods(skuB, productB, tray, each, id(26),
        5, "4", 0, "57.35", 22940, "000009-B")

    // Referenced freeze fixtures.json:72-85.
    fun referencedDifferentConversion(): PurchaseGoodsV1 = goods(skuA, productA, carton, each, id(23),
        10, "3", 0, "91.10", 27330, "000008-A")

    fun order(): PurchaseOrderV1 = PurchaseOrderV1(
        id = orderId, supplierId = supplier, reference = "PO-000042",
        orderDate = BusinessDate.parseCanonical("2026-11-05"), expectedDelivery = null,
        paymentTerms = "Synthetic original terms",
        validFrom = UtcInstant.parseCanonical("2026-11-05T00:00:00Z"), validUntil = null,
        lines = listOf(PurchaseOrderLineV1(allocationId, id(27),
            PurchaseReceiptCostBasisV1.NET_GOODS_ONLY, false, null, originalGoods())),
        charges = emptyList(),
    )

    fun documentaryLine(goods: PurchaseGoodsV1, allocation: DomainId? = null,
        literalReference: String? = null, lineId: DomainId = lineB): PurchaseInvoiceCaptureLineV2 =
        PurchaseInvoiceCaptureLineV2(lineId, goods, PurchaseInvoiceEntryProvenanceV2.DOCUMENTARY,
            allocation, literalReference)

    fun invoice(reference: String, lines: List<PurchaseInvoiceCaptureLineV2>,
        context: PurchaseInvoiceOrderLinkV2? = null,
        literalOrderReference: String? = null): PurchaseInvoiceCaptureV2 = PurchaseInvoiceCaptureV2(
        id = invoiceId, supplierId = supplier, reference = reference,
        invoiceDate = BusinessDate.parseCanonical("2026-11-05"),
        dueDate = BusinessDate.parseCanonical("2026-11-05"), sourceEvidenceId = sourceEvidenceId,
        lines = lines, charges = emptyList(), internalOrderLink = context,
        supplierOrderReference = literalOrderReference,
    )

    private fun goods(sku: DomainId, product: DomainId, purchaseUnit: DomainId, baseUnit: DomainId,
        conversion: DomainId, numerator: Long, quantity: String, quantityScale: Int,
        unitPrice: String, declaredMinor: Long, itemReference: String): PurchaseGoodsV1 = PurchaseGoodsV1(
        skuId = sku, supplierItemReference = itemReference,
        units = PurchaseUnitsV1(product, purchaseUnit, baseUnit, 0, conversion, Revision(7),
            UnitConversionRate.ratio(numerator, 1)),
        quantity = Quantity.parse(purchaseUnit, quantity, quantityScale),
        unitPrice = UnitPrice.parse(Currency.EGP, unitPrice), discount = PercentageRate.parse("0"),
        tax = PurchaseTaxV1(id(30), Revision(7), "SYNTHETIC_ZERO", PercentageRate.parse("0")),
        net = Money(Currency.EGP, declaredMinor), taxAmount = Money(Currency.EGP, 0),
    )

    private fun id(value: Int): DomainId = DomainId("00000000-0000-0000-0000-${value.toString().padStart(12, '0')}")
}
