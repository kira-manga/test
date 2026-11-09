package com.ukkera.pos.sales

import com.ukkera.pos.pricing.PricingProfiles
import com.ukkera.pos.pricing.PricingSnapshot
import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.pricing.StoredReturnCursor
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@Serializable
internal data class StoredTenderShare(val paymentId: DomainId, val amount: Money) {
    fun restore(): OriginalTenderShare = OriginalTenderShare(paymentId, amount)
}

@Serializable
internal data class StoredComponentTenderRow(val componentKey: String, val shares: List<StoredTenderShare>) {
    fun restore(): OriginalComponentTenderAllocation {
        snapshotCheck(shares.size <= 64, SalesSnapshotFailure.TOO_LARGE)
        return OriginalComponentTenderAllocation(componentKey, shares.map { it.restore() })
    }

    companion object {
        fun from(value: OriginalComponentTenderAllocation): StoredComponentTenderRow = StoredComponentTenderRow(value.componentKey,
            value.shares.map { StoredTenderShare(it.paymentId, it.amount) })
    }
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
internal data class StoredPaidSale(
    val orderId: DomainId, val originalCreatorId: DomainId, val collectorId: DomainId, val collectingSessionId: DomainId,
    val quote: String, val stockIssueId: DomainId, val payments: List<StoredPaymentRecord>,
    val originalTenderAllocations: List<StoredComponentTenderRow>,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val priceSourceReference: SalesPriceSourceReference? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val returnPolicySourceReference: SalesReturnPolicySourceReference? = null,
) {
    fun restore(id: DomainId, scope: BranchScope): PaidSale {
        snapshotCheck(payments.size <= 64 && originalTenderAllocations.size <= 1_024, SalesSnapshotFailure.TOO_LARGE)
        return PaidSale(id, scope, orderId, originalCreatorId, collectorId, collectingSessionId,
            StoredPricingSnapshotCodec.decode(quote), stockIssueId, payments.map { it.restore() },
            originalTenderAllocations.map { it.restore() }, priceSourceReference, returnPolicySourceReference).also(::validateStoredSale)
    }

    companion object {
        fun from(value: PaidSale): StoredPaidSale = StoredPaidSale(value.orderId, value.originalCreatorId, value.collectorId,
            value.collectingSessionId, StoredPricingSnapshotCodec.encode(value.quote), value.stockIssueId,
            value.payments.map(StoredPaymentRecord::from), value.originalTenderAllocations.map(StoredComponentTenderRow::from), value.priceSourceReference, value.returnPolicySourceReference)
    }
}

@Serializable
internal data class StoredConsumedQuantity(val portionId: DomainId, val consumedQuantity: Quantity)

@Serializable
internal data class StoredReceiptEntitlement(
    val sale: StoredPaidSale, val returnProfile: String, val cursors: List<StoredConsumedQuantity>,
    val remainingComponentTenders: List<StoredComponentTenderRow>,
) {
    fun restore(binding: SalesSnapshotBinding): ReceiptEntitlement {
        snapshotCheck(returnProfile == PricingProfiles.STORED_RETURN_V1)
        snapshotCheck(cursors.size in 1..256 && remainingComponentTenders.size <= 1_024, SalesSnapshotFailure.TOO_LARGE)
        val original = sale.restore(binding.id, binding.scope)
        return ReceiptEntitlement(original, binding.revision,
            cursors.map { restoreOriginalCursor(original.quote, it.portionId, it.consumedQuantity) },
            remainingComponentTenders.map { it.restore() }).also(::validateStoredEntitlement)
    }

    companion object {
        fun from(value: ReceiptEntitlement): StoredReceiptEntitlement = StoredReceiptEntitlement(StoredPaidSale.from(value.sale),
            PricingProfiles.STORED_RETURN_V1, value.cursors.map { StoredConsumedQuantity(it.portionId, it.consumedQuantity) },
            value.remainingComponentTenders.map(StoredComponentTenderRow::from))
    }
}

/** Pure cumulative arithmetic on the protected ORIGINAL receipt; never current pricing. */
internal fun restoreOriginalCursor(quote: PricingSnapshot, portionId: DomainId, consumed: Quantity): StoredReturnCursor {
    val original = quote.openReturnCursor(portionId)
    snapshotCheck(consumed.unitId == original.originalQuantity.unitId && consumed.scale == original.originalQuantity.scale &&
        consumed.scaled >= 0 && consumed <= original.originalQuantity)
    return if (consumed.scaled == 0L) original else original.consume(consumed).remaining
}

internal fun validateStoredSale(sale: PaidSale) {
    snapshotCheck(sale.returnPolicySourceReference == null || sale.priceSourceReference != null)
    snapshotCheck(sale.quote.lines.size in 1..256 && sale.quote.components.size in 1..1_024, SalesSnapshotFailure.TOO_LARGE)
    sale.quote.payable.requireNonnegative()
    snapshotCheck(sale.payments.map { it.attemptId }.distinct().size == sale.payments.size)
    snapshotCheck(sale.payments.map { it.collectionId }.distinct().size <= 1)
    for (payment in sale.payments) {
        validateStoredPayment(payment)
        snapshotCheck(payment.foodResolutionObservation == null)
        snapshotCheck(payment.collectorId == sale.collectorId && payment.cashSessionId == sale.collectingSessionId)
        sameCurrency(sale.quote.payable, payment.amount)
    }
    snapshotCheck(sale.payments.fold(sale.quote.payable.zero()) { total, payment -> total + payment.amount } == sale.quote.payable)
    val componentAmounts = sale.quote.components.associate { it.key to it.posted }
    snapshotCheck(componentAmounts.size == sale.quote.components.size)
    snapshotCheck(componentAmounts.values.fold(sale.quote.payable.zero(), Money::plus) == sale.quote.payable)
    val totals = validateTenderRows(sale.originalTenderAllocations, componentAmounts, sale.payments)
    snapshotCheck(sale.payments.all { totals[it.attemptId] == it.amount })
}

internal fun validateStoredEntitlement(entitlement: ReceiptEntitlement) {
    val sale = entitlement.sale
    validateStoredSale(sale)
    snapshotCheck(entitlement.cursors.map { it.portionId }.distinct().size == entitlement.cursors.size)
    snapshotCheck(entitlement.cursors.map { it.portionId }.toSet() == sale.quote.lines.map { it.portionId }.toSet())
    val remaining = sale.quote.components.associate { it.key to it.posted }.toMutableMap()
    for (cursor in entitlement.cursors) {
        val original = sale.quote.openReturnCursor(cursor.portionId)
        snapshotCheck(cursor.originalSnapshotId == original.originalSnapshotId && cursor.revision == original.revision &&
            cursor.originalQuantity == original.originalQuantity && cursor.originalComponents == original.originalComponents &&
            cursor.profile == PricingProfiles.STORED_RETURN_V1)
        val reconstructed = restoreOriginalCursor(sale.quote, cursor.portionId, cursor.consumedQuantity)
        snapshotCheck(cursor.remainingComponents == reconstructed.remainingComponents)
        for (component in cursor.remainingComponents) remaining[component.key] = component.amount
    }
    snapshotCheck((entitlement.revision == Revision(0)) == entitlement.cursors.all { it.consumedQuantity.scaled == 0L })
    validateTenderRows(entitlement.remainingComponentTenders, remaining, sale.payments, sale.originalTenderAllocations)
}

/** Row amounts and original payment columns both conserve; a row/key cannot be silently dropped. */
internal fun validateTenderRows(
    rows: List<OriginalComponentTenderAllocation>,
    componentAmounts: Map<String, Money>,
    payments: List<PaymentRecord>,
    caps: List<OriginalComponentTenderAllocation>? = null,
): Map<DomainId, Money> {
    snapshotCheck(rows.map { it.componentKey }.distinct().size == rows.size && rows.map { it.componentKey }.toSet() == componentAmounts.keys)
    val paymentIds = payments.map { it.attemptId }.toSet()
    val totals = payments.associate { it.attemptId to it.amount.zero() }.toMutableMap()
    val original = caps?.associateBy { it.componentKey }
    for (row in rows) {
        val component = componentAmounts.getValue(row.componentKey)
        component.requireNonnegative()
        snapshotCheck(row.shares.map { it.paymentId }.distinct().size == row.shares.size && row.shares.map { it.paymentId }.toSet() == paymentIds)
        val rowCaps = original?.getValue(row.componentKey)?.shares?.associate { it.paymentId to it.amount }
        var sum = component.zero()
        for (share in row.shares) {
            share.amount.requireNonnegative()
            sameCurrency(component, share.amount)
            snapshotCheck(rowCaps == null || share.amount.minor <= rowCaps.getValue(share.paymentId).minor)
            sum += share.amount
            totals[share.paymentId] = totals.getValue(share.paymentId) + share.amount
        }
        snapshotCheck(sum == component)
    }
    snapshotCheck(payments.all { totals.getValue(it.attemptId).minor <= it.amount.minor })
    return totals
}
