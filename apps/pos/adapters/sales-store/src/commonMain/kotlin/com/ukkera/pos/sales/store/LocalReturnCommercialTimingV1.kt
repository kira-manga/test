package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.sales.ElapsedReturnWindow
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.Serializable

/** A temporal observation only, never legal applicability or return/refund authority. */
@Serializable
public enum class LocalReturnCommercialWindowStatusV1 { WITHIN, OUTSIDE, UNAVAILABLE }

/** Original commercial window, distinct from remedyDueAt and customer-asserted dates. */
@Serializable
public data class LocalReturnCommercialTimingV1(
    public val days: Int,
    public val receiptEventId: DomainId,
    public val receivedAt: UtcInstant,
    public val receiptTimeZoneId: String,
    public val submissionOperationId: OperationId,
    public val submittedAt: UtcInstant,
    public val expiresAt: UtcInstant?,
    public val status: LocalReturnCommercialWindowStatusV1,
) {
    init {
        returnCaseTimeZone(receiptTimeZoneId)
        val window = ElapsedReturnWindow(days)
        require(expiresAt == window.expiresAt(receivedAt) &&
            status == commercialWindowStatus(window.containsSubmission(receivedAt, submittedAt))) {
            "sales.return.commercial_timing"
        }
    }

    public companion object {
        /** Inputs must separately be established by genuine original producers. */
        public fun observe(days: Int, receiptEventId: DomainId, receivedAt: UtcInstant,
            receiptTimeZoneId: String, submissionOperationId: OperationId,
            submittedAt: UtcInstant): LocalReturnCommercialTimingV1 {
            val window = ElapsedReturnWindow(days)
            return LocalReturnCommercialTimingV1(days, receiptEventId, receivedAt, receiptTimeZoneId,
                submissionOperationId, submittedAt, window.expiresAt(receivedAt),
                commercialWindowStatus(window.containsSubmission(receivedAt, submittedAt)))
        }
    }
}

private fun commercialWindowStatus(within: Boolean?): LocalReturnCommercialWindowStatusV1 = when (within) {
    true -> LocalReturnCommercialWindowStatusV1.WITHIN
    false -> LocalReturnCommercialWindowStatusV1.OUTSIDE
    null -> LocalReturnCommercialWindowStatusV1.UNAVAILABLE
}

/** Literal facts only; callers separately close every exact source/producer reference. */
internal suspend fun readReturnCommercialReceiptEvents(scope: BranchScope,
    references: List<LocalReturnCaseSourceReferenceV1>,
    read: suspend (AggregateKey) -> StoredAggregate?): Map<DomainId, LocalRetailCustomerReceiptEventV1> = references
    .filter { it.kind == LocalRetailCustomerReceiptProfileV1.EVENT }.associate { reference ->
        val key = AggregateKey(reference.kind, reference.id)
        val row = returnCaseRequired(read(key))
        requireLocalSalesSource(row.key == key && row.revision == reference.storageRevision &&
            row.schema == reference.schema && row.digest == reference.digest)
        reference.id to retailCustomerReceiptEventMembershipValue(row.key, row.revision,
            row.schema, row.payload, row.digest, scope)
    }

/** Validate the observation against the original case, capture and actual receipt literals. */
internal fun requireReturnCommercialTiming(assessment: LocalReturnCaseAssessmentV1,
    request: LocalReturnCaseRequestV1.Qualify, state: LocalReturnCaseStateV1,
    binding: LocalReturnCaseReceiptBindingV1, capture: LocalSalesReturnPolicyCaptureV2?,
    sold: Quantity?, receipts: Map<DomainId, LocalRetailCustomerReceiptEventV1>) {
    val timing = assessment.originalCommercialTiming ?: return
    requireLocalSalesSource(request.recordOriginalCommercialTiming &&
        assessment.outcome == LocalReturnOrdinaryAssessmentV1.PENDING &&
        timing.submissionOperationId == state.originalOperationId && timing.submittedAt == state.recordedAt &&
        assessment.customerReceipts.size == 1 && assessment.customerReceipts.single().id == timing.receiptEventId)
    val originalCapture = returnCaseRequired(capture)
    val portion = returnCaseRequired(originalCapture.input.portions.singleOrNull { it.portionId == binding.portionId })
    val promise = returnCaseRequired(originalCapture.input.publications.singleOrNull {
        it.publicationId == portion.publicationId
    }?.promise)
    val receiptReference = assessment.customerReceipts.single()
    val receipt = returnCaseRequired(receipts[timing.receiptEventId])
    requireLocalSalesSource(assessment.salePolicyCapture?.let {
        it.kind == LocalSalesReturnPolicyCaptureCodecV2.KIND && it.schema == LocalSalesReturnPolicyCaptureCodecV2.SCHEMA &&
            it.id == originalCapture.id && it.producerOperationId == originalCapture.originalQuoteOperationId
    } == true && originalCapture.scope == state.scope.value() &&
        timing.days == promise.changeOfMindReceiptWindowDays && sold != null &&
        receiptReference.kind == LocalRetailCustomerReceiptProfileV1.EVENT &&
        receiptReference.producerOperationId == receipt.operationId &&
        receipt.after.source.scope == state.scope && receipt.after.source.saleId == binding.saleId &&
        receipt.portions.singleOrNull { it.portionId == binding.portionId }?.quantity == sold &&
        timing.receivedAt == receipt.receivedAt && timing.receiptTimeZoneId == receipt.receiptTimeZoneId &&
        timing.receiptEventId.value == receipt.operationId.value)
}
