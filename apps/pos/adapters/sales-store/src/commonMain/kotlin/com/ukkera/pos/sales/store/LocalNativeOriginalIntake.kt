package com.ukkera.pos.sales.store

import com.ukkera.pos.database.NativeOriginalRetainedLease
import com.ukkera.pos.database.NativeOriginalIntakeDisposition
import com.ukkera.pos.database.PhysicalRecordingNativeCheck
import com.ukkera.pos.database.PhysicalRecordingNativeRelease

/**
 * Private actual native custody, held across the entire core P4 import. The public lease is
 * only a reference to this SAME registered object; no DTO/body/certificate creates this entry.
 * The source keeps both catalogue+journal native owners until definite outer settlement.
 */
internal class LocalNativeOriginalIntakeEntry(
    val lease: NativeOriginalRetainedLease,
    private val master: LocalPhysicalJournal,
    private val client: LocalPhysicalJournal,
    private val native: List<PhysicalSpoolLease>,
    private val denial: PhysicalJournalDenial,
) {
    private var released = false
    private var cleanupUncertain = false
    val allocationId get() = client.receipt.binding.allocationId

    fun recheck(): PhysicalRecordingNativeCheck {
        if (released || cleanupUncertain || denial.denied || lease.invocation.denied) return PhysicalRecordingNativeCheck.HELD
        val current = LocalPhysicalJournalDelivery.deliverLocalOriginal(client, master)
            as? LocalPhysicalDelivery.MasterRawRetained ?: return PhysicalRecordingNativeCheck.HELD
        val retained = current.retention
        val expected = lease.retained
        return if (!denial.denied && !lease.invocation.denied && !retained.resolution &&
            retained.requirement.canonicalBytes == expected.canonicalRequirement && retained.certificate == expected.certificate &&
            retained.originalBody == expected.originalBody &&
            master.receipt.sourceCommitment == expected.sourceRows.single { it.key.id == master.receipt.binding.allocationId }.payload &&
            client.receipt.sourceCommitment == expected.sourceRows.single { it.key.id == client.receipt.binding.allocationId }.payload)
            PhysicalRecordingNativeCheck.VERIFIED else PhysicalRecordingNativeCheck.HELD
    }

    fun settle(disposition: NativeOriginalIntakeDisposition): PhysicalRecordingNativeRelease {
        if (disposition.invocation !== lease.invocation || disposition.state == NativeOriginalIntakeDisposition.State.UNCERTAIN)
            return PhysicalRecordingNativeRelease.STORAGE_UNCERTAIN
        return release()
    }

    /** Only used by the gate before an acquisition was delivered to core; no SQL can have run. */
    fun abandonUndelivered(): PhysicalRecordingNativeRelease { lease.invocation.deny(); return release() }

    private fun release(): PhysicalRecordingNativeRelease {
        if (cleanupUncertain) return PhysicalRecordingNativeRelease.STORAGE_UNCERTAIN
        if (!released) {
            if (!releasePhysicalSpoolLeases(native)) { cleanupUncertain = true; return PhysicalRecordingNativeRelease.STORAGE_UNCERTAIN }
            released = true
        }
        // Never unlink/reclaim, clear raw evidence, free accounting resources or close owners.
        return PhysicalRecordingNativeRelease.RELEASED
    }
}
