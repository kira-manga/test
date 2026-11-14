package com.ukkera.pos.database

import androidx.room3.RoomDatabase
import com.ukkera.pos.security.AndroidSelfOwnedRoomDriver
import com.ukkera.pos.security.LocalMasterSdkOriginal
import com.ukkera.pos.security.OriginalNativeHistorySnapshot
import com.ukkera.pos.security.SelfOwnedLocalSecurityContext
import com.ukkera.pos.security.SelfOwnedNativeHistoryClaimResult
import com.ukkera.pos.security.SelfOwnedNativeHistoryCustody

/**
 * The platform host that owns the self-owned Master lifetime. On Android this is exactly
 * `android.app.Application`; on the JVM desktop it is an opaque host owner created only by
 * the desktop factory. The shared service compares it by identity only; it carries no grant.
 */
public expect open class SelfOwnedMasterHostOwner

/** Resolves the platform's genuine security owner, Room builder and diagnostics for [owner]. */
internal expect fun selfOwnedMasterPlatform(owner: SelfOwnedMasterHostOwner): SelfOwnedMasterPlatform

/**
 * The platform's genuine original installation security owner. Implementations MUST delegate
 * to that platform's actual native custody owner (Android: `AndroidSelfOwnedInstallationOwner`).
 * This is not an accepting seam: every operation consumes an opaque security-module custody or
 * context that only that module can construct, and refusal must stay refusal (fail closed).
 */
public interface SelfOwnedMasterNativeOwner {
    /** Transfers the single claim from THIS actual context; never creates a second lease. */
    public suspend fun claimOriginalHistoryCustody(actualContext: SelfOwnedLocalSecurityContext): SelfOwnedNativeHistoryClaimResult
    /** The exact original native driver of [custody]; no other driver may be substituted. */
    public suspend fun openRoomDriver(custody: SelfOwnedNativeHistoryCustody): AndroidSelfOwnedRoomDriver
    public suspend fun finishRoomOpen(custody: SelfOwnedNativeHistoryCustody, driver: AndroidSelfOwnedRoomDriver)
    /** Existing-only current original SDK DATA for an already retained Host resource. */
    public suspend fun yieldExistingMasterSdkForHost(custody: SelfOwnedNativeHistoryCustody,
        expectedCurrentSnapshot: OriginalNativeHistorySnapshot): LocalMasterSdkOriginal
}

/**
 * Bounded fixed-text diagnostics. Messages contain only static phases, failure class names and
 * `com.ukkera.pos.` source locations, never Throwable messages, secrets or protected records.
 * Implementations must not throw into or alter the protected outcome.
 */
public interface SelfOwnedMasterDiagnostics {
    /** Monotonic milliseconds used only for phase-duration diagnostics. */
    public fun elapsedRealtimeMillis(): Long
    public fun info(message: String)
    public fun warn(message: String)
}

/** Platform bindings for the shared self-owned Master. All members are required. */
internal class SelfOwnedMasterPlatform(
    /** Builds the Room builder for the native driver's own database path; the driver is set by the shared open. */
    val databaseBuilder: (path: String) -> RoomDatabase.Builder<MasterRoomDatabase>,
    val nativeOwner: SelfOwnedMasterNativeOwner,
    val diagnostics: SelfOwnedMasterDiagnostics,
) {
    override fun toString(): String = "SelfOwnedMasterPlatform([REDACTED])"
}
