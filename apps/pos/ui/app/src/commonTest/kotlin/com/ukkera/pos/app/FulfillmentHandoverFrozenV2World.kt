package com.ukkera.pos.app

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.app.navigation.SecurityRoot
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withContext
import org.koin.core.parameter.parametersOf
import kotlin.coroutines.CoroutineContext

/** A missing observation is a wiring outcome, not an implementation pass or an ignored test. */
internal class HandoverWiringUnavailable(detail: String) : AssertionError("BLOCKED_WIRING: $detail")

internal enum class HandoverOutputMeaning { READ, SESSION_CONTROL, RESULT_ACK, HANDOVER, FINANCIAL, CUSTODY, UNKNOWN }

internal data class HandoverOutputSample(
    val ordinal: Int,
    val inFrozenWindow: Boolean,
    val scheduleOrdinal: Int,
    val method: String,
    val meaning: HandoverOutputMeaning,
    val action: LocalAction? = null,
    val operationId: OperationId? = null,
    val masterStopRequested: Boolean?,
)

internal data class HandoverIntentCounts(val handover: Int, val financial: Int, val custody: Int, val masterStop: Int)

/**
 * Exhaustive closed-port samples, not a native-host observer. Known reads/session cleanup and
 * result ACK have their public meanings; an unassigned port or action retains UNKNOWN. In
 * particular an arbitrary stage/submit is never assumed to be a non-stop or nonfinancial call.
 */
internal class HandoverClosedOutputs {
    private val retained = mutableListOf<HandoverOutputSample>()
    private val observedActions = mutableMapOf<OperationId, LocalAction>()
    private var frozenWindow = false
    private var scheduleOrdinal = 0
    val samples: List<HandoverOutputSample> get() = retained.toList()

    fun beginWindow() { check(!frozenWindow); frozenWindow = true; scheduleOrdinal = 1 }
    fun at(ordinal: Int) { check(frozenWindow); scheduleOrdinal = ordinal }
    fun endWindow() { check(frozenWindow); frozenWindow = false }

    fun record(method: String, meaning: HandoverOutputMeaning, action: LocalAction? = null,
        operationId: OperationId? = null) {
        retained += HandoverOutputSample(retained.size + 1, frozenWindow, scheduleOrdinal, method, meaning,
            action, operationId, masterStopRequested = if (meaning == HandoverOutputMeaning.UNKNOWN) null else false)
    }

    fun observePending(reply: LocalPendingResult) {
        if (reply is LocalPendingResult.Available) reply.operations.forEach { operation ->
            val before = observedActions[operation.operationId]
            if (before != null && before != operation.action) throw HandoverWiringUnavailable("conflicting public original-action association")
            observedActions[operation.operationId] = operation.action
        }
    }

    fun stage(intent: LocalCommandIntent) {
        observedActions[intent.operationId] = intent.action
        record("gateway.stage", actionMeaning(intent.action), intent.action, intent.operationId)
    }

    fun submit(id: OperationId) {
        val action = observedActions[id]
        record("gateway.submit", action?.let(::actionMeaning) ?: HandoverOutputMeaning.UNKNOWN, action, id)
    }

    private fun actionMeaning(action: LocalAction): HandoverOutputMeaning = when (action) {
        LocalAction.FOOD_SERVE, LocalAction.FOOD_PAID_PICKUP -> HandoverOutputMeaning.HANDOVER
        LocalAction.PAYMENT_ATTEST, LocalAction.RETURN_REFUND,
        LocalAction.RETAIL_PAYMENT_START, LocalAction.RETAIL_PAYMENT_APPLY, LocalAction.RETAIL_PAYMENT_RESOLUTION,
        LocalAction.RETAIL_REFUND_START, LocalAction.RETAIL_REFUND_APPLY, LocalAction.RETAIL_REFUND_RESOLUTION,
        LocalAction.FOOD_ACCEPT_HANDOFF, LocalAction.FOOD_BEGIN_COLLECTION, LocalAction.FOOD_FINALIZE_PAID,
        LocalAction.FOOD_RECORD_QUALITY_CLAIM, LocalAction.FOOD_PAYMENT_START, LocalAction.FOOD_PAYMENT_APPLY,
        LocalAction.FOOD_PAYMENT_RESOLUTION, LocalAction.FOOD_PAYMENT_RESOLUTION_APPLY,
        LocalAction.FOOD_REFUND_START, LocalAction.FOOD_REFUND_APPLY,
        LocalAction.FOOD_REFUND_RESOLUTION, LocalAction.FOOD_REFUND_RESOLUTION_APPLY,
        LocalAction.RETURN_CASE_RECORD, LocalAction.RETURN_CASE_ACCEPT,
        LocalAction.SUPPLIER_CASH_START, LocalAction.SUPPLIER_CASH_APPLY,
        LocalAction.SUPPLIER_CASH_RESOLUTION, LocalAction.SUPPLIER_CASH_RESOLUTION_APPLY,
        LocalAction.SUPPLIER_ADVANCE_ALLOCATE -> HandoverOutputMeaning.FINANCIAL
        LocalAction.CASH_BEGIN_CLOSE, LocalAction.CASH_CANCEL_CLOSE, LocalAction.CASH_SUBMIT_CLOSE,
        LocalAction.CASH_REVIEW_CLOSE, LocalAction.CASH_RECORD_COUNT, LocalAction.CASH_DELIVER_COUNTED,
        LocalAction.CASH_SUBMIT_COUNTED, LocalAction.CASH_DROP_TO_SAFE, LocalAction.CASH_RECEIVE_SAFE -> HandoverOutputMeaning.CUSTODY
        else -> HandoverOutputMeaning.UNKNOWN
    }

    fun countsInWindow(): HandoverIntentCounts {
        return classifiedCounts(retained.filter { it.inFrozenWindow })
    }

    fun classifiedCounts(samples: List<HandoverOutputSample>): HandoverIntentCounts {
        val unknown = samples.filter { it.meaning == HandoverOutputMeaning.UNKNOWN || it.masterStopRequested == null }
        if (unknown.isNotEmpty()) throw HandoverWiringUnavailable("unclassified actual outputs: ${unknown.map { it.method }}")
        return HandoverIntentCounts(samples.count { it.meaning == HandoverOutputMeaning.HANDOVER },
            samples.count { it.meaning == HandoverOutputMeaning.FINANCIAL }, samples.count { it.meaning == HandoverOutputMeaning.CUSTODY },
            samples.count { it.masterStopRequested == true })
    }
}

/**
 * A schedule label binds the first matching actual call AFTER arming. Other explicitly reserved
 * harmless calls are held until teardown, never completed inside the frozen window. Reserve
 * exhaustion is retained missing wiring, not a fixed-read-count oracle. Queues need not be empty.
 */
internal class HandoverDesignatedPort<I, O>(private val method: String, private val integrity: HandoverHarnessIntegrity) {
    private data class Bound<I, O>(val input: I, val reply: HandoverReplyPlan<I, O>)
    private val designated = ArrayDeque<Bound<I, O>>()
    private val reserved = mutableListOf<Bound<I, O>>()
    private val retainedInputs = mutableListOf<I>()
    val calls: List<I> get() = retainedInputs.toList()

    fun expect(input: I, reply: O, delivery: HandoverReplyDelivery): HandoverReplyPlan<I, O> =
        HandoverReplyPlan<I, O>(integrity, method, reply, delivery).also { designated.addLast(Bound(input, it)) }

    fun reserve(input: I, reply: O, count: Int) {
        repeat(count) { reserved += Bound(input, HandoverReplyPlan(integrity, method, reply,
            HandoverReplyDelivery.HELD_IGNORING_CANCELLATION)) }
    }

    suspend fun next(input: I): O {
        retainedInputs += input
        val selected = if (designated.firstOrNull()?.input == input) designated.removeFirst().reply
            else reserved.indexOfFirst { it.input == input }.takeIf { it >= 0 }?.let { reserved.removeAt(it).reply }
                ?: integrity.missingPlan(method)
        return selected.deliver(input)
    }
}

internal data class HandoverWorkspaceSample(
    val ordinal: Int,
    val inFrozenWindow: Boolean,
    val scheduleOrdinal: Int,
    val controllerOrdinal: Int,
    val attachment: LocalSessionAttachmentKey,
    val checkpoint: String?,
    val workflow: LocalWorkflow,
    val foodQuery: LocalFoodUiQuery,
    val search: String,
    val reference: LocalRecordReference?,
    val page: LocalWorkflowPage?,
    val readProblem: LocalServiceProblem?,
    val foodUnavailable: LocalFoodUnavailable?,
    val working: Boolean,
    val pending: List<LocalPendingOperation>,
    val formAction: LocalAction?,
    val operationId: OperationId?,
    val operation: LocalOperationResult?,
    val recoveryRequired: Boolean,
)

/** Snapshot only the three assigned body families. An unexpected body is not silently ignored. */
private fun immutableHandoverPage(page: LocalWorkflowPage?): LocalWorkflowPage? = page?.let { source ->
    val food = source.food?.let { food ->
        val body = when (val body = food.body) {
            is LocalFoodUiBody.Checks -> body.copy(checks = body.checks.map { it.copy(service = it.service.copy()) })
            is LocalFoodUiBody.Check -> body.copy(check = body.check.copy(service = body.check.service.copy()),
                portions = body.portions.map { it.copy() }, financial = body.financial?.copy())
            is LocalFoodUiBody.Financial -> body.copy(groups = body.groups.map { group ->
                group.copy(title = group.title.copy(), values = group.values.map { it.copy(label = it.label.copy(), value = it.value.copy()) })
            }, links = body.links.map { it.copy(title = it.title.copy()) })
            else -> throw HandoverWiringUnavailable("consumer emitted an unassigned food body")
        }
        food.copy(body = body)
    }
    // Retain unexpected generic rows/action forms too: their appearance must not become a
    // passing zero-control check. Unassigned report/price/opening payload capture stays blocked.
    if (source.priceControls != null || source.branchReport != null || source.forms.any { it.openingAllocations != null })
        throw HandoverWiringUnavailable("consumer emitted an unassigned report/price/opening payload")
    source.copy(records = source.records.map { row -> row.copy(reference = row.reference.copy(), title = row.title.copy(),
        status = row.status?.copy(), values = row.values.map { it.copy(label = it.label.copy(), value = it.value.copy()) }) },
        forms = source.forms.map { form -> form.copy(title = form.title.copy(), explanation = form.explanation?.copy(),
            confirmation = form.confirmation?.copy(), fields = form.fields.map { field -> when (field) {
                is LocalFormField.Scalar -> immutableHandoverScalarField(field)
                is LocalFormField.Lines -> field.copy(label = field.label.copy(), columns = field.columns.map(::immutableHandoverScalarField),
                    initialLines = field.initialLines.map { LocalInputLine(it.lineId, it.values.toList()) })
            } }) }, notice = source.notice?.copy(), food = food)
}

private fun immutableHandoverScalarField(field: LocalFormField.Scalar) = field.copy(label = field.label.copy(), help = field.help?.copy(),
    choices = field.choices.map { it.copy(label = it.label.copy()) }, references = field.references.map { it.copy(label = it.label.copy()) })

private fun immutableHandoverOperation(value: LocalOperationResult?): LocalOperationResult? = when (value) {
    null -> null
    is LocalOperationResult.Unresolved -> value.copy()
    is LocalOperationResult.Pending -> value.copy(operation = value.operation.copy(summary = value.operation.summary.map { it.copy() }))
    is LocalOperationResult.Recorded -> {
        if (value.result != null) throw HandoverWiringUnavailable("unassigned generic operation result")
        value.copy(message = value.message.copy(), food = value.food?.let { it.copy(portions = it.portions.map { portion -> portion.copy() }) })
    }
}

internal data class HandoverRuntimeSample(val ordinal: Int, val inFrozenWindow: Boolean, val scheduleOrdinal: Int,
    val presentation: RuntimePresentation, val root: SecurityRoot, val entryId: Long)

/**
 * Pure observation only. Dispatch directly instead of queuing an unconfined continuation behind
 * an emitting Main.immediate coroutine, which could otherwise conflate intervening StateFlow
 * values. Callbacks copy values and do not emit, suspend or drive production work. Reentrant
 * observation is retained as missing capture, not silently accepted. This is not a work dispatcher.
 */
private class HandoverInlineObservationDispatcher(private val failures: MutableList<String>) : CoroutineDispatcher() {
    private var dispatching = false
    override fun isDispatchNeeded(context: CoroutineContext): Boolean = true
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (dispatching) failures += "reentrant inline observation dispatch: continuous capture unverified"
        val before = dispatching
        dispatching = true
        try { block.run() } finally { dispatching = before }
    }
}

/** The fixed JVM tests and all port/flow callbacks run on the same actual UI event loop. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class HandoverFrozenV2World {
    val outputs = HandoverClosedOutputs()
    private val setupIntegrity = HandoverHarnessIntegrity()
    private val observerIntegrity = HandoverHarnessIntegrity()
    private val observerFailures = mutableListOf<String>()
    private val observerRoot = SupervisorJob()
    private val observerScope = CoroutineScope(observerRoot + HandoverInlineObservationDispatcher(observerFailures))
    private val checkpointScope = CoroutineScope(observerRoot + UnconfinedTestDispatcher())
    private val sessions = mutableListOf<Session>()
    private val entries = mutableListOf<Entry>()
    private val retainedWorkspace = mutableListOf<HandoverWorkspaceSample>()
    private val retainedRuntime = mutableListOf<HandoverRuntimeSample>()
    private val observerJobs = mutableListOf<Job>()
    private var inWindow = false
    private var scheduleOrdinal = 0
    private var closed = false
    val windowOpen: Boolean get() = inWindow
    val workspaceSamples: List<HandoverWorkspaceSample> get() = retainedWorkspace.toList()
    val runtimeSamples: List<HandoverRuntimeSample> get() = retainedRuntime.toList()

    val branchSignIns = HandoverReplyQueue<HandoverAccountSample, SignInResult<BranchUiSession>>("branch.signIn", setupIntegrity)
    private val cleanupStatus = HandoverReplyQueue<Unit, LocalClientCleanupStatus>("branch.cleanupStatus", setupIntegrity)
    private val retryCleanup = HandoverReplyQueue<Unit, LocalClientCleanupStatus>("branch.retryCleanup", setupIntegrity)
    private val branch = object : BranchUiGateway {
        override val configured: Boolean get() { outputs.record("branch.configured", HandoverOutputMeaning.READ); return true }
        override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> = signIn(account, secret, false)
        override suspend fun signIn(account: String, secret: CharArray, explicitDeviceMove: Boolean): SignInResult<BranchUiSession> {
            outputs.record("branch.signIn", HandoverOutputMeaning.SESSION_CONTROL)
            return branchSignIns.next(HandoverAccountSample(account, explicitDeviceMove))
        }
        override suspend fun cleanupStatus(): LocalClientCleanupStatus {
            outputs.record("branch.cleanupStatus", HandoverOutputMeaning.SESSION_CONTROL); return this@HandoverFrozenV2World.cleanupStatus.next(Unit)
        }
        override suspend fun retryCleanup(): LocalClientCleanupStatus {
            outputs.record("branch.retryCleanup", HandoverOutputMeaning.SESSION_CONTROL); return this@HandoverFrozenV2World.retryCleanup.next(Unit)
        }
    }
    private fun unsupported(method: String): Nothing {
        outputs.record(method, HandoverOutputMeaning.UNKNOWN)
        setupIntegrity.unsupportedCall(method)
    }
    private val central = object : CentralUiGateway {
        override val configured: Boolean get() = unsupported("central.configured")
        override suspend fun signIn(account: String, secret: CharArray): SignInResult<CentralUiSession> = unsupported("central.signIn")
    }
    private val enrollment = object : LocalEnrollmentUiGateway {
        override suspend fun status(): LocalEnrollmentStatus = unsupported("enrollment.status")
        override suspend fun enroll(requestId: DomainId, account: String, displayName: String, password: CharArray): LocalEnrollmentResult = unsupported("enrollment.enroll")
        override suspend fun lookup(requestId: DomainId): LocalEnrollmentResult = unsupported("enrollment.lookup")
    }
    private val files = object : LocalBranchReportFileDelivery() {
        override val bound: Boolean get() = unsupported("reportFiles.bound")
        override val commitGate: LocalReportCommitGate? get() = unsupported("reportFiles.commitGate")
        override suspend fun nativeChoice(suggestedName: String, lifetime: LocalBranchReportExportLifetime): LocalReportNativeChoice? = unsupported("reportFiles.nativeChoice")
        override fun hostCurrent(host: Any): Boolean = unsupported("reportFiles.hostCurrent")
        override suspend fun <T> disk(block: (LocalReportReceiptDisk) -> T): T = unsupported("reportFiles.disk")
    }
    val runtime = UkkeraRuntime(AppDependencies(branchGateway = branch, centralGateway = central,
        platform = PlatformCapabilities(PlatformFamily.DESKTOP), enrollmentGateway = enrollment,
        centralServicesEnabled = false, branchReportFiles = files))

    init {
        observerIntegrity.registerControllerRoot(observerRoot)
        check(runtime.dependencies.branchGateway === branch && runtime.dependencies.centralGateway === central &&
            runtime.dependencies.enrollmentGateway === enrollment && runtime.dependencies.branchReportFiles === files)
        observerJobs += observerScope.launch(start = CoroutineStart.UNDISPATCHED) {
            runtime.presentation.collect { presentation ->
                val nav = runtime.navigator.state.value
                retainedRuntime += HandoverRuntimeSample(retainedRuntime.size + 1, inWindow, scheduleOrdinal,
                    presentation.copy(display = presentation.display?.let { it.copy(allowedAreas = it.allowedAreas.toSet()) }),
                    nav.root, nav.activeEntry.id)
            }
        }
        observerJobs += observerScope.launch(start = CoroutineStart.UNDISPATCHED) {
            runtime.navigator.state.collect { nav ->
                retainedRuntime += HandoverRuntimeSample(retainedRuntime.size + 1, inWindow, scheduleOrdinal,
                    runtime.presentation.value.copy(display = runtime.presentation.value.display?.let { it.copy(allowedAreas = it.allowedAreas.toSet()) }),
                    nav.root, nav.activeEntry.id)
            }
        }
    }

    fun beginWindow() { check(!inWindow); inWindow = true; scheduleOrdinal = 1; outputs.beginWindow() }
    fun at(ordinal: Int) { check(inWindow); scheduleOrdinal = ordinal; outputs.at(ordinal) }
    fun endWindow() { check(inWindow); inWindow = false; outputs.endWindow() }
    fun mark(): Int = retainedWorkspace.size

    fun session(contextName: String): Session = Session(FrozenHandoverV2Inputs.contexts.getValue(contextName)).also(sessions::add)

    internal data class Entry(val ordinal: Int, val model: EntryViewModel, val controller: LocalWorkflowController,
        val session: Session, val attachment: LocalSessionAttachmentKey) {
        val route: AppRoute get() = AppRoute.Orders
    }

    suspend fun signIn(session: Session) {
        setupIntegrity.protectCaller(currentCoroutineContext()[Job])
        session.integrity.protectCaller(currentCoroutineContext()[Job])
        branchSignIns.plan(SignInResult.Authenticated(session), HandoverReplyDelivery.IMMEDIATE)
        runtime.openSignIn(company = false, token = runtime.navigator.token())
        runtime.signIn(session.context.actorId.value, "synthetic-presentation-only".toCharArray(), runtime.navigator.token())
        if (runtime.localSessionFor(runtime.navigator.token()) !== session)
            throw HandoverWiringUnavailable("actual runtime did not attach the planned session")
    }

    fun acquireEntry(session: Session): Entry {
        val root = runtime.rootScope
        val id = runtime.navigator.state.value.activeEntry.id
        val provider = ViewModelProvider.create(root.viewModelStore, factory = viewModelFactory {
            initializer { root.koinScope.get<EntryViewModel> { parametersOf(id) } }
        })
        val model = provider["entry-$id", EntryViewModel::class]
        val job = model.viewModelScope.coroutineContext[Job] ?: throw HandoverWiringUnavailable("actual ViewModel has no owned job")
        session.integrity.registerControllerRoot(job)
        val key = session.context.attachment
        val controller = model.localWorkflows(runtime, runtime.navigator.token(), key)
            ?: throw HandoverWiringUnavailable("actual entry factory did not create the current controller")
        if (entries.any { it.controller === controller }) throw HandoverWiringUnavailable("controller was acquired twice as two consumers")
        val entry = Entry(entries.size + 1, model, controller, session, key).also(entries::add)
        observerJobs += observerScope.launch(start = CoroutineStart.UNDISPATCHED) {
            controller.state.collect { state ->
                try { retain(entry, state, null) }
                catch (problem: HandoverWiringUnavailable) { observerFailures += problem.message ?: "missing workspace observation" }
            }
        }
        return entry
    }

    private fun retain(entry: Entry, state: LocalWorkspaceState, checkpoint: String?): HandoverWorkspaceSample =
        HandoverWorkspaceSample(retainedWorkspace.size + 1, inWindow, scheduleOrdinal, entry.ordinal, entry.attachment, checkpoint,
            state.workflow, state.foodQuery, state.search, state.reference, immutableHandoverPage(state.page), state.readProblem,
            state.foodUnavailable, state.working, state.pending.map { it.copy(summary = it.summary.map { value -> value.copy() }) },
            state.form?.descriptor?.action, state.operationId, immutableHandoverOperation(state.operation), state.recoveryRequired)
            .also(retainedWorkspace::add)

    fun checkpoint(entry: Entry, label: String): HandoverWorkspaceSample {
        if (observerJobs.any { !it.isActive } || observerFailures.isNotEmpty())
            throw HandoverWiringUnavailable("incomplete actual consumer emission capture: $observerFailures")
        return retain(entry, entry.controller.state.value, label)
    }

    /** Coordinate the next human entry event with an ACTUAL accepted runtime attachment emission. */
    fun <T> afterAcceptedAttachment(key: LocalSessionAttachmentKey, action: () -> T): Deferred<T> {
        val result = CompletableDeferred<T>()
        observerJobs += checkpointScope.launch(start = CoroutineStart.UNDISPATCHED) {
            runtime.presentation.collect { presentation ->
                if (presentation.localAttachment == key && !result.isCompleted) {
                    try { result.complete(action()) }
                    catch (problem: Throwable) {
                        observerFailures += "accepted-attachment checkpoint action did not complete"
                        result.completeExceptionally(problem)
                    }
                }
            }
        }
        return result
    }

    /** Observers deliberately outlive retired controller/ViewModel jobs and late RA completions. */
    suspend fun closeAndValidate(closeRuntimeOnUi: () -> Unit) {
        if (closed) throw HandoverWiringUnavailable("world teardown was reused")
        closed = true
        val ledgers = listOf(setupIntegrity) + sessions.map { it.integrity }
        val caller = currentCoroutineContext()[Job]
        (ledgers + observerIntegrity).forEach { it.protectCaller(caller) }
        withContext(NonCancellable) {
            var failure: Throwable? = null
            fun retain(problem: Throwable) { failure?.addSuppressed(problem) ?: run { failure = problem } }
            ledgers.flatMap { it.safeOwnedJobs() }.distinct().forEach { it.cancel() }
            try { closeRuntimeOnUi() } catch (problem: Throwable) { retain(problem) }
            ledgers.forEach { it.releaseAllHeldReplies() }
            // Release first, then join the actual retained consumers, including non-cooperative RA
            // and the runtime's detached refresh/logout jobs. Never join/cancel a caller ancestor.
            val joined = mutableSetOf<Job>()
            while (true) {
                val batch = ledgers.flatMap { it.safeOwnedJobs() }.distinct().filter { it !in joined }
                if (batch.isEmpty()) break
                batch.forEach { job ->
                    val owners = ledgers.filter { ledger -> ledger.safeOwnedJobs().any { it === job } }
                    if (owners.isNotEmpty()) try { job.cancel(); job.join(); owners.forEach { it.markJoined(job) } }
                    catch (problem: Throwable) { retain(problem) }
                    joined += job
                }
            }
            observerIntegrity.safeOwnedJobs().forEach { job ->
                try { job.cancel(); job.join(); observerIntegrity.markJoined(job) } catch (problem: Throwable) { retain(problem) }
            }
            if (observerFailures.isNotEmpty()) retain(HandoverWiringUnavailable("retained observer failures: $observerFailures"))
            (ledgers + observerIntegrity).forEach { ledger -> try { ledger.validateAfterTeardown() } catch (problem: Throwable) { retain(problem) } }
            failure?.let { throw it }
        }
    }

    inner class Session internal constructor(initial: FrozenHandoverV2Inputs.Context) : BranchUiSession {
        val integrity = HandoverHarnessIntegrity()
        var context: FrozenHandoverV2Inputs.Context = initial
            private set
        private var currentInput = true
        private val attachmentInput = MutableStateFlow<LocalSessionAttachmentKey?>(initial.attachment)
        private val lifecycleInput = MutableStateFlow(LocalSessionLifecycle.ACTIVE)
        val gateway = Gateway(integrity)
        val currentReplies = HandoverReplyQueue<Unit, LocalSessionCheckResult>("session.current", integrity)
        val refreshReplies = HandoverReplyQueue<Unit, LocalSessionCheckResult>("session.refresh", integrity)
        val restoreReplies = HandoverReplyQueue<AppRoute, Boolean>("session.mayRestore", integrity)
        val logoutReplies = HandoverReplyQueue<Unit, LocalSessionEndResult>("session.logout", integrity)
        private val retail = TrappedRetail(integrity)
        override val display: SessionDisplayContext get() {
            outputs.record("session.display", HandoverOutputMeaning.READ); return displayFor(context)
        }
        override val workflows: LocalWorkflowGateway get() { outputs.record("session.workflows", HandoverOutputMeaning.READ); return gateway }
        override val administration: LocalAdministrationUiGateway get() = reject("session.administration")
        override val companyCatalog: LocalCompanyCatalogGateway get() = reject("session.companyCatalog")
        override val retailPhysical: LocalRetailPhysicalGateway get() { outputs.record("session.retailPhysical", HandoverOutputMeaning.READ); return retail }
        override val attachmentUpdates: StateFlow<LocalSessionAttachmentKey?> get() { outputs.record("session.attachmentUpdates", HandoverOutputMeaning.READ); return attachmentInput }
        override val attachmentKey: LocalSessionAttachmentKey? get() { outputs.record("session.attachmentKey", HandoverOutputMeaning.READ); return attachmentInput.value }
        override val lifecycle: StateFlow<LocalSessionLifecycle> get() { outputs.record("session.lifecycle", HandoverOutputMeaning.READ); return lifecycleInput }
        override fun isCurrent(): Boolean { outputs.record("session.isCurrent", HandoverOutputMeaning.READ); return currentInput }
        override suspend fun current(): LocalSessionCheckResult { outputs.record("session.current", HandoverOutputMeaning.READ); return currentReplies.next(Unit) }
        override suspend fun refresh(): LocalSessionCheckResult { outputs.record("session.refresh", HandoverOutputMeaning.READ); return refreshReplies.next(Unit) }
        override suspend fun mayRestore(route: AppRoute): Boolean { outputs.record("session.mayRestore", HandoverOutputMeaning.READ); return restoreReplies.next(route) }
        override suspend fun logout(): LocalSessionEndResult { outputs.record("session.logout", HandoverOutputMeaning.SESSION_CONTROL); return logoutReplies.next(Unit) }
        override fun close() {
            outputs.record("session.close", HandoverOutputMeaning.SESSION_CONTROL)
            currentInput = false; attachmentInput.value = null; lifecycleInput.value = LocalSessionLifecycle.LOCKED
        }
        fun replaceWith(next: FrozenHandoverV2Inputs.Context) {
            context = next; currentInput = true; lifecycleInput.value = LocalSessionLifecycle.ACTIVE; attachmentInput.value = next.attachment
        }
        fun currentReply(): LocalSessionCheckResult.Current = LocalSessionCheckResult.Current(displayFor(context), context.attachment)
        private fun reject(method: String): Nothing { outputs.record(method, HandoverOutputMeaning.UNKNOWN); integrity.unsupportedCall(method) }
    }

    inner class Gateway internal constructor(val integrity: HandoverHarnessIntegrity) : LocalWorkflowGateway {
        val reads = HandoverDesignatedPort<LocalWorkflowQuery, LocalReadResult>("gateway.read", integrity)
        val pending = HandoverDesignatedPort<String?, LocalPendingResult>("gateway.pending", integrity)
        val lookups = HandoverDesignatedPort<OperationId, LocalOperationResult>("gateway.lookup", integrity)
        init {
            FrozenHandoverV2Inputs.queries.values.forEach { reads.reserve(it, FrozenHandoverV2Inputs.reads.getValue("UNAVAILABLE"), count = 4) }
            pending.reserve(null, LocalPendingResult.Available(emptyList(), null), count = 6)
            lookups.reserve(FrozenHandoverV2Inputs.OP_PICKUP_ORIGINAL_A, FrozenHandoverV2Inputs.operations.getValue("UNKNOWN"), count = 4)
        }
        override suspend fun read(query: LocalWorkflowQuery): LocalReadResult { outputs.record("gateway.read", HandoverOutputMeaning.READ); return reads.next(query) }
        override suspend fun pending(cursor: String?): LocalPendingResult {
            outputs.record("gateway.pending", HandoverOutputMeaning.READ)
            return pending.next(cursor).also(outputs::observePending)
        }
        override suspend fun lookup(operationId: OperationId): LocalOperationResult { outputs.record("gateway.lookup", HandoverOutputMeaning.READ, operationId = operationId); return lookups.next(operationId) }
        override suspend fun stage(intent: LocalCommandIntent): LocalStageResult { outputs.stage(intent); return integrity.unsupportedCall("gateway.stage.unassigned-response") }
        override suspend fun submit(operationId: OperationId): LocalOperationResult { outputs.submit(operationId); return integrity.unsupportedCall("gateway.submit.unassigned-response") }
        override suspend fun acknowledgeRecorded(operationId: OperationId): LocalAcknowledgementResult {
            outputs.record("gateway.acknowledgeRecorded", HandoverOutputMeaning.RESULT_ACK, operationId = operationId)
            return integrity.unsupportedCall("gateway.acknowledgeRecorded.unassigned-response")
        }
        override suspend fun references(query: LocalReferenceQuery): LocalReferenceResult = reject("gateway.references")
        override suspend fun exportBranchReport(selection: LocalBranchReportExportSelection, files: LocalBranchReportFileDelivery,
            lifetime: LocalBranchReportExportLifetime, progress: (LocalBranchReportExportProgress) -> Unit): LocalBranchReportExportResult = reject("gateway.exportBranchReport")
        override suspend fun branchReportExports(files: LocalBranchReportFileDelivery, lifetime: LocalBranchReportExportLifetime,
            after: DomainId?): LocalBranchReportExportHistory = reject("gateway.branchReportExports")
        override suspend fun recoverBranchReportExport(exportId: DomainId, files: LocalBranchReportFileDelivery,
            lifetime: LocalBranchReportExportLifetime): LocalBranchReportExportResult = reject("gateway.recoverBranchReportExport")
        private fun reject(method: String): Nothing { outputs.record(method, HandoverOutputMeaning.UNKNOWN); integrity.unsupportedCall(method) }
    }

    private inner class TrappedRetail(val integrity: HandoverHarnessIntegrity) : LocalRetailPhysicalGateway {
        private fun reject(method: String): Nothing { outputs.record("retail.$method", HandoverOutputMeaning.UNKNOWN); integrity.unsupportedCall("retail.$method") }
        override suspend fun paymentContext(collectionId: DomainId, afterTender: DomainId?): LocalRetailPhysicalReply = reject("paymentContext")
        override suspend fun foodPaymentContext(collectionId: DomainId): LocalRetailPhysicalReply = reject("foodPaymentContext")
        override suspend fun recoverFood(operationId: OperationId): LocalRetailPhysicalReply = reject("recoverFood")
        override suspend fun pendingFood(afterFact: DomainId?): LocalRetailPhysicalReply = reject("pendingFood")
        override suspend fun pendingFoodResolutions(afterFact: DomainId?): LocalRetailPhysicalReply = reject("pendingFoodResolutions")
        override suspend fun foodRefundContext(refundId: DomainId): LocalRetailPhysicalReply = reject("foodRefundContext")
        override suspend fun recoverFoodRefund(operationId: OperationId): LocalRetailPhysicalReply = reject("recoverFoodRefund")
        override suspend fun recoverFoodRefundAttempt(refundId: DomainId, attemptId: DomainId): LocalRetailPhysicalReply = reject("recoverFoodRefundAttempt")
        override suspend fun foodRefundOriginal(recordingOperationId: OperationId): LocalRetailPhysicalReply = reject("foodRefundOriginal")
        override suspend fun pendingFoodRefunds(afterFact: DomainId?): LocalRetailPhysicalReply = reject("pendingFoodRefunds")
        override suspend fun pendingFoodRefundResolutions(afterFact: DomainId?): LocalRetailPhysicalReply = reject("pendingFoodRefundResolutions")
        override suspend fun recoverSupplierCash(operationId: OperationId): LocalRetailPhysicalReply = reject("recoverSupplierCash")
        override suspend fun pendingSupplierCash(afterFact: DomainId?): LocalRetailPhysicalReply = reject("pendingSupplierCash")
        override suspend fun prepare(request: LocalRetailPhysicalRequest): LocalRetailPhysicalReply = reject("prepare")
        override suspend fun stage(preparation: LocalRetailPhysicalPreparation): LocalRetailPhysicalReply = reject("stage")
        override suspend fun start(operationId: OperationId, originalEntry: LocalRetailPhysicalLifetime): LocalRetailPhysicalReply = reject("start")
        override suspend fun continueSql(operationId: OperationId): LocalRetailPhysicalReply = reject("continueSql")
        override suspend fun recover(operationId: OperationId): LocalRetailPhysicalReply = reject("recover")
        override suspend fun pending(afterFact: DomainId?): LocalRetailPhysicalReply = reject("pending")
        override suspend fun refundDues(after: DomainId?): LocalRetailPhysicalReply = reject("refundDues")
        override suspend fun acceptedRefundDues(caseId: DomainId, returnId: DomainId): LocalRetailPhysicalReply = reject("acceptedRefundDues")
        override suspend fun pendingRefunds(afterFact: DomainId?): LocalRetailPhysicalReply = reject("pendingRefunds")
        override suspend fun recoverRefundOperation(operationId: OperationId): LocalRetailPhysicalReply = reject("recoverRefundOperation")
        override suspend fun recoverRefund(saleId: DomainId, claimId: DomainId, returnId: DomainId, attemptId: DomainId): LocalRetailPhysicalReply = reject("recoverRefund")
    }

    private fun displayFor(context: FrozenHandoverV2Inputs.Context) = SessionDisplayContext(context.actorId,
        context.companyId, context.branchId, "Synthetic employee ${context.actorId.value}",
        "Synthetic company ${context.companyId.value}", "Synthetic branch ${context.branchId.value}", setOf(AppArea.ORDERS))
}

internal data class HandoverAccountSample(val account: String, val explicitDeviceMove: Boolean)
