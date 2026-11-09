package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId

/**
 * Additive Windows/macOS original-target profile. It does not reinterpret any Android V1 native
 * byte, AAD or domain, and it does not change history formats 2-6. The embedded root/delegation
 * bytes remain the unchanged platform-neutral V1 signed documents; the preparation remains the
 * frozen installed-identity V2 signed document. Everything here is DATA until the concrete native
 * owner reads and authenticates its actual OS custody and keys.
 */
internal object SelfOwnedNativeIdentityProfileV2 {
    const val ID: String = "ukkera.installation/self-owned-native-target/v2"
    const val INTENT_DOMAIN: String = "native-target-original-intent/v2"
    const val KEY_DOMAIN: String = "native-target-original-key/v2"
    const val BINDING_BODY_DOMAIN: String = "native-target-binding-body/v2"
    const val BINDING_PROOF_DOMAIN: String = "native-target-binding-proof/v2"
    const val BINDING_DOMAIN: String = "native-target-binding-signed/v2"
    const val AAD_DOMAIN: String = "native-target-journal-aad/v2"
    const val SIGNATURE_DOMAIN: String = "native-target-journal-signature/v2"
    const val ENVELOPE_DOMAIN: String = "native-target-journal-envelope/v2"
    const val STATE_DOMAIN: String = "native-target-journal-state/v2"
    const val FORMAT_VERSION: Int = 2
    const val NATIVE_ONLY_PURPOSE: String =
        "ORIGINAL_NATIVE_TARGET_ONLY_NO_ROOM_HISTORY_OR_WRITER_AUTHORITY_V2"
    const val MAXIMUM_INTENT_BYTES: Int = 4 * 65_536
    const val MAXIMUM_BINDING_BODY_BYTES: Int = 128 * 1024
    const val MAXIMUM_BINDING_BYTES: Int = MAXIMUM_BINDING_BODY_BYTES + 4_096
    const val MAXIMUM_STATE_BYTES: Int = 256 * 1024
    const val MAXIMUM_STATE_PLAINTEXT_BYTES: Int = MAXIMUM_STATE_BYTES - 1_024
    const val MAXIMUM_CONTROL_FRAME_BYTES: Int = MAXIMUM_INTENT_BYTES + 8_192
    const val MAXIMUM_KEY_PROOF_BYTES: Int = 512
    const val MAXIMUM_PATH_BYTES: Int = 1_024
    const val NONCE_BYTES: Int = 32
    const val SIGNATURE_BYTES: Int = 64
    const val MAXIMUM_INSTALLED_TARGET_BYTES: Int =
        SelfOwnedInstalledTargetBindingWireV1.MAXIMUM_BINDING_BYTES
}

/** Closed native platform discriminator for this profile. Android and iOS are not accepted. */
internal enum class SelfOwnedNativePlatformV2(val wireId: Int) {
    WINDOWS(1), MACOS(2);

    companion object {
        fun fromWireId(value: Int): SelfOwnedNativePlatformV2? =
            entries.singleOrNull { it.wireId == value }
    }
}

/**
 * Exact original target-directory/namespace identity. Windows carries the actual owner SID,
 * volume identity and 128-bit file IDs; macOS carries the actual uid, volume UUID and inodes.
 * There is deliberately no Android UID/AndroidId/APK-signer fallback and no caller-selected
 * default. Constructing this value proves no custody by itself.
 */
internal sealed interface SelfOwnedNativeNamespaceV2 {
    val platform: SelfOwnedNativePlatformV2
    val canonicalPath: String

    data class Windows(
        override val canonicalPath: String,
        val ownerSid: String,
        val volumeId: String,
        val directoryFileId: String,
        val lockFileId: String,
        val databaseDirectoryFileId: String,
    ) : SelfOwnedNativeNamespaceV2 {
        override val platform: SelfOwnedNativePlatformV2 get() = SelfOwnedNativePlatformV2.WINDOWS

        init {
            require(selfOwnedWindowsTargetPath(canonicalPath))
            require(SelfOwnedInstalledIdentityGrammarV2.accountSid(ownerSid))
            require(SelfOwnedInstalledIdentityGrammarV2.canonicalUuid(volumeId))
            require(selfOwnedWindowsFileId(directoryFileId))
            require(selfOwnedWindowsFileId(lockFileId))
            require(selfOwnedWindowsFileId(databaseDirectoryFileId))
            require(setOf(directoryFileId, lockFileId, databaseDirectoryFileId).size == 3)
        }

        override fun toString(): String = "SelfOwnedNativeNamespaceV2.Windows([REDACTED])"
    }

    data class Mac(
        override val canonicalPath: String,
        val uid: Int,
        val volumeUuid: String,
        val directoryInode: Long,
        val lockInode: Long,
        val databaseDirectoryInode: Long,
    ) : SelfOwnedNativeNamespaceV2 {
        override val platform: SelfOwnedNativePlatformV2 get() = SelfOwnedNativePlatformV2.MACOS

        init {
            require(selfOwnedPosixTargetPath(canonicalPath))
            require(uid >= 0)
            require(SelfOwnedInstalledIdentityGrammarV2.canonicalUuid(volumeUuid))
            require(directoryInode > 0 && lockInode > 0 && databaseDirectoryInode > 0)
            require(setOf(directoryInode, lockInode, databaseDirectoryInode).size == 3)
        }

        override fun toString(): String = "SelfOwnedNativeNamespaceV2.Mac([REDACTED])"
    }
}

/**
 * Full, non-truncated OS boot-session identity. Windows uses the boot environment GUID and macOS
 * uses the kernel boot-session UUID. An integer boot counter is never substituted or fabricated.
 */
internal sealed interface SelfOwnedNativeBootSessionV2 {
    val platform: SelfOwnedNativePlatformV2
    val bootSessionId: String

    data class Windows(val bootId: String) : SelfOwnedNativeBootSessionV2 {
        override val platform: SelfOwnedNativePlatformV2 get() = SelfOwnedNativePlatformV2.WINDOWS
        override val bootSessionId: String get() = bootId
        init { require(SelfOwnedInstalledIdentityGrammarV2.canonicalUuid(bootId)) }
        override fun toString(): String = "SelfOwnedNativeBootSessionV2.Windows([REDACTED])"
    }

    data class Mac(val bootSessionUuid: String) : SelfOwnedNativeBootSessionV2 {
        override val platform: SelfOwnedNativePlatformV2 get() = SelfOwnedNativePlatformV2.MACOS
        override val bootSessionId: String get() = bootSessionUuid
        init { require(SelfOwnedInstalledIdentityGrammarV2.canonicalUuid(bootSessionUuid)) }
        override fun toString(): String = "SelfOwnedNativeBootSessionV2.Mac([REDACTED])"
    }
}

/**
 * One original target-key intent. Role IDs and key IDs are exactly the P1 allocation coordinates.
 * The proof is opaque bounded DATA interpreted only by the concrete platform key primitive; it is
 * not an Android-length assumption. [security] is the actually established native key policy.
 */
internal data class SelfOwnedTargetKeyIntentV2(
    val role: SelfOwnedTargetKeyRoleV1,
    val keyId: DomainId,
    val phase: SelfOwnedKeyIntentPhaseV1,
    val startedBoot: SelfOwnedNativeBootSessionV2?,
    val security: SelfOwnedNativeSecurityV1?,
    val proof: SelfOwnedOctetsV1,
) {
    init {
        when (phase) {
            SelfOwnedKeyIntentPhaseV1.NOT_STARTED ->
                require(startedBoot == null && security == null && proof.size == 0)
            SelfOwnedKeyIntentPhaseV1.STARTED ->
                require(startedBoot != null && security == null && proof.size == 0)
            SelfOwnedKeyIntentPhaseV1.COMPLETE -> require(
                startedBoot != null && security != null &&
                    proof.size in 1..SelfOwnedNativeIdentityProfileV2.MAXIMUM_KEY_PROOF_BYTES,
            )
        }
    }
    override fun toString(): String = "SelfOwnedTargetKeyIntentV2([REDACTED])"
}

/**
 * Retained original V2 source: the unchanged V1 root/delegation signed documents plus the frozen
 * V2 installed-identity signed preparation and its explicit native installed target. It neither
 * verifies signatures nor proves P1 registry origin; the concrete owner must do both.
 */
internal class SelfOwnedNativeSourceV2 internal constructor(
    val packet: SelfOwnedPreparationPacketV2,
) {
    val installedTarget: SelfOwnedNativeInstalledTargetBindingV1
    val platform: SelfOwnedNativePlatformV2
    val coordinates: SelfOwnedAllocationCoordinatesV1
    val root: SelfOwnedSignedDocumentV1
    val delegation: SelfOwnedSignedDocumentV1
    val preparation: SelfOwnedInstalledIdentitySignedDocumentV2
    val rootBody: SelfOwnedRootBodyV1
    val delegationBody: SelfOwnedDelegationBodyV1
    val preparationBody: SelfOwnedInstalledIdentityPreparationV2
    val rootDocumentDigest: String
    val delegationDigest: String
    val preparationDigest: String
    val protection: SelfOwnedKeyProtectionV1

    init {
        require(packet.root.role == SelfOwnedDocumentRoleV1.ROOT_ANNOUNCEMENT &&
            packet.delegation.role == SelfOwnedDocumentRoleV1.ISSUER_DELEGATION)
        installedTarget = packet.installedTarget as? SelfOwnedNativeInstalledTargetBindingV1
            ?: throw IllegalArgumentException("V2 native source requires a Windows/macOS target")
        platform = when (installedTarget.platform) {
            SelfOwnedInstalledTargetPlatformV1.WINDOWS -> SelfOwnedNativePlatformV2.WINDOWS
            SelfOwnedInstalledTargetPlatformV1.MACOS -> SelfOwnedNativePlatformV2.MACOS
            SelfOwnedInstalledTargetPlatformV1.ANDROID ->
                throw IllegalArgumentException("Android is not a V2 native target")
        }
        coordinates = packet.coordinates
        root = packet.root
        delegation = packet.delegation
        preparation = packet.preparation
        preparationBody = preparation.preparation
        require(SelfOwnedInstalledIdentityV2.matches(installedTarget.target, preparationBody.target))
        require(selfOwnedSameCoordinatesV1(coordinates, preparationBody.coordinates))

        val rootBytes = root.body()
        rootBody = try { SelfOwnedInstallationWireV1.decodeRoot(rootBytes) } finally { rootBytes.fill(0) }
        val delegationBytes = delegation.body()
        delegationBody = try { SelfOwnedInstallationWireV1.decodeDelegation(delegationBytes) }
        finally { delegationBytes.fill(0) }

        protection = rootBody.protection
        require(rootBody.protection == delegationBody.protection)
        rootDocumentDigest = selfOwnedPreparationDocumentDigest(root)
        delegationDigest = selfOwnedPreparationDocumentDigest(delegation)
        val preparationBytes = preparation.copyWireBytes()
        preparationDigest = try { sha256(preparationBytes).value } finally { preparationBytes.fill(0) }
        require(rootDocumentDigest == preparationBody.rootDocumentDigest)
        require(delegationDigest == preparationBody.delegationDigest)
        require(delegationBody.rootDocumentDigest == rootDocumentDigest)
        require(rootBody.rootId == delegationBody.rootId && delegationBody.rootId == preparationBody.rootId)
        require(rootBody.organizationId == delegationBody.organizationId &&
            delegationBody.organizationId == coordinates.scope.organizationId)
        require(rootBody.companyId == delegationBody.companyId &&
            delegationBody.companyId == coordinates.scope.companyId)
        require(rootBody.installerId == delegationBody.installerId &&
            delegationBody.installerId == preparationBody.installerId)
        require(delegationBody.issuerId == preparationBody.issuerId &&
            delegationBody.installerRevision == preparationBody.installerRevision &&
            delegationBody.delegationRevision == preparationBody.delegationRevision)
    }

    fun same(other: SelfOwnedNativeSourceV2): Boolean =
        sameDocument(root, other.root) && sameDocument(delegation, other.delegation) &&
            sameDocumentV2(preparation, other.preparation) &&
            sameInstalledTarget(installedTarget, other.installedTarget)

    private fun sameDocument(left: SelfOwnedSignedDocumentV1, right: SelfOwnedSignedDocumentV1): Boolean {
        val first = left.copyWireBytes(); val second = right.copyWireBytes()
        return try { first.contentEquals(second) } finally { first.fill(0); second.fill(0) }
    }

    private fun sameDocumentV2(
        left: SelfOwnedInstalledIdentitySignedDocumentV2,
        right: SelfOwnedInstalledIdentitySignedDocumentV2,
    ): Boolean {
        val first = left.copyWireBytes(); val second = right.copyWireBytes()
        return try { first.contentEquals(second) } finally { first.fill(0); second.fill(0) }
    }

    private fun sameInstalledTarget(
        left: SelfOwnedNativeInstalledTargetBindingV1,
        right: SelfOwnedNativeInstalledTargetBindingV1,
    ): Boolean {
        val first = left.copyWireBytes(); val second = right.copyWireBytes()
        return try { first.contentEquals(second) } finally { first.fill(0); second.fill(0) }
    }

    override fun toString(): String = "SelfOwnedNativeSourceV2([REDACTED])"
}

/**
 * Invocation-local original PREPARATION approval created only after the actual native P1 registry
 * and original keys were read by the SAME concrete owner. [owner] is compared by identity and
 * [bootSession] is compared with the actual current OS boot session. This is not a human session,
 * full request approval, transferable token or public accepting callback.
 */
internal class SelfOwnedNativeApprovalV2(
    val owner: Any,
    val source: SelfOwnedNativeSourceV2,
    val bootSession: SelfOwnedNativeBootSessionV2,
    val expiresElapsedMillis: Long,
) {
    init {
        require(expiresElapsedMillis > 0)
        require(bootSession.platform == source.platform)
    }
    override fun toString(): String = "SelfOwnedNativeApprovalV2([REDACTED])"
}

/**
 * OS-private prior intent for the Windows/macOS target. It retains the exact source, actual
 * namespace, per-key boot sessions/policies/proofs and the full key progression. It is not
 * company, installer or Room authority before the concrete owner validates actual native state.
 */
internal data class SelfOwnedNativeIntentV2(
    val revision: Long,
    val source: SelfOwnedNativeSourceV2,
    val namespace: SelfOwnedNativeNamespaceV2,
    val nonce: SelfOwnedOctetsV1,
    val keys: List<SelfOwnedTargetKeyIntentV2>,
    val bindingStarted: Boolean,
) {
    init {
        require(revision in 0 until Long.MAX_VALUE && nonce.size == SelfOwnedNativeIdentityProfileV2.NONCE_BYTES)
        require(namespace.platform == source.platform)
        require(keys.map { it.role } == SelfOwnedTargetKeyRoleV1.entries.toList())
        require(keys.all { it.startedBoot == null || it.startedBoot.platform == source.platform })
        val coordinates = source.coordinates
        require(keys.map { it.keyId } ==
            listOf(coordinates.targetSigningKeyId, coordinates.storageKeyId, coordinates.pepperKeyId))
        var unfinished = false
        for (key in keys) {
            when (key.phase) {
                SelfOwnedKeyIntentPhaseV1.COMPLETE -> require(!unfinished)
                SelfOwnedKeyIntentPhaseV1.STARTED -> { require(!unfinished); unfinished = true }
                SelfOwnedKeyIntentPhaseV1.NOT_STARTED -> unfinished = true
            }
        }
        require(!bindingStarted || complete())
    }

    fun complete(): Boolean = keys.all { it.phase == SelfOwnedKeyIntentPhaseV1.COMPLETE }
    fun key(role: SelfOwnedTargetKeyRoleV1): SelfOwnedTargetKeyIntentV2 = keys.single { it.role == role }

    fun replaceKey(next: SelfOwnedTargetKeyIntentV2): SelfOwnedNativeIntentV2 {
        require(!bindingStarted && revision < Long.MAX_VALUE - 1 && key(next.role).keyId == next.keyId)
        return copy(revision = revision + 1L, keys = keys.map { if (it.role == next.role) next else it })
    }
    override fun toString(): String = "SelfOwnedNativeIntentV2([REDACTED])"
}

/**
 * A fixed native-only signed body. It binds the exact installed target, namespace, binding boot
 * session, source document digests, coordinates, key policies/proofs and the SHA-256 of the full
 * completed intent. It intentionally contains NO complete branch definition or Room authority.
 */
internal data class SelfOwnedNativeBindingBodyV2(
    val coordinates: SelfOwnedAllocationCoordinatesV1,
    val installedTarget: SelfOwnedNativeInstalledTargetBindingV1,
    val namespace: SelfOwnedNativeNamespaceV2,
    val bootSession: SelfOwnedNativeBootSessionV2,
    val rootDocumentDigest: String,
    val delegationDigest: String,
    val preparationDigest: String,
    val completedIntentDigest: String,
    val targetPublicKey: SelfOwnedOctetsV1,
    val protection: SelfOwnedKeyProtectionV1,
    val keys: List<SelfOwnedTargetKeyIntentV2>,
) {
    init {
        selfOwnedHexDigest(rootDocumentDigest); selfOwnedHexDigest(delegationDigest)
        selfOwnedHexDigest(preparationDigest); selfOwnedHexDigest(completedIntentDigest)
        require(targetPublicKey.size == 65)
        val platform = when (installedTarget.platform) {
            SelfOwnedInstalledTargetPlatformV1.WINDOWS -> SelfOwnedNativePlatformV2.WINDOWS
            SelfOwnedInstalledTargetPlatformV1.MACOS -> SelfOwnedNativePlatformV2.MACOS
            SelfOwnedInstalledTargetPlatformV1.ANDROID ->
                throw IllegalArgumentException("Android is not a V2 native target")
        }
        require(namespace.platform == platform && bootSession.platform == platform)
        require(keys.map { it.role } == SelfOwnedTargetKeyRoleV1.entries.toList())
        require(keys.all { it.phase == SelfOwnedKeyIntentPhaseV1.COMPLETE })
        require(keys.all { it.startedBoot?.platform == platform })
        require(keys.map { it.keyId } ==
            listOf(coordinates.targetSigningKeyId, coordinates.storageKeyId, coordinates.pepperKeyId))
    }

    val purpose: String get() = SelfOwnedNativeIdentityProfileV2.NATIVE_ONLY_PURPOSE
    val target: SelfOwnedInstalledIdentityTargetV2 get() = installedTarget.target
    val signingSecurity: SelfOwnedNativeSecurityV1
        get() = requireNotNull(key(SelfOwnedTargetKeyRoleV1.TARGET_SIGNING).security)
    val storageSecurity: SelfOwnedNativeSecurityV1
        get() = requireNotNull(key(SelfOwnedTargetKeyRoleV1.STORAGE).security)
    val pepperSecurity: SelfOwnedNativeSecurityV1
        get() = requireNotNull(key(SelfOwnedTargetKeyRoleV1.PEPPER).security)
    fun key(role: SelfOwnedTargetKeyRoleV1): SelfOwnedTargetKeyIntentV2 = keys.single { it.role == role }
    override fun toString(): String = "SelfOwnedNativeBindingBodyV2([REDACTED])"
}

/**
 * Retained original V2 target signature bytes. Parsing or possessing this object alone proves no
 * native custody, registry origin, human approval, complete history or current writer authority.
 */
public class SelfOwnedNativeBindingV2 internal constructor(body: ByteArray, signature: ByteArray) : SelfOwnedNativeOriginalBinding {
    private val bodyValue: SelfOwnedOctetsV1
    private val signatureValue: SelfOwnedOctetsV1

    init {
        require(body.size in 1..SelfOwnedNativeIdentityProfileV2.MAXIMUM_BINDING_BYTES - 512)
        require(signature.size == SelfOwnedNativeIdentityProfileV2.SIGNATURE_BYTES)
        bodyValue = SelfOwnedOctetsV1(body, SelfOwnedNativeIdentityProfileV2.MAXIMUM_BINDING_BYTES)
        signatureValue = SelfOwnedOctetsV1(signature, SelfOwnedNativeIdentityProfileV2.SIGNATURE_BYTES)
    }

    override fun copyWireBytes(): ByteArray = SelfOwnedNativeInstallationWireV2.encodeBinding(this)
    /** SHA-256 of the exact retained binding wire bytes; syntax only, never custody evidence. */
    public fun wireDigest(): PayloadDigest {
        val bytes = copyWireBytes()
        return try { sha256(bytes) } finally { bytes.fill(0) }
    }
    internal fun body(): ByteArray = bodyValue.copyBytes()
    internal fun signature(): ByteArray = signatureValue.copyBytes()
    override fun toString(): String = "SelfOwnedNativeBindingV2([REDACTED])"
}

/** Additive retained V2 read result. It is DATA, not custody, history or writer authority. */
public sealed interface SelfOwnedNativeReadResultV2 {
    public class Retained(
        public val material: SelfOwnedNativeMaterialView,
        public val binding: SelfOwnedNativeBindingV2,
    ) : SelfOwnedNativeReadResultV2 {
        init {
            require(material.installedTarget is SelfOwnedNativeInstalledTargetBindingV1) {
                "V2 native binding requires a Windows/macOS installed target"
            }
        }
        public val installedTarget: SelfOwnedInstalledTargetBindingV1 get() = material.installedTarget
    }
    public data class Unresolved(public val reason: SelfOwnedInstallationFailureV1) :
        SelfOwnedNativeReadResultV2
}

internal data class SelfOwnedNativeJournalStateV2(
    val revision: Long,
    val originalBinding: SelfOwnedNativeBindingV2,
    val newCeremoniesAllowed: Boolean,
) {
    init { require(revision in 0 until Long.MAX_VALUE) }

    fun withdrawNewCeremonies(): SelfOwnedNativeJournalStateV2 {
        if (revision == Long.MAX_VALUE - 1) selfOwnedFail(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
        return copy(revision = revision + 1L, newCeremoniesAllowed = false)
    }
    override fun toString(): String = "SelfOwnedNativeJournalStateV2([REDACTED])"
}

/** Owned transient output, no key bytes and no claim that a ciphertext is committed state. */
internal class SelfOwnedNativeJournalEnvelopeV2(
    val nonce: ByteArray,
    val ciphertext: ByteArray,
    val signature: ByteArray,
) {
    fun clear() { nonce.fill(0); ciphertext.fill(0); signature.fill(0) }
    override fun toString(): String = "SelfOwnedNativeJournalEnvelopeV2([REDACTED])"
}

private fun selfOwnedWindowsFileId(value: String): Boolean =
    value.length == 32 && value.all { it in '0'..'9' || it in 'a'..'f' }

private fun selfOwnedWindowsTargetPath(value: String): Boolean {
    if (value.length !in 3..SelfOwnedNativeIdentityProfileV2.MAXIMUM_PATH_BYTES) return false
    if (value[0] !in 'A'..'Z' && value[0] !in 'a'..'z') return false
    if (value[1] != ':' || value[2] != '\\') return false
    if ('\u0000' in value || '/' in value || value.endsWith("\\") || "\\\\" in value) return false
    selfOwnedUtf16(value)
    val parts = value.split('\\')
    if (parts.size < 2 || parts.any { it.isEmpty() || it == "." || it == ".." }) return false
    val names = buildList {
        add(SelfOwnedCompositionNames.LIVE_TARGET_DIRECTORY)
        LocalTrainingCompositionSlot.entries.forEach { add(SelfOwnedCompositionNames.trainingTargetDirectory(it)) }
    }
    return names.any { value.endsWith("\\$it") }
}

private fun selfOwnedPosixTargetPath(value: String): Boolean {
    if (value.length !in 1..SelfOwnedNativeIdentityProfileV2.MAXIMUM_PATH_BYTES) return false
    if (!value.startsWith('/') || '\u0000' in value) return false
    selfOwnedUtf16(value)
    if (value.split('/').any { it == "." || it == ".." }) return false
    return SelfOwnedCompositionNames.isTargetPath(value)
}
