package com.ukkera.pos.app.independent.sessionterminalcleanupv1

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ukkera.pos.app.*
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.app.navigation.NavigationToken
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.koin.core.parameter.parametersOf
import kotlin.test.*

/**
 * Implementer L1 wiring of existing STC-S10/S19 permission-loss/original-recovery
 * invariants. Runtime/controller are real; session, directory and retained request
 * observations are controlled DATA. No Master authorization, durable journal,
 * native authority, business effect or complete frozen-case qualification.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdministrationPermissionLossFrozenSlicesTest {
    @Test fun stcS10_deniedOriginalLookupRetainsIdentityWithoutPrivateReceipt() = runTest {
        withController { fixture ->
            fixture.gateway.lookupReply = LocalAdministrationResult.Unresolved(REQUEST.requestId, LocalServiceProblem.DENIED)
            fixture.controller.recover(fixture.token)
            runCurrent()
            assertEquals(listOf(REQUEST.requestId), fixture.gateway.lookups)
            assertRestricted(fixture)
            assertEquals(ACTOR, fixture.runtime.presentation.value.display?.actorId)
            assertTrue(fixture.session.isCurrent(), "A scoped denial must not end unrelated rights")
        }
    }

    @Test fun stcS10_deniedDirectoryRefreshRetiresLoadedOriginalDetails() = runTest {
        withController { fixture ->
            assertNotNull(fixture.controller.state.value.pending?.receipt)
            fixture.controller.changeTarget(TARGET.value, fixture.token)
            fixture.gateway.readReply = LocalAdministrationReadResult.Denied
            fixture.controller.refresh(fixture.token)
            runCurrent()
            assertRestricted(fixture)
            assertTrue(fixture.session.isCurrent())
        }
    }

    @Test fun stcS19_repeatedDeniedRecoveryNeverForgetsOrReplacesSelectedOriginal() = runTest {
        withController { fixture ->
            fixture.gateway.lookupReply = LocalAdministrationResult.Unresolved(REQUEST.requestId, LocalServiceProblem.DENIED)
            fixture.gateway.pendingReply = LocalAdministrationPendingResult.Unavailable(LocalServiceProblem.DENIED)
            repeat(2) {
                fixture.controller.recover(fixture.token)
                runCurrent()
                assertRestricted(fixture)
            }
            assertEquals(listOf(REQUEST.requestId, REQUEST.requestId), fixture.gateway.lookups)
            fixture.controller.retryPreparation(fixture.token)
            fixture.controller.submit(fixture.token)
            fixture.controller.acknowledge(fixture.token)
            runCurrent()
            assertEquals(0, fixture.gateway.prepares)
            assertEquals(0, fixture.gateway.submits)
            assertEquals(0, fixture.gateway.acknowledgements)
        }
    }

    @Test fun stcS19_freshAuthorizedLookupRestoresOnlyTheExactOriginalResult() = runTest {
        withController { fixture ->
            fixture.gateway.lookupReply = LocalAdministrationResult.Unresolved(REQUEST.requestId, LocalServiceProblem.DENIED)
            fixture.controller.recover(fixture.token)
            runCurrent()
            assertRestricted(fixture)
            fixture.gateway.lookupReply = LocalAdministrationResult.Known(SAVED)
            fixture.controller.recover(fixture.token)
            runCurrent()
            assertEquals(listOf(REQUEST.requestId, REQUEST.requestId), fixture.gateway.lookups)
            assertEquals(REQUEST, fixture.controller.state.value.original)
            assertEquals(SAVED, fixture.controller.state.value.pending)
            assertEquals(REQUEST, fixture.controller.state.value.displayedOriginal)
            assertNull(fixture.controller.state.value.operationProblem)
            assertEquals(ACTOR, fixture.controller.state.value.pending?.receipt?.originalOperatorId)
            assertEquals(0, fixture.gateway.prepares)
            assertEquals(0, fixture.gateway.submits)
        }
    }

    @Test fun stcS10_unavailableOriginalLookupCannotRediscloseDeniedDetails() = runTest {
        withController { fixture ->
            fixture.gateway.lookupReply = LocalAdministrationResult.Unresolved(REQUEST.requestId, LocalServiceProblem.DENIED)
            fixture.controller.recover(fixture.token)
            runCurrent()
            assertRestricted(fixture)
            fixture.gateway.lookupReply = LocalAdministrationResult.Unresolved(REQUEST.requestId, LocalServiceProblem.UNAVAILABLE)
            fixture.controller.recover(fixture.token)
            runCurrent()
            assertEquals(REQUEST, fixture.controller.state.value.original)
            assertNull(fixture.controller.state.value.displayedOriginal)
            assertNull(fixture.controller.state.value.pending)
            assertFalse(fixture.controller.state.value.canSubmit)
            assertEquals(listOf(REQUEST.requestId, REQUEST.requestId), fixture.gateway.lookups)
        }
    }

    @Test fun stcS19_directoryRecoveryAloneCannotRestoreProtectedOriginalDetails() = runTest {
        withController { fixture ->
            val availableDirectory = fixture.gateway.readReply
            fixture.gateway.readReply = LocalAdministrationReadResult.Denied
            fixture.controller.refresh(fixture.token)
            runCurrent()
            assertRestricted(fixture)
            fixture.gateway.readReply = availableDirectory
            fixture.controller.refresh(fixture.token)
            runCurrent()
            assertNotNull(fixture.controller.state.value.view)
            assertEquals(REQUEST, fixture.controller.state.value.original)
            assertNull(fixture.controller.state.value.displayedOriginal)
            assertNull(fixture.controller.state.value.pending)
            assertFalse(fixture.controller.state.value.canSubmit)
            assertTrue(fixture.gateway.lookups.isEmpty())
        }
    }

    private fun assertRestricted(fixture: Fixture) {
        val state = fixture.controller.state.value
        assertEquals(REQUEST, state.original, "Preserve the selected exact original for protected recovery")
        assertNull(state.displayedOriginal, "Denied target/action metadata must not be redisplayed")
        assertNull(state.pending, "Denied presentation must not retain a private result/receipt")
        assertNull(state.view)
        assertEquals("", state.targetInput)
        assertNull(state.query.targetId)
        assertNull(state.query.cursor)
        assertFalse(state.passwordEntryOpen)
        assertFalse(state.canPrepare)
        assertFalse(state.canSubmit)
        assertFalse(state.preparationUncertain)
        assertEquals(LocalServiceProblem.DENIED, state.operationProblem)
    }

    private suspend fun TestScope.withController(block: suspend TestScope.(Fixture) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val gateway = ControlledAdministration()
        val session = ControlledAdministrationSession(gateway)
        val runtime = UkkeraRuntime(AppDependencies(platform = PlatformCapabilities(PlatformFamily.DESKTOP),
            branchGateway = object : BranchUiGateway {
                override val configured = true
                override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> =
                    SignInResult.Authenticated(session)
            }))
        try {
            runtime.openSignIn(false, runtime.navigator.token())
            runtime.signIn("synthetic-administration", "synthetic".toCharArray(), runtime.navigator.token())
            runCurrent()
            val token = runtime.navigator.token()
            val root = runtime.rootScope
            val entryId = runtime.navigator.state.value.activeEntry.id
            val provider = ViewModelProvider.create(root.viewModelStore,
                factory = viewModelFactory { initializer { root.koinScope.get<EntryViewModel> { parametersOf(entryId) } } })
            val entry = provider["entry-$entryId", EntryViewModel::class]
            val controller = assertNotNull(entry.localAdministration(runtime, token))
            controller.activate(LocalAdministrationDirectory.ACCOUNTS)
            runCurrent()
            assertEquals(SAVED, controller.state.value.pending)
            block(Fixture(runtime, session, gateway, controller))
        } finally {
            runtime.close()
            Dispatchers.resetMain()
        }
    }
}

private data class Fixture(val runtime: UkkeraRuntime, val session: ControlledAdministrationSession,
    val gateway: ControlledAdministration, val controller: LocalAdministrationController) {
    val token: NavigationToken get() = runtime.navigator.token()
}

private class ControlledAdministrationSession(override val administration: LocalAdministrationUiGateway) : BranchUiSession {
    private var active = true
    override val lifecycle = MutableStateFlow(LocalSessionLifecycle.ACTIVE)
    override val display = SessionDisplayContext(ACTOR, COMPANY, BRANCH, "Controlled actor", "Controlled company",
        "Controlled branch", setOf(AppArea.PEOPLE, AppArea.CASHIER))
    override fun isCurrent() = active
    override suspend fun current(): LocalSessionCheckResult = if (active) LocalSessionCheckResult.Current(display)
        else LocalSessionCheckResult.Unavailable(LocalServiceProblem.SESSION_ENDED)
    override suspend fun mayRestore(route: AppRoute) = false
    override fun close() { active = false }
}

private class ControlledAdministration : LocalAdministrationUiGateway {
    override val configured = true
    var readReply: LocalAdministrationReadResult = LocalAdministrationReadResult.Available(LocalAdministrationView(
        BranchScope(ORGANIZATION, COMPANY, BRANCH), LocalAdministrationDirectory.ACCOUNTS, DIGEST,
        emptyList(), emptyList(), null, null))
    var pendingReply: LocalAdministrationPendingResult = LocalAdministrationPendingResult.Available(SAVED)
    var lookupReply: LocalAdministrationResult = LocalAdministrationResult.Known(SAVED)
    val lookups = mutableListOf<DomainId>()
    var prepares = 0
    var submits = 0
    var acknowledgements = 0
    override suspend fun read(query: LocalAdministrationQuery) = readReply
    override suspend fun pending() = pendingReply
    override suspend fun prepare(command: LocalAdministrationCommand): LocalAdministrationResult {
        prepares++
        return LocalAdministrationResult.NotPrepared(LocalServiceProblem.DENIED)
    }
    override suspend fun submit(requestId: DomainId, newPassword: CharArray?): LocalAdministrationResult {
        newPassword?.fill('\u0000')
        submits++
        return LocalAdministrationResult.Unresolved(requestId, LocalServiceProblem.DENIED)
    }
    override suspend fun lookup(requestId: DomainId): LocalAdministrationResult {
        lookups += requestId
        return lookupReply
    }
    override suspend fun cancel(requestId: DomainId) = LocalAdministrationResult.Unresolved(requestId, LocalServiceProblem.DENIED)
    override suspend fun acknowledge(requestId: DomainId): Boolean { acknowledgements++; return false }
}

private fun id(n: Int) = DomainId("75000000-0000-4000-8000-${n.toString().padStart(12, '0')}")
private val ACTOR = id(1)
private val COMPANY = id(2)
private val BRANCH = id(3)
private val ORGANIZATION = id(4)
private val TARGET = id(5)
private val DIGEST = PayloadDigest("1".repeat(64))
private val REQUEST = LocalAdministrationCommand(id(6), LocalAdministrationAction.DISABLE_ACCOUNT, TARGET, Revision(1))
private val SAVED = LocalAdministrationPending(REQUEST, LocalAdministrationPhase.APPLIED, false,
    LocalAdministrationReceipt(REQUEST.requestId, REQUEST.action, TARGET, ACTOR, Revision(2), DIGEST))
