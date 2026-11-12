package com.ukkera.pos.security

import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision
import kotlin.coroutines.cancellation.CancellationException

/**
 * Pure schema7 DATA only. Actual P1/P2/P4 owners must authenticate original state separately.
 * Neither construction, decoding nor equality grants signing, custody, SQL or writer authority.
 */
public class OriginalAllocationCommitmentV2(
    public val coordinates: SelfOwnedAllocationCoordinatesV1,
    public val originalNativeBindingDigest: PayloadDigest,
    public val p1RootDocumentDigest: PayloadDigest,
    public val p1DelegationDigest: PayloadDigest,
    public val p1PreparationDigest: PayloadDigest,
    targetPublicKey: ByteArray,
) {
    private val point = installationPointV2(targetPublicKey)
    public fun copyTargetPublicKey(): ByteArray = point.copyOf()
    public val digest: PayloadDigest get() = installationHashV2(LocalBranchInstallationWireV2.encodeAllocation(this))
    public val originalTargetKeyDigest: PayloadDigest
        get() = installationHashV2(LocalBranchInstallationWireV2.encodeOriginalTargetKey(this))
    override fun toString(): String = "OriginalAllocationCommitmentV2([REDACTED])"
}

/** Reference to an independent root-signed founding BODY, never a D7-containing envelope. */
public class LocalInstallationHumanReferenceV2(
    public val rootId: DomainId,
    public val rootKeyId: DomainId,
    public val rootDocumentDigest: PayloadDigest,
    public val foundingActionId: DomainId,
    public val foundingBodyDigest: PayloadDigest,
    public val headAuthorityId: DomainId,
    public val headEnrollmentId: DomainId,
    public val headInstallationKeyId: DomainId,
    headInstallationPublicKey: ByteArray,
) {
    private val point = installationPointV2(headInstallationPublicKey)
    init {
        require(listOf(rootId, rootKeyId, foundingActionId, headAuthorityId,
            headEnrollmentId, headInstallationKeyId).distinct().size == 6)
    }
    public fun copyHeadInstallationPublicKey(): ByteArray = point.copyOf()
    override fun toString(): String = "LocalInstallationHumanReferenceV2([REDACTED])"
}

/** Full stable proposal. Password/verifier and attempt-current facts deliberately are not fields. */
public class LocalBranchInstallationDefinitionV2(
    public val allocation: OriginalAllocationCommitmentV2,
    public val originalRequestId: DomainId,
    public val authorityEpoch: Revision,
    public val human: LocalInstallationHumanReferenceV2,
    firstAccount: LocalAccountProvisioning,
    masterAdmission: LocalDeviceAdmission,
) {
    private val accountValue = copyInstallationAccountV2(firstAccount)
    private val admissionValue = copyInstallationAdmissionV2(masterAdmission)
    public val firstAccount: LocalAccountProvisioning get() = copyInstallationAccountV2(accountValue)
    public val masterAdmission: LocalDeviceAdmission get() = copyInstallationAdmissionV2(admissionValue)
    init {
        val c = allocation.coordinates
        require(authorityEpoch.value < Long.MAX_VALUE)
        require(originalRequestId !in installationCoordinateIdsV2(c))
        require(human.rootDocumentDigest == allocation.p1RootDocumentDigest)
        require(human.headAuthorityId !in installationCoordinateIdsV2(c))
        require(human.headEnrollmentId !in installationCoordinateIdsV2(c))
        require(human.headInstallationKeyId !in installationCoordinateIdsV2(c))
        require(accountValue.actorId == c.firstActorId && accountValue.policy.actorId == c.firstActorId)
        require(accountValue.policy.scope == c.scope && accountValue.policy.revision == Revision(0))
        require(accountValue.admittedDeviceIds == setOf(c.deviceId))
        require(admissionValue.scope == c.scope && admissionValue.deviceId == c.deviceId &&
            admissionValue.revision == Revision(0) && admissionValue.admitted)
    }
    override fun toString(): String = "LocalBranchInstallationDefinitionV2([REDACTED])"
}

/** Complete canonical bytes plus complete immutable semantics. Hashes cannot be supplied. */
public class LocalBranchInstallationSnapshotV2 internal constructor(
    public val definition: LocalBranchInstallationDefinitionV2,
    canonicalBytes: ByteArray,
) {
    private val bytes = canonicalBytes.copyOf()
    public val definitionDigest: PayloadDigest = sha256(bytes)
    public val allocationDigest: PayloadDigest = definition.allocation.digest
    public val originalTargetKeyDigest: PayloadDigest = definition.allocation.originalTargetKeyDigest
    public fun copyWireBytes(): ByteArray = bytes.copyOf()
    override fun toString(): String = "LocalBranchInstallationSnapshotV2([REDACTED])"
}

/**
 * Parsed current facts are DATA, not current authentication. P4 issues the attempt; A0b supplies
 * actual protected account/purpose/key revisions. Referenced records PRECEDE this claim/proof.
 */
public data class LocalInstallationAttemptFactsV2(
    public val issuedAttemptId: DomainId,
    public val attemptSequence: Long,
    public val issuerContextId: DomainId,
    public val nativeCustodyLifetimeId: DomainId,
    public val originalAnchorGeneration: Long,
    public val originalAnchorStateDigest: PayloadDigest,
    public val headAccountRevision: Long,
    public val headCredentialRevision: Long,
    public val installationPurposeRevision: Long,
    public val installationKeyRevision: Long,
    public val protectedHeadStateDigest: PayloadDigest,
    public val nativeCustodyRecordDigest: PayloadDigest,
    public val osBootCount: Int,
    public val monotonicBootId: DomainId,
    public val observedElapsedMillis: Long,
    public val deadlineElapsedMillis: Long,
    public val observedOsUtcEpochMillis: Long,
    public val protectedClockRecordDigest: PayloadDigest?,
) {
    init {
        require(attemptSequence in 1 until Long.MAX_VALUE)
        require(listOf(originalAnchorGeneration, headAccountRevision, headCredentialRevision,
            installationPurposeRevision, installationKeyRevision).all { it in 0 until Long.MAX_VALUE })
        require(osBootCount >= 0 && observedElapsedMillis >= 0 &&
            deadlineElapsedMillis > observedElapsedMillis && observedOsUtcEpochMillis >= 0)
    }
    override fun toString(): String = "LocalInstallationAttemptFactsV2([REDACTED])"
}

public enum class LocalInstallationApprovalKindV2 { HUMAN, TARGET }

/** Canonical signing input; still not evidence that any actual native signer approved it. */
public class LocalInstallationApprovalFrameV2 internal constructor(
    public val kind: LocalInstallationApprovalKindV2,
    public val allocationDigest: PayloadDigest,
    public val definitionDigest: PayloadDigest,
    public val originalRequestId: DomainId,
    public val issuedAttemptId: DomainId,
    public val attemptSequence: Long,
    public val signerKeyId: DomainId,
    signerPublicKey: ByteArray,
    canonicalBytes: ByteArray,
) {
    private val point = installationPointV2(signerPublicKey)
    private val bytes = canonicalBytes.copyOf()
    public fun copySignerPublicKey(): ByteArray = point.copyOf()
    public fun copyWireBytes(): ByteArray = bytes.copyOf()
    public val digest: PayloadDigest get() = sha256(bytes)
    override fun toString(): String = "LocalInstallationApprovalFrameV2([REDACTED])"
}

/** Low-S signature shape is checked; this value DOES NOT verify a signature or key possession. */
public class LocalInstallationSignedApprovalV2 internal constructor(
    public val frame: LocalInstallationApprovalFrameV2,
    signature: ByteArray,
) {
    private val signatureValue = installationSignatureV2(signature)
    public fun copySignature(): ByteArray = signatureValue.copyOf()
    public fun copyWireBytes(): ByteArray = LocalBranchInstallationWireV2.encodeSignedApproval(this)
    public val digest: PayloadDigest get() = installationHashV2(copyWireBytes())
    override fun toString(): String = "LocalInstallationSignedApprovalV2([REDACTED])"
}

public class LocalInstallationOriginalApprovalsV2(
    public val human: LocalInstallationSignedApprovalV2,
    public val target: LocalInstallationSignedApprovalV2,
) {
    init {
        require(human.frame.kind == LocalInstallationApprovalKindV2.HUMAN &&
            target.frame.kind == LocalInstallationApprovalKindV2.TARGET)
        require(human.frame.definitionDigest == target.frame.definitionDigest &&
            human.frame.allocationDigest == target.frame.allocationDigest &&
            human.frame.originalRequestId == target.frame.originalRequestId &&
            human.frame.issuedAttemptId == target.frame.issuedAttemptId &&
            human.frame.attemptSequence == target.frame.attemptSequence)
    }
    override fun toString(): String = "LocalInstallationOriginalApprovalsV2([REDACTED])"
}

public data class LocalInstallationCommitFactsV2(
    public val issuerContextId: DomainId,
    public val nativeCustodyLifetimeId: DomainId,
    public val monotonicBootId: DomainId,
    public val elapsedMillis: Long,
    public val osUtcEpochMillis: Long,
    public val installationCustodyEvidenceDigest: PayloadDigest,
) {
    init { require(elapsedMillis >= 0 && osUtcEpochMillis >= 0) }
    override fun toString(): String = "LocalInstallationCommitFactsV2([REDACTED])"
}

public data class LocalInstallationSeedDigestsV2(
    public val account: PayloadDigest,
    public val policy: PayloadDigest,
    public val configuration: PayloadDigest,
    public val admission: PayloadDigest,
    public val accountThrottle: PayloadDigest,
    public val deviceThrottle: PayloadDigest,
    public val recoveryState: PayloadDigest,
) {
    override fun toString(): String = "LocalInstallationSeedDigestsV2([REDACTED])"
}

public class LocalBranchInstallationGenesisCoreV2 internal constructor(
    public val allocation: OriginalAllocationCommitmentV2,
    public val definitionDigest: PayloadDigest,
    public val originalRequestId: DomainId,
    public val issuedAttemptId: DomainId,
    public val attemptSequence: Long,
    public val authorityEpoch: Revision,
    public val humanApprovalDigest: PayloadDigest,
    public val targetProofDigest: PayloadDigest,
    public val issuerContextId: DomainId,
    public val monotonicBootId: DomainId,
    public val installedAtElapsedMillis: Long,
    public val installedAtOsUtcEpochMillis: Long,
    public val installationCustodyEvidenceDigest: PayloadDigest,
    public val headAuthorityId: DomainId,
    public val headEnrollmentId: DomainId,
    public val foundingBodyDigest: PayloadDigest,
    public val seeds: LocalInstallationSeedDigestsV2,
) {
    init {
        require(attemptSequence in 1 until Long.MAX_VALUE && authorityEpoch.value < Long.MAX_VALUE)
        require(installedAtElapsedMillis >= 0 && installedAtOsUtcEpochMillis >= 0)
        require(originalRequestId !in installationCoordinateIdsV2(allocation.coordinates))
        require(issuedAttemptId != originalRequestId && issuedAttemptId !in installationCoordinateIdsV2(allocation.coordinates))
        require(headAuthorityId != headEnrollmentId && headAuthorityId != allocation.coordinates.firstActorId &&
            headEnrollmentId != allocation.coordinates.firstActorId)
    }
    public fun copyWireBytes(): ByteArray = LocalBranchInstallationWireV2.encodeGenesisCore(this)
    override fun toString(): String = "LocalBranchInstallationGenesisCoreV2([REDACTED])"
}

/** Original installation receipt DATA, not complete-history/custody/runtime admission. */
public class LocalBranchInstallationReceiptV2 internal constructor(
    public val core: LocalBranchInstallationGenesisCoreV2,
) {
    public val genesis: PayloadDigest = installationHashV2(core.copyWireBytes())
    public fun copyWireBytes(): ByteArray = LocalBranchInstallationWireV2.encodeReceipt(this)
    public val digest: PayloadDigest get() = installationHashV2(copyWireBytes())
    override fun toString(): String = "LocalBranchInstallationReceiptV2([REDACTED])"
}

/** A parsed C1 reference is not the opaque genuine completion token accepted by native A0b. */
public data class LocalInstallationCompletedChildFactsV2(
    public val originalAllocationDigest: PayloadDigest,
    public val definitionDigest: PayloadDigest,
    public val originalRequestId: DomainId,
    public val issuedAttemptId: DomainId,
    public val attemptSequence: Long,
    public val installationGenesisDigest: PayloadDigest,
    public val receiptDigest: PayloadDigest,
    public val historyDigest: PayloadDigest,
    public val commandEpoch: Revision,
) {
    init { require(attemptSequence in 1 until Long.MAX_VALUE && commandEpoch.value < Long.MAX_VALUE) }
    override fun toString(): String = "LocalInstallationCompletedChildFactsV2([REDACTED])"
}

/** Root bookkeeping frame; NEVER fresh human consent or another installation permit. */
public class LocalInstallationCommitAcknowledgementV2 internal constructor(
    public val rootKeyId: DomainId,
    public val allocationDigest: PayloadDigest,
    public val definitionDigest: PayloadDigest,
    public val originalRequestId: DomainId,
    public val issuedAttemptId: DomainId,
    public val receiptDigest: PayloadDigest,
    public val firstChildDigest: PayloadDigest,
    canonicalBytes: ByteArray,
) {
    private val bytes = canonicalBytes.copyOf()
    public fun copyWireBytes(): ByteArray = bytes.copyOf()
    public val digest: PayloadDigest get() = sha256(bytes)
    override fun toString(): String = "LocalInstallationCommitAcknowledgementV2([REDACTED])"
}

public class LocalInstallationSignedAcknowledgementV2 internal constructor(
    public val frame: LocalInstallationCommitAcknowledgementV2,
    signature: ByteArray,
) {
    private val signatureValue = installationSignatureV2(signature)
    public fun copySignature(): ByteArray = signatureValue.copyOf()
    public fun copyWireBytes(): ByteArray = LocalBranchInstallationWireV2.encodeSignedAcknowledgement(this)
    public val digest: PayloadDigest get() = installationHashV2(copyWireBytes())
    override fun toString(): String = "LocalInstallationSignedAcknowledgementV2([REDACTED])"
}

/**
 * ONE complete pure schema7 canonical implementation. Compact signing messages derive D7 from
 * the mandatory full snapshot. Actual native operations MUST reread the protected original.
 * Legacy schema6/P1/P2 wire and purpose meanings are not changed by this object.
 */
public object LocalBranchInstallationWireV2 {
    public const val PROFILE: String = "ukkera.local-installation/v2"
    public const val FORMAT: Int = 2
    public const val MAXIMUM_DEFINITION_BYTES: Int = 2 * 1024 * 1024
    public const val MAXIMUM_POLICY_BYTES: Int = 1536 * 1024
    public const val MAXIMUM_ALLOCATION_BYTES: Int = 1024
    public const val MAXIMUM_APPROVAL_FRAME_BYTES: Int = 4096
    public const val MAXIMUM_SIGNED_APPROVAL_BYTES: Int = 8192
    public const val MAXIMUM_GENESIS_CORE_BYTES: Int = 2048
    public const val MAXIMUM_RECEIPT_BYTES: Int = 2196
    public const val MAXIMUM_ACKNOWLEDGEMENT_BYTES: Int = 4096
    public const val MAXIMUM_SIGNED_ACKNOWLEDGEMENT_BYTES: Int = 8192
    public const val SIGNATURE_ALGORITHM: String = "ECDSA_P256_SHA256_P1363_LOW_S"
    public const val SEED_PROFILE: String = "INITIAL_PROTECTED_SEEDS_V1"
    private const val HEAD_SOURCE = "LOCAL_SELF_OWNED_COMPANY_HEAD"
    private const val ALLOCATED = "ALLOCATED_UNINSTALLED"
    private const val CREDENTIAL_ALGORITHM = "PBKDF2-HMAC-SHA256/POST-HMAC-SHA256/v1"

    public fun snapshotDefinition(value: LocalBranchInstallationDefinitionV2): LocalBranchInstallationSnapshotV2 {
        val encoded = encodeDefinitionValue(value)
        return try {
            val parsed = decodeDefinitionValue(encoded)
            val second = encodeDefinitionValue(parsed)
            try { require(encoded.contentEquals(second)) } finally { second.fill(0) }
            LocalBranchInstallationSnapshotV2(parsed, encoded)
        } finally { encoded.fill(0) }
    }

    public fun encodeDefinition(snapshot: LocalBranchInstallationSnapshotV2): ByteArray = snapshot.copyWireBytes()

    public fun decodeDefinition(bytes: ByteArray): LocalBranchInstallationSnapshotV2? =
        installationParseV2(bytes, MAXIMUM_DEFINITION_BYTES) { owned ->
            val value = decodeDefinitionValue(owned)
            val canonical = encodeDefinitionValue(value)
            try {
                require(owned.contentEquals(canonical))
                LocalBranchInstallationSnapshotV2(value, canonical)
            } finally { canonical.fill(0) }
        }

    public fun encodeAllocation(value: OriginalAllocationCommitmentV2): ByteArray =
        write("original-allocation", MAXIMUM_ALLOCATION_BYTES) {
            val c = value.coordinates
            installationCoordinateIdsV2(c).forEach { idV2(it) }
            hashV2(value.originalNativeBindingDigest); hashV2(value.p1RootDocumentDigest)
            hashV2(value.p1DelegationDigest); hashV2(value.p1PreparationDigest)
            ownedFieldV2(value.copyTargetPublicKey())
        }

    public fun decodeAllocation(bytes: ByteArray): OriginalAllocationCommitmentV2? =
        installationParseV2(bytes, MAXIMUM_ALLOCATION_BYTES) { owned ->
            val value = allocationValue(owned)
            canonicalV2(owned, encodeAllocation(value)); value
        }

    public fun encodeOriginalTargetKey(value: OriginalAllocationCommitmentV2): ByteArray =
        write("original-target-key", 1024) {
            string("TARGET_SIGNING"); scopeV2(value.coordinates.scope); idV2(value.coordinates.deviceId)
            idV2(value.coordinates.targetSigningKeyId); ownedFieldV2(value.copyTargetPublicKey())
            hashV2(value.originalNativeBindingDigest)
        }

    /** Exact pure semantic policy bytes, NOT the legacy protected-policy row serialization. */
    public fun encodePolicy(value: ActivatedLocalPolicy): ByteArray = policyBytes(value)

    public fun encodeConfiguration(value: LocalAuthConfiguration): ByteArray =
        write("configuration", 4096) {
            require(value.revision == Revision(0))
            scopeV2(value.scope); idV2(value.actorId); long(value.revision.value)
            string(LocalLoginIdentifier(value.login.value).value)
            long(value.tokens.accessLifetimeMillis); long(value.tokens.workLifetimeMillis)
        }

    public fun encodeAdmission(value: LocalDeviceAdmission): ByteArray = admissionBytes(value)

    public fun encodeHumanReference(value: LocalInstallationHumanReferenceV2): ByteArray =
        write("human-reference", 1024) {
            string(HEAD_SOURCE); idV2(value.rootId); idV2(value.rootKeyId); hashV2(value.rootDocumentDigest)
            idV2(value.foundingActionId); hashV2(value.foundingBodyDigest); idV2(value.headAuthorityId)
            idV2(value.headEnrollmentId); idV2(value.headInstallationKeyId)
            ownedFieldV2(value.copyHeadInstallationPublicKey()); string("COMPANY_HEAD"); string("UNTIL_LOCAL_DISABLE")
        }

    public fun encodeAttemptFacts(value: LocalInstallationAttemptFactsV2): ByteArray =
        write("attempt-facts", 1024) {
            idV2(value.issuedAttemptId); long(value.attemptSequence); idV2(value.issuerContextId)
            idV2(value.nativeCustodyLifetimeId); string(ALLOCATED); long(0)
            long(value.originalAnchorGeneration); hashV2(value.originalAnchorStateDigest)
            long(value.headAccountRevision); long(value.headCredentialRevision)
            long(value.installationPurposeRevision); long(value.installationKeyRevision)
            hashV2(value.protectedHeadStateDigest); hashV2(value.nativeCustodyRecordDigest)
            int(value.osBootCount); idV2(value.monotonicBootId); long(value.observedElapsedMillis)
            long(value.deadlineElapsedMillis); long(value.observedOsUtcEpochMillis)
            optionalHashV2(value.protectedClockRecordDigest)
        }

    public fun decodeAttemptFacts(bytes: ByteArray): LocalInstallationAttemptFactsV2? =
        installationParseV2(bytes, 1024) { owned ->
            val value = read(owned, "attempt-facts") {
                val attempt = idV2(); val sequence = long(); val issuer = idV2(); val lifetime = idV2()
                require(string(64) == ALLOCATED && long() == 0L)
                LocalInstallationAttemptFactsV2(attempt, sequence, issuer, lifetime, long(), hashV2(),
                    long(), long(), long(), long(), hashV2(), hashV2(), int(), idV2(), long(), long(), long(),
                    optionalHashV2())
            }
            canonicalV2(owned, encodeAttemptFacts(value)); value
        }

    public fun humanApprovalFrame(
        snapshot: LocalBranchInstallationSnapshotV2,
        attempt: LocalInstallationAttemptFactsV2,
    ): LocalInstallationApprovalFrameV2 {
        requireAttemptV2(snapshot, attempt)
        val human = snapshot.definition.human
        val bytes = write("human-approval", MAXIMUM_APPROVAL_FRAME_BYTES) {
            ownedFieldV2(subjectBytes(snapshot)); ownedFieldV2(encodeAttemptFacts(attempt))
            ownedFieldV2(encodeHumanReference(human))
            string("HEAD_INSTALLATION_APPROVER"); string("FULL_D7_INSTALLATION_APPROVE")
            string("HEAD_INSTALLATION_SIGNING")
        }
        val point = human.copyHeadInstallationPublicKey()
        return try { approvalFrame(snapshot, attempt, LocalInstallationApprovalKindV2.HUMAN,
            human.headInstallationKeyId, point, bytes) } finally { bytes.fill(0); point.fill(0) }
    }

    public fun targetApprovalFrame(
        snapshot: LocalBranchInstallationSnapshotV2,
        attempt: LocalInstallationAttemptFactsV2,
        originalSignedHumanApproval: LocalInstallationSignedApprovalV2,
    ): LocalInstallationApprovalFrameV2 {
        requireAttemptV2(snapshot, attempt)
        requireFrameV2(originalSignedHumanApproval.frame, humanApprovalFrame(snapshot, attempt))
        val allocation = snapshot.definition.allocation
        val bytes = write("target-approval", MAXIMUM_APPROVAL_FRAME_BYTES) {
            ownedFieldV2(subjectBytes(snapshot)); ownedFieldV2(encodeAttemptFacts(attempt))
            hashV2(originalSignedHumanApproval.digest); string("ORIGINAL_TARGET_DEVICE")
            string("FULL_D7_TARGET_POSSESSION"); string("TARGET_SIGNING")
            idV2(allocation.coordinates.targetSigningKeyId); ownedFieldV2(allocation.copyTargetPublicKey())
        }
        val point = allocation.copyTargetPublicKey()
        return try { approvalFrame(snapshot, attempt, LocalInstallationApprovalKindV2.TARGET,
            allocation.coordinates.targetSigningKeyId, point, bytes) } finally { bytes.fill(0); point.fill(0) }
    }

    /** To parse a proof, the complete snapshot and exact original attempt are mandatory. */
    public fun decodeHumanApprovalFrame(bytes: ByteArray, snapshot: LocalBranchInstallationSnapshotV2,
        attempt: LocalInstallationAttemptFactsV2): LocalInstallationApprovalFrameV2? =
        installationParseV2(bytes, MAXIMUM_APPROVAL_FRAME_BYTES) { owned ->
            humanApprovalFrame(snapshot, attempt).also { canonicalV2(owned, it.copyWireBytes()) }
        }

    public fun decodeTargetApprovalFrame(bytes: ByteArray, snapshot: LocalBranchInstallationSnapshotV2,
        attempt: LocalInstallationAttemptFactsV2, human: LocalInstallationSignedApprovalV2): LocalInstallationApprovalFrameV2? =
        installationParseV2(bytes, MAXIMUM_APPROVAL_FRAME_BYTES) { owned ->
            targetApprovalFrame(snapshot, attempt, human).also { canonicalV2(owned, it.copyWireBytes()) }
        }

    public fun signedApproval(frame: LocalInstallationApprovalFrameV2, signature: ByteArray):
        LocalInstallationSignedApprovalV2 = LocalInstallationSignedApprovalV2(frame, signature)

    public fun encodeSignedApproval(value: LocalInstallationSignedApprovalV2): ByteArray =
        write(if (value.frame.kind == LocalInstallationApprovalKindV2.HUMAN)
            "signed-human-approval" else "signed-target-approval", MAXIMUM_SIGNED_APPROVAL_BYTES) {
            ownedFieldV2(value.frame.copyWireBytes()); string(SIGNATURE_ALGORITHM)
            ownedFieldV2(value.copySignature())
        }

    public fun decodeSignedHumanApproval(bytes: ByteArray, snapshot: LocalBranchInstallationSnapshotV2,
        attempt: LocalInstallationAttemptFactsV2): LocalInstallationSignedApprovalV2? =
        signedApprovalValue(bytes, humanApprovalFrame(snapshot, attempt), "signed-human-approval")

    public fun decodeSignedTargetApproval(bytes: ByteArray, snapshot: LocalBranchInstallationSnapshotV2,
        attempt: LocalInstallationAttemptFactsV2, human: LocalInstallationSignedApprovalV2): LocalInstallationSignedApprovalV2? =
        signedApprovalValue(bytes, targetApprovalFrame(snapshot, attempt, human), "signed-target-approval")

    public fun genesisCore(
        snapshot: LocalBranchInstallationSnapshotV2,
        attempt: LocalInstallationAttemptFactsV2,
        approvals: LocalInstallationOriginalApprovalsV2,
        commit: LocalInstallationCommitFactsV2,
        seeds: LocalInstallationSeedDigestsV2,
    ): LocalBranchInstallationGenesisCoreV2 {
        requireOriginalApprovalsV2(snapshot, attempt, approvals)
        require(commit.issuerContextId == attempt.issuerContextId &&
            commit.nativeCustodyLifetimeId == attempt.nativeCustodyLifetimeId &&
            commit.monotonicBootId == attempt.monotonicBootId &&
            commit.elapsedMillis >= attempt.observedElapsedMillis &&
            commit.elapsedMillis < attempt.deadlineElapsedMillis)
        val d = snapshot.definition
        return LocalBranchInstallationGenesisCoreV2(d.allocation, snapshot.definitionDigest, d.originalRequestId,
            attempt.issuedAttemptId, attempt.attemptSequence, d.authorityEpoch, approvals.human.digest,
            approvals.target.digest, commit.issuerContextId, commit.monotonicBootId, commit.elapsedMillis,
            commit.osUtcEpochMillis, commit.installationCustodyEvidenceDigest, d.human.headAuthorityId,
            d.human.headEnrollmentId, d.human.foundingBodyDigest, seeds).also {
                val bytes = encodeGenesisCore(it); bytes.fill(0)
            }
    }

    public fun encodeGenesisCore(value: LocalBranchInstallationGenesisCoreV2): ByteArray =
        write("genesis-core", MAXIMUM_GENESIS_CORE_BYTES) {
            ownedFieldV2(encodeAllocation(value.allocation)); hashV2(value.definitionDigest)
            idV2(value.originalRequestId); idV2(value.issuedAttemptId); long(value.attemptSequence)
            long(value.authorityEpoch.value); hashV2(value.humanApprovalDigest); hashV2(value.targetProofDigest)
            idV2(value.issuerContextId); idV2(value.monotonicBootId); long(value.installedAtElapsedMillis)
            long(value.installedAtOsUtcEpochMillis); hashV2(value.installationCustodyEvidenceDigest)
            idV2(value.headAuthorityId); idV2(value.headEnrollmentId); hashV2(value.foundingBodyDigest)
            with(value.seeds) {
                hashV2(account); hashV2(policy); hashV2(configuration); hashV2(admission)
                hashV2(accountThrottle); hashV2(deviceThrottle); hashV2(recoveryState)
            }
            string(SEED_PROFILE); long(0); long(0); bool(true)
        }

    public fun receipt(core: LocalBranchInstallationGenesisCoreV2): LocalBranchInstallationReceiptV2 =
        LocalBranchInstallationReceiptV2(core)

    public fun encodeReceipt(value: LocalBranchInstallationReceiptV2): ByteArray =
        write("installation-receipt", MAXIMUM_RECEIPT_BYTES) {
            ownedFieldV2(encodeGenesisCore(value.core)); hashV2(value.genesis)
        }

    public fun decodeReceipt(bytes: ByteArray): LocalBranchInstallationReceiptV2? =
        installationParseV2(bytes, MAXIMUM_RECEIPT_BYTES) { owned ->
            val value = read(owned, "installation-receipt") {
                val coreBytes = field(MAXIMUM_GENESIS_CORE_BYTES)
                val core = try { genesisCoreValue(coreBytes) } finally { coreBytes.fill(0) }
                LocalBranchInstallationReceiptV2(core).also { require(hashV2() == it.genesis) }
            }
            canonicalV2(owned, encodeReceipt(value)); value
        }

    public fun acknowledgement(
        snapshot: LocalBranchInstallationSnapshotV2,
        attempt: LocalInstallationAttemptFactsV2,
        approvals: LocalInstallationOriginalApprovalsV2,
        receipt: LocalBranchInstallationReceiptV2,
        child: LocalInstallationCompletedChildFactsV2,
    ): LocalInstallationCommitAcknowledgementV2 {
        requireOriginalApprovalsV2(snapshot, attempt, approvals)
        requireReceiptV2(snapshot, attempt, approvals, receipt)
        require(child.originalAllocationDigest == snapshot.allocationDigest &&
            child.definitionDigest == snapshot.definitionDigest &&
            child.originalRequestId == snapshot.definition.originalRequestId &&
            child.issuedAttemptId == attempt.issuedAttemptId && child.attemptSequence == attempt.attemptSequence &&
            child.installationGenesisDigest == receipt.genesis && child.receiptDigest == receipt.digest &&
            child.commandEpoch == snapshot.definition.authorityEpoch)
        val d = snapshot.definition; val c = d.allocation.coordinates; val h = d.human
        val bytes = write("commit-acknowledgement", MAXIMUM_ACKNOWLEDGEMENT_BYTES) {
            string("ORIGINAL_COMPANY_ROOT_ACK"); string("ACK_ORIGINAL_COMMITTED_INSTALLATION")
            idV2(h.rootId); idV2(h.rootKeyId); hashV2(h.rootDocumentDigest)
            hashV2(snapshot.allocationDigest); hashV2(snapshot.definitionDigest); idV2(d.originalRequestId)
            idV2(attempt.issuedAttemptId); long(attempt.attemptSequence); scopeV2(c.scope)
            idV2(c.installationId); idV2(c.storageInstanceId); idV2(c.targetSigningKeyId)
            idV2(h.headAuthorityId); idV2(h.headEnrollmentId); hashV2(approvals.human.digest)
            hashV2(approvals.target.digest); hashV2(receipt.genesis); hashV2(receipt.digest)
            hashV2(child.historyDigest); long(1); string(ALLOCATED); long(0); long(d.authorityEpoch.value)
            long(0); hashV2(receipt.core.installationCustodyEvidenceDigest); hashV2(attempt.originalAnchorStateDigest)
        }
        return try { LocalInstallationCommitAcknowledgementV2(h.rootKeyId, snapshot.allocationDigest,
            snapshot.definitionDigest, d.originalRequestId, attempt.issuedAttemptId, receipt.digest,
            child.historyDigest, bytes) } finally { bytes.fill(0) }
    }

    public fun encodeAcknowledgement(value: LocalInstallationCommitAcknowledgementV2): ByteArray = value.copyWireBytes()

    public fun decodeAcknowledgement(bytes: ByteArray, snapshot: LocalBranchInstallationSnapshotV2,
        attempt: LocalInstallationAttemptFactsV2, approvals: LocalInstallationOriginalApprovalsV2,
        receipt: LocalBranchInstallationReceiptV2, child: LocalInstallationCompletedChildFactsV2):
        LocalInstallationCommitAcknowledgementV2? =
        installationParseV2(bytes, MAXIMUM_ACKNOWLEDGEMENT_BYTES) { owned ->
            acknowledgement(snapshot, attempt, approvals, receipt, child).also { canonicalV2(owned, it.copyWireBytes()) }
        }

    public fun signedAcknowledgement(frame: LocalInstallationCommitAcknowledgementV2, signature: ByteArray):
        LocalInstallationSignedAcknowledgementV2 = LocalInstallationSignedAcknowledgementV2(frame, signature)

    public fun encodeSignedAcknowledgement(value: LocalInstallationSignedAcknowledgementV2): ByteArray =
        write("signed-commit-acknowledgement", MAXIMUM_SIGNED_ACKNOWLEDGEMENT_BYTES) {
            ownedFieldV2(value.frame.copyWireBytes()); string(SIGNATURE_ALGORITHM); ownedFieldV2(value.copySignature())
        }

    public fun decodeSignedAcknowledgement(bytes: ByteArray, snapshot: LocalBranchInstallationSnapshotV2,
        attempt: LocalInstallationAttemptFactsV2, approvals: LocalInstallationOriginalApprovalsV2,
        receipt: LocalBranchInstallationReceiptV2, child: LocalInstallationCompletedChildFactsV2):
        LocalInstallationSignedAcknowledgementV2? =
        installationParseV2(bytes, MAXIMUM_SIGNED_ACKNOWLEDGEMENT_BYTES) { owned ->
            val expected = acknowledgement(snapshot, attempt, approvals, receipt, child)
            val value = read(owned, "signed-commit-acknowledgement") {
                val frame = field(MAXIMUM_ACKNOWLEDGEMENT_BYTES)
                try { canonicalV2(frame, expected.copyWireBytes()) } finally { frame.fill(0) }
                require(string(64) == SIGNATURE_ALGORITHM)
                val signature = field(64)
                try { signedAcknowledgement(expected, signature) } finally { signature.fill(0) }
            }
            canonicalV2(owned, encodeSignedAcknowledgement(value)); value
        }

    private fun encodeDefinitionValue(value: LocalBranchInstallationDefinitionV2): ByteArray =
        write("full-definition", MAXIMUM_DEFINITION_BYTES) {
            val account = value.firstAccount
            ownedFieldV2(encodeAllocation(value.allocation)); idV2(value.originalRequestId)
            long(value.authorityEpoch.value); ownedFieldV2(encodeHumanReference(value.human))
            ownedFieldV2(write("account-intent", MAXIMUM_POLICY_BYTES + 8192) {
                idV2(account.actorId); idSetV2(account.admittedDeviceIds, 1)
                ownedFieldV2(encodeConfiguration(LocalAuthConfiguration(value.allocation.coordinates.scope,
                    account.actorId, Revision(0), account.login, account.tokenPolicy)))
                ownedFieldV2(policyBytes(account.policy))
            })
            ownedFieldV2(admissionBytes(value.masterAdmission)); ownedFieldV2(seedProfileBytes())
        }

    private fun decodeDefinitionValue(bytes: ByteArray): LocalBranchInstallationDefinitionV2 =
        read(bytes, "full-definition") {
            val aBytes = field(MAXIMUM_ALLOCATION_BYTES)
            val a = try { allocationValue(aBytes) } finally { aBytes.fill(0) }
            val request = idV2(); val epoch = Revision(long())
            val hBytes = field(1024)
            val h = try { humanValue(hBytes) } finally { hBytes.fill(0) }
            val accountBytes = field(MAXIMUM_POLICY_BYTES + 8192)
            val account = try { read(accountBytes, "account-intent") {
                val actor = idV2(); val admitted = idSetV2(1, 1)
                val cBytes = field(4096)
                val config = try { configurationValue(cBytes) } finally { cBytes.fill(0) }
                val pBytes = field(MAXIMUM_POLICY_BYTES)
                val policy = try { policyValue(pBytes) } finally { pBytes.fill(0) }
                require(config.scope == a.coordinates.scope && config.actorId == actor && config.revision == Revision(0))
                LocalAccountProvisioning(actor, config.login, policy, config.tokens, admitted)
            } } finally { accountBytes.fill(0) }
            val admissionBytes = field(16384)
            val admission = try { admissionValue(admissionBytes) } finally { admissionBytes.fill(0) }
            val seedBytes = field(512)
            try { canonicalV2(seedBytes, seedProfileBytes()) } finally { seedBytes.fill(0) }
            LocalBranchInstallationDefinitionV2(a, request, epoch, h, account, admission)
        }

    private fun allocationValue(bytes: ByteArray): OriginalAllocationCommitmentV2 =
        read(bytes, "original-allocation") {
            val allocation = idV2(); val scope = scopeV2()
            val c = SelfOwnedAllocationCoordinatesV1(allocation, scope, idV2(), idV2(), idV2(),
                idV2(), idV2(), idV2(), idV2(), idV2())
            val binding = hashV2(); val root = hashV2(); val delegation = hashV2(); val preparation = hashV2()
            val point = field(65)
            try { OriginalAllocationCommitmentV2(c, binding, root, delegation, preparation, point) }
            finally { point.fill(0) }
        }

    private fun humanValue(bytes: ByteArray): LocalInstallationHumanReferenceV2 =
        read(bytes, "human-reference") {
            require(string(64) == HEAD_SOURCE)
            val root = idV2(); val key = idV2(); val rootDigest = hashV2(); val action = idV2()
            val founding = hashV2(); val head = idV2(); val enrollment = idV2(); val installationKey = idV2()
            val point = field(65)
            try {
                require(string(64) == "COMPANY_HEAD" && string(64) == "UNTIL_LOCAL_DISABLE")
                LocalInstallationHumanReferenceV2(root, key, rootDigest, action, founding, head,
                    enrollment, installationKey, point)
            } finally { point.fill(0) }
        }

    private fun configurationValue(bytes: ByteArray): LocalAuthConfiguration =
        read(bytes, "configuration") {
            val scope = scopeV2(); val actor = idV2(); val revision = Revision(long())
            require(revision == Revision(0))
            LocalAuthConfiguration(scope, actor, revision, LocalLoginIdentifier(string(256)),
                LocalTokenPolicy(long(), long()))
        }

    private fun policyBytes(value: ActivatedLocalPolicy): ByteArray {
        // This frozen installation packet cannot represent the later policy extension.
        // Keep its exact bytes and fail rather than silently dropping delegated settings.
        require(value.employeeCreationConfiguration == null)
        return write("policy", MAXIMUM_POLICY_BYTES) {
            require(value.revision == Revision(0))
            scopeV2(value.scope); idV2(value.actorId); long(value.revision.value)
            val grants = value.grants
            require(grants.size <= 1024); int(grants.size)
            for (g in grants) {
                actionV2(g.permission.action); verbV2(g.permission.verb)
                when (val scope = g.scope) {
                    is CompanyScope -> {
                        require(scope == value.scope.company()); string("COMPANY")
                        idV2(scope.organizationId); idV2(scope.companyId)
                    }
                    is BranchScope -> { require(scope == value.scope); string("BRANCH"); scopeV2(scope) }
                }
                string(when (g.effect) { GrantEffect.ALLOW -> "ALLOW"; GrantEffect.DENY -> "DENY" })
                limitsV2(g.limits)
                string(when (g.ownerAccess) { OwnerAccess.OWN_ONLY -> "OWN_ONLY"; OwnerAccess.ANY_OWNER -> "ANY_OWNER" })
                require(g.namedSubjectId == null || g.namedSubjectId == value.actorId)
                optionalIdV2(g.namedSubjectId)
                bool(g.targetIds != null); g.targetIds?.let { require(it.isNotEmpty()); idSetV2(it, 256) }
                bool(g.delegationCeiling != null); g.delegationCeiling?.let { limitsV2(it) }
            }
            int(value.sessionPolicy.maximumConcurrentSessions)
            with(value.throttlePolicy) {
                int(freeFailures); long(initialDelayMillis); long(maximumDelayMillis); long(attemptTimeoutMillis)
            }
            bool(value.approvalPolicy != null)
            value.approvalPolicy?.let {
                long(it.validForMillis)
                val actions = it.namedSelfApprovalActions.map(::actionTagV2).sorted()
                require(actions.size <= 256); int(actions.size); actions.forEach { name -> string(name) }
            }
        }
    }

    private fun policyValue(bytes: ByteArray): ActivatedLocalPolicy =
        read(bytes, "policy") {
            val scope = scopeV2(); val actor = idV2(); val revision = Revision(long()); require(revision == Revision(0))
            val count = int(); require(count <= 1024)
            val grants = List(count) {
                val permission = Permission(actionV2(), verbV2())
                val grantScope: TenantScope = when (string(16)) {
                    "COMPANY" -> CompanyScope(idV2(), idV2())
                    "BRANCH" -> scopeV2()
                    else -> throw IllegalArgumentException("Unsupported installation grant scope")
                }
                val effect = when (string(8)) {
                    "ALLOW" -> GrantEffect.ALLOW; "DENY" -> GrantEffect.DENY
                    else -> throw IllegalArgumentException("Unsupported installation grant effect")
                }
                val limits = limitsV2()
                val owner = when (string(16)) {
                    "OWN_ONLY" -> OwnerAccess.OWN_ONLY; "ANY_OWNER" -> OwnerAccess.ANY_OWNER
                    else -> throw IllegalArgumentException("Unsupported installation owner access")
                }
                val subject = optionalIdV2()
                val targets = if (bool()) idSetV2(256, 1) else null
                val ceiling = if (bool()) limitsV2() else null
                PermissionGrant(permission, grantScope, effect, limits, owner, subject, targets, ceiling)
            }
            val session = LocalSessionPolicy(int())
            val throttle = ThrottlePolicy(int(), long(), long(), long())
            val approval = if (bool()) {
                val duration = long(); val n = int(); require(n <= 256)
                val names = List(n) { string(64).also { require(it in INSTALLATION_ACTIONS_V2) } }
                requireStrictOrderV2(names)
                LocalApprovalPolicy(duration, names.map { SecurityAction.valueOf(it) }.toSet())
            } else null
            ActivatedLocalPolicy(scope, actor, revision, grants, session, throttle, approval)
        }

    private fun admissionBytes(value: LocalDeviceAdmission): ByteArray =
        write("admission", 16384) {
            require(value.revision == Revision(0) && value.admitted)
            val checked = copyInstallationAdmissionV2(value)
            scopeV2(checked.scope); idV2(checked.deviceId); long(checked.revision.value); bool(checked.admitted)
            optionalTextV2(checked.publicKeyIdentifier); optionalTextV2(checked.publicFingerprint)
            val capabilities = checked.capabilities.sorted()
            int(capabilities.size); capabilities.forEach { string(it) }
        }

    private fun admissionValue(bytes: ByteArray): LocalDeviceAdmission =
        read(bytes, "admission") {
            val scope = scopeV2(); val device = idV2(); val revision = Revision(long()); val admitted = bool()
            require(revision == Revision(0) && admitted)
            val key = optionalTextV2(512); val fingerprint = optionalTextV2(512)
            val n = int(); require(n <= 64)
            val codes = List(n) { string(64) }; requireStrictOrderV2(codes)
            LocalDeviceAdmission(scope, device, revision, admitted, key, fingerprint, codes.toSet())
        }

    private fun seedProfileBytes(): ByteArray = write("initial-seed-profile", 512) {
        string(SEED_PROFILE); string(CREDENTIAL_ALGORITHM)
        int(600_000); int(5_000_000); int(16); int(64); int(32)
    }

    private fun subjectBytes(snapshot: LocalBranchInstallationSnapshotV2): ByteArray =
        write("installation-subject", 1024) {
            val d = snapshot.definition; val c = d.allocation.coordinates
            hashV2(snapshot.allocationDigest); hashV2(snapshot.definitionDigest); idV2(d.originalRequestId)
            scopeV2(c.scope); idV2(c.installationId); idV2(c.assignmentId); idV2(c.deviceId)
            idV2(c.storageInstanceId); idV2(c.storageKeyId); idV2(c.pepperKeyId)
            idV2(c.targetSigningKeyId); idV2(c.firstActorId); long(d.authorityEpoch.value)
            hashV2(installationHashV2(encodeHumanReference(d.human)))
        }

    private fun genesisCoreValue(bytes: ByteArray): LocalBranchInstallationGenesisCoreV2 =
        read(bytes, "genesis-core") {
            val aBytes = field(MAXIMUM_ALLOCATION_BYTES)
            val a = try { allocationValue(aBytes) } finally { aBytes.fill(0) }
            val definition = hashV2(); val request = idV2(); val attempt = idV2(); val sequence = long()
            val epoch = Revision(long()); val humanProof = hashV2(); val targetProof = hashV2()
            val issuer = idV2(); val boot = idV2(); val elapsed = long(); val utc = long(); val custody = hashV2()
            val head = idV2(); val enrollment = idV2(); val founding = hashV2()
            val seeds = LocalInstallationSeedDigestsV2(hashV2(), hashV2(), hashV2(), hashV2(), hashV2(), hashV2(), hashV2())
            require(string(64) == SEED_PROFILE && long() == 0L && long() == 0L && bool())
            LocalBranchInstallationGenesisCoreV2(a, definition, request, attempt, sequence, epoch, humanProof,
                targetProof, issuer, boot, elapsed, utc, custody, head, enrollment, founding, seeds)
        }

    private fun signedApprovalValue(bytes: ByteArray, expected: LocalInstallationApprovalFrameV2,
        domain: String): LocalInstallationSignedApprovalV2? =
        installationParseV2(bytes, MAXIMUM_SIGNED_APPROVAL_BYTES) { owned ->
            val value = read(owned, domain) {
                val frame = field(MAXIMUM_APPROVAL_FRAME_BYTES)
                try { canonicalV2(frame, expected.copyWireBytes()) } finally { frame.fill(0) }
                require(string(64) == SIGNATURE_ALGORITHM)
                val signature = field(64)
                try { signedApproval(expected, signature) } finally { signature.fill(0) }
            }
            canonicalV2(owned, encodeSignedApproval(value)); value
        }

    private fun requireOriginalApprovalsV2(snapshot: LocalBranchInstallationSnapshotV2,
        attempt: LocalInstallationAttemptFactsV2, proofs: LocalInstallationOriginalApprovalsV2) {
        requireFrameV2(proofs.human.frame, humanApprovalFrame(snapshot, attempt))
        requireFrameV2(proofs.target.frame, targetApprovalFrame(snapshot, attempt, proofs.human))
    }

    private fun requireReceiptV2(snapshot: LocalBranchInstallationSnapshotV2,
        attempt: LocalInstallationAttemptFactsV2, proofs: LocalInstallationOriginalApprovalsV2,
        receipt: LocalBranchInstallationReceiptV2) {
        val d = snapshot.definition; val r = receipt.core
        require(r.allocation.digest == snapshot.allocationDigest && r.definitionDigest == snapshot.definitionDigest &&
            r.originalRequestId == d.originalRequestId && r.issuedAttemptId == attempt.issuedAttemptId &&
            r.attemptSequence == attempt.attemptSequence && r.authorityEpoch == d.authorityEpoch &&
            r.humanApprovalDigest == proofs.human.digest && r.targetProofDigest == proofs.target.digest &&
            r.issuerContextId == attempt.issuerContextId && r.monotonicBootId == attempt.monotonicBootId &&
            r.installedAtElapsedMillis >= attempt.observedElapsedMillis &&
            r.installedAtElapsedMillis < attempt.deadlineElapsedMillis &&
            r.headAuthorityId == d.human.headAuthorityId && r.headEnrollmentId == d.human.headEnrollmentId &&
            r.foundingBodyDigest == d.human.foundingBodyDigest)
    }

    private fun requireAttemptV2(snapshot: LocalBranchInstallationSnapshotV2, attempt: LocalInstallationAttemptFactsV2) {
        require(attempt.issuedAttemptId != snapshot.definition.originalRequestId &&
            attempt.issuedAttemptId !in installationCoordinateIdsV2(snapshot.definition.allocation.coordinates))
    }

    private fun approvalFrame(snapshot: LocalBranchInstallationSnapshotV2, attempt: LocalInstallationAttemptFactsV2,
        kind: LocalInstallationApprovalKindV2, key: DomainId, point: ByteArray, bytes: ByteArray):
        LocalInstallationApprovalFrameV2 =
        LocalInstallationApprovalFrameV2(kind, snapshot.allocationDigest, snapshot.definitionDigest,
            snapshot.definition.originalRequestId, attempt.issuedAttemptId, attempt.attemptSequence, key, point, bytes)

    private fun requireFrameV2(actual: LocalInstallationApprovalFrameV2, expected: LocalInstallationApprovalFrameV2) {
        val a = actual.copyWireBytes(); val b = expected.copyWireBytes()
        try { require(actual.kind == expected.kind && a.contentEquals(b)) } finally { a.fill(0); b.fill(0) }
    }

    private fun write(domain: String, maximum: Int, body: SelfOwnedWireWriterV1.() -> Unit): ByteArray {
        val writer = SelfOwnedWireWriterV1(maximum)
        return try { writer.string(PROFILE); writer.string(domain); writer.int(FORMAT); writer.body(); writer.finish() }
        finally { writer.clear() }
    }

    private fun <T> read(bytes: ByteArray, domain: String, body: SelfOwnedWireReaderV1.() -> T): T {
        val reader = SelfOwnedWireReaderV1(bytes)
        require(reader.string(32) == PROFILE && reader.string(64) == domain && reader.int() == FORMAT)
        return reader.body().also { reader.finish() }
    }

    private fun SelfOwnedWireWriterV1.limitsV2(value: PermissionLimits) {
        bool(value.maximumAmount != null); value.maximumAmount?.let {
            require(it.currency == Currency.EGP && it.minor >= 0); string("EGP"); long(it.minor)
        }
        bool(value.maximumPercentageBasisPoints != null); value.maximumPercentageBasisPoints?.let { int(it) }
        bool(value.noMonetaryCeiling != null); value.noMonetaryCeiling?.let {
            require(it.currency == Currency.EGP); string("EGP")
        }
    }

    private fun SelfOwnedWireReaderV1.limitsV2(): PermissionLimits {
        val amount = if (bool()) { require(string(3) == "EGP"); Money(Currency.EGP, long()) } else null
        val percentage = if (bool()) int() else null
        val unlimited = if (bool()) { require(string(3) == "EGP"); NoMonetaryCeiling(Currency.EGP) } else null
        return PermissionLimits(amount, percentage, unlimited)
    }

    private fun SelfOwnedWireWriterV1.actionV2(value: SecurityAction) { string(actionTagV2(value)) }
    private fun SelfOwnedWireReaderV1.actionV2(): SecurityAction {
        val name = string(64); require(name in INSTALLATION_ACTIONS_V2); return SecurityAction.valueOf(name)
    }
    private fun SelfOwnedWireWriterV1.verbV2(value: PermissionVerb) {
        string(when (value) {
            PermissionVerb.PERFORM -> "PERFORM"; PermissionVerb.REQUEST -> "REQUEST"
            PermissionVerb.APPROVE -> "APPROVE"; PermissionVerb.ADMINISTER -> "ADMINISTER"; PermissionVerb.EXPORT -> "EXPORT"
        })
    }
    private fun SelfOwnedWireReaderV1.verbV2(): PermissionVerb = when (string(16)) {
        "PERFORM" -> PermissionVerb.PERFORM; "REQUEST" -> PermissionVerb.REQUEST; "APPROVE" -> PermissionVerb.APPROVE
        "ADMINISTER" -> PermissionVerb.ADMINISTER; "EXPORT" -> PermissionVerb.EXPORT
        else -> throw IllegalArgumentException("Unsupported installation verb")
    }

    private fun SelfOwnedWireWriterV1.idSetV2(values: Set<DomainId>, maximum: Int) {
        val copy = values.toList().sortedBy { it.value }; require(copy.size <= maximum)
        int(copy.size); copy.forEach { idV2(it) }
    }
    private fun SelfOwnedWireReaderV1.idSetV2(maximum: Int, minimum: Int = 0): Set<DomainId> {
        val n = int(); require(n in minimum..maximum)
        val ids = List(n) { idV2() }; requireStrictOrderV2(ids.map { it.value }); return ids.toSet()
    }

    private fun SelfOwnedWireWriterV1.idV2(value: DomainId) {
        require(INSTALLATION_ID_V2.matches(value.value)); string(value.value)
    }
    private fun SelfOwnedWireReaderV1.idV2(): DomainId {
        val text = string(36); require(INSTALLATION_ID_V2.matches(text)); return DomainId(text)
    }
    private fun SelfOwnedWireWriterV1.scopeV2(value: BranchScope) {
        idV2(value.organizationId); idV2(value.companyId); idV2(value.branchId)
    }
    private fun SelfOwnedWireReaderV1.scopeV2(): BranchScope = BranchScope(idV2(), idV2(), idV2())
    private fun SelfOwnedWireWriterV1.optionalIdV2(value: DomainId?) { bool(value != null); value?.let { idV2(it) } }
    private fun SelfOwnedWireReaderV1.optionalIdV2(): DomainId? = if (bool()) idV2() else null
    private fun SelfOwnedWireWriterV1.optionalTextV2(value: String?) { bool(value != null); value?.let { string(it) } }
    private fun SelfOwnedWireReaderV1.optionalTextV2(maximum: Int): String? = if (bool()) string(maximum) else null
    private fun SelfOwnedWireWriterV1.optionalHashV2(value: PayloadDigest?) { bool(value != null); value?.let { hashV2(it) } }
    private fun SelfOwnedWireReaderV1.optionalHashV2(): PayloadDigest? = if (bool()) hashV2() else null
    private fun SelfOwnedWireWriterV1.hashV2(value: PayloadDigest) { ownedFieldV2(installationUnhexV2(value.value)) }
    private fun SelfOwnedWireReaderV1.hashV2(): PayloadDigest {
        val bytes = field(32)
        return try { require(bytes.size == 32); PayloadDigest(installationHexV2(bytes)) } finally { bytes.fill(0) }
    }
    private fun SelfOwnedWireWriterV1.ownedFieldV2(bytes: ByteArray) {
        try { field(bytes) } finally { bytes.fill(0) }
    }
}

private val INSTALLATION_ID_V2 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
private val INSTALLATION_ACTIONS_V2 = setOf(
    "ORDER_CREATE", "ORDER_READ", "ORDER_EDIT", "ORDER_HANDOFF", "ORDER_REASSIGN", "DISCOUNT",
    "PRICE_OVERRIDE", "BRANCH_PRICE_EDIT", "CASH_OPEN", "CASH_MOVE", "CASH_TRANSFER", "CASH_RECEIVE",
    "CASH_CLOSE", "CASH_REVIEW", "CASH_READ", "CASH_CONFIGURE", "COLLECTION_BEGIN", "COLLECTION_ATTEST",
    "COLLECTION_RESOLVE", "COLLECTION_FINALIZE", "COLLECTION_FINALIZE_ZERO", "RETURN_CLAIM", "RETURN_ACCEPT",
    "RETURN_RECEIPT_LOOKUP", "REFUND_RECORD", "REFUND_RESOLVE", "INVENTORY_QUANTITY_READ", "INVENTORY_COST_READ",
    "INVENTORY_ADJUST", "CATALOG_ACTIVATE", "CATALOG_READ", "CATALOG_AUDIT_READ", "WAREHOUSE_CONFIGURE",
    "SUPPLIER_ADMINISTRATION", "PURCHASING_READ", "PURCHASING_APPROVE", "OPENING_INVENTORY_APPROVE",
    "TENDER_CONFIGURE", "KITCHEN_TASK", "DELIVERY_ASSIGNMENT", "BRANCH_REPORT", "LOCAL_ACCOUNT_ADMINISTRATION",
    "DEVICE_ADMINISTRATION", "OPERATION_RESULT_RECOVERY", "RECOVERY_COPY", "RECOVERY_ACTIVATION",
    "RECOVERY_RECONCILIATION", "COMPANY_STRUCTURE", "COMPANY_PEOPLE", "COMPANY_CATALOG", "COMPANY_POLICY",
    "COMPANY_REPORT", "DEVICE_STATUS", "MASTER_COMMIT", "CENTRAL_INGESTION",
    "SUPPLIER_CASH_SETTLE", "SUPPLIER_CASH_RESOLVE",
    "FLOOR_CONFIGURE", "FLOOR_READ",
    "CUSTOMER_RECEIPT_RECORD",
    "PURCHASING_DOCUMENT_CAPTURE", "PURCHASING_DOCUMENT_ATTACH", "PURCHASING_DOCUMENT_READ",
    "PAYMENT_CLASSIFICATION_CORRECT",
    "PURCHASING_INVOICE_CAPTURE",
    "PURCHASING_INVOICE_MATCH_RECOGNIZE",
    "SETUP_RESULT_DELIVERY_ACKNOWLEDGE", "SETUP_BUSINESS_GO_LIVE", "SETUP_RESULT_READ",
    "SETUP_CONFIGURATION_SETTLE", "SETUP_CATALOG_IMPORT_SETTLE", "SETUP_OPENING_SETTLE", "SETUP_CHECKPOINTS_READ",
    "MASTER_LIFECYCLE",
)

private fun actionTagV2(value: SecurityAction): String = value.name.also { require(it in INSTALLATION_ACTIONS_V2) }

private fun installationCoordinateIdsV2(c: SelfOwnedAllocationCoordinatesV1): List<DomainId> = listOf(
    c.allocationId, c.scope.organizationId, c.scope.companyId, c.scope.branchId, c.installationId,
    c.assignmentId, c.deviceId, c.storageInstanceId, c.storageKeyId, c.pepperKeyId, c.targetSigningKeyId, c.firstActorId,
)

private fun copyInstallationAccountV2(value: LocalAccountProvisioning): LocalAccountProvisioning {
    val p = value.policy
    val policy = ActivatedLocalPolicy(p.scope, p.actorId, p.revision, p.grants, p.sessionPolicy,
        p.throttlePolicy, p.approvalPolicy?.let { LocalApprovalPolicy(it.validForMillis, it.namedSelfApprovalActions) },
        p.employeeCreationConfiguration)
    return LocalAccountProvisioning(value.actorId, LocalLoginIdentifier(value.login.value), policy,
        value.tokenPolicy, value.admittedDeviceIds.toSet())
}

private fun copyInstallationAdmissionV2(value: LocalDeviceAdmission): LocalDeviceAdmission =
    LocalDeviceAdmission(value.scope, value.deviceId, value.revision, value.admitted,
        value.publicKeyIdentifier, value.publicFingerprint, value.capabilities)

private fun installationPointV2(bytes: ByteArray): ByteArray {
    require(bytes.size == 65 && bytes[0] == 4.toByte())
    return bytes.copyOf()
}

private fun installationSignatureV2(bytes: ByteArray): ByteArray {
    require(bytes.size == 64)
    val order = installationUnhexV2("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551")
    val half = installationUnhexV2("7fffffff800000007fffffffffffffffde737d56d38bcf4279dce5617e3192a8")
    val r = bytes.copyOfRange(0, 32); val s = bytes.copyOfRange(32, 64)
    try {
        require(r.any { it != 0.toByte() } && s.any { it != 0.toByte() })
        require(compareInstallationOctetsV2(r, order) < 0 && compareInstallationOctetsV2(s, half) <= 0)
        return bytes.copyOf()
    } finally { order.fill(0); half.fill(0); r.fill(0); s.fill(0) }
}

private fun compareInstallationOctetsV2(a: ByteArray, b: ByteArray): Int {
    require(a.size == b.size)
    for (i in a.indices) {
        val x = a[i].toInt() and 255; val y = b[i].toInt() and 255
        if (x != y) return x.compareTo(y)
    }
    return 0
}

private fun installationHashV2(owned: ByteArray): PayloadDigest =
    try { sha256(owned) } finally { owned.fill(0) }

private fun installationUnhexV2(text: String): ByteArray {
    require(text.length == 64 && text.all { it in '0'..'9' || it in 'a'..'f' })
    return ByteArray(32) { i -> ((text[i * 2].digitToInt(16) shl 4) or text[i * 2 + 1].digitToInt(16)).toByte() }
}

private fun installationHexV2(bytes: ByteArray): String {
    val alphabet = "0123456789abcdef"
    return buildString(bytes.size * 2) {
        for (byte in bytes) { val value = byte.toInt() and 255; append(alphabet[value ushr 4]); append(alphabet[value and 15]) }
    }
}

private fun requireStrictOrderV2(values: List<String>) {
    for (i in 1 until values.size) require(values[i - 1] < values[i])
}

private fun canonicalV2(actual: ByteArray, ownedExpected: ByteArray) {
    try { require(actual.contentEquals(ownedExpected)) } finally { ownedExpected.fill(0) }
}

private inline fun <T> installationParseV2(bytes: ByteArray, maximum: Int, block: (ByteArray) -> T): T? {
    if (bytes.size !in 1..maximum) return null
    val owned = bytes.copyOf()
    return try { block(owned) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }
    finally { owned.fill(0) }
}
