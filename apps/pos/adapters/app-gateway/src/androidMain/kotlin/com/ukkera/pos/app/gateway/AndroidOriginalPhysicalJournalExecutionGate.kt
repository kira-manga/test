package com.ukkera.pos.app.gateway

import android.app.Application
import com.ukkera.pos.database.AndroidOriginalLocalClient
import com.ukkera.pos.sales.store.AndroidOriginalRetailPhysicalAction
import com.ukkera.pos.sales.store.AndroidOriginalRetailPhysicalRecording
import com.ukkera.pos.sales.store.AndroidPhysicalCompositionNamespaceV1

/** The actual original Android gate, registered ONCE before master activation, over the shared
 * [LocalOriginalPhysicalJournalExecutionGate]. Its native acquisition is the Android app-private
 * namespace and the original authenticated AndroidKeyStore key of exactly one composition. */
internal class AndroidOriginalPhysicalJournalExecutionGate private constructor(
    private val application: Application,
    internal val composition: AndroidPhysicalCompositionNamespaceV1,
) : LocalOriginalPhysicalJournalExecutionGate(application, AndroidOriginalRetailPhysicalRecording::createBackend) {
    internal companion object {
        fun create(application: Application,
            composition: AndroidPhysicalCompositionNamespaceV1 = AndroidPhysicalCompositionNamespaceV1.LIVE):
            AndroidOriginalPhysicalJournalExecutionGate =
            AndroidOriginalPhysicalJournalExecutionGate(application, composition)
    }

    /** The gate is created for exactly one already-created setup composition. */
    internal fun ownsComposition(mode: com.ukkera.pos.security.LocalSetupCompositionMode): Boolean =
        composition.mode == mode

    override suspend fun acquireRecording(original: AndroidOriginalLocalClient,
        action: AndroidOriginalRetailPhysicalAction): Boolean =
        AndroidOriginalRetailPhysicalRecording.acquire(application, original, backend, action, composition)

    override fun acquireRemoteRecording(original: AndroidOriginalLocalClient,
        action: com.ukkera.pos.sales.store.DistributedPhysicalNativeAction):
        com.ukkera.pos.sales.store.DistributedPhysicalMasterNativeAcquisition? =
        com.ukkera.pos.sales.store.AndroidDistributedRetailPhysicalRecording.acquireMaster(
            application, original, this, action, composition)

    /** Only the cohost's exact private successful entry invokes this. */
    internal suspend fun admitEmployee(original: AndroidOriginalLocalClient,
        custody: com.ukkera.pos.database.AndroidEmployeeClientCustody, native: AndroidLocalClientJournalOwner): Boolean =
        admitEmployee(original, custody, native.physicalEmployee)

    override fun toString(): String = "AndroidOriginalPhysicalJournalExecutionGate([REDACTED])"
}
