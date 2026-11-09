package com.ukkera.pos.recovery

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.Serializable

/** A ceremony never changes target, predecessor or incident after issuance. */
@Serializable
public data class AuthorityTransferBinding(
    public val branchId: DomainId,
    public val ceremonyId: DomainId,
    public val incidentId: DomainId,
    public val predecessor: AuthorityIdentity,
    public val target: AuthorityIdentity,
)

@Serializable
public data class PrepareHandover(
    public val binding: AuthorityTransferBinding,
    public val approvingActorId: DomainId,
    public val approvalEvidence: RecoveryEvidenceRef,
    public val targetReadinessEvidence: RecoveryEvidenceRef,
)

@Serializable
public data class DrainHandover(
    public val binding: AuthorityTransferBinding,
    public val decisionEvidence: RecoveryEvidenceRef,
)

@Serializable
public data class SealHandover(
    public val binding: AuthorityTransferBinding,
    public val finalCheckpoint: RecoveryCheckpoint,
    public val drainedHandlersEvidence: RecoveryEvidenceRef,
    public val targetDurableCheckpointEvidence: RecoveryEvidenceRef,
    public val sealRecord: RecoveryEvidenceRef,
)

@Serializable
public data class ActivateHandover(
    public val binding: AuthorityTransferBinding,
    public val finalCheckpoint: RecoveryCheckpoint,
    public val durableOldSealEvidence: RecoveryEvidenceRef,
    public val targetActivationEvidence: RecoveryEvidenceRef,
)

@Serializable
public data class CompleteHandover(
    public val binding: AuthorityTransferBinding,
    public val completionEvidence: RecoveryEvidenceRef,
)

@Serializable
public data class AbortHandover(
    public val binding: AuthorityTransferBinding,
    public val decisionEvidence: RecoveryEvidenceRef,
    public val targetRemainsNonAuthoritativeEvidence: RecoveryEvidenceRef,
)

@Serializable
public enum class HandoverPhase {
    PREPARING,
    DRAINING,
    SEALED,
    ACTIVATED,
    COMPLETED,
    ABORTED,
}

@Serializable
public data class PlannedHandover(
    public val preparation: PrepareHandover,
    public val phase: HandoverPhase,
    public val drain: DrainHandover? = null,
    public val seal: SealHandover? = null,
    public val activation: ActivateHandover? = null,
    public val completion: CompleteHandover? = null,
    public val abort: AbortHandover? = null,
) {
    init {
        require(listOfNotNull(drain?.binding, seal?.binding, activation?.binding, completion?.binding, abort?.binding)
            .all { it == preparation.binding })
        require(activation == null || (activation.finalCheckpoint == seal?.finalCheckpoint &&
            activation.durableOldSealEvidence == seal?.sealRecord))
        when (phase) {
            HandoverPhase.PREPARING -> require(drain == null && seal == null && activation == null && completion == null && abort == null)
            HandoverPhase.DRAINING -> require(drain != null && seal == null && activation == null && completion == null && abort == null)
            HandoverPhase.SEALED -> require(drain != null && seal != null && activation == null && completion == null && abort == null)
            HandoverPhase.ACTIVATED -> require(drain != null && seal != null && activation != null && completion == null && abort == null)
            HandoverPhase.COMPLETED -> require(drain != null && seal != null && activation != null && completion != null && abort == null)
            HandoverPhase.ABORTED -> require(seal == null && activation == null && completion == null && abort != null)
        }
    }
}

@Serializable
public data class IssueEmergencyAppointment(
    public val binding: AuthorityTransferBinding,
    public val checkpoint: RecoveryCheckpoint,
    public val approvingActorId: DomainId,
    public val preparedLocalDelegationEvidence: RecoveryEvidenceRef,
    public val independentCopyEvidence: RecoveryEvidenceRef,
    public val issuanceEvidence: RecoveryEvidenceRef,
)

/**
 * Claims to be checked by an independently qualified owner-controlled physical gate.
 * Signatures/epochs/one-use tokens alone do not satisfy any exclusivity claim here.
 * In particular, a cloned disconnected issuer database is NOT exclusive custody.
 */
@Serializable
public data class PhysicalRecoveryEvidenceClaim(
    public val ownerAcceptedProcedure: RecoveryEvidenceRef,
    public val exclusiveIssuanceCustody: RecoveryEvidenceRef,
    public val oldWriterExclusion: RecoveryEvidenceRef,
    public val competingSuccessorExclusion: RecoveryEvidenceRef,
    public val targetExclusiveStorage: RecoveryEvidenceRef,
    public val freshTargetIdentityAndSessions: RecoveryEvidenceRef,
)

@Serializable
public data class ActivateEmergencyAppointment(
    public val binding: AuthorityTransferBinding,
    public val checkpoint: RecoveryCheckpoint,
    public val issuanceEvidence: RecoveryEvidenceRef,
    public val activationRecordEvidence: RecoveryEvidenceRef,
    public val physicalEvidence: PhysicalRecoveryEvidenceClaim,
)

@Serializable
public data class CancelEmergencyAppointment(
    public val binding: AuthorityTransferBinding,
    public val decisionEvidence: RecoveryEvidenceRef,
    public val targetCancellationAndExclusionEvidence: RecoveryEvidenceRef,
)

@Serializable
public data class CompleteEmergencyAppointment(
    public val binding: AuthorityTransferBinding,
    public val completionEvidence: RecoveryEvidenceRef,
)

@Serializable
public enum class EmergencyAppointmentPhase {
    ISSUED,
    ACTIVATED,
    COMPLETED,
    CANCELLED,
}

@Serializable
public data class EmergencyAppointment(
    public val issuance: IssueEmergencyAppointment,
    public val phase: EmergencyAppointmentPhase,
    public val activation: ActivateEmergencyAppointment? = null,
    public val cancellation: CancelEmergencyAppointment? = null,
    public val completion: CompleteEmergencyAppointment? = null,
) {
    init {
        require(listOfNotNull(activation?.binding, cancellation?.binding, completion?.binding).all { it == issuance.binding })
        require(activation == null || (activation.checkpoint == issuance.checkpoint && activation.issuanceEvidence == issuance.issuanceEvidence))
        when (phase) {
            EmergencyAppointmentPhase.ISSUED -> require(activation == null && cancellation == null && completion == null)
            EmergencyAppointmentPhase.ACTIVATED -> require(activation != null && cancellation == null && completion == null)
            EmergencyAppointmentPhase.COMPLETED -> require(activation != null && cancellation == null && completion != null)
            EmergencyAppointmentPhase.CANCELLED -> require(activation == null && cancellation != null && completion == null)
        }
    }
}

/**
 * Local persisted protocol state, NOT a globally consistent register. A copied state
 * can fork; actual emergency exclusion is an external activation prerequisite.
 * Retirement in this state does not install a marker on an isolated old device.
 */
@Serializable
public data class AuthorityLifecycleState(
    public val branchId: DomainId,
    public val revision: Revision,
    public val lastKnownAuthority: AuthorityIdentity,
    public val activeAuthority: AuthorityIdentity?,
    public val intentionallyStopped: Boolean,
    public val restoredFrom: RecoveryCheckpoint?,
    public val retiredAssignmentIds: Set<DomainId>,
    public val handovers: List<PlannedHandover>,
    public val emergencyAppointments: List<EmergencyAppointment>,
) {
    init {
        require(restoredFrom == null || restoredFrom.position.branchId == branchId)
        require(activeAuthority == null || activeAuthority.assignmentId !in retiredAssignmentIds)
        require(activeAuthority == null || activeAuthority == lastKnownAuthority)
        require(handovers.size <= MAX_RECOVERY_ITEMS && emergencyAppointments.size <= MAX_RECOVERY_ITEMS)
        require(retiredAssignmentIds.size <= MAX_RECOVERY_ITEMS)
        val ids = handovers.map { it.preparation.binding.ceremonyId } +
            emergencyAppointments.map { it.issuance.binding.ceremonyId }
        require(ids.toSet().size == ids.size)
        require(handovers.all { it.preparation.binding.branchId == branchId })
        require(emergencyAppointments.all { it.issuance.binding.branchId == branchId })
        require(handovers.count { it.phase !in setOf(HandoverPhase.COMPLETED, HandoverPhase.ABORTED) } +
            emergencyAppointments.count { it.phase !in setOf(EmergencyAppointmentPhase.COMPLETED, EmergencyAppointmentPhase.CANCELLED) } <= 1)
    }

    public companion object {
        /** Initial verified branch provisioning only, not restore or automatic promotion. */
        public fun initiallyProvisioned(branchId: DomainId, authority: AuthorityIdentity): AuthorityLifecycleState =
            AuthorityLifecycleState(branchId, Revision(0), authority, authority, false, null,
                emptySet(), emptyList(), emptyList())

        /** Restoring a copy never restores active-writer status. */
        public fun restored(checkpoint: RecoveryCheckpoint): AuthorityLifecycleState = AuthorityLifecycleState(
            checkpoint.position.branchId, Revision(0), checkpoint.position.authority, null, false, checkpoint,
            emptySet(), emptyList(), emptyList(),
        )
    }
}

public data class PlannedPreparationAssertion(public val proposal: PrepareHandover) : RecoveryEvidenceAssertion {
    override val branchId: DomainId get() = proposal.binding.branchId
    override val evidence: List<RecoveryEvidenceRef> get() =
        listOf(proposal.approvalEvidence, proposal.targetReadinessEvidence)
}

public data class PlannedDrainAssertion(public val proposal: DrainHandover) : RecoveryEvidenceAssertion {
    override val branchId: DomainId get() = proposal.binding.branchId
    override val evidence: List<RecoveryEvidenceRef> get() = listOf(proposal.decisionEvidence)
}

/** Verifies drained identity handlers and a complete target-durable cut BEFORE source sealing. */
public data class PlannedSealPreconditionsAssertion(public val proposal: SealHandover) : RecoveryEvidenceAssertion {
    override val branchId: DomainId get() = proposal.binding.branchId
    override val evidence: List<RecoveryEvidenceRef> get() =
        listOf(proposal.drainedHandlersEvidence, proposal.targetDurableCheckpointEvidence)
}

/** Activation requires the actual persisted source seal, not this reducer's proposed state. */
public data class DurablePlannedSealAssertion(public val seal: SealHandover) : RecoveryEvidenceAssertion {
    override val branchId: DomainId get() = seal.binding.branchId
    override val evidence: List<RecoveryEvidenceRef> get() = listOf(seal.sealRecord)
}

public data class PlannedActivationAssertion(public val proposal: ActivateHandover) : RecoveryEvidenceAssertion {
    override val branchId: DomainId get() = proposal.binding.branchId
    override val evidence: List<RecoveryEvidenceRef> get() =
        listOf(proposal.durableOldSealEvidence, proposal.targetActivationEvidence)
}

public data class PlannedCompletionAssertion(public val proposal: CompleteHandover) : RecoveryEvidenceAssertion {
    override val branchId: DomainId get() = proposal.binding.branchId
    override val evidence: List<RecoveryEvidenceRef> get() = listOf(proposal.completionEvidence)
}

public data class PlannedAbortAssertion(public val proposal: AbortHandover) : RecoveryEvidenceAssertion {
    override val branchId: DomainId get() = proposal.binding.branchId
    override val evidence: List<RecoveryEvidenceRef> get() =
        listOf(proposal.decisionEvidence, proposal.targetRemainsNonAuthoritativeEvidence)
}

/** Prepared named local delegation, independent usable copy and durable target-bound issuance. */
public data class EmergencyIssuanceAssertion(public val proposal: IssueEmergencyAppointment) : RecoveryEvidenceAssertion {
    override val branchId: DomainId get() = proposal.binding.branchId
    override val evidence: List<RecoveryEvidenceRef> get() = listOf(
        proposal.preparedLocalDelegationEvidence, proposal.independentCopyEvidence, proposal.issuanceEvidence,
    )
}

public data class EmergencyActivationAssertion(
    public val issuance: IssueEmergencyAppointment,
    public val proposal: ActivateEmergencyAppointment,
) : RecoveryEvidenceAssertion {
    override val branchId: DomainId get() = proposal.binding.branchId
    override val evidence: List<RecoveryEvidenceRef> get() =
        listOf(proposal.issuanceEvidence, proposal.activationRecordEvidence)
}

public data class EmergencyCancellationAssertion(public val proposal: CancelEmergencyAppointment) : RecoveryEvidenceAssertion {
    override val branchId: DomainId get() = proposal.binding.branchId
    override val evidence: List<RecoveryEvidenceRef> get() =
        listOf(proposal.decisionEvidence, proposal.targetCancellationAndExclusionEvidence)
}

public data class EmergencyCompletionAssertion(public val proposal: CompleteEmergencyAppointment) : RecoveryEvidenceAssertion {
    override val branchId: DomainId get() = proposal.binding.branchId
    override val evidence: List<RecoveryEvidenceRef> get() = listOf(proposal.completionEvidence)
}

public data class PhysicalActivationAssessment(
    public val issuance: IssueEmergencyAppointment,
    public val activation: ActivateEmergencyAppointment,
)

public data class PhysicalCancellationAssessment(
    public val issuance: IssueEmergencyAppointment,
    public val cancellation: CancelEmergencyAppointment,
)

public enum class PhysicalRecoveryGateDecision {
    EXCLUSIVE_CONTROLS_VERIFIED,
    OWNER_PROCEDURE_UNQUALIFIED,
    OLD_WRITER_NOT_EXCLUDED,
    COMPETING_SUCCESSOR_NOT_EXCLUDED,
    ISSUER_LOST_OR_CLONED,
    TARGET_NOT_EXCLUDED,
}

/**
 * Deployment/owner gate, not software consensus. Implement only after qualification of
 * actual exclusive issuance custody and physical/operational writer/target exclusion.
 * There is intentionally no accepting production implementation in this module.
 */
public interface PhysicalRecoveryGate {
    public fun assessActivation(assessment: PhysicalActivationAssessment): PhysicalRecoveryGateDecision
    public fun assessCancellation(assessment: PhysicalCancellationAssessment): PhysicalRecoveryGateDecision
}

public object UnqualifiedPhysicalRecoveryGate : PhysicalRecoveryGate {
    override fun assessActivation(assessment: PhysicalActivationAssessment): PhysicalRecoveryGateDecision =
        PhysicalRecoveryGateDecision.OWNER_PROCEDURE_UNQUALIFIED

    override fun assessCancellation(assessment: PhysicalCancellationAssessment): PhysicalRecoveryGateDecision =
        PhysicalRecoveryGateDecision.OWNER_PROCEDURE_UNQUALIFIED
}

public data class RuntimeStorageOwnershipAssertion(
    override val branchId: DomainId,
    public val authority: AuthorityIdentity,
    public val runtimeInstanceId: DomainId,
    public val ownershipEvidence: RecoveryEvidenceRef,
) : RecoveryEvidenceAssertion {
    override val evidence: List<RecoveryEvidenceRef> get() = listOf(ownershipEvidence)
}

public enum class AuthorityWriterDecision {
    ACTIVE,
    NO_ACTIVE_AUTHORITY,
    STALE_OR_RETIRED_AUTHORITY,
    INTENTIONALLY_STOPPED,
    DRAINING,
    EXCLUSIVE_STORAGE_NOT_VERIFIED,
}

public class AuthorityWritePolicy(
    private val verifier: RecoveryEvidenceVerifier = UnqualifiedRecoveryEvidenceVerifier,
) {
    /** Result recovery can remain available; this gate is only for fresh mutations. */
    public fun evaluate(
        state: AuthorityLifecycleState,
        localAuthority: AuthorityIdentity,
        runtimeInstanceId: DomainId,
        ownershipEvidence: RecoveryEvidenceRef,
    ): AuthorityWriterDecision {
        val active = state.activeAuthority ?: return AuthorityWriterDecision.NO_ACTIVE_AUTHORITY
        if (active != localAuthority || localAuthority.assignmentId in state.retiredAssignmentIds) {
            return AuthorityWriterDecision.STALE_OR_RETIRED_AUTHORITY
        }
        // Redundant with valid transition output, but fail closed for a contradictory persisted role.
        if (state.handovers.any { it.preparation.binding.predecessor == localAuthority && it.seal != null } ||
            state.emergencyAppointments.any { it.issuance.binding.predecessor == localAuthority && it.phase != EmergencyAppointmentPhase.CANCELLED }
        ) {
            return AuthorityWriterDecision.STALE_OR_RETIRED_AUTHORITY
        }
        if (state.intentionallyStopped) return AuthorityWriterDecision.INTENTIONALLY_STOPPED
        if (state.handovers.any { it.preparation.binding.predecessor == localAuthority && it.phase == HandoverPhase.DRAINING }) {
            return AuthorityWriterDecision.DRAINING
        }
        if (!verifier.verifies(RuntimeStorageOwnershipAssertion(state.branchId, localAuthority, runtimeInstanceId, ownershipEvidence))) {
            return AuthorityWriterDecision.EXCLUSIVE_STORAGE_NOT_VERIFIED
        }
        return AuthorityWriterDecision.ACTIVE
    }
}

/** Pure transitions; every APPLIED state still requires a real protected durable CAS commit. */
public class AuthorityLifecycleMachine(
    private val verifier: RecoveryEvidenceVerifier = UnqualifiedRecoveryEvidenceVerifier,
    private val physicalGate: PhysicalRecoveryGate = UnqualifiedPhysicalRecoveryGate,
) {
    public fun prepare(
        state: AuthorityLifecycleState,
        expectedRevision: Revision,
        proposal: PrepareHandover,
    ): RecoveryTransition<AuthorityLifecycleState> {
        state.handover(proposal.binding)?.let {
            return if (it.preparation == proposal) unchanged(state) else refused(state, RecoveryRefusal.IDENTITY_CONFLICT)
        }
        validateNewBinding(state, expectedRevision, proposal.binding)?.let { return refused(state, it) }
        if (state.activeAuthority != proposal.binding.predecessor || state.hasPendingCeremony()) {
            return refused(state, RecoveryRefusal.INVALID_STATE)
        }
        if (!verifier.verifies(PlannedPreparationAssertion(proposal))) return refused(state, RecoveryRefusal.EVIDENCE_NOT_VERIFIED)
        if (state.handovers.size == MAX_RECOVERY_ITEMS) return refused(state, RecoveryRefusal.CAPACITY_REQUIRES_PROTECTED_ARCHIVE)
        return applied(state.copy(revision = state.revision.next(),
            handovers = state.handovers + PlannedHandover(proposal, HandoverPhase.PREPARING)))
    }

    public fun drain(
        state: AuthorityLifecycleState,
        expectedRevision: Revision,
        proposal: DrainHandover,
    ): RecoveryTransition<AuthorityLifecycleState> {
        val handover = state.handover(proposal.binding) ?: return refused(state, RecoveryRefusal.INVALID_STATE)
        if (handover.preparation.binding != proposal.binding) return refused(state, RecoveryRefusal.STALE_TARGET)
        handover.drain?.let {
            return if (it == proposal) unchanged(state) else refused(state, RecoveryRefusal.IDENTITY_CONFLICT)
        }
        if (state.revision != expectedRevision) return refused(state, RecoveryRefusal.REVISION_CONFLICT)
        if (handover.phase != HandoverPhase.PREPARING) return refused(state, RecoveryRefusal.INVALID_STATE)
        if (!verifier.verifies(PlannedDrainAssertion(proposal))) return refused(state, RecoveryRefusal.EVIDENCE_NOT_VERIFIED)
        return applied(state.replace(handover.copy(phase = HandoverPhase.DRAINING, drain = proposal)))
    }

    public fun seal(
        state: AuthorityLifecycleState,
        expectedRevision: Revision,
        proposal: SealHandover,
    ): RecoveryTransition<AuthorityLifecycleState> {
        val handover = state.handover(proposal.binding) ?: return refused(state, RecoveryRefusal.INVALID_STATE)
        if (handover.preparation.binding != proposal.binding) return refused(state, RecoveryRefusal.STALE_TARGET)
        handover.seal?.let {
            return if (it == proposal) unchanged(state) else refused(state, RecoveryRefusal.IDENTITY_CONFLICT)
        }
        if (state.revision != expectedRevision) return refused(state, RecoveryRefusal.REVISION_CONFLICT)
        if (handover.phase != HandoverPhase.DRAINING || state.activeAuthority != proposal.binding.predecessor) {
            return refused(state, RecoveryRefusal.INVALID_STATE)
        }
        if (proposal.finalCheckpoint.position.branchId != state.branchId ||
            proposal.finalCheckpoint.position.authority != proposal.binding.predecessor
        ) {
            return refused(state, RecoveryRefusal.INVALID_AUTHORITY_CHAIN)
        }
        if (!verifier.verifies(PlannedSealPreconditionsAssertion(proposal))) return refused(state, RecoveryRefusal.EVIDENCE_NOT_VERIFIED)
        if (state.retiredAssignmentIds.size == MAX_RECOVERY_ITEMS) return refused(state, RecoveryRefusal.CAPACITY_REQUIRES_PROTECTED_ARCHIVE)
        return applied(state.replace(handover.copy(phase = HandoverPhase.SEALED, seal = proposal)).copy(
            activeAuthority = null, retiredAssignmentIds = state.retiredAssignmentIds + proposal.binding.predecessor.assignmentId))
    }

    public fun activate(
        state: AuthorityLifecycleState,
        expectedRevision: Revision,
        proposal: ActivateHandover,
    ): RecoveryTransition<AuthorityLifecycleState> {
        val handover = state.handover(proposal.binding) ?: return refused(state, RecoveryRefusal.INVALID_STATE)
        if (handover.preparation.binding != proposal.binding) return refused(state, RecoveryRefusal.STALE_TARGET)
        handover.activation?.let {
            return if (it == proposal) unchanged(state) else refused(state, RecoveryRefusal.IDENTITY_CONFLICT)
        }
        if (state.revision != expectedRevision) return refused(state, RecoveryRefusal.REVISION_CONFLICT)
        val seal = handover.seal ?: return refused(state, RecoveryRefusal.INVALID_STATE)
        if (handover.phase != HandoverPhase.SEALED || state.activeAuthority != null) return refused(state, RecoveryRefusal.INVALID_STATE)
        if (proposal.finalCheckpoint != seal.finalCheckpoint || proposal.durableOldSealEvidence != seal.sealRecord) {
            return refused(state, RecoveryRefusal.IDENTITY_CONFLICT)
        }
        if (!verifier.verifies(DurablePlannedSealAssertion(seal)) || !verifier.verifies(PlannedActivationAssertion(proposal))) {
            return refused(state, RecoveryRefusal.EVIDENCE_NOT_VERIFIED)
        }
        return applied(state.replace(handover.copy(phase = HandoverPhase.ACTIVATED, activation = proposal)).copy(
            activeAuthority = proposal.binding.target, lastKnownAuthority = proposal.binding.target, intentionallyStopped = false))
    }

    public fun complete(
        state: AuthorityLifecycleState,
        expectedRevision: Revision,
        proposal: CompleteHandover,
    ): RecoveryTransition<AuthorityLifecycleState> {
        val handover = state.handover(proposal.binding) ?: return refused(state, RecoveryRefusal.INVALID_STATE)
        if (handover.preparation.binding != proposal.binding) return refused(state, RecoveryRefusal.STALE_TARGET)
        handover.completion?.let {
            return if (it == proposal) unchanged(state) else refused(state, RecoveryRefusal.IDENTITY_CONFLICT)
        }
        if (state.revision != expectedRevision) return refused(state, RecoveryRefusal.REVISION_CONFLICT)
        if (handover.phase != HandoverPhase.ACTIVATED || state.activeAuthority != proposal.binding.target) {
            return refused(state, RecoveryRefusal.INVALID_STATE)
        }
        if (!verifier.verifies(PlannedCompletionAssertion(proposal))) return refused(state, RecoveryRefusal.EVIDENCE_NOT_VERIFIED)
        return applied(state.replace(handover.copy(phase = HandoverPhase.COMPLETED, completion = proposal)))
    }

    public fun abort(
        state: AuthorityLifecycleState,
        expectedRevision: Revision,
        proposal: AbortHandover,
    ): RecoveryTransition<AuthorityLifecycleState> {
        val handover = state.handover(proposal.binding) ?: return refused(state, RecoveryRefusal.INVALID_STATE)
        if (handover.preparation.binding != proposal.binding) return refused(state, RecoveryRefusal.STALE_TARGET)
        if (handover.seal != null || handover.phase in setOf(HandoverPhase.SEALED, HandoverPhase.ACTIVATED, HandoverPhase.COMPLETED)) {
            return refused(state, RecoveryRefusal.POST_SEAL_ABORT_FORBIDDEN)
        }
        handover.abort?.let {
            return if (it == proposal) unchanged(state) else refused(state, RecoveryRefusal.IDENTITY_CONFLICT)
        }
        if (state.revision != expectedRevision) return refused(state, RecoveryRefusal.REVISION_CONFLICT)
        if (handover.phase !in setOf(HandoverPhase.PREPARING, HandoverPhase.DRAINING)) return refused(state, RecoveryRefusal.INVALID_STATE)
        if (!verifier.verifies(PlannedAbortAssertion(proposal))) return refused(state, RecoveryRefusal.EVIDENCE_NOT_VERIFIED)
        return applied(state.replace(handover.copy(phase = HandoverPhase.ABORTED, abort = proposal)))
    }

    public fun issueEmergency(
        state: AuthorityLifecycleState,
        expectedRevision: Revision,
        proposal: IssueEmergencyAppointment,
    ): RecoveryTransition<AuthorityLifecycleState> {
        state.emergency(proposal.binding)?.let {
            return if (it.issuance == proposal) unchanged(state) else refused(state, RecoveryRefusal.IDENTITY_CONFLICT)
        }
        validateNewBinding(state, expectedRevision, proposal.binding)?.let { return refused(state, it) }
        if (state.activeAuthority != null || state.hasPendingCeremony() || proposal.checkpoint != state.restoredFrom ||
            proposal.checkpoint.position.authority != proposal.binding.predecessor
        ) {
            return refused(state, RecoveryRefusal.INVALID_STATE)
        }
        // Target changes keep the same incident, and the old appointment must first be fenced/cancelled.
        if (state.emergencyAppointments.any { it.issuance.binding.predecessor == proposal.binding.predecessor &&
                it.issuance.binding.incidentId != proposal.binding.incidentId }) {
            return refused(state, RecoveryRefusal.IDENTITY_CONFLICT)
        }
        if (!verifier.verifies(EmergencyIssuanceAssertion(proposal))) return refused(state, RecoveryRefusal.EVIDENCE_NOT_VERIFIED)
        if (state.emergencyAppointments.size == MAX_RECOVERY_ITEMS) return refused(state, RecoveryRefusal.CAPACITY_REQUIRES_PROTECTED_ARCHIVE)
        return applied(state.copy(revision = state.revision.next(), emergencyAppointments = state.emergencyAppointments +
            EmergencyAppointment(proposal, EmergencyAppointmentPhase.ISSUED)))
    }

    public fun activateEmergency(
        state: AuthorityLifecycleState,
        expectedRevision: Revision,
        proposal: ActivateEmergencyAppointment,
    ): RecoveryTransition<AuthorityLifecycleState> {
        val appointment = state.emergency(proposal.binding) ?: return refused(state, RecoveryRefusal.INVALID_STATE)
        if (appointment.issuance.binding != proposal.binding) return refused(state, RecoveryRefusal.STALE_TARGET)
        appointment.activation?.let {
            return if (it == proposal) unchanged(state) else refused(state, RecoveryRefusal.IDENTITY_CONFLICT)
        }
        if (state.revision != expectedRevision) return refused(state, RecoveryRefusal.REVISION_CONFLICT)
        if (appointment.phase != EmergencyAppointmentPhase.ISSUED || state.activeAuthority != null) {
            return refused(state, RecoveryRefusal.INVALID_STATE)
        }
        if (proposal.checkpoint != appointment.issuance.checkpoint || proposal.issuanceEvidence != appointment.issuance.issuanceEvidence) {
            return refused(state, RecoveryRefusal.IDENTITY_CONFLICT)
        }
        if (!verifier.verifies(EmergencyActivationAssertion(appointment.issuance, proposal))) {
            return refused(state, RecoveryRefusal.EVIDENCE_NOT_VERIFIED)
        }
        physicalGate.assessActivation(PhysicalActivationAssessment(appointment.issuance, proposal)).refusal()?.let {
            return refused(state, it)
        }
        if (state.retiredAssignmentIds.size == MAX_RECOVERY_ITEMS) return refused(state, RecoveryRefusal.CAPACITY_REQUIRES_PROTECTED_ARCHIVE)
        return applied(state.replace(appointment.copy(phase = EmergencyAppointmentPhase.ACTIVATED, activation = proposal)).copy(
            activeAuthority = proposal.binding.target, lastKnownAuthority = proposal.binding.target,
            retiredAssignmentIds = state.retiredAssignmentIds + proposal.binding.predecessor.assignmentId, intentionallyStopped = false))
    }

    public fun cancelEmergency(
        state: AuthorityLifecycleState,
        expectedRevision: Revision,
        proposal: CancelEmergencyAppointment,
    ): RecoveryTransition<AuthorityLifecycleState> {
        val appointment = state.emergency(proposal.binding) ?: return refused(state, RecoveryRefusal.INVALID_STATE)
        if (appointment.issuance.binding != proposal.binding) return refused(state, RecoveryRefusal.STALE_TARGET)
        appointment.cancellation?.let {
            return if (it == proposal) unchanged(state) else refused(state, RecoveryRefusal.IDENTITY_CONFLICT)
        }
        if (state.revision != expectedRevision) return refused(state, RecoveryRefusal.REVISION_CONFLICT)
        if (appointment.phase != EmergencyAppointmentPhase.ISSUED || state.activeAuthority != null) {
            return refused(state, RecoveryRefusal.INVALID_STATE)
        }
        if (!verifier.verifies(EmergencyCancellationAssertion(proposal))) return refused(state, RecoveryRefusal.EVIDENCE_NOT_VERIFIED)
        physicalGate.assessCancellation(PhysicalCancellationAssessment(appointment.issuance, proposal)).refusal()?.let {
            return refused(state, it)
        }
        return applied(state.replace(appointment.copy(phase = EmergencyAppointmentPhase.CANCELLED, cancellation = proposal)))
    }

    public fun completeEmergency(
        state: AuthorityLifecycleState,
        expectedRevision: Revision,
        proposal: CompleteEmergencyAppointment,
    ): RecoveryTransition<AuthorityLifecycleState> {
        val appointment = state.emergency(proposal.binding) ?: return refused(state, RecoveryRefusal.INVALID_STATE)
        if (appointment.issuance.binding != proposal.binding) return refused(state, RecoveryRefusal.STALE_TARGET)
        appointment.completion?.let {
            return if (it == proposal) unchanged(state) else refused(state, RecoveryRefusal.IDENTITY_CONFLICT)
        }
        if (state.revision != expectedRevision) return refused(state, RecoveryRefusal.REVISION_CONFLICT)
        if (appointment.phase != EmergencyAppointmentPhase.ACTIVATED || state.activeAuthority != proposal.binding.target) {
            return refused(state, RecoveryRefusal.INVALID_STATE)
        }
        if (!verifier.verifies(EmergencyCompletionAssertion(proposal))) return refused(state, RecoveryRefusal.EVIDENCE_NOT_VERIFIED)
        return applied(state.replace(appointment.copy(phase = EmergencyAppointmentPhase.COMPLETED, completion = proposal)))
    }

    private fun validateNewBinding(
        state: AuthorityLifecycleState,
        expectedRevision: Revision,
        binding: AuthorityTransferBinding,
    ): RecoveryRefusal? {
        if (binding.branchId != state.branchId) return RecoveryRefusal.WRONG_BRANCH
        if (state.revision != expectedRevision) return RecoveryRefusal.REVISION_CONFLICT
        if (binding.predecessor != state.lastKnownAuthority || binding.predecessor.epoch.value == Long.MAX_VALUE ||
            binding.target.epoch.value != binding.predecessor.epoch.value + 1L ||
            binding.predecessor.assignmentId == binding.target.assignmentId || binding.predecessor.deviceId == binding.target.deviceId
        ) {
            return RecoveryRefusal.INVALID_AUTHORITY_CHAIN
        }
        if (state.handovers.any { it.preparation.binding.ceremonyId == binding.ceremonyId } ||
            state.emergencyAppointments.any { it.issuance.binding.ceremonyId == binding.ceremonyId } ||
            binding.target.assignmentId in state.retiredAssignmentIds ||
            state.handovers.any { it.preparation.binding.target.assignmentId == binding.target.assignmentId } ||
            state.emergencyAppointments.any { it.issuance.binding.target.assignmentId == binding.target.assignmentId }
        ) {
            return RecoveryRefusal.IDENTITY_CONFLICT
        }
        return null
    }
}

private fun AuthorityLifecycleState.handover(binding: AuthorityTransferBinding): PlannedHandover? =
    handovers.find { it.preparation.binding.ceremonyId == binding.ceremonyId }

private fun AuthorityLifecycleState.emergency(binding: AuthorityTransferBinding): EmergencyAppointment? =
    emergencyAppointments.find { it.issuance.binding.ceremonyId == binding.ceremonyId }

private fun AuthorityLifecycleState.hasPendingCeremony(): Boolean =
    handovers.any { it.phase !in setOf(HandoverPhase.COMPLETED, HandoverPhase.ABORTED) } ||
        emergencyAppointments.any { it.phase !in setOf(EmergencyAppointmentPhase.COMPLETED, EmergencyAppointmentPhase.CANCELLED) }

private fun AuthorityLifecycleState.replace(handover: PlannedHandover): AuthorityLifecycleState = copy(
    revision = revision.next(), handovers = handovers.map {
        if (it.preparation.binding.ceremonyId == handover.preparation.binding.ceremonyId) handover else it
    },
)

private fun AuthorityLifecycleState.replace(appointment: EmergencyAppointment): AuthorityLifecycleState = copy(
    revision = revision.next(), emergencyAppointments = emergencyAppointments.map {
        if (it.issuance.binding.ceremonyId == appointment.issuance.binding.ceremonyId) appointment else it
    },
)

private fun PhysicalRecoveryGateDecision.refusal(): RecoveryRefusal? = when (this) {
    PhysicalRecoveryGateDecision.EXCLUSIVE_CONTROLS_VERIFIED -> null
    PhysicalRecoveryGateDecision.OWNER_PROCEDURE_UNQUALIFIED -> RecoveryRefusal.OWNER_PHYSICAL_GATE_UNQUALIFIED
    PhysicalRecoveryGateDecision.OLD_WRITER_NOT_EXCLUDED -> RecoveryRefusal.OLD_WRITER_NOT_EXCLUDED
    PhysicalRecoveryGateDecision.COMPETING_SUCCESSOR_NOT_EXCLUDED -> RecoveryRefusal.COMPETING_SUCCESSOR_NOT_EXCLUDED
    PhysicalRecoveryGateDecision.ISSUER_LOST_OR_CLONED -> RecoveryRefusal.ISSUER_CUSTODY_UNCERTAIN
    PhysicalRecoveryGateDecision.TARGET_NOT_EXCLUDED -> RecoveryRefusal.TARGET_NOT_EXCLUDED
}
