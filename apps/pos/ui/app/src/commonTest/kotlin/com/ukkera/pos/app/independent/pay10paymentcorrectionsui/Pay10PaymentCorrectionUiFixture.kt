@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.independent.pay10paymentcorrectionsui

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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Literal PAY-10 inputs. Controlled UI replies provide no native, RPC or ledger evidence. */
internal object Pay10UiInputs {
    val manager = id(1)
    val otherEmployee = id(2)
    val collector = id(3)
    val company = id(4)
    val branch = id(5)
    val sale = id(6)
    val payment = id(7)
    val cashSession = id(8)
    val correction = id(9)
    val evidence = id(10)
    val originalOperation = OperationId("a40f4978-c4fa-5844-a9a6-5b33b006e22b")
    val search = "payment-correction:${sale.value}"
    val source = LocalRecordReference(LocalRecordKind.PAYMENT_CORRECTION_SOURCE, sale)
    val history = LocalRecordReference(LocalRecordKind.PAYMENT_CORRECTION_HISTORY, payment, parentId = sale)
    const val reason = "synthetic receipt/count investigation"
    const val occurredAt = "2026-11-03T18:00:00Z"
    const val emptyPrompt = "Enter payment-correction:SALE_UUID and choose Refresh to read the original payment and linked history."

    fun id(number: Int) = DomainId("10000000-0000-4000-8000-${number.toString().padStart(12, '0')}")
    fun text(english: String, arabic: String = english) = LocalText(english, arabic)
    fun value(label: String, value: String) = LocalRecordValue(text(label), text(value))

    fun display(actor: DomainId = manager, areas: Set<AppArea> = setOf(AppArea.CASHIER, AppArea.ORDERS)) =
        SessionDisplayContext(actor, company, branch, "Synthetic manager", "Synthetic company", "Synthetic branch",
            areas, workspaceMode = LocalSessionWorkspaceMode.PAYMENT_CORRECTIONS_ONLY)

    fun form(): LocalActionForm {
        fun scalar(field: LocalField, kind: LocalInputKind, initial: LocalScalarValue? = null,
            readOnly: Boolean = false, required: Boolean = true) =
            LocalFormField.Scalar(field, text(field.name), kind, initialValue = initial, readOnly = readOnly, required = required)
        return LocalActionForm(LocalAction.PAYMENT_CLASSIFICATION_CORRECT, 1,
            text("Correct historical classification", "تصحيح التصنيف التاريخي"), listOf(
                scalar(LocalField.SALE_ID, LocalInputKind.IDENTIFIER, LocalScalarValue.Identifier(sale), true),
                scalar(LocalField.PAYMENT_ID, LocalInputKind.IDENTIFIER, LocalScalarValue.Identifier(payment), true),
                scalar(LocalField.EXPECTED_SESSION_REVISION, LocalInputKind.INTEGER, LocalScalarValue.Integer(1), true),
                scalar(LocalField.AMOUNT, LocalInputKind.DECIMAL, LocalScalarValue.Decimal("100.00"), true),
                LocalFormField.Scalar(LocalField.PAYMENT_METHOD, text("Historical payment method"), LocalInputKind.CHOICE,
                    choices = listOf(LocalChoice("CASH", text("Cash")), LocalChoice("EXTERNAL", text("External tender")))),
                scalar(LocalField.TENDER_ID, LocalInputKind.IDENTIFIER, required = false),
                scalar(LocalField.EXPECTED_REVISION, LocalInputKind.INTEGER, required = false),
                scalar(LocalField.PAYMENT_TERMINAL, LocalInputKind.TEXT, required = false),
                scalar(LocalField.EXTERNAL_REFERENCE, LocalInputKind.TEXT, required = false),
                scalar(LocalField.EVIDENCE_ID, LocalInputKind.IDENTIFIER),
                scalar(LocalField.OCCURRED_AT, LocalInputKind.TEXT),
                scalar(LocalField.REASON, LocalInputKind.MULTILINE)),
            confirmation = text("Confirm historical classification; no new physical money movement."))
    }

    fun originalPayment() = LocalRecord(history, text("Original paid payment", "دفع أصلي مدفوع"),
        text("Immutable attestation; classification is separate"), listOf(
            value("Original reported CARD (minor units)", "10000"),
            value("Original collector", collector.value), value("Original sale", sale.value)))

    fun originalClose() = LocalRecord(LocalRecordReference(LocalRecordKind.CASH_SESSION, cashSession),
        text("Original submitted close — immutable", "الإغلاق المقدم الأصلي — ثابت"), values = listOf(
            value("Original expected cash (minor units)", "100000"),
            value("Original actual count (minor units)", "110000"),
            value("Original variance (minor units)", "+10000")), selectable = false)

    fun historicalCorrection() = LocalRecord(LocalRecordReference(LocalRecordKind.PAYMENT_CORRECTION,
        correction, Revision(1), payment), text("Linked historical tender correction", "تصحيح تاريخي مرتبط لوسيلة الدفع"),
        values = listOf(value("Historical CARD classification (minor units)", "-10000"),
            value("Historical CASH classification (minor units)", "+10000"),
            value("Physical cash delta (minor units)", "0"),
            value("Original collector", collector.value), value("Correction actor", manager.value)), selectable = false)

    fun emptyPage(workflow: LocalWorkflow) = LocalWorkflowPage(workflow, emptyList(), emptyList(), notice = text(emptyPrompt))
    fun sourcePage(workflow: LocalWorkflow) = LocalWorkflowPage(workflow, listOf(originalPayment(), originalClose()), emptyList())
    fun historyPage(workflow: LocalWorkflow, includeCorrection: Boolean = false, offerForm: Boolean = true) =
        LocalWorkflowPage(workflow, listOf(originalPayment(), originalClose()) +
            if (includeCorrection) listOf(historicalCorrection()) else emptyList(),
            if (offerForm) listOf(form()) else emptyList(),
            notice = text("Today's drawer remains 50000 minor units; historical classification is separate from current collections."))

    val expectedValues: Map<LocalField, LocalScalarValue> get() = mapOf(
        LocalField.SALE_ID to LocalScalarValue.Identifier(sale), LocalField.PAYMENT_ID to LocalScalarValue.Identifier(payment),
        LocalField.EXPECTED_SESSION_REVISION to LocalScalarValue.Integer(1), LocalField.AMOUNT to LocalScalarValue.Decimal("100.00"),
        LocalField.PAYMENT_METHOD to LocalScalarValue.Choice("CASH"), LocalField.EVIDENCE_ID to LocalScalarValue.Identifier(evidence),
        LocalField.OCCURRED_AT to LocalScalarValue.Text(occurredAt), LocalField.REASON to LocalScalarValue.Text(reason))

    fun intent(operation: OperationId = originalOperation, action: LocalAction = LocalAction.PAYMENT_CLASSIFICATION_CORRECT) =
        LocalCommandIntent(operation, action, 1, history, expectedValues.map { LocalScalarInput(it.key, it.value) })

    fun pending(operation: OperationId, action: LocalAction = LocalAction.PAYMENT_CLASSIFICATION_CORRECT,
        submitAllowed: Boolean = true) = LocalPendingOperation(operation, action, text("Saved original classification"),
        summary = listOf(value("Original amount (minor units)", "10000")), submitAllowed = submitAllowed)

    fun recorded(operation: OperationId) = LocalOperationResult.Recorded(operation, LocalRecordedDecision.APPLIED,
        text("Historical correction recorded; no new physical cash."), result = originalPayment(), replayed = true)
}

/** Records every invocation, including malformed ones. It does not enforce profile or policy. */
internal class Pay10UiWorkflowPort(val actor: DomainId = Pay10UiInputs.manager) : LocalWorkflowGateway {
    val reads = mutableListOf<LocalWorkflowQuery>()
    val pendingReads = mutableListOf<String?>()
    val stages = mutableListOf<LocalCommandIntent>()
    val submissions = mutableListOf<Pair<DomainId, OperationId>>()
    val lookups = mutableListOf<Pair<DomainId, OperationId>>()
    var pendingReply: LocalPendingResult = LocalPendingResult.Available(emptyList())
    var readReply: ((LocalWorkflowQuery) -> LocalReadResult)? = null
    var stageReply: ((LocalCommandIntent) -> LocalStageResult)? = null
    var submitReply: (OperationId) -> LocalOperationResult = Pay10UiInputs::recorded
    var lookupReply: (OperationId) -> LocalOperationResult = Pay10UiInputs::recorded
    var includeCorrection = false
    var offerForm = true

    override suspend fun read(query: LocalWorkflowQuery): LocalReadResult {
        reads += query
        return readReply?.invoke(query) ?: LocalReadResult.Available(when {
            query.reference?.kind == LocalRecordKind.PAYMENT_CORRECTION_HISTORY ->
                Pay10UiInputs.historyPage(query.workflow, includeCorrection, offerForm)
            query.reference != null || query.search.isNotEmpty() -> Pay10UiInputs.sourcePage(query.workflow)
            else -> Pay10UiInputs.emptyPage(query.workflow)
        })
    }
    override suspend fun pending(cursor: String?): LocalPendingResult { pendingReads += cursor; return pendingReply }
    override suspend fun stage(intent: LocalCommandIntent): LocalStageResult {
        stages += intent
        return stageReply?.invoke(intent) ?: LocalStageResult.Staged(Pay10UiInputs.pending(intent.operationId))
    }
    override suspend fun submit(operationId: OperationId): LocalOperationResult {
        submissions += actor to operationId
        return submitReply(operationId)
    }
    override suspend fun lookup(operationId: OperationId): LocalOperationResult {
        lookups += actor to operationId
        return lookupReply(operationId)
    }
    fun callCounts() = listOf(reads.size, pendingReads.size, stages.size, submissions.size, lookups.size)
}

internal class Pay10UiSession(val port: Pay10UiWorkflowPort, override val display: SessionDisplayContext = Pay10UiInputs.display(port.actor)) : BranchUiSession {
    override val workflows: LocalWorkflowGateway = port
    override val lifecycle = MutableStateFlow(LocalSessionLifecycle.ACTIVE)
    var current = true
    var closeCalls = 0
    var logoutCalls = 0
    override fun isCurrent() = current
    override suspend fun current() = LocalSessionCheckResult.Current(display)
    override suspend fun mayRestore(route: AppRoute) = true
    override fun close() { closeCalls++; current = false; lifecycle.value = LocalSessionLifecycle.LOCKED }
    override suspend fun logout(): LocalSessionEndResult { logoutCalls++; return LocalSessionEndResult.UNRESOLVED }
}

internal class Pay10UiLoginPort(var nextSession: Pay10UiSession) : BranchUiGateway {
    override val configured = true
    override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> = SignInResult.Authenticated(nextSession)
}

internal class Pay10UiControllerFixture(val runtime: UkkeraRuntime, val session: Pay10UiSession,
    val login: Pay10UiLoginPort, val port: Pay10UiWorkflowPort, val controller: LocalWorkflowController) {
    fun token() = runtime.navigator.token()
    fun enterEvidence() {
        controller.openForm(LocalAction.PAYMENT_CLASSIFICATION_CORRECT, token())
        assertNotNull(controller.state.value.form)
        controller.edit(LocalField.PAYMENT_METHOD, LocalDraftValue(selected = LocalScalarValue.Choice("CASH")), token())
        controller.edit(LocalField.EVIDENCE_ID, LocalDraftValue(Pay10UiInputs.evidence.value), token())
        controller.edit(LocalField.OCCURRED_AT, LocalDraftValue(Pay10UiInputs.occurredAt), token())
        controller.edit(LocalField.REASON, LocalDraftValue(Pay10UiInputs.reason), token())
    }
    fun assertOriginalInput(intent: LocalCommandIntent) {
        assertEquals(LocalAction.PAYMENT_CLASSIFICATION_CORRECT, intent.action)
        assertEquals(1, intent.formVersion)
        assertEquals(Pay10UiInputs.history, intent.reference)
        assertEquals(Pay10UiInputs.expectedValues, intent.values.associate { it.field to it.value })
        assertEquals(emptyList(), intent.lines)
    }
}

internal suspend fun TestScope.withPay10UiController(reference: LocalRecordReference? = null,
    continuation: LocalWorkflowContinuation? = null, configure: (Pay10UiWorkflowPort) -> Unit = {},
    block: suspend Pay10UiControllerFixture.() -> Unit) {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val port = Pay10UiWorkflowPort().also(configure)
    val session = Pay10UiSession(port)
    val login = Pay10UiLoginPort(session)
    val runtime = UkkeraRuntime(AppDependencies(branchGateway = login, platform = PlatformCapabilities(PlatformFamily.DESKTOP)))
    var controller: LocalWorkflowController? = null
    try {
        runtime.openSignIn(false, runtime.navigator.token())
        runtime.signIn("synthetic-manager", charArrayOf('x'), runtime.navigator.token())
        controller = LocalWorkflowController(runtime, session, runtime.navigator.token(), backgroundScope, continuation)
        controller.activate(LocalWorkflow.CHECKOUT, reference)
        runCurrent()
        Pay10UiControllerFixture(runtime, session, login, port, controller).block()
    } finally {
        try { controller?.close(); runtime.close(); runCurrent() } finally { Dispatchers.resetMain() }
    }
}
