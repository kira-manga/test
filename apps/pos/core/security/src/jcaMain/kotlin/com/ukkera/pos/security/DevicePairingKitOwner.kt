package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import dev.p2pkit.core.AppId
import dev.p2pkit.core.P2pKit
import dev.p2pkit.core.P2pState
import dev.p2pkit.core.PeerAuthorizationPolicy
import dev.p2pkit.core.PeerFingerprint
import dev.p2pkit.core.PeerPairingQr
import dev.p2pkit.core.ReconnectPolicy
import dev.p2pkit.core.SecurityMode
import dev.p2pkit.core.dsl.P2pKitBuilder
import kotlinx.coroutines.runBlocking

/**
 * The native original's actual official Kit, shared by Android and desktop (identical logic; only
 * Kit transport/identity configuration is platform-supplied). No supplied Kit/provider, start/connect
 * callback or public handle can bypass original association. Construction does not start LAN and
 * never exposes or admits a peer.
 */
internal class DevicePairingKitOwner(
    private val requireActive: () -> Unit,
    private val closureUnknown: () -> Unit,
    private val beforeCreate: () -> Unit,
    private val configure: P2pKitBuilder.() -> Unit,
) {
    private enum class Phase { NEW, CREATING, RETAINED, CLOSE_STARTED, CLOSED }
    private var phase = Phase.NEW
    private var kit: P2pKit? = null

    /** The acquisition intent has already been forced, so a lost/failed creation is not retried. */
    fun create(exactAppId: AppId, exactDeviceName: String) {
        check(phase == Phase.NEW)
        LocalDevicePairingProfileV2.validateAcquisitionText(exactAppId.value)
        LocalDevicePairingProfileV2.validateAcquisitionText(exactDeviceName)
        requireActive()
        beforeCreate()
        phase = Phase.CREATING
        val platformConfiguration = configure
        val created = P2pKit.create {
            appId = exactAppId
            deviceName = exactDeviceName
            platformConfiguration()
            security { mode = SecurityMode.AuthenticatedV2(PeerAuthorizationPolicy.RejectUnknown) }
            lifecycle { reconnectPolicy = ReconnectPolicy.Disabled }
        }
        kit = created // Retain the exact returned owner BEFORE inspecting any public fact.
        phase = Phase.RETAINED
        requireActive()
        requireIdle()
        if (created.appId != exactAppId || created.localDeviceName != exactDeviceName) identityFailure()
    }

    /** Android Client capture with its original application descriptor. */
    fun captureAndroid(originalAcquisitionId: DomainId, originalPreparationId: DomainId,
        target: LocalDevicePairingTargetDescriptor, nativeApplication: SelfOwnedTargetDescriptorV1): LocalDevicePairingSdkIdentityV2 =
        capture(target) { actual, fingerprint, qr ->
            LocalDevicePairingSdkIdentityV2.capture(originalAcquisitionId, originalPreparationId, target, nativeApplication,
                actual.appId, actual.localPeerId, fingerprint, qr)
        }

    /** Desktop Client capture with its installed packaged identity. */
    fun captureDesktop(originalAcquisitionId: DomainId, originalPreparationId: DomainId,
        target: LocalDevicePairingTargetDescriptor, installed: SelfOwnedInstalledIdentityTargetV2): LocalDevicePairingSdkIdentityV2 =
        capture(target) { actual, fingerprint, qr ->
            LocalDevicePairingSdkIdentityV2.captureDesktop(originalAcquisitionId, originalPreparationId, target, installed,
                actual.appId, actual.localPeerId, fingerprint, qr)
        }

    private fun capture(target: LocalDevicePairingTargetDescriptor,
        build: (P2pKit, PeerFingerprint, String) -> LocalDevicePairingSdkIdentityV2): LocalDevicePairingSdkIdentityV2 {
        requireActive(); requireIdle()
        val actual = checkNotNull(kit)
        val (fingerprint, qr) = captureAuthenticatedQr(actual)
        val result = build(actual, fingerprint, qr)
        requireMatches(result, actual.localDeviceName)
        return result
    }

    /** First actual Master association only (Android application facts). */
    fun captureMaster(originalAcquisitionId: DomainId, allocation: OriginalAllocationCommitmentV2,
        nativeApplication: SelfOwnedTargetDescriptorV1): LocalMasterSdkOriginalV1 {
        requireActive(); requireIdle()
        val actual = checkNotNull(kit)
        val (fingerprint, qr) = captureAuthenticatedQr(actual)
        val result = LocalMasterSdkOriginalV1.capture(originalAcquisitionId, allocation,
            nativeApplication, actual.appId, actual.localPeerId, fingerprint, qr, actual.localDeviceName)
        requireMatchesMaster(result)
        return result
    }

    /** Compare actual kit-derived facts without deriving or reformatting either key. */
    fun requireMatches(original: LocalDevicePairingSdkIdentityV2, exactDeviceName: String) {
        requireActive(); requireIdle()
        val actual = checkNotNull(kit)
        if (!original.matchesSdkTransportIdentity(actual.appId, actual.localPeerId,
                actual.localFingerprint ?: identityFailure(), actual.localPairingQr ?: identityFailure()) ||
            actual.localDeviceName != exactDeviceName || actual.parsePeerPairingQr(original.pairingQr) != original.fingerprint)
            identityFailure()
        val qr = PeerPairingQr.parseOrNull(original.pairingQr) ?: identityFailure()
        if (qr.encode() != original.pairingQr || qr.fingerprint != original.fingerprint) identityFailure()
        requireIdle(); requireActive()
    }

    fun requireMatchesMaster(original: LocalMasterSdkOriginalV1) {
        requireActive(); requireIdle()
        val actual = checkNotNull(kit)
        val (fingerprint, qr) = captureAuthenticatedQr(actual)
        if (!original.matchesSdkTransportIdentity(actual.appId, actual.localPeerId, fingerprint, qr) ||
            actual.localDeviceName != original.exactDeviceName ||
            actual.parsePeerPairingQr(original.pairingQr) != original.fingerprint)
            identityFailure()
        requireIdle(); requireActive()
    }

    private fun captureAuthenticatedQr(actual: P2pKit): Pair<PeerFingerprint, String> {
        val fingerprint = actual.localFingerprint ?: identityFailure()
        val qr = actual.localPairingQr ?: identityFailure()
        val parsed = PeerPairingQr.parseOrNull(qr) ?: identityFailure()
        if (parsed.fingerprint != fingerprint || parsed.encode() != qr || actual.parsePeerPairingQr(qr) != fingerprint)
            identityFailure()
        return fingerprint to qr
    }

    private fun requireIdle() {
        if (phase != Phase.RETAINED || kit?.state?.value != P2pState.Idle || kit?.sessions?.value?.isEmpty() != true)
            identityFailure()
    }

    /** stop() returns only after SDK-owned teardown; a Stopped state alone is not success. */
    fun close() {
        if (phase == Phase.CLOSED) return
        if (phase == Phase.CLOSE_STARTED || (phase == Phase.CREATING && kit == null)) {
            // No returned Kit: there is no public handle to confirm partial constructor cleanup.
            closureUnknown(); selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
        }
        phase = Phase.CLOSE_STARTED
        try {
            kit?.let { actual ->
                runBlocking { actual.stop() }
                if (actual.state.value != P2pState.Stopped) {
                    closureUnknown(); selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
                }
            }
            kit = null; phase = Phase.CLOSED
        } catch (failure: Throwable) {
            closureUnknown(); throw failure
        }
    }

    override fun toString(): String = "DevicePairingKitOwner([REDACTED])"

    private companion object {
        fun identityFailure(): Nothing = selfOwnedFail(SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN)
    }
}
