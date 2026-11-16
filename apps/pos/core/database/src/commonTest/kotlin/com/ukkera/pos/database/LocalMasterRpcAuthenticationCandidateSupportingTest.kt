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

/**
 * Self-authored, NOT independent. Supporting controlled schedules for unchanged frozen
 * lan-master-client-v1 LAN-A02/A08, authentication-current-renewal-v2 ACR2-S010 and the
 * qa/pos/designs/lan-master-client-v1/cases.json and
 * qa/pos/designs/authentication-current-renewal-v2/cases.md. Authority contracts:
 * qa/pos/contracts/local-rpc-authentication-exchange-v1.md and
 * qa/pos/contracts/local-rpc-authentication-current-v2.md.
 * Genuine SDK session objects and the production fence, with inert identity/account DATA.
 * The latches represent the principal-read interval, NOT real native verification, SQL,
 * admitted physical peers, private disclosure or whole frozen-case execution.
 */
class LocalMasterRpcAuthenticationCandidateSupportingTest {
    @Test fun unrelatedMasterMetadataWriteDuringPrincipalWorkPreservesCandidateWithoutRelaxingEpochAdmission() = runTest {
        fixture { f ->
            val epoch = f.retain()
            val finished = CompletableDeferred<Unit>()
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                assertTrue(f.prepare())
                finished.await()
                f.complete()
            }
            f.fence.beforeProtectedWrite(LocalMasterRpcSessionInvalidation.None)
            // Preserve the old strict epoch semantics; only the pre-staged census path differs.
            assertFalse(f.fence.attachAuthenticationExchangeResult(f.owner, epoch, f.session, f.device, f.identity))
            finished.complete(Unit)
            assertTrue(pending.await())
            assertTrue(f.session.isActive)
            assertEquals("device-exchange", f.session.principal)
            assertFalse(f.fence.retainApplicationSession(f.owner, epoch, f.session, f.identity))
            assertNull(f.fence.captureResourceSession(f.owner, f.session))
        }
    }

    @Test fun unrelatedAccountAndPolicyWriteDuringPrincipalWorkPreserveExactCandidate() = runTest {
        fixture { f ->
            f.retain(); assertTrue(f.prepare())
            f.fence.beforeProtectedWrite(LocalMasterRpcSessionInvalidation.Account(
                f.account(f.identity.copy(actorId = id(8)), SessionStatus.REVOKED), Revision(0)))
            f.fence.beforeProtectedWrite(LocalMasterRpcSessionInvalidation.Policy(f.identity.scope, id(8)))
            assertTrue(f.complete())
        }
    }

    @Test fun matchingSessionRevocationWinningPrincipalIntervalIrreversiblyDeniesCompletion() = revokedDuringVerification {
        LocalMasterRpcSessionInvalidation.Account(it.account(it.identity, SessionStatus.REVOKED), Revision(0))
    }
    @Test fun matchingEmployeeDisableWinningPrincipalIntervalIrreversiblyDeniesCompletion() = revokedDuringVerification {
        LocalMasterRpcSessionInvalidation.Account(it.account(it.identity, SessionStatus.REVOKED, false), Revision(0))
    }
    @Test fun matchingDeviceWithdrawalWinningPrincipalIntervalIrreversiblyDeniesCompletion() = revokedDuringVerification {
        LocalMasterRpcSessionInvalidation.Admission(LocalDeviceAdmission(it.identity.scope, it.identity.deviceId,
            it.identity.admissionRevision, false, null, null, emptySet()))
    }
    @Test fun matchingPolicyWriteWinningPrincipalIntervalIrreversiblyDeniesCompletion() = revokedDuringVerification {
        LocalMasterRpcSessionInvalidation.Policy(it.identity.scope, it.identity.actorId)
    }
    @Test fun unknownMutationWinningPrincipalIntervalIrreversiblyDeniesCompletion() = revokedDuringVerification {
        LocalMasterRpcSessionInvalidation.All
    }

    @Test fun candidateCannotAuthenticateRetargetOrCompleteWithoutPreparation() = runTest {
        fixture { f ->
            f.retain()
            assertFalse(f.complete())
            assertTrue(f.prepare())
            for (other in listOf(f.identity.copy(actorId = id(8)), f.identity.copy(sessionId = id(9)),
                f.identity.copy(generation = SessionGeneration(2)), f.identity.copy(admissionRevision = Revision(2)),
                f.identity.copy(purpose = AuthenticationPurpose.PRIVILEGED_APPROVAL),
                f.identity.copy(scope = f.identity.scope.copy(branchId = id(10))), f.identity.copy(deviceId = id(11)))) {
                assertFalse(f.fence.prepareAuthenticationExchangeResult(f.owner, f.session, f.device, other))
                assertFalse(f.fence.completeAuthenticationExchangeResult(f.owner, f.session, f.device, other))
            }
            assertTrue(f.complete())
        }
    }

    @Test fun sameDescriptorSdkObjectAndForeignOriginCannotAdoptCandidate() = runTest {
        fixture { f ->
            f.retain(); assertTrue(f.prepare())
            val twin = RpcApplicationSession(f.session.peer, f.session.principal, f.session.sessionId, 60.seconds)
            try {
                assertFalse(f.fence.prepareAuthenticationExchangeResult(f.owner, twin, f.device, f.identity))
                assertFalse(f.fence.completeAuthenticationExchangeResult(f.owner, twin, f.device, f.identity))
                assertFalse(f.fence.completeAuthenticationExchangeResult(Any(), f.session, f.device, f.identity))
                assertTrue(f.complete())
            } finally { twin.revoke() }
        }
    }

    @Test fun explicitRetirementAfterPreparationCannotBeRearmed() = runTest {
        fixture { f ->
            f.retain(); assertTrue(f.prepare())
            f.fence.deny()
            assertFalse(f.complete()); assertFalse(f.prepare())
            f.fence.awaitSessionRevocation()
            assertFalse(f.complete())
        }
    }

    @Test fun completedCandidateStillFencesPolicyRevocationBeforePrivateDisclosure() = runTest {
        fixture { f ->
            f.retain(); assertTrue(f.prepare()); assertTrue(f.complete())
            f.fence.beforeProtectedWrite(LocalMasterRpcSessionInvalidation.Policy(f.identity.scope, f.identity.actorId))
            assertFalse(f.session.isActive)
            assertFalse(f.complete()); assertFalse(f.prepare())
        }
    }

    @Test fun withdrawnDeviceBeforePreparationCannotStageOrCompleteCandidate() = runTest {
        fixture { f ->
            f.retain()
            f.fence.beforeProtectedWrite(LocalMasterRpcSessionInvalidation.Admission(LocalDeviceAdmission(
                f.identity.scope, f.identity.deviceId, f.identity.admissionRevision, false, null, null, emptySet())))
            assertFalse(f.prepare()); assertFalse(f.complete())
        }
    }

    private fun revokedDuringVerification(invalidation: (Fixture) -> LocalMasterRpcSessionInvalidation) = runTest {
        fixture { f ->
            f.retain()
            val release = CompletableDeferred<Unit>()
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                assertTrue(f.prepare())
                release.await()
                f.complete()
            }
            f.fence.beforeProtectedWrite(invalidation(f))
            release.complete(Unit)
            assertFalse(pending.await())
            assertFalse(f.session.isActive)
            assertFalse(f.prepare())
            assertFalse(f.complete())
        }
    }

    private class Fixture {
        val owner = Any()
        val fence = LocalMasterRpcSessionFence().also { it.bind(owner) }
        val identity = LocalMasterRpcSessionIdentity(BranchScope(id(1), id(2), id(3)), id(4), id(5), id(6),
            SessionGeneration(1), Revision(1), AuthenticationPurpose.INTERACTIVE)
        val device = LocalMasterRpcExchangeDeviceIdentity(identity.scope, identity.deviceId, identity.admissionRevision)
        val session = RpcApplicationSession(PeerFingerprint("p2f1-" + "a".repeat(52)), "device-exchange", id(7).value, 60.seconds)
        suspend fun retain(): LocalMasterRpcSessionEpoch = assertNotNull(fence.captureSessionEpoch(owner)).also {
            assertTrue(fence.retainAuthenticationExchangeSession(owner, it, session, device))
        }
        suspend fun prepare(): Boolean = fence.prepareAuthenticationExchangeResult(owner, session, device, identity)
        suspend fun complete(): Boolean = fence.completeAuthenticationExchangeResult(owner, session, device, identity)
        fun account(target: LocalMasterRpcSessionIdentity, status: SessionStatus, enabled: Boolean = true): ProtectedLocalAccount {
            val credentialRevision = Revision(0)
            val state = LocalAccountSessionState(target.scope, target.actorId, enabled, Revision(1), target.generation,
                listOf(LocalSessionRecord(target.sessionId, target.scope, target.actorId, target.deviceId,
                    target.generation, status, target.purpose)))
            val credential = PasswordHashRecord(CredentialBinding(target.scope, target.actorId, credentialRevision),
                PasswordHashParameters(iterations = 600_000), ProtectedPepperReference(id(12)), ByteArray(16), ByteArray(32))
            return ProtectedLocalAccount(state, credentialRevision, credential)
        }
    }
    private companion object {
        fun id(n: Int): DomainId = DomainId("00000000-0000-4000-8000-" + n.toString().padStart(12, '0'))
        suspend fun fixture(work: suspend (Fixture) -> Unit) {
            val f = Fixture()
            try { work(f) } finally { f.session.revoke() }
        }
    }
}
