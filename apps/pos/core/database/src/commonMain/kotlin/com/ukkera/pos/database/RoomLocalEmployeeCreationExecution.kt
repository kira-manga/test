package com.ukkera.pos.database

import com.ukkera.pos.security.LocalEmployeeCreationReceiptV1
import com.ukkera.pos.security.LocalEmployeeCreationReviewV1
import com.ukkera.pos.security.LocalEmployeeCreationStatusV1

/** Internal original-owning coordinator outcomes. The protected service maps these to its
 * shared result only after the coordinator's actual writer/native/currentness return.
 * These values are DATA, never employee/native/history authority. */
internal sealed interface RoomLocalEmployeeCreationExecution {
    /** New INSERTs completed the normal outer Room/native return and currentness checks. */
    class Inserted(val receipt: LocalEmployeeCreationReceiptV1): RoomLocalEmployeeCreationExecution {
        override fun toString(): String = "RoomLocalEmployeeCreationExecution.Inserted([REDACTED])"
    }

    /** A protected event already owns this request key. It is lookup-only: a new supplied
     * password was neither compared with the original nor installed by this invocation. */
    class Existing(val status: LocalEmployeeCreationStatusV1): RoomLocalEmployeeCreationExecution {
        init { require(status is LocalEmployeeCreationStatusV1.Applied || status is LocalEmployeeCreationStatusV1.Cancelled || status == LocalEmployeeCreationStatusV1.IdentityConflict) }
        override fun toString(): String = "RoomLocalEmployeeCreationExecution.Existing([REDACTED])"
    }

    class Refused(val review: LocalEmployeeCreationReviewV1.Refused): RoomLocalEmployeeCreationExecution {
        override fun toString(): String = "RoomLocalEmployeeCreationExecution.Refused([REDACTED])"
    }

    data object Denied: RoomLocalEmployeeCreationExecution
    data object Unavailable: RoomLocalEmployeeCreationExecution
    data object StorageUncertain: RoomLocalEmployeeCreationExecution
    data object Busy: RoomLocalEmployeeCreationExecution
}
