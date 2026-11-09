package com.ukkera.pos.app.gateway.independent.pay10classificationcorrection

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.database.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * commerce-v1 COM-PAY-10-I/E gateway SUPPORT only. Real form, retained-original,
 * typed-response and presentation functions run over controlled DTO projections.
 * No authenticated original CARD history, native grant, business commit, physical
 * drawer, admitted RPC, durable journal, restart or installed UI is supplied.
 * A typed terminal carrier below is not evidence of a committed financial result.
 */
class Pay10ClassificationCorrectionGatewaySupportingTest {
    private val input get() = Pay10GatewayInputs

    @Test
    fun supportPreparationRetainsOriginalOperationAndExactHistoricalCorrectionWithoutDispatch() = runTest {
        withPay10Gateway { fixture ->
            val request = preparePaymentCorrection(fixture.access, input.intent())
            assertEquals(OperationId("a40f4978-c4fa-5844-a9a6-5b33b006e22b"), request.operationId)
            assertEquals(LocalPaymentCorrectionCodec.COMMAND, request.command)
            assertEquals(input.request, LocalPaymentCorrectionCodec.decode(request.payload))
            assertEquals(Money(Currency.EGP, 10000), LocalPaymentCorrectionCodec.decode(request.payload).expectedAmount)
            assertNull(request.actionApprovalId)
            assertTrue(fixture.endpoint.reads.isEmpty())
            assertEquals(0, fixture.endpoint.executeCalls)
        }
    }

    @Test
    fun supportCashCorrectionRejectsExternalMetadataMissingEvidenceAndWrongEnvelope() = runTest {
        withPay10Gateway { fixture ->
            val invalid = listOf(
                input.intent(extra = listOf(LocalScalarInput(LocalField.TENDER_ID, LocalScalarValue.Identifier(input.card.tenderId!!)))),
                input.intent(extra = listOf(LocalScalarInput(LocalField.EXPECTED_REVISION, LocalScalarValue.Integer(1)))),
                input.intent(extra = listOf(LocalScalarInput(LocalField.PAYMENT_TERMINAL, LocalScalarValue.Text("old-terminal")))),
                input.intent(extra = listOf(LocalScalarInput(LocalField.EXTERNAL_REFERENCE, LocalScalarValue.Text("old-reference")))),
                input.intent(omit = setOf(LocalField.EVIDENCE_ID)),
                input.intent(version = 2), input.intent(action = LocalAction.COLLECTION_PREPARE),
                input.intent(lineField = LocalField.TENDER_ID),
            )
            for (intent in invalid) expect(LocalServiceProblem.INVALID_INPUT) { preparePaymentCorrection(fixture.access, intent) }
            assertEquals(0, fixture.endpoint.executeCalls)
            assertTrue(fixture.endpoint.reads.isEmpty())
        }
    }

    @Test
    fun supportFreshPreparationUsesCurrentExplicitSaleHintAndDenyWins() = runTest {
        val invalid = listOf(emptyList(), listOf(input.grant(target = input.auxiliary(9))),
            listOf(input.grant(), input.grant(effect = GrantEffect.DENY)))
        for (grants in invalid) withPay10Gateway(grants) { fixture ->
            expect(LocalServiceProblem.DENIED) { preparePaymentCorrection(fixture.access, input.intent()) }
            assertEquals(0, fixture.endpoint.executeCalls)
            assertTrue(fixture.endpoint.reads.isEmpty())
        }
        assertFailsWith<IllegalArgumentException> {
            input.sessionView(listOf(input.grant(grantScope = input.scope.copy(branchId = input.auxiliary(10)))))
        }
        // A foreign effective grant cannot form current policy DATA; this is not
        // an authenticated foreign-branch command or a Master denial observation.
        // The actual Master's current employee/session/grant boundary is not exercised.
    }

    @Test
    fun supportSavedOriginalKeepsActorOperationAndExact10000Versus10001SemanticBytes() {
        val original = input.staged()
        assertEquals(input.request, savedPaymentCorrectionRequest(original))
        assertEquals(input.owner, original.owner)
        assertEquals(input.operation, original.operationId)
        val changedValue = input.request.copy(expectedAmount = Money(Currency.EGP, 10001))
        val changed = input.staged(changedValue, original = input.intent("100.01"))
        assertEquals(input.operation, changed.operationId)
        assertNotEquals(original.semanticSha256, changed.semanticSha256)
        assertNotEquals(original.request().payload, changed.request().payload)
        assertFalse(original.sameIntent(changed))
        assertEquals(Money(Currency.EGP, 10000), savedPaymentCorrectionRequest(original).expectedAmount)
        assertEquals(Money(Currency.EGP, 10001), savedPaymentCorrectionRequest(changed).expectedAmount)
        // Neither carrier establishes the kernel's durable same-ID conflict outcome.
    }

    @Test
    fun supportMatchingTypedOutcomeRendersDocumentaryAdjustmentAndPreservesReplayIdentity() {
        val original = input.staged()
        for (replayed in listOf(false, true)) {
            val reply = input.recorded(replayed = replayed)
            val typed = assertIs<LocalPaymentCorrectionResultV1.Recorded>(validatePaymentCorrectionRecorded(original, reply))
            assertEquals(Money(Currency.EGP, -10000), typed.correction.previousClassificationDelta)
            assertEquals(Money(Currency.EGP, 10000), typed.correction.correctedClassificationDelta)
            assertEquals(Money(Currency.EGP, 0), typed.correction.physicalCashDelta)
            assertEquals(input.collector, typed.correction.collectorId)
            assertEquals(input.manager, typed.correction.evidence.actorId)
            val rendered = paymentCorrectionRecorded(original, reply)
            assertEquals(input.operation, rendered.operationId)
            assertEquals(LocalRecordedDecision.APPLIED, rendered.decision)
            assertEquals(replayed, rendered.replayed)
            assertEquals(LocalRecordReference(LocalRecordKind.PAYMENT_CORRECTION_HISTORY,
                input.payment, parentId = input.sale), assertNotNull(rendered.result).reference)
            assertEquals("0.00 EGP", value(assertNotNull(rendered.result), "Physical cash delta"))
        }
    }

    @Test
    fun supportTypedAmount10001CannotReplaceTheSaved10000Outcome() {
        val original = input.staged()
        val changed = input.correction.copy(amount = Money(Currency.EGP, 10001),
            previousClassificationDelta = Money(Currency.EGP, -10001), correctedClassificationDelta = Money(Currency.EGP, 10001))
        assertUncertain { validatePaymentCorrectionRecorded(original, input.recorded(changed)) }
        assertEquals(Money(Currency.EGP, 10000), savedPaymentCorrectionRequest(original).expectedAmount)
        assertNull(original.recorded)
    }

    @Test
    fun supportWrongActorOrOperationCannotAdoptTheOriginalCorrection() {
        val original = input.staged()
        val otherOperation = OperationId(input.auxiliary(11).value)
        val altered = listOf(input.recorded(actor = input.otherActor), input.recorded(operationId = otherOperation),
            input.recorded(input.correction.copy(evidence = input.correction.evidence.copy(actorId = input.otherActor))),
            input.recorded(input.correction.copy(operationId = otherOperation)))
        for (reply in altered) assertUncertain { validatePaymentCorrectionRecorded(original, reply) }
        assertEquals(input.owner, original.owner)
        assertEquals(input.operation, original.operationId)
        assertEquals(input.request, savedPaymentCorrectionRequest(original))
    }

    @Test
    fun supportContradictoryCommitFlagsAndRefusalCodesCannotBecomeSuccess() {
        val original = input.staged()
        for (reply in listOf(input.recorded(committed = false), input.recorded(code = "sales.payment_correction.refused"),
            input.refused(committed = true), input.refused(code = "committed")))
            assertUncertain { validatePaymentCorrectionRecorded(original, reply) }
        val refused = paymentCorrectionRecorded(original, input.refused())
        assertEquals(LocalRecordedDecision.REJECTED, refused.decision)
        assertEquals(input.operation, refused.operationId)
        assertNull(refused.result)
        assertNull(original.recorded)
    }

    @Test
    fun supportStoredForgedTypedOutcomeCannotBeUsedAsAnAcknowledgedOriginal() {
        val original = input.staged()
        val changed = input.correction.copy(reason = "a different correction reason")
        val forged = original.copy(phase = LocalPendingPhase.RECORDED_UNACKNOWLEDGED.name,
            recorded = StoredKernelResultV1.from(input.recorded(changed)))
        assertUncertain { savedPaymentCorrectionRequest(forged) }
        val matched = original.copy(phase = LocalPendingPhase.RECORDED_UNACKNOWLEDGED.name,
            recorded = StoredKernelResultV1.from(input.recorded()))
        assertEquals(input.request, savedPaymentCorrectionRequest(matched))
        assertUncertain { savedPaymentCorrectionRequest(original.copy(action = LocalAction.COLLECTION_PREPARE.name)) }
        assertUncertain { savedPaymentCorrectionRequest(original.copy(formVersion = 2)) }
    }

    @Test
    fun supportAuthoritativeTimestampAndCashMetadataMismatchesRemainUncertain() {
        val original = input.staged()
        for (correction in listOf(input.correction.copy(recordedAt = input.yesterday),
            input.correction.copy(terminal = "unrelated-card-terminal"),
            input.correction.copy(reference = "unrelated-card-reference")))
            assertUncertain { validatePaymentCorrectionRecorded(original, input.recorded(correction)) }
        val retained = original.copy(phase = LocalPendingPhase.RECORDED_UNACKNOWLEDGED.name,
            recorded = StoredKernelResultV1.from(input.recorded()).copy(recordedAt = input.yesterday))
        assertUncertain { savedPaymentCorrectionRequest(retained) }
        assertEquals(input.request, savedPaymentCorrectionRequest(original))
    }

    @Test
    fun supportGrantRemovalOrAnotherActorCannotRewriteOrContinueTheSavedOriginal() = runTest {
        val original = input.staged()
        withPay10Gateway { fixture -> assertTrue(fixture.access.canContinue(original)) }
        withPay10Gateway(grants = emptyList()) { fixture ->
            assertFalse(fixture.access.canContinue(original))
            assertEquals(0, fixture.endpoint.lookupCalls)
            assertEquals(0, fixture.endpoint.executeCalls)
        }
        withPay10Gateway(actor = input.otherActor) { fixture ->
            expect(LocalServiceProblem.STORAGE_UNCERTAIN) { fixture.access.canContinue(original) }
            assertEquals(0, fixture.endpoint.lookupCalls)
        }
        assertEquals(input.owner, original.owner)
        assertEquals(input.request, savedPaymentCorrectionRequest(original))
        assertEquals(input.operation, original.operationId)
    }

    @Test
    fun supportSourceProjectionDisplaysOriginalCloseAndCardAttestationSeparately() = runTest {
        withPay10Gateway { fixture ->
            fixture.endpoint.projection = { input.readReply(LocalPaymentCorrectionReadResultV1.Source(input.source)) }
            val page = paymentCorrectionWorkflow(fixture.access, LocalWorkflowQuery(LocalWorkflow.ORDERS,
                reference = LocalRecordReference(LocalRecordKind.PAYMENT_CORRECTION_SOURCE, input.sale)))
            assertEquals(2, page.records.size)
            val close = page.records.single { it.reference.kind == LocalRecordKind.CASH_SESSION }
            assertEquals("1000.00 EGP", value(close, "Original expected cash"))
            assertEquals("1100.00 EGP", value(close, "Original actual count"))
            assertEquals("100.00 EGP", value(close, "Original variance"))
            val payment = page.records.single { it.reference.kind == LocalRecordKind.PAYMENT_CORRECTION_HISTORY }
            assertTrue(value(payment, "Original reported tender").startsWith("CARD"))
            assertEquals(input.collector.value, value(payment, "Original attesting employee"))
            assertTrue(page.forms.isEmpty())
            assertEquals(1, fixture.endpoint.reads.size)
            assertEquals(0, fixture.endpoint.executeCalls)
        }
    }

    @Test
    fun supportHistoryProjectionSeparatesAdjustmentAndRetainsFrozenReadOnlyAmount() = runTest {
        withPay10Gateway { fixture ->
            fixture.endpoint.projection = { input.readReply(LocalPaymentCorrectionReadResultV1.History(input.page)) }
            val page = paymentCorrectionWorkflow(fixture.access, input.historyQuery())
            assertEquals(3, page.records.size)
            assertEquals("100.00 EGP", value(page.records.single { it.reference.kind == LocalRecordKind.CASH_SESSION }, "Original variance"))
            val adjustment = page.records.single { it.reference.kind == LocalRecordKind.PAYMENT_CORRECTION }
            assertEquals("-100.00 EGP", value(adjustment, "Historical classification delta"))
            assertEquals("100.00 EGP", value(adjustment, "Linked classification delta"))
            assertEquals("0.00 EGP", value(adjustment, "Physical cash delta"))
            assertEquals(input.manager.value, value(adjustment, "Author"))
            val form = page.forms.single()
            assertEquals(LocalAction.PAYMENT_CLASSIFICATION_CORRECT, form.action)
            val amount = assertIs<LocalFormField.Scalar>(form.fields.single { it.field == LocalField.AMOUNT })
            assertTrue(amount.readOnly)
            assertEquals(LocalScalarValue.Decimal("100.00"), amount.initialValue)
            assertNull(page.nextCursor)
            assertEquals(0, fixture.endpoint.executeCalls)
        }
    }

    @Test
    fun supportUnavailableHistoryIsExplicitAndCannotEnableANewCorrection() = runTest {
        withPay10Gateway { fixture ->
            fixture.endpoint.projection = { input.readReply(LocalPaymentCorrectionReadResultV1.NotAvailable) }
            val page = paymentCorrectionWorkflow(fixture.access, input.historyQuery())
            assertTrue(page.records.isEmpty())
            assertTrue(page.forms.isEmpty())
            assertTrue(assertNotNull(page.notice).english.contains("unavailable"))
            assertTrue(assertNotNull(page.notice).arabic.isNotBlank())
            assertEquals(0, fixture.endpoint.executeCalls)
        }
    }

    @Test
    fun supportForeignCursorOrContradictoryBoundedHistoryCannotProduceACorrectionForm() = runTest {
        withPay10Gateway { fixture ->
            expect(LocalServiceProblem.INVALID_INPUT) {
                paymentCorrectionWorkflow(fixture.access,
                    input.historyQuery("payment-corrections:${input.auxiliary(12).value}:${input.payment.value}:${input.correction.id.value}"))
            }
            assertTrue(fixture.endpoint.reads.isEmpty())
        }
        val invalid = listOf(
            input.page.copy(corrections = List(6) { input.correction }),
            input.page.copy(payment = input.page.payment.copy(amount = Money(Currency.EGP, 10001))),
            input.page.copy(payment = input.page.payment.copy(latestClassification = input.card)),
            input.page.copy(corrections = listOf(input.correction.copy(collectorId = input.otherActor))),
            input.page.copy(corrections = listOf(input.correction.copy(saleId = input.auxiliary(12)))),
            input.page.copy(corrections = listOf(input.correction.copy(originalCloseOperationId = OperationId(input.auxiliary(12).value)))),
        )
        for (carrier in invalid) withPay10Gateway { fixture ->
            fixture.endpoint.projection = { input.readReply(LocalPaymentCorrectionReadResultV1.History(carrier)) }
            expect(LocalServiceProblem.UNAVAILABLE) { paymentCorrectionWorkflow(fixture.access, input.historyQuery()) }
            assertEquals(0, fixture.endpoint.executeCalls)
        }
    }

    @Test
    fun supportContradictoryOriginalExternalEvidenceAndCorruptCashMetadataCannotBecomeHistory() = runTest {
        fun originalTender(tender: LocalTenderViewV1, terminal: String? = null, reference: String? = null) = input.page.copy(
            payment = input.page.payment.copy(originalTender = tender, originalTerminal = terminal, originalReference = reference),
            corrections = listOf(input.correction.copy(originalTender = tender, previousClassification = tender)))
        val invalid = listOf(
            originalTender(input.card.copy(requiresTerminal = true)),
            originalTender(input.card.copy(requiresReference = true), reference = " "),
            originalTender(input.card, terminal = "T".repeat(129)),
            originalTender(input.card.copy(tenderId = null)),
            originalTender(input.card.copy(kind = "UNKNOWN_TENDER")),
            input.page.copy(corrections = listOf(input.correction.copy(correctedTender = LocalTenderViewV1("CASH", input.card.tenderId)))),
            input.page.copy(corrections = listOf(input.correction.copy(terminal = "unrelated-card-terminal"))),
        )
        for (carrier in invalid) withPay10Gateway { fixture ->
            fixture.endpoint.projection = { input.readReply(LocalPaymentCorrectionReadResultV1.History(carrier)) }
            expect(LocalServiceProblem.UNAVAILABLE) { paymentCorrectionWorkflow(fixture.access, input.historyQuery()) }
            assertEquals(0, fixture.endpoint.executeCalls)
        }
    }

    @Test
    fun supportMissingWriteInstallationOrCurrentHintSuppressesTheFreshCorrectionForm() = runTest {
        withPay10Gateway(writeInstalled = false) { fixture ->
            fixture.endpoint.projection = { input.readReply(LocalPaymentCorrectionReadResultV1.History(input.page)) }
            assertTrue(paymentCorrectionWorkflow(fixture.access, input.historyQuery()).forms.isEmpty())
            assertEquals(0, fixture.endpoint.executeCalls)
        }
        withPay10Gateway(grants = emptyList()) { fixture ->
            fixture.endpoint.projection = { input.readReply(LocalPaymentCorrectionReadResultV1.History(input.page)) }
            assertTrue(paymentCorrectionWorkflow(fixture.access, input.historyQuery()).forms.isEmpty())
            assertEquals(0, fixture.endpoint.executeCalls)
        }
        withPay10Gateway(readInstalled = false) { fixture ->
            assertNull(paymentCorrectionLink(fixture.access, input.sale))
            expect(LocalServiceProblem.NOT_IMPLEMENTED) { paymentCorrectionWorkflow(fixture.access, input.historyQuery()) }
            assertTrue(fixture.endpoint.reads.isEmpty())
        }
    }

    private fun value(record: LocalRecord, label: String) = record.values.single { it.label.english == label }.value.english
    private fun assertUncertain(block: () -> Unit) {
        assertEquals(LocalServiceProblem.STORAGE_UNCERTAIN, assertFailsWith<GatewayFailure>(block = block).problem)
    }
    private suspend fun expect(problem: LocalServiceProblem, block: suspend () -> Unit) {
        try { block(); fail("Expected a typed gateway refusal") }
        catch (failure: GatewayFailure) { assertEquals(problem, failure.problem) }
    }
}
