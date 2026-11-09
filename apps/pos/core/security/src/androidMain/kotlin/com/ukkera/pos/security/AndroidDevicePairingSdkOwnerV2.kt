package com.ukkera.pos.security

import android.app.Application
import com.ukkera.pos.types.DomainId
import dev.p2pkit.core.AppId
import dev.p2pkit.core.P2pKit
import dev.p2pkit.core.P2pState
import dev.p2pkit.core.PeerAuthorizationPolicy
import dev.p2pkit.core.PeerFingerprint
import dev.p2pkit.core.PeerPairingQr
import dev.p2pkit.core.ReconnectPolicy
import dev.p2pkit.core.SecurityMode
import dev.p2pkit.core.android.P2pKitAndroid
import dev.p2pkit.transport.lan.lan
import kotlinx.coroutines.runBlocking

/** The native original's actual official Android Kit, shared by Client pairing and
 * initial Master association. No supplied Kit/provider, internal SDK
 * storage API, start/connect callback or public handle can bypass original association.
 * Construction does not start LAN. This prerequisite never exposes or admits a peer. */
internal class AndroidDevicePairingSdkOwnerV2(
    private val application: Application,
    private val work: AndroidOwnedInstallationWork,
) {
    private enum class Phase { NEW, CREATING, RETAINED, CLOSE_STARTED, CLOSED }
    private var phase = Phase.NEW
    private var kit: P2pKit? = null

    /** The native target retains THIS partial owner before calling this operation. The
     * acquisition intent has already been forced, so a lost/failed creation is not retried. */
    fun createNative(exactAppId: AppId, exactDeviceName: String) {
        check(phase == Phase.NEW)
        validateAcquisitionText(exactAppId.value); validateAcquisitionText(exactDeviceName)
        work.requireActive()
        P2pKitAndroid.initialize(application)
        phase = Phase.CREATING
        val created = P2pKit.create {
            appId = exactAppId
            deviceName = exactDeviceName
            transports { lan(application) }
            security { mode = SecurityMode.AuthenticatedV2(PeerAuthorizationPolicy.RejectUnknown) }
            lifecycle { reconnectPolicy = ReconnectPolicy.Disabled }
        }
        kit = created // Retain the exact returned owner BEFORE inspecting any public fact.
        phase = Phase.RETAINED
        work.requireActive()
        requireIdleNative()
        if (created.appId != exactAppId || created.localDeviceName != exactDeviceName)
            identityFailure()
    }

    fun captureNative(originalAcquisitionId: DomainId, originalPreparationId: DomainId,
        target: LocalDevicePairingTargetDescriptor, nativeApplication: SelfOwnedTargetDescriptorV1): LocalDevicePairingSdkIdentityV2 {
        work.requireActive(); requireIdleNative()
        val actual = checkNotNull(kit)
        val (fingerprint, qr) = captureAuthenticatedQrNative(actual)
        val result = LocalDevicePairingSdkIdentityV2.capture(originalAcquisitionId, originalPreparationId,
            target, nativeApplication, actual.appId, actual.localPeerId, fingerprint, qr)
        requireMatchesNative(result, actual.localDeviceName)
        return result
    }

    /** First actual Master association only. A Client descriptor/preparation is not
     * fabricated, and a complete protected SDK key is not evidence of fresh key birth. */
    fun captureMasterNative(originalAcquisitionId: DomainId, allocation: OriginalAllocationCommitmentV2,
        nativeApplication: SelfOwnedTargetDescriptorV1): LocalMasterSdkOriginalV1 {
        work.requireActive(); requireIdleNative()
        val actual = checkNotNull(kit)
        val (fingerprint, qr) = captureAuthenticatedQrNative(actual)
        val result = LocalMasterSdkOriginalV1.capture(originalAcquisitionId, allocation,
            nativeApplication, actual.appId, actual.localPeerId, fingerprint, qr, actual.localDeviceName)
        requireMatchesNative(result)
        return result
    }

    /** Compare actual kit-derived facts without deriving or reformatting either key. A
     * reconstructed replacement is untrusted and must be closed by the native target. */
    fun requireMatchesNative(original: LocalDevicePairingSdkIdentityV2, exactDeviceName: String) {
        work.requireActive(); requireIdleNative()
        val actual = checkNotNull(kit)
        if (!original.matchesSdkTransportIdentity(actual.appId, actual.localPeerId,
                actual.localFingerprint ?: identityFailure(), actual.localPairingQr ?: identityFailure()) ||
            actual.localDeviceName != exactDeviceName || actual.parsePeerPairingQr(original.pairingQr) != original.fingerprint)
            identityFailure()
        val qr = PeerPairingQr.parseOrNull(original.pairingQr) ?: identityFailure()
        if (qr.encode() != original.pairingQr || qr.fingerprint != original.fingerprint) identityFailure()
        requireIdleNative(); work.requireActive()
    }

    fun requireMatchesNative(original: LocalMasterSdkOriginalV1) {
        work.requireActive(); requireIdleNative()
        val actual = checkNotNull(kit)
        val (fingerprint, qr) = captureAuthenticatedQrNative(actual)
        if (!original.matchesSdkTransportIdentity(actual.appId, actual.localPeerId, fingerprint, qr) ||
            actual.localDeviceName != original.exactDeviceName ||
            actual.parsePeerPairingQr(original.pairingQr) != original.fingerprint)
            identityFailure()
        requireIdleNative(); work.requireActive()
    }

    private fun captureAuthenticatedQrNative(actual: P2pKit): Pair<PeerFingerprint, String> {
        val fingerprint = actual.localFingerprint ?: identityFailure()
        val qr = actual.localPairingQr ?: identityFailure()
        val parsed = PeerPairingQr.parseOrNull(qr) ?: identityFailure()
        if (parsed.fingerprint != fingerprint || parsed.encode() != qr || actual.parsePeerPairingQr(qr) != fingerprint)
            identityFailure()
        return fingerprint to qr
    }

    private fun requireIdleNative() {
        if (phase != Phase.RETAINED || kit?.state?.value != P2pState.Idle || kit?.sessions?.value?.isEmpty() != true)
            identityFailure()
    }

    /** stop() actually returns only after the SDK-owned teardown completes. Its published
     * Stopped state alone is not success: the official SDK can publish it and still throw.
     * This fresh blocking coroutine runs inside the already watched owned native task;
     * caller cancellation cannot abandon the exact Kit's terminal cleanup. */
    fun closeNative() {
        if (phase == Phase.CLOSED) return
        if (phase == Phase.CLOSE_STARTED || (phase == Phase.CREATING && kit == null)) {
            // No returned Kit means there is no public handle with which to confirm a
            // partial SDK constructor's cleanup. Never turn that into successful closure.
            work.nativeClosureUnknown(); selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
        }
        phase = Phase.CLOSE_STARTED
        try {
            kit?.let { actual ->
                runBlocking { actual.stop() }
                if (actual.state.value != P2pState.Stopped) {
                    work.nativeClosureUnknown(); selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
                }
            }
            kit = null; phase = Phase.CLOSED
        } catch (failure: Throwable) {
            work.nativeClosureUnknown(); throw failure
        }
    }

    override fun toString(): String = "AndroidDevicePairingSdkOwnerV2([REDACTED])"

    companion object {
        /** Native acquisition uses the official SDK's stricter wire-control constraints,
         * before IDs, persistence or construction. No text is normalized or truncated. */
        fun validateAcquisitionText(value: String): Unit = LocalDevicePairingProfileV2.validateAcquisitionText(value)
        private fun identityFailure(): Nothing = selfOwnedFail(SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN)
    }
}
