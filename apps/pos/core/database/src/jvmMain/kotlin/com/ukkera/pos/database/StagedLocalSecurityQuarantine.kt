package com.ukkera.pos.database

import androidx.room3.Room
import androidx.room3.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

public data class StagedLocalSecurityQuarantineRequest(
    public val databasePath: Path,
    public val scope: BranchScope,
    public val backupId: DomainId,
    public val incidentId: DomainId,
    public val sourceAuthority: AuthorityIdentity,
    public val targetDeviceId: DomainId,
    public val approvalId: DomainId,
)

/** Only emitted AFTER real transaction completion and synchronous close returned successfully. */
public class StagedLocalSecurityQuarantineReceipt internal constructor(
    public val scope: BranchScope,
    public val backupId: DomainId,
    public val incidentId: DomainId,
    public val targetDeviceId: DomainId,
    authenticatedRowCounts: Map<String, Long>,
    public val revokedSessionCount: Long,
) {
    public val authenticatedRowCounts: Map<String, Long> = authenticatedRowCounts.toMap()
}

/**
 * MAINTENANCE-ONLY: invoke in a supervised, bounded helper process over the exact private STAGED
 * v6 file after the owner holds a qualified stopped/exclusive maintenance lease. This function
 * cannot establish that lease, the request's named recovery delegation or the absence of another
 * physical writer; it grants no install/activation authority. Never call it on a running/live DB.
 *
 * Source recovery key access is mandatory. All ten protected tables authenticate before ANY
 * security change. Only active-session account aggregates and admitted-device rows change; old
 * credentials, policies, throttles, access/refresh issuance, archives and configuration remain.
 * Exactly one protected audit is appended, bound to all request identities. No rekey, key copy,
 * device admission, branch.writable change, issue restamping or automatic activation is performed.
 *
 * A failure/cancellation can leave a poisoned pool. Do NOT call Room.close() in that failure path:
 * F36 can spin before pool cancellation. The mandatory retirement hook must arrange OS-level
 * termination and the owner must reap the helper before reusing/deleting/installing staging.
 * No receipt is emitted on failure, timeout, missing key, unsupported snapshot or unconfirmed close.
 */
public suspend fun quarantineStagedLocalSecurity(
    request: StagedLocalSecurityQuarantineRequest,
    protection: DeviceProtectedLocalStorage,
    storageKey: ProtectedStorageKeyReference,
    environment: LocalAuthenticationEnvironment,
    retirement: LocalMasterRetirement,
    maximumProtectedRecords: Long,
): StagedLocalSecurityQuarantineReceipt {
    currentCoroutineContext().ensureActive()
    require(maximumProtectedRecords > 0)
    // Pin the genuine generated current Room identity, never an archive-supplied identity.
    // Never validate a v5 snapshot and then hand it to a v6 builder for implicit migration.
    val currentIdentity = STAGED_QUARANTINE_V6_IDENTITY
    val path = request.databasePath
    require(path.isAbsolute && path == path.normalize() && !Files.isSymbolicLink(path) &&
        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "Private staged database required" }
    var opened = false
    try {
        // No migration. The private/exclusive staging owner prevents path substitution/creation;
        // the recovery adapter independently pins this same exact current profile and its DDL.
        opened = true
        BundledSQLiteDriver().open(path.toString()).use { connection ->
        connection.prepare("PRAGMA main.user_version").use {
            check(it.step() && it.getLong(0) == 6L && !it.step()) { "Unsupported staged schema" }
        }
        connection.prepare("SELECT count(*), COALESCE(sum(CASE WHEN typeof(id)='integer' AND id=42 " +
            "AND typeof(identity_hash)='text' AND CAST(identity_hash AS BLOB)=CAST(? AS BLOB) " +
            "THEN 1 ELSE 0 END),0) FROM room_master_table").use {
            it.bindText(1, currentIdentity)
            check(it.step() && it.getLong(0) == 1L && it.getLong(1) == 1L && !it.step()) { "Unsupported staged identity" }
        }
        }
        opened = false
        // A version6 quarantine observation is never authority to touch it with Room7.
        requireLegacyMasterRoomProfile()
        // From this point even a partially constructed/opened pool requires external retirement.
        opened = true
        val database = buildMasterRoom(Room.databaseBuilder<MasterRoomDatabase>(path.toString(), MasterRoomConstructor::initialize))
        val store = RoomLocalSecurityStore(database, protection, storageKey)
        val source = RoomCurrentLocalAuthorizationSource(store, RoomLocalAuthorizationEnvironment {
            environment.observe()?.let { RoomLocalAuthorizationObservation(it.issuerContextId, it.moment) }
        })
        val digest = quarantineBinding(request)
        val receipt = database.useWriterConnection { connection ->
            requireDurableWriter(connection)
            store.transaction { transaction ->
                connection.usePrepared("SELECT count(*), COALESCE(sum(CASE WHEN typeof(scope)='text' AND scope=? " +
                    "AND typeof(assignmentId)='text' AND assignmentId=? AND typeof(deviceId)='text' AND deviceId=? " +
                    "AND typeof(epoch)='integer' AND epoch=? THEN 1 ELSE 0 END),0) FROM branch_authority") {
                    it.bindText(1, request.scope.storageKey()); it.bindText(2, request.sourceAuthority.assignmentId.value)
                    it.bindText(3, request.sourceAuthority.deviceId.value); it.bindLong(4, request.sourceAuthority.epoch.value)
                    if (!it.step() || it.getLong(0) != 1L || it.getLong(1) != 1L || it.step()) transaction.refuse()
                }
                val before = transaction.authenticateCensus(request.scope, maximumProtectedRecords)
                if (transaction.readAuthEvent(request.scope, request.approvalId) != null) transaction.refuse()
                var revoked = 0L
                transaction.forEachAccount(request.scope) { account ->
                    val count = account.state.sessions.count { it.status == SessionStatus.ACTIVE }
                    if (count > 0) {
                        val transition = LocalSessions.revokeAll(account.state) as? SessionTransition.Applied ?: transaction.refuse()
                        if (!transaction.replaceAccount(account.state.revision, transition.state)) transaction.refuse()
                        revoked += count
                    }
                }
                for (device in transaction.admittedDeviceIds(request.scope)) {
                    if (!transaction.revokeAdmission(request.scope, device)) transaction.refuse()
                }
                val at = source.observeCurrent(currentCoroutineContext()).moment
                transaction.insertAuthEvent(LocalAuthEvent(request.scope, request.approvalId, LocalAuthEventKind.RESTORE_QUARANTINED,
                    request.targetDeviceId, null, null, null, at, digest))
                val after = transaction.authenticateCensus(request.scope, maximumProtectedRecords)
                for ((table, count) in before) {
                    val expected = count + if (table == "protected_local_auth_event") 1L else 0L
                    if (after[table] != expected) transaction.refuse()
                }
                transaction.forEachAccount(request.scope) { account ->
                    if (account.state.sessions.any { it.status == SessionStatus.ACTIVE }) transaction.refuse()
                }
                if (transaction.admittedDeviceIds(request.scope).isNotEmpty()) transaction.refuse()
                transaction.ensureAuthorizationHealthy()
                source.observeCurrent(currentCoroutineContext())
                StagedLocalSecurityQuarantineReceipt(request.scope, request.backupId, request.incidentId,
                    request.targetDeviceId, after, revoked)
            }.also { source.observeCurrent(currentCoroutineContext()) }
        }
        currentCoroutineContext().ensureActive()
        database.close() // Only the normally returned, non-poisoned path; OS supervisor still bounds it.
        opened = false
        currentCoroutineContext().ensureActive()
        return receipt
    } catch (cancelled: CancellationException) {
        if (opened) retireQuarantine(retirement)
        throw cancelled
    } catch (_: Exception) {
        if (opened) retireQuarantine(retirement)
        throw StagedLocalSecurityQuarantineUnavailable()
    }
}

/** Observed genuine KSP v6 identity; not runtime/quarantine qualification or a trigger-body pin. */
private const val STAGED_QUARANTINE_V6_IDENTITY: String = "c35cf1dddfea41417d2bb3d4f95ce791"

public class StagedLocalSecurityQuarantineUnavailable : IllegalStateException("Staged local security quarantine unavailable")
private fun retireQuarantine(retirement: LocalMasterRetirement) {
    try { retirement.retire(LocalMasterRetirementReason.STORAGE_UNCERTAIN) } catch (_: Exception) { /* No receipt; require helper reaping. */ }
}
private fun quarantineBinding(request: StagedLocalSecurityQuarantineRequest): PayloadDigest = sha256((
    "ukkera.staged-local-security-quarantine/v1\u0000${request.scope.storageKey()}\u0000${request.backupId.value}\u0000" +
        "${request.incidentId.value}\u0000${request.sourceAuthority.assignmentId.value}\u0000${request.sourceAuthority.deviceId.value}\u0000" +
        "${request.sourceAuthority.epoch.value}\u0000${request.targetDeviceId.value}\u0000${request.approvalId.value}"
    ).encodeToByteArray())
