package com.ukkera.pos.app.independent.credentialentry

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextClearance
import com.ukkera.pos.app.ui.AppLanguage
import org.junit.Test

/**
 * Unchanged CEN-V1-007/008/009 and additive V1-C1 rendered assertion slices.
 * The real shared entry screens run against refusing gateways. This is not native keyboard,
 * authentication, employee creation, identity-allocation-attempt or accepted-session qualification.
 */
class CredentialEntryFrozenRenderedSlicesTest : CredentialEntryRenderedHarness() {
    private val signIn = listOf(EntryMode.SIGN_IN)
    private val enrollment = listOf(EntryMode.ENROLLMENT_SUPPORTED, EntryMode.ENROLLMENT_UNSUPPORTED)

    @Test fun cenV1007SignInRetainsAsciiAndUtf16OversizedEditsAndCorrections() = oversizedEdits(signIn)
    @Test fun cenV1007EnrollmentRetainsAsciiAndUtf16OversizedEditsAndCorrections() = oversizedEdits(enrollment)
    @Test fun cenV1007SignInRetainsWhitespaceAndDecomposedEdits() = exactEdits(signIn)
    @Test fun cenV1007EnrollmentRetainsWhitespaceAndDecomposedEdits() = exactEdits(enrollment)

    @Test fun cenV1007C1EnglishUnsupportedNameDraftRemainsInvalidAndClearable() = unsupportedName(AppLanguage.ENGLISH)
    @Test fun cenV1007C1ArabicUnsupportedNameDraftRemainsInvalidAndClearable() = unsupportedName(AppLanguage.ARABIC)

    @Test fun cenV1008SignInButtonRejectsEveryFrozenInvalidRowAndCorrectionDispatchesOnce() =
        invalidSubmissions(signIn, SubmitAction.BUTTON)
    @Test fun cenV1008SignInImeRejectsEveryFrozenInvalidRowAndCorrectionDispatchesOnce() =
        invalidSubmissions(signIn, SubmitAction.IME)
    @Test fun cenV1008EnrollmentButtonRejectsEveryFrozenInvalidRowAndCorrectionDispatchesOnce() =
        invalidSubmissions(enrollment, SubmitAction.BUTTON)
    @Test fun cenV1008EnrollmentImeRejectsEveryFrozenInvalidRowAndCorrectionDispatchesOnce() =
        invalidSubmissions(enrollment, SubmitAction.IME)

    @Test fun cenV1008EligibleWhitespaceAndDecomposedEditsReachRefusingGatewayExactly() {
        contexts(signIn + enrollment) { mode, language ->
            for ((row, input) in listOf("V03" to whitespace(mode), "V05" to decomposed(mode))) {
                for (action in SubmitAction.entries) {
                    val fixture = show(mode, language, "CEN-V1-007/008 $row $action")
                    fill(fixture, input)
                    assertInputRetained(fixture, input)
                    assertNoSubmission(fixture)
                    confirm(fixture)
                    button(fixture).assertIsEnabled()
                    expectRefusal(fixture, input)
                    attempt(fixture, action)
                    assertOneRefusal(fixture)
                }
            }
        }
    }

    @Test fun cenV1009EnrollmentChangesClearConsentAndCorrectionOrChangingBackCannotRestoreIt() =
        consentChanges(enrollment)
    @Test fun cenV1009SignInAccountChangesClearDeviceMoveAndCorrectionOrChangingBackCannotRestoreIt() =
        consentChanges(signIn)

    private fun oversizedEdits(modes: List<EntryMode>) {
        contexts(modes) { mode, language ->
            for (crossing in crossings(mode)) {
                val fixture = show(mode, language, "CEN-V1-007 ${crossing.id}")
                val initial = minimum(mode)
                fill(fixture, initial)
                assertPresented(fixture, crossing.field, initial.value(crossing.field), 1)
                replace(fixture, crossing.field, crossing.oversized)
                assertPresented(fixture, crossing.field, crossing.oversized, crossing.oversizedUnits)
                assertBoundsError(fixture, crossing.field, expected = true)
                assertInvalidNotice(fixture)
                button(fixture).assertIsNotEnabled()
                assertNoSubmission(fixture)
                replace(fixture, crossing.field, crossing.corrected)
                assertPresented(fixture, crossing.field, crossing.corrected, crossing.correctedUnits)
                assertBoundsError(fixture, crossing.field, expected = false)
                assertNoInvalidNotice()
                assertNoSubmission(fixture)
            }
        }
    }

    private fun exactEdits(modes: List<EntryMode>) {
        contexts(modes) { mode, language ->
            for (field in mode.fields) {
                for ((row, input) in listOf("V03" to whitespace(mode), "V05" to decomposed(mode))) {
                    val fixture = show(mode, language, "CEN-V1-007 $row $field")
                    fill(fixture, minimum(mode))
                    replace(fixture, field, input.value(field))
                    assertPresented(fixture, field, input.value(field))
                    assertBoundsError(fixture, field, expected = false)
                    assertNoInvalidNotice()
                    assertNoSubmission(fixture)
                }
            }
        }
    }

    private fun unsupportedName(language: AppLanguage) {
        val fixture = show(EntryMode.ENROLLMENT_SUPPORTED, language, "CEN-V1-007 V1-C1")
        fill(fixture, minimum(fixture.mode))
        confirm(fixture)
        button(fixture).assertIsEnabled()
        reloadNameSupport(fixture, supported = false)
        assertPresented(fixture, Field.NAME, "n", 1)
        assertBoundsError(fixture, Field.NAME, expected = true)
        assertInvalidNotice(fixture)
        button(fixture).assertIsNotEnabled()
        attempt(fixture, SubmitAction.BUTTON)
        attempt(fixture, SubmitAction.IME)
        assertPresented(fixture, Field.NAME, "n", 1)
        assertNoSubmission(fixture)
        field(fixture, Field.NAME).performTextClearance()
        ui.onAllNodes(hasText(fixture.labels.field(Field.NAME)) and hasSetTextAction()).assertCountEquals(0)
        assertNoInvalidNotice()
        assertConfirmation(fixture, checked = false)
        button(fixture).assertIsNotEnabled()
        assertNoSubmission(fixture)
        confirm(fixture)
        button(fixture).assertIsEnabled()
        assertNoSubmission(fixture)
        // Re-expose the same transient field through the real status flow to observe exact empty state.
        reloadNameSupport(fixture, supported = true)
        assertPresented(fixture, Field.NAME, "", 0)
        assertNoSubmission(fixture)
    }

    private fun invalidSubmissions(modes: List<EntryMode>, action: SubmitAction) {
        contexts(modes) { mode, language ->
            for ((row, input) in invalidRows(mode)) {
                val fixture = show(mode, language, "CEN-V1-008 $row $action")
                fill(fixture, input)
                confirm(fixture)
                assertInputRetained(fixture, input)
                button(fixture).assertIsNotEnabled()
                for (crossing in crossings(mode).filter { it.oversized == input.value(it.field) }) {
                    assertBoundsError(fixture, crossing.field, expected = true)
                    assertInvalidNotice(fixture)
                }
                attempt(fixture, action)
                assertInputRetained(fixture, input)
                button(fixture).assertIsNotEnabled()
                assertNoSubmission(fixture)
                val corrected = upperBoundary(mode)
                fill(fixture, corrected)
                assertInputRetained(fixture, corrected)
                for (field in mode.fields) assertBoundsError(fixture, field, expected = false)
                assertNoInvalidNotice()
                confirm(fixture)
                button(fixture).assertIsEnabled()
                expectRefusal(fixture, corrected)
                attempt(fixture, action)
                assertOneRefusal(fixture)
            }
        }
    }

    private fun consentChanges(modes: List<EntryMode>) {
        contexts(modes) { mode, language ->
            val fields = if (mode == EntryMode.SIGN_IN) listOf(Field.ACCOUNT) else mode.fields
            for (field in fields) {
                val plain = minimum(mode)
                val upper = upperBoundary(mode).value(field)
                val spaced = whitespace(mode)
                val combining = decomposed(mode)
                val changes = listOf(
                    Change("valid-boundary", plain, upper, upper),
                    Change("whitespace-only", spaced,
                        if (field == Field.PASSWORD) "  " else spaced.value(field).removePrefix(" ").removeSuffix(" "),
                        spaced.value(field)),
                    Change("decomposed-to-composed", combining, "\u00E9", combining.value(field)),
                ) + crossings(mode).filter { it.field == field }.map {
                    Change(it.id, plain, it.oversized, it.corrected, oversized = true)
                }
                for (change in changes) {
                    val fixture = show(mode, language, "CEN-V1-009 $field ${change.id}")
                    fill(fixture, change.initial)
                    confirm(fixture)
                    button(fixture).assertIsEnabled()
                    replace(fixture, field, change.changed)
                    assertPresented(fixture, field, change.changed)
                    assertConfirmation(fixture, checked = false)
                    assertBoundsError(fixture, field, expected = change.oversized)
                    assertNoSubmission(fixture)
                    if (change.corrected != change.changed) replace(fixture, field, change.corrected)
                    assertPresented(fixture, field, change.corrected)
                    assertBoundsError(fixture, field, expected = false)
                    assertConfirmation(fixture, checked = false)
                    if (change.initial.value(field) != change.corrected)
                        replace(fixture, field, change.initial.value(field))
                    assertPresented(fixture, field, change.initial.value(field))
                    assertConfirmation(fixture, checked = false)
                    assertNoInvalidNotice()
                    assertNoSubmission(fixture)
                    if (mode == EntryMode.SIGN_IN) button(fixture).assertIsEnabled()
                    else {
                        button(fixture).assertIsNotEnabled()
                        attempt(fixture, SubmitAction.IME)
                        assertNoSubmission(fixture)
                    }
                    confirm(fixture)
                    button(fixture).assertIsEnabled()
                    assertNoSubmission(fixture)
                }
            }
        }
    }

    private data class Crossing(val id: String, val field: Field, val oversized: String, val oversizedUnits: Int,
        val corrected: String, val correctedUnits: Int)
    private fun crossings(mode: EntryMode): List<Crossing> = listOf(
        Crossing("B06", Field.ACCOUNT, "a".repeat(129), 129, "a".repeat(128), 128),
        Crossing("B08", Field.PASSWORD, "p".repeat(257), 257, "p".repeat(256), 256),
        Crossing("U02", Field.ACCOUNT, rockets(64) + "a", 129, rockets(64), 128),
        Crossing("U04", Field.PASSWORD, rockets(128) + "p", 257, rockets(128), 256),
    ) + if (mode.supportsName) listOf(
        Crossing("D06", Field.NAME, "n".repeat(121), 121, "n".repeat(120), 120),
        Crossing("U06", Field.NAME, rockets(60) + "n", 121, rockets(60), 120),
    ) else emptyList()

    private fun invalidRows(mode: EntryMode): List<Pair<String, Input>> {
        val valid = minimum(mode)
        return listOf(
            "B01" to valid.copy(account = ""),
            "B02" to valid.copy(account = " \t\r\n"),
            "B06" to valid.copy(account = "a".repeat(129)),
            "B07" to valid.copy(password = ""),
            "B08" to valid.copy(password = "p".repeat(257)),
            "U02" to valid.copy(account = rockets(64) + "a"),
            "U04" to valid.copy(password = rockets(128) + "p"),
        ) + if (mode.supportsName) listOf(
            "D01" to valid.copy(name = ""),
            "D02" to valid.copy(name = " \t\r\n"),
            "D06" to valid.copy(name = "n".repeat(121)),
            "U06" to valid.copy(name = rockets(60) + "n"),
        ) else emptyList()
    }

    private data class Change(val id: String, val initial: Input, val changed: String, val corrected: String,
        val oversized: Boolean = false)
}
