package com.ukkera.pos.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sun.jna.Platform
import com.ukkera.pos.app.gateway.setup.ipc.DesktopRemoteSetupController
import com.ukkera.pos.app.gateway.setup.ipc.DesktopSetupIpcConnection
import com.ukkera.pos.app.gateway.setup.ipc.DesktopSetupUnixIpc
import com.ukkera.pos.app.setup.LocalSetupConnectionPhase
import com.ukkera.pos.app.setup.LocalSetupConnectionScreen
import com.ukkera.pos.app.setup.LocalSetupScreen
import com.ukkera.pos.app.ui.AppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Master administration from the UI process: a remote view of the service-owned setup coordinator.
 * Nothing here holds Master authority; an unreachable or unverified service stays UNAVAILABLE.
 */
@Composable
internal fun DesktopMasterConsole(composition: DesktopClientComposition, language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var attempt by remember { mutableIntStateOf(0) }
    var controller by remember { mutableStateOf<DesktopRemoteSetupController?>(null) }
    var unavailable by remember { mutableStateOf(false) }
    LaunchedEffect(attempt) {
        unavailable = false
        controller = withContext(Dispatchers.IO) {
            runCatching { DesktopRemoteSetupController.connect(openMasterConsole(), composition.pairingDocuments, scope) }.getOrNull()
        }
        unavailable = controller == null
    }
    DisposableEffect(Unit) { onDispose { controller?.close() } }
    val current = controller
    if (current == null) {
        Column {
            if (unavailable && Platform.isMac()) MacServiceAgentActions(language) { attempt++ }
            LocalSetupConnectionScreen(if (unavailable) LocalSetupConnectionPhase.UNAVAILABLE else LocalSetupConnectionPhase.CONNECTING,
                language, onLanguageChange, onRetry = { attempt++ }, onBack = onBack)
        }
    } else {
        val connected by current.connected.collectAsState()
        if (!connected) {
            LocalSetupConnectionScreen(LocalSetupConnectionPhase.UNAVAILABLE, language, onLanguageChange,
                onRetry = { current.close(); controller = null; attempt++ }, onBack = onBack)
        } else {
            LocalSetupScreen(current, language, onLanguageChange, onBack = onBack)
        }
    }
}

/** macOS (test only): register or remove the per-user LaunchAgent that runs the service mode. */
@Composable
private fun MacServiceAgentActions(language: AppLanguage, onChanged: () -> Unit) {
    val arabic = language == AppLanguage.ARABIC
    var message by remember { mutableStateOf<String?>(null) }
    var installed by remember { mutableStateOf(DesktopMacServiceAgent.isInstalled()) }
    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!installed) Button(onClick = {
            message = DesktopMacServiceAgent.install().exceptionOrNull()?.message
            installed = DesktopMacServiceAgent.isInstalled(); onChanged()
        }) { Text(if (arabic) "تثبيت خدمة الخادم الرئيسي" else "Install Master service") }
        else OutlinedButton(onClick = {
            message = DesktopMacServiceAgent.uninstall().exceptionOrNull()?.message
            installed = DesktopMacServiceAgent.isInstalled(); onChanged()
        }) { Text(if (arabic) "إزالة خدمة الخادم الرئيسي" else "Remove Master service") }
        message?.let { Text(it) }
    }
}

private fun openMasterConsole(): DesktopSetupIpcConnection = when {
    Platform.isWindows() -> DesktopSetupWindowsPipe.connect(DesktopWindowsPackageIdentity.TEST.target().packageFamilyName)
    Platform.isMac() -> DesktopSetupUnixIpc.connect(DesktopMasterServiceHost.macConsoleDirectory())
    else -> error("No desktop Master service on this platform")
}
