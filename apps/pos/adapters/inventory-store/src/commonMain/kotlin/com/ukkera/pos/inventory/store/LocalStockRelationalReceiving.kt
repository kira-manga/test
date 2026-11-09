package com.ukkera.pos.inventory.store

import com.ukkera.pos.catalog.StockTracking
import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

@Serializable internal data class StockReceivingRequestV2Wire(val action: Int, val operationId: OperationId,
    val receipt: StockReceiptRequestWire?, val purchase: StockPoReceiptRequestWire?) {
    fun restore(): LocalStockRelationalCommand = when (action) {
        4, 5 -> { localStockCheck(receipt != null && purchase == null); LocalStockRelationalCommand.Receive(operationId, checkNotNull(receipt).restore(), action == 4) }
        6 -> { localStockCheck(receipt == null && purchase != null); LocalStockRelationalCommand.ReceivePo(operationId, checkNotNull(purchase).restore()) }
        else -> inconsistentStock()
    }
}
internal const val STOCK_RECEIVING_EFFECT_V2: String = "local.stock.v2.receiving"
internal val stockReceivingDocumentKinds: Set<String> get() = setOf(LocalStockStoreProfile.RECEIPT_DOCUMENT,
    LocalStockStoreProfile.ALLOWANCE, LocalStockStoreProfile.SERIAL_ORIGIN)
internal val stockDocumentKinds: Set<String> get() = stockReceivingDocumentKinds + setOf(
    LocalStockStoreProfile.COUNT_DOCUMENT, LocalStockStoreProfile.INSPECTION, LocalStockStoreProfile.TRANSFER_DOCUMENT)
internal val stockReceivingClaimNamespaces: Set<String> get() = setOf(LocalStockStoreProfile.ITEM_CLAIM,
    LocalStockStoreProfile.SOURCE_CLAIM, LocalStockStoreProfile.DOCUMENT_CLAIM, LocalStockStoreProfile.RECEIPT_LINE_CLAIM,
    LocalStockStoreProfile.ALLOWANCE_CLAIM, LocalStockStoreProfile.SERIAL_ID_CLAIM, LocalStockStoreProfile.SERIAL_CODE_CLAIM)

internal fun stockReceivingInput(request: LocalStockRelationalCommand): StockReceiptPreparationInput = when (request) {
    is LocalStockRelationalCommand.Receive -> StockReceiptPreparationInput.legacy(request.request)
    is LocalStockRelationalCommand.ReceivePo -> StockReceiptPreparationInput.purchase(request.request)
    else -> inconsistentStock()
}
internal fun stockReceivingOperation(request: LocalStockRelationalCommand): OperationId = when (request) {
    is LocalStockRelationalCommand.Receive -> request.operationId
    is LocalStockRelationalCommand.ReceivePo -> request.operationId
    else -> inconsistentStock()
}
private fun stockOpening(request: LocalStockRelationalCommand): Boolean = (request as? LocalStockRelationalCommand.Receive)?.opening == true

/** Existing bounded receiving/PO input limits remain unchanged. This is NOT food/retail capacity. */
internal class StockReceivingInputs(val request: LocalStockRelationalCommand, val input: StockReceiptPreparationInput,
    val basis: LocalStockReceivingBasis, val items: Map<DomainId, LocalStockItemAuthority>,
    val warehouses: Map<DomainId, LocalStockWarehouseAuthority>, val registrations: Map<DomainId, LocalStockSerialRegistration?>,
    val purchase: LocalStockApprovedPurchaseOrderV1?, val authority: LocalStockRelationalAuthorityReader)

private suspend fun currentReceivingInputs(repository: LocalStockRepository, authority: LocalStockAuthorityReader,
    read: LocalStockRelationalRead, request: LocalStockRelationalCommand): StockReceivingInputs {
    val installed = repository.receivingAuthority(authority)
    val input = stockReceivingInput(request)
    val basis = (if (stockOpening(request)) installed.openingBasis(read.view, input.basisId) else installed.receivingBasis(read.view, input.basisId))
        ?: throw LocalStockRejected(LocalStockFailure.BASIS_UNAVAILABLE)
    val items = input.items.associate { it.skuId to (installed.item(read.view, it.skuId) ?: throw LocalStockRejected(LocalStockFailure.ITEM_UNAVAILABLE)) }
    val warehouses = input.lines.map { it.warehouseId }.distinct().associateWith {
        installed.warehouse(read.view, it) ?: throw LocalStockRejected(LocalStockFailure.WAREHOUSE_UNAVAILABLE) }
    val registrations = input.lines.filter { it.serialId != null && it.disposition != LocalReceiptDisposition.REFUSED }.associate {
        checkNotNull(it.serialId) to installed.serialRegistration(read.view, checkNotNull(it.serialId)) }
    val purchase = if (request is LocalStockRelationalCommand.ReceivePo) installed.currentPurchaseOrder(read.view, input.basisId)
        ?: throw LocalStockRejected(LocalStockFailure.BASIS_UNAVAILABLE) else null
    return StockReceivingInputs(request, input, basis, items, warehouses, registrations, purchase, installed)
}

private suspend fun retainedReceivingInputs(original: RetainedStreamedCommand,
    authority: LocalStockRelationalAuthorityReader, request: LocalStockRelationalCommand): StockReceivingInputs {
    val input = stockReceivingInput(request)
    val basis = authority.retainedBasis(original, input.basisId)
    val items = input.items.associate { it.skuId to authority.retainedItem(original, it.skuId) }
    val warehouses = input.lines.map { it.warehouseId }.distinct().associateWith { authority.retainedWarehouse(original, it) }
    val registrations = input.lines.filter { it.serialId != null && it.disposition != LocalReceiptDisposition.REFUSED }.associate {
        checkNotNull(it.serialId) to authority.retainedSerialRegistration(original, checkNotNull(it.serialId)) }
    val purchase = if (request is LocalStockRelationalCommand.ReceivePo) authority.retainedPurchaseOrder(original, input.basisId) else null
    return StockReceivingInputs(request, input, basis, items, warehouses, registrations, purchase, authority)
}

/** Read implementations consume actual current owner rows OR literal validated original frames.
 * Neither accepts a caller snapshot, a completeness flag or a missing-as-empty stock projection. */
internal interface StockPoolHistoryAccess {
    suspend fun normal(key: AggregateKey): StockRelationalRecordV2?
    suspend fun claim(key: ClaimKey): DomainId?
    suspend fun range(query: StockQueryV2, consume: suspend (StockRelationalRecordV2) -> Unit)
}

internal interface StockReceiptAccess : StockPoolHistoryAccess {
    val scope: BranchScope
    val operationId: OperationId
    val actorId: DomainId
    val now: UtcInstant
    override suspend fun normal(key: AggregateKey): StockRelationalRecordV2?
    suspend fun document(key: AggregateKey): StoredAggregate?
    override suspend fun claim(key: ClaimKey): DomainId?
    suspend fun current(stamp: LocalStockSourceStamp)
    suspend fun absent(key: AggregateKey)
    suspend fun arrivalV2NegativeProfile(): Boolean
    override suspend fun range(query: StockQueryV2, consume: suspend (StockRelationalRecordV2) -> Unit)
}

internal class CurrentReceiptAccess(private val repository: LocalStockRepository, private val read: LocalStockRelationalRead,
    private val context: CommandContext, override val now: UtcInstant) : StockReceiptAccess {
    override val scope: BranchScope get() = read.scope
    override val operationId: OperationId get() = context.operationId
    override val actorId: DomainId get() = context.originalActorId
    override suspend fun normal(key: AggregateKey): StockRelationalRecordV2? = read.record(key)?.also {
        (it.body as? StockRelationalBody.Position)?.value?.let { position -> requireRelationalStockSerialPosition(read, repository, position) }
    }
    override suspend fun document(key: AggregateKey): StoredAggregate? {
        localStockCheck(key.kind in stockDocumentKinds)
        val record = when (key.kind) {
            LocalStockStoreProfile.RECEIPT_DOCUMENT -> repository.record(read.view, key, StockReceiptWire.serializer())
            LocalStockStoreProfile.ALLOWANCE -> stockAllowance(repository, read.view, key.id)
            LocalStockStoreProfile.SERIAL_ORIGIN -> stockSerial(repository, read.view, key.id)
            LocalStockStoreProfile.COUNT_DOCUMENT -> stockDocument(repository, read.view, key.kind, key.id, StockCountWire.serializer())
            LocalStockStoreProfile.INSPECTION -> stockDocument(repository, read.view, key.kind, key.id, StockInspectionWire.serializer())
            LocalStockStoreProfile.TRANSFER_DOCUMENT -> stockDocument(repository, read.view, key.kind, key.id, StockTransferWire.serializer())
            else -> inconsistentStock()
        }
        if (record == null) { absent(key); return null }
        val p = record.envelope.producer
        checkNotNull(read.sink).source(StreamedSourceObservationV2.Aggregate(record.row.key, record.row.revision, record.row.schema,
            record.row.payload, record.row.digest, StreamedProducerIdentityV2(p.operationId, p.commandKey(), p.actorId, PayloadDigest(p.digest))))
        return record.row
    }
    override suspend fun claim(key: ClaimKey): DomainId? = read.claim(key)
    override suspend fun current(stamp: LocalStockSourceStamp) { requireStockAuthoritySources(read, listOf(stamp)) }
    override suspend fun absent(key: AggregateKey) {
        localStockCheck(key.kind !in StockRelationalKindsV2.all && read.access.aggregate(key) == null, LocalStockFailure.PRODUCER_UNAVAILABLE)
        checkNotNull(read.sink).source(StreamedSourceObservationV2.CurrentAbsent(key))
    }
    override suspend fun arrivalV2NegativeProfile(): Boolean = true
    override suspend fun range(query: StockQueryV2, consume: suspend (StockRelationalRecordV2) -> Unit) {
        read.query(query) { index ->
            val row = normal(index.identity.key) ?: inconsistentStock()
            localStockCheck(stockIndexOf(scope, row) == index); consume(row)
        }
    }
}

internal class RetainedReceiptAccess(private val stock: LocalStockRelationalInspection,
    private val observe: suspend (StreamedSourceObservationV2) -> Unit = {}) : StockReceiptAccess {
    override val scope: BranchScope get() = stock.scope
    override val operationId: OperationId get() = stock.operationId
    override val actorId: DomainId get() = stock.original.identity.producer.originalActorId
    override val now: UtcInstant get() = stock.recordedAt
    private var hasArrivalV2NegativeProfile: Boolean? = null
    private suspend fun literal(key: AggregateKey): StreamedSourceObservationV2.Aggregate? = stock.source(key)?.also { observe(it) }
    override suspend fun normal(key: AggregateKey): StockRelationalRecordV2? {
        val source = literal(key) ?: return null
        val row = LocalStockRelationalCodecV2.decode(scope, key, source.generation, source.schema, source.payload, source.payloadHash)
        val position = (row.body as? StockRelationalBody.Position)?.value
        if (position?.key?.serialId != null) {
            val id = checkNotNull(position.key.serialId)
            val serialRow = document(LocalStockStoreProfile.serialKey(id)) ?: inconsistentStock()
            val serial = receiptDocument(serialRow, scope, StockSerialWire.serializer()).value
            requireStockSerialPosition(serial, position)
            requireStockSerialCode(serial.serialCode)
            localStockCheck(claim(LocalStockStoreProfile.serialClaim(id)) == id && claim(LocalStockStoreProfile.serialCodeClaim(serial.serialCode)) == id)
            serial.registration?.let { requireSerialRegistration(it.restore(), scope, id, serial.skuId, serial.serialCode,
                serial.registeredAt ?: inconsistentStock()) }
        }
        return row
    }
    override suspend fun document(key: AggregateKey): StoredAggregate? {
        val source = literal(key)
        if (source == null) { absent(key); return null }
        localStockCheck(key.kind in stockDocumentKinds)
        val row = stock.original.sourceRow(key) ?: inconsistentStock()
        localStockCheck(row.revision == source.generation && row.schema == source.schema && row.payload == source.payload && row.digest == source.payloadHash)
        return row.also { stockRecord(it, scope) }
    }
    override suspend fun claim(key: ClaimKey): DomainId? {
        val owner = stock.observedClaim(key); observe(StreamedSourceObservationV2.Claim(key, owner)); return owner
    }
    override suspend fun current(stamp: LocalStockSourceStamp) {
        var actual: StreamedSourceObservationV2.Current? = null
        stock.original.frames { frame ->
            val row = (frame as? RetainedCommandFrameV2.Source)?.value as? StreamedSourceObservationV2.Current
            if (row?.key == stamp.key) { localStockCheck(actual == null); actual = row }
        }
        val source = actual ?: inconsistentStock()
        localStockCheck(source.generation == stamp.storageRevision && source.payloadHash == stamp.digest); observe(source)
    }
    override suspend fun absent(key: AggregateKey) {
        var found = false
        stock.original.frames { frame -> if ((frame as? RetainedCommandFrameV2.Source)?.value == StreamedSourceObservationV2.CurrentAbsent(key)) {
            localStockCheck(!found); found = true
        } }
        localStockCheck(found); observe(StreamedSourceObservationV2.CurrentAbsent(key))
    }
    override suspend fun arrivalV2NegativeProfile(): Boolean {
        hasArrivalV2NegativeProfile?.let { return it }
        var present = false
        stock.original.frames { frame ->
            val source = (frame as? RetainedCommandFrameV2.Source)?.value as? StreamedSourceObservationV2.CurrentAbsent
            if (source != null && source.key.kind in arrivalKindsV2) present = true
        }
        hasArrivalV2NegativeProfile = present
        return present
    }
    override suspend fun range(query: StockQueryV2, consume: suspend (StockRelationalRecordV2) -> Unit) {
        var count = 0L
        stock.original.stockRange(query) { index ->
            val row = normal(index.identity.key) ?: inconsistentStock()
            localStockCheck(stockIndexOf(scope, row) == index)
            observe(StreamedSourceObservationV2.StockMember(query, count, index)); count = stockCheckedAdd(count, 1); consume(row)
        }
        var terminal: StreamedSourceObservationV2.StockRange? = null
        stock.original.frames { frame ->
            val source = (frame as? RetainedCommandFrameV2.Source)?.value as? StreamedSourceObservationV2.StockRange
            if (source?.query == query) { localStockCheck(terminal == null && source.count == count); terminal = source }
        }
        observe(terminal ?: inconsistentStock())
    }
}

internal fun <T> receiptDocument(row: StoredAggregate, scope: BranchScope, serializer: KSerializer<T>): LocalStockRecord<T> {
    val envelope = stockRecord(row, scope)
    return LocalStockRecord(row, envelope, stockBody(envelope.body, serializer))
}
internal class ReceiptDocumentDraft(val key: AggregateKey, val before: StoredAggregate?, val body: String)
internal class StockReceiptPlan(val records: Map<AggregateKey, StockRelationalBody>, val documents: List<ReceiptDocumentDraft>,
    val claims: Map<ClaimKey, DomainId>, val companionResources: Set<RecoveryResourceKey>,
    val result: StoredBytes, val deadline: UtcInstant?)

/** Literal deterministic receiving transition. Only command-bounded input/delta maps are held;
 * all lot/position/value-history sources are consumed completely as streams. */
internal suspend fun receiptPlan(inputs: StockReceivingInputs, read: StockReceiptAccess): StockReceiptPlan {
    val input = inputs.input; val basis = inputs.basis; val opening = stockOpening(inputs.request)
    localStockCheck(stockReceivingOperation(inputs.request) == read.operationId && basis.scope == read.scope && basis.basisId == input.basisId,
        LocalStockFailure.WRONG_SCOPE)
    val activation = read.normal(LocalStockRelationalProfileV2.activationKey(read.scope.branchId)) ?: unavailableProducer()
    localStockCheck(activation.body is StockRelationalBody.Activation && stockActivationShape(activation.body))
    localStockCheck(basis.revision == input.expectedBasisRevision, LocalStockFailure.STALE_REVISION)
    localStockCheck((basis.kind == LocalStockBasisKind.OPENING) == opening && read.now >= basis.validFrom &&
        (basis.validUntil == null || read.now < basis.validUntil), LocalStockFailure.BASIS_EXPIRED)
    localStockCheck(!opening || input.items.all { it.storageRevision == null } && input.lines.all {
        it.disposition == LocalReceiptDisposition.RELEASED || it.disposition == LocalReceiptDisposition.QUARANTINE }, LocalStockFailure.OPENING_ALREADY_ESTABLISHED)
    val records = linkedMapOf<AggregateKey, StockRelationalBody>()
    val claims = linkedMapOf<ClaimKey, DomainId>()
    val documents = mutableListOf<ReceiptDocumentDraft>()
    val resources = linkedSetOf<RecoveryResourceKey>()
    fun resource(kind: RecoveryResourceKind, id: DomainId) { resources += RecoveryResourceKey(kind, id) }
    suspend fun acquire(key: ClaimKey, owner: DomainId) {
        localStockCheck(key !in claims && read.claim(key) == null, LocalStockFailure.SOURCE_ALREADY_USED); claims[key] = owner
    }
    val versions = input.items.associate { expected ->
        val row = read.normal(LocalStockRelationalProfileV2.itemKey(expected.skuId))
        localStockCheck(read.claim(LocalStockStoreProfile.itemClaim(expected.skuId)) == row?.key?.id)
        localStockCheck(row?.generation == expected.storageRevision, LocalStockFailure.STALE_REVISION)
        val authority = inputs.items.getValue(expected.skuId)
        localStockCheck(authority.scope == read.scope && authority.skuId == expected.skuId)
        if (row != null) localStockCheck(row.body == StockRelationalBody.Item(authority.skuId, authority.baseUnitId,
            authority.quantityScale, authority.tracking == StockTracking.SERIAL), LocalStockFailure.INVALID_UNIT)
        else read.range(StockQueryV2.SkuPositions(expected.skuId)) { inconsistentStock() }
        expected.skuId to row
    }
    localStockCheck(read.document(AggregateKey(LocalStockStoreProfile.RECEIPT_DOCUMENT, input.receiptId)) == null, LocalStockFailure.SOURCE_ALREADY_USED)
    acquire(LocalStockStoreProfile.documentClaim(input.receiptId), input.receiptId)
    val allowances = input.lines.map { it.allocationId }.distinct().associateWith { id ->
        val row = read.document(AggregateKey(LocalStockStoreProfile.ALLOWANCE, id))
        localStockCheck(read.claim(LocalStockStoreProfile.allowanceClaim(id)) == row?.key?.id)
        row?.let { receiptDocument(it, read.scope, StockAllowanceWire.serializer()).also { before -> requireAllowance(before.value) } }
    }
    inputs.purchase?.let { purchase ->
        requireApprovedPoSource(purchase, read.scope, input.basisId)
        localStockCheck(purchase.approval.recordedAt <= read.now && purchase.approval.operationId != read.operationId)
    }
    for (observation in inputs.authority.receivingClaims(basis, inputs.purchase)) localStockCheck(read.claim(observation.key) == observation.ownerId)
    val (request, poProof) = input.resolve(inputs.purchase, basis, allowances)
    val rawStamps = basis.sources + inputs.items.values.flatMap { it.sources } + inputs.warehouses.values.flatMap { it.sources } +
        inputs.registrations.values.filterNotNull().flatMap { it.sources } + listOfNotNull(inputs.purchase?.source)
    val stamps = rawStamps.distinctBy { it.key }.sortedWith(compareBy({ it.key.kind }, { it.key.id.value }))
    for (stamp in rawStamps) localStockCheck(stamps.single { it.key == stamp.key } == stamp)
    for (stamp in stamps) { read.current(stamp); resource(RecoveryResourceKind.AUTHORIZATION, stamp.key.id) }
    resource(RecoveryResourceKind.STOCK, input.receiptId)
    resource(RecoveryResourceKind.PURCHASE_CUSTODY, input.receiptId); resource(RecoveryResourceKind.PO_ALLOWANCE, basis.basisId)
    resource(RecoveryResourceKind.AUTHORIZATION, basis.approvedBy); resource(RecoveryResourceKind.AUTHORIZATION, basis.policyId)
    basis.supplierId?.let { resource(RecoveryResourceKind.PURCHASE_CUSTODY, it); if (inputs.purchase != null) resource(RecoveryResourceKind.SUPPLIER_SETTLEMENT, it) }
    val lines = request.lines.map { line ->
        val item = inputs.items.getValue(line.skuId); val warehouse = inputs.warehouses.getValue(line.warehouseId)
        localStockCheck(warehouse.scope == read.scope && warehouse.warehouseId == line.warehouseId && warehouse.active, LocalStockFailure.WAREHOUSE_UNAVAILABLE)
        val approved = basis.lines.singleOrNull { it.allocationId == line.allocationId && it.skuId == line.skuId && it.warehouseId == line.warehouseId }
            ?: throw LocalStockRejected(LocalStockFailure.BASIS_UNAVAILABLE)
        inputs.purchase?.order?.lines?.singleOrNull { it.allocationId == line.allocationId }?.let { ordered ->
            localStockCheck(item.productId == ordered.goods.units.productId && item.baseUnitId == ordered.goods.units.baseUnitId &&
                item.quantityScale == ordered.goods.units.baseScale, LocalStockFailure.INVALID_UNIT)
        }
        requireReceiptArrivalAbsence(read, line) { resources += it }
        val registration = if (line.disposition == LocalReceiptDisposition.REFUSED) null else line.serialId?.let { inputs.registrations[it] }
        registration?.let { requireSerialRegistration(it, read.scope, checkNotNull(line.serialId), line.skuId, checkNotNull(line.serialCode), read.now) }
        localStockCheck(line.serialId == null || line.disposition != LocalReceiptDisposition.RELEASED || registration != null, LocalStockFailure.SERIAL_REGISTRATION_REQUIRED)
        val resolved = resolveReceiptLine(line, item, opening, approved.inspectionRequired, read.now).copy(serialRegistration = registration?.let(StockSerialRegistrationWire::from))
        localStockCheck(approved.maximumBaseQuantity.unitId == resolved.baseQuantity.unitId && approved.maximumBaseQuantity.scale == resolved.baseQuantity.scale, LocalStockFailure.INVALID_UNIT)
        acquire(LocalStockStoreProfile.receiptLineClaim(line.sourceId), input.receiptId)
        // A refused source is still permanently a receipt identity; it creates no physical stock.
        localStockCheck(read.claim(LocalStockStoreProfile.sourceClaim(line.sourceId)) == null, LocalStockFailure.SOURCE_ALREADY_USED)
        resource(RecoveryResourceKind.PO_ALLOWANCE, line.allocationId)
        resource(RecoveryResourceKind.PURCHASE_CUSTODY, line.sourceId); resource(RecoveryResourceKind.STOCK, line.sourceId)
        resource(RecoveryResourceKind.STOCK, line.skuId); resource(RecoveryResourceKind.STOCK, line.warehouseId)
        if (inputs.purchase != null) resource(RecoveryResourceKind.PURCHASE_CUSTODY, line.lineId)
        line.lotId?.let { resource(RecoveryResourceKind.STOCK, it) }
        line.serialId?.let { resource(RecoveryResourceKind.STOCK, it) }
        line.serialCode?.let { resource(RecoveryResourceKind.STOCK, localStockId("serial-code", it)) }
        registration?.let { resources += serialRegistrationResources(it) }
        resolved
    }
    localStockCheck(lines.filter { it.request.disposition != LocalReceiptDisposition.REFUSED.name }.groupBy { it.request.skuId to it.request.lotId }
        .values.all { group -> group.map { it.request.expiresAt }.distinct().size == 1 }, LocalStockFailure.INVALID_LOT)
    val arrived = lines.filter { it.request.disposition != LocalReceiptDisposition.REFUSED.name }
    for (sku in arrived.map { it.request.skuId }.distinct()) {
        val item = inputs.items.getValue(sku)
        records[LocalStockRelationalProfileV2.itemKey(sku)] = StockRelationalBody.Item(sku, item.baseUnitId, item.quantityScale, item.tracking == StockTracking.SERIAL)
        if (versions.getValue(sku) == null) acquire(LocalStockStoreProfile.itemClaim(sku), sku)
    }
    for (line in arrived) {
        val value = line.request.restore(); val positionKey = receiptPosition(value, basis.supplierId).inScope(read.scope)
        val key = LocalStockRelationalProfileV2.positionKey(positionKey)
        acquire(LocalStockStoreProfile.sourceClaim(value.sourceId), DomainId(read.operationId.value))
        if (value.serialId != null) {
            val id = value.serialId; val code = checkNotNull(value.serialCode)
            localStockCheck(read.document(LocalStockStoreProfile.serialKey(id)) == null, LocalStockFailure.SOURCE_ALREADY_USED)
            acquire(LocalStockStoreProfile.serialClaim(id), id); acquire(LocalStockStoreProfile.serialCodeClaim(code), id)
            val serial = StockSerialWire(id, code, value.skuId, input.receiptId, value.lineId, value.sourceId,
                StockPositionRefWire.from(positionKey), line.baseQuantity, read.actorId, read.now, line.serialRegistration,
                if (line.serialRegistration == null) null else read.now)
            documents += ReceiptDocumentDraft(LocalStockStoreProfile.serialKey(id), null, stockBody(serial, StockSerialWire.serializer()))
        }
        val current = read.normal(key)
        val previous = (records[key] ?: current?.body) as? StockRelationalBody.Position
        var recalled = false; var expired = false
        read.range(StockQueryV2.SkuPositions(value.skuId)) { row ->
            val candidate = (row.body as? StockRelationalBody.Position)?.value ?: inconsistentStock()
            if (value.serialId != null) localStockCheck(candidate.key.serialId != value.serialId, LocalStockFailure.SOURCE_ALREADY_USED)
            if (candidate.key.lotId == value.lotId) {
                localStockCheck(candidate.expiresAtEpochMillis == value.expiresAt?.value?.toEpochMilliseconds(), LocalStockFailure.INVALID_LOT)
                recalled = recalled || candidate.safety == StockSafety.RECALLED; expired = expired || candidate.safety == StockSafety.EXPIRED
            }
        }
        if (value.disposition == LocalReceiptDisposition.RELEASED) localStockCheck(!recalled && !expired &&
            (previous == null || previous.value.safety == StockSafety.CLEAR), LocalStockFailure.UNSAFE_STOCK)
        previous?.let { localStockCheck(it.value.onHand.unitId == line.baseQuantity.unitId && it.value.onHand.scale == line.baseQuantity.scale &&
            it.value.expiresAtEpochMillis == value.expiresAt?.value?.toEpochMilliseconds(), LocalStockFailure.INVALID_UNIT) }
        val safety = if (recalled) StockSafety.RECALLED else if (expired) StockSafety.EXPIRED else previous?.value?.safety ?: StockSafety.CLEAR
        records[key] = StockRelationalBody.Position(StockPosition(positionKey, (previous?.value?.onHand ?: stockZeroQuantity(line.baseQuantity)) + line.baseQuantity,
            safety, value.expiresAt?.value?.toEpochMilliseconds(), line.serialRegistration?.registrationId))
        if (positionKey.pool.ownership == StockOwnership.CompanyOwned) {
            val poolKey = LocalStockRelationalProfileV2.poolKey(positionKey.pool)
            val currentPool = read.normal(poolKey)
            val old = (currentPool?.body as? StockRelationalBody.Pool)?.value
            requireReceiptPoolBefore(read, positionKey.pool, old, line.baseQuantity)
            val before = (records[poolKey] as? StockRelationalBody.Pool)?.value ?: old ?:
                // Mathematical identity ONLY for a proven new pool with no positions or events.
                // It is not stocked inventory/default cost and is never separately committed.
                InventoryPoolProjection(positionKey.pool, Revision(1), stockZeroQuantity(line.baseQuantity), Money(Currency.EGP, 0), 0)
            val delta = InventoryValuationProjection.receive(before, line.baseQuantity, checkNotNull(value.originalValue))
            val event = StockRelationalBody.ValueEvent(positionKey.pool, value.sourceId, delta.after.lastEventOrdinal,
                if (opening) StockValueEventKind.OPENING else StockValueEventKind.RECEIVE, before, delta.after, line.baseQuantity,
                checkNotNull(value.originalValue), checkNotNull(value.originalValue), 1, null)
            val eventKey = stockKey(event)
            localStockCheck(read.normal(eventKey) == null && eventKey !in records, LocalStockFailure.SOURCE_ALREADY_USED)
            records[eventKey] = event
            val slice = StockRelationalBody.ValueSlice(positionKey.pool, value.sourceId, eventKey.id, event.ordinal, null,
                value.lineId, line.baseQuantity, checkNotNull(value.originalValue))
            localStockCheck(read.normal(stockKey(slice)) == null && stockKey(slice) !in records, LocalStockFailure.SOURCE_ALREADY_USED)
            records[stockKey(slice)] = slice; records[poolKey] = StockRelationalBody.Pool(delta.after)
        } else localStockCheck(value.originalValue == null && positionKey.custody == StockCustody.REJECTED_HELD)
    }
    val committed = lines.filter { it.request.disposition == LocalReceiptDisposition.RELEASED.name || it.request.disposition == LocalReceiptDisposition.QUARANTINE.name }
        .groupBy { it.request.allocationId }
    for ((id, slices) in committed) {
        val approved = basis.lines.single { it.allocationId == id }
        val old = allowances.getValue(id)
        val zero = stockZeroQuantity(approved.maximumBaseQuantity)
        val before = old?.value ?: StockAllowanceWire(id, basis.basisId, basis.supplierId, approved.skuId, approved.warehouseId,
            zero, Money(Currency.EGP, 0), zero, zero)
        requireAllowance(before)
        localStockCheck(before.allocationId == id && before.basisId == basis.basisId && before.supplierId == basis.supplierId &&
            before.skuId == approved.skuId && before.warehouseId == approved.warehouseId && before.committedQuantity.unitId == zero.unitId &&
            before.committedQuantity.scale == zero.scale, LocalStockFailure.BASIS_UNAVAILABLE)
        val added = slices.fold(zero) { total, slice -> total + slice.baseQuantity }
        val value = slices.fold(Money(Currency.EGP, 0)) { total, slice -> total + checkNotNull(slice.request.originalValue) }
        val accepted = slices.filter { it.request.disposition == LocalReceiptDisposition.RELEASED.name }.fold(zero) { total, slice -> total + slice.baseQuantity }
        val next = before.copy(committedQuantity = before.committedQuantity + added, committedOwnedValue = before.committedOwnedValue + value,
            acceptedQuantity = before.acceptedQuantity + accepted, pendingInspectionQuantity = before.pendingInspectionQuantity + added - accepted)
        requireAllowance(next)
        localStockCheck(next.committedQuantity <= approved.maximumBaseQuantity && next.committedOwnedValue.currency == approved.maximumOwnedValue.currency && next.committedOwnedValue.minor <= approved.maximumOwnedValue.minor, LocalStockFailure.ALLOWANCE_EXCEEDED)
        documents += ReceiptDocumentDraft(AggregateKey(LocalStockStoreProfile.ALLOWANCE, id), old?.row, stockBody(next, StockAllowanceWire.serializer()))
        if (old == null) acquire(LocalStockStoreProfile.allowanceClaim(id), id)
    }
    val document = StockReceiptWire(input.receiptId, basis.basisId, basis.revision, basis.kind.name, basis.supplierId, basis.approvedBy, basis.policyId,
        read.actorId, read.now, lines, stamps.map(StockSourceStampWire::from), input.reasonCode, poProof)
    documents += ReceiptDocumentDraft(AggregateKey(LocalStockStoreProfile.RECEIPT_DOCUMENT, input.receiptId), null, stockBody(document, StockReceiptWire.serializer()))
    val afterVersions = records.keys.filter { it.kind == StockRelationalKindsV2.ITEM }.map {
        LocalStockItemVersion(it.id, versions.getValue(it.id)?.generation?.next() ?: Revision(1)) }.sortedBy { it.skuId.value }
    val deadline = (listOfNotNull(basis.validUntil) + inputs.registrations.values.mapNotNull { it?.validUntil } +
        lines.filter { it.request.disposition == LocalReceiptDisposition.RELEASED.name }.mapNotNull { it.request.expiresAt }).minOrNull()
    return StockReceiptPlan(records, documents, claims, resources, stockResult(if (opening) LocalStockResultKind.OPENED else LocalStockResultKind.RECEIVED,
        input.receiptId, afterVersions), deadline)
}

/** The fresh profile cannot silently reuse unpriced custody. New live receiving observes both
 * exact row families and shared permanent claims. A genuinely retained v1-only negative profile
 * remains v1-only, never a proof of v2 absence; native startup separately checks actual v2 BEFORE.
 * Once any additive v2 negative is retained, its entire exact relevant set is mandatory. */
private suspend fun requireReceiptArrivalAbsence(read: StockReceiptAccess, line: LocalGoodsReceiptLine,
    resource: (RecoveryResourceKey) -> Unit) {
    suspend fun missing(key: AggregateKey, claim: ClaimKey) {
        read.absent(key)
        localStockCheck(read.claim(claim) == null, LocalStockFailure.SOURCE_ALREADY_USED)
        resource(RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, key.id))
    }
    missing(arrivalKey(LocalUnpricedArrivalProfileV1.SOURCE_IDENTITY, line.sourceId), arrivalSourceClaim(line.sourceId))
    localStockCheck(read.claim(arrivalLineClaim(line.lineId)) == null, LocalStockFailure.SOURCE_ALREADY_USED)
    resource(RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, line.lineId))
    line.serialId?.let { missing(arrivalSerialIdKey(it), arrivalSerialIdClaim(it)) }
    line.serialCode?.let { missing(arrivalSerialCodeKey(it), arrivalSerialCodeClaim(it)) }
    line.lotId?.let { lot -> val key = arrivalLotKey(line.skuId, lot); missing(key, arrivalLotClaim(key)) }
    if (read.arrivalV2NegativeProfile()) {
        val keys = listOfNotNull(arrivalKeyV2(LocalUnpricedArrivalProfileV2.SOURCE_IDENTITY, line.sourceId),
            line.serialId?.let(::arrivalSerialIdKeyV2), line.serialCode?.let(::arrivalSerialCodeKeyV2),
            line.lotId?.let { arrivalLotKeyV2(line.skuId, it) })
        for (key in keys) {
            read.absent(key)
            resource(RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, key.id))
        }
    }
}

/** Complete current position/value closure, not the last page or a chosen favorable event.
 * Every event is the actual scalar source, every slice joins its event and immutable original
 * issue allocation in both directions, and the entire consecutive history ends at the literal
 * current pool. A restoration joins the exact immutable issue and complete cumulative slices;
 * DIRECT_RECIPE keeps its original whole-once contract, RETAIL permits explicit partial returns.
 * Same-branch transfers join both immutable edges and their original carrying value.
 * Linked corrections remain unsupported; they are NEVER dropped from this fold. */
internal suspend fun requireReceiptPoolBefore(read: StockPoolHistoryAccess, pool: StockPoolKey,
    before: InventoryPoolProjection?, unit: Quantity) {
    localStockCheck(pool.ownership == StockOwnership.CompanyOwned)
    var positions = 0L; var quantity = stockZeroQuantity(unit)
    read.range(StockQueryV2.Positions(pool.skuId, pool.warehouseId)) { row ->
        val position = (row.body as? StockRelationalBody.Position)?.value ?: inconsistentStock()
        if (position.key.pool == pool) {
            localStockCheck(position.onHand.unitId == unit.unitId && position.onHand.scale == unit.scale)
            positions = stockCheckedAdd(positions, 1); quantity += position.onHand
        }
    }
    val seed = InventoryPoolProjection(pool, Revision(1), stockZeroQuantity(unit), Money(Currency.EGP, 0), 0)
    var previous = seed; var current: StockRelationalBody.ValueEvent? = null; var eventId: DomainId? = null
    var eventProducer: StreamedProducerIdentityV2? = null
    var slices = 0L; var sliceQuantity = stockZeroQuantity(unit); var sliceValue = Money(Currency.EGP, 0); var rows = 0L
    fun closeEvent() {
        val event = current ?: return
        localStockCheck(slices == event.sliceCount && slices > 0 && sliceQuantity ==
            (if (event.quantityDelta.scaled < 0) -event.quantityDelta else event.quantityDelta) &&
            sliceValue == (if (event.quantityDelta.scaled < 0) -event.originalValueDelta else event.originalValueDelta))
    }
    read.range(StockQueryV2.PoolValues(LocalStockRelationalProfileV2.poolKey(pool).id)) { row ->
        rows = stockCheckedAdd(rows, 1)
        if (row.body !is StockRelationalBody.IssueSlice)
            localStockCheck(row.generation == Revision(1) && row.previous == null, LocalStockFailure.UNSUPPORTED_VALUE_LINEAGE)
        when (val body = row.body) {
            is StockRelationalBody.IssueSlice -> {
                val original = body.value
                localStockCheck(current == null && original.allocation.position.pool == pool)
                stockOriginalSliceEvent(read, row)
                val issue = read.normal(LocalStockRelationalProfileV2.issueKey(original.issueId))?.body as? StockRelationalBody.Issue ?: inconsistentStock()
                localStockCheck(issue.value.id == original.issueId)
                if (issue.value.purpose == ValueIssuePurpose.RETAIL && issue.value.revision.value > 1L)
                    requireStockRetailIssueHistory(read, original.issueId)
                else if (original.restoredQuantity.scaled == 0L) localStockCheck(row.generation == Revision(1) &&
                    issue.value.disposition != KitchenDisposition.CANCELLED_BEFORE_START)
                else {
                    val source = stockRestoredIssueSource(read, original.issueId)
                    requireStockRestoredSliceHistory(read, row, source)
                }
            }
            is StockRelationalBody.ValueEvent -> {
                closeEvent()
                localStockCheck(body.pool == pool && body.before == previous && body.ordinal == stockCheckedAdd(previous.lastEventOrdinal, 1))
                val delta = when (body.event) {
                    StockValueEventKind.OPENING, StockValueEventKind.RECEIVE, StockValueEventKind.COUNT_SURPLUS -> {
                        localStockCheck(body.issueId == null && body.sliceCount == 1L && body.originalValueDelta == body.carryingValueDelta)
                        InventoryValuationProjection.receive(previous, body.quantityDelta, body.originalValueDelta)
                    }
                    StockValueEventKind.COUNT_SHORTAGE -> {
                        localStockCheck(body.issueId == null && body.sliceCount == 1L && body.originalValueDelta == body.carryingValueDelta)
                        InventoryValuationProjection.issue(previous, -body.quantityDelta)
                    }
                    StockValueEventKind.TRANSFER_OUT, StockValueEventKind.TRANSFER_IN -> {
                        val edge = requireStockTransferValuePair(read, row)
                        if (body.event == StockValueEventKind.TRANSFER_OUT) InventoryValuationProjection.issue(previous, edge.quantity)
                        else InventoryValuationProjection.receive(previous, edge.quantity, edge.originalValue)
                    }
                    StockValueEventKind.ISSUE -> {
                        localStockCheck(body.issueId == body.sourceId && body.originalValueDelta == body.carryingValueDelta && body.sliceCount > 0)
                        InventoryValuationProjection.issue(previous, -body.quantityDelta)
                    }
                    StockValueEventKind.RESTORE -> {
                        val issueId = body.issueId ?: inconsistentStock()
                        val issue = (read.normal(LocalStockRelationalProfileV2.issueKey(issueId))?.body as? StockRelationalBody.Issue)?.value
                            ?: inconsistentStock()
                        if (issue.purpose == ValueIssuePurpose.RETAIL) {
                            requireStockRetailIssueHistory(read, issueId)
                            requireStockRetailHistoryEvent(read, row)
                        } else {
                            localStockCheck(body.sourceId != issueId && stockRestoredIssueSource(read, issueId) == body.sourceId &&
                                body.originalValueDelta == body.carryingValueDelta && body.quantityDelta.scaled > 0)
                            val issued = read.normal(stockValueEventKey(pool, issueId))?.body as? StockRelationalBody.ValueEvent ?: inconsistentStock()
                            localStockCheck(issued.event == StockValueEventKind.ISSUE && issued.issueId == issueId && issued.sourceId == issueId &&
                                body.quantityDelta == -issued.quantityDelta && body.originalValueDelta == -issued.originalValueDelta &&
                                body.sliceCount == issued.sliceCount && body.ordinal > issued.ordinal)
                        }
                        InventoryValuationProjection.receive(previous, body.quantityDelta, body.originalValueDelta)
                    }
                    else -> throw LocalStockRejected(LocalStockFailure.UNSUPPORTED_VALUE_LINEAGE)
                }
                localStockCheck(body.after == delta.after && body.quantityDelta == delta.quantity && body.carryingValueDelta == delta.value)
                previous = body.after; current = body; eventId = row.key.id; eventProducer = row.producer
                slices = 0; sliceQuantity = stockZeroQuantity(unit); sliceValue = Money(Currency.EGP, 0)
            }
            is StockRelationalBody.ValueSlice -> {
                val event = current ?: inconsistentStock()
                localStockCheck(body.pool == pool && body.sourceId == event.sourceId && body.eventId == eventId && body.eventOrdinal == event.ordinal &&
                    body.issueId == event.issueId && body.quantity.unitId == unit.unitId && body.quantity.scale == unit.scale && row.producer == eventProducer)
                if (body.issueId != null) {
                    val originalRow = read.normal(LocalStockRelationalProfileV2.issueSliceKey(body.issueId, body.allocationId)) ?: inconsistentStock()
                    val original = originalRow.body as? StockRelationalBody.IssueSlice ?: inconsistentStock()
                    localStockCheck(original.value.issueId == body.issueId && original.value.allocation.position.pool == pool &&
                        original.value.allocation.quantity.unitId == body.quantity.unitId &&
                        original.value.allocation.quantity.scale == body.quantity.scale)
                    when (event.event) {
                        StockValueEventKind.ISSUE -> {
                            localStockCheck(body.sourceId == body.issueId && original.value.allocation.quantity == body.quantity &&
                                original.value.originalValue == body.originalValue)
                            stockOriginalSliceEvent(read, originalRow)
                        }
                        StockValueEventKind.RESTORE -> {
                            val issue = (read.normal(LocalStockRelationalProfileV2.issueKey(body.issueId))?.body
                                as? StockRelationalBody.Issue)?.value ?: inconsistentStock()
                            if (issue.purpose == ValueIssuePurpose.RETAIL) requireStockRetailSliceHistory(read, originalRow)
                            else {
                                localStockCheck(original.value.allocation.quantity == body.quantity && original.value.originalValue == body.originalValue)
                                requireStockRestoredSliceHistory(read, originalRow, event.sourceId)
                            }
                        }
                        else -> throw LocalStockRejected(LocalStockFailure.UNSUPPORTED_VALUE_LINEAGE)
                    }
                }
                slices = stockCheckedAdd(slices, 1); sliceQuantity += body.quantity; sliceValue += body.originalValue
            }
            is StockRelationalBody.TransferEdge -> {
                // The index orders a transfer edge before its event at the same ordinal.
                // Validate the exact pair directly, without advancing the local pool frontier.
                val event = read.normal(AggregateKey(StockRelationalKindsV2.VALUE_EVENT, body.eventId)) ?: inconsistentStock()
                localStockCheck(body.pool == pool && requireStockTransferValuePair(read, event) == body)
            }
            else -> throw LocalStockRejected(LocalStockFailure.UNSUPPORTED_VALUE_LINEAGE)
        }
    }
    closeEvent()
    if (before == null) localStockCheck(positions == 0L && rows == 0L && previous == seed)
    else localStockCheck(positions > 0L && previous.lastEventOrdinal > 0L && previous == before && before.onHand == quantity)
}

/** Genuine writer on the actual prepared command's owner loan; no committed inventory prefix. */
internal suspend fun receiveStock(repository: LocalStockRepository, authority: LocalStockAuthorityReader,
    view: CommandReadView, context: CommandContext, sink: StreamedCommandSink, now: UtcInstant,
    request: LocalStockRelationalCommand): StreamedMutationDecision.Apply {
    localStockCheck(context.key == LocalStockRelationalProfileV2.COMMAND && context.operationId == stockReceivingOperation(request))
    val read = repository.receivingRelationalRead(view, context, sink)
    val inputs = currentReceivingInputs(repository, authority, read, request)
    val plan = receiptPlan(inputs, CurrentReceiptAccess(repository, read, context, now))
    val work = StockPostingBuilder(read, context, sink)
    for ((_, body) in plan.records) work.put(body)
    for ((key, owner) in plan.claims) work.acquire(key, owner)
    for (draft in plan.documents) sink.change(AggregateChange(draft.key, draft.before?.revision, stockSchema(draft.key.kind, draft.body),
        stockRecord(context.scope, draft.key, draft.before?.revision, StockProducerWire.from(context), draft.body, draft.before)))
    sink.effect(StreamedEffectV2(STOCK_RECEIVING_EFFECT_V2, inputs.input.receiptId, LocalStockRelationalCommandsV2.encode(request)))
    for (resource in plan.companionResources) sink.resource(resource)
    return work.finish(plan.result, plan.deadline)
}

/** Only distinguishes the real all-refused receipt from an empty administrative sweep. The
 * complete original producer below still validates literal request, every claim/document,
 * effects/resources and sources. This is NOT a count-only accepting producer certificate. */
internal suspend fun sinkReceivingCommand(context: CommandContext, sink: StreamedCommandSink): Boolean {
    var found = false
    sink.frames(StreamedSectionV2.EFFECTS) { frame ->
        val effect = (frame as? RetainedCommandFrameV2.Effect)?.value ?: inconsistentStock()
        if (effect.kind == STOCK_RECEIVING_EFFECT_V2) {
            val request = LocalStockRelationalCommandsV2.decode(effect.payload)
            localStockCheck(!found && stockReceivingOperation(request) == context.operationId && effect.id == stockReceivingInput(request).receiptId)
            found = true
        }
    }
    return found
}

/** Full literal historical transition, including signed catalog/UOM and actual retained whole
 * PO approval via the installed production repository. No current re-read supplies old prices,
 * conversion/qualification or custody, and no chosen subset of sources/effects is accepted. */
internal suspend fun requireReceivingStockProducer(original: RetainedStreamedCommand,
    stock: LocalStockRelationalInspection, authority: LocalStockRelationalAuthorityReader,
    request: LocalStockRelationalCommand) {
    localStockCheck(original.identity.producer.command == LocalStockRelationalProfileV2.COMMAND &&
        stockReceivingOperation(request) == stock.operationId && LocalStockRelationalCommandsV2.encode(request) == original.normalized)
    val inputs = retainedReceivingInputs(original, authority, request)
    val read = RetainedReceiptAccess(stock)
    val plan = receiptPlan(inputs, read)
    requireScalarStockPlan(original, stock, plan, STOCK_RECEIVING_EFFECT_V2, inputs.input.receiptId)
    // Forward evaluation demanded every literal required source. Reverse evaluation accounts for
    // EACH actual source by exact observation identity. Repeated bounded reads deliberately avoid
    // a stock/history-sized Set; performance is unqualified until separate owner execution.
    original.frames { frame ->
        val actual = (frame as? RetainedCommandFrameV2.Source)?.value ?: return@frames
        localStockCheck(actual !is StreamedSourceObservationV2.EstateMember && actual !is StreamedSourceObservationV2.EstateEnd)
        var used = false
        receiptPlan(inputs, RetainedReceiptAccess(stock) { if (it == actual) used = true })
        localStockCheck(used, LocalStockFailure.STORAGE_INCONSISTENT)
    }
    stock.requireResources(plan.companionResources)
}

/** Native history calls this only after the complete installed receiving producer has joined.
 * Reuse its exact retained authority and BEFORE sources; no persisted deadline header is authority. */
internal suspend fun retainedReceivingStockDeadline(stock: LocalStockRelationalInspection,
    authority: LocalStockRelationalAuthorityReader, request: LocalStockRelationalCommand): UtcInstant? =
    receiptPlan(retainedReceivingInputs(stock.original, authority, request), RetainedReceiptAccess(stock)).deadline

/** Whole literal scalar-document plan check shared by bounded receiving and inspection. */
internal suspend fun requireScalarStockPlan(original: RetainedStreamedCommand,
    stock: LocalStockRelationalInspection, plan: StockReceiptPlan, effectKind: String, effectId: DomainId) {
    localStockCheck(original.result == plan.result && (plan.deadline == null || stock.recordedAt < plan.deadline))
    var writes = 0L; var claims = 0L; var rowEffects = 0L; var receiptEffects = 0L
    original.frames { frame -> when (frame) {
        is RetainedCommandFrameV2.Write -> {
            val value = frame.value
            if (value.key.kind == StockRelationalKindsV2.POSTING) {
                localStockCheck(value.key.id.value == stock.operationId.value && value.storageGeneration == Revision(1))
            } else if (value.key.kind in StockRelationalKindsV2.all) {
                val expected = plan.records[value.key] ?: inconsistentStock()
                val record = stock.write(value.key) ?: inconsistentStock()
                val before = stock.before(value.key)
                localStockCheck(record.body == expected && record.producer == original.identity.producer &&
                    record.generation == (before?.generation?.next() ?: Revision(1)) && record.previous == before?.let {
                        StockPreviousWire(it.producer.operationId, it.generation, it.digest.value) })
            } else {
                val draft = plan.documents.singleOrNull { it.key == value.key } ?: inconsistentStock()
                val row = original.writeRow(value.key) ?: inconsistentStock()
                localStockCheck(row.revision == value.storageGeneration && row.schema == value.schema && row.payload == value.payload && row.digest == value.payloadDigest)
                val record = stockRecord(row, stock.scope)
                val producer = original.identity.producer
                localStockCheck(record.body == draft.body && record.storageRevision == (draft.before?.revision?.next() ?: Revision(1)) &&
                    record.producer == StockProducerWire(producer.operationId, producer.command.name, producer.command.version,
                        producer.originalActorId, producer.semanticDigest.value) && record.previous == draft.before?.let {
                        StockPreviousWire(stockRecord(it, stock.scope).producer.operationId, it.revision, it.digest.value) })
            }
            writes = stockCheckedAdd(writes, 1)
        }
        is RetainedCommandFrameV2.Claim -> {
            localStockCheck(frame.value.action == RetainedClaimAction.ACQUIRE && plan.claims[frame.value.key] == frame.value.ownerId &&
                stock.observedClaim(frame.value.key) == null)
            claims = stockCheckedAdd(claims, 1)
        }
        is RetainedCommandFrameV2.Effect -> when (frame.value.kind) {
            LocalStockRelationalProfileV2.EFFECT_KIND -> {
                val value = stockDecode(frame.value.payload, StockRowEffectV2Wire.serializer())
                val key = AggregateKey(value.kind, value.id)
                val after = stock.write(key) ?: inconsistentStock(); val before = stock.before(key)
                localStockCheck(key in plan.records && frame.value.id == rowEffectId(key) &&
                    value == StockRowEffectV2Wire(key.kind, key.id, before?.generation, before?.digest?.value, after.digest.value))
                rowEffects = stockCheckedAdd(rowEffects, 1)
            }
            effectKind -> {
                localStockCheck(frame.value.id == effectId && frame.value.payload == original.normalized)
                receiptEffects = stockCheckedAdd(receiptEffects, 1)
            }
            else -> inconsistentStock()
        }
        is RetainedCommandFrameV2.Job -> inconsistentStock()
        else -> Unit
    } }
    localStockCheck(writes == plan.records.size.toLong() + plan.documents.size.toLong() + 1L && claims == plan.claims.size.toLong() &&
        rowEffects == plan.records.size.toLong() && receiptEffects == 1L)
}
