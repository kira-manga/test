package com.ukkera.pos.app.independent.returnrightsauthoringv1

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.ukkera.pos.app.UnavailableLocalReturnRightsPublication
import com.ukkera.pos.app.ui.AppLanguage
import com.ukkera.pos.app.ui.LocalAppLanguage
import com.ukkera.pos.app.ui.LocalReturnRightsEntryDrafts
import com.ukkera.pos.app.ui.LocalReturnRightsPublicationCard
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** Actual unavailable-card semantics only; no configured native review, credential prompt or installed-platform evidence. */
class ReturnRightsAuthoringUnavailableRenderedTest {
    @get:Rule val compose = createComposeRule()
    private var fixture: ControlledReturnRightsAuthoringUi? = null

    @After
    fun closeRuntime() {
        fixture?.let { controlled -> compose.runOnUiThread { controlled.close() } }
    }

    @Test
    fun raa17UnconfiguredNativeAuthoringShowsUnavailableWithoutDraftOrHeadControlsInBothLanguages() {
        lateinit var controlled: ControlledReturnRightsAuthoringUi
        lateinit var drafts: LocalReturnRightsEntryDrafts
        compose.runOnUiThread {
            controlled = ControlledReturnRightsAuthoringUi().also { fixture = it }
            runBlocking { controlled.attach() }
            drafts = controlled.entry().returnRightsDrafts.apply { populateUnsigned() }
            assertSame(UnavailableLocalReturnRightsPublication, controlled.session.companyCatalog.returnRights)
        }
        val language = mutableStateOf(AppLanguage.ENGLISH)
        val callbacks = mutableListOf<String>()
        compose.setContent {
            val selected = language.value
            CompositionLocalProvider(LocalAppLanguage provides selected, LocalLayoutDirection provides selected.direction) {
                MaterialTheme {
                    Box(Modifier.size(900.dp, 720.dp)) {
                        LocalReturnRightsPublicationCard(controlled.runtime, controlled.runtime.navigator.token(), drafts,
                            parentReady = true, mayStartNew = true,
                            guardChanged = { _, _ -> callbacks += "guard" },
                            employeeAccessDenied = { callbacks += "denied" },
                            activationStaged = { callbacks += "staged" })
                    }
                }
            }
        }
        for (selected in listOf(AppLanguage.ENGLISH, AppLanguage.ARABIC)) {
            compose.runOnIdle { language.value = selected }
            // Bind the current semantic notice, not a frozen exact-copy or pixel oracle.
            val unavailable = if (selected == AppLanguage.ENGLISH) "Native return-rights authoring is unavailable."
                else "إنشاء سياسة حقوق المرتجعات الأصلية غير متاح."
            compose.onNodeWithText(unavailable, substring = true).assertIsDisplayed()
            compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).assertCountEquals(0)
            compose.onAllNodes(hasClickAction(), useUnmergedTree = true).assertCountEquals(0)
            val input = FrozenReturnRightsAuthoringUiInputs.draft()
            for (literal in listOf(input.legalSourceUri, input.continuingRights.english, input.continuingRights.arabic)) {
                compose.onAllNodesWithText(literal, substring = true, useUnmergedTree = true).assertCountEquals(0)
            }
            compose.runOnIdle {
                assertEquals(emptyList(), callbacks)
                controlled.session.ports.forEach { it.assertNoCommands() }
            }
        }
    }
}
