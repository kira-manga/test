package com.ukkera.pos.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.app.navigation.SecurityRoot
import com.ukkera.pos.app.training.TrainingWorkspace
import com.ukkera.pos.app.ui.AppLanguage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Rendered desktop regressions, not physical phone, scanner, or assistive-technology qualification. */
class AccessibilityInputUiTest {
    @get:Rule val compose = createComposeRule()
    private var runtime: UkkeraRuntime? = null

    @After fun closeRuntime() {
        runtime?.let { app -> compose.runOnUiThread { app.close() } }
    }

    @Test fun compactShortcutRestoresDisposedSearchAndEscapeKeepsDraftPolicy() {
        val app = showTraining()
        val last = TrainingWorkspace.products.last()

        // The field must genuinely leave the lazy composition. Pre-scrolling back to it would
        // conceal an uninitialized FocusRequester rather than test the actual shortcut path.
        for (modifierKey in listOf(Key.CtrlLeft, Key.MetaLeft)) {
            verticalList().performScrollToKey(last.id.value)
            val product = compose.onNodeWithContentDescription("Add Linen shirt")
            product.assertIsDisplayed().requestFocus().assertIsFocused()
            compose.onNodeWithText(SEARCH_EN).assertDoesNotExist()
            product.performKeyInput { withKeyDown(modifierKey) { pressKey(Key.K) } }
            compose.onNodeWithText(SEARCH_EN).assertIsDisplayed().assertIsFocused()
        }

        val search = compose.onNodeWithText(SEARCH_EN)
        search.performTextInput("000000000001")
        search.performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle {
            assertEquals(1, app.rootScope.session.training!!.basket.value.count)
            assertTrue(app.navigator.state.value.rootHasDraft)
        }
        search.performKeyInput {
            keyDown(Key.Escape)
            advanceEventTime(1_200)
            keyUp(Key.Escape)
        }
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        compose.onNodeWithText("Leave this draft?").assertIsDisplayed()
        compose.runOnIdle {
            assertNotNull(app.navigator.state.value.confirmation)
            assertEquals(SecurityRoot.BRANCH_WORKSPACE, app.navigator.state.value.root)
            assertEquals(1, app.rootScope.session.training!!.basket.value.count)
        }
        compose.onNodeWithText("Keep working").performClick()
        // Touch exit is governed by the same real root draft, not a keyboard-only bypass.
        touchVisible(compose.onNodeWithContentDescription("Exit training"))
        compose.onNodeWithText("Leave this draft?").assertIsDisplayed()
        compose.onNodeWithText("Keep working").performClick()
        compose.runOnIdle {
            assertEquals(null, app.navigator.state.value.confirmation)
            assertEquals(AppRoute.Cashier, app.navigator.state.value.activeEntry.route)
            assertTrue(app.navigator.state.value.rootHasDraft)
            assertEquals(1, app.rootScope.session.training!!.basket.value.count)
        }
    }

    @Test fun narrowEnlargedTextKeepsAddAndReviewReachableInBothLanguages() {
        val app = showTraining(width = 390.dp, fontScale = 2f)
        for (language in listOf(AppLanguage.ENGLISH, AppLanguage.ARABIC)) {
            val arabic = language == AppLanguage.ARABIC
            val addLabel = if (arabic) "إضافة حليب طازج" else "Add Fresh milk"
            verticalList().performScrollToNode(hasContentDescription(addLabel))
            touchVisible(compose.onNodeWithContentDescription(addLabel))
            val count = if (arabic) 2 else 1
            compose.runOnIdle {
                assertEquals(language, app.presentation.value.language)
                assertEquals(count, app.rootScope.session.training!!.basket.value.count)
                assertEquals(4_250L * count, app.rootScope.session.training!!.basket.value.total.minor)
            }

            val review = compose.onNodeWithText(if (arabic) REVIEW_AR else REVIEW_EN)
            review.assertIsEnabled()
            assertEntireTargetInsideViewport(review)
            touchVisible(review)
            compose.runOnIdle { assertEquals(AppRoute.QuotePreview, app.navigator.state.value.activeEntry.route) }
            verticalList().performScrollToNode(hasText(if (arabic) "الإجمالي" else "Total"))
            assertMoneyVisible(if (arabic) "\u2066٨٥٫٠٠\u2069 ج.م" else "\u206642.50\u2069 EGP")
            touchVisible(compose.onNodeWithContentDescription(if (arabic) "رجوع" else "Back"))
            compose.runOnIdle {
                assertEquals(AppRoute.Cashier, app.navigator.state.value.activeEntry.route)
                assertEquals(count, app.rootScope.session.training!!.basket.value.count)
            }
            if (!arabic) touchVisible(compose.onNodeWithContentDescription(LANGUAGE_EN))
        }
    }

    @Test fun scannerEnterRepeatsPreserveLeadingZerosFocusAndAnnouncedRejection() {
        val repeatDowns = AtomicInteger()
        val app = showTraining(language = AppLanguage.ARABIC, observeKey = { event ->
            if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                repeatDowns.incrementAndGet()
            }
        })
        val search = compose.onNodeWithText(SEARCH_AR)
        val scans = listOf("٠٠٠٠٠٠٠٠٠٠٠١" to Key.Enter, "۰۰۰۰۰۰۰۰۰۰۰۱" to Key.NumPadEnter)
        scans.forEachIndexed { index, (barcode, suffix) ->
            search.performTextInput(barcode)
            search.assertIsFocused().assertEditableText(barcode)
            repeatDowns.set(0)
            search.performKeyInput {
                keyDown(suffix)
                // The pinned InputDispatcher generates repeated keydowns during this interval.
                advanceEventTime(1_200)
                keyUp(suffix)
            }
            assertTrue(repeatDowns.get() > 1, "The test must deliver genuine repeats, not merely a single Enter")
            search.assertIsFocused().assertEditableText("")
            compose.runOnIdle {
                val basket = app.rootScope.session.training!!.basket.value
                assertEquals(index + 1, basket.count)
                assertEquals(1, basket.lines.size)
                assertEquals("000000000001", basket.lines.single().product.barcode)
                assertEquals(4_250L * (index + 1), basket.total.minor)
            }
        }

        search.performTextInput("٠٠٠٠٠٠٠٠٠٠٩٩")
        search.performKeyInput {
            keyDown(Key.Enter)
            advanceEventTime(1_200)
            keyUp(Key.Enter)
        }
        search.assertIsFocused().assertEditableText("٠٠٠٠٠٠٠٠٠٠٩٩")
        compose.onNodeWithText("لم يُضف شيء. استخدم باركودًا تجريبيًا معروفًا أو ابحث بالاسم؛ لا يمكن تجاوز المخزون بصنف مجهول.")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.runOnIdle { assertEquals(2, app.rootScope.session.training!!.basket.value.count) }
    }

    @Test fun runtimeRtlRoundTripRetainsQuoteBasketQueryAndExactAmount() {
        val app = showTraining()
        val cashierEntry = compose.runOnIdle { app.navigator.state.value.activeEntry }
        compose.onNodeWithContentDescription("Add Fresh milk").performClick()
        compose.onNodeWithText(SEARCH_EN).performTextInput("000000000001")
        compose.onNodeWithText(REVIEW_EN).performClick()
        val quoteEntry = compose.runOnIdle { app.navigator.state.value.activeEntry }
        val rootScope = compose.runOnIdle { app.rootScope }
        val generation = compose.runOnIdle { app.navigator.state.value.rootGeneration }
        assertEquals(AppRoute.QuotePreview, quoteEntry.route)

        listOf(AppLanguage.ENGLISH, AppLanguage.ARABIC, AppLanguage.ENGLISH).forEachIndexed { index, language ->
            val arabic = language == AppLanguage.ARABIC
            val back = compose.onNodeWithContentDescription(if (arabic) "رجوع" else "Back")
            val languageButton = compose.onNodeWithContentDescription(if (arabic) LANGUAGE_AR else LANGUAGE_EN)
            val backX = back.fetchSemanticsNode().boundsInRoot.center.x
            val languageX = languageButton.fetchSemanticsNode().boundsInRoot.center.x
            assertTrue(if (arabic) backX > languageX else backX < languageX,
                "The rendered header ordering must reverse, not only its translated labels")
            verticalList().performScrollToNode(hasText(if (arabic) "الإجمالي" else "Total"))
            assertMoneyVisible(if (arabic) "\u2066٤٢٫٥٠\u2069 ج.م" else "\u206642.50\u2069 EGP")
            compose.onNodeWithText(if (arabic) "الدفع الفعلي غير متاح" else "Live checkout unavailable")
                .assertIsNotEnabled()
            compose.runOnIdle {
                assertEquals(language, app.presentation.value.language)
                assertSame(rootScope, app.rootScope)
                assertEquals(generation, app.navigator.state.value.rootGeneration)
                assertEquals(quoteEntry, app.navigator.state.value.activeEntry)
                assertEquals(1, app.rootScope.session.training!!.basket.value.count)
                assertEquals(4_250L, app.rootScope.session.training!!.basket.value.total.minor)
            }
            if (index < 2) touchVisible(languageButton)
        }
        verticalList().performScrollToNode(hasText("Continue editing basket"))
        touchVisible(compose.onNodeWithText("Continue editing basket"))
        compose.runOnIdle {
            assertEquals(cashierEntry, app.navigator.state.value.activeEntry)
            assertEquals(generation, app.navigator.state.value.rootGeneration)
            assertTrue(app.navigator.state.value.rootHasDraft)
        }
        val search = compose.onNodeWithText(SEARCH_EN)
        search.assertEditableText("000000000001")
        search.performTextClearance()
        verticalList().performScrollToNode(hasContentDescription("Add Fresh milk"))
        touchVisible(compose.onNodeWithContentDescription("Add Fresh milk"))
        touchVisible(compose.onNodeWithText(REVIEW_EN))
        compose.runOnIdle {
            assertEquals(AppRoute.QuotePreview, app.navigator.state.value.activeEntry.route)
            assertEquals(2, app.rootScope.session.training!!.basket.value.count)
            assertEquals(8_500L, app.rootScope.session.training!!.basket.value.total.minor)
        }
    }

    private fun showTraining(
        width: Dp = 700.dp,
        fontScale: Float = 1f,
        language: AppLanguage = AppLanguage.ENGLISH,
        observeKey: (KeyEvent) -> Unit = {},
    ): UkkeraRuntime {
        val app = UkkeraRuntime(AppDependencies(platform = PlatformCapabilities(PlatformFamily.DESKTOP)))
            .also { runtime = it }
        app.openTraining(company = false, app.navigator.token())
        app.changeLanguage(language)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.size(width, 760.dp).testTag(VIEWPORT).onPreviewKeyEvent { event ->
                        observeKey(event)
                        false
                    }) { UkkeraApp(app) }
                }
            }
        }
        return app
    }

    private fun verticalList(): SemanticsNodeInteraction = compose.onNode(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and hasScrollToIndexAction(),
    )

    private fun SemanticsNodeInteraction.assertEditableText(value: String): SemanticsNodeInteraction =
        assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value)))

    /** Real pointer hit in the clipped visible target, not a semantics action on an offscreen node. */
    private fun touchVisible(target: SemanticsNodeInteraction) {
        target.assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        val visible = target.getBoundsInRoot()
        assertTrue(visible.right - visible.left >= 48.dp && visible.bottom - visible.top >= 48.dp,
            "At least a 48dp target must remain visibly reachable: $visible")
        val viewport = compose.onNodeWithTag(VIEWPORT)
        val targetPixels = target.fetchSemanticsNode().boundsInRoot
        val viewportPixels = viewport.fetchSemanticsNode().boundsInRoot
        assertTrue(viewportPixels.contains(targetPixels.center), "The actual pointer hit must be in the app viewport")
        viewport.performTouchInput { click(targetPixels.center - viewportPixels.topLeft) }
    }

    private fun assertEntireTargetInsideViewport(target: SemanticsNodeInteraction) {
        val viewport = compose.onNodeWithTag(VIEWPORT).getUnclippedBoundsInRoot()
        val bounds = target.getUnclippedBoundsInRoot()
        assertTrue(bounds.left >= viewport.left && bounds.top >= viewport.top &&
            bounds.right <= viewport.right && bounds.bottom <= viewport.bottom,
            "The complete review action must fit inside the app viewport: target=$bounds viewport=$viewport")
    }

    private fun assertMoneyVisible(literal: String) {
        val viewport = compose.onNodeWithTag(VIEWPORT).fetchSemanticsNode().boundsInRoot
        assertTrue(compose.onAllNodesWithText(literal).fetchSemanticsNodes().any { node ->
            val bounds = node.boundsInRoot
            bounds.width > 0 && bounds.height > 0 && viewport.contains(bounds.center)
        }, "Exact isolated money text must actually be visible: $literal")
    }

    private companion object {
        const val VIEWPORT = "bounded-training-app"
        const val SEARCH_EN = "Search items or scan a barcode"
        const val SEARCH_AR = "ابحث عن صنف أو امسح باركود"
        const val REVIEW_EN = "Review training total"
        const val REVIEW_AR = "مراجعة إجمالي التدريب"
        const val LANGUAGE_EN = "Change language"
        const val LANGUAGE_AR = "تغيير اللغة"
    }
}
