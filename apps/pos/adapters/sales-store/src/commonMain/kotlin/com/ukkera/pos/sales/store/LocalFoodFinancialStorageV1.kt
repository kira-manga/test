package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Transient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable internal sealed interface FoodFinancialBodyV1 {
    @Serializable @SerialName("COLLECTION") data class Collection(val snapshot: String,
        @Transient val sources: List<String> = emptyList()) : FoodFinancialBodyV1 {
        constructor(value: FoodFinancialEncodedFrameV2) : this(value.document, value.sources)
    }
    @Serializable @SerialName("PAID_FOOD") data class Sale(val snapshot: String,
        @Transient val sources: List<String> = emptyList()) : FoodFinancialBodyV1 {
        constructor(value: FoodFinancialEncodedFrameV2) : this(value.document, value.sources)
    }
    @Serializable @SerialName("FOOD_ENTITLEMENT") data class Entitlement(val snapshot: String,
        @Transient val sources: List<String> = emptyList()) : FoodFinancialBodyV1 {
        constructor(value: FoodFinancialEncodedFrameV2) : this(value.document, value.sources)
    }
    @Serializable @SerialName("UNSENT_REFUND") data class Refund(val snapshot: String,
        @Transient val sources: List<String> = emptyList()) : FoodFinancialBodyV1 {
        constructor(value: FoodFinancialEncodedFrameV2) : this(value.document, value.sources)
    }
    @Serializable @SerialName("REFUND_INDEX") data class RefundIndex(val orderId: DomainId, val refundIds: List<DomainId>) : FoodFinancialBodyV1
    @Serializable @SerialName("QUALITY_CLAIM") data class Claim(val facts: LocalFoodClaimFactsV1,
        val recordedBy: DomainId, val recordedAt: UtcInstant, val responsibleOwnerId: DomainId,
        /** Source linkage is NOT entitlement, eligibility, receipt proof or a payout decision. */
        val linkedOrderId: DomainId?) : FoodFinancialBodyV1
    @Serializable @SerialName("CLAIM_INDEX") data class ClaimIndex(val target: FoodClaimIndexTargetV1,
        val targetId: DomainId, val claimIds: List<DomainId>) : FoodFinancialBodyV1
}
@Serializable internal enum class FoodClaimIndexTargetV1 { OWNER, ORDER }
@Serializable internal data class FoodFinancialEnvelopeV1(val scope: BranchScope, val kind: String, val id: DomainId,
    val revision: Revision, val operationId: OperationId, val body: FoodFinancialBodyV1,
    @Transient val framed: Boolean = false, @Transient val originalBatches: List<FoodPriceBatch> = emptyList())
@Serializable internal data class FoodFinancialStockLiteralV1(val scope: BranchScope, val domainRevision: Revision,
    val snapshot: String, val producerCommand: String, val producerVersion: Int, val producerActorId: DomainId,
    val producerSemanticDigest: PayloadDigest)

/** Exact observed bytes, not a recipe/stock/cash grant. No input comes directly from an RPC. */
@Serializable internal data class FoodFinancialInputV1(val kind: String, val id: DomainId, val revision: Revision,
    val schema: String, val digest: PayloadDigest, val operationId: OperationId, val payload: String,
    val stock: FoodFinancialStockLiteralV1? = null,
    @Transient val financial: FoodFinancialEnvelopeV1? = null) {
    fun key(): AggregateKey = AggregateKey(kind, id)
    fun bytes(): StoredBytes = StoredBytes(payload.encodeToByteArray(throwOnInvalidSequence = true))
}
@Serializable internal data class FoodFinancialCashInputV1(val sessionId: DomainId, val storageRevision: Revision, val snapshot: String)
@OptIn(ExperimentalSerializationApi::class)
@Serializable internal data class FoodFinancialEventV1(val scope: BranchScope, val operationId: OperationId,
    val actorId: DomainId, val recordedAt: UtcInstant, val inputIds: List<DomainId>, val after: List<FoodAfterRefV1>,
    val stockProduced: Boolean, val cash: FoodFinancialCashInputV1?, val result: LocalFoodFinancialResultV1,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val framing: FoodFinancialManifestV2? = null)

internal class FoodFinancialRow private constructor(val row: StoredAggregate, val envelope: FoodFinancialEnvelopeV1,
    private val legacyProducer: RetainedCommandEvidence?, val normalizedProducer: RetainedStreamedCommand?) {
    constructor(row: StoredAggregate, envelope: FoodFinancialEnvelopeV1, producer: RetainedCommandEvidence) : this(row, envelope, producer, null)
    constructor(row: StoredAggregate, envelope: FoodFinancialEnvelopeV1, producer: RetainedStreamedCommand) : this(row, envelope, null, producer)
    val producer: RetainedCommandEvidence get() = legacyProducer ?: throw SalesRuleViolation(SalesRule.UNSUPPORTED_STOCK_TIMING)
}
internal data class FoodFinancialObservation(val input: FoodFinancialInputV1, val producer: RetainedCommandEvidence)
internal data class FoodFinancialCollectionCut(val row: FoodFinancialRow, val value: FoodCollection,
    val order: FoodOrderCut, val portions: List<FoodRow>, val batches: List<FoodRow>)

internal val foodFinancialDocumentKinds: Set<String> = LocalFoodFinancialProfileV1.kinds - setOf(
    LocalFoodFinancialProfileV1.EVENT, LocalFoodFinancialProfileV1.BEFORE, LocalFoodFinancialProfileV1.FRAME, LocalFoodFinancialProfileV1.PHYSICAL_SLOT,
    LocalFoodFinancialProfileV1.PHYSICAL_FACT, LocalFoodFinancialProfileV1.PHYSICAL_APPLICATION)
internal fun foodFinancialEnvelope(bytes: StoredBytes): FoodFinancialEnvelopeV1 =
    localSalesDecode(bytes, FoodFinancialEnvelopeV1.serializer(), 65_536).also(::requireFoodFinancialEnvelope)

internal fun requireFoodFinancialEnvelope(value: FoodFinancialEnvelopeV1) {
    check(value.kind in foodFinancialDocumentKinds && value.revision.value >= 1)
    when (val body = value.body) {
        is FoodFinancialBodyV1.Collection -> {
            check(value.kind == LocalFoodFinancialProfileV1.COLLECTION)
            foodFinancialCollection(value)
        }
        is FoodFinancialBodyV1.Sale -> {
            check(value.kind == LocalFoodFinancialProfileV1.SALE && value.revision == Revision(1))
            foodFinancialSale(value)
        }
        is FoodFinancialBodyV1.Entitlement -> {
            check(value.kind == LocalFoodFinancialProfileV1.ENTITLEMENT)
            foodFinancialEntitlement(value)
        }
        is FoodFinancialBodyV1.Refund -> {
            check(value.kind == LocalFoodFinancialProfileV1.REFUND)
            foodFinancialRefund(value)
        }
        is FoodFinancialBodyV1.RefundIndex -> check(value.kind == LocalFoodFinancialProfileV1.REFUND_INDEX &&
            value.id == body.orderId && body.refundIds.size <= 32 && body.refundIds.distinct().size == body.refundIds.size)
        is FoodFinancialBodyV1.Claim -> {
            check(value.kind == LocalFoodFinancialProfileV1.CLAIM && value.revision == Revision(1))
            LocalFoodFinancialCodecV1.encode(LocalFoodFinancialRequestV1.RecordQualityClaim(value.id, body.facts))
            check(body.recordedAt >= body.facts.claimedAt && body.facts.evidence.occurredAt <= body.recordedAt)
        }
        is FoodFinancialBodyV1.ClaimIndex -> check(value.kind == LocalFoodFinancialProfileV1.CLAIM_INDEX &&
            value.id == foodClaimIndexId(body.target, body.targetId) && body.claimIds.size <= 64 &&
            body.claimIds.distinct().size == body.claimIds.size)
    }
}

internal fun foodFinancialCollection(value: FoodFinancialEnvelopeV1): FoodCollection {
    val body = value.body as FoodFinancialBodyV1.Collection
    return if (value.framed) FoodFinancialFramesV2.decodeCollection(body.snapshot, value.scope, value.id,
        Revision(value.revision.value - 1), value.originalBatches, body.sources)
    else FoodFinancialSnapshots.decodeCollection(body.snapshot, value.scope, value.id, Revision(value.revision.value - 1))
}
internal fun foodFinancialSale(value: FoodFinancialEnvelopeV1): FoodPaidSale {
    val body = value.body as FoodFinancialBodyV1.Sale
    return if (value.framed) FoodFinancialFramesV2.decodeSale(body.snapshot, value.scope, value.id, value.originalBatches, body.sources)
    else FoodFinancialSnapshots.decodeSale(body.snapshot, value.scope, value.id)
}
internal fun foodFinancialEntitlement(value: FoodFinancialEnvelopeV1): FoodReceiptEntitlement {
    val body = value.body as FoodFinancialBodyV1.Entitlement
    return if (value.framed) FoodFinancialFramesV2.decodeEntitlement(body.snapshot, value.scope, value.id,
        Revision(value.revision.value - 1), value.originalBatches, body.sources)
    else FoodFinancialSnapshots.decodeEntitlement(body.snapshot, value.scope, value.id, Revision(value.revision.value - 1))
}
internal fun foodFinancialRefund(value: FoodFinancialEnvelopeV1): FoodRefundObligation {
    val body = value.body as FoodFinancialBodyV1.Refund
    return if (value.framed) FoodFinancialFramesV2.decodeRefund(body.snapshot, value.scope, value.id,
        Revision(value.revision.value - 1), value.originalBatches, body.sources)
    else FoodFinancialSnapshots.decodeRefund(body.snapshot, value.scope, value.id, Revision(value.revision.value - 1))
}
internal fun foodClaimIndexId(target: FoodClaimIndexTargetV1, id: DomainId): DomainId = foodId("financial-claim-index", "${target.name}/${id.value}")
internal fun foodFinancialInputId(operation: OperationId, key: AggregateKey): DomainId = foodId("financial-input", "${operation.value}/${key.kind}/${key.id.value}")

/** Header observation selects exact private authorization only. Success still needs foodFinancialRead. */
internal suspend fun foodFinancialObserve(view: CommandReadView, kind: String, id: DomainId): Pair<StoredAggregate, FoodFinancialEnvelopeV1>? {
    check(kind in foodFinancialDocumentKinds)
    val row = view.aggregate(AggregateKey(kind, id))
    val claim = view.claim(LocalFoodFinancialProfileV1.source(kind, id))
    if (row == null) { check(claim == null) { "food.financial.claim_without_document" }; return null }
    check(claim == id && row.key == AggregateKey(kind, id) &&
        row.schema in setOf(LocalFoodFinancialProfileV1.schema(kind), foodFinancialSchemaV2(kind), foodStockSchema(kind)) &&
        row.digest == sha256(row.payload.copyBytes()))
    val value = if (row.schema == foodStockSchema(kind)) {
        foodStockObserveFinancial(view, row)
    } else if (row.schema == foodFinancialSchemaV2(kind)) {
        val root = foodFinancialRootV2(row.payload)
        val producer = view.retainedCommand(root.operationId) ?: error("food.financial.producer_missing")
        check(producer.committed && producer.scope == view.scope && producer.operationId == root.operationId &&
            (producer.command == LocalFoodFinancialProfileV1.COMMAND ||
                producer.command == LocalFoodProfileV1.COMMAND && isPaidFoodHandoffProducer(producer) ||
                kind == LocalFoodFinancialProfileV1.COLLECTION && isFoodPaymentFinancialProducer(producer) ||
                kind == LocalFoodFinancialProfileV1.REFUND && isFoodRefundFinancialProducer(producer)) &&
            producer.aggregateWrites.single { it.key == row.key }.let {
                it.storageGeneration == row.revision && it.schema == row.schema && it.payload == row.payload && it.payloadDigest == row.digest
            })
        if (isFoodPaymentFinancialProducer(producer)) {
            FoodFinancialBundleReaderV2(producer, foodPaymentStoredEvent(producer).framing, allowStockInputs = true).document(row.payload)
        } else if (isFoodRefundFinancialProducer(producer)) {
            FoodFinancialBundleReaderV2(producer, foodRefundStoredEvent(producer).framing, allowStockInputs = true).document(row.payload)
        } else if (isPaidFoodHandoffProducer(producer)) {
            FoodFinancialBundleReaderV2(producer, paidFoodHandoffEvent(producer).framing, allowStockInputs = true).document(row.payload)
        } else {
            val event = foodFinancialStoredEvent(producer)
            FoodFinancialBundleReaderV2(producer, checkNotNull(event.framing)).document(row.payload)
        }
    } else foodFinancialEnvelope(row.payload)
    check(value.scope == view.scope && value.kind == kind && value.id == id && value.revision == row.revision)
    return row to value
}

internal suspend fun foodFinancialRead(view: CommandReadView, kind: String, id: DomainId): FoodFinancialRow? {
    val bounded = foodReadView(view)
    val (row, value) = foodFinancialObserve(bounded, kind, id) ?: return null
    if (row.schema == foodStockSchema(kind)) {
        val original = bounded.foodStockProducer(value.operationId)
        requireCommerceWrite(original, row)
        bounded.recordCommerceOriginal(row, original)
        return FoodFinancialRow(row, value, original)
    }
    val producer = bounded.foodProducer(value.operationId)
    check((producer.command == LocalFoodFinancialProfileV1.COMMAND ||
        producer.command == LocalFoodProfileV1.COMMAND && isPaidFoodHandoffProducer(producer) ||
        kind == LocalFoodFinancialProfileV1.COLLECTION && row.schema == foodFinancialSchemaV2(kind) && isFoodPaymentFinancialProducer(producer) ||
        kind == LocalFoodFinancialProfileV1.REFUND && row.schema == foodFinancialSchemaV2(kind) && isFoodRefundFinancialProducer(producer)) &&
        producer.aggregateWrites.single { it.key == row.key }.let {
        it.storageGeneration == row.revision && it.payload == row.payload && it.schema == row.schema && it.payloadDigest == row.digest
    })
    bounded.requireFoodPredecessors(producer)
    commerceRecordLegacy(bounded, row, producer)
    return FoodFinancialRow(row, value, producer)
}

internal fun foodObserved(row: FoodRow): FoodFinancialObservation = FoodFinancialObservation(
    foodFinancialInput(row.row, row.envelope.operationId), row.producer)
internal fun foodObserved(row: FoodFinancialRow): FoodFinancialObservation = FoodFinancialObservation(
    foodFinancialInput(row.row, row.envelope.operationId).copy(financial = row.envelope), row.producer)
private fun foodFinancialInput(row: StoredAggregate, operationId: OperationId,
    stock: FoodFinancialStockLiteralV1? = null): FoodFinancialInputV1 = FoodFinancialInputV1(row.key.kind, row.key.id,
    row.revision, row.schema, row.digest, operationId, row.payload.copyBytes().decodeToString(throwOnInvalidSequence = true), stock)

/** Consumes the repository's complete current cut, then binds copied scalars to actual retained bytes. */
internal suspend fun foodObservedStock(view: CommandReadView, stamp: LocalStockObservedSource,
    domainRevision: Revision, encoded: String): FoodFinancialObservation {
    check(stamp.scope == view.scope && stamp.key.kind in setOf(LocalStockStoreProfile.RESERVATION, LocalStockStoreProfile.ISSUE))
    val row = view.aggregate(stamp.key) ?: error("food.financial.stock_source_missing")
    check(row.revision == stamp.storageRevision && row.digest == stamp.payloadDigest && sha256(row.payload.copyBytes()) == row.digest)
    val producer = view.retainedCommand(stamp.producerOperationId) ?: error("food.financial.stock_producer_missing")
    check(producer.committed && producer.scope == view.scope && producer.operationId == stamp.producerOperationId &&
        producer.command == stamp.producerCommand && producer.originalActorId == stamp.producerActorId &&
        producer.semanticDigest == stamp.producerSemanticDigest && producer.aggregateWrites.single { it.key == stamp.key }.let {
            it.storageGeneration == row.revision && it.schema == row.schema && it.payloadDigest == row.digest && it.payload == row.payload
        })
    val input = foodFinancialInput(row, producer.operationId, FoodFinancialStockLiteralV1(view.scope, domainRevision,
        encoded, producer.command.name, producer.command.version, producer.originalActorId, producer.semanticDigest))
    requireFoodStockInput(input, producer)
    return FoodFinancialObservation(input, producer)
}

/** Literal posting crosswalk only. Current authority/safety still comes from LocalStockRepository.observe. */
internal fun requireFoodStockInput(input: FoodFinancialInputV1, producer: RetainedCommandEvidence) {
    val literal = checkNotNull(input.stock)
    check(producer.committed && literal.scope == producer.scope && input.operationId == producer.operationId &&
        producer.command == CommandKey(literal.producerCommand, literal.producerVersion) &&
        producer.originalActorId == literal.producerActorId && producer.semanticDigest == literal.producerSemanticDigest &&
        semanticDigest(producer.scope, producer.originalActorId, producer.command, producer.normalized) == producer.semanticDigest)
    val records = checkNotNull(LocalStockRepository().inspectPosting(producer))
    val scope = InventoryScope(producer.scope.companyId, producer.scope.branchId)
    when (input.kind) {
        LocalStockStoreProfile.RESERVATION -> check(InventoryRecordCodec.encodeReservation(
            records.reservations.last { it.reservationId == input.id }, scope) == literal.snapshot &&
            records.reservations.last { it.reservationId == input.id }.revision == literal.domainRevision)
        LocalStockStoreProfile.ISSUE -> check(InventoryRecordCodec.encodeIssue(
            records.issues.last { it.issueId == input.id }, scope) == literal.snapshot &&
            records.issues.last { it.issueId == input.id }.revision == literal.domainRevision)
        else -> error("food.financial.stock_input_kind")
    }
}

/** Decode the exact stored version; only explicit v2 framing is interpreted as a bundle. */
internal fun foodFinancialStoredEvent(evidence: RetainedCommandEvidence): FoodFinancialEventV1 {
    val key = AggregateKey(LocalFoodFinancialProfileV1.EVENT, DomainId(evidence.operationId.value))
    val row = evidence.aggregateWrites.single { it.key == key }
    check(row.storageGeneration == Revision(1) && row.schema in setOf(LocalFoodFinancialProfileV1.schema(key.kind), foodFinancialSchemaV2(key.kind)))
    return localSalesDecode(row.payload, FoodFinancialEventV1.serializer(), 65_536).also {
        check(if (row.schema == foodFinancialSchemaV2(key.kind)) it.framing?.version == 2 && it.cash == null else it.framing == null)
    }
}
internal fun foodFinancialInputs(evidence: RetainedCommandEvidence,
    frames: FoodFinancialBundleReaderV2? = null): List<FoodFinancialInputV1> {
    val event = foodFinancialStoredEvent(evidence)
    val inputs = if (event.framing != null) (frames ?: FoodFinancialBundleReaderV2(evidence, event.framing)).inputs()
    else event.inputIds.map { id ->
        val key = AggregateKey(LocalFoodFinancialProfileV1.BEFORE, id)
        val row = evidence.aggregateWrites.single { it.key == key }
        check(row.storageGeneration == Revision(1) && row.schema == LocalFoodFinancialProfileV1.schema(key.kind))
        localSalesDecode(row.payload, FoodFinancialInputV1.serializer(), 65_536)
    }
    check(event.inputIds == inputs.map { foodFinancialInputId(evidence.operationId, it.key()) })
    for (input in inputs) {
        check(input.revision.value >= 1 && input.operationId != evidence.operationId && sha256(input.bytes().copyBytes()) == input.digest)
        when (input.kind) {
            in LocalFoodProfileV1.kinds - setOf(LocalFoodProfileV1.BEFORE, LocalFoodProfileV1.EVENT) -> {
                val source = foodEnvelope(input.bytes())
                check(input.stock == null && input.financial == null && source.scope == evidence.scope && source.kind == input.kind && source.id == input.id &&
                    source.revision == input.revision && source.operationId == input.operationId && input.schema == LocalFoodProfileV1.schema(input.kind))
            }
            in foodFinancialDocumentKinds -> {
                val source = input.financial ?: foodFinancialEnvelope(input.bytes())
                check(input.stock == null && source.scope == evidence.scope && source.kind == input.kind && source.id == input.id &&
                    source.revision == input.revision && source.operationId == input.operationId && input.schema ==
                    if (source.framed) foodFinancialSchemaV2(input.kind) else LocalFoodFinancialProfileV1.schema(input.kind))
            }
            LocalStockStoreProfile.RESERVATION, LocalStockStoreProfile.ISSUE -> check(input.stock?.scope == evidence.scope &&
                input.financial == null && input.schema == LocalStockStoreProfile.schema(input.kind))
            else -> error("food.financial.unrecognized_source")
        }
    }
    return inputs
}

internal class FoodFinancialWrites(private val context: CommandContext, private val at: UtcInstant,
    observed: List<FoodFinancialObservation>, private val cash: FoodFinancialCashInputV1? = null) {
    private val inputs = observed.distinctBy { it.input.key() }.associateBy { it.input.key() }
    private val changes = linkedMapOf<AggregateKey, AggregateChange>()
    private val frames = FoodFinancialBundleWriterV2(context.operationId)
    private val originalBatches = foodFinancialPriceBasesV2(inputs.values.map { it.input }, context.scope)
    init {
        check(observed.groupBy { it.input.key() }.values.all { group -> group.map { it.input.copy(financial = null) }.distinct().size == 1 })
        foodCapacity(inputs.size <= FOOD_FINANCIAL_MAX_INPUTS_V2)
    }
    fun putFood(kind: String, id: DomainId, body: FoodBodyV1): FoodEnvelopeV1 {
        val key = AggregateKey(kind, id); val prior = inputs[key]?.input
        val next = FoodEnvelopeV1(context.scope, kind, id, prior?.revision?.next() ?: Revision(1), context.operationId, body)
        requireFoodEnvelope(next)
        put(key, LocalFoodProfileV1.schema(kind), foodEnvelopeBytes(next)); return next
    }
    fun put(kind: String, id: DomainId, body: FoodFinancialBodyV1): FoodFinancialEnvelopeV1 {
        val key = AggregateKey(kind, id); val prior = inputs[key]?.input
        val next = FoodFinancialEnvelopeV1(context.scope, kind, id, prior?.revision?.next() ?: Revision(1), context.operationId, body,
            framed = true, originalBatches = originalBatches)
        requireFoodFinancialEnvelope(next)
        put(key, foodFinancialSchemaV2(kind), frames.document(next)); return next
    }
    private fun put(key: AggregateKey, schema: String, payload: StoredBytes) {
        check(key !in changes)
        changes[key] = AggregateChange(key, inputs[key]?.input?.revision, schema, payload)
    }
    fun apply(code: String, documentId: DomainId, amount: Money? = null, jobs: List<JobIntent> = emptyList(),
        stock: LocalStockRepository? = null, contribution: LocalStockContribution? = null,
        extraClaims: List<ClaimChange> = emptyList()): ProposedOutcome.Apply {
        check((stock == null) == (contribution == null) && changes.isNotEmpty())
        foodAdmitFinancialInputProducers(inputs.values.map { it.producer })
        val refs = changes.values.map { FoodAfterRefV1(it.key.kind, it.key.id,
            it.expectedRevision?.next() ?: Revision(1), sha256(it.payload.copyBytes())) }
        val result = LocalFoodFinancialResultV1(code, documentId, refs.map {
            LocalFoodStorageRevisionV1(it.kind, it.id, it.revision)
        }, amount)
        val encoded = localSalesEncode(result, LocalFoodFinancialResultV1.serializer(), 16_384)
        val (manifest, frameWrites) = frames.finish(inputs.values.map { it.input }, cash)
        val event = FoodFinancialEventV1(context.scope, context.operationId, context.originalActorId, at,
            inputs.values.map { foodFinancialInputId(context.operationId, it.input.key()) }, refs,
            contribution != null, null, result, manifest)
        val all = changes.values.toList() + frameWrites + AggregateChange(
            AggregateKey(LocalFoodFinancialProfileV1.EVENT, DomainId(context.operationId.value)), null,
            foodFinancialSchemaV2(LocalFoodFinancialProfileV1.EVENT), localSalesEncode(event, FoodFinancialEventV1.serializer(), 65_536))
        val claims = changes.keys.filter { it !in inputs }.map { key -> ClaimChange.Acquire(
            if (key.kind in LocalFoodProfileV1.kinds) LocalFoodProfileV1.claim(key.kind, key.id)
            else LocalFoodFinancialProfileV1.source(key.kind, key.id), key.id) } + extraClaims
        foodAdmitProposal(encoded, all, claims, jobs)
        val proposal = if (contribution == null) ProposedOutcome.Apply(encoded, all, claims, jobs)
            else checkNotNull(stock).compose(context, contribution, encoded, all, claims, jobs)
        foodAdmitProposal(proposal.result, proposal.changes, proposal.claims, proposal.jobs)
        return proposal
    }
}

internal fun foodFinancialCashInput(cash: LocalCashCut): FoodFinancialCashInputV1 = FoodFinancialCashInputV1(
    cash.session.value.id, cash.session.stored.revision, SalesSnapshotCodec.encodeCashSession(cash.session.value))
internal fun foodFinancialReject(code: String): ProposedOutcome.Reject = ProposedOutcome.Reject(code,
    localSalesEncode(LocalFoodFinancialResultV1(code), LocalFoodFinancialResultV1.serializer(), 16_384))
internal fun foodFinancialResources(cut: FoodFinancialCollectionCut): Set<RecoveryResourceKey> =
    foodResources(cut.order, cut.portions) + orderResource(cut.value.id) + cashResource(cut.value.cashSessionId) +
        cut.value.portions.flatMap { listOf(RecoveryResourceKey(RecoveryResourceKind.STOCK, it.reservationId)) +
            listOfNotNull(it.priorIssueId?.let { id -> RecoveryResourceKey(RecoveryResourceKind.STOCK, id) }) } +
        listOfNotNull(cut.value.saleId?.let(::entitlementResource), cut.value.cancellationRefundId?.let(::entitlementResource))

internal suspend fun foodFinancialCollectionCut(view: CommandReadView, id: DomainId,
    recipes: TransactionFoodRecipeSourceV1, includeClosed: Boolean = false): FoodFinancialCollectionCut {
    val row = foodFinancialRead(view, LocalFoodFinancialProfileV1.COLLECTION, id) ?: throw SalesRuleViolation(SalesRule.INVALID_STATE)
    val value = foodFinancialCollection(row.envelope)
    val order = foodOrder(view, value.orderId, includeClosed)
    check(order.value.financialCollectionId == id && order.value.ownerId == value.currentOwnerId &&
        order.value.creatorId == value.originalCreatorId && order.value.handoff == null)
    val portions = order.value.portionIds.map { foodPortion(view, order, it, recipes) }
    val batches = order.value.batchIds.map { foodRead(view, LocalFoodProfileV1.PRICE_BATCH, it) ?: error("food.financial.price_missing") }
    requireFoodFinancialQuote(value, order.value, portions.map { it.envelope }, batches.map { it.envelope })
    return FoodFinancialCollectionCut(row, value, order, portions, batches)
}

internal fun foodFinancialQuote(orderId: DomainId, batches: List<FoodEnvelopeV1>): FoodFinancialQuote = FoodFinancialQuote(batches.map {
    val body = it.body as FoodBodyV1.PriceBatch
    check(body.orderId == orderId)
    FoodPriceBatch(it.id, sha256(foodEnvelopeBytes(it).copyBytes()), StoredPricingSnapshotCodec.decode(body.quote))
})
internal fun requireFoodFinancialQuote(collection: FoodCollection, order: FoodBodyV1.Order,
    portions: List<FoodEnvelopeV1>, batches: List<FoodEnvelopeV1>) {
    check(batches.map { it.id } == order.batchIds && portions.map { it.id } == order.portionIds &&
        collection.quote.portionIds == order.portionIds && collection.quote.payable == order.originalQuotedTotal &&
        FoodFinancialSnapshots.sameQuote(collection.quote, foodFinancialQuote(collection.orderId, batches)))
    for (entry in collection.portions) {
        val portion = portions.single { it.id == entry.portionId }.body as FoodBodyV1.Portion
        check(portion.orderId == collection.orderId && portion.priceBatchId == entry.batchId &&
            portion.recipe.snapshotId == entry.recipeSnapshotId && portion.selection.quantity == entry.quantity &&
            entry.reservationId == foodReservationId(entry.portionId))
        if (entry.stockOrigin == FoodStockOrigin.ISSUED_BEFORE_COLLECTION) check(
            entry.priorIssueId == portion.preparation.issueId && portion.financialCollectionId == null)
        else check(portion.financialCollectionId == null && portion.preparation.issueId == null ||
            portion.financialCollectionId == collection.id && portion.preparation.reservationId == entry.reservationId)
    }
}
