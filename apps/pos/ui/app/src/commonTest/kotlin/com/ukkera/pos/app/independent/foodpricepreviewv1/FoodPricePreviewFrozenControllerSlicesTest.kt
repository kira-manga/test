@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app

import com.ukkera.pos.app.navigation.NavigationAction
import com.ukkera.pos.app.navigation.NavigationResult
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Common controller slices of food-pre-save-preview-v1, freeze
 * dd90e2bb14571c813d2d8c974755ab7edf734c1f03fdc2652200c1d553d2fcf3.
 * Literal inputs/amounts come from that independent freeze and unchanged FQ v1.
 *
 * Replies and coroutine schedules are controlled UI seams. These assertions do not
 * qualify native Master authority, durable zero-delta cuts, pricing-engine selection,
 * admission, protected same-device/LAN, renewal lineage, installed UI or native input.
 * All eight preview whole cases/54 variants and ten original FQ cases remain NOT_RUN.
 */
class FoodPricePreviewFrozenControllerSlicesTest {
    @Test
    fun fqp01DirectTwoPreservesOneExactCandidateLineAndOriginalAmountsWithoutStaging() = runTest {
        withFoodPricePreviewFrozenController { fixture ->
            val original = fixture.enter("2")
            fixture.preview(FoodPricePreviewFrozenExpected.originalTwo)
            fixture.assertPreview(FoodPricePreviewFrozenExpected.originalTwo, original)
            assertFalse(assertNotNull(fixture.controller.state.value.form).confirmed)
            fixture.controller.stage(fixture.token())
            runCurrent()
            fixture.assertNoBusinessCalls()
        }
    }

    @Test
    fun fqp01ExplicitOneToTwoClearsOldPreviewAndConfirmationBeforeTheExactTwoRead() = runTest {
        withFoodPricePreviewFrozenController { fixture ->
            val one = fixture.enter("1")
            fixture.preview(FoodPricePreviewFrozenExpected.originalOne)
            fixture.assertPreview(FoodPricePreviewFrozenExpected.originalOne, one)
            fixture.controller.confirmForm(true, fixture.token())
            assertTrue(assertNotNull(fixture.controller.state.value.form).confirmed)

            fixture.controller.edit(LocalField.FOOD_SERVING_COUNT, LocalDraftValue("2"), fixture.token(), one.lineId)
            val two = fixture.checkpoint()
            assertEquals(one.lineId, two.lineId)
            assertNull(fixture.controller.state.value.foodPricePreview)
            assertFalse(assertNotNull(fixture.controller.state.value.form).confirmed)
            fixture.controller.stage(fixture.token())
            runCurrent()
            fixture.assertNoBusinessCalls()

            fixture.preview(FoodPricePreviewFrozenExpected.originalTwo)
            fixture.assertPreview(FoodPricePreviewFrozenExpected.originalTwo, two)
        }
    }

    @Test
    fun fqp01UnchangedMenuRefreshClearsPreviewAndOldConfirmationWhileRetainingExactInput() = runTest {
        withFoodPricePreviewFrozenController { fixture ->
            val original = fixture.enter("2")
            fixture.preview(FoodPricePreviewFrozenExpected.originalTwo)
            fixture.controller.confirmForm(true, fixture.token())
            fixture.port.policyRevision = Revision(11)
            val held = fixture.port.holdNextRead()
            fixture.controller.refresh(fixture.token())
            runCurrent()
            held.requested.await()
            assertNull(fixture.controller.state.value.foodPricePreview)
            assertFalse(assertNotNull(fixture.controller.state.value.form).confirmed)
            fixture.assertDraft(original)
            fixture.controller.confirmForm(true, fixture.token())
            fixture.controller.stage(fixture.token())
            fixture.assertNoBusinessCalls()

            held.reply.complete(fixture.port.page(FoodPricePreviewFrozenInputs.menu))
            runCurrent()
            held.returned.await()
            fixture.assertDraft(original)
            assertFalse(assertNotNull(fixture.controller.state.value.form).confirmed)
            assertNull(fixture.controller.state.value.foodPricePreview)
            fixture.controller.stage(fixture.token())
            runCurrent()
            fixture.assertNoBusinessCalls()
            fixture.preview(FoodPricePreviewFrozenExpected.originalTwo)
            fixture.assertPreview(FoodPricePreviewFrozenExpected.originalTwo, original)
        }
    }

    @Test
    fun fqp02FreshCompatibleAttachmentRestoresOriginalInputAndOnlyTheCurrentPreview() = runTest {
        withFoodPricePreviewFrozenController { fixture ->
            val original = fixture.enter("2")
            fixture.preview(FoodPricePreviewFrozenExpected.originalTwo)
            fixture.controller.confirmForm(true, fixture.token())
            val old = fixture.controller
            val oldToken = fixture.token()
            val fresh = FoodPricePreviewFrozenPort().apply {
                catalogRevision = Revision(6); policyRevision = Revision(11); recipeRevision = Revision(2)
                expected = FoodPricePreviewFrozenExpected.currentTwo
            }
            val held = fresh.holdNextRead()
            val current = fixture.replaceAttachment(fresh)
            assertTrue(current.state.value.attachmentReviewRequired)
            assertNull(current.state.value.foodPricePreview)
            assertNull(current.state.value.form)
            val retainedLine = assertNotNull(current.state.value.formInput).lines.single()
            assertEquals(original.lineId, retainedLine.lineId)
            assertEquals("2", retainedLine.values.getValue(LocalField.FOOD_SERVING_COUNT).text)
            assertEquals(FoodPricePreviewFrozenInputs.itemNote, retainedLine.values.getValue(LocalField.ITEM_NOTE).text)
            assertEquals(FoodPricePreviewFrozenInputs.seatNote, retainedLine.values.getValue(LocalField.LINE_SEAT_NOTE).text)
            old.confirmForm(true, oldToken)
            old.stage(oldToken)
            current.reviewAttachment(fixture.token())
            runCurrent()
            held.requested.await()
            current.confirmForm(true, fixture.token())
            current.stage(fixture.token())
            fixture.assertNoBusinessCalls()

            held.reply.complete(fresh.page(FoodPricePreviewFrozenInputs.menu))
            runCurrent()
            held.returned.await()
            assertFalse(current.state.value.attachmentReviewRequired)
            assertFalse(assertNotNull(current.state.value.form).confirmed)
            assertNull(current.state.value.foodPricePreview)
            fixture.assertDraft(original, current = true)
            current.stage(fixture.token())
            runCurrent()
            fixture.assertNoBusinessCalls()
            fixture.preview(FoodPricePreviewFrozenExpected.currentTwo)
            fixture.assertPreview(FoodPricePreviewFrozenExpected.currentTwo, original, current = true)
        }
    }

    @Test
    fun fqp02LateOriginalReplyCannotReplaceTheFreshCompatiblePreviewOrOldConfirmation() = runTest {
        withFoodPricePreviewFrozenController { fixture ->
            val original = fixture.enter("2")
            fixture.preview(FoodPricePreviewFrozenExpected.originalTwo)
            val old = fixture.controller
            val held = fixture.port.holdNextPreview()
            old.previewFood(fixture.token())
            runCurrent()
            held.requested.await()
            val originalInput = fixture.port.previews.last().snapshot()
            val fresh = FoodPricePreviewFrozenPort().apply {
                catalogRevision = Revision(6); policyRevision = Revision(11); recipeRevision = Revision(2)
                expected = FoodPricePreviewFrozenExpected.currentTwo
            }
            val current = fixture.replaceAttachment(fresh)
            current.reviewAttachment(fixture.token())
            runCurrent()
            fixture.assertDraft(original, current = true)
            fixture.preview(FoodPricePreviewFrozenExpected.currentTwo)
            fixture.assertPreview(FoodPricePreviewFrozenExpected.currentTwo, original, current = true)

            held.reply.complete(foodPricePreviewFrozenReply(originalInput, FoodPricePreviewFrozenExpected.originalTwo))
            runCurrent()
            held.returned.await()
            fixture.assertPreview(FoodPricePreviewFrozenExpected.currentTwo, original, current = true)
            assertNull(old.state.value.foodPricePreview)
            assertFalse(assertNotNull(current.state.value.form).confirmed)
            fixture.assertNoBusinessCalls()
        }
    }

    @Test
    fun fqp03OneHomogeneousLineDisplaysTheIndependentTierReplyAtQuantityTwo() = runTest {
        withFoodPricePreviewFrozenController { fixture ->
            val one = fixture.enter("1")
            fixture.preview(FoodPricePreviewFrozenExpected.tierOne)
            fixture.assertPreview(FoodPricePreviewFrozenExpected.tierOne, one)
            fixture.controller.edit(LocalField.FOOD_SERVING_COUNT, LocalDraftValue("2"), fixture.token(), one.lineId)
            val two = fixture.checkpoint()
            assertEquals(one.lineId, two.lineId)
            assertNull(fixture.controller.state.value.foodPricePreview)
            fixture.preview(FoodPricePreviewFrozenExpected.tierTwo)
            fixture.assertPreview(FoodPricePreviewFrozenExpected.tierTwo, two)
            assertEquals(1, fixture.port.previews.last().lines.size)
            fixture.assertNoBusinessCalls()
        }
    }

    @Test
    fun fqp04DeclaredInclusiveAndExclusiveRepliesRetainExactNetTaxAndFinalPayable() = runTest {
        listOf(FoodPricePreviewFrozenExpected.inclusive14, FoodPricePreviewFrozenExpected.exclusive14).forEach { expected ->
            withFoodPricePreviewFrozenController { fixture ->
                val original = fixture.enter("2")
                fixture.preview(expected)
                fixture.assertPreview(expected, original)
                assertFalse(assertNotNull(fixture.controller.state.value.form).confirmed)
                fixture.assertNoBusinessCalls()
            }
        }
    }

    @Test
    fun fqp05DeniedPreviewClearsInaccessibleProjectionInputAndOldConfirmation() = runTest {
        withFoodPricePreviewFrozenController { fixture ->
            fixture.enter("2")
            fixture.preview(FoodPricePreviewFrozenExpected.originalTwo)
            fixture.port.previewProblem = LocalServiceProblem.DENIED
            fixture.controller.previewFood(fixture.token())
            runCurrent()
            assertNull(fixture.controller.state.value.foodPricePreview)
            assertNull(fixture.controller.state.value.page)
            assertNull(fixture.controller.state.value.form)
            assertNull(fixture.controller.state.value.formInput)
            assertEquals(LocalServiceProblem.DENIED, fixture.controller.state.value.readProblem)
            fixture.controller.confirmForm(true, fixture.token())
            fixture.controller.stage(fixture.token())
            runCurrent()
            fixture.assertNoBusinessCalls()
        }
    }

    @Test
    fun fqp05DeniedCurrentAttachmentCannotBeRestoredByTheOldAuthorizedReply() = runTest {
        withFoodPricePreviewFrozenController { fixture ->
            fixture.enter("2")
            fixture.preview(FoodPricePreviewFrozenExpected.originalTwo)
            val old = fixture.controller
            val held = fixture.port.holdNextPreview()
            old.previewFood(fixture.token())
            runCurrent()
            held.requested.await()
            val oldInput = fixture.port.previews.last().snapshot()
            val denied = FoodPricePreviewFrozenPort().apply { readProblem = LocalServiceProblem.DENIED }
            val current = fixture.replaceAttachment(denied)
            current.reviewAttachment(fixture.token())
            runCurrent()
            assertEquals(LocalServiceProblem.DENIED, current.state.value.readProblem)
            assertNull(current.state.value.form)
            assertNull(current.state.value.formInput)

            held.reply.complete(foodPricePreviewFrozenReply(oldInput, FoodPricePreviewFrozenExpected.originalTwo))
            runCurrent()
            held.returned.await()
            assertNull(current.state.value.foodPricePreview)
            assertNull(current.state.value.page)
            assertNull(current.state.value.form)
            assertNull(current.state.value.formInput)
            assertNull(old.state.value.foodPricePreview)
            current.confirmForm(true, fixture.token())
            current.stage(fixture.token())
            fixture.assertNoBusinessCalls()
        }
    }

    @Test
    fun fqp06DifferentOriginalOrderProductVariantOrOptionReplyCannotBindToExactInput() = runTest {
        val ids = FoodPricePreviewFrozenInputs
        val mismatches = listOf(
            LocalField.ORDER_ID to ids.lookalikeOrder,
            LocalField.SKU_ID to ids.lookalikeProduct,
            LocalField.RECIPE_ID to ids.lookalikeVariant,
            LocalField.CHOICE_ID to ids.lookalikeOption,
        )
        mismatches.forEach { (field, replacement) ->
            withFoodPricePreviewFrozenController { fixture ->
                val original = fixture.enter("2")
                fixture.port.replyInput = { input -> replaceFrozenReplyIdentity(input, field, replacement) }
                fixture.preview(FoodPricePreviewFrozenExpected.originalTwo)
                assertNull(fixture.controller.state.value.foodPricePreview)
                assertEquals(LocalServiceProblem.UNAVAILABLE, fixture.controller.state.value.foodPricePreviewProblem)
                fixture.assertDraft(original)
                assertFalse(assertNotNull(fixture.controller.state.value.form).confirmed)
                fixture.controller.stage(fixture.token())
                runCurrent()
                fixture.assertNoBusinessCalls()
            }
        }
    }

    @Test
    fun fqp07CanceledReadDoesNotPublishACompletePreviewOrAdoptABusinessOperation() = runTest {
        withFoodPricePreviewFrozenController { fixture ->
            val original = fixture.enter("2")
            fixture.preview(FoodPricePreviewFrozenExpected.originalTwo)
            fixture.port.cancelNextPreview = true
            fixture.controller.previewFood(fixture.token())
            runCurrent()
            assertNull(fixture.controller.state.value.foodPricePreview)
            assertFalse(fixture.controller.state.value.working)
            fixture.assertDraft(original)
            fixture.assertNoBusinessCalls()
            fixture.preview(FoodPricePreviewFrozenExpected.originalTwo)
            fixture.assertPreview(FoodPricePreviewFrozenExpected.originalTwo, original)
        }
    }

    @Test
    fun fqp07UnavailableReadIsExplicitAndNeverReplacedWithAnEmptyOrZeroPreview() = runTest {
        withFoodPricePreviewFrozenController { fixture ->
            val original = fixture.enter("2")
            fixture.preview(FoodPricePreviewFrozenExpected.originalTwo)
            fixture.port.previewProblem = LocalServiceProblem.UNAVAILABLE
            fixture.controller.previewFood(fixture.token())
            runCurrent()
            assertNull(fixture.controller.state.value.foodPricePreview)
            assertEquals(LocalServiceProblem.UNAVAILABLE, fixture.controller.state.value.foodPricePreviewProblem)
            fixture.assertDraft(original)
            fixture.assertNoBusinessCalls()
            fixture.port.previewProblem = null
            fixture.preview(FoodPricePreviewFrozenExpected.originalTwo)
            fixture.assertPreview(FoodPricePreviewFrozenExpected.originalTwo, original)
        }
    }

    @Test
    fun fqp07RepeatedReadsKeepTheExactSelectionAndNeverStageOrDispatch() = runTest {
        withFoodPricePreviewFrozenController { fixture ->
            val original = fixture.enter("2")
            fixture.preview(FoodPricePreviewFrozenExpected.originalTwo)
            val first = fixture.port.previews.single().snapshot()
            fixture.preview(FoodPricePreviewFrozenExpected.originalTwo)
            assertEquals(2, fixture.port.previews.size)
            assertTrue(first.sameSelection(fixture.port.previews.last()))
            fixture.assertPreview(FoodPricePreviewFrozenExpected.originalTwo, original)
            fixture.assertNoBusinessCalls()
        }
    }

    @Test
    fun fqp08ObservedNonActiveSessionCannotBecomeFreshAuthorityByReturningToActive() = runTest {
        LocalSessionLifecycle.entries.filter { it != LocalSessionLifecycle.ACTIVE }.forEach { ended ->
            withFoodPricePreviewFrozenController(monitorSession = true) { fixture ->
                fixture.enter("2")
                fixture.preview(FoodPricePreviewFrozenExpected.originalTwo)
                fixture.controller.confirmForm(true, fixture.token())
                val old = fixture.controller
                val oldToken = fixture.token()
                val held = fixture.port.holdNextPreview()
                old.previewFood(oldToken)
                runCurrent()
                held.requested.await()
                val oldInput = fixture.port.previews.last().snapshot()
                fixture.session.lifecycle.value = ended
                runCurrent()
                assertNull(old.state.value.foodPricePreview)
                fixture.session.lifecycle.value = LocalSessionLifecycle.ACTIVE
                runCurrent()
                held.reply.complete(foodPricePreviewFrozenReply(oldInput, FoodPricePreviewFrozenExpected.originalTwo))
                runCurrent()
                held.returned.await()
                assertNull(old.state.value.foodPricePreview)
                assertNull(old.state.value.form)
                assertFalse(fixture.session.isCurrent())
                old.confirmForm(true, oldToken)
                old.stage(oldToken)
                fixture.assertNoBusinessCalls()
            }
        }
    }

    @Test
    fun fqp08CommonBackDiscardRetiresAPendingPreviewAndItsLateReply() = runTest {
        withFoodPricePreviewFrozenController { fixture ->
            fixture.enter("2")
            val old = fixture.controller
            val oldToken = fixture.token()
            val held = fixture.port.holdNextPreview()
            old.previewFood(oldToken)
            runCurrent()
            held.requested.await()
            val oldInput = fixture.port.previews.last().snapshot()
            assertEquals(NavigationResult.CONFIRM_DISCARD,
                fixture.runtime.navigator.navigate(NavigationAction.Back, fixture.token()))
            assertEquals(NavigationResult.CHANGED, fixture.runtime.navigator.confirmLeave(
                assertNotNull(fixture.runtime.navigator.state.value.confirmation).id))
            runCurrent()
            held.reply.complete(foodPricePreviewFrozenReply(oldInput, FoodPricePreviewFrozenExpected.originalTwo))
            runCurrent()
            held.returned.await()
            assertNull(old.state.value.foodPricePreview)
            assertNull(old.state.value.form)
            old.confirmForm(true, oldToken)
            old.stage(oldToken)
            fixture.assertNoBusinessCalls()
        }
    }
}

private fun replaceFrozenReplyIdentity(input: LocalFoodPreviewInput, field: LocalField,
    replacement: DomainId): LocalFoodPreviewInput {
    fun replace(value: LocalScalarInput): LocalScalarInput = when {
        value.field == field -> value.copy(value = LocalScalarValue.Identifier(replacement))
        field == LocalField.CHOICE_ID && value.field == LocalField.MODIFIER_IDS ->
            value.copy(value = LocalScalarValue.Text(replacement.value))
        else -> value
    }
    return input.copy(values = input.values.map(::replace),
        lines = input.lines.map { LocalInputLine(it.lineId, it.values.map(::replace)) })
}
