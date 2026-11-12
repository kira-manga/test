package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.security.LocalEmployeeCreationReviewV1
import com.ukkera.pos.types.DomainId

/** Internal submission DATA. The application adapter retains each distinct outcome.
 * No outcome authorizes resubmission, replaces an original or restores the creator's login. */
internal sealed interface EmployeeCreationSubmissionResult {
    /** Only a fresh protected Inserted response followed by durable exact journal recording
     * can produce this. It does not retain the submitted password. */
    class Inserted(val entry: StoredClientEmployeeCreationV1): EmployeeCreationSubmissionResult {
        init { require(entry.phase == StoredEmployeeCreationPhaseV1.APPLIED) }
        override fun toString(): String = "EmployeeCreationSubmissionResult.Inserted([REDACTED])"
    }
    /** A local/protected terminal original already existed. This invocation did not install
     * its new password, even when the original was Applied. */
    class Existing(val entry: StoredClientEmployeeCreationV1): EmployeeCreationSubmissionResult {
        init { require(entry.terminal) }
        override fun toString(): String = "EmployeeCreationSubmissionResult.Existing([REDACTED])"
    }
    /** A prior dispatch cannot be rearmed. Retain the original for protected recovery. */
    class NeedsRecovery(val entry: StoredClientEmployeeCreationV1): EmployeeCreationSubmissionResult {
        init { require(entry.phase == StoredEmployeeCreationPhaseV1.DISPATCHED) }
        override fun toString(): String = "EmployeeCreationSubmissionResult.NeedsRecovery([REDACTED])"
    }
    /** Protected input refusal after first dispatch. The original remains DISPATCHED;
     * this is not a durable cancellation or permission to submit a replacement. */
    class Refused(val requestId: DomainId, val review: LocalEmployeeCreationReviewV1.Refused): EmployeeCreationSubmissionResult {
        override fun toString(): String = "EmployeeCreationSubmissionResult.Refused([REDACTED])"
    }
    /** This invocation did not attempt the durable first-dispatch claim. This says nothing
     * about an existing local/server original, rollback or safe fresh identity. */
    class NotDispatched(val problem: LocalServiceProblem): EmployeeCreationSubmissionResult {
        override fun toString(): String = "EmployeeCreationSubmissionResult.NotDispatched([REDACTED])"
    }
    class Unresolved(val requestId: DomainId, val problem: LocalServiceProblem): EmployeeCreationSubmissionResult {
        override fun toString(): String = "EmployeeCreationSubmissionResult.Unresolved([REDACTED])"
    }
}
