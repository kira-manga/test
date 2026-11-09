package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.RetainedAggregateWrite
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.inventory.CollectionExposure
import com.ukkera.pos.inventory.InventoryLocalCut
import com.ukkera.pos.inventory.InventoryPostedRecords
import com.ukkera.pos.inventory.InventoryRecordCodec
import com.ukkera.pos.inventory.InventoryScope
import com.ukkera.pos.inventory.OwnedValuationPool
import com.ukkera.pos.inventory.ReservationHoldReason
import com.ukkera.pos.inventory.ReservationStatus
import com.ukkera.pos.inventory.StockIssue
import com.ukkera.pos.inventory.StockOwnership
import com.ukkera.pos.inventory.StockPosition
import com.ukkera.pos.inventory.StockReservation
import com.ukkera.pos.inventory.StockSafety
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import kotlinx.serialization.KSerializer

/** Resolved immutable retained AFTER, never substituted for today's row or a source permission. */
internal class StockHistoricalRecord(val row: RetainedAggregateWrite, val envelope: StockRecordWire)

/**
 * Current normalized-row reads verify exact retained read references and predecessors as well as
 * the complete producer shape. The original read set is normalized and dependency-closed; it is
 * not a fabricated whole-branch snapshot or a replayable authorization. Direct original sources
 * are checked against kernel-retained evidence. Protected writer/frontier/anti-rollback remains
 * an INDEPENDENT mandatory runtime obligation; hashes alone do not establish that frontier.
 */
internal suspend fun requireStockSourceCrosswalk(
    view: CommandReadView,
    evidence: RetainedCommandEvidence,
    budget: StockLoadBudget,
    producer: suspend (OperationId) -> RetainedCommandEvidence,
) {
    val manifest = retainedBody(evidence, AggregateKey(LocalStockStoreProfile.POSTING, DomainId(evidence.operationId.value)), StockPostingWire.serializer())
    val observed = linkedMapOf<AggregateKey, StockHistoricalRecord>()
    suspend fun resolve(key: AggregateKey, id: OperationId, revision: Revision, digest: String): StockHistoricalRecord {
        val original = producer(id)
        localStockCheck(original.scope == evidence.scope && original.operationId != evidence.operationId &&
            (original.assignmentId != evidence.assignmentId || original.authorityEpoch != evidence.authorityEpoch || original.sequence < evidence.sequence),
            LocalStockFailure.STORAGE_INCONSISTENT)
        val row = original.aggregateWrites.singleOrNull { it.key == key } ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
        localStockCheck(row.storageGeneration == revision && row.payloadDigest.value == digest,
            LocalStockFailure.STORAGE_INCONSISTENT)
        val envelope = stockDecode(row.payload, StockRecordWire.serializer())
        requireRetainedStockEnvelope(original, key, revision, envelope)
        return StockHistoricalRecord(row, envelope)
    }
    for (reference in manifest.observations) observed[reference.key()] = resolve(reference.key(), reference.operationId, reference.revision, reference.digest)
    val after = evidence.aggregateWrites.filter { it.key.kind in stockRecordKinds }.associate { row ->
        val envelope = stockDecode(row.payload, StockRecordWire.serializer())
        requireRetainedStockEnvelope(evidence, row.key, row.storageGeneration, envelope)
        row.key to StockHistoricalRecord(row, envelope)
    }
    val predecessors = linkedMapOf<AggregateKey, StockHistoricalRecord>()
    for ((key, row) in after) {
        val previous = row.envelope.previous
        if (previous == null) localStockCheck(key !in observed, LocalStockFailure.STORAGE_INCONSISTENT)
        else {
            val source = observed[key] ?: resolve(key, previous.operationId, previous.revision, previous.digest)
            localStockCheck(source.row.storageGeneration == previous.revision && source.row.payloadDigest.value == previous.digest &&
                source.envelope.producer.operationId == previous.operationId, LocalStockFailure.STORAGE_INCONSISTENT)
            if (key.kind in LocalStockStoreProfile.inventoryKinds) localStockCheck(key in observed, LocalStockFailure.STORAGE_INCONSISTENT)
            predecessors[key] = source
        }
    }
    val scope = InventoryScope(evidence.scope.companyId, evidence.scope.branchId)
    val beforeItems = observed.filterKeys { it.kind == LocalStockStoreProfile.ITEM }.mapValues { historicalBody(it.value, StockItemWire.serializer()) }
    val beforePositions = observed.filterKeys { it.kind == LocalStockStoreProfile.POSITION }.mapValues { (key, row) -> historicalPosition(row, evidence) }
    val beforeReservations = observed.filterKeys { it.kind == LocalStockStoreProfile.RESERVATION }.mapValues { (key, row) -> historicalReservation(row, scope) }
    val beforeIssues = observed.filterKeys { it.kind == LocalStockStoreProfile.ISSUE }.mapValues { (_, row) -> historicalIssue(row, scope) }
    val serials = observed.filterKeys { it.kind == LocalStockStoreProfile.SERIAL_ORIGIN }
        .mapValues { (_, row) -> historicalBody(row, StockSerialWire.serializer()) }.toMutableMap()
    localStockCheck(serials.keys == beforePositions.values.mapNotNull { it.key.serialId }.map(LocalStockStoreProfile::serialKey).toSet(),
        LocalStockFailure.STORAGE_INCONSISTENT)
    for (position in beforePositions.values.filter { it.key.serialId != null }) {
        val serial = serials.getValue(LocalStockStoreProfile.serialKey(requireNotNull(position.key.serialId)))
        requireStockSerialPosition(serial, position)
    }
    val pages = HistoricalPoolReader(view, budget)
    val beforePools = observed.filterKeys { it.kind == LocalStockStoreProfile.POOL }.mapValues { (_, row) -> pages.pool(row, evidence) }
    localStockCheck(beforeItems.size <= LocalStockStoreProfile.MAX_ITEMS_PER_COMMAND && beforePositions.size <= LocalStockStoreProfile.MAX_POSITIONS_PER_COMMAND &&
        beforePools.size <= LocalStockStoreProfile.MAX_POOLS_PER_COMMAND && beforeReservations.size <= LocalStockStoreProfile.MAX_RESERVATIONS_PER_COMMAND,
        LocalStockFailure.STORAGE_INCONSISTENT)
    for ((key, item) in beforeItems) {
        requireStockItemIndex(item, evidence.scope, key.id)
        localStockCheck(item.skuId == key.id && item.positions.isNotEmpty() && item.quantityScale in 0..6 &&
            item.positions.map { LocalStockStoreProfile.positionKey(it.key(evidence.scope)) }.toSet() == beforePositions.filterValues { it.key.pool.skuId == key.id }.keys &&
            item.pools.map { LocalStockStoreProfile.poolKey(it.key(evidence.scope)) }.toSet() == beforePools.filterValues { it.key.skuId == key.id }.keys &&
            item.activeReservationIds.toSet() == beforeReservations.values.filter { reservation -> reservation.retainsStockClaim &&
                reservation.allocations.any { it.position.pool.skuId == key.id } }.map { it.reservationId }.toSet(), LocalStockFailure.STORAGE_INCONSISTENT)
        localStockCheck(beforePositions.values.filter { it.key.pool.skuId == key.id }.all { it.onHand.unitId == item.unitId && it.onHand.scale == item.quantityScale },
            LocalStockFailure.STORAGE_INCONSISTENT)
    }
    localStockCheck(beforePositions.values.all { LocalStockStoreProfile.itemKey(it.key.pool.skuId) in beforeItems } &&
        beforePools.values.all { LocalStockStoreProfile.itemKey(it.key.skuId) in beforeItems }, LocalStockFailure.STORAGE_INCONSISTENT)
    // Validates physical/value totals, original issue/restore correspondence and retained paid holds.
    InventoryLocalCut.assemble(scope, beforePositions.values.toList(), beforePools.values.toList(), beforeReservations.values.toList(), beforeIssues.values.toList())
    val records = manifest.effects?.let { InventoryRecordCodec.inspectEffects(it, scope, evidence.operationId) }
    val positions = beforePositions.values.associateBy { it.key }.toMutableMap()
    val pools = beforePools.values.associateBy { it.key }.toMutableMap()
    val reservations = beforeReservations.values.associateBy { it.reservationId }.toMutableMap()
    val issues = beforeIssues.values.associateBy { it.issueId }.toMutableMap()
    for ((key, record) in after.filterKeys { it.kind == LocalStockStoreProfile.POSITION }) {
        val value = historicalPosition(record, evidence)
        val previous = predecessors[key]?.let { historicalPosition(it, evidence) }
        val movements = records?.movements.orEmpty().filter { it.position == value.key }
        val zero = Quantity(value.onHand.unitId, 0, value.onHand.scale)
        val delta = movements.fold(zero) { total, movement -> total + movement.quantityDelta }
        localStockCheck((previous?.onHand ?: zero) + delta == value.onHand &&
            (previous == null || previous.expiresAtEpochMillis == value.expiresAtEpochMillis), LocalStockFailure.STORAGE_INCONSISTENT)
        val safety = records?.safety.orEmpty().filter { it.second.key == value.key }
        if (safety.isEmpty()) localStockCheck(previous == null || previous.safety == value.safety, LocalStockFailure.STORAGE_INCONSISTENT)
        else {
            // Safety metadata remains exact; a following return movement can change quantity.
            // The original quantity plus the complete movement delta was checked above.
            localStockCheck(previous != null && safety.first().first == previous && safety.last().second.copy(onHand = value.onHand) == value,
                LocalStockFailure.STORAGE_INCONSISTENT)
            safety.zipWithNext().forEach { (left, right) -> localStockCheck(left.second == right.first, LocalStockFailure.STORAGE_INCONSISTENT) }
        }
        val qualifications = records?.serialRegistrations.orEmpty().filter { it.second.key == value.key }
        if (qualifications.isEmpty()) localStockCheck(previous == null || previous.serialRegistrationId == value.serialRegistrationId,
            LocalStockFailure.STORAGE_INCONSISTENT)
        else localStockCheck(qualifications.size == 1 && qualifications.single().first == previous &&
            qualifications.single().second.copy(onHand = value.onHand) == value, LocalStockFailure.STORAGE_INCONSISTENT)
        positions[value.key] = value
    }
    for ((key, record) in after.filterKeys { it.kind == LocalStockStoreProfile.POOL }) {
        val header = historicalBody(record, StockPoolWire.serializer())
        val priorHeader = predecessors[key]?.let { historicalBody(it, StockPoolWire.serializer()) }
        for ((ordinal, page) in header.pages.withIndex()) {
            if (page.creationGeneration < record.row.storageGeneration) {
                localStockCheck(priorHeader?.ownedTransferVersion == header.ownedTransferVersion &&
                    priorHeader?.pages?.getOrNull(ordinal) == page, LocalStockFailure.STORAGE_INCONSISTENT)
            }
        }
        val value = pages.pool(record, evidence)
        val previous = beforePools[key]
        InventoryRecordCodec.requirePostedPoolTransition(previous, value, records?.valuations.orEmpty().filter { it.pool == value.key })
        pools[value.key] = value
    }
    for ((key, record) in after.filterKeys { it.kind == LocalStockStoreProfile.RESERVATION }) {
        val value = historicalReservation(record, scope)
        var previous = beforeReservations[key]
        for (step in records?.reservations.orEmpty().filter { it.reservationId == value.reservationId }) {
            requireReservationSuccessor(previous, step)
            previous = step
        }
        reservations[value.reservationId] = value
    }
    for ((key, record) in after.filterKeys { it.kind == LocalStockStoreProfile.ISSUE }) {
        val value = historicalIssue(record, scope)
        var previous = beforeIssues[key]
        for (step in records?.issues.orEmpty().filter { it.issueId == value.issueId }) {
            requireIssueSuccessor(previous, step)
            previous = step
        }
        issues[value.issueId] = value
    }
    // A collateral hold cannot be invented from an unrelated after row's digest.
    for (reservation in records?.reservations.orEmpty().filter { it.status == ReservationStatus.HELD }) {
        for (hold in reservation.holds) {
            val position = hold.position?.let { positions[it] }
            val meaningful = when (hold.reason) {
                ReservationHoldReason.RECALL -> position?.safety == StockSafety.RECALLED
                ReservationHoldReason.EXPIRY -> position != null && (position.safety == StockSafety.EXPIRED ||
                    position.expiresAtEpochMillis?.let { it <= evidence.recordedAt.value.toEpochMilliseconds() } == true)
                ReservationHoldReason.COUNT_SHORTAGE -> position != null && (position.safety == StockSafety.COUNT_REVIEW ||
                    records?.counts.orEmpty().any { it.position == position.key })
                ReservationHoldReason.RESERVATION_EXPIRED -> reservation.expiresAtEpochMillis?.let { it <= evidence.recordedAt.value.toEpochMilliseconds() } == true
                ReservationHoldReason.UNAVAILABLE -> position != null && (!position.isSellableAt(evidence.recordedAt.value.toEpochMilliseconds()) ||
                    reservations.values.filter { it.retainsStockClaim }.flatMap { it.allocations }.filter { it.position == position.key }
                        .fold(Quantity(position.onHand.unitId, 0, position.onHand.scale)) { total, allocation -> total + allocation.quantity } > position.onHand)
            }
            // Existing holds remain until a separate disposition. A subsequent event need not
            // recreate the original shortage merely to preserve an unresolved paid/unknown hold.
            val retained = beforeReservations[LocalStockStoreProfile.reservationKey(reservation.reservationId)]?.holds?.contains(hold) == true
            localStockCheck(meaningful || retained, LocalStockFailure.STORAGE_INCONSISTENT)
        }
    }
    val afterItems = after.filterKeys { it.kind == LocalStockStoreProfile.ITEM }.mapValues { historicalBody(it.value, StockItemWire.serializer()) }
    after.filterKeys { it.kind == LocalStockStoreProfile.SERIAL_ORIGIN }.forEach { (key, row) -> serials[key] = historicalBody(row, StockSerialWire.serializer()) }
    for (position in positions.values.filter { it.key.serialId != null }) {
        val serial = serials[LocalStockStoreProfile.serialKey(requireNotNull(position.key.serialId))]
            ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
        requireStockSerialPosition(serial, position)
    }
    for ((key, item) in afterItems) {
        requireStockItemIndex(item, evidence.scope, key.id)
        val previous = beforeItems[key]
        localStockCheck(item.skuId == key.id && (previous == null || item.unitId == previous.unitId && item.quantityScale == previous.quantityScale) &&
            item.positions.toSet() == positions.values.filter { it.key.pool.skuId == key.id }.map { StockPositionRefWire.from(it.key) }.toSet() &&
            item.pools.toSet() == pools.values.filter { it.key.skuId == key.id }.map { StockPoolRefWire.from(it.key) }.toSet() &&
            item.activeReservationIds.toSet() == reservations.values.filter { it.retainsStockClaim && it.allocations.any { allocation -> allocation.position.pool.skuId == key.id } }
                .map { it.reservationId }.toSet(), LocalStockFailure.STORAGE_INCONSISTENT)
    }
    if (records != null) InventoryLocalCut.assemble(scope, positions.values.toList(), pools.values.toList(), reservations.values.toList(), issues.values.toList())
    if (evidence.command in stockCommands) requireStockDocumentSuccessors(evidence, observed, predecessors, after, records)
}

private val stockRecordKinds = LocalStockStoreProfile.inventoryKinds - setOf(LocalStockStoreProfile.POSTING, LocalStockStoreProfile.VALUE_PAGE) +
    setOf(LocalStockStoreProfile.RECEIPT_DOCUMENT, LocalStockStoreProfile.ALLOWANCE, LocalStockStoreProfile.INSPECTION,
        LocalStockStoreProfile.COUNT_DOCUMENT, LocalStockStoreProfile.TRANSFER_DOCUMENT)

internal fun <T> historicalBody(record: StockHistoricalRecord, serializer: KSerializer<T>): T = stockBody(record.envelope.body, serializer)

private fun historicalPosition(record: StockHistoricalRecord, evidence: RetainedCommandEvidence): StockPosition {
    val body = historicalBody(record, StockPositionWire.serializer())
    val key = body.position.key(evidence.scope)
    localStockCheck(LocalStockStoreProfile.positionKey(key) == record.row.key, LocalStockFailure.STORAGE_INCONSISTENT)
    return InventoryRecordCodec.decodePosition(body.encoded, key)
}
private fun historicalReservation(record: StockHistoricalRecord, scope: InventoryScope): StockReservation {
    val body = historicalBody(record, StockReservationWire.serializer())
    return InventoryRecordCodec.decodeReservation(body.encoded, scope, record.row.key.id, body.revision)
}
private fun historicalIssue(record: StockHistoricalRecord, scope: InventoryScope): StockIssue {
    val body = historicalBody(record, StockIssueWire.serializer())
    return InventoryRecordCodec.decodeIssue(body.encoded, scope, record.row.key.id, body.revision)
}

private class HistoricalPoolReader(private val view: CommandReadView, private val budget: StockLoadBudget) {
    private var totalBytes = 0L
    private val cache = mutableMapOf<Pair<AggregateKey, String>, OwnedValuationPool>()
    suspend fun pool(record: StockHistoricalRecord, evidence: RetainedCommandEvidence): OwnedValuationPool {
        val cacheKey = record.row.key to record.row.payloadDigest.value
        cache[cacheKey]?.let { return it }
        val header = historicalBody(record, StockPoolWire.serializer())
        val key = header.pool.key(evidence.scope)
        localStockCheck(LocalStockStoreProfile.poolKey(key) == record.row.key, LocalStockFailure.STORAGE_INCONSISTENT)
        requireStockPoolPages(header, record.row.key.id, record.row.storageGeneration)
        totalBytes += header.encodedBytes
        if (totalBytes > 16L * 1_024 * 1_024) throw LocalStockHydrationCapacityUnavailable()
        val bytes = ByteArray(header.encodedBytes)
        var offset = 0
        for (page in header.pages) {
            val row = view.aggregate(AggregateKey(LocalStockStoreProfile.VALUE_PAGE, page.id)) ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
            budget.row(row.payload.size)
            localStockCheck(row.schema == stockPoolPageSchema(header) && row.revision == Revision(1) &&
                row.payload.size == page.bytes && row.digest.value == page.digest && sha256(row.payload.copyBytes()) == row.digest,
                LocalStockFailure.STORAGE_INCONSISTENT)
            row.payload.copyBytes().copyInto(bytes, offset)
            offset += page.bytes
        }
        localStockCheck(offset == bytes.size && sha256(bytes).value == header.encodedDigest, LocalStockFailure.STORAGE_INCONSISTENT)
        val value = InventoryRecordCodec.decodePool(bytes.decodeToString(throwOnInvalidSequence = true), key, header.revision)
        requireStockPoolProfile(header, value)
        localStockCheck(value.value.onHand == header.quantity && value.value.carryingValue == header.carryingValue, LocalStockFailure.STORAGE_INCONSISTENT)
        cache[cacheKey] = value
        return value
    }
}

private fun requireReservationSuccessor(before: StockReservation?, after: StockReservation) {
    if (before == null) {
        localStockCheck(after.revision == Revision(1) && after.status == ReservationStatus.RESERVED && after.holds.isEmpty(), LocalStockFailure.STORAGE_INCONSISTENT)
        return
    }
    localStockCheck(before.retainsStockClaim && after.revision == before.revision.next() && before.allocations == after.allocations &&
        before.binding.orderId == after.binding.orderId && before.binding.quoteRevision == after.binding.quoteRevision &&
        before.binding.collectionId == after.binding.collectionId && before.binding.originalActorId == after.binding.originalActorId &&
        before.expiresAtEpochMillis == after.expiresAtEpochMillis && after.holds.containsAll(before.holds) &&
        (before.binding.exposure == CollectionExposure.UNCOLLECTED || after.binding.exposure != CollectionExposure.UNCOLLECTED) &&
        (before.status != ReservationStatus.HELD || after.status == ReservationStatus.HELD || after.status == ReservationStatus.RELEASED) &&
        (before.status == after.status || before.binding.exposure == after.binding.exposure), LocalStockFailure.STORAGE_INCONSISTENT)
}

private fun requireIssueSuccessor(before: StockIssue?, after: StockIssue) {
    if (before == null) {
        localStockCheck(after.revision == Revision(1) && after.allocations.all { it.restoredQuantity.scaled == 0L }, LocalStockFailure.STORAGE_INCONSISTENT)
        return
    }
    localStockCheck(after.revision == before.revision.next() && after.issueId == before.issueId && after.reservationId == before.reservationId &&
        after.binding == before.binding && after.purpose == before.purpose && after.allocations.size == before.allocations.size &&
        after.allocations.zip(before.allocations).all { (next, old) -> next.allocation == old.allocation && next.originalValue == old.originalValue &&
            next.restoredQuantity >= old.restoredQuantity }, LocalStockFailure.STORAGE_INCONSISTENT)
}
