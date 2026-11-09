package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.ClaimKey
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.time.Instant

public object LocalReturnCaseProfileV1 {
    public val COMMAND: CommandKey = CommandKey("sales.return.case.write", 1)
    public const val CASE: String = "sales.return.case"
    public const val EVENT: String = "sales.return.case-event"
    public const val WORK: String = "sales.return.case-work"
    public const val ENTITLEMENT_BEFORE: String = "sales.return.case-entitlement-before"
    public const val CLAIM_BEFORE: String = "sales.return.case-claim-before"
    public const val WORK_BEFORE: String = "sales.return.case-cash-work-before"
    public const val NAMESPACE: String = "sales.return.case-source"
    public const val MAX_EVENTS: Int = 64
    public const val MAX_OPEN_CASES: Int = 256
    public const val MAX_REQUEST_BYTES: Int = 8_192
    public const val MAX_READ_BYTES: Int = 32_768
    public fun key(kind: String, id: DomainId): AggregateKey = AggregateKey(kind, id)
    public fun source(name: String, id: DomainId): ClaimKey = ClaimKey(NAMESPACE, "$name/${id.value}")
}

@Serializable
public enum class LocalReturnCaseStageV1 { PENDING, BOUND_UNACCEPTED, ACCEPTED_DISPOSITION_PENDING }

@Serializable
public enum class LocalReturnRequestedRemedyV1 { REFUND, EXCHANGE, REPAIR, OTHER }

@Serializable
public enum class LocalReturnClaimReasonV1 {
    CHANGE_OF_MIND, WRONG_SUPPLIED_ITEM, DEFECTIVE_OR_DAMAGED,
    MERCHANT_PREPARATION_ERROR, QUALITY_COMPLAINT, FAILED_DELIVERY, UNPERFORMED_SERVICE,
}

@Serializable
public enum class LocalReturnInspectionConditionV1 { UNCHANGED }

/** Employee attestation bound to the original protected qualification operation. */
@Serializable
public data class LocalReturnOrdinaryInspectionV1(
    public val condition: LocalReturnInspectionConditionV1,
    public val evidence: LocalCashEvidenceV1,
    /** Exact employee-reported offset timestamp; UTC evidence is only its comparison coordinate. */
    public val reportedOccurredAt: String,
) {
    init {
        require(reportedOccurredAt.length in 1..64 && Instant.parse(reportedOccurredAt) == evidence.occurredAt.value) {
            "sales.return.inspection_time"
        }
    }
}

/** Original asserted facts are never overwritten by later receipt discovery or qualification. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
public data class LocalReturnCaseFactsV1(
    public val asserted: LocalReturnClaimFactsV1,
    public val condition: String,
    public val requestedRemedy: LocalReturnRequestedRemedyV1,
    public val claimTimeZoneId: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    public val claimedReason: LocalReturnClaimReasonV1? = null,
) {
    init {
        localReason(condition); localReason(asserted.reason); returnCaseTimeZone(claimTimeZoneId)
        require(asserted.quantity == null || asserted.quantity.scaled > 0)
    }
}

/** An asserted original-receipt link, NOT customer delivery evidence or an entitlement decision. */
@Serializable
public data class LocalReturnCaseReceiptBindingV1(
    public val saleId: DomainId, public val portionId: DomainId, public val quantity: Quantity,
    public val evidence: LocalCashEvidenceV1,
) {
    init { require(quantity.scaled > 0) }
}

@Serializable
public sealed interface LocalReturnCaseRequestV1 {
    @Serializable @SerialName("RECORD")
    public data class Record(public val caseId: DomainId, public val facts: LocalReturnCaseFactsV1) : LocalReturnCaseRequestV1
    @Serializable @SerialName("APPEND_RECEIPT_EVIDENCE")
    public data class AppendReceiptEvidence(public val caseId: DomainId, public val expectedCaseRevision: Revision,
        public val binding: LocalReturnCaseReceiptBindingV1) : LocalReturnCaseRequestV1
    /** Omit the entitlement expectation when none was observed. This is not proof of
     * receipt absence: the Master independently checks the original and refuses a
     * missing expectation if a genuine entitlement exists. Existing supplied-revision
     * bodies retain their canonical bytes; older Masters reject the new omitted form. */
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable @SerialName("QUALIFY")
    public data class Qualify(public val caseId: DomainId, public val expectedCaseRevision: Revision,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        public val expectedEntitlementRevision: Revision? = null,
        /** Optional documentary observation only; omitted defaults preserve original bodies. */
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        public val recordOriginalCommercialTiming: Boolean = false,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        public val ordinaryInspection: LocalReturnOrdinaryInspectionV1? = null) : LocalReturnCaseRequestV1
    @Serializable @SerialName("ACCEPT")
    public data class Accept(public val caseId: DomainId, public val expectedCaseRevision: Revision,
        public val decisionOperationId: OperationId, public val expectedEntitlementRevision: Revision,
        public val expectedClaimRevision: Revision, public val returnId: DomainId,
        public val disposition: LocalReturnDispositionV1, public val inspectionEvidence: LocalCashEvidenceV1) : LocalReturnCaseRequestV1
}

@Serializable
public enum class LocalReturnCaseActionV1 { RECORDED, EVIDENCE_APPENDED, ASSESSMENT_PENDING, BOUND, ACCEPTED }

@Serializable
public sealed interface LocalReturnCaseResultV1 {
    /** Durable only when the enclosing kernel reply is committed Recorded. */
    @Serializable @SerialName("APPLIED")
    public data class Applied(public val action: LocalReturnCaseActionV1, public val caseId: DomainId,
        public val caseRevision: Revision, public val stage: LocalReturnCaseStageV1,
        public val eventOperationId: OperationId, public val claimRevision: Revision? = null,
        public val returnId: DomainId? = null, public val entitlementRevision: Revision? = null,
        public val originalPayable: Money? = null) : LocalReturnCaseResultV1
    /** Stable public code only, never an exception message or evidence transcript. */
    @Serializable @SerialName("REFUSED")
    public data class Refused(public val reason: String) : LocalReturnCaseResultV1 {
        init { returnCaseCode(reason) }
    }
}

public object LocalReturnCaseCommandCodecV1 {
    public fun command(operationId: OperationId, request: LocalReturnCaseRequestV1, actionApprovalId: DomainId? = null): MutationRequest =
        MutationRequest(operationId, LocalReturnCaseProfileV1.COMMAND, encode(request), actionApprovalId)
    public fun encode(request: LocalReturnCaseRequestV1): StoredBytes =
        localSalesEncode(request, LocalReturnCaseRequestV1.serializer(), LocalReturnCaseProfileV1.MAX_REQUEST_BYTES)
    public fun decode(body: StoredBytes): LocalReturnCaseRequestV1 =
        localSalesDecode(body, LocalReturnCaseRequestV1.serializer(), LocalReturnCaseProfileV1.MAX_REQUEST_BYTES)
    public fun normalize(body: StoredBytes): StoredBytes = encode(decode(body))
    public fun decodeResult(body: StoredBytes): LocalReturnCaseResultV1 = localSalesDecode(body, LocalReturnCaseResultV1.serializer(), 4_096)
    internal fun result(value: LocalReturnCaseResultV1): StoredBytes = localSalesEncode(value, LocalReturnCaseResultV1.serializer(), 4_096)
}

internal fun LocalReturnCaseRequestV1.caseId(): DomainId = when (this) {
    is LocalReturnCaseRequestV1.Record -> caseId
    is LocalReturnCaseRequestV1.AppendReceiptEvidence -> caseId
    is LocalReturnCaseRequestV1.Qualify -> caseId
    is LocalReturnCaseRequestV1.Accept -> caseId
}

internal fun LocalReturnCaseRequestV1.expectedRevision(): Revision? = when (this) {
    is LocalReturnCaseRequestV1.Record -> null
    is LocalReturnCaseRequestV1.AppendReceiptEvidence -> expectedCaseRevision
    is LocalReturnCaseRequestV1.Qualify -> expectedCaseRevision
    is LocalReturnCaseRequestV1.Accept -> expectedCaseRevision
}
