package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** Full documentary quantity may exceed the approved/accepted quantity. Only a
 * bounded exact-price slice can be recognized; the original excess stays unresolved. */
private fun purchasingCapturedLineLiteralTermsAgreeV2(order: PurchaseGoodsV1, claim: PurchaseGoodsV1): Boolean =
    purchaseLineDiscrepancies(order, claim).all { it == PurchaseDiscrepancyV1.QUANTITY_VARIANCE }

internal fun purchasingCapturedLineTermsAgree(order: PurchaseGoodsV1, claim: PurchaseGoodsV1): Boolean {
    if (purchasingCapturedLineLiteralTermsAgreeV2(order, claim)) return true
    val original = order.units
    val claimed = claim.units
    // Direct-base claims carry rate 1 and no conversion identity/evidence. A
    // documentary non-base rate is not an authoritative catalog conversion.
    if (order.skuId != claim.skuId || original.productId != claimed.productId ||
        original.baseUnitId != claimed.baseUnitId || original.baseScale != claimed.baseScale ||
        claimed.purchaseUnitId != claimed.baseUnitId) return false
    return order.unitPrice.currency == claim.unitPrice.currency && order.discount == claim.discount &&
        order.tax == claim.tax && order.amountsAgree() && claim.amountsAgree() &&
        order.unitPrice.toFraction() / original.rate.toFraction() ==
            claim.unitPrice.toFraction() / claimed.rate.toFraction()
}

internal fun LocalMatchCapturedSupplierInvoiceV2.sourceQuery(): LocalCapturedSupplierInvoiceMatchSourceQueryV2 =
    LocalCapturedSupplierInvoiceMatchSourceQueryV2(invoiceId, invoiceLineId, expectedInvoiceRevision,
        originalDocumentId, expectedOriginalRevision, orderId, expectedOrderRevision, allocationId,
        receiptId, receiptLineId, expectedReceiptRevision, quantity)

/** These are literal source facts from one actual current/native-BEFORE observation,
 * not a converted v1 invoice, receipt allocator or parallel payable ledger. */
internal data class PurchasingCapturedMatchCutV2(
    val original: PurchasingInvoiceCaptureCut, val recognized: PurchasingInvoiceCut?,
    val order: PurchasingRow?, val orderMatches: PurchasingRow?, val orderBilling: PurchasingRow?,
    val document: PurchasingCapturedOriginalResolutionV1, val receipt: LocalPurchaseReceiptFacts?,
    val receiptBilling: PurchasingRow?, val previousMatch: PurchasingRow?,
    val resources: Set<RecoveryResourceKey>,
) {
    val invoice: PurchasingRow get() = original.invoice
    val claim: PurchaseInvoiceCaptureV2 get() = (invoice.value.body as PurchasingBody.InvoiceCapture).value
    val progress: PurchasingBody.Progress get() = recognized?.progress?.value?.body as? PurchasingBody.Progress
        ?: purchasingInitialProgress(purchasingInvoiceClaim(invoice))
}

internal fun purchasingCapturedMatchResourcesV2(
    invoice: PurchasingRow, selection: LocalCapturedSupplierInvoiceMatchSourceQueryV2,
): Set<RecoveryResourceKey> {
    val claim = (invoice.value.body as PurchasingBody.InvoiceCapture).value
    return purchasingCaptureResources(invoice.value.scope, claim) + setOf(
        supplierSettlementResource(claim.id), supplierSettlementResource(claim.supplierId),
        RecoveryResourceKey(RecoveryResourceKind.PO_ALLOWANCE, selection.orderId),
        RecoveryResourceKey(RecoveryResourceKind.PO_ALLOWANCE, selection.allocationId),
        RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, selection.originalDocumentId),
        RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, selection.receiptId),
        RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, selection.receiptLineId),
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, invoice.value.producer.actorId))
}

internal suspend fun purchasingCapturedMatchCutV2(
    reader: PurchasingReader, selection: LocalCapturedSupplierInvoiceMatchSourceQueryV2, matchId: DomainId?,
    receiptFacts: suspend (DomainId, DomainId) -> LocalPurchaseReceiptFacts?,
    crossAccounts: suspend (PurchasingInvoiceCut) -> Unit,
): PurchasingCapturedMatchCutV2 {
    val original = reader.capturedInvoiceOriginal(selection.invoiceId) ?: error("Selected captured invoice unavailable")
    val recognized = if (reader.row(LocalPurchasingProfile.PROGRESS, selection.invoiceId) == null) {
        checkNotNull(reader.captureInvoice(selection.invoiceId)) // Claim-checked complete financial absence.
        null
    } else checkNotNull(reader.invoice(selection.invoiceId))
    val claim = (original.invoice.value.body as PurchasingBody.InvoiceCapture).value
    // Always qualify the immutable associated evidence. A different supplied ID never
    // causes adoption or hydration of that unrelated document.
    val document = purchasingCapturedOriginal(reader.documents, claim, selection.expectedOriginalRevision)
    val order = reader.order(selection.orderId)
    val ordered = (order?.value?.body as? PurchasingBody.Order)?.value
    val orderMatches = if (order == null) {
        check(reader.row(LocalPurchasingProfile.ORDER_MATCHES, selection.orderId) == null)
        null
    } else reader.orderMatches(selection.orderId)
    val selectedLine = ordered?.lines?.singleOrNull { it.allocationId == selection.allocationId }
    val orderBilling = selectedLine?.let { reader.orderBilling(checkNotNull(ordered), it.allocationId) }
    val previousMatch = matchId?.let { reader.row(LocalPurchasingProfile.MATCH, it) }
    val receipt = receiptFacts(selection.receiptId, selection.receiptLineId)
    val receiptBilling = receipt?.let { reader.receiptBilling(it.sourceId) }
    if (recognized != null) crossAccounts(recognized)
    // The selected PO shares capacities and supplier history with BOTH invoice formats.
    if (ordered != null && recognized?.order?.row?.key?.id != ordered.id)
        reader.observeSupplierOrderDependencies(ordered.id)
    val resources = purchasingCapturedMatchResourcesV2(original.invoice, selection) +
        ordered?.let(::purchasingResources).orEmpty() + document.resources + receipt?.recoveryResources.orEmpty() +
        listOfNotNull(order?.value?.producer?.actorId).map { RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it) } +
        matchId?.let { setOf(supplierSettlementResource(it)) }.orEmpty() + reader.supplierRecoveryResources
    return PurchasingCapturedMatchCutV2(original, recognized, order, orderMatches, orderBilling, document,
        receipt, receiptBilling, previousMatch, resources)
}

internal data class PurchasingCapturedMatchSliceV2(
    val line: PurchaseInvoiceCaptureLineV2, val ordered: PurchaseOrderLineV1,
    val invoiceBefore: PurchasingLineProgress, val orderBefore: PurchasingBody.OrderBilled,
    val receiptBefore: PurchasingBody.ReceiptBilled?, val receiptQuantity: Quantity,
    val net: Money, val tax: Money,
)

/** Same ordered first-refusal calculation for live admission, protected quote and
 * native BEFORE replay. It never moves goods/cash or approves the unresolved remainder. */
internal fun purchasingCapturedMatchSliceV2(
    selection: LocalCapturedSupplierInvoiceMatchSourceQueryV2, cut: PurchasingCapturedMatchCutV2,
    request: LocalMatchCapturedSupplierInvoiceV2? = null,
    literalTermsOnly: Boolean = false,
): PurchasingCapturedMatchSliceV2 {
    val invoice = cut.claim
    purchaseRequire(selection.originalDocumentId == invoice.sourceEvidenceId, "purchasing.original_association_mismatch")
    cut.document.refusalCode?.let { throw PurchaseRejected(it) }
    purchaseRequire(request == null || cut.previousMatch == null, "purchasing.identity_already_used")
    purchaseRequire(cut.invoice.row.revision == selection.expectedInvoiceRevision, "purchasing.stale_revision")
    val originalLink = invoice.internalOrderLink
    purchaseRequire(originalLink == null || originalLink.orderId == selection.orderId, "purchasing.order_association_conflict")
    purchaseRequire(cut.recognized == null || cut.recognized.order.row.key.id == selection.orderId,
        "purchasing.multi_order_association_unsupported")
    val orderRow = cut.order ?: throw PurchaseRejected("purchasing.order_required")
    val order = (orderRow.value.body as PurchasingBody.Order).value
    purchaseRequire(order.supplierId == invoice.supplierId, "purchasing.order_association_conflict")
    purchaseRequire(orderRow.row.revision == selection.expectedOrderRevision &&
        (originalLink == null || originalLink.revision == orderRow.row.revision), "purchasing.stale_revision")
    val line = invoice.lines.singleOrNull { it.lineId == selection.invoiceLineId }
        ?: throw PurchaseRejected("purchasing.invoice_line_required")
    purchaseRequire(line.internalAllocationId == null || line.internalAllocationId == selection.allocationId,
        "purchasing.allocation_association_conflict")
    val prior = cut.recognized?.matches.orEmpty().filter { it.request.invoiceLineId == line.lineId }.map { it.allocationId }.distinct()
    check(prior.size <= 1)
    purchaseRequire(prior.isEmpty() || prior.single() == selection.allocationId, "purchasing.allocation_association_conflict")
    val ordered = order.lines.singleOrNull { it.allocationId == selection.allocationId }
        ?: throw PurchaseRejected("purchasing.order_line_required")
    // Retained older refusals can tighten this gate, never bypass it or grant a match.
    val termsAgree = if (literalTermsOnly) purchasingCapturedLineLiteralTermsAgreeV2(ordered.goods, line.goods)
        else purchasingCapturedLineTermsAgree(ordered.goods, line.goods)
    purchaseRequire(termsAgree, "purchasing.variance_requires_approval")
    val receipt = cut.receipt ?: throw PurchaseRejected("purchasing.receipt_required")
    purchaseRequire(receipt.receiptRevision == selection.expectedReceiptRevision, "purchasing.stale_revision")
    if (request != null) purchaseRequire(
        cut.recognized?.progress?.row?.revision == request.expectedProgressRevision &&
            cut.orderMatches?.row?.revision == request.expectedOrderMatchRevision &&
            cut.orderBilling?.row?.revision == request.expectedOrderBillingRevision &&
            cut.receiptBilling?.row?.revision == request.expectedReceiptBillingRevision &&
            cut.recognized?.payable?.row?.revision == request.expectedPayableRevision,
        "purchasing.stale_revision")
    purchaseRequire(receipt.scope == cut.invoice.value.scope && receipt.basisId == order.id && receipt.basisRevision == Revision(0) &&
        receipt.basisKind == LocalStockBasisKind.LOCAL_PURCHASE && receipt.supplierId == invoice.supplierId &&
        receipt.allocationId == ordered.allocationId && receipt.skuId == ordered.goods.skuId && receipt.warehouseId == ordered.warehouseId &&
        purchasingReceiptUnitsAgree(ordered, receipt) && purchasingReceiptOrderSourceAgrees(orderRow, receipt.poValuation),
        "purchasing.receipt_source_mismatch")
    purchaseRequire(receipt.disposition in setOf(LocalReceiptDisposition.RELEASED, LocalReceiptDisposition.QUARANTINE) &&
        receipt.originalOwnedValue != null && receipt.acceptedQuantity.scaled > 0 && receipt.allowance != null,
        "purchasing.accepted_receipt_required")
    val progress = cut.progress
    val invoiceBefore = progress.lines.single { it.lineId == line.lineId }
    val orderBefore = checkNotNull(cut.orderBilling).value.body as PurchasingBody.OrderBilled
    val receiptBefore = cut.receiptBilling?.value?.body as? PurchasingBody.ReceiptBilled
    if (receiptBefore != null) check(receiptBefore.orderId == order.id && receiptBefore.allocationId == ordered.allocationId &&
        receiptBefore.receiptId == receipt.receiptId && receiptBefore.lineId == receipt.lineId)
    val receiptQuantity = receiptBefore?.quantity ?: receipt.baseQuantity.copy(scaled = 0)
    val orderMatches = checkNotNull(cut.orderMatches).value.body as PurchasingBody.OrderMatches
    purchaseRequire(progress.matchIds.size < LocalPurchasingProfile.MAX_MATCHES && orderMatches.matchIds.size < LocalPurchasingProfile.MAX_MATCHES &&
        orderBefore.matchIds.size < LocalPurchasingProfile.MAX_MATCHES &&
        (receiptBefore?.matchIds?.size ?: 0) < LocalPurchasingProfile.MAX_MATCHES, "purchasing.capacity_required")
    val quantity = selection.quantity
    purchaseRequire(quantity.unitId == receipt.baseQuantity.unitId && quantity.scale == receipt.baseQuantity.scale,
        "purchasing.quantity_unit_mismatch")
    // Remaining capacities are compared before adding, including already billed v1
    // invoices and every other captured claim. The accepted allowance is not replenished.
    purchaseRequire(quantity <= line.goods.baseQuantity() - invoiceBefore.quantity &&
        quantity <= ordered.goods.baseQuantity() - orderBefore.quantity &&
        quantity <= receipt.acceptedQuantity - receiptQuantity &&
        quantity <= checkNotNull(receipt.allowance).acceptedQuantity - orderBefore.quantity,
        "purchasing.remaining_quantity_exceeded")
    purchaseRequire(receipt.serialId == null || quantity == receipt.baseQuantity, "purchasing.whole_serial_required")
    purchaseRequire(purchasingReceiptValueAgrees(order, ordered, receipt), "purchasing.receipt_cost_variance")
    val net = purchaseCumulativeAmount(line.goods.net, invoiceBefore.quantity + quantity, line.goods.baseQuantity()) - invoiceBefore.net
    val tax = purchaseCumulativeAmount(line.goods.taxAmount, invoiceBefore.quantity + quantity, line.goods.baseQuantity()) - invoiceBefore.tax
    purchaseRequire(net == purchaseCumulativeAmount(ordered.goods.net, orderBefore.quantity + quantity, ordered.goods.baseQuantity()) - orderBefore.net &&
        tax == purchaseCumulativeAmount(ordered.goods.taxAmount, orderBefore.quantity + quantity, ordered.goods.baseQuantity()) - orderBefore.tax,
        "purchasing.rounding_variance")
    return PurchasingCapturedMatchSliceV2(line, ordered, invoiceBefore, orderBefore, receiptBefore, receiptQuantity, net, tax)
}

internal fun purchasingCapturedMatchRequirementV2(
    scope: BranchScope, invoiceId: DomainId, invoiceRevision: Revision, operationId: OperationId,
    digest: PayloadDigest, reason: String, originalInvoiceClaim: Money,
): AuthorizationRequirement = AuthorizationRequirement(Permission(SecurityAction.PURCHASING_INVOICE_MATCH_RECOGNIZE, PermissionVerb.PERFORM),
    scope, ActionBinding(invoiceId, invoiceRevision, digest, operationId, reason), LimitUse.Monetary(originalInvoiceClaim))

internal fun purchasingCapturedMatchRefusalV2(code: String, request: LocalMatchCapturedSupplierInvoiceV2, invoice: PurchasingRow): ProposedOutcome.Reject =
    ProposedOutcome.Reject(code, LocalPurchasingCodec.capturedMatchResult(LocalCapturedSupplierInvoiceMatchResultV2(code,
        request.matchId, request.invoiceId, invoice.row.revision, purchasingInvoiceClaim(invoice).total(),
        null, null, null, null, null, null)))

internal fun purchasingCapturedMatchEffectsV2(
    request: LocalMatchCapturedSupplierInvoiceV2, cut: PurchasingCapturedMatchCutV2, slice: PurchasingCapturedMatchSliceV2,
    change: (String, DomainId, PurchasingRow?, PurchasingBody) -> AggregateChange,
): ProposedOutcome.Apply {
    val orderRow = checkNotNull(cut.order)
    val order = (orderRow.value.body as PurchasingBody.Order).value
    val receipt = checkNotNull(cut.receipt)
    val invoice = cut.claim
    val event = PurchasingBody.CapturedMatch(request, checkNotNull(cut.document.source),
        PurchasingSource.from(cut.invoice.row), cut.invoice.value.producer.operationId,
        PurchasingSource.from(orderRow.row), orderRow.value.producer.operationId, PurchasingReceiptSnapshot.from(receipt),
        slice.invoiceBefore.quantity, slice.orderBefore.quantity, slice.receiptQuantity, slice.net, slice.tax)
    requirePurchasingMatch(order, purchasingInvoiceClaim(cut.invoice), PurchasingMatchFact(event))
    val progress = cut.progress
    val nextProgress = progress.copy(lines = progress.lines.map { if (it.lineId == slice.line.lineId)
        it.copy(quantity = it.quantity + request.quantity, net = it.net + slice.net, tax = it.tax + slice.tax) else it },
        matchIds = progress.matchIds + request.matchId)
    val nextOrder = slice.orderBefore.copy(quantity = slice.orderBefore.quantity + request.quantity,
        net = slice.orderBefore.net + slice.net, tax = slice.orderBefore.tax + slice.tax,
        matchIds = slice.orderBefore.matchIds + request.matchId)
    val nextReceipt = PurchasingBody.ReceiptBilled(receipt.receiptId, receipt.lineId, receipt.sourceId, order.id, slice.ordered.allocationId,
        slice.receiptQuantity + request.quantity, (slice.receiptBefore?.net ?: purchaseZero()) + slice.net,
        (slice.receiptBefore?.tax ?: purchaseZero()) + slice.tax, slice.receiptBefore?.matchIds.orEmpty() + request.matchId)
    val nextPayable = PurchasingBody.Payable(invoice.id, order.id, invoice.supplierId, invoice.dueDate, PurchasingPayableStatus.UNSETTLED,
        nextProgress.lines.fold(purchaseZero()) { sum, it -> sum + it.net }, nextProgress.lines.fold(purchaseZero()) { sum, it -> sum + it.tax },
        nextProgress.matchIds)
    val orderMatches = checkNotNull(cut.orderMatches).value.body as PurchasingBody.OrderMatches
    val changes = listOf(change(LocalPurchasingProfile.MATCH, request.matchId, null, event),
        change(LocalPurchasingProfile.ORDER_MATCHES, order.id, cut.orderMatches, orderMatches.copy(matchIds = orderMatches.matchIds + request.matchId)),
        change(LocalPurchasingProfile.PROGRESS, invoice.id, cut.recognized?.progress, nextProgress),
        change(LocalPurchasingProfile.ORDER_BILLED, slice.ordered.allocationId, cut.orderBilling, nextOrder),
        change(LocalPurchasingProfile.RECEIPT_BILLED, receipt.sourceId, cut.receiptBilling, nextReceipt),
        change(LocalPurchasingProfile.PAYABLE, invoice.id, cut.recognized?.payable, nextPayable))
    val claims = listOf(purchasingAcquisition(LocalPurchasingProfile.MATCH, request.matchId)) +
        (if (cut.recognized == null) listOf(purchasingAcquisition(LocalPurchasingProfile.PROGRESS, invoice.id)) else emptyList()) +
        (if (cut.receiptBilling == null) listOf(purchasingAcquisition(LocalPurchasingProfile.RECEIPT_BILLED, receipt.sourceId)) else emptyList()) +
        (if (cut.recognized?.payable == null) listOf(purchasingAcquisition(LocalPurchasingProfile.PAYABLE, invoice.id)) else emptyList())
    val result = LocalCapturedSupplierInvoiceMatchResultV2(LocalCapturedSupplierInvoiceMatchCodesV2.SUCCESS,
        request.matchId, invoice.id, cut.invoice.row.revision, invoice.total(), Revision(1),
        (cut.recognized?.progress?.row?.revision ?: Revision(0)).next(),
        (cut.recognized?.payable?.row?.revision ?: Revision(0)).next(), request.quantity, slice.net, slice.tax)
    return ProposedOutcome.Apply(LocalPurchasingCodec.capturedMatchResult(result), changes, claims)
}

/** One serialized, Master-authorized match AND incremental operational-payable commit. */
internal class PurchasingCapturedMatchHandlerV2(private val stock: LocalStockRepository) :
    MasterCommandHandler, LocalMasterRpcRetainedResultSourceAccessProvider {
    override val key: CommandKey = LocalPurchasingProfile.MATCH_CAPTURED_INVOICE
    override val writableKinds: Set<String> = setOf(LocalPurchasingProfile.MATCH, LocalPurchasingProfile.PROGRESS,
        LocalPurchasingProfile.ORDER_MATCHES, LocalPurchasingProfile.ORDER_BILLED, LocalPurchasingProfile.RECEIPT_BILLED, LocalPurchasingProfile.PAYABLE)
    override val claimNamespaces: Set<String> = setOf(LocalPurchasingProfile.DOCUMENT_CLAIM)
    override fun normalize(input: StoredBytes): StoredBytes = try { purchasingNormalize(key, input) }
        catch (_: ArithmeticException) { throw IllegalArgumentException("Unsupported captured match precision or range") }

    override fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean =
        profile == LocalMasterRpcWorkflowProfile.INVOICE_MATCHING && normalize(normalized) == normalized

    /** Disclosure only: derive the complete actual immutable claim, never the selected
     * increment/client amount, and never re-evaluate or mutate the original command. */
    override suspend fun rpcRetainedResultRequirements(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes,
        view: CommandReadView, context: CommandContext): List<AuthorizationRequirement>? {
        check(supportsRpcWorkflow(profile, normalized) && context.scope == view.scope && context.key == key &&
            semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        val request = LocalPurchasingCodec.decodeCapturedMatch(normalized)
        val reader = PurchasingReader(view)
        val row = reader.row(LocalPurchasingProfile.INVOICE, request.invoiceId) ?: return null
        if (row.value.body !is PurchasingBody.InvoiceCapture) return null
        val original = try { reader.capturedInvoiceOriginal(request.invoiceId) }
            catch (_: PurchasingHydrationCapacityUnavailable) { return null }
            ?: return null
        val claim = purchasingInvoiceClaim(original.invoice)
        return listOf(purchasingCapturedMatchRequirementV2(context.scope, claim.id, original.invoice.row.revision,
            context.operationId, context.semanticDigest, request.reasonCode, claim.total()))
    }

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        check(context.scope == view.scope && context.key == key && semanticDigest(view.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        val request = LocalPurchasingCodec.decodeCapturedMatch(normalized)
        val reader = PurchasingReader(view)
        val selected = reader.row(LocalPurchasingProfile.INVOICE, request.invoiceId)
        if (selected?.value?.body !is PurchasingBody.InvoiceCapture) {
            // Actual absence/unsupported format cannot supply a mandatory real captured
            // claim amount. No fabricated zero or financial refusal is retained.
            if (selected == null) check(reader.invoice(request.invoiceId) == null) else reader.invoiceDocument(request.invoiceId)
            return unavailableCapturedMatchV2(context, request)
        }
        val selection = request.sourceQuery()
        val baseResources = purchasingCapturedMatchResourcesV2(selected, selection) + supplierSettlementResource(request.matchId)
        return try {
            val cut = purchasingCapturedMatchCutV2(reader, selection, request.matchId,
                { receiptId, lineId -> stock.purchaseReceiptFacts(view, receiptId, lineId) },
                { invoice -> requirePurchasingCrossAccounts(reader, invoice, stock, view) })
            preparedCapturedMatchV2(context, request, selected, cut.resources) { authorization, at ->
                val slice = purchasingCapturedMatchSliceV2(selection, cut, request)
                reader.requireSupplierHydrationCapacity()
                purchasingCapturedMatchEffectsV2(request, cut, slice) { kind, id, before, body ->
                    purchasingChange(context, kind, id, before, body, at, authorization.policyRevision)
                }
            }
        } catch (_: PurchasingHydrationCapacityUnavailable) {
            preparedCapturedMatchV2(context, request, selected, baseResources) { _, _ ->
                purchasingCapturedMatchRefusalV2("purchasing.capacity_unavailable", request, selected)
            }
        } catch (_: LocalStockHydrationCapacityUnavailable) {
            preparedCapturedMatchV2(context, request, selected, baseResources) { _, _ ->
                purchasingCapturedMatchRefusalV2("purchasing.capacity_unavailable", request, selected)
            }
        }
    }
}

private fun preparedCapturedMatchV2(context: CommandContext, request: LocalMatchCapturedSupplierInvoiceV2,
    invoice: PurchasingRow, resources: Set<RecoveryResourceKey>,
    block: (AuthorizedAction, UtcInstant) -> ProposedOutcome,
): PreparedMutation = object : PreparedMutation() {
    override val requirement: AuthorizationRequirement = purchasingCapturedMatchRequirementV2(context.scope, invoice.value.id,
        invoice.row.revision, context.operationId, context.semanticDigest, request.reasonCode, purchasingInvoiceClaim(invoice).total())
    override val allowsActionApproval: Boolean = false
    override val affectedResources: Set<RecoveryResourceKey> = resources + RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, context.originalActorId)
    override val recoveryHoldBarrierKinds: Set<RecoveryResourceKind> = purchasingRecoveryBarriers
    override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
        check(authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE &&
            authorization.isBoundTo(requirement, context.originalActorId) && authorization.approvalId == null)
        return try { block(authorization, recordedAt) }
            catch (refusal: PurchaseRejected) { purchasingCapturedMatchRefusalV2(refusal.code, request, invoice) }
    }
}

private fun unavailableCapturedMatchV2(context: CommandContext, request: LocalMatchCapturedSupplierInvoiceV2): PreparedMutation = object : PreparedMutation() {
    override val requirement: AuthorizationRequirement = AuthorizationRequirement(Permission(SecurityAction.PURCHASING_READ, PermissionVerb.PERFORM),
        context.scope, ActionBinding(request.invoiceId, request.expectedInvoiceRevision, context.semanticDigest, context.operationId, request.reasonCode))
    override val allowsActionApproval: Boolean = false
    override val affectedResources: Set<RecoveryResourceKey> = setOf(supplierSettlementResource(request.invoiceId),
        supplierSettlementResource(request.matchId), RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, context.originalActorId))
    override val recoveryHoldBarrierKinds: Set<RecoveryResourceKind> = purchasingRecoveryBarriers
    override val admissionRestriction: PreparedMutationAdmissionRestriction = PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE
    override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome =
        error("Unavailable captured match source must defer before evaluation")
}
