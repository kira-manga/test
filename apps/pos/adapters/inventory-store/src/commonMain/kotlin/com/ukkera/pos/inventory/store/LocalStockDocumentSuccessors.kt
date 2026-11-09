package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.inventory.InventoryPostedEffectKind
import com.ukkera.pos.inventory.InventoryPostedRecords
import com.ukkera.pos.inventory.InventoryRecordCodec
import com.ukkera.pos.inventory.InventoryScope
import com.ukkera.pos.inventory.ReservationHold
import com.ukkera.pos.inventory.ReservationHoldReason
import com.ukkera.pos.inventory.ReservationStatus
import com.ukkera.pos.inventory.StockCustody
import com.ukkera.pos.inventory.StockPosition
import com.ukkera.pos.inventory.StockReservation
import com.ukkera.pos.inventory.StockSafety
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import kotlinx.serialization.KSerializer

/** Exact command inputs → original read rows → document counters → complete ordered effects. */
internal fun requireStockDocumentSuccessors(
    evidence: RetainedCommandEvidence,
    observed: Map<AggregateKey, StockHistoricalRecord>,
    previous: Map<AggregateKey, StockHistoricalRecord>,
    after: Map<AggregateKey, StockHistoricalRecord>,
    records: InventoryPostedRecords?,
) {
    fun itemVersion(skuId: DomainId, expected: Revision?) {
        localStockCheck(observed[LocalStockStoreProfile.itemKey(skuId)]?.row?.storageGeneration == expected, LocalStockFailure.STORAGE_INCONSISTENT)
    }
    when (evidence.command) {
        LocalStockStoreProfile.OPENING, LocalStockStoreProfile.RECEIVE,
        LocalStockStoreProfile.OPENING_SERIAL, LocalStockStoreProfile.RECEIVE_SERIAL, LocalStockStoreProfile.RECEIVE_PO -> {
            val request = receiptRequestForProducer(evidence)
            request.items.forEach { itemVersion(it.skuId, it.storageRevision) }
            val receipt = requiredBody(after, AggregateKey(LocalStockStoreProfile.RECEIPT_DOCUMENT, request.receiptId), StockReceiptWire.serializer())
            for (line in receipt.lines.filter { it.request.serialId != null && it.request.disposition != LocalReceiptDisposition.REFUSED.name }) {
                val position = sourcePosition(after, LocalStockStoreProfile.positionKey(receiptPosition(line.request.restore(), receipt.supplierId).inScope(evidence.scope)), evidence)
                localStockCheck(position.serialRegistrationId == line.serialRegistration?.registrationId,
                    LocalStockFailure.STORAGE_INCONSISTENT)
            }
            val owned = receipt.lines.filter { it.request.disposition == LocalReceiptDisposition.RELEASED.name ||
                it.request.disposition == LocalReceiptDisposition.QUARANTINE.name }.groupBy { it.request.allocationId }
            for ((allocationId, lines) in owned) {
                val key = AggregateKey(LocalStockStoreProfile.ALLOWANCE, allocationId)
                val unit = lines.first().baseQuantity
                val zero = Quantity(unit.unitId, 0, unit.scale)
                val original = previous[key]?.let { historicalBody(it, StockAllowanceWire.serializer()) } ?: StockAllowanceWire(
                    allocationId, request.basisId, receipt.supplierId, lines.first().request.skuId, lines.first().request.warehouseId,
                    zero, Money(Currency.EGP, 0), zero, zero)
                requireAllowance(original)
                if (evidence.command == LocalStockStoreProfile.RECEIVE_PO) {
                    val proof = receipt.poValuation ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
                    // The starting quantity/value is the EXACT retained predecessor, not a caller counter or stamp alone.
                    localStockCheck(proof.prefixes.singleOrNull { it.allocationId == allocationId } == StockPoReceiptPrefixWire(
                        allocationId, previous[key]?.row?.storageGeneration, original.committedQuantity, original.committedOwnedValue),
                        LocalStockFailure.STORAGE_INCONSISTENT)
                }
                localStockCheck(original.allocationId == allocationId && original.basisId == receipt.basisId && original.supplierId == receipt.supplierId &&
                    lines.all { it.request.skuId == original.skuId && it.request.warehouseId == original.warehouseId }, LocalStockFailure.STORAGE_INCONSISTENT)
                val added = lines.fold(zero) { total, line -> total + line.baseQuantity }
                val accepted = lines.filter { it.request.disposition == LocalReceiptDisposition.RELEASED.name }.fold(zero) { total, line -> total + line.baseQuantity }
                val value = lines.fold(Money(Currency.EGP, 0)) { total, line -> total + requireNotNull(line.request.originalValue) }
                val expected = original.copy(committedQuantity = original.committedQuantity + added, committedOwnedValue = original.committedOwnedValue + value,
                    acceptedQuantity = original.acceptedQuantity + accepted, pendingInspectionQuantity = original.pendingInspectionQuantity + added - accepted)
                localStockCheck(requiredBody(after, key, StockAllowanceWire.serializer()) == expected, LocalStockFailure.STORAGE_INCONSISTENT)
            }
            for (line in receipt.lines.filter { it.request.disposition != LocalReceiptDisposition.REFUSED.name }) {
                val source = line.request.restore()
                val key = LocalStockStoreProfile.positionKey(receiptPosition(source, receipt.supplierId).inScope(evidence.scope))
                val position = sourcePosition(after, key, evidence)
                localStockCheck(position.expiresAtEpochMillis == source.expiresAt?.value?.toEpochMilliseconds(), LocalStockFailure.STORAGE_INCONSISTENT)
                if (source.disposition == LocalReceiptDisposition.RELEASED) localStockCheck(position.safety == StockSafety.CLEAR &&
                    (position.expiresAtEpochMillis?.let { it > evidence.recordedAt.value.toEpochMilliseconds() } ?: true), LocalStockFailure.STORAGE_INCONSISTENT)
            }
        }
        LocalStockStoreProfile.INSPECT, LocalStockStoreProfile.INSPECT_SERIAL -> {
            val request = LocalStockCommandCodec.inspection(evidence.normalized)
            val receiptKey = AggregateKey(LocalStockStoreProfile.RECEIPT_DOCUMENT, request.receiptId)
            val original = requiredBody(previous, receiptKey, StockReceiptWire.serializer())
            val inspection = requiredBody(after, AggregateKey(LocalStockStoreProfile.INSPECTION, request.inspectionId), StockInspectionWire.serializer())
            localStockCheck(previous.getValue(receiptKey).row.storageGeneration == request.expectedReceiptRevision, LocalStockFailure.STORAGE_INCONSISTENT)
            val line = original.lines.singleOrNull { it.request.lineId == request.lineId } ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
            localStockCheck(line.request.disposition == LocalReceiptDisposition.QUARANTINE.name && request.quantity.unitId == line.baseQuantity.unitId &&
                request.quantity.scale == line.baseQuantity.scale && request.quantity <= line.baseQuantity - line.releasedFromQuarantine, LocalStockFailure.STORAGE_INCONSISTENT)
            itemVersion(line.request.skuId, request.expectedItemRevision)
            val expected = original.copy(lines = original.lines.map { if (it.request.lineId == request.lineId)
                it.copy(releasedFromQuarantine = it.releasedFromQuarantine + request.quantity) else it })
            localStockCheck(requiredBody(after, receiptKey, StockReceiptWire.serializer()) == expected, LocalStockFailure.STORAGE_INCONSISTENT)
            val allocationKey = AggregateKey(LocalStockStoreProfile.ALLOWANCE, line.request.allocationId)
            val allocation = requiredBody(previous, allocationKey, StockAllowanceWire.serializer())
            requireAllowance(allocation)
            localStockCheck(allocation.allocationId == line.request.allocationId && allocation.basisId == original.basisId && allocation.supplierId == original.supplierId &&
                allocation.skuId == line.request.skuId && allocation.warehouseId == line.request.warehouseId && allocation.pendingInspectionQuantity >= request.quantity,
                LocalStockFailure.STORAGE_INCONSISTENT)
            localStockCheck(requiredBody(after, allocationKey, StockAllowanceWire.serializer()) == allocation.copy(
                acceptedQuantity = allocation.acceptedQuantity + request.quantity, pendingInspectionQuantity = allocation.pendingInspectionQuantity - request.quantity),
                LocalStockFailure.STORAGE_INCONSISTENT)
            val positionKey = receiptPosition(line.request.restore(), original.supplierId).inScope(evidence.scope)
            val position = sourcePosition(observed, LocalStockStoreProfile.positionKey(positionKey), evidence)
            localStockCheck(position.safety == StockSafety.CLEAR && position.onHand >= request.quantity &&
                (position.expiresAtEpochMillis?.let { it > evidence.recordedAt.value.toEpochMilliseconds() } ?: true), LocalStockFailure.STORAGE_INCONSISTENT)
            val destinationKey = LocalStockStoreProfile.positionKey(positionKey.copy(custody = StockCustody.RELEASED))
            val destination = sourcePosition(after, destinationKey, evidence)
            localStockCheck(destination.safety == StockSafety.CLEAR && destination.expiresAtEpochMillis == position.expiresAtEpochMillis, LocalStockFailure.STORAGE_INCONSISTENT)
            if (request.serialId != null) {
                localStockCheck(line.request.serialId == request.serialId && request.quantity == stockSerialUnit(request.quantity),
                    LocalStockFailure.STORAGE_INCONSISTENT)
                val serialKey = LocalStockStoreProfile.serialKey(request.serialId)
                val serial = requiredBody(observed, serialKey, StockSerialWire.serializer())
                val registration = inspection.serialRegistration
                val qualifications = records?.serialRegistrations.orEmpty()
                if (registration == null) localStockCheck(serial.registration != null && serialKey !in after && qualifications.isEmpty(),
                    LocalStockFailure.STORAGE_INCONSISTENT)
                else {
                    val changed = requiredBody(after, serialKey, StockSerialWire.serializer())
                    localStockCheck(serial.registration == null && changed == serial.copy(registration = registration, registeredAt = evidence.recordedAt),
                        LocalStockFailure.STORAGE_INCONSISTENT)
                    val positions = observed.filterKeys { it.kind == LocalStockStoreProfile.POSITION }.map { (key, _) -> sourcePosition(observed, key, evidence) }
                        .filter { it.key.serialId == request.serialId }
                    localStockCheck(qualifications.map { it.first }.toSet() == positions.toSet() && qualifications.size == positions.size,
                        LocalStockFailure.STORAGE_INCONSISTENT)
                }
                localStockCheck(destination.serialRegistrationId == (registration ?: serial.registration)?.registrationId &&
                    destination.serialRegistrationId != null, LocalStockFailure.STORAGE_INCONSISTENT)
            } else localStockCheck(inspection.serialRegistration == null && records?.serialRegistrations.orEmpty().isEmpty(),
                LocalStockFailure.STORAGE_INCONSISTENT)
        }
        LocalStockStoreProfile.COUNT_CAPTURE, LocalStockStoreProfile.COUNT_CAPTURE_SERIAL -> {
            val request = LocalStockCommandCodec.countCapture(evidence.normalized)
            itemVersion(request.position.skuId, request.expectedItemRevision)
            val position = sourcePosition(observed, LocalStockStoreProfile.positionKey(request.position.inScope(evidence.scope)), evidence)
            val count = requiredBody(after, AggregateKey(LocalStockStoreProfile.COUNT_DOCUMENT, request.countId), StockCountWire.serializer())
            localStockCheck(count.capturedQuantity == position.onHand, LocalStockFailure.STORAGE_INCONSISTENT)
        }
        LocalStockStoreProfile.COUNT_POST, LocalStockStoreProfile.COUNT_POST_SERIAL -> {
            val request = LocalStockCommandCodec.countPost(evidence.normalized)
            val key = AggregateKey(LocalStockStoreProfile.COUNT_DOCUMENT, request.countId)
            val original = requiredBody(previous, key, StockCountWire.serializer())
            localStockCheck(request.serialId == original.position.serialId &&
                (request.serialId == null || request.counted <= original.capturedQuantity &&
                    (request.counted.scaled == 0L || request.counted == stockSerialUnit(request.counted))), LocalStockFailure.STORAGE_INCONSISTENT)
            localStockCheck(previous.getValue(key).row.storageGeneration == request.expectedCountRevision && original.counted == null,
                LocalStockFailure.STORAGE_INCONSISTENT)
            itemVersion(original.position.skuId, original.capturedItemRevision)
            val posted = requiredBody(after, key, StockCountWire.serializer())
            localStockCheck(posted == original.copy(counted = request.counted,
                postedBy = evidence.originalActorId, postedAt = evidence.recordedAt, postingReasonCode = request.reasonCode,
                surplusBasis = posted.surplusBasis), LocalStockFailure.STORAGE_INCONSISTENT)
            posted.surplusBasis?.let {
                requireCountSurplusBasis(it.restore(), evidence.scope, request.expectedCountRevision, original, request.counted, evidence.recordedAt)
            }
            val positionKey = original.position.key(evidence.scope)
            val position = sourcePosition(observed, LocalStockStoreProfile.positionKey(positionKey), evidence)
            localStockCheck(position.onHand == original.capturedQuantity, LocalStockFailure.STORAGE_INCONSISTENT)
            val facts = requireNotNull(records)
            val active = sourceReservations(observed, evidence).filter { it.retainsStockClaim && it.allocations.any { allocation -> allocation.position == positionKey } }
            val shortage = request.counted < position.onHand
            val needsSafety = shortage && active.isNotEmpty() && position.safety == StockSafety.CLEAR
            localStockCheck(facts.safety.size == if (needsSafety) 1 else 0, LocalStockFailure.STORAGE_INCONSISTENT)
            val held = if (shortage) active else emptyList()
            localStockCheck(facts.reservations.map { it.reservationId }.toSet() == held.map { it.reservationId }.toSet(), LocalStockFailure.STORAGE_INCONSISTENT)
            for (reservation in held) {
                val next = facts.reservations.single { it.reservationId == reservation.reservationId }
                requireHeldSuccessor(reservation, next, ReservationHold(ReservationHoldReason.COUNT_SHORTAGE, positionKey))
            }
        }
        LocalStockStoreProfile.RECALL, LocalStockStoreProfile.RECALL_SERIAL -> {
            val request = LocalStockCommandCodec.recall(evidence.normalized)
            itemVersion(request.skuId, request.expectedItemRevision)
            val positions = observed.filterKeys { it.kind == LocalStockStoreProfile.POSITION }.map { (key, _) -> sourcePosition(observed, key, evidence) }
                .filter { it.key.pool.skuId == request.skuId && it.key.lotId == request.lotId }
            localStockCheck(evidence.command == LocalStockStoreProfile.RECALL_SERIAL || positions.none { it.key.serialId != null },
                LocalStockFailure.STORAGE_INCONSISTENT)
            val facts = requireNotNull(records)
            localStockCheck(positions.isNotEmpty() && facts.safety.map { it.first }.toSet() == positions.toSet(), LocalStockFailure.STORAGE_INCONSISTENT)
            val active = sourceReservations(observed, evidence).filter { it.retainsStockClaim && it.allocations.any { allocation -> positions.any { it.key == allocation.position } } }
            val histories = facts.reservations.groupBy { it.reservationId }
            localStockCheck(histories.keys == active.map { it.reservationId }.toSet(), LocalStockFailure.STORAGE_INCONSISTENT)
            for (reservation in active) {
                val affected = facts.safety.map { it.second.key }.filter { position -> reservation.allocations.any { it.position == position } }
                val history = histories.getValue(reservation.reservationId)
                localStockCheck(history.size == affected.size, LocalStockFailure.STORAGE_INCONSISTENT)
                var before = reservation
                affected.zip(history).forEach { (position, next) ->
                    requireHeldSuccessor(before, next, ReservationHold(ReservationHoldReason.RECALL, position))
                    before = next
                }
            }
            val kinds = facts.safety.flatMap { (_, position) -> listOf(InventoryPostedEffectKind.SAFETY) +
                List(active.count { it.allocations.any { allocation -> allocation.position == position.key } }) { InventoryPostedEffectKind.RESERVATION } }
            localStockCheck(facts.effectKinds == kinds, LocalStockFailure.STORAGE_INCONSISTENT)
        }
        LocalStockStoreProfile.TRANSFER, LocalStockStoreProfile.TRANSFER_SERIAL -> {
            val request = LocalStockCommandCodec.transfer(evidence.normalized)
            itemVersion(request.source.skuId, request.expectedItemRevision)
            val position = sourcePosition(observed, LocalStockStoreProfile.positionKey(request.source.inScope(evidence.scope)), evidence)
            localStockCheck(position.onHand >= request.quantity, LocalStockFailure.STORAGE_INCONSISTENT)
            val destinationKey = LocalStockStoreProfile.positionKey(position.key.copy(pool = position.key.pool.copy(warehouseId = request.destinationWarehouseId)))
            val destination = sourcePosition(after, destinationKey, evidence)
            localStockCheck(destination.safety == position.safety && destination.expiresAtEpochMillis == position.expiresAtEpochMillis &&
                destination.serialRegistrationId == position.serialRegistrationId, LocalStockFailure.STORAGE_INCONSISTENT)
        }
        LocalStockStoreProfile.TRANSFER_OWNED -> {
            val request = LocalStockCommandCodec.transfer(evidence.normalized)
            itemVersion(request.source.skuId, request.expectedItemRevision)
            requireOwnedTransferSuccessors(evidence, observed, after, requireNotNull(records))
        }
        else -> throw LocalStockRejected(LocalStockFailure.PRODUCER_UNAVAILABLE)
    }
}

private fun requireHeldSuccessor(before: StockReservation, after: StockReservation, hold: ReservationHold) {
    localStockCheck(after.reservationId == before.reservationId && after.status == ReservationStatus.HELD && after.revision == before.revision.next() &&
        after.binding == before.binding && after.allocations == before.allocations && after.expiresAtEpochMillis == before.expiresAtEpochMillis &&
        after.holds == (before.holds + hold).distinct(), LocalStockFailure.STORAGE_INCONSISTENT)
}
private fun sourceReservations(rows: Map<AggregateKey, StockHistoricalRecord>, evidence: RetainedCommandEvidence): List<StockReservation> =
    rows.filterKeys { it.kind == LocalStockStoreProfile.RESERVATION }.map { (key, row) ->
        val wire = historicalBody(row, StockReservationWire.serializer())
        InventoryRecordCodec.decodeReservation(wire.encoded, InventoryScope(evidence.scope.companyId, evidence.scope.branchId), key.id, wire.revision)
    }
private fun sourcePosition(rows: Map<AggregateKey, StockHistoricalRecord>, key: AggregateKey, evidence: RetainedCommandEvidence): StockPosition {
    val value = requiredBody(rows, key, StockPositionWire.serializer())
    localStockCheck(LocalStockStoreProfile.positionKey(value.position.key(evidence.scope)) == key, LocalStockFailure.STORAGE_INCONSISTENT)
    return InventoryRecordCodec.decodePosition(value.encoded, value.position.key(evidence.scope))
}
private fun <T> requiredBody(rows: Map<AggregateKey, StockHistoricalRecord>, key: AggregateKey, serializer: KSerializer<T>): T =
    historicalBody(rows[key] ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT), serializer)
