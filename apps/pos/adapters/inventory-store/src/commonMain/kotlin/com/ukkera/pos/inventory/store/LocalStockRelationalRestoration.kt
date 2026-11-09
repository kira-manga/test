package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.types.*
import kotlinx.serialization.Serializable

/** Only the already bounded station facts travel in the action. Allocations remain per-ID rows. */
@Serializable internal data class StockKitchenConfirmationV2Wire(
    val stationId: DomainId, val confirmationId: DomainId, val actorId: DomainId,
    val recordedAt: UtcInstant, val custody: Int,
) {
    fun restore(): LocalStockKitchenConfirmation = LocalStockKitchenConfirmation(stationId, confirmationId,
        actorId, recordedAt, stockV2Custody(custody))
    companion object {
        fun from(value: LocalStockKitchenConfirmation): StockKitchenConfirmationV2Wire = StockKitchenConfirmationV2Wire(
            value.stationId, value.confirmationId, value.actorId, value.recordedAt, stockV2CustodyTag(value.requestedCustody))
    }
}

internal fun StockIntentV2Wire.Restore.request(): LocalStockKitchenRestoration =
    LocalStockKitchenRestoration(restorationId, issueId, revision, confirmations.map { it.restore() })

internal fun stockRestorationIntent(request: LocalStockKitchenRestoration): StockIntentV2Wire.Restore =
    StockIntentV2Wire.Restore(request.restorationId, request.issueId, request.expectedRevision,
        request.confirmations.map(StockKitchenConfirmationV2Wire::from))

internal suspend fun stockRestorationForIssue(stock: LocalStockRelationalInspection, id: DomainId): StockIntentV2Wire.Restore? {
    var found: StockIntentV2Wire.Restore? = null
    stock.intents { if (it is StockIntentV2Wire.Restore && it.issueId == id) { localStockCheck(found == null); found = it } }
    return found
}

internal suspend fun stockRestorationForSource(stock: LocalStockRelationalInspection, id: DomainId): StockIntentV2Wire.Restore? {
    var found: StockIntentV2Wire.Restore? = null
    stock.intents { if (it is StockIntentV2Wire.Restore && it.restorationId == id) { localStockCheck(found == null); found = it } }
    return found
}

/** Shape is not station authority: the installed WHOLE food verifier must join every fact to
 * the actual before/after task, including earlier confirmations' genuine retained producers. */
internal fun requireStockRestorationRequest(request: LocalStockKitchenRestoration,
    issue: LocalStockIssueHeader, actorId: DomainId, at: UtcInstant) {
    localStockCheck(issue.id == request.issueId && issue.revision == request.expectedRevision, LocalStockFailure.STALE_REVISION)
    localStockCheck(issue.purpose == ValueIssuePurpose.DIRECT_RECIPE && issue.disposition == KitchenDisposition.ACTIVE &&
        issue.startedStationIds.isEmpty() && request.confirmations.size == issue.requiredStationIds.size &&
        request.confirmations.map { it.stationId }.toSet() == issue.requiredStationIds.toSet() &&
        request.confirmations.any { it.actorId == actorId && it.recordedAt == at }, LocalStockFailure.UNSUPPORTED_VALUE_LINEAGE)
}

internal suspend fun stockRestorationResources(request: LocalStockKitchenRestoration,
    consume: suspend (RecoveryResourceKey) -> Unit) {
    consume(RecoveryResourceKey(RecoveryResourceKind.STOCK, request.restorationId))
    consume(RecoveryResourceKey(RecoveryResourceKind.STOCK, request.issueId))
    for (confirmation in request.confirmations) {
        consume(RecoveryResourceKey(RecoveryResourceKind.KITCHEN_CUSTODY, confirmation.stationId))
        consume(RecoveryResourceKey(RecoveryResourceKind.KITCHEN_CUSTODY, confirmation.confirmationId))
        consume(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, confirmation.actorId))
    }
}

internal fun stockValueEventKey(pool: StockPoolKey, sourceId: DomainId): AggregateKey =
    AggregateKey(StockRelationalKindsV2.VALUE_EVENT, localStockId("v2.value-event",
        "${sourceId.value}/${LocalStockRelationalProfileV2.poolKey(pool).id.value}"))

internal fun stockValueSliceKey(eventId: DomainId, allocationId: DomainId): AggregateKey =
    AggregateKey(StockRelationalKindsV2.VALUE_SLICE, localStockId("v2.value-slice", "${eventId.value}/${allocationId.value}"))

internal fun stockRestorationDestination(request: LocalStockKitchenRestoration, source: StockPosition, now: Long): StockPositionKey {
    localStockCheck(source.key.pool.ownership == StockOwnership.CompanyOwned && source.key.custody == StockCustody.RELEASED &&
        source.key.serialId == null, LocalStockFailure.UNSUPPORTED_VALUE_LINEAGE)
    val released = request.confirmations.all { it.requestedCustody == StockCustody.RELEASED } && source.isSellableAt(now)
    return source.key.copy(custody = if (released) StockCustody.RELEASED else StockCustody.QUARANTINE)
}

internal fun stockRestorationPosition(before: StockPosition?, source: StockPosition,
    destination: StockPositionKey, quantity: Quantity, now: Long): StockPosition {
    localStockCheck(destination.pool == source.key.pool && destination.lotId == source.key.lotId &&
        destination.serialId == null && source.key.serialId == null && quantity.scaled > 0 &&
        quantity.unitId == source.onHand.unitId && quantity.scale == source.onHand.scale &&
        destination.custody in setOf(StockCustody.RELEASED, StockCustody.QUARANTINE) &&
        (destination.custody != StockCustody.RELEASED || source.isSellableAt(now)))
    localStockCheck(before == null || before.key == destination && before.expiresAtEpochMillis == source.expiresAtEpochMillis &&
        before.serialRegistrationId == source.serialRegistrationId && before.onHand.unitId == quantity.unitId && before.onHand.scale == quantity.scale)
    localStockCheck(before != null || destination.custody == StockCustody.QUARANTINE)
    val observedSafety = if (source.safety == StockSafety.CLEAR && source.expiresAtEpochMillis?.let { it <= now } == true)
        StockSafety.EXPIRED else source.safety
    // Match original-return custody semantics: an existing unsafe destination is never cleared.
    val safety = before?.safety?.takeUnless { it == StockSafety.CLEAR } ?: observedSafety
    return StockPosition(destination, (before?.onHand ?: stockZeroQuantity(quantity)) + quantity, safety,
        source.expiresAtEpochMillis, source.serialRegistrationId)
}

internal fun stockHistoryAccess(read: LocalStockRelationalRead): StockPoolHistoryAccess = object : StockPoolHistoryAccess {
    override suspend fun normal(key: AggregateKey): StockRelationalRecordV2? = read.record(key)
    override suspend fun claim(key: ClaimKey): DomainId? = read.claim(key)
    override suspend fun range(query: StockQueryV2, consume: suspend (StockRelationalRecordV2) -> Unit) {
        read.query(query) { index ->
            val row = read.record(index.identity.key) ?: inconsistentStock()
            localStockCheck(stockIndexOf(read.scope, row) == index); consume(row)
        }
    }
}

internal fun stockHistoryAccess(stock: LocalStockRelationalInspection): StockPoolHistoryAccess = object : StockPoolHistoryAccess {
    override suspend fun normal(key: AggregateKey): StockRelationalRecordV2? = stock.before(key)
    override suspend fun claim(key: ClaimKey): DomainId? = stock.observedClaim(key)
    override suspend fun range(query: StockQueryV2, consume: suspend (StockRelationalRecordV2) -> Unit) = stock.range(query, consume)
}

/** Exact immutable ISSUE event/slice join. A current restored slice's predecessor is its genuine
 * original generation, never a reconstructed original or a current-average replacement. */
internal suspend fun stockOriginalSliceEvent(read: StockPoolHistoryAccess, row: StockRelationalRecordV2): StockRelationalRecordV2 {
    val slice = (row.body as? StockRelationalBody.IssueSlice)?.value ?: inconsistentStock()
    val key = stockValueEventKey(slice.allocation.position.pool, slice.issueId)
    val eventRow = read.normal(key) ?: inconsistentStock()
    val event = eventRow.body as? StockRelationalBody.ValueEvent ?: inconsistentStock()
    val valueRow = read.normal(stockValueSliceKey(key.id, slice.allocation.allocationId)) ?: inconsistentStock()
    val value = valueRow.body as? StockRelationalBody.ValueSlice ?: inconsistentStock()
    localStockCheck(eventRow.generation == Revision(1) && eventRow.previous == null && valueRow.generation == Revision(1) &&
        valueRow.previous == null && valueRow.producer == eventRow.producer && event.event == StockValueEventKind.ISSUE &&
        event.pool == slice.allocation.position.pool && event.sourceId == slice.issueId && event.issueId == slice.issueId &&
        value.pool == event.pool && value.sourceId == slice.issueId && value.eventId == key.id && value.eventOrdinal == event.ordinal &&
        value.issueId == slice.issueId && value.allocationId == slice.allocation.allocationId &&
        value.quantity == slice.allocation.quantity && value.originalValue == slice.originalValue &&
        read.claim(LocalStockStoreProfile.sourceClaim(slice.issueId)) == slice.issueId)
    if (row.generation == Revision(1))
        localStockCheck(row.previous == null && row.producer == eventRow.producer && slice.restoredQuantity.scaled == 0L)
    else {
        val issue = (read.normal(LocalStockRelationalProfileV2.issueKey(slice.issueId))?.body
            as? StockRelationalBody.Issue)?.value ?: inconsistentStock()
        when (issue.purpose) {
            ValueIssuePurpose.DIRECT_RECIPE -> localStockCheck(row.generation == Revision(2) &&
                row.previous?.revision == Revision(1) && row.previous?.operationId == eventRow.producer.operationId &&
                slice.restoredQuantity == slice.allocation.quantity, LocalStockFailure.UNSUPPORTED_VALUE_LINEAGE)
            ValueIssuePurpose.RETAIL -> localStockCheck(row.generation.value >= 2 &&
                row.previous?.revision?.value == row.generation.value - 1 && slice.restoredQuantity.scaled > 0 &&
                slice.allocation.position.serialId == null && row.producer.command == LocalStockRelationalProfileV2.RETURN_COMMAND &&
                eventRow.producer.command == LocalStockRelationalProfileV2.COLLECTION_COMMAND,
                LocalStockFailure.UNSUPPORTED_VALUE_LINEAGE)
            else -> throw LocalStockRejected(LocalStockFailure.UNSUPPORTED_VALUE_LINEAGE)
        }
    }
    return eventRow
}

/** Generic commerce source closure needs complete memberships of the historical issues loaded
 * by a pool fold too. This streams real current rows; it does not synthesize before snapshots. */
internal suspend fun captureStockPoolIssueMembers(read: LocalStockRelationalRead, pool: StockPoolKey) {
    read.query(StockQueryV2.PoolValues(LocalStockRelationalProfileV2.poolKey(pool).id)) { index ->
        val row = read.record(index.identity.key) ?: inconsistentStock()
        (row.body as? StockRelationalBody.IssueSlice)?.value?.let { read.issue(it.issueId) ?: inconsistentStock() }
    }
}

@Serializable private data class StockRestorationSliceV2Wire(val allocation: AllocationV2Wire, val originalValue: Money)

/** Whole original portion restoration on the caller's actual, still-open stock/food sink. The
 * only in-memory collections are bounded station evidence; pool and lot membership is spooled. */
internal suspend fun restoreStockRecipeBeforeStart(work: StockPostingBuilder, request: LocalStockKitchenRestoration,
    now: UtcInstant, observeExpiry: suspend (StockPosition) -> Unit) {
    work.requireOpen()
    val read = work.read; val sink = work.sink; val context = work.context
    localStockCheck(context.key == LocalStockRelationalProfileV2.FOOD_COMMAND)
    val issue = read.issue(request.issueId) ?: missingStock()
    requireStockRestorationRequest(request, issue, context.originalActorId, now)
    val first = read.issueOriginal(issue.id) ?: inconsistentStock()
    val reservation = read.reservation(issue.reservationId) ?: inconsistentStock()
    localStockCheck(reservation.status == ReservationStatus.ISSUED && reservation.issueId == issue.id &&
        reservation.binding == issue.binding && reservation.allocationCount == issue.allocationCount)
    work.acquire(LocalStockStoreProfile.sourceClaim(request.restorationId), request.restorationId)
    work.intent(stockRestorationIntent(request))
    val history = stockHistoryAccess(read)
    var count = 0L; var totalValue = Money(issue.originalValue.currency, 0)
    read.issueAllocations(issue.id) { slice ->
        val row = read.record(LocalStockRelationalProfileV2.issueSliceKey(issue.id, slice.allocation.allocationId)) ?: inconsistentStock()
        localStockCheck(row.generation == Revision(1) && row.previous == null && row.producer == first.original.identity.producer &&
            row.body == StockRelationalBody.IssueSlice(slice) && slice.restoredQuantity.scaled == 0L &&
            slice.allocation.position.serialId == null)
        stockOriginalSliceEvent(history, row)
        val allocation = read.record(LocalStockRelationalProfileV2.allocationKey(slice.allocation.allocationId))?.body
            as? StockRelationalBody.Allocation ?: inconsistentStock()
        localStockCheck(allocation.value == InventoryAllocationRecord(issue.reservationId, slice.allocation))
        val poolId = LocalStockRelationalProfileV2.poolKey(slice.allocation.position.pool).id
        val group = issuePool(request.restorationId, poolId)
        val ref = stockEncode(StockPoolRefWire.from(slice.allocation.position.pool), StockPoolRefWire.serializer())
        val prior = sink.scratch.get(StockWorkingSpace.MEMBERSHIP, request.restorationId, poolId)
        if (prior == null) sink.scratch.insert(StockWorkingSpace.MEMBERSHIP, StockWorkingRecord(request.restorationId, poolId, 0, ref))
        else localStockCheck(prior.payload == ref)
        val amount = InventoryValuationProjection.originalRestorationValue(slice, slice.allocation.quantity)
        localStockCheck(amount == slice.originalValue)
        sink.scratch.insert(StockWorkingSpace.COST_SLICE, StockWorkingRecord(group, slice.allocation.allocationId, 0,
            stockEncode(StockRestorationSliceV2Wire(AllocationV2Wire.from(slice.allocation), amount), StockRestorationSliceV2Wire.serializer())))
        count = stockCheckedAdd(count, 1); totalValue += amount
    }
    localStockCheck(count == issue.allocationCount && totalValue == issue.originalValue)
    sink.scratch.forEach(StockWorkingSpace.MEMBERSHIP, request.restorationId) { member ->
        val poolKey = stockDecode(member.payload, StockPoolRefWire.serializer()).key(context.scope)
        localStockCheck(member.recordId == LocalStockRelationalProfileV2.poolKey(poolKey).id)
        val before = read.pool(poolKey) ?: inconsistentStock()
        localStockCheck((work.body(LocalStockRelationalProfileV2.poolKey(poolKey)) as? StockRelationalBody.Pool)?.value == before)
        // Exact closed frontier: corrections/linked transfers remain unavailable, never ignored.
        requireStockPoolHistory(read, before)
        captureStockPoolIssueMembers(read, poolKey)
        read.query(StockQueryV2.Positions(poolKey.skuId, poolKey.warehouseId)) { index ->
            val position = (read.record(index.identity.key)?.body as? StockRelationalBody.Position)?.value ?: inconsistentStock()
            if (position.key.pool == poolKey && position.safety == StockSafety.CLEAR &&
                position.expiresAtEpochMillis?.let { it <= now.value.toEpochMilliseconds() } == true) observeExpiry(position)
        }
        val group = issuePool(request.restorationId, member.recordId)
        var quantity = stockZeroQuantity(before.onHand); var value = Money(before.carryingValue.currency, 0); var slices = 0L
        sink.scratch.forEach(StockWorkingSpace.COST_SLICE, group) { row ->
            val slice = stockDecode(row.payload, StockRestorationSliceV2Wire.serializer())
            val allocation = slice.allocation.restore(context.scope)
            localStockCheck(allocation.position.pool == poolKey && allocation.allocationId == row.recordId)
            quantity += allocation.quantity; value += slice.originalValue; slices = stockCheckedAdd(slices, 1)
        }
        val delta = InventoryValuationProjection.receive(before, quantity, value)
        val event = StockRelationalBody.ValueEvent(poolKey, request.restorationId, delta.after.lastEventOrdinal,
            StockValueEventKind.RESTORE, before, delta.after, quantity, value, value, slices, issue.id)
        val eventId = stockKey(event).id
        sink.scratch.forEach(StockWorkingSpace.COST_SLICE, group) { row ->
            val slice = stockDecode(row.payload, StockRestorationSliceV2Wire.serializer())
            val allocation = slice.allocation.restore(context.scope)
            val old = (read.record(LocalStockRelationalProfileV2.issueSliceKey(issue.id, allocation.allocationId))?.body
                as? StockRelationalBody.IssueSlice)?.value ?: inconsistentStock()
            localStockCheck(old.allocation == allocation && old.originalValue == slice.originalValue && old.restoredQuantity.scaled == 0L)
            val source = (work.body(LocalStockRelationalProfileV2.positionKey(allocation.position)) as? StockRelationalBody.Position)?.value
                ?: inconsistentStock()
            val destination = stockRestorationDestination(request, source, now.value.toEpochMilliseconds())
            val prior = (work.body(LocalStockRelationalProfileV2.positionKey(destination)) as? StockRelationalBody.Position)?.value
            work.put(StockRelationalBody.Position(stockRestorationPosition(prior, source, destination, allocation.quantity,
                now.value.toEpochMilliseconds())))
            if (destination.custody == StockCustody.RELEASED) work.beforeMillis(source.expiresAtEpochMillis)
            work.put(StockRelationalBody.IssueSlice(old.copy(restoredQuantity = allocation.quantity)))
            work.insert(StockRelationalBody.ValueSlice(poolKey, request.restorationId, eventId, event.ordinal,
                issue.id, allocation.allocationId, allocation.quantity, slice.originalValue))
            work.intent(StockIntentV2Wire.Movement(request.restorationId, allocation.allocationId,
                PositionKeyV2Wire.from(destination), allocation.quantity, 2))
        }
        work.insert(event); work.put(StockRelationalBody.Pool(delta.after))
    }
    work.put(StockRelationalBody.Issue(copyIssueHeader(issue, issue.revision.next(), emptyList(), KitchenDisposition.CANCELLED_BEFORE_START)))
    // The original issued reservation/binding and all permanent issue/allocation claims remain.
}

/** Full after-start refusal and before/after slice census. Quantities and costs cannot be
 * selected by the caller, nor can an issued paid/unknown owner be silently released. */
internal suspend fun verifyStockRestorationIntent(stock: LocalStockRelationalInspection, intent: StockIntentV2Wire.Restore) {
    localStockCheck(stock.original.identity.producer.command == LocalStockRelationalProfileV2.FOOD_COMMAND)
    val request = intent.request()
    localStockCheck(stockRestorationIntent(request) == intent)
    val before = stock.beforeIssue(intent.issueId) ?: inconsistentStock()
    requireStockRestorationRequest(request, before, stock.original.identity.producer.originalActorId, stock.recordedAt)
    val after = stock.issue(intent.issueId) ?: inconsistentStock()
    localStockCheck(IssueV2Wire.from(after) == IssueV2Wire.from(copyIssueHeader(before, before.revision.next(), emptyList(),
        KitchenDisposition.CANCELLED_BEFORE_START)))
    val owner = stock.beforeReservation(before.reservationId) ?: inconsistentStock()
    localStockCheck(owner.status == ReservationStatus.ISSUED && owner.issueId == before.id && owner.binding == before.binding &&
        owner.allocationCount == before.allocationCount && stock.reservation(owner.id) == null)
    val claim = stock.claim(LocalStockStoreProfile.sourceClaim(intent.restorationId)) ?: inconsistentStock()
    localStockCheck(claim.action == RetainedClaimAction.ACQUIRE && claim.ownerId == intent.restorationId &&
        stock.observedClaim(claim.key) == null)
    val first = stock.issueOriginal(before.id) ?: inconsistentStock()
    val history = stockHistoryAccess(stock)
    var count = 0L; var total = Money(before.originalValue.currency, 0)
    stock.beforeIssueSlices(before.id) { slice ->
        val key = LocalStockRelationalProfileV2.issueSliceKey(before.id, slice.allocation.allocationId)
        val row = stock.before(key) ?: inconsistentStock()
        localStockCheck(row.generation == Revision(1) && row.previous == null && row.producer == first.original.identity.producer &&
            slice.restoredQuantity.scaled == 0L && slice.allocation.position.serialId == null)
        stockOriginalSliceEvent(history, row)
        val allocationKey = LocalStockRelationalProfileV2.allocationKey(slice.allocation.allocationId)
        val allocation = (stock.before(allocationKey)?.body as? StockRelationalBody.Allocation)?.value ?: inconsistentStock()
        localStockCheck(allocation == InventoryAllocationRecord(before.reservationId, slice.allocation) && stock.write(allocationKey) == null)
        localStockCheck((stock.write(key)?.body as? StockRelationalBody.IssueSlice)?.value == slice.copy(restoredQuantity = slice.allocation.quantity))
        requireStockRestorationSliceEffects(stock, request, slice)
        count = stockCheckedAdd(count, 1); total += slice.originalValue
    }
    localStockCheck(count == before.allocationCount && total == before.originalValue)
}

internal suspend fun verifyStockRestoredSlice(stock: LocalStockRelationalInspection,
    record: StockRelationalRecordV2, slice: InventoryIssueSlice) {
    val retail = stockRetailReturnForIssue(stock, slice.issueId)
    if (retail != null) { verifyStockRetailReturnedSlice(stock, record, slice, retail); return }
    val before = stock.before(record.key) ?: inconsistentStock()
    val old = (before.body as? StockRelationalBody.IssueSlice)?.value ?: inconsistentStock()
    val intent = stockRestorationForIssue(stock, slice.issueId) ?: inconsistentStock()
    localStockCheck(before.generation == Revision(1) && before.previous == null && record.generation == Revision(2) &&
        old.restoredQuantity.scaled == 0L && slice == old.copy(restoredQuantity = old.allocation.quantity) &&
        stock.issue(slice.issueId)?.disposition == KitchenDisposition.CANCELLED_BEFORE_START)
    stockOriginalSliceEvent(stockHistoryAccess(stock), before)
    requireStockRestorationSliceEffects(stock, intent.request(), old)
}

private suspend fun requireStockRestorationSliceEffects(stock: LocalStockRelationalInspection,
    request: LocalStockKitchenRestoration, slice: InventoryIssueSlice) {
    val eventKey = stockValueEventKey(slice.allocation.position.pool, request.restorationId)
    val event = stock.write(eventKey)?.body as? StockRelationalBody.ValueEvent ?: inconsistentStock()
    val restored = stock.write(stockValueSliceKey(eventKey.id, slice.allocation.allocationId))?.body as? StockRelationalBody.ValueSlice
        ?: inconsistentStock()
    localStockCheck(event.event == StockValueEventKind.RESTORE && event.issueId == slice.issueId && event.sourceId == request.restorationId &&
        restored == StockRelationalBody.ValueSlice(slice.allocation.position.pool, request.restorationId, eventKey.id, event.ordinal,
            slice.issueId, slice.allocation.allocationId, slice.allocation.quantity, slice.originalValue))
    val source = (stock.before(LocalStockRelationalProfileV2.positionKey(slice.allocation.position))?.body as? StockRelationalBody.Position)?.value
        ?: inconsistentStock()
    val destination = stockRestorationDestination(request, source, stock.recordedAt.value.toEpochMilliseconds())
    val movement = StockIntentV2Wire.Movement(request.restorationId, slice.allocation.allocationId,
        PositionKeyV2Wire.from(destination), slice.allocation.quantity, 2)
    localStockCheck(stock.intent(stockIntentId(movement)) == movement &&
        stock.write(LocalStockRelationalProfileV2.positionKey(destination))?.body is StockRelationalBody.Position)
}

/** Positive movement has exactly one original allocation owner and one exact restored slice. */
internal suspend fun verifyStockRestorationMovement(stock: LocalStockRelationalInspection,
    movement: StockIntentV2Wire.Movement): InventoryIssueSlice {
    localStockCheck(movement.reason == 2 && movement.quantity.scaled > 0 && movement.allocationId != null)
    val retail = stockRetailReturnForSource(stock, movement.sourceId)
    if (retail != null) return verifyStockRetailReturnMovement(stock, movement, retail)
    val intent = stockRestorationForSource(stock, movement.sourceId) ?: inconsistentStock()
    val key = LocalStockRelationalProfileV2.issueSliceKey(intent.issueId, checkNotNull(movement.allocationId))
    val before = (stock.before(key)?.body as? StockRelationalBody.IssueSlice)?.value ?: inconsistentStock()
    val after = (stock.write(key)?.body as? StockRelationalBody.IssueSlice)?.value ?: inconsistentStock()
    val source = (stock.before(LocalStockRelationalProfileV2.positionKey(before.allocation.position))?.body as? StockRelationalBody.Position)?.value
        ?: inconsistentStock()
    localStockCheck(before.restoredQuantity.scaled == 0L && after == before.copy(restoredQuantity = before.allocation.quantity) &&
        movement.quantity == before.allocation.quantity && movement.position.restore(stock.scope) ==
        stockRestorationDestination(intent.request(), source, stock.recordedAt.value.toEpochMilliseconds()))
    return before
}

/** Expiry is an actual observation of every position in a restored original pool, not a new
 * inventory-administration permission for arbitrary food commands. */
internal suspend fun stockRestoresPool(stock: LocalStockRelationalInspection, pool: StockPoolKey): Boolean {
    var found = false
    stock.records { row ->
        val event = row.body as? StockRelationalBody.ValueEvent
        if (event?.pool == pool && event.event == StockValueEventKind.RESTORE) {
            val recipe = stockRestorationForSource(stock, event.sourceId)
            val retail = stockRetailReturnForSource(stock, event.sourceId)
            localStockCheck((recipe != null) != (retail != null) &&
                (recipe?.issueId ?: retail?.issueId) == event.issueId); found = true
        }
    }
    return found
}

private suspend fun observedRestorationExpiry(stock: LocalStockRelationalInspection, before: StockPosition): StockPosition {
    if (before.safety != StockSafety.CLEAR || before.expiresAtEpochMillis?.let { it <= stock.recordedAt.value.toEpochMilliseconds() } != true)
        return before
    val intent = StockIntentV2Wire.Safety(PositionKeyV2Wire.from(before.key), 2)
    localStockCheck(stock.intent(stockIntentId(intent)) == intent &&
        (stock.write(LocalStockRelationalProfileV2.positionKey(before.key))?.body as? StockRelationalBody.Position)?.value?.safety == StockSafety.EXPIRED)
    return before.copy(safety = StockSafety.EXPIRED)
}

/** Returns false only for a position with no restoration movement; ordinary issue/safety
 * validation then remains unchanged. New quarantine positions require genuine source metadata. */
internal suspend fun verifyStockRestorationPosition(stock: LocalStockRelationalInspection,
    body: StockRelationalBody.Position, oldBody: StockRelationalBody?): Boolean {
    var found = false
    stock.intents { if (it is StockIntentV2Wire.Movement && it.reason == 2 && it.position.restore(stock.scope) == body.value.key) found = true }
    if (!found) return false
    localStockCheck(stock.original.identity.producer.command == LocalStockRelationalProfileV2.FOOD_COMMAND ||
        stock.original.identity.producer.command == LocalStockRelationalProfileV2.RETURN_COMMAND)
    val old = (oldBody as? StockRelationalBody.Position)?.value
    var expected = old?.let { observedRestorationExpiry(stock, it) }
    stock.intents { intent ->
        if (intent is StockIntentV2Wire.Movement && intent.position.restore(stock.scope) == body.value.key) {
            val slice = verifyStockRestorationMovement(stock, intent)
            val source = (stock.before(LocalStockRelationalProfileV2.positionKey(slice.allocation.position))?.body
                as? StockRelationalBody.Position)?.value ?: inconsistentStock()
            expected = stockRestorationPosition(expected, observedRestorationExpiry(stock, source), body.value.key,
                intent.quantity, stock.recordedAt.value.toEpochMilliseconds())
        }
        if (intent is StockIntentV2Wire.Safety && intent.position.restore(stock.scope) == body.value.key)
            localStockCheck(old != null && intent.reason == 2)
    }
    localStockCheck(expected == body.value)
    return true
}

internal suspend fun verifyStockRestorationEvent(stock: LocalStockRelationalInspection,
    record: StockRelationalRecordV2, event: StockRelationalBody.ValueEvent) {
    val retail = stockRetailReturnForSource(stock, event.sourceId)
    if (retail != null) { verifyStockRetailReturnEvent(stock, record, event, retail); return }
    localStockCheck(record.generation == Revision(1) && record.previous == null && event.event == StockValueEventKind.RESTORE)
    val intent = stockRestorationForSource(stock, event.sourceId) ?: inconsistentStock()
    localStockCheck(event.issueId == intent.issueId && event.sourceId != intent.issueId && event.quantityDelta.scaled > 0 &&
        event.originalValueDelta.minor >= 0 && event.originalValueDelta == event.carryingValueDelta)
    val before = (stock.before(LocalStockRelationalProfileV2.poolKey(event.pool))?.body as? StockRelationalBody.Pool)?.value ?: inconsistentStock()
    localStockCheck(event.before == before)
    requireReceiptPoolBefore(stockHistoryAccess(stock), event.pool, before, before.onHand)
    val issued = stock.before(stockValueEventKey(event.pool, intent.issueId))?.body as? StockRelationalBody.ValueEvent ?: inconsistentStock()
    localStockCheck(issued.event == StockValueEventKind.ISSUE && issued.issueId == intent.issueId && issued.sourceId == intent.issueId &&
        event.quantityDelta == -issued.quantityDelta && event.originalValueDelta == -issued.originalValueDelta &&
        event.sliceCount == issued.sliceCount && event.ordinal > issued.ordinal)
    val expected = InventoryValuationProjection.receive(before, event.quantityDelta, event.originalValueDelta)
    localStockCheck(event.after == expected.after && event.carryingValueDelta == expected.value &&
        stock.write(LocalStockRelationalProfileV2.poolKey(event.pool)) != null)
    var quantity = stockZeroQuantity(before.onHand); var value = Money(before.carryingValue.currency, 0); var count = 0L
    stock.records { row ->
        val slice = row.body as? StockRelationalBody.ValueSlice
        if (slice?.eventId == record.key.id) {
            localStockCheck(slice.pool == event.pool && slice.issueId == intent.issueId && slice.sourceId == event.sourceId)
            quantity += slice.quantity; value += slice.originalValue; count = stockCheckedAdd(count, 1)
        }
    }
    localStockCheck(count > 0L && count == event.sliceCount && quantity == event.quantityDelta && value == event.originalValueDelta)
    stock.range(StockQueryV2.Positions(event.pool.skuId, event.pool.warehouseId)) { row ->
        val position = (row.body as? StockRelationalBody.Position)?.value ?: inconsistentStock()
        if (position.key.pool == event.pool) observedRestorationExpiry(stock, position)
    }
}

/** Current restored issue history is a whole source, not a favorable restored pool subset.
 * Its immutable ISSUE rows keep the original sender; all RESTORE rows share the one genuine
 * later producer/source and every current issue slice is that producer's generation2 write. */
internal suspend fun stockRestoredIssueSource(read: StockPoolHistoryAccess, issueId: DomainId): DomainId {
    val headerRow = read.normal(LocalStockRelationalProfileV2.issueKey(issueId)) ?: inconsistentStock()
    val header = (headerRow.body as? StockRelationalBody.Issue)?.value ?: inconsistentStock()
    localStockCheck(header.id == issueId && header.purpose == ValueIssuePurpose.DIRECT_RECIPE &&
        header.disposition == KitchenDisposition.CANCELLED_BEFORE_START && header.startedStationIds.isEmpty() &&
        read.claim(LocalStockStoreProfile.sourceClaim(issueId)) == issueId)
    var restorationId: DomainId? = null; var producer: StreamedProducerIdentityV2? = null
    var count = 0L; var value = Money(header.originalValue.currency, 0); var events = 0L
    read.range(StockQueryV2.IssueValues(issueId)) { row -> when (val body = row.body) {
        is StockRelationalBody.IssueSlice -> {
            localStockCheck(body.value.issueId == issueId && row.generation == Revision(2) &&
                body.value.restoredQuantity == body.value.allocation.quantity && body.value.allocation.position.serialId == null &&
                (producer == null || producer == row.producer))
            producer = row.producer; stockOriginalSliceEvent(read, row)
            count = stockCheckedAdd(count, 1); value += body.value.originalValue
        }
        is StockRelationalBody.ValueEvent -> {
            localStockCheck(row.generation == Revision(1) && row.previous == null && body.issueId == issueId)
            when (body.event) {
                StockValueEventKind.ISSUE -> localStockCheck(body.sourceId == issueId)
                StockValueEventKind.RESTORE -> {
                    localStockCheck(body.sourceId != issueId && (restorationId == null || restorationId == body.sourceId) &&
                        (producer == null || producer == row.producer))
                    restorationId = body.sourceId; producer = row.producer; events = stockCheckedAdd(events, 1)
                }
                else -> throw LocalStockRejected(LocalStockFailure.UNSUPPORTED_VALUE_LINEAGE)
            }
        }
        is StockRelationalBody.ValueSlice -> {
            localStockCheck(row.generation == Revision(1) && row.previous == null && body.issueId == issueId)
            val eventRow = read.normal(AggregateKey(StockRelationalKindsV2.VALUE_EVENT, body.eventId)) ?: inconsistentStock()
            val event = eventRow.body as? StockRelationalBody.ValueEvent ?: inconsistentStock()
            val current = (read.normal(LocalStockRelationalProfileV2.issueSliceKey(issueId, body.allocationId))?.body
                as? StockRelationalBody.IssueSlice)?.value ?: inconsistentStock()
            localStockCheck(event.issueId == issueId && event.pool == body.pool && event.sourceId == body.sourceId &&
                event.ordinal == body.eventOrdinal && row.producer == eventRow.producer &&
                event.event in setOf(StockValueEventKind.ISSUE, StockValueEventKind.RESTORE) && current.issueId == issueId &&
                current.allocation.position.pool == body.pool && current.allocation.quantity == body.quantity &&
                current.originalValue == body.originalValue && current.restoredQuantity == body.quantity)
        }
        else -> throw LocalStockRejected(LocalStockFailure.UNSUPPORTED_VALUE_LINEAGE)
    } }
    val source = restorationId ?: inconsistentStock()
    localStockCheck(events > 0 && count == header.allocationCount && value == header.originalValue && headerRow.producer == producer &&
        read.claim(LocalStockStoreProfile.sourceClaim(source)) == source)
    read.range(StockQueryV2.IssueValues(issueId)) { row ->
        if (row.body is StockRelationalBody.IssueSlice) requireStockRestoredSliceHistory(read, row, source)
    }
    return source
}

internal suspend fun requireStockRestoredSliceHistory(read: StockPoolHistoryAccess, row: StockRelationalRecordV2,
    restorationId: DomainId) {
    val slice = (row.body as? StockRelationalBody.IssueSlice)?.value ?: inconsistentStock()
    val originalRow = stockOriginalSliceEvent(read, row)
    val original = originalRow.body as? StockRelationalBody.ValueEvent ?: inconsistentStock()
    val key = stockValueEventKey(slice.allocation.position.pool, restorationId)
    val restoredRow = read.normal(key) ?: inconsistentStock()
    val restored = restoredRow.body as? StockRelationalBody.ValueEvent ?: inconsistentStock()
    val valueRow = read.normal(stockValueSliceKey(key.id, slice.allocation.allocationId)) ?: inconsistentStock()
    val value = valueRow.body as? StockRelationalBody.ValueSlice ?: inconsistentStock()
    localStockCheck(row.generation == Revision(2) && slice.restoredQuantity == slice.allocation.quantity &&
        restoredRow.generation == Revision(1) && restoredRow.previous == null && restoredRow.producer == row.producer &&
        valueRow.generation == Revision(1) && valueRow.previous == null && valueRow.producer == row.producer &&
        restored.event == StockValueEventKind.RESTORE && restored.pool == original.pool && restored.sourceId == restorationId &&
        restored.issueId == slice.issueId && restorationId != slice.issueId && restored.ordinal > original.ordinal &&
        restored.quantityDelta == -original.quantityDelta && restored.originalValueDelta == -original.originalValueDelta &&
        restored.carryingValueDelta == restored.originalValueDelta && restored.sliceCount == original.sliceCount &&
        value == StockRelationalBody.ValueSlice(original.pool, restorationId, key.id, restored.ordinal, slice.issueId,
            slice.allocation.allocationId, slice.allocation.quantity, slice.originalValue))
}

/** Exact before-state source claim owner, only from an actual complete value/member range. */
internal suspend fun stockRestorationClaimSource(stock: LocalStockRelationalInspection, sourceId: DomainId): Boolean {
    var found = false
    stock.original.frames { frame ->
        val range = (frame as? RetainedCommandFrameV2.Source)?.value as? StreamedSourceObservationV2.StockRange ?: return@frames
        if (range.query is StockQueryV2.PoolValues || range.query is StockQueryV2.IssueValues) stock.range(range.query) { row ->
            val event = row.body as? StockRelationalBody.ValueEvent
            if (event?.event == StockValueEventKind.RESTORE && event.sourceId == sourceId) {
                localStockCheck(row.generation == Revision(1) && row.previous == null && event.issueId != null &&
                    event.issueId != sourceId && stock.observedClaim(LocalStockStoreProfile.sourceClaim(sourceId)) == sourceId)
                found = true
            }
        }
    }
    return found
}
