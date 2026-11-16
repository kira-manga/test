package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/**
 * Format-neutral pure pieces of company purchasing-policy publication shared by the Android V1
 * issuer ([AndroidLocalCompanyAuthority]) and the Windows/macOS V2 issuer
 * ([SelfOwnedDesktopCompanyPolicyPublicationV2]). The record, its bodies, the action digest
 * ([localCompanyPolicyActionDigestV1]) and the signed envelope are the SAME A0a purchasing values on
 * every platform; only custody (container, head ceremony, keys) differs. Nothing here touches
 * native state, authenticates, signs or grants anything.
 */
internal object LocalCompanyPolicyPublicationCore {
    fun digest(value: SelfOwnedOctetsV1): PayloadDigest {
        val bytes = value.copyBytes()
        return try { PayloadDigest(AndroidP256Primitives.hashHex(bytes)) } finally { bytes.fill(0) }
    }

    fun reference(id: DomainId, revision: Revision, body: SelfOwnedOctetsV1): PurchasingPublicationReferenceV1 =
        PurchasingPublicationReferenceV1(id, revision, digest(body))

    fun samePublicationReference(a: PurchasingPublicationReferenceV1?, b: PurchasingPublicationReferenceV1?): Boolean =
        if (a == null || b == null) a == null && b == null
        else a.publicationId == b.publicationId && a.revision == b.revision && a.bodyDigest == b.bodyDigest

    /**
     * Pure construction of one PREPARED record from the proposal and the caller's actual custody
     * observations ([root], [subject]). The caller performs the structural cross-check and sizing.
     */
    fun buildRecord(proposal: LocalCompanyPolicyProposal, branch: BranchScope, root: PurchasingRootReferenceV1,
        subject: PurchasingNamedAuthorityV1, actionId: DomainId, policyId: DomainId, publicationId: DomainId,
        delegationId: DomainId, delegationPublicationId: DomainId, revision: Revision,
        delegationPredecessor: PurchasingPublicationReferenceV1?, headCredentialRevision: Long, purposeRevision: Long,
        keyRevision: Long): LocalCompanyPolicyRecordV1 {
        val delegation = PurchasingAuthorityDelegationV1(branch.company(), root, delegationId, delegationPublicationId, revision,
            delegationPredecessor, PurchasingAuthorityIssuerV1.Root(root.keyId), subject, proposal.grants, proposal.validity)
        val delegationBytes = LocalCompanyPolicyWireV1.encodeDelegationBody(delegation)
        return try {
            val policy = PurchasingPolicyPublicationV1(branch, root, policyId, publicationId, revision, proposal.expectedPredecessor,
                PurchasingDelegatedIssuerV1(subject.authorityId, subject.accountRevision, subject.keyId, delegationId,
                    PurchasingPublicationReferenceV1(delegationPublicationId, revision, PayloadDigest(AndroidP256Primitives.hashHex(delegationBytes)))),
                proposal.validity, proposal.retentionClasses, proposal.rules)
            val policyBytes = LocalCompanyPolicyWireV1.encodePolicyBody(policy)
            try { LocalCompanyPolicyRecordV1(actionId, subject.accountRevision.value, headCredentialRevision,
                purposeRevision, keyRevision, SelfOwnedOctetsV1(delegationBytes), SelfOwnedOctetsV1(policyBytes),
                LocalCompanyPolicyPhaseV1.PREPARED, null) }
            finally { policyBytes.fill(0) }
        } finally { delegationBytes.fill(0) }
    }

    /** Pure exact envelope sizing only; these zero signature bytes NEVER leave this function. */
    fun requireEnvelopeFits(record: LocalCompanyPolicyRecordV1) {
        val placeholder = ByteArray(64)
        try {
            val shape = PurchasingSignedPolicyV1(record.policy(), PurchasingSignatureV1(placeholder),
                PurchasingDelegationDocumentsV1(listOf(PurchasingSignedDelegationV1(record.delegation(), PurchasingSignatureV1(placeholder)))))
            LocalCompanyPolicyWireV1.encodeSignedPolicy(shape).fill(0)
        } finally { placeholder.fill(0) }
    }

    /** The original-ledger projection both platforms hand to the Master's purchasing trust. */
    fun ledgerReference(record: LocalCompanyPolicyRecordV1): LocalPurchasingPublicationReference {
        val body = record.policyBody.copyBytes()
        return try {
            val policy = record.policy()
            LocalPurchasingPublicationReference(record.originalActionId, policy.policyId, policy.publicationId,
                policy.revision, sha256(body), when (record.phase) {
                    LocalCompanyPolicyPhaseV1.PREPARED -> LocalPurchasingPublicationPhase.PREPARED
                    LocalCompanyPolicyPhaseV1.SIGNING_INTENT -> LocalPurchasingPublicationPhase.SIGNING_INTENT
                    LocalCompanyPolicyPhaseV1.ISSUED -> LocalPurchasingPublicationPhase.ISSUED
                })
        } finally { body.fill(0) }
    }

    /** Issued envelopes only; the caller's authenticated container read already verified both signatures. */
    fun verifiedIssued(records: List<LocalCompanyPolicyRecordV1>): List<VerifiedNativePurchasingPolicy> =
        records.filter { it.phase == LocalCompanyPolicyPhaseV1.ISSUED }.map { record ->
            val envelope = checkNotNull(record.signedPolicy)
            val bytes = envelope.copyBytes()
            try { VerifiedNativePurchasingPolicy(record.policy().publicationId, sha256(bytes), envelope) }
            finally { bytes.fill(0) }
        }
}
