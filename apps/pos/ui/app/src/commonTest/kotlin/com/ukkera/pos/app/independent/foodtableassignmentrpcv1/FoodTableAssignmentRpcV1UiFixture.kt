@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.independent.foodtableassignmentrpcv1

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

/** Frozen FTA-RPC-v1 coordinates, carried by a controlled UI port; no native source or ledger. */
internal object TableAssignmentUiInput {
    val owner = id(21)
    val company = id(2)
    val branch = id(11)
    val floorId = id(101)
    val otherFloorId = id(102)
    val tableId = id(201)
    val otherTableId = id(202)
    val orderId = id(301)
    val otherOrderId = id(302)
    val a = OperationId(id(911).value)
    val b = OperationId(id(912).value)
    val target = LocalFoodUiTableTarget(orderId, Revision(7), null)
    val selection = LocalFoodUiQuery.TableDefinition(tableId, target)
    val floor = LocalFoodUiFloor(floorId, Revision(2), LocalText("Floor 1", "الطابق الأول"), 9, true)
    val table = LocalFoodUiTable(tableId, floorId, Revision(3), LocalText("Table 01", "طاولة 01"), 20, true)
    val destination = LocalFoodUiBody.Table(floor, table)
    val check = LocalFoodUiCheck(orderId, owner, Revision(7), "RESTAURANT",
        LocalFoodUiService("DINE_IN", "Guest note 017 / ملاحظة الضيف 017", "Window preference / بجانب النافذة"),
        2, null, null)
    val values = listOf(
        LocalScalarInput(LocalField.ORDER_ID, LocalScalarValue.Identifier(orderId)),
        LocalScalarInput(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION, LocalScalarValue.Integer(7)),
        LocalScalarInput(LocalField.FLOOR_ID, LocalScalarValue.Identifier(floorId)),
        LocalScalarInput(LocalField.EXPECTED_FLOOR_STORAGE_REVISION, LocalScalarValue.Integer(2)),
        LocalScalarInput(LocalField.TABLE_ID, LocalScalarValue.Identifier(tableId)),
        LocalScalarInput(LocalField.EXPECTED_TABLE_STORAGE_REVISION, LocalScalarValue.Integer(3)),
    )

    private fun id(number: Int) = DomainId("10000000-0000-4000-8000-${number.toString().padStart(12, '0')}")
    fun text(value: String) = LocalText(value, value)
    fun display() = SessionDisplayContext(owner, company, branch, "Synthetic check owner", "Synthetic restaurant",
        "Synthetic branch", setOf(AppArea.ORDERS), workspaceMode = LocalSessionWorkspaceMode.FOOD_TABLE_ASSIGNMENTS_ONLY)
    fun original() = LocalCommandIntent(a, LocalAction.FOOD_ASSIGN_TABLE, 1, null, values)
    fun form() = LocalActionForm(LocalAction.FOOD_ASSIGN_TABLE, 1, text("Assign this observed check"),
        values.map { entry -> LocalFormField.Scalar(entry.field, text(entry.field.name),
            if (entry.value is LocalScalarValue.Identifier) LocalInputKind.IDENTIFIER else LocalInputKind.INTEGER,
            initialValue = entry.value, readOnly = true) } + LocalFormField.Scalar(
            LocalField.EXPECTED_TABLE_ASSIGNMENT_STORAGE_REVISION, text("First assignment"), LocalInputKind.INTEGER,
            required = false, readOnly = true), confirmation = text("Review the original assignment before Submit"))

    fun page(query: LocalFoodUiQuery): LocalWorkflowPage {
        val body: LocalFoodUiBody = when (query) {
            is LocalFoodUiQuery.MyChecks -> LocalFoodUiBody.Checks(listOf(check))
            is LocalFoodUiQuery.Check -> LocalFoodUiBody.Check(check, emptyList(), Money(Currency.EGP, 30000), false,
                tableAssignmentAvailable = true)
            is LocalFoodUiQuery.TableAssignment -> LocalFoodUiBody.TableAssignment(target, null, null, false, false, true)
            is LocalFoodUiQuery.FloorDirectory -> LocalFoodUiBody.Floors(listOf(floor))
            is LocalFoodUiQuery.FloorTables -> LocalFoodUiBody.Tables(floor, listOf(table))
            is LocalFoodUiQuery.TableDefinition -> destination
            else -> error("Unsupported controlled read")
        }
        val forms = if (query is LocalFoodUiQuery.TableDefinition && query.assignment != null) listOf(form()) else emptyList()
        return LocalWorkflowPage(LocalWorkflow.FOOD, emptyList(), forms, food = LocalFoodUiPage(query,
            company, branch, owner, Revision(1), UtcInstant.parseCanonical("2026-11-04T21:30:00Z"), body))
    }

    fun pending() = LocalPendingOperation(a, LocalAction.FOOD_ASSIGN_TABLE, text("Original A"),
        values.map { LocalRecordValue(text(it.field.name), text(when (val value = it.value) {
            is LocalScalarValue.Identifier -> value.value.value
            is LocalScalarValue.Integer -> value.value.toString()
            else -> error("Unexpected frozen coordinate")
        })) }, submitAllowed = true)
    fun recorded(id: OperationId = a, decision: LocalRecordedDecision = LocalRecordedDecision.APPLIED) =
        LocalOperationResult.Recorded(id, decision, text("Qualified original assignment presentation"))
    fun continuation(uncertainStage: Boolean = false, hasPending: Boolean = false) = LocalWorkflowContinuation(
        LocalWorkspaceState(workflow = LocalWorkflow.FOOD, foodQuery = selection, operationId = a,
            operation = if (hasPending) LocalOperationResult.Pending(pending()) else LocalOperationResult.Unresolved(a, LocalServiceProblem.UNAVAILABLE),
            stageUncertain = uncertainStage), original(), LocalAction.FOOD_ASSIGN_TABLE, null, null)
}

/** Captures calls to the existing UI contract; it cannot establish durable storage or authority. */
internal class TableAssignmentUiPort : LocalWorkflowGateway {
    val reads = mutableListOf<LocalWorkflowQuery>()
    val stages = mutableListOf<LocalCommandIntent>()
    val submissions = mutableListOf<OperationId>()
    val lookups = mutableListOf<OperationId>()
    val acknowledgements = mutableListOf<OperationId>()
    var readReply: suspend (LocalWorkflowQuery) -> LocalReadResult = {
        LocalReadResult.Available(TableAssignmentUiInput.page(it.food!!))
    }
    var submitReply: suspend (OperationId) -> LocalOperationResult = { LocalOperationResult.Unresolved(it, LocalServiceProblem.UNAVAILABLE) }
    var lookupReply: suspend (OperationId) -> LocalOperationResult = { LocalOperationResult.Unresolved(it, LocalServiceProblem.UNAVAILABLE) }
    override suspend fun read(query: LocalWorkflowQuery): LocalReadResult { reads += query; return readReply(query) }
    override suspend fun pending(cursor: String?) = LocalPendingResult.Available(emptyList())
    override suspend fun stage(intent: LocalCommandIntent): LocalStageResult {
        stages += intent
        return LocalStageResult.Staged(TableAssignmentUiInput.pending())
    }
    override suspend fun submit(operationId: OperationId): LocalOperationResult { submissions += operationId; return submitReply(operationId) }
    override suspend fun lookup(operationId: OperationId): LocalOperationResult { lookups += operationId; return lookupReply(operationId) }
    override suspend fun acknowledgeRecorded(operationId: OperationId): LocalAcknowledgementResult {
        acknowledgements += operationId
        return LocalAcknowledgementResult.Acknowledged(operationId)
    }
}

internal class TableAssignmentUiSession(override val workflows: TableAssignmentUiPort) : BranchUiSession {
    override val display = TableAssignmentUiInput.display()
    override val lifecycle = MutableStateFlow(LocalSessionLifecycle.ACTIVE)
    var current = true
    override fun isCurrent() = current
    override suspend fun current(): LocalSessionCheckResult = if (current) LocalSessionCheckResult.Current(display)
        else LocalSessionCheckResult.Unavailable(LocalServiceProblem.SESSION_ENDED)
    override suspend fun mayRestore(route: AppRoute) = true
    override fun close() { current = false; lifecycle.value = LocalSessionLifecycle.LOCKED }
}

internal class TableAssignmentUiFixture(val runtime: UkkeraRuntime, val port: TableAssignmentUiPort,
    val controller: LocalWorkflowController) {
    fun token() = runtime.navigator.token()
}

internal suspend fun TestScope.withTableAssignmentUi(
    query: LocalFoodUiQuery = TableAssignmentUiInput.selection,
    continuation: LocalWorkflowContinuation? = null,
    configure: (TableAssignmentUiPort) -> Unit = {},
    block: suspend TableAssignmentUiFixture.() -> Unit,
) {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val port = TableAssignmentUiPort().also(configure)
    val session = TableAssignmentUiSession(port)
    val login = object : BranchUiGateway {
        override val configured = true
        override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> = SignInResult.Authenticated(session)
    }
    val runtime = UkkeraRuntime(AppDependencies(branchGateway = login, platform = PlatformCapabilities(PlatformFamily.DESKTOP)))
    var controller: LocalWorkflowController? = null
    try {
        runtime.openSignIn(false, runtime.navigator.token())
        runtime.signIn("synthetic-table-owner", charArrayOf('x'), runtime.navigator.token())
        controller = LocalWorkflowController(runtime, session, runtime.navigator.token(), backgroundScope, continuation)
        controller.activate(LocalWorkflow.FOOD, food = query)
        runCurrent()
        TableAssignmentUiFixture(runtime, port, controller).block()
    } finally {
        try { controller?.close(); runtime.close(); runCurrent() } finally { Dispatchers.resetMain() }
    }
}
