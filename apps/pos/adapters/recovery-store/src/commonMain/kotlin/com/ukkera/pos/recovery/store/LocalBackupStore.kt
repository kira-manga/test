package com.ukkera.pos.recovery.store

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.UtcInstant
import kotlin.time.Clock

/**
 * Trusted composition configuration. Read compatibility is explicitly pinned separately from
 * the current creation profile. An older archive is never migrated, relabelled or learned as
 * trusted by reading it; unsupported restore upgrades remain unavailable.
 */
public class LocalBackupConfiguration(
    public val scope: BranchScope,
    public val schema: SqliteSnapshotProfile,
    public val retainLatest: Int = 5,
    compatibleReadProfiles: List<SqliteSnapshotProfile> = emptyList(),
) {
    private val profiles = (listOf(schema) + compatibleReadProfiles).toList()
    public val readProfiles: List<SqliteSnapshotProfile> get() = profiles.toList()

    init {
        require(retainLatest in 2..256)
        require(profiles.size in 1..64 && profiles.map { it.schemaVersion }.distinct().size == profiles.size)
        require(profiles.all { it.schemaVersion <= schema.schemaVersion })
    }

    internal fun readProfile(manifest: LocalBackupManifest): SqliteSnapshotProfile {
        if (manifest.scope != scope) fail(LocalBackupFailure.WRONG_SCOPE)
        return profiles.singleOrNull { it.schemaVersion == manifest.schemaVersion &&
            it.roomIdentityHash == manifest.roomIdentityHash && manifest.schemaSha256 in it.permittedSchemaSha256 }
            ?: fail(LocalBackupFailure.UNSUPPORTED_DATABASE)
    }
}

/**
 * Callable shared backup/verification/retention/held-restore workflow. Platforms supply actual
 * private storage, authenticated bounded archive I/O, native stopped custody, and supervised
 * security quarantine. This facade provides no keys, device authority or accepting adapters.
 */
public class LocalBackupStore<File : Any>(
    configuration: LocalBackupConfiguration,
    native: LocalBackupNativePort<File>,
    clock: () -> UtcInstant = { UtcInstant(Clock.System.now()) },
) {
    private val policy = LocalBackupPolicy(configuration, native, clock)

    public suspend fun createBackup(): CreatedLocalBackup = policy.createBackup()
    public suspend fun verifyBackup(backupId: DomainId): VerifiedLocalBackup = policy.verifyBackup(backupId)
    public suspend fun applyRetention(): BackupRetentionResult = policy.applyRetention()
    public suspend fun restoreBackup(request: ControlledLocalRestore): InstalledHeldRestore = policy.restoreBackup(request)
}
