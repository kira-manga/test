package com.ukkera.pos.database

import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.types.DomainId
import dev.p2pkit.rpc.RpcExecutionEvidence
import dev.p2pkit.rpc.RpcFailure
import dev.p2pkit.rpc.RpcFailureKind
import kotlinx.coroutines.delay

/** The encoder destroys each outgoing carrier. Retain only an unencoded, owned
 * template while the fixed read-only route recaptures each bounded attempt. No caller
 * buffer or already-retired outgoing request is reused; all temporary secrets are wiped. */
internal fun <Q : LocalMasterRpcOwnedRequestV1> freshRpcReadRequest(template: LocalMasterRpcOwnedRequestV1,
    connectionId: DomainId, capture: (LocalGatewayCredentials) -> Q): Q = template.useBearer { bytes ->
    val bearer = OpaqueCredential(bytes)
    try { capture(LocalGatewayCredentials(connectionId, bearer)) }
    finally { bearer.destroy() }
}

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
