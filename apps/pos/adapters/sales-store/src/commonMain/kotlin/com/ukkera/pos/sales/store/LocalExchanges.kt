package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.AcceptedReturnChanged
import com.ukkera.pos.sales.CashSessionStatus
import com.ukkera.pos.sales.CollectionChanged
import com.ukkera.pos.sales.ExchangeOffsetRecorded
import com.ukkera.pos.sales.ExchangeOffsetSlice
import com.ukkera.pos.sales.Exchanges
import com.ukkera.pos.sales.PaymentRecord
import com.ukkera.pos.sales.RefundRecord
import com.ukkera.pos.sales.SalesAggregate
import com.ukkera.pos.sales.SalesSnapshotLinks
import com.ukkera.pos.sales.SalesUniqueCheck
import com.ukkera.pos.sales.SalesUniqueKind
import com.ukkera.pos.sales.SalesVersionCheck
import com.ukkera.pos.sales.Tender
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Exchange (RET-05/06/07) as one additive Master command. An accepted return's refund obligation
 * is applied as a restricted offset that COMPLETES the replacement's normalized collection, in the
 * same commit, with no cash movement. The refund/collection journals, their bytes and their
 * history stay unchanged; an unavailable or not-yet-covered replacement leaves the return intact.
 */
@Serializable
public data class LocalExchangeSliceV1(
    public val attemptId: DomainId,
    public val originalPaymentId: DomainId,
    public val amount: Money,
) { init { require(amount.minor > 0L) } }

@Serializable
public data class LocalExchangeRequestV1(
    public val exchangeId: DomainId,
    public val saleId: DomainId,
    public val claimId: DomainId,
    public val returnId: DomainId,
    public val expectedReturnRevision: Revision,
    public val collectionId: DomainId,
    public val expectedCollectionRevision: Revision,
    public val slices: List<LocalExchangeSliceV1>,
    public val evidence: LocalCashEvidenceV1,
) { init { require(slices.size in 1..16) } }

@Serializable
public enum class LocalExchangeRefusalV1 { NOT_AVAILABLE, DUPLICATE }

@Serializable
public sealed interface LocalExchangeResultV1 {
    @Serializable @SerialName("APPLIED")
    public data class Applied(
        public val exchangeId: DomainId,
        public val returnId: DomainId,
        public val returnRevision: Revision,
        public val returnDue: Money,
        public val collectionId: DomainId,
        public val collectionRevision: Revision,
        public val amount: Money,
    ) : LocalExchangeResultV1
    @Serializable @SerialName("REFUSED")
    public data class Refused(public val reason: LocalExchangeRefusalV1) : LocalExchangeResultV1
}

public object LocalExchangeCodec {
    public val COMMAND: CommandKey = CommandKey("sales.exchange.write", 1)
    public const val KIND: String = "sales.exchange"
    public fun encode(value: LocalExchangeRequestV1): StoredBytes = localSalesEncode(value, LocalExchangeRequestV1.serializer(), 8_192)
    public fun decode(value: StoredBytes): LocalExchangeRequestV1 = localSalesDecode(value, LocalExchangeRequestV1.serializer(), 8_192)
    public fun normalize(value: StoredBytes): StoredBytes = encode(decode(value))
    public fun result(value: StoredBytes): LocalExchangeResultV1 = localSalesDecode(value, LocalExchangeResultV1.serializer(), 4_096)
    internal fun result(value: LocalExchangeResultV1): StoredBytes = localSalesEncode(value, LocalExchangeResultV1.serializer(), 4_096)
    internal fun key(id: DomainId): AggregateKey = LocalSalesStoreProfile.key(KIND, id)
    internal fun claim(id: DomainId): ClaimKey = LocalSalesStoreProfile.unique("exchange", id)
}

/** Immutable exchange record. `accepted` is the exact BEFORE image the return's predecessor chain needs. */
@Serializable
internal data class LocalExchangeRecordV1(
    val scope: LocalSalesScope,
    val exchangeId: DomainId,
    val operationId: OperationId,
    val returnId: DomainId,
    val saleId: DomainId,
    val claimId: DomainId,
    val collectionId: DomainId,
    val actorId: DomainId,
    val amount: Money,
    val slices: List<LocalExchangeSliceV1>,
    val evidence: LocalCashActorEvidenceV1,
    val recordedAt: UtcInstant,
    val accepted: LocalRetailBeforeV3,
)

internal suspend fun readExchangeRecord(view: CommandReadView, id: DomainId): LocalSalesRow<LocalExchangeRecordV1> {
    val row = LocalSalesStorage.required(view, LocalExchangeCodec.key(id))
    requireLocalSalesSource(row.revision == Revision(1) && row.schema == LocalSalesStoreProfile.schema(LocalExchangeCodec.KIND))
    val value = decodeLocalSalesSource(row.payload, LocalExchangeRecordV1.serializer())
    requireLocalSalesSource(value.scope.value() == view.scope && value.exchangeId == id &&
        view.claim(LocalExchangeCodec.claim(id)) == value.returnId)
    return LocalSalesRow(row, value)
}

/** Static shape of a retained exchange original; the caller still qualifies every observed source. */
internal fun requireExchangeOriginal(original: RetainedCommandEvidence): LocalExchangeRecordV1 {
    check(original.committed && original.command == LocalExchangeCodec.COMMAND && original.jobs.isEmpty() &&
        original.nativeOriginalProvenance == null && LocalExchangeCodec.normalize(original.normalized) == original.normalized &&
        semanticDigest(original.scope, original.originalActorId, original.command, original.normalized) == original.semanticDigest)
    val request = LocalExchangeCodec.decode(original.normalized)
    val result = LocalExchangeCodec.result(original.result) as? LocalExchangeResultV1.Applied ?: error("sales.exchange.original_result")
    val write = original.aggregateWrites.singleOrNull { it.key == LocalExchangeCodec.key(request.exchangeId) }
        ?: error("sales.exchange.original_record")
    check(write.storageGeneration == Revision(1) && write.schema == LocalSalesStoreProfile.schema(LocalExchangeCodec.KIND))
    val record = localSalesDecode(write.payload, LocalExchangeRecordV1.serializer())
    check(record.scope.value() == original.scope && record.operationId == original.operationId && record.actorId == original.originalActorId &&
        record.exchangeId == request.exchangeId && record.returnId == request.returnId && record.saleId == request.saleId &&
        record.claimId == request.claimId && record.collectionId == request.collectionId && record.slices == request.slices &&
        record.evidence == LocalCashActorEvidenceV1(request.evidence.evidenceId, original.originalActorId, request.evidence.occurredAt) &&
        result.exchangeId == request.exchangeId && result.collectionId == request.collectionId && result.returnId == request.returnId &&
        result.amount == record.amount && record.accepted.role == "accepted" && record.accepted.capturingOperationId == original.operationId &&
        record.accepted.sourceKind == LocalSalesStoreProfile.RETURN_KIND && record.accepted.sourceId == request.returnId)
    val collection = LocalSalesStoreProfile.key(LocalSalesStoreProfile.COLLECTION_KIND, request.collectionId)
    check(original.aggregateWrites.any { it.key == collection } && original.aggregateWrites.any { it.key == commerceLinkKey(collection) } &&
        original.aggregateWrites.any { it.key == LocalSalesStoreProfile.key(LocalSalesStoreProfile.RETURN_KIND, request.returnId) } &&
        original.aggregateWrites.any { it.key.kind == StockReadEvidenceV1.KIND } &&
        original.claimChanges.any { it.key == LocalExchangeCodec.claim(request.exchangeId) })
    return record
}

/**
 * Master-enforced: the kernel checks the CURRENT owner-private REFUND_RECORD grant for the
 * replacement collector, bound to the accepted return's revision and the offset amount; the domain
 * rechecks collector, scope, coverage and remaining obligation. Client state never grants it.
 */
public class LocalExchangeHandler(
    private val stock: LocalStockRepository,
    private val tenderPolicies: HistoricalSalesTenderPolicySourceV1 = UnavailableHistoricalSalesTenderPolicySourceV1,
) : MasterCommandHandler {
    override public val key: CommandKey get() = LocalExchangeCodec.COMMAND
    override public val writableKinds: Set<String> = setOf(LocalSalesStoreProfile.RETURN_KIND, LocalSalesStoreProfile.COLLECTION_KIND,
        LocalSalesStoreProfile.WORK_KIND, LocalExchangeCodec.KIND, StockReadEvidenceV1.KIND, COMMERCE_STOCK_LINK_KIND_V2)
    override public val claimNamespaces: Set<String> = setOf(LocalSalesStoreProfile.UNIQUE_NAMESPACE)
    override public fun normalize(input: StoredBytes): StoredBytes = LocalExchangeCodec.normalize(input)

    override public suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        check(view.scope == context.scope && context.key == key &&
            semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest) { "sales.context.invalid" }
        return prepareExchange(LocalExchangeCodec.decode(normalized), commerceLoan(view, stock, tenderPolicies), context)
    }
}

private fun exchangeRefused(reason: LocalExchangeRefusalV1): ProposedOutcome =
    ProposedOutcome.Reject("sales.exchange.refused", LocalExchangeCodec.result(LocalExchangeResultV1.Refused(reason)))

private fun exchangeUnavailable(context: CommandContext, request: LocalExchangeRequestV1): PreparedMutation =
    LocalSalesPrepared(context, SecurityAction.REFUND_RECORD, request.returnId, request.expectedReturnRevision,
        context.originalActorId, "exchange.unavailable", resources = setOf(entitlementResource(request.returnId),
            orderResource(request.collectionId))) { _, _ -> exchangeRefused(LocalExchangeRefusalV1.NOT_AVAILABLE) }

private suspend fun prepareExchange(request: LocalExchangeRequestV1, view: CommandReadView, context: CommandContext): PreparedMutation {
    val returnKey = LocalSalesStoreProfile.key(LocalSalesStoreProfile.RETURN_KIND, request.returnId)
    val collectionKey = LocalSalesStoreProfile.key(LocalSalesStoreProfile.COLLECTION_KIND, request.collectionId)
    if (view.aggregate(returnKey) == null || view.aggregate(collectionKey) == null) return exchangeUnavailable(context, request)
    // The return's whole predecessor chain is proven before the collection's read capture starts.
    val returned = try { readAcceptedReturn(view, request.saleId, request.claimId, request.returnId) }
        catch (_: LocalSalesSourceUnavailable) { return exchangeUnavailable(context, request) }
    if (!normalizedCollection(LocalSalesStorage.required(view, collectionKey), view.scope).stockRequirements.normalized)
        return exchangeUnavailable(context, request)
    val ids = request.slices.map { it.attemptId }
    var duplicate = view.claim(LocalExchangeCodec.claim(request.exchangeId)) != null ||
        view.aggregate(LocalExchangeCodec.key(request.exchangeId)) != null
    for (id in ids) if (financialSourceAllocated(view, id) ||
        view.claim(LocalSalesStoreProfile.unique("payment_record", id)) != null ||
        view.claim(LocalSalesStoreProfile.unique("refund_record", id)) != null) duplicate = true

    val capture = view.requireStockRelational().beginReadEvidence(context)
    val cut: LocalCollectionCut
    val oldLink: StoredAggregate
    val readEvidence: List<AggregateChange>
    try {
        cut = readCollection(view, request.collectionId)
        val collection = cut.collection.value
        val order = cut.order.order.value
        val session = cut.cash.session.value
        if (session.status != CashSessionStatus.OPEN || session.custodianId != context.originalActorId ||
            collection.collectorId != context.originalActorId || order.ownerId != collection.collectorId ||
            collection.saleId != null || order.paidSaleId != null || order.handoff != null ||
            order.collectionId != collection.id || order.revision != collection.lockedOrderRevision) {
            capture.discard(); return exchangeUnavailable(context, request)
        }
        oldLink = LocalSalesStorage.required(view, commerceLinkKey(cut.collection.stored.key), commerceLinkSchema())
        val sources = NormalizedPaymentSources(capture.observations())
        sources.collection(collection, order, session)
        sources.finish(stock = false)
        readEvidence = capture.finish()
    } catch (unavailable: LocalSalesSourceUnavailable) {
        capture.discard(); return exchangeUnavailable(context, request)
    } catch (failure: Throwable) { capture.discard(); throw failure }

    val accepted = returned.accepted.value
    val collection = cut.collection.value
    val amount = request.slices.fold(Money(collection.quote.payable.currency, 0L)) { sum, slice ->
        if (slice.amount.currency != sum.currency || slice.amount.minor > Long.MAX_VALUE - sum.minor)
            return exchangeUnavailable(context, request)
        Money(sum.currency, sum.minor + slice.amount.minor)
    }
    val resources = setOf(entitlementResource(accepted.id), entitlementResource(returned.original.claim.value.id),
        entitlementResource(returned.original.original.entitlement.value.saleId), orderResource(returned.work.value.id),
        entitlementResource(request.exchangeId)) + ids.map(::entitlementResource) + collectionResources(cut) +
        returned.original.caseRecoveryResources
    return LocalSalesPrepared(context, SecurityAction.REFUND_RECORD, accepted.id, request.expectedReturnRevision,
        collection.collectorId, "exchange.offset.apply", amount, resources,
        setOf(RecoveryResourceKind.CASH_CUSTODY, RecoveryResourceKind.ORDER_CUSTODY, RecoveryResourceKind.REFUND_ENTITLEMENT)) { command, now ->
        if (duplicate) exchangeRefused(LocalExchangeRefusalV1.DUPLICATE) else applyExchange(command, now, context, request,
            returned, cut, oldLink, readEvidence, ids, amount)
    }
}

private fun applyExchange(command: com.ukkera.pos.sales.SalesCommand, now: UtcInstant, context: CommandContext,
    request: LocalExchangeRequestV1, returned: LocalReturnCut, cut: LocalCollectionCut, oldLink: StoredAggregate,
    readEvidence: List<AggregateChange>, ids: List<DomainId>, amount: Money): ProposedOutcome {
        val accepted = returned.accepted.value
        val collection = cut.collection.value
        val transition = Exchanges.applyOffset(command, accepted, request.expectedReturnRevision, collection,
            request.expectedCollectionRevision, request.exchangeId,
            request.slices.map { ExchangeOffsetSlice(it.attemptId, it.originalPaymentId, it.amount) },
            request.evidence.domain(context.originalActorId))
        val after = transition.value
        val records = transition.effects.filter { it is RefundRecord || it is PaymentRecord }
        check(transition.command == command && transition.inventoryDependencies.isEmpty() &&
            transition.versions == listOf(SalesVersionCheck(SalesAggregate.ACCEPTED_RETURN, accepted.id, accepted.revision),
                SalesVersionCheck(SalesAggregate.COLLECTION, collection.id, collection.revision)) &&
            transition.unique.first() == SalesUniqueCheck(SalesUniqueKind.EXCHANGE, request.exchangeId) &&
            transition.effects == listOf(AcceptedReturnChanged(after.acceptedReturn), CollectionChanged(after.collection)) +
                records + after.record && records.size == 2 * ids.size && after.record.amount == amount &&
            after.acceptedReturn.attempts.filter { it.id in ids }.all { it.tender is Tender.ExchangeOffset }) { "sales.exchange.transition" }
        SalesSnapshotLinks.requireAcceptedReturn(after.acceptedReturn, returned.original.original.entitlement.value, returned.original.claim.value)
        SalesSnapshotLinks.requireCollection(after.collection, cut.order.order.value, cut.cash.session.value)
        return exchangeOutcome(context, request, returned, cut, oldLink, after.acceptedReturn, after.collection, after.record, readEvidence, now)
}

private fun exchangeOutcome(context: CommandContext, request: LocalExchangeRequestV1, returned: LocalReturnCut,
    cut: LocalCollectionCut, oldLink: StoredAggregate, acceptedAfter: com.ukkera.pos.sales.AcceptedReturn,
    collectionAfter: com.ukkera.pos.sales.CollectionLock, record: ExchangeOffsetRecorded,
    readEvidence: List<AggregateChange>, now: UtcInstant): ProposedOutcome.Apply {
    val scope = LocalSalesScope.from(context.scope)
    val document = collectionWrite(collectionAfter, cut.collection.stored)
    val changes = mutableListOf(
        acceptedReturnWrite(acceptedAfter, returned.original.original.entitlement.value, returned.original.claim.value, returned.accepted.stored),
        document, normalizedPaymentLink(context, document, cut.collection.stored, oldLink),
        rootWrite(LocalExchangeCodec.KIND, request.exchangeId, LocalExchangeRecordV1(scope, request.exchangeId, context.operationId,
            request.returnId, request.saleId, request.claimId, request.collectionId, context.originalActorId, record.amount,
            request.slices, LocalCashActorEvidenceV1(request.evidence.evidenceId, context.originalActorId, request.evidence.occurredAt),
            now, retailBeforeImageV3(context.operationId, scope, "accepted", returned.accepted.stored)),
            LocalExchangeRecordV1.serializer(), null))
    if (!acceptedAfter.unresolved && acceptedAfter.due.minor == 0L && request.returnId in returned.work.value.returnIds) {
        changes += changedWork(returned.work, returnIds = returned.work.value.returnIds - request.returnId).second
    }
    changes += readEvidence
    val claims = listOf(ClaimChange.Acquire(LocalExchangeCodec.claim(request.exchangeId), request.returnId)) +
        request.slices.sortedBy { it.attemptId.value }.flatMap { slice -> listOf(
            ClaimChange.Acquire(LocalSalesStoreProfile.unique("refund_attempt", slice.attemptId), request.returnId),
            ClaimChange.Acquire(LocalSalesStoreProfile.unique("refund_record", slice.attemptId), request.returnId),
            ClaimChange.Acquire(LocalSalesStoreProfile.unique("payment_attempt", slice.attemptId), request.collectionId),
            ClaimChange.Acquire(LocalSalesStoreProfile.unique("payment_record", slice.attemptId), request.collectionId)) }
    return ProposedOutcome.Apply(LocalExchangeCodec.result(LocalExchangeResultV1.Applied(request.exchangeId, request.returnId,
        acceptedAfter.revision, acceptedAfter.due, request.collectionId, collectionAfter.revision, record.amount)), changes, claims)
}
