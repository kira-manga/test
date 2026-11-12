package com.ukkera.pos.desktop

import com.sun.jna.Platform
import com.ukkera.pos.app.gateway.DesktopClientJournalStorages
import com.ukkera.pos.app.gateway.LocalClientPublication
import com.ukkera.pos.app.gateway.setup.DesktopClientSetupPlatform
import com.ukkera.pos.app.gateway.setup.DesktopLocalSetupExecution
import com.ukkera.pos.app.gateway.setup.DesktopLocalSetupNativeServices
import com.ukkera.pos.app.gateway.setup.DesktopSetupStorages
import com.ukkera.pos.app.gateway.setup.LocalDeviceAdmissionIntents
import com.ukkera.pos.app.gateway.setup.LocalEmployeeSetupIntents
import com.ukkera.pos.app.gateway.setup.LocalFreshDevicePairingIntents
import com.ukkera.pos.app.gateway.setup.LocalMasterLaunchRecord
import com.ukkera.pos.app.gateway.setup.LocalMasterLifecycleIntentStore
import com.ukkera.pos.app.gateway.setup.LocalSetupCoordinator
import com.ukkera.pos.security.DesktopClientTargetHandle
import com.ukkera.pos.security.MacDesktopFiles
import com.ukkera.pos.security.MacLoginKeychainSecureIdentityStore
import com.ukkera.pos.security.SelfOwnedInstalledIdentityResultV2
import com.ukkera.pos.security.WindowsDpapiSecureIdentityStore
import com.ukkera.pos.security.WindowsInstalledApplicationIdentity
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Desktop UI-process (Client role) composition. Only a signed/packaged app with an OS-observed
 * identity gets native setup; an unpackaged run stays fail-closed UNAVAILABLE, never a fallback.
 */
internal class DesktopClientComposition private constructor(private val platform: DesktopClientSetupPlatform, private val root: Path) {
    val localClient = LocalClientPublication()
    val pairingDocuments = DesktopSetupPairingDocuments()
    private var coordinator: LocalSetupCoordinator? = null

    @Synchronized fun setup(): LocalSetupCoordinator = coordinator ?: run {
        val storages = DesktopSetupStorages(root)
        LocalSetupCoordinator(
            services = DesktopLocalSetupNativeServices(platform, localClient),
            employeeSetupIntents = LocalEmployeeSetupIntents(storages.employee),
            launchRecord = LocalMasterLaunchRecord(storages.launch),
            execution = DesktopLocalSetupExecution(),
            beforeRetirement = { _, _ -> },
            beforeClientHosting = {},
            deviceAdmissionIntents = LocalDeviceAdmissionIntents(storages.deviceAdmission),
            freshDevicePairingIntents = LocalFreshDevicePairingIntents(storages.freshTarget, storages.freshMaster),
            pairingDocuments = pairingDocuments,
            lifecycleIntents = LocalMasterLifecycleIntentStore(storages.lifecycle),
            stopBindingIntents = LocalMasterLifecycleIntentStore(storages.stopBinding),
        ).also { coordinator = it }
    }

    companion object {
        /** Null when this process is not the packaged/signed desktop app (no native custody is opened). */
        fun observe(): DesktopClientComposition? = runCatching {
            when {
                Platform.isWindows() -> windows()
                Platform.isMac() -> mac()
                else -> null
            }
        }.getOrNull()

        private fun windows(): DesktopClientComposition? {
            val expected = DesktopWindowsPackageIdentity.TEST.target()
            if (WindowsInstalledApplicationIdentity.captureUi(expected) !is SelfOwnedInstalledIdentityResultV2.Data) return null
            val local = System.getenv("LOCALAPPDATA") ?: return null
            val root = Paths.get(local, "Packages", expected.packageFamilyName, "LocalState")
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return null
            return DesktopClientComposition(DesktopClientSetupPlatform(
                prepare = { slot, scope, capabilities -> DesktopClientTargetHandle.prepareNewWindows(expected, slot, scope, capabilities) },
                openExisting = { slot -> DesktopClientTargetHandle.openExistingWindows(expected, slot) },
                journalStorage = { DesktopClientJournalStorages.windows(expected) },
                identityStore = { WindowsDpapiSecureIdentityStore.open() },
            ), root)
        }

        private fun mac(): DesktopClientComposition {
            val identity = MacDesktopFiles.observeOwnIdentity()
            return DesktopClientComposition(DesktopClientSetupPlatform(
                prepare = { slot, scope, capabilities -> DesktopClientTargetHandle.prepareNewMac(slot, scope, capabilities) },
                openExisting = { slot -> DesktopClientTargetHandle.openExistingMac(slot) },
                journalStorage = { DesktopClientJournalStorages.mac(identity) },
                identityStore = { MacLoginKeychainSecureIdentityStore.open() },
            ), MacDesktopFiles.applicationSupport())
        }
    }
}
