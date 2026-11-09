package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.InventoryRecordCodec
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*

/** Extension on the SAME stock service. No second database, stock map or valuation repository. */
internal fun LocalStockRepository.unpricedArrivals(view: CommandReadView): ArrivalReader = ArrivalReader(this, view)

/** Loan-local bounded hydration. Every current row is cross-checked against its whole retained producer. */
internal class ArrivalReader(private val stock: LocalStockRepository, private val view: CommandReadView) {
    val scope get() = view.scope
    private val rows = linkedMapOf<AggregateKey, ArrivalRow?>()
    private val producers = linkedMapOf<OperationId, RetainedCommandEvidence>()
    private val visiting = mutableSetOf<OperationId>()
    private val stockProducers = linkedMapOf<OperationId, RetainedCommandEvidence>()
    private val stockVisiting = mutableSetOf<OperationId>()
    private val stockBudget = StockLoadBudget()
    private val purchaseSources = mutableMapOf<OperationId, LocalStockApprovedPurchaseOrderV1>()
    private var loadedBytes = 0L

    private fun account(bytes: Long) {
        check(bytes >= 0 && loadedBytes in 0L..16_777_216L)
        if (bytes > 16_777_216L - loadedBytes) throw ArrivalHydrationCapacityUnavailable()
        loadedBytes += bytes
    }

    suspend fun row(key: AggregateKey): ArrivalRow? {
        check(key.kind in arrivalKinds)
        if (key in rows) return rows.getValue(key)
        if (rows.size >= 512) throw ArrivalHydrationCapacityUnavailable()
        val row = view.aggregate(key)
        if (row == null) {
            val claim = when (key.kind) {
                LocalUnpricedArrivalProfileV1.SOURCE_IDENTITY -> arrivalSourceClaim(key.id)
                LocalUnpricedArrivalProfileV1.LOT_GUARD -> arrivalLotClaim(key)
                LocalUnpricedArrivalProfileV1.SERIAL_GUARD -> null // Exact alias check is in serialCode/serialId below.
                else -> arrivalDocumentClaim(key.kind, key.id)
            }
            check(claim == null || view.claim(claim) == null)
            rows[key] = null
            return null
        }
        check(row.key == key && row.payload.size <= 65_536 && sha256(row.payload.copyBytes()) == row.digest)
        account(row.payload.size.toLong())
        val value = arrivalDecodeRecord(row.payload)
        check(value.scope == scope && arrivalBodyKey(value.body) == key && value.revision == row.revision && row.schema == LocalUnpricedArrivalProfileV1.schema(key.kind))
        val evidence = producer(value.producer.operationId)
        val write = evidence.aggregateWrites.singleOrNull { it.key == key } ?: error("Arrival producer lost a row")
        check(write.storageGeneration == row.revision && write.schema == row.schema && write.payloadDigest == row.digest && write.payload == row.payload)
        when (val body = value.body) {
            is ArrivalBodyV1.Original -> {
                check(view.claim(arrivalDocumentClaim(key.kind, key.id)) == key.id)
                check(view.claim(arrivalDeliveryClaim(scope, body.request)) == body.request.arrivalId)
            }
            is ArrivalBodyV1.State, is ArrivalBodyV1.Event -> check(view.claim(arrivalDocumentClaim(key.kind, key.id)) == key.id)
            is ArrivalBodyV1.SourceIdentity -> {
                check(view.claim(arrivalSourceClaim(body.sourceId)) == body.sourceId && view.claim(arrivalUnitClaim(body.custodyUnitId)) == body.sourceId &&
                    view.claim(arrivalLineClaim(body.lineId)) == body.arrivalId)
            }
            is ArrivalBodyV1.SerialGuard -> check(view.claim(arrivalSerialClaim(body)) == body.sourceId)
            is ArrivalBodyV1.LotGuard -> check(view.claim(arrivalLotClaim(key)) == key.id)
        }
        return ArrivalRow(row, value).also { rows[key] = it }
    }

    private suspend fun producer(id: OperationId): RetainedCommandEvidence {
        producers[id]?.let { return it }
        check(visiting.add(id)) { "Arrival producer cycle" }
        try {
            if (producers.size + visiting.size > 256) throw ArrivalHydrationCapacityUnavailable()
            val evidence = view.retainedCommand(id) ?: error("Arrival producer unavailable")
            check(evidence.scope == scope && evidence.operationId == id)
            val bytes = evidence.normalized.size.toLong() + evidence.result.size + evidence.aggregateWrites.sumOf { it.payload.size.toLong() }
            check(bytes <= 1_081_344L)
            account(bytes)
            requireArrivalProducer(this, evidence)
            producers[id] = evidence
            return evidence
        } finally { visiting.remove(id) }
    }

    suspend fun historical(reference: ArrivalRowRefV1, child: RetainedCommandEvidence? = null): ArrivalHistorical {
        check(reference.kind in arrivalKinds && reference.revision.value >= 1)
        val evidence = producer(reference.operationId)
        if (child != null) check(evidence.operationId != child.operationId && evidence.recordedAt <= child.recordedAt &&
            (evidence.assignmentId != child.assignmentId || evidence.authorityEpoch != child.authorityEpoch || evidence.sequence < child.sequence))
        val row = evidence.aggregateWrites.singleOrNull { it.key == reference.key() } ?: error("Arrival predecessor unavailable")
        check(row.storageGeneration == reference.revision && row.payloadDigest.value == reference.digest)
        return ArrivalHistorical(row, arrivalDecodeRecord(row.payload))
    }

    suspend fun historical(row: ArrivalRow): ArrivalHistorical = historical(row.reference())

    suspend fun serialCode(code: String): ArrivalRow? {
        requireStockSerialCode(code)
        val row = row(arrivalSerialCodeKey(code))
        val owner = view.claim(arrivalSerialCodeClaim(code))
        check(if (row == null) owner == null else owner == (row.value.body as ArrivalBodyV1.SerialGuard).sourceId)
        if (row != null) {
            val guard = row.value.body as ArrivalBodyV1.SerialGuard
            check(guard.alias == ArrivalSerialAliasV1.CODE && guard.serialCode == code)
            guard.serialId?.let { id ->
                val paired = this.row(arrivalSerialIdKey(id)) ?: error("Arrival serial alias unavailable")
                check(paired.value.body == guard.copy(alias = ArrivalSerialAliasV1.ID) && view.claim(arrivalSerialIdClaim(id)) == guard.sourceId)
            }
            requireSerialCustody(guard)
        }
        return row
    }

    suspend fun serialId(id: DomainId): ArrivalRow? {
        val row = row(arrivalSerialIdKey(id))
        val owner = view.claim(arrivalSerialIdClaim(id))
        check(if (row == null) owner == null else owner == (row.value.body as ArrivalBodyV1.SerialGuard).sourceId)
        if (row != null) {
            val guard = row.value.body as ArrivalBodyV1.SerialGuard
            check(guard.alias == ArrivalSerialAliasV1.ID && guard.serialId == id)
            val paired = this.row(arrivalSerialCodeKey(guard.serialCode)) ?: error("Arrival serial alias unavailable")
            check(paired.value.body == guard.copy(alias = ArrivalSerialAliasV1.CODE) && view.claim(arrivalSerialCodeClaim(guard.serialCode)) == guard.sourceId)
            requireSerialCustody(guard)
        }
        return row
    }

    private suspend fun requireSerialCustody(guard: ArrivalBodyV1.SerialGuard) {
        val source = row(arrivalKey(LocalUnpricedArrivalProfileV1.SOURCE_IDENTITY, guard.sourceId)) ?: error("Arrival serial source unavailable")
        val identity = source.value.body as ArrivalBodyV1.SourceIdentity
        check(identity.arrivalId == guard.arrivalId && identity.lineId == guard.lineId && identity.custodyUnitId == guard.custodyUnitId)
        val original = this.row(arrivalKey(LocalUnpricedArrivalProfileV1.ARRIVAL, guard.arrivalId)) ?: error("Arrival serial original unavailable")
        val request = (original.value.body as ArrivalBodyV1.Original).request
        check(request.lines.single { it.lineId == guard.lineId }.skuId == guard.skuId)
        val state = row(arrivalKey(LocalUnpricedArrivalProfileV1.STATE, guard.arrivalId)) ?: error("Arrival serial custody unavailable")
        val body = state.value.body as ArrivalBodyV1.State
        custodyRows(original, state)
        val slice = body.slices.single { it.sourceId == guard.sourceId && it.custodyUnitId == guard.custodyUnitId }
        check(slice.serialCode == guard.serialCode && slice.serialId == guard.serialId)
        // Rejection/dispatch do not clear this guard or invent a new physical origin.
    }

    suspend fun lot(skuId: DomainId, lotId: DomainId): ArrivalRow? {
        val row = row(arrivalLotKey(skuId, lotId)) ?: return null
        val guard = row.value.body as ArrivalBodyV1.LotGuard
        check(guard.skuId == skuId && guard.lotId == lotId)
        val original = this.row(arrivalKey(LocalUnpricedArrivalProfileV1.ARRIVAL, guard.originArrivalId)) ?: error("Arrival lot original unavailable")
        val body = original.value.body as ArrivalBodyV1.Original
        val line = body.request.lines.single { it.sourceId == guard.originSourceId }
        val item = body.request.items.single { it.skuId == line.skuId }
        check(line.skuId == skuId && item.productId == guard.productId && item.baseUnitId == guard.baseUnitId && item.quantityScale == guard.quantityScale)
        val state = this.row(arrivalKey(LocalUnpricedArrivalProfileV1.STATE, guard.originArrivalId)) ?: error("Arrival lot custody unavailable")
        custodyRows(original, state)
        val partitions = (state.value.body as ArrivalBodyV1.State).slices.filter { it.lineId == line.lineId && it.lotId == lotId }
        check(partitions.isNotEmpty() && partitions.all { it.expiresAt == null || it.expiresAt == guard.expiresAt })
        return row
    }

    /** Current immutable facts must still be present, not just their retained AFTER witnesses.
     * Does not recurse into guards: serial/lot interlocks can safely use this core closure too. */
    private suspend fun custodyRows(original: ArrivalRow, state: ArrivalRow): Pair<List<ArrivalRow>, List<ArrivalRow>> {
        val origin = original.value.body as ArrivalBodyV1.Original
        val current = state.value.body as ArrivalBodyV1.State
        val id = origin.request.arrivalId
        check(current.original == original.reference() && current.arrivalId == id && current.events.size <= 64 &&
            current.events.map { it.id }.distinct().size == current.events.size && state.row.revision.value == current.events.size.toLong() + 1)
        requireArrivalPhysicalState(origin, current.slices)
        val events = current.events.map { ref ->
            check(ref.kind == LocalUnpricedArrivalProfileV1.EVENT)
            val event = row(ref.key()) ?: error("Arrival transition unavailable")
            check(event.reference() == ref && (event.value.body as ArrivalBodyV1.Event).original == original.reference())
            event
        }
        val sources = current.slices.map { slice ->
            val source = row(arrivalKey(LocalUnpricedArrivalProfileV1.SOURCE_IDENTITY, slice.sourceId)) ?: error("Arrival source unavailable")
            check(source.value.body == arrivalSourceIdentity(id, slice))
            source
        }
        return events to sources
    }

    suspend fun guards(original: LocalRecordUnpricedArrivalV1, slices: List<ArrivalSliceV1>): Map<AggregateKey, ArrivalRow?> {
        val result = linkedMapOf<AggregateKey, ArrivalRow?>()
        for (slice in slices) {
            slice.serialCode?.let { code -> val key = arrivalSerialCodeKey(code); if (key !in result) result[key] = serialCode(code) }
            slice.serialId?.let { id -> val key = arrivalSerialIdKey(id); if (key !in result) result[key] = serialId(id) }
            slice.lotId?.let { lot ->
                val sku = original.lines.single { it.lineId == slice.lineId }.skuId
                val key = arrivalLotKey(sku, lot); if (key !in result) result[key] = this.lot(sku, lot)
            }
        }
        return result
    }

    suspend fun arrival(id: DomainId): ArrivalCut? {
        val original = this.row(arrivalKey(LocalUnpricedArrivalProfileV1.ARRIVAL, id))
        val state = row(arrivalKey(LocalUnpricedArrivalProfileV1.STATE, id))
        check((original == null) == (state == null))
        if (original == null || state == null) return null
        val origin = original.value.body as ArrivalBodyV1.Original
        val current = state.value.body as ArrivalBodyV1.State
        val (events, sources) = custodyRows(original, state)
        val guards = guards(origin.request, current.slices)
        check(guards.values.all { it != null })
        for (slice in current.slices) slice.lotId?.let { lot ->
            val sku = origin.request.lines.single { it.lineId == slice.lineId }.skuId
            val guard = guards.getValue(arrivalLotKey(sku, lot))!!.value.body as ArrivalBodyV1.LotGuard
            check(slice.expiresAt == null || guard.expiresAt == slice.expiresAt)
        }
        return ArrivalCut(original, state, events, sources, guards.values.filterNotNull())
    }

    /** Source/unit/line claims are permanent. An orphan is uncertainty, not an unused identity. */
    suspend fun sourceUsed(sourceId: DomainId, unitId: DomainId, lineId: DomainId?): Boolean {
        val source = row(arrivalKey(LocalUnpricedArrivalProfileV1.SOURCE_IDENTITY, sourceId))
        val unitOwner = view.claim(arrivalUnitClaim(unitId))
        unitOwner?.let { owner ->
            val actual = row(arrivalKey(LocalUnpricedArrivalProfileV1.SOURCE_IDENTITY, owner)) ?: error("Arrival unit source unavailable")
            check((actual.value.body as ArrivalBodyV1.SourceIdentity).custodyUnitId == unitId)
        }
        val lineOwner = lineId?.let { view.claim(arrivalLineClaim(it)) }
        lineOwner?.let { owner ->
            val actual = row(arrivalKey(LocalUnpricedArrivalProfileV1.ARRIVAL, owner)) ?: error("Arrival line original unavailable")
            check((actual.value.body as ArrivalBodyV1.Original).request.lines.any { it.lineId == lineId })
        }
        return source != null || unitOwner != null || lineOwner != null
    }

    suspend fun deliveryUsed(request: LocalRecordUnpricedArrivalV1): Boolean {
        val owner = view.claim(arrivalDeliveryClaim(scope, request)) ?: return false
        val row = row(arrivalKey(LocalUnpricedArrivalProfileV1.ARRIVAL, owner)) ?: error("Arrival delivery original unavailable")
        check(arrivalDeliveryClaim(scope, (row.value.body as ArrivalBodyV1.Original).request) == arrivalDeliveryClaim(scope, request))
        return true
    }

    private suspend fun stockProducer(id: OperationId): RetainedCommandEvidence {
        stockProducers[id]?.let { return it }
        check(stockVisiting.add(id)) { "Stock producer cycle" }
        try {
            val evidence = view.retainedCommand(id) ?: error("Observed stock producer unavailable")
            check(evidence.scope == scope && evidence.operationId == id)
            stockBudget.producer(evidence)
            stock.requireProducer(view, evidence, stockBudget, purchaseSources)
            requireStockSourceCrosswalk(view, evidence, stockBudget) { previous -> stockProducer(previous) }
            stockProducers[id] = evidence
            return evidence
        } finally { stockVisiting.remove(id) }
    }

    suspend fun requireStockObservations(child: RetainedCommandEvidence, original: ArrivalBodyV1.Original,
        selected: List<ArrivalSliceV1>, authority: ArrivalAuthorityWireV1) {
        val skuIds = selected.map { original.request.lines.single { line -> line.lineId == it.lineId }.skuId }.toSet()
        check(authority.stock.map { it.key() }.distinct().size == authority.stock.size &&
            authority.stock.all { it.kind == LocalStockStoreProfile.ITEM || it.kind == LocalStockStoreProfile.POSITION } &&
            authority.absentStockItems.distinct().size == authority.absentStockItems.size)
        val observations = linkedMapOf<AggregateKey, StockRecordWire>()
        for (ref in authority.stock) {
            val evidence = stockProducer(ref.operationId)
            check(evidence.operationId != child.operationId && evidence.recordedAt <= child.recordedAt &&
                (evidence.assignmentId != child.assignmentId || evidence.authorityEpoch != child.authorityEpoch || evidence.sequence < child.sequence))
            val row = evidence.aggregateWrites.singleOrNull { it.key == ref.key() } ?: error("Observed stock row unavailable")
            check(row.storageGeneration == ref.revision && row.payloadDigest.value == ref.digest)
            val envelope = stockDecode(row.payload, StockRecordWire.serializer())
            requireRetainedStockEnvelope(evidence, row.key, row.storageGeneration, envelope)
            observations[ref.key()] = envelope
        }
        val itemRefs = authority.stock.filter { it.kind == LocalStockStoreProfile.ITEM }
        check(itemRefs.map { it.id }.toSet().intersect(authority.absentStockItems.toSet()).isEmpty() &&
            itemRefs.map { it.id }.toSet() + authority.absentStockItems.toSet() == skuIds)
        val expectedPositions = mutableSetOf<AggregateKey>()
        for (item in authority.items) {
            val ref = itemRefs.singleOrNull { it.id == item.selection.skuId }
            check(item.selection.stockStorageRevision == ref?.revision)
            if (ref != null) {
                val body = stockBody(observations.getValue(ref.key()).body, StockItemWire.serializer())
                requireStockItemIndex(body, scope, item.selection.skuId)
                check(body.unitId == item.selection.baseUnitId && body.quantityScale == item.selection.quantityScale)
                check(body.positions.all { (it.serialId != null) == (item.tracking == LocalArrivalTrackingV1.SERIAL) })
                val lots = selected.filter { original.request.lines.single { line -> line.lineId == it.lineId }.skuId == item.selection.skuId }
                    .mapNotNull { it.lotId }.toSet()
                expectedPositions += body.positions.filter { it.lotId in lots }.map { LocalStockStoreProfile.positionKey(it.key(scope)) }
            }
        }
        check(observations.keys.filter { it.kind == LocalStockStoreProfile.POSITION }.toSet() == expectedPositions)
        for ((key, envelope) in observations.filterKeys { it.kind == LocalStockStoreProfile.POSITION }) {
            val body = stockBody(envelope.body, StockPositionWire.serializer())
            check(LocalStockStoreProfile.positionKey(body.position.key(scope)) == key)
            val position = InventoryRecordCodec.decodePosition(body.encoded, body.position.key(scope))
            for (slice in selected.filter { it.lotId == position.key.lotId &&
                original.request.lines.single { line -> line.lineId == it.lineId }.skuId == position.key.pool.skuId }) {
                check(slice.quantity.unitId == position.onHand.unitId && slice.quantity.scale == position.onHand.scale &&
                    (slice.expiresAt == null || slice.expiresAt.value.toEpochMilliseconds() == position.expiresAtEpochMillis))
            }
        }
    }

    fun resources(): Set<RecoveryResourceKey> = buildSet {
        // Also retain exact absent coordinates consulted for a prospective new guard/document.
        rows.keys.forEach { add(RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, it.id)) }
        for (row in rows.values.filterNotNull()) {
            add(RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, row.row.key.id))
            add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, row.value.producer.actorId))
            when (val body = row.value.body) {
                is ArrivalBodyV1.Original -> addAll(arrivalRequestResources(scope, body.request))
                is ArrivalBodyV1.SourceIdentity -> listOf(body.arrivalId, body.lineId, body.rootSourceId, body.sourceId, body.custodyUnitId).forEach {
                    add(RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, it)) }
                is ArrivalBodyV1.SerialGuard -> {
                    listOfNotNull(body.skuId, body.serialId, localStockId("serial-code", body.serialCode)).forEach { add(RecoveryResourceKey(RecoveryResourceKind.STOCK, it)) }
                    add(RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, body.arrivalId))
                }
                is ArrivalBodyV1.LotGuard -> listOf(body.skuId, body.lotId).forEach { add(RecoveryResourceKey(RecoveryResourceKind.STOCK, it)) }
                else -> Unit
            }
        }
        for (evidence in producers.values) {
            add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, evidence.originalActorId))
            for (write in evidence.aggregateWrites) when (val body = arrivalDecodeRecord(write.payload).body) {
                is ArrivalBodyV1.Original -> addAll(arrivalAuthorityResources(body.authority))
                is ArrivalBodyV1.Event -> addAll(arrivalAuthorityResources(body.authority))
                else -> Unit
            }
        }
    }
}

internal fun arrivalStockObservations(cut: LocalStockReadCut, request: LocalRecordUnpricedArrivalV1, slices: List<ArrivalSliceV1>): List<StockObservedWire> {
    val skuIds = slices.map { request.lines.single { line -> line.lineId == it.lineId }.skuId }.toSet()
    val lots = slices.mapNotNull { slice -> slice.lotId?.let { request.lines.single { line -> line.lineId == slice.lineId }.skuId to it } }.toSet()
    val positionKeys = cut.state.positions.keys.filter { it.pool.skuId to it.lotId in lots }.map(LocalStockStoreProfile::positionKey).toSet()
    return stockReadObservations(cut.state).filter { it.kind == LocalStockStoreProfile.ITEM && it.id in skuIds || it.key() in positionKeys }
}

internal data class ArrivalReceiptIdentity(val skuId: DomainId, val lotId: DomainId?, val serialId: DomainId?, val serialCode: String?,
    val sourceId: DomainId, val lineId: DomainId)
internal class ArrivalReceiptGuards(val identityAlreadyHeld: Boolean, private val lots: Map<Pair<DomainId, DomainId>, ArrivalBodyV1.LotGuard>,
    val resources: Set<RecoveryResourceKey>) {
    fun requireCompatible(item: LocalStockItemAuthority, lotId: DomainId?, expiresAt: UtcInstant?) {
        val lot = lotId?.let { lots[item.skuId to it] } ?: return
        localStockCheck(lot.productId == item.productId && lot.baseUnitId == item.baseUnitId && lot.quantityScale == item.quantityScale &&
            (lot.expiresAt == null || lot.expiresAt == expiresAt), LocalStockFailure.INVALID_LOT)
    }
}

/** Called by every ordinary receipt/opening profile in the SAME preparation transaction. */
internal suspend fun LocalStockRepository.unpricedArrivalReceiptGuards(view: CommandReadView, identities: List<ArrivalReceiptIdentity>): ArrivalReceiptGuards = try {
    val reader = unpricedArrivals(view)
    val readerV2 = unpricedArrivalsV2(view)
    var conflict = false
    val lots = linkedMapOf<Pair<DomainId, DomainId>, ArrivalBodyV1.LotGuard>()
    for (identity in identities) {
        // The v2 reader authenticates either genuine profile without interpreting a shared
        // claim as a missing v1/v2 row. Ordinary receiving has no custody-unit coordinate.
        if (readerV2.row(arrivalKeyV2(LocalUnpricedArrivalProfileV2.SOURCE_IDENTITY, identity.sourceId)) != null ||
            view.claim(arrivalSourceClaim(identity.sourceId)) != null) conflict = true
        view.claim(arrivalLineClaim(identity.lineId))?.let { owner ->
            val original = readerV2.row(arrivalKeyV2(LocalUnpricedArrivalProfileV2.ARRIVAL, owner))
            if (original != null) check((original.value.body as ArrivalBodyV2.Original).request.lines.any { it.lineId == identity.lineId })
            else {
                val foreign = reader.row(arrivalKey(LocalUnpricedArrivalProfileV1.ARRIVAL, owner)) ?: inconsistentStock()
                check((foreign.value.body as ArrivalBodyV1.Original).request.lines.any { it.lineId == identity.lineId })
            }
            conflict = true
        }
        identity.serialId?.let { if (readerV2.serialId(it) != null || view.claim(arrivalSerialIdClaim(it)) != null) conflict = true }
        identity.serialCode?.let { if (readerV2.serialCode(it) != null || view.claim(arrivalSerialCodeClaim(it)) != null) conflict = true }
        identity.lotId?.let { lot ->
            if (readerV2.lot(identity.skuId, lot) != null) conflict = true
            else reader.lot(identity.skuId, lot)?.let { lots[identity.skuId to lot] = it.value.body as ArrivalBodyV1.LotGuard }
        }
    }
    ArrivalReceiptGuards(conflict, lots, reader.resources() + readerV2.resources() + identities.map {
        RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, it.lineId)
    })
} catch (_: ArrivalHydrationCapacityUnavailable) {
    // Translate only this owned resource-admission signal, never corruption or a broad BOUND error.
    throw LocalStockHydrationCapacityUnavailable()
} catch (_: ArrivalHydrationCapacityUnavailableV2) {
    throw LocalStockHydrationCapacityUnavailable()
}
