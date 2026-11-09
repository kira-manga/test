@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.gateway.independent.approvalretirementnoadmission

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.app.navigation.NavigationToken
import com.ukkera.pos.app.navigation.RouteRegistry
import com.ukkera.pos.database.LocalActionApprovalNoAdmissionReason
import com.ukkera.pos.database.LocalActionApprovalReceipt
import com.ukkera.pos.database.LocalActionApprovalRetireResult
import com.ukkera.pos.database.LocalActionApprovalRetirementReceipt
import com.ukkera.pos.database.LocalActionApprovalStatus
import com.ukkera.pos.database.LocalMasterApprovalClient
import com.ukkera.pos.database.LocalMasterClient
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.LocalActionApprovalRetirementAttempt as MasterRetirementAttempt
import com.ukkera.pos.inventory.store.LocalUnpricedArrivalCommandsV2
import com.ukkera.pos.inventory.store.LocalUnpricedArrivalProfileV2
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.LocalSessionViewResult
import com.ukkera.pos.security.MonotonicMoment
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes

/**
 * Additional frozen ARRNA-01/03/08 client slices, not revisions of the published tests.
 * The gateway/controller and encrypted journal are real. Historical issuance, current
 * views, terminal statuses and endpoint replies are controlled client observations.
 * These do not authenticate native history, Master authority, business effects,
 * transport replay or lost-negative fencing, and do not pass a whole frozen case.
 */
class ArrivalRetirementHeldLookupSlicesTest {
    @Test
    fun arrna01GatewayHeldRepeatEntersAuthorizedLookupBeforePrimaryReply() = runTest(timeout = 3.minutes) {
        withArrivalRetirementNoAdmissionFixture { fixture ->
            val probe = HeldArrivalLookupProbe(fixture)
            val primaryHold = probe.holdRetirement()
            val primary = async { probe.gateway.retireActionApproval(fixture.inputs.operationId) }
            var repeat: Deferred<LocalActionApprovalResult>? = null
            try {
                val handoff = arrivalMilestone { primaryHold.entered.await() }
                val pending = assertHeldOriginalInvocation(fixture, handoff)
                val lookupHold = probe.endpoint.holdLookup()
                val beforeLookups = probe.endpoint.lookups.size
                val beforeCurrent = probe.endpoint.currentChecks.size
                val repeatedCall = async { probe.gateway.retireActionApproval(fixture.inputs.operationId) }
                repeat = repeatedCall
                val lookup = arrivalMilestone { lookupHold.entered.await() }

                probe.assertAuthorizedLookup(lookup, handoff, beforeCurrent)
                assertEquals(pending, lookup.durable)
                assertFalse(primaryHold.reply.isCompleted)
                assertFalse(primary.isCompleted)
                assertFalse(repeatedCall.isCompleted)
                assertEquals(beforeLookups + 1, probe.endpoint.lookups.size)
                probe.assertDispatches(1)

                lookupHold.reply.complete(probe.historicalStatus())
                val observed = assertIs<LocalActionApprovalResult.Available>(arrivalMilestone { repeatedCall.await() }).operation
                assertEquals(LocalActionApprovalPhase.RETIRE_UNRESOLVED, observed.actionApproval?.phase)
                assertFalse(observed.submitAllowed)
                assertEquals(pending, fixture.latest())
                assertFalse(primary.isCompleted)
                probe.assertDispatches(1)
                fixture.assertOriginal(pending)
                fixture.assertNoBusinessOrIssuance()

                primaryHold.reply.complete(LocalActionApprovalRetireResult.Unavailable)
                arrivalMilestone { primary.await() }
                assertEquals(pending, fixture.latest())
                fixture.reopen()
                assertEquals(pending, fixture.latest())
                assertEquals(beforeLookups + 1, probe.endpoint.lookups.size)
                probe.assertDispatches(1)
                fixture.assertNoBusinessOrIssuance()
            } finally {
                withContext(NonCancellable) {
                    probe.releaseHolds()
                    arrivalMilestone { primary.join() }
                    repeat?.let { arrivalMilestone { it.join() } }
                }
            }
        }
    }

    @Test
    fun arrna03GatewayTerminalLookupSurvivesLaterExactNegativeOrGenericFailure() = runTest(timeout = 3.minutes) {
        for (kind in HeldArrivalTerminalKind.values()) for (late in HeldArrivalLateReply.values()) {
            withArrivalRetirementNoAdmissionFixture { fixture ->
                val probe = HeldArrivalLookupProbe(fixture)
                val primaryHold = probe.holdRetirement()
                val primary = async { probe.gateway.retireActionApproval(fixture.inputs.operationId) }
                var repeat: Deferred<LocalActionApprovalResult>? = null
                try {
                    val handoff = arrivalMilestone { primaryHold.entered.await() }
                    val pending = assertHeldOriginalInvocation(fixture, handoff)
                    val terminal = controlledArrivalTerminal(fixture, kind)
                    val lookupHold = probe.endpoint.holdLookup()
                    val beforeLookups = probe.endpoint.lookups.size
                    val beforeCurrent = probe.endpoint.currentChecks.size
                    val repeatedCall = async { probe.gateway.retireActionApproval(fixture.inputs.operationId) }
                    repeat = repeatedCall
                    val lookup = arrivalMilestone { lookupHold.entered.await() }
                    probe.assertAuthorizedLookup(lookup, handoff, beforeCurrent)
                    assertEquals(pending, lookup.durable)
                    assertFalse(primaryHold.reply.isCompleted)

                    lookupHold.reply.complete(terminal.status)
                    val observed = assertIs<LocalActionApprovalResult.Available>(arrivalMilestone { repeatedCall.await() }).operation
                    assertEquals(terminal.phase, observed.actionApproval?.phase)
                    assertFalse(observed.submitAllowed)
                    assertTerminalOriginalPreserved(fixture, pending, fixture.latest(), terminal)
                    assertFalse(primary.isCompleted)
                    probe.assertDispatches(1)

                    primaryHold.reply.complete(late.reply(handoff))
                    arrivalMilestone { primary.await() }
                    val retained = fixture.latest()
                    assertTerminalOriginalPreserved(fixture, pending, retained, terminal)
                    fixture.reopen()
                    assertEquals(retained, fixture.latest())
                    assertEquals(beforeLookups + 1, probe.endpoint.lookups.size)
                    probe.assertDispatches(1)
                    fixture.assertNoBusinessOrIssuance()
                } finally {
                    withContext(NonCancellable) {
                        probe.releaseHolds()
                        arrivalMilestone { primary.join() }
                        repeat?.let { arrivalMilestone { it.join() } }
                    }
                }
            }
        }
    }

    @Test
    fun arrna01And08ControllerHeldTapUsesOnePendingLookupWithoutReplacingPrimary() = runTest(timeout = 3.minutes) {
        for (firstTap in HeldArrivalRepeatTap.values()) {
            withArrivalRetirementNoAdmissionFixture { fixture ->
                val probe = HeldArrivalLookupProbe(fixture)
                withHeldArrivalController(fixture, probe) { ui ->
                    val operation = fixture.inputs.operationId
                    ui.review(operation)
                    val primaryHold = probe.holdRetirement()
                    ui.controller.retryActionApprovalRetirement(ui.token())
                    val handoff = arrivalMilestone { primaryHold.entered.await() }
                    val pending = assertHeldOriginalInvocation(fixture, handoff)
                    val lookupHold = probe.endpoint.holdLookup()
                    val beforeUiLookups = ui.audit.lookups.size
                    val beforeEndpointLookups = probe.endpoint.lookups.size
                    val beforeCurrent = probe.endpoint.currentChecks.size
                    firstTap.perform(ui, operation)
                    val lookup = arrivalMilestone { lookupHold.entered.await() }
                    probe.assertAuthorizedLookup(lookup, handoff, beforeCurrent)
                    assertEquals(pending, lookup.durable)

                    // Only the controller's bounded pending-read fence owns this burst oracle.
                    repeat(3) { HeldArrivalRepeatTap.values().forEach { it.perform(ui, operation) } }
                    runCurrent()
                    assertEquals(beforeUiLookups + 1, ui.audit.lookups.size)
                    assertEquals(beforeEndpointLookups + 1, probe.endpoint.lookups.size)
                    assertEquals(listOf(operation), ui.audit.retirements)
                    assertEquals(0, ui.audit.retirementsReturned.value)
                    assertEquals(LocalWorkBusy.ACTION_APPROVAL_RETIRE, ui.controller.state.value.busy)
                    assertTrue(ui.controller.state.value.actionApprovalLookupWorking)
                    ui.assertSubmitBlocked(operation)
                    assertFalse(primaryHold.reply.isCompleted)
                    probe.assertDispatches(1)

                    lookupHold.reply.complete(probe.historicalStatus())
                    ui.awaitHeldLookupFinished(beforeUiLookups + 1)
                    runCurrent()
                    assertEquals(beforeUiLookups + 1, ui.audit.lookups.size)
                    assertEquals(beforeEndpointLookups + 1, probe.endpoint.lookups.size)
                    assertEquals(0, ui.audit.retirementsReturned.value)
                    assertEquals(LocalWorkBusy.ACTION_APPROVAL_RETIRE, ui.controller.state.value.busy)
                    assertEquals(LocalActionApprovalPhase.RETIRE_UNRESOLVED,
                        ui.controller.state.value.actionApproval?.approval?.phase)
                    assertTrue(assertNotNull(ui.controller.state.value.actionApproval).lookupRequired)
                    assertFalse(assertNotNull(ui.controller.state.value.actionApproval).canRetryRetirement)
                    ui.assertSubmitBlocked(operation)
                    assertEquals(pending, fixture.latest())
                    fixture.assertOriginal(pending)

                    primaryHold.reply.complete(HeldArrivalLateReply.EXACT_NEGATIVE.reply(handoff))
                    ui.awaitPrimaryFinished(1)
                    val completed = fixture.latest()
                    assertEquals(LocalActionApprovalPhase.RETIRE_RETRY_REQUIRED, completed.actionApproval?.localPhase)
                    assertEquals(handoff.attempt.attemptId, completed.actionApproval?.retirementAttempt?.attemptId)
                    assertEquals(listOf(assertNotNull(fixture.i0.actionApproval?.retirementAttempt)),
                        completed.actionApproval?.completedRetirementAttempts)
                    fixture.assertOriginal(completed)
                    ui.assertSubmitBlocked(operation)
                    runCurrent()
                    assertEquals(beforeUiLookups + 1, ui.audit.lookups.size)
                    assertEquals(listOf(operation), ui.audit.retirements)
                    probe.assertDispatches(1)
                    ui.assertNoBusinessOrCeremony()
                }
            }
        }
    }

    @Test
    fun arrna03And08ControllerTerminalLookupSurvivesLaterPrimaryNegativeOrFailure() = runTest(timeout = 3.minutes) {
        for (kind in HeldArrivalTerminalKind.values()) for (late in HeldArrivalLateReply.values()) {
            withArrivalRetirementNoAdmissionFixture { fixture ->
                val probe = HeldArrivalLookupProbe(fixture)
                withHeldArrivalController(fixture, probe) { ui ->
                    val operation = fixture.inputs.operationId
                    ui.review(operation)
                    val primaryHold = probe.holdRetirement()
                    ui.controller.retryActionApprovalRetirement(ui.token())
                    val handoff = arrivalMilestone { primaryHold.entered.await() }
                    val pending = assertHeldOriginalInvocation(fixture, handoff)
                    val terminal = controlledArrivalTerminal(fixture, kind)
                    val lookupHold = probe.endpoint.holdLookup()
                    val beforeLookups = ui.audit.lookups.size
                    val beforeCurrent = probe.endpoint.currentChecks.size
                    ui.controller.retryActionApprovalRetirement(ui.token())
                    val lookup = arrivalMilestone { lookupHold.entered.await() }
                    probe.assertAuthorizedLookup(lookup, handoff, beforeCurrent)
                    assertEquals(pending, lookup.durable)
                    assertFalse(primaryHold.reply.isCompleted)

                    lookupHold.reply.complete(terminal.status)
                    ui.awaitHeldLookupFinished(beforeLookups + 1)
                    val terminalCut = fixture.latest()
                    assertTerminalOriginalPreserved(fixture, pending, terminalCut, terminal)
                    ui.assertApprovalMatches(terminalCut)
                    assertFalse(assertNotNull(ui.controller.state.value.actionApproval).lookupRequired)
                    assertEquals(LocalWorkBusy.ACTION_APPROVAL_RETIRE, ui.controller.state.value.busy)
                    assertEquals(0, ui.audit.retirementsReturned.value)
                    ui.assertSubmitBlocked(operation)

                    primaryHold.reply.complete(late.reply(handoff))
                    ui.awaitPrimaryFinished(1)
                    val retained = fixture.latest()
                    assertTerminalOriginalPreserved(fixture, pending, retained, terminal)
                    ui.assertApprovalMatches(retained)
                    assertFalse(assertNotNull(ui.controller.state.value.actionApproval).lookupRequired)
                    assertFalse(assertNotNull(ui.controller.state.value.actionApproval).canRetryRetirement)
                    ui.assertSubmitBlocked(operation)
                    fixture.reopen()
                    assertEquals(retained, fixture.latest())
                    runCurrent()
                    assertEquals(beforeLookups + 1, ui.audit.lookups.size)
                    assertEquals(listOf(operation), ui.audit.retirements)
                    probe.assertDispatches(1)
                    ui.assertNoBusinessOrCeremony()
                }
            }
        }
    }

    @Test
    fun arrna03And08DelayedPendingLookupCannotRegressNewerNegativeOrTerminalUi() = runTest(timeout = 3.minutes) {
        for (newer in listOf("exact negative", "consumed", "retired")) {
            withArrivalRetirementNoAdmissionFixture { fixture ->
                val probe = HeldArrivalLookupProbe(fixture)
                withHeldArrivalController(fixture, probe) { ui ->
                    val operation = fixture.inputs.operationId
                    ui.review(operation)
                    val primaryHold = probe.holdRetirement()
                    ui.controller.retryActionApprovalRetirement(ui.token())
                    val handoff = arrivalMilestone { primaryHold.entered.await() }
                    val pending = assertHeldOriginalInvocation(fixture, handoff)
                    val lookupHold = probe.endpoint.holdLookup()
                    val delivery = ui.audit.holdLookupDelivery()
                    val beforeLookups = ui.audit.lookups.size
                    val beforeCurrent = probe.endpoint.currentChecks.size
                    ui.controller.lookupActionApproval(operation, ui.token())
                    val lookup = arrivalMilestone { lookupHold.entered.await() }
                    probe.assertAuthorizedLookup(lookup, handoff, beforeCurrent)
                    lookupHold.reply.complete(probe.historicalStatus())
                    val oldResult = assertIs<LocalActionApprovalResult.Available>(arrivalMilestone { delivery.entered.await() })
                    assertEquals(LocalActionApprovalPhase.RETIRE_UNRESOLVED, oldResult.operation.actionApproval?.phase)
                    assertFalse(oldResult.operation.submitAllowed)
                    assertEquals(pending, fixture.latest())
                    assertFalse(primaryHold.reply.isCompleted)

                    val terminal = when (newer) {
                        "consumed" -> controlledArrivalTerminal(fixture, HeldArrivalTerminalKind.CONSUMED)
                        "retired" -> controlledArrivalTerminal(fixture, HeldArrivalTerminalKind.RETIRED)
                        else -> null
                    }
                    primaryHold.reply.complete(terminal?.primaryReply ?: HeldArrivalLateReply.EXACT_NEGATIVE.reply(handoff))
                    ui.awaitPrimaryFinished(1)
                    val newerCut = fixture.latest()
                    if (terminal == null) {
                        assertEquals(LocalActionApprovalPhase.RETIRE_RETRY_REQUIRED, newerCut.actionApproval?.localPhase)
                        fixture.assertOriginal(newerCut)
                    } else assertTerminalOriginalPreserved(fixture, pending, newerCut, terminal)
                    ui.assertApprovalMatches(newerCut)
                    assertFalse(assertNotNull(ui.controller.state.value.actionApproval).lookupRequired)

                    // Only UI delivery is delayed; the real gateway already finished this old read.
                    delivery.release.complete(Unit)
                    arrivalMilestone { delivery.released.await() }
                    ui.audit.awaitLookupsReturned(beforeLookups + 1)
                    runCurrent()
                    assertEquals(newerCut, fixture.latest())
                    ui.assertApprovalMatches(newerCut)
                    assertFalse(assertNotNull(ui.controller.state.value.actionApproval).lookupRequired)
                    assertEquals(terminal == null, assertNotNull(ui.controller.state.value.actionApproval).canRetryRetirement)
                    assertFalse(ui.controller.state.value.actionApprovalLookupWorking)
                    ui.assertSubmitBlocked(operation)
                    fixture.reopen()
                    assertEquals(newerCut, fixture.latest())
                    assertEquals(beforeLookups + 1, ui.audit.lookups.size)
                    assertEquals(listOf(operation), ui.audit.retirements)
                    probe.assertDispatches(1)
                    ui.assertNoBusinessOrCeremony()
                }
            }
        }
    }

    @Test
    fun arrna08AuxiliaryDeliveryIsLifecycleFencedAndOldPrimaryCannotCancelNewEntryLookup() = runTest(timeout = 3.minutes) {
        withArrivalRetirementNoAdmissionFixture { fixture ->
            val probe = HeldArrivalLookupProbe(fixture)
            withHeldArrivalController(fixture, probe) { ui ->
                val operation = fixture.inputs.operationId
                ui.review(operation)
                val primaryHold = probe.holdRetirement()
                ui.controller.retryActionApprovalRetirement(ui.token())
                val handoff = arrivalMilestone { primaryHold.entered.await() }
                val pending = assertHeldOriginalInvocation(fixture, handoff)
                val lookupHold = probe.endpoint.holdLookup()
                val delivery = ui.audit.holdLookupDelivery()
                val beforeLookups = ui.audit.lookups.size
                val beforeCurrent = probe.endpoint.currentChecks.size
                ui.controller.lookupActionApproval(operation, ui.token())
                val lookup = arrivalMilestone { lookupHold.entered.await() }
                probe.assertAuthorizedLookup(lookup, handoff, beforeCurrent)
                assertEquals(pending, lookup.durable)
                lookupHold.reply.complete(probe.historicalStatus())
                val oldResult = assertIs<LocalActionApprovalResult.Available>(arrivalMilestone { delivery.entered.await() })
                assertEquals(LocalActionApprovalPhase.RETIRE_UNRESOLVED, oldResult.operation.actionApproval?.phase)
                assertFalse(primaryHold.reply.isCompleted)

                ui.session.lifecycle.value = LocalSessionLifecycle.REAUTHENTICATION_REQUIRED
                arrivalMilestone { ui.controller.state.first { it.actionApproval == null && !it.actionApprovalLookupWorking } }
                ui.audit.awaitRetirementsReturned(1)
                delivery.release.complete(Unit)
                arrivalMilestone { delivery.released.await() }
                ui.audit.awaitLookupsReturned(beforeLookups + 1)
                runCurrent()
                assertNull(ui.controller.state.value.actionApproval)
                assertFalse(ui.controller.actionApprovalDisplayCurrent(ui.token()))
                ui.controller.retireActionApproval(ui.token())
                ui.controller.retryActionApprovalRetirement(ui.token())
                ui.controller.lookupActionApproval(operation, ui.token())
                runCurrent()
                assertEquals(beforeLookups + 1, ui.audit.lookups.size)
                assertEquals(listOf(operation), ui.audit.retirements)
                assertEquals(pending, fixture.latest())
                fixture.assertOriginal(pending)
                probe.assertDispatches(1)
                ui.assertNoBusinessOrCeremony()
            }
        }

        withArrivalRetirementNoAdmissionFixture { fixture ->
            val probe = HeldArrivalLookupProbe(fixture)
            withHeldArrivalController(fixture, probe) { ui ->
                val operationA = fixture.inputs.operationId
                ui.review(operationA)
                val oldDelivery = ui.audit.holdRetirementDelivery()
                val heldA = probe.holdRetirement()
                ui.controller.retryActionApprovalRetirement(ui.token())
                val handoffA = arrivalMilestone { heldA.entered.await() }
                assertHeldOriginalInvocation(fixture, handoffA)
                heldA.reply.complete(HeldArrivalLateReply.EXACT_NEGATIVE.reply(handoffA))
                assertIs<LocalActionApprovalResult.Available>(arrivalMilestone { oldDelivery.entered.await() })
                val completedA = fixture.latest()
                assertEquals(LocalActionApprovalPhase.RETIRE_RETRY_REQUIRED, completedA.actionApproval?.localPhase)
                assertEquals(handoffA.attempt.attemptId, completedA.actionApproval?.retirementAttempt?.attemptId)
                assertNotNull(completedA.actionApproval?.retirementAttempt?.noAdmissionReason)
                assertEquals(listOf(assertNotNull(fixture.i0.actionApproval?.retirementAttempt)),
                    completedA.actionApproval?.completedRetirementAttempts)
                fixture.assertOriginal(completedA)

                // Reuse this SAME controller. The cancelled A delivery ignores cancellation;
                // its eventual finalizer must not cancel the new B job's separate lookup.
                val sameController = ui.controller
                sameController.deactivate()
                val inputsB = ArrivalRetirementNoAdmissionInputs("B")
                fixture.saveHistoricalAttached(inputsB)
                sameController.activate(LocalWorkflow.RECEIVING)
                ui.awaitIdle()
                ui.review(inputsB.operationId)
                assertSame(sameController, ui.controller)
                val heldB = probe.holdRetirement()
                sameController.retireActionApproval(ui.token())
                val handoffB = arrivalMilestone { heldB.entered.await() }
                assertEquals(12, inputsB.baseUnits)
                assertOriginalHeldRequest(inputsB.request, handoffB.request)
                assertEquals(handoffB.durable, fixture.journal.savedInvocations.last())
                val pendingB = assertNotNull(handoffB.durable.actionApproval)
                val i3 = assertNotNull(pendingB.retirementAttempt)
                assertEquals(LocalActionApprovalPhase.RETIRE_UNRESOLVED, pendingB.localPhase)
                assertNull(i3.noAdmissionReason)
                assertTrue(pendingB.completedRetirementAttempts.isEmpty())
                assertNotEquals(handoffA.attempt.attemptId, i3.attemptId)
                assertEquals(i3.boundAttempt(handoffB.durable), handoffB.attempt)
                fixture.assertOriginal(handoffB.durable, inputsB)
                val lookupB = probe.endpoint.holdLookup()
                val beforeLookups = ui.audit.lookups.size
                val beforeReturnedLookups = ui.audit.lookupsReturned.value
                val beforeCurrent = probe.endpoint.currentChecks.size
                sameController.lookupActionApproval(inputsB.operationId, ui.token())
                val enteredB = arrivalMilestone { lookupB.entered.await() }
                probe.assertAuthorizedLookup(enteredB, handoffB, beforeCurrent)
                assertEquals(handoffB.durable, enteredB.durable)
                assertTrue(sameController.state.value.actionApprovalLookupWorking)

                oldDelivery.release.complete(Unit)
                arrivalMilestone { oldDelivery.released.await() }
                ui.audit.awaitRetirementsReturned(1)
                runCurrent()
                assertEquals(LocalWorkBusy.ACTION_APPROVAL_RETIRE, sameController.state.value.busy)
                assertTrue(sameController.state.value.actionApprovalLookupWorking)
                assertEquals(beforeReturnedLookups, ui.audit.lookupsReturned.value)
                assertFalse(lookupB.reply.isCompleted)
                assertFalse(heldB.reply.isCompleted)
                assertEquals(inputsB.operationId, sameController.state.value.actionApproval?.operationId)
                assertEquals(handoffB.durable, fixture.host.find(inputsB.operationId))
                assertEquals(completedA, fixture.latest())

                lookupB.reply.complete(probe.historicalStatus(inputsB))
                ui.awaitHeldLookupFinished(beforeLookups + 1)
                assertEquals(LocalActionApprovalPhase.RETIRE_UNRESOLVED,
                    sameController.state.value.actionApproval?.approval?.phase)
                assertEquals(inputsB.operationId, sameController.state.value.actionApproval?.operationId)
                ui.assertSubmitBlocked(inputsB.operationId)
                heldB.reply.complete(LocalActionApprovalRetireResult.Unavailable)
                ui.awaitPrimaryFinished(2)
                assertEquals(handoffB.durable, fixture.host.find(inputsB.operationId))
                assertEquals(completedA, fixture.latest())
                fixture.assertOriginal(handoffB.durable, inputsB)
                fixture.assertOriginal(completedA)
                assertEquals(listOf(operationA, inputsB.operationId), ui.audit.retirements)
                assertEquals(beforeLookups + 1, ui.audit.lookups.size)
                probe.assertDispatches(2)
                ui.assertNoBusinessOrCeremony()
            }
        }
    }
}

/** Wall-time bounds: virtual scheduler advancement is not completion of encrypted-store IO. */
private suspend fun <T> arrivalMilestone(block: suspend () -> T): T = withContext(Dispatchers.Default) {
    withTimeout(30_000L) { block() }
}

private suspend fun assertHeldOriginalInvocation(fixture: ArrivalRetirementNoAdmissionFixture,
    handoff: ArrivalRetirementHandoff): StoredClientIntentV1 {
    val pending = fixture.latest()
    assertEquals(handoff.durable, pending)
    assertEquals(pending, fixture.journal.savedInvocations.last())
    val ceremony = assertNotNull(pending.actionApproval)
    val i0 = assertNotNull(fixture.i0.actionApproval?.retirementAttempt)
    val i1 = assertNotNull(ceremony.retirementAttempt)
    assertEquals(LocalActionApprovalPhase.RETIRE_UNRESOLVED, ceremony.localPhase)
    assertNotEquals(i0.attemptId, i1.attemptId)
    assertEquals(i0.sequence + 1, i1.sequence)
    assertNull(i1.noAdmissionReason)
    assertEquals(listOf(i0), ceremony.completedRetirementAttempts)
    assertEquals(i1.boundAttempt(pending), handoff.attempt)
    assertOriginalHeldRequest(fixture.inputs.request, handoff.request)
    assertEquals(fixture.inputs.approvalId, handoff.attempt.approvalId)
    assertEquals(fixture.inputs.confirmationDigest, handoff.attempt.confirmationDigest)
    assertEquals(sha256(fixture.inputs.request.payload.bytes()), handoff.attempt.originalPayloadDigest)
    assertEquals(24, fixture.inputs.baseUnits)
    fixture.assertOriginal(pending)
    return pending
}

private fun assertOriginalHeldRequest(expected: MutationRequest, actual: MutationRequest) {
    assertEquals(expected.operationId, actual.operationId)
    assertEquals(expected.command, actual.command)
    assertContentEquals(expected.payload.bytes(), actual.payload.bytes())
    assertEquals(expected.actionApprovalId, actual.actionApprovalId)
    assertNull(actual.actionApprovalId)
}

private enum class HeldArrivalTerminalKind { CONSUMED, RETIRED }

private class ControlledArrivalTerminal(val receipt: LocalActionApprovalReceipt,
    val retirement: LocalActionApprovalRetirementReceipt?) {
    val phase = if (retirement == null) LocalActionApprovalPhase.CONSUMED else LocalActionApprovalPhase.RETIRED
    val status: LocalActionApprovalStatus get() = retirement?.let { LocalActionApprovalStatus.Retired(receipt, it) }
        ?: LocalActionApprovalStatus.Recorded(receipt)
    val primaryReply: LocalActionApprovalRetireResult get() = retirement?.let { LocalActionApprovalRetireResult.Retired(receipt, it) }
        ?: LocalActionApprovalRetireResult.Consumed(receipt)
}

/** Exact original issuance plus explicitly supplied terminal metadata; no native producer. */
private fun controlledArrivalTerminal(fixture: ArrivalRetirementNoAdmissionFixture,
    kind: HeldArrivalTerminalKind): ControlledArrivalTerminal {
    val original = checkNotNull(fixture.endpoint.historicalReceipts[fixture.inputs.approvalId])
    if (kind == HeldArrivalTerminalKind.CONSUMED) return ControlledArrivalTerminal(LocalActionApprovalReceipt(
        original.approvalId, original.requestorId, original.approverId, original.requirement, original.confirmationDigest,
        original.issuedAt, original.expiresAt, fixture.inputs.operationId), null)
    return ControlledArrivalTerminal(original, LocalActionApprovalRetirementReceipt(
        ArrivalRetirementFrozenV2.id("controlled-client-terminal-retirement-A"), original.approvalId,
        fixture.inputs.operationId, original.requestorId, original.confirmationDigest,
        sha256("Controlled client terminal receipt; not authenticated native history".encodeToByteArray()),
        MonotonicMoment(original.issuedAt.bootId, 30)))
}

private suspend fun assertTerminalOriginalPreserved(fixture: ArrivalRetirementNoAdmissionFixture,
    pending: StoredClientIntentV1, actual: StoredClientIntentV1, terminal: ControlledArrivalTerminal) {
    // Nothing outside the approval presentation/status may change the exact original.
    assertEquals(pending.copy(actionApprovalId = actual.actionApprovalId, actionApproval = actual.actionApproval), actual)
    assertTrue(fixture.inputs.staged().sameIntent(actual))
    assertOriginalHeldRequest(fixture.inputs.request, actual.unapprovedRequest())
    assertEquals(fixture.inputs.record, LocalUnpricedArrivalCommandsV2.decodeRecordRequest(actual.unapprovedRequest().payload))
    val before = assertNotNull(pending.actionApproval)
    val after = assertNotNull(actual.actionApproval)
    assertEquals(before.rule, after.rule)
    assertEquals(before.preview, after.preview)
    assertEquals(before.approvalId, after.approvalId)
    assertEquals(before.retiredApprovals, after.retiredApprovals)
    assertEquals(before.refusal, after.refusal)
    val i1 = assertNotNull(before.retirementAttempt)
    val allAttempts = after.completedRetirementAttempts + listOfNotNull(after.retirementAttempt)
    assertEquals(before.completedRetirementAttempts, allAttempts.filter { it.attemptId != i1.attemptId })
    val retainedI1 = allAttempts.single { it.attemptId == i1.attemptId }
    // Terminal observation supplies no invocation disposition; a losing completion CAS cannot invent one.
    assertEquals(i1, retainedI1)
    assertEquals(terminal.phase, after.localPhase)
    val receipt = assertNotNull(after.receipt)
    assertEquals(assertNotNull(before.receipt).copy(consumedByOperationId = terminal.receipt.consumedByOperationId), receipt)
    assertEquals(terminal.receipt.approverId, receipt.approverId)
    assertEquals(terminal.receipt.issuedAt, receipt.issuedAt)
    assertEquals(terminal.receipt.expiresAt, receipt.expiresAt)
    if (terminal.retirement == null) {
        assertEquals(fixture.inputs.operationId, receipt.consumedByOperationId)
        assertEquals(fixture.inputs.approvalId, actual.actionApprovalId)
        assertNull(after.retirement)
    } else {
        assertNull(actual.actionApprovalId)
        assertNull(receipt.consumedByOperationId)
        assertEquals(StoredClientActionApprovalRetirementV1(terminal.retirement.retirementId,
            terminal.retirement.originalApprovalDigest, terminal.retirement.retiredAt), after.retirement)
    }
    val source = assertNotNull(assertIs<JournalRead.Value<StoredClientDocumentarySourceV1?>>(
        fixture.journal.documentarySource(fixture.inputs.documentId)).value)
    val bytes = source.copyOriginalBytes()
    try { assertContentEquals(fixture.inputs.originalBytes, bytes) } finally { bytes.fill(0) }
    assertEquals(LocalArrivalApprovalModeV2.SEPARATE, after.rule.approvalRoute.mode)
    assertNull(actual.recorded)
    assertFalse(actual.acknowledged)
}

private enum class HeldArrivalLateReply {
    EXACT_NEGATIVE, GENERIC_UNAVAILABLE;
    fun reply(handoff: ArrivalRetirementHandoff): LocalActionApprovalRetireResult =
        if (this == EXACT_NEGATIVE) LocalActionApprovalRetireResult.NotAdmitted(handoff.attempt,
            LocalActionApprovalNoAdmissionReason.QUEUE_FULL) else LocalActionApprovalRetireResult.Unavailable
}

private data class HeldArrivalLookupHandoff(val approvalId: DomainId, val confirmationDigest: PayloadDigest,
    val credentials: LocalGatewayCredentials, val durable: StoredClientIntentV1)

private class HeldArrivalApprovalLookup {
    val entered = CompletableDeferred<HeldArrivalLookupHandoff>()
    val reply = CompletableDeferred<LocalActionApprovalStatus>()
    suspend fun receive(handoff: HeldArrivalLookupHandoff): LocalActionApprovalStatus {
        check(entered.complete(handoff))
        return reply.await()
    }
}

/** Adds endpoint-entry holds and audits, delegating every other client method unchanged. */
private class HeldArrivalLookupEndpoint(private val fixture: ArrivalRetirementNoAdmissionFixture) :
    LocalMasterClient by fixture.endpoint, LocalMasterApprovalClient by fixture.endpoint {
    val currentChecks = mutableListOf<LocalGatewayCredentials>()
    val retirementCredentials = mutableListOf<Pair<MasterRetirementAttempt, LocalGatewayCredentials>>()
    val lookups = mutableListOf<HeldArrivalLookupHandoff>()
    private var nextLookup: HeldArrivalApprovalLookup? = null
    private val holds = mutableListOf<HeldArrivalApprovalLookup>()
    fun holdLookup() = HeldArrivalApprovalLookup().also {
        check(nextLookup == null)
        nextLookup = it
        holds += it
    }
    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult {
        val result = fixture.endpoint.current(credentials)
        if (result is LocalSessionViewResult.Current) currentChecks += credentials
        return result
    }
    override suspend fun lookupActionApproval(approvalId: DomainId, requestor: LocalGatewayCredentials,
        confirmationDigest: PayloadDigest): LocalActionApprovalStatus {
        val held = nextLookup.also { nextLookup = null }
        val historical = fixture.endpoint.lookupActionApproval(approvalId, requestor, confirmationDigest)
        val receipt = checkNotNull(fixture.endpoint.historicalReceipts[approvalId])
        val handoff = HeldArrivalLookupHandoff(approvalId, confirmationDigest, requestor,
            fixture.host.find(checkNotNull(receipt.requirement.binding?.operationId)))
        lookups += handoff
        return held?.receive(handoff) ?: historical
    }
    override suspend fun retireActionApprovalAttempt(attempt: MasterRetirementAttempt,
        requestor: LocalGatewayCredentials, request: MutationRequest): LocalActionApprovalRetireResult {
        retirementCredentials += attempt to requestor
        return fixture.endpoint.retireActionApprovalAttempt(attempt, requestor, request)
    }
    fun releaseHolds() { holds.forEach { it.reply.complete(LocalActionApprovalStatus.Unavailable) } }
}

private class HeldArrivalLookupProbe(private val fixture: ArrivalRetirementNoAdmissionFixture) {
    val endpoint = HeldArrivalLookupEndpoint(fixture)
    val gateway = LocalEndpointWorkflowGateway(endpoint, fixture.journal, fixture.credentials,
        LocalWorkflowInstallation(setOf(LocalUnpricedArrivalProfileV2.RECORD, LocalUnpricedArrivalProfileV2.QUALIFY,
            LocalUnpricedArrivalProfileV2.REJECT, LocalUnpricedArrivalProfileV2.DISPATCH), setOf(LocalUnpricedArrivalProfileV2.SOURCE)))
    private val retirementHolds = mutableListOf<ArrivalRetirementHold>()
    fun holdRetirement() = ArrivalRetirementHold().also {
        retirementHolds += it
        fixture.endpoint.onRetire = it::receive
    }
    fun historicalStatus(inputs: ArrivalRetirementNoAdmissionInputs = fixture.inputs): LocalActionApprovalStatus =
        LocalActionApprovalStatus.Recorded(checkNotNull(fixture.endpoint.historicalReceipts[inputs.approvalId]))
    fun assertAuthorizedLookup(lookup: HeldArrivalLookupHandoff, retirement: ArrivalRetirementHandoff,
        previousCurrentChecks: Int) {
        assertEquals(retirement.attempt.approvalId, lookup.approvalId)
        assertEquals(retirement.attempt.confirmationDigest, lookup.confirmationDigest)
        assertTrue(endpoint.currentChecks.size > previousCurrentChecks)
        assertTrue(endpoint.currentChecks.drop(previousCurrentChecks).any { it === lookup.credentials })
        val primaryCredentials = endpoint.retirementCredentials.single { it.first === retirement.attempt }.second
        assertNotSame(primaryCredentials, lookup.credentials)
        val primaryLease = fixture.credentials.issued.single { it.credentials === primaryCredentials }
        val lookupLease = fixture.credentials.issued.single { it.credentials === lookup.credentials }
        assertNotSame(primaryLease, lookupLease)
    }
    fun assertDispatches(count: Int) {
        assertEquals(fixture.baselineRetirements + count, fixture.endpoint.retirements.size)
        assertEquals(count, endpoint.retirementCredentials.size)
    }
    fun releaseHolds() {
        endpoint.releaseHolds()
        retirementHolds.forEach { it.reply.complete(LocalActionApprovalRetireResult.Unavailable) }
    }
}

/** Noncooperative UI-only delivery; the actual gateway has already returned its real result. */
private class HeldArrivalUiDelivery {
    val entered = CompletableDeferred<LocalActionApprovalResult>()
    val release = CompletableDeferred<Unit>()
    val released = CompletableDeferred<Unit>()
    suspend fun deliver(result: LocalActionApprovalResult): LocalActionApprovalResult = withContext(NonCancellable) {
        check(entered.complete(result))
        release.await()
        released.complete(Unit)
        result
    }
}

private class HeldArrivalControllerAudit(private val actual: LocalWorkflowGateway) : LocalWorkflowGateway by actual {
    val retirements = mutableListOf<OperationId>()
    val lookups = mutableListOf<OperationId>()
    val retirementsReturned = MutableStateFlow(0)
    val lookupsReturned = MutableStateFlow(0)
    private val businessOrCeremony = mutableListOf<String>()
    private var nextLookupDelivery: HeldArrivalUiDelivery? = null
    private var nextRetirementDelivery: HeldArrivalUiDelivery? = null
    private val deliveries = mutableListOf<HeldArrivalUiDelivery>()
    fun holdLookupDelivery() = HeldArrivalUiDelivery().also {
        check(nextLookupDelivery == null)
        nextLookupDelivery = it
        deliveries += it
    }
    fun holdRetirementDelivery() = HeldArrivalUiDelivery().also {
        check(nextRetirementDelivery == null)
        nextRetirementDelivery = it
        deliveries += it
    }
    override suspend fun retireActionApproval(operationId: OperationId): LocalActionApprovalResult {
        retirements += operationId
        val held = nextRetirementDelivery.also { nextRetirementDelivery = null }
        try {
            val result = actual.retireActionApproval(operationId)
            return held?.deliver(result) ?: result
        } finally { retirementsReturned.value += 1 }
    }
    override suspend fun lookupActionApproval(operationId: OperationId): LocalActionApprovalResult {
        lookups += operationId
        val held = nextLookupDelivery.also { nextLookupDelivery = null }
        try {
            val result = actual.lookupActionApproval(operationId)
            return held?.deliver(result) ?: result
        } finally { lookupsReturned.value += 1 }
    }
    suspend fun awaitRetirementsReturned(count: Int) { arrivalMilestone { retirementsReturned.first { it >= count } } }
    suspend fun awaitLookupsReturned(count: Int) { arrivalMilestone { lookupsReturned.first { it >= count } } }
    override suspend fun submit(operationId: OperationId): LocalOperationResult {
        businessOrCeremony += "submit"
        return actual.submit(operationId)
    }
    override suspend fun stage(intent: LocalCommandIntent): LocalStageResult {
        businessOrCeremony += "stage"
        return actual.stage(intent)
    }
    override suspend fun previewActionApproval(operationId: OperationId): LocalActionApprovalResult {
        businessOrCeremony += "preview"
        return actual.previewActionApproval(operationId)
    }
    override suspend fun issueActionApproval(operationId: OperationId, confirmationDigest: PayloadDigest,
        approverLogin: String, approverPassword: CharArray): LocalActionApprovalResult {
        businessOrCeremony += "issue"
        return actual.issueActionApproval(operationId, confirmationDigest, approverLogin, approverPassword)
    }
    override suspend fun beginActionApprovalReplacement(operationId: OperationId): LocalActionApprovalResult {
        businessOrCeremony += "replacement"
        return actual.beginActionApprovalReplacement(operationId)
    }
    fun assertNoBusinessOrCeremony() { assertTrue(businessOrCeremony.isEmpty()) }
    fun releaseDeliveries() { deliveries.forEach { it.release.complete(Unit) } }
}

private class HeldArrivalControllerSession(fixture: ArrivalRetirementNoAdmissionFixture,
    override val workflows: LocalWorkflowGateway) : BranchUiSession {
    override val display = SessionDisplayContext(fixture.inputs.requester, fixture.inputs.scope.companyId,
        fixture.inputs.scope.branchId, "Synthetic original requester", "Synthetic company", "Synthetic branch", setOf(AppArea.STOCK))
    override val lifecycle = MutableStateFlow(LocalSessionLifecycle.ACTIVE)
    override val attachmentUpdates = MutableStateFlow<LocalSessionAttachmentKey?>(LocalSessionAttachmentKey(fixture.inputs.requester, 1))
    private var active = true
    override fun isCurrent() = active
    override suspend fun current(): LocalSessionCheckResult = if (active) LocalSessionCheckResult.Current(display, attachmentKey)
        else LocalSessionCheckResult.Unavailable(LocalServiceProblem.SESSION_ENDED, attachmentKey)
    override suspend fun mayRestore(route: AppRoute) = active && RouteRegistry.area(route) in display.allowedAreas
    override fun close() { active = false; lifecycle.value = LocalSessionLifecycle.LOCKED; attachmentUpdates.value = null }
}

private class HeldArrivalController(val runtime: UkkeraRuntime, val session: HeldArrivalControllerSession,
    val audit: HeldArrivalControllerAudit, private val fixture: ArrivalRetirementNoAdmissionFixture,
    scope: TestScope) {
    val controller = LocalWorkflowController(runtime, session, runtime.navigator.token(), scope)
    fun token(): NavigationToken = runtime.navigator.token()
    suspend fun awaitIdle() { arrivalMilestone { controller.state.first { !it.working } } }
    suspend fun review(operationId: OperationId) {
        controller.reviewActionApproval(operationId, token())
        awaitIdle()
        assertEquals(operationId, controller.state.value.actionApproval?.operationId)
    }
    suspend fun awaitPrimaryFinished(count: Int) { audit.awaitRetirementsReturned(count); awaitIdle() }
    suspend fun awaitHeldLookupFinished(count: Int) {
        audit.awaitLookupsReturned(count)
        arrivalMilestone { controller.state.first { !it.actionApprovalLookupWorking && it.busy == LocalWorkBusy.ACTION_APPROVAL_RETIRE } }
    }
    fun assertSubmitBlocked(operationId: OperationId) {
        val pending = assertIs<LocalOperationResult.Pending>(controller.state.value.operation).operation
        assertEquals(operationId, pending.operationId)
        assertFalse(pending.submitAllowed)
        assertFalse(controller.state.value.pending.single { it.operationId == operationId }.submitAllowed)
    }
    fun assertApprovalMatches(entry: StoredClientIntentV1) {
        val stored = assertNotNull(entry.actionApproval)
        val presentation = assertNotNull(controller.state.value.actionApproval)
        assertEquals(entry.operationId, presentation.operationId)
        val approval = assertNotNull(presentation.approval)
        assertEquals(stored.localPhase, approval.phase)
        assertEquals(stored.rule, StoredClientArrivalApprovalRuleV1.capture(approval.rule))
        assertEquals(stored.approvalId, approval.approvalId)
        assertEquals(stored.receipt?.approverId, approval.approverId)
        assertEquals(stored.receipt?.issuedAt, approval.issuedAt)
        assertEquals(stored.receipt?.expiresAt, approval.expiresAt)
        assertEquals(stored.retirement?.ui(), approval.retirement)
        assertEquals(stored.retiredApprovals.map { it.ui() }, approval.retiredApprovals)
        assertEquals(stored.retirementAttempt?.ui(), approval.retirementAttempt)
        assertEquals(stored.completedRetirementAttempts.map { it.ui() }, approval.completedRetirementAttempts)
        val preview = assertNotNull(stored.preview)
        val review = assertNotNull(approval.review)
        assertEquals(entry.operationId, review.operationId)
        assertEquals(entry.localAction, review.action)
        assertEquals(preview.requestorId, review.requestorId)
        assertEquals(preview.requestorPolicyRevision, review.requestorPolicyRevision)
        assertEquals(preview.requirement(), review.requirement)
        assertEquals(preview.confirmationDigest, review.confirmationDigest)
    }
    fun assertNoBusinessOrCeremony() { audit.assertNoBusinessOrCeremony(); fixture.assertNoBusinessOrIssuance() }
}

private enum class HeldArrivalRepeatTap {
    RETIRE, RETRY, LOOKUP;
    fun perform(ui: HeldArrivalController, operationId: OperationId) {
        when (this) {
            RETIRE -> ui.controller.retireActionApproval(ui.token())
            RETRY -> ui.controller.retryActionApprovalRetirement(ui.token())
            LOOKUP -> ui.controller.lookupActionApproval(operationId, ui.token())
        }
    }
}

private suspend fun TestScope.withHeldArrivalController(fixture: ArrivalRetirementNoAdmissionFixture,
    probe: HeldArrivalLookupProbe, block: suspend (HeldArrivalController) -> Unit) {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val audit = HeldArrivalControllerAudit(probe.gateway)
    val session = HeldArrivalControllerSession(fixture, audit)
    val runtime = UkkeraRuntime(AppDependencies(branchGateway = object : BranchUiGateway {
        override val configured = true
        override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> = SignInResult.Authenticated(session)
    }, platform = PlatformCapabilities(PlatformFamily.DESKTOP)))
    var ui: HeldArrivalController? = null
    try {
        runtime.openSignIn(false, runtime.navigator.token())
        runtime.signIn("synthetic-requester", charArrayOf('x'), runtime.navigator.token())
        ui = HeldArrivalController(runtime, session, audit, fixture, this)
        ui.controller.activate(LocalWorkflow.RECEIVING)
        ui.awaitIdle()
        block(ui)
    } finally {
        try {
            withContext(NonCancellable) {
                probe.releaseHolds()
                audit.releaseDeliveries()
                try {
                    audit.awaitRetirementsReturned(audit.retirements.size)
                    audit.awaitLookupsReturned(audit.lookups.size)
                } finally {
                    ui?.controller?.close()
                    runtime.close()
                }
            }
            runCurrent()
        } finally { Dispatchers.resetMain() }
    }
}
