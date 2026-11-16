package com.ukkera.pos.android

import android.app.Application
import com.ukkera.pos.app.gateway.setup.LocalEmployeeSetupIntents
import com.ukkera.pos.app.gateway.setup.LocalDeviceAdmissionIntents
import com.ukkera.pos.app.gateway.setup.LocalFreshDevicePairingIntents
import com.ukkera.pos.app.gateway.setup.LocalMasterLaunchRecord
import com.ukkera.pos.app.gateway.setup.LocalLanLaunchRecord
import com.ukkera.pos.app.gateway.setup.LocalMasterLifecycleIntentStore
import com.ukkera.pos.app.gateway.setup.LocalSetupCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
        lanLaunchRecord = LocalLanLaunchRecord(AndroidLocalMasterLaunchStorage(application,
            "ukkera.local-lan-launch.v1", LocalLanLaunchRecord.MAXIMUM_BYTES)),
        execution = AndroidLocalSetupExecution(),
        beforeRetirement = { _, completion -> beforeRetirement(this, completion) },
        beforeClientHosting = beforeClientHosting,
        deviceAdmissionIntents = LocalDeviceAdmissionIntents(AndroidSetupIntentStorage(application, AndroidSetupIntentRole.DEVICE_ADMISSION)),
        freshDevicePairingIntents = LocalFreshDevicePairingIntents(
            AndroidSetupIntentStorage(application, AndroidSetupIntentRole.FRESH_DEVICE_TARGET),
            AndroidSetupIntentStorage(application, AndroidSetupIntentRole.FRESH_DEVICE_MASTER)),
        pairingDocuments = (application as PosApplication).pairingDocuments,
        lifecycleIntents = LocalMasterLifecycleIntentStore(
            AndroidLocalMasterLifecycleIntentStorage(application)),
        stopBindingIntents = LocalMasterLifecycleIntentStore(
            AndroidLocalMasterLifecycleIntentStorage(application, AndroidLifecyclePrivateNamespace.STOP_BINDING)),
    )

    /** Scheduled-backup due checks only; creation goes through the operational Master (else no-op). */
    private val scheduler = CoroutineScope(SupervisorJob() + Dispatchers.IO).apply {
        launch {
            while (isActive) {
                delay(SCHEDULE_CHECK_MILLIS)
                try { controller.runScheduledMasterBackup() }
                catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
            }
        }
    }

    fun lockPresentation(): Unit = controller.lockPresentation()
    fun reopenRetainedMaster(): Unit = controller.reopenRetainedMaster()
    fun requestRetirement(intentional: Boolean = false): Deferred<Unit> {
        scheduler.cancel()
        return controller.requestRetirement(intentional)
    }

    private companion object { const val SCHEDULE_CHECK_MILLIS = 15L * 60 * 1000 }
}
