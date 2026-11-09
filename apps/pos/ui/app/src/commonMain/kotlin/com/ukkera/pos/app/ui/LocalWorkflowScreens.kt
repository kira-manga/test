package com.ukkera.pos.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.ukkera.pos.app.EntryViewModel
import com.ukkera.pos.app.LocalAction
import com.ukkera.pos.app.LocalDraftValue
import com.ukkera.pos.app.LocalField
import com.ukkera.pos.app.LocalFoodUiQuery
import com.ukkera.pos.app.canAppearIn
import com.ukkera.pos.app.foodSelectionActions
import com.ukkera.pos.app.foodDraftFields
import com.ukkera.pos.app.foodUnavailableReason
import com.ukkera.pos.app.LocalFormField
import com.ukkera.pos.app.LocalInputKind
import com.ukkera.pos.app.LocalInventorySection
import com.ukkera.pos.app.LocalOpeningAllocationPage
import com.ukkera.pos.app.LocalOperationResult
import com.ukkera.pos.app.LocalPendingOperation
import com.ukkera.pos.app.LocalPendingPhase
import com.ukkera.pos.app.LocalPricePreviewDraft
import com.ukkera.pos.app.LocalPurchasingSection
import com.ukkera.pos.app.LocalReturnSection
import com.ukkera.pos.app.LocalRecord
import com.ukkera.pos.app.LocalRecordedDecision
import com.ukkera.pos.app.LocalRecordKind
import com.ukkera.pos.app.LocalRecordReference
import com.ukkera.pos.app.LocalRecordValue
import com.ukkera.pos.app.LocalScalarValue
import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.app.LocalSessionWorkspaceMode
import com.ukkera.pos.app.invoiceUiAction
import com.ukkera.pos.app.invoiceUiSections
import com.ukkera.pos.app.isInvoiceUiForm
import com.ukkera.pos.app.paymentCorrectionUiRoute
import com.ukkera.pos.app.LocalText
import com.ukkera.pos.app.LocalWorkBusy
import com.ukkera.pos.app.LocalWorkflow
import com.ukkera.pos.app.LocalWorkspaceState
import com.ukkera.pos.app.retailWorkflows
import com.ukkera.pos.app.customerReceiptUiRoute
import com.ukkera.pos.app.foodCheckUiRoute
import com.ukkera.pos.app.foodCashFinancialUiRoute
import com.ukkera.pos.app.foodTableAssignmentUiRoute
import com.ukkera.pos.app.UkkeraRuntime
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.app.navigation.NavigationToken
import com.ukkera.pos.app.navigation.RouteRegistry

/** Areas are presentation grouping, never a replacement for the service's per-action policy. */
internal fun localWorkflows(area: AppArea, workspaceMode: LocalSessionWorkspaceMode = LocalSessionWorkspaceMode.POLICY_FILTERED): List<LocalWorkflow> =
    if (workspaceMode == LocalSessionWorkspaceMode.SUPPLIER_ONLY || workspaceMode.invoiceUiAction() != null) {
        if (area == AppArea.PURCHASING) listOf(LocalWorkflow.PURCHASING) else emptyList()
    } else if (workspaceMode == LocalSessionWorkspaceMode.WAREHOUSE_ONLY) {
        if (area == AppArea.SETUP) listOf(LocalWorkflow.SETUP) else emptyList()
    } else if (workspaceMode == LocalSessionWorkspaceMode.REPORTS_ONLY) {
        if (area == AppArea.REPORTS) listOf(LocalWorkflow.REPORTS) else emptyList()
    } else if (workspaceMode == LocalSessionWorkspaceMode.STOCK_TRANSFERS_ONLY) {
        if (area == AppArea.STOCK) listOf(LocalWorkflow.INVENTORY) else emptyList()
    } else if (workspaceMode == LocalSessionWorkspaceMode.KITCHEN_ONLY) {
        if (area == AppArea.ORDERS) listOf(LocalWorkflow.KITCHEN) else emptyList()
    } else if (workspaceMode == LocalSessionWorkspaceMode.FOOD_CHECKS_ONLY ||
        workspaceMode == LocalSessionWorkspaceMode.FOOD_CASH_FINANCIAL_ONLY ||
        workspaceMode == LocalSessionWorkspaceMode.FOOD_TABLE_ASSIGNMENTS_ONLY) {
        if (area == AppArea.ORDERS) listOf(LocalWorkflow.FOOD) else emptyList()
    } else if (workspaceMode == LocalSessionWorkspaceMode.CUSTOMER_RECEIPTS_ONLY) {
        when (area) {
            AppArea.CASHIER -> listOf(LocalWorkflow.CHECKOUT)
            AppArea.ORDERS -> listOf(LocalWorkflow.ORDERS, LocalWorkflow.RETURNS)
            else -> emptyList()
        }
    } else if (workspaceMode == LocalSessionWorkspaceMode.RETAIL_ORDERS_ONLY ||
        workspaceMode == LocalSessionWorkspaceMode.PAYMENT_CORRECTIONS_ONLY) {
        when (area) {
            AppArea.CASHIER -> listOf(LocalWorkflow.CHECKOUT)
            AppArea.ORDERS -> listOf(LocalWorkflow.ORDERS)
            else -> emptyList()
        }
    } else when (area) {
    AppArea.CASHIER -> listOf(LocalWorkflow.CHECKOUT)
    AppArea.ORDERS -> listOf(LocalWorkflow.ORDERS, LocalWorkflow.RETURNS, LocalWorkflow.FOOD, LocalWorkflow.KITCHEN, LocalWorkflow.FULFILLMENT, LocalWorkflow.EXCEPTIONS)
    AppArea.DRAWER -> listOf(LocalWorkflow.CASH)
    AppArea.STOCK -> listOf(LocalWorkflow.INVENTORY, LocalWorkflow.RECEIVING)
    AppArea.CATALOG -> listOf(LocalWorkflow.CATALOG)
    AppArea.PURCHASING -> listOf(LocalWorkflow.PURCHASING, LocalWorkflow.RECEIVING)
    AppArea.REPORTS -> listOf(LocalWorkflow.REPORTS)
    AppArea.PEOPLE -> listOf(LocalWorkflow.PEOPLE)
    AppArea.DEVICES -> listOf(LocalWorkflow.DEVICES)
    else -> listOf(LocalWorkflow.SETUP)
}

@Composable
internal fun localProblem(problem: LocalServiceProblem): String = when (problem) {
    LocalServiceProblem.NOT_CONFIGURED -> copy(Copy.LOCAL_NOT_CONFIGURED)
    LocalServiceProblem.NOT_IMPLEMENTED -> copy(Copy.LOCAL_NOT_IMPLEMENTED)
    LocalServiceProblem.DENIED -> copy(Copy.LOCAL_DENIED)
    LocalServiceProblem.SESSION_ENDED -> copy(Copy.LOCAL_SESSION_ENDED)
    LocalServiceProblem.UNAVAILABLE -> copy(Copy.LOCAL_UNAVAILABLE)
    LocalServiceProblem.STORAGE_UNCERTAIN -> copy(Copy.LOCAL_STORAGE_UNCERTAIN)
    LocalServiceProblem.INVALID_INPUT -> copy(Copy.LOCAL_INVALID_INPUT)
    LocalServiceProblem.RETRY_LATER -> copy(Copy.LOCAL_RETRY_LATER)
    LocalServiceProblem.RETURN_CAPACITY_REQUIRED -> LocalAppLanguage.current.text(LocalText(
        "The complete return record does not fit this read. No claim, entitlement or refund was shown as absent or zero.",
        "لا يتسع هذا الفحص لسجل المرتجع الكامل. لم تُعرض المطالبة أو الاستحقاق أو المبالغ المستردة كغائبة أو صفرية."))
    LocalServiceProblem.RETURN_RESTART_REQUIRED -> LocalAppLanguage.current.text(LocalText(
        "The return history or entitlement changed. Refresh from the first page; no different revisions were combined.",
        "تغير سجل المرتجع أو الاستحقاق. حدّث القراءة من الصفحة الأولى؛ لم تُدمج مراجعات مختلفة."))
    LocalServiceProblem.PURCHASING_CAPACITY_REQUIRED -> LocalAppLanguage.current.text(LocalText(
        "The complete purchasing history does not fit this read. Nothing was shown as empty, zero or matched. A bounded history/paging extension is required.",
        "لا يتسع هذا الفحص لسجل الشراء الكامل. لم تُعرض البيانات كفارغة أو صفرية أو مطابقة. يلزم دعم قراءة السجل على أجزاء محدودة."))
    LocalServiceProblem.FOOD_TARGET_UNAVAILABLE, LocalServiceProblem.FOOD_CAPACITY_REQUIRED,
    LocalServiceProblem.FOOD_NOT_PRECOLLECTION, LocalServiceProblem.FOOD_PAYMENT_UNRESOLVED -> LocalAppLanguage.current.text(foodUnavailableText(checkNotNull(foodUnavailableReason(problem))))
    LocalServiceProblem.REPORT_RESTART_REQUIRED -> LocalAppLanguage.current.text(LocalText(
        "The branch read revision changed. This page was discarded; restart from the first page. No pages were combined.",
        "تغيرت مراجعة قراءة الفرع. أُهملت هذه الصفحة؛ ابدأ من الصفحة الأولى. لم تُدمج الصفحات."))
    LocalServiceProblem.CASH_RESTART_REQUIRED -> LocalAppLanguage.current.text(LocalText(
        "The cash-session revision changed. Refresh from the first page before continuing; no count or revision was guessed or combined.",
        "تغيرت مراجعة الجلسة النقدية. حدّث القراءة من الصفحة الأولى قبل المتابعة؛ لم يُفترض أو يُدمج عد أو مراجعة."))
}

@Composable
private fun workflowTitle(workflow: LocalWorkflow): String = when (workflow) {
    LocalWorkflow.CHECKOUT -> copy(Copy.CASHIER)
    LocalWorkflow.ORDERS -> copy(Copy.ORDERS)
    LocalWorkflow.CASH -> copy(Copy.DRAWER)
    LocalWorkflow.CATALOG -> copy(Copy.CATALOG)
    LocalWorkflow.RECEIVING -> copy(Copy.RECEIVING)
    LocalWorkflow.INVENTORY -> copy(Copy.STOCK)
    LocalWorkflow.PURCHASING -> copy(Copy.PURCHASING)
    LocalWorkflow.KITCHEN -> copy(Copy.KITCHEN_WORKFLOW)
    LocalWorkflow.FULFILLMENT -> copy(Copy.FULFILLMENT)
    LocalWorkflow.EXCEPTIONS -> copy(Copy.EXCEPTIONS)
    LocalWorkflow.REPORTS -> copy(Copy.REPORTS)
    LocalWorkflow.PEOPLE -> copy(Copy.PEOPLE)
    LocalWorkflow.DEVICES -> copy(Copy.DEVICES)
    LocalWorkflow.SETUP -> copy(Copy.SETUP)
    LocalWorkflow.FOOD -> foodCopy(FoodCopy.TITLE)
    LocalWorkflow.RETURNS -> LocalAppLanguage.current.text(LocalText("Returns and remedy claims", "المرتجعات ومطالبات المعالجة"))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocalWorkflowScreen(runtime: UkkeraRuntime, route: AppRoute, model: EntryViewModel, token: NavigationToken) {
    val area = RouteRegistry.area(route)
    val runtimePresentation by runtime.presentation.collectAsState()
    val supplierOnly = runtimePresentation.display?.workspaceMode == LocalSessionWorkspaceMode.SUPPLIER_ONLY
    val invoiceAction = runtimePresentation.display?.workspaceMode?.invoiceUiAction()
    val invoiceOnly = invoiceAction != null
    val invoiceCaptureOnly = runtimePresentation.display?.workspaceMode == LocalSessionWorkspaceMode.INVOICE_CAPTURE_ONLY
    val invoiceMatchingOnly = runtimePresentation.display?.workspaceMode == LocalSessionWorkspaceMode.INVOICE_MATCHING_ONLY
    val warehouseOnly = runtimePresentation.display?.workspaceMode == LocalSessionWorkspaceMode.WAREHOUSE_ONLY
    val reportsOnly = runtimePresentation.display?.workspaceMode == LocalSessionWorkspaceMode.REPORTS_ONLY
    val stockTransfersOnly = runtimePresentation.display?.workspaceMode == LocalSessionWorkspaceMode.STOCK_TRANSFERS_ONLY
    val kitchenOnly = runtimePresentation.display?.workspaceMode == LocalSessionWorkspaceMode.KITCHEN_ONLY
    val foodChecksOnly = runtimePresentation.display?.workspaceMode == LocalSessionWorkspaceMode.FOOD_CHECKS_ONLY
    val foodCashFinancialOnly = runtimePresentation.display?.workspaceMode == LocalSessionWorkspaceMode.FOOD_CASH_FINANCIAL_ONLY
    val foodTableAssignmentsOnly = runtimePresentation.display?.workspaceMode == LocalSessionWorkspaceMode.FOOD_TABLE_ASSIGNMENTS_ONLY
    val paymentCorrectionsOnly = runtimePresentation.display?.workspaceMode == LocalSessionWorkspaceMode.PAYMENT_CORRECTIONS_ONLY
    val customerReceiptsOnly = runtimePresentation.display?.workspaceMode == LocalSessionWorkspaceMode.CUSTOMER_RECEIPTS_ONLY
    if ((supplierOnly || invoiceOnly) && area != AppArea.PURCHASING || warehouseOnly && area != AppArea.SETUP || reportsOnly && area != AppArea.REPORTS ||
        stockTransfersOnly && area != AppArea.STOCK || kitchenOnly && (area != AppArea.ORDERS ||
            route is AppRoute.Order || route == AppRoute.Food || route is AppRoute.FoodOrder || route is AppRoute.FoodHandoff) ||
        paymentCorrectionsOnly && !paymentCorrectionUiRoute(route) ||
        customerReceiptsOnly && !customerReceiptUiRoute(route) || foodChecksOnly && !foodCheckUiRoute(route) ||
        foodCashFinancialOnly && !foodCashFinancialUiRoute(route) ||
        foodTableAssignmentsOnly && !foodTableAssignmentUiRoute(route)) {
        LiveUnavailableScreen(area); return
    }
    // Administration has its own protected endpoint/control journal, never generic business DTOs.
    if (area == AppArea.PEOPLE || area == AppArea.DEVICES) {
        LocalAdministrationScreen(runtime, area, model, token)
        return
    }
    val workflows = localWorkflows(area, runtimePresentation.display?.workspaceMode ?: LocalSessionWorkspaceMode.POLICY_FILTERED)
    val active = runtime.navigator.isActiveEntry(token)
    // The navigator clears synchronously on real departure, even if away/back coalesces.
    // Disposal is only a fallback; a lazy-list removal is not an entry departure.
    DisposableEffect(runtime, model, active) {
        if (!active) model.clearLocalConfigurationDrafts()
        onDispose { if (!runtime.navigator.isActiveEntry(token)) model.clearLocalConfigurationDrafts() }
    }
    // Observe the accepted key without replacing navigation/entry owners or their safe drafts.
    val controller = model.localWorkflows(runtime, token, runtimePresentation.localAttachment)
    if (controller == null) { LiveUnavailableScreen(area); return }
    val state by controller.state.collectAsState()
    if (reportsOnly) {
        // The same current-document controller/screen, without entering generic editors,
        // publication drafts, physical controls or unrelated pending-operation UI.
        DisposableEffect(controller, active) {
            if (active) controller.activate(LocalWorkflow.REPORTS)
            onDispose { controller.deactivate() }
        }
        if (!active) return
        if (state.workflow != LocalWorkflow.REPORTS || state.search.isNotEmpty() || state.reference != null ||
            state.form != null || state.formInput != null || state.referencePicker != null || state.openingCounts != null ||
            state.priceDraft != null || state.pricePreview != null || state.foodPricePreview != null ||
            state.operation != null || state.operationId != null || state.pending.isNotEmpty() || state.pendingCursor != null ||
            state.actionApproval != null || state.purchasingDocuments.hasDraft ||
            state.page?.let { page -> page.workflow != LocalWorkflow.REPORTS || page.branchReport == null ||
                page.records.isNotEmpty() || page.forms.isNotEmpty() || page.nextCursor != null ||
                page.priceControls != null || page.food != null || page.openingAllocations != null ||
                page.managerQuoteDetails != null || page.returnRights != null } == true) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Notice(LocalAppLanguage.current.text(LocalText(
                    "This reports-only connection cannot continue an unrelated retained selection or operation. Review it through its compatible original workflow; its evidence has not been replaced or acknowledged.",
                    "لا يمكن لاتصال التقارير فقط متابعة اختيار أو عملية محفوظة غير مرتبطة بالتقارير. راجعها عبر مسارها الأصلي المتوافق؛ لم يُستبدل دليلها أو يُؤكد استلام نتيجتها.")), caution = true)
                LocalSessionAccessScreen(runtime, token)
            }
            return
        }
        LocalBranchReportScreen(state, controller.reportDisplayCurrent(token),
            selectSection = { controller.selectBranchReportSection(it, token) },
            selectReportSale = { controller.selectReportSale(it, token) },
            restart = { controller.restartBranchReport(token) },
            next = { controller.nextBranchReportPage(token) },
            retry = { controller.retryBranchReport(token) },
            filesBound = controller.reportFilesBound(),
            export = { controller.exportBranchReport(token, it) },
            exportHistory = { controller.branchReportExportHistory(token, it) },
            recoverExport = { controller.recoverBranchReportExport(it, token) },
            cancelExport = { controller.cancelBranchReportExport(token) })
        return
    }
    val retail by controller.retailPhysical.state.collectAsState()
    val supplier by controller.supplierPhysical.state.collectAsState()
    val companyPresentation = model.companyCatalogDrafts.lifetime.identity
    val foodPresentation = model.foodConfigurationDrafts.lifetime.identity
    val returnRightsPresentation = model.returnRightsDrafts.lifetime.identity
    val pricePolicyPresentation = model.pricePolicyDrafts.lifetime.identity
    val serialRegistrationPresentation = model.serialRegistrationDrafts.lifetime.identity
    var serialRegistrationDraft by remember(controller, serialRegistrationPresentation) { mutableStateOf(false) }
    var serialRegistrationWorking by remember(controller, serialRegistrationPresentation) { mutableStateOf(false) }
    val catalogReleasePresentation = model.catalogReleaseDrafts.lifetime.identity
    var companyCatalogDraft by remember(controller, companyPresentation) { mutableStateOf(false) }
    var companyCatalogWorking by remember(controller, companyPresentation) { mutableStateOf(false) }
    var foodConfigurationDraft by remember(controller, foodPresentation) { mutableStateOf(false) }
    var foodConfigurationWorking by remember(controller, foodPresentation) { mutableStateOf(false) }
    var returnRightsDraft by remember(controller, returnRightsPresentation) { mutableStateOf(false) }
    var returnRightsWorking by remember(controller, returnRightsPresentation) { mutableStateOf(false) }
    var pricePolicyDraft by remember(controller, pricePolicyPresentation) { mutableStateOf(false) }
    var pricePolicyWorking by remember(controller, pricePolicyPresentation) { mutableStateOf(false) }
    var catalogReleaseDraft by remember(controller, catalogReleasePresentation) { mutableStateOf(false) }
    var catalogReleaseWorking by remember(controller, catalogReleasePresentation) { mutableStateOf(false) }
    val serialRegistrationHasDraft = serialRegistrationDraft || model.serialRegistrationDrafts.hasDraft
    val serialRegistrationBusy = serialRegistrationHasDraft || serialRegistrationWorking
    val companyHasDraft = companyCatalogDraft || model.companyCatalogDrafts.hasDraft
    val foodHasDraft = foodConfigurationDraft || model.foodConfigurationDrafts.hasDraft
    val returnRightsHasDraft = returnRightsDraft || model.returnRightsDrafts.hasDraft
    val pricePolicyHasDraft = pricePolicyDraft || model.pricePolicyDrafts.hasDraft
    val catalogReleaseHasDraft = catalogReleaseDraft || model.catalogReleaseDrafts.hasDraft
    val companyCatalogBusy = companyHasDraft || companyCatalogWorking
    val foodConfigurationBusy = foodHasDraft || foodConfigurationWorking
    val returnRightsBusy = returnRightsHasDraft || returnRightsWorking
    val pricePolicyBusy = pricePolicyHasDraft || pricePolicyWorking
    val catalogReleaseBusy = catalogReleaseHasDraft || catalogReleaseWorking
    val otherBusy = state.working || state.actionApproval != null || retail.working || retail.instruction != null || retail.preview != null ||
        supplier.working || supplier.instruction != null || supplier.preview != null
    val localBusy = otherBusy || companyCatalogBusy || foodConfigurationBusy || returnRightsBusy || pricePolicyBusy || catalogReleaseBusy || serialRegistrationBusy ||
        state.attachmentReviewRequired || state.purchasingDocuments.hasDraft || state.actionApproval != null
    val initialFood = when (route) {
        AppRoute.Food -> LocalFoodUiQuery.MyChecks()
        is AppRoute.FoodOrder -> LocalFoodUiQuery.Check(route.orderId)
        is AppRoute.FoodStation -> LocalFoodUiQuery.StationLabel(route.stationId)
        is AppRoute.FoodHandoff -> LocalFoodUiQuery.IncomingKnownOrder(route.orderId)
        else -> null
    }
    DisposableEffect(controller, active) {
        if (active) controller.activate(if (kitchenOnly) LocalWorkflow.KITCHEN else if (initialFood != null) LocalWorkflow.FOOD else workflows.first(),
            (route as? AppRoute.Order)?.let { LocalRecordReference(LocalRecordKind.ORDER, it.orderId) }, food = initialFood)
        else {
            companyCatalogDraft = false; companyCatalogWorking = false
            foodConfigurationDraft = false; foodConfigurationWorking = false
            returnRightsDraft = false; returnRightsWorking = false
            pricePolicyDraft = false; pricePolicyWorking = false
            catalogReleaseDraft = false; catalogReleaseWorking = false
            serialRegistrationDraft = false; serialRegistrationWorking = false
        }
        onDispose { controller.deactivate() }
    }
    // Reattach dirty guards even when the editor's lazy-list item is currently offscreen.
    SideEffect {
        if (active) {
            controller.companyCatalogGuard(companyHasDraft, companyCatalogWorking)
            controller.foodConfigurationGuard(foodHasDraft, foodConfigurationWorking)
            controller.returnRightsGuard(returnRightsHasDraft, returnRightsWorking)
            controller.pricePolicyGuard(pricePolicyHasDraft, pricePolicyWorking)
            controller.catalogReleaseGuard(catalogReleaseHasDraft, catalogReleaseWorking)
            controller.serialRegistrationGuard(serialRegistrationHasDraft, serialRegistrationWorking)
        }
    }
    // Outgoing Nav3 animation entries neither display retained private data nor capture callbacks.
    if (!active) return
    if (invoiceOnly && !controller.invoiceWorkspaceCompatible()) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Notice(LocalAppLanguage.current.text(if (invoiceMatchingOnly) LocalText(
                "This matching-only connection cannot continue the retained selection, draft or other original work. Review it through its compatible original workflow. Its original identity and evidence remain retained; nothing was submitted or acknowledged here.",
                "لا يمكن لاتصال المطابقة فقط متابعة الاختيار أو المسودة المحفوظة أو العمل الأصلي الآخر. راجعه عبر مساره الأصلي المتوافق. تبقى هويته وأدلته الأصلية محفوظة؛ ولم يُرسل شيء أو يُؤكد استلام نتيجة هنا.") else LocalText(
                "This capture-only connection cannot continue the retained selection, draft or other original work. Review it through its compatible original workflow. Its original identity and evidence remain retained; nothing was submitted or acknowledged here.",
                "لا يمكن لاتصال الحفظ فقط متابعة الاختيار أو المسودة المحفوظة أو العمل الأصلي الآخر. راجعه عبر مساره الأصلي المتوافق. تبقى هويته وأدلته الأصلية محفوظة؛ ولم يُرسل شيء أو يُؤكد استلام نتيجة هنا.")), caution = true)
            LocalSessionAccessScreen(runtime, token)
        }
        return
    }
    if (foodTableAssignmentsOnly && !controller.foodTableAssignmentWorkspaceCompatible()) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Notice(LocalAppLanguage.current.text(LocalText(
                "Review this retained selection or request through its compatible original workflow. This table-assignment connection cannot continue it. Its original evidence remains retained.",
                "راجع هذا الاختيار أو الطلب المحفوظ عبر مساره الأصلي المتوافق. لا يمكن لاتصال تعيين الطاولات هذا متابعته. يبقى دليله الأصلي محفوظًا.")), caution = true)
            LocalSessionAccessScreen(runtime, token)
        }
        return
    }
    if (foodChecksOnly && !controller.foodCheckWorkspaceCompatible()) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Notice(LocalAppLanguage.current.text(LocalText(
                "Review this retained selection or request through its compatible original workflow. This food-check connection cannot continue it. Its original evidence remains retained.",
                "راجع هذا الاختيار أو الطلب المحفوظ عبر مساره الأصلي المتوافق. لا يمكن لاتصال طلبات الطعام هذا متابعته. يبقى دليله الأصلي محفوظًا.")), caution = true)
            LocalSessionAccessScreen(runtime, token)
        }
        return
    }
    if (foodCashFinancialOnly && !controller.foodCashFinancialWorkspaceCompatible()) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Notice(LocalAppLanguage.current.text(LocalText(
                "This financial-only connection cannot continue the retained selection, physical action or incompatible original request. Review it through its compatible original workflow. Its original identity and evidence remain retained; nothing was submitted or acknowledged here.",
                "لا يمكن لاتصال الإجراءات المالية فقط متابعة الاختيار المحفوظ أو الإجراء الفعلي أو الطلب الأصلي غير المتوافق. راجعه عبر مساره الأصلي المتوافق. تبقى هويته وأدلته الأصلية محفوظة؛ ولم يُرسل شيء أو يُؤكد استلام نتيجة هنا.")), caution = true)
            LocalSessionAccessScreen(runtime, token)
        }
        return
    }
    if (paymentCorrectionsOnly && !controller.paymentCorrectionWorkspaceCompatible()) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Notice(LocalAppLanguage.current.text(LocalText(
                "Review this retained selection or request through its compatible original workflow. This historical payment correction connection cannot continue it. Its original evidence remains retained.",
                "راجع هذا الاختيار أو الطلب المحفوظ عبر مساره الأصلي المتوافق. لا يمكن لاتصال تصحيح تصنيف المدفوعات التاريخية هذا متابعته. يبقى دليله الأصلي محفوظًا.")), caution = true)
            LocalSessionAccessScreen(runtime, token)
        }
        return
    }
    val kitchenActions = setOf(LocalAction.FOOD_STATION_START, LocalAction.FOOD_STATION_READY)
    if (kitchenOnly && (state.workflow != LocalWorkflow.KITCHEN || !state.foodQuery.canAppearIn(LocalWorkflow.KITCHEN) ||
            state.search.isNotEmpty() || state.reference != null ||
            state.form?.descriptor?.let { it.action !in kitchenActions || it.version != 1 } == true ||
            state.formInput?.let { it.action !in kitchenActions || it.version != 1 } == true ||
            state.page?.let { page -> page.workflow != LocalWorkflow.KITCHEN || page.food == null ||
                !page.food.query.canAppearIn(LocalWorkflow.KITCHEN) || page.records.isNotEmpty() || page.nextCursor != null ||
                page.forms.any { it.action !in kitchenActions || it.version != 1 } || page.priceControls != null ||
                page.returnRights != null || page.branchReport != null || page.openingAllocations != null ||
                page.managerQuoteDetails != null } == true ||
            state.pending.any { it.action !in kitchenActions } ||
            (state.operation as? LocalOperationResult.Pending)?.operation?.action?.let { it !in kitchenActions } == true ||
            (state.operation as? LocalOperationResult.Recorded)?.let { it.food == null ||
                it.food.followUp?.canAppearIn(LocalWorkflow.KITCHEN) == false || it.result != null || it.returnStock != null } == true ||
            state.referencePicker != null || state.openingCounts != null || state.priceDraft != null ||
            state.pricePreview != null || state.foodPricePreview != null || state.actionApproval != null ||
            state.purchasingDocuments.hasDraft)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Notice(LocalAppLanguage.current.text(LocalText(
                "Review this retained selection or request through its compatible original workflow. This kitchen connection cannot continue it. Its original evidence remains retained.",
                "راجع هذا الاختيار أو الطلب المحفوظ عبر مساره الأصلي المتوافق. لا يمكن لاتصال المطبخ هذا متابعته. يبقى دليله الأصلي محفوظًا.")), caution = true)
            LocalSessionAccessScreen(runtime, token)
        }
        return
    }
    if (supplierOnly && (state.workflow != LocalWorkflow.PURCHASING ||
            state.purchasingQuery.section != LocalPurchasingSection.SUPPLIERS || state.purchasingQuery.includeCosts ||
            state.reference?.let { it.kind != LocalRecordKind.SUPPLIER || it.parentId != null } == true ||
            state.form?.descriptor?.let { it.action !in setOf(LocalAction.SUPPLIER_CREATE, LocalAction.SUPPLIER_UPDATE) || it.version != 1 } == true ||
            state.formInput?.let { it.action !in setOf(LocalAction.SUPPLIER_CREATE, LocalAction.SUPPLIER_UPDATE) || it.version != 1 } == true ||
            state.actionApproval != null || state.purchasingDocuments.hasDraft)) {
        // Do not reinterpret a retained unrelated editor/selection as a new supplier request.
        // Its input and durable originals remain with their owners; no replacement is minted.
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Notice(LocalAppLanguage.current.text(LocalText(
                "The retained selection or draft is outside this supplier-only connection. It cannot be continued here; its evidence has not been replaced or acknowledged. Use a compatible original workflow to review it.",
                "الاختيار أو المسودة المحفوظة خارج نطاق اتصال الموردين فقط. لا يمكن متابعتها هنا؛ لم يُستبدل دليلها أو يُؤكد استلام نتيجتها. استخدم المسار الأصلي المتوافق لمراجعتها.")), caution = true)
            LocalSessionAccessScreen(runtime, token)
        }
        return
    }
    if (warehouseOnly && (state.workflow != LocalWorkflow.SETUP ||
            state.reference?.let { it.kind != LocalRecordKind.WAREHOUSE || it.parentId != null } == true ||
            state.form?.descriptor?.let { it.action != LocalAction.WAREHOUSE_CONFIGURE || it.version != 1 } == true ||
            state.formInput?.let { it.action != LocalAction.WAREHOUSE_CONFIGURE || it.version != 1 } == true ||
            state.page?.let { page -> page.workflow != LocalWorkflow.SETUP ||
                page.records.any { it.reference.kind != LocalRecordKind.WAREHOUSE || it.reference.parentId != null } ||
                page.forms.any { it.action != LocalAction.WAREHOUSE_CONFIGURE || it.version != 1 } || page.returnRights != null ||
                page.priceControls != null || page.food != null || page.openingAllocations != null ||
                page.branchReport != null || page.managerQuoteDetails != null } == true ||
            state.pending.any { it.action != LocalAction.WAREHOUSE_CONFIGURE } ||
            state.referencePicker?.source?.kind?.let { it != LocalRecordKind.WAREHOUSE } == true ||
            state.actionApproval != null || state.purchasingDocuments.hasDraft)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Notice(LocalAppLanguage.current.text(LocalText(
                "Review this retained selection or draft through its compatible original workflow. This warehouse connection cannot continue it. Its original evidence remains retained.",
                "راجع هذا الاختيار أو المسودة المحفوظة عبر مسارها الأصلي المتوافق. لا يمكن لاتصال المخازن هذا متابعتها. يبقى دليلها الأصلي محفوظًا.")), caution = true)
            LocalSessionAccessScreen(runtime, token)
        }
        return
    }
    if (stockTransfersOnly && (state.workflow != LocalWorkflow.INVENTORY ||
            state.inventorySection !in setOf(LocalInventorySection.QUANTITIES, LocalInventorySection.COSTS) ||
            state.reference?.let { reference -> when (reference.kind) {
                LocalRecordKind.SKU, LocalRecordKind.TRANSFER -> reference.parentId != null || reference.revision != null
                LocalRecordKind.STOCK_CUT -> reference.parentId == null || reference.revision != null
                else -> true
            } } == true ||
            state.form?.descriptor?.let { it.action != LocalAction.WAREHOUSE_TRANSFER || it.version != 1 } == true ||
            state.formInput?.let { it.action != LocalAction.WAREHOUSE_TRANSFER || it.version != 1 } == true ||
            state.page?.let { page -> page.workflow != LocalWorkflow.INVENTORY ||
                page.forms.any { it.action != LocalAction.WAREHOUSE_TRANSFER || it.version != 1 } ||
                page.records.any { record -> record.reference.kind !in setOf(LocalRecordKind.SKU, LocalRecordKind.STOCK_CUT,
                    LocalRecordKind.TRANSFER, LocalRecordKind.STOCK_VALUE_POOL) &&
                    !(record.reference.kind == LocalRecordKind.RECEIPT_LINE && !record.selectable && record.reference.parentId != null) } ||
                page.returnRights != null || page.priceControls != null || page.food != null || page.openingAllocations != null ||
                page.branchReport != null || page.managerQuoteDetails != null } == true ||
            state.pending.any { it.action != LocalAction.WAREHOUSE_TRANSFER } ||
            (state.operation as? LocalOperationResult.Pending)?.operation?.action?.let { it != LocalAction.WAREHOUSE_TRANSFER } == true ||
            (state.operation as? LocalOperationResult.Recorded)?.result?.reference?.kind?.let { it != LocalRecordKind.TRANSFER } == true ||
            state.referencePicker?.source?.let { it.kind != LocalRecordKind.WAREHOUSE || it.excludedId == null } == true ||
            state.actionApproval != null || state.purchasingDocuments.hasDraft || state.openingCounts != null)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Notice(LocalAppLanguage.current.text(LocalText(
                "Review this retained selection or request through its compatible original workflow. This stock-transfer connection cannot continue it. Its original evidence remains retained.",
                "راجع هذا الاختيار أو الطلب المحفوظ عبر مساره الأصلي المتوافق. لا يمكن لاتصال نقل المخزون هذا متابعته. يبقى دليله الأصلي محفوظًا.")), caution = true)
            LocalSessionAccessScreen(runtime, token)
        }
        return
    }
    if (area == AppArea.REPORTS) {
        if (state.workflow == LocalWorkflow.REPORTS) LocalBranchReportScreen(
            state, controller.reportDisplayCurrent(token),
            selectSection = { controller.selectBranchReportSection(it, token) },
            selectReportSale = { controller.selectReportSale(it, token) },
            restart = { controller.restartBranchReport(token) },
            next = { controller.nextBranchReportPage(token) },
            retry = { controller.retryBranchReport(token) },
            filesBound = controller.reportFilesBound(),
            export = { controller.exportBranchReport(token, it) },
            exportHistory = { controller.branchReportExportHistory(token, it) },
            recoverExport = { controller.recoverBranchReportExport(it, token) },
            cancelExport = { controller.cancelBranchReportExport(token) })
        return
    }
    val language = LocalAppLanguage.current
    val searchFocus = remember(controller) { FocusRequester() }
    var discardForm by remember(controller, companyPresentation, foodPresentation, returnRightsPresentation, pricePolicyPresentation, catalogReleasePresentation) { mutableStateOf(false) }
    var review by remember(controller, companyPresentation, foodPresentation, returnRightsPresentation, pricePolicyPresentation, catalogReleasePresentation) { mutableStateOf<LocalPendingOperation?>(null) }
    fun employeeConfigurationAccessDenied() {
        if (!model.denyLocalConfigurationAccess(controller)) return
        companyCatalogDraft = false; companyCatalogWorking = false
        foodConfigurationDraft = false; foodConfigurationWorking = false
        returnRightsDraft = false; returnRightsWorking = false
        pricePolicyDraft = false; pricePolicyWorking = false
        catalogReleaseDraft = false; catalogReleaseWorking = false
        serialRegistrationDraft = false; serialRegistrationWorking = false
        controller.companyCatalogGuard(false, false)
        controller.foodConfigurationGuard(false, false)
        controller.returnRightsGuard(false, false)
        controller.pricePolicyGuard(false, false)
        controller.catalogReleaseGuard(false, false)
        controller.serialRegistrationGuard(false, false)
        review = null; discardForm = false
    }
    // Generic DENIED clears holders synchronously through the entry owner. Their identities
    // reset transient presentation above; no delayed effect can clear a replacement entry.
    Box(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.K && event.isCtrlPressed && state.form == null &&
            !localBusy && !state.workflow.usesFoodQueries) {
            searchFocus.requestFocus()
            true
        } else false
    }, contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 1100.dp).fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                SectionHeading(when {
                    invoiceMatchingOnly -> language.text(LocalText("Captured invoice matching and payable recognition", "مطابقة الفاتورة المحفوظة وإثبات المستحق"))
                    invoiceCaptureOnly -> language.text(LocalText("Supplier invoice claim capture", "حفظ مطالبة فاتورة المورد"))
                    supplierOnly -> language.text(LocalText("Suppliers", "الموردون"))
                    warehouseOnly -> language.text(LocalText("Warehouses", "المخازن"))
                    stockTransfersOnly -> language.text(LocalText("Same-branch stock transfers", "نقل المخزون داخل الفرع"))
                    paymentCorrectionsOnly -> language.text(LocalText("Historical payment classification", "تصنيف المدفوعات التاريخية"))
                    foodCashFinancialOnly -> language.text(LocalText("Food collection accounting and original recovery", "محاسبة تحصيل الطعام واسترداد النتائج الأصلية"))
                    foodTableAssignmentsOnly -> language.text(LocalText("Managed table assignment", "تعيين الطاولة المُدارة"))
                    else -> workflowTitle(state.workflow)
                }, if (paymentCorrectionsOnly) language.text(LocalText(
                    "Look up the original sale, select its payment and review linked historical classifications.",
                    "ابحث عن البيع الأصلي واختر دفعه وراجع التصنيفات التاريخية المرتبطة.")) else copy(Copy.LOCAL_WORKFLOW_HELP))
                if (paymentCorrectionsOnly) Notice(language.text(LocalText(
                    "Enter payment-correction:SALE_UUID, then select the original payment to review its history. The correction records a historical classification with exact original identifiers, amount, reason and evidence. Save and submit separately, then recover and acknowledge the original result. Current Master permission is required for every read and operation.",
                    "أدخل payment-correction:SALE_UUID ثم اختر الدفع الأصلي لمراجعة سجله. يسجل التصحيح تصنيفًا تاريخيًا بالمعرفات والمبلغ والسبب والإثبات الأصلية الدقيقة. احفظ وأرسل في خطوتين منفصلتين ثم استرد النتيجة الأصلية وأكد استلامها. تتطلب كل قراءة وعملية إذنًا حاليًا من الجهاز الرئيسي.")))
                if (supplierOnly) Notice(language.text(LocalText(
                    "Supplier directory only. Each page is a fresh current read; changes, original recovery and acknowledgement require their own current Master authorization. Other purchasing and physical workflows are unavailable on this connection.",
                    "دليل الموردين فقط. كل صفحة قراءة حالية جديدة؛ ويتطلب التعديل واستعادة العملية الأصلية وتأكيد استلام النتيجة إذنًا ساريًا خاصًا من الجهاز الرئيسي. لا تتوفر مسارات الشراء الأخرى أو الإجراءات الفعلية على هذا الاتصال.")))
                if (invoiceCaptureOnly) Notice(language.text(LocalText(
                    "Capture the supplier's actual goods, amounts and literal references. An existing purchase order is optional context. Review and save, then submit separately; recover and acknowledge the same original result. Captured claims remain unmatched and unapproved and create no receipt, stock, recognized payable or payment. Reads, costs, capture and recovery each require current Master permission. Other purchasing, configuration and physical workflows are unavailable on this connection.",
                    "احفظ بضاعة المورد ومبالغه ومراجعه النصية الفعلية. أمر الشراء الموجود سياق اختياري. راجع واحفظ ثم أرسل في خطوة منفصلة؛ واستعد النتيجة الأصلية نفسها وأكد استلامها. تبقى المطالبة غير مطابقة وغير معتمدة ولا تنشئ استلامًا أو مخزونًا أو مستحقًا معترفًا به أو دفعًا. تتطلب القراءة والتكلفة والحفظ والاسترداد إذنًا حاليًا من الجهاز الرئيسي لكل منها. لا تتوفر مسارات الشراء الأخرى أو الإعداد أو الإجراءات الفعلية على هذا الاتصال.")))
                if (invoiceMatchingOnly) Notice(language.text(LocalText(
                    "Select a captured invoice and the exact accepted goods from its approved purchase order and receipt. Review and save the original request, then submit separately; recover and acknowledge the same original result. Matching authority is checked against the full original supplier claim, not only this slice. Reads, costs, matching and recovery each need current Master permission. This does not alter the original claim, approve discrepancies, move stock or pay the supplier. Capture, legacy matching, credit, settlement, configuration and physical workflows are unavailable on this connection.",
                    "اختر فاتورة محفوظة والبضاعة المقبولة الدقيقة من أمر شرائها المعتمد والاستلام. راجع الطلب الأصلي واحفظه ثم أرسله في خطوة منفصلة؛ واستعد نتيجته الأصلية نفسها وأكد استلامها. تُفحص صلاحية المطابقة مقابل مطالبة المورد الأصلية كاملة، لا هذا الجزء فقط. تتطلب القراءة والتكلفة والمطابقة والاسترداد إذنًا حاليًا من الجهاز الرئيسي لكل منها. لا يغير ذلك المطالبة الأصلية ولا يعتمد فروقًا ولا ينقل مخزونًا ولا يدفع للمورد. لا يتوفر على هذا الاتصال الحفظ أو المطابقة القديمة أو إشعارات الدائن أو التسوية أو الإعداد أو الإجراءات الفعلية.")))
                if (warehouseOnly) Notice(language.text(LocalText(
                    "Browse the warehouse directory, create a warehouse or rename the selected warehouse. Each page and operation requires current Master permission. Recover and acknowledge the saved original result before starting another change. Warehouse retirement requires a separate procedure.",
                    "تصفح دليل المخازن وأنشئ مخزنًا أو أعد تسمية المخزن المختار. تتطلب كل صفحة وعملية إذنًا ساريًا من الجهاز الرئيسي. استعد النتيجة الأصلية المحفوظة وأكد استلامها قبل بدء تعديل آخر. يتطلب إيقاف المخزن إجراءً منفصلًا.")))
                if (stockTransfersOnly) Notice(language.text(LocalText(
                    "Select an item, then its exact position and an active destination warehouse. Review quantity and reason before saving the immutable transfer. Recover and acknowledge its original result before starting another transfer. Costs and value history require separate permission.",
                    "اختر صنفًا ثم موضعه الدقيق ومخزنًا وجهة نشطًا. راجع الكمية والسبب قبل حفظ طلب النقل غير القابل للتعديل. استعد نتيجته الأصلية وأكد استلامها قبل بدء نقل آخر. تتطلب التكاليف وتاريخ القيمة صلاحية منفصلة.")))
                if (kitchenOnly) Notice(language.text(LocalText(
                    "Choose your station and a task. Review Start or Ready, then separately Stage and Submit the original request. Recover and acknowledge its original result before starting another operation. Station discovery and task access require separate current Master permissions.",
                    "اختر محطتك ومهمة. راجع بدء التحضير أو الجاهزية، ثم احفظ الطلب الأصلي وأرسله في خطوتين منفصلتين. استعد نتيجته الأصلية وأكد استلامها قبل بدء عملية أخرى. يتطلب اكتشاف المحطات والوصول إلى المهام صلاحيات حالية مستقلة من الجهاز الرئيسي.")))
                if (foodChecksOnly) Notice(language.text(LocalText(
                    "Browse your own checks, open an empty unpaid draft and add quoted menu selections to the selected check. Review and save before a separate submission; recover and acknowledge the saved original result. Draft/Add can remain available when kitchen sending is unavailable, subject to current Master permission and source checks. Saving does not reserve or issue stock, start kitchen work or take payment.",
                    "تصفح طلباتك وافتح مسودة فارغة غير مدفوعة وأضف اختيارات القائمة بعرض سعر إلى الطلب المختار. راجع الطلب واحفظه قبل إرساله في خطوة منفصلة، ثم استعد نتيجته الأصلية المحفوظة وأكد استلامها. قد تتاح المسودة والإضافة عند تعذر الإرسال للمطبخ، وفقًا للإذن الحالي وفحص المصادر من الجهاز الرئيسي. لا يحجز الحفظ مخزونًا أو يصرفه ولا يبدأ عمل المطبخ أو يستلم مدفوعات.")))
                if (foodCashFinancialOnly) Notice(language.text(LocalText(
                    "Use an explicitly selected new financial connection to begin a collection, apply an already retained native payment original, or finalize exact paid coverage. Review and save before separate Submit; recover and acknowledge the same original result. Current Master order, stock discovery, payment-original, monetary and receipt permissions remain independent. This does not upgrade a FOOD_CHECKS connection or provide a fresh physical cash instruction.",
                    "استخدم اتصالًا ماليًا جديدًا مختارًا صراحةً لبدء تحصيل أو تطبيق واقعة دفع أصلية محفوظة بالفعل من المصدر الأصلي أو إتمام التغطية المدفوعة الدقيقة. راجع واحفظ قبل الإرسال المنفصل، ثم استعد النتيجة الأصلية نفسها وأكد استلامها. تبقى صلاحيات الطلب واكتشاف المخزون وواقعة الدفع الأصلية والإجراءات المالية والإيصال الحالية من الجهاز الرئيسي مستقلة. لا يرقي هذا اتصال FOOD_CHECKS ولا يوفر تعليمات لتحصيل نقدي فعلي جديد.")))
                if (foodTableAssignmentsOnly) Notice(language.text(LocalText(
                    "Read your own check and its current managed table assignment, then choose an active destination. Review and save the exact assignment before submitting separately. Recover and acknowledge its original result before starting another change. Check reads, directory reads, assignment and recovery each require current Master permission.",
                    "اقرأ طلبك وتعيين طاولته المُدارة الحالي، ثم اختر وجهة مفعّلة. راجع التعيين الدقيق واحفظه قبل الإرسال في خطوة منفصلة. استعد نتيجته الأصلية وأكد استلامها قبل بدء تعديل آخر. تتطلب قراءة الطلب والدليل والتعيين والاسترداد صلاحيات حالية من الجهاز الرئيسي لكل منها.")))
                if (state.attachmentReviewRequired) {
                    Notice(language.text(if (state.workflow == LocalWorkflow.PURCHASING && state.purchasingQuery.section == LocalPurchasingSection.DOCUMENTS) LocalText(
                        "Session renewed. Private unsaved document form and file data were cleared. Durable saved sources remain with the original actor. Reload current access, then explicitly resume the same saved source; no transfer was started automatically.",
                        "جُددت الجلسة ومُسحت بيانات النموذج والملف الخاصة غير المحفوظة. تبقى المصادر المحفوظة بشكل دائم مع الموظف الأصلي. أعد قراءة الصلاحيات الحالية ثم استأنف المصدر المحفوظ نفسه صراحةً؛ لم يبدأ أي نقل تلقائيًا.") else LocalText(
                        "Session renewed. Your draft and original request IDs were kept, but old instructions and read results were retired. Reload current access, review the draft, then explicitly continue the SAME saved request. Nothing was submitted automatically.",
                        "جُددت الجلسة وحُفظت المسودة ومعرفات الطلبات الأصلية، لكن انتهت التعليمات ونتائج القراءة القديمة. أعد قراءة الصلاحيات الحالية وراجع المسودة، ثم تابع الطلب المحفوظ نفسه صراحةً. لم يُرسل أي طلب تلقائيًا.")), caution = true)
                    OutlinedButton(onClick = { controller.reviewAttachment(token) }, enabled = !state.working,
                        modifier = Modifier.touchTarget()) {
                        Text(language.text(LocalText("Reload current access and saved requests", "إعادة قراءة الصلاحيات الحالية والطلبات المحفوظة")))
                    }
                    state.formInput?.let { input ->
                        if (state.readProblem == LocalServiceProblem.INVALID_INPUT) Notice(language.text(LocalText(
                            "The current form or its source rows cannot be safely rebound. Your editable unsaved input and original line IDs were kept separately; old read-only values will not be reused. Reload compatible current data, or explicitly discard this input.",
                            "لا يمكن ربط النموذج الحالي أو أسطر مصادره بأمان. حُفظت المدخلات القابلة للتحرير ومعرفات الأسطر الأصلية منفصلةً؛ ولن تُستخدم القيم القديمة للقراءة فقط. أعد قراءة بيانات حالية متوافقة، أو احذف هذه المدخلات صراحةً.")), caution = true)
                        OutlinedButton(onClick = {
                            if (input.dirty) discardForm = true else controller.discardForm(token)
                        }, enabled = !state.working && state.operationId == null, modifier = Modifier.touchTarget()) {
                            Text(language.text(LocalText("Discard unsaved input", "حذف المدخلات غير المحفوظة")))
                        }
                    }
                }
                if (workflows.size > 1) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(workflows, key = { it.name }) { workflow ->
                        FilterChip(selected = state.workflow == workflow,
                            enabled = !localBusy && state.form == null && !state.formFrozen,
                            onClick = { controller.selectWorkflow(workflow, token) },
                            label = { Text(workflowTitle(workflow)) }, modifier = Modifier.touchTarget())
                    }
                }
            }
            if (area == AppArea.CATALOG && state.reference?.kind !in setOf(
                    LocalRecordKind.RETURN_RIGHTS_CURRENT, LocalRecordKind.RETURN_RIGHTS_PUBLICATION)) item(key = "native-company-catalog-publication") {
                LocalCompanyCatalogPublicationCard(runtime, token, model.companyCatalogDrafts,
                    parentReady = !state.attachmentReviewRequired && !otherBusy && !serialRegistrationBusy && !foodConfigurationBusy && !returnRightsBusy && !pricePolicyBusy && !catalogReleaseBusy && state.form == null && !state.formFrozen,
                    mayStartNew = state.canStart,
                    employeeAccessDenied = ::employeeConfigurationAccessDenied,
                    guardChanged = { draft, working ->
                        companyCatalogDraft = draft
                        companyCatalogWorking = working
                        controller.companyCatalogGuard(draft || model.companyCatalogDrafts.hasDraft, working)
                    },
                    activationStaged = { controller.refreshConfigurationPending(it) })
            }
            if (area == AppArea.CATALOG && state.reference?.kind !in setOf(
                    LocalRecordKind.RETURN_RIGHTS_CURRENT, LocalRecordKind.RETURN_RIGHTS_PUBLICATION)) item(key = "native-company-food-configuration") {
                LocalFoodConfigurationCard(runtime, token, model.foodConfigurationDrafts,
                    parentReady = !state.attachmentReviewRequired && !otherBusy && !serialRegistrationBusy && !companyCatalogBusy && !returnRightsBusy && !pricePolicyBusy && !catalogReleaseBusy && state.form == null && !state.formFrozen,
                    mayStartNew = state.canStart,
                    employeeAccessDenied = ::employeeConfigurationAccessDenied,
                    guardChanged = { draft, working ->
                        foodConfigurationDraft = draft
                        foodConfigurationWorking = working
                        controller.foodConfigurationGuard(draft || model.foodConfigurationDrafts.hasDraft, working)
                    }, activationStaged = { controller.refreshConfigurationPending(it) })
            }
            if (area == AppArea.CATALOG && state.reference?.kind !in setOf(
                    LocalRecordKind.RETURN_RIGHTS_CURRENT, LocalRecordKind.RETURN_RIGHTS_PUBLICATION)) item(key = "native-company-return-rights") {
                LocalReturnRightsPublicationCard(runtime, token, model.returnRightsDrafts,
                    parentReady = !state.attachmentReviewRequired && !otherBusy && !serialRegistrationBusy && !companyCatalogBusy && !foodConfigurationBusy && !pricePolicyBusy && !catalogReleaseBusy && state.form == null && !state.formFrozen,
                    mayStartNew = state.canStart,
                    employeeAccessDenied = ::employeeConfigurationAccessDenied,
                    guardChanged = { draft, working ->
                        returnRightsDraft = draft
                        returnRightsWorking = working
                        controller.returnRightsGuard(draft || model.returnRightsDrafts.hasDraft, working)
                    }, activationStaged = { controller.refreshConfigurationPending(it) })
            }
            if (area == AppArea.CATALOG && state.reference?.kind !in setOf(
                    LocalRecordKind.RETURN_RIGHTS_CURRENT, LocalRecordKind.RETURN_RIGHTS_PUBLICATION)) item(key = "native-company-price-policy") {
                LocalPricePolicyPublicationCard(runtime, token, model.pricePolicyDrafts,
                    parentReady = !state.attachmentReviewRequired && !otherBusy && !serialRegistrationBusy && !companyCatalogBusy && !foodConfigurationBusy && !returnRightsBusy && !catalogReleaseBusy && state.form == null && !state.formFrozen,
                    mayStartNew = state.canStart,
                    employeeAccessDenied = ::employeeConfigurationAccessDenied,
                    guardChanged = { draft, working ->
                        pricePolicyDraft = draft
                        pricePolicyWorking = working
                        controller.pricePolicyGuard(draft || model.pricePolicyDrafts.hasDraft, working)
                    }, receiptStaged = { controller.refreshConfigurationPending(it) })
            }
            if (area == AppArea.CATALOG && state.reference?.kind !in setOf(
                    LocalRecordKind.RETURN_RIGHTS_CURRENT, LocalRecordKind.RETURN_RIGHTS_PUBLICATION)) item(key = "native-company-catalog-release") {
                LocalCatalogReleasePublicationCard(runtime, token, model.catalogReleaseDrafts,
                    parentReady = !state.attachmentReviewRequired && !otherBusy && !serialRegistrationBusy && !companyCatalogBusy && !foodConfigurationBusy && !returnRightsBusy && !pricePolicyBusy && state.form == null && !state.formFrozen,
                    mayStartNew = state.canStart,
                    employeeAccessDenied = ::employeeConfigurationAccessDenied,
                    guardChanged = { draft, working ->
                        catalogReleaseDraft = draft
                        catalogReleaseWorking = working
                        controller.catalogReleaseGuard(draft || model.catalogReleaseDrafts.hasDraft, working)
                    }, activationStaged = { controller.refreshConfigurationPending(it) })
            }
            if (area == AppArea.CATALOG && state.reference?.kind !in setOf(
                    LocalRecordKind.RETURN_RIGHTS_CURRENT, LocalRecordKind.RETURN_RIGHTS_PUBLICATION)) item(key = "native-company-serial-registration") {
                LocalSerialRegistrationPublicationCard(runtime, token, model.serialRegistrationDrafts,
                    parentReady = !state.attachmentReviewRequired && !otherBusy && !companyCatalogBusy && !foodConfigurationBusy && !returnRightsBusy && !pricePolicyBusy && !catalogReleaseBusy && state.form == null && !state.formFrozen,
                    mayStartNew = state.canStart,
                    employeeAccessDenied = ::employeeConfigurationAccessDenied,
                    guardChanged = { draft, working ->
                        serialRegistrationDraft = draft; serialRegistrationWorking = working
                        controller.serialRegistrationGuard(draft || model.serialRegistrationDrafts.hasDraft, working)
                    }, activationStaged = { controller.refreshConfigurationPending(it) })
            }
            if (state.working) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(copy(when (state.busy) {
                        LocalWorkBusy.STAGING -> Copy.STAGING_OPERATION
                        LocalWorkBusy.SUBMITTING -> Copy.SUBMITTING_OPERATION
                        LocalWorkBusy.ACKNOWLEDGING -> Copy.ACKNOWLEDGING_RESULT
                        else -> Copy.LOADING_LOCAL
                    }), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.form == null && !state.workflow.usesFoodQueries) item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.workflow == LocalWorkflow.CASH) LocalCashControls(state.cashQuery, !localBusy && !state.formFrozen,
                        select = { controller.selectCash(it, token) }, destinations = { controller.browseCashSafes(token) })
                    if (state.workflow == LocalWorkflow.RETURNS) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            (if (customerReceiptsOnly) listOf(LocalReturnSection.RECEIPTS) else LocalReturnSection.entries).forEach { section ->
                                FilterChip(selected = state.returnQuery.section == section,
                                    enabled = !localBusy && !state.formFrozen,
                                    onClick = { controller.selectReturns(state.returnQuery.copy(section = section), token) },
                                    label = { Text(language.text(when (section) {
                                        LocalReturnSection.CASES -> LocalText("My remedy claims", "مطالبات المعالجة الخاصة بي")
                                        LocalReturnSection.RECEIPTS -> if (customerReceiptsOnly)
                                            LocalText("Original goods-receipt observations", "ملاحظات استلام البضاعة الأصلية")
                                        else LocalText("Original receipt lookup", "البحث عن الإيصال الأصلي")
                                    })) }, modifier = Modifier.touchTarget())
                            }
                        }
                        Text(language.text(if (customerReceiptsOnly)
                            LocalText("Inspect only original counter goods-receipt observations. Use customer-receipt:SALE_UUID for a known paid sale. A missing observation does not establish non-delivery or reject a claim; claim intake is a separate workflow.",
                                "اعرض فقط ملاحظات استلام البضاعة الأصلية من المنضدة. استخدم customer-receipt:SALE_UUID لبيع مدفوع معلوم. غياب الملاحظة لا يثبت عدم التسليم ولا يرفض المطالبة؛ تسجيل المطالبات مسار عمل منفصل.")
                        else if (state.returnQuery.section == LocalReturnSection.RECEIPTS)
                            LocalText("Enter the original local sale ID. A missing receipt does not erase a claim; record it under My remedy claims.",
                                "أدخل معرف البيع المحلي الأصلي. غياب الإيصال لا يمحو المطالبة؛ سجلها في مطالبات المعالجة الخاصة بي.")
                        else LocalText("Leave search empty for your cases, or enter an exact case ID. Intake is separate from eligibility, stock and payment.",
                            "اترك البحث فارغًا لعرض مطالباتك، أو أدخل معرف المطالبة. التسجيل منفصل عن الأهلية والمخزون والدفع.")),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.workflow == LocalWorkflow.INVENTORY) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(if (stockTransfersOnly) listOf(LocalInventorySection.QUANTITIES, LocalInventorySection.COSTS)
                                else LocalInventorySection.entries, key = { it.name }) { section ->
                                FilterChip(selected = state.inventorySection == section, enabled = !localBusy && !state.formFrozen,
                                    onClick = { controller.selectInventory(section, token) }, label = { Text(language.text(when (section) {
                                        LocalInventorySection.QUANTITIES -> LocalText("Stock quantities", "كميات المخزون")
                                        LocalInventorySection.COSTS -> LocalText("Stock costs — permission required", "تكلفة المخزون — تتطلب صلاحية")
                                        LocalInventorySection.SETUP -> LocalText("Stock setup / profile", "إعداد المخزون / نمط التخزين")
                                        LocalInventorySection.OPENING -> LocalText("Opening inventory", "المخزون الافتتاحي")
                                    })) }, modifier = Modifier.touchTarget())
                            }
                        }
                    }
                    if (state.workflow == LocalWorkflow.PURCHASING) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(when {
                                invoiceOnly -> invoiceUiSections
                                supplierOnly -> listOf(LocalPurchasingSection.SUPPLIERS)
                                else -> LocalPurchasingSection.entries
                            }, key = { it.name }) { section ->
                                FilterChip(selected = state.purchasingQuery.section == section,
                                    enabled = !localBusy && !state.formFrozen,
                                    onClick = { controller.selectPurchasing(state.purchasingQuery.copy(section = section), token) },
                                    label = { Text(language.text(when (section) {
                                        LocalPurchasingSection.SUPPLIERS -> LocalText("Suppliers", "الموردون")
                                        LocalPurchasingSection.RECEIVING_ALLOWANCES -> LocalText("Receiving allowances", "سماح الاستلام")
                                        LocalPurchasingSection.ORDERS -> if (invoiceCaptureOnly)
                                            LocalText("Existing purchase-order context", "سياق أمر شراء موجود")
                                        else if (invoiceMatchingOnly) LocalText("Purchase orders — read only", "أوامر الشراء — للقراءة فقط")
                                        else LocalText("Purchase orders", "أوامر الشراء")
                                        LocalPurchasingSection.INVOICES -> LocalText("Supplier invoices", "فواتير الموردين")
                                        LocalPurchasingSection.PAYABLES -> LocalText("Unsettled payables", "المستحقات غير المسددة")
                                        LocalPurchasingSection.ADVANCES -> LocalText("Paid advances / explicit allocation", "دفعات مقدمة مدفوعة أو تخصيص صريح")
                                        LocalPurchasingSection.OPENING_OBLIGATIONS -> LocalText("Opening supplier debt", "ديون الموردين الافتتاحية")
                                        LocalPurchasingSection.DOCUMENTS -> LocalText("Original supplier documents", "مستندات المورد الأصلية")
                                    })) }, modifier = Modifier.touchTarget())
                            }
                        }
                        if (!supplierOnly) ConfirmRow(language.text(LocalText("Include costs — requires current cost permission",
                            "عرض التكلفة — يتطلب صلاحية تكلفة سارية")), state.purchasingQuery.includeCosts,
                            !localBusy && !state.formFrozen) {
                            controller.selectPurchasing(state.purchasingQuery.copy(includeCosts = it), token)
                        }
                    }
                    OutlinedTextField(value = state.search, onValueChange = { controller.changeSearch(it, token) },
                        enabled = !localBusy && !state.formFrozen && !(state.workflow == LocalWorkflow.INVENTORY &&
                            state.inventorySection == LocalInventorySection.SETUP), singleLine = true,
                        label = { Text(copy(Copy.LOCAL_SEARCH)) }, leadingIcon = { PosIcon(Glyph.SEARCH) },
                        supportingText = if (invoiceMatchingOnly) ({
                            Text(language.text(LocalText(
                                "Select a captured invoice before matching its remaining goods. Purchase orders provide read-only context. Search filters the returned page; Refresh performs a current protected read.",
                                "اختر فاتورة محفوظة قبل مطابقة البضاعة المتبقية بها. توفر أوامر الشراء سياقًا للقراءة فقط. يصفي البحث الصفحة المعروضة؛ ويجري التحديث قراءة حالية محمية.")))
                        }) else if (invoiceCaptureOnly) ({
                            Text(language.text(LocalText(
                                "Browse invoices to capture without a selected PO, or select an existing purchase order as original context. Search filters the returned page; Refresh performs a current protected read.",
                                "تصفح الفواتير للحفظ دون أمر شراء مختار، أو اختر أمر شراء موجودًا كسياق أصلي. يصفي البحث الصفحة المعروضة؛ ويجري التحديث قراءة حالية محمية.")))
                        }) else if (customerReceiptsOnly) ({
                            Text(language.text(LocalText(
                                "Enter customer-receipt:SALE_UUID or customer-receipt-event:SALE_UUID:ORIGINAL_OPERATION_UUID, then Refresh. Returns is read-only evidence, not qualification or refund.",
                                "أدخل customer-receipt:SALE_UUID أو customer-receipt-event:SALE_UUID:ORIGINAL_OPERATION_UUID ثم حدّث. تعرض المرتجعات دليلًا للقراءة فقط، دون تأهيل أو رد أموال.")))
                        }) else if (paymentCorrectionsOnly) ({
                            Text(language.text(LocalText(
                                "Enter payment-correction:SALE_UUID, then choose Refresh.",
                                "أدخل payment-correction:SALE_UUID ثم اختر تحديث.")))
                        }) else if (state.workflow in setOf(LocalWorkflow.ORDERS, LocalWorkflow.CHECKOUT, LocalWorkflow.RETURNS)) ({
                            Column {
                                if (state.workflow == LocalWorkflow.ORDERS) Text(language.text(LocalText(
                                    "order:ID · handoff:ID · manager:ID (read-only) · reassign:ID (explicit manager action)",
                                    "order:ID · handoff:ID · manager:ID (قراءة فقط) · reassign:ID (إجراء صريح للمدير)")))
                                if (state.workflow != LocalWorkflow.RETURNS &&
                                    runtimePresentation.display?.workspaceMode == LocalSessionWorkspaceMode.POLICY_FILTERED)
                                    Text(language.text(LocalText("payment-correction:SALE (protected historical classification; no money movement)",
                                        "payment-correction:SALE (تصنيف تاريخي محمي؛ دون حركة أموال)")))
                                Text(language.text(LocalText(
                                    "customer-receipt:SALE · customer-receipt-event:SALE:ORIGINAL-OPERATION (Returns: read-only evidence)",
                                    "customer-receipt:SALE · customer-receipt-event:SALE:ORIGINAL-OPERATION (المرتجعات: دليل للقراءة فقط)")))
                            }
                        }) else if (stockTransfersOnly) ({
                            Text(language.text(LocalText(
                                "sku:ID · transfer:ID · history on a selected position (cost permission required)",
                                "sku:ID · transfer:ID · history في موضع مختار (يتطلب صلاحية التكلفة)")))
                        }) else if (state.workflow == LocalWorkflow.RECEIVING) ({
                            Text(language.text(LocalText(
                                "arrivals2 · arrival2:ID · arrival2-new:SUPPLIER/WAREHOUSE[/SKU-or--/DOCUMENT[,DOCUMENT]] · select a v2 warehouse/partition before arrival2-documents:DOCUMENT[,DOCUMENT], then Refresh · arrival2-serial:ID",
                                "arrivals2 · arrival2:ID · arrival2-new:SUPPLIER/WAREHOUSE[/SKU-or--/DOCUMENT[,DOCUMENT]] · اختر مخزنًا أو جزءًا v2 قبل arrival2-documents:DOCUMENT[,DOCUMENT] ثم حدّث · arrival2-serial:ID")))
                        }) else null,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { controller.refresh(token) }),
                        modifier = Modifier.fillMaxWidth().focusRequester(searchFocus).onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                                controller.refresh(token)
                                true
                            } else false
                        })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { controller.refresh(token) }, enabled = !localBusy && !state.formFrozen,
                            modifier = Modifier.touchTarget()) { Text(copy(Copy.REFRESH)) }
                        if (state.reference != null) OutlinedButton(onClick = { controller.selectRecord(null, token) },
                            enabled = !localBusy && !state.formFrozen, modifier = Modifier.touchTarget()) {
                            Text(if (paymentCorrectionsOnly) language.text(LocalText("Clear payment selection", "مسح اختيار الدفع"))
                                else if (state.managerOrderContext) language.text(LocalText("Clear selected target", "مسح الهدف المحدد")) else copy(Copy.ALL_RECORDS))
                        }
                    }
                }
            }
            state.readProblem?.let { problem -> item {
                val foodReason = state.foodUnavailable
                Notice(if (state.workflow.usesFoodQueries && foodReason != null) language.text(foodUnavailableText(foodReason))
                    else localProblem(problem), caution = true)
            } }
            state.page?.notice?.let { notice -> item { Notice(language.text(notice)) } }
            if (!invoiceOnly && !paymentCorrectionsOnly && !foodChecksOnly && !foodCashFinancialOnly && !foodTableAssignmentsOnly && !state.managerOrderContext && state.workflow in retailWorkflows) item {
                LocalRetailPhysicalScreen(controller.retailPhysical, state, token) { controller.openRetailCollection(token) }
            }
            if (!invoiceOnly && !supplierOnly && state.workflow == LocalWorkflow.PURCHASING) item(key = "supplier-cash-payout-recovery") {
                LocalSupplierCashPanel(controller.supplierPhysical, state, token)
            }
            if (!invoiceOnly && !supplierOnly && state.workflow == LocalWorkflow.PURCHASING && state.purchasingQuery.section == LocalPurchasingSection.DOCUMENTS) item(key = "purchasing-document-intake") {
                LocalPurchasingDocumentCard(controller, state, token)
            }
            if (state.form == null && !state.formFrozen) state.priceDraft?.takeIf { it.controls == state.page?.priceControls }?.let { draft -> item {
                PricePreviewControls(draft, enabled = !localBusy,
                    changeQuantity = { controller.changePriceQuantity(it, token) },
                    changeChannel = { controller.changePriceChannel(it, token) }, preview = { controller.previewPrice(token) })
            } }
            if (!state.managerOrderReview) item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeading(copy(Copy.PENDING_OPERATIONS))
                    if (state.recoveryRequired) Notice(copy(Copy.RECOVERY_BLOCKS_NEW), caution = true)
                    state.pendingProblem?.let { Notice(localProblem(it), caution = true) }
                    if (invoiceOnly && state.pendingProblem != null) Notice(language.text(LocalText(
                        "Saved-history availability is unresolved. Other original work must be recovered through its compatible workflow; this profile does not discard it or report an empty successful scan.",
                        "توفر السجل المحفوظ غير محسوم. يجب استرداد العمل الأصلي الآخر عبر مساره المتوافق؛ ولا يتجاهله هذا النمط أو يعرض فحصًا ناجحًا فارغًا.")), caution = true)
                    if (state.pendingLoaded && state.pending.isEmpty() && state.pendingCursor == null && state.pendingPageIsFirst)
                        Text(copy(Copy.NO_PENDING), style = MaterialTheme.typography.bodySmall, color = Muted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { controller.refreshPending(token) },
                            enabled = !localBusy && (state.form == null || state.formFrozen), modifier = Modifier.touchTarget()) {
                            Text(copy(if (state.pendingPageIsFirst) Copy.PENDING_REFRESH else Copy.PENDING_FIRST))
                        }
                        if (state.pendingCursor != null) OutlinedButton(onClick = { controller.refreshPending(token, nextPage = true) },
                            enabled = !localBusy && (state.form == null || state.formFrozen), modifier = Modifier.touchTarget()) {
                            Text(copy(Copy.PENDING_NEXT))
                        }
                    }
                    OutlinedTextField(value = state.originalLookupInput,
                        onValueChange = { controller.changeOriginalOperationId(it, token) },
                        label = { Text(language.text(LocalText("Original operation ID", "معرّف العملية الأصلية"))) },
                        enabled = !localBusy, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = { controller.lookupOriginal(token) },
                        enabled = !localBusy && state.canLookupOriginal, modifier = Modifier.touchTarget()) {
                        Text(language.text(LocalText("Look up original result", "استرجاع النتيجة الأصلية")))
                    }
                    state.originalLookupProblem?.let { Notice(localProblem(it), caution = true) }
                }
            }
            items(state.pending.filterNot { state.managerOrderReview || it.operationId == state.operationId }, key = { "pending-${it.operationId.value}" }) { operation ->
                SavedOperationCard(operation, canWork = !localBusy && state.form == null && !state.formFrozen,
                    canReview = operation.submitAllowed && operation.summary.isNotEmpty(),
                    lookup = { controller.lookup(operation.operationId, token) }, submit = { review = operation },
                    approve = { controller.reviewActionApproval(operation.operationId, token) })
            }
            state.operation?.takeUnless { state.managerOrderReview }?.let { result -> item {
                when (result) {
                    is LocalOperationResult.Pending -> SavedOperationCard(result.operation, canWork = !localBusy,
                        canReview = result.operation.submitAllowed && (result.operation.summary.isNotEmpty() || state.form != null),
                        lookup = { controller.lookup(result.operation.operationId, token) }, submit = { review = result.operation },
                        approve = { controller.reviewActionApproval(result.operation.operationId, token) })
                    is LocalOperationResult.Unresolved -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val read = state.operationRead?.takeIf { state.operationId == result.operationId }
                        Notice(if (read?.recorded == true) language.text(LocalText(
                            "The original outcome was recorded earlier. Read its details with current access. An unavailable read does not undo that outcome or repeat the operation.",
                            "سُجلت النتيجة الأصلية سابقًا. اقرأ تفاصيلها بالصلاحيات الحالية. تعذر القراءة لا يلغي تلك النتيجة ولا يكرر العملية."))
                        else copy(Copy.OPERATION_UNKNOWN), caution = true,
                            modifier = Modifier.semantics { liveRegion = if (read?.recorded == true) LiveRegionMode.Polite else LiveRegionMode.Assertive })
                        IdentifierText(copy(Copy.OPERATION_ID), result.operationId.value)
                        if (read != null && !read.recorded) Notice(language.text(LocalText(
                            "Read the original request with current access before continuing. Earlier details were cleared; no payment, refund or acknowledgement was repeated.",
                            "اقرأ الطلب الأصلي بالصلاحيات الحالية قبل المتابعة. مُسحت التفاصيل السابقة؛ ولم يتكرر دفع أو رد مبلغ أو إقرار.")), caution = true)
                        if (read == null) Notice(localProblem(result.problem), caution = true)
                        else read.problem?.let { Notice(localProblem(it), caution = true) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { controller.lookup(result.operationId, token) }, enabled = !localBusy,
                                modifier = Modifier.touchTarget()) { Text(copy(Copy.CHECK_OUTCOME)) }
                            if (state.stageUncertain && read == null) OutlinedButton(onClick = { controller.retryStage(token) }, enabled = !localBusy,
                                modifier = Modifier.touchTarget()) { Text(copy(Copy.SAME_ID_STAGE)) }
                        }
                    }
                    is LocalOperationResult.Recorded -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatusPill(recordedStatus(result.decision),
                            caution = result.decision == LocalRecordedDecision.REJECTED)
                        Text(language.text(result.message), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        IdentifierText(copy(Copy.OPERATION_ID), result.operationId.value)
                        if (result.replayed) Text(copy(Copy.RECORDED_REPLAY), style = MaterialTheme.typography.bodySmall)
                        result.result?.let { RecordContents(it) }
                        result.food?.let { FoodRecordedDetails(it) }
                        Text(copy(Copy.DURABLE_NOTE), style = MaterialTheme.typography.bodySmall, color = Muted)
                        if (state.recordedAcknowledged) Text(copy(Copy.RESULT_ACKNOWLEDGED), style = MaterialTheme.typography.bodySmall)
                        else {
                            Notice(copy(Copy.RESULT_AWAITING_ACK))
                            state.acknowledgementProblem?.let {
                                Notice(copy(Copy.RESULT_ACK_UNKNOWN), caution = true)
                                Text(localProblem(it), style = MaterialTheme.typography.bodySmall)
                            }
                            Button(onClick = { controller.acknowledgeRecorded(result.operationId, token) },
                                enabled = !localBusy, modifier = Modifier.touchTarget()) { Text(copy(Copy.ACKNOWLEDGE_RESULT)) }
                        }
                    }
                }
            } }
            if (state.workflow.usesFoodQueries) localFoodContent(state, controller, token)
            val form = state.form
            if (form != null) {
                val foodForm = form.descriptor.action.workflow == LocalWorkflow.FOOD
                val scalars = form.foodDraftFields()
                val group = form.descriptor.fields.filterIsInstance<LocalFormField.Lines>().singleOrNull()
                val openingSource = form.descriptor.openingAllocations
                val openingCounts = state.openingCounts
                item {
                    SectionHeading(language.text(form.descriptor.title))
                    Notice(copy(when {
                        !state.formFrozen -> Copy.FORM_EDIT_HELP
                        state.busy == LocalWorkBusy.STAGING -> Copy.STAGING_OPERATION
                        state.operation is LocalOperationResult.Pending -> Copy.SAVED_NOT_SUBMITTED
                        else -> Copy.OPERATION_UNKNOWN
                    }))
                    form.descriptor.explanation?.let { Text(language.text(it), Modifier.padding(top = 8.dp)) }
                }
                if (foodForm) item { FoodDraftDetails(scalars, form.values) }
                items(scalars.filterNot { foodForm && it.readOnly }, key = { "field-${it.field.name}" }) { field ->
                    ScalarEditor(field, form.values[field.field] ?: LocalDraftValue(), !localBusy && !state.formFrozen,
                        isError = state.issue?.field == field.field && state.issue?.lineId == null,
                        chooseReference = { controller.openReferencePicker(field.field, token) },
                        change = { controller.edit(field.field, it, token) })
                }
                if (openingSource != null) {
                    item {
                        SectionHeading(language.text(LocalText("Count every allocation", "عدّ كل تخصيص")))
                        Text(language.text(LocalText("Unsaved counts stay only in this screen; leaving or locking clears them. Saved requests keep their original identity for recovery.",
                            "تبقى الكميات غير المحفوظة في هذه الشاشة فقط؛ تُمسح عند مغادرتها أو قفلها. تحتفظ الطلبات المحفوظة بهويتها الأصلية لاسترجاع نتيجتها.")), style = MaterialTheme.typography.bodySmall)
                        state.openingCountProgress?.let { progress ->
                            Text(language.text(LocalText(
                                "Counts reviewed: ${progress.totalAllocations - progress.unfinishedAllocations}/${progress.totalAllocations}; actual ZERO: ${progress.zeroAllocations}; positive physical slices: ${progress.positiveSlices}.",
                                "التخصيصات المعدودة المراجعة: ${progress.totalAllocations - progress.unfinishedAllocations}/${progress.totalAllocations}؛ الصفر الفعلي: ${progress.zeroAllocations}؛ الأجزاء الفعلية الموجبة: ${progress.positiveSlices}.")))
                            if (progress.positiveSlices > LocalOpeningAllocationPage.MAX_POSITIVE_LINES) Notice(language.text(LocalText(
                                "This count has more than 16 positive physical slices. Posting is unavailable and your draft is kept. Do not hide positive stock or use another receipt to complete this first entry. Larger receiving is still required.",
                                "يحتوي هذا العد على أكثر من 16 جزءًا فعليًا موجبًا. الترحيل غير متاح وتبقى مسودتك محفوظة هنا. لا تخف مخزونًا موجبًا ولا تستخدم إيصالًا آخر لإكمال هذا القيد الأول. يبقى الاستلام الأكبر مطلوبًا.")), caution = true)
                            else if (progress.unfinishedAllocations > 0) Text(language.text(LocalText(
                                "${progress.unfinishedAllocations} allocations still need all actual counts or an explicit ZERO count. A blank count or removed slice is not zero.",
                                "لا يزال ${progress.unfinishedAllocations} تخصيصًا يحتاج جميع كمياته الفعلية أو عدًا صفريًا صريحًا. العد الفارغ أو الجزء المحذوف ليس صفرًا.")))
                            else if (progress.positiveSlices == 0) Notice(language.text(LocalText(
                                "Every allocation was counted ZERO. There is no positive stock to post; no stock item is initialized. Your count review remains here.",
                                "عُدّت كل التخصيصات صفرًا. لا يوجد مخزون موجب لترحيله؛ لا تتم تهيئة سجل مخزون. تبقى مراجعة عدك هنا.")), caution = true)
                        }
                        openingCounts?.sourceProblem?.let { problem ->
                            Notice(localProblem(problem), caution = true)
                            Text(language.text(LocalText("Your counts are kept. Read the allocation page again before saving; unavailable information is not zero stock.",
                                "تبقى كمياتك المعدودة هنا. أعد قراءة صفحة التخصيص قبل الحفظ؛ المعلومات غير المتاحة ليست مخزونًا صفريًا.")))
                        }
                        Text(language.text(LocalText(
                            "Approved quantities and values below are ceilings, not actual counts. Count each allocation even if you need several pages. Later opening and bulk/resumable import remain unfinished.",
                            "الكميات والقيم المعتمدة أدناه حدود وليست عدًا فعليًا. عدّ كل تخصيص ولو احتجت عدة صفحات. يبقى الافتتاحي اللاحق والاستيراد الكبير القابل للاستئناف غير مكتملين.")))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { controller.readOpeningAllocations(token) }, enabled = !localBusy && !state.formFrozen,
                                modifier = Modifier.touchTarget()) { Text(language.text(LocalText("First allocation page / retry", "أول صفحة تخصيصات / إعادة القراءة"))) }
                            OutlinedButton(onClick = { controller.readOpeningAllocations(token, nextPage = true) },
                                enabled = !localBusy && !state.formFrozen && openingSource.nextCursor != null,
                                modifier = Modifier.touchTarget()) { Text(language.text(LocalText("Next allocations", "التخصيصات التالية"))) }
                        }
                    }
                    items(openingSource.rows, key = { "opening-allocation-${it.allocationId.value}" }) { row ->
                        val hasSlices = form.lines.any { it.values[LocalField.ALLOCATION_ID]?.text == row.allocationId.value }
                        val zero = openingCounts?.zeroAllocations?.contains(row.allocationId) == true
                        val editable = !localBusy && !state.formFrozen && openingCounts?.sourceProblem == null
                        Surface(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Outline)) {
                            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                RecordContents(row.record)
                                Text(language.text(when {
                                    zero -> LocalText("Actual count: ZERO", "العد الفعلي: صفر")
                                    hasSlices -> LocalText("Count entry started. Complete every actual physical slice and owned value below.", "بدأ إدخال العد. أكمل كل جزء فعلي وقيمته المملوكة أدناه.")
                                    else -> LocalText("Actual count not entered yet", "لم يُدخل العد الفعلي بعد")
                                }))
                                OutlinedButton(onClick = { controller.countOpeningAllocation(row.allocationId, token) },
                                    enabled = editable && !hasSlices && group != null && form.lines.size < group.maximumLines,
                                    modifier = Modifier.touchTarget()) { Text(language.text(LocalText("Count stock here", "عدّ المخزون هنا"))) }
                                ConfirmRow(language.text(LocalText("I physically counted ZERO stock for this allocation", "عددت فعليًا مخزون هذا التخصيص وكان صفرًا")),
                                    zero, editable && !hasSlices) { controller.confirmOpeningZero(row.allocationId, it, token) }
                                if (hasSlices) Text(language.text(LocalText(
                                    "Remove all its physical slices first if correcting this allocation to ZERO, then explicitly confirm the zero count. Removing them alone does not record zero.",
                                    "إذا صححت هذا التخصيص إلى صفر، احذف جميع أجزائه أولًا ثم أكد العد الصفري صراحةً. حذفها وحده لا يسجل صفرًا.")), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (group != null) {
                    item { Text(language.text(group.label), style = MaterialTheme.typography.titleMedium) }
                    val lineOffset = if (openingSource != null) openingCounts?.lineOffset ?: 0 else 0
                    val visibleLines = if (openingSource != null) form.lines.drop(lineOffset).take(LocalOpeningAllocationPage.PAGE_SIZE) else form.lines
                    items(visibleLines, key = { "line-${it.lineId.value}" }) { line ->
                        Surface(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Outline)) {
                            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                val number = language.digits((form.lines.indexOf(line) + 1).toString())
                                Text(copy(Copy.LINE_NUMBER, number), style = MaterialTheme.typography.titleMedium)
                                if (foodForm) {
                                    FoodDraftSelectionHeader(line)
                                    FoodDraftDetails(group.columns, line.values, line.lineId)
                                }
                                group.columns.filterNot { foodForm && it.readOnly }.forEach { field -> key(field.field) {
                                    ScalarEditor(field, line.values[field.field] ?: LocalDraftValue(), !localBusy && !state.formFrozen,
                                        isError = state.issue?.field == field.field && state.issue?.lineId == line.lineId,
                                        chooseReference = { controller.openReferencePicker(field.field, token, line.lineId) },
                                        change = { controller.edit(field.field, it, token, line.lineId) })
                                } }
                                OutlinedButton(onClick = { controller.removeLine(line.lineId, token) },
                                    enabled = !localBusy && !state.formFrozen && form.lines.size > group.minimumLines,
                                    modifier = Modifier.touchTarget()) { Text((if (openingSource != null) language.text(LocalText(
                                        "Remove physical slice", "حذف الجزء الفعلي")) else copy(Copy.REMOVE_LINE)) + " · " + number) }
                                if (group.allowDuplicateLines) OutlinedButton(onClick = { controller.duplicateLine(line.lineId, token) },
                                    enabled = !localBusy && !state.formFrozen && form.lines.size < group.maximumLines,
                                    modifier = Modifier.touchTarget()) { Text((if (openingSource != null) language.text(LocalText(
                                        "Add another physical slice", "إضافة جزء فعلي آخر")) else copy(Copy.DUPLICATE_LINE)) + " · " + number) }
                            }
                        }
                    }
                    if (openingSource != null && form.lines.isNotEmpty()) item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { controller.pageOpeningSlices(token, nextPage = false) },
                                enabled = !localBusy && !state.formFrozen && lineOffset != 0,
                                modifier = Modifier.touchTarget()) { Text(language.text(LocalText("First counted slices", "أول الأجزاء المعدودة"))) }
                            OutlinedButton(onClick = { controller.pageOpeningSlices(token, nextPage = true) },
                                enabled = !localBusy && !state.formFrozen && lineOffset + visibleLines.size < form.lines.size,
                                modifier = Modifier.touchTarget()) { Text(language.text(LocalText("Next counted slices", "الأجزاء المعدودة التالية"))) }
                        }
                        if (form.lines.size >= group.maximumLines) Notice(language.text(LocalText(
                            "This draft has reached its entry limit; existing counts are kept. Do not leave positive stock out to fit the form. Larger receiving remains required.",
                            "بلغت المسودة حد إدخالها؛ تبقى الكميات الحالية هنا. لا تستبعد مخزونًا موجبًا ليتسع النموذج. يبقى الاستلام الأكبر مطلوبًا.")), caution = true)
                    }
                    if (group.allowBlankLines) item { OutlinedButton(onClick = { controller.addLine(token) },
                        enabled = !localBusy && !state.formFrozen && form.lines.size < group.maximumLines,
                        modifier = Modifier.touchTarget()) { Text(copy(Copy.ADD_LINE)) } }
                }
                if (form.descriptor.action in foodSelectionActions && !state.formFrozen) item("food-price-preview") {
                    val displayCurrent = controller.foodPreviewDisplayCurrent(token)
                    LocalFoodPricePreviewPanel(state.foodPricePreview.takeIf { displayCurrent },
                        state.foodPricePreviewProblem.takeIf { displayCurrent },
                        enabled = displayCurrent && !localBusy && !state.recoveryRequired) { controller.previewFood(token) }
                }
                form.descriptor.confirmation?.let { confirmation -> item {
                    ConfirmRow(language.text(confirmation), form.confirmed,
                        !localBusy && !state.formFrozen && (openingSource == null || state.openingCanSave)) { controller.confirmForm(it, token) }
                } }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.issue?.let { issue -> Notice(copy(when {
                            issue.tooLarge -> Copy.INPUT_TOO_LARGE
                            issue.field == LocalField.CONFIRMED -> Copy.CONFIRMATION_REQUIRED
                            else -> Copy.INPUT_INVALID
                        }), caution = true, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) }
                        state.stageProblem?.let { Notice(localProblem(it), caution = true) }
                        if (!state.formFrozen) FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { controller.stage(token) },
                                enabled = !localBusy && !state.recoveryRequired && (openingSource == null || state.openingCanSave),
                                modifier = Modifier.touchTarget()) { Text(copy(Copy.STAGE_OPERATION)) }
                            OutlinedButton(onClick = {
                                if (form.dirty) discardForm = true else controller.discardForm(token)
                            }, enabled = !localBusy, modifier = Modifier.touchTarget()) { Text(copy(Copy.CLOSE)) }
                        }
                    }
                }
            } else {
                state.page?.let { page ->
                    val availableForms = page.forms.filterNot { it.action in foodSelectionActions }
                        .filter { form -> invoiceAction?.let { form.isInvoiceUiForm(it, state.reference) } != false }
                    page.returnRights?.let { rights ->
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                SectionHeading(language.text(LocalText(
                                    if (rights.historical) "Historical return-rights policy" else "Current return-rights policy",
                                    if (rights.historical) "سياسة حقوق مرتجعات تاريخية" else "سياسة حقوق المرتجعات الحالية")))
                                RecordValues(rights.attribution)
                                Text(language.text(rights.continuingRights))
                            }
                        }
                        items(rights.rules, key = { "rights-${it.categoryCode}-${it.reasonCode}" }) { rule ->
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                RecordValues(rule.values)
                            }
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SectionHeading(copy(Copy.LOCAL_ACTIONS))
                            if (availableForms.isEmpty() && page.forms.isEmpty()) Text(copy(Copy.NO_ACTIONS), color = Muted)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                availableForms.forEach { form -> Button(onClick = { controller.openForm(form.action, token) },
                                    enabled = state.canStart, modifier = Modifier.touchTarget()) { Text(language.text(form.title)) } }
                            }
                        }
                    }
                    if (!page.workflow.usesFoodQueries) {
                        if (page.records.isEmpty()) item { Text(if (paymentCorrectionsOnly) language.text(LocalText(
                            "Enter payment-correction:SALE_UUID and choose Refresh to read the original payment and linked history.",
                            "أدخل payment-correction:SALE_UUID واختر تحديث لقراءة الدفع الأصلي والسجل المرتبط.")) else copy(Copy.NO_RECORDS), color = Muted) }
                        items(page.records, key = { "record-${it.reference.kind}-${it.reference.id.value}" }) { record ->
                            Surface(onClick = { controller.selectRecord(record.reference, token) },
                                enabled = record.selectable && !localBusy && !state.formFrozen, modifier = Modifier.fillMaxWidth().visibleKeyboardFocus(),
                                shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Outline)) {
                                Column(Modifier.padding(18.dp)) { RecordContents(record) }
                            }
                        }
                        if (state.managerOrderReview) page.managerQuoteDetails?.let { details ->
                            details.originalQuote?.let { original ->
                                item(key = "manager-original-quote") {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        SectionHeading(language.text(LocalText("Original pricing snapshot", "لقطة التسعير الأصلية")))
                                        Notice(language.text(LocalText(
                                            "Exact amounts are stored ratios, not newly rounded prices. Discount allocations explain already-discounted postings; do not deduct them again. Original human-readable item selections, previous collectors, cash custody and unresolved-payment facts are not supplied. This is not reassignment eligibility or fiscal certification.",
                                            "المبالغ الدقيقة نسب محفوظة وليست أسعارًا مقربة من جديد. توزيعات الخصم تفسر مبالغ مرحّلة بعد الخصم؛ لا تخصمها مرة أخرى. لا تتوفر أوصاف اختيار الأصناف الأصلية أو المحصلون السابقون أو عهدة النقد أو حقائق الدفع غير المحسوم. ليست هذه أهلية لنقل المسؤولية أو اعتمادًا ضريبيًا.")))
                                        RecordValues(original.values)
                                    }
                                }
                                items(original.sections, key = { "manager-original-${it.key}" }) { section ->
                                    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                                        border = BorderStroke(1.dp, Outline)) {
                                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            SectionHeading(language.text(section.title))
                                            RecordValues(section.values)
                                        }
                                    }
                                }
                            }
                            item(key = "manager-quote-components") {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    SectionHeading(language.text(LocalText("Recorded pricing components", "مكوّنات التسعير المسجلة")))
                                    Notice(language.text(if (details.components.isEmpty()) LocalText(
                                        "Component allocations were not supplied. Do not infer a zero net amount or tax, or a complete original quote.",
                                        "لم تُزوَّد توزيعات المكوّنات. لا تستنتج أن الصافي أو الضريبة صفر، أو أن عرض السعر الأصلي كامل.")
                                    else if (details.originalQuote != null) LocalText(
                                        "Original posted component amounts after discounts, with their stored tax policy and exact allocation. No fresh repricing or additional deduction is performed.",
                                        "مبالغ المكوّنات الأصلية المرحّلة بعد الخصم، مع سياسة الضريبة والتوزيع الدقيق المحفوظين. لا إعادة تسعير جديدة ولا خصم إضافي.")
                                    else LocalText(
                                        "These are the original posted component amounts after discounts. Do not subtract the advertised discount again. Original names, unit prices and merchandise-net discount allocations are not supplied.",
                                        "هذه مبالغ المكوّنات الأصلية المرحّلة بعد الخصم. لا تخصم الخصم المعلن مرة أخرى. لا تتوفر الأسماء الأصلية أو أسعار الوحدات أو توزيعات الخصم على صافي الأصناف.")))
                                }
                            }
                            items(details.components, key = { "manager-component-${it.key}" }) { component ->
                                // No clickable Surface, selectable target, form or operation is attached.
                                Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                                    border = BorderStroke(1.dp, Outline)) {
                                    Column(Modifier.padding(18.dp)) { RecordValues(component.values) }
                                }
                            }
                        }
                        if (page.nextCursor != null) item { OutlinedButton(onClick = { controller.refresh(token, nextPage = true) },
                            enabled = !localBusy && !state.formFrozen, modifier = Modifier.touchTarget()) { Text(copy(Copy.NEXT_PAGE)) } }
                    }
                }
            }
        }
    }
    state.actionApproval?.let { presentation -> LocalActionApprovalDialog(presentation, state.working, controller, token) }
    state.referencePicker?.let { picker -> LocalReferencePickerDialog(picker, state.working, controller, token) }
    if (discardForm) AlertDialog(onDismissRequest = { discardForm = false },
        title = { Text(copy(Copy.DISCARD_TITLE)) }, text = { Text(copy(Copy.DISCARD_BODY)) },
        confirmButton = { TextButton(onClick = { discardForm = false; controller.discardForm(token) }, modifier = Modifier.touchTarget()) { Text(copy(Copy.DISCARD_LEAVE)) } },
        dismissButton = { TextButton(onClick = { discardForm = false }, modifier = Modifier.touchTarget()) { Text(copy(Copy.KEEP_WORKING)) } })
    review?.let { operation -> AlertDialog(onDismissRequest = { review = null },
        title = { Text(copy(Copy.REVIEW_SAVED)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(language.text(operation.label), style = MaterialTheme.typography.titleMedium)
            IdentifierText(copy(Copy.OPERATION_ID), operation.operationId.value)
            RecordValues(operation.summary)
            Text(copy(Copy.NO_RECOLLECT))
        } },
        confirmButton = { TextButton(onClick = { review = null; controller.submit(operation.operationId, token) },
            enabled = !localBusy, modifier = Modifier.touchTarget()) { Text(copy(Copy.CONFIRM_SUBMIT)) } },
        dismissButton = { TextButton(onClick = { review = null }, modifier = Modifier.touchTarget()) { Text(copy(Copy.CLOSE)) } }) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PricePreviewControls(draft: LocalPricePreviewDraft, enabled: Boolean, changeQuantity: (String) -> Unit,
                                 changeChannel: (String) -> Unit, preview: () -> Unit) {
    val language = LocalAppLanguage.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeading(language.text(LocalText("Read-only price preview", "معاينة السعر للقراءة فقط")))
        Text(language.text(LocalText(
            "Choose a channel and exact quantity, then Preview. Editing these controls does not change the last displayed result. No quote or stock is locked.",
            "اختر القناة والكمية الدقيقة ثم اضغط معاينة. تعديل هذه المدخلات لا يغير آخر نتيجة معروضة. لا يُثبت عرض سعر أو مخزون.")),
            style = MaterialTheme.typography.bodySmall)
        IdentifierText(language.text(LocalText("SKU", "الصنف")), draft.controls.skuId.value)
        IdentifierText(language.text(LocalText("Base unit · quantity scale", "الوحدة الأساسية · دقة الكمية")),
            "${draft.controls.unitId.value} · ${draft.controls.quantityScale}")
        OutlinedTextField(value = draft.quantity, onValueChange = { if (it.length <= 32) changeQuantity(it) },
            enabled = enabled, singleLine = true, isError = draft.issue == LocalField.QUANTITY,
            label = { Text(language.text(LocalText("Exact quantity", "الكمية الدقيقة"))) },
            supportingText = { if (draft.issue == LocalField.QUANTITY) Text(copy(Copy.INPUT_INVALID)) },
            textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { preview() }), modifier = Modifier.fillMaxWidth())
        Text(language.text(LocalText("Channel", "القناة")), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("COUNTER" to LocalText("Counter", "الكاونتر"), "DINE_IN" to LocalText("Dine in", "داخل المكان"),
                "TAKEAWAY" to LocalText("Takeaway", "سفري"), "DELIVERY" to LocalText("Delivery", "توصيل")).forEach { (code, label) ->
                FilterChip(selected = draft.channel == code, enabled = enabled, onClick = { changeChannel(code) },
                    label = { Text(language.text(label)) }, modifier = Modifier.touchTarget())
            }
        }
        if (draft.issue == LocalField.CHANNEL) Text(copy(Copy.INPUT_INVALID), color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Button(onClick = preview, enabled = enabled, modifier = Modifier.touchTarget()) {
            Text(language.text(LocalText("Preview current price", "معاينة السعر الحالي")))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SavedOperationCard(operation: LocalPendingOperation, canWork: Boolean, canReview: Boolean, lookup: () -> Unit, submit: () -> Unit, approve: () -> Unit) {
    val language = LocalAppLanguage.current
    Surface(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Outline)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(language.text(operation.label), style = MaterialTheme.typography.titleMedium)
            IdentifierText(copy(Copy.OPERATION_ID), operation.operationId.value)
            Notice(copy(when (operation.phase) {
                LocalPendingPhase.STAGED -> Copy.SAVED_NOT_SUBMITTED
                LocalPendingPhase.DISPATCH_UNRESOLVED -> Copy.OPERATION_UNKNOWN
                LocalPendingPhase.RECORDED_UNACKNOWLEDGED -> Copy.RESULT_AWAITING_ACK
            }), caution = operation.phase == LocalPendingPhase.DISPATCH_UNRESOLVED)
            operation.recordedDecision?.let { decision ->
                StatusPill(recordedStatus(decision),
                    caution = decision == LocalRecordedDecision.REJECTED)
            }
            RecordValues(operation.listSummary())
            if (!canReview && operation.phase != LocalPendingPhase.RECORDED_UNACKNOWLEDGED)
                Text(copy(Copy.SAVED_REVIEW_UNAVAILABLE), style = MaterialTheme.typography.bodySmall, color = Muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = lookup, enabled = canWork, modifier = Modifier.touchTarget()) { Text(copy(Copy.CHECK_OUTCOME)) }
                if (operation.actionApproval != null && operation.phase != LocalPendingPhase.RECORDED_UNACKNOWLEDGED)
                    OutlinedButton(onClick = approve, enabled = canWork && operation.summary.isNotEmpty(), modifier = Modifier.touchTarget()) {
                        Text(language.text(LocalText("Review original approval", "مراجعة الموافقة الأصلية")))
                    }
                if (operation.phase != LocalPendingPhase.RECORDED_UNACKNOWLEDGED)
                    Button(onClick = submit, enabled = canWork && canReview, modifier = Modifier.touchTarget()) { Text(copy(Copy.SAVED_SUBMIT)) }
            }
        }
    }
}

@Composable
private fun recordedStatus(decision: LocalRecordedDecision): String = when (decision) {
    LocalRecordedDecision.APPLIED -> copy(Copy.RECORDED_APPLIED)
    LocalRecordedDecision.REJECTED -> copy(Copy.RECORDED_REJECTED)
    LocalRecordedDecision.RECORDED_ONLY -> LocalAppLanguage.current.text(LocalText(
        "Recorded — review the exact workflow state; not payment success", "سُجلت — راجع حالة سير العمل الدقيقة؛ هذا ليس نجاح دفع"))
}

@Composable
internal fun IdentifierText(label: String, identifier: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        SelectionContainer { Text("\u2066$identifier\u2069", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)) }
    }
}

@Composable
private fun RecordContents(record: LocalRecord) {
    val language = LocalAppLanguage.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(language.text(record.title), style = MaterialTheme.typography.titleMedium)
        record.status?.let { Text(language.text(it), style = MaterialTheme.typography.labelMedium) }
        RecordValues(record.values)
    }
}

@Composable
internal fun RecordValues(values: List<LocalRecordValue>) {
    val language = LocalAppLanguage.current
    values.forEach { entry -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(language.text(entry.label), style = MaterialTheme.typography.labelMedium, color = Muted)
        val literal = entry.value.english == entry.value.arabic
        val value = language.text(entry.value)
        // Display isolation only. Exact identifiers and serial codes are never normalized.
        Text(if (literal) "\u2066$value\u2069" else value,
            style = MaterialTheme.typography.bodyMedium.let { if (literal) it.copy(textDirection = TextDirection.Ltr) else it })
    } }
}

@Composable
internal fun ConfirmRow(label: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().touchTarget().toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = change),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ScalarEditor(field: LocalFormField.Scalar, input: LocalDraftValue, enabled: Boolean, isError: Boolean,
    chooseReference: () -> Unit, change: (LocalDraftValue) -> Unit) {
    val language = LocalAppLanguage.current
    val focusManager = LocalFocusManager.current
    val focus = remember(field.field) { FocusRequester() }
    val editable = enabled && !field.readOnly
    var choosing by remember(field.field) { mutableStateOf(false) }
    LaunchedEffect(enabled, field.readOnly) { if (!editable) choosing = false }
    val label = language.text(field.label)
    when (field.kind) {
        LocalInputKind.CHECKBOX -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ConfirmRow(label, (input.selected as? LocalScalarValue.Flag)?.value ?: false, editable) { change(LocalDraftValue(selected = LocalScalarValue.Flag(it))) }
            if (isError) Text(copy(Copy.INPUT_INVALID), color = MaterialTheme.colorScheme.error)
            field.help?.let { Text(language.text(it), style = MaterialTheme.typography.bodySmall, color = Muted) }
        }
        LocalInputKind.CHOICE, LocalInputKind.REFERENCE, LocalInputKind.MULTI_CHOICE, LocalInputKind.MULTI_REFERENCE -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label + if (field.required) " *" else "", style = MaterialTheme.typography.labelLarge)
                OutlinedButton(onClick = { if (field.referenceSource != null) chooseReference() else choosing = true }, enabled = editable,
                    modifier = Modifier.fillMaxWidth().touchTarget().semantics { contentDescription = label }) {
                    val chosen = selectedLabels(field, input).map(language::text)
                    Text(if (chosen.isEmpty()) copy(Copy.CHOOSE_VALUE) else chosen.joinToString(" · "))
                }
                if (isError) Text(copy(Copy.INPUT_INVALID), color = MaterialTheme.colorScheme.error)
                field.help?.let { Text(language.text(it), style = MaterialTheme.typography.bodySmall, color = Muted) }
                if (field.referenceSource != null && !field.required && input.selected != null)
                    TextButton(onClick = { change(LocalDraftValue()) }, enabled = editable, modifier = Modifier.touchTarget()) {
                        Text(language.text(LocalText("Clear selection", "مسح الاختيار")))
                    }
            }
            if (choosing) ScalarSelectionDialog(field, input, close = { choosing = false }, change = change)
        }
        else -> {
            LaunchedEffect(isError) { if (isError && editable) focus.requestFocus() }
            val numeric = field.kind in setOf(LocalInputKind.INTEGER, LocalInputKind.DECIMAL, LocalInputKind.DATE, LocalInputKind.IDENTIFIER)
            val technical = numeric || field.field == LocalField.SERIAL_CODE
            OutlinedTextField(value = input.text, onValueChange = { if (it.length <= field.maximumCharacters) change(LocalDraftValue(it)) },
                enabled = enabled, readOnly = field.readOnly, label = { Text(label + if (field.required) " *" else "") },
                singleLine = field.kind != LocalInputKind.MULTILINE, minLines = if (field.kind == LocalInputKind.MULTILINE) 3 else 1,
                isError = isError, supportingText = {
                    if (isError) Text(copy(Copy.INPUT_INVALID))
                    else field.help?.let { Text(language.text(it)) } ?: if (!field.required) Text(copy(Copy.OPTIONAL)) else Unit
                },
                textStyle = MaterialTheme.typography.bodyLarge.let { if (technical) it.copy(textDirection = TextDirection.Ltr) else it },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = if (field.field == LocalField.SERIAL_CODE) false else null, keyboardType = when (field.kind) {
                    LocalInputKind.DECIMAL -> KeyboardType.Decimal
                    LocalInputKind.INTEGER -> KeyboardType.Number
                    else -> KeyboardType.Text
                }, imeAction = if (field.kind == LocalInputKind.MULTILINE) ImeAction.Default else ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Next) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus))
        }
    }
}

private fun selectedLabels(field: LocalFormField.Scalar, input: LocalDraftValue) = when (val selected = input.selected) {
    is LocalScalarValue.Choice -> field.choices.filter { it.code == selected.code }.map { it.label }
    is LocalScalarValue.Reference -> field.references.filter { it.reference == selected.value }.map { it.label }.ifEmpty {
        listOf(input.referenceLabel ?: LocalText("\u2066${selected.value.id.value}\u2069", "\u2066${selected.value.id.value}\u2069"))
    }
    is LocalScalarValue.Choices -> field.choices.filter { it.code in selected.codes }.map { it.label }
    is LocalScalarValue.References -> if (field.field == LocalField.ARRIVAL_ORIGINAL_DOCUMENTS)
        selected.values.mapNotNull { reference -> field.references.singleOrNull { it.reference == reference }?.label }
    else field.references.filter { it.reference in selected.values }.map { it.label }
    else -> emptyList()
}

@Composable
private fun ScalarSelectionDialog(field: LocalFormField.Scalar, input: LocalDraftValue, close: () -> Unit, change: (LocalDraftValue) -> Unit) {
    val language = LocalAppLanguage.current
    var search by remember(field.field) { mutableStateOf("") }
    val multiple = field.kind == LocalInputKind.MULTI_CHOICE || field.kind == LocalInputKind.MULTI_REFERENCE
    AlertDialog(onDismissRequest = close,
        title = { Text(language.text(field.label)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = search, onValueChange = { if (it.length <= 256) search = it }, singleLine = true,
                    label = { Text(copy(Copy.SELECTION_SEARCH)) }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(field.choices.filter { language.text(it.label).contains(search, ignoreCase = true) }, key = { "choice-${it.code}" }) { choice ->
                        val selected = when (val value = input.selected) {
                            is LocalScalarValue.Choice -> value.code == choice.code
                            is LocalScalarValue.Choices -> choice.code in value.codes
                            else -> false
                        }
                        FilterChip(selected = selected, modifier = Modifier.fillMaxWidth().touchTarget(), label = { Text(language.text(choice.label)) },
                            onClick = {
                                if (multiple) {
                                    val before = (input.selected as? LocalScalarValue.Choices)?.codes.orEmpty()
                                    val next = if (selected) before - choice.code else before + choice.code
                                    if (next.size <= 32) change(LocalDraftValue(selected = LocalScalarValue.Choices(next)))
                                } else { change(LocalDraftValue(selected = LocalScalarValue.Choice(choice.code))); close() }
                            })
                    }
                    items(field.references.filter { language.text(it.label).contains(search, ignoreCase = true) },
                        key = { "reference-${it.reference.kind}-${it.reference.id.value}-${it.reference.revision?.value}" }) { choice ->
                        val selected = when (val value = input.selected) {
                            is LocalScalarValue.Reference -> value.value == choice.reference
                            is LocalScalarValue.References -> choice.reference in value.values
                            else -> false
                        }
                        FilterChip(selected = selected, modifier = Modifier.fillMaxWidth().touchTarget(), label = { Text(language.text(choice.label)) },
                            onClick = {
                                if (multiple) {
                                    val before = (input.selected as? LocalScalarValue.References)?.values.orEmpty()
                                    val next = if (selected) before - choice.reference else before + choice.reference
                                    if (next.size <= 32) change(LocalDraftValue(selected = LocalScalarValue.References(next)))
                                } else { change(LocalDraftValue(selected = LocalScalarValue.Reference(choice.reference))); close() }
                            })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = close, modifier = Modifier.touchTarget()) { Text(copy(Copy.DONE_SELECTION)) } },
        dismissButton = { if (!field.required) TextButton(onClick = { change(LocalDraftValue()); close() },
            modifier = Modifier.touchTarget()) { Text(copy(Copy.CLEAR_SELECTION)) } },
    )
}
