package com.ukkera.pos.app.gateway.independent.returnrightspresentationv1

import com.ukkera.pos.app.AppDependencies
import com.ukkera.pos.app.BranchUiGateway
import com.ukkera.pos.app.BranchUiSession
import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.app.LocalWorkflow
import com.ukkera.pos.app.LocalWorkflowController
import com.ukkera.pos.app.LocalWorkspaceState
import com.ukkera.pos.app.PlatformCapabilities
import com.ukkera.pos.app.PlatformFamily
import com.ukkera.pos.app.SessionDisplayContext
import com.ukkera.pos.app.SignInResult
import com.ukkera.pos.app.UkkeraRuntime
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.app.navigation.NavigationAction
import com.ukkera.pos.app.navigation.SecurityRoot
import com.ukkera.pos.app.ui.AppLanguage
import com.ukkera.pos.database.ReadFailure
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Observable common-controller state, with the real gateway beneath controlled UI attachments.
 * Explicit controller departure is exercised; installed composition, physical frames, automatic
 * entry-model cleanup and authenticated restored-route acceptance remain separate obligations.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReturnRightsPresentationControllerFrozenSlicesTest {
    private val inputs = FrozenReturnRightsPresentationInputs

    @Test fun rrp004_localeSwitchKeepsBothOriginalNoticesWithoutAnotherProtectedRead() = controllerTest {
        for (workflow in inputs.workflows) for (historical in listOf(false, true))
            for (fixture in listOf("F-RRP-A", "F-RRP-B")) withController(workflow, historical, fixture) { binding ->
                runCurrent()
                val page = assertNotNull(binding.controller.state.value.page)
                val rights = assertNotNull(page.returnRights)
                for (language in listOf(AppLanguage.ENGLISH, AppLanguage.ARABIC, AppLanguage.ENGLISH)) {
                    binding.runtime.changeLanguage(language)
                    val selected = binding.runtime.presentation.value.language
                    val field = if (selected == AppLanguage.ARABIC) "arabicNoticeLiteral" else "englishNoticeLiteral"
                    assertEquals(inputs.visible(fixture).string(field), selected.text(rights.continuingRights))
                    assertEquals(page, binding.controller.state.value.page)
                    assertEquals(1, binding.ports.master.protectedRequests.size)
                }
                binding.ports.assertNoMutationDispatch("RRP-004/controller/$workflow/$historical/$fixture")
            }
    }

    @Test fun rrp005_and_rrp006_statusRefreshRetiresPriorPublishedPageBeforeReply() = controllerTest {
        for (workflow in inputs.workflows) for (historical in listOf(false, true))
            for (fixture in listOf("F-RRP-ABSENT", "F-RRP-SOURCE-UNAVAILABLE"))
                withController(workflow, historical, "F-RRP-A") { binding ->
                    runCurrent()
                    assertNotNull(binding.controller.state.value.page?.returnRights)
                    val boundary = binding.states.size
                    binding.ports.master.replyFixture(fixture)
                    val delivery = ControlledDelivery<ReadReply>()
                    binding.ports.master.readDelivery = delivery
                    if (historical && fixture == "F-RRP-ABSENT") {
                        binding.controller.deactivate()
                        binding.controller.activate(workflow,
                            inputs.selection(workflow, true, inputs.id("unretainedPublication")).reference)
                    } else binding.controller.refresh(binding.runtime.navigator.token())
                    assertNull(binding.controller.state.value.page)
                    runCurrent()
                    assertTrue(delivery.waiting.isCompleted)
                    assertNoDocumentaryState(binding.states.drop(boundary))
                    delivery.deliver()
                    runCurrent()
                    assertStatusPage(assertNotNull(binding.controller.state.value.page))
                    assertNoDocumentaryState(binding.states.drop(boundary))
                    binding.ports.assertNoMutationDispatch("RRP-005/006/controller/$workflow/$historical/$fixture")
                }
    }

    @Test fun rrp011_permissionRefusalCannotRetainOrTransientlyRepublishPriorTerms() = controllerTest {
        for (workflow in inputs.workflows) for (historical in listOf(false, true))
            withController(workflow, historical, "F-RRP-A") { binding ->
                runCurrent()
                assertNotNull(binding.controller.state.value.page?.returnRights)
                val boundary = binding.states.size
                binding.ports.master.replyUnavailable(ReadReply.Unavailable(ReadFailure.AUTHORIZATION_DENIED))
                binding.controller.refresh(binding.runtime.navigator.token())
                assertNull(binding.controller.state.value.page)
                runCurrent()
                assertNull(binding.controller.state.value.page)
                assertEquals(LocalServiceProblem.DENIED, binding.controller.state.value.readProblem)
                assertNoDocumentaryState(binding.states.drop(boundary))
                assertEquals(2, binding.ports.master.protectedRequests.size)
                binding.ports.assertNoMutationDispatch("RRP-011/controller/$workflow/$historical")
            }
    }

    @Test fun rrp012_missingReadDoesNotCreateAbsentOrUnavailableDocumentaryState() = controllerTest {
        for (workflow in inputs.workflows) for (historical in listOf(false, true)) {
            val ports = ControlledReturnRightsPresentationHarness(installedRead = false)
            withController(workflow, historical, null, ports) { binding ->
                runCurrent()
                assertEquals(LocalServiceProblem.NOT_IMPLEMENTED, binding.controller.state.value.readProblem)
                assertNull(binding.controller.state.value.page)
                assertNoDocumentaryState(binding.states)
                assertEquals(0, ports.master.protectedRequests.size)
                ports.assertNoMutationDispatch("RRP-012/controller/$workflow/$historical")
            }
        }
    }

    @Test fun rrp014_eachLeaseChangeDuringReadOrBeforeControllerReleaseKeepsStatePrivate() = controllerTest {
        for (workflow in inputs.workflows) for (historical in listOf(false, true))
            for (variant in inputs.array("leaseVariants")) for (afterReply in listOf(false, true))
                withController(workflow, historical, "F-RRP-A") { binding ->
                    runCurrent()
                    assertNotNull(binding.controller.state.value.page?.returnRights)
                    val boundary = binding.states.size
                    val delivery = ControlledDelivery<ReadReply>()
                    binding.ports.master.readDelivery = delivery
                    binding.ports.master.replyFixture("F-RRP-A")
                    binding.controller.refresh(binding.runtime.navigator.token())
                    runCurrent()
                    assertTrue(delivery.waiting.isCompleted)
                    if (afterReply) delivery.deliver()
                    binding.ports.invalidate(variant.string("variantId"))
                    if (!afterReply) delivery.deliver()
                    runCurrent()
                    assertNoDocumentaryState(binding.states.drop(boundary))
                    assertNull(binding.controller.state.value.page)
                    assertEquals(SecurityRoot.BOOTSTRAP, binding.runtime.navigator.state.value.root)
                    binding.ports.assertNoMutationDispatch("RRP-014/controller/$workflow/$historical/${variant.string("variantId")}/$afterReply")
                }
    }

    @Test fun rrp015_departureCancelsEveryFrozenBoundaryAndLateRepliesCannotPublish() = controllerTest {
        for (workflow in inputs.workflows) for (historical in listOf(false, true))
            for (boundary in inputs.root.getValue("cancellationBoundaries").jsonArray)
                withController(workflow, historical, "F-RRP-A") { binding ->
                    runCurrent()
                    val firstPrivateState = binding.states.size
                    val named = boundary.jsonPrimitive.content
                    val delivery = ControlledDelivery<ReadReply>()
                    binding.ports.master.readDelivery = delivery
                    binding.ports.master.replyFixture("F-RRP-A")
                    binding.controller.refresh(binding.runtime.navigator.token())
                    if (named != "before_read") {
                        runCurrent()
                        assertTrue(delivery.waiting.isCompleted)
                        if (named == "reply_received_before_page_release") delivery.deliver()
                    }
                    binding.controller.deactivate()
                    if (named == "read_waiting") delivery.deliver()
                    runCurrent()
                    assertNoDocumentaryState(binding.states.drop(firstPrivateState))
                    assertNull(binding.controller.state.value.page)
                    assertEquals(if (named == "before_read") 1 else 2, binding.ports.master.protectedRequests.size)
                    binding.ports.assertNoMutationDispatch("RRP-015/controller/$workflow/$historical/$named")
                }
    }

    @Test fun rrp016_explicitDepartureAndReplacementReentryCannotReuseOldPageOrCallback() = controllerTest {
        for (workflow in inputs.workflows) for (historical in listOf(false, true))
            for (departure in listOf("Back", "tab", "reentry")) withController(workflow, historical, "F-RRP-A") { original ->
                runCurrent()
                assertNotNull(original.controller.state.value.page?.returnRights)
                val oldToken = original.runtime.navigator.token()
                val firstPrivateState = original.states.size
                original.controller.deactivate()
                val departureToken = original.runtime.navigator.token()
                when (departure) {
                    "Back" -> original.runtime.navigate(NavigationAction.Back, departureToken)
                    "tab" -> {
                        original.runtime.navigate(NavigationAction.SelectArea(
                            if (workflow == LocalWorkflow.CATALOG) AppArea.SETUP else AppArea.CATALOG), departureToken)
                        original.runtime.localSessionEnded(original.session)
                    }
                    else -> original.runtime.localSessionEnded(original.session)
                }
                assertNoDocumentaryState(original.states.drop(firstPrivateState))
                val freshPorts = ControlledReturnRightsPresentationHarness(inputs.replacementScope,
                    DomainId(inputs.replacement.string("actorId")))
                try {
                    val freshSession = ControlledDocumentaryUiSession(freshPorts)
                    original.login.next = freshSession
                    authenticate(original.runtime, workflow)
                    val fresh = LocalWorkflowController(original.runtime, freshSession, original.runtime.navigator.token(), backgroundScope)
                    try {
                        freshPorts.master.replyUnavailable(ReadReply.Unavailable(ReadFailure.AUTHORIZATION_DENIED))
                        fresh.activate(workflow, inputs.selection(workflow, historical, selectedScope = inputs.replacementScope).reference)
                        runCurrent()
                        assertNull(fresh.state.value.page)
                        assertEquals(LocalServiceProblem.DENIED, fresh.state.value.readProblem)
                        assertEquals(listOf(inputs.query(historical)), freshPorts.master.protectedRequests.map { it.payload.rrpText() })
                        original.controller.refresh(oldToken)
                        runCurrent()
                        assertNoDocumentaryState(original.states.drop(firstPrivateState))
                        assertNull(fresh.state.value.page)
                        freshPorts.assertNoMutationDispatch("RRP-016/controller/$workflow/$historical/$departure")
                    } finally { fresh.close() }
                } finally { freshPorts.close() }
            }
    }

    @Test fun rrp017_openingDocumentaryStateAndLocaleNavigationNeverCreatesAnActionDraft() = controllerTest {
        for (workflow in inputs.workflows) for (historical in listOf(false, true))
            for (fixture in listOf("F-RRP-A", "F-RRP-B", "F-RRP-ABSENT", "F-RRP-SOURCE-UNAVAILABLE"))
                withController(workflow, historical, fixture) { binding ->
                    runCurrent()
                    assertTrue(assertNotNull(binding.controller.state.value.page).forms.isEmpty())
                    assertNull(binding.controller.state.value.form)
                    assertNull(binding.controller.state.value.operationId)
                    for (language in listOf(AppLanguage.ENGLISH, AppLanguage.ARABIC, AppLanguage.ENGLISH)) {
                        binding.runtime.changeLanguage(language)
                        assertNull(binding.controller.state.value.form)
                        assertNull(binding.controller.state.value.operationId)
                    }
                    binding.ports.master.replyFixture(fixture)
                    binding.controller.refresh(binding.runtime.navigator.token())
                    runCurrent()
                    assertNull(binding.controller.state.value.form)
                    assertNull(binding.controller.state.value.operationId)
                    binding.ports.assertNoMutationDispatch("RRP-017/controller/$workflow/$historical/$fixture")
                }
    }

    private fun controllerTest(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { block() } finally { Dispatchers.resetMain() }
    }

    private suspend fun TestScope.withController(workflow: LocalWorkflow, historical: Boolean, fixture: String?,
        ports: ControlledReturnRightsPresentationHarness = ControlledReturnRightsPresentationHarness(),
        block: suspend TestScope.(ControllerBinding) -> Unit) {
        val session = ControlledDocumentaryUiSession(ports)
        val login = ControlledDocumentaryUiLogin(session)
        val runtime = UkkeraRuntime(AppDependencies(branchGateway = login,
            platform = PlatformCapabilities(PlatformFamily.DESKTOP)))
        var controller: LocalWorkflowController? = null
        try {
            authenticate(runtime, workflow)
            val active = LocalWorkflowController(runtime, session, runtime.navigator.token(), backgroundScope)
            controller = active
            val states = mutableListOf<LocalWorkspaceState>()
            val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                active.state.collect { states += it }
            }
            try {
                if (fixture != null) ports.master.replyFixture(fixture)
                val publication = when (fixture) {
                    "F-RRP-A", "F-RRP-B", "F-RRP-SCALAR" -> DomainId(inputs.visible(fixture).string("publicationId"))
                    "F-RRP-ABSENT" -> inputs.id("unretainedPublication")
                    else -> inputs.id("publicationA")
                }
                active.activate(workflow, inputs.selection(workflow, historical, publication, ports.selectedScope).reference)
                block(ControllerBinding(runtime, active, session, login, ports, states))
            } finally { observer.cancel() }
        } finally { controller?.close(); runtime.close(); ports.close() }
    }

    private suspend fun authenticate(runtime: UkkeraRuntime, workflow: LocalWorkflow) {
        runtime.openSignIn(company = false, token = runtime.navigator.token())
        runtime.signIn("synthetic-documentary-session", CharArray(16) { 'x' }, runtime.navigator.token())
        assertEquals(SecurityRoot.BRANCH_WORKSPACE, runtime.navigator.state.value.root)
        val area = if (workflow == LocalWorkflow.CATALOG) AppArea.CATALOG else AppArea.SETUP
        runtime.navigate(NavigationAction.SelectArea(area), runtime.navigator.token())
        assertEquals(area, runtime.navigator.state.value.selectedArea)
    }
}

private data class ControllerBinding(
    val runtime: UkkeraRuntime,
    val controller: LocalWorkflowController,
    val session: ControlledDocumentaryUiSession,
    val login: ControlledDocumentaryUiLogin,
    val ports: ControlledReturnRightsPresentationHarness,
    val states: MutableList<LocalWorkspaceState>,
)

private class ControlledDocumentaryUiSession(private val ports: ControlledReturnRightsPresentationHarness) : BranchUiSession {
    private var attached = true
    override val display = SessionDisplayContext(ports.actor, ports.selectedScope.companyId, ports.selectedScope.branchId,
        "Synthetic employee", "Synthetic company", "Synthetic branch", setOf(AppArea.CATALOG, AppArea.SETUP))
    override val workflows = ports.gateway
    override fun isCurrent(): Boolean = attached
    override suspend fun mayRestore(route: AppRoute): Boolean = false
    override fun close() { attached = false; ports.close() }
}

private class ControlledDocumentaryUiLogin(var next: ControlledDocumentaryUiSession) : BranchUiGateway {
    override val configured = true
    override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> = SignInResult.Authenticated(next)
}

private fun assertNoDocumentaryState(states: List<LocalWorkspaceState>) {
    assertTrue(states.isNotEmpty())
    for (state in states) {
        assertNull(state.page?.returnRights)
        assertNull(state.form)
        assertNull(state.operationId)
    }
}
