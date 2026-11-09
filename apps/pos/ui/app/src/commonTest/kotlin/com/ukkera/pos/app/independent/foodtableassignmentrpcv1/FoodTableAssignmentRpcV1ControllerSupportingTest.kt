@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.independent.foodtableassignmentrpcv1

import com.ukkera.pos.app.*
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** FTA-RPC-v1-S02 and UI-only S03 slices. Controlled port calls are not native/RPC evidence. */
class FoodTableAssignmentRpcV1ControllerSupportingTest {
    @Test
    fun s02BrowsingAndOpeningFormsCannotExecuteOrStageAssignment() = runTest {
        val input = TableAssignmentUiInput
        withTableAssignmentUi(query = LocalFoodUiQuery.Check(input.orderId)) {
            controller.selectFood(LocalFoodUiQuery.TableAssignment(input.orderId, Revision(7)), token()); runCurrent()
            controller.selectFood(LocalFoodUiQuery.FloorDirectory(assignment = input.target), token()); runCurrent()
            controller.selectFood(LocalFoodUiQuery.FloorTables(input.floorId, assignment = input.target), token()); runCurrent()
            controller.selectFood(input.selection, token()); runCurrent()
            controller.refresh(token()); runCurrent()
            assertEquals(listOf(LocalFoodUiQuery.Check(input.orderId),
                LocalFoodUiQuery.TableAssignment(input.orderId, Revision(7)),
                LocalFoodUiQuery.FloorDirectory(assignment = input.target),
                LocalFoodUiQuery.FloorTables(input.floorId, assignment = input.target),
                input.selection, input.selection), port.reads.map { it.food })
            controller.openForm(LocalAction.FOOD_ASSIGN_TABLE, token())
            val opened = assertNotNull(controller.state.value.form)
            assertEquals(LocalAction.FOOD_ASSIGN_TABLE, opened.descriptor.action)
            assertFalse(opened.confirmed)
            controller.stage(token()); runCurrent() // No explicit confirmation: no staged original.
            assertEquals(LocalField.CONFIRMED, controller.state.value.issue?.field)
            assertNull(controller.state.value.operationId)
            assertEquals(emptyList(), port.stages)
            assertEquals(emptyList(), port.submissions)
            assertEquals(emptyList(), port.acknowledgements)
        }
    }

    @Test
    fun s02ExactOriginalStageRetryAndDuplicateSubmitKeepA() = runTest {
        val input = TableAssignmentUiInput
        withTableAssignmentUi(continuation = input.continuation(uncertainStage = true)) {
            controller.retryStage(token()); runCurrent()
            val original = port.stages.single()
            assertEquals(input.a, original.operationId)
            assertEquals(input.values, original.values)
            assertIs<LocalOperationResult.Pending>(controller.state.value.operation)
            assertEquals(emptyList(), port.submissions)
            controller.retryStage(token()); runCurrent()
            assertEquals(1, port.stages.size)
            controller.submit(input.a, token())
            controller.submit(input.a, token()); runCurrent()
            assertEquals(listOf(input.a), port.submissions)
            assertEquals(input.a, controller.state.value.operationId)
            assertIs<LocalOperationResult.Unresolved>(controller.state.value.operation)
            controller.edit(LocalField.TABLE_ID, LocalDraftValue(selected = LocalScalarValue.Identifier(input.otherTableId)), token())
            controller.selectFood(LocalFoodUiQuery.TableDefinition(input.otherTableId, input.target), token())
            controller.lookup(input.a, token()); runCurrent()
            assertEquals(listOf(input.a), port.lookups)
            assertEquals(input.values, original.values)
            assertEquals(1, port.stages.size)
            assertEquals(emptyList(), port.acknowledgements)
        }
    }

    @Test
    fun s02RetirementKeepsTheOriginalButRejectsLatePrivateRead() = runTest {
        val input = TableAssignmentUiInput
        val late = CompletableDeferred<LocalReadResult>()
        withTableAssignmentUi(continuation = input.continuation(), configure = { port ->
            port.readReply = { withContext(NonCancellable) { late.await() } }
        }) {
            assertEquals(listOf(input.selection), port.reads.map { it.food })
            val retained = controller.retireForAttachment()!!
            assertNull(retained.workspace.page)
            assertEquals(input.a, retained.workspace.operationId)
            assertEquals(input.a, retained.frozen?.operationId)
            assertEquals(input.values, retained.frozen?.values)
            late.complete(LocalReadResult.Available(input.page(input.selection))); runCurrent()
            assertNull(controller.state.value.page)
            assertNull(controller.state.value.operationId)
            assertEquals(emptyList(), port.stages)
            assertEquals(emptyList(), port.submissions)
            assertEquals(emptyList(), port.acknowledgements)
        }
    }

    @Test
    fun s03ForeignOrNonAssignmentOutcomeCannotBeAcknowledged() = runTest {
        val input = TableAssignmentUiInput
        for (reply in listOf(input.recorded(input.b), input.recorded(decision = LocalRecordedDecision.RECORDED_ONLY))) {
            withTableAssignmentUi(continuation = input.continuation(), configure = { it.lookupReply = { reply } }) {
                controller.lookup(input.a, token()); runCurrent()
                assertEquals(input.a, controller.state.value.operationId)
                assertIs<LocalOperationResult.Unresolved>(controller.state.value.operation)
                controller.acknowledgeRecorded(input.a, token()); runCurrent()
                assertEquals(emptyList(), port.acknowledgements)
                assertEquals(emptyList(), port.submissions)
            }
        }
    }

    @Test
    fun s03ValidPresentationWaitsForExplicitOriginalAcknowledgement() = runTest {
        val input = TableAssignmentUiInput
        for (decision in listOf(LocalRecordedDecision.APPLIED, LocalRecordedDecision.REJECTED)) {
            withTableAssignmentUi(continuation = input.continuation(), configure = { it.lookupReply = { input.recorded(decision = decision) } }) {
                controller.lookup(input.a, token()); runCurrent()
                assertIs<LocalOperationResult.Recorded>(controller.state.value.operation)
                assertFalse(controller.state.value.recordedAcknowledged)
                assertEquals(emptyList(), port.acknowledgements)
                controller.acknowledgeRecorded(input.b, token()); runCurrent()
                assertEquals(emptyList(), port.acknowledgements)
                controller.acknowledgeRecorded(input.a, token())
                controller.acknowledgeRecorded(input.a, token()); runCurrent()
                assertEquals(listOf(input.a), port.acknowledgements)
                assertEquals(emptyList(), port.submissions)
            }
        }
        // These prequalified UI records do not establish typed parsing or durable remember-before-ACK.
    }
}
