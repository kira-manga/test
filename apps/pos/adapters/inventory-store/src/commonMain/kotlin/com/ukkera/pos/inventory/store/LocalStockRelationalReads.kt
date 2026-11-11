package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** Existing read identities/DTOs stay usable by the real stock screens after activation. */
internal val stockRelationalReadKeys: Set<ReadKey> get() = setOf(LocalStockStoreProfile.ITEMS,
    LocalStockStoreProfile.QUANTITIES, LocalStockStoreProfile.QUANTITY_POSITION, LocalStockStoreProfile.COSTS,
    LocalStockStoreProfile.TRANSFER_DETAIL, LocalStockStoreProfile.TRANSFER_COSTS)

/** The query owns only one output page. Complete relational memberships/history are consumed
 * from the same retained owner loan; they are not cached as a whole branch or SKU object graph. */
internal suspend fun prepareStockRelationalRead(repository: LocalStockRepository, normalized: StoredBytes,
    view: CommandReadView, context: ReadContext): PreparedRead {
    val read = repository.observeRelational(view)
    return read.withCompletedProducerProof {
        when (context.key) {
            LocalStockStoreProfile.ITEMS -> relationalItems(read, normalized, view, context)
            LocalStockStoreProfile.QUANTITIES, LocalStockStoreProfile.QUANTITY_POSITION -> relationalQuantities(repository, read, normalized, context)
            LocalStockStoreProfile.COSTS -> relationalCosts(read, normalized, context)
            LocalStockStoreProfile.TRANSFER_DETAIL, LocalStockStoreProfile.TRANSFER_COSTS ->
                relationalTransfer(repository, read, normalized, context)
            else -> inconsistentStock()
        }
    }
}

private fun relationalStockReadRequirement(context: ReadContext, action: SecurityAction, id: DomainId,
    revision: Revision): AuthorizationRequirement = AuthorizationRequirement(Permission(action, PermissionVerb.PERFORM),
    context.scope, ActionBinding(id, revision, context.semanticDigest, reasonCode = "stock.read"))

private fun stockReadProjection(context: ReadContext, requirements: List<AuthorizationRequirement>,
    projection: (UtcInstant) -> StoredBytes): PreparedRead = object : PreparedRead() {
    override val requirements: List<AuthorizationRequirement> = localStockFrozen(requirements)
    override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes {
        localStockCheck(authorizations.size == requirements.size && authorizations.zip(requirements).all { (action, requirement) ->
            action.principalKind == PrincipalKind.LOCAL_EMPLOYEE && action.isBoundTo(requirement, context.actorId)
        }, LocalStockFailure.STORAGE_INCONSISTENT)
        return projection(observedAt)
    }
}

private suspend fun relationalItems(read: LocalStockRelationalRead, normalized: StoredBytes,
    view: CommandReadView, context: ReadContext): PreparedRead {
    val request = LocalStockCommandCodec.page(normalized)
    val page = view.aggregateKeys(StockRelationalKindsV2.ITEM, request.after, request.limit)
    localStockCheck(page.keys.size <= request.limit && page.keys.all { it.kind == StockRelationalKindsV2.ITEM } &&
        page.keys.zipWithNext().all { (a, b) -> a.id.value < b.id.value } &&
        (page.nextAfter == null || page.keys.lastOrNull()?.id == page.nextAfter))
    val rows = mutableListOf<StockItemReadWire>()
    for (key in page.keys) {
        val row = read.record(key) ?: inconsistentStock()
        val item = row.body as? StockRelationalBody.Item ?: inconsistentStock()
        localStockCheck(read.itemVersion(item.skuId).storageRevision == row.generation)
        var count = 0
        read.query(StockQueryV2.SkuPositions(item.skuId)) { index ->
            val position = read.record(index.identity.key)?.body as? StockRelationalBody.Position ?: inconsistentStock()
            localStockCheck(position.value.key.pool.skuId == item.skuId && position.value.onHand.unitId == item.unitId &&
                position.value.onHand.scale == item.scale && count < Int.MAX_VALUE)
            count++
        }
        rows += StockItemReadWire(item.skuId, row.generation, item.unitId, item.scale, count)
    }
    val requirements = rows.map { relationalStockReadRequirement(context, SecurityAction.INVENTORY_QUANTITY_READ, it.skuId, it.revision) }
        .ifEmpty { listOf(relationalStockReadRequirement(context, SecurityAction.INVENTORY_QUANTITY_READ, context.scope.branchId, Revision(0))) }
    return stockReadProjection(context, requirements) { stockEncode(StockItemsReadWire(1, rows, page.nextAfter), StockItemsReadWire.serializer()) }
}

/** Retains only the first limit+1 ordered identities, never silently truncates the logical query. */
private fun selectPage(ids: MutableList<DomainId>, id: DomainId, after: DomainId?, limit: Int) {
    if (after != null && id.value <= after.value || id in ids) return
    val index = ids.indexOfFirst { it.value > id.value }.let { if (it < 0) ids.size else it }
    if (index <= limit) {
        ids.add(index, id)
        if (ids.size > limit + 1) ids.removeAt(ids.lastIndex)
    }
}

private data class QuantityProjection(val row: StockRelationalRecordV2, val position: StockPosition,
    val reserved: Quantity, val held: Quantity, val shortage: Quantity, val serialCode: String?)

private suspend fun relationalQuantities(repository: LocalStockRepository, read: LocalStockRelationalRead,
    normalized: StoredBytes, context: ReadContext): PreparedRead {
    val coordinate = if (context.key == LocalStockStoreProfile.QUANTITY_POSITION) LocalStockCommandCodec.position(normalized) else null
    val request = coordinate?.let { StockPageWire(it.skuId, null, 1) } ?: LocalStockCommandCodec.page(normalized)
    val skuId = checkNotNull(request.id)
    val version = read.itemVersion(skuId)
    val item = read.record(LocalStockRelationalProfileV2.itemKey(skuId))?.body as? StockRelationalBody.Item
    val selected = mutableListOf<DomainId>()
    read.query(StockQueryV2.SkuPositions(skuId)) { index ->
        localStockCheck(item != null && (index as? StockIndexRecordV2.Position)?.skuId == skuId)
        if (coordinate == null || coordinate.positionId == index.identity.key.id)
            selectPage(selected, index.identity.key.id, request.after, request.limit)
    }
    val next = if (selected.size > request.limit) selected[request.limit - 1] else null
    val projections = mutableListOf<QuantityProjection>()
    for (id in selected.take(request.limit)) {
        val row = read.record(AggregateKey(StockRelationalKindsV2.POSITION, id)) ?: inconsistentStock()
        val position = (row.body as? StockRelationalBody.Position)?.value ?: inconsistentStock()
        localStockCheck(position.key.pool.skuId == skuId && position.onHand.unitId == item?.unitId && position.onHand.scale == item?.scale)
        val zero = stockZeroQuantity(position.onHand)
        var reserved = zero; var held = zero
        val reason = when (position.safety) {
            StockSafety.CLEAR -> null
            StockSafety.RECALLED -> ReservationHoldReason.RECALL
            StockSafety.EXPIRED -> ReservationHoldReason.EXPIRY
            StockSafety.COUNT_REVIEW -> ReservationHoldReason.COUNT_SHORTAGE
        }
        var shortageHolds = true
        read.positionMembers(position.key) { reservation, allocation ->
            if (reservation.retainsStockClaim) {
                reserved += allocation.allocation.quantity
                if (reservation.status == ReservationStatus.HELD) held += allocation.allocation.quantity
                var safetyHeld = reason == null; var countHeld = false
                read.holds(reservation.id) { hold -> if (hold.hold.position == position.key) {
                    if (hold.hold.reason == reason) safetyHeld = true
                    if (hold.hold.reason == ReservationHoldReason.COUNT_SHORTAGE || hold.hold.reason == ReservationHoldReason.UNAVAILABLE) countHeld = true
                } }
                localStockCheck(safetyHeld && (reason == null || reservation.status == ReservationStatus.HELD))
                shortageHolds = shortageHolds && countHeld && reservation.status == ReservationStatus.HELD
            }
        }
        val shortage = if (reserved > position.onHand) reserved - position.onHand else zero
        localStockCheck(shortage.scaled == 0L || shortageHolds && reserved == held)
        requireRelationalStockSerialPosition(read, repository, position)
        val serial = position.key.serialId?.let { serialId ->
            stockSerial(repository, read.view, serialId)?.value?.serialCode ?: inconsistentStock()
        }
        projections += QuantityProjection(row, position, reserved, held, shortage, serial)
    }
    return stockReadProjection(context, listOf(relationalStockReadRequirement(context, SecurityAction.INVENTORY_QUANTITY_READ,
        skuId, version.storageRevision ?: Revision(0)))) { now ->
        val millis = now.value.toEpochMilliseconds()
        val rows = projections.map { value ->
            val p = value.position
            StockQuantityReadWire(value.row.key.id, StockPositionRefWire.from(p.key), value.row.generation, p.onHand, value.reserved,
                if (p.isSellableAt(millis) && value.reserved < p.onHand) p.onHand - value.reserved else stockZeroQuantity(p.onHand),
                p.safety.name, p.expiresAtEpochMillis?.let { UtcInstant(kotlin.time.Instant.fromEpochMilliseconds(it)) },
                p.expiresAtEpochMillis?.let { millis >= it } ?: false, value.held, value.shortage, value.serialCode, p.serialRegistration?.name)
        }
        stockEncode(StockQuantitiesReadWire(if (rows.any { it.position.serialId != null }) 3 else 2, skuId,
            version.storageRevision, item?.unitId, item?.scale, rows, next), StockQuantitiesReadWire.serializer())
    }
}

private suspend fun relationalCosts(read: LocalStockRelationalRead, normalized: StoredBytes,
    context: ReadContext): PreparedRead {
    val request = LocalStockCommandCodec.page(normalized)
    val skuId = checkNotNull(request.id)
    val version = read.itemVersion(skuId)
    val ids = mutableListOf<DomainId>()
    // Complete per-SKU position enumeration derives the finite page of valuation owners. There
    // is no global pool tree in memory and supplier-owned custody never acquires an owned value.
    read.query(StockQueryV2.SkuPositions(skuId)) { index ->
        val p = (read.record(index.identity.key)?.body as? StockRelationalBody.Position)?.value ?: inconsistentStock()
        if (p.key.pool.ownership == StockOwnership.CompanyOwned)
            selectPage(ids, LocalStockRelationalProfileV2.poolKey(p.key.pool).id, request.after, request.limit)
    }
    val rows = mutableListOf<StockCostReadWire>()
    for (id in ids.take(request.limit)) {
        val row = read.record(AggregateKey(StockRelationalKindsV2.POOL, id)) ?: inconsistentStock()
        val pool = (row.body as? StockRelationalBody.Pool)?.value ?: inconsistentStock()
        localStockCheck(pool.key.skuId == skuId)
        requireStockPoolHistory(read, pool)
        rows += StockCostReadWire(id, pool.key.warehouseId, row.generation, pool.revision, pool.onHand, pool.carryingValue)
    }
    return stockReadProjection(context, listOf(SecurityAction.INVENTORY_QUANTITY_READ, SecurityAction.INVENTORY_COST_READ).map {
        relationalStockReadRequirement(context, it, skuId, version.storageRevision ?: Revision(0))
    }) { stockEncode(StockCostsReadWire(1, skuId, version.storageRevision, rows,
        if (ids.size > request.limit) ids[request.limit - 1] else null), StockCostsReadWire.serializer()) }
}

/** Shared by stock issue and cost/history queries, not just receiving. Exhausts every historical
 * event and slice, with bidirectional issue joins, before trusting the present moving average. */
internal suspend fun requireStockPoolHistory(read: LocalStockRelationalRead, pool: InventoryPoolProjection) {
    requireReceiptPoolBefore(currentPoolHistory(read), pool.key, pool, pool.onHand)
}

private fun currentPoolHistory(read: LocalStockRelationalRead): StockPoolHistoryAccess = object : StockPoolHistoryAccess {
    override suspend fun normal(key: AggregateKey): StockRelationalRecordV2? = read.record(key)
    override suspend fun claim(key: ClaimKey): DomainId? = read.claim(key)
    override suspend fun range(query: StockQueryV2, consume: suspend (StockRelationalRecordV2) -> Unit) {
        read.query(query) { index ->
            val row = read.record(index.identity.key) ?: inconsistentStock()
            localStockCheck(stockIndexOf(read.scope, row) == index)
            consume(row)
        }
    }
}

/** The old transfer read identity remains usable; v2 explicitly identifies the normalized
 * valuation projection. No legacy list-root read or invented original pool enters this route. */
private suspend fun relationalTransfer(repository: LocalStockRepository, read: LocalStockRelationalRead,
    normalized: StoredBytes, context: ReadContext): PreparedRead {
    val id = LocalStockCommandCodec.document(normalized)
    val costs = context.key == LocalStockStoreProfile.TRANSFER_COSTS
    val row = stockDocument(repository, read.view, LocalStockStoreProfile.TRANSFER_DOCUMENT, id, StockTransferWire.serializer())
    val transfer = row?.value
    val value = if (transfer == null || row == null) null else {
        val input = transfer.request.restore()
        localStockCheck(row.envelope.producer.commandKey() == LocalStockRelationalProfileV2.COMMAND &&
            input.transferId == id && transfer.owned == null && read.claim(LocalStockStoreProfile.documentClaim(id)) == id)
        val source = input.source.inScope(context.scope)
        val destination = source.copy(pool = source.pool.copy(warehouseId = input.destinationWarehouseId))
        val outRecord = read.record(stockValueEventKey(source.pool, input.sourceEventId)) ?: inconsistentStock()
        val outgoing = outRecord.body as? StockRelationalBody.ValueEvent ?: inconsistentStock()
        val edge = requireStockTransferValuePair(currentPoolHistory(read), outRecord)
        val inRecord = read.record(stockValueEventKey(destination.pool, input.destinationEventId)) ?: inconsistentStock()
        val incoming = inRecord.body as? StockRelationalBody.ValueEvent ?: inconsistentStock()
        localStockCheck(outgoing.event == StockValueEventKind.TRANSFER_OUT && incoming.event == StockValueEventKind.TRANSFER_IN &&
            edge.sourceId == id && edge.linkedPool == destination.pool && edge.linkedEventId == inRecord.key.id &&
            edge.quantity == input.quantity && edge.originalValue == transfer.originalValue &&
            outRecord.producer.operationId == row.envelope.producer.operationId)
        if (costs) for (key in listOf(source.pool, destination.pool)) {
            val pool = (read.record(LocalStockRelationalProfileV2.poolKey(key))?.body as? StockRelationalBody.Pool)?.value
                ?: inconsistentStock()
            // A zero linked correction is disclosed only after BOTH complete current
            // histories have been validated. Unsupported correction rows fail this fold.
            requireStockPoolHistory(read, pool)
        }
        val serial = source.serialId?.let { serialId ->
            val origin = stockSerial(repository, read.view, serialId)?.value ?: inconsistentStock()
            localStockCheck(origin.serialId == serialId && origin.skuId == source.pool.skuId && origin.registration != null &&
                read.claim(LocalStockStoreProfile.serialClaim(serialId)) == serialId &&
                read.claim(LocalStockStoreProfile.serialCodeClaim(origin.serialCode)) == serialId)
            var current: StockPosition? = null
            read.query(StockQueryV2.SkuPositions(source.pool.skuId)) { index ->
                val position = (read.record(index.identity.key)?.body as? StockRelationalBody.Position)?.value ?: inconsistentStock()
                if (position.key.serialId == serialId) {
                    requireRelationalStockSerialPosition(read, repository, position)
                    if (position.onHand.scaled > 0) {
                        localStockCheck(current == null && position.onHand == stockSerialUnit(input.quantity))
                        current = position
                    }
                }
            }
            StockTransferSerialReadWire(serialId, origin.serialCode, StockSerialRegistrationStatus.REGISTERED.name,
                checkNotNull(origin.registration).registrationId, origin.receiptId, origin.lineId, origin.sourceId, origin.originalPosition,
                origin.receivedAt, current?.key?.let(StockPositionRefWire::from), current?.onHand ?: stockZeroQuantity(input.quantity),
                current?.safety?.name, current?.expiresAtEpochMillis?.let { UtcInstant(kotlin.time.Instant.fromEpochMilliseconds(it)) })
        }
        StockTransferReadValueWire(id, row.row.revision, input.sourceEventId, input.destinationEventId,
            StockPositionRefWire.from(source), StockPositionRefWire.from(destination), input.quantity, transfer.transferredBy,
            transfer.recordedAt, input.reasonCode, id, outgoing.before.revision, incoming.before.revision,
            MovingAveragePlanner.CALCULATION_VERSION, costs, if (costs) edge.originalValue else null,
            if (costs) Money(edge.originalValue.currency, 0) else null, serial)
    }
    val actions = listOf(SecurityAction.INVENTORY_QUANTITY_READ) + if (costs) listOf(SecurityAction.INVENTORY_COST_READ) else emptyList()
    return stockReadProjection(context, actions.map { relationalStockReadRequirement(context, it, id, row?.row?.revision ?: Revision(0)) }) {
        stockEncode(StockTransferReadWire(2, value), StockTransferReadWire.serializer())
    }
}

/** Only a routing projection, never an activation token or substitute for transactional checks. */
public object LocalStockRelationalQueriesV2 {
    public val PROFILE: ReadKey = ReadKey("stock.normalized.profile", 2)
    public val STATION_PROFILE: ReadKey = ReadKey("stock.normalized.station-profile", 2)
    public val HISTORY: ReadKey = ReadKey("stock.normalized.history", 2)
    public fun history(skuId: DomainId, warehouseId: DomainId, afterOrdinal: Long = 0, limit: Int = 25): ReadRequest =
        ReadRequest(HISTORY, stockEncode(StockHistoryQueryV2(skuId, warehouseId, afterOrdinal, limit).also { it.requireValid() }, StockHistoryQueryV2.serializer()))
    public fun decodeHistory(bytes: StoredBytes): LocalStockHistoryPage = stockDecode(bytes, StockHistoryPageWireV2.serializer()).let {
        localStockCheck(it.version == 2 && it.rows.size <= LocalStockStoreProfile.MAX_READ_ROWS &&
            it.rows.zipWithNext().all { (a, b) -> a.ordinal < b.ordinal } && (it.nextOrdinal == null || it.nextOrdinal == it.rows.lastOrNull()?.ordinal))
        LocalStockHistoryPage(it.skuId, it.warehouseId, it.itemRevision, it.rows, it.nextOrdinal)
    }
    public fun profile(): ReadRequest = ReadRequest(PROFILE, StoredBytes(byteArrayOf()))
    public fun stationProfile(stationId: DomainId): ReadRequest = ReadRequest(STATION_PROFILE,
        stockEncode(StockStationProfileQueryV2(stationId), StockStationProfileQueryV2.serializer(), 256))
    public fun stationProfileTarget(bytes: StoredBytes): DomainId =
        stockDecode(bytes, StockStationProfileQueryV2.serializer(), 256).stationId
    public fun normalized(bytes: StoredBytes): Boolean = stockDecode(bytes, StockProfileWireV2.serializer()).let {
        localStockCheck(it.version == 2); it.normalized
    }
}
@kotlinx.serialization.Serializable internal data class StockStationProfileQueryV2(val stationId: DomainId)
@kotlinx.serialization.Serializable internal data class StockProfileWireV2(val version: Int, val normalized: Boolean)
internal class StockProfileRead(private val repository: LocalStockRepository) : MasterReadHandler {
    override val key: ReadKey = LocalStockRelationalQueriesV2.PROFILE
    override fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean =
        profile == LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS ||
            profile == LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL && normalized.size == 0
    override fun normalize(input: StoredBytes): StoredBytes { localStockCheck(input.size == 0); return input }
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        localStockCheck(normalized.size == 0 && context.key == key && context.scope == view.scope &&
            queryDigest(context.scope, context.actorId, key, normalized) == context.semanticDigest)
        val active = repository.isRelationalCurrent(view)
        if (active) repository.observeRelational(view) // Full actual retained activation producer.
        return stockReadProjection(context, listOf(relationalStockReadRequirement(context, SecurityAction.INVENTORY_QUANTITY_READ,
            context.scope.branchId, Revision(0)))) { stockEncode(StockProfileWireV2(2, active), StockProfileWireV2.serializer()) }
    }
}

/** Station routing metadata only; current task checks still govern each preparation command. */
internal class StockStationProfileRead(private val repository: LocalStockRepository) : MasterReadHandler {
    override val key: ReadKey = LocalStockRelationalQueriesV2.STATION_PROFILE
    override fun normalize(input: StoredBytes): StoredBytes = stockEncode(
        stockDecode(input, StockStationProfileQueryV2.serializer(), 256), StockStationProfileQueryV2.serializer(), 256)
    override fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean {
        if (profile != LocalMasterRpcWorkflowProfile.KITCHEN) return false
        LocalStockRelationalQueriesV2.stationProfileTarget(normalized)
        return true
    }
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        localStockCheck(context.key == key && context.scope == view.scope &&
            queryDigest(context.scope, context.actorId, key, normalized) == context.semanticDigest)
        val query = stockDecode(normalized, StockStationProfileQueryV2.serializer(), 256)
        val active = repository.isRelationalCurrent(view)
        if (active) repository.observeRelational(view) // Full actual retained activation producer.
        return stockReadProjection(context, listOf(relationalStockReadRequirement(context, SecurityAction.KITCHEN_TASK,
            query.stationId, Revision(0)))) { stockEncode(StockProfileWireV2(2, active), StockProfileWireV2.serializer()) }
    }
}


/** Original posted value and later carrying-value contribution remain distinct display fields. */
@kotlinx.serialization.Serializable
public data class LocalStockHistoryRowV2(public val sourceId: DomainId, public val ordinal: Long, public val movement: String,
    public val quantityDelta: Quantity, public val originalValueDelta: Money, public val carryingValueDelta: Money,
    public val resultingQuantity: Quantity, public val resultingValue: Money, public val recordedAt: UtcInstant)
public class LocalStockHistoryPage(public val skuId: DomainId, public val warehouseId: DomainId, public val itemRevision: Revision?,
    rows: List<LocalStockHistoryRowV2>, public val nextOrdinal: Long?) {
    public val rows: List<LocalStockHistoryRowV2> = localStockFrozen(rows)
}
@kotlinx.serialization.Serializable internal data class StockHistoryQueryV2(val skuId: DomainId, val warehouseId: DomainId, val after: Long, val limit: Int) {
    fun requireValid() { localStockCheck(after >= 0 && limit in 1..LocalStockStoreProfile.MAX_READ_ROWS) }
}
@kotlinx.serialization.Serializable internal data class StockHistoryPageWireV2(val version: Int, val skuId: DomainId, val warehouseId: DomainId,
    val itemRevision: Revision?, val rows: List<LocalStockHistoryRowV2>, val nextOrdinal: Long?)
internal class StockHistoryRead(private val repository: LocalStockRepository) : MasterReadHandler {
    override val key: ReadKey = LocalStockRelationalQueriesV2.HISTORY
    override fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean =
        profile == LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS
    override fun normalize(input: StoredBytes): StoredBytes = stockEncode(stockDecode(input, StockHistoryQueryV2.serializer()).also {
        it.requireValid() }, StockHistoryQueryV2.serializer())
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        localStockCheck(context.key == key && view.scope == context.scope && queryDigest(context.scope, context.actorId, key, normalized) == context.semanticDigest)
        val input = stockDecode(normalized, StockHistoryQueryV2.serializer())
        val read = repository.observeRelational(view)
        return read.withCompletedProducerProof {
            val version = read.itemVersion(input.skuId)
            val poolKey = StockPoolKey(InventoryScope(context.scope.companyId, context.scope.branchId), input.skuId, input.warehouseId, StockOwnership.CompanyOwned)
            val pool = read.pool(poolKey)
            val rows = mutableListOf<LocalStockHistoryRowV2>()
            if (pool != null) {
                requireStockPoolHistory(read, pool)
                read.query(StockQueryV2.PoolValues(LocalStockRelationalProfileV2.poolKey(poolKey).id)) { index ->
                    val row = read.record(index.identity.key) ?: inconsistentStock()
                    val event = row.body as? StockRelationalBody.ValueEvent
                    if (event != null && event.ordinal > input.after && rows.size <= input.limit) rows += LocalStockHistoryRowV2(event.sourceId,
                        event.ordinal, event.event.name, event.quantityDelta, event.originalValueDelta, event.carryingValueDelta,
                        event.after.onHand, event.after.carryingValue, read.original(row.producer.operationId).identity.recordedAt)
                }
            }
            val next = if (rows.size > input.limit) rows[input.limit - 1].ordinal else null
            val output = StockHistoryPageWireV2(2, input.skuId, input.warehouseId, version.storageRevision, rows.take(input.limit), next)
            stockReadProjection(context, listOf(SecurityAction.INVENTORY_QUANTITY_READ, SecurityAction.INVENTORY_COST_READ).map {
                relationalStockReadRequirement(context, it, input.skuId, version.storageRevision ?: Revision(0))
            }) { stockEncode(output, StockHistoryPageWireV2.serializer()) }
        }
    }
}
