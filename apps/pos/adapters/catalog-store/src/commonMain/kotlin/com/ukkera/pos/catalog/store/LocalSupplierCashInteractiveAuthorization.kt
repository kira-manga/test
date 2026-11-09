package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.sales.CashSessionStatus
import com.ukkera.pos.sales.supplierCashAuthorizationRequirement
import com.ukkera.pos.sales.store.*

/** Real same-handler one-shot current authorization; never reruns Start or manufactures custody. */
internal class LocalSupplierCashInteractiveAuthorization(private val stock: LocalStockRepository) : MasterPhysicalInteractiveAuthorizer {
    override val command: CommandKey = LocalSupplierCashProfile.COMMAND
    override val domainProfile: String = LocalSupplierCashProfile.DOMAIN
    override suspend fun prepareRetainedStartAuthorization(view: CommandReadView, context: PhysicalRetainedStartContext): PhysicalRetainedStartRequirement? = try {
        current(view, context)
    } catch (_: PurchasingHydrationCapacityUnavailable) { null }
      catch (_: PurchasingInvoiceCaptureUnqualified) { null }
      catch (_: LocalSupplierCashSourceUnavailable) { null }

    private suspend fun current(view: CommandReadView, context: PhysicalRetainedStartContext): PhysicalRetainedStartRequirement? {
        val original = context.original; val committed = context.committedStart
        if (original.scope != view.scope || original.key != command || committed.scope != view.scope || committed.command != command ||
            committed.domainProfile != domainProfile || committed.startKind != PhysicalRecordingStartKind.PAYMENT ||
            committed.operationId != original.operationId || committed.originalActorId != original.originalActorId || committed.semanticDigest != original.semanticDigest) return null
        val requirement = LocalSupplierCashPhysicalProjection.parse(context.requirement).requirement
        if (requirement.scope != view.scope || requirement.command != command || requirement.domainProfile != domainProfile ||
            requirement.intentOperationId != original.operationId || requirement.intentSemanticDigest != original.semanticDigest ||
            requirement.originalActorId != original.originalActorId) return null
        val slot = LocalSupplierCashFacts.slot(view, requirement.recordingOperationId) ?: return null
        val start = slot.start
        requirePurchasingProducer(start)
        if (slot.value.requirement().canonicalBytes != context.requirement || start.nativeOriginalProvenance != null ||
            start.resultHash != committed.resultDigest || start.recordedAt != committed.recordedAt || start.sequence != committed.sequence ||
            start.journalHash != committed.journalHash || start.assignmentId != committed.authority.assignmentId ||
            start.authorityEpoch != committed.authority.epoch || committed.authority != slot.value.domain.authority) return null
        val target = slot.value.target
        for (operation in listOf(target.recordingOperationId, target.resolutionOperationId))
            if (view.aggregate(LocalSupplierCashJournal.applicationKey(operation)) != null || view.retainedCommand(operation) != null) return null
        if (view.aggregate(LocalSupplierCashJournal.rawKey(target.recordingOperationId)) != null ||
            view.aggregate(LocalSupplierCashJournal.resolutionKey(target.resolutionOperationId)) != null) return null
        val reader = PurchasingReader(view)
        val supplier = supplierSettlementSupplier(view, target.supplierId) ?: return null
        if (!supplier.value.active) return null
        val cut = target.invoiceId?.let { reader.invoice(it) }
        if (target.invoiceId != null && cut == null) return null
        if (cut != null && !purchasingCaptureSettlementEligible(cut)) return null
        if (cut != null) requirePurchasingCrossAccounts(reader, cut, stock, view)
        val attempt = reader.supplierAttempt(target.settlementId) ?: return null
        val value = (attempt.value.body as PurchasingBody.SettlementAttempt).value
        val cash = LocalSupplierCashStorage.current(view, target.sessionId)
        if (!supplierStartPostimage(start, attempt.row) || !supplierStartPostimage(start, cash.sessionRow) ||
            cash.session.status != CashSessionStatus.OPEN || cash.session.custodianId != slot.value.originalActorId ||
            target.attemptId !in cash.session.pendingAttemptIds || cash.session.reservedCashOut.minor < value.amount.minor ||
            value.status != PurchasingSupplierAttemptStatus.AWAITING_ATTESTATION || value.applicationOperationIds.isNotEmpty() ||
            value.basisBefore.digest() != slot.value.domain.basisDigest || PurchasingSource.from(supplier.row) != value.basisBefore.supplierSource ||
            value.basisBefore.capturedInvoice != (cut?.let { purchasingInvoiceClaim(it.invoice).captured } ?: false) ||
            view.claim(LocalSupplierCashStorage.sourceClaim(target.attemptId)) != target.sessionId ||
            view.aggregate(LocalSupplierCashStorage.movementKey(target.attemptId)) != null) return null
        if (cut != null && (cut.settlements.index == null || !supplierStartPostimage(start, cut.settlements.index.row) ||
            PurchasingSource.from(cut.invoice.row) != value.basisBefore.invoiceSource || PurchasingSource.from(cut.progress.row) != value.basisBefore.progressSource ||
            cut.payable?.let { PurchasingSource.from(it.row) } != value.basisBefore.payableSource ||
            cut.credits.index?.let { PurchasingSource.from(it.row) } != value.basisBefore.creditSource ||
            cut.settlements.allocatedTotal != value.basisBefore.allocated || cut.settlements.reservedTotal != value.basisBefore.reserved!! + value.amount)) return null
        for (row in cash.rows.filter { it.key != cash.sessionRow.key }) if (PurchasingSource.from(row) !in value.cashSourcesBefore) return null
        val financial = supplierSettlementResources(supplier, cut, target, cash)
        if (financial != slot.value.domain.financialResources.toSet()) return null
        return PhysicalRetainedStartRequirement(supplierCashAuthorizationRequirement(view.scope, value.commitment(), original.operationId,
            original.semanticDigest, slot.value.domain.reasonCode, slot.value.originalActorId), context.requirement,
            financial + LocalSupplierCashJournal.authorityResources(requirement), supplierCashBarriers)
    }
}

private fun supplierStartPostimage(original: RetainedCommandEvidence, row: StoredAggregate): Boolean =
    original.aggregateWrites.singleOrNull { it.key == row.key }?.let {
        it.storageGeneration == row.revision && it.schema == row.schema && it.payload == row.payload && it.payloadDigest == row.digest
    } == true
