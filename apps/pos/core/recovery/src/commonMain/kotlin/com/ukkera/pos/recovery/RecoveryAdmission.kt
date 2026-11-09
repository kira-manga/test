package com.ukkera.pos.recovery

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.MinorUnitsSerializer
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.Serializable

@Serializable
public enum class RecoveryResourceKind {
    STOCK,
    REFUND_ENTITLEMENT,
    DEPOSIT_ENTITLEMENT,
    EXCHANGE_OFFSET,
    CASH_CUSTODY,
    PO_ALLOWANCE,
    SUPPLIER_SETTLEMENT,
    KITCHEN_CUSTODY,
    ORDER_CUSTODY,
    PURCHASE_CUSTODY,
    AUTHORIZATION,
}

@Serializable
public data class RecoveryResourceKey(
    public val kind: RecoveryResourceKind,
    public val resourceId: DomainId,
)

@Serializable
public sealed interface RecoveryResourceSelector {
    @Serializable
    public data object WholeBranch : RecoveryResourceSelector

    @Serializable
    public data class Kind(public val kind: RecoveryResourceKind) : RecoveryResourceSelector

    @Serializable
    public data class Resource(public val resource: RecoveryResourceKey) : RecoveryResourceSelector
}

@Serializable
public enum class RecoveryHoldReason {
    RESTORED_STATE_NOT_TRUSTWORTHY,
    UNMATCHED_LATE_EFFECT,
    CONFLICTING_LATE_EVIDENCE,
}

@Serializable
public data class RecoveryResourceHold(
    public val holdId: DomainId,
    public val selectors: List<RecoveryResourceSelector>,
    public val reason: RecoveryHoldReason,
    public val createdRevision: Revision,
) {
    init {
        require(selectors.isNotEmpty() && selectors.size <= MAX_RECOVERY_ITEMS)
    }
}

@Serializable
public enum class PreservedObligationKind {
    UNRESOLVED_EXTERNAL_PAYMENT,
    CUSTOMER_REFUND_DUE,
    CLAIM_OR_LEGAL_DEADLINE,
    CASH_OR_COURIER_CUSTODY,
    SUPPLIER_PAYABLE_OR_CLAIM,
    UNCERTAIN_SIDE_EFFECT,
}

/** No reducer deletes an obligation, pays it, changes its actor, or turns it into credit. */
@Serializable
public data class PreservedRecoveryObligation(
    public val obligationId: DomainId,
    public val resource: RecoveryResourceKey,
    public val kind: PreservedObligationKind,
    public val originalActorId: DomainId,
    public val originalOperationId: OperationId?,
    public val evidence: List<RecoveryEvidenceRef>,
) {
    init {
        require(evidence.isNotEmpty() && evidence.size <= MAX_EVIDENCE_REFS)
    }
}

@Serializable
public enum class ResourceReconciliationKind(public val resourceKind: RecoveryResourceKind) {
    STOCK_COUNT(RecoveryResourceKind.STOCK),
    REFUND_SOURCE_ENTITLEMENT(RecoveryResourceKind.REFUND_ENTITLEMENT),
    DEPOSIT_SOURCE_ENTITLEMENT(RecoveryResourceKind.DEPOSIT_ENTITLEMENT),
    EXCHANGE_SOURCE_ALLOCATION(RecoveryResourceKind.EXCHANGE_OFFSET),
    CASH_COUNT_AND_CUSTODY(RecoveryResourceKind.CASH_CUSTODY),
    PO_SOURCE_ALLOCATION(RecoveryResourceKind.PO_ALLOWANCE),
    SUPPLIER_PAYMENT_AND_CLAIM(RecoveryResourceKind.SUPPLIER_SETTLEMENT),
    KITCHEN_ISSUE_AND_CUSTODY(RecoveryResourceKind.KITCHEN_CUSTODY),
    ORDER_OWNERSHIP_AND_OBLIGATION(RecoveryResourceKind.ORDER_CUSTODY),
    PURCHASE_INSPECTION_AND_CUSTODY(RecoveryResourceKind.PURCHASE_CUSTODY),
    LOCAL_AUTHORIZATION_REESTABLISHMENT(RecoveryResourceKind.AUTHORIZATION),
}

@Serializable
public enum class RecoveryEffectUnit {
    EGP_MINOR_UNITS,
    QUANTITY_MICRO_UNITS,
    SOURCE_ENTITLEMENT_UNITS,
}

/**
 * A reference to an independently audited, exact opening/reconciliation adjustment.
 * This reducer never posts the adjustment: its ledger post and this record must be
 * one protected store transaction. The same adjustment cannot represent two tails.
 */
@Serializable
public data class RecoveryAdjustment(
    public val adjustmentId: DomainId,
    public val resource: RecoveryResourceKey,
    public val unit: RecoveryEffectUnit,
    @Serializable(with = MinorUnitsSerializer::class)
    public val delta: Long,
)

@Serializable
public data class ResourceReconciliation(
    public val reconciliationId: DomainId,
    public val incidentId: DomainId,
    public val branchId: DomainId,
    public val sourceCheckpoint: RecoveryCheckpoint,
    public val resource: RecoveryResourceKey,
    public val kind: ResourceReconciliationKind,
    public val approvingActorId: DomainId,
    public val decisionEvidence: RecoveryEvidenceRef,
    public val supportingEvidence: List<RecoveryEvidenceRef>,
    public val acknowledgedObligationIds: Set<DomainId>,
    public val observedHoldIds: Set<DomainId>,
    public val adjustment: RecoveryAdjustment?,
) {
    init {
        require(supportingEvidence.isNotEmpty() && supportingEvidence.size < MAX_EVIDENCE_REFS)
        require(acknowledgedObligationIds.size <= MAX_RECOVERY_ITEMS)
        require(observedHoldIds.isNotEmpty() && observedHoldIds.size <= MAX_RECOVERY_ITEMS)
    }
}

@Serializable
public data class ResourceReadmission(
    public val readmissionId: DomainId,
    public val incidentId: DomainId,
    public val branchId: DomainId,
    public val resource: RecoveryResourceKey,
    public val reconciliationId: DomainId,
    public val approvingActorId: DomainId,
    public val decisionEvidence: RecoveryEvidenceRef,
    public val acknowledgedObligationIds: Set<DomainId>,
    public val coveredHoldIds: Set<DomainId>,
) {
    init {
        require(acknowledgedObligationIds.size <= MAX_RECOVERY_ITEMS)
        require(coveredHoldIds.isNotEmpty() && coveredHoldIds.size <= MAX_RECOVERY_ITEMS)
    }
}

/** A qualified bound on the missing tail; a list of known pending clients is insufficient. */
@Serializable
public data class BoundedRecoveryScope(
    public val declarationId: DomainId,
    public val branchId: DomainId,
    public val incidentId: DomainId,
    public val sourceCheckpoint: RecoveryCheckpoint,
    public val affectedSelectors: List<RecoveryResourceSelector>,
    public val approvingActorId: DomainId,
    public val decisionEvidence: RecoveryEvidenceRef,
    public val supportingEvidence: List<RecoveryEvidenceRef>,
) {
    init {
        require(affectedSelectors.size <= MAX_RECOVERY_ITEMS)
        require(supportingEvidence.isNotEmpty() && supportingEvidence.size < MAX_EVIDENCE_REFS)
    }
}

@Serializable
public data class HistoricalRecoveryEffect(
    public val effectId: DomainId,
    public val resource: RecoveryResourceKey,
    public val unit: RecoveryEffectUnit,
    @Serializable(with = MinorUnitsSerializer::class)
    public val delta: Long,
)

@Serializable
public data class LateHistoricalOperation(
    public val identity: OperationIdentity,
    public val origin: JournalPosition,
    public val recordSha256: String,
    public val effects: List<HistoricalRecoveryEffect>,
    public val originEvidence: RecoveryEvidenceRef,
) {
    init {
        requireRecoveryHash(recordSha256)
        require(effects.isNotEmpty() && effects.size <= MAX_RECOVERY_ITEMS)
        require(effects.map { it.effectId }.toSet().size == effects.size)
    }
}

/** An exact one-effect/one-adjustment match, not permission to replay either effect. */
@Serializable
public data class LateEffectAdjustmentMatch(
    public val effectId: DomainId,
    public val reconciliationId: DomainId,
    public val adjustmentId: DomainId,
    public val correspondenceEvidence: RecoveryEvidenceRef,
)

@Serializable
public data class LateTailProposal(
    public val ingestId: DomainId,
    public val branchId: DomainId,
    public val incidentId: DomainId,
    public val operation: LateHistoricalOperation,
    public val matches: List<LateEffectAdjustmentMatch>,
) {
    init {
        require(matches.size <= MAX_RECOVERY_ITEMS)
        require(matches.map { it.effectId }.toSet().size == matches.size)
        require(matches.map { it.adjustmentId }.toSet().size == matches.size)
    }
}

@Serializable
public data class RecordedLateTail(
    public val proposal: LateTailProposal,
    public val unmatchedEffectIds: Set<DomainId>,
)

/**
 * Protected persistence shape. A backup must enter [RecoveryAdmissionMachine.restore],
 * never deserialize this record and resume a copied healthy/readmitted runtime.
 * The bounded in-memory form is not a durable store or a full-state recovery package.
 */
@Serializable
public data class RecoveryAdmissionState(
    public val branchId: DomainId,
    public val revision: Revision,
    public val incidentId: DomainId?,
    public val sourceCheckpoint: RecoveryCheckpoint?,
    public val holds: List<RecoveryResourceHold>,
    public val obligations: List<PreservedRecoveryObligation>,
    public val scopeDeclaration: BoundedRecoveryScope?,
    public val reconciliations: List<ResourceReconciliation>,
    public val readmissions: List<ResourceReadmission>,
    public val lateTail: List<RecordedLateTail>,
    public val quarantinedTail: List<LateTailProposal>,
) {
    init {
        require((incidentId == null) == (sourceCheckpoint == null))
        require(sourceCheckpoint == null || sourceCheckpoint.position.branchId == branchId)
        require(listOf(holds.size, obligations.size, reconciliations.size, readmissions.size,
            lateTail.size, quarantinedTail.size).all { it <= MAX_RECOVERY_ITEMS })
        require(holds.map { it.holdId }.toSet().size == holds.size)
        require(obligations.map { it.obligationId }.toSet().size == obligations.size)
        require(reconciliations.map { it.reconciliationId }.toSet().size == reconciliations.size)
        require(readmissions.map { it.readmissionId }.toSet().size == readmissions.size)
        require(incidentId == null || holds.any {
            it.holdId == incidentId && it.reason == RecoveryHoldReason.RESTORED_STATE_NOT_TRUSTWORTHY
        })
        require(holds.all { it.createdRevision.value <= revision.value })
        require(reconciliations.all { it.branchId == branchId && it.incidentId == incidentId &&
            it.sourceCheckpoint == sourceCheckpoint && it.observedHoldIds.all { holdId -> holds.any { hold -> hold.holdId == holdId } } })
        require(readmissions.all { readmission -> readmission.branchId == branchId && readmission.incidentId == incidentId &&
            reconciliations.any { it.reconciliationId == readmission.reconciliationId && it.resource == readmission.resource &&
                it.observedHoldIds == readmission.coveredHoldIds } })
        require(lateTail.all { it.proposal.branchId == branchId && it.proposal.incidentId == incidentId })
        require(quarantinedTail.all { it.branchId == branchId && it.incidentId == incidentId })
        require(lateTail.map { it.proposal.operation.identity.operationId }.toSet().size == lateTail.size)
        require(incidentId != null || (holds.isEmpty() && reconciliations.isEmpty() && readmissions.isEmpty() &&
            lateTail.isEmpty() && quarantinedTail.isEmpty() && scopeDeclaration == null))
    }

    public companion object {
        /** Only initial verified provisioning, never a recovery/reset shortcut. */
        public fun initiallyProvisioned(branchId: DomainId): RecoveryAdmissionState = RecoveryAdmissionState(
            branchId, Revision(0), null, null, emptyList(), emptyList(), null,
            emptyList(), emptyList(), emptyList(), emptyList(),
        )
    }
}

public data class BoundedScopeAssertion(public val declaration: BoundedRecoveryScope) : RecoveryEvidenceAssertion {
    override val branchId: DomainId get() = declaration.branchId
    override val evidence: List<RecoveryEvidenceRef> get() =
        listOf(declaration.decisionEvidence) + declaration.supportingEvidence
}

public data class ResourceReconciliationAssertion(
    public val proposal: ResourceReconciliation,
    public val preservedObligations: List<PreservedRecoveryObligation>,
    public val unmatchedLateEffects: List<HistoricalRecoveryEffect>,
    public val conflictingTail: List<LateTailProposal>,
) : RecoveryEvidenceAssertion {
    override val branchId: DomainId get() = proposal.branchId
    override val evidence: List<RecoveryEvidenceRef> get() =
        listOf(proposal.decisionEvidence) + proposal.supportingEvidence
}

public data class ResourceReadmissionAssertion(
    public val proposal: ResourceReadmission,
    public val reconciliation: ResourceReconciliation,
    public val preservedObligations: List<PreservedRecoveryObligation>,
    public val holds: List<RecoveryResourceHold>,
) : RecoveryEvidenceAssertion {
    override val branchId: DomainId get() = proposal.branchId
    override val evidence: List<RecoveryEvidenceRef> get() = listOf(proposal.decisionEvidence)
}

/** Verifier must enforce historical appointment and cutoff; a retired writer's new fact is not a late tail. */
public data class HistoricalTailOriginAssertion(
    public val sourceCheckpoint: RecoveryCheckpoint,
    public val proposal: LateTailProposal,
) : RecoveryEvidenceAssertion {
    override val branchId: DomainId get() = proposal.branchId
    override val evidence: List<RecoveryEvidenceRef> get() = listOf(proposal.operation.originEvidence)
}

public data class LateEffectMatchAssertion(
    public val proposal: LateTailProposal,
    public val effect: HistoricalRecoveryEffect,
    public val reconciliation: ResourceReconciliation,
    public val match: LateEffectAdjustmentMatch,
) : RecoveryEvidenceAssertion {
    override val branchId: DomainId get() = proposal.branchId
    override val evidence: List<RecoveryEvidenceRef> get() = listOf(match.correspondenceEvidence)
}

public enum class ResourceAdmissionDecision {
    ADMITTED,
    WRONG_BRANCH,
    RESOURCE_FOOTPRINT_REQUIRED,
    RESOURCE_RECONCILIATION_REQUIRED,
}

/** No operation-ID argument: a fresh ID cannot bypass an uncertain resource. */
public object RecoveryAdmissionPolicy {
    /** Ordinary exact-resource admission; no implicit hold barrier is selected. */
    public fun evaluate(
        state: RecoveryAdmissionState,
        branchId: DomainId,
        affectedResources: Set<RecoveryResourceKey>,
    ): ResourceAdmissionDecision = evaluate(state, branchId, affectedResources, emptySet())

    /**
     * Optional denial-only restriction selected by a trusted command profile.
     * Any retained hold selecting a kind in [holdBarrierKinds], including a
     * whole-branch hold, denies even after exact-resource readmission. Resource
     * IDs and intersection with [affectedResources] do not narrow this barrier.
     *
     * This is not a grant, an alias-equivalence proof or a complete footprint.
     * Normal branch, footprint and per-resource readmission checks still apply;
     * an empty restriction set preserves ordinary admission. No hold, obligation
     * or readmission is removed, and no global restored-branch ban is imposed.
     */
    public fun evaluate(
        state: RecoveryAdmissionState,
        branchId: DomainId,
        affectedResources: Set<RecoveryResourceKey>,
        holdBarrierKinds: Set<RecoveryResourceKind>,
    ): ResourceAdmissionDecision {
        if (state.branchId != branchId) return ResourceAdmissionDecision.WRONG_BRANCH
        // The trusted handler must derive the complete footprint, including activated authorization.
        if (affectedResources.isEmpty() || affectedResources.size > MAX_RECOVERY_ITEMS ||
            affectedResources.none { it.kind == RecoveryResourceKind.AUTHORIZATION }
        ) {
            return ResourceAdmissionDecision.RESOURCE_FOOTPRINT_REQUIRED
        }
        if (holdBarrierKinds.isNotEmpty() && state.holds.any { hold ->
                hold.selectors.any { selector ->
                    when (selector) {
                        RecoveryResourceSelector.WholeBranch -> true
                        is RecoveryResourceSelector.Kind -> selector.kind in holdBarrierKinds
                        is RecoveryResourceSelector.Resource -> selector.resource.kind in holdBarrierKinds
                    }
                }
            }
        ) {
            return ResourceAdmissionDecision.RESOURCE_RECONCILIATION_REQUIRED
        }
        for (resource in affectedResources) {
            for (hold in state.holds.filter { it.matches(resource) }) {
                if (state.readmissions.none { it.resource == resource && hold.holdId in it.coveredHoldIds }) {
                    return ResourceAdmissionDecision.RESOURCE_RECONCILIATION_REQUIRED
                }
            }
        }
        return ResourceAdmissionDecision.ADMITTED
    }
}

public class RecoveryAdmissionMachine(
    private val verifier: RecoveryEvidenceVerifier = UnqualifiedRecoveryEvidenceVerifier,
) {
    /**
     * Every restore starts conservatively held, including unknown grants and resources
     * which no pending client knows about. Nothing here activates a writer or sessions.
     */
    public fun restore(
        branchId: DomainId,
        incidentId: DomainId,
        sourceCheckpoint: RecoveryCheckpoint,
        preservedObligations: List<PreservedRecoveryObligation>,
    ): RecoveryAdmissionState {
        require(sourceCheckpoint.position.branchId == branchId)
        return RecoveryAdmissionState(
            branchId, Revision(0), incidentId, sourceCheckpoint,
            listOf(RecoveryResourceHold(incidentId, listOf(RecoveryResourceSelector.WholeBranch),
                RecoveryHoldReason.RESTORED_STATE_NOT_TRUSTWORTHY, Revision(0))),
            preservedObligations.toList(), null, emptyList(), emptyList(), emptyList(), emptyList(),
        )
    }

    /** Optional qualified independence proof; never inferred from a stock count or client list. */
    public fun boundAffectedScope(
        state: RecoveryAdmissionState,
        expectedRevision: Revision,
        declaration: BoundedRecoveryScope,
    ): RecoveryTransition<RecoveryAdmissionState> {
        state.scopeDeclaration?.let {
            return if (it == declaration) unchanged(state) else refused(state, RecoveryRefusal.IDENTITY_CONFLICT)
        }
        validateContext(state, expectedRevision, declaration.branchId, declaration.incidentId)?.let {
            return refused(state, it)
        }
        if (declaration.sourceCheckpoint != state.sourceCheckpoint || state.reconciliations.isNotEmpty() ||
            state.readmissions.isNotEmpty() || state.lateTail.isNotEmpty() || state.quarantinedTail.isNotEmpty()
        ) {
            return refused(state, RecoveryRefusal.INVALID_STATE)
        }
        if (!verifier.verifies(BoundedScopeAssertion(declaration))) {
            return refused(state, RecoveryRefusal.EVIDENCE_NOT_VERIFIED)
        }
        // Restored authorization is always held even if physical business effects were bounded.
        val selectors = (declaration.affectedSelectors +
            RecoveryResourceSelector.Kind(RecoveryResourceKind.AUTHORIZATION)).distinct()
        if (selectors.size > MAX_RECOVERY_ITEMS) return refused(state, RecoveryRefusal.CAPACITY_REQUIRES_PROTECTED_ARCHIVE)
        val hold = state.holds.single().copy(selectors = selectors)
        return applied(state.copy(revision = state.revision.next(), holds = listOf(hold), scopeDeclaration = declaration))
    }

    public fun reconcile(
        state: RecoveryAdmissionState,
        expectedRevision: Revision,
        proposal: ResourceReconciliation,
    ): RecoveryTransition<RecoveryAdmissionState> {
        state.reconciliations.find { it.reconciliationId == proposal.reconciliationId }?.let {
            return if (it == proposal) unchanged(state) else refused(state, RecoveryRefusal.IDENTITY_CONFLICT)
        }
        validateContext(state, expectedRevision, proposal.branchId, proposal.incidentId)?.let {
            return refused(state, it)
        }
        if (proposal.sourceCheckpoint != state.sourceCheckpoint) return refused(state, RecoveryRefusal.INVALID_STATE)
        if (proposal.kind.resourceKind != proposal.resource.kind ||
            proposal.adjustment?.resource?.let { it != proposal.resource } == true ||
            (proposal.resource.kind == RecoveryResourceKind.AUTHORIZATION && proposal.adjustment != null)
        ) {
            return refused(state, RecoveryRefusal.RECONCILIATION_KIND_MISMATCH)
        }
        val obligations = state.obligations.filter { it.resource == proposal.resource }
        if (!proposal.acknowledgedObligationIds.containsAll(obligations.map { it.obligationId })) {
            return refused(state, RecoveryRefusal.OBLIGATIONS_NOT_ACKNOWLEDGED)
        }
        if (proposal.observedHoldIds != state.holds.filter { it.matches(proposal.resource) }.map { it.holdId }.toSet()) {
            return refused(state, RecoveryRefusal.RESOURCE_RECONCILIATION_REQUIRED)
        }
        if (proposal.adjustment != null && state.reconciliations.any {
                it.adjustment?.adjustmentId == proposal.adjustment.adjustmentId
            }
        ) {
            return refused(state, RecoveryRefusal.IDENTITY_CONFLICT)
        }
        val unmatched = state.lateTail.flatMap { tail ->
            tail.proposal.operation.effects.filter { it.effectId in tail.unmatchedEffectIds && it.resource == proposal.resource }
        }
        if (!verifier.verifies(ResourceReconciliationAssertion(proposal, obligations, unmatched, state.quarantinedTail))) {
            return refused(state, RecoveryRefusal.EVIDENCE_NOT_VERIFIED)
        }
        if (state.reconciliations.size == MAX_RECOVERY_ITEMS) {
            return refused(state, RecoveryRefusal.CAPACITY_REQUIRES_PROTECTED_ARCHIVE)
        }
        // A reconciliation is not itself readmission, and does not erase obligations/history gaps.
        return applied(state.copy(revision = state.revision.next(), reconciliations = state.reconciliations + proposal))
    }

    public fun readmit(
        state: RecoveryAdmissionState,
        expectedRevision: Revision,
        proposal: ResourceReadmission,
    ): RecoveryTransition<RecoveryAdmissionState> {
        state.readmissions.find { it.readmissionId == proposal.readmissionId }?.let {
            return if (it == proposal) unchanged(state) else refused(state, RecoveryRefusal.IDENTITY_CONFLICT)
        }
        validateContext(state, expectedRevision, proposal.branchId, proposal.incidentId)?.let {
            return refused(state, it)
        }
        val reconciliation = state.reconciliations.find { it.reconciliationId == proposal.reconciliationId }
            ?: return refused(state, RecoveryRefusal.RECONCILIATION_NOT_FOUND)
        if (reconciliation.resource != proposal.resource) return refused(state, RecoveryRefusal.RECONCILIATION_KIND_MISMATCH)
        val obligations = state.obligations.filter { it.resource == proposal.resource }
        if (!proposal.acknowledgedObligationIds.containsAll(obligations.map { it.obligationId })) {
            return refused(state, RecoveryRefusal.OBLIGATIONS_NOT_ACKNOWLEDGED)
        }
        val holds = state.holds.filter { it.matches(proposal.resource) }
        if (holds.isEmpty() || proposal.coveredHoldIds != holds.map { it.holdId }.toSet() ||
            reconciliation.observedHoldIds != proposal.coveredHoldIds
        ) {
            return refused(state, RecoveryRefusal.RESOURCE_RECONCILIATION_REQUIRED)
        }
        if (!verifier.verifies(ResourceReadmissionAssertion(proposal, reconciliation, obligations, holds))) {
            return refused(state, RecoveryRefusal.EVIDENCE_NOT_VERIFIED)
        }
        if (state.readmissions.size == MAX_RECOVERY_ITEMS) {
            return refused(state, RecoveryRefusal.CAPACITY_REQUIRES_PROTECTED_ARCHIVE)
        }
        return applied(state.copy(revision = state.revision.next(), readmissions = state.readmissions + proposal))
    }

    /**
     * Read-only historical evidence ingestion: NO ledger effects, printing, payment,
     * kitchen jobs or outboxes are returned for replay. Unmatched/contradictory effects
     * add holds. Matching requires exact source IDs, measure, delta and protected proof.
     */
    public fun recordLateTail(
        state: RecoveryAdmissionState,
        expectedRevision: Revision,
        proposal: LateTailProposal,
    ): RecoveryTransition<RecoveryAdmissionState> {
        if (state.branchId != proposal.branchId || proposal.operation.identity.branchId != state.branchId ||
            proposal.operation.origin.branchId != state.branchId
        ) {
            return refused(state, RecoveryRefusal.WRONG_BRANCH)
        }
        val existing = state.lateTail.find {
            it.proposal.operation.identity.operationId == proposal.operation.identity.operationId
        }
        if (existing != null && existing.proposal.sameHistoricalContent(proposal) &&
            existing.proposal.sameMatches(proposal)
        ) {
            return unchanged(state)
        }
        state.quarantinedTail.find { it.ingestId == proposal.ingestId }?.let {
            return if (it == proposal) unchanged(state) else refused(state, RecoveryRefusal.IDENTITY_CONFLICT)
        }
        validateContext(state, expectedRevision, proposal.branchId, proposal.incidentId)?.let {
            return refused(state, it)
        }
        val checkpoint = state.sourceCheckpoint ?: return refused(state, RecoveryRefusal.INVALID_STATE)
        if (!verifier.verifies(HistoricalTailOriginAssertion(checkpoint, proposal))) {
            return refused(state, RecoveryRefusal.EVIDENCE_NOT_VERIFIED)
        }
        val priorEffects = state.lateTail.flatMap { it.proposal.operation.effects }.map { it.effectId }.toSet()
        val conflict = existing != null || proposal.operation.effects.any { it.effectId in priorEffects } ||
            state.lateTail.any { it.proposal.ingestId == proposal.ingestId }
        if (conflict) {
            if (state.quarantinedTail.size == MAX_RECOVERY_ITEMS || state.holds.size == MAX_RECOVERY_ITEMS ||
                state.holds.any { it.holdId == proposal.ingestId }
            ) {
                return refused(state, RecoveryRefusal.CONFLICTING_LATE_EVIDENCE)
            }
            // The contradictory evidence is retained; original facts are never overwritten.
            val hold = RecoveryResourceHold(proposal.ingestId, listOf(RecoveryResourceSelector.WholeBranch),
                RecoveryHoldReason.CONFLICTING_LATE_EVIDENCE, state.revision.next())
            return applied(state.copy(revision = state.revision.next(), holds = state.holds + hold,
                quarantinedTail = state.quarantinedTail + proposal))
        }
        val usedAdjustments = state.lateTail.flatMap { it.proposal.matches }.map { it.adjustmentId }.toSet()
        for (match in proposal.matches) {
            val effect = proposal.operation.effects.find { it.effectId == match.effectId }
                ?: return refused(state, RecoveryRefusal.IDENTITY_CONFLICT)
            val reconciliation = state.reconciliations.find { it.reconciliationId == match.reconciliationId }
                ?: return refused(state, RecoveryRefusal.RECONCILIATION_NOT_FOUND)
            val adjustment = reconciliation.adjustment ?: return refused(state, RecoveryRefusal.RECONCILIATION_NOT_FOUND)
            if (adjustment.adjustmentId != match.adjustmentId || adjustment.adjustmentId in usedAdjustments ||
                adjustment.resource != effect.resource || adjustment.unit != effect.unit || adjustment.delta != effect.delta
            ) {
                return refused(state, RecoveryRefusal.IDENTITY_CONFLICT)
            }
            if (!verifier.verifies(LateEffectMatchAssertion(proposal, effect, reconciliation, match))) {
                return refused(state, RecoveryRefusal.EVIDENCE_NOT_VERIFIED)
            }
        }
        val matchedIds = proposal.matches.map { it.effectId }.toSet()
        val unmatched = proposal.operation.effects.filter { it.effectId !in matchedIds }
        if (state.lateTail.size == MAX_RECOVERY_ITEMS || (unmatched.isNotEmpty() && state.holds.size == MAX_RECOVERY_ITEMS)) {
            return refused(state, RecoveryRefusal.CAPACITY_REQUIRES_PROTECTED_ARCHIVE)
        }
        if (unmatched.isNotEmpty() && state.holds.any { it.holdId == proposal.ingestId }) {
            return refused(state, RecoveryRefusal.IDENTITY_CONFLICT)
        }
        val newHolds = if (unmatched.isEmpty()) emptyList() else listOf(RecoveryResourceHold(
            proposal.ingestId, unmatched.map { RecoveryResourceSelector.Resource(it.resource) }.distinct(),
            RecoveryHoldReason.UNMATCHED_LATE_EFFECT, state.revision.next(),
        ))
        return applied(state.copy(revision = state.revision.next(), holds = state.holds + newHolds,
            lateTail = state.lateTail + RecordedLateTail(proposal, unmatched.map { it.effectId }.toSet())))
    }

    private fun validateContext(
        state: RecoveryAdmissionState,
        expectedRevision: Revision,
        branchId: DomainId,
        incidentId: DomainId,
    ): RecoveryRefusal? = when {
        branchId != state.branchId -> RecoveryRefusal.WRONG_BRANCH
        incidentId != state.incidentId -> RecoveryRefusal.INVALID_STATE
        expectedRevision != state.revision -> RecoveryRefusal.REVISION_CONFLICT
        else -> null
    }
}

internal fun RecoveryResourceHold.matches(resource: RecoveryResourceKey): Boolean = selectors.any {
    when (it) {
        RecoveryResourceSelector.WholeBranch -> true
        is RecoveryResourceSelector.Kind -> it.kind == resource.kind
        is RecoveryResourceSelector.Resource -> it.resource == resource
    }
}

private fun LateTailProposal.sameHistoricalContent(other: LateTailProposal): Boolean =
    branchId == other.branchId && incidentId == other.incidentId &&
        operation.identity == other.operation.identity && operation.origin == other.operation.origin &&
        operation.recordSha256 == other.operation.recordSha256 && operation.effects == other.operation.effects

private fun LateTailProposal.sameMatches(other: LateTailProposal): Boolean =
    matches.map { Triple(it.effectId, it.reconciliationId, it.adjustmentId) }.toSet() ==
        other.matches.map { Triple(it.effectId, it.reconciliationId, it.adjustmentId) }.toSet()
