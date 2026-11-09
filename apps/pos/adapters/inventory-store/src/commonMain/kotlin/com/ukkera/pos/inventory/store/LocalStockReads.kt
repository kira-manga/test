package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.MasterReadHandler
import com.ukkera.pos.database.LocalMasterRpcWorkflowProfile
import com.ukkera.pos.database.LocalMasterStockTransferRpcKeysV1
import com.ukkera.pos.database.PreparedRead
import com.ukkera.pos.database.ReadContext
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.queryDigest
import com.ukkera.pos.inventory.InventoryRecordCodec
import com.ukkera.pos.inventory.InventoryScope
import com.ukkera.pos.inventory.ReservationHold
import com.ukkera.pos.inventory.ReservationHoldReason
import com.ukkera.pos.inventory.ReservationStatus
import com.ukkera.pos.inventory.StockPosition
import com.ukkera.pos.inventory.StockReservation
import com.ukkera.pos.inventory.StockSafety
import com.ukkera.pos.inventory.StockSerialRegistrationStatus
import com.ukkera.pos.inventory.ValuationFact
import com.ukkera.pos.inventory.ValueDestination
import com.ukkera.pos.security.ActionBinding
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.AuthorizedAction
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.PrincipalKind
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlin.time.Instant

internal fun localStockReadHandlers(repository: LocalStockRepository): List<MasterReadHandler> = localStockFrozen(listOf(
    StockItemsRead(repository), StockQuantitiesRead(repository), StockQuantitiesRead(repository, exact = true), StockCostsRead(repository),
    StockReceiptRead(repository, false), StockReceiptRead(repository, true), StockCountRead(repository),
    StockTransferRead(repository, false), StockTransferRead(repository, true),
))

private abstract class StockReadHandler(final override val key: ReadKey) : MasterReadHandler {
    final override fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean =
        profile == LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS && LocalMasterStockTransferRpcKeysV1.accepts(key)

    final override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        localStockCheck(context.key == key && context.scope == view.scope && queryDigest(context.scope, context.actorId, key, normalized) == context.semanticDigest,
            LocalStockFailure.STORAGE_INCONSISTENT)
        if (context.key in stockRelationalReadKeys && repositoryForRelational()?.isRelationalCurrent(view) == true)
            return prepareStockRelationalRead(checkNotNull(repositoryForRelational()), normalized, view, context)
        return prepareStock(normalized, view, context)
    }
    protected open fun repositoryForRelational(): LocalStockRepository? = null
    protected abstract suspend fun prepareStock(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead
}

private fun stockReadRequirement(context: ReadContext, action: SecurityAction, id: DomainId, revision: Revision): AuthorizationRequirement =
    AuthorizationRequirement(Permission(action, PermissionVerb.PERFORM), context.scope,
        ActionBinding(id, revision, context.semanticDigest, reasonCode = "stock.read"))

private fun preparedStockRead(context: ReadContext, requirements: List<AuthorizationRequirement>, projection: (UtcInstant) -> StoredBytes): PreparedRead =
    object : PreparedRead() {
        override val requirements: List<AuthorizationRequirement> = localStockFrozen(requirements)
        override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes {
            localStockCheck(authorizations.size == requirements.size && authorizations.zip(requirements).all { (authorization, requirement) ->
                authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE && authorization.isBoundTo(requirement, context.actorId)
            }, LocalStockFailure.STORAGE_INCONSISTENT)
            return projection(observedAt)
        }
    }

private suspend fun readStockItem(repository: LocalStockRepository, view: CommandReadView, skuId: DomainId): LocalStockRecord<StockItemWire>? {
    val record = repository.record(view, LocalStockStoreProfile.itemKey(skuId), StockItemWire.serializer())
    val claim = view.claim(LocalStockStoreProfile.itemClaim(skuId))
    localStockCheck(if (record == null) claim == null else claim == skuId, LocalStockFailure.STORAGE_INCONSISTENT)
    record?.value?.let { item ->
        requireStockItemIndex(item, view.scope, skuId)
    }
    return record
}

private fun normalizePage(input: StoredBytes, withSku: Boolean): StoredBytes {
    val value = LocalStockCommandCodec.page(input)
    localStockCheck((value.id != null) == withSku)
    return LocalStockCommandCodec.page(value.id, value.after, value.limit)
}

private class StockItemsRead(private val repository: LocalStockRepository) : StockReadHandler(LocalStockStoreProfile.ITEMS) {
    override fun repositoryForRelational(): LocalStockRepository = repository
    override fun normalize(input: StoredBytes): StoredBytes = normalizePage(input, false)
    override suspend fun prepareStock(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        val request = LocalStockCommandCodec.page(normalized)
        val page = view.aggregateKeys(LocalStockStoreProfile.ITEM, request.after, request.limit)
        localStockCheck(page.keys.size <= request.limit && page.keys.all { it.kind == LocalStockStoreProfile.ITEM } &&
            page.keys.map { it.id }.distinct().size == page.keys.size, LocalStockFailure.STORAGE_INCONSISTENT)
        val items = page.keys.map { key -> readStockItem(repository, view, key.id) ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT) }
        val requirements = items.map { stockReadRequirement(context, SecurityAction.INVENTORY_QUANTITY_READ, it.value.skuId, it.row.revision) }
            .ifEmpty { listOf(stockReadRequirement(context, SecurityAction.INVENTORY_QUANTITY_READ, context.scope.branchId, Revision(0))) }
        return preparedStockRead(context, requirements) {
            stockEncode(StockItemsReadWire(1, items.map { StockItemReadWire(it.value.skuId, it.row.revision, it.value.unitId,
                it.value.quantityScale, it.value.positions.size) }, page.nextAfter), StockItemsReadWire.serializer())
        }
    }
}

private class StockQuantitiesRead(private val repository: LocalStockRepository, private val exact: Boolean = false) : StockReadHandler(
    if (exact) LocalStockStoreProfile.QUANTITY_POSITION else LocalStockStoreProfile.QUANTITIES,
) {
    override fun repositoryForRelational(): LocalStockRepository = repository
    override fun normalize(input: StoredBytes): StoredBytes = if (exact) LocalStockCommandCodec.position(input).let {
        LocalStockCommandCodec.position(it.skuId, it.positionId)
    } else normalizePage(input, true)
    override suspend fun prepareStock(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        val coordinate = if (exact) LocalStockCommandCodec.position(normalized) else null
        val request = coordinate?.let { StockPageWire(it.skuId, null, 1) } ?: LocalStockCommandCodec.page(normalized)
        val skuId = requireNotNull(request.id)
        val item = readStockItem(repository, view, skuId)
        // Serial availability is a complete current-custody fact, never a selected-row guess.
        // The full loader also validates the permanent origin/registration and active claims.
        val serialCut = if (item?.value?.positions?.any { it.serialId != null } == true) repository.observe(view, listOf(skuId)) else null
        if (serialCut != null) {
            val completeItem = serialCut.state.items[skuId] ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
            localStockCheck(completeItem.row.revision == item?.row?.revision && completeItem.row.digest == item?.row?.digest,
                LocalStockFailure.STORAGE_INCONSISTENT)
        }
        val refs = item?.value?.positions.orEmpty().map { it to LocalStockStoreProfile.positionKey(it.key(view.scope)).id }.sortedBy { it.second.value }
            .filter { if (coordinate != null) it.second == coordinate.positionId else request.after == null || it.second.value > request.after.value }
            .take(request.limit + 1)
        val selected = refs.take(request.limit)
        val nextAfter = if (refs.size > request.limit) selected.last().second else null
        val positions = selected.map { (reference, id) ->
            if (serialCut != null) {
                val row = serialCut.state.positions[reference.key(view.scope)]
                    ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
                Triple(id, row.row.revision, row.value)
            } else {
                val row = repository.record(view, AggregateKey(LocalStockStoreProfile.POSITION, id), StockPositionWire.serializer())
                    ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
                localStockCheck(row.value.position == reference, LocalStockFailure.STORAGE_INCONSISTENT)
                val value = InventoryRecordCodec.decodePosition(row.value.encoded, reference.key(view.scope))
                localStockCheck(value.onHand.unitId == item?.value?.unitId && value.onHand.scale == item?.value?.quantityScale, LocalStockFailure.STORAGE_INCONSISTENT)
                Triple(id, row.row.revision, value)
            }
        }
        val reservations = serialCut?.snapshot?.reservations?.toMutableList() ?: mutableListOf<StockReservation>()
        for (id in if (serialCut == null) item?.value?.activeReservationIds.orEmpty() else emptyList()) {
            val raw = repository.record(view, LocalStockStoreProfile.reservationKey(id), StockReservationWire.serializer())
                ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
            localStockCheck(view.claim(LocalStockStoreProfile.reservationClaim(id)) == id, LocalStockFailure.STORAGE_INCONSISTENT)
            val reservation = InventoryRecordCodec.decodeReservation(raw.value.encoded, InventoryScope(context.scope.companyId, context.scope.branchId), id, raw.value.revision)
            localStockCheck(reservation.retainsStockClaim && reservation.allocations.any { it.position.pool.skuId == skuId }, LocalStockFailure.STORAGE_INCONSISTENT)
            for (allocation in reservation.allocations) localStockCheck(view.claim(LocalStockStoreProfile.allocationClaim(allocation.allocationId)) == id,
                LocalStockFailure.STORAGE_INCONSISTENT)
            reservations += reservation
        }
        // Individual original producers are not a substitute for this current aggregate cut
        // check. A real count shortage retains protected HELD claims above physical custody.
        val claimsByPosition = positions.associate { (_, _, position) -> position.key to stockQuantityClaims(position, reservations) }
        return preparedStockRead(context, listOf(stockReadRequirement(context, SecurityAction.INVENTORY_QUANTITY_READ, skuId, item?.row?.revision ?: Revision(0)))) { now ->
            val rows = positions.map { (id, revision, position) ->
                val claims = claimsByPosition.getValue(position.key)
                val reserved = claims.reserved
                val available = if (position.isSellableAt(now.value.toEpochMilliseconds()) && reserved < position.onHand) position.onHand - reserved
                    else Quantity(position.onHand.unitId, 0, position.onHand.scale)
                StockQuantityReadWire(id, StockPositionRefWire.from(position.key), revision, position.onHand, reserved, available,
                    position.safety.name, position.expiresAtEpochMillis?.let { UtcInstant(Instant.fromEpochMilliseconds(it)) },
                    position.expiresAtEpochMillis?.let { now.value.toEpochMilliseconds() >= it } ?: false,
                    claims.held, claims.shortage,
                    position.key.serialId?.let { serialId ->
                        serialCut?.state?.serials?.get(serialId)?.value?.serialCode
                            ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
                    }, position.serialRegistration?.name)
            }
            val version = if (rows.any { it.position.serialId != null }) 3 else 2
            stockEncode(StockQuantitiesReadWire(version, skuId, item?.row?.revision, item?.value?.unitId, item?.value?.quantityScale, rows, nextAfter),
                StockQuantitiesReadWire.serializer())
        }
    }
}

private data class StockQuantityClaims(val reserved: Quantity, val held: Quantity, val shortage: Quantity)

private fun stockQuantityClaims(position: StockPosition, reservations: List<StockReservation>): StockQuantityClaims {
    val zero = Quantity(position.onHand.unitId, 0, position.onHand.scale)
    val claimants = reservations.filter { reservation -> reservation.retainsStockClaim &&
        reservation.allocations.any { it.position == position.key } }
    fun quantity(reservation: StockReservation): Quantity = reservation.allocations.filter { it.position == position.key }.fold(zero) { total, allocation ->
        localStockCheck(allocation.quantity.unitId == zero.unitId && allocation.quantity.scale == zero.scale && allocation.quantity.scaled > 0,
            LocalStockFailure.STORAGE_INCONSISTENT)
        total + allocation.quantity
    }
    val reserved = claimants.fold(zero) { total, reservation -> total + quantity(reservation) }
    val held = claimants.filter { it.status == ReservationStatus.HELD }.fold(zero) { total, reservation -> total + quantity(reservation) }
    val safetyReason = when (position.safety) {
        StockSafety.CLEAR -> null
        StockSafety.RECALLED -> ReservationHoldReason.RECALL
        StockSafety.EXPIRED -> ReservationHoldReason.EXPIRY
        StockSafety.COUNT_REVIEW -> ReservationHoldReason.COUNT_SHORTAGE
    }
    if (safetyReason != null) localStockCheck(claimants.all { it.status == ReservationStatus.HELD &&
        ReservationHold(safetyReason, position.key) in it.holds }, LocalStockFailure.STORAGE_INCONSISTENT)
    val shortage = if (reserved > position.onHand) reserved - position.onHand else zero
    if (shortage.scaled > 0) {
        // A recall/expiry on a DIFFERENT allocation is not evidence for a physical shortage here.
        // Each retained hold's original producer/source correspondence was checked by the loader.
        localStockCheck(claimants.all { reservation -> reservation.status == ReservationStatus.HELD && reservation.holds.any {
            it.position == position.key && (it.reason == ReservationHoldReason.COUNT_SHORTAGE || it.reason == ReservationHoldReason.UNAVAILABLE)
        } }, LocalStockFailure.STORAGE_INCONSISTENT)
    }
    return StockQuantityClaims(reserved, held, shortage)
}

private class StockCostsRead(private val repository: LocalStockRepository) : StockReadHandler(LocalStockStoreProfile.COSTS) {
    override fun repositoryForRelational(): LocalStockRepository = repository
    override fun normalize(input: StoredBytes): StoredBytes = normalizePage(input, true)
    override suspend fun prepareStock(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        val request = LocalStockCommandCodec.page(normalized)
        val skuId = requireNotNull(request.id)
        // Current value is a complete linked component, not a plausible standalone pool/header.
        val cut = repository.observe(view, listOf(skuId))
        val item = cut.state.items[skuId]
        val refs = item?.value?.pools.orEmpty().map { it to LocalStockStoreProfile.poolKey(it.key(view.scope)).id }.sortedBy { it.second.value }
            .filter { request.after == null || it.second.value > request.after.value }.take(request.limit + 1)
        val selected = refs.take(request.limit)
        val rows = selected.map { (reference, id) ->
            val pool = cut.state.pools[reference.key(view.scope)] ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
            localStockCheck(pool.value.value.unitId == item?.value?.unitId && pool.value.value.scale == item?.value?.quantityScale,
                LocalStockFailure.STORAGE_INCONSISTENT)
            StockCostReadWire(id, reference.warehouseId, pool.record.row.revision, pool.value.value.revision, pool.value.value.onHand, pool.value.value.carryingValue)
        }
        return preparedStockRead(context, listOf(
            stockReadRequirement(context, SecurityAction.INVENTORY_QUANTITY_READ, skuId, item?.row?.revision ?: Revision(0)),
            stockReadRequirement(context, SecurityAction.INVENTORY_COST_READ, skuId, item?.row?.revision ?: Revision(0)),
        )) { stockEncode(StockCostsReadWire(1, skuId, item?.row?.revision, rows, if (refs.size > request.limit) selected.last().second else null), StockCostsReadWire.serializer()) }
    }
}

private class StockReceiptRead(private val repository: LocalStockRepository, private val includeCosts: Boolean) : StockReadHandler(
    if (includeCosts) LocalStockStoreProfile.RECEIPT_COSTS else LocalStockStoreProfile.RECEIPT,
) {
    override fun normalize(input: StoredBytes): StoredBytes = LocalStockCommandCodec.document(LocalStockCommandCodec.document(input))
    override suspend fun prepareStock(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        val id = LocalStockCommandCodec.document(normalized)
        val row = stockDocument(repository, view, LocalStockStoreProfile.RECEIPT_DOCUMENT, id, StockReceiptWire.serializer())
        val actions = listOf(SecurityAction.PURCHASING_READ, SecurityAction.INVENTORY_QUANTITY_READ) +
            if (includeCosts) listOf(SecurityAction.INVENTORY_COST_READ) else emptyList()
        return preparedStockRead(context, actions.map { stockReadRequirement(context, it, id, row?.row?.revision ?: Revision(0)) }) {
            val value = row?.let { record ->
                val receipt = record.value
                StockReceiptReadValueWire(id, record.row.revision, receipt.basisId, receipt.basisRevision, receipt.basisKind, receipt.supplierId,
                    receipt.recordedBy, receipt.receivedAt, receipt.reasonCode, includeCosts, receipt.lines.map { line ->
                        val request = line.request
                        StockReceivedReadLineWire(request.lineId, request.allocationId, request.sourceId, request.skuId, request.warehouseId,
                            request.quantity, line.baseQuantity, line.conversion, request.lotId, request.expiresAt, request.disposition,
                            if (includeCosts) request.originalValue else null, line.releasedFromQuarantine,
                            request.serialId, request.serialCode, when {
                                request.serialId == null || request.disposition == LocalReceiptDisposition.REFUSED.name -> null
                                line.serialRegistration == null -> StockSerialRegistrationStatus.LOCAL_PENDING_REGISTRATION.name
                                else -> StockSerialRegistrationStatus.REGISTERED.name
                            })
                    })
            }
            stockEncode(StockReceiptReadWire(if (value?.lines?.any { it.serialId != null } == true) 2 else 1, value), StockReceiptReadWire.serializer())
        }
    }
}

private class StockCountRead(private val repository: LocalStockRepository) : StockReadHandler(LocalStockStoreProfile.COUNT) {
    override fun normalize(input: StoredBytes): StoredBytes = LocalStockCommandCodec.document(LocalStockCommandCodec.document(input))
    override suspend fun prepareStock(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        val id = LocalStockCommandCodec.document(normalized)
        val row = stockDocument(repository, view, LocalStockStoreProfile.COUNT_DOCUMENT, id, StockCountWire.serializer())
        return preparedStockRead(context, listOf(stockReadRequirement(context, SecurityAction.INVENTORY_QUANTITY_READ, id, row?.row?.revision ?: Revision(0)))) {
            val value = row?.let { record ->
                val count = record.value
                StockCountReadValueWire(id, record.row.revision, count.position, count.capturedItemRevision,
                    count.capturedQuantity, count.capturedBy, count.capturedAt, count.reasonCode, count.counted, count.postedBy, count.postedAt,
                    count.postingReasonCode, count.surplusBasis?.basisId)
            }
            stockEncode(StockCountReadWire(if (value?.position?.serialId != null) 2 else 1, value), StockCountReadWire.serializer())
        }
    }
}

private class StockTransferRead(private val repository: LocalStockRepository, private val includeCosts: Boolean) : StockReadHandler(
    if (includeCosts) LocalStockStoreProfile.TRANSFER_COSTS else LocalStockStoreProfile.TRANSFER_DETAIL,
) {
    override fun repositoryForRelational(): LocalStockRepository = repository
    override fun normalize(input: StoredBytes): StoredBytes = LocalStockCommandCodec.document(LocalStockCommandCodec.document(input))
    override suspend fun prepareStock(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        val id = LocalStockCommandCodec.document(normalized)
        val row = stockDocument(repository, view, LocalStockStoreProfile.TRANSFER_DOCUMENT, id, StockTransferWire.serializer())
        val transfer = row?.value
        val input = transfer?.request
        // Serial detail needs the whole current custody/claim cut. Edge correction amounts need
        // both current pools and the full same-SKU event graph, even if the original edge is zero.
        val cut = if (input != null && (input.source.serialId != null || includeCosts && transfer?.owned != null))
            repository.observe(view, listOf(input.source.skuId)) else null
        val owned = transfer?.owned?.value
        val linkedDelta = if (includeCosts && owned != null) {
            val complete = cut ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
            val source = complete.state.pools[owned.sourcePool.key(view.scope)]?.value?.value
                ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
            val destination = complete.state.pools[owned.destinationPool.key(view.scope)]?.value?.value
                ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
            val out = source.facts.filterIsInstance<ValuationFact.TransferOut>().singleOrNull { it.factId == owned.sourceEventId }
                ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
            val into = destination.facts.filterIsInstance<ValuationFact.TransferIn>().singleOrNull { it.factId == owned.destinationEventId }
                ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
            localStockCheck(out.link == into.link && StockOwnedTransferValueWire.from(out.link) == owned,
                LocalStockFailure.STORAGE_INCONSISTENT)
            val delta = source.linkedCorrectionBalances.singleOrNull { it.destination == ValueDestination.Transferred(id, owned.allocationId) }?.value
                ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
            localStockCheck(destination.transferTargets.singleOrNull { it.receiptEventId == owned.destinationEventId }?.targetDelta == delta,
                LocalStockFailure.STORAGE_INCONSISTENT)
            delta
        } else null
        val serial = input?.source?.serialId?.let { serialId ->
            val complete = cut ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
            val origin = complete.state.serials[serialId]?.value ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
            val current = complete.snapshot.positions.filter { it.key.serialId == serialId && it.onHand.scaled != 0L }.singleOrNull()
            StockTransferSerialReadWire(serialId, origin.serialCode,
                if (origin.registration == null) StockSerialRegistrationStatus.LOCAL_PENDING_REGISTRATION.name else StockSerialRegistrationStatus.REGISTERED.name,
                origin.registration?.registrationId, origin.receiptId, origin.lineId, origin.sourceId, origin.originalPosition, origin.receivedAt,
                current?.key?.let(StockPositionRefWire::from), current?.onHand ?: Quantity(origin.baseQuantity.unitId, 0, origin.baseQuantity.scale),
                current?.safety?.name, current?.expiresAtEpochMillis?.let { UtcInstant(Instant.fromEpochMilliseconds(it)) })
        }
        val actions = listOf(SecurityAction.INVENTORY_QUANTITY_READ) + if (includeCosts) listOf(SecurityAction.INVENTORY_COST_READ) else emptyList()
        return preparedStockRead(context, actions.map { stockReadRequirement(context, it, id, row?.row?.revision ?: Revision(0)) }) {
            val value = if (transfer == null || input == null || row == null) null else StockTransferReadValueWire(id, row.row.revision,
                input.sourceEventId, input.destinationEventId, input.source, input.source.copy(warehouseId = input.destinationWarehouseId),
                input.quantity, transfer.transferredBy, transfer.recordedAt, input.reasonCode,
                owned?.allocationId, owned?.sourceRevision, owned?.destinationRevision, owned?.calculationVersion,
                includeCosts, if (includeCosts) transfer.originalValue else null, linkedDelta, serial)
            stockEncode(StockTransferReadWire(1, value), StockTransferReadWire.serializer())
        }
    }
}
