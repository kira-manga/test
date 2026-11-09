package com.ukkera.pos.master.host

import com.ukkera.pos.database.LocalMasterEndpoint
import com.ukkera.pos.database.LocalMasterSecurityDependencies
import com.ukkera.pos.database.MasterClock
import com.ukkera.pos.database.MasterCommandHandler
import com.ukkera.pos.database.MasterReadHandler
import com.ukkera.pos.database.MasterRuntimeVerifier
import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.recovery.store.JvmLocalBackupConfiguration
import com.ukkera.pos.recovery.store.LocalMasterBackupProfiles
import com.ukkera.pos.recovery.store.MasterMaintenanceRequest
import com.ukkera.pos.recovery.store.QuarantineProcessCustodyLease
import com.ukkera.pos.recovery.store.RestoreInstallIntent
import com.ukkera.pos.recovery.store.StagedSecurityQuarantineRequest
import com.ukkera.pos.recovery.store.VerifiedLocalBackup
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId
import java.nio.file.Path
import kotlinx.coroutines.CoroutineDispatcher

/** Trusted installation configuration, never decoded from a client/backup request. */
public class JvmLocalMasterHostConfiguration(
    public val installationId: DomainId,
    /** Bound by the actual protected supervisor identity, not inferred from a copied authority. */
    public val localDeviceId: DomainId,
    public val databasePath: Path,
    public val stateDirectory: Path,
    public val authority: AuthorityIdentity,
    public val backups: JvmLocalBackupConfiguration,
) {
    public val scope: BranchScope get() = backups.scope
    init {
        for (path in listOf(databasePath, stateDirectory, backups.backupDirectory)) {
            require(path.isAbsolute && path == path.normalize() && path.parent != null)
        }
        require(databasePath.fileName.toString() != ":memory:")
        require(!databasePath.startsWith(stateDirectory) && !databasePath.startsWith(backups.backupDirectory))
        require(!stateDirectory.startsWith(backups.backupDirectory) && !backups.backupDirectory.startsWith(stateDirectory))
        val currentProfile = when (backups.schema.schemaVersion) {
            6 -> LocalMasterBackupProfiles.localAuthV6(backups.schema.permittedSchemaSha256)
            7 -> LocalMasterBackupProfiles.localAuthV7(backups.schema.permittedSchemaSha256)
            else -> throw IllegalArgumentException("Unsupported master backup profile")
        }
        require(backups.schema.roomIdentityHash == currentProfile.roomIdentityHash &&
            backups.schema.requiredTables == currentProfile.requiredTables &&
            backups.schema.securityTables == currentProfile.securityTables &&
            backups.schema.quarantineAuditTable == currentProfile.quarantineAuditTable &&
            backups.schema.scopedTables == currentProfile.scopedTables)
    }
    override fun toString(): String = "JvmLocalMasterHostConfiguration([REDACTED])"
}

public enum class LocalMasterHostOpenMode { START_SERVICE, REMAIN_STOPPED }
public enum class LocalMasterHostPhase { STOPPED, STARTING, ACTIVE, STOPPING, MAINTENANCE, RESTORE_HELD, RETIRED, CLOSED }
public enum class LocalMasterHostFailure {
    PRIVATE_STORAGE_REQUIRED, STORAGE_UNAVAILABLE, CUSTODY_UNAVAILABLE, STARTUP_HELD,
    AUTHORIZATION_UNAVAILABLE, MAINTENANCE_UNAVAILABLE, HELPER_UNAVAILABLE, RESTORE_HELD, RETIRED,
}
public class LocalMasterHostException(public val failure: LocalMasterHostFailure) : Exception(failure.name)

/** An old handle remains old. Reopening publishes a NEW endpoint and issuer incarnation. */
public class LocalMasterHostActive internal constructor(
    public val serviceIncarnationId: DomainId,
    public val endpoint: LocalMasterEndpoint,
    private val current: () -> Boolean,
) {
    public fun isCurrent(): Boolean = current()
}

public class LocalMasterHostStatus internal constructor(
    public val phase: LocalMasterHostPhase,
    public val active: LocalMasterHostActive? = null,
)

public data class LocalMasterServiceLaunch(
    public val installationId: DomainId,
    public val localDeviceId: DomainId,
    public val serviceIncarnationId: DomainId,
    public val databasePath: Path,
    public val scope: BranchScope,
    public val authority: AuthorityIdentity,
)

/**
 * A fresh composition for EACH launch. environment.issuerContextId must be the supplied new
 * incarnation. All native key/peer/time/writer/provisioning implementations remain independently
 * qualified. This host never substitutes an accepting runtime verifier or copies device keys.
 */
public class LocalMasterServiceComposition(
    handlers: List<MasterCommandHandler>,
    readHandlers: List<MasterReadHandler>,
    public val security: LocalMasterSecurityDependencies,
    public val runtime: MasterRuntimeVerifier,
    public val clock: MasterClock,
    public val dispatcher: CoroutineDispatcher,
    public val workers: Int = 4,
    public val maximumQueuedRequests: Int = 32,
    public val maximumRequestMillis: Long = 30_000,
    public val maximumCloseMillis: Long = 30_000,
) {
    public val handlers: List<MasterCommandHandler> = handlers.toList()
    public val readHandlers: List<MasterReadHandler> = readHandlers.toList()
    init {
        require(workers in 1..16 && maximumQueuedRequests in 1..256)
        require(maximumRequestMillis in 1_000..300_000 && maximumCloseMillis in 1_000..300_000)
    }
    override fun toString(): String = "LocalMasterServiceComposition([REDACTED])"
}

public fun interface LocalMasterServiceCompositionSource {
    /** Must leave no untracked DB handles, even when construction fails or is cancelled. */
    public suspend fun prepare(launch: LocalMasterServiceLaunch): LocalMasterServiceComposition
}

/** Enrollment only provisions the deny-on-restart ledger, not a branch/employee/writer. */
public fun interface LocalMasterHostEnrollmentAuthority {
    public suspend fun authorize(configuration: JvmLocalMasterHostConfiguration)
}

public enum class LocalMaintenanceAuthorizationKind { LOCAL_COPY, PREPARED_NAMED_RECOVERY }

/**
 * Issued by the trusted authorization adapter, not authority by type. Restore requires the
 * prepared NAMED recovery ceremony, with the exact independently authenticated selected backup.
 * A copied action approval, ordinary interactive session or file possession cannot issue it.
 * No permit may retain a Room loan/connection or require reopening the stopped source database.
 */
public interface LocalMasterMaintenancePermit {
    public val request: MasterMaintenanceRequest
    public val decisionId: DomainId
    public val subjectId: DomainId
    public val kind: LocalMaintenanceAuthorizationKind
    public val selectedBackup: VerifiedLocalBackup?
    public suspend fun ensureCurrent()
}

public fun interface LocalMasterMaintenanceAuthorization {
    /** Cancellation before delivery remains the provider's custody/cleanup responsibility. */
    public suspend fun authorize(request: MasterMaintenanceRequest): LocalMasterMaintenancePermit
}

/** Metadata for the actual OS supervisor, not process-reap evidence or a writable appointment. */
public class LocalMasterHostCustodyRequest internal constructor(
    public val configuration: JvmLocalMasterHostConfiguration,
    public val acquisitionId: DomainId,
    public val enrollment: Boolean,
    public val restoreIntent: RestoreInstallIntent?,
)

/**
 * Mandatory actual OS/service boundary. Establish protected ancestor/path custody and exclusive
 * process ownership before delivery. The host's file lock is NOT proof that crashed children are
 * dead. Independently cover a blocked JVM/native operation, parent death and poisoned close.
 * An acquisition that fails/cancels before delivery must keep/settle its own ownership. No
 * accepting implementation or platform support claim is provided by this module.
 */
public fun interface LocalMasterHostSupervision {
    public suspend fun acquire(request: LocalMasterHostCustodyRequest): LocalMasterHostSupervisionLease
}

public interface LocalMasterHostSupervisionLease {
    public suspend fun ensureExclusive()

    /** Protect the entire launch BEFORE any Room builder. Does not qualify branch authority. */
    public suspend fun prepareService(launch: LocalMasterServiceLaunch)

    /**
     * Stop transport/outbox/other storage owners and actually close/join ALL handles outside the
     * grouped service. Must not call back into host maintenance or release the host's custody.
     */
    public suspend fun quiesceAdditionalOwners()

    /**
     * SAME supervisor, exact already-authorized stage. Establish independent containment/deadline
     * BEFORE ProcessBuilder.start; a later PID callback is insufficient. The returned lease must
     * establish full containment reap, including escaped descendants/descriptor handoff risk.
     */
    public suspend fun prepareQuarantine(
        request: StagedSecurityQuarantineRequest,
        maximumRunMillis: Long,
    ): QuarantineProcessCustodyLease

    /** Nonblocking actual OS retirement request. Never Room.close(), unlock or a new pool here. */
    public fun retirementRequired(reason: LocalMasterHostFailure)

    /** Only after all tracked handles/helpers are safely closed. Never activates a restored DB. */
    public suspend fun close()
}

internal fun hostFail(reason: LocalMasterHostFailure): Nothing = throw LocalMasterHostException(reason)
