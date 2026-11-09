package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.types.*
import kotlinx.serialization.Serializable

@Serializable internal data class StockInspectionRequestV2Wire(val action: Int, val operationId: OperationId,
    val request: StockInspectionRequestWire) {
    fun restore(): LocalStockRelationalCommand.Inspect {
        localStockCheck(action == 9); return LocalStockRelationalCommand.Inspect(operationId, request.restore())
    }
}
internal const val STOCK_INSPECTION_EFFECT_V2: String = "local.stock.v2.inspection"

private suspend fun inspectionSerial(read: StockReceiptAccess, request: LocalInspectionReleaseRequest): StockSerialWire? {
    val serialId = request.serialId ?: return null
    val row = read.document(LocalStockStoreProfile.serialKey(serialId)) ?: inconsistentStock()
    val serial = receiptDocument(row, read.scope, StockSerialWire.serializer()).value
    localStockCheck(serial.serialId == serialId && read.claim(LocalStockStoreProfile.serialClaim(serialId)) == serialId &&
        read.claim(LocalStockStoreProfile.serialCodeClaim(serial.serialCode)) == serialId)
    return serial
}

/** One actual custody transfer, with receipt entitlement and PO accepted/pending quantities in
 * the same business commit. It never receives new units or applies the purchase basis twice. */
private suspend fun inspectionPlan(command: LocalStockRelationalCommand.Inspect, read: StockReceiptAccess,
    registration: LocalStockSerialRegistration?): StockReceiptPlan {
    localStockCheck(command.operationId == read.operationId)
    val request = command.request
    val activation = read.normal(LocalStockRelationalProfileV2.activationKey(read.scope.branchId)) ?: unavailableProducer()
    localStockCheck(activation.body is StockRelationalBody.Activation && stockActivationShape(activation.body))
    val inspectionKey = AggregateKey(LocalStockStoreProfile.INSPECTION, request.inspectionId)
    localStockCheck(read.document(inspectionKey) == null && read.claim(LocalStockStoreProfile.documentClaim(request.inspectionId)) == null &&
        read.claim(LocalStockStoreProfile.sourceClaim(request.inspectionId)) == null, LocalStockFailure.SOURCE_ALREADY_USED)
    val receiptKey = AggregateKey(LocalStockStoreProfile.RECEIPT_DOCUMENT, request.receiptId)
    val receiptRow = read.document(receiptKey) ?: throw LocalStockRejected(LocalStockFailure.ORIGINAL_RECEIPT_REQUIRED)
    localStockCheck(read.claim(LocalStockStoreProfile.documentClaim(request.receiptId)) == request.receiptId &&
        receiptRow.revision == request.expectedReceiptRevision, LocalStockFailure.STALE_REVISION)
    val receipt = receiptDocument(receiptRow, read.scope, StockReceiptWire.serializer()).value
    val line = receipt.lines.singleOrNull { it.request.lineId == request.lineId } ?: throw LocalStockRejected(LocalStockFailure.ORIGINAL_RECEIPT_REQUIRED)
    localStockCheck(line.request.disposition == LocalReceiptDisposition.QUARANTINE.name && request.serialId == line.request.serialId &&
        request.quantity.scaled > 0 && request.quantity.unitId == line.baseQuantity.unitId && request.quantity.scale == line.baseQuantity.scale &&
        request.quantity <= line.baseQuantity - line.releasedFromQuarantine, LocalStockFailure.INVALID_QUANTITY)
    val itemRow = read.normal(LocalStockRelationalProfileV2.itemKey(line.request.skuId)) ?: inconsistentStock()
    localStockCheck(itemRow.generation == request.expectedItemRevision && itemRow.body is StockRelationalBody.Item &&
        read.claim(LocalStockStoreProfile.itemClaim(line.request.skuId)) == line.request.skuId, LocalStockFailure.STALE_REVISION)
    val allowanceKey = AggregateKey(LocalStockStoreProfile.ALLOWANCE, line.request.allocationId)
    val allowanceRow = read.document(allowanceKey) ?: inconsistentStock()
    val allowance = receiptDocument(allowanceRow, read.scope, StockAllowanceWire.serializer()).value
    requireAllowance(allowance)
    localStockCheck(read.claim(LocalStockStoreProfile.allowanceClaim(line.request.allocationId)) == line.request.allocationId &&
        allowance.allocationId == line.request.allocationId && allowance.basisId == receipt.basisId && allowance.supplierId == receipt.supplierId &&
        allowance.skuId == line.request.skuId && allowance.warehouseId == line.request.warehouseId && allowance.pendingInspectionQuantity >= request.quantity)
    val sourceKey = receiptPosition(line.request.restore(), receipt.supplierId).inScope(read.scope)
    localStockCheck(sourceKey.custody == StockCustody.QUARANTINE && sourceKey.pool.ownership == StockOwnership.CompanyOwned)
    val source = (read.normal(LocalStockRelationalProfileV2.positionKey(sourceKey))?.body as? StockRelationalBody.Position)?.value ?: inconsistentStock()
    val destinationKey = sourceKey.copy(custody = StockCustody.RELEASED)
    val serial = inspectionSerial(read, request)
    localStockCheck(registration == null || serial?.registration == null)
    if (registration != null) {
        val originalSerial = serial ?: inconsistentStock()
        requireSerialRegistration(registration, read.scope, originalSerial.serialId, originalSerial.skuId, originalSerial.serialCode, read.now)
        for (stamp in registration.sources) read.current(stamp)
    }
    val registrationId = serial?.registration?.registrationId ?: registration?.registrationId
    localStockCheck(sourceKey.serialId == null || registrationId != null && request.quantity == stockSerialUnit(request.quantity),
        LocalStockFailure.SERIAL_REGISTRATION_REQUIRED)
    val destination = (read.normal(LocalStockRelationalProfileV2.positionKey(destinationKey))?.body as? StockRelationalBody.Position)?.value
        ?: source.copy(key = destinationKey, onHand = stockZeroQuantity(source.onHand), serialRegistrationId = registrationId)
    localStockCheck(source.safety == StockSafety.CLEAR && destination.safety == StockSafety.CLEAR && source.onHand >= request.quantity &&
        destination.expiresAtEpochMillis == source.expiresAtEpochMillis && (source.expiresAtEpochMillis?.let { read.now.value.toEpochMilliseconds() < it } ?: true),
        LocalStockFailure.UNSAFE_STOCK)
    read.range(StockQueryV2.SkuPositions(line.request.skuId)) { row ->
        val position = (row.body as? StockRelationalBody.Position)?.value ?: inconsistentStock()
        if (position.key.lotId == sourceKey.lotId) localStockCheck(position.expiresAtEpochMillis == source.expiresAtEpochMillis &&
            position.safety != StockSafety.RECALLED && position.safety != StockSafety.EXPIRED, LocalStockFailure.UNSAFE_STOCK)
    }
    val pool = (read.normal(LocalStockRelationalProfileV2.poolKey(sourceKey.pool))?.body as? StockRelationalBody.Pool)?.value ?: inconsistentStock()
    requireReceiptPoolBefore(read, sourceKey.pool, pool, pool.onHand)
    val records = linkedMapOf<AggregateKey, StockRelationalBody>()
    for (body in listOf(itemRow.body, StockRelationalBody.Position(source.copy(onHand = source.onHand - request.quantity, serialRegistrationId = registrationId)),
        StockRelationalBody.Position(destination.copy(onHand = destination.onHand + request.quantity, serialRegistrationId = registrationId)))) records[stockKey(body)] = body
    val updated = receipt.copy(lines = receipt.lines.map { if (it.request.lineId == request.lineId)
        it.copy(releasedFromQuarantine = it.releasedFromQuarantine + request.quantity) else it })
    val nextAllowance = allowance.copy(acceptedQuantity = allowance.acceptedQuantity + request.quantity,
        pendingInspectionQuantity = allowance.pendingInspectionQuantity - request.quantity)
    val documents = mutableListOf(ReceiptDocumentDraft(receiptKey, receiptRow, stockBody(updated, StockReceiptWire.serializer())),
        ReceiptDocumentDraft(allowanceKey, allowanceRow, stockBody(nextAllowance, StockAllowanceWire.serializer())),
        ReceiptDocumentDraft(inspectionKey, null, stockBody(StockInspectionWire(StockInspectionRequestWire.from(request), read.actorId,
            read.now, registration?.let(StockSerialRegistrationWire::from)), StockInspectionWire.serializer())))
    if (registration != null) {
        val value = checkNotNull(serial)
        val key = LocalStockStoreProfile.serialKey(value.serialId)
        documents += ReceiptDocumentDraft(key, read.document(key), stockBody(value.copy(registration = StockSerialRegistrationWire.from(registration),
            registeredAt = read.now), StockSerialWire.serializer()))
    }
    val resources = setOf(RecoveryResourceKey(RecoveryResourceKind.STOCK, request.inspectionId),
        RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, request.receiptId), RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, request.inspectionId),
        RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, request.evidenceId), RecoveryResourceKey(RecoveryResourceKind.PO_ALLOWANCE, receipt.basisId),
        RecoveryResourceKey(RecoveryResourceKind.PO_ALLOWANCE, line.request.allocationId)) +
        listOf(receipt.approvedBy, receipt.policyId, receipt.recordedBy).map { RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it) } +
        registration?.let(::serialRegistrationResources).orEmpty()
    val deadline = listOfNotNull(registration?.validUntil, source.expiresAtEpochMillis?.let { UtcInstant(kotlin.time.Instant.fromEpochMilliseconds(it)) }).minOrNull()
    return StockReceiptPlan(records, documents, mapOf(LocalStockStoreProfile.documentClaim(request.inspectionId) to request.inspectionId,
        LocalStockStoreProfile.sourceClaim(request.inspectionId) to request.inspectionId), resources,
        stockResult(LocalStockResultKind.INSPECTED, request.inspectionId, listOf(LocalStockItemVersion(line.request.skuId, itemRow.generation.next()))), deadline)
}

internal suspend fun inspectStock(repository: LocalStockRepository, authority: LocalStockAuthorityReader, view: CommandReadView,
    context: CommandContext, sink: StreamedCommandSink, now: UtcInstant, request: LocalStockRelationalCommand.Inspect): StreamedMutationDecision.Apply {
    val read = repository.receivingRelationalRead(view, context, sink)
    val access = CurrentReceiptAccess(repository, read, context, now)
    val serial = inspectionSerial(access, request.request)
    val registration = serial?.takeIf { it.registration == null }?.let { repository.receivingAuthority(authority).serialRegistration(view, it.serialId) }
    val plan = inspectionPlan(request, access, registration)
    val work = StockPostingBuilder(read, context, sink)
    for (body in plan.records.values) work.put(body)
    for ((key, owner) in plan.claims) work.acquire(key, owner)
    for (document in plan.documents) sink.change(AggregateChange(document.key, document.before?.revision, stockSchema(document.key.kind, document.body),
        stockRecord(context.scope, document.key, document.before?.revision, StockProducerWire.from(context), document.body, document.before)))
    sink.effect(StreamedEffectV2(STOCK_INSPECTION_EFFECT_V2, request.request.inspectionId, LocalStockRelationalCommandsV2.encode(request)))
    for (resource in plan.companionResources) sink.resource(resource)
    return work.finish(plan.result, plan.deadline)
}

internal suspend fun requireInspectionStockProducer(original: RetainedStreamedCommand, stock: LocalStockRelationalInspection,
    authority: LocalStockRelationalAuthorityReader, request: LocalStockRelationalCommand.Inspect) {
    localStockCheck(original.identity.producer.command == LocalStockRelationalProfileV2.COMMAND && original.normalized == LocalStockRelationalCommandsV2.encode(request))
    val serial = inspectionSerial(RetainedReceiptAccess(stock), request.request)
    val registration = serial?.takeIf { it.registration == null }?.let { authority.retainedSerialRegistration(original, it.serialId) }
    val plan = inspectionPlan(request, RetainedReceiptAccess(stock), registration)
    requireScalarStockPlan(original, stock, plan, STOCK_INSPECTION_EFFECT_V2, request.request.inspectionId)
    original.frames { frame ->
        val actual = (frame as? RetainedCommandFrameV2.Source)?.value ?: return@frames
        var used = false
        inspectionPlan(request, RetainedReceiptAccess(stock) { if (it == actual) used = true }, registration)
        localStockCheck(used)
    }
    stock.requireResources(plan.companionResources)
}

/** SAME deterministic plan and literal registration selection as the admitted inspection producer. */
internal suspend fun retainedInspectionStockDeadline(stock: LocalStockRelationalInspection,
    authority: LocalStockRelationalAuthorityReader, request: LocalStockRelationalCommand.Inspect): UtcInstant? {
    val serial = inspectionSerial(RetainedReceiptAccess(stock), request.request)
    val registration = serial?.takeIf { it.registration == null }?.let { authority.retainedSerialRegistration(stock.original, it.serialId) }
    return inspectionPlan(request, RetainedReceiptAccess(stock), registration).deadline
}
