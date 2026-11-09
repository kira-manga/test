package com.ukkera.pos.security

import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class PrincipalAndSessionTest {
    @Test
    fun onlyVerifiedTransportCredentialsIssuePrincipals(): Unit = runTest {
        val denied = TrustedPrincipalFactory(DenyAllPrincipalVerifier)
        assertNull(denied.authenticateLocal(LocalGatewayCredentials(id(8), credential())))
        assertNull(denied.authenticateCentral(CentralGatewayCredentials(credential())))
        assertNull(denied.authenticateDevice(DeviceGatewayCredentials(id(8))))
        assertNull(denied.authenticateService(ServiceGatewayCredentials(id(8), credential())))
        val wrong = FixtureVerifier(
            local = VerifiedLocalIdentity(id(4), branch, id(6), id(7), SessionGeneration(1), Revision(1), AuthenticationPurpose.SERVICE),
        )
        assertNull(TrustedPrincipalFactory(wrong).authenticateLocal(LocalGatewayCredentials(id(8), credential())))
    }

    @Test
    fun centralHumanDeviceAndServiceNeverInheritEmployeePowers(): Unit = runTest {
        val factory = TrustedPrincipalFactory(
            FixtureVerifier(
                central = VerifiedCentralIdentity(id(4), branch.company(), id(7), SessionGeneration(1)),
                device = VerifiedDeviceIdentity(id(6), branch, Revision(1)),
                service = VerifiedServiceIdentity(
                    id(4), id(6), branch, id(7), SessionGeneration(1), Revision(1), BranchServicePurpose.MASTER,
                ),
            ),
        )
        val central = checkNotNull(factory.authenticateCentral(CentralGatewayCredentials(credential())))
        val device = checkNotNull(factory.authenticateDevice(DeviceGatewayCredentials(id(8))))
        val service = checkNotNull(factory.authenticateService(ServiceGatewayCredentials(id(8), credential())))
        val required = requirement()
        for (actor in listOf(central, device, service)) {
            assertEquals(
                AuthorizationDecision.Denied(AuthorizationDenial.WRONG_PRINCIPAL_KIND),
                AuthorizationEvaluator.evaluate(actor, snapshot(actor, listOf(grant(required))), required),
            )
        }
        val company = requirement(SecurityAction.COMPANY_POLICY, PermissionVerb.ADMINISTER, scope = branch.company())
        assertIs<AuthorizationDecision.Allowed>(
            AuthorizationEvaluator.evaluate(central, snapshot(central, listOf(grant(company))), company),
        )
        val foreignCompany = company.copy(scope = branch.company().copy(companyId = id(42)))
        assertEquals(
            AuthorizationDecision.Denied(AuthorizationDenial.WRONG_SCOPE),
            AuthorizationEvaluator.evaluate(central, snapshot(central, listOf(grant(foreignCompany))), foreignCompany),
        )
        val humanApproval = requirement(SecurityAction.RECOVERY_ACTIVATION, PermissionVerb.APPROVE)
        assertEquals(
            AuthorizationDecision.Denied(AuthorizationDenial.WRONG_PRINCIPAL_KIND),
            AuthorizationEvaluator.evaluate(service, snapshot(service, listOf(grant(humanApproval))), humanApproval),
        )
    }

    @Test
    fun credentialContentsAreBoundedRedactedAndDisposable() {
        assertFailsWith<IllegalArgumentException> { OpaqueCredential(ByteArray(15)) }
        assertFailsWith<IllegalArgumentException> { OpaqueCredential(ByteArray(8193)) }
        val source = ByteArray(32) { 9 }
        val token = OpaqueCredential(source)
        source.fill(1)
        assertTrue(token.useBytes { it.all { value -> value == 9.toByte() } })
        var temporary: ByteArray? = null
        token.useBytes { temporary = it }
        assertTrue(checkNotNull(temporary).all { it == 0.toByte() })
        assertEquals("OpaqueCredential([REDACTED])", token.toString())
        token.destroy()
        assertFailsWith<IllegalStateException> { token.useBytes { it.size } }
    }

    @Test
    fun generationInvalidatesOldCallbacksAndForeignFenceInstances() {
        val fence = InteractiveSessionFence()
        val a = fence.capture()
        var published = 0
        assertTrue(fence.publishIfCurrent(a) { published++ })
        fence.invalidate()
        val b = fence.capture()
        assertFalse(fence.publishIfCurrent(a) { published++ })
        assertTrue(fence.publishIfCurrent(b) { published++ })
        assertFalse(InteractiveSessionFence(fence.generation).isCurrent(b))
        assertEquals(2, published)
    }

    @Test
    fun staleFirstFinancialReplyCannotRenderAsBOrRelabelPendingOriginalActor() {
        val fence = InteractiveSessionFence()
        val pending = PendingOperationOwner(op(10), branch, id(4), id(6), id(7))
        val before = pending.copy()
        val firstReplyTicket = fence.capture()
        fence.invalidate() // Synchronous logout before remote revocation/network completion.
        fence.invalidate() // B's root replaces A's root while A's first reply is delayed.
        var displayedActor = id(5)
        assertFalse(fence.publishIfCurrent(firstReplyTicket) { displayedActor = pending.originalActorId })
        assertEquals(id(5), displayedActor)
        assertEquals(before, pending)
        assertEquals(id(4), pending.originalActorId)
    }

    @Test
    fun exhaustedFenceLocksRatherThanWrappingToAnOldGeneration() {
        val fence = InteractiveSessionFence(SessionGeneration(Long.MAX_VALUE - 1))
        val old = fence.capture()
        assertEquals(SessionGeneration(Long.MAX_VALUE), fence.invalidate())
        assertFalse(fence.isCurrent(old))
        assertFailsWith<IllegalStateException> { fence.capture() }
        assertFailsWith<IllegalStateException> { SessionGeneration(Long.MAX_VALUE).next() }
    }

    @Test
    fun exhaustedRevocationForcesWholeAccountLockdownEvenForCompetingPartialRevokes() {
        val sessionA = LocalSessionRecord(
            id(7), branch, id(4), id(6), SessionGeneration(1), SessionStatus.ACTIVE, AuthenticationPurpose.INTERACTIVE,
        )
        val sessionB = sessionA.copy(sessionId = id(17), deviceId = id(16), generation = SessionGeneration(2))
        for ((revision, generation) in listOf(
            Revision(Long.MAX_VALUE) to SessionGeneration(2),
            Revision(2) to SessionGeneration(Long.MAX_VALUE),
            Revision(Long.MAX_VALUE - 1) to SessionGeneration(2),
            Revision(2) to SessionGeneration(Long.MAX_VALUE - 1),
        )) {
            val state = LocalAccountSessionState(branch, id(4), true, revision, generation, listOf(sessionA, sessionB))
            val first = assertIs<SessionTransition.Applied>(LocalSessions.revoke(state, id(7)))
            val competing = assertIs<SessionTransition.Applied>(LocalSessions.revoke(state, id(17)))
            assertFalse(first.state.enabled)
            assertFalse(competing.state.enabled)
            assertTrue(first.state.sessions.all { it.status == SessionStatus.REVOKED })
            assertEquals(first.state.sessions, competing.state.sessions)
            assertEquals(Revision(Long.MAX_VALUE), first.state.revision)
            assertEquals(SessionGeneration(Long.MAX_VALUE), first.state.generation)
            assertEquals(
                SessionTransition.Denied(SessionTransitionDenial.ACCOUNT_DISABLED),
                LocalSessions.signIn(first.state, id(27), id(26), explicitDeviceMove = true),
            )
        }
    }

    private fun emptyAccount(): LocalAccountSessionState =
        LocalAccountSessionState(branch, id(4), true, Revision(0), SessionGeneration(0), emptyList())

    @Test
    fun defaultOneSessionRequiresExplicitMoveAndPreservesOriginalPendingOwnership() {
        val first = assertIs<SessionTransition.Applied>(LocalSessions.signIn(emptyAccount(), id(7), id(6)))
        val pending = PendingOperationOwner(op(10), branch, id(4), id(6), id(7))
        assertEquals(
            SessionTransition.Denied(SessionTransitionDenial.EXPLICIT_DEVICE_MOVE_REQUIRED),
            LocalSessions.signIn(first.state, id(17), id(16)),
        )
        val move = assertIs<SessionTransition.Applied>(
            LocalSessions.signIn(first.state, id(17), id(16), explicitDeviceMove = true),
        )
        assertEquals(setOf(id(7)), move.revokedSessionIds)
        assertEquals(listOf(id(17)), move.state.sessions.filter { it.status == SessionStatus.ACTIVE }.map { it.sessionId })
        assertEquals(SessionGeneration(2), move.newSession?.generation)
        assertEquals(id(6), pending.originatingDeviceId)
        assertEquals(id(4), pending.originalActorId)
    }

    @Test
    fun optionalConcurrentSessionsKeepDistinctStampsAndMoveRevokesAll() {
        val policy = LocalSessionPolicy(2)
        val first = assertIs<SessionTransition.Applied>(LocalSessions.signIn(emptyAccount(), id(7), id(6), policy))
        val second = assertIs<SessionTransition.Applied>(LocalSessions.signIn(first.state, id(17), id(16), policy))
        assertEquals(listOf(SessionGeneration(1), SessionGeneration(2)), second.state.sessions.map { it.generation })
        assertEquals(
            SessionTransition.Denied(SessionTransitionDenial.SESSION_LIMIT),
            LocalSessions.signIn(second.state, id(27), id(26), policy),
        )
        val moved = assertIs<SessionTransition.Applied>(
            LocalSessions.signIn(second.state, id(27), id(26), policy, explicitDeviceMove = true),
        )
        assertEquals(setOf(id(7), id(17)), moved.revokedSessionIds)
        assertEquals(1, moved.state.sessions.count { it.status == SessionStatus.ACTIVE })
    }

    @Test
    fun localDisableRevokesImmediatelyAndNoSignInRestoresTheAccount() {
        val first = assertIs<SessionTransition.Applied>(LocalSessions.signIn(emptyAccount(), id(7), id(6)))
        val disabled = assertIs<SessionTransition.Applied>(LocalSessions.disable(first.state))
        assertFalse(disabled.state.enabled)
        assertEquals(SessionStatus.REVOKED, disabled.state.sessions.single().status)
        assertEquals(setOf(id(7)), disabled.revokedSessionIds)
        assertEquals(
            SessionTransition.Denied(SessionTransitionDenial.ACCOUNT_DISABLED),
            LocalSessions.signIn(disabled.state, id(17), id(16), explicitDeviceMove = true),
        )
        val exhausted = LocalAccountSessionState(branch, id(4), true, Revision(0), SessionGeneration(Long.MAX_VALUE), emptyList())
        assertEquals(
            SessionTransition.Denied(SessionTransitionDenial.GENERATION_EXHAUSTED),
            LocalSessions.signIn(exhausted, id(7), id(6)),
        )
    }

    @Test
    fun logoutDoesNotTerminateIndependentServiceIdentity(): Unit = runTest {
        val first = assertIs<SessionTransition.Applied>(LocalSessions.signIn(emptyAccount(), id(7), id(6)))
        val revoked = assertIs<SessionTransition.Applied>(LocalSessions.revoke(first.state, id(7)))
        assertEquals(SessionStatus.REVOKED, revoked.state.sessions.single().status)
        val verifier = FixtureVerifier(
            service = VerifiedServiceIdentity(id(80), id(6), branch, id(81), SessionGeneration(1), Revision(1), BranchServicePurpose.MASTER),
        )
        val service = checkNotNull(
            TrustedPrincipalFactory(verifier).authenticateService(ServiceGatewayCredentials(id(8), credential())),
        )
        val required = requirement(SecurityAction.MASTER_COMMIT)
        assertIs<AuthorizationDecision.Allowed>(
            AuthorizationEvaluator.evaluate(service, snapshot(service, listOf(grant(required))), required),
        )
    }
}
