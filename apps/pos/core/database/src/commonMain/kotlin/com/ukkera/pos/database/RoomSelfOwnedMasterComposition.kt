package com.ukkera.pos.database

import com.ukkera.pos.security.*
import kotlinx.coroutines.CoroutineDispatcher

/** One original database/owner/security source/kernel. No accepting provider/runtime can be
 * supplied. Installation and operational ingress are built from the same protected source. */
internal class RoomSelfOwnedMasterComposition(
    internal val owner: RoomRuntimeHistoryOwner,
    native: SelfOwnedRuntimeSecurity,
    private val localPeer: LocalAuthenticatedPeer,
) {
    private val rpcSessionFence = LocalMasterRpcSessionFence()
    private val rpcPeers = LocalMasterRpcPeerRegistry(owner, localPeer, native.entropy, native.environment, rpcSessionFence)
    private val sameSecurity=LocalMasterSecurity(owner.database,owner.originalScope,LocalMasterSecurityDependencies(
        native.protection,native.storageKey,native.passwords,native.pepper,native.entropy,native.environment,
        LocalAuthenticatedPeerSource(rpcPeers::capture),
        maximumConcurrentAuthentication=1), rpcSessionFence)
    internal val initializer: RoomSelfOwnedBranchInitializer = sameSecurity.originalBranchInitializer(owner)
    /** Same owner/security as the operational endpoint; no endpoint is created for this ingress. */
    internal val authentication: com.ukkera.pos.security.LocalAuthenticationService = sameSecurity.authentication
    internal val lifecycle: com.ukkera.pos.security.LocalMasterLifecycleService = sameSecurity.originalLifecycle(owner)
    private var kernel: AtomicMasterStore?=null
    private var operationalEndpoint: LocalMasterEndpoint?=null
    private var employeeEnrollments: RoomSelfOwnedEmployeeEnrollments?=null
    private var employeePolicyActivations: RoomSelfOwnedEmployeePolicyActivations?=null
    private var deviceAdmissions: RoomSelfOwnedDeviceAdmissions?=null
    private var rpcTrust: RoomLocalMasterRpcTrust?=null
    internal val endpoint: LocalMasterEndpoint? get() = operationalEndpoint

    internal fun originalEmployeeEnrollments(native: LocalCompanyEmployeeEnrollments): RoomSelfOwnedEmployeeEnrollments {
        check(owner.operational && localPeer.isCurrent())
        val endpoint = checkNotNull(operationalEndpoint)
        employeeEnrollments?.let { check(it.native === native); return it }
        return sameSecurity.originalEmployeeEnrollments(owner, native).also {
            endpoint.bindOriginalEmployeeEnrollments(it)
            employeeEnrollments = it
        }
    }
    internal fun originalEmployeePolicyActivations(native: LocalCompanyEmployeePolicyActivations): RoomSelfOwnedEmployeePolicyActivations {
        check(owner.operational && localPeer.isCurrent())
        val endpoint = checkNotNull(operationalEndpoint)
        employeePolicyActivations?.let { check(it.native === native); return it }
        return sameSecurity.originalEmployeePolicyActivations(owner, native, endpoint).also {
            endpoint.bindOriginalEmployeePolicyActivations(it)
            employeePolicyActivations = it
        }
    }
    internal fun originalDeviceAdmissions(native: LocalCompanyDevicePairings): RoomSelfOwnedDeviceAdmissions {
        check(owner.operational && localPeer.isCurrent() && native.belongsTo(owner.originalCustody))
        val endpoint = checkNotNull(operationalEndpoint)
        deviceAdmissions?.let { check(it.native === native); return it }
        return sameSecurity.originalDeviceAdmissions(owner, native, endpoint).also {
            endpoint.bindOriginalDeviceAdmissions(it)
            deviceAdmissions = it
        }
    }

    internal fun originalRpcTrust(native: LocalCompanyDevicePairings): RoomLocalMasterRpcTrust {
        check(owner.operational && localPeer.isCurrent() && operationalEndpoint != null && native.belongsTo(owner.originalCustody))
        rpcTrust?.let { return it }
        return RoomLocalMasterRpcTrust(owner, sameSecurity.originalStore, sameSecurity.originalSource, native)
            .also { rpcTrust = it }
    }

    internal fun originalRpcPeerRegistration(lifetime: LocalMasterRpcPeerRegistrationLifetime,
        trust: LocalMasterRpcTrust): LocalMasterRpcPeerRegistration {
        check(owner.operational && localPeer.isCurrent())
        return rpcPeers.bind(lifetime, trust, owner.compositionProfile.appId(owner.originalScope),
            checkNotNull(operationalEndpoint), sameSecurity.principalFactory)
    }

    internal suspend fun awaitRpcPeerCleanup() { rpcPeers.awaitCleanup() }

    internal fun openOperational(handlers: List<MasterCommandHandler>,reads: List<MasterReadHandler>,clock: MasterClock,
        retirement: LocalMasterRetirement,watchdog: CoroutineDispatcher,dispatcher: CoroutineDispatcher,
        physical: PhysicalRecordingExecutionGate,imports: List<NativeOriginalImportInterpreter>,
        stockProjection: StockCanonicalProjectionV2?,historyProducer: RuntimeHistoryBusinessProducer?): LocalMasterEndpoint {
        check(owner.operational && kernel==null && operationalEndpoint==null && localPeer.isCurrent())
        val store=AtomicMasterStore(owner.database,owner.originalScope,owner.originalAuthority,handlers,
            sameSecurity.authorization,owner.verifier,clock,readHandlers=reads,approvalSource=sameSecurity.transactionApprovals,
            physicalRecordingGate=physical,physicalInstructionRegistry=sameSecurity.physicalInstructionRegistry,
            runtimeHistoryOwner=owner,nativeOriginalImports=imports,stockProjection=stockProjection,
            historyProducer=historyProducer)
        kernel=store
        val approvals=sameSecurity.createApprovalService(store.approvalCommandPreparation())
        return LocalMasterEndpoint(store,sameSecurity.principalFactory,sameSecurity.authentication,sameSecurity.credentials,approvals,
            retirement,watchdog,dispatcher,workers=1,maximumQueuedRequests=32,maximumRequestMillis=60_000,
            administration=sameSecurity.administration)
            .also {
                operationalEndpoint=it
                it.bindOriginalSerialPublications(sameSecurity.originalSerialPublications(owner))
                store.bindLocalDisplayDelivery(it,localPeer)
            }
    }
    internal fun deny() { rpcPeers.retireAll(); operationalEndpoint?.stopAccepting(); owner.denyBusiness() }
    internal suspend fun close() {
        deny(); operationalEndpoint?.awaitQuiescence(); rpcPeers.awaitCleanup(); owner.close()
    }
}
