package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId

/** Expected finite-work refusal only. Do not derive this from an arbitrary storage exception. */
internal class FoodCapacityExceeded : RuntimeException("food.capacity.required")

internal fun foodCapacity(available: Boolean) {
    if (!available) throw FoodCapacityExceeded()
}

/**
 * One request-wide envelope, shared by food, catalog, cash-admission and normalized-stock reads.
 * These are workload limits, not permissions or evidence of a complete/absent ledger. The
 * single-producer/predecessor limits are also enforced on NEW proposals before Apply exists.
 */
internal object FoodReadLimits {
    const val REQUEST_BYTES: Long = 64L * 1_024 * 1_024
    const val REQUEST_CALLS: Int = 32_768
    const val REQUEST_ROWS: Int = 16_384
    const val REQUEST_PRODUCERS: Int = 4_096
    const val FOOD_PRODUCER_BYTES: Long = 2L * 1_024 * 1_024
    const val PREDECESSOR_BYTES: Long = 8L * 1_024 * 1_024
    const val PREDECESSOR_PRODUCERS: Int = 64
}

/**
 * Conservative literal-data/framing accounting, including jobs and claims, not just AFTERs.
 * V2 financial raw chunks, their event manifest and compact roots are actual aggregateWrites,
 * so none can disappear from this charge. Decoding a literal bundle performs no further IO.
 */
internal fun foodProducerReadBytes(evidence: RetainedCommandEvidence): Long =
    8_192L + evidence.normalized.size + evidence.result.size +
        evidence.aggregateWrites.sumOf { it.payload.size.toLong() + 512 } +
        evidence.claimChanges.sumOf { 128L + it.key.namespace.length + it.key.key.length } +
        evidence.jobs.sumOf { it.body.size.toLong() + 128 }

/**
 * Loan-confined immutable observations. Never retain this view in PreparedMutation/PreparedRead,
 * a service field or a client cache. In-request reuse is not a grant, a recovery frontier, or a
 * substitute for the repository's full stock/source crosswalk. Every use, including cache hits,
 * is charged so separate stock loaders cannot each silently spend the whole request budget.
 *
 * Current-identity authorization is the kernel's responsibility. Per-action authorization is
 * still performed by the kernel AFTER prepare and BEFORE evaluate/project; no public result
 * may be emitted by this view, and observing a target header cannot authorize an Apply.
 */
internal class FoodReadView(private val borrowed: CommandReadView) : CommerceStockView {
    override val commerceStock: com.ukkera.pos.inventory.store.LocalStockRepository get() = commerceRepository(borrowed)
    override val commerceTenderPolicies: HistoricalSalesTenderPolicySourceV1 get() = commerceTenderPolicies(borrowed)
    override suspend fun stockRelationalAccess(): StockRelationalAccess = borrowed.requireStockRelational()
    override suspend fun recordCommerceOriginal(row: StoredAggregate, original: RetainedStreamedCommand) =
        commerceRecordOriginal(borrowed, row, original)
    override suspend fun observeRecoveryResources(resources: Set<com.ukkera.pos.recovery.RecoveryResourceKey>) {
        (borrowed as? LocalCommerceResourceCollector)?.observeRecoveryResources(resources)
    }
    internal suspend fun recordLegacyStockSource(row: StoredAggregate, original: RetainedCommandEvidence) =
        commerceRecordLegacy(borrowed, row, original)
    private val completeStockFoodProducers = mutableMapOf<OperationId, RetainedStreamedCommand>()
    /** Streaming physical stock cardinality is charged by its owner, not flattened into v1 bytes. */
    internal suspend fun foodStockProducer(id: OperationId): RetainedStreamedCommand {
        call(); producers++; foodCapacity(producers <= FoodReadLimits.REQUEST_PRODUCERS)
        data(512L)
        completeStockFoodProducers[id]?.let { return it }
        val original = (stockRelationalAccess().originals.read(id) as? RetainedOriginalV2.Streamed)?.evidence
            ?: error("food.stock.original_missing")
        check(original.identity.scope == scope && original.identity.producer.operationId == id &&
            original.identity.producer.command == StockStreamingCommandsV2.FOOD)
        commerceStock.requireRelationalProducer(this, original)
        completeStockFoodProducers[id] = original
        return original
    }
    override val scope: BranchScope get() = borrowed.scope
    private var bytes: Long = 0
    private var calls: Int = 0
    private var rows: Int = 0
    private var producers: Int = 0
    private val aggregates = mutableMapOf<AggregateKey, StoredAggregate?>()
    private val claims = mutableMapOf<ClaimKey, DomainId?>()
    private val retained = mutableMapOf<OperationId, RetainedCommandEvidence?>()
    private val completeFoodProducers = mutableSetOf<OperationId>()
    private val checkingFoodProducers = mutableSetOf<OperationId>()
    private val crosswalkedFoodProducers = mutableSetOf<OperationId>()
    private val queuedHandoffOrigins = mutableSetOf<OperationId>()
    private val pendingHandoffOrigins = ArrayDeque<Pair<RetainedCommandEvidence, FoodEnvelopeV1>>()
    private var checkingHandoffOrigins = false
    private var handoffOriginFailure: Throwable? = null

    private fun call() { calls++; foodCapacity(calls <= FoodReadLimits.REQUEST_CALLS) }
    private fun data(size: Long) {
        check(size >= 0)
        foodCapacity(size <= FoodReadLimits.REQUEST_BYTES - bytes)
        bytes += size
    }

    override suspend fun aggregate(key: AggregateKey): StoredAggregate? {
        call(); rows++; foodCapacity(rows <= FoodReadLimits.REQUEST_ROWS)
        val value = if (key in aggregates) aggregates[key] else borrowed.aggregate(key).also { aggregates[key] = it }
        data(512L + (value?.payload?.size ?: 0))
        return value
    }

    override suspend fun claim(key: ClaimKey): DomainId? {
        call()
        data(256L + key.namespace.length + key.key.length)
        return if (key in claims) claims[key] else borrowed.claim(key).also { claims[key] = it }
    }

    override suspend fun aggregateKeys(kind: String, after: DomainId?, limit: Int): AggregateKeyPage {
        call()
        // The underlying kernel independently validates the key/limit and loan. No short page
        // is synthesized when a complete bounded page cannot be observed.
        val page = borrowed.aggregateKeys(kind, after, limit)
        data(256L + page.keys.size * 128L)
        return page
    }

    override suspend fun claimKeys(namespace: String, prefix: String, after: String?, limit: Int): ClaimKeyPage {
        call()
        val page = borrowed.claimKeys(namespace, prefix, after, limit)
        rows += page.entries.size; foodCapacity(rows <= FoodReadLimits.REQUEST_ROWS)
        data(256L + page.entries.sumOf { 128L + it.key.namespace.length + it.key.key.length })
        return page
    }

    /** Stop a directory page BEFORE another whole-source unit; never publish a partial proof. */
    internal fun handoffCandidateHeadroom(): Boolean = bytes <= FoodReadLimits.REQUEST_BYTES - 40L * 1_024 * 1_024 &&
        calls <= FoodReadLimits.REQUEST_CALLS - 256 && rows <= FoodReadLimits.REQUEST_ROWS - 256 &&
        producers <= FoodReadLimits.REQUEST_PRODUCERS - 128

    /** Core accepts only the actual same-loan writer, not this budget wrapper or a copied grant. */
    internal fun handoffBorrowedView(): CommandReadView = when (borrowed) {
        is CommerceLoan -> borrowed.handoffBorrowedView()
        is FoodReadView -> borrowed.handoffBorrowedView()
        else -> borrowed
    }

    override suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence? {
        call(); producers++; foodCapacity(producers <= FoodReadLimits.REQUEST_PRODUCERS)
        val value = if (operationId in retained) retained[operationId]
        else borrowed.retainedCommand(operationId).also { retained[operationId] = it }
        data(value?.let(::foodProducerReadBytes) ?: 512L)
        return value
    }

    override suspend fun foodCheckRejectedOriginalSources(operationId: OperationId): RuntimeHistoricalFoodCheckRejectedSources? {
        call(); producers++; foodCapacity(producers <= FoodReadLimits.REQUEST_PRODUCERS)
        val source = borrowed.foodCheckRejectedOriginalSources(operationId)
        data(source?.evidence?.let(::foodProducerReadBytes) ?: 512L)
        source?.beforeAggregateSources?.forEach { literal ->
            val row = literal.row
            rows++; foodCapacity(rows <= FoodReadLimits.REQUEST_ROWS)
            data(512L + row.payload.size)
        }
        source?.beforeClaimSources?.forEach { literal ->
            data(256L + literal.key.namespace.length + literal.key.key.length)
        }
        source?.beforeAbsentAggregateKeys?.forEach { data(256L + it.kind.length + it.id.value.length) }
        source?.beforeAbsentClaimKeys?.forEach { data(256L + it.namespace.length + it.key.length) }
        return source
    }

    override suspend fun foodPaymentAlreadyAppliedOriginalSources(operationId: OperationId): RuntimeHistoricalFoodPaymentAlreadyAppliedSources? {
        call(); producers++; foodCapacity(producers <= FoodReadLimits.REQUEST_PRODUCERS)
        val source = borrowed.foodPaymentAlreadyAppliedOriginalSources(operationId)
        data(source?.evidence?.let(::foodProducerReadBytes) ?: 512L)
        data(256L + (source?.resources?.size ?: 0) * 128L)
        // Native source capsules hold complete originals beside this view's own later
        // producer reads. Charge each escaped object once, not only its selected rows.
        val sourceOriginals = mutableSetOf<RetainedOriginalV2>()
        fun original(value: RetainedOriginalV2) {
            if (!sourceOriginals.add(value)) return
            producers++; foodCapacity(producers <= FoodReadLimits.REQUEST_PRODUCERS)
            data(when (value) {
                is RetainedOriginalV2.Legacy -> foodProducerReadBytes(value.evidence)
                is RetainedOriginalV2.Streamed -> 8_192L + value.evidence.normalized.size + value.evidence.result.size
            })
        }
        source?.beforeAggregateSources?.forEach { literal ->
            original(literal.original)
            rows++; foodCapacity(rows <= FoodReadLimits.REQUEST_ROWS)
            data(512L + literal.row.payload.size)
        }
        source?.beforeClaimSources?.forEach { literal ->
            original(literal.original)
            data(256L + literal.key.namespace.length + literal.key.key.length)
        }
        source?.beforeAbsentAggregateKeys?.forEach { data(256L + it.kind.length + it.id.value.length) }
        source?.beforeAbsentClaimKeys?.forEach { data(256L + it.namespace.length + it.key.length) }
        return source
    }

    override suspend fun foodPaymentValueMismatchOriginalSources(operationId: OperationId): RuntimeHistoricalFoodPaymentValueMismatchSources? {
        call(); producers++; foodCapacity(producers <= FoodReadLimits.REQUEST_PRODUCERS)
        val source = borrowed.foodPaymentValueMismatchOriginalSources(operationId)
        data(source?.evidence?.let(::foodProducerReadBytes) ?: 512L)
        data(256L + (source?.resources?.size ?: 0) * 128L)
        // Native source capsules hold complete originals beside this view's own later
        // producer reads. Charge each escaped object once, not only its selected rows.
        val sourceOriginals = mutableSetOf<RetainedOriginalV2>()
        fun original(value: RetainedOriginalV2) {
            if (!sourceOriginals.add(value)) return
            producers++; foodCapacity(producers <= FoodReadLimits.REQUEST_PRODUCERS)
            data(when (value) {
                is RetainedOriginalV2.Legacy -> foodProducerReadBytes(value.evidence)
                is RetainedOriginalV2.Streamed -> 8_192L + value.evidence.normalized.size + value.evidence.result.size
            })
        }
        source?.beforeAggregateSources?.forEach { literal ->
            original(literal.original)
            rows++; foodCapacity(rows <= FoodReadLimits.REQUEST_ROWS)
            data(512L + literal.row.payload.size)
        }
        source?.beforeClaimSources?.forEach { literal ->
            original(literal.original)
            data(256L + literal.key.namespace.length + literal.key.key.length)
        }
        source?.beforeAbsentAggregateKeys?.forEach { data(256L + it.kind.length + it.id.value.length) }
        source?.beforeAbsentClaimKeys?.forEach { data(256L + it.namespace.length + it.key.length) }
        return source
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
                foodCapacity(checkingFoodProducers.size <= FoodReadLimits.PREDECESSOR_PRODUCERS)
                when (producer.command) {
                    LocalFoodProfileV1.COMMAND -> LocalFoodProducer.requireComplete(producer)
                    LocalFoodFinancialProfileV1.COMMAND -> LocalFoodFinancialProducer.requireComplete(producer)
                    LocalFoodPaymentProfile.COMMAND, LocalFoodPaymentSupervisorCodec.COMMAND ->
                        requireFoodPaymentOriginalCustody(this, producer, commerceStock)
                    LocalFoodRefundProfile.COMMAND -> requireFoodRefundOriginalCustody(this, producer, commerceStock)
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

    /**
     * Literal completeness cannot prove that an omitted offer was genuinely v1. Every loaded
     * resolving legacy producer, INCLUDING a predecessor used for some other current row,
     * therefore carries a same-view source obligation. Drain iteratively: nested direct-input
     * walks may enqueue further obligations, but no outer foodProducer call returns until all
     * are proved. Do not recursively expand whole histories or reset the request budget.
     */
    private suspend fun requireHandoffOrigins() {
        handoffOriginFailure?.let { throw it }
        if (checkingHandoffOrigins) return
        checkingHandoffOrigins = true
        try {
            while (pendingHandoffOrigins.isNotEmpty()) {
                val (producer, before) = pendingHandoffOrigins.removeFirst()
                foodRequireLegacyResolutionOriginV2(this, producer, before)
            }
        } catch (failure: Throwable) {
            handoffOriginFailure = failure
            throw failure
        } finally { checkingHandoffOrigins = false }
    }

    suspend fun requireFoodPredecessors(producer: RetainedCommandEvidence) {
        handoffOriginFailure?.let { throw it }
        if (producer.operationId in crosswalkedFoodProducers) return
        val originals = linkedMapOf<OperationId, RetainedCommandEvidence>()
        var predecessorBytes = 0L
        val payment = producer.command in foodPaymentCommands
        val refund = producer.command == LocalFoodRefundProfile.COMMAND
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
            LocalFoodRefundProfile.COMMAND -> if (isFoodRefundFinancialProducer(producer)) foodRefundInputs(producer) else emptyList()
            else -> error("food.predecessor_profile")
        }
        for (before in inputs) {
            if (before.schema == foodStockSchema(before.kind)) {
                check(paidHandoff || payment || refund)
                val original = normalizedOriginals[before.operationId] ?: run {
                    foodCapacity(originals.size + normalizedOriginals.size < maximumOriginals)
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
                foodCapacity(originals.size + normalizedOriginals.size < maximumOriginals)
                val prior = if (before.stock == null) foodProducer(before.operationId)
                    else retainedCommand(before.operationId) ?: error("food.stock_input_producer_missing")
                check(prior.committed && prior.scope == scope && prior.operationId == before.operationId)
                check(prior.operationId != producer.operationId &&
                    (prior.assignmentId != producer.assignmentId || prior.authorityEpoch != producer.authorityEpoch || prior.sequence < producer.sequence))
                predecessorBytes += foodProducerReadBytes(prior)
                foodCapacity(predecessorBytes <= FoodReadLimits.PREDECESSOR_BYTES)
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
}

internal fun foodReadView(view: CommandReadView): FoodReadView = view as? FoodReadView ?: FoodReadView(view)

/** Admission uses verified immutable current-row producers captured within the original loan. */
internal fun foodAdmitPredecessors(before: Collection<FoodRow>) =
    foodAdmitInputProducers(before.map { it.producer }, FoodReadLimits.PREDECESSOR_PRODUCERS)

internal fun foodAdmitHandoffInputProducers(producers: List<RetainedCommandEvidence>) =
    foodAdmitInputProducers(producers, FoodReadLimits.PREDECESSOR_PRODUCERS)

internal fun foodAdmitFinancialInputProducers(producers: List<RetainedCommandEvidence>) =
    foodAdmitInputProducers(producers, FOOD_FINANCIAL_MAX_INPUTS_V2)

private fun foodAdmitInputProducers(producers: List<RetainedCommandEvidence>, maximumOriginals: Int) {
    val originals = producers.distinctBy { it.operationId }
    val bytes = originals.sumOf(::foodProducerReadBytes)
    foodCapacity(originals.size <= maximumOriginals &&
        bytes <= FoodReadLimits.PREDECESSOR_BYTES)
    // The ordinary literal/direct-source envelope fits a fresh loan: at most two current
    // producer charges (v2 header + whole proof), two charges per original predecessor
    // (a mixed producer first seen as stock, then required as whole food), and its bounded
    // root/claim. This does not admit an uncharged external frame or guarantee that the
    // exceptional genuine-legacy lineage walk also fits; that work still refuses on capacity.
    // This is NOT a promise that every multi-row rich projection/current-stock closure
    // fits one request; those still share the unchanged finite budget and refuse explicitly.
    foodCapacity(2 * FoodReadLimits.FOOD_PRODUCER_BYTES + 2 * bytes + 65_536 + 1_024 <= FoodReadLimits.REQUEST_BYTES &&
        2 + 2 * originals.size <= FoodReadLimits.REQUEST_PRODUCERS && 4 + 2 * originals.size <= FoodReadLimits.REQUEST_CALLS)
}

/** Check known proposal capacity explicitly; do not translate arbitrary Apply invariant errors. */
internal fun foodAdmitProposal(result: StoredBytes, changes: List<AggregateChange>,
    claims: List<ClaimChange>, jobs: List<JobIntent>) {
    foodCapacity(changes.size <= 128 && claims.size <= 128 && jobs.size <= 32 &&
        changes.sumOf { it.payload.size.toLong() } + jobs.sumOf { it.body.size.toLong() } + result.size <= 1_048_576L)
    // Preparation commands have a 16,384-byte input cap; financial commands have a smaller
    // 8,192-byte cap. Count the larger bound for both, including every v2 chunk/root/event,
    // not an invented retained device/resource/deadline field absent from evidence v1.
    val framed = 8_192L + 16_384 + result.size + changes.sumOf { it.payload.size.toLong() + 512 } +
        claims.sumOf { 128L + it.key.namespace.length + it.key.key.length } + jobs.sumOf { it.body.size.toLong() + 128 }
    foodCapacity(framed <= FoodReadLimits.FOOD_PRODUCER_BYTES)
}

/**
 * Original-only counterpart for the streamed consumer's mixed legacy inputs. It borrows the
 * ACTUAL retained reader from the original's still-active loan; it never implements a fake
 * CommandReadView, fabricates StoredAggregate, or substitutes today's owner/recipe for history.
 * The old direct-input limits and exceptional queued v1-offer lineage rule remain unchanged.
 */
internal class FoodRetainedCustody(private val owner: RetainedStreamedCommand,
    private val recipes: TransactionFoodStockSource,
    private val stock: com.ukkera.pos.inventory.store.LocalStockRelationalInspection) {
    private var bytes = 0L
    private var calls = 0
    private val full = mutableSetOf<OperationId>()
    private val checkingWhole = mutableSetOf<OperationId>()
    private val direct = mutableSetOf<OperationId>()
    private val queued = mutableSetOf<OperationId>()
    private val origins = ArrayDeque<Pair<RetainedCommandEvidence, FoodEnvelopeV1>>()
    private var draining = false
    private suspend fun read(id: OperationId): RetainedCommandEvidence {
        calls++; foodCapacity(calls <= FoodReadLimits.REQUEST_PRODUCERS)
        val value = (stock.original(id) as? RetainedOriginalV2.Legacy)?.evidence ?: error("food.stock.legacy_original_missing")
        check(value.committed && value.scope == owner.identity.scope && value.operationId == id)
        val charge = foodProducerReadBytes(value)
        foodCapacity(charge <= FoodReadLimits.REQUEST_BYTES - bytes); bytes += charge
        return value
    }
    private suspend fun whole(id: OperationId): RetainedCommandEvidence {
        val value = read(id)
        check(foodProducerReadBytes(value) <= FoodReadLimits.FOOD_PRODUCER_BYTES)
        if (id !in full) {
            check(checkingWhole.add(id)) { "food.stock.legacy_custody_cycle" }
            try {
                foodCapacity(checkingWhole.size <= FoodReadLimits.PREDECESSOR_PRODUCERS)
                val snapshots = when (value.command) {
                    LocalFoodProfileV1.COMMAND -> LocalFoodProducer.requireComplete(value)
                    LocalFoodFinancialProfileV1.COMMAND -> LocalFoodFinancialProducer.requireComplete(value)
                    LocalFoodPaymentProfile.COMMAND, LocalFoodPaymentSupervisorCodec.COMMAND -> paymentWhole(value)
                    LocalFoodRefundProfile.COMMAND -> { refundWhole(value); emptyList<LocalFoodRecipeSnapshotV1>() }
                    else -> error("food.stock.legacy_commerce_profile")
                }
                snapshots.forEach { recipes.requireLegacyRecipe(it, value.scope) }
                if (value.command == LocalFoodProfileV1.COMMAND) foodHandoffLegacyOriginInputV2(value)?.let { before ->
                    check(queued.add(id)); origins.addLast(value to before)
                }
                full += id // Only after the whole proof, never an in-progress recursive shortcut.
            } finally { checkingWhole.remove(id) }
        }
        return value
    }
    suspend fun requireWholeAndDirect(id: OperationId) {
        val value = whole(id)
        inputs(value)
        if (draining) return
        draining = true
        try {
            while (origins.isNotEmpty()) {
                val (resolving, initial) = origins.removeFirst()
                legacyOffer(initial, resolving)
            }
        } finally { draining = false }
    }
    private suspend fun inputs(value: RetainedCommandEvidence) {
        if (value.operationId in direct) return
        val list = when (value.command) {
            LocalFoodProfileV1.COMMAND -> if (isPaidFoodHandoffProducer(value)) paidFoodHandoffInputs(value)
                else if (isFoodHandoffProducerV2(value)) foodHandoffInputsV2(value)
                else foodEvent(value).beforeIds.map { id ->
                    val before = foodBefore(value, id).envelope
                    val payload = foodEnvelopeBytes(before)
                    FoodFinancialInputV1(before.kind, before.id, before.revision, LocalFoodProfileV1.schema(before.kind),
                        sha256(payload.copyBytes()), before.operationId, payload.copyBytes().decodeToString(throwOnInvalidSequence = true))
                }
            LocalFoodFinancialProfileV1.COMMAND -> foodFinancialInputs(value)
            LocalFoodPaymentProfile.COMMAND, LocalFoodPaymentSupervisorCodec.COMMAND ->
                if (foodPaymentHasFinancialInputs(value)) foodPaymentInputs(value) else emptyList()
            LocalFoodRefundProfile.COMMAND -> if (isFoodRefundFinancialProducer(value)) foodRefundInputs(value) else emptyList()
            else -> error("food.stock.legacy_direct_profile")
        }
        val payment = value.command in foodPaymentCommands
        val refund = value.command == LocalFoodRefundProfile.COMMAND
        val financialV2 = payment || refund || value.command == LocalFoodFinancialProfileV1.COMMAND && foodFinancialStoredEvent(value).framing?.version == 2
        val maximum = if (financialV2 || isPaidFoodHandoffProducer(value)) FOOD_FINANCIAL_MAX_INPUTS_V2 else FoodReadLimits.PREDECESSOR_PRODUCERS
        val originals = mutableMapOf<OperationId, RetainedCommandEvidence>()
        val normalized = mutableMapOf<OperationId, RetainedStreamedCommand>()
        var sourceBytes = 0L
        for (before in list) {
            if (before.schema == foodStockSchema(before.kind)) {
                check(isPaidFoodHandoffProducer(value) || payment || refund)
                val original = normalized[before.operationId] ?: run {
                    calls++; foodCapacity(calls <= FoodReadLimits.REQUEST_PRODUCERS && originals.size + normalized.size < maximum)
                    val source = (stock.original(before.operationId) as? RetainedOriginalV2.Streamed)?.evidence
                        ?: error("food.handoff.normalized_original_missing")
                    check(source.identity.scope == owner.identity.scope && source.identity.producer.operationId != value.operationId &&
                        (source.identity.assignmentId != value.assignmentId || source.identity.authorityEpoch != value.authorityEpoch ||
                            source.identity.sequence < value.sequence))
                    stock.requireCompleteOriginal(source)
                    normalized[before.operationId] = source
                    source
                }
                if (payment) requireFoodPaymentStreamedInput(original, before)
                else if (refund) requireFoodRefundStreamedInput(original, before)
                else requirePaidFoodStreamedInput(original, before)
                continue
            }
            val original = originals[before.operationId] ?: run {
                foodCapacity(originals.size + normalized.size < maximum)
                val source = if (before.stock == null) whole(before.operationId) else read(before.operationId)
                check(source.operationId != value.operationId &&
                    (source.assignmentId != value.assignmentId || source.authorityEpoch != value.authorityEpoch || source.sequence < value.sequence))
                sourceBytes += foodProducerReadBytes(source)
                foodCapacity(sourceBytes <= FoodReadLimits.PREDECESSOR_BYTES)
                originals[before.operationId] = source; source
            }
            if (before.stock == null && original.operationId !in full) whole(original.operationId)
            val row = original.aggregateWrites.single { it.key == before.key() }
            check(row.storageGeneration == before.revision && row.schema == before.schema && row.payload == before.bytes() &&
                row.payloadDigest == before.digest && sha256(row.payload.copyBytes()) == row.payloadDigest)
            if (before.stock != null) requireFoodStockInput(before, original)
        }
        direct += value.operationId
    }
    /** Actual retained ancestors/stock only, never today's slot/app or a recursive cache shortcut. */
    private suspend fun paymentWhole(value: RetainedCommandEvidence): List<LocalFoodRecipeSnapshotV1> {
        val lineage = foodPaymentRetainedLineage(value) { id -> read(id) }
        val result = LocalFoodPaymentProducer.requireComplete(value, lineage.start, lineage.originalRaw,
            lineage.resolutionRaw, lineage.originalUnknownApplication)
        com.ukkera.pos.inventory.store.LocalStockPaymentObservations.requireOriginalStock(stock,
            checkNotNull(StockReadEvidenceV1.read(lineage.start)), lineage.start)
        val proof = LocalFoodPaymentProducer.requireStart(lineage.start)
        for (row in proof.cut.portions) {
            val portion = row.body as FoodBodyV1.Portion
            val entry = proof.cut.collection.portions.single { it.portionId == row.id }
            proof.stock.issued[entry.reservationId]?.let { issued ->
                requireFoodFirstIssue(portion,
                    com.ukkera.pos.inventory.store.LocalStockPaymentObservations.requireIssuedOriginal(stock, issued))
            }
        }
        if (lineage.start.operationId != value.operationId) inputs(lineage.start)
        lineage.originalUnknownApplication?.let { inputs(it) }
        return result
    }

    /** Actual accepted cancellation/funding predecessors, not a fabricated finalized sale. */
    private suspend fun refundWhole(value: RetainedCommandEvidence) {
        val lineage = foodRefundRetainedLineage(value) { id -> read(id) }
        LocalFoodRefundProducer.requireComplete(value, lineage.start, lineage.originalRaw,
            lineage.resolutionRaw, lineage.originalUnknownApplication)
        inputs(lineage.start)
        lineage.originalUnknownApplication?.let { inputs(it) }
    }

    private suspend fun legacyOffer(initial: FoodEnvelopeV1, resolving: RetainedCommandEvidence) {
        check(initial == foodHandoffLegacyOriginInputV2(resolving) && initial.scope == owner.identity.scope && initial.kind == LocalFoodProfileV1.ORDER)
        val pending = checkNotNull((initial.body as FoodBodyV1.Order).handoff)
        val key = AggregateKey(initial.kind, initial.id)
        var value = initial
        var later = resolving
        val seen = mutableSetOf<OperationId>()
        while (true) {
            check(seen.add(value.operationId)) { "food.stock.legacy_origin_cycle" }
            val original = whole(value.operationId)
            check(original.command == LocalFoodProfileV1.COMMAND && !isFoodHandoffProducerV2(original) &&
                !isPaidFoodHandoffProducer(original) && original.operationId != later.operationId &&
                (original.assignmentId != later.assignmentId || original.authorityEpoch != later.authorityEpoch || original.sequence < later.sequence))
            val row = original.aggregateWrites.single { it.key == key }
            val payload = foodEnvelopeBytes(value)
            check(value.scope == owner.identity.scope && value.kind == key.kind && value.id == key.id && row.storageGeneration == value.revision &&
                row.schema == LocalFoodProfileV1.schema(key.kind) && row.payload == payload && row.payloadDigest == sha256(payload.copyBytes()) &&
                (value.body as FoodBodyV1.Order).let { it.handoff == pending && it.ownerId == pending.from })
            val request = LocalFoodCodecV1.decode(original.normalized)
            check(request is LocalFoodRequestV1.OfferHandoff || request is LocalFoodRequestV1.Station || request is LocalFoodRequestV1.DiscardStarted)
            inputs(original)
            if (request is LocalFoodRequestV1.OfferHandoff) {
                check(request.orderId == key.id && request.handoffId == pending.id && request.recipientId == pending.to &&
                    original.originalActorId == pending.from && original.recordedAt == pending.offeredAt)
                return
            }
            val before = foodEvent(original).beforeIds.map { foodBefore(original, it).envelope }.single { it.kind == key.kind && it.id == key.id }
            check(before.scope == owner.identity.scope && before.revision.next() == value.revision && (before.body as FoodBodyV1.Order).handoff == pending)
            value = before; later = original
        }
    }
}
