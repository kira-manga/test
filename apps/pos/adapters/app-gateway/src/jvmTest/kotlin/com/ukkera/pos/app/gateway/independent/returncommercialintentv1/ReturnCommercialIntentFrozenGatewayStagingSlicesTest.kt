package com.ukkera.pos.app.gateway.independent.returncommercialintentv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.StoredClientIntentV1
import com.ukkera.pos.app.gateway.independent.returnabsentexpectationv3.ReturnAbsentGatewayFixture
import com.ukkera.pos.app.gateway.independent.returnabsentexpectationv3.ReturnAbsentGatewayInputs
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.LocalReturnCaseCommandCodecV1
import com.ukkera.pos.sales.store.LocalReturnCaseProfileV1
import com.ukkera.pos.sales.store.LocalReturnCaseRequestV1
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RCI-I01 bounded client-form/staging slices from return-commercial-intent-v1.
 * The unchanged controlled public case/receipt replies are not genuine original
 * sources, and the journal's DURABLE seam reply is not native persistence proof.
 * These slices bind selected input, public summary role and saved-original
 * preservation only. Genuine UI navigation, Master authority, dispatch/retry,
 * restart/history and whole-case business-zero-effect assertions remain unbound.
 * RCI-I04 conclusive conflicts and older-peer compatibility are not exercised.
 */
class ReturnCommercialIntentFrozenGatewayStagingSlicesTest {
    private val input = ReturnAbsentGatewayInputs

    @Test
    fun rciI01_selectedDocumentaryFlagStagesCanonicalOriginalWithoutDispatch() = runTest {
        val fixture = knownEntitlementFixture()
        val form = qualifyForm(fixture)
        val checkbox = documentaryField(form)
        assertEquals(LocalInputKind.CHECKBOX, checkbox.kind)
        assertTrue(!checkbox.readOnly)

        val initial = fixture.intent(form, input.presentOperation)
        val selected = withDocumentarySelection(initial, true)
        assertEquals(initial.operationId, selected.operationId)
        assertEquals(initial.action, selected.action)
        assertEquals(initial.formVersion, selected.formVersion)
        assertEquals(initial.reference, selected.reference)
        assertEquals(legacyInputs(initial), legacyInputs(selected))
        assertEquals(LocalScalarValue.Flag(true), selected.values.single {
            it.field == LocalField.RECORD_ORIGINAL_COMMERCIAL_TIMING
        }.value)

        val staged = assertIs<LocalStageResult.Staged>(fixture.gateway.stage(selected))
        assertEquals(selected.operationId, staged.operation.operationId)
        assertEquals(LocalAction.RETURN_CASE_QUALIFY, staged.operation.action)
        assertEquals(LocalPendingPhase.STAGED, staged.operation.phase)
        assertNull(staged.operation.recordedDecision)
        assertSavedTrueOriginal(fixture, selected)

        // Bind the public summary to the dedicated field role, not invented text
        // or a language/layout oracle. Full eligibility/remedy UI review is unbound.
        val documentary = staged.operation.summary.single { it.label == checkbox.label }
        assertTrue(form.fields.filter { it.field != checkbox.field }.none { it.label == documentary.label })
        assertTrue(documentary.value.english.isNotBlank())
        assertTrue(documentary.value.arabic.isNotBlank())
        assertEquals(1, fixture.journal.stageCalls)
        fixture.assertNoDispatch()
    }

    @Test
    fun rciI01_laterCurrentFormSelectionDoesNotRewriteSavedTrueOriginal() = runTest {
        val fixture = knownEntitlementFixture()
        val original = withDocumentarySelection(fixture.intent(qualifyForm(fixture), input.presentOperation), true)
        assertIs<LocalStageResult.Staged>(fixture.gateway.stage(original))
        val saved = assertSavedTrueOriginal(fixture, original)
        val originalPayload = saved.request().payload
        val originalIdentity = saved.identity()
        val originalValues = original.values

        // A newly read form and changed current input are not a retry/history
        // event. Do not restage, submit or infer a conclusive conflict from them.
        val current = withDocumentarySelection(fixture.intent(qualifyForm(fixture), input.presentOperation), false)
        assertEquals(original.operationId, current.operationId)
        assertEquals(original.action, current.action)
        assertEquals(original.formVersion, current.formVersion)
        assertEquals(original.reference, current.reference)
        assertEquals(legacyInputs(original), legacyInputs(current))
        assertEquals(LocalScalarValue.Flag(false), current.values.single {
            it.field == LocalField.RECORD_ORIGINAL_COMMERCIAL_TIMING
        }.value)
        assertTrue(original.values != current.values)
        assertEquals(originalValues, original.values)

        val retained = assertSavedTrueOriginal(fixture, original)
        assertEquals(saved, retained)
        assertEquals(originalPayload, retained.request().payload)
        assertEquals(originalIdentity, retained.identity())
        assertEquals(saved.inputDigest, retained.inputDigest)
        assertEquals(saved.semanticSha256, retained.semanticSha256)
        assertEquals(1, fixture.journal.stageCalls)
        fixture.assertNoDispatch()
    }

    private fun knownEntitlementFixture(): ReturnAbsentGatewayFixture = ReturnAbsentGatewayFixture(input.presentCase).also { fixture ->
        fixture.endpoint.receiptReply = { fixture.endpoint.data(input.receipt(input.presentSale)) }
    }

    private suspend fun qualifyForm(fixture: ReturnAbsentGatewayFixture): LocalActionForm =
        assertIs<LocalReadResult.Available>(fixture.gateway.read(fixture.query())).page.forms.single {
            it.action == LocalAction.RETURN_CASE_QUALIFY
        }

    private fun documentaryField(form: LocalActionForm): LocalFormField.Scalar = assertIs<LocalFormField.Scalar>(
        form.fields.single { it.field == LocalField.RECORD_ORIGINAL_COMMERCIAL_TIMING })

    private fun withDocumentarySelection(original: LocalCommandIntent, selected: Boolean): LocalCommandIntent = LocalCommandIntent(
        original.operationId, original.action, original.formVersion, original.reference,
        original.values.map { value ->
            if (value.field == LocalField.RECORD_ORIGINAL_COMMERCIAL_TIMING)
                LocalScalarInput(value.field, LocalScalarValue.Flag(selected)) else value
        }, original.lineField, original.lines, original.arrivalEvidence)

    private fun legacyInputs(intent: LocalCommandIntent): List<LocalScalarInput> =
        intent.values.filter { it.field != LocalField.RECORD_ORIGINAL_COMMERCIAL_TIMING }

    private fun assertSavedTrueOriginal(fixture: ReturnAbsentGatewayFixture, original: LocalCommandIntent): StoredClientIntentV1 {
        val saved = assertNotNull(fixture.journal.entry)
        assertEquals(input.scope, saved.owner.scope)
        assertEquals(input.owner, saved.owner.actorId)
        assertEquals(input.device, saved.owner.deviceId)
        assertEquals(original.operationId, saved.operationId)
        assertEquals(original.action, saved.localAction)
        assertEquals(original.formVersion, saved.formVersion)
        assertEquals(LocalPendingPhase.STAGED, saved.localPhase)
        assertNull(saved.recorded)

        val request = saved.request()
        assertEquals(original.operationId, request.operationId)
        assertEquals(LocalReturnCaseProfileV1.COMMAND, request.command)
        val decoded = assertIs<LocalReturnCaseRequestV1.Qualify>(LocalReturnCaseCommandCodecV1.decode(request.payload))
        val legacy = assertIs<LocalReturnCaseRequestV1.Qualify>(LocalReturnCaseCommandCodecV1.decode(
            StoredBytes(input.presentCorrectBody.encodeToByteArray())))
        assertEquals(legacy, decoded.copy(recordOriginalCommercialTiming = false))
        assertTrue(decoded.recordOriginalCommercialTiming)
        assertEquals(JsonPrimitive(true), input.json.parseToJsonElement(request.payload.copyBytes().decodeToString())
            .jsonObject["recordOriginalCommercialTiming"])
        assertEquals(LocalReturnCaseCommandCodecV1.encode(decoded), request.payload)
        assertEquals(request.payload, LocalReturnCaseCommandCodecV1.normalize(request.payload))
        return saved
    }
}
