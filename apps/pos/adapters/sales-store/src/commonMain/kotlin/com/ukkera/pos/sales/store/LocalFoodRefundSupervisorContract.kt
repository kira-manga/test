package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.types.OperationId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Explicit current FOOD refund supervisor route; never impersonates the original custodian. */
@Serializable
public sealed interface LocalFoodRefundSupervisorRequest {
    @Serializable @SerialName("REVIEW")
    public data class Review(public val originalRecordingOperationId: OperationId) : LocalFoodRefundSupervisorRequest
    @Serializable @SerialName("APPLY_RECORDED_OBSERVATION")
    public data class Apply(public val factOperationId: OperationId,
        public val phase: LocalFoodRefundFactPhase) : LocalFoodRefundSupervisorRequest
}

@Serializable
public data class LocalFoodRefundSupervisorReview(public val original: LocalFoodRefundOriginalSummary,
    public val originalOutcome: LocalFoodRefundOutcome, public val resolution: LocalFoodRefundResolutionSummary?,
    public val resolutionOutcome: LocalFoodRefundOutcome?)

public object LocalFoodRefundSupervisorCodec {
    public val COMMAND: CommandKey = CommandKey("sales.food.refund-supervisor", 1)
    public fun command(operationId: OperationId, request: LocalFoodRefundSupervisorRequest): MutationRequest =
        MutationRequest(operationId, COMMAND, encode(request))
    public fun encode(value: LocalFoodRefundSupervisorRequest): StoredBytes =
        localSalesEncode(value, LocalFoodRefundSupervisorRequest.serializer(), 2_048)
    public fun decode(body: StoredBytes): LocalFoodRefundSupervisorRequest =
        localSalesDecode(body, LocalFoodRefundSupervisorRequest.serializer(), 2_048)
    public fun normalize(body: StoredBytes): StoredBytes = encode(decode(body))
    public fun decodeReview(body: StoredBytes): LocalFoodRefundSupervisorReview =
        localSalesDecode(body, LocalFoodRefundSupervisorReview.serializer(), 32_768)
    internal fun review(value: LocalFoodRefundSupervisorReview): StoredBytes =
        localSalesEncode(value, LocalFoodRefundSupervisorReview.serializer(), 32_768)
}

/** Closed canonical routing only, with no native or employee capability. */
internal fun normalizeFoodRefundCommand(command: CommandKey, bytes: StoredBytes): StoredBytes = when (command) {
    LocalFoodRefundProfile.COMMAND -> LocalFoodRefundCodec.normalize(bytes)
    LocalFoodRefundSupervisorCodec.COMMAND -> LocalFoodRefundSupervisorCodec.normalize(bytes)
    else -> error("food.refund.command_required")
}
