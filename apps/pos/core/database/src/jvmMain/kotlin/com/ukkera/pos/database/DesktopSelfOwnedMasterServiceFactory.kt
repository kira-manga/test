package com.ukkera.pos.database

import androidx.room3.Room
import com.ukkera.pos.security.SelfOwnedLocalSecurityContext

/**
 * Opaque desktop Master host owner. Only [DesktopSelfOwnedMasterServiceFactory] constructs it;
 * the shared service compares it by identity only and it confers no authority by itself.
 */
public actual open class SelfOwnedMasterHostOwner internal constructor(
    internal val platform: SelfOwnedMasterPlatform,
) {
    override fun toString(): String = "SelfOwnedMasterHostOwner([REDACTED])"
}

internal actual fun selfOwnedMasterPlatform(owner: SelfOwnedMasterHostOwner): SelfOwnedMasterPlatform = owner.platform

/**
 * Desktop (Windows/macOS JVM) composition skeleton over the SAME shared self-owned Master open
 * path used by Android. Every binding is required; there is no default builder, owner or logger.
 * Desktop native custody is NOT provided here: [nativeOwner] must delegate to the desktop
 * platform's genuine original installation owner in `core:security`. The Room builder is the
 * module-owned context-free JVM builder for the exact path of the driver that owner returns
 * (the shared open installs that driver); callers cannot supply another database.
 *
 * One factory instance is one desktop host identity: clients opened through it satisfy
 * `belongsTo(factory.owner)` and nothing else.
 */
public class DesktopSelfOwnedMasterServiceFactory(
    nativeOwner: SelfOwnedMasterNativeOwner,
    diagnostics: SelfOwnedMasterDiagnostics,
) {
    public val owner: SelfOwnedMasterHostOwner =
        SelfOwnedMasterHostOwner(SelfOwnedMasterPlatform(
            databaseBuilder = { path -> Room.databaseBuilder<MasterRoomDatabase>(path, factory = MasterRoomConstructor::initialize) },
            nativeOwner = nativeOwner, diagnostics = diagnostics))

    /** Real first-account installation without business ingress (same path as Android). */
    public suspend fun forOriginalInstallation(context: SelfOwnedLocalSecurityContext): AndroidSelfOwnedMasterOpenResult =
        AndroidSelfOwnedMasterService.forOriginalInstallation(owner, context)

    /** Operational open from the same native setup host; all business inputs are explicit. */
    public suspend fun fromOriginalContext(context: SelfOwnedLocalSecurityContext,
        handlers: List<MasterCommandHandler>, reads: List<MasterReadHandler>,
        physical: PhysicalRecordingExecutionGate, nativeOriginalImports: List<NativeOriginalImportInterpreter>,
        stockProjection: StockCanonicalProjectionV2?, historyProducer: RuntimeHistoryBusinessProducer?,
    ): AndroidSelfOwnedMasterOpenResult =
        AndroidSelfOwnedMasterService.fromOriginalContext(owner, context, handlers, reads, physical,
            nativeOriginalImports, stockProjection, historyProducer)

    /** Receipt-only original attach through the same shared owner as Android. */
    public suspend fun attachOriginalReceipt(context: SelfOwnedLocalSecurityContext): LocalMasterInstallationReceiptOpenResult =
        OriginalInstallationReceiptOwnerCore.attachOriginal(owner.platform.nativeOwner, context)

    override fun toString(): String = "DesktopSelfOwnedMasterServiceFactory([REDACTED])"
}
