@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.independent.historicaltenderreportv2

import com.ukkera.pos.app.*
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlin.test.assertEquals

/** Literal selected-document inputs from payment-correction-report basis-fixtures-v2.json.
 * Controlled presentation replies do not establish native source completeness or authority.
 */
internal object HistoricalTenderReportV2Inputs {
    fun id(number: Int) = DomainId("20000000-0000-4000-8000-${number.toString().padStart(12, '0')}")
    fun money(minor: Long) = Money(Currency.EGP, minor)

    val reader = id(1)
    val collector = id(2)
    val company = id(3)
    val branch = id(4)
    val cash4600Sale = id(10)
    val cash10Sale = id(11)
    val split9999Sale = id(12)
    val zeroSale = id(13)
    val cash4600Order = id(20)
    val cash10Order = id(21)
    val splitOrder = id(22)
    val zeroOrder = id(23)
    val cashSession = id(30)
    val splitSession = id(31)
    val zeroSession = id(32)
    val cashCloseOperation = OperationId(id(40).value)
    val splitCloseOperation = OperationId(id(41).value)
    val zeroCloseOperation = OperationId(id(42).value)
    val cash4600Payment = id(50)
    val cash10Payment = id(51)
    val split7264Payment = id(52)
    val split2735Payment = id(53)
    val cashCorrection = id(60)
    val splitCorrection = id(61)
    val cardTender = id(70)
    val walletTender = id(71)
    val exportA = id(80)
    val cashAfterCut = Revision(42)
    val splitCut = Revision(51)
    val observedAt = UtcInstant.parseCanonical("2026-11-03T02:00:02Z")
    val correctionRecordedAt = UtcInstant.parseCanonical("2026-11-03T02:00:01Z")

    val cash = LocalText("Cash", "نقداً")
    val card = LocalText("Card", "بطاقة")
    val wallet = LocalText("External wallet", "محفظة إلكترونية")
    val cashClassification = LocalBranchReportTenderClassification(cash)
    val cardClassification = LocalBranchReportTenderClassification(card, cardTender, Revision(7))
    val walletClassification = LocalBranchReportTenderClassification(wallet, walletTender, Revision(7))

    val display = SessionDisplayContext(reader, company, branch, "Synthetic document reader",
        "Synthetic company", "Synthetic branch", setOf(AppArea.REPORTS),
        workspaceMode = LocalSessionWorkspaceMode.REPORTS_ONLY)

    private fun payment(id: DomainId, minor: Long, original: LocalBranchReportTenderClassification,
        reconciled: LocalBranchReportTenderClassification = original, correction: DomainId? = null) =
        LocalBranchReportRetailTenderPayment(id, money(minor), original, reconciled, correction,
            if (correction == null) 0L else 1L, correctionRecordedAt.takeIf { correction != null })

    private fun bucket(label: LocalText, tender: DomainId?, minor: Long) =
        LocalBranchReportTenderBucket(label, tender, money(minor))

    private fun document(sale: DomainId, relatedOrder: DomainId, session: DomainId, closeOperation: OperationId,
        paid: Long, expected: Long, counted: Long, variance: Long,
        payments: List<LocalBranchReportRetailTenderPayment>, original: List<LocalBranchReportTenderBucket>,
        reconciled: List<LocalBranchReportTenderBucket>) = LocalBranchReportDocument(
        id = sale, revision = Revision(1), status = LocalText("Paid", "مدفوع"),
        relatedDocumentId = relatedOrder, cashSessionId = session, cashSessionBusinessDate = null, pendingCount = 0,
        amounts = listOf(
            LocalBranchReportAmount(LocalText("Original paid-sale amount", "مبلغ البيع المدفوع الأصلي"), money(paid), LocalBranchReportAmountGroup.PAID_SALE),
            LocalBranchReportAmount(LocalText("Original expected cash", "النقدية المتوقعة الأصلية"), money(expected), LocalBranchReportAmountGroup.ORIGINAL_CLOSE),
            LocalBranchReportAmount(LocalText("Original actual count", "العد الفعلي الأصلي"), money(counted), LocalBranchReportAmountGroup.ORIGINAL_CLOSE),
            LocalBranchReportAmount(LocalText("Original submitted variance", "الفرق المقدم الأصلي"), money(variance), LocalBranchReportAmountGroup.ORIGINAL_CLOSE),
            LocalBranchReportAmount(LocalText("Physical cash delta", "فرق النقدية الفعلي"), money(0), LocalBranchReportAmountGroup.PHYSICAL_MOVEMENT)),
        retailTenderBreakdown = LocalBranchReportRetailTenderBreakdown(closeOperation, collector,
            payments, original, reconciled))

    fun cash4600Document() = document(cash4600Sale, cash4600Order, cashSession, cashCloseOperation,
        460000, 235000, 236000, 1000,
        listOf(payment(cash4600Payment, 460000, cashClassification)),
        listOf(bucket(cash, null, 460000)), listOf(bucket(cash, null, 460000)))

    fun cash10Document() = document(cash10Sale, cash10Order, cashSession, cashCloseOperation,
        1000, 235000, 236000, 1000,
        listOf(payment(cash10Payment, 1000, cardClassification, cashClassification, cashCorrection)),
        listOf(bucket(card, cardTender, 1000)), listOf(bucket(cash, null, 1000)))

    fun splitDocument() = document(split9999Sale, splitOrder, splitSession, splitCloseOperation,
        9999, 62550, 62550, 0,
        listOf(payment(split7264Payment, 7264, cardClassification),
            payment(split2735Payment, 2735, cardClassification, walletClassification, splitCorrection)),
        listOf(bucket(card, cardTender, 9999)),
        listOf(bucket(card, cardTender, 7264), bucket(wallet, walletTender, 2735)))

    fun zeroDocument() = document(zeroSale, zeroOrder, zeroSession, zeroCloseOperation,
        0, 10000, 10000, 0, emptyList(), emptyList(), emptyList())

    fun page(sale: DomainId): LocalWorkflowPage {
        val document = when (sale) {
            cash4600Sale -> cash4600Document()
            cash10Sale -> cash10Document()
            split9999Sale -> splitDocument()
            zeroSale -> zeroDocument()
            else -> error("No controlled source is supplied for this selected sale")
        }
        val cut = when (sale) {
            cash4600Sale -> Revision(41)
            cash10Sale -> cashAfterCut
            split9999Sale -> splitCut
            else -> Revision(61)
        }
        return LocalWorkflowPage(LocalWorkflow.REPORTS, emptyList(), emptyList(), branchReport =
            LocalBranchReportPage(LocalBranchReportSection.RETAIL_TENDER_BREAKDOWNS, null, null, cut,
                observedAt, reader, company, branch, listOf(document), selectedSaleId = sale))
    }

    fun read(query: LocalWorkflowQuery): LocalReadResult {
        val selection = query.branchReport
        if (query.workflow != LocalWorkflow.REPORTS || selection == null || selection.section != LocalBranchReportSection.RETAIL_TENDER_BREAKDOWNS ||
            selection.selectedSaleId !in setOf(cash4600Sale, cash10Sale, split9999Sale, zeroSale))
            return LocalReadResult.Unavailable(LocalServiceProblem.INVALID_INPUT)
        return LocalReadResult.Available(page(requireNotNull(selection.selectedSaleId)))
    }

    /** Metadata-only SELECTED receipt: no bytes, native publication or completed export is supplied. */
    fun originalARecord() = LocalBranchReportExportRecord(LocalBranchReportFileReceipt(
        exportId = exportA, receiptId = id(81), beginOperationId = OperationId(id(82).value),
        finishOperationId = OperationId(id(83).value), recoveryOperationId = OperationId(id(84).value),
        owner = LocalBranchReportFileOwner(id(90), company, branch, reader, id(91), id(92), 1, id(93)),
        query = LocalBranchReportFileQuery(LocalBranchReportSection.RETAIL_TENDER_BREAKDOWNS.name,
            profile = LocalBranchReportFileProfile.RETAIL_TENDER_BREAKDOWN_V3, selectedSaleId = cash10Sale),
        fileName = "historical-selected-a.csv", destinationKind = LocalBranchReportFileDestinationKind.DESKTOP_LOCAL_FILE,
        source = LocalBranchReportExportSource(cashAfterCut, observedAt, "0".repeat(64), 1, null)))
}

internal class HistoricalTenderReportV2WorkflowPort : LocalWorkflowGateway {
    val reads = mutableListOf<LocalWorkflowQuery>()
    val exports = mutableListOf<LocalBranchReportExportSelection>()
    val exportLifetimes = mutableListOf<LocalBranchReportExportLifetime>()
    val recoveries = mutableListOf<DomainId>()
    val unrelatedCalls = mutableListOf<String>()
    var readReply: suspend (LocalWorkflowQuery) -> LocalReadResult = { HistoricalTenderReportV2Inputs.read(it) }
    var exportReply: suspend (LocalBranchReportExportSelection, LocalBranchReportExportLifetime) -> LocalBranchReportExportResult =
        { _, _ -> LocalBranchReportExportResult.ChooserCancelled }
    var historyReply: LocalBranchReportExportHistory = LocalBranchReportExportHistory.Available(emptyList(), null)
    var recoveryReply: (DomainId) -> LocalBranchReportExportResult =
        { LocalBranchReportExportResult.Unavailable(serviceProblem = LocalServiceProblem.NOT_IMPLEMENTED, exportId = it) }

    override suspend fun read(query: LocalWorkflowQuery): LocalReadResult { reads += query; return readReply(query) }
    override suspend fun exportBranchReport(selection: LocalBranchReportExportSelection, files: LocalBranchReportFileDelivery,
        lifetime: LocalBranchReportExportLifetime, progress: (LocalBranchReportExportProgress) -> Unit): LocalBranchReportExportResult {
        exports += selection
        exportLifetimes += lifetime
        return exportReply(selection, lifetime)
    }
    override suspend fun branchReportExports(files: LocalBranchReportFileDelivery, lifetime: LocalBranchReportExportLifetime,
        after: DomainId?): LocalBranchReportExportHistory = historyReply
    override suspend fun recoverBranchReportExport(exportId: DomainId, files: LocalBranchReportFileDelivery,
        lifetime: LocalBranchReportExportLifetime): LocalBranchReportExportResult {
        recoveries += exportId
        return recoveryReply(exportId)
    }
    private fun unexpected(name: String): Nothing { unrelatedCalls += name; error("Unrelated controlled port was called: $name") }
    override suspend fun stage(intent: LocalCommandIntent): LocalStageResult = unexpected("stage")
    override suspend fun submit(operationId: OperationId): LocalOperationResult = unexpected("submit")
    override suspend fun lookup(operationId: OperationId): LocalOperationResult = unexpected("lookup")
    override suspend fun pending(cursor: String?): LocalPendingResult = unexpected("pending")
    override suspend fun references(query: LocalReferenceQuery): LocalReferenceResult = unexpected("references")
}

/** Availability and a single-dispatcher lifetime gate only; all native entry points refuse. */
internal class HistoricalTenderReportV2Files : LocalBranchReportFileDelivery() {
    override val bound = true
    var nativeCalls = 0
    override val commitGate = object : LocalReportCommitGate {
        override fun <T> locked(block: () -> T): T = block()
    }
    private fun unexpected(): Nothing { nativeCalls++; error("Native file entry is outside this controlled harness") }
    override suspend fun nativeChoice(suggestedName: String, lifetime: LocalBranchReportExportLifetime): LocalReportNativeChoice? = unexpected()
    override fun hostCurrent(host: Any): Boolean = unexpected()
    override suspend fun <T> disk(block: (LocalReportReceiptDisk) -> T): T = unexpected()
}

internal class HistoricalTenderReportV2Session(override val workflows: HistoricalTenderReportV2WorkflowPort) : BranchUiSession {
    override val display = HistoricalTenderReportV2Inputs.display
    override val lifecycle = MutableStateFlow(LocalSessionLifecycle.ACTIVE)
    private var active = true
    override fun isCurrent() = active
    override suspend fun current(): LocalSessionCheckResult = if (active) LocalSessionCheckResult.Current(display)
        else LocalSessionCheckResult.Unavailable(LocalServiceProblem.SESSION_ENDED)
    override suspend fun mayRestore(route: AppRoute) = false
    fun invalidate() { active = false; lifecycle.value = LocalSessionLifecycle.LOCKED }
    override fun close() = invalidate()
}

internal class HistoricalTenderReportV2ControllerFixture(val runtime: UkkeraRuntime,
    val controller: LocalWorkflowController, val session: HistoricalTenderReportV2Session,
    val port: HistoricalTenderReportV2WorkflowPort, val files: HistoricalTenderReportV2Files) {
    fun token() = runtime.navigator.token()
    fun assertNoUnrelatedOrNativeCalls() {
        assertEquals(emptyList(), port.unrelatedCalls)
        assertEquals(0, files.nativeCalls)
    }
}

internal suspend fun TestScope.withHistoricalTenderReportV2Controller(
    configure: HistoricalTenderReportV2WorkflowPort.() -> Unit = {},
    block: suspend HistoricalTenderReportV2ControllerFixture.() -> Unit) {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val port = HistoricalTenderReportV2WorkflowPort().apply(configure)
    val session = HistoricalTenderReportV2Session(port)
    val files = HistoricalTenderReportV2Files()
    val login = object : BranchUiGateway {
        override val configured = true
        override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> = SignInResult.Authenticated(session)
    }
    val runtime = UkkeraRuntime(AppDependencies(branchGateway = login,
        platform = PlatformCapabilities(PlatformFamily.DESKTOP), branchReportFiles = files))
    var controller: LocalWorkflowController? = null
    try {
        runtime.openSignIn(false, runtime.navigator.token())
        runtime.signIn("synthetic-document-reader", charArrayOf('x'), runtime.navigator.token())
        val selectionOnly = LocalWorkflowContinuation(LocalWorkspaceState(workflow = LocalWorkflow.REPORTS,
            branchReportQuery = LocalBranchReportQuery(LocalBranchReportSection.RETAIL_TENDER_BREAKDOWNS)), null, null, null, null)
        controller = LocalWorkflowController(runtime, session, runtime.navigator.token(), backgroundScope, selectionOnly)
        controller.activate(LocalWorkflow.REPORTS)
        runCurrent()
        HistoricalTenderReportV2ControllerFixture(runtime, controller, session, port, files).block()
    } finally {
        try { controller?.close(); runtime.close(); runCurrent() } finally { Dispatchers.resetMain() }
    }
}
