package com.ukkera.pos.app.navigation

import com.ukkera.pos.app.training.TrainingWorkspace
import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppNavigatorTest {
    private val branch = TrainingWorkspace.display(company = false)
    private val checkpointJson = Json { classDiscriminator = "route" }
    private fun navigator() = AppNavigator().apply { replaceRoot(SecurityRoot.BRANCH_WORKSPACE, branch) }
    private fun order(number: Int) = AppRoute.Order(DomainId("00000000-0000-4000-8000-${number.toString().padStart(12, '0')}"))

    @Test fun everyTypedRouteRoundTripsAndHasAnExhaustiveOwner() {
        val routes = listOf(AppRoute.Start, AppRoute.BranchSignIn, AppRoute.CompanySignIn, AppRoute.Cashier,
            AppRoute.QuotePreview, AppRoute.Orders, order(1), AppRoute.Drawer, AppRoute.Stock, AppRoute.Reports,
            AppRoute.Setup, AppRoute.SetupStep(7), AppRoute.Catalog, AppRoute.People, AppRoute.Purchasing,
            AppRoute.Devices, AppRoute.CompanySettings)
        val encoded = routes.map { Json.encodeToString<AppRoute>(it) }
        assertEquals(routes.size, encoded.toSet().size)
        routes.forEachIndexed { index, route ->
            assertEquals(route, Json.decodeFromString<AppRoute>(encoded[index]))
            assertTrue(SecurityRoot.entries.any { RouteRegistry.allows(it, route) })
            assertTrue(encoded[index].contains(".v1"))
        }
        assertFailsWith<IllegalArgumentException> { AppRoute.SetupStep(-1) }
        assertFailsWith<IllegalArgumentException> { AppRoute.SetupStep(8) }
        assertFalse(RouteRegistry.allows(SecurityRoot.COMPANY_WORKSPACE, AppRoute.Cashier))
        assertFalse(RouteRegistry.allows(SecurityRoot.BRANCH_SIGN_IN, AppRoute.CompanySignIn))
    }

    @Test fun independentStacksSurviveTabSwitchAndReselectPopsToRoot() {
        val nav = navigator()
        nav.navigate(NavigationAction.SelectArea(AppArea.ORDERS), nav.token())
        nav.navigate(NavigationAction.Push(order(1)), nav.token())
        val entry = nav.state.value.activeEntry
        nav.navigate(NavigationAction.SelectArea(AppArea.STOCK), nav.token())
        nav.navigate(NavigationAction.SelectArea(AppArea.ORDERS), nav.token())
        assertEquals(entry, nav.state.value.activeEntry)
        nav.navigate(NavigationAction.SelectArea(AppArea.ORDERS), nav.token())
        assertEquals(listOf(AppRoute.Orders), nav.state.value.activeStack.map { it.route })
    }

    @Test fun repeatedAndDuplicateInputsCannotGrowHistory() {
        val nav = navigator()
        val token = nav.token()
        repeat(100_000) { nav.navigate(NavigationAction.Push(AppRoute.QuotePreview), token) }
        assertEquals(2, nav.state.value.activeStack.size)
        assertEquals(NavigationResult.IGNORED, nav.navigate(NavigationAction.Push(AppRoute.QuotePreview), nav.token()))
        nav.navigate(NavigationAction.SelectArea(AppArea.ORDERS), nav.token())
        repeat(100) { nav.navigate(NavigationAction.Push(order(it + 1)), nav.token()) }
        assertEquals(AppNavigator.MAX_STACK_DEPTH, nav.state.value.activeStack.size)
    }

    @Test fun blockedBackAlsoBlocksTabsForwardAndHostCloseWithoutPreview() {
        val nav = navigator()
        nav.navigate(NavigationAction.Push(AppRoute.QuotePreview), nav.token())
        nav.registerGuard(nav.state.value.activeEntry.id, LeaveGuard.CRITICAL)
        assertFalse(BackCoordinator.allowsPredictivePreview(nav.state.value))
        assertEquals(NavigationResult.BLOCKED, BackCoordinator.disposition(nav.state.value))
        for (action in listOf(NavigationAction.Back, NavigationAction.SelectArea(AppArea.STOCK), NavigationAction.CloseHost)) {
            assertEquals(NavigationResult.BLOCKED, nav.navigate(action, nav.token()))
        }
        assertEquals(2, nav.state.value.activeStack.size)
    }

    @Test fun confirmationAndGuardDisposalCannotAffectAReplacementRegistration() {
        val nav = navigator()
        nav.navigate(NavigationAction.Push(AppRoute.QuotePreview), nav.token())
        val old = nav.registerGuard(nav.state.value.activeEntry.id, LeaveGuard.DIRTY)!!
        assertEquals(NavigationResult.CONFIRM_DISCARD, nav.navigate(NavigationAction.Back, nav.token()))
        val oldConfirmation = nav.state.value.confirmation!!.id
        val replacement = nav.registerGuard(nav.state.value.activeEntry.id, LeaveGuard.DIRTY)!!
        assertNull(nav.state.value.confirmation)
        nav.removeGuard(old)
        assertEquals(LeaveGuard.DIRTY, nav.state.value.guard)
        assertEquals(NavigationResult.IGNORED, nav.confirmLeave(oldConfirmation))
        nav.removeGuard(replacement)
        assertTrue(BackCoordinator.allowsPredictivePreview(nav.state.value))
    }

    @Test fun invalidDestinationsCannotDiscardAnEdit() {
        val nav = navigator()
        nav.registerGuard(nav.state.value.activeEntry.id, LeaveGuard.DIRTY)
        assertEquals(NavigationResult.IGNORED, nav.navigate(NavigationAction.Push(AppRoute.People), nav.token()))
        assertEquals(LeaveGuard.DIRTY, nav.state.value.guard)
        assertNull(nav.state.value.confirmation)
    }

    @Test fun retainedBasketDoesNotBlockTabSwitchButContextExitRequiresConfirmation() {
        val nav = navigator()
        nav.setRootDraft(true)
        assertEquals(NavigationResult.CHANGED, nav.navigate(NavigationAction.SelectArea(AppArea.STOCK), nav.token()))
        assertTrue(nav.state.value.rootHasDraft)
        assertEquals(NavigationResult.CONFIRM_DISCARD, nav.navigate(NavigationAction.CloseHost, nav.token()))
        val id = nav.state.value.confirmation!!.id
        assertEquals(NavigationResult.EXIT_PLATFORM, nav.confirmLeave(id))
        assertEquals(NavigationResult.IGNORED, nav.confirmLeave(id))
    }

    @Test fun discardingEntryEditsDoesNotAcknowledgeTheRetainedBasket() {
        for (action in listOf(NavigationAction.Back, NavigationAction.SelectArea(AppArea.STOCK),
            NavigationAction.Push(order(2)))) {
            val nav = navigator()
            nav.setRootDraft(true)
            nav.navigate(NavigationAction.SelectArea(AppArea.ORDERS), nav.token())
            nav.navigate(NavigationAction.Push(order(1)), nav.token())
            nav.registerGuard(nav.state.value.activeEntry.id, LeaveGuard.DIRTY)
            assertEquals(NavigationResult.CONFIRM_DISCARD, nav.navigate(action, nav.token()))
            assertEquals(NavigationResult.CHANGED, nav.confirmLeave(nav.state.value.confirmation!!.id))
            assertTrue(nav.state.value.rootHasDraft)
            assertEquals(NavigationResult.CONFIRM_DISCARD,
                nav.navigate(NavigationAction.CloseHost, nav.token()))
            assertEquals(NavigationResult.EXIT_PLATFORM, nav.confirmLeave(nav.state.value.confirmation!!.id))
            assertFalse(nav.state.value.rootHasDraft)
        }
    }

    @Test fun staleSessionEntryTabAndForeignContainerTokensAreRejected() {
        val nav = navigator()
        val cashierToken = nav.token()
        nav.navigate(NavigationAction.SelectArea(AppArea.ORDERS), cashierToken)
        assertFalse(nav.isCurrent(cashierToken))
        val employeeAToken = nav.token()
        val oldId = nav.state.value.activeEntry.id
        nav.replaceRoot(SecurityRoot.BRANCH_WORKSPACE, branch.copy(actorId = order(999).orderId))
        assertFalse(nav.isCurrent(employeeAToken))
        assertNotEquals(oldId, nav.state.value.activeEntry.id)
        assertFalse(nav.isCurrent(navigator().token()))
    }

    @Test fun detachDiscardsProtectedHistoryAndNeverReplaysAnOldCallback() {
        val nav = navigator()
        nav.navigate(NavigationAction.Push(AppRoute.QuotePreview), nav.token())
        val token = nav.token()
        nav.detachHost()
        assertEquals(SecurityRoot.BOOTSTRAP, nav.state.value.root)
        assertFalse(nav.state.value.attached)
        nav.attachHost()
        assertEquals(AppRoute.Start, nav.state.value.activeEntry.route)
        assertFalse(nav.isCurrent(token))
    }

    @Test fun permissionsFilterAreasAndProtectSecurityRoots() {
        val nav = AppNavigator()
        nav.replaceRoot(SecurityRoot.BRANCH_WORKSPACE, branch.copy(allowedAreas = setOf(AppArea.STOCK)))
        assertEquals(AppArea.STOCK, nav.state.value.selectedArea)
        assertEquals(NavigationResult.IGNORED, nav.navigate(NavigationAction.SelectArea(AppArea.CASHIER), nav.token()))
        assertFailsWith<IllegalArgumentException> { nav.replaceRoot(SecurityRoot.COMPANY_WORKSPACE) }
        assertEquals(AppArea.STOCK, nav.state.value.selectedArea)
    }

    @Test fun checkpointIsQuarantinedUntilEveryRouteIsAuthorized() = runTest {
        val source = navigator()
        source.navigate(NavigationAction.SelectArea(AppArea.ORDERS), source.token())
        source.navigate(NavigationAction.Push(order(42)), source.token())
        val checkpoint = source.checkpoint()!!
        assertFalse(checkpoint.contains("price"))
        assertFalse(checkpoint.contains("token"))
        val target = AppNavigator()
        assertTrue(target.quarantineRestore(checkpoint))
        assertEquals(AppRoute.Start, target.state.value.activeEntry.route)
        target.replaceRoot(SecurityRoot.BRANCH_WORKSPACE, branch)
        val authorized = mutableListOf<AppRoute>()
        assertTrue(target.restoreAfterAuthentication { authorized += it; true })
        assertEquals(order(42), target.state.value.activeEntry.route)
        assertTrue(authorized.containsAll(listOf(AppRoute.Cashier, AppRoute.Orders, order(42))))
    }

    @Test fun restorationRetainsNewlyGrantedAreasAndRejectsRevokedOnes() = runTest {
        val source = AppNavigator()
        source.replaceRoot(SecurityRoot.BRANCH_WORKSPACE, branch.copy(allowedAreas = setOf(AppArea.ORDERS)))
        source.navigate(NavigationAction.Push(order(42)), source.token())
        val checkpoint = source.checkpoint()!!
        val target = AppNavigator()
        assertTrue(target.quarantineRestore(checkpoint))
        target.replaceRoot(SecurityRoot.BRANCH_WORKSPACE, branch)
        assertTrue(target.restoreAfterAuthentication { true })
        assertEquals(order(42), target.state.value.activeEntry.route)
        assertTrue(AppArea.CASHIER in target.state.value.stacks)
        assertEquals(NavigationResult.CHANGED,
            target.navigate(NavigationAction.SelectArea(AppArea.CASHIER), target.token()))
        assertEquals(AppRoute.Cashier, target.state.value.activeEntry.route)

        val revoked = AppNavigator()
        assertTrue(revoked.quarantineRestore(checkpoint))
        revoked.replaceRoot(SecurityRoot.BRANCH_WORKSPACE, branch.copy(allowedAreas = setOf(AppArea.STOCK)))
        var reads = 0
        assertFalse(revoked.restoreAfterAuthentication { reads++; true })
        assertEquals(0, reads)
        assertEquals(setOf(AppArea.STOCK), revoked.state.value.stacks.keys)
        assertEquals(AppRoute.Stock, revoked.state.value.activeEntry.route)
    }

    @Test fun wrongActorUnauthorizedMissingOrFailedResourceRestoreFailsClosed() = runTest {
        val source = navigator()
        source.navigate(NavigationAction.SelectArea(AppArea.ORDERS), source.token())
        source.navigate(NavigationAction.Push(order(42)), source.token())
        val checkpoint = source.checkpoint()!!
        for (mode in 0..2) {
            val target = AppNavigator()
            assertTrue(target.quarantineRestore(checkpoint))
            target.replaceRoot(SecurityRoot.BRANCH_WORKSPACE, if (mode == 0) branch.copy(actorId = order(999).orderId) else branch)
            assertFalse(target.restoreAfterAuthentication { route -> if (mode == 2) error("repository unavailable") else route !is AppRoute.Order })
            assertEquals(AppRoute.Cashier, target.state.value.activeEntry.route)
        }
    }

    @Test fun slowRestoreCannotRenderForTheNextUser() = runTest {
        val source = navigator()
        source.navigate(NavigationAction.SelectArea(AppArea.ORDERS), source.token())
        val target = AppNavigator()
        target.quarantineRestore(source.checkpoint()!!)
        target.replaceRoot(SecurityRoot.BRANCH_WORKSPACE, branch)
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val job = async { target.restoreAfterAuthentication { entered.complete(Unit); resume.await(); true } }
        entered.await()
        target.replaceRoot(SecurityRoot.BRANCH_WORKSPACE, branch.copy(actorId = order(999).orderId))
        resume.complete(Unit)
        assertFalse(job.await())
        assertEquals(AppRoute.Cashier, target.state.value.activeEntry.route)
    }

    @Test fun delayedRestoreCannotEraseNewLeaveGuardsOrRootDrafts() = runTest {
        val source = navigator()
        source.navigate(NavigationAction.SelectArea(AppArea.ORDERS), source.token())
        source.navigate(NavigationAction.Push(order(42)), source.token())
        val checkpoint = source.checkpoint()!!
        for (guard in listOf(LeaveGuard.DIRTY, LeaveGuard.CRITICAL, LeaveGuard.HANDLED_LOCALLY, null)) {
            val target = AppNavigator()
            assertTrue(target.quarantineRestore(checkpoint))
            target.replaceRoot(SecurityRoot.BRANCH_WORKSPACE, branch)
            val entered = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val job = async { target.restoreAfterAuthentication { entered.complete(Unit); resume.await(); true } }
            entered.await()
            if (guard != null) target.registerGuard(target.state.value.activeEntry.id, guard)
            else target.setRootDraft(true)
            resume.complete(Unit)
            assertFalse(job.await())
            assertEquals(AppRoute.Cashier, target.state.value.activeEntry.route)
            assertEquals(guard ?: LeaveGuard.CLEAN, target.state.value.guard)
            assertEquals(guard == null, target.state.value.rootHasDraft)
        }
    }

    @Test fun restorationDoesNotStartOverExistingGuardedOrDraftState() = runTest {
        val source = navigator()
        source.navigate(NavigationAction.SelectArea(AppArea.ORDERS), source.token())
        val checkpoint = source.checkpoint()!!
        for (guard in listOf(LeaveGuard.DIRTY, LeaveGuard.CRITICAL, null)) {
            val target = AppNavigator()
            assertTrue(target.quarantineRestore(checkpoint))
            target.replaceRoot(SecurityRoot.BRANCH_WORKSPACE, branch)
            // Also protect an inactive retained entry, not just the currently visible route.
            if (guard != null) target.registerGuard(target.state.value.stacks.getValue(AppArea.ORDERS).last().id, guard)
            else target.setRootDraft(true)
            var reads = 0
            assertFalse(target.restoreAfterAuthentication { reads++; true })
            assertEquals(0, reads)
            assertEquals(AppRoute.Cashier, target.state.value.activeEntry.route)
        }
    }

    @Test fun malformedOversizedOrCrossRootCheckpointNeverChangesVisibleState() {
        val nav = AppNavigator()
        assertFalse(nav.quarantineRestore("{"))
        assertFalse(nav.quarantineRestore(" ".repeat(AppNavigator.MAX_CHECKPOINT_CHARS + 1)))
        val forged = RouteCheckpoint(1, SecurityRoot.COMPANY_WORKSPACE,
            RestoreContext(branch.actorId, branch.companyId, branch.branchId), AppArea.CASHIER,
            listOf(RestoredStack(AppArea.CASHIER, listOf(AppRoute.Cashier))))
        assertFalse(nav.quarantineRestore(checkpointJson.encodeToString(forged)))
        assertEquals(AppRoute.Start, nav.state.value.activeEntry.route)
    }

    @Test fun cancellationDuringAuthorizationIsNotTurnedIntoSuccess() = runTest {
        val source = navigator()
        val nav = AppNavigator()
        nav.quarantineRestore(source.checkpoint()!!)
        nav.replaceRoot(SecurityRoot.BRANCH_WORKSPACE, branch)
        assertFailsWith<CancellationException> { nav.restoreAfterAuthentication { throw CancellationException("validation canceled") } }
        assertEquals(AppRoute.Cashier, nav.state.value.activeEntry.route)
    }

    @Test fun cancellationStillFencesAnAuthorizerThatReturnsFromNonCancellableCleanup() = runTest {
        val source = navigator()
        source.navigate(NavigationAction.SelectArea(AppArea.ORDERS), source.token())
        val target = AppNavigator()
        assertTrue(target.quarantineRestore(source.checkpoint()!!))
        target.replaceRoot(SecurityRoot.BRANCH_WORKSPACE, branch)
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val job = async {
            target.restoreAfterAuthentication {
                withContext(NonCancellable) { entered.complete(Unit); resume.await() }
                true
            }
        }
        entered.await()
        job.cancel()
        resume.complete(Unit)
        assertFailsWith<CancellationException> { job.await() }
        job.join()
        assertEquals(AppRoute.Cashier, target.state.value.activeEntry.route)
    }

    @Test fun quotePreviewAndSetupEditsAreNotRestoredWithoutTheirApplicationState() {
        val nav = navigator()
        nav.navigate(NavigationAction.Push(AppRoute.QuotePreview), nav.token())
        assertFalse(nav.checkpoint()!!.contains("quote-preview.v1"))
    }
}
