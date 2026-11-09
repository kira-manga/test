package com.ukkera.pos.app.independent.returnrightsauthoringv1

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ukkera.pos.app.AppDependencies
import com.ukkera.pos.app.BranchUiGateway
import com.ukkera.pos.app.BranchUiSession
import com.ukkera.pos.app.EntryViewModel
import com.ukkera.pos.app.LocalCommandIntent
import com.ukkera.pos.app.LocalOperationResult
import com.ukkera.pos.app.LocalPendingResult
import com.ukkera.pos.app.LocalReadResult
import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.app.LocalSessionAttachmentKey
import com.ukkera.pos.app.LocalSessionCheckResult
import com.ukkera.pos.app.LocalSessionDraftEpochs
import com.ukkera.pos.app.LocalStageResult
import com.ukkera.pos.app.LocalWorkflowGateway
import com.ukkera.pos.app.LocalWorkflowQuery
import com.ukkera.pos.app.PlatformCapabilities
import com.ukkera.pos.app.PlatformFamily
import com.ukkera.pos.app.SessionDisplayContext
import com.ukkera.pos.app.SignInResult
import com.ukkera.pos.app.UkkeraRuntime
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.app.ui.LocalReturnRightsEntryDrafts
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import org.koin.core.parameter.parametersOf
import kotlin.test.assertEquals

/** Controlled existing-session and negative workflow responses; native publication support remains genuinely unavailable. */
internal class ControlledReturnRightsAuthoringUi {
    val session = ControlledReturnRightsAuthoringSession()
    val runtime = UkkeraRuntime(AppDependencies(branchGateway = object : BranchUiGateway {
        override val configured = true
        override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> =
            SignInResult.Authenticated(session)
    }, platform = PlatformCapabilities(PlatformFamily.DESKTOP)))

    suspend fun attach() {
        runtime.openSignIn(company = false, token = runtime.navigator.token())
        runtime.signIn("synthetic-authoring-presentation", charArrayOf('s'), runtime.navigator.token())
        assertEquals(AppArea.CATALOG, runtime.navigator.state.value.selectedArea)
    }
    fun entry(): EntryViewModel {
        val root = runtime.rootScope
        val id = runtime.navigator.state.value.activeEntry.id
        val provider = ViewModelProvider.create(root.viewModelStore,
            factory = viewModelFactory { initializer { root.koinScope.get<EntryViewModel> { parametersOf(id) } } })
        return provider["entry-$id", EntryViewModel::class]
    }
    fun close() {
        session.ports.forEach { it.releaseHeldReplies() }
        runtime.close()
    }
}

internal class ControlledReturnRightsAuthoringSession : BranchUiSession {
    private val inputs = FrozenReturnRightsAuthoringUiInputs
    private var live = true
    private val attachments = MutableStateFlow<LocalSessionAttachmentKey?>(LocalSessionAttachmentKey(DomainId.random(), 1))
    var port = ControlledReturnRightsAuthoringWorkflow()
        private set
    val ports = mutableListOf(port)
    override val display = SessionDisplayContext(inputs.id("employeeE1"), inputs.id("companyC1"),
        DomainId(inputs.identities.getValue("branchB1").jsonObject.raaString("branchId")),
        "Synthetic employee", "Synthetic company", "Synthetic branch", setOf(AppArea.CATALOG, AppArea.SETUP),
        LocalSessionDraftEpochs(1, 1))
    override val workflows: LocalWorkflowGateway get() = port
    override val attachmentUpdates: StateFlow<LocalSessionAttachmentKey?> get() = attachments
    override fun isCurrent() = live && attachments.value != null
    override suspend fun current(): LocalSessionCheckResult = LocalSessionCheckResult.Current(display, attachments.value)
    override suspend fun mayRestore(route: AppRoute) = false
    override fun close() { live = false; attachments.value = null }
    fun replaceAttachment(): ControlledReturnRightsAuthoringWorkflow {
        port = ControlledReturnRightsAuthoringWorkflow().also { ports += it }
        attachments.value = requireNotNull(attachments.value).let { it.copy(generation = it.generation + 1) }
        return port
    }
}

internal class ControlledReturnRightsAuthoringWorkflow : LocalWorkflowGateway {
    var pendingReply: LocalPendingResult = LocalPendingResult.Available(emptyList())
    var pendingCalls = 0
        private set
    var readCalls = 0
        private set
    var commandCalls = 0
        private set
    private var nextHeld: ControlledReturnRightsAuthoringPending? = null
    private val held = mutableListOf<ControlledReturnRightsAuthoringPending>()
    fun holdNextPending() = ControlledReturnRightsAuthoringPending().also { nextHeld = it; held += it }
    override suspend fun pending(cursor: String?): LocalPendingResult {
        pendingCalls++
        val plan = nextHeld.also { nextHeld = null }
        if (plan == null) return pendingReply
        plan.captured.complete(Unit)
        val result = withContext(NonCancellable) { plan.reply.await() }
        plan.returned.complete(Unit)
        return result
    }
    override suspend fun read(query: LocalWorkflowQuery): LocalReadResult {
        readCalls++
        return LocalReadResult.Unavailable(LocalServiceProblem.UNAVAILABLE)
    }
    override suspend fun stage(intent: LocalCommandIntent): LocalStageResult = forbidden()
    override suspend fun submit(operationId: OperationId): LocalOperationResult = forbidden()
    override suspend fun lookup(operationId: OperationId): LocalOperationResult = forbidden()
    fun assertNoCommands() = assertEquals(0, commandCalls, "Presentation cleanup must not stage, submit or lookup")
    fun releaseHeldReplies() { held.forEach { it.reply.complete(LocalPendingResult.Unavailable(LocalServiceProblem.UNAVAILABLE)) } }
    private fun forbidden(): Nothing { commandCalls++; throw AssertionError("No command is expected at this presentation boundary") }
}

internal class ControlledReturnRightsAuthoringPending {
    val captured = CompletableDeferred<Unit>()
    val reply = CompletableDeferred<LocalPendingResult>()
    val returned = CompletableDeferred<Unit>()
}

internal fun LocalReturnRightsEntryDrafts.populateUnsigned(name: String = "D0") {
    val inputs = FrozenReturnRightsAuthoringUiInputs
    editor.value = inputs.editor(name)
    // An in-memory continuation coordinate only, not an authentic N0/J0 or approval.
    retainedActionId.value = DomainId(inputs.operationId.value)
    approvalHeld.value = true
    notice.value = inputs.draft(name).continuingRights
}
