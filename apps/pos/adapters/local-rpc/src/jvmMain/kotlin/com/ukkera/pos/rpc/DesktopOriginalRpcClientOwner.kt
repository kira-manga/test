package com.ukkera.pos.rpc

import com.ukkera.pos.security.LocalOriginalPairingTarget
import com.ukkera.pos.types.DomainId
import dev.p2pkit.core.security.JvmSecureIdentityStore
import dev.p2pkit.rpc.RpcPlatform
import dev.p2pkit.rpc.jvm
import dev.p2pkit.transport.lan.OrganizationLan
import kotlinx.coroutines.CoroutineScope

/** Desktop configuration of the shared [LocalOriginalRpcClientOwner]: official JVM RpcPlatform with an OS identity store. */
public object DesktopOriginalRpcClientOwner {
    /** Selected-original route; the identity store is the same OS-protected store the target's Kit used. */
    public fun retainExisting(applicationScope: CoroutineScope, target: LocalOriginalPairingTarget,
        originalPairingActionId: DomainId, lan: OrganizationLan, identityStore: () -> JvmSecureIdentityStore): LocalOriginalRpcClientOwner =
        LocalOriginalRpcClientOwner.retainExisting(applicationScope, target, originalPairingActionId, lan,
            { RpcPlatform.jvm(identityStore()) }, {})
}
