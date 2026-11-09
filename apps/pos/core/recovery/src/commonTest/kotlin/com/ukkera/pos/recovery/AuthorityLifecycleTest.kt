package com.ukkera.pos.recovery

import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class AuthorityLifecycleTest {
    private val f = RecoveryFixtures
    private val verifier = ModelEvidenceVerifier()
    private val machine = AuthorityLifecycleMachine(verifier, ModelPhysicalGate())

    private fun initial(): AuthorityLifecycleState = AuthorityLifecycleState.initiallyProvisioned(f.branch, f.old)

    private fun writer(state: AuthorityLifecycleState, authority: AuthorityIdentity): AuthorityWriterDecision =
        AuthorityWritePolicy(verifier).evaluate(state, authority, f.id(900), f.evidence(901))

    private fun sealed(): AuthorityLifecycleState {
        var state = initial()
        state = machine.prepare(state, state.revision, f.prepare()).state
        state = machine.drain(state, state.revision, f.drain()).state
        return machine.seal(state, state.revision, f.seal()).state
    }

    @Test
    fun plannedLifecycleAllowsOnlyOneBoundWriterAndNoPostSealAbort() {
        var state = initial()
        assertEquals(AuthorityWriterDecision.ACTIVE, writer(state, f.old))
        state = machine.prepare(state, state.revision, f.prepare()).state
        assertEquals(HandoverPhase.PREPARING, state.handovers.single().phase)
        assertEquals(AuthorityWriterDecision.ACTIVE, writer(state, f.old))
        assertEquals(AuthorityWriterDecision.STALE_OR_RETIRED_AUTHORITY, writer(state, f.target))
        state = machine.drain(state, state.revision, f.drain()).state
        assertEquals(AuthorityWriterDecision.DRAINING, writer(state, f.old))
        state = machine.seal(state, state.revision, f.seal()).state
        assertNull(state.activeAuthority)
        assertEquals(AuthorityWriterDecision.NO_ACTIVE_AUTHORITY, writer(state, f.old))
        assertEquals(AuthorityWriterDecision.NO_ACTIVE_AUTHORITY, writer(state, f.target))
        assertEquals(RecoveryRefusal.POST_SEAL_ABORT_FORBIDDEN, machine.abort(state, state.revision, f.abort()).refusal)
        state = machine.activate(state, state.revision, f.activate()).state
        assertEquals(f.target, state.activeAuthority)
        assertEquals(AuthorityWriterDecision.ACTIVE, writer(state, f.target))
        assertEquals(AuthorityWriterDecision.STALE_OR_RETIRED_AUTHORITY, writer(state, f.old))
        assertEquals(RecoveryRefusal.POST_SEAL_ABORT_FORBIDDEN, machine.abort(state, state.revision, f.abort()).refusal)
        state = machine.complete(state, state.revision, f.complete()).state
        assertEquals(HandoverPhase.COMPLETED, state.handovers.single().phase)
        assertEquals(RecoveryRefusal.POST_SEAL_ABORT_FORBIDDEN, machine.abort(state, state.revision, f.abort()).refusal)
        assertEquals(setOf(f.old.assignmentId), state.retiredAssignmentIds)
        assertEquals(f.approver, state.handovers.single().preparation.approvingActorId)
    }

    @Test
    fun abortBeforeSealRestoresOldAdmissionWithoutPromotingTarget() {
        for (draining in listOf(false, true)) {
            var state = initial()
            state = machine.prepare(state, state.revision, f.prepare()).state
            if (draining) state = machine.drain(state, state.revision, f.drain()).state
            val beforeAbort = state.revision
            state = machine.abort(state, state.revision, f.abort()).state
            assertEquals(HandoverPhase.ABORTED, state.handovers.single().phase)
            assertEquals(f.old, state.activeAuthority)
            assertEquals(AuthorityWriterDecision.ACTIVE, writer(state, f.old))
            assertEquals(AuthorityWriterDecision.STALE_OR_RETIRED_AUTHORITY, writer(state, f.target))
            assertEquals(TransitionDisposition.IDEMPOTENT, machine.abort(state, beforeAbort, f.abort()).disposition)
            assertEquals(RecoveryRefusal.INVALID_STATE, machine.seal(state, state.revision, f.seal()).refusal)
        }
    }

    @Test
    fun lostReplyAtEveryPlannedTransitionIsResolvedFromPersistedStateNotNewPromotion() {
        val operations: List<(AuthorityLifecycleState, Revision) -> RecoveryTransition<AuthorityLifecycleState>> = listOf(
            { state, rev -> machine.prepare(state, rev, f.prepare()) },
            { state, rev -> machine.drain(state, rev, f.drain()) },
            { state, rev -> machine.seal(state, rev, f.seal()) },
            { state, rev -> machine.activate(state, rev, f.activate()) },
            { state, rev -> machine.complete(state, rev, f.complete()) },
        )
        var persisted = initial()
        for (operation in operations) {
            val oldRevision = persisted.revision
            val proposed = operation(persisted, oldRevision)
            assertEquals(TransitionDisposition.APPLIED, proposed.disposition)
            // Crash before durable CAS: proposed state is discarded, not called committed.
            val afterPrecommitCrash = Json.decodeFromString<AuthorityLifecycleState>(Json.encodeToString(persisted))
            assertEquals(proposed, operation(afterPrecommitCrash, oldRevision))
            // Crash after durable CAS / lost response: reload then retry the SAME command.
            persisted = Json.decodeFromString(Json.encodeToString(proposed.state))
            val retried = operation(persisted, oldRevision)
            assertEquals(TransitionDisposition.IDEMPOTENT, retried.disposition)
            assertEquals(persisted, retried.state)
        }
        assertEquals(Revision(5), persisted.revision)
        assertEquals(1, persisted.handovers.size)
        assertEquals(Revision(2), persisted.activeAuthority?.epoch)
    }

    @Test
    fun targetPredecessorIncidentAndFinalCheckpointCannotChangeUnderAHandoverId() {
        val prepared = machine.prepare(initial(), Revision(0), f.prepare()).state
        for (binding in listOf(
            f.binding.copy(target = f.otherTarget),
            f.binding.copy(incidentId = f.id(999)),
            f.binding.copy(predecessor = f.old.copy(assignmentId = f.id(999))),
            f.binding.copy(branchId = f.id(999)),
        )) {
            assertEquals(RecoveryRefusal.IDENTITY_CONFLICT,
                machine.prepare(prepared, prepared.revision, f.prepare(binding)).refusal)
            assertEquals(RecoveryRefusal.STALE_TARGET,
                machine.drain(prepared, prepared.revision, f.drain(binding)).refusal)
        }
        val sealed = sealed()
        assertEquals(RecoveryRefusal.IDENTITY_CONFLICT,
            machine.activate(sealed, sealed.revision, f.activate().copy(finalCheckpoint =
                f.checkpoint.copy(manifestSha256 = f.hash(999)))).refusal)
        assertEquals(RecoveryRefusal.IDENTITY_CONFLICT,
            machine.activate(sealed, sealed.revision, f.activate().copy(durableOldSealEvidence = f.evidence(999))).refusal)
    }

    @Test
    fun targetCannotActivateFromProposedSealWithoutVerifiedDurableSourceSeal() {
        val state = sealed()
        val noDurableSeal = AuthorityLifecycleMachine(ModelEvidenceVerifier { it !is DurablePlannedSealAssertion }, ModelPhysicalGate())
        assertEquals(RecoveryRefusal.EVIDENCE_NOT_VERIFIED, noDurableSeal.activate(state, state.revision, f.activate()).refusal)
        val noTargetCheckpoint = AuthorityLifecycleMachine(ModelEvidenceVerifier { it !is PlannedSealPreconditionsAssertion })
        var draining = machine.prepare(initial(), Revision(0), f.prepare()).state
        draining = machine.drain(draining, draining.revision, f.drain()).state
        assertEquals(RecoveryRefusal.EVIDENCE_NOT_VERIFIED, noTargetCheckpoint.seal(draining, draining.revision, f.seal()).refusal)
        assertEquals(RecoveryRefusal.EVIDENCE_NOT_VERIFIED,
            AuthorityLifecycleMachine().prepare(initial(), Revision(0), f.prepare()).refusal)
    }

    @Test
    fun exhaustivePlannedActionPermutationsDoNotReopenASealedPredecessor() {
        val schedules = permutations(listOf("prepare", "drain", "seal", "activate", "complete", "abort"))
        var appliedSeals = 0
        for (schedule in schedules) {
            var state = initial()
            var sealedEver = false
            for (event in schedule) {
                val before = state
                val result = when (event) {
                    "prepare" -> machine.prepare(state, state.revision, f.prepare())
                    "drain" -> machine.drain(state, state.revision, f.drain())
                    "seal" -> machine.seal(state, state.revision, f.seal())
                    "activate" -> machine.activate(state, state.revision, f.activate())
                    "complete" -> machine.complete(state, state.revision, f.complete())
                    "abort" -> machine.abort(state, state.revision, f.abort())
                    else -> error("Unknown fixture event")
                }
                if (result.disposition == TransitionDisposition.REFUSED) assertEquals(before, result.state)
                if (event == "seal" && result.disposition == TransitionDisposition.APPLIED) {
                    sealedEver = true
                    appliedSeals++
                }
                state = Json.decodeFromString(Json.encodeToString(result.state))
                if (sealedEver) {
                    assertNotEquals(AuthorityWriterDecision.ACTIVE, writer(state, f.old), schedule.toString())
                    assertTrue(f.old.assignmentId in state.retiredAssignmentIds)
                }
                val activeWriters = listOf(f.old, f.target).count { writer(state, it) == AuthorityWriterDecision.ACTIVE }
                assertTrue(activeWriters <= 1, schedule.toString())
            }
        }
        assertEquals(720, schedules.size)
        assertTrue(appliedSeals > 0)
    }

    @Test
    fun completePlannedTransitionTableMatchesIndependentProtocolOracle() {
        val empty = initial()
        val preparing = machine.prepare(empty, empty.revision, f.prepare()).state
        val draining = machine.drain(preparing, preparing.revision, f.drain()).state
        val sealed = machine.seal(draining, draining.revision, f.seal()).state
        val activated = machine.activate(sealed, sealed.revision, f.activate()).state
        val completed = machine.complete(activated, activated.revision, f.complete()).state
        val abortedBeforeDrain = machine.abort(preparing, preparing.revision, f.abort()).state
        val abortedAfterDrain = machine.abort(draining, draining.revision, f.abort()).state
        val states = listOf(empty, preparing, draining, sealed, activated, completed, abortedBeforeDrain, abortedAfterDrain)
        // Columns: prepare, drain, seal, activate, complete, abort.
        // A = new durable transition proposed; I = identical retry; R = forbidden.
        val oracle = listOf("ARRRRR", "IARRRA", "IIARRA", "IIIARR", "IIIIAR", "IIIIIR", "IRRRRI", "IIRRRI")
        val actions: List<(AuthorityLifecycleState) -> RecoveryTransition<AuthorityLifecycleState>> = listOf(
            { machine.prepare(it, it.revision, f.prepare()) }, { machine.drain(it, it.revision, f.drain()) },
            { machine.seal(it, it.revision, f.seal()) }, { machine.activate(it, it.revision, f.activate()) },
            { machine.complete(it, it.revision, f.complete()) }, { machine.abort(it, it.revision, f.abort()) },
        )
        var examined = 0
        for ((row, state) in states.withIndex()) for ((column, action) in actions.withIndex()) {
            val result = action(state)
            val expected = when (oracle[row][column]) {
                'A' -> TransitionDisposition.APPLIED
                'I' -> TransitionDisposition.IDEMPOTENT
                else -> TransitionDisposition.REFUSED
            }
            assertEquals(expected, result.disposition, "state=$row action=$column")
            if (expected != TransitionDisposition.APPLIED) assertEquals(state, result.state)
            if (column == 5 && row in 3..5) assertEquals(RecoveryRefusal.POST_SEAL_ABORT_FORBIDDEN, result.refusal)
            examined++
        }
        assertEquals(48, examined)
    }

    @Test
    fun copiedSignaturesAndEpochsCannotSatisfyEmergencyPhysicalExclusion() {
        val originalCopy = AuthorityLifecycleState.restored(f.checkpoint)
        val clonedCopy = Json.decodeFromString<AuthorityLifecycleState>(Json.encodeToString(originalCopy))
        val signaturesOnly = AuthorityLifecycleMachine(ModelEvidenceVerifier())
        val otherBinding = f.binding.copy(ceremonyId = f.id(999), target = f.otherTarget)
        // Two disconnected software copies can each issue a valid-looking appointment.
        val firstIssued = signaturesOnly.issueEmergency(originalCopy, originalCopy.revision, f.emergency()).state
        val secondIssued = signaturesOnly.issueEmergency(clonedCopy, clonedCopy.revision,
            f.emergency(otherBinding).copy(approvingActorId = f.id(998))).state
        assertEquals(EmergencyAppointmentPhase.ISSUED, firstIssued.emergencyAppointments.single().phase)
        assertEquals(EmergencyAppointmentPhase.ISSUED, secondIssued.emergencyAppointments.single().phase)
        for ((state, binding) in listOf(firstIssued to f.binding, secondIssued to otherBinding)) {
            val activation = signaturesOnly.activateEmergency(state, state.revision, f.emergencyActivation(binding))
            assertEquals(RecoveryRefusal.OWNER_PHYSICAL_GATE_UNQUALIFIED, activation.refusal)
            assertNull(activation.state.activeAuthority)
            assertEquals(state, activation.state)
        }
        assertEquals(Revision(2), firstIssued.emergencyAppointments.single().issuance.binding.target.epoch)
        assertEquals(Revision(2), secondIssued.emergencyAppointments.single().issuance.binding.target.epoch)
    }

    @Test
    fun everyMissingPhysicalControlFailsClosedEvenWithAcceptedSignatures() {
        val expected = mapOf(
            PhysicalRecoveryGateDecision.OWNER_PROCEDURE_UNQUALIFIED to RecoveryRefusal.OWNER_PHYSICAL_GATE_UNQUALIFIED,
            PhysicalRecoveryGateDecision.OLD_WRITER_NOT_EXCLUDED to RecoveryRefusal.OLD_WRITER_NOT_EXCLUDED,
            PhysicalRecoveryGateDecision.COMPETING_SUCCESSOR_NOT_EXCLUDED to RecoveryRefusal.COMPETING_SUCCESSOR_NOT_EXCLUDED,
            PhysicalRecoveryGateDecision.ISSUER_LOST_OR_CLONED to RecoveryRefusal.ISSUER_CUSTODY_UNCERTAIN,
            PhysicalRecoveryGateDecision.TARGET_NOT_EXCLUDED to RecoveryRefusal.TARGET_NOT_EXCLUDED,
        )
        for ((gateDecision, refusal) in expected) {
            val gate = ModelPhysicalGate(activation = gateDecision)
            val machine = AuthorityLifecycleMachine(ModelEvidenceVerifier(), gate)
            val initial = AuthorityLifecycleState.restored(f.checkpoint)
            val issued = machine.issueEmergency(initial, initial.revision, f.emergency()).state
            val result = machine.activateEmergency(issued, issued.revision, f.emergencyActivation())
            assertEquals(refusal, result.refusal)
            assertNull(result.state.activeAuthority)
            assertEquals(f.binding, gate.activationRequests.single().activation.binding)
            assertEquals(f.checkpoint, gate.activationRequests.single().issuance.checkpoint)
        }
    }

    @Test
    fun emergencyNeedsPreparedNamedDelegationNotAGenericManagerOrBackup() {
        val initial = AuthorityLifecycleState.restored(f.checkpoint)
        assertEquals(RecoveryRefusal.EVIDENCE_NOT_VERIFIED,
            AuthorityLifecycleMachine().issueEmergency(initial, initial.revision, f.emergency()).refusal)
        val expectedDelegate = f.approver
        val qualifiedModel = AuthorityLifecycleMachine(ModelEvidenceVerifier { assertion ->
            assertion !is EmergencyIssuanceAssertion || assertion.proposal.approvingActorId == expectedDelegate
        }, ModelPhysicalGate())
        assertEquals(RecoveryRefusal.EVIDENCE_NOT_VERIFIED, qualifiedModel.issueEmergency(initial, initial.revision,
            f.emergency().copy(approvingActorId = f.cashier)).refusal)
        assertEquals(TransitionDisposition.APPLIED,
            qualifiedModel.issueEmergency(initial, initial.revision, f.emergency()).disposition)
    }

    @Test
    fun twoConcurrentTargetSelectionsNeedDurableCasAndOnePendingIncident() {
        val bindings = listOf(f.binding, f.binding.copy(ceremonyId = f.id(999), target = f.otherTarget))
        for (schedule in permutations(bindings)) {
            val snapshot = AuthorityLifecycleState.restored(f.checkpoint)
            val first = machine.issueEmergency(snapshot, snapshot.revision, f.emergency(schedule[0])).state
            assertEquals(RecoveryRefusal.REVISION_CONFLICT,
                machine.issueEmergency(first, snapshot.revision, f.emergency(schedule[1])).refusal)
            assertEquals(RecoveryRefusal.INVALID_STATE,
                machine.issueEmergency(first, first.revision, f.emergency(schedule[1])).refusal)
            assertEquals(1, first.emergencyAppointments.size)
            assertEquals(schedule[0], first.emergencyAppointments.single().issuance.binding)
        }
    }

    @Test
    fun emergencyActivationLostReplyIsIdempotentAndCheckpointOrTargetSwapIsRejected() {
        var state = AuthorityLifecycleState.restored(f.checkpoint)
        state = machine.issueEmergency(state, state.revision, f.emergency()).state
        val beforeActivationRevision = state.revision
        assertEquals(RecoveryRefusal.STALE_TARGET, machine.activateEmergency(state, state.revision,
            f.emergencyActivation(f.binding.copy(target = f.otherTarget))).refusal)
        assertEquals(RecoveryRefusal.IDENTITY_CONFLICT, machine.activateEmergency(state, state.revision,
            f.emergencyActivation().copy(checkpoint = f.checkpoint.copy(manifestSha256 = f.hash(999)))).refusal)
        state = machine.activateEmergency(state, state.revision, f.emergencyActivation()).state
        state = Json.decodeFromString(Json.encodeToString(state))
        assertEquals(f.target, state.activeAuthority)
        val retry = machine.activateEmergency(state, beforeActivationRevision, f.emergencyActivation())
        assertEquals(TransitionDisposition.IDEMPOTENT, retry.disposition)
        assertEquals(state, retry.state)
        val completion = CompleteEmergencyAppointment(f.binding, f.evidence(998))
        state = machine.completeEmergency(state, state.revision, completion).state
        assertEquals(EmergencyAppointmentPhase.COMPLETED, state.emergencyAppointments.single().phase)
        assertEquals(TransitionDisposition.IDEMPOTENT,
            machine.activateEmergency(state, beforeActivationRevision, f.emergencyActivation()).disposition)
        assertEquals(1, state.emergencyAppointments.size)
        assertEquals(1, state.retiredAssignmentIds.size)
    }

    @Test
    fun changingEmergencyTargetRequiresPhysicalCancellationAndKeepsOriginalIncident() {
        val initial = AuthorityLifecycleState.restored(f.checkpoint)
        val issued = machine.issueEmergency(initial, initial.revision, f.emergency()).state
        assertEquals(RecoveryRefusal.OWNER_PHYSICAL_GATE_UNQUALIFIED,
            AuthorityLifecycleMachine(ModelEvidenceVerifier()).cancelEmergency(issued, issued.revision, f.cancellation()).refusal)
        val cancelled = machine.cancelEmergency(issued, issued.revision, f.cancellation()).state
        assertEquals(EmergencyAppointmentPhase.CANCELLED, cancelled.emergencyAppointments.single().phase)
        assertNull(cancelled.activeAuthority)
        val changed = f.binding.copy(ceremonyId = f.id(999), target = f.otherTarget)
        assertEquals(RecoveryRefusal.IDENTITY_CONFLICT, machine.issueEmergency(cancelled, cancelled.revision,
            f.emergency(changed.copy(incidentId = f.id(998)))).refusal)
        val next = machine.issueEmergency(cancelled, cancelled.revision, f.emergency(changed)).state
        assertEquals(2, next.emergencyAppointments.size)
        assertEquals(RecoveryRefusal.INVALID_STATE,
            machine.activateEmergency(next, next.revision, f.emergencyActivation()).refusal)
        assertEquals(TransitionDisposition.APPLIED,
            machine.activateEmergency(next, next.revision, f.emergencyActivation(changed)).disposition)
    }

    @Test
    fun startupDoesNotActivateRestoredOrRetiredStateAndNeedsExclusiveLocalStorage() {
        val restored = AuthorityLifecycleState.restored(f.checkpoint)
        assertEquals(AuthorityWriterDecision.NO_ACTIVE_AUTHORITY, writer(restored, f.old))
        assertEquals(AuthorityWriterDecision.INTENTIONALLY_STOPPED, writer(initial().copy(intentionallyStopped = true), f.old))
        assertEquals(AuthorityWriterDecision.EXCLUSIVE_STORAGE_NOT_VERIFIED,
            AuthorityWritePolicy().evaluate(initial(), f.old, f.id(900), f.evidence(901)))
        assertEquals(AuthorityWriterDecision.NO_ACTIVE_AUTHORITY, writer(sealed(), f.old))
        // Same-master restart preserves authority generation rather than inventing a lease.
        assertEquals(f.old, Json.decodeFromString<AuthorityLifecycleState>(Json.encodeToString(initial())).activeAuthority)
    }

    @Test
    fun invalidGenerationDuplicateTargetIdentityAndExhaustedEpochCannotAppoint() {
        for (target in listOf(
            f.target.copy(epoch = Revision(1)), f.target.copy(epoch = Revision(3)),
            f.target.copy(assignmentId = f.old.assignmentId), f.target.copy(deviceId = f.old.deviceId),
        )) {
            assertEquals(RecoveryRefusal.INVALID_AUTHORITY_CHAIN,
                machine.prepare(initial(), Revision(0), f.prepare(f.binding.copy(target = target))).refusal)
        }
        val exhausted = f.old.copy(epoch = Revision(Long.MAX_VALUE))
        val state = AuthorityLifecycleState.initiallyProvisioned(f.branch, exhausted)
        assertEquals(RecoveryRefusal.INVALID_AUTHORITY_CHAIN, machine.prepare(state, state.revision,
            f.prepare(f.binding.copy(predecessor = exhausted, target = f.target.copy(epoch = Revision(0))))).refusal)
    }

    @Test
    fun malformedPersistedPhasesAndContradictoryWriterStateFailClosed() {
        assertFailsWith<IllegalArgumentException> { PlannedHandover(f.prepare(), HandoverPhase.SEALED) }
        assertFailsWith<IllegalArgumentException> { EmergencyAppointment(f.emergency(), EmergencyAppointmentPhase.ACTIVATED) }
        assertFailsWith<IllegalArgumentException> {
            PlannedHandover(f.prepare(), HandoverPhase.DRAINING, drain = f.drain(f.binding.copy(target = f.otherTarget)))
        }
        val sealed = sealed()
        val contradictory = sealed.copy(activeAuthority = f.old, retiredAssignmentIds = emptySet())
        assertEquals(AuthorityWriterDecision.STALE_OR_RETIRED_AUTHORITY, writer(contradictory, f.old))
        assertFailsWith<IllegalArgumentException> {
            f.restored().copy(holds = emptyList())
        }
    }
}
