package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import dev.p2pkit.core.AppId
import dev.p2pkit.core.PeerFingerprint
import dev.p2pkit.core.PeerId

/**
 * Additive Windows/macOS original SDK association. V1 remains the exact Android format.
 * These immutable bytes bind an explicitly native installed target to the existing original
 * allocation and retained official SDK identity. Decoding never proves native custody, device
 * admission, employee authority, transport activation or current writer authority.
 */
public class LocalMasterSdkOriginalV2 private constructor(canonical: ByteArray) {
    private val bytes: ByteArray = canonical.copyOf()
    private val value: NativeMasterSdkOriginalFieldsV2 = decodeNativeMasterSdkOriginal(bytes)

    public val originalAcquisitionId: DomainId get() = value.originalAcquisitionId
    public val allocation: OriginalAllocationCommitmentV2 get() = value.allocation
    public val installedTarget: SelfOwnedNativeInstalledTargetBindingV1 get() = value.installedTarget
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
    override fun toString(): String = "LocalMasterSdkOriginalV2([REDACTED])"

    public companion object {
        public const val PURPOSE: String = "ORIGINAL_NATIVE_MASTER_SDK_X25519_IDENTITY_V2"
        public const val MAXIMUM_ORIGINAL_BYTES: Int = 16 * 1024

        public fun decode(canonical: ByteArray): LocalMasterSdkOriginalV2 {
            require(canonical.size in 1..MAXIMUM_ORIGINAL_BYTES)
            val result = LocalMasterSdkOriginalV2(canonical)
            val encoded = encodeNativeMasterSdkOriginal(result.value)
            try { require(encoded.contentEquals(canonical)) } finally { encoded.fill(0) }
            return result
        }

        /** The concrete native owner must retain/authenticate these bytes before SDK handoff. */
        internal fun capture(originalAcquisitionId: DomainId, allocation: OriginalAllocationCommitmentV2,
            installedTarget: SelfOwnedNativeInstalledTargetBindingV1, appId: AppId, peerId: PeerId,
            fingerprint: PeerFingerprint, pairingQr: String, exactDeviceName: String): LocalMasterSdkOriginalV2 {
            val fields = NativeMasterSdkOriginalFieldsV2(originalAcquisitionId, allocation, installedTarget,
                appId, peerId, fingerprint, pairingQr, exactDeviceName)
            val canonical = encodeNativeMasterSdkOriginal(fields)
            return try { decode(canonical) } finally { canonical.fill(0) }
        }
    }
}

private const val NATIVE_MASTER_SDK_ORIGINAL_DOMAIN_V2 = "original-native-master-sdk-identity-data/v2"

private class NativeMasterSdkOriginalFieldsV2(
    val originalAcquisitionId: DomainId,
    val allocation: OriginalAllocationCommitmentV2,
    val installedTarget: SelfOwnedNativeInstalledTargetBindingV1,
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
        compositionProfile = if (appId == LocalMasterSdkProfileV1.appId(c.scope)) LocalMasterSdkProfileV1
            else LocalTrainingSdkProfileV1.forAppId(appId, c.scope)
                ?: throw IllegalArgumentException("Master SDK AppId is outside its exact composition namespace")
        LocalDevicePairingProfileV2.validateAcquisitionText(appId.value)
        LocalDevicePairingProfileV2.validateAcquisitionText(exactDeviceName)
        requireAuthenticatedSdkTransportText(peerId, fingerprint, pairingQr)
    }
}

private fun encodeNativeMasterSdkOriginal(value: NativeMasterSdkOriginalFieldsV2): ByteArray = selfOwnedEncode(
    NATIVE_MASTER_SDK_ORIGINAL_DOMAIN_V2, LocalMasterSdkOriginalV2.MAXIMUM_ORIGINAL_BYTES,
) {
    string(LocalMasterSdkOriginalV2.PURPOSE)
    id(value.originalAcquisitionId)
    val allocation = LocalBranchInstallationWireV2.encodeAllocation(value.allocation)
    try { field(allocation) } finally { allocation.fill(0) }
    val target = value.installedTarget.copyWireBytes()
    try { field(target) } finally { target.fill(0) }
    string(value.appId.value); string(value.peerId.value)
    string(value.fingerprint.value); string(value.pairingQr); string(value.exactDeviceName)
}

private fun decodeNativeMasterSdkOriginal(bytes: ByteArray): NativeMasterSdkOriginalFieldsV2 = selfOwnedDecode(
    bytes, NATIVE_MASTER_SDK_ORIGINAL_DOMAIN_V2,
) {
    require(string(128) == LocalMasterSdkOriginalV2.PURPOSE)
    val acquisition = id()
    val allocationBytes = field(LocalBranchInstallationWireV2.MAXIMUM_ALLOCATION_BYTES)
    val allocation = try { requireNotNull(LocalBranchInstallationWireV2.decodeAllocation(allocationBytes)) }
        finally { allocationBytes.fill(0) }
    val targetBytes = field(SelfOwnedInstalledTargetBindingWireV1.MAXIMUM_BINDING_BYTES)
    val target = try {
        val decoded = SelfOwnedInstalledTargetBindingV1.decode(targetBytes)
        require(decoded is SelfOwnedInstalledIdentityResultV2.Data)
        requireNotNull(decoded.value as? SelfOwnedNativeInstalledTargetBindingV1)
    } finally { targetBytes.fill(0) }
    NativeMasterSdkOriginalFieldsV2(acquisition, allocation, target,
        AppId(string(LocalDevicePairingProfileV2.MAXIMUM_SDK_APP_ID_BYTES)), PeerId(string(58)),
        PeerFingerprint(string(57)), string(125), string(LocalDevicePairingProfileV2.MAXIMUM_SDK_APP_ID_BYTES))
}
