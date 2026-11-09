package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.sales.salesAuthorizationRequirement
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Exact production foodStockAdmission on genuine native BEFORE sources. No absent-row
 * amount/owner fallback, employee session, AuthorizedAction or historical view is created.
 * The caller still runs the installed WHOLE stock/FoodStockOriginalProof/catalog traversal;
 * this function neither executes a handler nor proves coverage, pickup or a refund itself. */
internal suspend fun requireNonphysicalFoodFinancialHistoryRequirement(command: RuntimeHistoricalCommand,
    request: LocalFoodFinancialRequestV1): Boolean {
    if (!foodNonphysicalRequest(request)) return false
    val expected = when (request) {
        is LocalFoodFinancialRequestV1.BeginCollection -> {
            val order = requiredFoodFinancialHistoryOrder(command, request.orderId, request.expectedOrderRevision)
            salesAuthorizationRequirement(SecurityAction.COLLECTION_BEGIN, command.scope, request.orderId,
                request.expectedOrderRevision, command.operationId, command.semanticDigest, "food.collection.begin",
                order.ownerId, order.originalQuotedTotal)
        }
        is LocalFoodFinancialRequestV1.FinalizePaid -> {
            val collection = foodFinancialCollection(requiredFoodFinancialHistoryDocument(command,
                LocalFoodFinancialProfileV1.COLLECTION, request.collectionId))
            salesAuthorizationRequirement(if (collection.quote.payable.minor == 0L) SecurityAction.COLLECTION_FINALIZE_ZERO
                else SecurityAction.COLLECTION_FINALIZE, command.scope, request.collectionId,
                request.expectedCollectionRevision, command.operationId, command.semanticDigest,
                "food.collection.finalize", collection.currentOwnerId, collection.quote.payable)
        }
        is LocalFoodFinancialRequestV1.SendReserved -> {
            val order = requiredFoodFinancialHistoryOrder(command, request.orderId, request.expectedOrderRevision)
            salesAuthorizationRequirement(SecurityAction.ORDER_EDIT, command.scope, request.orderId,
                request.expectedOrderRevision, command.operationId, command.semanticDigest, "food.collection.send_reserved",
                order.ownerId)
        }
        is LocalFoodFinancialRequestV1.PaidPickup -> {
            val order = requiredFoodFinancialHistoryOrder(command, request.orderId, request.expectedOrderRevision)
            salesAuthorizationRequirement(SecurityAction.ORDER_EDIT, command.scope, request.orderId,
                request.expectedOrderRevision, command.operationId, command.semanticDigest, "food.paid_pickup", order.ownerId)
        }
        is LocalFoodFinancialRequestV1.CancelWholeUnsent -> {
            val collection = foodFinancialCollection(requiredFoodFinancialHistoryDocument(command,
                LocalFoodFinancialProfileV1.COLLECTION, request.collectionId))
            salesAuthorizationRequirement(SecurityAction.COLLECTION_RESOLVE, command.scope, request.collectionId,
                request.expectedCollectionRevision, command.operationId, command.semanticDigest,
                "food.cancel_whole_unsent", collection.currentOwnerId, collection.collected)
        }
        is LocalFoodFinancialRequestV1.CloseResolvedWork -> {
            val order = requiredFoodFinancialHistoryOrder(command, request.orderId, request.expectedOrderRevision)
            salesAuthorizationRequirement(SecurityAction.ORDER_EDIT, command.scope, request.orderId,
                request.expectedOrderRevision, command.operationId, command.semanticDigest,
                "food.close_resolved_work", order.ownerId)
        }
        is LocalFoodFinancialRequestV1.RecordQualityClaim -> salesAuthorizationRequirement(SecurityAction.RETURN_CLAIM,
            command.scope, request.claimId, Revision(0), command.operationId, command.semanticDigest,
            "food.quality.claim", command.actorId)
        else -> return false
    }
    command.requireRequirement(expected)
    return true
}

/** Read-only typed join for EVERY literal financial source, including sale/rights/refund and
 * present claim/refund indexes used by closure. A present incompatible index never becomes
 * empty rights or absence. Absence is checked separately against the complete native cut and
 * the installed original proof requires its exact observation/claim; no absent DTO is made.
 * Legacy/uninstalled physical sources remain unavailable. Only genuine separately joined
 * FOOD CASH collection Start/Apply roots are recognized, never a retail funding alias. */
internal suspend fun requireFoodFinancialHistorySource(command: RuntimeHistoricalCommand,
    source: RuntimeHistoricalAggregateSource): FoodFinancialEnvelopeV1 {
    val row = source.row
    val key = row.key
    check(key.kind in foodFinancialDocumentKinds && command.claim(foodSourceClaim(key)) == key.id &&
        row.digest == sha256(row.payload.copyBytes()))
    val original = requireEarlierFoodFinancialSource(command, source)
    val framed = key.kind in setOf(LocalFoodFinancialProfileV1.COLLECTION, LocalFoodFinancialProfileV1.SALE,
        LocalFoodFinancialProfileV1.ENTITLEMENT, LocalFoodFinancialProfileV1.REFUND)
    val value = when (original) {
        is RetainedOriginalV2.Streamed -> {
            check(row.schema == foodStockSchema(key.kind))
            if (framed) foodStockReadDocument(original.evidence, row.payload) { chunkKey, bytes ->
                val chunk = command.aggregateSource(chunkKey) ?: foodFinancialHistoryUnavailable()
                check(chunk.original === source.original && chunk.row.key == chunkKey && chunk.row.revision == Revision(1) &&
                    chunk.row.schema == foodStockSchema(LocalFoodFinancialProfileV1.FRAME) && chunk.row.payload == bytes &&
                    chunk.row.digest == sha256(bytes.copyBytes()))
            } else foodFinancialEnvelope(row.payload)
        }
        is RetainedOriginalV2.Legacy -> {
            // Raw/native originals cannot write a financial root. This actual earlier scalar
            // Start/Apply has ALREADY completed its complete native business join, and its
            // whole literal producer additionally binds the genuine immutable Start/raw pair.
            check(row.schema == foodFinancialSchemaV2(key.kind))
            val framing = when {
                key.kind == LocalFoodFinancialProfileV1.COLLECTION && isFoodPaymentFinancialProducer(original.evidence) ->
                    foodPaymentStoredEvent(original.evidence).framing
                key.kind == LocalFoodFinancialProfileV1.REFUND && isFoodRefundFinancialProducer(original.evidence) ->
                    foodRefundStoredEvent(original.evidence).framing
                else -> foodFinancialHistoryUnavailable()
            }
            val reader = FoodFinancialBundleReaderV2(original.evidence, framing, allowStockInputs = true)
            for (descriptor in framing.chunks) {
                val chunkKey = AggregateKey(LocalFoodFinancialProfileV1.FRAME, descriptor.id)
                val chunk = command.aggregateSource(chunkKey) ?: foodFinancialHistoryUnavailable()
                val literal = original.evidence.aggregateWrites.single { it.key == chunkKey }
                check(chunk.original === source.original && chunk.row.revision == Revision(1) &&
                    chunk.row.schema == foodFinancialSchemaV2(LocalFoodFinancialProfileV1.FRAME) &&
                    chunk.row.payload.size == descriptor.byteCount && chunk.row.payload == literal.payload &&
                    chunk.row.digest == descriptor.digest && chunk.row.digest == literal.payloadDigest)
            }
            reader.document(row.payload)
        }
    }
    val operation = when (original) {
        is RetainedOriginalV2.Streamed -> original.evidence.identity.producer.operationId
        is RetainedOriginalV2.Legacy -> original.evidence.operationId
    }
    check(value.scope == command.scope && value.kind == key.kind && value.id == key.id && value.revision == row.revision && value.operationId == operation)
    if (framed && original is RetainedOriginalV2.Streamed) requireFoodStockFinancialLiteral(row.payload, value)
    return value
}

private suspend fun requiredFoodFinancialHistoryDocument(command: RuntimeHistoricalCommand,
    kind: String, id: DomainId): FoodFinancialEnvelopeV1 = requireFoodFinancialHistorySource(command,
    command.aggregateSource(AggregateKey(kind, id)) ?: foodFinancialHistoryUnavailable())

private suspend fun requiredFoodFinancialHistoryOrder(command: RuntimeHistoricalCommand,
    id: DomainId, revision: Revision): FoodBodyV1.Order {
    val key = AggregateKey(LocalFoodProfileV1.ORDER, id)
    val source = command.aggregateSource(key) ?: foodFinancialHistoryUnavailable()
    val original = requireEarlierNormalizedFoodFinancialSource(command, source)
    val row = source.row
    check(row.key == key && row.schema == foodStockSchema(key.kind) && row.revision == revision &&
        command.claim(foodSourceClaim(key)) == id && row.digest == sha256(row.payload.copyBytes()))
    val value = foodEnvelope(row.payload)
    check(value.scope == command.scope && value.kind == key.kind && value.id == id && value.revision == row.revision &&
        value.operationId == original.identity.producer.operationId)
    val order = value.body as? FoodBodyV1.Order ?: foodFinancialHistoryUnavailable()
    check(order.closedByOperationId == null && order.handoff == null)
    return order
}

private suspend fun requireEarlierNormalizedFoodFinancialSource(command: RuntimeHistoricalCommand,
    source: RuntimeHistoricalAggregateSource): RetainedStreamedCommand =
    (requireEarlierFoodFinancialSource(command, source) as? RetainedOriginalV2.Streamed)?.evidence ?: foodFinancialHistoryUnavailable()

private suspend fun requireEarlierFoodFinancialSource(command: RuntimeHistoricalCommand,
    source: RuntimeHistoricalAggregateSource): RetainedOriginalV2 {
    val currentAssignment = when (val current = command.original) {
        is RetainedOriginalV2.Streamed -> current.evidence.identity.assignmentId
        is RetainedOriginalV2.Legacy -> current.evidence.assignmentId
    }
    val currentEpoch = when (val current = command.original) {
        is RetainedOriginalV2.Streamed -> current.evidence.identity.authorityEpoch
        is RetainedOriginalV2.Legacy -> current.evidence.authorityEpoch
    }
    val currentSequence = when (val current = command.original) {
        is RetainedOriginalV2.Streamed -> current.evidence.identity.sequence
        is RetainedOriginalV2.Legacy -> current.evidence.sequence
    }
    when (val original = source.original) {
        is RetainedOriginalV2.Streamed -> {
            val identity = original.evidence.identity
            check(identity.producer.command == StockStreamingCommandsV2.FOOD && identity.scope == command.scope &&
                identity.assignmentId == currentAssignment && identity.authorityEpoch == currentEpoch && identity.sequence < currentSequence &&
                identity.producer.operationId != command.operationId && command.earlierOriginal(identity.producer.operationId) === source.original)
        }
        is RetainedOriginalV2.Legacy -> {
            val value = original.evidence
            check(source.row.key.kind in setOf(LocalFoodFinancialProfileV1.COLLECTION, LocalFoodFinancialProfileV1.REFUND) &&
                source.row.schema == foodFinancialSchemaV2(source.row.key.kind) &&
                value.committed && value.scope == command.scope && value.assignmentId == currentAssignment && value.authorityEpoch == currentEpoch &&
                value.sequence < currentSequence && value.operationId != command.operationId && command.earlierOriginal(value.operationId) === source.original)
            when (source.row.key.kind) {
                LocalFoodFinancialProfileV1.COLLECTION -> requireFoodPaymentHistoricalComplete(command, value)
                LocalFoodFinancialProfileV1.REFUND -> requireFoodRefundHistoricalComplete(command, value)
                else -> foodFinancialHistoryUnavailable()
            }
        }
    }
    return source.original
}

private fun foodFinancialHistoryUnavailable(): Nothing = error("food.history.financial_original_source_unavailable")
