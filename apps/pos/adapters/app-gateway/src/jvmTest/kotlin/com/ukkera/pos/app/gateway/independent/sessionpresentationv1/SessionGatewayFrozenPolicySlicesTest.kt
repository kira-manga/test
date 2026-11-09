package com.ukkera.pos.app.gateway.independent.sessionpresentationv1

import com.ukkera.pos.app.LocalReadResult
import com.ukkera.pos.app.LocalRecordKind
import com.ukkera.pos.app.LocalRecordReference
import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.app.LocalSessionCheckResult
import com.ukkera.pos.app.LocalWorkflow
import com.ukkera.pos.app.LocalWorkflowQuery
import com.ukkera.pos.app.SessionDisplayContext
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.database.ReadFailure
import com.ukkera.pos.inventory.store.LocalStockStoreProfile
import com.ukkera.pos.sales.store.LocalBranchReportReadCodec
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Unchanged frozen policy-continuity assertions, observed only at shared client projection and
 * initiating-call boundaries with controlled authentication/policy/refusal samples. Both JVM
 * journals are production encrypted files. No actual policy activation, master grants, successful
 * protected quantity/report projection, UI frame, original-payment evidence or admitted RPC is
 * qualified. An empty intent journal does not establish original pending-operation preservation.
 *
 * The four earlier SessionGatewayFrozenSlicesTest method inputs/assertions remain unchanged.
 * Full LSP cases/variants, cached report/cursor/media and held report-success/event/error schedules
 * remain NOT_RUN without their genuine independently supplied fixtures.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionGatewayFrozenPolicySlicesTest {
    @Test
    fun lsp001A01A03V01_newerUnchangedPolicyKeepsClientAndStartsNoWork() = runTest {
        val freeze = FrozenSessionPresentationV1.load()
        val continuation = freeze.assertion("LSP-001", "LSP-001-A01", "LSP-001-V01")
        val boundary = freeze.assertion("LSP-001", "LSP-001-A03", "LSP-001-V01")
        val fixture = SessionGatewayFixture.create(StandardTestDispatcher(testScheduler))
        try {
            val session = fixture.signIn()
            val originalDisplay = session.display
            val loginsBeforeRenewal = fixture.client.signInCalls.get()
            val held = fixture.client.holdRefresh(ControlledRefreshReply.SUCCESS,
                ControlledPolicyInput.quantityOnly(Revision(1)))
            val renewal = async(start = CoroutineStart.UNDISPATCHED) { session.refresh() }
            held.captured.await()
            held.release()
            val current = assertIs<LocalSessionCheckResult.Current>(renewal.await(),
                continuation.message("legitimate newer-reference client continuation"))
            assertSameEmployee(originalDisplay, current.display, continuation)
            assertTrue(session.isCurrent(), continuation.message("the same client remains interactive"))
            assertEquals(originalDisplay.allowedAreas, current.display.allowedAreas,
                continuation.message("unchanged grant samples retain the same current navigation hints"))
            runCurrent()
            assertEquals(loginsBeforeRenewal, fixture.client.signInCalls.get(), boundary.message("no implicit re-login"))
            assertEquals(0, fixture.client.protectedCalls.size,
                boundary.message("newly initiated protected work before any explicit lookup is zero"))
            fixture.retireAndReopenJournals()
        } finally { fixture.close() }
    }

    @Test
    fun lsp002A01A02_narrowedPolicyKeepsEmployeeAndQuantityInitiation() = runTest {
        val freeze = FrozenSessionPresentationV1.load()
        val continuation = freeze.assertion("LSP-002", "LSP-002-A01")
        val boundary = freeze.assertion("LSP-002", "LSP-002-A02")
        freeze.fixture("LSP-F-POLICY")
        val fixture = policyFixture(StandardTestDispatcher(testScheduler))
        try {
            val session = fixture.signIn()
            val originalDisplay = session.display
            val oldAdapter = session.workflows
            check(AppArea.REPORTS in originalDisplay.allowedAreas && AppArea.STOCK in originalDisplay.allowedAreas) {
                "The controlled input must contain both the report and allowed quantity hints before narrowing."
            }
            val held = fixture.client.holdRefresh(ControlledRefreshReply.SUCCESS,
                ControlledPolicyInput.quantityOnly(Revision(1)))
            val renewal = async(start = CoroutineStart.UNDISPATCHED) { session.refresh() }
            held.captured.await()
            held.release()
            val current = assertIs<LocalSessionCheckResult.Current>(renewal.await(),
                continuation.message("narrowing does not imply employee denial"))
            assertSameEmployee(originalDisplay, current.display, continuation)
            assertTrue(session.isCurrent(), continuation.message("the still-permitted client remains interactive"))
            assertFalse(AppArea.REPORTS in current.display.allowedAreas,
                continuation.message("the removed report hint is absent from the current projection"))
            assertTrue(AppArea.STOCK in current.display.allowedAreas,
                continuation.message("the allowed quantity hint is retained"))
            val beforeOld = fixture.client.protectedCalls.size
            oldAdapter.read(LocalWorkflowQuery(LocalWorkflow.REPORTS))
            assertEquals(beforeOld, fixture.client.protectedCalls.size,
                boundary.message("the retired adapter initiates no new report work"))
            val quantity = session.workflows.read(quantityQuery(fixture))
            check(quantity is LocalReadResult.Unavailable) { "Only initiation, followed by a controlled refusal, is wired." }
            val call = fixture.client.protectedCalls.single()
            assertEquals(LocalStockStoreProfile.QUANTITIES, call.query, boundary.message("one explicit quantity initiation"))
            assertEquals(fixture.client.currentSessionSample, call.sessionLocator,
                boundary.message("the explicit quantity call uses the current sample context"))
            fixture.retireAndReopenJournals()
        } finally { fixture.close() }
    }

    @Test
    fun lsp002A01A02V01_explicitCurrentReadAdoptsPolicyWithoutHint() = runTest {
        val freeze = FrozenSessionPresentationV1.load()
        val continuation = freeze.assertion("LSP-002", "LSP-002-A01", "LSP-002-V01")
        val boundary = freeze.assertion("LSP-002", "LSP-002-A02", "LSP-002-V01")
        val fixture = policyFixture(StandardTestDispatcher(testScheduler))
        try {
            val session = fixture.signIn()
            val originalDisplay = session.display
            val oldAdapter = session.workflows
            check(AppArea.REPORTS in originalDisplay.allowedAreas) { "A report hint is required before this sample cut." }
            // No notification or actual activation is fabricated: only the next controlled
            // protected Current reply changes. The explicit client read discovers that sample.
            fixture.client.replaceCurrentPolicyReplySample(ControlledPolicyInput.quantityOnly(Revision(1)))
            val current = assertIs<LocalSessionCheckResult.Current>(session.current(),
                continuation.message("explicit current read retains the enabled employee"))
            assertSameEmployee(originalDisplay, current.display, continuation)
            assertTrue(session.isCurrent(), continuation.message("the narrowed current client remains interactive"))
            assertFalse(AppArea.REPORTS in current.display.allowedAreas,
                continuation.message("the known narrowed projection carries no retired report hint"))
            assertTrue(AppArea.STOCK in current.display.allowedAreas,
                continuation.message("the permitted quantity hint remains available"))
            oldAdapter.read(LocalWorkflowQuery(LocalWorkflow.REPORTS))
            assertEquals(0, fixture.client.protectedCalls.size, boundary.message("old client adapter conveys no new work"))
            val quantity = session.workflows.read(quantityQuery(fixture))
            check(quantity is LocalReadResult.Unavailable) { "Only initiation, followed by a controlled refusal, is wired." }
            val call = fixture.client.protectedCalls.single()
            assertEquals(LocalStockStoreProfile.QUANTITIES, call.query, boundary.message("one current quantity initiation"))
            assertEquals(fixture.client.currentSessionSample, call.sessionLocator,
                boundary.message("the current sample context initiates the allowed quantity route"))
            fixture.retireAndReopenJournals()
        } finally { fixture.close() }
    }

    @Test
    fun lsp005V02A01_retiredQuantityRegistrationZeroWorkAndCurrentRefusal() = runTest {
        val freeze = FrozenSessionPresentationV1.load()
        val boundary = freeze.assertion("LSP-005", "LSP-005-V02-A01", "LSP-005-V02")
        val fixture = policyFixture(StandardTestDispatcher(testScheduler))
        try {
            val session = fixture.signIn()
            val oldAdapter = session.workflows
            check(AppArea.STOCK in session.display.allowedAreas) { "The selected quantity action must initially be present in this sample." }
            val held = fixture.client.holdRefresh(ControlledRefreshReply.SUCCESS,
                ControlledPolicyInput.reportsOnly(Revision(1)))
            val renewal = async(start = CoroutineStart.UNDISPATCHED) { session.refresh() }
            held.captured.await()
            held.release()
            val current = assertIs<LocalSessionCheckResult.Current>(renewal.await())
            assertFalse(AppArea.STOCK in current.display.allowedAreas,
                boundary.message("the removed quantity action has no current navigation hint"))
            assertTrue(AppArea.REPORTS in current.display.allowedAreas && session.isCurrent(),
                boundary.message("the separate permitted sample area keeps the client current"))
            val currentCallsBeforeOld = fixture.client.currentCalls.get()
            oldAdapter.read(quantityQuery(fixture))
            assertEquals(0, fixture.client.protectedCalls.size,
                boundary.message("captured superseded quantity registration initiates zero protected work"))
            assertEquals(currentCallsBeforeOld, fixture.client.currentCalls.get(),
                boundary.message("captured superseded quantity registration acquires no current context"))

            // A scheduled public refusal checks client result/context handling only. It is not
            // a fabricated master decision or evidence of current-grant enforcement.
            fixture.client.refuseNextRead(ReadFailure.AUTHORIZATION_DENIED)
            val denied = assertIs<LocalReadResult.Unavailable>(session.workflows.read(quantityQuery(fixture)))
            assertEquals(LocalServiceProblem.DENIED, denied.problem,
                boundary.message("the current direct route preserves the scheduled protected refusal"))
            val call = fixture.client.protectedCalls.single()
            assertEquals(LocalStockStoreProfile.QUANTITIES, call.query, boundary.message("the frozen selected quantity route"))
            assertEquals(fixture.client.currentSessionSample, call.sessionLocator,
                boundary.message("the direct request uses the current sample context without an old adapter"))
            fixture.retireAndReopenJournals()
        } finally { fixture.close() }
    }

    private suspend fun policyFixture(dispatcher: CoroutineDispatcher): SessionGatewayFixture = SessionGatewayFixture.create(
        dispatcher, ControlledPolicyInput.quantityAndReports(Revision(0)),
        setOf(LocalStockStoreProfile.QUANTITIES, LocalBranchReportReadCodec.QUERY),
    )

    private fun quantityQuery(fixture: SessionGatewayFixture): LocalWorkflowQuery = LocalWorkflowQuery(
        LocalWorkflow.INVENTORY, reference = LocalRecordReference(LocalRecordKind.SKU, fixture.skuSample),
    )

    private fun assertSameEmployee(before: SessionDisplayContext, after: SessionDisplayContext, oracle: FrozenAssertion) {
        assertEquals(before.actorId, after.actorId, oracle.message("employee sample identity is unchanged"))
        assertEquals(before.companyId, after.companyId, oracle.message("company sample scope is unchanged"))
        assertEquals(before.branchId, after.branchId, oracle.message("branch sample scope is unchanged"))
    }
}
