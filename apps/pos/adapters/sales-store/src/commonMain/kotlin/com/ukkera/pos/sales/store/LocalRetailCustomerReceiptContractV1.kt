package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

public object LocalRetailCustomerReceiptProfileV1 {
    public const val HEAD: String = "sales.customer-receipt.head"
    public const val EVENT: String = "sales.customer-receipt.event"
    public const val HEAD_BEFORE: String = "sales.customer-receipt.head-before"
    public const val HEAD_SCHEMA: String = "sales.customer-receipt.head/v1"
    public const val EVENT_SCHEMA: String = "sales.customer-receipt.event/v1"
    public const val HEAD_BEFORE_SCHEMA: String = "sales.customer-receipt.head-before/v1"
    public const val SOURCE_SCHEMA: String = "sales.customer-receipt.sale-source/v1"
    public const val NAMESPACE: String = "sales.customer-receipt.source"
    public const val MAX_REQUEST_BYTES: Int = 8_192
    public const val MAX_ROW_BYTES: Int = 65_536
    public const val MAX_READ_BYTES: Int = 65_536
    public const val MAX_REQUEST_PORTIONS: Int = 32
    public const val MAX_ORIGINAL_PORTIONS: Int = 256
    public const val MAX_EVENTS: Int = 64
    public const val RECEIPT_TIME_ZONE_ID: String = "Africa/Cairo"
}

@Serializable
public enum class LocalRetailCustomerReceiptObservationV1 { CUSTOMER_RECEIVED_AT_COUNTER }

/** Exact original base units. No rounding, rescaling or inferred pack conversion. */
@Serializable
public data class LocalRetailCustomerReceiptPortionV1(
    public val portionId: DomainId,
    public val quantity: Quantity,
) {
    init { require(quantity.scaled > 0) { "sales.customer_receipt.quantity" } }
}

/** Staff fixes the observed instant and explicitly selected zone before dispatch. */
@Serializable
public data class LocalRetailCustomerReceiptRequestV1(
    public val saleId: DomainId,
    public val expectedHeadRevision: Revision,
    public val receivedAt: UtcInstant,
    public val receiptTimeZoneId: String,
    public val observation: LocalRetailCustomerReceiptObservationV1,
    public val portions: List<LocalRetailCustomerReceiptPortionV1>,
) {
    init {
        require(receiptTimeZoneId.length in 1..128) { "sales.customer_receipt.time_zone" }
        requireReceiptPortions(portions, LocalRetailCustomerReceiptProfileV1.MAX_REQUEST_PORTIONS)
    }
}

@Serializable
public enum class LocalRetailCustomerReceiptRefusalV1 {
    REVISION_CONFLICT,
    ORIGINAL_UNSUPPORTED,
    ORIGINAL_UNAVAILABLE,
    INVALID_QUANTITY,
    QUANTITY_EXCEEDED,
    INVALID_OBSERVATION_TIME,
    INVALID_RECEIPT_TIME_ZONE,
    CAPACITY_REQUIRED,
}

@Serializable
public sealed interface LocalRetailCustomerReceiptResultV1 {
    /** Applied is durable only in an enclosing committed Recorded reply. */
    @Serializable
    @SerialName("APPLIED")
    public data class Applied(
        public val saleId: DomainId,
        public val eventOperationId: OperationId,
        public val headRevision: Revision,
        public val receivedAt: UtcInstant,
        public val receiptTimeZoneId: String,
        public val recordedAt: UtcInstant,
    ) : LocalRetailCustomerReceiptResultV1 {
        init {
            require(headRevision.value in 1L..LocalRetailCustomerReceiptProfileV1.MAX_EVENTS.toLong())
            require(receiptTimeZoneId == LocalRetailCustomerReceiptProfileV1.RECEIPT_TIME_ZONE_ID)
            require(receivedAt <= recordedAt)
        }
    }

    @Serializable
    @SerialName("REFUSED")
    public data class Refused(public val reason: LocalRetailCustomerReceiptRefusalV1) : LocalRetailCustomerReceiptResultV1
}

public object LocalRetailCustomerReceiptCodec {
    public val COMMAND: CommandKey = CommandKey("sales.customer-receipt.write", 1)

    public fun command(operationId: OperationId, request: LocalRetailCustomerReceiptRequestV1): MutationRequest =
        MutationRequest(operationId, COMMAND, encode(request))

    public fun encode(request: LocalRetailCustomerReceiptRequestV1): StoredBytes =
        localSalesEncode(request, LocalRetailCustomerReceiptRequestV1.serializer(), LocalRetailCustomerReceiptProfileV1.MAX_REQUEST_BYTES)

    public fun decode(body: StoredBytes): LocalRetailCustomerReceiptRequestV1 =
        localSalesDecode(body, LocalRetailCustomerReceiptRequestV1.serializer(), LocalRetailCustomerReceiptProfileV1.MAX_REQUEST_BYTES)

    public fun normalize(body: StoredBytes): StoredBytes = encode(decode(body))

    public fun decodeResult(body: StoredBytes): LocalRetailCustomerReceiptResultV1 =
        localSalesDecode(body, LocalRetailCustomerReceiptResultV1.serializer(), 2_048)

    internal fun result(value: LocalRetailCustomerReceiptResultV1): StoredBytes =
        localSalesEncode(value, LocalRetailCustomerReceiptResultV1.serializer(), 2_048)
}

/** Selects one protected read route; selecting a route supplies no grant. */
@Serializable
public enum class LocalRetailCustomerReceiptReadAccessV1 { RECORD, RETURN_LOOKUP }

@Serializable
public sealed interface LocalRetailCustomerReceiptQueryV1 {
    @Serializable
    @SerialName("HEAD")
    public data class Head(
        public val saleId: DomainId,
        public val access: LocalRetailCustomerReceiptReadAccessV1 = LocalRetailCustomerReceiptReadAccessV1.RECORD,
    ) : LocalRetailCustomerReceiptQueryV1

    @Serializable
    @SerialName("EVENT")
    public data class Event(
        public val saleId: DomainId,
        public val operationId: OperationId,
        public val access: LocalRetailCustomerReceiptReadAccessV1 = LocalRetailCustomerReceiptReadAccessV1.RECORD,
    ) : LocalRetailCustomerReceiptQueryV1
}

/** Documentary original quantities only; no payment, cost, contact or active-order data. */
@Serializable
public data class LocalRetailCustomerReceiptOriginalV1(
    public val saleId: DomainId,
    public val originalCollectorId: DomainId,
    public val paidFinalizeAt: UtcInstant,
    public val portions: List<LocalRetailCustomerReceiptPortionV1>,
) {
    init { requireReceiptPortions(portions, LocalRetailCustomerReceiptProfileV1.MAX_ORIGINAL_PORTIONS) }
}

@Serializable
public data class LocalRetailCustomerReceiptHeadViewV1(
    public val original: LocalRetailCustomerReceiptOriginalV1,
    public val headRevision: Revision,
    public val eventCount: Int,
    public val recordedPortions: List<LocalRetailCustomerReceiptPortionV1>,
    public val latestEventOperationId: OperationId,
) {
    init {
        require(eventCount in 1..LocalRetailCustomerReceiptProfileV1.MAX_EVENTS && headRevision.value == eventCount.toLong())
        requireReceiptPortions(recordedPortions, LocalRetailCustomerReceiptProfileV1.MAX_ORIGINAL_PORTIONS)
        requireReceiptWithinOriginal(original.portions, recordedPortions)
    }
}

@Serializable
public data class LocalRetailCustomerReceiptEventViewV1(
    public val saleId: DomainId,
    public val eventOperationId: OperationId,
    public val headRevision: Revision,
    public val observation: LocalRetailCustomerReceiptObservationV1,
    public val portions: List<LocalRetailCustomerReceiptPortionV1>,
    public val receivedAt: UtcInstant,
    public val receiptTimeZoneId: String,
    public val recordedAt: UtcInstant,
    public val originalCollectorId: DomainId,
    public val paidFinalizeAt: UtcInstant,
    public val observingActorId: DomainId,
    public val sourceOperationId: OperationId,
    public val sourceActorId: DomainId,
    public val sourceSemanticDigest: PayloadDigest,
    public val eventSemanticDigest: PayloadDigest,
) {
    init {
        require(headRevision.value in 1L..LocalRetailCustomerReceiptProfileV1.MAX_EVENTS.toLong())
        requireReceiptPortions(portions, LocalRetailCustomerReceiptProfileV1.MAX_REQUEST_PORTIONS)
        require(receiptTimeZoneId == LocalRetailCustomerReceiptProfileV1.RECEIPT_TIME_ZONE_ID)
        require(paidFinalizeAt <= receivedAt && receivedAt <= recordedAt)
        require(sourceActorId == originalCollectorId)
    }
}

@Serializable
public sealed interface LocalRetailCustomerReceiptReadResultV1 {
    /** Genuine original plus absence of this producer's head/source membership. */
    @Serializable
    @SerialName("NOT_RECORDED")
    public data class NotRecorded(public val original: LocalRetailCustomerReceiptOriginalV1) : LocalRetailCustomerReceiptReadResultV1

    @Serializable
    @SerialName("HEAD")
    public data class Head(public val value: LocalRetailCustomerReceiptHeadViewV1) : LocalRetailCustomerReceiptReadResultV1

    @Serializable
    @SerialName("EVENT")
    public data class Event(public val value: LocalRetailCustomerReceiptEventViewV1) : LocalRetailCustomerReceiptReadResultV1

    @Serializable
    @SerialName("EVENT_NOT_RECORDED")
    public data class EventNotRecorded(public val saleId: DomainId, public val operationId: OperationId) : LocalRetailCustomerReceiptReadResultV1

    @Serializable
    @SerialName("NOT_AVAILABLE")
    public data object NotAvailable : LocalRetailCustomerReceiptReadResultV1

    @Serializable
    @SerialName("CAPACITY_REQUIRED")
    public data object CapacityRequired : LocalRetailCustomerReceiptReadResultV1
}

public object LocalRetailCustomerReceiptReadCodec {
    public val QUERY: ReadKey = ReadKey("sales.customer-receipt.read", 1)

    public fun request(query: LocalRetailCustomerReceiptQueryV1): ReadRequest = ReadRequest(QUERY, encode(query))

    public fun encode(query: LocalRetailCustomerReceiptQueryV1): StoredBytes =
        localSalesEncode(query, LocalRetailCustomerReceiptQueryV1.serializer(), 2_048)

    public fun decode(body: StoredBytes): LocalRetailCustomerReceiptReadResultV1 =
        localSalesDecode(body, LocalRetailCustomerReceiptReadResultV1.serializer(), LocalRetailCustomerReceiptProfileV1.MAX_READ_BYTES)

    public fun decodeQuery(body: StoredBytes): LocalRetailCustomerReceiptQueryV1 =
        localSalesDecode(body, LocalRetailCustomerReceiptQueryV1.serializer(), 2_048)

    internal fun result(value: LocalRetailCustomerReceiptReadResultV1): StoredBytes =
        localSalesEncode(value, LocalRetailCustomerReceiptReadResultV1.serializer(), LocalRetailCustomerReceiptProfileV1.MAX_READ_BYTES)
}

internal fun requireReceiptPortions(portions: List<LocalRetailCustomerReceiptPortionV1>, maximum: Int) {
    require(portions.size in 1..maximum && portions.all { it.quantity.scaled > 0 } &&
        portions.zipWithNext().all { (first, second) -> first.portionId.value < second.portionId.value }) {
        "sales.customer_receipt.portions"
    }
}

internal fun requireReceiptWithinOriginal(originals: List<LocalRetailCustomerReceiptPortionV1>,
    recorded: List<LocalRetailCustomerReceiptPortionV1>) {
    val byId = originals.associateBy { it.portionId }
    require(recorded.all { portion -> byId[portion.portionId]?.quantity?.let { original ->
        portion.quantity.unitId == original.unitId && portion.quantity.scale == original.scale &&
            portion.quantity.scaled <= original.scaled
    } == true }) { "sales.customer_receipt.original_quantity" }
}
