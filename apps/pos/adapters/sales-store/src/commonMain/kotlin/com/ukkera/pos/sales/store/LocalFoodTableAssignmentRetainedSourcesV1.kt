package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.types.DomainId

/** Native BEFORE literals are not a business proof. Complete the actual earlier FOOD
 * writers and exact claim acquisitions on this same loan before interpreting ownership.
 * This source-only path cannot construct a historical view, prepare or evaluate assignment. */
internal suspend fun foodTableAssignmentRejectedOrder(view: FoodReadView,
    sources: RuntimeHistoricalFoodTableAssignmentRejectedSources,
    orderId: DomainId): FoodTableAssignmentOrderCutV1? {
    val key = LocalFoodProfileV1.key(LocalFoodProfileV1.ORDER, orderId)
    if (sources.evidence.scope != view.scope || sources.orderKey != key ||
        sources.beforeOrderState != RuntimeHistoricalFoodTableAssignmentBeforeOrderState.PRESENT ||
        sources.beforeOrderClaimOwnerId != key.id) return null
    val nativeOrder = sources.beforeOrder ?: return null
    check(sources.beforeAggregateSources.singleOrNull { it.row.key == key } === nativeOrder)
    val order = foodTableAssignmentRejectedFood(view, sources, key) ?: return null
    val body = order.first.body as? FoodBodyV1.Order ?: return null
    val index = foodTableAssignmentRejectedFood(view, sources,
        LocalFoodProfileV1.key(LocalFoodProfileV1.OWNER_INDEX, body.ownerId)) ?: return null
    return FoodTableAssignmentOrderCutV1(order.first, index.first, order.second, index.second).also {
        requireFoodTableAssignmentOrderCut(it, sources.evidence.scope)
    }
}

private suspend fun foodTableAssignmentRejectedFood(view: FoodReadView,
    sources: RuntimeHistoricalFoodTableAssignmentRejectedSources,
    key: AggregateKey): Pair<FoodEnvelopeV1, FoodTableAssignmentFoodSourceV1>? {
    val literal = sources.beforeAggregateSources.singleOrNull { it.row.key == key } ?: return null
    val claimKey = LocalFoodProfileV1.claim(key.kind, key.id)
    val claim = sources.beforeClaimSources.singleOrNull { it.key == claimKey } ?: return null
    check(claim.ownerId == key.id)
    val writer = foodTableAssignmentRejectedFoodOriginal(view, literal.original, sources.evidence)
    val acquisition = foodTableAssignmentRejectedFoodOriginal(view, claim.original, sources.evidence)
    val row = literal.row
    val coordinate = when (writer) {
        is RetainedOriginalV2.Legacy -> {
            val original = writer.evidence
            check(row.schema == LocalFoodProfileV1.schema(key.kind))
            val write = original.aggregateWrites.singleOrNull { it.key == key }
                ?: error("food.table.assignment.rejected_food_write_missing")
            check(write.storageGeneration == row.revision && write.schema == row.schema &&
                write.payload == row.payload && write.payloadDigest == row.digest)
            FoodTableAssignmentFoodSourceV1(key.kind, key.id, row.revision, row.schema, row.digest,
                original.operationId, original.originalActorId)
        }
        is RetainedOriginalV2.Streamed -> {
            val original = writer.evidence
            check(foodStreamedSchema(key.kind, row.schema))
            val write = original.writeRow(key) ?: error("food.table.assignment.rejected_food_write_missing")
            requireFoodTableAssignmentSameRow(row, write)
            FoodTableAssignmentFoodSourceV1(key.kind, key.id, row.revision, row.schema, row.digest,
                original.identity.producer.operationId, original.identity.producer.originalActorId)
        }
    }
    var acquisitions = 0
    fun requireAcquisition(change: RetainedClaimChange) {
        if (change.key == claimKey) {
            check(change.action == RetainedClaimAction.ACQUIRE && change.ownerId == claim.ownerId)
            acquisitions++
        }
    }
    when (acquisition) {
        is RetainedOriginalV2.Legacy -> acquisition.evidence.claimChanges.forEach(::requireAcquisition)
        is RetainedOriginalV2.Streamed -> acquisition.evidence.frames { frame ->
            (frame as? RetainedCommandFrameV2.Claim)?.value?.let(::requireAcquisition)
        }
    }
    check(acquisitions == 1) { "food.table.assignment.rejected_food_claim_missing" }
    return foodTableAssignmentSourceEnvelope(view.scope, coordinate, row.key, row.revision,
        row.schema, row.payload, row.digest) to coordinate
}

/** Whole installed producer traversal, including genuine mixed predecessors, not literal
 * journal/hash consistency or a schema allowlist standing in for source qualification. */
private suspend fun foodTableAssignmentRejectedFoodOriginal(view: FoodReadView,
    literal: RetainedOriginalV2, consumer: RetainedCommandEvidence): RetainedOriginalV2 = when (literal) {
    is RetainedOriginalV2.Legacy -> {
        val original = literal.evidence
        requireFoodTableAssignmentChronology(original, consumer)
        check(original.assignmentId == consumer.assignmentId && original.authorityEpoch == consumer.authorityEpoch)
        val completed = view.foodProducer(original.operationId)
        view.requireFoodPredecessors(completed)
        requireSameFoodTableAssignmentRetainedOriginal(original, completed)
        literal
    }
    is RetainedOriginalV2.Streamed -> {
        val original = literal.evidence
        val identity = original.identity
        check(identity.scope == consumer.scope && identity.assignmentId == consumer.assignmentId &&
            identity.authorityEpoch == consumer.authorityEpoch && identity.sequence < consumer.sequence &&
            identity.producer.operationId != consumer.operationId && identity.producer.command == StockStreamingCommandsV2.FOOD)
        val completed = view.foodStockProducer(identity.producer.operationId)
        check(original.identity == completed.identity && original.journalHash == completed.journalHash &&
            original.normalized == completed.normalized && original.result == completed.result)
        literal
    }
}

/** Same immutable authenticated source and independently completed literal original. */
internal fun requireSameFoodTableAssignmentRetainedOriginal(expected: RetainedCommandEvidence,
    actual: RetainedCommandEvidence) {
    check(expected.nativeOriginalProvenance == null && actual.nativeOriginalProvenance == null &&
        expected.scope == actual.scope && expected.operationId == actual.operationId &&
        expected.originalActorId == actual.originalActorId && expected.command == actual.command &&
        expected.assignmentId == actual.assignmentId && expected.authorityEpoch == actual.authorityEpoch &&
        expected.sequence == actual.sequence && expected.recordedAt == actual.recordedAt &&
        expected.authenticationId == actual.authenticationId && expected.policyRevision == actual.policyRevision &&
        expected.semanticDigest == actual.semanticDigest && expected.code == actual.code &&
        expected.normalized == actual.normalized && expected.result == actual.result && expected.resultHash == actual.resultHash &&
        expected.previousJournalHash == actual.previousJournalHash && expected.journalHash == actual.journalHash &&
        expected.aggregateWrites.size == actual.aggregateWrites.size &&
        expected.aggregateWrites.zip(actual.aggregateWrites).all { (a, b) -> a.key == b.key &&
            a.storageGeneration == b.storageGeneration && a.schema == b.schema && a.payload == b.payload &&
            a.payloadDigest == b.payloadDigest } && expected.claimChanges.size == actual.claimChanges.size &&
        expected.claimChanges.zip(actual.claimChanges).all { (a, b) ->
            a.key == b.key && a.ownerId == b.ownerId && a.action == b.action } && expected.jobs.size == actual.jobs.size &&
        expected.jobs.zip(actual.jobs).all { (a, b) -> a.kind == b.kind && a.body == b.body })
}
