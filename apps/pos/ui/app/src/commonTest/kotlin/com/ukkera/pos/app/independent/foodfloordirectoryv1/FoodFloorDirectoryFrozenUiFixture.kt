@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.independent.foodfloordirectoryv1

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ukkera.pos.app.*
import com.ukkera.pos.app.navigation.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.koin.core.parameter.parametersOf
import kotlin.test.*

/** Literal BASE/creation/CAPACITY presentation inputs from the frozen semantic fixture. */
internal object FfdUiInputs {
    val company = DomainId("20ad0200-0000-4000-8000-000000000001")
    val branch = DomainId("30ad0300-0000-4000-8000-000000000001")
    val actor = DomainId("40ad0400-0000-4000-8000-000000000001")
    val floorA = LocalFoodUiFloor(DomainId("f1000000-0000-4000-8000-000000000010"), Revision(7), LocalText("Dining 10", "صالة ١٠"), 700, true)
    val floorB = LocalFoodUiFloor(DomainId("f1000000-0000-4000-8000-000000000020"), Revision(3), LocalText("Terrace 02", "شرفة ٠٢"), 100, false)
    val table010 = LocalFoodUiTable(DomainId("a2000000-0000-4000-8000-000000000010"), floorA.id, Revision(11), LocalText("Table 002", "طاولة ٠٠٢"), 9000, true)
    val table020 = LocalFoodUiTable(DomainId("a2000000-0000-4000-8000-000000000020"), floorB.id, Revision(2), LocalText("", "طاولة الشرفة"), 3, false)
    val createdFloor = LocalFoodUiFloor(DomainId("f1000000-0000-4000-8000-000000000030"), Revision(1), LocalText("New floor 003", "صالة جديدة ٠٠٣"), 42, true)
    val capacityFloor = LocalFoodUiFloor(DomainId("f2000000-0000-4000-8000-000000000001"), Revision(1), LocalText("Floor 01", "صالة"), 9998, false)
    fun capacityTable(serial: Int) = LocalFoodUiTable(DomainId("a3000000-0000-4000-8000-${serial.toString(16).padStart(12, '0')}"),
        capacityFloor.id, Revision(1), LocalText("Table ${serial.toString().padStart(3, '0')}", "طاولة"), 100 - serial, false)
    fun form(action: LocalAction, floor: LocalFoodUiFloor? = null, table: LocalFoodUiTable? = null): LocalActionForm {
        fun field(key: LocalField, kind: LocalInputKind, value: LocalScalarValue? = null, required: Boolean = true,
            readOnly: Boolean = false, maximum: Int = 160) = LocalFormField.Scalar(key, LocalText(key.name, key.name), kind,
                required = required, initialValue = value, readOnly = readOnly, maximumCharacters = maximum)
        val names = table?.names ?: if (action == LocalAction.FLOOR_CONFIGURE) floor?.names ?: createdFloor.names else LocalText("004", "طاولة ٠٠٤")
        val revision = if (action == LocalAction.FLOOR_CONFIGURE) floor?.storageRevision else table?.storageRevision
        val order = table?.layoutOrder ?: if (action == LocalAction.FLOOR_CONFIGURE) floor?.layoutOrder ?: 42 else 17
        val active = table?.active ?: if (action == LocalAction.FLOOR_CONFIGURE) floor?.active ?: true else true
        val identities = if (action == LocalAction.FLOOR_CONFIGURE) listOf(field(LocalField.FLOOR_ID, LocalInputKind.IDENTIFIER,
            floor?.id?.let { LocalScalarValue.Identifier(it) }, readOnly = true, maximum = 36)) else listOf(
            field(LocalField.TABLE_ID, LocalInputKind.IDENTIFIER, table?.id?.let { LocalScalarValue.Identifier(it) }, readOnly = true, maximum = 36),
            field(LocalField.FLOOR_ID, LocalInputKind.IDENTIFIER, LocalScalarValue.Identifier(checkNotNull(floor).id), readOnly = true, maximum = 36))
        return LocalActionForm(action, 1, LocalText("Configuration", "الإعداد"), identities + listOf(
            field(LocalField.EXPECTED_STORAGE_REVISION, LocalInputKind.INTEGER, revision?.let { LocalScalarValue.Integer(it.value) },
                required = false, readOnly = true, maximum = 19),
            field(LocalField.NAME_ENGLISH, LocalInputKind.TEXT, LocalScalarValue.Text(names.english), required = false),
            field(LocalField.NAME_ARABIC, LocalInputKind.TEXT, LocalScalarValue.Text(names.arabic), required = false),
            field(LocalField.LAYOUT_ORDER, LocalInputKind.INTEGER, LocalScalarValue.Integer(order.toLong()), maximum = 4),
            field(LocalField.ACTIVE, LocalInputKind.CHECKBOX, LocalScalarValue.Flag(active), required = false)),
            confirmation = LocalText("Save configuration", "حفظ الإعداد"))
    }
}

internal class HeldFloorUiRead {
    val requested = CompletableDeferred<Unit>()
    val reply = CompletableDeferred<LocalReadResult>()
    val returned = CompletableDeferred<Unit>()
}

/** Scripted presentation input only; no native credential, Master writer or client durability. */
internal class FloorUiPort : LocalWorkflowGateway {
    val reads = mutableListOf<LocalWorkflowQuery>()
    val stages = mutableListOf<LocalCommandIntent>()
    val submissions = mutableListOf<OperationId>()
    val lookups = mutableListOf<OperationId>()
    val acknowledgements = mutableListOf<OperationId>()
    var readProblem: LocalServiceProblem? = null
    var lookupResult: LocalOperationResult? = null
    var stagedOperation: LocalPendingOperation? = null
    var updatedCapacity = false
    private var nextHeld: HeldFloorUiRead? = null
    private val heldReads = mutableListOf<HeldFloorUiRead>()

    fun holdNextRead() = HeldFloorUiRead().also { check(nextHeld == null); nextHeld = it; heldReads += it }
    fun available(query: LocalFoodUiQuery): LocalReadResult.Available {
        val f = FfdUiInputs
        val body: LocalFoodUiBody
        val forms: List<LocalActionForm>
        var next: LocalFoodUiQuery? = null
        when (query) {
            is LocalFoodUiQuery.FloorDirectory -> {
                body = LocalFoodUiBody.Floors(emptyList()) // EMPTY presentation slice.
                forms = listOf(f.form(LocalAction.FLOOR_CONFIGURE))
            }
            is LocalFoodUiQuery.FloorTables -> {
                if (query.floorId == f.capacityFloor.id) {
                    val range = when (query.after) { null -> 1..20; f.capacityTable(20).id -> 21..40; else -> error("Unbound UI cursor") }
                    val floor = if (updatedCapacity) f.capacityFloor.copy(storageRevision = Revision(2), names = LocalText("Updated Floor 01", "صالة محدثة")) else f.capacityFloor
                    val tables = range.map { serial -> if (updatedCapacity && serial == 21) f.capacityTable(serial).copy(
                        storageRevision = Revision(2), names = LocalText("Updated Table 021", "طاولة محدثة")) else f.capacityTable(serial) }
                    body = LocalFoodUiBody.Tables(floor, tables)
                    forms = emptyList()
                    next = query.copy(after = f.capacityTable(range.last).id)
                } else {
                    check(query.floorId == f.createdFloor.id)
                    body = LocalFoodUiBody.Tables(f.createdFloor, emptyList())
                    forms = listOf(f.form(LocalAction.TABLE_CONFIGURE, f.createdFloor))
                }
            }
            is LocalFoodUiQuery.TableDefinition -> {
                check(query.tableId == f.table010.id)
                body = LocalFoodUiBody.Table(f.floorA, f.table010)
                forms = listOf(f.form(LocalAction.TABLE_CONFIGURE, f.floorA, f.table010))
            }
            else -> error("No frozen presentation input for this selector")
        }
        return LocalReadResult.Available(LocalWorkflowPage(LocalWorkflow.FOOD, emptyList(), forms,
            food = LocalFoodUiPage(query, f.company, f.branch, f.actor, Revision(1),
                UtcInstant.parseCanonical("2026-10-31T02:00:00Z"), body, next)))
    }

    override suspend fun read(query: LocalWorkflowQuery): LocalReadResult {
        reads += query
        nextHeld?.let { held ->
            nextHeld = null
            return withContext(NonCancellable) {
                held.requested.complete(Unit)
                held.reply.await().also { held.returned.complete(Unit) }
            }
        }
        return readProblem?.let { LocalReadResult.Unavailable(it) } ?: available(assertNotNull(query.food))
    }
    override suspend fun pending(cursor: String?) = LocalPendingResult.Available(emptyList())
    override suspend fun stage(intent: LocalCommandIntent): LocalStageResult {
        stages += intent
        val stored = LocalPendingOperation(intent.operationId, intent.action, LocalText("Stored configuration input", "إدخال إعداد محفوظ"),
            summary = emptyList(), submitAllowed = true)
        stagedOperation = stored
        return LocalStageResult.Staged(stored)
    }
    override suspend fun submit(operationId: OperationId): LocalOperationResult { submissions += operationId; error("No committed command fixture") }
    override suspend fun lookup(operationId: OperationId): LocalOperationResult {
        lookups += operationId
        return lookupResult ?: LocalOperationResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE)
    }
    override suspend fun acknowledgeRecorded(operationId: OperationId): LocalAcknowledgementResult { acknowledgements += operationId; error("No committed command fixture") }
    fun assertNoMutationCalls() { assertTrue(stages.isEmpty()); assertNoDispatchOrRecoveryCalls() }
    fun assertNoDispatchOrRecoveryCalls() { assertTrue(submissions.isEmpty()); assertTrue(lookups.isEmpty()); assertTrue(acknowledgements.isEmpty()) }
    fun releaseHeld() = heldReads.forEach { it.reply.complete(LocalReadResult.Unavailable(LocalServiceProblem.UNAVAILABLE)) }
}

private class FloorUiSession(override val workflows: LocalWorkflowGateway) : BranchUiSession {
    private var active = true
    override val display = SessionDisplayContext(FfdUiInputs.actor, FfdUiInputs.company, FfdUiInputs.branch,
        "Synthetic configurator", "Synthetic FOOD company", "Synthetic branch", setOf(AppArea.ORDERS), LocalSessionDraftEpochs(1, 1))
    override fun isCurrent() = active
    override suspend fun mayRestore(route: AppRoute) = active
    override fun close() { active = false }
}

internal class FloorUiFixture private constructor(val runtime: UkkeraRuntime, private val session: FloorUiSession,
    private val model: EntryViewModel, val port: FloorUiPort, private val scope: TestScope) {
    val controller = assertNotNull(model.localWorkflows(runtime, runtime.navigator.token()))
    fun token() = runtime.navigator.token()
    fun currentController() = assertNotNull(model.localWorkflows(runtime, token()))
    fun retireSession() { runtime.localSessionEnded(session); scope.runCurrent() }

    companion object {
        suspend fun TestScope.withController(query: LocalFoodUiQuery, block: suspend (FloorUiFixture) -> Unit) {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val port = FloorUiPort()
            val session = FloorUiSession(port)
            val runtime = UkkeraRuntime(AppDependencies(branchGateway = object : BranchUiGateway {
                override val configured = true
                override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> = SignInResult.Authenticated(session)
            }, platform = PlatformCapabilities(PlatformFamily.DESKTOP)))
            try {
                runtime.openSignIn(false, runtime.navigator.token())
                runtime.signIn("synthetic-configurator", charArrayOf('x'), runtime.navigator.token())
                assertEquals(NavigationResult.CHANGED, runtime.navigator.navigate(NavigationAction.Push(AppRoute.Food), runtime.navigator.token()))
                val entry = runtime.navigator.state.value.activeEntry
                val root = runtime.rootScope
                val provider = ViewModelProvider.create(root.viewModelStore, factory = viewModelFactory {
                    initializer { root.koinScope.get<EntryViewModel> { parametersOf(entry.id) } }
                })
                val fixture = FloorUiFixture(runtime, session, provider["entry-${entry.id}", EntryViewModel::class], port, this)
                fixture.controller.activate(LocalWorkflow.FOOD, food = query)
                runCurrent()
                block(fixture)
            } finally {
                port.releaseHeld()
                try { runtime.close(); runCurrent() } finally { Dispatchers.resetMain() }
            }
        }
    }
}
