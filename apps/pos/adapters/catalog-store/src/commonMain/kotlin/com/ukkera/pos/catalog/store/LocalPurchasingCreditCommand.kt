package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** Installed on the existing master writer; one atomic credit/index/claims/result/audit commit. */
internal class PurchasingCreditHandler(private val stock: LocalStockRepository) : MasterCommandHandler {
    override val key: CommandKey = LocalPurchasingProfile.RECORD_CREDIT
    override val writableKinds: Set<String> = setOf(LocalPurchasingProfile.CREDIT, LocalPurchasingProfile.INVOICE_CREDITS)
    override val claimNamespaces: Set<String> = setOf(LocalPurchasingProfile.DOCUMENT_CLAIM,
        LocalPurchasingProfile.REFERENCE_CLAIM, LocalPurchasingProfile.CREDIT_SOURCE_CLAIM)
    override fun normalize(input: StoredBytes): StoredBytes = purchasingNormalize(key, input)

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        check(context.scope == view.scope && context.key == key &&
            semanticDigest(view.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        val request = LocalPurchasingCodec.decodeCredit(normalized)
        val reader = PurchasingReader(view)
        val selected = reader.row(LocalPurchasingProfile.INVOICE, request.invoiceId)
        if (selected == null) {
            check(reader.invoice(request.invoiceId) == null)
            val previous = reader.row(LocalPurchasingProfile.CREDIT, request.creditId)?.value?.body as? PurchasingBody.Credit
            check(previous == null || previous.request.invoiceId != request.invoiceId) { "Purchasing credit lost its invoice" }
            view.claim(purchasingCreditSourceClaim(request.sourceEvidenceId))?.let { owner ->
                val credit = reader.row(LocalPurchasingProfile.CREDIT, owner)?.value?.body as? PurchasingBody.Credit
                    ?: error("Purchasing credit source lost its owner")
                check(credit.request.sourceEvidenceId == request.sourceEvidenceId && credit.request.invoiceId != request.invoiceId)
            }
            val reference = purchasingReferenceClaim(view.scope, "credit", request.supplierId, request.reference)
            view.claim(reference)?.let { owner ->
                val credit = reader.row(LocalPurchasingProfile.CREDIT, owner)?.value?.body as? PurchasingBody.Credit
                    ?: error("Purchasing credit reference lost its owner")
                check(credit.request.creditId == owner && credit.supplierId == request.supplierId &&
                    credit.request.supplierId == request.supplierId &&
                    credit.normalizedReference == normalizedPurchaseReference(request.reference) &&
                    purchasingReferenceClaim(view.scope, "credit", credit.supplierId, credit.request.reference) == reference)
                check(credit.request.invoiceId != request.invoiceId) { "Purchasing credit reference lost its invoice" }
            }
            return missingCreditInvoice(context, request)
        }
        if (selected.value.body is PurchasingBody.InvoiceCapture && reader.row(LocalPurchasingProfile.PROGRESS, request.invoiceId) == null) {
            val invoice = selected.value.body.value
            return unqualifiedCaptureFinancialDeferral(context, reader, selected, request.expectedProgressRevision, request.reasonCode, setOf(
                supplierSettlementResource(request.creditId),
                RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, request.sourceEvidenceId),
                supplierSettlementResource(purchasingReferenceResource(purchasingReferenceClaim(view.scope, "credit", invoice.supplierId, request.reference))),
                supplierSettlementResource(purchasingReferenceResource(purchasingCreditSourceClaim(request.sourceEvidenceId)))))
        }
        val selectedInvoice = purchasingInvoiceClaim(selected)
        val refusalResources = purchasingCreditResources(view.scope, selectedInvoice, request,
            sourceActors = listOf(selected.value.producer.actorId))
        // The observed immutable invoice establishes the real FULL-document approval amount,
        // including on capacity/unsupported refusals. A small credit cannot evade that limit.
        return boundedPurchasingPreparation(context, selectedInvoice.id, request.expectedProgressRevision,
            selectedInvoice.total(), request.reasonCode, refusalResources) {
            val cut = reader.invoice(request.invoiceId) ?: error("Purchasing invoice unavailable")
            val invoice = purchasingInvoiceClaim(cut.invoice)
            val order = (cut.order.value.body as PurchasingBody.Order).value
            requirePurchasingCrossAccounts(reader, cut, stock, view)
            val previous = reader.row(LocalPurchasingProfile.CREDIT, request.creditId)
            val reference = purchasingReferenceClaim(view.scope, "credit", invoice.supplierId, request.reference)
            val referenceOwner = view.claim(reference)
            if (referenceOwner != null) {
                val duplicate = reader.row(LocalPurchasingProfile.CREDIT, referenceOwner)?.value?.body as? PurchasingBody.Credit
                    ?: error("Purchasing credit reference lost its owner")
                check(purchasingReferenceClaim(view.scope, "credit", duplicate.supplierId, duplicate.request.reference) == reference)
            }
            val sourceOwner = view.claim(purchasingCreditSourceClaim(request.sourceEvidenceId))
            if (sourceOwner != null) {
                val duplicate = reader.row(LocalPurchasingProfile.CREDIT, sourceOwner)?.value?.body as? PurchasingBody.Credit
                    ?: error("Purchasing credit source lost its owner")
                check(duplicate.request.sourceEvidenceId == request.sourceEvidenceId)
            }
            val resources = purchasingCreditResources(view.scope, invoice, request, order,
                listOf(cut.order.value.producer.actorId, cut.invoice.value.producer.actorId)) + reader.supplierRecoveryResources
            preparedPurchasing(context, invoice.id, request.expectedProgressRevision, invoice.total(), request.reasonCode, resources) { auth, now ->
                purchaseRequire(request.kind == LocalSupplierAdjustmentKindV1.CREDIT, "purchasing.debit_unsupported")
                purchaseRequire(request.amount.currency == Currency.EGP && request.amount.minor > 0, "purchasing.credit_amount_unsupported")
                purchaseRequire(request.agreed, "purchasing.credit_agreement_required")
                purchaseRequire(request.supplierId == invoice.supplierId, "purchasing.credit_supplier_mismatch")
                purchaseRequire(previous == null, "purchasing.identity_already_used")
                purchaseRequire(referenceOwner == null, "purchasing.duplicate_credit_reference")
                purchaseRequire(sourceOwner == null, "purchasing.credit_source_already_used")
                purchaseRequire(cut.invoice.row.revision == request.expectedInvoiceRevision &&
                    cut.progress.row.revision == request.expectedProgressRevision && cut.payable?.row?.revision == request.expectedPayableRevision &&
                    cut.credits.index?.row?.revision == request.expectedCreditRevision && purchasingCreditSettlementCutMatches(request, cut),
                    "purchasing.stale_revision")
                val due = purchasingDue(cut) ?: throw PurchaseRejected("purchasing.recognized_payable_required")
                val available = checkNotNull(purchasingAvailableToAllocate(cut))
                purchaseRequire(request.expectedRemainingDue == due.remainingDue,
                    "purchasing.stale_revision")
                purchaseRequire(request.amount.minor <= available.minor, "purchasing.credit_excess_unsupported")
                purchaseRequire(cut.credits.entries.size < LocalPurchasingProfile.MAX_CREDITS, "purchasing.capacity_required")
                reader.requireSupplierHydrationCapacity()
                val nextRevision = (cut.credits.index?.row?.revision ?: Revision(0)).next()
                ProposedOutcome.Apply(purchasingCreditResult(request.creditId, nextRevision), listOf(
                    purchasingChange(context, LocalPurchasingProfile.CREDIT, request.creditId, null,
                        purchasingCreditBody(request, cut), now, auth.policyRevision),
                    purchasingChange(context, LocalPurchasingProfile.INVOICE_CREDITS, invoice.id, cut.credits.index,
                        purchasingNextCredits(request, cut), now, auth.policyRevision)),
                    purchasingCreditClaims(view.scope, invoice.supplierId, request, cut.credits.index == null))
            }
        }
    }
}

internal fun purchasingCreditResources(
    scope: BranchScope, invoice: PurchaseInvoiceV1, request: LocalRecordSupplierCreditV1,
    order: PurchaseOrderV1? = null, sourceActors: List<DomainId> = emptyList(),
): Set<RecoveryResourceKey> = purchasingCreditResources(scope, purchasingInvoiceClaim(invoice), request, order, sourceActors)

/** The original format is retained; a selected PO comes only from a real recognized cut. */
internal fun purchasingCreditResources(
    scope: BranchScope, invoice: PurchasingInvoiceClaim, request: LocalRecordSupplierCreditV1,
    order: PurchaseOrderV1? = null, sourceActors: List<DomainId> = emptyList(),
): Set<RecoveryResourceKey> = order?.let(::purchasingResources).orEmpty() + listOfNotNull(
    (order?.id ?: invoice.originalOrderId)?.let { RecoveryResourceKey(RecoveryResourceKind.PO_ALLOWANCE, it) },
    RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT, invoice.id),
    RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT, invoice.supplierId),
    RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT, request.creditId),
    RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, invoice.sourceEvidenceId),
    RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, request.sourceEvidenceId),
    RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT,
        purchasingReferenceResource(purchasingReferenceClaim(scope, "credit", invoice.supplierId, request.reference))),
    RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT, purchasingReferenceResource(purchasingCreditSourceClaim(request.sourceEvidenceId))),
).toSet() + sourceActors.map { RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it) }

/** All-absent diagnostic only: no invented invoice amount or financial authority. */
private fun missingCreditInvoice(context: CommandContext, request: LocalRecordSupplierCreditV1): PreparedMutation = object : PreparedMutation() {
    override val requirement: AuthorizationRequirement = AuthorizationRequirement(Permission(SecurityAction.PURCHASING_READ, PermissionVerb.PERFORM),
        context.scope, ActionBinding(request.invoiceId, request.expectedInvoiceRevision, context.semanticDigest, context.operationId, request.reasonCode))
    override val allowsActionApproval: Boolean = false
    override val affectedResources: Set<RecoveryResourceKey> = setOf(
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, context.originalActorId),
        RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT, request.invoiceId),
        RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT, request.creditId),
        RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, request.sourceEvidenceId))
    override val recoveryHoldBarrierKinds: Set<RecoveryResourceKind> = purchasingRecoveryBarriers
    override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
        check(authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE && authorization.isBoundTo(requirement, context.originalActorId) &&
            authorization.approvalId == null)
        return ProposedOutcome.Reject("purchasing.invoice_required", purchasingResult("purchasing.invoice_required", request.invoiceId))
    }
}
