package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.InventoryRecordCodec
import com.ukkera.pos.inventory.StockPosition
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.*

/** Exact physical observations only. No valuation, reservation, stock admission or grant. */
internal data class ArrivalStockItemFactV2(val source: StockObservedWire, val unitId: DomainId, val scale: Int,
    val serialized: Boolean, val legacyPositions: List<StockPositionRefWire>? = null)
internal data class ArrivalStockPositionFactV2(val source: StockObservedWire, val value: StockPosition)

internal class ArrivalStockCutV2(
    val scope: BranchScope, val profile: ArrivalStockProfileV2, val activation: StockObservedWire?,
    items: Map<DomainId, ArrivalStockItemFactV2?>, positions: List<ArrivalStockPositionFactV2>,
    lots: Set<Pair<DomainId, DomainId>>, readEvidenceChanges: List<AggregateChange> = emptyList(),
) {
    val items: Map<DomainId, ArrivalStockItemFactV2?> = items.toMap()
    val positions: List<ArrivalStockPositionFactV2> = localStockFrozen(positions)
    val lots: Set<Pair<DomainId, DomainId>> = lots.toSet()
    val readEvidenceChanges: List<AggregateChange> = localStockFrozen(readEvidenceChanges)
    val observations: List<StockObservedWire> get() = (items.values.filterNotNull().map { it.source } + positions.map { it.source })
        .sortedWith(compareBy<StockObservedWire> { it.kind }.thenBy { it.id.value })
    val absentItems: List<DomainId> get() = items.filterValues { it == null }.keys.sortedBy { it.value }

    fun withReadEvidence(changes: List<AggregateChange>): ArrivalStockCutV2 {
        check(readEvidenceChanges.isEmpty() && profile == ArrivalStockProfileV2.RELATIONAL_V2 && changes.isNotEmpty())
        return ArrivalStockCutV2(scope, profile, activation, items, positions, lots, changes)
    }

    fun requireCompatible(itemFacts: List<ArrivalItemAuthorityWireV2>, lines: List<LocalArrivalLineV2>, slices: List<ArrivalSliceV1>) {
        for (item in itemFacts) items.getValue(item.selection.skuId)?.let { prior ->
            arrivalRequireV2(prior.unitId == item.selection.baseUnitId && prior.scale == item.selection.quantityScale, LocalArrivalFailureV2.INVALID_UNIT)
            arrivalRequireV2(prior.serialized == (item.tracking == LocalArrivalTrackingV1.SERIAL), LocalArrivalFailureV2.INVALID_TRACKING)
        }
        for (slice in slices) {
            val skuId = lines.single { it.lineId == slice.lineId }.skuId
            val matching = positions.filter { it.value.key.pool.skuId == skuId && it.value.key.lotId == slice.lotId }
            arrivalRequireV2(matching.all { it.value.onHand.unitId == slice.quantity.unitId && it.value.onHand.scale == slice.quantity.scale },
                LocalArrivalFailureV2.INVALID_UNIT)
            if (slice.lotId != null && slice.expiresAt != null) arrivalRequireV2(matching.all {
                it.value.expiresAtEpochMillis == slice.expiresAt.value.toEpochMilliseconds() }, LocalArrivalFailureV2.INVALID_LOT)
        }
    }
}

private fun arrivalSelectedStockV2(lines: List<LocalArrivalLineV2>, slices: List<ArrivalSliceV1>): Pair<Set<DomainId>, Set<Pair<DomainId, DomainId>>> {
    val selected = slices.map { slice -> lines.single { it.lineId == slice.lineId } to slice }
    return selected.map { it.first.skuId }.toSet() to selected.mapNotNull { (line, slice) -> slice.lotId?.let { line.skuId to it } }.toSet()
}

private fun arrivalStockReferenceV2(row: LocalStockRecord<*>): StockObservedWire = StockObservedWire(row.row.key.kind, row.row.key.id,
    row.envelope.producer.operationId, row.row.revision, row.row.digest.value)
private fun arrivalStockReferenceV2(row: StockRelationalRecordV2): StockObservedWire = StockObservedWire(row.key.kind, row.key.id,
    row.producer.operationId, row.generation, row.digest.value)

private fun arrivalLegacyItemV2(scope: BranchScope, source: StockObservedWire, body: StockItemWire): ArrivalStockItemFactV2 {
    check(source.key() == LocalStockStoreProfile.itemKey(body.skuId))
    requireStockItemIndex(body, scope, body.skuId)
    return ArrivalStockItemFactV2(source, body.unitId, body.quantityScale, body.positions.all { it.serialId != null }, body.positions.toList())
}
private fun arrivalRelationalItemV2(row: StockRelationalRecordV2): ArrivalStockItemFactV2 {
    val body = row.body as? StockRelationalBody.Item ?: inconsistentStock()
    check(row.key == LocalStockRelationalProfileV2.itemKey(body.skuId))
    return ArrivalStockItemFactV2(arrivalStockReferenceV2(row), body.unitId, body.scale, body.serialized)
}
private fun arrivalLegacyPositionV2(scope: BranchScope, source: StockObservedWire, body: StockPositionWire): ArrivalStockPositionFactV2 {
    val key = body.position.key(scope)
    check(source.key() == LocalStockStoreProfile.positionKey(key))
    return ArrivalStockPositionFactV2(source, InventoryRecordCodec.decodePosition(body.encoded, key))
}
private fun arrivalRelationalPositionV2(row: StockRelationalRecordV2): ArrivalStockPositionFactV2 {
    val body = row.body as? StockRelationalBody.Position ?: inconsistentStock()
    check(row.key == LocalStockRelationalProfileV2.positionKey(body.value.key))
    return ArrivalStockPositionFactV2(arrivalStockReferenceV2(row), body.value)
}
private fun requireArrivalStockActivationV2(row: StockRelationalRecordV2): StockObservedWire {
    val marker = row.body as? StockRelationalBody.Activation ?: inconsistentStock()
    check(stockActivationShape(marker) && marker.sourceProfile == STOCK_FRESH_PROFILE_V2)
    return arrivalStockReferenceV2(row)
}

/** Genuine same-loan profile routing. An active relational estate never calls legacy observe(). */
internal suspend fun observeArrivalStockV2(stock: LocalStockRepository, view: CommandReadView,
    lines: List<LocalArrivalLineV2>, slices: List<ArrivalSliceV1>): ArrivalStockCutV2 {
    val (skuIds, lots) = arrivalSelectedStockV2(lines, slices)
    val items = linkedMapOf<DomainId, ArrivalStockItemFactV2?>()
    val positions = linkedMapOf<AggregateKey, ArrivalStockPositionFactV2>()
    val budget = StockLoadBudget()
    if (!stock.isRelationalCurrent(view)) {
        for (id in skuIds.sortedBy { it.value }) {
            val row = stock.record(view, LocalStockStoreProfile.itemKey(id), StockItemWire.serializer())
            check(view.claim(LocalStockStoreProfile.itemClaim(id)) == row?.row?.key?.id)
            val fact = row?.let { budget.row(it.row.payload.size); arrivalLegacyItemV2(view.scope, arrivalStockReferenceV2(it), it.value) }
            items[id] = fact
            for (coordinate in fact?.legacyPositions.orEmpty().filter { id to it.lotId in lots }) {
                val key = LocalStockStoreProfile.positionKey(coordinate.key(view.scope))
                val position = stock.record(view, key, StockPositionWire.serializer()) ?: inconsistentStock()
                budget.row(position.row.payload.size)
                val observed = arrivalLegacyPositionV2(view.scope, arrivalStockReferenceV2(position), position.value)
                check(positions.put(key, observed) == null)
                observed.value.key.serialId?.let { serialId ->
                    val serial = stockSerial(stock, view, serialId) ?: inconsistentStock()
                    requireStockSerialPosition(serial.value, observed.value)
                }
            }
        }
        return ArrivalStockCutV2(view.scope, ArrivalStockProfileV2.LEGACY, null, items, positions.values.toList(), lots)
    }
    val read = stock.observeRelational(view)
    val activation = read.record(LocalStockRelationalProfileV2.activationKey(view.scope.branchId)) ?: unavailableProducer()
    budget.row(activation.payload.size)
    for (id in skuIds.sortedBy { it.value }) {
        val row = read.record(LocalStockRelationalProfileV2.itemKey(id))
        check(read.claim(LocalStockStoreProfile.itemClaim(id)) == row?.key?.id)
        items[id] = row?.let { budget.row(it.payload.size); arrivalRelationalItemV2(it) }
    }
    for ((skuId, lotId) in lots.sortedWith(compareBy<Pair<DomainId, DomainId>> { it.first.value }.thenBy { it.second.value })) {
        read.query(StockQueryV2.LotPositions(skuId, lotId)) { index ->
            val row = read.record(index.identity.key) ?: inconsistentStock()
            budget.row(row.payload.size)
            check(stockIndexOf(view.scope, row) == index)
            val position = arrivalRelationalPositionV2(row)
            check(items.getValue(skuId) != null && position.value.key.pool.skuId == skuId &&
                position.value.key.lotId == lotId && positions.put(row.key, position) == null)
            requireRelationalStockSerialPosition(read, stock, position.value)
        }
    }
    return ArrivalStockCutV2(view.scope, ArrivalStockProfileV2.RELATIONAL_V2, requireArrivalStockActivationV2(activation),
        items, positions.values.toList(), lots)
}

/** Item-only source discovery still binds an actual profile/item; no empty-stock fallback. */
internal suspend fun arrivalStockItemVersionsV2(stock: LocalStockRepository, view: CommandReadView,
    skuIds: List<DomainId>): Map<DomainId, Revision?> {
    check(skuIds.size <= 16 && skuIds.distinct().size == skuIds.size)
    if (stock.isRelationalCurrent(view)) {
        val read = stock.observeRelational(view)
        return skuIds.associateWith { read.itemVersion(it).storageRevision }
    }
    return skuIds.associateWith { id ->
        val row = stock.record(view, LocalStockStoreProfile.itemKey(id), StockItemWire.serializer())
        check(view.claim(LocalStockStoreProfile.itemClaim(id)) == row?.row?.key?.id)
        row?.let { requireStockItemIndex(it.value, view.scope, id); it.row.revision }
    }
}

/** The same row-derived aliases are used by live preparation and native original comparison. */
internal fun arrivalStockResourcesV2(cut: ArrivalStockCutV2): Set<RecoveryResourceKey> = buildSet {
    fun add(kind: RecoveryResourceKind, id: DomainId) { add(RecoveryResourceKey(kind, id)) }
    cut.items.keys.forEach { add(RecoveryResourceKind.STOCK, it) }
    cut.lots.forEach { (skuId, lotId) -> add(RecoveryResourceKind.STOCK, skuId); add(RecoveryResourceKind.STOCK, lotId) }
    cut.activation?.let { add(RecoveryResourceKind.STOCK, it.id) }
    for (observed in cut.positions) {
        val position = observed.value
        add(RecoveryResourceKind.STOCK, observed.source.id)
        add(RecoveryResourceKind.STOCK, position.key.pool.skuId)
        add(RecoveryResourceKind.STOCK, position.key.pool.warehouseId)
        add(RecoveryResourceKind.STOCK, LocalStockStoreProfile.poolKey(position.key.pool).id)
        position.key.lotId?.let { add(RecoveryResourceKind.STOCK, it) }
        position.key.serialId?.let { add(RecoveryResourceKind.STOCK, it) }
        position.serialRegistrationId?.let { add(RecoveryResourceKind.AUTHORIZATION, it) }
    }
    cut.readEvidenceChanges.forEach { add(RecoveryResourceKind.PURCHASE_CUSTODY, it.key.id) }
}

internal fun arrivalStockReadEvidenceChangesV2(original: RetainedCommandEvidence,
    profile: ArrivalStockProfileV2): List<AggregateChange> {
    val rows = original.aggregateWrites.filter { it.key.kind == StockReadEvidenceV1.KIND }
    if (profile == ArrivalStockProfileV2.LEGACY) { check(rows.isEmpty()); return emptyList() }
    check(StockReadEvidenceV1.read(original) != null)
    return rows.map { row ->
        check(row.storageGeneration == Revision(1) && row.schema == StockReadEvidenceV1.SCHEMA)
        AggregateChange(row.key, null, row.schema, row.payload)
    }.sortedWith(compareBy<AggregateChange> { it.key.kind }.thenBy { it.key.id.value })
}

private fun requireArrivalStockCutV2(cut: ArrivalStockCutV2, original: ArrivalBodyV2.Original,
    selected: List<ArrivalSliceV1>, authority: ArrivalAuthorityWireV2) {
    check(cut.profile == authority.stockProfile && cut.activation == authority.stockActivation &&
        authority.stock == cut.observations && authority.absentStockItems == cut.absentItems)
    for (item in authority.items) check(item.selection.stockStorageRevision == cut.items.getValue(item.selection.skuId)?.source?.revision)
    cut.requireCompatible(authority.items, original.request.lines, selected)
}

private fun requireCapturedStockCutV2(observed: StockReadObservations, cut: ArrivalStockCutV2) {
    check(observed.scope == cut.scope && cut.profile == ArrivalStockProfileV2.RELATIONAL_V2)
    val actual = (listOf(requireNotNull(cut.activation)) + cut.observations).associateBy { it.key() }
    for ((key, reference) in actual) {
        val row = observed.current(key) ?: inconsistentStock()
        val decoded = LocalStockRelationalCodecV2.decode(cut.scope, key, row.generation, row.schema, row.payload, row.payloadHash)
        check(arrivalStockReferenceV2(decoded) == reference)
    }
    for ((id, fact) in cut.items) {
        val claim = observed.claim(LocalStockStoreProfile.itemClaim(id)) ?: inconsistentStock()
        check(claim.ownerId == fact?.source?.id)
        if (fact == null) check(observed.absentStockItem(LocalStockRelationalProfileV2.itemKey(id)))
    }
    val expectedQueries = cut.lots.map { StockQueryV2.LotPositions(it.first, it.second) }.toSet()
    val actualQueries = observed.sources.filterIsInstance<StreamedSourceObservationV2.StockRange>().map { it.query }.toSet()
    check(actualQueries == expectedQueries)
    for (query in expectedQueries) {
        val keys = linkedSetOf<AggregateKey>()
        observed.range(query) { index ->
            val row = observed.current(index.identity.key) ?: inconsistentStock()
            val record = LocalStockRelationalCodecV2.decode(cut.scope, index.identity.key, row.generation, row.schema, row.payload, row.payloadHash)
            check(stockIndexOf(cut.scope, record) == index && keys.add(record.key))
        }
        check(keys == cut.positions.filter { it.value.key.pool.skuId == query.skuId && it.value.key.lotId == query.lotId }.map { it.source.key() }.toSet())
    }
}

private fun requireEarlierStockV2(scope: BranchScope, child: RetainedCommandEvidence, original: RetainedCommandEvidence) {
    check(original.scope == scope && original.committed && original.operationId != child.operationId && original.recordedAt <= child.recordedAt &&
        (original.assignmentId != child.assignmentId || original.authorityEpoch != child.authorityEpoch || original.sequence < child.sequence))
}
private fun requireEarlierStockV2(scope: BranchScope, child: RetainedCommandEvidence, original: RetainedStreamedCommand) {
    check(original.identity.scope == scope && original.identity.producer.operationId != child.operationId && original.identity.recordedAt <= child.recordedAt &&
        (original.identity.assignmentId != child.assignmentId || original.identity.authorityEpoch != child.authorityEpoch || original.identity.sequence < child.sequence))
}

/** Current custody validation consumes real retained stock originals, never current replacement rows. */
internal suspend fun requireArrivalRetainedStockV2(stock: LocalStockRepository, view: CommandReadView,
    child: RetainedCommandEvidence, original: ArrivalBodyV2.Original, selected: List<ArrivalSliceV1>, authority: ArrivalAuthorityWireV2): ArrivalStockCutV2 {
    check(child.scope == view.scope)
    val (skuIds, lots) = arrivalSelectedStockV2(original.request.lines, selected)
    val items = linkedMapOf<DomainId, ArrivalStockItemFactV2?>()
    val positions = mutableListOf<ArrivalStockPositionFactV2>()
    check(authority.stock.map { it.key() }.distinct().size == authority.stock.size && authority.absentStockItems.distinct().size == authority.absentStockItems.size)
    val itemKind = if (authority.stockProfile == ArrivalStockProfileV2.LEGACY) LocalStockStoreProfile.ITEM else StockRelationalKindsV2.ITEM
    val positionKind = if (authority.stockProfile == ArrivalStockProfileV2.LEGACY) LocalStockStoreProfile.POSITION else StockRelationalKindsV2.POSITION
    check(authority.stock.all { it.kind == itemKind || it.kind == positionKind } &&
        authority.stock.filter { it.kind == itemKind }.map { it.id }.toSet().intersect(authority.absentStockItems.toSet()).isEmpty() &&
        authority.stock.filter { it.kind == itemKind }.map { it.id }.toSet() + authority.absentStockItems.toSet() == skuIds)
    authority.absentStockItems.forEach { items[it] = null }
    val activation: StockObservedWire?
    if (authority.stockProfile == ArrivalStockProfileV2.LEGACY) {
        check(authority.stockActivation == null)
        val producers = linkedMapOf<OperationId, RetainedCommandEvidence>()
        val visiting = mutableSetOf<OperationId>()
        val budget = StockLoadBudget()
        val purchaseSources = mutableMapOf<OperationId, LocalStockApprovedPurchaseOrderV1>()
        suspend fun producer(id: OperationId): RetainedCommandEvidence {
            producers[id]?.let { return it }
            check(visiting.add(id))
            try {
                val evidence = view.retainedCommand(id) ?: unavailableProducer()
                requireEarlierStockV2(view.scope, child, evidence)
                budget.producer(evidence)
                stock.requireProducer(view, evidence, budget, purchaseSources)
                requireStockSourceCrosswalk(view, evidence, budget) { producer(it) }
                producers[id] = evidence
                return evidence
            } finally { visiting.remove(id) }
        }
        for (reference in authority.stock) {
            val evidence = producer(reference.operationId)
            val row = evidence.aggregateWrites.singleOrNull { it.key == reference.key() } ?: inconsistentStock()
            check(row.storageGeneration == reference.revision && row.payloadDigest.value == reference.digest)
            val envelope = stockDecode(row.payload, StockRecordWire.serializer())
            requireRetainedStockEnvelope(evidence, row.key, row.storageGeneration, envelope)
            if (reference.kind == itemKind) items[reference.id] = arrivalLegacyItemV2(view.scope, reference, stockBody(envelope.body, StockItemWire.serializer()))
            else positions += arrivalLegacyPositionV2(view.scope, reference, stockBody(envelope.body, StockPositionWire.serializer()))
        }
        val expected = items.values.filterNotNull().flatMap { item -> item.legacyPositions.orEmpty().filter { item.source.id to it.lotId in lots }
            .map { LocalStockStoreProfile.positionKey(it.key(view.scope)) } }.toSet()
        check(positions.map { it.source.key() }.toSet() == expected)
        activation = null
    } else {
        val observed = StockReadEvidenceV1.read(child) ?: unavailableProducer()
        val producers = linkedMapOf<OperationId, RetainedStreamedCommand>()
        suspend fun record(reference: StockObservedWire): StockRelationalRecordV2 {
            val evidence = producers[reference.operationId] ?: run {
                val actual = (view.requireStockRelational().originals.read(reference.operationId) as? RetainedOriginalV2.Streamed)?.evidence ?: unavailableProducer()
                requireEarlierStockV2(view.scope, child, actual)
                stock.requireRelationalProducer(view, actual)
                producers[reference.operationId] = actual
                actual
            }
            val source = observed.current(reference.key()) ?: inconsistentStock()
            val record = LocalStockRelationalCodecV2.decode(view.scope, reference.key(), source.generation, source.schema, source.payload, source.payloadHash)
            check(arrivalStockReferenceV2(record) == reference && record.producer == evidence.identity.producer)
            requireOriginalWrite(evidence, record.key, record.generation, record.schema, record.payload, record.digest)
            return record
        }
        activation = requireArrivalStockActivationV2(record(requireNotNull(authority.stockActivation)))
        check(activation.key() == LocalStockRelationalProfileV2.activationKey(view.scope.branchId))
        for (reference in authority.stock) {
            val row = record(reference)
            if (reference.kind == itemKind) items[reference.id] = arrivalRelationalItemV2(row) else positions += arrivalRelationalPositionV2(row)
        }
    }
    val cut = ArrivalStockCutV2(view.scope, authority.stockProfile, activation, items, positions, lots,
        arrivalStockReadEvidenceChangesV2(child, authority.stockProfile))
    requireArrivalStockCutV2(cut, original, selected, authority)
    if (authority.stockProfile == ArrivalStockProfileV2.RELATIONAL_V2) requireCapturedStockCutV2(requireNotNull(StockReadEvidenceV1.read(child)), cut)
    return cut
}

/** The source is core-selected and already FULLY joined; compare its actual literal original write. */
internal suspend fun requireArrivalNativeStockSourceV2(command: RuntimeHistoricalCommand, source: RuntimeHistoricalAggregateSource,
    reference: StockObservedWire) {
    val row = source.row
    check(row.key == reference.key() && row.revision == reference.revision && row.digest.value == reference.digest)
    when (val original = source.original) {
        is RetainedOriginalV2.Legacy -> {
            val evidence = original.evidence
            check(evidence.scope == command.scope && evidence.committed && evidence.operationId == reference.operationId)
            val envelope = stockRecord(row, command.scope)
            check(envelope.producer == StockProducerWire(evidence.operationId, evidence.command.name, evidence.command.version,
                evidence.originalActorId, evidence.semanticDigest.value))
            val after = evidence.aggregateWrites.singleOrNull { it.key == row.key } ?: inconsistentStock()
            requireSameWrite(after, row.key, row.revision, row.schema, row.payload, row.digest)
        }
        is RetainedOriginalV2.Streamed -> {
            val evidence = original.evidence
            val producer = if (row.key.kind in StockRelationalKindsV2.all) LocalStockRelationalCodecV2.decode(command.scope, row).producer
                else stockRecord(row, command.scope).producer.let {
                    StreamedProducerIdentityV2(it.operationId, it.commandKey(), it.actorId, PayloadDigest(it.digest))
                }
            check(evidence.identity.scope == command.scope && evidence.identity.producer.operationId == reference.operationId &&
                producer == evidence.identity.producer)
            requireOriginalWrite(evidence, row.key, row.revision, row.schema, row.payload, row.digest)
        }
    }
}

/** Actual persisted native BEFORE profile. A markerless relational member is not legacy. */
internal suspend fun arrivalStockProfileBeforeV2(command: RuntimeHistoricalCommand): RuntimeHistoricalAggregateSource? {
    val marker = command.aggregateSource(LocalStockRelationalProfileV2.activationKey(command.scope.branchId))
    if (marker == null) {
        for (kind in StockRelationalKindsV2.all.sorted()) check(command.aggregates(kind).isEmpty())
    } else {
        val row = LocalStockRelationalCodecV2.decode(command.scope, marker.row)
        requireArrivalNativeStockSourceV2(command, marker, requireArrivalStockActivationV2(row))
    }
    return marker
}

/** Genuine source-neutral native BEFORE cut, including complete relational lot membership.
 * Reconstructs only DATA for the exact proposed live capture; refused originals need no AFTER
 * custody, documentary row or trace. No authority DTO, fake current view or access is created. */
internal suspend fun observeArrivalStockBeforeV2(command: RuntimeHistoricalCommand,
    lines: List<LocalArrivalLineV2>, slices: List<ArrivalSliceV1>): ArrivalStockCutV2 {
    val (skuIds, lots) = arrivalSelectedStockV2(lines, slices)
    val items = linkedMapOf<DomainId, ArrivalStockItemFactV2?>()
    val positions = linkedMapOf<AggregateKey, ArrivalStockPositionFactV2>()
    val markerSource = arrivalStockProfileBeforeV2(command)
    val profile = if (markerSource == null) ArrivalStockProfileV2.LEGACY else ArrivalStockProfileV2.RELATIONAL_V2
    val budget = StockLoadBudget()
    val trace = mutableListOf<StreamedSourceObservationV2>()
    val activation: StockObservedWire?
    if (profile == ArrivalStockProfileV2.LEGACY) {
        activation = null
        for (id in skuIds.sortedBy { it.value }) {
            val source = command.aggregateSource(LocalStockStoreProfile.itemKey(id))
            check(command.claim(LocalStockStoreProfile.itemClaim(id)) == source?.row?.key?.id)
            items[id] = source?.let {
                budget.row(it.row.payload.size)
                val envelope = stockRecord(it.row, command.scope)
                val reference = StockObservedWire(it.row.key.kind, it.row.key.id, envelope.producer.operationId, it.row.revision, it.row.digest.value)
                requireArrivalNativeStockSourceV2(command, it, reference)
                arrivalLegacyItemV2(command.scope, reference, stockBody(envelope.body, StockItemWire.serializer()))
            }
            for (coordinate in items[id]?.legacyPositions.orEmpty().filter { id to it.lotId in lots }) {
                val sourcePosition = command.aggregateSource(LocalStockStoreProfile.positionKey(coordinate.key(command.scope))) ?: inconsistentStock()
                budget.row(sourcePosition.row.payload.size)
                val envelope = stockRecord(sourcePosition.row, command.scope)
                val reference = StockObservedWire(sourcePosition.row.key.kind, sourcePosition.row.key.id, envelope.producer.operationId,
                    sourcePosition.row.revision, sourcePosition.row.digest.value)
                requireArrivalNativeStockSourceV2(command, sourcePosition, reference)
                val position = arrivalLegacyPositionV2(command.scope, reference, stockBody(envelope.body, StockPositionWire.serializer()))
                check(positions.put(reference.key(), position) == null)
                requireArrivalNativeSerialPositionV2(command, position.value)
            }
        }
    } else {
        val marker = markerSource ?: unavailableProducer()
        val markerRow = LocalStockRelationalCodecV2.decode(command.scope, marker.row)
        activation = requireArrivalStockActivationV2(markerRow)
        budget.row(marker.row.payload.size)
        trace += arrivalNativeCurrentStockV2(marker.row)
        for (id in skuIds.sortedBy { it.value }) {
            val key = LocalStockRelationalProfileV2.itemKey(id)
            val source = command.aggregateSource(key)
            val claimKey = LocalStockStoreProfile.itemClaim(id)
            val owner = command.claim(claimKey)
            check(owner == source?.row?.key?.id)
            trace += source?.let { arrivalNativeCurrentStockV2(it.row) } ?: StreamedSourceObservationV2.StockItemAbsent(key)
            trace += StreamedSourceObservationV2.Claim(claimKey, owner)
            items[id] = source?.let {
                budget.row(it.row.payload.size)
                val row = LocalStockRelationalCodecV2.decode(command.scope, it.row)
                requireArrivalNativeStockSourceV2(command, it, arrivalStockReferenceV2(row))
                arrivalRelationalItemV2(row)
            }
        }
        // Complete native BEFORE membership, never a favorable selected-reference list.
        for (stored in command.aggregates(StockRelationalKindsV2.POSITION)) {
            val row = LocalStockRelationalCodecV2.decode(command.scope, stored)
            val position = arrivalRelationalPositionV2(row)
            if (position.value.key.pool.skuId to position.value.key.lotId !in lots) continue
            val source = command.aggregateSource(stored.key) ?: inconsistentStock()
            check(source.row.key == stored.key && source.row.revision == stored.revision && source.row.schema == stored.schema &&
                source.row.payload == stored.payload && source.row.digest == stored.digest)
            budget.row(stored.payload.size)
            requireArrivalNativeStockSourceV2(command, source, position.source)
            check(items.getValue(position.value.key.pool.skuId) != null && positions.put(stored.key, position) == null)
            trace += arrivalNativeCurrentStockV2(stored)
            val serial = requireArrivalNativeSerialPositionV2(command, position.value)
            if (serial != null) {
                val origin = command.aggregateSource(LocalStockStoreProfile.serialKey(serial.serialId)) ?: inconsistentStock()
                trace += arrivalNativeCurrentStockV2(origin.row)
                trace += StreamedSourceObservationV2.Claim(LocalStockStoreProfile.serialClaim(serial.serialId), serial.serialId)
                trace += StreamedSourceObservationV2.Claim(LocalStockStoreProfile.serialCodeClaim(serial.serialCode), serial.serialId)
            }
        }
        for ((skuId, lotId) in lots.sortedWith(compareBy<Pair<DomainId, DomainId>> { it.first.value }.thenBy { it.second.value })) {
            val members = positions.values.filter { it.value.key.pool.skuId == skuId && it.value.key.lotId == lotId }
                .sortedWith(compareBy<ArrivalStockPositionFactV2> { it.value.key.pool.warehouseId.value }.thenBy { it.source.id.value })
                .map { position ->
                    val stored = command.aggregateSource(position.source.key()) ?: inconsistentStock()
                    requireNotNull(stockIndexOf(command.scope, LocalStockRelationalCodecV2.decode(command.scope, stored.row)))
                }
            trace += StockReadEvidenceV1.proposedRangeSources(StockQueryV2.LotPositions(skuId, lotId), members)
        }
    }
    val cut = ArrivalStockCutV2(command.scope, profile, activation, items, positions.values.toList(), lots)
    if (profile == ArrivalStockProfileV2.LEGACY) return cut
    val changes = StockReadEvidenceV1.proposedChanges(command.scope, StreamedProducerIdentityV2(command.operationId,
        command.command, command.actorId, command.semanticDigest), trace)
    return cut.withReadEvidence(changes)
}

private fun arrivalNativeCurrentStockV2(row: StoredAggregate): StreamedSourceObservationV2.Current =
    StreamedSourceObservationV2.Current(row.key, row.revision, row.schema, row.payload, row.digest)

/** Successful original closure additionally compares actual captured traces and authority.
 * The caller closes the retained owner-generated capture through requireLegacyReadEvidence(). */
internal suspend fun requireArrivalHistoricalStockV2(stock: LocalStockRepository, command: RuntimeHistoricalCommand,
    original: ArrivalBodyV2.Original, selected: List<ArrivalSliceV1>, authority: ArrivalAuthorityWireV2): ArrivalStockCutV2 {
    check(command.scope == original.request.evidence.policy.scope)
    val cut = observeArrivalStockBeforeV2(command, original.request.lines, selected)
    val evidence = (command.original as? RetainedOriginalV2.Legacy)?.evidence ?: unavailableProducer()
    val retained = arrivalStockReadEvidenceChangesV2(evidence, cut.profile)
    check(retained.size == cut.readEvidenceChanges.size && retained.all { row -> cut.readEvidenceChanges.any {
        it.key == row.key && it.expectedRevision == row.expectedRevision && it.schema == row.schema && it.payload == row.payload
    } })
    requireArrivalStockCutV2(cut, original, selected, authority)
    if (authority.stockProfile == ArrivalStockProfileV2.RELATIONAL_V2) requireCapturedStockCutV2(requireNotNull(StockReadEvidenceV1.read(evidence)), cut)
    return cut
}
