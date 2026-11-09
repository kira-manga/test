package com.ukkera.pos.security

/**
 * Exact retained P1 documents, still DATA until the concrete native owner validates their
 * actual origin, chain and current target. No imported self-signed root becomes an authority.
 */
internal class SelfOwnedNativeSourceV1(
    val root: SelfOwnedSignedDocumentV1,
    val delegation: SelfOwnedSignedDocumentV1,
    val preparation: SelfOwnedSignedDocumentV1,
) {
    val rootBody: SelfOwnedRootBodyV1
    val delegationBody: SelfOwnedDelegationBodyV1
    val preparationBody: SelfOwnedPreparationBodyV1

    init {
        require(root.role == SelfOwnedDocumentRoleV1.ROOT_ANNOUNCEMENT &&
            delegation.role == SelfOwnedDocumentRoleV1.ISSUER_DELEGATION &&
            preparation.role == SelfOwnedDocumentRoleV1.NATIVE_PREPARATION)
        val rootBytes = root.body()
        rootBody = try { SelfOwnedInstallationWireV1.decodeRoot(rootBytes) } finally { rootBytes.fill(0) }
        val delegationBytes = delegation.body()
        delegationBody = try { SelfOwnedInstallationWireV1.decodeDelegation(delegationBytes) }
        finally { delegationBytes.fill(0) }
        val preparationBytes = preparation.body()
        preparationBody = try { SelfOwnedInstallationWireV1.decodePreparation(preparationBytes) }
        finally { preparationBytes.fill(0) }
        val grant = preparationBody; val delegated = delegationBody; val coordinates = grant.coordinates
        require(rootBody.rootId == delegated.rootId && delegated.rootId == grant.rootId &&
            rootBody.organizationId == delegated.organizationId && delegated.organizationId == coordinates.scope.organizationId &&
            rootBody.companyId == delegated.companyId && delegated.companyId == coordinates.scope.companyId &&
            rootBody.installerId == delegated.installerId && delegated.installerId == grant.installerId &&
            delegated.issuerId == grant.issuerId && delegated.rootDocumentDigest == grant.rootDocumentDigest &&
            delegated.installerRevision == grant.installerRevision && delegated.delegationRevision == grant.delegationRevision &&
            rootBody.protection == delegated.protection && delegated.protection == grant.protection)
    }

    fun same(other: SelfOwnedNativeSourceV1): Boolean = sameDocument(root, other.root) &&
        sameDocument(delegation, other.delegation) && sameDocument(preparation, other.preparation)

    private fun sameDocument(left: SelfOwnedSignedDocumentV1, right: SelfOwnedSignedDocumentV1): Boolean {
        val first = left.copyWireBytes(); val second = right.copyWireBytes()
        return try { first.contentEquals(second) } finally { first.fill(0); second.fill(0) }
    }
    override fun toString(): String = "SelfOwnedNativeSourceV1([REDACTED])"
}

/**
 * Invocation-local original PREPARATION approval created only after the actual P1 registry
 * and original keys were read by the SAME native owner. Not a human session, full request
 * approval or a public accepting callback. The owner separately checks this identity/boot/cut.
 */
internal class SelfOwnedNativeApproval(
    val owner: Any,
    val source: SelfOwnedNativeSourceV1,
    val bootCount: Int,
    val expiresElapsedMillis: Long,
) {
    init { require(bootCount >= 0 && expiresElapsedMillis > 0) }
    override fun toString(): String = "SelfOwnedNativeApprovalV1([REDACTED])"
}

/** A fixed native-only signed body. It intentionally contains NO complete branch definition. */
internal data class SelfOwnedNativeBindingBodyV1(
    val coordinates: SelfOwnedAllocationCoordinatesV1,
    val target: SelfOwnedTargetDescriptorV1,
    val rootDocumentDigest: String,
    val delegationDigest: String,
    val preparationDigest: String,
    val completedIntentDigest: String,
    val targetPublicKey: SelfOwnedOctetsV1,
    val protection: SelfOwnedKeyProtectionV1,
    val signingSecurity: SelfOwnedNativeSecurityV1,
    val storageSecurity: SelfOwnedNativeSecurityV1,
    val pepperSecurity: SelfOwnedNativeSecurityV1,
) {
    init {
        selfOwnedHexDigest(rootDocumentDigest); selfOwnedHexDigest(delegationDigest)
        selfOwnedHexDigest(preparationDigest); selfOwnedHexDigest(completedIntentDigest)
        require(targetPublicKey.size == 65)
    }
}

/**
 * Retained original target signature bytes. Parsing/possessing this object alone proves no
 * native custody, Room origin, human approval, complete history or current writer authority.
 */
public class SelfOwnedNativeBindingV1 internal constructor(body: ByteArray, signature: ByteArray) : SelfOwnedNativeOriginalBinding {
    private val bodyValue: SelfOwnedOctetsV1
    private val signatureValue: SelfOwnedOctetsV1
    init {
        require(body.size in 1..SelfOwnedNativeInstallationFormatV1.MAXIMUM_BINDING_BYTES - 512)
        require(signature.size == 64)
        bodyValue = SelfOwnedOctetsV1(body)
        signatureValue = SelfOwnedOctetsV1(signature)
    }
    override fun copyWireBytes(): ByteArray = SelfOwnedNativeInstallationWireV1.encodeBinding(this)
    internal fun body(): ByteArray = bodyValue.copyBytes()
    internal fun signature(): ByteArray = signatureValue.copyBytes()
    override fun toString(): String = "SelfOwnedNativeBindingV1([REDACTED])"
}
