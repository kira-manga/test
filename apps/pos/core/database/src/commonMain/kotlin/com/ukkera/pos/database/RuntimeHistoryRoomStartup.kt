package com.ukkera.pos.database

import androidx.room3.RoomDatabase
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteDriver
import com.ukkera.pos.security.SelfOwnedNativeHistoryCustody
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Shared owned Room acquisition inside the platform's exact retained Room invocation.
 * The platform acquires the native driver and supplies its actual open-completion operation.
 * Neither this sequence nor its return value substitutes for native custody or full history
 * admission. Builder acquisition stays lazy so its failures receive the same close/deny path. */
internal suspend fun openOwnedRuntimeHistoryRoom(
    custody: SelfOwnedNativeHistoryCustody,
    admission: RoomOriginalHistoryAdmission?,
    driver: SQLiteDriver,
    builder: () -> RoomDatabase.Builder<MasterRoomDatabase>,
    finishNativeOpen: suspend () -> Unit,
): RoomRuntimeHistoryOwner {
    var database: MasterRoomDatabase? = null
    try {
        val opened = buildOwnedMasterRoom(builder(), driver)
        database = opened
        // Force the actual lazy open/schema callbacks before native DATABASE_BOUND.
        opened.useWriterConnection { connection ->
            requireDurableWriter(connection)
            connection.usePrepared("SELECT identity_hash FROM room_master_table WHERE id=42") {
                check(it.step() && it.getColumnType(0) == 3 && it.getText(0).length == 32 && !it.step())
            }
        }
        finishNativeOpen()
        val original = custody.snapshotOriginal()
        return if (admission == null) RoomRuntimeHistoryOwner.installation(opened, custody, original)
        else {
            RoomRuntimeHistoryOwner.existing(opened, custody, admission, original)
        }
    } catch (failure: Throwable) {
        parkRuntimeHistoryAdmissionDuringOpenFailure(custody, admission, failure)
        try {
            if (database?.runtimeHistoryOwner == null) {
                // The original Room actor owns this drain. Replacing its Job with
                // NonCancellable would lose the exact native control identity.
                // Only a never-registered builder candidate retains this raw cleanup;
                // native preparation refuses even a partial retained close binding.
                custody.prepareActualRoomClose()
                database?.close()
            }
        } catch (cleanup: Throwable) {
            if (cleanup !== failure) failure.addSuppressed(cleanup)
        }
        throw failure
    }
}

/** Cleanup-only DATA park inside the existing watched Room-open body, including a driver
 * acquisition failure before the common builder ran. This never closes the Room lifetime. */
internal suspend fun parkRuntimeHistoryAdmissionDuringOpenFailure(
    custody: SelfOwnedNativeHistoryCustody,
    admission: RoomOriginalHistoryAdmission?,
    failure: Throwable,
) = withContext(NonCancellable) {
    val index = admission?.retailCustomerReceiptMembership?.index
    index?.denyUse()
    try { custody.denyDataUse() } catch (cleanup: Throwable) {
        if (cleanup !== failure) failure.addSuppressed(cleanup)
    }
    try { index?.abort() } catch (cleanup: Throwable) {
        if (cleanup !== failure) failure.addSuppressed(cleanup)
    }
}

/** Platform entry may fail before the open body was admitted, or after its owned return.
 * Invoke only after runRoomWork itself has returned/failed, never from its body or discard.
 * This exact private admission owns the candidate; no unrelated live Room owner is selected. */
internal suspend fun disposeRuntimeHistoryAdmissionAfterOpenFailure(
    custody: SelfOwnedNativeHistoryCustody,
    admission: RoomOriginalHistoryAdmission?,
    failure: Throwable,
) = withContext(NonCancellable) {
    val index = admission?.retailCustomerReceiptMembership?.index ?: return@withContext
    index.denyUse()
    try { custody.denyDataUse() } catch (cleanup: Throwable) {
        if (cleanup !== failure) failure.addSuppressed(cleanup)
    }
    try {
        custody.close()
        index.releaseAfterNativeClose()
    } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
}
