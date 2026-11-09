package com.ukkera.pos.app.independent.foodcheckrpcprofilev1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.navigation.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** FCRP-v1 bounded membership SHAPES only, not current grants, native sources or actual terminal revisions. */
class FoodCheckUiMembershipSupportingTest {
    private val frozen = FrozenFoodCheckUiInputs

    @Test
    fun fcrp001And004PresentationModeAndAreaShapeAreExplicitNotAuthority() {
        assertEquals("FOOD_CHECKS", frozen.profile.string("profile"))
        assertEquals("FOOD_CHECKS_ONLY", frozen.profile.string("presentation_subset"))
        val selected = frozen.display()
        assertEquals(LocalSessionWorkspaceMode.FOOD_CHECKS_ONLY, selected.workspaceMode)
        assertEquals(LocalSessionWorkspaceMode.POLICY_FILTERED,
            SessionDisplayContext(frozen.actor, frozen.company, frozen.branch, "Synthetic", "Synthetic", "Synthetic",
                setOf(AppArea.ORDERS)).workspaceMode)
        for (area in AppArea.entries.filterNot { it == AppArea.ORDERS }) {
            assertFailsWith<IllegalArgumentException> { frozen.display(setOf(area)) }
        }
        assertFailsWith<IllegalArgumentException> { selected.copy(branchId = null) }
        for (family in PlatformFamily.entries) {
            val platform = PlatformCapabilities(family)
            assertFalse(platform.liveBranchHostingQualified)
            assertFalse(platform.physicalPeripheralsQualified)
        }
    }

    @Test
    fun fcrp004ClosedRoutesExcludeStationHandoffFinanceAndOtherEditors() {
        val admitted = listOf(AppRoute.Orders, AppRoute.Food, AppRoute.FoodOrder(frozen.order), AppRoute.SessionAccess)
        val excluded = listOf(AppRoute.Start, AppRoute.BranchSignIn, AppRoute.LocalEnrollment, AppRoute.CompanySignIn,
            AppRoute.Cashier, AppRoute.QuotePreview, AppRoute.Order(frozen.order), AppRoute.FoodStation(frozen.selectorId),
            AppRoute.FoodHandoff(frozen.order), AppRoute.Drawer, AppRoute.Stock, AppRoute.Reports, AppRoute.Setup,
            AppRoute.SetupStep(0), AppRoute.Catalog, AppRoute.People, AppRoute.Purchasing, AppRoute.Devices, AppRoute.CompanySettings)
        admitted.forEach { assertTrue(foodCheckUiRoute(it), it.toString()) }
        excluded.forEach { assertFalse(foodCheckUiRoute(it), it.toString()) }
        val navigator = AppNavigator()
        navigator.replaceRoot(SecurityRoot.BRANCH_WORKSPACE, frozen.display())
        assertEquals(AppRoute.Orders, navigator.state.value.activeEntry.route)
        for (route in excluded) assertEquals(NavigationResult.IGNORED,
            navigator.navigate(NavigationAction.Push(route), navigator.token()), route.toString())
        assertEquals(NavigationResult.CHANGED,
            navigator.navigate(NavigationAction.Push(AppRoute.Food), navigator.token()))
        assertEquals(NavigationResult.CHANGED,
            navigator.navigate(NavigationAction.Push(AppRoute.FoodOrder(frozen.order)), navigator.token()))
        navigator.replaceRoot(SecurityRoot.BRANCH_WORKSPACE, frozen.display(emptySet()))
        assertEquals(AppRoute.SessionAccess, navigator.state.value.activeEntry.route)
    }

    @Test
    fun fcrp002And004OwnCheckAndUnboundMenuSelectorsDoNotAdmitBroaderReads() {
        val admitted = listOf(LocalFoodUiQuery.MyChecks(), LocalFoodUiQuery.MyChecks(frozen.order),
            LocalFoodUiQuery.Check(frozen.order), LocalFoodUiQuery.Check(frozen.order, frozen.portion), LocalFoodUiQuery.Menu())
        admitted.forEach { assertTrue(it.isFoodCheckUiQuery()) }
        frozen.excludedQueries().forEach { assertFalse(it.isFoodCheckUiQuery(), it.toString()) }
        // Menu() is only a read selector: no exact-order Add coordinate or revision is bound.
        assertNull(LocalFoodUiQuery.Menu().orderId)
        assertNull(LocalFoodUiQuery.Menu().expectedOrderStorageRevision)
    }

    @Test
    fun fcrp003And004OnlyDraft2AndQuotedAdd3ActionVersionsBelongToTheSubset() {
        assertEquals(setOf(LocalAction.FOOD_CREATE_DRAFT, LocalAction.FOOD_ADD), foodCheckUiActions)
        for (action in LocalAction.entries) for (version in 1..4) {
            val expected = action == LocalAction.FOOD_CREATE_DRAFT && version == 2 || action == LocalAction.FOOD_ADD && version == 3
            assertEquals(expected, action.isFoodCheckUiAction(version), "$action/$version")
        }
    }

    @Test
    fun fcrp003And013ExactDraftIntentLegacyFormAndUnboundAddStayDistinct() {
        val draft = frozen.draftIntent()
        assertTrue(draft.isFoodCheckUiIntent())
        assertEquals(frozen.order, (draft.values.single { it.field == LocalField.ORDER_ID }.value as LocalScalarValue.Identifier).value)
        assertFalse(frozen.draftIntent(1).isFoodCheckUiIntent())
        assertFalse(frozen.unboundAddIntent().isFoodCheckUiIntent())
        assertEquals(JsonNull, frozen.draftFixture.getValue("actual_recorded_revision"))
        assertEquals(JsonNull, frozen.addFixture.getValue("actual_recorded_revision"))
        assertEquals("NON_EXECUTABLE; revisions are explicit binding parameters, not wire values.", frozen.addFixture.string("template_status"))
        val request = frozen.addFixture.getValue("body_template").jsonObject.getValue("request").jsonObject
        assertEquals("\${order_revision_at_review}", request.string("expectedOrderRevision"))
        assertEquals("\${catalog_revision_at_review}", request.string("expectedCatalogRevision"))
        request.getValue("portions").jsonArray.forEach {
            assertEquals("\${recipe_revision_at_review}", it.jsonObject.string("expectedRecipeRevision"))
        }
    }

    @Test
    fun fcrp013PendingActionAndPhaseShapesCannotSupplyOriginalFormQualification() {
        // Pending DTOs contain no saved form version/canonical body; this is only their closed UI gate.
        for (action in LocalAction.entries) assertEquals(action in setOf(LocalAction.FOOD_CREATE_DRAFT, LocalAction.FOOD_ADD),
            frozen.pending(action).isFoodCheckUiPending(), action.name)
        for (action in listOf(LocalAction.FOOD_CREATE_DRAFT, LocalAction.FOOD_ADD)) {
            assertTrue(frozen.pending(action).copy(phase = LocalPendingPhase.DISPATCH_UNRESOLVED).isFoodCheckUiPending())
            assertTrue(frozen.pending(action, decision = LocalRecordedDecision.APPLIED).isFoodCheckUiPending())
            assertTrue(frozen.pending(action, decision = LocalRecordedDecision.REJECTED).isFoodCheckUiPending())
            assertFalse(frozen.pending(action, decision = LocalRecordedDecision.RECORDED_ONLY).isFoodCheckUiPending())
        }
    }
}
