package com.ukkera.pos.recovery.store

import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.recovery.RecoveryAdmissionState
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.UtcInstant
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Actual local filesystem/SQLite/AEAD adapter for the shared backup/recovery workflow. It is
 * callable only behind the trusted runtime's authorization/maintenance boundary. No public route,
 * OS key provisioner or master activator is supplied here. All required ports are explicit;
 * none has a no-op/accepting fallback.
 */
public class JvmLocalBackupStore(
    configuration: JvmLocalBackupConfiguration,
    keys: ProtectedBackupKeyProvider,
    maintenance: MasterMaintenancePort,
    quarantine: StagedSecurityQuarantine,
    historyVerification: LocalBackupHistoryVerification?,
    clock: () -> UtcInstant = { UtcInstant(Clock.System.now()) },
) {
    /** Preserve the existing JVM constructor and clock/trailing-lambda callers. */
    public constructor(
        configuration: JvmLocalBackupConfiguration,
        keys: ProtectedBackupKeyProvider,
        maintenance: MasterMaintenancePort,
        quarantine: StagedSecurityQuarantine,
        clock: () -> UtcInstant = { UtcInstant(Clock.System.now()) },
    ) : this(configuration, keys, maintenance, quarantine, null, clock)

    private val policy = LocalBackupStore(configuration.policyConfiguration,
        JvmLocalBackupNativePort(configuration, keys, maintenance, quarantine, clock, historyVerification), clock)

    public suspend fun createBackup(): CreatedLocalBackup = policy.createBackup()

    /** Verifies archive and SQLite integrity, not availability of the separately held auth keys. */
    public suspend fun verifyBackup(backupId: DomainId): VerifiedLocalBackup = policy.verifyBackup(backupId)

    /** Never compacts journals/results, removes unknown files or deletes pinned recovery evidence. */
    public suspend fun applyRetention(): BackupRetentionResult = policy.applyRetention()

    /**
     * Installs a verified, security-quarantined, whole-branch-held database. Return is NOT restart
     * or business admission. Unknown later history remains unknown. A corrupt existing target is
     * not overwritten: an operator must preserve it and use a separately stopped empty target.
     */
    public suspend fun restoreBackup(request: ControlledLocalRestore): InstalledHeldRestore = policy.restoreBackup(request)
}

/** Native effects only; the facade and this adapter do not retain a second workflow algorithm. */
private class JvmLocalBackupNativePort(
    private val configuration: JvmLocalBackupConfiguration,
    keys: ProtectedBackupKeyProvider,
    private val maintenance: MasterMaintenancePort,
    private val quarantine: StagedSecurityQuarantine,
    clock: () -> UtcInstant,
    historyVerification: LocalBackupHistoryVerification?,
) : LocalBackupNativePort<Path> {
    private val archive = BackupArchive(configuration, keys)
    private val sqlite = SqliteBackupSnapshot(configuration, clock, historyVerification)

    override suspend fun <T> withStoreCustody(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        privateDirectory(configuration.backupDirectory, create = true)
        val lockPath = configuration.backupDirectory.resolve("store.lock")
        // Acquire and own the native lock inside the original IO scope, never dispatch a held
        // lock back to a cancelled recipient before a native owner can close it.
        JvmPrivateFileLock.acquire(lockPath).use { block() }
    }

    override suspend fun acquireMaintenance(request: MasterMaintenanceRequest): LocalBackupMaintenanceLoan<Path> {
        val lease = maintenance.acquire(request)
        // Direct non-suspending wrapping: no new dispatcher/cancellation handoff. The mandatory
        // acquisition port protects cancelled delivery, and common finally owns confirmed close.
        return JvmLocalBackupMaintenanceLoan(configuration, lease, sqlite)
    }

    override suspend fun requireRestoreSupported(profile: SqliteSnapshotProfile) {
        // Room7 quarantine and branch hold must be captured by the genuine native history
        // owner. The legacy helper/raw UPDATE cannot manufacture that original history.
        if (profile.schemaVersion >= 7) fail(LocalBackupFailure.UNSUPPORTED_DATABASE)
    }

    override fun newArchiveStage(): LocalBackupStage<Path> = newLocalBackupStage(configuration.backupDirectory)

    override fun archiveSource(backupId: DomainId): Path =
        configuration.backupDirectory.resolve(backupId.value + ARCHIVE_SUFFIX)

    override suspend fun readArchive(source: Path, expectedId: DomainId, destination: Path?): VerifiedLocalBackup =
        archive.read(source, expectedId, destination)

    override suspend fun writeArchive(snapshot: Path, manifest: LocalBackupManifest, target: Path) {
        archive.write(snapshot, manifest, target)
    }

    override fun publishArchive(partialArchive: Path, backupId: DomainId) {
        atomicPublish(partialArchive, archiveSource(backupId))
    }

    override fun requirePin(backupId: DomainId, exactBytes: ByteArray) {
        val directory = privateDirectory(configuration.backupDirectory.resolve("pins"), create = true)
        val target = directory.resolve(backupId.value + ".pin")
        if (evidencePathExists(target)) {
            if (!readBounded(target, 65).contentEquals(exactBytes)) fail(LocalBackupFailure.STORAGE_UNAVAILABLE)
        } else writePrivateDurable(target, exactBytes)
    }

    override fun pinnedBackupIds(): Set<DomainId> {
        val pinned = mutableSetOf<DomainId>()
        val pins = configuration.backupDirectory.resolve("pins")
        if (evidencePathExists(pins)) {
            privateDirectory(pins)
            for (path in listBounded(pins)) {
                val id = pinnedLocalBackupId(path.fileName.toString())
                val hash = readBounded(path, 65).toString(Charsets.US_ASCII)
                requireLocalBackupPinHash(hash)
                pinned += id
            }
        }
        return pinned
    }

    override fun archiveCandidates(): List<LocalBackupArchiveCandidate<Path>> =
        listBounded(configuration.backupDirectory).map { LocalBackupArchiveCandidate(it.fileName.toString(), it) }

    override fun deleteArchive(backupId: DomainId) {
        Files.delete(archiveSource(backupId))
        forceDirectory(configuration.backupDirectory)
    }

    override suspend fun inspectSnapshot(database: Path, profile: SqliteSnapshotProfile): SnapshotInspection =
        sqlite.inspect(database, profile)

    override suspend fun tableFingerprints(database: Path, tables: Set<String>): Map<String, TableFingerprint> =
        sqlite.fingerprints(database, tables)

    override suspend fun requirePreservedAudit(database: Path, before: TableFingerprint) {
        sqlite.requirePreservedAudit(database, before)
    }

    override suspend fun applyRestoredBranchHold(
        database: Path,
        before: SnapshotInspection,
        held: RecoveryAdmissionState,
    ): SnapshotInspection = sqlite.applyRestoredBranchHold(database, before, held)

    override suspend fun prepareInstalledDatabase(database: Path): String {
        assertNoSqliteSidecars(database)
        forceFile(database)
        return fileSha256(database, configuration.maxDatabaseBytes)
    }

    override fun restoreTarget(database: Path): LocalBackupRestoreTarget<Path> = JvmLocalBackupRestoreTarget(configuration, database)

    override suspend fun quarantine(
        database: Path,
        request: ControlledLocalRestore,
        sourceAuthority: AuthorityIdentity,
    ): StagedSecurityQuarantineReceipt = quarantine.quarantine(
        StagedSecurityQuarantineRequest(database, configuration.scope, request.backupId,
            request.incidentId, sourceAuthority, request.targetDeviceId, request.approvalId))
}

private class JvmLocalBackupMaintenanceLoan(
    private val configuration: JvmLocalBackupConfiguration,
    private val lease: MasterMaintenanceLease,
    private val sqlite: SqliteBackupSnapshot,
) : LocalBackupMaintenanceLoan<Path> {
    override fun requireDatabase(): Path {
        val path = lease.databasePath
        if (lease.scope != configuration.scope || !path.isAbsolute || path != path.normalize() || path.parent == null ||
            path.fileName.toString() == ":memory:" || path.startsWith(configuration.backupDirectory) || Files.isSymbolicLink(path)) {
            fail(LocalBackupFailure.MAINTENANCE_UNAVAILABLE)
        }
        privateDirectory(path.parent)
        return path
    }

    override suspend fun ensureStopped() { lease.ensureStopped() }

    override suspend fun captureSnapshot(source: Path, destination: Path): CapturedSnapshot =
        sqlite.capture(source, destination, lease)

    override suspend fun persistRestoreIntent(intent: RestoreInstallIntent) { lease.persistRestoreIntent(intent) }

    override suspend fun close() { lease.close() }
}

private class JvmLocalBackupStage(private val directory: Path) : LocalBackupStage<Path> {
    override val snapshot: Path = directory.resolve("snapshot.db")
    override val verificationSnapshot: Path = directory.resolve("verify.db")
    override val restoredDatabase: Path = directory.resolve("master.db")
    override val partialArchive: Path = directory.resolve("archive.partial")

    override fun remove() { removeStaging(directory) }
}

private fun newLocalBackupStage(parent: Path): LocalBackupStage<Path> {
    // Exclusive creation establishes invocation ownership; an existing directory is NEVER
    // adopted merely because its random-looking name and permissions happen to match.
    val directory = Files.createTempDirectory(privateDirectory(parent), ".backup-stage-",
        PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY))
    forceDirectory(parent)
    return JvmLocalBackupStage(privateDirectory(directory))
}

private class JvmLocalBackupRestoreTarget(
    private val configuration: JvmLocalBackupConfiguration,
    override val database: Path,
) : LocalBackupRestoreTarget<Path> {
    private val marker: Path

    init {
        privateDirectory(database.parent.resolve(".ukkera-local-recovery"), create = true)
        marker = localRestoreMarkerPath(database)
    }

    override fun pendingRestoreExists(): Boolean = evidencePathExists(marker)

    override fun readPendingRestore(): ByteArray = readBounded(marker, MAX_MANIFEST_BYTES)

    override fun writePendingRestore(bytes: ByteArray) { writePrivateDurable(marker, bytes) }

    override fun newStage(): LocalBackupStage<Path> = newLocalBackupStage(database.parent)

    override fun databaseExists(): Boolean = evidencePathExists(database)

    override suspend fun databaseSha256(): String = fileSha256(database, configuration.maxDatabaseBytes)

    override fun install(stagedDatabase: Path) { atomicPublish(stagedDatabase, database, replace = true) }
}
