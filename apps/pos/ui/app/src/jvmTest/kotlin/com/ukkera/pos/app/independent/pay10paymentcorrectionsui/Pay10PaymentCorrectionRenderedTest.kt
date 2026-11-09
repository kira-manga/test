package com.ukkera.pos.app.independent.pay10paymentcorrectionsui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.ukkera.pos.app.*
import com.ukkera.pos.app.ui.AppLanguage
import com.ukkera.pos.app.ui.Copy
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Rendered supporting PAY-10 slices with controlled authentication/workflow replies only. */
class Pay10PaymentCorrectionRenderedTest {
    @get:Rule val compose = createComposeRule()
    private var runtime: UkkeraRuntime? = null

    @After fun closeRuntime() { runtime?.let { app -> compose.runOnUiThread { app.close() } } }

    @Test fun pcui01And03EnglishAndArabicEmptyEntryShowExactLookupWithoutCheckoutFallback() {
        val (app, port) = render()
        compose.onNodeWithText("Historical payment classification").assertIsDisplayed()
        scrollTo(Pay10UiInputs.emptyPrompt)
        assertTrue(compose.onAllNodesWithText(Pay10UiInputs.emptyPrompt).fetchSemanticsNodes().isNotEmpty())
        for (key in listOf(Copy.NO_RECORDS, Copy.DRAWER, Copy.STOCK, Copy.REPORTS,
            Copy.RECEIVING, Copy.KITCHEN_WORKFLOW, Copy.FULFILLMENT, Copy.EXCEPTIONS)) {
            compose.onAllNodesWithText(binding(key), useUnmergedTree = true).assertCountEquals(0)
        }
        compose.runOnIdle {
            assertEquals(LocalWorkflowQuery(LocalWorkflow.CHECKOUT), port.reads.single())
            assertEquals(0, port.stages.size)
            assertEquals(0, port.submissions.size)
            app.changeLanguage(AppLanguage.ARABIC)
        }
        scrollTo("تصنيف المدفوعات التاريخية")
        compose.onNodeWithText("تصنيف المدفوعات التاريخية").assertIsDisplayed()
        val arabicPrompt = "أدخل payment-correction:SALE_UUID واختر تحديث لقراءة الدفع الأصلي والسجل المرتبط."
        scrollTo(arabicPrompt)
        assertTrue(compose.onAllNodesWithText(arabicPrompt).fetchSemanticsNodes().isNotEmpty())
        compose.runOnIdle {
            assertEquals(1, port.reads.size)
            assertEquals(0, port.stages.size)
            assertEquals(0, port.submissions.size)
        }
    }

    @Test fun pcui04And08LookupDisplaysOriginalCloseAndSeparateZeroCashHistoricalCorrection() {
        val (_, port) = render(includeCorrection = true)
        compose.onNodeWithText(binding(Copy.LOCAL_SEARCH)).performTextInput(Pay10UiInputs.search)
        compose.onNodeWithText(binding(Copy.REFRESH)).performClick()
        scrollTo("Original paid payment")
        compose.onNodeWithText("Original paid payment").performClick()
        scrollTo("Original paid payment")
        compose.onNode(hasText("Original paid payment") and hasText(isolated("10000"))).assertIsDisplayed()
        scrollTo("Original submitted close — immutable")
        compose.onNode(hasText("Original submitted close — immutable") and hasText(isolated("100000")) and
            hasText(isolated("110000")) and hasText(isolated("+10000"))).assertIsDisplayed()
        scrollTo("Linked historical tender correction")
        compose.onNode(hasText("Linked historical tender correction") and hasText(isolated("-10000")) and
            hasText(isolated("+10000")) and hasText(isolated("0"))).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(LocalWorkflowQuery(LocalWorkflow.CHECKOUT, reference = Pay10UiInputs.history), port.reads.last())
            assertTrue(port.reads.none { it.reference != null && it.search.isNotEmpty() })
            assertEquals(0, port.stages.size)
            assertEquals(0, port.submissions.size)
            assertEquals(0, port.lookups.size)
        }
    }

    private fun render(includeCorrection: Boolean = false): Pair<UkkeraRuntime, Pay10UiWorkflowPort> {
        val port = Pay10UiWorkflowPort().apply { this.includeCorrection = includeCorrection; offerForm = false }
        val session = Pay10UiSession(port)
        val app = UkkeraRuntime(AppDependencies(branchGateway = Pay10UiLoginPort(session),
            platform = PlatformCapabilities(PlatformFamily.DESKTOP))).also { runtime = it }
        app.openSignIn(false, app.navigator.token())
        compose.setContent { Box(Modifier.size(1080.dp, 1600.dp)) { UkkeraApp(app) } }
        compose.onNodeWithText(binding(Copy.ACCOUNT)).performTextInput("synthetic-manager")
        compose.onNodeWithText(binding(Copy.PASSWORD)).performTextInput("synthetic-ui-input")
        compose.onNodeWithText(binding(Copy.SIGN_IN)).performClick()
        compose.onNodeWithText("Historical payment classification").assertIsDisplayed()
        return app to port
    }

    private fun scrollTo(title: String) {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToNode(hasText(title))
    }
    private fun isolated(value: String) = "\u2066$value\u2069"
    private fun binding(key: Copy) = runBlocking { getString(key.en) }
}
