package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.types.Revision

/** Same authorization and bounded provenance hydration as the actual purchasing document read. */
internal class PurchasingCreditSourceRead(private val stock: LocalStockRepository) : MasterReadHandler {
    override val key: ReadKey = LocalPurchasingProfile.READ_CREDIT_SOURCE
    override fun normalize(input: StoredBytes): StoredBytes = LocalPurchasingCodec.creditSource(
        purchasingDecode(input, LocalPurchasingCreditSourceQueryV1.serializer()))

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        requirePurchasingReadContext(view, context, key, normalized)
        val query = purchasingDecode(normalized, LocalPurchasingCreditSourceQueryV1.serializer())
        val reader = PurchasingReader(view)
        val selected = reader.row(LocalPurchasingProfile.INVOICE, query.invoiceId)
        return boundedPurchasingRead(context, listOf(query.invoiceId to (selected?.row?.revision ?: Revision(0))),
            true, false, { unavailableCreditSource(query) },
            { unavailableCreditSource(query, LocalPurchasingReadAvailabilityV1.UNSUPPORTED_INVOICE_CAPTURE) }) {
            val cut = reader.invoice(query.invoiceId)
            if (cut != null) requirePurchasingCrossAccounts(reader, cut, stock, view)
            val invoice = cut?.let { purchasingInvoiceClaim(it.invoice) }
            val due = cut?.let(::purchasingDue)
            purchasingEncode(LocalPurchasingCreditSourceV1(query, cut != null, cut?.invoice?.row?.revision,
                cut?.progress?.row?.revision, cut?.payable?.row?.revision, cut?.credits?.index?.row?.revision,
                invoice?.supplierId, invoice?.total(), due?.recognized, due?.appliedCredit, due?.remainingDue,
                settlementRevision = cut?.settlements?.index?.row?.revision, allocatedPayment = due?.allocatedPayment,
                reservedCash = cut?.takeIf { due != null }?.settlements?.reservedTotal,
                availableToAllocate = cut?.let(::purchasingAvailableToAllocate)),
                LocalPurchasingCreditSourceV1.serializer())
        }
    }
}

private fun unavailableCreditSource(query: LocalPurchasingCreditSourceQueryV1,
    availability: LocalPurchasingReadAvailabilityV1 = LocalPurchasingReadAvailabilityV1.HYDRATION_CAPACITY_UNAVAILABLE): StoredBytes = purchasingEncode(
    LocalPurchasingCreditSourceV1(query, null, null, null, null, null, null, null, null, null, null,
        availability), LocalPurchasingCreditSourceV1.serializer())
