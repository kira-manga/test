package com.ukkera.pos.app.independent.sessionpresentationv1

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ukkera.pos.app.AppDependencies
import com.ukkera.pos.app.BranchUiGateway
import com.ukkera.pos.app.BranchUiSession
import com.ukkera.pos.app.EntryViewModel
import com.ukkera.pos.app.LocalAcknowledgementResult
import com.ukkera.pos.app.LocalAction
import com.ukkera.pos.app.LocalActionForm
import com.ukkera.pos.app.LocalChoice
import com.ukkera.pos.app.LocalCommandIntent
import com.ukkera.pos.app.LocalField
import com.ukkera.pos.app.LocalFormField
import com.ukkera.pos.app.LocalInputKind
import com.ukkera.pos.app.LocalInputLine
import com.ukkera.pos.app.LocalOperationResult
import com.ukkera.pos.app.LocalPendingOperation
import com.ukkera.pos.app.LocalPendingResult
import com.ukkera.pos.app.LocalReadResult
import com.ukkera.pos.app.LocalRecordKind
import com.ukkera.pos.app.LocalRecordReference
import com.ukkera.pos.app.LocalReferenceChoice
import com.ukkera.pos.app.LocalReferenceQuery
import com.ukkera.pos.app.LocalReferenceResult
import com.ukkera.pos.app.LocalScalarInput
import com.ukkera.pos.app.LocalScalarValue
import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.app.LocalSessionAttachmentKey
import com.ukkera.pos.app.LocalSessionCheckResult
import com.ukkera.pos.app.LocalSessionDraftEpochs
import com.ukkera.pos.app.LocalSessionEndResult
import com.ukkera.pos.app.LocalSessionLifecycle
import com.ukkera.pos.app.LocalStageResult
import com.ukkera.pos.app.LocalText
import com.ukkera.pos.app.LocalWorkflow
import com.ukkera.pos.app.LocalWorkflowController
import com.ukkera.pos.app.LocalWorkflowGateway
import com.ukkera.pos.app.LocalWorkflowPage
import com.ukkera.pos.app.LocalWorkflowQuery
import com.ukkera.pos.app.PlatformCapabilities
import com.ukkera.pos.app.PlatformFamily
import com.ukkera.pos.app.SessionDisplayContext
import com.ukkera.pos.app.SignInResult
import com.ukkera.pos.app.UkkeraRuntime
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.app.navigation.RouteRegistry
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.koin.core.parameter.parametersOf

/**
 * Controlled replies exercise the real common runtime, entry and controller. They do not
 * authenticate an employee, activate policy, commit business work or qualify an admitted RPC.
 * The boundary performs no retirement, callback suppression or presentation cleanup itself.
 */
internal class SensitivePresentationRuntimeFixture(initialSession: SensitiveControlledSession) {
    val gateway = SensitiveControlledSignIn(initialSession)
    val runtime = UkkeraRuntime(AppDependencies(branchGateway = gateway,
        platform = PlatformCapabilities(PlatformFamily.DESKTOP)))

    suspend fun signIn() {
        runtime.openSignIn(company = false, runtime.navigator.token())
        runtime.signIn(gateway.session.display.actorName, charArrayOf('s', 'a', 'm', 'p', 'l', 'e'),
            runtime.navigator.token())
        check(runtime.presentation.value.display == gateway.session.display)
    }

    fun activeEntry(): EntryViewModel {
        val id = runtime.navigator.state.value.activeEntry.id
        val root = runtime.rootScope
        val provider = ViewModelProvider.create(root.viewModelStore,
            factory = viewModelFactory { initializer { root.koinScope.get<EntryViewModel> { parametersOf(id) } } })
        return provider["entry-$id", EntryViewModel::class]
    }

    fun controller(): LocalWorkflowController = requireNotNull(
        activeEntry().localWorkflows(runtime, runtime.navigator.token()),
    )

    fun releaseReplies() { gateway.sessions.forEach(SensitiveControlledSession::releaseReplies) }
    fun close() { releaseReplies(); runtime.close() }
    val calls: List<SensitivePresentationCall> get() = gateway.sessions.flatMap { session ->
        session.boundaries.flatMap { it.calls }
    }
}

internal class SensitiveControlledSignIn(initialSession: SensitiveControlledSession) : BranchUiGateway {
    var session = initialSession
        private set
    val sessions = mutableListOf(initialSession)
    var signIns = 0
        private set
    override val configured = true

    fun useNextSession(next: SensitiveControlledSession) {
        session = next
        sessions += next
    }

    override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> {
        signIns++
        check(account == session.display.actorName)
        return SignInResult.Authenticated(session)
    }
}

internal class SensitiveControlledSession(
    initialDisplay: SessionDisplayContext = sensitiveControlDisplay(),
    initialBoundary: SensitiveControlledWorkflowBoundary = SensitiveControlledWorkflowBoundary(),
) : BranchUiSession {
    private var currentDisplay = initialDisplay
    private var closed = false
    // Ephemeral owner coordinates, never provisioned session/device/authority evidence.
    private val attachments = MutableStateFlow<LocalSessionAttachmentKey?>(
        LocalSessionAttachmentKey(DomainId.random(), 1))
    private val lifecycleState = MutableStateFlow(LocalSessionLifecycle.ACTIVE)
    private var plannedRenewal: Pair<SessionDisplayContext, SensitiveControlledWorkflowBoundary>? = null
    var boundary = initialBoundary.also { it.attachment = attachments.value }
        private set
    val boundaries = mutableListOf(boundary)
    var workflowAcquisitions = 0
        private set
    override val display: SessionDisplayContext get() = currentDisplay
    override val workflows: LocalWorkflowGateway get() { workflowAcquisitions++; return boundary }
    override val attachmentUpdates: StateFlow<LocalSessionAttachmentKey?> get() = attachments
    override val lifecycle: StateFlow<LocalSessionLifecycle> get() = lifecycleState
    override fun isCurrent(): Boolean = !closed && attachments.value != null

    fun planRenewal(next: SessionDisplayContext, nextBoundary: SensitiveControlledWorkflowBoundary) {
        check(plannedRenewal == null)
        plannedRenewal = next to nextBoundary
    }

    /** An already-valid-session policy observation, not positive zero-grant auth admission. */
    fun publishCurrent(next: SessionDisplayContext, nextBoundary: SensitiveControlledWorkflowBoundary) {
        check(isCurrent())
        val previous = requireNotNull(attachments.value)
        currentDisplay = next
        boundary = nextBoundary.also {
            it.attachment = previous.copy(generation = previous.generation + 1)
            boundaries += it
        }
        attachments.value = boundary.attachment
    }

    override suspend fun current(): LocalSessionCheckResult = if (isCurrent())
        LocalSessionCheckResult.Current(currentDisplay, attachments.value)
        else LocalSessionCheckResult.Unavailable(LocalServiceProblem.SESSION_ENDED, attachments.value)

    override suspend fun refresh(): LocalSessionCheckResult {
        val plan = plannedRenewal ?: return LocalSessionCheckResult.Unavailable(
            LocalServiceProblem.UNAVAILABLE, attachments.value)
        plannedRenewal = null
        publishCurrent(plan.first, plan.second)
        return LocalSessionCheckResult.Current(currentDisplay, attachments.value)
    }

    override suspend fun mayRestore(route: AppRoute): Boolean = isCurrent() &&
        RouteRegistry.area(route) in currentDisplay.allowedAreas

    // A controlled unavailable close cannot establish native revocation or confirmed close.
    override suspend fun logout(): LocalSessionEndResult = LocalSessionEndResult.UNRESOLVED
    override fun close() {
        closed = true
        attachments.value = null
        lifecycleState.value = LocalSessionLifecycle.LOCKED
    }
    fun releaseReplies() { boundaries.forEach(SensitiveControlledWorkflowBoundary::releaseReply) }
}

internal data class SensitivePresentationCall(
    val method: String,
    val operationId: OperationId? = null,
    val attachment: LocalSessionAttachmentKey? = null,
    val query: LocalWorkflowQuery? = null,
)

/** Available/Recorded below are explicitly controlled presentation inputs, not master evidence. */
internal class SensitiveControlledWorkflowBoundary(
    private val readReply: (LocalWorkflowQuery) -> LocalReadResult = ::emptySensitiveControlRead,
    private val saved: List<LocalPendingOperation> = emptyList(),
    private val lookupReply: (OperationId) -> LocalOperationResult = {
        LocalOperationResult.Unresolved(it, LocalServiceProblem.UNAVAILABLE)
    },
    private val referenceReply: (LocalReferenceQuery) -> LocalReferenceResult = {
        LocalReferenceResult.Unavailable(LocalServiceProblem.NOT_IMPLEMENTED)
    },
) : LocalWorkflowGateway {
    var attachment: LocalSessionAttachmentKey? = null
    val calls = mutableListOf<SensitivePresentationCall>()
    private var heldLookup: SensitiveHeldLookup? = null

    fun holdNextLookup(reply: LocalOperationResult): SensitiveHeldLookup {
        check(heldLookup == null)
        return SensitiveHeldLookup(reply).also { heldLookup = it }
    }
    fun releaseReply() { heldLookup?.release() }
    override suspend fun read(query: LocalWorkflowQuery): LocalReadResult {
        calls += SensitivePresentationCall("READ", attachment = attachment, query = query)
        return readReply(query)
    }
    override suspend fun references(query: LocalReferenceQuery): LocalReferenceResult {
        calls += SensitivePresentationCall("REFERENCES", attachment = attachment)
        return referenceReply(query)
    }
    override suspend fun pending(cursor: String?): LocalPendingResult {
        calls += SensitivePresentationCall("PENDING", attachment = attachment)
        return LocalPendingResult.Available(saved)
    }
    override suspend fun stage(intent: LocalCommandIntent): LocalStageResult {
        calls += SensitivePresentationCall("STAGE", intent.operationId, attachment)
        return LocalStageResult.NotStaged(LocalServiceProblem.UNAVAILABLE)
    }
    override suspend fun submit(operationId: OperationId): LocalOperationResult {
        calls += SensitivePresentationCall("SUBMIT", operationId, attachment)
        return LocalOperationResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE)
    }
    override suspend fun lookup(operationId: OperationId): LocalOperationResult {
        calls += SensitivePresentationCall("LOOKUP", operationId, attachment)
        val held = heldLookup
        if (held != null) {
            held.captured.complete(Unit)
            // Let an old queued reply return after the real entry has cancelled its coroutine.
            // No callback/attachment safety decision is supplied by this boundary.
            withContext(NonCancellable) { held.awaitRelease() }
            return held.reply
        }
        return lookupReply(operationId)
    }
    override suspend fun acknowledgeRecorded(operationId: OperationId): LocalAcknowledgementResult {
        calls += SensitivePresentationCall("ACKNOWLEDGE", operationId, attachment)
        return LocalAcknowledgementResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE)
    }
}

internal class SensitiveHeldLookup(val reply: LocalOperationResult) {
    val captured = CompletableDeferred<Unit>()
    private val released = CompletableDeferred<Unit>()
    fun release() { released.complete(Unit) }
    suspend fun awaitRelease() { released.await() }
}

internal fun sensitiveControlDisplay(): SessionDisplayContext = SessionDisplayContext(
    actorId = DomainId("00000000-0000-4000-8000-000000000001"),
    companyId = DomainId("00000000-0000-4000-8000-000000000002"),
    branchId = DomainId("00000000-0000-4000-8000-000000000003"),
    actorName = "ACTOR-A", companyName = "COMPANY-1", branchName = "BRANCH-A",
    allowedAreas = setOf(AppArea.CASHIER, AppArea.ORDERS, AppArea.REPORTS),
    localDraftEpochs = LocalSessionDraftEpochs(1, 1),
)

internal fun sensitiveControlText(value: String) = LocalText(value, value)

private fun emptySensitiveControlRead(query: LocalWorkflowQuery): LocalReadResult =
    if (query.workflow == LocalWorkflow.FOOD) LocalReadResult.Unavailable(LocalServiceProblem.UNAVAILABLE)
    else LocalReadResult.Available(LocalWorkflowPage(query.workflow, emptyList(), emptyList()))

/**
 * UI-contract metadata carriers using the fixed LSP private marker/cost numbers. This is not
 * a proposed ORDER_CREATE body or an independently bound business form schema. No form is
 * staged. Only the explicitly supported note/reason and search have a retention assertion;
 * optional fields/line edits receive no new retention expectation (AMB-005).
 */
internal class SensitiveFormControl {
    val reference = LocalRecordReference(LocalRecordKind.SKU, DomainId.random())
    val lineId = DomainId.random()

    fun page(old: Boolean, workflow: LocalWorkflow = LocalWorkflow.CHECKOUT): LocalReadResult = LocalReadResult.Available(LocalWorkflowPage(
        workflow, emptyList(), listOf(form(old)),
        notice = sensitiveControlText(if (old) FrozenSensitivePresentationUiInputs.PRIVATE_MARKER else "Current read")))

    private fun form(old: Boolean): LocalActionForm {
        val privateText = FrozenSensitivePresentationUiInputs.PRIVATE_MARKER
        fun label(current: String) = sensitiveControlText(if (old) privateText else current)
        fun value() = LocalScalarValue.Text(if (old) privateText else "Current read value")
        val columns = listOf(
            LocalFormField.Scalar(LocalField.NOTES, label("Current line note"), LocalInputKind.MULTILINE,
                required = false, help = label("Current line help")),
            LocalFormField.Scalar(LocalField.LABEL, label("Current line label"), LocalInputKind.TEXT,
                required = false, initialValue = value(), readOnly = true),
            LocalFormField.Scalar(LocalField.ORIGINAL_OWNED_VALUE, label("Current line value"), LocalInputKind.INTEGER,
                required = false, initialValue = if (old) LocalScalarValue.Integer(2000) else null, readOnly = true),
        )
        return LocalActionForm(LocalAction.ORDER_CREATE, 1, label("Current form"), listOf(
            LocalFormField.Scalar(LocalField.REASON, sensitiveControlText("Reason"), LocalInputKind.MULTILINE,
                required = false, help = label("Current reason help")),
            LocalFormField.Scalar(LocalField.LABEL, label("Current label"), LocalInputKind.TEXT,
                required = false, initialValue = value(), readOnly = true),
            LocalFormField.Scalar(LocalField.ORIGINAL_OWNED_VALUE, label("Current value"), LocalInputKind.INTEGER,
                required = false, initialValue = if (old) LocalScalarValue.Integer(2000) else null, readOnly = true),
            LocalFormField.Scalar(LocalField.MODE, label("Current mode"), LocalInputKind.CHOICE,
                required = false, choices = listOf(LocalChoice("RETAIL", label("Current mode choice"))),
                initialValue = LocalScalarValue.Choice("RETAIL")),
            LocalFormField.Scalar(LocalField.SKU_ID, label("Current item"), LocalInputKind.REFERENCE,
                required = false, references = listOf(LocalReferenceChoice(reference, label("Current item choice"))),
                initialValue = LocalScalarValue.Reference(reference)),
            LocalFormField.Lines(LocalField.LINES, label("Current lines"), columns,
                minimumLines = 1, maximumLines = 8, initialLines = listOf(LocalInputLine(lineId, listOf(
                    LocalScalarInput(LocalField.LABEL, value()),
                ) + if (old) listOf(LocalScalarInput(LocalField.ORIGINAL_OWNED_VALUE, LocalScalarValue.Integer(2000))) else emptyList()))),
        ), explanation = label("Current guidance"), confirmation = sensitiveControlText(
            if (old) FrozenSensitivePresentationUiInputs.MARGIN_MINOR else "Review current input"))
    }
}
