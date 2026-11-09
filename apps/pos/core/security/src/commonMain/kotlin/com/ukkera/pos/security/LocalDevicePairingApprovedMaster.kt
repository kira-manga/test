package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId

/**
 * Existing native readback of the original Master intentionally selected in this target's
 * exact signed challenge/possession and the matching original root approval. Only the
 * actual native importer constructs this value after custody and signature verification.
 * It is not a decoded-DATA trust flag, remote hardware attestation, live SDK/session,
 * employee authentication, admission, networking authorization or business permission.
 */
public class LocalDevicePairingApprovedMaster internal constructor(
    private val identity: NativeDevicePairingApprovedMasterIdentityV1,
) {
    public val masterSdkOriginal: LocalMasterSdkOriginalV1 get() = identity.masterSdkOriginal
    public val targetSdkIdentity: LocalDevicePairingSdkIdentityV2 get() = identity.targetSdkIdentity
    public val originalPairingActionId: DomainId get() = identity.originalPairingActionId
    public val originalRequestId: DomainId get() = identity.originalRequestId
    public val scope: BranchScope get() = identity.scope
    public val targetDeviceId: DomainId get() = identity.targetDeviceId
    public val targetKeyId: DomainId get() = identity.targetKeyId
    public val rootId: DomainId get() = identity.rootId
    public val rootKeyId: DomainId get() = identity.rootKeyId
    public val foundingActionId: DomainId get() = identity.foundingActionId
    public val rootFingerprint: String get() = identity.rootFingerprint
    public val rootDocumentDigest: PayloadDigest get() = identity.rootDocumentDigest
    public val approvalCertificateDigest: PayloadDigest get() = identity.approvalCertificateDigest
    public val challengeDigest: PayloadDigest get() = identity.challengeDigest
    public val possessionDigest: PayloadDigest get() = identity.possessionDigest
    override fun toString(): String = "LocalDevicePairingApprovedMaster([REDACTED])"
}

/** Bounded immutable readback fields only; no duplicate full challenge or signing message. */
internal class NativeDevicePairingApprovedMasterIdentityV1(
    val masterSdkOriginal: LocalMasterSdkOriginalV1,
    val targetSdkIdentity: LocalDevicePairingSdkIdentityV2,
    val originalPairingActionId: DomainId,
    val originalRequestId: DomainId,
    val scope: BranchScope,
    val targetDeviceId: DomainId,
    val targetKeyId: DomainId,
    val rootId: DomainId,
    val rootKeyId: DomainId,
    val foundingActionId: DomainId,
    val rootFingerprint: String,
    val rootDocumentDigest: PayloadDigest,
    val approvalCertificateDigest: PayloadDigest,
    val challengeDigest: PayloadDigest,
    val possessionDigest: PayloadDigest,
)

/** Internal fixed signature obligations, NOT a qualified public readback or verifier input. */
internal class NativeDevicePairingApprovedMasterCheckV1(
    val identity: NativeDevicePairingApprovedMasterIdentityV1,
    val rootPoint: SelfOwnedOctetsV1,
    val rootMessage: SelfOwnedOctetsV1,
    val rootSignature: SelfOwnedOctetsV1,
    val endorsementMessage: SelfOwnedOctetsV1,
    val endorsementSignature: SelfOwnedOctetsV1,
)

/**
 * Reusable coherence and exact signing-message policy. Its C/P parameters MUST come from
 * the actual target's revalidated native-signed originals, not from the imported certificate.
 * This policy accepts no alternate anchor, accepting callback, trust Boolean or clock renewal.
 * Native code must verify BOTH fixed signatures and native custody before constructing readback.
 */
internal object NativeDevicePairingApprovedMasterPolicyV1 {
    fun prepare(record: NativeDevicePairingRecordV1, rootAnnouncement: ByteArray,
        retainedChallenge: LocalDevicePairingChallenge,
        retainedPossession: LocalDevicePairingPossession): NativeDevicePairingApprovedMasterCheckV1 {
        require(record.phase == LocalDevicePairingPhase.APPROVED && record.rootEndorsement.size == 64)
        val approvedChallenge = LocalDevicePairingChallenge.fromNative(record.body)
        val selected = retainedChallenge.copyWireBytes(); val approved = approvedChallenge.copyWireBytes()
        try { require(selected.contentEquals(approved)) } finally { selected.fill(0); approved.fill(0) }
        val possession = retainedPossession.copyWireBytes()
        val possessionDigest = try {
            require(requireNotNull(record.possession).same(possession))
            sha256(possession)
        } finally { possession.fill(0) }

        val requested = retainedChallenge.requested
        val master = requireNotNull(requested.masterSdkOriginal)
        val sdk = requireNotNull(requested.sdkIdentity)
        val origin = retainedChallenge.body.origin
        val target = requested.target
        val allocation = master.allocation
        require(sameCoordinates(allocation.coordinates, origin.coordinates) &&
            allocation.digest == origin.allocationDigest &&
            allocation.originalNativeBindingDigest == origin.bindingDigest &&
            allocation.p1RootDocumentDigest == origin.rootDocumentDigest &&
            requested.request.scope == origin.coordinates.scope && target.admission.scope == origin.coordinates.scope &&
            master.appId == master.compositionProfile.appId(origin.coordinates.scope) && master.appId == sdk.appId)
        val targetReferences = listOf(target.admission.deviceId, target.keyId,
            sdk.originalAcquisitionId, sdk.originalPreparationId,
            retainedChallenge.originalActionId, requested.request.requestId)
        require(targetReferences.distinct().size == targetReferences.size &&
            targetReferences.none { it in origin.reservedOriginalIds() || it == master.originalAcquisitionId } &&
            master.originalAcquisitionId !in origin.reservedOriginalIds() &&
            master.peerId != sdk.peerId && master.fingerprint != sdk.fingerprint && master.pairingQr != sdk.pairingQr &&
            retainedPossession.originalActionId == retainedChallenge.originalActionId &&
            retainedPossession.challengeDigest == retainedChallenge.challengeDigest &&
            retainedPossession.deviceId == target.admission.deviceId && retainedPossession.keyId == target.keyId &&
            retainedPossession.descriptorDigest == target.descriptorDigest &&
            retainedPossession.sdkIdentityDigest == sdk.descriptorDigest)
        val masterPoint = allocation.copyTargetPublicKey(); val targetPoint = target.copyPublicPoint()
        try { require(!masterPoint.contentEquals(targetPoint)) } finally { masterPoint.fill(0); targetPoint.fill(0) }
        val associated = sdk.target.copyWireBytes(); val descriptor = target.copyWireBytes()
        try { require(associated.contentEquals(descriptor)) } finally { associated.fill(0); descriptor.fill(0) }

        require(rootAnnouncement.size in 1..SelfOwnedInstallationProfileV1.MAXIMUM_AUTHORITY_DOCUMENT_BYTES &&
            sha256(rootAnnouncement) == origin.rootDocumentDigest)
        val root = requireNotNull(SelfOwnedInstallationWireV1.decodeSigned(rootAnnouncement))
        require(root.role == SelfOwnedDocumentRoleV1.ROOT_ANNOUNCEMENT)
        val envelope = root.copyWireBytes()
        try { require(envelope.contentEquals(rootAnnouncement)) } finally { envelope.fill(0) }
        val bodyBytes = root.body()
        try {
            val body = SelfOwnedInstallationWireV1.decodeRoot(bodyBytes)
            val canonicalBody = SelfOwnedInstallationWireV1.encodeRoot(body)
            try { require(canonicalBody.contentEquals(bodyBytes)) } finally { canonicalBody.fill(0) }
            require(body.rootId == origin.rootId && body.rootKeyId == origin.rootKeyId &&
                body.bootstrapActionId == origin.foundingActionId &&
                body.organizationId == origin.coordinates.scope.organizationId &&
                body.companyId == origin.coordinates.scope.companyId)
            val rootMessage = SelfOwnedInstallationWireV1.signingMessage(SelfOwnedDocumentRoleV1.ROOT_ANNOUNCEMENT, bodyBytes)
            try {
                // Root fingerprint is the role-bound canonical signing message, NOT the
                // signed envelope digest; both independent original anchors must match.
                require(sha256(rootMessage).value == origin.rootFingerprint)
                val endorsementMessage = NativeDevicePairingWireV1.endorsementMessage(record)
                var copiedSignature: ByteArray? = null; var copiedCertificate: ByteArray? = null
                try {
                    val rootSignature = root.signature(); copiedSignature = rootSignature
                    val certificate = NativeDevicePairingWireV1.certificate(record); copiedCertificate = certificate
                    val identity = NativeDevicePairingApprovedMasterIdentityV1(master, sdk,
                        retainedChallenge.originalActionId, requested.request.requestId, requested.request.scope,
                        target.admission.deviceId, target.keyId, origin.rootId, origin.rootKeyId,
                        origin.foundingActionId, origin.rootFingerprint, origin.rootDocumentDigest,
                        sha256(certificate), retainedChallenge.challengeDigest, possessionDigest)
                    return NativeDevicePairingApprovedMasterCheckV1(identity, body.rootPublicKey,
                        SelfOwnedOctetsV1(rootMessage, SelfOwnedInstallationProfileV1.MAXIMUM_AUTHORITY_DOCUMENT_BYTES),
                        SelfOwnedOctetsV1(rootSignature, 64),
                        SelfOwnedOctetsV1(endorsementMessage, LocalDevicePairingProfileV1.MAXIMUM_CHALLENGE_BYTES +
                            LocalDevicePairingProfileV1.COMPLETION_RESERVATION_BYTES), record.rootEndorsement)
                } finally { endorsementMessage.fill(0); copiedSignature?.fill(0); copiedCertificate?.fill(0) }
            } finally { rootMessage.fill(0) }
        } finally { bodyBytes.fill(0) }
    }

    /** Coordinates are decoded value DATA; object/reference equality is not coherence. */
    private fun sameCoordinates(left: SelfOwnedAllocationCoordinatesV1, right: SelfOwnedAllocationCoordinatesV1): Boolean =
        left.allocationId == right.allocationId && left.scope == right.scope &&
            left.installationId == right.installationId && left.assignmentId == right.assignmentId &&
            left.deviceId == right.deviceId && left.storageInstanceId == right.storageInstanceId &&
            left.storageKeyId == right.storageKeyId && left.pepperKeyId == right.pepperKeyId &&
            left.targetSigningKeyId == right.targetSigningKeyId && left.firstActorId == right.firstActorId
}
