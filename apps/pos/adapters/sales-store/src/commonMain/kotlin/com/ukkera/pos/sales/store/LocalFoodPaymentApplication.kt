package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** Inert typed accounting selector; no Boolean from a request can enter the supervised route. */
internal data class FoodPaymentApplySelection(val factOperationId: OperationId,
    val phase: LocalFoodPaymentFactPhase, val supervised: Boolean)

internal fun foodPaymentApplySelection(command: CommandKey, normalized: StoredBytes): FoodPaymentApplySelection = when (command) {
    LocalFoodPaymentProfile.COMMAND -> when (val request = LocalFoodPaymentCodec.decode(normalized)) {
        is LocalFoodPaymentRequest.ApplyOriginal -> FoodPaymentApplySelection(request.recordingOperationId, LocalFoodPaymentFactPhase.ORIGINAL, false)
        is LocalFoodPaymentRequest.ApplyResolution -> FoodPaymentApplySelection(request.resolutionOperationId, LocalFoodPaymentFactPhase.RESOLUTION, false)
        else -> error("food.payment.accounting_subtype")
    }
    LocalFoodPaymentSupervisorCodec.COMMAND -> {
        val request = LocalFoodPaymentSupervisorCodec.decode(normalized) as? LocalFoodPaymentSupervisorRequest.Apply
            ?: error("food.payment.supervisor_accounting_subtype")
        FoodPaymentApplySelection(request.factOperationId, request.phase, true)
    }
    else -> error("food.payment.accounting_command")
}

internal fun requireFoodPaymentSupervisedApplication(context: CommandContext, operationId: OperationId,
    phase: LocalFoodPaymentFactPhase) {
    check(context.key == LocalFoodPaymentSupervisorCodec.COMMAND && operationId != context.operationId &&
        semanticDigest(context.scope, context.originalActorId, context.key, LocalFoodPaymentSupervisorCodec.encode(
            LocalFoodPaymentSupervisorRequest.Apply(operationId, phase))) == context.semanticDigest)
}

/** Actual immutable current cuts, never reconstructed originals, grants or native source rows. */
internal class FoodPaymentApplicationFact(val original: FoodPaymentRawCut, val resolution: FoodPaymentResolutionCut? = null) {
    val slot: LocalFoodPaymentSlot get() = original.slot.value
    val phase: LocalFoodPaymentFactPhase get() = if (resolution == null) LocalFoodPaymentFactPhase.ORIGINAL else LocalFoodPaymentFactPhase.RESOLUTION
    val application: LocalSalesRow<LocalFoodPaymentApplication> get() = resolution?.application ?: original.application
    val recorderId: DomainId get() = resolution?.raw?.value?.recordingActorId ?: original.raw.value.recordingActorId
    val outcome: LocalFoodCashOutcome get() = if (resolution == null)
        (LocalFoodPaymentCodec.decode(original.raw.value.body()) as LocalFoodPaymentRequest.RecordOriginal).outcome
        else (LocalFoodPaymentCodec.decode(resolution.raw.value.body()) as LocalFoodPaymentRequest.RecordResolution).outcome
    val originalBodyDigest: PayloadDigest get() = original.raw.value.bodyDigest
    val resolutionBodyDigest: PayloadDigest? get() = resolution?.raw?.value?.bodyDigest
    fun initialApplication(): LocalFoodPaymentApplication = resolution?.let { foodPaymentInitialApplication(it.raw.value) }
        ?: foodPaymentInitialApplication(original.raw.value)
}

/** SQL-only Apply. Whole raw/UNKNOWN/Start/native custody precedes the nonstock accounting cut. */
internal suspend fun prepareFoodPaymentApplication(operationId: OperationId, phase: LocalFoodPaymentFactPhase,
    borrowed: CommandReadView, context: CommandContext, stock: LocalStockRepository,
    recipes: TransactionFoodStockSource, supervised: Boolean = false): PreparedMutation {
    if (supervised) requireFoodPaymentSupervisedApplication(context, operationId, phase)
    else {
        val body = if (phase == LocalFoodPaymentFactPhase.ORIGINAL) LocalFoodPaymentRequest.ApplyOriginal(operationId)
            else LocalFoodPaymentRequest.ApplyResolution(operationId)
        check(context.key == LocalFoodPaymentProfile.COMMAND && operationId != context.operationId &&
            semanticDigest(context.scope, context.originalActorId, context.key, LocalFoodPaymentCodec.encode(body)) == context.semanticDigest)
    }
    val rawKey = if (phase == LocalFoodPaymentFactPhase.ORIGINAL) foodPaymentRawKey(operationId) else foodPaymentResolutionKey(operationId)
    val headerRow = borrowed.aggregate(rawKey) ?: return foodPaymentUnavailablePreparation(context, DomainId(operationId.value))
    val owner: DomainId
    val recorder: DomainId
    if (phase == LocalFoodPaymentFactPhase.ORIGINAL) {
        val header = localSalesDecode(headerRow.payload, LocalFoodPaymentRaw.serializer(), LocalFoodPaymentProfile.MAX_RAW_BYTES)
        owner = header.originalActorId; recorder = header.recordingActorId
    } else {
        val header = localSalesDecode(headerRow.payload, LocalFoodPaymentResolutionRaw.serializer(), LocalFoodPaymentProfile.MAX_RAW_BYTES)
        owner = header.originalActorId; recorder = header.recordingActorId
    }
    // Denial-only header before rich reads. Foreign recorder/custody is an explicit supervisor path.
    if (!supervised && (owner != context.originalActorId || recorder != context.originalActorId))
        return foodPaymentUnavailablePreparation(context, DomainId(operationId.value))
    val originalView = FoodReadView(commerceLoan(borrowed, stock))
    val fact = if (phase == LocalFoodPaymentFactPhase.ORIGINAL)
        FoodPaymentApplicationFact(readFoodPaymentRaw(originalView, operationId, stock))
        else readFoodPaymentResolution(originalView, operationId, stock).let { FoodPaymentApplicationFact(it.original, it) }
    val slot = fact.slot
    check(slot.target.factOperation(phase) == operationId && owner == slot.originalActorId && recorder == fact.recorderId &&
        context.operationId !in setOf(slot.target.intentOperationId, slot.target.recordingOperationId, slot.target.resolutionOperationId))
    val resolve = supervised || phase == LocalFoodPaymentFactPhase.RESOLUTION
    val authorityResources = slot.domain.financialResources.toSet() + foodPaymentAuthorityResources(slot.requirement()) + recordingAuthority(fact.recorderId)
    val pendingAction = if (resolve) SecurityAction.COLLECTION_RESOLVE else SecurityAction.CASH_READ
    fun pending(reason: LocalFoodPaymentPendingReason, resources: Set<RecoveryResourceKey> = authorityResources): PreparedMutation =
        FoodPaymentPrepared(context, pendingAction, DomainId(operationId.value), fact.application.value.revision,
            slot.originalActorId, "food.payment.pending", if (resolve) slot.domain.amount else null, resources,
            foodPaymentApplicationBarriers, allowsActionApproval = resolve) { _, _ -> foodPaymentPending(slot.target, reason, phase) }
    if (fact.application.value.state == LocalFoodPaymentApplicationState.APPLIED)
        return FoodPaymentPrepared(context, pendingAction, DomainId(operationId.value), fact.application.value.revision,
            slot.originalActorId, "food.payment.already_applied", if (resolve) slot.domain.amount else null, authorityResources,
            foodPaymentApplicationBarriers, allowsActionApproval = resolve) { _, _ -> ProposedOutcome.Reject("food.payment.already_applied",
                LocalFoodPaymentCodec.result(LocalFoodPaymentResult.AlreadyApplied(operationId,
                    checkNotNull(fact.application.value.lastApplicationOperationId), phase))) }
    if (phase == LocalFoodPaymentFactPhase.RESOLUTION && (fact.original.application.value.state != LocalFoodPaymentApplicationState.APPLIED ||
            fact.original.application.value.action != LocalFoodPaymentAction.UNKNOWN))
        return pending(LocalFoodPaymentPendingReason.ORIGINAL_APPLICATION_REQUIRED)
    val outcome = fact.outcome
    if (outcome is LocalFoodCashOutcome.ExternalAttested || outcome is LocalFoodCashOutcome.CashReceived &&
        (outcome.received.currency != slot.domain.amount.currency || outcome.change.currency != slot.domain.amount.currency ||
            outcome.received.minor <= 0L || outcome.change.minor < 0L || outcome.received - outcome.change != slot.domain.amount))
        return pending(LocalFoodPaymentPendingReason.VALUE_OR_TENDER_MISMATCH)
    val current = foodFinancialObserve(borrowed, LocalFoodFinancialProfileV1.COLLECTION, slot.target.collectionId)?.second
        ?.let(::foodFinancialCollection) ?: return pending(LocalFoodPaymentPendingReason.SOURCE_UNAVAILABLE)
    if (current.orderId != slot.target.orderId || current.currentOwnerId != slot.originalActorId || current.responsibility.isNotEmpty() ||
        current.saleId != null || current.cancellationRefundId != null)
        return pending(LocalFoodPaymentPendingReason.CURRENT_CUSTODY_REQUIRES_RECONCILIATION)
    val view = FoodReadView(commerceLoan(borrowed, stock))
    val capture = view.requireStockRelational().beginReadEvidence(context)
    var sealed = false
    suspend fun hold(reason: LocalFoodPaymentPendingReason, resources: Set<RecoveryResourceKey> = authorityResources): PreparedMutation {
        if (!sealed) capture.discard()
        return pending(reason, resources)
    }
    try {
        val cut = foodPaymentCollectionCut(view, slot.target.collectionId, recipes)
        val cash = LocalSalesStorage.cash(view, slot.target.sessionId)
        val attempt = cut.value.attempts.singleOrNull { it.id == slot.target.attemptId }
        val resources = authorityResources + foodFinancialResources(cut) + cashResources(cash)
        if (cash.session.value.status != CashSessionStatus.OPEN || cash.session.value.custodianId != slot.originalActorId ||
            cut.order.value.ownerId != slot.originalActorId || cut.order.value.handoff != null || cut.order.value.closedByOperationId != null)
            return hold(LocalFoodPaymentPendingReason.CURRENT_CUSTODY_REQUIRES_RECONCILIATION, resources)
        if (attempt == null || !foodPaymentApplicationAttemptMatches(cut.value, cash.session.value, slot, phase,
                if (phase == LocalFoodPaymentFactPhase.RESOLUTION) fact.original.raw.value else null))
            return hold(LocalFoodPaymentPendingReason.ORIGINAL_ATTEMPT_MISMATCH, resources)
        if (view.claim(LocalSalesStoreProfile.unique("payment_record", attempt.id)) != null ||
            view.claim(CashNonSaleStoreProfile.cashSourceClaim(attempt.id)) != null ||
            view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.MOVEMENT_KIND, attempt.id)) != null)
            return hold(LocalFoodPaymentPendingReason.CURRENT_CUSTODY_REQUIRES_RECONCILIATION, resources)
        val inputs = foodPaymentCutInputs(cut)
        val cashInput = foodFinancialCashInput(cash)
        val sources = FoodPaymentSources(capture.observations())
        val literal = sources.collection(inputs, cashInput)
        requireFoodPaymentApplicationCut(literal, slot, phase,
            if (phase == LocalFoodPaymentFactPhase.RESOLUTION) fact.original.raw.value else null)
        sources.applicationVacancy(attempt.id); sources.finish(stock = false)
        val allResources = authorityResources + literal.financialResources
        if (allResources.size > 512) return hold(LocalFoodPaymentPendingReason.STORAGE_CAPACITY_REQUIRED, allResources)
        val evidence = capture.finish(); sealed = true
        return FoodPaymentPrepared(context, if (resolve) SecurityAction.COLLECTION_RESOLVE else SecurityAction.COLLECTION_ATTEST,
            cut.value.id, cut.value.revision, slot.originalActorId, "food.payment.apply", slot.domain.amount, allResources,
            foodPaymentApplicationBarriers, allowsActionApproval = resolve) { command, at ->
            try {
                val observedOutcome = foodPaymentOutcome(outcome, fact.recorderId)
                val changed = if (resolve) FoodCollections.applySupervisedObservation(command, cut.value, cash.session.value,
                    attempt.id, operationId, fact.recorderId,
                    if (phase == LocalFoodPaymentFactPhase.RESOLUTION) slot.target.recordingOperationId else null,
                    if (phase == LocalFoodPaymentFactPhase.RESOLUTION) fact.originalBodyDigest else null, observedOutcome)
                    else FoodCollections.applyObservation(command, cut.value, cash.session.value, attempt.id, operationId, observedOutcome)
                val financialRevisions = listOf(LocalFoodStorageRevisionV1(LocalFoodFinancialProfileV1.COLLECTION, cut.value.id, cut.row.row.revision.next())) +
                    listOfNotNull(if (changed.session === cash.session.value) null else LocalFoodStorageRevisionV1(CashNonSaleStoreProfile.SESSION_KIND,
                        changed.session.id, cash.session.stored.revision.next()))
                val application = fact.initialApplication().copy(revision = Revision(1), state = LocalFoodPaymentApplicationState.APPLIED,
                    reason = null, updatedAt = at, lastApplicationOperationId = context.operationId,
                    action = foodPaymentAction(outcome), financialRevisions = financialRevisions)
                val result = LocalFoodPaymentResult.Applied(slot.target, checkNotNull(application.action), context.operationId,
                    financialRevisions + LocalFoodStorageRevisionV1(LocalFoodPaymentProfile.APPLICATION, DomainId(operationId.value), Revision(2)), phase)
                val extra = listOfNotNull(if (changed.session === cash.session.value) null else foodPaymentCashWrite(changed.session, cashInput.storageRevision),
                    changed.movement?.let { movement -> if (resolve) foodPaymentAccountedMovementWrite(context.scope, context.operationId,
                        context.originalActorId, slot, phase, fact.recorderId, fact.originalBodyDigest, movement, at)
                        else movementWrite(context, movement, at) }) + foodPaymentApplicationWrite(application, Revision(1))
                val claims = listOfNotNull(changed.payment?.let { ClaimChange.Acquire(LocalSalesStoreProfile.unique("payment_record", it.attemptId), cut.value.id) },
                    changed.movement?.let { ClaimChange.Acquire(CashNonSaleStoreProfile.cashSourceClaim(it.sourceId), cash.session.value.id) })
                val writes = FoodPaymentWrites(context, at, inputs, cashInput)
                writes.collection(changed.collection)
                writes.finish(result, extra, claims, evidence, slotDigest = fact.original.raw.value.slotDigest,
                    originalBodyDigest = fact.originalBodyDigest, resolutionBodyDigest = fact.resolutionBodyDigest,
                    originalUnknownApplicationOperationId = if (phase == LocalFoodPaymentFactPhase.RESOLUTION)
                        checkNotNull(fact.original.application.value.lastApplicationOperationId) else null)
            } catch (_: FoodCapacityExceeded) { foodPaymentPending(slot.target, LocalFoodPaymentPendingReason.STORAGE_CAPACITY_REQUIRED, phase) }
              catch (_: LocalSalesWireCapacityExceeded) { foodPaymentPending(slot.target, LocalFoodPaymentPendingReason.STORAGE_CAPACITY_REQUIRED, phase) }
              catch (_: LocalSalesCapacityExceeded) { foodPaymentPending(slot.target, LocalFoodPaymentPendingReason.STORAGE_CAPACITY_REQUIRED, phase) }
            // No stock-at-Apply revalidation, expiry renewal/reversal, release or physical action.
        }
    } catch (_: FoodCapacityExceeded) { return hold(LocalFoodPaymentPendingReason.STORAGE_CAPACITY_REQUIRED) }
      catch (_: StockReadEvidenceCapacityExceeded) { return hold(LocalFoodPaymentPendingReason.STORAGE_CAPACITY_REQUIRED) }
      catch (_: LocalSalesSourceUnavailable) { return hold(LocalFoodPaymentPendingReason.SOURCE_UNAVAILABLE) }
}

private val foodPaymentApplicationBarriers: Set<RecoveryResourceKind> = setOf(RecoveryResourceKind.AUTHORIZATION,
    RecoveryResourceKind.CASH_CUSTODY, RecoveryResourceKind.ORDER_CUSTODY)

internal fun foodPaymentApplicationAttemptMatches(collection: FoodCollection, cash: CashSession,
    slot: LocalFoodPaymentSlot, phase: LocalFoodPaymentFactPhase, originalUnknown: LocalFoodPaymentRaw?): Boolean {
    val target = slot.target
    val attempt = collection.attempts.singleOrNull { it.id == target.attemptId } ?: return false
    if (collection.id != target.collectionId || collection.orderId != target.orderId || collection.cashSessionId != target.sessionId ||
        collection.currentOwnerId != slot.originalActorId || collection.collectorId != slot.originalActorId || collection.responsibility.isNotEmpty() ||
        collection.saleId != null || collection.cancellationRefundId != null || cash.id != target.sessionId ||
        cash.status != CashSessionStatus.OPEN || cash.custodianId != slot.originalActorId ||
        attempt.originalOperationId != target.intentOperationId || attempt.originalActorId != slot.originalActorId ||
        attempt.originalCashSessionId != target.sessionId || attempt.amount != slot.domain.amount || attempt.tender != Tender.Cash ||
        attempt.payment != null || attempt.resolutionObservation != null || attempt.foodResolutionObservation != null ||
        attempt.id !in cash.pendingAttemptIds) return false
    return if (phase == LocalFoodPaymentFactPhase.ORIGINAL)
        originalUnknown == null && attempt.status == AttemptStatus.AWAITING_ATTESTATION && attempt.latestObservation == null
    else {
        val raw = originalUnknown ?: return false
        val body = LocalFoodPaymentCodec.decode(raw.body()) as? LocalFoodPaymentRequest.RecordOriginal ?: return false
        raw.target == target && raw.originalActorId == slot.originalActorId && raw.recordingActorId == slot.originalActorId &&
            body.outcome is LocalFoodCashOutcome.Unknown && attempt.status == AttemptStatus.OUTCOME_UNKNOWN &&
            attempt.latestObservation == foodPaymentOutcome(body.outcome, slot.originalActorId)
    }
}
