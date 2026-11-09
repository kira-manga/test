@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.independent.approvalsafereplacement

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ukkera.pos.app.*
import com.ukkera.pos.app.independent.arrivalapprovalclient.AawClientUiInputs
import com.ukkera.pos.app.independent.arrivalapprovalclient.AawUiFrozenJourney
import com.ukkera.pos.app.navigation.*
import com.ukkera.pos.security.MonotonicMoment
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
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

/** Frozen ASR/F1 presentation DATA only. No authentication or native authority. */
internal fun asrUiApprovalCarrier(index: Int): DomainId =
    DomainId("b1000000-0000-4000-8000-${(index + 1).toString(16).padStart(12, '0')}")

internal fun asrUiRetirementData(index: Int): LocalActionApprovalRetirement = LocalActionApprovalRetirement(
    DomainId("b1700000-0000-4000-8000-${(index + 1).toString(16).padStart(12, '0')}"),
    PayloadDigest("a".repeat(64)), asrUiClockData(index * 3L + 3))

private fun asrUiClockData(elapsed: Long): MonotonicMoment =
    MonotonicMoment(DomainId("90000000-0000-4000-8000-000000000003"), elapsed)

internal fun asrUiArchivesData(count: Int, journey: AawUiFrozenJourney = AawClientUiInputs.journeys.first()):
    List<LocalRetiredActionApproval> = (0 until count).map { index ->
        LocalRetiredActionApproval(asrUiApprovalCarrier(index), journey.approverId,
            asrUiClockData(index * 3L + 1), asrUiClockData(index * 3L + 2),
            journey.confirmationDigest, asrUiRetirementData(index))
    }

/**
 * Public DTO observations of the frozen original and current/previous ceremonies.
 * These are display samples, not qualified retirement/expiry/history receipts.
 */
internal fun asrUiPendingData(phase: LocalActionApprovalPhase = LocalActionApprovalPhase.ATTACHED,
    index: Int = 0, archives: List<LocalRetiredActionApproval> = emptyList(),
    journey: AawUiFrozenJourney = AawClientUiInputs.journeys.first(),
    requestor: DomainId = AawClientUiInputs.requester,
    operationId: OperationId = journey.operationId): LocalPendingOperation {
    val inputs = AawClientUiInputs
    val recorded = phase in setOf(LocalActionApprovalPhase.ATTACHED, LocalActionApprovalPhase.CONSUMED,
        LocalActionApprovalPhase.RETIRE_UNRESOLVED, LocalActionApprovalPhase.RETIRED)
    val rule = inputs.rule(journey.namedSelf)
    val approval = LocalActionApprovalState(phase, rule,
        approvalId = asrUiApprovalCarrier(index).takeUnless { phase in setOf(
            LocalActionApprovalPhase.PREVIEW_REQUIRED, LocalActionApprovalPhase.READY_TO_CONFIRM) },
        review = if (phase == LocalActionApprovalPhase.PREVIEW_REQUIRED) null
            else inputs.review(rule, requestor, operationId),
        approverId = journey.approverId.takeIf { recorded },
        issuedAt = asrUiClockData(index * 3L + 1).takeIf { recorded },
        expiresAt = asrUiClockData(index * 3L + 2).takeIf { recorded },
        refusal = LocalActionApprovalRefusal.AUTHORIZATION_REFUSED.takeIf { phase == LocalActionApprovalPhase.REFUSED },
        retirement = asrUiRetirementData(index).takeIf { phase == LocalActionApprovalPhase.RETIRED },
        retiredApprovals = archives)
    return LocalPendingOperation(operationId, LocalAction.ARRIVAL_RECORD, inputs.text("Saved unpriced arrival"),
        inputs.summary, submitAllowed = phase == LocalActionApprovalPhase.ATTACHED, actionApproval = approval)
}

internal enum class AsrUiCallKind { PREVIEW, ISSUE, LOOKUP, RETIRE, BEGIN }

internal class HeldAsrUiReply {
    val entered = CompletableDeferred<Unit>()
    val reply = CompletableDeferred<LocalActionApprovalResult>()
    val returned = CompletableDeferred<Unit>()
}

internal data class AsrUiIssueCall(val operationId: OperationId, val confirmation: PayloadDigest,
    val login: String, val borrowedPassword: CharArray)

/** Supplied port observations, not a success implementation of a Master or durable journal. */
internal class ControlledAsrUiPort(val pendingOperation: LocalPendingOperation) : LocalWorkflowGateway {
    var previewResult: LocalActionApprovalResult = LocalActionApprovalResult.Unavailable(
        pendingOperation.operationId, LocalServiceProblem.UNAVAILABLE)
    var issueResult: LocalActionApprovalResult = LocalActionApprovalResult.Unavailable(
        pendingOperation.operationId, LocalServiceProblem.UNAVAILABLE)
    var lookupResult: LocalActionApprovalResult = LocalActionApprovalResult.Available(pendingOperation)
    var retirementResult: LocalActionApprovalResult = LocalActionApprovalResult.Unavailable(
        pendingOperation.operationId, LocalServiceProblem.STORAGE_UNCERTAIN)
    var beginResult: LocalActionApprovalResult = LocalActionApprovalResult.Unavailable(
        pendingOperation.operationId, LocalServiceProblem.UNAVAILABLE)
    val previews = mutableListOf<OperationId>()
    val issues = mutableListOf<AsrUiIssueCall>()
    val approvalLookups = mutableListOf<OperationId>()
    val retirements = mutableListOf<OperationId>()
    val begins = mutableListOf<OperationId>()
    val stages = mutableListOf<LocalCommandIntent>()
    val submissions = mutableListOf<OperationId>()
    val businessLookups = mutableListOf<OperationId>()
    val acknowledgements = mutableListOf<OperationId>()
    private val nextHeld = mutableMapOf<AsrUiCallKind, HeldAsrUiReply>()
    private val heldCalls = mutableListOf<HeldAsrUiReply>()

    fun hold(kind: AsrUiCallKind): HeldAsrUiReply = HeldAsrUiReply().also {
        check(nextHeld.put(kind, it) == null)
        heldCalls += it
    }

    private suspend fun delivery(kind: AsrUiCallKind, normal: LocalActionApprovalResult): LocalActionApprovalResult {
        val held = nextHeld.remove(kind) ?: return normal
        return withContext(NonCancellable) {
            held.entered.complete(Unit)
            held.reply.await().also { held.returned.complete(Unit) }
        }
    }

    override suspend fun previewActionApproval(operationId: OperationId): LocalActionApprovalResult {
        previews += operationId
        return delivery(AsrUiCallKind.PREVIEW, previewResult)
    }
    override suspend fun issueActionApproval(operationId: OperationId, confirmationDigest: PayloadDigest,
        approverLogin: String, approverPassword: CharArray): LocalActionApprovalResult {
        issues += AsrUiIssueCall(operationId, confirmationDigest, approverLogin, approverPassword)
        return delivery(AsrUiCallKind.ISSUE, issueResult)
    }
    override suspend fun lookupActionApproval(operationId: OperationId): LocalActionApprovalResult {
        approvalLookups += operationId
        return delivery(AsrUiCallKind.LOOKUP, lookupResult)
    }
    override suspend fun retireActionApproval(operationId: OperationId): LocalActionApprovalResult {
        retirements += operationId
        return delivery(AsrUiCallKind.RETIRE, retirementResult)
    }
    override suspend fun beginActionApprovalReplacement(operationId: OperationId): LocalActionApprovalResult {
        begins += operationId
        return delivery(AsrUiCallKind.BEGIN, beginResult)
    }
    override suspend fun read(query: LocalWorkflowQuery): LocalReadResult = LocalReadResult.Available(
        LocalWorkflowPage(LocalWorkflow.RECEIVING, emptyList(), emptyList()))
    override suspend fun pending(cursor: String?): LocalPendingResult = LocalPendingResult.Available(listOf(pendingOperation))
    override suspend fun stage(intent: LocalCommandIntent): LocalStageResult {
        stages += intent
        error("No fresh stage is supplied by this frozen ASR presentation slice.")
    }
    override suspend fun submit(operationId: OperationId): LocalOperationResult {
        submissions += operationId
        return LocalOperationResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE)
    }
    override suspend fun lookup(operationId: OperationId): LocalOperationResult {
        businessLookups += operationId
        return LocalOperationResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE)
    }
    override suspend fun acknowledgeRecorded(operationId: OperationId): LocalAcknowledgementResult {
        acknowledgements += operationId
        return LocalAcknowledgementResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE)
    }

    fun assertNoBusinessCalls() {
        assertTrue(stages.isEmpty())
        assertTrue(submissions.isEmpty())
        assertTrue(businessLookups.isEmpty())
        assertTrue(acknowledgements.isEmpty())
    }
    fun releaseHeld() = heldCalls.forEach {
        it.reply.complete(LocalActionApprovalResult.Unavailable(pendingOperation.operationId, LocalServiceProblem.UNAVAILABLE))
    }
}

internal class AsrUiSession(initial: ControlledAsrUiPort) : BranchUiSession {
    override val display = SessionDisplayContext(AawClientUiInputs.requester, AawClientUiInputs.company,
        AawClientUiInputs.branch, "Synthetic receiving requester", "Synthetic company", "Synthetic branch",
        setOf(AppArea.STOCK), LocalSessionDraftEpochs(1, 1))
    override var workflows: LocalWorkflowGateway = initial
    override val lifecycle = MutableStateFlow(LocalSessionLifecycle.ACTIVE)
    override val attachmentUpdates = MutableStateFlow<LocalSessionAttachmentKey?>(
        LocalSessionAttachmentKey(AawClientUiInputs.requester, 1))
    var closedCount = 0
        private set
    private var active = true
    private var replacement: ControlledAsrUiPort? = null
    override fun isCurrent(): Boolean = active
    override suspend fun mayRestore(route: AppRoute): Boolean = active
    override suspend fun current(): LocalSessionCheckResult = if (active) LocalSessionCheckResult.Current(display, attachmentKey)
        else LocalSessionCheckResult.Unavailable(LocalServiceProblem.SESSION_ENDED, attachmentKey)
    fun replaceWith(port: ControlledAsrUiPort) { replacement = port }
    override suspend fun refresh(): LocalSessionCheckResult {
        workflows = assertNotNull(replacement)
        replacement = null
        attachmentUpdates.value = LocalSessionAttachmentKey(AawClientUiInputs.requester,
            assertNotNull(attachmentKey).generation + 1)
        return LocalSessionCheckResult.Current(display, attachmentKey)
    }
    override fun close() { active = false; closedCount++; lifecycle.value = LocalSessionLifecycle.LOCKED }
}

internal class AsrUiFixture private constructor(val runtime: UkkeraRuntime, val session: AsrUiSession,
    private val model: EntryViewModel, val port: ControlledAsrUiPort, private val scope: TestScope,
    val signIns: List<String>) {
    var controller = assertNotNull(model.localWorkflows(runtime, runtime.navigator.token()))
        private set
    private val ports = mutableListOf(port)
    fun token(): NavigationToken = runtime.navigator.token()
    fun replaceAttachment(fresh: ControlledAsrUiPort): LocalWorkflowController {
        ports += fresh
        session.replaceWith(fresh)
        runtime.refreshLocalSession(token())
        scope.runCurrent()
        controller = assertNotNull(model.localWorkflows(runtime, token()))
        controller.activate(LocalWorkflow.RECEIVING)
        return controller
    }
    fun releaseHeld() = ports.forEach(ControlledAsrUiPort::releaseHeld)

    companion object {
        suspend fun TestScope.withAsrController(initial: LocalPendingOperation = asrUiPendingData(),
            block: suspend (AsrUiFixture) -> Unit) {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val port = ControlledAsrUiPort(initial)
            val session = AsrUiSession(port)
            val signIns = mutableListOf<String>()
            val runtime = UkkeraRuntime(AppDependencies(branchGateway = object : BranchUiGateway {
                override val configured = true
                override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> {
                    signIns += account
                    return SignInResult.Authenticated(session)
                }
            }, platform = PlatformCapabilities(PlatformFamily.DESKTOP)))
            var fixture: AsrUiFixture? = null
            try {
                runtime.openSignIn(false, runtime.navigator.token())
                runtime.signIn("synthetic-requester", charArrayOf('x'), runtime.navigator.token())
                assertEquals(AppRoute.Stock, runtime.navigator.state.value.activeEntry.route)
                val entry = runtime.navigator.state.value.activeEntry
                val root = runtime.rootScope
                val provider = ViewModelProvider.create(root.viewModelStore, factory = viewModelFactory {
                    initializer { root.koinScope.get<EntryViewModel> { parametersOf(entry.id) } }
                })
                fixture = AsrUiFixture(runtime, session,
                    provider["entry-${entry.id}", EntryViewModel::class], port, this, signIns)
                fixture.controller.activate(LocalWorkflow.RECEIVING)
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
