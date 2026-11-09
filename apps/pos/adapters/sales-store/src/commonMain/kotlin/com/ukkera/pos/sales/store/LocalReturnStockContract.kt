package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.ClaimKey
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.StockStreamingCommandsV2
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.Serializable

/** Separate from immutable CASE acceptance, refund application and recipe cancellation. */
public object LocalReturnStockProfileV1 {
    public val COMMAND: CommandKey get() = StockStreamingCommandsV2.RETURN
    public val QUERY: ReadKey = ReadKey("sales.return.stock.read", 1)
    public const val STATE: String = "sales.return.stock.state"
    public const val POSTING: String = "sales.return.stock.posting"
    public const val NAMESPACE: String = "sales.return.stock.source"
    public const val MAX_SELECTIONS: Int = 32
    public const val MAX_READ_BYTES: Int = 24_576
    public val rejectionReasons: Set<String> = setOf(
        "DISPOSITION_REVISION_CHANGED", "ACCEPTED_SOURCE_CHANGED", "DISPOSITION_QUANTITY_CHANGED", "DISPOSITION_NOT_SUPPORTED",
        "INVALID_SELECTION", "ORIGINAL_UNIT_REQUIRED", "INSPECTION_CUSTODY_REQUIRED", "ACCEPTED_QUANTITY_EXHAUSTED",
        "CASE_REVISION_CHANGED", "ORIGINAL_ISSUE_CHANGED", "DUPLICATE_SOURCE", "ORIGINAL_ALLOCATION_REQUIRED",
        "STOCK_UNAVAILABLE", "STOCK_REVISION_CHANGED",
    )
    public fun stateKey(returnId: DomainId): AggregateKey = AggregateKey(STATE, returnId)
    public fun postingKey(restorationId: DomainId): AggregateKey = AggregateKey(POSTING, restorationId)
    public fun stateClaim(returnId: DomainId): ClaimKey = ClaimKey(NAMESPACE, "return:${returnId.value}")
    public fun postingClaim(restorationId: DomainId): ClaimKey = ClaimKey(NAMESPACE, "posting:${restorationId.value}")
}

@Serializable
public enum class LocalReturnStockCustodyV1 { RELEASED, QUARANTINE }

/** Actual inspected original allocation, not a new FEFO selection or caller-supplied cost. */
@Serializable
public data class LocalReturnStockSelectionV1(
    public val allocationId: DomainId,
    public val quantity: Quantity,
    public val custody: LocalReturnStockCustodyV1,
)

/** A positive subset may be posted; only conserving committed cumulative state completes it. */
@Serializable
public data class LocalReturnStockRequestV1(
    public val saleId: DomainId,
    public val caseId: DomainId,
    public val returnId: DomainId,
    public val expectedCaseRevision: Revision,
    public val expectedDispositionRevision: Revision,
    /** Assertions against immutable acceptance and current stock state, never caller authority. */
    public val acceptedQuantity: Quantity,
    public val expectedPostedQuantity: Quantity,
    public val issueId: DomainId,
    public val expectedIssueRevision: Revision,
    public val restorationId: DomainId,
    public val selections: List<LocalReturnStockSelectionV1>,
    public val inspectionEvidence: LocalCashEvidenceV1,
)

public object LocalReturnStockCodecV1 {
    public fun encode(value: LocalReturnStockRequestV1): StoredBytes {
        require(value.expectedCaseRevision.value >= 1 && value.expectedDispositionRevision.value >= 0 &&
            value.acceptedQuantity.scaled > 0 && value.expectedPostedQuantity.scaled >= 0 &&
            value.acceptedQuantity.unitId == value.expectedPostedQuantity.unitId &&
            value.acceptedQuantity.scale == value.expectedPostedQuantity.scale &&
            value.expectedPostedQuantity <= value.acceptedQuantity &&
            value.expectedIssueRevision.value >= 1 && value.restorationId != value.returnId &&
            value.restorationId != value.issueId && value.selections.size in 1..LocalReturnStockProfileV1.MAX_SELECTIONS &&
            value.selections.map { it.allocationId }.distinct().size == value.selections.size &&
            value.selections.all { it.quantity.scaled > 0 }) { "sales.return.stock.request" }
        return localSalesEncode(value, LocalReturnStockRequestV1.serializer(), 8_192)
    }
    public fun decode(value: StoredBytes): LocalReturnStockRequestV1 =
        localSalesDecode(value, LocalReturnStockRequestV1.serializer(), 8_192)
    public fun normalize(value: StoredBytes): StoredBytes = encode(decode(value))
    public fun request(operationId: OperationId, value: LocalReturnStockRequestV1): MutationRequest =
        MutationRequest(operationId, LocalReturnStockProfileV1.COMMAND, encode(value))
}

@Serializable
public sealed interface LocalReturnStockResultV1 {
    @Serializable public data class Applied(
        public val returnId: DomainId,
        public val restorationId: DomainId,
        public val revision: Revision,
        public val postedQuantity: Quantity,
        public val remainingQuantity: Quantity,
        public val status: LocalReturnStockStatusV1,
    ) : LocalReturnStockResultV1
    @Serializable public data class Rejected(public val reason: String) : LocalReturnStockResultV1
}

public object LocalReturnStockResultCodecV1 {
    public fun decode(value: StoredBytes): LocalReturnStockResultV1 =
        localSalesDecode(value, LocalReturnStockResultV1.serializer(), 2_048)
    internal fun encode(value: LocalReturnStockResultV1): StoredBytes =
        localSalesEncode(value, LocalReturnStockResultV1.serializer(), 2_048)
}

@Serializable
public data class LocalReturnStockQueryV1(
    /** Optional discovery assertion; the Master derives the actual sale from immutable binding. */
    public val saleId: DomainId? = null,
    public val caseId: DomainId,
    public val returnId: DomainId,
    public val afterAllocationId: DomainId? = null,
    public val limit: Int = 16,
)

/** Quantities and original allocation identity only. Carrying cost requires a separate grant. */
@Serializable
public data class LocalReturnStockAllocationViewV1(
    public val allocationId: DomainId,
    public val skuId: DomainId,
    public val warehouseId: DomainId,
    public val lotId: DomainId?,
    public val originalQuantity: Quantity,
    public val restoredQuantity: Quantity,
    public val remainingQuantity: Quantity,
    public val currentSafety: String,
    public val expiresAt: UtcInstant?,
)

@Serializable
public enum class LocalReturnStockStatusV1 { PENDING, PARTIALLY_POSTED, POSTED }

@Serializable
public sealed interface LocalReturnStockReadResultV1 {
    @Serializable public data object NotAvailable : LocalReturnStockReadResultV1
    @Serializable public data class Original(
        public val saleId: DomainId,
        public val caseId: DomainId,
        public val returnId: DomainId,
        public val caseRevision: Revision,
        public val portionId: DomainId,
        public val acceptedQuantity: Quantity,
        public val requestedDisposition: LocalReturnDispositionV1,
        public val dispositionRevision: Revision,
        public val postedQuantity: Quantity,
        public val remainingQuantity: Quantity,
        public val status: LocalReturnStockStatusV1,
        public val issueId: DomainId,
        public val issueRevision: Revision,
        public val allocations: List<LocalReturnStockAllocationViewV1>,
        public val nextAfterAllocationId: DomainId?,
        public val lastPostingOperationId: OperationId?,
    ) : LocalReturnStockReadResultV1
}

public object LocalReturnStockReadCodecV1 {
    public fun encode(value: LocalReturnStockQueryV1): StoredBytes {
        require(value.limit in 1..16) { "sales.return.stock.page" }
        return localSalesEncode(value, LocalReturnStockQueryV1.serializer(), 2_048)
    }
    public fun request(value: LocalReturnStockQueryV1): ReadRequest =
        ReadRequest(LocalReturnStockProfileV1.QUERY, encode(value))
    public fun decode(value: StoredBytes): LocalReturnStockReadResultV1 =
        localSalesDecode(value, LocalReturnStockReadResultV1.serializer(), LocalReturnStockProfileV1.MAX_READ_BYTES)
}
