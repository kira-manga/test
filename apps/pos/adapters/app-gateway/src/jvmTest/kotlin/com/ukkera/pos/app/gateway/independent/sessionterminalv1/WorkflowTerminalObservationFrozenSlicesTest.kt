package com.ukkera.pos.app.gateway.independent.sessionterminalv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.LocalClientIdentity
import com.ukkera.pos.app.gateway.independent.sessionpresentationv1.ControlledPolicyInput
import com.ukkera.pos.app.gateway.independent.sessionpresentationv1.ControlledSessionClient
import com.ukkera.pos.app.gateway.independent.sessionpresentationv1.SessionGatewayFixture
import com.ukkera.pos.database.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.*

/**
 * Implementer wiring of unchanged STC-S03/S10/S11/S12/S19 observables. Real branch/workflow
 * adapters, attachment ownership and encrypted JVM journals; controlled Master current/refusal
 * replies. No native identity, accepting business service, money or full frozen-case claim.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkflowTerminalObservationFrozenSlicesTest {
    @Test fun stcS10_secondCurrentDenialRetiresTheExactRootWithoutANotification() = runTest {
        val freeze = FrozenTerminalCleanupV1.load()
        val message = freeze.case("STC-S10") + freeze.case("STC-S19")
        val sample = WorkflowObservationSample()
        val fixture = fixture(freeze, sample)
        try {
            val session = fixture.signIn()
            val previous = session.workflows
            val retained = fixture.observeJournals()
            sample.arm(LocalSessionViewResult.Denied)
            assertEquals(LocalServiceProblem.SESSION_ENDED,
                assertIs<LocalReadResult.Unavailable>(previous.read(query(fixture))).problem, message)
            assertEquals(2, sample.armedObservations, "$message: acquire passes; the adapter observes the terminal denial")
            assertFalse(session.isCurrent(), message)
            assertEquals(LocalSessionLifecycle.REAUTHENTICATION_REQUIRED, session.lifecycle?.value, message)
            repeat(2) { previous.read(query(fixture)) }
            assertTrue(fixture.client.protectedCalls.isEmpty(), message)
            assertEquals(retained, fixture.observeJournals(), message)
            fixture.retireAndReopenJournals()
            assertEquals(retained, fixture.observeJournals(), message)
        } finally { fixture.close() }
    }

    @Test fun stcS10_temporarySecondObservationIsNotAnIdentityDenial() = runTest {
        val freeze = FrozenTerminalCleanupV1.load()
        val message = freeze.case("STC-S10")
        for ((reply, problem) in listOf(LocalSessionViewResult.Busy to LocalServiceProblem.RETRY_LATER,
            LocalSessionViewResult.Unavailable to LocalServiceProblem.UNAVAILABLE)) {
            val sample = WorkflowObservationSample()
            val fixture = fixture(freeze, sample)
            try {
                val session = fixture.signIn()
                val retained = fixture.observeJournals()
                sample.arm(reply)
                assertEquals(problem, assertIs<LocalReadResult.Unavailable>(session.workflows.read(query(fixture))).problem, message)
                assertTrue(session.isCurrent(), message)
                assertEquals(LocalSessionLifecycle.ACTIVE, session.lifecycle?.value, message)
                assertTrue(fixture.client.protectedCalls.isEmpty(), message)
                assertEquals(retained, fixture.observeJournals(), message)
            } finally { fixture.close() }
        }
    }

    @Test fun stcS10_scopedBusinessRefusalDoesNotRevokeUnrelatedRights() = runTest {
        val freeze = FrozenTerminalCleanupV1.load()
        val message = freeze.case("STC-S10")
        val fixture = fixture(freeze, WorkflowObservationSample())
        try {
            val session = fixture.signIn()
            val retained = fixture.observeJournals()
            fixture.client.refuseNextRead(ReadFailure.AUTHORIZATION_DENIED)
            assertEquals(LocalServiceProblem.DENIED,
                assertIs<LocalReadResult.Unavailable>(session.workflows.read(query(fixture))).problem, message)
            assertTrue(session.isCurrent(), message)
            assertEquals(LocalSessionLifecycle.ACTIVE, session.lifecycle?.value, message)
            assertEquals(1, fixture.client.protectedCalls.size, message)
            assertEquals(retained, fixture.observeJournals(), message)
        } finally { fixture.close() }
    }

    @Test fun stcS10S12_failedNegativeHolderStillFencesTheDeniedRootAndStaysRetryable() = runTest {
        val freeze = FrozenTerminalCleanupV1.load()
        val message = freeze.case("STC-S10") + freeze.case("STC-S12")
        val sample = WorkflowObservationSample()
        val fixture = fixture(freeze, sample)
        val failed = TerminalGatewayDenialProbe(IllegalStateException("Synthetic required negative cleanup failure"))
        try {
            val session = fixture.signIn()
            val retained = fixture.observeJournals()
            assertNotNull(terminalNegativeLifetimes(session).session.attach(failed))
            sample.arm(LocalSessionViewResult.Denied)
            assertEquals(LocalServiceProblem.SESSION_ENDED,
                assertIs<LocalReadResult.Unavailable>(session.workflows.read(query(fixture))).problem, message)
            assertFalse(session.isCurrent(), message)
            assertEquals(LocalSessionLifecycle.REAUTHENTICATION_REQUIRED, session.lifecycle?.value, message)
            assertTrue(failed.attempts > 0, message)
            assertFalse(failed.released, message)
            assertTrue(fixture.client.protectedCalls.isEmpty(), message)
            assertEquals(retained, fixture.observeJournals(), message)
            failed.resolveFault()
            fixture.gateway.closeAndAwait()
            assertTrue(failed.released, message)
        } finally { failed.resolveFault(); fixture.close() }
    }

    @Test fun stcS03_oldAdapterCannotRetireTheSuccessorAttachment() = runTest {
        val freeze = FrozenTerminalCleanupV1.load()
        val message = freeze.case("STC-S03") + freeze.case("STC-S10")
        val sample = WorkflowObservationSample()
        val fixture = fixture(freeze, sample)
        try {
            val session = fixture.signIn()
            val previous = session.workflows
            fixture.client.replaceCurrentPolicyReplySample(ControlledPolicyInput.quantityOnly(Revision(1)))
            session.current()
            assertNotSame(previous, session.workflows, message)
            val retained = fixture.observeJournals()
            sample.arm(LocalSessionViewResult.Denied)
            repeat(2) {
                assertEquals(LocalServiceProblem.SESSION_ENDED,
                    assertIs<LocalReadResult.Unavailable>(previous.read(query(fixture))).problem, message)
            }
            assertEquals(0, sample.armedObservations, "$message: a retired adapter acquires no successor Current context")
            assertTrue(session.isCurrent(), message)
            assertEquals(LocalSessionLifecycle.ACTIVE, session.lifecycle?.value, message)
            assertTrue(fixture.client.protectedCalls.isEmpty(), message)
            assertEquals(retained, fixture.observeJournals(), message)
        } finally { fixture.close() }
    }

    @Test fun stcS11_cancelledPresentationCannotDiscardAnObservedTerminalDenial() = runTest {
        val freeze = FrozenTerminalCleanupV1.load()
        val message = freeze.case("STC-S10") + freeze.case("STC-S11")
        val sample = WorkflowObservationSample()
        val fixture = fixture(freeze, sample)
        try {
            val session = fixture.signIn()
            val retained = fixture.observeJournals()
            val held = sample.armHeld()
            val read = async { session.workflows.read(query(fixture)) }
            held.entered.await()
            read.cancel()
            held.reply.complete(LocalSessionViewResult.Denied)
            runCurrent()
            read.join()
            assertTrue(read.isCancelled, message)
            assertFalse(session.isCurrent(), message)
            assertEquals(LocalSessionLifecycle.REAUTHENTICATION_REQUIRED, session.lifecycle?.value, message)
            assertTrue(fixture.client.protectedCalls.isEmpty(), message)
            assertEquals(retained, fixture.observeJournals(), message)
        } finally { sample.releaseHeld(); fixture.close() }
    }

    private suspend fun TestScope.fixture(freeze: FrozenTerminalCleanupV1, sample: WorkflowObservationSample): SessionGatewayFixture =
        SessionGatewayFixture.create(StandardTestDispatcher(testScheduler),
            identitySample = LocalClientIdentity(BranchScope(freeze.id("organization_1"), freeze.id("retail_company"),
                freeze.id("retail_branch")), freeze.id("account_a"), freeze.id("shared_client_device")),
            initialSessionSample = freeze.id("session_a_original"), skuInputSample = freeze.retailProduct(),
            administrationSample = { delegate -> WorkflowObservationClient(delegate, sample) })

    private fun query(fixture: SessionGatewayFixture) = LocalWorkflowQuery(LocalWorkflow.INVENTORY,
        reference = LocalRecordReference(LocalRecordKind.SKU, fixture.skuSample))
}

private class WorkflowHeldObservation {
    val entered = CompletableDeferred<Unit>()
    val reply = CompletableDeferred<LocalSessionViewResult>()
}

private class WorkflowObservationSample {
    var armedObservations = 0
    private var remainingPasses: Int? = null
    private var reply: LocalSessionViewResult? = null
    private var held: WorkflowHeldObservation? = null
    fun arm(result: LocalSessionViewResult) { remainingPasses = 1; reply = result }
    fun armHeld(): WorkflowHeldObservation = WorkflowHeldObservation().also { held = it; remainingPasses = 1 }
    fun releaseHeld() { held?.reply?.complete(LocalSessionViewResult.Unavailable) }
    suspend fun observe(): LocalSessionViewResult? {
        val passes = remainingPasses ?: return null
        armedObservations++
        if (passes > 0) { remainingPasses = passes - 1; return null }
        remainingPasses = null
        val waiting = held
        if (waiting != null) {
            waiting.entered.complete(Unit)
            // A controlled non-cooperative native/transport completion, never a new authority.
            return withContext(NonCancellable) { waiting.reply.await() }
        }
        return checkNotNull(reply)
    }
}

private class WorkflowObservationClient(private val delegate: ControlledSessionClient,
    private val sample: WorkflowObservationSample) : LocalMasterClient by delegate, LocalMasterAdministrationClient {
    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult =
        sample.observe() ?: delegate.current(credentials)
    override suspend fun readAdministration(operator: LocalGatewayCredentials, query: LocalAdministrationQuery): LocalAdministrationReadResult =
        error("No administrative read is wired by this harness")
    override suspend fun administer(operator: LocalGatewayCredentials, command: LocalAdministrationCommand,
        newPassword: CharArray?): LocalCredentialAdministrationResult = error("No administrative mutation is wired by this harness")
    override suspend fun lookupAdministration(operator: LocalGatewayCredentials, command: LocalAdministrationCommand): LocalAdministrationStatus =
        error("No administrative original lookup is wired by this harness")
    override suspend fun cancelAdministration(operator: LocalGatewayCredentials, command: LocalAdministrationCommand): LocalAdministrationStatus =
        error("No administrative cancellation is wired by this harness")
}
