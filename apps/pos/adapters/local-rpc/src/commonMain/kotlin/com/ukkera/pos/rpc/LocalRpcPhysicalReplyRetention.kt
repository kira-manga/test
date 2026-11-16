package com.ukkera.pos.rpc

import dev.p2pkit.rpc.RpcApplicationSession
import dev.p2pkit.rpc.RpcRequestId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Bounded reply correlation DATA. A stored value cannot authorize output by itself.
 * Each actual SDK request retains its own immutable response; another same-body call
 * cannot replace the guard for an earlier SDK cached reply. Unknown slots are not evicted. */
internal class LocalRpcPhysicalReplyRetention<T : Any> {
    internal class Slot<T : Any> internal constructor(
        val request: RpcRequestId, val call: LocalRpcPhysicalCallScopes.Call,
    ) { internal var response: T? = null }
    private class Session<T : Any>(val actual: RpcApplicationSession) {
        val slots = linkedMapOf<RpcRequestId, Slot<T>>()
    }
    private val gate = Mutex()
    private val sessions = mutableListOf<Session<T>>()
    private var count = 0

    suspend fun reserve(actual: RpcApplicationSession, request: RpcRequestId,
        call: LocalRpcPhysicalCallScopes.Call): Slot<T>? = gate.withLock {
        if (!actual.isActive) return@withLock null
        val session = sessions.firstOrNull { it.actual === actual }
        // SDK execution is one-shot. A repeated handler must not overwrite earlier custody.
        if (session?.slots?.containsKey(request) == true ||
            (session?.slots?.size ?: 0) >= MAX_SESSION_REPLIES || count >= MAX_GLOBAL_REPLIES) return@withLock null
        val slot = Slot<T>(request, call)
        (session ?: Session<T>(actual).also(sessions::add)).slots[request] = slot
        count++
        slot
    }

    suspend fun retain(actual: RpcApplicationSession, slot: Slot<T>, response: T): Boolean = gate.withLock {
        if (!actual.isActive || sessions.firstOrNull { it.actual === actual }?.slots?.get(slot.request) !== slot ||
            slot.response != null) return@withLock false
        slot.response = response
        true
    }

    suspend fun capture(actual: RpcApplicationSession, request: RpcRequestId,
        call: LocalRpcPhysicalCallScopes.Call): T? = gate.withLock {
        if (!actual.isActive) null else sessions.firstOrNull { it.actual === actual }?.slots?.get(request)
            ?.takeIf { it.call === call }?.response
    }

    suspend fun removeRevoked(actual: RpcApplicationSession) = gate.withLock {
        check(!actual.isActive)
        sessions.firstOrNull { it.actual === actual }?.let { sessions.remove(it); count -= it.slots.size }
        check(count >= 0)
    }

    suspend fun removeAllRevoked() = gate.withLock {
        check(sessions.none { it.actual.isActive })
        sessions.clear(); count = 0
    }

    private companion object {
        const val MAX_SESSION_REPLIES = 1_024
        const val MAX_GLOBAL_REPLIES = 8_192
    }
}
