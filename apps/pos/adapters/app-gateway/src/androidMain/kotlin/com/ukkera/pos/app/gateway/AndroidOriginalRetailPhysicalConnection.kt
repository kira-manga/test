package com.ukkera.pos.app.gateway

import android.app.Application
import com.ukkera.pos.database.AndroidOriginalLocalClient
import com.ukkera.pos.sales.store.AndroidOriginalRetailPhysicalAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Physical-only original-client composition. Call before first protected-journal enrollment.
 * An unavailable/missing physical owner produces no binding; it does not replace or erase
 * valid nonphysical client journals, revoke a session, or close the actual master service.
 */
public object AndroidOriginalRetailPhysicalConnection {
    public suspend fun acquire(application: Application, original: AndroidOriginalLocalClient,
        action: LocalOriginalClientJournalAction): LocalRetailPhysicalBinding? {
        currentCoroutineContext().ensureActive()
        if (!original.belongsTo(application)) return null
        val gate = original.physicalRecordingGate() as? AndroidOriginalPhysicalJournalExecutionGate ?: return null
        val selected = if (action == LocalOriginalClientJournalAction.ENROLL_FIRST_CLIENT)
            AndroidOriginalRetailPhysicalAction.ENROLL_ORIGINAL else AndroidOriginalRetailPhysicalAction.REOPEN_EXISTING
        return try {
            if (!gate.acquireOriginalOwner(original, selected)) return null
            currentCoroutineContext().ensureActive()
            val endpoint = original.physicalEndpoint(gate) ?: return null
            LocalRetailPhysicalBinding(endpoint, gate)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { null }
    }
}
