package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/**
 * Closed literal v2 effects under the unchanged v1 command/result contract. A legacy resolving
 * shape also requires FoodReadView's actual retained-origin proof, even when used as an input
 * to another producer. Missing before-offer is not independently evidence of historical v1.
 */
internal object LocalHistoricalFoodHandoffProducer {
    fun requireComplete(evidence: RetainedCommandEvidence): List<LocalFoodRecipeSnapshotV1> =
        HistoricalFoodHandoffProducerProof(evidence).validate()
}

private class HistoricalFoodHandoffProducerProof(private val evidence: RetainedCommandEvidence) {
    private val request = LocalFoodCodecV1.decode(evidence.normalized)
    private val event = foodHandoffEventV2(evidence)
    private val before = foodHandoffBeforesV2(evidence).associateBy { it.key() }
    private val after = evidence.aggregateWrites.filter { it.key.kind in setOf(
        LocalFoodProfileV1.ORDER, LocalFoodProfileV1.OWNER_INDEX, LocalFoodHandoffProfileV2.OFFER) }.associate { row ->
        row.key to if (row.key.kind == LocalFoodHandoffProfileV2.OFFER)
            FoodHandoffBeforeV2(offer = foodHandoffEnvelopeV2(row.payload))
        else FoodHandoffBeforeV2(food = foodEnvelope(row.payload))
    }
    private val used = mutableSetOf<AggregateKey>()
    private val extraClaims = mutableListOf<ClaimChange>()

    fun validate(): List<LocalFoodRecipeSnapshotV1> {
        check(request is LocalFoodRequestV1.OfferHandoff || request is LocalFoodRequestV1.AcceptHandoff || request is LocalFoodRequestV1.CancelHandoff)
        check(request.foodHandoffFinancialV1() == null)
        check(evidence.committed && evidence.command == LocalFoodProfileV1.COMMAND &&
            LocalFoodCodecV1.normalize(evidence.normalized) == evidence.normalized &&
            semanticDigest(evidence.scope, evidence.originalActorId, evidence.command, evidence.normalized) == evidence.semanticDigest)
        check(event.scope == evidence.scope && event.operationId == evidence.operationId &&
            event.actorId == evidence.originalActorId && event.recordedAt == evidence.recordedAt &&
            before.size == event.beforeIds.size && after.size == event.after.size)
        val expectedKeys = after.keys + event.beforeIds.map { AggregateKey(LocalFoodHandoffProfileV2.BEFORE, it) } +
            AggregateKey(LocalFoodHandoffProfileV2.EVENT, DomainId(evidence.operationId.value))
        check(evidence.aggregateWrites.map { it.key }.toSet() == expectedKeys &&
            evidence.aggregateWrites.map { it.key }.distinct().size == evidence.aggregateWrites.size &&
            evidence.aggregateWrites.all { it.payloadDigest == sha256(it.payload.copyBytes()) } && evidence.jobs.isEmpty())
        check(before.keys.all { it in after })
        for ((key, value) in after) {
            val row = evidence.aggregateWrites.single { it.key == key }
            val ref = event.after.single { it.kind == key.kind && it.id == key.id }
            check(value.key() == key && value.operationId() == evidence.operationId &&
                (value.food?.scope ?: checkNotNull(value.offer).scope) == evidence.scope &&
                value.bytes() == row.payload && value.schema() == row.schema && value.revision() == row.storageGeneration &&
                ref.revision == row.storageGeneration && ref.digest == row.payloadDigest)
            val old = before[key]
            if (old == null) check(value.revision() == Revision(1)) else {
                check((old.food?.scope ?: checkNotNull(old.offer).scope) == evidence.scope &&
                    old.operationId() != evidence.operationId && old.revision().next() == value.revision())
            }
        }
        val orderKey = AggregateKey(LocalFoodProfileV1.ORDER, request.foodOrderId())
        val oldRow = checkNotNull(before[orderKey]?.food)
        val old = oldRow.body as FoodBodyV1.Order
        val nextRow = use(orderKey).food ?: error("food.handoff.order_kind")
        val next = nextRow.body as FoodBodyV1.Order
        check(oldRow.revision == request.foodOrderRevision() && old.closedByOperationId == null && old.financialCollectionId == null)
        val handoffId = request.foodHandoffId()
        val offerKey = AggregateKey(LocalFoodHandoffProfileV2.OFFER, handoffId)
        val nextOffer = checkNotNull(use(offerKey).offer).offer
        val priorOffer = before[offerKey]?.offer?.offer
        when (val selected = request) {
            is LocalFoodRequestV1.OfferHandoff -> {
                check(old.ownerId == evidence.originalActorId && old.handoff == null && selected.recipientId != old.ownerId && priorOffer == null)
                check(next == old.copy(handoff = FoodHandoffV1(handoffId, old.ownerId, selected.recipientId, evidence.recordedAt)))
                check(nextOffer == FoodHandoffOfferV2(selected.orderId, old.ownerId, selected.recipientId,
                    evidence.recordedAt, FoodHandoffStateV2.OFFERED, false))
                extraClaims += ClaimChange.Acquire(LocalFoodProfileV1.claim("handoff", handoffId), selected.orderId)
                extraClaims += ClaimChange.Acquire(LocalFoodHandoffProfileV2.incoming(selected.recipientId, handoffId), selected.orderId)
            }
            is LocalFoodRequestV1.AcceptHandoff, is LocalFoodRequestV1.CancelHandoff -> {
                val pending = checkNotNull(old.handoff)
                val accepting = selected is LocalFoodRequestV1.AcceptHandoff
                check(pending.id == handoffId && pending.from == old.ownerId && pending.offeredAt <= evidence.recordedAt &&
                    evidence.originalActorId == if (accepting) pending.to else pending.from)
                val offered = FoodHandoffOfferV2(request.foodOrderId(), pending.from, pending.to, pending.offeredAt,
                    FoodHandoffStateV2.OFFERED, false)
                if (priorOffer != null) check(priorOffer == offered)
                check(nextOffer == offered.copy(state = if (accepting) FoodHandoffStateV2.ACCEPTED else FoodHandoffStateV2.CANCELLED,
                    legacyOffer = priorOffer == null, resolvedAt = evidence.recordedAt, resolvedBy = evidence.originalActorId))
                check(next == old.copy(ownerId = if (accepting) pending.to else old.ownerId, handoff = null))
                if (accepting) {
                    ownerIndex(old.ownerId, request.foodOrderId(), false)
                    ownerIndex(pending.to, request.foodOrderId(), true)
                    extraClaims += ClaimChange.Acquire(LocalFoodProfileV1.claim("accepted-handoff", handoffId), request.foodOrderId())
                }
                if (priorOffer != null) extraClaims += ClaimChange.Release(
                    LocalFoodHandoffProfileV2.incoming(pending.to, handoffId), request.foodOrderId())
            }
            else -> error("food.handoff.command_kind")
        }
        check(after.keys == used)
        val result = foodResult(foodResultCode(request), nextRow)
        check(event.result == result && evidence.result == localSalesEncode(result, LocalFoodResultV1.serializer()) &&
            evidence.resultHash == sha256(evidence.result.copyBytes()))
        val claims = after.keys.filter { it !in before }.map { ClaimChange.Acquire(LocalFoodProfileV1.claim(it.kind, it.id), it.id) } + extraClaims
        check(evidence.claimChanges.size == claims.size && evidence.claimChanges.map { it.key }.distinct().size == claims.size &&
            claims.all { expected -> evidence.claimChanges.any { actual -> actual.key == expected.key && actual.ownerId == expected.ownerId &&
                actual.action == if (expected is ClaimChange.Acquire) RetainedClaimAction.ACQUIRE else RetainedClaimAction.RELEASE } })
        return emptyList() // No recipe, stock, cash custody, financial write or delivery job belongs to this transition.
    }

    private fun use(key: AggregateKey): FoodHandoffBeforeV2 = after.getValue(key).also { check(used.add(key)) }
    private fun ownerIndex(ownerId: DomainId, orderId: DomainId, adding: Boolean) {
        val key = AggregateKey(LocalFoodProfileV1.OWNER_INDEX, ownerId)
        val prior = (before[key]?.food?.body as? FoodBodyV1.OwnerIndex)?.orderIds.orEmpty()
        check((orderId !in prior) == adding)
        val next = checkNotNull(use(key).food).body as FoodBodyV1.OwnerIndex
        check(next.orderIds == if (adding) prior + orderId else prior - orderId)
    }
}

internal fun LocalFoodRequestV1.foodHandoffId(): DomainId = when (this) {
    is LocalFoodRequestV1.OfferHandoff -> handoffId
    is LocalFoodRequestV1.AcceptHandoff -> handoffId
    is LocalFoodRequestV1.CancelHandoff -> handoffId
    else -> error("food.handoff.command_kind")
}
