package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*

/** Extension on the SAME stock service. No second database, stock map or valuation repository. */
internal fun LocalStockRepository.unpricedArrivalsV2(view: CommandReadView): ArrivalReaderV2 = ArrivalReaderV2(this, view)

/** Loan-local bounded hydration. Every current row is cross-checked against its whole retained producer. */
internal class ArrivalReaderV2 private constructor(private val stock: LocalStockRepository,
    private val view: CommandReadView?, private val before: RuntimeHistoricalCommand?) : ArrivalProducerAccessV2 {
    constructor(stock: LocalStockRepository, view: CommandReadView) : this(stock, view, null)
    constructor(stock: LocalStockRepository, before: RuntimeHistoricalCommand) : this(stock, null, before)
    override val scope get() = view?.scope ?: checkNotNull(before).scope
    private suspend fun aggregate(key: AggregateKey): StoredAggregate? =
        if (view != null) view.aggregate(key) else checkNotNull(before).aggregateSource(key)?.row
    private suspend fun claim(key: ClaimKey): DomainId? =
        if (view != null) view.claim(key) else checkNotNull(before).claim(key)
    override val documentaryCodec: LocalUnpricedArrivalDocumentaryCodecV2
        get() = checkNotNull(stock.documentaryCodecV2) { "Present v2 custody requires its installed documentary codec" }
    private val foreign = ArrivalForeignReaderV2(stock, view, before)
    private var foreignConflict = false
    private val rows = linkedMapOf<AggregateKey, ArrivalRowV2?>()
    private val producers = linkedMapOf<OperationId, RetainedCommandEvidence>()
    private val visiting = mutableSetOf<OperationId>()
    private var loadedBytes = 0L

    private fun account(bytes: Long) {
        check(bytes >= 0 && loadedBytes in 0L..16_777_216L)
        if (bytes > 16_777_216L - loadedBytes) throw ArrivalHydrationCapacityUnavailableV2()
        loadedBytes += bytes
    }

    suspend fun row(key: AggregateKey): ArrivalRowV2? {
        check(key.kind in arrivalKindsV2)
        if (key in rows) return rows.getValue(key)
        if (rows.size >= 512) throw ArrivalHydrationCapacityUnavailableV2()
        val row = aggregate(key)
        val foreignKey = foreignArrivalKeyV2(key)
        val foreignRow = aggregate(foreignKey)
        check(row == null || foreignRow == null) { "Arrival identity exists in both custody profiles" }
        if (row == null) {
            // A shared claim owned by genuine v1 custody is not an orphan, nor a
            // fabricated v2 predecessor. Keep it as an explicit cross-profile conflict.
            if (foreignRow != null) {
                check(foreign.row(foreignKey) != null)
                foreignConflict = true
            }
            val claim = when (key.kind) {
                LocalUnpricedArrivalProfileV2.SOURCE_IDENTITY -> arrivalSourceClaimV2(key.id)
                LocalUnpricedArrivalProfileV2.LOT_GUARD -> arrivalLotClaimV2(key)
                LocalUnpricedArrivalProfileV2.SERIAL_GUARD -> null // Exact alias check is in serialCode/serialId below.
                else -> arrivalDocumentClaimV2(key.kind, key.id)
            }
            check(claim == null || (foreignRow != null && key.kind in setOf(
                LocalUnpricedArrivalProfileV2.SOURCE_IDENTITY, LocalUnpricedArrivalProfileV2.LOT_GUARD)) || claim(claim) == null)
            rows[key] = null
            return null
        }
        check(row.key == key && row.payload.size <= 65_536 && sha256(row.payload.copyBytes()) == row.digest)
        account(row.payload.size.toLong())
        val value = arrivalDecodeRecordV2(row.payload)
        check(value.scope == scope && arrivalBodyKeyV2(value.body) == key && value.revision == row.revision && row.schema == LocalUnpricedArrivalProfileV2.schema(key.kind))
        val evidence = producer(value.producer.operationId)
        val write = evidence.aggregateWrites.singleOrNull { it.key == key } ?: error("Arrival producer lost a row")
        check(write.storageGeneration == row.revision && write.schema == row.schema && write.payloadDigest == row.digest && write.payload == row.payload)
        when (val body = value.body) {
            is ArrivalBodyV2.Original -> {
                check(claim(arrivalDocumentClaimV2(key.kind, key.id)) == key.id)
                check(claim(arrivalDeliveryClaimV2(scope, body.request)) == body.request.arrivalId)
            }
            is ArrivalBodyV2.State, is ArrivalBodyV2.Event -> check(claim(arrivalDocumentClaimV2(key.kind, key.id)) == key.id)
            is ArrivalBodyV2.SourceIdentity -> {
                check(claim(arrivalSourceClaimV2(body.sourceId)) == body.sourceId && claim(arrivalUnitClaimV2(body.custodyUnitId)) == body.sourceId &&
                    claim(arrivalLineClaimV2(body.lineId)) == body.arrivalId)
            }
            is ArrivalBodyV2.SerialGuard -> check(claim(arrivalSerialClaimV2(body)) == body.sourceId)
            is ArrivalBodyV2.LotGuard -> check(claim(arrivalLotClaimV2(key)) == key.id)
        }
        return ArrivalRowV2(row, value).also { rows[key] = it }
    }

    private suspend fun producer(id: OperationId): RetainedCommandEvidence {
        producers[id]?.let { return it }
        check(visiting.add(id)) { "Arrival producer cycle" }
        try {
            if (producers.size + visiting.size > 256) throw ArrivalHydrationCapacityUnavailableV2()
            val evidence = if (view != null) view.retainedCommand(id)
                else (checkNotNull(before).earlierOriginal(id) as? RetainedOriginalV2.Legacy)?.evidence
            checkNotNull(evidence) { "Arrival producer unavailable" }
            check(evidence.scope == scope && evidence.operationId == id)
            val bytes = evidence.normalized.size.toLong() + evidence.result.size + evidence.aggregateWrites.sumOf { it.payload.size.toLong() }
            check(bytes <= 1_081_344L)
            account(bytes)
            val documentaryClaims = if (view != null) arrivalRetainedPlanV2(this, evidence).outcome.claims else {
                // earlierOriginal returns only a strictly earlier original whose complete
                // installed native join already succeeded. Do not replay its stock/source
                // observations against this later command's BEFORE cut.
                check(evidence.committed && evidence.command in arrivalCommandsV2)
                for (write in evidence.aggregateWrites.filter { it.key.kind in arrivalKindsV2 }) {
                    val body = arrivalDecodeRecordV2(write.payload).body
                    val refs = when (body) {
                        is ArrivalBodyV2.Original -> body.authority.guards.mapNotNull { it.before }
                        is ArrivalBodyV2.Event -> listOf(body.original, body.stateBefore) + body.authority.guards.mapNotNull { it.before }
                        else -> emptyList()
                    }
                    refs.forEach { historical(it, evidence) }
                }
                evidence.claimChanges.map { value ->
                    check(value.action == RetainedClaimAction.ACQUIRE)
                    ClaimChange.Acquire(value.key, value.ownerId)
                }
            }
            // Every immutable companion effect must still exist, especially the
            // action-use/retention row. A retained AFTER alone cannot excuse deletion.
            for (write in evidence.aggregateWrites.filter { it.key.kind !in arrivalKindsV2 }) {
                val current = checkNotNull(aggregate(write.key)) { "Arrival companion effect unavailable" }
                check(current.key == write.key && current.revision == write.storageGeneration && current.schema == write.schema &&
                    current.payload == write.payload && current.digest == write.payloadDigest)
            }
            for (claim in documentaryClaims.filter { it.key.namespace in documentaryCodec.documentaryClaimNamespaces })
                check(claim(claim.key) == claim.ownerId)
            producers[id] = evidence
            return evidence
        } finally { visiting.remove(id) }
    }

    override suspend fun historical(reference: ArrivalRowRefV2, child: RetainedCommandEvidence?): ArrivalHistoricalV2 {
        check(reference.kind in arrivalKindsV2 && reference.revision.value >= 1)
        val evidence = producer(reference.operationId)
        if (child != null) check(evidence.operationId != child.operationId && evidence.recordedAt <= child.recordedAt &&
            (evidence.assignmentId != child.assignmentId || evidence.authorityEpoch != child.authorityEpoch || evidence.sequence < child.sequence))
        val row = evidence.aggregateWrites.singleOrNull { it.key == reference.key() } ?: error("Arrival predecessor unavailable")
        check(row.storageGeneration == reference.revision && row.payloadDigest.value == reference.digest)
        return ArrivalHistoricalV2(row, arrivalDecodeRecordV2(row.payload))
    }

    suspend fun historical(row: ArrivalRowV2): ArrivalHistoricalV2 = historical(row.reference(), null)

    suspend fun serialCode(code: String): ArrivalRowV2? {
        requireStockSerialCode(code)
        val row = row(arrivalSerialCodeKeyV2(code))
        val owner = claim(arrivalSerialCodeClaimV2(code))
        if (row == null && owner != null) {
            val other = checkNotNull(foreign.serialCode(code)) { "Arrival serial code has no custody origin" }
            check((other.value.body as ArrivalBodyV1.SerialGuard).sourceId == owner)
            foreignConflict = true
            return null
        }
        check(if (row == null) owner == null else owner == (row.value.body as ArrivalBodyV2.SerialGuard).sourceId)
        if (row != null) {
            val guard = row.value.body as ArrivalBodyV2.SerialGuard
            check(guard.alias == ArrivalSerialAliasV2.CODE && guard.serialCode == code)
            guard.serialId?.let { id ->
                val paired = this.row(arrivalSerialIdKeyV2(id)) ?: error("Arrival serial alias unavailable")
                check(paired.value.body == guard.copy(alias = ArrivalSerialAliasV2.ID) && claim(arrivalSerialIdClaimV2(id)) == guard.sourceId)
            }
            requireSerialCustody(guard)
        }
        return row
    }

    suspend fun serialId(id: DomainId): ArrivalRowV2? {
        val row = row(arrivalSerialIdKeyV2(id))
        val owner = claim(arrivalSerialIdClaimV2(id))
        if (row == null && owner != null) {
            val other = checkNotNull(foreign.serialId(id)) { "Arrival serial ID has no custody origin" }
            check((other.value.body as ArrivalBodyV1.SerialGuard).sourceId == owner)
            foreignConflict = true
            return null
        }
        check(if (row == null) owner == null else owner == (row.value.body as ArrivalBodyV2.SerialGuard).sourceId)
        if (row != null) {
            val guard = row.value.body as ArrivalBodyV2.SerialGuard
            check(guard.alias == ArrivalSerialAliasV2.ID && guard.serialId == id)
            val paired = this.row(arrivalSerialCodeKeyV2(guard.serialCode)) ?: error("Arrival serial alias unavailable")
            check(paired.value.body == guard.copy(alias = ArrivalSerialAliasV2.CODE) && claim(arrivalSerialCodeClaimV2(guard.serialCode)) == guard.sourceId)
            requireSerialCustody(guard)
        }
        return row
    }

    private suspend fun requireSerialCustody(guard: ArrivalBodyV2.SerialGuard) {
        val source = row(arrivalKeyV2(LocalUnpricedArrivalProfileV2.SOURCE_IDENTITY, guard.sourceId)) ?: error("Arrival serial source unavailable")
        val identity = source.value.body as ArrivalBodyV2.SourceIdentity
        check(identity.arrivalId == guard.arrivalId && identity.lineId == guard.lineId && identity.custodyUnitId == guard.custodyUnitId)
        val original = this.row(arrivalKeyV2(LocalUnpricedArrivalProfileV2.ARRIVAL, guard.arrivalId)) ?: error("Arrival serial original unavailable")
        val request = (original.value.body as ArrivalBodyV2.Original).request
        check(request.lines.single { it.lineId == guard.lineId }.skuId == guard.skuId)
        val state = row(arrivalKeyV2(LocalUnpricedArrivalProfileV2.STATE, guard.arrivalId)) ?: error("Arrival serial custody unavailable")
        val body = state.value.body as ArrivalBodyV2.State
        custodyRows(original, state)
        val slice = body.slices.single { it.sourceId == guard.sourceId && it.custodyUnitId == guard.custodyUnitId }
        check(slice.serialCode == guard.serialCode && slice.serialId == guard.serialId)
        // Rejection/dispatch do not clear this guard or invent a new physical origin.
    }

    suspend fun lot(skuId: DomainId, lotId: DomainId): ArrivalRowV2? {
        val row = row(arrivalLotKeyV2(skuId, lotId)) ?: run {
            foreign.lot(skuId, lotId)?.let { foreignConflict = true }
            return null
        }
        val guard = row.value.body as ArrivalBodyV2.LotGuard
        check(guard.skuId == skuId && guard.lotId == lotId)
        val original = this.row(arrivalKeyV2(LocalUnpricedArrivalProfileV2.ARRIVAL, guard.originArrivalId)) ?: error("Arrival lot original unavailable")
        val body = original.value.body as ArrivalBodyV2.Original
        val line = body.request.lines.single { it.sourceId == guard.originSourceId }
        val item = body.request.items.single { it.skuId == line.skuId }
        check(line.skuId == skuId && item.productId == guard.productId && item.baseUnitId == guard.baseUnitId && item.quantityScale == guard.quantityScale)
        val state = this.row(arrivalKeyV2(LocalUnpricedArrivalProfileV2.STATE, guard.originArrivalId)) ?: error("Arrival lot custody unavailable")
        custodyRows(original, state)
        val partitions = (state.value.body as ArrivalBodyV2.State).slices.filter { it.lineId == line.lineId && it.lotId == lotId }
        check(partitions.isNotEmpty() && partitions.all { it.expiresAt == null || it.expiresAt == guard.expiresAt })
        return row
    }

    /** Current immutable facts must still be present, not just their retained AFTER witnesses.
     * Does not recurse into guards: serial/lot interlocks can safely use this core closure too. */
    private suspend fun custodyRows(original: ArrivalRowV2, state: ArrivalRowV2): Pair<List<ArrivalRowV2>, List<ArrivalRowV2>> {
        val origin = original.value.body as ArrivalBodyV2.Original
        val current = state.value.body as ArrivalBodyV2.State
        val id = origin.request.arrivalId
        check(current.original == original.reference() && current.arrivalId == id && current.events.size <= 64 &&
            current.events.map { it.id }.distinct().size == current.events.size && state.row.revision.value == current.events.size.toLong() + 1)
        requireArrivalPhysicalStateV2(origin, current.slices)
        val events = current.events.map { ref ->
            check(ref.kind == LocalUnpricedArrivalProfileV2.EVENT)
            val event = row(ref.key()) ?: error("Arrival transition unavailable")
            check(event.reference() == ref && (event.value.body as ArrivalBodyV2.Event).original == original.reference())
            event
        }
        val sources = current.slices.map { slice ->
            val source = row(arrivalKeyV2(LocalUnpricedArrivalProfileV2.SOURCE_IDENTITY, slice.sourceId)) ?: error("Arrival source unavailable")
            check(source.value.body == arrivalSourceIdentityV2(id, slice))
            source
        }
        return events to sources
    }

    suspend fun guards(original: LocalRecordUnpricedArrivalV2, slices: List<ArrivalSliceV1>): Map<AggregateKey, ArrivalRowV2?> {
        val result = linkedMapOf<AggregateKey, ArrivalRowV2?>()
        for (slice in slices) {
            slice.serialCode?.let { code -> val key = arrivalSerialCodeKeyV2(code); if (key !in result) result[key] = serialCode(code) }
            slice.serialId?.let { id -> val key = arrivalSerialIdKeyV2(id); if (key !in result) result[key] = serialId(id) }
            slice.lotId?.let { lot ->
                val sku = original.lines.single { it.lineId == slice.lineId }.skuId
                val key = arrivalLotKeyV2(sku, lot); if (key !in result) result[key] = this.lot(sku, lot)
            }
        }
        return result
    }

    suspend fun arrival(id: DomainId): ArrivalCutV2? {
        val original = this.row(arrivalKeyV2(LocalUnpricedArrivalProfileV2.ARRIVAL, id))
        val state = row(arrivalKeyV2(LocalUnpricedArrivalProfileV2.STATE, id))
        check((original == null) == (state == null))
        if (original == null || state == null) {
            foreign.arrival(id)?.let { foreignConflict = true }
            return null
        }
        val origin = original.value.body as ArrivalBodyV2.Original
        val current = state.value.body as ArrivalBodyV2.State
        val (events, sources) = custodyRows(original, state)
        val guards = guards(origin.request, current.slices)
        check(guards.values.all { it != null })
        for (slice in current.slices) slice.lotId?.let { lot ->
            val sku = origin.request.lines.single { it.lineId == slice.lineId }.skuId
            val guard = guards.getValue(arrivalLotKeyV2(sku, lot))!!.value.body as ArrivalBodyV2.LotGuard
            check(slice.expiresAt == null || guard.expiresAt == slice.expiresAt)
        }
        return ArrivalCutV2(original, state, events, sources, guards.values.filterNotNull())
    }

    /** Source/unit/line claims are permanent. An orphan is uncertainty, not an unused identity. */
    suspend fun sourceUsed(sourceId: DomainId, unitId: DomainId, lineId: DomainId?): Boolean {
        val source = row(arrivalKeyV2(LocalUnpricedArrivalProfileV2.SOURCE_IDENTITY, sourceId))
        val unitOwner = claim(arrivalUnitClaimV2(unitId))
        unitOwner?.let { owner ->
            val actual = row(arrivalKeyV2(LocalUnpricedArrivalProfileV2.SOURCE_IDENTITY, owner))
            if (actual != null) check((actual.value.body as ArrivalBodyV2.SourceIdentity).custodyUnitId == unitId)
            else {
                val other = checkNotNull(foreign.row(arrivalKey(LocalUnpricedArrivalProfileV1.SOURCE_IDENTITY, owner))) {
                    "Arrival unit source unavailable"
                }
                check((other.value.body as ArrivalBodyV1.SourceIdentity).custodyUnitId == unitId)
                foreignConflict = true
            }
        }
        val lineOwner = lineId?.let { claim(arrivalLineClaimV2(it)) }
        lineOwner?.let { owner ->
            val actual = row(arrivalKeyV2(LocalUnpricedArrivalProfileV2.ARRIVAL, owner))
            if (actual != null) check((actual.value.body as ArrivalBodyV2.Original).request.lines.any { it.lineId == lineId })
            else {
                val other = checkNotNull(foreign.row(arrivalKey(LocalUnpricedArrivalProfileV1.ARRIVAL, owner))) {
                    "Arrival line original unavailable"
                }
                check((other.value.body as ArrivalBodyV1.Original).request.lines.any { it.lineId == lineId })
                foreignConflict = true
            }
        }
        return source != null || claim(arrivalSourceClaimV2(sourceId)) != null || unitOwner != null || lineOwner != null
    }

    suspend fun deliveryUsed(request: LocalRecordUnpricedArrivalV2): Boolean {
        val owner = claim(arrivalDeliveryClaimV2(scope, request)) ?: return false
        val row = row(arrivalKeyV2(LocalUnpricedArrivalProfileV2.ARRIVAL, owner))
        if (row != null) check(arrivalDeliveryClaimV2(scope, (row.value.body as ArrivalBodyV2.Original).request) == arrivalDeliveryClaimV2(scope, request))
        else {
            val other = checkNotNull(foreign.row(arrivalKey(LocalUnpricedArrivalProfileV1.ARRIVAL, owner))) {
                "Arrival delivery original unavailable"
            }
            check(arrivalDeliveryClaim(scope, (other.value.body as ArrivalBodyV1.Original).request) == arrivalDeliveryClaimV2(scope, request))
            foreignConflict = true
        }
        return true
    }

    override suspend fun requireStockObservations(child: RetainedCommandEvidence, original: ArrivalBodyV2.Original,
        selected: List<ArrivalSliceV1>, authority: ArrivalAuthorityWireV2) {
        // Only live hydration reconstructs retained source shape. Historical hydration
        // consumes already joined earlier originals; the active command has a separate
        // ArrivalNativeHistoryAccessV2 for its own native BEFORE stock observations.
        requireArrivalRetainedStockV2(stock, checkNotNull(view), child, original, selected, authority)
    }

    fun foreignGuardConflict(): Boolean = foreignConflict

    fun resources(): Set<RecoveryResourceKey> = buildSet {
        for ((key, row) in rows) addAll(arrivalRowResourcesV2(scope, key, row?.value))
        for (producer in producers.values) addAll(arrivalProducerResourcesV2(producer))
        addAll(foreign.resources())
    }
}

/** Coordinates only: this never converts a v1 row, record, producer or source stamp to v2. */
internal fun foreignArrivalKeyV2(key: AggregateKey): AggregateKey = arrivalKey(when (key.kind) {
    LocalUnpricedArrivalProfileV2.ARRIVAL -> LocalUnpricedArrivalProfileV1.ARRIVAL
    LocalUnpricedArrivalProfileV2.STATE -> LocalUnpricedArrivalProfileV1.STATE
    LocalUnpricedArrivalProfileV2.EVENT -> LocalUnpricedArrivalProfileV1.EVENT
    LocalUnpricedArrivalProfileV2.SOURCE_IDENTITY -> LocalUnpricedArrivalProfileV1.SOURCE_IDENTITY
    LocalUnpricedArrivalProfileV2.SERIAL_GUARD -> LocalUnpricedArrivalProfileV1.SERIAL_GUARD
    LocalUnpricedArrivalProfileV2.LOT_GUARD -> LocalUnpricedArrivalProfileV1.LOT_GUARD
    else -> error("Unsupported arrival custody coordinate")
}, key.id)
