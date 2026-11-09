package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** Typed SQL-only selector; no Start/physical request can enter accounting. */
internal data class FoodRefundApplySelection(val factOperationId: OperationId, val phase: LocalFoodRefundFactPhase)
internal fun foodRefundApplySelection(command: CommandKey, normalized: StoredBytes): FoodRefundApplySelection {
    check(command == LocalFoodRefundProfile.COMMAND)
    return when (val request = LocalFoodRefundCodec.decode(normalized)) {
        is LocalFoodRefundRequest.ApplyOriginal -> FoodRefundApplySelection(request.recordingOperationId, LocalFoodRefundFactPhase.ORIGINAL)
        is LocalFoodRefundRequest.ApplyResolution -> FoodRefundApplySelection(request.resolutionOperationId, LocalFoodRefundFactPhase.RESOLUTION)
        else -> error("food.refund.accounting_subtype")
    }
}

/** Actual immutable current cuts, never reconstructed originals, grants or native source rows. */
internal class FoodRefundApplicationFact(val original: FoodRefundRawCut, val resolution: FoodRefundResolutionCut? = null) {
    val slot: LocalFoodRefundSlot get() = original.slot.value
    val phase: LocalFoodRefundFactPhase get() = if (resolution == null) LocalFoodRefundFactPhase.ORIGINAL else LocalFoodRefundFactPhase.RESOLUTION
    val application: LocalSalesRow<LocalFoodRefundApplication> get() = resolution?.application ?: original.application
    val recorderId: DomainId get() = resolution?.raw?.value?.recordingActorId ?: original.raw.value.recordingActorId
    val outcome: LocalFoodRefundOutcome get() = if (resolution == null)
        (LocalFoodRefundCodec.decode(original.raw.value.body()) as LocalFoodRefundRequest.RecordOriginal).outcome
        else (LocalFoodRefundCodec.decode(resolution.raw.value.body()) as LocalFoodRefundRequest.RecordResolution).outcome
    val originalBodyDigest: PayloadDigest get() = original.raw.value.bodyDigest
    val resolutionBodyDigest: PayloadDigest? get() = resolution?.raw?.value?.bodyDigest
    fun initialApplication(): LocalFoodRefundApplication = resolution?.let { foodRefundInitialApplication(it.raw.value) }
        ?: foodRefundInitialApplication(original.raw.value)
}

/** SQL-only Apply. Whole raw/UNKNOWN/Start/native custody precedes the nonstock accounting cut. */
internal suspend fun prepareFoodRefundApplication(operationId: OperationId, phase: LocalFoodRefundFactPhase,
    borrowed: CommandReadView, context: CommandContext, stock: LocalStockRepository): PreparedMutation {
    run {
        val body = if (phase == LocalFoodRefundFactPhase.ORIGINAL) LocalFoodRefundRequest.ApplyOriginal(operationId)
            else LocalFoodRefundRequest.ApplyResolution(operationId)
        check(context.key == LocalFoodRefundProfile.COMMAND && operationId != context.operationId &&
            semanticDigest(context.scope, context.originalActorId, context.key, LocalFoodRefundCodec.encode(body)) == context.semanticDigest)
    }
    val rawKey = if (phase == LocalFoodRefundFactPhase.ORIGINAL) foodRefundRawKey(operationId) else foodRefundResolutionKey(operationId)
    val headerRow = borrowed.aggregate(rawKey) ?: return foodRefundUnavailablePreparation(context, DomainId(operationId.value))
    val owner: DomainId
    val recorder: DomainId
    if (phase == LocalFoodRefundFactPhase.ORIGINAL) {
        val header = localSalesDecode(headerRow.payload, LocalFoodRefundRaw.serializer(), LocalFoodRefundProfile.MAX_RAW_BYTES)
        owner = header.originalActorId; recorder = header.recordingActorId
    } else {
        val header = localSalesDecode(headerRow.payload, LocalFoodRefundResolutionRaw.serializer(), LocalFoodRefundProfile.MAX_RAW_BYTES)
        owner = header.originalActorId; recorder = header.recordingActorId
    }
    // Denial-only header before rich reads. Foreign recorder/custody remains explicitly held.
    if (owner != context.originalActorId || recorder != context.originalActorId)
        return foodRefundUnavailablePreparation(context, DomainId(operationId.value))
    val originalView = FoodReadView(commerceLoan(borrowed, stock))
    val fact = if (phase == LocalFoodRefundFactPhase.ORIGINAL)
        FoodRefundApplicationFact(readFoodRefundRaw(originalView, operationId, stock))
        else readFoodRefundResolution(originalView, operationId, stock).let { FoodRefundApplicationFact(it.original, it) }
    val slot = fact.slot
    check(slot.target.factOperation(phase) == operationId && owner == slot.originalActorId && recorder == fact.recorderId &&
        context.operationId !in setOf(slot.target.intentOperationId, slot.target.recordingOperationId, slot.target.resolutionOperationId))
    val resolve = phase == LocalFoodRefundFactPhase.RESOLUTION
    val authorityResources = slot.domain.financialResources.toSet() + foodRefundAuthorityResources(slot.requirement()) + recordingAuthority(fact.recorderId)
    val pendingAction = if (resolve) SecurityAction.REFUND_RESOLVE else SecurityAction.RETURN_RECEIPT_LOOKUP
    fun pending(reason: LocalFoodRefundPendingReason, resources: Set<RecoveryResourceKey> = authorityResources): PreparedMutation =
        FoodRefundPrepared(context, pendingAction, DomainId(operationId.value), fact.application.value.revision,
            slot.originalActorId, "food.refund.pending", if (resolve) slot.domain.amount else null, resources,
            foodRefundApplicationBarriers, allowsActionApproval = resolve) { _, _ -> foodRefundPending(slot.target, reason, phase) }
    if (fact.application.value.state == LocalFoodRefundApplicationState.APPLIED)
        return FoodRefundPrepared(context, pendingAction, DomainId(operationId.value), fact.application.value.revision,
            slot.originalActorId, "food.refund.already_applied", if (resolve) slot.domain.amount else null, authorityResources,
            foodRefundApplicationBarriers, allowsActionApproval = resolve) { _, _ -> ProposedOutcome.Reject("food.refund.already_applied",
                LocalFoodRefundCodec.result(LocalFoodRefundResult.AlreadyApplied(operationId,
                    checkNotNull(fact.application.value.lastApplicationOperationId), phase))) }
    if (phase == LocalFoodRefundFactPhase.RESOLUTION && (fact.original.application.value.state != LocalFoodRefundApplicationState.APPLIED ||
            fact.original.application.value.action != LocalFoodRefundAction.UNKNOWN))
        return pending(LocalFoodRefundPendingReason.ORIGINAL_APPLICATION_REQUIRED)
    val outcome = fact.outcome
    if (outcome is LocalFoodRefundOutcome.ExternalAttested || outcome is LocalFoodRefundOutcome.CashPaid &&
        outcome.amount != slot.domain.amount) return pending(LocalFoodRefundPendingReason.VALUE_OR_TENDER_MISMATCH)
    val current = foodFinancialObserve(borrowed, LocalFoodFinancialProfileV1.REFUND, slot.target.refundId)?.second
        ?.let(::foodFinancialRefund) ?: return pending(LocalFoodRefundPendingReason.SOURCE_UNAVAILABLE)
    if (current.orderId != slot.target.orderId || current.originalCollection.id != slot.target.collectionId || current.saleId != slot.target.saleId)
        return pending(LocalFoodRefundPendingReason.CURRENT_CUSTODY_REQUIRES_RECONCILIATION)
    val view = FoodReadView(commerceLoan(borrowed, stock))
    val capture = view.requireStockRelational().beginReadEvidence(context)
    var sealed = false
    suspend fun hold(reason: LocalFoodRefundPendingReason, resources: Set<RecoveryResourceKey> = authorityResources): PreparedMutation {
        if (!sealed) capture.discard()
        return pending(reason, resources)
    }
    try {
        val cut = foodRefundCut(view, slot.target.refundId)
        val cash = LocalSalesStorage.cash(view, slot.target.sessionId)
        val attempt = cut.value.attempts.singleOrNull { it.id == slot.target.attemptId }
        val resources = authorityResources + foodRefundBasisResources(cut.value) + cashResources(cash)
        if (cash.session.value.status != CashSessionStatus.OPEN || cash.session.value.custodianId != slot.originalActorId)
            return hold(LocalFoodRefundPendingReason.CURRENT_CUSTODY_REQUIRES_RECONCILIATION, resources)
        if (attempt == null || !foodRefundApplicationAttemptMatches(cut.value, cash.session.value, slot, phase,
                if (phase == LocalFoodRefundFactPhase.RESOLUTION) fact.original.raw.value else null))
            return hold(LocalFoodRefundPendingReason.ORIGINAL_ATTEMPT_MISMATCH, resources)
        if (view.claim(LocalSalesStoreProfile.unique("refund_record", attempt.id)) != null ||
            view.claim(CashNonSaleStoreProfile.cashSourceClaim(attempt.id)) != null ||
            view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.MOVEMENT_KIND, attempt.id)) != null)
            return hold(LocalFoodRefundPendingReason.CURRENT_CUSTODY_REQUIRES_RECONCILIATION, resources)
        val inputs = cut.inputs
        val cashInput = foodFinancialCashInput(cash)
        val sources = FoodRefundSources(capture.observations())
        val literal = sources.refund(inputs, cashInput)
        requireFoodRefundApplicationCut(literal, slot, phase,
            if (phase == LocalFoodRefundFactPhase.RESOLUTION) fact.original.raw.value else null)
        sources.applicationVacancy(attempt.id); sources.finish()
        val allResources = authorityResources + literal.financialResources
        if (allResources.size > 512) return hold(LocalFoodRefundPendingReason.STORAGE_CAPACITY_REQUIRED, allResources)
        val evidence = capture.finish(); sealed = true
        return FoodRefundPrepared(context, if (resolve) SecurityAction.REFUND_RESOLVE else SecurityAction.REFUND_RECORD,
            cut.value.id, cut.value.revision, slot.originalActorId, "food.refund.apply", slot.domain.amount, allResources,
            foodRefundApplicationBarriers, allowsActionApproval = resolve) { command, at ->
            try {
                val observedOutcome = foodRefundOutcome(outcome, fact.recorderId)
                val changed = FoodRefunds.applyObservation(command, cut.value, cash.session.value,
                    attempt.id, operationId, observedOutcome)
                val financialRevisions = listOf(LocalFoodStorageRevisionV1(LocalFoodFinancialProfileV1.REFUND, cut.value.id, cut.refund.row.revision.next())) +
                    listOfNotNull(if (changed.session === cash.session.value) null else LocalFoodStorageRevisionV1(CashNonSaleStoreProfile.SESSION_KIND,
                        changed.session.id, cash.session.stored.revision.next()))
                val application = fact.initialApplication().copy(revision = Revision(1), state = LocalFoodRefundApplicationState.APPLIED,
                    reason = null, updatedAt = at, lastApplicationOperationId = context.operationId,
                    action = foodRefundAction(outcome), financialRevisions = financialRevisions)
                val result = LocalFoodRefundResult.Applied(slot.target, checkNotNull(application.action), context.operationId,
                    financialRevisions + LocalFoodStorageRevisionV1(LocalFoodRefundProfile.APPLICATION, DomainId(operationId.value), Revision(2)), phase)
                val extra = listOfNotNull(if (changed.session === cash.session.value) null else foodPaymentCashWrite(changed.session, cashInput.storageRevision),
                    changed.movement?.let { movementWrite(context, it, at) }) + foodRefundApplicationWrite(application, Revision(1))
                val claims = listOfNotNull(changed.record?.let { ClaimChange.Acquire(LocalSalesStoreProfile.unique("refund_record", it.attemptId), cut.value.id) },
                    changed.movement?.let { ClaimChange.Acquire(CashNonSaleStoreProfile.cashSourceClaim(it.sourceId), cash.session.value.id) })
                val writes = FoodRefundWrites(context, at, inputs, cashInput)
                writes.refund(changed.refund)
                writes.finish(result, extra, claims, evidence, slotDigest = fact.original.raw.value.slotDigest,
                    originalBodyDigest = fact.originalBodyDigest, resolutionBodyDigest = fact.resolutionBodyDigest,
                    originalUnknownApplicationOperationId = if (phase == LocalFoodRefundFactPhase.RESOLUTION)
                        checkNotNull(fact.original.application.value.lastApplicationOperationId) else null)
            } catch (_: FoodCapacityExceeded) { foodRefundPending(slot.target, LocalFoodRefundPendingReason.STORAGE_CAPACITY_REQUIRED, phase) }
              catch (_: LocalSalesWireCapacityExceeded) { foodRefundPending(slot.target, LocalFoodRefundPendingReason.STORAGE_CAPACITY_REQUIRED, phase) }
              catch (_: LocalSalesCapacityExceeded) { foodRefundPending(slot.target, LocalFoodRefundPendingReason.STORAGE_CAPACITY_REQUIRED, phase) }
            // No stock-at-Apply revalidation, expiry renewal/reversal, release or physical action.
        }
    } catch (_: FoodCapacityExceeded) { return hold(LocalFoodRefundPendingReason.STORAGE_CAPACITY_REQUIRED) }
      catch (_: StockReadEvidenceCapacityExceeded) { return hold(LocalFoodRefundPendingReason.STORAGE_CAPACITY_REQUIRED) }
      catch (_: LocalSalesSourceUnavailable) { return hold(LocalFoodRefundPendingReason.SOURCE_UNAVAILABLE) }
}

internal val foodRefundApplicationBarriers: Set<RecoveryResourceKind> = setOf(RecoveryResourceKind.AUTHORIZATION,
    RecoveryResourceKind.CASH_CUSTODY, RecoveryResourceKind.ORDER_CUSTODY, RecoveryResourceKind.REFUND_ENTITLEMENT)

internal fun foodRefundApplicationAttemptMatches(refund: FoodRefundObligation, cash: CashSession,
    slot: LocalFoodRefundSlot, phase: LocalFoodRefundFactPhase, originalUnknown: LocalFoodRefundRaw?): Boolean {
    val target = slot.target
    val attempt = refund.attempts.singleOrNull { it.id == target.attemptId } ?: return false
    if (refund.id != target.refundId || refund.orderId != target.orderId || refund.originalCollection.id != target.collectionId ||
        refund.saleId != target.saleId || cash.id != target.sessionId || cash.status != CashSessionStatus.OPEN ||
        cash.custodianId != slot.originalActorId || attempt.originalOperationId != target.intentOperationId ||
        attempt.originalActorId != slot.originalActorId || attempt.originalCashSessionId != target.sessionId ||
        attempt.originalPaymentId != target.originalPaymentId || attempt.amount != slot.domain.amount || attempt.tender != Tender.Cash ||
        attempt.refund != null || attempt.resolutionObservation != null || attempt.id !in cash.pendingAttemptIds ||
        cash.reservedCashOut.minor < attempt.amount.minor) return false
    return if (phase == LocalFoodRefundFactPhase.ORIGINAL)
        originalUnknown == null && attempt.status == AttemptStatus.AWAITING_ATTESTATION && attempt.latestObservation == null
    else {
        val raw = originalUnknown ?: return false
        val body = LocalFoodRefundCodec.decode(raw.body()) as? LocalFoodRefundRequest.RecordOriginal ?: return false
        raw.target == target && raw.originalActorId == slot.originalActorId && raw.recordingActorId == slot.originalActorId &&
            body.outcome is LocalFoodRefundOutcome.Unknown && attempt.status == AttemptStatus.OUTCOME_UNKNOWN &&
            attempt.latestObservation == foodRefundOutcome(body.outcome, slot.originalActorId)
    }
}
