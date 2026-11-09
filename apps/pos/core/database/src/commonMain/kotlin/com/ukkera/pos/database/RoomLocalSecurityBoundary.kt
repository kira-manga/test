package com.ukkera.pos.database

import androidx.room3.Transactor
import androidx.room3.useWriterConnection
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** No SQL, provider, stored-row, credential or cause details escape this boundary. */
internal class RoomLocalStorageUncertain : IllegalStateException("Local security storage outcome uncertain")
internal fun localStorageUncertain(): Nothing = throw RoomLocalStorageUncertain()

/**
 * A body failure is carried as DATA across normal Room cleanup, never inferred from the identity
 * of a thrown exception. In a transaction, non-values MUST be passed to rollback(result). A
 * rollback/COMMIT/loan failure itself must throw [RoomLocalStorageUncertain] instead. This avoids
 * treating an ordinary peer/provider refusal as poisoned storage OR hiding failed unwind behind
 * an otherwise benign exception. Caller cancellation is always propagated, never turned to data.
 */
internal sealed interface RoomLocalSecurityOutcome<out T> {
    class Value<T>(val value: T) : RoomLocalSecurityOutcome<T>
    data object Denied : RoomLocalSecurityOutcome<Nothing>
    data object Unavailable : RoomLocalSecurityOutcome<Nothing>
}

internal suspend inline fun <T> localSecurityBody(block: () -> T): RoomLocalSecurityOutcome<T> = try {
    RoomLocalSecurityOutcome.Value(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (uncertain: RoomLocalStorageUncertain) {
    throw uncertain
} catch (_: RoomCurrentLocalAuthorizationDenied) {
    RoomLocalSecurityOutcome.Denied
} catch (_: Exception) {
    RoomLocalSecurityOutcome.Unavailable
}

internal fun <T> RoomLocalSecurityOutcome<T>.valueOrThrow(): T = when (this) {
    is RoomLocalSecurityOutcome.Value -> value
    RoomLocalSecurityOutcome.Denied -> currentLocalAuthorizationDenied()
    RoomLocalSecurityOutcome.Unavailable -> protectedLocalUnavailable()
}

/** Only a clean, unborrowed F35 entry is supported; outer cleanup remains supervised for F36. */
internal suspend fun <T> localSecurityWriter(database: MasterRoomDatabase, block: suspend (Transactor) -> T): T {
    currentCoroutineContext().ensureActive()
    val result = try {
        database.runtimeWriterConnection { connection ->
            requireDurableWriter(connection)
            localSecurityBody { block(connection) }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        localStorageUncertain()
    }
    currentCoroutineContext().ensureActive()
    return result.valueOrThrow()
}

/** No-Room service phase; never wraps password hashing or multiple real transactions in one native child. */
internal suspend fun <T> localSecurityLane(database: MasterRoomDatabase,block: suspend () -> T): T =
    database.runtimeHistoryOwner?.securityLane(block) ?: localSecurityWriter(database) { block() }
