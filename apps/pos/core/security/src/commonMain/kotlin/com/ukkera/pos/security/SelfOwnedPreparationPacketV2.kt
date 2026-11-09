package com.ukkera.pos.security

/**
 * Additive platform-native original preparation packet. It joins the unchanged platform-neutral
 * V1 root/delegation signed documents with the existing V2 signed installed-identity preparation.
 * It is DATA only: holding these documents never proves native custody, signer trust, installation
 * or writer authority. The Android V1 packet and its exact bytes are a separate contract.
 *
 * Only Windows/macOS target bindings are accepted. iOS remains refused by the binding factory and
 * is never substituted by an Android legacy target.
 */
public class SelfOwnedPreparationPacketV2 internal constructor(
    public val coordinates: SelfOwnedAllocationCoordinatesV1,
    public val installedTarget: SelfOwnedInstalledTargetBindingV1,
    public val root: SelfOwnedSignedDocumentV1,
    public val delegation: SelfOwnedSignedDocumentV1,
    public val preparation: SelfOwnedInstalledIdentitySignedDocumentV2,
) {
    init {
        require(root.role == SelfOwnedDocumentRoleV1.ROOT_ANNOUNCEMENT &&
            delegation.role == SelfOwnedDocumentRoleV1.ISSUER_DELEGATION)
        val nativeTarget = installedTarget as? SelfOwnedNativeInstalledTargetBindingV1
            ?: throw IllegalArgumentException("V2 preparation requires a native Windows/macOS target binding")
        val grant = preparation.preparation
        require(SelfOwnedInstalledIdentityV2.matches(nativeTarget.target, grant.target))
        require(selfOwnedSameCoordinatesV1(coordinates, grant.coordinates))

        val rootDigest = selfOwnedPreparationDocumentDigest(root)
        val delegationDigest = selfOwnedPreparationDocumentDigest(delegation)
        val rootBytes = root.body()
        val rootBody = try { SelfOwnedInstallationWireV1.decodeRoot(rootBytes) } finally { rootBytes.fill(0) }
        val delegationBytes = delegation.body()
        val delegated = try { SelfOwnedInstallationWireV1.decodeDelegation(delegationBytes) }
        finally { delegationBytes.fill(0) }

        require(rootDigest == grant.rootDocumentDigest && delegated.rootDocumentDigest == grant.rootDocumentDigest)
        require(delegationDigest == grant.delegationDigest)
        require(rootBody.rootId == delegated.rootId && delegated.rootId == grant.rootId)
        require(rootBody.organizationId == delegated.organizationId &&
            delegated.organizationId == grant.coordinates.scope.organizationId)
        require(rootBody.companyId == delegated.companyId &&
            delegated.companyId == grant.coordinates.scope.companyId)
        require(rootBody.installerId == delegated.installerId && delegated.installerId == grant.installerId)
        require(delegated.issuerId == grant.issuerId)
        require(delegated.installerRevision == grant.installerRevision &&
            delegated.delegationRevision == grant.delegationRevision)
        require(rootBody.protection == delegated.protection)
    }

    override fun toString(): String = "SelfOwnedPreparationPacketV2([REDACTED])"
}

internal fun selfOwnedPreparationDocumentDigest(document: SelfOwnedSignedDocumentV1): String {
    val bytes = document.copyWireBytes()
    return try { sha256(bytes).value } finally { bytes.fill(0) }
}

internal fun selfOwnedSameCoordinatesV1(
    left: SelfOwnedAllocationCoordinatesV1,
    right: SelfOwnedAllocationCoordinatesV1,
): Boolean = left.allocationId == right.allocationId &&
    left.scope == right.scope &&
    left.installationId == right.installationId &&
    left.assignmentId == right.assignmentId &&
    left.deviceId == right.deviceId &&
    left.storageInstanceId == right.storageInstanceId &&
    left.storageKeyId == right.storageKeyId &&
    left.pepperKeyId == right.pepperKeyId &&
    left.targetSigningKeyId == right.targetSigningKeyId &&
    left.firstActorId == right.firstActorId
