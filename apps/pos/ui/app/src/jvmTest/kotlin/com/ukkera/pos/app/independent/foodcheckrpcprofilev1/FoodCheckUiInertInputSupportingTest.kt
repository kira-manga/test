@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.independent.foodcheckrpcprofilev1

import com.ukkera.pos.app.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Controlled presentation membership/inert-input SUPPORT only. No durable or admitted native/RPC original is created. */
class FoodCheckUiInertInputSupportingTest {
    private val frozen = FrozenFoodCheckUiInputs

    @Test
    fun fcrp004ExcludedWorkflowSelectorEditorAndPhysicalControlsMakeNoPortCalls() = runTest {
        withFoodCheckUi {
            assertEquals(LocalFoodUiQuery.MyChecks(), port.reads.single().food)
            val calls = port.counts()
            for (workflow in LocalWorkflow.entries.filterNot { it == LocalWorkflow.FOOD }) controller.selectWorkflow(workflow, token())
            frozen.excludedQueries().forEach { controller.selectFood(it, token()) }
            for (action in LocalAction.entries.filterNot { it in setOf(LocalAction.FOOD_CREATE_DRAFT, LocalAction.FOOD_ADD) }) {
                controller.openForm(action, token())
            }
            controller.changeSearch("Excluded free-text selector", token())
            controller.selectRecord(LocalRecordReference(LocalRecordKind.ORDER, frozen.order), token())
            controller.openRetailCollection(token())
            // No supplied authorized form exists. Allowed action names cannot manufacture one.
            controller.openForm(LocalAction.FOOD_CREATE_DRAFT, token())
            controller.openForm(LocalAction.FOOD_ADD, token())
            controller.stage(token())
            controller.submit(frozen.draftOperation, token())
            controller.lookup(frozen.draftOperation, token())
            controller.acknowledgeRecorded(frozen.draftOperation, token())
            runCurrent()
            assertEquals(calls, port.counts())
            assertEquals(LocalWorkflow.FOOD, controller.state.value.workflow)
            assertEquals("", controller.state.value.search)
            assertNull(controller.state.value.reference)
            assertNull(controller.state.value.form)
            assertFalse(controller.retailPhysical.state.value.pendingLoaded)
            assertNull(controller.retailPhysical.state.value.payment)
            port.assertNoBusinessOrRecoveryCalls()
        }
    }

    @Test
    fun fcrp004DeniedUnavailableOrBroaderEditorRepliesNeverSupplyAFormOrFallback() = runTest {
        val replies = listOf(LocalReadResult.Unavailable(LocalServiceProblem.DENIED),
            LocalReadResult.Unavailable(LocalServiceProblem.UNAVAILABLE),
            LocalReadResult.Available(frozen.ownPage(listOf(frozen.draftForm(1)))),
            LocalReadResult.Available(frozen.ownPage(listOf(LocalActionForm(LocalAction.FOOD_STATION_START, 1,
                frozen.text("Excluded editor"), emptyList())))))
        for (reply in replies) withFoodCheckUi(configure = { it.readReply = { reply } }) {
            assertEquals(1, port.reads.size)
            assertNull(controller.state.value.page)
            assertNotNull(controller.state.value.readProblem)
            controller.openForm(LocalAction.FOOD_CREATE_DRAFT, token())
            controller.openForm(LocalAction.FOOD_ADD, token())
            controller.confirmForm(true, token())
            controller.stage(token())
            runCurrent()
            assertEquals(1, port.reads.size)
            assertNull(controller.state.value.form)
            assertNull(controller.state.value.operationId)
            port.assertNoBusinessOrRecoveryCalls()
        }
    }

    @Test
    fun fcrp013MixedPendingShapesRemainUnavailableNotSuccessfulEmptyOrRewritten() = runTest {
        val incompatible = listOf(frozen.pending(LocalAction.FOOD_STATION_START, frozen.addOperation),
            frozen.pending(LocalAction.ORDER_CREATE, frozen.addOperation),
            frozen.pending(operation = frozen.addOperation, decision = LocalRecordedDecision.RECORDED_ONLY))
        for (other in incompatible) {
            val own = frozen.pending()
            val supplied = LocalPendingResult.Available(listOf(own, other))
            withFoodCheckUi(configure = { it.pendingReply = { supplied } }) {
                assertFalse(controller.state.value.pendingLoaded)
                assertNotNull(controller.state.value.pendingProblem)
                assertTrue(controller.state.value.pending.isEmpty())
                controller.refreshPending(token())
                controller.stage(token())
                controller.submit(frozen.draftOperation, token())
                controller.lookup(frozen.draftOperation, token())
                controller.acknowledgeRecorded(frozen.draftOperation, token())
                runCurrent()
                assertFalse(controller.state.value.pendingLoaded)
                assertNotNull(controller.state.value.pendingProblem)
                assertSame(own, supplied.operations[0])
                assertSame(other, supplied.operations[1])
                assertEquals(listOf(frozen.draftOperation, frozen.addOperation), supplied.operations.map { it.operationId })
                port.assertNoBusinessOrRecoveryCalls()
            }
        }
    }

    @Test
    fun fcrp013LegacyOrUnboundRetainedInputsStayInertWithTheSameRecoveryIdentity() = runTest {
        val original = frozen.unresolved()
        val oldForm = localFormDraft(frozen.draftForm(1))
        val variants = listOf(frozen.unresolved(frozen.draftIntent(1)), frozen.unresolved(frozen.unboundAddIntent()),
            original.copy(originalAction = LocalAction.FOOD_STATION_START),
            original.copy(workspace = original.workspace.copy(form = oldForm)),
            original.copy(workspace = original.workspace.copy(formInput = oldForm.inputContinuation())),
            original.copy(workspace = original.workspace.copy(foodQuery = LocalFoodUiQuery.FloorDirectory())),
            original.copy(workspace = original.workspace.copy(pending = listOf(frozen.pending(LocalAction.ORDER_CREATE)))))
        for (retained in variants) withFoodCheckUi(continuation = retained) {
            assertEquals(listOf(0, 0, 0, 0, 0, 0), port.counts())
            controller.refresh(token())
            controller.selectFood(LocalFoodUiQuery.MyChecks(), token())
            controller.selectWorkflow(LocalWorkflow.FOOD, token())
            controller.openForm(LocalAction.FOOD_CREATE_DRAFT, token())
            controller.discardForm(token())
            controller.confirmForm(true, token())
            controller.stage(token())
            controller.retryStage(token())
            controller.submit(assertNotNull(retained.workspace.operationId), token())
            controller.lookup(assertNotNull(retained.workspace.operationId), token())
            controller.acknowledgeRecorded(assertNotNull(retained.workspace.operationId), token())
            controller.reviewAttachment(token())
            runCurrent()
            assertEquals(listOf(0, 0, 0, 0, 0, 0), port.counts())
            assertEquals(retained.workspace.operationId, controller.state.value.operationId)
            assertEquals(retained.workspace.form, controller.state.value.form)
            assertEquals(retained.workspace.formInput, controller.state.value.formInput)
            val saved = assertNotNull(controller.retireForAttachment())
            assertEquals(retained.workspace.operationId, saved.workspace.operationId)
            assertSame(retained.frozen, saved.frozen)
            assertEquals(retained.originalAction, saved.originalAction)
        }
    }
}
