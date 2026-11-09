package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** Actual original claim sources, with no receipt, payment, matching or allowance consumption. */
internal data class PurchasingCaptureCreationCutV2(
    val existing: PurchasingRow?, val referenceOwner: DomainId?, val order: PurchasingRow?,
    val supplier: Pair<StoredAggregate, LocalSupplierV1>?,
    val items: Map<DomainId, PurchasingCaptureItemResolutionV2>,
) {
    val originalOrder: PurchaseOrderV1? get() = (order?.value?.body as? PurchasingBody.Order)?.value
    val sourceUnavailable: Boolean get() = items.values.any { it.sourceUnavailable }
}

internal class PurchasingInvoiceCaptureHandlerV2(private val catalog: PurchasingInvoiceCaptureCatalogAuthorityV2) :
    MasterCommandHandler, LocalMasterRpcRetainedResultAccessProvider {
    override val key: CommandKey = LocalPurchasingProfile.CAPTURE_INVOICE
    override val writableKinds: Set<String> = setOf(LocalPurchasingProfile.INVOICE)
    override val claimNamespaces: Set<String> = setOf(LocalPurchasingProfile.DOCUMENT_CLAIM, LocalPurchasingProfile.REFERENCE_CLAIM)
    override fun normalize(input: StoredBytes): StoredBytes = try { purchasingNormalize(key, input) }
        catch (_: ArithmeticException) { throw IllegalArgumentException("Unsupported purchasing input precision or range") }

    override fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean =
        profile == LocalMasterRpcWorkflowProfile.INVOICE_CAPTURE && normalize(normalized) == normalized

    /** Disclosure checks the exact original capture permission without preparing its sources again. */
    override fun rpcRetainedResultRequirements(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes,
        context: CommandContext): List<AuthorizationRequirement> {
        check(supportsRpcWorkflow(profile, normalized) && context.key == key &&
            semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        val request = LocalPurchasingCodec.decodeCaptureInvoice(normalized)
        return listOf(invoiceCaptureRequirement(context.scope, request.invoice.id, context.operationId,
            context.semanticDigest, request.reasonCode))
    }

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        check(context.scope == view.scope && context.key == key &&
            semanticDigest(view.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        val request = LocalPurchasingCodec.decodeCaptureInvoice(normalized)
        val invoice = request.invoice
        val baseResources = purchasingCaptureResources(view.scope, invoice)
        return try {
            val reader = PurchasingReader(view)
            val existing = reader.invoiceDocument(invoice.id)
            val order = invoice.internalOrderLink?.let { reader.order(it.orderId) }
            val supplier = purchasingCaptureSupplier(view, invoice.supplierId)
            val reference = purchasingReferenceClaim(view.scope, "invoice", invoice.supplierId, invoice.reference)
            val referenceOwner = view.claim(reference)
            referenceOwner?.let { reader.requireInvoiceReferenceOwner(it, reference) }
            val items = invoice.lines.groupBy { it.goods.skuId }.mapValues { (skuId, lines) ->
                catalog.current(view, skuId, lines.flatMap { listOf(it.goods.units.purchaseUnitId, it.goods.units.baseUnitId) }.toSet())
            }
            val cut = PurchasingCaptureCreationCutV2(existing, referenceOwner, order, supplier, items)
            val resources = purchasingCaptureResources(view.scope, invoice, cut)
            preparedInvoiceCapture(context, request, resources, cut.sourceUnavailable) { auth, at ->
                val refusal = purchasingCaptureRefusal(request, cut)
                if (refusal != null) invoiceCaptureRejection(refusal, invoice.id)
                else {
                    val body = purchasingCaptureBody(request, cut)
                    val change = purchasingChange(context, LocalPurchasingProfile.INVOICE, invoice.id, null, body, at, auth.policyRevision)
                    if (change.payload.size > 65_536) invoiceCaptureRejection("purchasing.capacity_required", invoice.id)
                    else ProposedOutcome.Apply(invoiceCaptureResult("purchasing.invoice_captured", invoice.id, Revision(1)), listOf(change),
                        listOf(purchasingAcquisition(LocalPurchasingProfile.INVOICE, invoice.id), ClaimChange.Acquire(reference, invoice.id)))
                }
            }
        } catch (_: PurchasingHydrationCapacityUnavailable) {
            preparedInvoiceCapture(context, request, baseResources) { _, _ ->
                invoiceCaptureRejection("purchasing.capacity_unavailable", invoice.id)
            }
        }
    }
}

/** Capture does not borrow PURCHASING_APPROVE or a monetary approval limit. */
internal fun invoiceCaptureRequirement(scope: BranchScope, invoiceId: DomainId, operationId: OperationId,
    digest: PayloadDigest, reason: String): AuthorizationRequirement =
    AuthorizationRequirement(Permission(SecurityAction.PURCHASING_INVOICE_CAPTURE, PermissionVerb.PERFORM), scope,
        ActionBinding(invoiceId, Revision(0), digest, operationId, reason))

private fun preparedInvoiceCapture(context: CommandContext, request: LocalCaptureSupplierInvoiceV2,
    resources: Set<RecoveryResourceKey>, sourceUnavailable: Boolean = false,
    proposal: (AuthorizedAction, UtcInstant) -> ProposedOutcome): PreparedMutation = object : PreparedMutation() {
    override val requirement: AuthorizationRequirement = invoiceCaptureRequirement(context.scope, request.invoice.id,
        context.operationId, context.semanticDigest, request.reasonCode)
    override val allowsActionApproval: Boolean = false
    override val admissionRestriction: PreparedMutationAdmissionRestriction? =
        if (sourceUnavailable) PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE else null
    override val affectedResources: Set<RecoveryResourceKey> = resources + priceResource(context.originalActorId)
    override val recoveryHoldBarrierKinds: Set<RecoveryResourceKind> = purchasingRecoveryBarriers
    override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
        check(authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE && authorization.approvalId == null &&
            authorization.isBoundTo(requirement, context.originalActorId))
        check(!sourceUnavailable)
        return proposal(authorization, recordedAt)
    }
}

internal fun purchasingCaptureRefusal(request: LocalCaptureSupplierInvoiceV2, cut: PurchasingCaptureCreationCutV2): String? {
    val invoice = request.invoice
    if (cut.existing != null) return "purchasing.identity_already_used"
    if (cut.referenceOwner != null) return "purchasing.duplicate_reference"
    val link = invoice.internalOrderLink
    val order = cut.originalOrder
    if (link != null && (cut.order?.row?.revision != link.revision || order?.id != link.orderId || order.supplierId != invoice.supplierId ||
            invoice.lines.any { line -> line.internalAllocationId?.let { id -> order.lines.none { it.allocationId == id } } == true }))
        return "purchasing.order_required"
    if (cut.supplier?.second?.active != true || cut.supplier.first.revision != request.expectedSupplierRevision)
        return "purchasing.supplier_required"
    for (line in invoice.lines) {
        val resolved = cut.items.getValue(line.goods.skuId)
        // Unknown native provenance is an admission deferral, never a durable valid/invalid-source fiction.
        check(!resolved.sourceUnavailable)
        resolved.rejection?.let { return it }
        val item = resolved.item ?: error("Capture catalog authority unavailable")
        purchasingCaptureGoodsRejection(line.goods, item.definition, item.units)?.let { return it }
        if (line.provenance == PurchaseInvoiceEntryProvenanceV2.SELECTED_ORDER_LINE) {
            val original = order?.lines?.singleOrNull { it.allocationId == line.internalAllocationId }
            if (original == null || original.goods.skuId != line.goods.skuId || original.goods.units != line.goods.units)
                return "purchasing.selected_order_line_required"
        }
    }
    return null
}

internal fun purchasingCaptureBody(request: LocalCaptureSupplierInvoiceV2, cut: PurchasingCaptureCreationCutV2): PurchasingBody.InvoiceCapture {
    val invoice = request.invoice
    check(purchasingCaptureRefusal(request, cut) == null)
    return PurchasingBody.InvoiceCapture(invoice, normalizedPurchaseReference(invoice.reference),
        cut.order?.row?.let(PurchasingSource::from), cut.order?.value?.producer?.operationId,
        PurchasingSource.from(checkNotNull(cut.supplier).first),
        cut.items.values.map { checkNotNull(it.item).sources }.sortedBy { it.skuSource.id.value },
        purchaseInvoiceCaptureDiscrepanciesV2(cut.originalOrder, invoice).sortedBy { it.name })
}

/** Include every referenced identity/source and original author in the exact recovery boundary. */
internal fun purchasingCaptureResources(scope: BranchScope, invoice: PurchaseInvoiceCaptureV2,
    cut: PurchasingCaptureCreationCutV2? = null): Set<RecoveryResourceKey> = buildSet {
    add(RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT, invoice.id))
    add(RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT, invoice.supplierId))
    add(RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT,
        purchasingReferenceResource(purchasingReferenceClaim(scope, "invoice", invoice.supplierId, invoice.reference))))
    add(RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, invoice.sourceEvidenceId))
    invoice.internalOrderLink?.let { add(RecoveryResourceKey(RecoveryResourceKind.PO_ALLOWANCE, it.orderId)) }
    for (line in invoice.lines) {
        line.internalAllocationId?.let { add(RecoveryResourceKey(RecoveryResourceKind.PO_ALLOWANCE, it)) }
        add(RecoveryResourceKey(RecoveryResourceKind.STOCK, line.goods.skuId))
        listOfNotNull(line.goods.units.productId, line.goods.units.purchaseUnitId, line.goods.units.baseUnitId,
            line.goods.units.conversionId, line.goods.units.dimensionalEvidenceId, line.goods.tax.sourceId).forEach { add(priceResource(it)) }
    }
    for (charge in invoice.charges) {
        add(RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT, charge.id))
        add(RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, charge.sourceEvidenceId))
    }
    cut?.order?.let { add(priceResource(it.value.producer.actorId)) }
    cut?.existing?.let { add(priceResource(it.value.producer.actorId)) }
    cut?.supplier?.first?.let { add(priceResource(it.key.id)); add(priceResource(decodeRecord(scope, it).authorizedBy)) }
    cut?.items?.values?.forEach { addAll(it.resources) }
}

private suspend fun purchasingCaptureSupplier(view: CommandReadView, id: DomainId): Pair<StoredAggregate, LocalSupplierV1>? {
    val row = view.aggregate(LocalConfigurationProfile.key(LocalConfigurationProfile.SUPPLIER, id)) ?: return null
    val value = (decodeRecord(view.scope, row).body as? ConfigurationBody.Supplier)?.value ?: error("Capture supplier source unavailable")
    check(value.id == id)
    return row to value
}

internal fun invoiceCaptureResult(code: String, id: DomainId, revision: Revision? = null): StoredBytes =
    LocalPurchasingCodec.captureResult(LocalInvoiceCaptureResultV2(code, id, revision))
internal fun invoiceCaptureRejection(code: String, id: DomainId): ProposedOutcome.Reject =
    ProposedOutcome.Reject(code, invoiceCaptureResult(code, id))

/** Existing unqualified capture is never conflated with a claim-checked absent invoice. */
internal class PurchasingInvoiceCaptureUnqualified(val capture: PurchasingInvoiceCaptureCut) : RuntimeException()

/** Legacy financial actions retain their full-document requirement but cannot record an unsupported refusal. */
internal suspend fun unqualifiedCaptureFinancialDeferral(context: CommandContext, reader: PurchasingReader, row: PurchasingRow,
    revision: Revision, reason: String, extraResources: Set<RecoveryResourceKey> = emptySet()): PreparedMutation {
    val invoice = (row.value.body as PurchasingBody.InvoiceCapture).value
    val resources = purchasingCaptureResources(context.scope, invoice) + priceResource(row.value.producer.actorId) + extraResources
    requireCaptureFinancialDeferralSource(reader, row)
    return preparedPurchasing(context, invoice.id, revision, invoice.total(), reason, resources,
        admissionRestriction = PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE) { _, _ ->
        error("Unresolved capture financial action must be deferred before evaluation")
    }
}

/** Hydration capacity does not turn an unsupported capture route into durable legacy history. */
internal suspend fun requireCaptureFinancialDeferralSource(reader: PurchasingReader, row: PurchasingRow) {
    check(row.value.body is PurchasingBody.InvoiceCapture)
    try { checkNotNull(reader.captureInvoice(row.value.id)) }
    catch (_: PurchasingHydrationCapacityUnavailable) { /* Admission remains SOURCE_UNAVAILABLE. */ }
}
