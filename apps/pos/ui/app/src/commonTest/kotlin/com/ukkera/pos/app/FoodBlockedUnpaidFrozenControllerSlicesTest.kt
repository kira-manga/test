@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app

import androidx.compose.ui.unit.LayoutDirection
import com.ukkera.pos.app.FbuUiFixture.Companion.withController
import com.ukkera.pos.app.navigation.*
import com.ukkera.pos.app.ui.AppLanguage
import com.ukkera.pos.app.ui.foodKitchenNoticeText
import com.ukkera.pos.types.*
import kotlinx.coroutines.test.*
import kotlin.test.*

/**
 * Production runtime/controller with controlled public UI inputs only. No authentication,
 * native recreation, original-result custody, Master persistence or admitted RPC is supplied.
 * Native rendering/accessibility and all 24 whole FBU cases remain NOT_RUN.
 */
class FoodBlockedUnpaidFrozenControllerSlicesTest {
    @Test
    fun fbu21EnglishArabicEnglishKeepsTheSameBlockedCheckAmountsNotesAndQuantitiesWithoutReplay() = runTest {
        for (order in listOf(FbuUiInputs.r0, FbuUiInputs.c0, FbuUiInputs.r2, FbuUiInputs.c2)) {
            withController(order) { fixture ->
                val before = fixture.controller.state.value
                val body = assertIs<LocalFoodUiBody.Check>(before.page?.food?.body)
                val reads = fixture.port.reads.toList()
                for (language in listOf(AppLanguage.ENGLISH, AppLanguage.ARABIC, AppLanguage.ENGLISH)) {
                    fixture.runtime.changeLanguage(language)
                    runCurrent()
                    val state = fixture.controller.state.value
                    assertEquals(before.page, state.page)
                    assertEquals(LocalFoodUiQuery.Check(order.orderId), state.foodQuery)
                    assertEquals(language, fixture.runtime.presentation.value.language)
                    assertEquals(if (language == AppLanguage.ARABIC) LayoutDirection.Rtl else LayoutDirection.Ltr, language.direction)
                    assertEquals(if (language == AppLanguage.ARABIC) foodKitchenNoticeText(body).arabic
                        else foodKitchenNoticeText(body).english, language.text(foodKitchenNoticeText(body)))
                    assertEquals(order.orderId, body.check.orderId)
                    assertEquals(Revision(7), body.check.orderStorageRevision)
                    assertEquals(Money(Currency.EGP, order.quoteMinor), body.originalQuotedTotal)
                    assertEquals(order.portionIds, body.portions.map { it.portionId })
                    assertTrue(body.portions.all { it.quantity == FbuUiInputs.one && it.itemNote == FbuUiInputs.itemNote &&
                        it.seatNote == FbuUiInputs.seatNote && it.preparation == LocalFoodPreparation.NOT_SENT })
                    assertEquals(FbuUiInputs.seatNote, body.check.service.seatNote)
                    assertNull(body.check.collectionId)
                    assertNull(body.financial)
                    assertEquals(reads, fixture.port.reads)
                    fixture.port.assertNoBusinessCalls()
                }
            }
        }
    }

    @Test
    fun fbu11RefreshPublishesOnlyTheFreshExactUnknownCutWithoutBusinessReplay() = runTest {
        withController { fixture ->
            val old = assertIs<LocalFoodUiBody.Check>(fixture.controller.state.value.page?.food?.body)
            assertEquals(LocalFoodUiKitchenStatus.UNAVAILABLE, old.kitchen?.status)
            val held = fixture.port.holdNextRead()
            fixture.controller.refresh(fixture.token())
            runCurrent()
            held.requested.await()
            val query = LocalFoodUiQuery.Check(FbuUiInputs.r2.orderId)
            val fresh = FbuUiInputs.body(FbuUiInputs.r2, LocalFoodUiKitchenStatus.UNKNOWN)
            held.reply.complete(FbuUiInputs.available(query, fresh))
            runCurrent()
            held.returned.await()
            val current = assertIs<LocalFoodUiBody.Check>(fixture.controller.state.value.page?.food?.body)
            assertEquals(fresh, current)
            assertEquals(LocalFoodUiKitchenObservation(FbuUiInputs.r2.orderId, Revision(7), 2,
                LocalFoodUiKitchenStatus.UNKNOWN), current.kitchen)
            assertFalse(current.kitchenUnavailableForUnsentWork)
            assertEquals(query, fixture.controller.state.value.foodQuery)
            assertEquals(Money(Currency.EGP, 20_000), current.originalQuotedTotal)
            fixture.port.assertNoBusinessCalls()
        }
    }

    @Test
    fun fbu10And11CurrentDenialRemovesOldPrivateBlockedPageAndUnstagedFormControls() = runTest {
        withController { fixture ->
            assertNotNull(fixture.controller.state.value.page)
            fixture.port.readProblem = LocalServiceProblem.DENIED
            fixture.controller.refresh(fixture.token())
            runCurrent()
            assertNull(fixture.controller.state.value.page)
            assertNull(fixture.controller.state.value.form)
            assertNull(fixture.controller.state.value.formInput)
            assertEquals(LocalFoodUiQuery.MyChecks(), fixture.controller.state.value.foodQuery)
            assertEquals(LocalServiceProblem.DENIED, fixture.controller.state.value.readProblem)
            fixture.controller.openForm(LocalAction.FOOD_EDIT_SERVICE_DETAILS, fixture.token())
            fixture.controller.confirmForm(true, fixture.token())
            fixture.controller.stage(fixture.token())
            runCurrent()
            fixture.port.assertNoBusinessCalls()
        }
        withController { fixture ->
            fixture.controller.openForm(LocalAction.FOOD_EDIT_SERVICE_DETAILS, fixture.token())
            val oldForm = assertNotNull(fixture.controller.state.value.form)
            assertFalse(oldForm.dirty)
            fixture.port.pendingProblem = LocalServiceProblem.DENIED
            fixture.controller.refreshPending(fixture.token()) // Current UI denial, not an accepting history lookup.
            runCurrent()
            assertNull(fixture.controller.state.value.page)
            assertNull(fixture.controller.state.value.form)
            assertNull(fixture.controller.state.value.formInput)
            assertEquals(LocalServiceProblem.DENIED, fixture.controller.state.value.readProblem)
            fixture.controller.confirmForm(true, fixture.token())
            fixture.controller.stage(fixture.token())
            runCurrent()
            fixture.port.assertNoBusinessCalls()
        }
    }

    @Test
    fun fbu11And22LateOldReplyCannotReviveANewerDeniedAttachment() = runTest {
        withController { fixture ->
            val query = LocalFoodUiQuery.Check(FbuUiInputs.r2.orderId)
            val oldBody = assertIs<LocalFoodUiBody.Check>(fixture.controller.state.value.page?.food?.body)
            val held = fixture.port.holdNextRead()
            fixture.controller.refresh(fixture.token())
            runCurrent()
            held.requested.await()
            val oldController = fixture.controller
            val oldToken = fixture.token()
            val freshPort = FbuUiPort().apply { readProblem = LocalServiceProblem.DENIED }
            val current = fixture.replaceAttachment(freshPort)
            assertNull(oldController.state.value.page)
            assertNull(current.state.value.page)
            assertNull(current.state.value.form)
            assertTrue(current.state.value.attachmentReviewRequired)
            assertTrue(freshPort.reads.isEmpty(), "Replacement does not replay business work or a protected read")
            current.reviewAttachment(fixture.token())
            runCurrent()
            assertEquals(LocalServiceProblem.DENIED, current.state.value.readProblem)
            assertNull(current.state.value.page)

            held.reply.complete(FbuUiInputs.available(query, oldBody))
            runCurrent()
            held.returned.await()
            oldController.openForm(LocalAction.FOOD_EDIT_SERVICE_DETAILS, oldToken)
            oldController.confirmForm(true, oldToken)
            oldController.stage(oldToken)
            current.openForm(LocalAction.FOOD_EDIT_SERVICE_DETAILS, fixture.token())
            current.confirmForm(true, fixture.token())
            current.stage(fixture.token())
            runCurrent()
            assertNull(oldController.state.value.page)
            assertNull(oldController.state.value.form)
            assertNull(current.state.value.page)
            assertNull(current.state.value.form)
            assertEquals(LocalServiceProblem.DENIED, current.state.value.readProblem)
            fixture.port.assertNoBusinessCalls()
            freshPort.assertNoBusinessCalls()
        }
    }

    @Test
    fun fbu21And22BackAndSessionRetirementClearPrivateObservationsAndRejectLateCallbacks() = runTest {
        for (retirement in listOf("back", "session")) {
            withController { fixture ->
                val oldController = fixture.controller
                val oldBody = assertIs<LocalFoodUiBody.Check>(oldController.state.value.page?.food?.body)
                val held = fixture.port.holdNextRead()
                oldController.refresh(fixture.token())
                runCurrent()
                held.requested.await()
                val oldToken = fixture.token()
                if (retirement == "back") {
                    assertEquals(NavigationResult.CHANGED,
                        fixture.runtime.navigator.navigate(NavigationAction.Back, oldToken))
                    assertNotEquals(AppRoute.Food, fixture.runtime.navigator.state.value.activeEntry.route)
                } else {
                    fixture.runtime.lock(oldToken)
                    assertEquals(SecurityRoot.BOOTSTRAP, fixture.runtime.navigator.state.value.root)
                    assertTrue(fixture.runtime.presentation.value.locallyLocked)
                }
                runCurrent()
                assertNull(oldController.state.value.page)
                assertNull(oldController.state.value.form)
                assertNull(oldController.state.value.formInput)
                held.reply.complete(FbuUiInputs.available(LocalFoodUiQuery.Check(FbuUiInputs.r2.orderId), oldBody))
                runCurrent()
                held.returned.await()
                oldController.openForm(LocalAction.FOOD_EDIT_SERVICE_DETAILS, oldToken)
                oldController.confirmForm(true, oldToken)
                oldController.stage(oldToken)
                runCurrent()
                assertNull(oldController.state.value.page)
                assertNull(oldController.state.value.form)
                assertEquals(NavigationResult.IGNORED,
                    fixture.runtime.navigator.navigate(NavigationAction.Back, oldToken))
                fixture.port.assertNoBusinessCalls()
            }
        }
    }
}
