package com.ukkera.pos.recovery.store

import com.ukkera.pos.recovery.PreservedRecoveryObligation
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.UtcInstant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Shared production workflow, not an authorization or platform-qualification adapter. Every
 * observation and native effect comes from the mandatory real adapter under its actual custody.
 * Restores preserve later-tail evidence and install a held branch; they never admit a writer.
 */
internal class LocalBackupPolicy<File : Any>(
    private val configuration: LocalBackupConfiguration,
    private val native: LocalBackupNativePort<File>,
    private val clock: () -> UtcInstant,
) {
    private val scope = configuration.scope
    private val profile = configuration.schema
    private val snapshots = LocalBackupSnapshotPolicy(scope, profile)
    private val mutex = Mutex()

    suspend fun createBackup(): CreatedLocalBackup = operation {
        val stage = native.newArchiveStage()
        try {
            val snapshot = stage.snapshot
            val captured = withLease(MasterMaintenanceRequest.Snapshot(scope)) { lease ->
                lease.captureSnapshot(lease.requireDatabase(), snapshot)
            }
            // The source may resume now. Encryption/verification/retention use ONLY the private
            // consistent copy and must not unnecessarily keep checkout in a maintenance window.
            val created = publishCapturedSnapshot(captured, snapshot, stage)
            val retained = try {
                retention(setOf(created.manifest.backupId))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            CreatedLocalBackup(created, retained != null, retained)
        } finally { stage.remove() }
    }

    /** Verifies archive and SQLite integrity, not availability of the separately held auth keys. */
    suspend fun verifyBackup(backupId: DomainId): VerifiedLocalBackup = operation {
        verifyArchiveForUse(native.archiveSource(backupId), backupId)
    }

    /** Never compacts journals/results, removes unknown files or deletes pinned recovery evidence. */
    suspend fun applyRetention(): BackupRetentionResult = operation { retention(emptySet()) }

    /** Return is NOT restart or business admission; unknown later history remains unknown. */
    suspend fun restoreBackup(request: ControlledLocalRestore): InstalledHeldRestore = operation {
        native.requireRestoreSupported(profile)
        withLease(MasterMaintenanceRequest.Restore(scope, request)) { lease ->
            val database = lease.requireDatabase()
            val target = native.restoreTarget(database)
            if (target.pendingRestoreExists()) return@withLease replayHeldInstall(target, request, lease)
            val stage = target.newStage()
            var mayRemoveStage = true
            try {
                val stagedDatabase = stage.restoredDatabase
                val verified = native.readArchive(native.archiveSource(request.backupId), request.backupId, stagedDatabase)
                // Compatible read/retention is not an unqualified upgrade. Authenticate the old
                // bytes, then refuse before quarantine/intent/replacement if migration is absent.
                if (configuration.readProfile(verified.manifest).schemaVersion != profile.schemaVersion) {
                    fail(LocalBackupFailure.UNSUPPORTED_DATABASE)
                }
                val before = native.inspectSnapshot(stagedDatabase, profile)
                snapshots.requireManifest(before, verified.manifest)
                val beforeTables = native.tableFingerprints(stagedDatabase, profile.requiredTables)

                // Preserve the complete newer local state before any replacement, encrypted and
                // pinned. It is evidence of a possible later tail, NOT permission to replay it.
                val previous = if (target.databaseExists()) createFromStoppedDatabase(database, lease) else null
                // An exceptional/cancelled helper return is not process-reap evidence. Preserve
                // its private stage rather than unlinking a file a poisoned child might still own.
                mayRemoveStage = false
                val receipt = try {
                    native.quarantine(stagedDatabase, request, before.authority).let {
                        it.copy(authenticatedRowCounts = it.authenticatedRowCounts.toMap())
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { fail(LocalBackupFailure.SECURITY_QUARANTINE_UNAVAILABLE) }
                mayRemoveStage = true // Successful parent-side receipt includes actual helper reap.
                lease.ensureStopped()
                val afterSecurity = native.inspectSnapshot(stagedDatabase, profile)
                if (afterSecurity != before) fail(LocalBackupFailure.SECURITY_QUARANTINE_UNAVAILABLE)
                val afterTables = native.tableFingerprints(stagedDatabase, profile.requiredTables)
                snapshots.checkQuarantineReceipt(request, receipt, beforeTables, afterTables)
                native.requirePreservedAudit(stagedDatabase, beforeTables.getValue(profile.quarantineAuditTable))

                holdRestoredBranch(stagedDatabase, verified, request,
                    previous?.second?.inspection?.recovery?.obligations.orEmpty())
                val installedHash = native.prepareInstalledDatabase(stagedDatabase)
                pin(verified)
                previous?.let { pin(it.first) }
                val previousBackup = previous?.first
                val record = PendingRestoreRecord(1, request, verified, installedHash, previousBackup)
                val intent = RestoreInstallIntent(request, verified, installedHash, previousBackup)
                currentCoroutineContext().ensureActive()
                lease.ensureStopped()
                // After even an uncertain acknowledgement, the port MUST keep the durable stop.
                lease.persistRestoreIntent(intent)
                target.writePendingRestore(backupJson.encodeToString(record).encodeToByteArray())
                currentCoroutineContext().ensureActive()
                try {
                    // No cancellation gap between rename and directory durability. This is not a
                    // claim that an OS fsync/poisoned close has a bounded wall-clock return time.
                    withContext(NonCancellable) { target.install(stagedDatabase) }
                } catch (_: Exception) { fail(LocalBackupFailure.RESTORE_OUTCOME_UNCERTAIN) }
                lease.ensureStopped()
                record.receipt()
            } finally { if (mayRemoveStage) stage.remove() }
        }
    }

    private suspend fun createFromStoppedDatabase(
        database: File, lease: LocalBackupMaintenanceLoan<File>,
    ): Pair<VerifiedLocalBackup, CapturedSnapshot> {
        val stage = native.newArchiveStage()
        try {
            val snapshot = stage.snapshot
            val captured = lease.captureSnapshot(database, snapshot)
            return publishCapturedSnapshot(captured, snapshot, stage) to captured
        } finally { stage.remove() }
    }

    private suspend fun publishCapturedSnapshot(
        captured: CapturedSnapshot, snapshot: File, stage: LocalBackupStage<File>,
    ): VerifiedLocalBackup {
        val id = DomainId.random()
        val manifest = snapshots.manifest(captured, id, clock())
        snapshots.requireManifest(native.inspectSnapshot(snapshot, profile), manifest)
        val partial = stage.partialArchive
        native.writeArchive(snapshot, manifest, partial)
        val verified = native.readArchive(partial, id)
        if (verified.manifest != manifest) fail(LocalBackupFailure.INVALID_ARCHIVE)
        native.publishArchive(partial, id)
        return verified
    }

    private suspend fun verifyArchiveForUse(path: File, id: DomainId): VerifiedLocalBackup {
        val stage = native.newArchiveStage()
        try {
            val snapshot = stage.verificationSnapshot
            val result = native.readArchive(path, id, snapshot)
            val readProfile = configuration.readProfile(result.manifest)
            LocalBackupSnapshotPolicy(scope, readProfile).requireManifest(native.inspectSnapshot(snapshot, readProfile), result.manifest)
            return result
        } finally { stage.remove() }
    }

    private suspend fun holdRestoredBranch(
        database: File,
        backup: VerifiedLocalBackup,
        request: ControlledLocalRestore,
        additionalObligations: List<PreservedRecoveryObligation>,
    ) {
        val before = native.inspectSnapshot(database, profile)
        snapshots.requireManifest(before, backup.manifest)
        val held = snapshots.restoredBranchHold(before, backup, request, additionalObligations)
        val after = native.applyRestoredBranchHold(database, before, held)
        snapshots.requireAppliedHold(before, held, after)
    }

    private suspend fun replayHeldInstall(
        target: LocalBackupRestoreTarget<File>, request: ControlledLocalRestore, lease: LocalBackupMaintenanceLoan<File>,
    ): InstalledHeldRestore {
        val text = target.readPendingRestore().decodeToString(throwOnInvalidSequence = true)
        requireShallowJson(text)
        val record = backupJson.decodeFromString<PendingRestoreRecord>(text)
        if (record.version != 1 || record.request != request || backupJson.encodeToString(record) != text) {
            fail(LocalBackupFailure.RESTORE_ALREADY_PENDING)
        }
        val verified = native.readArchive(native.archiveSource(request.backupId), request.backupId)
        if (record.backup != verified || configuration.readProfile(verified.manifest).schemaVersion != profile.schemaVersion) {
            fail(LocalBackupFailure.RESTORE_ALREADY_PENDING)
        }
        record.previousBackup?.let { previous ->
            val preserved = native.readArchive(native.archiveSource(previous.manifest.backupId), previous.manifest.backupId)
            if (preserved != previous) fail(LocalBackupFailure.RESTORE_ALREADY_PENDING)
        }
        lease.ensureStopped()
        if (!target.databaseExists() || target.databaseSha256() != record.installedHash) {
            fail(LocalBackupFailure.RESTORE_ALREADY_PENDING)
        }
        val inspection = native.inspectSnapshot(target.database, profile)
        if (inspection.writable || inspection.recovery.incidentId != request.incidentId) fail(LocalBackupFailure.RESTORE_ALREADY_PENDING)
        lease.persistRestoreIntent(RestoreInstallIntent(request, verified, record.installedHash,
            record.previousBackup))
        return record.receipt()
    }

    private fun pin(backup: VerifiedLocalBackup) {
        native.requirePin(backup.manifest.backupId, localBackupPinBytes(backup))
    }

    private suspend fun retention(extraKeep: Set<DomainId>): BackupRetentionResult {
        val pinned = native.pinnedBackupIds()
        val verified = mutableListOf<VerifiedLocalBackup>()
        var unavailable = 0
        for (candidate in native.archiveCandidates()) {
            if (!candidate.name.endsWith(ARCHIVE_SUFFIX)) continue
            currentCoroutineContext().ensureActive()
            try {
                val id = DomainId(candidate.name.removeSuffix(ARCHIVE_SUFFIX))
                verified += verifyArchiveForUse(candidate.source, id)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { unavailable++ }
        }
        val ordered = verified.sortedWith(compareByDescending<VerifiedLocalBackup> { it.manifest.createdAt }
            .thenBy { it.manifest.backupId.value })
        // Keep the latest verified generation of each explicitly supported schema. A newly
        // captured current schema cannot silently retire the sole compatible older generation.
        val generations = ordered.distinctBy { it.manifest.schemaVersion }.map { it.manifest.backupId }.toSet()
        val keep = ordered.take(configuration.retainLatest).map { it.manifest.backupId }.toSet() + generations + pinned + extraKeep
        val deleted = mutableListOf<DomainId>()
        for (candidate in ordered) {
            val id = candidate.manifest.backupId
            if (id in keep) continue
            currentCoroutineContext().ensureActive()
            native.deleteArchive(id)
            deleted += id
        }
        return BackupRetentionResult(deleted.toList(), ordered.size - deleted.size, unavailable)
    }

    private suspend fun <T> withLease(
        request: MasterMaintenanceRequest, block: suspend (LocalBackupMaintenanceLoan<File>) -> T,
    ): T {
        val lease = try { native.acquireMaintenance(request) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { fail(LocalBackupFailure.MAINTENANCE_UNAVAILABLE) }
        try {
            lease.ensureStopped()
            return block(lease)
        } finally {
            // A failed release is observable. Do not swallow it or claim a joined writer.
            withContext(NonCancellable) { lease.close() }
        }
    }

    private suspend fun <T> operation(block: suspend () -> T): T {
        mutex.lock()
        try {
            return native.withStoreCustody { block() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: LocalBackupException) { throw failure }
        catch (_: Exception) { fail(LocalBackupFailure.STORAGE_UNAVAILABLE) }
        finally { mutex.unlock() }
    }
}
