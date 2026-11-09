@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.independent.capturedinvoicematchingv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain

internal val matchingAction = LocalAction.SUPPLIER_INVOICE_MATCH_RECOGNIZE
internal val matchingInvoiceA = DomainId("71000000-0000-4000-8000-000000000001")
internal val matchingInvoiceB = DomainId("71000000-0000-4000-8000-000000000002")
internal val matchingLineA = DomainId("71000000-0000-4000-8000-000000000003")
internal val matchingLineB = DomainId("71000000-0000-4000-8000-000000000004")
internal val matchingOrder = DomainId("71000000-0000-4000-8000-000000000005")
internal val matchingOriginal = DomainId("71000000-0000-4000-8000-000000000006")
internal val matchingInvoiceAReference = LocalRecordReference(LocalRecordKind.PURCHASE_INVOICE, matchingInvoiceA)
internal val matchingInvoiceBReference = LocalRecordReference(LocalRecordKind.PURCHASE_INVOICE, matchingInvoiceB)
internal val matchingOrderReference = LocalRecordReference(LocalRecordKind.PURCHASE, matchingOrder)

internal fun matchingLineReference(invoice: LocalRecordReference = matchingInvoiceAReference) =
    LocalRecordReference(LocalRecordKind.PURCHASE_LINE,
        if (invoice.id == matchingInvoiceA) matchingLineA else matchingLineB, parentId = invoice.id)

internal fun matchingValue(label: String, value: String) = LocalRecordValue(LocalText(label, ""), LocalText(value, ""))

/** F-QUALITY values supplied to the UI port, not produced by a Master or invoice decoder. */
internal val matchingRawClaim = LocalRecord(matchingInvoiceAReference,
    LocalText("00042-QUALITY", ""), LocalText("UNMATCHED_UNAPPROVED", ""), listOf(
        matchingValue("Supplier reference", "00042-QUALITY"),
        matchingValue("Document date", "2026-11-05"),
        matchingValue("Original claimed quantity", "2 CARTON = 24 EACH"),
        matchingValue("Raw claim", "EGP 240"),
    ), invoiceEntryKind = LocalInvoiceEntryKind.CAPTURE_ONLY_V2)

internal val matchingRecognitionOverlay = LocalRecord(
    LocalRecordReference(LocalRecordKind.PURCHASE_MATCH,
        DomainId("71000000-0000-4000-8000-000000000007"), parentId = matchingInvoiceA),
    LocalText("Recognized goods progress", ""), LocalText("PARTIALLY_MATCHED_RECOGNIZED", ""), listOf(
        matchingValue("Matched accepted quantity", "22 EACH"),
        matchingValue("Recognized payable", "EGP 220"),
        matchingValue("Unmatched claim", "2 EACH / EGP 20"),
        matchingValue("Payments", "EGP 0"),
        matchingValue("Remaining due", "EGP 220"),
        matchingValue("Settlement eligible", "false"),
    ), selectable = false)

internal val matchingSummary = listOf(
    matchingValue("Raw claim", "EGP 240"),
    matchingValue("Selected accepted quantity", "22 EACH"),
    matchingValue("New recognized payable", "EGP 220"),
    matchingValue("Unmatched claim", "2 EACH / EGP 20"),
)

/** Scalar UI input only. These synthetic selectors/revisions issue no source qualification. */
internal fun matchingForm(invoice: LocalRecordReference = matchingInvoiceAReference): LocalActionForm {
    fun identifier(field: LocalField, id: DomainId, readOnly: Boolean = false) =
        LocalFormField.Scalar(field, LocalText(field.name, ""), LocalInputKind.IDENTIFIER,
            initialValue = LocalScalarValue.Identifier(id), readOnly = readOnly)
    fun integer(field: LocalField, value: Long, readOnly: Boolean = false) =
        LocalFormField.Scalar(field, LocalText(field.name, ""), LocalInputKind.INTEGER,
            initialValue = LocalScalarValue.Integer(value), readOnly = readOnly)
    val line = matchingLineReference(invoice)
    return LocalActionForm(matchingAction, 1, LocalText("Match claim and recognize payable", ""), listOf(
        identifier(LocalField.MATCH_ID, DomainId("71000000-0000-4000-8000-000000000008")),
        identifier(LocalField.INVOICE_ID, invoice.id, readOnly = true),
        integer(LocalField.EXPECTED_STORAGE_REVISION, 7, readOnly = true),
        LocalFormField.Scalar(LocalField.EXPECTED_PROGRESS_REVISION, LocalText("Observed progress", ""),
            LocalInputKind.INTEGER, required = false, readOnly = true),
        identifier(LocalField.EVIDENCE_ID, matchingOriginal, readOnly = true),
        integer(LocalField.EXPECTED_SOURCE_REVISION, 3, readOnly = true),
        LocalFormField.Scalar(LocalField.INVOICE_LINE_ID, LocalText("Invoice goods line", ""), LocalInputKind.REFERENCE,
            references = listOf(LocalReferenceChoice(line, LocalText("G-A", ""))),
            initialValue = LocalScalarValue.Reference(line)),
        identifier(LocalField.PURCHASE_ID, matchingOrder),
        integer(LocalField.EXPECTED_ORDER_REVISION, 11),
        identifier(LocalField.ALLOCATION_ID, DomainId("71000000-0000-4000-8000-000000000009")),
        identifier(LocalField.RECEIPT_ID, DomainId("71000000-0000-4000-8000-000000000010")),
        identifier(LocalField.RECEIPT_LINE_ID, DomainId("71000000-0000-4000-8000-000000000011")),
        integer(LocalField.EXPECTED_RECEIPT_REVISION, 13),
        LocalFormField.Scalar(LocalField.QUANTITY, LocalText("Exact accepted base quantity", ""), LocalInputKind.DECIMAL,
            initialValue = LocalScalarValue.Decimal("22")),
        LocalFormField.Scalar(LocalField.REASON, LocalText("Reason", ""), LocalInputKind.TEXT,
            initialValue = LocalScalarValue.Text("EXACT_GOODS")),
    ))
}

internal fun matchingPage(invoice: LocalRecordReference? = null) = LocalWorkflowPage(LocalWorkflow.PURCHASING,
    listOf(matchingRawClaim, LocalRecord(matchingInvoiceBReference, LocalText("Other synthetic claim", ""))),
    invoice?.let { listOf(matchingForm(it)) }.orEmpty())

internal fun matchingQualityPage() = matchingPage(matchingInvoiceAReference).copy(records = listOf(
    matchingRawClaim,
    LocalRecord(matchingLineReference(), LocalText("G-A original goods", ""), values = listOf(
        matchingValue("Claimed", "24 EACH"), matchingValue("Accepted", "22 EACH"),
        matchingValue("Rejected held", "2 EACH")), selectable = false),
    matchingRecognitionOverlay,
))

/** Explicitly scripted shared UI callbacks. No durable journal, Master, RPC or native file boundary. */
internal class CapturedMatchingUiPort : LocalWorkflowGateway {
    val reads = mutableListOf<LocalWorkflowQuery>()
    val pendingReads = mutableListOf<String?>()
    val stages = mutableListOf<LocalCommandIntent>()
    val submissions = mutableListOf<OperationId>()
    val lookups = mutableListOf<OperationId>()
    val acknowledgements = mutableListOf<OperationId>()
    val referenceReads = mutableListOf<LocalReferenceQuery>()
    var pendingReply: LocalPendingResult = LocalPendingResult.Available(emptyList())
    var readReply: suspend (LocalWorkflowQuery) -> LocalReadResult = { query ->
        LocalReadResult.Available(if (query.purchasing?.section == LocalPurchasingSection.ORDERS)
            LocalWorkflowPage(LocalWorkflow.PURCHASING,
                listOf(LocalRecord(matchingOrderReference, LocalText("Synthetic approved PO", ""))), emptyList())
        else matchingPage(query.reference))
    }
    var stageReply: suspend (LocalCommandIntent) -> LocalStageResult = {
        LocalStageResult.NotStaged(LocalServiceProblem.UNAVAILABLE)
    }
    var submitReply: suspend (OperationId) -> LocalOperationResult = {
        LocalOperationResult.Unresolved(it, LocalServiceProblem.UNAVAILABLE)
    }
    var lookupReply: suspend (OperationId) -> LocalOperationResult = {
        LocalOperationResult.Unresolved(it, LocalServiceProblem.UNAVAILABLE)
    }

    override suspend fun read(query: LocalWorkflowQuery): LocalReadResult { reads += query; return readReply(query) }
    override suspend fun pending(cursor: String?): LocalPendingResult { pendingReads += cursor; return pendingReply }
    override suspend fun stage(intent: LocalCommandIntent): LocalStageResult { stages += intent; return stageReply(intent) }
    override suspend fun submit(operationId: OperationId): LocalOperationResult { submissions += operationId; return submitReply(operationId) }
    override suspend fun lookup(operationId: OperationId): LocalOperationResult { lookups += operationId; return lookupReply(operationId) }
    override suspend fun acknowledgeRecorded(operationId: OperationId): LocalAcknowledgementResult {
        acknowledgements += operationId
        return LocalAcknowledgementResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE)
    }
    override suspend fun references(query: LocalReferenceQuery): LocalReferenceResult {
        referenceReads += query
        return LocalReferenceResult.Unavailable(LocalServiceProblem.UNAVAILABLE)
    }
    fun callCounts() = listOf(reads.size, pendingReads.size, stages.size, submissions.size,
        lookups.size, acknowledgements.size, referenceReads.size)
}

internal class CapturedMatchingUiSession(override val workflows: CapturedMatchingUiPort) : BranchUiSession {
    override val display = SessionDisplayContext(
        DomainId("71000000-0000-4000-8000-000000000101"),
        DomainId("71000000-0000-4000-8000-000000000102"),
        DomainId("71000000-0000-4000-8000-000000000103"),
        "Synthetic matching owner", "Synthetic company", "Synthetic branch", setOf(AppArea.PURCHASING),
        workspaceMode = LocalSessionWorkspaceMode.INVOICE_MATCHING_ONLY)
    override val lifecycle = MutableStateFlow(LocalSessionLifecycle.ACTIVE)
    private var current = true
    override fun isCurrent() = current
    override suspend fun current(): LocalSessionCheckResult = if (current) LocalSessionCheckResult.Current(display)
        else LocalSessionCheckResult.Unavailable(LocalServiceProblem.SESSION_ENDED)
    override suspend fun mayRestore(route: AppRoute) = current && invoiceMatchingUiRoute(route)
    override fun close() { current = false; lifecycle.value = LocalSessionLifecycle.LOCKED }
}

internal class CapturedMatchingUiFixture(val runtime: UkkeraRuntime, val controller: LocalWorkflowController,
    val port: CapturedMatchingUiPort)

internal suspend fun TestScope.withCapturedMatchingUi(
    initialWorkflow: LocalWorkflow = LocalWorkflow.PURCHASING,
    reference: LocalRecordReference? = null,
    continuation: LocalWorkflowContinuation? = null,
    configure: (CapturedMatchingUiPort) -> Unit = {},
    block: suspend CapturedMatchingUiFixture.() -> Unit,
) {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val port = CapturedMatchingUiPort().also(configure)
    val session = CapturedMatchingUiSession(port)
    val runtime = UkkeraRuntime(AppDependencies(branchGateway = object : BranchUiGateway {
        override val configured = true
        override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> =
            SignInResult.Authenticated(session)
    }, platform = PlatformCapabilities(PlatformFamily.DESKTOP)))
    var controller: LocalWorkflowController? = null
    try {
        runtime.openSignIn(false, runtime.navigator.token())
        runtime.signIn("synthetic-matching-owner", charArrayOf('x'), runtime.navigator.token())
        val created = LocalWorkflowController(runtime, session, runtime.navigator.token(), backgroundScope, continuation)
        controller = created
        created.activate(initialWorkflow, reference)
        runCurrent()
        CapturedMatchingUiFixture(runtime, created, port).block()
    } finally {
        try { controller?.close(); runtime.close(); runCurrent() } finally { Dispatchers.resetMain() }
    }
}
