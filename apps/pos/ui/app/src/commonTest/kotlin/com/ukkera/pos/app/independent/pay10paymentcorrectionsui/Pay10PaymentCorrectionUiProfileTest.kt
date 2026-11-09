package com.ukkera.pos.app.independent.pay10paymentcorrectionsui

import com.ukkera.pos.app.*
import com.ukkera.pos.app.navigation.*
import com.ukkera.pos.app.ui.localWorkflows
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Supporting UI guards for PAY-10. These are not Master authorization or commerce-v1 acceptance. */
class Pay10PaymentCorrectionUiProfileTest {
    @Test fun pcui01CorrectionProfileExposesOnlyCorrectionWorkspacesAndSessionStatus() {
        val mode = LocalSessionWorkspaceMode.PAYMENT_CORRECTIONS_ONLY
        assertEquals(setOf(AppArea.CASHIER, AppArea.ORDERS), Pay10UiInputs.display().allowedAreas)
        assertEquals(listOf(LocalWorkflow.CHECKOUT), localWorkflows(AppArea.CASHIER, mode))
        assertEquals(listOf(LocalWorkflow.ORDERS), localWorkflows(AppArea.ORDERS, mode))
        for (area in AppArea.entries - setOf(AppArea.CASHIER, AppArea.ORDERS)) {
            assertEquals(emptyList(), localWorkflows(area, mode), area.name)
        }
        assertTrue(paymentCorrectionUiRoute(AppRoute.Cashier))
        assertTrue(paymentCorrectionUiRoute(AppRoute.Orders))
        assertTrue(paymentCorrectionUiRoute(AppRoute.SessionAccess))
        for (area in AppArea.entries - setOf(AppArea.CASHIER, AppArea.ORDERS, AppArea.SESSION)) {
            assertFailsWith<IllegalArgumentException>(area.name) { Pay10UiInputs.display(areas = setOf(AppArea.CASHIER, area)) }
        }
        assertFailsWith<IllegalArgumentException> { Pay10UiInputs.display().copy(branchId = null) }
    }

    @Test fun pcui04To07OnlyCanonicalLookupSourceAndHistoryQueriesAreAccepted() {
        for (workflow in listOf(LocalWorkflow.CHECKOUT, LocalWorkflow.ORDERS)) {
            assertTrue(LocalWorkflowQuery(workflow).isPaymentCorrectionUiQuery())
            assertTrue(LocalWorkflowQuery(workflow, Pay10UiInputs.search).isPaymentCorrectionUiQuery())
            assertTrue(LocalWorkflowQuery(workflow, reference = Pay10UiInputs.source).isPaymentCorrectionUiQuery())
            assertTrue(LocalWorkflowQuery(workflow, reference = Pay10UiInputs.history).isPaymentCorrectionUiQuery())
            assertTrue(LocalWorkflowQuery(workflow, reference = Pay10UiInputs.history, cursor = "history-next").isPaymentCorrectionUiQuery())
            for (reference in listOf(Pay10UiInputs.source, Pay10UiInputs.history)) {
                assertFalse(LocalWorkflowQuery(workflow, Pay10UiInputs.search, reference).isPaymentCorrectionUiQuery())
                assertFalse(LocalWorkflowQuery(workflow, reference = reference.copy(revision = Revision(1))).isPaymentCorrectionUiQuery())
            }
            assertFalse(LocalWorkflowQuery(workflow, reference = Pay10UiInputs.source.copy(parentId = Pay10UiInputs.payment)).isPaymentCorrectionUiQuery())
            assertFalse(LocalWorkflowQuery(workflow, reference = Pay10UiInputs.history.copy(parentId = null)).isPaymentCorrectionUiQuery())
            assertFalse(LocalWorkflowQuery(workflow, reference = Pay10UiInputs.source, cursor = "next").isPaymentCorrectionUiQuery())
            for (search in listOf("all", Pay10UiInputs.sale.value, "payment-correction:", "payment-correction:not-a-uuid", " ${Pay10UiInputs.search}", "${Pay10UiInputs.search} ")) {
                assertFalse(LocalWorkflowQuery(workflow, search).isPaymentCorrectionUiQuery(), search)
            }
            for (kind in LocalRecordKind.entries - setOf(LocalRecordKind.PAYMENT_CORRECTION_SOURCE, LocalRecordKind.PAYMENT_CORRECTION_HISTORY)) {
                assertFalse(LocalWorkflowQuery(workflow, reference = LocalRecordReference(kind, Pay10UiInputs.sale)).isPaymentCorrectionUiQuery(), kind.name)
            }
        }
        for (workflow in LocalWorkflow.entries - setOf(LocalWorkflow.CHECKOUT, LocalWorkflow.ORDERS)) {
            assertFalse(LocalWorkflowQuery(workflow).isPaymentCorrectionUiQuery(), workflow.name)
        }
    }

    @Test fun pcui08OriginalPaymentCloseAndHistoricalCorrectionRemainDistinctReadOnlyRecords() {
        val source = Pay10UiInputs.sourcePage(LocalWorkflow.CHECKOUT)
        assertTrue(source.matchesPaymentCorrectionUiPage(LocalWorkflowQuery(LocalWorkflow.CHECKOUT, Pay10UiInputs.search)))
        assertTrue(source.matchesPaymentCorrectionUiPage(LocalWorkflowQuery(LocalWorkflow.CHECKOUT, reference = Pay10UiInputs.source)))
        val history = Pay10UiInputs.historyPage(LocalWorkflow.CHECKOUT, includeCorrection = true)
        val query = LocalWorkflowQuery(LocalWorkflow.CHECKOUT, reference = Pay10UiInputs.history)
        assertTrue(history.matchesPaymentCorrectionUiPage(query))
        assertFalse(history.records.single { it.reference.kind == LocalRecordKind.CASH_SESSION }.selectable)
        assertFalse(history.records.single { it.reference.kind == LocalRecordKind.PAYMENT_CORRECTION }.selectable)
        assertFalse(history.copy(records = history.records.filter { it.reference.kind != LocalRecordKind.CASH_SESSION }).matchesPaymentCorrectionUiPage(query))
        assertFalse(history.copy(records = history.records.map { if (it.reference.kind == LocalRecordKind.CASH_SESSION) it.copy(selectable = true) else it }).matchesPaymentCorrectionUiPage(query))
        assertFalse(history.copy(records = history.records.map { if (it.reference.kind == LocalRecordKind.PAYMENT_CORRECTION) it.copy(reference = it.reference.copy(parentId = Pay10UiInputs.sale)) else it }).matchesPaymentCorrectionUiPage(query))
        assertFalse(history.copy(records = history.records.map { if (it.reference == Pay10UiInputs.history) it.copy(reference = it.reference.copy(parentId = Pay10UiInputs.otherEmployee)) else it }).matchesPaymentCorrectionUiPage(query))
        assertFalse(history.matchesPaymentCorrectionUiPage(LocalWorkflowQuery(LocalWorkflow.CHECKOUT)))
    }

    @Test fun pcui09OnlyOriginalBoundFullAmountCorrectionFormsAreUsable() {
        val form = Pay10UiInputs.form()
        assertTrue(form.matchesPaymentCorrectionUiForm(Pay10UiInputs.history))
        assertFalse(form.matchesPaymentCorrectionUiForm(Pay10UiInputs.source))
        assertFalse(form.copy(version = 2).matchesPaymentCorrectionUiForm(Pay10UiInputs.history))
        assertFalse(form.copy(confirmation = null).matchesPaymentCorrectionUiForm(Pay10UiInputs.history))
        assertFalse(form.copy(reservedOperationId = Pay10UiInputs.originalOperation).matchesPaymentCorrectionUiForm(Pay10UiInputs.history))
        for (field in listOf(LocalField.SALE_ID, LocalField.PAYMENT_ID, LocalField.EXPECTED_SESSION_REVISION, LocalField.AMOUNT)) {
            val editable = form.copy(fields = form.fields.map { if (it.field == field) (it as LocalFormField.Scalar).copy(readOnly = false) else it })
            assertFalse(editable.matchesPaymentCorrectionUiForm(Pay10UiInputs.history), field.name)
        }
        val changedSource = form.copy(fields = form.fields.map { if (it.field == LocalField.PAYMENT_ID)
            (it as LocalFormField.Scalar).copy(initialValue = LocalScalarValue.Identifier(Pay10UiInputs.otherEmployee)) else it })
        assertFalse(changedSource.matchesPaymentCorrectionUiForm(Pay10UiInputs.history))
        for (action in LocalAction.entries.filter { it != LocalAction.PAYMENT_CLASSIFICATION_CORRECT && it.canAppearIn(LocalWorkflow.CHECKOUT) }) {
            assertFalse(form.copy(action = action).matchesPaymentCorrectionUiForm(Pay10UiInputs.history), action.name)
        }
    }

    @Test fun pcui18NavigatorRejectsUnrelatedPushesWithoutDiscardConsent() {
        val navigator = AppNavigator()
        navigator.replaceRoot(SecurityRoot.BRANCH_WORKSPACE, Pay10UiInputs.display())
        assertEquals(setOf(AppArea.CASHIER, AppArea.ORDERS), navigator.state.value.stacks.keys)
        navigator.registerGuard(navigator.state.value.activeEntry.id, LeaveGuard.DIRTY)
        val before = navigator.state.value
        assertEquals(NavigationResult.IGNORED, navigator.navigate(NavigationAction.Push(AppRoute.QuotePreview), navigator.token()))
        assertEquals(before, navigator.state.value)
        assertNull(navigator.state.value.confirmation)
        navigator.replaceRoot(SecurityRoot.BRANCH_WORKSPACE, Pay10UiInputs.display())
        assertEquals(NavigationResult.CHANGED, navigator.navigate(NavigationAction.SelectArea(AppArea.ORDERS), navigator.token()))
        for (route in listOf(AppRoute.Order(Pay10UiInputs.sale), AppRoute.Food, AppRoute.FoodOrder(Pay10UiInputs.sale),
            AppRoute.FoodStation(Pay10UiInputs.sale), AppRoute.FoodHandoff(Pay10UiInputs.sale))) {
            val unchanged = navigator.state.value
            assertEquals(NavigationResult.IGNORED, navigator.navigate(NavigationAction.Push(route), navigator.token()))
            assertEquals(unchanged, navigator.state.value)
        }
        for (area in AppArea.entries - setOf(AppArea.CASHIER, AppArea.ORDERS)) {
            assertEquals(NavigationResult.IGNORED, navigator.navigate(NavigationAction.SelectArea(area), navigator.token()))
        }
    }

    @Test fun pcui18RestorationCannotAdoptHistoricalFoodOrOrderNavigation() = runTest {
        for (route in listOf(AppRoute.Order(Pay10UiInputs.sale), AppRoute.Food, AppRoute.FoodOrder(Pay10UiInputs.sale),
            AppRoute.FoodStation(Pay10UiInputs.sale), AppRoute.FoodHandoff(Pay10UiInputs.sale))) {
            val old = AppNavigator()
            old.replaceRoot(SecurityRoot.BRANCH_WORKSPACE, Pay10UiInputs.display().copy(workspaceMode = LocalSessionWorkspaceMode.POLICY_FILTERED))
            old.navigate(NavigationAction.SelectArea(AppArea.ORDERS), old.token())
            assertEquals(NavigationResult.CHANGED, old.navigate(NavigationAction.Push(route), old.token()))
            val encoded = assertNotNull(old.checkpoint())
            val fresh = AppNavigator()
            assertTrue(fresh.quarantineRestore(encoded))
            fresh.replaceRoot(SecurityRoot.BRANCH_WORKSPACE, Pay10UiInputs.display())
            val before = fresh.state.value
            assertFalse(fresh.restoreAfterAuthentication { true })
            assertEquals(before, fresh.state.value)
            assertEquals(encoded, old.checkpoint())
        }
        val original = AppNavigator()
        original.replaceRoot(SecurityRoot.BRANCH_WORKSPACE, Pay10UiInputs.display())
        original.navigate(NavigationAction.SelectArea(AppArea.ORDERS), original.token())
        val current = AppNavigator()
        assertTrue(current.quarantineRestore(assertNotNull(original.checkpoint())))
        current.replaceRoot(SecurityRoot.BRANCH_WORKSPACE, Pay10UiInputs.display())
        assertTrue(current.restoreAfterAuthentication { route -> paymentCorrectionUiRoute(route) })
        assertEquals(AppRoute.Orders, current.state.value.activeEntry.route)
    }
}
