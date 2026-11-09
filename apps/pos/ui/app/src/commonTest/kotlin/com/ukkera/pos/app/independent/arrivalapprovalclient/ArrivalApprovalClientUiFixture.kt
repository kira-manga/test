@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.independent.arrivalapprovalclient

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ukkera.pos.app.*
import com.ukkera.pos.app.navigation.*
import com.ukkera.pos.security.ActionBinding
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.MonotonicMoment
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.PurchasingEvidenceMediaV1
import com.ukkera.pos.security.PurchasingEvidencePurposeV1
import com.ukkera.pos.security.SecurityAction
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
 * AAW v1 journeys/0, authority, policy and retryRestart input projection. Frozen
 * content digests are specification DATA only. Organization, retention UUID,
 * original revision and clock/receipt samples are inert constructor carriers;
 * no genuine source, approval, authentication or native history is supplied.
 */
internal object AawClientUiInputs {
    val requester = DomainId("bd706b87-eaf0-45a9-8212-0f40f038eafe")
    val otherRequester = DomainId("1c93ade3-3a6d-428d-b7c0-1c1a1b65fee8")
    val approver = DomainId("a1100000-0000-4000-8000-000000000001")
    val company = DomainId("8891f6af-3003-4a10-a40c-56830e000324")
    val branch = DomainId("17256bce-b955-4c06-898f-b740d9c1f3de")
    val arrival = DomainId("a3000000-0000-4000-8000-000000000001")
    val scope = BranchScope(DomainId("90000000-0000-4000-8000-000000000001"), company, branch)
    val operation = OperationId("a3200000-0000-4000-8000-00000000000b")
    val conflictingOperation = OperationId("a1700000-0000-4000-8000-000000000063")
    val contentDigest = PayloadDigest("5d81aba456091369ac8d3f4b2ac2d6de937be03dd25378e1d2426f88ebbd920a")
    val confirmationDigest = PayloadDigest("badfd2ef0a137206d65b9c6949c02c029fdbfbc2f9165733c6c1c03de6a446bc")
    val journeys = listOf(
        AawUiFrozenJourney(operation, arrival, approver, false, contentDigest, confirmationDigest),
        AawUiFrozenJourney(OperationId("a3200000-0000-4000-8000-000000000015"),
            DomainId("a3000000-0000-4000-8000-000000000002"), requester, true,
            PayloadDigest("cd1ed4937a45b6773e77216f554af9ea7123a97dab4ea89bd10be95f8086253e"),
            PayloadDigest("0585c4d34d73d3034654f06aea3a258e84933b7543ad8c0797be64a1da8457d4")),
        AawUiFrozenJourney(OperationId("a3200000-0000-4000-8000-00000000001f"),
            DomainId("a3000000-0000-4000-8000-000000000003"), approver, true,
            PayloadDigest("3bb1e20ad6e7e0479754984fee1ab8a19051329060357dca14c49bc90b426e7d"),
            PayloadDigest("aa0981dc5e846a79d3a78a2f79145ada5a7bf360d973554bf75ab75fc3a1b656")),
    )
    val summary = listOf(
        LocalRecordValue(text("Supplier"), text("0d92ac11-e8ca-46e1-afcb-e57f219a7277")),
        LocalRecordValue(text("A uninspected held"), text("24")),
        LocalRecordValue(text("B uninspected held"), text("6")),
        LocalRecordValue(text("Financial status"), text("UNPRICED_UNRESOLVED")),
    )

    fun text(value: String): LocalText = LocalText(value, value)

    fun rule(namedSelf: Boolean = false): LocalArrivalRuleOptionV2 {
        val grantees = LocalArrivalActorSelectionV2(LocalArrivalActorSelectionModeV2.CURRENT_BRANCH_GRANTEES)
        return LocalArrivalRuleOptionV2(
            DomainId(if (namedSelf) "a2000000-0000-4000-8000-000000000015" else "a2000000-0000-4000-8000-00000000000b"),
            LocalAction.ARRIVAL_RECORD, false, 1, 2,
            listOf(PurchasingEvidenceMediaV1.PDF), listOf(PurchasingEvidencePurposeV1.SUPPLIER_DELIVERY),
            LocalArrivalRetentionCoordinateV2(DomainId("90000000-0000-4000-8000-000000000002"), Revision(2)),
            LocalArrivalApprovalRouteV2(if (namedSelf) LocalArrivalApprovalModeV2.NAMED_SELF else LocalArrivalApprovalModeV2.SEPARATE,
                requesters = grantees, approvers = grantees, namedEmployeeIds = if (namedSelf) listOf(requester) else emptyList()),
            "Supplied AAW v1 Record rule metadata, not a signed rule: current R REQUEST and own approver APPROVE; " +
                (if (namedSelf) "only named R may use equality; distinct eligible R/A remains allowed. " else "distinct R/A required. ") +
                "Company ${company.value}; branch ${branch.value}; supplier 0d92ac11-e8ca-46e1-afcb-e57f219a7277; " +
                "products A/B; catalog release 7; warehouse 23e86e98-2c8c-48fb-b7d2-029fd7a440b8; " +
                "policy 7/publication 9; required PDF SUPPLIER_DELIVERY originals 1..2; retention SYN-RET-PUR-ORIGINAL/2. " +
                "Delegation is the exact unpriced held-custody action, not administration or financial acceptance; " +
                "unspecified native acquisition/limit facts remain UNBOUND.")
    }

    fun review(rule: LocalArrivalRuleOptionV2 = rule(), requestor: DomainId = requester,
        operationId: OperationId = operation): LocalActionApprovalReview {
        val journey = journeys.singleOrNull { it.operationId == operationId } ?: journeys.first()
        return LocalActionApprovalReview(operationId, LocalAction.ARRIVAL_RECORD, requestor, Revision(21),
            AuthorizationRequirement(Permission(SecurityAction.INVENTORY_ADJUST, PermissionVerb.PERFORM), scope,
                ActionBinding(journey.arrivalId, Revision(1), journey.contentDigest, operationId, "syn.aaw.record")),
            journey.confirmationDigest, rule, summary)
    }

    fun pending(phase: LocalActionApprovalPhase = LocalActionApprovalPhase.PREVIEW_REQUIRED,
        rule: LocalArrivalRuleOptionV2 = rule(), approvalId: DomainId = DomainId.random(),
        actualApprover: DomainId = approver, operationId: OperationId = operation,
        requestor: DomainId = requester): LocalPendingOperation {
        val recorded = phase in setOf(LocalActionApprovalPhase.ATTACHED, LocalActionApprovalPhase.CONSUMED)
        val clock = DomainId("90000000-0000-4000-8000-000000000003")
        val approval = LocalActionApprovalState(phase, rule,
            approvalId = approvalId.takeUnless { phase in setOf(LocalActionApprovalPhase.PREVIEW_REQUIRED, LocalActionApprovalPhase.READY_TO_CONFIRM) },
            review = if (phase == LocalActionApprovalPhase.PREVIEW_REQUIRED) null else review(rule, requestor, operationId),
            approverId = actualApprover.takeIf { recorded },
            issuedAt = MonotonicMoment(clock, 1).takeIf { recorded },
            expiresAt = MonotonicMoment(clock, 2).takeIf { recorded },
            refusal = LocalActionApprovalRefusal.AUTHORIZATION_REFUSED.takeIf { phase == LocalActionApprovalPhase.REFUSED })
        return LocalPendingOperation(operationId, LocalAction.ARRIVAL_RECORD, text("Saved unpriced arrival"),
            summary, submitAllowed = phase == LocalActionApprovalPhase.ATTACHED, actionApproval = approval)
    }
}

internal data class AawUiFrozenJourney(val operationId: OperationId, val arrivalId: DomainId,
    val approverId: DomainId, val namedSelf: Boolean, val contentDigest: PayloadDigest,
    val confirmationDigest: PayloadDigest)

internal class HeldAawUiApproval {
    val entered = CompletableDeferred<Unit>()
    val reply = CompletableDeferred<LocalActionApprovalResult>()
    val returned = CompletableDeferred<Unit>()
}

internal data class AawUiIssueCall(val operationId: OperationId, val confirmation: PayloadDigest,
    val login: String, val borrowedPassword: CharArray)

/** Supplied UI observations only; this port never grants authority or durably issues an AP. */
internal class ControlledAawClientUiPort(initial: LocalPendingOperation = AawClientUiInputs.pending()) : LocalWorkflowGateway {
    var pendingOperation = initial
    var previewResult: LocalActionApprovalResult = LocalActionApprovalResult.Available(
        AawClientUiInputs.pending(LocalActionApprovalPhase.READY_TO_CONFIRM, initial.actionApproval!!.rule,
            operationId = initial.operationId))
    var issueResult: LocalActionApprovalResult = LocalActionApprovalResult.Unavailable(
        initial.operationId, LocalServiceProblem.UNAVAILABLE)
    var approvalLookupResult: LocalActionApprovalResult = LocalActionApprovalResult.Unavailable(
        initial.operationId, LocalServiceProblem.UNAVAILABLE)
    val previews = mutableListOf<OperationId>()
    val issues = mutableListOf<AawUiIssueCall>()
    val approvalLookups = mutableListOf<OperationId>()
    val submissions = mutableListOf<OperationId>()
    val businessLookups = mutableListOf<OperationId>()
    val stages = mutableListOf<LocalCommandIntent>()
    val acknowledgements = mutableListOf<OperationId>()
    var heldPreview: HeldAawUiApproval? = null
    var heldIssue: HeldAawUiApproval? = null
    private val heldCalls = mutableListOf<HeldAawUiApproval>()

    fun holdIssue(): HeldAawUiApproval = HeldAawUiApproval().also { heldIssue = it; heldCalls += it }
    fun holdPreview(): HeldAawUiApproval = HeldAawUiApproval().also { heldPreview = it; heldCalls += it }

    private suspend fun delivery(held: HeldAawUiApproval): LocalActionApprovalResult = withContext(NonCancellable) {
        held.entered.complete(Unit)
        held.reply.await().also { held.returned.complete(Unit) }
    }

    override suspend fun previewActionApproval(operationId: OperationId): LocalActionApprovalResult {
        previews += operationId
        heldPreview?.let { held -> heldPreview = null; return delivery(held) }
        return previewResult
    }

    override suspend fun issueActionApproval(operationId: OperationId, confirmationDigest: PayloadDigest,
        approverLogin: String, approverPassword: CharArray): LocalActionApprovalResult {
        issues += AawUiIssueCall(operationId, confirmationDigest, approverLogin, approverPassword)
        heldIssue?.let { held -> heldIssue = null; return delivery(held) }
        return issueResult
    }

    override suspend fun lookupActionApproval(operationId: OperationId): LocalActionApprovalResult {
        approvalLookups += operationId
        return approvalLookupResult
    }

    override suspend fun read(query: LocalWorkflowQuery): LocalReadResult =
        LocalReadResult.Available(LocalWorkflowPage(LocalWorkflow.RECEIVING, emptyList(), emptyList()))
    override suspend fun pending(cursor: String?): LocalPendingResult = LocalPendingResult.Available(listOf(pendingOperation))
    override suspend fun stage(intent: LocalCommandIntent): LocalStageResult {
        stages += intent
        error("No fresh application-stage binding is supplied by this frozen UI slice.")
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

internal class AawClientUiSession(initial: ControlledAawClientUiPort) : BranchUiSession {
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
    private var replacement: ControlledAawClientUiPort? = null
    override fun isCurrent(): Boolean = active
    override suspend fun mayRestore(route: AppRoute): Boolean = active
    override suspend fun current(): LocalSessionCheckResult = if (active) LocalSessionCheckResult.Current(display, attachmentKey)
        else LocalSessionCheckResult.Unavailable(LocalServiceProblem.SESSION_ENDED, attachmentKey)
    fun replaceWith(port: ControlledAawClientUiPort) { replacement = port }
    override suspend fun refresh(): LocalSessionCheckResult {
        workflows = assertNotNull(replacement)
        replacement = null
        attachmentUpdates.value = LocalSessionAttachmentKey(AawClientUiInputs.requester,
            assertNotNull(attachmentKey).generation + 1)
        return LocalSessionCheckResult.Current(display, attachmentKey)
    }
    override fun close() { active = false; closedCount++; lifecycle.value = LocalSessionLifecycle.LOCKED }
}

internal class AawClientUiFixture private constructor(
    val runtime: UkkeraRuntime,
    val session: AawClientUiSession,
    private val model: EntryViewModel,
    val port: ControlledAawClientUiPort,
    private val scope: TestScope,
    val signIns: List<String>,
) {
    var controller = assertNotNull(model.localWorkflows(runtime, runtime.navigator.token()))
        private set
    private val ports = mutableListOf(port)
    fun token(): NavigationToken = runtime.navigator.token()
    fun replaceAttachment(fresh: ControlledAawClientUiPort): LocalWorkflowController {
        ports += fresh
        session.replaceWith(fresh)
        runtime.refreshLocalSession(token())
        scope.runCurrent()
        controller = assertNotNull(model.localWorkflows(runtime, token()))
        controller.activate(LocalWorkflow.RECEIVING)
        return controller
    }
    fun releaseHeld() = ports.forEach(ControlledAawClientUiPort::releaseHeld)

    companion object {
        suspend fun TestScope.withController(initial: LocalPendingOperation = AawClientUiInputs.pending(),
            block: suspend (AawClientUiFixture) -> Unit) {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val port = ControlledAawClientUiPort(initial)
            val session = AawClientUiSession(port)
            val signIns = mutableListOf<String>()
            val runtime = UkkeraRuntime(AppDependencies(branchGateway = object : BranchUiGateway {
                override val configured = true
                override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> {
                    signIns += account
                    return SignInResult.Authenticated(session)
                }
            }, platform = PlatformCapabilities(PlatformFamily.DESKTOP)))
            var fixture: AawClientUiFixture? = null
            try {
                runtime.openSignIn(false, runtime.navigator.token())
                runtime.signIn("synthetic-requester", charArrayOf('x'), runtime.navigator.token())
                assertEquals(AppRoute.Stock, runtime.navigator.state.value.activeEntry.route)
                val entry = runtime.navigator.state.value.activeEntry
                val root = runtime.rootScope
                val provider = ViewModelProvider.create(root.viewModelStore, factory = viewModelFactory {
                    initializer { root.koinScope.get<EntryViewModel> { parametersOf(entry.id) } }
                })
                fixture = AawClientUiFixture(runtime, session,
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
