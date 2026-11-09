@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.independent.kitchenboard

import com.ukkera.pos.app.*
import com.ukkera.pos.app.independent.kitchenboard.KbaFixture.Companion.withKitchenBoard
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * KBA-010 L1 recovery/navigation slices using exact frozen existing-original
 * pending/lookup inputs. The prior Stage/Submit and durable retention are inputs,
 * not observed production journal execution. No whole-case result is asserted.
 */
class KitchenBoardFrozenOriginalRecoveryTest {
    @Test
    fun kba010StagedFrozenStartRetainsItsOriginalAcrossNavigationAndAccountIsolation() = runTest {
        checkOriginalRecovery(staged = true)
    }

    @Test
    fun kba010UnknownFrozenReadyRetainsItsOriginalAndResolvesByExplicitOriginalLookup() = runTest {
        checkOriginalRecovery(staged = false)
    }
}

private suspend fun TestScope.checkOriginalRecovery(staged: Boolean) {
    val seed = KbaInputs.original(staged)
    val originalId = seed.intent.operationId
    val originalValues = seed.intent.values
    withKitchenBoard(seed.row, KbaInputs.paged, seed) { fixture ->
        assertEquals(seed.pending(), fixture.controller.state.value.pending.single())
        assertNull(fixture.controller.state.value.operationId, "Navigation does not mint the existing original")
        // Open task preserves the actual current board origin before recovery is displayed.
        fixture.controller.selectFood(KbaInputs.task, fixture.token())
        runCurrent()
        assertEquals(KbaInputs.task, fixture.port.reads.last().food)
        assertEquals(KbaInputs.paged, fixture.controller.state.value.kitchenBoardOrigin)
        fixture.port.lookupResult = if (staged) LocalOperationResult.Pending(seed.pending())
            else LocalOperationResult.Unresolved(originalId, LocalServiceProblem.UNAVAILABLE)
        fixture.controller.lookup(originalId, fixture.token())
        runCurrent()
        assertEquals(originalId, fixture.controller.state.value.operationId)
        if (staged) assertEquals(seed.pending(), assertIs<LocalOperationResult.Pending>(fixture.controller.state.value.operation).operation)
        else assertIs<LocalOperationResult.Unresolved>(fixture.controller.state.value.operation)
        val guardedReadCount = fixture.port.reads.size
        fixture.controller.returnKitchenBoard(fixture.token())
        runCurrent()
        assertEquals(guardedReadCount, fixture.port.reads.size, "A frozen original's existing leave guard is respected")
        assertEquals(originalId, fixture.controller.state.value.operationId)
        fixture.assertNoNewOriginalsOrCommands()

        val old = fixture.controller
        fixture.leaveKitchen()
        fixture.port.boardRows[KbaInputs.stationA] = KbaInputs.returnRows
        val returned = fixture.openKitchen(KbaInputs.paged)
        assertEquals(KbaInputs.paged, fixture.port.reads.last().food)
        assertEquals(KbaInputs.returnRows, assertIs<LocalFoodUiBody.Tasks>(returned.state.value.page?.food?.body).tasks)
        assertNull(returned.state.value.form)
        assertEquals(seed.pending(), returned.state.value.pending.single())
        assertEquals(originalValues, assertNotNull(fixture.port.original).intent.values)
        assertNull(old.state.value.page)
        assertNull(old.state.value.form)
        fixture.assertNoNewOriginalsOrCommands()

        val actorB = KbaPort(KbaInputs.actorB).apply { readProblem = LocalServiceProblem.DENIED }
        val otherEmployee = fixture.switchAccount(actorB, KbaInputs.paged)
        assertEquals(KbaInputs.actorB, fixture.runtime.presentation.value.display?.actorId)
        assertNull(otherEmployee.state.value.page)
        assertNull(otherEmployee.state.value.form)
        assertNull(otherEmployee.state.value.operationId)
        assertNull(otherEmployee.state.value.operation)
        assertTrue(otherEmployee.state.value.pending.isEmpty())
        assertTrue(actorB.lookups.isEmpty())
        assertEquals(seed.actor, assertNotNull(fixture.originalPort.original).actor)
        assertEquals(originalValues, assertNotNull(fixture.originalPort.original).intent.values)
        fixture.assertNoNewOriginalsOrCommands()

        val current = fixture.switchAccount(fixture.originalPort, KbaInputs.paged)
        assertEquals(KbaInputs.actorA, fixture.runtime.presentation.value.display?.actorId)
        assertEquals(KbaInputs.paged, fixture.originalPort.reads.last().food)
        assertEquals(seed.pending(), current.state.value.pending.single())
        assertNull(current.state.value.form)
        fixture.assertNoNewOriginalsOrCommands()
        fixture.originalPort.lookupResult = if (staged) LocalOperationResult.Pending(seed.pending())
            else fixture.originalPort.recorded(originalId).copy(replayed = true)
        current.lookup(originalId, fixture.token())
        runCurrent()
        assertEquals(originalId, current.state.value.operationId)
        assertEquals(listOf(originalId, originalId), fixture.originalPort.lookups)
        assertEquals(seed.actor, assertNotNull(fixture.originalPort.original).actor)
        assertEquals(seed.intent.action, assertNotNull(fixture.originalPort.original).intent.action)
        assertEquals(originalValues, assertNotNull(fixture.originalPort.original).intent.values)
        assertTrue(fixture.originalPort.stages.isEmpty())
        assertTrue(fixture.originalPort.submissions.isEmpty())
        if (staged) {
            assertEquals(seed.pending(), assertIs<LocalOperationResult.Pending>(current.state.value.operation).operation)
            fixture.originalPort.returnRecordedOnSubmit = true
            current.submit(originalId, fixture.token())
            runCurrent()
            assertEquals(listOf(originalId), fixture.originalPort.submissions)
            assertEquals(seed, fixture.originalPort.submittedOriginals.single())
        }
        val result = assertIs<LocalOperationResult.Recorded>(current.state.value.operation)
        assertEquals(originalId, result.operationId)
        assertEquals(!staged, result.replayed)
        assertEquals(originalValues, assertNotNull(fixture.originalPort.original).intent.values)
        assertTrue(fixture.originalPort.stages.isEmpty())
        assertTrue(actorB.stages.isEmpty())
        assertTrue(actorB.submissions.isEmpty())
        assertTrue(fixture.originalPort.acknowledgements.isEmpty())
    }
}
