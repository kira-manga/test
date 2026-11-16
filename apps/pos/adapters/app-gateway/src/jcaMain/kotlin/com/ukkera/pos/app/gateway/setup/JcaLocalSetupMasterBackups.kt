package com.ukkera.pos.app.gateway.setup

import com.ukkera.pos.recovery.store.FileMasterBackupVault
import com.ukkera.pos.recovery.store.FileMasterRestoreTarget
import com.ukkera.pos.recovery.store.FileMasterStateSnapshot
import com.ukkera.pos.recovery.store.MasterBackupKeyCustody
import com.ukkera.pos.recovery.store.MasterBackupVault
import com.ukkera.pos.recovery.store.MasterRestoreTarget
import com.ukkera.pos.recovery.store.MasterStateBackupConfiguration
import com.ukkera.pos.recovery.store.MasterStateCaptureFacts
import com.ukkera.pos.recovery.store.MasterStateSnapshot
import com.ukkera.pos.types.DomainId
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Shared Android/desktop file composition. Directories come from the platform's own owner-only
 * application storage; the restore directory must be separate from the live Master database.
 */
public class JcaLocalSetupMasterBackups(
    override val keys: MasterBackupKeyCustody,
    archiveDirectory: File,
    private val captureDirectory: File,
    restoreDirectory: File,
    override val configuration: MasterStateBackupConfiguration = MasterStateBackupConfiguration(),
) : LocalSetupMasterBackups {
    override val vault: MasterBackupVault = FileMasterBackupVault(archiveDirectory)
    override val restoreTarget: MasterRestoreTarget = FileMasterRestoreTarget(restoreDirectory)

    init {
        val restore = restoreDirectory.absoluteFile.normalize()
        require(restore != archiveDirectory.absoluteFile.normalize() && restore != captureDirectory.absoluteFile.normalize())
    }

    override suspend fun capture(master: LocalSetupMaster): MasterStateSnapshot {
        val file = withContext(Dispatchers.IO) {
            check(captureDirectory.isDirectory || captureDirectory.mkdirs())
            // Leftover copies from an interrupted capture are private, never reused.
            captureDirectory.listFiles()?.filter { it.name.startsWith(PREFIX) }?.forEach { it.delete() }
            File(captureDirectory, PREFIX + DomainId.random().value + ".db").absoluteFile
        }
        try {
            val captured = checkNotNull(master.captureFullStateSnapshot(file.path)) { "Master capture unavailable" }
            val (schema, identity) = FileMasterStateSnapshot.readSchema(file)
            check(schema == captured.schemaVersion && identity == captured.roomIdentityHash)
            val (bytes, sha256) = FileMasterStateSnapshot.measure(file)
            return FileMasterStateSnapshot(file, MasterStateCaptureFacts(captured.scope.organizationId,
                captured.scope.companyId, captured.scope.branchId, captured.installationId, captured.schemaVersion,
                captured.roomIdentityHash, captured.historyGeneration, captured.historyRecordDigest.value, bytes, sha256))
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { file.delete() }
            throw failure
        }
    }

    override fun toString(): String = "JcaLocalSetupMasterBackups([REDACTED])"

    private companion object { const val PREFIX = "capture-" }
}
