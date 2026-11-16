package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.sales.salesAuthorizationRequirement
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

internal suspend fun foodRefundRpcRetainedRequirements(normalized: StoredBytes, view: FoodReadView,
    context: CommandContext, services: LocalFinancialOriginalSourceServices? = null): List<AuthorizationRequirement>? {
    if (context.scope != view.scope || context.key !in foodRefundCommands ||
        normalizeFoodRefundCommand(context.key, normalized) != normalized ||
        semanticDigest(context.scope, context.originalActorId, context.key, normalized) != context.semanticDigest) return null
    val retained = view.retainedCommand(context.operationId) ?: return null
    if (!financialRpcOriginalMatches(retained, normalized, context)) return null
    val actual = services ?: LocalFinancialOriginalSourceServices(view.commerceStock, null, view.commerceTenderPolicies)
    return actual.read(view, retained) { loan -> foodRefundRpcOriginalRequirements(FoodReadView(loan), retained) }
}

/** Inert original coordinates only; never reconstruct a kernel CommandContext or authority. */
internal suspend fun foodRefundRpcOriginalRequirements(view: FoodReadView, retained: RetainedCommandEvidence): List<AuthorizationRequirement>? {
    val context = FinancialOriginalCoordinates(retained)
    val normalized = retained.normalized
    if (retained.command !in foodRefundCommands) return null
    val canonical = normalizeFoodRefundCommand(retained.command, normalized)
    if (view.scope != retained.scope || retained.nativeOriginalProvenance != null || canonical != normalized ||
        semanticDigest(retained.scope, retained.originalActorId, retained.command, normalized) != retained.semanticDigest) return null
    val sources = view.financialOriginalSources(context.operationId) ?: return null
    requireSameFinancialRpcOriginal(sources.evidence, retained)
    val startRequest = if (context.key == LocalFoodRefundProfile.COMMAND)
        LocalFoodRefundCodec.decode(normalized) as? LocalFoodRefundRequest.StartCash else null
    if (startRequest != null) {
        if (!retained.committed) return null
        val completed = view.foodProducer(context.operationId)
        requireSameFinancialRpcOriginal(completed, retained)
        view.requireFoodPredecessors(completed)
        val frames = FoodFinancialBundleReaderV2(completed, foodRefundStoredEvent(completed).framing, allowStockInputs = true)
        requireFinancialRpcBeforeRows(sources, foodRefundInputs(completed, frames), checkNotNull(frames.cash()), view)
        val start = LocalFoodRefundProducer.requireStart(completed)
        val expected = salesAuthorizationRequirement(SecurityAction.REFUND_RECORD, context.scope, start.cut.refund.id,
            startRequest.expectedRefundRevision, context.operationId, context.semanticDigest, "food.refund.start",
            start.slot.originalActorId, start.slot.domain.amount)
        check(sources.requirement == expected)
        return listOf(expected)
    }
    val resolutionRequest = if (context.key == LocalFoodRefundProfile.COMMAND)
        LocalFoodRefundCodec.decode(normalized) as? LocalFoodRefundRequest.RecordResolution else null
    if (resolutionRequest != null) {
        if (!retained.committed) return null
        val completed = view.foodProducer(context.operationId)
        requireSameFinancialRpcOriginal(completed, retained)
        val lineage = foodRefundRetainedLineage(completed) { view.retainedCommand(it) ?: error("food.refund.rpc_ancestor_required") }
        val resolution = LocalFoodRefundResolutionProducer.requireRaw(completed, lineage.start, checkNotNull(lineage.originalRaw))
        requireFoodRefundRetainedRow(checkNotNull(lineage.originalRaw),
            financialRpcBeforeRow(sources, foodRefundRawKey(resolutionRequest.target.recordingOperationId), checkNotNull(lineage.originalRaw)))
        requireFoodRefundRetainedRow(lineage.start,
            financialRpcBeforeRow(sources, foodRefundSlotKey(resolutionRequest.target.recordingOperationId), lineage.start))
        check(sources.aggregateSource(foodRefundResolutionKey(context.operationId)) == null &&
            sources.aggregateSource(foodRefundApplicationKey(context.operationId)) == null)
        val expected = salesAuthorizationRequirement(SecurityAction.REFUND_RESOLVE, context.scope,
            DomainId(resolution.raw.target.recordingOperationId.value), Revision(0), context.operationId, context.semanticDigest,
            "food.refund.retain_resolution", resolution.original.slot.originalActorId, resolution.original.slot.domain.amount)
        check(sources.requirement == expected)
        return listOf(expected)
    }
    val review = if (context.key == LocalFoodRefundSupervisorCodec.COMMAND)
        LocalFoodRefundSupervisorCodec.decode(normalized) as? LocalFoodRefundSupervisorRequest.Review else null
    if (review != null) {
        check(!retained.committed && retained.code == "food.refund.supervisor.reviewed" && retained.aggregateWrites.isEmpty() &&
            retained.claimChanges.isEmpty() && retained.jobs.isEmpty())
        val facts = FoodRefundNativeFacts(sources, view)
        val original = facts.original(review.originalRecordingOperationId)
        val resolution = facts.resolution(original)
        val expectedResult = LocalFoodRefundSupervisorReview(foodRefundOriginalSummary(original),
            (LocalFoodRefundCodec.decode(original.raw.value.body()) as LocalFoodRefundRequest.RecordOriginal).outcome,
            resolution?.let(::foodRefundResolutionSummary), resolution?.let {
                (LocalFoodRefundCodec.decode(it.raw.value.body()) as LocalFoodRefundRequest.RecordResolution).outcome
            })
        check(retained.result == LocalFoodRefundSupervisorCodec.review(expectedResult))
        val expected = salesAuthorizationRequirement(SecurityAction.REFUND_RESOLVE, context.scope, original.slot.stored.key.id,
            Revision(0), context.operationId, context.semanticDigest, "food.refund.supervisor.review",
            original.slot.value.originalActorId, original.slot.value.domain.amount)
        check(sources.requirement == expected)
        return listOf(expected)
    }
    val selected = foodRefundApplySelection(context.key, normalized)
    val expected: AuthorizationRequirement
    if (retained.committed) {
        val completed = view.foodProducer(context.operationId)
        requireSameFinancialRpcOriginal(completed, retained)
        view.requireFoodPredecessors(completed)
        val frames = FoodFinancialBundleReaderV2(completed, foodRefundStoredEvent(completed).framing, allowStockInputs = true)
        requireFinancialRpcBeforeRows(sources, foodRefundInputs(completed, frames), checkNotNull(frames.cash()), view)
        val lineage = foodRefundRetainedLineage(completed) { view.retainedCommand(it) ?: error("food.refund.rpc_ancestor_required") }
        val raw = LocalFoodRefundProducer.requireRaw(checkNotNull(lineage.originalRaw), lineage.start)
        val applied = if (selected.phase == LocalFoodRefundFactPhase.ORIGINAL)
            LocalFoodRefundProducer.requireApply(completed, lineage.start, checkNotNull(lineage.originalRaw))
        else LocalFoodRefundResolutionProducer.requireApply(completed, lineage.start, checkNotNull(lineage.originalRaw),
            checkNotNull(lineage.resolutionRaw), checkNotNull(lineage.originalUnknownApplication))
        check((selected.supervised || raw.slot.originalActorId == context.originalActorId) &&
            raw.slot.target.factOperation(selected.phase) == selected.factOperationId)
        expected = salesAuthorizationRequirement(if (selected.supervised || selected.phase == LocalFoodRefundFactPhase.RESOLUTION)
            SecurityAction.REFUND_RESOLVE else SecurityAction.REFUND_RECORD, context.scope, applied.cut.refund.id,
            applied.cut.refund.revision, context.operationId, context.semanticDigest, "food.refund.apply",
            raw.slot.originalActorId, raw.slot.domain.amount)
    } else {
        val proof = LocalFoodRefundApplicationFirstRefusal(sources, view, view.commerceStock).prove() ?: return null
        check(retained.code == proof.proposal.code && retained.result == proof.proposal.result)
        expected = proof.requirement
    }
    check(sources.requirement == expected)
    return listOf(expected)
}

internal fun financialRpcOriginalMatches(original: RetainedCommandEvidence, normalized: StoredBytes,
    context: CommandContext): Boolean = original.scope == context.scope && original.operationId == context.operationId &&
    original.command == context.key && original.originalActorId == context.originalActorId &&
    original.semanticDigest == context.semanticDigest && original.normalized == normalized && original.nativeOriginalProvenance == null

internal fun requireSameFinancialRpcOriginal(actual: RetainedCommandEvidence, expected: RetainedCommandEvidence) {
    check(actual.scope == expected.scope && actual.command == expected.command && actual.operationId == expected.operationId &&
        actual.originalActorId == expected.originalActorId && actual.semanticDigest == expected.semanticDigest &&
        actual.normalized == expected.normalized && actual.journalHash == expected.journalHash && actual.result == expected.result &&
        actual.resultHash == expected.resultHash && actual.committed == expected.committed && actual.code == expected.code &&
        actual.assignmentId == expected.assignmentId && actual.authorityEpoch == expected.authorityEpoch && actual.sequence == expected.sequence &&
        actual.nativeOriginalProvenance == expected.nativeOriginalProvenance)
}

/** The selected immutable source must have been present in THIS native BEFORE cut. A
 * current row or even a genuine, but different, immutable original cannot replace it. */
internal fun financialRpcBeforeRow(sources: RuntimeHistoricalFinancialSources, key: AggregateKey,
    producer: RetainedCommandEvidence): StoredAggregate {
    val source = sources.aggregateSource(key) ?: error("financial.rpc.before_source_required")
    val paired = (source.original as? RetainedOriginalV2.Legacy)?.evidence ?: error("financial.rpc.before_original_required")
    requireSameFinancialRpcOriginal(paired, producer)
    check(producer.scope == sources.evidence.scope && producer.assignmentId == sources.evidence.assignmentId &&
        producer.authorityEpoch == sources.evidence.authorityEpoch && producer.sequence < sources.evidence.sequence)
    return source.row
}

/** Whole typed producer/predecessor validation is performed by the caller on its actual
 * loan. Independently bind each literal input and cash image to THIS native BEFORE cut. */
internal suspend fun requireFinancialRpcBeforeRows(sources: RuntimeHistoricalFinancialSources,
    inputs: List<FoodFinancialInputV1>, cash: FoodFinancialCashInputV1, view: CommandReadView) {
    for (input in inputs) {
        val source = sources.aggregateSource(input.key()) ?: error("food.financial.rpc_before_required")
        val row = source.row
        val operation = when (val original = source.original) {
            is RetainedOriginalV2.Legacy -> original.evidence.operationId
            is RetainedOriginalV2.Streamed -> original.evidence.identity.producer.operationId
        }
        check(operation == input.operationId && row.revision == input.revision && row.schema == input.schema &&
            row.digest == input.digest && row.payload == input.bytes())
    }
    val row = sources.aggregateSource(AggregateKey(CashNonSaleStoreProfile.SESSION_KIND, cash.sessionId))?.row
        ?: error("food.financial.rpc_cash_before_required")
    check(row.revision == cash.storageRevision && row.schema == LocalSalesStoreProfile.schema(CashNonSaleStoreProfile.SESSION_KIND) &&
        row.payload.copyBytes().decodeToString(throwOnInvalidSequence = true) == cash.snapshot)
    val qualified = LocalFinancialCashBeforeRead(sources, view).cash(cash.sessionId)
    check(qualified.session.stored == row)
}

/** Original-at-cut FOOD projection only. All selected rows are paired with genuine earlier
 * immutable writes; whole FOOD/native/stock predecessors are rejoined on this same loan.
 * These inert cuts cannot be used as current readers, grants or physical continuations. */
private class FoodRefundNativeFacts(private val sources: RuntimeHistoricalFinancialSources,
    private val view: FoodReadView) {
    private suspend fun required(key: AggregateKey): Pair<StoredAggregate, RetainedCommandEvidence> {
        val source = sources.beforeAggregateSources.single { it.row.key == key }
        val original = (source.original as? RetainedOriginalV2.Legacy)?.evidence ?: error("food.refund.rpc_original_required")
        val qualified = view.foodProducer(original.operationId)
        requireSameFinancialRpcOriginal(qualified, original)
        view.requireFoodPredecessors(qualified)
        requireFoodRefundRetainedRow(qualified, source.row)
        check(qualified.scope == sources.evidence.scope && qualified.assignmentId == sources.evidence.assignmentId &&
            qualified.authorityEpoch == sources.evidence.authorityEpoch && qualified.sequence < sources.evidence.sequence)
        return source.row to qualified
    }

    suspend fun original(operation: OperationId): FoodRefundRawCut {
        val (rawRow, rawOriginal) = required(foodRefundRawKey(operation))
        val raw = localSalesDecode(rawRow.payload, LocalFoodRefundRaw.serializer(), LocalFoodRefundProfile.MAX_RAW_BYTES)
        val (slotRow, start) = required(foodRefundSlotKey(operation))
        val proved = LocalFoodRefundProducer.requireRaw(rawOriginal, start)
        check(proved.raw == raw && proved.slot.target.recordingOperationId == operation &&
            foodRefundSlotWrite(proved.slot).payload == slotRow.payload)
        val application = application(operation, LocalFoodRefundFactPhase.ORIGINAL)
        check(application.value.target == raw.target && application.value.originalActorId == raw.originalActorId)
        return FoodRefundRawCut(LocalSalesRow(slotRow, proved.slot), LocalSalesRow(rawRow, raw), application, start, rawOriginal)
    }

    suspend fun resolution(original: FoodRefundRawCut): FoodRefundResolutionCut? {
        val operation = original.slot.value.target.resolutionOperationId
        if (sources.beforeAggregateSources.none { it.row.key == foodRefundResolutionKey(operation) }) {
            check(foodRefundResolutionKey(operation) in sources.beforeAbsentAggregateKeys &&
                foodRefundApplicationKey(operation) in sources.beforeAbsentAggregateKeys)
            return null
        }
        val (row, producer) = required(foodRefundResolutionKey(operation))
        val proved = LocalFoodRefundResolutionProducer.requireRaw(producer, original.startOriginal, original.rawOriginal)
        check(proved.original.raw == original.raw.value && proved.raw.target == original.slot.value.target)
        val application = application(operation, LocalFoodRefundFactPhase.RESOLUTION)
        check(application.value.target == original.slot.value.target && application.value.originalActorId == original.slot.value.originalActorId)
        return FoodRefundResolutionCut(original, LocalSalesRow(row, proved.raw), application, producer)
    }

    private suspend fun application(operation: OperationId, phase: LocalFoodRefundFactPhase): LocalSalesRow<LocalFoodRefundApplication> {
        val (row, _) = required(foodRefundApplicationKey(operation))
        val value = localSalesDecode(row.payload, LocalFoodRefundApplication.serializer(), LocalFoodRefundProfile.MAX_APPLICATION_BYTES)
        check(value.scope == sources.evidence.scope && value.target.factOperation(phase) == operation && value.phase == phase &&
            value.revision.next() == row.revision && foodRefundApplicationWrite(value, value.revision).payload == row.payload)
        return LocalSalesRow(row, value)
    }
}
