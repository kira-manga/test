package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.AggregateChange
import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.ClaimChange
import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.MasterCommandHandler
import com.ukkera.pos.database.MasterReadHandler
import com.ukkera.pos.database.PreparedMutation
import com.ukkera.pos.database.ProposedOutcome
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.inventory.InventoryFailure
import com.ukkera.pos.inventory.InventoryEffect
import com.ukkera.pos.inventory.InventoryPlanner
import com.ukkera.pos.inventory.InventoryRejection
import com.ukkera.pos.inventory.ValuationFact
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.ActionBinding
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.AuthorizedAction
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.PrincipalKind
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.KSerializer

/**
 * Install these trusted handlers in the REAL master store with the SAME repository used by
 * sales. No handler owns a Room connection, initializes a branch or supplies accepting runtime,
 * authority or authentication. Command success is published only by the kernel after commit.
 */
public class LocalStockHandlers(
    repository: LocalStockRepository,
    authority: LocalStockAuthorityReader,
) {
    public val commands: List<MasterCommandHandler> = localStockFrozen(listOf(
        StockReceiptHandler(repository, authority, opening = true),
        StockReceiptHandler(repository, authority, opening = false),
        StockReceiptHandler(repository, authority, opening = true, serial = true),
        StockReceiptHandler(repository, authority, opening = false, serial = true),
        StockInspectionHandler(repository, authority), StockInspectionHandler(repository, authority, serial = true),
        StockCountCaptureHandler(repository), StockCountPostHandler(repository, authority),
        StockCountCaptureHandler(repository, serial = true), StockCountPostHandler(repository, authority, serial = true),
        StockRecallHandler(repository), StockTransferHandler(repository, authority),
        StockRecallHandler(repository, serial = true), StockTransferHandler(repository, authority, serial = true),
        StockTransferHandler(repository, authority, owned = true),
    ) + (if (repository.hasPurchaseOrderReader) listOf(StockReceiptHandler(repository, authority, opening = false, purchaseOrder = true)) else emptyList()) +
        LocalStockRelationalServices(repository, authority).commands)
    public val reads: List<MasterReadHandler> = localStockReadHandlers(repository)
}

internal abstract class StockCommandHandler(
    final override val key: CommandKey,
    kinds: Set<String>,
    claims: Set<String>,
) : MasterCommandHandler {
    private val kinds = kinds.toSet()
    private val claims = claims.toSet()
    final override val writableKinds: Set<String> get() = kinds.toSet()
    final override val claimNamespaces: Set<String> get() = claims.toSet()
    final override fun normalize(input: StoredBytes): StoredBytes = LocalStockCommandCodec.normalize(key, input)
    final override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        localStockCheck(context.key == key && context.scope == view.scope &&
            semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest,
            LocalStockFailure.STORAGE_INCONSISTENT)
        requireLegacyStockCurrent(view)
        return prepareStock(normalized, view, context)
    }
    protected abstract suspend fun prepareStock(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation
}

/** All failures, including a missing document, are evaluated only after exact live authorization. */
internal fun preparedStock(
    context: CommandContext,
    documentId: DomainId,
    revision: Revision,
    reasonCode: String,
    resources: Set<RecoveryResourceKey>,
    barrierKinds: Set<RecoveryResourceKind> = setOf(RecoveryResourceKind.STOCK),
    action: (UtcInstant) -> ProposedOutcome,
): PreparedMutation = object : PreparedMutation() {
    override val requirement: AuthorizationRequirement = AuthorizationRequirement(
        Permission(SecurityAction.INVENTORY_ADJUST, PermissionVerb.PERFORM), context.scope,
        ActionBinding(documentId, revision, context.semanticDigest, context.operationId, reasonCode))
    private val resources = (resources + RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, context.originalActorId)).toSet()
    private val barriers = barrierKinds.toSet()
    override val affectedResources: Set<RecoveryResourceKey> get() = resources.toSet()
    override val recoveryHoldBarrierKinds: Set<RecoveryResourceKind> get() = barriers.toSet()
    override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
        localStockCheck(authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE &&
            authorization.isBoundTo(requirement, context.originalActorId), LocalStockFailure.STORAGE_INCONSISTENT)
        return try {
            action(recordedAt)
        } catch (failure: LocalStockRejected) {
            // Current-row/parser/producer failures never become durable business rejections.
            if (failure.failure in stockUncertainFailures) throw failure
            rejectStock(documentId, failure.failure)
        } catch (failure: InventoryRejection) {
            val mapped = when (failure.failure) {
                InventoryFailure.STALE_REVISION -> LocalStockFailure.STALE_REVISION
                InventoryFailure.DUPLICATE_SOURCE -> LocalStockFailure.SOURCE_ALREADY_USED
                InventoryFailure.INVALID_QUANTITY -> LocalStockFailure.INVALID_QUANTITY
                InventoryFailure.UNIT_OR_SCALE_MISMATCH -> LocalStockFailure.INVALID_UNIT
                InventoryFailure.INSUFFICIENT_STOCK -> LocalStockFailure.INSUFFICIENT_STOCK
                InventoryFailure.UNSAFE_STOCK -> LocalStockFailure.UNSAFE_STOCK
                InventoryFailure.UNSUPPORTED_LINEAGE -> LocalStockFailure.UNSUPPORTED_VALUE_LINEAGE
                InventoryFailure.BOUND_EXCEEDED -> LocalStockFailure.BOUND_EXCEEDED
                else -> throw failure
            }
            rejectStock(documentId, mapped)
        }
    }
}

private val stockUncertainFailures = setOf(LocalStockFailure.MALFORMED, LocalStockFailure.NON_CANONICAL,
    LocalStockFailure.STORAGE_INCONSISTENT, LocalStockFailure.PRODUCER_UNAVAILABLE, LocalStockFailure.WRONG_SCOPE)

internal fun rejectStock(documentId: DomainId, failure: LocalStockFailure): ProposedOutcome.Reject = ProposedOutcome.Reject(
    "stock.${failure.name.lowercase()}", LocalStockCommandCodec.result(LocalStockResult(LocalStockResultKind.REJECTED, documentId, emptyList(), failure)))

internal fun stockResult(kind: LocalStockResultKind, id: DomainId, items: List<LocalStockItemVersion> = emptyList()): StoredBytes =
    LocalStockCommandCodec.result(LocalStockResult(kind, id, items.sortedBy { it.skuId.value }))

internal fun requireItemVersions(cut: LocalStockReadCut, expected: List<LocalStockItemVersion>) {
    localStockCheck(expected.all { version -> version.skuId in cut.state.items &&
        cut.state.items[version.skuId]?.row?.revision == version.storageRevision }, LocalStockFailure.STALE_REVISION)
}

internal fun <T> stockChange(context: CommandContext, key: AggregateKey, previous: LocalStockRecord<T>?, value: T, serializer: KSerializer<T>): AggregateChange =
    AggregateChange(key, previous?.row?.revision, stockSchema(key.kind, stockBody(value, serializer)), stockRecord(context.scope, key,
        previous?.row?.revision, StockProducerWire.from(context), stockBody(value, serializer), previous?.row))

/** Authoritative absence of both row and permanent ID is required before an insert. */
internal suspend fun <T> stockDocument(
    repository: LocalStockRepository, view: CommandReadView, kind: String, id: DomainId, serializer: KSerializer<T>,
): LocalStockRecord<T>? {
    val record = repository.record(view, AggregateKey(kind, id), serializer)
    val claim = view.claim(LocalStockStoreProfile.documentClaim(id))
    localStockCheck(if (record == null) claim == null else claim == id, LocalStockFailure.STORAGE_INCONSISTENT)
    return record
}

/** Re-read every source digest/generation under the same borrowed transaction. No cache is trusted. */
internal suspend fun requireStockAuthorityStamps(view: CommandReadView, sources: List<LocalStockSourceStamp>): List<LocalStockSourceStamp> {
    localStockCheck(sources.isNotEmpty(), LocalStockFailure.STORAGE_INCONSISTENT)
    val grouped = sources.groupBy { it.key }
    localStockCheck(grouped.size <= 128 && grouped.values.all { it.distinct().size == 1 }, LocalStockFailure.STORAGE_INCONSISTENT)
    val result = grouped.values.map { it.first() }.sortedWith(compareBy<LocalStockSourceStamp> { it.key.kind }.thenBy { it.key.id.value })
    for (stamp in result) {
        val row = view.aggregate(stamp.key) ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
        localStockCheck(row.revision == stamp.storageRevision && row.digest == stamp.digest && sha256(row.payload.copyBytes()) == stamp.digest,
            LocalStockFailure.STORAGE_INCONSISTENT)
    }
    return localStockFrozen(result)
}

internal fun sourceResources(sources: List<LocalStockSourceStamp>): Set<RecoveryResourceKey> =
    sources.map { RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it.key.id) }.toSet()

private class StockCountCaptureHandler(private val repository: LocalStockRepository, serial: Boolean = false) : StockCommandHandler(
    if (serial) LocalStockStoreProfile.COUNT_CAPTURE_SERIAL else LocalStockStoreProfile.COUNT_CAPTURE,
    setOf(LocalStockStoreProfile.COUNT_DOCUMENT, LocalStockStoreProfile.POSTING),
    setOf(LocalStockStoreProfile.DOCUMENT_CLAIM),
) {
    override suspend fun prepareStock(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        val request = LocalStockCommandCodec.countCapture(normalized)
        val current = stockDocument(repository, view, LocalStockStoreProfile.COUNT_DOCUMENT, request.countId, StockCountWire.serializer())
        val cut = repository.observe(view, listOf(request.position.skuId))
        return preparedStock(context, request.countId, request.expectedItemRevision, request.reasonCode,
            repository.resources(cut) + RecoveryResourceKey(RecoveryResourceKind.STOCK, request.countId) +
                RecoveryResourceKey(RecoveryResourceKind.STOCK, request.position.warehouseId)) { now ->
            localStockCheck(current == null, LocalStockFailure.SOURCE_ALREADY_USED)
            requireItemVersions(cut, listOf(LocalStockItemVersion(request.position.skuId, request.expectedItemRevision)))
            val position = cut.snapshot.position(request.position.inScope(context.scope)) ?: throw LocalStockRejected(LocalStockFailure.STOCK_MISSING)
            val count = StockCountWire(request.countId, StockPositionRefWire.from(request.position), request.expectedItemRevision,
                position.onHand, context.originalActorId, now, null, null, null, request.reasonCode, null)
            completeStockProposal(context, listOf(stockChange(context, AggregateKey(LocalStockStoreProfile.COUNT_DOCUMENT, request.countId),
                null, count, StockCountWire.serializer())), listOf(ClaimChange.Acquire(LocalStockStoreProfile.documentClaim(request.countId), request.countId)),
                stockResult(LocalStockResultKind.COUNT_CAPTURED, request.countId), observations = stockReadObservations(cut.state))
        }
    }
}

private class StockCountPostHandler(private val repository: LocalStockRepository, private val authority: LocalStockAuthorityReader, serial: Boolean = false) : StockCommandHandler(
    if (serial) LocalStockStoreProfile.COUNT_POST_SERIAL else LocalStockStoreProfile.COUNT_POST,
    LocalStockStoreProfile.inventoryKinds + LocalStockStoreProfile.COUNT_DOCUMENT,
    setOf(LocalStockStoreProfile.SOURCE_CLAIM, LocalStockStoreProfile.COUNT_SURPLUS_CLAIM),
) {
    override suspend fun prepareStock(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        val request = LocalStockCommandCodec.countPost(normalized)
        val current = stockDocument(repository, view, LocalStockStoreProfile.COUNT_DOCUMENT, request.countId, StockCountWire.serializer())
        val cut = repository.observe(view, current?.value?.let { listOf(it.position.skuId) } ?: emptyList())
        val used = view.claim(LocalStockStoreProfile.sourceClaim(request.countId))
        val basis = request.surplusBasisId?.let { authority.countSurplusBasis(view, it) }
        localStockCheck(basis == null || basis.scope == context.scope && basis.basisId == request.surplusBasisId, LocalStockFailure.STORAGE_INCONSISTENT)
        val basisSources = basis?.let { requireStockAuthorityStamps(view, it.sources) }.orEmpty()
        val basisUsed = request.surplusBasisId?.let { view.claim(LocalStockStoreProfile.countSurplusClaim(it)) }
        val approvalResources = sourceResources(basisSources) + listOfNotNull(
            request.surplusBasisId?.let { RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it) },
            basis?.approvedBy?.let { RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it) },
            basis?.policyId?.let { RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it) })
        return preparedStock(context, request.countId, request.expectedCountRevision, request.reasonCode,
            repository.resources(cut) + approvalResources + RecoveryResourceKey(RecoveryResourceKind.STOCK, request.countId)) { now ->
            val record = current ?: throw LocalStockRejected(LocalStockFailure.STOCK_MISSING)
            val before = record.value
            localStockCheck(request.serialId == before.position.serialId, LocalStockFailure.INVALID_SERIAL)
            localStockCheck(record.row.revision == request.expectedCountRevision, LocalStockFailure.STALE_REVISION)
            localStockCheck(before.counted == null && used == null, LocalStockFailure.SOURCE_ALREADY_USED)
            localStockCheck(cut.state.items[before.position.skuId]?.row?.revision == before.capturedItemRevision,
                LocalStockFailure.COUNT_REQUIRES_RECOUNT)
            val position = cut.snapshot.position(before.position.key(context.scope)) ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
            localStockCheck(position.onHand == before.capturedQuantity, LocalStockFailure.STORAGE_INCONSISTENT)
            localStockCheck(request.counted.unitId == position.onHand.unitId && request.counted.scale == position.onHand.scale,
                LocalStockFailure.INVALID_UNIT)
            localStockCheck(request.serialId == null || request.counted.scaled == 0L || request.counted == stockSerialUnit(request.counted),
                LocalStockFailure.INVALID_SERIAL)
            val surplus = request.counted > position.onHand
            localStockCheck(request.serialId == null || !surplus, LocalStockFailure.UNSUPPORTED_VALUE_LINEAGE)
            val approved = if (surplus) {
                val approval = basis ?: throw LocalStockRejected(LocalStockFailure.COUNT_SURPLUS_REQUIRES_APPROVAL)
                localStockCheck(approval.revision == request.expectedSurplusBasisRevision, LocalStockFailure.STALE_REVISION)
                localStockCheck(basisUsed == null, LocalStockFailure.SOURCE_ALREADY_USED)
                requireCountSurplusBasis(approval, context.scope, record.row.revision, before, request.counted, now)
                approval
            } else {
                localStockCheck(request.surplusBasisId == null, LocalStockFailure.BASIS_UNAVAILABLE)
                null
            }
            val plan = if (approved != null) InventoryPlanner.recordCountSurplus(cut.snapshot, cut.snapshot.revision, context.operationId,
                request.countId, position.key, cut.snapshot.revision, request.counted, approved.surplusValue)
            else InventoryPlanner.recordCountShortage(cut.snapshot, cut.snapshot.revision, context.operationId,
                request.countId, position.key, cut.snapshot.revision, request.counted)
            val contribution = repository.project(cut, context, plan, now)
            val after = before.copy(counted = request.counted, postedBy = context.originalActorId, postedAt = now,
                postingReasonCode = request.reasonCode, surplusBasis = approved?.let(StockCountSurplusBasisWire::from))
            repository.compose(context, contribution, stockResult(LocalStockResultKind.COUNT_POSTED, request.countId, contribution.items),
                listOf(stockChange(context, record.row.key, record, after, StockCountWire.serializer())),
                approved?.let { listOf(ClaimChange.Acquire(LocalStockStoreProfile.countSurplusClaim(it.basisId), request.countId)) }.orEmpty(),
                mustCommitBefore = approved?.validUntil)
        }
    }
}

internal fun requireCountSurplusBasis(
    basis: LocalCountSurplusBasis,
    scope: com.ukkera.pos.security.BranchScope,
    countRevision: Revision,
    count: StockCountWire,
    counted: com.ukkera.pos.types.Quantity,
    now: UtcInstant,
) {
    localStockCheck(basis.scope == scope && basis.countId == count.countId && basis.countRevision == countRevision &&
        basis.capturedItemRevision == count.capturedItemRevision && StockPositionRefWire.from(basis.position) == count.position &&
        basis.capturedQuantity == count.capturedQuantity && basis.countedQuantity == counted,
        LocalStockFailure.BASIS_UNAVAILABLE)
    localStockCheck(now >= basis.validFrom && (basis.validUntil?.let { now < it } ?: true), LocalStockFailure.BASIS_EXPIRED)
}

private class StockRecallHandler(private val repository: LocalStockRepository, private val serial: Boolean = false) : StockCommandHandler(
    if (serial) LocalStockStoreProfile.RECALL_SERIAL else LocalStockStoreProfile.RECALL,
    setOf(LocalStockStoreProfile.ITEM, LocalStockStoreProfile.POSITION, LocalStockStoreProfile.RESERVATION,
        LocalStockStoreProfile.POSTING), emptySet(),
) {
    override suspend fun prepareStock(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        val request = LocalStockCommandCodec.recall(normalized)
        val cut = repository.observe(view, listOf(request.skuId))
        return preparedStock(context, request.lotId, request.expectedItemRevision, request.reasonCode,
            repository.resources(cut) + RecoveryResourceKey(RecoveryResourceKind.STOCK, request.lotId)) { now ->
            requireItemVersions(cut, listOf(LocalStockItemVersion(request.skuId, request.expectedItemRevision)))
            localStockCheck(serial || cut.snapshot.positions.none { it.key.serialId != null }, LocalStockFailure.SERIAL_WORKFLOW_REQUIRED)
            localStockCheck(cut.snapshot.positions.any { it.key.pool.skuId == request.skuId && it.key.lotId == request.lotId },
                LocalStockFailure.STOCK_MISSING)
            val plan = InventoryPlanner.recallLot(cut.snapshot, cut.snapshot.revision, context.operationId, request.skuId, request.lotId)
            val contribution = repository.project(cut, context, plan, now)
            repository.compose(context, contribution, stockResult(LocalStockResultKind.RECALLED, request.lotId, contribution.items))
        }
    }
}

private class StockTransferHandler(private val repository: LocalStockRepository, private val authority: LocalStockAuthorityReader,
    serial: Boolean = false, private val owned: Boolean = false) : StockCommandHandler(
    if (owned) LocalStockStoreProfile.TRANSFER_OWNED else if (serial) LocalStockStoreProfile.TRANSFER_SERIAL else LocalStockStoreProfile.TRANSFER,
    LocalStockStoreProfile.inventoryKinds + LocalStockStoreProfile.TRANSFER_DOCUMENT,
    setOf(LocalStockStoreProfile.SOURCE_CLAIM, LocalStockStoreProfile.DOCUMENT_CLAIM),
) {
    override suspend fun prepareStock(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        val request = LocalStockCommandCodec.transfer(normalized)
        val current = stockDocument(repository, view, LocalStockStoreProfile.TRANSFER_DOCUMENT, request.transferId, StockTransferWire.serializer())
        val cut = repository.observe(view, listOf(request.source.skuId))
        val source = authority.warehouse(view, request.source.warehouseId)
        val destination = authority.warehouse(view, request.destinationWarehouseId)
        localStockCheck(source == null || source.scope == context.scope && source.warehouseId == request.source.warehouseId, LocalStockFailure.STORAGE_INCONSISTENT)
        localStockCheck(destination == null || destination.scope == context.scope && destination.warehouseId == request.destinationWarehouseId, LocalStockFailure.STORAGE_INCONSISTENT)
        val rawSources = source?.sources.orEmpty() + destination?.sources.orEmpty()
        val sources = if (rawSources.isEmpty()) emptyList() else requireStockAuthorityStamps(view, rawSources)
        val sourceUsed = view.claim(LocalStockStoreProfile.sourceClaim(request.sourceEventId))
        val destinationUsed = view.claim(LocalStockStoreProfile.sourceClaim(request.destinationEventId))
        val destinationPosition = request.source.inScope(context.scope).let { it.copy(pool = it.pool.copy(warehouseId = request.destinationWarehouseId)) }
        return preparedStock(context, request.transferId, request.expectedItemRevision, request.reasonCode,
            repository.resources(cut) + sourceResources(sources) +
                (if (owned) setOf(RecoveryResourceKey(RecoveryResourceKind.STOCK, request.transferId)) else emptySet()) + setOf(
                RecoveryResourceKey(RecoveryResourceKind.STOCK, request.source.warehouseId),
                RecoveryResourceKey(RecoveryResourceKind.STOCK, request.destinationWarehouseId),
                RecoveryResourceKey(RecoveryResourceKind.STOCK, LocalStockStoreProfile.positionKey(destinationPosition).id),
                RecoveryResourceKey(RecoveryResourceKind.STOCK, LocalStockStoreProfile.poolKey(destinationPosition.pool).id),
                RecoveryResourceKey(RecoveryResourceKind.STOCK, request.sourceEventId),
                RecoveryResourceKey(RecoveryResourceKind.STOCK, request.destinationEventId),
                RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, request.transferId))) { now ->
            localStockCheck(current == null && sourceUsed == null && destinationUsed == null, LocalStockFailure.SOURCE_ALREADY_USED)
            localStockCheck(source?.active == true && destination?.active == true, LocalStockFailure.WAREHOUSE_UNAVAILABLE)
            requireItemVersions(cut, listOf(LocalStockItemVersion(request.source.skuId, request.expectedItemRevision)))
            localStockCheck((request.source.supplierId == null) == owned, LocalStockFailure.UNSUPPORTED_VALUE_LINEAGE)
            val plan = if (owned) InventoryPlanner.transferReleasedOwned(cut.snapshot, cut.snapshot.revision, context.operationId,
                request.transferId, request.sourceEventId, request.destinationEventId, request.source.inScope(context.scope),
                request.destinationWarehouseId, request.quantity, now.value.toEpochMilliseconds())
            else InventoryPlanner.transferSupplierCustody(cut.snapshot, cut.snapshot.revision, context.operationId,
                request.sourceEventId, request.destinationEventId, request.source.inScope(context.scope), request.destinationWarehouseId, request.quantity)
            val contribution = repository.project(cut, context, plan, now)
            val value = if (owned) {
                val link = plan.effects.filterIsInstance<InventoryEffect.Valuation>().mapNotNull { (it.plan.fact as? ValuationFact.TransferOut)?.link }.single()
                StockOwnedTransferWire(StockOwnedTransferValueWire.from(link), StockTransferWarehouseWire.from(requireNotNull(source)),
                    StockTransferWarehouseWire.from(requireNotNull(destination)))
            } else null
            val transfer = StockTransferWire(StockTransferRequestWire.from(request), context.originalActorId, now, value?.value?.originalValue, value)
            repository.compose(context, contribution, stockResult(LocalStockResultKind.TRANSFERRED, request.transferId, contribution.items),
                listOf(stockChange(context, AggregateKey(LocalStockStoreProfile.TRANSFER_DOCUMENT, request.transferId), null,
                    transfer, StockTransferWire.serializer())),
                listOf(ClaimChange.Acquire(LocalStockStoreProfile.documentClaim(request.transferId), request.transferId)))
        }
    }
}
