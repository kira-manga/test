package com.ukkera.pos.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.app.navigation.AppNavigator
import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.app.navigation.LeaveGuard
import com.ukkera.pos.app.navigation.NavigationAction
import com.ukkera.pos.app.navigation.SecurityRoot
import com.ukkera.pos.app.training.TrainingWorkspace
import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real desktop Compose/Nav3/Koin compositions. Run in the lead's Xvfb batch, not as AT evidence. */
class NativeShellUiTest {
    // v2 deliberately queues DEFAULT launches on StandardTestDispatcher.
    @get:Rule val compose = createComposeRule()
    private var runtime: UkkeraRuntime? = null
    private val platform = PlatformCapabilities(PlatformFamily.DESKTOP)

    @After fun closeRuntime() {
        runtime?.let { value -> compose.runOnUiThread { value.close() } }
    }

    @Test fun cachedCashierEntryObservesDraftAndGuardRevisionsBeforeReview() {
        val app = UkkeraRuntime(AppDependencies(platform = platform)).also { runtime = it }
        app.openTraining(company = false, app.navigator.token())
        compose.setContent { UkkeraApp(app) }
        val entryBefore = compose.runOnIdle { app.navigator.state.value.activeEntry }
        compose.onNodeWithContentDescription("Add Fresh milk").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertTrue(app.navigator.state.value.rootHasDraft)
            assertEquals(1, app.rootScope.session.training!!.basket.value.count)
            assertEquals(entryBefore, app.navigator.state.value.activeEntry)
            val beforePolicyChange = app.navigator.token()
            // No basket/presentation/backstack change: only the policy revision changes.
            app.navigator.registerGuard(entryBefore.id, LeaveGuard.CLEAN)
            assertFalse(app.navigator.isCurrent(beforePolicyChange))
        }
        compose.onNodeWithText("Review training total").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(AppRoute.QuotePreview, app.navigator.state.value.activeEntry.route) }
        compose.onNodeWithText("Review, before you collect").assertIsDisplayed()
    }

    @Test fun rapidSignInKeepsSingleFlightAndTheNewRootOwnsSuspendedRestore() {
        val display = TrainingWorkspace.display(company = false)
        val order = AppRoute.Order(DomainId("00000000-0000-4000-8000-000000000042"))
        val source = AppNavigator().apply {
            replaceRoot(SecurityRoot.BRANCH_WORKSPACE, display)
            navigate(NavigationAction.SelectArea(AppArea.ORDERS), token())
            navigate(NavigationAction.Push(order), token())
        }
        val signInReply = CompletableDeferred<Unit>()
        val restoreEntered = CompletableDeferred<Unit>()
        val restoreReply = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val reads = AtomicInteger()
        val session = object : BranchUiSession {
            private var active = true
            override val display = TrainingWorkspace.display(company = false)
            override fun isCurrent(): Boolean = active
            override suspend fun mayRestore(route: AppRoute): Boolean {
                reads.incrementAndGet()
                restoreEntered.complete(Unit)
                restoreReply.await()
                return true
            }
            override fun close() { active = false }
        }
        val gateway = object : BranchUiGateway {
            override val configured = true
            override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> {
                calls.incrementAndGet()
                signInReply.await()
                return SignInResult.Authenticated(session)
            }
        }
        val app = UkkeraRuntime(AppDependencies(branchGateway = gateway, platform = platform)).also { runtime = it }
        assertTrue(app.navigator.quarantineRestore(source.checkpoint()!!))
        app.openSignIn(company = false, app.navigator.token())
        compose.setContent { UkkeraApp(app) }
        compose.onNodeWithText("Account").performTextInput("synthetic-operator")
        compose.onNodeWithText("Password").performTextInput("synthetic-test-only")
        val click = compose.onNodeWithText("Sign in").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        // Two delivered actions in the same UI turn, before recomposition can disable the button.
        queuedUiTurn {
            assertFalse(app.presentation.value.signingIn)
            assertTrue(click())
            assertTrue(click())
            assertEquals(0, calls.get(), "The sign-in launches must still be queued inside the input turn")
            assertFalse(app.presentation.value.signingIn)
        }
        compose.runOnIdle {
            assertTrue(app.presentation.value.signingIn)
            assertEquals(1, calls.get())
        }
        queuedUiTurn {
            signInReply.complete(Unit)
            assertEquals(SecurityRoot.BRANCH_SIGN_IN, app.navigator.state.value.root,
                "Completing the gateway must queue, not inline, the sign-in continuation")
            assertFalse(restoreEntered.isCompleted)
        }
        // Draining the queued frame also disposes the old sign-in entry and its remembered scope.
        compose.onNodeWithText("Sign in").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(restoreEntered.isCompleted)
            assertEquals(SecurityRoot.BRANCH_WORKSPACE, app.navigator.state.value.root)
            assertEquals(AppRoute.Cashier, app.navigator.state.value.activeEntry.route)
            assertEquals(1, reads.get())
        }
        queuedUiTurn {
            restoreReply.complete(Unit)
            assertEquals(AppRoute.Cashier, app.navigator.state.value.activeEntry.route,
                "Route installation must wait for the root-owned queued continuation")
        }
        compose.runOnIdle {
            assertEquals(order, app.navigator.state.value.activeEntry.route)
            assertTrue(reads.get() > 1)
            assertFalse(app.presentation.value.training)
            assertFalse(app.presentation.value.signingIn)
        }
    }

    /** Proves pre-drain ordering before explicitly advancing the queued effects and composition. */
    private fun queuedUiTurn(action: () -> Unit) {
        val previousAutoAdvance = compose.mainClock.autoAdvance
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread(action)
            compose.mainClock.advanceTimeByFrame()
        } finally {
            compose.mainClock.autoAdvance = previousAutoAdvance
        }
        compose.waitForIdle()
    }
}
