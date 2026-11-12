package com.ukkera.pos.rpc

import com.ukkera.pos.database.LocalMasterRpcWorkflowProfile
import com.ukkera.pos.security.LocalCompositionSdkProfileV1
import com.ukkera.pos.security.LocalDevicePairingApprovedMaster
import com.ukkera.pos.security.LocalDevicePairingSdkOriginal
import com.ukkera.pos.security.LocalOriginalPairingTarget
import com.ukkera.pos.security.LocalOriginalPairingTargetResult
import com.ukkera.pos.security.SelfOwnedInstallationFailureV1
import com.ukkera.pos.types.DomainId
import dev.p2pkit.rpc.RpcEndpoint
import dev.p2pkit.rpc.RpcLocalIdentity
import dev.p2pkit.rpc.RpcPlatform
import dev.p2pkit.rpc.RpcTrustStore
import dev.p2pkit.transport.lan.OrganizationLan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

public class LocalOriginalRpcClientUnavailable internal constructor(
    public val reason: SelfOwnedInstallationFailureV1,
) : IllegalStateException("Original SDK DATA is unavailable")

/**
 * Shared (Android/desktop) existing-original to official Client handoff. Successful retention
 * transfers the exact target into one process-held composition slot; a second handoff is refused
 * until actual owned close. The platform supplies only its official RpcPlatform and OS network
 * access check. The selected-original route alone can connect; it exposes no raw role, admission,
 * employee permission or serving capability.
 */
public class LocalOriginalRpcClientOwner private constructor(
    private val requireNetwork: () -> Unit,
    private val retained: LocalRpcOriginalClientOwner,
) {
    public val phase: StateFlow<LocalRpcFactoryPhase> get() = retained.phase
    public val original: StateFlow<LocalDevicePairingSdkOriginal?> get() = retained.original
    public val approvedMaster: StateFlow<LocalDevicePairingApprovedMaster?> get() = retained.approvedMaster

    public suspend fun acquire(): RpcLocalIdentity { requireNetwork(); return retained.acquire() }
    public suspend fun connect(endpoint: RpcEndpoint) { requireNetwork(); retained.connect(endpoint) }
    public suspend fun authenticationService(): LocalRpcRemoteAuthenticationService { requireNetwork(); return retained.authenticationService() }
    public suspend fun supplierClient(): LocalRpcRemoteSupplierClient { requireNetwork(); return retained.supplierClient() }
    public suspend fun directoryClient(profile: LocalMasterRpcWorkflowProfile): LocalRpcRemoteDirectoryClient {
        requireNetwork(); return retained.directoryClient(profile)
    }
    /** Awaits the same exact source/factory cleanup; caller cancellation cannot abandon it. */
    public suspend fun close(): Unit = retained.close()
    override fun toString(): String = "LocalOriginalRpcClientOwner([REDACTED])"

    public companion object {
        /** Identity-only compatibility route; cannot connect to a selected Master. */
        public fun retainExisting(applicationScope: CoroutineScope, target: LocalOriginalPairingTarget,
            lan: OrganizationLan, trustStore: RpcTrustStore, platform: () -> RpcPlatform,
            requireNetwork: () -> Unit): LocalOriginalRpcClientOwner {
            requireNetwork()
            val lifetime = LocalRpcOriginalClientOwner.retentionLifetime(applicationScope, trustStore)
            val source = OriginalTargetClientSource(target)
            OriginalTargetClientSlot.retain(source)
            try {
                val retained = LocalRpcOriginalClientOwner(applicationScope, lifetime, source,
                    { requireNetwork(); platform() }, lan, trustStore, { OriginalTargetClientSlot.release(source) })
                source.retainOwner(retained)
                return LocalOriginalRpcClientOwner(requireNetwork, retained)
            } catch (failure: Throwable) {
                // Transfer was claimed: retain its target and uncertain construction outcome.
                source.retainFailure(failure); throw failure
            }
        }

        /** One existing target and explicit original action; shared code supplies selected-Master trust. */
        public fun retainExisting(applicationScope: CoroutineScope, target: LocalOriginalPairingTarget,
            originalPairingActionId: DomainId, lan: OrganizationLan, platform: () -> RpcPlatform,
            requireNetwork: () -> Unit): LocalOriginalRpcClientOwner {
            requireNetwork()
            val lifetime = LocalRpcOriginalClientOwner.retentionLifetime(applicationScope)
            val source = OriginalTargetClientSource(target)
            OriginalTargetClientSlot.retain(source)
            try {
                val retained = LocalRpcOriginalClientOwner(applicationScope, lifetime,
                    source.selectedMasterSource(originalPairingActionId),
                    { requireNetwork(); platform() }, lan, { OriginalTargetClientSlot.release(source) })
                source.retainOwner(retained)
                return LocalOriginalRpcClientOwner(requireNetwork, retained)
            } catch (failure: Throwable) {
                source.retainFailure(failure); throw failure
            }
        }
    }
}

/** Holds the exact native source and shared owner even after all temporary callers exit. */
@OptIn(ExperimentalAtomicApi::class)
private object OriginalTargetClientSlot {
    private val retained = AtomicReference<Map<LocalCompositionSdkProfileV1, OriginalTargetClientSource>>(emptyMap())

    fun retain(source: OriginalTargetClientSource) {
        while (true) {
            val current = retained.load()
            check(current[source.compositionProfile] == null) { "An original RPC client composition is already retained" }
            if (retained.compareAndSet(current, current + (source.compositionProfile to source))) return
        }
    }

    fun release(source: OriginalTargetClientSource) {
        while (true) {
            val current = retained.load()
            check(current[source.compositionProfile] === source) { "Original RPC client holder differs" }
            if (retained.compareAndSet(current, current - source.compositionProfile)) return
        }
    }
}

private class OriginalTargetClientSource(private val target: LocalOriginalPairingTarget) : LocalRpcOriginalClientSource {
    val compositionProfile = target.compositionProfile
    private var owner: LocalRpcOriginalClientOwner? = null
    private var constructionFailure: Throwable? = null

    fun retainOwner(retained: LocalRpcOriginalClientOwner) { check(owner == null); owner = retained }
    fun retainFailure(failure: Throwable) { check(constructionFailure == null); constructionFailure = failure }

    override suspend fun recoverOriginal(): LocalDevicePairingSdkOriginal {
        try { return readOriginal() } finally { target.denyNewWork() }
    }

    fun selectedMasterSource(originalPairingActionId: DomainId): LocalRpcSelectedMasterClientSource =
        object : LocalRpcSelectedMasterClientSource {
            override suspend fun recover(): LocalRpcSelectedMasterClientReadback {
                try {
                    val original = readOriginal()
                    val approvedMaster = when (val result = target.recoverApprovedMasterData(originalPairingActionId)) {
                        is LocalOriginalPairingTargetResult.Value -> result.value
                        is LocalOriginalPairingTargetResult.Unavailable -> throw LocalOriginalRpcClientUnavailable(result.reason)
                    }
                    return LocalRpcSelectedMasterClientReadback(original = original,
                        approvedMaster = approvedMaster, originalPairingActionId = originalPairingActionId)
                } finally {
                    // recoverOriginal() here would fence the target before Master readback.
                    target.denyNewWork()
                }
            }
            override suspend fun close(): Unit = this@OriginalTargetClientSource.close()
        }

    private suspend fun readOriginal(): LocalDevicePairingSdkOriginal = when (val result = target.recoverSdkOriginalData()) {
        is LocalOriginalPairingTargetResult.Value -> result.value
        is LocalOriginalPairingTargetResult.Unavailable -> throw LocalOriginalRpcClientUnavailable(result.reason)
    }

    override suspend fun close(): Unit = target.close()
}
