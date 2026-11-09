@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.independent.kitchenboard

import com.ukkera.pos.app.*
import com.ukkera.pos.app.independent.kitchenboard.KbaFixture.Companion.withKitchenBoard
import com.ukkera.pos.app.navigation.SecurityRoot
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Controlled L1 slices of kitchen-board-actions/v1, KBA-001–009.
 * Frozen inputs/assertions are unchanged. No controller result qualifies a Master,
 * ledger, native credential/storage, admitted RPC or installed screen outcome.
 */
class KitchenBoardFrozenControllerTest {
    @Test
    fun kba001StartRequiresAnExactFreshTaskAndOpensOnlyItsUnconfirmedReview() = runTest {
        withKitchenBoard { fixture ->
            val held = fixture.port.holdNextRead()
            fixture.controller.chooseKitchenTask(KbaInputs.queued, LocalAction.FOOD_STATION_START, fixture.token())
            runCurrent()
            assertEquals(KbaInputs.task, held.requested.await().food)
            assertNull(fixture.controller.state.value.page)
            assertNull(fixture.controller.state.value.form)
            assertNull(fixture.controller.state.value.operationId)
            fixture.assertNoNewOriginalsOrCommands()
            held.reply.complete(fixture.port.taskPage(KbaInputs.queued, LocalAction.FOOD_STATION_START))
            runCurrent()
            assertKbaReview(fixture.controller, KbaInputs.queued, LocalAction.FOOD_STATION_START)
            assertEquals(KbaInputs.first, fixture.controller.state.value.kitchenBoardOrigin)
            fixture.assertNoNewOriginalsOrCommands()
        }
    }

    @Test
    fun kba002ReadyRetainsItsPagedOriginAndOpensOnlyTheExactReadyReview() = runTest {
        withKitchenBoard(KbaInputs.started, KbaInputs.paged) { fixture ->
            val held = fixture.port.holdNextRead()
            fixture.controller.chooseKitchenTask(KbaInputs.started, LocalAction.FOOD_STATION_READY, fixture.token())
            runCurrent()
            assertEquals(KbaInputs.task, held.requested.await().food)
            assertNull(fixture.controller.state.value.form)
            fixture.assertNoNewOriginalsOrCommands()
            held.reply.complete(fixture.port.taskPage(KbaInputs.started, LocalAction.FOOD_STATION_READY))
            runCurrent()
            assertKbaReview(fixture.controller, KbaInputs.started, LocalAction.FOOD_STATION_READY)
            assertEquals(KbaInputs.paged, fixture.controller.state.value.kitchenBoardOrigin)
            fixture.assertNoNewOriginalsOrCommands()
        }
    }

    @Test
    fun kba003QueuedQueuedOffersStartAndRetainsExplicitOpenTask() = runTest {
        checkOpenTask(KbaInputs.queued, LocalAction.FOOD_STATION_START, LocalAction.FOOD_STATION_START)
    }

    @Test
    fun kba003StartedPreparingOffersReadyAndRetainsExplicitOpenTask() = runTest {
        checkOpenTask(KbaInputs.started, LocalAction.FOOD_STATION_READY, LocalAction.FOOD_STATION_READY)
    }

    @Test
    fun kba003RemainingQueuedPreparingHasNoShortcutAndKeepsTheDetailStartPath() = runTest {
        checkOpenTask(KbaInputs.queued.copy(preparation = LocalFoodPreparation.PREPARING), null,
            LocalAction.FOOD_STATION_START)
    }

    @Test
    fun kba003ReadyReadyHasNoShortcutAndRetainsExplicitOpenTask() = runTest {
        checkOpenTask(KbaInputs.queued.copy(taskState = LocalFoodTaskState.READY,
            preparation = LocalFoodPreparation.READY), null, null)
    }

    @Test
    fun kba004StationSubstitutionRefusesTheSelectedStartReview() = runTest {
        checkMismatch(KbaInputs.queued.copy(stationId = KbaInputs.stationB))
    }

    @Test
    fun kba004TaskSubstitutionRefusesTheSelectedStartReview() = runTest {
        checkMismatch(KbaInputs.queued.copy(taskId = KbaInputs.otherTask))
    }

    @Test
    fun kba004OrderSubstitutionRefusesTheSelectedStartReview() = runTest {
        checkMismatch(KbaInputs.queued.copy(orderId = KbaInputs.otherOrder))
    }

    @Test
    fun kba004PortionSubstitutionRefusesTheSelectedStartReview() = runTest {
        checkMismatch(KbaInputs.queued.copy(portionId = KbaInputs.otherPortion))
    }

    @Test
    fun kba004OrderRevision42DoesNotRebaseTheSelectedRevision41Review() = runTest {
        checkMismatch(KbaInputs.queued.copy(orderStorageRevision = Revision(42)))
    }

    @Test
    fun kba004PreparationRevision18DoesNotRebaseTheSelectedRevision17Review() = runTest {
        checkMismatch(KbaInputs.queued.copy(preparationDomainRevision = Revision(18)))
    }

    @Test
    fun kba004TaskStateSubstitutionRefusesTheSelectedStartReview() = runTest {
        checkMismatch(KbaInputs.queued.copy(taskState = LocalFoodTaskState.STARTED))
    }

    @Test
    fun kba004PreparationStateSubstitutionRefusesTheSelectedStartReview() = runTest {
        checkMismatch(KbaInputs.queued.copy(preparation = LocalFoodPreparation.PREPARING))
    }

    @Test
    fun kba004ReadyOrderRevision43DoesNotRebaseTheSelectedRevision42Review() = runTest {
        checkMismatch(KbaInputs.started.copy(orderStorageRevision = Revision(43)),
            KbaInputs.started, LocalAction.FOOD_STATION_READY, KbaInputs.paged)
    }

    @Test
    fun kba005StationTasksReplyCannotSubstituteForTheFreshTaskOrUseTheOlderTask() = runTest {
        withKitchenBoard { fixture ->
            prepareOlderTask(fixture)
            val held = chooseHeldStart(fixture)
            val readsBeforeReply = fixture.port.reads.size
            held.reply.complete(fixture.port.page(KbaInputs.first, listOf(KbaInputs.queued)))
            runCurrent()
            assertKbaRefused(fixture.controller)
            assertEquals(readsBeforeReply, fixture.port.reads.size, "No fallback read")
            fixture.assertNoNewOriginalsOrCommands()
        }
    }

    /** Constructor-only refusal: this missing Task never reaches the controller port. */
    @Test
    fun kba005MissingTaskIsRejectedByTheTypedPageConstructorOnly() {
        assertFailsWith<IllegalArgumentException> { KbaPort().page(KbaInputs.task, emptyList()) }
    }

    @Test
    fun kba005UnavailableFreshTaskCannotFallBackToTheOlderTask() = runTest {
        checkFailedTaskRead(LocalServiceProblem.UNAVAILABLE)
    }

    @Test
    fun kba005DeniedFreshTaskCannotExposeTheOlderTaskOrAReview() = runTest {
        checkFailedTaskRead(LocalServiceProblem.DENIED)
    }

    @Test
    fun kba005ExpiredSessionClearsTheTaskAndReviewInsteadOfUsingTheOlderTask() = runTest {
        withKitchenBoard { fixture ->
            prepareOlderTask(fixture)
            val held = chooseHeldStart(fixture)
            held.reply.complete(LocalReadResult.Unavailable(LocalServiceProblem.SESSION_ENDED))
            runCurrent()
            assertEquals(SecurityRoot.BOOTSTRAP, fixture.runtime.navigator.state.value.root)
            assertEquals(LocalServiceProblem.SESSION_ENDED, fixture.runtime.presentation.value.sessionProblem)
            assertTrue(fixture.runtime.presentation.value.locallyLocked)
            assertNull(fixture.controller.state.value.page)
            assertNull(fixture.controller.state.value.form)
            fixture.assertNoNewOriginalsOrCommands()
        }
    }

    @Test
    fun kba006DuplicateStartActivationKeepsOneUnconfirmedReviewAndStagesNothing() = runTest {
        withKitchenBoard { fixture ->
            val held = chooseHeldStart(fixture)
            fixture.controller.chooseKitchenTask(KbaInputs.queued, LocalAction.FOOD_STATION_START, fixture.token())
            runCurrent()
            fixture.assertNoNewOriginalsOrCommands()
            held.reply.complete(fixture.port.taskPage(KbaInputs.queued, LocalAction.FOOD_STATION_START))
            runCurrent()
            val entry = fixture.runtime.navigator.state.value.activeEntry.id
            assertKbaReview(fixture.controller, KbaInputs.queued, LocalAction.FOOD_STATION_START)
            val readCount = fixture.port.reads.size
            fixture.controller.chooseKitchenTask(KbaInputs.queued, LocalAction.FOOD_STATION_START, fixture.token())
            runCurrent()
            assertEquals(entry, fixture.runtime.navigator.state.value.activeEntry.id)
            assertEquals(readCount, fixture.port.reads.size)
            assertKbaReview(fixture.controller, KbaInputs.queued, LocalAction.FOOD_STATION_START)
            fixture.assertNoNewOriginalsOrCommands()
        }
    }

    @Test
    fun kba006ValidTaskCoordinatesDoNotAuthorizeTheWrongRequestedShortcut() = runTest {
        listOf(KbaInputs.queued to LocalAction.FOOD_STATION_READY,
            KbaInputs.started to LocalAction.FOOD_STATION_START).forEach { (row, wrong) ->
            withKitchenBoard(row, if (row == KbaInputs.started) KbaInputs.paged else KbaInputs.first) { fixture ->
                val readCount = fixture.port.reads.size
                fixture.controller.chooseKitchenTask(row, wrong, fixture.token())
                runCurrent()
                assertNull(fixture.controller.state.value.form)
                assertNull(fixture.controller.state.value.operationId)
                assertEquals(readCount, fixture.port.reads.size)
                fixture.assertNoNewOriginalsOrCommands()
            }
        }
    }

    @Test
    fun kba007BackRemovalDiscardsTheLateSuccessfulTaskWithoutNavigation() = runTest {
        withKitchenBoard { fixture ->
            val old = fixture.controller
            val held = chooseHeldStart(fixture)
            fixture.leaveKitchen()
            val entry = fixture.runtime.navigator.state.value.activeEntry.id
            held.reply.complete(fixture.port.taskPage(KbaInputs.queued, LocalAction.FOOD_STATION_START))
            runCurrent()
            held.returned.await()
            assertEquals(entry, fixture.runtime.navigator.state.value.activeEntry.id)
            assertNull(old.state.value.page)
            assertNull(old.state.value.form)
            fixture.assertNoNewOriginalsOrCommands()
        }
    }

    @Test
    fun kba007CancelledReadAfterBackRemovalCannotProduceAReviewOrSuccess() = runTest {
        withKitchenBoard { fixture ->
            val old = fixture.controller
            val held = chooseHeldStart(fixture)
            fixture.leaveKitchen()
            val entry = fixture.runtime.navigator.state.value.activeEntry.id
            held.reply.cancel(CancellationException("Frozen cancelled-read schedule"))
            runCurrent()
            held.returned.await()
            assertEquals(entry, fixture.runtime.navigator.state.value.activeEntry.id)
            assertNull(old.state.value.page)
            assertNull(old.state.value.form)
            assertNull(old.state.value.operation)
            fixture.assertNoNewOriginalsOrCommands()
        }
    }

    @Test
    fun kba007LateStationATaskCannotOverwriteTheCompletedStationBSelection() = runTest {
        withKitchenBoard { fixture ->
            val old = fixture.controller
            val held = chooseHeldStart(fixture)
            fixture.leaveKitchen()
            val current = fixture.openKitchen(KbaInputs.otherStation)
            current.chooseKitchenTask(KbaInputs.stationBQueued, LocalAction.FOOD_STATION_START, fixture.token())
            runCurrent()
            assertKbaReview(current, KbaInputs.stationBQueued, LocalAction.FOOD_STATION_START)
            val entry = fixture.runtime.navigator.state.value.activeEntry.id
            held.reply.complete(fixture.port.taskPage(KbaInputs.queued, LocalAction.FOOD_STATION_START))
            runCurrent()
            held.returned.await()
            assertEquals(entry, fixture.runtime.navigator.state.value.activeEntry.id)
            assertKbaReview(current, KbaInputs.stationBQueued, LocalAction.FOOD_STATION_START)
            assertEquals(KbaInputs.otherStation, current.state.value.kitchenBoardOrigin)
            assertNull(old.state.value.page)
            assertNull(old.state.value.form)
            fixture.assertNoNewOriginalsOrCommands()
        }
    }

    @Test
    fun kba007LateActorATaskCannotAttachToActorBAfterAccountSwitch() = runTest {
        withKitchenBoard { fixture ->
            val old = fixture.controller
            val staleReply = fixture.port.taskPage(KbaInputs.queued, LocalAction.FOOD_STATION_START)
            val held = chooseHeldStart(fixture)
            val deniedB = KbaPort(KbaInputs.actorB).apply { readProblem = LocalServiceProblem.DENIED }
            val current = fixture.switchAccount(deniedB, KbaInputs.first)
            assertEquals(KbaInputs.actorB, fixture.runtime.presentation.value.display?.actorId)
            val entry = fixture.runtime.navigator.state.value.activeEntry.id
            held.reply.complete(staleReply)
            runCurrent()
            held.returned.await()
            assertEquals(entry, fixture.runtime.navigator.state.value.activeEntry.id)
            assertNull(current.state.value.page)
            assertNull(current.state.value.form)
            assertNull(current.state.value.operationId)
            assertNull(old.state.value.page)
            fixture.assertNoNewOriginalsOrCommands()
        }
    }

    @Test
    fun kba007ChangedAuthorityContextCannotAdoptTheHeldOldAllowedTask() = runTest {
        withKitchenBoard { fixture ->
            val old = fixture.controller
            val staleReply = fixture.port.taskPage(KbaInputs.queued, LocalAction.FOOD_STATION_START)
            val held = chooseHeldStart(fixture)
            val denied = KbaPort().apply { readProblem = LocalServiceProblem.DENIED }
            val current = fixture.replaceAttachment(denied, LocalSessionDraftEpochs(2, 2))
            val entry = fixture.runtime.navigator.state.value.activeEntry.id
            held.reply.complete(staleReply)
            runCurrent()
            held.returned.await()
            assertEquals(entry, fixture.runtime.navigator.state.value.activeEntry.id)
            assertNull(current.state.value.page)
            assertNull(current.state.value.form)
            assertNull(current.state.value.operationId)
            assertNull(old.state.value.page)
            fixture.assertNoNewOriginalsOrCommands()
        }
    }

    /** Attachment generation is controlled here; actual native connection close is unbound. */
    @Test
    fun kba007ReplacementAttachmentReentersFreshlyAndDiscardsTheOldTaskReply() = runTest {
        withKitchenBoard { fixture ->
            val old = fixture.controller
            val staleReply = fixture.port.taskPage(KbaInputs.queued, LocalAction.FOOD_STATION_START)
            val held = chooseHeldStart(fixture)
            val fresh = KbaPort()
            val current = fixture.replaceAttachment(fresh)
            assertTrue(current.state.value.attachmentReviewRequired)
            assertNull(current.state.value.page)
            assertNull(current.state.value.form)
            current.selectFood(KbaInputs.otherStation, fixture.token())
            runCurrent()
            assertFalse(current.state.value.attachmentReviewRequired)
            assertEquals(KbaInputs.otherStation, fresh.reads.last().food)
            current.chooseKitchenTask(KbaInputs.stationBQueued, LocalAction.FOOD_STATION_START, fixture.token())
            runCurrent()
            assertKbaReview(current, KbaInputs.stationBQueued, LocalAction.FOOD_STATION_START)
            val entry = fixture.runtime.navigator.state.value.activeEntry.id
            held.reply.complete(staleReply)
            runCurrent()
            held.returned.await()
            assertEquals(entry, fixture.runtime.navigator.state.value.activeEntry.id)
            assertKbaReview(current, KbaInputs.stationBQueued, LocalAction.FOOD_STATION_START)
            assertNull(old.state.value.page)
            assertNull(old.state.value.form)
            fixture.assertNoNewOriginalsOrCommands()
        }
    }

    @Test
    fun kba008CancelReviewReadsTheExactFirstPageAndReplacesItsFormerRows() = runTest {
        checkReturn(KbaInputs.first)
    }

    @Test
    fun kba008CancelReviewReadsTheExactPagedSelectorAndReplacesItsFormerRows() = runTest {
        checkReturn(KbaInputs.paged)
    }

    @Test
    fun kba008FailedPagedReturnCannotReuseTheOldBoardAsCurrentEvidence() = runTest {
        withKitchenBoard(query = KbaInputs.paged) { fixture ->
            openStart(fixture)
            val held = fixture.port.holdNextRead()
            fixture.controller.discardForm(fixture.token())
            runCurrent()
            assertEquals(KbaInputs.paged, held.requested.await().food)
            assertNull(fixture.controller.state.value.page)
            assertNull(fixture.controller.state.value.form)
            held.reply.complete(LocalReadResult.Unavailable(LocalServiceProblem.UNAVAILABLE))
            runCurrent()
            assertEquals(KbaInputs.paged, fixture.controller.state.value.foodQuery)
            assertKbaRefused(fixture.controller)
            fixture.assertNoNewOriginalsOrCommands()
        }
    }

    /** Frozen predetermined-ID Stage/Submit remains unbound; this method stops at confirmation. */
    @Test
    fun kba009StartReviewAndConfirmationDoNotAllocateOrSubmitAnOriginal() = runTest {
        checkThroughConfirmation(KbaInputs.queued, LocalAction.FOOD_STATION_START, KbaInputs.first)
    }

    /** Frozen predetermined-ID Stage/Submit remains unbound; this method stops at confirmation. */
    @Test
    fun kba009ReadyReviewAndConfirmationDoNotAllocateOrSubmitAnOriginal() = runTest {
        checkThroughConfirmation(KbaInputs.started, LocalAction.FOOD_STATION_READY, KbaInputs.paged)
    }
}

private suspend fun TestScope.checkOpenTask(row: LocalFoodUiTask, shortcut: LocalAction?, detailAction: LocalAction?) {
    assertEquals(shortcut, row.kitchenBoardAction())
    withKitchenBoard(row, if (row == KbaInputs.started) KbaInputs.paged else KbaInputs.first) { fixture ->
        val held = fixture.port.holdNextRead()
        fixture.controller.selectFood(KbaInputs.taskQuery(row), fixture.token())
        runCurrent()
        assertEquals(KbaInputs.taskQuery(row), held.requested.await().food)
        assertNull(fixture.controller.state.value.page)
        assertNull(fixture.controller.state.value.form)
        fixture.assertNoNewOriginalsOrCommands()
        held.reply.complete(fixture.port.taskPage(row, detailAction))
        runCurrent()
        assertEquals(listOf(row), assertIs<LocalFoodUiBody.Tasks>(fixture.controller.state.value.page?.food?.body).tasks)
        assertNull(fixture.controller.state.value.form, "Open task does not auto-open an action")
        if (detailAction != null) {
            fixture.controller.openForm(detailAction, fixture.token())
            assertKbaReview(fixture.controller, row, detailAction)
        }
        fixture.assertNoNewOriginalsOrCommands()
    }
}

private suspend fun TestScope.checkMismatch(replyRow: LocalFoodUiTask, selected: LocalFoodUiTask = KbaInputs.queued,
    action: LocalAction = LocalAction.FOOD_STATION_START, query: LocalFoodUiQuery.StationTasks = KbaInputs.first) {
    withKitchenBoard(selected, query) { fixture ->
        val held = fixture.port.holdNextRead()
        fixture.controller.chooseKitchenTask(selected, action, fixture.token())
        runCurrent()
        assertEquals(KbaInputs.taskQuery(selected), held.requested.await().food)
        // A station/task substitution necessarily has its own valid echoed Task selector.
        // An exact requested selector with a different station/task cannot be constructed.
        held.reply.complete(fixture.port.taskPage(replyRow, action))
        runCurrent()
        assertKbaRefused(fixture.controller)
        fixture.assertNoNewOriginalsOrCommands()
    }
}

private fun TestScope.prepareOlderTask(fixture: KbaFixture) {
    fixture.controller.selectFood(KbaInputs.task, fixture.token())
    runCurrent()
    assertEquals(listOf(KbaInputs.queued), assertIs<LocalFoodUiBody.Tasks>(fixture.controller.state.value.page?.food?.body).tasks)
    fixture.controller.returnKitchenBoard(fixture.token())
    runCurrent()
    assertEquals(KbaInputs.first, fixture.controller.state.value.foodQuery)
    fixture.assertNoNewOriginalsOrCommands()
}

private suspend fun TestScope.chooseHeldStart(fixture: KbaFixture): KbaHeldRead {
    val held = fixture.port.holdNextRead()
    fixture.controller.chooseKitchenTask(KbaInputs.queued, LocalAction.FOOD_STATION_START, fixture.token())
    runCurrent()
    assertEquals(KbaInputs.task, held.requested.await().food)
    assertNull(fixture.controller.state.value.page)
    assertNull(fixture.controller.state.value.form)
    assertNull(fixture.controller.state.value.operationId)
    return held
}

private suspend fun TestScope.checkFailedTaskRead(problem: LocalServiceProblem) {
    withKitchenBoard { fixture ->
        prepareOlderTask(fixture)
        val held = chooseHeldStart(fixture)
        val readCount = fixture.port.reads.size
        held.reply.complete(LocalReadResult.Unavailable(problem))
        runCurrent()
        assertKbaRefused(fixture.controller)
        assertEquals(problem, fixture.controller.state.value.readProblem)
        assertEquals(readCount, fixture.port.reads.size)
        fixture.assertNoNewOriginalsOrCommands()
    }
}

private fun TestScope.openStart(fixture: KbaFixture) {
    fixture.controller.chooseKitchenTask(KbaInputs.queued, LocalAction.FOOD_STATION_START, fixture.token())
    runCurrent()
    assertKbaReview(fixture.controller, KbaInputs.queued, LocalAction.FOOD_STATION_START)
}

private suspend fun TestScope.checkReturn(query: LocalFoodUiQuery.StationTasks) {
    withKitchenBoard(query = query) { fixture ->
        openStart(fixture)
        val held = fixture.port.holdNextRead()
        fixture.controller.discardForm(fixture.token())
        runCurrent()
        assertEquals(query, held.requested.await().food)
        assertEquals(query, fixture.controller.state.value.foodQuery)
        assertNull(fixture.controller.state.value.page)
        assertNull(fixture.controller.state.value.form)
        fixture.assertNoNewOriginalsOrCommands()
        held.reply.complete(fixture.port.page(query, KbaInputs.returnRows))
        runCurrent()
        assertEquals(query, fixture.controller.state.value.foodQuery)
        assertEquals(KbaInputs.returnRows, assertIs<LocalFoodUiBody.Tasks>(fixture.controller.state.value.page?.food?.body).tasks)
        assertNull(fixture.controller.state.value.form)
        assertNull(fixture.controller.state.value.operationId)
        fixture.assertNoNewOriginalsOrCommands()
    }
}

private suspend fun TestScope.checkThroughConfirmation(row: LocalFoodUiTask, action: LocalAction,
    query: LocalFoodUiQuery.StationTasks) {
    withKitchenBoard(row, query) { fixture ->
        fixture.controller.chooseKitchenTask(row, action, fixture.token())
        runCurrent()
        assertKbaReview(fixture.controller, row, action)
        fixture.assertNoNewOriginalsOrCommands()
        fixture.controller.stage(fixture.token())
        runCurrent()
        assertNull(fixture.controller.state.value.operationId)
        assertNotNull(fixture.controller.state.value.issue)
        fixture.assertNoNewOriginalsOrCommands()
        fixture.controller.confirmForm(true, fixture.token())
        runCurrent()
        assertTrue(assertNotNull(fixture.controller.state.value.form).confirmed)
        assertNull(fixture.controller.state.value.operationId)
        assertNull(fixture.controller.state.value.operation)
        fixture.assertNoNewOriginalsOrCommands()
    }
}
