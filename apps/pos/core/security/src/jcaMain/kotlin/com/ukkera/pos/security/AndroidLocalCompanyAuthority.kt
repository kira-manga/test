package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import java.util.concurrent.atomic.AtomicReference

/** The concrete same-work issuer that installed one [AndroidLocalCompanyInstallationAdmission]. */
internal interface LocalCompanyInstallationAdmissionAuthority {
    fun requireBeforeInstallationAttemptRetention(admission: AndroidLocalCompanyInstallationAdmission,
        actualAnchor: AndroidSelfOwnedHistoryAnchor, actualWork: AndroidOwnedInstallationWork,
        actualEnvironment: SelfOwnedOriginalEnvironment, proposal: OriginalInstallationProposal,
        snapshot: LocalBranchInstallationSnapshotV2, attemptSequence: Long, prospectiveAttemptId: DomainId)
}

/** Original-installation issuer surface shared by the Android V1 and desktop V2 company containers. */
internal interface LocalCompanyOriginalInstallationIssuer {
    fun inspectCompanyAuthority(): LocalCompanyAuthorityView
    fun readOriginalInstallationHumanReference(): LocalInstallationHumanReferenceV2
    fun prepareOriginalInstallationDefinition(proposal: OriginalInstallationProposal): LocalCompanyInstallationAction
    fun approveOriginalInstallationDefinition(action: LocalCompanyInstallationAction, login: String,
        secret: CharArray): LocalCompanyInstallationApproval
    fun readOriginalInstallationAttempt(): LocalCompanyInstallationApproval
    fun acknowledgeOriginalInstallationReceipt(completion: OriginalInstallationCompletion): LocalInstallationSignedAcknowledgementV2
    fun retireOriginalInstallationAttempt(retirement: OriginalAttemptRetirement)
}

/**
 * Execute-only participant of the ACTUAL existing P1/P2 work. No factory, executor, process
 * lease, own store or externally supplied authority callback. Wrappers admit/copy/clear inputs
 * and watch registry acquisition/discard/close. This helper never calls work.run or closes it.
 */
internal class AndroidLocalCompanyAuthority(
    private val environment: SelfOwnedOriginalEnvironment,
    private val registry: LocalProvisionerRegistryStore,
    private val keys: LocalProvisionerNativeKeys,
    private val work: AndroidOwnedInstallationWork,
    private val historyAnchor: AndroidSelfOwnedHistoryAnchor? = null,
    private val issuance: AndroidLocalCompanyIssuanceGate = AndroidLocalCompanyIssuanceGate(),
) : LocalCompanyInstallationAdmissionAuthority, LocalCompanyOriginalInstallationIssuer {
    private val actionOwner = Any()
    private val preparedFounding = AtomicReference<PreparedFoundingCredentials?>(null)
    private val authentication = AndroidLocalInstallerAuthentication(environment, registry, keys, work, issuance)
    // Present only during the synchronous call into the ACTUAL same-worker P4 owner.
    // An internal constructor or copied fields cannot install a second admission here.
    private var activeInstallationAdmission: AndroidLocalCompanyInstallationAdmission? = null

    fun denyHeadIssuance() { issuance.deny(null) }
    fun denyPurpose(purpose: LocalCompanyHeadPurposeV1?) { issuance.deny(purpose) }

    override fun inspectCompanyAuthority(): LocalCompanyAuthorityView {
        requireOwned()
        val original = requireBootstrap()
        val state = registry.readCompanyState(keys) ?: localCompanyRefuse(LocalCompanyAuthorityFailure.ORIGINAL_RESULT_REQUIRED)
        return companyView(original, state)
    }

    fun readOriginalCompanyAndHeadAction(): LocalCompanyFoundingAction {
        requireOwned()
        val original = requireBootstrap()
        keys.assertCompletedOriginalKeys()
        val state = registry.readCompanyState(keys)
        if (state == null && original.original.enrollmentStarted) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        if (!original.complete()) localCompanyRefuse(LocalCompanyAuthorityFailure.ORIGINAL_KEY_PREPARATION_REQUIRED)
        keys.assertAllCompanyKeys()
        return foundingAction(original, enrollmentRetained = state != null).also { requireOwned() }
    }

    fun resumeOriginalCompanyAndHead(): LocalCompanyFoundingAction {
        requireOwned()
        val original = requireBootstrap() // No fresh bootstrap, facts, IDs or alias adoption.
        val state = registry.readCompanyState(keys)
        if (state != null) return readOriginalCompanyAndHeadAction()
        if (original.original.enrollmentStarted) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        requireNewIssuance()
        keys.assertCompletedOriginalKeys()
        keys.prepareCompanyKeys()
        requireNewIssuance()
        return readOriginalCompanyAndHeadAction()
    }

    override fun readOriginalInstallationHumanReference(): LocalInstallationHumanReferenceV2 {
        val state = requireState()
        return originalInstallationHumanReference(state).also { requireOwned() }
    }

    override fun prepareOriginalInstallationDefinition(proposal: OriginalInstallationProposal): LocalCompanyInstallationAction {
        val owner = requireInstallationOwner()
        requireNewIssuance(LocalCompanyHeadPurposeV1.FULL_D7_INSTALLATION_APPROVE)
        var state = requireState()
        requirePurpose(state, LocalCompanyHeadPurposeV1.FULL_D7_INSTALLATION_APPROVE)
        val complete = requireOriginalInstallationSnapshot(owner.requireOriginalProposal(proposal), state)
        val expected = LocalCompanyInstallationAdmissionV1(complete.definition.originalRequestId, complete.allocationDigest,
            complete.definitionDigest, state.installationClaims.size.toLong() + 1L)
        if (state.installationClaims.any { it.retirementEvidence == null }) {
            localCompanyRefuse(LocalCompanyAuthorityFailure.ORIGINAL_ATTEMPT_SETTLEMENT_REQUIRED)
        }
        val existing = state.installationAdmission
        if (existing != null && existing != expected) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        if (existing == null) {
            if (state.installationClaims.size >= LocalCompanyAuthorityStateV1.MAX_INSTALLATION_CLAIMS) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
            }
            // Force the space/identity reservation BEFORE P4 can allocate/force an attempt.
            // Cancellation does not free it; only its exact real next claim consumes it.
            state = registry.retainCompanyState(state.next(admission = expected), keys)
        }
        owner.requireOriginalProposal(proposal)
        requireNewIssuance(LocalCompanyHeadPurposeV1.FULL_D7_INSTALLATION_APPROVE)
        val head = state.head
        return LocalCompanyInstallationAction(actionOwner, proposal, complete, expected.nextAttemptSequence,
            head.accountRevision, head.credentialRevision, head.installationRevision,
            requireBootstrap().key(LocalCompanyHeadKeyRoleV1.INSTALLATION).keyRevision, environment.bootCount, pendingDeadline())
    }

    override fun approveOriginalInstallationDefinition(action: LocalCompanyInstallationAction, login: String,
        secret: CharArray): LocalCompanyInstallationApproval {
        val owner = requireInstallationOwner()
        var state = requireState()
        requireInstallationAction(action, state, current = false)
        val prior = state.installationClaims.singleOrNull { it.attemptSequence == action.nextAttemptSequence }
        if (prior != null) {
            if (prior.originalRequestId != action.definition.originalRequestId || prior.definitionDigest != action.definitionDigest ||
                prior.allocationDigest != action.allocationDigest) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
            // No automatic re-signing of an interrupted/expired original attempt. Genuine P4
            // terminal retirement permits a new ceremony over the SAME D7/request instead.
            if (prior.signedHumanApproval == null || prior.retirementEvidence != null) {
                localCompanyRefuse(LocalCompanyAuthorityFailure.ORIGINAL_ATTEMPT_SETTLEMENT_REQUIRED)
            }
            val original = owner.readOriginalInstallationAttempt()
            if (original.facts.issuedAttemptId != prior.issuedAttemptId) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
            return readOriginalInstallationAttempt(original)
        }
        requireNewIssuance(LocalCompanyHeadPurposeV1.FULL_D7_INSTALLATION_APPROVE)
        requireInstallationAction(action, state, current = true)
        requireInstallationAdmission(state, action.snapshot, action.nextAttemptSequence)
        requireSameInstallationSnapshot(action.snapshot, owner.requireOriginalProposal(action.originalProposal))
        val approval = authentication.authenticateHeadAction(LocalCompanyHeadPurposeV1.FULL_D7_INSTALLATION_APPROVE,
            action.definition.originalRequestId, action.definitionDigest, login, secret, action.expiresElapsedMillis)
        state = requireState()
        authentication.requireHeadStillCurrent(approval, state, LocalCompanyHeadPurposeV1.FULL_D7_INSTALLATION_APPROVE,
            action.definition.originalRequestId, action.definitionDigest)
        requireInstallationAction(action, state, current = true)
        requireInstallationAdmission(state, action.snapshot, action.nextAttemptSequence)
        requireNewIssuance(LocalCompanyHeadPurposeV1.FULL_D7_INSTALLATION_APPROVE)
        val headCommitment = protectedHeadDigest(state)
        val admission = AndroidLocalCompanyInstallationAdmission(this, owner, action.originalProposal,
            action.snapshot, action.nextAttemptSequence, approval, headCommitment)
        if (activeInstallationAdmission != null) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        activeInstallationAdmission = admission
        val original = try {
            // P4 MUST consume this concrete capability before forcing an attempt. It cannot
            // substitute raw head scalars, an accepting callback or a caller-supplied digest.
            owner.reserveOriginalInstallationAttempt(action.originalProposal, admission)
        } finally {
            admission.invalidateAfterReservation()
            activeInstallationAdmission = null
        }
        admission.requireReturnedAttempt(original.facts)
        val snapshot = requireOriginalInstallationSnapshot(original.snapshot, state)
        requireSameInstallationSnapshot(action.snapshot, snapshot)
        val facts = original.facts
        if (facts.attemptSequence != action.nextAttemptSequence || facts.headAccountRevision != approval.accountRevision ||
            facts.headCredentialRevision != approval.credentialRevision || facts.installationPurposeRevision != approval.purposeRevision ||
            facts.installationKeyRevision != approval.keyRevision || facts.protectedHeadStateDigest != headCommitment ||
            facts.osBootCount != environment.bootCount || facts.deadlineElapsedMillis > approval.expires) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        approval.bindInstallationAttempt(facts.issuedAttemptId)
        owner.requireOriginalAttemptForIssuance(original)
        state = requireState()
        authentication.requireHeadStillCurrent(approval, state, LocalCompanyHeadPurposeV1.FULL_D7_INSTALLATION_APPROVE,
            snapshot.definition.originalRequestId, snapshot.definitionDigest)
        if (protectedHeadDigest(state) != headCommitment) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val claim = originalInstallationClaim(snapshot, facts, state)
        requireNewIssuance(LocalCompanyHeadPurposeV1.FULL_D7_INSTALLATION_APPROVE)
        state = registry.retainCompanyState(state.next(claims = state.installationClaims + claim, admission = null), keys)
        owner.requireOriginalAttemptForIssuance(original)
        requireNewIssuance(LocalCompanyHeadPurposeV1.FULL_D7_INSTALLATION_APPROVE)
        val proof = keys.signOriginalInstallationApproval(snapshot, facts, approval)
        state = requireState()
        if (state.installationClaims.singleOrNull { it.issuedAttemptId == claim.issuedAttemptId } != claim) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        authentication.requireHeadStillCurrent(approval, state, LocalCompanyHeadPurposeV1.FULL_D7_INSTALLATION_APPROVE,
            snapshot.definition.originalRequestId, snapshot.definitionDigest)
        owner.requireOriginalAttemptForIssuance(original)
        requireNewIssuance(LocalCompanyHeadPurposeV1.FULL_D7_INSTALLATION_APPROVE)
        val encoded = proof.copyWireBytes()
        val retained = try { registry.retainCompanyState(state.replaceClaim(claim.copy(signedHumanApproval = SelfOwnedOctetsV1(encoded))), keys) }
        finally { encoded.fill(0) }
        // A lost/withdrawn delivery may still rejoin this exact retained output as bookkeeping.
        // It never obtains renewed currentness merely from its positive historical existence.
        val result = readOriginalInstallationAttempt(original)
        authentication.requireHeadStillCurrent(approval, retained, LocalCompanyHeadPurposeV1.FULL_D7_INSTALLATION_APPROVE,
            snapshot.definition.originalRequestId, snapshot.definitionDigest)
        owner.requireOriginalAttemptForIssuance(original)
        requireNewIssuance(LocalCompanyHeadPurposeV1.FULL_D7_INSTALLATION_APPROVE)
        return result
    }

    override fun readOriginalInstallationAttempt(): LocalCompanyInstallationApproval =
        readOriginalInstallationAttempt(requireInstallationOwner().readOriginalInstallationAttempt())

    private fun readOriginalInstallationAttempt(original: OriginalInstallationAttempt): LocalCompanyInstallationApproval {
        val owner = requireInstallationOwner()
        owner.readOriginalInstallationAttempt(original)
        val state = requireState()
        val snapshot = requireOriginalInstallationSnapshot(original.snapshot, state)
        val claim = requireInstallationClaim(state, snapshot, original.facts)
        if (claim.retirementEvidence != null) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        val proof = originalHumanApproval(claim, snapshot, original.facts)
        owner.retainOriginalIssuerApproval(original, proof)
        owner.readOriginalInstallationAttempt(original)
        val checked = requireInstallationClaim(requireState(), snapshot, original.facts)
        if (checked != claim) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        return LocalCompanyInstallationApproval(original, proof)
    }

    override fun acknowledgeOriginalInstallationReceipt(completion: OriginalInstallationCompletion): LocalInstallationSignedAcknowledgementV2 {
        val owner = requireInstallationOwner()
        owner.requireOriginalCompletion(completion)
        var state = requireState()
        val snapshot = requireOriginalInstallationSnapshot(completion.snapshot, state)
        var claim = requireInstallationClaim(state, snapshot, completion.facts)
        if (claim.retirementEvidence != null) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        val human = originalHumanApproval(claim, snapshot, completion.facts)
        val suppliedHuman = completion.approvals.human.copyWireBytes()
        try { if (claim.signedHumanApproval?.same(suppliedHuman) != true) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT) }
        finally { suppliedHuman.fill(0) }
        val targetBytes = completion.approvals.target.copyWireBytes()
        val target = try { LocalBranchInstallationWireV2.decodeSignedTargetApproval(targetBytes, snapshot, completion.facts, human)
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN) } finally { targetBytes.fill(0) }
        verifyInstallationApproval(target)
        val approvals = LocalInstallationOriginalApprovalsV2(human, target)
        val frame = LocalBranchInstallationWireV2.acknowledgement(snapshot, completion.facts, approvals,
            completion.receipt, completion.childFacts)
        val frameBytes = frame.copyWireBytes()
        try {
            val priorFrame = claim.acknowledgementFrame
            if (priorFrame != null && !priorFrame.same(frameBytes)) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
            if (priorFrame == null) {
                owner.requireOriginalCompletion(completion)
                state = registry.retainCompanyState(state.replaceClaim(claim.copy(acknowledgementFrame = SelfOwnedOctetsV1(frameBytes))), keys)
                claim = requireInstallationClaim(state, snapshot, completion.facts)
            }
            if (claim.signedAcknowledgement == null) {
                owner.requireOriginalCompletion(completion)
                val signed = keys.signOriginalInstallationAcknowledgement(frame)
                val encoded = signed.copyWireBytes()
                try {
                    state = requireState()
                    if (requireInstallationClaim(state, snapshot, completion.facts) != claim) {
                        selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                    }
                    owner.requireOriginalCompletion(completion)
                    state = registry.retainCompanyState(state.replaceClaim(claim.copy(signedAcknowledgement = SelfOwnedOctetsV1(encoded))), keys)
                    claim = requireInstallationClaim(state, snapshot, completion.facts)
                } finally { encoded.fill(0) }
            }
            val bytes = requireNotNull(claim.signedAcknowledgement).copyBytes()
            val result = try { LocalBranchInstallationWireV2.decodeSignedAcknowledgement(bytes, snapshot, completion.facts,
                approvals, completion.receipt, completion.childFacts) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN) }
            finally { bytes.fill(0) }
            verifyInstallationAcknowledgement(result)
            owner.requireOriginalCompletion(completion)
            owner.retainOriginalIssuerAcknowledgement(completion, result)
            owner.requireOriginalCompletion(completion)
            if (requireInstallationClaim(requireState(), snapshot, completion.facts) != claim) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
            return result
        } finally { frameBytes.fill(0) }
    }

    override fun retireOriginalInstallationAttempt(retirement: OriginalAttemptRetirement) {
        val owner = requireInstallationOwner()
        owner.requireOriginalRetirement(retirement)
        var state = requireState()
        val snapshot = requireOriginalInstallationSnapshot(retirement.snapshot, state)
        val existing = state.installationClaims.singleOrNull { it.issuedAttemptId == retirement.facts.issuedAttemptId }
        var claim: LocalCompanyInstallationClaimV1 = if (existing == null) {
            // P4-force -> P1-claim interruption: consume ONLY the already reserved identity/
            // capacity with this genuine original attempt. No fresh auth, ID or signature.
            val original = originalInstallationClaim(snapshot, retirement.facts, state)
            owner.requireOriginalRetirement(retirement)
            state = registry.retainCompanyState(state.next(claims = state.installationClaims + original, admission = null), keys)
            requireInstallationClaim(state, snapshot, retirement.facts)
        } else requireInstallationClaim(state, snapshot, retirement.facts)
        if (claim.acknowledgementFrame != null || claim.signedAcknowledgement != null) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        claim.signedHumanApproval?.let { originalHumanApproval(claim, snapshot, retirement.facts) }
        val evidence = retirement.copyEvidenceBytes()
        try {
            if (evidence.size !in 1..LocalCompanyInstallationClaimV1.MAXIMUM_RETIREMENT_EVIDENCE_BYTES ||
                PayloadDigest(AndroidP256Primitives.hashHex(evidence)) != retirement.retirementDigest) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
            if (claim.retirementEvidence == null) {
                owner.requireOriginalRetirement(retirement)
                state = registry.retainCompanyState(state.replaceClaim(claim.copy(retirementEvidence = SelfOwnedOctetsV1(evidence))), keys)
                claim = requireInstallationClaim(state, snapshot, retirement.facts)
            } else if (claim.retirementEvidence?.same(evidence) != true) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
            val tombstone = LocalCompanyAuthorityWireV1.encodeRetiredInstallationClaim(requireBootstrap(), claim)
            val tombstoneDigest = try { PayloadDigest(AndroidP256Primitives.hashHex(tombstone)) } finally { tombstone.fill(0) }
            owner.requireOriginalRetirement(retirement)
            owner.retainOriginalIssuerRetirement(retirement, tombstoneDigest)
            owner.requireOriginalRetirement(retirement)
            if (requireInstallationClaim(requireState(), snapshot, retirement.facts) != claim) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
        } finally { evidence.fill(0) }
    }

    private fun requireInstallationOwner(): AndroidSelfOwnedHistoryAnchor {
        requireOwned()
        val owner = historyAnchor ?: localCompanyRefuse(LocalCompanyAuthorityFailure.ORIGINAL_TARGET_OWNER_REQUIRED)
        owner.requireIssuerParticipant(work, environment)
        return owner
    }

    private fun requireInstallationAction(action: LocalCompanyInstallationAction,
        state: LocalCompanyAuthorityStateV1, current: Boolean) {
        if (action.owner !== actionOwner || action.nextAttemptSequence !in 1 until Long.MAX_VALUE) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        }
        requireSameInstallationSnapshot(action.snapshot, action.originalProposal.snapshot)
        requireOriginalInstallationSnapshot(action.snapshot, state)
        if (current) {
            val head = state.head
            requirePurpose(state, LocalCompanyHeadPurposeV1.FULL_D7_INSTALLATION_APPROVE)
            if (action.headAccountRevision != head.accountRevision || action.headCredentialRevision != head.credentialRevision ||
                action.purposeRevision != head.installationRevision ||
                action.keyRevision != requireBootstrap().key(LocalCompanyHeadKeyRoleV1.INSTALLATION).keyRevision ||
                action.boot != environment.bootCount || work.clock() >= action.expiresElapsedMillis) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
            }
        }
    }

    private fun requireSameInstallationSnapshot(expected: LocalBranchInstallationSnapshotV2, actual: LocalBranchInstallationSnapshotV2) {
        val rebuilt = LocalBranchInstallationWireV2.snapshotDefinition(actual.definition)
        val canonical = rebuilt.copyWireBytes(); val captured = actual.copyWireBytes(); val prior = expected.copyWireBytes()
        try {
            if (!captured.contentEquals(canonical) || rebuilt.definitionDigest != actual.definitionDigest ||
                rebuilt.allocationDigest != actual.allocationDigest || rebuilt.originalTargetKeyDigest != actual.originalTargetKeyDigest) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
            if (!prior.contentEquals(canonical) || expected.definitionDigest != actual.definitionDigest ||
                expected.allocationDigest != actual.allocationDigest || expected.originalTargetKeyDigest != actual.originalTargetKeyDigest) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
            }
        } finally { canonical.fill(0); captured.fill(0); prior.fill(0) }
    }

    private fun protectedHeadDigest(state: LocalCompanyAuthorityStateV1): PayloadDigest {
        val bytes = LocalCompanyAuthorityWireV1.headStateCommitment(state, requireBootstrap())
        return try { PayloadDigest(AndroidP256Primitives.hashHex(bytes)) } finally { bytes.fill(0) }
    }

    private fun requireInstallationAdmission(state: LocalCompanyAuthorityStateV1, snapshot: LocalBranchInstallationSnapshotV2,
        sequence: Long) {
        val expected = LocalCompanyInstallationAdmissionV1(snapshot.definition.originalRequestId, snapshot.allocationDigest,
            snapshot.definitionDigest, sequence)
        if (state.installationAdmission != expected || sequence != state.installationClaims.size.toLong() + 1L ||
            state.installationClaims.any { it.retirementEvidence == null }) {
            localCompanyRefuse(LocalCompanyAuthorityFailure.ORIGINAL_ATTEMPT_SETTLEMENT_REQUIRED)
        }
    }

    /**
     * Called only by the concrete one-shot admission installed immediately around P4's
     * reserve call. This is the actual original P1 registry/key/auth owner, not a provider.
     * The one-shot is consumed before native observations; any refusal still consumes it.
     */
    override fun requireBeforeInstallationAttemptRetention(admission: AndroidLocalCompanyInstallationAdmission,
        actualAnchor: AndroidSelfOwnedHistoryAnchor, actualWork: AndroidOwnedInstallationWork,
        actualEnvironment: SelfOwnedOriginalEnvironment, proposal: OriginalInstallationProposal,
        snapshot: LocalBranchInstallationSnapshotV2, attemptSequence: Long, prospectiveAttemptId: DomainId) {
        if (activeInstallationAdmission !== admission || actualAnchor !== admission.originalAnchor ||
            actualWork !== work || actualEnvironment !== environment || proposal !== admission.originalProposal ||
            attemptSequence != admission.nextAttemptSequence) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        admission.consumeProspectiveAttempt(prospectiveAttemptId)
        if (requireInstallationOwner() !== actualAnchor) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        requireSameInstallationSnapshot(admission.originalSnapshot, snapshot)
        requireSameInstallationSnapshot(admission.originalSnapshot, actualAnchor.requireOriginalProposal(proposal))
        val state = requireState()
        requireInstallationAdmission(state, snapshot, attemptSequence)
        val headApproval = admission.headApproval as? AndroidLocalInstallerAuthentication.HeadApproval
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        authentication.requireHeadStillCurrent(headApproval, state, LocalCompanyHeadPurposeV1.FULL_D7_INSTALLATION_APPROVE,
            snapshot.definition.originalRequestId, snapshot.definitionDigest)
        if (protectedHeadDigest(state) != admission.protectedHeadStateDigest) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        // Include all fifteen founding/key IDs, original allocation IDs, every policy ID,
        // permanent prior attempt/request IDs, and the pending request BEFORE P4 force.
        if (prospectiveAttemptId in reservedIds(requireBootstrap(), state)) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        registry.requireCompanyCapacity(state)
        requireNewIssuance(LocalCompanyHeadPurposeV1.FULL_D7_INSTALLATION_APPROVE)
        admission.markChecksCompleted()
    }

    /** Capture only the actual P4 facts; historical denial consumes a prior reservation too. */
    private fun originalInstallationClaim(snapshot: LocalBranchInstallationSnapshotV2, facts: LocalInstallationAttemptFactsV2,
        state: LocalCompanyAuthorityStateV1): LocalCompanyInstallationClaimV1 {
        requireInstallationAdmission(state, snapshot, facts.attemptSequence)
        if (facts.issuedAttemptId in reservedIds(requireBootstrap(), state)) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        requireAttemptHeadReference(facts, state)
        val frame = LocalBranchInstallationWireV2.humanApprovalFrame(snapshot, facts).copyWireBytes()
        return try { LocalCompanyInstallationClaimV1(facts.issuedAttemptId, facts.attemptSequence,
            snapshot.definition.originalRequestId, snapshot.allocationDigest, snapshot.definitionDigest,
            SelfOwnedOctetsV1(frame), null, null, null, null) } finally { frame.fill(0) }
    }

    private fun requireInstallationClaim(state: LocalCompanyAuthorityStateV1, snapshot: LocalBranchInstallationSnapshotV2,
        facts: LocalInstallationAttemptFactsV2): LocalCompanyInstallationClaimV1 {
        val claim = state.installationClaims.singleOrNull { it.issuedAttemptId == facts.issuedAttemptId }
            ?: localCompanyRefuse(LocalCompanyAuthorityFailure.ORIGINAL_RESULT_REQUIRED)
        requireAttemptHeadReference(facts, state)
        val frame = LocalBranchInstallationWireV2.humanApprovalFrame(snapshot, facts).copyWireBytes()
        try {
            if (claim.attemptSequence != facts.attemptSequence || claim.originalRequestId != snapshot.definition.originalRequestId ||
                claim.allocationDigest != snapshot.allocationDigest || claim.definitionDigest != snapshot.definitionDigest ||
                !claim.approvalFrame.same(frame)) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        } finally { frame.fill(0) }
        return claim
    }

    private fun requireAttemptHeadReference(facts: LocalInstallationAttemptFactsV2, state: LocalCompanyAuthorityStateV1) {
        // These are historical immutable references, NOT a renewed active-head/clock check.
        if (facts.headAccountRevision > state.head.accountRevision || facts.headCredentialRevision != state.head.credentialRevision ||
            facts.installationPurposeRevision > state.head.installationRevision ||
            facts.installationKeyRevision != requireBootstrap().key(LocalCompanyHeadKeyRoleV1.INSTALLATION).keyRevision ||
            facts.osBootCount > environment.bootCount) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
    }

    private fun originalHumanApproval(claim: LocalCompanyInstallationClaimV1, snapshot: LocalBranchInstallationSnapshotV2,
        facts: LocalInstallationAttemptFactsV2): LocalInstallationSignedApprovalV2 {
        val bytes = (claim.signedHumanApproval ?: localCompanyRefuse(LocalCompanyAuthorityFailure.ORIGINAL_RESULT_REQUIRED)).copyBytes()
        val proof = try { LocalBranchInstallationWireV2.decodeSignedHumanApproval(bytes, snapshot, facts)
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN) } finally { bytes.fill(0) }
        verifyInstallationApproval(proof)
        return proof
    }

    private fun verifyInstallationApproval(value: LocalInstallationSignedApprovalV2) {
        val bytes = value.frame.copyWireBytes(); val signature = value.copySignature(); val point = value.frame.copySignerPublicKey()
        try {
            if (!AndroidP256Primitives.verify(point, bytes, signature)) selfOwnedFail(SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN)
        } finally { bytes.fill(0); signature.fill(0); point.fill(0) }
        requireOwned()
    }

    private fun verifyInstallationAcknowledgement(value: LocalInstallationSignedAcknowledgementV2) {
        val bytes = value.frame.copyWireBytes(); val signature = value.copySignature(); val point = keys.originalPoint(SelfOwnedNativeKeyRoleV1.ROOT)
        try {
            if (value.frame.rootKeyId != requireBootstrap().original.key(SelfOwnedNativeKeyRoleV1.ROOT).keyId ||
                !AndroidP256Primitives.verify(point, bytes, signature)) selfOwnedFail(SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN)
        } finally { bytes.fill(0); signature.fill(0); point.fill(0) }
        requireOwned()
    }

    private fun originalInstallationHumanReference(state: LocalCompanyAuthorityStateV1): LocalInstallationHumanReferenceV2 {
        val body = state.enrollment.body
        val encoded = LocalCompanyAuthorityWireV1.encodeEnrollmentBody(body)
        val point = body.installationPoint.copyBytes()
        return try {
            LocalInstallationHumanReferenceV2(body.rootId, body.rootKeyId,
                PayloadDigest(AndroidP256Primitives.documentDigest(state.original.root)), body.foundingActionId,
                PayloadDigest(AndroidP256Primitives.hashHex(encoded)), body.headAuthorityId,
                body.headEnrollmentId, body.installationKeyId, point)
        } finally { encoded.fill(0); point.fill(0) }
    }

    /** P4 supplies actual custody first; this independently recomputes ALL stable D7 content. */
    private fun requireOriginalInstallationSnapshot(snapshot: LocalBranchInstallationSnapshotV2,
        state: LocalCompanyAuthorityStateV1): LocalBranchInstallationSnapshotV2 {
        val complete = LocalBranchInstallationWireV2.snapshotDefinition(snapshot.definition)
        val expectedBytes = complete.copyWireBytes(); val retainedBytes = snapshot.copyWireBytes()
        try {
            if (!expectedBytes.contentEquals(retainedBytes) || complete.definitionDigest != snapshot.definitionDigest ||
                complete.allocationDigest != snapshot.allocationDigest || complete.originalTargetKeyDigest != snapshot.originalTargetKeyDigest) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
        } finally { expectedBytes.fill(0); retainedBytes.fill(0) }
        val original = state.original.allocations.singleOrNull()
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val grant = original.grant ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val allocation = complete.definition.allocation
        val actualCoordinates = selfOwnedEncode("original-allocation-crosscheck/v1") { coordinates(allocation.coordinates) }
        val originalCoordinates = selfOwnedEncode("original-allocation-crosscheck/v1") { coordinates(original.body.coordinates) }
        try {
            if (original.phase != LocalProvisionerAllocationPhaseV1.GRANT_RETAINED ||
                !actualCoordinates.contentEquals(originalCoordinates) ||
                allocation.p1RootDocumentDigest.value != AndroidP256Primitives.documentDigest(state.original.root) ||
                allocation.p1DelegationDigest.value != AndroidP256Primitives.documentDigest(state.original.delegation) ||
                allocation.p1PreparationDigest.value != AndroidP256Primitives.documentDigest(grant)) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
            }
        } finally { actualCoordinates.fill(0); originalCoordinates.fill(0) }
        val human = LocalBranchInstallationWireV2.encodeHumanReference(complete.definition.human)
        val originalHuman = LocalBranchInstallationWireV2.encodeHumanReference(originalInstallationHumanReference(state))
        try { if (!human.contentEquals(originalHuman)) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT) }
        finally { human.fill(0); originalHuman.fill(0) }
        val requestId = complete.definition.originalRequestId
        val alreadyReservedRequest = state.installationAdmission?.originalRequestId == requestId ||
            state.installationClaims.firstOrNull()?.originalRequestId == requestId
        if (!alreadyReservedRequest && requestId in reservedIds(requireBootstrap(), state)) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        }
        // P4, not this structural cross-check, establishes the target key/native-binding origin.
        requireOwned()
        return complete
    }

    fun prepareNewCompanyAndHead(facts: LocalCompanyFoundingFactsV1): LocalCompanyFoundingAction {
        requireNewIssuance()
        environment.requireSupportedProtection(facts.company.protection)
        keys.assertCompletedOriginalKeys()
        val legacy = registry.bootstrapRecord()
        var original = registry.companyBootstrapRecord()
        if (legacy != null && original == null) localCompanyRefuse(LocalCompanyAuthorityFailure.HEAD_ENROLLMENT_REQUIRED)
        if (registry.readState(keys) != null) selfOwnedFail(SelfOwnedInstallationFailureV1.ALREADY_ENROLLED)
        if (original == null) {
            val ids = newIds(15, emptySet())
            val nonce = AndroidP256Primitives.randomBytes(32)
            val base = try {
                SelfOwnedBootstrapV1(0L, ids[0], ids[1], ids[2], ids[3], ids[4], ids[5], facts.company,
                    environment.androidTargetV1(), registry.namespaceIdentity(), SelfOwnedOctetsV1(nonce),
                    SelfOwnedNativeKeyRoleV1.entries.mapIndexed { index, role ->
                        SelfOwnedNativeKeyIntentV1(role, ids[6 + index], SelfOwnedKeyIntentPhaseV1.NOT_STARTED,
                            null, null, SelfOwnedOctetsV1(ByteArray(0)))
                    }, false)
            } finally { nonce.fill(0) }
            registry.retainCompanyBootstrap(LocalCompanyAuthorityBootstrapV1(0L, base, ids[10], ids[11], facts.headName,
                facts.headLogin, facts.headLifetime, LocalCompanyHeadKeyRoleV1.entries.mapIndexed { index, role ->
                    LocalCompanyHeadKeyIntentV1(role, ids[12 + index], 0L, SelfOwnedKeyIntentPhaseV1.NOT_STARTED,
                        null, null, SelfOwnedOctetsV1(ByteArray(0)))
                }))
            original = requireBootstrap()
        }
        if (original.original.enrollmentStarted) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        if (!sameProvisionerCompanyFacts(original.original.facts, facts.company) ||
            original.headName != facts.headName || original.headLogin != facts.headLogin || original.lifetime != facts.headLifetime) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        }
        requireNewIssuance(); keys.prepareCompanyKeys(); requireNewIssuance()
        return foundingAction(requireBootstrap())
    }

    /** Read/derive only. This operation completes before signing/persistence is admitted. */
    fun prepareFoundingCredentials(action: LocalCompanyFoundingAction, installerSecret: CharArray,
        headSecret: CharArray): PreparedFoundingCredentials {
        requireNewIssuance()
        if (preparedFounding.get() != null) selfOwnedFail(SelfOwnedInstallationFailureV1.BUSY)
        // Static diagnostics are outside the lifecycle monitor; the original watchdog stays armed.
        try { work.platform.info("Company founding phase: body-admitted") }
        catch (_: Throwable) { /* best-effort diagnostics never change the original result */ }
        val original = requireBootstrap()
        if (registry.readState(keys) != null) selfOwnedFail(SelfOwnedInstallationFailureV1.ALREADY_ENROLLED)
        if (original.original.enrollmentStarted) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        if (!original.complete()) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        keys.assertAllCompanyKeys(); requireFoundingAction(action, original)
        try { work.platform.info("Company founding phase: installer-credential-next") }
        catch (_: Throwable) { /* best-effort diagnostics never change the original result */ }
        val installer = authentication.prepareFoundingInstaller(installerSecret)
        var head: AndroidLocalFoundingVerifier? = null
        var prepared: PreparedFoundingCredentials? = null
        try {
            try { work.platform.info("Company founding phase: installer-credential-returned") }
            catch (_: Throwable) { /* best-effort diagnostics never change the original result */ }
            try { work.platform.info("Company founding phase: head-credential-next") }
            catch (_: Throwable) { /* best-effort diagnostics never change the original result */ }
            head = authentication.prepareFoundingHead(headSecret)
            try { work.platform.info("Company founding phase: head-credential-returned") }
            catch (_: Throwable) { /* best-effort diagnostics never change the original result */ }
            requireNewIssuance()
            if (requireBootstrap() !== original) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
            requireFoundingAction(action, original)
            prepared = PreparedFoundingCredentials(action, original, installer, head)
            if (!preparedFounding.compareAndSet(null, prepared)) selfOwnedFail(SelfOwnedInstallationFailureV1.BUSY)
            return prepared
        } catch (failure: Throwable) {
            prepared?.let { preparedFounding.compareAndSet(it, null) }
            installer.clear(); head?.clear(); throw failure
        }
    }

    /** Only the exact privately retained preparation can be consumed, once. No renewed action
     * expiry and no rearm of running native work; all writes/readback retain ordinary admission. */
    fun completeNewCompanyAndHead(prepared: PreparedFoundingCredentials): LocalCompanyAuthorityView {
        requireNewIssuance()
        if (!preparedFounding.compareAndSet(prepared, null)) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        try {
            val action = prepared.action
            val original = requireBootstrap()
            if (original !== prepared.original) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
            if (registry.readState(keys) != null) selfOwnedFail(SelfOwnedInstallationFailureV1.ALREADY_ENROLLED)
            if (original.original.enrollmentStarted) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            if (!original.complete()) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
            keys.assertAllCompanyKeys(); requireFoundingAction(action, original)
            if (prepared.installer.boot != environment.bootCount || prepared.head.boot != environment.bootCount) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
            }
            val installer = prepared.installer.copyIntoAccount { salt, verifier ->
                LocalProvisionerInstallerV1(original.original.installerId, original.original.facts.responsiblePersonName,
                    original.original.facts.installerLogin, 0L, 0L, true, SelfOwnedOctetsV1(salt), SelfOwnedOctetsV1(verifier),
                    0, null, prepared.installer.boot, prepared.installer.createdElapsedMillis, 0L)
            }
            val head = prepared.head.copyIntoAccount { salt, verifier ->
                LocalCompanyHeadAccountV1(original.headAuthorityId, original.headEnrollmentId, original.headName, original.headLogin,
                    0L, 0L, true, 0L, true, 0L, true, SelfOwnedOctetsV1(salt), SelfOwnedOctetsV1(verifier),
                    0, null, prepared.head.boot, prepared.head.createdElapsedMillis, 0L)
            }
            prepared.clear()
            val root = keys.rootAnnouncement()
            val delegation = keys.issuerDelegation(root)
            val enrollment = keys.signFreshHeadEnrollment()
            val state = LocalCompanyAuthorityStateV1(LocalProvisionerRegistryStateV1(0L, root, delegation, true, 1L,
                listOf(installer), emptyList()), enrollment, head, emptyList(), emptyList())
            // This preflight also reserves both buckets; capacity cannot strand a founding marker.
            registry.requireCompanyCapacity(state)
            requireFoundingAction(action, original); requireNewIssuance()
            try { work.platform.info("Company founding phase: retention-next") }
            catch (_: Throwable) { /* best-effort diagnostics never change the original result */ }
            registry.retainEnrollmentStart()
            try { work.platform.info("Company founding phase: bootstrap-retention-returned") }
            catch (_: Throwable) { /* best-effort diagnostics never change the original result */ }
            val retained = registry.retainCompanyState(state, keys)
            try { work.platform.info("Company founding phase: retention-returned") }
            catch (_: Throwable) { /* best-effort diagnostics never change the original result */ }
            requireNewIssuance()
            return companyView(requireBootstrap(), retained)
        } finally { prepared.clear() }
    }

    /** Bounded scratch transfer only; called by the final task factory AFTER work admission.
     * Its independent buffers cannot be erased by the original caller's finally cleanup. */
    fun adoptFoundingCredentials(prepared: PreparedFoundingCredentials): PreparedFoundingCredentials {
        val installer = prepared.installer.copyForExecution()
        var head: AndroidLocalFoundingVerifier? = null
        try {
            head = prepared.head.copyForExecution()
            val adopted = PreparedFoundingCredentials(prepared.action, prepared.original, installer, head)
            if (!preparedFounding.compareAndSet(prepared, adopted)) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
            }
            prepared.clear()
            return adopted
        } catch (failure: Throwable) { installer.clear(); head?.clear(); throw failure }
    }

    /** Pure scratch cleanup, safe after cancelled delivery; never native close or rollback. */
    fun discardFoundingCredentials(prepared: PreparedFoundingCredentials) {
        preparedFounding.compareAndSet(prepared, null)
        prepared.clear()
    }

    internal class PreparedFoundingCredentials(val action: LocalCompanyFoundingAction,
        val original: LocalCompanyAuthorityBootstrapV1, val installer: AndroidLocalFoundingVerifier,
        val head: AndroidLocalFoundingVerifier) {
        fun clear() { installer.clear(); head.clear() }
        override fun toString(): String = "PreparedFoundingCredentials([REDACTED])"
    }

    fun withdrawHeadIssuance(purpose: LocalCompanyHeadPurposeV1?) {
        denyPurpose(purpose); requireOwned()
        val state = requireState(); val head = state.head
        val policyEnabled = head.policyEnabled && purpose != null && purpose != LocalCompanyHeadPurposeV1.POLICY_PUBLISH
        val installationEnabled = head.installationEnabled && purpose != null && purpose != LocalCompanyHeadPurposeV1.FULL_D7_INSTALLATION_APPROVE
        val enabled = head.enabled && purpose != null
        if (head.enabled == enabled && head.policyEnabled == policyEnabled && head.installationEnabled == installationEnabled) return
        if (enabled != head.enabled) capacityRevision(head.accountRevision)
        if (policyEnabled != head.policyEnabled) capacityRevision(head.policyRevision)
        if (installationEnabled != head.installationEnabled) capacityRevision(head.installationRevision)
        registry.retainCompanyState(state.next(head = head.copy(enabled = enabled,
            accountRevision = head.accountRevision + (if (head.enabled != enabled) 1L else 0L),
            policyEnabled = policyEnabled, policyRevision = head.policyRevision + (if (head.policyEnabled != policyEnabled) 1L else 0L),
            installationEnabled = installationEnabled,
            installationRevision = head.installationRevision + (if (head.installationEnabled != installationEnabled) 1L else 0L))), keys)
        requireOwned()
    }

    fun preparePolicyPublication(proposal: LocalCompanyPolicyProposal): LocalCompanyPolicyAction {
        requireNewIssuance(LocalCompanyHeadPurposeV1.POLICY_PUBLISH)
        val original = requireBootstrap(); val state = requireState()
        requirePurpose(state, LocalCompanyHeadPurposeV1.POLICY_PUBLISH)
        val allocation = state.original.allocations.singleOrNull() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        // Same predecessor + changed proposal is a conflict, not an invented next action.
        val replay = state.policies.singleOrNull { samePublicationReference(it.policy().predecessor, proposal.expectedPredecessor) }
        if (replay != null) {
            val expected = proposedPolicyRecord(proposal, state, original, replay.originalActionId,
                replay.policy().policyId, replay.policy().publicationId, replay.delegation().delegationId,
                replay.delegation().publicationId, replay.policy().revision, replay.delegation().predecessor,
                replay.delegation().subject)
            if (replay.policyBody != expected.policyBody || replay.delegationBody != expected.delegationBody) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
            }
            return policyAction(replay)
        }
        // P4 may already have forced an attempt whose ID has not reached P1 after a
        // lost reply. Until the genuine claim/tombstone consumes this admission, P1
        // cannot allocate more stable authority IDs against an incomplete census.
        // Exact older actions/results remain available; cancellation is not release.
        if (state.installationAdmission != null) {
            localCompanyRefuse(LocalCompanyAuthorityFailure.ORIGINAL_ATTEMPT_SETTLEMENT_REQUIRED)
        }
        val preceding = state.policies.lastOrNull()
        if (preceding != null && preceding.phase != LocalCompanyPolicyPhaseV1.ISSUED) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        val priorReference = preceding?.let { reference(it.policy().publicationId, it.policy().revision, it.policyBody) }
        if (!samePublicationReference(priorReference, proposal.expectedPredecessor)) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        val revision = (preceding?.policy()?.revision?.value ?: 0L)
        capacityRevision(revision)
        if (state.policies.size == LocalCompanyAuthorityStateV1.MAX_POLICY_RECORDS) selfOwnedFail(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
        val ids = newIds(if (preceding == null) 5 else 3, reservedIds(original, state))
        val record = proposedPolicyRecord(proposal, state, original, ids[0], preceding?.policy()?.policyId ?: ids[3], ids[1],
            preceding?.delegation()?.delegationId ?: ids[4], ids[2], Revision(revision + 1L),
            preceding?.let { reference(it.delegation().publicationId, it.delegation().revision, it.delegationBody) }, null)
        if (record.policy().branch != allocation.body.coordinates.scope) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        requireNewIssuance(LocalCompanyHeadPurposeV1.POLICY_PUBLISH)
        val retained = registry.retainCompanyState(state.next(policies = state.policies + record), keys)
        requireNewIssuance(LocalCompanyHeadPurposeV1.POLICY_PUBLISH)
        return policyAction(retained.policies.last())
    }

    fun approvePolicyPublication(action: LocalCompanyPolicyAction, login: String, secret: CharArray): LocalCompanyPolicyPublication {
        var state = requireState()
        var record = state.policies.singleOrNull { it.originalActionId == action.originalActionId }
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        requirePolicyAction(action, record, current = record.phase != LocalCompanyPolicyPhaseV1.ISSUED)
        if (record.phase == LocalCompanyPolicyPhaseV1.ISSUED) return policyResult(record)
        requireNewIssuance(LocalCompanyHeadPurposeV1.POLICY_PUBLISH)
        requirePolicyHeadRevisions(record, state)
        val approval = authentication.authenticateHeadAction(LocalCompanyHeadPurposeV1.POLICY_PUBLISH,
            record.originalActionId, localCompanyPolicyActionDigestV1(record), login, secret, action.expiresElapsedMillis)
        state = requireState(); record = state.policies.single { it.originalActionId == action.originalActionId }
        authentication.requireHeadStillCurrent(approval, state, LocalCompanyHeadPurposeV1.POLICY_PUBLISH,
            record.originalActionId, localCompanyPolicyActionDigestV1(record))
        requireNewIssuance(LocalCompanyHeadPurposeV1.POLICY_PUBLISH)
        if (record.phase == LocalCompanyPolicyPhaseV1.PREPARED) {
            state = registry.retainCompanyState(state.replacePolicy(record.copy(phase = LocalCompanyPolicyPhaseV1.SIGNING_INTENT)), keys)
            record = state.policies.single { it.originalActionId == action.originalActionId }
        }
        val result = keys.signCompanyPolicy(record, approval)
        state = requireState()
        val current = state.policies.single { it.originalActionId == action.originalActionId }
        if (current != record) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        authentication.requireHeadStillCurrent(approval, state, LocalCompanyHeadPurposeV1.POLICY_PUBLISH,
            record.originalActionId, localCompanyPolicyActionDigestV1(record))
        requireNewIssuance(LocalCompanyHeadPurposeV1.POLICY_PUBLISH)
        val encoded = LocalCompanyPolicyWireV1.encodeSignedPolicy(result)
        val retained = try { registry.retainCompanyState(state.replacePolicy(record.copy(phase = LocalCompanyPolicyPhaseV1.ISSUED,
            signedPolicy = SelfOwnedOctetsV1(encoded))), keys) } finally { encoded.fill(0) }
        authentication.requireHeadStillCurrent(approval, retained, LocalCompanyHeadPurposeV1.POLICY_PUBLISH,
            record.originalActionId, localCompanyPolicyActionDigestV1(record))
        requireNewIssuance(LocalCompanyHeadPurposeV1.POLICY_PUBLISH)
        return policyResult(retained.policies.single { it.originalActionId == record.originalActionId })
    }

    fun recoverPolicyPublication(originalActionId: DomainId): LocalCompanyPolicyPublication {
        requireOwned()
        val record = requireState().policies.singleOrNull { it.originalActionId == originalActionId }
            ?: localCompanyRefuse(LocalCompanyAuthorityFailure.ORIGINAL_RESULT_REQUIRED)
        return policyResult(record).also { requireOwned() }
    }

    private fun policyResult(record: LocalCompanyPolicyRecordV1): LocalCompanyPolicyPublication =
        LocalCompanyPolicyPublication(record.originalActionId, record.result())

    private fun proposedPolicyRecord(proposal: LocalCompanyPolicyProposal, state: LocalCompanyAuthorityStateV1,
        original: LocalCompanyAuthorityBootstrapV1, actionId: DomainId, policyId: DomainId, publicationId: DomainId,
        delegationId: DomainId, delegationPublicationId: DomainId, revision: Revision,
        delegationPredecessor: PurchasingPublicationReferenceV1?, replaySubject: PurchasingNamedAuthorityV1?): LocalCompanyPolicyRecordV1 {
        val branch = state.original.allocations.singleOrNull()?.body?.coordinates?.scope
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val head = state.head
        // Native custody/key observations are outside input-error conversion. Only pure
        // construction/encoding below may turn a malformed proposal into INVALID_INPUT.
        val point = keys.headPoint(LocalCompanyHeadKeyRoleV1.POLICY)
        val fingerprint = try { AndroidP256Primitives.rootFingerprint(keys.rootBody()) }
        catch (failure: Throwable) { point.fill(0); throw failure }
        return try {
            val subject = replaySubject ?: PurchasingNamedAuthorityV1(original.headAuthorityId, original.headName,
                PurchasingAuthorityRoleV1.COMPANY_HEAD, Revision(head.accountRevision), original.key(LocalCompanyHeadKeyRoleV1.POLICY).keyId,
                PurchasingPublicPointV1(point))
            val root = PurchasingRootReferenceV1(original.original.key(SelfOwnedNativeKeyRoleV1.ROOT).keyId, PayloadDigest(fingerprint))
            val delegation = PurchasingAuthorityDelegationV1(branch.company(), root, delegationId, delegationPublicationId, revision,
                delegationPredecessor, PurchasingAuthorityIssuerV1.Root(root.keyId), subject, proposal.grants, proposal.validity)
            val delegationBytes = LocalCompanyPolicyWireV1.encodeDelegationBody(delegation)
            val record = try {
                val policy = PurchasingPolicyPublicationV1(branch, root, policyId, publicationId, revision, proposal.expectedPredecessor,
                    PurchasingDelegatedIssuerV1(subject.authorityId, subject.accountRevision, subject.keyId, delegationId,
                        PurchasingPublicationReferenceV1(delegationPublicationId, revision, PayloadDigest(AndroidP256Primitives.hashHex(delegationBytes)))),
                    proposal.validity, proposal.retentionClasses, proposal.rules)
                val policyBytes = LocalCompanyPolicyWireV1.encodePolicyBody(policy)
                try { LocalCompanyPolicyRecordV1(actionId, subject.accountRevision.value, head.credentialRevision,
                    head.policyRevision, original.key(LocalCompanyHeadKeyRoleV1.POLICY).keyRevision,
                    SelfOwnedOctetsV1(delegationBytes), SelfOwnedOctetsV1(policyBytes), LocalCompanyPolicyPhaseV1.PREPARED, null) }
                finally { policyBytes.fill(0) }
            } finally { delegationBytes.fill(0) }
            requireOriginalCompanyPolicyV1(record.policy(), record.delegation(), state.original, original, fingerprint)
            requirePolicyEnvelopeFits(record)
            record
        } catch (_: IllegalArgumentException) { selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT) }
        finally { point.fill(0) }
    }

    private fun policyAction(record: LocalCompanyPolicyRecordV1): LocalCompanyPolicyAction =
        LocalCompanyPolicyAction(actionOwner, record.originalActionId, record.policy(), record.delegation(), digest(record.policyBody),
            digest(record.delegationBody), record.headAccountRevision, record.headCredentialRevision, record.purposeRevision,
            record.keyRevision, environment.bootCount, pendingDeadline())

    private fun requirePolicyAction(action: LocalCompanyPolicyAction, record: LocalCompanyPolicyRecordV1, current: Boolean) {
        if (action.owner !== actionOwner || action.originalActionId != record.originalActionId ||
            action.policyBodyDigest != digest(record.policyBody) || action.delegationBodyDigest != digest(record.delegationBody) ||
            action.headAccountRevision != record.headAccountRevision || action.headCredentialRevision != record.headCredentialRevision ||
            action.purposeRevision != record.purposeRevision || action.keyRevision != record.keyRevision ||
            current && (action.boot != environment.bootCount || work.clock() >= action.expiresElapsedMillis)) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        }
        val p = LocalCompanyPolicyWireV1.encodePolicyBody(action.policy); val d = LocalCompanyPolicyWireV1.encodeDelegationBody(action.delegation)
        try { if (!record.policyBody.same(p) || !record.delegationBody.same(d)) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT) }
        finally { p.fill(0); d.fill(0) }
    }

    private fun requirePolicyHeadRevisions(record: LocalCompanyPolicyRecordV1, state: LocalCompanyAuthorityStateV1) {
        val original = requireBootstrap(); val head = state.head
        requirePurpose(state, LocalCompanyHeadPurposeV1.POLICY_PUBLISH)
        if (record.headAccountRevision != head.accountRevision || record.headCredentialRevision != head.credentialRevision ||
            record.purposeRevision != head.policyRevision || record.keyRevision != original.key(LocalCompanyHeadKeyRoleV1.POLICY).keyRevision) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
        }
    }

    /** Pure exact envelope sizing only; these zero signature bytes NEVER leave this function. */
    private fun requirePolicyEnvelopeFits(record: LocalCompanyPolicyRecordV1) {
        val placeholder = ByteArray(64)
        try {
            val shape = PurchasingSignedPolicyV1(record.policy(), PurchasingSignatureV1(placeholder),
                PurchasingDelegationDocumentsV1(listOf(PurchasingSignedDelegationV1(record.delegation(), PurchasingSignatureV1(placeholder)))))
            LocalCompanyPolicyWireV1.encodeSignedPolicy(shape).fill(0)
        } finally { placeholder.fill(0) }
    }

    private fun foundingAction(original: LocalCompanyAuthorityBootstrapV1,
        enrollmentRetained: Boolean = false): LocalCompanyFoundingAction {
        val base = original.original
        return LocalCompanyFoundingAction(actionOwner, base.actionId, base.facts.companyName, base.facts.responsiblePersonName,
            base.facts.installerLogin, original.headName, original.headLogin, original.lifetime,
            AndroidP256Primitives.rootFingerprint(keys.rootBody()), base.organizationId, base.companyId,
            original.headAuthorityId, original.headEnrollmentId, base.facts.protection, enrollmentRetained,
            environment.bootCount, pendingDeadline())
    }

    private fun requireFoundingAction(action: LocalCompanyFoundingAction, original: LocalCompanyAuthorityBootstrapV1) {
        val base = original.original
        if (action.owner !== actionOwner || action.enrollmentRetained || action.originalId != base.actionId || action.organizationId != base.organizationId ||
            action.companyId != base.companyId || action.companyName != base.facts.companyName || action.installerName != base.facts.responsiblePersonName ||
            action.installerLogin != base.facts.installerLogin || action.headName != original.headName || action.headLogin != original.headLogin ||
            action.headAuthorityId != original.headAuthorityId || action.headEnrollmentId != original.headEnrollmentId ||
            action.headLifetime != original.lifetime || action.protection != base.facts.protection ||
            action.rootFingerprint != AndroidP256Primitives.rootFingerprint(keys.rootBody()) ||
            action.boot != environment.bootCount || work.clock() >= action.expiresElapsedMillis) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
    }

    private fun companyView(original: LocalCompanyAuthorityBootstrapV1, state: LocalCompanyAuthorityStateV1): LocalCompanyAuthorityView {
        val body = LocalCompanyAuthorityWireV1.encodeEnrollmentBody(state.enrollment.body)
        return try { LocalCompanyAuthorityView(original.original.rootId, original.original.organizationId, original.original.companyId,
            original.original.facts.companyName, AndroidP256Primitives.rootFingerprint(keys.rootBody()), original.original.facts.protection,
            original.headAuthorityId,
            original.headEnrollmentId, original.headName, original.headLogin, Revision(state.head.accountRevision), Revision(state.head.credentialRevision),
            state.head.enabled && !issuance.denied(),
            state.head.purposeEnabled(LocalCompanyHeadPurposeV1.POLICY_PUBLISH) && !issuance.denied(LocalCompanyHeadPurposeV1.POLICY_PUBLISH),
            state.head.purposeEnabled(LocalCompanyHeadPurposeV1.FULL_D7_INSTALLATION_APPROVE) &&
                !issuance.denied(LocalCompanyHeadPurposeV1.FULL_D7_INSTALLATION_APPROVE),
            PayloadDigest(AndroidP256Primitives.hashHex(body))) } finally { body.fill(0) }
    }

    private fun requireBootstrap(): LocalCompanyAuthorityBootstrapV1 = registry.companyBootstrapRecord()
        ?: localCompanyRefuse(LocalCompanyAuthorityFailure.HEAD_ENROLLMENT_REQUIRED)
    private fun requireState(): LocalCompanyAuthorityStateV1 {
        requireOwned()
        return registry.readCompanyState(keys) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
    }
    private fun requirePurpose(state: LocalCompanyAuthorityStateV1, purpose: LocalCompanyHeadPurposeV1) {
        if (!state.head.purposeEnabled(purpose)) selfOwnedFail(SelfOwnedInstallationFailureV1.ISSUANCE_WITHDRAWN)
    }
    private fun requireOwned() { work.requireActive(); environment.requireCurrent(); registry.assertContinuity(); work.requireActive() }
    private fun requireNewIssuance(purpose: LocalCompanyHeadPurposeV1? = null) {
        requireOwned()
        issuance.requireIssuance(purpose)
    }
    private fun pendingDeadline(): Long {
        val now = work.clock()
        if (now > Long.MAX_VALUE - SelfOwnedInstallationProfileV1.PENDING_ACTION_MILLIS) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        return now + SelfOwnedInstallationProfileV1.PENDING_ACTION_MILLIS
    }
    private fun digest(value: SelfOwnedOctetsV1): PayloadDigest {
        val bytes = value.copyBytes()
        return try { PayloadDigest(AndroidP256Primitives.hashHex(bytes)) } finally { bytes.fill(0) }
    }
    private fun reference(id: DomainId, revision: Revision, body: SelfOwnedOctetsV1): PurchasingPublicationReferenceV1 =
        PurchasingPublicationReferenceV1(id, revision, digest(body))
    private fun samePublicationReference(a: PurchasingPublicationReferenceV1?, b: PurchasingPublicationReferenceV1?): Boolean =
        if (a == null || b == null) a == null && b == null else a.publicationId == b.publicationId && a.revision == b.revision && a.bodyDigest == b.bodyDigest
    private fun newIds(count: Int, reserved: Set<DomainId>): List<DomainId> {
        val ids = List(count) { AndroidP256Primitives.newId() }
        if (ids.distinct().size != count || ids.any { it in reserved }) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        return ids
    }
    private fun reservedIds(original: LocalCompanyAuthorityBootstrapV1, state: LocalCompanyAuthorityStateV1): Set<DomainId> = buildSet {
        addAll(original.allIds())
        for (allocation in state.original.allocations) {
            val c = allocation.body.coordinates
            addAll(listOf(c.allocationId, c.scope.branchId, c.installationId, c.assignmentId, c.deviceId, c.storageInstanceId,
                c.storageKeyId, c.pepperKeyId, c.targetSigningKeyId, c.firstActorId))
        }
        for (record in state.policies) {
            val p = record.policy(); val d = record.delegation()
            addAll(listOf(record.originalActionId, p.policyId, p.publicationId, d.delegationId, d.publicationId))
        }
        for (claim in state.installationClaims) { add(claim.issuedAttemptId); add(claim.originalRequestId) }
        state.installationAdmission?.let { add(it.originalRequestId) }
    }
}

/**
 * One invocation's concrete same-P1 admission. This object stores no second durable D7 or
 * lifecycle. Its immutable references originate in the real helper's retained admission
 * and purpose authentication; only that helper's exact active object may pass the gate.
 * A failed force/return invalidates this object, never frees the durable reservation.
 */
internal class AndroidLocalCompanyInstallationAdmission internal constructor(
    private val authority: LocalCompanyInstallationAdmissionAuthority,
    internal val originalAnchor: AndroidSelfOwnedHistoryAnchor,
    internal val originalProposal: OriginalInstallationProposal,
    internal val originalSnapshot: LocalBranchInstallationSnapshotV2,
    internal val nextAttemptSequence: Long,
    /** The authority's own opaque head approval (V1 Android or V2 desktop); compared by that authority only. */
    internal val headApproval: Any,
    val headAccountRevision: Long,
    val headCredentialRevision: Long,
    val installationPurposeRevision: Long,
    val installationKeyRevision: Long,
    val deadlineElapsedMillis: Long,
    val protectedHeadStateDigest: PayloadDigest,
) {
    internal constructor(authority: AndroidLocalCompanyAuthority, originalAnchor: AndroidSelfOwnedHistoryAnchor,
        originalProposal: OriginalInstallationProposal, originalSnapshot: LocalBranchInstallationSnapshotV2,
        nextAttemptSequence: Long, headApproval: AndroidLocalInstallerAuthentication.HeadApproval,
        protectedHeadStateDigest: PayloadDigest) : this(authority, originalAnchor, originalProposal, originalSnapshot,
        nextAttemptSequence, headApproval, headApproval.accountRevision, headApproval.credentialRevision,
        headApproval.purposeRevision, headApproval.keyRevision, headApproval.expires, protectedHeadStateDigest)
    private var prospectiveAttempt: DomainId? = null
    private var consumed = false
    private var checked = false
    private var invalidated = false

    /** Exactly once, after P4 chooses an unused candidate ID but BEFORE any attempt force. */
    fun requireBeforeAttemptRetention(actualAnchor: AndroidSelfOwnedHistoryAnchor,
        actualWork: AndroidOwnedInstallationWork, actualEnvironment: SelfOwnedOriginalEnvironment,
        proposal: OriginalInstallationProposal, snapshot: LocalBranchInstallationSnapshotV2,
        attemptSequence: Long, prospectiveAttemptId: DomainId) {
        authority.requireBeforeInstallationAttemptRetention(this, actualAnchor, actualWork, actualEnvironment,
            proposal, snapshot, attemptSequence, prospectiveAttemptId)
    }

    internal fun consumeProspectiveAttempt(id: DomainId) {
        if (consumed || invalidated) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        consumed = true
        prospectiveAttempt = id
    }

    internal fun markChecksCompleted() {
        if (!consumed || checked || invalidated) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        checked = true
    }

    internal fun invalidateAfterReservation() { invalidated = true }

    /** Identity check only, NOT a renewed approval or permission after the one-shot ends. */
    internal fun requireReturnedAttempt(facts: LocalInstallationAttemptFactsV2) {
        if (!consumed || !checked || !invalidated || facts.issuedAttemptId != prospectiveAttempt ||
            facts.attemptSequence != nextAttemptSequence || facts.headAccountRevision != headAccountRevision ||
            facts.headCredentialRevision != headCredentialRevision || facts.installationPurposeRevision != installationPurposeRevision ||
            facts.installationKeyRevision != installationKeyRevision || facts.protectedHeadStateDigest != protectedHeadStateDigest ||
            facts.deadlineElapsedMillis > deadlineElapsedMillis) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
    }

    override fun toString(): String = "AndroidLocalCompanyInstallationAdmissionV1([REDACTED])"
}

internal fun localCompanyPolicyActionDigestV1(record: LocalCompanyPolicyRecordV1): PayloadDigest {
    val bytes = selfOwnedEncode("exact-local-company-policy-action/v1", 2 * LocalCompanyPolicyFormatV1.MAX_BODY_BYTES + 1024) {
        id(record.originalActionId); long(record.headAccountRevision); long(record.headCredentialRevision)
        long(record.purposeRevision); long(record.keyRevision); octets(record.delegationBody); octets(record.policyBody)
    }
    return try { PayloadDigest(AndroidP256Primitives.hashHex(bytes)) } finally { bytes.fill(0) }
}
