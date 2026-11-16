package com.ukkera.pos.sales.store

import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.types.DomainId

/** Canonical inert classification only; domain validation uses the original native UNKNOWN. */
internal fun distributedResolutionRequest(request: MutationRequest): Boolean {
    if (request.actionApprovalId != null) return false
    return when (request.command) {
        LocalFinancialJournalProfileV3.COLLECTION_COMMAND -> LocalCollectionCommandCodecV3.normalize(request.payload) == request.payload &&
            LocalCollectionCommandCodecV3.decode(request.payload) is LocalCollectionRequestV3.RecordResolution
        LocalFinancialJournalProfileV3.RETURN_COMMAND -> LocalReturnCommandCodecV3.normalize(request.payload) == request.payload &&
            LocalReturnCommandCodecV3.decode(request.payload) is LocalReturnRequestV3.RecordResolution
        LocalFoodPaymentProfile.COMMAND -> LocalFoodPaymentCodec.normalize(request.payload) == request.payload &&
            LocalFoodPaymentCodec.decode(request.payload) is LocalFoodPaymentRequest.RecordResolution
        LocalFoodRefundProfile.COMMAND -> LocalFoodRefundCodec.normalize(request.payload) == request.payload &&
            LocalFoodRefundCodec.decode(request.payload) is LocalFoodRefundRequest.RecordResolution
        LocalSupplierCashProfile.COMMAND -> LocalSupplierCashCodec.normalize(request.payload) == request.payload &&
            LocalSupplierCashCodec.decode(request.payload) is LocalSupplierCashRequest.RecordResolution
        else -> false
    }
}

/** Canonical resolver DATA only. Current authority comes from the protected endpoint. */
internal fun distributedResolutionActor(request: MutationRequest): DomainId? {
    if (!distributedResolutionRequest(request)) return null
    return when (request.command) {
        LocalFinancialJournalProfileV3.COLLECTION_COMMAND -> (LocalCollectionCommandCodecV3.decode(request.payload) as LocalCollectionRequestV3.RecordResolution).resolverActorId
        LocalFinancialJournalProfileV3.RETURN_COMMAND -> (LocalReturnCommandCodecV3.decode(request.payload) as LocalReturnRequestV3.RecordResolution).resolverActorId
        LocalFoodPaymentProfile.COMMAND -> (LocalFoodPaymentCodec.decode(request.payload) as LocalFoodPaymentRequest.RecordResolution).resolverActorId
        LocalFoodRefundProfile.COMMAND -> (LocalFoodRefundCodec.decode(request.payload) as LocalFoodRefundRequest.RecordResolution).resolverActorId
        LocalSupplierCashProfile.COMMAND -> (LocalSupplierCashCodec.decode(request.payload) as LocalSupplierCashRequest.RecordResolution).resolverActorId
        else -> null
    }
}

/** An actual original-catalogue operation with a distinct current resolver loan. No Start,
 * publication, instruction or catalogue ownership is exposed by this continuation. */
public class DistributedPhysicalClientResolution internal constructor(
    private val owner: DistributedPhysicalClientJournalOwner,
    private val operation: DistributedPhysicalClientOperation,
) {
    public suspend fun advance(credentials: LocalGatewayCredentials): DistributedPhysicalClientPhase? = owner.advance(operation, credentials)
    public suspend fun result(): MutationReply.Recorded? = owner.startResult(operation)
    public suspend fun retire(): Boolean = owner.retireResolution(operation)
    override fun toString(): String = "DistributedPhysicalClientResolution([REDACTED])"
}

/** Correlation DATA following the concrete native force/readback, never a force assertion. */
internal fun distributedResolutionAcknowledgment(allocation: DomainId, requirement: StoredBytes,
    original: StoredBytes, resolution: StoredBytes): StoredBytes = StoredBytes(journalFrame(listOf(
    journalText("ukkera.distributed-physical-linked-resolution/v1"), journalText(allocation.value),
    journalText(journalBodyDigest(requirement).value), journalText(journalBodyDigest(original).value),
    journalText(journalBodyDigest(resolution).value)), 4_096))
