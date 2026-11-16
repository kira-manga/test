package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.*
import com.ukkera.pos.sales.CashSessionStatus
import com.ukkera.pos.sales.supplierCashAuthorizationRequirement
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** An inert deterministic first refusal, derived from complete genuine BEFORE sources.
 * No handler, PreparedMutation, evaluate, current mutable view or grant is used. Each
 * reader below is fresh exactly where installed Apply starts a fresh hydration envelope;
 * reusing a favorable prefix or merging their budgets would prove a different command. */
internal class SupplierCashApplicationFirstRefusal private constructor(private val before: SupplierCashApplicationBefore) {
    constructor(command: RuntimeHistoricalCommand) : this(SupplierHistoryApplicationBefore(command))
    constructor(sources: RuntimeHistoricalFinancialSources, view: CommandReadView) : this(SupplierRetainedApplicationBefore(sources, view))

    init {
        check(before.command in setOf(LocalSupplierCashProfile.COMMAND, LocalSupplierCashSupervisorCodec.COMMAND) &&
            semanticDigest(before.scope, before.actorId, before.command, before.normalized) == before.digest)
    }

    internal data class Proof(val fact: LocalSupplierCashStoredFact, val result: LocalSupplierCashResult,
        val requirement: AuthorizationRequirement, val resources: Set<RecoveryResourceKey>)

    suspend fun prove(operationId: OperationId, phase: LocalSupplierCashPhase): Proof? {
        when (val request = supplierCashApplicationRequest(before.command, before.normalized)) {
            is LocalSupplierCashRequest.ApplyOriginal -> check(phase == LocalSupplierCashPhase.ORIGINAL && request.recordingOperationId == operationId)
            is LocalSupplierCashRequest.ApplyResolution -> check(phase == LocalSupplierCashPhase.RESOLUTION && request.resolutionOperationId == operationId)
            else -> error("purchasing.supplier_cash_application_first_refusal_only")
        }
        val physical = before.physical(operationId, phase) ?: return null
        val slot = physical.slot.value
        val target = slot.target
        check(target.factOperation(phase) == operationId && before.operationId !in setOf(
            target.intentOperationId, target.recordingOperationId, target.resolutionOperationId))
        val resolve = before.command == LocalSupplierCashSupervisorCodec.COMMAND || phase == LocalSupplierCashPhase.RESOLUTION
        check(resolve || slot.originalActorId == before.actorId && physical.value.recordingActorId == before.actorId)
        val base = slot.domain.financialResources.toSet() + LocalSupplierCashJournal.authorityResources(slot.requirement()) +
            RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, before.actorId)
        val requirement = supplierCashAuthorizationRequirement(before.scope,
            supplierCashCommitment(slot.domain.source, target.settlementId, slot.domain.amount), before.operationId,
            before.digest, slot.domain.reasonCode, slot.originalActorId, resolve)
        // Installed Apply does this capture qualifier BEFORE rich fact/AlreadyApplied.
        // An unqualified capture is admission-deferred, never a durable Pending original.
        val capture = before.reader()
        val selected = target.invoiceId?.let { capture.row(LocalPurchasingProfile.INVOICE, it) }
        if (selected?.value?.body is PurchasingBody.InvoiceCapture) {
            val cut = try { capture.invoice(selected.row.key.id) }
                catch (_: PurchasingInvoiceCaptureUnqualified) { null }
                catch (_: PurchasingHydrationCapacityUnavailable) { null }
            if (cut == null || !purchasingCaptureSettlementEligible(cut)) return null
        }
        val fact = try { before.fact(operationId, phase, before.reader()) ?: return null }
            catch (_: PurchasingInvoiceCaptureUnqualified) { return null }
        fun proof(result: LocalSupplierCashResult, resources: Set<RecoveryResourceKey> = base) =
            Proof(fact, result, requirement, resources)
        fun pending(reason: LocalSupplierCashPendingReason, extra: Set<RecoveryResourceKey> = emptySet()) =
            proof(LocalSupplierCashResult.Pending(target, reason, phase), base + extra)
        fact.application.applicationOperationId?.let { return proof(LocalSupplierCashResult.AlreadyApplied(target, it, phase)) }
        val resolution = phase == LocalSupplierCashPhase.RESOLUTION
        val parent = if (resolution) {
            try { before.fact(target.recordingOperationId, LocalSupplierCashPhase.ORIGINAL, before.reader())
                ?: return pending(LocalSupplierCashPendingReason.ORIGINAL_APPLICATION_REQUIRED) }
            catch (_: PurchasingInvoiceCaptureUnqualified) { return null }
        } else fact
        if (resolution && parent.application.result?.let {
                it is LocalSupplierCashResult.Applied && it.action == LocalSupplierCashAction.UNKNOWN } != true)
            return pending(LocalSupplierCashPendingReason.ORIGINAL_APPLICATION_REQUIRED)
        val action = supplierCashKnownAction(fact.outcome(), slot.domain.amount)
            ?: return pending(LocalSupplierCashPendingReason.VALUE_OR_TENDER_REQUIRES_RECONCILIATION)
        try {
            val reader = before.reader()
            val supplier = before.supplier(target.supplierId)
                ?: return pending(LocalSupplierCashPendingReason.SOURCE_UNAVAILABLE)
            val cut = target.invoiceId?.let { reader.invoice(it) }
            if (target.invoiceId != null && cut == null) return pending(LocalSupplierCashPendingReason.SOURCE_UNAVAILABLE)
            val attempt = reader.supplierAttempt(target.settlementId)
                ?: return pending(LocalSupplierCashPendingReason.SOURCE_UNAVAILABLE)
            val value = (attempt.value.body as PurchasingBody.SettlementAttempt).value
            val cash = before.cash(target.sessionId)
            val extra = supplierSettlementResources(supplier, cut, target, cash)
            // Keep the installed short-circuit order, including exact absent observations.
            if (value.target != target || value.slotDigest != fact.value.slotDigest || value.amount != slot.domain.amount ||
                value.source != slot.domain.source || value.basisBefore.digest() != slot.domain.basisDigest ||
                cash.session.status != CashSessionStatus.OPEN || cash.session.custodianId != slot.originalActorId ||
                target.attemptId !in cash.session.pendingAttemptIds || cash.session.reservedCashOut.minor < value.amount.minor ||
                reader.observedClaim(LocalSupplierCashStorage.sourceClaim(target.attemptId)) != target.sessionId ||
                reader.observedAggregate(LocalSupplierCashStorage.movementKey(target.attemptId)) != null ||
                (if (resolution) value.status != PurchasingSupplierAttemptStatus.OUTCOME_UNKNOWN || value.originalBodyDigest != parent.value.bodyDigest
                    else value.status != PurchasingSupplierAttemptStatus.AWAITING_ATTESTATION))
                return pending(LocalSupplierCashPendingReason.SOURCE_OR_CUSTODY_CHANGED, extra)
            if (cut != null && (cut.settlements.reservedTotal.minor < value.amount.minor ||
                    checkNotNull(purchasingDue(cut)).remainingDue.minor < value.amount.minor))
                return pending(LocalSupplierCashPendingReason.SOURCE_OR_CUSTODY_CHANGED, extra)
            if (cut != null) requireSupplierInvoiceCapacity(cut, applying = value, action = action)
            if (reader.row(LocalSupplierSettlementProfile.EVENT, DomainId(before.operationId.value)) != null ||
                reader.row(LocalSupplierSettlementProfile.PAYMENT, target.settlementId) != null ||
                target.allocationId?.let { reader.row(LocalSupplierSettlementProfile.ALLOCATION, it) != null } == true)
                return pending(LocalSupplierCashPendingReason.SOURCE_OR_CUSTODY_CHANGED, extra)
            reader.requireSupplierHydrationCapacity(applying = value, action = action)
            return null // Every deterministic read passed; a Pending result cannot fit.
        } catch (_: PurchasingHydrationCapacityUnavailable) {
            return pending(LocalSupplierCashPendingReason.STORAGE_CAPACITY_REQUIRED)
        } catch (_: PurchasingInvoiceCaptureUnqualified) {
            return null // Existing capture admission deferral is never a recorded refusal.
        } catch (_: LocalSupplierCashSourceUnavailable) {
            return pending(LocalSupplierCashPendingReason.SOURCE_UNAVAILABLE)
        } catch (_: PurchaseRejected) {
            return pending(LocalSupplierCashPendingReason.STORAGE_CAPACITY_REQUIRED)
        }
    }
}

/** Closed actual source owners only. No caller-implemented source or historical
 * CommandReadView exists; immutable originals retain their genuine native owners. */
private sealed interface SupplierCashApplicationBefore {
    val scope: BranchScope
    val command: CommandKey
    val operationId: OperationId
    val actorId: DomainId
    val digest: PayloadDigest
    val normalized: StoredBytes
    fun reader(): PurchasingReader
    suspend fun physical(id: OperationId, phase: LocalSupplierCashPhase): LocalSupplierCashStoredFact?
    suspend fun fact(id: OperationId, phase: LocalSupplierCashPhase, reader: PurchasingReader): LocalSupplierCashStoredFact?
    suspend fun supplier(id: DomainId): PurchasingSupplierCut?
    suspend fun cash(id: DomainId): LocalSupplierCashCut
}

private class SupplierHistoryApplicationBefore(private val original: RuntimeHistoricalCommand) : SupplierCashApplicationBefore {
    override val scope get() = original.scope
    override val command get() = original.command
    override val operationId get() = original.operationId
    override val actorId get() = original.actorId
    override val digest get() = original.semanticDigest
    override val normalized get() = original.normalized
    override fun reader() = PurchasingReader(original)
    override suspend fun physical(id: OperationId, phase: LocalSupplierCashPhase) = LocalSupplierCashFacts.fact(original, id, phase)
    override suspend fun fact(id: OperationId, phase: LocalSupplierCashPhase, reader: PurchasingReader) = supplierCashFact(original, id, phase, reader)
    override suspend fun supplier(id: DomainId) = supplierSettlementSupplier(original, id)
    override suspend fun cash(id: DomainId) = LocalSupplierCashStorage.historicalApplication(original, id)
}

private class SupplierRetainedApplicationBefore(private val sources: RuntimeHistoricalFinancialSources,
    private val originalLoan: CommandReadView) : SupplierCashApplicationBefore {
    override val scope get() = sources.evidence.scope
    override val command get() = sources.evidence.command
    override val operationId get() = sources.evidence.operationId
    override val actorId get() = sources.evidence.originalActorId
    override val digest get() = sources.evidence.semanticDigest
    override val normalized get() = sources.evidence.normalized
    init { check(originalLoan.scope == scope) }
    override fun reader() = PurchasingReader(sources, originalLoan)
    override suspend fun physical(id: OperationId, phase: LocalSupplierCashPhase) = LocalSupplierCashFacts.fact(sources, originalLoan, id, phase)
    override suspend fun fact(id: OperationId, phase: LocalSupplierCashPhase, reader: PurchasingReader) = supplierCashFact(sources, originalLoan, id, phase, reader)
    override suspend fun supplier(id: DomainId): PurchasingSupplierCut? {
        val row = qualifiedSupplierFinancialSource(sources, originalLoan, id) ?: return null
        val record = decodeRecord(scope, row)
        val value = (record.body as ConfigurationBody.Supplier).value
        check(value.id == id)
        return PurchasingSupplierCut(row, value, record.authorizedBy)
    }
    override suspend fun cash(id: DomainId) = LocalSupplierCashStorage.historicalApplication(sources, id, originalLoan)
}
