package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.inventory.store.purchaseReceiptFacts
import com.ukkera.pos.inventory.store.LocalStockHydrationCapacityUnavailable
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** Cost-bearing source quote over the same current ledgers and original evidence as
 * the command. A quote is not a reservation, approval, match or recognition. */
internal class PurchasingCapturedMatchSourceReadV2(private val stock: LocalStockRepository) : MasterReadHandler {
    override val key: ReadKey = LocalPurchasingProfile.READ_CAPTURED_MATCH_SOURCE
    override fun normalize(input: StoredBytes): StoredBytes {
        require(input.size <= 4_096)
        return LocalPurchasingCodec.capturedMatchSource(LocalPurchasingCodec.decodeCapturedMatchSourceQuery(input))
            .also { require(it.size <= 4_096) }
    }
    override fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean =
        profile == LocalMasterRpcWorkflowProfile.INVOICE_MATCHING && normalize(normalized) == normalized

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        requirePurchasingReadContext(view, context, key, normalized)
        val query = LocalPurchasingCodec.decodeCapturedMatchSourceQuery(normalized)
        val reader = PurchasingReader(view)
        val selected = reader.row(LocalPurchasingProfile.INVOICE, query.invoiceId)
        val bindings = mutableListOf(query.invoiceId to (selected?.row?.revision ?: Revision(0)))
        val result = try {
            if (selected?.value?.body !is PurchasingBody.InvoiceCapture) {
                if (selected == null) check(reader.invoice(query.invoiceId) == null) else reader.invoiceDocument(query.invoiceId)
                unavailableCapturedMatchSourceV2(query, LocalPurchasingReadAvailabilityV1.SOURCE_UNAVAILABLE)
            } else {
                val selectedOrder = reader.order(query.orderId)
                bindings += query.orderId to (selectedOrder?.row?.revision ?: Revision(0))
                val cut = purchasingCapturedMatchCutV2(reader, query, null,
                    { receiptId, lineId -> stock.purchaseReceiptFacts(view, receiptId, lineId) },
                    { invoice -> requirePurchasingCrossAccounts(reader, invoice, stock, view) })
                bindings += query.orderId to (cut.order?.row?.revision ?: Revision(0))
                bindings += query.receiptId to (cut.receipt?.receiptRevision ?: Revision(0))
                val file = reader.documents.file(cut.claim.sourceEvidenceId)
                val originalRevision = file?.takeIf { it.value.kind == LocalPurchasingDocumentProfileV1.ORIGINAL }?.row?.revision
                var refusal: String? = null
                val slice = try { purchasingCapturedMatchSliceV2(query, cut) }
                    catch (rejected: PurchaseRejected) { refusal = rejected.code; null }
                val line = cut.claim.lines.singleOrNull { it.lineId == query.invoiceLineId }
                val ordered = (cut.order?.value?.body as? PurchasingBody.Order)?.value?.lines
                    ?.singleOrNull { it.allocationId == query.allocationId }
                val progress = cut.progress.lines.singleOrNull { it.lineId == query.invoiceLineId }
                val billed = cut.orderBilling?.value?.body as? PurchasingBody.OrderBilled
                val receiptBilled = cut.receiptBilling?.value?.body as? PurchasingBody.ReceiptBilled
                val payable = cut.recognized?.payable?.value?.body as? PurchasingBody.Payable
                LocalCapturedSupplierInvoiceMatchSourceV2(query,
                    refusal ?: LocalCapturedSupplierInvoiceMatchCodesV2.SOURCE_READY, LocalPurchasingReadAvailabilityV1.AVAILABLE,
                    cut.document.source != null && cut.order != null && line != null && ordered != null && cut.receipt != null,
                    slice != null, cut.invoice.row.revision, originalRevision, cut.order?.row?.revision, cut.receipt?.receiptRevision,
                    cut.recognized?.progress?.row?.revision, cut.orderMatches?.row?.revision, cut.orderBilling?.row?.revision,
                    cut.receiptBilling?.row?.revision, cut.recognized?.payable?.row?.revision, cut.receipt?.sourceId,
                    if (line != null && progress != null) line.goods.baseQuantity() - progress.quantity else null,
                    if (ordered != null && billed != null) ordered.goods.baseQuantity() - billed.quantity else null,
                    cut.receipt?.let { it.acceptedQuantity - (receiptBilled?.quantity ?: it.baseQuantity.copy(scaled = 0)) },
                    cut.claim.total(), slice?.net, slice?.tax,
                    payable?.let { it.net + it.tax }) // BEFORE null is true absence, never an invented zero.
            }
        } catch (_: PurchasingHydrationCapacityUnavailable) {
            unavailableCapturedMatchSourceV2(query, LocalPurchasingReadAvailabilityV1.HYDRATION_CAPACITY_UNAVAILABLE)
        } catch (_: LocalStockHydrationCapacityUnavailable) {
            unavailableCapturedMatchSourceV2(query, LocalPurchasingReadAvailabilityV1.HYDRATION_CAPACITY_UNAVAILABLE)
        }
        val bytes = purchasingEncode(result, LocalCapturedSupplierInvoiceMatchSourceV2.serializer())
        val projection = if (bytes.size <= 65_536) bytes else purchasingEncode(unavailableCapturedMatchSourceV2(query,
            LocalPurchasingReadAvailabilityV1.HYDRATION_CAPACITY_UNAVAILABLE), LocalCapturedSupplierInvoiceMatchSourceV2.serializer())
        return object : PreparedRead() {
            override val requirements: List<AuthorizationRequirement> = bindings.distinct().flatMap { (id, revision) ->
                listOf(SecurityAction.PURCHASING_READ, SecurityAction.INVENTORY_QUANTITY_READ, SecurityAction.INVENTORY_COST_READ).map { action ->
                    AuthorizationRequirement(Permission(action, PermissionVerb.PERFORM), context.scope,
                        ActionBinding(id, revision, context.semanticDigest, reasonCode = "purchasing.captured-match-source"))
                }
            }
            override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes {
                check(authorizations.size == requirements.size && authorizations.zip(requirements).all { (authorization, required) ->
                    authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE && authorization.isBoundTo(required, context.actorId)
                })
                return projection
            }
        }
    }
}

private fun unavailableCapturedMatchSourceV2(query: LocalCapturedSupplierInvoiceMatchSourceQueryV2,
    availability: LocalPurchasingReadAvailabilityV1): LocalCapturedSupplierInvoiceMatchSourceV2 =
    LocalCapturedSupplierInvoiceMatchSourceV2(query,
        if (availability == LocalPurchasingReadAvailabilityV1.HYDRATION_CAPACITY_UNAVAILABLE) "purchasing.capacity_unavailable"
        else "purchasing.captured_match_source_unavailable", availability, sourcePresent = null, sourceAndTermsCompatible = null,
        invoiceRevision = null, originalRevision = null, orderRevision = null, receiptRevision = null,
        progressRevision = null, orderMatchRevision = null, orderBillingRevision = null, receiptBillingRevision = null, payableRevision = null,
        sourceId = null, invoiceRemaining = null, orderRemaining = null, acceptedReceiptRemaining = null,
        originalInvoiceClaim = null, incrementNet = null, incrementTax = null, recognizedPayable = null)
