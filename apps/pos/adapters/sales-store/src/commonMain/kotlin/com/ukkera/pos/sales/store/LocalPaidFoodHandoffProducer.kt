package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

internal fun isPaidFoodHandoffProducer(value: RetainedCommandEvidence): Boolean =
    value.aggregateWrites.any { it.key.kind == LocalPaidFoodHandoffProfileV1.EVENT }

internal fun paidFoodHandoffEvent(value: RetainedCommandEvidence): PaidFoodHandoffEventV1 {
    val row = value.aggregateWrites.single { it.key == AggregateKey(LocalPaidFoodHandoffProfileV1.EVENT, DomainId(value.operationId.value)) }
    check(row.storageGeneration == Revision(1) && row.schema == LocalPaidFoodHandoffProfileV1.schema() &&
        row.payloadDigest == sha256(row.payload.copyBytes()))
    return localSalesDecode(row.payload, PaidFoodHandoffEventV1.serializer(), 65_536).also {
        check(it.version == 1 && it.inputIds.size in 1..FOOD_FINANCIAL_MAX_INPUTS_V2 &&
            it.inputIds.distinct().size == it.inputIds.size && it.after.size in 2..5 &&
            it.after.map { ref -> ref.kind to ref.id }.distinct().size == it.after.size && it.framing.cash == null)
    }
}

/** Literal decoding only. The same-loan read view separately crosswalks every actual original. */
internal fun paidFoodHandoffInputs(value: RetainedCommandEvidence,
    frames: FoodFinancialBundleReaderV2? = null): List<FoodFinancialInputV1> {
    val event = paidFoodHandoffEvent(value)
    val inputs = (frames ?: FoodFinancialBundleReaderV2(value, event.framing, allowStockInputs = true)).inputs()
    check(inputs.size == event.inputIds.size && event.inputIds == inputs.map { foodFinancialInputId(value.operationId, it.key()) })
    for (input in inputs) {
        check(input.stock == null && input.operationId != value.operationId && input.revision.value >= 1 &&
            sha256(input.bytes().copyBytes()) == input.digest)
        when (input.kind) {
            LocalFoodProfileV1.ORDER, LocalFoodProfileV1.OWNER_INDEX, LocalFoodProfileV1.PORTION, LocalFoodProfileV1.PRICE_BATCH -> {
                check(input.financial == null && input.schema in setOf(LocalFoodProfileV1.schema(input.kind), foodStockSchema(input.kind)))
                val source = foodEnvelope(input.bytes())
                check(source.scope == value.scope && source.kind == input.kind && source.id == input.id &&
                    source.revision == input.revision && source.operationId == input.operationId)
            }
            LocalFoodHandoffProfileV2.OFFER -> {
                check(input.financial == null && input.schema == LocalFoodHandoffProfileV2.schema(input.kind))
                val source = foodHandoffEnvelopeV2(input.bytes())
                check(source.scope == value.scope && source.id == input.id && source.revision == input.revision && source.operationId == input.operationId)
            }
            LocalFoodFinancialProfileV1.COLLECTION, LocalFoodFinancialProfileV1.REFUND, LocalFoodFinancialProfileV1.CLAIM_INDEX -> {
                val source = checkNotNull(input.financial)
                check(source.scope == value.scope && source.kind == input.kind && source.id == input.id &&
                    source.revision == input.revision && source.operationId == input.operationId)
                check(input.schema == foodStockSchema(input.kind) || input.schema ==
                    if (source.framed) foodFinancialSchemaV2(input.kind) else LocalFoodFinancialProfileV1.schema(input.kind))
            }
            else -> error("food.handoff.unrecognized_input")
        }
    }
    return inputs
}

/** Complete closed effects, not planner replay, a supplied permission proof or a source-name allowlist. */
internal object LocalPaidFoodHandoffProducer {
    fun requireComplete(value: RetainedCommandEvidence): List<LocalFoodRecipeSnapshotV1> = PaidFoodHandoffProof(value).validate()
}

private class PaidFoodHandoffProof(private val evidence: RetainedCommandEvidence) {
    private val request = LocalFoodCodecV1.decode(evidence.normalized)
    private val event = paidFoodHandoffEvent(evidence)
    private val frames = FoodFinancialBundleReaderV2(evidence, event.framing, allowStockInputs = true)
    private val inputs = paidFoodHandoffInputs(evidence, frames).associateBy { it.key() }
    private val usedInputs = mutableSetOf<AggregateKey>()
    private val usedAfter = mutableSetOf<AggregateKey>()
    private val food = evidence.aggregateWrites.filter { it.key.kind in setOf(LocalFoodProfileV1.ORDER, LocalFoodProfileV1.OWNER_INDEX) }
        .associate { it.key to foodEnvelope(it.payload) }
    private val collection = evidence.aggregateWrites.singleOrNull { it.key.kind == LocalFoodFinancialProfileV1.COLLECTION }
        ?.let { it.key to frames.document(it.payload) }
    private val offers = evidence.aggregateWrites.filter { it.key.kind == LocalFoodHandoffProfileV2.OFFER }
        .associate { it.key to foodHandoffEnvelopeV2(it.payload) }
    private val afterKeys = food.keys + offers.keys + listOfNotNull(collection?.first)

    fun validate(): List<LocalFoodRecipeSnapshotV1> {
        check(request is LocalFoodRequestV1.OfferHandoff || request is LocalFoodRequestV1.AcceptHandoff || request is LocalFoodRequestV1.CancelHandoff)
        val expectation = request.foodHandoffFinancialV1()
        check(evidence.committed && evidence.command == LocalFoodProfileV1.COMMAND &&
            LocalFoodCodecV1.normalize(evidence.normalized) == evidence.normalized &&
            semanticDigest(evidence.scope, evidence.originalActorId, evidence.command, evidence.normalized) == evidence.semanticDigest)
        check(event.scope == evidence.scope && event.operationId == evidence.operationId && event.actorId == evidence.originalActorId &&
            event.recordedAt == evidence.recordedAt && event.after.size == afterKeys.size && event.inputIds.size == inputs.size)
        val keys = afterKeys + frames.chunkKeys + AggregateKey(LocalPaidFoodHandoffProfileV1.EVENT, DomainId(evidence.operationId.value))
        check(evidence.aggregateWrites.map { it.key }.toSet() == keys &&
            evidence.aggregateWrites.map { it.key }.distinct().size == evidence.aggregateWrites.size && evidence.jobs.isEmpty())
        for (key in afterKeys) {
            val row = evidence.aggregateWrites.single { it.key == key }
            val ref = event.after.single { it.kind == key.kind && it.id == key.id }
            check(row.payloadDigest == sha256(row.payload.copyBytes()) && row.storageGeneration == ref.revision &&
                ref.digest == row.payloadDigest && row.storageGeneration == (inputs[key]?.revision?.next() ?: Revision(1)))
            if (key in food) {
                val value = food.getValue(key)
                check(row.schema == LocalFoodProfileV1.schema(key.kind) && foodEnvelopeBytes(value) == row.payload &&
                    value.scope == evidence.scope && value.kind == key.kind && value.id == key.id &&
                    value.revision == row.storageGeneration && value.operationId == evidence.operationId)
            } else if (key in offers) {
                val value = offers.getValue(key)
                check(row.schema == LocalFoodHandoffProfileV2.schema(key.kind) && foodHandoffBytesV2(value) == row.payload &&
                    value.scope == evidence.scope && value.id == key.id && value.revision == row.storageGeneration && value.operationId == evidence.operationId)
            } else {
                val value = checkNotNull(collection).second
                check(row.schema == foodFinancialSchemaV2(key.kind) && value.scope == evidence.scope && value.id == key.id &&
                    value.kind == key.kind && value.revision == row.storageGeneration && value.operationId == evidence.operationId)
            }
        }
        val oldRow = beforeFood(LocalFoodProfileV1.ORDER, request.foodOrderId())
        val old = oldRow.body as FoodBodyV1.Order
        check(oldRow.revision == request.foodOrderRevision() && old.closedByOperationId == null && old.financialCollectionId == expectation?.collectionId)
        val value = expectation?.let { foodFinancialCollection(beforeFinancial(LocalFoodFinancialProfileV1.COLLECTION, it.collectionId)) }
        if (value != null) check(value.orderId == request.foodOrderId() && value.revision == checkNotNull(expectation).expectedCollectionRevision &&
            value.originalCreatorId == old.creatorId && value.currentOwnerId == old.ownerId)
        val portions = old.portionIds.map { beforeFood(LocalFoodProfileV1.PORTION, it) }
        val batches = old.batchIds.map { beforeFood(LocalFoodProfileV1.PRICE_BATCH, it) }
        if (value != null) requireFoodFinancialQuote(value, old, portions, batches)
        else requireUnboundHandoffInputs(request.foodOrderId(), old, portions, batches)
        val ownIndex = beforeFood(LocalFoodProfileV1.OWNER_INDEX, old.ownerId).body as FoodBodyV1.OwnerIndex
        check(request.foodOrderId() in ownIndex.orderIds)
        val refund = value?.cancellationRefundId?.let { foodFinancialRefund(beforeFinancial(LocalFoodFinancialProfileV1.REFUND, it)) }
        refund?.let { requireFoodRefundSource(checkNotNull(value), it) }
        val orderId = request.foodOrderId()
        val claimKey = AggregateKey(LocalFoodFinancialProfileV1.CLAIM_INDEX, foodClaimIndexId(FoodClaimIndexTargetV1.ORDER, orderId))
        if (claimKey in inputs) {
            check(value != null)
            val claims = beforeFinancial(claimKey.kind, claimKey.id).body as FoodFinancialBodyV1.ClaimIndex
            check(claims.target == FoodClaimIndexTargetV1.ORDER && claims.targetId == orderId)
        }
        val accepting = request is LocalFoodRequestV1.AcceptHandoff
        val offering = request is LocalFoodRequestV1.OfferHandoff
        if (value != null && (offering || accepting)) check(!value.unresolved && refund?.unresolved != true &&
            value.responsibility.size < FoodCollections.MAX_RESPONSIBILITY_TRANSITIONS)
        val id = request.foodHandoffId()
        val offerKey = AggregateKey(LocalFoodHandoffProfileV2.OFFER, id)
        val afterOffer = offers.getValue(offerKey).offer.also { check(usedAfter.add(offerKey)) }
        val orderKey = AggregateKey(LocalFoodProfileV1.ORDER, orderId)
        val nextRow = food.getValue(orderKey).also { check(usedAfter.add(orderKey)) }
        val next = nextRow.body as FoodBodyV1.Order
        val extra = mutableListOf<ClaimChange>()
        if (offering) {
            val selected = request as LocalFoodRequestV1.OfferHandoff
            check(old.ownerId == evidence.originalActorId && old.handoff == null && selected.recipientId != old.ownerId &&
                offerKey !in inputs && collection == null)
            recipientIndex(selected.recipientId, orderId, false)
            check(next == old.copy(handoff = FoodHandoffV1(id, old.ownerId, selected.recipientId, evidence.recordedAt)))
            check(afterOffer == FoodHandoffOfferV2(orderId, old.ownerId, selected.recipientId, evidence.recordedAt,
                FoodHandoffStateV2.OFFERED, false, financial = expectation))
            extra += ClaimChange.Acquire(LocalFoodProfileV1.claim("handoff", id), orderId)
            extra += ClaimChange.Acquire(LocalFoodHandoffProfileV2.incoming(selected.recipientId, id), orderId)
        } else {
            val pending = checkNotNull(old.handoff)
            check(pending.id == id && pending.from == old.ownerId && pending.offeredAt <= evidence.recordedAt &&
                evidence.originalActorId == if (accepting) pending.to else pending.from)
            val priorOffer = inputs[offerKey]?.let {
                check(usedInputs.add(offerKey)); foodHandoffEnvelopeV2(it.bytes()).offer
            }
            if (priorOffer != null) {
                check(priorOffer == FoodHandoffOfferV2(orderId, old.ownerId, pending.to, pending.offeredAt,
                    FoodHandoffStateV2.OFFERED, false, financial = priorOffer.financial) && priorOffer.financial?.collectionId == value?.id)
                if (accepting) check(priorOffer.financial == expectation)
                check(afterOffer == priorOffer.copy(state = if (accepting) FoodHandoffStateV2.ACCEPTED else FoodHandoffStateV2.CANCELLED,
                    resolvedAt = evidence.recordedAt, resolvedBy = evidence.originalActorId))
                extra += ClaimChange.Release(LocalFoodHandoffProfileV2.incoming(pending.to, id), orderId)
            } else {
                check(value == null && expectation == null)
                check(afterOffer == FoodHandoffOfferV2(orderId, old.ownerId, pending.to, pending.offeredAt,
                    if (accepting) FoodHandoffStateV2.ACCEPTED else FoodHandoffStateV2.CANCELLED,
                    legacyOffer = true, resolvedAt = evidence.recordedAt, resolvedBy = evidence.originalActorId))
                // foodHandoffLegacyOriginInputV2 adds the SAME required original-v1 lineage
                // obligation in current-view and retained-only proofs; this shape is not proof.
            }
            check(next == old.copy(ownerId = if (accepting) pending.to else old.ownerId, handoff = null))
            if (accepting) {
                if (value != null) {
                    val (key, afterCollection) = checkNotNull(collection)
                    check(key == AggregateKey(LocalFoodFinancialProfileV1.COLLECTION, value.id) && usedAfter.add(key))
                    check(FoodFinancialSnapshots.isResponsibilitySuccessor(value, foodFinancialCollection(afterCollection),
                        FoodCollectionResponsibilityV1(id, evidence.operationId, pending.from, pending.to, pending.offeredAt,
                            evidence.recordedAt, value.revision, value.attempts.size)))
                } else check(collection == null)
                val ownerKey = AggregateKey(LocalFoodProfileV1.OWNER_INDEX, old.ownerId)
                check(usedAfter.add(ownerKey))
                check((food.getValue(ownerKey).body as FoodBodyV1.OwnerIndex).orderIds == ownIndex.orderIds - orderId)
                recipientIndex(pending.to, orderId, true)
                extra += ClaimChange.Acquire(LocalFoodProfileV1.claim("accepted-handoff", id), orderId)
            } else check(collection == null)
        }
        check(usedInputs == inputs.keys && usedAfter == afterKeys)
        frames.requireCompleteLeaves(event.after)
        val result = foodResult(foodResultCode(request), nextRow)
        check(event.result == result && evidence.result == localSalesEncode(result, LocalFoodResultV1.serializer()) &&
            evidence.resultHash == sha256(evidence.result.copyBytes()))
        val claims = afterKeys.filter { it !in inputs }.map { key ->
            check(key.kind != LocalFoodFinancialProfileV1.COLLECTION)
            ClaimChange.Acquire(LocalFoodProfileV1.claim(key.kind, key.id), key.id)
        } + extra
        check(evidence.claimChanges.size == claims.size && evidence.claimChanges.map { it.key }.distinct().size == claims.size &&
            claims.all { expected -> evidence.claimChanges.any { actual -> actual.key == expected.key && actual.ownerId == expected.ownerId &&
                actual.action == if (expected is ClaimChange.Acquire) RetainedClaimAction.ACQUIRE else RetainedClaimAction.RELEASE } })
        return portions.map { (it.body as FoodBodyV1.Portion).recipe }.distinct()
    }

    private fun beforeFood(kind: String, id: DomainId): FoodEnvelopeV1 {
        val key = AggregateKey(kind, id); check(usedInputs.add(key)); return foodEnvelope(inputs.getValue(key).bytes())
    }
    private fun beforeFinancial(kind: String, id: DomainId): FoodFinancialEnvelopeV1 {
        val key = AggregateKey(kind, id); check(usedInputs.add(key)); return checkNotNull(inputs.getValue(key).financial)
    }
    private fun recipientIndex(recipient: DomainId, orderId: DomainId, accepting: Boolean) {
        val key = AggregateKey(LocalFoodProfileV1.OWNER_INDEX, recipient)
        val prior = if (key in inputs) (beforeFood(key.kind, key.id).body as FoodBodyV1.OwnerIndex).orderIds else emptyList()
        check(orderId !in prior && prior.size < LocalFoodProfileV1.MAX_ACTIVE_INDEX)
        if (accepting) {
            check(usedAfter.add(key))
            check((food.getValue(key).body as FoodBodyV1.OwnerIndex).orderIds == prior + orderId)
        }
    }
}

/** One exact normalized literal, while the real retained handle remains loan-bound. */
internal suspend fun requirePaidFoodStreamedInput(original: RetainedStreamedCommand, input: FoodFinancialInputV1) {
    check(original.identity.producer.operationId == input.operationId && original.identity.producer.command == StockStreamingCommandsV2.FOOD)
    var matched = false
    original.frames { frame ->
        if (frame is RetainedCommandFrameV2.Write && frame.value.key == input.key()) {
            val row = frame.value
            check(!matched && row.storageGeneration == input.revision && row.schema == input.schema &&
                row.payload == input.bytes() && row.payloadDigest == input.digest && row.payloadDigest == sha256(row.payload.copyBytes()))
            matched = true
        }
    }
    check(matched) { "food.handoff.normalized_original_missing" }
}
