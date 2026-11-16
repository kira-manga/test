package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** A distinct original-only replay of the installed FOOD read accounting. It has no
 * CommandReadView, current rows, writer, handler or employee/native capability. Every
 * read uses an actual closed native BEFORE owner; every cache hit is still charged.
 * Qualification failures are distinct from the replayed first capacity refusal. */
internal class FoodFinancialBeforeReadBudget private constructor(private val before: FoodFinancialBeforeOwner) {
    constructor(command: RuntimeHistoricalCommand) : this(FoodHistoryBeforeOwner(command))
    constructor(sources: RuntimeHistoricalFinancialSources, originalLoan: CommandReadView, stock: LocalStockRepository) :
        this(FoodRetainedBeforeOwner(sources, originalLoan, stock))
    val scope get() = before.scope
    val original get() = before.original
    suspend fun unbudgetedAggregate(key: AggregateKey): StoredAggregate? = before.aggregate(key)
    suspend fun <T> withOriginalProofs(block: suspend () -> T): T = before.withOriginalProofs(block)
    private var bytes = 0L
    private var calls = 0
    private var rows = 0
    private var producers = 0
    private val retained = mutableMapOf<OperationId, RetainedCommandEvidence?>()
    private val completeFoodProducers = mutableSetOf<OperationId>()
    private val checkingFoodProducers = mutableSetOf<OperationId>()
    private val crosswalkedFoodProducers = mutableSetOf<OperationId>()
    private val completeStockFoodProducers = mutableMapOf<OperationId, RetainedStreamedCommand>()
    private val queuedHandoffOrigins = mutableSetOf<OperationId>()
    private val pendingHandoffOrigins = ArrayDeque<Pair<RetainedCommandEvidence, FoodEnvelopeV1>>()
    private var checkingHandoffOrigins = false
    private var handoffOriginFailure: Throwable? = null
    private fun capacity(available: Boolean) { if (!available) throw FoodBeforeApplicationCapacity() }
    private fun call() { calls++; capacity(calls <= FoodReadLimits.REQUEST_CALLS) }
    private fun data(size: Long) { check(size >= 0); capacity(size <= FoodReadLimits.REQUEST_BYTES - bytes); bytes += size }
    suspend fun required(key: AggregateKey, schema: String): StoredAggregate {
        val value = aggregate(key) ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
        requireLocalSalesSource(value.key == key && value.schema == schema && value.revision.value > 0)
        return value
    }
    suspend fun requireRefundCustody(original: RetainedCommandEvidence) { refundWhole(original) }
    private var collecting = false
    private val observed = linkedMapOf<Pair<String, String>, StreamedSourceObservationV2>()
    private var observedBytes = 0
    fun beginFinancialEvidence() {
        check(!collecting && calls == 0 && rows == 0 && bytes == 0L && observed.isEmpty())
        collecting = true
    }
    private fun observe(source: StreamedSourceObservationV2) {
        if (!collecting) return
        val key = when (source) {
            is StreamedSourceObservationV2.Current -> "current.${source.key.kind}" to source.key.id.value
            is StreamedSourceObservationV2.CurrentAbsent -> "absent.${source.key.kind}" to source.key.id.value
            is StreamedSourceObservationV2.Claim -> "claim.${source.key.namespace}" to source.key.key
            else -> error("food.before.point_source_required")
        }
        // Use the existing pure literal proposal codec, never the core-internal root
        // reader or a caller-created live capture. This also checks the same single
        // member bound before the installed aggregate/claim accounting continues.
        val producer = StreamedProducerIdentityV2(original.operationId, original.command,
            original.originalActorId, original.semanticDigest)
        val single = StockReadEvidenceV1.proposedChanges(scope, producer, listOf(source))
        val encoded = checkNotNull(StockReadEvidenceV1.decodeChanges(scope, producer, single)).copySourceFrameBytes(source)
        try {
            observed[key]?.let { check(it == source); return }
            // Same installed StockReadCapture order: this bound runs DURING each actual
            // read, before a later FOOD byte charge or first-refusal condition.
            if (observed.size >= 4096 || encoded.size + 4 > StockReadEvidenceV1.MAX_BYTES - observedBytes)
                throw FoodBeforeApplicationCapacity()
            observed[key] = source; observedBytes += encoded.size + 4
        } finally { encoded.fill(0) }
    }
    fun evidence(producer: StreamedProducerIdentityV2): List<AggregateChange> =
        StockReadEvidenceV1.proposedChanges(scope, producer, observed.values.toList())
    fun observations(producer: StreamedProducerIdentityV2): StockReadObservations =
        checkNotNull(StockReadEvidenceV1.decodeChanges(scope, producer, evidence(producer)))
    suspend fun aggregate(key: AggregateKey): StoredAggregate? {
        call(); rows++; capacity(rows <= FoodReadLimits.REQUEST_ROWS)
        val value = before.aggregate(key)
        observe(if (value == null) StreamedSourceObservationV2.CurrentAbsent(key) else
            StreamedSourceObservationV2.Current(key, value.revision, value.schema, value.payload, value.digest))
        data(512L + (value?.payload?.size ?: 0))
        return value
    }
    suspend fun claim(key: ClaimKey): DomainId? {
        call(); data(256L + key.namespace.length + key.key.length)
        val value = before.claim(key)
        observe(StreamedSourceObservationV2.Claim(key, value))
        return value
    }
    suspend fun retainedCommand(id: OperationId): RetainedCommandEvidence? {
        call(); producers++; capacity(producers <= FoodReadLimits.REQUEST_PRODUCERS)
        val value = if (id in retained) retained[id] else before.legacy(id).also { retained[id] = it }
        data(value?.let(::foodProducerReadBytes) ?: 512L)
        return value
    }
    private suspend fun foodStockProducer(id: OperationId): RetainedStreamedCommand {
        call(); producers++; capacity(producers <= FoodReadLimits.REQUEST_PRODUCERS); data(512L)
        completeStockFoodProducers[id]?.let { return it }
        val value = before.streamed(id)
        check(value.identity.scope == scope && value.identity.producer.operationId == id && value.identity.producer.command == StockStreamingCommandsV2.FOOD)
        before.requireWholeStock(value)
        completeStockFoodProducers[id] = value
        return value
    }
    /** Exact preliminary header observation; installed code performs this on the
     * unwrapped native loan, before the separate financial read budget starts. */
    suspend fun observeFinancial(kind: String, id: DomainId): FoodFinancialEnvelopeV1? {
        val (row, value) = financialHeader(kind, id, charged = false) ?: return null
        before.qualifyHeader(row, value)
        return value
    }

    private suspend fun financialHeader(kind: String, id: DomainId, charged: Boolean): Pair<StoredAggregate, FoodFinancialEnvelopeV1>? {
        check(kind in foodFinancialDocumentKinds)
        val key = AggregateKey(kind, id)
        val row = if (charged) aggregate(key) else before.aggregate(key)
        val claim = if (charged) claim(foodSourceClaim(key)) else before.claim(foodSourceClaim(key))
        if (row == null) { check(claim == null); return null }
        check(claim == id && row.key == key && row.schema in setOf(LocalFoodFinancialProfileV1.schema(kind),
            foodFinancialSchemaV2(kind), foodStockSchema(kind)) && row.digest == sha256(row.payload.copyBytes()))
        val value = if (row.schema == foodStockSchema(kind)) {
            if (kind !in setOf(LocalFoodFinancialProfileV1.COLLECTION, LocalFoodFinancialProfileV1.SALE,
                    LocalFoodFinancialProfileV1.ENTITLEMENT, LocalFoodFinancialProfileV1.REFUND)) foodFinancialEnvelope(row.payload)
            else {
                val original = before.streamedSource(row) // header lookup is uncharged in installed code
                before.bind(row, original)
                requireCommerceWrite(original, row)
                foodStockReadDocument(original, row.payload)
            }
        } else if (row.schema == foodFinancialSchemaV2(kind)) {
            val root = foodFinancialRootV2(row.payload)
            val original = (if (charged) retainedCommand(root.operationId) else before.legacy(root.operationId))
                ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
            before.bind(row, original)
            check(original.committed && original.scope == scope && original.operationId == root.operationId &&
                (original.command == LocalFoodFinancialProfileV1.COMMAND || original.command == LocalFoodProfileV1.COMMAND && isPaidFoodHandoffProducer(original) ||
                    kind == LocalFoodFinancialProfileV1.COLLECTION && isFoodPaymentFinancialProducer(original) ||
                    kind == LocalFoodFinancialProfileV1.REFUND && isFoodRefundFinancialProducer(original)))
            requireLegacyWrite(original, row)
            val framing = when {
                isFoodPaymentFinancialProducer(original) -> foodPaymentStoredEvent(original).framing
                isFoodRefundFinancialProducer(original) -> foodRefundStoredEvent(original).framing
                isPaidFoodHandoffProducer(original) -> paidFoodHandoffEvent(original).framing
                else -> checkNotNull(foodFinancialStoredEvent(original).framing)
            }
            FoodFinancialBundleReaderV2(original, framing, allowStockInputs = true).document(row.payload)
        } else foodFinancialEnvelope(row.payload)
        check(value.scope == scope && value.kind == kind && value.id == id && value.revision == row.revision)
        return row to value
    }
    suspend fun financial(kind: String, id: DomainId): FoodFinancialRow? {
        val (row, value) = financialHeader(kind, id, charged = true) ?: return null
        if (row.schema == foodStockSchema(kind)) {
            val original = foodStockProducer(value.operationId)
            before.bind(row, original); requireCommerceWrite(original, row)
            return FoodFinancialRow(row, value, original)
        }
        val original = foodProducer(value.operationId)
        before.bind(row, original)
        check(original.command == LocalFoodFinancialProfileV1.COMMAND || original.command == LocalFoodProfileV1.COMMAND && isPaidFoodHandoffProducer(original) ||
            kind == LocalFoodFinancialProfileV1.COLLECTION && row.schema == foodFinancialSchemaV2(kind) && isFoodPaymentFinancialProducer(original) ||
            kind == LocalFoodFinancialProfileV1.REFUND && row.schema == foodFinancialSchemaV2(kind) && isFoodRefundFinancialProducer(original))
        requireLegacyWrite(original, row)
        requireFoodPredecessors(original)
        return FoodFinancialRow(row, value, original)
    }
    suspend fun batch(id: DomainId): FoodRow? {
        val key = LocalFoodProfileV1.key(LocalFoodProfileV1.PRICE_BATCH, id)
        val row = aggregate(key)
        val claim = claim(LocalFoodProfileV1.claim(LocalFoodProfileV1.PRICE_BATCH, id))
        if (row == null) { check(claim == null); return null }
        check(claim == id && (row.schema == LocalFoodProfileV1.schema(key.kind) || foodStreamedSchema(key.kind, row.schema)) &&
            sha256(row.payload.copyBytes()) == row.digest)
        val value = foodStoredEnvelope(row.schema, row.payload)
        check(value.kind == key.kind && value.id == id && value.scope == scope && value.revision == row.revision)
        if (foodStreamedSchema(key.kind, row.schema)) {
            val original = foodStockProducer(value.operationId)
            before.bind(row, original); requireCommerceWrite(original, row)
            return FoodRow(row, value, original)
        }
        val original = foodProducer(value.operationId)
        before.bind(row, original); requireLegacyWrite(original, row); requireFoodPredecessors(original)
        return FoodRow(row, value, original)
    }
    private fun requireLegacyWrite(original: RetainedCommandEvidence, row: StoredAggregate) {
        check(original.committed && original.scope == scope && original.aggregateWrites.single { it.key == row.key }.let {
            it.storageGeneration == row.revision && it.schema == row.schema && it.payload == row.payload && it.payloadDigest == row.digest })
    }
    private suspend fun paymentWhole(value: RetainedCommandEvidence): List<LocalFoodRecipeSnapshotV1> {
        val lineage = foodPaymentRetainedLineage(value) { retainedCommand(it) ?: error("food.before.payment_ancestor_missing") }
        val proof = LocalFoodPaymentProducer.requireStart(lineage.start)
        before.requirePaymentStock(checkNotNull(StockReadEvidenceV1.read(lineage.start)), lineage.start)
        for (row in proof.cut.portions) {
            val portion = row.body as FoodBodyV1.Portion
            val entry = proof.cut.collection.portions.single { it.portionId == row.id }
            proof.stock.issued[entry.reservationId]?.let { issued -> requireFoodFirstIssue(portion, before.issued(lineage.start, issued)) }
        }
        if (lineage.start.operationId != value.operationId) requireFoodPredecessors(lineage.start)
        lineage.originalUnknownApplication?.let { requireFoodPredecessors(it) }
        return if (lineage.start.operationId == value.operationId) proof.cut.portions.map { (it.body as FoodBodyV1.Portion).recipe }
            else LocalFoodPaymentProducer.requireComplete(value, lineage.start, lineage.originalRaw, lineage.resolutionRaw, lineage.originalUnknownApplication)
    }
    private suspend fun refundWhole(value: RetainedCommandEvidence) {
        val lineage = foodRefundRetainedLineage(value) { retainedCommand(it) ?: error("food.before.refund_ancestor_missing") }
        LocalFoodRefundProducer.requireComplete(value, lineage.start, lineage.originalRaw, lineage.resolutionRaw, lineage.originalUnknownApplication)
        if (lineage.start.operationId != value.operationId) requireFoodPredecessors(lineage.start)
        lineage.originalUnknownApplication?.let { requireFoodPredecessors(it) }
    }
    suspend fun foodProducer(id: OperationId): RetainedCommandEvidence {
        handoffOriginFailure?.let { throw it }
        val producer = retainedCommand(id) ?: error("food.producer_missing")
        check(producer.scope == scope && producer.operationId == id)
        // Kernel v1 proposals already bound literal payload to 1 MiB. A food v1 producer
        // exceeding this larger FRAMED envelope is not a newly permitted wire profile.
        check(foodProducerReadBytes(producer) <= FoodReadLimits.FOOD_PRODUCER_BYTES) { "food.producer_profile" }
        if (id !in completeFoodProducers) {
            check(checkingFoodProducers.add(id)) { "food.producer_custody_cycle" }
            try {
                capacity(checkingFoodProducers.size <= FoodReadLimits.PREDECESSOR_PRODUCERS)
                when (producer.command) {
                    LocalFoodProfileV1.COMMAND -> LocalFoodProducer.requireComplete(producer)
                    LocalFoodFinancialProfileV1.COMMAND -> LocalFoodFinancialProducer.requireComplete(producer)
                    LocalFoodPaymentProfile.COMMAND, LocalFoodPaymentSupervisorCodec.COMMAND ->
                        paymentWhole(producer)
                    LocalFoodRefundProfile.COMMAND, LocalFoodRefundSupervisorCodec.COMMAND -> refundWhole(producer)
                    else -> error("food.producer_profile")
                }
                if (producer.command == LocalFoodProfileV1.COMMAND) foodHandoffLegacyOriginInputV2(producer)?.let { before ->
                    check(queuedHandoffOrigins.add(id))
                    pendingHandoffOrigins.addLast(producer to before)
                }
                completeFoodProducers += id
            } finally { checkingFoodProducers.remove(id) }
        }
        requireHandoffOrigins()
        return producer
    }

    suspend fun requireFoodPredecessors(producer: RetainedCommandEvidence) {
        handoffOriginFailure?.let { throw it }
        if (producer.operationId in crosswalkedFoodProducers) return
        val originals = linkedMapOf<OperationId, RetainedCommandEvidence>()
        var predecessorBytes = 0L
        val payment = producer.command in foodPaymentCommands
        val refund = producer.command in foodRefundCommands
        val financialV2 = payment || refund || producer.command == LocalFoodFinancialProfileV1.COMMAND &&
            foodFinancialStoredEvent(producer).framing?.version == 2
        val paidHandoff = producer.command == LocalFoodProfileV1.COMMAND && isPaidFoodHandoffProducer(producer)
        val maximumOriginals = if (financialV2 || paidHandoff) FOOD_FINANCIAL_MAX_INPUTS_V2 else FoodReadLimits.PREDECESSOR_PRODUCERS
        val normalizedOriginals = mutableMapOf<OperationId, RetainedStreamedCommand>()
        val inputs = when (producer.command) {
            LocalFoodProfileV1.COMMAND -> if (paidHandoff) paidFoodHandoffInputs(producer)
                else if (isFoodHandoffProducerV2(producer)) foodHandoffInputsV2(producer)
                else foodEvent(producer).beforeIds.map { id ->
                val before = foodBefore(producer, id).envelope
                val bytes = foodEnvelopeBytes(before)
                FoodFinancialInputV1(before.kind, before.id, before.revision, LocalFoodProfileV1.schema(before.kind),
                    sha256(bytes.copyBytes()), before.operationId, bytes.copyBytes().decodeToString(throwOnInvalidSequence = true))
            }
            LocalFoodFinancialProfileV1.COMMAND -> foodFinancialInputs(producer)
            LocalFoodPaymentProfile.COMMAND, LocalFoodPaymentSupervisorCodec.COMMAND ->
                if (foodPaymentHasFinancialInputs(producer)) foodPaymentInputs(producer) else emptyList()
            LocalFoodRefundProfile.COMMAND, LocalFoodRefundSupervisorCodec.COMMAND -> if (isFoodRefundFinancialProducer(producer)) foodRefundInputs(producer) else emptyList()
            else -> error("food.predecessor_profile")
        }
        for (before in inputs) {
            if (before.schema == foodStockSchema(before.kind)) {
                check(paidHandoff || payment || refund)
                val original = normalizedOriginals[before.operationId] ?: run {
                    capacity(originals.size + normalizedOriginals.size < maximumOriginals)
                    foodStockProducer(before.operationId).also { source ->
                        check(source.identity.producer.operationId != producer.operationId &&
                            (source.identity.assignmentId != producer.assignmentId || source.identity.authorityEpoch != producer.authorityEpoch ||
                                source.identity.sequence < producer.sequence))
                        normalizedOriginals[before.operationId] = source
                    }
                }
                if (payment) requireFoodPaymentStreamedInput(original, before)
                else if (refund) requireFoodRefundStreamedInput(original, before)
                else requirePaidFoodStreamedInput(original, before)
                continue
            }
            val original = originals[before.operationId] ?: run {
                capacity(originals.size + normalizedOriginals.size < maximumOriginals)
                val prior = if (before.stock == null) foodProducer(before.operationId)
                    else retainedCommand(before.operationId) ?: error("food.stock_input_producer_missing")
                check(prior.committed && prior.scope == scope && prior.operationId == before.operationId)
                check(prior.operationId != producer.operationId &&
                    (prior.assignmentId != producer.assignmentId || prior.authorityEpoch != producer.authorityEpoch || prior.sequence < producer.sequence))
                predecessorBytes += foodProducerReadBytes(prior)
                capacity(predecessorBytes <= FoodReadLimits.PREDECESSOR_BYTES)
                originals[before.operationId] = prior
                prior
            }
            // A mixed producer can first appear as a stock input. Its raw retained bytes
            // cached above are not the whole food proof required by a later food input.
            if (before.stock == null && before.operationId !in completeFoodProducers) foodProducer(before.operationId)
            val source = original.aggregateWrites.single { it.key == before.key() }
            check(source.storageGeneration == before.revision && source.schema == before.schema && source.payload == before.bytes() &&
                source.payloadDigest == before.digest && source.payloadDigest == sha256(source.payload.copyBytes()))
            // No alternate stock decoder or native producer-name allowlist. These are literal
            // input bytes from a formerly complete normalized observation; any financial act
            // or rich portion projection MUST still load the current normalized closure.
            if (before.stock != null) requireFoodStockInput(before, original)
        }
        // The literal input census stays one-hop. foodProducer additionally discharges the
        // exceptional legacy-origin obligation in this same view; neither is a general
        // recursive-history/census proof, detached copy or truncated successful prefix.
        crosswalkedFoodProducers += producer.operationId
    }
    private suspend fun legacyOffer(successor: RetainedCommandEvidence, initial: FoodEnvelopeV1) {
        check(initial == foodHandoffLegacyOriginInputV2(successor))
    check(initial.scope == scope && initial.kind == LocalFoodProfileV1.ORDER)
    val pending = checkNotNull((initial.body as FoodBodyV1.Order).handoff)
    val key = AggregateKey(initial.kind, initial.id)
    var value = initial
    var later = successor
    val visited = mutableSetOf<OperationId>()
    while (true) {
        check(visited.add(value.operationId)) { "food.handoff.legacy_origin_cycle" }
        val producer = foodProducer(value.operationId)
        check(producer.command == LocalFoodProfileV1.COMMAND)
        check(!isFoodHandoffProducerV2(producer) && !isPaidFoodHandoffProducer(producer)) { "food.handoff.v2_record_missing" }
        later?.let { next -> check(producer.operationId != next.operationId &&
            (producer.assignmentId != next.assignmentId || producer.authorityEpoch != next.authorityEpoch || producer.sequence < next.sequence)) }
        val original = producer.aggregateWrites.single { it.key == key }
        val bytes = foodEnvelopeBytes(value)
        check(value.scope == scope && value.kind == key.kind && value.id == key.id &&
            original.storageGeneration == value.revision && original.schema == LocalFoodProfileV1.schema(key.kind) &&
            original.payload == bytes && original.payloadDigest == sha256(bytes.copyBytes()))
        check((value.body as FoodBodyV1.Order).let { it.handoff == pending && it.ownerId == pending.from })
        val request = LocalFoodCodecV1.decode(producer.normalized)
        check(request is LocalFoodRequestV1.OfferHandoff || request is LocalFoodRequestV1.Station || request is LocalFoodRequestV1.DiscardStarted)
        requireFoodPredecessors(producer)
        if (request is LocalFoodRequestV1.OfferHandoff) {
            check(request.orderId == key.id && request.handoffId == pending.id && request.recipientId == pending.to &&
                producer.originalActorId == pending.from && producer.recordedAt == pending.offeredAt)
            return
        }
        val before = foodEvent(producer).beforeIds.map { foodBefore(producer, it).envelope }.single { it.kind == key.kind && it.id == key.id }
        check(before.scope == scope && before.revision.next() == value.revision &&
            (before.body as FoodBodyV1.Order).handoff == pending)
        value = before
        later = producer
    }
    }
    private suspend fun requireHandoffOrigins() {
        handoffOriginFailure?.let { throw it }
        if (checkingHandoffOrigins) return
        checkingHandoffOrigins = true
        try {
            while (pendingHandoffOrigins.isNotEmpty()) {
                val (producer, before) = pendingHandoffOrigins.removeFirst()
                legacyOffer(producer, before)
            }
        } catch (failure: Throwable) {
            handoffOriginFailure = failure
            throw failure
        } finally { checkingHandoffOrigins = false }
    }
}

internal class FoodBeforeApplicationCapacity : RuntimeException()

/** Only the two actual native source owners implement this; no caller reply port. */
private sealed interface FoodFinancialBeforeOwner {
    val scope: BranchScope
    val original: RetainedCommandEvidence
    suspend fun <T> withOriginalProofs(block: suspend () -> T): T
    suspend fun qualifyHeader(row: StoredAggregate, value: FoodFinancialEnvelopeV1)
    suspend fun aggregate(key: AggregateKey): StoredAggregate?
    suspend fun claim(key: ClaimKey): DomainId?
    suspend fun legacy(id: OperationId): RetainedCommandEvidence?
    suspend fun bind(row: StoredAggregate, original: RetainedCommandEvidence)
    suspend fun bind(row: StoredAggregate, original: RetainedStreamedCommand)
    suspend fun streamedSource(row: StoredAggregate): RetainedStreamedCommand
    suspend fun streamed(id: OperationId): RetainedStreamedCommand
    suspend fun requireWholeStock(original: RetainedStreamedCommand)
    suspend fun requirePaymentStock(observed: StockReadObservations, owner: RetainedCommandEvidence)
    suspend fun issued(owner: RetainedCommandEvidence, cut: LocalStockPaymentIssuedCut): LocalStockIssueOriginal
}
private class FoodHistoryBeforeOwner(private val command: RuntimeHistoricalCommand) : FoodFinancialBeforeOwner {
    override val scope get() = command.scope
    override val original get() = (command.original as? RetainedOriginalV2.Legacy)?.evidence
        ?: error("food.before.legacy_context_required")
    override suspend fun <T> withOriginalProofs(block: suspend () -> T): T = block()
    override suspend fun qualifyHeader(row: StoredAggregate, value: FoodFinancialEnvelopeV1) {
        // The real native aggregate source requires its actual earlier complete business
        // join. Do not substitute a retained DTO or infer it from immutable row shape.
        val source = command.aggregateSource(row.key) ?: error("food.before.source_required")
        when (val origin = source.original) {
            is RetainedOriginalV2.Legacy -> check(command.earlierOriginal(origin.evidence.operationId) === origin &&
                origin.evidence.committed && origin.evidence.operationId == value.operationId)
            is RetainedOriginalV2.Streamed -> check(command.earlierOriginal(origin.evidence.identity.producer.operationId) === origin &&
                origin.evidence.identity.producer.operationId == value.operationId)
        }
    }
    override suspend fun aggregate(key: AggregateKey) = command.aggregateSource(key)?.row
    override suspend fun claim(key: ClaimKey) = command.claim(key)
    override suspend fun legacy(id: OperationId) = when (val original = command.earlierOriginal(id)) {
        is RetainedOriginalV2.Legacy -> original.evidence
        null -> null
        is RetainedOriginalV2.Streamed -> error("food.before.legacy_original_required")
    }
    override suspend fun bind(row: StoredAggregate, original: RetainedCommandEvidence) {
        val source = command.aggregateSource(row.key) ?: error("food.before.row_source_required")
        check(source.row == row && (source.original as? RetainedOriginalV2.Legacy)?.evidence === original)
    }
    override suspend fun bind(row: StoredAggregate, original: RetainedStreamedCommand) {
        val source = command.aggregateSource(row.key) ?: error("food.before.row_source_required")
        check(source.row == row && (source.original as? RetainedOriginalV2.Streamed)?.evidence === original)
    }
    override suspend fun streamedSource(row: StoredAggregate): RetainedStreamedCommand {
        val source = command.aggregateSource(row.key) ?: error("food.before.row_source_required")
        check(source.row == row)
        val origin = source.original as? RetainedOriginalV2.Streamed ?: error("food.before.streamed_row_required")
        check(command.earlierOriginal(origin.evidence.identity.producer.operationId) === origin)
        return origin.evidence
    }
    override suspend fun streamed(id: OperationId) =
        (command.earlierOriginal(id) as? RetainedOriginalV2.Streamed)?.evidence ?: error("food.before.streamed_original_required")
    override suspend fun requireWholeStock(original: RetainedStreamedCommand) {
        // Core issues this exact object only after the installed WHOLE business join.
        check((command.earlierOriginal(original.identity.producer.operationId) as? RetainedOriginalV2.Streamed)?.evidence === original)
    }
    override suspend fun requirePaymentStock(observed: StockReadObservations, owner: RetainedCommandEvidence) =
        LocalFinancialStockBeforeSources.requireInputs(command, owner, observed)
    override suspend fun issued(owner: RetainedCommandEvidence, cut: LocalStockPaymentIssuedCut) =
        LocalFinancialStockBeforeSources.requireIssued(command, owner, checkNotNull(StockReadEvidenceV1.read(owner)), cut)
}
private class FoodRetainedBeforeOwner(private val sources: RuntimeHistoricalFinancialSources,
    private val originalLoan: CommandReadView, private val stock: LocalStockRepository) : FoodFinancialBeforeOwner {
    override val scope get() = sources.evidence.scope
    override val original get() = sources.evidence
    override suspend fun <T> withOriginalProofs(block: suspend () -> T): T = immutable.withFinancialStockOriginals(block)
    override suspend fun qualifyHeader(row: StoredAggregate, value: FoodFinancialEnvelopeV1) = qualification {
        // Validation-only extra work does not enter original first-cause accounting.
        // A failure here remains unavailable instead of becoming a capacity witness.
        val qualified = FoodFinancialBeforeReadBudget(sources, originalLoan, stock).financial(row.key.kind, row.key.id)
            ?: error("food.before.header_source_required")
        check(qualified.row == row)
        val input = foodPaymentInput(qualified)
        check(sameFoodPaymentCutInputs(listOf(input), listOf(input.copy(financial = value))))
    }
    private val immutable = FoodReadView(commerceLoan(originalLoan, stock))
    init { check(originalLoan.scope == scope) }
    override suspend fun aggregate(key: AggregateKey): StoredAggregate? {
        val source = sources.aggregateSource(key) ?: return null
        if (key.kind in setOf(CashNonSaleStoreProfile.SESSION_KIND, LocalSalesStoreProfile.DRAWER_KIND,
                LocalSalesStoreProfile.CUSTODIAN_KIND, LocalSalesStoreProfile.WORK_KIND))
            financialOriginalProof(originalLoan).requireOriginal(source.original)
        return source.row
    }
    override suspend fun claim(key: ClaimKey): DomainId? = if (key.namespace in setOf(LocalSalesStoreProfile.CONFIGURED_NAMESPACE,
            LocalSalesStoreProfile.ACTIVE_DRAWER_NAMESPACE, LocalSalesStoreProfile.ACTIVE_CUSTODIAN_NAMESPACE,
            LocalSalesStoreProfile.UNIQUE_NAMESPACE, CashNonSaleStoreProfile.CASH_SOURCE_NAMESPACE))
        financialOriginalProof(originalLoan).claim(sources, key) else sources.claim(key)
    override suspend fun legacy(id: OperationId): RetainedCommandEvidence? = qualification {
        originalLoan.retainedCommand(id)?.also { value ->
            check(value.scope == scope && value.operationId == id && value.assignmentId == sources.evidence.assignmentId &&
                value.authorityEpoch == sources.evidence.authorityEpoch && value.sequence < sources.evidence.sequence)
        }
    }
    override suspend fun bind(row: StoredAggregate, original: RetainedCommandEvidence) {
        val source = sources.aggregateSource(row.key) ?: error("food.before.row_source_required")
        val paired = (source.original as? RetainedOriginalV2.Legacy)?.evidence ?: error("food.before.legacy_row_required")
        check(source.row == row); requireSameFinancialRpcOriginal(original, paired)
    }
    override suspend fun bind(row: StoredAggregate, original: RetainedStreamedCommand) {
        val source = sources.aggregateSource(row.key) ?: error("food.before.row_source_required")
        val paired = (source.original as? RetainedOriginalV2.Streamed)?.evidence ?: error("food.before.streamed_row_required")
        check(source.row == row && paired.identity == original.identity)
    }
    override suspend fun streamedSource(row: StoredAggregate): RetainedStreamedCommand {
        val source = sources.aggregateSource(row.key) ?: error("food.before.row_source_required")
        check(source.row == row)
        return (source.original as? RetainedOriginalV2.Streamed)?.evidence ?: error("food.before.streamed_row_required")
    }
    override suspend fun streamed(id: OperationId): RetainedStreamedCommand = qualification {
        val original = (originalLoan.requireStockRelational().originals.read(id) as? RetainedOriginalV2.Streamed)?.evidence
            ?: error("food.before.streamed_original_required")
        check(original.identity.scope == scope && original.identity.producer.operationId == id &&
            original.identity.assignmentId == sources.evidence.assignmentId && original.identity.authorityEpoch == sources.evidence.authorityEpoch &&
            original.identity.sequence < sources.evidence.sequence)
        original
    }
    override suspend fun requireWholeStock(original: RetainedStreamedCommand) = qualification {
        val actual = immutable.foodStockProducer(original.identity.producer.operationId)
        check(actual.identity == original.identity)
    }
    override suspend fun requirePaymentStock(observed: StockReadObservations, owner: RetainedCommandEvidence) = qualification {
        immutable.requireFinancialOriginalStock(observed, owner)
    }
    override suspend fun issued(owner: RetainedCommandEvidence, cut: LocalStockPaymentIssuedCut): LocalStockIssueOriginal = qualification {
        LocalStockPaymentObservations.requireIssuedOriginal(immutable, stock, cut)
    }
    private suspend fun <T> qualification(block: suspend () -> T): T = try { block() }
        catch (_: FoodCapacityExceeded) { throw FoodBeforeSourceQualificationUnavailable() }
        catch (_: FoodBeforeApplicationCapacity) { throw FoodBeforeSourceQualificationUnavailable() }
        catch (_: LocalSalesSourceUnavailable) { throw FoodBeforeSourceQualificationUnavailable() }
}
internal class FoodBeforeSourceQualificationUnavailable : RuntimeException()
