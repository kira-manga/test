package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.sales.salesAuthorizationRequirement
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

internal class FoodPaymentValueMismatchOriginal(
    val application: LocalFoodPaymentApplication,
    val proposal: ProposedOutcome.Reject,
    val requirement: AuthorizationRequirement,
    val resources: Set<RecoveryResourceKey>,
)

/** Source-only proof of the ordinary mismatch branch before any current collection,
 * cash or accounting read. The actual native BEFORE pairing is supplied separately;
 * successful old ancestors alone never prove this command's observed application. */
internal suspend fun requireFoodPaymentValueMismatch(
    original: RetainedCommandEvidence,
    application: StoredAggregate,
    historicalStart: RetainedCommandEvidence,
    originalRaw: RetainedCommandEvidence,
): FoodPaymentValueMismatchOriginal {
    check(original.command == LocalFoodPaymentProfile.COMMAND && !original.committed &&
        original.nativeOriginalProvenance == null && original.code == "food.payment.pending_reconciliation" &&
        LocalFoodPaymentCodec.normalize(original.normalized) == original.normalized &&
        semanticDigest(original.scope, original.originalActorId, original.command, original.normalized) == original.semanticDigest &&
        sha256(original.result.copyBytes()) == original.resultHash && original.aggregateWrites.isEmpty() &&
        original.claimChanges.isEmpty() && original.jobs.isEmpty())
    foodCapacity(foodProducerReadBytes(original) <= FoodReadLimits.FOOD_PRODUCER_BYTES)
    val request = LocalFoodPaymentCodec.decode(original.normalized) as? LocalFoodPaymentRequest.ApplyOriginal
        ?: error("food.payment.value_mismatch_original_required")
    check(originalRaw.nativeOriginalProvenance != null)
    val raw = LocalFoodPaymentProducer.requireRaw(originalRaw, historicalStart)
    val slot = raw.slot
    check(request.recordingOperationId == slot.target.recordingOperationId && original.scope == slot.scope &&
        original.originalActorId == slot.originalActorId && raw.raw.recordingActorId == slot.originalActorId &&
        original.operationId !in setOf(slot.target.intentOperationId, slot.target.recordingOperationId,
            slot.target.resolutionOperationId))
    foodPaymentRequireEarlier(historicalStart, originalRaw)
    foodPaymentRequireEarlier(originalRaw, original)
    val value = localSalesDecode(application.payload, LocalFoodPaymentApplication.serializer(), LocalFoodPaymentProfile.MAX_APPLICATION_BYTES)
    check(application.key == foodPaymentApplicationKey(request.recordingOperationId) && application.revision == Revision(1) &&
        application.schema == LocalFoodPaymentProfile.schema(LocalFoodPaymentProfile.APPLICATION) &&
        application.digest == sha256(application.payload.copyBytes()) && value == foodPaymentInitialApplication(raw.raw) &&
        value.version == 1 && value.revision == Revision(0) && value.state == LocalFoodPaymentApplicationState.PENDING_RECONCILIATION &&
        value.phase == LocalFoodPaymentFactPhase.ORIGINAL && value.scope == original.scope && value.target == slot.target &&
        value.originalActorId == slot.originalActorId && value.lastApplicationOperationId == null && value.action == null)
    requireFoodPaymentRetainedRow(originalRaw, application)
    val outcome = (LocalFoodPaymentCodec.decode(raw.raw.body()) as LocalFoodPaymentRequest.RecordOriginal).outcome
    check(foodPaymentValueOrTenderMismatch(outcome, slot))
    val proposal = foodPaymentPending(slot.target, LocalFoodPaymentPendingReason.VALUE_OR_TENDER_MISMATCH,
        LocalFoodPaymentFactPhase.ORIGINAL)
    check(original.code == proposal.code && original.result == proposal.result)
    val requirement = salesAuthorizationRequirement(SecurityAction.CASH_READ, original.scope,
        DomainId(request.recordingOperationId.value), value.revision, original.operationId, original.semanticDigest,
        "food.payment.pending", slot.originalActorId)
    val resources = slot.domain.financialResources.toSet() + foodPaymentAuthorityResources(slot.requirement()) +
        recordingAuthority(raw.raw.recordingActorId)
    check(resources.size <= 512)
    return FoodPaymentValueMismatchOriginal(value, proposal, requirement, resources)
}

/** Disclose only after both native original-BEFORE custody and the installed complete
 * business producers agree. This never prepares, applies or reads a current application. */
internal suspend fun foodPaymentValueMismatchRetainedRequirement(
    sources: RuntimeHistoricalFoodPaymentValueMismatchSources,
    view: FoodReadView,
    recipes: TransactionFoodStockSource,
): AuthorizationRequirement? {
    val original = sources.evidence
    if (view.scope != original.scope) return null
    val applicationSource = sources.beforeApplication
    val rawLiteral = (applicationSource.original as? RetainedOriginalV2.Legacy)?.evidence ?: return null
    val raw = view.foodProducer(rawLiteral.operationId)
    requireSameFoodPaymentOriginal(raw, rawLiteral)
    if (raw.nativeOriginalProvenance == null) return null
    view.requireFoodPredecessors(raw)
    val lineage = foodPaymentRetainedLineage(raw) { id ->
        view.retainedCommand(id) ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    }
    if (lineage.originalRaw != null || lineage.resolutionRaw != null) return null
    val proof = requireFoodPaymentValueMismatch(original, applicationSource.row, lineage.start, raw)
    if (sources.requirement != proof.requirement) return null
    val capturedResources = sources.resources
    check(capturedResources.size == proof.resources.size && capturedResources.all {
        it.selectorTag == 3 && it.preserveHold && it.originalEvidenceDigest == original.semanticDigest
    } && capturedResources.map { checkNotNull(it.kind).name to checkNotNull(it.resourceId) }.toSet() ==
        proof.resources.map { it.kind.name to it.resourceId }.toSet())

    fun row(key: AggregateKey, producer: RetainedCommandEvidence): StoredAggregate {
        val literal = sources.beforeAggregateSources.singleOrNull { it.row.key == key }
            ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
        val paired = (literal.original as? RetainedOriginalV2.Legacy)?.evidence
            ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
        requireSameFoodPaymentOriginal(paired, producer)
        requireFoodPaymentRetainedRow(producer, literal.row)
        return literal.row
    }

    check(sources.beforeAggregateSources.singleOrNull { it.row.key == applicationSource.row.key } === applicationSource)
    row(applicationSource.row.key, raw)
    val start = LocalFoodPaymentProducer.requireStart(lineage.start)
    val slot = start.slot
    val requirement = slot.requirement()
    val fences = foodPaymentFences(requirement)
    val expectedRowKeys = setOf(applicationSource.row.key, foodPaymentSlotKey(slot.target.recordingOperationId),
        foodPaymentRawKey(slot.target.recordingOperationId),
        AggregateKey(PhysicalRecordingSourceProfileV1.KIND, requirement.masterAllocationId),
        AggregateKey(PhysicalRecordingSourceProfileV1.KIND, requirement.clientAllocationId)) + fences.map { it.key }
    // This closed lane proves every positive observation. An additional native-retained
    // but business-unqualified source is not silently ignored or treated as authority.
    check(sources.beforeAggregateSources.map { it.row.key }.toSet() == expectedRowKeys)
    val slotRow = row(foodPaymentSlotKey(slot.target.recordingOperationId), lineage.start)
    val rawRow = row(foodPaymentRawKey(slot.target.recordingOperationId), raw)
    check(slotRow.revision == Revision(1) && slotRow.schema == LocalFoodPaymentProfile.schema(LocalFoodPaymentProfile.SLOT) &&
        slotRow.payload == foodPaymentSlotWrite(slot).payload && rawRow.revision == Revision(1) &&
        rawRow.schema == LocalFoodPaymentProfile.schema(LocalFoodPaymentProfile.ORIGINAL))
    val nativeRows = listOf(requirement.masterAllocationId, requirement.clientAllocationId).map { id ->
        val source = row(AggregateKey(PhysicalRecordingSourceProfileV1.KIND, id), lineage.start)
        check(source.revision == Revision(1) && source.schema == PhysicalRecordingSourceProfileV1.SCHEMA)
        AggregateChange(source.key, null, source.schema, source.payload)
    }
    val pair = requireFoodPaymentSourcePair(requirement, nativeRows)
    check(slot.masterSource == LocalFoodPaymentSourceReference.from(pair.first) &&
        slot.clientSource == LocalFoodPaymentSourceReference.from(pair.second))
    for (expected in fences) {
        val fence = row(expected.key, lineage.start)
        check(fence.revision == Revision(1) && fence.schema == expected.schema && fence.payload == expected.payload)
    }
    val expectedClaims = foodPaymentLineageClaims(requirement) + ClaimChange.Acquire(
        LocalSalesStoreProfile.unique("payment_attempt", slot.target.attemptId), slot.target.collectionId)
    check(sources.beforeClaimSources.map { it.key }.toSet() == expectedClaims.map { it.key }.toSet())
    for (expected in expectedClaims) {
        val literal = sources.beforeClaimSources.singleOrNull { it.key == expected.key }
            ?: return null
        val paired = (literal.original as? RetainedOriginalV2.Legacy)?.evidence ?: return null
        requireSameFoodPaymentOriginal(paired, lineage.start)
        check(literal.ownerId == expected.ownerId && lineage.start.claimChanges.single { it.key == expected.key }.let {
            it.action == RetainedClaimAction.ACQUIRE && it.ownerId == expected.ownerId
        })
    }
    start.cut.portions.forEach {
        recipes.requireLegacyRecipe((it.body as FoodBodyV1.Portion).recipe, original.scope)
    }
    return proof.requirement
}
