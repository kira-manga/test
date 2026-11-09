package com.ukkera.pos.app.gateway.independent.sessionnoaccessv1

import com.ukkera.pos.app.LocalReadResult
import com.ukkera.pos.app.LocalInventorySection
import com.ukkera.pos.app.LocalRecordKind
import com.ukkera.pos.app.LocalRecordReference
import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.app.LocalSessionCheckResult
import com.ukkera.pos.app.LocalSessionEndResult
import com.ukkera.pos.app.LocalSessionLifecycle
import com.ukkera.pos.app.LocalWorkflow
import com.ukkera.pos.app.LocalWorkflowQuery
import com.ukkera.pos.app.SessionDisplayContext
import com.ukkera.pos.app.gateway.StoredSessionPhaseV1
import com.ukkera.pos.app.gateway.independent.sessionpresentationv1.SessionGatewayFixture
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.database.ReadFailure
import com.ukkera.pos.inventory.store.LocalStockStoreProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Assertion slices of frozen LSNA v1.0.0. Actual shared client adapters and encrypted JVM journals
 * receive controlled Current/refusal replies with the frozen A identity. No actual master policy
 * activation, authenticated RPC, positive zero-grant admission/renewal or protected projection is
 * supplied. OM-01 and OM-02 remain unresolved; all six whole cases remain NOT_RUN.
 *
 * Journal observations concern existing-session metadata only. The intent journal is empty:
 * no original payment body/digest/attestation/effect, master ledger, process restart or LSNA-06
 * monetary-preservation qualification is claimed by closing/reopening these readers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionGatewayFrozenNoAccessSlicesTest {
    @Test
    fun lsna01Steps3To5_existingCurrentWithNoAreasKeepsEmployeeAndJournalIdentity() = runTest {
        val freeze = FrozenLocalSessionNoAccessV1.load()
        val status = freeze.step("LSNA-01", 4)
        val boundary = freeze.step("LSNA-01", 5)
        val fixture = fixture(freeze, StandardTestDispatcher(testScheduler))
        try {
            val session = fixture.signIn()
            val beforeDisplay = session.display
            val before = fixture.observeJournals()
            check(before.sessions.single().identity == freeze.employeeIdentity())
            check(before.sessions.single().sessionId == freeze.id("session_a"))
            check(before.pendingOperationIds.isEmpty())
            val signInsBefore = fixture.client.signInCalls.get()

            // An observation of the already-valid session is fixed. This controlled Current
            // does not settle the public zero-grant status/renewal/admission mapping OM-01.
            fixture.client.replaceCurrentPolicyReplySample(freeze.projectionSample("P11"))
            val current = assertIs<LocalSessionCheckResult.Current>(session.current(),
                status.message("the existing Current is distinct from an authentication failure"))
            assertSameEmployee(freeze, beforeDisplay, current.display, status)
            assertTrue(current.display.allowedAreas.isEmpty(), status.message("no operational hint or fallback grant"))
            assertFalse(AppArea.SESSION in current.display.allowedAreas,
                status.message("the session-control presentation is not an operational grant"))
            assertTrue(session.isCurrent(), status.message("the existing local capability remains current"))
            assertEquals(LocalSessionLifecycle.ACTIVE, session.lifecycle?.value,
                status.message("permission loss has not been classified as local logout"))
            runCurrent()
            assertEquals(signInsBefore, fixture.client.signInCalls.get(), boundary.message("no implicit login"))
            assertEquals(0, fixture.client.refreshCalls.get(), boundary.message("no automatic renewal"))
            assertEquals(0, fixture.client.protectedCalls.size, boundary.message("zero initiated protected business calls"))
            val observed = fixture.observeJournals()
            assertEquals(before.sessions, observed.sessions,
                boundary.message("the retained A session is not revoked or relabelled by the observation"))
            assertEquals(before.pendingOperationIds, observed.pendingOperationIds,
                boundary.message("the observation creates no new business intent"))

            fixture.retireAndReopenJournals()
            val reopened = fixture.observeJournals()
            assertEquals(observed.sessions, reopened.sessions,
                boundary.message("fresh encrypted readers retain the same existing-session metadata"))
            assertEquals(observed.pendingOperationIds, reopened.pendingOperationIds,
                boundary.message("fresh encrypted readers retain the same intent identities"))
        } finally { fixture.close() }
    }

    @Test
    fun lsna02Step4_retiredAdaptersCannotStartProtectedReads() = runTest {
        val freeze = FrozenLocalSessionNoAccessV1.load()
        val retirement = freeze.step("LSNA-02", 4)
        val fixture = fixture(freeze, StandardTestDispatcher(testScheduler))
        try {
            val session = fixture.signIn()
            val retired = session.workflows
            fixture.client.replaceCurrentPolicyReplySample(freeze.projectionSample("P11"))
            assertIs<LocalSessionCheckResult.Current>(session.current())
            val before = fixture.observeJournals()

            assertIs<LocalReadResult.Unavailable>(retired.read(quantityQuery(fixture)))
            assertIs<LocalReadResult.Unavailable>(retired.read(costQuery(fixture)))
            assertEquals(0, fixture.client.protectedCalls.size,
                retirement.message("retained old quantity/cost adapters initiate no protected read"))
            assertTrue(session.isCurrent(), retirement.message("old adapter attempts do not alter the valid replacement"))
            assertTrue(session.display.allowedAreas.isEmpty(), retirement.message("no former area is restored"))
            val after = fixture.observeJournals()
            assertEquals(before.sessions, after.sessions, retirement.message("old read attempts preserve A's session identity"))
            assertEquals(before.pendingOperationIds, after.pendingOperationIds,
                retirement.message("old read attempts create no business intent"))
        } finally { fixture.close() }
    }

    @Test
    fun lsna03Step3_controlledPermissionRefusalDoesNotBecomeAuthenticationFailure() = runTest {
        val freeze = FrozenLocalSessionNoAccessV1.load()
        val classification = freeze.step("LSNA-03", 3)
        val fixture = fixture(freeze, StandardTestDispatcher(testScheduler))
        try {
            val session = fixture.signIn()
            fixture.client.replaceCurrentPolicyReplySample(freeze.projectionSample("P11"))
            assertIs<LocalSessionCheckResult.Current>(session.current())
            val before = fixture.observeJournals()
            // Only client result classification is qualified. This scheduled public refusal
            // is not evidence of current authorization at a real same-device or RPC master.
            fixture.client.refuseNextRead(ReadFailure.AUTHORIZATION_DENIED)
            val denied = assertIs<LocalReadResult.Unavailable>(session.workflows.read(quantityQuery(fixture)))
            assertEquals(LocalServiceProblem.DENIED, denied.problem,
                classification.message("a protected permission refusal remains a permission outcome"))
            val call = fixture.client.protectedCalls.single()
            assertEquals(LocalStockStoreProfile.QUANTITIES, call.query,
                classification.message("the explicit request uses the existing registered quantity query"))
            assertEquals(freeze.id("session_a"), call.sessionLocator,
                classification.message("the explicit request retains A's existing session locator"))
            assertTrue(session.isCurrent(), classification.message("the refusal has not fabricated logout"))
            assertEquals(before.sessions, fixture.observeJournals().sessions,
                classification.message("permission classification does not revoke or relabel stored A metadata"))
        } finally { fixture.close() }
    }

    @Test
    fun lsna05Steps2To4_laterNarrowCurrentUsesFreshAdapterWithoutAutomaticWork() = runTest {
        val freeze = FrozenLocalSessionNoAccessV1.load()
        val noReentry = freeze.step("LSNA-05", 2)
        val regrant = freeze.step("LSNA-05", 3)
        val fixture = fixture(freeze, StandardTestDispatcher(testScheduler))
        try {
            val session = fixture.signIn()
            val p10Adapter = session.workflows
            val before = fixture.observeJournals()
            fixture.client.replaceCurrentPolicyReplySample(freeze.projectionSample("P11"))
            assertIs<LocalSessionCheckResult.Current>(session.current())
            val p11Adapter = session.workflows
            assertTrue(assertIs<LocalSessionCheckResult.Current>(session.current()).display.allowedAreas.isEmpty(),
                noReentry.message("another existing-session observation supplies no former area"))

            // Controlled reply data only; real P11-to-P12 activation and current protected
            // quantity/order projection remain assigned integrations, not inferred outcomes.
            fixture.client.replaceCurrentPolicyReplySample(freeze.projectionSample("P12"))
            val current = assertIs<LocalSessionCheckResult.Current>(session.current())
            assertEquals(setOf(AppArea.CASHIER, AppArea.ORDERS, AppArea.STOCK), current.display.allowedAreas,
                regrant.message("only selected own-order and quantity presentation hints are bound"))
            assertFalse(AppArea.REPORTS in current.display.allowedAreas,
                regrant.message("the cost/export report hint remains removed"))
            assertEquals(0, fixture.client.protectedCalls.size, regrant.message("no automatic protected work on regrant"))
            p10Adapter.read(quantityQuery(fixture))
            p11Adapter.read(quantityQuery(fixture))
            assertEquals(0, fixture.client.protectedCalls.size, regrant.message("retired adapters start no work"))

            // One explicit current quantity initiation is followed by a controlled refusal.
            // It does not qualify the required successful quantity-only response/schema.
            assertIs<LocalReadResult.Unavailable>(session.workflows.read(quantityQuery(fixture)))
            val call = fixture.client.protectedCalls.single()
            assertEquals(LocalStockStoreProfile.QUANTITIES, call.query, regrant.message("fresh explicit quantity initiation"))
            assertEquals(freeze.id("session_a"), call.sessionLocator, regrant.message("A remains the original valid session"))
            val after = fixture.observeJournals()
            assertEquals(before.sessions, after.sessions, regrant.message("A's retained original session is not revoked or relabelled"))
            assertEquals(before.pendingOperationIds, after.pendingOperationIds,
                regrant.message("the regrant sample creates no business intent"))
        } finally { fixture.close() }
    }

    @Test
    fun lsna04Step4_explicitUnavailableLogoutRetainsOriginalRevocationIntent() = runTest {
        val freeze = FrozenLocalSessionNoAccessV1.load()
        val logout = freeze.step("LSNA-04", 4)
        val fixture = fixture(freeze, StandardTestDispatcher(testScheduler))
        try {
            val session = fixture.signIn()
            fixture.client.replaceCurrentPolicyReplySample(freeze.projectionSample("P11"))
            assertIs<LocalSessionCheckResult.Current>(session.current())
            val original = fixture.observeJournals().sessions.single()
            // The existing fixture returns remote Unavailable. No real partition, reconnect
            // or revoked master-session result is fabricated from that controlled response.
            assertEquals(LocalSessionEndResult.UNRESOLVED, session.logout(),
                logout.message("unavailable remote logout is not reported as confirmed revocation"))
            assertFalse(session.isCurrent(), logout.message("explicit logout closes the local capability immediately"))
            assertNull(session.attachmentKey, logout.message("no interactive attachment is restored"))
            val after = fixture.observeJournals()
            assertEquals(original.copy(phase = StoredSessionPhaseV1.REVOCATION_PENDING), after.sessions.single(),
                logout.message("the original A identity is retained with its unresolved revocation intent"))
            assertEquals(0, fixture.client.protectedCalls.size, logout.message("revocation starts no business or recovery work"))
            fixture.retireAndReopenJournals()
            val reopened = fixture.observeJournals()
            assertEquals(after.sessions, reopened.sessions,
                logout.message("fresh encrypted readers retain that original revocation intent"))
            assertEquals(after.pendingOperationIds, reopened.pendingOperationIds,
                logout.message("fresh encrypted readers retain original business intent identities"))
        } finally { fixture.close() }
    }

    private suspend fun fixture(freeze: FrozenLocalSessionNoAccessV1,
        dispatcher: CoroutineDispatcher): SessionGatewayFixture = SessionGatewayFixture.create(
        dispatcher, freeze.projectionSample("P10"),
        setOf(LocalStockStoreProfile.QUANTITIES, LocalStockStoreProfile.COSTS),
        identitySample = freeze.employeeIdentity(), initialSessionSample = freeze.id("session_a"),
        skuInputSample = freeze.id("sku"),
    )

    private fun quantityQuery(fixture: SessionGatewayFixture): LocalWorkflowQuery = LocalWorkflowQuery(
        LocalWorkflow.INVENTORY, reference = LocalRecordReference(LocalRecordKind.SKU, fixture.skuSample),
    )

    private fun costQuery(fixture: SessionGatewayFixture): LocalWorkflowQuery = quantityQuery(fixture)
        .copy(inventory = LocalInventorySection.COSTS)

    private fun assertSameEmployee(freeze: FrozenLocalSessionNoAccessV1, before: SessionDisplayContext,
        after: SessionDisplayContext, oracle: FrozenNoAccessStep) {
        assertEquals(freeze.id("employee_a"), before.actorId, oracle.message("frozen employee A input"))
        assertEquals(before.actorId, after.actorId, oracle.message("employee identity is unchanged"))
        assertEquals(freeze.id("company"), after.companyId, oracle.message("company scope is unchanged"))
        assertEquals(freeze.id("branch"), after.branchId, oracle.message("branch scope is unchanged"))
    }
}
