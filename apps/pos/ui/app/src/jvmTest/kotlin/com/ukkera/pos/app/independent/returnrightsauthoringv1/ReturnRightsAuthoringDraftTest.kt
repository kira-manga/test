package com.ukkera.pos.app.independent.returnrightsauthoringv1

import com.ukkera.pos.app.LocalReturnRightsPublicationResult
import com.ukkera.pos.app.LocalReturnRightsPublicationRule
import com.ukkera.pos.app.LocalReturnRightsPublicationStatusResult
import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.app.LocalStageResult
import com.ukkera.pos.app.UnavailableLocalReturnRightsPublication
import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

/** Unsigned public draft/editor boundaries only; no original native preparation or review is supplied. */
class ReturnRightsAuthoringDraftTest {
    private val inputs = FrozenReturnRightsAuthoringUiInputs

    @Test
    fun raa01D0DraftDetachesCallerAndAccessorCollectionsAndRedactsDiagnosticContent() {
        val caller = inputs.rules().toMutableList()
        val actual = inputs.draft(ruleValues = caller)
        inputs.assertLiteralDraft(actual)
        caller[0] = caller[0].copy(receiptWindowDays = inputs.rules("D1")[0].receiptWindowDays)
        caller.clear()
        val exported = actual.rules
        if (exported is MutableList<LocalReturnRightsPublicationRule>) {
            try { exported.clear() } catch (_: UnsupportedOperationException) { /* An immutable copy also preserves ownership. */ }
        }
        inputs.assertLiteralDraft(actual, context = "Caller mutation cannot change the detached D0 draft")
        assertEquals("LocalReturnRightsPublicationDraft([REDACTED])", actual.toString())
        for (literal in listOf(actual.publicationId.value, actual.policyId.value, actual.legalSourceUri,
            actual.continuingRights.english, actual.continuingRights.arabic)) assertFalse(actual.toString().contains(literal))
    }

    @Test
    fun raa01EditorConversionPreservesEveryD0LiteralRulePeriodAndExceptionWithoutCurrentPolicyProjection() {
        val original = inputs.editor()
        inputs.assertLiteralDraft(original.toDraft())
        inputs.assertLiteralDraft(inputs.editor("D1").toDraft(), "D1")
        // Mapping an unrelated later input cannot mutate the original explicit revision or documentary terms.
        inputs.assertLiteralDraft(original.toDraft())
    }

    @Test
    fun raa02FrozenMissingOrNoncanonicalPublicationAndEmptyRulesRefuseBeforeProposalConstruction() {
        for (name in listOf("missing-publication", "noncanonical-publication")) {
            val edit = inputs.invalidEdit(name)
            assertFailsWith<IllegalArgumentException>(name) { DomainId(edit.raaString("value")) }
        }
        assertFailsWith<IllegalArgumentException>("empty-rules") { inputs.editor().copy(rules = emptyList()).toDraft() }
        inputs.assertLiteralDraft(inputs.editor().toDraft())
        // Other documentary validity and envelope-size checks require their actual typed/native boundary.
    }

    @Test
    fun raa17UnavailableProductionBindingProvidesNoProposalReviewOutputOrStagedRequest() = runTest {
        val unavailable = UnavailableLocalReturnRightsPublication
        assertFalse(unavailable.configured)
        assertEquals(LocalServiceProblem.NOT_CONFIGURED,
            assertIs<LocalReturnRightsPublicationStatusResult.Unavailable>(unavailable.status()).problem)
        for (result in listOf(unavailable.prepare(inputs.draft()), unavailable.review(inputs.operationId.let { DomainId(it.value) }),
            unavailable.completed(DomainId(inputs.operationId.value))))
            assertEquals(LocalServiceProblem.NOT_CONFIGURED, assertIs<LocalReturnRightsPublicationResult.Unavailable>(result).problem)
        assertEquals(LocalServiceProblem.NOT_CONFIGURED,
            assertIs<LocalStageResult.NotStaged>(unavailable.stageActivation(DomainId(inputs.operationId.value))).problem)
        // No legitimate owner-bound review is available for an approval call or head-buffer cleanup assertion.
    }
}
