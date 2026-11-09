package com.ukkera.pos.database

import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.InstalledOfflineInstallationContext
import kotlinx.coroutines.CoroutineDispatcher

/**
 * One database, protected store/source, kernel, approval preparation and work owner.
 * Native openers compose this once, never a second security owner or initializer pool.
 * Nothing here acquires native custody, qualifies providers or permits a second activation.
 *
 * Offline installation is selected only by the actual immutable installed context. The
 * existing same-security hook verifies exact provider/environment object identity and key
 * references. There is no public initializer-verifier parameter or accepting runtime flag.
 * The self-owned composition may pass only its concrete admitted history owner. The ordinary
 * builders' absent owner leaves native-original intake unavailable; neither handler registration
 * nor a runtime-verifier enum creates an owner. The endpoint registers the SAME physical gate
 * with that owner on its actual clean worker before native enumeration/retention.
 */
internal class RoomLocalMasterComposition(
    database: MasterRoomDatabase,
    scope: BranchScope,
    authority: AuthorityIdentity,
    handlers: List<MasterCommandHandler>,
    readHandlers: List<MasterReadHandler>,
    security: LocalMasterSecurityDependencies,
    runtime: MasterRuntimeVerifier,
    clock: MasterClock,
    retirement: LocalMasterRetirement,
    watchdogDispatcher: CoroutineDispatcher,
    dispatcher: CoroutineDispatcher,
    workers: Int,
    maximumQueuedRequests: Int,
    maximumRequestMillis: Long,
    installationContext: InstalledOfflineInstallationContext? = null,
    physicalRecordingGate: PhysicalRecordingExecutionGate = UnavailablePhysicalRecordingExecutionGate,
    runtimeHistoryOwner: RoomRuntimeHistoryOwner? = null,
    nativeOriginalImports: List<NativeOriginalImportInterpreter> = emptyList(),
    stockProjection: StockCanonicalProjectionV2? = null,
) {
    init {
        if (installationContext != null) {
            val identity = installationContext.approvedRequest.identity
            check(scope == identity.scope &&
                authority == AuthorityIdentity(identity.assignmentId, identity.masterDeviceId, identity.authorityEpoch))
            // Defense in depth, not the admission boundary: the endpoint ALSO rejects all
            // operational work, including authentication/admin that bypass the command registry.
            check(handlers.isEmpty() && readHandlers.isEmpty() && runtime === UnqualifiedMasterRuntime)
            check(physicalRecordingGate === UnavailablePhysicalRecordingExecutionGate)
            check(runtimeHistoryOwner == null && nativeOriginalImports.isEmpty() && stockProjection == null)
        }
    }

    private val sameSecurity = LocalMasterSecurity(database, scope, security)
    private val store = AtomicMasterStore(database, scope, authority, handlers,
        sameSecurity.authorization, runtime, clock, readHandlers = readHandlers,
        approvalSource = sameSecurity.transactionApprovals, physicalRecordingGate = physicalRecordingGate,
        physicalInstructionRegistry = sameSecurity.physicalInstructionRegistry,
        runtimeHistoryOwner = runtimeHistoryOwner, nativeOriginalImports = nativeOriginalImports,
        stockProjection = stockProjection)
    // Capture this exact kernel's preparation ONCE, using that same protected store/source.
    private val actionApprovals = sameSecurity.createApprovalService(store.approvalCommandPreparation())
    private val initializer = installationContext?.let(sameSecurity::createOfflineBranchInitializer)

    val endpoint: LocalMasterEndpoint = LocalMasterEndpoint(
        store, sameSecurity.principalFactory, sameSecurity.authentication, sameSecurity.credentials, actionApprovals,
        retirement, watchdogDispatcher, dispatcher, workers, maximumQueuedRequests, maximumRequestMillis,
        installationInitializer = initializer,
        administration = sameSecurity.administration,
    )
    val installationClient: LocalMasterInstallationClient? get() = endpoint.installationClient
}
