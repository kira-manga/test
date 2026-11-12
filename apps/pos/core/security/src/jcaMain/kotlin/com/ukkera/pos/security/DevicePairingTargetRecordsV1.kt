package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import dev.p2pkit.core.AppId

/** Shared byte limits of the target-only pairing records; values are unchanged from the Android original. */
internal object DevicePairingTargetLimitsV1 {
    const val MAXIMUM_RESPONSE_CYCLES = LocalDevicePairingProfileV1.MAXIMUM_ACTIONS
    const val TOTAL_SNAPSHOT_MAX = SelfOwnedInstallationProfileV1.MAXIMUM_REGISTRY_BYTES
    const val ALLOCATION_INTENT_MAX = LocalDevicePairingProfileV1.MAXIMUM_RETIREMENT_BYTES + 4096
    const val ALLOCATION_READY_MAX = ALLOCATION_INTENT_MAX + 64 * 1024 + 4096
    const val IDENTITY_MAX = 16 * 1024
    const val KEY_INTENT_MAX = 8 * 1024
    const val READY_MAX = 64 * 1024
    const val SDK_ACQUISITION_MAX = 8 * 1024
    const val SDK_SIGNING_INTENT_MAX = LocalDevicePairingProfileV2.MAXIMUM_SDK_IDENTITY_BYTES + 4096
    const val SDK_READY_MAX = IDENTITY_MAX + READY_MAX + SDK_ACQUISITION_MAX + SDK_SIGNING_INTENT_MAX + 8192
    const val RESPONSE_INTENT_MAX = LocalDevicePairingProfileV1.MAXIMUM_CHALLENGE_BYTES + 4096
    const val COMPLETE_MAX = SelfOwnedInstallationProfileV1.MAXIMUM_REGISTRY_BYTES
    const val APPROVAL_CERTIFICATE_MAX = LocalDevicePairingProfileV1.MAXIMUM_CHALLENGE_BYTES +
            LocalDevicePairingProfileV1.COMPLETION_RESERVATION_BYTES + 1024
    const val APPROVED_MASTER_IMPORT_INTENT_MAX = 8192
    const val APPROVED_MASTER_EVIDENCE_MAX = SelfOwnedInstallationProfileV1.MAXIMUM_AUTHORITY_DOCUMENT_BYTES + 8192
}

internal data class TargetNamespace(val path: String, val uid: Int, val volumeUuid: String, val directoryInode: Long, val lockInode: Long) {
    init {
        require(path.startsWith('/') && SelfOwnedCompositionNames.isDevicePairingTargetPath(path) &&
            path.length in 1..1024 && '\u0000' !in path && path.split('/').none { it == "." || it == ".." })
        selfOwnedUtf16(path)
        require(uid >= 0 && directoryInode > 0 && lockInode > 0 && volumeUuid.length == 36 && volumeUuid.withIndex().all { (index, code) ->
            if (index in listOf(8, 13, 18, 23)) code == '-' else code in '0'..'9' || code in 'a'..'f' })
    }
}
/** Record family: Android V1 bytes are unchanged; desktop Windows/macOS Clients use separate domains. */
internal enum class TargetWireVariant(val suffix: String, val targetProfile: String, val sdkProfile: String) {
    ANDROID_V1("", "TARGET_ONLY_ANDROID31_NONDEBUGGABLE_NATIVE_P256_OS_ISOLATED_V1",
        "TARGET_OWNED_OFFICIAL_ANDROID_SDK_X25519_NATIVE_P256_ASSOCIATION_V2"),
    DESKTOP_V2("+desktop-v2", "TARGET_ONLY_DESKTOP_PACKAGED_CLIENT_NATIVE_P256_V2",
        "TARGET_OWNED_OFFICIAL_DESKTOP_SDK_X25519_NATIVE_P256_ASSOCIATION_V2");
    fun domain(base: String): String = base + suffix
}

/**
 * Observed boot. Android keeps its OS boot count. Desktop keeps the full OS boot-session UUID and
 * a local ordinal assigned by the target's append-only boot ledger; ordering uses the ordinal and
 * the same boot requires both values. A UUID is never truncated or replaced by a counter.
 */
internal sealed interface TargetBoot : Comparable<TargetBoot> {
    val ordinal: Int
    val variant: TargetWireVariant
    fun sameBoot(other: TargetBoot): Boolean = this == other
    override fun compareTo(other: TargetBoot): Int {
        require(variant == other.variant) { "boot variants differ" }
        return ordinal.compareTo(other.ordinal)
    }
    data class Android(override val ordinal: Int) : TargetBoot {
        init { require(ordinal >= 0) }
        override val variant: TargetWireVariant get() = TargetWireVariant.ANDROID_V1
    }
    data class Desktop(override val ordinal: Int, val sessionId: String) : TargetBoot {
        init { require(ordinal >= 0 && SelfOwnedInstalledIdentityGrammarV2.canonicalUuid(sessionId)) }
        override val variant: TargetWireVariant get() = TargetWireVariant.DESKTOP_V2
        override fun toString(): String = "TargetBoot.Desktop($ordinal, [REDACTED])"
    }
}

/** Actual desktop Client target directory/lock identity; Windows: owner SID and file IDs, macOS: uid and inodes. */
internal data class TargetDesktopNamespaceV2(val platform: SelfOwnedNativePlatformV2, val canonicalPath: String,
    val owner: String, val volumeUuid: String, val directoryId: String, val lockId: String) {
    init {
        require(canonicalPath.length in 3..1024 && '\u0000' !in canonicalPath)
        selfOwnedUtf16(canonicalPath)
        require(SelfOwnedInstalledIdentityGrammarV2.canonicalUuid(volumeUuid) && directoryId != lockId)
        val names = listOf(SelfOwnedCompositionNames.devicePairingTargetDirectory(null)) +
            LocalTrainingCompositionSlot.entries.map { SelfOwnedCompositionNames.devicePairingTargetDirectory(it) }
        when (platform) {
            SelfOwnedNativePlatformV2.WINDOWS -> {
                require(canonicalPath[0].isLetter() && canonicalPath.substring(1, 3) == ":\\" && '/' !in canonicalPath &&
                    canonicalPath.split('\\').drop(1).none { it.isEmpty() || it == "." || it == ".." } &&
                    names.any { canonicalPath.endsWith("\\$it") })
                require(SelfOwnedInstalledIdentityGrammarV2.accountSid(owner))
                require(listOf(directoryId, lockId).all { id -> id.length == 32 && id.all { it in '0'..'9' || it in 'a'..'f' } })
            }
            SelfOwnedNativePlatformV2.MACOS -> {
                require(canonicalPath.startsWith('/') && canonicalPath.split('/').none { it == "." || it == ".." } &&
                    names.any { canonicalPath.endsWith("/$it") })
                require(owner.toIntOrNull()?.let { it >= 0 && it.toString() == owner } == true)
                require(listOf(directoryId, lockId).all { id -> id.toLongOrNull()?.let { it > 0 && it.toString() == id } == true })
            }
        }
    }
    override fun toString(): String = "TargetDesktopNamespaceV2([REDACTED])"
}

/** Where the original target lives. Constructing it proves no custody. */
internal sealed interface TargetOrigin {
    val variant: TargetWireVariant
    data class Android(val target: SelfOwnedTargetDescriptorV1, val namespace: TargetNamespace) : TargetOrigin {
        init { require(target.applicationUid == namespace.uid) }
        override val variant: TargetWireVariant get() = TargetWireVariant.ANDROID_V1
    }
    data class Desktop(val installed: SelfOwnedInstalledIdentityTargetV2, val namespace: TargetDesktopNamespaceV2) : TargetOrigin {
        init {
            require(when (namespace.platform) {
                SelfOwnedNativePlatformV2.WINDOWS -> installed is SelfOwnedWindowsPackagedAppIdentityV2
                SelfOwnedNativePlatformV2.MACOS -> installed is SelfOwnedMacUiIdentityV2
            })
        }
        override val variant: TargetWireVariant get() = TargetWireVariant.DESKTOP_V2
    }
}

internal data class TargetIdentity(val preparationId: DomainId, val deviceId: DomainId, val keyId: DomainId, val responseId: DomainId,
    val scope: BranchScope, val capabilities: List<String>, val origin: TargetOrigin,
    val osBoot: TargetBoot, val observedElapsed: Long, val nonce: SelfOwnedOctetsV1) {
    /** Android V1 compatibility constructor; produces the unchanged V1 record. */
    constructor(preparationId: DomainId, deviceId: DomainId, keyId: DomainId, responseId: DomainId, scope: BranchScope,
        capabilities: List<String>, target: SelfOwnedTargetDescriptorV1, namespace: TargetNamespace, osBoot: Int,
        observedElapsed: Long, nonce: SelfOwnedOctetsV1) : this(preparationId, deviceId, keyId, responseId, scope,
        capabilities, TargetOrigin.Android(target, namespace), TargetBoot.Android(osBoot), observedElapsed, nonce)
    val variant: TargetWireVariant get() = origin.variant
    /** Android-only accessors; a desktop original never fabricates Android fields. */
    val target: SelfOwnedTargetDescriptorV1 get() = (origin as TargetOrigin.Android).target
    val namespace: TargetNamespace get() = (origin as TargetOrigin.Android).namespace
    init {
        val ids = listOf(preparationId, deviceId, keyId, responseId)
        require(ids.distinct().size == ids.size && ids.none { it in setOf(scope.organizationId, scope.companyId, scope.branchId) } &&
            observedElapsed >= 0 && nonce.size == 32 && osBoot.variant == origin.variant)
        require(capabilities == capabilities.sorted() && capabilities.distinct().size == capabilities.size)
        LocalDeviceAdmission(scope, deviceId, Revision(0), true, keyId.value, null, capabilities.toSet())
    }
}
internal data class TargetKeyIntent(val identityDigest: PayloadDigest, val osBoot: TargetBoot, val observedElapsed: Long,
    val security: SelfOwnedNativeSecurityV1, val point: SelfOwnedOctetsV1) {
    constructor(identityDigest: PayloadDigest, osBoot: Int, observedElapsed: Long, security: SelfOwnedNativeSecurityV1,
        point: SelfOwnedOctetsV1) : this(identityDigest, TargetBoot.Android(osBoot), observedElapsed, security, point)
    init { require(observedElapsed >= 0 && point.size == 65) }
}
internal data class TargetReady(val message: SelfOwnedOctetsV1, val signature: SelfOwnedOctetsV1) {
    init { require(message.size in 1..DevicePairingTargetLimitsV1.READY_MAX && signature.size == 64) }
}
internal data class TargetReadyFields(val identity: SelfOwnedOctetsV1, val keyIntent: SelfOwnedOctetsV1, val descriptor: SelfOwnedOctetsV1)
internal class TargetResponseSlot(var allocation: TargetResponseAllocation? = null, var allocationReady: TargetAllocationReady? = null,
    var responseIntent: TargetResponseIntent? = null, var complete: TargetComplete? = null,
    var approvedMasterImportIntent: TargetApprovedMasterImportIntent? = null,
    var approvedMasterEvidence: TargetApprovedMasterEvidence? = null)
internal data class TargetResponseAllocation(val index: Int, val responseId: DomainId, val nonce: SelfOwnedOctetsV1,
    val identityDigest: PayloadDigest, val parentAllocationDigest: PayloadDigest,
    val previousIntentDigest: PayloadDigest?, val previousCompleteDigest: PayloadDigest?,
    val retirement: SelfOwnedOctetsV1, val osBoot: TargetBoot, val observedElapsed: Long) {
    constructor(index: Int, responseId: DomainId, nonce: SelfOwnedOctetsV1, identityDigest: PayloadDigest,
        parentAllocationDigest: PayloadDigest, previousIntentDigest: PayloadDigest?, previousCompleteDigest: PayloadDigest?,
        retirement: SelfOwnedOctetsV1, osBoot: Int, observedElapsed: Long) : this(index, responseId, nonce, identityDigest,
        parentAllocationDigest, previousIntentDigest, previousCompleteDigest, retirement, TargetBoot.Android(osBoot), observedElapsed)
    init { require(index in 1 until DevicePairingTargetLimitsV1.MAXIMUM_RESPONSE_CYCLES && nonce.size == 32 &&
        retirement.size in 1..LocalDevicePairingProfileV1.MAXIMUM_RETIREMENT_BYTES && observedElapsed >= 0) }
}
internal data class TargetAllocationReady(val message: SelfOwnedOctetsV1, val signature: SelfOwnedOctetsV1) {
    init { require(message.size in 1..DevicePairingTargetLimitsV1.ALLOCATION_READY_MAX - 1024 && signature.size == 64) }
}
internal data class TargetAllocationReadyFields(val ready: SelfOwnedOctetsV1, val allocation: SelfOwnedOctetsV1)
internal data class TargetResponseIntent(val responseId: DomainId, val allocationIndex: Int, val allocationDigest: PayloadDigest,
    val readyDigest: PayloadDigest, val osBoot: TargetBoot,
    val observedElapsed: Long, val nonce: SelfOwnedOctetsV1, val challenge: SelfOwnedOctetsV1) {
    constructor(responseId: DomainId, allocationIndex: Int, allocationDigest: PayloadDigest, readyDigest: PayloadDigest,
        osBoot: Int, observedElapsed: Long, nonce: SelfOwnedOctetsV1, challenge: SelfOwnedOctetsV1) : this(responseId,
        allocationIndex, allocationDigest, readyDigest, TargetBoot.Android(osBoot), observedElapsed, nonce, challenge)
    init { require(allocationIndex in 0 until DevicePairingTargetLimitsV1.MAXIMUM_RESPONSE_CYCLES &&
        observedElapsed >= 0 && nonce.size == 32 && challenge.size in 1..LocalDevicePairingProfileV1.MAXIMUM_CHALLENGE_BYTES) }
}
internal data class TargetComplete(val message: SelfOwnedOctetsV1, val signature: SelfOwnedOctetsV1) {
    init { require(message.size in 1..DevicePairingTargetLimitsV1.COMPLETE_MAX - 1024 && signature.size == 64) }
}
internal data class TargetCompleteFields(val ready: SelfOwnedOctetsV1, val allocation: SelfOwnedOctetsV1,
    val intent: SelfOwnedOctetsV1, val possession: SelfOwnedOctetsV1)

/** One immutable first-import marker. Its forced presence forbids retrying a missing final
 * fragment; it links only to existing native C/P snapshots, without new IDs/signatures. */
internal data class TargetApprovedMasterImportIntent(val originalPairingActionId: DomainId,
    val allocationIndex: Int, val responseId: DomainId,
    val responseIntentDigest: PayloadDigest, val completeDigest: PayloadDigest,
    val approvalCertificateDigest: PayloadDigest, val rootAnnouncementDigest: PayloadDigest) {
    init { require(allocationIndex in 0 until DevicePairingTargetLimitsV1.MAXIMUM_RESPONSE_CYCLES &&
        originalPairingActionId != responseId) }
}
/** Bounded root/approval fragments only, referencing the original native C/P through the
 * exact forced import intent. No additional complete challenge/snapshot or native signature. */
internal data class TargetApprovedMasterEvidence(val importIntentDigest: PayloadDigest,
    val consent: NativeDevicePairingConsentV1, val approvedHead: NativeDevicePairingApprovedHeadV1,
    val rootEndorsement: SelfOwnedOctetsV1, val rootAnnouncement: SelfOwnedOctetsV1) {
    init { require(rootEndorsement.size == 64 && rootAnnouncement.size in
        1..SelfOwnedInstallationProfileV1.MAXIMUM_AUTHORITY_DOCUMENT_BYTES) }
}

internal data class TargetSdkAcquisition(val acquisitionId: DomainId, val identityDigest: PayloadDigest,
    val readyDigest: PayloadDigest, val appId: AppId, val deviceName: String,
    val osBoot: TargetBoot, val observedElapsed: Long, val nonce: SelfOwnedOctetsV1) {
    constructor(acquisitionId: DomainId, identityDigest: PayloadDigest, readyDigest: PayloadDigest, appId: AppId,
        deviceName: String, osBoot: Int, observedElapsed: Long, nonce: SelfOwnedOctetsV1) : this(acquisitionId,
        identityDigest, readyDigest, appId, deviceName, TargetBoot.Android(osBoot), observedElapsed, nonce)
    init {
        require(observedElapsed >= 0 && nonce.size == 32)
        LocalDevicePairingProfileV2.validateAcquisitionText(appId.value)
        LocalDevicePairingProfileV2.validateAcquisitionText(deviceName)
    }
}
internal data class TargetSdkSigningIntent(val identityDigest: PayloadDigest, val readyDigest: PayloadDigest,
    val acquisitionDigest: PayloadDigest, val osBoot: TargetBoot, val observedElapsed: Long, val descriptor: SelfOwnedOctetsV1) {
    constructor(identityDigest: PayloadDigest, readyDigest: PayloadDigest, acquisitionDigest: PayloadDigest, osBoot: Int,
        observedElapsed: Long, descriptor: SelfOwnedOctetsV1) : this(identityDigest, readyDigest, acquisitionDigest,
        TargetBoot.Android(osBoot), observedElapsed, descriptor)
    init { require(observedElapsed >= 0 && descriptor.size in 1..LocalDevicePairingProfileV2.MAXIMUM_SDK_IDENTITY_BYTES) }
}
internal data class TargetSdkReady(val message: SelfOwnedOctetsV1, val signature: SelfOwnedOctetsV1) {
    init { require(message.size in 1..DevicePairingTargetLimitsV1.SDK_READY_MAX - 1024 && signature.size == 64) }
}
internal data class TargetSdkReadyFields(val identity: SelfOwnedOctetsV1, val ready: SelfOwnedOctetsV1,
    val acquisition: SelfOwnedOctetsV1, val intent: SelfOwnedOctetsV1)

/** Additive immutable approval retention, not another signing ceremony or wire upgrade. */
internal object TargetApprovedMasterWireV1 {
    private const val PROFILE = "EXACT_ORIGINAL_MASTER_BOUND_APPROVAL_ROOT_EVIDENCE_NO_NEW_TARGET_OR_ROOT_SIGNATURE"
    fun importIntent(value: TargetApprovedMasterImportIntent): ByteArray = selfOwnedEncode(
        "target-original-approved-master-import-intent/v1", DevicePairingTargetLimitsV1.APPROVED_MASTER_IMPORT_INTENT_MAX) {
        purpose(); id(value.originalPairingActionId); int(value.allocationIndex); id(value.responseId)
        string(value.responseIntentDigest.value); string(value.completeDigest.value)
        string(value.approvalCertificateDigest.value); string(value.rootAnnouncementDigest.value)
    }
    fun decodeImportIntent(bytes: ByteArray): TargetApprovedMasterImportIntent = selfOwnedDecode(
        bytes, "target-original-approved-master-import-intent/v1") {
        purpose(); TargetApprovedMasterImportIntent(id(), int(), id(), PayloadDigest(string(64)),
            PayloadDigest(string(64)), PayloadDigest(string(64)), PayloadDigest(string(64)))
    }.also { canonical(bytes, importIntent(it)) }
    fun evidence(value: TargetApprovedMasterEvidence): ByteArray = selfOwnedEncode(
        "target-original-approved-master-evidence/v1", DevicePairingTargetLimitsV1.APPROVED_MASTER_EVIDENCE_MAX) {
        purpose(); string(value.importIntentDigest.value); consent(value.consent); approval(value.approvedHead)
        octets(value.rootEndorsement); octets(value.rootAnnouncement)
    }
    fun decodeEvidence(bytes: ByteArray): TargetApprovedMasterEvidence = selfOwnedDecode(
        bytes, "target-original-approved-master-evidence/v1") {
        purpose(); TargetApprovedMasterEvidence(PayloadDigest(string(64)), consent(), approval(), octets(64),
            octets(SelfOwnedInstallationProfileV1.MAXIMUM_AUTHORITY_DOCUMENT_BYTES))
    }.also { canonical(bytes, evidence(it)) }
    private fun SelfOwnedWireWriterV1.consent(value: NativeDevicePairingConsentV1) {
        id(value.consentId); string(value.actionDigest.value); id(value.observation.issuerContextId)
        id(value.observation.moment.bootId); long(value.observation.moment.elapsedMillis)
        long(value.accountRevision); long(value.credentialRevision); long(value.retainedRevision)
        int(value.osBootCount); long(value.deadlineElapsedMillis)
    }
    private fun SelfOwnedWireReaderV1.consent(): NativeDevicePairingConsentV1 = NativeDevicePairingConsentV1(
        id(), PayloadDigest(string(64)), LocalAuthenticationObservation(id(), MonotonicMoment(id(), long())),
        long(), long(), long(), int(), long())
    private fun SelfOwnedWireWriterV1.approval(value: NativeDevicePairingApprovedHeadV1) {
        long(value.accountRevision); long(value.credentialRevision); long(value.retainedRevision)
        int(value.osBootCount); long(value.deadlineElapsedMillis)
    }
    private fun SelfOwnedWireReaderV1.approval(): NativeDevicePairingApprovedHeadV1 =
        NativeDevicePairingApprovedHeadV1(long(), long(), long(), int(), long())
    private fun SelfOwnedWireWriterV1.purpose() { string(LocalDevicePairingProfileV2.PURPOSE); string(PROFILE) }
    private fun SelfOwnedWireReaderV1.purpose() {
        require(string(128) == LocalDevicePairingProfileV2.PURPOSE && string(128) == PROFILE)
    }
    private fun canonical(actual: ByteArray, encoded: ByteArray) {
        try { require(actual.contentEquals(encoded)) } finally { encoded.fill(0) }
    }
}

/** Separately additive private metadata. No change to native V1 files, signing domains,
 * original descriptor/fingerprint, fixed identity allocation or retained response history. */
internal object TargetSdkWireV2 {
    fun acquisition(value: TargetSdkAcquisition, variant: TargetWireVariant = value.osBoot.variant): ByteArray = selfOwnedEncode(variant.domain("target-original-sdk-acquisition-intent/v2"),
        DevicePairingTargetLimitsV1.SDK_ACQUISITION_MAX) {
        purpose(variant); string("ONE_ORIGINAL_SDK_CREATION_NO_UNKNOWN_RETRY_NO_START_OR_PEER_ADMISSION")
        id(value.acquisitionId); string(value.identityDigest.value); string(value.readyDigest.value)
        string(value.appId.value); string(value.deviceName); boot(value.osBoot, variant); long(value.observedElapsed); octets(value.nonce)
    }
    fun decodeAcquisition(bytes: ByteArray, variant: TargetWireVariant = TargetWireVariant.ANDROID_V1): TargetSdkAcquisition = selfOwnedDecode(bytes, variant.domain("target-original-sdk-acquisition-intent/v2")) {
        purpose(variant); require(string(128) == "ONE_ORIGINAL_SDK_CREATION_NO_UNKNOWN_RETRY_NO_START_OR_PEER_ADMISSION")
        TargetSdkAcquisition(id(), PayloadDigest(string(64)), PayloadDigest(string(64)),
            AppId(string(LocalDevicePairingProfileV2.MAXIMUM_SDK_APP_ID_BYTES)),
            string(LocalDevicePairingProfileV2.MAXIMUM_SDK_APP_ID_BYTES), boot(variant), long(), octets(32))
    }.also { canonical(bytes, acquisition(it, variant)) }
    fun signingIntent(value: TargetSdkSigningIntent, variant: TargetWireVariant = value.osBoot.variant): ByteArray = selfOwnedEncode(variant.domain("target-original-sdk-metadata-signing-intent/v2"),
        DevicePairingTargetLimitsV1.SDK_SIGNING_INTENT_MAX) {
        purpose(variant); string("ONE_ORIGINAL_SDK_ASSOCIATION_METADATA_SIGNATURE_NO_RETRY")
        string(value.identityDigest.value); string(value.readyDigest.value); string(value.acquisitionDigest.value)
        boot(value.osBoot, variant); long(value.observedElapsed); octets(value.descriptor)
    }
    fun decodeSigningIntent(bytes: ByteArray, variant: TargetWireVariant = TargetWireVariant.ANDROID_V1): TargetSdkSigningIntent = selfOwnedDecode(bytes, variant.domain("target-original-sdk-metadata-signing-intent/v2")) {
        purpose(variant); require(string(128) == "ONE_ORIGINAL_SDK_ASSOCIATION_METADATA_SIGNATURE_NO_RETRY")
        TargetSdkSigningIntent(PayloadDigest(string(64)), PayloadDigest(string(64)), PayloadDigest(string(64)),
            boot(variant), long(), octets(LocalDevicePairingProfileV2.MAXIMUM_SDK_IDENTITY_BYTES))
    }.also { canonical(bytes, signingIntent(it, variant)) }
    fun readyMessage(identity: ByteArray, ready: ByteArray, acquisition: ByteArray, intent: ByteArray, variant: TargetWireVariant = TargetWireVariant.ANDROID_V1): ByteArray = selfOwnedEncode(
        "target-private-original-native-sdk-association-snapshot/v2", DevicePairingTargetLimitsV1.SDK_READY_MAX - 1024) {
        purpose(variant); field(identity); field(ready); field(acquisition); field(intent)
        string("ORIGINAL_SDK_ASSOCIATION_DATA_ONLY_NO_MASTER_HEAD_ADMISSION_SESSION_OR_SERVING_AUTHORITY")
    }
    fun decodeReadyMessage(value: SelfOwnedOctetsV1, variant: TargetWireVariant = TargetWireVariant.ANDROID_V1): TargetSdkReadyFields {
        val bytes = value.copyBytes()
        return try { selfOwnedDecode(bytes, variant.domain("target-private-original-native-sdk-association-snapshot/v2")) {
            purpose(variant); val identity = octets(DevicePairingTargetLimitsV1.IDENTITY_MAX)
            val ready = octets(DevicePairingTargetLimitsV1.READY_MAX)
            val acquisition = octets(DevicePairingTargetLimitsV1.SDK_ACQUISITION_MAX)
            val intent = octets(DevicePairingTargetLimitsV1.SDK_SIGNING_INTENT_MAX)
            require(string(128) == "ORIGINAL_SDK_ASSOCIATION_DATA_ONLY_NO_MASTER_HEAD_ADMISSION_SESSION_OR_SERVING_AUTHORITY")
            TargetSdkReadyFields(identity, ready, acquisition, intent)
        } } finally { bytes.fill(0) }
    }
    fun ready(value: TargetSdkReady, variant: TargetWireVariant = TargetWireVariant.ANDROID_V1): ByteArray = selfOwnedEncode(variant.domain("target-signed-original-native-sdk-association-state/v2"),
        DevicePairingTargetLimitsV1.SDK_READY_MAX) { purpose(variant); octets(value.message); octets(value.signature) }
    fun decodeReady(bytes: ByteArray, variant: TargetWireVariant = TargetWireVariant.ANDROID_V1): TargetSdkReady = selfOwnedDecode(bytes, variant.domain("target-signed-original-native-sdk-association-state/v2")) {
        purpose(variant); TargetSdkReady(octets(DevicePairingTargetLimitsV1.SDK_READY_MAX - 1024), octets(64))
    }.also { canonical(bytes, ready(it, variant)) }
    private fun SelfOwnedWireWriterV1.purpose(variant: TargetWireVariant) { string(LocalDevicePairingProfileV2.PURPOSE); string(variant.sdkProfile) }
    private fun SelfOwnedWireReaderV1.purpose(variant: TargetWireVariant) {
        require(string(128) == LocalDevicePairingProfileV2.PURPOSE && string(128) == variant.sdkProfile)
    }
    private fun canonical(actual: ByteArray, encoded: ByteArray) { try { require(actual.contentEquals(encoded)) } finally { encoded.fill(0) } }
}

/** Target-only named domains, no original tag/ordinal/alias/schema extension. */
internal object TargetWire {
    fun identity(value: TargetIdentity, variant: TargetWireVariant = value.variant): ByteArray = selfOwnedEncode(variant.domain("target-only-pairing-original-generation-intent/v1"), DevicePairingTargetLimitsV1.IDENTITY_MAX) {
        purpose(variant); string("STARTED_NO_GENERATION_RETRY"); id(value.preparationId); id(value.deviceId); id(value.keyId); id(value.responseId)
        id(value.scope.organizationId); id(value.scope.companyId); id(value.scope.branchId)
        int(value.capabilities.size); value.capabilities.forEach { string(it) }; origin(value.origin, variant)
        boot(value.osBoot, variant); long(value.observedElapsed); octets(value.nonce)
    }
    fun decodeIdentity(bytes: ByteArray, variant: TargetWireVariant = TargetWireVariant.ANDROID_V1): TargetIdentity = selfOwnedDecode(bytes, variant.domain("target-only-pairing-original-generation-intent/v1")) {
        purpose(variant); require(string(64) == "STARTED_NO_GENERATION_RETRY")
        val preparation = id(); val device = id(); val key = id(); val response = id(); val scope = BranchScope(id(), id(), id())
        val count = int(); require(count in 0..ProtectedLocalStorageFormat.MAX_DEVICE_CAPABILITIES)
        val capabilities = List(count) { string(ProtectedLocalStorageFormat.MAX_DEVICE_CAPABILITY_BYTES) }
        val origin = origin(variant)
        TargetIdentity(preparation, device, key, response, scope, capabilities, origin, boot(variant), long(), octets(32))
    }.also { canonical(bytes, identity(it, variant)) }
    fun keyIntent(value: TargetKeyIntent, variant: TargetWireVariant = value.osBoot.variant): ByteArray = selfOwnedEncode(variant.domain("target-only-pairing-key-metadata-signing-intent/v1"), DevicePairingTargetLimitsV1.KEY_INTENT_MAX) {
        purpose(variant); string("ONE_ORIGINAL_READY_SNAPSHOT_SIGNATURE_NO_RETRY"); string(value.identityDigest.value)
        boot(value.osBoot, variant); long(value.observedElapsed); string(value.security.name); octets(value.point)
    }
    fun decodeKeyIntent(bytes: ByteArray, variant: TargetWireVariant = TargetWireVariant.ANDROID_V1): TargetKeyIntent = selfOwnedDecode(bytes, variant.domain("target-only-pairing-key-metadata-signing-intent/v1")) {
        purpose(variant); require(string(64) == "ONE_ORIGINAL_READY_SNAPSHOT_SIGNATURE_NO_RETRY")
        val digest = PayloadDigest(string(64)); val boot = boot(variant); val elapsed = long(); val level = string(64)
        TargetKeyIntent(digest, boot, elapsed, requireNotNull(SelfOwnedNativeSecurityV1.entries.singleOrNull { it.name == level }), octets(65))
    }.also { canonical(bytes, keyIntent(it, variant)) }
    fun readyMessage(identity: ByteArray, intent: ByteArray, descriptor: ByteArray, variant: TargetWireVariant = TargetWireVariant.ANDROID_V1): ByteArray = selfOwnedEncode(variant.domain("target-only-private-key-ready-snapshot/v1"), DevicePairingTargetLimitsV1.READY_MAX) {
        purpose(variant); field(identity); field(intent); field(descriptor); string("LOCAL_CUSTODY_ONLY_NOT_REMOTE_ATTESTATION_OR_MASTER_AUTHORITY")
    }
    fun decodeReadyMessage(value: SelfOwnedOctetsV1, variant: TargetWireVariant = TargetWireVariant.ANDROID_V1): TargetReadyFields {
        val bytes = value.copyBytes()
        return try { selfOwnedDecode(bytes, variant.domain("target-only-private-key-ready-snapshot/v1")) {
            purpose(variant); val identity = octets(DevicePairingTargetLimitsV1.IDENTITY_MAX); val intent = octets(DevicePairingTargetLimitsV1.KEY_INTENT_MAX)
            val descriptor = octets(ProtectedLocalTable.ADMISSION.maximumPlaintextBytes + 1024)
            require(string(128) == "LOCAL_CUSTODY_ONLY_NOT_REMOTE_ATTESTATION_OR_MASTER_AUTHORITY")
            TargetReadyFields(identity, intent, descriptor)
        } } finally { bytes.fill(0) }
    }
    fun ready(value: TargetReady, variant: TargetWireVariant = TargetWireVariant.ANDROID_V1): ByteArray = selfOwnedEncode(variant.domain("target-only-signed-private-ready-state/v1"), DevicePairingTargetLimitsV1.READY_MAX) {
        purpose(variant); octets(value.message); octets(value.signature)
    }
    fun decodeReady(bytes: ByteArray, variant: TargetWireVariant = TargetWireVariant.ANDROID_V1): TargetReady = selfOwnedDecode(bytes, variant.domain("target-only-signed-private-ready-state/v1")) {
        purpose(variant); TargetReady(octets(DevicePairingTargetLimitsV1.READY_MAX), octets(64))
    }.also { canonical(bytes, ready(it, variant)) }
    fun allocation(value: TargetResponseAllocation, variant: TargetWireVariant = value.osBoot.variant): ByteArray = selfOwnedEncode(variant.domain("target-explicit-known-unsigned-response-allocation-intent/v1"),
        DevicePairingTargetLimitsV1.ALLOCATION_INTENT_MAX) {
        purpose(variant); string("ONE_NEW_ALLOCATION_METADATA_SIGNATURE_NO_RETRY_OLD_KEY_AND_HISTORY_RETAINED")
        int(value.index); id(value.responseId); octets(value.nonce); string(value.identityDigest.value); string(value.parentAllocationDigest.value)
        bool(value.previousIntentDigest != null); value.previousIntentDigest?.let { string(it.value) }
        bool(value.previousCompleteDigest != null); value.previousCompleteDigest?.let { string(it.value) }
        octets(value.retirement); boot(value.osBoot, variant); long(value.observedElapsed)
    }
    fun decodeAllocation(bytes: ByteArray, variant: TargetWireVariant = TargetWireVariant.ANDROID_V1): TargetResponseAllocation = selfOwnedDecode(bytes, variant.domain("target-explicit-known-unsigned-response-allocation-intent/v1")) {
        purpose(variant); require(string(128) == "ONE_NEW_ALLOCATION_METADATA_SIGNATURE_NO_RETRY_OLD_KEY_AND_HISTORY_RETAINED")
        val index = int(); val id = id(); val nonce = octets(32); val identity = PayloadDigest(string(64)); val parent = PayloadDigest(string(64))
        val intent = if (bool()) PayloadDigest(string(64)) else null; val complete = if (bool()) PayloadDigest(string(64)) else null
        TargetResponseAllocation(index, id, nonce, identity, parent, intent, complete,
            octets(LocalDevicePairingProfileV1.MAXIMUM_RETIREMENT_BYTES), boot(variant), long())
    }.also { canonical(bytes, allocation(it, variant)) }
    fun allocationReadyMessage(ready: ByteArray, allocation: ByteArray, variant: TargetWireVariant = TargetWireVariant.ANDROID_V1): ByteArray = selfOwnedEncode(variant.domain("target-private-known-unsigned-response-allocation-snapshot/v1"),
        DevicePairingTargetLimitsV1.ALLOCATION_READY_MAX - 1024) {
        purpose(variant); field(ready); field(allocation); string("EXPLICIT_APPEND_ONLY_NO_MASTER_TRUST_PERMISSION_OR_OLD_SIGNATURE_RETRY")
    }
    fun decodeAllocationReadyMessage(value: SelfOwnedOctetsV1, variant: TargetWireVariant = TargetWireVariant.ANDROID_V1): TargetAllocationReadyFields {
        val bytes = value.copyBytes()
        return try { selfOwnedDecode(bytes, variant.domain("target-private-known-unsigned-response-allocation-snapshot/v1")) {
            purpose(variant); val ready = octets(DevicePairingTargetLimitsV1.READY_MAX)
            val allocation = octets(DevicePairingTargetLimitsV1.ALLOCATION_INTENT_MAX)
            require(string(128) == "EXPLICIT_APPEND_ONLY_NO_MASTER_TRUST_PERMISSION_OR_OLD_SIGNATURE_RETRY")
            TargetAllocationReadyFields(ready, allocation)
        } } finally { bytes.fill(0) }
    }
    fun allocationReady(value: TargetAllocationReady, variant: TargetWireVariant = TargetWireVariant.ANDROID_V1): ByteArray = selfOwnedEncode(variant.domain("target-signed-known-unsigned-response-allocation-state/v1"),
        DevicePairingTargetLimitsV1.ALLOCATION_READY_MAX) { purpose(variant); octets(value.message); octets(value.signature) }
    fun decodeAllocationReady(bytes: ByteArray, variant: TargetWireVariant = TargetWireVariant.ANDROID_V1): TargetAllocationReady = selfOwnedDecode(bytes, variant.domain("target-signed-known-unsigned-response-allocation-state/v1")) {
        purpose(variant); TargetAllocationReady(octets(DevicePairingTargetLimitsV1.ALLOCATION_READY_MAX - 1024), octets(64))
    }.also { canonical(bytes, allocationReady(it, variant)) }
    fun responseIntent(value: TargetResponseIntent, variant: TargetWireVariant = value.osBoot.variant): ByteArray = selfOwnedEncode(variant.domain("target-only-original-possession-signing-intent/v1"), DevicePairingTargetLimitsV1.RESPONSE_INTENT_MAX) {
        purpose(variant); string("ONE_POSSESSION_AND_ONE_COMPLETED_SNAPSHOT_SIGNATURE_NO_RETRY")
        id(value.responseId); int(value.allocationIndex); string(value.allocationDigest.value); string(value.readyDigest.value)
        boot(value.osBoot, variant); long(value.observedElapsed); octets(value.nonce); octets(value.challenge)
    }
    fun decodeResponseIntent(bytes: ByteArray, variant: TargetWireVariant = TargetWireVariant.ANDROID_V1): TargetResponseIntent = selfOwnedDecode(bytes, variant.domain("target-only-original-possession-signing-intent/v1")) {
        purpose(variant); require(string(128) == "ONE_POSSESSION_AND_ONE_COMPLETED_SNAPSHOT_SIGNATURE_NO_RETRY")
        TargetResponseIntent(id(), int(), PayloadDigest(string(64)), PayloadDigest(string(64)), boot(variant), long(), octets(32),
            octets(LocalDevicePairingProfileV1.MAXIMUM_CHALLENGE_BYTES))
    }.also { canonical(bytes, responseIntent(it, variant)) }
    fun completeMessage(ready: ByteArray, allocation: ByteArray, intent: ByteArray, possession: ByteArray, variant: TargetWireVariant = TargetWireVariant.ANDROID_V1): ByteArray = selfOwnedEncode(variant.domain("target-only-original-private-possession-snapshot/v1"), DevicePairingTargetLimitsV1.COMPLETE_MAX - 1024) {
        purpose(variant); field(ready); field(allocation); field(intent); field(possession); string("ORIGINAL_POSSESSION_DATA_NO_ADMISSION_SESSION_MASTER_OR_REMOTE_ATTESTATION")
    }
    fun decodeCompleteMessage(value: SelfOwnedOctetsV1, variant: TargetWireVariant = TargetWireVariant.ANDROID_V1): TargetCompleteFields {
        val bytes = value.copyBytes()
        return try { selfOwnedDecode(bytes, variant.domain("target-only-original-private-possession-snapshot/v1")) {
            purpose(variant); val ready = octets(DevicePairingTargetLimitsV1.READY_MAX)
            val allocation = octets(DevicePairingTargetLimitsV1.ALLOCATION_READY_MAX)
            val intent = octets(DevicePairingTargetLimitsV1.RESPONSE_INTENT_MAX)
            val proof = octets(LocalDevicePairingProfileV1.MAXIMUM_POSSESSION_BYTES)
            require(string(128) == "ORIGINAL_POSSESSION_DATA_NO_ADMISSION_SESSION_MASTER_OR_REMOTE_ATTESTATION")
            TargetCompleteFields(ready, allocation, intent, proof)
        } } finally { bytes.fill(0) }
    }
    fun complete(value: TargetComplete, variant: TargetWireVariant = TargetWireVariant.ANDROID_V1): ByteArray = selfOwnedEncode(variant.domain("target-only-signed-original-completed-state/v1"), DevicePairingTargetLimitsV1.COMPLETE_MAX) {
        purpose(variant); octets(value.message); octets(value.signature)
    }
    fun decodeComplete(bytes: ByteArray, variant: TargetWireVariant = TargetWireVariant.ANDROID_V1): TargetComplete = selfOwnedDecode(bytes, variant.domain("target-only-signed-original-completed-state/v1")) {
        purpose(variant); TargetComplete(octets(DevicePairingTargetLimitsV1.COMPLETE_MAX - 1024), octets(64))
    }.also { canonical(bytes, complete(it, variant)) }
    private fun SelfOwnedWireWriterV1.purpose(variant: TargetWireVariant) { string(LocalDevicePairingProfileV1.PURPOSE); string(variant.targetProfile) }
    private fun SelfOwnedWireReaderV1.purpose(variant: TargetWireVariant) {
        require(string(128) == LocalDevicePairingProfileV1.PURPOSE && string(128) == variant.targetProfile)
    }
    private fun canonical(actual: ByteArray, encoded: ByteArray) { try { require(actual.contentEquals(encoded)) } finally { encoded.fill(0) } }
}

/** Boot/origin field codecs shared by the target record wires; Android V1 bytes are unchanged. */
internal fun SelfOwnedWireWriterV1.boot(value: TargetBoot, variant: TargetWireVariant) {
    require(value.variant == variant)
    int(value.ordinal)
    if (value is TargetBoot.Desktop) string(value.sessionId)
}
internal fun SelfOwnedWireReaderV1.boot(variant: TargetWireVariant): TargetBoot = when (variant) {
    TargetWireVariant.ANDROID_V1 -> TargetBoot.Android(int())
    TargetWireVariant.DESKTOP_V2 -> TargetBoot.Desktop(int(), string(36))
}
internal fun SelfOwnedWireWriterV1.origin(value: TargetOrigin, variant: TargetWireVariant) {
    require(value.variant == variant)
    when (value) {
        is TargetOrigin.Android -> {
            target(value.target)
            string(value.namespace.path); int(value.namespace.uid); string(value.namespace.volumeUuid)
            long(value.namespace.directoryInode); long(value.namespace.lockInode)
        }
        is TargetOrigin.Desktop -> {
            val installed = SelfOwnedInstalledIdentityWireV2.encodeTarget(value.installed)
            try { field(installed) } finally { installed.fill(0) }
            int(value.namespace.platform.wireId); string(value.namespace.canonicalPath); string(value.namespace.owner)
            string(value.namespace.volumeUuid); string(value.namespace.directoryId); string(value.namespace.lockId)
        }
    }
}
internal fun SelfOwnedWireReaderV1.origin(variant: TargetWireVariant): TargetOrigin = when (variant) {
    TargetWireVariant.ANDROID_V1 -> TargetOrigin.Android(target(), TargetNamespace(string(1024), int(), string(36), long(), long()))
    TargetWireVariant.DESKTOP_V2 -> {
        val bytes = field(SelfOwnedInstalledIdentityProfileV2.MAXIMUM_TARGET_BYTES)
        val installed = try {
            when (val decoded = SelfOwnedInstalledIdentityWireV2.decodeTarget(bytes)) {
                is SelfOwnedInstalledIdentityResultV2.Data -> decoded.value
                is SelfOwnedInstalledIdentityResultV2.Refused -> throw IllegalArgumentException("installed target")
            }
        } finally { bytes.fill(0) }
        val platform = requireNotNull(SelfOwnedNativePlatformV2.fromWireId(int()))
        TargetOrigin.Desktop(installed, TargetDesktopNamespaceV2(platform, string(1024), string(256), string(36),
            string(64), string(64)))
    }
}
