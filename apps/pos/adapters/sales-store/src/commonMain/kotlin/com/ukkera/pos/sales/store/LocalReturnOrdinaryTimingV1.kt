package com.ukkera.pos.sales.store

import com.ukkera.pos.sales.ElapsedReturnWindow
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.Serializable

/** Original commercial and applicable ordinary periods, with immutable Master intake timing. */
@Serializable
public data class LocalReturnOrdinaryTimingV1(
    public val categoryCode: String,
    public val originalCommercialDays: Int,
    public val applicableReceiptWindowDays: Int,
    public val receiptEventId: DomainId,
    public val receivedAt: UtcInstant,
    public val receiptTimeZoneId: String,
    public val submissionOperationId: OperationId,
    public val submittedAt: UtcInstant,
    public val expiresAt: UtcInstant?,
    public val status: LocalReturnCommercialWindowStatusV1,
) {
    public val effectiveDays: Int get() = maxOf(originalCommercialDays, applicableReceiptWindowDays)

    init {
        localReason(categoryCode)
        returnCaseTimeZone(receiptTimeZoneId)
        require(originalCommercialDays > 0 && applicableReceiptWindowDays > 0)
        val window = ElapsedReturnWindow(effectiveDays)
        require(expiresAt == window.expiresAt(receivedAt) &&
            status == ordinaryWindowStatus(window.containsSubmission(receivedAt, submittedAt))) {
            "sales.return.ordinary_timing"
        }
    }

    public companion object {
        /** Applicability, exact source provenance and inspection are established separately. */
        public fun observe(categoryCode: String, originalCommercialDays: Int, applicableReceiptWindowDays: Int,
            receiptEventId: DomainId, receivedAt: UtcInstant, receiptTimeZoneId: String,
            submissionOperationId: OperationId, submittedAt: UtcInstant): LocalReturnOrdinaryTimingV1 {
            val window = ElapsedReturnWindow(maxOf(originalCommercialDays, applicableReceiptWindowDays))
            return LocalReturnOrdinaryTimingV1(categoryCode, originalCommercialDays, applicableReceiptWindowDays,
                receiptEventId, receivedAt, receiptTimeZoneId, submissionOperationId, submittedAt,
                window.expiresAt(receivedAt), ordinaryWindowStatus(window.containsSubmission(receivedAt, submittedAt)))
        }
    }
}

private fun ordinaryWindowStatus(within: Boolean?): LocalReturnCommercialWindowStatusV1 = when (within) {
    true -> LocalReturnCommercialWindowStatusV1.WITHIN
    false -> LocalReturnCommercialWindowStatusV1.OUTSIDE
    null -> LocalReturnCommercialWindowStatusV1.UNAVAILABLE
}

/** Match ordinary timing to immutable intake, signed captured terms and actual receipt literals.
 * The installed catalog source separately validates the exact signed current matching rule. */
internal fun requireReturnOrdinaryTiming(assessment: LocalReturnCaseAssessmentV1,
    request: LocalReturnCaseRequestV1.Qualify, state: LocalReturnCaseStateV1,
    binding: LocalReturnCaseReceiptBindingV1, capture: LocalSalesReturnPolicyCaptureV2?,
    sold: Quantity?, receipts: Map<DomainId, LocalRetailCustomerReceiptEventV1>) {
    val timing = assessment.ordinaryTiming
    if (assessment.outcome == LocalReturnOrdinaryAssessmentV1.ELIGIBLE) requireLocalSalesSource(timing != null &&
        state.facts.claimedReason == LocalReturnClaimReasonV1.CHANGE_OF_MIND &&
        state.facts.requestedRemedy == LocalReturnRequestedRemedyV1.REFUND &&
        request.ordinaryInspection?.condition == LocalReturnInspectionConditionV1.UNCHANGED &&
        timing.status == LocalReturnCommercialWindowStatusV1.WITHIN)
    if (timing == null) return
    requireLocalSalesSource(state.facts.claimedReason == LocalReturnClaimReasonV1.CHANGE_OF_MIND &&
        state.facts.requestedRemedy == LocalReturnRequestedRemedyV1.REFUND &&
        timing.submissionOperationId == state.originalOperationId && timing.submittedAt == state.recordedAt &&
        assessment.customerReceipts.size == 1 && assessment.customerReceipts.single().id == timing.receiptEventId &&
        assessment.applicableRights.size == 1)
    val originalCapture = returnCaseRequired(capture)
    val portion = returnCaseRequired(originalCapture.input.portions.singleOrNull { it.portionId == binding.portionId })
    val publication = returnCaseRequired(originalCapture.input.publications.singleOrNull { it.publicationId == portion.publicationId })
    val promise = returnCaseRequired(publication.promise)
    val receiptReference = assessment.customerReceipts.single()
    val receipt = returnCaseRequired(receipts[timing.receiptEventId])
    requireLocalSalesSource(publication.state == LocalSalesReturnPromiseStateV2.SIGNED_PRESENT &&
        timing.categoryCode == promise.categoryCode && timing.originalCommercialDays == promise.changeOfMindReceiptWindowDays &&
        assessment.policyId == promise.policyId && assessment.policyRevision == promise.policyRevision &&
        assessment.salePolicyCapture?.let {
            it.kind == LocalSalesReturnPolicyCaptureCodecV2.KIND && it.schema == LocalSalesReturnPolicyCaptureCodecV2.SCHEMA &&
                it.id == originalCapture.id && it.producerOperationId == originalCapture.originalQuoteOperationId
        } == true && originalCapture.scope == state.scope.value() && sold != null &&
        receiptReference.kind == LocalRetailCustomerReceiptProfileV1.EVENT &&
        receiptReference.producerOperationId == receipt.operationId &&
        receipt.after.source.scope == state.scope && receipt.after.source.saleId == binding.saleId &&
        receipt.portions.singleOrNull { it.portionId == binding.portionId }?.quantity == sold &&
        timing.receivedAt == receipt.receivedAt && timing.receiptTimeZoneId == receipt.receiptTimeZoneId &&
        timing.receiptEventId.value == receipt.operationId.value)
    if (assessment.outcome == LocalReturnOrdinaryAssessmentV1.ELIGIBLE) requireLocalSalesSource(
        assessment.customerReceivedAt == timing.receivedAt && assessment.receiptTimeZoneId == timing.receiptTimeZoneId &&
            (assessment.remedyDueAt == null || assessment.remedyDueAt >= state.recordedAt))
}
