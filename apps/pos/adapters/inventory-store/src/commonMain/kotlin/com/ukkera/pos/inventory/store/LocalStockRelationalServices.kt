package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.Serializable

/** Explicit additional handlers; the composition owner still installs ONE repository/registry. */
public class LocalStockRelationalServices(repository: LocalStockRepository, authority: LocalStockAuthorityReader) {
    public val reads: List<MasterReadHandler> = localStockFrozen(listOf(
        StockProfileRead(repository), StockStationProfileRead(repository), StockHistoryRead(repository)))
    public val commands: List<MasterCommandHandler> = localStockFrozen(listOf(StockAdminHandler(repository, authority)))
}

/**
 * Real whole-operation stock planner on the owner's exact TEMP spool. Call from the installed
 * commerce PreparedStreamingMutation only AFTER authorization. This is neither a wire type,
 * a generic transaction provider, a success reply nor a separate financial commit. Every API
 * re-enters the same owner loan; no list of all branch SKUs, holders, lots or history is retained.
 */
public class LocalStockRelationalTransaction internal constructor(
    private val repository: LocalStockRepository,
    private val authority: LocalStockAuthorityReader,
    internal val read: LocalStockRelationalRead,
    internal val context: CommandContext,
    internal val sink: StreamedCommandSink,
    public val recordedAt: UtcInstant,
) {
    private val work = StockPostingBuilder(read, context, sink)
    private var demandOrdinal = 0
    private var issueCount = 0
    private var releaseCount = 0L
    private var restoring = false
    private var kitchenChanges = 0L
    private var foodExposure: CollectionExposure? = null
    private val millis: Long get() = recordedAt.value.toEpochMilliseconds()

    /** Actual capture-bound reader of THIS still-open transaction. Unchanged reservations,
     * issues and full memberships are retained on the SAME sink as commerce effects; a sinkless
     * read, caller DTO or cloned view cannot stand in for a literal BEFORE posting proof. */
    public suspend fun observations(): LocalStockRelationalRead {
        work.requireOpen()
        return read
    }

    /** All32 unsent portions, each with all16 ingredients; lot splits have no combined allocation cap. */
    public suspend fun reserveFoodBeforeCollection(portions: List<LocalStockReservationDemand>): LocalStockStreamResult {
        localStockCheck(context.key == LocalStockRelationalProfileV2.FOOD_COMMAND && portions.size in 1..32 &&
            portions.map { it.reservationId }.distinct().size == portions.size && portions.all {
                it.lines.size in 1..16 && it.binding.exposure == CollectionExposure.UNKNOWN && it.expiresAt == null
            }, LocalStockFailure.BOUND_EXCEEDED)
        return reserveAll(portions)
    }

    /** Existing uncollected kitchen-send selection8 is separate from financial precollection32. */
    public suspend fun reserveUncollectedFood(portions: List<LocalStockReservationDemand>): LocalStockStreamResult {
        localStockCheck(context.key == LocalStockRelationalProfileV2.FOOD_COMMAND && portions.size in 1..8 &&
            portions.map { it.reservationId }.distinct().size == portions.size && portions.all {
                it.lines.size in 1..16 && it.binding.exposure == CollectionExposure.UNCOLLECTED && it.expiresAt == null
            }, LocalStockFailure.BOUND_EXCEEDED)
        return reserveAll(portions)
    }

    /** The original256-line quote scope is retained, not a sixteen-SKU checkout masquerading as v2. */
    public suspend fun reserveRetail(demand: LocalStockReservationDemand): LocalStockStreamResult {
        localStockCheck(context.key == LocalStockRelationalProfileV2.COLLECTION_COMMAND && demand.lines.size in 1..256)
        return reserveAll(listOf(demand))
    }

    private suspend fun reserveAll(demands: List<LocalStockReservationDemand>): LocalStockStreamResult {
        work.requireOpen()
        localStockCheck(!restoring && releaseCount == 0L && issueCount == 0, LocalStockFailure.UNSUPPORTED_VALUE_LINEAGE)
        if (context.key == LocalStockRelationalProfileV2.COLLECTION_COMMAND) localStockCheck(demandOrdinal == 0 && demands.size == 1)
        if (context.key == LocalStockRelationalProfileV2.FOOD_COMMAND) {
            val exposure = demands.first().binding.exposure
            localStockCheck(demands.all { it.binding.exposure == exposure } && (foodExposure == null || foodExposure == exposure))
            localStockCheck(demandOrdinal + demands.size <= (if (exposure == CollectionExposure.UNCOLLECTED) 8 else 32), LocalStockFailure.BOUND_EXCEEDED)
            foodExposure = exposure
        }
        var count = 0L; var total = 0L; var deadline: UtcInstant? = null
        for (demand in demands) {
            localStockCheck(demandOrdinal < 32, LocalStockFailure.BOUND_EXCEEDED)
            val key = LocalStockRelationalProfileV2.reservationKey(demand.reservationId)
            localStockCheck(work.body(key) == null, LocalStockFailure.SOURCE_ALREADY_USED)
            work.acquire(LocalStockStoreProfile.reservationClaim(demand.reservationId), demand.reservationId)
            work.intent(StockIntentV2Wire.Reserve(demand.reservationId, BindingV2Wire.from(demand.binding), demand.allocationSetId,
                demand.allocationSetRevision, demand.expiresAt, demand.lines.size, demandOrdinal++))
            var allocations = 0L
            for ((lineOrdinal, line) in demand.lines.withIndex()) {
                val approved = requireStockAllocationAuthority(read, authority, line)
                work.intent(StockIntentV2Wire.Line(demand.reservationId, lineOrdinal, line.lineId, line.skuId,
                    line.warehouseId, line.baseQuantity, approved))
                var remaining = line.baseQuantity
                read.query(StockQueryV2.Positions(line.skuId, line.warehouseId)) { index ->
                    // All candidates are observed to EOF, even after this line is fulfilled.
                    val body = read.record(index.identity.key)?.body as? StockRelationalBody.Position ?: inconsistentStock()
                    val position = (work.body(index.identity.key) as? StockRelationalBody.Position)?.value ?: inconsistentStock()
                    localStockCheck(position.key == body.value.key)
                    requireStockSelectedPosition(read, repository, approved, position)
                    if (remaining.scaled > 0 && position.isSellableAt(millis)) {
                        val available = available(position)
                        val quantity = InventoryRelationalPlanner.select(available, stockZeroQuantity(remaining), remaining, millis)
                        if (quantity.scaled > 0) {
                            val id = LocalStockRelationalProfileV2.allocationId(demand.allocationSetId,
                                demand.allocationSetRevision, line.lineId, position.key)
                            val allocation = InventoryAllocationRecord(demand.reservationId, StockAllocation(id, line.lineId, position.key, quantity))
                            work.acquire(LocalStockStoreProfile.allocationClaim(id), demand.reservationId)
                            work.insert(StockRelationalBody.Allocation(allocation))
                            sink.scratch.insert(StockWorkingSpace.ALLOCATION, StockWorkingRecord(demand.reservationId, id, 0,
                                stockEncode(AllocationV2Wire.from(allocation.allocation), AllocationV2Wire.serializer())))
                            selection(position.key, quantity)
                            allocations = stockCheckedAdd(allocations, 1)
                            remaining -= quantity
                            work.beforeMillis(position.expiresAtEpochMillis)
                            position.expiresAtEpochMillis?.let { expiry ->
                                val at = UtcInstant(kotlin.time.Instant.fromEpochMilliseconds(expiry))
                                deadline = deadline?.let { minOf(it, at) } ?: at
                            }
                        }
                    }
                }
                localStockCheck(remaining.scaled == 0L, LocalStockFailure.INSUFFICIENT_STOCK)
            }
            val header = InventoryRelationalPlanner.reserve(demand.reservationId, demand.binding, allocations, millis,
                demand.expiresAt?.value?.toEpochMilliseconds())
            work.insert(StockRelationalBody.Reservation(header, demand.allocationSetId, demand.allocationSetRevision))
            work.before(demand.expiresAt)
            demand.expiresAt?.let { at -> deadline = deadline?.let { minOf(it, at) } ?: at }
            count = stockCheckedAdd(count, 1); total = stockCheckedAdd(total, allocations)
        }
        return LocalStockStreamResult(count, total, deadline)
    }

    /** Complete allocations are iterated, not materialized into the old256-entry issue object. */
    public suspend fun issueReserved(request: LocalStockReservedIssue): LocalStockIssueResult {
        work.requireOpen()
        localStockCheck(!restoring && releaseCount == 0L && issueCount < (if (context.key == LocalStockRelationalProfileV2.COLLECTION_COMMAND) 1 else 8),
            LocalStockFailure.BOUND_EXCEEDED)
        issueCount++
        localStockCheck(context.key == LocalStockRelationalProfileV2.FOOD_COMMAND && request.purpose == ValueIssuePurpose.DIRECT_RECIPE ||
            context.key == LocalStockRelationalProfileV2.COLLECTION_COMMAND && request.purpose == ValueIssuePurpose.RETAIL,
            LocalStockFailure.UNSUPPORTED_VALUE_LINEAGE)
        localStockCheck((request.purpose == ValueIssuePurpose.DIRECT_RECIPE) == (request.recipe != null))
        val before = reservation(request.reservationId)
        val header = InventoryRelationalPlanner.issue(before.value, request.expectedRevision, request.issueId, millis)
        localStockCheck(work.body(LocalStockRelationalProfileV2.issueKey(request.issueId)) == null, LocalStockFailure.SOURCE_ALREADY_USED)
        work.acquire(LocalStockStoreProfile.sourceClaim(request.issueId), request.issueId)
        work.intent(StockIntentV2Wire.Issue(request.reservationId, request.expectedRevision, request.issueId,
            stockV2PurposeTag(request.purpose), request.recipe?.recipeSnapshotId, request.recipe?.requiredStationIds.orEmpty()))
        var totalCount = 0L
        eachAllocation(before) { allocation ->
            val original = allocation.allocation
            val position = (work.body(LocalStockRelationalProfileV2.positionKey(original.position)) as? StockRelationalBody.Position)?.value
                ?: inconsistentStock()
            localStockCheck(position.isSellableAt(millis) && available(position).held <= position.onHand, LocalStockFailure.UNSAFE_STOCK)
            requireRelationalStockSerialPosition(read, repository, position)
            localStockCheck(request.purpose != ValueIssuePurpose.DIRECT_RECIPE || original.position.serialId == null,
                LocalStockFailure.UNSUPPORTED_VALUE_LINEAGE)
            val poolId = LocalStockRelationalProfileV2.poolKey(original.position.pool).id
            val group = issuePool(request.issueId, poolId)
            val ref = stockEncode(StockPoolRefWire.from(original.position.pool), StockPoolRefWire.serializer())
            val old = sink.scratch.get(StockWorkingSpace.MEMBERSHIP, request.issueId, poolId)
            if (old == null) sink.scratch.insert(StockWorkingSpace.MEMBERSHIP, StockWorkingRecord(request.issueId, poolId, 0, ref))
            else localStockCheck(old.payload == ref)
            sink.scratch.insert(StockWorkingSpace.COST_SLICE, StockWorkingRecord(group, original.allocationId, 0,
                stockEncode(StockCostSliceV2Wire(AllocationV2Wire.from(original), 0), StockCostSliceV2Wire.serializer())))
            work.beforeMillis(position.expiresAtEpochMillis)
            totalCount = stockCheckedAdd(totalCount, 1)
        }
        localStockCheck(totalCount == before.value.allocationCount)
        var totalValue = Money(Currency.EGP, 0)
        sink.scratch.forEach(StockWorkingSpace.MEMBERSHIP, request.issueId) { entry ->
            val poolKey = stockDecode(entry.payload, StockPoolRefWire.serializer()).key(context.scope)
            val pool = pool(poolKey)
            val group = issuePool(request.issueId, entry.recordId)
            var quantity = stockZeroQuantity(pool.onHand); var slices = 0L
            sink.scratch.forEach(StockWorkingSpace.COST_SLICE, group) { row ->
                val allocation = stockDecode(row.payload, StockCostSliceV2Wire.serializer()).allocation.restore(context.scope)
                localStockCheck(allocation.position.pool == poolKey)
                quantity += allocation.quantity; slices = stockCheckedAdd(slices, 1)
            }
            val delta = InventoryValuationProjection.issue(pool, quantity)
            val value = -delta.value
            var floorTotal = 0L
            sink.scratch.forEach(StockWorkingSpace.COST_SLICE, group) { row ->
                val slice = stockDecode(row.payload, StockCostSliceV2Wire.serializer())
                val share = InventoryValuationProjection.slice(value, slice.allocation.quantity, quantity)
                floorTotal = stockCheckedAdd(floorTotal, share.floor.minor)
                // Enumeration here is ID order; sort changes do not change its page key.
                sink.scratch.put(StockWorkingSpace.COST_SLICE, row.copy(sortValue = share.remainder,
                    payload = stockEncode(slice.copy(floor = share.floor.minor), StockCostSliceV2Wire.serializer())))
            }
            var extra = value.minor - floorTotal
            localStockCheck(extra >= 0 && extra < slices)
            val event = StockRelationalBody.ValueEvent(poolKey, request.issueId, delta.after.lastEventOrdinal,
                StockValueEventKind.ISSUE, pool, delta.after, -quantity, -value, delta.value, slices, request.issueId)
            val eventId = stockKey(event).id
            sink.scratch.forEach(StockWorkingSpace.COST_SLICE, group, byRemainder = true) { row ->
                val slice = stockDecode(row.payload, StockCostSliceV2Wire.serializer())
                val allocation = slice.allocation.restore(context.scope)
                val amount = Money(value.currency, slice.floor + if (extra > 0) 1 else 0)
                if (extra > 0) extra--
                work.insert(StockRelationalBody.IssueSlice(InventoryIssueSlice(request.issueId, allocation, amount, stockZeroQuantity(allocation.quantity))))
                work.insert(StockRelationalBody.ValueSlice(poolKey, request.issueId, eventId, event.ordinal,
                    request.issueId, allocation.allocationId, allocation.quantity, amount))
                val position = (work.body(LocalStockRelationalProfileV2.positionKey(allocation.position)) as? StockRelationalBody.Position)?.value
                    ?: inconsistentStock()
                work.put(StockRelationalBody.Position(InventoryRelationalPlanner.movement(position, -allocation.quantity)))
                work.intent(StockIntentV2Wire.Movement(request.issueId, allocation.allocationId, PositionKeyV2Wire.from(allocation.position), -allocation.quantity, 1))
                if (read.reservationRecord(request.reservationId) == null) selection(allocation.position, -allocation.quantity)
            }
            localStockCheck(extra == 0L)
            work.insert(event); work.put(StockRelationalBody.Pool(delta.after)); totalValue += value
        }
        work.put(before.copy(value = header))
        work.insert(StockRelationalBody.Issue(LocalStockIssueHeader(request.issueId, request.reservationId, header.binding,
            request.purpose, Revision(1), totalCount, totalValue, request.recipe?.recipeSnapshotId,
            request.recipe?.requiredStationIds.orEmpty(), emptyList(), request.recipe?.let { KitchenDisposition.ACTIVE })))
        work.beforeMillis(before.value.expiresAtEpochMillis)
        return LocalStockIssueResult(request.issueId, totalCount, totalValue)
    }

    /** The caller must persist its real sales resolution in THIS operation; this API cannot authorize a refund. */
    public suspend fun release(request: LocalStockRelease) {
        work.requireOpen()
        localStockCheck(context.key == LocalStockRelationalProfileV2.FOOD_COMMAND || context.key == LocalStockRelationalProfileV2.COLLECTION_COMMAND)
        releaseInternal(request)
    }
    private suspend fun releaseInternal(request: LocalStockRelease) {
        work.requireOpen()
        localStockCheck(!restoring && demandOrdinal == 0 && issueCount == 0, LocalStockFailure.UNSUPPORTED_VALUE_LINEAGE)
        releaseCount = stockCheckedAdd(releaseCount, 1)
        if (context.key != LocalStockRelationalProfileV2.COMMAND) localStockCheck(releaseCount <= 32L, LocalStockFailure.BOUND_EXCEEDED)
        val before = reservation(request.reservationId)
        val next = InventoryRelationalPlanner.release(before.value, request.expectedRevision, request.resolution)
        work.intent(StockIntentV2Wire.Release(request.reservationId, request.expectedRevision, request.resolution?.resolutionId,
            request.resolution?.orderId, request.resolution?.collectionId, request.resolution?.preservedObligationId))
        work.put(before.copy(value = next))
        // Permanent reservation/allocation/source claims are deliberately NEVER released.
    }

    public suspend fun acknowledgeKitchenStart(issueId: DomainId, expected: Revision, stationId: DomainId) {
        work.requireOpen(); localStockCheck(!restoring && context.key == LocalStockRelationalProfileV2.FOOD_COMMAND)
        kitchenChanges = stockCheckedAdd(kitchenChanges, 1)
        val issue = issueHeader(issueId)
        localStockCheck(issue.revision == expected, LocalStockFailure.STALE_REVISION)
        localStockCheck(issue.purpose == ValueIssuePurpose.DIRECT_RECIPE && issue.disposition == KitchenDisposition.ACTIVE &&
            stationId in issue.requiredStationIds && stationId !in issue.startedStationIds)
        work.intent(StockIntentV2Wire.KitchenStart(issueId, expected, stationId))
        work.put(StockRelationalBody.Issue(copyIssueHeader(issue, issue.revision.next(), issue.startedStationIds + stationId, issue.disposition)))
    }

    public suspend fun classifyWasteAfterStart(issueId: DomainId, expected: Revision, reasonId: DomainId) {
        work.requireOpen(); localStockCheck(!restoring && context.key == LocalStockRelationalProfileV2.FOOD_COMMAND)
        kitchenChanges = stockCheckedAdd(kitchenChanges, 1)
        val issue = issueHeader(issueId)
        localStockCheck(issue.revision == expected, LocalStockFailure.STALE_REVISION)
        localStockCheck(issue.purpose == ValueIssuePurpose.DIRECT_RECIPE && issue.disposition == KitchenDisposition.ACTIVE && issue.startedStationIds.isNotEmpty())
        work.intent(StockIntentV2Wire.Waste(issueId, expected, reasonId))
        work.put(StockRelationalBody.Issue(copyIssueHeader(issue, issue.revision.next(), issue.startedStationIds, KitchenDisposition.WASTE_AFTER_START)))
        // Cost classification only: never restore prepared ingredients or create a second consumption.
    }

    /** Called only for the real final all-stations unstarted transition in this SAME food
     * posting. Partial confirmations, request-to-cancel, served work and refunds cannot restore. */
    public suspend fun restoreRecipeBeforeStart(request: LocalStockKitchenRestoration) {
        work.requireOpen()
        localStockCheck(context.key == LocalStockRelationalProfileV2.FOOD_COMMAND && !restoring &&
            demandOrdinal == 0 && issueCount == 0 && releaseCount == 0L && kitchenChanges == 0L,
            LocalStockFailure.UNSUPPORTED_VALUE_LINEAGE)
        restoring = true
        restoreStockRecipeBeforeStart(work, request, recordedAt) { position ->
            changeSafety(position, StockSafety.EXPIRED, ReservationHoldReason.EXPIRY)
        }
    }

    /** A separate actual accepted RETAIL posting. The installed sales producer must prove its
     * current inspection/authority and acceptance-specific cumulative consumption on this sink. */
    public suspend fun restoreRetailReturn(request: LocalStockRetailReturnRestoration) {
        work.requireOpen()
        localStockCheck(context.key == LocalStockRelationalProfileV2.RETURN_COMMAND && !restoring &&
            demandOrdinal == 0 && issueCount == 0 && releaseCount == 0L && kitchenChanges == 0L,
            LocalStockFailure.UNSUPPORTED_VALUE_LINEAGE)
        restoring = true
        restoreStockRetailReturn(work, request, recordedAt) { position ->
            changeSafety(position, StockSafety.EXPIRED, ReservationHoldReason.EXPIRY)
        }
    }

    internal suspend fun recall(request: LocalStockRelationalCommand.Recall) {
        work.requireOpen(); localStockCheck(context.key == LocalStockRelationalProfileV2.COMMAND)
        val item = read.record(LocalStockRelationalProfileV2.itemKey(request.skuId)) ?: missingStock()
        localStockCheck(item.generation == request.expectedItemRevision, LocalStockFailure.STALE_REVISION)
        var count = 0L
        read.query(StockQueryV2.LotPositions(request.skuId, request.lotId)) { index ->
            val position = (read.record(index.identity.key)?.body as? StockRelationalBody.Position)?.value ?: inconsistentStock()
            changeSafety(position, StockSafety.RECALLED, ReservationHoldReason.RECALL); count = stockCheckedAdd(count, 1)
        }
        localStockCheck(count > 0, LocalStockFailure.STOCK_MISSING)
    }

    internal suspend fun expire(sweepId: DomainId) {
        work.requireOpen(); localStockCheck(context.key == LocalStockRelationalProfileV2.COMMAND)
        work.intent(StockIntentV2Wire.ExpireSweep(sweepId))
        read.query(StockQueryV2.AllPositions) { index ->
            val position = (read.record(index.identity.key)?.body as? StockRelationalBody.Position)?.value ?: inconsistentStock()
            if (position.safety == StockSafety.CLEAR && position.expiresAtEpochMillis?.let { it <= millis } == true)
                changeSafety(position, StockSafety.EXPIRED, ReservationHoldReason.EXPIRY)
        }
        read.query(StockQueryV2.AllReservations) { index ->
            read.record(index.identity.key) ?: inconsistentStock()
            val before = reservation(index.identity.key.id)
            if (before.value.retainsStockClaim && before.value.expiresAtEpochMillis?.let { it <= millis } == true) {
                if (before.value.binding.exposure == CollectionExposure.UNCOLLECTED)
                    releaseInternal(LocalStockRelease(before.value.id, before.value.revision, null))
                else hold(before.value.id, ReservationHold(ReservationHoldReason.RESERVATION_EXPIRED))
            }
        }
    }

    private suspend fun changeSafety(position: StockPosition, safety: StockSafety, reason: ReservationHoldReason) {
        val current = (work.body(LocalStockRelationalProfileV2.positionKey(position.key)) as? StockRelationalBody.Position)?.value ?: inconsistentStock()
        work.intent(StockIntentV2Wire.Safety(PositionKeyV2Wire.from(position.key), stockV2HoldTag(reason)))
        work.put(StockRelationalBody.Position(current.copy(safety = safety)))
        // All live holders, not only the selected commerce cut. Duplicate allocations per owner
        // produce one deterministic hold, never duplicate claims or multiple cancellation owners.
        read.positionMembers(position.key) { old, _ ->
            val currentHeader = reservation(old.id).value
            if (currentHeader.retainsStockClaim) hold(old.id, ReservationHold(reason, position.key))
        }
    }

    private suspend fun hold(reservationId: DomainId, reason: ReservationHold) {
        val before = reservation(reservationId)
        if (!before.value.retainsStockClaim) return
        val hold = StockRelationalBody.Hold(InventoryHoldRecord(reservationId, reason))
        val current = work.body(stockKey(hold))
        if (current != null) { localStockCheck(current == hold); return }
        work.insert(hold)
        work.put(before.copy(value = InventoryRelationalPlanner.hold(before.value, 1)))
    }

    private suspend fun reservation(id: DomainId): StockRelationalBody.Reservation {
        val value = work.body(LocalStockRelationalProfileV2.reservationKey(id)) as? StockRelationalBody.Reservation ?: missingStock()
        if (read.reservationRecord(id) != null) read.allocations(id) { /* Complete source membership, no unrelated position hydration. */ }
        return value
    }
    private suspend fun eachAllocation(header: StockRelationalBody.Reservation, consume: suspend (InventoryAllocationRecord) -> Unit) {
        if (read.reservationRecord(header.value.id) != null) { read.allocations(header.value.id, consume); return }
        var count = 0L
        sink.scratch.forEach(StockWorkingSpace.ALLOCATION, header.value.id) { row ->
            val allocation = stockDecode(row.payload, AllocationV2Wire.serializer()).restore(context.scope)
            localStockCheck(allocation.allocationId == row.recordId)
            consume(InventoryAllocationRecord(header.value.id, allocation)); count = stockCheckedAdd(count, 1)
        }
        localStockCheck(count == header.value.allocationCount, LocalStockFailure.STORAGE_INCONSISTENT)
    }
    private suspend fun available(position: StockPosition): InventoryPositionAvailability {
        var held = stockZeroQuantity(position.onHand)
        var allHeld = true
        read.positionMembers(position.key) { old, allocation ->
            val current = (work.body(LocalStockRelationalProfileV2.reservationKey(old.id)) as? StockRelationalBody.Reservation)?.value ?: inconsistentStock()
            if (current.retainsStockClaim) { held += allocation.allocation.quantity; allHeld = allHeld && current.status == ReservationStatus.HELD }
        }
        val selected = selected(position.key, position.onHand)
        held += selected
        localStockCheck(held <= position.onHand || allHeld && selected.scaled == 0L, LocalStockFailure.STORAGE_INCONSISTENT)
        return InventoryPositionAvailability(position, held)
    }
    private suspend fun selected(key: StockPositionKey, unit: Quantity): Quantity =
        sink.scratch.get(StockWorkingSpace.SELECTION, context.scope.branchId, LocalStockRelationalProfileV2.positionKey(key).id)?.let {
            stockDecode(it.payload, StockQuantityV2Wire.serializer()).quantity.also { value ->
                localStockCheck(value.unitId == unit.unitId && value.scale == unit.scale && value.scaled >= 0)
            }
        } ?: stockZeroQuantity(unit)
    private suspend fun selection(key: StockPositionKey, delta: Quantity) {
        val after = selected(key, delta) + delta
        localStockCheck(after.scaled >= 0, LocalStockFailure.STORAGE_INCONSISTENT)
        sink.scratch.put(StockWorkingSpace.SELECTION, StockWorkingRecord(context.scope.branchId,
            LocalStockRelationalProfileV2.positionKey(key).id, 0, stockEncode(StockQuantityV2Wire(after), StockQuantityV2Wire.serializer())))
    }
    private suspend fun pool(key: StockPoolKey): InventoryPoolProjection {
        val value = (work.body(LocalStockRelationalProfileV2.poolKey(key)) as? StockRelationalBody.Pool)?.value ?: inconsistentStock()
        val original = (read.record(LocalStockRelationalProfileV2.poolKey(key))?.body as? StockRelationalBody.Pool)?.value
            ?: inconsistentStock()
        requireStockPoolHistory(read, original)
        captureStockPoolIssueMembers(read, key)
        var quantity = stockZeroQuantity(value.onHand)
        read.query(StockQueryV2.Positions(key.skuId, key.warehouseId)) { index ->
            read.record(index.identity.key) ?: inconsistentStock()
            val position = (work.body(index.identity.key) as? StockRelationalBody.Position)?.value ?: inconsistentStock()
            if (position.key.pool == key) quantity += position.onHand
        }
        localStockCheck(quantity == value.onHand, LocalStockFailure.STORAGE_INCONSISTENT)
        return value
    }
    private suspend fun issueHeader(id: DomainId): LocalStockIssueHeader {
        read.issue(id) ?: missingStock()
        return (work.body(LocalStockRelationalProfileV2.issueKey(id)) as? StockRelationalBody.Issue)?.value ?: inconsistentStock()
    }

    /** Actual same-spool draft header for the caller's real domain transition; not a proof token. */
    public suspend fun plannedReservation(id: DomainId): InventoryReservationHeader = reservation(id).value

    public suspend fun plannedIssue(id: DomainId): LocalStockIssueHeader {
        work.requireOpen()
        return (work.body(LocalStockRelationalProfileV2.issueKey(id)) as? StockRelationalBody.Issue)?.value ?: missingStock()
    }

    /** No business write occurs here. The real kernel still owns seal/CAS/B4/P4/commit/final observation. */
    public suspend fun finish(result: StoredBytes, mustCommitBefore: UtcInstant? = null): StreamedMutationDecision.Apply =
        work.finish(result, mustCommitBefore)
}

/** The same inert binding is used by the live writer and native retained-history reader.
 * It does not grant authority, execute an operation or reconstruct a historical principal. */
internal fun stockAdministrationBinding(request: LocalStockRelationalCommand, operationId: OperationId,
    digest: PayloadDigest): ActionBinding {
    val requestedOperation = when (request) {
        is LocalStockRelationalCommand.InitializeFresh -> request.operationId
        is LocalStockRelationalCommand.Receive -> request.operationId
        is LocalStockRelationalCommand.ReceivePo -> request.operationId
        is LocalStockRelationalCommand.Inspect -> request.operationId
        is LocalStockRelationalCommand.CaptureCount -> request.operationId
        is LocalStockRelationalCommand.PostCount -> request.operationId
        is LocalStockRelationalCommand.Transfer -> request.operationId
        is LocalStockRelationalCommand.Recall, is LocalStockRelationalCommand.Expire -> operationId
    }
    localStockCheck(requestedOperation == operationId)
    val id = when (request) {
        is LocalStockRelationalCommand.InitializeFresh -> request.initializationId
        is LocalStockRelationalCommand.Receive -> request.request.receiptId
        is LocalStockRelationalCommand.ReceivePo -> request.request.receiptId
        is LocalStockRelationalCommand.Inspect -> request.request.inspectionId
        is LocalStockRelationalCommand.CaptureCount -> request.request.countId
        is LocalStockRelationalCommand.PostCount -> request.request.countId
        is LocalStockRelationalCommand.Transfer -> request.request.transferId
        is LocalStockRelationalCommand.Recall -> request.lotId
        is LocalStockRelationalCommand.Expire -> request.sweepId
    }
    val revision = when (request) {
        is LocalStockRelationalCommand.Receive -> request.request.expectedBasisRevision
        is LocalStockRelationalCommand.ReceivePo -> request.request.expectedBasisRevision
        is LocalStockRelationalCommand.Inspect -> request.request.expectedReceiptRevision
        is LocalStockRelationalCommand.CaptureCount -> request.request.expectedItemRevision
        is LocalStockRelationalCommand.PostCount -> request.request.expectedCountRevision
        is LocalStockRelationalCommand.Transfer -> request.request.expectedItemRevision
        is LocalStockRelationalCommand.Recall -> request.expectedItemRevision
        is LocalStockRelationalCommand.InitializeFresh, is LocalStockRelationalCommand.Expire -> Revision(0)
    }
    val reason = when (request) {
        is LocalStockRelationalCommand.InitializeFresh -> request.reasonCode
        is LocalStockRelationalCommand.Receive -> request.request.reasonCode
        is LocalStockRelationalCommand.ReceivePo -> request.request.reasonCode
        is LocalStockRelationalCommand.Inspect -> request.request.reasonCode
        is LocalStockRelationalCommand.CaptureCount -> request.request.reasonCode
        is LocalStockRelationalCommand.PostCount -> request.request.reasonCode
        is LocalStockRelationalCommand.Transfer -> request.request.reasonCode
        is LocalStockRelationalCommand.Recall -> request.reasonCode
        is LocalStockRelationalCommand.Expire -> request.reasonCode
    }
    return ActionBinding(id, revision, digest, operationId, reason)
}

private class StockAdminHandler(private val repository: LocalStockRepository, private val authority: LocalStockAuthorityReader) :
    MasterStreamingCommandHandler, LocalMasterRpcRetainedResultAccessProvider {
    override val key: CommandKey = LocalStockRelationalProfileV2.COMMAND
    override val writableKinds: Set<String> get() = StockRelationalKindsV2.all + stockDocumentKinds
    override val claimNamespaces: Set<String> get() = stockReceivingClaimNamespaces + LocalStockStoreProfile.COUNT_SURPLUS_CLAIM
    override fun normalize(input: StoredBytes): StoredBytes = LocalStockRelationalCommandsV2.encode(LocalStockRelationalCommandsV2.decode(input))
    override fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean {
        if (profile != LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS) return false
        val transfer = LocalStockRelationalCommandsV2.decode(normalized) as? LocalStockRelationalCommand.Transfer ?: return false
        // Body classification only. Actual source safety, custody, revision, warehouse and
        // current employee authority are still proved by the installed Master transaction.
        return transfer.request.source.supplierId == null && transfer.request.source.custody == StockCustody.RELEASED
    }
    override fun rpcRetainedResultRequirements(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes,
        context: CommandContext): List<AuthorizationRequirement> {
        localStockCheck(profile == LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS && context.key == key &&
            supportsRpcWorkflow(profile, normalized) &&
            semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        val transfer = LocalStockRelationalCommandsV2.decode(normalized) as LocalStockRelationalCommand.Transfer
        localStockCheck(transfer.operationId == context.operationId)
        // A saved result is not a new adjustment. Protect its original SKU identity with
        // current quantity-read authority without evaluating or rebuilding a stock proposal.
        return listOf(AuthorizationRequirement(Permission(SecurityAction.INVENTORY_QUANTITY_READ, PermissionVerb.PERFORM),
            context.scope, ActionBinding(transfer.request.source.skuId, transfer.request.expectedItemRevision,
                context.semanticDigest, context.operationId, "stock.transfer.result")))
    }
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedStreamingMutation {
        localStockCheck(context.key == key && context.scope == view.scope &&
            semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        val request = LocalStockRelationalCommandsV2.decode(normalized)
        val binding = stockAdministrationBinding(request, context.operationId, context.semanticDigest)
        val id = binding.documentId
        return object : PreparedStreamingMutation() {
            override val requirement = AuthorizationRequirement(Permission(SecurityAction.INVENTORY_ADJUST, PermissionVerb.PERFORM),
                context.scope, binding)
            override val affectedResources: Set<RecoveryResourceKey> = setOf(RecoveryResourceKey(RecoveryResourceKind.STOCK, id),
                RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, context.originalActorId))
            override val recoveryHoldBarrierKinds: Set<RecoveryResourceKind> = when (request) {
                is LocalStockRelationalCommand.Receive, is LocalStockRelationalCommand.ReceivePo -> arrivalBarriers
                is LocalStockRelationalCommand.InitializeFresh -> setOf(RecoveryResourceKind.STOCK, RecoveryResourceKind.PURCHASE_CUSTODY,
                    RecoveryResourceKind.PO_ALLOWANCE, RecoveryResourceKind.SUPPLIER_SETTLEMENT, RecoveryResourceKind.AUTHORIZATION)
                else -> setOf(RecoveryResourceKind.STOCK)
            }
            override suspend fun propose(authorization: AuthorizedAction, recordedAt: UtcInstant, sink: StreamedCommandSink): StreamedMutationDecision {
                localStockCheck(authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE && authorization.isBoundTo(requirement, context.originalActorId))
                if (request is LocalStockRelationalCommand.InitializeFresh)
                    return initializeFreshStock(repository, view, context, sink, recordedAt, request)
                if (request is LocalStockRelationalCommand.Receive || request is LocalStockRelationalCommand.ReceivePo)
                    return receiveStock(repository, authority, view, context, sink, recordedAt, request)
                if (request is LocalStockRelationalCommand.Inspect)
                    return inspectStock(repository, authority, view, context, sink, recordedAt, request)
                if (request is LocalStockRelationalCommand.CaptureCount || request is LocalStockRelationalCommand.PostCount)
                    return countStock(repository, authority, view, context, sink, recordedAt, request)
                if (request is LocalStockRelationalCommand.Transfer)
                    return transferStock(repository, authority, view, context, sink, recordedAt, request)
                val transaction = repository.streamRelational(view, context, sink, authority, recordedAt)
                val action = when (request) {
                    is LocalStockRelationalCommand.Recall -> { transaction.recall(request); 1 }
                    is LocalStockRelationalCommand.Expire -> { transaction.expire(request.sweepId); 2 }
                    else -> inconsistentStock()
                }
                return transaction.finish(stockEncode(StockAdminResultV2Wire(2, id, action), StockAdminResultV2Wire.serializer()))
            }
        }
    }
}

@Serializable internal data class StockQuantityV2Wire(val quantity: Quantity)
@Serializable internal data class StockCostSliceV2Wire(val allocation: AllocationV2Wire,
    @Serializable(with = MinorUnitsSerializer::class) val floor: Long)
@Serializable internal data class StockAdminResultV2Wire(val format: Int, val id: DomainId, val action: Int)
internal fun stockZeroQuantity(quantity: Quantity): Quantity = Quantity(quantity.unitId, 0, quantity.scale)
internal fun issuePool(issueId: DomainId, poolId: DomainId): DomainId = localStockId("v2.issue-pool", "${issueId.value}/${poolId.value}")
internal fun copyIssueHeader(value: LocalStockIssueHeader, revision: Revision, started: List<DomainId>, disposition: KitchenDisposition?): LocalStockIssueHeader =
    LocalStockIssueHeader(value.id, value.reservationId, value.binding, value.purpose, revision, value.allocationCount,
        value.originalValue, value.recipeSnapshotId, value.requiredStationIds, started, disposition)
