package com.ukkera.pos.app.gateway.independent.sessionpresentationv1

import com.ukkera.pos.app.LocalReadResult
import com.ukkera.pos.app.LocalRecordKind
import com.ukkera.pos.app.LocalRecordReference
import com.ukkera.pos.app.LocalSessionCheckResult
import com.ukkera.pos.app.LocalWorkflow
import com.ukkera.pos.app.LocalWorkflowQuery
import com.ukkera.pos.inventory.store.LocalStockStoreProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Client-boundary assertion slices from the unchanged session freeze. Transport and identity
 * values are explicitly controlled samples; the credential/intent journals are production JVM
 * encrypted files. No whole case/variant, UI-frame, financial-evidence, master-authority, native
 * expiry/reconnect/revocation or admitted-RPC acceptance is established by these tests.
 *
 * The original physical-payment body/cut is unavailable. Its identity, effects and preservation
 * assertions remain NOT_RUN. Zero new calls below is observed with an empty production intent
 * journal and does not establish recovery/retry behavior for an original pending payment.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionGatewayFrozenSlicesTest {
    @Test
    fun lsp005A01_supersededAdapterZeroWorkAndFreshQuantityInitiation() = runTest {
        val freeze = FrozenSessionPresentationV1.load()
        val oracle = freeze.assertion("LSP-005", "LSP-005-A01")
        freeze.fixture("LSP-F-BASE")
        freeze.fixture("LSP-F-ACTIVE-SAFE")
        val fixture = SessionGatewayFixture.create(StandardTestDispatcher(testScheduler))
        try {
            val session = fixture.signIn()
            val oldAdapter = session.workflows
            val held = fixture.client.holdRefresh(ControlledRefreshReply.SUCCESS)
            val renewal = async(start = CoroutineStart.UNDISPATCHED) { session.refresh() }
            held.captured.await()
            held.release()
            val current = assertIs<LocalSessionCheckResult.Current>(renewal.await(), oracle.message("current sample attachment"))
            check(session.isCurrent() && current.attachmentKey == session.attachmentKey) {
                "The controlled replacement cut must be current before observing stale and fresh calls."
            }

            val protectedBeforeStale = fixture.client.protectedCalls.size
            val currentBeforeStale = fixture.client.currentCalls.get()
            oldAdapter.read(quantityQuery(fixture))
            assertEquals(protectedBeforeStale, fixture.client.protectedCalls.size,
                oracle.message("captured superseded adapter initiates zero protected calls"))
            assertEquals(currentBeforeStale, fixture.client.currentCalls.get(),
                oracle.message("captured superseded adapter initiates no authentication Current call"))

            val fresh = session.workflows.read(quantityQuery(fixture))
            check(fresh is LocalReadResult.Unavailable) {
                "This initiation-only fixture returns an explicit controlled refusal, never a successful protected projection."
            }
            val call = fixture.client.protectedCalls.single()
            assertEquals("READ", call.kind, oracle.message("exactly one explicitly initiated quantity read"))
            assertEquals(LocalStockStoreProfile.QUANTITIES, call.query, oracle.message("quantity-only route"))
            assertEquals(fixture.client.currentSessionSample, call.sessionLocator,
                oracle.message("the fresh call carries the current sample context"))
            assertEquals(fixture.client.connectionSample, call.connectionId,
                oracle.message("the fresh call uses the captured connection sample"))
            fixture.retireAndReopenJournals()
        } finally { fixture.close() }
    }

    @Test
    fun lsp003A03V02_unrecoverableConcurrentTriggersStartNoBusinessWork() = runTest {
        val freeze = FrozenSessionPresentationV1.load()
        val oracle = freeze.assertion("LSP-003", "LSP-003-A03", "LSP-003-V02")
        val fixture = SessionGatewayFixture.create(StandardTestDispatcher(testScheduler))
        try {
            val session = fixture.signIn()
            val oldAdapter = session.workflows
            val held = fixture.client.holdRefresh(ControlledRefreshReply.UNRECOVERABLE)
            val first = async(start = CoroutineStart.UNDISPATCHED) { session.refresh() }
            held.captured.await()
            // Starts before the first completion and captures its original attachment before await.
            val second = async(start = CoroutineStart.UNDISPATCHED) { session.refresh() }
            val submittedAtHeldCut = fixture.client.refreshCalls.get()
            val loginsAtHeldCut = fixture.client.signInCalls.get()
            held.release()
            assertIs<LocalSessionCheckResult.Unavailable>(first.await())
            assertIs<LocalSessionCheckResult.Unavailable>(second.await())
            runCurrent()
            assertEquals(submittedAtHeldCut, fixture.client.refreshCalls.get(),
                oracle.message("no automatic renewal retry after the unrecoverable response cut"))
            assertEquals(loginsAtHeldCut, fixture.client.signInCalls.get(), oracle.message("no implicit re-login"))
            assertEquals(0, fixture.client.protectedCalls.size,
                oracle.message("renewal/fallback initiates zero payment, sale, kitchen, refund or protected business calls"))
            oldAdapter.read(quantityQuery(fixture))
            assertEquals(0, fixture.client.protectedCalls.size, oracle.message("retained client adapter remains retired"))
            fixture.retireAndReopenJournals()
        } finally { fixture.close() }
    }

    @Test
    fun lsp004A03_lostReplyFallbackStartsNoBusinessWorkBeforeLogin() = runTest {
        val freeze = FrozenSessionPresentationV1.load()
        val oracle = freeze.assertion("LSP-004", "LSP-004-A03", "LSP-004-V02")
        val fixture = SessionGatewayFixture.create(StandardTestDispatcher(testScheduler))
        try {
            val session = fixture.signIn()
            val oldAdapter = session.workflows
            val held = fixture.client.holdRefresh(ControlledRefreshReply.LOST_REPLY)
            val renewal = async(start = CoroutineStart.UNDISPATCHED) { session.refresh() }
            held.captured.await()
            val submittedAtHeldCut = fixture.client.refreshCalls.get()
            val loginsAtHeldCut = fixture.client.signInCalls.get()
            held.release()
            assertIs<LocalSessionCheckResult.Unavailable>(renewal.await())
            runCurrent()
            assertEquals(submittedAtHeldCut, fixture.client.refreshCalls.get(),
                oracle.message("no automatic retry after the lost response cut"))
            assertEquals(loginsAtHeldCut, fixture.client.signInCalls.get(), oracle.message("no implicit re-login before the explicit user cut"))
            oldAdapter.read(quantityQuery(fixture))
            assertEquals(0, fixture.client.protectedCalls.size,
                oracle.message("zero newly initiated protected calls through fallback and the retained adapter"))
            fixture.retireAndReopenJournals()
        } finally { fixture.close() }
    }

    @Test
    fun lsp009A01A02_clientCloseDefeatsHeldSuccess() = runTest {
        val freeze = FrozenSessionPresentationV1.load()
        val retirement = freeze.assertion("LSP-009", "LSP-009-A01")
        val boundary = freeze.assertion("LSP-009", "LSP-009-A02")
        val fixture = SessionGatewayFixture.create(StandardTestDispatcher(testScheduler))
        try {
            val session = fixture.signIn()
            val oldAdapter = session.workflows
            val held = fixture.client.holdRefresh(ControlledRefreshReply.SUCCESS)
            val renewal = async(start = CoroutineStart.UNDISPATCHED) { session.refresh() }
            held.captured.await()
            val loginsAtHeldCut = fixture.client.signInCalls.get()
            // Only the actual client-cleanup boundary is exercised; no master revocation is claimed.
            session.close()
            val retainedAfterClose = session.workflows
            held.release()
            assertIs<LocalSessionCheckResult.Unavailable>(renewal.await(), retirement.message("late success remains unavailable"))
            assertFalse(session.isCurrent(), retirement.message("client cleanup is not reversed by the held reply"))
            assertNull(session.attachmentKey, retirement.message("no interactive attachment is restored"))
            val currentBeforeControls = fixture.client.currentCalls.get()
            oldAdapter.read(quantityQuery(fixture))
            retainedAfterClose.read(quantityQuery(fixture))
            assertEquals(0, fixture.client.protectedCalls.size, boundary.message("post-cleanup retained adapters initiate zero protected calls"))
            assertEquals(currentBeforeControls, fixture.client.currentCalls.get(), boundary.message("post-cleanup adapters acquire no Current context"))
            assertEquals(loginsAtHeldCut, fixture.client.signInCalls.get(), boundary.message("late success causes no implicit re-login"))
            fixture.retireAndReopenJournals()
        } finally { fixture.close() }
    }

    private fun quantityQuery(fixture: SessionGatewayFixture): LocalWorkflowQuery = LocalWorkflowQuery(
        LocalWorkflow.INVENTORY, reference = LocalRecordReference(LocalRecordKind.SKU, fixture.skuSample),
    )
}
