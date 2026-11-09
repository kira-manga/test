package com.ukkera.pos.recovery.store

import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.KeyProtectionStatus
import com.ukkera.pos.types.DomainId
import java.nio.file.Path
import javax.crypto.SecretKey

/**
 * A protected AES-256 key handle, not an adjacent key file, password or exported OS identity key.
 * The provider must qualify its actual supervised service context and recovery custody. A status
 * enum alone is not that evidence. Missing/locked keys must throw; never generate a replacement.
 * Only configured key IDs are requested. This key wraps a fresh per-archive data key.
 */
public interface ProtectedBackupKeyProvider {
    public val protectionStatus: KeyProtectionStatus
    public suspend fun <T> withKey(keyId: DomainId, action: (SecretKey) -> T): T
}

/**
 * Trusted runtime boundary. Acquisition authorizes this operation, stops admission/dispatch,
 * drains in-flight work, closes EVERY Room/auth/outbox handle and holds exclusive local process/
 * filesystem custody. It must not return after merely requesting close or timing out a join.
 * Restore authorization requires the named local recovery delegation, not possession of a file.
 * Lease acquisition must retain/clean up its own custody if cancellation prevents delivery of the
 * lease. The shared supervisor must also track every quarantine helper until actual process reap.
 */
public fun interface MasterMaintenancePort {
    public suspend fun acquire(request: MasterMaintenanceRequest): MasterMaintenanceLease
}

public interface MasterMaintenanceLease {
    public val databasePath: Path
    public val scope: BranchScope
    public suspend fun ensureStopped()

    /**
     * Persist deny-on-restart BEFORE replacement. Bind the authenticated approval, incident,
     * source checkpoint, target and hashes. An uncertain acknowledgement must also keep stopped.
     * This is not physical exclusion of another device or authority to activate this database.
     */
    public suspend fun persistRestoreIntent(intent: RestoreInstallIntent)

    /**
     * Release this loan. May resume the unchanged source only when NO restore intent exists.
     * After intent (including failure/cancellation) retain the durable stop; NEVER auto-promote.
     * A failed/poisoned close needs supervised process retirement, not a fake timeout completion.
     * Unconfirmed quarantine-helper death also forbids resumption or release of writer custody.
     */
    public suspend fun close()
}

public data class StagedSecurityQuarantineRequest(
    public val databasePath: Path,
    public val scope: BranchScope,
    public val backupId: DomainId,
    public val incidentId: DomainId,
    public val sourceAuthority: AuthorityIdentity,
    public val targetDeviceId: DomainId,
    public val approvalId: DomainId,
)

/**
 * Authenticate ALL protected accounts, policies, admissions, throttle, issuance and auth history
 * with the authorized recovery key. Preserve IDs/credentials/policies/custody/history, revoke
 * copied sessions and disable all copied admissions by valid revision transitions; append the
 * recovery auth audit. No key/private-identity cloning or automatic admission. Missing key or any
 * failed validation/close throws. Open only this exact private staged file in a supervised helper
 * process. This is the PARENT-side port: before returning success, reap that process and confirm
 * no remaining handles. A child-side retirement request is NOT proof of termination. On failure/
 * cancellation the supervisor must likewise arrange/reap termination; if custody is uncertain,
 * preserve the maintenance stop and stage for later supervised cleanup. The caller conservatively
 * leaves staging untouched after any exceptional return, even if termination was confirmed.
 * Never wire an in-process Room helper directly to this port. The caller never installs without
 * this real operation and bound receipt.
 */
public fun interface StagedSecurityQuarantine {
    public suspend fun quarantine(request: StagedSecurityQuarantineRequest): StagedSecurityQuarantineReceipt
}

public class JvmLocalBackupConfiguration(
    public val scope: BranchScope,
    public val backupDirectory: Path,
    public val activeKeyId: DomainId,
    permittedReadKeyIds: Set<DomainId>,
    public val schema: SqliteSnapshotProfile,
    public val retainLatest: Int = 5,
    public val maxDatabaseBytes: Long = 8L * 1024 * 1024 * 1024,
    compatibleReadProfiles: List<SqliteSnapshotProfile> = emptyList(),
) {
    private val permittedKeyValues: Set<DomainId> = permittedReadKeyIds.toSet()
    public val permittedReadKeyIds: Set<DomainId> get() = permittedKeyValues.toSet()
    internal val policyConfiguration = LocalBackupConfiguration(scope, schema, retainLatest, compatibleReadProfiles)
    public val readProfiles: List<SqliteSnapshotProfile> get() = policyConfiguration.readProfiles
    init {
        require(backupDirectory.isAbsolute)
        require(activeKeyId in this.permittedReadKeyIds && this.permittedReadKeyIds.size in 1..64)
        require(retainLatest in 2..256)
        require(maxDatabaseBytes in 512L..64L * 1024 * 1024 * 1024)
    }
}
