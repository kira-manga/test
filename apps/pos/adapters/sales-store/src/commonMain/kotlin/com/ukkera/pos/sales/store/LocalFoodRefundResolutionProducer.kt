package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*

internal class FoodRefundResolutionOriginal(val raw: LocalFoodRefundResolutionRaw,
    val original: FoodRefundRawOriginal, val proposal: ProposedOutcome.Apply)

/** Whole same-paying-actor FOOD UNKNOWN resolution proof; no supervisor/custody takeover. */
internal object LocalFoodRefundResolutionProducer {
    suspend fun requireRaw(original: RetainedCommandEvidence, historicalStart: RetainedCommandEvidence,
        originalUnknown: RetainedCommandEvidence): FoodRefundResolutionOriginal {
        foodRefundRequireOriginalHeader(original, employee = true)
        val request = LocalFoodRefundCodec.decode(original.normalized) as? LocalFoodRefundRequest.RecordResolution
            ?: error("food.refund.resolution_required")
        val parent = LocalFoodRefundProducer.requireRaw(originalUnknown, historicalStart)
        val slot = parent.slot
        val requirement = slot.requirement()
        foodRefundRequireEarlier(originalUnknown, original)
        check(original.scope == slot.scope && request.target == slot.target && original.operationId == slot.target.resolutionOperationId &&
            original.originalActorId == slot.originalActorId && request.resolverActorId == slot.originalActorId &&
            request.originalBodyDigest == parent.raw.bodyDigest &&
            LocalFoodRefundCashJournalDomain.classifyOriginal(requirement, parent.raw.body()) == LocalPhysicalOriginalKind.UNKNOWN)
        LocalFoodRefundResolutionDescriptor.create(requirement, parent.raw.body(), original.originalActorId, original.normalized)
        val raw = LocalFoodRefundResolutionRaw(1, original.scope, slot.target, slot.originalActorId, slot.originalActorId,
            original.normalized.copyBytes().decodeToString(throwOnInvalidSequence = true), sha256(original.normalized.copyBytes()),
            parent.raw.bodyDigest, original.semanticDigest, parent.raw.slotDigest, original.recordedAt)
        val proposal = ProposedOutcome.Apply(LocalFoodRefundCodec.result(LocalFoodRefundResult.RawRetained(slot.target,
            phase = LocalFoodRefundFactPhase.RESOLUTION)), listOf(foodRefundResolutionWrite(raw),
            foodRefundApplicationWrite(foodRefundInitialApplication(raw), null)))
        foodRefundRequireExactEffects(original, proposal)
        return FoodRefundResolutionOriginal(raw, parent, proposal)
    }

    suspend fun requireApply(original: RetainedCommandEvidence, historicalStart: RetainedCommandEvidence,
        originalUnknown: RetainedCommandEvidence, resolution: RetainedCommandEvidence,
        originalUnknownApplication: RetainedCommandEvidence): FoodRefundAppliedOriginal {
        foodRefundRequireOriginalHeader(original, employee = true)
        val selection = foodRefundApplySelection(original.command, original.normalized)
        check(selection.phase == LocalFoodRefundFactPhase.RESOLUTION)
        val resolved = requireRaw(resolution, historicalStart, originalUnknown)
        val raw = resolved.raw
        val slot = resolved.original.slot
        val parent = resolved.original.raw
        val parentApplication = LocalFoodRefundProducer.requireApply(originalUnknownApplication, historicalStart, originalUnknown)
        check(parentApplication.application.state == LocalFoodRefundApplicationState.APPLIED &&
            parentApplication.application.phase == LocalFoodRefundFactPhase.ORIGINAL &&
            parentApplication.application.action == LocalFoodRefundAction.UNKNOWN &&
            parentApplication.application.target == slot.target)
        foodRefundRequireEarlier(resolution, original)
        foodRefundRequireEarlier(originalUnknownApplication, original)
        val outcome = (LocalFoodRefundCodec.decode(raw.body()) as LocalFoodRefundRequest.RecordResolution).outcome
        check(selection.factOperationId == slot.target.resolutionOperationId && original.originalActorId == slot.originalActorId &&
            raw.recordingActorId == slot.originalActorId &&
            original.operationId !in setOf(slot.target.intentOperationId, slot.target.recordingOperationId, slot.target.resolutionOperationId) &&
            outcome !is LocalFoodRefundOutcome.Unknown)
        requireFoodRefundApplicableOutcome(outcome, slot.domain.amount)
        val event = foodRefundStoredEvent(original)
        val frame = FoodFinancialBundleReaderV2(original, event.framing, allowStockInputs = true)
        val inputs = foodRefundInputs(original, frame)
        val cashInput = checkNotNull(frame.cash())
        val observed = StockReadEvidenceV1.read(original) ?: error("food.refund.read_evidence_required")
        val sources = FoodRefundSources(observed)
        val cut = sources.refund(inputs, cashInput)
        requireFoodRefundApplicationCut(cut, slot, LocalFoodRefundFactPhase.RESOLUTION, parent)
        sources.applicationVacancy(slot.target.attemptId); sources.finish()
        check(event.slotDigest == parent.slotDigest && event.originalBodyDigest == parent.bodyDigest &&
            event.resolutionBodyDigest == raw.bodyDigest &&
            event.originalUnknownApplicationOperationId == originalUnknownApplication.operationId)
        val after = foodRefundAfterRefund(original, frame, cut.refund.id)
        val cashAfter = foodPaymentAfterCash(original, cut.cash.id, cashInput.storageRevision.next())
        val movement = FoodRefundCashHistory.requireApplied(cut.refund, after, cut.cash, cashAfter, slot.target.attemptId,
            slot.target.resolutionOperationId, original.operationId, slot.originalActorId,
            foodRefundOutcome(outcome, raw.recordingActorId), resolvingUnknown = true)
        val financialRevisions = listOf(LocalFoodStorageRevisionV1(LocalFoodFinancialProfileV1.REFUND,
            cut.refund.id, cut.refundEnvelope.revision.next()), LocalFoodStorageRevisionV1(CashNonSaleStoreProfile.SESSION_KIND,
            cut.cash.id, cashInput.storageRevision.next()))
        val application = foodRefundInitialApplication(raw).copy(revision = Revision(1), state = LocalFoodRefundApplicationState.APPLIED,
            reason = null, updatedAt = original.recordedAt, lastApplicationOperationId = original.operationId,
            action = foodRefundAction(outcome), financialRevisions = financialRevisions)
        val result = LocalFoodRefundResult.Applied(slot.target, checkNotNull(application.action), original.operationId,
            financialRevisions + LocalFoodStorageRevisionV1(LocalFoodRefundProfile.APPLICATION,
                DomainId(slot.target.resolutionOperationId.value), Revision(2)), LocalFoodRefundFactPhase.RESOLUTION)
        val extra = listOf(foodPaymentCashWrite(cashAfter, cashInput.storageRevision)) + listOfNotNull(movement?.let {
            cashMovementLiteralWrite(original.scope, original.operationId, original.originalActorId, it, original.recordedAt)
        }) + foodRefundApplicationWrite(application, Revision(1))
        val record = after.attempts.single { it.id == slot.target.attemptId }.refund
        val claims = listOfNotNull(record?.let { ClaimChange.Acquire(LocalSalesStoreProfile.unique("refund_record", it.attemptId), after.id) },
            movement?.let { ClaimChange.Acquire(CashNonSaleStoreProfile.cashSourceClaim(it.sourceId), cut.cash.id) })
        val writes = FoodRefundWrites(original.scope, original.operationId, original.originalActorId, original.recordedAt, inputs, cashInput)
        writes.refund(after)
        val proposal = writes.finish(result, extra, claims, foodRefundOriginalReadWrites(original),
            slotDigest = parent.slotDigest, originalBodyDigest = parent.bodyDigest, resolutionBodyDigest = raw.bodyDigest,
            originalUnknownApplicationOperationId = originalUnknownApplication.operationId)
        frame.requireCompleteLeaves(event.after)
        foodRefundRequireExactEffects(original, proposal)
        val resources: Set<RecoveryResourceKey> = slot.domain.financialResources.toSet() + cut.financialResources +
            foodRefundAuthorityResources(slot.requirement()) + recordingAuthority(raw.recordingActorId) + recordingAuthority(original.originalActorId)
        check(resources.size <= 512)
        return FoodRefundAppliedOriginal(application, cut, proposal, resources)
    }
}
