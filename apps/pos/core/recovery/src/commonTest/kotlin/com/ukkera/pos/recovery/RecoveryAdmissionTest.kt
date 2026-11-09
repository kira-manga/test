package com.ukkera.pos.recovery

import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class RecoveryAdmissionTest {
    private val f = RecoveryFixtures
    private val machine = RecoveryAdmissionMachine(ModelEvidenceVerifier())

    private fun admission(state: RecoveryAdmissionState, resource: RecoveryResourceKey): ResourceAdmissionDecision =
        RecoveryAdmissionPolicy.evaluate(state, f.branch, setOf(resource, f.auth))

    @Test
    fun unknownTailHoldsEveryResourceIncludingFreshIdsAndUnknownAuthorization() {
        val restored = f.restored()
        for (kind in RecoveryResourceKind.entries) {
            for (freshId in listOf(0, 1000, 2000)) {
                assertEquals(ResourceAdmissionDecision.RESOURCE_RECONCILIATION_REQUIRED,
                    admission(restored, f.resource(kind, freshId)), "$kind/$freshId")
            }
        }
        assertEquals(ResourceAdmissionDecision.RESOURCE_RECONCILIATION_REQUIRED,
            RecoveryAdmissionPolicy.evaluate(restored, f.branch, setOf(f.auth)))
        assertEquals(ResourceAdmissionDecision.RESOURCE_FOOTPRINT_REQUIRED,
            RecoveryAdmissionPolicy.evaluate(restored, f.branch, emptySet()))
        assertEquals(ResourceAdmissionDecision.RESOURCE_FOOTPRINT_REQUIRED,
            RecoveryAdmissionPolicy.evaluate(restored, f.branch, setOf(f.stock)))
        assertEquals(ResourceAdmissionDecision.WRONG_BRANCH,
            RecoveryAdmissionPolicy.evaluate(restored, f.id(999), setOf(f.stock, f.auth)))
    }

    @Test
    fun healthyCheckoutHasNoStandbyOrCloudAcknowledgementGate() {
        val provisioned = RecoveryAdmissionState.initiallyProvisioned(f.branch)
        assertEquals(ResourceAdmissionDecision.ADMITTED, admission(provisioned, f.stock))
        assertEquals(ResourceAdmissionDecision.ADMITTED, admission(provisioned, f.refund))
    }

    @Test
    fun countReadmissionAndReestablishedAuthorizationAreSeparateDecisions() {
        val restored = f.restored()
        val reconciliation = f.reconciliation(restored, f.stock)
        val reconciled = machine.reconcile(restored, restored.revision, reconciliation)
        assertEquals(TransitionDisposition.APPLIED, reconciled.disposition)
        assertEquals(ResourceAdmissionDecision.RESOURCE_RECONCILIATION_REQUIRED, admission(reconciled.state, f.stock))
        val readmission = f.readmission(reconciled.state, reconciliation)
        val stockReadmitted = machine.readmit(reconciled.state, reconciled.state.revision, readmission).state
        assertEquals(ResourceAdmissionDecision.RESOURCE_RECONCILIATION_REQUIRED, admission(stockReadmitted, f.stock))
        val both = f.admit(machine, stockReadmitted, f.auth)
        assertEquals(ResourceAdmissionDecision.ADMITTED, admission(both, f.stock))
        assertEquals(ResourceAdmissionDecision.RESOURCE_RECONCILIATION_REQUIRED, admission(both, f.refund))
        assertEquals(ResourceAdmissionDecision.RESOURCE_RECONCILIATION_REQUIRED,
            admission(both, f.resource(RecoveryResourceKind.STOCK, 5000)))
        val stillIncomplete = f.incompleteHistory()
        assertEquals(OperationOutcomeClassification.HISTORY_INCOMPLETE,
            OperationOutcomePolicy(ModelEvidenceVerifier()).classify(f.identity, null, stillIncomplete, f.lookup(stillIncomplete)))
    }

    @Test
    fun everyResourceHasItsOwnReconciliationKindAndCannotBorrowStockCount() {
        val initial = f.restored()
        for (kind in RecoveryResourceKind.entries.filter { it != RecoveryResourceKind.STOCK }) {
            val proposal = f.reconciliation(initial, f.resource(kind)).copy(kind = ResourceReconciliationKind.STOCK_COUNT)
            val result = machine.reconcile(initial, initial.revision, proposal)
            assertEquals(RecoveryRefusal.RECONCILIATION_KIND_MISMATCH, result.refusal, kind.name)
            assertEquals(initial, result.state)
        }
        val fakeAuthAdjustment = f.reconciliation(initial, f.auth, adjustment =
            RecoveryAdjustment(f.id(999), f.auth, RecoveryEffectUnit.SOURCE_ENTITLEMENT_UNITS, 1))
        assertEquals(RecoveryRefusal.RECONCILIATION_KIND_MISMATCH,
            machine.reconcile(initial, initial.revision, fakeAuthAdjustment).refusal)
    }

    @Test
    fun defaultEvidenceRejectsReadmissionAndClaimsPreserveOriginalActorAndObligation() {
        val obligation = PreservedRecoveryObligation(f.id(1000), f.refund, PreservedObligationKind.CUSTOMER_REFUND_DUE,
            f.cashier, f.identity.operationId, listOf(f.evidence(1001)))
        val initial = f.restored(listOf(obligation))
        val proposal = f.reconciliation(initial, f.refund)
        assertEquals(RecoveryRefusal.EVIDENCE_NOT_VERIFIED,
            RecoveryAdmissionMachine().reconcile(initial, initial.revision, proposal).refusal)
        assertEquals(RecoveryRefusal.OBLIGATIONS_NOT_ACKNOWLEDGED,
            machine.reconcile(initial, initial.revision, proposal.copy(acknowledgedObligationIds = emptySet())).refusal)
        val reconciled = machine.reconcile(initial, initial.revision, proposal).state
        val readmission = f.readmission(reconciled, proposal)
        assertEquals(RecoveryRefusal.OBLIGATIONS_NOT_ACKNOWLEDGED,
            machine.readmit(reconciled, reconciled.revision, readmission.copy(acknowledgedObligationIds = emptySet())).refusal)
        assertEquals(RecoveryRefusal.EVIDENCE_NOT_VERIFIED,
            RecoveryAdmissionMachine().readmit(reconciled, reconciled.revision, readmission).refusal)
        val admitted = machine.readmit(reconciled, reconciled.revision, readmission).state
        assertEquals(listOf(obligation), admitted.obligations)
        assertEquals(f.cashier, admitted.obligations.single().originalActorId)
        assertEquals(f.approver, admitted.reconciliations.single().approvingActorId)
        assertEquals(f.identity.operationId, admitted.obligations.single().originalOperationId)
        assertEquals(PreservedObligationKind.CUSTOMER_REFUND_DUE, admitted.obligations.single().kind)
    }

    @Test
    fun lostReplyReconciliationRetriesAreIdempotentButChangedIdentityOrRevisionIsNot() {
        val initial = f.restored()
        val proposal = f.reconciliation(initial, f.stock)
        val committed = machine.reconcile(initial, initial.revision, proposal).state
        assertEquals(TransitionDisposition.IDEMPOTENT, machine.reconcile(committed, initial.revision, proposal).disposition)
        assertEquals(RecoveryRefusal.IDENTITY_CONFLICT,
            machine.reconcile(committed, committed.revision, proposal.copy(approvingActorId = f.id(999))).refusal)
        assertEquals(RecoveryRefusal.REVISION_CONFLICT,
            machine.reconcile(committed, initial.revision, f.reconciliation(committed, f.auth)).refusal)
        val readmission = f.readmission(committed, proposal)
        val readmitted = machine.readmit(committed, committed.revision, readmission).state
        assertEquals(TransitionDisposition.IDEMPOTENT, machine.readmit(readmitted, committed.revision, readmission).disposition)
        assertEquals(RecoveryRefusal.IDENTITY_CONFLICT,
            machine.readmit(readmitted, readmitted.revision, readmission.copy(resource = f.refund)).refusal)
        assertEquals(1, readmitted.readmissions.size)
        assertEquals(1, readmitted.reconciliations.size)
    }

    @Test
    fun qualifiedScopeMayProveIndependenceButNeverRestoresCopiedGrants() {
        val initial = f.restored()
        val declaration = BoundedRecoveryScope(f.id(1000), f.branch, f.incident, f.checkpoint,
            listOf(RecoveryResourceSelector.Kind(RecoveryResourceKind.REFUND_ENTITLEMENT)), f.approver,
            f.evidence(1001), listOf(f.evidence(1002)))
        assertEquals(RecoveryRefusal.EVIDENCE_NOT_VERIFIED,
            RecoveryAdmissionMachine().boundAffectedScope(initial, initial.revision, declaration).refusal)
        val bounded = machine.boundAffectedScope(initial, initial.revision, declaration).state
        assertEquals(ResourceAdmissionDecision.RESOURCE_RECONCILIATION_REQUIRED, admission(bounded, f.stock))
        val authReestablished = f.admit(machine, bounded, f.auth)
        assertEquals(ResourceAdmissionDecision.ADMITTED, admission(authReestablished, f.stock))
        assertEquals(ResourceAdmissionDecision.RESOURCE_RECONCILIATION_REQUIRED, admission(authReestablished, f.refund))
        assertEquals(TransitionDisposition.IDEMPOTENT,
            machine.boundAffectedScope(authReestablished, initial.revision, declaration).disposition)
        assertEquals(RecoveryRefusal.IDENTITY_CONFLICT, machine.boundAffectedScope(authReestablished,
            authReestablished.revision, declaration.copy(affectedSelectors = emptyList())).refusal)
    }

    @Test
    fun lateTailMatchingDoesNotApplyStockEffectAgainOrRewriteOriginalActor() {
        val initial = f.restored()
        val adjustment = RecoveryAdjustment(f.id(1200), f.stock, RecoveryEffectUnit.QUANTITY_MICRO_UNITS, -2_000_000)
        val reconciliation = f.reconciliation(initial, f.stock, adjustment = adjustment)
        val reconciled = machine.reconcile(initial, initial.revision, reconciliation).state
        // Independent model ledger: source stock 20, counted stock 18, one recovery adjustment.
        val postedAdjustments = reconciled.reconciliations.mapNotNull { it.adjustment }.associateBy { it.adjustmentId }
        val ledgerQuantityBeforeTail = 20_000_000L + postedAdjustments.values.sumOf { it.delta }
        val tail = f.lateTail().let { it.copy(matches = listOf(LateEffectAdjustmentMatch(
            it.operation.effects.single().effectId, reconciliation.reconciliationId, adjustment.adjustmentId, f.evidence(1201)))) }
        val result = machine.recordLateTail(reconciled, reconciled.revision, tail)
        assertEquals(TransitionDisposition.APPLIED, result.disposition)
        assertEquals(emptySet(), result.state.lateTail.single().unmatchedEffectIds)
        assertEquals(reconciled.reconciliations, result.state.reconciliations)
        assertEquals(reconciled.holds, result.state.holds)
        assertEquals(18_000_000L, ledgerQuantityBeforeTail)
        val after = 20_000_000L + result.state.reconciliations.mapNotNull { it.adjustment }.distinctBy { it.adjustmentId }.sumOf { it.delta }
        assertEquals(18_000_000L, after, "Late evidence must not replay another -2 units")
        assertEquals(f.cashier, result.state.lateTail.single().proposal.operation.identity.originalActorId)
        val retried = machine.recordLateTail(result.state, reconciled.revision, tail.copy(ingestId = f.id(1299)))
        assertEquals(TransitionDisposition.IDEMPOTENT, retried.disposition)
        assertEquals(1, retried.state.lateTail.size)
        assertEquals(1, retried.state.reconciliations.size)
    }

    @Test
    fun lateMatchRequiresExactMeasureDeltaSourceAndPreviouslyUnusedAdjustment() {
        val initial = f.restored()
        val adjustment = RecoveryAdjustment(f.id(1200), f.stock, RecoveryEffectUnit.QUANTITY_MICRO_UNITS, -2_000_000)
        val reconciliation = f.reconciliation(initial, f.stock, adjustment = adjustment)
        val reconciled = machine.reconcile(initial, initial.revision, reconciliation).state
        val tail = f.lateTail().let { it.copy(matches = listOf(LateEffectAdjustmentMatch(
            it.operation.effects.single().effectId, reconciliation.reconciliationId, adjustment.adjustmentId, f.evidence(1201)))) }
        for (effect in listOf(
            tail.operation.effects.single().copy(delta = -1_000_000),
            tail.operation.effects.single().copy(unit = RecoveryEffectUnit.EGP_MINOR_UNITS),
            tail.operation.effects.single().copy(resource = f.cash),
        )) {
            assertEquals(RecoveryRefusal.IDENTITY_CONFLICT, machine.recordLateTail(reconciled, reconciled.revision,
                tail.copy(operation = tail.operation.copy(effects = listOf(effect)))).refusal)
        }
        val matched = machine.recordLateTail(reconciled, reconciled.revision, tail).state
        val differentSource = f.lateTail(n = 1300).let { it.copy(matches = listOf(LateEffectAdjustmentMatch(
            it.operation.effects.single().effectId, reconciliation.reconciliationId, adjustment.adjustmentId, f.evidence(1305)))) }
        assertEquals(RecoveryRefusal.IDENTITY_CONFLICT,
            machine.recordLateTail(matched, matched.revision, differentSource).refusal)
        assertEquals(RecoveryRefusal.IDENTITY_CONFLICT, machine.reconcile(matched, matched.revision,
            f.reconciliation(matched, f.stock, n = 1400, adjustment = adjustment)).refusal)
    }

    @Test
    fun unmatchedLateCashReholdsResourceAndCannotReuseAnEarlierReconciliation() {
        var state = f.admit(machine, f.restored(), f.cash)
        state = f.admit(machine, state, f.auth)
        assertEquals(ResourceAdmissionDecision.ADMITTED, admission(state, f.cash))
        val oldReconciliation = state.reconciliations.single { it.resource == f.cash }
        val tail = f.lateTail(f.cash, -11_400, RecoveryEffectUnit.EGP_MINOR_UNITS)
        val late = machine.recordLateTail(state, state.revision, tail).state
        assertEquals(ResourceAdmissionDecision.RESOURCE_RECONCILIATION_REQUIRED, admission(late, f.cash))
        assertEquals(setOf(tail.operation.effects.single().effectId), late.lateTail.single().unmatchedEffectIds)
        assertEquals(RecoveryRefusal.RESOURCE_RECONCILIATION_REQUIRED, machine.readmit(late, late.revision,
            f.readmission(late, oldReconciliation, n = 1999)).refusal)
        val verifier = ModelEvidenceVerifier()
        val renewed = f.reconciliation(late, f.cash, n = 2000)
        val reconciled = RecoveryAdmissionMachine(verifier).reconcile(late, late.revision, renewed).state
        assertEquals(listOf(tail.operation.effects.single()),
            (verifier.assertions.single() as ResourceReconciliationAssertion).unmatchedLateEffects)
        assertEquals(ResourceAdmissionDecision.RESOURCE_RECONCILIATION_REQUIRED, admission(reconciled, f.cash))
        val readmitted = machine.readmit(reconciled, reconciled.revision, f.readmission(reconciled, renewed, n = 2200)).state
        assertEquals(ResourceAdmissionDecision.ADMITTED, admission(readmitted, f.cash))
    }

    @Test
    fun conflictingLateContentIsQuarantinedAndHeldWithoutOverwritingOriginalEvidence() {
        var state = f.admit(machine, f.restored(), f.stock)
        state = f.admit(machine, state, f.auth)
        val tail = f.lateTail()
        val original = machine.recordLateTail(state, state.revision, tail).state
        val changed = tail.copy(ingestId = f.id(2200), operation = tail.operation.copy(recordSha256 = f.hash(2201),
            identity = tail.operation.identity.copy(originalActorId = f.approver)))
        val result = machine.recordLateTail(original, original.revision, changed)
        assertEquals(TransitionDisposition.APPLIED, result.disposition)
        assertEquals(listOf(changed), result.state.quarantinedTail)
        assertEquals(tail, result.state.lateTail.single().proposal)
        assertEquals(f.cashier, result.state.lateTail.single().proposal.operation.identity.originalActorId)
        assertTrue(result.state.holds.last().selectors.contains(RecoveryResourceSelector.WholeBranch))
        assertEquals(ResourceAdmissionDecision.RESOURCE_RECONCILIATION_REQUIRED,
            RecoveryAdmissionPolicy.evaluate(result.state, f.branch, setOf(f.auth)))
        assertEquals(TransitionDisposition.IDEMPOTENT,
            machine.recordLateTail(result.state, original.revision, changed).disposition)
    }

    @Test
    fun illegitimatePostRetirementTailIsNotAcceptedAsAuthorizedHistory() {
        val verifier = ModelEvidenceVerifier { assertion ->
            assertion !is HistoricalTailOriginAssertion || assertion.proposal.operation.origin.sequence.value <= 6
        }
        val machine = RecoveryAdmissionMachine(verifier)
        val initial = f.restored()
        val legitimate = f.lateTail()
        assertEquals(TransitionDisposition.APPLIED, machine.recordLateTail(initial, initial.revision, legitimate).disposition)
        val forbidden = legitimate.copy(operation = legitimate.operation.copy(origin =
            legitimate.operation.origin.copy(sequence = JournalSequence(7))))
        val result = machine.recordLateTail(initial, initial.revision, forbidden)
        assertEquals(RecoveryRefusal.EVIDENCE_NOT_VERIFIED, result.refusal)
        assertEquals(initial, result.state)
    }

    @Test
    fun readmissionAndLateTailRacePermutationsAlwaysLeaveNewUncertaintyHeld() {
        for (schedule in permutations(listOf("readmit", "late"))) {
            val initial = f.restored()
            val reconciliation = f.reconciliation(initial, f.cash)
            var state = machine.reconcile(initial, initial.revision, reconciliation).state
            state = f.admit(machine, state, f.auth)
            val readmission = f.readmission(state, reconciliation)
            val initialRevision = state.revision
            val tail = f.lateTail(f.cash, -11_400, RecoveryEffectUnit.EGP_MINOR_UNITS)
            for (step in schedule) {
                state = if (step == "readmit") machine.readmit(state, initialRevision, readmission).state
                else machine.recordLateTail(state, state.revision, tail).state
            }
            assertEquals(ResourceAdmissionDecision.RESOURCE_RECONCILIATION_REQUIRED, admission(state, f.cash), schedule.toString())
            assertEquals(1, state.lateTail.size)
            assertEquals(2, state.holds.size)
        }
    }

    @Test
    fun persistedStateRoundTripKeepsObligationsHoldsMatchesAndExactSignedAmounts() {
        val obligation = PreservedRecoveryObligation(f.id(3000), f.cash, PreservedObligationKind.UNRESOLVED_EXTERNAL_PAYMENT,
            f.cashier, f.identity.operationId, listOf(f.evidence(3001)))
        var state = f.restored(listOf(obligation))
        val adjustment = RecoveryAdjustment(f.id(3002), f.cash, RecoveryEffectUnit.EGP_MINOR_UNITS, Long.MIN_VALUE)
        val reconciliation = f.reconciliation(state, f.cash, adjustment = adjustment)
        state = machine.reconcile(state, state.revision, reconciliation).state
        val encoded = Json.encodeToString(state)
        assertTrue(encoded.contains("\"delta\":\"-9223372036854775808\""))
        assertEquals(state, Json.decodeFromString<RecoveryAdmissionState>(encoded))
        assertEquals(ResourceAdmissionDecision.RESOURCE_RECONCILIATION_REQUIRED,
            admission(Json.decodeFromString(encoded), f.cash))
        assertNotNull(state.sourceCheckpoint)
        assertFalse(encoded.contains("bearerToken"))
    }

    @Test
    fun branchIncidentAndRevisionCannotBeChangedToReuseRecoveryDecisions() {
        val initial = f.restored()
        val valid = f.reconciliation(initial, f.stock)
        assertEquals(RecoveryRefusal.WRONG_BRANCH,
            machine.reconcile(initial, initial.revision, valid.copy(branchId = f.id(999))).refusal)
        assertEquals(RecoveryRefusal.INVALID_STATE,
            machine.reconcile(initial, initial.revision, valid.copy(incidentId = f.id(999))).refusal)
        assertEquals(RecoveryRefusal.REVISION_CONFLICT,
            machine.reconcile(initial, Revision(99), valid).refusal)
    }
}
