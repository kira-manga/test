package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/**
 * Windows/macOS company purchasing-policy publication over the seven-key V2 company container. It
 * mirrors the Android V1 [AndroidLocalCompanyAuthority] policy operations step for step and shares
 * their pure record/body/digest/envelope logic ([LocalCompanyPolicyPublicationCore],
 * [localCompanyPolicyActionDigestV1]), so the published A0a documents are identical in form on every
 * platform. Only the custody differs:
 *
 * - PREPARED -> SIGNING_INTENT -> ISSUED records live in the V2 container
 *   ([LocalCompanyAuthorityStateV2.policies]), whose authenticated read re-verifies the predecessor
 *   chain and both signatures ([SelfOwnedCompanyAuthorityV2]);
 * - the head ceremony is [SelfOwnedDesktopCompanyHeadV2] (POLICY_PUBLISH purpose and key revisions),
 *   re-checked against every container state this issuer commits with (revocation serialized with
 *   commit) and the in-memory issuance gate;
 * - the ROOT delegation and POLICY head signatures come only from [SelfOwnedCompanyNativeKeysV2].
 *
 * Same predecessor with a changed proposal is a CONFLICT (never an invented next action), a pending
 * original-installation admission blocks new authority IDs, and capacity is reserved before the
 * PREPARED record is retained. Every method is a synchronous native operation inside the platform's
 * admitted owned work stage. Password buffers are owned by the caller and cleared there.
 */
internal class SelfOwnedDesktopCompanyPolicyPublicationV2(
    private val environment: SelfOwnedOriginalEnvironment,
    private val registry: SelfOwnedProvisionerRegistryStoreV2,
    keys: SelfOwnedOriginalProvisionerKeys,
    private val work: AndroidOwnedInstallationWork,
    private val issuance: AndroidLocalCompanyIssuanceGate,
) {
    private val keys: SelfOwnedCompanyNativeKeysV2 = SelfOwnedCompanyAuthorityV2.requireCompanyKeys(keys)
    private val head = SelfOwnedDesktopCompanyHeadV2(environment, registry, this.keys, work, issuance)
    private val actionOwner = Any()

    fun close() { head.close() }

    fun preparePolicyPublication(proposal: LocalCompanyPolicyProposal): LocalCompanyPolicyAction {
        requireNewIssuance()
        val original = requireBootstrap(); val state = requireState()
        requirePurpose(state)
        val allocation = state.original.allocations.singleOrNull() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        // Same predecessor + changed proposal is a conflict, not an invented next action.
        val replay = state.policies.singleOrNull { same(it.policy().predecessor, proposal.expectedPredecessor) }
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
        // An admitted original-installation attempt may not have reached this container yet; no
        // further stable authority IDs are allocated against an incomplete census (as on Android V1).
        if (state.installationAdmission != null) {
            localCompanyRefuse(LocalCompanyAuthorityFailure.ORIGINAL_ATTEMPT_SETTLEMENT_REQUIRED)
        }
        val preceding = state.policies.lastOrNull()
        if (preceding != null && preceding.phase != LocalCompanyPolicyPhaseV1.ISSUED) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        val priorReference = preceding?.let { reference(it.policy().publicationId, it.policy().revision, it.policyBody) }
        if (!same(priorReference, proposal.expectedPredecessor)) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        val revision = preceding?.policy()?.revision?.value ?: 0L
        capacityRevision(revision)
        if (state.policies.size == LocalCompanyAuthorityStateV1.MAX_POLICY_RECORDS) selfOwnedFail(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
        val ids = newIds(if (preceding == null) 5 else 3, reservedIds(original, state))
        val record = proposedPolicyRecord(proposal, state, original, ids[0], preceding?.policy()?.policyId ?: ids[3], ids[1],
            preceding?.delegation()?.delegationId ?: ids[4], ids[2], Revision(revision + 1L),
            preceding?.let { reference(it.delegation().publicationId, it.delegation().revision, it.delegationBody) }, null)
        if (record.policy().branch != allocation.body.coordinates.scope) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        requireNewIssuance()
        val retained = retain(state, state.next(policies = state.policies + record))
        requireNewIssuance()
        return policyAction(retained.policies.last())
    }

    fun approvePolicyPublication(action: LocalCompanyPolicyAction, login: String, secret: CharArray): LocalCompanyPolicyPublication {
        var state = requireState()
        var record = state.policies.singleOrNull { it.originalActionId == action.originalActionId }
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        requirePolicyAction(action, record, current = record.phase != LocalCompanyPolicyPhaseV1.ISSUED)
        if (record.phase == LocalCompanyPolicyPhaseV1.ISSUED) return policyResult(record)
        requireNewIssuance()
        requirePolicyHeadRevisions(record, state)
        val approval = head.authenticate(CEREMONY, record.originalActionId, localCompanyPolicyActionDigestV1(record), login, secret,
            action.expiresElapsedMillis)
        state = requireState(); record = state.policies.single { it.originalActionId == action.originalActionId }
        head.requireStillCurrent(approval, state, CEREMONY, record.originalActionId, localCompanyPolicyActionDigestV1(record))
        requirePolicyHeadRevisions(record, state)
        requireNewIssuance()
        if (record.phase == LocalCompanyPolicyPhaseV1.PREPARED) {
            state = retain(state, state.replacePolicy(record.copy(phase = LocalCompanyPolicyPhaseV1.SIGNING_INTENT)))
            record = state.policies.single { it.originalActionId == action.originalActionId }
        }
        val result = signCompanyPolicy(record, approval)
        state = requireState()
        val current = state.policies.single { it.originalActionId == action.originalActionId }
        if (current != record) uncertain()
        head.requireStillCurrent(approval, state, CEREMONY, record.originalActionId, localCompanyPolicyActionDigestV1(record))
        requireNewIssuance()
        val encoded = LocalCompanyPolicyWireV1.encodeSignedPolicy(result)
        val retained = try { retain(state, state.replacePolicy(record.copy(phase = LocalCompanyPolicyPhaseV1.ISSUED,
            signedPolicy = SelfOwnedOctetsV1(encoded)))) } finally { encoded.fill(0) }
        head.requireStillCurrent(approval, retained, CEREMONY, record.originalActionId, localCompanyPolicyActionDigestV1(record))
        requireNewIssuance()
        return policyResult(retained.policies.single { it.originalActionId == record.originalActionId })
    }

    fun recoverPolicyPublication(originalActionId: DomainId): LocalCompanyPolicyPublication {
        requireOwned()
        val record = requireState().policies.singleOrNull { it.originalActionId == originalActionId }
            ?: localCompanyRefuse(LocalCompanyAuthorityFailure.ORIGINAL_RESULT_REQUIRED)
        return policyResult(record).also { requireOwned() }
    }

    /** The authenticated current container (its read verified every policy record and signature), or null when absent. */
    fun readStateOrNull(): LocalCompanyAuthorityStateV2? { requireOwned(); return head.readState() }

    /** Fingerprint of the actual original root key body. */
    fun rootFingerprint(): String = JvmP256Primitives.rootFingerprint(keys.rootBody())

    /** Revocation of the policy purpose (or the whole head for null), as on Android V1. */
    fun withdrawHeadIssuance(purpose: LocalCompanyHeadPurposeV1?) { head.withdrawHeadIssuance(purpose) }

    private fun signCompanyPolicy(record: LocalCompanyPolicyRecordV1, approval: SelfOwnedDesktopCompanyHeadV2.Approval): PurchasingSignedPolicyV1 {
        val digest = localCompanyPolicyActionDigestV1(record)
        fun current() {
            val state = requireState()
            val retained = state.policies.singleOrNull { it.originalActionId == record.originalActionId }
                ?: selfOwnedFail(SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN)
            if (retained != record || retained.phase != LocalCompanyPolicyPhaseV1.SIGNING_INTENT) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN)
            }
            head.requireStillCurrent(approval, state, CEREMONY, record.originalActionId, digest)
            requireNewIssuance()
        }
        current()
        val delegationBytes = record.delegationBody.copyBytes(); val policyBytes = record.policyBody.copyBytes()
        var rootSignature: ByteArray? = null; var policySignature: ByteArray? = null
        return try {
            rootSignature = keys.signCompanyPolicyDelegation(delegationBytes)
            current()
            policySignature = keys.signCompanyPolicy(policyBytes)
            current()
            PurchasingSignedPolicyV1(record.policy(), PurchasingSignatureV1(requireNotNull(policySignature)),
                PurchasingDelegationDocumentsV1(listOf(PurchasingSignedDelegationV1(record.delegation(),
                    PurchasingSignatureV1(requireNotNull(rootSignature))))))
        } finally { delegationBytes.fill(0); policyBytes.fill(0); rootSignature?.fill(0); policySignature?.fill(0) }
    }

    private fun policyResult(record: LocalCompanyPolicyRecordV1): LocalCompanyPolicyPublication =
        LocalCompanyPolicyPublication(record.originalActionId, record.result())

    private fun proposedPolicyRecord(proposal: LocalCompanyPolicyProposal, state: LocalCompanyAuthorityStateV2,
        original: LocalCompanyAuthorityBootstrapV2, actionId: DomainId, policyId: DomainId, publicationId: DomainId,
        delegationId: DomainId, delegationPublicationId: DomainId, revision: Revision,
        delegationPredecessor: PurchasingPublicationReferenceV1?, replaySubject: PurchasingNamedAuthorityV1?): LocalCompanyPolicyRecordV1 {
        val branch = state.original.allocations.singleOrNull()?.body?.coordinates?.scope
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val account = state.head
        // Native custody/key observations are outside input-error conversion. Only pure
        // construction/encoding below may turn a malformed proposal into INVALID_INPUT.
        val point = keys.headPoint(LocalCompanyHeadKeyRoleV1.POLICY)
        val fingerprint = try { rootFingerprint() } catch (failure: Throwable) { point.fill(0); throw failure }
        return try {
            val subject = replaySubject ?: PurchasingNamedAuthorityV1(original.headAuthorityId, original.headName,
                PurchasingAuthorityRoleV1.COMPANY_HEAD, Revision(account.accountRevision), original.key(LocalCompanyHeadKeyRoleV1.POLICY).keyId,
                PurchasingPublicPointV1(point))
            val root = PurchasingRootReferenceV1(original.original.key(SelfOwnedNativeKeyRoleV1.ROOT).keyId, PayloadDigest(fingerprint))
            val record = LocalCompanyPolicyPublicationCore.buildRecord(proposal, branch, root, subject, actionId, policyId,
                publicationId, delegationId, delegationPublicationId, revision, delegationPredecessor, account.credentialRevision,
                account.policyRevision, original.key(LocalCompanyHeadKeyRoleV1.POLICY).keyRevision)
            requireOriginalCompanyPolicyV2(record.policy(), record.delegation(), state.original, original, fingerprint, point)
            LocalCompanyPolicyPublicationCore.requireEnvelopeFits(record)
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

    private fun requirePolicyHeadRevisions(record: LocalCompanyPolicyRecordV1, state: LocalCompanyAuthorityStateV2) {
        val original = requireBootstrap(); val account = state.head
        requirePurpose(state)
        if (record.headAccountRevision != account.accountRevision || record.headCredentialRevision != account.credentialRevision ||
            record.purposeRevision != account.policyRevision || record.keyRevision != original.key(LocalCompanyHeadKeyRoleV1.POLICY).keyRevision) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
        }
    }

    /** Capacity (bytes and revisions, completion of every unsigned record included) before the authenticated replace. */
    private fun retain(previous: LocalCompanyAuthorityStateV2, next: LocalCompanyAuthorityStateV2): LocalCompanyAuthorityStateV2 {
        SelfOwnedCompanyAuthorityV2.requireCapacity(next)
        return head.retainState(previous, next)
    }

    private fun LocalCompanyAuthorityStateV2.replacePolicy(value: LocalCompanyPolicyRecordV1): LocalCompanyAuthorityStateV2 {
        if (policies.count { it.originalActionId == value.originalActionId } != 1) uncertain()
        return next(policies = policies.map { if (it.originalActionId == value.originalActionId) value else it })
    }

    private fun requireBootstrap(): LocalCompanyAuthorityBootstrapV2 = registry.companyBootstrapRecordV2()
        ?: localCompanyRefuse(LocalCompanyAuthorityFailure.HEAD_ENROLLMENT_REQUIRED)
    private fun requireState(): LocalCompanyAuthorityStateV2 {
        requireOwned()
        return head.readState() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
    }
    private fun requirePurpose(state: LocalCompanyAuthorityStateV2) {
        if (!state.head.purposeEnabled(LocalCompanyHeadPurposeV1.POLICY_PUBLISH)) selfOwnedFail(SelfOwnedInstallationFailureV1.ISSUANCE_WITHDRAWN)
    }
    private fun requireOwned() { work.requireActive(); environment.requireCurrent(); registry.assertContinuity(); work.requireActive() }
    private fun requireNewIssuance() { requireOwned(); issuance.requireIssuance(LocalCompanyHeadPurposeV1.POLICY_PUBLISH) }
    private fun pendingDeadline(): Long {
        val now = work.clock()
        if (now > Long.MAX_VALUE - SelfOwnedInstallationProfileV1.PENDING_ACTION_MILLIS) uncertain()
        return now + SelfOwnedInstallationProfileV1.PENDING_ACTION_MILLIS
    }
    private fun digest(value: SelfOwnedOctetsV1): PayloadDigest = LocalCompanyPolicyPublicationCore.digest(value)
    private fun reference(id: DomainId, revision: Revision, body: SelfOwnedOctetsV1): PurchasingPublicationReferenceV1 =
        LocalCompanyPolicyPublicationCore.reference(id, revision, body)
    private fun same(a: PurchasingPublicationReferenceV1?, b: PurchasingPublicationReferenceV1?): Boolean =
        LocalCompanyPolicyPublicationCore.samePublicationReference(a, b)
    private fun newIds(count: Int, reserved: Set<DomainId>): List<DomainId> {
        val ids = List(count) { JvmP256Primitives.newId() }
        if (ids.distinct().size != count || ids.any { it in reserved }) uncertain()
        return ids
    }
    private fun uncertain(): Nothing = selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)

    override fun toString(): String = "SelfOwnedDesktopCompanyPolicyPublicationV2([REDACTED])"

    internal companion object {
        private val CEREMONY = SelfOwnedDesktopHeadCeremonyV2.POLICY_PUBLISH

        /** Every stable ID the V2 company container already uses (shared with the installation issuer). */
        fun reservedIds(company: LocalCompanyAuthorityBootstrapV2, state: LocalCompanyAuthorityStateV2): Set<DomainId> = buildSet {
            addAll(company.allIds())
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
}
