package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.security.LocalEmployeeCreationReviewV1
import com.ukkera.pos.types.DomainId

/** Local control outcomes, not server execution, native settlement or restored authority.
 * The application adapter maps these outcomes without granting dispatch or authority. */
internal sealed interface EmployeeCreationControlResult {
    /** An already retained original is never replaced or rearmed by preparing it again. */
    class Retained(val entry: StoredClientEmployeeCreationV1): EmployeeCreationControlResult {
        override fun toString(): String = "EmployeeCreationControlResult.Retained([REDACTED])"
    }
    /** Removed while the actual journal phase was PREPARED; never a server tombstone. */
    data object RemovedPrepared: EmployeeCreationControlResult
    /** Explicit local acknowledgement only; the Master's immutable original is untouched. */
    data object ForgottenTerminal: EmployeeCreationControlResult
    data object NoLocalOriginal: EmployeeCreationControlResult
    class Refused(val requestId: DomainId, val review: LocalEmployeeCreationReviewV1.Refused): EmployeeCreationControlResult {
        override fun toString(): String = "EmployeeCreationControlResult.Refused([REDACTED])"
    }
    /** Used only before a local staging attempt; no absence/rollback conclusion follows. */
    class NotPrepared(val problem: LocalServiceProblem): EmployeeCreationControlResult {
        override fun toString(): String = "EmployeeCreationControlResult.NotPrepared([REDACTED])"
    }
    class Unresolved(val requestId: DomainId, val problem: LocalServiceProblem): EmployeeCreationControlResult {
        override fun toString(): String = "EmployeeCreationControlResult.Unresolved([REDACTED])"
    }
}
