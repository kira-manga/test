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
import com.ukkera.pos.app.LocalCommandIntent
import com.ukkera.pos.app.LocalOperationResult
import com.ukkera.pos.app.LocalPendingOperation
import com.ukkera.pos.app.LocalPendingPhase
import com.ukkera.pos.app.LocalPendingResult
import com.ukkera.pos.app.LocalReadResult
import com.ukkera.pos.app.LocalRetailPhysicalGateway
import com.ukkera.pos.app.LocalRetailPhysicalLifetime
import com.ukkera.pos.app.LocalRetailPhysicalPreparation
import com.ukkera.pos.app.LocalRetailPhysicalReply
import com.ukkera.pos.app.LocalRetailPhysicalRequest
import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.app.LocalSessionAttachmentKey
import com.ukkera.pos.app.LocalSessionCheckResult
import com.ukkera.pos.app.LocalSessionDraftEpochs
import com.ukkera.pos.app.LocalSessionLifecycle
import com.ukkera.pos.app.LocalStageResult
import com.ukkera.pos.app.LocalText
import com.ukkera.pos.app.LocalWorkflow
import com.ukkera.pos.app.LocalWorkflowGateway
import com.ukkera.pos.app.LocalWorkflowPage
import com.ukkera.pos.app.LocalWorkflowQuery
import com.ukkera.pos.app.PlatformCapabilities
import com.ukkera.pos.app.PlatformFamily
import com.ukkera.pos.app.SessionDisplayContext
import com.ukkera.pos.app.SignInResult
import com.ukkera.pos.app.UkkeraRuntime
import com.ukkera.pos.app.UnavailableLocalRetailPhysical
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.app.navigation.RouteRegistry
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.koin.core.parameter.parametersOf

/**
 * Presentation-identity portions of the unchanged local-session-presentation-v1 freeze only.
 * OP-PAY-ORIGINAL uses its frozen public UUID slot. Controlled pending/empty-page/unavailable
 * replies exercise the common controller's visible recovery item and successor call boundary.
 * They supply no payment body, financial success, durable journal or protected authority.
 *
 * The original submission and full BODY-PAY-ORIGINAL/actor/lock/attempt/revision tuple remain
 * unbound. These tests do not execute admitted master/RPC, native payment capture, money/stock
 * effects, protected report fields, UI frames or a complete frozen assertion/case/variant.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FrozenOriginalPaymentRecoverySliceTest {
    @Test
    fun lsp001A02A03_presentationIdRemainsExplicitlyRecoverableAfterRenewal() = runTest {
        exerciseOriginalRecovery(reportNarrowing = false)
    }

    @Test
    fun lsp002A03Lsp029V02A01A02_presentationIdRemainsRecoverableAfterReportNarrowing() = runTest {
        exerciseOriginalRecovery(reportNarrowing = true)
    }

    private suspend fun TestScope.exerciseOriginalRecovery(reportNarrowing: Boolean) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var runtimeToClose: UkkeraRuntime? = null
        try {
            val session = RecoveryPresentationSession(sampleDisplay())
            val runtime = UkkeraRuntime(AppDependencies(branchGateway = RecoveryPresentationGateway(session),
                platform = PlatformCapabilities(PlatformFamily.DESKTOP))).also { runtimeToClose = it }
            runtime.openSignIn(company = false, runtime.navigator.token())
            runtime.signIn("ACTOR-A", charArrayOf('s', 'a', 'm', 'p', 'l', 'e'), runtime.navigator.token())
            val before = requireNotNull(runtime.presentation.value.display)
            val entryId = runtime.navigator.state.value.activeEntry.id
            val model = activeEntry(runtime)
            val original = requireNotNull(model.localWorkflows(runtime, runtime.navigator.token()))
            original.activate(LocalWorkflow.CHECKOUT)
            runCurrent()
            val originalBoundary = session.boundary
            check(original.state.value.pending.single().operationId == FrozenRecoveryIdentity.OPERATION) {
                "This slice requires the controlled original unresolved presentation identifier."
            }
            // Establish only a selected unknown UI identity. No original financial body is supplied.
            original.lookup(FrozenRecoveryIdentity.OPERATION, runtime.navigator.token())
            runCurrent()
            check((original.state.value.operation as? LocalOperationResult.Unresolved)?.operationId ==
                FrozenRecoveryIdentity.OPERATION)
            val originatingToken = runtime.navigator.token()
            val originatingCalls = originalBoundary.calls.toList()
            val originatingKey = requireNotNull(session.attachmentKey)

            session.planRenewal(if (reportNarrowing) before.copy(allowedAreas = setOf(AppArea.CASHIER),
                localDraftEpochs = LocalSessionDraftEpochs(2, 1)) else before)
            runtime.refreshLocalSession(runtime.navigator.token())
            runCurrent()
            val currentBoundary = session.boundary
            check(currentBoundary !== originalBoundary && currentBoundary.attachment != originatingKey)
            assertEquals(entryId, runtime.navigator.state.value.activeEntry.id,
                FrozenRecoveryIdentity.identity(reportNarrowing).message("the same cashier entry remains active"))
            val continued = requireNotNull(activeEntry(runtime).localWorkflows(runtime, runtime.navigator.token()))
            continued.activate(LocalWorkflow.CHECKOUT)
            runCurrent()

            val current = requireNotNull(runtime.presentation.value.display)
            assertEquals(before.actorId, current.actorId,
                FrozenRecoveryIdentity.identity(reportNarrowing).message("the controlled employee display ID is unchanged"))
            assertEquals(before.companyId, current.companyId,
                FrozenRecoveryIdentity.identity(reportNarrowing).message("the controlled company display ID is unchanged"))
            assertEquals(before.branchId, current.branchId,
                FrozenRecoveryIdentity.identity(reportNarrowing).message("the controlled branch display ID is unchanged"))
            assertVisibleOriginal(continued.state.value.operationId, continued.state.value.operation, reportNarrowing)
            assertEquals(emptyList(), currentBoundary.lookupIds,
                FrozenRecoveryIdentity.RENEWAL_BOUNDARY.message("renewal/activation did not initiate outcome lookup"))
            assertNoMutations(session, reportNarrowing)
            if (reportNarrowing) {
                assertFalse(AppArea.REPORTS in current.allowedAreas,
                    FrozenRecoveryIdentity.NARROWED_RECOVERY.message("the retired report navigation hint is absent"))
                assertFalse(runtime.presentation.value.locallyLocked,
                    FrozenRecoveryIdentity.NARROWED_RECOVERY.message("the same cashier presentation remains interactive"))
            }

            // A former callback cannot use either its captured token or a newly sampled token.
            original.lookup(FrozenRecoveryIdentity.OPERATION, originatingToken)
            original.lookup(FrozenRecoveryIdentity.OPERATION, runtime.navigator.token())
            original.submit(FrozenRecoveryIdentity.OPERATION, runtime.navigator.token())
            runCurrent()
            assertEquals(originatingCalls, originalBoundary.calls,
                FrozenRecoveryIdentity.RENEWAL_BOUNDARY.message("retired controller callbacks initiate no old-adapter calls"))

            // Explicit current preflight supplies empty presentation controls, not result absence.
            continued.reviewAttachment(runtime.navigator.token())
            runCurrent()
            check(!continued.state.value.attachmentReviewRequired && continued.state.value.pending.isEmpty()) {
                "The controlled successor reread must finish before exercising the explicit lookup control."
            }
            assertVisibleOriginal(continued.state.value.operationId, continued.state.value.operation, reportNarrowing)
            assertEquals(emptyList(), currentBoundary.lookupIds,
                FrozenRecoveryIdentity.RENEWAL_BOUNDARY.message("preflight alone did not repeat the original lookup"))
            continued.lookup(FrozenRecoveryIdentity.OPERATION, runtime.navigator.token())
            runCurrent()
            assertEquals(listOf(FrozenRecoveryIdentity.OPERATION), currentBoundary.lookupIds,
                FrozenRecoveryIdentity.RENEWAL_BOUNDARY.message("one explicit lookup reaches the successor with the original ID"))
            assertEquals(requireNotNull(runtime.presentation.value.localAttachment), currentBoundary.attachment,
                FrozenRecoveryIdentity.RENEWAL_BOUNDARY.message("the lookup adapter has the adopted attachment identity"))
            assertVisibleOriginal(continued.state.value.operationId, continued.state.value.operation, reportNarrowing)
            assertNoMutations(session, reportNarrowing)
            if (reportNarrowing) {
                assertFalse(AppArea.REPORTS in requireNotNull(runtime.presentation.value.display).allowedAreas,
                    FrozenRecoveryIdentity.NARROWED_BOUNDARY.message("original lookup does not restore the retired report hint"))
            }
        } finally {
            try { runtimeToClose?.close() } finally { Dispatchers.resetMain() }
        }
    }

    private fun activeEntry(runtime: UkkeraRuntime): EntryViewModel {
        val id = runtime.navigator.state.value.activeEntry.id
        val root = runtime.rootScope
        val provider = ViewModelProvider.create(root.viewModelStore,
            factory = viewModelFactory { initializer { root.koinScope.get<EntryViewModel> { parametersOf(id) } } })
        return provider["entry-$id", EntryViewModel::class]
    }

    private fun assertVisibleOriginal(id: OperationId?, result: LocalOperationResult?, reportNarrowing: Boolean) {
        val oracle = FrozenRecoveryIdentity.identity(reportNarrowing)
        assertEquals(FrozenRecoveryIdentity.OPERATION, id, oracle.message("the original selected presentation ID survives"))
        // The UI renders its explicit outcome-lookup item from operation, not operationId alone.
        val unknown = assertIs<LocalOperationResult.Unresolved>(result,
            oracle.message("a visible unknown-result lookup item survives; no completed/rejected outcome is invented"))
        assertEquals(FrozenRecoveryIdentity.OPERATION, unknown.operationId,
            oracle.message("the visible lookup item keeps the original ID"))
    }

    private fun assertNoMutations(session: RecoveryPresentationSession, reportNarrowing: Boolean) {
        val calls = session.boundaries.flatMap { it.calls }
        assertTrue(calls.none { it.method in setOf("STAGE", "SUBMIT", "ACKNOWLEDGE",
            "PHYSICAL_PREPARE", "PHYSICAL_STAGE", "PHYSICAL_START", "PHYSICAL_SQL") },
            FrozenRecoveryIdentity.identity(reportNarrowing).message("controlled initiating-app mutation/ACK/physical calls are zero"))
    }

    /** Control-only display symbols shared with the existing presentation slices; no provisioning. */
    private fun sampleDisplay() = SessionDisplayContext(
        actorId = DomainId("00000000-0000-4000-8000-000000000001"),
        companyId = DomainId("00000000-0000-4000-8000-000000000002"),
        branchId = DomainId("00000000-0000-4000-8000-000000000003"),
        actorName = "ACTOR-A", companyName = "COMPANY-1", branchName = "BRANCH-A",
        allowedAreas = setOf(AppArea.CASHIER, AppArea.REPORTS),
        localDraftEpochs = LocalSessionDraftEpochs(1, 1),
    )
}

private class RecoveryPresentationGateway(private val session: RecoveryPresentationSession) : BranchUiGateway {
    override val configured = true
    override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> {
        check(account == "ACTOR-A")
        return SignInResult.Authenticated(session)
    }
}

private class RecoveryPresentationSession(initialDisplay: SessionDisplayContext) : BranchUiSession {
    private var currentDisplay = initialDisplay
    private var closed = false
    private var renewalDisplay: SessionDisplayContext? = null
    private val attachments = MutableStateFlow<LocalSessionAttachmentKey?>(LocalSessionAttachmentKey(
        DomainId("00000000-0000-4000-8000-000000000004"), 1))
    private val lifecycleState = MutableStateFlow(LocalSessionLifecycle.ACTIVE)
    var boundary = RecoveryPresentationBoundary(requireNotNull(attachments.value), includeOriginalPending = true)
        private set
    val boundaries = mutableListOf(boundary)
    override val display: SessionDisplayContext get() = currentDisplay
    override val workflows: LocalWorkflowGateway get() = boundary
    override val retailPhysical: LocalRetailPhysicalGateway get() = boundary.physical
    override val attachmentUpdates: StateFlow<LocalSessionAttachmentKey?> get() = attachments
    override val lifecycle: StateFlow<LocalSessionLifecycle> get() = lifecycleState
    override fun isCurrent(): Boolean = !closed && attachments.value != null

    fun planRenewal(display: SessionDisplayContext) { renewalDisplay = display }

    override suspend fun refresh(): LocalSessionCheckResult {
        val nextDisplay = requireNotNull(renewalDisplay)
        renewalDisplay = null
        val next = requireNotNull(attachments.value).let { it.copy(generation = it.generation + 1) }
        currentDisplay = nextDisplay
        boundary = RecoveryPresentationBoundary(next, includeOriginalPending = false).also { boundaries += it }
        attachments.value = next
        return LocalSessionCheckResult.Current(nextDisplay, next)
    }

    override suspend fun current(): LocalSessionCheckResult = if (isCurrent())
        LocalSessionCheckResult.Current(currentDisplay, attachments.value)
        else LocalSessionCheckResult.Unavailable(LocalServiceProblem.SESSION_ENDED, attachments.value)

    override suspend fun mayRestore(route: AppRoute): Boolean = isCurrent() && RouteRegistry.area(route) in currentDisplay.allowedAreas
    override fun close() {
        closed = true
        attachments.value = null
        lifecycleState.value = LocalSessionLifecycle.LOCKED
    }
}

private data class RecoveryPresentationCall(val method: String, val operationId: OperationId? = null)

/** No stored business body or positive financial result is constructed by this boundary. */
private class RecoveryPresentationBoundary(val attachment: LocalSessionAttachmentKey, private val includeOriginalPending: Boolean) : LocalWorkflowGateway {
    val calls = mutableListOf<RecoveryPresentationCall>()
    val lookupIds: List<OperationId> get() = calls.filter { it.method == "LOOKUP" }.map { requireNotNull(it.operationId) }
    override suspend fun pending(cursor: String?): LocalPendingResult {
        calls += RecoveryPresentationCall("PENDING")
        // Action is a display/routing hint only; no native observation or exact body is supplied.
        return LocalPendingResult.Available(if (includeOriginalPending) listOf(LocalPendingOperation(
            FrozenRecoveryIdentity.OPERATION, LocalAction.RETAIL_PAYMENT_APPLY,
            LocalText("Original unresolved payment", "دفعة أصلية غير محسومة"),
            phase = LocalPendingPhase.DISPATCH_UNRESOLVED)) else emptyList())
    }
    override suspend fun read(query: LocalWorkflowQuery): LocalReadResult {
        calls += RecoveryPresentationCall("READ")
        check(query.workflow == LocalWorkflow.CHECKOUT)
        return LocalReadResult.Available(LocalWorkflowPage(query.workflow, emptyList(), emptyList()))
    }
    override suspend fun lookup(operationId: OperationId): LocalOperationResult {
        calls += RecoveryPresentationCall("LOOKUP", operationId)
        return LocalOperationResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE)
    }
    override suspend fun stage(intent: LocalCommandIntent): LocalStageResult {
        calls += RecoveryPresentationCall("STAGE", intent.operationId)
        return LocalStageResult.NotStaged(LocalServiceProblem.UNAVAILABLE)
    }
    override suspend fun submit(operationId: OperationId): LocalOperationResult {
        calls += RecoveryPresentationCall("SUBMIT", operationId)
        return LocalOperationResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE)
    }
    override suspend fun acknowledgeRecorded(operationId: OperationId): LocalAcknowledgementResult {
        calls += RecoveryPresentationCall("ACKNOWLEDGE", operationId)
        return LocalAcknowledgementResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE)
    }
    val physical = object : LocalRetailPhysicalGateway by UnavailableLocalRetailPhysical {
        override suspend fun prepare(request: LocalRetailPhysicalRequest): LocalRetailPhysicalReply {
            calls += RecoveryPresentationCall("PHYSICAL_PREPARE")
            return UnavailableLocalRetailPhysical.prepare(request)
        }
        override suspend fun stage(preparation: LocalRetailPhysicalPreparation): LocalRetailPhysicalReply {
            calls += RecoveryPresentationCall("PHYSICAL_STAGE", preparation.operationId)
            return UnavailableLocalRetailPhysical.stage(preparation)
        }
        override suspend fun start(operationId: OperationId, originalEntry: LocalRetailPhysicalLifetime): LocalRetailPhysicalReply {
            calls += RecoveryPresentationCall("PHYSICAL_START", operationId)
            return UnavailableLocalRetailPhysical.start(operationId, originalEntry)
        }
        override suspend fun continueSql(operationId: OperationId): LocalRetailPhysicalReply {
            calls += RecoveryPresentationCall("PHYSICAL_SQL", operationId)
            return UnavailableLocalRetailPhysical.continueSql(operationId)
        }
    }
}

private data class FrozenRecoveryPartialAssertion(val id: String, val expected: String) {
    fun message(observedSlice: String): String = "$id presentation-identity slice: $observedSlice. Full frozen expectation: $expected"
}

/** Verbatim expectation selections and the public operation UUID from the immutable packet. */
private object FrozenRecoveryIdentity {
    val OPERATION = OperationId("50a00000-0000-4000-8000-000000000101")
    val RENEWAL_IDENTITY = FrozenRecoveryPartialAssertion("LSP-001-A02", "OP-PAY-ORIGINAL, originating A/company/branch, lock/attempt, pinned revisions and every semantic BODY-PAY-ORIGINAL field remain identical; only transient authentication/connection metadata may change.")
    val RENEWAL_BOUNDARY = FrozenRecoveryPartialAssertion("LSP-001-A03", "Between accepted renewal and explicit lookup, newly initiated payment/charge/sale/approval/kitchen mutations are zero. The explicit lookup uses current authorization and original OP-PAY-ORIGINAL.")
    val NARROWED_IDENTITY = FrozenRecoveryPartialAssertion("LSP-002-A03", "Original pending payment ID/body/actor remain pinned; retired permission snapshot is never proof of a new action. No automatic payment retry or new body is generated.")
    val NARROWED_RECOVERY = FrozenRecoveryPartialAssertion("LSP-029-V02-A01", "Only retired report-cost/export fields/actions are cleared. A is still the valid employee; separately allowed current cashier actions remain usable and original pending tuple stays pinned.")
    val NARROWED_BOUNDARY = FrozenRecoveryPartialAssertion("LSP-029-V02-A02", "Removed cost/export requests are denied/redacted. A permitted current quantity/cashier preflight read can succeed under current grants; do not submit a new collection during this variant. Limited original recovery does not reinstate report rights.")
    fun identity(reportNarrowing: Boolean) = if (reportNarrowing) NARROWED_IDENTITY else RENEWAL_IDENTITY
}
