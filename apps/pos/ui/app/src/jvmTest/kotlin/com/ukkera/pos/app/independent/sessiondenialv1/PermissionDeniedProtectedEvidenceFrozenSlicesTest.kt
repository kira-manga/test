package com.ukkera.pos.app.independent.sessiondenialv1

import com.ukkera.pos.app.AppDependencies
import com.ukkera.pos.app.BranchUiGateway
import com.ukkera.pos.app.BranchUiSession
import com.ukkera.pos.app.LocalAction
import com.ukkera.pos.app.LocalOperationResult
import com.ukkera.pos.app.LocalPendingOperation
import com.ukkera.pos.app.LocalPendingPhase
import com.ukkera.pos.app.LocalPaymentSubject
import com.ukkera.pos.app.LocalPurchasingUiQuery
import com.ukkera.pos.app.LocalReadResult
import com.ukkera.pos.app.LocalRecord
import com.ukkera.pos.app.LocalRecordedDecision
import com.ukkera.pos.app.LocalRecordKind
import com.ukkera.pos.app.LocalRecordReference
import com.ukkera.pos.app.LocalRecordValue
import com.ukkera.pos.app.LocalRetailInstructionDisplay
import com.ukkera.pos.app.LocalRetailObservation
import com.ukkera.pos.app.LocalRetailPhysicalCapture
import com.ukkera.pos.app.LocalRetailPhysicalGateway
import com.ukkera.pos.app.LocalRetailPhysicalInstruction
import com.ukkera.pos.app.LocalRetailPhysicalKind
import com.ukkera.pos.app.LocalRetailPhysicalLifetime
import com.ukkera.pos.app.LocalRetailPhysicalPreparation
import com.ukkera.pos.app.LocalRetailPhysicalReply
import com.ukkera.pos.app.LocalRetailPhysicalRequest
import com.ukkera.pos.app.LocalRetailFinancialKind
import com.ukkera.pos.app.LocalRetailSavedOperation
import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.app.LocalWorkflow
import com.ukkera.pos.app.LocalWorkBusy
import com.ukkera.pos.app.LocalWorkflowController
import com.ukkera.pos.app.LocalWorkflowGateway
import com.ukkera.pos.app.LocalWorkflowPage
import com.ukkera.pos.app.PlatformCapabilities
import com.ukkera.pos.app.PlatformFamily
import com.ukkera.pos.app.SignInResult
import com.ukkera.pos.app.UkkeraRuntime
import com.ukkera.pos.app.UnavailableLocalRetailPhysical
import com.ukkera.pos.app.independent.sessionpresentationv1.SensitiveControlledSession
import com.ukkera.pos.app.independent.sessionpresentationv1.SensitiveControlledWorkflowBoundary
import com.ukkera.pos.app.independent.sessionterminalv1.FrozenTerminalCleanupV1
import com.ukkera.pos.app.independent.sessionterminalv1.TerminalDenialProbe
import com.ukkera.pos.app.independent.sessionterminalv1.terminalEntry
import com.ukkera.pos.app.independent.sessionterminalv1.terminalText
import com.ukkera.pos.app.independent.sessionterminalv1.terminalUiSession
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.app.navigation.NavigationAction
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import java.lang.reflect.InvocationTargetException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * STC-S12/S10 and LSP-002-A01/A03/A04 bounded UI/lifetime slices. The production
 * controller's current DENIED handler and page-unavailable entrypoint are invoked
 * directly while the retail or supplier child holds a controlled negative lifetime.
 * Reflection delivers the fixed denial input; it never writes a production field,
 * changes an oracle, performs a protected read/RPC or installs native authority.
 *
 * A controlled instruction permits only UI-lifetime observation and always rejects
 * capture. Its display and RECORDED_ONLY result are inert inputs, not accepted native
 * instructions, protected evidence, durable payment completion or money/stock proof.
 * The supplier variant reuses the frozen retail IDs and 300-EGP display solely as
 * inert UI carriers for the same cleanup schedule; they are not supplier evidence.
 * The original selected operation ID before retirement and zero new controlled
 * mutation/physical calls are observable; full body/actor/session/evidence retention and independent
 * durable effects remain unbound. No native closure or successor writer is qualified.
 * The stale-navigation methods deliver the fixed disposal entrypoint directly, using
 * the same unchanged STC-S12 independent-cleanup/private-erasure oracle. They do not
 * execute a coroutine/navigation race or prove any native close.
 * All complete frozen cases/variants and unbound required layers remain NOT_RUN.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PermissionDeniedProtectedEvidenceFrozenSlicesTest {
    @Test
    fun stcS12_currentDeniedClearsPrivatePendingResultAndConfigurationDespiteFailedPhysicalOwner() = runTest {
        exerciseCurrentDenial(supplierPage = false)
    }

    @Test
    fun stcS12_pageDeniedReachesIndependentCleanupDespiteFailedSupplierOwner() = runTest {
        exerciseCurrentDenial(supplierPage = true)
    }

    @Test
    fun stcS12_retailChildDeniedClearsPrivateInstructionBeforeParentCleanup() = runTest {
        exerciseCurrentDenial(supplierPage = false, childDenialFirst = true)
    }

    @Test
    fun stcS12_supplierChildDeniedClearsPrivateInstructionBeforeParentCleanup() = runTest {
        exerciseCurrentDenial(supplierPage = true, childDenialFirst = true)
    }

    @Test
    fun stcS12_retailInstructionEndClearsPrivateInstructionBeforeParentCleanup() = runTest {
        exerciseCurrentDenial(supplierPage = false, childDenialFirst = true, explicitEnd = true)
    }

    @Test
    fun stcS12_supplierInstructionEndClearsPrivateInstructionBeforeParentCleanup() = runTest {
        exerciseCurrentDenial(supplierPage = true, childDenialFirst = true, explicitEnd = true)
    }

    @Test
    fun stcS12_staleNavigationClearsPrivateViewsDespiteFailedRetailOwner() = runTest {
        exerciseCurrentDenial(supplierPage = false, staleNavigation = true)
    }

    @Test
    fun stcS12_staleNavigationClearsPrivateViewsDespiteFailedSupplierOwner() = runTest {
        exerciseCurrentDenial(supplierPage = true, staleNavigation = true)
    }

    private suspend fun TestScope.exerciseCurrentDenial(supplierPage: Boolean, childDenialFirst: Boolean = false,
        explicitEnd: Boolean = false, staleNavigation: Boolean = false) {
        val freeze = FrozenTerminalCleanupV1.load()
        val message = "${freeze.case("STC-S12")} / ${freeze.case("STC-S10")}"
        val operationId = freeze.paymentOperation()
        val marker = freeze.privateCanary(0)
        val saved = LocalPendingOperation(operationId, LocalAction.PAYMENT_ATTEST, terminalText(marker),
            listOf(LocalRecordValue(terminalText("Original private pending display"), terminalText(marker))),
            submitAllowed = false, phase = LocalPendingPhase.DISPATCH_UNRESOLVED)
        val privateResult = LocalOperationResult.Recorded(operationId, LocalRecordedDecision.RECORDED_ONLY,
            terminalText(marker), result = LocalRecord(
                LocalRecordReference(LocalRecordKind.PAYMENT, DomainId.random()), terminalText(marker),
                terminalText(marker), listOf(LocalRecordValue(terminalText("Private read display"), terminalText(marker)))))
        val boundary = SensitiveControlledWorkflowBoundary(readReply = { query ->
            LocalReadResult.Available(LocalWorkflowPage(query.workflow, emptyList(), emptyList(),
                notice = terminalText(freeze.privateCanary(2))))
        }, saved = listOf(saved), lookupReply = { privateResult })
        val physical = DeniedControlledPhysicalBoundary(freeze, supplierPage)
        val originalControl = terminalUiSession(freeze, boundary = boundary)
        val control = if (supplierPage) SensitiveControlledSession(
            initialDisplay = originalControl.display.copy(allowedAreas = originalControl.display.allowedAreas + AppArea.PURCHASING),
            initialBoundary = boundary,
        ) else originalControl
        val session = DeniedPhysicalSession(control, boundary, physical)
        val fault = IllegalStateException("Synthetic required physical-owner cleanup failure")
        val failed = TerminalDenialProbe(fault)
        val later = TerminalDenialProbe()
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var runtime: UkkeraRuntime? = null
        var signIns = 0
        try {
            val actual = UkkeraRuntime(AppDependencies(branchGateway = object : BranchUiGateway {
                override val configured = true
                override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> {
                    check(account == session.display.actorName)
                    signIns++
                    return SignInResult.Authenticated(session)
                }
            }, platform = PlatformCapabilities(PlatformFamily.DESKTOP))).also { runtime = it }
            actual.openSignIn(company = false, actual.navigator.token())
            actual.signIn(session.display.actorName, charArrayOf('s', 'a', 'm', 'p', 'l', 'e'), actual.navigator.token())
            runCurrent()
            if (supplierPage) actual.navigate(NavigationAction.SelectArea(AppArea.PURCHASING), actual.navigator.token())
            val entry = terminalEntry(actual.rootScope, actual.navigator.state.value.activeEntry.id)
            val controller = assertNotNull(entry.localWorkflows(actual, actual.navigator.token()))
            controller.activate(if (supplierPage) LocalWorkflow.PURCHASING else LocalWorkflow.CHECKOUT)
            runCurrent()
            if (supplierPage) controller.selectPurchasing(LocalPurchasingUiQuery(includeCosts = true), actual.navigator.token())
            runCurrent()
            controller.lookup(operationId, actual.navigator.token())
            runCurrent()
            // Recorded-result delivery deliberately retires its former page. Re-read the
            // independent current page before scheduling simultaneous private-holder cleanup.
            controller.refresh(actual.navigator.token())
            runCurrent()
            check(controller.state.value.pending.single().operationId == operationId &&
                (controller.state.value.operation as? LocalOperationResult.Recorded)?.result?.title?.english == marker &&
                controller.state.value.page != null) {
                "Private controlled pending and result presentations must reach the controller before DENIED."
            }
            if (supplierPage) controller.supplierPhysical.loadPending(actual.navigator.token())
            else controller.retailPhysical.loadPending(actual.navigator.token())
            runCurrent()
            if (supplierPage) controller.supplierPhysical.start(operationId, actual.navigator.token())
            else controller.retailPhysical.start(operationId, actual.navigator.token())
            runCurrent()
            val originalLifetime = assertNotNull(physical.originalLifetime,
                "The production physical child must acquire its real runtime-created negative owner.")
            assertNotNull(if (supplierPage) controller.supplierPhysical.state.value.instruction else controller.retailPhysical.state.value.instruction,
                "The controlled UI instruction must be installed before its negative-owner failure is observed.")
            assertFalse(originalLifetime.denied, "The failure schedule begins with an attached original negative lifetime.")
            assertNotNull(originalLifetime.attach(failed))
            assertNotNull(originalLifetime.attach(later))
            seedDeniedConfiguration(entry, freeze.privateCanary(1))
            val configuration = DeniedConfigurationProbe(entry)
            val token = actual.navigator.token()
            val calls = boundary.calls.toList()
            val physicalCalls = physical.calls.toList()
            val originalAttachment = actual.presentation.value.localAttachment
            val signInsBeforeFailure = signIns

            if (childDenialFirst) {
                // Same frozen failed-resource schedule, delivered first through the
                // physical child's current DENIED entrypoint. No field is mutated.
                val child: Any = if (supplierPage) controller.supplierPhysical else controller.retailPhysical
                observeControlledFault(fault) {
                    if (explicitEnd) {
                        if (supplierPage) controller.supplierPhysical.endInstruction(token)
                        else controller.retailPhysical.endInstruction(token)
                    } else deliverChildDenied(child)
                }
                assertTrue(failed.attempts > 0 && later.attempts > 0,
                    "$message: the failed callback does not skip a later independent negative cleanup")
                assertFalse(failed.released, "$message: failed physical cleanup has not been relabeled successful")
                assertTrue(originalLifetime.denied, "$message: failure cannot restore the original lifetime")
                assertTrue(physical.instruction.denied, "$message: independent instruction denial still completes")
                assertFalse(if (supplierPage) controller.supplierPhysical.displayCurrent(token) else controller.retailPhysical.displayCurrent(token),
                    "$message: retained failed physical ownership cannot publish private instruction data")
                assertNull(if (supplierPage) controller.supplierPhysical.state.value.instruction else controller.retailPhysical.state.value.instruction,
                    "$message: required cleanup failure hides old ordinary private presentation")
                assertTrue(if (supplierPage) controller.supplierPhysical.state.value.saved.isEmpty() else controller.retailPhysical.state.value.saved.isEmpty(),
                    "$message: old private pending summaries are absent")
                assertEquals(calls, boundary.calls, "LSP-002-A03/A04: DENIED initiates no business or acknowledgement call")
                assertEquals(physicalCalls, physical.calls, "LSP-002-A03/A04: DENIED initiates no physical call")
            }
            observeControlledFault(fault) {
                if (staleNavigation) deliverStaleNavigation(controller)
                else deliverCurrentDenied(controller, supplierPage)
            }

            assertTrue(failed.attempts > 0 && later.attempts > 0,
                "$message: the failed callback does not skip a later independent negative cleanup")
            assertFalse(failed.released, "$message: failed physical cleanup has not been relabeled successful")
            assertTrue(later.released, "$message: later successful lifetime has actually ended")
            assertTrue(originalLifetime.denied, "$message: failure cannot restore the original lifetime")
            assertTrue(physical.instruction.denied, "$message: independent instruction denial still completes")
            assertFalse(if (supplierPage) controller.supplierPhysical.displayCurrent(token) else controller.retailPhysical.displayCurrent(token),
                "$message: retained failed physical ownership cannot publish private instruction data")
            assertNull(controller.state.value.page, "LSP-002-A01 / $message: old private page is absent")
            assertNull(controller.state.value.form, "$message: old private full-form presentation is absent")
            assertNull(controller.state.value.referencePicker, "$message: old private reference presentation is absent")
            assertTrue(controller.state.value.pending.isEmpty(), "$message: old private pending summaries are absent")
            assertTrue(controller.state.value.operation == null || controller.state.value.operation is LocalOperationResult.Unresolved,
                "$message: old private Pending/Recorded details do not remain in the ordinary result presentation")
            assertTrue(controller.state.value.operationId == null || controller.state.value.operationId == operationId,
                "LSP-002-A03 / STC-S12: retiring ordinary UI does not install a replacement operation identity")
            (controller.state.value.operation as? LocalOperationResult.Unresolved)?.let {
                assertEquals(operationId, it.operationId,
                    "LSP-002-A03: any retained unknown UI envelope refers only to the original identity")
            }
            assertFalse(controller.state.value.canStart,
                "LSP-002-A03: cleanup does not authorize replacement business work")
            assertDeniedConfigurationCleared(entry, configuration, message)
            assertNull(actual.presentation.value.display,
                "$message: required cleanup failure hides old ordinary private presentation")
            assertFalse(actual.ownsLocalAttachment(session, token, originalAttachment),
                "$message: failed cleanup cannot restore old local access")
            assertNull(actual.localSessionFor(token), "$message: old local protected-work acquisition is invalid")
            assertFalse(actual.navigator.isCurrent(token), "$message: the failed scope cannot remain an active callback owner")
            assertTrue(actual.presentation.value.cleanupNeedsAttention,
                "$message: failed physical ownership is not reported as conclusive cleanup")
            actual.openSignIn(company = false, actual.navigator.token())
            actual.signIn("ACTOR-A", charArrayOf('s', 'a', 'm', 'p', 'l', 'e'), actual.navigator.token())
            assertEquals(signInsBeforeFailure, signIns,
                "$message: failed cleanup authorizes no controlled successor authentication delivery")
            assertEquals(calls, boundary.calls, "LSP-002-A03/A04: DENIED initiates no business or acknowledgement call")
            assertEquals(physicalCalls, physical.calls, "LSP-002-A03/A04: DENIED initiates no physical call")
            assertEquals(0, physical.instruction.captureRequests,
                "LSP-002-A03/A04: denial does not recapture or repeat an observation")

            val attemptsAfterDenial = failed.attempts
            observeControlledFault(fault) {
                controller.close()
            }
            assertTrue(failed.attempts > attemptsAfterDenial,
                "$message: failed ownership is retained rather than replaced by a closed UI state")
            assertFalse(failed.released, "$message: retrying a still-failing callback does not report its release")
            assertTrue(actual.presentation.value.cleanupNeedsAttention,
                "$message: a retry cannot convert unresolved required cleanup to success")
            val beforeResolution = failed.attempts
            failed.fail = false
            controller.close()
            assertTrue(failed.attempts > beforeResolution && failed.released,
                "$message: resolution retries the same required physical callback")
            assertEquals(calls, boundary.calls, "LSP-002-A03/A04: negative cleanup never replays or acknowledges the original")
            assertEquals(physicalCalls, physical.calls, "LSP-002-A03/A04: negative retry grants no new physical instruction")
            assertEquals(0, physical.instruction.captureRequests,
                "LSP-002-A03/A04: negative retry does not recapture an original observation")
        } finally {
            failed.fail = false
            try { runtime?.close(); runCurrent() } finally { Dispatchers.resetMain() }
        }
    }
}

/** Controlled sign-in/display input; no production authority is supplied by the wrapper. */
private class DeniedPhysicalSession(control: SensitiveControlledSession,
    override val workflows: LocalWorkflowGateway,
    override val retailPhysical: LocalRetailPhysicalGateway,
) : BranchUiSession by control

/** Fixed input to the actual method; its internal cleanup algorithm is not an oracle. */
private fun deliverCurrentDenied(controller: LocalWorkflowController, pageRead: Boolean) {
    val method = LocalWorkflowController::class.java.getDeclaredMethod(
        if (pageRead) "readUnavailable" else "permissionFailure", LocalServiceProblem::class.java)
    method.isAccessible = true
    try { method.invoke(controller, LocalServiceProblem.DENIED) }
    catch (failure: InvocationTargetException) { throw failure.targetException }
}

/** Fixed late-reply disposal input. Does not claim an asynchronous navigation race run. */
private fun deliverStaleNavigation(controller: LocalWorkflowController) {
    val method = LocalWorkflowController::class.java.getDeclaredMethod("discardStaleNavigation", LocalWorkBusy::class.java)
    method.isAccessible = true
    try { method.invoke(controller, LocalWorkBusy.READING) }
    catch (failure: InvocationTargetException) { throw failure.targetException }
}

/** Fixed child denial input; same frozen observables as the parent-denial schedule. */
private fun deliverChildDenied(child: Any) {
    val method = child.javaClass.getDeclaredMethod("unavailable", LocalServiceProblem::class.java)
    method.isAccessible = true
    try { method.invoke(child, LocalServiceProblem.DENIED) }
    catch (failure: InvocationTargetException) { throw failure.targetException }
}

/** A status or the original controlled exception may expose failure; unrelated errors escape. */
private fun observeControlledFault(expected: Throwable, block: () -> Unit) {
    try { block() }
    catch (failure: Throwable) {
        if (failure !== expected && failure.cause !== expected && failure.suppressed.none { it === expected }) throw failure
    }
}

/**
 * Controlled UI data only. The actual runtime creates/retains the negative lifetime.
 * Preparation, money capture, SQL continuation, recovery and new instructions are never
 * supplied as positive native capabilities. One controlled START belongs to setup only.
 */
private class DeniedControlledPhysicalBoundary(freeze: FrozenTerminalCleanupV1,
    private val supplier: Boolean,
) : LocalRetailPhysicalGateway by UnavailableLocalRetailPhysical {
    private val operationId = freeze.paymentOperation()
    val calls = mutableListOf<String>()
    var originalLifetime: LocalRetailPhysicalLifetime? = null
        private set
    val instruction = DeniedControlledInstruction(LocalRetailInstructionDisplay(operationId,
        OperationId("00000000-0000-4000-8000-000000000021"),
        OperationId("00000000-0000-4000-8000-000000000022"),
        if (supplier) LocalRetailPhysicalKind.SUPPLIER_CASH_OUT else LocalRetailPhysicalKind.PAYMENT,
        freeze.aOrder(), freeze.id("cash_session_a"), Money(Currency.EGP, 30000),
        if (supplier) "CASH" else "EXTERNAL_CARD", requiresTerminal = false, requiresReference = !supplier,
        subject = if (supplier) LocalPaymentSubject.SUPPLIER else LocalPaymentSubject.RETAIL))

    override suspend fun pending(afterFact: DomainId?): LocalRetailPhysicalReply {
        calls += "PENDING"
        return LocalRetailPhysicalReply.Saved(listOf(LocalRetailSavedOperation(operationId,
            terminalText("Controlled original UI lifetime"), dispatched = false, isPhysicalStart = true,
            summary = listOf(LocalRecordValue(terminalText("Controlled original display"), terminalText("STC-SYNTHETIC-EXTERNAL-300"))),
            subject = if (supplier) LocalPaymentSubject.SUPPLIER else LocalPaymentSubject.RETAIL,
            financialKind = if (supplier) LocalRetailFinancialKind.SUPPLIER_CASH else null)),
            emptyList(), null)
    }

    override suspend fun pendingSupplierCash(afterFact: DomainId?): LocalRetailPhysicalReply {
        check(supplier)
        return pending(afterFact)
    }

    override suspend fun start(operationId: OperationId, originalEntry: LocalRetailPhysicalLifetime): LocalRetailPhysicalReply {
        check(operationId == this.operationId && originalLifetime == null)
        calls += "START"
        originalLifetime = originalEntry
        return LocalRetailPhysicalReply.Instruction(instruction)
    }

    override suspend fun paymentContext(collectionId: DomainId, afterTender: DomainId?): LocalRetailPhysicalReply {
        calls += "PAYMENT_CONTEXT"
        return UnavailableLocalRetailPhysical.paymentContext(collectionId, afterTender)
    }

    override suspend fun prepare(request: LocalRetailPhysicalRequest): LocalRetailPhysicalReply {
        calls += "PREPARE"
        return UnavailableLocalRetailPhysical.prepare(request)
    }

    override suspend fun stage(preparation: LocalRetailPhysicalPreparation): LocalRetailPhysicalReply {
        calls += "STAGE"
        return UnavailableLocalRetailPhysical.stage(preparation)
    }

    override suspend fun continueSql(operationId: OperationId): LocalRetailPhysicalReply {
        calls += "CONTINUE_SQL"
        return UnavailableLocalRetailPhysical.continueSql(operationId)
    }

    override suspend fun recover(operationId: OperationId): LocalRetailPhysicalReply {
        calls += "RECOVER"
        return UnavailableLocalRetailPhysical.recover(operationId)
    }

    override suspend fun refundDues(after: DomainId?): LocalRetailPhysicalReply {
        calls += "REFUND_DUES"
        return UnavailableLocalRetailPhysical.refundDues(after)
    }
}

/** UI delivery flag only. It can be withdrawn; capture can never produce evidence. */
private class DeniedControlledInstruction(override val display: LocalRetailInstructionDisplay) : LocalRetailPhysicalInstruction {
    var denied = false
        private set
    var captureRequests = 0
        private set
    override fun deliverable(): Boolean = !denied
    override fun deny() { denied = true }
    override fun capture(observation: LocalRetailObservation): LocalRetailPhysicalCapture {
        captureRequests++
        return LocalRetailPhysicalCapture.Rejected
    }
}
