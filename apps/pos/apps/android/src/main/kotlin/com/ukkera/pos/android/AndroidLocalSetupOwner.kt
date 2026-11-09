package com.ukkera.pos.android

import android.app.Application
import com.ukkera.pos.app.gateway.setup.LocalEmployeeSetupIntents
import com.ukkera.pos.app.gateway.setup.LocalDeviceAdmissionIntents
import com.ukkera.pos.app.gateway.setup.LocalFreshDevicePairingIntents
import com.ukkera.pos.app.gateway.setup.LocalMasterLaunchRecord
import com.ukkera.pos.app.gateway.setup.LocalSetupCoordinator
import kotlinx.coroutines.Deferred

/** Android composition only. Setup, original-ID recovery and lifetime transitions are shared. */
internal class AndroidLocalSetupOwner(
    application: Application,
    beforeRetirement: (AndroidLocalSetupOwner, Deferred<Unit>) -> Unit,
    beforeClientHosting: () -> Unit,
) {
    val controller = LocalSetupCoordinator(
        services = AndroidLocalSetupNativeServices(application),
        employeeSetupIntents = LocalEmployeeSetupIntents(AndroidSetupIntentStorage(application)),
        launchRecord = LocalMasterLaunchRecord(AndroidLocalMasterLaunchStorage(application)),
        execution = AndroidLocalSetupExecution(),
        beforeRetirement = { _, completion -> beforeRetirement(this, completion) },
        beforeClientHosting = beforeClientHosting,
        deviceAdmissionIntents = LocalDeviceAdmissionIntents(AndroidSetupIntentStorage(application, AndroidSetupIntentRole.DEVICE_ADMISSION)),
        freshDevicePairingIntents = LocalFreshDevicePairingIntents(
            AndroidSetupIntentStorage(application, AndroidSetupIntentRole.FRESH_DEVICE_TARGET),
            AndroidSetupIntentStorage(application, AndroidSetupIntentRole.FRESH_DEVICE_MASTER)),
        pairingDocuments = (application as PosApplication).pairingDocuments,
    )

    fun lockPresentation(): Unit = controller.lockPresentation()
    fun reopenRetainedMaster(): Unit = controller.reopenRetainedMaster()
    fun requestRetirement(intentional: Boolean = false): Deferred<Unit> = controller.requestRetirement(intentional)
}
