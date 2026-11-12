package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.types.DomainId

/** Protected lookup/journal DATA, never a creation permit, native settlement, restored login
 * or proof of server cancellation. The application adapter exposes only historical DATA. */
internal sealed interface EmployeeCreationRecoveryResult {
    /** A matching protected terminal response was durably associated with this local original. */
    class Known(val entry: StoredClientEmployeeCreationV1): EmployeeCreationRecoveryResult {
        init { require(entry.terminal) }
        override fun toString(): String = "EmployeeCreationRecoveryResult.Known([REDACTED])"
    }
    /** No local row for the requested ID. This says nothing about server state or redispatch. */
    data object NoLocalOriginal: EmployeeCreationRecoveryResult
    /** The protected read observed no event. Retain PREPARED/DISPATCHED and any stop request;
     * this is neither a rollback conclusion nor permission to submit the original again. */
    class NotObserved(val requestId: DomainId): EmployeeCreationRecoveryResult {
        override fun toString(): String = "EmployeeCreationRecoveryResult.NotObserved([REDACTED])"
    }
    class Unresolved(val requestId: DomainId, val problem: LocalServiceProblem): EmployeeCreationRecoveryResult {
        override fun toString(): String = "EmployeeCreationRecoveryResult.Unresolved([REDACTED])"
    }
}

/** Local retained-original projection only; no protected absence or submission conclusion. */
internal sealed interface EmployeeCreationPendingResult {
    class Available(val entry: StoredClientEmployeeCreationV1?) : EmployeeCreationPendingResult
    class Unavailable(val problem: LocalServiceProblem) : EmployeeCreationPendingResult
}
