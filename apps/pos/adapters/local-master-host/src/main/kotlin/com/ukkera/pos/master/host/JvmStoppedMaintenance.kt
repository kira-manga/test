package com.ukkera.pos.master.host

import com.ukkera.pos.recovery.store.MasterMaintenanceLease
import com.ukkera.pos.recovery.store.MasterMaintenancePort
import com.ukkera.pos.recovery.store.MasterMaintenanceRequest
import com.ukkera.pos.recovery.store.QuarantineProcessCustody
import com.ukkera.pos.recovery.store.QuarantineProcessCustodyLease
import com.ukkera.pos.recovery.store.QuarantineProcessUncertainty
import com.ukkera.pos.recovery.store.RestoreInstallIntent
import com.ukkera.pos.recovery.store.StagedSecurityQuarantineRequest
import com.ukkera.pos.recovery.store.VerifiedLocalBackup
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Only JvmMasterHost creates this; a stopped flag supplied by a caller is never accepted. */
public class JvmStoppedMaintenance internal constructor(private val host: JvmMasterHost) : MasterMaintenancePort {
    override suspend fun acquire(request: MasterMaintenanceRequest): MasterMaintenanceLease {
        var acquired: MaintenanceLoan? = null
        try {
            return host.hostOperation {
                host.operations.withLock {
                    host.requireUsable()
                    val before = host.ledger.read()
                    if (request.scope != host.configuration.scope || host.maintenanceLoan != null || host.helperLoan != null ||
                        before.maintenance != null || before.helper != null) hostFail(LocalMasterHostFailure.MAINTENANCE_UNAVAILABLE)
                    if (before.intent != null && (request !is MasterMaintenanceRequest.Restore || request.request != before.intent.request)) {
                        hostFail(LocalMasterHostFailure.RESTORE_HELD)
                    }
                    val permit = try { host.authorization.authorize(request).also { it.ensureCurrent() } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Throwable) { hostFail(LocalMasterHostFailure.AUTHORIZATION_UNAVAILABLE) }
                    val selected = validatePermit(host.configuration, request, permit)
                    if (before.intent != null && selected != before.intent.backup) hostFail(LocalMasterHostFailure.AUTHORIZATION_UNAVAILABLE)
                    val loan = MaintenanceLoan(host, DomainId.random(), request, permit, selected)
                    acquired = loan
                    host.maintenanceLoan = loan
                    host.publish(LocalMasterHostPhase.STOPPING)
                    host.service?.endpoint?.stopAccepting()
                    // A crash/cancel before closure remains dirty, never a clean stopped marker.
                    host.ledger.update { it.copy(phase = RunPhase.STOPPING, maintenance = MaintenanceRecord(
                        loan.id, permit.decisionId, permit.subjectId, permit.kind.name,
                        (request as? MasterMaintenanceRequest.Restore)?.request, selected)) }
                    host.stopServiceLocked()
                    loan.ensureStoppedLocked()
                    loan
                }
            }
        } catch (failure: Throwable) {
            // Accepted acquisition runs in an owned clean context. If delivery loses to caller
            // cancellation, this still holds the exact loan and can close only after real stop.
            if (acquired != null) {
                try { withContext(NonCancellable) { acquired?.close() } }
                catch (_: Throwable) { host.retire(LocalMasterHostFailure.MAINTENANCE_UNAVAILABLE) }
            }
            if (failure is CancellationException) throw failure
            if (failure is LocalMasterHostException) throw failure
            hostFail(LocalMasterHostFailure.MAINTENANCE_UNAVAILABLE)
        }
    }
}

internal class MaintenanceLoan(
    private val host: JvmMasterHost,
    val id: DomainId,
    val request: MasterMaintenanceRequest,
    private val permit: LocalMasterMaintenancePermit,
    val selected: VerifiedLocalBackup?,
) : MasterMaintenanceLease {
    private val closed = AtomicBoolean(false)
    override val databasePath: Path get() = host.configuration.databasePath
    override val scope: BranchScope get() = host.configuration.scope

    override suspend fun ensureStopped(): Unit = host.hostOperation {
        host.operations.withLock { ensureStoppedLocked() }
    }

    suspend fun ensureStoppedLocked() {
        host.requireUsable()
        val record = host.ledger.read()
        if (closed.get() || host.maintenanceLoan !== this || host.service != null ||
            record.phase != RunPhase.MAINTENANCE || record.maintenance?.loanId != id) {
            hostFail(LocalMasterHostFailure.MAINTENANCE_UNAVAILABLE)
        }
        host.supervision.ensureExclusive()
        try { permit.ensureCurrent() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Throwable) { hostFail(LocalMasterHostFailure.AUTHORIZATION_UNAVAILABLE) }
        host.requireUsable()
    }

    override suspend fun persistRestoreIntent(intent: RestoreInstallIntent): Unit = host.hostOperation {
        host.operations.withLock {
            ensureStoppedLocked()
            val restore = request as? MasterMaintenanceRequest.Restore ?: hostFail(LocalMasterHostFailure.AUTHORIZATION_UNAVAILABLE)
            val previousBackup = intent.previousBackup
            if (host.helperLoan != null || host.ledger.read().helper != null || intent.request != restore.request ||
                intent.backup != selected || !isHostSha256(intent.installedDatabaseSha256) ||
                (previousBackup != null && previousBackup.manifest.scope != scope)) {
                hostFail(LocalMasterHostFailure.AUTHORIZATION_UNAVAILABLE)
            }
            val captured = RestoreIntentRecord.capture(intent)
            val existing = host.ledger.read().intent
            if (existing != null && existing != captured) hostFail(LocalMasterHostFailure.RESTORE_HELD)
            // Latch BEFORE disk publication. Any uncertain acknowledgement prevents close from
            // reclassifying the original source as clean/resumable, even if the disk write fails.
            host.restoreAttempted = true
            host.ledger.update { it.copy(intent = captured) }
            host.supervision.ensureExclusive()
        }
    }

    override suspend fun close(): Unit = host.hostOperation {
        host.operations.withLock {
            if (closed.get()) return@withLock
            host.requireUsable()
            val record = host.ledger.read()
            if (host.maintenanceLoan !== this || record.maintenance?.loanId != id || host.service != null ||
                record.phase != RunPhase.MAINTENANCE || host.helperLoan != null || record.helper != null) {
                host.retire(LocalMasterHostFailure.MAINTENANCE_UNAVAILABLE)
                hostFail(LocalMasterHostFailure.MAINTENANCE_UNAVAILABLE)
            }
            if (host.restoreAttempted && record.intent == null) {
                host.retire(LocalMasterHostFailure.RESTORE_HELD)
                hostFail(LocalMasterHostFailure.RESTORE_HELD)
            }
            host.supervision.ensureExclusive()
            val next = host.ledger.update { it.copy(maintenance = null,
                phase = if (it.intent != null) RunPhase.RESTORE_HELD else RunPhase.CLEAN_STOPPED) }
            closed.set(true)
            host.maintenanceLoan = null
            host.publish(if (next.intent != null) LocalMasterHostPhase.RESTORE_HELD else LocalMasterHostPhase.STOPPED)
            // Only bookkeeping ended. No service resumes and no key/admission/token is revived.
        }
    }
}

private fun validatePermit(
    configuration: JvmLocalMasterHostConfiguration,
    request: MasterMaintenanceRequest,
    permit: LocalMasterMaintenancePermit,
): VerifiedLocalBackup? {
    if (permit.request != request) hostFail(LocalMasterHostFailure.AUTHORIZATION_UNAVAILABLE)
    val selected = permit.selectedBackup
    when (request) {
        is MasterMaintenanceRequest.Snapshot -> if (selected != null) hostFail(LocalMasterHostFailure.AUTHORIZATION_UNAVAILABLE)
        is MasterMaintenanceRequest.Restore -> {
            if (permit.kind != LocalMaintenanceAuthorizationKind.PREPARED_NAMED_RECOVERY ||
                permit.decisionId != request.request.approvalId || selected == null ||
                request.request.targetDeviceId != configuration.localDeviceId ||
                selected.manifest.backupId != request.request.backupId || selected.manifest.scope != configuration.scope ||
                selected.manifest.schemaVersion != configuration.backups.schema.schemaVersion ||
                selected.manifest.roomIdentityHash != configuration.backups.schema.roomIdentityHash ||
                selected.manifest.schemaSha256 !in configuration.backups.schema.permittedSchemaSha256) {
                hostFail(LocalMasterHostFailure.AUTHORIZATION_UNAVAILABLE)
            }
        }
    }
    return selected
}

internal class JvmHostQuarantineCustody(private val host: JvmMasterHost) : QuarantineProcessCustody {
    override suspend fun acquire(request: StagedSecurityQuarantineRequest, maximumRunMillis: Long): QuarantineProcessCustodyLease {
        var acquired: HostHelperLoan? = null
        var recorded = false
        try {
            return host.hostOperation {
                host.operations.withLock {
                    val maintenance = host.maintenanceLoan ?: hostFail(LocalMasterHostFailure.MAINTENANCE_UNAVAILABLE)
                    maintenance.ensureStoppedLocked()
                    if (maximumRunMillis !in 1_000..300_000 || host.helperLoan != null || host.ledger.read().helper != null ||
                        host.ledger.read().intent != null) hostFail(LocalMasterHostFailure.HELPER_UNAVAILABLE)
                    val restore = maintenance.request as? MasterMaintenanceRequest.Restore ?: hostFail(LocalMasterHostFailure.AUTHORIZATION_UNAVAILABLE)
                    val selected = maintenance.selected ?: hostFail(LocalMasterHostFailure.AUTHORIZATION_UNAVAILABLE)
                    if (request.scope != host.configuration.scope || request.backupId != restore.request.backupId ||
                        request.incidentId != restore.request.incidentId || request.targetDeviceId != restore.request.targetDeviceId ||
                        request.approvalId != restore.request.approvalId || request.sourceAuthority != selected.manifest.sourceAuthority) {
                        hostFail(LocalMasterHostFailure.AUTHORIZATION_UNAVAILABLE)
                    }
                    bindPrivateStage(host.configuration, request.databasePath, selected)
                    maintenance.ensureStoppedLocked()
                    val id = DomainId.random()
                    host.ledger.update { it.copy(helper = HelperRecord(id, request.databasePath.toString(), maximumRunMillis)) }
                    recorded = true
                    // Durable possibly-starting child exists BEFORE the OS containment call and
                    // before ProcessBuilder.start. The real provider must cover its own handoff.
                    val containment = host.supervision.prepareQuarantine(request, maximumRunMillis)
                    RetainedHostResources.retain(containment)
                    val loan = HostHelperLoan(host, id, maintenance, containment)
                    host.helperLoan = loan
                    acquired = loan
                    loan
                }
            }
        } catch (failure: Throwable) {
            if (acquired != null) {
                // No launch can have been attempted when delivery of this lease was cancelled.
                try { withContext(NonCancellable) { acquired?.close() } }
                catch (_: Throwable) { host.retire(LocalMasterHostFailure.HELPER_UNAVAILABLE) }
            } else if (recorded) host.retire(LocalMasterHostFailure.HELPER_UNAVAILABLE)
            if (failure is CancellationException) throw failure
            if (failure is LocalMasterHostException) throw failure
            hostFail(LocalMasterHostFailure.HELPER_UNAVAILABLE)
        }
    }
}

internal class HostHelperLoan(
    private val host: JvmMasterHost,
    private val id: DomainId,
    private val maintenance: MaintenanceLoan,
    private val containment: QuarantineProcessCustodyLease,
) : QuarantineProcessCustodyLease {
    private val closed = AtomicBoolean(false)
    private val reaped = AtomicBoolean(false)
    @Volatile private var child: ProcessHandle? = null

    override suspend fun ensureStopped(): Unit = host.hostOperation {
        host.operations.withLock {
            requireTracked()
            maintenance.ensureStoppedLocked()
            containment.ensureStopped()
        }
    }

    @Synchronized override fun childStarted(child: ProcessHandle) {
        try {
            host.requireUsable()
            requireTracked()
            if (this.child != null) hostFail(LocalMasterHostFailure.HELPER_UNAVAILABLE)
            this.child = child
            containment.childStarted(child)
            val start = child.info().startInstant().orElse(null)?.toEpochMilli()
            host.ledger.update {
                val helper = it.helper ?: hostFail(LocalMasterHostFailure.HELPER_UNAVAILABLE)
                if (helper.loanId != id) hostFail(LocalMasterHostFailure.HELPER_UNAVAILABLE)
                it.copy(helper = helper.copy(processId = child.pid(), processStartEpochMillis = start))
            }
        } catch (_: Throwable) {
            host.retire(LocalMasterHostFailure.HELPER_UNAVAILABLE)
            hostFail(LocalMasterHostFailure.HELPER_UNAVAILABLE)
        }
    }

    override suspend fun confirmReaped(child: ProcessHandle): Unit = host.hostOperation {
        host.operations.withLock {
            requireTracked()
            if (this.child != child) hostFail(LocalMasterHostFailure.HELPER_UNAVAILABLE)
            // Allow actual reap bookkeeping even after host retirement; never infer it from the
            // retirement flag, PID/start metadata, a timer or an expired coroutine join.
            containment.confirmReaped(child)
            reaped.set(true)
        }
    }

    override fun retirementRequired(reason: QuarantineProcessUncertainty, child: ProcessHandle?) {
        host.retire(LocalMasterHostFailure.HELPER_UNAVAILABLE)
        try { containment.retirementRequired(reason, child) } catch (_: Throwable) { /* Loan remains. */ }
    }

    override suspend fun close(): Unit = host.hostOperation {
        host.operations.withLock {
            if (closed.get()) return@withLock
            requireTracked()
            if (child != null && !reaped.get()) {
                host.retire(LocalMasterHostFailure.HELPER_UNAVAILABLE)
                hostFail(LocalMasterHostFailure.HELPER_UNAVAILABLE)
            }
            try {
                // With no registered child the parent wrapper guarantees launch was unattempted;
                // the real containment provider must independently settle its pending launch.
                containment.close()
                host.ledger.update {
                    if (it.helper?.loanId != id) hostFail(LocalMasterHostFailure.HELPER_UNAVAILABLE)
                    it.copy(helper = null)
                }
                closed.set(true)
                host.helperLoan = null
                RetainedHostResources.release(containment)
            } catch (failure: Throwable) {
                host.retire(LocalMasterHostFailure.HELPER_UNAVAILABLE)
                if (failure is CancellationException) throw failure
                hostFail(LocalMasterHostFailure.HELPER_UNAVAILABLE)
            }
        }
    }

    private fun requireTracked() {
        if (closed.get() || host.helperLoan !== this || host.maintenanceLoan !== maintenance ||
            host.ledger.read().helper?.loanId != id) hostFail(LocalMasterHostFailure.HELPER_UNAVAILABLE)
    }
}

/** Bind actual private stage bytes to the backup already authenticated by the named ceremony. */
private fun bindPrivateStage(configuration: JvmLocalMasterHostConfiguration, path: Path, selected: VerifiedLocalBackup) {
    if (!path.isAbsolute || path != path.normalize() || path == configuration.databasePath ||
        path.fileName.toString() != "master.db" || path.parent?.parent != configuration.databasePath.parent ||
        !path.parent.fileName.toString().startsWith(".backup-stage-")) hostFail(LocalMasterHostFailure.HELPER_UNAVAILABLE)
    privateHostDirectory(path.parent)
    if (Files.exists(configuration.databasePath, NOFOLLOW_LINKS) && Files.isSameFile(path, configuration.databasePath)) {
        hostFail(LocalMasterHostFailure.HELPER_UNAVAILABLE)
    }
    val before = requirePrivateHostFile(path)
    val expectedSize = selected.manifest.databaseBytes
    if (expectedSize > configuration.backups.maxDatabaseBytes || before.size() != expectedSize) hostFail(LocalMasterHostFailure.HELPER_UNAVAILABLE)
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteBuffer.allocate(256 * 1024)
    var size = 0L
    try {
        FileChannel.open(path, setOf(READ, NOFOLLOW_LINKS)).use { input ->
            while (true) {
                buffer.clear()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                size += count
                if (size > expectedSize) hostFail(LocalMasterHostFailure.HELPER_UNAVAILABLE)
                buffer.flip()
                digest.update(buffer)
            }
        }
        val after = requirePrivateHostFile(path)
        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        if (size != expectedSize || before.fileKey() != after.fileKey() || before.size() != after.size() ||
            hash != selected.manifest.databaseSha256) hostFail(LocalMasterHostFailure.HELPER_UNAVAILABLE)
    } finally { buffer.array().fill(0) }
}
