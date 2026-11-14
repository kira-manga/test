package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/**
 * Windows/macOS original-installation issuer over the seven-key V2 company container. It mirrors the
 * Android V1 [AndroidLocalCompanyAuthority] original-installation operations step for step (durable
 * admission reservation before P4 may force an attempt, the one-shot same-work admission, the claim
 * retained before the INSTALLATION head signature, exact readback joins, ACK intent before the ROOT
 * signature, retirement tombstones) with only the custody changed:
 *
 * - the head ceremony is [SelfOwnedDesktopCompanyHeadV2] (FULL_D7_INSTALLATION_APPROVE), re-checked
 *   against every container state this issuer commits with (revocation serialized with commit);
 * - signatures come only from [SelfOwnedCompanyNativeKeysV2] and are verified before retention;
 * - allocation cross-checks use the V2 preparation grant digests the desktop target signer uses.
 *
 * Every method is a synchronous native operation inside the platform's admitted owned work stage.
 */
internal class SelfOwnedDesktopCompanyInstallationV2(
    private val environment: SelfOwnedOriginalEnvironment,
    private val registry: SelfOwnedProvisionerRegistryStoreV2,
    keys: SelfOwnedOriginalProvisionerKeys,
    private val work: AndroidOwnedInstallationWork,
    private val historyAnchor: AndroidSelfOwnedHistoryAnchor?,
    private val issuance: AndroidLocalCompanyIssuanceGate,
) : LocalCompanyInstallationAdmissionAuthority, LocalCompanyOriginalInstallationIssuer {
    private val keys: SelfOwnedCompanyNativeKeysV2 = SelfOwnedCompanyAuthorityV2.requireCompanyKeys(keys)
    private val head = SelfOwnedDesktopCompanyHeadV2(environment, registry, this.keys, work, issuance)
    private val actionOwner = Any()
    private var activeInstallationAdmission: AndroidLocalCompanyInstallationAdmission? = null
    private var boundApproval: Pair<SelfOwnedDesktopCompanyHeadV2.Approval, DomainId>? = null

    override fun inspectCompanyAuthority(): LocalCompanyAuthorityView {
        val state = requireState()
        val company = requireBootstrap(); val original = company.original
        val body = SelfOwnedNativeProvisionerWireV2.encodeHeadEnrollmentBody(state.enrollment.body)
        return try {
            LocalCompanyAuthorityView(original.rootId, original.organizationId, original.companyId, original.facts.companyName,
                JvmP256Primitives.rootFingerprint(keys.rootBody()), original.facts.protection, company.headAuthorityId,
                company.headEnrollmentId, company.headName, company.headLogin, Revision(state.head.accountRevision),
                Revision(state.head.credentialRevision), state.head.enabled && !issuance.denied(),
                state.head.purposeEnabled(LocalCompanyHeadPurposeV1.POLICY_PUBLISH) && !issuance.denied(LocalCompanyHeadPurposeV1.POLICY_PUBLISH),
                state.head.purposeEnabled(LocalCompanyHeadPurposeV1.FULL_D7_INSTALLATION_APPROVE) &&
                    !issuance.denied(LocalCompanyHeadPurposeV1.FULL_D7_INSTALLATION_APPROVE),
                PayloadDigest(AndroidP256Primitives.hashHex(body)))
        } finally { body.fill(0) }
    }

    override fun readOriginalInstallationHumanReference(): LocalInstallationHumanReferenceV2 {
        val state = requireState()
        return originalInstallationHumanReference(state).also { requireOwned() }
    }

    override fun prepareOriginalInstallationDefinition(proposal: OriginalInstallationProposal): LocalCompanyInstallationAction {
        val owner = requireInstallationOwner()
        requireNewIssuance()
        var state = requireState()
        requirePurpose(state)
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
            // The space/identity reservation precedes any P4 attempt force; only its exact claim consumes it.
            val next = state.next(admission = expected)
            SelfOwnedCompanyAuthorityV2.requireCapacity(next)
            state = head.retainState(state, next)
        }
        owner.requireOriginalProposal(proposal)
        requireNewIssuance()
        val account = state.head
        return LocalCompanyInstallationAction(actionOwner, proposal, complete, expected.nextAttemptSequence,
            account.accountRevision, account.credentialRevision, account.installationRevision,
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
            // No automatic re-signing of an interrupted/expired original attempt.
            if (prior.signedHumanApproval == null || prior.retirementEvidence != null) {
                localCompanyRefuse(LocalCompanyAuthorityFailure.ORIGINAL_ATTEMPT_SETTLEMENT_REQUIRED)
            }
            val original = owner.readOriginalInstallationAttempt()
            if (original.facts.issuedAttemptId != prior.issuedAttemptId) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
            return readOriginalInstallationAttempt(original)
        }
        requireNewIssuance()
        requireInstallationAction(action, state, current = true)
        requireInstallationAdmission(state, action.snapshot, action.nextAttemptSequence)
        requireSameInstallationSnapshot(action.snapshot, owner.requireOriginalProposal(action.originalProposal))
        val requestId = action.definition.originalRequestId; val digest = action.definitionDigest
        val approval = head.authenticate(CEREMONY, requestId, digest, login, secret, action.expiresElapsedMillis)
        state = requireState()
        head.requireStillCurrent(approval, state, CEREMONY, requestId, digest)
        requireInstallationAction(action, state, current = true)
        requireInstallationAdmission(state, action.snapshot, action.nextAttemptSequence)
        requireNewIssuance()
        val headCommitment = protectedHeadDigest(state)
        val admission = AndroidLocalCompanyInstallationAdmission(this, owner, action.originalProposal, action.snapshot,
            action.nextAttemptSequence, approval, approval.accountRevision, approval.credentialRevision,
            approval.purposeRevision ?: uncertain(), approval.keyRevision ?: uncertain(), approval.expires, headCommitment)
        if (activeInstallationAdmission != null) uncertain()
        activeInstallationAdmission = admission
        val original = try {
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
            facts.osBootCount != environment.bootCount || facts.deadlineElapsedMillis > approval.expires) uncertain()
        bindApproval(approval, facts.issuedAttemptId)
        owner.requireOriginalAttemptForIssuance(original)
        state = requireState()
        head.requireStillCurrent(approval, state, CEREMONY, requestId, snapshot.definitionDigest)
        if (protectedHeadDigest(state) != headCommitment) uncertain()
        val claim = originalInstallationClaim(snapshot, facts, state)
        requireNewIssuance()
        state = head.retainState(state, state.next(claims = state.installationClaims + claim, admission = null))
        owner.requireOriginalAttemptForIssuance(original)
        requireNewIssuance()
        val proof = signHumanApproval(snapshot, facts, approval)
        state = requireState()
        if (state.installationClaims.singleOrNull { it.issuedAttemptId == claim.issuedAttemptId } != claim) uncertain()
        head.requireStillCurrent(approval, state, CEREMONY, requestId, snapshot.definitionDigest)
        owner.requireOriginalAttemptForIssuance(original)
        requireNewIssuance()
        val encoded = proof.copyWireBytes()
        val retained = try { head.retainState(state, state.next(claims = state.installationClaims.map {
            if (it.issuedAttemptId == claim.issuedAttemptId) claim.copy(signedHumanApproval = SelfOwnedOctetsV1(encoded)) else it
        })) } finally { encoded.fill(0) }
        val result = readOriginalInstallationAttempt(original)
        head.requireStillCurrent(approval, retained, CEREMONY, requestId, snapshot.definitionDigest)
        owner.requireOriginalAttemptForIssuance(original)
        requireNewIssuance()
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
        if (requireInstallationClaim(requireState(), snapshot, original.facts) != claim) uncertain()
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
            ?: uncertain() } finally { targetBytes.fill(0) }
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
                state = head.retainState(state, state.replaceClaim(claim.copy(acknowledgementFrame = SelfOwnedOctetsV1(frameBytes))))
                claim = requireInstallationClaim(state, snapshot, completion.facts)
            }
            if (claim.signedAcknowledgement == null) {
                owner.requireOriginalCompletion(completion)
                val signed = signAcknowledgement(frame)
                val encoded = signed.copyWireBytes()
                try {
                    state = requireState()
                    if (requireInstallationClaim(state, snapshot, completion.facts) != claim) uncertain()
                    owner.requireOriginalCompletion(completion)
                    state = head.retainState(state, state.replaceClaim(claim.copy(signedAcknowledgement = SelfOwnedOctetsV1(encoded))))
                    claim = requireInstallationClaim(state, snapshot, completion.facts)
                } finally { encoded.fill(0) }
            }
            val bytes = requireNotNull(claim.signedAcknowledgement).copyBytes()
            val result = try { LocalBranchInstallationWireV2.decodeSignedAcknowledgement(bytes, snapshot, completion.facts,
                approvals, completion.receipt, completion.childFacts) ?: uncertain() } finally { bytes.fill(0) }
            verifyInstallationAcknowledgement(result)
            owner.requireOriginalCompletion(completion)
            owner.retainOriginalIssuerAcknowledgement(completion, result)
            owner.requireOriginalCompletion(completion)
            if (requireInstallationClaim(requireState(), snapshot, completion.facts) != claim) uncertain()
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
            // P4-force -> claim interruption: consume ONLY the already reserved identity/capacity.
            val original = originalInstallationClaim(snapshot, retirement.facts, state)
            owner.requireOriginalRetirement(retirement)
            state = head.retainState(state, state.next(claims = state.installationClaims + original, admission = null))
            requireInstallationClaim(state, snapshot, retirement.facts)
        } else requireInstallationClaim(state, snapshot, retirement.facts)
        if (claim.acknowledgementFrame != null || claim.signedAcknowledgement != null) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        claim.signedHumanApproval?.let { originalHumanApproval(claim, snapshot, retirement.facts) }
        val evidence = retirement.copyEvidenceBytes()
        try {
            if (evidence.size !in 1..LocalCompanyInstallationClaimV1.MAXIMUM_RETIREMENT_EVIDENCE_BYTES ||
                PayloadDigest(AndroidP256Primitives.hashHex(evidence)) != retirement.retirementDigest) uncertain()
            if (claim.retirementEvidence == null) {
                owner.requireOriginalRetirement(retirement)
                state = head.retainState(state, state.replaceClaim(claim.copy(retirementEvidence = SelfOwnedOctetsV1(evidence))))
                claim = requireInstallationClaim(state, snapshot, retirement.facts)
            } else if (claim.retirementEvidence?.same(evidence) != true) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
            val tombstone = SelfOwnedNativeProvisionerWireV2.encodeRetiredInstallationClaim(requireBootstrap(), claim)
            val tombstoneDigest = try { PayloadDigest(AndroidP256Primitives.hashHex(tombstone)) } finally { tombstone.fill(0) }
            owner.requireOriginalRetirement(retirement)
            owner.retainOriginalIssuerRetirement(retirement, tombstoneDigest)
            owner.requireOriginalRetirement(retirement)
            if (requireInstallationClaim(requireState(), snapshot, retirement.facts) != claim) uncertain()
        } finally { evidence.fill(0) }
    }

    /** One-shot same-work admission check, called only by P4 through the installed admission. */
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
        val approval = admission.headApproval as? SelfOwnedDesktopCompanyHeadV2.Approval
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        head.requireStillCurrent(approval, state, CEREMONY, snapshot.definition.originalRequestId, snapshot.definitionDigest)
        if (protectedHeadDigest(state) != admission.protectedHeadStateDigest) uncertain()
        if (prospectiveAttemptId in reservedIds(requireBootstrap(), state)) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        SelfOwnedCompanyAuthorityV2.requireCapacity(state)
        requireNewIssuance()
        admission.markChecksCompleted()
    }

    // ---- Signing (claim re-checked before and after each native signature) ----------------------

    private fun bindApproval(approval: SelfOwnedDesktopCompanyHeadV2.Approval, attemptId: DomainId) {
        val bound = boundApproval
        if (bound != null && bound.first === approval && bound.second != attemptId) uncertain()
        boundApproval = approval to attemptId
    }

    private fun signHumanApproval(snapshot: LocalBranchInstallationSnapshotV2, attempt: LocalInstallationAttemptFactsV2,
        approval: SelfOwnedDesktopCompanyHeadV2.Approval): LocalInstallationSignedApprovalV2 {
        val fresh = LocalBranchInstallationWireV2.snapshotDefinition(snapshot.definition)
        if (fresh.definitionDigest != snapshot.definitionDigest || fresh.allocationDigest != snapshot.allocationDigest) uncertain()
        val frame = LocalBranchInstallationWireV2.humanApprovalFrame(fresh, attempt)
        val bytes = frame.copyWireBytes()
        fun current() {
            val state = requireState()
            val claim = state.installationClaims.singleOrNull { it.issuedAttemptId == attempt.issuedAttemptId } ?: uncertain()
            if (claim.signedHumanApproval != null || claim.retirementEvidence != null || claim.acknowledgementFrame != null ||
                !claim.approvalFrame.same(bytes) || claim.definitionDigest != snapshot.definitionDigest ||
                claim.allocationDigest != snapshot.allocationDigest || claim.originalRequestId != snapshot.definition.originalRequestId ||
                claim.attemptSequence != attempt.attemptSequence) uncertain()
            head.requireStillCurrent(approval, state, CEREMONY, snapshot.definition.originalRequestId, snapshot.definitionDigest)
            if (attempt.headAccountRevision != approval.accountRevision || attempt.headCredentialRevision != approval.credentialRevision ||
                attempt.installationPurposeRevision != approval.purposeRevision || attempt.installationKeyRevision != approval.keyRevision ||
                attempt.osBootCount != environment.bootCount || work.clock() >= attempt.deadlineElapsedMillis) uncertain()
            val bound = boundApproval
            if (bound == null || bound.first !== approval || bound.second != attempt.issuedAttemptId) uncertain()
            if (frame.signerKeyId != requireBootstrap().key(LocalCompanyHeadKeyRoleV1.INSTALLATION).keyId) uncertain()
        }
        return try {
            current()
            val signature = keys.signInstallationApproval(frame)
            try { current(); LocalBranchInstallationWireV2.signedApproval(frame, signature) } finally { signature.fill(0) }
        } finally { bytes.fill(0) }
    }

    private fun signAcknowledgement(frame: LocalInstallationCommitAcknowledgementV2): LocalInstallationSignedAcknowledgementV2 {
        val bytes = frame.copyWireBytes()
        fun current() {
            val state = requireState()
            val claim = state.installationClaims.singleOrNull { it.issuedAttemptId == frame.issuedAttemptId } ?: uncertain()
            if (claim.signedHumanApproval == null || claim.signedAcknowledgement != null || claim.retirementEvidence != null ||
                claim.acknowledgementFrame?.same(bytes) != true || claim.originalRequestId != frame.originalRequestId ||
                claim.definitionDigest != frame.definitionDigest || claim.allocationDigest != frame.allocationDigest ||
                frame.rootKeyId != requireBootstrap().original.key(SelfOwnedNativeKeyRoleV1.ROOT).keyId) uncertain()
            // Deliberately no renewed head enablement, password, attempt expiry or new-work grant.
        }
        return try {
            current()
            val signature = keys.signInstallationAcknowledgement(frame)
            try { current(); LocalBranchInstallationWireV2.signedAcknowledgement(frame, signature) } finally { signature.fill(0) }
        } finally { bytes.fill(0) }
    }

    // ---- Checks (Android V1 rules over the V2 container) ----------------------------------------

    private fun requireInstallationOwner(): AndroidSelfOwnedHistoryAnchor {
        requireOwned()
        val owner = historyAnchor ?: localCompanyRefuse(LocalCompanyAuthorityFailure.ORIGINAL_TARGET_OWNER_REQUIRED)
        owner.requireIssuerParticipant(work, environment)
        return owner
    }

    private fun requireInstallationAction(action: LocalCompanyInstallationAction, state: LocalCompanyAuthorityStateV2,
        current: Boolean) {
        if (action.owner !== actionOwner || action.nextAttemptSequence !in 1 until Long.MAX_VALUE) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        }
        requireSameInstallationSnapshot(action.snapshot, action.originalProposal.snapshot)
        requireOriginalInstallationSnapshot(action.snapshot, state)
        if (current) {
            val account = state.head
            requirePurpose(state)
            if (action.headAccountRevision != account.accountRevision || action.headCredentialRevision != account.credentialRevision ||
                action.purposeRevision != account.installationRevision ||
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
                rebuilt.allocationDigest != actual.allocationDigest || rebuilt.originalTargetKeyDigest != actual.originalTargetKeyDigest) uncertain()
            if (!prior.contentEquals(canonical) || expected.definitionDigest != actual.definitionDigest ||
                expected.allocationDigest != actual.allocationDigest || expected.originalTargetKeyDigest != actual.originalTargetKeyDigest) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
            }
        } finally { canonical.fill(0); captured.fill(0); prior.fill(0) }
    }

    private fun protectedHeadDigest(state: LocalCompanyAuthorityStateV2): PayloadDigest {
        val bytes = SelfOwnedNativeProvisionerWireV2.headStateCommitment(state, requireBootstrap())
        return try { PayloadDigest(AndroidP256Primitives.hashHex(bytes)) } finally { bytes.fill(0) }
    }

    private fun requireInstallationAdmission(state: LocalCompanyAuthorityStateV2, snapshot: LocalBranchInstallationSnapshotV2,
        sequence: Long) {
        val expected = LocalCompanyInstallationAdmissionV1(snapshot.definition.originalRequestId, snapshot.allocationDigest,
            snapshot.definitionDigest, sequence)
        if (state.installationAdmission != expected || sequence != state.installationClaims.size.toLong() + 1L ||
            state.installationClaims.any { it.retirementEvidence == null }) {
            localCompanyRefuse(LocalCompanyAuthorityFailure.ORIGINAL_ATTEMPT_SETTLEMENT_REQUIRED)
        }
    }

    private fun originalInstallationClaim(snapshot: LocalBranchInstallationSnapshotV2, facts: LocalInstallationAttemptFactsV2,
        state: LocalCompanyAuthorityStateV2): LocalCompanyInstallationClaimV1 {
        requireInstallationAdmission(state, snapshot, facts.attemptSequence)
        if (facts.issuedAttemptId in reservedIds(requireBootstrap(), state)) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        requireAttemptHeadReference(facts, state)
        val frame = LocalBranchInstallationWireV2.humanApprovalFrame(snapshot, facts).copyWireBytes()
        return try { LocalCompanyInstallationClaimV1(facts.issuedAttemptId, facts.attemptSequence,
            snapshot.definition.originalRequestId, snapshot.allocationDigest, snapshot.definitionDigest,
            SelfOwnedOctetsV1(frame), null, null, null, null) } finally { frame.fill(0) }
    }

    private fun requireInstallationClaim(state: LocalCompanyAuthorityStateV2, snapshot: LocalBranchInstallationSnapshotV2,
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

    private fun requireAttemptHeadReference(facts: LocalInstallationAttemptFactsV2, state: LocalCompanyAuthorityStateV2) {
        // Historical immutable references, NOT a renewed active-head/clock check.
        if (facts.headAccountRevision > state.head.accountRevision || facts.headCredentialRevision != state.head.credentialRevision ||
            facts.installationPurposeRevision > state.head.installationRevision ||
            facts.installationKeyRevision != requireBootstrap().key(LocalCompanyHeadKeyRoleV1.INSTALLATION).keyRevision ||
            facts.osBootCount > environment.bootCount) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
    }

    private fun originalHumanApproval(claim: LocalCompanyInstallationClaimV1, snapshot: LocalBranchInstallationSnapshotV2,
        facts: LocalInstallationAttemptFactsV2): LocalInstallationSignedApprovalV2 {
        val bytes = (claim.signedHumanApproval ?: localCompanyRefuse(LocalCompanyAuthorityFailure.ORIGINAL_RESULT_REQUIRED)).copyBytes()
        val proof = try { LocalBranchInstallationWireV2.decodeSignedHumanApproval(bytes, snapshot, facts) ?: uncertain() }
        finally { bytes.fill(0) }
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
        val bytes = value.frame.copyWireBytes(); val signature = value.copySignature()
        val point = keys.originalPoint(SelfOwnedNativeKeyRoleV1.ROOT)
        try {
            if (value.frame.rootKeyId != requireBootstrap().original.key(SelfOwnedNativeKeyRoleV1.ROOT).keyId ||
                !AndroidP256Primitives.verify(point, bytes, signature)) selfOwnedFail(SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN)
        } finally { bytes.fill(0); signature.fill(0); point.fill(0) }
        requireOwned()
    }

    private fun originalInstallationHumanReference(state: LocalCompanyAuthorityStateV2): LocalInstallationHumanReferenceV2 {
        val body = state.enrollment.body
        val encoded = SelfOwnedNativeProvisionerWireV2.encodeHeadEnrollmentBody(body)
        val point = body.installationPoint.copyBytes()
        return try {
            LocalInstallationHumanReferenceV2(body.rootId, body.rootKeyId,
                PayloadDigest(selfOwnedPreparationDocumentDigest(state.original.root)), body.foundingActionId,
                PayloadDigest(AndroidP256Primitives.hashHex(encoded)), body.headAuthorityId,
                body.headEnrollmentId, body.installationKeyId, point)
        } finally { encoded.fill(0); point.fill(0) }
    }

    /** P4 supplies actual custody first; this independently recomputes ALL stable D7 content. */
    private fun requireOriginalInstallationSnapshot(snapshot: LocalBranchInstallationSnapshotV2,
        state: LocalCompanyAuthorityStateV2): LocalBranchInstallationSnapshotV2 {
        val complete = LocalBranchInstallationWireV2.snapshotDefinition(snapshot.definition)
        val expectedBytes = complete.copyWireBytes(); val retainedBytes = snapshot.copyWireBytes()
        try {
            if (!expectedBytes.contentEquals(retainedBytes) || complete.definitionDigest != snapshot.definitionDigest ||
                complete.allocationDigest != snapshot.allocationDigest || complete.originalTargetKeyDigest != snapshot.originalTargetKeyDigest) uncertain()
        } finally { expectedBytes.fill(0); retainedBytes.fill(0) }
        val original = state.original.allocations.singleOrNull()
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val grant = original.grant ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val allocation = complete.definition.allocation
        val grantBytes = grant.copyWireBytes()
        val grantDigest = try { sha256(grantBytes).value } finally { grantBytes.fill(0) }
        if (original.phase != LocalProvisionerAllocationPhaseV1.GRANT_RETAINED ||
            !selfOwnedSameCoordinatesV1(allocation.coordinates, original.body.coordinates) ||
            allocation.p1RootDocumentDigest.value != selfOwnedPreparationDocumentDigest(state.original.root) ||
            allocation.p1DelegationDigest.value != selfOwnedPreparationDocumentDigest(state.original.delegation) ||
            allocation.p1PreparationDigest.value != grantDigest) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
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
        requireOwned()
        return complete
    }

    private fun LocalCompanyAuthorityStateV2.replaceClaim(claim: LocalCompanyInstallationClaimV1): LocalCompanyAuthorityStateV2 =
        next(claims = installationClaims.map { if (it.issuedAttemptId == claim.issuedAttemptId) claim else it })

    private fun requireBootstrap(): LocalCompanyAuthorityBootstrapV2 = registry.companyBootstrapRecordV2()
        ?: localCompanyRefuse(LocalCompanyAuthorityFailure.HEAD_ENROLLMENT_REQUIRED)
    private fun requireState(): LocalCompanyAuthorityStateV2 {
        requireOwned()
        return head.readState() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
    }
    private fun requirePurpose(state: LocalCompanyAuthorityStateV2) {
        if (!state.head.purposeEnabled(LocalCompanyHeadPurposeV1.FULL_D7_INSTALLATION_APPROVE)) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.ISSUANCE_WITHDRAWN)
        }
    }
    private fun requireOwned() { work.requireActive(); environment.requireCurrent(); registry.assertContinuity(); work.requireActive() }
    private fun requireNewIssuance() { requireOwned(); issuance.requireIssuance(LocalCompanyHeadPurposeV1.FULL_D7_INSTALLATION_APPROVE) }
    private fun pendingDeadline(): Long {
        val now = work.clock()
        if (now > Long.MAX_VALUE - SelfOwnedInstallationProfileV1.PENDING_ACTION_MILLIS) uncertain()
        return now + SelfOwnedInstallationProfileV1.PENDING_ACTION_MILLIS
    }
    private fun reservedIds(company: LocalCompanyAuthorityBootstrapV2, state: LocalCompanyAuthorityStateV2): Set<DomainId> = buildSet {
        addAll(company.allIds())
        for (allocation in state.original.allocations) {
            val c = allocation.body.coordinates
            addAll(listOf(c.allocationId, c.scope.branchId, c.installationId, c.assignmentId, c.deviceId, c.storageInstanceId,
                c.storageKeyId, c.pepperKeyId, c.targetSigningKeyId, c.firstActorId))
        }
        for (claim in state.installationClaims) { add(claim.issuedAttemptId); add(claim.originalRequestId) }
        state.installationAdmission?.let { add(it.originalRequestId) }
    }
    private fun uncertain(): Nothing = selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)

    override fun toString(): String = "SelfOwnedDesktopCompanyInstallationV2([REDACTED])"

    private companion object {
        val CEREMONY = SelfOwnedDesktopHeadCeremonyV2.FULL_D7_INSTALLATION_APPROVE
    }
}
