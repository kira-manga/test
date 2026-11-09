package com.ukkera.pos.inventory.store

import com.ukkera.pos.catalog.CatalogVersionRef
import com.ukkera.pos.catalog.ProductUnitConversion
import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.ClaimKey
import com.ukkera.pos.database.RetainedClaimAction
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.inventory.InventoryPostedEffectKind
import com.ukkera.pos.inventory.InventoryPostedRecords
import com.ukkera.pos.inventory.ReceiptValueOrigin
import com.ukkera.pos.inventory.ReservationStatus
import com.ukkera.pos.inventory.StockCustody
import com.ukkera.pos.inventory.StockMovementReason
import com.ukkera.pos.inventory.StockOwnership
import com.ukkera.pos.inventory.StockSafety
import com.ukkera.pos.inventory.ValuationFact
import com.ukkera.pos.inventory.ValueIssuePurpose
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision

/** Closed stock-only producers. Exact documents/claims/effect shapes, not namespace allowlists. */
internal fun requireLocalStockProducer(evidence: RetainedCommandEvidence, records: InventoryPostedRecords?) {
    localStockCheck(evidence.command in stockCommands && LocalStockCommandCodec.normalize(evidence.command, evidence.normalized) == evidence.normalized &&
        evidence.jobs.isEmpty(), LocalStockFailure.PRODUCER_UNAVAILABLE)
    // These stock producers append physical facts only. A public acquisition-cost correction
    // producer, including propagated graph-only events, is a separately versioned future cut.
    localStockCheck(records?.valuations.orEmpty().all { it.linkedAdjustment?.changedSource == null &&
        it.linkedAdjustment?.transferCorrection == null }, LocalStockFailure.STORAGE_INCONSISTENT)
    val result = LocalStockCommandCodec.decodeResult(evidence.result)
    localStockCheck(result.failure == null && result.kind != LocalStockResultKind.REJECTED, LocalStockFailure.STORAGE_INCONSISTENT)
    val items = evidence.aggregateWrites.filter { it.key.kind == LocalStockStoreProfile.ITEM }.map { LocalStockItemVersion(it.key.id, it.storageGeneration) }
        .sortedBy { it.skuId.value }
    localStockCheck(result.items == items, LocalStockFailure.STORAGE_INCONSISTENT)
    val expectedDocuments = mutableSetOf<AggregateKey>()
    val expectedExtraClaims = linkedMapOf<ClaimKey, DomainId>()
    val permitted: Set<String>
    fun newDocument(kind: String, id: DomainId) {
        val key = AggregateKey(kind, id)
        expectedDocuments += key
        localStockCheck(evidence.aggregateWrites.singleOrNull { it.key == key }?.storageGeneration == Revision(1), LocalStockFailure.STORAGE_INCONSISTENT)
        expectedExtraClaims[LocalStockStoreProfile.documentClaim(id)] = id
    }
    when (evidence.command) {
        LocalStockStoreProfile.OPENING, LocalStockStoreProfile.RECEIVE,
        LocalStockStoreProfile.OPENING_SERIAL, LocalStockStoreProfile.RECEIVE_SERIAL, LocalStockStoreProfile.RECEIVE_PO -> {
            val request = receiptRequestForProducer(evidence)
            val opening = evidence.command in setOf(LocalStockStoreProfile.OPENING, LocalStockStoreProfile.OPENING_SERIAL)
            localStockCheck(result.documentId == request.receiptId && result.kind == if (opening) LocalStockResultKind.OPENED else LocalStockResultKind.RECEIVED,
                LocalStockFailure.STORAGE_INCONSISTENT)
            permitted = setOf(LocalStockStoreProfile.ITEM, LocalStockStoreProfile.POSITION, LocalStockStoreProfile.POOL,
                LocalStockStoreProfile.VALUE_PAGE, LocalStockStoreProfile.RECEIPT_DOCUMENT, LocalStockStoreProfile.ALLOWANCE,
                LocalStockStoreProfile.POSTING, LocalStockStoreProfile.SERIAL_ORIGIN)
            newDocument(LocalStockStoreProfile.RECEIPT_DOCUMENT, request.receiptId)
            request.lines.forEach { expectedExtraClaims[LocalStockStoreProfile.receiptLineClaim(it.sourceId)] = request.receiptId }
            val serialLines = request.lines.filter { it.serialId != null && it.disposition != LocalReceiptDisposition.REFUSED }
            localStockCheck(evidence.aggregateWrites.filter { it.key.kind == LocalStockStoreProfile.SERIAL_ORIGIN }.map { it.key.id }.toSet() ==
                serialLines.map { requireNotNull(it.serialId) }.toSet(), LocalStockFailure.STORAGE_INCONSISTENT)
            serialLines.forEach { line ->
                val id = requireNotNull(line.serialId)
                localStockCheck(evidence.aggregateWrites.single { it.key == LocalStockStoreProfile.serialKey(id) }.storageGeneration == Revision(1),
                    LocalStockFailure.STORAGE_INCONSISTENT)
                expectedExtraClaims[LocalStockStoreProfile.serialClaim(id)] = id
                expectedExtraClaims[LocalStockStoreProfile.serialCodeClaim(requireNotNull(line.serialCode))] = id
            }
            val owned = request.lines.filter { it.disposition == LocalReceiptDisposition.RELEASED || it.disposition == LocalReceiptDisposition.QUARANTINE }
            owned.map { it.allocationId }.distinct().forEach { id ->
                val key = AggregateKey(LocalStockStoreProfile.ALLOWANCE, id)
                expectedDocuments += key
                if (evidence.aggregateWrites.singleOrNull { it.key == key }?.storageGeneration == Revision(1))
                    expectedExtraClaims[LocalStockStoreProfile.allowanceClaim(id)] = id
                val allowance = retainedBody(evidence, key, StockAllowanceWire.serializer())
                requireAllowance(allowance)
                localStockCheck(allowance.allocationId == id, LocalStockFailure.STORAGE_INCONSISTENT)
            }
            if (opening) localStockCheck(request.items.all { it.storageRevision == null } && request.lines.all {
                it.disposition == LocalReceiptDisposition.RELEASED || it.disposition == LocalReceiptDisposition.QUARANTINE }, LocalStockFailure.STORAGE_INCONSISTENT)
            requireReceiptProducer(evidence, request, records)
        }
        LocalStockStoreProfile.COUNT_CAPTURE, LocalStockStoreProfile.COUNT_CAPTURE_SERIAL -> {
            val request = LocalStockCommandCodec.countCapture(evidence.normalized)
            localStockCheck(result.kind == LocalStockResultKind.COUNT_CAPTURED && result.documentId == request.countId && items.isEmpty() && records == null,
                LocalStockFailure.STORAGE_INCONSISTENT)
            permitted = setOf(LocalStockStoreProfile.COUNT_DOCUMENT, LocalStockStoreProfile.POSTING)
            newDocument(LocalStockStoreProfile.COUNT_DOCUMENT, request.countId)
            val count = retainedBody(evidence, AggregateKey(LocalStockStoreProfile.COUNT_DOCUMENT, request.countId), StockCountWire.serializer())
            localStockCheck(count.countId == request.countId && count.position == StockPositionRefWire.from(request.position) &&
                count.capturedItemRevision == request.expectedItemRevision && count.capturedQuantity.scaled >= 0 &&
                count.capturedBy == evidence.originalActorId && count.capturedAt == evidence.recordedAt && count.reasonCode == request.reasonCode &&
                count.counted == null && count.postedBy == null && count.postedAt == null && count.postingReasonCode == null &&
                count.surplusBasis == null, LocalStockFailure.STORAGE_INCONSISTENT)
        }
        LocalStockStoreProfile.COUNT_POST, LocalStockStoreProfile.COUNT_POST_SERIAL -> {
            val request = LocalStockCommandCodec.countPost(evidence.normalized)
            localStockCheck(result.kind == LocalStockResultKind.COUNT_POSTED && result.documentId == request.countId && records != null, LocalStockFailure.STORAGE_INCONSISTENT)
            permitted = setOf(LocalStockStoreProfile.COUNT_DOCUMENT, LocalStockStoreProfile.POSTING, LocalStockStoreProfile.ITEM,
                LocalStockStoreProfile.POSITION, LocalStockStoreProfile.POOL, LocalStockStoreProfile.VALUE_PAGE, LocalStockStoreProfile.RESERVATION)
            val key = AggregateKey(LocalStockStoreProfile.COUNT_DOCUMENT, request.countId)
            expectedDocuments += key
            localStockCheck(evidence.aggregateWrites.singleOrNull { it.key == key }?.storageGeneration == request.expectedCountRevision.next(), LocalStockFailure.STORAGE_INCONSISTENT)
            val count = retainedBody(evidence, key, StockCountWire.serializer())
            localStockCheck(count.countId == request.countId && count.counted == request.counted && count.postedBy == evidence.originalActorId && count.postedAt == evidence.recordedAt &&
                request.serialId == count.position.serialId &&
                count.postingReasonCode == request.reasonCode && count.capturedQuantity.unitId == request.counted.unitId &&
                count.capturedQuantity.scale == request.counted.scale, LocalStockFailure.STORAGE_INCONSISTENT)
            if (request.counted > count.capturedQuantity) {
                val wire = count.surplusBasis ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
                val basis = wire.restore()
                localStockCheck(basis.basisId == request.surplusBasisId && basis.revision == request.expectedSurplusBasisRevision &&
                    wire == StockCountSurplusBasisWire.from(basis), LocalStockFailure.STORAGE_INCONSISTENT)
                requireCountSurplusBasis(basis, evidence.scope, request.expectedCountRevision, count, request.counted, evidence.recordedAt)
                expectedExtraClaims[LocalStockStoreProfile.countSurplusClaim(basis.basisId)] = count.countId
            } else localStockCheck(request.surplusBasisId == null && count.surplusBasis == null, LocalStockFailure.STORAGE_INCONSISTENT)
            requireCountPosting(evidence, count, requireNotNull(records))
        }
        LocalStockStoreProfile.INSPECT, LocalStockStoreProfile.INSPECT_SERIAL -> {
            val request = LocalStockCommandCodec.inspection(evidence.normalized)
            localStockCheck(result.kind == LocalStockResultKind.INSPECTED && result.documentId == request.inspectionId && records != null, LocalStockFailure.STORAGE_INCONSISTENT)
            permitted = setOf(LocalStockStoreProfile.ITEM, LocalStockStoreProfile.POSITION, LocalStockStoreProfile.RECEIPT_DOCUMENT,
                LocalStockStoreProfile.ALLOWANCE, LocalStockStoreProfile.INSPECTION, LocalStockStoreProfile.POSTING, LocalStockStoreProfile.SERIAL_ORIGIN)
            newDocument(LocalStockStoreProfile.INSPECTION, request.inspectionId)
            val receiptKey = AggregateKey(LocalStockStoreProfile.RECEIPT_DOCUMENT, request.receiptId)
            expectedDocuments += receiptKey
            localStockCheck(evidence.aggregateWrites.singleOrNull { it.key == receiptKey }?.storageGeneration == request.expectedReceiptRevision.next(), LocalStockFailure.STORAGE_INCONSISTENT)
            val inspection = retainedBody(evidence, AggregateKey(LocalStockStoreProfile.INSPECTION, request.inspectionId), StockInspectionWire.serializer())
            localStockCheck(inspection.request == StockInspectionRequestWire.from(request) && inspection.inspectorId == evidence.originalActorId &&
                inspection.recordedAt == evidence.recordedAt, LocalStockFailure.STORAGE_INCONSISTENT)
            val receipt = retainedBody(evidence, receiptKey, StockReceiptWire.serializer())
            val line = receipt.lines.singleOrNull { it.request.lineId == request.lineId } ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
            localStockCheck(line.request.serialId == request.serialId, LocalStockFailure.STORAGE_INCONSISTENT)
            localStockCheck(line.request.disposition == LocalReceiptDisposition.QUARANTINE.name && line.releasedFromQuarantine >= request.quantity &&
                line.releasedFromQuarantine <= line.baseQuantity, LocalStockFailure.STORAGE_INCONSISTENT)
            expectedDocuments += AggregateKey(LocalStockStoreProfile.ALLOWANCE, line.request.allocationId)
            val source = receiptPosition(line.request.restore(), receipt.supplierId).inScope(evidence.scope)
            val facts = requireNotNull(records)
            localStockCheck(facts.effectKinds == List(facts.serialRegistrations.size) { InventoryPostedEffectKind.SERIAL_REGISTRATION } +
                listOf(InventoryPostedEffectKind.MOVEMENT, InventoryPostedEffectKind.MOVEMENT) && facts.movements.size == 2 &&
                facts.movements.all { it.sourceId == request.inspectionId && it.reason == StockMovementReason.QUARANTINE_RELEASE && it.sourceAllocationId == null } &&
                facts.movements[0].position == source && facts.movements[0].quantityDelta == -request.quantity &&
                facts.movements[1].position == source.copy(custody = StockCustody.RELEASED) && facts.movements[1].quantityDelta == request.quantity,
                LocalStockFailure.STORAGE_INCONSISTENT)
            val registration = inspection.serialRegistration
            val expectedSerialIds = registration?.let { setOf(it.serialId) }.orEmpty()
            localStockCheck(evidence.aggregateWrites.filter { it.key.kind == LocalStockStoreProfile.SERIAL_ORIGIN }.map { it.key.id }.toSet() ==
                expectedSerialIds, LocalStockFailure.STORAGE_INCONSISTENT)
            localStockCheck((registration == null) == facts.serialRegistrations.isEmpty(), LocalStockFailure.STORAGE_INCONSISTENT)
            registration?.let {
                val restored = it.restore()
                localStockCheck(it == StockSerialRegistrationWire.from(restored), LocalStockFailure.STORAGE_INCONSISTENT)
                requireSerialRegistration(restored, evidence.scope, requireNotNull(request.serialId), line.request.skuId,
                    requireNotNull(line.request.serialCode), evidence.recordedAt)
                localStockCheck(facts.serialRegistrations.all { change -> change.first.key.serialId == request.serialId &&
                    change.second.serialRegistrationId == it.registrationId }, LocalStockFailure.STORAGE_INCONSISTENT)
            }
        }
        LocalStockStoreProfile.RECALL, LocalStockStoreProfile.RECALL_SERIAL -> {
            val request = LocalStockCommandCodec.recall(evidence.normalized)
            localStockCheck(result.kind == LocalStockResultKind.RECALLED && result.documentId == request.lotId && records != null, LocalStockFailure.STORAGE_INCONSISTENT)
            permitted = setOf(LocalStockStoreProfile.ITEM, LocalStockStoreProfile.POSITION, LocalStockStoreProfile.RESERVATION, LocalStockStoreProfile.POSTING)
            val facts = requireNotNull(records)
            localStockCheck(facts.safety.isNotEmpty() && facts.effectKinds.all { it == InventoryPostedEffectKind.SAFETY || it == InventoryPostedEffectKind.RESERVATION } &&
                facts.effectKinds.first() == InventoryPostedEffectKind.SAFETY && facts.safety.all { (before, after) ->
                    before.key.pool.skuId == request.skuId && before.key.lotId == request.lotId && after == before.copy(safety = StockSafety.RECALLED) } &&
                facts.safety.map { it.second.key }.distinct().size == facts.safety.size && facts.reservations.all { it.status == ReservationStatus.HELD },
                LocalStockFailure.STORAGE_INCONSISTENT)
        }
        LocalStockStoreProfile.TRANSFER, LocalStockStoreProfile.TRANSFER_SERIAL -> {
            val request = LocalStockCommandCodec.transfer(evidence.normalized)
            localStockCheck(result.kind == LocalStockResultKind.TRANSFERRED && result.documentId == request.transferId && records != null &&
                request.source.supplierId != null, LocalStockFailure.STORAGE_INCONSISTENT)
            permitted = setOf(LocalStockStoreProfile.ITEM, LocalStockStoreProfile.POSITION, LocalStockStoreProfile.TRANSFER_DOCUMENT, LocalStockStoreProfile.POSTING)
            newDocument(LocalStockStoreProfile.TRANSFER_DOCUMENT, request.transferId)
            val transfer = retainedBody(evidence, AggregateKey(LocalStockStoreProfile.TRANSFER_DOCUMENT, request.transferId), StockTransferWire.serializer())
            localStockCheck(transfer.request == StockTransferRequestWire.from(request) && transfer.transferredBy == evidence.originalActorId &&
                transfer.recordedAt == evidence.recordedAt && transfer.originalValue == null && transfer.owned == null, LocalStockFailure.STORAGE_INCONSISTENT)
            val facts = requireNotNull(records)
            val source = request.source.inScope(evidence.scope)
            localStockCheck(facts.effectKinds == listOf(InventoryPostedEffectKind.MOVEMENT, InventoryPostedEffectKind.MOVEMENT) && facts.movements.size == 2 &&
                facts.movements[0].position == source && facts.movements[0].sourceId == request.sourceEventId && facts.movements[0].quantityDelta == -request.quantity &&
                facts.movements[1].position == source.copy(pool = source.pool.copy(warehouseId = request.destinationWarehouseId)) &&
                facts.movements[1].sourceId == request.destinationEventId && facts.movements[1].quantityDelta == request.quantity &&
                facts.movements.all { it.reason == StockMovementReason.WAREHOUSE_TRANSFER && it.sourceAllocationId == null }, LocalStockFailure.STORAGE_INCONSISTENT)
        }
        LocalStockStoreProfile.TRANSFER_OWNED -> {
            val request = LocalStockCommandCodec.transfer(evidence.normalized)
            localStockCheck(result.kind == LocalStockResultKind.TRANSFERRED && result.documentId == request.transferId && records != null,
                LocalStockFailure.STORAGE_INCONSISTENT)
            permitted = setOf(LocalStockStoreProfile.ITEM, LocalStockStoreProfile.POSITION, LocalStockStoreProfile.POOL,
                LocalStockStoreProfile.VALUE_PAGE, LocalStockStoreProfile.RESERVATION, LocalStockStoreProfile.TRANSFER_DOCUMENT, LocalStockStoreProfile.POSTING)
            newDocument(LocalStockStoreProfile.TRANSFER_DOCUMENT, request.transferId)
            val transfer = retainedBody(evidence, AggregateKey(LocalStockStoreProfile.TRANSFER_DOCUMENT, request.transferId), StockTransferWire.serializer())
            requireOwnedTransferProducer(evidence, request, transfer, requireNotNull(records))
        }
        else -> throw LocalStockRejected(LocalStockFailure.PRODUCER_UNAVAILABLE)
    }
    localStockCheck(evidence.aggregateWrites.all { it.key.kind in permitted } &&
        evidence.aggregateWrites.filter { it.key.kind !in LocalStockStoreProfile.inventoryKinds }.map { it.key }.toSet() == expectedDocuments,
        LocalStockFailure.STORAGE_INCONSISTENT)
    val extras = evidence.claimChanges.filter { it.key.namespace !in LocalStockStoreProfile.inventoryClaimNamespaces }
    localStockCheck(extras.size == expectedExtraClaims.size && extras.all { it.action == RetainedClaimAction.ACQUIRE && expectedExtraClaims[it.key] == it.ownerId },
        LocalStockFailure.STORAGE_INCONSISTENT)
    for (row in evidence.aggregateWrites.filter { it.key.kind !in setOf(LocalStockStoreProfile.VALUE_PAGE, LocalStockStoreProfile.POSTING) }) {
        val envelope = stockDecode(row.payload, StockRecordWire.serializer())
        requireRetainedStockEnvelope(evidence, row.key, row.storageGeneration, envelope)
    }
}

private fun requireReceiptProducer(evidence: RetainedCommandEvidence, request: LocalGoodsReceiptRequest, records: InventoryPostedRecords?) {
    val receipt = retainedBody(evidence, AggregateKey(LocalStockStoreProfile.RECEIPT_DOCUMENT, request.receiptId), StockReceiptWire.serializer())
    localStockCheck((receipt.poValuation != null) == (evidence.command == LocalStockStoreProfile.RECEIVE_PO), LocalStockFailure.STORAGE_INCONSISTENT)
    localStockCheck(receipt.receiptId == request.receiptId && receipt.basisId == request.basisId && receipt.basisRevision == request.expectedBasisRevision &&
        receipt.lines.map { it.request } == request.lines.map(StockReceiptLineWire::from) && receipt.recordedBy == evidence.originalActorId &&
        receipt.receivedAt == evidence.recordedAt && receipt.reasonCode == request.reasonCode && receipt.sources.isNotEmpty() &&
        receipt.sources.map { it.restore().key }.distinct().size == receipt.sources.size, LocalStockFailure.STORAGE_INCONSISTENT)
    val opening = evidence.command in setOf(LocalStockStoreProfile.OPENING, LocalStockStoreProfile.OPENING_SERIAL)
    localStockCheck((stockEnum<LocalStockBasisKind>(receipt.basisKind) == LocalStockBasisKind.OPENING) == opening && (receipt.supplierId == null) == opening,
        LocalStockFailure.STORAGE_INCONSISTENT)
    for (line in receipt.lines) {
        val input = line.request.restore()
        localStockCheck(line.baseQuantity.scaled > 0 && line.releasedFromQuarantine == Quantity(line.baseQuantity.unitId, 0, line.baseQuantity.scale), LocalStockFailure.STORAGE_INCONSISTENT)
        val converted = line.conversion?.let { conversion ->
            localStockCheck(input.conversion == CatalogVersionRef(conversion.id, conversion.revision) &&
                conversion.sourceUnitId == input.quantity.unitId && conversion.targetUnitId == line.baseQuantity.unitId, LocalStockFailure.STORAGE_INCONSISTENT)
            ProductUnitConversion(conversion.id, conversion.revision, conversion.productId, conversion.sourceUnitId,
                conversion.targetUnitId, conversion.rate, conversion.massVolumeEvidenceId).convertExact(input.quantity, line.baseQuantity.scale)
        } ?: input.quantity.rescaleExact(line.baseQuantity.scale)
        localStockCheck(converted == line.baseQuantity && (line.conversion != null || input.conversion == null), LocalStockFailure.STORAGE_INCONSISTENT)
        if (input.serialId != null) {
            localStockCheck(line.baseQuantity == stockSerialUnit(line.baseQuantity) &&
                (input.disposition != LocalReceiptDisposition.RELEASED || line.serialRegistration != null), LocalStockFailure.STORAGE_INCONSISTENT)
            line.serialRegistration?.let {
                localStockCheck(it == StockSerialRegistrationWire.from(it.restore()) && receipt.sources.containsAll(it.sources),
                    LocalStockFailure.STORAGE_INCONSISTENT)
                requireSerialRegistration(it.restore(), evidence.scope, input.serialId, input.skuId, requireNotNull(input.serialCode), evidence.recordedAt)
            }
            if (input.disposition != LocalReceiptDisposition.REFUSED) {
                val serial = retainedBody(evidence, LocalStockStoreProfile.serialKey(input.serialId), StockSerialWire.serializer())
                localStockCheck(serial == StockSerialWire(input.serialId, requireNotNull(input.serialCode), input.skuId, receipt.receiptId,
                    input.lineId, input.sourceId, StockPositionRefWire.from(receiptPosition(input, receipt.supplierId)), line.baseQuantity,
                    evidence.originalActorId, evidence.recordedAt, line.serialRegistration,
                    if (line.serialRegistration == null) null else evidence.recordedAt), LocalStockFailure.STORAGE_INCONSISTENT)
            } else localStockCheck(line.serialRegistration == null, LocalStockFailure.STORAGE_INCONSISTENT)
        } else localStockCheck(line.serialRegistration == null, LocalStockFailure.STORAGE_INCONSISTENT)
    }
    val arrived = receipt.lines.filter { it.request.disposition != LocalReceiptDisposition.REFUSED.name }
    val expectedKinds = arrived.flatMap { line -> if (line.request.disposition == LocalReceiptDisposition.REJECTED_HELD.name)
        listOf(InventoryPostedEffectKind.MOVEMENT) else listOf(InventoryPostedEffectKind.VALUATION, InventoryPostedEffectKind.MOVEMENT) }
    localStockCheck(records?.effectKinds.orEmpty() == expectedKinds && (records == null) == arrived.isEmpty() &&
        records?.movements.orEmpty().size == arrived.size, LocalStockFailure.STORAGE_INCONSISTENT)
    for ((index, line) in arrived.withIndex()) {
        val input = line.request.restore()
        val movement = requireNotNull(records).movements[index]
        val position = receiptPosition(input, receipt.supplierId).inScope(evidence.scope)
        localStockCheck(movement.sourceId == input.sourceId && movement.position == position && movement.quantityDelta == line.baseQuantity &&
            movement.sourceAllocationId == null && movement.reason == if (opening) StockMovementReason.OPENING else StockMovementReason.RECEIPT,
            LocalStockFailure.STORAGE_INCONSISTENT)
        val valuations = records.valuations.filter { it.fact?.factId == input.sourceId }
        if (input.disposition == LocalReceiptDisposition.REJECTED_HELD) localStockCheck(valuations.isEmpty(), LocalStockFailure.STORAGE_INCONSISTENT)
        else {
            localStockCheck(valuations.size == 1, LocalStockFailure.STORAGE_INCONSISTENT)
            val valuation = valuations.single()
            val fact = valuation.fact as? ValuationFact.Receipt ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
            localStockCheck(valuation.pool == position.pool && fact.quantity == line.baseQuantity && fact.originalValue == input.originalValue &&
                fact.origin == if (opening) ReceiptValueOrigin.OPENING else ReceiptValueOrigin.PURCHASE, LocalStockFailure.STORAGE_INCONSISTENT)
        }
    }
}

private fun requireCountPosting(evidence: RetainedCommandEvidence, count: StockCountWire, records: InventoryPostedRecords) {
    val counted = requireNotNull(count.counted)
    val shortage = count.capturedQuantity - counted
    val position = count.position.key(evidence.scope)
    localStockCheck(position.serialId == null || count.surplusBasis == null && shortage.scaled >= 0 &&
        (counted.scaled == 0L || counted == stockSerialUnit(counted)), LocalStockFailure.STORAGE_INCONSISTENT)
    localStockCheck(records.counts.size == 1 && records.counts.single().countId == count.countId && records.counts.single().position == position &&
        records.counts.single().counted == counted && records.counts.single().capturedRevision == Revision(0) &&
        records.safety.size <= 1 && records.reservations.all { it.status == ReservationStatus.HELD } &&
        records.reservations.map { it.reservationId }.distinct().size == records.reservations.size, LocalStockFailure.STORAGE_INCONSISTENT)
    val kinds = mutableListOf<InventoryPostedEffectKind>()
    if (shortage.scaled > 0) {
        if (position.pool.ownership == StockOwnership.CompanyOwned) {
            kinds += InventoryPostedEffectKind.VALUATION
            localStockCheck(records.valuations.size == 1, LocalStockFailure.STORAGE_INCONSISTENT)
            val valuation = records.valuations.single()
            val fact = valuation.fact as? ValuationFact.Issue ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
            localStockCheck(valuation.pool == position.pool && fact.factId == count.countId && fact.purpose == ValueIssuePurpose.COUNT_LOSS &&
                fact.slices.size == 1 && fact.slices.single().allocationId == count.countId && fact.slices.single().quantity == shortage,
                LocalStockFailure.STORAGE_INCONSISTENT)
        }
        kinds += InventoryPostedEffectKind.MOVEMENT
        localStockCheck(records.movements.size == 1 && records.movements.single().sourceId == count.countId &&
            records.movements.single().position == position && records.movements.single().quantityDelta == -shortage &&
            records.movements.single().reason == StockMovementReason.COUNT_SHORTAGE && records.movements.single().sourceAllocationId == null,
            LocalStockFailure.STORAGE_INCONSISTENT)
        if (records.safety.isNotEmpty()) {
            kinds += InventoryPostedEffectKind.SAFETY
            val (before, after) = records.safety.single()
            localStockCheck(before.key == position && before.onHand == count.capturedQuantity && before.safety == StockSafety.CLEAR &&
                after == before.copy(onHand = counted, safety = StockSafety.COUNT_REVIEW), LocalStockFailure.STORAGE_INCONSISTENT)
        }
        repeat(records.reservations.size) { kinds += InventoryPostedEffectKind.RESERVATION }
    } else if (shortage.scaled < 0) {
        val approval = count.surplusBasis ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
        val gain = -shortage
        localStockCheck(position.pool.ownership == StockOwnership.CompanyOwned && records.valuations.size == 1 &&
            records.movements.size == 1 && records.safety.isEmpty() && records.reservations.isEmpty(), LocalStockFailure.STORAGE_INCONSISTENT)
        val value = records.valuations.single()
        val fact = value.fact as? ValuationFact.Receipt ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
        localStockCheck(value.pool == position.pool && fact.factId == count.countId && fact.quantity == gain &&
            fact.originalValue == approval.surplusValue && fact.origin == ReceiptValueOrigin.COUNT_SURPLUS,
            LocalStockFailure.STORAGE_INCONSISTENT)
        val movement = records.movements.single()
        localStockCheck(movement.sourceId == count.countId && movement.position == position && movement.quantityDelta == gain &&
            movement.reason == StockMovementReason.COUNT_SURPLUS && movement.sourceAllocationId == null, LocalStockFailure.STORAGE_INCONSISTENT)
        kinds += InventoryPostedEffectKind.VALUATION
        kinds += InventoryPostedEffectKind.MOVEMENT
    }
    kinds += InventoryPostedEffectKind.COUNT
    localStockCheck(records.effectKinds == kinds, LocalStockFailure.STORAGE_INCONSISTENT)
}
