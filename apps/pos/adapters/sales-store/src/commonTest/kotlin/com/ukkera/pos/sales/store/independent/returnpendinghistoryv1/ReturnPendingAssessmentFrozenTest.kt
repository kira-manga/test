package com.ukkera.pos.sales.store.independent.returnpendinghistoryv1

import com.ukkera.pos.sales.store.LocalReturnCaseAssessmentV1
import com.ukkera.pos.sales.store.LocalReturnOrdinaryAssessmentV1
import com.ukkera.pos.sales.store.returnCaseUnavailableAssessment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Assessment-field slices of frozen return-pending-history-v1 RPH-V01/V02 only.
 * The helper accepts the synthetic receipt-descriptor boolean, not authorization,
 * revisions, native receipt provenance, evidence, dates or a promised policy.
 * Its value does not establish a committed action/stage, acceptance or zero effects.
 * RPH-V03/V04 and all fourteen whole endpoint/history cases remain unbound here.
 */
class ReturnPendingAssessmentFrozenTest {
    @Test
    fun rphV01_missingOriginalReceipt_assessmentFieldsOnly() {
        // PURE_MISSING_RECEIPT.genuineOriginalReceiptDescriptorPresent = false.
        val assessment = returnCaseUnavailableAssessment(originalReceiptPresent = false)

        assertPendingFields(assessment, "ORIGINAL_RECEIPT_REQUIRED")
    }

    @Test
    fun rphV02_receiptDescriptorWithoutQualificationSource_assessmentFieldsOnly() {
        // PURE_RECEIPT_NO_SOURCE overrides the descriptor boolean to true.
        // This pure descriptor is not authentication of an original receipt.
        val assessment = returnCaseUnavailableAssessment(originalReceiptPresent = true)

        assertPendingFields(assessment, "QUALIFICATION_SOURCE_REQUIRED")
    }

    private fun assertPendingFields(assessment: LocalReturnCaseAssessmentV1, reason: String) {
        assertEquals(LocalReturnOrdinaryAssessmentV1.PENDING, assessment.outcome)
        assertEquals(reason, assessment.reasonCode)
        assertEquals("EVIDENCE_OR_POLICY_REVIEW", assessment.route)
        assertNull(assessment.policyId)
        assertNull(assessment.policyRevision)
        assertNull(assessment.salePolicyCapture)
        assertTrue(assessment.applicableRights.isEmpty())
        assertTrue(assessment.customerReceipts.isEmpty())
        assertNull(assessment.customerReceivedAt)
        assertNull(assessment.receiptTimeZoneId)
        assertNull(assessment.remedyDueAt)
    }
}
