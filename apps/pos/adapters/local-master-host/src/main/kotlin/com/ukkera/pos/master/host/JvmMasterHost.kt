package com.ukkera.pos.master.host

import com.ukkera.pos.database.JvmLocalMasterService
import com.ukkera.pos.database.LocalMasterCloseResult
import com.ukkera.pos.database.LocalMasterRetirement
import com.ukkera.pos.database.LocalMasterServiceState
import com.ukkera.pos.database.openLocalMasterService
import com.ukkera.pos.recovery.store.MasterMaintenancePort
import com.ukkera.pos.recovery.store.JvmLocalBackupRestartFence
import com.ukkera.pos.recovery.store.QuarantineProcessCustody
import com.ukkera.pos.types.DomainId
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Concrete single-owner JVM composition. The ledger/file lock and required OS supervisor are
 * acquired BEFORE Room; only the actual grouped service's endpoint escapes. No arbitrary pool,
 * DAO or principal is exposed. Low-level legacy openers remain trusted plumbing and must NOT
 * be used by an application's alternative startup path.
 *
 * Maintenance leaves the host stopped. resumeUnchanged is explicit, creates a new service and
 * is unavailable after ANY restore intent or outstanding/uncertain helper. This host never
 * activates a restored branch, clears an incident, qualifies full history or manufactures keys.
 */
public class JvmMasterHost private constructor(
    public val configuration: JvmLocalMasterHostConfiguration,
    internal val ledger: DurableMasterRunState,
    internal val supervision: LocalMasterHostSupervisionLease,
    private val compositions: LocalMasterServiceCompositionSource,
    internal val authorization: LocalMasterMaintenanceAuthorization,
) {
    internal val operations = Mutex()
    private val retired = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    @Volatile private var activeIncarnationId: DomainId? = null
    @Volatile internal var service: JvmLocalMasterService? = null
    @Volatile internal var maintenanceLoan: MaintenanceLoan? = null
    @Volatile internal var helperLoan: HostHelperLoan? = null
    @Volatile internal var restoreAttempted: Boolean = ledger.read().intent != null
    private val mutableStatus = MutableStateFlow(LocalMasterHostStatus(
        if (restoreAttempted) LocalMasterHostPhase.RESTORE_HELD else LocalMasterHostPhase.STOPPED))

    public val status: StateFlow<LocalMasterHostStatus> = mutableStatus.asStateFlow()
    public val maintenance: MasterMaintenancePort = JvmStoppedMaintenance(this)
    public val quarantineCustody: QuarantineProcessCustody = JvmHostQuarantineCustody(this)

    /** Snapshot only; the captured handle must still be current when using a client credential. */
    public fun captureActive(): LocalMasterHostActive? = mutableStatus.value.active?.takeIf { it.isCurrent() }

    /** Explicit clean-source restart, NOT restore recovery/readmission or resetting an endpoint. */
    public suspend fun resumeUnchanged(): Unit = hostOperation {
        operations.withLock { startServiceLocked() }
    }

    /**
     * Real close, not cancellation as cleanup evidence. Caller cancellation cannot abandon the
     * close/lock handoff. On uncertainty retain all resources strongly and require OS retirement.
     */
    public suspend fun close(): Unit {
        try {
            inCleanHostContext {
                operations.withLock {
                    if (released.get()) return@withLock
                    requireUsable()
                    if (maintenanceLoan != null || helperLoan != null || ledger.read().maintenance != null || ledger.read().helper != null) {
                        hostFail(LocalMasterHostFailure.MAINTENANCE_UNAVAILABLE)
                    }
                    stopServiceLocked()
                    val before = ledger.read()
                    ledger.update { it.copy(phase = RunPhase.CUSTODY_ACQUIRING) }
                    supervision.ensureExclusive()
                    supervision.close()
                    requireUsable()
                    // With all handles gone, publish only the prior clean/held state. Never infer
                    // permission to resume merely because the OS custody loan closed normally.
                    ledger.update { it.copy(phase = before.phase, acquisitionId = null) }
                    ledger.closeSafe()
                    released.set(true)
                    mutableStatus.value = LocalMasterHostStatus(LocalMasterHostPhase.CLOSED)
                    RetainedHostResources.release(supervision)
                    RetainedHostResources.release(this@JvmMasterHost)
                }
            }
        } catch (failure: Throwable) {
            retire(LocalMasterHostFailure.CUSTODY_UNAVAILABLE)
            if (failure is CancellationException) throw failure
            if (failure is LocalMasterHostException) throw failure
            hostFail(LocalMasterHostFailure.CUSTODY_UNAVAILABLE)
        }
        currentCoroutineContext().ensureActive()
    }

    internal fun requireUsable() {
        if (retired.get() || released.get()) hostFail(LocalMasterHostFailure.RETIRED)
        if (!ledger.isHealthy()) hostFail(LocalMasterHostFailure.STORAGE_UNAVAILABLE)
    }

    internal fun publish(phase: LocalMasterHostPhase) {
        activeIncarnationId = null
        mutableStatus.update { LocalMasterHostStatus(if (retired.get()) LocalMasterHostPhase.RETIRED else phase) }
    }

    /** Never blocks on disk, joins or Room. Earlier dirty ledger state already fences restart. */
    internal fun retire(reason: LocalMasterHostFailure) {
        if (released.get()) return
        if (retired.compareAndSet(false, true)) {
            activeIncarnationId = null
            // Ask the independent supervisor before flow subscribers/cancellation callbacks.
            try { supervision.retirementRequired(reason) } catch (_: Throwable) { /* Keep ownership. */ }
            try { service?.endpoint?.stopAccepting() } catch (_: Throwable) { /* OS retirement still owns it. */ }
            mutableStatus.value = LocalMasterHostStatus(LocalMasterHostPhase.RETIRED)
        }
    }

    internal suspend fun startServiceLocked() {
        requireUsable()
        val before = ledger.read()
        if (service != null || maintenanceLoan != null || helperLoan != null || restoreAttempted ||
            before.phase != RunPhase.CLEAN_STOPPED || before.maintenance != null || before.helper != null || before.intent != null ||
            configuration.authority.deviceId != configuration.localDeviceId) {
            hostFail(LocalMasterHostFailure.STARTUP_HELD)
        }
        val incarnation = DomainId.random()
        val launch = LocalMasterServiceLaunch(configuration.installationId, configuration.localDeviceId, incarnation,
            configuration.databasePath, configuration.scope, configuration.authority)
        publish(LocalMasterHostPhase.STARTING)
        ledger.update { it.copy(phase = RunPhase.STARTING, serviceIncarnationId = incarnation) }
        try {
            supervision.ensureExclusive()
            supervision.prepareService(launch)
            JvmLocalBackupRestartFence.requireExactRoomIdentity(configuration.databasePath, configuration.backups.schema)
            supervision.ensureExclusive()
            val composition = compositions.prepare(launch)
            val observation = composition.security.environment.observe()
            if (observation?.issuerContextId != incarnation) hostFail(LocalMasterHostFailure.CUSTODY_UNAVAILABLE)
            requireUsable()
            // The durable startup gate and actual OS custody above precede this first builder.
            val opened = openLocalMasterService(configuration.databasePath, configuration.scope, configuration.authority,
                composition.handlers, composition.readHandlers, composition.security, composition.runtime,
                LocalMasterRetirement { retire(LocalMasterHostFailure.RETIRED) }, composition.clock,
                composition.dispatcher, composition.workers, composition.maximumQueuedRequests,
                composition.maximumRequestMillis, composition.maximumCloseMillis)
            service = opened
            requireUsable()
            if (opened.state != LocalMasterServiceState.ACTIVE || composition.security.environment.observe()?.issuerContextId != incarnation) {
                hostFail(LocalMasterHostFailure.CUSTODY_UNAVAILABLE)
            }
            ledger.update { it.copy(phase = RunPhase.ACTIVE) }
            requireUsable()
            val active = LocalMasterHostActive(incarnation, opened.endpoint) {
                !retired.get() && !released.get() && service === opened &&
                    opened.state == LocalMasterServiceState.ACTIVE && activeIncarnationId == incarnation
            }
            // Client lifetime checks are bounded volatile/atomic reads, never waits behind the
            // ledger's filesystem monitor. Concurrent retirement cannot republish ACTIVE.
            activeIncarnationId = incarnation
            mutableStatus.update { if (retired.get()) LocalMasterHostStatus(LocalMasterHostPhase.RETIRED)
                else LocalMasterHostStatus(LocalMasterHostPhase.ACTIVE, active) }
        } catch (failure: Throwable) {
            // Even a failed factory may have opened a pool. No speculative close/reuse/unlock.
            retire(LocalMasterHostFailure.CUSTODY_UNAVAILABLE)
            if (failure is CancellationException) throw failure
            hostFail(LocalMasterHostFailure.CUSTODY_UNAVAILABLE)
        }
    }

    /** Caller holds operations. The external-owner close is mandatory even for a stopped host. */
    internal suspend fun stopServiceLocked() {
        requireUsable()
        val current = service
        publish(LocalMasterHostPhase.STOPPING)
        current?.endpoint?.stopAccepting()
        ledger.update { it.copy(phase = RunPhase.STOPPING) }
        try {
            supervision.ensureExclusive()
            supervision.quiesceAdditionalOwners()
            if (current != null && (current.quiesceAndClose() != LocalMasterCloseResult.CLOSED ||
                    current.state != LocalMasterServiceState.CLOSED)) hostFail(LocalMasterHostFailure.RETIRED)
            requireUsable()
            supervision.ensureExclusive()
            service = null
            val after = ledger.update {
                it.copy(phase = if (it.maintenance != null) RunPhase.MAINTENANCE
                    else if (it.intent != null) RunPhase.RESTORE_HELD else RunPhase.CLEAN_STOPPED)
            }
            publish(if (after.maintenance != null) LocalMasterHostPhase.MAINTENANCE
                else if (after.intent != null) LocalMasterHostPhase.RESTORE_HELD else LocalMasterHostPhase.STOPPED)
        } catch (failure: Throwable) {
            retire(LocalMasterHostFailure.CUSTODY_UNAVAILABLE)
            if (failure is CancellationException) throw failure
            hostFail(LocalMasterHostFailure.CUSTODY_UNAVAILABLE)
        }
    }

    internal suspend fun <T> hostOperation(block: suspend () -> T): T = try {
        val result = inCleanHostContext(block)
        currentCoroutineContext().ensureActive()
        result
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: LocalMasterHostException) {
        if (!ledger.isHealthy() || failure.failure in setOf(LocalMasterHostFailure.STORAGE_UNAVAILABLE, LocalMasterHostFailure.CUSTODY_UNAVAILABLE)) {
            retire(failure.failure)
        }
        throw failure
    }
    catch (_: Throwable) { retire(LocalMasterHostFailure.STORAGE_UNAVAILABLE); hostFail(LocalMasterHostFailure.STORAGE_UNAVAILABLE) }

    public companion object {
        /**
         * Explicit authorized ledger enrollment. Missing existing state is NEVER auto-enrolled by
         * open(). Interrupted enrollment remains held; this does not provision or migrate a DB.
         */
        public suspend fun enroll(
            configuration: JvmLocalMasterHostConfiguration,
            authority: LocalMasterHostEnrollmentAuthority,
            supervision: LocalMasterHostSupervision,
        ): Unit {
            var custody: LocalMasterHostSupervisionLease? = null
            var settled = false
            try {
                inCleanHostContext {
                    val ledger = DurableMasterRunState.acquire(configuration, enrollment = true)
                    val acquisitionId = DomainId.random()
                    ledger.initialize(RunRecord(1, configuration.installationId, configuration.localDeviceId, configuration.scope, configuration.authority,
                        configuration.databasePath.toString(), configuration.backups.backupDirectory.toString(), 0,
                        RunPhase.CUSTODY_ACQUIRING, acquisitionId, null, null, null, null))
                    val lease = supervision.acquire(LocalMasterHostCustodyRequest(configuration, acquisitionId, true, null))
                    custody = lease
                    RetainedHostResources.retain(lease)
                    lease.ensureExclusive()
                    authority.authorize(configuration)
                    lease.ensureExclusive()
                    lease.close()
                    ledger.update { it.copy(phase = RunPhase.CLEAN_STOPPED, acquisitionId = null) }
                    ledger.closeSafe()
                    RetainedHostResources.release(lease)
                    settled = true
                }
                currentCoroutineContext().ensureActive()
            } catch (failure: Throwable) {
                if (!settled) try { custody?.retirementRequired(LocalMasterHostFailure.CUSTODY_UNAVAILABLE) } catch (_: Throwable) { }
                if (failure is CancellationException) throw failure
                if (failure is LocalMasterHostException) throw failure
                hostFail(LocalMasterHostFailure.CUSTODY_UNAVAILABLE)
            }
        }

        /**
         * Opens only a cleanly stopped or cleanly held enrolled installation. Dirty startup,
         * orphan maintenance/helper and interrupted enrollment records need separately supervised
         * resolution; this factory deliberately supplies no automatic repair/unpin/reset path.
         * A held record may open REMAIN_STOPPED for the exact authorized restore receipt replay.
         */
        public suspend fun open(
            configuration: JvmLocalMasterHostConfiguration,
            compositions: LocalMasterServiceCompositionSource,
            supervision: LocalMasterHostSupervision,
            authorization: LocalMasterMaintenanceAuthorization,
            mode: LocalMasterHostOpenMode,
        ): JvmMasterHost {
            var delivered: JvmMasterHost? = null
            var custody: LocalMasterHostSupervisionLease? = null
            try {
                val host = inCleanHostContext {
                    val ledger = DurableMasterRunState.acquire(configuration, enrollment = false)
                    val before = ledger.read()
                    if (before.maintenance != null || before.helper != null ||
                        before.phase !in setOf(RunPhase.CLEAN_STOPPED, RunPhase.RESTORE_HELD) ||
                        (mode == LocalMasterHostOpenMode.START_SERVICE && before.intent != null)) {
                        ledger.closeSafe()
                        hostFail(LocalMasterHostFailure.STARTUP_HELD)
                    }
                    val acquisitionId = DomainId.random()
                    ledger.update { it.copy(phase = RunPhase.CUSTODY_ACQUIRING, acquisitionId = acquisitionId) }
                    val lease = supervision.acquire(LocalMasterHostCustodyRequest(configuration, acquisitionId, false, before.intent?.value()))
                    custody = lease
                    RetainedHostResources.retain(lease)
                    lease.ensureExclusive()
                    ledger.update { it.copy(phase = before.phase) }
                    val owner = JvmMasterHost(configuration, ledger, lease, compositions, authorization)
                    delivered = owner
                    RetainedHostResources.retain(owner)
                    if (mode == LocalMasterHostOpenMode.START_SERVICE) owner.operations.withLock { owner.startServiceLocked() }
                    owner
                }
                currentCoroutineContext().ensureActive()
                return host
            } catch (failure: Throwable) {
                if (failure is CancellationException && delivered != null) {
                    // A result lost to prompt cancellation must not orphan a live host/lock.
                    try { withContext(NonCancellable) { delivered?.close() } } catch (_: Throwable) { }
                } else if (delivered != null) delivered?.retire(LocalMasterHostFailure.CUSTODY_UNAVAILABLE)
                else try { custody?.retirementRequired(LocalMasterHostFailure.CUSTODY_UNAVAILABLE) } catch (_: Throwable) { }
                if (failure is CancellationException) throw failure
                if (failure is LocalMasterHostException) throw failure
                hostFail(LocalMasterHostFailure.CUSTODY_UNAVAILABLE)
            }
        }
    }
}

/**
 * A fresh, explicitly owned context excludes caller Room-loan elements. Once accepted, custody
 * work finishes (or is externally retired), even if the recipient cancels. Callers handling a
 * returned ownership object must account for its undelivered-result path before rethrowing.
 */
internal suspend fun <T> inCleanHostContext(block: suspend () -> T): T {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    return try { withContext(NonCancellable) { scope.async { block() }.await() } }
    finally { scope.cancel() }
}
