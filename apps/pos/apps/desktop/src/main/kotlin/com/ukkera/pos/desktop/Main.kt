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
import com.ukkera.pos.app.PlatformCapabilities
import com.ukkera.pos.app.PlatformFamily
import com.ukkera.pos.app.UkkeraApp
import com.ukkera.pos.app.UkkeraRuntime
import com.ukkera.pos.app.UnavailableLocalBranchReportFiles
import com.ukkera.pos.app.UnavailableLocalPurchasingDocumentFiles
import com.ukkera.pos.app.files.DesktopLocalBranchReportFiles
import com.ukkera.pos.app.files.DesktopLocalBranchReportStorageV1
import com.ukkera.pos.security.LocalSetupCompositionMode
import com.ukkera.pos.app.files.DesktopLocalPurchasingDocumentFiles
import com.ukkera.pos.app.navigation.NavigationAction
import com.ukkera.pos.app.ui.AppLanguage
import kotlinx.coroutines.delay

/** Default remains explicitly unconfigured until trusted local composition supplies dependencies. */
fun main(args: Array<String>) = runUkkeraDesktop(AppDependencies(platform = PlatformCapabilities(PlatformFamily.DESKTOP)), args)

/**
 * Typed launcher for the actual root-composed client. Never opens/stops a master or invents a
 * peer/key/setup identity. Closing this UI must not System.exit a separately supervised cohost.
 */
fun runUkkeraDesktop(dependencies: AppDependencies, args: Array<String> = emptyArray()) = application(exitProcessOnExit = false) {
    require(dependencies.platform.family == PlatformFamily.DESKTOP)
    val reportFiles = remember {
        if (dependencies.branchReportFiles === UnavailableLocalBranchReportFiles)
            DesktopLocalBranchReportFiles(if (dependencies.compositionMode == LocalSetupCompositionMode.TRAINING)
                DesktopLocalBranchReportStorageV1.TRAINING else DesktopLocalBranchReportStorageV1.LIVE)
        else null
    }
    val documentFiles = remember {
        if (dependencies.documentFiles === UnavailableLocalPurchasingDocumentFiles) DesktopLocalPurchasingDocumentFiles() else null
    }
    val runtime = remember { UkkeraRuntime(dependencies.copy(
        branchReportFiles = reportFiles ?: dependencies.branchReportFiles,
        documentFiles = documentFiles ?: dependencies.documentFiles,
    )) }
    val presentation by runtime.presentation.collectAsState()
    val exit = { runtime.close(); exitApplication() }
    DisposableEffect(runtime, reportFiles, documentFiles) {
        onDispose { runtime.close(); reportFiles?.close(); documentFiles?.close() }
    }
    Window(
        title = if (presentation.language == AppLanguage.ARABIC) "Ukkera POS · تحقق محلي" else "Ukkera POS · Local qualification",
        state = rememberWindowState(width = 1320.dp, height = 860.dp),
        onCloseRequest = { runtime.navigate(NavigationAction.CloseHost, runtime.navigator.token(), exit) },
    ) {
        DisposableEffect(window, reportFiles, documentFiles) {
            val reports = reportFiles?.bind(window)
            val documents = documentFiles?.bind(window)
            onDispose { reports?.close(); documents?.close() }
        }
        UkkeraApp(runtime, onExitRequest = exit)
        // Explicit bounded local launch check; never enters training or simulates authentication.
        if ("--smoke-exit" in args) LaunchedEffect(Unit) { delay(2500); exit() }
    }
}
