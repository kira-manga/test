package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.sales.salesAuthorizationRequirement
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

internal suspend fun foodPaymentLanRetainedRequirements(normalized: StoredBytes, view: FoodReadView,
    context: CommandContext, recipes: TransactionFoodStockSource,
    services: LocalFinancialOriginalSourceServices? = null): List<AuthorizationRequirement>? {
    if (view.scope != context.scope || semanticDigest(context.scope, context.originalActorId, context.key, normalized) != context.semanticDigest) return null
    val retained = view.retainedCommand(context.operationId) ?: return null
    if (!financialRpcOriginalMatches(retained, normalized, context)) return null
    val actual = services ?: LocalFinancialOriginalSourceServices(view.commerceStock, recipes, view.commerceTenderPolicies)
    return actual.read(view, retained) { loan -> foodPaymentRpcOriginalRequirements(FoodReadView(loan), retained, recipes) }
}

/** Inert original coordinates only; never reconstruct a kernel CommandContext or authority. */
internal suspend fun foodPaymentRpcOriginalRequirements(view: FoodReadView, retained: RetainedCommandEvidence, recipes: TransactionFoodStockSource): List<AuthorizationRequirement>? {
    val context = FinancialOriginalCoordinates(retained)
    val normalized = retained.normalized
    val canonical = when (retained.command) { LocalFoodPaymentProfile.COMMAND -> LocalFoodPaymentCodec.normalize(normalized)
        LocalFoodPaymentSupervisorCodec.COMMAND -> LocalFoodPaymentSupervisorCodec.normalize(normalized)
        else -> return null }
    if (view.scope != retained.scope || retained.nativeOriginalProvenance != null || canonical != normalized ||
        semanticDigest(retained.scope, retained.originalActorId, retained.command, normalized) != retained.semanticDigest) return null
    val sources = view.financialOriginalSources(context.operationId) ?: return null
    requireSameFinancialRpcOriginal(sources.evidence, retained)
    val startRequest = if (context.key == LocalFoodPaymentProfile.COMMAND) LocalFoodPaymentCodec.decode(normalized) as? LocalFoodPaymentRequest.StartCash else null
    if (startRequest != null) {
        if (!retained.committed) return null
        val completed = view.foodProducer(context.operationId)
        requireSameFinancialRpcOriginal(completed, retained)
        view.requireFoodPredecessors(completed)
        val frames = FoodFinancialBundleReaderV2(completed, foodPaymentStoredEvent(completed).framing, allowStockInputs = true)
        requireFinancialRpcBeforeRows(sources, foodPaymentInputs(completed, frames), checkNotNull(frames.cash()), view)
        val start = LocalFoodPaymentProducer.requireStart(completed)
        check(sources.mustCommitBefore == start.result.mustCommitBefore)
        start.cut.portions.forEach { recipes.requireLegacyRecipe((it.body as FoodBodyV1.Portion).recipe, context.scope) }
        val expected = salesAuthorizationRequirement(SecurityAction.COLLECTION_ATTEST, context.scope, start.cut.collection.id,
            startRequest.expectedCollectionRevision, context.operationId, context.semanticDigest, "food.payment.start",
            start.slot.originalActorId, start.slot.domain.amount)
        check(sources.requirement == expected)
        return listOf(expected)
    }
    val resolutionRequest = if (context.key == LocalFoodPaymentProfile.COMMAND)
        LocalFoodPaymentCodec.decode(normalized) as? LocalFoodPaymentRequest.RecordResolution else null
    if (resolutionRequest != null) {
        if (!retained.committed) return null
        val completed = view.foodProducer(context.operationId)
        requireSameFinancialRpcOriginal(completed, retained)
        val lineage = foodPaymentRetainedLineage(completed) { view.retainedCommand(it) ?: error("food.payment.rpc_ancestor_required") }
        val resolution = LocalFoodPaymentResolutionProducer.requireRaw(completed, lineage.start, checkNotNull(lineage.originalRaw))
        // Raw resolution is a native-gated retention, not financial application. Join the
        // actual UNKNOWN raw/slot BEFORE and their genuine original producers separately.
        requireFoodPaymentRetainedRow(checkNotNull(lineage.originalRaw),
            financialRpcBeforeRow(sources, foodPaymentRawKey(resolutionRequest.target.recordingOperationId), checkNotNull(lineage.originalRaw)))
        requireFoodPaymentRetainedRow(lineage.start,
            financialRpcBeforeRow(sources, foodPaymentSlotKey(resolutionRequest.target.recordingOperationId), lineage.start))
        check(sources.aggregateSource(foodPaymentResolutionKey(context.operationId)) == null &&
            sources.aggregateSource(foodPaymentApplicationKey(context.operationId)) == null)
        val expected = salesAuthorizationRequirement(SecurityAction.COLLECTION_RESOLVE, context.scope,
            DomainId(resolution.raw.target.recordingOperationId.value), Revision(0), context.operationId, context.semanticDigest,
            "food.payment.retain_resolution", resolution.original.slot.originalActorId, resolution.original.slot.domain.amount)
        check(sources.requirement == expected)
        return listOf(expected)
    }
    val review = if (context.key == LocalFoodPaymentSupervisorCodec.COMMAND)
        LocalFoodPaymentSupervisorCodec.decode(normalized) as? LocalFoodPaymentSupervisorRequest.Review else null
    val expected = if (review != null) {
        check(!retained.committed && retained.code == "food.payment.supervisor.reviewed" && retained.aggregateWrites.isEmpty() &&
            retained.claimChanges.isEmpty() && retained.jobs.isEmpty())
        val facts = FoodPaymentNativeFacts(sources, view, recipes)
        val original = facts.original(review.originalRecordingOperationId)
        val resolution = facts.resolution(original)
        val result = LocalFoodPaymentSupervisorCodec.decodeReview(retained.result)
        check(result.original == foodPaymentOriginalSummary(original) &&
            result.originalOutcome == (LocalFoodPaymentCodec.decode(original.raw.value.body()) as LocalFoodPaymentRequest.RecordOriginal).outcome &&
            result.resolution == resolution?.let(::foodPaymentResolutionSummary) &&
            result.resolutionOutcome == resolution?.let { (LocalFoodPaymentCodec.decode(it.raw.value.body()) as LocalFoodPaymentRequest.RecordResolution).outcome })
        salesAuthorizationRequirement(SecurityAction.COLLECTION_RESOLVE, context.scope, original.slot.stored.key.id,
            Revision(0), context.operationId, context.semanticDigest, "food.payment.supervisor.review",
            original.slot.value.originalActorId, original.slot.value.domain.amount)
    } else {
        val selected = foodPaymentApplySelection(context.key, normalized)
        if (retained.committed) {
            val completed = view.foodProducer(context.operationId)
            requireSameFinancialRpcOriginal(completed, retained)
            view.requireFoodPredecessors(completed)
            val frames = FoodFinancialBundleReaderV2(completed, foodPaymentStoredEvent(completed).framing, allowStockInputs = true)
            requireFinancialRpcBeforeRows(sources, foodPaymentInputs(completed, frames), checkNotNull(frames.cash()), view)
            val lineage = foodPaymentRetainedLineage(completed) { view.retainedCommand(it) ?: error("food.payment.rpc_ancestor_required") }
            val raw = LocalFoodPaymentProducer.requireRaw(checkNotNull(lineage.originalRaw), lineage.start)
            val applied = if (selected.phase == LocalFoodPaymentFactPhase.ORIGINAL)
                LocalFoodPaymentProducer.requireApply(completed, lineage.start, checkNotNull(lineage.originalRaw))
            else LocalFoodPaymentResolutionProducer.requireApply(completed, lineage.start, checkNotNull(lineage.originalRaw),
                checkNotNull(lineage.resolutionRaw), checkNotNull(lineage.originalUnknownApplication))
            check(raw.slot.target.factOperation(selected.phase) == selected.factOperationId)
            LocalFoodPaymentProducer.requireStart(lineage.start).cut.portions.forEach {
                recipes.requireLegacyRecipe((it.body as FoodBodyV1.Portion).recipe, context.scope)
            }
            salesAuthorizationRequirement(if (selected.supervised || selected.phase == LocalFoodPaymentFactPhase.RESOLUTION)
                SecurityAction.COLLECTION_RESOLVE else SecurityAction.COLLECTION_ATTEST, context.scope, applied.cut.collection.id,
                applied.cut.collection.revision, context.operationId, context.semanticDigest, "food.payment.apply",
                raw.slot.originalActorId, raw.slot.domain.amount)
        } else {
            check(retained.aggregateWrites.isEmpty() && retained.claimChanges.isEmpty() && retained.jobs.isEmpty())
            val facts = FoodPaymentNativeFacts(sources, view, recipes)
            val original = if (selected.phase == LocalFoodPaymentFactPhase.ORIGINAL) facts.original(selected.factOperationId)
            else {
                val row = sources.beforeAggregateSources.single { it.row.key == foodPaymentResolutionKey(selected.factOperationId) }.row
                val value = localSalesDecode(row.payload, LocalFoodPaymentResolutionRaw.serializer(), LocalFoodPaymentProfile.MAX_RAW_BYTES)
                facts.original(value.target.recordingOperationId)
            }
            val resolution = if (selected.phase == LocalFoodPaymentFactPhase.RESOLUTION) checkNotNull(facts.resolution(original)) else null
            val slot = original.slot.value
            val application = resolution?.application?.value ?: original.application.value
            val recorder = resolution?.raw?.value?.recordingActorId ?: original.raw.value.recordingActorId
            check(slot.target.factOperation(selected.phase) == selected.factOperationId &&
                (selected.supervised || slot.originalActorId == context.originalActorId && recorder == context.originalActorId))
            when (val result = LocalFoodPaymentCodec.result(retained.result)) {
                is LocalFoodPaymentResult.AlreadyApplied -> check(retained.code == "food.payment.already_applied" &&
                    application.state == LocalFoodPaymentApplicationState.APPLIED && result.recordingOperationId == selected.factOperationId &&
                    result.phase == selected.phase && result.applicationOperationId == application.lastApplicationOperationId)
                is LocalFoodPaymentResult.Pending -> check(retained.code == "food.payment.pending_reconciliation" && result.target == slot.target &&
                    result.phase == selected.phase && application.state == LocalFoodPaymentApplicationState.PENDING_RECONCILIATION)
                else -> return null
            }
            val resolve = selected.supervised || selected.phase == LocalFoodPaymentFactPhase.RESOLUTION
            salesAuthorizationRequirement(if (resolve) SecurityAction.COLLECTION_RESOLVE else SecurityAction.CASH_READ,
                context.scope, DomainId(selected.factOperationId.value), application.revision, context.operationId, context.semanticDigest,
                if (retained.code == "food.payment.already_applied") "food.payment.already_applied" else "food.payment.pending",
                slot.originalActorId, if (resolve) slot.domain.amount else null)
        }
    }
    check(sources.requirement == expected)
    return listOf(expected)
}

/** Original-at-cut FOOD projection only. All selected rows are paired with genuine earlier
 * immutable writes; whole FOOD/native/stock predecessors are rejoined on this same loan.
 * These inert cuts cannot be used as current readers, grants or physical continuations. */
private class FoodPaymentNativeFacts(private val sources: RuntimeHistoricalFinancialSources,
    private val view: FoodReadView, private val recipes: TransactionFoodStockSource) {
    private suspend fun required(key: AggregateKey): Pair<StoredAggregate, RetainedCommandEvidence> {
        val source = sources.beforeAggregateSources.single { it.row.key == key }
        val original = (source.original as? RetainedOriginalV2.Legacy)?.evidence ?: error("food.payment.rpc_original_required")
        val qualified = view.foodProducer(original.operationId)
        requireSameFinancialRpcOriginal(qualified, original)
        view.requireFoodPredecessors(qualified)
        requireFoodPaymentRetainedRow(qualified, source.row)
        check(qualified.scope == sources.evidence.scope && qualified.assignmentId == sources.evidence.assignmentId &&
            qualified.authorityEpoch == sources.evidence.authorityEpoch && qualified.sequence < sources.evidence.sequence)
        return source.row to qualified
    }

    suspend fun original(operation: OperationId): FoodPaymentRawCut {
        val (rawRow, rawOriginal) = required(foodPaymentRawKey(operation))
        val raw = localSalesDecode(rawRow.payload, LocalFoodPaymentRaw.serializer(), LocalFoodPaymentProfile.MAX_RAW_BYTES)
        val (slotRow, start) = required(foodPaymentSlotKey(operation))
        val proved = LocalFoodPaymentProducer.requireRaw(rawOriginal, start)
        check(proved.raw == raw && proved.slot.target.recordingOperationId == operation &&
            foodPaymentSlotWrite(proved.slot).payload == slotRow.payload)
        LocalFoodPaymentProducer.requireStart(start).cut.portions.forEach {
            recipes.requireLegacyRecipe((it.body as FoodBodyV1.Portion).recipe, sources.evidence.scope)
        }
        val application = application(operation, LocalFoodPaymentFactPhase.ORIGINAL)
        check(application.value.target == raw.target && application.value.originalActorId == raw.originalActorId)
        return FoodPaymentRawCut(LocalSalesRow(slotRow, proved.slot), LocalSalesRow(rawRow, raw), application, start, rawOriginal)
    }

    suspend fun resolution(original: FoodPaymentRawCut): FoodPaymentResolutionCut? {
        val operation = original.slot.value.target.resolutionOperationId
        if (sources.beforeAggregateSources.none { it.row.key == foodPaymentResolutionKey(operation) }) {
            check(foodPaymentResolutionKey(operation) in sources.beforeAbsentAggregateKeys)
            return null
        }
        val (row, producer) = required(foodPaymentResolutionKey(operation))
        val proved = LocalFoodPaymentResolutionProducer.requireRaw(producer, original.startOriginal, original.rawOriginal)
        check(proved.original.raw == original.raw.value && proved.raw.target == original.slot.value.target)
        val application = application(operation, LocalFoodPaymentFactPhase.RESOLUTION)
        check(application.value.target == original.slot.value.target && application.value.originalActorId == original.slot.value.originalActorId)
        return FoodPaymentResolutionCut(original, LocalSalesRow(row, proved.raw), application, producer)
    }

    private suspend fun application(operation: OperationId, phase: LocalFoodPaymentFactPhase): LocalSalesRow<LocalFoodPaymentApplication> {
        val (row, _) = required(foodPaymentApplicationKey(operation))
        val value = localSalesDecode(row.payload, LocalFoodPaymentApplication.serializer(), LocalFoodPaymentProfile.MAX_APPLICATION_BYTES)
        check(value.scope == sources.evidence.scope && value.target.factOperation(phase) == operation && value.phase == phase &&
            value.revision.next() == row.revision && foodPaymentApplicationWrite(value, value.revision).payload == row.payload)
        return LocalSalesRow(row, value)
    }
}
