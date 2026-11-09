package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.*

/** Complete literal producer verification, not an original-envelope hash or favorable row check. */
internal suspend fun requireStockRelationalTransitions(original: RetainedStreamedCommand, stock: LocalStockRelationalInspection) {
    val activation = stock.before(LocalStockRelationalProfileV2.activationKey(stock.scope.branchId)) ?: unavailableProducer()
    localStockCheck(activation.body is StockRelationalBody.Activation && stockActivationShape(activation.body) &&
        activation.body.sourceProfile == STOCK_FRESH_PROFILE_V2, LocalStockFailure.PRODUCER_UNAVAILABLE)
    var writes = 0L; var effects = 0L
    stock.records { record ->
        localStockCheck(record.producer == original.identity.producer)
        if (record.key.kind == StockRelationalKindsV2.POSTING) {
            localStockCheck(record.key.id.value == stock.operationId.value && record.generation == Revision(1)); return@records
        }
        val old = stock.before(record.key)
        localStockCheck(if (old == null) record.generation == Revision(1) && record.previous == null
            else record.generation == old.generation.next() && record.previous == StockPreviousWire(old.producer.operationId, old.generation, old.digest.value))
        val expected = StockRowEffectV2Wire(record.key.kind, record.key.id, old?.generation, old?.digest?.value, record.digest.value)
        var found = false
        original.frames { frame ->
            val effect = (frame as? RetainedCommandFrameV2.Effect)?.value
            if (effect?.kind == LocalStockRelationalProfileV2.EFFECT_KIND && effect.id == rowEffectId(record.key)) {
                localStockCheck(!found && stockDecode(effect.payload, StockRowEffectV2Wire.serializer()) == expected); found = true
            }
        }
        localStockCheck(found)
        writes = stockCheckedAdd(writes, 1)
        when (val body = record.body) {
            is StockRelationalBody.Item -> verifyItemRevision(stock, record, body, old?.body)
            is StockRelationalBody.Allocation -> verifyAllocation(stock, record, body)
            is StockRelationalBody.Reservation -> verifyReservation(stock, record, body, old?.body)
            is StockRelationalBody.Hold -> verifyHold(stock, record, body)
            is StockRelationalBody.Position -> verifyPosition(stock, record, body, old?.body)
            is StockRelationalBody.Pool -> verifyPool(stock, body, old?.body)
            is StockRelationalBody.Issue -> verifyIssue(stock, record, body, old?.body)
            is StockRelationalBody.IssueSlice -> verifyIssueSlice(stock, record, body)
            is StockRelationalBody.ValueEvent -> verifyValueEvent(stock, record, body)
            is StockRelationalBody.ValueSlice -> verifyValueSlice(stock, record, body)
            // There is deliberately no generic import/genesis or self-approved value/transfer writer.
            // Until each real wider producer is implemented this is unavailable, not an empty pool.
            is StockRelationalBody.ValueTarget, is StockRelationalBody.TransferEdge,
            is StockRelationalBody.Activation -> unavailableProducer()
            is StockRelationalBody.Posting -> inconsistentStock()
        }
    }
    original.frames { frame -> when (frame) {
        is RetainedCommandFrameV2.Effect -> when (frame.value.kind) {
            LocalStockRelationalProfileV2.EFFECT_KIND -> {
                val wire = stockDecode(frame.value.payload, StockRowEffectV2Wire.serializer())
                val key = AggregateKey(wire.kind, wire.id)
                localStockCheck(key.kind in StockRelationalKindsV2.all && key.kind != StockRelationalKindsV2.POSTING &&
                    frame.value.id == rowEffectId(key) && stock.write(key) != null)
                effects = stockCheckedAdd(effects, 1)
            }
            STOCK_ACTION_EFFECT_V2 -> verifyIntent(stock, stockDecode(frame.value.payload, StockIntentV2Wire.serializer()))
            else -> localStockCheck(!frame.value.kind.startsWith("local.stock."))
        }
        is RetainedCommandFrameV2.Claim -> if (frame.value.key.namespace in LocalStockStoreProfile.inventoryClaimNamespaces) {
            val claim = frame.value
            localStockCheck(claim.action == RetainedClaimAction.ACQUIRE && stock.observedClaim(claim.key) == null)
            val id = DomainId(claim.key.key)
            when (claim.key.namespace) {
                LocalStockStoreProfile.RESERVATION_CLAIM -> localStockCheck(claim.ownerId == id &&
                    stock.write(LocalStockRelationalProfileV2.reservationKey(id))?.generation == Revision(1))
                LocalStockStoreProfile.ALLOCATION_CLAIM -> localStockCheck(
                    (stock.write(LocalStockRelationalProfileV2.allocationKey(id))?.body as? StockRelationalBody.Allocation)?.value?.reservationId == claim.ownerId)
                LocalStockStoreProfile.SOURCE_CLAIM -> {
                    val restoration = stockRestorationForSource(stock, id)
                    val retail = stockRetailReturnForSource(stock, id)
                    localStockCheck(restoration == null || retail == null)
                    localStockCheck(claim.ownerId == id && when {
                        restoration != null -> stock.issue(restoration.issueId)?.disposition == KitchenDisposition.CANCELLED_BEFORE_START
                        retail != null -> stock.issue(retail.issueId)?.purpose == ValueIssuePurpose.RETAIL
                        else -> stock.write(LocalStockRelationalProfileV2.issueKey(id))?.generation == Revision(1) && stock.issue(id) != null
                    })
                }
                else -> unavailableProducer()
            }
        }
        is RetainedCommandFrameV2.Source -> when (val source = frame.value) {
            is StreamedSourceObservationV2.Aggregate -> {
                localStockCheck(source.producer.operationId != original.identity.producer.operationId)
                if (source.key.kind in StockRelationalKindsV2.all) {
                    val decoded = stock.before(source.key) ?: inconsistentStock()
                    localStockCheck(decoded.producer == source.producer)
                }
            }
            is StreamedSourceObservationV2.Current -> localStockCheck(source.key.kind !in StockRelationalKindsV2.all)
            is StreamedSourceObservationV2.CurrentAbsent -> localStockCheck(source.key.kind !in StockRelationalKindsV2.all)
            is StreamedSourceObservationV2.StockItemAbsent -> inconsistentStock()
            is StreamedSourceObservationV2.EstateMember, is StreamedSourceObservationV2.EstateEnd -> inconsistentStock()
            is StreamedSourceObservationV2.StockMember -> {
                // Ensure no orphan favorable member can skip a complete terminal range.
                var found = false
                original.frames { candidate ->
                    val terminal = (candidate as? RetainedCommandFrameV2.Source)?.value as? StreamedSourceObservationV2.StockRange
                    if (terminal?.query == source.query) { localStockCheck(!found && source.ordinal < terminal.count); found = true }
                }
                localStockCheck(found)
            }
            else -> Unit
        }
        else -> Unit
    } }
    localStockCheck(writes == effects)
    if (writes == 0L) {
        if (original.identity.producer.command == LocalStockRelationalProfileV2.COMMAND)
            localStockCheck(LocalStockRelationalCommandsV2.decode(original.normalized) is LocalStockRelationalCommand.Expire)
        else {
            // Stock-free pickup/finalization still goes through the installed WHOLE commerce
            // verifier; a nonstock effect count is never an acceptance substitute for that call.
            localStockCheck(original.identity.producer.command == LocalStockRelationalProfileV2.FOOD_COMMAND ||
                original.identity.producer.command == LocalStockRelationalProfileV2.COLLECTION_COMMAND)
            var companion = false
            original.frames { frame -> when (frame) {
                is RetainedCommandFrameV2.Write -> if (frame.value.key.kind !in StockRelationalKindsV2.all) companion = true
                is RetainedCommandFrameV2.Claim -> {
                    localStockCheck(frame.value.key.namespace !in LocalStockStoreProfile.inventoryClaimNamespaces); companion = true
                }
                else -> Unit
            } }
            localStockCheck(companion)
        }
    }
    // Forward closure: every affected SKU has its actual one extra scalar generation write.
    stockAffectedItemIds(stock) { id ->
        localStockCheck(stock.write(LocalStockRelationalProfileV2.itemKey(id))?.body is StockRelationalBody.Item)
    }
    verifyReservationOrder(stock)
    stock.stockAuthoritySources { /* Complete exact stock authority membership; commerce owns complement. */ }
    stock.stockClaimSources { /* Includes rejection of unused stock-owned before-state observations. */ }
    requireStockResourcesPresent(stock)
}

/** Exact affected-SKU union from literal changed records and actual complete BEFORE owners.
 * Repeated streaming keeps memory bounded even for a branch-wide expiry/recall and arbitrarily
 * split recipe reservations. These facts are writes/B4 invalidations, not a frozen root revision. */
private suspend fun stockAffectedItemIds(stock: LocalStockRelationalInspection, consume: suspend (DomainId) -> Unit) {
    stock.records { record -> when (val body = record.body) {
        is StockRelationalBody.Position -> consume(body.value.key.pool.skuId)
        is StockRelationalBody.Pool -> consume(body.value.key.skuId)
        is StockRelationalBody.Allocation -> consume(body.value.allocation.position.pool.skuId)
        is StockRelationalBody.IssueSlice -> consume(body.value.allocation.position.pool.skuId)
        is StockRelationalBody.ValueEvent -> consume(body.pool.skuId)
        is StockRelationalBody.ValueSlice -> consume(body.pool.skuId)
        is StockRelationalBody.ValueTarget -> consume(body.pool.skuId)
        is StockRelationalBody.TransferEdge -> consume(body.pool.skuId)
        is StockRelationalBody.Reservation -> if (stock.before(record.key) != null)
            stock.beforeAllocations(body.value.id) { consume(it.allocation.position.pool.skuId) }
        is StockRelationalBody.Hold -> stock.beforeAllocations(body.value.reservationId) { consume(it.allocation.position.pool.skuId) }
        is StockRelationalBody.Issue -> if (stock.before(record.key) != null)
            stock.beforeIssueSlices(body.value.id) { consume(it.allocation.position.pool.skuId) }
        is StockRelationalBody.Item, is StockRelationalBody.Posting, is StockRelationalBody.Activation -> Unit
    } }
}

private suspend fun verifyItemRevision(stock: LocalStockRelationalInspection, record: StockRelationalRecordV2,
    body: StockRelationalBody.Item, old: StockRelationalBody?) {
    // Only the explicit receiving producer can create a stock identity. Ordinary commerce and
    // safety changes preserve the immutable signed base-unit/tracking contract, incrementing its
    // actual storage generation exactly once (checked with every other canonical write above).
    localStockCheck(old is StockRelationalBody.Item && body == old && record.key.id == body.skuId &&
        stock.observedClaim(LocalStockStoreProfile.itemClaim(body.skuId)) == body.skuId)
    var affected = false
    stockAffectedItemIds(stock) { if (it == body.skuId) affected = true }
    localStockCheck(affected) // No gratuitous extra SKU revision, including a stock-free payment.
}

private suspend fun requireAcquire(stock: LocalStockRelationalInspection, key: ClaimKey, owner: DomainId) {
    val value = stock.claim(key) ?: inconsistentStock()
    localStockCheck(value.action == RetainedClaimAction.ACQUIRE && value.ownerId == owner && stock.observedClaim(key) == null)
}
private suspend fun reserveIntent(stock: LocalStockRelationalInspection, id: DomainId): StockIntentV2Wire.Reserve? {
    var result: StockIntentV2Wire.Reserve? = null
    stock.intents { if (it is StockIntentV2Wire.Reserve && it.id == id) { localStockCheck(result == null); result = it } }
    return result
}
private suspend fun issueIntent(stock: LocalStockRelationalInspection, id: DomainId): StockIntentV2Wire.Issue? {
    var result: StockIntentV2Wire.Issue? = null
    stock.intents { if (it is StockIntentV2Wire.Issue && it.issueId == id) { localStockCheck(result == null); result = it } }
    return result
}
private suspend fun lineIntent(stock: LocalStockRelationalInspection, reservation: DomainId, line: DomainId): StockIntentV2Wire.Line? {
    var result: StockIntentV2Wire.Line? = null
    stock.intents { if (it is StockIntentV2Wire.Line && it.reservationId == reservation && it.lineId == line) { localStockCheck(result == null); result = it } }
    return result
}

private suspend fun verifyAllocation(stock: LocalStockRelationalInspection, record: StockRelationalRecordV2, body: StockRelationalBody.Allocation) {
    val allocation = body.value.allocation
    localStockCheck(record.generation == Revision(1) && record.previous == null && allocation.position.pool.ownership == StockOwnership.CompanyOwned)
    val reserve = reserveIntent(stock, body.value.reservationId) ?: inconsistentStock()
    localStockCheck(allocation.allocationId == LocalStockRelationalProfileV2.allocationId(reserve.setId, reserve.setRevision,
        allocation.lineId, allocation.position))
    val line = lineIntent(stock, body.value.reservationId, allocation.lineId) ?: inconsistentStock()
    localStockCheck(allocation.position.pool.skuId == line.skuId && allocation.position.pool.warehouseId == line.warehouseId &&
        allocation.quantity.unitId == line.quantity.unitId && allocation.quantity.scale == line.quantity.scale && allocation.quantity <= line.quantity)
    requireAcquire(stock, LocalStockStoreProfile.allocationClaim(allocation.allocationId), body.value.reservationId)
}

private suspend fun verifyReservation(stock: LocalStockRelationalInspection, record: StockRelationalRecordV2,
    body: StockRelationalBody.Reservation, oldBody: StockRelationalBody?) {
    val next = body.value
    val old = oldBody as? StockRelationalBody.Reservation
    val reserve = reserveIntent(stock, next.id)
    var newHolds = 0L
    stock.records { if (it.body is StockRelationalBody.Hold && it.body.value.reservationId == next.id) newHolds = stockCheckedAdd(newHolds, 1) }
    if (old == null) {
        val intent = reserve ?: inconsistentStock()
        localStockCheck(body.allocationSetId == intent.setId && body.allocationSetRevision == intent.setRevision &&
            next.binding == intent.binding.restore() && next.expiresAtEpochMillis == intent.expiresAt?.value?.toEpochMilliseconds() &&
            next.holdCount == 0L && newHolds == 0L)
        var count = 0L
        stock.allocations(next.id) { count = stockCheckedAdd(count, 1) }
        localStockCheck(count > 0 && count == next.allocationCount)
        requireAcquire(stock, LocalStockStoreProfile.reservationClaim(next.id), next.id)
    } else {
        localStockCheck(reserve == null && record.generation == stock.before(record.key)?.generation?.next() &&
            old.value.id == next.id && old.value.binding == next.binding && old.value.expiresAtEpochMillis == next.expiresAtEpochMillis &&
            old.allocationSetId == body.allocationSetId && old.allocationSetRevision == body.allocationSetRevision &&
            old.value.allocationCount == next.allocationCount && next.holdCount == stockCheckedAdd(old.value.holdCount, newHolds) &&
            stock.observedClaim(LocalStockStoreProfile.reservationClaim(next.id)) == next.id && old.value.retainsStockClaim)
        var allocations = 0L; var holds = 0L
        stock.range(StockQueryV2.ReservationMembers(next.id)) {
            when (val member = it.body) {
                is StockRelationalBody.Allocation -> {
                    localStockCheck(member.value.reservationId == next.id && stock.observedClaim(LocalStockStoreProfile.allocationClaim(member.value.allocation.allocationId)) == next.id)
                    allocations = stockCheckedAdd(allocations, 1)
                }
                is StockRelationalBody.Hold -> { localStockCheck(member.value.reservationId == next.id); holds = stockCheckedAdd(holds, 1) }
                else -> inconsistentStock()
            }
        }
        localStockCheck(allocations == old.value.allocationCount && holds == old.value.holdCount)
    }
    val baseRevision = old?.value?.revision ?: Revision(1)
    when (next.status) {
        ReservationStatus.RESERVED -> localStockCheck(old == null && next.revision == Revision(1) && next.issueId == null)
        ReservationStatus.HELD -> localStockCheck(old != null && newHolds > 0 && next.revision.value == stockCheckedAdd(baseRevision.value, newHolds) && next.issueId == null)
        ReservationStatus.ISSUED -> {
            val issueId = next.issueId ?: inconsistentStock()
            val intent = issueIntent(stock, issueId) ?: inconsistentStock()
            val expiresAt = next.expiresAtEpochMillis
            localStockCheck(intent.reservationId == next.id && intent.revision == baseRevision && next.revision == baseRevision.next() &&
                newHolds == 0L && next.holdCount == 0L && (old == null || old.value.status == ReservationStatus.RESERVED) &&
                (expiresAt == null || expiresAt > stock.recordedAt.value.toEpochMilliseconds()) && stock.issue(issueId) != null)
        }
        ReservationStatus.RELEASED -> {
            var release: StockIntentV2Wire.Release? = null
            stock.intents { if (it is StockIntentV2Wire.Release && it.reservationId == next.id) { localStockCheck(release == null); release = it } }
            val intent = release ?: inconsistentStock()
            val before = old?.value ?: inconsistentStock() // No create-and-release prefix masquerades as a persisted reservation.
            val withHolds = if (newHolds == 0L) before else InventoryRelationalPlanner.hold(before, newHolds)
            localStockCheck(intent.revision.value == stockCheckedAdd(baseRevision.value, newHolds))
            // Each independent reason advances the live domain revision; compare exact final fields below.
            val expected = InventoryRelationalPlanner.release(withHolds.copy(revision = intent.revision), intent.revision, releaseResolution(intent))
            localStockCheck(next == expected)
        }
    }
}

private suspend fun verifyHold(stock: LocalStockRelationalInspection, record: StockRelationalRecordV2, body: StockRelationalBody.Hold) {
    val command = stock.original.identity.producer.command
    localStockCheck(record.generation == Revision(1) && record.previous == null &&
        (command == LocalStockRelationalProfileV2.COMMAND || command == LocalStockRelationalProfileV2.FOOD_COMMAND ||
            command == LocalStockRelationalProfileV2.RETURN_COMMAND))
    val header = (stock.before(LocalStockRelationalProfileV2.reservationKey(body.value.reservationId))?.body as? StockRelationalBody.Reservation)?.value
        ?: inconsistentStock()
    localStockCheck(header.retainsStockClaim)
    val hold = body.value.hold
    val heldPosition = hold.position
    if (command == LocalStockRelationalProfileV2.FOOD_COMMAND || command == LocalStockRelationalProfileV2.RETURN_COMMAND)
        localStockCheck(heldPosition != null &&
        hold.reason == ReservationHoldReason.EXPIRY && stockRestoresPool(stock, heldPosition.pool))
    if (heldPosition == null) localStockCheck(hold.reason == ReservationHoldReason.RESERVATION_EXPIRED &&
        header.binding.exposure != CollectionExposure.UNCOLLECTED && header.expiresAtEpochMillis?.let { it <= stock.recordedAt.value.toEpochMilliseconds() } == true)
    else {
        var owns = false
        stock.range(StockQueryV2.PositionMembers(LocalStockRelationalProfileV2.positionKey(heldPosition).id)) { row ->
            val allocation = row.body as? StockRelationalBody.Allocation
            if (allocation?.value?.reservationId == header.id) owns = true
        }
        localStockCheck(owns)
        val intent = StockIntentV2Wire.Safety(PositionKeyV2Wire.from(heldPosition), stockV2HoldTag(hold.reason))
        localStockCheck(stock.intent(stockIntentId(intent)) == intent)
    }
    localStockCheck((stock.write(LocalStockRelationalProfileV2.reservationKey(header.id))?.body as? StockRelationalBody.Reservation)?.value?.let {
        it.status == ReservationStatus.HELD || it.status == ReservationStatus.RELEASED
    } == true)
}

private suspend fun verifyPosition(stock: LocalStockRelationalInspection, record: StockRelationalRecordV2,
    body: StockRelationalBody.Position, oldBody: StockRelationalBody?) {
    if (verifyStockRestorationPosition(stock, body, oldBody)) return
    val old = (oldBody as? StockRelationalBody.Position)?.value ?: unavailableProducer()
    var quantity = old.onHand; var safety = old.safety; var transitions = 0L
    stock.intents { intent -> when (intent) {
        is StockIntentV2Wire.Movement -> if (intent.position.restore(stock.scope) == old.key) {
            localStockCheck(intent.reason == 1 && intent.quantity.scaled < 0)
            quantity += intent.quantity; transitions = stockCheckedAdd(transitions, 1)
        }
        is StockIntentV2Wire.Safety -> if (intent.position.restore(stock.scope) == old.key) {
            safety = when (intent.reason) {
                1 -> StockSafety.RECALLED
                2 -> { localStockCheck(old.safety == StockSafety.CLEAR && old.expiresAtEpochMillis?.let { it <= stock.recordedAt.value.toEpochMilliseconds() } == true); StockSafety.EXPIRED }
                else -> unavailableProducer()
            }
            transitions = stockCheckedAdd(transitions, 1)
        }
        else -> Unit
    } }
    localStockCheck(transitions > 0 && body.value == old.copy(onHand = quantity, safety = safety) && record.key == LocalStockRelationalProfileV2.positionKey(old.key))
}

private suspend fun verifyPool(stock: LocalStockRelationalInspection, body: StockRelationalBody.Pool, oldBody: StockRelationalBody?) {
    val old = (oldBody as? StockRelationalBody.Pool)?.value ?: unavailableProducer()
    localStockCheck(body.value.key == old.key && body.value.lastEventOrdinal > old.lastEventOrdinal)
    var current = old; var count = 0L
    // The number of pool events is not capped or materialized. Traverse exact next ordinals.
    while (current.lastEventOrdinal < body.value.lastEventOrdinal) {
        var next: StockRelationalBody.ValueEvent? = null
        stock.records { record ->
            val event = record.body as? StockRelationalBody.ValueEvent
            if (event?.pool == old.key && event.ordinal == current.lastEventOrdinal + 1) { localStockCheck(next == null); next = event }
        }
        val event = next ?: inconsistentStock()
        localStockCheck(event.before == current)
        current = event.after; count = stockCheckedAdd(count, 1)
    }
    var actual = 0L
    stock.records { if ((it.body as? StockRelationalBody.ValueEvent)?.pool == old.key) actual = stockCheckedAdd(actual, 1) }
    localStockCheck(count == actual && current == body.value)
    var originalQuantity = stockZeroQuantity(old.onHand); var finalQuantity = stockZeroQuantity(old.onHand)
    stock.range(StockQueryV2.Positions(old.key.skuId, old.key.warehouseId)) { record ->
        val before = (record.body as? StockRelationalBody.Position)?.value ?: inconsistentStock()
        if (before.key.pool == old.key) {
            originalQuantity += before.onHand
            val after = (stock.write(record.key)?.body as? StockRelationalBody.Position)?.value ?: before
            finalQuantity += after.onHand
        }
    }
    // A genuine original return may create the missing quarantine custody row. It is an
    // actual extra canonical write/CAS, never an invented member of the BEFORE range.
    stock.records { record ->
        val position = (record.body as? StockRelationalBody.Position)?.value
        if (position?.key?.pool == old.key && stock.before(record.key) == null) finalQuantity += position.onHand
    }
    localStockCheck(originalQuantity == old.onHand && finalQuantity == body.value.onHand)
}

private suspend fun verifyIssue(stock: LocalStockRelationalInspection, record: StockRelationalRecordV2,
    body: StockRelationalBody.Issue, oldBody: StockRelationalBody?) {
    val issue = body.value
    val old = (oldBody as? StockRelationalBody.Issue)?.value
    if (old == null) {
        val intent = issueIntent(stock, issue.id) ?: inconsistentStock()
        val reservation = stock.reservation(issue.reservationId) ?: inconsistentStock()
        localStockCheck(record.generation == Revision(1) && issue.revision == Revision(1) && intent.reservationId == issue.reservationId &&
            issue.binding == reservation.binding && issue.allocationCount == reservation.allocationCount && issue.purpose == stockV2Purpose(intent.purpose) &&
            issue.recipeSnapshotId == intent.recipeId && issue.requiredStationIds == intent.stationIds && issue.startedStationIds.isEmpty() &&
            issue.disposition == (if (intent.recipeId == null) null else KitchenDisposition.ACTIVE))
        var count = 0L; var value = Money(issue.originalValue.currency, 0)
        stock.issueSlices(issue.id) { count = stockCheckedAdd(count, 1); value += it.originalValue }
        localStockCheck(count == issue.allocationCount && value == issue.originalValue)
        requireAcquire(stock, LocalStockStoreProfile.sourceClaim(issue.id), issue.id)
    } else {
        localStockCheck(issue.revision == old.revision.next())
        if (old.purpose == ValueIssuePurpose.RETAIL) {
            val intent = stockRetailReturnForIssue(stock, issue.id) ?: inconsistentStock()
            requireStockRetailReturnRequest(intent.request(), old)
            localStockCheck(IssueV2Wire.from(issue) == IssueV2Wire.from(copyIssueHeader(old, old.revision.next(), emptyList(), null)))
            return
        }
        localStockCheck(old.purpose == ValueIssuePurpose.DIRECT_RECIPE && old.disposition == KitchenDisposition.ACTIVE)
        var expected: LocalStockIssueHeader? = null
        stock.intents { when (it) {
            is StockIntentV2Wire.KitchenStart -> if (it.issueId == issue.id) {
                localStockCheck(expected == null && it.revision == old.revision && it.stationId in old.requiredStationIds && it.stationId !in old.startedStationIds)
                expected = copyIssueHeader(old, old.revision.next(), old.startedStationIds + it.stationId, old.disposition)
            }
            is StockIntentV2Wire.Waste -> if (it.issueId == issue.id) {
                localStockCheck(expected == null && it.revision == old.revision && old.startedStationIds.isNotEmpty())
                expected = copyIssueHeader(old, old.revision.next(), old.startedStationIds, KitchenDisposition.WASTE_AFTER_START)
            }
            is StockIntentV2Wire.Restore -> if (it.issueId == issue.id) {
                localStockCheck(expected == null)
                requireStockRestorationRequest(it.request(), old, stock.original.identity.producer.originalActorId, stock.recordedAt)
                expected = copyIssueHeader(old, old.revision.next(), emptyList(), KitchenDisposition.CANCELLED_BEFORE_START)
            }
            else -> Unit
        } }
        localStockCheck(expected != null && IssueV2Wire.from(issue) == IssueV2Wire.from(checkNotNull(expected)))
    }
}

private suspend fun verifyIssueSlice(stock: LocalStockRelationalInspection, record: StockRelationalRecordV2, body: StockRelationalBody.IssueSlice) {
    val slice = body.value
    if (stock.before(record.key) != null) { verifyStockRestoredSlice(stock, record, slice); return }
    localStockCheck(record.generation == Revision(1) && slice.restoredQuantity.scaled == 0L)
    val issue = stock.issue(slice.issueId) ?: inconsistentStock()
    val allocation = ((stock.write(LocalStockRelationalProfileV2.allocationKey(slice.allocation.allocationId))
        ?: stock.before(LocalStockRelationalProfileV2.allocationKey(slice.allocation.allocationId)))?.body as? StockRelationalBody.Allocation)?.value ?: inconsistentStock()
    localStockCheck(allocation.reservationId == issue.reservationId && allocation.allocation == slice.allocation &&
        (issue.purpose != ValueIssuePurpose.DIRECT_RECIPE || slice.allocation.position.serialId == null))
    val position = (stock.before(LocalStockRelationalProfileV2.positionKey(slice.allocation.position))?.body as? StockRelationalBody.Position)?.value ?: inconsistentStock()
    localStockCheck(position.isSellableAt(stock.recordedAt.value.toEpochMilliseconds()))
    verifySerialPosition(stock, position)
    val movement = StockIntentV2Wire.Movement(slice.issueId, slice.allocation.allocationId, PositionKeyV2Wire.from(slice.allocation.position), -slice.allocation.quantity, 1)
    localStockCheck(stock.intent(stockIntentId(movement)) == movement)
    var held = stockZeroQuantity(position.onHand)
    stock.range(StockQueryV2.PositionMembers(LocalStockRelationalProfileV2.positionKey(position.key).id)) { row ->
        val member = row.body as? StockRelationalBody.Allocation ?: return@range
        val header = (stock.before(LocalStockRelationalProfileV2.reservationKey(member.value.reservationId))?.body as? StockRelationalBody.Reservation)?.value ?: inconsistentStock()
        if (header.retainsStockClaim) held += member.value.allocation.quantity
    }
    stock.records { row -> (row.body as? StockRelationalBody.Allocation)?.value?.let { if (it.allocation.position == position.key) held += it.allocation.quantity } }
    localStockCheck(held <= position.onHand)
}

private suspend fun verifyValueEvent(stock: LocalStockRelationalInspection, record: StockRelationalRecordV2, event: StockRelationalBody.ValueEvent) {
    if (event.event == StockValueEventKind.RESTORE) { verifyStockRestorationEvent(stock, record, event); return }
    localStockCheck(record.generation == Revision(1) && event.event == StockValueEventKind.ISSUE && event.issueId == event.sourceId)
    val issue = stock.issue(event.sourceId) ?: inconsistentStock()
    localStockCheck(issueIntent(stock, issue.id) != null)
    val expected = InventoryValuationProjection.issue(event.before, -event.quantityDelta)
    localStockCheck(event.after == expected.after && event.carryingValueDelta == expected.value && event.originalValueDelta == expected.value &&
        stock.write(LocalStockRelationalProfileV2.poolKey(event.pool)) != null)
    var quantity = stockZeroQuantity(event.before.onHand); var value = Money(event.before.carryingValue.currency, 0); var count = 0L; var floors = 0L
    stock.records { row ->
        val slice = row.body as? StockRelationalBody.ValueSlice
        if (slice?.eventId == record.key.id) {
            quantity += slice.quantity; value += slice.originalValue; count = stockCheckedAdd(count, 1)
            floors = stockCheckedAdd(floors, InventoryValuationProjection.slice(-event.originalValueDelta, slice.quantity, -event.quantityDelta).floor.minor)
        }
    }
    localStockCheck(count == event.sliceCount && count > 0 && quantity == -event.quantityDelta && value == -event.originalValueDelta &&
        value.minor >= floors && value.minor - floors < count)
    val surplus = value.minor - floors
    stock.records { row ->
        val slice = row.body as? StockRelationalBody.ValueSlice ?: return@records
        if (slice.eventId != record.key.id) return@records
        val share = InventoryValuationProjection.slice(value, slice.quantity, quantity)
        var rank = 0L
        stock.records { otherRow ->
            val other = otherRow.body as? StockRelationalBody.ValueSlice
            if (other?.eventId == record.key.id) {
                val otherShare = InventoryValuationProjection.slice(value, other.quantity, quantity)
                if (otherShare.remainder > share.remainder || otherShare.remainder == share.remainder && other.allocationId.value < slice.allocationId.value)
                    rank = stockCheckedAdd(rank, 1)
            }
        }
        localStockCheck(slice.originalValue == Money(value.currency, share.floor.minor + if (rank < surplus) 1 else 0))
    }
}

private suspend fun verifyValueSlice(stock: LocalStockRelationalInspection, record: StockRelationalRecordV2, slice: StockRelationalBody.ValueSlice) {
    localStockCheck(record.generation == Revision(1))
    val event = stock.write(AggregateKey(StockRelationalKindsV2.VALUE_EVENT, slice.eventId))?.body as? StockRelationalBody.ValueEvent ?: inconsistentStock()
    if (event.event == StockValueEventKind.RESTORE) {
        val retail = stockRetailReturnForSource(stock, slice.sourceId)
        if (retail != null) { verifyStockRetailReturnValueSlice(stock, record, slice, event, retail); return }
    }
    val issueId = slice.issueId ?: inconsistentStock()
    val issue = stock.write(LocalStockRelationalProfileV2.issueSliceKey(issueId, slice.allocationId))?.body as? StockRelationalBody.IssueSlice ?: inconsistentStock()
    localStockCheck(event.pool == slice.pool && event.sourceId == slice.sourceId && event.ordinal == slice.eventOrdinal && event.issueId == issueId &&
        issue.value.allocation.position.pool == slice.pool && issue.value.allocation.quantity == slice.quantity && issue.value.originalValue == slice.originalValue)
    when (event.event) {
        StockValueEventKind.ISSUE -> localStockCheck(slice.sourceId == issueId && issue.value.restoredQuantity.scaled == 0L)
        StockValueEventKind.RESTORE -> localStockCheck(slice.sourceId != issueId &&
            stockRestorationForSource(stock, slice.sourceId)?.issueId == issueId && issue.value.restoredQuantity == slice.quantity)
        else -> unavailableProducer()
    }
}

private suspend fun verifyIntent(stock: LocalStockRelationalInspection, intent: StockIntentV2Wire) {
    when (intent) {
        is StockIntentV2Wire.Reserve -> {
            val header = stock.reservation(intent.id) ?: inconsistentStock()
            localStockCheck(intent.setRevision.value >= 1 && intent.lineCount in 1..256 && intent.ordinal in 0..31 &&
                intent.expiresAt?.let { it > stock.recordedAt } != false && header.binding == intent.binding.restore())
            for (ordinal in 0 until intent.lineCount) {
                var found = false
                stock.intents { if (it is StockIntentV2Wire.Line && it.reservationId == intent.id && it.ordinal == ordinal) { localStockCheck(!found); found = true } }
                localStockCheck(found)
            }
        }
        is StockIntentV2Wire.Line -> verifyLine(stock, intent)
        is StockIntentV2Wire.Issue -> {
            localStockCheck(stock.issue(intent.issueId) != null && intent.stationIds.distinct().size == intent.stationIds.size &&
                if (intent.purpose == 1) intent.recipeId == null && intent.stationIds.isEmpty()
                else intent.purpose == 2 && intent.recipeId != null && intent.stationIds.size in 1..32)
        }
        is StockIntentV2Wire.Release -> localStockCheck(stock.reservation(intent.reservationId)?.status == ReservationStatus.RELEASED)
        is StockIntentV2Wire.ExpireSweep -> {
            localStockCheck(stock.original.identity.producer.command == LocalStockRelationalProfileV2.COMMAND)
            val request = LocalStockRelationalCommandsV2.decode(stock.original.normalized) as? LocalStockRelationalCommand.Expire ?: inconsistentStock()
            localStockCheck(request.sweepId == intent.sweepId)
        }
        is StockIntentV2Wire.Safety -> {
            val key = intent.position.restore(stock.scope)
            val command = stock.original.identity.producer.command
            localStockCheck(command == LocalStockRelationalProfileV2.COMMAND ||
                (command == LocalStockRelationalProfileV2.FOOD_COMMAND || command == LocalStockRelationalProfileV2.RETURN_COMMAND) &&
                    intent.reason == 2 && stockRestoresPool(stock, key.pool))
            val before = (stock.before(LocalStockRelationalProfileV2.positionKey(key))?.body as? StockRelationalBody.Position)?.value ?: inconsistentStock()
            val after = stock.write(LocalStockRelationalProfileV2.positionKey(key))?.body as? StockRelationalBody.Position ?: inconsistentStock()
            localStockCheck(intent.reason in 1..2 && after.value.safety == (if (intent.reason == 1) StockSafety.RECALLED else StockSafety.EXPIRED))
            if (intent.reason == 2) localStockCheck(before.safety == StockSafety.CLEAR && before.expiresAtEpochMillis?.let { it <= stock.recordedAt.value.toEpochMilliseconds() } == true)
            stock.range(StockQueryV2.PositionMembers(LocalStockRelationalProfileV2.positionKey(key).id)) { row ->
                val allocation = row.body as? StockRelationalBody.Allocation ?: return@range
                val reservation = (stock.before(LocalStockRelationalProfileV2.reservationKey(allocation.value.reservationId))?.body as? StockRelationalBody.Reservation)?.value ?: inconsistentStock()
                if (reservation.retainsStockClaim) {
                    val hold = StockRelationalBody.Hold(InventoryHoldRecord(reservation.id, ReservationHold(stockV2Hold(intent.reason), key)))
                    val actual = stock.write(stockKey(hold)) ?: stock.before(stockKey(hold)) ?: inconsistentStock()
                    localStockCheck(actual.body == hold)
                    val next = (stock.write(LocalStockRelationalProfileV2.reservationKey(reservation.id))?.body as? StockRelationalBody.Reservation)?.value ?: reservation
                    localStockCheck(next.status == ReservationStatus.HELD || next.status == ReservationStatus.RELEASED)
                }
            }
        }
        is StockIntentV2Wire.Movement -> {
            if (intent.reason == 2) verifyStockRestorationMovement(stock, intent)
            else {
                localStockCheck(intent.reason == 1 && intent.quantity.scaled < 0 && intent.allocationId != null)
                val slice = (stock.write(LocalStockRelationalProfileV2.issueSliceKey(intent.sourceId, checkNotNull(intent.allocationId)))?.body
                    as? StockRelationalBody.IssueSlice)?.value ?: inconsistentStock()
                localStockCheck(slice.allocation.position == intent.position.restore(stock.scope) && slice.allocation.quantity == -intent.quantity)
            }
        }
        is StockIntentV2Wire.KitchenStart -> localStockCheck(stock.issue(intent.issueId)?.startedStationIds?.contains(intent.stationId) == true)
        is StockIntentV2Wire.Waste -> localStockCheck(stock.issue(intent.issueId)?.disposition == KitchenDisposition.WASTE_AFTER_START)
        is StockIntentV2Wire.Restore -> verifyStockRestorationIntent(stock, intent)
        is StockIntentV2Wire.RetailReturn -> verifyStockRetailReturnIntent(stock, intent)
    }
}

/** Exact original-order FEFO replay using complete literal current-position/member ranges. */
private suspend fun verifyLine(stock: LocalStockRelationalInspection, line: StockIntentV2Wire.Line) {
    val reserve = reserveIntent(stock, line.reservationId) ?: inconsistentStock()
    val authority = line.authority
    localStockCheck(line.ordinal in 0 until reserve.lineCount && line.quantity.scaled > 0 && authority.skuId == line.skuId &&
        authority.warehouseId == line.warehouseId && authority.unitId == line.quantity.unitId && authority.scale == line.quantity.scale &&
        authority.tracking in 1..3 && authority.catalogRevision.value >= 1 && authority.skuRevision.value >= 1)
    val item = (stock.before(LocalStockRelationalProfileV2.itemKey(line.skuId))?.body as? StockRelationalBody.Item) ?: inconsistentStock()
    localStockCheck(item.unitId == authority.unitId && item.scale == authority.scale && item.serialized == (authority.tracking == 3))
    for (list in listOf(authority.itemSources, authority.warehouseSources)) {
        localStockCheck(list.size in 1..16 && list.map { it.kind to it.id }.distinct().size == list.size)
        for (stamp in list) {
            PayloadDigest(stamp.digest)
            var found = false
            stock.original.frames { frame ->
                val source = (frame as? RetainedCommandFrameV2.Source)?.value as? StreamedSourceObservationV2.Current
                if (source?.key == AggregateKey(stamp.kind, stamp.id)) {
                    localStockCheck(!found && source.generation == stamp.revision && source.payloadHash.value == stamp.digest); found = true
                }
            }
            localStockCheck(found)
        }
    }
    var remaining = line.quantity
    var previous: PositionOrder? = null
    var matchedAllocations = 0L
    stock.range(StockQueryV2.Positions(line.skuId, line.warehouseId)) { row ->
        val position = (row.body as? StockRelationalBody.Position)?.value ?: inconsistentStock()
        localStockCheck(position.key.pool.skuId == line.skuId && position.key.pool.warehouseId == line.warehouseId &&
            position.onHand.unitId == line.quantity.unitId && position.onHand.scale == line.quantity.scale &&
            (authority.tracking == 3) == (position.key.serialId != null) && (authority.tracking != 2 || position.key.lotId != null) &&
            (!authority.requiresExpiry || position.expiresAtEpochMillis != null))
        verifySerialPosition(stock, position)
        val order = PositionOrder(position.expiresAtEpochMillis ?: Long.MAX_VALUE, position.key.lotId?.value.orEmpty(), position.key.serialId?.value.orEmpty(), row.key.id.value)
        previous?.let { localStockCheck(it < order) }; previous = order
        var actual: StockAllocation? = null
        stock.allocations(line.reservationId) { if (it.allocation.lineId == line.lineId && it.allocation.position == position.key) {
            localStockCheck(actual == null); actual = it.allocation; matchedAllocations = stockCheckedAdd(matchedAllocations, 1)
        } }
        if (remaining.scaled == 0L || !position.isSellableAt(stock.recordedAt.value.toEpochMilliseconds())) {
            localStockCheck(actual == null); return@range
        }
        var held = stockZeroQuantity(line.quantity)
        stock.range(StockQueryV2.PositionMembers(row.key.id)) members@ { member ->
            val allocation = member.body as? StockRelationalBody.Allocation ?: return@members
            localStockCheck(allocation.value.allocation.position == position.key)
            val reservation = (stock.before(LocalStockRelationalProfileV2.reservationKey(allocation.value.reservationId))?.body as? StockRelationalBody.Reservation)?.value ?: inconsistentStock()
            if (reservation.retainsStockClaim) held += allocation.value.allocation.quantity
        }
        var selected = stockZeroQuantity(line.quantity)
        stock.records { candidate ->
            val allocation = (candidate.body as? StockRelationalBody.Allocation)?.value ?: return@records
            if (allocation.allocation.position != position.key) return@records
            val owner = reserveIntent(stock, allocation.reservationId) ?: inconsistentStock()
            val prior = lineIntent(stock, owner.id, allocation.allocation.lineId) ?: inconsistentStock()
            if (owner.ordinal < reserve.ordinal || owner.ordinal == reserve.ordinal && prior.ordinal < line.ordinal) selected += allocation.allocation.quantity
        }
        val available = InventoryRelationalPlanner.available(InventoryPositionAvailability(position, held), stock.recordedAt.value.toEpochMilliseconds())
        localStockCheck(selected <= available)
        val free = available - selected
        val expected = if (free < remaining) free else remaining
        localStockCheck(if (expected.scaled == 0L) actual == null else actual?.quantity == expected)
        remaining -= expected
    }
    localStockCheck(remaining.scaled == 0L)
    var allAllocations = 0L
    stock.allocations(line.reservationId) { if (it.allocation.lineId == line.lineId) allAllocations = stockCheckedAdd(allAllocations, 1) }
    localStockCheck(matchedAllocations == allAllocations && matchedAllocations > 0)
}

/**
 * Required before-state claims derive from literal typed obligations, not a caller-issued set.
 * Public replay deduplicates by the original Source key after every occurrence agrees exactly.
 */
internal suspend fun stockRequiredClaimSources(stock: LocalStockRelationalInspection,
    consume: suspend (StreamedSourceObservationV2.Claim) -> Unit) {
    suspend fun required(key: ClaimKey, owner: DomainId?) {
        var actual: StreamedSourceObservationV2.Claim? = null
        stock.original.frames { frame ->
            val source = (frame as? RetainedCommandFrameV2.Source)?.value as? StreamedSourceObservationV2.Claim
            if (source?.key == key) { localStockCheck(actual == null && source.ownerId == owner); actual = source }
        }
        consume(actual ?: inconsistentStock())
    }
    suspend fun reservation(id: DomainId) {
        val row = stock.before(LocalStockRelationalProfileV2.reservationKey(id)) ?: inconsistentStock()
        val body = row.body as? StockRelationalBody.Reservation ?: inconsistentStock()
        localStockCheck(body.value.id == id)
        required(LocalStockStoreProfile.reservationClaim(id), id)
        var allocations = 0L; var holds = 0L
        stock.range(StockQueryV2.ReservationMembers(id)) { member -> when (val value = member.body) {
            is StockRelationalBody.Allocation -> {
                val allocation = value.value.allocation
                localStockCheck(value.value.reservationId == id && allocation.allocationId == LocalStockRelationalProfileV2.allocationId(
                    body.allocationSetId, body.allocationSetRevision, allocation.lineId, allocation.position))
                required(LocalStockStoreProfile.allocationClaim(allocation.allocationId), id)
                allocations = stockCheckedAdd(allocations, 1)
            }
            is StockRelationalBody.Hold -> {
                localStockCheck(value.value.reservationId == id); holds = stockCheckedAdd(holds, 1)
            }
            else -> inconsistentStock()
        } }
        localStockCheck(allocations == body.value.allocationCount && holds == body.value.holdCount)
    }
    suspend fun issue(id: DomainId) {
        val value = (stock.before(LocalStockRelationalProfileV2.issueKey(id))?.body as? StockRelationalBody.Issue)?.value ?: inconsistentStock()
        localStockCheck(value.id == id)
        required(LocalStockStoreProfile.sourceClaim(id), id)
        var count = 0L; var total = Money(value.originalValue.currency, 0)
        stock.range(StockQueryV2.IssueValues(id)) { row -> when (val body = row.body) {
            is StockRelationalBody.IssueSlice -> {
                localStockCheck(body.value.issueId == id); count = stockCheckedAdd(count, 1); total += body.value.originalValue
            }
            is StockRelationalBody.ValueEvent -> {
                localStockCheck(body.issueId == id)
                if (body.event == StockValueEventKind.RESTORE) required(LocalStockStoreProfile.sourceClaim(body.sourceId), body.sourceId)
            }
            is StockRelationalBody.ValueSlice -> localStockCheck(body.issueId == id)
            else -> inconsistentStock()
        } }
        localStockCheck(count == value.allocationCount && total == value.originalValue)
    }
    suspend fun serial(position: StockPosition) {
        val id = position.key.serialId ?: return
        verifySerialPosition(stock, position)
        val source = stock.source(LocalStockStoreProfile.serialKey(id)) ?: inconsistentStock()
        val body = stockBody(stockDecode(source.payload, StockRecordWire.serializer()).body, StockSerialWire.serializer())
        required(LocalStockStoreProfile.serialClaim(id), id)
        required(LocalStockStoreProfile.serialCodeClaim(body.serialCode), id)
    }
    // Only actual validated writes can introduce new permanent claims; compare BEFORE, not after.
    stock.original.frames { frame ->
        val change = (frame as? RetainedCommandFrameV2.Claim)?.value
        if (change != null && change.key.namespace in stockOwnedClaimNamespaces) {
            localStockCheck(change.action == RetainedClaimAction.ACQUIRE && change.key.namespace in setOf(
                LocalStockStoreProfile.RESERVATION_CLAIM, LocalStockStoreProfile.ALLOCATION_CLAIM, LocalStockStoreProfile.SOURCE_CLAIM))
            required(change.key, null)
        }
    }
    stock.records { record -> if (record.body is StockRelationalBody.Item) {
        localStockCheck(stock.before(record.key)?.body == record.body)
        required(LocalStockStoreProfile.itemClaim(record.key.id), record.key.id)
    } }
    stockTransferClaimRequirements(stock) { key, owner -> required(key, owner) }
    // Every existing header actually loaded by stock has its complete per-ID membership proof.
    // Unrelated claim keys cannot be smuggled in merely by declaring a stock namespace.
    stock.original.frames { frame ->
        val source = (frame as? RetainedCommandFrameV2.Source)?.value as? StreamedSourceObservationV2.Aggregate ?: return@frames
        when (source.key.kind) {
            StockRelationalKindsV2.RESERVATION -> reservation(source.key.id)
            StockRelationalKindsV2.ISSUE -> issue(source.key.id)
        }
    }
    // Existing allocations observed through a complete position range require their current
    // owner's full membership, even when the owner is RELEASED/ISSUED and retains no quantity.
    stock.original.frames { frame ->
        val source = (frame as? RetainedCommandFrameV2.Source)?.value as? StreamedSourceObservationV2.StockRange ?: return@frames
        if (source.query is StockQueryV2.PositionMembers) stock.range(source.query) { row ->
            val value = row.body as? StockRelationalBody.Allocation
            if (value != null) reservation(value.value.reservationId)
        }
    }
    // Only Line candidates and actual issued slices perform current serial qualification reads.
    // Merely adding an unrelated serial-origin row is not permission for its claim observations.
    stock.intents { intent -> when (intent) {
        is StockIntentV2Wire.Line -> stock.range(StockQueryV2.Positions(intent.skuId, intent.warehouseId)) { row ->
            serial((row.body as? StockRelationalBody.Position)?.value ?: inconsistentStock())
        }
        is StockIntentV2Wire.Issue -> stock.issueSlices(intent.issueId) { slice ->
            val position = (stock.before(LocalStockRelationalProfileV2.positionKey(slice.allocation.position))?.body
                as? StockRelationalBody.Position)?.value ?: inconsistentStock()
            serial(position)
        }
        else -> Unit
    } }
}

/** Reverse exact-key projection after the complete forward check; no whole-stock key Set. */
internal suspend fun stockClaimSourceRequired(stock: LocalStockRelationalInspection,
    source: StreamedSourceObservationV2.Claim): Boolean {
    if (source.key.namespace !in stockOwnedClaimNamespaces) return false
    val change = stock.claim(source.key)
    if (change != null) {
        localStockCheck(change.action == RetainedClaimAction.ACQUIRE && change.key.namespace in setOf(
            LocalStockStoreProfile.RESERVATION_CLAIM, LocalStockStoreProfile.ALLOCATION_CLAIM, LocalStockStoreProfile.SOURCE_CLAIM) && source.ownerId == null)
        return true
    }
    if (source.key.namespace == LocalStockStoreProfile.DOCUMENT_CLAIM || source.key.namespace == LocalStockStoreProfile.SOURCE_CLAIM) {
        var transfer = false
        stockTransferClaimRequirements(stock) { key, owner -> if (key == source.key) {
            localStockCheck(source.ownerId == owner); transfer = true
        } }
        if (transfer) return true
    }
    when (source.key.namespace) {
        LocalStockStoreProfile.ITEM_CLAIM -> {
            val id = DomainId(source.key.key)
            val row = stock.write(LocalStockRelationalProfileV2.itemKey(id)) ?: return false
            localStockCheck(row.body is StockRelationalBody.Item && row.body.skuId == id &&
                stock.before(row.key)?.body == row.body && source.ownerId == id)
            return true // Exact touched-SKU forward/reverse validation already ran.
        }
        LocalStockStoreProfile.RESERVATION_CLAIM -> {
            val id = DomainId(source.key.key)
            val body = stock.before(LocalStockRelationalProfileV2.reservationKey(id))?.body as? StockRelationalBody.Reservation ?: return false
            localStockCheck(body.value.id == id && source.ownerId == id)
            return true // Complete header/membership/claim check was performed in the forward pass.
        }
        LocalStockStoreProfile.ALLOCATION_CLAIM -> {
            val id = DomainId(source.key.key)
            val body = stock.before(LocalStockRelationalProfileV2.allocationKey(id))?.body as? StockRelationalBody.Allocation ?: return false
            val owner = body.value.reservationId
            if (stock.before(LocalStockRelationalProfileV2.reservationKey(owner)) == null) return false
            localStockCheck(body.value.allocation.allocationId == id && source.ownerId == owner)
            var member = false
            stock.range(StockQueryV2.ReservationMembers(owner)) { row -> if (row.key == LocalStockRelationalProfileV2.allocationKey(id)) {
                localStockCheck(!member && row.body == body); member = true
            } }
            return member
        }
        LocalStockStoreProfile.SOURCE_CLAIM -> {
            val id = DomainId(source.key.key)
            val body = stock.before(LocalStockRelationalProfileV2.issueKey(id))?.body as? StockRelationalBody.Issue
            if (body != null) {
                localStockCheck(body.value.id == id && source.ownerId == id)
                return true
            }
            if (!stockRestorationClaimSource(stock, id)) return false
            localStockCheck(source.ownerId == id)
            return true
        }
        LocalStockStoreProfile.SERIAL_ID_CLAIM, LocalStockStoreProfile.SERIAL_CODE_CLAIM -> {
            var found = false
            suspend fun position(value: StockPosition) {
                val id = value.key.serialId ?: return
                val original = stock.source(LocalStockStoreProfile.serialKey(id)) ?: inconsistentStock()
                val serial = stockBody(stockDecode(original.payload, StockRecordWire.serializer()).body, StockSerialWire.serializer())
                if (source.key == LocalStockStoreProfile.serialClaim(id) || source.key == LocalStockStoreProfile.serialCodeClaim(serial.serialCode)) {
                    localStockCheck(source.ownerId == id); found = true
                }
            }
            stock.intents { intent -> when (intent) {
                is StockIntentV2Wire.Line -> stock.range(StockQueryV2.Positions(intent.skuId, intent.warehouseId)) { row ->
                    position((row.body as? StockRelationalBody.Position)?.value ?: inconsistentStock())
                }
                is StockIntentV2Wire.Issue -> stock.issueSlices(intent.issueId) { slice ->
                    position((stock.before(LocalStockRelationalProfileV2.positionKey(slice.allocation.position))?.body
                        as? StockRelationalBody.Position)?.value ?: inconsistentStock())
                }
                else -> Unit
            } }
            return found
        }
        else -> return false
    }
}

/** Exact immutable serial origin/qualification at acceptance; no current renewal or accepting flag. */
private suspend fun verifySerialPosition(stock: LocalStockRelationalInspection, position: StockPosition) {
    val serialId = position.key.serialId ?: return
    val source = stock.source(LocalStockStoreProfile.serialKey(serialId)) ?: inconsistentStock()
    val envelope = stockDecode(source.payload, StockRecordWire.serializer())
    localStockCheck(envelope.scope.restore() == stock.scope && envelope.kind == source.key.kind && envelope.id == serialId &&
        envelope.storageRevision == source.generation && envelope.producer.operationId == source.producer.operationId &&
        envelope.producer.commandKey() == source.producer.command && envelope.producer.actorId == source.producer.originalActorId &&
        envelope.producer.digest == source.producer.semanticDigest.value)
    val serial = stockBody(envelope.body, StockSerialWire.serializer())
    requireStockSerialCode(serial.serialCode)
    requireStockSerialPosition(serial, position)
    localStockCheck(serial.serialId == serialId && serial.baseQuantity == stockSerialUnit(serial.baseQuantity) &&
        serial.originalPosition.serialId == serialId && serial.originalPosition.skuId == serial.skuId &&
        stock.observedClaim(LocalStockStoreProfile.serialClaim(serialId)) == serialId &&
        stock.observedClaim(LocalStockStoreProfile.serialCodeClaim(serial.serialCode)) == serialId &&
        (serial.registration == null) == (serial.registeredAt == null))
    serial.registration?.let {
        val registration = it.restore()
        localStockCheck(it == StockSerialRegistrationWire.from(registration))
        requireSerialRegistration(registration, stock.scope, serialId, serial.skuId, serial.serialCode,
            serial.registeredAt ?: inconsistentStock())
    }
}

private data class PositionOrder(val expiry: Long, val lot: String, val serial: String, val id: String) : Comparable<PositionOrder> {
    override fun compareTo(other: PositionOrder): Int {
        if (expiry != other.expiry) return expiry.compareTo(other.expiry)
        if (lot != other.lot) return lot.compareTo(other.lot)
        if (serial != other.serial) return serial.compareTo(other.serial)
        return id.compareTo(other.id)
    }
}

private suspend fun verifyReservationOrder(stock: LocalStockRelationalInspection) {
    var reserves = 0; var issues = 0; var releases = 0L
    var restorations = 0L; var retailReturns = 0L; var kitchenChanges = 0L; var sweeps = 0L
    var foodExposure: CollectionExposure? = null
    stock.intents { when (it) {
        is StockIntentV2Wire.Reserve -> {
            reserves++; localStockCheck(reserves <= 32)
            when (stock.original.identity.producer.command) {
                LocalStockRelationalProfileV2.FOOD_COMMAND -> {
                    val exposure = it.binding.restore().exposure
                    localStockCheck(it.lineCount in 1..16 && it.expiresAt == null && exposure in setOf(CollectionExposure.UNKNOWN, CollectionExposure.UNCOLLECTED) &&
                        (foodExposure == null || foodExposure == exposure))
                    foodExposure = exposure
                    if (exposure == CollectionExposure.UNCOLLECTED) localStockCheck(reserves <= 8)
                }
                LocalStockRelationalProfileV2.COLLECTION_COMMAND -> localStockCheck(it.lineCount in 1..256)
                else -> inconsistentStock()
            }
        }
        is StockIntentV2Wire.Issue -> { issues++; localStockCheck(issues <= 8) }
        is StockIntentV2Wire.Release -> {
            releases = stockCheckedAdd(releases, 1)
            localStockCheck(releases <= 32L || stock.original.identity.producer.command == LocalStockRelationalProfileV2.COMMAND)
        }
        is StockIntentV2Wire.Restore -> { restorations = stockCheckedAdd(restorations, 1); localStockCheck(restorations <= 1) }
        is StockIntentV2Wire.RetailReturn -> { retailReturns = stockCheckedAdd(retailReturns, 1); localStockCheck(retailReturns <= 1) }
        is StockIntentV2Wire.KitchenStart, is StockIntentV2Wire.Waste -> kitchenChanges = stockCheckedAdd(kitchenChanges, 1)
        is StockIntentV2Wire.ExpireSweep -> sweeps = stockCheckedAdd(sweeps, 1)
        else -> Unit
    } }
    localStockCheck(releases == 0L || reserves == 0 && issues == 0)
    if (restorations != 0L) localStockCheck(stock.original.identity.producer.command == LocalStockRelationalProfileV2.FOOD_COMMAND &&
        reserves == 0 && issues == 0 && releases == 0L && retailReturns == 0L && kitchenChanges == 0L && sweeps == 0L)
    if (stock.original.identity.producer.command == LocalStockRelationalProfileV2.RETURN_COMMAND)
        localStockCheck(retailReturns == 1L && reserves == 0 && issues == 0 && releases == 0L &&
            restorations == 0L && kitchenChanges == 0L && sweeps == 0L)
    else localStockCheck(retailReturns == 0L)
    if (stock.original.identity.producer.command == LocalStockRelationalProfileV2.COLLECTION_COMMAND) localStockCheck(reserves <= 1 && issues <= 1)
    for (ordinal in 0 until reserves) {
        var count = 0
        stock.intents { if (it is StockIntentV2Wire.Reserve && it.ordinal == ordinal) count++ }
        localStockCheck(count == 1)
    }
}

/** Whole safety-administration producer; fresh/receiving have their distinct literal validators. */
internal suspend fun requireLocalStockRelationalProducer(original: RetainedStreamedCommand, stock: LocalStockRelationalInspection) {
    localStockCheck(original.identity.producer.command == LocalStockRelationalProfileV2.COMMAND &&
        LocalStockRelationalCommandsV2.encode(LocalStockRelationalCommandsV2.decode(original.normalized)) == original.normalized)
    val request = LocalStockRelationalCommandsV2.decode(original.normalized)
    val result = stockDecode(original.result, StockAdminResultV2Wire.serializer())
    localStockCheck(result.format == 2)
    when (request) {
        is LocalStockRelationalCommand.Recall -> {
            localStockCheck(result.id == request.lotId && result.action == 1 &&
                stock.before(LocalStockRelationalProfileV2.itemKey(request.skuId))?.generation == request.expectedItemRevision)
            var count = 0L
            stock.range(StockQueryV2.LotPositions(request.skuId, request.lotId)) { row ->
                val position = row.body as? StockRelationalBody.Position ?: inconsistentStock()
                localStockCheck(position.value.key.pool.skuId == request.skuId && position.value.key.lotId == request.lotId)
                val intent = StockIntentV2Wire.Safety(PositionKeyV2Wire.from(position.value.key), 1)
                localStockCheck(stock.intent(stockIntentId(intent)) == intent); count = stockCheckedAdd(count, 1)
            }
            localStockCheck(count > 0)
        }
        is LocalStockRelationalCommand.Expire -> {
            localStockCheck(result.id == request.sweepId && result.action == 2)
            val sweep = StockIntentV2Wire.ExpireSweep(request.sweepId)
            localStockCheck(stock.intent(stockIntentId(sweep)) == sweep)
            stock.range(StockQueryV2.AllPositions) { row ->
                val p = (row.body as? StockRelationalBody.Position)?.value ?: inconsistentStock()
                if (p.safety == StockSafety.CLEAR && p.expiresAtEpochMillis?.let { it <= stock.recordedAt.value.toEpochMilliseconds() } == true) {
                    val intent = StockIntentV2Wire.Safety(PositionKeyV2Wire.from(p.key), 2)
                    localStockCheck(stock.intent(stockIntentId(intent)) == intent)
                }
            }
            stock.range(StockQueryV2.AllReservations) { row ->
                val header = (row.body as? StockRelationalBody.Reservation)?.value ?: inconsistentStock()
                if (header.retainsStockClaim && header.expiresAtEpochMillis?.let { it <= stock.recordedAt.value.toEpochMilliseconds() } == true) {
                    val after = stock.reservation(header.id) ?: header
                    if (header.binding.exposure == CollectionExposure.UNCOLLECTED) localStockCheck(after.status == ReservationStatus.RELEASED)
                    else {
                        val hold = StockRelationalBody.Hold(InventoryHoldRecord(header.id, ReservationHold(ReservationHoldReason.RESERVATION_EXPIRED)))
                        localStockCheck((stock.write(stockKey(hold)) ?: stock.before(stockKey(hold)))?.body == hold && after.status == ReservationStatus.HELD)
                    }
                }
            }
        }
        else -> inconsistentStock() // Routed to their own fresh/receiving producers, never safety.
    }
    stock.intents { intent -> when (intent) {
        is StockIntentV2Wire.ExpireSweep -> localStockCheck(request is LocalStockRelationalCommand.Expire && request.sweepId == intent.sweepId)
        is StockIntentV2Wire.Safety -> when (request) {
            is LocalStockRelationalCommand.Recall -> localStockCheck(intent.reason == 1 && intent.position.skuId == request.skuId && intent.position.lotId == request.lotId)
            is LocalStockRelationalCommand.Expire -> localStockCheck(intent.reason == 2)
            else -> inconsistentStock()
        }
        is StockIntentV2Wire.Release -> {
            localStockCheck(request is LocalStockRelationalCommand.Expire && releaseResolution(intent) == null)
            val header = (stock.before(LocalStockRelationalProfileV2.reservationKey(intent.reservationId))?.body as? StockRelationalBody.Reservation)?.value ?: inconsistentStock()
            localStockCheck(header.binding.exposure == CollectionExposure.UNCOLLECTED && header.expiresAtEpochMillis?.let { it <= stock.recordedAt.value.toEpochMilliseconds() } == true)
        }
        else -> inconsistentStock()
    } }
    original.frames { frame -> when (frame) {
        is RetainedCommandFrameV2.Write -> localStockCheck(frame.value.key.kind in setOf(StockRelationalKindsV2.ITEM, StockRelationalKindsV2.POSITION,
            StockRelationalKindsV2.RESERVATION, StockRelationalKindsV2.HOLD, StockRelationalKindsV2.POSTING))
        is RetainedCommandFrameV2.Claim, is RetainedCommandFrameV2.Job -> inconsistentStock()
        is RetainedCommandFrameV2.Effect -> localStockCheck(frame.value.kind == LocalStockRelationalProfileV2.EFFECT_KIND || frame.value.kind == STOCK_ACTION_EFFECT_V2)
        is RetainedCommandFrameV2.Source -> when (val source = frame.value) {
            is StreamedSourceObservationV2.Current, is StreamedSourceObservationV2.CurrentAbsent,
            is StreamedSourceObservationV2.StockItemAbsent,
            is StreamedSourceObservationV2.EstateMember, is StreamedSourceObservationV2.EstateEnd -> inconsistentStock()
            is StreamedSourceObservationV2.Claim -> localStockCheck(source.key.namespace in stockOwnedClaimNamespaces)
            else -> Unit
        }
        else -> Unit
    } }
    stock.requireResources(emptySet())
}
