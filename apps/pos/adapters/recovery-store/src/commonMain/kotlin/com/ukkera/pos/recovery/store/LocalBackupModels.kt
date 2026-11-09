package com.ukkera.pos.recovery.store

import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.Serializable

/** A copied live head never certifies the absence of a later, unavailable tail. */
@Serializable
public enum class BackupHistoryKnowledge { UNKNOWN_AFTER_CAPTURED_CHECKPOINT }

/** Authenticated metadata, not a recovery delegation or a master appointment. */
@Serializable
public data class LocalBackupManifest(
    public val formatVersion: Int,
    public val backupId: DomainId,
    public val capturedAt: UtcInstant,
    public val createdAt: UtcInstant,
    public val scope: BranchScope,
    public val sourceAuthority: AuthorityIdentity,
    public val branchRevision: Revision,
    public val checkpoint: JournalSequence,
    public val journalHash: String,
    public val sourceRecoveryRevision: Revision,
    public val schemaVersion: Int,
    public val roomIdentityHash: String,
    public val schemaSha256: String,
    public val databaseBytes: Long,
    public val databaseSha256: String,
    public val historyKnowledge: BackupHistoryKnowledge,
) {
    init {
        require(formatVersion == 1)
        require(schemaVersion > 0 && databaseBytes >= 512)
        require(listOf(journalHash, schemaSha256, databaseSha256).all(::isSha256))
        require(roomIdentityHash.length == 32 && roomIdentityHash.all { it in "0123456789abcdef" })
    }
}

@Serializable
public data class VerifiedLocalBackup(
    public val manifest: LocalBackupManifest,
    public val archiveSha256: String,
) {
    init { require(isSha256(archiveSha256)) }
}

/** Only complete authenticated archives are retention candidates. Invalid/unavailable files stay. */
public data class BackupRetentionResult(
    public val deletedBackupIds: List<DomainId>,
    public val retainedVerifiedBackups: Int,
    public val unavailableArchives: Int,
)

public data class CreatedLocalBackup(
    public val backup: VerifiedLocalBackup,
    /** False is a separate retention failure, never an invented backup failure or success. */
    public val retentionCompleted: Boolean,
    public val retention: BackupRetentionResult?,
)

/** approvalId resolves to protected local named-user recovery evidence in the runtime port. */
@Serializable
public data class ControlledLocalRestore(
    public val backupId: DomainId,
    public val incidentId: DomainId,
    public val targetDeviceId: DomainId,
    public val approvalId: DomainId,
)

/** No active role/epoch/token is issued. The installed database and runtime remain held. */
public data class InstalledHeldRestore(
    public val backupId: DomainId,
    public val incidentId: DomainId,
    public val targetDeviceId: DomainId,
    public val installedDatabaseSha256: String,
    /** Relative artifact name retained for controlled recovery, never an executable database route. */
    public val previousDatabaseArtifact: String?,
) {
    init { require(isSha256(installedDatabaseSha256)) }
}

public enum class LocalBackupFailure {
    STORAGE_UNAVAILABLE,
    PRIVATE_FILESYSTEM_REQUIRED,
    KEY_UNAVAILABLE,
    INVALID_ARCHIVE,
    WRONG_SCOPE,
    UNSUPPORTED_DATABASE,
    DATABASE_INTEGRITY_UNAVAILABLE,
    MAINTENANCE_UNAVAILABLE,
    SECURITY_QUARANTINE_UNAVAILABLE,
    RESTORE_ALREADY_PENDING,
    RESTORE_OUTCOME_UNCERTAIN,
    CAPACITY_EXCEEDED,
}

/** Deliberately excludes provider errors, file paths, SQL, credentials and plaintext from text. */
public class LocalBackupException(public val failure: LocalBackupFailure) : Exception(failure.name)

internal fun isSha256(value: String): Boolean = value.length == 64 && value.all { it in "0123456789abcdef" }
