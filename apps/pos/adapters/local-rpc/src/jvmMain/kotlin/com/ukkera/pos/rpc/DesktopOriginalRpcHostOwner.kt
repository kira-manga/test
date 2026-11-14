package com.ukkera.pos.rpc

import com.ukkera.pos.database.AndroidOriginalLocalClient
import com.ukkera.pos.database.AndroidOriginalRpcHostRegistration
import com.ukkera.pos.database.LocalMasterRpcHostResource
import com.ukkera.pos.database.LocalMasterRpcPeerRegistration
import com.ukkera.pos.database.SelfOwnedMasterHostOwner
import com.ukkera.pos.security.LocalCompanyAuthorityResult
import com.ukkera.pos.security.LocalMasterSdkOriginal
import dev.p2pkit.core.security.JvmSecureIdentityStore
import dev.p2pkit.rpc.RpcEndpoint
import dev.p2pkit.rpc.RpcLocalIdentity
import dev.p2pkit.rpc.RpcPlatform
import dev.p2pkit.rpc.RpcTrustStore
import dev.p2pkit.rpc.jvm
import dev.p2pkit.transport.lan.OrganizationLan
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Desktop (Windows service / macOS launchd) binding of the shared [LocalRpcOriginalHostOwner]:
 * the official JVM RpcPlatform over the SAME OS identity store the Master's Kit used. Shared code
 * owns handoff, trust and factory lifecycle exactly as on Android.
 */
public class DesktopOriginalRpcHostOwner private constructor(private val owner: LocalRpcOriginalHostOwner) {
    public val phase: StateFlow<LocalRpcFactoryPhase> get() = owner.phase
    public val original: StateFlow<LocalMasterSdkOriginal?> get() = owner.original
    public suspend fun acquire(): RpcLocalIdentity = owner.acquire()
    public suspend fun start(): Unit = owner.start()
    public suspend fun endpoint(): RpcEndpoint = owner.endpoint()
    public suspend fun close(): Unit = owner.closeOwned()
    override fun toString(): String = "DesktopOriginalRpcHostOwner([REDACTED])"

    public companion object {
        /** Retain under the actual operational desktop Master before any original/SDK work. */
        public fun retainExisting(host: SelfOwnedMasterHostOwner, applicationScope: CoroutineScope,
            original: AndroidOriginalLocalClient, lan: OrganizationLan,
            identityStore: () -> JvmSecureIdentityStore): DesktopOriginalRpcHostOwner {
            val lifetime = LocalRpcOriginalHostOwner.retentionLifetime(applicationScope)
            require(original.belongsTo(host)) { "The original desktop Master is unavailable" }
            val resource = DesktopMasterRpcHostResource(original)
            resource.registration = original.retainRpcHostResource(resource)
            try {
                val retained = LocalRpcOriginalHostOwner(applicationScope, lifetime, resource,
                    { RpcPlatform.jvm(identityStore()) }, lan)
                resource.captured.complete(retained)
                return DesktopOriginalRpcHostOwner(retained)
            } catch (failure: Throwable) {
                resource.captured.completeExceptionally(failure)
                throw failure
            }
        }
    }
}

private class DesktopMasterRpcHostResource(private val original: AndroidOriginalLocalClient) :
    LocalMasterRpcHostResource, LocalRpcOriginalHostSource {
    lateinit var registration: AndroidOriginalRpcHostRegistration
    val captured = CompletableDeferred<LocalRpcOriginalHostOwner>()
    private val peers = CompletableDeferred<LocalMasterRpcPeerRegistration>()

    override fun bindPeerRegistration(registration: LocalMasterRpcPeerRegistration) {
        check(isCurrent() && peers.complete(registration)) { "Original Host peer registration is unavailable" }
    }
    override suspend fun peerRegistration(): LocalMasterRpcPeerRegistration = peers.await()
    override fun isCurrent(): Boolean = registration.isCurrent()
    override suspend fun yieldOriginal(): LocalMasterSdkOriginal {
        check(isCurrent()) { "Original Master RPC registration is unavailable" }
        val yielded = when (val result = registration.yieldOriginal()) {
            is LocalCompanyAuthorityResult.Value -> result.value
            is LocalCompanyAuthorityResult.Unavailable -> error("Original Master SDK yield is unavailable")
        }
        check(isCurrent()) { "Original Master RPC registration is unavailable" }
        return yielded
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
    override suspend fun closeOwned() { captured.await().closeOwned() }
}
