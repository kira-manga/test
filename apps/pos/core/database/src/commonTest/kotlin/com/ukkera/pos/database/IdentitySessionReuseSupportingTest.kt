package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import dev.p2pkit.core.PeerFingerprint
import dev.p2pkit.rpc.RpcApplicationSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/** Self-authored, NOT independent. Supporting the existing LAN-A02/A08 continuity and
 * current-authority requirements. Real SDK objects and production fence with inert account
 * DATA. A latch models the fresh-verification interval; this is not native auth or TCP.
 * An existing identity session may survive unrelated writes, never a relevant retirement.
 * A new identity/resource session must still satisfy the strict epoch admission contract. */
class IdentitySessionReuseSupportingTest {
    @Test fun unrelatedWriteDuringFreshCheckPreservesExistingSessionButCannotAdmitAnother() = runTest {
        fixture { f ->
            f.retain()
            val epoch = assertNotNull(f.fence.captureSessionEpoch(f.owner))
            val release = CompletableDeferred<Unit>()
            val checked = async(start = CoroutineStart.UNDISPATCHED) {
                release.await()
                f.current()
            }
            f.fence.beforeProtectedWrite(LocalMasterRpcSessionInvalidation.None)
            release.complete(Unit)
            assertTrue(checked.await())
            assertFalse(f.fence.retainApplicationSession(f.owner, epoch, f.session, f.identity))
            val successor = f.newSession()
            try {
                assertFalse(f.fence.existingIdentitySessionCurrent(f.owner, successor, f.identity))
                assertFalse(f.fence.retainResourceApplicationSession(f.owner, epoch, successor, f.identity))
                assertNull(f.fence.captureResourceSession(f.owner, f.session))
            } finally { successor.revoke() }
        }
    }

    @Test fun unretainedResourceAndAuthenticationExchangeObjectsCannotBecomeIdentitySessions() = runTest {
        fixture { f ->
            assertFalse(f.current())
            val epoch = assertNotNull(f.fence.captureSessionEpoch(f.owner))
            assertTrue(f.fence.retainResourceApplicationSession(f.owner, epoch, f.session, f.identity))
            assertFalse(f.current())
            assertNotNull(f.fence.captureResourceSession(f.owner, f.session))
            val exchange = RpcApplicationSession(f.session.peer, "exchange", id(20).value, 60.seconds)
            try {
                assertTrue(f.fence.retainAuthenticationExchangeSession(f.owner, epoch, exchange,
                    LocalMasterRpcExchangeDeviceIdentity(f.identity.scope, f.identity.deviceId, f.identity.admissionRevision)))
                assertFalse(f.fence.existingIdentitySessionCurrent(f.owner, exchange, f.identity))
            } finally { exchange.revoke() }
        }
    }

    @Test fun exactOriginalIdentityAndSdkObjectCannotBeRetargeted() = runTest {
        fixture { f ->
            f.retain()
            for (changed in listOf(f.identity.copy(actorId = id(8)), f.identity.copy(deviceId = id(9)),
                f.identity.copy(sessionId = id(10)), f.identity.copy(generation = SessionGeneration(2)),
                f.identity.copy(admissionRevision = Revision(2)),
                f.identity.copy(scope = f.identity.scope.copy(branchId = id(11))),
                f.identity.copy(purpose = AuthenticationPurpose.PRIVILEGED_APPROVAL))) {
                assertFalse(f.fence.existingIdentitySessionCurrent(f.owner, f.session, changed))
            }
            val twin = f.newSession()
            try {
                assertFalse(f.fence.existingIdentitySessionCurrent(f.owner, twin, f.identity))
                assertFalse(f.fence.existingIdentitySessionCurrent(Any(), f.session, f.identity))
                assertTrue(f.current())
            } finally { twin.revoke() }
        }
    }

    @Test fun matchingAccountCutWinningFreshCheckCannotBeRearmed() = revokedDuringCheck {
        LocalMasterRpcSessionInvalidation.Account(it.account(it.identity), Revision(0))
    }

    @Test fun matchingAdmissionCutWinningFreshCheckCannotBeRearmed() = revokedDuringCheck {
        LocalMasterRpcSessionInvalidation.Admission(LocalDeviceAdmission(it.identity.scope,
            it.identity.deviceId, it.identity.admissionRevision, false, null, null, emptySet()))
    }

    @Test fun unknownWriteWinningFreshCheckCannotBeRearmed() = revokedDuringCheck {
        LocalMasterRpcSessionInvalidation.All
    }

    @Test fun revokingOneEmployeePreservesTheOtherRetainedIdentity() = runTest {
        fixture { f ->
            f.retain()
            val other = f.identity.copy(actorId = id(8), sessionId = id(9))
            val second = f.newSession(other)
            try {
                assertTrue(f.fence.retainApplicationSession(f.owner,
                    assertNotNull(f.fence.captureSessionEpoch(f.owner)), second, other))
                f.fence.beforeProtectedWrite(LocalMasterRpcSessionInvalidation.Account(f.account(f.identity), Revision(0)))
                assertFalse(f.current())
                assertTrue(f.fence.existingIdentitySessionCurrent(f.owner, second, other))
                f.fence.beforeProtectedWrite(LocalMasterRpcSessionInvalidation.Policy(f.identity.scope, f.identity.actorId))
                assertTrue(f.fence.existingIdentitySessionCurrent(f.owner, second, other))
            } finally { second.revoke() }
        }
    }

    @Test fun sdkRevocationOrHostDenialCannotRestoreAnExistingSession() = runTest {
        fixture { f ->
            f.retain(); f.session.revoke(); assertFalse(f.current())
        }
        fixture { f ->
            f.retain(); f.fence.deny(); assertFalse(f.current())
            f.fence.awaitSessionRevocation(); assertFalse(f.current())
        }
    }

    @Test fun retainedResourceSurvivesUnrelatedWritesWhileNewEpochAdmissionStaysStrict() = runTest {
        fixture { f ->
            val epoch = assertNotNull(f.fence.captureSessionEpoch(f.owner))
            assertTrue(f.fence.retainResourceApplicationSession(f.owner, epoch, f.session, f.identity))
            val retained = assertNotNull(f.fence.captureResourceSession(f.owner, f.session))
            f.fence.beforeProtectedWrite(LocalMasterRpcSessionInvalidation.None)
            f.fence.beforeProtectedWrite(LocalMasterRpcSessionInvalidation.Account(
                f.account(f.identity.copy(actorId = id(21))), Revision(0)))
            assertTrue(retained.current())
            assertEquals(f.identity, assertNotNull(f.fence.captureResourceSession(f.owner, f.session)).identity)
            assertFalse(f.fence.retainResourceApplicationSession(f.owner, epoch, f.session, f.identity))
            assertFalse(f.current(), "A resource scope cannot become an identity scope")
        }
    }

    @Test fun retainedResourcePolicyCutCannotBeRearmedByAnUnrelatedWrite() = runTest {
        fixture { f ->
            assertTrue(f.fence.retainResourceApplicationSession(f.owner,
                assertNotNull(f.fence.captureSessionEpoch(f.owner)), f.session, f.identity))
            val retained = assertNotNull(f.fence.captureResourceSession(f.owner, f.session))
            f.fence.beforeProtectedWrite(LocalMasterRpcSessionInvalidation.Policy(f.identity.scope, f.identity.actorId))
            f.fence.beforeProtectedWrite(LocalMasterRpcSessionInvalidation.None)
            assertFalse(retained.current()); assertFalse(f.session.isActive)
            assertNull(f.fence.captureResourceSession(f.owner, f.session))
        }
    }

    @Test fun retainedResourceCannotBeBorrowedByAnotherOwnerOrSdkObject() = runTest {
        fixture { f ->
            assertTrue(f.fence.retainResourceApplicationSession(f.owner,
                assertNotNull(f.fence.captureSessionEpoch(f.owner)), f.session, f.identity))
            val twin = f.newSession()
            try {
                assertNull(f.fence.captureResourceSession(Any(), f.session))
                assertNull(f.fence.captureResourceSession(f.owner, twin))
                assertTrue(assertNotNull(f.fence.captureResourceSession(f.owner, f.session)).current())
            } finally { twin.revoke() }
        }
    }

    private fun revokedDuringCheck(invalidation: (Fixture) -> LocalMasterRpcSessionInvalidation) = runTest {
        fixture { f ->
            f.retain()
            val release = CompletableDeferred<Unit>()
            val checked = async(start = CoroutineStart.UNDISPATCHED) { release.await(); f.current() }
            f.fence.beforeProtectedWrite(invalidation(f)); release.complete(Unit)
            assertFalse(checked.await()); assertFalse(f.session.isActive); assertFalse(f.current())
        }
    }

    private class Fixture {
        val owner = Any()
        val fence = LocalMasterRpcSessionFence().also { it.bind(owner) }
        val identity = LocalMasterRpcSessionIdentity(BranchScope(id(1), id(2), id(3)), id(4), id(5), id(6),
            SessionGeneration(1), Revision(1), AuthenticationPurpose.INTERACTIVE)
        val session = newSession()
        fun newSession(value: LocalMasterRpcSessionIdentity = identity) = RpcApplicationSession(
            PeerFingerprint("p2f1-" + "a".repeat(52)), value.actorId.value, value.sessionId.value, 60.seconds)
        suspend fun retain() {
            assertTrue(fence.retainApplicationSession(owner, assertNotNull(fence.captureSessionEpoch(owner)), session, identity))
        }
        suspend fun current() = fence.existingIdentitySessionCurrent(owner, session, identity)
        fun account(value: LocalMasterRpcSessionIdentity): ProtectedLocalAccount {
            val revision = Revision(0)
            return ProtectedLocalAccount(LocalAccountSessionState(value.scope, value.actorId, false, Revision(1), value.generation,
                listOf(LocalSessionRecord(value.sessionId, value.scope, value.actorId, value.deviceId,
                    value.generation, SessionStatus.REVOKED, value.purpose))), revision,
                PasswordHashRecord(CredentialBinding(value.scope, value.actorId, revision), PasswordHashParameters(iterations = 600_000),
                    ProtectedPepperReference(id(12)), ByteArray(16), ByteArray(32)))
        }
    }
    private companion object {
        fun id(n: Int) = DomainId("00000000-0000-4000-8000-" + n.toString().padStart(12, '0'))
        suspend fun fixture(work: suspend (Fixture) -> Unit) {
            val f = Fixture()
            try { work(f) } finally { f.session.revoke() }
        }
    }
}
