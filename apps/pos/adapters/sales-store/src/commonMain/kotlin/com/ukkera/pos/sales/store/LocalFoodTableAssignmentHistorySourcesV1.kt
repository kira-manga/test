package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.types.*

/** Actual native BEFORE rows and their already fully joined writers; never a historical view. */
internal suspend fun foodTableAssignmentHistoricalOrder(command: RuntimeHistoricalCommand,
    orderId: DomainId): FoodTableAssignmentOrderCutV1? {
    val key = AggregateKey(LocalFoodProfileV1.ORDER, orderId)
    val source = command.aggregateSource(key)
    val claim = command.claim(LocalFoodProfileV1.claim(key.kind, key.id))
    if (source == null) {
        check(claim == null) { "food.table.assignment.history_claim_without_order" }
        return null
    }
    check(claim == orderId && source.row.key == key)
    val (order, orderReference) = foodTableAssignmentHistoricalFood(command, source)
    val body = order.body as? FoodBodyV1.Order ?: error("food.table.assignment.history_order_body")
    val indexKey = AggregateKey(LocalFoodProfileV1.OWNER_INDEX, body.ownerId)
    val indexSource = command.aggregateSource(indexKey) ?: error("food.table.assignment.history_owner_index_missing")
    check(indexSource.row.key == indexKey && command.claim(LocalFoodProfileV1.claim(indexKey.kind, indexKey.id)) == body.ownerId)
    val (index, indexReference) = foodTableAssignmentHistoricalFood(command, indexSource)
    return FoodTableAssignmentOrderCutV1(order, index, orderReference, indexReference).also {
        requireFoodTableAssignmentOrderCut(it, command.scope)
    }
}

private suspend fun foodTableAssignmentHistoricalFood(command: RuntimeHistoricalCommand,
    source: RuntimeHistoricalAggregateSource): Pair<FoodEnvelopeV1, FoodTableAssignmentFoodSourceV1> {
    val row = source.row
    val (operation, actor) = requireFoodTableAssignmentHistoricalFoodWriter(command, source.original)
    val reference = FoodTableAssignmentFoodSourceV1(row.key.kind, row.key.id, row.revision, row.schema, row.digest, operation, actor)
    val envelope = foodTableAssignmentSourceEnvelope(command.scope, reference, row.key, row.revision, row.schema, row.payload, row.digest)
    when (val original = source.original) {
        is RetainedOriginalV2.Streamed -> {
            check(foodStreamedSchema(row.key.kind, row.schema))
            requireCommerceWrite(original.evidence, row)
        }
        is RetainedOriginalV2.Legacy -> {
            check(row.schema == LocalFoodProfileV1.schema(row.key.kind))
            val write = original.evidence.aggregateWrites.singleOrNull { it.key == row.key }
                ?: error("food.table.assignment.history_food_write_missing")
            check(write.storageGeneration == row.revision && write.schema == row.schema && write.payload == row.payload && write.payloadDigest == row.digest)
        }
    }
    return envelope to reference
}

/** Genuine immutable sources of a prior binding; current FOOD may have advanced since then. */
internal suspend fun foodTableAssignmentHistoricalRecordedOrder(command: RuntimeHistoricalCommand,
    record: FoodTableAssignmentRecordV1, consumer: RetainedCommandEvidence): FoodTableAssignmentOrderCutV1 {
    val order = foodTableAssignmentHistoricalRecordedFood(command, record.orderSource, consumer)
    val index = foodTableAssignmentHistoricalRecordedFood(command, record.ownerIndexSource, consumer)
    return FoodTableAssignmentOrderCutV1(order, index, record.orderSource, record.ownerIndexSource).also {
        requireFoodTableAssignmentOrderCut(it, record.scope)
    }
}

private suspend fun foodTableAssignmentHistoricalRecordedFood(command: RuntimeHistoricalCommand,
    source: FoodTableAssignmentFoodSourceV1, consumer: RetainedCommandEvidence): FoodEnvelopeV1 {
    val original = command.earlierOriginal(source.operationId) ?: error("food.table.assignment.history_food_origin_missing")
    val (operation, actor) = requireFoodTableAssignmentHistoricalFoodWriter(command, original)
    check(operation == source.operationId && actor == source.actorId && operation != consumer.operationId)
    when (original) {
        is RetainedOriginalV2.Streamed -> {
            val value = original.evidence
            check(foodStreamedSchema(source.kind, source.schema) && value.identity.sequence < consumer.sequence &&
                value.identity.authorityEpoch <= consumer.authorityEpoch)
            val row = value.writeRow(AggregateKey(source.kind, source.id)) ?: error("food.table.assignment.history_food_original_write_missing")
            return foodTableAssignmentSourceEnvelope(command.scope, source, row.key, row.revision, row.schema, row.payload, row.digest)
        }
        is RetainedOriginalV2.Legacy -> {
            check(source.schema == LocalFoodProfileV1.schema(source.kind))
            requireFoodTableAssignmentChronology(original.evidence, consumer)
            val row = original.evidence.aggregateWrites.singleOrNull { it.key == AggregateKey(source.kind, source.id) }
                ?: error("food.table.assignment.history_food_original_write_missing")
            return foodTableAssignmentSourceEnvelope(command.scope, source, row.key, row.storageGeneration, row.schema, row.payload, row.payloadDigest)
        }
    }
}

/** An allowlisted schema is not the proof: earlierOriginal requires the whole native business join. */
private suspend fun requireFoodTableAssignmentHistoricalFoodWriter(command: RuntimeHistoricalCommand,
    original: RetainedOriginalV2): Pair<OperationId, DomainId> {
    val result = when (original) {
        is RetainedOriginalV2.Streamed -> {
            val identity = original.evidence.identity
            check(identity.scope == command.scope && identity.producer.command == StockStreamingCommandsV2.FOOD)
            identity.producer.operationId to identity.producer.originalActorId
        }
        is RetainedOriginalV2.Legacy -> {
            val value = original.evidence
            check(value.committed && value.nativeOriginalProvenance == null && value.scope == command.scope &&
                value.command in setOf(LocalFoodProfileV1.COMMAND, LocalFoodFinancialProfileV1.COMMAND,
                    LocalFoodPaymentProfile.COMMAND, LocalFoodPaymentSupervisorCodec.COMMAND, LocalFoodRefundProfile.COMMAND))
            value.operationId to value.originalActorId
        }
    }
    check(result.first != command.operationId && command.earlierOriginal(result.first) === original)
    return result
}
