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
import kotlinx.serialization.Serializable

internal const val COMMERCE_STOCK_LINK_KIND_V2 = "sales.stock.producer_link"
internal const val COMMERCE_STOCK_EFFECT_V2 = "sales.stock.row_effect"
internal fun commerceLinkKey(key: AggregateKey): AggregateKey = AggregateKey(COMMERCE_STOCK_LINK_KIND_V2,
    foodId("stock-commerce-source-v2", "${key.kind}/${key.id.value}"))
internal fun commerceLinkSchema(): String = "$COMMERCE_STOCK_LINK_KIND_V2/v2"
@Serializable internal data class CommerceRowLinkV2(val scope: BranchScope, val kind: String, val id: DomainId,
    val generation: Revision, val digest: PayloadDigest, val operationId: OperationId, val actorId: DomainId,
    val command: String, val version: Int, val semanticDigest: PayloadDigest)
@Serializable internal data class CommerceRowEffectV2(val kind: String, val id: DomainId,
    val beforeGeneration: Revision?, val beforeDigest: PayloadDigest?, val afterDigest: PayloadDigest)

/** Real view decoration only; an original is never converted to a fake current CommandReadView. */
internal interface CommerceStockView : StockRelationalReadView, LocalCommerceResourceCollector {
    val commerceStock: LocalStockRepository
    val commerceTenderPolicies: HistoricalSalesTenderPolicySourceV1
        get() = UnavailableHistoricalSalesTenderPolicySourceV1
    suspend fun recordCommerceOriginal(row: StoredAggregate, original: RetainedStreamedCommand)
    override suspend fun observeRecoveryResources(resources: Set<RecoveryResourceKey>)
}
internal class CommerceLoan(private val borrowed: CommandReadView, override val commerceStock: LocalStockRepository,
    private val sink: StreamedCommandSink? = null,
    override val commerceTenderPolicies: HistoricalSalesTenderPolicySourceV1 = commerceTenderPolicies(borrowed),
) : CommerceStockView {
    override val scope: BranchScope get() = borrowed.scope
    internal fun handoffBorrowedView(): CommandReadView = when (borrowed) {
        is CommerceLoan -> borrowed.handoffBorrowedView()
        is FoodReadView -> borrowed.handoffBorrowedView()
        else -> borrowed
    }
    override suspend fun stockRelationalAccess(): StockRelationalAccess = borrowed.requireStockRelational()
    override suspend fun aggregate(key: AggregateKey): StoredAggregate? {
        val row = borrowed.aggregate(key)
        if (sink != null && key.kind !in StockRelationalKindsV2.all) {
            // Food owns explicit original identities; its full reader records the exact producer
            // after validation, not an invented identity inferred from the current actor.
            val food = key.kind in LocalFoodProfileV1.kinds || key.kind == FoodQuotationStorageV1.RECIPE_QUOTATION ||
                key.kind in foodFinancialDocumentKinds
            if (row == null) sink.source(StreamedSourceObservationV2.CurrentAbsent(key))
            else if (!food && !normalizedCommerceRow(scope, row))
                sink.source(StreamedSourceObservationV2.Current(key, row.revision, row.schema, row.payload, row.digest))
        }
        return row
    }
    override suspend fun claim(key: ClaimKey): DomainId? = borrowed.claim(key).also {
        if (sink != null) sink.source(StreamedSourceObservationV2.Claim(key, it))
    }
    override suspend fun aggregateKeys(kind: String, after: DomainId?, limit: Int): AggregateKeyPage = borrowed.aggregateKeys(kind, after, limit)
    override suspend fun claimKeys(namespace: String, prefix: String, after: String?, limit: Int): ClaimKeyPage = borrowed.claimKeys(namespace, prefix, after, limit)
    override suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence? = borrowed.retainedCommand(operationId)
    override suspend fun foodCheckRejectedOriginalSources(operationId: OperationId): RuntimeHistoricalFoodCheckRejectedSources? =
        borrowed.foodCheckRejectedOriginalSources(operationId)
    override suspend fun foodPaymentAlreadyAppliedOriginalSources(operationId: OperationId): RuntimeHistoricalFoodPaymentAlreadyAppliedSources? =
        borrowed.foodPaymentAlreadyAppliedOriginalSources(operationId)
    override suspend fun foodPaymentValueMismatchOriginalSources(operationId: OperationId): RuntimeHistoricalFoodPaymentValueMismatchSources? =
        borrowed.foodPaymentValueMismatchOriginalSources(operationId)
    override suspend fun observeRecoveryResources(resources: Set<RecoveryResourceKey>) {
        for (resource in resources) sink?.resource(resource)
    }
    override suspend fun recordCommerceOriginal(row: StoredAggregate, original: RetainedStreamedCommand) {
        sink?.source(StreamedSourceObservationV2.Aggregate(row.key, row.revision, row.schema, row.payload, row.digest, original.identity.producer))
    }
    suspend fun recordLegacy(row: StoredAggregate, original: RetainedCommandEvidence) {
        sink?.source(StreamedSourceObservationV2.Aggregate(row.key, row.revision, row.schema, row.payload, row.digest,
            StreamedProducerIdentityV2(original.operationId, original.command, original.originalActorId, original.semanticDigest)))
    }
}
internal fun commerceLoan(view: CommandReadView, stock: LocalStockRepository,
    tenderPolicies: HistoricalSalesTenderPolicySourceV1 = commerceTenderPolicies(view)): CommandReadView =
    if (view is CommerceStockView && view.commerceStock === stock && view.commerceTenderPolicies === tenderPolicies) view
    else CommerceLoan(view, stock, commerceTenderPolicies = tenderPolicies)
internal fun commerceTenderPolicies(view: CommandReadView): HistoricalSalesTenderPolicySourceV1 =
    (view as? CommerceStockView)?.commerceTenderPolicies ?: UnavailableHistoricalSalesTenderPolicySourceV1
internal fun commerceRepository(view: CommandReadView): LocalStockRepository =
    (view as? CommerceStockView)?.commerceStock ?: error("sales.stock.same_loan_repository_required")
internal suspend fun commerceRecordLegacy(view: CommandReadView, row: StoredAggregate, original: RetainedCommandEvidence) {
    when (view) {
        is CommerceLoan -> view.recordLegacy(row, original)
        is FoodReadView -> view.recordLegacyStockSource(row, original)
    }
}
internal suspend fun commerceRecordOriginal(view: CommandReadView, row: StoredAggregate, original: RetainedStreamedCommand) {
    (view as? CommerceStockView)?.recordCommerceOriginal(row, original)
}

private fun normalizedCommerceRow(scope: BranchScope, row: StoredAggregate): Boolean = when (row.key.kind) {
    LocalSalesStoreProfile.ORDER_KIND -> SalesSnapshotCodec.decodeOrder(row.text(),
        SalesSnapshotBinding(SalesSnapshotKind.ORDER, scope, row.key.id, row.domainRevision())).stockRequirements.normalized
    LocalSalesStoreProfile.COLLECTION_KIND -> SalesSnapshotCodec.decodeHistoricalCollection(row.text(),
        SalesSnapshotBinding(SalesSnapshotKind.COLLECTION, scope, row.key.id, row.domainRevision())).stockRequirements.normalized
    LocalSalesReturnPolicyCaptureCodecV2.KIND -> row.schema == LocalSalesReturnPolicyCaptureCodecV2.SCHEMA
    else -> false
}

/** Attach the installed repository to ordinary production readers without changing their auth. */
internal class CommerceReadHandler(private val delegate: MasterReadHandler, private val stock: LocalStockRepository,
    private val tenderPolicies: HistoricalSalesTenderPolicySourceV1 = UnavailableHistoricalSalesTenderPolicySourceV1) : MasterReadHandler {
    override val key: ReadKey get() = delegate.key
    override fun normalize(input: StoredBytes): StoredBytes = delegate.normalize(input)
    override fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean =
        delegate.supportsRpcWorkflow(profile, normalized)
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead =
        delegate.prepare(normalized, commerceLoan(view, stock, tenderPolicies), context)
}

/** Attach the SAME installed repository to real return/CASE/counter-receipt command reads. The original
 * handler retains every key, gate, result, normalization and authorization requirement. */
internal class CommerceCommandHandler(private val delegate: MasterCommandHandler,
    private val stock: LocalStockRepository,
    private val tenderPolicies: HistoricalSalesTenderPolicySourceV1 = UnavailableHistoricalSalesTenderPolicySourceV1) :
    MasterCommandHandler by delegate, LocalMasterRpcRetainedResultSourceAccessProvider {
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation =
        delegate.prepare(normalized, commerceLoan(view, stock, tenderPolicies), context)

    /** Only the installed counter-receipt provider gets this same repository/source loan. */
    override suspend fun rpcRetainedResultRequirements(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes,
        view: CommandReadView, context: CommandContext): List<AuthorizationRequirement>? {
        if (profile != LocalMasterRpcWorkflowProfile.CUSTOMER_RECEIPTS || key != LocalRetailCustomerReceiptCodec.COMMAND) return null
        val provider = delegate as? LocalMasterRpcRetainedResultSourceAccessProvider ?: return null
        return provider.rpcRetainedResultRequirements(profile, normalized, commerceLoan(view, stock, tenderPolicies), context)
    }
}

private class CommerceCurrentSource(val row: StoredAggregate, val linkRow: StoredAggregate,
    val producer: StreamedProducerIdentityV2)

/** Simultaneous finite current roots on this actual loan. Each literal link is checked
 * before roots with the SAME complete producer identity share its actual verification.
 * No successful qualification survives this call or substitutes for a selected write. */
internal suspend fun requireCommerceCurrent(view: CommandReadView, vararg rows: StoredAggregate) {
    check(rows.isNotEmpty() && rows.size <= 2 && rows.map { it.key }.distinct().size == rows.size)
    val selected = rows.map { row ->
        val linkRow = view.aggregate(commerceLinkKey(row.key)) ?: error("sales.stock.current_source_missing")
        check(linkRow.schema == commerceLinkSchema() && sha256(linkRow.payload.copyBytes()) == linkRow.digest)
        val link = localSalesDecode(linkRow.payload, CommerceRowLinkV2.serializer())
        check(link.scope == view.scope && link.kind == row.key.kind && link.id == row.key.id &&
            link.generation == row.revision && link.digest == row.digest)
        CommerceCurrentSource(row, linkRow, StreamedProducerIdentityV2(link.operationId,
            CommandKey(link.command, link.version), link.actorId, link.semanticDigest))
    }
    for ((identity, sources) in selected.groupBy { it.producer }) {
        when (val original = view.requireStockRelational().originals.read(identity.operationId)
            ?: error("sales.stock.original_missing")) {
            is RetainedOriginalV2.Streamed -> {
                val value = original.evidence
                check(value.identity.producer == identity)
                commerceRepository(view).requireRelationalProducer(view, value)
                requireCommerceWrite(value, *sources.flatMap { listOf(it.row, it.linkRow) }.toTypedArray())
                for (source in sources) commerceRecordOriginal(view, source.row, value)
            }
            is RetainedOriginalV2.Legacy -> {
                val value = original.evidence
                check(sources.all { it.row.key.kind == LocalSalesStoreProfile.COLLECTION_KIND } &&
                    value.scope == view.scope && value.operationId == identity.operationId &&
                    value.command == identity.command && value.originalActorId == identity.originalActorId &&
                    value.semanticDigest == identity.semanticDigest)
                requireNormalizedPaymentOrigin(view, value)
                for (source in sources) {
                    requireRetailRetainedRowV3(value, source.row)
                    requireRetailRetainedRowV3(value, source.linkRow)
                    commerceRecordLegacy(view, source.row, value)
                }
            }
        }
    }
}
/** Match the complete finite selected row set during ONE actual original traversal.
 * Normal return still requires every selected write exactly once. No observed row,
 * authenticated stream or successful producer qualification is cached for another read. */
internal suspend fun requireCommerceWrite(original: RetainedStreamedCommand, vararg rows: StoredAggregate) {
    check(rows.isNotEmpty() && rows.size <= 4 && rows.map { it.key }.distinct().size == rows.size)
    val found = BooleanArray(rows.size)
    original.frames { frame ->
        val write = (frame as? RetainedCommandFrameV2.Write)?.value ?: return@frames
        val index = rows.indexOfFirst { it.key == write.key }
        if (index >= 0) {
            val row = rows[index]
            check(!found[index] && write.storageGeneration == row.revision && write.schema == row.schema &&
                write.payload == row.payload && write.payloadDigest == row.digest)
            found[index] = true
        }
    }
    check(found.all { it }) { "sales.stock.current_original_mismatch" }
}

/** Finite commerce effects only. Physical stock rows/claims never enter these collections. */
internal class CommerceWrites(private val view: CommandReadView, private val context: CommandContext,
    private val sink: StreamedCommandSink) {
    private val keys = mutableSetOf<AggregateKey>()
    val revisions: MutableList<LocalSalesRevision> = mutableListOf()
    suspend fun put(change: AggregateChange, before: StoredAggregate?, link: Boolean = false) {
        check(change.key.kind !in StockRelationalKindsV2.all && keys.add(change.key) && change.expectedRevision == before?.revision)
        check(before == null || before.key == change.key)
        sink.change(change)
        sink.effect(StreamedEffectV2(COMMERCE_STOCK_EFFECT_V2, foodId("commerce-row-effect-v2", "${change.key.kind}/${change.key.id.value}"),
            localSalesEncode(CommerceRowEffectV2(change.key.kind, change.key.id, before?.revision, before?.digest,
                sha256(change.payload.copyBytes())), CommerceRowEffectV2.serializer())))
        if (link) {
            val key = commerceLinkKey(change.key)
            val old = view.aggregate(key)
            if (before != null) {
                check(old != null)
                val previous = localSalesDecode(old.payload, CommerceRowLinkV2.serializer())
                check(previous.scope == context.scope && previous.kind == change.key.kind && previous.id == change.key.id &&
                    previous.generation == before.revision && previous.digest == before.digest)
            } else check(old == null)
            val value = CommerceRowLinkV2(context.scope, change.key.kind, change.key.id, before?.revision?.next() ?: Revision(1),
                sha256(change.payload.copyBytes()), context.operationId, context.originalActorId, context.key.name, context.key.version, context.semanticDigest)
            put(AggregateChange(key, old?.revision, commerceLinkSchema(), localSalesEncode(value, CommerceRowLinkV2.serializer())), old)
        }
    }
    suspend fun claim(change: ClaimChange) { sink.claim(change) }
    suspend fun jobs(jobs: List<JobIntent>) { check(jobs.size <= 32); for (job in jobs) sink.job(job) }
    suspend fun resources(resources: Set<RecoveryResourceKey>) { for (resource in resources) sink.resource(resource) }
}

/** Production literal producer accounting. No historical grants or fake retained evidence. */
internal class CommerceProof(val original: RetainedStreamedCommand) {
    val sources = LocalCommerceOriginalSources(original)
    val writes = linkedMapOf<AggregateKey, RetainedAggregateWrite>()
    private val used = mutableSetOf<AggregateKey>()
    private val claims = mutableListOf<RetainedClaimChange>()
    private val jobs = mutableListOf<RetainedJobIntent>()
    private val effects = linkedMapOf<AggregateKey, CommerceRowEffectV2>()
    private var bytes = 0L
    suspend fun load() {
        original.frames { frame -> when (frame) {
            is RetainedCommandFrameV2.Write -> if (frame.value.key.kind !in StockRelationalKindsV2.all) {
                // Financial DOMAIN frames are finite4MiB each, stock allocation cardinality is not.
                bytes += frame.value.payload.size
                check(bytes <= 32L * 1_048_576 && writes.put(frame.value.key, frame.value) == null)
            }
            is RetainedCommandFrameV2.Claim -> if (frame.value.key.namespace !in LocalStockStoreProfile.inventoryClaimNamespaces) claims += frame.value
            is RetainedCommandFrameV2.Job -> { check(frame.ordinal == jobs.size.toLong() + 1); jobs += frame.value; check(jobs.size <= 32) }
            is RetainedCommandFrameV2.Effect -> if (frame.value.kind != LocalStockRelationalProfileV2.EFFECT_KIND && frame.value.kind != "local.stock.v2.action") {
                check(frame.value.kind == COMMERCE_STOCK_EFFECT_V2)
                val value = localSalesDecode(frame.value.payload, CommerceRowEffectV2.serializer())
                val key = AggregateKey(value.kind, value.id)
                check(frame.value.id == foodId("commerce-row-effect-v2", "${key.kind}/${key.id.value}") && effects.put(key, value) == null)
            }
            else -> Unit
        } }
        check(writes.size == effects.size && writes.keys == effects.keys)
    }
    fun write(key: AggregateKey): RetainedAggregateWrite = writes[key] ?: error("sales.stock.required_effect_missing")
    suspend fun account(key: AggregateKey, payload: StoredBytes, schema: String, before: LocalCommerceOriginalRow?, linked: Boolean = false) {
        val row = write(key)
        check(used.add(key) && row.schema == schema && row.payload == payload && row.payloadDigest == sha256(payload.copyBytes()) &&
            row.storageGeneration == (before?.generation?.next() ?: Revision(1)))
        check(effects.getValue(key) == CommerceRowEffectV2(key.kind, key.id, before?.generation, before?.digest, row.payloadDigest))
        if (linked) {
            val linkKey = commerceLinkKey(key)
            val previous = sources.aggregate(linkKey)
            if (before == null) check(previous == null) else {
                val value = localSalesDecode(checkNotNull(previous).payload, CommerceRowLinkV2.serializer())
                check(value.kind == key.kind && value.id == key.id && value.scope == original.identity.scope &&
                    value.generation == before.generation && value.digest == before.digest)
            }
            val producer = original.identity.producer
            val expected = CommerceRowLinkV2(original.identity.scope, key.kind, key.id, row.storageGeneration,
                row.payloadDigest, producer.operationId, producer.originalActorId, producer.command.name, producer.command.version, producer.semanticDigest)
            account(linkKey, localSalesEncode(expected, CommerceRowLinkV2.serializer()), commerceLinkSchema(), previous)
        }
    }
    /**
     * Exact complementary source census. The stock owner emits unique ACTUAL source frames in
     * retained section order after its forward/reverse proof. Hash chains compare the two full
     * ordered streams, so physical claim cardinality never becomes a List/Set/cap128. A prefix,
     * a namespace allowlist, or an unexplained absent authority is not accepted as a source.
     */
    suspend fun requireSourceComplement(stock: LocalStockRelationalInspection) {
        val requiredAuthorities = CommerceSourceCensus(); val actualAuthorities = CommerceSourceCensus()
        val requiredClaims = CommerceSourceCensus(); val actualClaims = CommerceSourceCensus()
        stock.stockAuthoritySources { source ->
            if (source.key !in sources.consumed) requiredAuthorities.current(source)
        }
        stock.stockClaimSources { source ->
            if (source.key !in sources.consumedClaims) requiredClaims.claim(source)
        }
        original.frames { frame -> when (val source = (frame as? RetainedCommandFrameV2.Source)?.value) {
            is StreamedSourceObservationV2.Current -> {
                check(source.key.kind !in StockRelationalKindsV2.all)
                if (source.key !in sources.consumed) actualAuthorities.current(source)
            }
            is StreamedSourceObservationV2.CurrentAbsent -> check(source.key in sources.consumed) {
                "sales.stock.unexplained_absent_source"
            }
            is StreamedSourceObservationV2.Aggregate -> if (source.key.kind !in StockRelationalKindsV2.all)
                check(source.key in sources.consumed) { "sales.stock.unexplained_original_source" }
            is StreamedSourceObservationV2.Claim -> if (source.key !in sources.consumedClaims) actualClaims.claim(source)
            is StreamedSourceObservationV2.StockRange, is StreamedSourceObservationV2.StockMember -> Unit
            null -> Unit
            else -> error("sales.stock.unrequested_source_profile") // Includes future initialization-only census frames.
        } }
        check(requiredAuthorities.same(actualAuthorities) && requiredClaims.same(actualClaims)) {
            "sales.stock.unexplained_current_or_claim_source"
        }
    }
    fun finish(result: StoredBytes, requiredClaims: List<ClaimChange>, requiredJobs: List<JobIntent> = emptyList()) {
        check(used == writes.keys && result == original.result && sha256(result.copyBytes()) == original.identity.resultHash)
        val required = requiredClaims.associateBy { it.key }
        check(required.size == requiredClaims.size && claims.size == required.size && claims.map { it.key }.toSet() == required.keys && claims.all { claim ->
            val expected = required.getValue(claim.key)
            claim.ownerId == expected.ownerId && claim.action == (if (expected is ClaimChange.Acquire) RetainedClaimAction.ACQUIRE else RetainedClaimAction.RELEASE)
        })
        check(jobs.size == requiredJobs.size && jobs.zip(requiredJobs).all { (actual, required) -> actual.kind == required.kind && actual.body == required.body })
    }
}

@Serializable private data class CommerceSourceCensusEntryV2(val tag: String, val kind: String, val id: String,
    val generation: Revision? = null, val schema: String? = null, val digest: PayloadDigest? = null, val ownerId: DomainId? = null)
private class CommerceSourceCensus {
    private var count = 0L
    private var digest = sha256(byteArrayOf())
    private fun append(value: CommerceSourceCensusEntryV2) {
        check(count < Long.MAX_VALUE); count++
        val entry = localSalesEncode(value, CommerceSourceCensusEntryV2.serializer(), 16_384)
        digest = sha256(digest.value.encodeToByteArray() + entry.copyBytes())
    }
    fun current(source: StreamedSourceObservationV2.Current) = append(CommerceSourceCensusEntryV2("CURRENT",
        source.key.kind, source.key.id.value, source.generation, source.schema, source.payloadHash))
    fun claim(source: StreamedSourceObservationV2.Claim) = append(CommerceSourceCensusEntryV2("CLAIM",
        source.key.namespace, source.key.key, ownerId = source.ownerId))
    fun same(other: CommerceSourceCensus): Boolean = count == other.count && digest == other.digest
}

/** Exact complete normalized order/collection producer, installed into the SAME repository. */
public class LocalCollectionStockProducer(private val quoteSource: TransactionSalesStockQuoteSource,
    private val tenderPolicies: HistoricalSalesTenderPolicySourceV1 = UnavailableHistoricalSalesTenderPolicySourceV1) : LocalStockRelationalCompositeVerifier {
    override val commands: Set<CommandKey> = setOf(StockStreamingCommandsV2.COLLECTION)
    override suspend fun requireComplete(original: RetainedStreamedCommand, stock: LocalStockRelationalInspection) {
        check(original.identity.producer.command in commands && LocalCollectionStockCodecV2.normalize(original.normalized) == original.normalized)
        val proof = CommerceProof(original); proof.load()
        when (val request = LocalCollectionStockCodecV2.decode(original.normalized)) {
            is LocalCollectionStockRequestV2.CreateOrder -> requireCreatedCollectionOrder(proof, stock, request, quoteSource)
            is LocalCollectionStockRequestV2.ReplaceUnlockedOrder -> requireReplacedCollectionOrder(proof, stock, request, quoteSource)
            is LocalCollectionStockRequestV2.OfferHandoff -> requireOfferedCollectionOrder(proof, stock, request)
            is LocalCollectionStockRequestV2.CancelHandoff -> requireCancelledCollectionOrder(proof, stock, request)
            is LocalCollectionStockRequestV2.AcceptHandoff -> requireAcceptedCollectionOrder(proof, stock, request)
            // The installed native producer separately requires original recipient-before
            // authority; live sources originate in the protected current writer transaction.
            is LocalCollectionStockRequestV2.ReassignOrder -> requireReassignedCollectionOrderBusiness(proof, stock, request)
            is LocalCollectionStockRequestV2.Begin -> requireBegunCollection(proof, stock, request, quoteSource)
            is LocalCollectionStockRequestV2.Finalize -> requireFinalizedCollection(proof, stock, request)
        }
    }
    override suspend fun requireOriginalSource(original: RetainedOriginalV2, observation: StreamedSourceObservationV2.Aggregate) {
        check(observation.key.kind in setOf(LocalSalesStoreProfile.ORDER_KIND, LocalSalesStoreProfile.COLLECTION_KIND,
            LocalSalesStoreProfile.SALE_KIND, LocalSalesStoreProfile.ENTITLEMENT_KIND) ||
            observation.key.kind == LocalSalesReturnPolicyCaptureCodecV2.KIND && observation.schema == LocalSalesReturnPolicyCaptureCodecV2.SCHEMA)
        // The owner already proves the whole prior streamed producer, real older identity and
        // exact literal source write. This route never imports a legacy food/stock certificate.
        check(original is RetainedOriginalV2.Streamed && original.evidence.identity.producer.command in commands)
    }
    override suspend fun requireOriginalSource(view: CommandReadView, original: RetainedOriginalV2,
        observation: StreamedSourceObservationV2.Aggregate) {
        if (original is RetainedOriginalV2.Legacy) {
            check(observation.key.kind == LocalSalesStoreProfile.COLLECTION_KIND)
            requireNormalizedPaymentOrigin(commerceLoan(view, commerceRepository(view), tenderPolicies), original.evidence)
        } else requireOriginalSource(original, observation)
    }
}

internal suspend fun requireNoStockActions(stock: LocalStockRelationalInspection,
    reserve: Boolean = false, issue: Boolean = false, release: Boolean = false, start: Boolean = false, waste: Boolean = false,
    restore: Boolean = false, retailReturn: Boolean = false) {
    if (!reserve) stock.reservationIntents { error("sales.stock.unrequested_reservation") }
    if (!issue) stock.issueIntents { error("sales.stock.unrequested_issue") }
    if (!release) stock.releaseIntents { error("sales.stock.unrequested_release") }
    if (!start) stock.kitchenStartIntents { error("sales.stock.unrequested_start") }
    if (!waste) stock.wasteIntents { error("sales.stock.unrequested_waste") }
    if (!restore) stock.restorationIntents { error("sales.stock.unrequested_restoration") }
    if (!retailReturn) stock.retailReturnRestorationIntents { error("sales.stock.unrequested_retail_return") }
}
internal suspend fun requireRetailAllocations(lines: List<RetailStockDemandV2>, header: InventoryReservationHeader,
    each: suspend (suspend (InventoryAllocationRecord) -> Unit) -> Unit) {
    val check = RetailAllocationCheck(lines, header.id)
    each { check.observe(it) }
    check.finish(header)
}

/** Bounded logical demand totals only; no physical allocation rows or source proofs are cached. */
internal class RetailAllocationCheck(private val lines: List<RetailStockDemandV2>, private val reservationId: DomainId) {
    private val byLine = lines.associateBy { it.portionId }
    private val totals = lines.associate { it.portionId to Quantity(it.quantity.unitId, 0, it.quantity.scale) }.toMutableMap()
    private var count = 0L

    fun observe(member: InventoryAllocationRecord) {
        val a = member.allocation; val demand = byLine[a.lineId] ?: error("sales.stock.unrequested_line")
        check(member.reservationId == reservationId && a.position.pool.skuId == demand.skuId && a.position.pool.warehouseId == demand.warehouseId &&
            a.position.pool.ownership == StockOwnership.CompanyOwned && a.position.custody == StockCustody.RELEASED &&
            a.position.serialId == null && a.quantity.scaled > 0)
        totals[a.lineId] = totals.getValue(a.lineId) + a.quantity; check(count < Long.MAX_VALUE); count++
    }

    fun finish(header: InventoryReservationHeader) {
        check(header.id == reservationId && count == header.allocationCount && lines.all { totals.getValue(it.portionId) == it.quantity })
    }
}


/** Logical quote256 totals; all physical issue slices, original value and restoration are read. */
internal suspend fun requireRetailIssueSlices(lines: List<RetailStockDemandV2>, issue: LocalStockIssueHeader,
    each: suspend (suspend (InventoryIssueSlice) -> Unit) -> Unit) {
    val byLine = lines.associateBy { it.portionId }
    check(byLine.size == lines.size && issue.purpose == ValueIssuePurpose.RETAIL && issue.recipeSnapshotId == null &&
        issue.requiredStationIds.isEmpty() && issue.startedStationIds.isEmpty() && issue.disposition == null)
    val quantities = lines.associate { it.portionId to Quantity(it.quantity.unitId, 0, it.quantity.scale) }.toMutableMap()
    var value = Money(issue.originalValue.currency, 0); var count = 0L
    each { slice ->
        val a = slice.allocation; val demand = byLine[a.lineId] ?: error("sales.stock.unrequested_issue_line")
        check(slice.issueId == issue.id && a.position.pool.skuId == demand.skuId && a.position.pool.warehouseId == demand.warehouseId &&
            a.position.pool.ownership == StockOwnership.CompanyOwned && a.position.custody == StockCustody.RELEASED &&
            a.position.serialId == null && a.quantity.scaled > 0 && slice.restoredQuantity.scaled == 0L &&
            slice.originalValue.currency == issue.originalValue.currency && slice.originalValue.minor >= 0)
        quantities[a.lineId] = quantities.getValue(a.lineId) + a.quantity
        value += slice.originalValue; check(count < Long.MAX_VALUE); count++
    }
    check(value == issue.originalValue && count == issue.allocationCount && lines.all { quantities.getValue(it.portionId) == it.quantity })
}

internal fun commerceRowResource(key: AggregateKey): RecoveryResourceKey = when {
    key.kind == CashNonSaleStoreProfile.SESSION_KIND || key.kind == LocalSalesStoreProfile.DRAWER_KIND ||
        key.kind == LocalSalesStoreProfile.CUSTODIAN_KIND || key.kind == LocalSalesStoreProfile.SAFE_KIND -> cashResource(key.id)
    key.kind == LocalSalesStoreProfile.ENTITLEMENT_KIND || key.kind == LocalFoodFinancialProfileV1.ENTITLEMENT -> entitlementResource(key.id)
    key.kind in LocalFoodProfileV1.kinds || key.kind == FoodQuotationStorageV1.RECIPE_QUOTATION ||
        key.kind in foodFinancialDocumentKinds || key.kind == LocalFoodFinancialProfileV1.FRAME ||
        key.kind in setOf(COMMERCE_STOCK_LINK_KIND_V2, LocalSalesStoreProfile.ORDER_KIND, LocalSalesStoreProfile.ORDER_CONTEXT_KIND,
            LocalSalesStoreProfile.COLLECTION_KIND, LocalSalesStoreProfile.PRICE_SOURCES_KIND, LocalSalesStoreProfile.WORK_KIND,
            LocalSalesStoreProfile.SALE_KIND, LocalSalesStoreProfile.ORDER_REASSIGNMENT_KIND,
            LocalSalesReturnPolicyCaptureCodecV2.KIND) -> orderResource(key.id)
    else -> RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, key.id)
}
internal suspend fun emitCommerceRowResources(sink: StreamedCommandSink) {
    for (section in listOf(StreamedSectionV2.SOURCES, StreamedSectionV2.WRITES)) sink.frames(section) { frame ->
        val key = when (frame) {
            is RetainedCommandFrameV2.Write -> frame.value.key
            is RetainedCommandFrameV2.Source -> when (val value = frame.value) {
                is StreamedSourceObservationV2.Current -> value.key
                is StreamedSourceObservationV2.CurrentAbsent -> value.key
                is StreamedSourceObservationV2.Aggregate -> value.key
                else -> null
            }
            else -> null
        }
        if (key != null && key.kind !in StockRelationalKindsV2.all) {
            sink.resource(commerceRowResource(key))
            for (resource in commerceFrameReturnPolicyResources(frame)) sink.resource(resource)
            val source = (frame as? RetainedCommandFrameV2.Source)?.value as? StreamedSourceObservationV2.Aggregate
            source?.let { sink.resource(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it.producer.originalActorId)) }
        }
    }
}
internal suspend fun commerceRowResources(original: RetainedStreamedCommand): Set<RecoveryResourceKey> = buildSet {
    // buildSet's lambda is inline; each source is metadata only, never an accumulated stock cut.
    original.frames { frame ->
        val key = when (frame) {
            is RetainedCommandFrameV2.Write -> frame.value.key
            is RetainedCommandFrameV2.Source -> when (val value = frame.value) {
                is StreamedSourceObservationV2.Current -> value.key
                is StreamedSourceObservationV2.CurrentAbsent -> value.key
                is StreamedSourceObservationV2.Aggregate -> value.key
                else -> null
            }
            else -> null
        }
        if (key != null && key.kind !in StockRelationalKindsV2.all) {
            add(commerceRowResource(key))
            addAll(commerceFrameReturnPolicyResources(frame))
            val source = (frame as? RetainedCommandFrameV2.Source)?.value as? StreamedSourceObservationV2.Aggregate
            source?.let { add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it.producer.originalActorId)) }
        }
    }
}
private fun commerceFrameReturnPolicyResources(frame: RetainedCommandFrameV2): Set<RecoveryResourceKey> {
    val value = when (frame) {
        is RetainedCommandFrameV2.Write -> Triple(frame.value.key, frame.value.schema, frame.value.payload)
        is RetainedCommandFrameV2.Source -> when (val source = frame.value) {
            is StreamedSourceObservationV2.Aggregate -> Triple(source.key, source.schema, source.payload)
            is StreamedSourceObservationV2.Current -> Triple(source.key, source.schema, source.payload)
            else -> null
        }
        else -> null
    } ?: return emptySet()
    return if (value.first.kind == LocalSalesReturnPolicyCaptureCodecV2.KIND && value.second == LocalSalesReturnPolicyCaptureCodecV2.SCHEMA)
        returnPolicyCaptureResources(LocalSalesReturnPolicyCaptureCodecV2.decode(value.third)) else emptySet()
}
private fun originalOrder(scope: BranchScope, row: LocalCommerceOriginalRow): OwnedOrder =
    SalesSnapshotCodec.decodeOrder(row.payload.copyBytes().decodeToString(throwOnInvalidSequence = true),
        SalesSnapshotBinding(SalesSnapshotKind.ORDER, scope, row.key.id, Revision(row.generation.value - 1)))
private fun postedOrder(proof: CommerceProof, id: DomainId): OwnedOrder {
    val row = proof.write(LocalSalesStoreProfile.key(LocalSalesStoreProfile.ORDER_KIND, id))
    return originalOrder(proof.sources.scope, LocalCommerceOriginalRow(row.key, row.storageGeneration, row.schema, row.payload, row.payloadDigest))
}
private fun originalCollection(scope: BranchScope, row: LocalCommerceOriginalRow): CollectionLock =
    SalesSnapshotCodec.decodeHistoricalCollection(row.payload.copyBytes().decodeToString(throwOnInvalidSequence = true),
        SalesSnapshotBinding(SalesSnapshotKind.COLLECTION, scope, row.key.id, Revision(row.generation.value - 1)))
private fun postedCollection(proof: CommerceProof, id: DomainId): CollectionLock {
    val row = proof.write(LocalSalesStoreProfile.key(LocalSalesStoreProfile.COLLECTION_KIND, id))
    return originalCollection(proof.sources.scope, LocalCommerceOriginalRow(row.key, row.storageGeneration, row.schema, row.payload, row.payloadDigest))
}
internal fun originalBytes(text: String): StoredBytes = StoredBytes(text.encodeToByteArray(throwOnInvalidSequence = true))
private suspend fun originalWork(sources: LocalCommerceOriginalSources, id: DomainId): Pair<LocalCommerceOriginalRow, LocalWorkState> {
    val row = checkNotNull(sources.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.WORK_KIND, id)))
    val work = localSalesDecode(row.payload, LocalWorkState.serializer())
    check(row.schema == LocalSalesStoreProfile.schema(row.key.kind) && work.scope.value() == sources.scope && work.id == id &&
        work.revision.next() == row.generation && sources.claim(configuredClaim(LocalSalesStoreProfile.CUSTODIAN_KIND, id)) == id &&
        listOf(work.orderIds, work.returnIds, work.pendingClaimIds).all {
            it.size <= LocalSalesStoreProfile.MAX_OPEN_WORK && it.distinct().size == it.size })
    return row to work
}
private suspend fun originalCustodian(sources: LocalCommerceOriginalSources, id: DomainId): LocalCustodianState {
    val row = checkNotNull(sources.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.CUSTODIAN_KIND, id)))
    val value = localSalesDecode(row.payload, LocalCustodianState.serializer())
    check(row.schema == LocalSalesStoreProfile.schema(row.key.kind) && value.scope.value() == sources.scope &&
        value.id == id && value.revision.next() == row.generation &&
        sources.claim(configuredClaim(LocalSalesStoreProfile.CUSTODIAN_KIND, id)) == id &&
        sources.claim(LocalSalesStoreProfile.activeCustodian(id)) == value.activeSessionId)
    return value
}
private suspend fun originalAcceptsNewWork(sources: LocalCommerceOriginalSources, id: DomainId) {
    originalCustodian(sources, id).activeSessionId?.let {
        check(originalCash(sources, it).session.status == CashSessionStatus.OPEN)
    }
}
internal data class OriginalCash(val session: CashSession, val drawer: LocalDrawerState, val custodian: LocalCustodianState,
    val work: LocalWorkState, val resources: Set<RecoveryResourceKey>)
internal suspend fun originalCash(sources: LocalCommerceOriginalSources, id: DomainId): OriginalCash {
    val row = checkNotNull(sources.aggregate(CashNonSaleStoreProfile.sessionKey(id)))
    check(row.schema == CashNonSaleStoreProfile.SESSION_SCHEMA)
    val session = SalesSnapshotCodec.decodeCashSession(row.payload.copyBytes().decodeToString(throwOnInvalidSequence = true),
        SalesSnapshotBinding(SalesSnapshotKind.CASH_SESSION, sources.scope, id, Revision(row.generation.value - 1)))
    check(sources.claim(LocalSalesStoreProfile.unique("cash_session", id)) == id &&
        sources.claim(LocalSalesStoreProfile.unique("opening_source", session.openingSourceId)) == id)
    val drawerRow = checkNotNull(sources.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.DRAWER_KIND, session.drawerId)))
    val drawer = localSalesDecode(drawerRow.payload, LocalDrawerState.serializer())
    val custodianRow = checkNotNull(sources.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.CUSTODIAN_KIND, session.custodianId)))
    val custodian = localSalesDecode(custodianRow.payload, LocalCustodianState.serializer())
    val work = originalWork(sources, session.custodianId).second
    check(drawerRow.schema == LocalSalesStoreProfile.schema(drawerRow.key.kind) && custodianRow.schema == LocalSalesStoreProfile.schema(custodianRow.key.kind) &&
        drawer.scope.value() == sources.scope && custodian.scope.value() == sources.scope && drawer.id == session.drawerId && custodian.id == session.custodianId &&
        drawer.revision.next() == drawerRow.generation && custodian.revision.next() == custodianRow.generation && drawer.currency == session.expectedCash.currency &&
        sources.claim(LocalSalesStoreProfile.activeDrawer(drawer.id)) == drawer.activeSessionId &&
        sources.claim(LocalSalesStoreProfile.activeCustodian(custodian.id)) == custodian.activeSessionId &&
        sources.claim(configuredClaim(LocalSalesStoreProfile.DRAWER_KIND, drawer.id)) == drawer.id &&
        sources.claim(configuredClaim(LocalSalesStoreProfile.CUSTODIAN_KIND, custodian.id)) == custodian.id &&
        (drawer.activeSessionId == null || drawer.closingTransferId == null))
    localLabel(drawer.label)
    if (session.status in setOf(CashSessionStatus.OPEN, CashSessionStatus.CLOSING))
        check(drawer.activeSessionId == id && custodian.activeSessionId == id && drawer.closingTransferId == null)
    else check(drawer.activeSessionId != id && custodian.activeSessionId != id)
    return OriginalCash(session, drawer, custodian, work, setOf(cashResource(id), cashResource(drawer.id), cashResource(custodian.id), orderResource(work.id)) +
        work.orderIds.map(::orderResource) + work.returnIds.map(::entitlementResource))
}
private suspend fun originalOrderSources(proof: CommerceProof, id: DomainId): Pair<LocalCommerceOriginalRow, OwnedOrder> {
    val row = checkNotNull(proof.sources.aggregateOriginal(LocalSalesStoreProfile.key(LocalSalesStoreProfile.ORDER_KIND, id)))
    check(row.schema == LocalSalesStoreProfile.schema(row.key.kind))
    val order = originalOrder(proof.sources.scope, row)
    check(order.stockRequirements.normalized && proof.sources.claim(LocalSalesStoreProfile.unique("order", id)) == id)
    val contextRow = checkNotNull(proof.sources.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.ORDER_CONTEXT_KIND, id)))
    val context = localSalesDecode(contextRow.payload, LocalOrderContext.serializer())
    check(contextRow.schema == LocalSalesStoreProfile.schema(contextRow.key.kind) && context.scope.value() == proof.sources.scope && context.orderId == id && context.revision.next() == contextRow.generation && context.quoteRevision == order.quote.revision)
    requireSelectedQuote(context.selection, order.quote, id, context.quoteRevision, true)
    val reference = checkNotNull(order.priceSourceReference)
    val sourceRow = checkNotNull(proof.sources.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.PRICE_SOURCES_KIND, reference.id)))
    check(sourceRow.generation == Revision(1) && sourceRow.schema == LocalSalesStoreProfile.PRICE_SOURCES_SCHEMA)
    val prices = requirePriceSources(sourceRow.payload, proof.sources.scope, id, order.quote, reference, normalizedLines = order.stockRequirements.normalizedLines)
    originalReturnPolicyCapture(proof.sources, id, order.quote, order.returnPolicySourceReference, prices.sources, order.stockRequirements.normalizedLines)
    val work = originalWork(proof.sources, order.ownerId).second
    check((id in work.orderIds) == (order.paidSaleId == null))
    return row to order
}
private suspend fun postedReturnPolicyCapture(proof: CommerceProof, order: OwnedOrder,
    prices: LocalSalesPriceSourcesV2): LocalSalesReturnPolicyCaptureV2? {
    val reference = order.returnPolicySourceReference ?: return null
    check(reference.id == DomainId(proof.original.identity.producer.operationId.value))
    val key = AggregateKey(LocalSalesReturnPolicyCaptureCodecV2.KIND, reference.id)
    check(proof.sources.aggregate(key) == null)
    val write = proof.write(key)
    check(write.storageGeneration == Revision(1) && write.schema == LocalSalesReturnPolicyCaptureCodecV2.SCHEMA)
    return requireReturnPolicyCapture(write.payload, proof.sources.scope, order.id, order.quote, reference,
        prices.sources, order.stockRequirements.normalizedLines).also {
        check(it.originalQuoteOperationId == proof.original.identity.producer.operationId)
    }
}
private suspend fun accountPostedReturnPolicyCapture(proof: CommerceProof, capture: LocalSalesReturnPolicyCaptureV2?) {
    if (capture == null) return
    val key = AggregateKey(LocalSalesReturnPolicyCaptureCodecV2.KIND, capture.id)
    proof.account(key, LocalSalesReturnPolicyCaptureCodecV2.encode(capture), LocalSalesReturnPolicyCaptureCodecV2.SCHEMA, null)
}
private fun postedReturnPolicyRevision(capture: LocalSalesReturnPolicyCaptureV2?): List<LocalSalesRevision> =
    listOfNotNull(capture?.let { LocalSalesRevision(LocalSalesReturnPolicyCaptureCodecV2.KIND, it.id, Revision(0)) })
private suspend fun requireCreatedCollectionOrder(proof: CommerceProof, stock: LocalStockRelationalInspection,
    request: LocalCollectionStockRequestV2.CreateOrder, quotes: TransactionSalesStockQuoteSource) {
    requireNoStockActions(stock)
    val actor = proof.original.identity.producer.originalActorId
    val order = postedOrder(proof, request.orderId)
    NormalizedRetailHistory.requireCreated(order, actor)
    requireSelectedQuote(request.selection, order.quote, order.id, Revision(0), true)
    val orderKey = LocalSalesStoreProfile.key(LocalSalesStoreProfile.ORDER_KIND, order.id)
    val contextKey = LocalSalesStoreProfile.key(LocalSalesStoreProfile.ORDER_CONTEXT_KIND, order.id)
    check(proof.sources.aggregate(orderKey) == null && proof.sources.aggregate(contextKey) == null &&
        proof.sources.claim(LocalSalesStoreProfile.unique("order", order.id)) == null)
    val (workRow, work) = originalWork(proof.sources, actor)
    check(order.id !in work.orderIds && work.orderIds.size < LocalSalesStoreProfile.MAX_OPEN_WORK)
    originalAcceptsNewWork(proof.sources, actor)
    val reference = checkNotNull(order.priceSourceReference)
    val priceKey = LocalSalesStoreProfile.key(LocalSalesStoreProfile.PRICE_SOURCES_KIND, reference.id)
    check(reference.id == DomainId(proof.original.identity.producer.operationId.value) && proof.sources.aggregate(priceKey) == null)
    val priceRow = proof.write(priceKey)
    val prices = requirePriceSources(priceRow.payload, proof.sources.scope, order.id, order.quote, reference,
        normalizedLines = order.stockRequirements.normalizedLines)
    val capture = postedReturnPolicyCapture(proof, order, prices)
    val resources = quotes.requireOriginalNormalized(proof.sources, request.selection,
        LocalSalesQuotedOrder(order.quote, order.stockRequirements, prices.profile, prices.sources, capture?.input))
    check(prices.originalQuoteOperationId == proof.original.identity.producer.operationId)
    val context = LocalOrderContext(LocalSalesScope.from(proof.sources.scope), order.id, Revision(0), Revision(0), request.selection)
    val afterWork = work.copy(revision = work.revision.next(), orderIds = work.orderIds + order.id)
    proof.account(orderKey, originalBytes(SalesSnapshotCodec.encodeOrder(order)), LocalSalesStoreProfile.schema(orderKey.kind), null, true)
    proof.account(contextKey, localSalesEncode(context, LocalOrderContext.serializer()), LocalSalesStoreProfile.schema(contextKey.kind), null)
    proof.account(priceKey, priceRow.payload, LocalSalesStoreProfile.PRICE_SOURCES_SCHEMA, null)
    accountPostedReturnPolicyCapture(proof, capture)
    proof.account(workRow.key, localSalesEncode(afterWork, LocalWorkState.serializer()), LocalSalesStoreProfile.schema(workRow.key.kind), workRow)
    proof.finish(LocalSalesResultCodec.encode(LocalSalesResultV1.Applied(LocalSalesAction.ORDER_CREATED, order.id, listOf(
        LocalSalesRevision(orderKey.kind, order.id, order.revision), LocalSalesRevision(workRow.key.kind, work.id, afterWork.revision),
        LocalSalesRevision(priceKey.kind, priceKey.id, Revision(0))) + postedReturnPolicyRevision(capture), order.quote.payable)),
        listOf(ClaimChange.Acquire(LocalSalesStoreProfile.unique("order", order.id), order.id)))
    proof.requireSourceComplement(stock)
    stock.requireResources(resources + commerceRowResources(proof.original) + setOf(orderResource(order.id), orderResource(actor), orderResource(reference.id),
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, actor)))
}
private suspend fun requireReplacedCollectionOrder(proof: CommerceProof, stock: LocalStockRelationalInspection,
    request: LocalCollectionStockRequestV2.ReplaceUnlockedOrder, quotes: TransactionSalesStockQuoteSource) {
    requireNoStockActions(stock)
    val actor = proof.original.identity.producer.originalActorId
    val (beforeRow, before) = originalOrderSources(proof, request.orderId)
    check(before.revision == request.expectedOrderRevision)
    originalAcceptsNewWork(proof.sources, before.ownerId)
    val after = postedOrder(proof, request.orderId)
    NormalizedRetailHistory.requireReplaced(before, after, actor)
    requireSelectedQuote(request.selection, after.quote, after.id, before.quote.revision.next(), true)
    val reference = checkNotNull(after.priceSourceReference)
    val priceKey = LocalSalesStoreProfile.key(LocalSalesStoreProfile.PRICE_SOURCES_KIND, reference.id)
    check(reference.id == DomainId(proof.original.identity.producer.operationId.value) && proof.sources.aggregate(priceKey) == null)
    val priceRow = proof.write(priceKey)
    val prices = requirePriceSources(priceRow.payload, proof.sources.scope, after.id, after.quote, reference,
        normalizedLines = after.stockRequirements.normalizedLines)
    check(prices.originalQuoteOperationId == proof.original.identity.producer.operationId)
    val capture = postedReturnPolicyCapture(proof, after, prices)
    val resources = quotes.requireOriginalNormalized(proof.sources, request.selection,
        LocalSalesQuotedOrder(after.quote, after.stockRequirements, prices.profile, prices.sources, capture?.input))
    val contextRow = checkNotNull(proof.sources.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.ORDER_CONTEXT_KIND, after.id)))
    val context = localSalesDecode(contextRow.payload, LocalOrderContext.serializer())
    val selected = context.copy(revision = context.revision.next(), quoteRevision = after.quote.revision, selection = request.selection)
    proof.account(beforeRow.key, originalBytes(SalesSnapshotCodec.encodeOrder(after)), LocalSalesStoreProfile.schema(beforeRow.key.kind), beforeRow, true)
    proof.account(contextRow.key, localSalesEncode(selected, LocalOrderContext.serializer()), LocalSalesStoreProfile.schema(contextRow.key.kind), contextRow)
    proof.account(priceKey, priceRow.payload, LocalSalesStoreProfile.PRICE_SOURCES_SCHEMA, null)
    accountPostedReturnPolicyCapture(proof, capture)
    proof.finish(LocalSalesResultCodec.encode(LocalSalesResultV1.Applied(LocalSalesAction.ORDER_EDITED, after.id, listOf(
        LocalSalesRevision(beforeRow.key.kind, after.id, after.revision), LocalSalesRevision(priceKey.kind, priceKey.id, Revision(0))) + postedReturnPolicyRevision(capture),
        after.quote.payable)), emptyList())
    proof.requireSourceComplement(stock)
    stock.requireResources(resources + commerceRowResources(proof.original) + setOf(orderResource(after.id), orderResource(before.ownerId),
        orderResource(reference.id), RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, actor)) + priceSourcesResources(before.priceSourceReference))
}
private suspend fun requireOfferedCollectionOrder(proof: CommerceProof, stock: LocalStockRelationalInspection,
    request: LocalCollectionStockRequestV2.OfferHandoff) {
    requireNoStockActions(stock)
    val actor = proof.original.identity.producer.originalActorId
    val (beforeRow, before) = originalOrderSources(proof, request.orderId)
    check(before.revision == request.expectedOrderRevision)
    originalCustodian(proof.sources, request.recipientId)
    val recipient = originalWork(proof.sources, request.recipientId).second
    val claim = LocalSalesStoreProfile.unique("handoff_offer", request.handoffId)
    check(proof.sources.claim(claim) == null)
    val after = postedOrder(proof, request.orderId)
    NormalizedRetailHistory.requireHandoffOffered(before, after, actor, request.handoffId, request.recipientId, request.evidence.domain(actor))
    proof.account(beforeRow.key, originalBytes(SalesSnapshotCodec.encodeOrder(after)), LocalSalesStoreProfile.schema(beforeRow.key.kind), beforeRow, true)
    proof.finish(LocalSalesResultCodec.encode(LocalSalesResultV1.Applied(LocalSalesAction.HANDOFF_OFFERED, after.id,
        listOf(LocalSalesRevision(beforeRow.key.kind, after.id, after.revision)))), listOf(ClaimChange.Acquire(claim, after.id)))
    proof.requireSourceComplement(stock)
    stock.requireResources(commerceRowResources(proof.original) + setOf(orderResource(before.id), orderResource(before.ownerId), orderResource(recipient.id),
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, actor)) + priceSourcesResources(before.priceSourceReference))
}
private suspend fun requireCancelledCollectionOrder(proof: CommerceProof, stock: LocalStockRelationalInspection,
    request: LocalCollectionStockRequestV2.CancelHandoff) {
    requireNoStockActions(stock)
    val actor = proof.original.identity.producer.originalActorId
    val (beforeRow, before) = originalOrderSources(proof, request.orderId)
    check(before.revision == request.expectedOrderRevision &&
        proof.sources.claim(LocalSalesStoreProfile.unique("handoff_offer", request.handoffId)) == before.id)
    val after = postedOrder(proof, request.orderId)
    NormalizedRetailHistory.requireHandoffCancelled(before, after, actor, request.handoffId)
    proof.account(beforeRow.key, originalBytes(SalesSnapshotCodec.encodeOrder(after)), LocalSalesStoreProfile.schema(beforeRow.key.kind), beforeRow, true)
    proof.finish(LocalSalesResultCodec.encode(LocalSalesResultV1.Applied(LocalSalesAction.HANDOFF_CANCELLED, after.id,
        listOf(LocalSalesRevision(beforeRow.key.kind, after.id, after.revision)))), emptyList())
    proof.requireSourceComplement(stock)
    stock.requireResources(commerceRowResources(proof.original) + setOf(orderResource(before.id), orderResource(before.ownerId),
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, actor)) + priceSourcesResources(before.priceSourceReference))
}
private suspend fun requireAcceptedCollectionOrder(proof: CommerceProof, stock: LocalStockRelationalInspection,
    request: LocalCollectionStockRequestV2.AcceptHandoff) {
    requireNoStockActions(stock)
    val actor = proof.original.identity.producer.originalActorId
    val (beforeRow, before) = originalOrderSources(proof, request.orderId)
    check(before.revision == request.expectedOrderRevision &&
        proof.sources.claim(LocalSalesStoreProfile.unique("handoff_offer", request.handoffId)) == before.id)
    val (oldRow, oldOwner) = originalWork(proof.sources, before.ownerId)
    val (newRow, newOwner) = originalWork(proof.sources, actor)
    originalAcceptsNewWork(proof.sources, actor)
    check(oldOwner.id != newOwner.id && before.id in oldOwner.orderIds && before.id !in newOwner.orderIds &&
        newOwner.orderIds.size < LocalSalesStoreProfile.MAX_OPEN_WORK)
    val claim = LocalSalesStoreProfile.unique("handoff_acceptance", request.handoffId)
    check(proof.sources.claim(claim) == null)
    val after = postedOrder(proof, request.orderId)
    NormalizedRetailHistory.requireHandoffAccepted(before, after, actor, request.handoffId, request.evidence.domain(actor))
    val oldWork = oldOwner.copy(revision = oldOwner.revision.next(), orderIds = oldOwner.orderIds - after.id)
    val newWork = newOwner.copy(revision = newOwner.revision.next(), orderIds = newOwner.orderIds + after.id)
    proof.account(beforeRow.key, originalBytes(SalesSnapshotCodec.encodeOrder(after)), LocalSalesStoreProfile.schema(beforeRow.key.kind), beforeRow, true)
    proof.account(oldRow.key, localSalesEncode(oldWork, LocalWorkState.serializer()), LocalSalesStoreProfile.schema(oldRow.key.kind), oldRow)
    proof.account(newRow.key, localSalesEncode(newWork, LocalWorkState.serializer()), LocalSalesStoreProfile.schema(newRow.key.kind), newRow)
    proof.finish(LocalSalesResultCodec.encode(LocalSalesResultV1.Applied(LocalSalesAction.HANDOFF_ACCEPTED, after.id, listOf(
        LocalSalesRevision(beforeRow.key.kind, after.id, after.revision), LocalSalesRevision(oldRow.key.kind, oldWork.id, oldWork.revision),
        LocalSalesRevision(newRow.key.kind, newWork.id, newWork.revision)))), listOf(ClaimChange.Acquire(claim, after.id)))
    proof.requireSourceComplement(stock)
    stock.requireResources(commerceRowResources(proof.original) + setOf(orderResource(before.id), orderResource(before.ownerId), orderResource(newOwner.id),
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, actor)) + priceSourcesResources(before.priceSourceReference))
}
/** Complete business-row accounting only. Its caller must refuse without original receiver authority. */
private suspend fun requireReassignedCollectionOrderBusiness(proof: CommerceProof, stock: LocalStockRelationalInspection,
    request: LocalCollectionStockRequestV2.ReassignOrder) {
    requireNoStockActions(stock)
    val actor = proof.original.identity.producer.originalActorId
    val (beforeRow, before) = originalOrderSources(proof, request.orderId)
    check(before.revision == request.expectedOrderRevision && before.ownerId == request.previousOwnerId)
    val contextRow = checkNotNull(proof.sources.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.ORDER_CONTEXT_KIND, before.id)))
    val context = localSalesDecode(contextRow.payload, LocalOrderContext.serializer())
    check(context.selection.activity in setOf(LocalSalesActivity.CLOTHING, LocalSalesActivity.SUPERMARKET))
    val (oldRow, oldOwner) = originalWork(proof.sources, before.ownerId)
    val (newRow, newOwner) = originalWork(proof.sources, request.recipientId)
    originalAcceptsNewWork(proof.sources, before.ownerId)
    originalAcceptsNewWork(proof.sources, request.recipientId)
    check(oldOwner.id != newOwner.id && before.id in oldOwner.orderIds && before.id !in newOwner.orderIds &&
        newOwner.orderIds.size < LocalSalesStoreProfile.MAX_OPEN_WORK)
    val after = postedOrder(proof, request.orderId)
    val evidence = request.evidence.domain(actor)
    NormalizedRetailHistory.requireReassigned(before, after, actor, request.previousOwnerId, request.recipientId, request.reasonCode, evidence)
    val event = OrderReassigned(proof.sources.scope, proof.original.identity.producer.operationId, before.id, before.revision,
        after.revision, actor, request.previousOwnerId, request.recipientId, request.reasonCode, evidence, proof.sources.recordedAt.value)
    val audit = orderReassignmentAudit(event, oldOwner, newOwner)
    val auditKey = LocalSalesStoreProfile.key(LocalSalesStoreProfile.ORDER_REASSIGNMENT_KIND, audit.id)
    check(proof.sources.aggregate(auditKey) == null)
    val oldWork = oldOwner.copy(revision = oldOwner.revision.next(), orderIds = oldOwner.orderIds - after.id)
    val newWork = newOwner.copy(revision = newOwner.revision.next(), orderIds = newOwner.orderIds + after.id)
    proof.account(beforeRow.key, originalBytes(SalesSnapshotCodec.encodeOrder(after)), LocalSalesStoreProfile.schema(beforeRow.key.kind), beforeRow, true)
    proof.account(oldRow.key, localSalesEncode(oldWork, LocalWorkState.serializer()), LocalSalesStoreProfile.schema(oldRow.key.kind), oldRow)
    proof.account(newRow.key, localSalesEncode(newWork, LocalWorkState.serializer()), LocalSalesStoreProfile.schema(newRow.key.kind), newRow)
    proof.account(auditKey, localSalesEncode(audit, LocalOrderReassignmentAuditV1.serializer()), LocalSalesStoreProfile.schema(auditKey.kind), null)
    proof.finish(LocalSalesResultCodec.encode(LocalSalesResultV1.Applied(LocalSalesAction.ORDER_REASSIGNED, after.id, listOf(
        LocalSalesRevision(beforeRow.key.kind, after.id, after.revision), LocalSalesRevision(oldRow.key.kind, oldWork.id, oldWork.revision),
        LocalSalesRevision(newRow.key.kind, newWork.id, newWork.revision), LocalSalesRevision(auditKey.kind, audit.id, audit.revision)))), emptyList())
    proof.requireSourceComplement(stock)
    stock.requireResources(commerceRowResources(proof.original) + setOf(orderResource(before.id), orderResource(before.ownerId),
        orderResource(newOwner.id), orderResource(audit.id), RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, actor),
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, request.recipientId)) + priceSourcesResources(before.priceSourceReference))
}
private suspend fun requireBegunCollection(proof: CommerceProof, stock: LocalStockRelationalInspection,
    request: LocalCollectionStockRequestV2.Begin, quotes: TransactionSalesStockQuoteSource) {
    requireNoStockActions(stock, reserve = true)
    val actor = proof.original.identity.producer.originalActorId
    val (beforeRow, before) = originalOrderSources(proof, request.orderId)
    val cash = originalCash(proof.sources, request.sessionId)
    check(before.revision == request.expectedOrderRevision && cash.session.revision == request.expectedSessionRevision)
    val afterOrder = postedOrder(proof, request.orderId)
    val after = postedCollection(proof, request.collectionId)
    val collectionKey = LocalSalesStoreProfile.key(LocalSalesStoreProfile.COLLECTION_KIND, after.id)
    check(proof.sources.aggregate(collectionKey) == null && proof.sources.claim(LocalSalesStoreProfile.unique("collection", after.id)) == null)
    var count = 0
    val authorityResources = mutableSetOf<RecoveryResourceKey>()
    stock.reservationIntents { value ->
        check(count++ == 0 && value.reservationId == request.reservationId && value.binding == CollectionBinding(before.id, before.quote.revision,
            after.id, actor, CollectionExposure.UNKNOWN) && value.allocationSetId == before.id && value.allocationSetRevision == before.quote.revision.next() &&
            value.ordinal == 0 && value.lineCount == before.quote.lines.size && value.expiresAt == request.reservationExpiresAt)
        check(stock.beforeReservation(value.reservationId) == null)
        val lines = checkNotNull(before.stockRequirements.normalizedLines)
        var lineCount = 0
        stock.demandLines(value.reservationId) { line ->
            val demand = lines.getOrNull(line.ordinal) ?: error("sales.stock.demand_ordinal")
            check(line.ordinal == lineCount++ && line.reservationId == value.reservationId &&
                line.request == LocalStockAllocationRequest(demand.portionId, demand.skuId, demand.warehouseId, demand.quantity))
            authorityResources += quotes.requireOriginalReservationAuthority(proof.sources, line)
        }
        check(lineCount == lines.size)
    }
    check(count == 1)
    val header = checkNotNull(stock.reservation(request.reservationId))
    check(header.revision == Revision(1) && header.status == ReservationStatus.RESERVED && header.holdCount == 0L && header.issueId == null &&
        header.expiresAtEpochMillis == request.reservationExpiresAt?.value?.toEpochMilliseconds())
    requireRetailAllocations(checkNotNull(before.stockRequirements.normalizedLines), header) { stock.allocations(header.id, it) }
    NormalizedRetailHistory.requireBegun(before, cash.session, afterOrder, after, actor, header)
    proof.account(beforeRow.key, originalBytes(SalesSnapshotCodec.encodeOrder(afterOrder)), LocalSalesStoreProfile.schema(beforeRow.key.kind), beforeRow, true)
    proof.account(collectionKey, originalBytes(SalesSnapshotCodec.encodeCollection(after)), LocalSalesStoreProfile.schema(collectionKey.kind), null, true)
    proof.finish(LocalSalesResultCodec.encode(LocalSalesResultV1.Applied(LocalSalesAction.COLLECTION_LOCKED, after.id, listOf(
        LocalSalesRevision(beforeRow.key.kind, afterOrder.id, afterOrder.revision), LocalSalesRevision(collectionKey.kind, after.id, after.revision)), after.quote.payable)),
        listOf(ClaimChange.Acquire(LocalSalesStoreProfile.unique("collection", after.id), request.orderId)))
    proof.requireSourceComplement(stock)
    stock.requireResources(authorityResources + cash.resources + commerceRowResources(proof.original) + setOf(orderResource(before.id), orderResource(before.ownerId),
        orderResource(after.id), RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, actor)) + priceSourcesResources(before.priceSourceReference))
}
private suspend fun requireFinalizedCollection(proof: CommerceProof, stock: LocalStockRelationalInspection,
    request: LocalCollectionStockRequestV2.Finalize) {
    requireNoStockActions(stock, issue = true)
    val actor = proof.original.identity.producer.originalActorId
    val (orderRow, order) = originalOrderSources(proof, request.orderId)
    val collectionRow = checkNotNull(proof.sources.aggregateOriginal(LocalSalesStoreProfile.key(LocalSalesStoreProfile.COLLECTION_KIND, request.collectionId)))
    val collection = originalCollection(proof.sources.scope, collectionRow)
    check(collectionRow.schema == LocalSalesStoreProfile.schema(collectionRow.key.kind))
    check(collection.stockRequirements.normalized && collection.orderId == order.id && collection.revision == request.expectedCollectionRevision &&
        order.revision == request.expectedOrderRevision && proof.sources.claim(LocalSalesStoreProfile.unique("collection", collection.id)) == order.id)
    val cash = originalCash(proof.sources, collection.cashSessionId)
    SalesSnapshotLinks.requireCollection(collection, order, cash.session)
    for (attempt in collection.attempts) {
        check(proof.sources.claim(LocalSalesStoreProfile.unique("payment_attempt", attempt.id)) == collection.id)
        if (attempt.payment != null) check(proof.sources.claim(LocalSalesStoreProfile.unique("payment_record", attempt.id)) == collection.id)
    }
    val beforeReservation = checkNotNull(stock.beforeReservation(collection.reservationId))
    val reservationExpiry = beforeReservation.expiresAtEpochMillis
    check(beforeReservation.revision == request.reservationRevision && beforeReservation.status == ReservationStatus.RESERVED &&
        beforeReservation.holdCount == 0L && beforeReservation.issueId == null &&
        (reservationExpiry == null || reservationExpiry > proof.original.identity.recordedAt.value.toEpochMilliseconds()))
    requireNormalizedCollectionReservation(collection, beforeReservation) { stock.beforeAllocations(collection.reservationId, it) }
    stock.beforeHolds(collection.reservationId) { error("sales.stock.held_finalization") }
    check(stock.beforeIssue(request.issueId) == null)
    var issues = 0
    stock.issueIntents { check(issues++ == 0 && it == LocalStockReservedIssue(collection.reservationId,
        request.reservationRevision, request.issueId, ValueIssuePurpose.RETAIL)) }
    check(issues == 1)
    val header = checkNotNull(stock.reservation(collection.reservationId))
    val issue = checkNotNull(stock.issue(request.issueId))
    check(header.revision == request.reservationRevision.next() && header.issueId == issue.id && header.status == ReservationStatus.ISSUED &&
        issue.revision == Revision(1) && issue.reservationId == collection.reservationId && issue.purpose == ValueIssuePurpose.RETAIL &&
        issue.binding == CollectionBinding(order.id, collection.quote.revision, collection.id, actor, CollectionExposure.UNKNOWN) &&
        header.binding == beforeReservation.binding && header.allocationCount == beforeReservation.allocationCount && header.holdCount == 0L &&
        header.expiresAtEpochMillis == beforeReservation.expiresAtEpochMillis && issue.recipeSnapshotId == null &&
        issue.requiredStationIds.isEmpty() && issue.startedStationIds.isEmpty() && issue.disposition == null)
    check(issue.allocationCount == header.allocationCount)
    requireRetailIssueSlices(checkNotNull(collection.stockRequirements.normalizedLines), issue) { stock.issueSlices(issue.id, it) }
    val afterOrder = postedOrder(proof, request.orderId); val after = postedCollection(proof, request.collectionId)
    val saleKey = LocalSalesStoreProfile.key(LocalSalesStoreProfile.SALE_KIND, request.saleId)
    val entitlementKey = LocalSalesStoreProfile.key(LocalSalesStoreProfile.ENTITLEMENT_KIND, request.saleId)
    check(proof.sources.aggregate(saleKey) == null && proof.sources.aggregate(entitlementKey) == null)
    val sale = SalesSnapshotCodec.decodePaidSale(proof.write(saleKey).payload.copyBytes().decodeToString(throwOnInvalidSequence = true),
        SalesSnapshotBinding(SalesSnapshotKind.PAID_SALE, proof.sources.scope, request.saleId, Revision(0)))
    val entitlement = SalesSnapshotCodec.decodeReceiptEntitlement(proof.write(entitlementKey).payload.copyBytes().decodeToString(throwOnInvalidSequence = true),
        SalesSnapshotBinding(SalesSnapshotKind.RECEIPT_ENTITLEMENT, proof.sources.scope, request.saleId, Revision(0)))
    NormalizedRetailHistory.requireFinalized(order, collection, afterOrder, after, sale, entitlement, actor, request.issueId)
    val (workRow, work) = originalWork(proof.sources, order.ownerId)
    val afterWork = work.copy(revision = work.revision.next(), orderIds = work.orderIds - order.id)
    proof.account(orderRow.key, originalBytes(SalesSnapshotCodec.encodeOrder(afterOrder)), LocalSalesStoreProfile.schema(orderRow.key.kind), orderRow, true)
    proof.account(collectionRow.key, originalBytes(SalesSnapshotCodec.encodeCollection(after)), LocalSalesStoreProfile.schema(collectionRow.key.kind), collectionRow, true)
    proof.account(saleKey, originalBytes(SalesSnapshotCodec.encodePaidSale(sale)), LocalSalesStoreProfile.schema(saleKey.kind), null, true)
    proof.account(entitlementKey, originalBytes(SalesSnapshotCodec.encodeReceiptEntitlement(entitlement)), LocalSalesStoreProfile.schema(entitlementKey.kind), null, true)
    proof.account(workRow.key, localSalesEncode(afterWork, LocalWorkState.serializer()), LocalSalesStoreProfile.schema(workRow.key.kind), workRow)
    val claims = listOf(ClaimChange.Acquire(LocalSalesStoreProfile.unique("sale", sale.id), order.id),
        ClaimChange.Acquire(LocalSalesStoreProfile.unique("sale_order", order.id), sale.id),
        ClaimChange.Acquire(LocalSalesStoreProfile.unique("return_entitlement", sale.id), sale.id))
    for (claim in claims) check(proof.sources.claim(claim.key) == null)
    proof.finish(LocalSalesResultCodec.encode(LocalSalesResultV1.Applied(LocalSalesAction.SALE_FINALIZED, sale.id, listOf(
        LocalSalesRevision(orderRow.key.kind, order.id, afterOrder.revision), LocalSalesRevision(collectionRow.key.kind, collection.id, after.revision),
        LocalSalesRevision(saleKey.kind, sale.id, Revision(0)), LocalSalesRevision(entitlementKey.kind, sale.id, entitlement.revision),
        LocalSalesRevision(workRow.key.kind, work.id, afterWork.revision)), sale.quote.payable)), claims)
    proof.requireSourceComplement(stock)
    stock.requireResources(commerceRowResources(proof.original) + setOf(orderResource(collection.id), orderResource(order.id), orderResource(order.ownerId),
        orderResource(sale.id), entitlementResource(sale.id), cashResource(cash.session.id), cashResource(cash.drawer.id), cashResource(cash.custodian.id),
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, actor)) + priceSourcesResources(order.priceSourceReference))
}
