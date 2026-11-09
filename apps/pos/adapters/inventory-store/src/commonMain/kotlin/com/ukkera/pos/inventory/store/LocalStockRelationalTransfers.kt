package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.types.*
import kotlinx.serialization.Serializable

@Serializable internal data class StockTransferRequestV2Wire(val action: Int, val operationId: OperationId,
    val request: StockTransferRequestWire) {
    fun restore(): LocalStockRelationalCommand.Transfer {
        localStockCheck(action == 10)
        return LocalStockRelationalCommand.Transfer(operationId, request.restore())
    }
}

internal const val STOCK_TRANSFER_EFFECT_V2: String = "local.stock.v2.transfer"

internal fun stockTransferEdgeKey(pool: StockPoolKey, eventId: DomainId): AggregateKey =
    AggregateKey(StockRelationalKindsV2.TRANSFER_EDGE,
        localStockId("v2.transfer-edge", "${eventId.value}/${LocalStockRelationalProfileV2.poolKey(pool).id.value}"))

/** One source position, two warehouses, one local commit. Cross-branch dispatch/receipt,
 * supplier-held/quarantined custody and linked cost corrections are different workflows. */
private suspend fun transferPlan(command: LocalStockRelationalCommand.Transfer, read: StockReceiptAccess,
    sourceWarehouse: LocalStockWarehouseAuthority, destinationWarehouse: LocalStockWarehouseAuthority): StockReceiptPlan {
    localStockCheck(command.operationId == read.operationId)
    val input = command.request
    localStockCheck(StockTransferRequestWire.from(input).restore() == input)
    val sourceKey = input.source.inScope(read.scope)
    localStockCheck(sourceKey.pool.ownership == StockOwnership.CompanyOwned && sourceKey.custody == StockCustody.RELEASED,
        LocalStockFailure.UNSUPPORTED_VALUE_LINEAGE)
    val destinationKey = sourceKey.copy(pool = sourceKey.pool.copy(warehouseId = input.destinationWarehouseId))
    val activation = read.normal(LocalStockRelationalProfileV2.activationKey(read.scope.branchId)) ?: unavailableProducer()
    localStockCheck(activation.body is StockRelationalBody.Activation && stockActivationShape(activation.body))
    for ((warehouse, expected) in listOf(sourceWarehouse to sourceKey.pool.warehouseId,
        destinationWarehouse to destinationKey.pool.warehouseId)) {
        localStockCheck(warehouse.scope == read.scope && warehouse.warehouseId == expected && warehouse.active &&
            warehouse.sources.size in 1..16 && warehouse.sources.map { it.key }.distinct().size == warehouse.sources.size,
            LocalStockFailure.WAREHOUSE_UNAVAILABLE)
        for (stamp in warehouse.sources) read.current(stamp)
    }
    val documentKey = AggregateKey(LocalStockStoreProfile.TRANSFER_DOCUMENT, input.transferId)
    localStockCheck(read.document(documentKey) == null && read.claim(LocalStockStoreProfile.documentClaim(input.transferId)) == null &&
        read.claim(LocalStockStoreProfile.sourceClaim(input.sourceEventId)) == null &&
        read.claim(LocalStockStoreProfile.sourceClaim(input.destinationEventId)) == null, LocalStockFailure.SOURCE_ALREADY_USED)
    val item = read.normal(LocalStockRelationalProfileV2.itemKey(sourceKey.pool.skuId)) ?: missingStock()
    val metadata = item.body as? StockRelationalBody.Item ?: inconsistentStock()
    localStockCheck(item.generation == input.expectedItemRevision &&
        read.claim(LocalStockStoreProfile.itemClaim(sourceKey.pool.skuId)) == sourceKey.pool.skuId, LocalStockFailure.STALE_REVISION)
    localStockCheck(input.quantity.unitId == metadata.unitId && input.quantity.scale == metadata.scale &&
        input.quantity.scaled > 0, LocalStockFailure.INVALID_UNIT)
    localStockCheck(metadata.serialized == (sourceKey.serialId != null), LocalStockFailure.INVALID_SERIAL)
    val source = (read.normal(LocalStockRelationalProfileV2.positionKey(sourceKey))?.body as? StockRelationalBody.Position)?.value
        ?: missingStock()
    val existingDestination = (read.normal(LocalStockRelationalProfileV2.positionKey(destinationKey))?.body
        as? StockRelationalBody.Position)?.value
    val destination = existingDestination ?: source.copy(key = destinationKey, onHand = stockZeroQuantity(source.onHand))
    val now = read.now.value.toEpochMilliseconds()
    localStockCheck(source.isSellableAt(now) && destination.isSellableAt(now) &&
        destination.expiresAtEpochMillis == source.expiresAtEpochMillis &&
        destination.serialRegistrationId == source.serialRegistrationId, LocalStockFailure.UNSAFE_STOCK)

    // The complete membership retains paid/unknown claims. A transfer never expires,
    // releases, steals or moves an order's allocation to make stock appear available.
    var claimed = stockZeroQuantity(source.onHand)
    read.range(StockQueryV2.PositionMembers(LocalStockRelationalProfileV2.positionKey(sourceKey).id)) { row ->
        when (val body = row.body) {
            is StockRelationalBody.Allocation -> {
                val allocation = body.value
                localStockCheck(allocation.allocation.position == sourceKey)
                val reservation = (read.normal(LocalStockRelationalProfileV2.reservationKey(allocation.reservationId))?.body
                    as? StockRelationalBody.Reservation) ?: inconsistentStock()
                localStockCheck(read.claim(LocalStockStoreProfile.reservationClaim(reservation.value.id)) == reservation.value.id &&
                    read.claim(LocalStockStoreProfile.allocationClaim(allocation.allocation.allocationId)) == reservation.value.id &&
                    allocation.allocation.allocationId == LocalStockRelationalProfileV2.allocationId(reservation.allocationSetId,
                        reservation.allocationSetRevision, allocation.allocation.lineId, sourceKey))
                if (reservation.value.retainsStockClaim) claimed += allocation.allocation.quantity
            }
            is StockRelationalBody.Hold -> localStockCheck(body.value.hold.position == sourceKey)
            else -> inconsistentStock()
        }
    }
    localStockCheck(claimed <= source.onHand && input.quantity <= source.onHand - claimed,
        LocalStockFailure.INSUFFICIENT_STOCK)
    if (sourceKey.serialId != null || sourceKey.lotId != null) {
        var serialPositions = 0L
        read.range(StockQueryV2.SkuPositions(sourceKey.pool.skuId)) { row ->
            val position = (row.body as? StockRelationalBody.Position)?.value ?: inconsistentStock()
            if (sourceKey.lotId != null && position.key.lotId == sourceKey.lotId)
                localStockCheck(position.expiresAtEpochMillis == source.expiresAtEpochMillis &&
                    position.safety != StockSafety.RECALLED && position.safety != StockSafety.EXPIRED, LocalStockFailure.UNSAFE_STOCK)
            if (sourceKey.serialId != null && position.key.serialId == sourceKey.serialId && position.onHand.scaled > 0) {
                localStockCheck(position.key == sourceKey && position.onHand == stockSerialUnit(input.quantity), LocalStockFailure.INVALID_SERIAL)
                serialPositions = stockCheckedAdd(serialPositions, 1)
            }
        }
        if (sourceKey.serialId != null) localStockCheck(serialPositions == 1L && source.serialRegistrationId != null &&
            input.quantity == source.onHand && destination.onHand.scaled == 0L, LocalStockFailure.INVALID_SERIAL)
    }

    val sourcePool = (read.normal(LocalStockRelationalProfileV2.poolKey(sourceKey.pool))?.body as? StockRelationalBody.Pool)?.value
        ?: inconsistentStock()
    val oldDestinationPool = (read.normal(LocalStockRelationalProfileV2.poolKey(destinationKey.pool))?.body as? StockRelationalBody.Pool)?.value
    requireReceiptPoolBefore(read, sourceKey.pool, sourcePool, input.quantity)
    requireReceiptPoolBefore(read, destinationKey.pool, oldDestinationPool, input.quantity)
    val destinationPool = oldDestinationPool ?: InventoryPoolProjection(destinationKey.pool, Revision(1),
        stockZeroQuantity(input.quantity), Money(Currency.EGP, 0), 0)
    val outgoing = InventoryValuationProjection.issue(sourcePool, input.quantity)
    val value = -outgoing.value
    val incoming = InventoryValuationProjection.receive(destinationPool, input.quantity, value)
    val outEvent = StockRelationalBody.ValueEvent(sourceKey.pool, input.sourceEventId, outgoing.after.lastEventOrdinal,
        StockValueEventKind.TRANSFER_OUT, sourcePool, outgoing.after, -input.quantity, -value, -value, 1, null)
    val inEvent = StockRelationalBody.ValueEvent(destinationKey.pool, input.destinationEventId, incoming.after.lastEventOrdinal,
        StockValueEventKind.TRANSFER_IN, destinationPool, incoming.after, input.quantity, value, value, 1, null)
    val outId = stockKey(outEvent).id
    val inId = stockKey(inEvent).id
    val records = linkedMapOf<AggregateKey, StockRelationalBody>()
    for (body in listOf(metadata, StockRelationalBody.Position(source.copy(onHand = source.onHand - input.quantity)),
        StockRelationalBody.Position(destination.copy(onHand = destination.onHand + input.quantity)),
        StockRelationalBody.Pool(outgoing.after), StockRelationalBody.Pool(incoming.after), outEvent, inEvent,
        StockRelationalBody.ValueSlice(sourceKey.pool, input.sourceEventId, outId, outEvent.ordinal, null, input.transferId, input.quantity, value),
        StockRelationalBody.ValueSlice(destinationKey.pool, input.destinationEventId, inId, inEvent.ordinal, null, input.transferId, input.quantity, value),
        StockRelationalBody.TransferEdge(sourceKey.pool, input.transferId, outId, outEvent.ordinal, destinationKey.pool, inId, input.quantity, value),
        StockRelationalBody.TransferEdge(destinationKey.pool, input.transferId, inId, inEvent.ordinal, sourceKey.pool, outId, input.quantity, value))) {
        val key = stockKey(body)
        localStockCheck(records.put(key, body) == null)
        if (body is StockRelationalBody.ValueEvent || body is StockRelationalBody.ValueSlice || body is StockRelationalBody.TransferEdge)
            localStockCheck(read.normal(key) == null, LocalStockFailure.SOURCE_ALREADY_USED)
    }
    // The existing scalar document shape is retained; normalized value edges live in their
    // own canonical rows, never a fabricated legacy OwnedValuationPool/transfer certificate.
    val document = StockTransferWire(StockTransferRequestWire.from(input), read.actorId, read.now, value)
    val resources = setOf(RecoveryResourceKey(RecoveryResourceKind.STOCK, input.transferId)) +
        sourceResources(sourceWarehouse.sources + destinationWarehouse.sources)
    return StockReceiptPlan(records, listOf(ReceiptDocumentDraft(documentKey, null, stockBody(document, StockTransferWire.serializer()))),
        mapOf(LocalStockStoreProfile.documentClaim(input.transferId) to input.transferId,
            LocalStockStoreProfile.sourceClaim(input.sourceEventId) to input.transferId,
            LocalStockStoreProfile.sourceClaim(input.destinationEventId) to input.transferId), resources,
        stockResult(LocalStockResultKind.TRANSFERRED, input.transferId,
            listOf(LocalStockItemVersion(metadata.skuId, item.generation.next()))),
        source.expiresAtEpochMillis?.let { UtcInstant(kotlin.time.Instant.fromEpochMilliseconds(it)) })
}

internal suspend fun transferStock(repository: LocalStockRepository, authority: LocalStockAuthorityReader,
    view: CommandReadView, context: CommandContext, sink: StreamedCommandSink, now: UtcInstant,
    request: LocalStockRelationalCommand.Transfer): StreamedMutationDecision.Apply {
    val read = repository.receivingRelationalRead(view, context, sink)
    val installed = repository.receivingAuthority(authority)
    val source = installed.warehouse(view, request.request.source.warehouseId) ?: throw LocalStockRejected(LocalStockFailure.WAREHOUSE_UNAVAILABLE)
    val destination = installed.warehouse(view, request.request.destinationWarehouseId) ?: throw LocalStockRejected(LocalStockFailure.WAREHOUSE_UNAVAILABLE)
    val plan = transferPlan(request, CurrentReceiptAccess(repository, read, context, now), source, destination)
    val work = StockPostingBuilder(read, context, sink)
    for (body in plan.records.values) work.put(body)
    for ((key, owner) in plan.claims) work.acquire(key, owner)
    for (document in plan.documents) sink.change(AggregateChange(document.key, null, stockSchema(document.key.kind, document.body),
        stockRecord(context.scope, document.key, null, StockProducerWire.from(context), document.body)))
    sink.effect(StreamedEffectV2(STOCK_TRANSFER_EFFECT_V2, request.request.transferId, LocalStockRelationalCommandsV2.encode(request)))
    for (resource in plan.companionResources) sink.resource(resource)
    return work.finish(plan.result, plan.deadline)
}

internal suspend fun requireTransferStockProducer(original: RetainedStreamedCommand, stock: LocalStockRelationalInspection,
    authority: LocalStockRelationalAuthorityReader, request: LocalStockRelationalCommand.Transfer) {
    localStockCheck(original.identity.producer.command == LocalStockRelationalProfileV2.COMMAND &&
        original.normalized == LocalStockRelationalCommandsV2.encode(request))
    val source = authority.retainedWarehouse(original, request.request.source.warehouseId)
    val destination = authority.retainedWarehouse(original, request.request.destinationWarehouseId)
    val plan = transferPlan(request, RetainedReceiptAccess(stock), source, destination)
    requireScalarStockPlan(original, stock, plan, STOCK_TRANSFER_EFFECT_V2, request.request.transferId)
    original.frames { frame ->
        val actual = (frame as? RetainedCommandFrameV2.Source)?.value ?: return@frames
        var used = false
        transferPlan(request, RetainedReceiptAccess(stock) { if (it == actual) used = true }, source, destination)
        localStockCheck(used)
    }
    stock.requireResources(plan.companionResources)
}

/** SAME admitted local transfer plan; expiry comes from the exact source position. */
internal suspend fun retainedTransferStockDeadline(stock: LocalStockRelationalInspection,
    authority: LocalStockRelationalAuthorityReader, request: LocalStockRelationalCommand.Transfer): UtcInstant? =
    transferPlan(request, RetainedReceiptAccess(stock),
        authority.retainedWarehouse(stock.original, request.request.source.warehouseId),
        authority.retainedWarehouse(stock.original, request.request.destinationWarehouseId)).deadline

/** Exact immutable paired history, used by later receipts/issues/transfers and cost reads.
 * It checks BOTH event/edge/slice directions under the real producer reader. It does not
 * recurse through a second warehouse's complete history or admit cost-correction rows. */
internal suspend fun requireStockTransferValuePair(read: StockPoolHistoryAccess,
    record: StockRelationalRecordV2): StockRelationalBody.TransferEdge {
    val event = record.body as? StockRelationalBody.ValueEvent ?: inconsistentStock()
    localStockCheck(event.event == StockValueEventKind.TRANSFER_OUT || event.event == StockValueEventKind.TRANSFER_IN)
    val edgeRecord = read.normal(stockTransferEdgeKey(event.pool, record.key.id)) ?: inconsistentStock()
    val edge = edgeRecord.body as? StockRelationalBody.TransferEdge ?: inconsistentStock()
    val otherRecord = read.normal(AggregateKey(StockRelationalKindsV2.VALUE_EVENT, edge.linkedEventId)) ?: inconsistentStock()
    val other = otherRecord.body as? StockRelationalBody.ValueEvent ?: inconsistentStock()
    val backRecord = read.normal(stockTransferEdgeKey(edge.linkedPool, otherRecord.key.id)) ?: inconsistentStock()
    val back = backRecord.body as? StockRelationalBody.TransferEdge ?: inconsistentStock()
    localStockCheck(edge.pool == event.pool && edge.eventId == record.key.id && edge.eventOrdinal == event.ordinal &&
        edge.linkedPool == other.pool && edge.linkedEventId == otherRecord.key.id && event.sourceId != other.sourceId &&
        back == StockRelationalBody.TransferEdge(other.pool, edge.sourceId, otherRecord.key.id, other.ordinal,
            event.pool, record.key.id, edge.quantity, edge.originalValue) && event.pool != other.pool &&
        event.pool.scope == other.pool.scope &&
        event.pool.skuId == other.pool.skuId && event.pool.ownership == StockOwnership.CompanyOwned &&
        other.pool.ownership == StockOwnership.CompanyOwned && event.pool.warehouseId != other.pool.warehouseId &&
        read.claim(LocalStockStoreProfile.documentClaim(edge.sourceId)) == edge.sourceId &&
        read.claim(LocalStockStoreProfile.sourceClaim(event.sourceId)) == edge.sourceId &&
        read.claim(LocalStockStoreProfile.sourceClaim(other.sourceId)) == edge.sourceId)
    val outgoing = if (event.event == StockValueEventKind.TRANSFER_OUT) event else other
    val incoming = if (event.event == StockValueEventKind.TRANSFER_IN) event else other
    localStockCheck(outgoing.event == StockValueEventKind.TRANSFER_OUT && incoming.event == StockValueEventKind.TRANSFER_IN)
    val out = InventoryValuationProjection.issue(outgoing.before, edge.quantity)
    val into = InventoryValuationProjection.receive(incoming.before, edge.quantity, edge.originalValue)
    localStockCheck(-out.value == edge.originalValue && outgoing.after == out.after && incoming.after == into.after)
    for ((row, value) in listOf(record to event, otherRecord to other)) {
        val sign = if (value.event == StockValueEventKind.TRANSFER_OUT) -1 else 1
        localStockCheck(value.issueId == null && value.sliceCount == 1L && value.quantityDelta ==
            (if (sign < 0) -edge.quantity else edge.quantity) && value.originalValueDelta ==
            (if (sign < 0) -edge.originalValue else edge.originalValue) && value.carryingValueDelta == value.originalValueDelta)
        val slice = StockRelationalBody.ValueSlice(value.pool, value.sourceId, row.key.id, value.ordinal, null,
            edge.sourceId, edge.quantity, edge.originalValue)
        val sliceRecord = read.normal(stockKey(slice)) ?: inconsistentStock()
        localStockCheck(sliceRecord.body == slice)
        for (immutable in listOf(row, sliceRecord)) localStockCheck(immutable.generation == Revision(1) &&
            immutable.previous == null && immutable.producer == record.producer)
    }
    for (immutable in listOf(edgeRecord, backRecord)) localStockCheck(immutable.generation == Revision(1) &&
        immutable.previous == null && immutable.producer == record.producer)
    return edge
}

/** Later commerce producers must account for these permanent claim observations too.
 * An isolated favorable edge is insufficient: one side must occur in an actually retained
 * complete pool range, and both sides are checked by the same literal original reader. */
internal suspend fun stockTransferClaimRequirements(stock: LocalStockRelationalInspection,
    consume: suspend (ClaimKey, DomainId) -> Unit) {
    stock.original.frames { frame ->
        val source = (frame as? RetainedCommandFrameV2.Source)?.value as? StreamedSourceObservationV2.Aggregate ?: return@frames
        if (source.key.kind != StockRelationalKindsV2.TRANSFER_EDGE) return@frames
        val edge = stock.before(source.key)?.body as? StockRelationalBody.TransferEdge ?: inconsistentStock()
        val eventRecord = stock.before(AggregateKey(StockRelationalKindsV2.VALUE_EVENT, edge.eventId)) ?: inconsistentStock()
        localStockCheck(requireStockTransferValuePair(RetainedReceiptAccess(stock), eventRecord) == edge)
        var inPoolHistory = false
        stock.original.frames { candidate ->
            val query = ((candidate as? RetainedCommandFrameV2.Source)?.value as? StreamedSourceObservationV2.StockRange)?.query
                as? StockQueryV2.PoolValues
            if (query != null && (query.poolId == LocalStockRelationalProfileV2.poolKey(edge.pool).id ||
                    query.poolId == LocalStockRelationalProfileV2.poolKey(edge.linkedPool).id)) {
                stock.range(query) { member ->
                    if (member.key == source.key || member.key == stockTransferEdgeKey(edge.linkedPool, edge.linkedEventId))
                        inPoolHistory = true
                }
            }
        }
        localStockCheck(inPoolHistory)
        val event = eventRecord.body as? StockRelationalBody.ValueEvent ?: inconsistentStock()
        val other = stock.before(AggregateKey(StockRelationalKindsV2.VALUE_EVENT, edge.linkedEventId))?.body
            as? StockRelationalBody.ValueEvent ?: inconsistentStock()
        consume(LocalStockStoreProfile.documentClaim(edge.sourceId), edge.sourceId)
        consume(LocalStockStoreProfile.sourceClaim(event.sourceId), edge.sourceId)
        consume(LocalStockStoreProfile.sourceClaim(other.sourceId), edge.sourceId)
    }
}
