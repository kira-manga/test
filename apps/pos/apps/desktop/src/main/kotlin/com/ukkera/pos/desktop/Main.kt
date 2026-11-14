package com.ukkera.pos.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ukkera.pos.app.setup.LocalEntryScreen
import com.ukkera.pos.app.setup.LocalSetupConnectionPhase
import com.ukkera.pos.app.setup.LocalSetupConnectionScreen
import com.ukkera.pos.app.setup.LocalSetupScreen
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.ukkera.pos.app.AppDependencies
import com.ukkera.pos.app.DesktopAppHost
import com.ukkera.pos.app.PlatformCapabilities
import com.ukkera.pos.app.PlatformFamily
import com.ukkera.pos.app.UkkeraApp
import com.ukkera.pos.app.navigation.NavigationAction
import com.ukkera.pos.app.ui.AppLanguage
import kotlinx.coroutines.delay

/** Default remains explicitly unconfigured until trusted local composition supplies dependencies. */
fun main(args: Array<String>) {
    // Bounded packaging diagnostic; runs before any window, storage or service is opened.
    args.firstOrNull { it.startsWith(DesktopIdentityProbe.ARGUMENT) }?.let {
        kotlin.system.exitProcess(DesktopIdentityProbe.run(java.io.File(it.removePrefix(DesktopIdentityProbe.ARGUMENT))))
    }
    args.firstOrNull { it.startsWith(DesktopIdentityProbe.CLIENT_TARGET_ARGUMENT) }?.let {
        kotlin.system.exitProcess(DesktopIdentityProbe.runClientTarget(
            java.io.File(it.removePrefix(DesktopIdentityProbe.CLIENT_TARGET_ARGUMENT))))
    }
    args.firstOrNull { it.startsWith(DesktopIdentityProbe.CLIENT_JOURNAL_ARGUMENT) }?.let {
        kotlin.system.exitProcess(DesktopIdentityProbe.runClientJournal(
            java.io.File(it.removePrefix(DesktopIdentityProbe.CLIENT_JOURNAL_ARGUMENT))))
    }
    // Started by the Service Control Manager only; never opens a window in session 0.
    if (args.firstOrNull() == DesktopWindowsService.ARGUMENT) {
        kotlin.system.exitProcess(if (com.sun.jna.Platform.isMac()) DesktopMasterServiceHost.runMac() else DesktopWindowsService.run())
    }
    val composition = DesktopClientComposition.observe()
    val dependencies = AppDependencies(platform = PlatformCapabilities(PlatformFamily.DESKTOP))
    runUkkeraDesktop(composition?.let { dependencies.copy(branchGateway = it.localClient) } ?: dependencies, args, composition)
}

private enum class DesktopEntryRoute { CHOICE, SETUP, MASTER, EMPLOYEE }

/**
 * Typed launcher for the actual root-composed client. Never opens/stops a master or invents a
 * peer/key/setup identity. Closing this UI must not System.exit a separately supervised cohost.
 */
fun runUkkeraDesktop(dependencies: AppDependencies, args: Array<String> = emptyArray()) = runUkkeraDesktop(dependencies, args, null)

internal fun runUkkeraDesktop(dependencies: AppDependencies, args: Array<String>, composition: DesktopClientComposition?) =
    application(exitProcessOnExit = false) {
    val host = remember { DesktopAppHost(dependencies) }
    val runtime = host.runtime
    val presentation by runtime.presentation.collectAsState()
    val exit = { host.close(); exitApplication() }
    var route by remember { mutableStateOf(DesktopEntryRoute.CHOICE) }
    DisposableEffect(host) { onDispose { host.close() } }
    Window(
        title = if (presentation.language == AppLanguage.ARABIC) "Ukkera POS · تحقق محلي" else "Ukkera POS · Local qualification",
        state = rememberWindowState(width = 1320.dp, height = 860.dp),
        onCloseRequest = { runtime.navigate(NavigationAction.CloseHost, runtime.navigator.token(), exit) },
    ) {
        DisposableEffect(window, host) {
            val binding = host.bind(window)
            val documents = composition?.pairingDocuments?.bind(window)
            onDispose { binding.close(); documents?.close() }
        }
        val language = presentation.language
        val changeLanguage: (AppLanguage) -> Unit = { runtime.changeLanguage(it) }
        when (route) {
            DesktopEntryRoute.CHOICE -> LocalEntryScreen(language, changeLanguage,
                onSetup = { route = DesktopEntryRoute.SETUP }, onEmployee = { route = DesktopEntryRoute.EMPLOYEE },
                onMasterService = composition?.let { { route = DesktopEntryRoute.MASTER } })
            DesktopEntryRoute.MASTER -> DesktopMasterConsole(composition!!, language, changeLanguage,
                onBack = { route = DesktopEntryRoute.CHOICE })
            DesktopEntryRoute.SETUP -> if (composition == null) {
                // Unpackaged/unsigned process: no native custody exists, never a demonstration fallback.
                LocalSetupConnectionScreen(LocalSetupConnectionPhase.UNAVAILABLE, language, changeLanguage,
                    onRetry = {}, onBack = { route = DesktopEntryRoute.CHOICE })
            } else {
                LocalSetupScreen(remember(composition) { composition.setup() }, language, changeLanguage,
                    onBack = { route = DesktopEntryRoute.CHOICE })
            }
            DesktopEntryRoute.EMPLOYEE -> UkkeraApp(runtime, onExitRequest = { route = DesktopEntryRoute.CHOICE },
                onLocalSetupRequest = { route = DesktopEntryRoute.SETUP })
        }
        // Explicit bounded local launch check; never enters training or simulates authentication.
        if ("--smoke-exit" in args) LaunchedEffect(Unit) { delay(2500); exit() }
    }
}
