package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.Serializable

/** Closed responsibility profile for both collection-bound and normalized/unbound food.
 * The original command/result identity is unchanged; no payment is implied by this event. */
internal object LocalPaidFoodHandoffProfileV1 {
    const val EVENT: String = "sales.food.paid-handoff-event"
    val kinds: Set<String> = setOf(EVENT, LocalFoodFinancialProfileV1.COLLECTION, LocalFoodFinancialProfileV1.FRAME)
    fun schema(): String = "$EVENT/v1"
}

@Serializable internal data class PaidFoodHandoffEventV1(val version: Int, val scope: BranchScope,
    val operationId: OperationId, val actorId: DomainId, val recordedAt: UtcInstant,
    val inputIds: List<DomainId>, val after: List<FoodAfterRefV1>, val framing: FoodFinancialManifestV2,
    val result: LocalFoodResultV1)

/** All sources are read on one real writer/read loan. A quote or a status header alone is insufficient. */
internal class PaidFoodHandoffCut(val collection: FoodFinancialRow, val value: FoodCollection,
    val portions: List<FoodRow>, val batches: List<FoodRow>, val refund: FoodFinancialRow?,
    val claimIndex: FoodFinancialRow?) {
    val unresolved: Boolean get() = value.unresolved || refund?.let { foodFinancialRefund(it.envelope).unresolved } == true
    fun projection(): LocalFoodHandoffFinancialV1 = LocalFoodHandoffFinancialV1(
        LocalFoodHandoffFinancialExpectationV1(value.id, value.revision), value.status, value.collected, value.remaining,
        refund?.let { foodFinancialRefund(it.envelope).due },
        (claimIndex?.envelope?.body as? FoodFinancialBodyV1.ClaimIndex)?.claimIds?.size ?: 0, unresolved)
}

internal suspend fun foodPaidHandoffCut(view: FoodReadView, order: FoodOrderCut): PaidFoodHandoffCut {
    val id = checkNotNull(order.value.financialCollectionId)
    val row = foodFinancialRead(view, LocalFoodFinancialProfileV1.COLLECTION, id)
        ?: error("food.handoff.collection_missing")
    val value = foodFinancialCollection(row.envelope)
    check(value.scope == view.scope && value.orderId == order.order.envelope.id &&
        value.currentOwnerId == order.value.ownerId && value.originalCreatorId == order.value.creatorId)
    val portions = order.value.portionIds.map { foodPortionSummary(view, order, it) }
    val batches = order.value.batchIds.map { foodRead(view, LocalFoodProfileV1.PRICE_BATCH, it)
        ?: error("food.handoff.price_missing") }
    requireFoodFinancialQuote(value, order.value, portions.map { it.envelope }, batches.map { it.envelope })
    val refund = value.cancellationRefundId?.let { foodFinancialRead(view, LocalFoodFinancialProfileV1.REFUND, it)
        ?: error("food.handoff.refund_missing") }
    refund?.let { requireFoodRefundSource(value, foodFinancialRefund(it.envelope)) }
    val claims = foodFinancialRead(view, LocalFoodFinancialProfileV1.CLAIM_INDEX,
        foodClaimIndexId(FoodClaimIndexTargetV1.ORDER, value.orderId))
    claims?.let { check((it.envelope.body as FoodFinancialBodyV1.ClaimIndex).let { body ->
        body.target == FoodClaimIndexTargetV1.ORDER && body.targetId == value.orderId }) }
    return PaidFoodHandoffCut(row, value, portions, batches, refund, claims)
}

/** Collects exact literals, never flattens a streamed stock original into a v1 producer. */
private class PaidFoodHandoffInputs {
    private val values = linkedMapOf<AggregateKey, FoodFinancialInputV1>()
    private val legacy = linkedMapOf<OperationId, RetainedCommandEvidence>()
    private val originalIds = mutableSetOf<OperationId>()
    fun add(row: FoodRow) = add(row.row, row.envelope.operationId, null,
        if (row.normalizedProducer == null) row.producer else null)
    fun add(row: FoodFinancialRow) = add(row.row, row.envelope.operationId, row.envelope,
        if (row.normalizedProducer == null) row.producer else null)
    fun add(row: FoodHandoffRowV2) = add(row.row, row.envelope.operationId, null, row.producer)
    private fun add(row: StoredAggregate, operation: OperationId, financial: FoodFinancialEnvelopeV1?, original: RetainedCommandEvidence?) {
        val value = FoodFinancialInputV1(row.key.kind, row.key.id, row.revision, row.schema, row.digest, operation,
            row.payload.copyBytes().decodeToString(throwOnInvalidSequence = true), financial = financial)
        val old = values.put(row.key, value)
        check(old == null || old.copy(financial = null) == value.copy(financial = null))
        originalIds += operation
        if (original != null) legacy[operation] = original
    }
    fun finish(): List<FoodFinancialInputV1> {
        foodCapacity(values.size <= FOOD_FINANCIAL_MAX_INPUTS_V2 && originalIds.size <= FOOD_FINANCIAL_MAX_INPUTS_V2)
        foodAdmitFinancialInputProducers(legacy.values.toList())
        return values.values.toList()
    }
}

internal suspend fun foodPreparePaidHandoff(request: LocalFoodRequestV1, order: FoodOrderCut,
    view: FoodReadView, context: CommandContext): PreparedMutation {
    val expectation = request.foodHandoffFinancialV1()
    val accepting = request is LocalFoodRequestV1.AcceptHandoff
    val offering = request is LocalFoodRequestV1.OfferHandoff
    val handoffId = request.foodHandoffId()
    val pending = order.value.handoff
    val recipient = if (request is LocalFoodRequestV1.OfferHandoff) request.recipientId else pending?.to
    val owner = if (accepting) context.originalActorId else order.value.ownerId
    fun reject(code: String): PreparedMutation = FoodPrepared(context, SecurityAction.ORDER_HANDOFF,
        request.foodOrderId(), request.foodOrderRevision(), owner, foodResources(order)) { foodReject(code) }
    if (expectation?.collectionId != order.value.financialCollectionId)
        return reject("food.handoff.financial_expectation_mismatch")
    if (order.order.envelope.revision != request.foodOrderRevision()) return reject("food.rule.revision_conflict")
    if (recipient == null || (!offering && pending?.id != handoffId)) return reject("food.handoff.unavailable")
    val cut = if (order.value.financialCollectionId != null) foodPaidHandoffCut(view, order) else null
    if (cut != null && cut.value.revision != expectation?.expectedCollectionRevision) return reject("food.handoff.financial_expectation_mismatch")
    if (cut?.unresolved == true && (offering || accepting)) return reject("food.handoff.payment_unresolved")
    val portions = cut?.portions ?: order.value.portionIds.map { foodPortionSummary(view, order, it) }
    val batches = cut?.batches ?: order.value.batchIds.map { foodRead(view, LocalFoodProfileV1.PRICE_BATCH, it)
        ?: error("food.handoff.price_missing") }
    if (cut == null) requireUnboundHandoffInputs(request.foodOrderId(), order.value,
        portions.map { it.envelope }, batches.map { it.envelope })
    val offer = foodHandoffRowV2(view, handoffId)
    val identity = view.claim(LocalFoodProfileV1.claim("handoff", handoffId))
    if (offering) {
        if (pending != null || identity != null || offer != null) return reject("food.identity.used")
        check(view.claim(LocalFoodProfileV1.claim("accepted-handoff", handoffId)) == null &&
            view.claim(LocalFoodHandoffProfileV2.incoming(recipient, handoffId)) == null)
    } else {
        if (identity != request.foodOrderId()) return reject("food.handoff.unavailable")
        if (offer == null) {
            if (cut != null) return reject("food.handoff.unavailable")
            check(view.claim(LocalFoodHandoffProfileV2.incoming(recipient, handoffId)) == null &&
                view.claim(LocalFoodProfileV1.claim("accepted-handoff", handoffId)) == null)
            // Only a complete walk to the genuine original v1 offer can justify this absence.
            // Its resolution may still need normalized owner-index inputs from other work.
            foodRequireLegacyPendingV2(view, order)
        } else {
            requirePendingHandoffV2(order, offer)
            if (accepting && offer.envelope.offer.financial != expectation)
                return reject("food.handoff.financial_expectation_mismatch")
        }
    }
    val eligible = if (offering || accepting) TransactionLocalHandoffRecipients.current(
        view.handoffBorrowedView(), request.foodOrderId(), recipient) else null
    val admission = if (offering || accepting) foodWorkAdmission(view, recipient) else null
    val recipientIndex = if (offering || accepting) foodOpenOwnerIndex(view, recipient) else null
    if (offering || accepting) {
        if (recipient == order.value.ownerId || eligible == null || admission?.accepting != true) return reject("food.handoff.unavailable")
        if (foodOpenOrderIds(recipientIndex).size >= LocalFoodProfileV1.MAX_ACTIVE_INDEX ||
            (cut != null && cut.value.responsibility.size >= FoodCollections.MAX_RESPONSIBILITY_TRANSITIONS)) return reject("food.capacity.required")
    }
    val inputs = PaidFoodHandoffInputs().apply {
        add(order.order); add(order.ownerIndex); cut?.collection?.let(::add)
        portions.forEach(::add); batches.forEach(::add)
        cut?.refund?.let(::add); cut?.claimIndex?.let(::add); offer?.let(::add); recipientIndex?.let(::add)
    }.finish()
    val resources = foodResources(order, portions) + listOfNotNull(cut?.value?.id?.let(::orderResource)) + orderResource(handoffId) +
        orderResource(recipient) + RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, recipient) +
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, context.originalActorId) +
        admission?.resources.orEmpty() + cut?.value?.portions.orEmpty().flatMap { portion -> listOfNotNull(
            RecoveryResourceKey(RecoveryResourceKind.STOCK, portion.reservationId),
            portion.priorIssueId?.let { RecoveryResourceKey(RecoveryResourceKind.STOCK, it) }) } +
        listOfNotNull(cut?.value?.saleId?.let(::entitlementResource), cut?.value?.cancellationRefundId?.let(::entitlementResource))
    // Copy the immutable literals before releasing this read loan. Row wrappers retain streamed
    // original handles and must not be captured by the synchronous evaluation closure.
    val orderBody = order.value
    val orderStorageRevision = order.order.envelope.revision
    val collection = cut?.value
    val offerBody = offer?.envelope?.offer
    val ownerOrderIds = foodOpenOrderIds(order.ownerIndex).toList()
    val recipientOrderIds = foodOpenOrderIds(recipientIndex).toList()
    // No view, driver, streamed handle, current permission or drawer grant escapes into evaluate.
    return PaidFoodHandoffPrepared(context, request, owner, resources) { command, at ->
        foodRule(orderBody.closedByOperationId == null && orderStorageRevision == request.foodOrderRevision(), SalesRule.REVISION_CONFLICT)
        val writes = PaidFoodHandoffWrites(context, at, inputs)
        val claims = mutableListOf<ClaimChange>()
        val nextOrder: FoodBodyV1.Order
        val nextOffer: FoodHandoffOfferV2
        if (offering) {
            foodRule(context.originalActorId == orderBody.ownerId && pending == null, SalesRule.OWNER_REQUIRED)
            nextOrder = orderBody.copy(handoff = FoodHandoffV1(handoffId, owner, recipient, at))
            nextOffer = FoodHandoffOfferV2(request.foodOrderId(), owner, recipient, at, FoodHandoffStateV2.OFFERED, false,
                financial = expectation)
            claims += ClaimChange.Acquire(LocalFoodProfileV1.claim("handoff", handoffId), request.foodOrderId())
            claims += ClaimChange.Acquire(LocalFoodHandoffProfileV2.incoming(recipient, handoffId), request.foodOrderId())
        } else {
            val prior = checkNotNull(pending)
            foodRule(context.originalActorId == if (accepting) prior.to else prior.from, SalesRule.OWNER_REQUIRED)
            foodRule(prior.offeredAt <= at && prior.from == orderBody.ownerId, SalesRule.HANDOFF_CONFLICT)
            nextOrder = orderBody.copy(ownerId = if (accepting) recipient else orderBody.ownerId, handoff = null)
            nextOffer = if (offerBody != null) offerBody.copy(
                state = if (accepting) FoodHandoffStateV2.ACCEPTED else FoodHandoffStateV2.CANCELLED,
                resolvedAt = at, resolvedBy = context.originalActorId)
            else FoodHandoffOfferV2(request.foodOrderId(), prior.from, prior.to, prior.offeredAt,
                if (accepting) FoodHandoffStateV2.ACCEPTED else FoodHandoffStateV2.CANCELLED,
                legacyOffer = true, resolvedAt = at, resolvedBy = context.originalActorId)
            if (offerBody != null) claims += ClaimChange.Release(LocalFoodHandoffProfileV2.incoming(recipient, handoffId), request.foodOrderId())
            if (accepting) {
                if (collection != null) {
                    val next = FoodCollections.acceptSettledHandoff(command, collection, checkNotNull(expectation).expectedCollectionRevision,
                        request.foodOrderRevision(), handoffId, prior.from, recipient, prior.offeredAt)
                    writes.collection(next)
                }
                writes.food(LocalFoodProfileV1.OWNER_INDEX, orderBody.ownerId,
                    FoodBodyV1.OwnerIndex(ownerOrderIds - request.foodOrderId()))
                writes.food(LocalFoodProfileV1.OWNER_INDEX, recipient,
                    FoodBodyV1.OwnerIndex(recipientOrderIds + request.foodOrderId()))
                claims += ClaimChange.Acquire(LocalFoodProfileV1.claim("accepted-handoff", handoffId), request.foodOrderId())
            }
        }
        val after = writes.food(LocalFoodProfileV1.ORDER, request.foodOrderId(), nextOrder)
        writes.offer(handoffId, nextOffer)
        writes.apply(foodResult(foodResultCode(request), after), claims)
    }
}

private class PaidFoodHandoffPrepared(private val context: CommandContext, request: LocalFoodRequestV1,
    owner: DomainId, override val affectedResources: Set<RecoveryResourceKey>,
    private val proposal: (SalesCommand, UtcInstant) -> ProposedOutcome) : PreparedMutation() {
    override val requirement: AuthorizationRequirement = AuthorizationRequirement(
        Permission(SecurityAction.ORDER_HANDOFF, PermissionVerb.PERFORM), context.scope,
        ActionBinding(request.foodOrderId(), request.foodOrderRevision(), context.semanticDigest, context.operationId, "food.operation"),
        owner = OwnerConstraint.OwnerPrivate(owner))
    override val allowsActionApproval: Boolean = false
    override val recoveryHoldBarrierKinds: Set<RecoveryResourceKind> = setOf(RecoveryResourceKind.STOCK, RecoveryResourceKind.AUTHORIZATION)
    override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
        check(authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE && authorization.isBoundTo(requirement, context.originalActorId))
        return try { proposal(SalesCommand(context.operationId, authorization, recordedAt.value, context.semanticDigest, "food.operation"), recordedAt) }
        catch (_: FoodCapacityExceeded) { foodReject("food.capacity.required") }
        catch (_: LocalSalesWireCapacityExceeded) { foodReject("food.capacity.required") }
        catch (failure: SalesRuleViolation) { foodReject("food.rule.${failure.rule.name.lowercase()}") }
    }
}

/** Bounded literals only: no money, stock, kitchen jobs, new tender record or rewritten sale. */
private class PaidFoodHandoffWrites(private val context: CommandContext, private val at: UtcInstant,
    inputList: List<FoodFinancialInputV1>) {
    private val inputs = inputList.associateBy { it.key() }
    private val changes = linkedMapOf<AggregateKey, AggregateChange>()
    private val frames = FoodFinancialBundleWriterV2(context.operationId, allowStockInputs = true)
    fun food(kind: String, id: DomainId, body: FoodBodyV1): FoodEnvelopeV1 {
        check(kind == LocalFoodProfileV1.ORDER || kind == LocalFoodProfileV1.OWNER_INDEX)
        val key = AggregateKey(kind, id)
        val row = FoodEnvelopeV1(context.scope, kind, id, inputs[key]?.revision?.next() ?: Revision(1), context.operationId, body)
        requireFoodEnvelope(row); put(key, LocalFoodProfileV1.schema(kind), foodEnvelopeBytes(row)); return row
    }
    fun collection(value: FoodCollection) {
        val key = AggregateKey(LocalFoodFinancialProfileV1.COLLECTION, value.id)
        val prior = inputs.getValue(key)
        check(value.revision.value + 1 == prior.revision.next().value)
        val row = FoodFinancialEnvelopeV1(context.scope, key.kind, key.id, prior.revision.next(), context.operationId,
            FoodFinancialBodyV1.Collection(FoodFinancialFramesV2.encodeCollection(value)), true,
            foodFinancialPriceBasesV2(inputs.values.toList(), context.scope, allowStockInputs = true))
        requireFoodFinancialEnvelope(row); put(key, foodFinancialSchemaV2(key.kind), frames.document(row))
    }
    fun offer(id: DomainId, body: FoodHandoffOfferV2) {
        val key = AggregateKey(LocalFoodHandoffProfileV2.OFFER, id)
        val row = FoodHandoffEnvelopeV2(context.scope, id, inputs[key]?.revision?.next() ?: Revision(1), context.operationId, body)
        val bytes = foodHandoffBytesV2(row); foodHandoffEnvelopeV2(bytes)
        put(key, LocalFoodHandoffProfileV2.schema(key.kind), bytes)
    }
    private fun put(key: AggregateKey, schema: String, bytes: StoredBytes) {
        check(key !in changes); changes[key] = AggregateChange(key, inputs[key]?.revision, schema, bytes)
    }
    fun apply(result: LocalFoodResultV1, extraClaims: List<ClaimChange>): ProposedOutcome.Apply {
        val refs = changes.values.map { FoodAfterRefV1(it.key.kind, it.key.id,
            it.expectedRevision?.next() ?: Revision(1), sha256(it.payload.copyBytes())) }
        val (manifest, chunks) = frames.finish(inputs.values.toList(), null)
        val event = PaidFoodHandoffEventV1(1, context.scope, context.operationId, context.originalActorId, at,
            inputs.values.map { foodFinancialInputId(context.operationId, it.key()) }, refs, manifest, result)
        val writes = changes.values.toList() + chunks + AggregateChange(
            AggregateKey(LocalPaidFoodHandoffProfileV1.EVENT, DomainId(context.operationId.value)), null,
            LocalPaidFoodHandoffProfileV1.schema(), localSalesEncode(event, PaidFoodHandoffEventV1.serializer(), 65_536))
        val claims = changes.keys.filter { it !in inputs }.map {
            check(it.kind != LocalFoodFinancialProfileV1.COLLECTION)
            ClaimChange.Acquire(LocalFoodProfileV1.claim(it.kind, it.id), it.id)
        } + extraClaims
        val bytes = localSalesEncode(result, LocalFoodResultV1.serializer())
        foodAdmitProposal(bytes, writes, claims, emptyList())
        return ProposedOutcome.Apply(bytes, writes, claims)
    }
}

/** Exact unbound commercial literals. A paid/collection-bound order cannot enter this case. */
internal fun requireUnboundHandoffInputs(orderId: DomainId, order: FoodBodyV1.Order,
    portions: List<FoodEnvelopeV1>, batches: List<FoodEnvelopeV1>) {
    check(order.financialCollectionId == null && order.closedByOperationId == null &&
        portions.map { it.id } == order.portionIds && batches.map { it.id } == order.batchIds)
    val quote = foodFinancialQuote(orderId, batches)
    check(quote.portionIds == order.portionIds && quote.payable == order.originalQuotedTotal)
    for (row in portions) {
        val portion = row.body as FoodBodyV1.Portion
        val batch = batches.single { it.id == portion.priceBatchId }.body as FoodBodyV1.PriceBatch
        val price = quote.batches.single { it.id == portion.priceBatchId }.quote
        check(portion.orderId == orderId && portion.financialCollectionId == null &&
            portion.recipe.activity == order.activity && portion.recipe.channel == order.service.channel &&
            price.lines.single { it.portionId == row.id }.quantity == portion.selection.quantity &&
            batch.sources.single { it.portionId == row.id }.skuId == portion.recipe.pricingSkuId)
    }
}
