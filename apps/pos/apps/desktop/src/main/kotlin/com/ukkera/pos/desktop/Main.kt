package com.ukkera.pos.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
    runUkkeraDesktop(AppDependencies(platform = PlatformCapabilities(PlatformFamily.DESKTOP)), args)
}

/**
 * Typed launcher for the actual root-composed client. Never opens/stops a master or invents a
 * peer/key/setup identity. Closing this UI must not System.exit a separately supervised cohost.
 */
fun runUkkeraDesktop(dependencies: AppDependencies, args: Array<String> = emptyArray()) = application(exitProcessOnExit = false) {
    val host = remember { DesktopAppHost(dependencies) }
    val runtime = host.runtime
    val presentation by runtime.presentation.collectAsState()
    val exit = { host.close(); exitApplication() }
    DisposableEffect(host) { onDispose { host.close() } }
    Window(
        title = if (presentation.language == AppLanguage.ARABIC) "Ukkera POS · تحقق محلي" else "Ukkera POS · Local qualification",
        state = rememberWindowState(width = 1320.dp, height = 860.dp),
        onCloseRequest = { runtime.navigate(NavigationAction.CloseHost, runtime.navigator.token(), exit) },
    ) {
        DisposableEffect(window, host) {
            val binding = host.bind(window)
            onDispose { binding.close() }
        }
        UkkeraApp(runtime, onExitRequest = exit)
        // Explicit bounded local launch check; never enters training or simulates authentication.
        if ("--smoke-exit" in args) LaunchedEffect(Unit) { delay(2500); exit() }
    }
}
