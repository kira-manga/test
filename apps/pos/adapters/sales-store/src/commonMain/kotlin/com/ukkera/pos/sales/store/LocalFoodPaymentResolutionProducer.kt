package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*

internal class FoodPaymentResolutionOriginal(val raw: LocalFoodPaymentResolutionRaw,
    val original: FoodPaymentRawOriginal, val proposal: ProposedOutcome.Apply)

/** Whole literal FOOD resolution proof. No current read, handler, grant, lease or retail body. */
internal object LocalFoodPaymentResolutionProducer {
    suspend fun requireRaw(original: RetainedCommandEvidence, historicalStart: RetainedCommandEvidence,
        originalUnknown: RetainedCommandEvidence): FoodPaymentResolutionOriginal {
        foodPaymentRequireOriginalHeader(original, employee = true)
        check(original.command == LocalFoodPaymentProfile.COMMAND)
        val request = LocalFoodPaymentCodec.decode(original.normalized) as? LocalFoodPaymentRequest.RecordResolution
            ?: error("food.payment.resolution_required")
        val parent = LocalFoodPaymentProducer.requireRaw(originalUnknown, historicalStart)
        val slot = parent.slot
        val requirement = slot.requirement()
        foodPaymentRequireEarlier(historicalStart, originalUnknown)
        foodPaymentRequireEarlier(originalUnknown, original)
        check(original.scope == slot.scope && request.target == slot.target && original.operationId == slot.target.resolutionOperationId &&
            request.resolverActorId == original.originalActorId && request.originalBodyDigest == parent.raw.bodyDigest &&
            LocalFoodCashJournalDomain.classifyOriginal(requirement, parent.raw.body()) == LocalPhysicalOriginalKind.UNKNOWN)
        LocalFoodPaymentResolutionDescriptor.create(requirement, parent.raw.body(), original.originalActorId, original.normalized)
        val raw = LocalFoodPaymentResolutionRaw(1, original.scope, slot.target, slot.originalActorId, original.originalActorId,
            original.normalized.copyBytes().decodeToString(throwOnInvalidSequence = true), sha256(original.normalized.copyBytes()),
            parent.raw.bodyDigest, original.semanticDigest, parent.raw.slotDigest, original.recordedAt)
        val proposal = ProposedOutcome.Apply(LocalFoodPaymentCodec.result(LocalFoodPaymentResult.RawRetained(slot.target,
            phase = LocalFoodPaymentFactPhase.RESOLUTION)), listOf(foodPaymentResolutionWrite(raw),
            foodPaymentApplicationWrite(foodPaymentInitialApplication(raw), null)))
        foodPaymentRequireExactEffects(original, proposal)
        return FoodPaymentResolutionOriginal(raw, parent, proposal)
    }

    suspend fun requireApply(original: RetainedCommandEvidence, historicalStart: RetainedCommandEvidence,
        originalUnknown: RetainedCommandEvidence, resolution: RetainedCommandEvidence,
        originalUnknownApplication: RetainedCommandEvidence): FoodPaymentAppliedOriginal {
        foodPaymentRequireOriginalHeader(original, employee = true)
        val selection = foodPaymentApplySelection(original.command, original.normalized)
        check(selection.phase == LocalFoodPaymentFactPhase.RESOLUTION)
        val resolved = requireRaw(resolution, historicalStart, originalUnknown)
        val raw = resolved.raw
        val slot = resolved.original.slot
        val parent = resolved.original.raw
        val parentApplication = LocalFoodPaymentProducer.requireApply(originalUnknownApplication, historicalStart, originalUnknown)
        check(parentApplication.application.state == LocalFoodPaymentApplicationState.APPLIED &&
            parentApplication.application.phase == LocalFoodPaymentFactPhase.ORIGINAL &&
            parentApplication.application.action == LocalFoodPaymentAction.UNKNOWN &&
            parentApplication.application.target == slot.target)
        foodPaymentRequireEarlier(resolution, original)
        foodPaymentRequireEarlier(originalUnknown, originalUnknownApplication)
        foodPaymentRequireEarlier(originalUnknownApplication, original)
        val request = LocalFoodPaymentCodec.decode(raw.body()) as LocalFoodPaymentRequest.RecordResolution
        val outcome = request.outcome
        check(selection.factOperationId == slot.target.resolutionOperationId &&
            (selection.supervised || original.originalActorId == slot.originalActorId && original.originalActorId == raw.recordingActorId) &&
            original.operationId !in setOf(slot.target.intentOperationId, slot.target.recordingOperationId, slot.target.resolutionOperationId) &&
            outcome !is LocalFoodCashOutcome.Unknown && outcome !is LocalFoodCashOutcome.ExternalAttested)
        val event = foodPaymentStoredEvent(original)
        val frame = FoodFinancialBundleReaderV2(original, event.framing, allowStockInputs = true)
        val inputs = foodPaymentInputs(original, frame)
        val cashInput = checkNotNull(frame.cash())
        val observed = StockReadEvidenceV1.read(original) ?: error("food.payment.read_evidence_required")
        val sources = FoodPaymentSources(observed)
        val cut = sources.collection(inputs, cashInput)
        requireFoodPaymentApplicationCut(cut, slot, LocalFoodPaymentFactPhase.RESOLUTION, parent)
        sources.applicationVacancy(slot.target.attemptId); sources.finish(stock = false)
        check(event.slotDigest == parent.slotDigest && event.originalBodyDigest == parent.bodyDigest &&
            event.resolutionBodyDigest == raw.bodyDigest &&
            event.originalUnknownApplicationOperationId == originalUnknownApplication.operationId)
        val after = foodPaymentAfterCollection(original, frame, cut.collection.id)
        val cashAfter = foodPaymentAfterCash(original, cut.cash.id, cashInput.storageRevision.next())
        val movement = FoodCashHistory.requireSupervisedApplied(cut.collection, after, cut.cash, cashAfter, slot.target.attemptId,
            slot.target.resolutionOperationId, raw.recordingActorId, original.operationId, original.originalActorId,
            slot.target.recordingOperationId, parent.bodyDigest, foodPaymentOutcome(outcome, raw.recordingActorId))
        val financialRevisions = listOf(LocalFoodStorageRevisionV1(LocalFoodFinancialProfileV1.COLLECTION,
            cut.collection.id, cut.collectionEnvelope.revision.next()), LocalFoodStorageRevisionV1(CashNonSaleStoreProfile.SESSION_KIND,
            cut.cash.id, cashInput.storageRevision.next()))
        val application = foodPaymentInitialApplication(raw).copy(revision = Revision(1), state = LocalFoodPaymentApplicationState.APPLIED,
            reason = null, updatedAt = original.recordedAt, lastApplicationOperationId = original.operationId,
            action = foodPaymentAction(outcome), financialRevisions = financialRevisions)
        val result = LocalFoodPaymentResult.Applied(slot.target, checkNotNull(application.action), original.operationId,
            financialRevisions + LocalFoodStorageRevisionV1(LocalFoodPaymentProfile.APPLICATION,
                DomainId(slot.target.resolutionOperationId.value), Revision(2)), LocalFoodPaymentFactPhase.RESOLUTION)
        val extra = listOf(foodPaymentCashWrite(cashAfter, cashInput.storageRevision)) + listOfNotNull(movement?.let {
            foodPaymentAccountedMovementWrite(original.scope, original.operationId, original.originalActorId, slot,
                LocalFoodPaymentFactPhase.RESOLUTION, raw.recordingActorId, parent.bodyDigest, it, original.recordedAt)
        }) + foodPaymentApplicationWrite(application, Revision(1))
        val payment = after.attempts.single { it.id == slot.target.attemptId }.payment
        val claims = listOfNotNull(payment?.let { ClaimChange.Acquire(LocalSalesStoreProfile.unique("payment_record", it.attemptId), after.id) },
            movement?.let { ClaimChange.Acquire(CashNonSaleStoreProfile.cashSourceClaim(it.sourceId), cut.cash.id) })
        val writes = FoodPaymentWrites(original.scope, original.operationId, original.originalActorId, original.recordedAt, inputs, cashInput)
        writes.collection(after)
        val proposal = writes.finish(result, extra, claims, foodPaymentOriginalReadWrites(original),
            slotDigest = parent.slotDigest, originalBodyDigest = parent.bodyDigest, resolutionBodyDigest = raw.bodyDigest,
            originalUnknownApplicationOperationId = originalUnknownApplication.operationId)
        frame.requireCompleteLeaves(event.after)
        foodPaymentRequireExactEffects(original, proposal)
        val resources: Set<RecoveryResourceKey> = slot.domain.financialResources.toSet() + cut.financialResources +
            foodPaymentAuthorityResources(slot.requirement()) + recordingAuthority(raw.recordingActorId) + recordingAuthority(original.originalActorId)
        check(resources.size <= 512)
        return FoodPaymentAppliedOriginal(application, cut, proposal, resources)
    }
}

internal fun foodPaymentRequireEarlier(earlier: RetainedCommandEvidence, later: RetainedCommandEvidence) {
    check(earlier.committed && later.committed && earlier.scope == later.scope && earlier.operationId != later.operationId &&
        (earlier.assignmentId != later.assignmentId || earlier.authorityEpoch != later.authorityEpoch || earlier.sequence < later.sequence))
}
