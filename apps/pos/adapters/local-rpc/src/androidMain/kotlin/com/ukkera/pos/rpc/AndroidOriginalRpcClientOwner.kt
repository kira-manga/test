package com.ukkera.pos.rpc

import android.app.Application
import com.ukkera.pos.database.LocalMasterRpcWorkflowProfile
import com.ukkera.pos.security.AndroidDevicePairingTarget
import com.ukkera.pos.security.AndroidDevicePairingTargetResult
import com.ukkera.pos.security.LocalDevicePairingApprovedMaster
import com.ukkera.pos.security.LocalDevicePairingSdkOriginal
import com.ukkera.pos.security.SelfOwnedInstallationFailureV1
import com.ukkera.pos.types.DomainId
import dev.p2pkit.rpc.RpcLocalIdentity
import dev.p2pkit.rpc.RpcEndpoint
import dev.p2pkit.rpc.RpcPlatform
import dev.p2pkit.rpc.RpcTrustStore
import dev.p2pkit.rpc.android
import dev.p2pkit.transport.lan.OrganizationLan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

public class AndroidOriginalRpcClientUnavailable internal constructor(
    public val reason: SelfOwnedInstallationFailureV1,
) : IllegalStateException("Original SDK DATA is unavailable")

/**
 * Application-owned Android existing-original to official Client handoff.
 * Successful retention transfers the exact target into one process-held composition slot.
 * Validation failure leaves that target with its caller; other callers must stop using a
 * successfully transferred target. A second handoff is refused until actual owned close.
 * Its native originals are read and its actual owned close is awaited before a replacement factory
 * uses the same AppId/name. An unavailable original or uncertain close is never repaired/retried.
 *
 * The selected-Master overload revalidates the same target's original approved pairing action;
 * shared code checks correspondence and composes its single original Master pin. The identity-only
 * overload requires a supplied Client-compatible trust store. Explicit approved LAN remains a
 * prerequisite. Only the selected-original route can explicitly connect to a numeric endpoint;
 * it exposes no raw role, admission, employee permission or serving capability. Its narrow
 * authentication port uses that same retained connection, never a partial business client.
 * Application shutdown with unknown cleanup retains the holder rather than
 * reporting successful termination.
 */
public class AndroidOriginalRpcClientOwner private constructor(
    private val application: Application,
    private val retained: LocalRpcOriginalClientOwner,
) {
    public val phase: StateFlow<LocalRpcFactoryPhase> get() = retained.phase
    public val original: StateFlow<LocalDevicePairingSdkOriginal?> get() = retained.original
    /** Qualified original selection only; it grants no current admission/session/permission. */
    public val approvedMaster: StateFlow<LocalDevicePairingApprovedMaster?> get() = retained.approvedMaster

    /** The actual unstarted Client factory identity, after exact original correspondence. */
    public suspend fun acquire(): RpcLocalIdentity {
        AndroidLocalNetworkAccess.requireGranted(application)
        return retained.acquire()
    }

    /** Uses only the retained original Master pin; identity-only handoffs cannot connect. */
    public suspend fun connect(endpoint: RpcEndpoint) {
        AndroidLocalNetworkAccess.requireGranted(application)
        retained.connect(endpoint)
    }

    /**
     * Only after the selected-original connection is actually ready. The returned service carries
     * its owner's stable local connection correlation; neither that ID nor caller IDs cross the wire.
     */
    public suspend fun authenticationService(): LocalRpcRemoteAuthenticationService {
        AndroidLocalNetworkAccess.requireGranted(application)
        return retained.authenticationService()
    }

    /** Fixed supplier-only business port; it shares the actual original authentication connection. */
    public suspend fun supplierClient(): LocalRpcRemoteSupplierClient {
        AndroidLocalNetworkAccess.requireGranted(application)
        return retained.supplierClient()
    }

    /** Native facade only; profile selection and lifecycle remain in shared code. */
    public suspend fun directoryClient(profile: LocalMasterRpcWorkflowProfile): LocalRpcRemoteDirectoryClient {
        AndroidLocalNetworkAccess.requireGranted(application)
        return retained.directoryClient(profile)
    }

    /** Awaits the same exact source/factory cleanup; caller cancellation cannot abandon it. */
    public suspend fun close(): Unit = retained.close()

    override fun toString(): String = "AndroidOriginalRpcClientOwner([REDACTED])"

    public companion object {
        /** Identity-only compatibility route. The holder precedes any suspending DATA read. */
        public fun retainExisting(
            application: Application,
            applicationScope: CoroutineScope,
            target: AndroidDevicePairingTarget,
            lan: OrganizationLan,
            trustStore: RpcTrustStore,
        ): AndroidOriginalRpcClientOwner {
            AndroidLocalNetworkAccess.requireGranted(application)
            val lifetime = LocalRpcOriginalClientOwner.retentionLifetime(applicationScope, trustStore)
            val source = AndroidOriginalRpcClientSource(target)
            AndroidOriginalRpcClientSlot.retain(source)
            try {
                val retained = LocalRpcOriginalClientOwner(applicationScope, lifetime, source,
                    {
                        AndroidLocalNetworkAccess.requireGranted(application)
                        RpcPlatform.android(application)
                    }, lan, trustStore,
                    { AndroidOriginalRpcClientSlot.release(source) })
                source.retainOwner(retained)
                return AndroidOriginalRpcClientOwner(application, retained)
            } catch (failure: Throwable) {
                // Transfer was claimed: retain its target and uncertain construction outcome.
                // No automatic slot release or second attempt after a lost holder constructor.
                source.retainFailure(failure)
                throw failure
            }
        }

        /**
         * One existing target and explicit original action. Both genuine native readbacks precede
         * its work fence and actual close; the shared owner supplies selected-Master trust.
         */
        public fun retainExisting(
            application: Application,
            applicationScope: CoroutineScope,
            target: AndroidDevicePairingTarget,
            originalPairingActionId: DomainId,
            lan: OrganizationLan,
        ): AndroidOriginalRpcClientOwner {
            AndroidLocalNetworkAccess.requireGranted(application)
            val lifetime = LocalRpcOriginalClientOwner.retentionLifetime(applicationScope)
            val source = AndroidOriginalRpcClientSource(target)
            AndroidOriginalRpcClientSlot.retain(source)
            try {
                val retained = LocalRpcOriginalClientOwner(applicationScope, lifetime,
                    source.selectedMasterSource(originalPairingActionId),
                    {
                        AndroidLocalNetworkAccess.requireGranted(application)
                        RpcPlatform.android(application)
                    }, lan,
                    { AndroidOriginalRpcClientSlot.release(source) })
                source.retainOwner(retained)
                return AndroidOriginalRpcClientOwner(application, retained)
            } catch (failure: Throwable) {
                // The same slot retains the exact target and any lost constructor outcome.
                source.retainFailure(failure)
                throw failure
            }
        }
    }
}

/** Holds the exact native source and shared owner even after all temporary callers exit. */
private object AndroidOriginalRpcClientSlot {
    private val gate = Any()
    private val retained = mutableMapOf<com.ukkera.pos.security.LocalCompositionSdkProfileV1, AndroidOriginalRpcClientSource>()

    fun retain(source: AndroidOriginalRpcClientSource) {
        synchronized(gate) {
            check(retained[source.compositionProfile] == null) { "An original RPC client composition is already retained" }
            retained[source.compositionProfile] = source
        }
    }

    fun release(source: AndroidOriginalRpcClientSource) {
        synchronized(gate) {
            check(retained[source.compositionProfile] === source) { "Original RPC client holder differs" }
            retained.remove(source.compositionProfile)
        }
    }
}

private class AndroidOriginalRpcClientSource(
    private val target: AndroidDevicePairingTarget,
) : LocalRpcOriginalClientSource {
    val compositionProfile = target.compositionProfile
    @Volatile private var owner: LocalRpcOriginalClientOwner? = null
    @Volatile private var constructionFailure: Throwable? = null

    fun retainOwner(retained: LocalRpcOriginalClientOwner) {
        check(owner == null)
        owner = retained
    }

    fun retainFailure(failure: Throwable) {
        check(constructionFailure == null)
        constructionFailure = failure
    }

    override suspend fun recoverOriginal(): LocalDevicePairingSdkOriginal {
        try {
            return readOriginal()
        } finally {
            target.denyNewWork()
        }
    }

    /** Both paths retain this same target/owner/failure slot until confirmed common cleanup. */
    fun selectedMasterSource(originalPairingActionId: DomainId): LocalRpcSelectedMasterClientSource =
        object : LocalRpcSelectedMasterClientSource {
            override suspend fun recover(): LocalRpcSelectedMasterClientReadback {
                try {
                    val original = readOriginal()
                    val approvedMaster = when (val result = target.recoverApprovedMaster(originalPairingActionId)) {
                        is AndroidDevicePairingTargetResult.Value -> result.value
                        is AndroidDevicePairingTargetResult.Unavailable -> throw AndroidOriginalRpcClientUnavailable(result.reason)
                    }
                    return LocalRpcSelectedMasterClientReadback(original = original,
                        approvedMaster = approvedMaster, originalPairingActionId = originalPairingActionId)
                } finally {
                    // Calling recoverOriginal() here would fence the target before Master readback.
                    target.denyNewWork()
                }
            }

            override suspend fun close(): Unit = this@AndroidOriginalRpcClientSource.close()
        }

    private suspend fun readOriginal(): LocalDevicePairingSdkOriginal =
        when (val result = target.recoverSdkOriginal()) {
            is AndroidDevicePairingTargetResult.Value -> result.value
            is AndroidDevicePairingTargetResult.Unavailable -> throw AndroidOriginalRpcClientUnavailable(result.reason)
        }

    override suspend fun close(): Unit = target.close()
}
