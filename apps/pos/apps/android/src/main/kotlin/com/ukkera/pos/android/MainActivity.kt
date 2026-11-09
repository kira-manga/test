package com.ukkera.pos.android

import android.app.Application
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ukkera.pos.app.AppDependencies
import com.ukkera.pos.app.PlatformCapabilities
import com.ukkera.pos.app.PlatformFamily
import com.ukkera.pos.app.UkkeraApp
import com.ukkera.pos.app.UkkeraRuntime
import com.ukkera.pos.app.files.AndroidLocalBranchReportFiles
import com.ukkera.pos.app.files.AndroidLocalPurchasingDocumentFiles
import com.ukkera.pos.app.gateway.LocalClientPublication
import com.ukkera.pos.app.gateway.LocalClientRole
import com.ukkera.pos.app.setup.LocalEntryScreen
import com.ukkera.pos.app.setup.LocalMasterOperatingNotice
import com.ukkera.pos.app.setup.LocalSetupConnectionScreen
import com.ukkera.pos.app.setup.LocalSetupScreen
import com.ukkera.pos.app.setup.LocalSetupLanRole
import com.ukkera.pos.app.setup.LocalSetupLanStatus
import com.ukkera.pos.app.ui.AppLanguage

/** Default local entry; only the retained native service may publish the cohost client. */
open class PosApplication : Application() {
    val reportFiles: AndroidLocalBranchReportFiles by lazy { AndroidLocalBranchReportFiles(this) }
    val documentFiles: AndroidLocalPurchasingDocumentFiles by lazy { AndroidLocalPurchasingDocumentFiles(this) }
    internal val pairingDocuments: AndroidLocalSetupPairingDocuments by lazy { AndroidLocalSetupPairingDocuments(this) }
    internal val localClient = LocalClientPublication()
    /** No default account or synthetic service. Publication requires explicit native setup. */
    protected open fun clientDependencies(): AppDependencies = AppDependencies(branchGateway = localClient,
        platform = PlatformCapabilities(PlatformFamily.ANDROID))
    val runtime: UkkeraRuntime by lazy {
        val dependencies = clientDependencies()
        require(dependencies.platform.family == PlatformFamily.ANDROID)
        UkkeraRuntime(dependencies.copy(branchReportFiles = reportFiles, documentFiles = documentFiles))
    }
}

class MainActivity : ComponentActivity() {
    private val runtime: UkkeraRuntime get() = (application as PosApplication).runtime
    private var reportFileHost: AutoCloseable? = null
    private var documentFileHost: AndroidLocalPurchasingDocumentFiles.Binding? = null
    private val documentPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        (application as PosApplication).documentFiles.onActivityResult(this, result.resultCode, result.data)
    }
    private var pairingDocumentHost: AndroidLocalSetupPairingDocuments.Binding? = null
    private val pairingDocumentPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        (application as PosApplication).pairingDocuments.onActivityResult(this, result.resultCode, result.data)
    }
    private lateinit var setup: AndroidSetupConnection
    private var visible = false
    private var route by mutableStateOf(EntryRoute.CHOICE)
    private var language by mutableStateOf(AppLanguage.ENGLISH)

    private enum class EntryRoute { CHOICE, SETUP, EMPLOYEE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setup = AndroidSetupConnection(this)
        // Only non-sensitive route/language are restored. No controller, native action,
        // credential, readiness flag or private review enters Android saved state.
        route = EntryRoute.entries.firstOrNull { it.name == savedInstanceState?.getString("local.entry.route") }
            ?: EntryRoute.CHOICE
        language = AppLanguage.entries.firstOrNull { it.name == savedInstanceState?.getString("local.entry.language") }
            ?: if (resources.configuration.locales[0]?.language == "ar") AppLanguage.ARABIC else AppLanguage.ENGLISH
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            val localRole by (application as PosApplication).localClient.role.collectAsState(initial = null)
            val setupController by setup.controller.collectAsState()
            val lan = setupController?.state?.collectAsState()?.value?.lan
            val hosting = localRole == LocalClientRole.MASTER ||
                (lan?.role == LocalSetupLanRole.HOST && lan.status == LocalSetupLanStatus.LISTENING)
            DisposableEffect(hosting) {
                if (hosting) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
            when (route) {
                EntryRoute.CHOICE -> LocalEntryScreen(language, { language = it },
                    onSetup = { choose(EntryRoute.SETUP) }, onEmployee = { choose(EntryRoute.EMPLOYEE) })
                EntryRoute.SETUP -> {
                    val phase by setup.phase.collectAsState()
                    val actual = setupController
                    if (actual == null) LocalSetupConnectionScreen(phase, language, { language = it },
                        onRetry = { if (visible) { setup.disconnect(); setup.connect() } },
                        onBack = { choose(EntryRoute.CHOICE) })
                    else LocalSetupScreen(actual, language, { language = it },
                        onBack = { choose(EntryRoute.CHOICE) })
                    BackHandler { choose(EntryRoute.CHOICE) }
                }
                EntryRoute.EMPLOYEE -> UkkeraApp(runtime, onExitRequest = { choose(EntryRoute.CHOICE) },
                    onLocalSetupRequest = { choose(EntryRoute.SETUP) })
            }
            LocalMasterOperatingNotice(hosting, language)
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        setup.connect() // Existing-only shared startup is independent of the selected screen.
        reportFileHost = (application as PosApplication).reportFiles.bind(this)
        if (documentFileHost == null) {
            documentFileHost = (application as PosApplication).documentFiles.bind(this,
                launch = { documentPicker.launch(it) }, onInvalidated = {
                    documentFileHost = null
                    if (route == EntryRoute.EMPLOYEE) runtime.lock(runtime.navigator.token())
                })
        }
        if (pairingDocumentHost == null) {
            pairingDocumentHost = (application as PosApplication).pairingDocuments.bind(this,
                launch = { pairingDocumentPicker.launch(it) }, onInvalidated = {
                    pairingDocumentHost = null
                    setup.controller.value?.lockPresentation()
                })
        }
    }

    override fun onResume() {
        super.onResume()
        documentFileHost?.onResume()
        pairingDocumentHost?.onResume()
    }

    override fun onPause() {
        documentFileHost?.onPause()
        pairingDocumentHost?.onPause()
        super.onPause()
    }

    override fun onUserLeaveHint() {
        documentFileHost?.onUserLeaving()
        pairingDocumentHost?.onUserLeaving()
        super.onUserLeaveHint()
    }

    override fun onStop() {
        visible = false
        setup.disconnect() // presentation/client attachment only, not the native setup lifetime
        reportFileHost?.close()
        reportFileHost = null
        // Only one actually launched native document picker can suspend this Activity's
        // presentation. No protected native read/write occurs until it is resumed and the
        // exact original shared session is current; unrelated stops retain the lock rule.
        val returningFromDocumentPicker = documentFileHost?.stopForPicker() == true
        if (!returningFromDocumentPicker) {
            documentFileHost?.close()
            documentFileHost = null
        }
        // Setup remains locked/detached above. Only the matching picker binding survives;
        // its resumed DATA result still requires explicit shared review and later action.
        if (pairingDocumentHost?.stopForPicker() != true) {
            pairingDocumentHost?.close()
            pairingDocumentHost = null
        }
        // Hide private UI on leaving the foreground; no branch service lifetime is tied to this.
        // Retained credentials are revocation-only after detach; backgrounding is not a claimed
        // master logout and never deletes the separate durable pending business journal.
        if (route == EntryRoute.EMPLOYEE && !returningFromDocumentPicker) runtime.lock(runtime.navigator.token())
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("local.entry.route", route.name)
        outState.putString("local.entry.language", language.name)
        super.onSaveInstanceState(outState)
    }

    private fun choose(next: EntryRoute) {
        if (route == EntryRoute.SETUP && next != EntryRoute.SETUP) setup.controller.value?.lockPresentation()
        if (route == EntryRoute.EMPLOYEE && next != EntryRoute.EMPLOYEE) runtime.lock(runtime.navigator.token())
        route = next
        if (visible) setup.connect()
    }

    override fun onDestroy() {
        reportFileHost?.close()
        reportFileHost = null
        documentFileHost?.close()
        documentFileHost = null
        pairingDocumentHost?.close()
        pairingDocumentHost = null
        setup.close()
        super.onDestroy()
    }
}
