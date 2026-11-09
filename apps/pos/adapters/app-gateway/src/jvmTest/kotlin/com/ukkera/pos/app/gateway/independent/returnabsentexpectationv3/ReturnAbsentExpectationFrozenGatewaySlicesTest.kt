package com.ukkera.pos.app.gateway.independent.returnabsentexpectationv3

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.StoredClientIntentV1
import com.ukkera.pos.app.gateway.renderRecorded
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.ReadFailure
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.LocalSessionViewResult
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * RAEX3-010/011 bounded shared gateway slices using controlled public case/receipt
 * replies. Stage tests exercise fresh production input preparation, not submit/RPC
 * or Master authority. Whole-case R/Z, genuine sources, native history, actual
 * revocation, controller privacy clearing and physical/language/layout runs are unbound.
 */
class ReturnAbsentExpectationFrozenGatewaySlicesTest {
    private val input = ReturnAbsentGatewayInputs

    @Test
    fun raex3010_notAvailable_offersPendingOnlyAndFreshStageKeepsExactOmittedBody() = runTest {
        val fixture = ReturnAbsentGatewayFixture()
        val page = assertIs<LocalReadResult.Available>(fixture.gateway.read(fixture.query())).page
        assertRetainedFrozenClaim(page)
        val form = pendingForm(page)
        assertEquals(setOf(LocalField.CASE_ID, LocalField.EXPECTED_CASE_REVISION), form.fields.map { it.field }.toSet())
        assertTrue(checkNotNull(form.explanation).english.contains("pending", ignoreCase = true))
        assertTrue(checkNotNull(form.explanation).arabic.contains("معلق"))
        val intent = fixture.intent(form)
        val originalValues = intent.values
        val staged = assertIs<LocalStageResult.Staged>(fixture.gateway.stage(intent))
        assertEquals(input.omittedOperation, staged.operation.operationId)
        assertEquals(LocalPendingPhase.STAGED, staged.operation.phase)
        assertNull(staged.operation.recordedDecision)
        val entry = assertNotNull(fixture.journal.entry)
        assertEquals(input.owner, entry.owner.actorId)
        assertEquals(input.omittedOperation, entry.operationId)
        assertEquals(intent.formVersion, entry.formVersion)
        assertEquals(input.omittedBody, entry.request().payload.copyBytes().decodeToString())
        assertNull(assertIs<LocalReturnCaseRequestV1.Qualify>(LocalReturnCaseCommandCodecV1.decode(entry.request().payload))
            .expectedEntitlementRevision)
        assertEquals(originalValues, intent.values)
        assertReads(fixture, listOf(caseQuery(), receiptQuery(), caseQuery(), receiptQuery()))
        assertEquals(1, fixture.journal.stageCalls)
        fixture.assertNoDispatch() // Client input only; the seam's DURABLE reply is not native durability.
    }

    @Test
    fun raex3010_deniedAndErrors_preserveCaseAndReportFailure_withoutAnAcceptOracleForOptionalForms() = runTest {
        for (variant in listOf("denied", "storage_error", "exception_error")) {
            val fixture = ReturnAbsentGatewayFixture()
            val baseline = assertIs<LocalReadResult.Available>(fixture.gateway.read(fixture.query())).page
            fixture.endpoint.receiptReply = {
                when (variant) {
                    "denied" -> ReadReply.Unavailable(ReadFailure.AUTHORIZATION_DENIED)
                    "storage_error" -> ReadReply.Unavailable(ReadFailure.STORAGE_UNCERTAIN)
                    else -> error("Controlled receipt-read error")
                }
            }
            val page = assertIs<LocalReadResult.Available>(fixture.gateway.read(fixture.query())).page
            assertEquals(baseline.records, page.records, variant)
            assertRetainedFrozenClaim(page)
            val notice = assertNotNull(page.notice)
            assertTrue(notice.english.contains("unavailable", ignoreCase = true), variant)
            assertTrue(notice.arabic.isNotBlank(), variant)
            assertNoAccept(page)
            // G5 intentionally leaves QUALIFY-form availability after Denied/Error unspecified.
            assertReads(fixture, listOf(caseQuery(), receiptQuery(), caseQuery(), receiptQuery()))
            fixture.assertNothingStaged()
        }
    }

    @Test
    fun raex3010_receiptDescriptor_retainsPure7InFormAndFreshCanonicalInput() = runTest {
        val fixture = ReturnAbsentGatewayFixture(input.presentCase)
        fixture.endpoint.receiptReply = { fixture.endpoint.data(input.receipt(input.presentSale)) }
        val page = assertIs<LocalReadResult.Available>(fixture.gateway.read(fixture.query())).page
        assertNoAccept(page)
        val form = page.forms.single { it.action == LocalAction.RETURN_CASE_QUALIFY }
        val entitlement = assertIs<LocalFormField.Scalar>(form.fields.single { it.field == LocalField.EXPECTED_ENTITLEMENT_REVISION })
        assertEquals(LocalScalarValue.Integer(7), entitlement.initialValue)
        assertTrue(entitlement.required && entitlement.readOnly)
        val intent = fixture.intent(form, input.presentOperation)
        assertIs<LocalStageResult.Staged>(fixture.gateway.stage(intent))
        val entry = assertNotNull(fixture.journal.entry)
        assertEquals(input.presentOperation, entry.operationId)
        assertEquals(input.presentCorrectBody, entry.request().payload.copyBytes().decodeToString())
        assertEquals(Revision(7), assertIs<LocalReturnCaseRequestV1.Qualify>(
            LocalReturnCaseCommandCodecV1.decode(entry.request().payload)).expectedEntitlementRevision)
        assertReads(fixture, listOf(caseQuery(input.presentCase), receiptQuery(input.presentSale),
            caseQuery(input.presentCase), receiptQuery(input.presentSale)))
        fixture.assertNoDispatch() // Public revision descriptor, not genuine-original or PS/Z evidence.
    }

    @Test
    fun raex3010_receiptCancellation_propagatesWithoutNotAvailableOrHiddenStage() = runTest {
        val fixture = ReturnAbsentGatewayFixture()
        val cancellation = CancellationException("Controlled receipt-read cancellation")
        fixture.endpoint.receiptReply = { throw cancellation }
        assertSame(cancellation, assertFailsWith<CancellationException> { fixture.gateway.read(fixture.query()) })
        assertReads(fixture, listOf(caseQuery(), receiptQuery()))
        fixture.assertNothingStaged()
    }

    @Test
    fun raex3010_failedCaseRead_neverUsesLateReceiptOrPreviouslyOfferedPrivateForm() = runTest {
        for (variant in listOf("denied", "missing", "error")) {
            val fixture = ReturnAbsentGatewayFixture()
            val oldPage = assertIs<LocalReadResult.Available>(fixture.gateway.read(fixture.query())).page
            val oldIntent = fixture.intent(pendingForm(oldPage))
            fixture.endpoint.caseReply = {
                when (variant) {
                    "denied" -> ReadReply.Unavailable(ReadFailure.AUTHORIZATION_DENIED)
                    "missing" -> fixture.endpoint.data(LocalReturnCaseReadResultV1.NotAvailable)
                    else -> ReadReply.Unavailable(ReadFailure.STORAGE_UNCERTAIN)
                }
            }
            val result = fixture.gateway.read(fixture.query())
            when (variant) {
                "missing" -> {
                    val page = assertIs<LocalReadResult.Available>(result).page
                    assertTrue(page.forms.isEmpty())
                    assertTrue(page.records.none { it.reference.kind == LocalRecordKind.RETURN_CASE })
                }
                "denied" -> assertEquals(LocalServiceProblem.DENIED, assertIs<LocalReadResult.Unavailable>(result).problem)
                else -> assertEquals(LocalServiceProblem.STORAGE_UNCERTAIN, assertIs<LocalReadResult.Unavailable>(result).problem)
            }
            val stopped = assertIs<LocalStageResult.NotStaged>(fixture.gateway.stage(oldIntent))
            assertEquals(when (variant) {
                "denied" -> LocalServiceProblem.DENIED
                "missing" -> LocalServiceProblem.INVALID_INPUT
                else -> LocalServiceProblem.STORAGE_UNCERTAIN
            }, stopped.problem)
            if (variant == "missing") assertEquals(LocalField.CASE_ID, stopped.field)
            assertReads(fixture, listOf(caseQuery(), receiptQuery(), caseQuery(), caseQuery()))
            fixture.assertNothingStaged()
        }
    }

    @Test
    fun raex3010_sessionEndedDuringReceipt_retiresLatePageAndCannotStageOldForm() = runTest {
        val fixture = ReturnAbsentGatewayFixture()
        val oldPage = assertIs<LocalReadResult.Available>(fixture.gateway.read(fixture.query())).page
        val oldIntent = fixture.intent(pendingForm(oldPage))
        fixture.endpoint.receiptReply = {
            fixture.lifetimeCurrent = false
            fixture.endpoint.data(LocalReturnCaseReadResultV1.NotAvailable)
        }
        assertEquals(LocalServiceProblem.SESSION_ENDED,
            assertIs<LocalReadResult.Unavailable>(fixture.gateway.read(fixture.query())).problem)
        assertIs<LocalStageResult.Uncertain>(fixture.gateway.stage(oldIntent))
        assertReads(fixture, listOf(caseQuery(), receiptQuery(), caseQuery(), receiptQuery()))
        fixture.assertNothingStaged()
    }

    @Test
    fun raex3011_originalDescriptorAppears_freshPrecheckStopsWithoutFillingOmittedRevision() = runTest {
        val fixture = ReturnAbsentGatewayFixture()
        val oldPage = assertIs<LocalReadResult.Available>(fixture.gateway.read(fixture.query())).page
        val intent = fixture.intent(pendingForm(oldPage))
        val originalValues = intent.values
        fixture.endpoint.receiptReply = { fixture.endpoint.data(input.receipt(input.missingSale)) }
        val stopped = assertIs<LocalStageResult.NotStaged>(fixture.gateway.stage(intent))
        assertEquals(LocalServiceProblem.INVALID_INPUT, stopped.problem)
        assertEquals(LocalField.EXPECTED_ENTITLEMENT_REVISION, stopped.field)
        assertEquals(input.omittedOperation, intent.operationId)
        assertEquals(originalValues, intent.values)
        assertTrue(intent.values.none { it.field == LocalField.EXPECTED_ENTITLEMENT_REVISION })
        assertReads(fixture, listOf(caseQuery(), receiptQuery(), caseQuery(), receiptQuery()))
        fixture.assertNothingStaged() // The exact Master REVISION_CONFLICT and R/Z remain unbound.
    }

    @Test
    fun raex3011_changedPublicCaseRevision_freshPrecheckStopsOriginalC0BeforeReceipt() = runTest {
        val fixture = ReturnAbsentGatewayFixture()
        val oldPage = assertIs<LocalReadResult.Available>(fixture.gateway.read(fixture.query())).page
        val intent = fixture.intent(pendingForm(oldPage))
        val originalValues = intent.values
        fixture.endpoint.caseValue = input.caseView(input.missingCase, Revision(12))
        val stopped = assertIs<LocalStageResult.NotStaged>(fixture.gateway.stage(intent))
        assertEquals(LocalServiceProblem.INVALID_INPUT, stopped.problem)
        assertEquals(LocalField.EXPECTED_CASE_REVISION, stopped.field)
        assertEquals(originalValues, intent.values)
        assertReads(fixture, listOf(caseQuery(), receiptQuery(), caseQuery()))
        fixture.assertNothingStaged() // 12 is a changed public DTO, not an asserted storage increment.
    }

    @Test
    fun raex3011_freshPermissionHintAndCurrentSessionDenial_cannotStageOldForm() = runTest {
        for (variant in listOf("permission_hint_denied", "current_session_denied")) {
            val fixture = ReturnAbsentGatewayFixture()
            val oldPage = assertIs<LocalReadResult.Available>(fixture.gateway.read(fixture.query())).page
            val intent = fixture.intent(pendingForm(oldPage))
            if (variant == "permission_hint_denied") fixture.endpoint.view = input.view(denied = true)
            else fixture.endpoint.currentReply = LocalSessionViewResult.Denied
            val result = fixture.gateway.stage(intent)
            if (variant == "permission_hint_denied") {
                assertEquals(LocalServiceProblem.DENIED, assertIs<LocalStageResult.NotStaged>(result).problem)
                assertReads(fixture, listOf(caseQuery(), receiptQuery(), caseQuery()))
            } else {
                assertIs<LocalStageResult.Uncertain>(result)
                assertReads(fixture, listOf(caseQuery(), receiptQuery()))
            }
            assertEquals(input.omittedOperation, intent.operationId)
            fixture.assertNothingStaged() // No actual activated-policy/session revocation is manufactured.
        }
    }

    @Test
    fun raex3011_freshReceiptCancellation_propagatesAndDoesNotStageOriginalInput() = runTest {
        val fixture = ReturnAbsentGatewayFixture()
        val oldPage = assertIs<LocalReadResult.Available>(fixture.gateway.read(fixture.query())).page
        val intent = fixture.intent(pendingForm(oldPage))
        val cancellation = CancellationException("Controlled fresh receipt-read cancellation")
        fixture.endpoint.receiptReply = { throw cancellation }
        assertSame(cancellation, assertFailsWith<CancellationException> { fixture.gateway.stage(intent) })
        assertReads(fixture, listOf(caseQuery(), receiptQuery(), caseQuery(), receiptQuery()))
        fixture.assertNothingStaged()
    }

    /** Controlled renderer input only: construction is not a protected Recorded publication. */
    @Test
    fun raex3010_recordedNullExpectation_pendingShapeRendersOnlyPendingCaseProjection() = runTest {
        val entry = stagedRendererEntry()
        val rendered = renderRecorded(entry, rendererReply(pendingRendererResult()))
        assertEquals(input.omittedOperation, rendered.operationId)
        val record = assertNotNull(rendered.result)
        assertEquals(LocalRecordKind.RETURN_CASE, record.reference.kind)
        assertEquals(input.missingCase, record.reference.id)
        assertTrue(assertNotNull(record.status).english.contains("pending", ignoreCase = true))
        assertEquals(1, record.values.size)
        assertEquals("11", record.values.single().value.english)
        // The only value is the supplied renderer case revision, not entitlement,
        // claim, return or original money. It is not an asserted committed C1.
    }

    /** RAEX3-010/012 pending-only result semantics; no accepting source or Master effect proof. */
    @Test
    fun raex3010And012_recordedNullExpectation_boundAcceptedAndFinancialShapesDoNotRender() = runTest {
        val entry = stagedRendererEntry()
        val pending = pendingRendererResult()
        val variants = listOf(
            "bound_action" to pending.copy(action = LocalReturnCaseActionV1.BOUND),
            "accepted_action" to pending.copy(action = LocalReturnCaseActionV1.ACCEPTED),
            "bound_stage" to pending.copy(stage = LocalReturnCaseStageV1.BOUND_UNACCEPTED),
            "accepted_stage" to pending.copy(stage = LocalReturnCaseStageV1.ACCEPTED_DISPOSITION_PENDING),
            "entitlement_7" to pending.copy(entitlementRevision = Revision(7)),
            "entitlement_zero" to pending.copy(entitlementRevision = Revision(0)),
            "claim_5" to pending.copy(claimRevision = Revision(5)),
            "return_id" to pending.copy(returnId = input.coordinate("40")),
            "original_money" to pending.copy(originalPayable = Money(Currency.EGP, 22_800)),
        )
        for ((name, result) in variants) {
            val rendered = renderRecorded(entry, rendererReply(result))
            assertEquals(input.omittedOperation, rendered.operationId, name)
            assertNull(rendered.result, name)
        }
    }

    private suspend fun stagedRendererEntry(): StoredClientIntentV1 {
        val fixture = ReturnAbsentGatewayFixture()
        val page = assertIs<LocalReadResult.Available>(fixture.gateway.read(fixture.query())).page
        assertIs<LocalStageResult.Staged>(fixture.gateway.stage(fixture.intent(pendingForm(page))))
        fixture.assertNoDispatch()
        return assertNotNull(fixture.journal.entry)
    }

    private fun pendingRendererResult(): LocalReturnCaseResultV1.Applied = LocalReturnCaseResultV1.Applied(
        LocalReturnCaseActionV1.ASSESSMENT_PENDING, input.missingCase, Revision(11), LocalReturnCaseStageV1.PENDING,
        input.omittedOperation)

    /** Epoch/sequence/time and committed=true are envelope metadata, never native authority evidence. */
    private fun rendererReply(result: LocalReturnCaseResultV1.Applied): MutationReply.Recorded = MutationReply.Recorded(
        input.omittedOperation, input.owner, true, "committed", StoredBytes(input.json.encodeToString(
            LocalReturnCaseResultV1.serializer(), result).encodeToByteArray()), Revision(1), JournalSequence(1), input.claimedAt, false)

    private fun pendingForm(page: LocalWorkflowPage): LocalActionForm {
        assertNoAccept(page)
        val form = page.forms.single { it.action == LocalAction.RETURN_CASE_QUALIFY }
        assertTrue(form.fields.none { it.field == LocalField.EXPECTED_ENTITLEMENT_REVISION })
        assertEquals(LocalScalarValue.Identifier(input.missingCase),
            assertIs<LocalFormField.Scalar>(form.fields.single { it.field == LocalField.CASE_ID }).initialValue)
        assertEquals(LocalScalarValue.Integer(11),
            assertIs<LocalFormField.Scalar>(form.fields.single { it.field == LocalField.EXPECTED_CASE_REVISION }).initialValue)
        return form
    }

    private fun assertNoAccept(page: LocalWorkflowPage) {
        assertTrue(page.forms.none { it.action == LocalAction.RETURN_CASE_ACCEPT })
        assertTrue(page.records.none { it.reference.kind == LocalRecordKind.RETURN_CASE_EVENT })
    }

    private fun assertRetainedFrozenClaim(page: LocalWorkflowPage) {
        val record = page.records.single { it.reference.kind == LocalRecordKind.RETURN_CASE && it.reference.id == input.missingCase }
        val values = record.values.map { it.value }
        for (literal in listOf(input.originalOperation.value, "change of mind", "unchanged, inspection pending", "Africa/Cairo",
            "2026-10-31T09:00:00Z", "2026-10-18T09:00:00Z", input.evidence.value, input.missingSale.value, input.portion.value)) {
            assertTrue(values.any { it.english == literal && it.arabic == literal }, literal)
        }
        assertTrue(values.any { it.english.contains("refund", ignoreCase = true) && it.arabic.isNotBlank() })
    }

    private fun caseQuery(id: com.ukkera.pos.types.DomainId = input.missingCase) = LocalReturnCaseQueryV1.Case(id)
    private fun receiptQuery(id: com.ukkera.pos.types.DomainId = input.missingSale) = LocalReturnCaseQueryV1.Receipt(id, limit = 1)

    private fun assertReads(fixture: ReturnAbsentGatewayFixture, queries: List<LocalReturnCaseQueryV1>) {
        assertEquals(queries.size, fixture.endpoint.reads.size)
        for ((query, actual) in queries.zip(fixture.endpoint.reads)) {
            val expected = LocalReturnCaseReadCodecV1.request(query)
            assertEquals(expected.query, actual.query)
            assertContentEquals(expected.payload.copyBytes(), actual.payload.copyBytes())
        }
    }
}
