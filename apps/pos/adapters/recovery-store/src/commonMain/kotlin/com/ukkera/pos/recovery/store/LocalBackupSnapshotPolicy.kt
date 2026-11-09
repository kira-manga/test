package com.ukkera.pos.recovery.store

import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.recovery.JournalPosition
import com.ukkera.pos.recovery.PreservedRecoveryObligation
import com.ukkera.pos.recovery.RecoveryAdmissionMachine
import com.ukkera.pos.recovery.RecoveryAdmissionState
import com.ukkera.pos.recovery.RecoveryCheckpoint
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

public data class TableFingerprint(public val rows: Long, public val sha256: String, public val maxRowId: Long)
public data class SnapshotInspection(
    public val authority: AuthorityIdentity,
    public val branchRevision: Revision,
    public val sequence: JournalSequence,
    public val journalHash: String,
    public val writable: Boolean,
    public val recovery: RecoveryAdmissionState,
    public val schemaSha256: String,
    public val tables: Set<String>,
)
public data class CapturedSnapshot(public val inspection: SnapshotInspection, public val size: Long,
    public val sha256: String, public val capturedAt: UtcInstant)

/** Decisions over actual native observations; no schema learning, SQL, activation, or replay. */
internal class LocalBackupSnapshotPolicy(
    private val scope: BranchScope,
    private val profile: SqliteSnapshotProfile,
) {
    fun manifest(capture: CapturedSnapshot, id: DomainId, at: UtcInstant): LocalBackupManifest = LocalBackupManifest(
        1, id, capture.capturedAt, at, scope, capture.inspection.authority, capture.inspection.branchRevision,
        capture.inspection.sequence, capture.inspection.journalHash, capture.inspection.recovery.revision,
        profile.schemaVersion, profile.roomIdentityHash, capture.inspection.schemaSha256,
        capture.size, capture.sha256, BackupHistoryKnowledge.UNKNOWN_AFTER_CAPTURED_CHECKPOINT,
    )

    fun requireManifest(inspection: SnapshotInspection, manifest: LocalBackupManifest) {
        if (manifest.scope != scope || manifest.schemaVersion != profile.schemaVersion ||
            manifest.roomIdentityHash != profile.roomIdentityHash || manifest.schemaSha256 !in profile.permittedSchemaSha256 ||
            inspection.tables != profile.requiredTables || inspection.recovery.branchId != scope.branchId ||
            inspection.authority != manifest.sourceAuthority || inspection.branchRevision != manifest.branchRevision ||
            inspection.sequence != manifest.checkpoint || inspection.journalHash != manifest.journalHash ||
            inspection.recovery.revision != manifest.sourceRecoveryRevision || inspection.schemaSha256 != manifest.schemaSha256) {
            fail(LocalBackupFailure.DATABASE_INTEGRITY_UNAVAILABLE)
        }
    }

    fun checkQuarantineReceipt(
        request: ControlledLocalRestore,
        receipt: StagedSecurityQuarantineReceipt,
        before: Map<String, TableFingerprint>,
        after: Map<String, TableFingerprint>,
    ) {
        if (receipt.scope != scope || receipt.backupId != request.backupId ||
            receipt.incidentId != request.incidentId || receipt.targetDeviceId != request.targetDeviceId ||
            receipt.authenticatedRowCounts.keys != profile.securityTables) {
            fail(LocalBackupFailure.SECURITY_QUARANTINE_UNAVAILABLE)
        }
        for (table in profile.securityTables) {
            if (receipt.authenticatedRowCounts[table] != after.getValue(table).rows) fail(LocalBackupFailure.SECURITY_QUARANTINE_UNAVAILABLE)
        }
        val mutable = setOf("protected_local_account", "protected_device_admission", profile.quarantineAuditTable)
        for (table in profile.requiredTables) {
            if (table !in mutable && before[table] != after[table]) fail(LocalBackupFailure.SECURITY_QUARANTINE_UNAVAILABLE)
        }
        for (table in mutable - profile.quarantineAuditTable) {
            if (before.getValue(table).rows != after.getValue(table).rows) fail(LocalBackupFailure.SECURITY_QUARANTINE_UNAVAILABLE)
        }
    }

    fun restoredBranchHold(
        before: SnapshotInspection,
        backup: VerifiedLocalBackup,
        request: ControlledLocalRestore,
        additionalObligations: List<PreservedRecoveryObligation>,
    ): RecoveryAdmissionState {
        val obligations = linkedMapOf<DomainId, PreservedRecoveryObligation>()
        for (obligation in before.recovery.obligations + additionalObligations) {
            val previous = obligations[obligation.obligationId]
            if (previous != null && previous != obligation) fail(LocalBackupFailure.DATABASE_INTEGRITY_UNAVAILABLE)
            if (previous == null) obligations[obligation.obligationId] = obligation
        }
        // The old observed head is NOT terminal completeness or a successor appointment.
        val checkpoint = RecoveryCheckpoint(backup.manifest.backupId,
            JournalPosition(scope.branchId, before.authority, before.sequence), backup.archiveSha256)
        return RecoveryAdmissionMachine().restore(scope.branchId, request.incidentId, checkpoint, obligations.values.toList())
    }

    fun requireAppliedHold(before: SnapshotInspection, held: RecoveryAdmissionState, after: SnapshotInspection) {
        if (after.writable || after.recovery != held || after.authority != before.authority ||
            after.branchRevision != before.branchRevision.next() || after.tables != before.tables ||
            after.sequence != before.sequence || after.journalHash != before.journalHash ||
            after.schemaSha256 != before.schemaSha256) fail(LocalBackupFailure.DATABASE_INTEGRITY_UNAVAILABLE)
    }
}
