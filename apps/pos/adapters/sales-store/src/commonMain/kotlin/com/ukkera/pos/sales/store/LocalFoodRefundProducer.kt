package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

internal class FoodRefundStartOriginal(val slot: LocalFoodRefundSlot,
    val result: LocalFoodRefundResult.AttemptStarted, val cut: FoodRefundLiteralCut,
    val proposal: ProposedOutcome.Apply, val resources: Set<RecoveryResourceKey>)
internal class FoodRefundRawOriginal(val raw: LocalFoodRefundRaw, val slot: LocalFoodRefundSlot,
    val proposal: ProposedOutcome.Apply)
internal class FoodRefundAppliedOriginal(val application: LocalFoodRefundApplication,
    val cut: FoodRefundLiteralCut, val proposal: ProposedOutcome.Apply,
    val resources: Set<RecoveryResourceKey>)

/**
 * Whole literal accepted FOOD liability/cash/native producer comparison. Uses the SAME
 * pure refund/cash values as real authorized production. It creates no grants, history
 * views, native leases, order/stock decisions or fabricated originals. Actual source
 * custody is closed separately on the current loan or genuine native before cut.
 */
public object LocalFoodRefundProducer {
    public suspend fun requireComplete(original: RetainedCommandEvidence,
        historicalStart: RetainedCommandEvidence? = null, originalRaw: RetainedCommandEvidence? = null,
        resolutionRaw: RetainedCommandEvidence? = null,
        originalUnknownApplication: RetainedCommandEvidence? = null) {
        when (LocalFoodRefundCodec.decode(original.normalized)) {
            is LocalFoodRefundRequest.StartCash -> requireStart(original)
            is LocalFoodRefundRequest.RecordOriginal -> requireRaw(original, checkNotNull(historicalStart))
            is LocalFoodRefundRequest.RecordResolution -> LocalFoodRefundResolutionProducer.requireRaw(original,
                checkNotNull(historicalStart), checkNotNull(originalRaw))
            is LocalFoodRefundRequest.ApplyOriginal -> requireApply(original, checkNotNull(historicalStart), checkNotNull(originalRaw))
            is LocalFoodRefundRequest.ApplyResolution -> LocalFoodRefundResolutionProducer.requireApply(original,
                checkNotNull(historicalStart), checkNotNull(originalRaw), checkNotNull(resolutionRaw),
                checkNotNull(originalUnknownApplication))
        }
    }

    internal suspend fun requireStart(original: RetainedCommandEvidence): FoodRefundStartOriginal {
        foodRefundRequireOriginalHeader(original, employee = true)
        val request = LocalFoodRefundCodec.decode(original.normalized) as? LocalFoodRefundRequest.StartCash
            ?: error("food.refund.start_required")
        val result = LocalFoodRefundCodec.result(original.result) as? LocalFoodRefundResult.AttemptStarted
            ?: error("food.refund.start_result_required")
        val event = foodRefundStoredEvent(original)
        val frame = FoodFinancialBundleReaderV2(original, event.framing, allowStockInputs = true)
        val inputs = foodRefundInputs(original, frame)
        val cashInput = checkNotNull(frame.cash())
        val observed = StockReadEvidenceV1.read(original) ?: error("food.refund.read_evidence_required")
        val sources = FoodRefundSources(observed)
        val cut = sources.refund(inputs, cashInput)
        val slotWrite = original.aggregateWrites.single { it.key == foodRefundSlotKey(request.recordingOperationId) }
        check(slotWrite.storageGeneration == Revision(1) && slotWrite.schema == LocalFoodRefundProfile.schema(LocalFoodRefundProfile.SLOT))
        val slot = localSalesDecode(slotWrite.payload, LocalFoodRefundSlot.serializer(), LocalFoodRefundProfile.MAX_SLOT_BYTES)
        val requirement = slot.requirement()
        LocalFoodRefundCashJournalDomain.validateRequirement(requirement)
        val target = request.target(cut.refund, original.operationId)
        val financial = cut.financialResources + entitlementResource(request.attemptId)
        check(slot.version == 1 && slot.scope == original.scope && slot.originalActorId == original.originalActorId &&
            slot.target == target && slot.intentSemanticDigest == original.semanticDigest && foodRefundSlotWrite(slot).payload == slotWrite.payload &&
            slot.domain.refundRevision == request.expectedRefundRevision && slot.domain.sessionRevision == request.expectedSessionRevision &&
            slot.domain.amount == request.amount && slot.domain.authority.assignmentId == original.assignmentId &&
            slot.domain.authority.epoch == original.authorityEpoch &&
            slot.domain.financialResources == foodRefundSortedResources(financial) &&
            slot.domain.obligationSourcesDigest == foodRefundBasisDigest(inputs) &&
            cut.refund.revision == request.expectedRefundRevision && cut.cash.revision == request.expectedSessionRevision &&
            cut.cash.id == request.sessionId && cut.cash.custodianId == original.originalActorId && cut.cash.status == CashSessionStatus.OPEN)
        sources.startVacancy(requirement); sources.finish()
        val after = foodRefundAfterRefund(original, frame, cut.refund.id)
        val cashAfter = foodPaymentAfterCash(original, cut.cash.id, cashInput.storageRevision.next())
        FoodRefundCashHistory.requireStarted(cut.refund, after, cut.cash, cashAfter, original.operationId,
            original.originalActorId, request.expectedRefundRevision, request.expectedSessionRevision,
            request.attemptId, request.originalPaymentId, request.amount)
        val nativeSources = original.aggregateWrites.filter { it.key.kind == PhysicalRecordingSourceProfileV1.KIND }.map(::foodRefundInsert)
        val pair = requireFoodRefundSourcePair(requirement, nativeSources)
        check(slot.masterSource == LocalFoodRefundSourceReference.from(pair.first) &&
            slot.clientSource == LocalFoodRefundSourceReference.from(pair.second))
        val gated = nativeSources + foodRefundSlotWrite(slot) + foodRefundFences(requirement)
        val revisions = listOf(LocalFoodStorageRevisionV1(LocalFoodFinancialProfileV1.REFUND, cut.refund.id, cut.refundEnvelope.revision.next()),
            LocalFoodStorageRevisionV1(CashNonSaleStoreProfile.SESSION_KIND, cut.cash.id, cashInput.storageRevision.next()),
            LocalFoodStorageRevisionV1(LocalFoodRefundProfile.SLOT, DomainId(target.recordingOperationId.value), Revision(1)))
        val expectedResult = LocalFoodRefundResult.AttemptStarted(target, request.amount, sha256(requirement.canonicalBytes.copyBytes()), revisions)
        check(result == expectedResult && event.slotDigest == slotWrite.payloadDigest && event.originalBodyDigest == null &&
            event.resolutionBodyDigest == null && event.originalUnknownApplicationOperationId == null)
        val writes = FoodRefundWrites(original.scope, original.operationId, original.originalActorId, original.recordedAt, inputs, cashInput)
        writes.refund(after)
        val proposal = writes.finish(expectedResult, listOf(foodPaymentCashWrite(cashAfter, cashInput.storageRevision)) + gated,
            listOf(ClaimChange.Acquire(LocalSalesStoreProfile.unique("refund_attempt", target.attemptId), target.refundId)) +
                foodRefundLineageClaims(requirement), foodRefundOriginalReadWrites(original), slotDigest = slotWrite.payloadDigest)
        frame.requireCompleteLeaves(event.after)
        foodRefundRequireExactEffects(original, proposal)
        val resources = financial + foodRefundAuthorityResources(requirement) + recordingAuthority(original.originalActorId)
        check(resources.size <= 512)
        return FoodRefundStartOriginal(slot, result, cut, proposal, resources)
    }

    internal suspend fun requireRaw(original: RetainedCommandEvidence,
        historicalStart: RetainedCommandEvidence): FoodRefundRawOriginal {
        foodRefundRequireOriginalHeader(original, employee = original.nativeOriginalProvenance == null)
        val request = LocalFoodRefundCodec.decode(original.normalized) as? LocalFoodRefundRequest.RecordOriginal
            ?: error("food.refund.raw_required")
        val start = requireStart(historicalStart)
        val slot = start.slot
        val requirement = slot.requirement()
        foodRefundRequireEarlier(historicalStart, original)
        check(request.target == slot.target && original.scope == slot.scope && original.operationId == slot.target.recordingOperationId &&
            original.originalActorId == slot.originalActorId && historicalStart.operationId == slot.target.intentOperationId)
        LocalFoodRefundCashJournalDomain.classifyOriginal(requirement, original.normalized)
        val stored = original.aggregateWrites.single { it.key == foodRefundRawKey(original.operationId) }
        check(stored.storageGeneration == Revision(1) && stored.schema == LocalFoodRefundProfile.schema(LocalFoodRefundProfile.ORIGINAL))
        val raw = localSalesDecode(stored.payload, LocalFoodRefundRaw.serializer(), LocalFoodRefundProfile.MAX_RAW_BYTES)
        val expected = LocalFoodRefundRaw(1, original.scope, request.target, slot.originalActorId, slot.originalActorId,
            original.normalized.copyBytes().decodeToString(throwOnInvalidSequence = true), sha256(original.normalized.copyBytes()),
            original.semanticDigest, sha256(foodRefundSlotWrite(slot).payload.copyBytes()), original.recordedAt)
        check(raw == expected)
        if (original.nativeOriginalProvenance != null) requireFoodRefundNativeOriginalEvidence(original, historicalStart, raw, slot)
        val changes = listOf(foodRefundRawWrite(expected), foodRefundApplicationWrite(foodRefundInitialApplication(expected), null))
        val proposal = ProposedOutcome.Apply(LocalFoodRefundCodec.result(LocalFoodRefundResult.RawRetained(slot.target)),
            if (original.nativeOriginalProvenance == null) changes else changes.sortedWith(compareBy({ it.key.kind }, { it.key.id.value })))
        foodRefundRequireExactEffects(original, proposal)
        return FoodRefundRawOriginal(raw, slot, proposal)
    }

    internal suspend fun requireApply(original: RetainedCommandEvidence, historicalStart: RetainedCommandEvidence,
        originalRaw: RetainedCommandEvidence): FoodRefundAppliedOriginal {
        foodRefundRequireOriginalHeader(original, employee = true)
        val selection = foodRefundApplySelection(original.command, original.normalized)
        check(selection.phase == LocalFoodRefundFactPhase.ORIGINAL)
        val rawOriginal = requireRaw(originalRaw, historicalStart)
        val slot = rawOriginal.slot
        val raw = rawOriginal.raw
        val outcome = (LocalFoodRefundCodec.decode(raw.body()) as LocalFoodRefundRequest.RecordOriginal).outcome
        foodRefundRequireEarlier(originalRaw, original)
        check(selection.factOperationId == slot.target.recordingOperationId && original.originalActorId == slot.originalActorId &&
            original.originalActorId == raw.recordingActorId &&
            original.operationId !in setOf(slot.target.intentOperationId, slot.target.recordingOperationId, slot.target.resolutionOperationId))
        requireFoodRefundApplicableOutcome(outcome, slot.domain.amount)
        val event = foodRefundStoredEvent(original)
        val frame = FoodFinancialBundleReaderV2(original, event.framing, allowStockInputs = true)
        val inputs = foodRefundInputs(original, frame)
        val cashInput = checkNotNull(frame.cash())
        val observed = StockReadEvidenceV1.read(original) ?: error("food.refund.read_evidence_required")
        val sources = FoodRefundSources(observed)
        val cut = sources.refund(inputs, cashInput)
        requireFoodRefundApplicationCut(cut, slot)
        sources.applicationVacancy(slot.target.attemptId); sources.finish()
        check(event.slotDigest == raw.slotDigest && event.originalBodyDigest == raw.bodyDigest &&
            event.resolutionBodyDigest == null && event.originalUnknownApplicationOperationId == null)
        val after = foodRefundAfterRefund(original, frame, cut.refund.id)
        val cashAfter = if (outcome is LocalFoodRefundOutcome.Unknown) cut.cash
            else foodPaymentAfterCash(original, cut.cash.id, cashInput.storageRevision.next())
        val movement = FoodRefundCashHistory.requireApplied(cut.refund, after, cut.cash, cashAfter, slot.target.attemptId,
            raw.target.recordingOperationId, original.operationId, slot.originalActorId,
            foodRefundOutcome(outcome, raw.recordingActorId), resolvingUnknown = false)
        val financialRevisions = listOf(LocalFoodStorageRevisionV1(LocalFoodFinancialProfileV1.REFUND, cut.refund.id, cut.refundEnvelope.revision.next())) +
            listOfNotNull(if (outcome is LocalFoodRefundOutcome.Unknown) null else LocalFoodStorageRevisionV1(
                CashNonSaleStoreProfile.SESSION_KIND, cut.cash.id, cashInput.storageRevision.next()))
        val application = foodRefundInitialApplication(raw).copy(revision = Revision(1), state = LocalFoodRefundApplicationState.APPLIED,
            reason = null, updatedAt = original.recordedAt, lastApplicationOperationId = original.operationId,
            action = foodRefundAction(outcome), financialRevisions = financialRevisions)
        val result = LocalFoodRefundResult.Applied(slot.target, checkNotNull(application.action), original.operationId,
            financialRevisions + LocalFoodStorageRevisionV1(LocalFoodRefundProfile.APPLICATION,
                DomainId(slot.target.recordingOperationId.value), Revision(2)))
        val extra = listOfNotNull(if (outcome is LocalFoodRefundOutcome.Unknown) null else foodPaymentCashWrite(cashAfter, cashInput.storageRevision),
            movement?.let { cashMovementLiteralWrite(original.scope, original.operationId, original.originalActorId, it, original.recordedAt) }) +
            foodRefundApplicationWrite(application, Revision(1))
        val record = after.attempts.single { it.id == slot.target.attemptId }.refund
        val claims = listOfNotNull(record?.let { ClaimChange.Acquire(LocalSalesStoreProfile.unique("refund_record", it.attemptId), after.id) },
            movement?.let { ClaimChange.Acquire(CashNonSaleStoreProfile.cashSourceClaim(it.sourceId), cut.cash.id) })
        val writes = FoodRefundWrites(original.scope, original.operationId, original.originalActorId, original.recordedAt, inputs, cashInput)
        writes.refund(after)
        val proposal = writes.finish(result, extra, claims, foodRefundOriginalReadWrites(original),
            slotDigest = raw.slotDigest, originalBodyDigest = raw.bodyDigest)
        frame.requireCompleteLeaves(event.after)
        foodRefundRequireExactEffects(original, proposal)
        val resources = slot.domain.financialResources.toSet() + cut.financialResources +
            foodRefundAuthorityResources(slot.requirement()) + recordingAuthority(original.originalActorId)
        check(resources.size <= 512)
        return FoodRefundAppliedOriginal(application, cut, proposal, resources)
    }
}

internal fun requireFoodRefundApplicationCut(cut: FoodRefundLiteralCut, slot: LocalFoodRefundSlot,
    phase: LocalFoodRefundFactPhase = LocalFoodRefundFactPhase.ORIGINAL, originalUnknown: LocalFoodRefundRaw? = null) {
    check(foodRefundApplicationAttemptMatches(cut.refund, cut.cash, slot, phase, originalUnknown))
}
internal fun requireFoodRefundApplicableOutcome(outcome: LocalFoodRefundOutcome, expectedAmount: Money) {
    check(outcome !is LocalFoodRefundOutcome.ExternalAttested &&
        (outcome !is LocalFoodRefundOutcome.CashPaid || outcome.amount == expectedAmount))
}
internal fun foodRefundRequireOriginalHeader(original: RetainedCommandEvidence, employee: Boolean) {
    check(original.committed && original.command == LocalFoodRefundProfile.COMMAND &&
        (original.nativeOriginalProvenance == null) == employee && LocalFoodRefundCodec.normalize(original.normalized) == original.normalized &&
        semanticDigest(original.scope, original.originalActorId, original.command, original.normalized) == original.semanticDigest &&
        sha256(original.result.copyBytes()) == original.resultHash &&
        original.aggregateWrites.map { it.key }.distinct().size == original.aggregateWrites.size &&
        original.aggregateWrites.all { sha256(it.payload.copyBytes()) == it.payloadDigest } && original.jobs.isEmpty())
    foodCapacity(foodProducerReadBytes(original) <= FoodReadLimits.FOOD_PRODUCER_BYTES)
}
internal fun foodRefundInsert(write: RetainedAggregateWrite): AggregateChange {
    check(write.storageGeneration == Revision(1) && write.payloadDigest == sha256(write.payload.copyBytes()))
    return AggregateChange(write.key, null, write.schema, write.payload)
}
internal fun foodRefundOriginalReadWrites(original: RetainedCommandEvidence): List<AggregateChange> =
    original.aggregateWrites.filter { it.key.kind == StockReadEvidenceV1.KIND }.map {
        check(it.schema == StockReadEvidenceV1.SCHEMA); foodRefundInsert(it)
    }
internal fun foodRefundAfterRefund(original: RetainedCommandEvidence, frames: FoodFinancialBundleReaderV2,
    id: DomainId): FoodRefundObligation {
    val row = original.aggregateWrites.single { it.key == AggregateKey(LocalFoodFinancialProfileV1.REFUND, id) }
    check(row.schema == foodFinancialSchemaV2(row.key.kind) && row.storageGeneration.value >= 2)
    return foodFinancialRefund(frames.document(row.payload)).also { check(it.revision.next() == row.storageGeneration) }
}
internal fun foodRefundRequireExactEffects(original: RetainedCommandEvidence, expected: ProposedOutcome.Apply) {
    // Generic literal equality only; the distinct refund pure values/proposal above supply monetary authority.
    foodPaymentRequireExactEffects(original, expected)
}
internal fun foodRefundRequireEarlier(earlier: RetainedCommandEvidence, later: RetainedCommandEvidence) {
    check(earlier.committed && later.committed && earlier.scope == later.scope && earlier.operationId != later.operationId &&
        (earlier.assignmentId != later.assignmentId || earlier.authorityEpoch != later.authorityEpoch || earlier.sequence < later.sequence))
}

/** Actual retained ancestors only. No current slot/application can substitute for history. */
internal class FoodRefundRetainedLineage(val start: RetainedCommandEvidence,
    val originalRaw: RetainedCommandEvidence? = null, val resolutionRaw: RetainedCommandEvidence? = null,
    val originalUnknownApplication: RetainedCommandEvidence? = null)
internal suspend fun foodRefundRetainedLineage(original: RetainedCommandEvidence,
    lookup: suspend (OperationId) -> RetainedCommandEvidence): FoodRefundRetainedLineage {
    suspend fun read(id: OperationId): RetainedCommandEvidence = lookup(id).also {
        check(it.committed && it.scope == original.scope && it.operationId == id)
    }
    var raw: RetainedCommandEvidence? = null
    var resolution: RetainedCommandEvidence? = null
    var application: FoodRefundApplySelection? = null
    val target = when (val request = LocalFoodRefundCodec.decode(original.normalized)) {
        is LocalFoodRefundRequest.StartCash -> return FoodRefundRetainedLineage(original)
        is LocalFoodRefundRequest.RecordOriginal -> request.target
        is LocalFoodRefundRequest.RecordResolution -> { raw = read(request.target.recordingOperationId); request.target }
        is LocalFoodRefundRequest.ApplyOriginal -> {
            application = foodRefundApplySelection(original.command, original.normalized)
            val fact = read(request.recordingOperationId); raw = fact
            (LocalFoodRefundCodec.decode(fact.normalized) as LocalFoodRefundRequest.RecordOriginal).target
        }
        is LocalFoodRefundRequest.ApplyResolution -> {
            application = foodRefundApplySelection(original.command, original.normalized)
            val fact = read(request.resolutionOperationId); resolution = fact
            (LocalFoodRefundCodec.decode(fact.normalized) as LocalFoodRefundRequest.RecordResolution).target
        }
    }
    if (application?.phase == LocalFoodRefundFactPhase.RESOLUTION && raw == null) raw = read(target.recordingOperationId)
    val start = read(target.intentOperationId)
    raw?.let { foodRefundRequireEarlier(start, it); foodRefundRequireEarlier(it, original) }
        ?: foodRefundRequireEarlier(start, original)
    resolution?.let { foodRefundRequireEarlier(checkNotNull(raw), it); foodRefundRequireEarlier(it, original) }
    val unknownApplication = if (application?.phase == LocalFoodRefundFactPhase.RESOLUTION)
        read(checkNotNull(foodRefundStoredEvent(original).originalUnknownApplicationOperationId)).also {
            foodRefundRequireEarlier(checkNotNull(raw), it); foodRefundRequireEarlier(it, original)
        } else null
    return FoodRefundRetainedLineage(start, raw, resolution, unknownApplication)
}

/** Current genuine original-only traversal into BOTH authentic cancellation/funding roots. */
internal suspend fun requireFoodRefundOriginalCustody(view: CommandReadView,
    original: RetainedCommandEvidence, stock: LocalStockRepository) {
    check(view.scope == original.scope && commerceRepository(view) === stock)
    val lineage = foodRefundRetainedLineage(original) { id ->
        view.retainedCommand(id) ?: error("food.refund.original_ancestor_missing")
    }
    LocalFoodRefundProducer.requireComplete(original, lineage.start, lineage.originalRaw,
        lineage.resolutionRaw, lineage.originalUnknownApplication)
    if (lineage.start.operationId != original.operationId) foodReadView(view).requireFoodPredecessors(lineage.start)
    lineage.originalUnknownApplication?.let { foodReadView(view).requireFoodPredecessors(it) }
}
internal fun isFoodRefundFinancialProducer(original: RetainedCommandEvidence): Boolean {
    if (original.command != LocalFoodRefundProfile.COMMAND || original.nativeOriginalProvenance != null) return false
    return when (LocalFoodRefundCodec.decode(original.normalized)) {
        is LocalFoodRefundRequest.StartCash, is LocalFoodRefundRequest.ApplyOriginal, is LocalFoodRefundRequest.ApplyResolution -> true
        is LocalFoodRefundRequest.RecordOriginal, is LocalFoodRefundRequest.RecordResolution -> false
    }
}

/** Exact normalized cancellation/financial or original PRICE_BATCH input after the actual whole FOOD owner. */
internal suspend fun requireFoodRefundStreamedInput(original: RetainedStreamedCommand, input: FoodFinancialInputV1) {
    check(input.stock == null && input.schema == foodStockSchema(input.kind) &&
        original.identity.producer.operationId == input.operationId && original.identity.producer.command == StockStreamingCommandsV2.FOOD)
    var matched = false
    original.frames { frame -> if (frame is RetainedCommandFrameV2.Write && frame.value.key == input.key()) {
        val row = frame.value
        check(!matched && row.storageGeneration == input.revision && row.schema == input.schema && row.payload == input.bytes() &&
            row.payloadDigest == input.digest && row.payloadDigest == sha256(row.payload.copyBytes()))
        matched = true
    } }
    check(matched) { "food.refund.normalized_input_missing" }
}
