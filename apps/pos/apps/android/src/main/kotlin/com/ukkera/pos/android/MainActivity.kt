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
import com.ukkera.pos.app.files.AndroidLocalBranchReportStorageV1
import com.ukkera.pos.app.files.AndroidLocalPurchasingDocumentFiles
import com.ukkera.pos.app.gateway.LocalClientPublication
import com.ukkera.pos.app.gateway.LocalClientRole
import com.ukkera.pos.app.setup.LocalEntryScreen
import com.ukkera.pos.app.setup.LocalMasterOperatingNotice
import com.ukkera.pos.app.setup.LocalSetupConnectionScreen
import com.ukkera.pos.app.setup.LocalSetupConnectionPhase
import com.ukkera.pos.app.setup.LocalSetupModeSelectionScreen
import com.ukkera.pos.app.setup.LocalSetupEmployeePolicyTarget
import com.ukkera.pos.app.setup.LocalSetupScreen
import com.ukkera.pos.app.setup.LocalSetupLanRole
import com.ukkera.pos.app.setup.LocalSetupLanStatus
import com.ukkera.pos.app.ui.AppLanguage
import com.ukkera.pos.security.LocalSetupCompositionMode
import com.ukkera.pos.security.LocalTrainingCompositionSlot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Default local entry; only the retained native service may publish the cohost client. */
open class PosApplication : Application() {
    val reportFiles: AndroidLocalBranchReportFiles by lazy { AndroidLocalBranchReportFiles(this) }
    val trainingReportFiles: AndroidLocalBranchReportFiles by lazy {
        AndroidLocalBranchReportFiles(this, AndroidLocalBranchReportStorageV1.TRAINING)
    }
    val documentFiles: AndroidLocalPurchasingDocumentFiles by lazy { AndroidLocalPurchasingDocumentFiles(this) }
    val trainingDocumentFiles: AndroidLocalPurchasingDocumentFiles by lazy {
        AndroidLocalPurchasingDocumentFiles(this)
    }
    internal val pairingDocuments: AndroidLocalSetupPairingDocuments by lazy { AndroidLocalSetupPairingDocuments(this) }
    internal val localClient = LocalClientPublication()
    internal val trainingLocalClient = LocalClientPublication()
    private val baseDependencies: AppDependencies by lazy {
        val dependencies = clientDependencies()
        require(dependencies.platform.family == PlatformFamily.ANDROID)
        dependencies
    }
    private val trainingOwners = mutableMapOf<LocalTrainingCompositionSlot, AndroidLocalTrainingSetupOwner>()
    /** No default account or synthetic service. Publication requires explicit native setup. */
    protected open fun clientDependencies(): AppDependencies = AppDependencies(branchGateway = localClient,
        platform = PlatformCapabilities(PlatformFamily.ANDROID))
    val runtime: UkkeraRuntime by lazy {
        UkkeraRuntime(baseDependencies.copy(branchReportFiles = reportFiles, documentFiles = documentFiles))
    }
    /** Separate publication/runtime for a production-stack training composition. */
    val trainingRuntime: UkkeraRuntime by lazy {
        UkkeraRuntime(baseDependencies.copy(branchGateway = trainingLocalClient,
            branchReportFiles = trainingReportFiles,
            documentFiles = trainingDocumentFiles,
            // Training never inherits a live hosting/peripheral qualification or central route.
            platform = baseDependencies.platform.copy(liveBranchHostingQualified = false,
                physicalPeripheralsQualified = false),
            centralServicesEnabled = false,
            compositionMode = LocalSetupCompositionMode.TRAINING))
    }
    internal fun trainingSetup(slot: LocalTrainingCompositionSlot): AndroidLocalTrainingSetupOwner =
        synchronized(trainingOwners) {
            trainingOwners.getOrPut(slot) { AndroidLocalTrainingSetupOwner(this, slot) }
        }

    /** Presentation teardown must not create an owner after a confirmed retirement. */
    internal fun lockTrainingPresentation(slot: LocalTrainingCompositionSlot) {
        synchronized(trainingOwners) { trainingOwners[slot] }?.lockPresentation()
    }

    /** Close any other training setup owner before switching slots; never touches live roots. */
    internal fun retireTrainingSetups(except: LocalTrainingCompositionSlot?): List<Deferred<Unit>> {
        val owners = synchronized(trainingOwners) {
            trainingOwners.filterKeys { it != except }.values.toList()
        }
        return owners.map(::retireTrainingOwner)
    }

    /** Retire exactly the retained owner; late close cannot remove a newer slot incarnation. */
    internal fun retireTrainingOwner(owner: AndroidLocalTrainingSetupOwner): Deferred<Unit> =
        owner.requestRetirement()

    internal fun observeTrainingRetirement(owner: AndroidLocalTrainingSetupOwner, completion: Deferred<Unit>) {
        completion.invokeOnCompletion { failure ->
            if (failure == null) synchronized(trainingOwners) {
                if (trainingOwners[owner.slot] === owner) trainingOwners.remove(owner.slot)
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    private val runtime: UkkeraRuntime get() = (application as PosApplication).runtime
    private var reportFileHost: AutoCloseable? = null
    private var trainingReportFileHost: AutoCloseable? = null
    private var documentFileHost: AndroidLocalPurchasingDocumentFiles.Binding? = null
    private var trainingDocumentFileHost: AndroidLocalPurchasingDocumentFiles.Binding? = null
    private val documentPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val app = application as PosApplication
        app.documentFiles.onActivityResult(this, result.resultCode, result.data)
        app.trainingDocumentFiles.onActivityResult(this, result.resultCode, result.data)
    }
    private var pairingDocumentHost: AndroidLocalSetupPairingDocuments.Binding? = null
    private val pairingDocumentPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        (application as PosApplication).pairingDocuments.onActivityResult(this, result.resultCode, result.data)
    }
    private lateinit var setup: AndroidSetupConnection
    private val trainingScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lanPermissionGranted by mutableStateOf(false)
    private val lanPermissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Observe current OS state; a callback is never branch/device authority.
        lanPermissionGranted = AndroidLocalNetworkPermission.isGranted(this)
    }
    private fun requestLanPermission() {
        if (!visible) return
        lanPermissionGranted = AndroidLocalNetworkPermission.isGranted(this)
        if (!lanPermissionGranted) lanPermissionRequest.launch(AndroidLocalNetworkPermission.NAME)
    }
    private var visible = false
    private var route by mutableStateOf(EntryRoute.CHOICE)
    private var language by mutableStateOf(AppLanguage.ENGLISH)
    // Explicit selection DATA only; never saved in Bundle, journal, Intent or logs.
    private var liveEmployeePolicyTarget by mutableStateOf<LocalSetupEmployeePolicyTarget?>(null)
    private var trainingEmployeePolicyTarget by mutableStateOf<LocalSetupEmployeePolicyTarget?>(null)
    private var trainingSlot by mutableStateOf<LocalTrainingCompositionSlot?>(null)

    private enum class EntryRoute { CHOICE, SETUP_MODE, SETUP, TRAINING_SETUP, EMPLOYEE, TRAINING_EMPLOYEE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setup = AndroidSetupConnection(this)
        lanPermissionGranted = AndroidLocalNetworkPermission.isGranted(this)
        // Only non-sensitive route/language are restored. No controller, native action,
        // credential, readiness flag or private review enters Android saved state.
        route = EntryRoute.entries.firstOrNull { it.name == savedInstanceState?.getString("local.entry.route") }
            ?: EntryRoute.CHOICE
        trainingSlot = LocalTrainingCompositionSlot.entries.firstOrNull {
            it.name == savedInstanceState?.getString("local.entry.training.slot")
        }
        language = AppLanguage.entries.firstOrNull { it.name == savedInstanceState?.getString("local.entry.language") }
            ?: if (resources.configuration.locales[0]?.language == "ar") AppLanguage.ARABIC else AppLanguage.ENGLISH
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            val localRole by (application as PosApplication).localClient.role.collectAsState(initial = null)
            val trainingRole by (application as PosApplication).trainingLocalClient.role.collectAsState(initial = null)
            val setupController by setup.controller.collectAsState()
            val lan = setupController?.state?.collectAsState()?.value?.lan
            val hosting = localRole == LocalClientRole.MASTER || trainingRole == LocalClientRole.MASTER ||
                (lan?.role == LocalSetupLanRole.HOST && lan.status == LocalSetupLanStatus.LISTENING)
            DisposableEffect(hosting) {
                if (hosting) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
            when (route) {
                EntryRoute.CHOICE -> LocalEntryScreen(language, { language = it },
                    onSetup = { choose(EntryRoute.SETUP_MODE) }, onEmployee = { choose(EntryRoute.EMPLOYEE) },
                    onTrainingEmployee = trainingRole?.let { { choose(EntryRoute.TRAINING_EMPLOYEE) } })
                EntryRoute.SETUP_MODE -> LocalSetupModeSelectionScreen(language, { language = it },
                    onLive = { choose(EntryRoute.SETUP) },
                    onTraining = { slot -> selectTraining(slot) },
                    onBack = { choose(EntryRoute.CHOICE) })
                EntryRoute.TRAINING_SETUP -> {
                    val slot = trainingSlot
                    if (slot == null) {
                        LocalSetupConnectionScreen(LocalSetupConnectionPhase.UNAVAILABLE, language, { language = it },
                            onRetry = { choose(EntryRoute.SETUP_MODE) },
                            onBack = { choose(EntryRoute.CHOICE) })
                    } else {
                        val owner = (application as PosApplication).trainingSetup(slot)
                        DisposableEffect(owner) {
                            owner.reopenRetainedMaster()
                            onDispose { owner.lockPresentation() }
                        }
                        LocalSetupScreen(owner.controller, language, { language = it },
                            onBack = { owner.lockPresentation(); choose(EntryRoute.CHOICE) },
                            compositionMode = LocalSetupCompositionMode.TRAINING,
                            employeePolicyTarget = trainingEmployeePolicyTarget,
                            onEmployeePolicyTargetCleared = { trainingEmployeePolicyTarget = null },
                            lanPermissionGranted = lanPermissionGranted, onRequestLanPermission = ::requestLanPermission)
                        BackHandler { owner.lockPresentation(); choose(EntryRoute.CHOICE) }
                    }
                }
                EntryRoute.SETUP -> {
                    val phase by setup.phase.collectAsState()
                    val actual = setupController
                    if (actual == null) LocalSetupConnectionScreen(phase, language, { language = it },
                        onRetry = { if (visible) { setup.disconnect(); setup.connect() } },
                        onBack = { choose(EntryRoute.CHOICE) })
                    else LocalSetupScreen(actual, language, { language = it },
                        onBack = { choose(EntryRoute.CHOICE) },
                        employeePolicyTarget = liveEmployeePolicyTarget,
                        onEmployeePolicyTargetCleared = { liveEmployeePolicyTarget = null },
                        lanPermissionGranted = lanPermissionGranted, onRequestLanPermission = ::requestLanPermission)
                    BackHandler { choose(EntryRoute.CHOICE) }
                }
                EntryRoute.EMPLOYEE -> UkkeraApp(runtime, onExitRequest = { choose(EntryRoute.CHOICE) },
                    onLocalSetupRequest = { choose(EntryRoute.SETUP_MODE) },
                    onEmployeePolicyRequest = if (localRole == LocalClientRole.MASTER) ({ target ->
                        choose(EntryRoute.SETUP)
                        liveEmployeePolicyTarget = target
                    }) else null)
                EntryRoute.TRAINING_EMPLOYEE -> UkkeraApp((application as PosApplication).trainingRuntime,
                    onExitRequest = { choose(EntryRoute.CHOICE) },
                    onLocalSetupRequest = { choose(EntryRoute.SETUP_MODE) },
                    onEmployeePolicyRequest = if (trainingRole == LocalClientRole.MASTER && trainingSlot != null) ({ target ->
                        choose(EntryRoute.TRAINING_SETUP)
                        trainingEmployeePolicyTarget = target
                    }) else null)
            }
            LocalMasterOperatingNotice(hosting, language)
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        if (route != EntryRoute.TRAINING_SETUP && route != EntryRoute.TRAINING_EMPLOYEE) {
            setup.connect() // Existing-only shared startup for the live path.
        }
        reportFileHost = (application as PosApplication).reportFiles.bind(this)
        trainingReportFileHost = (application as PosApplication).trainingReportFiles.bind(this)
        if (documentFileHost == null) {
            documentFileHost = (application as PosApplication).documentFiles.bind(this,
                launch = { documentPicker.launch(it) }, onInvalidated = {
                    documentFileHost = null
                    if (route == EntryRoute.EMPLOYEE) runtime.lock(runtime.navigator.token())
                })
        }
        if (trainingDocumentFileHost == null) {
            trainingDocumentFileHost = (application as PosApplication).trainingDocumentFiles.bind(this,
                launch = { documentPicker.launch(it) }, onInvalidated = {
                    trainingDocumentFileHost = null
                    if (route == EntryRoute.TRAINING_EMPLOYEE) {
                        val training = (application as PosApplication).trainingRuntime
                        training.lock(training.navigator.token())
                    }
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
        lanPermissionGranted = AndroidLocalNetworkPermission.isGranted(this)
        documentFileHost?.onResume()
        trainingDocumentFileHost?.onResume()
        pairingDocumentHost?.onResume()
    }

    override fun onPause() {
        documentFileHost?.onPause()
        trainingDocumentFileHost?.onPause()
        pairingDocumentHost?.onPause()
        super.onPause()
    }

    override fun onUserLeaveHint() {
        documentFileHost?.onUserLeaving()
        trainingDocumentFileHost?.onUserLeaving()
        pairingDocumentHost?.onUserLeaving()
        super.onUserLeaveHint()
    }

    override fun onStop() {
        liveEmployeePolicyTarget = null
        trainingEmployeePolicyTarget = null
        visible = false
        setup.disconnect() // presentation/client attachment only, not the native setup lifetime
        reportFileHost?.close()
        reportFileHost = null
        trainingReportFileHost?.close()
        trainingReportFileHost = null
        // Only one actually launched native document picker can suspend this Activity's
        // presentation. No protected native read/write occurs until it is resumed and the
        // exact original shared session is current; unrelated stops retain the lock rule.
        val returningFromDocumentPicker = documentFileHost?.stopForPicker() == true
        val returningFromTrainingDocumentPicker = trainingDocumentFileHost?.stopForPicker() == true
        if (!returningFromDocumentPicker) {
            documentFileHost?.close()
            documentFileHost = null
        }
        if (!returningFromTrainingDocumentPicker) {
            trainingDocumentFileHost?.close()
            trainingDocumentFileHost = null
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
        if (route == EntryRoute.TRAINING_SETUP) {
            trainingSlot?.let { (application as PosApplication).lockTrainingPresentation(it) }
        }
        if (route == EntryRoute.TRAINING_EMPLOYEE && !returningFromTrainingDocumentPicker) {
            val training = (application as PosApplication).trainingRuntime
            training.lock(training.navigator.token())
        }
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("local.entry.route", route.name)
        outState.putString("local.entry.language", language.name)
        outState.putString("local.entry.training.slot", trainingSlot?.name)
        super.onSaveInstanceState(outState)
    }

    private fun selectTraining(slot: LocalTrainingCompositionSlot) {
        trainingScope.launch {
            val pending: List<Deferred<Unit>> =
                (application as PosApplication).retireTrainingSetups(except = slot)
            var confirmed = true
            pending.forEach { deferred ->
                if (runCatching { deferred.await() }.isFailure) confirmed = false
            }
            if (!confirmed) { choose(EntryRoute.SETUP_MODE); return@launch }
            trainingSlot = slot
            choose(EntryRoute.TRAINING_SETUP)
        }
    }

    private fun choose(next: EntryRoute) {
        liveEmployeePolicyTarget = null
        trainingEmployeePolicyTarget = null
        if (route == EntryRoute.SETUP && next != EntryRoute.SETUP) setup.controller.value?.lockPresentation()
        if (route == EntryRoute.TRAINING_SETUP && next != EntryRoute.TRAINING_SETUP) {
            trainingSlot?.let { (application as PosApplication).lockTrainingPresentation(it) }
        }
        if (route == EntryRoute.EMPLOYEE && next != EntryRoute.EMPLOYEE) runtime.lock(runtime.navigator.token())
        if (route == EntryRoute.TRAINING_EMPLOYEE && next != EntryRoute.TRAINING_EMPLOYEE) {
            (application as PosApplication).trainingRuntime.lock((application as PosApplication).trainingRuntime.navigator.token())
        }
        route = next
        if (visible) {
            if (next == EntryRoute.TRAINING_SETUP || next == EntryRoute.TRAINING_EMPLOYEE) setup.disconnect()
            else setup.connect()
        }
    }

    override fun onDestroy() {
        reportFileHost?.close()
        reportFileHost = null
        trainingReportFileHost?.close()
        trainingReportFileHost = null
        documentFileHost?.close()
        documentFileHost = null
        trainingDocumentFileHost?.close()
        trainingDocumentFileHost = null
        pairingDocumentHost?.close()
        pairingDocumentHost = null
        setup.close()
        trainingScope.cancel()
        super.onDestroy()
    }
}
