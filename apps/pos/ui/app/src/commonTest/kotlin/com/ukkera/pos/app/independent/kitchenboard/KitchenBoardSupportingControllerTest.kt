@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.independent.kitchenboard

import com.ukkera.pos.app.*
import com.ukkera.pos.app.independent.kitchenboard.KbaFixture.Companion.withKitchenBoard
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Requirements-derived supporting slices. These do not replace missing frozen
 * predetermined Stage IDs, a canonical cancellation-state binding or whole cases.
 */
class KitchenBoardSupportingControllerTest {
    @Test
    fun supportingSelectedTaskWithTheWrongActionFormCannotOpenAReview() = runTest {
        withKitchenBoard { fixture ->
            fixture.port.formSource = { listOf(KbaInputs.form(it, LocalAction.FOOD_STATION_READY)) }
            fixture.controller.chooseKitchenTask(KbaInputs.queued, LocalAction.FOOD_STATION_START, fixture.token())
            runCurrent()
            assertEquals(KbaInputs.task, fixture.port.reads.last().food)
            assertKbaRefused(fixture.controller)
            fixture.assertNoNewOriginalsOrCommands()
        }
    }

    @Test
    fun supportingAnExactTaskFormWithoutExplicitConfirmationIsRefused() = runTest {
        withKitchenBoard { fixture ->
            fixture.port.formSource = { listOf(KbaInputs.form(it, LocalAction.FOOD_STATION_START).copy(confirmation = null)) }
            fixture.controller.chooseKitchenTask(KbaInputs.queued, LocalAction.FOOD_STATION_START, fixture.token())
            runCurrent()
            assertEquals(KbaInputs.task, fixture.port.reads.last().food)
            assertKbaRefused(fixture.controller)
            fixture.assertNoNewOriginalsOrCommands()
        }
    }

    @Test
    fun supportingWrongReviewCoordinatesCannotRebaseAnOtherwiseMatchingFreshTask() = runTest {
        val replacements = listOf(
            LocalField.ORDER_ID to LocalScalarValue.Identifier(KbaInputs.otherOrder),
            LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION to LocalScalarValue.Integer(42),
            LocalField.PORTION_ID to LocalScalarValue.Identifier(KbaInputs.otherPortion),
            LocalField.EXPECTED_FOOD_PORTION_DOMAIN_REVISION to LocalScalarValue.Integer(18),
            LocalField.STATION_ID to LocalScalarValue.Identifier(KbaInputs.stationB),
            LocalField.TASK_ID to LocalScalarValue.Identifier(KbaInputs.otherTask),
        )
        replacements.forEach { (field, wrongValue) ->
            withKitchenBoard { fixture ->
                fixture.port.formSource = { row ->
                    val expected = KbaInputs.form(row, LocalAction.FOOD_STATION_START)
                    listOf(expected.copy(fields = expected.fields.map { original ->
                        if (original.field == field) assertIs<LocalFormField.Scalar>(original).copy(initialValue = wrongValue)
                        else original
                    }))
                }
                fixture.controller.chooseKitchenTask(KbaInputs.queued, LocalAction.FOOD_STATION_START, fixture.token())
                runCurrent()
                assertEquals(KbaInputs.task, fixture.port.reads.last().food)
                assertKbaRefused(fixture.controller)
                fixture.assertNoNewOriginalsOrCommands()
            }
        }
    }

    @Test
    fun supportingStartStagesOnceAndExplicitSubmitAndAcknowledgementUseThatGeneratedOriginal() = runTest {
        checkGeneratedOriginalDecisions(KbaInputs.queued, LocalAction.FOOD_STATION_START, KbaInputs.first)
    }

    @Test
    fun supportingReadyStagesOnceAndExplicitSubmitAndAcknowledgementUseThatGeneratedOriginal() = runTest {
        checkGeneratedOriginalDecisions(KbaInputs.started, LocalAction.FOOD_STATION_READY, KbaInputs.paged)
    }

    @Test
    fun supportingPreAcknowledgementReturnReadsTheSamePagedBoardAndKeepsTheRecordedOriginal() = runTest {
        withKitchenBoard(KbaInputs.started, KbaInputs.paged) { fixture ->
            fixture.port.returnRecordedOnSubmit = true
            fixture.controller.chooseKitchenTask(KbaInputs.started, LocalAction.FOOD_STATION_READY, fixture.token())
            runCurrent()
            fixture.controller.confirmForm(true, fixture.token())
            fixture.controller.stage(fixture.token())
            runCurrent()
            val original = fixture.port.stages.single()
            fixture.controller.submit(original.operationId, fixture.token())
            runCurrent()
            val result = assertIs<LocalOperationResult.Recorded>(fixture.controller.state.value.operation)
            assertFalse(fixture.controller.state.value.recordedAcknowledged)
            val held = fixture.port.holdNextRead()
            fixture.controller.returnKitchenBoard(fixture.token())
            runCurrent()
            assertEquals(KbaInputs.paged, held.requested.await().food)
            assertNull(fixture.controller.state.value.page)
            assertEquals(original.operationId, fixture.controller.state.value.operationId)
            assertEquals(result, fixture.controller.state.value.operation)
            assertFalse(fixture.controller.state.value.recordedAcknowledged)
            held.reply.complete(fixture.port.page(KbaInputs.paged, KbaInputs.returnRows))
            runCurrent()
            assertEquals(KbaInputs.paged, fixture.controller.state.value.foodQuery)
            assertEquals(KbaInputs.returnRows, assertIs<LocalFoodUiBody.Tasks>(fixture.controller.state.value.page?.food?.body).tasks)
            assertNull(fixture.controller.state.value.form)
            assertEquals(1, fixture.port.stages.size)
            assertEquals(listOf(original.operationId), fixture.port.submissions)
            assertTrue(fixture.port.acknowledgements.isEmpty())
            fixture.controller.acknowledgeRecorded(original.operationId, fixture.token())
            runCurrent()
            assertEquals(KbaInputs.paged, fixture.port.reads.last().food)
            assertEquals(listOf(original.operationId), fixture.port.acknowledgements)
            assertEquals(listOf(original.operationId), fixture.port.submissions)
            assertEquals(1, fixture.port.stages.size)
        }
    }

    /** This canonical UI state is supporting input; frozen semantic state mapping stays unbound. */
    @Test
    fun supportingCancelledWasteAfterStartHasNoShortcutAndRetainsTheFreshDetailRead() = runTest {
        val row = KbaInputs.queued.copy(taskState = LocalFoodTaskState.CANCELLED,
            preparation = LocalFoodPreparation.WASTE_AFTER_START)
        assertNull(row.kitchenBoardAction())
        withKitchenBoard(row) { fixture ->
            fixture.controller.selectFood(KbaInputs.taskQuery(row), fixture.token())
            runCurrent()
            assertEquals(KbaInputs.taskQuery(row), fixture.port.reads.last().food)
            assertEquals(listOf(row), assertIs<LocalFoodUiBody.Tasks>(fixture.controller.state.value.page?.food?.body).tasks)
            assertNull(fixture.controller.state.value.form)
            fixture.assertNoNewOriginalsOrCommands()
        }
    }
}

private suspend fun TestScope.checkGeneratedOriginalDecisions(row: LocalFoodUiTask, action: LocalAction,
    query: LocalFoodUiQuery.StationTasks) {
    withKitchenBoard(row, query) { fixture ->
        fixture.port.returnRecordedOnSubmit = true
        fixture.controller.chooseKitchenTask(row, action, fixture.token())
        runCurrent()
        assertKbaReview(fixture.controller, row, action)
        fixture.assertNoNewOriginalsOrCommands()
        fixture.controller.confirmForm(true, fixture.token())
        runCurrent()
        fixture.assertNoNewOriginalsOrCommands()
        fixture.controller.stage(fixture.token())
        runCurrent()
        val original = fixture.port.stages.single()
        // The generated value is observed only as a supporting identity boundary.
        // KBA-009's separately frozen predetermined-ID assertion remains unbound.
        assertEquals(original.operationId, fixture.controller.state.value.operationId)
        assertEquals(action, original.action)
        assertEquals(1, original.formVersion)
        assertEquals(KbaInputs.values(row), original.values)
        assertNull(original.reference)
        assertNull(original.lineField)
        assertTrue(original.lines.isEmpty())
        assertEquals(KbaInputs.actorA, fixture.runtime.presentation.value.display?.actorId)
        assertEquals(KbaInputs.actorA, assertNotNull(fixture.port.original).actor)
        assertTrue(fixture.port.submissions.isEmpty())
        fixture.controller.stage(fixture.token())
        runCurrent()
        assertEquals(1, fixture.port.stages.size)
        assertTrue(fixture.port.submissions.isEmpty())
        fixture.controller.submit(original.operationId, fixture.token())
        runCurrent()
        assertEquals(listOf(original.operationId), fixture.port.submissions)
        assertEquals(original.operationId,
            assertIs<LocalOperationResult.Recorded>(fixture.controller.state.value.operation).operationId)
        assertTrue(fixture.port.acknowledgements.isEmpty())
        fixture.controller.acknowledgeRecorded(original.operationId, fixture.token())
        runCurrent()
        assertEquals(listOf(original.operationId), fixture.port.acknowledgements)
        assertEquals(listOf(original.operationId), fixture.port.submissions)
        assertEquals(1, fixture.port.stages.size)
        assertEquals(query, fixture.port.reads.last().food)
        assertEquals(query, fixture.controller.state.value.foodQuery)
    }
}
