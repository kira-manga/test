package com.ukkera.pos.app.gateway

import com.ukkera.pos.database.AndroidOriginalLocalClient
import com.ukkera.pos.database.SelfOwnedMasterHostOwner
import com.ukkera.pos.sales.store.AndroidOriginalRetailPhysicalAction
import com.ukkera.pos.sales.store.DesktopOriginalRetailPhysicalRecording
import com.ukkera.pos.security.DesktopMasterClientJournalCustody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The actual original desktop Master SERVICE physical gate, registered ONCE before master
 * activation over the shared [LocalOriginalPhysicalJournalExecutionGate]. Its native acquisition
 * uses only the service's own [DesktopMasterClientJournalCustody] (owner-only directory and OS
 * key custody); there is no caller path and no UI-process store.
 */
internal class DesktopOriginalPhysicalJournalExecutionGate private constructor(
    private val host: SelfOwnedMasterHostOwner,
    private val storage: DesktopClientJournalStorage,
    private val custody: DesktopMasterClientJournalCustody,
) : LocalOriginalPhysicalJournalExecutionGate(host, DesktopOriginalRetailPhysicalRecording::createBackend) {
    internal companion object {
        /** Only the Master service's own journal storage carries the custody; others stay unavailable. */
        fun create(host: SelfOwnedMasterHostOwner, storage: DesktopClientJournalStorage): DesktopOriginalPhysicalJournalExecutionGate? {
            val custody = storage.masterService ?: return null
            return DesktopOriginalPhysicalJournalExecutionGate(host, storage, custody)
        }
    }

    /** The cohost client must use the SAME service storage this gate was installed with. */
    internal fun ownsStorage(candidate: DesktopClientJournalStorage): Boolean =
        candidate === storage && candidate.masterService === custody

    override suspend fun acquireRecording(original: AndroidOriginalLocalClient,
        action: AndroidOriginalRetailPhysicalAction): Boolean =
        DesktopOriginalRetailPhysicalRecording.acquire(host, original, backend, custody, action)

    override fun acquireRemoteRecording(original: AndroidOriginalLocalClient,
        action: com.ukkera.pos.sales.store.DistributedPhysicalNativeAction):
        com.ukkera.pos.sales.store.DistributedPhysicalMasterNativeAcquisition? =
        com.ukkera.pos.sales.store.DesktopDistributedRetailPhysicalRecording.acquireMaster(
            host, original, this, custody, action)

    override fun toString(): String = "DesktopOriginalPhysicalJournalExecutionGate([REDACTED])"
}

/**
 * Physical-only desktop original-client composition, called before first protected-journal
 * enrollment exactly like Android. An unavailable/missing physical owner produces no binding; it
 * does not replace or erase valid nonphysical client journals or close the actual master service.
 */
internal object DesktopOriginalRetailPhysicalConnection {
    suspend fun acquire(host: SelfOwnedMasterHostOwner, original: AndroidOriginalLocalClient,
        action: LocalOriginalClientJournalAction, storage: DesktopClientJournalStorage): LocalRetailPhysicalBinding? {
        currentCoroutineContext().ensureActive()
        if (!original.belongsTo(host)) return null
        val gate = original.physicalRecordingGate() as? DesktopOriginalPhysicalJournalExecutionGate ?: return null
        if (!gate.ownsStorage(storage)) return null
        val selected = if (action == LocalOriginalClientJournalAction.ENROLL_FIRST_CLIENT)
            AndroidOriginalRetailPhysicalAction.ENROLL_ORIGINAL else AndroidOriginalRetailPhysicalAction.REOPEN_EXISTING
        return try {
            // Both native namespaces are acquired under the explicit original disposition,
            // before the first protected client journal consumes first-enrollment authority.
            // The independently retained remote acquisition cannot borrow a cohost Client FD.
            gate.acquireRemoteOriginalOwner(original,
                if (selected == AndroidOriginalRetailPhysicalAction.ENROLL_ORIGINAL)
                    com.ukkera.pos.sales.store.DistributedPhysicalNativeAction.ENROLL_ORIGINAL
                else com.ukkera.pos.sales.store.DistributedPhysicalNativeAction.REOPEN_EXISTING)
            if (!gate.acquireOriginalOwner(original, selected)) return null
            currentCoroutineContext().ensureActive()
            val endpoint = original.physicalEndpoint(gate) ?: return null
            LocalRetailPhysicalBinding(endpoint, gate)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { null }
    }
}
