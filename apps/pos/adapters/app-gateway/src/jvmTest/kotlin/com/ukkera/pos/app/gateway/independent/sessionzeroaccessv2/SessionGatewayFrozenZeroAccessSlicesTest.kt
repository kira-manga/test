package com.ukkera.pos.app.gateway.independent.sessionzeroaccessv2

import com.ukkera.pos.app.BranchUiSession
import com.ukkera.pos.app.LocalSessionCheckResult
import com.ukkera.pos.app.LocalSessionEndResult
import com.ukkera.pos.app.LocalSessionLifecycle
import com.ukkera.pos.app.SignInFailure
import com.ukkera.pos.app.SignInResult
import com.ukkera.pos.app.gateway.StoredSessionPhaseV1
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.security.LocalSignOutResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Bounded LZA v2.0.0 gateway assertion slices, with exact frozen identity and semantic replies.
 * All eight whole cases remain NOT_RUN. Controlled replies establish only client reaction;
 * genuine M01/M02/A01 retain their authentication/SQLite/RPC/platform prerequisites.
 *
 * Missing assertions: exact lifecycle request IDs; F-SIGNED-OUT cached menu/quarantined UI;
 * complete F-PENDING body, historical references, physical evidence and business-effect cut;
 * delivered change hints; exact successful quantity response and quantity request binding;
 * original otherwise-valid order-create probe; detailed invalidation/storage-rollback codes;
 * installed sensitive UI teardown and actual Master lifetime. No substitute fixtures, positive
 * recovery grant or business success are supplied for these gaps. LO02 and LO03 have the same
 * public Unavailable reply binding, so only local closure and lack of confirmed revocation are
 * asserted for those variants; their distinct remote delivery claims remain unbound.
 * Frozen lost-refresh explicit local re-login is also BLOCKED: ordinary sign-in retains the
 * unresolved refresh marker before dispatch; this harness supplies no explicit device-move
 * consent. The F09 no-repeat/last-identity slice does not qualify that positive fallback.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionGatewayFrozenZeroAccessSlicesTest {
    @Test
    fun lzaC01_freshConfirmedZeroAccessKeepsIdentityStatusCleanupAndBlockedEntry() = runTest {
        val freeze = FrozenLocalSessionZeroAccessV2.load()
        val oracle = freeze.case("LZA-C01")
        val fixture = ZeroAccessGatewayFixture.create(freeze, StandardTestDispatcher(testScheduler))
        try {
            val session = assertIs<SignInResult.Authenticated<BranchUiSession>>(fixture.signIn(),
                oracle.message("session", "fresh authentic/current zero access is accepted")).session
            val current = assertIs<LocalSessionCheckResult.Current>(session.current(),
                oracle.message("session", "explicit status remains current"))
            assertEquals(freeze.id("employee"), current.display.actorId, oracle.message("session", "original employee"))
            assertEquals(freeze.id("company"), current.display.companyId, oracle.message("session", "original company"))
            assertEquals(freeze.id("branch"), current.display.branchId, oracle.message("session", "original branch"))
            assertTrue(current.display.allowedAreas.isEmpty(),
                oracle.message("operationalEntryAvailable", "no operational navigation grant"))
            assertTrue(session.isCurrent(), oracle.message("session", "zero grants do not invalidate the local session"))
            assertEquals(LocalSessionLifecycle.ACTIVE, session.lifecycle?.value,
                oracle.message("session", "no forced logout or authentication failure"))
            assertFalse(session.mayRestore(AppRoute.Cashier),
                oracle.message("oldProjectionGrants", "stale cashier route cannot enter"))
            assertFalse(session.mayRestore(AppRoute.Stock),
                oracle.message("oldProjectionGrants", "stale inventory route cannot enter"))
            assertTrue(fixture.gateway.cleanupStatus().available,
                oracle.message("controls", "the local cleanup status action remains available"))
            assertTrue(fixture.gateway.retryCleanup().available,
                oracle.message("controls", "the explicit local cleanup action remains available"))
            runCurrent()
            assertEquals(oracle.int("businessDispatches"), fixture.client.protectedCalls.size,
                oracle.message("businessDispatches", "status, blocked navigation and cleanup initiate no business call"))
            val beforeReopen = fixture.observeJournals().sessions.single()
            assertEquals(freeze.employeeIdentity(), beforeReopen.identity,
                oracle.message("session", "encrypted client state retains the exact employee/branch/device tuple"))
            assertEquals(freeze.id("controlledSession"), beforeReopen.sessionId,
                oracle.message("session", "encrypted client state retains the frozen controlled session"))
            fixture.retireAndReopenJournals()
            assertEquals(listOf(beforeReopen), fixture.observeJournals().sessions,
                oracle.message("session", "fresh encrypted readers retain the same accepted session metadata"))
        } finally { fixture.close() }
    }

    @Test
    fun lzaC03_explicitCurrentConfirmationEnablesOnlyQuantityNavigationWithoutAutomaticWork() = runTest {
        val freeze = FrozenLocalSessionZeroAccessV2.load()
        val oracle = freeze.case("LZA-C03")
        val fixture = ZeroAccessGatewayFixture.create(freeze, StandardTestDispatcher(testScheduler))
        var waiting: Deferred<LocalSessionCheckResult>? = null
        try {
            val session = fixture.attachZeroAccessInput()
            val original = fixture.observeJournals().sessions.single()
            // The unsupported change-hint action is not replaced by an inert harness method.
            // This slice starts at the frozen explicit current-check/confirmation action.
            val reply = fixture.client.holdCurrent(freeze.quantityOnlyPolicy())
            val currentWait = async { session.current() }.also { waiting = it }
            reply.captured.await()
            assertTrue(session.display.allowedAreas.isEmpty(),
                oracle.message("beforeCurrentConfirmation", "an undelivered current reply supplies no operational entry"))
            assertEquals(oracle.int("automaticBusinessSubmissions"), fixture.client.protectedCalls.size,
                oracle.message("automaticBusinessSubmissions", "waiting for confirmation initiates no business call"))
            reply.release()
            val current = assertIs<LocalSessionCheckResult.Current>(currentWait.await(),
                oracle.message("afterCurrentConfirmation", "the explicit current reply is adopted"))
            assertEquals(setOf(AppArea.STOCK), current.display.allowedAreas,
                oracle.message("afterCurrentConfirmation", "only the existing quantity-view navigation area is exposed"))
            assertEquals(freeze.id("employee"), current.display.actorId,
                oracle.message("afterCurrentConfirmation", "the current reply keeps the original employee"))
            assertEquals(freeze.id("company"), current.display.companyId,
                oracle.message("afterCurrentConfirmation", "the current reply keeps the original company"))
            assertEquals(freeze.id("branch"), current.display.branchId,
                oracle.message("afterCurrentConfirmation", "the current reply keeps the original branch"))
            assertTrue(session.mayRestore(AppRoute.Stock),
                oracle.message("afterCurrentConfirmation", "the confirmed quantity navigation may be restored"))
            assertFalse(session.mayRestore(AppRoute.Cashier),
                oracle.message("unauthorizedBusinessDispatches", "the unrelated cashier route remains blocked"))
            assertFalse(session.mayRestore(AppRoute.Order(freeze.id("order"))),
                oracle.message("unauthorizedBusinessDispatches", "the original order route supplies no recovery entry"))
            runCurrent()
            assertEquals(oracle.int("automaticBusinessSubmissions"), fixture.client.protectedCalls.size,
                oracle.message("automaticBusinessSubmissions", "regrant and navigation initiate no automatic business call"))
            assertEquals(listOf(original), fixture.observeJournals().sessions,
                oracle.message("afterCurrentConfirmation", "current-policy adoption keeps the exact original client session identity"))
            // exactAuthorizedRead, explicitAuthorizedReadCount == 1 and excludedData are unbound:
            // no successful Data constructor or different quantity/probe request is synthesized.
        } finally { waiting?.cancel(); fixture.close() }
    }

    @Test
    fun lzaC04F01_controlledAuthenticationRejectionDoesNotBecomeZeroAccessSuccess() = runTest {
        val freeze = FrozenLocalSessionZeroAccessV2.load()
        val row = freeze.failure("LZA-F01")
        val fixture = ZeroAccessGatewayFixture.create(freeze, StandardTestDispatcher(testScheduler),
            ZeroAccessAuthenticationReply.AUTHENTICATION_REJECTED)
        try {
            val result = assertIs<SignInResult.Rejected>(fixture.signIn(),
                row.message("semanticOutcome", "controlled verified-non-match reply is rejected"))
            assertEquals(SignInFailure.DENIED, result.reason,
                row.message("semanticOutcome", "authentication rejection remains distinct from zero-access success"))
            assertTrue(fixture.observeJournals().sessions.isEmpty(),
                row.message("currentSessionEstablished", "the client attaches no session from the denied reply"))
            runCurrent()
            assertEquals(freeze.businessEffectOracle().int("automaticBusinessSubmissions"), fixture.client.protectedCalls.size,
                row.message("zeroAccessSuccess", "the denial starts no business call"))
        } finally { fixture.close() }
    }

    @Test
    fun lzaC04F08_lostInitialReplyKeepsOriginalClientAttemptAndTruthfulUncertainty() = runTest {
        initialUncertaintySlice("LZA-F08", ZeroAccessAuthenticationReply.UNAVAILABLE,
            SignInFailure.AUTHENTICATION_UNRESOLVED)
    }

    @Test
    fun lzaC04F11_indeterminateInitialStorageKeepsOriginalClientAttemptWithoutSuccessOrDenial() = runTest {
        initialUncertaintySlice("LZA-F11", ZeroAccessAuthenticationReply.STORAGE_UNCERTAIN,
            SignInFailure.STORAGE_UNCERTAIN)
    }

    @Test
    fun lzaC04F09_unavailableRenewalKeepsOriginalIdentityAndUnresolvedMaintenanceWithoutResend() = runTest {
        val freeze = FrozenLocalSessionZeroAccessV2.load()
        val row = freeze.failure("LZA-F09")
        val fixture = ZeroAccessGatewayFixture.create(freeze, StandardTestDispatcher(testScheduler))
        var waiting: Deferred<LocalSessionCheckResult>? = null
        try {
            val session = fixture.attachZeroAccessInput()
            val original = fixture.observeJournals().sessions.single()
            val reply = fixture.client.holdRefreshFailure(ZeroAccessAuthenticationReply.UNAVAILABLE)
            val refreshWait = async { session.refresh() }.also { waiting = it }
            reply.captured.await()
            reply.release()
            assertIs<LocalSessionCheckResult.Unavailable>(refreshWait.await(),
                row.message("currentSessionEstablished", "unavailable renewal does not publish a newly Current session"))
            val retained = fixture.observeJournals().sessions.single()
            assertEquals(original.clientLocalId, retained.clientLocalId,
                row.message("preserveRenewalRequestIdentity", "the original client session-row identity is retained; fixed request ID remains unbound"))
            assertEquals(original.identity, retained.identity,
                row.message("semanticOutcome", "the last confirmed employee/branch/device identity is unchanged"))
            assertEquals(original.sessionId, retained.sessionId,
                row.message("semanticOutcome", "the last confirmed session locator is unchanged"))
            assertEquals(StoredSessionPhaseV1.REFRESH_PENDING, retained.phase,
                row.message("preserveRenewalRequestIdentity", "the original client renewal marker remains unresolved"))
            assertFalse(retained.originalRevoked,
                row.message("sessionRevocationIsConfirmed", "unavailable renewal writes no confirmed original-revocation marker"))
            assertTrue(fixture.gateway.cleanupStatus().authenticationUnresolved,
                row.message("semanticOutcome", "cleanup reports unresolved authentication maintenance"))
            runCurrent()
            assertEquals(1, fixture.client.refreshCalls.get(),
                row.message("preserveRenewalRequestIdentity", "only the single explicit renewal was dispatched"))
            assertEquals(row.int("automaticIndependentSessionCount"), fixture.client.signInCalls.get() - 1,
                row.message("automaticIndependentSessionCount", "no new sign-in is dispatched after uncertainty"))
            assertEquals(freeze.businessEffectOracle().int("automaticBusinessSubmissions"), fixture.client.protectedCalls.size,
                row.message("semanticOutcome", "no automatic business or recovery call is dispatched"))
            fixture.retireAndReopenJournals()
            assertEquals(listOf(retained), fixture.observeJournals().sessions,
                row.message("preserveRenewalRequestIdentity", "fresh encrypted readers retain the same unresolved original row"))
            // The outward SESSION_ENDED/reauthentication presentation has no separate unresolved
            // current-check code. Detailed diagnosis is not claimed from this retention slice.
        } finally { waiting?.cancel(); fixture.close() }
    }

    @Test
    fun lzaC05Lo01_reachableLogoutClosesLocallyBeforeControlledRevocationAndRejectsOldCallback() = runTest {
        logoutSlice("LZA-LO01", LocalSignOutResult.Revoked, LocalSessionEndResult.REVOKED)
    }

    @Test
    fun lzaC05Lo02_unavailableLogoutClosesLocallyAndRetainsUnconfirmedRevocation() = runTest {
        logoutSlice("LZA-LO02", LocalSignOutResult.Unavailable, LocalSessionEndResult.UNRESOLVED)
    }

    @Test
    fun lzaC05Lo03_lostLogoutReplyClosesLocallyAndRetainsUnconfirmedRevocation() = runTest {
        logoutSlice("LZA-LO03", LocalSignOutResult.Unavailable, LocalSessionEndResult.UNRESOLVED)
    }

    private suspend fun TestScope.initialUncertaintySlice(rowId: String, reply: ZeroAccessAuthenticationReply,
        expectedFailure: SignInFailure) {
        val freeze = FrozenLocalSessionZeroAccessV2.load()
        val row = freeze.failure(rowId)
        val case = freeze.case("LZA-C04")
        val fixture = ZeroAccessGatewayFixture.create(freeze, StandardTestDispatcher(testScheduler), reply)
        try {
            val result = assertIs<SignInResult.Rejected>(fixture.signIn(),
                row.message("semanticOutcome", "the controlled uncertain initial reply attaches no current session"))
            assertEquals(expectedFailure, result.reason,
                row.message("semanticOutcome", "uncertainty is neither authentication success nor credential rejection"))
            val original = fixture.observeJournals().attempts.single()
            assertTrue(fixture.observeJournals().sessions.isEmpty(),
                row.message("currentSessionEstablished", "the uncertain result creates no client session attachment"))
            assertTrue(fixture.gateway.cleanupStatus().authenticationUnresolved,
                row.message("semanticOutcome", "the original client attempt remains unresolved"))
            runCurrent()
            assertEquals(case.int("automaticNewCredentialAttemptsOrIndependentSessionsAfterUnknown"),
                fixture.client.signInCalls.get() - 1,
                case.message("automaticNewCredentialAttemptsOrIndependentSessionsAfterUnknown", "no automatic fresh credential attempt"))
            assertEquals(listOf(original), fixture.observeJournals().attempts,
                row.message("semanticOutcome", "the actual original client-local attempt identity is preserved"))
            assertEquals(freeze.businessEffectOracle().int("automaticBusinessSubmissions"), fixture.client.protectedCalls.size,
                row.message("semanticOutcome", "initial uncertainty starts no business call"))
            fixture.retireAndReopenJournals()
            assertEquals(listOf(original), fixture.observeJournals().attempts,
                row.message("semanticOutcome", "fresh encrypted readers retain the same original client-local attempt"))
            // This public adapter generates its own client-local ID. The fixed F-REQUESTS.login
            // UUID and server reservation/commit/rollback observations remain unbound.
        } finally { fixture.close() }
    }

    private suspend fun TestScope.logoutSlice(variantId: String, reply: LocalSignOutResult,
        expectedClientEnd: LocalSessionEndResult) {
        val freeze = FrozenLocalSessionZeroAccessV2.load()
        val oracle = freeze.case("LZA-C05")
        val variant = freeze.logout(variantId)
        val fixture = ZeroAccessGatewayFixture.create(freeze, StandardTestDispatcher(testScheduler))
        var waitingStatus: Deferred<LocalSessionCheckResult>? = null
        var waitingLogout: Deferred<LocalSessionEndResult>? = null
        try {
            val session = fixture.attachZeroAccessInput()
            val original = fixture.observeJournals().sessions.single()
            val oldReply = fixture.client.holdCurrent()
            val statusWait = async { session.current() }.also { waitingStatus = it }
            oldReply.captured.await()
            val remoteReply = fixture.client.holdLogout(reply)
            val logoutWait = async(start = CoroutineStart.UNDISPATCHED) { session.logout() }.also { waitingLogout = it }
            assertFalse(session.isCurrent(),
                oracle.message("localActiveAuthentication", "local capability is closed while the old callback and logout reply are held"))
            assertEquals(LocalSessionLifecycle.LOCKED, session.lifecycle?.value,
                oracle.message("localActiveAuthentication", "explicit local logout does not wait for acknowledgement"))
            assertNull(session.attachmentKey,
                oracle.message("localActiveAuthentication", "the old interactive attachment is cleared immediately"))
            oldReply.release()
            assertIs<LocalSessionCheckResult.Unavailable>(statusWait.await(),
                oracle.message("lateCallbackResurrection", "the delayed previous current reply cannot publish Current after logout"))
            remoteReply.captured.await()
            assertFalse(session.isCurrent(), oracle.message("lateCallbackResurrection", "the callback cannot reopen the local session"))
            assertNull(session.attachmentKey, oracle.message("lateCallbackResurrection", "no attachment is republished"))
            remoteReply.release()
            assertEquals(expectedClientEnd, logoutWait.await(),
                variant.message("expectedRemoteClaim", "only the selected controlled reply's client outcome is asserted"))
            assertEquals(listOf(freeze.id("controlledSession")), fixture.client.logoutLocators.toList(),
                oracle.message("retainedIntent", "logout uses the original session locator; fixed logout request ID is unbound"))
            assertFalse(session.mayRestore(AppRoute.Cashier),
                oracle.message("lateCallbackResurrection", "no business route can enter after local logout"))
            runCurrent()
            assertEquals(freeze.businessEffectOracle().int("automaticBusinessSubmissions"), fixture.client.protectedCalls.size,
                oracle.message("businessEffects", "logout and the old callback dispatch no business or recovery call"))
            val after = fixture.observeJournals().sessions
            if (reply == LocalSignOutResult.Revoked) {
                assertTrue(after.isEmpty(), variant.message("expectedRemoteClaim", "controlled confirmed revocation clears the client revocation row"))
            } else {
                assertEquals(listOf(original.copy(phase = StoredSessionPhaseV1.REVOCATION_PENDING)), after,
                    oracle.message("retainedIntent", "unconfirmed logout retains the exact original client identity and revocation intent"))
            }
            fixture.retireAndReopenJournals()
            assertEquals(after, fixture.observeJournals().sessions,
                oracle.message("retainedIntent", "fresh encrypted readers retain the selected client revocation outcome"))
        } finally { waitingStatus?.cancel(); waitingLogout?.cancel(); fixture.close() }
    }
}
