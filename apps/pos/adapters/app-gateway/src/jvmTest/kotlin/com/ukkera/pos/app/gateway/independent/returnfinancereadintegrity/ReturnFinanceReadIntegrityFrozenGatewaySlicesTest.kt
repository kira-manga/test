package com.ukkera.pos.app.gateway.independent.returnfinancereadintegrity

import com.ukkera.pos.app.*
import com.ukkera.pos.sales.store.*
import java.math.BigDecimal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Financial-field and refusal slices of frozen RFRI v1. No method qualifies a
 * whole frozen case, the two-portion header, application/observation history,
 * protected authority, native persistence/RPC, or installed presentation. */
class ReturnFinanceReadIntegrityFrozenGatewaySlicesTest {
    @Test fun s001_unpaidFinancialFieldsSlice() = runTest { assertFinanceSlice("UNPAID") }
    @Test fun s002_partialFinancialFieldsSlice() = runTest { assertFinanceSlice("PARTIAL") }
    @Test fun s002_settledFinancialFieldsSlice() = runTest { assertFinanceSlice("SETTLED") }
    @Test fun s002_lowCashDoesNotChangeObligationFieldsSlice() = runTest { assertFinanceSlice("UNPAID_LOW_CASH") }
    @Test fun s003_failedAttemptFieldsSlice() = runTest { assertFinanceSlice("FAILED") }
    @Test fun s003_unknownAttemptFieldsSlice() = runTest { assertFinanceSlice("UNKNOWN") }

    @Test fun s005_m02MixedCurrencyRefusalSlice() = runTest { assertMalformedSlice("M02") }
    @Test fun s005_m03OriginalTenderSumRefusalSlice() = runTest { assertMalformedSlice("M03") }
    @Test fun s005_m05NegativeLiabilityRefusalSlice() = runTest { assertMalformedSlice("M05") }
    @Test fun s005_m06OverflowMinorUnitsRefusalSlice() = runTest { assertMalformedSlice("M06") }
    @Test fun s005_m07FractionalMinorUnitsRefusalSlice() = runTest { assertMalformedSlice("M07") }
    @Test fun s005_m08OverallocatedPayoutRefusalSlice() = runTest { assertMalformedSlice("M08") }

    @Test fun s006_a02ControlledReadDenialWithholdsFinanceSlice() = runTest {
        val fixture = ReturnFinanceReadGatewayFixture("UNKNOWN")
        fixture.endpoint.denyFinance = true
        val result = assertIs<LocalReadResult.Unavailable>(fixture.gateway.read(fixture.query))
        assertEquals(LocalServiceProblem.DENIED, result.problem)
        assertBoundReadsAndNoWrites(fixture)
        // O_DENIED's no-private-page meaning only; this DATA refusal does not
        // establish actual grant enforcement or field filtering before serialization.
    }

    @Test fun s006_a06DelayedReadAfterLifetimeLossWithholdsFinanceSlice() = runTest {
        val fixture = ReturnFinanceReadGatewayFixture("UNKNOWN")
        val requested = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.endpoint.beforeFinanceReply = { requested.complete(Unit); release.await() }
        val pending = async { fixture.gateway.read(fixture.query) }
        requested.await()
        fixture.lifetimeCurrent = false
        release.complete(Unit)
        val result = assertIs<LocalReadResult.Unavailable>(pending.await())
        assertEquals(LocalServiceProblem.SESSION_ENDED, result.problem)
        assertBoundReadsAndNoWrites(fixture)
        // Frozen A06 no-late-publication slice only. Activated revocation,
        // original recovery retention and UI/cache clearing remain unbound.
    }

    private suspend fun assertFinanceSlice(snapshot: String) {
        val fixture = ReturnFinanceReadGatewayFixture(snapshot)
        val expected = fixture.input.expected(snapshot)
        val page = assertIs<LocalReadResult.Available>(fixture.gateway.read(fixture.query)).page
        assertEquals(LocalWorkflow.RETURNS, page.workflow)
        val finance = page.records.single { it.reference.kind == LocalRecordKind.RETURN_FINANCE }
        assertEquals(fixture.query.reference, finance.reference)
        assertLiteral(finance.value("Original accepted payable"), display(expected, "accepted_obligation_minor"))
        assertLiteral(finance.value("Actually recorded refunded"), display(expected, "recorded_actual_payout_minor"))
        assertLiteral(finance.value("Refund still due"), display(expected, "ledger_liability_minor"))
        assertLiteral(finance.value("Exact quantity"), "${fixture.input.frozen.obj("common_expected_authorized_projection").string("accepted_quantity")} · ${fixture.input.unitId.value}")
        val expectedStatus = when (expected.string("settlement_badge")) {
            "REFUND_DUE" -> "DUE"
            "PARTLY_APPLIED_REFUND_DUE" -> "PART_REFUNDED"
            "REFUND_DUE_WITH_UNRESOLVED_ATTEMPT" -> "OUTCOME_UNKNOWN"
            "SETTLED_BY_SUPPORTED_RECORDED_APPLICATIONS" -> "SETTLED"
            else -> error("Unbound frozen settlement meaning")
        }
        assertLiteral(checkNotNull(finance.status), expectedStatus)
        val obligations = page.records.filter { it.reference.kind == LocalRecordKind.RETURN_OBLIGATION }.associateBy { it.reference.id }
        assertEquals(setOf(fixture.input.cashPaymentId, fixture.input.externalPaymentId), obligations.keys)
        assertLiteral(obligations.getValue(fixture.input.cashPaymentId).value("Amount, EGP"), display(expected.obj("cash"), "obligation_minor"))
        assertLiteral(obligations.getValue(fixture.input.externalPaymentId).value("Amount, EGP"), display(expected.obj("external"), "obligation_minor"))
        val attempts = page.records.filter { it.reference.kind == LocalRecordKind.PAYMENT }.associateBy { it.reference.id }
        val facts = fixture.root.obj("facts")
        val included = fixture.root.obj("snapshot").getValue("included_facts").jsonArray.map { facts.obj(it.jsonPrimitive.content) }
        assertEquals(included.map { fixture.input.id(it.string("attempt_id")) }.toSet(), attempts.keys)
        for (fact in included) {
            val row = attempts.getValue(fixture.input.id(fact.string("attempt_id")))
            assertLiteral(checkNotNull(row.status), fixture.input.attemptStatus(fact))
            assertLiteral(row.value("Amount, EGP"), display(fact, "amount_minor"))
            assertLiteral(row.value("Original intent operation"), fixture.input.operation(fact.string("original_operation_id")).value)
        }
        assertTrue(page.forms.isEmpty(), "Frozen read creates no new payout instruction")
        val visible = buildString {
            for (row in page.records) {
                append(row.reference).append(row.title).append(row.status)
                for (value in row.values) append(value.label).append(value.value)
            }
            append(page.notice)
        }
        for (canary in fixture.input.frozen.obj("common_input").obj("private_canaries").values)
            assertFalse(canary.jsonPrimitive.content in visible)
        assertBoundReadsAndNoWrites(fixture)
    }

    private suspend fun assertMalformedSlice(variant: String) {
        val fixture = ReturnFinanceReadGatewayFixture("PARTIAL", variant)
        assertEquals("O_UNAVAILABLE", fixture.input.frozen.getValue("malformed_variants").jsonArray
            .map { it.jsonObject }.single { it.string("id") == variant }.string("expected_output"))
        val result = assertIs<LocalReadResult.Unavailable>(fixture.gateway.read(fixture.query))
        assertEquals(LocalServiceProblem.UNAVAILABLE, result.problem)
        assertBoundReadsAndNoWrites(fixture)
        // O_UNAVAILABLE finance withholding only: the separately verified header
        // and its original IDs have no partial-page surface in LocalReadResult.
    }

    private fun assertBoundReadsAndNoWrites(fixture: ReturnFinanceReadGatewayFixture) {
        assertEquals(listOf(LocalReturnCaseReadCodecV1.QUERY, LocalReturnReadCodec.QUERY), fixture.endpoint.reads.map { it.query })
        val case = fixture.input.json.decodeFromString(LocalReturnCaseQueryV1.serializer(), fixture.endpoint.reads[0].payload.copyBytes().decodeToString())
        val finance = fixture.input.json.decodeFromString(LocalReturnQueryV1.serializer(), fixture.endpoint.reads[1].payload.copyBytes().decodeToString())
        assertEquals(LocalReturnCaseQueryV1.Case(fixture.input.caseId), case)
        assertEquals(LocalReturnQueryV1.Accepted(fixture.input.id("sale"), fixture.input.caseId, fixture.input.id("current_return")), finance)
        assertEquals(0, fixture.endpoint.executeCalls)
        assertEquals(0, fixture.endpoint.lookupCalls)
        assertEquals(0, fixture.journal.calls)
    }

    private fun LocalRecord.value(label: String): LocalText = values.single { it.label.english == label }.value
    private fun assertLiteral(actual: LocalText, expected: String) {
        assertEquals(expected, actual.english)
        assertEquals(expected, actual.arabic)
    }
    private fun display(value: JsonObject, minorKey: String): String =
        "${BigDecimal(value.string(minorKey)).movePointLeft(2).setScale(2).toPlainString()} EGP"
}
