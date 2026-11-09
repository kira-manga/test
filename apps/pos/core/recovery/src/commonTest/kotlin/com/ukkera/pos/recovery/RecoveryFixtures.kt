package com.ukkera.pos.recovery

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision

/** L1 model evidence ONLY. This is not a backup verifier or qualified physical fencing. */
internal class ModelEvidenceVerifier(
    private val accepts: (RecoveryEvidenceAssertion) -> Boolean = { true },
) : RecoveryEvidenceVerifier {
    val assertions = mutableListOf<RecoveryEvidenceAssertion>()

    override fun verify(assertion: RecoveryEvidenceAssertion): EvidenceVerification {
        assertions += assertion
        return if (accepts(assertion)) EvidenceVerification.VERIFIED else EvidenceVerification.REFUSED
    }
}

/** Abstract environmental assumption used to test the reducer, not real owner approval. */
internal class ModelPhysicalGate(
    var activation: PhysicalRecoveryGateDecision = PhysicalRecoveryGateDecision.EXCLUSIVE_CONTROLS_VERIFIED,
    var cancellation: PhysicalRecoveryGateDecision = PhysicalRecoveryGateDecision.EXCLUSIVE_CONTROLS_VERIFIED,
) : PhysicalRecoveryGate {
    val activationRequests = mutableListOf<PhysicalActivationAssessment>()
    val cancellationRequests = mutableListOf<PhysicalCancellationAssessment>()

    override fun assessActivation(assessment: PhysicalActivationAssessment): PhysicalRecoveryGateDecision {
        activationRequests += assessment
        return activation
    }

    override fun assessCancellation(assessment: PhysicalCancellationAssessment): PhysicalRecoveryGateDecision {
        cancellationRequests += assessment
        return cancellation
    }
}

internal object RecoveryFixtures {
    fun id(number: Int): DomainId = DomainId("00000000-0000-4000-8000-${number.toString(16).padStart(12, '0')}")
    fun operationId(number: Int): OperationId = OperationId(id(number).value)
    fun hash(number: Int): String = number.toString(16).padStart(64, '0')
    fun evidence(number: Int): RecoveryEvidenceRef = RecoveryEvidenceRef(id(number), hash(number))

    val branch = id(1)
    val cashier = id(2)
    val approver = id(3)
    val incident = id(4)
    val transaction = id(5)
    val old = AuthorityIdentity(id(10), id(11), Revision(1))
    val target = AuthorityIdentity(id(12), id(13), Revision(2))
    val otherTarget = AuthorityIdentity(id(14), id(15), Revision(2))
    val checkpoint = checkpoint(old, 5)
    val identity = OperationIdentity(branch, operationId(20), cashier, 1, hash(21))
    val auth = RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, cashier)
    val stock = resource(RecoveryResourceKind.STOCK)
    val refund = resource(RecoveryResourceKind.REFUND_ENTITLEMENT)
    val cash = resource(RecoveryResourceKind.CASH_CUSTODY)
    val binding = AuthorityTransferBinding(branch, id(30), incident, old, target)

    fun checkpoint(authority: AuthorityIdentity, sequence: Long): RecoveryCheckpoint = RecoveryCheckpoint(
        id(40 + authority.epoch.value.toInt()), JournalPosition(branch, authority, JournalSequence(sequence)), hash(41),
    )

    fun range(first: Long, last: Long): AvailableHistoryRange = AvailableHistoryRange(
        SequenceRange(JournalSequence(first), JournalSequence(last)), AvailableHistorySource.VERIFIED_LOCAL_INDEX, evidence(45),
    )

    fun healthyHistory(cut: Long = 5): HistoryCoverage {
        val checkpoint = checkpoint(old, cut)
        return HistoryCoverage(branch, listOf(EpochHistoryCoverage(
            old, null, checkpoint, if (cut == 0L) emptyList() else listOf(range(1, cut)), emptyList(), emptyList(),
            HistoryTail.LiveWriterHead(transaction, evidence(46)),
        )), evidence(47))
    }

    fun incompleteHistory(): HistoryCoverage = healthyHistory().let {
        it.copy(epochs = listOf(it.epochs.single().copy(tail = HistoryTail.Unknown)))
    }

    fun lookup(coverage: HistoryCoverage, operationId: OperationId = identity.operationId): CoordinatedLookupObservation =
        CoordinatedLookupObservation(branch, operationId, transaction, coverage.epochs.last().authority,
            coverage.epochs.last().checkpoint, evidence(48))

    fun known(state: KnownOperationState = KnownOperationState.COMMITTED): KnownOperationMetadata = KnownOperationMetadata(
        identity, state, if (state == KnownOperationState.IN_PROGRESS) null else checkpoint.position, hash(49), evidence(50),
    )

    fun resource(kind: RecoveryResourceKind, extra: Int = 0): RecoveryResourceKey =
        RecoveryResourceKey(kind, id(100 + kind.ordinal + extra))

    fun restored(obligations: List<PreservedRecoveryObligation> = emptyList()): RecoveryAdmissionState =
        RecoveryAdmissionMachine().restore(branch, incident, checkpoint, obligations)

    fun reconciliation(
        state: RecoveryAdmissionState,
        resource: RecoveryResourceKey,
        n: Int = 200 + resource.kind.ordinal,
        adjustment: RecoveryAdjustment? = null,
    ): ResourceReconciliation = ResourceReconciliation(
        id(n), incident, branch, checkpoint, resource,
        ResourceReconciliationKind.entries.single { it.resourceKind == resource.kind }, approver,
        evidence(n + 1000), listOf(evidence(n + 2000)),
        state.obligations.filter { it.resource == resource }.map { it.obligationId }.toSet(),
        state.holds.filter { it.matches(resource) }.map { it.holdId }.toSet(), adjustment,
    )

    fun readmission(state: RecoveryAdmissionState, reconciliation: ResourceReconciliation, n: Int = 400 + reconciliation.resource.kind.ordinal): ResourceReadmission =
        ResourceReadmission(id(n), incident, branch, reconciliation.resource, reconciliation.reconciliationId, approver,
            evidence(n + 1000), state.obligations.filter { it.resource == reconciliation.resource }.map { it.obligationId }.toSet(),
            state.holds.filter { it.matches(reconciliation.resource) }.map { it.holdId }.toSet())

    fun admit(
        machine: RecoveryAdmissionMachine,
        initial: RecoveryAdmissionState,
        resource: RecoveryResourceKey,
        n: Int = 200 + resource.kind.ordinal,
    ): RecoveryAdmissionState {
        val reconciliation = reconciliation(initial, resource, n)
        val reconciled = machine.reconcile(initial, initial.revision, reconciliation).state
        return machine.readmit(reconciled, reconciled.revision, readmission(reconciled, reconciliation, n + 200)).state
    }

    fun lateTail(
        resource: RecoveryResourceKey = stock,
        delta: Long = -2_000_000,
        unit: RecoveryEffectUnit = RecoveryEffectUnit.QUANTITY_MICRO_UNITS,
        n: Int = 600,
    ): LateTailProposal = LateTailProposal(id(n), branch, incident, LateHistoricalOperation(
        identity.copy(operationId = operationId(n + 1)), checkpoint.position.copy(sequence = JournalSequence(6)), hash(n + 2),
        listOf(HistoricalRecoveryEffect(id(n + 3), resource, unit, delta)), evidence(n + 4),
    ), emptyList())

    fun prepare(binding: AuthorityTransferBinding = this.binding): PrepareHandover =
        PrepareHandover(binding, approver, evidence(700), evidence(701))
    fun drain(binding: AuthorityTransferBinding = this.binding): DrainHandover = DrainHandover(binding, evidence(702))
    fun seal(binding: AuthorityTransferBinding = this.binding): SealHandover =
        SealHandover(binding, checkpoint, evidence(703), evidence(704), evidence(705))
    fun activate(binding: AuthorityTransferBinding = this.binding): ActivateHandover =
        ActivateHandover(binding, checkpoint, evidence(705), evidence(706))
    fun complete(binding: AuthorityTransferBinding = this.binding): CompleteHandover = CompleteHandover(binding, evidence(707))
    fun abort(binding: AuthorityTransferBinding = this.binding): AbortHandover = AbortHandover(binding, evidence(708), evidence(709))
    fun emergency(binding: AuthorityTransferBinding = this.binding): IssueEmergencyAppointment =
        IssueEmergencyAppointment(binding, checkpoint, approver, evidence(710), evidence(711), evidence(712))
    fun emergencyActivation(binding: AuthorityTransferBinding = this.binding): ActivateEmergencyAppointment =
        ActivateEmergencyAppointment(binding, checkpoint, evidence(712), evidence(713), PhysicalRecoveryEvidenceClaim(
            evidence(714), evidence(715), evidence(716), evidence(717), evidence(718), evidence(719)))
    fun cancellation(binding: AuthorityTransferBinding = this.binding): CancelEmergencyAppointment =
        CancelEmergencyAppointment(binding, evidence(720), evidence(721))
}

internal fun <T> permutations(items: List<T>): List<List<T>> = if (items.isEmpty()) listOf(emptyList()) else
    items.flatMapIndexed { index, value -> permutations(items.filterIndexed { i, _ -> i != index }).map { listOf(value) + it } }
