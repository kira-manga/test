package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Windows packaged-application facts for the desktop Client target (owner decision: Client keys
 * stay in the app). The parent is the package's own LocalState folder; facts are native
 * observations of the running packaged process, never caller-supplied values.
 */
internal class WindowsClientTargetPlatform private constructor(
    private val expected: SelfOwnedWindowsPackagedAppIdentityV2,
    override val slot: LocalTrainingCompositionSlot?,
    private val boot: WindowsOriginalBootSession,
) : DesktopClientTargetPlatform {

    override fun parentDirectory(): Path {
        val local = System.getenv("LOCALAPPDATA") ?: selfOwnedFail(SelfOwnedInstallationFailureV1.UNAVAILABLE)
        val parent = Paths.get(local, "Packages", expected.packageFamilyName, "LocalState")
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) selfOwnedFail(SelfOwnedInstallationFailureV1.UNAVAILABLE)
        return parent
    }

    override fun origin(directory: Path): TargetOrigin.Desktop {
        val owner = WindowsClientTargetKey.requirePackagedUser(expected)
        val support = WindowsOriginalNative.openSupportDirectory(directory)
        try {
            val canonical = support.observation.canonicalPath
            val lock = WindowsOriginalNative.observeExistingEntry(canonical + "\\" + DesktopTargetSnapshotStore.LOCK)
            if (lock.volumeSerialNumber != support.observation.identity.volumeSerialNumber) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
            return TargetOrigin.Desktop(expected, TargetDesktopNamespaceV2(SelfOwnedNativePlatformV2.WINDOWS, canonical,
                owner, support.volumeUuid, support.observation.identity.fileId, lock.fileId))
        } finally { support.handle.closeConfirmed() }
    }

    override fun bootSessionId(): String {
        boot.elapsedMillis() // fresh same-boot observation before reporting the retained session
        return boot.identity.bootId
    }

    override fun elapsedMillis(): Long = boot.elapsedMillis()

    override fun createKey(preparationId: DomainId, keyId: DomainId): DesktopClientTargetKey =
        WindowsClientTargetKey.create(expected, slot, preparationId, keyId)

    override fun openKey(preparationId: DomainId, keyId: DomainId, originalPoint: ByteArray): DesktopClientTargetKey =
        WindowsClientTargetKey.openExisting(expected, slot, preparationId, keyId, originalPoint)

    override fun toString(): String = "WindowsClientTargetPlatform([REDACTED])"

    companion object {
        /** Requires the running process to be exactly the expected packaged UI identity. */
        fun observe(expected: SelfOwnedWindowsPackagedAppIdentityV2,
            slot: LocalTrainingCompositionSlot?): WindowsClientTargetPlatform {
            WindowsClientTargetKey.requirePackagedUser(expected)
            return WindowsClientTargetPlatform(expected, slot, WindowsOriginalBootSession.observe())
        }
    }
}

/**
 * Application-facing desktop Client target probe/handle. Exposes only DATA results; the target and
 * its key stay owned here until [close].
 */
public class DesktopClientTargetHandle internal constructor(private val target: DesktopDevicePairingTarget) : AutoCloseable {
    public fun original(): LocalDevicePairingTargetReference = target.original()
    public fun descriptor(): LocalDevicePairingTargetDescriptor = target.descriptor()
    public fun denyNewWork(): Unit = target.denyNewWork()
    override fun close(): Unit = target.close()

    public companion object {
        /** Windows packaged app: explicit FIRST preparation in the fixed slot directory. */
        public fun prepareNewWindows(expected: SelfOwnedWindowsPackagedAppIdentityV2, slot: LocalTrainingCompositionSlot?,
            scope: BranchScope, capabilities: Set<String>): DesktopClientTargetHandle =
            DesktopClientTargetHandle(DesktopDevicePairingTarget.prepareNew(
                WindowsClientTargetPlatform.observe(expected, slot), scope, capabilities))

        /** Windows packaged app: existing-only open of the same slot's original target. */
        public fun openExistingWindows(expected: SelfOwnedWindowsPackagedAppIdentityV2,
            slot: LocalTrainingCompositionSlot?): DesktopClientTargetHandle =
            DesktopClientTargetHandle(DesktopDevicePairingTarget.openExisting(WindowsClientTargetPlatform.observe(expected, slot)))
    }
}
