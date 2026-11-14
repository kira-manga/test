package com.ukkera.pos.rpc

import android.app.Application
import com.ukkera.pos.database.LocalMasterRpcWorkflowProfile
import com.ukkera.pos.security.AndroidDevicePairingTarget
import com.ukkera.pos.security.LocalDevicePairingApprovedMaster
import com.ukkera.pos.security.LocalDevicePairingSdkOriginal
import com.ukkera.pos.types.DomainId
import dev.p2pkit.rpc.RpcEndpoint
import dev.p2pkit.rpc.RpcLocalIdentity
import dev.p2pkit.rpc.RpcPlatform
import dev.p2pkit.rpc.RpcTrustStore
import dev.p2pkit.rpc.android
import dev.p2pkit.transport.lan.OrganizationLan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** Source-compatible Android name for the shared original-unavailable failure. */
public typealias AndroidOriginalRpcClientUnavailable = LocalOriginalRpcClientUnavailable

/**
 * Android configuration of the shared [LocalOriginalRpcClientOwner]: the official Android RpcPlatform
 * and the local-network permission check. All handoff/slot/selected-Master logic is shared with desktop.
 */
public class AndroidOriginalRpcClientOwner private constructor(private val shared: LocalOriginalRpcClientOwner) :
    LocalOriginalRpcAuthenticationSource {
    public val phase: StateFlow<LocalRpcFactoryPhase> get() = shared.phase
    override val original: StateFlow<LocalDevicePairingSdkOriginal?> get() = shared.original
    override val approvedMaster: StateFlow<LocalDevicePairingApprovedMaster?> get() = shared.approvedMaster
    public suspend fun acquire(): RpcLocalIdentity = shared.acquire()
    public suspend fun connect(endpoint: RpcEndpoint): Unit = shared.connect(endpoint)
    override suspend fun authenticationService(): LocalRpcRemoteAuthenticationService = shared.authenticationService()
    public suspend fun supplierClient(): LocalRpcRemoteSupplierClient = shared.supplierClient()
    public suspend fun directoryClient(profile: LocalMasterRpcWorkflowProfile): LocalRpcRemoteDirectoryClient =
        shared.directoryClient(profile)
    public suspend fun employeeCreationClient(directory: LocalRpcRemoteDirectoryClient): LocalRpcRemoteEmployeeCreationClient =
        shared.employeeCreationClient(directory)
    public suspend fun close(): Unit = shared.close()
    override fun toString(): String = "AndroidOriginalRpcClientOwner([REDACTED])"

    public companion object {
        /** Identity-only compatibility route. */
        public fun retainExisting(application: Application, applicationScope: CoroutineScope, target: AndroidDevicePairingTarget,
            lan: OrganizationLan, trustStore: RpcTrustStore): AndroidOriginalRpcClientOwner =
            AndroidOriginalRpcClientOwner(LocalOriginalRpcClientOwner.retainExisting(applicationScope, target, lan, trustStore,
                { RpcPlatform.android(application) }, { AndroidLocalNetworkAccess.requireGranted(application) }))

        /** Selected-original route; shared code supplies selected-Master trust. */
        public fun retainExisting(application: Application, applicationScope: CoroutineScope, target: AndroidDevicePairingTarget,
            originalPairingActionId: DomainId, lan: OrganizationLan): AndroidOriginalRpcClientOwner =
            AndroidOriginalRpcClientOwner(LocalOriginalRpcClientOwner.retainExisting(applicationScope, target,
                originalPairingActionId, lan, { RpcPlatform.android(application) },
                { AndroidLocalNetworkAccess.requireGranted(application) }))
    }
}
