package com.ukkera.pos.security

import android.app.Application
import com.ukkera.pos.types.DomainId
import dev.p2pkit.core.AppId
import dev.p2pkit.core.android.P2pKitAndroid
import dev.p2pkit.transport.lan.lan

/** Android Kit configuration of the shared [DevicePairingKitOwner]; logic is shared with desktop. */
internal class AndroidDevicePairingSdkOwnerV2(
    private val application: Application,
    private val work: AndroidOwnedInstallationWork,
): SelfOwnedMasterSdkOwner {
    private val shared = DevicePairingKitOwner(
        requireActive = { work.requireActive() },
        closureUnknown = { work.nativeClosureUnknown() },
        beforeCreate = { P2pKitAndroid.initialize(application) },
        configure = { transports { lan(application) } },
    )

    override fun createNative(exactAppId: AppId, exactDeviceName: String) = shared.create(exactAppId, exactDeviceName)
    fun captureNative(originalAcquisitionId: DomainId, originalPreparationId: DomainId,
        target: LocalDevicePairingTargetDescriptor, nativeApplication: SelfOwnedTargetDescriptorV1): LocalDevicePairingSdkIdentityV2 =
        shared.captureAndroid(originalAcquisitionId, originalPreparationId, target, nativeApplication)
    override fun captureMasterNative(originalAcquisitionId: DomainId, allocation: OriginalAllocationCommitmentV2,
        nativeApplication: SelfOwnedTargetDescriptorV1): LocalMasterSdkOriginalV1 =
        shared.captureMaster(originalAcquisitionId, allocation, nativeApplication)
    fun requireMatchesNative(original: LocalDevicePairingSdkIdentityV2, exactDeviceName: String) =
        shared.requireMatches(original, exactDeviceName)
    override fun requireMatchesNative(original: LocalMasterSdkOriginalV1) = shared.requireMatchesMaster(original)
    override fun closeNative() = shared.close()

    override fun toString(): String = "AndroidDevicePairingSdkOwnerV2([REDACTED])"

    companion object {
        fun validateAcquisitionText(value: String): Unit = LocalDevicePairingProfileV2.validateAcquisitionText(value)
    }
}
