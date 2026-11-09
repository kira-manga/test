@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.independent.fooddeliverydraftsv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.independent.fooddeliverydraftsv1.FdpUiFixture.Companion.withController
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Controlled common controller slices of frozen food-delivery-drafts-v1.
 * Exact fixture values and frozen outcomes remain the oracles. Scripted form,
 * pending and read inputs do not qualify Master commit, canonical gateway bytes,
 * current authentication, durable recovery, native lifecycle or admitted RPC.
 */
class FoodDeliveryDraftsFrozenControllerSlicesTest {
    @Test
    fun fdpD03InvalidFrozenScalarInputsAndAsciiControlsStageZeroOperations() = runTest {
        val refused = FdpUiFrozenInputs.metadataVariants.filter { !it.accepts } +
            FdpUiFrozenInputs.asciiControlVariants()
        refused.forEach { input ->
            withController(form = fdpUiDraftForm(input.texts)) { fixture ->
                fixture.controller.openForm(LocalAction.FOOD_CREATE_DRAFT, fixture.token())
                fixture.controller.confirmForm(true, fixture.token())
                fixture.controller.stage(fixture.token())
                runCurrent()
                assertNotNull(fixture.controller.state.value.issue, input.caseId)
                assertNull(fixture.controller.state.value.operationId, input.caseId)
                fixture.port.assertNoBusinessCalls()
            }
        }
    }

    @Test
    fun fdpD01D02D03D19ValidDeliveryInputsStageExactTextAndRequestedActivity() = runTest {
        val originals = listOf(
            Triple("RESTAURANT", FdpUiFrozenInputs.restaurantOrder, FdpUiFrozenInputs.baseTexts),
            Triple("CAFE", FdpUiFrozenInputs.cafeOrder, FdpUiFrozenInputs.baseTexts),
            Triple("RESTAURANT", FdpUiFrozenInputs.restaurantOrder, FdpUiFrozenInputs.arabicTexts),
        ) + FdpUiFrozenInputs.metadataVariants.filter { it.accepts }.map {
            Triple("RESTAURANT", FdpUiFrozenInputs.restaurantOrder, it.texts)
        }
        originals.forEach { (activity, order, texts) ->
            val summary = fdpUiSummary(texts)
            withController(form = fdpUiDraftForm(texts, activity = activity, orderId = order), summary = summary) { fixture ->
                fixture.controller.openForm(LocalAction.FOOD_CREATE_DRAFT, fixture.token())
                fixture.port.assertNoBusinessCalls()
                fixture.controller.confirmForm(true, fixture.token())
                fixture.controller.stage(fixture.token())
                runCurrent()
                val original = fixture.port.stages.single()
                assertExactDeliveryIntent(original, texts, activity, order)
                val review = assertIs<LocalOperationResult.Pending>(fixture.controller.state.value.operation).operation
                assertEquals(summary, review.summary)
                assertTrue(review.listSummary().isEmpty())
                fixture.port.assertNoDispatchOrRecoveryCalls()
            }
        }
    }

    @Test
    fun fdpD04D19ValidDeliveryWithoutExtraReferenceInputStillStagesOnce() = runTest {
        withController(form = fdpUiDraftForm(reference = null)) { fixture ->
            fixture.controller.openForm(LocalAction.FOOD_CREATE_DRAFT, fixture.token())
            fixture.controller.confirmForm(true, fixture.token())
            fixture.controller.stage(fixture.token())
            runCurrent()
            val original = fixture.port.stages.single()
            assertExactDeliveryIntent(original, FdpUiFrozenInputs.baseTexts, "RESTAURANT",
                FdpUiFrozenInputs.restaurantOrder, reference = null)
            // The gateway's allocated-order neutral label and exact wire bytes are a separate seam.
            assertNull(original.scalar(LocalField.SERVICE_REFERENCE))
            fixture.port.assertNoDispatchOrRecoveryCalls()
        }
    }

    @Test
    fun fdpD19NonDeliveryStagingOmitsDetailsAndPresentInapplicableInputRefuses() = runTest {
        listOf(
            Triple("TAKEAWAY", FdpUiFrozenInputs.pickupReference, FdpUiFrozenInputs.takeawayOrder),
            Triple("DINE_IN", FdpUiFrozenInputs.tableReference, FdpUiFrozenInputs.dineInOrder),
        ).forEachIndexed { index, (channel, reference, order) ->
            withController(form = fdpUiDraftForm(emptyMap(), channel, reference, orderId = order), summary = emptyList()) { fixture ->
                fixture.controller.openForm(LocalAction.FOOD_CREATE_DRAFT, fixture.token())
                fixture.controller.confirmForm(true, fixture.token())
                fixture.controller.stage(fixture.token())
                runCurrent()
                val original = fixture.port.stages.single()
                assertTrue(original.values.none { it.field in FdpUiFrozenInputs.deliveryFields }, "FDP-D19-V0${index + 1}")
                assertEquals(LocalScalarValue.Choice(channel), original.scalar(LocalField.CHANNEL))
                assertEquals(LocalScalarValue.Text(reference), original.scalar(LocalField.SERVICE_REFERENCE))
                assertEquals(LocalScalarValue.Identifier(order), original.scalar(LocalField.ORDER_ID))
                fixture.port.assertNoDispatchOrRecoveryCalls()
            }
            withController(form = fdpUiDraftForm(FdpUiFrozenInputs.baseTexts, channel, reference, orderId = order), summary = emptyList()) { fixture ->
                fixture.controller.openForm(LocalAction.FOOD_CREATE_DRAFT, fixture.token())
                fixture.controller.confirmForm(true, fixture.token())
                fixture.controller.stage(fixture.token())
                runCurrent()
                assertNotNull(fixture.controller.state.value.issue, "FDP-D19-V0${index + 3}")
                fixture.port.assertNoBusinessCalls()
            }
        }
    }

    @Test
    fun fdpD19ChannelChangesRetireStaleDetailsBeforeTheNewFormStages() = runTest {
        listOf("TAKEAWAY" to FdpUiFrozenInputs.pickupReference, "DINE_IN" to FdpUiFrozenInputs.tableReference)
            .forEachIndexed { index, (channel, reference) ->
                withController(summary = emptyList()) { fixture ->
                    fixture.controller.openForm(LocalAction.FOOD_CREATE_DRAFT, fixture.token())
                    fixture.controller.edit(LocalField.CHANNEL,
                        LocalDraftValue(selected = LocalScalarValue.Choice(channel)), fixture.token())
                    fixture.controller.edit(LocalField.SERVICE_REFERENCE, LocalDraftValue(reference), fixture.token())
                    val changed = assertNotNull(fixture.controller.state.value.form)
                    assertTrue(changed.foodDraftFields().none { it.field in FdpUiFrozenInputs.deliveryFields })
                    FdpUiFrozenInputs.deliveryFields.forEach { field ->
                        assertEquals(LocalDraftValue(), changed.values.getValue(field), "FDP-D19-V0${index + 5}")
                    }
                    fixture.controller.confirmForm(true, fixture.token())
                    fixture.controller.stage(fixture.token())
                    runCurrent()
                    val original = fixture.port.stages.single()
                    assertTrue(original.values.none { it.field in FdpUiFrozenInputs.deliveryFields })
                    assertEquals(LocalScalarValue.Choice(channel), original.scalar(LocalField.CHANNEL))
                    assertEquals(LocalScalarValue.Text(reference), original.scalar(LocalField.SERVICE_REFERENCE))
                    fixture.port.assertNoDispatchOrRecoveryCalls()
                }
            }
    }

    @Test
    fun fdpD19D20D22PendingOriginalAndPrivateReviewKeepSavedDetailsAcrossAttachmentChange() = runTest {
        listOf("TAKEAWAY", "DINE_IN").forEach { pendingChannel ->
            withController { fixture ->
                fixture.controller.openForm(LocalAction.FOOD_CREATE_DRAFT, fixture.token())
                fixture.controller.confirmForm(true, fixture.token())
                fixture.controller.stage(fixture.token())
                runCurrent()
                val original = fixture.port.stages.single()
                val originalValues = original.values
                val stored = assertNotNull(fixture.port.stagedOperation)
                assertEquals(fdpUiSummary(FdpUiFrozenInputs.baseTexts), stored.summary)
                assertTrue(stored.listSummary().isEmpty())
                FdpUiFrozenInputs.replacementTexts.forEach { (field, replacement) ->
                    fixture.controller.edit(field, LocalDraftValue(replacement), fixture.token())
                }
                fixture.controller.edit(LocalField.CHANNEL,
                    LocalDraftValue(selected = LocalScalarValue.Choice(pendingChannel)), fixture.token())
                fixture.controller.confirmForm(true, fixture.token())
                fixture.controller.stage(fixture.token())
                runCurrent()
                assertEquals(1, fixture.port.stages.size)
                assertEquals(originalValues, original.values)
                assertExactDeliveryIntent(original, FdpUiFrozenInputs.baseTexts, "RESTAURANT", FdpUiFrozenInputs.restaurantOrder)
                fixture.port.assertNoDispatchOrRecoveryCalls()

                val fresh = FdpUiPort(formSource = fdpUiDraftForm(FdpUiFrozenInputs.replacementTexts)).apply {
                    lookupResult = LocalOperationResult.Pending(stored)
                }
                val current = fixture.replaceAttachment(fresh)
                assertNull(current.state.value.form)
                assertNull(current.state.value.page)
                assertEquals(FdpUiFrozenInputs.createOperation, current.state.value.operationId)
                fresh.assertNoBusinessCalls()
                current.reviewAttachment(fixture.token())
                runCurrent()
                current.lookup(original.operationId, fixture.token())
                runCurrent()
                current.lookup(original.operationId, fixture.token())
                runCurrent()
                val review = assertIs<LocalOperationResult.Pending>(current.state.value.operation).operation
                assertEquals(fdpUiSummary(FdpUiFrozenInputs.baseTexts), review.summary)
                assertTrue(review.listSummary().isEmpty())
                assertEquals(originalValues, original.values)
                assertEquals(listOf(original.operationId, original.operationId), fresh.lookups)
                assertTrue(fresh.stages.isEmpty())
                assertTrue(fresh.submissions.isEmpty())
                assertTrue(fresh.acknowledgements.isEmpty())
                assertEquals(0, fresh.previews)
                assertEquals(1, fixture.port.stages.size)
            }
        }
    }

    @Test
    fun fdpD20ExactCurrentPrivateCheckRetiresOnFailedDeniedOrEndedReadWithoutFallback() = runTest {
        listOf(LocalServiceProblem.UNAVAILABLE, LocalServiceProblem.DENIED, LocalServiceProblem.SESSION_ENDED)
            .forEach { readProblem ->
                val query = LocalFoodUiQuery.Check(FdpUiFrozenInputs.restaurantOrder)
                withController(query = query) { fixture ->
                    val current = assertIs<LocalFoodUiBody.Check>(fixture.controller.state.value.page?.food?.body)
                    assertEquals(FdpUiFrozenInputs.restaurantOrder, current.check.orderId)
                    assertEquals(Revision(30), current.check.orderStorageRevision)
                    assertEquals(FdpUiFrozenInputs.baseTexts, assertNotNull(current.delivery).details.texts())
                    assertEquals(current.check.orderId, current.delivery?.orderId)
                    assertEquals(current.check.orderStorageRevision, current.delivery?.orderStorageRevision)
                    assertEquals(FdpUiFrozenInputs.reference, current.check.service.reference)
                    assertEquals("", current.check.service.seatNote)
                    assertEquals(listOf(FdpUiFrozenInputs.firstPortion, FdpUiFrozenInputs.secondPortion),
                        current.portions.map { it.portionId })
                    assertTrue(current.portions.all { it.preparation == LocalFoodPreparation.NOT_SENT && it.itemNote == "" })
                    assertEquals(22800L, current.originalQuotedTotal.minor)
                    assertTrue(current.chargeResolutionRequired)
                    assertTrue(current.kitchenUnavailableForUnsentWork)
                    assertNull(current.financial)
                    assertNull(current.check.collectionId)
                    fixture.port.assertNoBusinessCalls()

                    fixture.port.readProblem = readProblem
                    fixture.controller.refresh(fixture.token())
                    runCurrent()
                    assertNull(fixture.controller.state.value.page)
                    assertNull(fixture.controller.state.value.form)
                    assertEquals(listOf(query, query), fixture.port.reads.map { it.food })
                    fixture.port.assertNoBusinessCalls()
                }
            }
    }

    @Test
    fun fdpD20AnOldAllowedCallbackCannotRestoreDetailsAfterDeniedAttachment() = runTest {
        val query = LocalFoodUiQuery.Check(FdpUiFrozenInputs.restaurantOrder)
        withController(query = query) { fixture ->
            assertEquals(FdpUiFrozenInputs.baseTexts,
                assertIs<LocalFoodUiBody.Check>(fixture.controller.state.value.page?.food?.body).delivery?.details?.texts())
            val held = fixture.port.holdNextRead()
            fixture.controller.refresh(fixture.token())
            runCurrent()
            held.requested.await()
            val oldController = fixture.controller
            val oldToken = fixture.token()
            val staleReply = fixture.port.available(query)
            val denied = FdpUiPort().apply { readProblem = LocalServiceProblem.DENIED }
            val current = fixture.replaceAttachment(denied)
            assertNull(current.state.value.page)
            current.reviewAttachment(fixture.token())
            runCurrent()
            assertNull(current.state.value.page)
            held.reply.complete(staleReply)
            runCurrent()
            held.returned.await()
            oldController.openForm(LocalAction.FOOD_CREATE_DRAFT, oldToken)
            oldController.confirmForm(true, oldToken)
            oldController.stage(oldToken)
            assertNull(current.state.value.page)
            assertNull(current.state.value.form)
            fixture.port.assertNoBusinessCalls()
            denied.assertNoBusinessCalls()
        }
    }
}

private fun LocalCommandIntent.scalar(field: LocalField): LocalScalarValue? =
    values.singleOrNull { it.field == field }?.value

private fun assertExactDeliveryIntent(intent: LocalCommandIntent, texts: Map<LocalField, String>,
    activity: String, order: DomainId, reference: String? = FdpUiFrozenInputs.reference) {
    assertEquals(FdpUiFrozenInputs.createOperation, intent.operationId)
    assertEquals(LocalAction.FOOD_CREATE_DRAFT, intent.action)
    assertEquals(2, intent.formVersion)
    assertEquals(LocalScalarValue.Identifier(order), intent.scalar(LocalField.ORDER_ID))
    assertEquals(LocalScalarValue.Choice(activity), intent.scalar(LocalField.ACTIVITY))
    assertEquals(LocalScalarValue.Choice("DELIVERY"), intent.scalar(LocalField.CHANNEL))
    assertEquals(reference?.let { LocalScalarValue.Text(it) }, intent.scalar(LocalField.SERVICE_REFERENCE))
    assertEquals(FdpUiFrozenInputs.deliveryFields.toSet(), intent.values.filter {
        it.field in FdpUiFrozenInputs.deliveryFields
    }.map { it.field }.toSet())
    FdpUiFrozenInputs.deliveryFields.forEach { field ->
        assertEquals(LocalScalarValue.Text(texts.getValue(field)), intent.scalar(field))
    }
    val seatNote = intent.scalar(LocalField.SEAT_NOTE)
    assertTrue(seatNote == null || seatNote == LocalScalarValue.Text(""))
    assertNull(intent.lineField)
    assertTrue(intent.lines.isEmpty())
}
