package com.ukkera.pos.app.gateway.independent.sessionterminalv1

import com.ukkera.pos.app.LocalSessionLifecycle
import com.ukkera.pos.app.gateway.LocalClientIdentity
import com.ukkera.pos.app.gateway.independent.sessionpresentationv1.ControlledPolicyInput
import com.ukkera.pos.app.gateway.independent.sessionpresentationv1.ControlledSessionClient
import com.ukkera.pos.app.gateway.independent.sessionpresentationv1.SessionGatewayFixture
import com.ukkera.pos.database.LocalMasterAdministrationClient
import com.ukkera.pos.database.LocalMasterClient
import com.ukkera.pos.security.*
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Implementer wiring of existing STC-S03/S10/S12/S19 invariants, not independent design.
 * Branch/administration gateways, attachment fencing and encrypted journal IO are real.
 * Master authentication/current replies and the directory refusal are controlled DATA.
 * No accepting administrative read, mutation, native custody or financial original exists here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdministrationTerminalReadFrozenSlicesTest {
    @Test fun stcS10_currentDenialAfterDirectoryReadRetiresTheOriginalSession() = runTest {
        val freeze = FrozenTerminalCleanupV1.load()
        val message = freeze.case("STC-S10") + freeze.case("STC-S19")
        val sample = AdministrationSample()
        val fixture = fixture(freeze, sample)
        try {
            val session = fixture.signIn()
            val retained = fixture.observeJournals()
            sample.afterRead = LocalSessionViewResult.Denied
            assertEquals(LocalAdministrationReadResult.Denied, session.administration.read(query()), message)
            assertEquals(1, sample.directoryReads, message)
            assertFalse(session.isCurrent(), "$message: terminal identity scopes cannot remain interactive")
            assertEquals(LocalSessionLifecycle.REAUTHENTICATION_REQUIRED, session.lifecycle?.value, message)
            assertEquals(retained, fixture.observeJournals(), "$message: terminal presentation does not erase credential originals")
            assertTrue(fixture.client.protectedCalls.isEmpty(), message)
            fixture.retireAndReopenJournals()
            assertEquals(retained, fixture.observeJournals(), "$message: fresh encrypted readers retain the same original rows")
        } finally { fixture.close() }
    }

    @Test fun stcS10_scopedDirectoryDenialLeavesUnrelatedSessionRightsCurrent() = runTest {
        val freeze = FrozenTerminalCleanupV1.load()
        val message = freeze.case("STC-S10")
        val sample = AdministrationSample()
        val fixture = fixture(freeze, sample)
        try {
            val session = fixture.signIn()
            val retained = fixture.observeJournals()
            assertEquals(LocalAdministrationReadResult.Denied, session.administration.read(query()), message)
            assertTrue(session.isCurrent(), message)
            assertEquals(LocalSessionLifecycle.ACTIVE, session.lifecycle?.value, message)
            assertEquals(retained, fixture.observeJournals(), message)
            assertTrue(fixture.client.protectedCalls.isEmpty(), message)
        } finally { fixture.close() }
    }

    @Test fun stcS10_unavailableFinalCurrentObservationIsNotTerminalIdentityDenial() = runTest {
        val freeze = FrozenTerminalCleanupV1.load()
        val message = freeze.case("STC-S10")
        val sample = AdministrationSample()
        val fixture = fixture(freeze, sample)
        try {
            val session = fixture.signIn()
            val retained = fixture.observeJournals()
            sample.afterRead = LocalSessionViewResult.Unavailable
            assertEquals(LocalAdministrationReadResult.Unavailable, session.administration.read(query()), message)
            assertTrue(session.isCurrent(), message)
            assertEquals(LocalSessionLifecycle.ACTIVE, session.lifecycle?.value, message)
            assertEquals(retained, fixture.observeJournals(), message)
            assertTrue(fixture.client.protectedCalls.isEmpty(), message)
        } finally { fixture.close() }
    }

    @Test fun stcS10S12_failedNegativeHolderCannotLeaveTheDeniedSessionInteractive() = runTest {
        val freeze = FrozenTerminalCleanupV1.load()
        val message = freeze.case("STC-S10") + freeze.case("STC-S12")
        val sample = AdministrationSample()
        val fixture = fixture(freeze, sample)
        val failed = TerminalGatewayDenialProbe(IllegalStateException("Synthetic required negative cleanup failure"))
        try {
            val session = fixture.signIn()
            val retained = fixture.observeJournals()
            assertNotNull(terminalNegativeLifetimes(session).session.attach(failed))
            sample.afterRead = LocalSessionViewResult.Denied
            assertEquals(LocalAdministrationReadResult.Denied, session.administration.read(query()), message)
            assertFalse(session.isCurrent(), message)
            assertEquals(LocalSessionLifecycle.REAUTHENTICATION_REQUIRED, session.lifecycle?.value, message)
            assertTrue(failed.attempts > 0, "$message: actual required holder was attempted")
            assertFalse(failed.released, "$message: failure is not confirmed cleanup")
            assertEquals(retained, fixture.observeJournals(), message)
            failed.resolveFault()
            fixture.gateway.closeAndAwait()
            assertTrue(failed.released, "$message: the exact failed holder remains retryable")
        } finally { failed.resolveFault(); fixture.close() }
    }

    @Test fun stcS03_oldAdministrationAdapterCannotRetireTheSuccessorAttachment() = runTest {
        val freeze = FrozenTerminalCleanupV1.load()
        val message = freeze.case("STC-S03") + freeze.case("STC-S10")
        val sample = AdministrationSample()
        val fixture = fixture(freeze, sample)
        try {
            val session = fixture.signIn()
            val previous = session.administration
            fixture.client.replaceCurrentPolicyReplySample(policy(Revision(1)))
            session.current()
            assertTrue(session.isCurrent(), message)
            assertNotSame(previous, session.administration, message)
            val retained = fixture.observeJournals()
            repeat(2) { assertEquals(LocalAdministrationReadResult.Denied, previous.read(query()), message) }
            assertTrue(session.isCurrent(), message)
            assertEquals(LocalSessionLifecycle.ACTIVE, session.lifecycle?.value, message)
            assertEquals(0, sample.directoryReads, "$message: retired adapter cannot dispatch through the successor")
            assertEquals(LocalAdministrationReadResult.Denied, session.administration.read(query()), message)
            assertTrue(session.isCurrent(), message)
            assertEquals(1, sample.directoryReads, message)
            assertEquals(retained, fixture.observeJournals(), message)
            assertTrue(fixture.client.protectedCalls.isEmpty(), message)
        } finally { fixture.close() }
    }

    private suspend fun TestScope.fixture(freeze: FrozenTerminalCleanupV1, sample: AdministrationSample): SessionGatewayFixture =
        SessionGatewayFixture.create(StandardTestDispatcher(testScheduler), initialPolicySample = policy(Revision(0)),
            identitySample = LocalClientIdentity(BranchScope(freeze.id("organization_1"), freeze.id("retail_company"),
                freeze.id("retail_branch")), freeze.id("account_a"), freeze.id("shared_client_device")),
            initialSessionSample = freeze.id("session_a_original"), skuInputSample = freeze.retailProduct(),
            administrationSample = { delegate -> ControlledAdministrationClient(delegate, sample) })

    private fun policy(revision: Revision) = ControlledPolicyInput(revision, listOf(
        Permission(SecurityAction.LOCAL_ACCOUNT_ADMINISTRATION, PermissionVerb.ADMINISTER),
        Permission(SecurityAction.INVENTORY_QUANTITY_READ, PermissionVerb.PERFORM)))
    private fun query() = LocalAdministrationQuery(LocalAdministrationDirectory.ACCOUNTS)
}

/** Responses are refusal/currentness samples, never a protected production master or authority. */
private class AdministrationSample {
    var afterRead: LocalSessionViewResult? = null
    var nextCurrent: LocalSessionViewResult? = null
    var directoryReads = 0
}

private class ControlledAdministrationClient(private val delegate: ControlledSessionClient,
    private val sample: AdministrationSample) : LocalMasterClient by delegate, LocalMasterAdministrationClient {
    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult {
        sample.nextCurrent?.let { sample.nextCurrent = null; return it }
        return delegate.current(credentials)
    }
    override suspend fun readAdministration(operator: LocalGatewayCredentials, query: LocalAdministrationQuery): LocalAdministrationReadResult {
        sample.directoryReads++
        sample.nextCurrent = sample.afterRead
        return LocalAdministrationReadResult.Denied
    }
    override suspend fun administer(operator: LocalGatewayCredentials, command: LocalAdministrationCommand,
        newPassword: CharArray?): LocalCredentialAdministrationResult = error("No administrative mutation is wired by this harness")
    override suspend fun lookupAdministration(operator: LocalGatewayCredentials, command: LocalAdministrationCommand): LocalAdministrationStatus =
        error("No original-result lookup is wired by this harness")
    override suspend fun cancelAdministration(operator: LocalGatewayCredentials, command: LocalAdministrationCommand): LocalAdministrationStatus =
        error("No cancellation is wired by this harness")
}
