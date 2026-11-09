package com.ukkera.pos.app.independent.counterreceiptrpc

import com.ukkera.pos.app.*
import com.ukkera.pos.app.navigation.*
import com.ukkera.pos.app.setup.LocalSetupLanInput
import com.ukkera.pos.app.setup.LocalSetupLanWorkflow
import com.ukkera.pos.security.LocalPairedDeviceCredentialJournalAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** S1 SUPPORT only, from frozen CRRPC v1. No setup/authentication/RPC qualification is asserted. */
class CounterReceiptProfileSupportTest {
    @Test
    fun crrpc001TransportDefaultsDoNotSelectReceiptWorkspaceOrIndependentCustody() {
        val transport = LocalSetupLanInput(listOf("192.0.2.0/24"), "synthetic", "192.0.2.2", 19001)
        assertEquals(LocalSetupLanWorkflow.AUTHENTICATION_ONLY, transport.workflow)
        assertNull(transport.credentialAction)
        assertNull(transport.actorIntentAction)

        val receiptChoice = LocalSetupLanInput(listOf("192.0.2.0/24"), "synthetic", "192.0.2.2", 19001,
            LocalPairedDeviceCredentialJournalAction.REOPEN, LocalSetupLanWorkflow.CUSTOMER_RECEIPTS)
        assertEquals(LocalSetupLanWorkflow.CUSTOMER_RECEIPTS, receiptChoice.workflow)
        assertEquals(LocalPairedDeviceCredentialJournalAction.REOPEN, receiptChoice.credentialAction)
        assertNull(receiptChoice.actorIntentAction) // Device disposition cannot supply the independent actor choice.

        val independent = LocalSetupLanInput(listOf("192.0.2.0/24"), "synthetic", "192.0.2.2", 19001,
            LocalPairedDeviceCredentialJournalAction.REOPEN, LocalSetupLanWorkflow.CUSTOMER_RECEIPTS,
            LocalPairedDeviceCredentialJournalAction.RECOVER)
        assertEquals(LocalPairedDeviceCredentialJournalAction.REOPEN, independent.credentialAction)
        assertEquals(LocalPairedDeviceCredentialJournalAction.RECOVER, independent.actorIntentAction)
    }

    @Test
    fun crrpc002ReceiptPresentationRejectsUnrelatedAreasAndRoutes() {
        val display = CounterReceiptUiInput.display()
        assertEquals(setOf(AppArea.CASHIER, AppArea.ORDERS), display.allowedAreas)
        for (area in AppArea.entries.filterNot { it == AppArea.CASHIER || it == AppArea.ORDERS }) {
            assertFailsWith<IllegalArgumentException> { CounterReceiptUiInput.display(areas = setOf(area)) }
        }
        assertFailsWith<IllegalArgumentException> { display.copy(branchId = null) }

        val navigator = AppNavigator()
        navigator.replaceRoot(SecurityRoot.BRANCH_WORKSPACE, display)
        assertEquals(NavigationResult.IGNORED,
            navigator.navigate(NavigationAction.Push(AppRoute.QuotePreview), navigator.token()))
        for (area in listOf(AppArea.DRAWER, AppArea.STOCK, AppArea.CATALOG, AppArea.REPORTS, AppArea.SETUP)) {
            assertEquals(NavigationResult.IGNORED, navigator.navigate(NavigationAction.SelectArea(area), navigator.token()))
        }
        assertEquals(NavigationResult.CHANGED,
            navigator.navigate(NavigationAction.SelectArea(AppArea.ORDERS), navigator.token()))
        for (route in listOf(AppRoute.Order(CounterReceiptUiInput.sale), AppRoute.Food,
            AppRoute.FoodOrder(CounterReceiptUiInput.sale), AppRoute.FoodStation(CounterReceiptUiInput.item),
            AppRoute.FoodHandoff(CounterReceiptUiInput.sale))) {
            assertEquals(NavigationResult.IGNORED, navigator.navigate(NavigationAction.Push(route), navigator.token()))
        }
        assertEquals(listOf(AppRoute.Orders), navigator.state.value.activeStack.map { it.route })

        navigator.replaceRoot(SecurityRoot.BRANCH_WORKSPACE, CounterReceiptUiInput.display(areas = emptySet()))
        assertEquals(AppRoute.SessionAccess, navigator.state.value.activeEntry.route)
        assertEquals(setOf(AppArea.SESSION), navigator.state.value.stacks.keys)
    }

    @Test
    fun crrpc029ReceiptProfileReconciliationRemovesIncompatibleHistoryWithoutResurrection() {
        val departed = mutableListOf<Long>()
        val navigator = AppNavigator { departed += it }
        val broad = CounterReceiptUiInput.display().copy(workspaceMode = LocalSessionWorkspaceMode.POLICY_FILTERED)
        navigator.replaceRoot(SecurityRoot.BRANCH_WORKSPACE, broad)
        navigator.navigate(NavigationAction.Push(AppRoute.QuotePreview), navigator.token())
        val quote = navigator.state.value.activeEntry
        navigator.navigate(NavigationAction.SelectArea(AppArea.ORDERS), navigator.token())
        navigator.navigate(NavigationAction.Push(AppRoute.Order(CounterReceiptUiInput.sale)), navigator.token())
        val order = navigator.state.value.activeEntry
        val oldCallback = navigator.token()
        val retained = navigator.state.value.stacks.values.flatten().map { it.id }.toSet()

        navigator.reconcileLocalAreas(CounterReceiptUiInput.display(), retained)
        assertEquals(listOf(AppRoute.Cashier), navigator.state.value.stacks.getValue(AppArea.CASHIER).map { it.route })
        assertEquals(listOf(AppRoute.Orders), navigator.state.value.stacks.getValue(AppArea.ORDERS).map { it.route })
        assertTrue(quote.id in departed && order.id in departed)
        assertFalse(navigator.isCurrent(oldCallback))
        assertEquals(NavigationResult.IGNORED,
            navigator.navigate(NavigationAction.Push(AppRoute.Order(CounterReceiptUiInput.sale)), oldCallback))

        val safe = navigator.state.value.stacks.values.flatten().map { it.id }.toSet()
        navigator.reconcileLocalAreas(broad, safe)
        assertTrue(navigator.state.value.stacks.values.flatten().none { it.id == quote.id || it.id == order.id })
        val ownerCallback = navigator.token()
        navigator.replaceRoot(SecurityRoot.BRANCH_WORKSPACE, CounterReceiptUiInput.display(CounterReceiptUiInput.other))
        assertFalse(navigator.isCurrent(ownerCallback))
        assertTrue(navigator.state.value.stacks.values.flatten().all { it.route == AppRoute.Cashier || it.route == AppRoute.Orders })
    }
}
