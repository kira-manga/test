package com.ukkera.pos.app.gateway.setup

import com.ukkera.pos.recovery.store.MasterBackupKeyCustody
import com.ukkera.pos.security.DesktopMasterBackupCustody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Desktop Master SERVICE backup composition: the shared JCA archive/restore files over the
 * service's own owner-only [DesktopMasterBackupCustody] directories, sealed by its non-exportable
 * OS recovery key. Every seal/open re-observes the installed service identity (fails closed).
 */
public object DesktopMasterBackups {
    public fun open(custody: DesktopMasterBackupCustody): LocalSetupMasterBackups = JcaLocalSetupMasterBackups(
        keys = DesktopMasterBackupKeys(custody),
        archiveDirectory = custody.archiveDirectory.toFile(),
        captureDirectory = custody.captureDirectory.toFile(),
        restoreDirectory = custody.restoreDirectory.toFile(),
    )
}

internal class DesktopMasterBackupKeys(private val custody: DesktopMasterBackupCustody) : MasterBackupKeyCustody {
    override val keyId: String get() = custody.keyId
    override suspend fun seal(aad: ByteArray, plaintext: ByteArray): ByteArray =
        withContext(Dispatchers.IO) { custody.seal(aad, plaintext) }
    override suspend fun open(aad: ByteArray, sealed: ByteArray): ByteArray =
        withContext(Dispatchers.IO) { custody.open(aad, sealed) }
    override fun toString(): String = "DesktopMasterBackupKeys([REDACTED])"
}
