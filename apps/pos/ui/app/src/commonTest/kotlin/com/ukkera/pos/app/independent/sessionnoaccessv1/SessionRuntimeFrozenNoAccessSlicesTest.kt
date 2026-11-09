package com.ukkera.pos.app.independent.sessionnoaccessv1

import com.ukkera.pos.app.LocalInventorySection
import com.ukkera.pos.app.LocalRecordKind
import com.ukkera.pos.app.LocalRecordReference
import com.ukkera.pos.app.LocalWorkflow
import com.ukkera.pos.app.LocalWorkflowController
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.app.navigation.LeaveGuard
import com.ukkera.pos.app.navigation.NavigationAction
import com.ukkera.pos.app.navigation.NavigationToken
import com.ukkera.pos.app.navigation.SecurityRoot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Runtime/navigation/controller slices of immutable LSNA v1.0.0. Fixed A identity and selected
 * P10/P11/P12 presentation inputs feed actual common paths. The original supported search is
 * entered through the controller. An existing production DIRTY guard/confirmation is bound to a
 * controlled draft flag: the full quantity-3 cart/form has no supplied production binding.
 *
 * The deterministic late response is a controlled unavailable SKU-cost read. No successful cost
 * or branch valuation report, export, subscription, pending payment body/result, monetary effect,
 * protected storage, master activation, admitted RPC, rendered frame/semantics or native input
 * is qualified. All six whole cases remain NOT_RUN; OM-01 and OM-02 remain unresolved.
 *
 * Suspended authentication-route restoration across Current/mayRestore generations has no
 * explicit schedule in this freeze. These methods do not invent or qualify that separate race.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionRuntimeFrozenNoAccessSlicesTest {
    @Test
    fun lsna01Steps3To5_existingCurrentNoAreasRetiresSearchAndBusinessControllers() = runTest {
        withFixture { fixture ->
            val original = preparePrivateSearchAndConfirmation(fixture)
            val readsBefore = fixture.session.readCalls
            val pendingBefore = fixture.session.pendingCalls
            fixture.session.publishCurrent(FrozenNoAccessUiInputs.p11Display())
            runCurrent()

            assertSafeNoAreas(fixture, FrozenNoAccessUiInputs.LSNA01_S4)
            assertRetiredSearch(original, FrozenNoAccessUiInputs.LSNA01_S3)
            assertTrue(fixture.runtime.navigator.state.value.stacks.values.flatten().none { it.id in original.entryIds },
                FrozenNoAccessUiInputs.LSNA01_S3.message("all former private entry identities are retired"))
            val acquisitionsBefore = fixture.session.workflowAcquisitions
            assertNull(fixture.runtime.localSessionFor(fixture.runtime.navigator.token()),
                FrozenNoAccessUiInputs.LSNA01_S4.message("the session-control entry has no business session binding"))
            assertNull(fixture.activeEntry().localWorkflows(fixture.runtime, fixture.runtime.navigator.token()),
                FrozenNoAccessUiInputs.LSNA01_S4.message("the session-control entry cannot acquire a workflow controller"))
            assertNull(fixture.activeEntry().localAdministration(fixture.runtime, fixture.runtime.navigator.token()),
                FrozenNoAccessUiInputs.LSNA01_S4.message("the session-control entry cannot acquire administration"))
            assertEquals(acquisitionsBefore, fixture.session.workflowAcquisitions,
                FrozenNoAccessUiInputs.LSNA01_S4.message("the status entry has acquired no business adapter"))

            for (area in FrozenNoAccessUiInputs.p10Display().allowedAreas) {
                fixture.runtime.navigate(NavigationAction.SelectArea(area), fixture.runtime.navigator.token())
                assertSafeNoAreas(fixture, FrozenNoAccessUiInputs.LSNA01_S4)
            }
            original.controller.changeSearch(FrozenNoAccessUiInputs.PRIVATE_SEARCH, original.token)
            original.controller.refresh(original.token)
            runCurrent()
            assertRetiredSearch(original, FrozenNoAccessUiInputs.LSNA01_S3)
            assertEquals(readsBefore, fixture.session.readCalls,
                FrozenNoAccessUiInputs.LSNA01_S5.message("known no-area status starts no new controller read"))
            assertEquals(pendingBefore, fixture.session.pendingCalls,
                FrozenNoAccessUiInputs.LSNA01_S5.message("known no-area status starts no new pending read"))
            assertNoAutomaticWork(fixture, FrozenNoAccessUiInputs.LSNA01_S5)
        }
    }

    @Test
    fun lsna02Step4_confirmationBeforeControlledSafeErrorCannotRestoreRetiredState() = runTest {
        withFixture { fixture ->
            val departed = prepareDepartedCostRead(fixture)
            val original = preparePrivateSearchAndConfirmation(fixture)
            fixture.session.publishCurrent(FrozenNoAccessUiInputs.p11Display())
            runCurrent()
            assertSafeNoAreas(fixture, FrozenNoAccessUiInputs.LSNA01_S4)
            val retiredCostBefore = departed.controller.state.value

            // Frozen LSNA-02 step 4 error ordering: original confirmation before old error.
            fixture.runtime.confirmLeave(original.confirmationId)
            departed.reply.release()
            runCurrent()
            assertEquals(retiredCostBefore, departed.controller.state.value,
                FrozenNoAccessUiInputs.LSNA02_S4.message("the late error cannot repopulate a retired cost controller"))
            assertSafeNoAreas(fixture, FrozenNoAccessUiInputs.LSNA02_S4)
            assertRetiredSearch(original, FrozenNoAccessUiInputs.LSNA02_S4)
            fixture.runtime.navigator.cancelLeave(original.confirmationId)
            fixture.runtime.navigate(NavigationAction.Back, original.token)
            fixture.runtime.navigate(NavigationAction.SelectArea(AppArea.STOCK), fixture.runtime.navigator.token())
            fixture.runtime.navigate(NavigationAction.SelectArea(AppArea.REPORTS), fixture.runtime.navigator.token())
            assertSafeNoAreas(fixture, FrozenNoAccessUiInputs.LSNA02_S5)
            assertNoAutomaticWork(fixture, FrozenNoAccessUiInputs.LSNA02_S4)
        }
    }

    @Test
    fun lsna05Steps2To5_controlledSafeErrorBeforeFreshEntryDoesNotRestoreRetiredSearch() = runTest {
        regrantWithoutRestoration(errorAfterFreshEntry = false)
    }

    @Test
    fun lsna05Steps2To5_controlledSafeErrorAfterFreshEntryDoesNotAlterFreshSearch() = runTest {
        regrantWithoutRestoration(errorAfterFreshEntry = true)
    }

    @Test
    fun lsna04Step4_explicitLogoutHidesPrivateUiBeforeUnavailableRemoteCompletion() = runTest {
        withFixture { fixture ->
            val original = preparePrivateSearchAndConfirmation(fixture)
            fixture.session.publishCurrent(FrozenNoAccessUiInputs.p11Display())
            runCurrent()
            assertSafeNoAreas(fixture, FrozenNoAccessUiInputs.LSNA01_S4)
            val held = fixture.session.holdUnavailableLogout()
            // This is the same registered action bound by the status surface's Logout button.
            fixture.runtime.navigate(NavigationAction.ExitWorkspace, fixture.runtime.navigator.token())
            assertEquals(SecurityRoot.BOOTSTRAP, fixture.runtime.navigator.state.value.root,
                FrozenNoAccessUiInputs.LSNA04_S4.message("explicit logout clears local navigation immediately"))
            assertNull(fixture.runtime.presentation.value.display,
                FrozenNoAccessUiInputs.LSNA04_S4.message("local authentication/private display is hidden before remote completion"))
            assertTrue(fixture.runtime.presentation.value.locallyLocked,
                FrozenNoAccessUiInputs.LSNA04_S4.message("local closure does not wait for a remote reply"))
            assertRetiredSearch(original, FrozenNoAccessUiInputs.LSNA04_S4)
            runCurrent()
            held.captured.await()
            assertTrue(fixture.runtime.presentation.value.revocationPending,
                FrozenNoAccessUiInputs.LSNA04_S4.message("in-flight remote revocation is still pending"))
            assertEquals(1, fixture.session.logoutCalls,
                FrozenNoAccessUiInputs.LSNA04_S4.message("only the explicit revocation attempt is started"))
            held.release()
            runCurrent()
            assertTrue(fixture.runtime.presentation.value.cleanupNeedsAttention,
                FrozenNoAccessUiInputs.LSNA04_S4.message("an unavailable remote result is retained as cleanup attention"))
            assertNull(fixture.runtime.presentation.value.display,
                FrozenNoAccessUiInputs.LSNA04_S4.message("unavailable cleanup does not restore the old session UI"))
            assertNoAutomaticWork(fixture, FrozenNoAccessUiInputs.LSNA04_S4)
        }
    }

    private suspend fun TestScope.regrantWithoutRestoration(errorAfterFreshEntry: Boolean) {
        withFixture { fixture ->
            val departed = prepareDepartedCostRead(fixture)
            val original = preparePrivateSearchAndConfirmation(fixture)
            fixture.session.publishCurrent(FrozenNoAccessUiInputs.p11Display())
            runCurrent()
            assertSafeNoAreas(fixture, FrozenNoAccessUiInputs.LSNA01_S4)
            fixture.runtime.navigate(NavigationAction.SelectArea(AppArea.CASHIER), fixture.runtime.navigator.token())
            assertSafeNoAreas(fixture, FrozenNoAccessUiInputs.LSNA05_S2)
            val readsBeforeRegrant = fixture.session.readCalls
            fixture.session.publishCurrent(FrozenNoAccessUiInputs.p12Display())
            runCurrent()
            assertEquals(FrozenNoAccessUiInputs.p12Display().allowedAreas,
                assertNotNull(fixture.runtime.presentation.value.display).allowedAreas,
                FrozenNoAccessUiInputs.LSNA05_S4.message("the current narrow presentation has no old cost/cash/report area"))
            assertEquals(AppRoute.Cashier, fixture.runtime.navigator.state.value.activeEntry.route,
                FrozenNoAccessUiInputs.LSNA05_S4.message("the regranted cashier has a fresh root route"))
            assertTrue(fixture.runtime.navigator.state.value.stacks.values.flatten().none { it.id in original.entryIds },
                FrozenNoAccessUiInputs.LSNA05_S5.message("none of the retired private entries is restored by regrant"))
            assertEquals(readsBeforeRegrant, fixture.session.readCalls,
                FrozenNoAccessUiInputs.LSNA05_S6.message("runtime adoption alone does not start an old protected read"))
            fixture.runtime.confirmLeave(original.confirmationId)
            if (!errorAfterFreshEntry) {
                departed.reply.release()
                runCurrent()
            }

            val fresh = fixture.activeController()
            fresh.activate(LocalWorkflow.CHECKOUT)
            runCurrent()
            assertEquals("", fresh.state.value.search,
                FrozenNoAccessUiInputs.LSNA05_S4.message("explicit fresh entry has no restored original search"))
            assertNull(fresh.state.value.form,
                FrozenNoAccessUiInputs.LSNA05_S4.message("no retired form/confirmation is attached to fresh entry"))
            assertNull(fixture.runtime.navigator.state.value.confirmation,
                FrozenNoAccessUiInputs.LSNA05_S4.message("old leave consent does not become fresh confirmation"))
            val freshBeforeLateWork = fresh.state.value
            if (errorAfterFreshEntry) {
                departed.reply.release()
                runCurrent()
            }
            original.controller.changeSearch(FrozenNoAccessUiInputs.PRIVATE_SEARCH, original.token)
            fixture.runtime.confirmLeave(original.confirmationId)
            fixture.runtime.navigate(NavigationAction.Back, original.token)
            fixture.runtime.navigate(NavigationAction.SelectArea(AppArea.REPORTS), fixture.runtime.navigator.token())
            runCurrent()
            assertEquals(freshBeforeLateWork, fresh.state.value,
                FrozenNoAccessUiInputs.LSNA05_S5.message("old search/error/confirmation callbacks cannot alter the fresh controller"))
            assertEquals(AppRoute.Cashier, fixture.runtime.navigator.state.value.activeEntry.route,
                FrozenNoAccessUiInputs.LSNA05_S5.message("retired navigation cannot replace the fresh permitted route"))
            assertRetiredSearch(original, FrozenNoAccessUiInputs.LSNA05_S5)
            assertNoAutomaticWork(fixture, FrozenNoAccessUiInputs.LSNA05_S6)
        }
    }

    private suspend fun TestScope.prepareDepartedCostRead(fixture: NoAccessRuntimeFixture): DepartedCostRead {
        val runtime = fixture.runtime
        runtime.navigate(NavigationAction.SelectArea(AppArea.DRAWER), runtime.navigator.token())
        runtime.navigate(NavigationAction.SelectArea(AppArea.STOCK), runtime.navigator.token())
        val controller = fixture.activeController()
        controller.activate(LocalWorkflow.INVENTORY,
            LocalRecordReference(LocalRecordKind.SKU, FrozenNoAccessUiInputs.sku))
        runCurrent()
        val held = fixture.session.holdDepartedCostError()
        controller.selectInventory(LocalInventorySection.COSTS, runtime.navigator.token())
        runCurrent()
        held.captured.await()
        val query = fixture.session.boundaries.single().readQueries.last()
        check(query.inventory == LocalInventorySection.COSTS && query.reference?.id == FrozenNoAccessUiInputs.sku) {
            "The held sample must be the actual fixed-SKU cost read, not another report family."
        }
        runtime.navigate(NavigationAction.SelectArea(AppArea.CASHIER), runtime.navigator.token())
        check(runtime.navigator.state.value.activeEntry.route == AppRoute.Cashier)
        return DepartedCostRead(controller, held)
    }

    private fun TestScope.preparePrivateSearchAndConfirmation(fixture: NoAccessRuntimeFixture): OriginalPrivateWork {
        val runtime = fixture.runtime
        val controller = fixture.activeController()
        controller.activate(LocalWorkflow.CHECKOUT)
        runCurrent()
        controller.changeSearch(FrozenNoAccessUiInputs.PRIVATE_SEARCH, runtime.navigator.token())
        check(controller.state.value.search == FrozenNoAccessUiInputs.PRIVATE_SEARCH)
        // Bind the existing public guard and real confirmation; do not invent an unavailable
        // complete cart/form schema or infer quantity-3 business effects from this draft flag.
        runtime.navigator.setRootDraft(true)
        check(runtime.navigator.registerGuard(runtime.navigator.state.value.activeEntry.id, LeaveGuard.DIRTY) != null)
        val originalToken = runtime.navigator.token()
        runtime.navigate(NavigationAction.Back, originalToken)
        val confirmationId = checkNotNull(runtime.navigator.state.value.confirmation).id
        return OriginalPrivateWork(controller, originalToken, confirmationId,
            runtime.navigator.state.value.stacks.values.flatten().map { it.id }.toSet())
    }

    private fun assertSafeNoAreas(fixture: NoAccessRuntimeFixture, oracle: FrozenNoAccessUiStep) {
        val state = fixture.runtime.navigator.state.value
        val presentation = fixture.runtime.presentation.value
        assertEquals(SecurityRoot.BRANCH_WORKSPACE, state.root, oracle.message("operational loss is distinct from logout"))
        assertEquals(setOf(AppArea.SESSION), state.stacks.keys, oracle.message("no operational stack remains"))
        assertEquals(AppArea.SESSION, state.selectedArea, oracle.message("only session-control presentation is selected"))
        assertEquals(AppRoute.SessionAccess, state.activeEntry.route, oracle.message("the existing no-area status surface is reachable"))
        val display = assertNotNull(presentation.display, oracle.message("the valid employee identity remains distinct"))
        assertEquals(FrozenNoAccessUiInputs.employeeA, display.actorId, oracle.message("same employee A"))
        assertEquals(FrozenNoAccessUiInputs.company, display.companyId, oracle.message("same company scope"))
        assertEquals(FrozenNoAccessUiInputs.branch, display.branchId, oracle.message("same branch scope"))
        assertTrue(display.allowedAreas.isEmpty(), oracle.message("zero operational hints"))
        assertTrue(fixture.session.isCurrent(), oracle.message("existing current session has not ended"))
        assertFalse(presentation.locallyLocked, oracle.message("no fabricated authentication lock"))
        assertNull(presentation.sessionProblem, oracle.message("no fabricated expired/disabled/logout problem"))
        assertNull(presentation.signInFailure, oracle.message("no fabricated failed login"))
        assertNull(state.confirmation, oracle.message("retired dirty-workflow confirmation is absent"))
        assertFalse(state.rootHasDraft, oracle.message("retired private draft flag is absent"))
    }

    private fun assertRetiredSearch(original: OriginalPrivateWork, oracle: FrozenNoAccessUiStep) {
        assertEquals("", original.controller.state.value.search, oracle.message("retired original search is cleared"))
        assertNull(original.controller.state.value.page, oracle.message("retired protected page is cleared"))
        assertNull(original.controller.state.value.form, oracle.message("retired form is cleared"))
    }

    private fun assertNoAutomaticWork(fixture: NoAccessRuntimeFixture, oracle: FrozenNoAccessUiStep) {
        assertEquals(1, fixture.gateway.signInCalls, oracle.message("no implicit replacement login"))
        assertEquals(0, fixture.session.refreshCalls, oracle.message("no automatic renewal"))
        assertEquals(0, fixture.session.businessDispatches, oracle.message("zero new staging/submission calls"))
        assertEquals(0, fixture.session.lookupCalls, oracle.message("zero automatic original-result lookup calls"))
        assertEquals(0, fixture.session.acknowledgementCalls, oracle.message("zero obligation acknowledgement calls"))
    }

    private suspend fun TestScope.withFixture(block: suspend TestScope.(NoAccessRuntimeFixture) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var fixtureToClose: NoAccessRuntimeFixture? = null
        try {
            val fixture = NoAccessRuntimeFixture().also { fixtureToClose = it }
            fixture.attachExistingP10Session()
            fixture.monitor(backgroundScope)
            runCurrent()
            block(fixture)
        } finally {
            try { fixtureToClose?.close(); runCurrent() } finally { Dispatchers.resetMain() }
        }
    }
}

private data class OriginalPrivateWork(val controller: LocalWorkflowController, val token: NavigationToken,
    val confirmationId: Long, val entryIds: Set<Long>)
private data class DepartedCostRead(val controller: LocalWorkflowController, val reply: NoAccessHeldRead)
