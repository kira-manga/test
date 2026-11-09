package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockFailure
import com.ukkera.pos.inventory.store.LocalStockHydrationCapacityUnavailable
import com.ukkera.pos.inventory.store.LocalStockRejected
import com.ukkera.pos.sales.salesAuthorizationRequirement
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId

/** Source-only disclosure of a successful native FOOD stream. The installed whole
 * stock/FOOD/catalogue proof finishes before any original owner or amount is interpreted.
 * A scalar refusal has no qualified financial BEFORE source and remains unavailable. */
internal suspend fun foodCashFinancialRetainedStockRequirements(normalized: StoredBytes, view: FoodReadView,
    context: CommandContext): List<AuthorizationRequirement>? = foodCashFinancialRpcSources {
    if (context.scope != view.scope || context.key != StockStreamingCommandsV2.FOOD ||
        LocalFoodStockCodecV2.normalize(normalized) != normalized ||
        semanticDigest(context.scope, context.originalActorId, context.key, normalized) != context.semanticDigest)
        return@foodCashFinancialRpcSources null
    val request = (LocalFoodStockCodecV2.decode(normalized) as? LocalFoodStockRequestV2.Financial)?.request
        ?: return@foodCashFinancialRpcSources null
    if (request !is LocalFoodFinancialRequestV1.BeginCollection && request !is LocalFoodFinancialRequestV1.FinalizePaid)
        return@foodCashFinancialRpcSources null
    val retained = (view.stockRelationalAccess().originals.read(context.operationId) as? RetainedOriginalV2.Streamed)
        ?.evidence ?: return@foodCashFinancialRpcSources null
    val identity = retained.identity
    if (identity.scope != context.scope || identity.producer.operationId != context.operationId ||
        identity.producer.command != context.key || identity.producer.originalActorId != context.originalActorId ||
        identity.producer.semanticDigest != context.semanticDigest || retained.normalized != normalized)
        return@foodCashFinancialRpcSources null
    val completed = view.foodStockProducer(context.operationId)
    check(completed.identity == identity && completed.journalHash == retained.journalHash &&
        completed.normalized == normalized && completed.result == retained.result)
    val sources = LocalCommerceOriginalSources(completed)
    when (request) {
        is LocalFoodFinancialRequestV1.BeginCollection -> {
            val order = foodCashFinancialBeforeFood(sources, LocalFoodProfileV1.ORDER, request.orderId)
                ?: return@foodCashFinancialRpcSources null
            val body = order.body as? FoodBodyV1.Order ?: return@foodCashFinancialRpcSources null
            val index = foodCashFinancialBeforeFood(sources, LocalFoodProfileV1.OWNER_INDEX, body.ownerId)
                ?: return@foodCashFinancialRpcSources null
            val members = (index.body as? FoodBodyV1.OwnerIndex)?.orderIds ?: return@foodCashFinancialRpcSources null
            check(order.revision == request.expectedOrderRevision && request.orderId in members &&
                body.ownerId == identity.producer.originalActorId && body.handoff == null && body.closedByOperationId == null)
            salesAuthorizationRequirement(SecurityAction.COLLECTION_BEGIN, context.scope, request.orderId,
                request.expectedOrderRevision, context.operationId, context.semanticDigest, "food.collection.begin",
                body.ownerId, body.originalQuotedTotal)
        }
        is LocalFoodFinancialRequestV1.FinalizePaid -> {
            val source = foodCashFinancialBeforeCollection(sources, request.collectionId)
                ?: return@foodCashFinancialRpcSources null
            val collection = foodFinancialCollection(source)
            check(collection.revision == request.expectedCollectionRevision &&
                collection.currentOwnerId == identity.producer.originalActorId)
            salesAuthorizationRequirement(if (collection.quote.payable.minor == 0L) SecurityAction.COLLECTION_FINALIZE_ZERO
                else SecurityAction.COLLECTION_FINALIZE, context.scope, request.collectionId,
                request.expectedCollectionRevision, context.operationId, context.semanticDigest, "food.collection.finalize",
                collection.currentOwnerId, collection.quote.payable)
        }
        else -> null
    }
}

/** Successful SQL accounting rejoins the authentic Apply, Start, native raw and all
 * original stock/FOOD/cash predecessors. No current slot/application or prepare is used.
 * AlreadyApplied additionally requires its actual native BEFORE cut. Other refusals
 * cannot substitute today's application revision for a missing original observation. */
internal suspend fun foodCashFinancialRetainedPaymentRequirements(normalized: StoredBytes, view: FoodReadView,
    context: CommandContext, recipes: TransactionFoodStockSource): List<AuthorizationRequirement>? = foodCashFinancialRpcSources {
    if (context.scope != view.scope || context.key != LocalFoodPaymentProfile.COMMAND ||
        LocalFoodPaymentCodec.normalize(normalized) != normalized ||
        semanticDigest(context.scope, context.originalActorId, context.key, normalized) != context.semanticDigest)
        return@foodCashFinancialRpcSources null
    val request = LocalFoodPaymentCodec.decode(normalized) as? LocalFoodPaymentRequest.ApplyOriginal
        ?: return@foodCashFinancialRpcSources null
    val retained = view.retainedCommand(context.operationId) ?: return@foodCashFinancialRpcSources null
    if (retained.nativeOriginalProvenance != null || retained.scope != context.scope ||
        retained.operationId != context.operationId || retained.command != context.key ||
        retained.originalActorId != context.originalActorId || retained.semanticDigest != context.semanticDigest ||
        retained.normalized != normalized) return@foodCashFinancialRpcSources null
    if (!retained.committed) {
        if (retained.code != "food.payment.already_applied") return@foodCashFinancialRpcSources null
        val sources = view.foodPaymentAlreadyAppliedOriginalSources(context.operationId)
            ?: return@foodCashFinancialRpcSources null
        requireSameFoodPaymentOriginal(sources.evidence, retained)
        return@foodCashFinancialRpcSources foodPaymentAlreadyAppliedRetainedRequirement(sources, view, recipes)
    }
    val completed = view.foodProducer(context.operationId)
    requireSameFoodPaymentOriginal(completed, retained)
    view.requireFoodPredecessors(completed)
    val lineage = foodPaymentRetainedLineage(completed) { operation ->
        view.retainedCommand(operation) ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    }
    val raw = lineage.originalRaw ?: return@foodCashFinancialRpcSources null
    val original = LocalFoodPaymentProducer.requireRaw(raw, lineage.start)
    val applied = LocalFoodPaymentProducer.requireApply(completed, lineage.start, raw)
    val slot = original.slot
    check(slot.target.recordingOperationId == request.recordingOperationId && slot.originalActorId == retained.originalActorId &&
        original.raw.recordingActorId == retained.originalActorId && applied.cut.collection.id == slot.target.collectionId)
    LocalFoodPaymentProducer.requireStart(lineage.start).cut.portions.forEach {
        recipes.requireLegacyRecipe((it.body as FoodBodyV1.Portion).recipe, context.scope)
    }
    salesAuthorizationRequirement(SecurityAction.COLLECTION_ATTEST, context.scope, applied.cut.collection.id,
        applied.cut.collection.revision, context.operationId, context.semanticDigest, "food.payment.apply",
        slot.originalActorId, slot.domain.amount)
}

private class FoodCashFinancialRpcBefore(val row: LocalCommerceOriginalRow, val original: RetainedOriginalV2)

/** Called only after the root's complete mixed producer traversal. Require a genuine
 * Aggregate source and its exact original AFTER write; Current/header/hash alone cannot
 * install a previous owner, quote or monetary limit. The native source claim is mandatory. */
private suspend fun foodCashFinancialBefore(sources: LocalCommerceOriginalSources, key: AggregateKey): FoodCashFinancialRpcBefore? {
    val row = sources.aggregateOriginal(key) ?: return null
    check(sources.claim(foodSourceClaim(key)) == key.id)
    var observation: StreamedSourceObservationV2.Aggregate? = null
    sources.original.frames { frame ->
        val source = (frame as? RetainedCommandFrameV2.Source)?.value as? StreamedSourceObservationV2.Aggregate
        if (source?.key == key) { check(observation == null); observation = source }
    }
    val source = observation ?: return null
    check(source.generation == row.generation && source.schema == row.schema && source.payload == row.payload &&
        source.payloadHash == row.digest)
    val original = sources.original(source.producer.operationId) ?: return null
    val consumer = sources.original.identity
    when (original) {
        is RetainedOriginalV2.Streamed -> {
            val evidence = original.evidence
            val identity = evidence.identity
            check(identity.scope == sources.scope && identity.producer == source.producer &&
                identity.producer.operationId != consumer.producer.operationId &&
                (identity.assignmentId != consumer.assignmentId || identity.authorityEpoch != consumer.authorityEpoch ||
                    identity.sequence < consumer.sequence))
            val write = evidence.writeRow(key) ?: return null
            check(write.revision == row.generation && write.schema == row.schema && write.payload == row.payload && write.digest == row.digest)
        }
        is RetainedOriginalV2.Legacy -> {
            val evidence = original.evidence
            check(evidence.committed && evidence.nativeOriginalProvenance == null && evidence.scope == sources.scope &&
                StreamedProducerIdentityV2(evidence.operationId, evidence.command, evidence.originalActorId, evidence.semanticDigest) == source.producer &&
                evidence.operationId != consumer.producer.operationId &&
                (evidence.assignmentId != consumer.assignmentId || evidence.authorityEpoch != consumer.authorityEpoch ||
                    evidence.sequence < consumer.sequence))
            val write = evidence.aggregateWrites.singleOrNull { it.key == key } ?: return null
            check(write.storageGeneration == row.generation && write.schema == row.schema && write.payload == row.payload &&
                write.payloadDigest == row.digest)
        }
    }
    return FoodCashFinancialRpcBefore(row, original)
}

private suspend fun foodCashFinancialBeforeFood(sources: LocalCommerceOriginalSources, kind: String, id: DomainId): FoodEnvelopeV1? {
    val before = foodCashFinancialBefore(sources, LocalFoodProfileV1.key(kind, id)) ?: return null
    if (!foodStreamedSchema(kind, before.row.schema) && before.row.schema != LocalFoodProfileV1.schema(kind)) return null
    return foodStoredEnvelope(before.row.schema, before.row.payload).also {
        check(it.scope == sources.scope && it.kind == kind && it.id == id && it.revision == before.row.generation)
        sources.aggregateOriginal(before.row.key, it.operationId)
    }
}

private suspend fun foodCashFinancialBeforeCollection(sources: LocalCommerceOriginalSources, id: DomainId): FoodFinancialEnvelopeV1? {
    val kind = LocalFoodFinancialProfileV1.COLLECTION
    val before = foodCashFinancialBefore(sources, AggregateKey(kind, id)) ?: return null
    val value = when (before.row.schema) {
        foodStockSchema(kind) -> {
            val original = (before.original as? RetainedOriginalV2.Streamed)?.evidence ?: return null
            if (original.identity.producer.command != StockStreamingCommandsV2.FOOD) return null
            foodStockReadDocument(original, before.row.payload)
        }
        foodFinancialSchemaV2(kind) -> {
            val original = (before.original as? RetainedOriginalV2.Legacy)?.evidence ?: return null
            val reader = when {
                isFoodPaymentFinancialProducer(original) -> FoodFinancialBundleReaderV2(original,
                    foodPaymentStoredEvent(original).framing, allowStockInputs = true)
                original.command == LocalFoodProfileV1.COMMAND && isPaidFoodHandoffProducer(original) -> FoodFinancialBundleReaderV2(original,
                    paidFoodHandoffEvent(original).framing, allowStockInputs = true)
                original.command == LocalFoodFinancialProfileV1.COMMAND -> FoodFinancialBundleReaderV2(original,
                    foodFinancialStoredEvent(original).framing ?: return null)
                else -> return null
            }
            reader.document(before.row.payload)
        }
        LocalFoodFinancialProfileV1.schema(kind) -> {
            val original = (before.original as? RetainedOriginalV2.Legacy)?.evidence ?: return null
            if (original.command != LocalFoodFinancialProfileV1.COMMAND) return null
            foodFinancialEnvelope(before.row.payload)
        }
        else -> return null
    }
    check(value.scope == sources.scope && value.kind == kind && value.id == id && value.revision == before.row.generation)
    sources.aggregateOriginal(before.row.key, value.operationId)
    return value
}

private suspend fun foodCashFinancialRpcSources(read: suspend () -> AuthorizationRequirement?): List<AuthorizationRequirement>? =
    try { read()?.let { listOf(it) } }
    catch (_: FoodCapacityExceeded) { null }
    catch (_: LocalStockHydrationCapacityUnavailable) { null }
    catch (_: LocalSalesWireCapacityExceeded) { null }
    catch (_: LocalSalesSourceUnavailable) { null }
    catch (failure: LocalStockRejected) {
        if (failure.failure == LocalStockFailure.PRODUCER_UNAVAILABLE || failure.failure == LocalStockFailure.BOUND_EXCEEDED) null
        else throw failure
    }
