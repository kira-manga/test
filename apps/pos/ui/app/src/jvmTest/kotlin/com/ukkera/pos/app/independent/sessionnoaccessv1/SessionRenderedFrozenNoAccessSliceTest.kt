package com.ukkera.pos.app.independent.sessionnoaccessv1

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.ukkera.pos.app.UkkeraApp
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.app.navigation.SecurityRoot
import com.ukkera.pos.app.ui.Copy
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Frozen v1.0.0 supporting COMMON-UI slice: LSNA-01 steps 3/4, LSNA-04 step 1,
 * and only the automatic-dispatch portion of LSNA-01 step 5.
 *
 * The existing-session attachment and P10/P11 observations are controlled presentation
 * boundaries. Actual UkkeraApp, runtime, navigation and private text input are exercised.
 * No successful protected read, master activation, authentication admission, registry,
 * quantity-3 cart, report, durable business cut, RPC or installed input is qualified.
 * This checks displayed semantics; no independent pixel oracle or late-frame schedule is bound.
 * All six whole cases remain NOT_RUN. OM-01/OM-02 and all LSNA-05 prerequisites remain unbound.
 */
class SessionRenderedFrozenNoAccessSliceTest {
    @get:Rule val compose = createComposeRule()
    private var fixture: NoAccessRuntimeFixture? = null

    @After fun closeRuntime() {
        fixture?.let { controlled -> compose.runOnUiThread { controlled.close() } }
    }

    @Test
    fun lsna01Steps3To5_lsna04Step1_currentNoAreasRetiresRenderedPrivateSearch() {
        val controlled = NoAccessRuntimeFixture().also { fixture = it }
        val app = controlled.runtime
        app.openSignIn(company = false, app.navigator.token())
        compose.setContent { Box(Modifier.size(860.dp, 720.dp)) { UkkeraApp(app) } }

        // These resource selectors bind current native controls, not exact-copy test oracles.
        // The controlled reply attaches the already-valid P10 fixture; it proves no real login.
        compose.onNodeWithText(binding(Copy.ACCOUNT)).performTextInput(FrozenNoAccessUiInputs.EMPLOYEE_A_NAME)
        compose.onNodeWithText(binding(Copy.PASSWORD)).performTextInput("synthetic-ui-attachment")
        compose.onNodeWithText(binding(Copy.SIGN_IN)).performClick()
        val privateSearch = compose.onNodeWithText(binding(Copy.LOCAL_SEARCH)).assertIsDisplayed()
        privateSearch.performTextInput(FrozenNoAccessUiInputs.PRIVATE_SEARCH)
        privateSearch.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,
            AnnotatedString(FrozenNoAccessUiInputs.PRIVATE_SEARCH)))

        // Nav3 owns the rendered editor. Inspect its actual semantics without acquiring
        // another controller through the fixture's separate root ViewModel store.
        val before = compose.runOnIdle { callCut(controlled) }
        compose.runOnUiThread { controlled.session.publishCurrent(FrozenNoAccessUiInputs.p11Display()) }

        val sessionPane = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, binding(Copy.SESSION_ACCESS))
        compose.onNode(hasText(binding(Copy.SESSION_ACCESS)) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertIsDisplayed()
        compose.onNodeWithText(binding(Copy.SESSION_ACCESS_BODY)).assertIsDisplayed()
        for (action in listOf(Copy.RENEW_SESSION, Copy.SIGN_OUT)) {
            compose.onNode(hasText(binding(action)) and hasClickAction() and hasAnyAncestor(sessionPane))
                .assertIsDisplayed().assertIsEnabled()
        }
        // Controls are reachable only. No zero-grant renewal, remote revocation or recovery
        // success is supplied or inferred from these displayed controls.
        compose.onAllNodesWithText(FrozenNoAccessUiInputs.PRIVATE_SEARCH,
            substring = true, useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText(binding(Copy.LOCAL_SEARCH), useUnmergedTree = true).assertCountEquals(0)
        for (area in listOf(Copy.CASHIER, Copy.ORDERS, Copy.DRAWER, Copy.STOCK, Copy.REPORTS)) {
            compose.onAllNodesWithText(binding(area), useUnmergedTree = true).assertCountEquals(0)
        }

        compose.runOnIdle {
            val statusOracle = FrozenNoAccessUiInputs.LSNA01_S4
            val state = app.navigator.state.value
            val presentation = app.presentation.value
            assertEquals(SecurityRoot.BRANCH_WORKSPACE, state.root,
                statusOracle.message("permission loss is not rendered as logout"))
            assertEquals(setOf(AppArea.SESSION), state.stacks.keys,
                statusOracle.message("no operational navigation area remains"))
            assertEquals(AppRoute.SessionAccess, state.activeEntry.route,
                statusOracle.message("the displayed surface is session-only"))
            val display = assertNotNull(presentation.display, statusOracle.message("same employee remains attached"))
            assertEquals(FrozenNoAccessUiInputs.employeeA, display.actorId, statusOracle.message("employee A is retained"))
            assertTrue(display.allowedAreas.isEmpty(), statusOracle.message("empty grants have no UI fallback"))
            assertTrue(controlled.session.isCurrent(), statusOracle.message("the controlled existing session remains current"))
            assertFalse(presentation.locallyLocked, statusOracle.message("no authentication lock is invented"))
            assertNull(presentation.sessionProblem, statusOracle.message("no expiry/disable/logout problem is invented"))
            assertNull(presentation.signInFailure, statusOracle.message("no failed authentication is invented"))
            val after = callCut(controlled)
            assertEquals(before.workflowReads, after.workflowReads, statusOracle.message("no new protected workflow read"))
            assertEquals(before.pendingReads, after.pendingReads, statusOracle.message("no new pending-result read"))
            val dispatchOracle = FrozenNoAccessUiInputs.LSNA01_S5
            assertEquals(before.businessDispatches, after.businessDispatches,
                dispatchOracle.message("zero transition staging/submission dispatches"))
            assertEquals(before.resultLookups, after.resultLookups,
                dispatchOracle.message("zero transition original-result lookup dispatches"))
            assertEquals(before.acknowledgements, after.acknowledgements,
                dispatchOracle.message("zero transition acknowledgement dispatches"))
        }
    }

    private fun binding(key: Copy): String = runBlocking { getString(key.en) }

    private fun callCut(controlled: NoAccessRuntimeFixture) = ControlledCallCut(
        workflowReads = controlled.session.readCalls,
        pendingReads = controlled.session.pendingCalls,
        businessDispatches = controlled.session.businessDispatches,
        resultLookups = controlled.session.lookupCalls,
        acknowledgements = controlled.session.acknowledgementCalls,
    )
}

private data class ControlledCallCut(
    val workflowReads: Int,
    val pendingReads: Int,
    val businessDispatches: Int,
    val resultLookups: Int,
    val acknowledgements: Int,
)
