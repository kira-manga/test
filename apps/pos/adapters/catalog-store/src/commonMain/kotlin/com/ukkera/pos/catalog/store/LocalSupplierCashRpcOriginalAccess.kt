package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.sales.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** Closed immutable supplier accounting proof. Reads the actual native BEFORE cut through
 * the existing purchasing/physical/cash readers, and compares the entire original proposal.
 * It never prepares/evaluates a command, reconstructs a current view or issues a payout. */
internal suspend fun supplierCashRpcOriginalRequirements(normalized: StoredBytes, original: RetainedOriginalV2,
    view: CommandReadView, context: CommandContext, stock: LocalStockRepository): List<AuthorizationRequirement>? {
    val retained = (original as? RetainedOriginalV2.Legacy)?.evidence ?: return null
    if (view.scope != context.scope || context.key !in setOf(LocalSupplierCashProfile.COMMAND, LocalSupplierCashSupervisorCodec.COMMAND) ||
        retained.scope != context.scope || retained.operationId != context.operationId || retained.command != context.key ||
        retained.originalActorId != context.originalActorId || retained.normalized != normalized ||
        retained.semanticDigest != context.semanticDigest || retained.nativeOriginalProvenance != null ||
        semanticDigest(context.scope, context.originalActorId, context.key, normalized) != context.semanticDigest) return null
    return listOf(SupplierCashRpcOriginalProof(view, stock).require(retained))
}

internal class SupplierCashRpcOriginalProof(private val view: CommandReadView, private val stock: LocalStockRepository) {
    private val active = mutableSetOf<OperationId>()
    private val completed = mutableMapOf<OperationId, Pair<PayloadDigest, AuthorizationRequirement>>()
    private var bytes = 0L

    suspend fun require(original: RetainedCommandEvidence): AuthorizationRequirement {
        completed[original.operationId]?.let { check(it.first == original.journalHash); return it.second }
        check(active.size + completed.size < 256 && active.add(original.operationId))
        bytes += original.normalized.size + original.result.size + original.aggregateWrites.sumOf { it.payload.size.toLong() }
        check(bytes <= 16_777_216L)
        try {
            val sources = view.financialOriginalSources(original.operationId) ?: error("purchasing.supplier_cash_original_sources_required")
            bytes += sources.retainedDataBytes
            check(bytes <= 16_777_216L)
            val actual = sources.evidence
            check(actual.scope == original.scope && actual.operationId == original.operationId && actual.command == original.command &&
                actual.originalActorId == original.originalActorId && actual.semanticDigest == original.semanticDigest &&
                actual.normalized == original.normalized && actual.journalHash == original.journalHash &&
                actual.result == original.result && actual.resultHash == original.resultHash &&
                actual.committed == original.committed && actual.code == original.code)
            val review = if (original.command == LocalSupplierCashSupervisorCodec.COMMAND)
                LocalSupplierCashSupervisorCodec.decode(original.normalized) as? LocalSupplierCashSupervisorRequest.Review else null
            if (review != null) {
                check(!original.committed && original.code == SUPPLIER_SUPERVISOR_REVIEWED && original.aggregateWrites.isEmpty() &&
                    original.claimChanges.isEmpty() && original.jobs.isEmpty() && original.nativeOriginalProvenance == null &&
                    LocalSupplierCashSupervisorCodec.normalize(original.normalized) == original.normalized)
                val reader = PurchasingReader(sources, view)
                val parent = supplierCashFact(sources, view, review.originalRecordingOperationId, LocalSupplierCashPhase.ORIGINAL, reader)
                    ?: error("purchasing.supplier_cash_review_original_required")
                val resolutionOperation = parent.value.target.resolutionOperationId
                val resolution = if (sources.aggregateSource(LocalSupplierCashJournal.resolutionKey(resolutionOperation)) == null) {
                    check(sources.aggregateSource(LocalSupplierCashJournal.applicationKey(resolutionOperation)) == null)
                    null
                } else supplierCashFact(sources, view, resolutionOperation, LocalSupplierCashPhase.RESOLUTION, reader)
                    ?: error("purchasing.supplier_cash_review_resolution_required")
                for (fact in listOfNotNull(parent, resolution)) fact.application.applicationOperationId?.let {
                    require(view.retainedCommand(it) ?: error("purchasing.supplier_cash_review_application_required"))
                }
                val result = supplierCashSupervisorReview(parent, resolution)
                check(original.result == LocalSupplierCashSupervisorCodec.review(result))
                val expected = supplierCashSupervisorReviewRequirement(original.scope, original.operationId, original.semanticDigest, parent.slot.value)
                check(sources.requirement == expected)
                completed[original.operationId] = original.journalHash to expected
                return expected
            }
            val request = supplierCashApplicationRequest(original.command, original.normalized)
            if (request is LocalSupplierCashRequest.StartCash) {
                check(original.committed && original.code == "committed")
                requireSupplierSettlementProducer(original)
                val slot = LocalSupplierCashFacts.requireStart(original)
                val reader = PurchasingReader(sources, view)
                val supplierRow = qualifiedSupplierFinancialSource(sources, view, request.source.supplierId)
                    ?: error("purchasing.supplier_cash_original_supplier_required")
                val supplierRecord = decodeRecord(original.scope, supplierRow)
                val supplier = PurchasingSupplierCut(supplierRow, (supplierRecord.body as ConfigurationBody.Supplier).value, supplierRecord.authorizedBy)
                val cut = (request.source as? LocalSupplierCashSource.Invoice)?.let { reader.invoice(it.invoiceId) }
                check(supplier.value.active && (request.source !is LocalSupplierCashSource.Invoice || cut?.payable != null))
                if (cut != null) {
                    requirePurchasingCrossAccounts(reader, cut, stock, sources, view)
                    check(purchasingInvoiceClaim(cut.invoice).supplierId == request.source.supplierId && purchasingCaptureSettlementEligible(cut) &&
                        request.amount.minor <= checkNotNull(purchasingAvailableToAllocate(cut)).minor)
                    requireSupplierInvoiceCapacity(cut, addAttempt = true)
                }
                val basis = purchasingSupplierCashBasis(supplier.row, cut)
                check(purchasingSupplierSelectionMatches(request.source, basis) && slot.domain.basisDigest == basis.digest())
                val cash = LocalSupplierCashStorage.historical(sources, request.sessionId, view)
                check(cash.sessionRow.revision == request.expectedSessionRevision && cash.drawerRevision == request.expectedDrawerRevision &&
                    cash.custodianRevision == request.expectedCustodianRevision && cash.workRevision == request.expectedWorkRevision &&
                    cash.session.availableCash == request.expectedAvailableCash && cash.session.status == CashSessionStatus.OPEN &&
                    cash.session.custodianId == original.originalActorId)
                val commitment = supplierCashCommitment(request.source, request.settlementId, request.amount)
                val expected = supplierCashAuthorizationRequirement(original.scope, commitment, original.operationId,
                    original.semanticDigest, request.reasonCode, cash.session.custodianId)
                check(sources.requirement == expected)
                check(supplierNewIdentity(reader, original.operationId, request.settlementId, request.allocationId))
                for (key in LocalSupplierCashJournal.vacantKeys(slot.requirement())) check(sources.aggregateSource(key) == null)
                for (claim in LocalSupplierCashJournal.lineageClaims(slot.requirement())) check(sources.claim(claim.key) == null)
                check(sources.aggregateSource(LocalSupplierCashStorage.movementKey(request.attemptId)) == null &&
                    sources.claim(LocalSupplierCashStorage.sourceClaim(request.attemptId)) == null &&
                    sources.claim(LocalSalesStoreProfile.unique("payment_attempt", request.attemptId)) == null &&
                    sources.claim(LocalSalesStoreProfile.unique("refund_attempt", request.attemptId)) == null)
                reader.requireSupplierHydrationCapacity(newTarget = slot.target)
                val after = SupplierCashSettlements.historicalReserveImage(cash.session, commitment, original.originalActorId,
                    cash.assignments, request.attemptId)
                val native = original.aggregateWrites.filter { it.key.kind in LocalSupplierCashProfile.gatedKinds || it.key.kind == ReservedOperations.KIND }.map {
                    check(it.storageGeneration == Revision(1))
                    AggregateChange(it.key, null, it.schema, it.payload)
                }
                requireSupplierRpcEffects(original, supplierStartEffects(SupplierSettlementWrites(sources), original.operationId,
                    request, supplier, cut, cash, after, slot, native, LocalSupplierCashJournal.lineageClaims(slot.requirement())))
                completed[original.operationId] = original.journalHash to expected
                return expected
            }
            if (request is LocalSupplierCashRequest.RecordResolution) {
                check(original.committed && original.code == "committed" && original.operationId == request.target.resolutionOperationId)
                requireSupplierSettlementProducer(original)
                val reader = PurchasingReader(sources, view)
                val parent = supplierCashFact(sources, view, request.target.recordingOperationId, LocalSupplierCashPhase.ORIGINAL, reader)
                    ?: error("purchasing.supplier_cash_unknown_required")
                val slot = parent.slot.value
                check(slot.target == request.target && request.resolverActorId == original.originalActorId &&
                    parent.application.result?.let { it is LocalSupplierCashResult.Applied && it.action == LocalSupplierCashAction.UNKNOWN } == true)
                val parentOperation = checkNotNull(parent.application.applicationOperationId)
                require(view.retainedCommand(parentOperation) ?: error("purchasing.supplier_cash_unknown_application_required"))
                LocalSupplierCashResolutionDescriptor.create(slot.requirement(), parent.value.body(), original.originalActorId, original.normalized)
                LocalSupplierCashFacts.requireResolution(original, parent.slot.start, parent.original)
                check(sources.aggregateSource(LocalSupplierCashJournal.resolutionKey(original.operationId)) == null &&
                    sources.aggregateSource(LocalSupplierCashJournal.applicationKey(original.operationId)) == null)
                val expected = supplierCashAuthorizationRequirement(original.scope,
                    supplierCashCommitment(slot.domain.source, slot.target.settlementId, slot.domain.amount), original.operationId,
                    original.semanticDigest, slot.domain.reasonCode, slot.originalActorId, true)
                check(sources.requirement == expected)
                completed[original.operationId] = original.journalHash to expected
                return expected
            }
            val operation: OperationId
            val phase: LocalSupplierCashPhase
            when (val request = supplierCashApplicationRequest(original.command, original.normalized)) {
                is LocalSupplierCashRequest.ApplyOriginal -> { operation = request.recordingOperationId; phase = LocalSupplierCashPhase.ORIGINAL }
                is LocalSupplierCashRequest.ApplyResolution -> { operation = request.resolutionOperationId; phase = LocalSupplierCashPhase.RESOLUTION }
                else -> error("purchasing.supplier_cash_nonaccounting_original")
            }
            if (!original.committed) {
                check(original.aggregateWrites.isEmpty() && original.claimChanges.isEmpty() && original.jobs.isEmpty())
                val proof = SupplierCashApplicationFirstRefusal(sources, view).prove(operation, phase)
                    ?: error("purchasing.supplier_cash_actual_first_refusal_required")
                // The source-only read derives the exact installed ordering and hydration
                // budgets. Independently close each ancestor against ITS native BEFORE cut.
                require(proof.fact.slot.start)
                for (source in sources.beforeAggregateSources.filter { it.row.key.kind == LocalSupplierCashProfile.APPLICATION }) {
                    val previous = (source.original as? RetainedOriginalV2.Legacy)?.evidence
                        ?: error("purchasing.supplier_cash_application_original_required")
                    if (supplierCashApplicationRequest(previous.command, previous.normalized).let {
                            it is LocalSupplierCashRequest.ApplyOriginal || it is LocalSupplierCashRequest.ApplyResolution }) require(previous)
                }
                val code = when (proof.result) {
                    is LocalSupplierCashResult.AlreadyApplied -> "purchasing.supplier_cash_already_applied"
                    is LocalSupplierCashResult.Pending -> "purchasing.supplier_cash_pending"
                    else -> error("purchasing.supplier_cash_first_refusal_subtype_required")
                }
                check(original.code == code && original.result == LocalSupplierCashCodec.result(proof.result) &&
                    sources.requirement == proof.requirement)
                completed[original.operationId] = original.journalHash to proof.requirement
                return proof.requirement
            }
            // Each already applied ancestor is qualified against its OWN native BEFORE cut.
            for (source in sources.beforeAggregateSources.filter { it.row.key.kind == LocalSupplierCashProfile.APPLICATION }) {
                val previous = (source.original as? RetainedOriginalV2.Legacy)?.evidence ?: error("purchasing.supplier_cash_application_original_required")
                if (supplierCashApplicationRequest(previous.command, previous.normalized).let {
                        it is LocalSupplierCashRequest.ApplyOriginal || it is LocalSupplierCashRequest.ApplyResolution }) require(previous)
            }
            val reader = PurchasingReader(sources, view)
            val fact = supplierCashFact(sources, view, operation, phase, reader) ?: error("purchasing.supplier_cash_original_fact_required")
            val slot = fact.slot.value
            val target = slot.target
            val supervised = original.command == LocalSupplierCashSupervisorCodec.COMMAND
            check((supervised || original.originalActorId == slot.originalActorId && fact.value.recordingActorId == original.originalActorId) &&
                original.operationId !in setOf(target.intentOperationId, target.recordingOperationId, target.resolutionOperationId))
            val resolve = phase == LocalSupplierCashPhase.RESOLUTION
            val commitment = supplierCashCommitment(slot.domain.source, target.settlementId, slot.domain.amount)
            val expectedRequirement = supplierCashAuthorizationRequirement(original.scope, commitment, original.operationId,
                original.semanticDigest, slot.domain.reasonCode, slot.originalActorId, supervised || resolve)
            check(sources.requirement == expectedRequirement)
            check(original.committed)
            run {
                requireSupplierSettlementProducer(original)
                check(fact.application.applicationOperationId == null && fact.applicationRow.revision == Revision(1))
                val parent = if (resolve) checkNotNull(supplierCashFact(sources, view, target.recordingOperationId,
                    LocalSupplierCashPhase.ORIGINAL, reader)) else fact
                if (resolve) check(parent.application.result?.let { it is LocalSupplierCashResult.Applied && it.action == LocalSupplierCashAction.UNKNOWN } == true)
                val action = checkNotNull(supplierCashKnownAction(fact.outcome(), slot.domain.amount))
                val supplierRow = qualifiedSupplierFinancialSource(sources, view, target.supplierId)
                    ?: error("purchasing.supplier_cash_original_supplier_required")
                val supplierRecord = decodeRecord(original.scope, supplierRow)
                val supplier = PurchasingSupplierCut(supplierRow, (supplierRecord.body as ConfigurationBody.Supplier).value, supplierRecord.authorizedBy)
                val cut = target.invoiceId?.let { reader.invoice(it) }
                check(target.invoiceId == null || cut != null)
                val attempt = reader.supplierAttempt(target.settlementId) ?: error("purchasing.supplier_cash_original_attempt_required")
                val before = (attempt.value.body as PurchasingBody.SettlementAttempt).value
                val cash = LocalSupplierCashStorage.historical(sources, target.sessionId, view)
                check(before.target == target && before.slotDigest == fact.value.slotDigest && before.source == slot.domain.source &&
                    before.amount == slot.domain.amount && before.basisBefore.digest() == slot.domain.basisDigest &&
                    cash.session.status == CashSessionStatus.OPEN && cash.session.custodianId == slot.originalActorId &&
                    target.attemptId in cash.session.pendingAttemptIds && cash.session.reservedCashOut.minor >= before.amount.minor &&
                    sources.claim(LocalSupplierCashStorage.sourceClaim(target.attemptId)) == target.sessionId &&
                    sources.aggregateSource(LocalSupplierCashStorage.movementKey(target.attemptId)) == null)
                check(if (resolve) before.status == PurchasingSupplierAttemptStatus.OUTCOME_UNKNOWN && before.originalBodyDigest == parent.value.bodyDigest
                    else before.status == PurchasingSupplierAttemptStatus.AWAITING_ATTESTATION)
                if (cut != null) {
                    check(purchasingInvoiceClaim(cut.invoice).supplierId == target.supplierId && purchasingCaptureSettlementEligible(cut) &&
                        cut.settlements.reservedTotal.minor >= before.amount.minor && checkNotNull(purchasingDue(cut)).remainingDue.minor >= before.amount.minor)
                    requireSupplierInvoiceCapacity(cut, applying = before, action = action)
                }
                check(reader.row(LocalSupplierSettlementProfile.EVENT, DomainId(original.operationId.value)) == null &&
                    reader.row(LocalSupplierSettlementProfile.PAYMENT, target.settlementId) == null &&
                    (target.allocationId == null || reader.row(LocalSupplierSettlementProfile.ALLOCATION, checkNotNull(target.allocationId)) == null))
                reader.requireSupplierHydrationCapacity(applying = before, action = action)
                val changed = if (action == LocalSupplierCashAction.UNKNOWN) null
                else if (supervised) SupplierCashSettlements.historicalSupervisedFinishImage(cash.session, commitment, slot.originalActorId,
                    cash.assignments, target.attemptId, operation, fact.value.recordingActorId, original.operationId,
                    if (resolve) target.recordingOperationId else null, if (resolve) parent.value.bodyDigest else null,
                    action == LocalSupplierCashAction.CASH_PAID, supplierEvidence(fact.outcome()).supplierDomain(fact.value.recordingActorId), slot.domain.reasonCode)
                else SupplierCashSettlements.historicalFinishImage(cash.session, commitment, original.originalActorId, cash.assignments,
                    target.attemptId, original.operationId, action == LocalSupplierCashAction.CASH_PAID,
                    supplierEvidence(fact.outcome()).supplierDomain(original.originalActorId), slot.domain.reasonCode)
                val inputs = SupplierApplicationFact(slot, parent.value, fact.value, PurchasingSource.from(parent.row),
                    if (resolve) PurchasingSource.from(fact.row) else null, fact.application, fact.applicationRow.revision)
                val expected = supplierApplyEffects(SupplierSettlementWrites(sources), original.operationId, inputs, attempt, supplier, cut, cash, changed,
                    changed?.movement?.let { if (supervised) LocalSupplierCashStorage.retainedSupervisedMovementWrite(original, slot, fact.value,
                        if (resolve) parent.value else null, it) else LocalSupplierCashStorage.retainedMovementWrite(original, it) })
                check(original.code == "committed" && original.result == expected.result && original.jobs.isEmpty() &&
                    original.aggregateWrites.size == expected.changes.size && original.claimChanges.size == expected.claims.size)
                for (change in expected.changes) {
                    val write = original.aggregateWrites.single { it.key == change.key }
                    check(write.schema == change.schema && write.payload == change.payload && write.payloadDigest == sha256(change.payload.copyBytes()) &&
                        write.storageGeneration == (change.expectedRevision ?: Revision(0)).next())
                }
                for (change in expected.claims) {
                    val retained = original.claimChanges.single { it.key == change.key }
                    check(retained.ownerId == change.ownerId && retained.action ==
                        if (change is ClaimChange.Acquire) RetainedClaimAction.ACQUIRE else RetainedClaimAction.RELEASE)
                }
            }
            completed[original.operationId] = original.journalHash to expectedRequirement
            return expectedRequirement
        } finally { active.remove(original.operationId) }
    }
}

private fun requireSupplierRpcEffects(original: RetainedCommandEvidence, expected: ProposedOutcome.Apply) {
    check(original.committed && original.code == "committed" && original.result == expected.result && original.jobs.isEmpty() &&
        original.aggregateWrites.size == expected.changes.size && original.claimChanges.size == expected.claims.size)
    for (change in expected.changes) {
        val write = original.aggregateWrites.single { it.key == change.key }
        check(write.schema == change.schema && write.payload == change.payload && write.payloadDigest == sha256(change.payload.copyBytes()) &&
            write.storageGeneration == (change.expectedRevision ?: Revision(0)).next())
    }
    for (change in expected.claims) {
        val retained = original.claimChanges.single { it.key == change.key }
        check(retained.ownerId == change.ownerId && retained.action ==
            if (change is ClaimChange.Acquire) RetainedClaimAction.ACQUIRE else RetainedClaimAction.RELEASE)
    }
}
