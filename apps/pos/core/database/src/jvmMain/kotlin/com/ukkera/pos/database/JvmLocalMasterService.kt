package com.ukkera.pos.database

import androidx.room3.Room
import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.InstalledOfflineInstallationContext
import com.ukkera.pos.security.LocalAuthenticatedPeerSource
import com.ukkera.pos.security.LocalAuthenticationEntropy
import com.ukkera.pos.security.UnavailableLocalProvisioningAuthority
import com.ukkera.pos.types.UtcInstant
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

public enum class LocalMasterServiceState { ACTIVE, CLOSING, CLOSED, RETIRED }
public enum class LocalMasterCloseResult { CLOSED, PROCESS_RETIREMENT_REQUIRED }

/**
 * Grouped real Room/auth/kernel composition. Verifier, credential administration, read/command
 * authorization, action approval and authentication share ONE protected source and ONE Room database. Only the
 * typed clean-context ingress escapes. No DAO, arbitrary transaction callback or principal
 * factory is returned to a UI/transport caller.
 *
 * The ordinary opener neither appoints a writer nor provisions identities. Supplied runtime, peer,
 * provisioning and key providers remain independently qualified trust boundaries. A copied DB,
 * empty handler registry or status flag does not satisfy them. The process owner must acquire
 * protected storage/custody before opening and retain it until CLOSED or actual process reap.
 */
public class JvmLocalMasterService internal constructor(
    private val database: MasterRoomDatabase,
    public val databasePath: Path,
    public val scope: BranchScope,
    public val authority: AuthorityIdentity,
    handlers: List<MasterCommandHandler>,
    readHandlers: List<MasterReadHandler>,
    security: LocalMasterSecurityDependencies,
    runtime: MasterRuntimeVerifier,
    private val processRetirement: LocalMasterRetirement,
    clock: MasterClock,
    dispatcher: CoroutineDispatcher,
    workers: Int,
    maximumQueuedRequests: Int,
    maximumRequestMillis: Long,
    private val maximumCloseMillis: Long,
    private val installationContext: InstalledOfflineInstallationContext? = null,
    physicalRecordingGate: PhysicalRecordingExecutionGate = UnavailablePhysicalRecordingExecutionGate,
) {
    init {
        require(workers in 1..16 && maximumQueuedRequests in 1..256)
        require(maximumRequestMillis in 1_000..300_000 && maximumCloseMillis in 1_000..300_000)
    }

    private val life = AtomicReference(LocalMasterServiceState.ACTIVE)
    public val state: LocalMasterServiceState get() = life.get()
    private val closed = CompletableDeferred<LocalMasterCloseResult>()
    // Actual independent scheduling, not a watchdog queued behind a blocked Room/hasher call.
    // These daemon threads do not themselves qualify OS suspension clocks or process fencing.
    private val watchdogDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ukkera-master-watchdog").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val closeDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ukkera-master-close").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val closeScope = CoroutineScope(SupervisorJob() + closeDispatcher)
    private val watchdogScope = CoroutineScope(SupervisorJob() + watchdogDispatcher)
    private val composition = RoomLocalMasterComposition(
        database, scope, authority, handlers, readHandlers, security, runtime, clock,
        LocalMasterRetirement(::retire), watchdogDispatcher, dispatcher, workers,
        maximumQueuedRequests, maximumRequestMillis, installationContext, physicalRecordingGate,
    )
    public val endpoint: LocalMasterEndpoint get() = composition.endpoint
    internal val installationClient: LocalMasterInstallationClient? get() = composition.installationClient

    /**
     * Stops ingress now, then waits on a service-owned close. Caller cancellation only abandons
     * that wait. CLOSED means worker quiescence AND the real database.close() returned normally.
     * Timeout/failure returns PROCESS_RETIREMENT_REQUIRED and never releases writer custody or
     * starts another connection pool. The platform supervisor must reap the old process first.
     */
    public suspend fun quiesceAndClose(): LocalMasterCloseResult {
        // End-only SAME core atom, before the state transition; no Room/channel/Job callback.
        endpoint.denyPhysicalFromOwner()
        if (life.compareAndSet(LocalMasterServiceState.ACTIVE, LocalMasterServiceState.CLOSING)) {
            endpoint.stopAccepting()
            val watchdog = watchdogScope.launch {
                delay(maximumCloseMillis)
                if (life.get() == LocalMasterServiceState.CLOSING) retire(LocalMasterRetirementReason.REQUEST_DEADLINE)
            }
            closeScope.launch {
                try {
                    endpoint.awaitQuiescence()
                    // May block on an actual poisoned implementation; the other scheduler and
                    // required process supervisor do not depend on this call returning.
                    database.close()
                    // Do not expire the installation environment while graceful in-flight work
                    // is still using it. Retirement here follows ACTUAL quiescence and close.
                    installationContext?.retire()
                    if (life.compareAndSet(LocalMasterServiceState.CLOSING, LocalMasterServiceState.CLOSED)) {
                        closed.complete(LocalMasterCloseResult.CLOSED)
                        watchdog.cancel()
                        watchdogScope.cancel()
                        watchdogDispatcher.close()
                        closeScope.cancel()
                        closeDispatcher.close()
                    }
                } catch (_: Throwable) {
                    retire(LocalMasterRetirementReason.STORAGE_UNCERTAIN)
                }
            }
        }
        return when (life.get()) {
            LocalMasterServiceState.CLOSED -> LocalMasterCloseResult.CLOSED
            LocalMasterServiceState.RETIRED -> LocalMasterCloseResult.PROCESS_RETIREMENT_REQUIRED
            else -> closed.await()
        }
    }

    private fun retire(reason: LocalMasterRetirementReason) {
        // Physical publication/adoption/capture must lose BEFORE a retiring CAS or open
        // installation/supervisor callback. Do NOT replace with stopAccepting here: its
        // channel/Job cleanup belongs AFTER the mandatory independent OS supervisor request.
        endpoint.denyPhysicalFromOwner()
        while (true) {
            val before = life.get()
            if (before == LocalMasterServiceState.CLOSED || before == LocalMasterServiceState.RETIRED) return
            if (life.compareAndSet(before, LocalMasterServiceState.RETIRED)) break
        }
        installationContext?.retire()
        // Ask the mandatory nonblocking OS supervisor before callbacks or cancellation cleanup.
        try { processRetirement.retire(reason) } catch (_: Throwable) { /* no safe-close claim */ }
        endpoint.retireFromOwner(reason) {
            // Ingress/late response health is retired before observers receive this signal,
            // but synchronous queue/coroutine cancellation callbacks run only afterward.
            closed.complete(LocalMasterCloseResult.PROCESS_RETIREMENT_REQUIRED)
        }
        // No Room.close(), executor join, custody unlock or replacement here. Even if a later
        // close happens to finish, the retired composition cannot become ACTIVE/CLOSED again.
    }
}

public fun openLocalMasterService(
    path: Path,
    scope: BranchScope,
    authority: AuthorityIdentity,
    handlers: List<MasterCommandHandler>,
    readHandlers: List<MasterReadHandler>,
    security: LocalMasterSecurityDependencies,
    runtime: MasterRuntimeVerifier,
    processRetirement: LocalMasterRetirement,
    clock: MasterClock = MasterClock { UtcInstant(Clock.System.now()) },
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    workers: Int = 4,
    maximumQueuedRequests: Int = 32,
    maximumRequestMillis: Long = 30_000,
    maximumCloseMillis: Long = 30_000,
    physicalRecordingGate: PhysicalRecordingExecutionGate = UnavailablePhysicalRecordingExecutionGate,
): JvmLocalMasterService {
    requireLocalMasterServicePathAndLimits(path, workers, maximumQueuedRequests, maximumRequestMillis, maximumCloseMillis)
    return try {
        requireLegacyMasterRoomProfile()
        val database = buildMasterRoom(Room.databaseBuilder<MasterRoomDatabase>(path.toString(), MasterRoomConstructor::initialize))
        JvmLocalMasterService(database, path, scope, authority, handlers, readHandlers, security, runtime,
            processRetirement, clock, dispatcher, workers, maximumQueuedRequests, maximumRequestMillis, maximumCloseMillis,
            physicalRecordingGate = physicalRecordingGate)
    } catch (failure: Throwable) {
        // Do not claim an unknown partially opened pool can be synchronously closed/reused.
        try { processRetirement.retire(LocalMasterRetirementReason.UNEXPECTED_SERVICE_FAILURE) } catch (_: Throwable) { }
        throw failure
    }
}

/**
 * Trusted native installation owner, not an operational endpoint or an application lifetime.
 * The private grouped service owns the ONLY Room pool, security source, workers and close.
 * No unwrap, generic verifier, context refresh or in-place go-live method is offered.
 */
public class JvmLocalMasterInstallationService internal constructor(
    private val owner: JvmLocalMasterService,
) {
    public val databasePath: Path get() = owner.databasePath
    public val scope: BranchScope get() = owner.scope
    public val authority: AuthorityIdentity get() = owner.authority
    public val state: LocalMasterServiceState get() = owner.state
    public val client: LocalMasterInstallationClient = checkNotNull(owner.installationClient)

    /**
     * Caller cancellation abandons only the wait. CLOSED means the actual grouped owner
     * quiesced, closed Room and retired its short installation context. Any other terminal
     * result requires physical supervisor reap before another activation; custody stays held.
     */
    public suspend fun quiesceAndClose(): LocalMasterCloseResult = owner.quiesceAndClose()
    override fun toString(): String = "JvmLocalMasterInstallationService([REDACTED])"
}

/**
 * Explicit OFFLINE INSTALLATION-ONLY opener for an already verified installed native context.
 * Dependencies are derived by REFERENCE from that exact context; UI supplies no verifier,
 * scope, appointment, peer admission, provisioning authority or accepting operational runtime.
 * The required entropy and retirement hooks still belong to the real supervised native owner.
 *
 * Native composition MUST already own exclusive storage/key/process custody and authorize
 * this exact path/storageInstanceId as fresh or exact initial-intent resume before Room opens.
 * This factory does not acquire that custody, journal the intent, create directories/enrollment,
 * prove path-to-native-instance binding, or qualify any provider. A current observation alone
 * does not establish those obligations. After path/limit preconditions pass, factory failure
 * irreversibly retires the context and requests process retirement; no unknown pool is
 * closed/reused by this function. Those preconditions perform no Room/native enrollment.
 *
 * After retaining the real installation receipt, native composition must actually close/reap
 * this owner before independently qualified normal activation. The short installation clock
 * is NEVER widened/swapped in place or reused as an ordinary restart/cloud lease.
 */
public fun openLocalMasterInstallationService(
    path: Path,
    context: InstalledOfflineInstallationContext,
    entropy: LocalAuthenticationEntropy,
    processRetirement: LocalMasterRetirement,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    workers: Int = 1,
    maximumQueuedRequests: Int = 1,
    maximumRequestMillis: Long = 30_000,
    maximumCloseMillis: Long = 30_000,
): JvmLocalMasterInstallationService {
    requireLocalMasterServicePathAndLimits(path, workers, maximumQueuedRequests, maximumRequestMillis, maximumCloseMillis)
    return try {
        checkNotNull(context.environment.observe()) { "Offline installation context unavailable" }
        val identity = context.approvedRequest.identity
        val security = LocalMasterSecurityDependencies(
            protection = context.protection,
            storageKey = context.storageKey,
            passwords = context.passwords,
            pepperReference = context.pepperReference,
            entropy = entropy,
            environment = context.environment,
            peers = LocalAuthenticatedPeerSource { null },
            maximumConcurrentAuthentication = 1,
            provisioning = UnavailableLocalProvisioningAuthority,
        )
        requireLegacyMasterRoomProfile()
        val database = buildMasterRoom(Room.databaseBuilder<MasterRoomDatabase>(path.toString(), MasterRoomConstructor::initialize))
        val owner = JvmLocalMasterService(database, path, identity.scope,
            AuthorityIdentity(identity.assignmentId, identity.masterDeviceId, identity.authorityEpoch),
            emptyList(), emptyList(), security, UnqualifiedMasterRuntime, processRetirement,
            // The inaccessible operational kernel clock is NOT installation UTC evidence.
            MasterClock { UtcInstant(Clock.System.now()) }, dispatcher, workers, maximumQueuedRequests,
            maximumRequestMillis, maximumCloseMillis, installationContext = context)
        JvmLocalMasterInstallationService(owner)
    } catch (failure: Throwable) {
        context.retire()
        try { processRetirement.retire(LocalMasterRetirementReason.UNEXPECTED_SERVICE_FAILURE) } catch (_: Throwable) { }
        throw failure
    }
}

private fun requireLocalMasterServicePathAndLimits(
    path: Path, workers: Int, maximumQueuedRequests: Int, maximumRequestMillis: Long, maximumCloseMillis: Long,
) {
    require(path.isAbsolute && path.normalize() == path && path.fileName.toString().isNotBlank())
    require(path.fileName.toString() != ":memory:")
    // Refuse path aliasing through any existing symlink, not only the final database filename.
    var cursor: Path? = path
    while (cursor != null) {
        require(!Files.isSymbolicLink(cursor)) { "Master path contains a symbolic link" }
        cursor = cursor.parent
    }
    require(Files.isDirectory(path.parent)) { "Provision the protected directory and acquire custody first" }
    require(!Files.exists(path) || Files.isRegularFile(path))
    require(workers in 1..16 && maximumQueuedRequests in 1..256)
    require(maximumRequestMillis in 1_000..300_000 && maximumCloseMillis in 1_000..300_000)
}
