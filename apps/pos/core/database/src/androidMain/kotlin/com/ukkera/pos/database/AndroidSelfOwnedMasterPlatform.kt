package com.ukkera.pos.database

import android.app.Application
import androidx.room3.Room
import androidx.room3.RoomDatabase
import com.ukkera.pos.security.AndroidSelfOwnedInstallationOwner
import com.ukkera.pos.security.AndroidSelfOwnedRoomDriver
import com.ukkera.pos.security.LocalMasterSdkOriginal
import com.ukkera.pos.security.OriginalNativeHistorySnapshot
import com.ukkera.pos.security.SelfOwnedLocalSecurityContext
import com.ukkera.pos.security.SelfOwnedNativeHistoryClaimResult
import com.ukkera.pos.security.SelfOwnedNativeHistoryCustody

/** The Android Master host is exactly the process Application, compared by identity only. */
public actual typealias SelfOwnedMasterHostOwner = Application

internal actual fun selfOwnedMasterPlatform(owner: SelfOwnedMasterHostOwner): SelfOwnedMasterPlatform =
    SelfOwnedMasterPlatform(
        databaseBuilder = { path -> androidMasterRoomBuilder(owner, path) },
        nativeOwner = AndroidSelfOwnedMasterNativeOwner,
        diagnostics = AndroidSelfOwnedMasterDiagnostics,
    )

private fun androidMasterRoomBuilder(application: Application, path: String): RoomDatabase.Builder<MasterRoomDatabase> =
    Room.databaseBuilder<MasterRoomDatabase>(application, path, factory = MasterRoomConstructor::initialize)

/** Android's genuine original installation owner; no other implementation is constructed here. */
internal object AndroidSelfOwnedMasterNativeOwner : SelfOwnedMasterNativeOwner {
    override suspend fun claimOriginalHistoryCustody(actualContext: SelfOwnedLocalSecurityContext): SelfOwnedNativeHistoryClaimResult =
        AndroidSelfOwnedInstallationOwner.claimOriginalHistoryCustody(actualContext)
    override suspend fun openRoomDriver(custody: SelfOwnedNativeHistoryCustody): AndroidSelfOwnedRoomDriver =
        AndroidSelfOwnedInstallationOwner.openRoomDriver(custody)
    override suspend fun finishRoomOpen(custody: SelfOwnedNativeHistoryCustody, driver: AndroidSelfOwnedRoomDriver): Unit =
        AndroidSelfOwnedInstallationOwner.finishRoomOpen(custody, driver)
    override suspend fun yieldExistingMasterSdkForHost(custody: SelfOwnedNativeHistoryCustody,
        expectedCurrentSnapshot: OriginalNativeHistorySnapshot): LocalMasterSdkOriginal =
        AndroidSelfOwnedInstallationOwner.yieldExistingMasterSdkForHost(custody, expectedCurrentSnapshot)
    override fun toString(): String = "AndroidSelfOwnedMasterNativeOwner([REDACTED])"
}

/** Unchanged Android log tag/levels and elapsed-realtime clock. */
internal object AndroidSelfOwnedMasterDiagnostics : SelfOwnedMasterDiagnostics {
    override fun elapsedRealtimeMillis(): Long = android.os.SystemClock.elapsedRealtime()
    override fun info(message: String) { android.util.Log.i("UkkeraNativeOwner", message) }
    override fun warn(message: String) { android.util.Log.w("UkkeraNativeOwner", message) }
}
