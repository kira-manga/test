package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import dev.p2pkit.core.dsl.jvmSecureIdentityStore
import dev.p2pkit.transport.lan.lan
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

    override fun keyExists(preparationId: DomainId, keyId: DomainId): Boolean =
        WindowsClientTargetKey.exists(expected, slot, preparationId, keyId)

    /** Official JVM Kit with the per-user DPAPI identity store; same shared owner logic as Android. */
    override fun newSdkOwner(installed: SelfOwnedInstalledIdentityTargetV2): DevicePairingTargetSdkOwner =
        desktopSdkOwner(installed) { WindowsDpapiSecureIdentityStore.open() }

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

/** Shared desktop SDK owner: the same [DevicePairingKitOwner] logic with a JVM LAN Kit and OS identity store. */
internal fun desktopSdkOwner(installed: SelfOwnedInstalledIdentityTargetV2,
    openStore: () -> dev.p2pkit.core.security.JvmSecureIdentityStore): DevicePairingTargetSdkOwner = object : DevicePairingTargetSdkOwner {
    private val shared = DevicePairingKitOwner(requireActive = {}, closureUnknown = {}, beforeCreate = {}, configure = {
        jvmSecureIdentityStore(openStore())
        transports { lan() }
    })
    override fun create(appId: dev.p2pkit.core.AppId, deviceName: String) = shared.create(appId, deviceName)
    override fun capture(acquisitionId: DomainId, preparationId: DomainId, target: LocalDevicePairingTargetDescriptor) =
        shared.captureDesktop(acquisitionId, preparationId, target, installed)
    override fun requireMatches(original: LocalDevicePairingSdkIdentityV2, deviceName: String) =
        shared.requireMatches(original, deviceName)
    override fun close() = shared.close()
}

/**
 * Application-facing desktop Client target: the shared pairing ceremony over desktop custody.
 * Results are DATA; the target and its key stay owned here until [close].
 */
public class DesktopClientTargetHandle internal constructor(private val target: DesktopDevicePairingTarget) :
    LocalOriginalPairingTarget {
    override val compositionProfile: LocalCompositionSdkProfileV1 get() = target.compositionProfile
    public fun original(): LocalDevicePairingTargetReference = target.original()
    public fun descriptor(): LocalDevicePairingTargetDescriptor = target.descriptor()
    public fun acquireSdkIdentity(appId: dev.p2pkit.core.AppId, deviceName: String): LocalDevicePairingSdkIdentityV2 =
        target.acquireSdkIdentity(appId, deviceName)
    public fun recoverSdkIdentity(): LocalDevicePairingSdkIdentityV2 = target.recoverSdkIdentity()
    public fun respond(challenge: LocalDevicePairingChallenge): LocalDevicePairingPossession = target.respond(challenge)
    public fun recoverPossession(originalPairingActionId: DomainId): LocalDevicePairingPossession =
        target.recoverPossession(originalPairingActionId)
    public fun importApprovedMaster(transfer: LocalDevicePairingMasterTransfer): LocalDevicePairingApprovedMaster {
        val certificate = transfer.copyAuthorityCertificate()
        try {
            val root = transfer.copyRootAnnouncement()
            try { return target.importApprovedMaster(certificate, root) } finally { root.fill(0) }
        } finally { certificate.fill(0) }
    }
    /** Root-owned credential-journal custody on this approved original (see the Android target). */
    public fun acquireCredentialJournalCustody(owner: Any, originalPairingActionId: DomainId,
        action: LocalPairedDeviceCredentialJournalAction): PairedDeviceCredentialJournalCustody =
        target.acquireCredentialJournalCustody(owner, originalPairingActionId, action)
    public fun beginAfterKnownUnsigned(retirement: LocalDevicePairingUnsignedRetirement): LocalDevicePairingTargetReference =
        target.beginAfterKnownUnsigned(retirement)
    public fun recoverAfterKnownUnsigned(retirement: LocalDevicePairingUnsignedRetirement): LocalDevicePairingTargetReference =
        target.recoverAfterKnownUnsigned(retirement)
    override suspend fun recoverSdkOriginalData(): LocalOriginalPairingTargetResult<LocalDevicePairingSdkOriginal> =
        dataResult { target.recoverSdkOriginal() }
    override suspend fun recoverApprovedMasterData(originalPairingActionId: DomainId):
        LocalOriginalPairingTargetResult<LocalDevicePairingApprovedMaster> = dataResult { target.recoverApprovedMaster(originalPairingActionId) }
    override fun denyNewWork(): Unit = target.denyNewWork()
    override suspend fun close(): Unit = target.close()
    public fun closeNow(): Unit = target.close()
    override fun toString(): String = "DesktopClientTargetHandle([REDACTED])"

    private inline fun <T> dataResult(body: () -> T): LocalOriginalPairingTargetResult<T> = try {
        LocalOriginalPairingTargetResult.Value(body())
    } catch (failure: SelfOwnedFailureV1) { LocalOriginalPairingTargetResult.Unavailable(failure.reason) }

    public companion object {
        /** Typed refusal reason of a target operation's failure, or null for an unknown outcome. */
        public fun refusalReason(failure: Throwable): SelfOwnedInstallationFailureV1? = (failure as? SelfOwnedFailureV1)?.reason

        /** Windows packaged app: explicit FIRST preparation in the fixed slot directory. */
        public fun prepareNewWindows(expected: SelfOwnedWindowsPackagedAppIdentityV2, slot: LocalTrainingCompositionSlot?,
            scope: BranchScope, capabilities: Set<String>): DesktopClientTargetHandle =
            DesktopClientTargetHandle(DesktopDevicePairingTarget.prepareNew(
                WindowsClientTargetPlatform.observe(expected, slot), scope, capabilities))

        /** Signed macOS app: explicit FIRST preparation in the fixed slot directory (login Keychain key). */
        public fun prepareNewMac(slot: LocalTrainingCompositionSlot?, scope: BranchScope, capabilities: Set<String>): DesktopClientTargetHandle =
            DesktopClientTargetHandle(DesktopDevicePairingTarget.prepareNew(MacClientTargetPlatform.observe(slot), scope, capabilities))

        /** Signed macOS app: existing-only open of the same slot's original target. */
        public fun openExistingMac(slot: LocalTrainingCompositionSlot?): DesktopClientTargetHandle =
            DesktopClientTargetHandle(DesktopDevicePairingTarget.openExisting(MacClientTargetPlatform.observe(slot)))

        /** Windows packaged app: existing-only open of the same slot's original target. */
        public fun openExistingWindows(expected: SelfOwnedWindowsPackagedAppIdentityV2,
            slot: LocalTrainingCompositionSlot?): DesktopClientTargetHandle =
            DesktopClientTargetHandle(DesktopDevicePairingTarget.openExisting(WindowsClientTargetPlatform.observe(expected, slot)))
    }
}
