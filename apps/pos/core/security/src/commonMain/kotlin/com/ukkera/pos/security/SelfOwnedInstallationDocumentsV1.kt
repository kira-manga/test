package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId

public enum class SelfOwnedDocumentRoleV1(internal val wireId: Int) {
    ROOT_ANNOUNCEMENT(1), ISSUER_DELEGATION(2), NATIVE_PREPARATION(3),
}

/** Parsed signed bytes are UNTRUSTED DATA; this type alone grants no native or Room authority. */
public class SelfOwnedSignedDocumentV1 internal constructor(
    public val role: SelfOwnedDocumentRoleV1,
    body: ByteArray,
    signature: ByteArray,
) {
    private val bodyValue: SelfOwnedOctetsV1
    private val signatureValue: SelfOwnedOctetsV1
    init {
        require(body.isNotEmpty() && body.size <= SelfOwnedInstallationProfileV1.MAXIMUM_AUTHORITY_DOCUMENT_BYTES - 512)
        require(signature.size == 64)
        bodyValue = SelfOwnedOctetsV1(body)
        signatureValue = SelfOwnedOctetsV1(signature)
    }
    public fun copyWireBytes(): ByteArray = SelfOwnedInstallationWireV1.encodeSigned(this)
    internal fun body(): ByteArray = bodyValue.copyBytes()
    internal fun signature(): ByteArray = signatureValue.copyBytes()
    override fun toString(): String = "SelfOwnedSignedDocumentV1([REDACTED])"
}

/** Native installed-app facts, not hardware attestation, uniqueness or old-writer exclusion. */
public class SelfOwnedTargetDescriptorV1 internal constructor(
    public val applicationId: String,
    public val applicationUid: Int,
    public val apkSignerSha256: String,
    public val androidIdCommitment: String,
) {
    init {
        require(applicationId.length in 1..255 && applicationId.all {
            it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '_'
        })
        require(applicationUid >= 0)
        selfOwnedHexDigest(apkSignerSha256); selfOwnedHexDigest(androidIdCommitment)
    }
    internal fun same(other: SelfOwnedTargetDescriptorV1): Boolean = applicationId == other.applicationId &&
        applicationUid == other.applicationUid && apkSignerSha256 == other.apkSignerSha256 &&
        androidIdCommitment == other.androidIdCommitment
    override fun toString(): String = "SelfOwnedTargetDescriptorV1([REDACTED])"
}

/** Original coordinates reserved before signing/delivery; no branch key/account exists in P1. */
public class SelfOwnedAllocationCoordinatesV1 internal constructor(
    public val allocationId: DomainId,
    public val scope: BranchScope,
    public val installationId: DomainId,
    public val assignmentId: DomainId,
    public val deviceId: DomainId,
    public val storageInstanceId: DomainId,
    public val storageKeyId: DomainId,
    public val pepperKeyId: DomainId,
    public val targetSigningKeyId: DomainId,
    public val firstActorId: DomainId,
) {
    init {
        require(listOf(allocationId, scope.organizationId, scope.companyId, scope.branchId, installationId,
            assignmentId, deviceId, storageInstanceId, storageKeyId, pepperKeyId, targetSigningKeyId,
            firstActorId).distinct().size == 12)
    }
    override fun toString(): String = "SelfOwnedAllocationCoordinatesV1([REDACTED])"
}

/** One durable original packet; permission is NATIVE PREPARATION ONLY, never full installation. */
public class SelfOwnedPreparationPacketV1 internal constructor(
    public val coordinates: SelfOwnedAllocationCoordinatesV1,
    public val target: SelfOwnedTargetDescriptorV1,
    public val root: SelfOwnedSignedDocumentV1,
    public val delegation: SelfOwnedSignedDocumentV1,
    public val preparation: SelfOwnedSignedDocumentV1,
) {
    init {
        require(root.role == SelfOwnedDocumentRoleV1.ROOT_ANNOUNCEMENT &&
            delegation.role == SelfOwnedDocumentRoleV1.ISSUER_DELEGATION &&
            preparation.role == SelfOwnedDocumentRoleV1.NATIVE_PREPARATION)
    }
    /** Explicit Android legacy binding for consumers that share the platform-neutral surface. */
    public val installedTarget: SelfOwnedInstalledTargetBindingV1 =
        SelfOwnedInstalledTargetBindingV1.android(target)
    override fun toString(): String = "SelfOwnedPreparationPacketV1([REDACTED])"
}

internal enum class SelfOwnedNativeKeyRoleV1(val wireId: Int, val aliasPart: String) {
    ROOT(1, "root"), ISSUER(2, "issuer"), REGISTRY(3, "registry"), INSTALLER_PEPPER(4, "installer-pepper"),
}

internal enum class SelfOwnedKeyIntentPhaseV1 { NOT_STARTED, STARTED, COMPLETE }
/** Ordinals 0..2 are the original wire values. Generic secure hardware is appended as 3. */
internal enum class SelfOwnedNativeSecurityV1 { SOFTWARE, TRUSTED_ENVIRONMENT, STRONGBOX, HARDWARE_KIND_UNSPECIFIED }

/** Actual original private-directory/lock identity, not a caller-selected path or restore token. */
internal data class SelfOwnedRegistryNamespaceV1(
    val canonicalPath: String,
    val applicationUid: Int,
    val volumeUuid: String,
    val directoryInode: Long,
    val lockInode: Long,
) {
    init {
        require(canonicalPath.length in 1..1024 && canonicalPath.startsWith('/') &&
            SelfOwnedCompositionNames.isProvisionerPath(canonicalPath) && '\u0000' !in canonicalPath &&
            canonicalPath.split('/').none { it == "." || it == ".." })
        selfOwnedUtf16(canonicalPath)
        require(volumeUuid.length == 36 && volumeUuid.withIndex().all { (index, character) ->
            if (index in listOf(8, 13, 18, 23)) character == '-'
            else character in '0'..'9' || character in 'a'..'f'
        })
        require(applicationUid >= 0 && directoryInode > 0 && lockInode > 0)
    }
}

internal data class SelfOwnedNativeKeyIntentV1(
    val role: SelfOwnedNativeKeyRoleV1,
    val keyId: DomainId,
    val phase: SelfOwnedKeyIntentPhaseV1,
    val startedBoot: Int?,
    val security: SelfOwnedNativeSecurityV1?,
    val proof: SelfOwnedOctetsV1,
) {
    init {
        require(startedBoot == null || startedBoot >= 0)
        when (phase) {
            SelfOwnedKeyIntentPhaseV1.NOT_STARTED -> require(startedBoot == null && security == null && proof.size == 0)
            SelfOwnedKeyIntentPhaseV1.STARTED -> require(startedBoot != null && security == null && proof.size == 0)
            SelfOwnedKeyIntentPhaseV1.COMPLETE -> require(startedBoot != null && security != null && proof.size in 1..512)
        }
    }
}

/** OS-private prior custody intent, not signed company/installer authority. Never contains a password. */
internal data class SelfOwnedBootstrapV1(
    val revision: Long,
    val actionId: DomainId,
    val rootId: DomainId,
    val organizationId: DomainId,
    val companyId: DomainId,
    val issuerId: DomainId,
    val installerId: DomainId,
    val facts: SelfOwnedCompanyFactsV1,
    val target: SelfOwnedTargetDescriptorV1,
    val namespace: SelfOwnedRegistryNamespaceV1,
    val nonce: SelfOwnedOctetsV1,
    val keys: List<SelfOwnedNativeKeyIntentV1>,
    val enrollmentStarted: Boolean,
) {
    init {
        require(revision >= 0 && revision < Long.MAX_VALUE && nonce.size == 32)
        require(keys.map { it.role } == SelfOwnedNativeKeyRoleV1.entries.toList())
        require((listOf(actionId, rootId, organizationId, companyId, issuerId, installerId) + keys.map { it.keyId }).distinct().size == 10)
        var unfinished = false
        var started = false
        for (key in keys) {
            if (key.phase == SelfOwnedKeyIntentPhaseV1.COMPLETE) require(!unfinished)
            else {
                if (key.phase == SelfOwnedKeyIntentPhaseV1.STARTED) { require(!unfinished && !started); started = true }
                unfinished = true
            }
        }
        require(!enrollmentStarted || keys.all { it.phase == SelfOwnedKeyIntentPhaseV1.COMPLETE })
        require(namespace.applicationUid == target.applicationUid)
    }
    fun key(role: SelfOwnedNativeKeyRoleV1): SelfOwnedNativeKeyIntentV1 = keys.single { it.role == role }
    fun replaceKey(next: SelfOwnedNativeKeyIntentV1): SelfOwnedBootstrapV1 {
        require(!enrollmentStarted && next.keyId == key(next.role).keyId && revision < Long.MAX_VALUE - 1)
        return copy(revision = revision + 1, keys = keys.map { if (it.role == next.role) next else it })
    }
    fun complete(): Boolean = keys.all { it.phase == SelfOwnedKeyIntentPhaseV1.COMPLETE }
}

internal data class SelfOwnedRootBodyV1(
    val rootId: DomainId, val rootKeyId: DomainId, val organizationId: DomainId, val companyId: DomainId,
    val installerId: DomainId, val companyName: String, val responsiblePersonName: String,
    val installerLogin: String, val rootPublicKey: SelfOwnedOctetsV1,
    val protection: SelfOwnedKeyProtectionV1, val bootstrapActionId: DomainId,
) {
    init { selfOwnedLabel(companyName); selfOwnedLabel(responsiblePersonName); selfOwnedLogin(installerLogin); require(rootPublicKey.size == 65) }
}

internal data class SelfOwnedDelegationBodyV1(
    val rootId: DomainId, val rootDocumentDigest: String,
    val organizationId: DomainId, val companyId: DomainId,
    val issuerId: DomainId, val issuerKeyId: DomainId, val issuerPublicKey: SelfOwnedOctetsV1,
    val installerId: DomainId, val installerRevision: Long, val delegationRevision: Long,
    val protection: SelfOwnedKeyProtectionV1,
) {
    init { selfOwnedHexDigest(rootDocumentDigest); require(issuerPublicKey.size == 65 && installerRevision >= 0 && delegationRevision >= 0) }
}

internal data class SelfOwnedPreparationBodyV1(
    val rootId: DomainId, val rootDocumentDigest: String, val issuerId: DomainId,
    val delegationDigest: String, val installerId: DomainId, val installerRevision: Long,
    val delegationRevision: Long, val coordinates: SelfOwnedAllocationCoordinatesV1,
    val serial: Long, val nonce: SelfOwnedOctetsV1, val target: SelfOwnedTargetDescriptorV1,
    val branchName: String, val protection: SelfOwnedKeyProtectionV1,
) {
    init {
        selfOwnedHexDigest(rootDocumentDigest); selfOwnedHexDigest(delegationDigest); selfOwnedBranchLabel(branchName)
        require(installerRevision >= 0 && delegationRevision >= 0 && serial > 0 && nonce.size == 32)
    }
}
