package com.ukkera.pos.app.independent.sessionterminalv1

import com.ukkera.pos.app.LocalAction
import com.ukkera.pos.app.LocalOperationResult
import com.ukkera.pos.app.LocalPendingOperation
import com.ukkera.pos.app.LocalPendingPhase
import com.ukkera.pos.app.LocalRecordKind
import com.ukkera.pos.app.LocalRecordReference
import com.ukkera.pos.app.LocalRecordValue
import com.ukkera.pos.app.LocalWorkflow
import com.ukkera.pos.app.independent.sessionpresentationv1.SensitiveControlledSession
import com.ukkera.pos.app.independent.sessionpresentationv1.SensitiveControlledWorkflowBoundary
import com.ukkera.pos.app.independent.sessionpresentationv1.SensitivePresentationRuntimeFixture
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.app.navigation.LeaveGuard
import com.ukkera.pos.app.navigation.NavigationAction
import com.ukkera.pos.app.navigation.NavigationResult
import com.ukkera.pos.app.navigation.SecurityRoot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Frozen terminal v1 L1/white-box lifecycle slices through the real runtime, navigator,
 * Koin root and entry ViewModels. Controlled session/private reply data and negative
 * teardown callbacks do not establish genuine authentication, Master/RPC/native closure,
 * rendered frames, private-media release or original payment/effect preservation.
 * Every full STC case and all unbound layers remain NOT_RUN.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalUiFrozenSlicesTest {
    @Test
    fun stcS12_failedNegativeHolderStillClearsBothPrivateEntriesAndDeniesSuccessor() = runTest {
        val freeze = FrozenTerminalCleanupV1.load()
        val message = freeze.case("STC-S12")
        val failed = TerminalDenialProbe(IllegalStateException("Synthetic required teardown failure"))
        val later = TerminalDenialProbe()
        val session = terminalUiSession(freeze)
        withFixture(session, listOf(failed, later)) { fixture ->
            val runtime = fixture.runtime
            val oldRoot = runtime.rootScope
            val first = fixture.activeEntry()
            val controller = fixture.controller()
            controller.activate(LocalWorkflow.CHECKOUT)
            runCurrent()
            seedTerminalPrivateInput(first, freeze)
            val secondId = oldRoot.session.display?.let {
                runtime.navigator.state.value.stacks.getValue(AppArea.REPORTS).single().id
            } ?: error("The second permitted private entry must exist before teardown.")
            val second = terminalEntry(oldRoot, secondId)
            seedTerminalPrivateInput(second, freeze)
            val oldToken = runtime.navigator.token()
            val oldAttachment = runtime.presentation.value.localAttachment
            val owner = assertNotNull(runtime.retailPhysicalLifetime(session, oldToken))
            assertNotNull(owner.lifetime.attach(failed))
            assertNotNull(owner.lifetime.attach(later))

            runtime.signOut()
            runCurrent()

            assertEquals(SecurityRoot.BOOTSTRAP, runtime.navigator.state.value.root, message)
            assertNull(runtime.presentation.value.display, message)
            assertNull(oldRoot.session.display, message)
            assertTrue(oldRoot.koinScope.closed, "$message: unrelated root scope close is attempted")
            assertTrue(first.scopeClosed && second.scopeClosed, "$message: both real entry scopes are closed")
            assertTerminalPrivateInputCleared(first, message)
            assertTerminalPrivateInputCleared(second, message)
            assertTerminalReadCleared(controller.state.value, message)
            assertTrue(failed.attempts > 0 && later.attempts > 0, "$message: failure does not skip later negative cleanup")
            assertTrue(later.released, message)
            assertTrue(runtime.presentation.value.cleanupNeedsAttention, "$message: failure remains observable")
            assertEquals(freeze.failed("old_local_access_valid"),
                runtime.ownsLocalAttachment(session, oldToken, oldAttachment), message)
            assertNull(runtime.localSessionFor(oldToken), message)

            val signIns = fixture.gateway.signIns
            runtime.openSignIn(company = false, runtime.navigator.token())
            runtime.signIn("ACTOR-B", charArrayOf('s', 'a', 'm', 'p', 'l', 'e'), runtime.navigator.token())
            assertEquals(signIns, fixture.gateway.signIns, "$message: failed cleanup blocks even controlled successor delivery")
            assertEquals(SecurityRoot.BOOTSTRAP, runtime.navigator.state.value.root, message)
            assertNoNewMutationCalls(fixture, freeze, message)
        }
    }

    @Test
    fun stcS05S12S14S15_droppedEntryOwnerFailureStillClearsOtherHoldersAndRetriesExactEntry() = runTest {
        val freeze = FrozenTerminalCleanupV1.load()
        val message = listOf("STC-S05", "STC-S12", "STC-S14", "STC-S15").joinToString { freeze.case(it) }
        withFixture(terminalUiSession(freeze)) { fixture ->
            val oldRoot = fixture.runtime.rootScope
            fixture.controller().activate(LocalWorkflow.CHECKOUT)
            runCurrent()
            val externalOwner = TerminalDisposableEntryOwner()
            val entryId = fixture.runtime.navigator.state.value.stacks.getValue(AppArea.REPORTS).single().id
            val entry = externalOwner.entry(oldRoot, entryId)
            seedTerminalPrivateInput(entry, freeze)
            val attempted = mutableListOf<String>()
            var failCatalogCleanup = true
            entry.companyCatalogDrafts.lifetime.attach(entry.companyCatalogDrafts.lifetime.identity) {
                attempted += "catalog"
                if (failCatalogCleanup) throw AssertionError("Synthetic private-presentation cleanup error")
            }
            entry.foodConfigurationDrafts.lifetime.attach(entry.foodConfigurationDrafts.lifetime.identity) { attempted += "food" }
            entry.returnRightsDrafts.lifetime.attach(entry.returnRightsDrafts.lifetime.identity) { attempted += "returns" }
            entry.pricePolicyDrafts.lifetime.attach(entry.pricePolicyDrafts.lifetime.identity) { attempted += "pricing" }

            try {
                assertFailsWith<AssertionError>(message) { externalOwner.removeAndClear() }
                assertFalse(externalOwner.ownsStore, "$message: original external store owner is gone even though disposal failed")
                assertTerminalPrivateInputCleared(entry, message)
                val attemptsAfterOwnerRemoval = attempted.count { it == "catalog" }
                assertFailsWith<AssertionError>(message) { fixture.runtime.signOut() }
                runCurrent()

                assertTrue(attempted.count { it == "catalog" } > attemptsAfterOwnerRemoval,
                    "$message: real root teardown reaches the failed entry after its external store owner is gone")
                assertTrue(attempted.containsAll(listOf("catalog", "food", "returns", "pricing")), message)
                assertTerminalPrivateInputCleared(entry, message)
                assertTrue(oldRoot.koinScope.closed, "$message: Error does not strand later root cleanup")
                assertNull(fixture.runtime.presentation.value.display, message)
                assertTrue(fixture.runtime.presentation.value.cleanupNeedsAttention, message)
                assertNoNewMutationCalls(fixture, freeze, message)

                val catalogAttempts = attempted.count { it == "catalog" }
                assertFailsWith<AssertionError>(message) { fixture.runtime.close() }
                assertTrue(attempted.count { it == "catalog" } > catalogAttempts,
                    "$message: root retry retains the same failed entry without the removed external owner")
                val beforeResolution = attempted.count { it == "catalog" }
                failCatalogCleanup = false
                fixture.runtime.close()
                assertTrue(attempted.count { it == "catalog" } > beforeResolution,
                    "$message: explicit resolution releases the exact retained draft cleanup")
                assertTrue(entry.scopeClosed && oldRoot.koinScope.closed, message)
                assertNoNewMutationCalls(fixture, freeze, message)
            } finally {
                // Resolve only the synthetic callback fault before releasing harness resources.
                failCatalogCleanup = false
            }
        }
    }

    @Test
    fun stcS12S15_terminalNavigationErrorHidesPrivateRootAndNeverInvokesPlatformExit() = runTest {
        val freeze = FrozenTerminalCleanupV1.load()
        val message = "${freeze.case("STC-S12")} / ${freeze.case("STC-S15") }"
        for (action in listOf(NavigationAction.ExitWorkspace, NavigationAction.CloseHost)) {
            val failed = TerminalDenialProbe(AssertionError("Synthetic terminal negative callback error"))
            val later = TerminalDenialProbe()
            val session = terminalUiSession(freeze)
            withFixture(session, listOf(failed, later)) { fixture ->
                val runtime = fixture.runtime
                val entry = fixture.activeEntry()
                fixture.controller().activate(LocalWorkflow.CHECKOUT)
                runCurrent()
                seedTerminalPrivateInput(entry, freeze)
                val token = runtime.navigator.token()
                val owner = assertNotNull(runtime.retailPhysicalLifetime(session, token))
                assertNotNull(owner.lifetime.attach(failed))
                assertNotNull(owner.lifetime.attach(later))
                var platformExits = 0

                assertFailsWith<AssertionError>(message) { runtime.navigate(action, token) { platformExits++ } }

                assertEquals(0, platformExits, "$message: attempted denial is not completed platform exit")
                assertEquals(SecurityRoot.BOOTSTRAP, runtime.navigator.state.value.root, message)
                assertNull(runtime.presentation.value.display, message)
                assertTerminalPrivateInputCleared(entry, message)
                assertFalse(runtime.navigator.isCurrent(token), message)
                assertTrue(later.attempts > 0 && later.released, message)
                assertTrue(runtime.presentation.value.cleanupNeedsAttention, message)
                assertNoNewMutationCalls(fixture, freeze, message)
            }
        }
    }

    @Test
    fun stcS03S08_latePrivateReplyAndOldCallbacksCannotChangeControlledReplacementGuard() = runTest {
        val freeze = FrozenTerminalCleanupV1.load()
        val message = "${freeze.case("STC-S03")} / ${freeze.case("STC-S08") }"
        val boundary = terminalUiBoundary(freeze)
        val session = terminalUiSession(freeze, boundary = boundary)
        withFixture(session) { fixture ->
            val runtime = fixture.runtime
            val oldEntry = fixture.activeEntry()
            val old = fixture.controller()
            old.activate(LocalWorkflow.CHECKOUT)
            runCurrent()
            seedTerminalPrivateInput(oldEntry, freeze)
            val privateReply = LocalOperationResult.Pending(LocalPendingOperation(freeze.paymentOperation(),
                LocalAction.PAYMENT_ATTEST, terminalText(freeze.privateCanary(0)),
                listOf(LocalRecordValue(terminalText("Old private reply"), terminalText(freeze.privateCanary(2)))),
                submitAllowed = false, phase = LocalPendingPhase.DISPATCH_UNRESOLVED))
            val held = boundary.holdNextLookup(privateReply)
            old.lookup(freeze.paymentOperation(), runtime.navigator.token())
            runCurrent()
            check(held.captured.isCompleted) { "The original private reply must be held before terminal invalidation." }
            val token = runtime.navigator.token()
            runtime.signOut()
            runCurrent()
            val replacement = terminalUiSession(freeze, actorB = true, boundary = SensitiveControlledWorkflowBoundary())
            fixture.gateway.useNextSession(replacement)
            fixture.signIn()
            runCurrent()
            val freshEntry = fixture.activeEntry()
            val fresh = fixture.controller()
            fresh.activate(LocalWorkflow.CHECKOUT)
            runCurrent()
            assertNotNull(runtime.navigator.registerGuard(freshEntry.entryId, LeaveGuard.CRITICAL))
            val newState = fresh.state.value
            val calls = boundary.calls.toList()

            held.release()
            runCurrent()
            old.lookup(freeze.paymentOperation(), token)
            old.submit(freeze.paymentOperation(), runtime.navigator.token())
            old.acknowledgeRecorded(freeze.paymentOperation(), runtime.navigator.token())
            assertEquals(NavigationResult.IGNORED,
                runtime.navigator.navigate(NavigationAction.Push(AppRoute.Order(freeze.aOrder())), token), message)
            runCurrent()

            assertEquals(freeze.id("account_b"), runtime.presentation.value.display?.actorId, message)
            assertEquals(LeaveGuard.CRITICAL, runtime.navigator.state.value.guard, message)
            assertEquals(newState, fresh.state.value, message)
            assertTerminalPrivateInputCleared(oldEntry, message)
            assertTerminalReadCleared(old.state.value, message)
            assertEquals(calls, boundary.calls, "$message: retired callbacks initiate no new boundary work")
            assertNoNewMutationCalls(fixture, freeze, message)
        }
    }

    @Test
    fun stcS05_oldEntryDisposalCannotRemoveFreshCriticalGuardOrReplayItsAction() = runTest {
        val freeze = FrozenTerminalCleanupV1.load()
        val message = freeze.case("STC-S05")
        val session = terminalUiSession(freeze)
        withFixture(session) { fixture ->
            val runtime = fixture.runtime
            assertEquals(NavigationResult.CHANGED,
                runtime.navigator.navigate(NavigationAction.SelectArea(AppArea.ORDERS), runtime.navigator.token()))
            assertEquals(NavigationResult.CHANGED,
                runtime.navigator.navigate(NavigationAction.Push(AppRoute.Order(freeze.aOrder())), runtime.navigator.token()))
            val oldEntry = fixture.activeEntry()
            val old = fixture.controller()
            old.activate(LocalWorkflow.ORDERS, LocalRecordReference(LocalRecordKind.ORDER, freeze.aOrder()))
            runCurrent()
            seedTerminalPrivateInput(oldEntry, freeze)
            val oldGuard = assertNotNull(runtime.navigator.registerGuard(oldEntry.entryId, LeaveGuard.CLEAN))
            val token = runtime.navigator.token()
            assertEquals(NavigationResult.CHANGED, runtime.navigator.navigate(NavigationAction.Back, token))
            assertEquals(NavigationResult.CHANGED,
                runtime.navigator.navigate(NavigationAction.Push(AppRoute.Order(freeze.aOrder())), runtime.navigator.token()))
            val freshEntry = fixture.activeEntry()
            fixture.controller().activate(LocalWorkflow.ORDERS)
            runCurrent()
            assertNotNull(runtime.navigator.registerGuard(freshEntry.entryId, LeaveGuard.CRITICAL))
            val calls = fixture.calls.toList()

            oldEntry.closeResources() // Actual negative disposal, delivered after the fresh guard exists.
            runtime.navigator.removeGuard(oldGuard)
            old.lookup(freeze.paymentOperation(), token)
            old.submit(freeze.paymentOperation(), runtime.navigator.token())
            assertEquals(NavigationResult.BLOCKED,
                runtime.navigator.navigate(NavigationAction.Back, runtime.navigator.token()), message)
            runCurrent()

            assertTrue(oldEntry.entryId != freshEntry.entryId, message)
            assertTrue(oldEntry.scopeClosed, "$message: production disposal closes the removed entry's real Koin scope")
            assertEquals(LeaveGuard.CRITICAL, runtime.navigator.state.value.guard, message)
            assertTerminalPrivateInputCleared(oldEntry, message)
            assertEquals(calls, fixture.calls, message)
            assertTrue(session.isCurrent(), "$message: entry departure is not human-session termination")
            assertNoNewMutationCalls(fixture, freeze, message)
        }
    }

    @Test
    fun stcS11S13S14S15_shutdownRetainsFailedExactHolderAndRetriesItWithoutLateRevival() = runTest {
        val freeze = FrozenTerminalCleanupV1.load()
        val message = listOf("STC-S11", "STC-S13", "STC-S14", "STC-S15").joinToString { freeze.case(it) }
        val failed = TerminalDenialProbe(IllegalStateException("Synthetic required close failure"))
        val later = TerminalDenialProbe()
        val boundary = terminalUiBoundary(freeze)
        val session = terminalUiSession(freeze, boundary = boundary)
        withFixture(session, listOf(failed, later)) { fixture ->
            val runtime = fixture.runtime
            val entry = fixture.activeEntry()
            val controller = fixture.controller()
            controller.activate(LocalWorkflow.CHECKOUT)
            runCurrent()
            seedTerminalPrivateInput(entry, freeze)
            val held = boundary.holdNextLookup(LocalOperationResult.Unresolved(freeze.paymentOperation(),
                com.ukkera.pos.app.LocalServiceProblem.UNAVAILABLE))
            controller.lookup(freeze.paymentOperation(), runtime.navigator.token())
            runCurrent()
            check(held.captured.isCompleted)
            val token = runtime.navigator.token()
            val owner = assertNotNull(runtime.retailPhysicalLifetime(session, token))
            assertNotNull(owner.lifetime.attach(failed))
            assertNotNull(owner.lifetime.attach(later))

            assertFailsWith<IllegalStateException>(message) { runtime.close() }
            assertTrue(runtime.closed, message)
            assertNull(runtime.presentation.value.display, message)
            assertTerminalPrivateInputCleared(entry, message)
            assertTrue(later.released, message)
            val originalAttempts = failed.attempts
            held.release()
            runCurrent()
            assertFailsWith<IllegalStateException>(message) { runtime.close() }
            assertTrue(failed.attempts > originalAttempts, "$message: repeated close retains the exact failed callback")
            assertTrue(runtime.presentation.value.cleanupNeedsAttention, "$message: an unrelated late reply cannot certify cleanup")
            assertTerminalReadCleared(controller.state.value, message)
            assertFalse(runtime.navigator.isCurrent(token), message)

            failed.fail = false // Explicitly resolve the same controlled negative obligation.
            val beforeRecovery = failed.attempts
            runtime.close()
            assertTrue(failed.attempts > beforeRecovery && failed.released, message)
            runtime.close()
            runtime.openSignIn(company = false, runtime.navigator.token())
            assertTrue(runtime.closed, message)
            assertNull(runtime.presentation.value.display, message)
            assertNoNewMutationCalls(fixture, freeze, message)
        }
    }

    private fun assertNoNewMutationCalls(fixture: SensitivePresentationRuntimeFixture,
        freeze: FrozenTerminalCleanupV1, message: String) {
        assertEquals(freeze.failedCount("automatic_new_business_operation_count"),
            fixture.calls.count { it.method in setOf("STAGE", "SUBMIT", "ACKNOWLEDGE") },
            "$message: observed boundary-call count, not a Master business-ledger assertion")
    }

    private suspend fun TestScope.withFixture(session: SensitiveControlledSession,
        probes: List<TerminalDenialProbe> = emptyList(),
        block: suspend TestScope.(SensitivePresentationRuntimeFixture) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var fixture: SensitivePresentationRuntimeFixture? = null
        try {
            val current = SensitivePresentationRuntimeFixture(session).also { fixture = it }
            current.signIn()
            runCurrent()
            block(current)
        } finally {
            probes.forEach { it.fail = false }
            try { fixture?.close(); runCurrent() }
            finally { Dispatchers.resetMain() }
        }
    }
}
