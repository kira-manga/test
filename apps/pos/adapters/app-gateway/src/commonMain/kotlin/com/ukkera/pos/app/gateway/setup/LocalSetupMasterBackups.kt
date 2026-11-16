package com.ukkera.pos.app.gateway.setup

import com.ukkera.pos.recovery.store.MasterBackupKeyCustody
import com.ukkera.pos.recovery.store.MasterBackupVault
import com.ukkera.pos.recovery.store.MasterRestoreTarget
import com.ukkera.pos.recovery.store.MasterStateBackupConfiguration
import com.ukkera.pos.recovery.store.MasterStateSnapshot

/**
 * Platform custody for the Master's encrypted full-state backups: the platform recovery key,
 * owner-only archive vault, a separate fresh/quarantined restore target and a capture producer
 * that goes through exactly the given operational Master. Composition plumbing only: the
 * common coordinator owns scheduling, verification and the restore approval check.
 */
public interface LocalSetupMasterBackups {
    public val keys: MasterBackupKeyCustody
    public val vault: MasterBackupVault
    /** Never the live Master database location. */
    public val restoreTarget: MasterRestoreTarget
    public val configuration: MasterStateBackupConfiguration get() = MasterStateBackupConfiguration()
    /** Consistent snapshot captured through [master]'s own serialized native capture. */
    public suspend fun capture(master: LocalSetupMaster): MasterStateSnapshot
}
