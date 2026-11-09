package com.ukkera.pos.app.gateway.independent.sessionzeroaccessv2

import com.ukkera.pos.app.LocalSessionCheckResult
import com.ukkera.pos.app.LocalSessionLifecycle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Bounded LZA-C02 v2.0.0 assertion slice. The controlled reply uses the independently fixed
 * successor issued-session ID, both authored symbolic credential markers and the retained
 * original cutoff. The actual gateway and encrypted client readers observe those data.
 *
 * Same client-local row and scope observations do not establish authoritative work lineage,
 * unrelated-session counts, genuine both-token transactional rotation or Master authorization.
 * The UTC assertion is a controlled relative-time projection, not a native clock observation or
 * expiry-enforcement check. Revision 10 is supplied as input; the public UI result has no exact
 * policy-revision field. Fixed lifecycle request IDs, complete F-PENDING/effect cuts, installed
 * controls and all genuine M01 obligations remain BLOCKED/NOT_RUN. No whole-case pass is claimed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionGatewayFrozenZeroAccessRenewalSliceTest {
    @Test
    fun lzaC02_successfulZeroAccessRenewalObservesFrozenSuccessorCredentialsAndOriginalCutoff() = runTest {
        val freeze = FrozenLocalSessionZeroAccessV2.load()
        val oracle = freeze.case("LZA-C02")
        val renewal = freeze.renewalInput()
        val fixture = ZeroAccessGatewayFixture.create(freeze, StandardTestDispatcher(testScheduler))
        var waiting: Deferred<LocalSessionCheckResult>? = null
        try {
            val session = fixture.attachZeroAccessInput()
            val original = fixture.observeJournals().sessions.single()
            assertEquals(renewal.predecessorSessionId, original.sessionId,
                oracle.message("issuedIdentityRelation", "capture the frozen predecessor issued identity before refresh"))
            assertEquals(renewal.predecessorCredentials.access, original.accessCredential.symbolicLabel,
                oracle.message("bothCredentialObservationsRotated", "the actual encrypted predecessor access bytes match the authored before marker"))
            assertEquals(renewal.predecessorCredentials.refresh, original.refreshCredential.symbolicLabel,
                oracle.message("bothCredentialObservationsRotated", "the actual encrypted predecessor refresh bytes match the authored before marker"))
            assertEquals(oracle.text("originalWorkDeadlineUtc"), fixture.client.controlledDeadlineUtc(original.workExpiresAt),
                oracle.message("originalWorkDeadlineUtc", "the stored predecessor cutoff represents the frozen original controlled instant"))
            assertEquals(oracle.text("policyRevision"), renewal.policy.revision.value.toString(),
                oracle.message("policyRevision", "the controlled reply supplies the exact frozen revision; no outward revision observation is invented"))
            val reply = fixture.client.holdFrozenSuccessorRenewal()
            val refreshWait = async { session.refresh() }.also { waiting = it }
            reply.captured.await()
            reply.release()
            val renewed = assertIs<LocalSessionCheckResult.Current>(refreshWait.await(),
                oracle.message("status", "successful current zero-access renewal remains valid"))
            assertTrue(renewed.display.allowedAreas.isEmpty(),
                oracle.message("operationalGrants", "renewal adds no operational entry"))
            assertTrue(session.isCurrent(), oracle.message("forcedLoginFailure", "renewal does not force login failure"))
            assertEquals(LocalSessionLifecycle.ACTIVE, session.lifecycle?.value,
                oracle.message("status", "zero-access renewal leaves the local lifecycle active"))
            val after = fixture.observeJournals().sessions.single()
            assertEquals(oracle.text("issuedSessionId"), after.sessionId.value,
                oracle.message("issuedSessionId", "the actual encrypted client row contains the exact authored successor issued identity"))
            assertNotEquals(original.sessionId, after.sessionId,
                oracle.message("issuedIdentityRelation", "the captured successor differs from the predecessor; difference does not prove work lineage"))
            assertEquals(renewal.successorCredentials.access, after.accessCredential.symbolicLabel,
                oracle.message("bothCredentialObservationsRotated", "the actual encrypted successor access bytes match the authored after marker"))
            assertEquals(renewal.successorCredentials.refresh, after.refreshCredential.symbolicLabel,
                oracle.message("bothCredentialObservationsRotated", "the actual encrypted successor refresh bytes match the authored after marker"))
            assertEquals(after.sessionId, after.accessCredential.issuedSessionId,
                oracle.message("issuedSessionId", "the stored access locator names the fixed successor issued identity"))
            assertEquals(after.sessionId, after.refreshCredential.issuedSessionId,
                oracle.message("issuedSessionId", "the stored refresh locator names the fixed successor issued identity"))
            assertEquals(oracle.boolean("bothCredentialObservationsRotated"),
                original.accessCredential.symbolicLabel != after.accessCredential.symbolicLabel &&
                    original.refreshCredential.symbolicLabel != after.refreshCredential.symbolicLabel,
                oracle.message("bothCredentialObservationsRotated", "both observed synthetic credential markers changed"))
            assertEquals(original.workExpiresAt, after.workExpiresAt,
                oracle.message("originalWorkDeadlineUtc", "the actual stored boot coordinate and original work cutoff are retained exactly"))
            assertEquals(oracle.text("originalWorkDeadlineUtc"), fixture.client.controlledDeadlineUtc(after.workExpiresAt),
                oracle.message("originalWorkDeadlineUtc", "the successor cutoff represents the same frozen controlled instant"))
            assertEquals(oracle.boolean("workDeadlineExtended"), after.workExpiresAt.elapsedMillis > original.workExpiresAt.elapsedMillis,
                oracle.message("workDeadlineExtended", "the stored controlled work cutoff was not extended"))
            assertEquals(original.clientLocalId, after.clientLocalId,
                oracle.message("newIndependentSessions", "the existing client-local row is retained; this is no authoritative lineage or immutable-ID claim"))
            assertEquals(freeze.employeeIdentity(), after.identity,
                oracle.message("renewalContinuity", "the original controlled employee/branch/device tuple remains; scope equality alone is not lineage proof"))
            assertIs<LocalSessionCheckResult.Current>(session.current(),
                oracle.message("controls", "one explicit session-status action remains current"))
            assertEquals(ZeroAccessCredentialObservation(renewal.predecessorSessionId, "ULR1", renewal.predecessorCredentials.refresh),
                fixture.client.refreshCredentials.single(),
                oracle.message("issuedIdentityRelation", "the one refresh actually used the captured predecessor refresh marker and locator"))
            assertEquals(ZeroAccessCredentialObservation(renewal.successorSessionId, "ULA1", renewal.successorCredentials.access),
                fixture.client.currentCredentials.last(),
                oracle.message("bothCredentialObservationsRotated", "the explicit status check actually uses the stored successor access marker and locator"))
            runCurrent()
            assertEquals(1, fixture.client.refreshCalls.get(),
                oracle.message("automaticBusinessSubmissions", "only the single explicit refresh was dispatched"))
            assertEquals(oracle.int("newIndependentSessions"), fixture.client.signInCalls.get() - 1,
                oracle.message("newIndependentSessions", "renewal dispatches no independent new client sign-in"))
            assertEquals(oracle.int("automaticBusinessSubmissions"), fixture.client.protectedCalls.size,
                oracle.message("automaticBusinessSubmissions", "renewal/status dispatch no automatic business work"))
            fixture.retireAndReopenJournals()
            assertEquals(listOf(after), fixture.observeJournals().sessions,
                oracle.message("issuedSessionId", "fresh production encrypted readers retain successor metadata, both synthetic markers and the original controlled cutoff"))
        } finally { waiting?.cancel(); fixture.close() }
    }
}
