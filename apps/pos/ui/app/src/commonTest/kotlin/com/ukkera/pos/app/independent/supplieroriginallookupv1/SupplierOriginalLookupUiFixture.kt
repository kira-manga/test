@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.independent.supplieroriginallookupv1

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ukkera.pos.app.*
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.app.navigation.NavigationToken
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.koin.core.parameter.parametersOf
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * supplier-acknowledged-original-lookup-v1, Profile S only. These UUID/revision
 * carriers are not durable preparation bindings or execution of the frozen
 * F-COMMITTED/F-REFUSED prefixes. No revision progression is established here.
 * The production supplier result shape exposes an original action/code and
 * supplier reference, not names, active, actor, scope or semantic digest. Those
 * omitted original fields still require the frozen authorized durable checks.
 */
internal object SupplierOriginalLookupUiInputs {
    val employee = DomainId("51000000-0000-4000-8000-000000000001")
    val company = DomainId("21000000-0000-4000-8000-000000000001")
    val branch = DomainId("31000000-0000-4000-8000-000000000001")
    val supplier = DomainId("41000000-0000-4000-8000-000000000001")
    val create = OperationId("71000000-0000-4000-8000-000000000001")
    val update = OperationId("71000000-0000-4000-8000-000000000002")
    val refuse = OperationId("71000000-0000-4000-8000-000000000003")
    val pending = OperationId("71000000-0000-4000-8000-000000000005")
    val laterNames = LocalText("Atlas Supplies East", "مستلزمات أطلس الشرقية")
    val afterRefusalNames = LocalText("Atlas Supplies South", "مستلزمات أطلس الجنوبية")
    const val refusedEnglishName = "Stale Supplier Name"

    fun text(value: String) = LocalText(value, value)

    private fun committed(id: OperationId, action: LocalAction, revision: Revision) =
        LocalOperationResult.Recorded(id, LocalRecordedDecision.APPLIED,
            text("Configuration result recorded: configuration.applied"),
            LocalRecord(LocalRecordReference(LocalRecordKind.SUPPLIER, supplier, revision),
                text(if (action == LocalAction.SUPPLIER_CREATE) "Create local supplier" else "Update local supplier"),
                text("configuration.applied")), replayed = true, originalAction = action)

    val resultCreate = committed(create, LocalAction.SUPPLIER_CREATE, Revision(0))
    val resultUpdate = committed(update, LocalAction.SUPPLIER_UPDATE, Revision(1))
    val resultRefuse = LocalOperationResult.Recorded(refuse, LocalRecordedDecision.REJECTED,
        text("Configuration result recorded: configuration.stale_revision"), replayed = true,
        originalAction = LocalAction.SUPPLIER_UPDATE)

    fun currentSupplier(afterRefusal: Boolean = false) = LocalRecord(
        LocalRecordReference(LocalRecordKind.SUPPLIER, supplier, Revision(if (afterRefusal) 2L else 1L)),
        if (afterRefusal) afterRefusalNames else laterNames,
        text(if (afterRefusal) "Active" else "Inactive"),
        values = listOf(LocalRecordValue(text("Active"), text(afterRefusal.toString()))))

    /** Only an editable name is needed to exercise the existing dirty-draft guard. */
    fun supplierForm() = LocalActionForm(LocalAction.SUPPLIER_UPDATE, 1, text("Update local supplier"),
        listOf(LocalFormField.Scalar(LocalField.NAME_ENGLISH, text("English name"), LocalInputKind.TEXT,
            initialValue = LocalScalarValue.Text(laterNames.english))))

    fun pendingOriginal(id: OperationId = pending) = LocalPendingOperation(id, LocalAction.SUPPLIER_UPDATE,
        text("Original supplier update awaiting recovery"),
        summary = listOf(LocalRecordValue(text("Supplier"), text(supplier.value))),
        phase = LocalPendingPhase.DISPATCH_UNRESOLVED)
}

internal class HeldSupplierOriginalLookup {
    val entered = CompletableDeferred<Unit>()
    val reply = CompletableDeferred<LocalOperationResult>()
    val returned = CompletableDeferred<Unit>()
}

/** Scripted callbacks and call observations; not a Master, journal, decoder or authority port. */
internal class ControlledSupplierOriginalLookupPort : LocalWorkflowGateway {
    val reads = mutableListOf<LocalWorkflowQuery>()
    val pendingReads = mutableListOf<String?>()
    val stages = mutableListOf<LocalCommandIntent>()
    val submissions = mutableListOf<OperationId>()
    val lookups = mutableListOf<OperationId>()
    val acknowledgements = mutableListOf<OperationId>()
    var currentSupplier = SupplierOriginalLookupUiInputs.currentSupplier()
    var pendingReply: LocalPendingResult = LocalPendingResult.Available(emptyList())
    var readProblem: LocalServiceProblem? = null
    var lookupReply: suspend (OperationId) -> LocalOperationResult = { id ->
        when (id) {
            SupplierOriginalLookupUiInputs.create -> SupplierOriginalLookupUiInputs.resultCreate
            SupplierOriginalLookupUiInputs.update -> SupplierOriginalLookupUiInputs.resultUpdate
            SupplierOriginalLookupUiInputs.refuse -> SupplierOriginalLookupUiInputs.resultRefuse
            else -> LocalOperationResult.Unresolved(id, LocalServiceProblem.UNAVAILABLE)
        }
    }
    var acknowledgementReply: suspend (OperationId) -> LocalAcknowledgementResult = {
        LocalAcknowledgementResult.Acknowledged(it)
    }
    private var nextHeldLookup: HeldSupplierOriginalLookup? = null
    private val heldLookups = mutableListOf<HeldSupplierOriginalLookup>()

    fun holdNextLookup() = HeldSupplierOriginalLookup().also {
        check(nextHeldLookup == null)
        nextHeldLookup = it
        heldLookups += it
    }

    override suspend fun read(query: LocalWorkflowQuery): LocalReadResult {
        reads += query
        return readProblem?.let { LocalReadResult.Unavailable(it) }
            ?: LocalReadResult.Available(LocalWorkflowPage(query.workflow, listOf(currentSupplier),
                listOf(SupplierOriginalLookupUiInputs.supplierForm())))
    }

    override suspend fun pending(cursor: String?): LocalPendingResult {
        pendingReads += cursor
        return pendingReply
    }

    override suspend fun stage(intent: LocalCommandIntent): LocalStageResult {
        stages += intent
        return LocalStageResult.NotStaged(LocalServiceProblem.INVALID_INPUT)
    }

    override suspend fun submit(operationId: OperationId): LocalOperationResult {
        submissions += operationId
        return LocalOperationResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE)
    }

    override suspend fun lookup(operationId: OperationId): LocalOperationResult {
        lookups += operationId
        nextHeldLookup?.let { held ->
            nextHeldLookup = null
            return withContext(NonCancellable) {
                held.entered.complete(Unit)
                held.reply.await().also { held.returned.complete(Unit) }
            }
        }
        return lookupReply(operationId)
    }

    override suspend fun acknowledgeRecorded(operationId: OperationId): LocalAcknowledgementResult {
        acknowledgements += operationId
        return acknowledgementReply(operationId)
    }

    fun assertNoMutationCalls() {
        assertTrue(stages.isEmpty(), "Lookup/ACK must not prepare a new command")
        assertTrue(submissions.isEmpty(), "Lookup/ACK must not dispatch a mutation")
    }

    fun releaseHeld() = heldLookups.forEach {
        it.reply.complete(LocalOperationResult.Unresolved(SupplierOriginalLookupUiInputs.create, LocalServiceProblem.UNAVAILABLE))
    }
}

internal class SupplierOriginalLookupUiSession(override val workflows: LocalWorkflowGateway) : BranchUiSession {
    override val display = SessionDisplayContext(SupplierOriginalLookupUiInputs.employee,
        SupplierOriginalLookupUiInputs.company, SupplierOriginalLookupUiInputs.branch,
        "Synthetic supplier owner", "Synthetic company", "Synthetic branch", setOf(AppArea.PURCHASING),
        workspaceMode = LocalSessionWorkspaceMode.SUPPLIER_ONLY)
    override val lifecycle = MutableStateFlow(LocalSessionLifecycle.ACTIVE)
    var current = true
    override fun isCurrent() = current
    override suspend fun current(): LocalSessionCheckResult = if (current) LocalSessionCheckResult.Current(display)
        else LocalSessionCheckResult.Unavailable(LocalServiceProblem.SESSION_ENDED)
    override suspend fun mayRestore(route: AppRoute) = current
    override fun close() { current = false; lifecycle.value = LocalSessionLifecycle.LOCKED }
}

internal class SupplierOriginalLookupUiFixture(
    val runtime: UkkeraRuntime,
    val session: SupplierOriginalLookupUiSession,
    val controller: LocalWorkflowController,
    val port: ControlledSupplierOriginalLookupPort,
    val signIns: List<String>,
) {
    fun token(): NavigationToken = runtime.navigator.token()
    fun enter(id: OperationId) = controller.changeOriginalOperationId(id.value, token())
    fun lookup(id: OperationId) { enter(id); controller.lookupOriginal(token()) }
}

internal suspend fun TestScope.withSupplierOriginalLookupUi(
    configure: (ControlledSupplierOriginalLookupPort) -> Unit = {},
    block: suspend SupplierOriginalLookupUiFixture.() -> Unit,
) {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val port = ControlledSupplierOriginalLookupPort().also(configure)
    val session = SupplierOriginalLookupUiSession(port)
    val signIns = mutableListOf<String>()
    val runtime = UkkeraRuntime(AppDependencies(branchGateway = object : BranchUiGateway {
        override val configured = true
        override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> {
            signIns += account
            return SignInResult.Authenticated(session)
        }
    }, platform = PlatformCapabilities(PlatformFamily.DESKTOP)))
    try {
        runtime.openSignIn(false, runtime.navigator.token())
        runtime.signIn("synthetic-supplier-owner", charArrayOf('x'), runtime.navigator.token())
        assertEquals(AppRoute.Purchasing, runtime.navigator.state.value.activeEntry.route)
        val entry = runtime.navigator.state.value.activeEntry
        val root = runtime.rootScope
        val provider = ViewModelProvider.create(root.viewModelStore, factory = viewModelFactory {
            initializer { root.koinScope.get<EntryViewModel> { parametersOf(entry.id) } }
        })
        val model = provider["entry-${entry.id}", EntryViewModel::class]
        val controller = assertNotNull(model.localWorkflows(runtime, runtime.navigator.token()))
        controller.activate(LocalWorkflow.PURCHASING)
        runCurrent()
        SupplierOriginalLookupUiFixture(runtime, session, controller, port, signIns).block()
    } finally {
        port.releaseHeld()
        try { runtime.close(); runCurrent() } finally { Dispatchers.resetMain() }
    }
}
