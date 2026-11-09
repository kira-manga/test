package com.ukkera.pos.android

import android.app.Application
import com.ukkera.pos.app.gateway.setup.LocalDeviceAdmissionIntents
import com.ukkera.pos.app.gateway.setup.LocalFreshDevicePairingIntents
import com.ukkera.pos.app.gateway.setup.LocalEmployeeSetupIntents
import com.ukkera.pos.app.gateway.setup.LocalMasterLaunchRecord
import com.ukkera.pos.app.gateway.setup.LocalMasterLifecycleIntentStore
import com.ukkera.pos.app.gateway.setup.LocalSetupCoordinator
import com.ukkera.pos.security.AndroidTrainingSetupNamespaces
import com.ukkera.pos.security.LocalTrainingCompositionSlot
import kotlinx.coroutines.Deferred

/**
 * Application-process owner for an explicit synthetic training composition.
 *
 * It reuses the production shared setup coordinator, production native P1/P2 acquisition,
 * current authority/session handling and the owned Room/current-schema master through the
 * same Android factories. Only the fixed native profile, launch/intent/lifecycle namespaces
 * and client publication are training-specific. No LIVE service, STOP record or production
 * root is used or modified.
 *
 * The foreground service retains this exact owner during hosting and drains it on service
 * disposal. Its notice is stopped only after genuine close; ordinary disposal never writes STOP.
 */
internal class AndroidLocalTrainingSetupOwner(
    private val application: Application,
    val slot: LocalTrainingCompositionSlot,
) {
    val controller: LocalSetupCoordinator

    init {
        val suffix = AndroidTrainingSetupNamespaces.intentStorageSuffix(slot)
        val storage = AndroidSetupIntentStorage(application, AndroidSetupIntentRole.EMPLOYEE, suffix)
        controller = LocalSetupCoordinator(
            services = AndroidLocalSetupNativeServices(application, trainingSlot = slot),
            employeeSetupIntents = LocalEmployeeSetupIntents(storage),
            launchRecord = LocalMasterLaunchRecord(
                AndroidLocalMasterLaunchStorage(
                    application,
                    AndroidTrainingSetupNamespaces.launchDirectoryName(slot),
                ),
            ),
            execution = AndroidLocalSetupExecution(),
            beforeRetirement = { _, completion ->
                (application as PosApplication).observeTrainingRetirement(this, completion)
                AndroidLocalTrainingBranchService.stopAfter(application, this, completion)
            },
            beforeClientHosting = { AndroidLocalTrainingBranchService.start(application, this) },
            deviceAdmissionIntents = LocalDeviceAdmissionIntents(
                AndroidSetupIntentStorage(application, AndroidSetupIntentRole.DEVICE_ADMISSION, suffix)),
            freshDevicePairingIntents = LocalFreshDevicePairingIntents(
                AndroidSetupIntentStorage(application, AndroidSetupIntentRole.FRESH_DEVICE_TARGET, suffix),
                AndroidSetupIntentStorage(application, AndroidSetupIntentRole.FRESH_DEVICE_MASTER, suffix)),
            pairingDocuments = (application as PosApplication).pairingDocuments,
            lifecycleIntents = LocalMasterLifecycleIntentStore(
                AndroidLocalMasterLifecycleIntentStorage(application,
                    AndroidLifecyclePrivateNamespace.training(slot, stopBinding = false)),
            ),
            stopBindingIntents = LocalMasterLifecycleIntentStore(
                AndroidLocalMasterLifecycleIntentStorage(application,
                    AndroidLifecyclePrivateNamespace.training(slot, stopBinding = true)),
            ),
        )
    }

    fun lockPresentation(): Unit = controller.lockPresentation()
    fun reopenRetainedMaster(): Unit = controller.reopenRetainedMaster()
    fun requestRetirement(intentional: Boolean = false): Deferred<Unit> = controller.requestRetirement(intentional)
}
