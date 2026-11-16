package com.ukkera.pos.sales.store

import com.ukkera.pos.database.StoredBytes
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** Channel settlement only, never native-force evidence, an employee grant or an
 * instruction-freshness window. A polling interval cannot classify outstanding work
 * as failed native custody. The real owner supplies denial; the exact typed ACK still
 * needs all native/source checks, and the endpoint retains its original deadline. */
internal suspend fun awaitPhysicalRemoteStageSettlement(answer: Deferred<StoredBytes>,
    denied: () -> Boolean): StoredBytes? {
    while (true) {
        currentCoroutineContext().ensureActive()
        if (denied()) return null
        val settled = withTimeoutOrNull(500) { answer.await() }
        currentCoroutineContext().ensureActive()
        if (denied()) return null
        if (settled != null) return settled
    }
}
