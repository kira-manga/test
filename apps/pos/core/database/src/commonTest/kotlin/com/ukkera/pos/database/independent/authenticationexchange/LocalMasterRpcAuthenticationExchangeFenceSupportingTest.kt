package com.ukkera.pos.database.independent.authenticationexchange

import com.ukkera.pos.database.LocalMasterRpcExchangeDeviceIdentity
import com.ukkera.pos.database.LocalMasterRpcSessionEpoch
import com.ukkera.pos.database.LocalMasterRpcSessionFence
import com.ukkera.pos.database.LocalMasterRpcSessionIdentity
import com.ukkera.pos.database.LocalMasterRpcSessionInvalidation
import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.CredentialBinding
import com.ukkera.pos.security.LocalAccountSessionState
import com.ukkera.pos.security.LocalDeviceAdmission
import com.ukkera.pos.security.LocalSessionRecord
import com.ukkera.pos.security.PasswordHashParameters
import com.ukkera.pos.security.PasswordHashRecord
import com.ukkera.pos.security.ProtectedLocalAccount
import com.ukkera.pos.security.ProtectedPepperReference
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.security.SessionStatus
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import dev.p2pkit.core.PeerFingerprint
import dev.p2pkit.rpc.RpcApplicationSession
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Portable supporting slices of remote-employee-authentication-v1 REMP-C05/C06/C07.
 * The actual production fence and public official SDK application-session objects are used.
 * Referential owner markers, device/result metadata and canonical descriptors are controlled
 * DATA: no native admission, authentication service, physical callback, SQLite transaction,
 * private output commitment/receipt, business grant or whole frozen case is established.
 */
class LocalMasterRpcAuthenticationExchangeFenceSupportingTest {
    @Test
    fun rempC05ExactDeviceExchangeRetentionNeverBecomesEmployeeSessionMetadata() = runTest(timeout = 5.seconds) {
        withFence { f ->
            val epoch = f.retainOriginal()
            assertTrue(f.fence.retainAuthenticationExchangeSession(f.owner, epoch, f.original, f.device))
            assertFalse(f.fence.retainApplicationSession(f.owner, epoch, f.original, f.identity))
            assertTrue(f.fence.attachAuthenticationExchangeResult(f.owner, epoch, f.original, f.device, f.identity))
            assertFalse(f.fence.retainApplicationSession(f.owner, epoch, f.original, f.identity))
            assertEquals("controlled-device-authentication-exchange", f.original.principal)
            assertEquals(FenceFrozenInputs.originalExchangeId, f.original.sessionId)
        }
        // Successful attachment is only a revocation census entry, not employee/business authority.
    }

    @Test
    fun rempC05ForeignOriginOrForeignFenceEpochCannotAdoptDeviceExchange() = runTest(timeout = 5.seconds) {
        withFence { f ->
            val epoch = f.retainOriginal()
            val foreignOwner = Any()
            val foreignFence = LocalMasterRpcSessionFence().also { it.bind(foreignOwner) }
            val foreignEpoch = assertNotNull(foreignFence.captureSessionEpoch(foreignOwner))
            assertNull(f.fence.captureSessionEpoch(foreignOwner))
            assertFalse(f.fence.retainAuthenticationExchangeSession(foreignOwner, epoch, f.successor, f.device))
            assertFalse(f.fence.attachAuthenticationExchangeResult(foreignOwner, epoch, f.original, f.device, f.identity))
            assertFalse(f.fence.attachAuthenticationExchangeResult(f.owner, foreignEpoch, f.original, f.device, f.identity))
            assertTrue(f.original.isActive)
            assertTrue(f.successor.isActive)
        }
    }

    @Test fun rempC05DifferentDeviceCannotAttachPrivateResult() = refusedInitialIdentity {
        it.copy(deviceId = FenceFrozenInputs.otherDeviceId)
    }
    @Test fun rempC05DifferentBranchCannotAttachPrivateResult() = refusedInitialIdentity {
        it.copy(scope = it.scope.copy(branchId = FenceFrozenInputs.otherBranchId))
    }
    @Test fun rempC05DifferentAdmissionRevisionCannotAttachPrivateResult() = refusedInitialIdentity {
        it.copy(admissionRevision = Revision(it.admissionRevision.value + 1))
    }
    @Test fun rempC05WrongPurposeCannotAttachPrivateResult() = refusedInitialIdentity {
        it.copy(purpose = AuthenticationPurpose.PRIVILEGED_APPROVAL)
    }

    @Test fun rempC05DifferentEmployeeCannotReplaceAttachedOriginal() = refusedReplacement {
        it.copy(actorId = FenceFrozenInputs.otherActorId)
    }
    @Test fun rempC05DifferentGenerationCannotReplaceAttachedOriginal() = refusedReplacement {
        it.copy(generation = SessionGeneration(it.generation.value + 1))
    }

    @Test
    fun rempC05EpochChangeRejectsLatePrivateResultWithoutInventingStorageState() = runTest(timeout = 5.seconds) {
        withFence { f ->
            val before = f.retainOriginal()
            f.fence.beforeProtectedWrite(LocalMasterRpcSessionInvalidation.None)
            assertTrue(f.original.isActive)
            assertFalse(f.fence.attachAuthenticationExchangeResult(f.owner, before, f.original, f.device, f.identity))
            assertFalse(f.fence.retainAuthenticationExchangeSession(f.owner, before, f.successor, f.device))
            val after = assertNotNull(f.fence.captureSessionEpoch(f.owner))
            assertTrue(f.fence.attachAuthenticationExchangeResult(f.owner, after, f.original, f.device, f.identity))
        }
        // Internal epoch/census boundary only, not an authoritative transaction/output ordering result.
    }

    @Test
    fun rempC05DeviceRevocationFenceRevokesOnlyMatchingControlledDeviceExchange() = runTest(timeout = 5.seconds) {
        withFence { f ->
            val epoch = f.retainOriginal()
            val otherDevice = f.device.copy(deviceId = FenceFrozenInputs.otherDeviceId)
            assertTrue(f.fence.retainAuthenticationExchangeSession(f.owner, epoch, f.successor, otherDevice))
            val revoked = LocalDeviceAdmission(f.device.scope, f.device.deviceId, f.device.admissionRevision,
                admitted = false, publicKeyIdentifier = null, publicFingerprint = null, capabilities = emptySet())
            f.fence.beforeProtectedWrite(LocalMasterRpcSessionInvalidation.Admission(revoked))
            assertFalse(f.original.isActive)
            assertTrue(f.successor.isActive)
        }
        // The protected provider/SQLite write and actual native currentness remain unbound.
    }

    @Test
    fun rempC05AccountDisableFenceRevokesAttachedResultButPreservesUnattachedDeviceExchange() =
        accountInvalidationRevokesAttachedResultOnly(enabled = false)

    @Test
    fun rempC05OriginalSessionRevocationFenceRevokesAttachedResultButPreservesUnattachedDeviceExchange() =
        accountInvalidationRevokesAttachedResultOnly(enabled = true)

    @Test
    fun rempC06AttachedOriginalCannotAdoptSuccessorButSeparateSdkExchangeCanAttachFreshIdentity() = runTest(timeout = 5.seconds) {
        withFence { f ->
            val epoch = f.retainOriginal()
            assertTrue(f.fence.attachAuthenticationExchangeResult(f.owner, epoch, f.original, f.device, f.identity))
            val successorIdentity = f.identity.copy(sessionId = FenceFrozenInputs.successorSessionId)
            assertFalse(f.fence.attachAuthenticationExchangeResult(f.owner, epoch, f.original, f.device, successorIdentity))
            assertTrue(f.fence.retainAuthenticationExchangeSession(f.owner, epoch, f.successor, f.device))
            assertTrue(f.fence.attachAuthenticationExchangeResult(f.owner, epoch, f.successor, f.device, successorIdentity))
            assertNotSame(f.original, f.successor)
            assertEquals(f.original.peer, f.successor.peer)
            assertTrue(f.original.isActive)
            assertTrue(f.successor.isActive)
            assertTrue(f.fence.attachAuthenticationExchangeResult(f.owner, epoch, f.original, f.device, f.identity))
        }
        // Separate SDK objects with identical descriptor DATA are not proof of freshly
        // authenticated successor identity on the same live physical connection.
    }

    @Test
    fun rempC07ExactOriginalExchangeRevocationCannotRetargetSameDescriptorSuccessor() = runTest(timeout = 5.seconds) {
        withFence { f ->
            val epoch = f.retainOriginal()
            assertTrue(f.fence.retainAuthenticationExchangeSession(f.owner, epoch, f.successor, f.device))
            f.fence.revokeApplicationSession(f.owner, f.original)
            f.fence.revokeApplicationSession(f.owner, f.original)
            assertFalse(f.original.isActive)
            assertTrue(f.successor.isActive)
            assertFalse(f.fence.retainAuthenticationExchangeSession(f.owner, epoch, f.original, f.device))
            assertTrue(f.fence.retainAuthenticationExchangeSession(f.owner, epoch, f.successor, f.device))
        }
        // Exact SDK application-session revocation only, never confirmed physical close.
    }

    @Test
    fun rempC07DenialWinsLateResultAttachmentAndCannotBeReversedByRepeatedCleanup() = runTest(timeout = 5.seconds) {
        withFence { f ->
            val epoch = f.retainOriginal()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val late = async(start = CoroutineStart.UNDISPATCHED) {
                entered.complete(Unit)
                release.await()
                f.fence.attachAuthenticationExchangeResult(f.owner, epoch, f.original, f.device, f.identity)
            }
            assertTrue(entered.isCompleted)
            f.fence.deny()
            assertNull(f.fence.captureSessionEpoch(f.owner))
            release.complete(Unit)
            assertFalse(late.await())
            f.fence.awaitSessionRevocation()
            f.fence.bind(f.owner)
            f.fence.awaitSessionRevocation()
            assertNull(f.fence.captureSessionEpoch(f.owner))
            assertFalse(f.original.isActive)
            assertTrue(f.successor.isActive)
            assertFalse(f.fence.retainAuthenticationExchangeSession(f.owner, epoch, f.successor, f.device))
        }
        // No SDK close failure or native lifetime resurrection is fabricated.
    }

    @Test
    fun rempC07CancelledCleanupCallerStillRevokesOwnedOriginalSdkExchange() = runTest(timeout = 5.seconds) {
        withFence { f ->
            f.retainOriginal()
            var cancellationPropagated = false
            val caller = launch(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                try { f.fence.awaitSessionRevocation() }
                catch (_: CancellationException) { cancellationPropagated = true }
            }
            caller.join()
            assertTrue(cancellationPropagated)
            assertTrue(caller.isCancelled)
            assertFalse(f.original.isActive)
            assertTrue(f.successor.isActive)
            assertNull(f.fence.captureSessionEpoch(f.owner))
            f.fence.awaitSessionRevocation()
            assertFalse(f.original.isActive)
        }
        // Cancelled at the actual helper cleanup entry; suspended physical cleanup remains unbound.
    }

    private fun refusedInitialIdentity(change: (LocalMasterRpcSessionIdentity) -> LocalMasterRpcSessionIdentity) = runTest(timeout = 5.seconds) {
        withFence { f ->
            val epoch = f.retainOriginal()
            assertFalse(f.fence.attachAuthenticationExchangeResult(f.owner, epoch, f.original, f.device, change(f.identity)))
            assertTrue(f.original.isActive)
            assertTrue(f.fence.attachAuthenticationExchangeResult(f.owner, epoch, f.original, f.device, f.identity))
        }
    }

    private fun refusedReplacement(change: (LocalMasterRpcSessionIdentity) -> LocalMasterRpcSessionIdentity) = runTest(timeout = 5.seconds) {
        withFence { f ->
            val epoch = f.retainOriginal()
            assertTrue(f.fence.attachAuthenticationExchangeResult(f.owner, epoch, f.original, f.device, f.identity))
            assertFalse(f.fence.attachAuthenticationExchangeResult(f.owner, epoch, f.original, f.device, change(f.identity)))
            assertTrue(f.fence.attachAuthenticationExchangeResult(f.owner, epoch, f.original, f.device, f.identity))
        }
    }

    private fun accountInvalidationRevokesAttachedResultOnly(enabled: Boolean) = runTest(timeout = 5.seconds) {
        withFence { f ->
            val epoch = f.retainOriginal()
            assertTrue(f.fence.attachAuthenticationExchangeResult(f.owner, epoch, f.original, f.device, f.identity))
            assertTrue(f.fence.retainAuthenticationExchangeSession(f.owner, epoch, f.successor, f.device))
            val next = f.accountWithOriginalRevoked(enabled)
            f.fence.beforeProtectedWrite(LocalMasterRpcSessionInvalidation.Account(next, f.credentialRevision))
            assertFalse(f.original.isActive)
            assertTrue(f.successor.isActive)
        }
        // Frozen account-disable/original-session-revocation cutoff at the actual fence helper.
        // No protected transaction, native currentness or private output commitment is executed.
    }
}

/**
 * Portable DATA mapping of the unchanged frozen JSON's original_identity, devices.device_b,
 * accounts.account_b/other_branch_account, requests.login/refresh and trusted_finite_positive.
 * Original hashes: cases 11733db43b1db894b73ded18006f0a6cdb86a653f0e94917826c16efc80ea46b;
 * fixtures 922509c2936525d54f02e541bd9d521d825ab5451fd75dd5e00dec0ea5f7c9ee.
 */
private object FenceFrozenInputs {
    val scope = BranchScope(DomainId("00000000-0000-4000-8000-000000000901"),
        DomainId("00000000-0000-4000-8000-000000000902"), DomainId("00000000-0000-4000-8000-000000000001"))
    val actorId = DomainId("00000000-0000-4000-8000-000000000101")
    val deviceId = DomainId("00000000-0000-4000-8000-000000000201")
    val sessionId = DomainId("00000000-0000-4000-8000-000000000301")
    val otherActorId = DomainId("00000000-0000-4000-8000-000000000102")
    val otherDeviceId = DomainId("00000000-0000-4000-8000-000000000202")
    val otherBranchId = DomainId("00000000-0000-4000-8000-000000000002")
    // Distinct canonical carrier for successor_session_id_symbol only, not an actually issued ID.
    val successorSessionId = DomainId("00000000-0000-4000-8000-000000000302")
    const val originalExchangeId = "00000000-0000-4000-8000-000000000401"
    const val successorExchangeId = "00000000-0000-4000-8000-000000000402"
}

private class AuthenticationExchangeFenceFixture {
    val owner: Any = Any()
    val fence = LocalMasterRpcSessionFence().also { it.bind(owner) }
    val identity = LocalMasterRpcSessionIdentity(FenceFrozenInputs.scope, FenceFrozenInputs.actorId, FenceFrozenInputs.deviceId,
        FenceFrozenInputs.sessionId, SessionGeneration(7), Revision(3), AuthenticationPurpose.INTERACTIVE)
    val device = LocalMasterRpcExchangeDeviceIdentity(identity.scope, identity.deviceId, identity.admissionRevision)
    // Shared physical peer symbol -> canonical syntax-only SDK fingerprint, not key/native evidence.
    private val fingerprint = PeerFingerprint("p2f1-" + "a".repeat(52))
    val original = RpcApplicationSession(fingerprint, "controlled-device-authentication-exchange",
        FenceFrozenInputs.originalExchangeId, 5000.milliseconds)
    val successor = RpcApplicationSession(fingerprint, "controlled-device-authentication-exchange",
        FenceFrozenInputs.successorExchangeId, 5000.milliseconds)
    val credentialRevision = Revision(0)

    fun accountWithOriginalRevoked(enabled: Boolean): ProtectedLocalAccount {
        val originalRecord = LocalSessionRecord(identity.sessionId, identity.scope, identity.actorId,
            identity.deviceId, identity.generation, SessionStatus.REVOKED, identity.purpose)
        val state = LocalAccountSessionState(identity.scope, identity.actorId, enabled, Revision(1),
            identity.generation, listOf(originalRecord))
        // A non-null syntactic sentinel isolates Account/session-state invalidation from a
        // credential change. Revisions, parameters and pepper UUID are carrier DATA, not
        // frozen provisioning/default policy; no hasher, key provider or store consumes it.
        val credential = PasswordHashRecord(CredentialBinding(identity.scope, identity.actorId, credentialRevision),
            PasswordHashParameters(iterations = 600_000),
            ProtectedPepperReference(DomainId("00000000-0000-4000-8000-000000000501")),
            ByteArray(16), ByteArray(32))
        return ProtectedLocalAccount(state, credentialRevision, credential)
    }

    suspend fun retainOriginal(): LocalMasterRpcSessionEpoch {
        val epoch = assertNotNull(fence.captureSessionEpoch(owner))
        assertTrue(fence.retainAuthenticationExchangeSession(owner, epoch, original, device))
        return epoch
    }
}

private suspend fun withFence(block: suspend (AuthenticationExchangeFenceFixture) -> Unit) {
    val fixture = AuthenticationExchangeFenceFixture()
    try { block(fixture) }
    finally { withContext(NonCancellable) { fixture.original.revoke(); fixture.successor.revoke() } }
}
