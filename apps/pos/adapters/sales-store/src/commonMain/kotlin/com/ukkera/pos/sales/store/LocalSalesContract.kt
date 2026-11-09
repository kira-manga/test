package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.ClaimKey
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.SalesRule
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The production composition must install only the qualified owners of these kinds. */
public object LocalSalesStoreProfile {
    public val CASH_COMMAND: CommandKey = CommandKey("sales.cash.control", 1)
    public val ORDER_COMMAND: CommandKey = CommandKey("sales.order.write", 1)
    public val COLLECTION_COMMAND: CommandKey = CommandKey("sales.collection.write", 1)
    public val RETURN_COMMAND: CommandKey = CommandKey("sales.return.write", 1)
    public val COLLECTION_COMMAND_V2: CommandKey = CommandKey("sales.collection.write", 2)
    public val RETURN_COMMAND_V2: CommandKey = CommandKey("sales.return.write", 2)

    public const val DRAWER_KIND: String = "sales.cash.drawer"
    public const val CUSTODIAN_KIND: String = "sales.cash.custodian"
    public const val WORK_KIND: String = "sales.cash.work"
    public const val SAFE_KIND: String = "sales.cash.safe"
    public const val TRANSFER_KIND: String = "sales.cash.transfer"
    public const val CLOSING_COUNT_KIND: String = "sales.cash.closing-count"
    public const val CLOSING_COUNT_INDEX_KIND: String = "sales.cash.closing-count-index"
    public const val ORDER_KIND: String = "sales.order"
    public const val ORDER_CONTEXT_KIND: String = "sales.order.context"
    public const val ORDER_REASSIGNMENT_KIND: String = "sales.order.reassignment"
    public const val PRICE_SOURCES_KIND: String = "sales.order.price-sources"
    public const val PRICE_SOURCES_SCHEMA: String = "sales.order.price-sources/v2"
    public const val COLLECTION_KIND: String = "sales.collection"
    public const val COLLECTION_BEFORE_KIND: String = "sales.collection.before"
    public const val SALE_KIND: String = "sales.paid_sale"
    public const val ENTITLEMENT_KIND: String = "sales.return.entitlement"
    public const val RETURN_CLAIM_KIND: String = "sales.return.claim"
    public const val RETURN_INTAKE_KIND: String = "sales.return.intake"
    public const val RETURN_KIND: String = "sales.return.accepted"
    public const val MOVEMENT_KIND: String = "sales.cash.fact"
    public const val RECORDING_SLOT_KIND: String = "sales.recording.slot"
    public const val PHYSICAL_SLOT_KIND: String = "sales.recording.physical-slot"
    public const val PHYSICAL_SLOT_SCHEMA: String = "sales.recording.physical-slot/v2"
    public const val PHYSICAL_FACT_KIND: String = "sales.recording.physical-fact"
    public const val PHYSICAL_FACT_SCHEMA: String = "sales.recording.physical-fact/v2"
    public const val PHYSICAL_APPLICATION_KIND: String = "sales.recording.application"
    public const val PHYSICAL_APPLICATION_SCHEMA: String = "sales.recording.application/v2"
    public const val CLIENT_RECORDING_ALLOCATION_NAMESPACE: String = "sales.recording.client-allocation"
    public const val RECORDING_ALLOCATION_NAMESPACE: String = "sales.recording.allocation"
    public const val RECORDING_OPERATION_NAMESPACE: String = "sales.recording.operation"
    public const val RECORDING_ATTEMPT_NAMESPACE: String = "sales.recording.attempt"
    public const val CONFIGURED_NAMESPACE: String = "sales.cash.configured"
    public const val ACTIVE_DRAWER_NAMESPACE: String = "sales.cash.active_drawer"
    public const val ACTIVE_CUSTODIAN_NAMESPACE: String = "sales.cash.active_custodian"
    public const val UNIQUE_NAMESPACE: String = "sales.unique"
    public const val SAFE_RECEIPT_NAMESPACE: String = "sales.cash.safe_receipt"
    public const val MAX_OPEN_WORK: Int = 256

    public fun schema(kind: String): String = "$kind/v1"
    public fun key(kind: String, id: DomainId): AggregateKey = AggregateKey(kind, id)
    public fun unique(kind: String, id: DomainId): ClaimKey = ClaimKey(UNIQUE_NAMESPACE, "$kind/${id.value}")
    public fun activeDrawer(drawerId: DomainId): ClaimKey = ClaimKey(ACTIVE_DRAWER_NAMESPACE, drawerId.value)
    public fun activeCustodian(custodianId: DomainId): ClaimKey = ClaimKey(ACTIVE_CUSTODIAN_NAMESPACE, custodianId.value)
}

@Serializable
public enum class LocalSalesAction {
    DRAWER_CONFIGURED, CUSTODIAN_CONFIGURED, SAFE_CONFIGURED,
    CASH_OPENED, CASH_MOVEMENT_RECORDED, CASH_CLOSING, CASH_CLOSING_CANCELLED, CASH_CLOSED, CASH_REVIEWED,
    SAFE_TRANSFER_SENT, SAFE_TRANSFER_RECEIVED,
    ORDER_CREATED, ORDER_EDITED, HANDOFF_OFFERED, HANDOFF_CANCELLED, HANDOFF_ACCEPTED,
    COLLECTION_LOCKED, PAYMENT_ATTEMPT_STARTED, PAYMENT_RECORDING_RESERVED, PAYMENT_UNKNOWN, PAYMENT_NOT_PAID,
    PAYMENT_RECORDED, SALE_FINALIZED, RETURN_CLAIM_PENDING, RETURN_CLAIMED, RETURN_ACCEPTED,
    REFUND_ATTEMPT_STARTED, REFUND_RECORDING_RESERVED, REFUND_UNKNOWN, REFUND_NOT_PAID, REFUND_RECORDED,
    CASH_COUNT_RECORDED, CASH_COUNTED_DELIVERY_SENT, CASH_COUNTED_CLOSED,
    ORDER_REASSIGNED,
}

@Serializable
public data class LocalSalesRevision(
    public val kind: String,
    public val id: DomainId,
    /** Logical domain revision, not the aggregate storage generation. */
    public val revision: Revision,
)

/** A Recorded reply's committed bit still governs this result; a timeout has no typed success. */
@Serializable
public sealed interface LocalSalesResultV1 {
    @Serializable @SerialName("APPLIED")
    public data class Applied(
        public val action: LocalSalesAction,
        public val documentId: DomainId,
        public val revisions: List<LocalSalesRevision>,
        public val amount: Money? = null,
        public val stockItems: List<LocalSalesStockRevisionV1> = emptyList(),
    ) : LocalSalesResultV1

    @Serializable @SerialName("REJECTED")
    public data class Rejected(
        @Serializable(with = StoredSalesRuleSerializer::class) public val rule: SalesRule,
    ) : LocalSalesResultV1

    @Serializable @SerialName("UNAVAILABLE")
    public data class Unavailable(public val reason: LocalSalesUnavailable) : LocalSalesResultV1

    /** Inventory safety changes committed; COVERED money is NOT a finalized sale. */
    @Serializable @SerialName("STOCK_RESOLUTION_REQUIRED")
    public data class StockResolutionRequired(
        public val collectionId: DomainId,
        public val reservationId: DomainId,
        public val reservationRevision: Revision,
        public val stockItems: List<LocalSalesStockRevisionV1>,
    ) : LocalSalesResultV1
}

@Serializable
public enum class LocalSalesUnavailable {
    CAPACITY_REQUIRED, UNSUPPORTED_ACTIVITY, SOURCE_REQUIRED, REVISION_EXHAUSTED,
    PRICE_REQUIRED, PRICE_CONFLICT, STOCK_REQUIRED,
    /** Only new/unrecorded physical starts are retired; original v1 history/recovery is unchanged. */
    OBSERVATION_V2_REQUIRED,
}

public object LocalSalesResultCodec {
    public fun decode(body: StoredBytes): LocalSalesResultV1 =
        localSalesDecode(body, LocalSalesResultV1.serializer(), 16_384)

    internal fun encode(result: LocalSalesResultV1): StoredBytes =
        localSalesEncode(result, LocalSalesResultV1.serializer(), 16_384)
}
