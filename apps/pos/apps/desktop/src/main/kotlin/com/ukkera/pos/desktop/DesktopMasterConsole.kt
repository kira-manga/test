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
import com.ukkera.pos.app.AppDependencies
import com.ukkera.pos.app.DesktopAppHost
import com.ukkera.pos.app.PlatformCapabilities
import com.ukkera.pos.app.PlatformFamily
import com.ukkera.pos.app.UkkeraApp
import com.ukkera.pos.app.UkkeraRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Master console from the UI process: setup and existing employee administration screens over
 * the service-owned coordinator and exact native employee session.
 * Nothing here holds Master authority; an unreachable or unverified service stays UNAVAILABLE.
 */
@Composable
internal fun DesktopMasterConsole(composition: DesktopClientComposition, language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit, window: java.awt.Window,
    onRuntime: (UkkeraRuntime?) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var attempt by remember { mutableIntStateOf(0) }
    var controller by remember { mutableStateOf<DesktopRemoteSetupController?>(null) }
    var unavailable by remember { mutableStateOf(false) }
    var employee by remember { mutableStateOf(false) }
    LaunchedEffect(attempt) {
        employee = false
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
            if (employee) {
                val host = remember(current) { DesktopAppHost(AppDependencies(
                    platform = PlatformCapabilities(PlatformFamily.DESKTOP), branchGateway = current.employeeGateway)) }
                DisposableEffect(host, window) {
                    val binding = host.bind(window)
                    onRuntime(host.runtime)
                    onDispose { onRuntime(null); binding.close(); host.close() }
                }
                LaunchedEffect(language, host) { host.runtime.changeLanguage(language) }
                UkkeraApp(host.runtime, onExitRequest = { employee = false }, onLocalSetupRequest = { employee = false },
                    onEmployeePolicyRequest = { target ->
                        current.openEmployeePolicies(target)
                        employee = false
                    })
            } else {
                Column {
                    if (current.employeeConsoleSupported) Button(onClick = { employee = true }, modifier = Modifier.padding(16.dp)) {
                        Text(if (language == AppLanguage.ARABIC) "إدارة الخادم الرئيسي بحساب الموظف" else "Master employee administration")
                    } else {
                        Text(if (language == AppLanguage.ARABIC)
                            "أعد تشغيل خدمة الخادم الرئيسي بعد تحديث التطبيق لإتاحة إدارة الموظف."
                            else "Restart the Master service after updating the app to enable employee administration.", Modifier.padding(16.dp))
                        if (Platform.isMac()) MacServiceAgentActions(language, onBeforeChange = {
                            current.close(); controller = null; employee = false
                        }) { attempt++ }
                    }
                    androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                        LocalSetupScreen(current, language, onLanguageChange, onBack = onBack)
                    }
                }
            }
        }
    }
}

/** macOS (test only): register or remove the per-user LaunchAgent that runs the service mode. */
@Composable
private fun MacServiceAgentActions(language: AppLanguage, onBeforeChange: () -> Unit = {}, onChanged: () -> Unit) {
    val arabic = language == AppLanguage.ARABIC
    var message by remember { mutableStateOf<String?>(null) }
    var installed by remember { mutableStateOf(DesktopMacServiceAgent.isInstalled()) }
    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!installed) Button(onClick = {
            onBeforeChange()
            message = DesktopMacServiceAgent.install().exceptionOrNull()?.message
            installed = DesktopMacServiceAgent.isInstalled(); onChanged()
        }) { Text(if (arabic) "تثبيت خدمة الخادم الرئيسي" else "Install Master service") }
        else OutlinedButton(onClick = {
            onBeforeChange()
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
