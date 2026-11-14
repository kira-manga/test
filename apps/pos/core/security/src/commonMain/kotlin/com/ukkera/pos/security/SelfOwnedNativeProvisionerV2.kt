package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId

/**
 * Additive Windows/macOS original PROVISIONER profile. Every domain is new and disjoint from the
 * Android V1 bootstrap/registry/company domains and from the native-target V2 domains. Nothing
 * here reinterprets an Android V1 byte, AAD or key; every value is DATA until the concrete native
 * owner reads and authenticates its actual OS custody and keys.
 */
internal object SelfOwnedNativeOriginalProfileV2 {
    const val ID: String = "ukkera.installation/self-owned-native-original/v2"
    const val NAMESPACE_DOMAIN: String = "native-provisioner-namespace/v2"
    const val BOOTSTRAP_DOMAIN: String = "native-provisioner-bootstrap/v2"
    const val REGISTRY_STATE_DOMAIN: String = "native-provisioner-registry-state/v2"
    const val COMPANY_BOOTSTRAP_DOMAIN: String = "native-company-authority-bootstrap/v2"
    const val BOOT_ORDINAL_LEDGER_DOMAIN: String = "native-boot-ordinal-ledger/v2"
    const val FORMAT_VERSION: Int = 2
    const val REGISTRY_PURPOSE: String = "ORIGINAL_NATIVE_PROVISIONER_REGISTRY_V2"
    const val MAXIMUM_NAMESPACE_BYTES: Int = 4_096
    const val MAXIMUM_BOOTSTRAP_BYTES: Int = SelfOwnedInstalledTargetBindingWireV1.MAXIMUM_BINDING_BYTES + 16_384
    const val MAXIMUM_COMPANY_BOOTSTRAP_BYTES: Int = MAXIMUM_BOOTSTRAP_BYTES + 8_192
    const val MAXIMUM_REGISTRY_PLAINTEXT_BYTES: Int = SelfOwnedInstallationProfileV1.MAXIMUM_REGISTRY_PLAINTEXT_BYTES
    const val MAXIMUM_LEDGER_BYTES: Int = 512
    const val MAXIMUM_KEY_PROOF_BYTES: Int = 512
    const val MAXIMUM_PATH_BYTES: Int = 1_024
    const val NONCE_BYTES: Int = 32
    const val REGISTRY_AAD_DOMAIN: String = "native-provisioner-registry-aad/v2"
    const val REGISTRY_SIGNATURE_DOMAIN: String = "native-provisioner-registry-signature/v2"
    const val REGISTRY_ENVELOPE_DOMAIN: String = "native-provisioner-registry-envelope/v2"
    const val COMPANY_REGISTRY_AAD_DOMAIN: String = "native-company-authority-registry-aad/v2"
    const val MAXIMUM_REGISTRY_ENVELOPE_BYTES: Int = MAXIMUM_REGISTRY_PLAINTEXT_BYTES + 1_024
    const val MAXIMUM_REGISTRY_SIGNATURE_MESSAGE_BYTES: Int = MAXIMUM_REGISTRY_ENVELOPE_BYTES + 65_536
    const val REGISTRY_NONCE_BYTES: Int = 12
    const val REGISTRY_SIGNATURE_BYTES: Int = 64
}

/**
 * Owned transient Windows/macOS registry envelope: AES-GCM nonce, ciphertext (with tag) and the
 * 64-byte registry-key signature. Holding it proves no key custody or registry origin.
 */
internal class SelfOwnedRegistryEnvelopeV2(val nonce: ByteArray, val ciphertext: ByteArray, val signature: ByteArray) {
    init {
        require(nonce.size == SelfOwnedNativeOriginalProfileV2.REGISTRY_NONCE_BYTES && ciphertext.size >= 16 &&
            signature.size == SelfOwnedNativeOriginalProfileV2.REGISTRY_SIGNATURE_BYTES)
    }
    fun clear() { nonce.fill(0); ciphertext.fill(0); signature.fill(0) }
    override fun toString(): String = "SelfOwnedRegistryEnvelopeV2([REDACTED])"
}

/**
 * Exact original provisioner private-directory/lock identity. Windows carries the actual owner SID,
 * volume GUID and 128-bit file IDs; macOS carries the actual uid, volume UUID and inodes. There is
 * no Android UID fallback and no caller-selected default. Constructing it proves no custody.
 */
internal sealed interface SelfOwnedRegistryNamespaceV2 {
    val platform: SelfOwnedNativePlatformV2
    val canonicalPath: String

    data class Windows(
        override val canonicalPath: String,
        val ownerSid: String,
        val volumeGuid: String,
        val directoryFileId: String,
        val lockFileId: String,
    ) : SelfOwnedRegistryNamespaceV2 {
        override val platform: SelfOwnedNativePlatformV2 get() = SelfOwnedNativePlatformV2.WINDOWS

        init {
            require(selfOwnedWindowsProvisionerPath(canonicalPath))
            require(SelfOwnedInstalledIdentityGrammarV2.accountSid(ownerSid))
            require(SelfOwnedInstalledIdentityGrammarV2.canonicalUuid(volumeGuid))
            require(selfOwnedFileId128(directoryFileId) && selfOwnedFileId128(lockFileId))
            require(directoryFileId != lockFileId)
        }

        override fun toString(): String = "SelfOwnedRegistryNamespaceV2.Windows([REDACTED])"
    }

    data class Mac(
        override val canonicalPath: String,
        val uid: Int,
        val volumeUuid: String,
        val directoryInode: Long,
        val lockInode: Long,
    ) : SelfOwnedRegistryNamespaceV2 {
        override val platform: SelfOwnedNativePlatformV2 get() = SelfOwnedNativePlatformV2.MACOS

        init {
            require(selfOwnedPosixProvisionerPath(canonicalPath))
            require(uid >= 0)
            require(SelfOwnedInstalledIdentityGrammarV2.canonicalUuid(volumeUuid))
            require(directoryInode > 0 && lockInode > 0 && directoryInode != lockInode)
        }

        override fun toString(): String = "SelfOwnedRegistryNamespaceV2.Mac([REDACTED])"
    }
}

/** One original provisioner key intent; [startedBoot] is the full OS boot session, never a count. */
internal data class SelfOwnedProvisionerKeyIntentV2(
    val role: SelfOwnedNativeKeyRoleV1,
    val keyId: DomainId,
    val phase: SelfOwnedKeyIntentPhaseV1,
    val startedBoot: SelfOwnedNativeBootSessionV2?,
    val security: SelfOwnedNativeSecurityV1?,
    val proof: SelfOwnedOctetsV1,
) {
    init {
        when (phase) {
            SelfOwnedKeyIntentPhaseV1.NOT_STARTED -> require(startedBoot == null && security == null && proof.size == 0)
            SelfOwnedKeyIntentPhaseV1.STARTED -> require(startedBoot != null && security == null && proof.size == 0)
            SelfOwnedKeyIntentPhaseV1.COMPLETE -> require(startedBoot != null && security != null &&
                proof.size in 1..SelfOwnedNativeOriginalProfileV2.MAXIMUM_KEY_PROOF_BYTES)
        }
    }
    override fun toString(): String = "SelfOwnedProvisionerKeyIntentV2([REDACTED])"
}

/**
 * OS-private prior provisioner custody intent for Windows/macOS. Same fields and progression rules
 * as [SelfOwnedBootstrapV1], with the explicit native installed target, the V2 registry namespace and
 * boot-session key intents. Never contains a password; not signed company/installer authority.
 */
internal data class SelfOwnedBootstrapV2(
    val revision: Long,
    val actionId: DomainId,
    val rootId: DomainId,
    val organizationId: DomainId,
    val companyId: DomainId,
    val issuerId: DomainId,
    val installerId: DomainId,
    val facts: SelfOwnedCompanyFactsV1,
    val installedTarget: SelfOwnedNativeInstalledTargetBindingV1,
    val namespace: SelfOwnedRegistryNamespaceV2,
    val nonce: SelfOwnedOctetsV1,
    val keys: List<SelfOwnedProvisionerKeyIntentV2>,
    val enrollmentStarted: Boolean,
) {
    val platform: SelfOwnedNativePlatformV2 = selfOwnedNativePlatformOf(installedTarget)

    init {
        require(revision >= 0 && revision < Long.MAX_VALUE && nonce.size == SelfOwnedNativeOriginalProfileV2.NONCE_BYTES)
        require(keys.map { it.role } == SelfOwnedNativeKeyRoleV1.entries.toList())
        require((listOf(actionId, rootId, organizationId, companyId, issuerId, installerId) + keys.map { it.keyId })
            .distinct().size == 10)
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
        require(namespace.platform == platform)
        require(keys.all { it.startedBoot == null || it.startedBoot.platform == platform })
    }

    fun key(role: SelfOwnedNativeKeyRoleV1): SelfOwnedProvisionerKeyIntentV2 = keys.single { it.role == role }
    fun replaceKey(next: SelfOwnedProvisionerKeyIntentV2): SelfOwnedBootstrapV2 {
        require(!enrollmentStarted && next.keyId == key(next.role).keyId && revision < Long.MAX_VALUE - 1)
        return copy(revision = revision + 1, keys = keys.map { if (it.role == next.role) next else it })
    }
    fun complete(): Boolean = keys.all { it.phase == SelfOwnedKeyIntentPhaseV1.COMPLETE }
    override fun toString(): String = "SelfOwnedBootstrapV2([REDACTED])"
}

/**
 * One V2 allocation: the existing installed-identity V2 preparation and, once retained, its exact
 * V2 signed grant. Windows/macOS targets only; the iOS active-app identity is not a native key packet.
 */
internal data class LocalProvisionerAllocationV2(
    val body: SelfOwnedInstalledIdentityPreparationV2,
    val phase: LocalProvisionerAllocationPhaseV1,
    val grant: SelfOwnedInstalledIdentitySignedDocumentV2?,
) {
    init {
        require((phase == LocalProvisionerAllocationPhaseV1.GRANT_RETAINED) == (grant != null))
        require(body.target.kind.platform != SelfOwnedInstalledIdentityPlatformV2.IOS)
        if (grant != null) {
            val expected = body.copyWireBytes(); val actual = grant.preparation.copyWireBytes()
            try { require(expected.contentEquals(actual)) } finally { expected.fill(0); actual.fill(0) }
        }
    }
    override fun toString(): String = "LocalProvisionerAllocationV2([REDACTED])"
}

/** Desktop provisioner registry state; installer records reuse the unchanged V1 record shape. */
internal data class LocalProvisionerRegistryStateV2(
    val revision: Long,
    val root: SelfOwnedSignedDocumentV1,
    val delegation: SelfOwnedSignedDocumentV1,
    val issuanceEnabled: Boolean,
    val nextSerial: Long,
    val installers: List<LocalProvisionerInstallerV1>,
    val allocations: List<LocalProvisionerAllocationV2>,
) {
    init {
        require(revision in 0 until Long.MAX_VALUE && nextSerial in 1 until Long.MAX_VALUE)
        require(root.role == SelfOwnedDocumentRoleV1.ROOT_ANNOUNCEMENT && delegation.role == SelfOwnedDocumentRoleV1.ISSUER_DELEGATION)
        require(installers.size in 1..SelfOwnedInstallationProfileV1.MAXIMUM_INSTALLERS)
        require(allocations.size <= SelfOwnedInstallationProfileV1.MAXIMUM_ALLOCATIONS)
        require(installers.map { it.installerId }.distinct().size == installers.size &&
            installers.map { it.login }.distinct().size == installers.size)
        require(allocations.map { it.body.coordinates.allocationId }.distinct().size == allocations.size)
        require(allocations.map { it.body.coordinates.scope }.distinct().size == allocations.size)
        require(allocations.map { it.body.serial }.distinct().size == allocations.size &&
            allocations.all { it.body.serial < nextSerial })
    }

    fun next(
        issuanceEnabled: Boolean = this.issuanceEnabled,
        nextSerial: Long = this.nextSerial,
        installers: List<LocalProvisionerInstallerV1> = this.installers,
        allocations: List<LocalProvisionerAllocationV2> = this.allocations,
    ): LocalProvisionerRegistryStateV2 {
        if (revision == Long.MAX_VALUE - 1) selfOwnedFail(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
        return copy(revision = revision + 1, issuanceEnabled = issuanceEnabled, nextSerial = nextSerial,
            installers = installers.toList(), allocations = allocations.toList())
    }

    fun replaceInstaller(record: LocalProvisionerInstallerV1): LocalProvisionerRegistryStateV2 {
        require(installers.count { it.installerId == record.installerId } == 1)
        return next(installers = installers.map { if (it.installerId == record.installerId) record else it })
    }

    fun replaceAllocation(record: LocalProvisionerAllocationV2): LocalProvisionerRegistryStateV2 {
        val id = record.body.coordinates.allocationId
        require(allocations.count { it.body.coordinates.allocationId == id } == 1)
        return next(allocations = allocations.map { if (it.body.coordinates.allocationId == id) record else it })
    }
    override fun toString(): String = "LocalProvisionerRegistryStateV2([REDACTED])"
}

/** Head key intent for the desktop company container; boot is the full OS boot session. */
internal data class LocalCompanyHeadKeyIntentV2(
    val role: LocalCompanyHeadKeyRoleV1,
    val keyId: DomainId,
    val keyRevision: Long,
    val phase: SelfOwnedKeyIntentPhaseV1,
    val startedBoot: SelfOwnedNativeBootSessionV2?,
    val security: SelfOwnedNativeSecurityV1?,
    val proof: SelfOwnedOctetsV1,
) {
    init {
        require(keyRevision == 0L)
        when (phase) {
            SelfOwnedKeyIntentPhaseV1.NOT_STARTED -> require(startedBoot == null && security == null && proof.size == 0)
            SelfOwnedKeyIntentPhaseV1.STARTED -> require(startedBoot != null && security == null && proof.size == 0)
            SelfOwnedKeyIntentPhaseV1.COMPLETE -> require(startedBoot != null && security != null &&
                proof.size in 1..SelfOwnedNativeOriginalProfileV2.MAXIMUM_KEY_PROOF_BYTES)
        }
    }
    override fun toString(): String = "LocalCompanyHeadKeyIntentV2([REDACTED])"
}

/** Desktop mirror of [LocalCompanyAuthorityBootstrapV1] over [SelfOwnedBootstrapV2]. */
internal data class LocalCompanyAuthorityBootstrapV2(
    val revision: Long,
    val original: SelfOwnedBootstrapV2,
    val headAuthorityId: DomainId,
    val headEnrollmentId: DomainId,
    val headName: String,
    val headLogin: String,
    val lifetime: LocalCompanyHeadLifetimeV1,
    val headKeys: List<LocalCompanyHeadKeyIntentV2>,
) {
    init {
        require(revision in 0 until Long.MAX_VALUE && revision >= original.revision)
        localCompanyHeadName(headName); selfOwnedLogin(headLogin)
        require(lifetime == LocalCompanyHeadLifetimeV1.UNTIL_LOCAL_DISABLE)
        require(headKeys.map { it.role } == LocalCompanyHeadKeyRoleV1.entries.toList())
        require(allIds().distinct().size == 15)
        require(headKeys.all { it.startedBoot == null || it.startedBoot.platform == original.platform })
        var unfinished = !original.complete()
        for (key in headKeys) {
            if (key.phase == SelfOwnedKeyIntentPhaseV1.COMPLETE) require(!unfinished)
            else {
                if (key.phase == SelfOwnedKeyIntentPhaseV1.STARTED) require(!unfinished)
                unfinished = true
            }
        }
        require(!original.enrollmentStarted || complete())
    }

    fun allIds(): List<DomainId> = listOf(original.actionId, original.rootId, original.organizationId,
        original.companyId, original.issuerId, original.installerId) + original.keys.map { it.keyId } +
        listOf(headAuthorityId, headEnrollmentId) + headKeys.map { it.keyId }
    fun key(role: LocalCompanyHeadKeyRoleV1): LocalCompanyHeadKeyIntentV2 = headKeys.single { it.role == role }
    fun complete(): Boolean = original.complete() && headKeys.all { it.phase == SelfOwnedKeyIntentPhaseV1.COMPLETE }
    fun nextOriginal(value: SelfOwnedBootstrapV2): LocalCompanyAuthorityBootstrapV2 {
        capacityRevision(revision)
        return copy(revision = revision + 1L, original = value)
    }
    fun replaceKey(value: LocalCompanyHeadKeyIntentV2): LocalCompanyAuthorityBootstrapV2 {
        capacityRevision(revision)
        require(!original.enrollmentStarted && value.keyId == key(value.role).keyId)
        return copy(revision = revision + 1L, headKeys = headKeys.map { if (it.role == value.role) value else it })
    }
    override fun toString(): String = "LocalCompanyAuthorityBootstrapV2([REDACTED])"
}

internal enum class SelfOwnedBootOrdinalRefusalV2 {
    PLATFORM_MISMATCH, CAPACITY_EXHAUSTED, ORDINAL_DECREASED, ORDINAL_CONFLICT, ORDINAL_GAP,
    DIGEST_MISMATCH, SESSION_NOT_ADVANCED,
}

internal sealed interface SelfOwnedBootOrdinalStepV2 {
    data class Same(val ledger: SelfOwnedBootOrdinalLedgerV2) : SelfOwnedBootOrdinalStepV2
    data class Advanced(val ledger: SelfOwnedBootOrdinalLedgerV2) : SelfOwnedBootOrdinalStepV2
    data class Refused(val reason: SelfOwnedBootOrdinalRefusalV2) : SelfOwnedBootOrdinalStepV2
}

/**
 * Hash-chained desktop boot ordinal. It replaces the Android integer boot count for throttle and
 * key-intent bookkeeping without fabricating one: ordinal 0 is the first observed OS boot session
 * (no previous digest); each new session is exactly ordinal + 1 bound to the SHA-256 of the
 * previous canonical ledger. Pure DATA: it is not anti-rollback, attestation or custody evidence.
 */
internal data class SelfOwnedBootOrdinalLedgerV2(
    val ordinal: Int,
    val bootSession: SelfOwnedNativeBootSessionV2,
    val previousDigest: String?,
) {
    init {
        require(ordinal >= 0)
        require((ordinal == 0) == (previousDigest == null))
        previousDigest?.let(::selfOwnedHexDigest)
    }

    val bootSessionId: String get() = bootSession.bootSessionId

    fun digest(): String {
        val bytes = SelfOwnedNativeProvisionerWireV2.encodeBootOrdinalLedger(this)
        return try { sha256(bytes).value } finally { bytes.fill(0) }
    }

    /** Same session keeps this ledger; a new session on the same platform advances by exactly one. */
    fun advance(current: SelfOwnedNativeBootSessionV2): SelfOwnedBootOrdinalStepV2 = when {
        current.platform != bootSession.platform -> SelfOwnedBootOrdinalStepV2.Refused(SelfOwnedBootOrdinalRefusalV2.PLATFORM_MISMATCH)
        current.bootSessionId == bootSession.bootSessionId -> SelfOwnedBootOrdinalStepV2.Same(this)
        ordinal == Int.MAX_VALUE -> SelfOwnedBootOrdinalStepV2.Refused(SelfOwnedBootOrdinalRefusalV2.CAPACITY_EXHAUSTED)
        else -> SelfOwnedBootOrdinalStepV2.Advanced(SelfOwnedBootOrdinalLedgerV2(ordinal + 1, current, digest()))
    }

    /** Checks a retained [next] against this predecessor; anything but Same/Advanced is refused. */
    fun verifySuccessor(next: SelfOwnedBootOrdinalLedgerV2): SelfOwnedBootOrdinalStepV2 {
        fun refuse(reason: SelfOwnedBootOrdinalRefusalV2) = SelfOwnedBootOrdinalStepV2.Refused(reason)
        return when {
            next.bootSession.platform != bootSession.platform -> refuse(SelfOwnedBootOrdinalRefusalV2.PLATFORM_MISMATCH)
            next == this -> SelfOwnedBootOrdinalStepV2.Same(next)
            next.ordinal < ordinal -> refuse(SelfOwnedBootOrdinalRefusalV2.ORDINAL_DECREASED)
            next.ordinal == ordinal -> refuse(SelfOwnedBootOrdinalRefusalV2.ORDINAL_CONFLICT)
            next.ordinal.toLong() != ordinal.toLong() + 1L -> refuse(SelfOwnedBootOrdinalRefusalV2.ORDINAL_GAP)
            next.bootSessionId == bootSessionId -> refuse(SelfOwnedBootOrdinalRefusalV2.SESSION_NOT_ADVANCED)
            next.previousDigest != digest() -> refuse(SelfOwnedBootOrdinalRefusalV2.DIGEST_MISMATCH)
            else -> SelfOwnedBootOrdinalStepV2.Advanced(next)
        }
    }

    override fun toString(): String = "SelfOwnedBootOrdinalLedgerV2([REDACTED])"
}

internal fun selfOwnedNativePlatformOf(target: SelfOwnedNativeInstalledTargetBindingV1): SelfOwnedNativePlatformV2 =
    when (target.platform) {
        SelfOwnedInstalledTargetPlatformV1.WINDOWS -> SelfOwnedNativePlatformV2.WINDOWS
        SelfOwnedInstalledTargetPlatformV1.MACOS -> SelfOwnedNativePlatformV2.MACOS
        SelfOwnedInstalledTargetPlatformV1.ANDROID, SelfOwnedInstalledTargetPlatformV1.IOS ->
            throw IllegalArgumentException("Only Windows/macOS are V2 native provisioner targets")
    }

private fun selfOwnedFileId128(value: String): Boolean =
    value.length == 32 && value.all { it in '0'..'9' || it in 'a'..'f' }

private fun selfOwnedProvisionerDirectoryNames(): List<String> = buildList {
    add(SelfOwnedCompositionNames.LIVE_PROVISIONER_DIRECTORY)
    LocalTrainingCompositionSlot.entries.forEach { add(SelfOwnedCompositionNames.trainingProvisionerDirectory(it)) }
}

private fun selfOwnedWindowsProvisionerPath(value: String): Boolean {
    if (value.length !in 3..SelfOwnedNativeOriginalProfileV2.MAXIMUM_PATH_BYTES) return false
    if (value[0] !in 'A'..'Z' && value[0] !in 'a'..'z') return false
    if (value[1] != ':' || value[2] != '\\') return false
    if ('\u0000' in value || '/' in value || value.endsWith("\\") || "\\\\" in value) return false
    try { selfOwnedUtf16(value) } catch (_: IllegalArgumentException) { return false }
    val parts = value.split('\\')
    if (parts.size < 2 || parts.any { it.isEmpty() || it == "." || it == ".." }) return false
    return parts.last() in selfOwnedProvisionerDirectoryNames()
}

private fun selfOwnedPosixProvisionerPath(value: String): Boolean {
    if (value.length !in 1..SelfOwnedNativeOriginalProfileV2.MAXIMUM_PATH_BYTES) return false
    if (!value.startsWith('/') || '\u0000' in value) return false
    try { selfOwnedUtf16(value) } catch (_: IllegalArgumentException) { return false }
    if (value.split('/').any { it == "." || it == ".." }) return false
    return SelfOwnedCompositionNames.isProvisionerPath(value)
}
