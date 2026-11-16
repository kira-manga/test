package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Explicit current supervisor route. An ordinary supplier command remains owner-only. */
@Serializable
public sealed interface LocalSupplierCashSupervisorRequest {
    @Serializable @SerialName("REVIEW")
    public data class Review(public val originalRecordingOperationId: OperationId) : LocalSupplierCashSupervisorRequest
    @Serializable @SerialName("APPLY_RECORDED_OBSERVATION")
    public data class Apply(public val factOperationId: OperationId,
        public val phase: LocalSupplierCashPhase) : LocalSupplierCashSupervisorRequest
}

@Serializable
public data class LocalSupplierCashSupervisorFact(public val target: LocalSupplierCashTarget,
    public val originalActorId: DomainId, public val recordingActorId: DomainId,
    public val bodyDigest: PayloadDigest, public val originalBodyDigest: PayloadDigest?,
    public val amount: Money, public val applicationOperationId: OperationId?, public val appliedAction: LocalSupplierCashAction?)

@Serializable
public data class LocalSupplierCashSupervisorReview(public val original: LocalSupplierCashSupervisorFact,
    public val originalOutcome: LocalSupplierCashOutcome, public val resolution: LocalSupplierCashSupervisorFact?,
    public val resolutionOutcome: LocalSupplierCashOutcome?)

public object LocalSupplierCashSupervisorCodec {
    public val COMMAND: CommandKey = CommandKey("purchasing.supplier-cash-supervisor", 1)
    public fun command(operationId: OperationId, request: LocalSupplierCashSupervisorRequest): MutationRequest =
        MutationRequest(operationId, COMMAND, encode(request))
    public fun encode(value: LocalSupplierCashSupervisorRequest): StoredBytes =
        localSalesEncode(value, LocalSupplierCashSupervisorRequest.serializer(), 2_048)
    public fun decode(body: StoredBytes): LocalSupplierCashSupervisorRequest =
        localSalesDecode(body, LocalSupplierCashSupervisorRequest.serializer(), 2_048)
    public fun normalize(body: StoredBytes): StoredBytes = encode(decode(body))
    public fun review(value: LocalSupplierCashSupervisorReview): StoredBytes =
        localSalesEncode(value, LocalSupplierCashSupervisorReview.serializer(), 32_768)
    public fun decodeReview(body: StoredBytes): LocalSupplierCashSupervisorReview =
        localSalesDecode(body, LocalSupplierCashSupervisorReview.serializer(), 32_768)
}

/** Pure typed Apply selection only; the new key cannot represent Start or raw recording. */
public fun supplierCashApplicationRequest(command: CommandKey, body: StoredBytes): LocalSupplierCashRequest = when (command) {
    LocalSupplierCashProfile.COMMAND -> LocalSupplierCashCodec.decode(body)
    LocalSupplierCashSupervisorCodec.COMMAND -> when (val request = LocalSupplierCashSupervisorCodec.decode(body)) {
        is LocalSupplierCashSupervisorRequest.Apply -> if (request.phase == LocalSupplierCashPhase.ORIGINAL)
            LocalSupplierCashRequest.ApplyOriginal(request.factOperationId) else LocalSupplierCashRequest.ApplyResolution(request.factOperationId)
        is LocalSupplierCashSupervisorRequest.Review -> error("supplier.cash.supervisor_apply_required")
    }
    else -> error("supplier.cash.command_required")
}
