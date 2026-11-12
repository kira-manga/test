package com.ukkera.pos.database

import com.ukkera.pos.security.LocalEmployeeCreationReceiptV1
import com.ukkera.pos.security.LocalEmployeeCreationStatusV1

/** Closed coordinator DATA; only Recorded requires a fresh native COMMITTED child. */
internal sealed interface RoomLocalEmployeeCreationCancellationExecution {
    class Recorded(val receipt: LocalEmployeeCreationReceiptV1): RoomLocalEmployeeCreationCancellationExecution {
        override fun toString(): String = "RoomLocalEmployeeCreationCancellationExecution.Recorded([REDACTED])"
    }
    class Existing(val status: LocalEmployeeCreationStatusV1): RoomLocalEmployeeCreationCancellationExecution {
        init { require(status is LocalEmployeeCreationStatusV1.Applied || status is LocalEmployeeCreationStatusV1.Cancelled ||
            status == LocalEmployeeCreationStatusV1.IdentityConflict) }
        override fun toString(): String = "RoomLocalEmployeeCreationCancellationExecution.Existing([REDACTED])"
    }
    data object Denied: RoomLocalEmployeeCreationCancellationExecution
    data object Unavailable: RoomLocalEmployeeCreationCancellationExecution
    data object StorageUncertain: RoomLocalEmployeeCreationCancellationExecution
    data object Busy: RoomLocalEmployeeCreationCancellationExecution
}
