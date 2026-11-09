package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import dev.p2pkit.core.AppId
import dev.p2pkit.core.PeerFingerprint
import dev.p2pkit.core.PeerId

/** Additive SDK-bound pairing profile; existing V1 originals retain their exact bytes. */
public object LocalDevicePairingProfileV2 {
    public const val PURPOSE: String = "EXPLICIT_NAMED_COMPANY_HEAD_ONE_NEW_DEVICE_NATIVE_P256_SDK_X25519_PAIRING_V2"
    public const val UNSIGNED_RETIREMENT_PURPOSE: String =
        "EXPLICIT_ORIGINAL_KNOWN_UNSIGNED_NATIVE_P256_SDK_X25519_DEVICE_PAIRING_RETIREMENT_DATA_V2"
    public const val MAXIMUM_SDK_IDENTITY_BYTES: Int = 16 * 1024
    public const val MAXIMUM_SDK_APP_ID_BYTES: Int = 255

    /** Exact reviewed SDK AppId/name input. No trimming, normalization or truncation. */
    public fun validateAcquisitionText(value: String) {
        require(value.length in 1..MAXIMUM_SDK_APP_ID_BYTES && value.isNotBlank())
        selfOwnedUtf16(value)
        val encoded = value.encodeToByteArray()
        try { require(encoded.size in 1..MAXIMUM_SDK_APP_ID_BYTES) }
        finally { encoded.fill(0) }
        require(value.none { code -> code.code in 0x00..0x1f || code.code in 0x7f..0x9f ||
            code.code == 0x061c || code.code == 0x200e || code.code == 0x200f ||
            code.code in 0x202a..0x202e || code.code in 0x2066..0x2069 })
    }
}

/**
 * Immutable public association DATA, not proof of key custody, admission or a live peer.
 * The original native owner must compare these SDK facts with its actual official Kit.
 * Parsing checks canonical syntax and QR/fingerprint correspondence only: it cannot prove
 * AppId/PeerId/key correspondence or authorize remote login and private disclosure.
 */
public class LocalDevicePairingSdkIdentityV2 private constructor(canonical: ByteArray) {
    private val bytes = canonical.copyOf()
    private val value = decodeFields(bytes)

    public val originalAcquisitionId: DomainId get() = value.originalAcquisitionId
    public val originalPreparationId: DomainId get() = value.originalPreparationId
    public val target: LocalDevicePairingTargetDescriptor get() = value.target
    public val nativeApplication: SelfOwnedTargetDescriptorV1 get() = value.nativeApplication
    public val appId: AppId get() = value.appId
    public val peerId: PeerId get() = value.peerId
    public val fingerprint: PeerFingerprint get() = value.fingerprint
    public val pairingQr: String get() = value.pairingQr
    public val descriptorDigest: PayloadDigest = sha256(bytes)

    /** Exact public DATA correspondence, not proof of SDK ownership or device admission.
     * Native composition must read these facts from its retained official SDK owner before
     * using this comparison; configuration, discovery and caller-supplied copies are not proof. */
    public fun matchesSdkTransportIdentity(
        actualAppId: AppId,
        actualPeerId: PeerId,
        actualFingerprint: PeerFingerprint,
        actualPairingQr: String,
    ): Boolean = appId == actualAppId && peerId == actualPeerId &&
        fingerprint == actualFingerprint && pairingQr == actualPairingQr

    public fun copyWireBytes(): ByteArray = bytes.copyOf()
    override fun toString(): String = "LocalDevicePairingSdkIdentityV2([REDACTED])"

    public companion object {
        /** Pure bounded DATA decode. It neither reconstructs a Kit nor grants authority. */
        public fun decode(canonical: ByteArray): LocalDevicePairingSdkIdentityV2 {
            require(canonical.size in 1..LocalDevicePairingProfileV2.MAXIMUM_SDK_IDENTITY_BYTES)
            val result = LocalDevicePairingSdkIdentityV2(canonical)
            val encoded = encodeFields(result.value)
            try { require(encoded.contentEquals(canonical)) } finally { encoded.fill(0) }
            return result
        }

        /** Only the actual native owner captures its inspected Kit; still public DATA. */
        internal fun capture(
            originalAcquisitionId: DomainId,
            originalPreparationId: DomainId,
            target: LocalDevicePairingTargetDescriptor,
            nativeApplication: SelfOwnedTargetDescriptorV1,
            appId: AppId,
            peerId: PeerId,
            fingerprint: PeerFingerprint,
            pairingQr: String,
        ): LocalDevicePairingSdkIdentityV2 {
            val fields = SdkIdentityFieldsV2(originalAcquisitionId, originalPreparationId, target,
                nativeApplication, appId, peerId, fingerprint, pairingQr)
            val canonical = encodeFields(fields)
            return try { decode(canonical) } finally { canonical.fill(0) }
        }
    }
}

private const val SDK_IDENTITY_DOMAIN_V2 = "original-native-device-sdk-identity-data/v2"

private class SdkIdentityFieldsV2(
    val originalAcquisitionId: DomainId,
    val originalPreparationId: DomainId,
    val target: LocalDevicePairingTargetDescriptor,
    val nativeApplication: SelfOwnedTargetDescriptorV1,
    val appId: AppId,
    val peerId: PeerId,
    val fingerprint: PeerFingerprint,
    val pairingQr: String,
) {
    init {
        val scope = target.admission.scope
        val ids = listOf(originalAcquisitionId, originalPreparationId, scope.organizationId,
            scope.companyId, scope.branchId, target.admission.deviceId, target.keyId)
        require(ids.distinct().size == ids.size)
        require(appId.value.length in 1..LocalDevicePairingProfileV2.MAXIMUM_SDK_APP_ID_BYTES)
        val encodedAppId = appId.value.encodeToByteArray()
        try { require(encodedAppId.size in 1..LocalDevicePairingProfileV2.MAXIMUM_SDK_APP_ID_BYTES) }
        finally { encodedAppId.fill(0) }
        requireAuthenticatedSdkTransportText(peerId, fingerprint, pairingQr)
    }
}

private fun encodeFields(value: SdkIdentityFieldsV2): ByteArray = selfOwnedEncode(
    SDK_IDENTITY_DOMAIN_V2, LocalDevicePairingProfileV2.MAXIMUM_SDK_IDENTITY_BYTES,
) {
    string(LocalDevicePairingProfileV2.PURPOSE)
    id(value.originalAcquisitionId); id(value.originalPreparationId)
    val targetBytes = value.target.copyWireBytes()
    try { field(targetBytes) } finally { targetBytes.fill(0) }
    target(value.nativeApplication)
    string(value.appId.value); string(value.peerId.value)
    string(value.fingerprint.value); string(value.pairingQr)
}

private fun decodeFields(bytes: ByteArray): SdkIdentityFieldsV2 = selfOwnedDecode(bytes, SDK_IDENTITY_DOMAIN_V2) {
    require(string(128) == LocalDevicePairingProfileV2.PURPOSE)
    val acquisition = id(); val preparation = id()
    val targetBytes = field(ProtectedLocalTable.ADMISSION.maximumPlaintextBytes + 1024)
    val descriptor = try { LocalDevicePairingTargetDescriptor.decode(targetBytes) } finally { targetBytes.fill(0) }
    SdkIdentityFieldsV2(acquisition, preparation, descriptor, target(),
        AppId(string(LocalDevicePairingProfileV2.MAXIMUM_SDK_APP_ID_BYTES)),
        PeerId(string(58)), PeerFingerprint(string(57)), string(125))
}
