package com.ukkera.pos.rpc

import dev.p2pkit.rpc.RpcPhysicalSession
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Scheduling only. Exact SDK reference identity selects a lane; no peer descriptor,
 * principal, credential, resource or currentness is cached or authenticated here.
 * A queued waiter retains the lane until cancellation cleanup completes, so a cancelled
 * waiter cannot let a successor bypass the original connection's in-flight admission. */
internal class LocalRpcAuthenticationAdmission(private val maximumLanes: Int) {
    init { require(maximumLanes > 0) }
    private class Lane(val physical: RpcPhysicalSession) {
        val gate = Mutex()
        var users = 0
    }
    private val gate = Mutex()
    private val lanes = mutableListOf<Lane>()

    suspend fun <T> withPhysical(physical: RpcPhysicalSession, refuse: () -> T, work: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        val lane = gate.withLock {
            val selected = lanes.firstOrNull { it.physical === physical } ?: run {
                if (lanes.size >= maximumLanes) return@withLock null
                Lane(physical).also { lanes.add(it) }
            }
            selected.also { it.users++ }
        } ?: return refuse()
        return try {
            lane.gate.withLock {
                currentCoroutineContext().ensureActive()
                work()
            }
        } finally {
            withContext(NonCancellable) {
                gate.withLock {
                    check(lane.users > 0)
                    lane.users--
                    if (lane.users == 0) check(lanes.remove(lane))
                }
            }
        }
    }
}
