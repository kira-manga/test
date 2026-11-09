package com.ukkera.pos.inventory

import com.ukkera.pos.types.*
import kotlinx.serialization.Serializable

/** Original document unit snapshot. Only the PO producer qualifies it against the current catalog. */
@Serializable
public data class PurchaseUnitsV1(
    public val productId: DomainId,
    public val purchaseUnitId: DomainId,
    public val baseUnitId: DomainId,
    public val baseScale: Int,
    public val conversionId: DomainId?,
    public val conversionRevision: Revision?,
    public val rate: UnitConversionRate,
    public val dimensionalEvidenceId: DomainId? = null,
) {
    init {
        require(baseScale in 0..6)
        require((conversionId == null) == (conversionRevision == null))
        if (purchaseUnitId == baseUnitId) require(conversionId == null && rate == UnitConversionRate.ratio(1, 1) && dimensionalEvidenceId == null)
        else require(conversionId != null)
    }
    public fun base(quantity: Quantity): Quantity {
        require(quantity.unitId == purchaseUnitId && quantity.scaled > 0)
        return Quantity(baseUnitId, (quantity.toFraction() * rate.toFraction()).toScaledLongExact(baseScale), baseScale)
            .requirePositive()
    }
}

/** Purchase-only attested tax basis. Not a sale tax inclusion flag or a fiscal approval. */
@Serializable
public data class PurchaseTaxV1(
    public val sourceId: DomainId, public val revision: Revision,
    public val categoryCode: String, public val rate: PercentageRate,
) {
    init { purchaseCode(categoryCode) }
}

/** Supplier amounts are kept even when they do not equal calculated amounts. */
@Serializable
public data class PurchaseGoodsV1(
    public val skuId: DomainId,
    public val supplierItemReference: String,
    public val units: PurchaseUnitsV1,
    public val quantity: Quantity,
    public val unitPrice: UnitPrice,
    public val discount: PercentageRate,
    public val tax: PurchaseTaxV1,
    public val net: Money,
    public val taxAmount: Money,
) {
    init {
        purchaseText(supplierItemReference, 96, allowEmpty = true)
        units.base(quantity)
        require(unitPrice.currency == Currency.EGP && unitPrice.amount >= ExactDecimal.parse("0"))
        discount.requireAtMost(ExactDecimal.parse("100"))
        purchaseMoney(net); purchaseMoney(taxAmount)
        net + taxAmount
        // Reject unsupported input arithmetic before any command reaches a writer transaction.
        calculatedNet(); calculatedTax()
    }
    public fun baseQuantity(): Quantity = units.base(quantity)
    public fun calculatedNet(): Money = (unitPrice.toFraction() * quantity.toFraction() *
        (ExactRational.ratio(1, 1) - discount.toFraction())).toMoney(Currency.EGP)
    public fun calculatedTax(): Money = (ExactRational.ratio(net.minor, 100) * tax.rate.toFraction()).toMoney(Currency.EGP)
    public fun total(): Money = net + taxAmount
    public fun amountsAgree(): Boolean = net == calculatedNet() && taxAmount == calculatedTax()
}

@Serializable
public enum class PurchaseChargeTreatmentV1 { ACQUISITION_COST_PENDING_ALLOCATION, NON_INVENTORY }

/** One original obligation source, never an additional stock/value/settlement effect. */
@Serializable
public data class PurchaseChargeV1(
    public val id: DomainId, public val code: String, public val sourceEvidenceId: DomainId,
    public val treatment: PurchaseChargeTreatmentV1, public val net: Money, public val taxAmount: Money,
) {
    init { purchaseCode(code); purchaseMoney(net); purchaseMoney(taxAmount); net + taxAmount }
    public fun total(): Money = net + taxAmount
}

@Serializable
public enum class PurchaseReceiptCostBasisV1 { NET_GOODS_ONLY }

@Serializable
public data class PurchaseOrderLineV1(
    public val allocationId: DomainId, public val warehouseId: DomainId,
    /** Explicit approved basis, not an inferred decision to capitalize/exclude purchase tax. */
    public val receiptCostBasis: PurchaseReceiptCostBasisV1,
    public val inspectionRequired: Boolean, public val requestedDelivery: BusinessDate?,
    public val goods: PurchaseGoodsV1,
)

@Serializable
public data class PurchaseOrderV1(
    public val id: DomainId, public val supplierId: DomainId, public val reference: String,
    public val orderDate: BusinessDate, public val expectedDelivery: BusinessDate?,
    public val paymentTerms: String, public val validFrom: UtcInstant, public val validUntil: UtcInstant?,
    public val lines: List<PurchaseOrderLineV1>, public val charges: List<PurchaseChargeV1>,
) {
    init {
        purchaseReference(reference); purchaseText(paymentTerms, 256, allowEmpty = false)
        require(validUntil == null || validUntil > validFrom)
        require(lines.size in 1..16 && lines.map { it.allocationId }.distinct().size == lines.size)
        require(charges.size <= 8 && charges.map { it.id }.distinct().size == charges.size)
        total()
    }
    public fun total(): Money = (lines.map { it.goods.total() } + charges.map { it.total() }).fold(purchaseZero(), Money::plus)
}

@Serializable
public data class PurchaseInvoiceLineV1(
    public val lineId: DomainId, public val allocationId: DomainId, public val goods: PurchaseGoodsV1,
)

@Serializable
public data class PurchaseInvoiceV1(
    public val id: DomainId, public val orderId: DomainId, public val supplierId: DomainId,
    public val reference: String, public val invoiceDate: BusinessDate, public val dueDate: BusinessDate,
    public val sourceEvidenceId: DomainId,
    public val lines: List<PurchaseInvoiceLineV1>, public val charges: List<PurchaseChargeV1>,
) {
    init {
        purchaseReference(reference)
        require(lines.size in 1..16 && lines.map { it.lineId }.distinct().size == lines.size)
        require(charges.size <= 8 && charges.map { it.id }.distinct().size == charges.size)
        total()
    }
    public fun total(): Money = (lines.map { it.goods.total() } + charges.map { it.total() }).fold(purchaseZero(), Money::plus)
}

@Serializable
public enum class PurchaseDiscrepancyV1 {
    ORDER_LINE_REQUIRED, ITEM_OR_UNIT_VARIANCE, PRICE_OR_DISCOUNT_VARIANCE, TAX_VARIANCE,
    AMOUNT_VARIANCE, QUANTITY_VARIANCE, CHARGE_MATCH_REQUIRED,
}

/** No missing line or invoice-before-arrival is converted into a paid/approved amount. */
public fun purchaseDiscrepancies(order: PurchaseOrderV1, invoice: PurchaseInvoiceV1): Set<PurchaseDiscrepancyV1> = buildSet {
    if (invoice.charges.isNotEmpty()) add(PurchaseDiscrepancyV1.CHARGE_MATCH_REQUIRED)
    for (line in invoice.lines) {
        val original = order.lines.singleOrNull { it.allocationId == line.allocationId }
        if (original == null) add(PurchaseDiscrepancyV1.ORDER_LINE_REQUIRED)
        else addAll(purchaseLineDiscrepancies(original.goods, line.goods))
    }
    for (original in order.lines) {
        val quantities = invoice.lines.filter { it.allocationId == original.allocationId && it.goods.units == original.goods.units }
        val maximum = original.goods.baseQuantity()
        var total = maximum.copy(scaled = 0)
        for (line in quantities) {
            if (line.goods.baseQuantity() > maximum - total) { add(PurchaseDiscrepancyV1.QUANTITY_VARIANCE); break }
            total += line.goods.baseQuantity()
        }
    }
}

public fun purchaseLineDiscrepancies(order: PurchaseGoodsV1, invoice: PurchaseGoodsV1): Set<PurchaseDiscrepancyV1> = buildSet {
    if (order.skuId != invoice.skuId || order.units != invoice.units) add(PurchaseDiscrepancyV1.ITEM_OR_UNIT_VARIANCE)
    if (order.unitPrice != invoice.unitPrice || order.discount != invoice.discount) add(PurchaseDiscrepancyV1.PRICE_OR_DISCOUNT_VARIANCE)
    if (order.tax != invoice.tax) add(PurchaseDiscrepancyV1.TAX_VARIANCE)
    if (!invoice.amountsAgree()) add(PurchaseDiscrepancyV1.AMOUNT_VARIANCE)
    if (order.units == invoice.units && invoice.baseQuantity() > order.baseQuantity()) add(PurchaseDiscrepancyV1.QUANTITY_VARIANCE)
}

/** Only the unchanged approved basis is recognizable here; a higher supplier price remains unapproved. */
public fun purchaseUncontestedPriceIncrease(order: PurchaseGoodsV1, invoice: PurchaseGoodsV1): Boolean =
    order.skuId == invoice.skuId && order.units == invoice.units && order.discount == invoice.discount &&
        order.tax == invoice.tax && invoice.unitPrice.amount > order.unitPrice.amount &&
        order.amountsAgree() && invoice.amountsAgree() && invoice.baseQuantity() <= order.baseQuantity()

/** Two distinct immutable amount streams. This is a calculation, never a variance or settlement approval. */
@Serializable
public data class PurchaseUncontestedAmountsV1(
    public val net: Money, public val tax: Money, public val claimedNet: Money, public val claimedTax: Money,
) {
    init {
        listOf(net, tax, claimedNet, claimedTax).forEach(::purchaseMoney)
        require(net.minor <= claimedNet.minor && tax.minor <= claimedTax.minor)
        net + tax; claimedNet + claimedTax
    }
    public fun recognized(): Money = net + tax
    public fun claimed(): Money = claimedNet + claimedTax
    public fun disputed(): Money = claimed() - recognized()
}

/**
 * Each stream telescopes from its own original full quantity and observed cumulative offset.
 * Never floor/min independent unit-price slices or spend the supplier's disputed remainder.
 * Null means even the original PO slice exceeds the supplier slice at this rounding cut;
 * no missing rounding policy is replaced with a permissive tolerance.
 */
public fun purchaseUncontestedAmounts(
    order: PurchaseGoodsV1, invoice: PurchaseGoodsV1, orderBefore: Quantity, invoiceBefore: Quantity, quantity: Quantity,
): PurchaseUncontestedAmountsV1? {
    require(purchaseUncontestedPriceIncrease(order, invoice))
    val ordered = order.baseQuantity()
    val billed = invoice.baseQuantity()
    require(quantity.scaled > 0 && listOf(orderBefore, invoiceBefore, quantity).all {
        it.unitId == ordered.unitId && it.scale == ordered.scale
    })
    require(orderBefore.scaled >= 0 && invoiceBefore.scaled >= 0 && orderBefore <= ordered && invoiceBefore <= billed &&
        quantity <= ordered - orderBefore && quantity <= billed - invoiceBefore)
    val net = purchaseCumulativeAmount(order.net, orderBefore + quantity, ordered) - purchaseCumulativeAmount(order.net, orderBefore, ordered)
    val tax = purchaseCumulativeAmount(order.taxAmount, orderBefore + quantity, ordered) - purchaseCumulativeAmount(order.taxAmount, orderBefore, ordered)
    val claimedNet = purchaseCumulativeAmount(invoice.net, invoiceBefore + quantity, billed) - purchaseCumulativeAmount(invoice.net, invoiceBefore, billed)
    val claimedTax = purchaseCumulativeAmount(invoice.taxAmount, invoiceBefore + quantity, billed) - purchaseCumulativeAmount(invoice.taxAmount, invoiceBefore, billed)
    if (net.minor > claimedNet.minor || tax.minor > claimedTax.minor) return null
    return PurchaseUncontestedAmountsV1(net, tax, claimedNet, claimedTax)
}

/** Cumulative floor in MINOR units, not independent rounded slices; full quantity conserves every penny. */
public fun purchaseCumulativeAmount(total: Money, quantity: Quantity, fullQuantity: Quantity): Money {
    purchaseMoney(total)
    require(quantity.unitId == fullQuantity.unitId && quantity.scale == fullQuantity.scale &&
        quantity.scaled >= 0 && fullQuantity.scaled > 0 && quantity <= fullQuantity)
    return Money(total.currency, (ExactRational.ratio(total.minor, 1) * ExactRational.ratio(quantity.scaled, fullQuantity.scaled)).floorScaled(0))
}

/** Deliberately declared normalization, not Unicode-confusable or cross-branch uniqueness. */
public fun normalizedPurchaseReference(reference: String): String {
    purchaseReference(reference)
    return reference.filter { it.isLetterOrDigit() }.map { if (it in 'a'..'z') it.uppercaseChar() else it }.joinToString("")
}

public fun purchaseZero(): Money = Money(Currency.EGP, 0)

private fun purchaseMoney(value: Money) { require(value.currency == Currency.EGP && value.minor >= 0) }
private fun purchaseReference(value: String) {
    require(value.length in 1..96 && value.any { it.isLetterOrDigit() } && value.all { it.isLetterOrDigit() || it in " -_./" })
}
private fun purchaseCode(value: String) { require(value.length in 1..64 && value.all { it.isLetterOrDigit() || it in "._-" }) }
private fun purchaseText(value: String, maximum: Int, allowEmpty: Boolean) {
    require(value.length <= maximum && (allowEmpty || value.isNotBlank()) && value.none { it.code < 0x20 || it.code == 0x7f })
}
