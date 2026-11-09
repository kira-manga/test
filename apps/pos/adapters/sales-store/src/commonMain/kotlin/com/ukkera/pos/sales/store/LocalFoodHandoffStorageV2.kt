package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@Serializable internal enum class FoodHandoffStateV2 { OFFERED, ACCEPTED, CANCELLED }
@OptIn(ExperimentalSerializationApi::class)
@Serializable internal data class FoodHandoffOfferV2(val orderId: DomainId, val fromActorId: DomainId, val recipientId: DomainId,
    val offeredAt: UtcInstant, val state: FoodHandoffStateV2, val legacyOffer: Boolean,
    val resolvedAt: UtcInstant? = null, val resolvedBy: DomainId? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val financial: LocalFoodHandoffFinancialExpectationV1? = null)
@Serializable internal data class FoodHandoffEnvelopeV2(val scope: BranchScope, val id: DomainId, val revision: Revision,
    val operationId: OperationId, val offer: FoodHandoffOfferV2)
@Serializable internal data class FoodHandoffBeforeV2(val food: FoodEnvelopeV1? = null, val offer: FoodHandoffEnvelopeV2? = null) {
    init { require((food == null) != (offer == null)) }
}
@Serializable internal data class FoodHandoffEventV2(val version: Int, val scope: BranchScope, val operationId: OperationId,
    val actorId: DomainId, val recordedAt: UtcInstant, val beforeIds: List<DomainId>, val after: List<FoodAfterRefV1>, val result: LocalFoodResultV1)
internal data class FoodHandoffRowV2(val row: StoredAggregate, val envelope: FoodHandoffEnvelopeV2, val producer: RetainedCommandEvidence)

internal fun foodHandoffBytesV2(value: FoodHandoffEnvelopeV2): StoredBytes =
    localSalesEncode(value, FoodHandoffEnvelopeV2.serializer(), 4_096)
internal fun foodHandoffEnvelopeV2(bytes: StoredBytes): FoodHandoffEnvelopeV2 =
    localSalesDecode(bytes, FoodHandoffEnvelopeV2.serializer(), 4_096).also { value ->
        val offer = value.offer
        check(value.revision.value >= 1 && offer.fromActorId != offer.recipientId &&
            (offer.state == FoodHandoffStateV2.OFFERED) == (offer.resolvedAt == null && offer.resolvedBy == null))
        if (offer.state != FoodHandoffStateV2.OFFERED) check(offer.resolvedAt != null && offer.resolvedBy != null && offer.resolvedAt >= offer.offeredAt &&
            offer.resolvedBy == if (offer.state == FoodHandoffStateV2.ACCEPTED) offer.recipientId else offer.fromActorId)
        check(!offer.legacyOffer || offer.state != FoodHandoffStateV2.OFFERED)
        check(!offer.legacyOffer || offer.financial == null)
        check(value.revision == Revision(if (offer.state == FoodHandoffStateV2.OFFERED || offer.legacyOffer) 1 else 2))
    }
internal fun foodHandoffBeforeIdV2(operationId: OperationId, key: AggregateKey): DomainId =
    foodId("handoff-before-v2", "${operationId.value}/${key.kind}/${key.id.value}")
internal fun FoodHandoffBeforeV2.key(): AggregateKey = food?.let { AggregateKey(it.kind, it.id) }
    ?: AggregateKey(LocalFoodHandoffProfileV2.OFFER, checkNotNull(offer).id)
internal fun FoodHandoffBeforeV2.bytes(): StoredBytes = food?.let(::foodEnvelopeBytes) ?: foodHandoffBytesV2(checkNotNull(offer))
internal fun FoodHandoffBeforeV2.revision(): Revision = food?.revision ?: checkNotNull(offer).revision
internal fun FoodHandoffBeforeV2.operationId(): OperationId = food?.operationId ?: checkNotNull(offer).operationId
internal fun FoodHandoffBeforeV2.schema(): String = if (food != null) LocalFoodProfileV1.schema(food.kind)
    else LocalFoodHandoffProfileV2.schema(LocalFoodHandoffProfileV2.OFFER)

internal fun isFoodHandoffProducerV2(evidence: RetainedCommandEvidence): Boolean =
    evidence.aggregateWrites.any { it.key.kind == LocalFoodHandoffProfileV2.EVENT }
internal fun foodHandoffEventV2(evidence: RetainedCommandEvidence): FoodHandoffEventV2 {
    val row = evidence.aggregateWrites.single { it.key == AggregateKey(LocalFoodHandoffProfileV2.EVENT, DomainId(evidence.operationId.value)) }
    check(row.storageGeneration == Revision(1) && row.schema == LocalFoodHandoffProfileV2.schema(row.key.kind))
    return localSalesDecode(row.payload, FoodHandoffEventV2.serializer(), 16_384).also {
        check(it.version == 2 && it.beforeIds.size in 1..4 && it.beforeIds.distinct().size == it.beforeIds.size &&
            it.after.size in 2..4 && it.after.map { row -> row.kind to row.id }.distinct().size == it.after.size)
    }
}
internal fun foodHandoffBeforesV2(evidence: RetainedCommandEvidence): List<FoodHandoffBeforeV2> =
    foodHandoffEventV2(evidence).beforeIds.map { id ->
        val row = evidence.aggregateWrites.single { it.key == AggregateKey(LocalFoodHandoffProfileV2.BEFORE, id) }
        check(row.storageGeneration == Revision(1) && row.schema == LocalFoodHandoffProfileV2.schema(row.key.kind))
        localSalesDecode(row.payload, FoodHandoffBeforeV2.serializer(), 65_536).also { before ->
            check(id == foodHandoffBeforeIdV2(evidence.operationId, before.key()))
            before.food?.let { requireFoodEnvelope(it); check(it.kind in setOf(LocalFoodProfileV1.ORDER, LocalFoodProfileV1.OWNER_INDEX)) }
            before.offer?.let { foodHandoffEnvelopeV2(foodHandoffBytesV2(it)) }
        }
    }
internal fun foodHandoffInputsV2(evidence: RetainedCommandEvidence): List<FoodFinancialInputV1> = foodHandoffBeforesV2(evidence).map {
    val key = it.key(); val bytes = it.bytes()
    FoodFinancialInputV1(key.kind, key.id, it.revision(), it.schema(), sha256(bytes.copyBytes()), it.operationId(),
        bytes.copyBytes().decodeToString(throwOnInvalidSequence = true))
}
internal suspend fun foodHandoffRowV2(view: CommandReadView, id: DomainId): FoodHandoffRowV2? {
    val bounded = foodReadView(view)
    val key = AggregateKey(LocalFoodHandoffProfileV2.OFFER, id)
    val row = bounded.aggregate(key)
    val claim = bounded.claim(LocalFoodProfileV1.claim(key.kind, id))
    if (row == null) { check(claim == null) { "food.handoff.claim_without_record" }; return null }
    check(claim == id && row.schema == LocalFoodHandoffProfileV2.schema(key.kind) && row.digest == sha256(row.payload.copyBytes()))
    val value = foodHandoffEnvelopeV2(row.payload)
    check(value.scope == view.scope && value.id == id && value.revision == row.revision)
    val producer = bounded.foodProducer(value.operationId)
    check(isFoodHandoffProducerV2(producer) || isPaidFoodHandoffProducer(producer))
    val original = producer.aggregateWrites.single { it.key == key }
    check(original.storageGeneration == row.revision && original.schema == row.schema && original.payload == row.payload && original.payloadDigest == row.digest)
    bounded.requireFoodPredecessors(producer)
    check(bounded.claim(LocalFoodProfileV1.claim("handoff", id)) == value.offer.orderId)
    val incoming = bounded.claim(LocalFoodHandoffProfileV2.incoming(value.offer.recipientId, id))
    check(if (value.offer.state == FoodHandoffStateV2.OFFERED) incoming == value.offer.orderId else incoming == null)
    val accepted = bounded.claim(LocalFoodProfileV1.claim("accepted-handoff", id))
    check(if (value.offer.state == FoodHandoffStateV2.ACCEPTED) accepted == value.offer.orderId else accepted == null)
    return FoodHandoffRowV2(row, value, producer)
}

/** A live index is only a locator. The whole current offer AND actual current order must agree. */
internal fun requirePendingHandoffV2(order: FoodOrderCut, row: FoodHandoffRowV2) {
    val pending = checkNotNull(order.value.handoff)
    check(row.envelope.id == pending.id && row.envelope.offer == FoodHandoffOfferV2(order.order.envelope.id,
        order.value.ownerId, pending.to, pending.offeredAt, FoodHandoffStateV2.OFFERED, false,
        financial = row.envelope.offer.financial) && pending.from == order.value.ownerId)
    check(row.envelope.offer.financial?.collectionId == order.value.financialCollectionId)
}

/** Literal shape identifies an obligation, not proof of historical absence. */
internal fun foodHandoffLegacyOriginInputV2(evidence: RetainedCommandEvidence): FoodEnvelopeV1? {
    if (isPaidFoodHandoffProducer(evidence)) {
        val request = LocalFoodCodecV1.decode(evidence.normalized)
        if (request is LocalFoodRequestV1.OfferHandoff) return null
        check(request is LocalFoodRequestV1.AcceptHandoff || request is LocalFoodRequestV1.CancelHandoff)
        val offerKey = AggregateKey(LocalFoodHandoffProfileV2.OFFER, request.foodHandoffId())
        val offer = foodHandoffEnvelopeV2(evidence.aggregateWrites.single { it.key == offerKey }.payload)
        if (!offer.offer.legacyOffer) return null
        val inputs = paidFoodHandoffInputs(evidence)
        check(inputs.none { it.key() == offerKey } && request.foodHandoffFinancialV1() == null)
        val before = inputs.single { it.kind == LocalFoodProfileV1.ORDER && it.id == request.foodOrderId() }
        return foodEnvelope(before.bytes()).also { check((it.body as FoodBodyV1.Order).financialCollectionId == null) }
    }
    if (!isFoodHandoffProducerV2(evidence)) return null
    val request = LocalFoodCodecV1.decode(evidence.normalized)
    if (request is LocalFoodRequestV1.OfferHandoff) return null
    check(request is LocalFoodRequestV1.AcceptHandoff || request is LocalFoodRequestV1.CancelHandoff)
    val key = AggregateKey(LocalFoodHandoffProfileV2.OFFER, request.foodHandoffId())
    val offer = foodHandoffEnvelopeV2(evidence.aggregateWrites.single { it.key == key }.payload)
    if (!offer.offer.legacyOffer) return null
    val inputs = foodHandoffBeforesV2(evidence)
    check(inputs.none { it.offer != null })
    return checkNotNull(inputs.single {
        it.food?.let { food -> food.kind == LocalFoodProfileV1.ORDER && food.id == request.foodOrderId() } == true
    }.food)
}

/** Resolving rows have no current pending offer: start from their genuine literal BEFORE. */
internal suspend fun foodRequireLegacyResolutionOriginV2(view: FoodReadView,
    resolving: RetainedCommandEvidence, before: FoodEnvelopeV1) {
    check(before == foodHandoffLegacyOriginInputV2(resolving))
    foodRequireLegacyOriginV2(view, before, resolving)
}

internal suspend fun foodRequireLegacyPendingV2(view: FoodReadView, order: FoodOrderCut) =
    foodRequireLegacyOriginV2(view, order.order.envelope, null)

/**
 * Neither a missing current record nor legacyOffer=true proves v1 origin. Crosswalk every
 * actual retained order frame to its original v1 Offer, permitting only Station/DiscardStarted
 * preservation of the SAME pending identity. Current and resolved/predecessor callers share
 * this walk and the original finite view. Missing originals, cycles and revision disagreement
 * fail closed; exhaustion is capacity, not an empty inbox or a synthesized legacy original.
 */
private suspend fun foodRequireLegacyOriginV2(view: FoodReadView, initial: FoodEnvelopeV1,
    successor: RetainedCommandEvidence?) {
    check(initial.scope == view.scope && initial.kind == LocalFoodProfileV1.ORDER)
    val pending = checkNotNull((initial.body as FoodBodyV1.Order).handoff)
    val key = AggregateKey(initial.kind, initial.id)
    var value = initial
    var later = successor
    val visited = mutableSetOf<OperationId>()
    while (true) {
        check(visited.add(value.operationId)) { "food.handoff.legacy_origin_cycle" }
        val producer = view.foodProducer(value.operationId)
        check(producer.command == LocalFoodProfileV1.COMMAND)
        check(!isFoodHandoffProducerV2(producer) && !isPaidFoodHandoffProducer(producer)) { "food.handoff.v2_record_missing" }
        later?.let { next -> check(producer.operationId != next.operationId &&
            (producer.assignmentId != next.assignmentId || producer.authorityEpoch != next.authorityEpoch || producer.sequence < next.sequence)) }
        val original = producer.aggregateWrites.single { it.key == key }
        val bytes = foodEnvelopeBytes(value)
        check(value.scope == view.scope && value.kind == key.kind && value.id == key.id &&
            original.storageGeneration == value.revision && original.schema == LocalFoodProfileV1.schema(key.kind) &&
            original.payload == bytes && original.payloadDigest == sha256(bytes.copyBytes()))
        check((value.body as FoodBodyV1.Order).let { it.handoff == pending && it.ownerId == pending.from })
        val request = LocalFoodCodecV1.decode(producer.normalized)
        check(request is LocalFoodRequestV1.OfferHandoff || request is LocalFoodRequestV1.Station || request is LocalFoodRequestV1.DiscardStarted)
        view.requireFoodPredecessors(producer)
        if (request is LocalFoodRequestV1.OfferHandoff) {
            check(request.orderId == key.id && request.handoffId == pending.id && request.recipientId == pending.to &&
                producer.originalActorId == pending.from && producer.recordedAt == pending.offeredAt)
            return
        }
        val before = foodEvent(producer).beforeIds.map { foodBefore(producer, it).envelope }.single { it.kind == key.kind && it.id == key.id }
        check(before.scope == view.scope && before.revision.next() == value.revision &&
            (before.body as FoodBodyV1.Order).handoff == pending)
        value = before
        later = producer
    }
}
