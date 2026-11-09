@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.independent.counterreceiptrpc

import com.ukkera.pos.app.*
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain

/** Frozen v1 facts carried through a controlled UI port; not paid-sale/native/RPC preparation. */
internal object CounterReceiptUiInput {
    val owner = DomainId("50000000-0000-4000-8000-000000000001")
    val other = DomainId("50000000-0000-4000-8000-000000000002")
    val company = DomainId("20000000-0000-4000-8000-000000000001")
    val branch = DomainId("30000000-0000-4000-8000-000000000001")
    val sale = DomainId("40000000-0000-4000-8000-000000000001")
    val item = DomainId("60000000-0000-4000-8000-000000000001")
    val kg = DomainId("60000000-0000-4000-8000-000000000002")
    val e1 = OperationId("70000000-0000-4000-8000-000000000001")
    val e2 = OperationId("70000000-0000-4000-8000-000000000002")
    const val receivedAt = "2026-11-01T20:30:00Z"
    const val zone = "Africa/Cairo"
    val search = "customer-receipt:${sale.value}"
    val eventSearch = "customer-receipt-event:${sale.value}:${e1.value}"

    // Identifier-shaped carriers for the frozen ITEM/KG metadata, not a real unit catalog.
    val itemUnit = DomainId("6f000000-0000-4000-8000-000000000001")
    val kgUnit = DomainId("6f000000-0000-4000-8000-000000000002")
    val head = LocalRecordReference(LocalRecordKind.CUSTOMER_RECEIPT, sale)
    val batch = batch(item)
    val event = LocalRecordReference(LocalRecordKind.CUSTOMER_RECEIPT_EVENT, DomainId(e1.value), parentId = sale)

    fun text(value: String) = LocalText(value, value)
    fun display(actor: DomainId = owner, areas: Set<AppArea> = setOf(AppArea.CASHIER, AppArea.ORDERS)) =
        SessionDisplayContext(actor, company, branch, "Synthetic receipt actor", "Synthetic retail company",
            "Synthetic branch", areas, workspaceMode = LocalSessionWorkspaceMode.CUSTOMER_RECEIPTS_ONLY)
    fun page33Id(index: Int) = DomainId("61000000-0000-4000-8000-${index.toString(16).padStart(12, '0')}")
    fun batch(first: DomainId) = LocalRecordReference(LocalRecordKind.CUSTOMER_RECEIPT_BATCH, first, Revision(0), sale)

    private fun scalar(field: LocalField, kind: LocalInputKind, initial: LocalScalarValue? = null, readOnly: Boolean = false) =
        LocalFormField.Scalar(field, text(field.name), kind, initialValue = initial, readOnly = readOnly)

    fun line(portion: DomainId, unit: DomainId, scale: Int, quantity: String? = null) = LocalInputLine(portion, listOf(
        LocalScalarInput(LocalField.PORTION_ID, LocalScalarValue.Identifier(portion)),
        LocalScalarInput(LocalField.UNIT, LocalScalarValue.Identifier(unit)),
        LocalScalarInput(LocalField.QUANTITY_SCALE, LocalScalarValue.Integer(scale.toLong())),
    ) + listOfNotNull(quantity?.let { LocalScalarInput(LocalField.QUANTITY, LocalScalarValue.Decimal(it)) }))

    fun form(initial: List<LocalInputLine> = listOf(line(kg, kgUnit, 3), line(item, itemUnit, 0))) =
        LocalActionForm(LocalAction.RETAIL_CUSTOMER_RECEIPT, 2, text("Record actual counter receipt — no payment"), listOf(
            scalar(LocalField.SALE_ID, LocalInputKind.IDENTIFIER, LocalScalarValue.Identifier(sale), true),
            scalar(LocalField.EXPECTED_CUSTOMER_RECEIPT_REVISION, LocalInputKind.INTEGER, LocalScalarValue.Integer(0), true),
            LocalFormField.Lines(LocalField.LINES, text("Chosen originals on this page"), listOf(
                scalar(LocalField.PORTION_ID, LocalInputKind.IDENTIFIER, readOnly = true),
                scalar(LocalField.UNIT, LocalInputKind.IDENTIFIER, readOnly = true),
                scalar(LocalField.QUANTITY_SCALE, LocalInputKind.INTEGER, readOnly = true),
                scalar(LocalField.QUANTITY, LocalInputKind.DECIMAL),
            ), maximumLines = 32, initialLines = initial, allowBlankLines = false),
            scalar(LocalField.CUSTOMER_RECEIVED_AT, LocalInputKind.TEXT),
            LocalFormField.Scalar(LocalField.CUSTOMER_RECEIPT_TIME_ZONE, text("Explicit Cairo confirmation"), LocalInputKind.CHOICE,
                choices = listOf(LocalChoice(zone, text(zone)))),
        ), confirmation = text("Attest actual receipt once; recover the original after a lost reply."))

    val rootValues get() = listOf(
        LocalScalarInput(LocalField.SALE_ID, LocalScalarValue.Identifier(sale)),
        LocalScalarInput(LocalField.EXPECTED_CUSTOMER_RECEIPT_REVISION, LocalScalarValue.Integer(0)),
        LocalScalarInput(LocalField.CUSTOMER_RECEIVED_AT, LocalScalarValue.Text(receivedAt)),
        LocalScalarInput(LocalField.CUSTOMER_RECEIPT_TIME_ZONE, LocalScalarValue.Choice(zone)),
    )

    fun original() = LocalCommandIntent(e1, LocalAction.RETAIL_CUSTOMER_RECEIPT, 2, batch, rootValues, LocalField.LINES,
        listOf(line(kg, kgUnit, 3, "0.500"), line(item, itemUnit, 0, "2")))
    fun unresolvedContinuation() = LocalWorkflowContinuation(
        LocalWorkspaceState(reference = batch, operationId = e1,
            operation = LocalOperationResult.Unresolved(e1, LocalServiceProblem.UNAVAILABLE)),
        original(), LocalAction.RETAIL_CUSTOMER_RECEIPT, null, null)

    fun record(reference: LocalRecordReference) = LocalRecord(reference, text("Synthetic receipt evidence"))
    fun page(query: LocalWorkflowQuery): LocalWorkflowPage = LocalWorkflowPage(query.workflow,
        query.reference?.let { listOf(record(it)) }.orEmpty(),
        if (query.reference?.kind == LocalRecordKind.CUSTOMER_RECEIPT_BATCH && query.workflow != LocalWorkflow.RETURNS)
            listOf(form()) else emptyList())
    fun pending(intent: LocalCommandIntent) = LocalPendingOperation(intent.operationId, intent.action,
        text("Saved original receipt observation"), intent.lines.map { row ->
            val quantity = (row.values.single { it.field == LocalField.QUANTITY }.value as LocalScalarValue.Decimal).canonical
            LocalRecordValue(text(row.lineId.value), text(quantity))
        }, submitAllowed = true)

    /** Pre-admitted UI result only: this fixture cannot prove v1.1 envelope or durable commit. */
    fun recorded() = LocalOperationResult.Recorded(e1, LocalRecordedDecision.RECORDED_ONLY,
        text("Original receipt observation recorded — no payment"), record(event), replayed = true)
}

/** Captures attempted calls without supplying policy, current authority, RPC or durable storage. */
internal class CounterReceiptUiPort : LocalWorkflowGateway {
    val reads = mutableListOf<LocalWorkflowQuery>()
    val pendingReads = mutableListOf<String?>()
    val stages = mutableListOf<LocalCommandIntent>()
    val submissions = mutableListOf<OperationId>()
    val lookups = mutableListOf<OperationId>()
    val acknowledgements = mutableListOf<OperationId>()
    var readReply: suspend (LocalWorkflowQuery) -> LocalReadResult = { LocalReadResult.Available(CounterReceiptUiInput.page(it)) }
    var stageReply: suspend (LocalCommandIntent) -> LocalStageResult = { LocalStageResult.Staged(CounterReceiptUiInput.pending(it)) }
    var submitReply: suspend (OperationId) -> LocalOperationResult = { LocalOperationResult.Unresolved(it, LocalServiceProblem.UNAVAILABLE) }
    var lookupReply: suspend (OperationId) -> LocalOperationResult = { LocalOperationResult.Unresolved(it, LocalServiceProblem.UNAVAILABLE) }

    override suspend fun read(query: LocalWorkflowQuery): LocalReadResult { reads += query; return readReply(query) }
    override suspend fun pending(cursor: String?): LocalPendingResult { pendingReads += cursor; return LocalPendingResult.Available(emptyList()) }
    override suspend fun stage(intent: LocalCommandIntent): LocalStageResult { stages += intent; return stageReply(intent) }
    override suspend fun submit(operationId: OperationId): LocalOperationResult { submissions += operationId; return submitReply(operationId) }
    override suspend fun lookup(operationId: OperationId): LocalOperationResult { lookups += operationId; return lookupReply(operationId) }
    override suspend fun acknowledgeRecorded(operationId: OperationId): LocalAcknowledgementResult {
        acknowledgements += operationId
        return LocalAcknowledgementResult.Acknowledged(operationId)
    }
    fun callCounts() = listOf(reads.size, pendingReads.size, stages.size, submissions.size, lookups.size, acknowledgements.size)
}

internal class CounterReceiptUiSession(override val workflows: CounterReceiptUiPort) : BranchUiSession {
    override val display = CounterReceiptUiInput.display()
    override val lifecycle = MutableStateFlow(LocalSessionLifecycle.ACTIVE)
    var current = true
    override fun isCurrent() = current
    override suspend fun current(): LocalSessionCheckResult = if (current) LocalSessionCheckResult.Current(display)
        else LocalSessionCheckResult.Unavailable(LocalServiceProblem.SESSION_ENDED)
    override suspend fun mayRestore(route: AppRoute) = true
    override fun close() { current = false; lifecycle.value = LocalSessionLifecycle.LOCKED }
}

internal class CounterReceiptUiFixture(val runtime: UkkeraRuntime, val session: CounterReceiptUiSession,
    val port: CounterReceiptUiPort, val controller: LocalWorkflowController) {
    fun token() = runtime.navigator.token()
    fun enterE1() {
        controller.openForm(LocalAction.RETAIL_CUSTOMER_RECEIPT, token())
        controller.edit(LocalField.QUANTITY, LocalDraftValue("0.500"), token(), CounterReceiptUiInput.kg)
        controller.edit(LocalField.QUANTITY, LocalDraftValue("2"), token(), CounterReceiptUiInput.item)
        controller.edit(LocalField.CUSTOMER_RECEIVED_AT, LocalDraftValue(CounterReceiptUiInput.receivedAt), token())
        controller.edit(LocalField.CUSTOMER_RECEIPT_TIME_ZONE,
            LocalDraftValue(selected = LocalScalarValue.Choice(CounterReceiptUiInput.zone)), token())
        controller.confirmForm(true, token())
    }
}

internal suspend fun TestScope.withCounterReceiptUi(
    workflow: LocalWorkflow = LocalWorkflow.CHECKOUT,
    reference: LocalRecordReference? = null,
    continuation: LocalWorkflowContinuation? = null,
    configure: (CounterReceiptUiPort) -> Unit = {},
    block: suspend CounterReceiptUiFixture.() -> Unit,
) {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val port = CounterReceiptUiPort().also(configure)
    val session = CounterReceiptUiSession(port)
    val login = object : BranchUiGateway {
        override val configured = true
        override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> = SignInResult.Authenticated(session)
    }
    val runtime = UkkeraRuntime(AppDependencies(branchGateway = login, platform = PlatformCapabilities(PlatformFamily.DESKTOP)))
    var controller: LocalWorkflowController? = null
    try {
        runtime.openSignIn(false, runtime.navigator.token())
        runtime.signIn("synthetic-receipt-owner", charArrayOf('x'), runtime.navigator.token())
        controller = LocalWorkflowController(runtime, session, runtime.navigator.token(), backgroundScope, continuation)
        controller.activate(workflow, reference)
        runCurrent()
        CounterReceiptUiFixture(runtime, session, port, controller).block()
    } finally {
        try { controller?.close(); runtime.close(); runCurrent() } finally { Dispatchers.resetMain() }
    }
}
