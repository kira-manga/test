@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.gateway.independent.managerorderpresentation

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.LocalBranchDisplayLabels
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.app.navigation.NavigationToken
import com.ukkera.pos.app.navigation.RouteRegistry
import com.ukkera.pos.app.ui.AppLanguage
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real runtime/navigator/controller and gateway with controlled session/native boundaries.
 * Frozen v2 subrow slices, not whole cases or actual authentication/device/financial acceptance.
 * No durable originals are provisioned in the rejecting journal. Durable actor/outcome/ref
 * snapshots, separate recovery availability, physical affordances and bilingual prose are UNBOUND.
 */
class ManagerOrderPresentationControllerTest {
    @Test
    fun mpv2SoleReassignmentHintReachesActualOrdersNavigationAndManagerRead() = runTest {
        for (language in AppLanguage.entries) withManagerController(language = language) { fixture ->
            assertEquals(1, fixture.harness.endpoint.view.policy.grants.size)
            assertEquals(setOf(AppArea.ORDERS), fixture.session.display.allowedAreas)
            assertEquals(AppArea.ORDERS, fixture.runtime.navigator.state.value.selectedArea)
            assertEquals(AppRoute.Orders, fixture.runtime.navigator.state.value.activeEntry.route)
            assertBoundManagerPage(assertNotNull(fixture.controller.state.value.page), ManagerPresentationInputs.legacyExpected)
            fixture.assertReviewOnly()
            fixture.harness.assertTargetDispatch()
        }
    }

    @Test
    fun mpv1AndV2ReviewedSummaryAndDetailsCannotEnterOwnerRoutes() = runTest {
        for (expected in listOf(ManagerPresentationInputs.legacyExpected, ManagerPresentationInputs.manyExpected))
            for (language in AppLanguage.entries) withManagerController(expected, language = language) { fixture ->
            val original = assertBoundManagerPage(assertNotNull(fixture.controller.state.value.page), expected)
            val details = original.records.filter { it.reference.kind == LocalRecordKind.ORDER_LINE }
            val attempts = listOf(original.records.single { it.reference.kind == LocalRecordKind.ORDER },
                details.first(), details[details.size / 2], details.last()).distinct()
            for (record in attempts) {
                fixture.controller.selectRecord(record.reference, fixture.token())
                runCurrent()
                assertEquals(ManagerPresentationInputs.query(expected.target).search, fixture.controller.state.value.search)
                assertNull(fixture.controller.state.value.reference)
                assertEquals(original, fixture.controller.state.value.page)
                fixture.assertReviewOnly()
            }
            fixture.harness.assertTargetDispatch()
            // Direct production callbacks only, not touch/keyboard or scrolling execution.
        }
    }

    @Test
    fun mpv2NullSelectionKeepsExactManagerSearchAndRetainedRecordedOperationCoordinate() = runTest {
        val original = LocalOperationResult.Recorded(ManagerPresentationInputs.committedOriginal,
            LocalRecordedDecision.APPLIED, LocalText("Constructor-only retained original", "عملية أصلية محتفظ بها للاختبار"))
        for (language in AppLanguage.entries) withManagerController(language = language,
            selected = LocalRecordReference(LocalRecordKind.ORDER, ManagerPresentationInputs.legacy),
            retainedOperation = original) { fixture ->
            assertBoundManagerPage(assertNotNull(fixture.controller.state.value.page), ManagerPresentationInputs.legacyExpected)
            val before = fixture.controller.state.value
            fixture.controller.selectRecord(null, fixture.token())
            runCurrent()
            val after = fixture.controller.state.value
            assertNull(after.reference)
            assertEquals("manager:00000000-0000-4000-8000-000000000401", after.search)
            assertEquals(ManagerPresentationInputs.committedOriginal, after.operationId)
            assertEquals(before.operation, after.operation)
            assertFalse(after.recordedAcknowledged)
            assertBoundManagerPage(assertNotNull(after.page), ManagerPresentationInputs.legacyExpected)
            assertTrue(fixture.audit.reads.all { it.workflow == LocalWorkflow.ORDERS &&
                it.search == "manager:00000000-0000-4000-8000-000000000401" &&
                (it.reference == null || it.reference == before.reference) })
            fixture.harness.assertTargetDispatch(fixture.harness.endpoint.keys.size)
            fixture.assertReviewOnly()
            // Refresh count is deliberately not frozen. This retained UI coordinate does not
            // claim both native durable originals, original actor 103 or cash-session preservation.
        }
    }

    @Test
    fun mpv2EntryAndAttachmentReviewDoNotAutomaticallyRecoverWhileReadIsPendingOrIdle() = runTest {
        for (attachmentReview in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            try {
                withManagerController(attachmentReviewRequired = attachmentReview, response = {
                    withContext(NonCancellable) { entered.complete(Unit); release.await(); managerPresent() }
                }) { fixture ->
                    if (attachmentReview) fixture.controller.reviewAttachment(fixture.token())
                    runCurrent()
                    assertTrue(entered.isCompleted)
                    assertNull(fixture.controller.state.value.page)
                    fixture.controller.refreshPending(fixture.token())
                    runCurrent()
                    fixture.assertReviewOnly()
                    release.complete(Unit)
                    runCurrent()
                    assertBoundManagerPage(assertNotNull(fixture.controller.state.value.page), ManagerPresentationInputs.legacyExpected)
                    fixture.controller.refreshPending(fixture.token())
                    runCurrent()
                    fixture.assertReviewOnly()
                    fixture.harness.assertTargetDispatch()
                    fixture.controller.deactivate()
                    fixture.audit.assertNoRecoveryOrMutations()
                    fixture.harness.assertReadOnly()
                }
            } finally { release.complete(Unit); runCurrent() }
        }
        // Existing durable UNKNOWN/COMMITTED records and after-exit recovery availability remain
        // unbound; no accepting in-memory journal stands in for actual native original storage.
    }

    @Test
    fun mpv2PhysicalAndMutationCallbacksRemainDisabledInBothRuntimeLanguages() = runTest {
        for (language in AppLanguage.entries) withManagerController(language = language) { fixture ->
            assertBoundManagerPage(assertNotNull(fixture.controller.state.value.page), ManagerPresentationInputs.legacyExpected)
            val controller = fixture.controller
            for (action in LocalAction.entries) controller.openForm(action, fixture.token())
            controller.confirmForm(true, fixture.token())
            controller.stage(fixture.token())
            controller.retryStage(fixture.token())
            controller.submit(ManagerPresentationInputs.unknownOriginal, fixture.token())
            controller.lookup(ManagerPresentationInputs.unknownOriginal, fixture.token())
            controller.acknowledgeRecorded(ManagerPresentationInputs.committedOriginal, fixture.token())
            controller.refreshPending(fixture.token())
            controller.openRetailCollection(fixture.token())
            val physical = controller.retailPhysical
            for (field in LocalRetailInput.entries) physical.changeInput(field, "constructor-only", fixture.token())
            physical.chooseTender(null, fixture.token())
            physical.chooseObservation(LocalRetailObserved.UNKNOWN, fixture.token())
            physical.confirmObservation(true, fixture.token())
            physical.loadPayment(fixture.token())
            physical.beginCollection(fixture.token())
            physical.preparePayment(fixture.token())
            physical.loadFoodRefund(fixture.token())
            physical.readFoodRefundOriginal(fixture.token())
            physical.loadRefunds(fixture.token())
            physical.loadAcceptedRefunds(fixture.token())
            physical.loadPending(fixture.token())
            physical.reviewOriginal(fixture.token())
            physical.resolve(fixture.token())
            physical.stage(fixture.token())
            physical.start(ManagerPresentationInputs.unknownOriginal, fixture.token())
            physical.continueSql(ManagerPresentationInputs.unknownOriginal, fixture.token())
            physical.recoverInput(fixture.token())
            physical.recover(ManagerPresentationInputs.unknownOriginal, fixture.token())
            physical.sampleInstruction(fixture.token())
            physical.endInstruction(fixture.token())
            physical.capture(fixture.token())
            physical.acknowledge(fixture.token())
            val supplier = controller.supplierPhysical
            supplier.preparePayout(fixture.token())
            supplier.stage(fixture.token())
            supplier.start(ManagerPresentationInputs.unknownOriginal, fixture.token())
            supplier.continueSql(ManagerPresentationInputs.unknownOriginal, fixture.token())
            supplier.loadPending(fixture.token())
            supplier.recoverInput(fixture.token())
            supplier.recover(ManagerPresentationInputs.unknownOriginal, fixture.token())
            supplier.capture(fixture.token())
            supplier.acknowledge(fixture.token())
            runCurrent()
            fixture.assertReviewOnly()
            assertNull(controller.state.value.form)
            assertNull(physical.state.value.preview)
            assertNull(physical.state.value.instruction)
            fixture.harness.assertTargetDispatch()
        }
        // Actual touch/keyboard controls and typed callbacks requiring absent saved facts are not
        // fabricated here. Disabled display/current state is a controller slice, not an installed UI check.
    }

    @Test
    fun mpv1OldManagerReplyCannotPublishAfterActualRuntimeAttachesOrdinaryCashier() = runTest {
        val delivery = HeldManagerReadDelivery()
        withManagerController(delivery = delivery) { fixture ->
            assertTrue(delivery.ready.isCompleted)
            assertBoundManagerPage(assertNotNull(delivery.result), ManagerPresentationInputs.legacyExpected)
            assertNull(fixture.controller.state.value.page)
            fixture.runtime.localSessionEnded(fixture.session)
            val replacement = ControlledManagerSession(null, null, SessionDisplayContext(
                ManagerPresentationInputs.cashier, ManagerPresentationInputs.scope.companyId, ManagerPresentationInputs.scope.branchId,
                "Omar Ordinary Cashier", "Synthetic retail company", "Synthetic branch A", setOf(AppArea.CASHIER)),
                LocalSessionAttachmentKey(ManagerPresentationInputs.cashierLease, 1))
            fixture.signIn(replacement)
            val next = LocalWorkflowController(fixture.runtime, replacement, fixture.token(), this)
            try {
                assertEquals(ManagerPresentationInputs.cashier, fixture.runtime.presentation.value.display?.actorId)
                assertNull(next.state.value.page)
                delivery.release.complete(Unit)
                runCurrent()
                assertTrue(delivery.delivered, "The controlled boundary does not suppress the obsolete Available reply")
                assertNull(fixture.controller.state.value.page)
                assertNull(next.state.value.page)
                fixture.audit.assertNoRecoveryOrMutations()
                fixture.harness.assertTargetDispatch()
            } finally { next.close() }
        }
        // The old real gateway finished mapping before the held UI delivery. The real controller's
        // attachment fence is tested independently of the gateway's earlier credential guard.
    }
}

/** JVM binding of the actual private navigation mapper; no source visibility or policy substitute. */
private fun soleGrantDisplay(harness: ControlledManagerGateway): SessionDisplayContext {
    val owner = Class.forName("com.ukkera.pos.app.gateway.LocalEndpointBranchGatewayKt")
    val mapper = owner.getDeclaredMethod("display", ActivatedLocalPolicy::class.java,
        LocalLoginIdentifier::class.java, LocalBranchDisplayLabels::class.java, java.lang.Boolean.TYPE,
        LocalSessionWorkspaceMode::class.java)
    mapper.isAccessible = true
    return mapper.invoke(null, harness.endpoint.view.policy, harness.endpoint.view.login,
        LocalBranchDisplayLabels(ManagerPresentationInputs.scope, "Synthetic retail company", "Synthetic branch A"), false,
        LocalSessionWorkspaceMode.POLICY_FILTERED) as SessionDisplayContext
}

private class HeldManagerReadDelivery {
    val ready = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    var result: LocalReadResult? = null
    var delivered = false
}

/** Audits calls while delegating all real workflow behavior; held delivery performs no cleanup. */
private class ManagerWorkflowAudit(private val actual: LocalWorkflowGateway,
    private val delivery: HeldManagerReadDelivery?) : LocalWorkflowGateway by actual {
    val reads = mutableListOf<LocalWorkflowQuery>()
    val nonReadCalls = mutableListOf<String>()
    override suspend fun read(query: LocalWorkflowQuery): LocalReadResult {
        reads += query
        val result = actual.read(query)
        if (delivery != null) {
            delivery.result = result
            delivery.ready.complete(Unit)
            withContext(NonCancellable) { delivery.release.await(); delivery.delivered = true }
        }
        return result
    }
    override suspend fun references(query: LocalReferenceQuery): LocalReferenceResult {
        nonReadCalls += "references"; return actual.references(query)
    }
    override suspend fun pending(cursor: String?): LocalPendingResult {
        nonReadCalls += "pending"; return actual.pending(cursor)
    }
    override suspend fun stage(intent: LocalCommandIntent): LocalStageResult {
        nonReadCalls += "stage"; return actual.stage(intent)
    }
    override suspend fun submit(operationId: OperationId): LocalOperationResult {
        nonReadCalls += "submit"; return actual.submit(operationId)
    }
    override suspend fun lookup(operationId: OperationId): LocalOperationResult {
        nonReadCalls += "lookup"; return actual.lookup(operationId)
    }
    override suspend fun acknowledgeRecorded(operationId: OperationId): LocalAcknowledgementResult {
        nonReadCalls += "acknowledgeRecorded"; return actual.acknowledgeRecorded(operationId)
    }
    fun assertNoRecoveryOrMutations() { assertTrue(nonReadCalls.isEmpty(), "No automatic pending/recovery, staging, mutation or acknowledgement") }
}

/** No accepted physical/native operation; every actual capability method records and rejects. */
private class RejectingManagerPhysical : LocalRetailPhysicalGateway {
    val calls = mutableListOf<String>()
    private fun reject(name: String): Nothing { calls += name; throw AssertionError("Forbidden manager physical capability: $name") }
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

/** Current/attachment flows are controlled presentation coordinates, not authenticated sessions. */
private class ControlledManagerSession(private val harness: ControlledManagerGateway?, private val audit: ManagerWorkflowAudit?,
    override val display: SessionDisplayContext, key: LocalSessionAttachmentKey) : BranchUiSession {
    val physical = RejectingManagerPhysical()
    private var active = true
    private val attachments = MutableStateFlow<LocalSessionAttachmentKey?>(key)
    private val lifecycleState = MutableStateFlow(LocalSessionLifecycle.ACTIVE)
    override val workflows: LocalWorkflowGateway get() = audit ?: UnavailableLocalWorkflows
    override val retailPhysical: LocalRetailPhysicalGateway get() = physical
    override val attachmentUpdates: StateFlow<LocalSessionAttachmentKey?> get() = attachments
    override val lifecycle: StateFlow<LocalSessionLifecycle> get() = lifecycleState
    override fun isCurrent(): Boolean = active
    override suspend fun current(): LocalSessionCheckResult = if (active) LocalSessionCheckResult.Current(display, attachmentKey)
        else LocalSessionCheckResult.Unavailable(LocalServiceProblem.SESSION_ENDED, attachmentKey)
    override suspend fun mayRestore(route: AppRoute): Boolean = active && RouteRegistry.area(route) in display.allowedAreas
    override fun close() {
        active = false
        harness?.leaseCurrent = false
        attachments.value = null
        lifecycleState.value = LocalSessionLifecycle.LOCKED
    }
}

private class MutableManagerSignIn(var session: BranchUiSession) : BranchUiGateway {
    override val configured = true
    override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> = SignInResult.Authenticated(session)
}

private class ManagerControllerFixture(val runtime: UkkeraRuntime, val controller: LocalWorkflowController,
    val session: ControlledManagerSession, val harness: ControlledManagerGateway, val audit: ManagerWorkflowAudit,
    private val signInPort: MutableManagerSignIn) {
    fun token(): NavigationToken = runtime.navigator.token()
    suspend fun signIn(next: BranchUiSession) {
        signInPort.session = next
        runtime.openSignIn(false, token())
        runtime.signIn(next.display.actorName, charArrayOf('x'), token())
        assertEquals(next.display, runtime.presentation.value.display)
    }
    fun assertReviewOnly() {
        val state = controller.state.value
        assertTrue(state.managerOrderReview)
        assertFalse(state.canStart)
        assertNull(state.form)
        assertTrue(state.page?.forms.isNullOrEmpty())
        assertFalse(controller.retailPhysical.displayCurrent(token()))
        assertFalse(controller.supplierPhysical.displayCurrent(token()))
        assertTrue(session.physical.calls.isEmpty())
        audit.assertNoRecoveryOrMutations()
        harness.assertReadOnly()
    }
}

private suspend fun TestScope.withManagerController(expected: BoundManagerOrder = ManagerPresentationInputs.legacyExpected,
    language: AppLanguage = AppLanguage.ENGLISH, selected: LocalRecordReference? = null,
    retainedOperation: LocalOperationResult.Recorded? = null, attachmentReviewRequired: Boolean = false,
    delivery: HeldManagerReadDelivery? = null, response: suspend () -> ReadReply = { managerPresent(expected) },
    block: suspend (ManagerControllerFixture) -> Unit) {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    var runtime: UkkeraRuntime? = null
    var controller: LocalWorkflowController? = null
    try {
        val harness = ControlledManagerGateway(target = expected.target, response = response)
        val audit = ManagerWorkflowAudit(harness.gateway, delivery)
        val session = ControlledManagerSession(harness, audit, soleGrantDisplay(harness),
            LocalSessionAttachmentKey(ManagerPresentationInputs.managerLease, 1))
        val signInPort = MutableManagerSignIn(session)
        val ownedRuntime = UkkeraRuntime(AppDependencies(branchGateway = signInPort, platform = PlatformCapabilities(PlatformFamily.DESKTOP)))
        runtime = ownedRuntime
        ownedRuntime.changeLanguage(language)
        ownedRuntime.openSignIn(false, ownedRuntime.navigator.token())
        ownedRuntime.signIn("Mira Manager", charArrayOf('x'), ownedRuntime.navigator.token())
        assertEquals(session.display, ownedRuntime.presentation.value.display)
        // Retained manager workspace prevents unrelated initial own-order/recovery dispatch.
        // This is a controller input seam, not execution of a typed/physical UI search affordance.
        val workspace = LocalWorkspaceState(workflow = LocalWorkflow.ORDERS, search = ManagerPresentationInputs.query(expected.target).search,
            reference = selected, operationId = retainedOperation?.operationId, operation = retainedOperation,
            attachmentReviewRequired = attachmentReviewRequired)
        controller = LocalWorkflowController(ownedRuntime, session, ownedRuntime.navigator.token(), this,
            LocalWorkflowContinuation(workspace, null, null, null, null))
        controller.activate(LocalWorkflow.ORDERS)
        runCurrent()
        block(ManagerControllerFixture(ownedRuntime, controller, session, harness, audit, signInPort))
    } finally {
        delivery?.release?.complete(Unit)
        try { controller?.close(); runtime?.close(); runCurrent() } finally { Dispatchers.resetMain() }
    }
}
