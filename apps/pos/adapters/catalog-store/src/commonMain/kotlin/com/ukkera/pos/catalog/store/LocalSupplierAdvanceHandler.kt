package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.recovery.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** Additive real master services; no separate writer, cash pool, default grant or native gate. */
public class LocalSupplierSettlementServices(stock: LocalStockRepository,
    participants: TransactionRetailJournalParticipantsSource = UnavailableRetailJournalParticipantsSource) {
    public val commands: List<MasterCommandHandler> = listOf(LocalSupplierCashHandler(stock, participants), SupplierAdvanceAllocationHandler(stock))
    public val reads: List<MasterReadHandler> = listOf(SupplierSettlementSourceRead(stock), SupplierAdvanceRead(), SupplierCashRead(), SupplierOwnCashRead())
    public val history: RuntimeHistoryBusinessProducer = LocalSupplierSettlementHistoryProducer(stock)
    public val nativeOriginalImport: NativeOriginalImportInterpreter = LocalSupplierCashNativeOriginalImport()
}

internal class SupplierAdvanceAllocationHandler(private val stock: LocalStockRepository) : MasterCommandHandler {
    override val key: CommandKey = LocalSupplierSettlementProfile.ALLOCATE_ADVANCE
    override val writableKinds: Set<String> = setOf(LocalSupplierSettlementProfile.ALLOCATION, LocalSupplierSettlementProfile.EVENT,
        LocalSupplierSettlementProfile.INVOICE_SETTLEMENTS, LocalSupplierSettlementProfile.ADVANCE_ALLOCATIONS)
    override val claimNamespaces: Set<String> = setOf(LocalPurchasingProfile.DOCUMENT_CLAIM)
    override fun normalize(input: StoredBytes): StoredBytes = purchasingNormalize(key, input)
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        check(context.key == key && context.scope == view.scope && semanticDigest(view.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        val request = LocalSupplierSettlementCodec.allocation(normalized)
        val reader = PurchasingReader(view)
        val selected = reader.row(LocalPurchasingProfile.INVOICE, request.invoice.invoiceId) ?: run {
            check(reader.invoice(request.invoice.invoiceId) == null)
            return missingAdvanceAllocationInvoice(context, request)
        }
        if (selected.value.body is PurchasingBody.InvoiceCapture) {
            val captureCut = try { reader.invoice(request.invoice.invoiceId) }
                catch (_: PurchasingInvoiceCaptureUnqualified) { null }
                catch (_: PurchasingHydrationCapacityUnavailable) { null }
            if (captureCut == null || !purchasingCaptureSettlementEligible(captureCut))
                return unqualifiedCaptureFinancialDeferral(context, reader, selected,
                    request.invoice.expectedProgressRevision, request.reasonCode, setOf(supplierSettlementResource(request.advanceId),
                        supplierSettlementResource(request.allocationId), supplierSettlementResource(DomainId(context.operationId.value))))
        }
        val document = purchasingInvoiceClaim(selected)
        return boundedPurchasingPreparation(context, document.id, request.invoice.expectedProgressRevision, document.total(), request.reasonCode,
            setOf(supplierSettlementResource(document.id), supplierSettlementResource(document.supplierId), supplierSettlementResource(request.advanceId),
                supplierSettlementResource(request.allocationId))) {
            val cut = reader.invoice(document.id) ?: error("Purchasing advance invoice unavailable")
            requirePurchasingCrossAccounts(reader, cut, stock, view)
            val supplier = supplierSettlementSupplier(view, document.supplierId)
            val advance = reader.advance(request.advanceId)
            advance?.let { reader.observeSupplierAdvanceDependencies(it) }
            val unused = reader.row(LocalSupplierSettlementProfile.ALLOCATION, request.allocationId) == null &&
                reader.row(LocalSupplierSettlementProfile.EVENT, DomainId(context.operationId.value)) == null
            val resources = supplierAdvanceResources(cut, supplier, advance, request) + reader.supplierRecoveryResources
            preparedPurchasing(context, document.id, request.invoice.expectedProgressRevision, document.total(), request.reasonCode, resources) { auth, at ->
                val actualSupplier = supplier ?: throw PurchaseRejected("purchasing.supplier_required")
                val sourceAdvance = advance ?: throw PurchaseRejected("purchasing.advance_required")
                purchaseRequire(actualSupplier.value.active && request.invoice.supplierId == document.supplierId &&
                    (sourceAdvance.advance.value.body as PurchasingBody.SupplierAdvance).value.supplierId == document.supplierId,
                    "purchasing.advance_supplier_mismatch")
                purchaseRequire(cut.payable != null, "purchasing.recognized_payable_required")
                purchaseRequire(purchasingSupplierSelectionMatches(request.invoice, purchasingSupplierCashBasis(actualSupplier.row, cut)) &&
                    sourceAdvance.advance.row.revision == request.expectedAdvanceRevision && sourceAdvance.index.row.revision == request.expectedAdvanceAllocationRevision &&
                    sourceAdvance.unapplied == request.expectedUnappliedAdvance, "purchasing.stale_revision")
                purchaseRequire(unused, "purchasing.identity_already_used")
                purchaseRequire(request.amount.minor <= sourceAdvance.unapplied.minor, "purchasing.advance_unapplied_exceeded")
                purchaseRequire(request.amount.minor <= checkNotNull(purchasingAvailableToAllocate(cut)).minor, "purchasing.advance_due_exceeded")
                requireSupplierInvoiceCapacity(cut, addAllocation = true)
                purchaseRequire(sourceAdvance.allocations.size < LocalSupplierSettlementProfile.MAX_ENTRIES, "purchasing.capacity_required")
                reader.requireSupplierHydrationCapacity()
                supplierAdvanceAllocationEffects(SupplierSettlementWrites(context, at, auth), context.operationId, request, actualSupplier, cut, sourceAdvance)
            }
        }
    }
}

/** Diagnostic absence has purchasing-read authority, not invented money/approval authority. */
private fun missingAdvanceAllocationInvoice(context: CommandContext, request: LocalAllocateSupplierAdvanceV1): PreparedMutation = object : PreparedMutation() {
    override val requirement: AuthorizationRequirement = AuthorizationRequirement(Permission(SecurityAction.PURCHASING_READ, PermissionVerb.PERFORM),
        context.scope, ActionBinding(request.invoice.invoiceId, request.invoice.expectedInvoiceRevision, context.semanticDigest,
            context.operationId, request.reasonCode))
    override val allowsActionApproval: Boolean = false
    override val affectedResources: Set<RecoveryResourceKey> = setOf(supplierSettlementResource(request.invoice.invoiceId),
        supplierSettlementResource(request.advanceId), supplierSettlementResource(request.allocationId),
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, context.originalActorId))
    override val recoveryHoldBarrierKinds: Set<RecoveryResourceKind> = purchasingRecoveryBarriers
    override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
        check(authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE && authorization.approvalId == null &&
            authorization.isBoundTo(requirement, context.originalActorId))
        return ProposedOutcome.Reject("purchasing.invoice_required", purchasingResult("purchasing.invoice_required", request.invoice.invoiceId))
    }
}

internal fun supplierAdvanceResources(cut: PurchasingInvoiceCut, supplier: PurchasingSupplierCut?, advance: PurchasingAdvanceCut?,
    request: LocalAllocateSupplierAdvanceV1): Set<RecoveryResourceKey> {
    val invoice = purchasingInvoiceClaim(cut.invoice)
    val order = (cut.order.value.body as PurchasingBody.Order).value
    return purchasingResources(order) + setOf(supplierSettlementResource(invoice.id), supplierSettlementResource(invoice.supplierId),
        supplierSettlementResource(request.advanceId), supplierSettlementResource(request.allocationId),
        RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, invoice.sourceEvidenceId)) +
        listOfNotNull(supplier?.actorId, cut.invoice.value.producer.actorId, cut.order.value.producer.actorId,
            advance?.payment?.value?.producer?.actorId).map { RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it) }
}
