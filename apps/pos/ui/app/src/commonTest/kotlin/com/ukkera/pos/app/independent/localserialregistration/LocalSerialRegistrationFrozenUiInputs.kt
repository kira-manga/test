@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.independent.localserialregistration

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ukkera.pos.app.*
import com.ukkera.pos.app.navigation.*
import com.ukkera.pos.app.ui.SerialRegistrationEditor
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.koin.core.parameter.parametersOf
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Exact frozen request/display coordinates only; none is a native original or authority. */
internal object LsrUiInputs {
    val scope = BranchScope(id("11000000-0000-4000-8000-000000000001"),
        id("31000000-0000-4000-8000-000000000001"), id("41000000-0000-4000-8000-000000000001"))
    val actor = id("51000000-0000-4000-8000-000000000001")
    val operation = OperationId("f1000000-0000-4000-8000-000000000001")
    val registration = id("a1000000-0000-4000-8000-000000000001")
    val publication = id("b1000000-0000-4000-8000-000000000004")
    val serial = id("c1000000-0000-4000-8000-000000000001")
    val sku = id("d1000000-0000-4000-8000-000000000001")
    val skuPublication = id("e1000000-0000-4000-8000-000000000003")
    val from = UtcInstant.parseCanonical("2031-04-05T12:00:00Z")
    val until = UtcInstant.parseCanonical("2031-04-05T13:00:00Z")
    val digest = PayloadDigest("01".repeat(32))
    const val code = "000AbC-07"

    /** Requested revision-3 predecessor shape, never evidence of native completion. */
    fun predecessorShape() = LocalPublishedSerialRegistrationV1(scope, carrier(1), digest,
        LocalSerialAuthorityReferenceV1(carrier(2), carrier(3), digest, digest),
        id("b1000000-0000-4000-8000-000000000003"), registration, Revision(3),
        LocalSerialRegistrationReferenceV1(registration, id("b1000000-0000-4000-8000-000000000002"), Revision(2), digest),
        serial, code, sku, skuPublication, digest, from, until)

    fun source(successor: Boolean = true) = LocalSerialRegistrationAuthoringSource(registration, sku,
        skuPublication, digest,
        if (successor) LocalSerialRegistrationReferenceV1(registration,
            id("b1000000-0000-4000-8000-000000000003"), Revision(3), digest) else null,
        if (successor) predecessorShape() else null, if (successor) Revision(4) else Revision(1))

    fun editor(successor: Boolean = true) = SerialRegistrationEditor(registration.value, sku.value, code,
        serial, publication, from.value.toEpochMilliseconds().toString(), until.value.toEpochMilliseconds().toString(), source(successor))

    val summary = listOf(
        LocalRecordValue(text("Exact serial / code / SKU"), text("${serial.value} / $code / ${sku.value}")),
        LocalRecordValue(text("Registration / publication / revision"), text("${registration.value} / ${publication.value} / 4")),
        LocalRecordValue(text("Unqualified time; explicit activation required"), text("${from.value.toEpochMilliseconds()} / ${until.value.toEpochMilliseconds()}")))

    fun pending(submitAllowed: Boolean = true) = LocalPendingOperation(operation,
        LocalAction.SERIAL_REGISTRATION_ACTIVATE, text("Saved exact serial activation"), summary, submitAllowed)
    fun text(value: String) = LocalText(value, value)
    fun id(value: String) = DomainId(value)
    private fun carrier(index: Int) = id("74000000-0000-4000-8000-${index.toString().padStart(12, '0')}")
}

/** Controlled UI projection inputs; no authentication, durable save or successful activation. */
internal class LsrUiPort : LocalWorkflowGateway {
    var pendingResult: LocalPendingResult = LocalPendingResult.Available(listOf(LsrUiInputs.pending()))
    var lookupResult: LocalOperationResult = LocalOperationResult.Unresolved(LsrUiInputs.operation, LocalServiceProblem.UNAVAILABLE)
    val reads = mutableListOf<LocalWorkflowQuery>()
    val stages = mutableListOf<LocalCommandIntent>()
    val submissions = mutableListOf<OperationId>()
    val lookups = mutableListOf<OperationId>()
    var pendingCalls = 0
    var acknowledgements = 0
    private var heldLookup: CompletableDeferred<LocalOperationResult>? = null
    private val held = mutableListOf<CompletableDeferred<LocalOperationResult>>()
    fun holdNextLookup() = CompletableDeferred<LocalOperationResult>().also { heldLookup = it; held += it }
    fun releaseHeld() = held.forEach {
        it.complete(LocalOperationResult.Unresolved(LsrUiInputs.operation, LocalServiceProblem.UNAVAILABLE))
    }
    override suspend fun read(query: LocalWorkflowQuery): LocalReadResult {
        reads += query
        return LocalReadResult.Available(LocalWorkflowPage(query.workflow, emptyList(), emptyList()))
    }
    override suspend fun stage(intent: LocalCommandIntent): LocalStageResult {
        stages += intent
        return LocalStageResult.Uncertain(intent.operationId)
    }
    override suspend fun submit(operationId: OperationId): LocalOperationResult {
        submissions += operationId
        return LocalOperationResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE)
    }
    override suspend fun lookup(operationId: OperationId): LocalOperationResult {
        lookups += operationId
        val waiting = heldLookup
        heldLookup = null
        return if (waiting == null) lookupResult else withContext(NonCancellable) { waiting.await() }
    }
    override suspend fun pending(cursor: String?): LocalPendingResult { pendingCalls++; return pendingResult }
    override suspend fun acknowledgeRecorded(operationId: OperationId): LocalAcknowledgementResult {
        acknowledgements++
        return LocalAcknowledgementResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE)
    }
}

internal class LsrUiSession(initial: LsrUiPort) : BranchUiSession {
    override val display = SessionDisplayContext(LsrUiInputs.actor, LsrUiInputs.scope.companyId,
        LsrUiInputs.scope.branchId, "Synthetic serial requester", "Synthetic company", "Synthetic branch",
        setOf(AppArea.CATALOG), LocalSessionDraftEpochs(1, 1))
    override var workflows: LocalWorkflowGateway = initial
    override val lifecycle = MutableStateFlow(LocalSessionLifecycle.ACTIVE)
    override val attachmentUpdates = MutableStateFlow<LocalSessionAttachmentKey?>(LocalSessionAttachmentKey(LsrUiInputs.actor, 1))
    private var active = true
    private var replacement: LsrUiPort? = null
    override fun isCurrent() = active
    override suspend fun mayRestore(route: AppRoute) = active
    override suspend fun current(): LocalSessionCheckResult = if (active) LocalSessionCheckResult.Current(display, attachmentKey)
        else LocalSessionCheckResult.Unavailable(LocalServiceProblem.SESSION_ENDED, attachmentKey)
    fun replaceWith(port: LsrUiPort) { replacement = port }
    override suspend fun refresh(): LocalSessionCheckResult {
        workflows = requireNotNull(replacement)
        replacement = null
        attachmentUpdates.value = LocalSessionAttachmentKey(LsrUiInputs.actor, requireNotNull(attachmentKey).generation + 1)
        return LocalSessionCheckResult.Current(display, attachmentKey)
    }
    override fun close() { active = false; lifecycle.value = LocalSessionLifecycle.LOCKED }
}

internal class LsrUiFixture private constructor(val runtime: UkkeraRuntime, val session: LsrUiSession,
    val model: EntryViewModel, val port: LsrUiPort, private val scope: TestScope) {
    var controller = assertNotNull(model.localWorkflows(runtime, runtime.navigator.token()))
        private set
    private val ports = mutableListOf(port)
    fun token() = runtime.navigator.token()
    fun replaceAttachment(fresh: LsrUiPort): LocalWorkflowController {
        ports += fresh
        session.replaceWith(fresh)
        runtime.refreshLocalSession(token())
        scope.runCurrent()
        controller = assertNotNull(model.localWorkflows(runtime, token()))
        controller.activate(LocalWorkflow.CATALOG)
        return controller
    }
    fun releaseHeld() = ports.forEach(LsrUiPort::releaseHeld)

    companion object {
        suspend fun TestScope.withSerialController(block: suspend (LsrUiFixture) -> Unit) {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val port = LsrUiPort()
            val session = LsrUiSession(port)
            val runtime = UkkeraRuntime(AppDependencies(branchGateway = object : BranchUiGateway {
                override val configured = true
                override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> =
                    SignInResult.Authenticated(session)
            }, platform = PlatformCapabilities(PlatformFamily.DESKTOP)))
            var fixture: LsrUiFixture? = null
            try {
                runtime.openSignIn(false, runtime.navigator.token())
                runtime.signIn("synthetic-serial-requester", charArrayOf('x'), runtime.navigator.token())
                assertEquals(AppRoute.Catalog, runtime.navigator.state.value.activeEntry.route)
                val entry = runtime.navigator.state.value.activeEntry
                val root = runtime.rootScope
                val provider = ViewModelProvider.create(root.viewModelStore, factory = viewModelFactory {
                    initializer { root.koinScope.get<EntryViewModel> { parametersOf(entry.id) } }
                })
                fixture = LsrUiFixture(runtime, session, provider["entry-${entry.id}", EntryViewModel::class], port, this)
                fixture.controller.activate(LocalWorkflow.CATALOG)
                runCurrent()
                block(fixture)
            } finally {
                fixture?.releaseHeld()
                port.releaseHeld()
                try { runtime.close(); runCurrent() } finally { Dispatchers.resetMain() }
            }
        }
    }
}
