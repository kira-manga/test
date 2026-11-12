package com.ukkera.pos.security

/** Original-owned submission DATA. A prior Applied result does not mean that a newly
 * supplied password was installed. No outcome is native, employee or history authority. */
public sealed interface LocalEmployeeCreationExecutionV1 {
    /** This invocation inserted the exact original and completed its required native/outer
     * settlement. A provider must not return this from a prior-result lookup. */
    public class Inserted(public val receipt: LocalEmployeeCreationReceiptV1): LocalEmployeeCreationExecutionV1 {
        override fun toString(): String = "LocalEmployeeCreationExecutionV1.Inserted([REDACTED])"
    }
    /** The original key already had a terminal result. The new password was not installed. */
    public class Existing(public val status: LocalEmployeeCreationStatusV1): LocalEmployeeCreationExecutionV1 {
        init { require(status is LocalEmployeeCreationStatusV1.Applied || status is LocalEmployeeCreationStatusV1.Cancelled ||
            status == LocalEmployeeCreationStatusV1.IdentityConflict) }
        override fun toString(): String = "LocalEmployeeCreationExecutionV1.Existing([REDACTED])"
    }
    public class Refused(public val review: LocalEmployeeCreationReviewV1.Refused): LocalEmployeeCreationExecutionV1 {
        override fun toString(): String = "LocalEmployeeCreationExecutionV1.Refused([REDACTED])"
    }
    public data object Denied: LocalEmployeeCreationExecutionV1
    public data object Unavailable: LocalEmployeeCreationExecutionV1
    public data object StorageUncertain: LocalEmployeeCreationExecutionV1
    public data object Busy: LocalEmployeeCreationExecutionV1
}

/** Same-original cancellation DATA. Recorded requires an actual committed tombstone;
 * stopping a client wait or local PREPARED row never produces this outcome. */
public sealed interface LocalEmployeeCreationCancellationV1 {
    public class Recorded(public val receipt: LocalEmployeeCreationReceiptV1): LocalEmployeeCreationCancellationV1 {
        override fun toString(): String = "LocalEmployeeCreationCancellationV1.Recorded([REDACTED])"
    }
    /** Applied can win the same-key race. It is preserved and never undone by cancellation. */
    public class Existing(public val status: LocalEmployeeCreationStatusV1): LocalEmployeeCreationCancellationV1 {
        init { require(status is LocalEmployeeCreationStatusV1.Applied || status is LocalEmployeeCreationStatusV1.Cancelled ||
            status == LocalEmployeeCreationStatusV1.IdentityConflict) }
        override fun toString(): String = "LocalEmployeeCreationCancellationV1.Existing([REDACTED])"
    }
    public data object Denied: LocalEmployeeCreationCancellationV1
    public data object Unavailable: LocalEmployeeCreationCancellationV1
    public data object StorageUncertain: LocalEmployeeCreationCancellationV1
    public data object Busy: LocalEmployeeCreationCancellationV1
}
