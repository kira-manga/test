package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.types.*
import kotlinx.serialization.Serializable

/** An independent typed association. Existing FOOD label requests and order bytes stay unchanged. */
public object LocalFoodTableAssignmentProfileV1 {
    public val COMMAND: CommandKey = CommandKey("sales.food.table.assignment", 1)
    public const val BINDING: String = "sales.food.table.binding"
    public const val VERSION: String = "sales.food.table.assignment-version"
    public const val SAVED: String = "food.table.assigned"
    public const val MAX_REQUEST_BYTES: Int = 4_096
    public const val MAX_RECORD_BYTES: Int = 65_536
    public val REJECTION_CODES: Set<String> = setOf(
        "food.table.assignment.order_unavailable", "food.table.assignment.order_stale",
        "food.table.assignment.order_closed", "food.table.assignment.channel_required",
        "food.table.assignment.handoff_pending", "food.table.assignment.stale",
        "food.table.assignment.floor_required", "food.table.assignment.table_required",
        "food.table.assignment.table_floor_mismatch", "food.table.assignment.floor_stale",
        "food.table.assignment.table_stale", "food.table.assignment.inactive",
        "food.table.assignment.capacity_required",
    )
    public fun key(kind: String, id: DomainId): AggregateKey = AggregateKey(kind, id)
    public fun schema(kind: String): String {
        require(kind == BINDING || kind == VERSION)
        return "$kind/v1"
    }
}

/** Null assignment expectation means genuine first assignment, never an automatic replacement. */
@Serializable
public data class LocalFoodTableAssignmentRequestV1(
    public val orderId: DomainId,
    public val expectedOrderRevision: Revision,
    public val floorId: DomainId,
    public val expectedFloorRevision: Revision,
    public val tableId: DomainId,
    public val expectedTableRevision: Revision,
    public val expectedAssignmentRevision: Revision? = null,
) {
    init {
        require(expectedOrderRevision.value > 0 && expectedFloorRevision.value > 0 && expectedTableRevision.value > 0)
        require(expectedAssignmentRevision == null || expectedAssignmentRevision.value > 0)
        require(floorId != tableId)
    }
}

/** Actual assigned definition identities and their original observed revisions, not current labels or grants. */
@Serializable
public data class LocalFoodTableBindingV1(
    public val revision: Revision,
    public val observedOrderRevision: Revision,
    public val floorId: DomainId,
    public val floorRevision: Revision,
    public val tableId: DomainId,
    public val tableRevision: Revision,
) {
    init {
        require(revision.value > 0 && observedOrderRevision.value > 0 && floorRevision.value > 0 && tableRevision.value > 0)
        require(floorId != tableId)
    }
}

@Serializable
public data class LocalFoodTableAssignmentResultV1(
    public val code: String,
    public val orderId: DomainId?,
    public val orderRevision: Revision?,
    public val assignment: LocalFoodTableBindingV1?,
) {
    init {
        require(if (code == LocalFoodTableAssignmentProfileV1.SAVED)
            orderId != null && orderRevision?.value?.let { it > 0 } == true && assignment != null &&
                assignment.observedOrderRevision == orderRevision
        else code in LocalFoodTableAssignmentProfileV1.REJECTION_CODES && orderId == null && orderRevision == null && assignment == null)
    }
}

public object LocalFoodTableAssignmentCodecV1 {
    public fun encode(request: LocalFoodTableAssignmentRequestV1): StoredBytes =
        localSalesEncode(request, LocalFoodTableAssignmentRequestV1.serializer(), LocalFoodTableAssignmentProfileV1.MAX_REQUEST_BYTES)
    public fun decode(bytes: StoredBytes): LocalFoodTableAssignmentRequestV1 =
        localSalesDecode(bytes, LocalFoodTableAssignmentRequestV1.serializer(), LocalFoodTableAssignmentProfileV1.MAX_REQUEST_BYTES)
    public fun command(operationId: OperationId, request: LocalFoodTableAssignmentRequestV1): MutationRequest =
        MutationRequest(operationId, LocalFoodTableAssignmentProfileV1.COMMAND, encode(request))
    public fun decodeResult(bytes: StoredBytes): LocalFoodTableAssignmentResultV1 =
        localSalesDecode(bytes, LocalFoodTableAssignmentResultV1.serializer(), 4_096)
}

@Serializable
public data class LocalFoodTableAssignmentQueryV1(public val orderId: DomainId)

/** A single current protected order/binding cut. A failed read cannot produce this observation. */
@Serializable
public data class LocalFoodTableAssignmentObservationV1(
    public val orderId: DomainId,
    public val orderRevision: Revision,
    public val ownerId: DomainId,
    public val activity: LocalSalesActivity,
    public val channel: LocalSalesChannel,
    public val workClosed: Boolean,
    public val handoffPending: Boolean,
    public val assignment: LocalFoodTableBindingV1?,
) {
    init {
        require(orderRevision.value > 0 && activity in setOf(LocalSalesActivity.RESTAURANT, LocalSalesActivity.CAFE))
        require(assignment == null || channel == LocalSalesChannel.DINE_IN)
    }
}

public object LocalFoodTableAssignmentReadCodecV1 {
    public val QUERY: ReadKey = ReadKey("sales.food.table.assignment-read", 1)
    public fun encode(query: LocalFoodTableAssignmentQueryV1): StoredBytes =
        localSalesEncode(query, LocalFoodTableAssignmentQueryV1.serializer(), 2_048)
    public fun read(query: LocalFoodTableAssignmentQueryV1): ReadRequest = ReadRequest(QUERY, encode(query))
    public fun decodeResult(bytes: StoredBytes): LocalFoodTableAssignmentObservationV1 =
        localSalesDecode(bytes, LocalFoodTableAssignmentObservationV1.serializer(), 4_096)
}
