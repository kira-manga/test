package com.ukkera.pos.recovery.store

import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.recovery.RecoveryAdmissionState
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.NativeHistoryTableV2
import com.ukkera.pos.types.DomainId

public sealed interface MasterMaintenanceRequest {
    public val scope: BranchScope
    public data class Snapshot(override val scope: BranchScope) : MasterMaintenanceRequest
    public data class Restore(
        override val scope: BranchScope,
        public val request: ControlledLocalRestore,
    ) : MasterMaintenanceRequest
}

public data class RestoreInstallIntent(
    public val request: ControlledLocalRestore,
    public val backup: VerifiedLocalBackup,
    public val installedDatabaseSha256: String,
    public val previousBackup: VerifiedLocalBackup?,
)

/**
 * This report comes ONLY from the mandatory authenticated core-security adapter, never a request
 * flag. Counts cover every configured security table after quarantine (including its new audit).
 */
public data class StagedSecurityQuarantineReceipt(
    public val scope: BranchScope,
    public val backupId: DomainId,
    public val incidentId: DomainId,
    public val targetDeviceId: DomainId,
    public val authenticatedRowCounts: Map<String, Long>,
    public val revokedSessionCount: Long,
) {
    init { require(revokedSessionCount >= 0 && authenticatedRowCounts.values.all { it >= 0 }) }
}

/**
 * Explicitly pinned application schema supplied by the composition root, NOT learned from an
 * archive. Include every auth/history table and canonical DDL hash for each independently qualified
 * fresh/migration profile. Room identity alone does not authenticate trigger bodies. Changing a
 * version/hash requires compatibility work; this adapter never migrates/restamps an unsupported
 * backup or silently creates missing tables. Observing a hash does not qualify it for this set.
 */
public class SqliteSnapshotProfile(
    public val schemaVersion: Int,
    public val roomIdentityHash: String,
    requiredTables: Set<String>,
    securityTables: Set<String>,
    public val quarantineAuditTable: String,
    permittedSchemaSha256: Set<String>,
    scopedTables: Set<String> = requiredTables,
) {
    private val requiredValues: Set<String> = requiredTables.toSet()
    private val securityValues: Set<String> = securityTables.toSet()
    private val schemaHashValues: Set<String> = permittedSchemaSha256.toSet()
    private val scopedValues: Set<String> = scopedTables.toSet()
    public val requiredTables: Set<String> get() = requiredValues.toSet()
    public val securityTables: Set<String> get() = securityValues.toSet()
    public val permittedSchemaSha256: Set<String> get() = schemaHashValues.toSet()
    /** Unscoped history rows require complete rooted history verification, never omission. */
    public val scopedTables: Set<String> get() = scopedValues.toSet()
    init {
        require(schemaVersion > 0)
        require(roomIdentityHash.length == 32 && roomIdentityHash.all { it in "0123456789abcdef" })
        require(this.permittedSchemaSha256.size in 1..64 && this.permittedSchemaSha256.all(::isSha256))
        require(this.requiredTables.size in 12..128 && this.requiredTables.all(::validSqlName))
        require(this.requiredTables.containsAll(BASE_TABLES))
        require(this.securityTables.containsAll(BASE_SECURITY_TABLES))
        require(this.requiredTables.containsAll(this.securityTables))
        require(this.requiredTables.containsAll(this.scopedTables) && this.scopedTables.containsAll(BASE_TABLES))
        require(this.scopedTables.containsAll(this.securityTables))
        require(quarantineAuditTable in this.securityTables && quarantineAuditTable !in BASE_SECURITY_TABLES)
    }
}

/** Pins generated Room identities/table sets, not whole-schema, migration or custody qualification. */
public object LocalMasterBackupProfiles {
    public fun localAuthV5(permittedSchemaSha256: Set<String>): SqliteSnapshotProfile {
        val additional = setOf("protected_local_auth_configuration", "protected_local_refresh_issuance",
            "protected_local_auth_event", "protected_local_session_archive")
        return SqliteSnapshotProfile(5, "917ea675dbaed518193bc43cfcd19ffc", BASE_TABLES + additional,
            BASE_SECURITY_TABLES + additional, "protected_local_auth_event", permittedSchemaSha256)
    }

    /** Exact v6 only; never migrates a v5 archive or learns trusted DDL hashes from one. */
    public fun localAuthV6(permittedSchemaSha256: Set<String>): SqliteSnapshotProfile {
        val additional = setOf("protected_local_auth_configuration", "protected_local_refresh_issuance",
            "protected_local_auth_event", "protected_local_session_archive", "protected_local_action_approval")
        return SqliteSnapshotProfile(6, "c35cf1dddfea41417d2bb3d4f95ce791", BASE_TABLES + additional,
            BASE_SECURITY_TABLES + additional, "protected_local_auth_event", permittedSchemaSha256)
    }

    /**
     * Exact generated Room7 inventory. The canonical DDL hashes and complete native history
     * verification must still be supplied by qualified composition; this profile grants neither.
     */
    public fun localAuthV7(permittedSchemaSha256: Set<String>): SqliteSnapshotProfile {
        val security = localAuthV6(permittedSchemaSha256).securityTables
        val tables = NativeHistoryTableV2.entries.map { it.sqlName }.toSet()
        val scoped = NativeHistoryTableV2.entries.filter { it.application || it == NativeHistoryTableV2.HEAD }
            .map { it.sqlName }.toSet()
        return SqliteSnapshotProfile(7, "a06a6ac54e0e94955d5e8ccb3236db07", tables, security,
            "protected_local_auth_event", permittedSchemaSha256, scoped)
    }
}

internal val BASE_SECURITY_TABLES = setOf("protected_local_account", "protected_effective_policy",
    "protected_device_admission", "protected_login_throttle", "protected_local_access_issuance")
internal val BASE_TABLES = BASE_SECURITY_TABLES + setOf("branch_authority", "operation_result",
    "aggregate_state", "unique_claim", "journal_envelope", "audit_event", "outbox_intent")
internal fun validSqlName(value: String): Boolean = value.length in 1..96 &&
    value.first() in 'a'..'z' && value.all { it in 'a'..'z' || it in '0'..'9' || it == '_' }

/**
 * Cohesive native boundary for the shared local-backup workflow. File values are opaque locations,
 * NOT authorization, writer admission, protected keys, or proof of exclusive native custody.
 * Implementations provide real filesystem/SQLite/AEAD and the mandatory stopped runtime and
 * parent-side supervised quarantine. There are no accepting implementations or platform defaults.
 */
public interface LocalBackupNativePort<File : Any> {
    /** Acquire and close the actual native lock inside this callback, including cancelled returns. */
    public suspend fun <T> withStoreCustody(block: suspend () -> T): T

    /** Acquisition retains/cleans its own custody if cancellation prevents delivery of the loan. */
    public suspend fun acquireMaintenance(request: MasterMaintenanceRequest): LocalBackupMaintenanceLoan<File>

    /** Refuse unsupported quarantine/history mutation before stopping or replacing the source. */
    public suspend fun requireRestoreSupported(profile: SqliteSnapshotProfile)

    // Invocation-owned private artifacts and authenticated archive I/O, never generic path APIs.
    public fun newArchiveStage(): LocalBackupStage<File>
    public fun archiveSource(backupId: DomainId): File
    public suspend fun readArchive(source: File, expectedId: DomainId, destination: File? = null): VerifiedLocalBackup
    public suspend fun writeArchive(snapshot: File, manifest: LocalBackupManifest, target: File)
    public fun publishArchive(partialArchive: File, backupId: DomainId)
    public fun requirePin(backupId: DomainId, exactBytes: ByteArray)
    public fun pinnedBackupIds(): Set<DomainId>
    public fun archiveCandidates(): List<LocalBackupArchiveCandidate<File>>
    /** Delete only this verified unkept canonical archive and make its directory change durable. */
    public fun deleteArchive(backupId: DomainId)

    // Actual SQLite observations/transactions. Metadata decisions remain in common policy.
    public suspend fun inspectSnapshot(database: File, profile: SqliteSnapshotProfile): SnapshotInspection
    public suspend fun tableFingerprints(database: File, tables: Set<String>): Map<String, TableFingerprint>
    public suspend fun requirePreservedAudit(database: File, before: TableFingerprint)
    /** CAS the stopped private stage, close every SQLite handle, force it, then inspect read-only. */
    public suspend fun applyRestoredBranchHold(
        database: File,
        before: SnapshotInspection,
        held: RecoveryAdmissionState,
    ): SnapshotInspection
    /** Reject sidecars, force the staged file, and hash its exact installed bytes. */
    public suspend fun prepareInstalledDatabase(database: File): String

    /** Prepare the canonical private marker location beside the validated authoritative database. */
    public fun restoreTarget(database: File): LocalBackupRestoreTarget<File>
    /** Success requires authenticated receipt AND actual supervised process/containment reap. */
    public suspend fun quarantine(
        database: File,
        request: ControlledLocalRestore,
        sourceAuthority: AuthorityIdentity,
    ): StagedSecurityQuarantineReceipt
}

/** Native runtime loan; its object identity is never proof of admission, stop, or custody. */
public interface LocalBackupMaintenanceLoan<File : Any> {
    /** Validate the runtime-supplied authoritative database location and its private ancestors. */
    public fun requireDatabase(): File
    public suspend fun ensureStopped()
    /** Real consistent SQLite capture under this loan; no live-file copy or learned schema. */
    public suspend fun captureSnapshot(source: File, destination: File): CapturedSnapshot
    /** An uncertain acknowledgement also retains the durable stop before any replacement. */
    public suspend fun persistRestoreIntent(intent: RestoreInstallIntent)
    /** Confirmed native release; intent/unreaped helpers forbid resumption, failure stays visible. */
    public suspend fun close()
}

/** Exclusive invocation ownership, never adoption of a pre-existing random-looking directory. */
public interface LocalBackupStage<File : Any> {
    public val snapshot: File
    public val verificationSnapshot: File
    public val restoredDatabase: File
    public val partialArchive: File
    /** Only the invocation's flat stage; never recurse into unknown evidence or follow links. */
    public fun remove()
}

/** Owned canonical restore artifacts under actual stopped-runtime/native path custody. */
public interface LocalBackupRestoreTarget<File : Any> {
    public val database: File
    public fun pendingRestoreExists(): Boolean
    /** Bounded bytes, without parsing, repairing, clearing, or adopting another marker. */
    public fun readPendingRestore(): ByteArray
    /** Exclusive durable creation, never overwrite an existing marker. */
    public fun writePendingRestore(bytes: ByteArray)
    public fun newStage(): LocalBackupStage<File>
    public fun databaseExists(): Boolean
    public suspend fun databaseSha256(): String
    /** Atomic replacement AND directory durability in one native call; not restart/admission. */
    public fun install(stagedDatabase: File)
}

/** A bounded native directory observation; unknown/noncanonical entries are retained. */
public data class LocalBackupArchiveCandidate<File : Any>(public val name: String, public val source: File)
