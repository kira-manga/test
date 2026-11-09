package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import dev.p2pkit.core.AppId
import dev.p2pkit.core.PeerFingerprint
import dev.p2pkit.core.PeerId

/** Stable branch-wide transport namespace. This public DATA grants no admission or permissions. */
public object LocalMasterSdkProfileV1 : LocalCompositionSdkProfileV1 {
    public const val PURPOSE: String = "ORIGINAL_NATIVE_MASTER_SDK_X25519_IDENTITY_V1"
    public const val MAXIMUM_ORIGINAL_BYTES: Int = 16 * 1024

    override val mode: LocalSetupCompositionMode get() = LocalSetupCompositionMode.LIVE

    /** All peers of the branch use this exact namespace, not a device/acquisition identifier. */
    override fun appId(scope: BranchScope): AppId = AppId(
        "ukkera-pos.rpc.branch.v1:${scope.organizationId.value}:${scope.companyId.value}:${scope.branchId.value}",
    )

    /** Initial label only; once acquired the exact retained name is used for reconstruction. */
    override fun deviceName(deviceId: DomainId): String = "Master ${deviceId.value}"
}

/**
 * Immutable original Master association DATA, distinct from a one-new-Client pairing original.
 * The original native producer authenticates it inside its first history companion. Decoding,
 * correspondence and the public fingerprint cannot establish native custody, a selected Master,
 * employee authority, fresh key generation or an active transport.
 */
public class LocalMasterSdkOriginalV1 private constructor(canonical: ByteArray) {
    private val bytes: ByteArray = canonical.copyOf()
    private val value: MasterSdkOriginalFieldsV1 = decodeMasterSdkOriginal(bytes)

    public val originalAcquisitionId: DomainId get() = value.originalAcquisitionId
    public val allocation: OriginalAllocationCommitmentV2 get() = value.allocation
    public val nativeApplication: SelfOwnedTargetDescriptorV1 get() = value.nativeApplication
    public val appId: AppId get() = value.appId
    public val peerId: PeerId get() = value.peerId
    public val fingerprint: PeerFingerprint get() = value.fingerprint
    public val pairingQr: String get() = value.pairingQr
    public val exactDeviceName: String get() = value.exactDeviceName
    public val compositionProfile: LocalCompositionSdkProfileV1 get() = value.compositionProfile
    public val descriptorDigest: PayloadDigest = sha256(bytes)

    public fun matchesSdkTransportIdentity(actualAppId: AppId, actualPeerId: PeerId,
        actualFingerprint: PeerFingerprint, actualPairingQr: String): Boolean =
        appId == actualAppId && peerId == actualPeerId && fingerprint == actualFingerprint &&
            pairingQr == actualPairingQr

    public fun copyWireBytes(): ByteArray = bytes.copyOf()
    override fun toString(): String = "LocalMasterSdkOriginalV1([REDACTED])"

    public companion object {
        /** Bounded canonical DATA only. Missing native originals cannot be repaired by decoding. */
        public fun decode(canonical: ByteArray): LocalMasterSdkOriginalV1 {
            require(canonical.size in 1..LocalMasterSdkProfileV1.MAXIMUM_ORIGINAL_BYTES)
            val result = LocalMasterSdkOriginalV1(canonical)
            val encoded = encodeMasterSdkOriginal(result.value)
            try { require(encoded.contentEquals(canonical)) } finally { encoded.fill(0) }
            return result
        }

        /** Called by the actual original producer after inspecting its retained unstarted Kit. */
        internal fun capture(originalAcquisitionId: DomainId, allocation: OriginalAllocationCommitmentV2,
            nativeApplication: SelfOwnedTargetDescriptorV1, appId: AppId, peerId: PeerId,
            fingerprint: PeerFingerprint, pairingQr: String, exactDeviceName: String): LocalMasterSdkOriginalV1 {
            val fields = MasterSdkOriginalFieldsV1(originalAcquisitionId, allocation, nativeApplication,
                appId, peerId, fingerprint, pairingQr, exactDeviceName)
            val canonical = encodeMasterSdkOriginal(fields)
            return try { decode(canonical) } finally { canonical.fill(0) }
        }
    }
}

private const val MASTER_SDK_ORIGINAL_DOMAIN_V1 = "original-native-master-sdk-identity-data/v1"

private class MasterSdkOriginalFieldsV1(
    val originalAcquisitionId: DomainId,
    val allocation: OriginalAllocationCommitmentV2,
    val nativeApplication: SelfOwnedTargetDescriptorV1,
    val appId: AppId,
    val peerId: PeerId,
    val fingerprint: PeerFingerprint,
    val pairingQr: String,
    val exactDeviceName: String,
) {
    val compositionProfile: LocalCompositionSdkProfileV1

    init {
        val c = allocation.coordinates
        require(originalAcquisitionId !in listOf(c.allocationId, c.scope.organizationId, c.scope.companyId,
            c.scope.branchId, c.installationId, c.assignmentId, c.deviceId, c.storageInstanceId,
            c.storageKeyId, c.pepperKeyId, c.targetSigningKeyId, c.firstActorId))
        compositionProfile = when {
            appId == LocalMasterSdkProfileV1.appId(c.scope) -> LocalMasterSdkProfileV1
            else -> LocalTrainingSdkProfileV1.forAppId(appId, c.scope)
                ?: throw IllegalArgumentException("Master SDK AppId is neither the live nor a training composition namespace")
        }
        // The exact retained device label is original data. The profile's name is used only
        // for initial acquisition; enforcing it here would reject valid retained LIVE labels.
        LocalDevicePairingProfileV2.validateAcquisitionText(appId.value)
        LocalDevicePairingProfileV2.validateAcquisitionText(exactDeviceName)
        requireAuthenticatedSdkTransportText(peerId, fingerprint, pairingQr)
    }
}

private fun encodeMasterSdkOriginal(value: MasterSdkOriginalFieldsV1): ByteArray = selfOwnedEncode(
    MASTER_SDK_ORIGINAL_DOMAIN_V1, LocalMasterSdkProfileV1.MAXIMUM_ORIGINAL_BYTES,
) {
    string(LocalMasterSdkProfileV1.PURPOSE)
    id(value.originalAcquisitionId)
    val allocationBytes = LocalBranchInstallationWireV2.encodeAllocation(value.allocation)
    try { field(allocationBytes) } finally { allocationBytes.fill(0) }
    target(value.nativeApplication)
    string(value.appId.value); string(value.peerId.value)
    string(value.fingerprint.value); string(value.pairingQr); string(value.exactDeviceName)
}

private fun decodeMasterSdkOriginal(bytes: ByteArray): MasterSdkOriginalFieldsV1 = selfOwnedDecode(
    bytes, MASTER_SDK_ORIGINAL_DOMAIN_V1,
) {
    require(string(128) == LocalMasterSdkProfileV1.PURPOSE)
    val acquisition = id()
    val allocationBytes = field(LocalBranchInstallationWireV2.MAXIMUM_ALLOCATION_BYTES)
    val allocation = try {
        requireNotNull(LocalBranchInstallationWireV2.decodeAllocation(allocationBytes))
    } finally { allocationBytes.fill(0) }
    MasterSdkOriginalFieldsV1(acquisition, allocation, target(),
        AppId(string(LocalDevicePairingProfileV2.MAXIMUM_SDK_APP_ID_BYTES)), PeerId(string(58)),
        PeerFingerprint(string(57)), string(125), string(LocalDevicePairingProfileV2.MAXIMUM_SDK_APP_ID_BYTES))
}
