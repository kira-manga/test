package com.ukkera.pos.rpc

import android.app.Application
import com.ukkera.pos.database.AndroidOriginalLocalClient
import com.ukkera.pos.database.AndroidOriginalRpcHostRegistration
import com.ukkera.pos.database.LocalMasterRpcHostResource
import com.ukkera.pos.database.LocalMasterRpcPeerRegistration
import com.ukkera.pos.security.LocalCompanyAuthorityResult
import com.ukkera.pos.security.LocalMasterSdkOriginalV1
import dev.p2pkit.rpc.RpcEndpoint
import dev.p2pkit.rpc.RpcLocalIdentity
import dev.p2pkit.rpc.RpcPlatform
import dev.p2pkit.rpc.RpcTrustStore
import dev.p2pkit.rpc.android
import dev.p2pkit.transport.lan.OrganizationLan
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** Native service/resource binding only. Shared code owns handoff and factory lifecycle. */
public class AndroidOriginalRpcHostOwner private constructor(
    private val application: Application,
    private val owner: LocalRpcOriginalHostOwner,
) {
    public val phase: StateFlow<LocalRpcFactoryPhase> get() = owner.phase
    public val original: StateFlow<LocalMasterSdkOriginalV1?> get() = owner.original

    /** Exact identity readback only; this does not start transport or business ingress. */
    public suspend fun acquire(): RpcLocalIdentity {
        AndroidLocalNetworkAccess.requireGranted(application)
        return owner.acquire()
    }

    /** Explicitly starts the same original Host after service-owned trust/registration checks. */
    public suspend fun start() {
        AndroidLocalNetworkAccess.requireGranted(application)
        owner.start()
    }

    /** Actual numeric address/listener port from that explicitly started Host. */
    public suspend fun endpoint(): RpcEndpoint {
        AndroidLocalNetworkAccess.requireGranted(application)
        return owner.endpoint()
    }

    /** Closes only this Host; the authoritative service retains its own native lifetime. */
    public suspend fun close(): Unit = owner.closeOwned()

    override fun toString(): String = "AndroidOriginalRpcHostOwner([REDACTED])"

    public companion object {
        /** Retain under the actual operational Master before any original/SDK work. */
        public fun retainExisting(application: Application, applicationScope: CoroutineScope,
            original: AndroidOriginalLocalClient, lan: OrganizationLan): AndroidOriginalRpcHostOwner {
            AndroidLocalNetworkAccess.requireGranted(application)
            val lifetime = LocalRpcOriginalHostOwner.retentionLifetime(applicationScope)
            require(original.belongsTo(application)) { "The original Master application is unavailable" }
            val resource = AndroidMasterRpcHostResource(application, original)
            resource.registration = original.retainRpcHostResource(resource)
            try {
                val retained = LocalRpcOriginalHostOwner(applicationScope, lifetime, resource,
                    {
                        AndroidLocalNetworkAccess.requireGranted(application)
                        RpcPlatform.android(application)
                    }, lan)
                resource.captured.complete(retained)
                return AndroidOriginalRpcHostOwner(application, retained)
            } catch (failure: Throwable) {
                // Service teardown observes this actual lost-constructor outcome, rather than
                // treating an empty holder as confirmation that no resource was constructed.
                resource.captured.completeExceptionally(failure)
                throw failure
            }
        }
    }
}

/** The service retains this exact wrapper before the common constructor can start work. */
private class AndroidMasterRpcHostResource(
    private val application: Application,
    private val original: AndroidOriginalLocalClient,
) : LocalMasterRpcHostResource, LocalRpcOriginalHostSource {
    lateinit var registration: AndroidOriginalRpcHostRegistration
    val captured = CompletableDeferred<LocalRpcOriginalHostOwner>()
    private val peers = CompletableDeferred<LocalMasterRpcPeerRegistration>()

    override fun bindPeerRegistration(registration: LocalMasterRpcPeerRegistration) {
        check(isCurrent() && peers.complete(registration)) { "Original Host peer registration is unavailable" }
    }

    override suspend fun peerRegistration(): LocalMasterRpcPeerRegistration = peers.await()

    override fun isCurrent(): Boolean =
        AndroidLocalNetworkAccess.isGranted(application) && registration.isCurrent()

    override suspend fun yieldOriginal(): LocalMasterSdkOriginalV1 {
        check(isCurrent()) { "Original Master RPC registration is unavailable" }
        val original = when (val result = registration.yieldOriginal()) {
            is LocalCompanyAuthorityResult.Value -> result.value
            is LocalCompanyAuthorityResult.Unavailable -> error("Original Master SDK yield is unavailable")
        }
        check(isCurrent()) { "Original Master RPC registration is unavailable" }
        return original
    }

    override suspend fun trustStore(): RpcTrustStore {
        check(isCurrent()) { "Original Master RPC registration is retired" }
        val trust = when (val result = original.acquireRpcHostTrust()) {
            is LocalCompanyAuthorityResult.Value -> result.value
            is LocalCompanyAuthorityResult.Unavailable -> error("Original Master RPC trust is unavailable")
        }
        check(isCurrent()) { "Original Master RPC registration is retired" }
        return LocalRpcTrustStore(trust)
    }

    override suspend fun closeOwned() {
        captured.await().closeOwned()
    }
}
