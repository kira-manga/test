package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.*

/** Literal selected reservation and complete competing memberships. It never changes stock or claims. */
public class LocalStockPaymentCut internal constructor(
    public val header: InventoryReservationHeader,
    allocations: List<InventoryAllocationRecord>,
    items: List<LocalStockItemVersion>,
    private val positions: Map<StockPositionKey, Pair<InventoryPositionAvailability, Quantity>>,
) {
    public val allocations: List<InventoryAllocationRecord> = allocations.toList()
    public val items: List<LocalStockItemVersion> = items.toList()
    public val deadline: UtcInstant? = (listOfNotNull(header.expiresAtEpochMillis) +
        positions.values.mapNotNull { it.first.position.expiresAtEpochMillis }).minOrNull()?.let {
        UtcInstant(kotlin.time.Instant.fromEpochMilliseconds(it))
    }
    /** Source-derived structural safety; expiry is still checked against the actual writer clock. */
    public val structurallySafe: Boolean get() = header.status == ReservationStatus.RESERVED && header.holdCount == 0L &&
        header.issueId == null && header.retainsStockClaim && header.binding.exposure == CollectionExposure.UNKNOWN &&
        positions.isNotEmpty() && positions.values.all { (available, own) ->
            available.position.key.pool.ownership == StockOwnership.CompanyOwned &&
                available.position.key.custody == StockCustody.RELEASED && available.position.safety == StockSafety.CLEAR &&
                available.position.key.serialId == null && own.scaled > 0 &&
                available.held >= own && available.held <= available.position.onHand
        }
    public fun safeAt(now: UtcInstant): Boolean {
        val millis = now.value.toEpochMilliseconds()
        return millis >= 0 && structurallySafe && (deadline == null || now < checkNotNull(deadline)) &&
            positions.values.all { it.first.position.isSellableAt(millis) }
    }
}

/** Literal already-issued FOOD custody. Original quantities/value remain distinct from current
 * stock availability. The producer identity is not an original-command or financial approval. */
public class LocalStockPaymentIssuedCut internal constructor(
    internal val scope: BranchScope,
    public val header: InventoryReservationHeader,
    public val issue: LocalStockIssueHeader,
    allocations: List<InventoryAllocationRecord>,
    slices: List<InventoryIssueSlice>,
    public val firstIssueProducer: StreamedProducerIdentityV2,
    internal val issueRecord: StockRelationalRecordV2,
) {
    public val allocations: List<InventoryAllocationRecord> = localStockFrozen(allocations)
    public val slices: List<InventoryIssueSlice> = localStockFrozen(slices)
}

/** Complete selected custody union. These are inert observations, never stock or money effects. */
public class LocalStockPaymentCustody internal constructor(
    reserved: Map<DomainId, LocalStockPaymentCut>,
    issued: Map<DomainId, LocalStockPaymentIssuedCut>,
) {
    public val reserved: Map<DomainId, LocalStockPaymentCut> = reserved.toMap()
    public val issued: Map<DomainId, LocalStockPaymentIssuedCut> = issued.toMap()
}

/** Current actual-owner reads. Availability is complete PositionMembers, not a favorable row list. */
public suspend fun LocalStockRepository.observePaymentReservation(view: CommandReadView, reservationId: DomainId,
    observeResource: (suspend (RecoveryResourceKey) -> Unit)? = null): LocalStockPaymentCut {
    val read = observeRelational(view)
    // Activation was already read and fully qualified on this actual loan. Its only alias is
    // its real branch key; every subsequent header/member/position/item alias comes from record().
    observeResource?.invoke(RecoveryResourceKey(com.ukkera.pos.recovery.RecoveryResourceKind.STOCK, view.scope.branchId))
    read.paymentResourceObserver = observeResource
    try {
        val allocations = mutableListOf<InventoryAllocationRecord>()
        val own = linkedMapOf<StockPositionKey, Quantity>()
        // Collect the existing selected allocation cut during the SAME complete checked
        // member range. Normal return still proves both allocation and hold counts;
        // callbacks alone cannot establish completeness or replace source verification.
        val header = read.reservationWithMembers(reservationId, { _, record ->
            allocations += record
            val allocation = record.allocation
            own[allocation.position] = own[allocation.position]?.plus(allocation.quantity) ?: allocation.quantity
        }, { }) ?: missingStock()
        localStockCheck(allocations.size.toLong() == header.allocationCount)
        val positions = linkedMapOf<StockPositionKey, Pair<InventoryPositionAvailability, Quantity>>()
        for ((position, quantity) in own) positions[position] = read.availability(position) to quantity
        val items = own.keys.map { it.pool.skuId }.distinct().sortedBy { it.value }.map { read.itemVersion(it) }
        localStockCheck(items.all { it.storageRevision != null })
        return LocalStockPaymentCut(header, allocations, items, positions)
    } finally { read.paymentResourceObserver = null }
}

/** SAME actual-owner read and finite capture as payment reservations. Issued recipe goods are
 * never re-reserved/re-issued or judged by today's on-hand quantity, safety or lot expiry. */
public suspend fun LocalStockRepository.observePaymentIssuedReservation(view: CommandReadView, reservationId: DomainId,
    observeResource: (suspend (RecoveryResourceKey) -> Unit)? = null): LocalStockPaymentIssuedCut {
    val read = observeRelational(view)
    observeResource?.invoke(RecoveryResourceKey(com.ukkera.pos.recovery.RecoveryResourceKind.STOCK, view.scope.branchId))
    read.paymentResourceObserver = observeResource
    try {
        val allocations = mutableListOf<InventoryAllocationRecord>()
        val header = read.reservationWithMembers(reservationId,
            { _, record -> allocations += record }, { }) ?: missingStock()
        val issueId = header.issueId ?: inconsistentStock()
        val first = read.issueOriginal(issueId) ?: missingStock()
        val issue = read.record(LocalStockRelationalProfileV2.issueKey(issueId)) ?: inconsistentStock()
        val values = mutableListOf<StockRelationalRecordV2>()
        read.query(StockQueryV2.IssueValues(issueId)) { index ->
            val row = read.record(index.identity.key) ?: inconsistentStock()
            localStockCheck(LocalStockRelationalCodecV2.projectRecord(read.scope, row) == index)
            values += row
        }
        return paymentIssuedCut(read.scope, header, allocations, issue, values).also {
            localStockCheck(first.original.identity.producer == it.firstIssueProducer)
        }
    } finally { read.paymentResourceObserver = null }
}

/** Every immutable ISSUE event/value slice must describe exactly its original allocation pool.
 * Complete producer verification remains with the real source-aware reader/traversal. */
private fun paymentIssuedCut(scope: BranchScope, header: InventoryReservationHeader,
    allocations: List<InventoryAllocationRecord>, issueRecord: StockRelationalRecordV2,
    values: List<StockRelationalRecordV2>): LocalStockPaymentIssuedCut {
    val issue = (issueRecord.body as? StockRelationalBody.Issue)?.value ?: inconsistentStock()
    localStockCheck(header.status == ReservationStatus.ISSUED && header.holdCount == 0L && !header.retainsStockClaim &&
        header.issueId == issue.id && issue.reservationId == header.id && issue.binding == header.binding &&
        issue.purpose == ValueIssuePurpose.DIRECT_RECIPE && issue.disposition == KitchenDisposition.ACTIVE &&
        issue.allocationCount == header.allocationCount)
    // A historical reservation deadline is retained literally. It no longer makes these
    // already-consumed ingredients an active reservation or a fresh issue candidate.
    val byAllocation = allocations.associateBy { it.allocation.allocationId }
    localStockCheck(allocations.size.toLong() == header.allocationCount && byAllocation.size == allocations.size &&
        allocations.all { it.reservationId == header.id })
    val slices = linkedMapOf<DomainId, InventoryIssueSlice>()
    val events = linkedMapOf<DomainId, StockRelationalBody.ValueEvent>()
    val valueSlices = linkedMapOf<DomainId, StockRelationalBody.ValueSlice>()
    var producer: StreamedProducerIdentityV2? = null
    for (row in values) {
        localStockCheck(row.generation == Revision(1) && row.previous == null)
        when (val body = row.body) {
            is StockRelationalBody.IssueSlice -> {
                val slice = body.value
                localStockCheck(slice.issueId == issue.id && slice.restoredQuantity.scaled == 0L &&
                    slices.put(slice.allocation.allocationId, slice) == null)
            }
            is StockRelationalBody.ValueEvent -> {
                localStockCheck(body.issueId == issue.id && body.sourceId == issue.id && body.event == StockValueEventKind.ISSUE &&
                    events.put(row.key.id, body) == null && (producer == null || producer == row.producer))
                producer = row.producer
            }
            is StockRelationalBody.ValueSlice -> localStockCheck(body.issueId == issue.id && body.sourceId == issue.id &&
                valueSlices.put(body.allocationId, body) == null)
            else -> inconsistentStock()
        }
    }
    val firstProducer = producer ?: inconsistentStock()
    localStockCheck(values.all { it.producer == firstProducer } && slices.keys == byAllocation.keys &&
        valueSlices.keys == slices.keys && events.isNotEmpty() && events.values.map { it.pool }.distinct().size == events.size)
    var total = Money(issue.originalValue.currency, 0)
    for ((allocationId, slice) in slices) {
        localStockCheck(slice.allocation == byAllocation.getValue(allocationId).allocation)
        val value = valueSlices.getValue(allocationId)
        val event = events[value.eventId] ?: inconsistentStock()
        localStockCheck(value.pool == slice.allocation.position.pool && event.pool == value.pool &&
            value.eventOrdinal == event.ordinal && value.quantity == slice.allocation.quantity && value.originalValue == slice.originalValue)
        total += slice.originalValue
    }
    localStockCheck(slices.size.toLong() == issue.allocationCount && total == issue.originalValue)
    for ((eventId, event) in events) {
        var count = 0L
        var quantity = Quantity(event.quantityDelta.unitId, 0, event.quantityDelta.scale)
        var value = Money(event.originalValueDelta.currency, 0)
        for (slice in valueSlices.values) if (slice.eventId == eventId) {
            count = stockCheckedAdd(count, 1); quantity += slice.quantity; value += slice.originalValue
        }
        localStockCheck(count > 0L && count == event.sliceCount && event.quantityDelta == -quantity &&
            event.originalValueDelta == -value && event.carryingValueDelta == -value)
    }
    return LocalStockPaymentIssuedCut(scope, header, issue, allocations, slices.values.toList(), firstProducer, issueRecord)
}

/** Exact typed original interpretation; sources are literal read evidence, not a historical current view. */
public object LocalStockPaymentObservations {
    private fun records(observed: StockReadObservations): Map<AggregateKey, StockRelationalRecordV2> {
        localStockCheck(observed.sources.none { it is StreamedSourceObservationV2.StockItemAbsent })
        return observed.sources.filterIsInstance<StreamedSourceObservationV2.Current>().filter { it.key.kind in StockRelationalKindsV2.all }.associate { source ->
            source.key to LocalStockRelationalCodecV2.decode(observed.scope, source.key, source.generation,
                source.schema, source.payload, source.payloadHash)
        }
    }

    public fun reservation(observed: StockReadObservations, reservationId: DomainId): LocalStockPaymentCut =
        reservations(observed, listOf(reservationId)).getValue(reservationId)

    /** Complete union for genuine multi-portion FOOD locks; all competing memberships remain mandatory. */
    public fun reservations(observed: StockReadObservations, reservationIds: List<DomainId>): Map<DomainId, LocalStockPaymentCut> {
        localStockCheck(reservationIds.isNotEmpty() && reservationIds.distinct().size == reservationIds.size)
        return custody(observed, reservationIds, emptyList()).reserved
    }

    /** Exact union of reserved and already-issued FOOD custody, including each complete range
     * and permanent claim. Nonstock authority sources remain the commerce owner's obligation. */
    public fun paymentCustody(observed: StockReadObservations, reservedReservationIds: List<DomainId>,
        issuedReservationIds: List<DomainId>): LocalStockPaymentCustody {
        val ids = reservedReservationIds + issuedReservationIds
        localStockCheck(ids.isNotEmpty() && ids.distinct().size == ids.size)
        val identities = mutableSetOf<Pair<String, Any>>()
        val members = mutableSetOf<Pair<StockQueryV2, AggregateKey>>()
        val sources = observed.sources
        val ranges = sources.filterIsInstance<StreamedSourceObservationV2.StockRange>().map { it.query }.toSet()
        for (source in sources) {
            val identity: Pair<String, Any> = when (source) {
                is StreamedSourceObservationV2.Current -> {
                    localStockCheck(source.key.kind !in LocalStockStoreProfile.inventoryKinds && source.key.kind !in stockDocumentKinds)
                    "current" to source.key
                }
                is StreamedSourceObservationV2.CurrentAbsent -> {
                    localStockCheck(source.key.kind !in LocalStockStoreProfile.inventoryKinds && source.key.kind !in stockDocumentKinds &&
                        source.key.kind !in StockRelationalKindsV2.all)
                    "current" to source.key
                }
                is StreamedSourceObservationV2.Claim -> {
                    localStockCheck(source.key.namespace !in stockOwnedClaimNamespaces ||
                        source.key.namespace in LocalStockStoreProfile.inventoryClaimNamespaces)
                    "claim" to source.key
                }
                is StreamedSourceObservationV2.StockRange -> "range" to source.query
                is StreamedSourceObservationV2.StockMember -> {
                    localStockCheck(source.query in ranges && members.add(source.query to source.record.identity.key))
                    "member" to (source.query to source.ordinal)
                }
                else -> inconsistentStock()
            }
            localStockCheck(identities.add(identity))
        }
        return custody(observed, reservedReservationIds, issuedReservationIds)
    }

    private fun custody(observed: StockReadObservations, reservationIds: List<DomainId>,
        issuedReservationIds: List<DomainId>): LocalStockPaymentCustody {
        val records = records(observed)
        val used = mutableSetOf<AggregateKey>()
        val usedClaims = mutableSetOf<ClaimKey>()
        val usedRanges = mutableSetOf<StockQueryV2>()
        fun claim(key: ClaimKey, owner: DomainId) {
            usedClaims += key
            localStockCheck(observed.claim(key)?.ownerId == owner)
        }
        fun range(query: StockQueryV2, consume: (StockIndexRecordV2) -> Unit) {
            usedRanges += query; observed.range(query, consume)
        }
        fun record(key: AggregateKey): StockRelationalRecordV2 { used += key; return records[key] ?: inconsistentStock() }
        fun header(id: DomainId): StockRelationalBody.Reservation {
            claim(LocalStockStoreProfile.reservationClaim(id), id)
            return record(LocalStockRelationalProfileV2.reservationKey(id)).body as? StockRelationalBody.Reservation ?: inconsistentStock()
        }
        fun member(index: StockIndexRecordV2): StockRelationalRecordV2 = record(index.identity.key).also {
            localStockCheck(LocalStockRelationalCodecV2.projectRecord(observed.scope, it) == index)
        }
        fun requireAllocation(reservation: StockRelationalBody.Reservation, value: InventoryAllocationRecord) {
            val allocation = value.allocation
            localStockCheck(value.reservationId == reservation.value.id && allocation.allocationId == LocalStockRelationalProfileV2.allocationId(
                reservation.allocationSetId, reservation.allocationSetRevision, allocation.lineId, allocation.position))
            claim(LocalStockStoreProfile.allocationClaim(allocation.allocationId), value.reservationId)
        }
        fun members(reservation: StockRelationalBody.Reservation): List<InventoryAllocationRecord> {
            val result = mutableListOf<InventoryAllocationRecord>()
            var holds = 0L
            range(StockQueryV2.ReservationMembers(reservation.value.id)) { index -> when (val body = member(index).body) {
                is StockRelationalBody.Allocation -> { requireAllocation(reservation, body.value); result += body.value }
                is StockRelationalBody.Hold -> { localStockCheck(body.value.reservationId == reservation.value.id); holds = stockCheckedAdd(holds, 1) }
                else -> inconsistentStock()
            } }
            localStockCheck(result.size.toLong() == reservation.value.allocationCount && holds == reservation.value.holdCount)
            return result
        }
        val activation = record(LocalStockRelationalProfileV2.activationKey(observed.scope.branchId)).body as? StockRelationalBody.Activation
            ?: inconsistentStock()
        localStockCheck(stockActivationShape(activation) && activation.sourceProfile == STOCK_FRESH_PROFILE_V2)
        val result = linkedMapOf<DomainId, LocalStockPaymentCut>()
        for (reservationId in reservationIds) {
            val selected = header(reservationId)
            val allocations = members(selected)
            val own = linkedMapOf<StockPositionKey, Quantity>()
            for (record in allocations) {
                val allocation = record.allocation
                own[allocation.position] = own[allocation.position]?.plus(allocation.quantity) ?: allocation.quantity
            }
            val positions = linkedMapOf<StockPositionKey, Pair<InventoryPositionAvailability, Quantity>>()
            val checkedHeaders = mutableSetOf<DomainId>(reservationId)
            for ((position, quantity) in own) {
                val literal = (record(LocalStockRelationalProfileV2.positionKey(position)).body as? StockRelationalBody.Position)?.value
                    ?: inconsistentStock()
                localStockCheck(literal.key == position)
                var held = Quantity(literal.onHand.unitId, 0, literal.onHand.scale)
                val actualOwn = mutableSetOf<DomainId>()
                range(StockQueryV2.PositionMembers(LocalStockRelationalProfileV2.positionKey(position).id)) { index ->
                    when (val body = member(index).body) {
                        is StockRelationalBody.Allocation -> {
                            localStockCheck(body.value.allocation.position == position)
                            val owner = header(body.value.reservationId)
                            requireAllocation(owner, body.value)
                            if (checkedHeaders.add(owner.value.id)) members(owner)
                            if (owner.value.retainsStockClaim) held += body.value.allocation.quantity
                            if (owner.value.id == reservationId) actualOwn += body.value.allocation.allocationId
                        }
                        is StockRelationalBody.Hold -> localStockCheck(body.value.hold.position == position)
                        else -> inconsistentStock()
                    }
                }
                localStockCheck(actualOwn == allocations.filter { it.allocation.position == position }.map { it.allocation.allocationId }.toSet())
                positions[position] = InventoryPositionAvailability(literal, held) to quantity
            }
            val items = own.keys.map { it.pool.skuId }.distinct().sortedBy { it.value }.map { id ->
                val item = record(LocalStockRelationalProfileV2.itemKey(id))
                localStockCheck(item.body is StockRelationalBody.Item)
                claim(LocalStockStoreProfile.itemClaim(id), id)
                LocalStockItemVersion(id, item.generation)
            }
            result[reservationId] = LocalStockPaymentCut(selected.value, allocations, items, positions)
        }
        val issued = linkedMapOf<DomainId, LocalStockPaymentIssuedCut>()
        for (reservationId in issuedReservationIds) {
            val selected = header(reservationId)
            val allocations = members(selected)
            val issueId = selected.value.issueId ?: inconsistentStock()
            claim(LocalStockStoreProfile.sourceClaim(issueId), issueId)
            val issue = record(LocalStockRelationalProfileV2.issueKey(issueId))
            val values = mutableListOf<StockRelationalRecordV2>()
            range(StockQueryV2.IssueValues(issueId)) { values += member(it) }
            issued[reservationId] = paymentIssuedCut(observed.scope, selected.value, allocations, issue, values)
        }
        // Every normalized read is accounted for: selected positions, full holder reservations,
        // their literal allocations/holds, activation, selected SKU generations and issued values.
        localStockCheck(records.keys == used &&
            observed.sources.filterIsInstance<StreamedSourceObservationV2.StockRange>().map { it.query }.toSet() == usedRanges &&
            observed.sources.filterIsInstance<StreamedSourceObservationV2.Claim>()
                .filter { it.key.namespace in LocalStockStoreProfile.inventoryClaimNamespaces }.map { it.key }.toSet() == usedClaims)
        return LocalStockPaymentCustody(result, issued)
    }

    /** Genuine retained first issue on the actual loan, including ancestor-only recovery.
     * The full captured source-original closure remains mandatory; no current issue is read. */
    public suspend fun requireIssuedOriginal(view: CommandReadView, stock: LocalStockRepository,
        cut: LocalStockPaymentIssuedCut): LocalStockIssueOriginal {
        localStockCheck(view.scope == cut.scope)
        val actual = (view.requireStockRelational().originals.read(cut.firstIssueProducer.operationId) as? RetainedOriginalV2.Streamed)?.evidence
            ?: unavailableProducer()
        localStockCheck(actual.identity.scope == cut.scope && actual.identity.producer == cut.firstIssueProducer)
        stock.requireRelationalProducer(view, actual)
        return stockIssueOriginal(actual, cut.issue.id, cut.issue)
    }

    /** SAME still-active complete stock traversal, including the required first producer in
     * its cumulative queue. No DATA observation is converted into a streamed original. */
    public suspend fun requireIssuedOriginal(stock: LocalStockRelationalInspection,
        cut: LocalStockPaymentIssuedCut): LocalStockIssueOriginal {
        localStockCheck(stock.scope == cut.scope)
        val first = (stock.original(cut.firstIssueProducer.operationId) as? RetainedOriginalV2.Streamed)?.evidence
            ?: unavailableProducer()
        localStockCheck(first.identity.scope == cut.scope && first.identity.producer == cut.firstIssueProducer)
        stock.requireCompleteOriginal(first)
        return stockIssueOriginal(first, cut.issue.id, cut.issue)
    }

    /** Core-selected earlier original on THIS native historical command, after its genuine
     * complete producer join. The literal current issue must also match this command's cut. */
    public suspend fun requireIssuedOriginal(command: RuntimeHistoricalCommand,
        cut: LocalStockPaymentIssuedCut): LocalStockIssueOriginal {
        localStockCheck(command.scope == cut.scope)
        val source = command.aggregateSource(cut.issueRecord.key) ?: inconsistentStock()
        val row = source.row
        localStockCheck(row.key == cut.issueRecord.key && row.revision == cut.issueRecord.generation &&
            row.schema == cut.issueRecord.schema && row.payload == cut.issueRecord.payload && row.digest == cut.issueRecord.digest)
        val first = (command.earlierOriginal(cut.firstIssueProducer.operationId) as? RetainedOriginalV2.Streamed)?.evidence
            ?: unavailableProducer()
        localStockCheck(first.identity.scope == cut.scope && first.identity.producer == cut.firstIssueProducer)
        return stockIssueOriginal(first, cut.issue.id, cut.issue)
    }

    public suspend fun resources(observed: StockReadObservations): Set<RecoveryResourceKey> {
        val result = linkedSetOf<RecoveryResourceKey>()
        for ((key, value) in records(observed)) stockResourceAliases(key, value.body) { result += it }
        return result.toSet()
    }

    /** Actual retained originals on the real view's loan. No accepting resolver or fake streamed command. */
    public suspend fun requireOriginalStock(view: CommandReadView, stock: LocalStockRepository, observed: StockReadObservations, owner: RetainedCommandEvidence) {
        localStockCheck(view.scope == observed.scope && owner.scope == observed.scope && owner.committed)
        val access = view.requireStockRelational()
        for ((operationId, rows) in records(observed).values.groupBy { it.producer.operationId }) {
            val original = (access.originals.read(operationId) as? RetainedOriginalV2.Streamed)?.evidence ?: unavailableProducer()
            localStockCheck(original.identity.scope == owner.scope && original.identity.producer.operationId != owner.operationId &&
                (original.identity.assignmentId != owner.assignmentId || original.identity.authorityEpoch != owner.authorityEpoch ||
                    original.identity.sequence < owner.sequence))
            stock.requireRelationalProducer(view, original)
            requireOriginalStockWrites(original, rows)
        }
    }

    /** Genuine original-only commerce consumer on the SAME still-active stock traversal.
     * The inspection owns all original lookups/complete queued producers and cumulative bounds;
     * no current view, accepting callback, fabricated record or earlier-id certificate is used. */
    public suspend fun requireOriginalStock(stock: LocalStockRelationalInspection,
        observed: StockReadObservations, owner: RetainedCommandEvidence) {
        localStockCheck(stock.scope == observed.scope && owner.scope == observed.scope && owner.committed)
        for ((operationId, rows) in records(observed).values.groupBy { it.producer.operationId }) {
            val original = (stock.original(operationId) as? RetainedOriginalV2.Streamed)?.evidence ?: unavailableProducer()
            localStockCheck(original.identity.scope == owner.scope && original.identity.producer.operationId != owner.operationId &&
                (original.identity.assignmentId != owner.assignmentId || original.identity.authorityEpoch != owner.authorityEpoch ||
                    original.identity.sequence < owner.sequence))
            stock.requireCompleteOriginal(original)
            requireOriginalStockWrites(original, rows)
        }
    }

    /** All selected records belong to this actual qualified producer. Match the complete
     * finite set during one genuine stream traversal, before returning any proof. No stream,
     * row or successful qualification is cached for another read or native/Room lifetime. */
    private suspend fun requireOriginalStockWrites(original: RetainedStreamedCommand,
        rows: List<StockRelationalRecordV2>) {
        localStockCheck(rows.isNotEmpty())
        val selected = linkedMapOf<AggregateKey, StockRelationalRecordV2>()
        for (row in rows) {
            localStockCheck(original.identity.producer == row.producer)
            localStockCheck(selected.put(row.key, row) == null, LocalStockFailure.STORAGE_INCONSISTENT)
        }
        val found = mutableSetOf<AggregateKey>()
        original.frames { frame ->
            val write = (frame as? RetainedCommandFrameV2.Write)?.value ?: return@frames
            val row = selected[write.key] ?: return@frames
            localStockCheck(found.add(write.key))
            requireSameWrite(write, row.key, row.generation, row.schema, row.payload, row.digest)
        }
        localStockCheck(found == selected.keys, LocalStockFailure.STORAGE_INCONSISTENT)
    }

}
