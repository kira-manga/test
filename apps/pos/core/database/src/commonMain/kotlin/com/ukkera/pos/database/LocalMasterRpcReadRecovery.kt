package com.ukkera.pos.database

import dev.p2pkit.rpc.RpcExecutionEvidence
import dev.p2pkit.rpc.RpcFailure
import dev.p2pkit.rpc.RpcFailureKind
import kotlinx.coroutines.delay

/** Scheduling DATA only: a decoded delivery forbids retry, even if later acceptance fails.
 * This is neither an identity/permission proof nor a stored projection or operation result. */
internal class LocalMasterRpcReadAttempt {
    var delivered: Boolean = false
        private set
    fun observeDelivery() { delivered = true }
}

/** Only the owning bridge's fixed read-only routes enter here. Every attempt is a NEW
 * current-authorized query on the SAME selected original connection; it cannot execute,
 * reconnect, issue credentials or publish an earlier partial view. Concrete denial and
 * any delivered DATA stop retries. Callers still enforce coherent whole-view cuts. */
internal suspend fun <T> recoverUndeliveredRpcRead(current: suspend () -> Unit,
    read: suspend (LocalMasterRpcReadAttempt) -> T): T {
    for (index in 0 until 3) {
        current()
        val attempt = LocalMasterRpcReadAttempt()
        try { return read(attempt) }
        catch (failure: RpcFailure) {
            if (index == 2 || attempt.delivered ||
                failure.kind !in setOf(RpcFailureKind.DeadlineExceeded, RpcFailureKind.UnknownOutcome) ||
                failure.executionEvidence !in setOf(RpcExecutionEvidence.MayHaveExecuted, RpcExecutionEvidence.HandlerFinished)) throw failure
            current()
            delay(250)
        }
    }
    error("Read recovery bound exhausted")
}
