package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.types.*
import kotlinx.serialization.Serializable

@Serializable internal data class StockRetailReturnSelectionV2Wire(
    val allocationId: DomainId, val quantity: Quantity, val custody: Int,
) {
    fun restore(): LocalStockRetailReturnSelection = LocalStockRetailReturnSelection(allocationId, quantity,
        stockV2Custody(custody))
    companion object {
        fun from(value: LocalStockRetailReturnSelection): StockRetailReturnSelectionV2Wire =
            StockRetailReturnSelectionV2Wire(value.allocationId, value.quantity, stockV2CustodyTag(value.requestedCustody))
    }
}

internal fun StockIntentV2Wire.RetailReturn.request(): LocalStockRetailReturnRestoration =
    LocalStockRetailReturnRestoration(restorationId, acceptedReturnId, saleId, portionId, issueId, revision,
        quantityToRestore, selections.map { it.restore() })

internal fun stockRetailReturnIntent(request: LocalStockRetailReturnRestoration): StockIntentV2Wire.RetailReturn =
    StockIntentV2Wire.RetailReturn(request.restorationId, request.acceptedReturnId, request.saleId, request.portionId,
        request.issueId, request.expectedIssueRevision, request.quantityToRestore,
        request.selections.map(StockRetailReturnSelectionV2Wire::from))

internal suspend fun stockRetailReturnForIssue(stock: LocalStockRelationalInspection,
    id: DomainId): StockIntentV2Wire.RetailReturn? {
    var found: StockIntentV2Wire.RetailReturn? = null
    stock.intents { if (it is StockIntentV2Wire.RetailReturn && it.issueId == id) {
        localStockCheck(found == null); found = it
    } }
    return found
}

internal suspend fun stockRetailReturnForSource(stock: LocalStockRelationalInspection,
    id: DomainId): StockIntentV2Wire.RetailReturn? {
    var found: StockIntentV2Wire.RetailReturn? = null
    stock.intents { if (it is StockIntentV2Wire.RetailReturn && it.restorationId == id) {
        localStockCheck(found == null); found = it
    } }
    return found
}

internal suspend fun stockRetailReturnResources(request: LocalStockRetailReturnRestoration,
    consume: suspend (RecoveryResourceKey) -> Unit) {
    consume(RecoveryResourceKey(RecoveryResourceKind.STOCK, request.restorationId))
    consume(RecoveryResourceKey(RecoveryResourceKind.STOCK, request.issueId))
    consume(RecoveryResourceKey(RecoveryResourceKind.REFUND_ENTITLEMENT, request.acceptedReturnId))
    consume(RecoveryResourceKey(RecoveryResourceKind.REFUND_ENTITLEMENT, request.saleId))
    consume(RecoveryResourceKey(RecoveryResourceKind.ORDER_CUSTODY, request.portionId))
    for (selection in request.selections)
        consume(RecoveryResourceKey(RecoveryResourceKind.STOCK, selection.allocationId))
}

/** Accepted-source authority is deliberately absent here; only the installed sales producer
 * can establish that separate join on this same operation. */
internal fun requireStockRetailReturnRequest(request: LocalStockRetailReturnRestoration,
    issue: LocalStockIssueHeader) {
    localStockCheck(issue.id == request.issueId && issue.revision == request.expectedIssueRevision,
        LocalStockFailure.STALE_REVISION)
    localStockCheck(issue.purpose == ValueIssuePurpose.RETAIL && issue.recipeSnapshotId == null &&
        issue.disposition == null && issue.requiredStationIds.isEmpty() && issue.startedStationIds.isEmpty(),
        LocalStockFailure.UNSUPPORTED_VALUE_LINEAGE)
}

internal fun requireStockRetailReturnSelection(request: LocalStockRetailReturnRestoration,
    selection: LocalStockRetailReturnSelection, slice: InventoryIssueSlice) {
    localStockCheck(slice.issueId == request.issueId && slice.allocation.allocationId == selection.allocationId &&
        slice.allocation.lineId == request.portionId && slice.allocation.position.pool.ownership == StockOwnership.CompanyOwned &&
        slice.allocation.position.custody == StockCustody.RELEASED && slice.allocation.position.serialId == null,
        LocalStockFailure.UNSUPPORTED_VALUE_LINEAGE)
    localStockCheck(selection.quantity.unitId == slice.allocation.quantity.unitId &&
        selection.quantity.scale == slice.allocation.quantity.scale, LocalStockFailure.INVALID_UNIT)
    localStockCheck(slice.restoredQuantity + selection.quantity <= slice.allocation.quantity,
        LocalStockFailure.SOURCE_ALREADY_USED)
}

/** RELEASED is explicit and must be genuinely safe, not a label for unsafe released custody.
 * QUARANTINE preserves the current source identity and never clears an unsafe destination. */
internal fun stockRetailReturnDestination(selection: LocalStockRetailReturnSelection, source: StockPosition,
    destination: StockPosition?, now: Long): StockPositionKey {
    localStockCheck(source.key.pool.ownership == StockOwnership.CompanyOwned &&
        source.key.custody == StockCustody.RELEASED && source.key.serialId == null,
        LocalStockFailure.UNSUPPORTED_VALUE_LINEAGE)
    val key = source.key.copy(custody = selection.requestedCustody)
    localStockCheck(destination == null || destination.key == key)
    if (selection.requestedCustody == StockCustody.RELEASED)
        localStockCheck(source.isSellableAt(now) && destination?.isSellableAt(now) == true,
            LocalStockFailure.UNSAFE_STOCK)
    return key
}

@Serializable private data class StockRetailRestorationSliceV2Wire(
    val allocation: AllocationV2Wire, val originalValue: Money, val restoredBefore: Quantity,
    val quantity: Quantity, val value: Money, val custody: Int,
) {
    fun original(scope: com.ukkera.pos.security.BranchScope, issueId: DomainId): InventoryIssueSlice =
        InventoryIssueSlice(issueId, allocation.restore(scope), originalValue, restoredBefore)
    fun selection(): LocalStockRetailReturnSelection = LocalStockRetailReturnSelection(allocation.id,
        quantity, stockV2Custody(custody))
}

/** One explicit positive posting on the real owning sink; all unselected original lots and
 * cumulative history remain per-ID streams, without a whole-return materialization or cap. */
internal suspend fun restoreStockRetailReturn(work: StockPostingBuilder, request: LocalStockRetailReturnRestoration,
    now: UtcInstant, observeExpiry: suspend (StockPosition) -> Unit) {
    work.requireOpen()
    val read = work.read; val sink = work.sink; val context = work.context
    localStockCheck(context.key == LocalStockRelationalProfileV2.RETURN_COMMAND)
    val issue = read.issue(request.issueId) ?: missingStock()
    requireStockRetailReturnRequest(request, issue)
    val first = read.issueOriginal(issue.id) ?: inconsistentStock()
    localStockCheck(first.header.purpose == ValueIssuePurpose.RETAIL &&
        first.original.identity.producer.command == LocalStockRelationalProfileV2.COLLECTION_COMMAND,
        LocalStockFailure.UNSUPPORTED_VALUE_LINEAGE)
    val owner = read.reservation(issue.reservationId) ?: inconsistentStock()
    localStockCheck(owner.status == ReservationStatus.ISSUED && owner.issueId == issue.id &&
        owner.binding == issue.binding && owner.allocationCount == issue.allocationCount)
    val history = stockHistoryAccess(read)
    requireStockRetailIssueHistory(history, issue.id)
    work.acquire(LocalStockStoreProfile.sourceClaim(request.restorationId), request.restorationId)
    work.intent(stockRetailReturnIntent(request))
    for (selection in request.selections) {
        val row = read.record(LocalStockRelationalProfileV2.issueSliceKey(issue.id, selection.allocationId)) ?: missingStock()
        val original = (row.body as? StockRelationalBody.IssueSlice)?.value ?: inconsistentStock()
        requireStockRetailReturnSelection(request, selection, original)
        stockOriginalSliceEvent(history, row)
        val allocation = (read.record(LocalStockRelationalProfileV2.allocationKey(selection.allocationId))?.body
            as? StockRelationalBody.Allocation)?.value ?: inconsistentStock()
        localStockCheck(allocation == InventoryAllocationRecord(issue.reservationId, original.allocation))
        val poolId = LocalStockRelationalProfileV2.poolKey(original.allocation.position.pool).id
        val ref = stockEncode(StockPoolRefWire.from(original.allocation.position.pool), StockPoolRefWire.serializer())
        val previous = sink.scratch.get(StockWorkingSpace.MEMBERSHIP, request.restorationId, poolId)
        if (previous == null) sink.scratch.insert(StockWorkingSpace.MEMBERSHIP,
            StockWorkingRecord(request.restorationId, poolId, 0, ref))
        else localStockCheck(previous.payload == ref)
        val value = InventoryValuationProjection.originalRestorationValue(original, selection.quantity)
        sink.scratch.insert(StockWorkingSpace.COST_SLICE, StockWorkingRecord(issuePool(request.restorationId, poolId),
            selection.allocationId, 0, stockEncode(StockRetailRestorationSliceV2Wire(AllocationV2Wire.from(original.allocation),
                original.originalValue, original.restoredQuantity, selection.quantity, value,
                stockV2CustodyTag(selection.requestedCustody)), StockRetailRestorationSliceV2Wire.serializer())))
    }
    val millis = now.value.toEpochMilliseconds()
    sink.scratch.forEach(StockWorkingSpace.MEMBERSHIP, request.restorationId) { member ->
        val poolKey = stockDecode(member.payload, StockPoolRefWire.serializer()).key(context.scope)
        localStockCheck(member.recordId == LocalStockRelationalProfileV2.poolKey(poolKey).id)
        val before = read.pool(poolKey) ?: inconsistentStock()
        localStockCheck((work.body(LocalStockRelationalProfileV2.poolKey(poolKey)) as? StockRelationalBody.Pool)?.value == before)
        requireStockPoolHistory(read, before)
        captureStockPoolIssueMembers(read, poolKey)
        // Observe every expired position and its live holders in this original pool. This does
        // not authorize an unrelated recall, release or branch-wide administration operation.
        read.query(StockQueryV2.Positions(poolKey.skuId, poolKey.warehouseId)) { index ->
            val position = (read.record(index.identity.key)?.body as? StockRelationalBody.Position)?.value ?: inconsistentStock()
            if (position.key.pool == poolKey && position.safety == StockSafety.CLEAR &&
                position.expiresAtEpochMillis?.let { it <= millis } == true) observeExpiry(position)
        }
        val group = issuePool(request.restorationId, member.recordId)
        var quantity = stockZeroQuantity(before.onHand); var value = Money(before.carryingValue.currency, 0); var count = 0L
        sink.scratch.forEach(StockWorkingSpace.COST_SLICE, group) { row ->
            val slice = stockDecode(row.payload, StockRetailRestorationSliceV2Wire.serializer())
            localStockCheck(slice.allocation.restore(context.scope).position.pool == poolKey &&
                slice.allocation.id == row.recordId)
            quantity += slice.quantity; value += slice.value; count = stockCheckedAdd(count, 1)
        }
        localStockCheck(count > 0)
        val delta = InventoryValuationProjection.receive(before, quantity, value)
        val event = StockRelationalBody.ValueEvent(poolKey, request.restorationId, delta.after.lastEventOrdinal,
            StockValueEventKind.RESTORE, before, delta.after, quantity, value, value, count, issue.id)
        val eventId = stockKey(event).id
        sink.scratch.forEach(StockWorkingSpace.COST_SLICE, group) { row ->
            val slice = stockDecode(row.payload, StockRetailRestorationSliceV2Wire.serializer())
            val original = slice.original(context.scope, issue.id)
            val key = LocalStockRelationalProfileV2.issueSliceKey(issue.id, row.recordId)
            localStockCheck((read.record(key)?.body as? StockRelationalBody.IssueSlice)?.value == original &&
                InventoryValuationProjection.originalRestorationValue(original, slice.quantity) == slice.value)
            val source = (work.body(LocalStockRelationalProfileV2.positionKey(original.allocation.position))
                as? StockRelationalBody.Position)?.value ?: inconsistentStock()
            val destinationKey = source.key.copy(custody = stockV2Custody(slice.custody))
            val previous = (work.body(LocalStockRelationalProfileV2.positionKey(destinationKey)) as? StockRelationalBody.Position)?.value
            val destination = stockRetailReturnDestination(slice.selection(), source, previous, millis)
            work.put(StockRelationalBody.Position(stockRestorationPosition(previous, source, destination, slice.quantity, millis)))
            if (destination.custody == StockCustody.RELEASED) work.beforeMillis(source.expiresAtEpochMillis)
            work.put(StockRelationalBody.IssueSlice(original.copy(restoredQuantity = original.restoredQuantity + slice.quantity)))
            work.insert(StockRelationalBody.ValueSlice(poolKey, request.restorationId, eventId, event.ordinal,
                issue.id, row.recordId, slice.quantity, slice.value))
            work.intent(StockIntentV2Wire.Movement(request.restorationId, row.recordId,
                PositionKeyV2Wire.from(destination), slice.quantity, 2))
        }
        work.insert(event); work.put(StockRelationalBody.Pool(delta.after))
    }
    work.put(StockRelationalBody.Issue(copyIssueHeader(issue, issue.revision.next(), emptyList(), null)))
    // Original acceptance/refund and the issued reservation are independent immutable sources.
}

internal suspend fun verifyStockRetailReturnIntent(stock: LocalStockRelationalInspection,
    intent: StockIntentV2Wire.RetailReturn) {
    localStockCheck(stock.original.identity.producer.command == LocalStockRelationalProfileV2.RETURN_COMMAND)
    val request = intent.request()
    localStockCheck(stockRetailReturnIntent(request) == intent)
    val before = stock.beforeIssue(request.issueId) ?: inconsistentStock()
    requireStockRetailReturnRequest(request, before)
    val after = stock.issue(request.issueId) ?: inconsistentStock()
    localStockCheck(IssueV2Wire.from(after) == IssueV2Wire.from(copyIssueHeader(before, before.revision.next(), emptyList(), null)))
    val owner = stock.beforeReservation(before.reservationId) ?: inconsistentStock()
    localStockCheck(owner.status == ReservationStatus.ISSUED && owner.issueId == before.id && owner.binding == before.binding &&
        owner.allocationCount == before.allocationCount && stock.reservation(owner.id) == null)
    val first = stock.issueOriginal(before.id) ?: inconsistentStock()
    localStockCheck(first.header.purpose == ValueIssuePurpose.RETAIL &&
        first.original.identity.producer.command == LocalStockRelationalProfileV2.COLLECTION_COMMAND)
    requireStockRetailIssueHistory(stockHistoryAccess(stock), before.id)
    val claim = stock.claim(LocalStockStoreProfile.sourceClaim(request.restorationId)) ?: inconsistentStock()
    localStockCheck(claim.action == RetainedClaimAction.ACQUIRE && claim.ownerId == request.restorationId &&
        stock.observedClaim(claim.key) == null)
    var selected = 0L
    stock.beforeIssueSlices(before.id) { original ->
        val allocationKey = LocalStockRelationalProfileV2.allocationKey(original.allocation.allocationId)
        localStockCheck((stock.before(allocationKey)?.body as? StockRelationalBody.Allocation)?.value ==
            InventoryAllocationRecord(before.reservationId, original.allocation) && stock.write(allocationKey) == null)
        val key = LocalStockRelationalProfileV2.issueSliceKey(before.id, original.allocation.allocationId)
        val selection = request.selections.singleOrNull { it.allocationId == original.allocation.allocationId }
        if (selection == null) localStockCheck(stock.write(key) == null)
        else {
            requireStockRetailReturnSelection(request, selection, original)
            localStockCheck((stock.write(key)?.body as? StockRelationalBody.IssueSlice)?.value ==
                original.copy(restoredQuantity = original.restoredQuantity + selection.quantity))
            requireStockRetailReturnSliceEffects(stock, request, selection, original)
            selected = stockCheckedAdd(selected, 1)
        }
    }
    localStockCheck(selected == request.selections.size.toLong())
}

internal suspend fun verifyStockRetailReturnedSlice(stock: LocalStockRelationalInspection,
    record: StockRelationalRecordV2, slice: InventoryIssueSlice, intent: StockIntentV2Wire.RetailReturn) {
    val before = stock.before(record.key) ?: inconsistentStock()
    val original = (before.body as? StockRelationalBody.IssueSlice)?.value ?: inconsistentStock()
    val request = intent.request()
    val selection = request.selections.singleOrNull { it.allocationId == slice.allocation.allocationId } ?: inconsistentStock()
    requireStockRetailReturnSelection(request, selection, original)
    localStockCheck(record.generation == before.generation.next() &&
        slice == original.copy(restoredQuantity = original.restoredQuantity + selection.quantity) &&
        stock.issue(slice.issueId)?.purpose == ValueIssuePurpose.RETAIL)
    requireStockRetailSliceHistory(stockHistoryAccess(stock), before)
    requireStockRetailReturnSliceEffects(stock, request, selection, original)
}

private suspend fun requireStockRetailReturnSliceEffects(stock: LocalStockRelationalInspection,
    request: LocalStockRetailReturnRestoration, selection: LocalStockRetailReturnSelection, original: InventoryIssueSlice) {
    val eventKey = stockValueEventKey(original.allocation.position.pool, request.restorationId)
    val event = stock.write(eventKey)?.body as? StockRelationalBody.ValueEvent ?: inconsistentStock()
    val value = InventoryValuationProjection.originalRestorationValue(original, selection.quantity)
    val restored = stock.write(stockValueSliceKey(eventKey.id, selection.allocationId))?.body as? StockRelationalBody.ValueSlice
        ?: inconsistentStock()
    localStockCheck(event.event == StockValueEventKind.RESTORE && event.issueId == request.issueId && event.sourceId == request.restorationId &&
        restored == StockRelationalBody.ValueSlice(original.allocation.position.pool, request.restorationId, eventKey.id, event.ordinal,
            request.issueId, selection.allocationId, selection.quantity, value))
    val source = (stock.before(LocalStockRelationalProfileV2.positionKey(original.allocation.position))?.body
        as? StockRelationalBody.Position)?.value ?: inconsistentStock()
    val destinationKey = source.key.copy(custody = selection.requestedCustody)
    val previous = (stock.before(LocalStockRelationalProfileV2.positionKey(destinationKey))?.body as? StockRelationalBody.Position)?.value
    val destination = stockRetailReturnDestination(selection, source, previous, stock.recordedAt.value.toEpochMilliseconds())
    val movement = StockIntentV2Wire.Movement(request.restorationId, selection.allocationId,
        PositionKeyV2Wire.from(destination), selection.quantity, 2)
    localStockCheck(stock.intent(stockIntentId(movement)) == movement &&
        stock.write(LocalStockRelationalProfileV2.positionKey(destination))?.body is StockRelationalBody.Position)
}

internal suspend fun verifyStockRetailReturnMovement(stock: LocalStockRelationalInspection,
    movement: StockIntentV2Wire.Movement, intent: StockIntentV2Wire.RetailReturn): InventoryIssueSlice {
    localStockCheck(movement.reason == 2 && movement.quantity.scaled > 0 && movement.allocationId != null)
    val request = intent.request()
    val selection = request.selections.singleOrNull { it.allocationId == movement.allocationId } ?: inconsistentStock()
    val key = LocalStockRelationalProfileV2.issueSliceKey(request.issueId, selection.allocationId)
    val before = (stock.before(key)?.body as? StockRelationalBody.IssueSlice)?.value ?: inconsistentStock()
    val after = (stock.write(key)?.body as? StockRelationalBody.IssueSlice)?.value ?: inconsistentStock()
    requireStockRetailReturnSelection(request, selection, before)
    val source = (stock.before(LocalStockRelationalProfileV2.positionKey(before.allocation.position))?.body
        as? StockRelationalBody.Position)?.value ?: inconsistentStock()
    val destination = source.key.copy(custody = selection.requestedCustody)
    val previous = (stock.before(LocalStockRelationalProfileV2.positionKey(destination))?.body as? StockRelationalBody.Position)?.value
    localStockCheck(after == before.copy(restoredQuantity = before.restoredQuantity + selection.quantity) &&
        movement.sourceId == request.restorationId && movement.quantity == selection.quantity &&
        movement.position.restore(stock.scope) == stockRetailReturnDestination(selection, source, previous,
            stock.recordedAt.value.toEpochMilliseconds()))
    return before
}

internal suspend fun verifyStockRetailReturnEvent(stock: LocalStockRelationalInspection,
    record: StockRelationalRecordV2, event: StockRelationalBody.ValueEvent, intent: StockIntentV2Wire.RetailReturn) {
    localStockCheck(stock.original.identity.producer.command == LocalStockRelationalProfileV2.RETURN_COMMAND &&
        record.generation == Revision(1) && record.previous == null && event.event == StockValueEventKind.RESTORE &&
        event.issueId == intent.issueId && event.sourceId == intent.restorationId && event.sourceId != event.issueId &&
        event.quantityDelta.scaled > 0 && event.originalValueDelta.minor >= 0 && event.originalValueDelta == event.carryingValueDelta)
    val before = (stock.before(LocalStockRelationalProfileV2.poolKey(event.pool))?.body as? StockRelationalBody.Pool)?.value
        ?: inconsistentStock()
    localStockCheck(event.before == before)
    requireReceiptPoolBefore(stockHistoryAccess(stock), event.pool, before, before.onHand)
    val issued = stock.before(stockValueEventKey(event.pool, intent.issueId))?.body as? StockRelationalBody.ValueEvent ?: inconsistentStock()
    localStockCheck(issued.event == StockValueEventKind.ISSUE && issued.issueId == intent.issueId && issued.sourceId == intent.issueId &&
        event.ordinal > issued.ordinal)
    val expected = InventoryValuationProjection.receive(before, event.quantityDelta, event.originalValueDelta)
    localStockCheck(event.after == expected.after && event.carryingValueDelta == expected.value &&
        stock.write(LocalStockRelationalProfileV2.poolKey(event.pool))?.body is StockRelationalBody.Pool)
    var quantity = stockZeroQuantity(before.onHand); var value = Money(before.carryingValue.currency, 0); var count = 0L
    stock.records { row ->
        val slice = row.body as? StockRelationalBody.ValueSlice
        if (slice?.eventId == record.key.id) {
            verifyStockRetailReturnValueSlice(stock, row, slice, event, intent)
            quantity += slice.quantity; value += slice.originalValue; count = stockCheckedAdd(count, 1)
        }
    }
    localStockCheck(count > 0 && count == event.sliceCount && quantity == event.quantityDelta && value == event.originalValueDelta)
    stock.range(StockQueryV2.Positions(event.pool.skuId, event.pool.warehouseId)) { row ->
        val position = (row.body as? StockRelationalBody.Position)?.value ?: inconsistentStock()
        if (position.key.pool == event.pool && position.safety == StockSafety.CLEAR &&
            position.expiresAtEpochMillis?.let { it <= stock.recordedAt.value.toEpochMilliseconds() } == true) {
            val expiry = StockIntentV2Wire.Safety(PositionKeyV2Wire.from(position.key), 2)
            localStockCheck(stock.intent(stockIntentId(expiry)) == expiry &&
                (stock.write(row.key)?.body as? StockRelationalBody.Position)?.value?.safety == StockSafety.EXPIRED)
        }
    }
}

internal suspend fun verifyStockRetailReturnValueSlice(stock: LocalStockRelationalInspection,
    record: StockRelationalRecordV2, slice: StockRelationalBody.ValueSlice, event: StockRelationalBody.ValueEvent,
    intent: StockIntentV2Wire.RetailReturn) {
    localStockCheck(record.generation == Revision(1) && record.previous == null && event.event == StockValueEventKind.RESTORE &&
        slice.issueId == intent.issueId && slice.sourceId == intent.restorationId && event.sourceId == intent.restorationId &&
        slice.pool == event.pool && slice.eventId == stockKey(event).id && slice.eventOrdinal == event.ordinal && event.issueId == intent.issueId)
    val request = intent.request()
    val selection = request.selections.singleOrNull { it.allocationId == slice.allocationId } ?: inconsistentStock()
    val key = LocalStockRelationalProfileV2.issueSliceKey(request.issueId, selection.allocationId)
    val before = (stock.before(key)?.body as? StockRelationalBody.IssueSlice)?.value ?: inconsistentStock()
    val after = (stock.write(key)?.body as? StockRelationalBody.IssueSlice)?.value ?: inconsistentStock()
    requireStockRetailReturnSelection(request, selection, before)
    localStockCheck(before.allocation.position.pool == slice.pool && slice.quantity == selection.quantity &&
        slice.originalValue == InventoryValuationProjection.originalRestorationValue(before, selection.quantity) &&
        after == before.copy(restoredQuantity = before.restoredQuantity + selection.quantity))
}
