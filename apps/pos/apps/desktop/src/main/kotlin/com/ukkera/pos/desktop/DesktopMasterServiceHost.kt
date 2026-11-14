package com.ukkera.pos.desktop

import com.sun.jna.Platform
import com.ukkera.pos.app.gateway.DesktopClientJournalStorages
import com.ukkera.pos.app.gateway.setup.DesktopLocalSetupExecution
import com.ukkera.pos.app.gateway.setup.DesktopMasterSetupNativeServices
import com.ukkera.pos.app.gateway.setup.DesktopMasterSetupPlatform
import com.ukkera.pos.app.gateway.setup.DesktopSetupStorages
import com.ukkera.pos.app.gateway.setup.LocalDeviceAdmissionIntents
import com.ukkera.pos.app.gateway.setup.LocalEmployeeSetupIntents
import com.ukkera.pos.app.gateway.setup.LocalFreshDevicePairingIntents
import com.ukkera.pos.app.gateway.setup.LocalMasterLaunchRecord
import com.ukkera.pos.app.gateway.setup.LocalMasterLifecycleIntentStore
import com.ukkera.pos.app.gateway.setup.LocalSetupCoordinator
import com.ukkera.pos.app.gateway.setup.ipc.DesktopSetupIpcDocuments
import com.ukkera.pos.app.gateway.setup.ipc.DesktopSetupIpcEndpoint
import com.ukkera.pos.app.gateway.setup.ipc.DesktopSetupIpcServer
import com.ukkera.pos.app.gateway.setup.ipc.DesktopSetupUnixIpc
import com.ukkera.pos.database.DesktopSelfOwnedMasterDiagnostics
import com.ukkera.pos.database.DesktopSelfOwnedMasterNativeOwner
import com.ukkera.pos.database.DesktopSelfOwnedMasterServiceFactory
import com.ukkera.pos.security.DesktopMasterClientJournalCustody
import com.ukkera.pos.security.DesktopMasterServiceDirectories
import com.ukkera.pos.security.DesktopSelfOwnedEnrollmentSource
import com.ukkera.pos.security.DesktopSelfOwnedInstallationOwner
import com.ukkera.pos.security.MacDesktopFiles
import com.ukkera.pos.security.MacKeychainSecureIdentityStore
import com.ukkera.pos.security.SelfOwnedInstalledIdentityTargetV2
import com.ukkera.pos.security.WindowsDpapiSecureIdentityStore
import dev.p2pkit.core.security.JvmSecureIdentityStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path

/**
 * The OS-supervised Master SERVICE mode of the desktop app (Windows packaged service, macOS
 * per-user LaunchAgent). It owns the shared setup coordinator over the service's native custody,
 * serves the local setup console to this package's UI only, and attempts one automatic reopen of a
 * retained original installation. No window, no Client role, no fallback when custody is refused.
 */
internal class DesktopMasterServiceHost private constructor(
    service: SelfOwnedInstalledIdentityTargetV2,
    root: Path,
    identityStore: () -> JvmSecureIdentityStore,
    private val endpoint: () -> DesktopSetupIpcEndpoint,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val documents = DesktopSetupIpcDocuments()
    private val coordinator: LocalSetupCoordinator
    private val server: DesktopSetupIpcServer
    private var listening: DesktopSetupIpcEndpoint? = null

    init {
        val factory = DesktopSelfOwnedMasterServiceFactory(DesktopSelfOwnedMasterNativeOwner, DesktopSelfOwnedMasterDiagnostics)
        val services = DesktopMasterSetupNativeServices(DesktopMasterSetupPlatform(
            openProvisioner = { originalOnly ->
                if (originalOnly) DesktopSelfOwnedEnrollmentSource.openOriginal(service) else DesktopSelfOwnedEnrollmentSource.open(service)
            },
            openTarget = { preparation ->
                if (preparation) DesktopSelfOwnedInstallationOwner.prepareOriginal(service) else DesktopSelfOwnedInstallationOwner.openOriginal(service)
            },
            factory = factory,
            identityStore = identityStore,
            // Same-device original Client over the service's own custody (never the UI-process stores).
            clientJournals = { DesktopClientJournalStorages.masterService(DesktopMasterClientJournalCustody.open(service)) },
        ))
        val storages = DesktopSetupStorages(root)
        coordinator = LocalSetupCoordinator(
            services = services,
            employeeSetupIntents = LocalEmployeeSetupIntents(storages.employee),
            launchRecord = LocalMasterLaunchRecord(storages.launch),
            execution = DesktopLocalSetupExecution(),
            beforeRetirement = { _, _ -> },
            beforeClientHosting = {},
            deviceAdmissionIntents = LocalDeviceAdmissionIntents(storages.deviceAdmission),
            freshDevicePairingIntents = LocalFreshDevicePairingIntents(storages.freshTarget, storages.freshMaster),
            pairingDocuments = documents,
            lifecycleIntents = LocalMasterLifecycleIntentStore(storages.lifecycle),
            stopBindingIntents = LocalMasterLifecycleIntentStore(storages.stopBinding),
        )
        server = DesktopSetupIpcServer(coordinator, documents, scope)
    }

    fun start() {
        val opened = endpoint()
        listening = opened
        server.serve(opened)
        coordinator.reopenRetainedMaster()
    }

    /** Ordinary service stop: unintentional retirement (never an authenticated product STOP). */
    fun stop(timeoutMillis: Long = 25_000L) {
        coordinator.lockPresentation()
        runCatching { listening?.close() }
        runBlocking { withTimeoutOrNull(timeoutMillis) { runCatching { coordinator.requestRetirement().await() } } }
        scope.cancel()
    }

    companion object {
        /** Fails closed (throws) unless this process is the packaged service with observed identity. */
        fun windows(): DesktopMasterServiceHost {
            val identity = DesktopWindowsServiceIdentity.TEST.target()
            val family = identity.packageFamilyName
            return DesktopMasterServiceHost(identity, DesktopMasterServiceDirectories.setup(identity),
                identityStore = { WindowsDpapiSecureIdentityStore.open() },
                endpoint = { DesktopSetupWindowsPipe.listen(expectedClientFamily = family) })
        }

        /** macOS (test only): the same signed app launched by its per-user LaunchAgent. */
        fun mac(): DesktopMasterServiceHost {
            // Expected identity from the signing build; the running signature must match it exactly.
            val identity = DesktopMacServiceIdentity.configured().getOrThrow()
            val root = DesktopMasterServiceDirectories.setup(identity)
            return DesktopMasterServiceHost(identity, root,
                identityStore = { MacKeychainSecureIdentityStore.open() },
                endpoint = { DesktopSetupUnixIpc.listen(macConsoleDirectory()) })
        }

        /** The per-user console directory both the LaunchAgent and the UI resolve (owner-only 0700). */
        fun macConsoleDirectory(): Path = MacDesktopFiles.applicationSupport().resolve("master-console")

        /** Runs the macOS service mode until the LaunchAgent terminates the process. */
        fun runMac(): Int {
            if (!Platform.isMac()) return 3
            val host = runCatching { mac() }.getOrElse { return 4 }
            val stopped = java.util.concurrent.CountDownLatch(1)
            Runtime.getRuntime().addShutdownHook(Thread { host.stop(); stopped.countDown() })
            host.start()
            stopped.await()
            return 0
        }
    }
}
