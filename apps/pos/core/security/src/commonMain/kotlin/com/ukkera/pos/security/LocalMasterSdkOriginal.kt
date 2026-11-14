package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import dev.p2pkit.core.AppId
import dev.p2pkit.core.PeerFingerprint
import dev.p2pkit.core.PeerId

/**
 * Immutable original Master association DATA in either exact persisted format: V1 for the
 * Android Master (bytes unchanged) or V2 for a Windows/macOS native Master. The format is
 * selected only by the framed domain of retained bytes; it is never inferred, converted or
 * repaired. Holding either value grants no custody, admission, employee or writer authority.
 */
public sealed interface LocalMasterSdkOriginal {
    public val formatVersion: Int
    public val originalAcquisitionId: DomainId
    public val allocation: OriginalAllocationCommitmentV2
    /** Android legacy target for V1; native Windows/macOS target for V2. */
    public val installedTarget: SelfOwnedInstalledTargetBindingV1
    public val appId: AppId
    public val peerId: PeerId
    public val fingerprint: PeerFingerprint
    public val pairingQr: String
    public val exactDeviceName: String
    public val compositionProfile: LocalCompositionSdkProfileV1
    public val descriptorDigest: PayloadDigest

    public fun matchesSdkTransportIdentity(actualAppId: AppId, actualPeerId: PeerId,
        actualFingerprint: PeerFingerprint, actualPairingQr: String): Boolean =
        appId == actualAppId && peerId == actualPeerId && fingerprint == actualFingerprint &&
            pairingQr == actualPairingQr

    public fun copyWireBytes(): ByteArray

    public companion object {
        public const val MAXIMUM_ORIGINAL_BYTES: Int = 16 * 1024
        internal const val DOMAIN_V1: String = "original-native-master-sdk-identity-data/v1"
        internal const val DOMAIN_V2: String = "original-native-master-sdk-identity-data/v2"

        /** Exact-format decode; an unknown domain/version or noncanonical bytes are refused. */
        public fun decode(canonical: ByteArray): LocalMasterSdkOriginal {
            require(canonical.size in 1..MAXIMUM_ORIGINAL_BYTES)
            val reader = SelfOwnedWireReaderV1(canonical)
            require(reader.string(128) == SelfOwnedInstallationProfileV1.ID)
            return when (reader.string(128)) {
                DOMAIN_V1 -> LocalMasterSdkOriginalV1.decode(canonical)
                DOMAIN_V2 -> LocalMasterSdkOriginalV2.decode(canonical)
                else -> throw IllegalArgumentException("Unknown Master SDK original format")
            }
        }

        /** Same digest semantics as each version's [descriptorDigest]. */
        public fun same(left: LocalMasterSdkOriginal?, right: LocalMasterSdkOriginal?): Boolean =
            left === right || (left != null && right != null && left.formatVersion == right.formatVersion &&
                left.descriptorDigest == right.descriptorDigest)
    }
}
