package com.ukkera.pos.rpc

import com.ukkera.pos.database.LocalMasterPhysicalRpcProtocolV1
import com.ukkera.pos.security.PayloadDigest
import dev.p2pkit.rpc.RpcApplicationSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Bounded immutable request correlation DATA, never employee or physical authority.
 * One actual SDK resource may carry successive native protocol stages. Each SDK call
 * still binds its complete separately decoded body and undergoes fresh protected checks. */
internal class LocalRpcPhysicalCallScopes {
    internal class Call internal constructor(val binding: PayloadDigest, val resource: String)
    private class Session(val actual: RpcApplicationSession) {
        val calls = linkedMapOf<String, Call>()
    }
    private val gate = Mutex()
    private val sessions = mutableListOf<Session>()
    private var count = 0

    suspend fun admit(actual: RpcApplicationSession, binding: PayloadDigest): Call? = gate.withLock {
        if (!actual.isActive) return@withLock null
        val resource = "${LocalMasterPhysicalRpcProtocolV1.INTERACTIVE.name}#${binding.value}"
        val session = sessions.firstOrNull { it.actual === actual }
        session?.calls?.get(resource)?.let { return@withLock it.takeIf { it.binding == binding } }
        if ((session?.calls?.size ?: 0) >= MAX_SESSION_CALLS || count >= MAX_GLOBAL_CALLS) return@withLock null
        val call = Call(binding, resource)
        (session ?: Session(actual).also { sessions.add(it) }).calls[resource] = call
        count++
        call
    }

    suspend fun capture(actual: RpcApplicationSession, resource: String): Call? = gate.withLock {
        if (!actual.isActive) null else sessions.firstOrNull { it.actual === actual }?.calls?.get(resource)
    }

    suspend fun current(actual: RpcApplicationSession, call: Call): Boolean = gate.withLock {
        actual.isActive && sessions.firstOrNull { it.actual === actual }?.calls?.get(call.resource) === call
    }

    suspend fun removeRevoked(actual: RpcApplicationSession) = gate.withLock {
        check(!actual.isActive)
        sessions.firstOrNull { it.actual === actual }?.let { sessions.remove(it); count -= it.calls.size }
        check(count >= 0)
    }

    suspend fun removeAllRevoked() = gate.withLock {
        check(sessions.none { it.actual.isActive })
        sessions.clear(); count = 0
    }

    private companion object {
        const val MAX_SESSION_CALLS = 1_024
        const val MAX_GLOBAL_CALLS = 8_192
    }
}
