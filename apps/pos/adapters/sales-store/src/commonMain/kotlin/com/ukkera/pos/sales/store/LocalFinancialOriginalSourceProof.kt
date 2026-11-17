package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlin.coroutines.cancellation.CancellationException

/** Trusted typed catalog composition, analogous to the installed history producer. It
 * receives actual native originals only and must discharge the complete source proof.
 * There is no accepting default, handler call, authority result or caller reply port. */
public interface LocalFinancialOriginalCatalogProof {
    public suspend fun requireLegacyBusinessOriginal(program: BusinessOriginalProgram)
    public suspend fun originalPublicationContents(proof: NativeHistoryBusinessPublicationObservationProof): List<CompanyBusinessPublicationOutput>
    public suspend fun requireSupplierOriginal(scope: LocalFinancialOriginalProofScope, original: RetainedCommandEvidence)
    public suspend fun requireSupplierConfiguration(scope: LocalFinancialOriginalProofScope,
        sources: RuntimeHistoricalCashConfigurationSources)
}

/** Reusable source SERVICES only. Every lookup below owns a fresh bounded proof scope;
 * this service retains no original result, current row, grant, session or proof cache. */
public class LocalFinancialOriginalSourceServices(
    internal val stock: LocalStockRepository,
    internal val recipes: TransactionFoodStockSource?,
    internal val tenderPolicies: HistoricalSalesTenderPolicySourceV1,
    internal val catalog: LocalFinancialOriginalCatalogProof? = null,
) {
    public suspend fun <T> read(view: CommandReadView, original: RetainedCommandEvidence,
        use: suspend (CommandReadView) -> T): T {
        val existing = (view as? FinancialOriginalProofCarrier)?.financialOriginalProof
        if (existing != null) { existing.requireReentry(this, original); return use(view) }
        val scope = LocalFinancialOriginalProofScope(this, view, original)
        return scope.withRoot { use(scope.view) }
    }

    /** Qualify the native-selected CURRENT writer on its own genuine BEFORE graph.
     * It is never disguised as an ancestor of a different historical result. The same
     * request retains cumulative budgets; current disclosure authority is still separate. */
    internal suspend fun currentLegacyOriginal(view: CommandReadView, row: StoredAggregate,
        expectedCommand: CommandKey): RetainedCommandEvidence? = currentLegacyOriginal(view, row, setOf(expectedCommand))

    internal suspend fun currentLegacyOriginal(view: CommandReadView, row: StoredAggregate,
        expectedCommands: Set<CommandKey>): RetainedCommandEvidence? {
        val parent = (view as? FinancialOriginalProofCarrier)?.financialOriginalProof
        val borrowed = parent?.currentSourceBorrowed(this) ?: view
        return borrowed.withCurrentLegacyBusinessSources(row.key) { selected ->
            val actual = selected.row
            if (actual.key != row.key || actual.revision != row.revision || actual.schema != row.schema ||
                actual.payload != row.payload || actual.digest != row.digest) return@withCurrentLegacyBusinessSources null
            val original = selected.graph.root.evidence
            if (original.command !in expectedCommands) return@withCurrentLegacyBusinessSources null
            val subject = LocalFinancialOriginalProofScope(this, borrowed, original, parent?.sourceBudget)
            subject.withRoot {
                subject.charge(row.payload.size.toLong() + 512L)
                subject.requireCurrentSubject(selected.graph)
                original
            }
        }
    }
}

internal interface FinancialOriginalProofCarrier {
    val financialOriginalProof: LocalFinancialOriginalProofScope?
}

/** Request-local accounting only; it never retains a grant or a successful proof. */
internal interface OriginalSourceBudgetCarrier {
    val originalSourceBudget: FinancialOriginalSourceBudget? get() = null
}

internal class FinancialOriginalSourceBudget {
    // Each admitted subject tightens to its existing family envelope. Starting at
    // the largest installed profile must not reduce FOOD's pre-existing allowance.
    private var maximumBytes = FoodReadLimits.REQUEST_BYTES
    private var maximumOriginals = FoodReadLimits.REQUEST_PRODUCERS
    private var bytes = 0L
    private var calls = 0
    private var healthy = true
    private val originals = mutableMapOf<OperationId, PayloadDigest>()
    fun tighten(bytes: Long, originals: Int) {
        requireBudget(bytes >= 0 && originals > 0)
        maximumBytes = minOf(maximumBytes, bytes)
        maximumOriginals = minOf(maximumOriginals, originals)
        requireBudget(this.bytes <= maximumBytes && this.originals.size <= maximumOriginals)
    }
    fun charge(amount: Long) {
        requireBudget(amount >= 0 && amount <= maximumBytes - bytes && ++calls <= FoodReadLimits.REQUEST_CALLS)
        bytes += amount
    }
    fun original(operationId: OperationId, hash: PayloadDigest) {
        originals[operationId]?.let { requireBudget(it == hash); return }
        requireBudget(originals.size < maximumOriginals)
        originals[operationId] = hash
    }
    private fun requireBudget(condition: Boolean) {
        if (!healthy || !condition) {
            healthy = false
            error("financial.original_source_capacity_required")
        }
    }
}

/** One actual writer-loan-confined proof graph. Completed immutable originals may be
 * reused only here and are still charged on every request. This cannot authorize or
 * publish a result; current result disclosure remains the kernel's separate decision. */
public class LocalFinancialOriginalProofScope internal constructor(
    private val services: LocalFinancialOriginalSourceServices,
    private val borrowed: CommandReadView,
    private val root: RetainedCommandEvidence,
    inheritedBudget: FinancialOriginalSourceBudget? = null,
) {
    private val food = root.command in foodPaymentCommands || root.command in foodRefundCommands
    private val maximumBytes = if (food) FoodReadLimits.REQUEST_BYTES else 16_777_216L
    private val maximumOriginals = if (food) FoodReadLimits.REQUEST_PRODUCERS else 256
    internal val sourceBudget: FinancialOriginalSourceBudget =
        (inheritedBudget ?: (borrowed as? OriginalSourceBudgetCarrier)?.originalSourceBudget ?: FinancialOriginalSourceBudget())
            .also { it.tighten(maximumBytes, maximumOriginals) }
    private var open = true
    private val active = mutableListOf<Identity>()
    private val completed = mutableMapOf<OperationId, PayloadDigest>()
    private var legacyGraph: RuntimeLegacyBusinessSourceGraph? = null
    public val view: CommandReadView = FinancialOriginalProofView(borrowed, this, services)
    private val immutableFood = FoodReadView(view)
    public val stock: LocalStockRepository get() = services.stock

    init { check(root.scope == borrowed.scope && root.nativeOriginalProvenance == null &&
        semanticDigest(root.scope, root.originalActorId, root.command, root.normalized) == root.semanticDigest) }

    internal suspend fun <T> withRoot(use: suspend () -> T): T {
        check(active.isEmpty() && open)
        sourceBudget.original(root.operationId, root.journalHash)
        active += identity(root)
        try { charge(foodProducerReadBytes(root)); return use() }
        finally { active.clear(); completed.clear(); open = false }
    }

    internal fun requireReentry(services: LocalFinancialOriginalSourceServices, original: RetainedCommandEvidence) {
        check(open && this.services === services && active.lastOrNull() == identity(original))
    }

    internal fun charge(amount: Long) {
        check(open)
        sourceBudget.charge(amount)
    }

    internal fun currentSourceBorrowed(services: LocalFinancialOriginalSourceServices): CommandReadView {
        check(open && this.services === services)
        return borrowed
    }

    internal suspend fun requireCurrentSubject(graph: RuntimeLegacyBusinessSourceGraph) {
        check(open && active.singleOrNull() == identity(root) && legacyGraph == null)
        requireSameFinancialRpcOriginal(root, graph.root.evidence)
        legacyGraph = graph
        try { requireLegacy(root) } finally { legacyGraph = null }
    }

    /** Qualification is extra source work. Its failure cannot become a live handler's
     * Pending/capacity witness; callers must not translate it to a business refusal. */
    public suspend fun requireOriginal(original: RetainedOriginalV2) {
        try { requireSelectedOriginal(original) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: FinancialOriginalSourceQualificationUnavailable) { throw failure }
        catch (failure: Exception) { throw FinancialOriginalSourceQualificationUnavailable(failure) }
    }

    private suspend fun requireSelectedOriginal(original: RetainedOriginalV2) {
        check(open && active.isNotEmpty())
        val identity = identity(original)
        val parent = active.last()
        check(identity.scope == root.scope && identity.assignmentId == root.assignmentId && identity.authorityEpoch == root.authorityEpoch &&
            identity.operationId != parent.operationId && identity.sequence < parent.sequence)
        charge(when (original) {
            is RetainedOriginalV2.Legacy -> foodProducerReadBytes(original.evidence)
            is RetainedOriginalV2.Streamed -> original.evidence.normalized.size.toLong() + original.evidence.result.size + 4_096L
        })
        sourceBudget.original(identity.operationId, identity.journalHash)
        completed[identity.operationId]?.let { check(it == identity.journalHash); return }
        check(completed.size + active.size < maximumOriginals && active.none { it.operationId == identity.operationId }) {
            "financial.original_source_cycle_or_capacity"
        }
        active += identity
        try {
            when (original) {
                is RetainedOriginalV2.Streamed -> services.stock.requireRelationalProducer(view, original.evidence)
                is RetainedOriginalV2.Legacy -> requireLegacy(original.evidence)
            }
            completed[identity.operationId] = identity.journalHash
        } finally { check(active.removeAt(active.lastIndex) === identity) }
    }

    private suspend fun requireLegacy(original: RetainedCommandEvidence) {
        check(original.nativeOriginalProvenance == null && original.committed && original.code == "committed")
        val actual = view.retainedCommand(original.operationId) ?: error("financial.original_source_required")
        requireSameFinancialRpcOriginal(actual, original)
        when (original.command) {
            LocalSalesStoreProfile.CASH_COMMAND -> {
                val sources = view.cashConfigurationOriginalSources(original.operationId)
                    ?: error("financial.ordinary_cash_before_required")
                requireSameFinancialRpcOriginal(sources.evidence, original)
                requireFinancialCashAncestor(this, sources)
            }
            CommandKey("local.configuration.write", 1) -> {
                val sources = view.cashConfigurationOriginalSources(original.operationId)
                if (sources == null) requireLegacyBusinessOriginal(original)
                else {
                    requireSameFinancialRpcOriginal(sources.evidence, original)
                    check(sources.kind == RuntimeCashConfigurationSourceKind.CONFIGURE_SUPPLIER)
                    (services.catalog ?: error("financial.catalog_source_composition_required"))
                        .requireSupplierConfiguration(this, sources)
                }
            }
            LocalFinancialJournalProfileV3.COLLECTION_COMMAND, LocalFinancialJournalProfileV3.RETURN_COMMAND,
            LocalRetailSupervisorCodecV1.COMMAND -> RetailJournalOriginalProof(view, services.tenderPolicies).require(original)
            LocalFoodPaymentProfile.COMMAND, LocalFoodPaymentSupervisorCodec.COMMAND ->
                checkNotNull(foodPaymentRpcOriginalRequirements(FoodReadView(view), original,
                    services.recipes ?: error("financial.food_source_composition_required")))
            LocalFoodRefundProfile.COMMAND, LocalFoodRefundSupervisorCodec.COMMAND ->
                checkNotNull(foodRefundRpcOriginalRequirements(FoodReadView(view), original))
            LocalSupplierCashProfile.COMMAND, LocalSupplierCashSupervisorCodec.COMMAND ->
                (services.catalog ?: error("financial.catalog_source_composition_required")).requireSupplierOriginal(this, original)
            LocalFoodProfileV1.COMMAND, LocalFoodFinancialProfileV1.COMMAND -> {
                val actual = immutableFood.foodProducer(original.operationId)
                requireSameFinancialRpcOriginal(actual, original)
                immutableFood.requireFoodPredecessors(actual)
            }
            else -> requireLegacyBusinessOriginal(original)
        }
    }

    private suspend fun requireLegacyBusinessOriginal(original: RetainedCommandEvidence) {
        val catalog = services.catalog ?: error("financial.catalog_source_composition_required")
        suspend fun selected(graph: RuntimeLegacyBusinessSourceGraph) {
            val sources = graph.source(original.operationId) ?: error("financial.legacy_before_required")
            requireSameFinancialRpcOriginal(sources.evidence, original)
            val program = FinancialLegacyOriginalProgram(this, catalog, sources)
            catalog.requireLegacyBusinessOriginal(program)
            program.requireComplete()
        }
        val held = legacyGraph
        if (held != null) selected(held)
        else check(borrowed.withLegacyBusinessSources(root.operationId) { graph ->
            check(legacyGraph == null)
            legacyGraph = graph
            try { selected(graph); true } finally { legacyGraph = null }
        } == true) { "financial.legacy_native_source_graph_required" }
    }

    internal suspend fun aggregate(sources: RuntimeHistoricalFinancialSources, key: AggregateKey): StoredAggregate? =
        sources.aggregateSource(key)?.let { requireOriginal(it.original); it.row }

    internal suspend fun claim(sources: RuntimeHistoricalFinancialSources, key: ClaimKey): DomainId? {
        val source = sources.beforeClaimSources.singleOrNull { it.key == key }
        val value = sources.claim(key)
        source?.let { requireOriginal(it.original) }
        return value
    }

    internal suspend fun aggregate(sources: RuntimeHistoricalCashConfigurationSources, key: AggregateKey): RuntimeNativeLiteralAggregateSource? =
        sources.aggregateSource(key)?.also { requireOriginal(it.original) }

    internal suspend fun claim(sources: RuntimeHistoricalCashConfigurationSources, key: ClaimKey): DomainId? {
        val source = sources.beforeClaimSources.singleOrNull { it.key == key }
        val value = sources.claim(key)
        source?.let { requireOriginal(it.original) }
        return value
    }

    private data class Identity(val scope: BranchScope, val assignmentId: DomainId, val authorityEpoch: Revision,
        val operationId: OperationId, val sequence: JournalSequence, val journalHash: PayloadDigest)
    private fun identity(value: RetainedCommandEvidence): Identity = Identity(value.scope, value.assignmentId, value.authorityEpoch,
        value.operationId, value.sequence, value.journalHash)
    private fun identity(value: RetainedOriginalV2): Identity = when (value) {
        is RetainedOriginalV2.Legacy -> value.evidence.let { Identity(it.scope, it.assignmentId, it.authorityEpoch,
            it.operationId, it.sequence, it.journalHash) }
        is RetainedOriginalV2.Streamed -> value.evidence.identity.let { Identity(it.scope, it.assignmentId, it.authorityEpoch,
            it.producer.operationId, it.sequence, value.evidence.journalHash) }
    }
}

internal class FinancialOriginalSourceQualificationUnavailable(cause: Throwable) : RuntimeException(cause)

internal fun financialOriginalProof(view: CommandReadView): LocalFinancialOriginalProofScope =
    (view as? FinancialOriginalProofCarrier)?.financialOriginalProof ?: error("financial.original_source_composition_required")

/** A source-only cross-module call into THIS installed request scope. No source, proof
 * callback, authority or successful default can be supplied by the caller. */
public object LocalFinancialOriginalSourceAccess {
    public suspend fun require(view: CommandReadView, original: RetainedOriginalV2): Unit =
        financialOriginalProof(view).requireOriginal(original)
}

internal suspend fun financialOriginalRead(view: CommandReadView, context: CommandContext, normalized: StoredBytes,
    services: LocalFinancialOriginalSourceServices?, stock: LocalStockRepository?,
    recipes: TransactionFoodStockSource? = null,
    tenderPolicies: HistoricalSalesTenderPolicySourceV1 = commerceTenderPolicies(view),
    read: suspend (CommandReadView) -> List<AuthorizationRequirement>?,
): List<AuthorizationRequirement>? {
    val original = view.retainedCommand(context.operationId) ?: return null
    if (!financialRpcOriginalMatches(original, normalized, context) ||
        semanticDigest(context.scope, context.originalActorId, context.key, normalized) != context.semanticDigest) return null
    val actual = services ?: LocalFinancialOriginalSourceServices(stock ?: return null, recipes, tenderPolicies)
    return actual.read(view, original, read)
}

private class FinancialOriginalProofView(private val borrowed: CommandReadView,
    override val financialOriginalProof: LocalFinancialOriginalProofScope,
    private val services: LocalFinancialOriginalSourceServices,
) : CommandReadView by borrowed, CommerceStockView, FinancialOriginalProofCarrier {
    override val commerceStock: LocalStockRepository get() = services.stock
    override val commerceOriginalSources: LocalFinancialOriginalSourceServices get() = services
    override val originalSourceBudget: FinancialOriginalSourceBudget get() = financialOriginalProof.sourceBudget
    override val commerceTenderPolicies: HistoricalSalesTenderPolicySourceV1 get() = services.tenderPolicies
    override suspend fun stockRelationalAccess(): StockRelationalAccess = borrowed.requireStockRelational()
    override suspend fun recordCommerceOriginal(row: StoredAggregate, original: RetainedStreamedCommand) =
        commerceRecordOriginal(borrowed, row, original)
    override suspend fun observeRecoveryResources(resources: Set<RecoveryResourceKey>) =
        (borrowed as? LocalCommerceResourceCollector)?.observeRecoveryResources(resources) ?: Unit
    override suspend fun financialOriginalSources(operationId: OperationId): RuntimeHistoricalFinancialSources? =
        borrowed.financialOriginalSources(operationId).also { financialOriginalProof.charge(it?.retainedDataBytes ?: 512L) }
    override suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence? =
        borrowed.retainedCommand(operationId).also { financialOriginalProof.charge(it?.let(::foodProducerReadBytes) ?: 512L) }
    override suspend fun cashConfigurationOriginalSources(operationId: OperationId): RuntimeHistoricalCashConfigurationSources? =
        borrowed.cashConfigurationOriginalSources(operationId).also { financialOriginalProof.charge(it?.retainedDataBytes ?: 512L) }
}

/** Immutable business DATA selected from a genuine retained original, with no kernel,
 * current principal, serialized capability, prepare/evaluate or native action. */
internal class FinancialOriginalCoordinates(original: RetainedCommandEvidence) {
    val scope = original.scope
    val originalActorId = original.originalActorId
    val operationId = original.operationId
    val key = original.command
    val semanticDigest = original.semanticDigest
}
