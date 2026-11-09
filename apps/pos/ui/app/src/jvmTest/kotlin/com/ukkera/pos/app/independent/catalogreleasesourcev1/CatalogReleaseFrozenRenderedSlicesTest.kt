package com.ukkera.pos.app.independent.catalogreleasesourcev1

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.ukkera.pos.app.ui.AppLanguage
import com.ukkera.pos.app.ui.CatalogReleaseEditor
import com.ukkera.pos.app.ui.LocalAppLanguage
import com.ukkera.pos.app.ui.LocalCatalogReleaseEntryDrafts
import com.ukkera.pos.app.ui.LocalCatalogReleasePublicationCard
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Actual shared card/editor semantics with controlled UI data, not native/Master/RPC or installed-platform evidence. */
class CatalogReleaseFrozenRenderedSlicesTest {
    @get:Rule val compose = createComposeRule()
    private val inputs = FrozenCatalogReleaseUiContentInputs
    private var fixture: ControlledCatalogReleaseCardUi? = null
    private lateinit var drafts: LocalCatalogReleaseEntryDrafts
    private val cardShown = mutableStateOf(true)
    private val world: ControlledCatalogReleaseCardUi get() = requireNotNull(fixture)

    @After
    fun closeRuntime() { fixture?.let { controlled -> compose.runOnUiThread { controlled.close() } } }

    @Test
    fun csr002CardOffersLiteralNamesAndAppendsTheExactOrderWithoutCommaExpansion() {
        show(inputs.editor(1).copy(observedRevision = null, previous = null, members = emptyList()))
        click("Load exact native release sequence and current member choices")
        for (name in listOf("Cotton bag, natural", "Black shirt / L", "Blue shirt / M")) {
            compose.onAllNodesWithText(name, substring = true, useUnmergedTree = true).assertCountEquals(1)
        }
        compose.onAllNodes(hasClickAction() and hasText("Append this EXACT member")).assertCountEquals(3)
        repeat(3) {
            compose.onAllNodes(hasClickAction() and hasText("Append this EXACT member")).onFirst()
                .performScrollTo().performClick()
        }
        compose.runOnIdle {
            val editor = assertNotNull(drafts.editor.value)
            assertEquals(Revision(1), editor.observedRevision)
            assertEquals(inputs.releaseReference(0), editor.previous)
            assertEquals(inputs.orderedMembers(1), editor.members)
            assertNull(editor.frozenDraft)
            assertEquals(emptyList(), world.releases.preparedDrafts.toList())
            assertNoNativeOrActivationCalls()
        }
        compose.onAllNodes(hasClickAction() and hasText("Already selected")).assertCountEquals(3)
        // Literal names are controlled UI inputs, not resolved genuine completed originals.
    }

    @Test
    fun csr002CardDisplaysEverySelectedPublicationDigestSkuAndProductCoordinateInDeclaredOrder() {
        show(inputs.editor(1, frozen = true))
        val members = inputs.orderedMembers(1)
        val texts = textNodes()
        // Current labels/regexes are selectors for wiring; expected IDs, scalars and order are frozen relations.
        val memberRows = texts.mapNotNull {
            Regex("Member ([0-9]+) — SKU ([0-9a-f-]{36}); revision ([0-9]+)").matchEntire(it)
        }
        assertEquals(listOf("1", "2", "3"), memberRows.map { it.groupValues[1] })
        assertEquals(members.map { it.skuId.value }, memberRows.map { it.groupValues[2] })
        assertEquals(members.map { it.skuRevision.value.toString() }, memberRows.map { it.groupValues[3] })
        assertEquals(members.map { it.publicationId.value },
            texts.filter { it.startsWith("SKU publication: ") }.map { it.substringAfter("SKU publication: ") })
        assertEquals(members.map { it.contentDigest.value },
            texts.filter { it.startsWith("Exact SKU canonical-content SHA-256: ") }
                .map { it.substringAfter("Exact SKU canonical-content SHA-256: ") })
        val productRows = texts.mapNotNull {
            Regex("Product: ([0-9a-f-]{36}); independent revision: ([0-9]+)").matchEntire(it)
        }
        assertEquals(members.map { it.productId.value }, productRows.map { it.groupValues[1] })
        assertEquals(members.map { it.productRevision.value.toString() }, productRows.map { it.groupValues[2] })
        compose.runOnIdle {
            assertEquals(inputs.draft(1), assertNotNull(drafts.editor.value).toDraft())
            assertNoNativeOrActivationCalls()
        }
        // This is the selected-editor renderer. Private native review/completed preview rendering is unbound.
    }

    @Test
    fun csr007UnavailablePrepareAndReferencesOnlyRefreshRetryTheSameFrozenDraft() {
        show(inputs.editor(1, frozen = true))
        click("Retry the SAME exact proposal / recover review")
        compose.runOnIdle {
            assertEquals(listOf(inputs.draft(1)), world.releases.preparedDrafts.toList())
        }
        click("Refresh current access / retained release originals")
        click("Retry the SAME exact proposal / recover review")
        compose.runOnIdle {
            assertEquals(listOf(inputs.draft(1), inputs.draft(1)), world.releases.preparedDrafts.toList())
            assertTrue(world.releases.statusCalls.isNotEmpty())
            assertTrue(world.releases.statusCalls.all { it == null })
            assertEquals(inputs.draft(1), assertNotNull(drafts.editor.value).toDraft())
            assertNull(drafts.retainedActionId.value)
            assertNoNativeOrActivationCalls()
        }
        // References-only refresh supplies no source/mayPrepare authority or newer source choices.
        // Fresh native observation/approval/stale-predecessor decisions remain unbound.
    }

    @Test
    fun csr008CancelledPreparationAndCardReopenKeepOriginalDraftWithoutManufacturingCompletion() {
        show(inputs.editor(1, frozen = true))
        compose.runOnIdle { world.releases.prepareGate = CompletableDeferred() }
        click("Retry the SAME exact proposal / recover review")
        compose.waitUntil(timeoutMillis = 5_000) { world.releases.preparedDrafts.size == 1 }
        compose.runOnIdle { cardShown.value = false }
        compose.waitUntil(timeoutMillis = 5_000) { world.releases.cancelledPreparations.get() == 1 }
        compose.runOnIdle {
            assertEquals(inputs.draft(1), assertNotNull(drafts.editor.value).toDraft())
            assertNull(drafts.retainedActionId.value)
            world.releases.prepareGate = null
            cardShown.value = true
        }
        click("Retry the SAME exact proposal / recover review")
        compose.runOnIdle {
            assertEquals(listOf(inputs.draft(1), inputs.draft(1)), world.releases.preparedDrafts.toList())
            assertEquals(inputs.draft(1), assertNotNull(drafts.editor.value).toDraft())
            assertNull(drafts.retainedActionId.value)
            assertNoNativeOrActivationCalls()
        }
        // Cancellation of preparation is not a head-signing attempt or evidence of noncompletion.
    }

    private fun show(editor: CatalogReleaseEditor) {
        compose.runOnUiThread {
            fixture = ControlledCatalogReleaseCardUi()
            runBlocking { world.attach() }
            drafts = LocalCatalogReleaseEntryDrafts().apply { this.editor.value = editor }
        }
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.ENGLISH) {
                MaterialTheme {
                    Box(Modifier.size(1_280.dp, 900.dp)) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            if (cardShown.value) LocalCatalogReleasePublicationCard(world.runtime,
                                world.runtime.navigator.token(), drafts, parentReady = true, mayStartNew = true,
                                guardChanged = { _, _ -> }, employeeAccessDenied = {}, activationStaged = {})
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun click(selector: String) {
        // Existing English labels identify callable controls; exact UI copy is not a frozen oracle.
        compose.onNode(hasClickAction() and hasText(selector)).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun textNodes(): List<String> = compose.onAllNodes(
        SemanticsMatcher("rendered text data") { it.config.getOrNull(SemanticsProperties.Text) != null },
        useUnmergedTree = true).fetchSemanticsNodes().flatMap { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
    }

    private fun assertNoNativeOrActivationCalls() {
        assertEquals(0, world.releases.forbiddenNativeOrActivationCalls.get())
    }
}
