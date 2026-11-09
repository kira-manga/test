package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

internal class FoodPaymentStartOriginal(val slot: LocalFoodPaymentSlot, val result: LocalFoodPaymentResult.AttemptStarted,
    val cut: FoodPaymentLiteralCut, val stock: LocalStockPaymentCustody, val proposal: ProposedOutcome.Apply,
    val resources: Set<RecoveryResourceKey>)
internal class FoodPaymentRawOriginal(val raw: LocalFoodPaymentRaw, val slot: LocalFoodPaymentSlot,
    val proposal: ProposedOutcome.Apply)
internal class FoodPaymentAppliedOriginal(val application: LocalFoodPaymentApplication,
    val cut: FoodPaymentLiteralCut, val proposal: ProposedOutcome.Apply, val resources: Set<RecoveryResourceKey>)

/**
 * Complete literal producer comparison, with the SAME pure FOOD/cash values as production.
 * No handler/authorization/current view or retained evidence is constructed. Start has all
 * its native/stock literals; raw and Apply additionally require the actual retained ancestor
 * bytes. The separate current/original-only/runtime traversals below authenticate custody.
 */
public object LocalFoodPaymentProducer {
    public suspend fun requireComplete(original: RetainedCommandEvidence,
        historicalStart: RetainedCommandEvidence? = null, originalRaw: RetainedCommandEvidence? = null,
        resolutionRaw: RetainedCommandEvidence? = null,
        originalUnknownApplication: RetainedCommandEvidence? = null): List<LocalFoodRecipeSnapshotV1> {
        if (original.command == LocalFoodPaymentSupervisorCodec.COMMAND) {
            val selection = foodPaymentApplySelection(original.command, original.normalized)
            val start = checkNotNull(historicalStart)
            val proof = if (selection.phase == LocalFoodPaymentFactPhase.ORIGINAL)
                requireApply(original, start, checkNotNull(originalRaw))
                else LocalFoodPaymentResolutionProducer.requireApply(original, start, checkNotNull(originalRaw),
                    checkNotNull(resolutionRaw), checkNotNull(originalUnknownApplication))
            return proof.cut.portions.map { (it.body as FoodBodyV1.Portion).recipe }
        }
        return when (LocalFoodPaymentCodec.decode(original.normalized)) {
            is LocalFoodPaymentRequest.StartCash -> requireStart(original).cut.portions.map { (it.body as FoodBodyV1.Portion).recipe }
            is LocalFoodPaymentRequest.RecordOriginal -> {
                val start = checkNotNull(historicalStart)
                requireRaw(original, start)
                requireStart(start).cut.portions.map { (it.body as FoodBodyV1.Portion).recipe }
            }
            is LocalFoodPaymentRequest.RecordResolution -> {
                val start = checkNotNull(historicalStart)
                LocalFoodPaymentResolutionProducer.requireRaw(original, start, checkNotNull(originalRaw))
                requireStart(start).cut.portions.map { (it.body as FoodBodyV1.Portion).recipe }
            }
            is LocalFoodPaymentRequest.ApplyOriginal -> requireApply(original, checkNotNull(historicalStart),
                checkNotNull(originalRaw)).cut.portions.map { (it.body as FoodBodyV1.Portion).recipe }
            is LocalFoodPaymentRequest.ApplyResolution -> LocalFoodPaymentResolutionProducer.requireApply(original,
                checkNotNull(historicalStart), checkNotNull(originalRaw), checkNotNull(resolutionRaw),
                checkNotNull(originalUnknownApplication)).cut.portions.map { (it.body as FoodBodyV1.Portion).recipe }
        }
    }

    internal suspend fun requireStart(original: RetainedCommandEvidence): FoodPaymentStartOriginal {
        foodPaymentRequireOriginalHeader(original, employee = true)
        val request = LocalFoodPaymentCodec.decode(original.normalized) as? LocalFoodPaymentRequest.StartCash
            ?: error("food.payment.start_required")
        val result = LocalFoodPaymentCodec.result(original.result) as? LocalFoodPaymentResult.AttemptStarted
            ?: error("food.payment.start_result_required")
        val event = foodPaymentStoredEvent(original)
        val frame = FoodFinancialBundleReaderV2(original, event.framing, allowStockInputs = true)
        val inputs = foodPaymentInputs(original, frame)
        val cashInput = checkNotNull(frame.cash())
        val observed = StockReadEvidenceV1.read(original) ?: error("food.payment.read_evidence_required")
        val sources = FoodPaymentSources(observed)
        val cut = sources.collection(inputs, cashInput)
        val slotWrite = original.aggregateWrites.single { it.key == foodPaymentSlotKey(request.recordingOperationId) }
        check(slotWrite.storageGeneration == Revision(1) && slotWrite.schema == LocalFoodPaymentProfile.schema(LocalFoodPaymentProfile.SLOT))
        val slot = localSalesDecode(slotWrite.payload, LocalFoodPaymentSlot.serializer(), LocalFoodPaymentProfile.MAX_SLOT_BYTES)
        val requirement = slot.requirement()
        LocalFoodCashJournalDomain.validateRequirement(requirement)
        val target = request.target(cut.orderEnvelope.id, original.operationId)
        check(slot.version == 1 && slot.scope == original.scope && slot.originalActorId == original.originalActorId && slot.target == target &&
            slot.intentSemanticDigest == original.semanticDigest && foodPaymentSlotWrite(slot).payload == slotWrite.payload &&
            slot.domain.orderStorageRevision == request.expectedOrderStorageRevision &&
            slot.domain.collectionRevision == request.expectedCollectionRevision && slot.domain.sessionRevision == request.expectedSessionRevision &&
            slot.domain.amount == request.amount && slot.domain.authority.assignmentId == original.assignmentId &&
            slot.domain.authority.epoch == original.authorityEpoch && slot.domain.financialResources == foodPaymentSortedResources(cut.financialResources) &&
            cut.orderEnvelope.revision == request.expectedOrderStorageRevision && cut.collection.revision == request.expectedCollectionRevision &&
            cut.cash.revision == request.expectedSessionRevision && cut.cash.id == request.sessionId &&
            cut.order.ownerId == original.originalActorId && cut.cash.custodianId == original.originalActorId && cut.cash.status == CashSessionStatus.OPEN)
        val inventory = LocalStockPaymentObservations.paymentCustody(observed, cut.reservedReservationIds, cut.issuedReservationIds)
        requireFoodPaymentCustody(cut, inventory)
        val deadline = foodPaymentDeadline(inventory.reserved.values)
        check(inventory.reserved.values.all { it.safeAt(original.recordedAt) } && slot.domain.stockDeadlineExclusive == deadline &&
            slot.domain.stockSourcesDigest == foodPaymentStockDigest(observed))
        sources.startVacancy(requirement); sources.finish(stock = true)
        val collectionAfter = foodPaymentAfterCollection(original, frame, cut.collection.id)
        val cashAfter = foodPaymentAfterCash(original, cut.cash.id, cashInput.storageRevision.next())
        FoodCashHistory.requireStarted(cut.collection, collectionAfter, cut.cash, cashAfter, original.operationId,
            original.originalActorId, request.expectedCollectionRevision, request.expectedSessionRevision, request.attemptId,
            request.amount, request.payerReference)
        val nativeSources = original.aggregateWrites.filter { it.key.kind == PhysicalRecordingSourceProfileV1.KIND }.map { foodPaymentInsert(it) }
        val pair = requireFoodPaymentSourcePair(requirement, nativeSources)
        check(slot.masterSource == LocalFoodPaymentSourceReference.from(pair.first) && slot.clientSource == LocalFoodPaymentSourceReference.from(pair.second))
        val gated = nativeSources + foodPaymentSlotWrite(slot) + foodPaymentFences(requirement)
        val revisions = listOf(LocalFoodStorageRevisionV1(LocalFoodFinancialProfileV1.COLLECTION, cut.collection.id, cut.collectionEnvelope.revision.next()),
            LocalFoodStorageRevisionV1(CashNonSaleStoreProfile.SESSION_KIND, cut.cash.id, cashInput.storageRevision.next()),
            LocalFoodStorageRevisionV1(LocalFoodPaymentProfile.SLOT, DomainId(target.recordingOperationId.value), Revision(1)))
        val expectedResult = LocalFoodPaymentResult.AttemptStarted(target, request.amount, sha256(requirement.canonicalBytes.copyBytes()), deadline, revisions)
        check(result == expectedResult && event.slotDigest == slotWrite.payloadDigest && event.originalBodyDigest == null)
        val writes = FoodPaymentWrites(original.scope, original.operationId, original.originalActorId, original.recordedAt, inputs, cashInput)
        writes.collection(collectionAfter)
        val proposal = writes.finish(expectedResult, listOf(foodPaymentCashWrite(cashAfter, cashInput.storageRevision)) + gated,
            listOf(ClaimChange.Acquire(LocalSalesStoreProfile.unique("payment_attempt", target.attemptId), target.collectionId)) +
                foodPaymentLineageClaims(requirement), foodPaymentOriginalReadWrites(original), deadline, slotWrite.payloadDigest)
        frame.requireCompleteLeaves(event.after)
        foodPaymentRequireExactEffects(original, proposal)
        val resources = cut.financialResources + LocalStockPaymentObservations.resources(observed) +
            foodPaymentAuthorityResources(requirement) + recordingAuthority(original.originalActorId)
        check(resources.size <= 512)
        return FoodPaymentStartOriginal(slot, result, cut, inventory, proposal, resources)
    }

    internal suspend fun requireRaw(original: RetainedCommandEvidence, historicalStart: RetainedCommandEvidence): FoodPaymentRawOriginal {
        foodPaymentRequireOriginalHeader(original, employee = original.nativeOriginalProvenance == null)
        val request = LocalFoodPaymentCodec.decode(original.normalized) as? LocalFoodPaymentRequest.RecordOriginal
            ?: error("food.payment.raw_required")
        val start = requireStart(historicalStart)
        val slot = start.slot
        val requirement = slot.requirement()
        check(request.target == slot.target && original.scope == slot.scope && original.operationId == slot.target.recordingOperationId &&
            original.originalActorId == slot.originalActorId && historicalStart.operationId == slot.target.intentOperationId)
        LocalFoodCashJournalDomain.classifyOriginal(requirement, original.normalized)
        val stored = original.aggregateWrites.single { it.key == foodPaymentRawKey(original.operationId) }
        check(stored.storageGeneration == Revision(1) && stored.schema == LocalFoodPaymentProfile.schema(LocalFoodPaymentProfile.ORIGINAL))
        val raw = localSalesDecode(stored.payload, LocalFoodPaymentRaw.serializer(), LocalFoodPaymentProfile.MAX_RAW_BYTES)
        val expected = LocalFoodPaymentRaw(1, original.scope, request.target, slot.originalActorId, slot.originalActorId,
            original.normalized.copyBytes().decodeToString(throwOnInvalidSequence = true), sha256(original.normalized.copyBytes()), original.semanticDigest,
            sha256(foodPaymentSlotWrite(slot).payload.copyBytes()), original.recordedAt)
        check(raw == expected)
        if (original.nativeOriginalProvenance != null) requireFoodPaymentNativeOriginalEvidence(original, historicalStart, raw, slot)
        val changes = listOf(foodPaymentRawWrite(expected), foodPaymentApplicationWrite(foodPaymentInitialApplication(expected), null))
        // The CLOSED native retained codec canonically sorts its two writes; genuine employee
        // commands keep their original proposal order. Neither provenance is reinterpreted.
        val proposal = ProposedOutcome.Apply(LocalFoodPaymentCodec.result(LocalFoodPaymentResult.RawRetained(slot.target)),
            if (original.nativeOriginalProvenance == null) changes else changes.sortedWith(compareBy({ it.key.kind }, { it.key.id.value })))
        foodPaymentRequireExactEffects(original, proposal)
        return FoodPaymentRawOriginal(raw, slot, proposal)
    }

    internal suspend fun requireApply(original: RetainedCommandEvidence, historicalStart: RetainedCommandEvidence,
        originalRaw: RetainedCommandEvidence): FoodPaymentAppliedOriginal {
        foodPaymentRequireOriginalHeader(original, employee = true)
        val selection = foodPaymentApplySelection(original.command, original.normalized)
        check(selection.phase == LocalFoodPaymentFactPhase.ORIGINAL)
        val rawOriginal = requireRaw(originalRaw, historicalStart)
        val slot = rawOriginal.slot
        val raw = rawOriginal.raw
        val outcome = (LocalFoodPaymentCodec.decode(raw.body()) as LocalFoodPaymentRequest.RecordOriginal).outcome
        check(selection.factOperationId == slot.target.recordingOperationId &&
            (selection.supervised || original.originalActorId == slot.originalActorId) &&
            original.operationId !in setOf(slot.target.intentOperationId, slot.target.recordingOperationId, slot.target.resolutionOperationId) &&
            outcome !is LocalFoodCashOutcome.ExternalAttested)
        val event = foodPaymentStoredEvent(original)
        val frame = FoodFinancialBundleReaderV2(original, event.framing, allowStockInputs = true)
        val inputs = foodPaymentInputs(original, frame)
        val cashInput = checkNotNull(frame.cash())
        val observed = StockReadEvidenceV1.read(original) ?: error("food.payment.read_evidence_required")
        val sources = FoodPaymentSources(observed)
        val cut = sources.collection(inputs, cashInput)
        requireFoodPaymentApplicationCut(cut, slot)
        sources.applicationVacancy(slot.target.attemptId); sources.finish(stock = false)
        check(event.slotDigest == raw.slotDigest && event.originalBodyDigest == raw.bodyDigest && event.resolutionBodyDigest == null &&
            event.originalUnknownApplicationOperationId == null)
        val after = foodPaymentAfterCollection(original, frame, cut.collection.id)
        val cashAfter = if (outcome is LocalFoodCashOutcome.Unknown) cut.cash
            else foodPaymentAfterCash(original, cut.cash.id, cashInput.storageRevision.next())
        val observedOutcome = foodPaymentOutcome(outcome, slot.originalActorId)
        val movement = if (selection.supervised) FoodCashHistory.requireSupervisedApplied(cut.collection, after, cut.cash, cashAfter,
            slot.target.attemptId, raw.target.recordingOperationId, raw.recordingActorId, original.operationId,
            original.originalActorId, null, null, observedOutcome)
            else FoodCashHistory.requireApplied(cut.collection, after, cut.cash, cashAfter, slot.target.attemptId,
                raw.target.recordingOperationId, original.operationId, slot.originalActorId, observedOutcome)
        val financialRevisions = listOf(LocalFoodStorageRevisionV1(LocalFoodFinancialProfileV1.COLLECTION, cut.collection.id, cut.collectionEnvelope.revision.next())) +
            listOfNotNull(if (outcome is LocalFoodCashOutcome.Unknown) null else LocalFoodStorageRevisionV1(CashNonSaleStoreProfile.SESSION_KIND, cut.cash.id, cashInput.storageRevision.next()))
        val application = foodPaymentInitialApplication(raw).copy(revision = Revision(1), state = LocalFoodPaymentApplicationState.APPLIED,
            reason = null, updatedAt = original.recordedAt, lastApplicationOperationId = original.operationId,
            action = foodPaymentAction(outcome), financialRevisions = financialRevisions)
        val result = LocalFoodPaymentResult.Applied(slot.target, checkNotNull(application.action), original.operationId,
            financialRevisions + LocalFoodStorageRevisionV1(LocalFoodPaymentProfile.APPLICATION, DomainId(slot.target.recordingOperationId.value), Revision(2)))
        val extra = listOfNotNull(if (outcome is LocalFoodCashOutcome.Unknown) null else foodPaymentCashWrite(cashAfter, cashInput.storageRevision),
            movement?.let { if (selection.supervised) foodPaymentAccountedMovementWrite(original.scope, original.operationId,
                original.originalActorId, slot, LocalFoodPaymentFactPhase.ORIGINAL, raw.recordingActorId, raw.bodyDigest, it, original.recordedAt)
                else cashMovementLiteralWrite(original.scope, original.operationId, original.originalActorId, it, original.recordedAt) }) +
            foodPaymentApplicationWrite(application, Revision(1))
        val payment = after.attempts.single { it.id == slot.target.attemptId }.payment
        val claims = listOfNotNull(payment?.let { ClaimChange.Acquire(LocalSalesStoreProfile.unique("payment_record", it.attemptId), after.id) },
            movement?.let { ClaimChange.Acquire(CashNonSaleStoreProfile.cashSourceClaim(it.sourceId), cut.cash.id) })
        val writes = FoodPaymentWrites(original.scope, original.operationId, original.originalActorId, original.recordedAt, inputs, cashInput)
        writes.collection(after)
        val proposal = writes.finish(result, extra, claims, foodPaymentOriginalReadWrites(original), slotDigest = raw.slotDigest, originalBodyDigest = raw.bodyDigest)
        frame.requireCompleteLeaves(event.after)
        foodPaymentRequireExactEffects(original, proposal)
        val resources = slot.domain.financialResources.toSet() + cut.financialResources + foodPaymentAuthorityResources(slot.requirement()) +
            recordingAuthority(original.originalActorId)
        check(resources.size <= 512)
        return FoodPaymentAppliedOriginal(application, cut, proposal, resources)
    }
}

internal fun requireFoodPaymentApplicationCut(cut: FoodPaymentLiteralCut, slot: LocalFoodPaymentSlot,
    phase: LocalFoodPaymentFactPhase = LocalFoodPaymentFactPhase.ORIGINAL, originalUnknown: LocalFoodPaymentRaw? = null) {
    check(cut.order.ownerId == slot.originalActorId &&
        foodPaymentApplicationAttemptMatches(cut.collection, cut.cash, slot, phase, originalUnknown))
}

internal fun foodPaymentRequireOriginalHeader(original: RetainedCommandEvidence, employee: Boolean) {
    check(original.committed && original.command in foodPaymentCommands && (original.nativeOriginalProvenance == null) == employee &&
        foodPaymentNormalize(original.command, original.normalized) == original.normalized &&
        semanticDigest(original.scope, original.originalActorId, original.command, original.normalized) == original.semanticDigest &&
        sha256(original.result.copyBytes()) == original.resultHash && original.aggregateWrites.map { it.key }.distinct().size == original.aggregateWrites.size &&
        original.aggregateWrites.all { sha256(it.payload.copyBytes()) == it.payloadDigest } && original.jobs.isEmpty())
    foodCapacity(foodProducerReadBytes(original) <= FoodReadLimits.FOOD_PRODUCER_BYTES)
}

internal fun foodPaymentInsert(write: RetainedAggregateWrite): AggregateChange {
    check(write.storageGeneration == Revision(1) && write.payloadDigest == sha256(write.payload.copyBytes()))
    return AggregateChange(write.key, null, write.schema, write.payload)
}
internal fun foodPaymentOriginalReadWrites(original: RetainedCommandEvidence): List<AggregateChange> =
    original.aggregateWrites.filter { it.key.kind == StockReadEvidenceV1.KIND }.map {
        check(it.schema == StockReadEvidenceV1.SCHEMA); foodPaymentInsert(it)
    }
internal fun foodPaymentAfterCollection(original: RetainedCommandEvidence, frames: FoodFinancialBundleReaderV2,
    collectionId: DomainId): FoodCollection {
    val row = original.aggregateWrites.single { it.key == AggregateKey(LocalFoodFinancialProfileV1.COLLECTION, collectionId) }
    check(row.schema == foodFinancialSchemaV2(row.key.kind) && row.storageGeneration.value >= 2)
    return foodFinancialCollection(frames.document(row.payload)).also { check(it.revision.next() == row.storageGeneration) }
}
internal fun foodPaymentAfterCash(original: RetainedCommandEvidence, id: DomainId, generation: Revision): CashSession {
    val row = original.aggregateWrites.single { it.key == CashNonSaleStoreProfile.sessionKey(id) }
    check(row.schema == CashNonSaleStoreProfile.SESSION_SCHEMA && row.storageGeneration == generation)
    val text = row.payload.copyBytes().decodeToString(throwOnInvalidSequence = true)
    return SalesSnapshotCodec.decodeCashSession(text, SalesSnapshotBinding(SalesSnapshotKind.CASH_SESSION, original.scope, id, Revision(generation.value - 1)))
        .also { check(SalesSnapshotCodec.encodeCashSession(it) == text) }
}
internal fun foodPaymentRequireExactEffects(original: RetainedCommandEvidence, expected: ProposedOutcome.Apply) {
    check(original.result == expected.result && original.aggregateWrites.size == expected.changes.size &&
        original.claimChanges.size == expected.claims.size && original.jobs.size == expected.jobs.size)
    for ((row, change) in original.aggregateWrites.zip(expected.changes)) check(row.key == change.key &&
        row.storageGeneration == (change.expectedRevision?.next() ?: Revision(1)) && row.schema == change.schema && row.payload == change.payload)
    for ((claim, change) in original.claimChanges.zip(expected.claims)) check(claim.key == change.key && claim.ownerId == change.ownerId &&
        claim.action == if (change is ClaimChange.Acquire) RetainedClaimAction.ACQUIRE else RetainedClaimAction.RELEASE)
    for ((job, wanted) in original.jobs.zip(expected.jobs)) check(job.kind == wanted.kind && job.body == wanted.body)
}

/** Actual retained-only custody traversal; no current slot/application is borrowed as history. */
internal suspend fun requireFoodPaymentOriginalCustody(view: CommandReadView, original: RetainedCommandEvidence,
    stock: LocalStockRepository): List<LocalFoodRecipeSnapshotV1> {
    check(view.scope == original.scope)
    val lineage = foodPaymentRetainedLineage(original) { id ->
        view.retainedCommand(id) ?: error("food.payment.original_ancestor_missing")
    }
    val proof = LocalFoodPaymentProducer.requireStart(lineage.start)
    LocalStockPaymentObservations.requireOriginalStock(view, stock,
        checkNotNull(StockReadEvidenceV1.read(lineage.start)), lineage.start)
    for (row in proof.cut.portions) {
        val portion = row.body as FoodBodyV1.Portion
        val entry = proof.cut.collection.portions.single { it.portionId == row.id }
        proof.stock.issued[entry.reservationId]?.let { issued ->
            requireFoodFirstIssue(portion, LocalStockPaymentObservations.requireIssuedOriginal(view, stock, issued))
        }
    }
    if (lineage.start.operationId != original.operationId) foodReadView(view).requireFoodPredecessors(lineage.start)
    lineage.originalUnknownApplication?.let { foodReadView(view).requireFoodPredecessors(it) }
    return if (lineage.start.operationId == original.operationId) proof.cut.portions.map { (it.body as FoodBodyV1.Portion).recipe }
        else LocalFoodPaymentProducer.requireComplete(original, lineage.start, lineage.originalRaw,
            lineage.resolutionRaw, lineage.originalUnknownApplication)
}


/** Closed genuine FOOD financial roots. Raw/native facts and supervisor Review never qualify. */
internal fun isFoodPaymentFinancialProducer(original: RetainedCommandEvidence): Boolean {
    if (original.nativeOriginalProvenance != null) return false
    return when (original.command) {
        LocalFoodPaymentProfile.COMMAND -> when (LocalFoodPaymentCodec.decode(original.normalized)) {
            is LocalFoodPaymentRequest.StartCash, is LocalFoodPaymentRequest.ApplyOriginal, is LocalFoodPaymentRequest.ApplyResolution -> true
            is LocalFoodPaymentRequest.RecordOriginal, is LocalFoodPaymentRequest.RecordResolution -> false
        }
        LocalFoodPaymentSupervisorCodec.COMMAND -> LocalFoodPaymentSupervisorCodec.decode(original.normalized) is LocalFoodPaymentSupervisorRequest.Apply
        else -> false
    }
}
internal fun foodPaymentNormalize(command: CommandKey, body: StoredBytes): StoredBytes = when (command) {
    LocalFoodPaymentProfile.COMMAND -> LocalFoodPaymentCodec.normalize(body)
    LocalFoodPaymentSupervisorCodec.COMMAND -> LocalFoodPaymentSupervisorCodec.normalize(body)
    else -> error("food.payment.command_profile")
}
internal fun foodPaymentHasFinancialInputs(original: RetainedCommandEvidence): Boolean = isFoodPaymentFinancialProducer(original)

/** Direct literal normalized FOOD input match, after the actual owner's WHOLE producer. */
internal suspend fun requireFoodPaymentStreamedInput(original: RetainedStreamedCommand, input: FoodFinancialInputV1) {
    check(input.stock == null && input.schema == foodStockSchema(input.kind) &&
        original.identity.producer.operationId == input.operationId && original.identity.producer.command == StockStreamingCommandsV2.FOOD)
    var matched = false
    original.frames { frame -> if (frame is RetainedCommandFrameV2.Write && frame.value.key == input.key()) {
        val row = frame.value
        check(!matched && row.storageGeneration == input.revision && row.schema == input.schema && row.payload == input.bytes() &&
            row.payloadDigest == input.digest && row.payloadDigest == sha256(row.payload.copyBytes()))
        matched = true
    } }
    check(matched) { "food.payment.normalized_input_missing" }
}

/** Exact retained ancestor selectors. The returned values are actual loan-bound reads, not mirrors. */
internal class FoodPaymentRetainedLineage(val start: RetainedCommandEvidence,
    val originalRaw: RetainedCommandEvidence? = null, val resolutionRaw: RetainedCommandEvidence? = null,
    val originalUnknownApplication: RetainedCommandEvidence? = null)

internal suspend fun foodPaymentRetainedLineage(original: RetainedCommandEvidence,
    lookup: suspend (OperationId) -> RetainedCommandEvidence): FoodPaymentRetainedLineage {
    suspend fun read(id: OperationId): RetainedCommandEvidence = lookup(id).also {
        check(it.committed && it.scope == original.scope && it.operationId == id)
    }
    var raw: RetainedCommandEvidence? = null
    var resolution: RetainedCommandEvidence? = null
    var application: FoodPaymentApplySelection? = null
    val target = if (original.command == LocalFoodPaymentSupervisorCodec.COMMAND) {
        val selected = foodPaymentApplySelection(original.command, original.normalized)
        application = selected
        if (selected.phase == LocalFoodPaymentFactPhase.ORIGINAL) {
            val fact = read(selected.factOperationId); raw = fact
            (LocalFoodPaymentCodec.decode(fact.normalized) as LocalFoodPaymentRequest.RecordOriginal).target
        } else {
            val fact = read(selected.factOperationId); resolution = fact
            (LocalFoodPaymentCodec.decode(fact.normalized) as LocalFoodPaymentRequest.RecordResolution).target
        }
    } else when (val request = LocalFoodPaymentCodec.decode(original.normalized)) {
        is LocalFoodPaymentRequest.StartCash -> return FoodPaymentRetainedLineage(original)
        is LocalFoodPaymentRequest.RecordOriginal -> request.target
        is LocalFoodPaymentRequest.RecordResolution -> {
            raw = read(request.target.recordingOperationId)
            request.target
        }
        is LocalFoodPaymentRequest.ApplyOriginal -> {
            application = foodPaymentApplySelection(original.command, original.normalized)
            val fact = read(request.recordingOperationId); raw = fact
            (LocalFoodPaymentCodec.decode(fact.normalized) as LocalFoodPaymentRequest.RecordOriginal).target
        }
        is LocalFoodPaymentRequest.ApplyResolution -> {
            application = foodPaymentApplySelection(original.command, original.normalized)
            val fact = read(request.resolutionOperationId); resolution = fact
            (LocalFoodPaymentCodec.decode(fact.normalized) as LocalFoodPaymentRequest.RecordResolution).target
        }
    }
    if (application?.phase == LocalFoodPaymentFactPhase.RESOLUTION && raw == null) raw = read(target.recordingOperationId)
    val start = read(target.intentOperationId)
    val originalFact = raw ?: original
    check(originalFact.operationId == target.recordingOperationId ||
        original.command == LocalFoodPaymentProfile.COMMAND && LocalFoodPaymentCodec.decode(original.normalized) is LocalFoodPaymentRequest.RecordResolution)
    raw?.let { foodPaymentRequireEarlier(start, it); foodPaymentRequireEarlier(it, original) }
        ?: foodPaymentRequireEarlier(start, original)
    resolution?.let { foodPaymentRequireEarlier(checkNotNull(raw), it); foodPaymentRequireEarlier(it, original) }
    val unknownApplication = if (application?.phase == LocalFoodPaymentFactPhase.RESOLUTION) {
        val event = foodPaymentStoredEvent(original)
        read(checkNotNull(event.originalUnknownApplicationOperationId)).also {
            foodPaymentRequireEarlier(checkNotNull(raw), it); foodPaymentRequireEarlier(it, original)
        }
    } else null
    return FoodPaymentRetainedLineage(start, raw, resolution, unknownApplication)
}
