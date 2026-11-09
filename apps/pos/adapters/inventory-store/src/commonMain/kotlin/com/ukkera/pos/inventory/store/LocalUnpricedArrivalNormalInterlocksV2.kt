package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.StockPosition
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*
import kotlinx.serialization.KSerializer

/** Actual normal-stock identity use only; no receipt, value or custody is admitted here. */
internal data class ArrivalNormalInterlockV2(val used: Boolean, val resources: Set<RecoveryResourceKey>)

private val arrivalNormalValueKindsV2 = setOf(StockRelationalKindsV2.VALUE_EVENT, StockRelationalKindsV2.VALUE_SLICE,
    StockRelationalKindsV2.VALUE_TARGET, StockRelationalKindsV2.TRANSFER_EDGE, StockRelationalKindsV2.ISSUE_SLICE)

/** These are genuine original DATA, not a recreated RetainedOriginal capability. */
private sealed class ArrivalNormalOriginalV2 {
    abstract val producer: StreamedProducerIdentityV2
    abstract suspend fun acquire(key: ClaimKey, owner: DomainId): Boolean
    abstract suspend fun document(key: AggregateKey): ArrivalNormalOriginalDocumentV2

    class Legacy(val evidence: RetainedCommandEvidence) : ArrivalNormalOriginalV2() {
        override val producer = StreamedProducerIdentityV2(evidence.operationId, evidence.command, evidence.originalActorId, evidence.semanticDigest)
        override suspend fun acquire(key: ClaimKey, owner: DomainId): Boolean {
            val claims = evidence.claimChanges.filter { it.key == key }
            if (claims.isEmpty()) return false
            check(claims.size == 1 && claims.single().action == RetainedClaimAction.ACQUIRE && claims.single().ownerId == owner)
            return true
        }
        override suspend fun document(key: AggregateKey): ArrivalNormalOriginalDocumentV2 {
            val row = evidence.aggregateWrites.singleOrNull { it.key == key } ?: inconsistentStock()
            check(sha256(row.payload.copyBytes()) == row.payloadDigest)
            val envelope = stockDecode(row.payload, StockRecordWire.serializer())
            requireRetainedStockEnvelope(evidence, key, row.storageGeneration, envelope)
            return ArrivalNormalOriginalDocumentV2(envelope, row.payloadDigest)
        }
    }

    class Streamed(val evidence: RetainedStreamedCommand) : ArrivalNormalOriginalV2() {
        override val producer get() = evidence.identity.producer
        private val acquisitions = mutableMapOf<ClaimKey, DomainId?>()
        override suspend fun acquire(key: ClaimKey, owner: DomainId): Boolean {
            if (key !in acquisitions) {
                var actual: DomainId? = null
                evidence.frames { frame ->
                    val claim = (frame as? RetainedCommandFrameV2.Claim)?.value ?: return@frames
                    if (claim.key == key) {
                        check(actual == null && claim.action == RetainedClaimAction.ACQUIRE)
                        actual = claim.ownerId
                    }
                }
                acquisitions[key] = actual
            }
            val actual = acquisitions[key] ?: return false
            check(actual == owner)
            return true
        }
        override suspend fun document(key: AggregateKey): ArrivalNormalOriginalDocumentV2 {
            val row = evidence.writeRow(key) ?: inconsistentStock()
            val envelope = stockRecord(row, evidence.identity.scope)
            check(envelope.producer.normalProducerV2() == producer)
            requireOriginalWrite(evidence, key, row.revision, row.schema, row.payload, row.digest)
            return ArrivalNormalOriginalDocumentV2(envelope, row.digest)
        }
    }
}

private data class ArrivalNormalOriginalDocumentV2(val envelope: StockRecordWire, val digest: PayloadDigest)
private data class ArrivalNormalDocumentV2<T>(val envelope: StockRecordWire, val value: T, val original: ArrivalNormalOriginalV2)
private fun StockProducerWire.normalProducerV2() = StreamedProducerIdentityV2(operationId, commandKey(), actorId, PayloadDigest(digest))
private fun normalOriginalV2(original: RetainedOriginalV2): ArrivalNormalOriginalV2 = when (original) {
    is RetainedOriginalV2.Legacy -> ArrivalNormalOriginalV2.Legacy(original.evidence)
    is RetainedOriginalV2.Streamed -> ArrivalNormalOriginalV2.Streamed(original.evidence)
}

/** Closed observation implementations. Native never calls a live stock/view factory. */
private sealed interface ArrivalNormalObservationV2 {
    val scope: BranchScope
    suspend fun claim(key: ClaimKey): DomainId?
    suspend fun original(id: OperationId): ArrivalNormalOriginalV2
    suspend fun <T> document(key: AggregateKey, serializer: KSerializer<T>): ArrivalNormalDocumentV2<T>?
    suspend fun serial(id: DomainId): ArrivalNormalDocumentV2<StockSerialWire>? =
        document(LocalStockStoreProfile.serialKey(id), StockSerialWire.serializer())
    suspend fun values(sourceId: DomainId, consume: suspend (StockRelationalRecordV2, ArrivalNormalOriginalV2) -> Unit)
}

private class ArrivalNormalCurrentV2(private val stock: LocalStockRepository, private val view: CommandReadView,
    private val relational: Boolean? = null, private val read: LocalStockRelationalRead? = null) : ArrivalNormalObservationV2 {
    override val scope get() = view.scope
    private val originals = linkedMapOf<OperationId, ArrivalNormalOriginalV2>()
    private val visiting = mutableSetOf<OperationId>()
    private val budget = StockLoadBudget()
    private val purchases = mutableMapOf<OperationId, LocalStockApprovedPurchaseOrderV1>()

    override suspend fun claim(key: ClaimKey): DomainId? = view.claim(key)
    private suspend fun legacy(id: OperationId): ArrivalNormalOriginalV2.Legacy {
        originals[id]?.let { return it as? ArrivalNormalOriginalV2.Legacy ?: inconsistentStock() }
        check(visiting.add(id))
        try {
            val evidence = view.retainedCommand(id) ?: unavailableProducer()
            check(evidence.scope == scope && evidence.operationId == id && evidence.committed)
            budget.producer(evidence)
            stock.requireProducer(view, evidence, budget, purchases)
            requireStockSourceCrosswalk(view, evidence, budget) { predecessor -> legacy(predecessor).evidence }
            return ArrivalNormalOriginalV2.Legacy(evidence).also { originals[id] = it }
        } finally { visiting.remove(id) }
    }
    private suspend fun streamed(id: OperationId): ArrivalNormalOriginalV2.Streamed {
        originals[id]?.let { return it as? ArrivalNormalOriginalV2.Streamed ?: inconsistentStock() }
        val evidence = (view.requireStockRelational().originals.read(id) as? RetainedOriginalV2.Streamed)?.evidence ?: unavailableProducer()
        check(evidence.identity.scope == scope && evidence.identity.producer.operationId == id)
        stock.requireRelationalProducer(view, evidence)
        return ArrivalNormalOriginalV2.Streamed(evidence).also { originals[id] = it }
    }
    override suspend fun original(id: OperationId): ArrivalNormalOriginalV2 {
        originals[id]?.let { return it }
        if (relational == false) return legacy(id)
        return when (view.requireStockRelational().originals.read(id) ?: unavailableProducer()) {
            is RetainedOriginalV2.Legacy -> legacy(id)
            is RetainedOriginalV2.Streamed -> streamed(id)
        }
    }
    override suspend fun <T> document(key: AggregateKey, serializer: KSerializer<T>): ArrivalNormalDocumentV2<T>? {
        val row = stock.record(view, key, serializer) ?: return null
        return qualifiedDocument(row)
    }
    override suspend fun serial(id: DomainId): ArrivalNormalDocumentV2<StockSerialWire>? {
        val row = stockSerial(stock, view, id) ?: return null
        return qualifiedDocument(row)
    }
    private suspend fun <T> qualifiedDocument(row: LocalStockRecord<T>): ArrivalNormalDocumentV2<T> {
        budget.row(row.row.payload.size)
        val original = if (row.envelope.producer.commandKey() == LocalStockRelationalProfileV2.COMMAND)
            streamed(row.envelope.producer.operationId) else legacy(row.envelope.producer.operationId)
        check(original.producer == row.envelope.producer.normalProducerV2())
        return ArrivalNormalDocumentV2(row.envelope, row.value, original)
    }
    override suspend fun values(sourceId: DomainId,
        consume: suspend (StockRelationalRecordV2, ArrivalNormalOriginalV2) -> Unit) {
        val selected = checkNotNull(read)
        selected.query(StockQueryV2.SourceValues(sourceId)) { index ->
            val row = selected.record(index.identity.key) ?: inconsistentStock()
            budget.row(row.payload.size)
            check(row.key.kind in arrivalNormalValueKindsV2 && stockIndexOf(scope, row) == index &&
                (index as? StockIndexRecordV2.Value)?.sourceId == sourceId)
            consume(row, streamed(row.producer.operationId))
        }
    }
}

private class ArrivalNormalNativeV2(private val command: RuntimeHistoricalCommand) : ArrivalNormalObservationV2 {
    override val scope get() = command.scope
    private val originals = linkedMapOf<OperationId, ArrivalNormalOriginalV2>()
    private val budget = StockLoadBudget()
    override suspend fun claim(key: ClaimKey): DomainId? = command.claim(key)
    override suspend fun original(id: OperationId): ArrivalNormalOriginalV2 = originals[id] ?: run {
        val actual = command.earlierOriginal(id) ?: unavailableProducer()
        normalOriginalV2(actual).also { originals[id] = it }
    }
    override suspend fun <T> document(key: AggregateKey, serializer: KSerializer<T>): ArrivalNormalDocumentV2<T>? {
        val source = command.aggregateSource(key) ?: return null
        budget.row(source.row.payload.size)
        val envelope = stockRecord(source.row, scope)
        requireArrivalNativeStockSourceV2(command, source, StockObservedWire(key.kind, key.id, envelope.producer.operationId,
            source.row.revision, source.row.digest.value))
        val original = normalOriginalV2(source.original)
        check(original.producer == envelope.producer.normalProducerV2())
        originals[original.producer.operationId] = original
        return ArrivalNormalDocumentV2(envelope, stockBody(envelope.body, serializer), original)
    }
    override suspend fun values(sourceId: DomainId,
        consume: suspend (StockRelationalRecordV2, ArrivalNormalOriginalV2) -> Unit) {
        // Complete native membership, not a caller-selected range prefix or current index.
        for (kind in arrivalNormalValueKindsV2.sorted()) for (stored in command.aggregates(kind)) {
            val row = LocalStockRelationalCodecV2.decode(scope, stored)
            val index = stockIndexOf(scope, row) as? StockIndexRecordV2.Value ?: inconsistentStock()
            if (index.sourceId != sourceId) continue
            val source = command.aggregateSource(stored.key) ?: inconsistentStock()
            check(source.row.key == stored.key && source.row.revision == stored.revision && source.row.schema == stored.schema &&
                source.row.payload == stored.payload && source.row.digest == stored.digest)
            budget.row(stored.payload.size)
            requireArrivalNativeStockSourceV2(command, source, StockObservedWire(row.key.kind, row.key.id,
                row.producer.operationId, row.generation, row.digest.value))
            val original = normalOriginalV2(source.original)
            check(original.producer == row.producer)
            originals[original.producer.operationId] = original
            consume(row, original)
        }
    }
}

private fun arrivalNormalProducerResourcesV2(producer: StreamedProducerIdentityV2): Set<RecoveryResourceKey> = setOf(
    RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, producer.originalActorId),
    RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, DomainId(producer.operationId.value)),
)

private fun arrivalNormalSourceResourcesV2(sourceId: DomainId, owner: DomainId?): Set<RecoveryResourceKey> = buildSet {
    for (id in listOfNotNull(sourceId, owner)) {
        add(RecoveryResourceKey(RecoveryResourceKind.STOCK, id))
        add(RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, id))
    }
}

/** Owner->operation is legal ONLY after the actual LEGACY estate has been confirmed.
 * Legacy projectLocalStock owns its source claims with the producing operation's ID. */
private suspend fun arrivalNormalSourcesV2(observation: ArrivalNormalObservationV2, relational: Boolean,
    sourceIds: List<DomainId>, profileResources: Set<RecoveryResourceKey>): ArrivalNormalInterlockV2 {
    require(sourceIds.isNotEmpty() && sourceIds.size <= LocalStockStoreProfile.MAX_LINES_PER_COMMAND)
    val resources = profileResources.toMutableSet()
    var used = false
    for (sourceId in sourceIds.distinct().sortedBy { it.value }) {
        val claimKey = LocalStockStoreProfile.sourceClaim(sourceId)
        val owner = observation.claim(claimKey)
        resources += arrivalNormalSourceResourcesV2(sourceId, owner)
        if (!relational) {
            if (owner != null) {
                val original = observation.original(OperationId(owner.value)) as? ArrivalNormalOriginalV2.Legacy ?: inconsistentStock()
                check(original.producer.operationId.value == owner.value && original.acquire(claimKey, owner))
                resources += arrivalNormalProducerResourcesV2(original.producer)
                original.evidence.aggregateWrites.filter { it.key.kind in LocalStockStoreProfile.inventoryKinds }.forEach {
                    resources += RecoveryResourceKey(RecoveryResourceKind.STOCK, it.key.id)
                }
                used = true
            }
            continue
        }
        var events = 0
        var otherValues = 0
        var transferEdges = 0
        observation.values(sourceId) { row, original ->
            resources += arrivalNormalProducerResourcesV2(original.producer)
            stockResourceAliases(row.key, row.body) { resources += it }
            when (val body = row.body) {
                is StockRelationalBody.ValueEvent -> {
                    check(body.sourceId == sourceId && owner != null && original.acquire(claimKey, owner))
                    events++
                }
                is StockRelationalBody.TransferEdge -> {
                    check(body.sourceId == sourceId)
                    val key = AggregateKey(LocalStockStoreProfile.TRANSFER_DOCUMENT, sourceId)
                    val transfer = observation.document(key, StockTransferWire.serializer()) ?: inconsistentStock()
                    check(transfer.value.request.transferId == sourceId && observation.claim(LocalStockStoreProfile.documentClaim(sourceId)) == sourceId &&
                        transfer.original.producer == original.producer &&
                        transfer.original.acquire(LocalStockStoreProfile.documentClaim(sourceId), sourceId))
                    resources += arrivalNormalProducerResourcesV2(transfer.original.producer)
                    resources += RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, sourceId)
                    transferEdges++
                }
                is StockRelationalBody.ValueSlice, is StockRelationalBody.ValueTarget, is StockRelationalBody.IssueSlice -> otherValues++
                else -> inconsistentStock()
            }
        }
        if (owner != null && events == 0) {
            resources += arrivalNormalScalarSourceV2(observation, sourceId, owner)
            used = true
        } else {
            check(owner != null || events == 0 && otherValues == 0)
            check(events > 0 || otherValues == 0)
            if (events > 0 || transferEdges > 0) used = true
        }
    }
    return ArrivalNormalInterlockV2(used, resources.toSet())
}

/** Count/inspection can genuinely consume a source without any value event. Receiving a
 * supplier-owned rejected-held slice does too; its real receipt-line claim selects the row.
 * No arbitrary source claim owner is interpreted as a receipt/transfer/operation ID. */
private suspend fun arrivalNormalScalarSourceV2(observation: ArrivalNormalObservationV2, sourceId: DomainId,
    owner: DomainId): Set<RecoveryResourceKey> {
    val resources = linkedSetOf<RecoveryResourceKey>()
    var matched = 0
    val inspection = observation.document(AggregateKey(LocalStockStoreProfile.INSPECTION, sourceId), StockInspectionWire.serializer())
    if (inspection != null) {
        check(owner == sourceId && inspection.value.request.inspectionId == sourceId &&
            observation.claim(LocalStockStoreProfile.documentClaim(sourceId)) == sourceId &&
            inspection.original.acquire(LocalStockStoreProfile.sourceClaim(sourceId), owner))
        matched++
        resources += arrivalNormalProducerResourcesV2(inspection.original.producer)
        listOf(sourceId, inspection.value.request.receiptId, inspection.value.request.lineId, inspection.value.request.evidenceId).forEach {
            resources += RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, it)
        }
        resources += RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, inspection.value.inspectorId)
        inspection.value.serialRegistration?.let { resources += serialRegistrationResources(it.restore()) }
    }
    val count = observation.document(AggregateKey(LocalStockStoreProfile.COUNT_DOCUMENT, sourceId), StockCountWire.serializer())
    if (count != null && count.value.counted != null) {
        check(owner == sourceId && count.value.countId == sourceId && count.value.postedBy != null && count.value.postedAt != null &&
            observation.claim(LocalStockStoreProfile.documentClaim(sourceId)) == sourceId &&
            count.original.acquire(LocalStockStoreProfile.sourceClaim(sourceId), owner))
        matched++
        resources += arrivalNormalProducerResourcesV2(count.original.producer)
        resources += arrivalNormalPositionResourcesV2(count.value.position, observation.scope)
        listOfNotNull(count.value.capturedBy, count.value.postedBy).forEach {
            resources += RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it)
        }
        count.value.surplusBasis?.let { basis ->
            resources += sourceResources(basis.restore().sources)
            listOf(basis.basisId, basis.approvedBy, basis.policyId).forEach { resources += RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it) }
        }
    }
    val receiptId = observation.claim(LocalStockStoreProfile.receiptLineClaim(sourceId))
    if (receiptId != null) {
        val key = AggregateKey(LocalStockStoreProfile.RECEIPT_DOCUMENT, receiptId)
        val receipt = observation.document(key, StockReceiptWire.serializer()) ?: inconsistentStock()
        check(receipt.value.receiptId == receiptId && observation.claim(LocalStockStoreProfile.documentClaim(receiptId)) == receiptId)
        val line = receipt.value.lines.singleOrNull { it.request.sourceId == sourceId } ?: inconsistentStock()
        check(line.request.restore().disposition != LocalReceiptDisposition.REFUSED)
        var original = receipt.original
        var document = ArrivalNormalOriginalDocumentV2(receipt.envelope, original.document(key).digest)
        val seen = mutableSetOf<OperationId>()
        while (!original.acquire(LocalStockStoreProfile.sourceClaim(sourceId), owner)) {
            if (seen.size >= 512) throw ArrivalHydrationCapacityUnavailableV2()
            check(seen.add(original.producer.operationId))
            val previous = document.envelope.previous ?: inconsistentStock()
            original = observation.original(previous.operationId)
            document = original.document(key)
            check(document.envelope.storageRevision == previous.revision && document.digest.value == previous.digest &&
                document.envelope.producer.normalProducerV2() == original.producer)
            val prior = stockBody(document.envelope.body, StockReceiptWire.serializer())
            check(prior.receiptId == receiptId && prior.lines.singleOrNull { it.request.sourceId == sourceId }?.request == line.request)
            resources += arrivalNormalProducerResourcesV2(original.producer)
        }
        check(document.envelope.previous == null)
        matched++
        resources += arrivalNormalProducerResourcesV2(receipt.original.producer)
        for (id in listOf(receiptId, line.request.lineId, sourceId)) resources += RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, id)
        resources += arrivalNormalPositionResourcesV2(StockPositionRefWire.from(receiptPosition(line.request.restore(), receipt.value.supplierId)), observation.scope)
    }
    check(matched == 1) { "Normal source claim has no genuine scalar or value origin" }
    return resources
}

internal suspend fun observeArrivalNormalSourcesV2(stock: LocalStockRepository, view: CommandReadView,
    sourceIds: List<DomainId>): ArrivalNormalInterlockV2 {
    val relational = stock.isRelationalCurrent(view)
    val read = if (relational) stock.observeRelational(view) else null
    val marker = read?.record(LocalStockRelationalProfileV2.activationKey(view.scope.branchId))
    val resources = marker?.let { arrivalNormalProducerResourcesV2(it.producer) +
        RecoveryResourceKey(RecoveryResourceKind.STOCK, it.key.id) }.orEmpty()
    return arrivalNormalSourcesV2(ArrivalNormalCurrentV2(stock, view, relational, read), relational, sourceIds, resources)
}

internal suspend fun observeArrivalNormalSourcesBeforeV2(command: RuntimeHistoricalCommand,
    sourceIds: List<DomainId>): ArrivalNormalInterlockV2 {
    val marker = arrivalStockProfileBeforeV2(command)
    val resources = marker?.let { source ->
        val row = LocalStockRelationalCodecV2.decode(command.scope, source.row)
        arrivalNormalProducerResourcesV2(row.producer) + RecoveryResourceKey(RecoveryResourceKind.STOCK, row.key.id)
    }.orEmpty()
    return arrivalNormalSourcesV2(ArrivalNormalNativeV2(command), marker != null, sourceIds, resources)
}

private suspend fun arrivalNormalSerialV2(observation: ArrivalNormalObservationV2, id: DomainId): ArrivalNormalDocumentV2<StockSerialWire>? {
    val row = observation.serial(id)
    val owner = observation.claim(LocalStockStoreProfile.serialClaim(id))
    check(owner == row?.value?.serialId)
    if (row == null) return null
    val serial = row.value
    requireStockSerialCode(serial.serialCode)
    check(serial.serialId == id && serial.skuId == serial.originalPosition.skuId && serial.originalPosition.serialId == id &&
        serial.baseQuantity == stockSerialUnit(serial.baseQuantity) &&
        observation.claim(LocalStockStoreProfile.serialCodeClaim(serial.serialCode)) == id &&
        (serial.registration == null) == (serial.registeredAt == null))
    serial.registration?.let { registration ->
        val actual = registration.restore()
        check(registration == StockSerialRegistrationWire.from(actual))
        requireSerialRegistration(actual, observation.scope, id, serial.skuId, serial.serialCode, requireNotNull(serial.registeredAt))
    }
    return row
}

private fun arrivalNormalPositionResourcesV2(position: StockPositionRefWire, scope: BranchScope): Set<RecoveryResourceKey> = buildSet {
    val key = position.key(scope)
    listOfNotNull(position.skuId, position.warehouseId, position.lotId, position.serialId,
        LocalStockStoreProfile.positionKey(key).id, LocalStockStoreProfile.poolKey(key.pool).id).forEach {
        add(RecoveryResourceKey(RecoveryResourceKind.STOCK, it))
    }
    position.supplierId?.let { add(RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT, it)) }
}

private fun arrivalNormalSerialResourcesV2(scope: BranchScope,
    row: ArrivalNormalDocumentV2<StockSerialWire>): Set<RecoveryResourceKey> = buildSet {
    addAll(arrivalNormalProducerResourcesV2(row.original.producer))
    addAll(arrivalNormalPositionResourcesV2(row.value.originalPosition, scope))
    add(RecoveryResourceKey(RecoveryResourceKind.STOCK, localStockId("serial-code", row.value.serialCode)))
    for (id in listOf(row.value.receiptId, row.value.lineId, row.value.sourceId)) add(RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, id))
    add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, row.value.recordedBy))
    row.value.registration?.let { addAll(serialRegistrationResources(it.restore())) }
}

private suspend fun arrivalNormalSerialsV2(observation: ArrivalNormalObservationV2,
    slices: List<ArrivalSliceV1>): ArrivalNormalInterlockV2 {
    val rows = linkedMapOf<DomainId, ArrivalNormalDocumentV2<StockSerialWire>?>()
    val resources = linkedSetOf<RecoveryResourceKey>()
    var used = false
    suspend fun serial(id: DomainId): ArrivalNormalDocumentV2<StockSerialWire>? {
        if (id !in rows) rows[id] = arrivalNormalSerialV2(observation, id)
        return rows[id]
    }
    for (slice in slices) {
        slice.serialId?.let { id ->
            resources += RecoveryResourceKey(RecoveryResourceKind.STOCK, id)
            serial(id)?.let { resources += arrivalNormalSerialResourcesV2(observation.scope, it); used = true }
        }
        slice.serialCode?.let { code ->
            requireStockSerialCode(code)
            resources += RecoveryResourceKey(RecoveryResourceKind.STOCK, localStockId("serial-code", code))
            observation.claim(LocalStockStoreProfile.serialCodeClaim(code))?.let { owner ->
                val row = serial(owner) ?: inconsistentStock()
                check(row.value.serialCode == code)
                resources += arrivalNormalSerialResourcesV2(observation.scope, row)
                used = true
            }
        }
    }
    return ArrivalNormalInterlockV2(used, resources.toSet())
}

internal suspend fun observeArrivalNormalSerialsV2(stock: LocalStockRepository, view: CommandReadView,
    slices: List<ArrivalSliceV1>): ArrivalNormalInterlockV2 = arrivalNormalSerialsV2(ArrivalNormalCurrentV2(stock, view), slices)

internal suspend fun observeArrivalNormalSerialsBeforeV2(command: RuntimeHistoricalCommand,
    slices: List<ArrivalSliceV1>): ArrivalNormalInterlockV2 = arrivalNormalSerialsV2(ArrivalNormalNativeV2(command), slices)

/** Pure selected-position comparison using a genuine native serial origin and actual claims. */
internal suspend fun requireArrivalNativeSerialPositionV2(command: RuntimeHistoricalCommand,
    position: StockPosition): StockSerialWire? {
    val id = position.key.serialId ?: return null
    val row = arrivalNormalSerialV2(ArrivalNormalNativeV2(command), id) ?: inconsistentStock()
    requireStockSerialPosition(row.value, position)
    return row.value
}
