package com.ukkera.pos.inventory.store

import com.ukkera.pos.catalog.CatalogVersionRef
import com.ukkera.pos.database.AggregateChange
import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.ClaimChange
import com.ukkera.pos.database.ClaimKey
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.inventory.InventoryPlan
import com.ukkera.pos.inventory.InventoryRecordCodec
import com.ukkera.pos.inventory.InventoryScope
import com.ukkera.pos.inventory.InventorySnapshot
import com.ukkera.pos.inventory.StockCustody
import com.ukkera.pos.inventory.StockOwnership
import com.ukkera.pos.inventory.StockPoolKey
import com.ukkera.pos.inventory.StockPositionKey
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/**
 * Normalized production storage. No whole-branch row, 32-receipt fixture ceiling or absent-state
 * initializer. Bounds limit ONE fully loaded dependency closure/proposal, not branch SKU count.
 * This profile is separate from the historical fixture-only inventory.cut/companion profiles.
 */
public object LocalStockStoreProfile {
    public val OPENING: CommandKey = CommandKey("stock.opening.post", 1)
    public val RECEIVE: CommandKey = CommandKey("purchasing.receipt.post", 1)
    /** Value-free BASE-unit receipt, priced only from the real retained approved PO. Includes serial slices. */
    public val RECEIVE_PO: CommandKey = CommandKey("local.stock.receive-po", 1)
    public val INSPECT: CommandKey = CommandKey("purchasing.inspection.release", 1)
    public val COUNT_CAPTURE: CommandKey = CommandKey("stock.count.capture", 1)
    public val COUNT_POST: CommandKey = CommandKey("stock.count.post", 1)
    public val RECALL: CommandKey = CommandKey("stock.lot.recall", 1)
    public val TRANSFER: CommandKey = CommandKey("stock.warehouse.transfer", 1)
    public val OPENING_SERIAL: CommandKey = CommandKey("stock.opening.post", 2)
    public val RECEIVE_SERIAL: CommandKey = CommandKey("purchasing.receipt.post", 2)
    public val INSPECT_SERIAL: CommandKey = CommandKey("purchasing.inspection.release", 2)
    public val COUNT_CAPTURE_SERIAL: CommandKey = CommandKey("stock.count.capture", 2)
    public val COUNT_POST_SERIAL: CommandKey = CommandKey("stock.count.post", 2)
    public val RECALL_SERIAL: CommandKey = CommandKey("stock.lot.recall", 2)
    public val TRANSFER_SERIAL: CommandKey = CommandKey("stock.warehouse.transfer", 2)
    /** Paired COMPANY-OWNED RELEASED transfer, with or without a serial. Old v1/v2 stay supplier-only. */
    public val TRANSFER_OWNED: CommandKey = CommandKey("stock.warehouse.transfer", 3)
    public val ITEMS: ReadKey = ReadKey("stock.items", 1)
    public val QUANTITIES: ReadKey = ReadKey("stock.quantities", 1)
    public val QUANTITY_POSITION: ReadKey = ReadKey("stock.quantity.position", 1)
    public val COSTS: ReadKey = ReadKey("stock.costs", 1)
    public val RECEIPT: ReadKey = ReadKey("purchasing.receipt", 1)
    public val RECEIPT_COSTS: ReadKey = ReadKey("purchasing.receipt.costs", 1)
    public val COUNT: ReadKey = ReadKey("stock.count", 1)
    public val TRANSFER_DETAIL: ReadKey = ReadKey("stock.transfer", 1)
    public val TRANSFER_COSTS: ReadKey = ReadKey("stock.transfer.costs", 1)

    public const val ITEM: String = "local.stock.item"
    public const val POSITION: String = "local.stock.position"
    public const val POOL: String = "local.stock.pool"
    public const val VALUE_PAGE: String = "local.stock.value-page"
    public const val RESERVATION: String = "local.stock.reservation"
    public const val ISSUE: String = "local.stock.issue"
    public const val POSTING: String = "local.stock.posting"
    public const val RECEIPT_DOCUMENT: String = "local.stock.receipt"
    public const val ALLOWANCE: String = "local.stock.allowance"
    public const val INSPECTION: String = "local.stock.inspection"
    public const val COUNT_DOCUMENT: String = "local.stock.count"
    public const val TRANSFER_DOCUMENT: String = "local.stock.transfer"
    public const val SOURCE_CLAIM: String = "local.stock.source"
    public const val ITEM_CLAIM: String = "local.stock.item-id"
    public const val RESERVATION_CLAIM: String = "local.stock.reservation-id"
    public const val ALLOCATION_CLAIM: String = "local.stock.allocation-id"
    public const val DOCUMENT_CLAIM: String = "local.stock.document-id"
    public const val ALLOWANCE_CLAIM: String = "local.stock.allowance-id"
    public const val RECEIPT_LINE_CLAIM: String = "local.stock.receipt-line"
    public const val COUNT_SURPLUS_CLAIM: String = "local.stock.count-surplus"
    public const val SERIAL_ORIGIN: String = "local.stock.serial-origin"
    public const val SERIAL_ID_CLAIM: String = "local.stock.serial-id"
    public const val SERIAL_CODE_CLAIM: String = "local.stock.serial-code"

    public const val MAX_ITEMS_PER_COMMAND: Int = 16
    public const val MAX_POSITIONS_PER_COMMAND: Int = 256
    public const val MAX_POOLS_PER_COMMAND: Int = 32
    public const val MAX_RESERVATIONS_PER_COMMAND: Int = 64
    public const val MAX_LINES_PER_COMMAND: Int = 16
    public const val MAX_ROW_BYTES: Int = 65_536
    public const val VALUE_PAGE_BYTES: Int = 48 * 1_024
    public const val MAX_POOL_PAGES: Int = 128
    public const val MAX_REQUEST_BYTES: Int = 32_768
    public const val MAX_READ_ROWS: Int = 50

    public val inventoryKinds: Set<String> get() = setOf(ITEM, POSITION, POOL, VALUE_PAGE, RESERVATION, ISSUE, POSTING, SERIAL_ORIGIN)
    public val inventoryClaimNamespaces: Set<String> get() = setOf(SOURCE_CLAIM, ITEM_CLAIM, RESERVATION_CLAIM, ALLOCATION_CLAIM)
    public fun schema(kind: String, version: Int = 1): String = "$kind/v$version"
    public fun itemKey(skuId: DomainId): AggregateKey = AggregateKey(ITEM, skuId)
    public fun positionKey(position: StockPositionKey): AggregateKey = AggregateKey(POSITION,
        localStockId("position", InventoryRecordCodec.positionKey(position)))
    public fun poolKey(pool: StockPoolKey): AggregateKey = AggregateKey(POOL, localStockId("pool", InventoryRecordCodec.poolKey(pool)))
    public fun reservationKey(id: DomainId): AggregateKey = AggregateKey(RESERVATION, id)
    public fun issueKey(id: DomainId): AggregateKey = AggregateKey(ISSUE, id)
    public fun sourceClaim(id: DomainId): ClaimKey = ClaimKey(SOURCE_CLAIM, id.value)
    public fun itemClaim(id: DomainId): ClaimKey = ClaimKey(ITEM_CLAIM, id.value)
    public fun reservationClaim(id: DomainId): ClaimKey = ClaimKey(RESERVATION_CLAIM, id.value)
    public fun allocationClaim(id: DomainId): ClaimKey = ClaimKey(ALLOCATION_CLAIM, id.value)
    public fun documentClaim(id: DomainId): ClaimKey = ClaimKey(DOCUMENT_CLAIM, id.value)
    public fun allowanceClaim(id: DomainId): ClaimKey = ClaimKey(ALLOWANCE_CLAIM, id.value)
    public fun receiptLineClaim(id: DomainId): ClaimKey = ClaimKey(RECEIPT_LINE_CLAIM, id.value)
    public fun countSurplusClaim(id: DomainId): ClaimKey = ClaimKey(COUNT_SURPLUS_CLAIM, id.value)
    public fun serialKey(id: DomainId): AggregateKey = AggregateKey(SERIAL_ORIGIN, id)
    public fun serialClaim(id: DomainId): ClaimKey = ClaimKey(SERIAL_ID_CLAIM, id.value)
    public fun serialCodeClaim(code: String): ClaimKey = ClaimKey(SERIAL_CODE_CLAIM, localStockId("serial-code", code).value)
}

public enum class LocalStockFailure {
    MALFORMED, NON_CANONICAL, BOUND_EXCEEDED, STORAGE_INCONSISTENT, PRODUCER_UNAVAILABLE,
    AUTHORITY_UNAVAILABLE, WRONG_SCOPE, STALE_REVISION, SOURCE_ALREADY_USED, STOCK_MISSING,
    ITEM_UNAVAILABLE, WAREHOUSE_UNAVAILABLE, BASIS_UNAVAILABLE, BASIS_EXPIRED, ALLOWANCE_EXCEEDED,
    INVALID_QUANTITY, INVALID_UNIT, INVALID_LOT, SERIAL_WORKFLOW_REQUIRED, INSPECTION_REQUIRED,
    INSUFFICIENT_STOCK, UNSAFE_STOCK, COUNT_REQUIRES_RECOUNT, COUNT_SURPLUS_REQUIRES_APPROVAL,
    OPENING_ALREADY_ESTABLISHED, ORIGINAL_RECEIPT_REQUIRED, UNSUPPORTED_VALUE_LINEAGE,
    INVALID_SERIAL, SERIAL_REGISTRATION_REQUIRED,
}

/** Stable reason only; SQL, private source data and parser diagnostics are not error messages. */
public class LocalStockRejected(public val failure: LocalStockFailure) : IllegalArgumentException(failure.name)

/** Owned loan-local resource admission only; stored JSON/row bounds NEVER throw this type. */
public class LocalStockHydrationCapacityUnavailable : RuntimeException()

/** Null means an explicitly requested NEW item, not an inference from a failed read. */
public data class LocalStockItemVersion(public val skuId: DomainId, public val storageRevision: Revision?)

public data class LocalStockAllocationRequest(
    public val lineId: DomainId,
    public val skuId: DomainId,
    public val warehouseId: DomainId,
    public val baseQuantity: Quantity,
)

public data class LocalStockPositionRef(
    public val skuId: DomainId,
    public val warehouseId: DomainId,
    public val lotId: DomainId?,
    public val custody: StockCustody,
    public val supplierId: DomainId? = null,
    public val serialId: DomainId? = null,
) {
    public fun inScope(scope: BranchScope): StockPositionKey = StockPositionKey(
        StockPoolKey(InventoryScope(scope.companyId, scope.branchId), skuId, warehouseId,
            supplierId?.let { StockOwnership.SupplierOwned(it) } ?: StockOwnership.CompanyOwned), lotId, custody, serialId)
}

public enum class LocalReceiptDisposition { RELEASED, QUARANTINE, REJECTED_HELD, REFUSED }

/**
 * One identified physical disposition slice. Quantity is the recorded purchase UOM, never a
 * caller-selected conversion rate. Released/quarantined slices carry owned value; rejected-held
 * supplier custody and refused-at-door slices must not. Invoice/settlement fields do not exist.
 */
public data class LocalGoodsReceiptLine(
    public val lineId: DomainId,
    public val allocationId: DomainId,
    public val sourceId: DomainId,
    public val skuId: DomainId,
    public val warehouseId: DomainId,
    public val quantity: Quantity,
    public val conversion: CatalogVersionRef?,
    public val lotId: DomainId?,
    public val expiresAt: UtcInstant?,
    public val disposition: LocalReceiptDisposition,
    public val originalValue: Money?,
    public val serialId: DomainId? = null,
    public val serialCode: String? = null,
)

public class LocalGoodsReceiptRequest(
    public val receiptId: DomainId,
    public val basisId: DomainId,
    public val expectedBasisRevision: Revision,
    items: List<LocalStockItemVersion>,
    lines: List<LocalGoodsReceiptLine>,
    public val reasonCode: String,
) {
    init {
        localStockCheck(lines.size in 1..LocalStockStoreProfile.MAX_LINES_PER_COMMAND, LocalStockFailure.BOUND_EXCEEDED)
        localStockCheck(items.size in 1..LocalStockStoreProfile.MAX_ITEMS_PER_COMMAND, LocalStockFailure.BOUND_EXCEEDED)
        localStockCheck(items.map { it.skuId }.distinct().size == items.size && items.map { it.skuId }.toSet() == lines.map { it.skuId }.toSet())
        localStockCheck(lines.map { it.lineId }.distinct().size == lines.size && lines.map { it.sourceId }.distinct().size == lines.size)
        localStockCheck(lines.mapNotNull { it.serialId }.distinct().size == lines.count { it.serialId != null } &&
            lines.mapNotNull { it.serialCode }.distinct().size == lines.count { it.serialCode != null }, LocalStockFailure.INVALID_SERIAL)
        localStockReason(reasonCode)
    }
    public val items: List<LocalStockItemVersion> = localStockFrozen(items)
    public val lines: List<LocalGoodsReceiptLine> = localStockFrozen(lines)
}

public data class LocalInspectionReleaseRequest(
    public val inspectionId: DomainId,
    public val receiptId: DomainId,
    public val lineId: DomainId,
    public val expectedReceiptRevision: Revision,
    public val expectedItemRevision: Revision,
    public val quantity: Quantity,
    public val evidenceId: DomainId,
    public val reasonCode: String,
    public val serialId: DomainId? = null,
)

public data class LocalCountCaptureRequest(
    public val countId: DomainId,
    public val position: LocalStockPositionRef,
    public val expectedItemRevision: Revision,
    public val reasonCode: String,
)

public data class LocalCountPostRequest(
    public val countId: DomainId,
    public val expectedCountRevision: Revision,
    public val counted: Quantity,
    public val reasonCode: String,
    public val surplusBasisId: DomainId? = null,
    public val expectedSurplusBasisRevision: Revision? = null,
    public val serialId: DomainId? = null,
)

public data class LocalLotRecallRequest(
    public val skuId: DomainId,
    public val lotId: DomainId,
    public val expectedItemRevision: Revision,
    public val reasonCode: String,
)

/** Same-branch warehouse custody only. Cross-branch dispatch/receipt is a separate protocol. */
public data class LocalWarehouseTransferRequest(
    public val transferId: DomainId,
    public val sourceEventId: DomainId,
    public val destinationEventId: DomainId,
    public val source: LocalStockPositionRef,
    public val destinationWarehouseId: DomainId,
    public val expectedItemRevision: Revision,
    public val quantity: Quantity,
    public val reasonCode: String,
)

public enum class LocalStockResultKind { RECEIVED, OPENED, INSPECTED, COUNT_CAPTURED, COUNT_POSTED, RECALLED, TRANSFERRED, REJECTED }

public class LocalStockResult(
    public val kind: LocalStockResultKind,
    public val documentId: DomainId,
    items: List<LocalStockItemVersion>,
    public val failure: LocalStockFailure? = null,
) {
    public val items: List<LocalStockItemVersion> = localStockFrozen(items)
}

/** Public app input factories. The app durably stages these immutable bytes before dispatch. */
public object LocalStockCommands {
    /** Canonical durable request replay/lookup; unknown command profiles refuse. Not authorization. */
    public fun normalize(command: CommandKey, body: StoredBytes): StoredBytes = LocalStockCommandCodec.normalize(command, body)
    /** Exact retained legacy opening target; no fresh routing, request change or authority grant. */
    public fun retainedOpeningReceiptId(command: CommandKey, body: StoredBytes): DomainId {
        localStockCheck(command == LocalStockStoreProfile.OPENING || command == LocalStockStoreProfile.OPENING_SERIAL)
        localStockCheck(LocalStockCommandCodec.normalize(command, body) == body)
        return LocalStockCommandCodec.receipt(body).receiptId
    }
    public fun receive(operationId: OperationId, request: LocalGoodsReceiptRequest): MutationRequest =
        MutationRequest(operationId, if (request.lines.any { it.serialId != null }) LocalStockStoreProfile.RECEIVE_SERIAL else LocalStockStoreProfile.RECEIVE,
            LocalStockCommandCodec.receipt(request))
    public fun receivePo(operationId: OperationId, request: LocalPoReceiptRequestV1): MutationRequest =
        MutationRequest(operationId, LocalStockStoreProfile.RECEIVE_PO, LocalStockCommandCodec.poReceipt(request))
    public fun opening(operationId: OperationId, request: LocalGoodsReceiptRequest): MutationRequest =
        MutationRequest(operationId, if (request.lines.any { it.serialId != null }) LocalStockStoreProfile.OPENING_SERIAL else LocalStockStoreProfile.OPENING,
            LocalStockCommandCodec.receipt(request))
    public fun inspect(operationId: OperationId, request: LocalInspectionReleaseRequest): MutationRequest =
        MutationRequest(operationId, if (request.serialId != null) LocalStockStoreProfile.INSPECT_SERIAL else LocalStockStoreProfile.INSPECT,
            LocalStockCommandCodec.inspection(request))
    public fun captureCount(operationId: OperationId, request: LocalCountCaptureRequest): MutationRequest =
        MutationRequest(operationId, if (request.position.serialId != null) LocalStockStoreProfile.COUNT_CAPTURE_SERIAL else LocalStockStoreProfile.COUNT_CAPTURE,
            LocalStockCommandCodec.countCapture(request))
    public fun postCount(operationId: OperationId, request: LocalCountPostRequest): MutationRequest =
        MutationRequest(operationId, if (request.serialId != null) LocalStockStoreProfile.COUNT_POST_SERIAL else LocalStockStoreProfile.COUNT_POST,
            LocalStockCommandCodec.countPost(request))
    public fun recall(operationId: OperationId, request: LocalLotRecallRequest): MutationRequest =
        MutationRequest(operationId, LocalStockStoreProfile.RECALL, LocalStockCommandCodec.recall(request))
    public fun recallSerials(operationId: OperationId, request: LocalLotRecallRequest): MutationRequest =
        MutationRequest(operationId, LocalStockStoreProfile.RECALL_SERIAL, LocalStockCommandCodec.recall(request))
    public fun transfer(operationId: OperationId, request: LocalWarehouseTransferRequest): MutationRequest =
        MutationRequest(operationId, if (request.source.supplierId == null) LocalStockStoreProfile.TRANSFER_OWNED
            else if (request.source.serialId != null) LocalStockStoreProfile.TRANSFER_SERIAL else LocalStockStoreProfile.TRANSFER,
            LocalStockCommandCodec.transfer(request))
    public fun items(after: DomainId? = null, limit: Int = 25): ReadRequest =
        ReadRequest(LocalStockStoreProfile.ITEMS, LocalStockCommandCodec.page(null, after, limit))
    public fun quantities(skuId: DomainId, after: DomainId? = null, limit: Int = 25): ReadRequest =
        ReadRequest(LocalStockStoreProfile.QUANTITIES, LocalStockCommandCodec.page(skuId, after, limit))
    /** Exact current coordinate; decodeQuantities returns zero or one row and no cursor. */
    public fun quantityPosition(skuId: DomainId, positionId: DomainId): ReadRequest =
        ReadRequest(LocalStockStoreProfile.QUANTITY_POSITION, LocalStockCommandCodec.position(skuId, positionId))
    public fun costs(skuId: DomainId, after: DomainId? = null, limit: Int = 25): ReadRequest =
        ReadRequest(LocalStockStoreProfile.COSTS, LocalStockCommandCodec.page(skuId, after, limit))
    public fun receipt(receiptId: DomainId): ReadRequest = ReadRequest(LocalStockStoreProfile.RECEIPT, LocalStockCommandCodec.document(receiptId))
    public fun receiptCosts(receiptId: DomainId): ReadRequest = ReadRequest(LocalStockStoreProfile.RECEIPT_COSTS, LocalStockCommandCodec.document(receiptId))
    public fun count(countId: DomainId): ReadRequest = ReadRequest(LocalStockStoreProfile.COUNT, LocalStockCommandCodec.document(countId))
    public fun transferDetail(transferId: DomainId): ReadRequest = ReadRequest(LocalStockStoreProfile.TRANSFER_DETAIL, LocalStockCommandCodec.document(transferId))
    public fun transferCosts(transferId: DomainId): ReadRequest = ReadRequest(LocalStockStoreProfile.TRANSFER_COSTS, LocalStockCommandCodec.document(transferId))
    public fun decodeResult(result: StoredBytes): LocalStockResult = LocalStockCommandCodec.decodeResult(result)
}

/**
 * Closed process-composition verifier for OTHER complete composite producers (for example sales).
 * It must validate the whole retained request/result/writes/claims/jobs, not an inventory subset.
 * No verifier or command list is selected from a cashier payload. Unknown producers always refuse.
 */
public interface LocalStockCompositeVerifier {
    public val commands: Set<CommandKey>
    public fun requireComplete(evidence: RetainedCommandEvidence)
}

/** Borrowed immutable cut. Constructor and storage rows are not public mutation/provision APIs. */
public class LocalStockReadCut internal constructor(internal val state: LocalStockLoaded) {
    public val scope: BranchScope get() = state.scope
    public val snapshot: InventorySnapshot get() = state.snapshot
    public val items: List<LocalStockItemVersion> get() = localStockFrozen(state.items.map { (id, row) -> LocalStockItemVersion(id, row?.row?.revision) })
}

/**
 * Copied scalar identity from an ALREADY verified normalized reservation/issue observation.
 * Not a stock capability, producer approval, mutable row, alternate decoder or permission.
 * The consumer still reads the actual retained producer and current domain record in the
 * same borrowed transaction. This object cannot project a plan or recreate a read cut.
 */
public class LocalStockObservedSource internal constructor(
    public val scope: BranchScope,
    public val key: AggregateKey,
    public val storageRevision: Revision,
    public val payloadDigest: PayloadDigest,
    public val producerOperationId: OperationId,
    public val producerCommand: CommandKey,
    public val producerActorId: DomainId,
    public val producerSemanticDigest: PayloadDigest,
)

/** Partial inventory contribution; sales must compose and validate its complete atomic proposal. */
public class LocalStockContribution internal constructor(
    public val plan: InventoryPlan,
    changes: List<AggregateChange>,
    claims: List<ClaimChange>,
    public val effects: StoredBytes,
    public val mustCommitBefore: UtcInstant?,
    items: List<LocalStockItemVersion>,
    observations: List<StockObservedWire>,
) {
    internal val observations: List<StockObservedWire> = localStockFrozen(observations)
    public val changes: List<AggregateChange> = localStockFrozen(changes)
    public val claims: List<ClaimChange> = localStockFrozen(claims)
    public val items: List<LocalStockItemVersion> = localStockFrozen(items)
}

internal fun localStockCheck(condition: Boolean, failure: LocalStockFailure = LocalStockFailure.MALFORMED) {
    if (!condition) throw LocalStockRejected(failure)
}

internal fun localStockReason(value: String) {
    localStockCheck(value.length in 1..96 && value.all { it in 'a'..'z' || it in '0'..'9' || it in "._-" })
}

/** 128-bit lookup key, always additionally checked against its full canonical coordinates/digest. */
internal fun localStockId(domain: String, value: String): DomainId {
    val hex = sha256(("ukkera.local.stock/v1\u0000$domain\u0000$value").encodeToByteArray()).value
    return DomainId("${hex.substring(0, 8)}-${hex.substring(8, 12)}-5${hex.substring(13, 16)}-8${hex.substring(17, 20)}-${hex.substring(20, 32)}")
}
