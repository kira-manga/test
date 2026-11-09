package com.ukkera.pos.app.gateway.independent.sessionterminalv1

import com.ukkera.pos.app.BranchUiSession
import com.ukkera.pos.app.LocalReadResult
import com.ukkera.pos.app.LocalRecordKind
import com.ukkera.pos.app.LocalRecordReference
import com.ukkera.pos.app.LocalSessionCheckResult
import com.ukkera.pos.app.LocalWorkflow
import com.ukkera.pos.app.LocalWorkflowGateway
import com.ukkera.pos.app.LocalWorkflowQuery
import com.ukkera.pos.app.SignInFailure
import com.ukkera.pos.app.SignInResult
import com.ukkera.pos.app.gateway.independent.sessionpresentationv1.SessionGatewayFixture
import com.ukkera.pos.app.gateway.independent.sessionpresentationv1.SessionJournalObservation
import com.ukkera.pos.security.BranchScope
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Frozen v1 L1/white-box client lifecycle slices. Only the encrypted credential/intent
 * journal IO and fresh-reader reopening are production persistence observations. The
 * transport/session values and required cleanup faults are controlled samples. Reflection
 * reads the actual negative owners; it never changes them or manufactures native authority.
 *
 * The original retail payment body/evidence is absent from the empty intent journal. These
 * tests do not pass full STC cases, Master authentication/revocation, genuine RPC, original
 * business preservation, native close, successor writer authority or native qualification.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalGatewayFrozenSlicesTest {
    @Test
    fun stcS12_failedEpochDenialStillAttemptsLaterExactHoldersAndRetainsCredentials() = runTest {
        val freeze = FrozenTerminalCleanupV1.load()
        val message = freeze.case("STC-S12")
        val failed = TerminalGatewayDenialProbe(IllegalStateException("Synthetic required epoch teardown failure"))
        val laterEpoch = TerminalGatewayDenialProbe()
        val laterSession = TerminalGatewayDenialProbe()
        val fixture = terminalGatewayFixture(freeze, StandardTestDispatcher(testScheduler))
        try {
            val session = fixture.signIn()
            val oldAdapter = session.workflows
            val retained = retainedFixtureCredentials(fixture, freeze)
            val lifetimes = terminalNegativeLifetimes(session)
            assertNotNull(lifetimes.epoch.attach(failed))
            assertNotNull(lifetimes.epoch.attach(laterEpoch))
            assertNotNull(lifetimes.session.attach(laterSession))

            assertFailsWith<IllegalStateException>(message) { session.close() }

            assertTrue(failed.attempts > 0, message)
            assertTrue(laterEpoch.attempts > 0 && laterEpoch.released,
                "$message: a failure does not skip the later callback in the same real epoch")
            assertTrue(laterSession.attempts > 0 && laterSession.released,
                "$message: a nested epoch failure does not skip the later real session holder")
            assertTrue(lifetimes.session.denied && lifetimes.epoch.denied, message)
            assertRetiredWithoutDispatch(fixture, session, oldAdapter, freeze, message)
            assertBlockedSuccessor(fixture, retained, freeze, message)

            failed.resolveFault()
            fixture.retireAndReopenJournals()
            assertEquals(retained, fixture.observeJournals(),
                "$message: original client credential rows survive actual encrypted-reader reopening")
        } finally {
            failed.resolveFault()
            fixture.close()
        }
    }

    @Test
    fun stcS13S14S15_gatewayShutdownRetriesTheSameErrorHolderAndRetainsReopenedCredentials() = runTest {
        val freeze = FrozenTerminalCleanupV1.load()
        val message = listOf("STC-S13", "STC-S14", "STC-S15").joinToString { freeze.case(it) }
        val failed = TerminalGatewayDenialProbe(AssertionError("Synthetic required shutdown cleanup error"))
        val later = TerminalGatewayDenialProbe()
        val fixture = terminalGatewayFixture(freeze, StandardTestDispatcher(testScheduler))
        try {
            val session = fixture.signIn()
            val oldAdapter = session.workflows
            val retained = retainedFixtureCredentials(fixture, freeze)
            val lifetimes = terminalNegativeLifetimes(session)
            assertNotNull(lifetimes.session.attach(failed))
            assertNotNull(lifetimes.session.attach(later))

            assertFailsWith<AssertionError>(message) { fixture.gateway.close() }
            assertTrue(later.released && later.attempts > 0, "$message: Error does not skip safe independent cleanup")
            assertRetiredWithoutDispatch(fixture, session, oldAdapter, freeze, message)
            val firstAttempts = failed.attempts
            later.deny() // Unrelated late success cannot certify the still-failing exact target.
            assertFailsWith<AssertionError>(message) { fixture.gateway.closeAndAwait() }
            assertTrue(failed.attempts > firstAttempts, "$message: repeated shutdown retries the exact retained target")
            assertBlockedSuccessor(fixture, retained, freeze, message)

            val attemptsBeforeResolution = failed.attempts
            failed.resolveFault()
            fixture.gateway.closeAndAwait()
            assertTrue(failed.attempts > attemptsBeforeResolution && failed.released,
                "$message: only fulfillment of the same controlled obligation ends its failure")
            fixture.retireAndReopenJournals()
            assertEquals(retained, fixture.observeJournals(), message)
            fixture.gateway.closeAndAwait()
            assertFalse(session.isCurrent(), message)
            assertBlockedSuccessor(fixture, retained, freeze,
                "$message: cleanup recovery does not reopen an already closed client gateway")
        } finally {
            failed.resolveFault()
            fixture.close()
        }
    }

    @Test
    fun stcS04S11_overlappingSuccessfulDenialKeepsSuccessorBlockedUntilTheLastDrainReturns() = runTest {
        val freeze = FrozenTerminalCleanupV1.load()
        val message = "${freeze.case("STC-S04")} / ${freeze.case("STC-S11") }"
        val held = TerminalHeldDenial()
        val threads = TerminalDenialThreads()
        val fixture = terminalGatewayFixture(freeze, StandardTestDispatcher(testScheduler))
        try {
            val session = fixture.signIn()
            val oldAdapter = session.workflows
            val retained = retainedFixtureCredentials(fixture, freeze)
            assertNotNull(terminalNegativeLifetimes(session).session.attach(held))
            val first = threads.closeSession(session)
            check(held.entered.await(10, TimeUnit.SECONDS)) { "The first real denial must reach its synthetic barrier." }
            val second = threads.closeSession(session)
            second.get(10, TimeUnit.SECONDS)

            assertFalse(first.isDone, "$message: the first required denial is still in flight")
            assertTrue(held.successfulAttempts > 0, "$message: the overlapping caller genuinely completed its own callback")
            assertRetiredWithoutDispatch(fixture, session, oldAdapter, freeze, message)
            assertBlockedSuccessor(fixture, retained, freeze,
                "$message: one successful close cannot authorize delivery while another can still fail")

            held.releaseFirst()
            first.get(10, TimeUnit.SECONDS)
            assertTrue(fixture.gateway.configured,
                "$message: local client availability returns only after all controlled negative drains finish")
            assertFalse(session.isCurrent(), message)
            assertEquals(retained, fixture.observeJournals(), message)
            // No successor is authenticated: the controlled client cannot establish genuine B authority.
            fixture.retireAndReopenJournals()
            assertEquals(retained, fixture.observeJournals(), message)
        } finally {
            held.releaseFirst()
            try { threads.close() } finally { fixture.close() }
        }
    }

    @Test
    fun stcS04S11S13_lateFailureRetainsExactCustodyAfterAnOverlappingSuccessfulClose() = runTest {
        val freeze = FrozenTerminalCleanupV1.load()
        val message = listOf("STC-S04", "STC-S11", "STC-S13").joinToString { freeze.case(it) }
        val held = TerminalHeldDenial(AssertionError("Synthetic late first-denial failure"))
        val threads = TerminalDenialThreads()
        val fixture = terminalGatewayFixture(freeze, StandardTestDispatcher(testScheduler))
        try {
            val session = fixture.signIn()
            val oldAdapter = session.workflows
            val retained = retainedFixtureCredentials(fixture, freeze)
            assertNotNull(terminalNegativeLifetimes(session).session.attach(held))
            val first = threads.closeSession(session)
            check(held.entered.await(10, TimeUnit.SECONDS)) { "The first real denial must reach its synthetic barrier." }
            val second = threads.closeSession(session)
            second.get(10, TimeUnit.SECONDS)
            assertFalse(first.isDone, message)
            assertTrue(held.successfulAttempts > 0, message)
            assertBlockedSuccessor(fixture, retained, freeze, "$message: the unresolved first caller still fences successor delivery")

            held.releaseFirst()
            val late = assertFailsWith<ExecutionException>(message) { first.get(10, TimeUnit.SECONDS) }
            assertIs<AssertionError>(late.cause, "$message: the held required denial really failed after the second caller succeeded")
            assertRetiredWithoutDispatch(fixture, session, oldAdapter, freeze, message)
            assertBlockedSuccessor(fixture, retained, freeze,
                "$message: late failure remains retained after the active session pointer and successful attachment are removed")

            val attemptsBeforeRetry = held.attempts
            fixture.gateway.closeAndAwait()
            assertTrue(held.attempts > attemptsBeforeRetry,
                "$message: shutdown still reaches the exact original callback, not a replacement holder")
            fixture.retireAndReopenJournals()
            assertEquals(retained, fixture.observeJournals(), message)
            assertFalse(session.isCurrent(), message)
        } finally {
            held.releaseFirst()
            try { threads.close() } finally { fixture.close() }
        }
    }

    private suspend fun retainedFixtureCredentials(fixture: SessionGatewayFixture,
        freeze: FrozenTerminalCleanupV1): SessionJournalObservation = fixture.observeJournals().also { retained ->
        val row = retained.sessions.single()
        check(row.identity.actorId == freeze.id("account_a") && row.identity.deviceId == freeze.id("shared_client_device") &&
            row.identity.scope == scopeForFreeze(freeze) && row.sessionId == freeze.id("session_a_original") && !row.originalRevoked) {
            "The controlled original credential sample must match the assigned frozen identity labels."
        }
        check(retained.pendingOperationIds.isEmpty()) {
            "This bounded harness has no original payment body; business-evidence preservation remains unbound."
        }
    }

    private fun scopeForFreeze(freeze: FrozenTerminalCleanupV1) = BranchScope(
        freeze.id("organization_1"), freeze.id("retail_company"), freeze.id("retail_branch"))

    private suspend fun assertRetiredWithoutDispatch(fixture: SessionGatewayFixture, session: BranchUiSession,
        oldAdapter: LocalWorkflowGateway, freeze: FrozenTerminalCleanupV1, message: String) {
        val currentCalls = fixture.client.currentCalls.get()
        val refreshCalls = fixture.client.refreshCalls.get()
        assertEquals(freeze.failed("old_local_access_valid"), session.isCurrent(), message)
        assertNull(session.attachmentKey, message)
        assertIs<LocalSessionCheckResult.Unavailable>(session.current(), message)
        assertIs<LocalSessionCheckResult.Unavailable>(session.refresh(), message)
        val query = LocalWorkflowQuery(LocalWorkflow.INVENTORY,
            reference = LocalRecordReference(LocalRecordKind.SKU, fixture.skuSample))
        assertIs<LocalReadResult.Unavailable>(oldAdapter.read(query), message)
        assertIs<LocalReadResult.Unavailable>(session.workflows.read(query), message)
        assertEquals(currentCalls, fixture.client.currentCalls.get(), "$message: old capabilities obtain no Current context")
        assertEquals(refreshCalls, fixture.client.refreshCalls.get(), "$message: old capabilities initiate no refresh")
        assertEquals(0, fixture.client.protectedCalls.size, "$message: no protected client-contract call is dispatched")
        assertEquals(freeze.failedCount("automatic_new_business_operation_count"),
            fixture.client.protectedCalls.count { it.kind == "EXECUTE" },
            "$message: observed client mutation-call count, not a Master business-ledger assertion")
    }

    private suspend fun assertBlockedSuccessor(fixture: SessionGatewayFixture, retained: SessionJournalObservation,
        freeze: FrozenTerminalCleanupV1, message: String) {
        assertFalse(fixture.gateway.configured, message)
        val before = fixture.client.signInCalls.get()
        val result = fixture.gateway.signIn("ACTOR-B", charArrayOf('s', 'a', 'm', 'p', 'l', 'e'))
        val rejected = assertIs<SignInResult.Rejected>(result, message)
        assertEquals(SignInFailure.UNAVAILABLE, rejected.reason, message)
        assertEquals(freeze.failed("successor_session_authorized"), result is SignInResult.Authenticated<*>, message)
        assertEquals(before, fixture.client.signInCalls.get(), "$message: the blocked request never reaches even the controlled client")
        assertEquals(retained, fixture.observeJournals(),
            "$message: no client-authentication revision or issuance is created by the blocked request")
    }
}
