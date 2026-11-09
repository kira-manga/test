package com.ukkera.pos.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.app.navigation.BackCoordinator
import com.ukkera.pos.app.navigation.NavigationAction
import com.ukkera.pos.app.navigation.NavigationEntry
import com.ukkera.pos.app.navigation.NavigationState
import com.ukkera.pos.app.navigation.NavigationToken
import com.ukkera.pos.app.navigation.RouteRegistry
import com.ukkera.pos.app.navigation.SecurityRoot
import com.ukkera.pos.app.ui.*
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Shared native CMP root. The caller owns and retains [runtime] across ordinary recomposition. */
@Composable
fun UkkeraApp(runtime: UkkeraRuntime, onExitRequest: () -> Unit = {}, onLocalSetupRequest: (() -> Unit)? = null) {
    val navigation by runtime.navigator.state.collectAsState()
    val presentation by runtime.presentation.collectAsState()
    val token = runtime.navigator.token()
    var escapeDown by remember { mutableStateOf(false) }
    DisposableEffect(runtime) {
        runtime.attachHost()
        onDispose { runtime.detachHost() }
    }
    // Successful authentication removes its sign-in entry/coroutine scope. Authorization of
    // quarantined routes instead belongs to this new root, canceled on logout or host removal.
    LaunchedEffect(runtime, navigation.rootGeneration) { runtime.restoreAuthenticatedRoutes() }
    LaunchedEffect(runtime, navigation.rootGeneration) { runtime.monitorAuthenticatedSession() }
    CompositionLocalProvider(
        LocalAppLanguage provides presentation.language,
        LocalLayoutDirection provides presentation.language.direction,
    ) {
        PosTheme {
            Surface(Modifier.fillMaxSize().safeDrawingPadding().onPreviewKeyEvent { event ->
                if (event.key != Key.Escape) false
                else {
                    if (event.type == KeyEventType.KeyUp) escapeDown = false
                    if (event.type == KeyEventType.KeyDown && !escapeDown) {
                        escapeDown = true
                        val confirmation = navigation.confirmation
                        if (confirmation != null) runtime.navigator.cancelLeave(confirmation.id)
                        else runtime.navigate(NavigationAction.Back, token, onExitRequest)
                    }
                    true
                }
            }, color = Paper) {
                BoxWithConstraints {
                    val expanded = usesSidebar(maxWidth.value, LocalDensity.current.fontScale)
                    Row(Modifier.fillMaxSize()) {
                        if (expanded && navigation.isWorkspace) Sidebar(runtime, navigation, presentation, token, onExitRequest)
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            Header(runtime, navigation, presentation, token, expanded, onExitRequest)
                            if (presentation.training) {
                                Surface(color = AmberSurface, modifier = Modifier.fillMaxWidth()) {
                                    Text(copy(Copy.SYNTHETIC_BANNER), Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                                        style = MaterialTheme.typography.bodySmall, color = AmberText)
                                }
                            }
                            if (!expanded && navigation.isWorkspace) CompactAreas(runtime, navigation, token)
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                // Root reset discards all old protected animations/saveable owners, not an overlay.
                                if (!runtime.closed) key(navigation.rootGeneration) {
                                    NavigationHost(runtime, navigation, onExitRequest, onLocalSetupRequest)
                                }
                            }
                        }
                    }
                    navigation.confirmation?.let { pending ->
                        AlertDialog(
                            onDismissRequest = { runtime.navigator.cancelLeave(pending.id) },
                            title = { Text(copy(Copy.DISCARD_TITLE)) },
                            text = { Text(copy(Copy.DISCARD_BODY)) },
                            confirmButton = { TextButton(onClick = { runtime.confirmLeave(pending.id, onExitRequest) }, modifier = Modifier.touchTarget()) { Text(copy(Copy.DISCARD_LEAVE)) } },
                            dismissButton = { TextButton(onClick = { runtime.navigator.cancelLeave(pending.id) }, modifier = Modifier.touchTarget()) { Text(copy(Copy.KEEP_WORKING)) } },
                        )
                    }
                }
            }
        }
    }
}

/** Text-size-aware breakpoint, independent of platform and locale. */
fun usesSidebar(widthDp: Float, fontScale: Float): Boolean = widthDp / fontScale.coerceAtLeast(1f) >= 920f

@Composable
private fun Header(runtime: UkkeraRuntime, state: NavigationState, presentation: RuntimePresentation,
    token: NavigationToken, expanded: Boolean, exit: () -> Unit) {
    Surface(color = Color.White, shadowElevation = 0.5.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.root != SecurityRoot.BOOTSTRAP && (!state.isWorkspace || state.activeStack.size > 1)) {
                IconButton(onClick = { runtime.navigate(NavigationAction.Back, token, exit) }, Modifier.touchTarget()) { PosIcon(Glyph.BACK, copy(Copy.BACK)) }
            }
            Column(Modifier.weight(1f)) {
                Text(if (state.isWorkspace) {
                    if (presentation.training) copy(if (state.root == SecurityRoot.COMPANY_WORKSPACE) Copy.TRAINING_COMPANY_NAME else Copy.TRAINING_BRANCH_NAME)
                    else presentation.display?.branchName ?: presentation.display?.companyName ?: copy(Copy.BRAND)
                } else copy(Copy.BRAND), style = MaterialTheme.typography.titleMedium)
                Text(if (presentation.training) copy(Copy.TRAINING) else copy(Copy.LOCAL_BUILD),
                    style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            val languageLabel = copy(Copy.LANGUAGE)
            OutlinedButton(onClick = { runtime.changeLanguage(presentation.language.toggle()) },
                modifier = Modifier.touchTarget().semantics { contentDescription = languageLabel },
                shape = RoundedCornerShape(10.dp)) {
                Text(copy(if (presentation.language == AppLanguage.ARABIC) Copy.ENGLISH else Copy.ARABIC))
            }
            if (!presentation.training && state.root == SecurityRoot.BRANCH_WORKSPACE) {
                if (expanded) IconButton(onClick = { runtime.refreshLocalSession(token) }, enabled = !presentation.refreshingSession,
                    modifier = Modifier.touchTarget()) {
                    PosIcon(Glyph.REFRESH, copy(Copy.RENEW_SESSION))
                }
                IconButton(onClick = { runtime.lock(token) }, modifier = Modifier.touchTarget()) {
                    PosIcon(Glyph.LOCK, copy(Copy.LOCK_WORKSPACE))
                }
            }
            if (!expanded && state.isWorkspace) {
                IconButton(onClick = { runtime.navigate(NavigationAction.ExitWorkspace, token, exit) }, modifier = Modifier.touchTarget()) {
                    PosIcon(Glyph.EXIT, copy(if (presentation.training) Copy.EXIT_TRAINING else Copy.SIGN_OUT))
                }
            }
        }
    }
}

@Composable
private fun Sidebar(runtime: UkkeraRuntime, state: NavigationState, presentation: RuntimePresentation, token: NavigationToken, exit: () -> Unit) {
    Column(Modifier.width(218.dp).fillMaxHeight().background(Forest).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = Color(0xFFE8F3CA), shape = RoundedCornerShape(11.dp)) { PosIcon(Glyph.BAG, modifier = Modifier.padding(10.dp), tint = Forest) }
            Text(copy(Copy.BRAND), style = MaterialTheme.typography.titleMedium, color = Color.White)
        }
        Text(copy(Copy.MENU), style = MaterialTheme.typography.labelSmall, color = Color(0xFFC5DBCF), modifier = Modifier.padding(vertical = 12.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.stacks.keys.forEach { area ->
                val selected = state.selectedArea == area
                Button(onClick = { runtime.navigate(NavigationAction.SelectArea(area), token) },
                    modifier = Modifier.fillMaxWidth().touchTarget(), shape = RoundedCornerShape(11.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (selected) Color(0xFFE7F2D0) else Forest,
                        contentColor = if (selected) Forest else Color(0xFFDFECE4))) {
                    PosIcon(areaGlyph(area), tint = if (selected) Forest else Color(0xFFDFECE4))
                    Spacer(Modifier.width(12.dp))
                    Text(areaTitle(area), modifier = Modifier.weight(1f))
                }
            }
        }
        Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (presentation.training) copy(Copy.TRAINING_ACTOR) else presentation.display?.actorName.orEmpty(),
                color = Color.White, style = MaterialTheme.typography.labelLarge)
            Text(copy(if (presentation.training) Copy.NOT_CONNECTED else if (state.root == SecurityRoot.BRANCH_WORKSPACE)
                Copy.LOCAL_SESSION else Copy.LOCAL_PREVIEW), color = Color(0xFFC5DBCF), style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = { runtime.navigate(NavigationAction.ExitWorkspace, token, exit) },
            modifier = Modifier.fillMaxWidth().touchTarget(), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
            PosIcon(Glyph.EXIT, tint = Color.White)
            Spacer(Modifier.width(8.dp))
            Text(copy(if (presentation.training) Copy.EXIT_TRAINING else Copy.SIGN_OUT))
        }
    }
}

@Composable
private fun CompactAreas(runtime: UkkeraRuntime, state: NavigationState, token: NavigationToken) {
    val presentation by runtime.presentation.collectAsState()
    LazyRow(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.stacks.keys.toList(), key = { it.name }) { area ->
            FilterChip(selected = state.selectedArea == area, onClick = { runtime.navigate(NavigationAction.SelectArea(area), token) },
                label = { Text(areaTitle(area)) }, leadingIcon = { PosIcon(areaGlyph(area)) }, modifier = Modifier.touchTarget())
        }
        if (!presentation.training && state.root == SecurityRoot.BRANCH_WORKSPACE) item(key = "renew-session") {
            OutlinedButton(onClick = { runtime.refreshLocalSession(token) }, enabled = !presentation.refreshingSession,
                modifier = Modifier.touchTarget()) { Text(copy(Copy.RENEW_SESSION)) }
        }
    }
}

@Composable
private fun NavigationHost(runtime: UkkeraRuntime, state: NavigationState, exit: () -> Unit, onLocalSetupRequest: (() -> Unit)?) {
    val rootScope = runtime.rootScope
    val token = runtime.navigator.token()
    // All stacks remain decorated while only one is displayed: tab switches retain intended VMs.
    val decorated = state.stacks.mapValues { (area, entries) ->
        key(area) {
            rememberDecoratedNavEntries(
                backStack = entries,
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(viewModelStoreOwner = rootScope, removeViewModelStoreOnPop = { true }),
                ),
                entryProvider = { entry ->
                    NavEntry(entry, contentKey = "${state.rootGeneration}:${entry.id}") { current ->
                        val title = areaTitle(RouteRegistry.area(current.route))
                        Box(Modifier.fillMaxSize().semantics { paneTitle = title }) {
                            val model = koinViewModel<EntryViewModel>(key = "entry-${current.id}", scope = rootScope.koinScope, parameters = { parametersOf(current.id) })
                            // Nav3 caches entry content by backstack. Observe policy-only revisions
                            // inside that cached content, not merely in its remembered provider.
                            val entryNavigation by runtime.navigator.state.collectAsState()
                            // An outgoing animated entry must NEVER capture the incoming entry's identity.
                            val entryToken = runtime.navigator.token().copy(
                                rootGeneration = entryNavigation.rootGeneration, revision = entryNavigation.revision,
                                entryId = current.id, area = RouteRegistry.area(current.route))
                            EntryAdapter(runtime, current.route, model, entryToken, exit, onLocalSetupRequest)
                        }
                    }
                },
            )
        }
    }
    val preview = BackCoordinator.allowsPredictivePreview(state)
    // Public Scene.previousEntries eligibility removes protected history before a guarded gesture.
    // Recreate only the display when eligibility changes, retaining the decorated entry owners.
    key(preview) {
        NavDisplay(entries = decorated.getValue(state.selectedArea), modifier = Modifier.fillMaxSize(),
            sceneStrategies = if (preview) emptyList() else listOf(NoBackPreviewStrategy()),
            onBack = { runtime.navigate(NavigationAction.Back, token, exit) })
    }
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = !preview && state.attached,
        onBackCompleted = {
            state.confirmation?.let { runtime.navigator.cancelLeave(it.id) }
                ?: runtime.navigate(NavigationAction.Back, token, exit)
        },
    )
}

private class NoBackPreviewStrategy : SceneStrategy<NavigationEntry> {
    override fun SceneStrategyScope<NavigationEntry>.calculateScene(entries: List<NavEntry<NavigationEntry>>): Scene<NavigationEntry> =
        NoBackPreviewScene(entries.last())
}

private data class NoBackPreviewScene(val entry: NavEntry<NavigationEntry>) : Scene<NavigationEntry> {
    override val key: Any get() = entry.contentKey
    override val entries: List<NavEntry<NavigationEntry>> = listOf(entry)
    override val previousEntries: List<NavEntry<NavigationEntry>> = emptyList()
    override val content: @Composable () -> Unit = { entry.Content() }
}

@Composable
private fun EntryAdapter(runtime: UkkeraRuntime, route: AppRoute, model: EntryViewModel, token: NavigationToken, exit: () -> Unit, onLocalSetupRequest: (() -> Unit)?) {
    val entryState by model.state.collectAsState()
    val training = runtime.rootScope.session.training
    if (route == AppRoute.SessionAccess) {
        LocalSessionAccessScreen(runtime, token)
        return
    }
    if (training == null && runtime.navigator.state.value.root == SecurityRoot.BRANCH_WORKSPACE) {
        LocalWorkflowScreen(runtime, route, model, token)
        return
    }
    when (route) {
        AppRoute.Start -> WelcomeScreen(runtime, token, onLocalSetupRequest)
        AppRoute.BranchSignIn -> SignInScreen(runtime, token, company = false)
        AppRoute.LocalEnrollment -> EnrollmentScreen(runtime, token)
        AppRoute.CompanySignIn -> SignInScreen(runtime, token, company = true)
        AppRoute.SessionAccess -> Unit // Status is handled before any business-controller access.
        AppRoute.Cashier -> if (training != null) CashierScreen(training, entryState, model::change,
            review = { runtime.navigate(NavigationAction.Push(AppRoute.QuotePreview), token) }) else LiveUnavailableScreen(AppArea.CASHIER)
        AppRoute.QuotePreview -> if (training != null) QuoteScreen(training,
            back = { runtime.navigate(NavigationAction.Back, token, exit) }) else LiveUnavailableScreen(AppArea.CASHIER)
        AppRoute.Orders -> if (training != null) OrdersScreen { id -> runtime.navigate(NavigationAction.Push(AppRoute.Order(id)), token) } else LiveUnavailableScreen(AppArea.ORDERS)
        is AppRoute.Order -> if (training != null) OrderDetailScreen(route.orderId) else LiveUnavailableScreen(AppArea.ORDERS)
        AppRoute.Food, is AppRoute.FoodOrder, is AppRoute.FoodStation, is AppRoute.FoodHandoff -> FoodUnavailableTraining()
        AppRoute.Drawer -> if (training != null) DrawerScreen(entryState, model::change) else LiveUnavailableScreen(AppArea.DRAWER)
        AppRoute.Stock -> if (training != null) StockScreen(training, entryState, model::change) else LiveUnavailableScreen(AppArea.STOCK)
        AppRoute.Reports -> ReportsScreen()
        AppRoute.Setup -> SetupScreen(runtime.dependencies.platform) { step -> runtime.navigate(NavigationAction.Push(AppRoute.SetupStep(step)), token) }
        is AppRoute.SetupStep -> SetupStepScreen(route.step, training != null, entryState, model::change)
        AppRoute.Catalog, AppRoute.People, AppRoute.Purchasing, AppRoute.Devices, AppRoute.CompanySettings ->
            CompanyScreen(RouteRegistry.area(route), training)
    }
}
