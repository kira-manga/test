package com.ukkera.pos.app

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.app.navigation.AppNavigator
import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.app.navigation.NavigationAction
import com.ukkera.pos.app.navigation.NavigationResult
import com.ukkera.pos.app.navigation.SecurityRoot
import com.ukkera.pos.app.training.TrainingWorkspace
import com.ukkera.pos.app.ui.AppLanguage
import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.koin.core.parameter.parametersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class UkkeraRuntimeTest {
    private val platform = PlatformCapabilities(PlatformFamily.DESKTOP)

    @Test fun defaultsNeverAuthenticateAndTrainingNeverCallsEitherGateway() = runTest {
        val runtime = UkkeraRuntime(AppDependencies(platform = platform))
        runtime.openSignIn(company = false, runtime.navigator.token())
        val input = "not-a-real-password".toCharArray()
        runtime.signIn("operator", input, runtime.navigator.token())
        assertEquals(SecurityRoot.BRANCH_SIGN_IN, runtime.navigator.state.value.root)
        assertEquals(SignInFailure.NOT_CONFIGURED, runtime.presentation.value.signInFailure)
        assertTrue(input.all { it == '\u0000' })
        runtime.signOut()
        runtime.openTraining(company = false, runtime.navigator.token())
        assertTrue(runtime.presentation.value.training)
        assertEquals(SecurityRoot.BRANCH_WORKSPACE, runtime.navigator.state.value.root)
        assertFalse(runtime.restoreAuthenticatedRoutes())
        runtime.close()

        val failIfCalled = object : BranchUiGateway {
            override val configured = true
            override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> = error("Training called a production gateway")
        }
        val central = object : CentralUiGateway {
            override val configured = true
            override suspend fun signIn(account: String, secret: CharArray): SignInResult<CentralUiSession> = error("Training called central auth")
        }
        val isolated = UkkeraRuntime(AppDependencies(failIfCalled, central, platform))
        isolated.openTraining(company = true, isolated.navigator.token())
        assertEquals(SecurityRoot.COMPANY_WORKSPACE, isolated.navigator.state.value.root)
        assertTrue(isolated.presentation.value.training)
        assertFalse(isolated.restoreAuthenticatedRoutes())
        isolated.close()
    }

    @Test fun credentialBufferIsClearedEvenWhenTheEntryLaunchNeverRuns() = runTest {
        var calls = 0
        val gateway = object : BranchUiGateway {
            override val configured = true
            override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> {
                calls++
                return SignInResult.Rejected(SignInFailure.DENIED)
            }
        }
        val runtime = UkkeraRuntime(AppDependencies(branchGateway = gateway, platform = platform))
        runtime.openSignIn(company = false, runtime.navigator.token())
        val disposedEntry = Job().apply { cancel() }
        val input = "test-not-submitted".toCharArray()
        runtime.launchSignIn(CoroutineScope(coroutineContext + disposedEntry), "a", input, runtime.navigator.token()).join()
        assertEquals(0, calls)
        assertTrue(input.all { it == '\u0000' })
        runtime.close()
    }

    @Test fun ignoredRapidSignInDuplicatesCannotReleaseTheFirstAttemptsBusyLatch() = runTest {
        for (company in listOf(false, true)) {
            val response = CompletableDeferred<SignInResult<Nothing>>()
            var calls = 0
            val branch = object : BranchUiGateway {
                override val configured = true
                override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> {
                    calls++
                    return response.await()
                }
            }
            val central = object : CentralUiGateway {
                override val configured = true
                override suspend fun signIn(account: String, secret: CharArray): SignInResult<CentralUiSession> {
                    calls++
                    return response.await()
                }
            }
            val runtime = UkkeraRuntime(AppDependencies(branch, central, platform, centralServicesEnabled = company))
            runtime.openSignIn(company, runtime.navigator.token())
            val token = runtime.navigator.token()
            val ownerSecret = "test-owner".toCharArray()
            val owner = async { runtime.signIn("a", ownerSecret, token) }
            runCurrent()
            assertEquals(1, calls)
            assertTrue(runtime.presentation.value.signingIn)
            repeat(3) {
                val duplicateSecret = "test-duplicate".toCharArray()
                runtime.signIn("a", duplicateSecret, token)
                assertTrue(duplicateSecret.all { it == '\u0000' })
                assertTrue(runtime.presentation.value.signingIn)
                assertEquals(1, calls)
            }
            response.complete(SignInResult.Rejected(SignInFailure.DENIED))
            owner.await()
            assertFalse(runtime.presentation.value.signingIn)
            assertTrue(ownerSecret.all { it == '\u0000' })
            runtime.signIn("a", "test-retry".toCharArray(), runtime.navigator.token())
            assertEquals(2, calls)
            assertFalse(runtime.presentation.value.signingIn)
            runtime.close()
        }
    }

    @Test fun workspaceOwnedRestoreSurvivesDisposalOfTheSuccessfulSignInCaller() = runTest {
        val sessionDisplay = TrainingWorkspace.display(company = false)
        val order = AppRoute.Order(DomainId("00000000-0000-4000-8000-000000000042"))
        val source = AppNavigator().apply {
            replaceRoot(SecurityRoot.BRANCH_WORKSPACE, sessionDisplay)
            navigate(NavigationAction.SelectArea(AppArea.ORDERS), token())
            navigate(NavigationAction.Push(order), token())
        }
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var reads = 0
        var closes = 0
        val session = object : BranchUiSession {
            override val display = sessionDisplay
            override fun isCurrent(): Boolean = closes == 0
            override suspend fun mayRestore(route: AppRoute): Boolean {
                reads++
                entered.complete(Unit)
                resume.await()
                return true
            }
            override fun close() { closes++ }
        }
        val gateway = object : BranchUiGateway {
            override val configured = true
            override suspend fun signIn(account: String, secret: CharArray) = SignInResult.Authenticated(session)
        }
        val runtime = UkkeraRuntime(AppDependencies(branchGateway = gateway, platform = platform))
        assertTrue(runtime.navigator.quarantineRestore(source.checkpoint()!!))
        runtime.openSignIn(company = false, runtime.navigator.token())
        val token = runtime.navigator.token()
        val signInEntry = launch {
            runtime.signIn("a", "test".toCharArray(), token)
            awaitCancellation() // Models the old entry being forgotten after successful root replacement.
        }
        runCurrent()
        assertEquals(SecurityRoot.BRANCH_WORKSPACE, runtime.navigator.state.value.root)
        assertEquals(0, reads)
        signInEntry.cancelAndJoin()
        val workspace = async { runtime.restoreAuthenticatedRoutes() }
        entered.await()
        assertEquals(AppRoute.Cashier, runtime.navigator.state.value.activeEntry.route)
        resume.complete(Unit)
        assertTrue(workspace.await())
        assertEquals(order, runtime.navigator.state.value.activeEntry.route)
        assertTrue(reads > 1)
        assertEquals(0, closes)
        runtime.close()
        assertEquals(1, closes)
    }

    @Test fun delayedWorkspaceRestoreCannotInstallRoutesIntoReplacementTraining() = runTest {
        val sessionDisplay = TrainingWorkspace.display(company = true)
        val source = AppNavigator().apply {
            replaceRoot(SecurityRoot.COMPANY_WORKSPACE, sessionDisplay)
            navigate(NavigationAction.SelectArea(AppArea.PEOPLE), token())
        }
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var closes = 0
        val session = object : CentralUiSession {
            override val display = sessionDisplay
            override suspend fun mayRestore(route: AppRoute): Boolean {
                entered.complete(Unit)
                resume.await()
                return true
            }
            override fun close() { closes++ }
        }
        val gateway = object : CentralUiGateway {
            override val configured = true
            override suspend fun signIn(account: String, secret: CharArray) = SignInResult.Authenticated(session)
        }
        val runtime = UkkeraRuntime(AppDependencies(centralGateway = gateway, platform = platform, centralServicesEnabled = true))
        assertTrue(runtime.navigator.quarantineRestore(source.checkpoint()!!))
        runtime.openSignIn(company = true, runtime.navigator.token())
        runtime.signIn("central", "test".toCharArray(), runtime.navigator.token())
        val workspace = async { runtime.restoreAuthenticatedRoutes() }
        entered.await()
        runtime.signOut()
        runtime.openTraining(company = false, runtime.navigator.token())
        resume.complete(Unit)
        assertFalse(workspace.await())
        assertEquals(SecurityRoot.BRANCH_WORKSPACE, runtime.navigator.state.value.root)
        assertEquals(AppRoute.Cashier, runtime.navigator.state.value.activeEntry.route)
        assertTrue(runtime.presentation.value.training)
        assertEquals(1, closes)
        runtime.close()
    }

    @Test fun firstSlowSignInReplyIsReleasedAndCannotRevealEmployeeAToB() = runTest {
        val first = CompletableDeferred<SignInResult<BranchUiSession>>()
        val second = CompletableDeferred<SignInResult<BranchUiSession>>()
        val aSession = TestBranchSession(1)
        val bSession = TestBranchSession(2)
        val gateway = object : BranchUiGateway {
            override val configured = true
            override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> =
                if (account == "a") first.await() else second.await()
        }
        val runtime = UkkeraRuntime(AppDependencies(branchGateway = gateway, platform = platform))
        runtime.openSignIn(company = false, runtime.navigator.token())
        val tokenA = runtime.navigator.token()
        val aSecret = "test-a".toCharArray()
        val a = async { runtime.signIn("a", aSecret, tokenA) }
        runCurrent()
        runtime.signOut()
        runtime.openSignIn(company = false, runtime.navigator.token())
        val b = async { runtime.signIn("b", "test-b".toCharArray(), runtime.navigator.token()) }
        runCurrent()
        second.complete(SignInResult.Authenticated(bSession))
        b.await()
        first.complete(SignInResult.Authenticated(aSession))
        a.await()
        assertEquals(bSession.display.actorId, runtime.presentation.value.display?.actorId)
        assertEquals(SecurityRoot.BRANCH_WORKSPACE, runtime.navigator.state.value.root)
        assertFalse(runtime.navigator.isCurrent(tokenA))
        assertEquals(1, aSession.closes)
        assertEquals(0, bSession.closes)
        assertTrue(aSecret.all { it == '\u0000' })
        runtime.signOut()
        assertEquals(1, bSession.closes)
        runtime.close()
    }

    @Test fun slowCentralReplyCannotBecomeLocalCashierAuthority() = runTest {
        val reply = CompletableDeferred<SignInResult<CentralUiSession>>()
        var closes = 0
        val session = object : CentralUiSession {
            override val display = TrainingWorkspace.display(company = true)
            override suspend fun mayRestore(route: AppRoute) = true
            override fun close() { closes++ }
        }
        val gateway = object : CentralUiGateway {
            override val configured = true
            override suspend fun signIn(account: String, secret: CharArray): SignInResult<CentralUiSession> = reply.await()
        }
        val runtime = UkkeraRuntime(AppDependencies(centralGateway = gateway, platform = platform, centralServicesEnabled = true))
        runtime.openSignIn(company = true, runtime.navigator.token())
        val job = async { runtime.signIn("central", "test".toCharArray(), runtime.navigator.token()) }
        runCurrent()
        runtime.signOut()
        runtime.openTraining(company = false, runtime.navigator.token())
        reply.complete(SignInResult.Authenticated(session))
        job.await()
        assertEquals(1, closes)
        assertEquals(SecurityRoot.BRANCH_WORKSPACE, runtime.navigator.state.value.root)
        assertTrue(runtime.presentation.value.training)
        runtime.close()
    }

    @Test fun entryViewModelsAndSessionScopesClearWithoutAProcessGlobalKoinContainer() {
        val a = UkkeraRuntime(AppDependencies(platform = platform))
        val b = UkkeraRuntime(AppDependencies(platform = platform))
        a.openTraining(company = false, a.navigator.token())
        b.openTraining(company = false, b.navigator.token())
        val aRoot = a.rootScope
        val bRoot = b.rootScope
        val aEntry = entry(aRoot, 888)
        val bEntry = entry(bRoot, 888)
        assertSame(aEntry, entry(aRoot, 888))
        assertNotSame(aEntry.resources, bEntry.resources)
        aEntry.change { it.copy(query = "private-to-a") }
        assertEquals("", bEntry.state.value.query)
        val training = aRoot.session.training!!
        training.add(TrainingWorkspace.products.first().id)
        a.signOut()
        assertTrue(aEntry.scopeClosed)
        assertTrue(aRoot.koinScope.closed)
        assertEquals("", aEntry.state.value.query)
        assertTrue(training.closed)
        assertEquals(0, training.basket.value.count)
        assertFalse(bEntry.scopeClosed)
        assertFalse(bRoot.koinScope.closed)
        a.close()
        b.close()
        assertTrue(bEntry.scopeClosed)
    }

    @Test fun runtimeLanguageSwitchKeepsTheSameSessionEntryAndExactBasket() {
        val runtime = UkkeraRuntime(AppDependencies(platform = platform))
        runtime.openTraining(company = false, runtime.navigator.token())
        val before = runtime.navigator.state.value
        val training = runtime.rootScope.session.training!!
        training.add(TrainingWorkspace.products.first().id)
        val total = training.basket.value.total
        runtime.changeLanguage(AppLanguage.ARABIC)
        assertEquals(before.rootGeneration, runtime.navigator.state.value.rootGeneration)
        assertEquals(before.activeEntry, runtime.navigator.state.value.activeEntry)
        assertEquals(total, training.basket.value.total)
        runtime.changeLanguage(AppLanguage.ENGLISH)
        assertEquals(total, training.basket.value.total)
        runtime.close()
    }

    @Test fun closedTrainingCallbacksCannotClearTheNextWorkspaceDraftProtection() {
        val runtime = UkkeraRuntime(AppDependencies(platform = platform))
        runtime.openTraining(company = false, runtime.navigator.token())
        val previousTraining = runtime.rootScope.session.training!!
        previousTraining.add(TrainingWorkspace.products.first().id)
        runtime.signOut()
        runtime.openTraining(company = false, runtime.navigator.token())
        val currentTraining = runtime.rootScope.session.training!!
        currentTraining.add(TrainingWorkspace.products.first().id)
        assertTrue(runtime.navigator.state.value.rootHasDraft)
        previousTraining.clear()
        previousTraining.close()
        previousTraining.removeOne(TrainingWorkspace.products.first().id)
        assertFalse(previousTraining.add(TrainingWorkspace.products.first().id))
        assertTrue(runtime.navigator.state.value.rootHasDraft)
        assertEquals(1, currentTraining.basket.value.count)
        assertEquals(NavigationResult.CONFIRM_DISCARD,
            runtime.navigator.navigate(NavigationAction.CloseHost, runtime.navigator.token()))
        runtime.close()
    }

    @Test fun gatewayCleanupFailureStillHidesAllPrivateUi() = runTest {
        val session = TestBranchSession(3, failClose = true)
        val gateway = object : BranchUiGateway {
            override val configured = true
            override suspend fun signIn(account: String, secret: CharArray) = SignInResult.Authenticated(session)
        }
        val runtime = UkkeraRuntime(AppDependencies(branchGateway = gateway, platform = platform))
        try {
            runtime.openSignIn(company = false, runtime.navigator.token())
            runtime.signIn("a", "test".toCharArray(), runtime.navigator.token())
            runtime.signOut()
            assertEquals(SecurityRoot.BOOTSTRAP, runtime.navigator.state.value.root)
            assertEquals(null, runtime.presentation.value.display)
            assertTrue(runtime.presentation.value.cleanupNeedsAttention)
        } finally {
            // The fault remains active through every assertion. Resolution here only disposes
            // test-owned resources; it does not assert successful terminal cleanup or recovery.
            session.resolveCloseFailure()
            runtime.close()
        }
    }

    private fun entry(root: UiRootScope, id: Long): EntryViewModel {
        val provider = ViewModelProvider.create(root.viewModelStore,
            factory = viewModelFactory { initializer { root.koinScope.get<EntryViewModel> { parametersOf(id) } } })
        return provider["entry-$id", EntryViewModel::class]
    }

    private class TestBranchSession(number: Int, private var failClose: Boolean = false) : BranchUiSession {
        override val display = TrainingWorkspace.display(company = false).copy(
            actorId = DomainId("00000000-0000-4000-8000-${number.toString().padStart(12, '0')}"), actorName = "test actor $number")
        var closes = 0
        override fun isCurrent(): Boolean = closes == 0
        override suspend fun mayRestore(route: AppRoute) = true
        override fun close() { closes++; if (failClose) error("test cleanup failure") }
        fun resolveCloseFailure() { failClose = false }
    }
}
