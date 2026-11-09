package com.ukkera.pos.app.independent.catalogunitauthoringv1

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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.ukkera.pos.app.ui.AppLanguage
import com.ukkera.pos.app.ui.CompanySkuEditor
import com.ukkera.pos.app.ui.LocalAppLanguage
import com.ukkera.pos.app.ui.LocalCompanyCatalogEntryDrafts
import com.ukkera.pos.app.ui.LocalCompanyCatalogPublicationCard
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The actual shared card, editor and preview renderer with controlled public DATA
 * responses. These are not native approval, durable-journal, Master, RPC or OS runs.
 */
class CatalogUnitAuthoringRenderedSlicesTest {
    @get:Rule val compose = createComposeRule()
    private val f = CatalogUnitAuthoringFrozenInputs
    private var fixture: ControlledCatalogUnitAuthoringUi? = null
    private lateinit var drafts: LocalCompanyCatalogEntryDrafts
    private val selectedLanguage = mutableStateOf(AppLanguage.ENGLISH)
    private val cardShown = mutableStateOf(true)
    private val world: ControlledCatalogUnitAuthoringUi get() = requireNotNull(fixture)
    private val labels: Labels get() = Labels(selectedLanguage.value)

    @After fun closeRuntime() { fixture?.let { compose.runOnUiThread { it.close() } } }

    @Test fun cuae007_englishPackEntryRequestsExactContentWithoutImplicitCompletion() = enterPack(AppLanguage.ENGLISH)
    @Test fun cuae007_arabicPackEntryRequestsExactContentWithoutImplicitCompletion() = enterPack(AppLanguage.ARABIC)

    @Test fun cuae013_englishFullPackPreviewKeepsEveryIdentityAndBasePrice() = fullPackPreview(AppLanguage.ENGLISH)
    @Test fun cuae013_arabicFullPackPreviewKeepsEveryIdentityAndBasePrice() = fullPackPreview(AppLanguage.ARABIC)

    @Test fun cuae004_uppercaseBaseIdCannotRetainOrFreezeAndCorrectionRestoresTheExactDraft() {
        val row = f.rows("invalid_base_variants").single { it.text("variant_id") == "id-uppercase" }
        val message = f.variant("CUAE-004", row.text("variant_id"))
        show(f.editor().copy(baseUnitIdentity = row.text("value")))
        click(labels.retain)
        compose.runOnIdle {
            assertEquals(emptyList(), world.catalog.preparations, message)
            assertFalse(assertNotNull(drafts.editor.value, message).frozen, message)
        }
        noStageAction()
        replace(labels.baseIdentity, f.base.text("id"))
        click(labels.retain)
        compose.runOnIdle {
            assertEquals(listOf(f.draft()), world.catalog.preparations, message)
            assertTrue(assertNotNull(drafts.editor.value, message).frozen, message)
            assertNoEffects()
        }
    }

    @Test fun cuae011_uppercaseConversionIdCannotDiscardThePackOrRetainAnInvalidOriginal() {
        val row = f.rows("incomplete_pack_variants").single { it.text("variant_id") == "conversion-id-uppercase" }
        val message = f.variant("CUAE-011", row.text("variant_id"))
        show(f.editor(true).copy(conversionIdentity = row.text("value")))
        click(labels.retain)
        compose.runOnIdle {
            assertEquals(emptyList(), world.catalog.preparations, message)
            val editor = assertNotNull(drafts.editor.value, message)
            assertFalse(editor.frozen, message)
            assertTrue(editor.packEnabled, message)
        }
        noStageAction()
        replace(labels.conversionIdentity, f.pack.obj("conversion_input").text("id"))
        click(labels.retain)
        compose.runOnIdle {
            assertEquals(listOf(f.draft(true)), world.catalog.preparations, message)
            assertNoEffects()
        }
    }

    @Test fun cuae010_allFrozenInvalidRatioInputsDisableRenderedPreparationWithoutACompletedSubstitute() {
        show(f.editor(true))
        for (row in f.rows("invalid_ratio_variants")) {
            val message = f.variant("CUAE-010", row.text("variant_id"))
            compose.runOnIdle {
                // Bind the exact invalid editor input; this is not a hardware paste/keyboard test.
                drafts.editor.value = f.editor(true).copy(conversionNumerator = row.optionalText("numerator_text").orEmpty(),
                    conversionDenominator = row.optionalText("denominator_text").orEmpty())
            }
            visible(hasText(labels.retain) and hasClickAction()).assertIsNotEnabled()
            noStageAction()
            compose.runOnIdle {
                assertEquals(emptyList(), world.catalog.preparations, message)
                assertFalse(assertNotNull(drafts.editor.value, message).frozen, message)
                assertNoEffects()
            }
        }
    }

    @Test fun cuae016_reopenedControlledEditorUsesObservedRetainedPackValuesNotChangedCurrentInputs() {
        val message = f.variant("CUAE-016", "extended-recovery")
        show(f.editor(true).copy(frozen = true, baseUnitIdentity = f.id("alternate_unit").value,
            packUnitCode = f.rows("retained_unit_mutations").single { it.text("variant_id") == "source-code" }
                .obj("value").text("original"), conversionNumerator = "10", conversionDenominator = "1"),
            completedProjection = true)
        compose.runOnIdle { cardShown.value = false }
        compose.runOnIdle { cardShown.value = true }
        click(labels.readSame)
        assertRenderedPack()
        compose.runOnIdle {
            val projection = assertNotNull(world.catalog.completedProjection, message)
            f.assertPackPreview(projection.preview, message)
            assertEquals(listOf(world.catalog.originalId), world.catalog.originalReads, message)
            assertEquals(world.catalog.originalId, drafts.retainedActionId.value, message)
            assertEquals(null, drafts.editor.value, message)
            assertEquals(emptyList(), world.catalog.preparations, message)
            assertNoEffects()
        }
        // This is card disposal/reopen and a controlled original-coordinate read, not process restart,
        // a genuine O-PACK, a persisted staged body or an exact legacy O-LEGACY recovery.
    }

    @Test fun cuae017_observedCompletedProjectionDoesNotImplicitlyStageOrSubmit() {
        val message = f.variant("CUAE-017", "head-completion-only-controlled")
        show(completedProjection = true)
        click(labels.readSame)
        assertRenderedPack()
        compose.runOnIdle {
            assertEquals(emptyList(), world.catalog.preparations, message)
            assertEquals(0, world.catalog.approvalCalls, message)
            assertNoEffects()
        }
        // Only the public completed projection was supplied. No head ceremony or native completion is proved.
    }

    @Test fun cuae017_explicitControlledStageRoutesOnlyTheObservedOriginalCoordinate() {
        val message = f.variant("CUAE-017", "stage-only-controlled")
        show(completedProjection = true)
        val original = assertNotNull(world.catalog.completedProjection, message)
        click(labels.readSame)
        compose.runOnIdle { assertNoEffects() }
        click(labels.stage)
        compose.runOnIdle {
            assertEquals(listOf(original.originalActionId), world.catalog.stageRequests, message)
            assertSame(original, world.catalog.completedProjection, message)
            f.assertPackPreview(original.preview, message)
            assertEquals(0, world.catalog.approvalCalls, message)
            assertEquals(emptyList(), world.catalog.preparations, message)
            world.assertNoBusinessCommands()
        }
        // The controlled response is DATA only. No durable stage content, signature, native
        // original or authoritative business read is supplied by this test.
    }

    @Test fun cuae030_pendingPreparationDoesNotExposeCompletedOriginalStaging() {
        val message = f.variant("CUAE-030", "prepared-pending-proposal")
        show(f.editor(true))
        click(labels.retain)
        noStageAction()
        compose.runOnIdle {
            assertEquals(listOf(f.draft(true)), world.catalog.preparations, message)
            assertEquals(null, world.catalog.completedProjection, message)
            assertTrue(assertNotNull(drafts.editor.value, message).frozen, message)
            assertNoEffects()
        }
    }

    @Test fun cuae030_editorAssembledExactLookingUnitsDoNotExposeCompletedOriginalStaging() {
        val message = f.variant("CUAE-030", "editor-assembled-non-native-content")
        show(f.editor(true))
        noStageAction()
        compose.runOnIdle {
            val entered = assertNotNull(assertNotNull(drafts.editor.value, message).toDraft(), message)
            f.assertUnits(entered.unitDefinitions, f.pack.array("expected_unit_definitions"), message)
            f.assertPackConversion(entered.conversions.single(), message)
            assertEquals(null, world.catalog.completedProjection, message)
            assertEquals(emptyList(), world.catalog.preparations, message)
            assertNoEffects()
        }
        // The UI's lack of a stage action is not the direct native/journal rejection of an unsigned substitute.
    }

    private fun enterPack(language: AppLanguage) {
        val message = f.variant("CUAE-007", "exact-count-pack-24-over-2")
        f.variant("CUAE-017", "prepare-only-controlled")
        show(f.editor(true).copy(baseUnitCode = "", packUnitCode = "", unitDimension = null,
            baseMaximumScale = null, packMaximumScale = null, conversionNumerator = "", conversionDenominator = ""), language)
        replace(labels.baseCode, f.base.text("code_text"))
        click(labels.count)
        clickNumber("0", 0) // Base precision, from the frozen F-PACK input.
        replace(labels.sourceCode, f.pack.obj("explicit_source_input").text("code_text"))
        clickNumber("0", 1) // Independent source precision.
        replace(labels.numerator, f.pack.obj("conversion_input").text("numerator_text"))
        replace(labels.denominator, f.pack.obj("conversion_input").text("denominator_text"))
        click(labels.retain)
        noStageAction()
        compose.runOnIdle {
            assertEquals(listOf(f.draft(true)), world.catalog.preparations, message)
            val captured = world.catalog.preparations.single()
            f.assertUnits(captured.unitDefinitions, f.pack.array("expected_unit_definitions"), message)
            f.assertPackConversion(captured.conversions.single(), message)
            assertTrue(assertNotNull(drafts.editor.value, message).frozen, message)
            assertEquals(null, world.catalog.completedProjection, message)
            assertNoEffects()
        }
    }

    private fun fullPackPreview(language: AppLanguage) {
        val message = f.variant("CUAE-013", if (language == AppLanguage.ENGLISH) "english-full-review" else "arabic-full-review")
        show(language = language, completedProjection = true)
        click(labels.readSame)
        assertRenderedPack()
        compose.runOnIdle {
            f.assertPackPreview(assertNotNull(world.catalog.completedProjection, message).preview, message)
            assertNoEffects()
        }
    }

    private fun assertRenderedPack() {
        val expected = f.fixtures.obj("review_pack_expected")
        for (unit in f.units(true)) visible(exactLiteral("${unit.id.value} / ${unit.code.original}"))
        val conversion = expected.obj("conversion_review")
        visible(exactLiteral("${conversion.text("id")} / ${conversion.text("revision")}"))
        visible(exactLiteral("${conversion.text("sourceUnitId")} → ${conversion.text("targetUnitId")}"))
        visible(hasText(labels.rate(conversion.text("reducedRate"))))
        compose.onAllNodes(hasText(labels.dimensionPrecision), useUnmergedTree = true).assertCountEquals(2)
        visible(hasText(labels.price(f.price.text("decimal")), substring = true))
        visible(hasText(labels.skuPrecision(expected.int("skuQuantityScale")), substring = true))
        visible(exactLiteral(f.id("base_unit").value))
        visible(exactLiteral("${f.id("sku").value} / ${f.id("product").value}"))
    }

    private fun show(editor: CompanySkuEditor? = null, language: AppLanguage = AppLanguage.ENGLISH,
        completedProjection: Boolean = false) {
        compose.runOnUiThread {
            fixture = ControlledCatalogUnitAuthoringUi()
            runBlocking { world.attach() }
            drafts = LocalCompanyCatalogEntryDrafts().apply {
                this.editor.value = editor
                if (completedProjection) {
                    world.catalog.observeCompletedProjection()
                    retainedActionId.value = world.catalog.originalId
                }
            }
            selectedLanguage.value = language
        }
        compose.setContent {
            val current = selectedLanguage.value
            CompositionLocalProvider(LocalAppLanguage provides current, LocalLayoutDirection provides current.direction) {
                MaterialTheme {
                    Box(Modifier.size(900.dp, 720.dp)) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            if (cardShown.value) LocalCompanyCatalogPublicationCard(world.runtime,
                                world.runtime.navigator.token(), drafts, parentReady = true, mayStartNew = true,
                                guardChanged = { _, _ -> }, employeeAccessDenied = {}, activationStaged = {})
                        }
                    }
                }
            }
        }
    }

    private fun assertNoEffects() {
        assertEquals(emptyList(), world.catalog.stageRequests)
        assertEquals(0, world.catalog.approvalCalls)
        world.assertNoBusinessCommands()
    }
    private fun noStageAction() = compose.onAllNodes(hasText(labels.stage) and hasClickAction()).assertCountEquals(0)
    private fun click(text: String) { visible(hasText(text) and hasClickAction()).performClick() }
    private fun clickNumber(text: String, index: Int) {
        compose.onAllNodes(hasText(text) and hasClickAction())[index].performScrollTo().assertIsDisplayed().performClick()
    }
    private fun replace(label: String, value: String) {
        visible(hasText(label) and hasSetTextAction()).performTextReplacement(value)
    }
    private fun visible(matcher: SemanticsMatcher): SemanticsNodeInteraction =
        compose.onAllNodes(matcher).onFirst().performScrollTo().assertIsDisplayed()

    /** Ignore only display-direction isolation; literal spaces/code/UUID/rate values remain exact. */
    private fun exactLiteral(expected: String): SemanticsMatcher = SemanticsMatcher("full exact value: $expected") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any {
            it.text.removePrefix("\u2066").removeSuffix("\u2069") == expected
        } == true
    }

    /** Current labels are locators, not independently frozen wording oracles. */
    private class Labels(private val language: AppLanguage) {
        private fun text(english: String, arabic: String) = if (language == AppLanguage.ENGLISH) english else arabic
        val retain get() = text("Retain exact proposal and review — NOT sign", "حفظ الاقتراح الدقيق ومراجعته — دون توقيع")
        val readSame get() = text("Read SAME original publication before continuing", "قراءة الإصدار الأصلي نفسه قبل المتابعة")
        val stage get() = text("Stage branch activation — no signing or stock", "حفظ تفعيل الفرع — دون توقيع أو مخزون")
        val baseIdentity get() = text("Exact base-unit identity (lowercase UUID)", "معرف الوحدة الأساسية الدقيق — UUID صغير الأحرف")
        val baseCode get() = text("Exact base-unit code", "رمز الوحدة الأساسية الدقيق")
        val sourceCode get() = text("Exact source-unit code", "رمز وحدة المصدر الدقيق")
        val conversionIdentity get() = text("Exact conversion identity — new version 0", "معرف التحويل الدقيق — مراجعة جديدة 0")
        val numerator get() = text("Base units per source unit: positive integer numerator", "وحدات الأساس لكل وحدة مصدر: بسط صحيح موجب")
        val denominator get() = text("Positive integer denominator", "مقام صحيح موجب")
        val count get() = text("Count", "العدد")
        val dimensionPrecision get() = text("Dimension: Count; maximum precision: 0.", "البُعد: العدد؛ أقصى دقة: 0.")
        fun rate(value: String) = text("Exact base units per source unit: $value.", "وحدات الأساس الدقيقة لكل وحدة مصدر: $value.")
        fun price(value: String) = text("EGP $value per base unit.", "$value جنيه لكل وحدة أساسية.")
        fun skuPrecision(value: Int) = text("; precision $value;", "؛ الدقة $value؛")
    }
}
