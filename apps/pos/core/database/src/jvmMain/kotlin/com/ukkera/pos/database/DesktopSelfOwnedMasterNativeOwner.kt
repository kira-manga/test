package com.ukkera.pos.database

import com.ukkera.pos.security.AndroidSelfOwnedRoomDriver
import com.ukkera.pos.security.DesktopSelfOwnedInstallationOwner
import com.ukkera.pos.security.LocalMasterSdkOriginal
import com.ukkera.pos.security.OriginalNativeHistorySnapshot
import com.ukkera.pos.security.SelfOwnedLocalSecurityContext
import com.ukkera.pos.security.SelfOwnedNativeHistoryClaimResult
import com.ukkera.pos.security.SelfOwnedNativeHistoryCustody

/** The genuine Windows/macOS original installation owner for [DesktopSelfOwnedMasterServiceFactory]. */
public object DesktopSelfOwnedMasterNativeOwner : SelfOwnedMasterNativeOwner {
    override suspend fun claimOriginalHistoryCustody(actualContext: SelfOwnedLocalSecurityContext): SelfOwnedNativeHistoryClaimResult =
        DesktopSelfOwnedInstallationOwner.claimOriginalHistoryCustody(actualContext)
    override suspend fun openRoomDriver(custody: SelfOwnedNativeHistoryCustody): AndroidSelfOwnedRoomDriver =
        DesktopSelfOwnedInstallationOwner.openRoomDriver(custody)
    override suspend fun finishRoomOpen(custody: SelfOwnedNativeHistoryCustody, driver: AndroidSelfOwnedRoomDriver): Unit =
        DesktopSelfOwnedInstallationOwner.finishRoomOpen(custody, driver)
    override suspend fun yieldExistingMasterSdkForHost(custody: SelfOwnedNativeHistoryCustody,
        expectedCurrentSnapshot: OriginalNativeHistorySnapshot): LocalMasterSdkOriginal =
        DesktopSelfOwnedInstallationOwner.yieldExistingMasterSdkForHost(custody, expectedCurrentSnapshot)
    override fun toString(): String = "DesktopSelfOwnedMasterNativeOwner([REDACTED])"
}

/** Fixed-text desktop diagnostics to standard error; never protected content. */
public object DesktopSelfOwnedMasterDiagnostics : SelfOwnedMasterDiagnostics {
    override fun elapsedRealtimeMillis(): Long = System.nanoTime() / 1_000_000L
    override fun info(message: String) { System.err.println("UkkeraNativeOwner I $message") }
    override fun warn(message: String) { System.err.println("UkkeraNativeOwner W $message") }
}
