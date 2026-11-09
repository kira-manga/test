package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.types.*

/**
 * Capture-only committed and finite refusal history over genuine already-joined BEFORE
 * sources. No recreated handler, mutable current catalog, employee session or read view.
 */
internal class LocalPurchasingInvoiceCaptureHistoryProducerV2(
    private val catalog: PurchasingInvoiceCaptureCatalogAuthorityV2,
) : RuntimeHistoryBusinessProducer {
    override val commands: Set<CommandKey> = setOf(LocalPurchasingProfile.CAPTURE_INVOICE)

    override suspend fun join(command: RuntimeHistoricalCommand): Boolean {
        if (command.command !in commands || command.original !is RetainedOriginalV2.Legacy ||
            command.authority != RuntimeHistoricalAuthority.EMPLOYEE_COMMAND) return false
        val original = (command.original as RetainedOriginalV2.Legacy).evidence
        check(purchasingNormalize(command.command, original.normalized) == original.normalized &&
            semanticDigest(original.scope, original.originalActorId, original.command, original.normalized) == original.semanticDigest &&
            original.scope == command.scope && original.operationId == command.operationId && original.originalActorId == command.actorId &&
            original.command == command.command && original.normalized == command.normalized && original.semanticDigest == command.semanticDigest &&
            original.recordedAt == command.recordedAt && original.policyRevision == command.policyRevision)
        if (original.committed) requirePurchasingProducer(original)
        val request = LocalPurchasingCodec.decodeCaptureInvoice(command.normalized)
        val invoice = request.invoice
        command.requireRequirement(invoiceCaptureRequirement(command.scope, invoice.id, command.operationId,
            command.semanticDigest, request.reasonCode))
        val baseResources = purchasingCaptureResources(command.scope, invoice) + priceResource(command.actorId)
        try {
            val reader = PurchasingReader(command)
            // Preserve the live read/hydration order, including its purchasing-owned budget.
            val existing = reader.invoiceDocument(invoice.id)
            val order = invoice.internalOrderLink?.let { reader.order(it.orderId) }
            val supplier = supplier(command, invoice.supplierId)
            val reference = purchasingReferenceClaim(command.scope, "invoice", invoice.supplierId, invoice.reference)
            val referenceOwner = command.claim(reference)
            referenceOwner?.let { reader.requireInvoiceReferenceOwner(it, reference) }
            var native: PurchasingCaptureNativeCatalogBeforeV2? = null
            suspend fun nativeBefore(): PurchasingCaptureNativeCatalogBeforeV2 = native
                ?: catalog.nativeBefore(command).also { native = it }
            val items = invoice.lines.groupBy { it.goods.skuId }.mapValues { (skuId, lines) ->
                catalog.historical(command, skuId,
                    lines.flatMap { listOf(it.goods.units.purchaseUnitId, it.goods.units.baseUnitId) }.toSet(), ::nativeBefore)
            }
            val cut = PurchasingCaptureCreationCutV2(existing, referenceOwner, order, supplier, items)
            check(!cut.sourceUnavailable)
            val resources = purchasingCaptureResources(command.scope, invoice, cut) + priceResource(command.actorId)
            val refusal = purchasingCaptureRefusal(request, cut)
            val change = if (refusal != null) null else AggregateChange(purchasingKey(LocalPurchasingProfile.INVOICE, invoice.id), null,
                LocalPurchasingProfile.schema(LocalPurchasingProfile.INVOICE), purchasingEncode(PurchasingRecord(command.scope,
                    LocalPurchasingProfile.INVOICE, invoice.id, Revision(1),
                    PurchasingProducer(command.operationId, command.actorId, command.command.name, command.semanticDigest.value),
                    command.recordedAt, command.policyRevision, purchasingCaptureBody(request, cut)), PurchasingRecord.serializer()))
            val finalRefusal = refusal ?: if (checkNotNull(change).payload.size > 65_536) "purchasing.capacity_required" else null
            if (!original.committed) {
                check(finalRefusal != null && finalRefusal == original.code)
                command.requireInvoiceCaptureRejection(invoiceCaptureRejection(finalRefusal, invoice.id), resources)
            } else {
                check(finalRefusal == null)
                command.requireLegacyEffects(ProposedOutcome.Apply(invoiceCaptureResult("purchasing.invoice_captured", invoice.id, Revision(1)),
                    listOf(checkNotNull(change)), listOf(purchasingAcquisition(LocalPurchasingProfile.INVOICE, invoice.id),
                        ClaimChange.Acquire(reference, invoice.id))), resources)
            }
        } catch (_: PurchasingHydrationCapacityUnavailable) {
            check(!original.committed && original.code == "purchasing.capacity_unavailable")
            command.requireInvoiceCaptureRejection(invoiceCaptureRejection("purchasing.capacity_unavailable", invoice.id), baseResources)
        }
        return true
    }

    private suspend fun supplier(command: RuntimeHistoricalCommand, id: DomainId): Pair<StoredAggregate, LocalSupplierV1>? {
        val row = command.aggregateSource(LocalConfigurationProfile.key(LocalConfigurationProfile.SUPPLIER, id))?.row ?: return null
        val value = (decodeRecord(command.scope, row).body as? ConfigurationBody.Supplier)?.value
            ?: error("Original capture supplier unavailable")
        check(value.id == id)
        return row to value
    }
}
