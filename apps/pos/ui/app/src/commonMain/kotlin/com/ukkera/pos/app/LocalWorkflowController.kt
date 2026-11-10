package com.ukkera.pos.app

import com.ukkera.pos.app.navigation.GuardHandle
import com.ukkera.pos.app.navigation.LeaveGuard
import com.ukkera.pos.app.navigation.NavigationToken
import com.ukkera.pos.security.PurchasingEvidencePurposeV1
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal enum class LocalWorkBusy { NONE, READING, FOOD_PREVIEW, REFERENCES, STAGING, SUBMITTING, LOOKUP, ORIGINAL_LOOKUP, PENDING, ACKNOWLEDGING, REPORT_EXPORT, REPORT_EXPORT_LOOKUP, ATTACHMENT_REVIEW,
    DOCUMENT_IMPORT, DOCUMENT_SAVE, DOCUMENT_SOURCES, DOCUMENT_TRANSFER, DOCUMENT_DOWNLOAD, DOCUMENT_COMPLETION_RETRY, ACTION_APPROVAL_PREVIEW, ACTION_APPROVAL_ISSUE, ACTION_APPROVAL_LOOKUP, ACTION_APPROVAL_RETIRE, ACTION_APPROVAL_REPLACE }

private val approvalWorkKinds = setOf(LocalWorkBusy.ACTION_APPROVAL_PREVIEW, LocalWorkBusy.ACTION_APPROVAL_ISSUE, LocalWorkBusy.ACTION_APPROVAL_LOOKUP,
    LocalWorkBusy.ACTION_APPROVAL_RETIRE, LocalWorkBusy.ACTION_APPROVAL_REPLACE)

private val documentaryWorkKinds = setOf(LocalWorkBusy.DOCUMENT_IMPORT, LocalWorkBusy.DOCUMENT_SAVE,
    LocalWorkBusy.DOCUMENT_SOURCES, LocalWorkBusy.DOCUMENT_TRANSFER, LocalWorkBusy.DOCUMENT_DOWNLOAD, LocalWorkBusy.DOCUMENT_COMPLETION_RETRY)

/** Ephemeral read controls, bound only to metadata returned by an authorized SKU read. */
internal data class LocalPricePreviewDraft(
    val controls: LocalPriceReadControls,
    val quantity: String = "",
    val channel: String? = null,
    val issue: LocalField? = null,
)

/** Only explicit zero decisions and a page coordinate supplement the entered physical slices.
 * No missing row/read or deleted slice is converted into a zero count. */
internal data class LocalOpeningCountDraft(
    val zeroAllocations: Set<DomainId> = emptySet(),
    val lineOffset: Int = 0,
    val sourceProblem: LocalServiceProblem? = null,
) {
    init { require(zeroAllocations.size <= LocalOpeningAllocationPage.MAX_ALLOCATIONS && lineOffset >= 0) }
}

internal data class LocalOpeningCountProgress(
    val totalAllocations: Int,
    val zeroAllocations: Int,
    val positiveAllocations: Int,
    val unfinishedAllocations: Int,
    val positiveSlices: Int,
    val canSave: Boolean,
)

/** Original-result knowledge only; no previously returned summary, record or FOOD projection. */
internal data class LocalOperationReadContinuation(val recorded: Boolean, val problem: LocalServiceProblem? = null)

internal data class LocalWorkspaceState(
    val workflow: LocalWorkflow = LocalWorkflow.CHECKOUT,
    val search: String = "",
    val reference: LocalRecordReference? = null,
    val page: LocalWorkflowPage? = null,
    val priceDraft: LocalPricePreviewDraft? = null,
    /** Last explicitly submitted read; an edited draft never silently replaces this query. */
    val pricePreview: LocalPricePreviewQuery? = null,
    val foodPricePreview: LocalFoodPricePreview? = null,
    val foodPricePreviewProblem: LocalServiceProblem? = null,
    val branchReportQuery: LocalBranchReportQuery = LocalBranchReportQuery(LocalBranchReportSection.CASH_SESSIONS),
    val branchReportExport: LocalBranchReportExportPresentation = LocalBranchReportExportPresentation(),
    val foodQuery: LocalFoodUiQuery = workflow.defaultFoodQuery(),
    /** A read selector only; never a retained task projection or an authority grant. */
    val kitchenBoardOrigin: LocalFoodUiQuery.StationTasks? = null,
    val foodUnavailable: LocalFoodUnavailable? = null,
    val purchasingQuery: LocalPurchasingUiQuery = LocalPurchasingUiQuery(),
    val purchasingDocuments: LocalPurchasingDocumentPresentation = LocalPurchasingDocumentPresentation(),
    val returnQuery: LocalReturnUiQuery = LocalReturnUiQuery(),
    val cashQuery: LocalCashUiQuery = LocalCashUiQuery(),
    val inventorySection: LocalInventorySection = LocalInventorySection.QUANTITIES,
    val readProblem: LocalServiceProblem? = null,
    val busy: LocalWorkBusy = LocalWorkBusy.NONE,
    val pending: List<LocalPendingOperation> = emptyList(),
    val pendingLoaded: Boolean = false,
    val pendingProblem: LocalServiceProblem? = null,
    val pendingCursor: String? = null,
    val pendingPageIsFirst: Boolean = true,
    /** Ephemeral read input only; never a staged identity, saved continuation or authority. */
    val originalLookupInput: String = "",
    val originalLookupProblem: LocalServiceProblem? = null,
    val form: LocalFormDraft? = null,
    val formInput: LocalFormInputContinuation? = null,
    val openingCounts: LocalOpeningCountDraft? = null,
    val referencePicker: LocalReferencePickerState? = null,
    val issue: LocalInputIssue? = null,
    val stageProblem: LocalServiceProblem? = null,
    val operationId: OperationId? = null,
    val operation: LocalOperationResult? = null,
    val operationRead: LocalOperationReadContinuation? = null,
    val stageUncertain: Boolean = false,
    val recordedAcknowledged: Boolean = false,
    val acknowledgementProblem: LocalServiceProblem? = null,
    val attachmentReviewRequired: Boolean = false,
    val actionApproval: LocalActionApprovalPresentation? = null,
    val actionApprovalLookupWorking: Boolean = false,
) {
    val working: Boolean get() = busy != LocalWorkBusy.NONE
    val managerOrderReview: Boolean get() = workflow.isManagerOrderReview(search)
    val managerOrderContext: Boolean get() = workflow.isManagerOrderContext(search)
    val recoveryRequired: Boolean get() = !pendingLoaded || pendingProblem != null || pending.isNotEmpty() ||
        pendingCursor != null || !pendingPageIsFirst || operationId != null && !recordedAcknowledged
    val canStart: Boolean get() = !managerOrderReview && !working && actionApproval == null && !attachmentReviewRequired && !recoveryRequired && page != null && form == null && formInput == null && !purchasingDocuments.hasDraft
    val formFrozen: Boolean get() = operationId != null && operation !is LocalOperationResult.Recorded
    val canLookupOriginal: Boolean get() = !managerOrderReview && !working && actionApproval == null &&
        !attachmentReviewRequired && form == null && formInput == null && !purchasingDocuments.hasDraft &&
        (operationId == null || recordedAcknowledged)
    val openingCountProgress: LocalOpeningCountProgress? get() = countedOpeningProgress(form, openingCounts)
    val openingCanSave: Boolean get() = openingCountProgress?.canSave != false && issue?.tooLarge != true
}

private fun LocalWorkspaceState.retiredOperationRead(problem: LocalServiceProblem? = null): LocalOperationReadContinuation? =
    operationId?.takeUnless { recordedAcknowledged }?.let { id ->
        LocalOperationReadContinuation((operation as? LocalOperationResult.Recorded)?.operationId == id || operationRead?.recorded == true, problem)
    }

/** Refusal-only UI binding. The Master independently authorizes the current target. */
private fun LocalActionForm.matchesManagerReassignment(search: String): Boolean {
    if (!search.startsWith("reassign:") || action != LocalAction.ORDER_REASSIGN || version != 2 ||
        fields.any { it !is LocalFormField.Scalar }) return false
    val scalars = fields.filterIsInstance<LocalFormField.Scalar>()
    fun fixed(field: LocalField): LocalScalarValue? = scalars.singleOrNull { it.field == field }
        ?.takeIf { it.required && it.readOnly }?.initialValue
    return (fixed(LocalField.ORDER_ID) as? LocalScalarValue.Identifier)?.value?.value == search.removePrefix("reassign:") &&
        fixed(LocalField.PREVIOUS_OWNER_ID) is LocalScalarValue.Identifier &&
        (fixed(LocalField.EXPECTED_REVISION) as? LocalScalarValue.Integer)?.value?.let { it >= 0 } == true
}

/** SAME-entry data only. No gateway, credentials, native preparation, instruction or file lease. */
internal data class LocalWorkflowContinuation(
    val workspace: LocalWorkspaceState,
    val frozen: LocalCommandIntent?,
    val originalAction: LocalAction?,
    val retail: LocalRetailDraftContinuation?,
    val supplier: LocalSupplierDraftContinuation?,
)

/**
 * Bounded continuity after report/cost-only changes. These workflows expose no cost/margin
 * inputs. Keep original recovery IDs/bodies and permitted entered data, not returned results,
 * unrelated editors, read controls, cursors, confirmations or physical action capabilities.
 */
internal fun LocalWorkflowContinuation.cashierPolicyContinuation(): LocalWorkflowContinuation? {
    if (workspace.workflow !in setOf(LocalWorkflow.CHECKOUT, LocalWorkflow.ORDERS) ||
        originalAction?.canAppearIn(workspace.workflow) == false) return null
    val recover = workspace.operationId?.takeUnless { workspace.recordedAcknowledged }
    return copy(workspace = workspace.copy(page = null, priceDraft = null, pricePreview = null, foodPricePreview = null, foodPricePreviewProblem = null,
        branchReportQuery = LocalBranchReportQuery(LocalBranchReportSection.CASH_SESSIONS),
        branchReportExport = LocalBranchReportExportPresentation(), foodQuery = LocalFoodUiQuery.MyChecks(),
        kitchenBoardOrigin = null, foodUnavailable = null, purchasingQuery = LocalPurchasingUiQuery(),
        purchasingDocuments = LocalPurchasingDocumentPresentation(), actionApproval = null, actionApprovalLookupWorking = false, returnQuery = LocalReturnUiQuery(),
        cashQuery = LocalCashUiQuery(), inventorySection = LocalInventorySection.QUANTITIES,
        pending = emptyList(), pendingLoaded = false, pendingCursor = null, pendingPageIsFirst = true,
        referencePicker = null, openingCounts = null, form = null, originalLookupInput = "", originalLookupProblem = null,
        formInput = (workspace.formInput ?: workspace.form?.inputContinuation()).takeIf { recover == null },
        operationId = recover,
        operation = recover?.let { LocalOperationResult.Unresolved(it, LocalServiceProblem.UNAVAILABLE) },
        operationRead = workspace.retiredOperationRead(),
        recordedAcknowledged = false, attachmentReviewRequired = true),
        frozen = frozen.takeIf { recover != null }, originalAction = originalAction.takeIf { recover != null },
        retail = retail?.takeIf { it.workflow == workspace.workflow }?.let {
            it.copy(foodCollectionId = null, foodRefundId = null,
                observation = it.observation.copy(selected = null, confirmed = false))
        }, supplier = null)
}

/**
 * Entry/actor-owned presentation, not an application service. No financial effects, transport
 * credentials, result journal or synthetic success live here. Every operation goes through the
 * current session's actual gateway, which owns durable staging and original-actor recovery.
 */
internal class LocalWorkflowController(
    private val runtime: UkkeraRuntime,
    private val session: BranchUiSession,
    private val owner: NavigationToken,
    private val scope: CoroutineScope,
    private var continuation: LocalWorkflowContinuation? = null,
    private val employeeAccessDenied: (LocalWorkflowController) -> Unit = {},
) {
    private val attachmentKey = session.attachmentKey
    private val gateway = session.workflows
    private val invoiceAction: LocalAction? get() = session.display.workspaceMode.invoiceUiAction()
    private val invoiceOnly: Boolean get() = invoiceAction != null
    private val invoiceCaptureOnly: Boolean get() = session.display.workspaceMode == LocalSessionWorkspaceMode.INVOICE_CAPTURE_ONLY
    private val invoiceMatchingOnly: Boolean get() = session.display.workspaceMode == LocalSessionWorkspaceMode.INVOICE_MATCHING_ONLY
    private val paymentCorrectionsOnly: Boolean get() = session.display.workspaceMode == LocalSessionWorkspaceMode.PAYMENT_CORRECTIONS_ONLY
    private val customerReceiptsOnly: Boolean get() = session.display.workspaceMode == LocalSessionWorkspaceMode.CUSTOMER_RECEIPTS_ONLY
    val foodChecksOnly: Boolean get() = session.display.workspaceMode == LocalSessionWorkspaceMode.FOOD_CHECKS_ONLY
    val foodCashFinancialOnly: Boolean get() = session.display.workspaceMode == LocalSessionWorkspaceMode.FOOD_CASH_FINANCIAL_ONLY
    val foodTableAssignmentsOnly: Boolean get() = session.display.workspaceMode == LocalSessionWorkspaceMode.FOOD_TABLE_ASSIGNMENTS_ONLY
    private val documentaryOnly: Boolean get() = paymentCorrectionsOnly || customerReceiptsOnly
    private val nonphysicalOnly: Boolean get() = documentaryOnly || foodChecksOnly || foodCashFinancialOnly || foodTableAssignmentsOnly || invoiceOnly
    private fun defaultReturnQuery(): LocalReturnUiQuery = LocalReturnUiQuery(
        if (customerReceiptsOnly) LocalReturnSection.RECEIPTS else LocalReturnSection.CASES)
    private fun defaultPurchasingQuery(reference: LocalRecordReference? = null): LocalPurchasingUiQuery = LocalPurchasingUiQuery(
        if (invoiceOnly) {
            if (reference?.kind == LocalRecordKind.PURCHASE) LocalPurchasingSection.ORDERS else LocalPurchasingSection.INVOICES
        } else LocalPurchasingSection.SUPPLIERS)
    // Keep unsupported physical data inert across attachment retirement; never restore a
    // physical controller or replace its original identity through a SQL-only profile.
    private val retainedPhysicalRetail = continuation?.retail.takeIf { invoiceOnly || foodCashFinancialOnly }
    private val retainedPhysicalSupplier = continuation?.supplier.takeIf { invoiceOnly || foodCashFinancialOnly }
    // An incompatible retained continuation survives transient UI deactivation, not close.
    private var inertNonphysicalContinuation: LocalWorkflowContinuation? = null
    // First composition must see the retained workflow, not a temporary CHECKOUT screen.
    private val mutableState = MutableStateFlow(continuation?.workspace?.copy(purchasingDocuments = LocalPurchasingDocumentPresentation(),
        originalLookupInput = "", originalLookupProblem = null) ?: LocalWorkspaceState(
            workflow = if (invoiceOnly) LocalWorkflow.PURCHASING else if (foodCashFinancialOnly) LocalWorkflow.FOOD else LocalWorkflow.CHECKOUT,
            purchasingQuery = defaultPurchasingQuery()))
    val state: StateFlow<LocalWorkspaceState> = mutableState.asStateFlow()
    private var active = false
    private var closed = false
    private var activation = 0L
    private var work: Job? = null
    private var heldActionApprovalLookup: Job? = null
    private var reportLifecycle: Job? = null
    private var foodPreviewLifecycle: Job? = null
    private var foodPreviewEpoch = 0L
    private var purchasingDocumentEpoch = 0L
    private var actionApprovalEpoch = 0L
    private var purchasingDocumentInput: LocalPurchasingDocumentFileSelection.Selected? = null
    private var reportFileLifetime: LocalBranchReportExportLifetime? = null
    private var guard: GuardHandle? = null
    private var guardKind = LeaveGuard.CLEAN
    private var frozen: LocalCommandIntent? = null
    private var originalAction: LocalAction? = null
    private var editorBeforeGuard: Long? = null
    private var editorAfterGuard: Long? = null
    private var companyCatalogDraft = false
    private var companyCatalogWorking = false
    private var foodConfigurationDraft = false
    private var foodConfigurationWorking = false
    private var returnRightsDraft = false
    private var returnRightsWorking = false
    private var pricePolicyDraft = false
    private var pricePolicyWorking = false
    private var serialRegistrationDraft = false
    private var serialRegistrationWorking = false
    private var catalogReleaseDraft = false
    private var catalogReleaseWorking = false
    private val configurationOwnsDraft: Boolean get() = companyCatalogDraft || companyCatalogWorking ||
        foodConfigurationDraft || foodConfigurationWorking || returnRightsDraft || returnRightsWorking ||
        pricePolicyDraft || pricePolicyWorking || catalogReleaseDraft || catalogReleaseWorking || serialRegistrationDraft || serialRegistrationWorking
    val retailPhysical: LocalRetailPhysicalController = LocalRetailPhysicalController(runtime, session, owner, scope,
        parentAvailable = { physicalParentReady() && !supplierPhysical.blocksParentWork },
        guardChanged = { syncGuard() })
    val supplierPhysical: LocalSupplierCashController = LocalSupplierCashController(runtime, session, owner, scope,
        parentAvailable = { physicalParentReady() && !retailPhysical.blocksParentWork },
        guardChanged = { syncGuard() })

    private fun physicalParentReady(): Boolean = !nonphysicalOnly && current() && !state.value.managerOrderContext && !state.value.attachmentReviewRequired && !configurationOwnsDraft && !state.value.working && state.value.actionApproval == null &&
        state.value.form == null && !state.value.formFrozen && !state.value.purchasingDocuments.hasDraft

    private fun activateRetailPhysical(workflow: LocalWorkflow, reference: LocalRecordReference?, food: LocalFoodUiQuery) {
        if (!nonphysicalOnly) retailPhysical.activate(workflow, reference, food)
    }

    private fun activateSupplierPhysical() {
        if (nonphysicalOnly) return
        val current = state.value
        // A typed selector from the actual selected purchasing record, never label/text parsing.
        val selected = current.page?.records?.singleOrNull { it.reference == current.reference && it.selectable }
        // Until the selected invoice's real format has been read, enable original recovery
        // only. A capture is never a legacy invoice payout target, even with cost access.
        val freshPayoutAllowed = if (current.reference?.kind == LocalRecordKind.PURCHASE_INVOICE)
            selected?.invoiceEntryKind == LocalInvoiceEntryKind.ORDER_LINKED_V1 else true
        supplierPhysical.activate(current.workflow, current.reference, current.purchasingQuery, selected?.supplierId, freshPayoutAllowed)
    }

    fun activate(workflow: LocalWorkflow, reference: LocalRecordReference? = null, food: LocalFoodUiQuery? = null) {
        if (invoiceOnly && (workflow != LocalWorkflow.PURCHASING || food != null ||
                reference != null && invoiceUiSections.none { reference.isInvoiceUiSelection(it) })) return
        if (foodCashFinancialOnly && (workflow != LocalWorkflow.FOOD || reference != null || food?.isFoodCashFinancialUiQuery() == false)) return
        if (foodChecksOnly && (workflow != LocalWorkflow.FOOD || reference != null || food?.isFoodCheckUiQuery() == false)) return
        if (foodTableAssignmentsOnly && (workflow != LocalWorkflow.FOOD || reference != null || food?.isFoodTableAssignmentUiQuery() == false)) return
        if (customerReceiptsOnly && (workflow !in customerReceiptUiWorkflows || food != null ||
                reference?.isCustomerReceiptUiSelection() == false)) return
        if (paymentCorrectionsOnly && (workflow !in paymentCorrectionUiWorkflows || food != null ||
                reference?.isPaymentCorrectionUiSelection() == false)) return
        if (session.display.workspaceMode == LocalSessionWorkspaceMode.RETAIL_ORDERS_ONLY &&
            (workflow !in setOf(LocalWorkflow.CHECKOUT, LocalWorkflow.ORDERS) || food != null ||
                reference?.kind?.let { it !in setOf(LocalRecordKind.ORDER, LocalRecordKind.SKU) } == true)) return
        if (session.display.workspaceMode == LocalSessionWorkspaceMode.KITCHEN_ONLY &&
            (workflow != LocalWorkflow.KITCHEN || reference != null || food?.canAppearIn(LocalWorkflow.KITCHEN) == false)) return
        if (closed || active || !runtime.ownsLocalAttachment(session, owner, attachmentKey)) return
        supplierPhysical.deactivate()
        retailPhysical.deactivate()
        active = true
        activation++
        val resumed = continuation ?: inertNonphysicalContinuation
        continuation = null
        mutableState.value = resumed?.workspace?.copy(purchasingDocuments = LocalPurchasingDocumentPresentation(),
            originalLookupInput = "", originalLookupProblem = null) ?: LocalWorkspaceState(workflow = workflow, reference = reference,
            foodQuery = food ?: workflow.defaultFoodQuery(), purchasingQuery = defaultPurchasingQuery(reference), returnQuery = defaultReturnQuery())
        frozen = resumed?.frozen
        originalAction = resumed?.originalAction
        if (paymentCorrectionsOnly && !paymentCorrectionWorkspaceCompatible() ||
            customerReceiptsOnly && !customerReceiptWorkspaceCompatible() || foodChecksOnly && !foodCheckWorkspaceCompatible() ||
            foodCashFinancialOnly && !foodCashFinancialWorkspaceCompatible() ||
            foodTableAssignmentsOnly && !foodTableAssignmentWorkspaceCompatible() || !invoiceWorkspaceCompatible()) {
            if (invoiceOnly || foodCashFinancialOnly) inertNonphysicalContinuation = resumed
            syncGuard()
            return // Keep unrelated continuation inert for its compatible original workflow.
        }
        if (!nonphysicalOnly && !state.value.managerOrderContext) {
            activateRetailPhysical(state.value.workflow, state.value.reference, state.value.foodQuery)
            resumed?.retail?.let(retailPhysical::restoreDraft)
        }
        activateSupplierPhysical()
        if (!nonphysicalOnly) resumed?.supplier?.let(supplierPhysical::restoreDraft)
        session.lifecycle?.let { lifecycle ->
            foodPreviewLifecycle = scope.launch {
                lifecycle.collect { state ->
                    if (state != LocalSessionLifecycle.ACTIVE) {
                        foodPreviewEpoch++
                        if (this@LocalWorkflowController.state.value.busy in documentaryWorkKinds ||
                            this@LocalWorkflowController.state.value.busy in approvalWorkKinds) work?.cancel()
                        actionApprovalEpoch++
                        heldActionApprovalLookup?.cancel()
                        heldActionApprovalLookup = null
                        clearPurchasingDocuments()
                        mutableState.value = this@LocalWorkflowController.state.value.copy(
                            foodPricePreview = null, foodPricePreviewProblem = null, actionApproval = null, actionApprovalLookupWorking = false,
                            originalLookupInput = "", originalLookupProblem = null)
                    }
                }
            }
        }
        if (state.value.workflow == LocalWorkflow.REPORTS) {
            val lifecycle = session.lifecycle
            if (lifecycle == null) {
                // A missing protected-session monitor cannot authorize retained report views.
                mutableState.value = state.value.copy(readProblem = LocalServiceProblem.NOT_CONFIGURED)
                return
            }
            watchReportLifecycle(lifecycle)
        }
        syncGuard()
        // A renewed entry never repeats staging, submission, acknowledgement or a physical
        // action. Reports have no input draft and may perform their normal fresh protected read.
        if (!state.value.attachmentReviewRequired) load()
    }

    fun retireForAttachment(): LocalWorkflowContinuation? {
        if (closed) { close(); return null } // Retry failed exact cleanup before releasing this holder.
        // Another attachment may arrive after construction but before Compose activates this
        // controller. Preserve its still-unconsumed data, not the inactive default workspace.
        if (!active) return (continuation ?: inertNonphysicalContinuation)?.let { it.copy(workspace = it.workspace.copy(purchasingDocuments = LocalPurchasingDocumentPresentation(), actionApproval = null, actionApprovalLookupWorking = false,
            originalLookupInput = "", originalLookupProblem = null)) }.also { close() }
        val before = state.value
        val id = before.operationId.takeUnless { before.recordedAcknowledged }
        val input = (before.formInput ?: before.form?.inputContinuation()).takeIf { id == null }
        val snapshot = before.copy(page = null, foodPricePreview = null, foodPricePreviewProblem = null, priceDraft = before.priceDraft?.copy(issue = null),
            branchReportQuery = before.branchReportQuery.copy(after = null, expectedBranchRevision = null),
            branchReportExport = LocalBranchReportExportPresentation(),
            purchasingDocuments = LocalPurchasingDocumentPresentation(), actionApproval = null, actionApprovalLookupWorking = false,
            foodUnavailable = null, readProblem = null, busy = LocalWorkBusy.NONE,
            pending = emptyList(), pendingLoaded = false, pendingProblem = null,
            pendingCursor = null, pendingPageIsFirst = true, referencePicker = null, originalLookupInput = "", originalLookupProblem = null,
            // A recipient cursor is an old discovery position, not original business input.
            // Review must rediscover from root even when the selected actor moved pages.
            foodQuery = if (input?.action == LocalAction.FOOD_OFFER_HANDOFF && before.foodQuery is LocalFoodUiQuery.Recipients)
                before.foodQuery.copy(after = null) else before.foodQuery,
            form = null, formInput = input,
            issue = null, stageProblem = null,
            operationId = id, operation = id?.let { LocalOperationResult.Unresolved(it, LocalServiceProblem.UNAVAILABLE) },
            operationRead = before.retiredOperationRead(),
            stageUncertain = before.stageUncertain || before.busy == LocalWorkBusy.STAGING,
            acknowledgementProblem = if (before.busy == LocalWorkBusy.ACKNOWLEDGING)
                LocalServiceProblem.STORAGE_UNCERTAIN else before.acknowledgementProblem,
            attachmentReviewRequired = before.workflow != LocalWorkflow.REPORTS)
        val retained = LocalWorkflowContinuation(snapshot, frozen.takeIf { id != null }, originalAction.takeIf { id != null },
            retainedPhysicalRetail ?: retailPhysical.snapshotDraft(), retainedPhysicalSupplier ?: supplierPhysical.snapshotDraft())
        close() // Denies originals/exports and cancels jobs; late replies cannot change retained data.
        return retained
    }

    /** Explicit protected reread; no old projection, confirmation or response grants new work. */
    fun reviewAttachment(token: NavigationToken) {
        if (!acceptsEntry(token) || !state.value.attachmentReviewRequired || state.value.working ||
            companyCatalogWorking || foodConfigurationWorking || returnRightsWorking || pricePolicyWorking || catalogReleaseWorking || serialRegistrationWorking) return
        val query = currentQuery()
        if (paymentCorrectionsOnly && !query.isPaymentCorrectionUiQuery()) {
            readUnavailable(LocalServiceProblem.INVALID_INPUT)
            return
        }
        mutableState.value = state.value.copy(page = null, readProblem = null, pendingProblem = null)
        run(LocalWorkBusy.ATTACHMENT_REVIEW) { ticket ->
            if (!query.workflow.isManagerOrderReview(query.search)) {
                val pending = gateway.pending()
                currentCoroutineContext().ensureActive()
                if (!current(ticket)) return@run
                applyPending(pending, null)
                if (!current(ticket) || pending !is LocalPendingResult.Available ||
                    (foodChecksOnly || foodCashFinancialOnly || foodTableAssignmentsOnly || invoiceOnly) && (!state.value.pendingLoaded || state.value.pendingProblem != null)) return@run
            }
            val result = gateway.read(query)
            currentCoroutineContext().ensureActive()
            if (!current(ticket)) return@run
            when (result) {
                is LocalReadResult.Available -> {
                    val supplierDraft = supplierPhysical.snapshotDraft()
                    applyPage(query, result.page)
                    supplierDraft?.let(supplierPhysical::restoreDraft)
                    if (state.value.page != null && state.value.readProblem == null && restoreFormInput(query, ticket) && current(ticket)) {
                        mutableState.value = state.value.copy(attachmentReviewRequired = false)
                        if (documentaryCurrent()) readPurchasingDocumentSources(ticket)
                    }
                }
                is LocalReadResult.Unavailable -> {
                    mutableState.value = state.value.copy(readProblem = result.problem)
                    permissionFailure(result.problem)
                }
            }
        }
    }

    /** Only a newly accepted protected page supplies the metadata for preserved unsent input. */
    private suspend fun restoreFormInput(query: LocalWorkflowQuery, ticket: RequestOwner): Boolean {
        val input = state.value.formInput ?: return true
        // A frozen request is recovered by its original ID/body; never prepare it as a new form.
        val page = state.value.page ?: return false
        val source = page.forms.singleOrNull { it.action == input.action && it.version == input.version }
        if (frozen != null || state.value.operationId != null || source == null)
            return restoreInputUnavailable(LocalServiceProblem.INVALID_INPUT)
        val form = restoreFoodInputForm(input, query, page, source, ticket)
        currentCoroutineContext().ensureActive()
        if (!current(ticket) || currentQuery() != query || state.value.formInput !== input || state.value.page !== page) return false
        val rebound = form?.let { if (input.action in foodSelectionActions) input.rebindFreshFoodSelection(it) else input.rebind(it) }
        if (rebound == null) {
            if (state.value.readProblem == null) restoreInputUnavailable(
                if (input.action.workflow == LocalWorkflow.FOOD) LocalServiceProblem.FOOD_TARGET_UNAVAILABLE else LocalServiceProblem.INVALID_INPUT)
            return false
        }
        mutableState.value = state.value.copy(form = rebound, formInput = null)
        return true
    }

    private fun restoreInputUnavailable(problem: LocalServiceProblem): Boolean {
        mutableState.value = state.value.copy(page = null, form = null, foodPricePreview = null, foodPricePreviewProblem = null, readProblem = problem,
            foodUnavailable = if (state.value.workflow.usesFoodQueries) foodUnavailableReason(problem) else state.value.foodUnavailable)
        permissionFailure(problem)
        return false
    }

    /** Bounded original-source reads only; they never stage, dispatch, acknowledge or create identities. */
    private suspend fun restoreFoodInputForm(input: LocalFormInputContinuation, query: LocalWorkflowQuery,
        page: LocalWorkflowPage, source: LocalActionForm, ticket: RequestOwner): LocalActionForm? {
        return when (input.action) {
            in foodSelectionActions -> {
                val selected = query.food as? LocalFoodUiQuery.Menu ?: return null
                val currentMenu = page.food?.body as? LocalFoodUiBody.Menu ?: return null
                val originals = input.foodMenuSourceQueries(selected) ?: return null
                if (input.action == LocalAction.FOOD_ADD) {
                    // The menu response echoes an add selector; it is not a fresh owner cut.
                    val orderId = selected.orderId ?: return null
                    val target = readFoodInputSource(LocalFoodUiQuery.Check(orderId), ticket) ?: return null
                    val check = (target.food?.body as? LocalFoodUiBody.Check)?.check ?: return null
                    if (check.orderId != orderId || check.orderStorageRevision != selected.expectedOrderStorageRevision) return null
                }
                // At most eight unique original queries, not a next-page loop or catalog crawl.
                // Keep the displayed page as its one real cut; only exact row inputs are joined.
                val menus = mutableMapOf<LocalFoodUiQuery.Menu, LocalFoodUiBody.Menu>()
                for (original in originals) {
                    val fresh = if (original == selected) page else readFoodInputSource(original, ticket) ?: return null
                    if (!current(ticket)) return null
                    val menu = fresh.food?.body as? LocalFoodUiBody.Menu ?: return null
                    if (fresh.forms.singleOrNull { it.action == input.action && it.version == input.version } != source) return null
                    menus[original] = menu
                }
                input.freshFoodSelectionForm(source, selected, currentMenu.catalogRevision, menus)
            }
            LocalAction.FOOD_OFFER_HANDOFF -> {
                restoreFoodRecipientForm(input, query, page, source, ticket)
            }
            else -> source
        }
    }

    /** One fresh root-to-terminal discovery, never a merged public page or an old cursor.
     * Keep the actual selected page only until the whole chain is accepted. A changed business cut,
     * denied page, repeated actor/cursor or unfinished bounded traversal leaves input inactive. */
    private suspend fun restoreFoodRecipientForm(input: LocalFormInputContinuation, query: LocalWorkflowQuery,
        page: LocalWorkflowPage, source: LocalActionForm, ticket: RequestOwner): LocalActionForm? {
        val root = query.food as? LocalFoodUiQuery.Recipients ?: return null
        val original = input.foodRecipientSource ?: return null
        if (root.after != null || root != original.copy(after = null)) return null
        val selected = input.values[LocalField.RECIPIENT_ID]
            ?.takeIf { it.inputIdentityOrigin == LocalInputIdentityOrigin.SELECTED }?.text ?: return null
        val revision = page.food?.branchRevision ?: return null
        val queries = mutableSetOf<LocalFoodUiQuery.Recipients>()
        val recipients = mutableSetOf<DomainId>()
        var currentQuery = root
        var currentPage = page
        var selectedPage: LocalFoodUiPage? = null
        repeat(128) {
            if (!current(ticket) || !queries.add(currentQuery)) return null
            val fresh = currentPage.food ?: return null
            val body = fresh.body as? LocalFoodUiBody.Recipients ?: return null
            if (fresh.query != currentQuery || fresh.branchRevision != revision ||
                body.orderId != root.orderId || body.orderStorageRevision != root.expectedOrderStorageRevision ||
                currentPage.forms.singleOrNull { it.action == input.action && it.version == input.version } != source ||
                body.recipients.any { it.actorId == session.display.actorId || !recipients.add(it.actorId) }) return null
            if (body.recipients.any { it.actorId.value == selected }) selectedPage = fresh
            val next = fresh.next
            if (next == null) {
                val matched = selectedPage ?: return null
                return input.freshFoodRecipientForm(source, matched.query as LocalFoodUiQuery.Recipients,
                    matched.body as LocalFoodUiBody.Recipients, session.display.actorId)
            }
            val continuation = next as? LocalFoodUiQuery.Recipients ?: return null
            if (continuation.orderId != root.orderId || continuation.expectedOrderStorageRevision != root.expectedOrderStorageRevision ||
                continuation.after == null || continuation in queries) return null
            if (queries.size == 128) {
                restoreInputUnavailable(LocalServiceProblem.FOOD_CAPACITY_REQUIRED)
                return null
            }
            currentQuery = continuation
            currentPage = readFoodInputSource(continuation, ticket) ?: return null
        }
        return null // No truncated discovery can enable confirmation.
    }

    /** Reply identity is checked after every suspension on the same attachment and navigation ticket. */
    private suspend fun readFoodInputSource(selected: LocalFoodUiQuery, ticket: RequestOwner): LocalWorkflowPage? {
        if (!current(ticket)) return null
        val query = LocalWorkflowQuery(LocalWorkflow.FOOD, food = selected)
        val result = gateway.read(query)
        currentCoroutineContext().ensureActive()
        if (!current(ticket)) return null
        val page = (result as? LocalReadResult.Available)?.page
        if (page == null || !matchesFoodPage(query, page)) {
            restoreInputUnavailable((result as? LocalReadResult.Unavailable)?.problem ?: LocalServiceProblem.UNAVAILABLE)
            return null
        }
        return snapshotPage(page)
    }

    /** Leaving destroys private editor/read material. The gateway's durable queue is not erased. */
    fun deactivate() {
        // Linearize native lifetime end before clearing presentation or cancelling its job.
        val cleanup = LocalSessionCleanup()
        actionApprovalEpoch++
        cleanup.attempt { supplierPhysical.deactivate() }
        cleanup.attempt { retailPhysical.deactivate() }
        cleanup.attempt { reportFileLifetime?.end(); reportFileLifetime = null }
        cleanup.attempt { clearPurchasingDocuments() }
        active = false
        activation++ // invalidate even a non-cooperative gateway's late reply before cleanup
        cleanup.attempt { work?.cancel(); work = null }
        cleanup.attempt { heldActionApprovalLookup?.cancel(); heldActionApprovalLookup = null }
        cleanup.attempt { reportLifecycle?.cancel(); reportLifecycle = null }
        cleanup.attempt { foodPreviewLifecycle?.cancel(); foodPreviewLifecycle = null }
        frozen = null
        originalAction = null
        continuation = null
        editorBeforeGuard = null
        editorAfterGuard = null
        companyCatalogDraft = false
        companyCatalogWorking = false
        foodConfigurationDraft = false
        foodConfigurationWorking = false
        returnRightsDraft = false
        returnRightsWorking = false
        pricePolicyDraft = false
        pricePolicyWorking = false
        serialRegistrationDraft = false
        serialRegistrationWorking = false
        catalogReleaseDraft = false
        catalogReleaseWorking = false
        mutableState.value = LocalWorkspaceState()
        cleanup.attempt { removeGuard() }
        cleanup.throwIfFailed()
    }

    fun close() { try { deactivate() } finally { inertNonphysicalContinuation = null; closed = true } }

    /** One existing entry guard owns both the ordinary workflow and native publication form. */
    fun companyCatalogGuard(draft: Boolean, working: Boolean) {
        if (!current() || state.value.workflow != LocalWorkflow.CATALOG) return
        companyCatalogDraft = draft
        companyCatalogWorking = working
        syncGuard()
    }

    /** Independent cohost state: one card cannot clear the other card's dirty/critical guard. */
    fun foodConfigurationGuard(draft: Boolean, working: Boolean) {
        if (!current() || state.value.workflow != LocalWorkflow.CATALOG) return
        foodConfigurationDraft = draft
        foodConfigurationWorking = working
        syncGuard()
    }

    fun returnRightsGuard(draft: Boolean, working: Boolean) {
        if (!current() || state.value.workflow != LocalWorkflow.CATALOG) return
        returnRightsDraft = draft
        returnRightsWorking = working
        syncGuard()
    }

    fun pricePolicyGuard(draft: Boolean, working: Boolean) {
        if (!current() || state.value.workflow != LocalWorkflow.CATALOG) return
        pricePolicyDraft = draft
        pricePolicyWorking = working
        syncGuard()
    }

    fun serialRegistrationGuard(draft: Boolean, working: Boolean) {
        if (!current() || state.value.workflow != LocalWorkflow.CATALOG) return
        serialRegistrationDraft = draft
        serialRegistrationWorking = working
        syncGuard()
    }

    fun catalogReleaseGuard(draft: Boolean, working: Boolean) {
        if (!current() || state.value.workflow != LocalWorkflow.CATALOG) return
        catalogReleaseDraft = draft
        catalogReleaseWorking = working
        syncGuard()
    }

    /** Dedicated setup completion refreshes ONLY saved operations, even while its card unwinds. */
    fun refreshConfigurationPending(token: NavigationToken) {
        if (state.value.workflow != LocalWorkflow.CATALOG || !acceptsEntry(token) || retailPhysical.blocksParentWork || supplierPhysical.blocksParentWork ||
            state.value.working || state.value.actionApproval != null || state.value.form != null) return
        // A saved operation remains immutable, but must not prevent this protected
        // read or known-denial cleanup. Real forms and active work stay guarded.
        readPending(null)
    }

    private fun current(ticket: Long = activation): Boolean = !closed && active && ticket == activation &&
        runtime.ownsLocalAttachment(session, owner, attachmentKey)

    private data class RequestOwner(val activation: Long, val navigation: NavigationToken, val documentEpoch: Long? = null, val approvalEpoch: Long? = null)

    private fun current(request: RequestOwner): Boolean = current(request.activation) && runtime.navigator.isCurrent(request.navigation) &&
        (request.documentEpoch == null || request.documentEpoch == purchasingDocumentEpoch && session.lifecycle?.value == LocalSessionLifecycle.ACTIVE) &&
        (request.approvalEpoch == null || request.approvalEpoch == actionApprovalEpoch && session.lifecycle?.value == LocalSessionLifecycle.ACTIVE)

    fun paymentCorrectionWorkspaceCompatible(): Boolean = state.value.isPaymentCorrectionUiWorkspace() &&
        originalAction?.let { it == LocalAction.PAYMENT_CLASSIFICATION_CORRECT } != false &&
        frozen?.let { it.action == LocalAction.PAYMENT_CLASSIFICATION_CORRECT && it.formVersion == 1 &&
            it.lineField == null && it.lines.isEmpty() } != false

    fun invoiceCaptureWorkspaceCompatible(): Boolean = !invoiceCaptureOnly || invoiceWorkspaceCompatible()

    fun invoiceMatchingWorkspaceCompatible(): Boolean = !invoiceMatchingOnly || invoiceWorkspaceCompatible()

    fun invoiceWorkspaceCompatible(): Boolean {
        val action = invoiceAction ?: return true
        val current = state.value
        return current.workflow == LocalWorkflow.PURCHASING && current.purchasingQuery.section in invoiceUiSections &&
            current.reference?.isInvoiceUiSelection(current.purchasingQuery.section) != false &&
            current.form?.isInvoiceUiDraft(action, current.reference) != false &&
            current.formInput?.isInvoiceUiInput(action, current.reference) != false &&
            current.referencePicker?.source?.kind?.let { action == LocalAction.SUPPLIER_INVOICE_CAPTURE &&
                it in setOf(LocalRecordKind.SUPPLIER, LocalRecordKind.SKU) } != false &&
            current.page?.isInvoiceUiPage(current.purchasingQuery.section, action, current.reference) != false &&
            current.pending.all { it.isInvoiceUiPending(action) } &&
            current.operation?.isInvoiceUiResult(action) != false && current.actionApproval == null && !current.purchasingDocuments.hasDraft &&
            current.openingCounts == null && current.priceDraft == null && current.pricePreview == null && current.foodPricePreview == null &&
            originalAction?.let { it == action } != false &&
            frozen?.let { it.isInvoiceUiIntent(action) && it.operationId == current.operationId && it.action == originalAction } != false &&
            retainedPhysicalRetail == null && retainedPhysicalSupplier == null
    }

    fun foodCheckWorkspaceCompatible(): Boolean = !foodChecksOnly || state.value.isFoodCheckUiWorkspace() &&
        originalAction?.let { it in foodCheckUiActions } != false &&
        frozen?.let { it.isFoodCheckUiIntent() && it.operationId == state.value.operationId && it.action == originalAction } != false &&
        state.value.operation?.isFoodCheckUiResult(originalAction, frozen) != false

    fun foodCashFinancialWorkspaceCompatible(): Boolean = !foodCashFinancialOnly ||
        state.value.isFoodCashFinancialUiWorkspace() && originalAction?.let { it in foodCashFinancialUiActions } != false &&
            frozen?.let { it.isFoodCashFinancialUiIntent() && it.operationId == state.value.operationId && it.action == originalAction } != false &&
            state.value.operation?.isFoodCashFinancialUiResult(originalAction, frozen) != false &&
            (state.value.operation as? LocalOperationResult.Recorded)?.foodCashFinancial?.originalActorId?.let {
                it == session.display.actorId
            } != false && retainedPhysicalRetail == null && retainedPhysicalSupplier == null

    fun foodTableAssignmentWorkspaceCompatible(): Boolean = !foodTableAssignmentsOnly ||
        state.value.isFoodTableAssignmentUiWorkspace() && originalAction?.let { it == LocalAction.FOOD_ASSIGN_TABLE } != false &&
            frozen?.let { it.isFoodTableAssignmentUiIntent() && it.operationId == state.value.operationId && it.action == originalAction } != false &&
            state.value.operation?.let { result ->
                val operationId = when (result) {
                    is LocalOperationResult.Pending -> result.operation.operationId
                    is LocalOperationResult.Recorded -> result.operationId
                    is LocalOperationResult.Unresolved -> result.operationId
                }
                operationId == state.value.operationId && result.isFoodTableAssignmentUiResult(originalAction, frozen) &&
                    (result !is LocalOperationResult.Recorded || originalAction == LocalAction.FOOD_ASSIGN_TABLE)
            } != false

    private fun customerReceiptWorkspaceCompatible(): Boolean = !customerReceiptsOnly ||
        state.value.isCustomerReceiptUiWorkspace() &&
            originalAction?.let { it == LocalAction.RETAIL_CUSTOMER_RECEIPT } != false &&
            frozen?.let { it.action == LocalAction.RETAIL_CUSTOMER_RECEIPT && it.formVersion in 1..2 &&
                it.reference?.isCustomerReceiptUiSelection() == true && it.arrivalEvidence == null &&
                (if (it.formVersion == 1) it.lineField == null && it.lines.isEmpty() else it.lineField == LocalField.LINES) } != false

    private fun acceptsEntry(token: NavigationToken): Boolean = invoiceWorkspaceCompatible() && foodCheckWorkspaceCompatible() && foodCashFinancialWorkspaceCompatible() && foodTableAssignmentWorkspaceCompatible() &&
        customerReceiptWorkspaceCompatible() && (!paymentCorrectionsOnly || paymentCorrectionWorkspaceCompatible()) &&
        current() && runtime.navigator.isCurrent(token) &&
        token.rootGeneration == owner.rootGeneration && token.entryId == owner.entryId && token.area == owner.area

    private fun accepts(token: NavigationToken): Boolean = state.value.actionApproval == null && !state.value.attachmentReviewRequired && !configurationOwnsDraft && !retailPhysical.blocksParentWork && !supplierPhysical.blocksParentWork &&
        !state.value.purchasingDocuments.hasDraft && acceptsEntry(token)

    // A scanner/IME may emit another edit before Compose renders our own new DIRTY guard.
    // Permit only that immediately preceding guard revision for EPHEMERAL edits, never dispatch.
    private fun sameEntryBeforeGuard(token: NavigationToken): Boolean = invoiceWorkspaceCompatible() && foodCheckWorkspaceCompatible() && foodCashFinancialWorkspaceCompatible() && foodTableAssignmentWorkspaceCompatible() &&
        customerReceiptWorkspaceCompatible() && (!paymentCorrectionsOnly || paymentCorrectionWorkspaceCompatible()) && current() &&
        token.rootGeneration == owner.rootGeneration && token.entryId == owner.entryId && token.area == owner.area &&
        token.revision == editorBeforeGuard && runtime.navigator.state.value.revision == editorAfterGuard

    private fun acceptsEdit(token: NavigationToken): Boolean = accepts(token) || state.value.actionApproval == null && !state.value.attachmentReviewRequired && !configurationOwnsDraft && !retailPhysical.blocksParentWork && !supplierPhysical.blocksParentWork &&
        !state.value.purchasingDocuments.hasDraft && sameEntryBeforeGuard(token)

    fun changeSearch(value: String, token: NavigationToken) {
        if (state.value.workflow == LocalWorkflow.REPORTS || state.value.workflow.usesFoodQueries) return
        if (state.value.workflow == LocalWorkflow.INVENTORY && state.value.inventorySection == LocalInventorySection.SETUP) return
        if (accepts(token) && !state.value.working && state.value.form == null && !state.value.formFrozen && value.length <= 256) {
            supplierPhysical.deactivate()
            val wasManagerContext = state.value.managerOrderContext
            val receiptSelectorChanged = value != state.value.search &&
                (value.startsWith("customer-receipt:") || value.startsWith("customer-receipt-event:") ||
                    state.value.search.startsWith("customer-receipt:") || state.value.search.startsWith("customer-receipt-event:"))
            val managerSelectorChanged = value != state.value.search &&
                (wasManagerContext || state.value.workflow.isManagerOrderContext(value))
            val transferSelectorChanged = session.display.workspaceMode == LocalSessionWorkspaceMode.STOCK_TRANSFERS_ONLY &&
                value != state.value.search && (value.startsWith("sku:") || value.startsWith("transfer:"))
            val correctionSelectorChanged = paymentCorrectionsOnly && value != state.value.search
            if (state.value.workflow == LocalWorkflow.CASH || managerSelectorChanged || receiptSelectorChanged || correctionSelectorChanged) {
                retailPhysical.deactivate()
            }
            mutableState.value = state.value.copy(search = value,
                reference = if (state.value.workflow == LocalWorkflow.CASH || managerSelectorChanged || receiptSelectorChanged || transferSelectorChanged || correctionSelectorChanged)
                    null else state.value.reference,
                // A page belongs to the exact accepted selector, not the text being edited.
                // Keep saved operation identity and uncertainty; retire only read capabilities.
                page = state.value.page.takeUnless { managerSelectorChanged || receiptSelectorChanged || transferSelectorChanged || correctionSelectorChanged },
                priceDraft = state.value.priceDraft.takeUnless { managerSelectorChanged || receiptSelectorChanged },
                pricePreview = state.value.pricePreview.takeUnless { managerSelectorChanged || receiptSelectorChanged },
                readProblem = state.value.readProblem.takeUnless { managerSelectorChanged || receiptSelectorChanged || correctionSelectorChanged })
            if (wasManagerContext && !state.value.managerOrderContext)
                activateRetailPhysical(state.value.workflow, state.value.reference, state.value.foodQuery)
        }
    }

    fun selectWorkflow(workflow: LocalWorkflow, token: NavigationToken) {
        if (invoiceOnly && workflow != LocalWorkflow.PURCHASING) return
        if ((foodChecksOnly || foodCashFinancialOnly) && workflow != LocalWorkflow.FOOD) return
        if (foodTableAssignmentsOnly && workflow != LocalWorkflow.FOOD) return
        if (customerReceiptsOnly && workflow !in customerReceiptUiWorkflows) return
        if (paymentCorrectionsOnly && workflow !in paymentCorrectionUiWorkflows) return
        if (session.display.workspaceMode == LocalSessionWorkspaceMode.RETAIL_ORDERS_ONLY &&
            workflow !in setOf(LocalWorkflow.CHECKOUT, LocalWorkflow.ORDERS)) return
        if (session.display.workspaceMode == LocalSessionWorkspaceMode.STOCK_TRANSFERS_ONLY && workflow != LocalWorkflow.INVENTORY) return
        if (session.display.workspaceMode == LocalSessionWorkspaceMode.KITCHEN_ONLY && workflow != LocalWorkflow.KITCHEN) return
        if (!accepts(token) || state.value.working || state.value.form != null || state.value.formFrozen || workflow == state.value.workflow) return
        supplierPhysical.deactivate()
        retailPhysical.deactivate()
        clearPurchasingDocuments()
        mutableState.value = state.value.copy(workflow = workflow, search = "", reference = null, operation = null, operationId = null,
            page = null, readProblem = null, originalLookupInput = "", originalLookupProblem = null,
            priceDraft = null, pricePreview = null, foodPricePreview = null, foodPricePreviewProblem = null, branchReportQuery = LocalBranchReportQuery(LocalBranchReportSection.CASH_SESSIONS),
            foodQuery = workflow.defaultFoodQuery(), kitchenBoardOrigin = null, foodUnavailable = null, purchasingQuery = LocalPurchasingUiQuery(), returnQuery = defaultReturnQuery(),
            cashQuery = LocalCashUiQuery(), inventorySection = LocalInventorySection.QUANTITIES)
        activateRetailPhysical(workflow, null, state.value.foodQuery)
        activateSupplierPhysical()
        load()
    }

    fun refresh(token: NavigationToken, nextPage: Boolean = false) {
        if (state.value.workflow.usesFoodQueries) {
            refreshFood(token, nextPage)
            return
        }
        if (state.value.workflow == LocalWorkflow.REPORTS) {
            if (nextPage) nextBranchReportPage(token) else restartBranchReport(token)
            return
        }
        if (!accepts(token) || state.value.working || state.value.form != null || state.value.formFrozen) return
        val cursor = if (nextPage) state.value.page?.nextCursor ?: return else null
        load(cursor)
    }

    fun selectPurchasing(query: LocalPurchasingUiQuery, token: NavigationToken) {
        if (invoiceOnly && query.section !in invoiceUiSections) return
        if (state.value.workflow != LocalWorkflow.PURCHASING || !accepts(token) || state.value.working ||
            state.value.form != null || state.value.formFrozen) return
        val changedSection = query.section != state.value.purchasingQuery.section
        supplierPhysical.deactivate()
        retailPhysical.deactivate()
        if (changedSection) clearPurchasingDocuments()
        mutableState.value = state.value.copy(purchasingQuery = query,
            search = if (changedSection) "" else state.value.search, reference = if (changedSection) null else state.value.reference,
            page = state.value.page.takeUnless { invoiceOnly && changedSection })
        activateSupplierPhysical()
        // Keep the pending/result panel and its original operation identity. Changing a read
        // selection never acknowledges, reconstructs or submits a durable command.
        load()
    }

    private fun documentaryCurrent(): Boolean = current() && session.lifecycle?.value == LocalSessionLifecycle.ACTIVE &&
        state.value.workflow == LocalWorkflow.PURCHASING && state.value.purchasingQuery.section == LocalPurchasingSection.DOCUMENTS

    fun purchasingDocumentDisplayCurrent(token: NavigationToken): Boolean = documentaryCurrent() && acceptsEntry(token)

    fun purchasingDocumentFilesBound(): Boolean = runtime.dependencies.documentFiles.bound

    private fun acceptsDocumentary(token: NavigationToken, editing: Boolean = false): Boolean = documentaryCurrent() &&
        state.value.actionApproval == null && !state.value.attachmentReviewRequired && !configurationOwnsDraft && !retailPhysical.blocksParentWork &&
        !supplierPhysical.blocksParentWork && !state.value.working && state.value.form == null && state.value.formInput == null &&
        (acceptsEntry(token) || editing && sameEntryBeforeGuard(token))

    fun purchasingDocumentReady(token: NavigationToken): Boolean = acceptsDocumentary(token)

    fun openPurchasingDocument(kind: LocalPurchasingDocumentKind, token: NavigationToken) {
        if (!acceptsDocumentary(token) || state.value.purchasingDocuments.hasDraft) return
        val documents = state.value.purchasingDocuments
        mutableState.value = state.value.copy(purchasingDocuments = documents.copy(
            draft = LocalPurchasingDocumentDraft(kind), selectedFile = null, inputIssue = null,
            fileProblem = null, importCancelled = false, download = null))
        syncGuard()
    }

    fun editPurchasingDocument(field: LocalPurchasingDocumentField, value: String, token: NavigationToken) {
        if (!acceptsDocumentary(token, editing = true) || value.length > field.maximumLength) return
        val documents = state.value.purchasingDocuments
        val draft = documents.draft?.takeUnless { it.saveAttempted } ?: return
        mutableState.value = state.value.copy(purchasingDocuments = documents.copy(
            draft = draft.edit(field, value), inputIssue = null))
        syncGuard()
    }

    fun newPurchasingDocumentId(token: NavigationToken) {
        if (!acceptsDocumentary(token) || state.value.purchasingDocuments.draft?.documentId?.isEmpty() != true) return
        editPurchasingDocument(LocalPurchasingDocumentField.DOCUMENT_ID, DomainId.random().value, token)
    }

    fun choosePurchasingDocumentPurpose(purpose: PurchasingEvidencePurposeV1, token: NavigationToken) {
        if (!acceptsDocumentary(token, editing = true)) return
        val documents = state.value.purchasingDocuments
        val draft = documents.draft?.takeUnless { it.saveAttempted } ?: return
        mutableState.value = state.value.copy(purchasingDocuments = documents.copy(draft = draft.copy(purpose = purpose), inputIssue = null))
        syncGuard()
    }

    fun importPurchasingDocument(token: NavigationToken) {
        if (!acceptsDocumentary(token) || state.value.purchasingDocuments.draft?.saveAttempted != false) return
        mutableState.value = state.value.copy(purchasingDocuments = state.value.purchasingDocuments.copy(fileProblem = null, importCancelled = false))
        run(LocalWorkBusy.DOCUMENT_IMPORT) { ticket ->
            val chosen = runtime.dependencies.documentFiles.importOriginal { current(ticket) && documentaryCurrent() }
            var adopted = false
            try {
                currentCoroutineContext().ensureActive()
                if (!current(ticket) || !documentaryCurrent()) return@run
                val documents = state.value.purchasingDocuments
                when (chosen) {
                    is LocalPurchasingDocumentFileSelection.Selected -> {
                        val file = LocalPurchasingDocumentSelectedFile(chosen.originalFileName, chosen.media, chosen.byteCount)
                        purchasingDocumentInput?.destroy()
                        purchasingDocumentInput = chosen
                        adopted = true
                        mutableState.value = state.value.copy(purchasingDocuments = documents.copy(selectedFile = file,
                            inputIssue = null, fileProblem = null, importCancelled = false))
                    }
                    LocalPurchasingDocumentFileSelection.Cancelled -> mutableState.value = state.value.copy(
                        purchasingDocuments = documents.copy(importCancelled = true))
                    is LocalPurchasingDocumentFileSelection.Unavailable -> mutableState.value = state.value.copy(
                        purchasingDocuments = documents.copy(fileProblem = chosen.problem))
                }
            } finally { if (chosen is LocalPurchasingDocumentFileSelection.Selected && !adopted) chosen.destroy() }
        }
    }

    fun savePurchasingDocument(token: NavigationToken) {
        if (!acceptsDocumentary(token)) return
        val documents = state.value.purchasingDocuments
        val draft = documents.draft ?: return
        val parsed = draft.capture(documents.selectedFile)
        if (parsed is LocalPurchasingDocumentCaptureInput.Invalid) {
            mutableState.value = state.value.copy(purchasingDocuments = documents.copy(inputIssue = parsed.issue))
            return
        }
        val capture = (parsed as LocalPurchasingDocumentCaptureInput.Valid).capture
        if (!draft.saveAttempted && (documents.sources as? LocalPurchasingDocumentSourcesResult.Available)?.sources
                ?.any { it.documentId == capture.documentId } == true) {
            mutableState.value = state.value.copy(purchasingDocuments = documents.copy(inputIssue = LocalPurchasingDocumentInputIssue(
                LocalPurchasingDocumentField.DOCUMENT_ID, LocalText(
                    "This ID already has a saved private source. Use its Transfer / resume control. A distinct original needs an explicitly chosen new ID.",
                    "لهذا المعرف مصدر خاص محفوظ بالفعل. استخدم أداة النقل أو الاستئناف الخاصة به. يحتاج الأصل المختلف إلى اختيار صريح لمعرف جديد."))))
            return
        }
        val original = purchasingDocumentInput ?: return
        mutableState.value = state.value.copy(purchasingDocuments = documents.copy(draft = draft.copy(saveAttempted = true),
            saveResult = null, inputIssue = null, workingDocumentId = capture.documentId))
        run(LocalWorkBusy.DOCUMENT_SAVE) { ticket ->
            val bytes = original.copyBytes()
            val result = try { gateway.savePurchasingDocument(capture, bytes) } finally { bytes.fill(0) }
            currentCoroutineContext().ensureActive()
            if (!current(ticket) || !documentaryCurrent()) return@run
            val actual = when (result) {
                is LocalPurchasingDocumentSourceResult.Saved -> result.takeIf { it.documentId == capture.documentId }
                is LocalPurchasingDocumentSourceResult.Unavailable -> result.takeIf { it.documentId == capture.documentId }
            } ?: LocalPurchasingDocumentSourceResult.Unavailable(LocalServiceProblem.STORAGE_UNCERTAIN, capture.documentId)
            if (actual is LocalPurchasingDocumentSourceResult.Saved) {
                purchasingDocumentInput?.destroy()
                purchasingDocumentInput = null
                mutableState.value = state.value.copy(purchasingDocuments = state.value.purchasingDocuments.copy(
                    draft = null, selectedFile = null, inputIssue = null, fileProblem = null, saveResult = actual))
            } else {
                mutableState.value = state.value.copy(purchasingDocuments = state.value.purchasingDocuments.copy(saveResult = actual))
                permissionFailure((actual as LocalPurchasingDocumentSourceResult.Unavailable).problem)
            }
            if (current(ticket) && documentaryCurrent()) readPurchasingDocumentSources(ticket)
        }
    }

    /** Discards only current private input; any durable saved original and transfer remain intact. */
    fun discardPurchasingDocument(token: NavigationToken) {
        if (!acceptsDocumentary(token)) return
        purchasingDocumentInput?.destroy()
        purchasingDocumentInput = null
        mutableState.value = state.value.copy(purchasingDocuments = state.value.purchasingDocuments.copy(
            draft = null, selectedFile = null, inputIssue = null, fileProblem = null, importCancelled = false, completionRetry = null))
        syncGuard()
    }

    fun refreshPurchasingDocumentSources(token: NavigationToken) {
        if (!acceptsDocumentary(token)) return
        run(LocalWorkBusy.DOCUMENT_SOURCES) { ticket -> readPurchasingDocumentSources(ticket) }
    }

    private suspend fun readPurchasingDocumentSources(ticket: RequestOwner) {
        val epoch = purchasingDocumentEpoch
        val result = try { gateway.purchasingDocumentSources() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { LocalPurchasingDocumentSourcesResult.Unavailable(LocalServiceProblem.STORAGE_UNCERTAIN) }
        currentCoroutineContext().ensureActive()
        if (!current(ticket) || !documentaryCurrent() || epoch != purchasingDocumentEpoch) return
        val actual = if (result is LocalPurchasingDocumentSourcesResult.Available) {
            if (result.sources.size > LOCAL_PURCHASING_DOCUMENT_SOURCE_LIMIT ||
                result.sources.map { it.documentId }.distinct().size != result.sources.size || result.sources.any {
                    it.documentId == it.parentOriginalId || !validLocalPurchasingDocumentFileName(it.originalFileName) ||
                        it.originalByteLength !in 1..if (it.parentOriginalId == null) MAX_LOCAL_PURCHASING_DOCUMENT_FILE_BYTES else 256 * 1024
                }) LocalPurchasingDocumentSourcesResult.Unavailable(LocalServiceProblem.STORAGE_UNCERTAIN)
            else LocalPurchasingDocumentSourcesResult.Available(result.sources.toList())
        } else result
        val documents = state.value.purchasingDocuments
        mutableState.value = state.value.copy(purchasingDocuments = documents.copy(sources = actual,
            transfers = if (actual is LocalPurchasingDocumentSourcesResult.Available)
                documents.transfers.filterKeys { id -> actual.sources.any { it.documentId == id } } else documents.transfers,
            completionOriginalRefusals = if (actual is LocalPurchasingDocumentSourcesResult.Available)
                documents.completionOriginalRefusals.filterKeys { id -> actual.sources.any { it.documentId == id } } else documents.completionOriginalRefusals))
        if (actual is LocalPurchasingDocumentSourcesResult.Unavailable) permissionFailure(actual.problem)
    }

    fun transferPurchasingDocument(documentId: DomainId, token: NavigationToken) {
        if (!acceptsDocumentary(token) || state.value.purchasingDocuments.hasDraft ||
            (state.value.purchasingDocuments.sources as? LocalPurchasingDocumentSourcesResult.Available)?.sources
                ?.singleOrNull { it.documentId == documentId } == null) return
        mutableState.value = state.value.copy(purchasingDocuments = state.value.purchasingDocuments.copy(workingDocumentId = documentId,
            completionSaveResult = state.value.purchasingDocuments.completionSaveResult.takeUnless {
                it is LocalPurchasingDocumentSourceResult.Saved && it.documentId == documentId }))
        run(LocalWorkBusy.DOCUMENT_TRANSFER) { ticket ->
            val result = gateway.transferPurchasingDocument(documentId)
            currentCoroutineContext().ensureActive()
            if (!current(ticket) || !documentaryCurrent()) return@run
            val actual = when (result) {
                is LocalPurchasingDocumentTransferResult.Captured -> result.takeIf { it.documentId == documentId }
                is LocalPurchasingDocumentTransferResult.Rejected -> result.takeIf { it.documentId == documentId }
                is LocalPurchasingDocumentTransferResult.Unresolved -> result.takeIf { it.documentId == documentId }
            } ?: LocalPurchasingDocumentTransferResult.Unresolved(documentId, null, LocalServiceProblem.STORAGE_UNCERTAIN)
            mutableState.value = state.value.copy(purchasingDocuments = state.value.purchasingDocuments.copy(
                transfers = state.value.purchasingDocuments.transfers + (documentId to actual)))
            if (actual is LocalPurchasingDocumentTransferResult.Unresolved) permissionFailure(actual.problem)
            if (!current(ticket) || !documentaryCurrent()) return@run
            val pending = try { gateway.pending() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { LocalPendingResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
            currentCoroutineContext().ensureActive()
            if (!current(ticket)) return@run
            applyPending(pending, null)
            if (!current(ticket) || !documentaryCurrent()) return@run
            if (actual is LocalPurchasingDocumentTransferResult.Captured) {
                val query = currentQuery()
                val page = try { gateway.read(query) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalReadResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
                currentCoroutineContext().ensureActive()
                if (!current(ticket)) return@run
                when (page) {
                    is LocalReadResult.Available -> applyPage(query, page.page)
                    is LocalReadResult.Unavailable -> readUnavailable(page.problem)
                }
            }
            if (current(ticket) && documentaryCurrent()) readPurchasingDocumentSources(ticket)
        }
    }

    fun reviewPurchasingDocumentCompletion(documentId: DomainId, token: NavigationToken) {
        if (!acceptsDocumentary(token) || state.value.purchasingDocuments.hasDraft) return
        val documents = state.value.purchasingDocuments
        val source = (documents.sources as? LocalPurchasingDocumentSourcesResult.Available)?.sources
            ?.singleOrNull { it.documentId == documentId && it.parentOriginalId != null } ?: return
        val refusal = (documents.transfers[source.documentId] as? LocalPurchasingDocumentTransferResult.Rejected)
            ?.takeIf { it.code == "purchasing.document.stale_revision" } ?: return
        if (documents.completionSaveResult is LocalPurchasingDocumentSourceResult.Saved &&
            documents.completionSaveResult.documentId == documentId) return
        mutableState.value = state.value.copy(purchasingDocuments = documents.copy(
            completionRetry = LocalPurchasingDocumentCompletionRetryDraft(documentId, refusal.operationId),
            completionSaveResult = null, inputIssue = null, download = null,
            completionOriginalRefusals = documents.completionOriginalRefusals + (documentId to refusal.operationId)))
        syncGuard()
    }

    fun editPurchasingDocumentCompletionRevision(value: String, token: NavigationToken) {
        if (!acceptsDocumentary(token, editing = true) || value.length > 19) return
        val documents = state.value.purchasingDocuments
        val retry = documents.completionRetry?.takeUnless { it.saveAttempted } ?: return
        mutableState.value = state.value.copy(purchasingDocuments = documents.copy(
            completionRetry = retry.copy(parentAttachmentsRevision = value), inputIssue = null))
        syncGuard()
    }

    fun savePurchasingDocumentCompletionRetry(token: NavigationToken) {
        if (!acceptsDocumentary(token)) return
        val documents = state.value.purchasingDocuments
        val retry = documents.completionRetry ?: return
        val revision = parsePurchasingDocumentPositiveRevision(retry.parentAttachmentsRevision)
        if (revision == null) {
            mutableState.value = state.value.copy(purchasingDocuments = documents.copy(inputIssue = LocalPurchasingDocumentInputIssue(
                LocalPurchasingDocumentField.PARENT_ATTACHMENTS_REVISION,
                LocalText("Enter the reviewed current parent attachments revision as a positive whole number.",
                    "أدخل مراجعة مرفقات الأصل الأب الحالية التي راجعتها كعدد صحيح موجب."))))
            return
        }
        mutableState.value = state.value.copy(purchasingDocuments = documents.copy(completionRetry = retry.copy(saveAttempted = true),
            completionSaveResult = null, inputIssue = null, workingDocumentId = retry.documentId))
        run(LocalWorkBusy.DOCUMENT_COMPLETION_RETRY) { ticket ->
            val result = gateway.retryPurchasingDocumentCompletion(retry.documentId, retry.refusedCompleteOperationId, revision)
            currentCoroutineContext().ensureActive()
            if (!current(ticket) || !documentaryCurrent()) return@run
            val actual = when (result) {
                is LocalPurchasingDocumentSourceResult.Saved -> result.takeIf { it.documentId == retry.documentId }
                is LocalPurchasingDocumentSourceResult.Unavailable -> result.takeIf { it.documentId == retry.documentId }
            } ?: LocalPurchasingDocumentSourceResult.Unavailable(LocalServiceProblem.STORAGE_UNCERTAIN, retry.documentId)
            mutableState.value = state.value.copy(purchasingDocuments = state.value.purchasingDocuments.copy(completionSaveResult = actual,
                completionRetry = state.value.purchasingDocuments.completionRetry.takeUnless { actual is LocalPurchasingDocumentSourceResult.Saved }))
            if (actual is LocalPurchasingDocumentSourceResult.Unavailable) permissionFailure(actual.problem)
            if (current(ticket) && documentaryCurrent()) readPurchasingDocumentSources(ticket)
        }
    }

    /** Only actual current detail records supply primary/attachment download identities. */
    fun purchasingDocumentDownloadTargets(token: NavigationToken): List<LocalRecord> {
        if (!purchasingDocumentDisplayCurrent(token)) return emptyList()
        val selected = state.value.reference?.takeIf { it.kind == LocalRecordKind.PURCHASING_DOCUMENT } ?: return emptyList()
        val records = state.value.page?.records ?: return emptyList()
        if (records.singleOrNull { it.reference == selected && it.selectable } == null) return emptyList()
        return records.filter { it.reference == selected || it.reference.kind == LocalRecordKind.PURCHASING_DOCUMENT_ATTACHMENT &&
            it.reference.parentId == selected.id }
    }

    fun downloadPurchasingDocument(documentId: DomainId, token: NavigationToken) {
        if (!acceptsDocumentary(token) || state.value.purchasingDocuments.hasDraft ||
            purchasingDocumentDownloadTargets(token).singleOrNull { it.reference.id == documentId } == null) return
        val selected = state.value.reference
        val page = state.value.page
        mutableState.value = state.value.copy(purchasingDocuments = state.value.purchasingDocuments.copy(download = null, workingDocumentId = documentId))
        run(LocalWorkBusy.DOCUMENT_DOWNLOAD) { ticket ->
            val result = gateway.downloadPurchasingDocument(documentId, runtime.dependencies.documentFiles) {
                current(ticket) && documentaryCurrent() && state.value.reference == selected && state.value.page === page
            }
            currentCoroutineContext().ensureActive()
            if (!current(ticket) || !documentaryCurrent()) return@run
            val actual = when (result) {
                is LocalPurchasingDocumentDownloadResult.Saved -> result.takeIf { it.documentId == documentId }
                is LocalPurchasingDocumentDownloadResult.Cancelled -> result.takeIf { it.documentId == documentId }
                is LocalPurchasingDocumentDownloadResult.Unavailable -> result.takeIf { it.documentId == documentId }
            } ?: LocalPurchasingDocumentDownloadResult.Unavailable(documentId, LocalServiceProblem.UNAVAILABLE)
            mutableState.value = state.value.copy(purchasingDocuments = state.value.purchasingDocuments.copy(download = actual))
            if (actual is LocalPurchasingDocumentDownloadResult.Unavailable) permissionFailure(actual.problem)
        }
    }

    private fun clearPurchasingDocuments() {
        purchasingDocumentEpoch++
        purchasingDocumentInput?.destroy()
        purchasingDocumentInput = null
        mutableState.value = state.value.copy(purchasingDocuments = LocalPurchasingDocumentPresentation())
    }

    private fun purchasingDocumentWorkFailed(kind: LocalWorkBusy, cancelled: Boolean = false) {
        val documents = state.value.purchasingDocuments
        val id = documents.workingDocumentId
        val changed = when (kind) {
            LocalWorkBusy.DOCUMENT_IMPORT -> if (cancelled) documents.copy(importCancelled = true)
                else documents.copy(fileProblem = LocalPurchasingDocumentFileProblem.IO_FAILED)
            LocalWorkBusy.DOCUMENT_SAVE -> if (id == null || documents.saveResult is LocalPurchasingDocumentSourceResult.Saved) documents
                else documents.copy(saveResult = LocalPurchasingDocumentSourceResult.Unavailable(LocalServiceProblem.STORAGE_UNCERTAIN, id))
            LocalWorkBusy.DOCUMENT_COMPLETION_RETRY -> if (id == null) documents
                else documents.copy(completionSaveResult = LocalPurchasingDocumentSourceResult.Unavailable(LocalServiceProblem.STORAGE_UNCERTAIN, id))
            LocalWorkBusy.DOCUMENT_SOURCES -> documents.copy(sources = LocalPurchasingDocumentSourcesResult.Unavailable(LocalServiceProblem.UNAVAILABLE))
            LocalWorkBusy.DOCUMENT_TRANSFER -> if (id == null || documents.transfers[id] is LocalPurchasingDocumentTransferResult.Captured ||
                    documents.transfers[id] is LocalPurchasingDocumentTransferResult.Rejected && id !in documents.completionOriginalRefusals) documents
                else documents.copy(transfers = documents.transfers + (id to
                    LocalPurchasingDocumentTransferResult.Unresolved(id, null, LocalServiceProblem.STORAGE_UNCERTAIN)))
            LocalWorkBusy.DOCUMENT_DOWNLOAD -> if (id == null) documents
                else documents.copy(download = LocalPurchasingDocumentDownloadResult.Unavailable(id, LocalServiceProblem.UNAVAILABLE))
            else -> documents
        }
        mutableState.value = state.value.copy(purchasingDocuments = changed)
    }

    fun selectCash(query: LocalCashUiQuery, token: NavigationToken) {
        if (state.value.workflow != LocalWorkflow.CASH || !accepts(token) || state.value.working ||
            state.value.form != null || state.value.formFrozen) return
        val changedSection = query.section != state.value.cashQuery.section
        supplierPhysical.deactivate()
        mutableState.value = state.value.copy(cashQuery = query,
            search = if (changedSection) "" else state.value.search, reference = if (changedSection) null else state.value.reference)
        load() // Never acknowledges, changes or dispatches the retained operation.
    }

    fun selectInventory(section: LocalInventorySection, token: NavigationToken) {
        if (session.display.workspaceMode == LocalSessionWorkspaceMode.STOCK_TRANSFERS_ONLY &&
            section !in setOf(LocalInventorySection.QUANTITIES, LocalInventorySection.COSTS)) return
        if (state.value.workflow != LocalWorkflow.INVENTORY || !accepts(token) || state.value.working ||
            state.value.form != null || state.value.formFrozen) return
        val reference = state.value.reference
        val quantityOrCost = setOf(LocalInventorySection.QUANTITIES, LocalInventorySection.COSTS)
        val sku = if (state.value.inventorySection in quantityOrCost && section in quantityOrCost) when (reference?.kind) {
            LocalRecordKind.SKU -> reference.id
            LocalRecordKind.STOCK_CUT -> reference.parentId
            else -> null
        } else null
        val transfer = if (session.display.workspaceMode == LocalSessionWorkspaceMode.STOCK_TRANSFERS_ONLY &&
            state.value.inventorySection in quantityOrCost && section in quantityOrCost) {
            reference?.takeIf { it.kind == LocalRecordKind.TRANSFER } ?: state.value.page?.records?.singleOrNull {
                it.reference.kind == LocalRecordKind.TRANSFER && it.reference.parentId == null && it.reference.revision == null &&
                    state.value.search == "transfer:${it.reference.id.value}"
            }?.reference
        } else null
        supplierPhysical.deactivate()
        mutableState.value = state.value.copy(inventorySection = section, search = "",
            reference = transfer ?: sku?.let { LocalRecordReference(LocalRecordKind.SKU, it) })
        load() // A read selector never initializes stock or acknowledges a prior command.
    }

    fun browseCashSafes(token: NavigationToken) {
        if (state.value.workflow != LocalWorkflow.CASH || !accepts(token) || state.value.working ||
            state.value.form != null || state.value.formFrozen) return
        supplierPhysical.deactivate()
        mutableState.value = state.value.copy(cashQuery = state.value.cashQuery.copy(section = LocalCashSection.MY_CASH, showCounts = false),
            search = "safes", reference = null)
        load()
    }

    fun selectRecord(reference: LocalRecordReference?, token: NavigationToken) {
        if (invoiceOnly && (reference?.isInvoiceUiSelection(state.value.purchasingQuery.section) == false ||
                state.value.operationId != null && !state.value.recordedAcknowledged || frozen != null)) return
        if (customerReceiptsOnly && reference?.isCustomerReceiptUiSelection() == false) return
        if (paymentCorrectionsOnly && reference?.isPaymentCorrectionUiSelection() == false) return
        if (state.value.managerOrderContext) {
            val unresolved = state.value.operation as? LocalOperationResult.Unresolved
            // Clearing read-only navigation never replaces or acknowledges an original.
            // Keep other frozen states and every owner/mutation guard unchanged.
            val frozenClear = unresolved != null && unresolved.operationId == state.value.operationId
            if (reference != null || !accepts(token) || state.value.working || state.value.form != null ||
                state.value.formFrozen && !frozenClear) return
            supplierPhysical.deactivate()
            retailPhysical.deactivate()
            mutableState.value = state.value.copy(reference = null)
            load() // Keep review mode and original recovery identity; never switch to an owner route.
            return
        }
        if (state.value.workflow == LocalWorkflow.REPORTS || state.value.workflow.usesFoodQueries) return
        if (!accepts(token) || state.value.working || state.value.form != null || state.value.formFrozen) return
        if (reference != null && state.value.page?.records?.none { it.reference == reference && it.selectable } != false) return
        supplierPhysical.deactivate()
        retailPhysical.deactivate()
        val returnSection = if (reference?.kind in setOf(LocalRecordKind.SALE, LocalRecordKind.RETURN_RECEIPT_PORTION))
            LocalReturnSection.RECEIPTS else if (reference?.kind in setOf(LocalRecordKind.RETURN_CASE, LocalRecordKind.RETURN_CASE_HISTORY,
                LocalRecordKind.RETURN_FINANCE, LocalRecordKind.RETURN_STOCK))
            LocalReturnSection.CASES else state.value.returnQuery.section
        mutableState.value = state.value.copy(reference = reference, operation = null, operationId = null, priceDraft = null, pricePreview = null, foodPricePreview = null, foodPricePreviewProblem = null,
            page = state.value.page.takeUnless { documentaryOnly || invoiceMatchingOnly },
            returnQuery = state.value.returnQuery.copy(section = returnSection),
            search = if (documentaryOnly || state.value.workflow == LocalWorkflow.CASH ||
                session.display.workspaceMode == LocalSessionWorkspaceMode.STOCK_TRANSFERS_ONLY) "" else state.value.search,
            cashQuery = if (state.value.workflow == LocalWorkflow.CASH) state.value.cashQuery.copy(section = when (reference?.kind) {
                LocalRecordKind.CASH_SESSION -> LocalCashSection.OWN_SESSION
                LocalRecordKind.TRANSFER -> LocalCashSection.TRANSFER
                else -> LocalCashSection.MY_CASH
            }, showCounts = false) else state.value.cashQuery)
        activateRetailPhysical(state.value.workflow, reference, state.value.foodQuery)
        activateSupplierPhysical()
        load()
    }

    fun selectReturns(query: LocalReturnUiQuery, token: NavigationToken) {
        if (customerReceiptsOnly && query.section != LocalReturnSection.RECEIPTS) return
        if (state.value.workflow != LocalWorkflow.RETURNS || !accepts(token) || state.value.working ||
            state.value.form != null || state.value.formFrozen) return
        supplierPhysical.deactivate()
        retailPhysical.deactivate()
        mutableState.value = state.value.copy(returnQuery = query, search = "", reference = null)
        activateRetailPhysical(state.value.workflow, null, state.value.foodQuery)
        activateSupplierPhysical()
        // A read selection never acknowledges or replaces an original retained operation.
        load()
    }

    /** Only a gateway-validated progress target opens this existing collection/finalization page. */
    fun openRetailCollection(token: NavigationToken) {
        if (nonphysicalOnly) return
        if (state.value.managerOrderContext || !accepts(token) || state.value.working || state.value.form != null || state.value.formFrozen ||
            state.value.workflow !in retailWorkflows || state.value.workflow == LocalWorkflow.RETURNS) return
        val id = retailPhysical.collectionToOpen(token) ?: return
        val food = retailPhysical.collectionSubjectToOpen(token) == LocalPaymentSubject.FOOD
        val reference = if (food) null else LocalRecordReference(LocalRecordKind.COLLECTION, id)
        val workflow = if (food) LocalWorkflow.FOOD else if (state.value.workflow == LocalWorkflow.EXCEPTIONS) LocalWorkflow.ORDERS else state.value.workflow
        supplierPhysical.deactivate()
        retailPhysical.deactivate()
        mutableState.value = state.value.copy(workflow = workflow, reference = reference, search = "", operationId = null, operation = null,
            page = null, readProblem = null, foodUnavailable = null,
            foodQuery = if (food) LocalFoodUiQuery.Collection(id) else state.value.foodQuery, priceDraft = null, pricePreview = null)
        activateRetailPhysical(workflow, reference, state.value.foodQuery)
        activateSupplierPhysical()
        load()
    }

    /** Read selectors share this controller's actor, cancellation and leave-guard lifetime. */
    fun selectFood(query: LocalFoodUiQuery, token: NavigationToken) {
        if (foodCashFinancialOnly && !query.isFoodCashFinancialUiQuery()) return
        if (foodChecksOnly && !query.isFoodCheckUiQuery()) return
        if (foodTableAssignmentsOnly && !query.isFoodTableAssignmentUiQuery()) return
        if (foodTableAssignmentsOnly && !matchesTableAssignmentSelection(query)) return
        if (foodChecksOnly && query is LocalFoodUiQuery.Menu && query.orderId != null) {
            val selected = state.value.foodQuery as? LocalFoodUiQuery.Menu
            val check = state.value.page?.food?.takeIf { it.query == state.value.foodQuery }?.body as? LocalFoodUiBody.Check
            val sameMenuTarget = selected?.let { it.orderId == query.orderId &&
                it.expectedOrderStorageRevision == query.expectedOrderStorageRevision } == true
            val currentCheckTarget = check?.foodCheckAddMenu()?.let { it.orderId == query.orderId &&
                it.expectedOrderStorageRevision == query.expectedOrderStorageRevision } == true
            if (!sameMenuTarget && !currentCheckTarget) return
        }
        // A renewed station-only account may lose catalog discovery but retain a station
        // grant. An explicit selector can request a fresh protected attachment review; it
        // cannot adopt old page metadata, replace preserved input or authorize an action.
        val reviewingKitchen = state.value.workflow == LocalWorkflow.KITCHEN &&
            state.value.attachmentReviewRequired && state.value.formInput == null
        // A floor-only employee may no longer read MyChecks after renewal. Explicitly
        // opening the directory can obtain a new protected cut, never an order grant.
        val reviewingFloorDirectory = state.value.workflow == LocalWorkflow.FOOD && query is LocalFoodUiQuery.FloorDirectory &&
            query.assignment == null && state.value.attachmentReviewRequired && state.value.formInput == null
        val reviewingFinancial = foodCashFinancialOnly && state.value.attachmentReviewRequired && state.value.formInput == null
        val reviewingSelector = reviewingKitchen || reviewingFloorDirectory || reviewingFinancial
        val accepted = if (reviewingSelector) acceptsEntry(token) && !configurationOwnsDraft &&
            !retailPhysical.blocksParentWork && !supplierPhysical.blocksParentWork else accepts(token)
        if (!query.canAppearIn(state.value.workflow) || !accepted || state.value.working ||
            state.value.form != null || state.value.formFrozen) return
        val boardOrigin = if (state.value.workflow == LocalWorkflow.KITCHEN) {
            val origin = state.value.kitchenBoardOrigin ?: (state.value.foodQuery as? LocalFoodUiQuery.StationTasks)
            origin?.takeIf { when (query) {
                is LocalFoodUiQuery.Task -> query.stationId == it.stationId
                is LocalFoodUiQuery.StationTasks -> state.value.kitchenBoardOrigin != null && query == it
                else -> false
            } }
        } else null
        supplierPhysical.deactivate()
        retailPhysical.deactivate()
        // Retire the accepted private page in the same emission as its selector. A collector
        // must not observe an old page under the new selection before load() clears it.
        mutableState.value = state.value.copy(foodQuery = query, kitchenBoardOrigin = boardOrigin, page = null, readProblem = null,
            foodUnavailable = null, search = "", reference = null)
        activateRetailPhysical(state.value.workflow, null, query)
        activateSupplierPhysical()
        // Attachment review still checks pending work and the current authorized source.
        // Ordinary selection retains the common operation/result panel without mutation.
        if (reviewingSelector) reviewAttachment(runtime.navigator.token()) else load()
    }

    /** Refusal-only source matching; selecting or rereading never creates an assignment. */
    private fun matchesTableAssignmentSelection(query: LocalFoodUiQuery): Boolean {
        val selected = state.value.foodQuery
        val source = state.value.page?.food?.takeIf { it.query == selected }
        val assignment = (source?.body as? LocalFoodUiBody.TableAssignment)?.target ?: selected.tableAssignmentTarget()
        if (query is LocalFoodUiQuery.TableAssignment) {
            val check = (source?.body as? LocalFoodUiBody.Check)?.check
            return check?.let { it.orderId == query.orderId && it.orderStorageRevision == query.expectedOrderStorageRevision } == true ||
                assignment?.let { it.orderId == query.orderId && it.expectedOrderStorageRevision == query.expectedOrderStorageRevision } == true ||
                query == selected
        }
        val target = query.tableAssignmentTarget() ?: return true
        return target == assignment
    }

    /** Menu paging retains the exact selected Add target; browsing never supplies an owner grant. */
    fun openFoodMenu(token: NavigationToken) {
        if (foodTableAssignmentsOnly || foodCashFinancialOnly) return
        val query = if (foodChecksOnly) {
            val selected = state.value.foodQuery as? LocalFoodUiQuery.Menu
            selected?.copy(after = null) ?: (state.value.page?.food?.takeIf { it.query == state.value.foodQuery }
                ?.body as? LocalFoodUiBody.Check)?.foodCheckAddMenu() ?: LocalFoodUiQuery.Menu()
        } else LocalFoodUiQuery.Menu()
        selectFood(query, token)
    }

    /** The displayed row chooses a fresh read, not a command or a new expected revision. */
    fun chooseKitchenTask(selected: LocalFoodUiTask, action: LocalAction, token: NavigationToken) {
        if (state.value.workflow != LocalWorkflow.KITCHEN || !accepts(token) || !state.value.canStart) return
        val board = state.value.foodQuery as? LocalFoodUiQuery.StationTasks ?: return
        val source = state.value.page?.food ?: return
        val rows = source.body as? LocalFoodUiBody.Tasks ?: return
        if (source.query != board || selected.stationId != board.stationId || action != selected.kitchenBoardAction() ||
            rows.tasks.singleOrNull { it.matchesKitchenBoardSelection(selected) } == null) return
        val target = LocalFoodUiQuery.Task(selected.stationId, selected.taskId)
        val query = LocalWorkflowQuery(LocalWorkflow.KITCHEN, food = target)
        mutableState.value = state.value.copy(foodQuery = target, kitchenBoardOrigin = board, page = null,
            readProblem = null, foodUnavailable = null, search = "", reference = null)
        run(LocalWorkBusy.READING) { ticket ->
            val result = gateway.read(query)
            currentCoroutineContext().ensureActive()
            if (!current(ticket) || state.value.foodQuery != target) return@run
            val page = (result as? LocalReadResult.Available)?.page
            val tasks = page?.food?.body as? LocalFoodUiBody.Tasks
            val task = tasks?.tasks?.singleOrNull()
            val form = page?.forms?.singleOrNull { it.action == action }
            if (page == null || !matchesFoodPage(query, page) || task == null ||
                !task.matchesKitchenBoardSelection(selected) || form?.matchesKitchenBoardTask(task, action) != true) {
                foodReadFailure((result as? LocalReadResult.Unavailable)?.problem ?: LocalServiceProblem.FOOD_TARGET_UNAVAILABLE,
                    preserveMenuDraft = false)
                return@run
            }
            applyFoodPage(query, page)
            beginForm(form)
        }
    }

    /** Normal selection still performs a fresh protected read and preserves unresolved originals. */
    fun returnKitchenBoard(token: NavigationToken) {
        if (state.value.workflow != LocalWorkflow.KITCHEN) return
        val board = state.value.kitchenBoardOrigin ?: (state.value.foodQuery as? LocalFoodUiQuery.Task)
            ?.let { LocalFoodUiQuery.StationTasks(it.stationId) } ?: return
        selectFood(board, token)
    }

    fun chooseFood(recipeId: DomainId, choiceId: DomainId, token: NavigationToken) {
        if (state.value.workflow != LocalWorkflow.FOOD || !accepts(token) || state.value.working ||
            state.value.recoveryRequired || state.value.formFrozen) return
        val page = state.value.page?.food ?: return
        if (page.query != state.value.foodQuery) return
        val menuQuery = page.query as? LocalFoodUiQuery.Menu ?: return
        val menu = page.body as? LocalFoodUiBody.Menu ?: return
        val recipe = menu.recipes.singleOrNull { it.recipeId == recipeId && it.active } ?: return
        val choice = recipe.choices.singleOrNull { it.choiceId == choiceId } ?: return
        val source = state.value.page?.forms?.singleOrNull { it.action in foodSelectionActions } ?: return
        if (foodChecksOnly && !source.isFoodCheckUiForm(menuQuery)) return
        val existing = state.value.form
        val draft = if (existing == null) newFoodSelectionDraft(source, recipe, choice, menuQuery) else {
            if (existing.descriptor.action != source.action || existing.lines.size >= 8 ||
                existing.foodServingCount()?.let { it < 8 } != true ||
                !existing.matchesFoodMenu(menuQuery, menu.catalogRevision)) return
            appendFoodSelectionDraft(existing, recipe, choice, menuQuery)
        }
        if (!draft.matchesFoodMenu(menuQuery, menu.catalogRevision)) return
        if (existing == null) {
            frozen = null
            mutableState.value = state.value.copy(operationId = null, operation = null, stageUncertain = false)
        }
        applyDraft(draft)
    }

    /** An explicit current-page selection, not a manually entered employee ID or copied grant. */
    fun chooseFoodRecipient(recipientId: DomainId, token: NavigationToken) {
        if (state.value.workflow != LocalWorkflow.FOOD || !accepts(token) || !state.value.canStart) return
        val page = state.value.page?.food ?: return
        val query = page.query as? LocalFoodUiQuery.Recipients ?: return
        val body = page.body as? LocalFoodUiBody.Recipients ?: return
        if (query != state.value.foodQuery || body.orderId != query.orderId || body.orderStorageRevision != query.expectedOrderStorageRevision) return
        val recipient = body.recipients.singleOrNull { it.actorId == recipientId && it.actorId != page.actorId } ?: return
        val source = state.value.page?.forms?.singleOrNull { it.action == LocalAction.FOOD_OFFER_HANDOFF } ?: return
        val descriptor = foodRecipientForm(source, recipient) ?: return
        // Only this explicit selection allocates the offer identity; staging freezes the same
        // bytes and original operation ID through timeout, retry, expiry and durable recovery.
        val created = foodActionDraft(descriptor)
        val selected = created.values[LocalField.RECIPIENT_ID]?.copy(inputIdentityOrigin = LocalInputIdentityOrigin.SELECTED) ?: return
        val draft = created.copy(values = created.values + (LocalField.RECIPIENT_ID to selected), dirty = true,
            foodRecipientSource = query)
        if (!draft.withinInputBound()) {
            mutableState.value = state.value.copy(issue = LocalInputIssue(tooLarge = true))
            return
        }
        frozen = null
        mutableState.value = state.value.copy(operationId = null, operation = null, stageUncertain = false)
        applyDraft(draft)
    }

    private fun refreshFood(token: NavigationToken, nextPage: Boolean) {
        if (!accepts(token) || state.value.working || state.value.formFrozen) return
        val query = if (nextPage) state.value.page?.food?.next ?: return else when (val selected = state.value.foodQuery) {
            is LocalFoodUiQuery.FloorDirectory -> selected.copy(after = null)
            is LocalFoodUiQuery.FloorTables -> selected.copy(after = null)
            is LocalFoodUiQuery.MyChecks -> selected.copy(after = null)
            is LocalFoodUiQuery.OwnClaims -> selected.copy(after = null)
            is LocalFoodUiQuery.OwnPendingPayments -> selected.copy(after = null)
            is LocalFoodUiQuery.Check -> selected.copy(afterPortionId = null)
            is LocalFoodUiQuery.Menu -> selected.copy(after = null)
            is LocalFoodUiQuery.ConfiguredStations -> selected.copy(after = null)
            is LocalFoodUiQuery.StationTasks -> selected.copy(afterTaskId = null)
            is LocalFoodUiQuery.Recipients -> selected.copy(after = null)
            is LocalFoodUiQuery.Incoming -> selected.copy(cursor = null)
            is LocalFoodUiQuery.IncomingKnownOrder -> selected.copy(expectedOrderStorageRevision = null)
            else -> selected
        }
        if (!query.canAppearIn(state.value.workflow)) return
        val form = state.value.form
        if (form == null) {
            mutableState.value = state.value.copy(foodQuery = query, page = null, foodPricePreview = null, foodPricePreviewProblem = null, readProblem = null, foodUnavailable = null)
            load()
        } else if (form.descriptor.action in foodSelectionActions && query is LocalFoodUiQuery.Menu && !state.value.recoveryRequired) {
            // Browsing another menu page does not rewrite previously selected servings. Only
            // an exact matching catalog generation can extend the same ephemeral editor.
            val request = LocalWorkflowQuery(LocalWorkflow.FOOD, food = query)
            mutableState.value = state.value.copy(foodQuery = query, page = null, form = form.copy(confirmed = false),
                foodPricePreview = null, foodPricePreviewProblem = null, readProblem = null, foodUnavailable = null)
            run(LocalWorkBusy.READING) { ticket ->
                val read = try { gateway.read(request) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalReadResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
                currentCoroutineContext().ensureActive()
                if (!current(ticket)) return@run
                when (read) {
                    is LocalReadResult.Available -> applyFoodPage(request, read.page, preserveMenuDraft = true)
                    is LocalReadResult.Unavailable -> foodReadFailure(read.problem, preserveMenuDraft = true)
                }
            }
        }
    }

    /** Synchronous clearance gate in addition to root monitoring and every gateway request. */
    fun reportDisplayCurrent(token: NavigationToken): Boolean = state.value.workflow == LocalWorkflow.REPORTS &&
        accepts(token) && session.lifecycle?.value == LocalSessionLifecycle.ACTIVE

    fun selectBranchReportSection(section: LocalBranchReportSection, token: NavigationToken) {
        if (!reportDisplayCurrent(token) || state.value.working || section == state.value.branchReportQuery.section) return
        mutableState.value = state.value.copy(branchReportQuery = LocalBranchReportQuery(section))
        load()
    }

    /** Explicit source selection, not a document cursor, discovery grant or inferred census. */
    fun selectReportSale(saleId: DomainId, token: NavigationToken) {
        if (!reportDisplayCurrent(token) || state.value.working ||
            state.value.branchReportQuery.section != LocalBranchReportSection.RETAIL_TENDER_BREAKDOWNS) return
        mutableState.value = state.value.copy(branchReportQuery = LocalBranchReportQuery(
            LocalBranchReportSection.RETAIL_TENDER_BREAKDOWNS, selectedSaleId = saleId))
        load()
    }

    fun restartBranchReport(token: NavigationToken) {
        if (!reportDisplayCurrent(token) || state.value.working) return
        mutableState.value = state.value.copy(branchReportQuery = state.value.branchReportQuery.copy(after = null, expectedBranchRevision = null))
        load()
    }

    fun nextBranchReportPage(token: NavigationToken) {
        if (!reportDisplayCurrent(token) || state.value.working ||
            state.value.branchReportQuery.section == LocalBranchReportSection.RETAIL_TENDER_BREAKDOWNS) return
        val page = state.value.page?.branchReport ?: return
        val after = page.nextAfter ?: return
        mutableState.value = state.value.copy(branchReportQuery = LocalBranchReportQuery(page.section, after, page.branchRevision))
        load()
    }

    fun retryBranchReport(token: NavigationToken) {
        if (!reportDisplayCurrent(token) || state.value.working || state.value.readProblem == null ||
            state.value.readProblem == LocalServiceProblem.REPORT_RESTART_REQUIRED) return
        // Keep the same selected page/cut. Only Restart or selecting a new section drops it.
        load()
    }

    fun reportFilesBound(): Boolean = runtime.dependencies.branchReportFiles.bound

    fun exportBranchReport(token: NavigationToken, coverage: LocalBranchReportExportCoverage? = null) {
        if (!reportDisplayCurrent(token) || state.value.working || !reportFilesBound() || state.value.readProblem != null) return
        val page = state.value.page?.branchReport ?: return
        if (page.section != state.value.branchReportQuery.section || page.after != state.value.branchReportQuery.after ||
            page.selectedSaleId != state.value.branchReportQuery.selectedSaleId ||
            page.section == LocalBranchReportSection.RETAIL_TENDER_BREAKDOWNS && coverage != null ||
            page.actorId != session.display.actorId || page.branchId != session.display.branchId) return
        mutableState.value = state.value.copy(branchReportExport = state.value.branchReportExport.copy(result = null,
            progress = LocalBranchReportExportProgress.CHOOSING_DESTINATION))
        // Do not call load(): the original VIEW page and cursor/cut remain intact during export.
        run(LocalWorkBusy.REPORT_EXPORT) { ticket ->
            val lifetime = exportLifetime(ticket)
            try {
                val result = gateway.exportBranchReport(LocalBranchReportExportSelection(page.section,
                    if (coverage == null) page.after else null, coverage, selectedSaleId = page.selectedSaleId),
                    runtime.dependencies.branchReportFiles, lifetime) { progress ->
                    if (current(ticket) && lifetime.isCurrent()) mutableState.value = state.value.copy(
                        branchReportExport = state.value.branchReportExport.copy(progress = progress))
                }
                currentCoroutineContext().ensureActive()
                if (current(ticket) && lifetime.isCurrent()) applyExportResult(result)
            } finally { endExportLifetime(lifetime) }
        }
    }

    fun branchReportExportHistory(token: NavigationToken, nextPage: Boolean = false) {
        if (!reportDisplayCurrent(token) || state.value.working || !reportFilesBound()) return
        val after = if (nextPage) (state.value.branchReportExport.history as? LocalBranchReportExportHistory.Available)?.nextAfter ?: return else null
        mutableState.value = state.value.copy(branchReportExport = state.value.branchReportExport.copy(
            progress = LocalBranchReportExportProgress.RECOVERING, history = null))
        run(LocalWorkBusy.REPORT_EXPORT_LOOKUP) { ticket ->
            val lifetime = exportLifetime(ticket)
            try {
                val result = gateway.branchReportExports(runtime.dependencies.branchReportFiles, lifetime, after)
                currentCoroutineContext().ensureActive()
                if (current(ticket) && lifetime.isCurrent()) {
                    mutableState.value = state.value.copy(branchReportExport = state.value.branchReportExport.copy(progress = null, history = result))
                    if ((result as? LocalBranchReportExportHistory.Unavailable)?.serviceProblem == LocalServiceProblem.SESSION_ENDED)
                        runtime.localSessionEnded(session)
                }
            } finally { endExportLifetime(lifetime) }
        }
    }

    fun recoverBranchReportExport(exportId: DomainId, token: NavigationToken) {
        if (!reportDisplayCurrent(token) || state.value.working || !reportFilesBound()) return
        val presentation = state.value.branchReportExport
        val listed = (presentation.history as? LocalBranchReportExportHistory.Available)?.records.orEmpty().any { it.receipt.exportId == exportId }
        val last = when (val result = presentation.result) {
            is LocalBranchReportExportResult.Outcome -> result.record.receipt.exportId
            is LocalBranchReportExportResult.AuditRecovered -> result.exportId
            is LocalBranchReportExportResult.Unavailable -> result.exportId
            else -> null
        }
        if (!listed && last != exportId) return
        mutableState.value = state.value.copy(branchReportExport = presentation.copy(progress = LocalBranchReportExportProgress.RECOVERING))
        run(LocalWorkBusy.REPORT_EXPORT_LOOKUP) { ticket ->
            val lifetime = exportLifetime(ticket)
            try {
                val result = gateway.recoverBranchReportExport(exportId, runtime.dependencies.branchReportFiles, lifetime)
                currentCoroutineContext().ensureActive()
                if (current(ticket) && lifetime.isCurrent()) applyExportResult(result)
            } finally { endExportLifetime(lifetime) }
        }
    }

    /** Stops the wait/local capability, not a claimed rollback of an already-linked CSV or audit. */
    fun cancelBranchReportExport(token: NavigationToken) {
        if (!reportDisplayCurrent(token) || state.value.busy !in setOf(LocalWorkBusy.REPORT_EXPORT, LocalWorkBusy.REPORT_EXPORT_LOOKUP)) return
        reportFileLifetime?.end()
        work?.cancel()
    }

    private fun exportLifetime(ticket: RequestOwner): LocalBranchReportExportLifetime {
        // Native IO samples only synchronized StateFlows and the exact session's current
        // connection capability. End and native commit-start share the bridge's short gate.
        return runtime.dependencies.branchReportFiles.newLifetime { session.isCurrent() && session.lifecycle?.value == LocalSessionLifecycle.ACTIVE &&
            runtime.navigator.isCurrent(ticket.navigation) }.also { reportFileLifetime = it }
    }

    private fun endExportLifetime(lifetime: LocalBranchReportExportLifetime) {
        lifetime.end()
        if (reportFileLifetime === lifetime) reportFileLifetime = null
    }

    private fun applyExportResult(result: LocalBranchReportExportResult) {
        mutableState.value = state.value.copy(branchReportExport = state.value.branchReportExport.copy(progress = null, result = result))
        val problem = when (result) {
            is LocalBranchReportExportResult.Outcome -> result.record.serviceProblem
            is LocalBranchReportExportResult.AuditRecovered -> result.serviceProblem
            is LocalBranchReportExportResult.Unavailable -> result.serviceProblem
            LocalBranchReportExportResult.ChooserCancelled -> null
        }
        if (problem == LocalServiceProblem.SESSION_ENDED) runtime.localSessionEnded(session)
        // EXPORT denial alone is not VIEW denial: keep the original authorized report page.
        // Actual session/policy lifetime loss still clears it through the existing monitor.
    }

    private fun watchReportLifecycle(lifecycle: StateFlow<LocalSessionLifecycle>) {
        val ticket = activation
        reportLifecycle = scope.launch {
            lifecycle.collect { value ->
                if (!closed && active && ticket == activation && value != LocalSessionLifecycle.ACTIVE) {
                    val cleanup = LocalSessionCleanup()
                    cleanup.attempt { reportFileLifetime?.end() }
                    val problem = when (value) {
                        LocalSessionLifecycle.STORAGE_UNCERTAIN -> LocalServiceProblem.STORAGE_UNCERTAIN
                        LocalSessionLifecycle.AUTHORITY_UNAVAILABLE -> LocalServiceProblem.UNAVAILABLE
                        else -> LocalServiceProblem.SESSION_ENDED
                    }
                    // Clear even when current() is already false. A late read cannot revive this
                    // capability's page, and the root owns subsequent revocation-only cleanup.
                    mutableState.value = LocalWorkspaceState(workflow = LocalWorkflow.REPORTS,
                        branchReportQuery = state.value.branchReportQuery.copy(after = null, expectedBranchRevision = null), readProblem = problem)
                    cleanup.attempt { work?.cancel() }
                    cleanup.attempt { runtime.localSessionEnded(session, problem) }
                    cleanup.throwIfFailed()
                }
            }
        }
    }

    fun changePriceQuantity(value: String, token: NavigationToken) {
        if (!accepts(token) || state.value.working || state.value.form != null || state.value.formFrozen || value.length > 32) return
        val draft = state.value.priceDraft ?: return
        if (state.value.page?.priceControls != draft.controls) return
        mutableState.value = state.value.copy(priceDraft = draft.copy(quantity = value, issue = null))
    }

    fun changePriceChannel(channel: String, token: NavigationToken) {
        if (!accepts(token) || state.value.working || state.value.form != null || state.value.formFrozen ||
            channel !in setOf("COUNTER", "DINE_IN", "TAKEAWAY", "DELIVERY")) return
        val draft = state.value.priceDraft ?: return
        if (state.value.page?.priceControls != draft.controls) return
        mutableState.value = state.value.copy(priceDraft = draft.copy(channel = channel, issue = null))
    }

    /** A protected read only: no operation ID, durable intent, price math or quote reservation. */
    fun previewPrice(token: NavigationToken) {
        if (!accepts(token) || state.value.working || state.value.form != null || state.value.formFrozen ||
            state.value.workflow != LocalWorkflow.CATALOG) return
        val draft = state.value.priceDraft ?: return
        if (state.value.page?.priceControls != draft.controls) return
        val quantity = try {
            LocalScalarValue.Decimal(normalizeLocalNumber(draft.quantity)).also {
                require(Quantity.parse(draft.controls.unitId, it.canonical, draft.controls.quantityScale).scaled > 0L)
            }
        } catch (_: IllegalArgumentException) {
            mutableState.value = state.value.copy(priceDraft = draft.copy(issue = LocalField.QUANTITY))
            return
        } catch (_: ArithmeticException) {
            // A valid decimal can still exceed the exact scaled-Long quantity boundary.
            mutableState.value = state.value.copy(priceDraft = draft.copy(issue = LocalField.QUANTITY))
            return
        }
        val channel = draft.channel
        if (channel == null) {
            mutableState.value = state.value.copy(priceDraft = draft.copy(issue = LocalField.CHANNEL))
            return
        }
        val preview = LocalPricePreviewQuery(draft.controls.skuId, channel, quantity)
        supplierPhysical.deactivate()
        mutableState.value = state.value.copy(reference = LocalRecordReference(LocalRecordKind.SKU, draft.controls.skuId), search = "",
            pricePreview = preview, priceDraft = draft.copy(issue = null))
        load()
    }

    /** Refresh and post-acknowledgement reads must preserve the same section selectors. */
    private fun currentQuery(cursor: String? = null): LocalWorkflowQuery {
        val current = state.value
        return LocalWorkflowQuery(current.workflow, current.search, current.reference, cursor, current.pricePreview,
            branchReport = current.branchReportQuery.takeIf { current.workflow == LocalWorkflow.REPORTS },
            food = current.foodQuery.takeIf { current.workflow.usesFoodQueries },
            purchasing = current.purchasingQuery.takeIf { current.workflow == LocalWorkflow.PURCHASING },
            returns = current.returnQuery.takeIf { current.workflow == LocalWorkflow.RETURNS },
            cash = current.cashQuery.takeIf { current.workflow == LocalWorkflow.CASH },
            inventory = current.inventorySection.takeIf { current.workflow == LocalWorkflow.INVENTORY })
    }

    private fun load(cursor: String? = null) {
        if (!invoiceWorkspaceCompatible()) return
        if (!foodCheckWorkspaceCompatible()) return // Preserve unrelated retained originals without invoking this profile.
        val query = currentQuery(cursor)
        if (!customerReceiptWorkspaceCompatible()) {
            readUnavailable(LocalServiceProblem.INVALID_INPUT)
            return
        }
        if (paymentCorrectionsOnly && !query.isPaymentCorrectionUiQuery()) {
            readUnavailable(LocalServiceProblem.INVALID_INPUT)
            return
        }
        supplierPhysical.deactivate()
        if (query.workflow.isManagerOrderContext(query.search)) retailPhysical.deactivate()
        mutableState.value = state.value.copy(page = null, foodPricePreview = null, foodPricePreviewProblem = null, readProblem = null, form = null, openingCounts = null, referencePicker = null, issue = null,
            stageProblem = null, pending = emptyList(), pendingLoaded = false, pendingProblem = null,
            pendingCursor = null, pendingPageIsFirst = true, foodUnavailable = null)
        if (query.workflow == LocalWorkflow.REPORTS && query.branchReport?.section == LocalBranchReportSection.RETAIL_TENDER_BREAKDOWNS &&
            query.branchReport.selectedSaleId == null) {
            // Selection-only state; no authorized source read or empty/zero report is inferred.
            syncGuard()
            return
        }
        run(LocalWorkBusy.READING) { ticket ->
            if (query.workflow != LocalWorkflow.REPORTS && !query.workflow.isManagerOrderReview(query.search)) {
                val pending = try { gateway.pending() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalPendingResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
                currentCoroutineContext().ensureActive()
                if (!current(ticket)) return@run
                applyPending(pending, null)
            }
            // Report pages are protected reads, never commands gated on an unrelated queue.
            // No pending entry is acknowledged, forgotten, relabelled or dispatched here.
            if (!current(ticket)) return@run
            val result = try { gateway.read(query) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { LocalReadResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
            currentCoroutineContext().ensureActive()
            if (!current(ticket)) return@run
            when (result) {
                is LocalReadResult.Available -> applyPage(query, result.page)
                is LocalReadResult.Unavailable -> readUnavailable(result.problem)
            }
            if (current(ticket) && documentaryCurrent()) readPurchasingDocumentSources(ticket)
        }
    }

    private fun applyPage(query: LocalWorkflowQuery, page: LocalWorkflowPage) {
        if (invoiceAction?.let { action -> query.workflow != LocalWorkflow.PURCHASING ||
                query.purchasing?.section?.let { page.isInvoiceUiPage(it, action, query.reference) } != true } == true) {
            readUnavailable(LocalServiceProblem.UNAVAILABLE)
            return
        }
        if (paymentCorrectionsOnly && !page.matchesPaymentCorrectionUiPage(query)) {
            readUnavailable(LocalServiceProblem.UNAVAILABLE)
            return
        }
        if (query.workflow.usesFoodQueries) {
            applyFoodPage(query, page)
            return
        }
        if (query.workflow == LocalWorkflow.REPORTS) {
            applyBranchReportPage(query, page)
            return
        }
        val controls = page.priceControls
        val preview = query.pricePreview
        page.returnRights?.let { rights ->
            val display = session.display
            if (rights.selection != query.reference || rights.companyId != display.companyId || rights.branchId != display.branchId) {
                readUnavailable(LocalServiceProblem.UNAVAILABLE)
                return
            }
        }
        if (page.workflow != query.workflow || controls != null && (
                query.reference?.let { it.kind != LocalRecordKind.SKU || it.id != controls.skuId } == true ||
                query.reference == null && page.records.none { it.reference.kind == LocalRecordKind.SKU && it.reference.id == controls.skuId }) ||
            preview != null && controls?.skuId != preview.skuId) {
            readUnavailable(LocalServiceProblem.UNAVAILABLE)
            return
        }
        if (query.workflow.isManagerOrderReview(query.search) && (page.forms.isNotEmpty() ||
                page.records.any { it.selectable } || page.nextCursor != null || page.priceControls != null)) {
            readUnavailable(LocalServiceProblem.UNAVAILABLE)
            return
        }
        if (query.workflow.isManagerOrderContext(query.search) && !query.workflow.isManagerOrderReview(query.search) &&
            (page.records.size > 1 || page.records.any { it.selectable || it.reference.kind != LocalRecordKind.ORDER ||
                it.reference.id.value != query.search.removePrefix("reassign:") } || page.nextCursor != null || page.priceControls != null ||
                page.managerQuoteDetails != null || page.forms.size > 1 || page.forms.any { form ->
                    !form.matchesManagerReassignment(query.search) || page.records.singleOrNull()?.reference?.revision?.value !=
                        (form.fields.filterIsInstance<LocalFormField.Scalar>().singleOrNull { it.field == LocalField.EXPECTED_REVISION }
                            ?.initialValue as? LocalScalarValue.Integer)?.value
                })) {
            readUnavailable(LocalServiceProblem.UNAVAILABLE)
            return
        }
        if (page.managerQuoteDetails != null && (!query.workflow.isManagerOrderReview(query.search) ||
                page.managerQuoteDetails.orderId.value != query.search.removePrefix("manager:"))) {
            readUnavailable(LocalServiceProblem.UNAVAILABLE)
            return
        }
        val draft = controls?.let { metadata ->
            state.value.priceDraft?.takeIf { it.controls == metadata }?.copy(issue = null)
                ?: LocalPricePreviewDraft(metadata, preview?.quantity?.canonical.orEmpty(), preview?.channel)
        }
        mutableState.value = state.value.copy(page = snapshotPage(page), readProblem = null, priceDraft = draft)
        activateSupplierPhysical()
    }

    private fun matchesFoodPage(query: LocalWorkflowQuery, page: LocalWorkflowPage): Boolean {
        val food = page.food ?: return false
        val display = session.display
        return query.workflow.usesFoodQueries && page.workflow == query.workflow && food.query == query.food &&
            food.query.canAppearIn(query.workflow) &&
            food.companyId == display.companyId && food.branchId == display.branchId && food.actorId == display.actorId &&
            page.records.isEmpty() && page.branchReport == null && page.nextCursor == null && page.priceControls == null &&
            (!foodCashFinancialOnly || page.matchesFoodCashFinancialUiPage(food.query)) &&
            (!foodChecksOnly || page.matchesFoodCheckUiPage(food.query)) &&
            (!foodTableAssignmentsOnly || page.matchesFoodTableAssignmentUiPage(food.query))
    }

    private fun applyFoodPage(query: LocalWorkflowQuery, page: LocalWorkflowPage, preserveMenuDraft: Boolean = false) {
        if (!matchesFoodPage(query, page) || query.food != state.value.foodQuery) {
            foodReadFailure(LocalServiceProblem.UNAVAILABLE, preserveMenuDraft)
            return
        }
        val food = page.food ?: return
        if (preserveMenuDraft) {
            val form = state.value.form
            val menu = food.body as? LocalFoodUiBody.Menu
            val selection = food.query as? LocalFoodUiQuery.Menu
            if (form == null || menu == null || selection == null || !form.matchesFoodMenu(selection, menu.catalogRevision)) {
                foodReadFailure(LocalServiceProblem.FOOD_TARGET_UNAVAILABLE, preserveMenuDraft = true)
                return
            }
        }
        mutableState.value = state.value.copy(page = snapshotPage(page),
            form = state.value.form?.let { if (preserveMenuDraft) it.copy(confirmed = false) else it },
            foodPricePreview = null, foodPricePreviewProblem = null, readProblem = null, foodUnavailable = null)
    }

    private fun foodReadFailure(problem: LocalServiceProblem, preserveMenuDraft: Boolean) {
        mutableState.value = state.value.copy(page = null, foodPricePreview = null, foodPricePreviewProblem = null, readProblem = problem,
            foodUnavailable = foodUnavailableReason(problem), form = state.value.form.takeIf { preserveMenuDraft })
        permissionFailure(problem)
    }

    private fun applyBranchReportPage(query: LocalWorkflowQuery, page: LocalWorkflowPage) {
        val selection = query.branchReport
        val report = page.branchReport
        val display = session.display
        if (page.workflow != LocalWorkflow.REPORTS || selection == null || report == null ||
            page.records.isNotEmpty() || page.forms.isNotEmpty() || page.nextCursor != null || page.priceControls != null ||
            selection != state.value.branchReportQuery || report.section != selection.section || report.after != selection.after ||
            report.selectedSaleId != selection.selectedSaleId ||
            selection.section == LocalBranchReportSection.RETAIL_TENDER_BREAKDOWNS &&
                (report.after != null || report.nextAfter != null || report.documents.size != 1 ||
                    report.documents.single().id != selection.selectedSaleId) ||
            report.actorId != display.actorId || report.companyId != display.companyId || report.branchId != display.branchId) {
            readUnavailable(LocalServiceProblem.UNAVAILABLE)
            return
        }
        if (selection.expectedBranchRevision != null && report.branchRevision != selection.expectedBranchRevision) {
            readUnavailable(LocalServiceProblem.REPORT_RESTART_REQUIRED)
            return
        }
        mutableState.value = state.value.copy(page = snapshotPage(page), readProblem = null)
    }

    fun refreshPending(token: NavigationToken, nextPage: Boolean = false) {
        if (state.value.managerOrderReview || !accepts(token) || state.value.working || state.value.form?.dirty == true && !state.value.formFrozen) return
        val cursor = if (nextPage) state.value.pendingCursor ?: return else null
        readPending(cursor)
    }

    private fun readPending(cursor: String?) {
        mutableState.value = state.value.copy(pending = emptyList(), pendingLoaded = false, pendingProblem = null)
        run(LocalWorkBusy.PENDING) { ticket ->
            val result = gateway.pending(cursor)
            currentCoroutineContext().ensureActive()
            if (current(ticket)) applyPending(result, cursor)
        }
    }

    private fun applyPending(result: LocalPendingResult, cursor: String?) {
        if (result is LocalPendingResult.Available &&
            invoiceAction?.let { action -> result.operations.any { !it.isInvoiceUiPending(action) } } == true) {
            applyPending(LocalPendingResult.Unavailable(LocalServiceProblem.NOT_IMPLEMENTED), cursor)
            return // No incompatible original is filtered into an apparently empty successful scan.
        }
        if (foodTableAssignmentsOnly && result is LocalPendingResult.Available &&
            result.operations.any { !it.isFoodTableAssignmentUiPending() }) {
            applyPending(LocalPendingResult.Unavailable(LocalServiceProblem.STORAGE_UNCERTAIN), cursor)
            return // Incompatible originals remain in their journal for the matching workflow.
        }
        if (foodCashFinancialOnly && result is LocalPendingResult.Available &&
            result.operations.any { !it.isFoodCashFinancialUiPending() }) {
            applyPending(LocalPendingResult.Unavailable(LocalServiceProblem.STORAGE_UNCERTAIN), cursor)
            return // Preserve the complete incompatible retained set, never silently filter it.
        }
        if (foodChecksOnly && result is LocalPendingResult.Available && result.operations.any { !it.isFoodCheckUiPending() }) {
            applyPending(LocalPendingResult.Unavailable(LocalServiceProblem.STORAGE_UNCERTAIN), cursor)
            return // A mixed incompatible set is unavailable; original journal evidence remains retained.
        }
        if (paymentCorrectionsOnly && result is LocalPendingResult.Available &&
            result.operations.any { it.action != LocalAction.PAYMENT_CLASSIFICATION_CORRECT }) {
            applyPending(LocalPendingResult.Unavailable(LocalServiceProblem.STORAGE_UNCERTAIN), cursor)
            return // Do not expose or reinterpret unrelated originals; their journal remains owned.
        }
        when (result) {
            is LocalPendingResult.Available -> mutableState.value = state.value.copy(
                pending = result.operations.map { it.copy(summary = it.summary.toList()) }, pendingLoaded = true, pendingProblem = null,
                pendingCursor = result.nextCursor, pendingPageIsFirst = cursor == null)
            is LocalPendingResult.Unavailable -> {
                mutableState.value = state.value.copy(pending = emptyList(), pendingLoaded = false,
                    pendingProblem = result.problem, pendingCursor = null)
                permissionFailure(result.problem)
            }
        }
    }

    fun openForm(action: LocalAction, token: NavigationToken) {
        if (invoiceOnly && action != invoiceAction) return
        if (foodCashFinancialOnly && action !in foodCashFinancialUiActions) return
        if (foodChecksOnly && action !in foodCheckUiActions) return
        if (foodTableAssignmentsOnly && action != LocalAction.FOOD_ASSIGN_TABLE) return
        if (paymentCorrectionsOnly && action != LocalAction.PAYMENT_CLASSIFICATION_CORRECT) return
        if (customerReceiptsOnly && (action != LocalAction.RETAIL_CUSTOMER_RECEIPT ||
                state.value.workflow == LocalWorkflow.RETURNS)) return
        if (!accepts(token) || !state.value.canStart) return
        if (action in foodSelectionActions) return // A real menu choice must create the first serving.
        val descriptor = state.value.page?.forms?.singleOrNull { it.action == action } ?: return
        if (state.value.managerOrderContext && !descriptor.matchesManagerReassignment(state.value.search)) return
        if (!state.value.managerOrderContext && action == LocalAction.ORDER_REASSIGN) return
        if (action == LocalAction.FOOD_OFFER_HANDOFF) {
            val query = when (val body = state.value.page?.food?.body) {
                is LocalFoodUiBody.Check -> LocalFoodUiQuery.Recipients(body.check.orderId, body.check.orderStorageRevision)
                is LocalFoodUiBody.Recipients -> LocalFoodUiQuery.Recipients(body.orderId, body.orderStorageRevision)
                else -> return
            }
            selectFood(query, token) // Opening the picker performs no mutation and mints no ID.
            return
        }
        beginForm(descriptor)
    }

    private fun beginForm(descriptor: LocalActionForm) {
        if (invoiceAction?.let { !descriptor.isInvoiceUiForm(it, state.value.reference) } == true) return
        if (foodCashFinancialOnly && !descriptor.isFoodCashFinancialUiForm(state.value.foodQuery, state.value.page?.food?.body)) return
        if (foodChecksOnly && !descriptor.isFoodCheckUiForm(state.value.foodQuery)) return
        if (foodTableAssignmentsOnly && !descriptor.isFoodTableAssignmentUiForm(state.value.foodQuery,
                state.value.page?.food?.body as? LocalFoodUiBody.Table)) return
        if (paymentCorrectionsOnly && !descriptor.matchesPaymentCorrectionUiForm(state.value.reference)) return
        val draft = if (descriptor.action.workflow == LocalWorkflow.FOOD) foodActionDraft(descriptor) else localFormDraft(descriptor)
        if (!draft.withinInputBound()) {
            mutableState.value = state.value.copy(issue = LocalInputIssue(tooLarge = true))
            return
        }
        frozen = null
        mutableState.value = state.value.copy(form = draft,
            page = if (descriptor.openingAllocations != null) null else state.value.page,
            openingCounts = descriptor.openingAllocations?.let { LocalOpeningCountDraft() },
            referencePicker = null, issue = null, stageProblem = null,
            operationId = null, operation = null, stageUncertain = false)
        syncGuard()
    }

    /** Called after explicit discard confirmation if edits are dirty, never for staged operations. */
    fun discardForm(token: NavigationToken) {
        if (state.value.attachmentReviewRequired && state.value.formInput != null) {
            if (!acceptsEntry(token) || state.value.working || state.value.operationId != null || frozen != null) return
            mutableState.value = state.value.copy(formInput = null, openingCounts = null, readProblem = null)
            syncGuard()
            return
        }
        if (!accepts(token) || state.value.working || state.value.formFrozen) return
        val countedOpening = state.value.form?.descriptor?.openingAllocations != null
        mutableState.value = state.value.copy(form = null, foodPricePreview = null, foodPricePreviewProblem = null, openingCounts = null, referencePicker = null, issue = null, stageProblem = null)
        syncGuard()
        if (countedOpening) load() // The editor retained only its current source page, not a hidden old census.
        else if (state.value.kitchenBoardOrigin != null) returnKitchenBoard(runtime.navigator.token())
    }

    /** Page reads never replace counts or the selected prospective receipt. They must rejoin
     * the same complete approval group and SKU revision; failure keeps a blocked private draft. */
    fun readOpeningAllocations(token: NavigationToken, nextPage: Boolean = false) {
        if (!accepts(token) || state.value.working || state.value.formFrozen) return
        val form = state.value.form ?: return
        val previous = form.descriptor.openingAllocations ?: return
        if (state.value.openingCounts == null) return
        val cursor = if (nextPage) previous.nextCursor ?: return else null
        val query = LocalWorkflowQuery(LocalWorkflow.INVENTORY,
            reference = LocalRecordReference(LocalRecordKind.SKU, previous.query.skuId, parentId = previous.query.basisId),
            cursor = cursor, inventory = LocalInventorySection.OPENING, openingAllocations = previous.query)
        run(LocalWorkBusy.READING) { ticket ->
            mutableState.value = state.value.copy(form = state.value.form?.copy(confirmed = false),
                openingCounts = state.value.openingCounts?.copy(sourceProblem = LocalServiceProblem.UNAVAILABLE))
            val result = try { gateway.read(query) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { LocalReadResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
            currentCoroutineContext().ensureActive()
            if (!current(ticket) || state.value.form?.descriptor?.openingAllocations?.query != previous.query) return@run
            val page = (result as? LocalReadResult.Available)?.page
            val source = page?.openingAllocations
            val expectedOffset = if (nextPage) previous.offset + previous.rows.size else 0
            if (page == null || page.workflow != LocalWorkflow.INVENTORY || source == null || source.query != previous.query ||
                source.allocationIds != previous.allocationIds || source.offset != expectedOffset || page.nextCursor != source.nextCursor) {
                openingReadUnavailable((result as? LocalReadResult.Unavailable)?.problem ?: LocalServiceProblem.UNAVAILABLE)
                return@run
            }
            val currentForm = state.value.form ?: return@run
            mutableState.value = state.value.copy(form = currentForm.copy(
                descriptor = currentForm.descriptor.copy(openingAllocations = snapshotOpeningAllocations(source)), confirmed = false),
                openingCounts = state.value.openingCounts?.copy(sourceProblem = null), issue = null)
        }
    }

    private fun openingReadUnavailable(problem: LocalServiceProblem) {
        mutableState.value = state.value.copy(form = state.value.form?.copy(confirmed = false),
            openingCounts = state.value.openingCounts?.copy(sourceProblem = problem))
        permissionFailure(problem) // Revocation/logout still clears private material as before.
    }

    fun countOpeningAllocation(allocationId: DomainId, token: NavigationToken) {
        if (!accepts(token) || state.value.working || state.value.formFrozen) return
        val form = state.value.form ?: return
        val counts = state.value.openingCounts ?: return
        if (counts.sourceProblem != null) return
        val source = form.descriptor.openingAllocations ?: return
        val row = source.rows.singleOrNull { it.allocationId == allocationId } ?: return
        val field = form.descriptor.fields.filterIsInstance<LocalFormField.Lines>().singleOrNull() ?: return
        if (form.lines.any { openingAllocationId(it) == allocationId }) return // Additional slices use the explicit split control.
        if (form.lines.size >= field.maximumLines) {
            mutableState.value = state.value.copy(issue = LocalInputIssue(tooLarge = true))
            return
        }
        val serial = (row.initialLine.values.singleOrNull { it.field == LocalField.TRACKING }?.value as? LocalScalarValue.Choice)?.code == "SERIAL"
        val initial = LocalInputLine(DomainId.random(), row.initialLine.values.map { value ->
            if (serial && value.field == LocalField.SERIAL_ID) LocalScalarInput(value.field, LocalScalarValue.Identifier(DomainId.random()))
            else value
        })
        val line = localDraftLine(field.columns, initial)
        val updated = form.copy(lines = form.lines + line, confirmed = false, dirty = true)
        applyDraft(updated)
        if (state.value.form === updated) mutableState.value = state.value.copy(openingCounts = counts.copy(
            zeroAllocations = counts.zeroAllocations - allocationId,
            lineOffset = (updated.lines.lastIndex / LocalOpeningAllocationPage.PAGE_SIZE) * LocalOpeningAllocationPage.PAGE_SIZE))
    }

    fun confirmOpeningZero(allocationId: DomainId, zero: Boolean, token: NavigationToken) {
        if (!accepts(token) || state.value.working || state.value.formFrozen) return
        val form = state.value.form ?: return
        val counts = state.value.openingCounts ?: return
        val source = form.descriptor.openingAllocations ?: return
        if (counts.sourceProblem != null || source.rows.none { it.allocationId == allocationId } ||
            form.lines.any { openingAllocationId(it) == allocationId }) return
        // Existing positive or unfinished slices must first be explicitly removed. This
        // checkbox never deletes them, and removing their last slice never selects zero.
        mutableState.value = state.value.copy(form = form.copy(confirmed = false, dirty = true),
            openingCounts = counts.copy(zeroAllocations = if (zero) counts.zeroAllocations + allocationId else counts.zeroAllocations - allocationId),
            issue = null, stageProblem = null)
        syncGuard()
    }

    fun pageOpeningSlices(token: NavigationToken, nextPage: Boolean) {
        if (!accepts(token) || state.value.working || state.value.formFrozen) return
        val form = state.value.form ?: return
        val counts = state.value.openingCounts ?: return
        val offset = if (nextPage) counts.lineOffset + LocalOpeningAllocationPage.PAGE_SIZE else 0
        if (offset != 0 && offset >= form.lines.size) return
        mutableState.value = state.value.copy(openingCounts = counts.copy(lineOffset = offset))
    }

    fun openReferencePicker(field: LocalField, token: NavigationToken, lineId: DomainId? = null) {
        if (invoiceMatchingOnly) return // Only fixed choices from this invoice; no directory discovery.
        if (!accepts(token) || state.value.working || state.value.formFrozen) return
        val form = state.value.form ?: return
        val column = if (lineId == null) form.descriptor.fields.filterIsInstance<LocalFormField.Scalar>().find { it.field == field }
            else form.descriptor.fields.filterIsInstance<LocalFormField.Lines>().singleOrNull()?.columns?.find { it.field == field }
        if (column == null || column.readOnly || lineId != null && form.lines.none { it.lineId == lineId }) return
        val source = column.referenceSource ?: return
        mutableState.value = state.value.copy(referencePicker = LocalReferencePickerState(field, lineId, column.label, source))
        readReferences(LocalReferenceQuery(source))
    }

    fun closeReferencePicker(token: NavigationToken) {
        if (!accepts(token) || state.value.formFrozen || state.value.referencePicker == null) return
        mutableState.value = state.value.copy(referencePicker = null)
        // Only cancel this read. Neither a command nor its journal is owned by the dialog.
        if (state.value.busy == LocalWorkBusy.REFERENCES) work?.cancel()
    }

    fun changeReferenceId(value: String, token: NavigationToken) {
        if (!accepts(token) || state.value.working || value.length > 36) return
        val picker = state.value.referencePicker ?: return
        mutableState.value = state.value.copy(referencePicker = picker.copy(exactIdText = value, invalidId = false))
    }

    fun referencePage(token: NavigationToken, next: Boolean = false, exact: Boolean = false) {
        if (!accepts(token) || state.value.working || state.value.formFrozen || next && exact) return
        val picker = state.value.referencePicker ?: return
        val id = if (exact) try { DomainId(picker.exactIdText.trim()) } catch (_: IllegalArgumentException) {
            mutableState.value = state.value.copy(referencePicker = picker.copy(invalidId = true))
            return
        } else null
        val after = if (next) picker.page?.nextAfter ?: return else null
        readReferences(LocalReferenceQuery(picker.source, after, id))
    }

    private fun readReferences(query: LocalReferenceQuery) {
        if (invoiceMatchingOnly) return
        val picker = state.value.referencePicker ?: return
        if (picker.source != query.source || state.value.form == null) return
        mutableState.value = state.value.copy(referencePicker = picker.copy(requested = query, page = null,
            problem = null, invalidId = false))
        run(LocalWorkBusy.REFERENCES) { ticket ->
            val result = gateway.references(query)
            currentCoroutineContext().ensureActive()
            val actual = state.value.referencePicker
            if (!current(ticket) || actual?.requested != query || actual.field != picker.field || actual.lineId != picker.lineId) return@run
            when (result) {
                is LocalReferenceResult.Available -> {
                    if (result.page.query != query) referenceUnavailable(LocalServiceProblem.UNAVAILABLE)
                    else mutableState.value = state.value.copy(referencePicker = actual.copy(
                        page = result.page.copy(choices = result.page.choices.toList()), problem = null))
                }
                is LocalReferenceResult.Unavailable -> referenceUnavailable(result.problem)
            }
        }
    }

    private fun referenceUnavailable(problem: LocalServiceProblem) {
        state.value.referencePicker?.let { picker ->
            mutableState.value = state.value.copy(referencePicker = picker.copy(page = null, problem = problem))
        }
        permissionFailure(problem)
    }

    fun chooseReference(choice: LocalReferenceChoice, token: NavigationToken) {
        if (!accepts(token) || state.value.working || state.value.formFrozen) return
        val picker = state.value.referencePicker ?: return
        if (picker.page?.choices?.none { it == choice } != false || !picker.source.accepts(choice.reference)) return
        edit(picker.field, LocalDraftValue(selected = LocalScalarValue.Reference(choice.reference), referenceLabel = choice.label), token, picker.lineId)
        mutableState.value = state.value.copy(referencePicker = null)
    }

    fun edit(field: LocalField, value: LocalDraftValue, token: NavigationToken, lineId: DomainId? = null) {
        if (!acceptsEdit(token) || state.value.working || state.value.formFrozen) return
        val form = state.value.form ?: return
        val column = if (lineId == null) form.descriptor.fields.filterIsInstance<LocalFormField.Scalar>().find { it.field == field }
            else form.descriptor.fields.filterIsInstance<LocalFormField.Lines>().singleOrNull()?.columns?.find { it.field == field }
        if (column == null || column.readOnly || value.text.length > column.maximumCharacters) return
        val confirmed = form.confirmed && keepConfirmationOnEdit(form)
        val updated = if (lineId == null) form.copy(values = form.values + (field to value), confirmed = confirmed, dirty = true)
            else {
                if (form.lines.none { it.lineId == lineId }) return
                form.copy(lines = form.lines.map { line ->
                    if (line.lineId != lineId) line
                    else if (form.descriptor.action in foodSelectionActions && field == LocalField.FOOD_SERVING_COUNT)
                        line.withFoodServingCount(value)
                    else line.copy(values = line.values + (field to value))
                },
                    confirmed = confirmed, dirty = true)
            }
        applyDraft(if (lineId == null && field == LocalField.CHANNEL) updated.withFoodDeliveryChannelCleanup() else updated)
    }

    fun addLine(token: NavigationToken) {
        if (!accepts(token) || state.value.working || state.value.formFrozen) return
        val form = state.value.form ?: return
        val field = form.descriptor.fields.filterIsInstance<LocalFormField.Lines>().singleOrNull() ?: return
        if (field.allowBlankLines && form.lines.size < field.maximumLines) applyDraft(form.copy(
            lines = form.lines + localDraftLine(field.columns), confirmed = form.confirmed && keepConfirmationOnEdit(form), dirty = true))
    }

    fun duplicateLine(lineId: DomainId, token: NavigationToken) {
        if (!accepts(token) || state.value.working || state.value.formFrozen) return
        val form = state.value.form ?: return
        val field = form.descriptor.fields.filterIsInstance<LocalFormField.Lines>().singleOrNull() ?: return
        if (!field.allowDuplicateLines || form.lines.size >= field.maximumLines) return
        val original = form.lines.singleOrNull { it.lineId == lineId } ?: return
        if (form.descriptor.action in foodSelectionActions) {
            val count = original.values[LocalField.FOOD_SERVING_COUNT]?.foodServingCount() ?: return
            if (count !in 1..8 || form.foodServingCount()?.let { it + count <= 8 } != true) return
        }
        // Metadata stays bound to the approved allocation; no source/operation ID is reused.
        val serializedReceipt = form.descriptor.action in setOf(LocalAction.RECEIPT_POST, LocalAction.STOCK_OPENING) &&
            (original.values[LocalField.TRACKING]?.selected as? LocalScalarValue.Choice)?.code == "SERIAL"
        val copiedValues = when {
            form.descriptor.action == LocalAction.ARRIVAL_RECORD -> original.values + mapOf(
                LocalField.SOURCE_ID to LocalDraftValue(DomainId.random().value, inputIdentityOrigin = LocalInputIdentityOrigin.CREATED),
                LocalField.CUSTODY_UNIT_ID to LocalDraftValue(DomainId.random().value, inputIdentityOrigin = LocalInputIdentityOrigin.CREATED),
                LocalField.SERIAL_ID to LocalDraftValue(), LocalField.SERIAL_CODE to LocalDraftValue(),
                LocalField.EXPECTED_SERIAL_REGISTRATION_REVISION to LocalDraftValue(), LocalField.QUANTITY to LocalDraftValue())
            serializedReceipt -> original.values + mapOf(
                LocalField.SERIAL_ID to LocalDraftValue(DomainId.random().value), LocalField.SERIAL_CODE to LocalDraftValue())
            else -> original.values.toMap()
        }
        val values = if (form.descriptor.openingAllocations != null) copiedValues + mapOf(
            LocalField.QUANTITY to LocalDraftValue(if (serializedReceipt) "1" else ""),
            LocalField.ORIGINAL_OWNED_VALUE to LocalDraftValue()) else copiedValues
        val duplicated = original.copy(lineId = DomainId.random(), values = values, foodPortionIds = emptyList()).let { line ->
            if (form.descriptor.action in foodSelectionActions)
                line.withFoodServingCount(values[LocalField.FOOD_SERVING_COUNT] ?: LocalDraftValue("1")) else line
        }
        val updated = form.copy(lines = form.lines + duplicated,
            confirmed = false, dirty = true)
        applyDraft(updated)
        if (state.value.form === updated) state.value.openingCounts?.let { counts ->
            mutableState.value = state.value.copy(openingCounts = counts.copy(
                lineOffset = (updated.lines.lastIndex / LocalOpeningAllocationPage.PAGE_SIZE) * LocalOpeningAllocationPage.PAGE_SIZE))
        }
    }

    fun removeLine(lineId: DomainId, token: NavigationToken) {
        if (!accepts(token) || state.value.working || state.value.formFrozen) return
        val form = state.value.form ?: return
        val field = form.descriptor.fields.filterIsInstance<LocalFormField.Lines>().singleOrNull() ?: return
        if (form.lines.size <= field.minimumLines || form.lines.none { it.lineId == lineId }) return
        val updated = form.copy(lines = form.lines.filterNot { it.lineId == lineId },
            confirmed = form.confirmed && keepConfirmationOnEdit(form), dirty = true)
        applyDraft(updated)
        if (state.value.form === updated) state.value.openingCounts?.let { counts ->
            val lastPage = if (updated.lines.isEmpty()) 0 else (updated.lines.lastIndex / LocalOpeningAllocationPage.PAGE_SIZE) * LocalOpeningAllocationPage.PAGE_SIZE
            mutableState.value = state.value.copy(openingCounts = counts.copy(lineOffset = minOf(counts.lineOffset, lastPage)))
        }
    }

    /** Opening stock/debt or a value-bound allowance needs review after every draft change. */
    private fun keepConfirmationOnEdit(form: LocalFormDraft): Boolean =
        form.descriptor.action.workflow != LocalWorkflow.FOOD &&
            form.descriptor.action !in setOf(LocalAction.STOCK_OPENING, LocalAction.RECEIVING_APPROVE,
                LocalAction.SUPPLIER_OPENING_RECORD)

    fun confirmForm(confirmed: Boolean, token: NavigationToken) {
        if (!acceptsEdit(token) || state.value.working || state.value.formFrozen) return
        if (confirmed && state.value.form?.descriptor?.openingAllocations != null && !state.value.openingCanSave) return
        state.value.form?.let { applyDraft(it.copy(confirmed = confirmed, dirty = true)) }
    }

    private fun applyDraft(draft: LocalFormDraft) {
        if (!draft.withinInputBound()) mutableState.value = state.value.copy(issue = LocalInputIssue(tooLarge = true))
        else {
            val previous = state.value.form
            val sameInput = previous != null && previous.descriptor == draft.descriptor &&
                previous.values == draft.values && previous.lines == draft.lines
            mutableState.value = state.value.copy(form = draft, issue = null, stageProblem = null,
                foodPricePreview = state.value.foodPricePreview.takeIf { sameInput },
                foodPricePreviewProblem = state.value.foodPricePreviewProblem.takeIf { sameInput })
        }
        syncGuard()
    }

    /** An explicit protected price read. No operation identity, journal or business effect. */
    fun foodPreviewDisplayCurrent(token: NavigationToken): Boolean = state.value.workflow == LocalWorkflow.FOOD &&
        accepts(token) && session.lifecycle?.value == LocalSessionLifecycle.ACTIVE && !state.value.formFrozen &&
        state.value.form?.descriptor?.action in foodSelectionActions

    fun previewFood(token: NavigationToken) {
        if (!foodPreviewDisplayCurrent(token) || state.value.working ||
            state.value.recoveryRequired || state.value.formFrozen || state.value.referencePicker != null) return
        val form = state.value.form ?: return
        val input = foodPreviewInput(form) ?: return
        val epoch = foodPreviewEpoch
        mutableState.value = state.value.copy(foodPricePreview = null, foodPricePreviewProblem = null)
        run(LocalWorkBusy.FOOD_PREVIEW) { ticket ->
            val result = gateway.previewFood(input.snapshot())
            currentCoroutineContext().ensureActive()
            if (!current(ticket) || state.value.form !== form || foodPreviewEpoch != epoch) return@run
            if (session.lifecycle?.value != LocalSessionLifecycle.ACTIVE) {
                foodPreviewUnavailable(LocalServiceProblem.SESSION_ENDED)
                return@run
            }
            when (result) {
                is LocalFoodPricePreviewResult.Available -> {
                    if (!input.sameSelection(result.preview.input)) foodPreviewUnavailable(LocalServiceProblem.UNAVAILABLE)
                    else mutableState.value = state.value.copy(foodPricePreview = result.preview.copy(
                        input = result.preview.input.snapshot(), lines = result.preview.lines.toList()),
                        foodPricePreviewProblem = null)
                }
                is LocalFoodPricePreviewResult.Unavailable -> {
                    foodPreviewUnavailable(result.problem)
                    if (result.field != null && current(ticket)) mutableState.value = state.value.copy(issue = LocalInputIssue(result.field))
                }
            }
        }
    }

    private fun foodPreviewInput(form: LocalFormDraft): LocalFoodPreviewInput? {
        if (form.descriptor.action !in foodSelectionActions || form.foodServingCount()?.let { it in 1..8 } != true) return null
        val values = parseFields(form.descriptor.fields.filterIsInstance<LocalFormField.Scalar>(), form.values) ?: return null
        val group = form.descriptor.fields.filterIsInstance<LocalFormField.Lines>().singleOrNull() ?: return null
        if (form.lines.size !in group.minimumLines..group.maximumLines) return null
        val lines = form.lines.map { line ->
            val parsed = parseFields(group.columns, line.values, line.lineId) ?: return null
            val identities = try { line.foodPortionIdentityInput() }
            catch (_: IllegalArgumentException) { return null }
            LocalInputLine(line.lineId, parsed + listOfNotNull(identities))
        }
        return try { LocalFoodPreviewInput(form.descriptor.action, form.descriptor.version, values, lines) }
        catch (_: IllegalArgumentException) {
            mutableState.value = state.value.copy(issue = LocalInputIssue(tooLarge = true))
            null
        }
    }

    private fun foodPreviewUnavailable(problem: LocalServiceProblem) {
        mutableState.value = state.value.copy(foodPricePreview = null, foodPricePreviewProblem = problem)
        permissionFailure(problem)
    }

    /** First mint an identity, freeze input, and durably stage. This NEVER submits a mutation. */
    fun stage(token: NavigationToken) {
        if (state.value.managerOrderReview || !accepts(token) || state.value.working || state.value.recoveryRequired || frozen != null || state.value.referencePicker != null) return
        val form = state.value.form ?: return
        if (state.value.managerOrderContext && !form.descriptor.matchesManagerReassignment(state.value.search)) return
        if (!state.value.managerOrderContext && form.descriptor.action == LocalAction.ORDER_REASSIGN) return
        if (form.descriptor.openingAllocations != null && !state.value.openingCanSave) {
            mutableState.value = state.value.copy(issue = LocalInputIssue(LocalField.CONFIRMED))
            return
        }
        if (form.descriptor.confirmation != null && !form.confirmed) {
            mutableState.value = state.value.copy(issue = LocalInputIssue(LocalField.CONFIRMED))
            return
        }
        form.foodDeliveryInputIssue()?.let { issue ->
            mutableState.value = state.value.copy(issue = issue)
            return
        }
        val values = (parseFields(form.foodDraftFields(), form.values) ?: return).toMutableList()
        if (form.descriptor.openingAllocations != null) {
            // The saved input digest binds the complete-count confirmation. Omitted zero IDs
            // are determined by the exact approved group and these positive rows, not a new wire.
            values += LocalScalarInput(LocalField.CONFIRMED, LocalScalarValue.Flag(true))
        }
        val lineField = form.descriptor.fields.filterIsInstance<LocalFormField.Lines>().singleOrNull()
        if (form.descriptor.action in foodSelectionActions && form.foodServingCount()?.let { it in 1..8 } != true) {
            mutableState.value = state.value.copy(issue = LocalInputIssue(LocalField.FOOD_SERVING_COUNT))
            return
        }
        val lines = mutableListOf<LocalInputLine>()
        if (lineField != null) {
            if (form.lines.size !in lineField.minimumLines..lineField.maximumLines) return
            for (line in form.lines) {
                val parsed = parseFields(lineField.columns, line.values, line.lineId) ?: return
                val identities = if (form.descriptor.action in foodSelectionActions) {
                    try { line.foodPortionIdentityInput() }
                    catch (_: IllegalArgumentException) {
                        mutableState.value = state.value.copy(issue = LocalInputIssue(LocalField.FOOD_SERVING_COUNT, line.lineId))
                        return
                    }
                } else null
                lines += LocalInputLine(line.lineId, parsed + listOfNotNull(identities))
            }
        }
        val evidence = try { localArrivalEvidenceInputV2(form.descriptor, values) }
        catch (failure: LocalArrivalSelectionInputFailure) {
            mutableState.value = state.value.copy(issue = LocalInputIssue(failure.field))
            return
        }
        val intent = try { LocalCommandIntent(form.descriptor.reservedOperationId ?: OperationId.random(), form.descriptor.action, form.descriptor.version,
            state.value.reference, values, lineField?.field, lines, arrivalEvidence = evidence) }
        catch (_: IllegalArgumentException) {
            mutableState.value = state.value.copy(issue = LocalInputIssue(tooLarge = true))
            return
        }
        if (invoiceAction?.let { !intent.isInvoiceUiIntent(it) } == true) {
            mutableState.value = state.value.copy(stageProblem = LocalServiceProblem.INVALID_INPUT)
            return
        }
        if (foodCashFinancialOnly && !intent.isFoodCashFinancialUiIntent()) {
            mutableState.value = state.value.copy(stageProblem = LocalServiceProblem.INVALID_INPUT)
            return
        }
        if (foodChecksOnly && !intent.isFoodCheckUiIntent()) {
            mutableState.value = state.value.copy(stageProblem = LocalServiceProblem.INVALID_INPUT)
            return
        }
        if (foodTableAssignmentsOnly && !intent.isFoodTableAssignmentUiIntent()) {
            mutableState.value = state.value.copy(stageProblem = LocalServiceProblem.INVALID_INPUT)
            return
        }
        frozen = intent
        originalAction = intent.action
        mutableState.value = state.value.copy(operationId = intent.operationId, issue = null, stageProblem = null,
            operation = null, operationRead = null, recordedAcknowledged = false, acknowledgementProblem = null)
        stageFrozen()
    }

    private fun parseFields(fields: List<LocalFormField.Scalar>, values: Map<LocalField, LocalDraftValue>, lineId: DomainId? = null): List<LocalScalarInput>? {
        val parsed = mutableListOf<LocalScalarInput>()
        for (field in fields) when (val value = parseLocalInput(field, values[field.field] ?: LocalDraftValue())) {
            is LocalParsedValue.Value -> parsed += LocalScalarInput(field.field, value.value)
            LocalParsedValue.Omitted -> Unit
            LocalParsedValue.Invalid -> {
                mutableState.value = state.value.copy(issue = LocalInputIssue(field.field, lineId))
                return null
            }
        }
        return parsed
    }

    /** Uncertain staging can only replay the EXACT frozen body/ID; no editable retry path. */
    fun retryStage(token: NavigationToken) {
        if (!state.value.managerOrderReview && accepts(token) && !state.value.working && state.value.stageUncertain && state.value.operationRead == null && frozen != null) stageFrozen()
    }

    private fun stageFrozen() {
        val intent = frozen ?: return
        if (invoiceAction?.let { !intent.isInvoiceUiIntent(it) } == true) return
        if (foodCashFinancialOnly && !intent.isFoodCashFinancialUiIntent()) return
        if (foodChecksOnly && !intent.isFoodCheckUiIntent()) return
        if (foodTableAssignmentsOnly && !intent.isFoodTableAssignmentUiIntent()) return
        run(LocalWorkBusy.STAGING) { ticket ->
            val result = gateway.stage(intent)
            currentCoroutineContext().ensureActive()
            if (!current(ticket)) return@run
            when (result) {
                is LocalStageResult.Staged -> {
                    if (result.operation.operationId != intent.operationId || result.operation.action != intent.action ||
                        invoiceAction?.let { !result.operation.isInvoiceUiPending(it) } == true ||
                        foodCashFinancialOnly && !result.operation.isFoodCashFinancialUiPending() ||
                        foodChecksOnly && !result.operation.isFoodCheckUiPending() ||
                        foodTableAssignmentsOnly && !result.operation.isFoodTableAssignmentUiPending()) unresolved(intent.operationId)
                    else mutableState.value = state.value.copy(operation = LocalOperationResult.Pending(result.operation.copy(summary = result.operation.summary.toList())),
                        stageUncertain = false, pendingLoaded = false)
                }
                is LocalStageResult.NotStaged -> {
                    frozen = null // Only this explicit no-dispatch/no-staging contract permits editing.
                    mutableState.value = state.value.copy(operationId = null, operation = null, stageUncertain = false,
                        stageProblem = result.problem, issue = result.field?.let { LocalInputIssue(it) })
                    permissionFailure(result.problem)
                }
                is LocalStageResult.Uncertain -> {
                    // Even a mismatched reply cannot redirect recovery to its supplied identity.
                    unresolved(intent.operationId, staging = true)
                }
            }
        }
    }

    /** The saved original is selected before any separate approver authentication. */
    fun reviewActionApproval(operationId: OperationId, token: NavigationToken) {
        if (state.value.managerOrderReview || !accepts(token) || state.value.working ||
            session.lifecycle?.value != LocalSessionLifecycle.ACTIVE ||
            state.value.form?.dirty == true && !state.value.formFrozen ||
            frozen?.let { it.operationId != operationId } == true) return
        val saved = (state.value.operation as? LocalOperationResult.Pending)?.operation?.takeIf { it.operationId == operationId }
            ?: state.value.pending.singleOrNull { it.operationId == operationId } ?: return
        val approval = saved.actionApproval ?: return
        if (saved.phase == LocalPendingPhase.RECORDED_UNACKNOWLEDGED || saved.action !in localArrivalActionsV2) return
        actionApprovalEpoch++
        originalAction = saved.action
        mutableState.value = state.value.copy(operationId = operationId, operation = LocalOperationResult.Pending(saved),
            operationRead = null, recordedAcknowledged = false, acknowledgementProblem = null,
            actionApproval = LocalActionApprovalPresentation(operationId))
        val lookup = approval.phase !in setOf(LocalActionApprovalPhase.PREVIEW_REQUIRED, LocalActionApprovalPhase.READY_TO_CONFIRM)
        run(if (lookup) LocalWorkBusy.ACTION_APPROVAL_LOOKUP else LocalWorkBusy.ACTION_APPROVAL_PREVIEW) { ticket ->
            val result = if (lookup) gateway.lookupActionApproval(operationId) else gateway.previewActionApproval(operationId)
            currentCoroutineContext().ensureActive()
            if (current(ticket)) applyActionApproval(operationId, saved.action, result)
        }
    }

    private fun acceptsActionApproval(token: NavigationToken, allowRetiring: Boolean = false): Boolean = acceptsEntry(token) &&
        !state.value.attachmentReviewRequired && !configurationOwnsDraft && !retailPhysical.blocksParentWork &&
        !supplierPhysical.blocksParentWork && !state.value.purchasingDocuments.hasDraft &&
        session.lifecycle?.value == LocalSessionLifecycle.ACTIVE &&
        (!state.value.working || allowRetiring && state.value.busy == LocalWorkBusy.ACTION_APPROVAL_RETIRE) &&
        state.value.actionApproval != null

    fun confirmActionApproval(confirmed: Boolean, token: NavigationToken) {
        if (!acceptsActionApproval(token)) return
        val presentation = state.value.actionApproval ?: return
        if (!presentation.canConfirm || presentation.passwordEntry) return
        mutableState.value = state.value.copy(actionApproval = presentation.copy(
            confirmedDigest = presentation.approval?.review?.confirmationDigest.takeIf { confirmed }))
        syncGuard()
    }

    fun openActionApproverPassword(token: NavigationToken) {
        if (!acceptsActionApproval(token)) return
        val presentation = state.value.actionApproval ?: return
        if (!presentation.confirmed) return
        mutableState.value = state.value.copy(actionApproval = presentation.copy(passwordEntry = true))
        syncGuard()
    }

    fun closeActionApproverPassword(token: NavigationToken) {
        if (!acceptsActionApproval(token)) return
        mutableState.value = state.value.copy(actionApproval = state.value.actionApproval?.copy(passwordEntry = false))
        syncGuard()
    }

    /** A known no-issuance refusal may be reviewed again; an unknown issuance may not. */
    fun refreshActionApproval(token: NavigationToken) {
        if (!acceptsActionApproval(token)) return
        val presentation = state.value.actionApproval ?: return
        if (presentation.lookupRequired || presentation.approval?.phase !in setOf(
                null, LocalActionApprovalPhase.PREVIEW_REQUIRED, LocalActionApprovalPhase.READY_TO_CONFIRM,
                LocalActionApprovalPhase.REFUSED)) return
        val action = originalAction ?: return
        mutableState.value = state.value.copy(actionApproval = presentation.copy(passwordEntry = false,
            confirmedDigest = null, problem = null))
        run(LocalWorkBusy.ACTION_APPROVAL_PREVIEW) { ticket ->
            val result = gateway.previewActionApproval(presentation.operationId)
            currentCoroutineContext().ensureActive()
            if (current(ticket)) applyActionApproval(presentation.operationId, action, result)
        }
    }

    /** Caller and queued-job copies are cleared, including cancellation before the lazy job starts. */
    fun issueActionApproval(login: String, password: CharArray, token: NavigationToken) {
        var owned: CharArray? = null
        try {
            if (!acceptsActionApproval(token)) return
            val presentation = state.value.actionApproval ?: return
            val review = presentation.approval?.review ?: return
            if (!presentation.passwordEntry || !presentation.confirmed || password.size !in 4..1024 ||
                login.length !in 1..128) return
            owned = password.copyOf()
            val input = owned
            mutableState.value = state.value.copy(actionApproval = presentation.copy(passwordEntry = false,
                confirmedDigest = null, lookupRequired = true, problem = null))
            run(LocalWorkBusy.ACTION_APPROVAL_ISSUE, secret = input) { ticket ->
                val result = gateway.issueActionApproval(presentation.operationId, review.confirmationDigest, login, input)
                currentCoroutineContext().ensureActive()
                if (current(ticket)) applyActionApproval(presentation.operationId, review.action, result)
            }
            owned = null
        } finally { password.fill('\u0000'); owned?.fill('\u0000') }
    }

    /** Reconciles only the saved approval ID; never enters a new manager session or reissues. */
    fun lookupActionApproval(operationId: OperationId, token: NavigationToken) {
        if (state.value.busy == LocalWorkBusy.ACTION_APPROVAL_RETIRE) {
            lookupHeldActionApproval(operationId, token)
            return
        }
        if (!acceptsActionApproval(token)) return
        val presentation = state.value.actionApproval?.takeIf { it.operationId == operationId } ?: return
        val action = originalAction ?: return
        mutableState.value = state.value.copy(actionApproval = presentation.copy(passwordEntry = false,
            confirmedDigest = null, problem = null))
        run(LocalWorkBusy.ACTION_APPROVAL_LOOKUP) { ticket ->
            val result = gateway.lookupActionApproval(operationId)
            currentCoroutineContext().ensureActive()
            if (current(ticket)) applyActionApproval(operationId, action, result)
        }
    }

    /** Separate explicit cancellation; never restarts an unknown issuance or business operation. */
    fun retireActionApproval(token: NavigationToken) {
        requestActionApprovalRetirement(LocalActionApprovalPhase.ATTACHED, token)
    }

    /** A separate user request after received durable no-admission, never an automatic retry. */
    fun retryActionApprovalRetirement(token: NavigationToken) {
        requestActionApprovalRetirement(LocalActionApprovalPhase.RETIRE_RETRY_REQUIRED, token)
    }

    private fun requestActionApprovalRetirement(phase: LocalActionApprovalPhase, token: NavigationToken) {
        if (state.value.busy == LocalWorkBusy.ACTION_APPROVAL_RETIRE) {
            state.value.actionApproval?.operationId?.let { lookupHeldActionApproval(it, token) }
            return
        }
        if (!acceptsActionApproval(token)) return
        val presentation = state.value.actionApproval ?: return
        if (presentation.lookupRequired || presentation.approval?.phase != phase ||
            phase == LocalActionApprovalPhase.RETIRE_RETRY_REQUIRED && !presentation.canRetryRetirement) return
        val action = originalAction ?: return
        val snapshot = state.value
        val cached = snapshot.operation
        // Suppress the known-stale continuation hint even if this dialog is later closed
        // after a lost/refused reply. Only a fresh authoritative lookup may restore it.
        val held = if (cached is LocalOperationResult.Pending &&
            cached.operation.operationId == presentation.operationId)
            cached.copy(operation = cached.operation.copy(submitAllowed = false)) else cached
        mutableState.value = snapshot.copy(operation = held,
            pending = snapshot.pending.map { if (it.operationId == presentation.operationId)
                it.copy(submitAllowed = false) else it }, actionApproval = presentation.copy(passwordEntry = false,
            confirmedDigest = null, lookupRequired = true, problem = null))
        run(LocalWorkBusy.ACTION_APPROVAL_RETIRE) { ticket ->
            val result = gateway.retireActionApproval(presentation.operationId)
            currentCoroutineContext().ensureActive()
            if (current(ticket)) applyActionApproval(presentation.operationId, action, result)
        }
    }

    /** A separate protected read while the original retirement response is held. */
    fun canLookupActionApprovalDuringRetirement(token: NavigationToken): Boolean =
        acceptsActionApproval(token, allowRetiring = true) && state.value.busy == LocalWorkBusy.ACTION_APPROVAL_RETIRE &&
        state.value.actionApproval?.lookupRequired == true && !state.value.actionApprovalLookupWorking

    private fun lookupHeldActionApproval(operationId: OperationId, token: NavigationToken) {
        if (!canLookupActionApprovalDuringRetirement(token) || heldActionApprovalLookup != null) return
        val presentation = state.value.actionApproval?.takeIf { it.operationId == operationId } ?: return
        val primary = work ?: return
        val action = originalAction ?: return
        val ticket = RequestOwner(activation, runtime.navigator.token(), approvalEpoch = actionApprovalEpoch)
        fun deliveryCurrent(): Boolean = current(ticket) && work === primary &&
            state.value.busy == LocalWorkBusy.ACTION_APPROVAL_RETIRE &&
            state.value.actionApproval?.let { it.operationId == operationId && it.lookupRequired } == true
        mutableState.value = state.value.copy(actionApprovalLookupWorking = true,
            actionApproval = presentation.copy(passwordEntry = false, confirmedDigest = null, problem = null))
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (!deliveryCurrent()) return@launch
                // The gateway acquires its own current credential lease; the primary's
                // suspended authorization snapshot is never borrowed by this read.
                val result = gateway.lookupActionApproval(operationId)
                currentCoroutineContext().ensureActive()
                if (deliveryCurrent()) applyActionApproval(operationId, action, result)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (deliveryCurrent()) applyActionApproval(operationId, action,
                    LocalActionApprovalResult.Unavailable(operationId, LocalServiceProblem.UNAVAILABLE))
            } finally {
                if (heldActionApprovalLookup === currentCoroutineContext()[Job]) {
                    heldActionApprovalLookup = null
                    if (current(ticket.activation))
                        mutableState.value = state.value.copy(actionApprovalLookupWorking = false)
                }
            }
        }
        heldActionApprovalLookup = job
        job.start()
    }

    /** Opens a fresh review only after authentic retirement; old original evidence is kept. */
    fun beginActionApprovalReplacement(token: NavigationToken) {
        if (!acceptsActionApproval(token)) return
        val presentation = state.value.actionApproval ?: return
        if (presentation.lookupRequired || presentation.approval?.phase != LocalActionApprovalPhase.RETIRED) return
        val action = originalAction ?: return
        mutableState.value = state.value.copy(actionApproval = presentation.copy(passwordEntry = false,
            confirmedDigest = null, problem = null))
        run(LocalWorkBusy.ACTION_APPROVAL_REPLACE) { ticket ->
            val result = gateway.beginActionApprovalReplacement(presentation.operationId)
            currentCoroutineContext().ensureActive()
            if (current(ticket)) applyActionApproval(presentation.operationId, action, result)
        }
    }

    fun closeActionApproval(token: NavigationToken) {
        if (!acceptsEntry(token) || state.value.working || state.value.actionApproval == null) return
        actionApprovalEpoch++
        mutableState.value = state.value.copy(actionApproval = null, actionApprovalLookupWorking = false)
        syncGuard()
    }

    fun actionApprovalDisplayCurrent(token: NavigationToken): Boolean = acceptsEntry(token) &&
        !state.value.attachmentReviewRequired && session.lifecycle?.value == LocalSessionLifecycle.ACTIVE

    private fun applyActionApproval(expected: OperationId, action: LocalAction, result: LocalActionApprovalResult) {
        when (result) {
            is LocalActionApprovalResult.Available -> {
                val operation = result.operation
                val approval = operation.actionApproval
                val review = approval?.review
                if (operation.operationId != expected || operation.action != action || approval == null ||
                    review != null && (review.operationId != expected || review.action != action ||
                        review.requestorId != session.display.actorId)) {
                    actionApprovalWorkFailed(state.value.busy)
                    return
                }
                mutableState.value = state.value.copy(operation = LocalOperationResult.Pending(operation),
                    pending = state.value.pending.map { if (it.operationId == expected) operation else it },
                    actionApproval = LocalActionApprovalPresentation(expected, approval,
                        lookupRequired = approval.phase in setOf(LocalActionApprovalPhase.ISSUE_UNRESOLVED, LocalActionApprovalPhase.RETIRE_UNRESOLVED)))
            }
            is LocalActionApprovalResult.Unavailable -> {
                val problem = if (result.operationId == expected) result.problem else LocalServiceProblem.STORAGE_UNCERTAIN
                mutableState.value = state.value.copy(actionApproval = state.value.actionApproval?.copy(
                    passwordEntry = false, confirmedDigest = null, problem = problem))
                permissionFailure(problem)
            }
        }
    }

    private fun actionApprovalWorkFailed(kind: LocalWorkBusy) {
        val completed = state.value.actionApproval?.approval?.phase in setOf(LocalActionApprovalPhase.CONSUMED,
            LocalActionApprovalPhase.RETIRED, LocalActionApprovalPhase.RETIRE_RETRY_REQUIRED)
        mutableState.value = state.value.copy(actionApproval = state.value.actionApproval?.copy(
            passwordEntry = false, confirmedDigest = null, problem = LocalServiceProblem.UNAVAILABLE,
            lookupRequired = state.value.actionApproval?.lookupRequired == true || !completed &&
                kind in setOf(LocalWorkBusy.ACTION_APPROVAL_ISSUE, LocalWorkBusy.ACTION_APPROVAL_RETIRE)))
    }

    fun submit(operationId: OperationId, token: NavigationToken) {
        if (state.value.managerOrderReview || !accepts(token) || state.value.working || state.value.form?.dirty == true && !state.value.formFrozen) return
        if (frozen != null && frozen?.operationId != operationId) return
        val currentOperation = state.value.operation
        if (state.value.operationId == operationId && currentOperation is LocalOperationResult.Unresolved) return
        val saved = (currentOperation as? LocalOperationResult.Pending)?.operation?.takeIf { it.operationId == operationId }
            ?: state.value.pending.firstOrNull { it.operationId == operationId } ?: return
        if (invoiceAction?.let { !saved.isInvoiceUiPending(it) } == true) return
        if (foodCashFinancialOnly && !saved.isFoodCashFinancialUiPending()) return
        if (!saved.submitAllowed || saved.phase == LocalPendingPhase.RECORDED_UNACKNOWLEDGED ||
            saved.summary.isEmpty() && frozen?.operationId != operationId) return
        originalAction = saved.action
        mutableState.value = state.value.copy(operationId = operationId, operation = LocalOperationResult.Pending(saved),
            operationRead = null, recordedAcknowledged = false, acknowledgementProblem = null)
        run(LocalWorkBusy.SUBMITTING) { ticket ->
            val result = gateway.submit(operationId)
            currentCoroutineContext().ensureActive()
            if (current(ticket)) applyOperation(operationId, result)
        }
    }

    /** Editing read input neither selects an original nor acknowledges the displayed result. */
    fun changeOriginalOperationId(value: String, token: NavigationToken) {
        if (!acceptsEdit(token) || state.value.working || value.length > 64) return
        mutableState.value = state.value.copy(originalLookupInput = value, originalLookupProblem = null)
    }

    /** Read an archived original without staging an intent or replacing unresolved work. */
    fun lookupOriginal(token: NavigationToken) {
        if (!accepts(token) || !state.value.canLookupOriginal || frozen != null) return
        // Only an acknowledged selection can reach this point. Clear it before even parsing
        // the new input: an old result is not completion of this new protected read.
        originalAction = null
        mutableState.value = state.value.copy(operationId = null, operation = null, operationRead = null,
            recordedAcknowledged = false, acknowledgementProblem = null, originalLookupProblem = null)
        val id = try { OperationId(state.value.originalLookupInput.trim()) }
        catch (_: IllegalArgumentException) {
            originalLookupUnavailable(LocalServiceProblem.INVALID_INPUT)
            return
        }
        run(LocalWorkBusy.ORIGINAL_LOOKUP) { ticket ->
            val result = gateway.lookup(id)
            currentCoroutineContext().ensureActive()
            if (!current(ticket)) return@run
            val recorded = result as? LocalOperationResult.Recorded
            val action = recorded?.originalAction
            if (recorded == null || action == null || !originalLookupActionCompatible(action) ||
                !matchesOperation(id, recorded, action)) {
                originalLookupUnavailable((result as? LocalOperationResult.Unresolved)
                    ?.takeIf { it.operationId == id }?.problem ?: LocalServiceProblem.UNAVAILABLE)
                return@run
            }
            // The current gateway supplied the action from its validated saved request.
            // Never infer it from a supplier's current record, label or caller-entered ID.
            originalAction = action
            mutableState.value = state.value.copy(operationId = id)
            applyOperation(id, recorded)
        }
    }

    private fun originalLookupActionCompatible(action: LocalAction): Boolean = action.canAppearIn(state.value.workflow) &&
        when (session.display.workspaceMode) {
            LocalSessionWorkspaceMode.POLICY_FILTERED -> true
            LocalSessionWorkspaceMode.SUPPLIER_ONLY -> action in setOf(LocalAction.SUPPLIER_CREATE, LocalAction.SUPPLIER_UPDATE)
            LocalSessionWorkspaceMode.INVOICE_CAPTURE_ONLY -> action == LocalAction.SUPPLIER_INVOICE_CAPTURE
            LocalSessionWorkspaceMode.INVOICE_MATCHING_ONLY -> action == LocalAction.SUPPLIER_INVOICE_MATCH_RECOGNIZE
            LocalSessionWorkspaceMode.WAREHOUSE_ONLY -> action == LocalAction.WAREHOUSE_CONFIGURE
            LocalSessionWorkspaceMode.STOCK_TRANSFERS_ONLY -> action == LocalAction.WAREHOUSE_TRANSFER
            LocalSessionWorkspaceMode.KITCHEN_ONLY -> action in setOf(LocalAction.FOOD_STATION_START, LocalAction.FOOD_STATION_READY)
            LocalSessionWorkspaceMode.RETAIL_ORDERS_ONLY -> action in setOf(LocalAction.ORDER_CREATE, LocalAction.ORDER_REPLACE, LocalAction.ORDER_REASSIGN)
            LocalSessionWorkspaceMode.PAYMENT_CORRECTIONS_ONLY -> action == LocalAction.PAYMENT_CLASSIFICATION_CORRECT
            LocalSessionWorkspaceMode.CUSTOMER_RECEIPTS_ONLY -> action == LocalAction.RETAIL_CUSTOMER_RECEIPT
            LocalSessionWorkspaceMode.FOOD_CHECKS_ONLY -> action in foodCheckUiActions
            LocalSessionWorkspaceMode.FOOD_CASH_FINANCIAL_ONLY -> action in foodCashFinancialUiActions
            LocalSessionWorkspaceMode.FOOD_TABLE_ASSIGNMENTS_ONLY -> action == LocalAction.FOOD_ASSIGN_TABLE
            LocalSessionWorkspaceMode.AUTHENTICATION_ONLY, LocalSessionWorkspaceMode.REPORTS_ONLY -> false
        }

    private fun originalLookupUnavailable(problem: LocalServiceProblem) {
        // A failed read of caller-entered text is not a new durable pending operation.
        // Existing journal history remains discoverable through its ordinary recovery path.
        mutableState.value = state.value.copy(originalLookupProblem = problem)
        permissionFailure(problem)
    }

    fun lookup(operationId: OperationId, token: NavigationToken) {
        if (state.value.managerOrderReview || !accepts(token) || state.value.working || state.value.form?.dirty == true && !state.value.formFrozen) return
        if (frozen != null && frozen?.operationId != operationId) return
        if (state.value.operationId != operationId && state.value.pending.none { it.operationId == operationId }) return
        if (state.value.operationId != operationId) originalAction = state.value.pending.firstOrNull { it.operationId == operationId }?.action
        mutableState.value = state.value.copy(operationId = operationId,
            operationRead = state.value.operationRead?.takeIf { state.value.operationId == operationId }?.copy(problem = null),
            recordedAcknowledged = false, acknowledgementProblem = null)
        run(LocalWorkBusy.LOOKUP) { ticket ->
            val result = gateway.lookup(operationId)
            currentCoroutineContext().ensureActive()
            if (current(ticket)) applyOperation(operationId, result)
        }
    }

    /** Explicit acknowledgement of the currently displayed result, never a financial mutation. */
    fun acknowledgeRecorded(operationId: OperationId, token: NavigationToken) {
        if (state.value.managerOrderReview || !accepts(token) || state.value.working || state.value.recordedAcknowledged) return
        val recorded = state.value.operation as? LocalOperationResult.Recorded ?: return
        if (state.value.operationId != operationId || recorded.operationId != operationId) return
        if (foodCashFinancialOnly && !matchesOperation(operationId, recorded, originalAction)) return
        mutableState.value = state.value.copy(acknowledgementProblem = null)
        run(LocalWorkBusy.ACKNOWLEDGING) { ticket ->
            val acknowledgement = gateway.acknowledgeRecorded(operationId)
            currentCoroutineContext().ensureActive()
            if (!current(ticket)) return@run
            when (acknowledgement) {
                is LocalAcknowledgementResult.Acknowledged -> {
                    if (acknowledgement.operationId != operationId) {
                        acknowledgementUnavailable(LocalServiceProblem.UNAVAILABLE)
                        return@run
                    }
                    mutableState.value = state.value.copy(recordedAcknowledged = true, acknowledgementProblem = null,
                        pending = emptyList(), pendingLoaded = false, pendingCursor = null, pendingPageIsFirst = true)
                    recordedFollowUp(recorded)?.let { reference ->
                        supplierPhysical.deactivate()
                        retailPhysical.deactivate()
                        val purchasingSection = when (reference.kind) {
                            LocalRecordKind.PURCHASE -> LocalPurchasingSection.ORDERS
                            LocalRecordKind.PURCHASE_INVOICE -> LocalPurchasingSection.INVOICES
                            LocalRecordKind.OPENING_SUPPLIER_OBLIGATION -> LocalPurchasingSection.OPENING_OBLIGATIONS
                            else -> state.value.purchasingQuery.section
                        }
                        mutableState.value = state.value.copy(reference = reference, search = "", pricePreview = null, priceDraft = null, foodPricePreview = null, foodPricePreviewProblem = null,
                            purchasingQuery = state.value.purchasingQuery.copy(section = purchasingSection),
                            returnQuery = if (reference.kind in setOf(LocalRecordKind.RETURN_CASE, LocalRecordKind.RETURN_STOCK))
                                LocalReturnUiQuery() else state.value.returnQuery,
                            cashQuery = if (state.value.workflow == LocalWorkflow.CASH) state.value.cashQuery.copy(section = when {
                                originalAction in setOf(LocalAction.CASH_REVIEW_CLOSE, LocalAction.CASH_ADD_CLOSE_NOTE) -> LocalCashSection.CLOSED_REVIEW
                                reference.kind == LocalRecordKind.TRANSFER -> LocalCashSection.TRANSFER
                                reference.kind == LocalRecordKind.CASH_SESSION -> LocalCashSection.OWN_SESSION
                                else -> LocalCashSection.MY_CASH
                            }, showCounts = false) else state.value.cashQuery)
                        activateRetailPhysical(state.value.workflow, reference, state.value.foodQuery)
                        activateSupplierPhysical()
                    }
                    recordedFoodFollowUp(recorded)?.let { food ->
                        supplierPhysical.deactivate()
                        retailPhysical.deactivate()
                        mutableState.value = state.value.copy(foodQuery = food, reference = null, search = "",
                            page = null, readProblem = null, pricePreview = null, priceDraft = null, foodPricePreview = null, foodPricePreviewProblem = null, foodUnavailable = null)
                        activateRetailPhysical(state.value.workflow, null, food)
                        activateSupplierPhysical()
                    }
                    val pending = try { gateway.pending() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { LocalPendingResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
                    currentCoroutineContext().ensureActive()
                    if (!current(ticket)) return@run
                    applyPending(pending, null)
                    if (!current(ticket)) return@run
                    val query = currentQuery()
                    val read = try { gateway.read(query) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { LocalReadResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
                    currentCoroutineContext().ensureActive()
                    if (!current(ticket)) return@run
                    when (read) {
                        is LocalReadResult.Available -> applyPage(query, read.page)
                        is LocalReadResult.Unavailable -> readUnavailable(read.problem)
                    }
                }
                is LocalAcknowledgementResult.Unresolved -> acknowledgementUnavailable(
                    if (acknowledgement.operationId == operationId) acknowledgement.problem else LocalServiceProblem.UNAVAILABLE)
            }
        }
    }

    /** Only the exact recorded reference is adopted, and only after explicit durable acknowledgement. */
    private fun recordedFollowUp(recorded: LocalOperationResult.Recorded): LocalRecordReference? {
        if (originalAction == LocalAction.RETURN_STOCK_POST) return recordedReturnStockFollowUp(recorded)
        if (originalAction == LocalAction.RETAIL_CUSTOMER_RECEIPT && recorded.decision == LocalRecordedDecision.RECORDED_ONLY) {
            val reference = recorded.result?.takeIf { it.selectable }?.reference ?: return null
            return reference.takeIf { state.value.workflow in setOf(LocalWorkflow.ORDERS, LocalWorkflow.CHECKOUT) &&
                it.kind == LocalRecordKind.CUSTOMER_RECEIPT_EVENT && it.parentId != null && it.revision == null &&
                it.id.value == recorded.operationId.value }
        }
        if (recorded.decision != LocalRecordedDecision.APPLIED) return null
        val record = recorded.result?.takeIf { it.selectable } ?: return null
        val reference = record.reference
        val valid = when (originalAction) {
            LocalAction.PAYMENT_CLASSIFICATION_CORRECT -> state.value.workflow in setOf(LocalWorkflow.CHECKOUT, LocalWorkflow.ORDERS) &&
                reference.kind == LocalRecordKind.PAYMENT_CORRECTION_HISTORY && reference.parentId != null && reference.revision == null
            LocalAction.CASH_OPEN, LocalAction.CASH_BEGIN_CLOSE, LocalAction.CASH_CANCEL_CLOSE, LocalAction.CASH_SUBMIT_CLOSE,
            LocalAction.CASH_REVIEW_CLOSE, LocalAction.CASH_ADD_CLOSE_NOTE, LocalAction.CASH_NON_SALE, LocalAction.CASH_RECORD_COUNT,
            LocalAction.CASH_SUBMIT_COUNTED -> state.value.workflow == LocalWorkflow.CASH &&
                reference.kind == LocalRecordKind.CASH_SESSION && reference.parentId == null && reference.revision != null
            LocalAction.CASH_DROP_TO_SAFE, LocalAction.CASH_RECEIVE_SAFE, LocalAction.CASH_DELIVER_COUNTED -> state.value.workflow == LocalWorkflow.CASH &&
                reference.kind == LocalRecordKind.TRANSFER && reference.parentId == null && reference.revision != null
            LocalAction.DRAWER_CONFIGURE, LocalAction.CUSTODIAN_CONFIGURE, LocalAction.SAFE_CONFIGURE ->
                state.value.workflow == LocalWorkflow.CASH && reference.parentId == null && reference.revision != null &&
                    reference.kind == when (originalAction) {
                        LocalAction.DRAWER_CONFIGURE -> LocalRecordKind.DRAWER
                        LocalAction.CUSTODIAN_CONFIGURE -> LocalRecordKind.CUSTODIAN
                        else -> LocalRecordKind.SAFE
                    }
            LocalAction.RETURN_CASE_RECORD, LocalAction.RETURN_CASE_RECEIPT, LocalAction.RETURN_CASE_QUALIFY, LocalAction.RETURN_CASE_ACCEPT ->
                state.value.workflow == LocalWorkflow.RETURNS && reference.kind == LocalRecordKind.RETURN_CASE &&
                    reference.parentId == null && reference.revision == null
            LocalAction.RECEIVING_APPROVE -> state.value.workflow == LocalWorkflow.INVENTORY &&
                state.value.inventorySection == LocalInventorySection.OPENING &&
                reference.kind == LocalRecordKind.RECEIVING_BASIS && reference.parentId == null && reference.revision != null
            LocalAction.PURCHASE_APPROVE -> state.value.workflow == LocalWorkflow.PURCHASING &&
                reference.kind == LocalRecordKind.PURCHASE && reference.parentId == null && reference.revision == null
            LocalAction.SUPPLIER_INVOICE_RECORD, LocalAction.SUPPLIER_INVOICE_CAPTURE,
            LocalAction.SUPPLIER_INVOICE_MATCH_RECOGNIZE -> state.value.workflow == LocalWorkflow.PURCHASING &&
                reference.kind == LocalRecordKind.PURCHASE_INVOICE && reference.parentId == null && reference.revision == null
            LocalAction.SUPPLIER_OPENING_RECORD -> state.value.workflow == LocalWorkflow.PURCHASING &&
                reference.kind == LocalRecordKind.OPENING_SUPPLIER_OBLIGATION && reference.parentId == null && reference.revision == null
            LocalAction.PRICE_SET, LocalAction.PRICE_RESET -> state.value.workflow == LocalWorkflow.CATALOG &&
                reference.kind == LocalRecordKind.PRICE_RULE && reference.parentId != null
            LocalAction.PRICE_POLICY_RECEIVE, LocalAction.PRICE_POLICY_ACTIVATE -> state.value.workflow == LocalWorkflow.CATALOG &&
                reference.kind == LocalRecordKind.PRICE_POLICY_PUBLICATION && reference.parentId == null && reference.revision == null
            LocalAction.SERIAL_REGISTRATION_ACTIVATE -> state.value.workflow == LocalWorkflow.CATALOG &&
                reference.kind == LocalRecordKind.SERIAL_REGISTRATION_PUBLICATION && reference.parentId == null && reference.revision == null
            LocalAction.CATALOG_RELEASE_ACTIVATE -> state.value.workflow == LocalWorkflow.CATALOG &&
                reference.kind == LocalRecordKind.CATALOG_RELEASE_PUBLICATION && reference.parentId == null && reference.revision == null
            LocalAction.PRICE_HISTORY_INDEX -> state.value.workflow == LocalWorkflow.CATALOG &&
                reference.kind == LocalRecordKind.PRICE_HISTORY_INDEX && reference.parentId == null && reference.revision == null &&
                reference.id == session.display.branchId
            LocalAction.COUNT_APPROVE_SURPLUS -> state.value.workflow == LocalWorkflow.INVENTORY &&
                reference.kind == LocalRecordKind.COUNT_SURPLUS_BASIS && reference.parentId != null && reference.revision != null
            LocalAction.COUNT_CAPTURE, LocalAction.COUNT_POST -> state.value.workflow == LocalWorkflow.INVENTORY && reference.kind == LocalRecordKind.COUNT
            LocalAction.WAREHOUSE_TRANSFER -> state.value.workflow == LocalWorkflow.INVENTORY &&
                reference.kind == LocalRecordKind.TRANSFER && reference.parentId == null && reference.revision == null
            LocalAction.ARRIVAL_RECORD, LocalAction.ARRIVAL_QUALIFY, LocalAction.ARRIVAL_REJECT, LocalAction.ARRIVAL_DISPATCH ->
                state.value.workflow == LocalWorkflow.RECEIVING && reference.kind in setOf(LocalRecordKind.ARRIVAL, LocalRecordKind.ARRIVAL_V2) &&
                    reference.revision != null && reference.parentId == null
            LocalAction.RECEIPT_POST, LocalAction.STOCK_OPENING -> state.value.workflow in setOf(LocalWorkflow.RECEIVING, LocalWorkflow.INVENTORY) &&
                reference.kind == LocalRecordKind.RECEIPT
            // Inspection returns an inspection ID, not a receipt ID. Re-read the current original
            // receipt/line when retained; never infer a receipt from display text after cold recovery.
            else -> false
        }
        return reference.takeIf { valid }
    }

    private fun recordedReturnStockFollowUp(recorded: LocalOperationResult.Recorded): LocalRecordReference? {
        if (state.value.workflow != LocalWorkflow.RETURNS) return null
        val reference = recordedReturnStockReference(recorded) ?: return null
        return reference.takeIf { state.value.reference?.let { current -> current != reference } != true }
    }

    private fun recordedReturnStockReference(recorded: LocalOperationResult.Recorded,
        action: LocalAction? = originalAction, expected: OperationId? = state.value.operationId): LocalRecordReference? {
        if (action != LocalAction.RETURN_STOCK_POST || recorded.decision != LocalRecordedDecision.APPLIED ||
            recorded.operationId != expected) return null
        // Recovery receives this binding only from strict gateway admission of the saved
        // original stock request and exact committed result. Generic display IDs are insufficient.
        val stock = recorded.returnStock ?: return null
        if (stock.operationId != recorded.operationId) return null
        val reference = LocalRecordReference(LocalRecordKind.RETURN_STOCK, stock.returnId, parentId = stock.caseId)
        if (recorded.result?.takeIf { it.selectable }?.reference != reference) return null
        frozen?.let { original ->
            if (original.action != LocalAction.RETURN_STOCK_POST || original.operationId != stock.operationId ||
                original.reference?.let { it != reference } == true) return null
            fun id(field: LocalField): DomainId? =
                (original.values.singleOrNull { it.field == field }?.value as? LocalScalarValue.Identifier)?.value
            val expectedRevision = (original.values.singleOrNull { it.field == LocalField.EXPECTED_DISPOSITION_REVISION }
                ?.value as? LocalScalarValue.Integer)?.value
            if (id(LocalField.SALE_ID) != stock.saleId || id(LocalField.CASE_ID) != stock.caseId ||
                id(LocalField.RETURN_ID) != stock.returnId || id(LocalField.RESTORATION_ID) != stock.restorationId ||
                expectedRevision != stock.expectedDispositionRevision.value) return null
        }
        return reference
    }

    private fun recordedFoodFollowUp(recorded: LocalOperationResult.Recorded): LocalFoodUiQuery? {
        if (foodCashFinancialOnly) {
            if (!matchesOperation(recorded.operationId, recorded, originalAction)) return null
            return recorded.foodCashFinancial?.followUp
        }
        // ACK resolves this original only. Its board origin chooses a new protected read,
        // including after a recorded refusal; it never supplies task/result authority.
        if (state.value.workflow == LocalWorkflow.KITCHEN &&
            originalAction in setOf(LocalAction.FOOD_STATION_START, LocalAction.FOOD_STATION_READY))
            state.value.kitchenBoardOrigin?.let { return it }
        if (!state.value.workflow.usesFoodQueries || originalAction?.workflow != LocalWorkflow.FOOD ||
            originalAction?.canAppearIn(state.value.workflow) != true ||
            recorded.decision != LocalRecordedDecision.APPLIED) return null
        // Financial results carry the exact document selector, never a storage generation in
        // a domain-revision field. Each post-ACK read authorizes that document independently.
        val reference = recorded.result?.reference
        if (reference != null && reference.revision == null && reference.parentId == null) when (originalAction) {
            LocalAction.FOOD_BEGIN_COLLECTION, LocalAction.FOOD_PAYMENT_APPLY -> return reference.takeIf { it.kind == LocalRecordKind.FOOD_COLLECTION }
                ?.let { LocalFoodUiQuery.Collection(it.id) }
            LocalAction.FOOD_FINALIZE_PAID -> return reference.takeIf { it.kind == LocalRecordKind.FOOD_SALE }
                ?.let { LocalFoodUiQuery.Receipt(it.id) }
            LocalAction.FOOD_CANCEL_WHOLE_UNSENT -> return reference.takeIf { it.kind == LocalRecordKind.FOOD_REFUND }
                ?.let { LocalFoodUiQuery.Refund(it.id) }
            LocalAction.FOOD_RECORD_QUALITY_CLAIM -> return reference.takeIf { it.kind == LocalRecordKind.FOOD_QUALITY_CLAIM }
                ?.let { LocalFoodUiQuery.Claim(it.id) }
            LocalAction.FOOD_SEND_RESERVED, LocalAction.FOOD_PAID_PICKUP -> return reference.takeIf { it.kind == LocalRecordKind.FOOD_ORDER }
                ?.let { LocalFoodUiQuery.Check(it.id) }
            LocalAction.FOOD_CLOSE_RESOLVED -> return LocalFoodUiQuery.MyChecks().takeIf { reference.kind == LocalRecordKind.FOOD_ORDER }
            else -> Unit
        }
        val food = recorded.food ?: return null
        return food.followUp?.takeIf { follow -> follow.canAppearIn(state.value.workflow) && when (originalAction) {
            LocalAction.FOOD_STATION_START, LocalAction.FOOD_STATION_READY, LocalAction.FOOD_CONFIRM_UNSTARTED,
            LocalAction.FOOD_DISCARD_STARTED -> follow is LocalFoodUiQuery.StationTasks
            else -> follow is LocalFoodUiQuery.Check && follow.orderId == food.orderId
        } }
    }

    private fun acknowledgementUnavailable(problem: LocalServiceProblem) {
        // Keep the known Recorded outcome and any known acknowledgement. A failed follow-up
        // read is not an unknown business outcome or an instruction to acknowledge again.
        mutableState.value = if (state.value.recordedAcknowledged)
            state.value.copy(page = null, readProblem = problem, pendingLoaded = false)
        else state.value.copy(acknowledgementProblem = problem, pendingLoaded = false)
        permissionFailure(problem)
    }

    private fun applyOperation(expected: OperationId, result: LocalOperationResult) {
        val retained = (state.value.operation as? LocalOperationResult.Recorded)?.takeIf { it.operationId == expected }
        val retired = state.value.operationRead?.takeIf { state.value.operationId == expected && it.recorded }
        if (retired != null && result !is LocalOperationResult.Recorded) {
            // A failed protected reread does not undo the earlier known durable result. Keep
            // only its original identity/knowledge, never restore the old returned details.
            val problem = (result as? LocalOperationResult.Unresolved)?.problem ?: LocalServiceProblem.UNAVAILABLE
            mutableState.value = state.value.copy(operation = LocalOperationResult.Unresolved(expected, problem),
                operationRead = retired.copy(problem = problem), pendingLoaded = false)
            permissionFailure(problem)
            return
        }
        if (retained != null && result !is LocalOperationResult.Recorded) {
            // A later failed read cannot undo a known durable result or suggest repeating money.
            acknowledgementUnavailable((result as? LocalOperationResult.Unresolved)?.problem ?: LocalServiceProblem.UNAVAILABLE)
            return
        }
        if (!matchesOperation(expected, result, originalAction)) {
            unresolved(expected)
            return
        }
        when (result) {
            is LocalOperationResult.Recorded -> {
                frozen = null
                mutableState.value = state.value.copy(operation = result.copy(result = result.result?.let { it.copy(values = it.values.toList()) },
                    food = result.food?.let { it.copy(portions = it.portions.toList()) }),
                    form = null, formInput = null, openingCounts = null, issue = null, page = null, priceDraft = null, pricePreview = null, foodPricePreview = null, foodPricePreviewProblem = null,
                    operationRead = null, stageUncertain = false,
                    recordedAcknowledged = false, acknowledgementProblem = null,
                    pending = state.value.pending.map { if (it.operationId == expected) it.copy(
                        phase = LocalPendingPhase.RECORDED_UNACKNOWLEDGED, recordedDecision = result.decision, submitAllowed = false) else it },
                    pendingLoaded = false)
            }
            is LocalOperationResult.Pending -> mutableState.value = state.value.copy(
                operation = result.copy(operation = result.operation.copy(summary = result.operation.summary.toList())),
                operationRead = null, stageUncertain = false)
            is LocalOperationResult.Unresolved -> {
                mutableState.value = state.value.copy(operation = result, operationRead = null)
                permissionFailure(result.problem)
            }
        }
    }

    private fun matchesOperation(expected: OperationId, result: LocalOperationResult, action: LocalAction?): Boolean {
        val actual = when (result) {
            is LocalOperationResult.Recorded -> result.operationId
            is LocalOperationResult.Pending -> result.operation.operationId
            is LocalOperationResult.Unresolved -> result.operationId
        }
        if (actual != expected || result is LocalOperationResult.Pending && action != null && result.operation.action != action ||
            result is LocalOperationResult.Recorded && result.originalAction != null && result.originalAction != action ||
            invoiceAction?.let { expectedAction -> action != expectedAction ||
                !result.isInvoiceUiResult(expectedAction, requireOriginalAction = true) } == true ||
            paymentCorrectionsOnly && !result.isPaymentCorrectionUiResult() ||
            foodChecksOnly && !result.isFoodCheckUiResult(action, frozen) ||
            foodCashFinancialOnly && (action !in foodCashFinancialUiActions || !result.isFoodCashFinancialUiResult(action, frozen) ||
                result is LocalOperationResult.Recorded && result.foodCashFinancial?.originalActorId != session.display.actorId) ||
            foodTableAssignmentsOnly && (action != LocalAction.FOOD_ASSIGN_TABLE || !result.isFoodTableAssignmentUiResult(action, frozen))) return false
        if (result is LocalOperationResult.Recorded &&
            (result.returnStock != null && action != LocalAction.RETURN_STOCK_POST ||
                action == LocalAction.RETURN_STOCK_POST && result.decision == LocalRecordedDecision.APPLIED &&
                recordedReturnStockReference(result, action, expected) == null)) return false
        // Match still-frozen input before releasing it. Cold recovery instead uses the
        // gateway's validated immutable saved-request/result binding.
        return true
    }

    private fun unresolved(id: OperationId, staging: Boolean = false) {
        state.value.operationRead?.takeIf { it.recorded && state.value.operationId == id }?.let { retired ->
            mutableState.value = state.value.copy(operation = LocalOperationResult.Unresolved(id, LocalServiceProblem.UNAVAILABLE),
                operationRead = retired.copy(problem = LocalServiceProblem.UNAVAILABLE), pendingLoaded = false)
            return
        }
        if ((state.value.operation as? LocalOperationResult.Recorded)?.operationId == id) {
            acknowledgementUnavailable(LocalServiceProblem.UNAVAILABLE)
            return
        }
        mutableState.value = state.value.copy(operationId = id,
            operation = LocalOperationResult.Unresolved(id, LocalServiceProblem.UNAVAILABLE), operationRead = null,
            stageUncertain = state.value.stageUncertain || staging, pendingLoaded = false)
    }

    private fun readUnavailable(problem: LocalServiceProblem) {
        if (problem == LocalServiceProblem.DENIED) {
            permissionFailure(problem)
            return
        }
        val cleanup = LocalSessionCleanup()
        cleanup.attempt { supplierPhysical.deactivate() }
        mutableState.value = state.value.copy(page = null, form = null, foodPricePreview = null, foodPricePreviewProblem = null, openingCounts = null, referencePicker = null, actionApproval = null, actionApprovalLookupWorking = false, readProblem = problem,
            foodUnavailable = foodUnavailableReason(problem).takeIf { state.value.workflow.usesFoodQueries })
        cleanup.attempt { permissionFailure(problem) }
        if (!cleanup.failed && problem != LocalServiceProblem.SESSION_ENDED && problem != LocalServiceProblem.DENIED)
            activateSupplierPhysical()
        cleanup.throwIfFailed()
    }

    private fun permissionFailure(problem: LocalServiceProblem) {
        if (!current()) return
        if (problem == LocalServiceProblem.SESSION_ENDED) runtime.localSessionEnded(session)
        else if (problem == LocalServiceProblem.DENIED) {
            val cleanup = LocalSessionCleanup()
            // A failed original denial cannot strand independent private views/callbacks.
            // The physical controllers retain their exact failed holders for negative retry.
            cleanup.attempt { supplierPhysical.deactivate() }
            cleanup.attempt { retailPhysical.deactivate() }
            cleanup.attempt { clearPurchasingDocuments() }
            companyCatalogDraft = false
            companyCatalogWorking = false
            foodConfigurationDraft = false
            foodConfigurationWorking = false
            returnRightsDraft = false
            returnRightsWorking = false
            pricePolicyDraft = false
            pricePolicyWorking = false
            serialRegistrationDraft = false
            serialRegistrationWorking = false
            catalogReleaseDraft = false
            catalogReleaseWorking = false
            val originalId = state.value.operationId.takeUnless { state.value.recordedAcknowledged }
            val resultRead = state.value.retiredOperationRead(problem)
            frozen = frozen?.takeIf { it.operationId == originalId }
            originalAction = originalAction.takeIf { originalId != null }
            mutableState.value = state.value.copy(page = null, form = null, formInput = null, openingCounts = null, referencePicker = null, actionApproval = null, actionApprovalLookupWorking = false, search = "", reference = null,
                priceDraft = null, pricePreview = null, foodPricePreview = null, foodPricePreviewProblem = null,
                foodQuery = state.value.workflow.defaultFoodQuery(), kitchenBoardOrigin = null, foodUnavailable = null,
                branchReportQuery = state.value.branchReportQuery.copy(after = null, expectedBranchRevision = null),
                branchReportExport = LocalBranchReportExportPresentation(),
                pending = emptyList(), pendingLoaded = false, pendingCursor = null, readProblem = problem,
                originalLookupInput = "", originalLookupProblem = null,
                operationId = originalId, operation = originalId?.let { LocalOperationResult.Unresolved(it, problem) },
                operationRead = resultRead)
            // Negative cleanup belongs to the accepted controller, not a later Compose effect:
            // an attachment refresh may replace the error projection before a frame observes it.
            cleanup.attempt { employeeAccessDenied(this) }
            if (cleanup.failed) {
                // End local access before the generic work-error path can retry/reactivate.
                cleanup.attempt { runtime.localSessionEnded(session, LocalServiceProblem.STORAGE_UNCERTAIN) }
            }
            cleanup.throwIfFailed()
            activateRetailPhysical(state.value.workflow, null, state.value.foodQuery)
        }
    }

    /** Retire the old navigation reply without replacing its unresolved original. */
    private fun discardStaleNavigation(kind: LocalWorkBusy) {
        val cleanup = LocalSessionCleanup()
        cleanup.attempt { supplierPhysical.deactivate() }
        cleanup.attempt { retailPhysical.deactivate() }
        cleanup.attempt { clearPurchasingDocuments() }
        // Accepted denial cleanup can itself change the entry's leave guard.
        // Retire the stale ticket without erasing that newer negative result.
        val problem = state.value.readProblem?.takeIf { it == LocalServiceProblem.DENIED }
            ?: LocalServiceProblem.UNAVAILABLE
        val unresolvedId = state.value.operationId.takeUnless { state.value.recordedAcknowledged }
        val resultRead = state.value.retiredOperationRead(LocalServiceProblem.UNAVAILABLE)
        mutableState.value = state.value.copy(page = null, form = null, formInput = null, openingCounts = null, referencePicker = null, actionApproval = null, actionApprovalLookupWorking = false, search = "", reference = null,
            priceDraft = null, pricePreview = null, foodPricePreview = null, foodPricePreviewProblem = null,
            foodQuery = state.value.workflow.defaultFoodQuery(), kitchenBoardOrigin = null, foodUnavailable = null,
            branchReportQuery = state.value.branchReportQuery.copy(after = null, expectedBranchRevision = null),
            branchReportExport = LocalBranchReportExportPresentation(),
            pending = emptyList(), pendingLoaded = false, pendingCursor = null, readProblem = problem,
            originalLookupInput = "", originalLookupProblem = null,
            operationId = unresolvedId,
            operation = unresolvedId?.let { LocalOperationResult.Unresolved(it, LocalServiceProblem.UNAVAILABLE) },
            operationRead = resultRead)
        if (cleanup.failed) {
            // A stale reply must still erase private views when an original holder fails.
            // Retain that holder and fence the attachment before any work-error path can
            // reactivate a child or offer a replacement operation.
            cleanup.attempt { runtime.localSessionEnded(session, LocalServiceProblem.STORAGE_UNCERTAIN) }
        }
        cleanup.throwIfFailed()
        activateRetailPhysical(state.value.workflow, null, state.value.foodQuery)
        activateSupplierPhysical()
        if (unresolvedId != null) unresolved(unresolvedId, staging = kind == LocalWorkBusy.STAGING)
    }

    private fun run(kind: LocalWorkBusy, secret: CharArray? = null, block: suspend (RequestOwner) -> Unit) {
        if (!current() || state.value.working || retailPhysical.blocksParentWork || supplierPhysical.blocksParentWork) { secret?.fill('\u0000'); return }
        mutableState.value = state.value.copy(busy = kind)
        syncGuard()
        // Capture AFTER our own guard registration. Even a rapid away-and-back before Compose
        // disposes the entry changes this token, so an old response cannot revive private data.
        val ticket = RequestOwner(activation, runtime.navigator.token(), purchasingDocumentEpoch.takeIf { kind in documentaryWorkKinds },
            actionApprovalEpoch.takeIf { kind in approvalWorkKinds })
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try { if (current(ticket)) block(ticket) }
            catch (cancelled: CancellationException) {
                if (current(ticket) && kind in approvalWorkKinds) actionApprovalWorkFailed(kind)
                if (current(ticket) && kind in documentaryWorkKinds) purchasingDocumentWorkFailed(kind, cancelled = true)
                if (current(ticket) && kind in setOf(LocalWorkBusy.REPORT_EXPORT, LocalWorkBusy.REPORT_EXPORT_LOOKUP))
                    applyExportResult(LocalBranchReportExportResult.Unavailable(fileProblem = LocalBranchReportFileProblem.RECOVERY_REQUIRED))
                if (current(ticket) && kind == LocalWorkBusy.ACKNOWLEDGING)
                    acknowledgementUnavailable(LocalServiceProblem.UNAVAILABLE)
                if (current(ticket) && kind == LocalWorkBusy.ORIGINAL_LOOKUP)
                    originalLookupUnavailable(LocalServiceProblem.UNAVAILABLE)
                if (current(ticket) && kind in setOf(LocalWorkBusy.STAGING, LocalWorkBusy.SUBMITTING, LocalWorkBusy.LOOKUP))
                    state.value.operationId?.let { unresolved(it, staging = kind == LocalWorkBusy.STAGING) }
                throw cancelled
            } catch (_: Exception) {
                if (current(ticket)) when (kind) {
                    in approvalWorkKinds -> actionApprovalWorkFailed(kind)
                    in documentaryWorkKinds -> purchasingDocumentWorkFailed(kind)
                    LocalWorkBusy.REPORT_EXPORT, LocalWorkBusy.REPORT_EXPORT_LOOKUP ->
                        applyExportResult(LocalBranchReportExportResult.Unavailable(serviceProblem = LocalServiceProblem.UNAVAILABLE,
                            fileProblem = LocalBranchReportFileProblem.RECOVERY_REQUIRED))
                    LocalWorkBusy.STAGING, LocalWorkBusy.SUBMITTING, LocalWorkBusy.LOOKUP ->
                        state.value.operationId?.let { unresolved(it, staging = kind == LocalWorkBusy.STAGING) }
                    LocalWorkBusy.PENDING -> applyPending(LocalPendingResult.Unavailable(LocalServiceProblem.UNAVAILABLE), null)
                    LocalWorkBusy.ORIGINAL_LOOKUP -> originalLookupUnavailable(LocalServiceProblem.UNAVAILABLE)
                    LocalWorkBusy.REFERENCES -> referenceUnavailable(LocalServiceProblem.UNAVAILABLE)
                    LocalWorkBusy.FOOD_PREVIEW -> foodPreviewUnavailable(LocalServiceProblem.UNAVAILABLE)
                    LocalWorkBusy.ACKNOWLEDGING -> acknowledgementUnavailable(LocalServiceProblem.UNAVAILABLE)
                    LocalWorkBusy.ATTACHMENT_REVIEW -> mutableState.value = state.value.copy(
                        page = null, readProblem = LocalServiceProblem.UNAVAILABLE, pendingLoaded = false)
                    else -> if (kind == LocalWorkBusy.READING && state.value.form?.descriptor?.openingAllocations != null)
                        openingReadUnavailable(LocalServiceProblem.UNAVAILABLE)
                    else readUnavailable(LocalServiceProblem.UNAVAILABLE)
                }
            } finally {
                if (kind == LocalWorkBusy.ACTION_APPROVAL_RETIRE && work === currentCoroutineContext()[Job]) {
                    heldActionApprovalLookup?.cancel()
                    heldActionApprovalLookup = null
                    if (current(ticket.activation))
                        mutableState.value = state.value.copy(actionApprovalLookupWorking = false)
                }
                if (current(ticket.activation)) {
                    if (!runtime.navigator.isCurrent(ticket.navigation)) discardStaleNavigation(kind)
                    mutableState.value = state.value.copy(busy = LocalWorkBusy.NONE,
                        purchasingDocuments = state.value.purchasingDocuments.copy(workingDocumentId = null))
                    work = null
                    syncGuard()
                }
            }
        }
        job.invokeOnCompletion { secret?.fill('\u0000') }
        work = job
        job.start()
    }

    private fun syncGuard() {
        if (!current()) return
        val desired = when {
            companyCatalogWorking || foodConfigurationWorking || returnRightsWorking || pricePolicyWorking || catalogReleaseWorking || serialRegistrationWorking -> LeaveGuard.CRITICAL
            retailPhysical.leaveGuard == LeaveGuard.CRITICAL || supplierPhysical.leaveGuard == LeaveGuard.CRITICAL -> LeaveGuard.CRITICAL
            state.value.busy in setOf(LocalWorkBusy.STAGING, LocalWorkBusy.SUBMITTING, LocalWorkBusy.ACKNOWLEDGING,
                LocalWorkBusy.REPORT_EXPORT, LocalWorkBusy.REPORT_EXPORT_LOOKUP, LocalWorkBusy.DOCUMENT_IMPORT,
                LocalWorkBusy.DOCUMENT_SAVE, LocalWorkBusy.DOCUMENT_TRANSFER, LocalWorkBusy.DOCUMENT_DOWNLOAD,
                LocalWorkBusy.DOCUMENT_COMPLETION_RETRY, LocalWorkBusy.ACTION_APPROVAL_ISSUE, LocalWorkBusy.ACTION_APPROVAL_RETIRE) -> LeaveGuard.CRITICAL
            retailPhysical.leaveGuard == LeaveGuard.DIRTY || supplierPhysical.leaveGuard == LeaveGuard.DIRTY -> LeaveGuard.DIRTY
            companyCatalogDraft || foodConfigurationDraft || returnRightsDraft || pricePolicyDraft || catalogReleaseDraft || serialRegistrationDraft -> LeaveGuard.DIRTY
            state.value.actionApproval?.let { it.passwordEntry || it.confirmed } == true -> LeaveGuard.DIRTY
            state.value.purchasingDocuments.hasDraft -> LeaveGuard.DIRTY
            (state.value.form?.dirty == true || state.value.formInput?.dirty == true) && !state.value.formFrozen -> LeaveGuard.DIRTY
            else -> LeaveGuard.CLEAN
        }
        if (desired == guardKind) return
        editorBeforeGuard = runtime.navigator.state.value.revision
        if (desired == LeaveGuard.CLEAN) removeGuard()
        else {
            guard = runtime.navigator.registerGuard(owner.entryId, desired)
            guardKind = if (guard != null) desired else LeaveGuard.CLEAN
        }
        editorAfterGuard = runtime.navigator.state.value.revision
    }

    private fun removeGuard() {
        guard?.let(runtime.navigator::removeGuard)
        guard = null
        guardKind = LeaveGuard.CLEAN
    }
}

private fun openingAllocationId(line: LocalDraftLine): DomainId? = try {
    line.values[LocalField.ALLOCATION_ID]?.text?.let(::DomainId)
} catch (_: IllegalArgumentException) { null }

/** Exact entered base quantity only. Blank/invalid/zero is unfinished, never an inferred zero. */
private fun positiveOpeningQuantity(line: LocalDraftLine): Quantity? = try {
    val unit = line.values[LocalField.UNIT]?.text?.let(::DomainId)
    val scale = line.values[LocalField.QUANTITY_SCALE]?.text?.toIntOrNull()
    val count = line.values[LocalField.QUANTITY]?.text
    if (unit == null || scale == null || scale !in 0..6 || count == null) null
    else Quantity.parse(unit, normalizeLocalNumber(count), scale).takeIf { it.scaled > 0 }
} catch (_: IllegalArgumentException) { null } catch (_: ArithmeticException) { null }

private fun countedOpeningProgress(form: LocalFormDraft?, counts: LocalOpeningCountDraft?): LocalOpeningCountProgress? {
    val draft = form ?: return null
    val source = draft.descriptor.openingAllocations ?: return null
    val ids = source.allocationIds.toSet()
    val zeros = counts?.zeroAllocations.orEmpty()
    val groups = draft.lines.groupBy(::openingAllocationId)
    val positive = groups.filter { (id, slices) -> id != null && id in ids && slices.all { positiveOpeningQuantity(it) != null } }
        .keys.filterNotNull().toSet()
    val positiveSlices = draft.lines.count { positiveOpeningQuantity(it) != null }
    val completed = zeros + positive
    val complete = counts != null && zeros.all { it in ids && it !in groups } && groups.keys.all { it != null && it in ids } &&
        completed == ids && positiveSlices == draft.lines.size
    return LocalOpeningCountProgress(ids.size, zeros.count { it in ids }, positive.size,
        ids.count { it !in completed }, positiveSlices,
        complete && counts?.sourceProblem == null && positiveSlices in 1..LocalOpeningAllocationPage.MAX_POSITIVE_LINES)
}

private fun snapshotOpeningAllocations(page: LocalOpeningAllocationPage): LocalOpeningAllocationPage = page.copy(
    allocationIds = page.allocationIds.toList(), rows = page.rows.map { row ->
        row.copy(record = row.record.copy(values = row.record.values.toList()),
            initialLine = LocalInputLine(row.initialLine.lineId, row.initialLine.values))
    })

private fun snapshotPage(page: LocalWorkflowPage): LocalWorkflowPage = page.copy(
    records = page.records.map { it.copy(values = it.values.toList()) },
    returnRights = page.returnRights?.let { rights -> rights.copy(attribution = rights.attribution.toList(),
        rules = rights.rules.map { it.copy(values = it.values.toList()) }) },
    managerQuoteDetails = page.managerQuoteDetails?.let { details ->
        details.copy(components = details.components.map { it.copy(values = it.values.toList()) },
            originalQuote = details.originalQuote?.let { original -> original.copy(values = original.values.toList(),
                sections = original.sections.map { it.copy(values = it.values.toList()) }) })
    },
    food = page.food?.snapshotFood(),
    branchReport = page.branchReport?.let { report ->
        report.copy(documents = report.documents.map { document ->
            document.copy(amounts = document.amounts.toList(), retailTenderBreakdown = document.retailTenderBreakdown?.let { detail ->
                detail.copy(payments = detail.payments.toList(), originalBuckets = detail.originalBuckets.toList(),
                    reconciledBuckets = detail.reconciledBuckets.toList())
            })
        })
    },
    openingAllocations = page.openingAllocations?.let(::snapshotOpeningAllocations),
    forms = page.forms.map { form -> form.copy(openingAllocations = form.openingAllocations?.let(::snapshotOpeningAllocations),
        fields = form.fields.map { field -> when (field) {
        is LocalFormField.Scalar -> field.copy(choices = field.choices.toList(), references = field.references.toList())
        is LocalFormField.Lines -> field.copy(columns = field.columns.map { it.copy(choices = it.choices.toList(), references = it.references.toList()) },
            initialLines = field.initialLines.toList())
    } }) },
)
