package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.types.OperationId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Explicit current FOOD supervisor route; never impersonates the original custodian. */
@Serializable
public sealed interface LocalFoodPaymentSupervisorRequest {
    @Serializable @SerialName("REVIEW")
    public data class Review(public val originalRecordingOperationId: OperationId) : LocalFoodPaymentSupervisorRequest
    @Serializable @SerialName("APPLY_RECORDED_OBSERVATION")
    public data class Apply(public val factOperationId: OperationId,
        public val phase: LocalFoodPaymentFactPhase) : LocalFoodPaymentSupervisorRequest
}

@Serializable
public data class LocalFoodPaymentSupervisorReview(public val original: LocalFoodPaymentOriginalSummary,
    public val originalOutcome: LocalFoodCashOutcome, public val resolution: LocalFoodPaymentResolutionSummary?,
    public val resolutionOutcome: LocalFoodCashOutcome?)

public object LocalFoodPaymentSupervisorCodec {
    public val COMMAND: CommandKey = CommandKey("sales.food.payment-supervisor", 1)
    public fun command(operationId: OperationId, request: LocalFoodPaymentSupervisorRequest): MutationRequest =
        MutationRequest(operationId, COMMAND, encode(request))
    public fun encode(value: LocalFoodPaymentSupervisorRequest): StoredBytes =
        localSalesEncode(value, LocalFoodPaymentSupervisorRequest.serializer(), 2_048)
    public fun decode(body: StoredBytes): LocalFoodPaymentSupervisorRequest =
        localSalesDecode(body, LocalFoodPaymentSupervisorRequest.serializer(), 2_048)
    public fun normalize(body: StoredBytes): StoredBytes = encode(decode(body))
    public fun decodeReview(body: StoredBytes): LocalFoodPaymentSupervisorReview =
        localSalesDecode(body, LocalFoodPaymentSupervisorReview.serializer(), 32_768)
    internal fun review(value: LocalFoodPaymentSupervisorReview): StoredBytes =
        localSalesEncode(value, LocalFoodPaymentSupervisorReview.serializer(), 32_768)
}
