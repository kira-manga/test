@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.ukkera.pos.inventory.store

import com.ukkera.pos.catalog.CatalogVersionRef
import com.ukkera.pos.catalog.ProductUnitConversion
import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.inventory.StockCustody
import com.ukkera.pos.inventory.StockOwnership
import com.ukkera.pos.inventory.StockPoolKey
import com.ukkera.pos.inventory.StockPositionKey
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitConversionRate
import com.ukkera.pos.types.UtcInstant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal object LocalStockCommandCodec {
    fun poReceipt(value: LocalPoReceiptRequestV1): StoredBytes = stockEncode(StockPoReceiptRequestWire.from(value), StockPoReceiptRequestWire.serializer(), 32_768)
    fun poReceipt(bytes: StoredBytes): LocalPoReceiptRequestV1 = stockDecode(bytes, StockPoReceiptRequestWire.serializer(), 32_768).restore()
    fun receipt(value: LocalGoodsReceiptRequest): StoredBytes = stockEncode(StockReceiptRequestWire.from(value), StockReceiptRequestWire.serializer(), 32_768)
    fun receipt(bytes: StoredBytes): LocalGoodsReceiptRequest = stockDecode(bytes, StockReceiptRequestWire.serializer(), 32_768).restore()
    fun inspection(value: LocalInspectionReleaseRequest): StoredBytes = stockEncode(StockInspectionRequestWire.from(value), StockInspectionRequestWire.serializer(), 32_768)
    fun inspection(bytes: StoredBytes): LocalInspectionReleaseRequest = stockDecode(bytes, StockInspectionRequestWire.serializer(), 32_768).restore()
    fun countCapture(value: LocalCountCaptureRequest): StoredBytes = stockEncode(StockCountCaptureWire.from(value), StockCountCaptureWire.serializer(), 32_768)
    fun countCapture(bytes: StoredBytes): LocalCountCaptureRequest = stockDecode(bytes, StockCountCaptureWire.serializer(), 32_768).restore()
    fun countPost(value: LocalCountPostRequest): StoredBytes = stockEncode(StockCountPostWire.from(value), StockCountPostWire.serializer(), 32_768)
    fun countPost(bytes: StoredBytes): LocalCountPostRequest = stockDecode(bytes, StockCountPostWire.serializer(), 32_768).restore()
    fun recall(value: LocalLotRecallRequest): StoredBytes = stockEncode(StockRecallWire.from(value), StockRecallWire.serializer(), 32_768)
    fun recall(bytes: StoredBytes): LocalLotRecallRequest = stockDecode(bytes, StockRecallWire.serializer(), 32_768).restore()
    fun transfer(value: LocalWarehouseTransferRequest): StoredBytes = stockEncode(StockTransferRequestWire.from(value), StockTransferRequestWire.serializer(), 32_768)
    fun transfer(bytes: StoredBytes): LocalWarehouseTransferRequest = stockDecode(bytes, StockTransferRequestWire.serializer(), 32_768).restore()
    fun page(id: DomainId?, after: DomainId?, limit: Int): StoredBytes {
        localStockCheck(limit in 1..LocalStockStoreProfile.MAX_READ_ROWS)
        return stockEncode(StockPageWire(id, after, limit), StockPageWire.serializer())
    }
    fun page(bytes: StoredBytes): StockPageWire = stockDecode(bytes, StockPageWire.serializer()).also {
        localStockCheck(it.limit in 1..LocalStockStoreProfile.MAX_READ_ROWS)
    }
    fun document(id: DomainId): StoredBytes = stockEncode(StockDocumentReadWire(id), StockDocumentReadWire.serializer())
    fun document(bytes: StoredBytes): DomainId = stockDecode(bytes, StockDocumentReadWire.serializer()).id
    fun position(skuId: DomainId, positionId: DomainId): StoredBytes = stockEncode(StockExactPositionReadWire(skuId, positionId), StockExactPositionReadWire.serializer())
    fun position(bytes: StoredBytes): StockExactPositionReadWire = stockDecode(bytes, StockExactPositionReadWire.serializer())
    fun result(value: LocalStockResult): StoredBytes = stockEncode(StockResultWire(value.kind.name, value.documentId,
        value.items.map(StockItemVersionWire::from), value.failure?.name), StockResultWire.serializer())
    fun decodeResult(bytes: StoredBytes): LocalStockResult = stockDecode(bytes, StockResultWire.serializer()).let {
        val kind = stockEnum<LocalStockResultKind>(it.kind)
        val failure = it.failure?.let { code -> stockEnum<LocalStockFailure>(code) }
        localStockCheck((kind == LocalStockResultKind.REJECTED) == (failure != null))
        LocalStockResult(kind, it.documentId, it.items.map(StockItemVersionWire::restore), failure)
    }
    fun normalize(key: CommandKey, input: StoredBytes): StoredBytes = when (key) {
        LocalStockStoreProfile.RECEIVE_PO -> poReceipt(poReceipt(input))
        LocalStockStoreProfile.RECEIVE, LocalStockStoreProfile.OPENING,
        LocalStockStoreProfile.RECEIVE_SERIAL, LocalStockStoreProfile.OPENING_SERIAL -> receipt(input).let {
            requireSerialCommandVersion(key, it.lines.any { line -> line.serialId != null }); receipt(it) }
        LocalStockStoreProfile.INSPECT, LocalStockStoreProfile.INSPECT_SERIAL -> inspection(input).let {
            requireSerialCommandVersion(key, it.serialId != null); inspection(it) }
        LocalStockStoreProfile.COUNT_CAPTURE, LocalStockStoreProfile.COUNT_CAPTURE_SERIAL -> countCapture(input).let {
            requireSerialCommandVersion(key, it.position.serialId != null); countCapture(it) }
        LocalStockStoreProfile.COUNT_POST, LocalStockStoreProfile.COUNT_POST_SERIAL -> countPost(input).let {
            requireSerialCommandVersion(key, it.serialId != null); countPost(it) }
        LocalStockStoreProfile.RECALL, LocalStockStoreProfile.RECALL_SERIAL -> recall(recall(input))
        LocalStockStoreProfile.TRANSFER, LocalStockStoreProfile.TRANSFER_SERIAL -> transfer(input).let {
            requireSerialCommandVersion(key, it.source.serialId != null); transfer(it) }
        LocalStockStoreProfile.TRANSFER_OWNED -> transfer(input).let {
            localStockCheck(it.source.supplierId == null, LocalStockFailure.UNSUPPORTED_VALUE_LINEAGE); transfer(it) }
        else -> throw LocalStockRejected(LocalStockFailure.MALFORMED)
    }
}

private fun requireSerialCommandVersion(key: CommandKey, serial: Boolean) {
    val expected = if (serial) 2 else 1
    localStockCheck(key.version == expected, LocalStockFailure.SERIAL_WORKFLOW_REQUIRED)
}

@Serializable internal data class StockScopeWire(val organizationId: DomainId, val companyId: DomainId, val branchId: DomainId) {
    fun restore(): BranchScope = BranchScope(organizationId, companyId, branchId)
    companion object { fun from(scope: BranchScope): StockScopeWire = StockScopeWire(scope.organizationId, scope.companyId, scope.branchId) }
}

@Serializable internal data class StockVersionWire(val id: DomainId, val revision: Revision) {
    fun restore(): CatalogVersionRef = CatalogVersionRef(id, revision)
    companion object { fun from(value: CatalogVersionRef): StockVersionWire = StockVersionWire(value.id, value.revision) }
}

@Serializable internal data class StockItemVersionWire(val skuId: DomainId, val revision: Revision?) {
    fun restore(): LocalStockItemVersion = LocalStockItemVersion(skuId, revision)
    companion object { fun from(value: LocalStockItemVersion): StockItemVersionWire = StockItemVersionWire(value.skuId, value.storageRevision) }
}

@Serializable internal data class StockPositionRefWire(
    val skuId: DomainId, val warehouseId: DomainId, val lotId: DomainId?, val custody: String, val supplierId: DomainId?,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val serialId: DomainId? = null,
) {
    fun restore(): LocalStockPositionRef = LocalStockPositionRef(skuId, warehouseId, lotId, stockEnum(custody), supplierId, serialId)
    fun key(scope: BranchScope): StockPositionKey = restore().inScope(scope)
    companion object {
        fun from(value: LocalStockPositionRef): StockPositionRefWire = StockPositionRefWire(value.skuId, value.warehouseId,
            value.lotId, value.custody.name, value.supplierId, value.serialId)
        fun from(value: StockPositionKey): StockPositionRefWire = from(LocalStockPositionRef(value.pool.skuId, value.pool.warehouseId,
            value.lotId, value.custody, (value.pool.ownership as? StockOwnership.SupplierOwned)?.supplierId, value.serialId))
    }
}

@Serializable internal data class StockPoolRefWire(val skuId: DomainId, val warehouseId: DomainId) {
    fun key(scope: BranchScope): StockPoolKey = LocalStockPositionRef(skuId, warehouseId, null, StockCustody.RELEASED).inScope(scope).pool
    companion object { fun from(value: StockPoolKey): StockPoolRefWire {
        localStockCheck(value.ownership == StockOwnership.CompanyOwned, LocalStockFailure.STORAGE_INCONSISTENT)
        return StockPoolRefWire(value.skuId, value.warehouseId)
    } }
}

@Serializable internal data class StockReceiptLineWire(
    val lineId: DomainId, val allocationId: DomainId, val sourceId: DomainId, val skuId: DomainId, val warehouseId: DomainId,
    val quantity: Quantity, val conversion: StockVersionWire?, val lotId: DomainId?, val expiresAt: UtcInstant?,
    val disposition: String, val originalValue: Money?,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val serialId: DomainId? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val serialCode: String? = null,
) {
    fun restore(): LocalGoodsReceiptLine = LocalGoodsReceiptLine(lineId, allocationId, sourceId, skuId, warehouseId,
        quantity, conversion?.restore(), lotId, expiresAt, stockEnum(disposition), originalValue, serialId, serialCode).also {
        localStockCheck((serialId == null) == (serialCode == null), LocalStockFailure.INVALID_SERIAL)
        serialCode?.let(::requireStockSerialCode)
        localStockCheck(quantity.scaled > 0, LocalStockFailure.INVALID_QUANTITY)
        localStockCheck((it.disposition == LocalReceiptDisposition.RELEASED || it.disposition == LocalReceiptDisposition.QUARANTINE) ==
            (originalValue != null))
        localStockCheck(originalValue == null || (originalValue.currency == Currency.EGP && originalValue.minor >= 0))
        localStockCheck(lotId != null || expiresAt == null, LocalStockFailure.INVALID_LOT)
    }
    companion object {
        fun from(value: LocalGoodsReceiptLine): StockReceiptLineWire = StockReceiptLineWire(value.lineId, value.allocationId, value.sourceId,
            value.skuId, value.warehouseId, value.quantity, value.conversion?.let(StockVersionWire::from), value.lotId, value.expiresAt,
            value.disposition.name, value.originalValue, value.serialId, value.serialCode).also { it.restore() }
    }
}

@Serializable internal data class StockReceiptRequestWire(
    val receiptId: DomainId, val basisId: DomainId, val expectedBasisRevision: Revision, val items: List<StockItemVersionWire>,
    val lines: List<StockReceiptLineWire>, val reasonCode: String,
) {
    fun restore(): LocalGoodsReceiptRequest = LocalGoodsReceiptRequest(receiptId, basisId, expectedBasisRevision,
        items.map(StockItemVersionWire::restore), lines.map(StockReceiptLineWire::restore), reasonCode)
    companion object {
        fun from(value: LocalGoodsReceiptRequest): StockReceiptRequestWire = StockReceiptRequestWire(value.receiptId,
            value.basisId, value.expectedBasisRevision, value.items.sortedBy { it.skuId.value }.map(StockItemVersionWire::from),
            value.lines.map(StockReceiptLineWire::from), value.reasonCode)
    }
}

@Serializable internal data class StockInspectionRequestWire(
    val inspectionId: DomainId, val receiptId: DomainId, val lineId: DomainId, val expectedReceiptRevision: Revision,
    val expectedItemRevision: Revision, val quantity: Quantity, val evidenceId: DomainId, val reasonCode: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val serialId: DomainId? = null,
) {
    fun restore(): LocalInspectionReleaseRequest {
        localStockReason(reasonCode); localStockCheck(quantity.scaled > 0, LocalStockFailure.INVALID_QUANTITY)
        return LocalInspectionReleaseRequest(inspectionId, receiptId, lineId, expectedReceiptRevision, expectedItemRevision, quantity, evidenceId, reasonCode, serialId)
    }
    companion object { fun from(v: LocalInspectionReleaseRequest): StockInspectionRequestWire = StockInspectionRequestWire(v.inspectionId,
        v.receiptId, v.lineId, v.expectedReceiptRevision, v.expectedItemRevision, v.quantity, v.evidenceId, v.reasonCode, v.serialId).also { it.restore() } }
}

@Serializable internal data class StockCountCaptureWire(val countId: DomainId, val position: StockPositionRefWire,
    val expectedItemRevision: Revision, val reasonCode: String) {
    fun restore(): LocalCountCaptureRequest { localStockReason(reasonCode); return LocalCountCaptureRequest(countId, position.restore(), expectedItemRevision, reasonCode) }
    companion object { fun from(v: LocalCountCaptureRequest): StockCountCaptureWire = StockCountCaptureWire(v.countId,
        StockPositionRefWire.from(v.position), v.expectedItemRevision, v.reasonCode).also { it.restore() } }
}

@Serializable internal data class StockCountPostWire(val countId: DomainId, val expectedCountRevision: Revision,
    val counted: Quantity, val reasonCode: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val surplusBasisId: DomainId? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val expectedSurplusBasisRevision: Revision? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val serialId: DomainId? = null) {
    fun restore(): LocalCountPostRequest {
        localStockReason(reasonCode)
        localStockCheck(counted.scaled >= 0 && (surplusBasisId == null) == (expectedSurplusBasisRevision == null))
        return LocalCountPostRequest(countId, expectedCountRevision, counted, reasonCode, surplusBasisId, expectedSurplusBasisRevision, serialId)
    }
    companion object { fun from(v: LocalCountPostRequest): StockCountPostWire = StockCountPostWire(v.countId, v.expectedCountRevision,
        v.counted, v.reasonCode, v.surplusBasisId, v.expectedSurplusBasisRevision, v.serialId).also { it.restore() } }
}

@Serializable internal data class StockRecallWire(val skuId: DomainId, val lotId: DomainId, val expectedItemRevision: Revision, val reasonCode: String) {
    fun restore(): LocalLotRecallRequest { localStockReason(reasonCode); return LocalLotRecallRequest(skuId, lotId, expectedItemRevision, reasonCode) }
    companion object { fun from(v: LocalLotRecallRequest): StockRecallWire = StockRecallWire(v.skuId, v.lotId, v.expectedItemRevision, v.reasonCode).also { it.restore() } }
}

@Serializable internal data class StockTransferRequestWire(val transferId: DomainId, val sourceEventId: DomainId, val destinationEventId: DomainId,
    val source: StockPositionRefWire, val destinationWarehouseId: DomainId, val expectedItemRevision: Revision, val quantity: Quantity, val reasonCode: String) {
    fun restore(): LocalWarehouseTransferRequest {
        localStockReason(reasonCode)
        localStockCheck(quantity.scaled > 0 && source.warehouseId != destinationWarehouseId && sourceEventId != destinationEventId)
        return LocalWarehouseTransferRequest(transferId, sourceEventId, destinationEventId, source.restore(), destinationWarehouseId, expectedItemRevision, quantity, reasonCode)
    }
    companion object { fun from(v: LocalWarehouseTransferRequest): StockTransferRequestWire = StockTransferRequestWire(v.transferId,
        v.sourceEventId, v.destinationEventId, StockPositionRefWire.from(v.source), v.destinationWarehouseId, v.expectedItemRevision,
        v.quantity, v.reasonCode).also { it.restore() } }
}

@Serializable internal data class StockPageWire(val id: DomainId?, val after: DomainId?, val limit: Int)
@Serializable internal data class StockDocumentReadWire(val id: DomainId)
@Serializable internal data class StockExactPositionReadWire(val skuId: DomainId, val positionId: DomainId)
@Serializable internal data class StockResultWire(val kind: String, val documentId: DomainId, val items: List<StockItemVersionWire>, val failure: String?)

@Serializable internal data class StockProducerWire(val operationId: OperationId, val command: String, val version: Int,
    val actorId: DomainId, val digest: String) {
    fun commandKey(): CommandKey = CommandKey(command, version)
    companion object { fun from(v: CommandContext): StockProducerWire = StockProducerWire(v.operationId, v.key.name, v.key.version,
        v.originalActorId, v.semanticDigest.value) }
}

@Serializable internal data class StockRecordWire(val version: Int, val scope: StockScopeWire, val kind: String, val id: DomainId,
    val storageRevision: Revision, val producer: StockProducerWire, val previous: StockPreviousWire?, val body: String)

/** Exact retained prior write, not a current/branch revision substituted for row provenance. */
@Serializable internal data class StockPreviousWire(val operationId: OperationId, val revision: Revision, val digest: String)
@Serializable internal data class StockObservedWire(val kind: String, val id: DomainId, val operationId: OperationId, val revision: Revision, val digest: String) {
    fun key(): AggregateKey = AggregateKey(kind, id)
}

@Serializable internal data class StockItemWire(val skuId: DomainId, val unitId: DomainId, val quantityScale: Int,
    val positions: List<StockPositionRefWire>, val pools: List<StockPoolRefWire>, val activeReservationIds: List<DomainId>)

@Serializable internal data class StockPositionWire(val position: StockPositionRefWire, val encoded: String)
@Serializable internal data class StockPageRefWire(val id: DomainId, val creationGeneration: Revision, val bytes: Int, val digest: String)
@Serializable internal data class StockPoolWire(val pool: StockPoolRefWire, val revision: Revision, val quantity: Quantity,
    val carryingValue: Money, val encodedBytes: Int, val encodedDigest: String, val pages: List<StockPageRefWire>,
    /** Old headers/pages stay byte-exact. A linked history has explicit v3 headers AND pages. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val ownedTransferVersion: Int? = null)
@Serializable internal data class StockReservationWire(val revision: Revision, val encoded: String)
@Serializable internal data class StockIssueWire(val revision: Revision, val encoded: String)

@Serializable internal data class StockSourceStampWire(val kind: String, val id: DomainId, val revision: Revision, val digest: String) {
    fun restore(): LocalStockSourceStamp = LocalStockSourceStamp(AggregateKey(kind, id), revision, PayloadDigest(digest))
    companion object { fun from(v: LocalStockSourceStamp): StockSourceStampWire = StockSourceStampWire(v.key.kind, v.key.id, v.storageRevision, v.digest.value) }
}

@Serializable internal data class StockConversionWire(val id: DomainId, val revision: Revision, val productId: DomainId,
    val sourceUnitId: DomainId, val targetUnitId: DomainId, val rate: UnitConversionRate, val massVolumeEvidenceId: DomainId?) {
    companion object { fun from(v: ProductUnitConversion): StockConversionWire = StockConversionWire(v.id, v.revision, v.productId,
        v.sourceUnitId, v.targetUnitId, v.rate, v.massVolumeEvidenceId) }
}

@Serializable internal data class StockReceivedLineWire(val request: StockReceiptLineWire, val baseQuantity: Quantity,
    val conversion: StockConversionWire?, val releasedFromQuarantine: Quantity,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val serialRegistration: StockSerialRegistrationWire? = null)
@Serializable internal data class StockReceiptWire(val receiptId: DomainId, val basisId: DomainId, val basisRevision: Revision,
    val basisKind: String, val supplierId: DomainId?, val approvedBy: DomainId, val policyId: DomainId, val recordedBy: DomainId,
    val receivedAt: UtcInstant, val lines: List<StockReceivedLineWire>, val sources: List<StockSourceStampWire>, val reasonCode: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val poValuation: StockPoReceiptValuationWire? = null)
@Serializable internal data class StockAllowanceWire(val allocationId: DomainId, val basisId: DomainId, val supplierId: DomainId?,
    val skuId: DomainId, val warehouseId: DomainId, val committedQuantity: Quantity, val committedOwnedValue: Money,
    val acceptedQuantity: Quantity, val pendingInspectionQuantity: Quantity)
@Serializable internal data class StockCountWire(val countId: DomainId, val position: StockPositionRefWire, val capturedItemRevision: Revision,
    val capturedQuantity: Quantity, val capturedBy: DomainId, val capturedAt: UtcInstant, val counted: Quantity?, val postedBy: DomainId?,
    val postedAt: UtcInstant?, val reasonCode: String, val postingReasonCode: String?,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val surplusBasis: StockCountSurplusBasisWire? = null)
@Serializable internal data class StockCountSurplusBasisWire(
    val scope: StockScopeWire, val basisId: DomainId, val revision: Revision, val countId: DomainId,
    val countRevision: Revision, val capturedItemRevision: Revision, val position: StockPositionRefWire,
    val capturedQuantity: Quantity, val countedQuantity: Quantity, val surplusValue: Money, val approvedBy: DomainId,
    val policyId: DomainId, val validFrom: UtcInstant, val validUntil: UtcInstant?, val sources: List<StockSourceStampWire>,
) {
    fun restore(): LocalCountSurplusBasis = LocalCountSurplusBasis(scope.restore(), basisId, revision, countId,
        countRevision, capturedItemRevision, position.restore(), capturedQuantity, countedQuantity, surplusValue,
        approvedBy, policyId, validFrom, validUntil, sources.map { it.restore() })
    companion object {
        fun from(v: LocalCountSurplusBasis): StockCountSurplusBasisWire = StockCountSurplusBasisWire(StockScopeWire.from(v.scope),
            v.basisId, v.revision, v.countId, v.countRevision, v.capturedItemRevision, StockPositionRefWire.from(v.position),
            v.capturedQuantity, v.countedQuantity, v.surplusValue, v.approvedBy, v.policyId, v.validFrom, v.validUntil,
            v.sources.sortedWith(compareBy<LocalStockSourceStamp> { it.key.kind }.thenBy { it.key.id.value }).map(StockSourceStampWire::from))
    }
}
@Serializable internal data class StockInspectionWire(val request: StockInspectionRequestWire, val inspectorId: DomainId, val recordedAt: UtcInstant,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val serialRegistration: StockSerialRegistrationWire? = null)
@Serializable internal data class StockTransferWire(val request: StockTransferRequestWire, val transferredBy: DomainId,
    val recordedAt: UtcInstant, val originalValue: Money?,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val owned: StockOwnedTransferWire? = null)

private val stockJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
    coerceInputValues = false
    allowSpecialFloatingPointValues = false
}

internal fun <T> stockEncode(value: T, serializer: KSerializer<T>, maximum: Int = 65_536): StoredBytes = localStockWire {
    val text = stockJson.encodeToString(serializer, value)
    localStockCheck(text.length <= maximum, LocalStockFailure.BOUND_EXCEEDED)
    val bytes = text.encodeToByteArray(throwOnInvalidSequence = true)
    localStockCheck(bytes.size <= maximum, LocalStockFailure.BOUND_EXCEEDED)
    boundStockJson(text)
    StoredBytes(bytes)
}

internal fun <T> stockDecode(bytes: StoredBytes, serializer: KSerializer<T>, maximum: Int = 65_536): T = localStockWire {
    localStockCheck(bytes.size <= maximum, LocalStockFailure.BOUND_EXCEEDED)
    val text = bytes.copyBytes().decodeToString(throwOnInvalidSequence = true)
    boundStockJson(text)
    val value = stockJson.decodeFromString(serializer, text)
    localStockCheck(stockJson.encodeToString(serializer, value) == text, LocalStockFailure.NON_CANONICAL)
    value
}

internal fun <T> stockBody(value: T, serializer: KSerializer<T>): String = stockEncode(value, serializer).copyBytes().decodeToString()
internal fun <T> stockBody(text: String, serializer: KSerializer<T>): T = stockDecode(StoredBytes(text.encodeToByteArray(throwOnInvalidSequence = true)), serializer)

internal fun stockRecord(
    scope: BranchScope,
    key: AggregateKey,
    expected: Revision?,
    producer: StockProducerWire,
    body: String,
    previous: StoredAggregate? = null,
): StoredBytes {
    localStockCheck((expected == null) == (previous == null), LocalStockFailure.STORAGE_INCONSISTENT)
    val predecessor = previous?.let {
        localStockCheck(it.key == key && it.revision == expected, LocalStockFailure.STORAGE_INCONSISTENT)
        StockPreviousWire(stockRecord(it, scope).producer.operationId, it.revision, it.digest.value)
    }
    return stockEncode(StockRecordWire(stockRecordVersion(key.kind, body), StockScopeWire.from(scope), key.kind, key.id,
        (expected ?: Revision(0)).next(), producer, predecessor, body), StockRecordWire.serializer())
}

internal fun stockRecord(row: StoredAggregate, scope: BranchScope): StockRecordWire = localStockWire {
    localStockCheck(sha256(row.payload.copyBytes()) == row.digest,
        LocalStockFailure.STORAGE_INCONSISTENT)
    val stored = stockDecode(row.payload, StockRecordWire.serializer())
    localStockCheck(stored.version == stockRecordVersion(row.key.kind, stored.body) &&
        row.schema == LocalStockStoreProfile.schema(row.key.kind, stored.version) &&
        stored.scope.restore() == scope && stored.kind == row.key.kind && stored.id == row.key.id &&
        stored.storageRevision == row.revision, LocalStockFailure.STORAGE_INCONSISTENT)
    PayloadDigest(stored.producer.digest); stored.producer.commandKey()
    localStockCheck(if (row.revision == Revision(1)) stored.previous == null else
        stored.previous != null && stored.previous.revision.next() == row.revision &&
            stored.previous.operationId != stored.producer.operationId, LocalStockFailure.STORAGE_INCONSISTENT)
    stored.previous?.let { PayloadDigest(it.digest) }
    stored
}

internal inline fun <reified T : Enum<T>> stockEnum(value: String): T = localStockWire { enumValueOf<T>(value) }

internal inline fun <T> localStockWire(block: () -> T): T = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: LocalStockRejected) {
    throw failure
} catch (_: Exception) {
    throw LocalStockRejected(LocalStockFailure.MALFORMED)
}

private fun boundStockJson(text: String) {
    var quoted = false
    var escaped = false
    var containers = 0
    val stack = mutableListOf<Pair<Char, Int>>()
    for (character in text) {
        if (quoted) {
            if (escaped) escaped = false
            else if (character == '\\') escaped = true
            else if (character == '"') quoted = false
        } else when (character) {
            '"' -> quoted = true
            '{', '[' -> {
                localStockCheck(stack.size < 24 && ++containers <= 4_096, LocalStockFailure.BOUND_EXCEEDED)
                stack += character to 0
            }
            '}', ']' -> {
                localStockCheck(stack.isNotEmpty() && stack.last().first == if (character == '}') '{' else '[')
                stack.removeAt(stack.lastIndex)
            }
            ',' -> if (stack.isNotEmpty() && stack.last().first == '[') {
                val size = stack.last().second + 1
                localStockCheck(size < 4_096, LocalStockFailure.BOUND_EXCEEDED)
                stack[stack.lastIndex] = '[' to size
            }
        }
    }
    localStockCheck(!quoted && stack.isEmpty())
}
