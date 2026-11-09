package com.ukkera.pos.database

import dev.p2pkit.core.PeerFingerprint
import dev.p2pkit.rpc.RpcApplicationSession
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * L1 supporting slices of frozen master-rpc-session-revocation v1.
 *
 * These exercise the actual production fence and official SDK application-session objects.
 * Synthetic referential owner markers and a canonical fingerprint supply no native admission,
 * authenticated Host callback, employee authorization, physical lifetime or durable mutation.
 * The pre-write call below is an internal epoch boundary, not the frozen authoritative M marker.
 * No whole case, business commit, SDK transport commitment or physical close is established here.
 */
class LocalMasterRpcSessionRevocationSupportingTest {
    /** MRSR-01 / IDENTITY-VALID: exact SDK object reuse without a security change, helper only. */
    @Test
    fun mrsr01SameOriginalSdkObjectCanBeRetainedAgainWithoutChange() = runTest(timeout = 5.seconds) {
        withFenceFixture { f ->
            assertTrue(f.original.isActive)
            val epoch = assertNotNull(f.fence.captureSessionEpoch(f.owner))

            assertTrue(f.fence.retainApplicationSession(f.owner, epoch, f.original, f.originalIdentity))
            assertTrue(f.fence.retainApplicationSession(f.owner, epoch, f.original, f.originalIdentity))
            assertTrue(f.original.isActive)

            f.fence.awaitSessionRevocation()
            assertFalse(f.original.isActive)
        }
        // No principal read, resource authorization or changed-policy reuse is represented.
    }

    /** MRSR-01 / invalid original context: no substitution by another synthetic owner/fence. */
    @Test
    fun mrsr01ForeignOriginAndForeignFenceEpochCannotRetainSdkSession() = runTest(timeout = 5.seconds) {
        withFenceFixture { f ->
            val epoch = f.retainOriginal()
            val otherOwner = Any()
            val otherFence = LocalMasterRpcSessionFence().also { it.bind(otherOwner) }
            assertNotNull(otherFence.captureSessionEpoch(otherOwner))

            assertNull(f.fence.captureSessionEpoch(otherOwner))
            assertFalse(f.fence.retainApplicationSession(otherOwner, epoch, f.original, f.originalIdentity))
            assertTrue(f.successor.isActive)
            assertFalse(otherFence.retainApplicationSession(otherOwner, epoch, f.successor, f.successorIdentity))

            f.fence.awaitSessionRevocation()
            assertFalse(f.original.isActive)
            assertTrue(f.successor.isActive)
        }
        // The markers are not native registrations and do not qualify a different Master.
    }

    /** MRSR-03 / IN-PROGRESS-NEXT-GATE-AFTER-M: stale retention slice at the internal epoch seam. */
    @Test
    fun mrsr03PreWriteEpochCannotRetainLateSdkCandidateAfterAwaitedFence() = runTest(timeout = 5.seconds) {
        withFenceFixture { f ->
            val earlierEpoch = f.retainOriginal()
            assertTrue(f.successor.isActive)
            val beforeRetention = RetentionBarrier()
            val lateRetention = async(start = CoroutineStart.UNDISPATCHED) {
                beforeRetention.arriveAndAwaitRelease()
                f.fence.retainApplicationSession(f.owner, earlierEpoch, f.successor, f.successorIdentity)
            }
            assertTrue(beforeRetention.entered.isCompleted)

            f.fence.beforeProtectedWrite()
            assertFalse(f.original.isActive)
            beforeRetention.release.complete(Unit)
            assertFalse(lateRetention.await())
        }
        // No SQL/provider write or authoritative M is fabricated; the business cut remains unrun.
    }

    /** MRSR-07 / REPEATED-RETIREMENT and successor target: awaited original SDK revoke only. */
    @Test
    fun mrsr07ExactOriginalRevokeLeavesSameFingerprintSuccessorActive() = runTest(timeout = 5.seconds) {
        withFenceFixture { f ->
            val epoch = f.retainOriginal()
            assertTrue(f.fence.retainApplicationSession(f.owner, epoch, f.successor, f.successorIdentity))
            assertNotSame(f.original, f.successor)
            assertEquals(f.original.peer, f.successor.peer)
            assertTrue(f.successor.isActive)

            f.fence.revokeApplicationSession(f.owner, f.original)
            assertFalse(f.original.isActive)
            assertTrue(f.successor.isActive)
            assertFalse(f.fence.retainApplicationSession(f.owner, epoch, f.original, f.originalIdentity))
            assertTrue(f.fence.retainApplicationSession(f.owner, epoch, f.successor, f.successorIdentity))

            f.fence.revokeApplicationSession(f.owner, f.original)
            assertFalse(f.original.isActive)
            assertTrue(f.successor.isActive)

            f.fence.revokeApplicationSession(f.owner, f.successor)
            assertFalse(f.successor.isActive)
        }
        // Matching fingerprint is synthetic metadata; no SDK generation or physical target exists.
    }

    /** MRSR-07: observable helper denial wins a later retention; only retained original is revoked. */
    @Test
    fun mrsr07DenialWinsLateRetentionBeforeAwaitedOriginalSdkCleanup() = runTest(timeout = 5.seconds) {
        withFenceFixture { f ->
            val epoch = f.retainOriginal()
            val beforeRetention = RetentionBarrier()
            val lateRetention = async(start = CoroutineStart.UNDISPATCHED) {
                beforeRetention.arriveAndAwaitRelease()
                f.fence.retainApplicationSession(f.owner, epoch, f.successor, f.successorIdentity)
            }
            assertTrue(beforeRetention.entered.isCompleted)

            f.fence.deny()
            assertNull(f.fence.captureSessionEpoch(f.owner))
            beforeRetention.release.complete(Unit)
            assertFalse(lateRetention.await())

            f.fence.awaitSessionRevocation()
            assertFalse(f.original.isActive)
            assertTrue(f.successor.isActive)
            assertNull(f.fence.captureSessionEpoch(f.owner))
        }
        // R is modeled only by helper denial; binding credential release and C remain unrun.
    }

    /** MRSR-07 / WAITER-CANCELLED: cancellation after helper denial, before the awaited cleanup call. */
    @Test
    fun mrsr07CancelledCallerAfterDenialStillRevokesExactRetainedSdkSession() = runTest(timeout = 5.seconds) {
        withFenceFixture { f ->
            f.retainOriginal()
            f.fence.deny()
            assertNull(f.fence.captureSessionEpoch(f.owner))
            var returnedNormally = false
            var propagatedCancellation = false
            val caller = launch(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                try {
                    f.fence.awaitSessionRevocation()
                    returnedNormally = true
                } catch (_: CancellationException) {
                    propagatedCancellation = true
                }
            }
            caller.join()

            assertTrue(caller.isCancelled)
            assertTrue(propagatedCancellation)
            assertFalse(returnedNormally)
            assertFalse(f.original.isActive)
            assertTrue(f.successor.isActive)
            assertNull(f.fence.captureSessionEpoch(f.owner))

            f.fence.awaitSessionRevocation()
            assertFalse(f.original.isActive)
            assertTrue(f.successor.isActive)
        }
        // No public SDK seam suspends revoke; cancellation during suspended cleanup is not covered.
    }

    /** MRSR-08 / FAILED-AFTER-RETIRE: irreversible local slice only; no storage failure is injected. */
    @Test
    fun mrsr08RepeatedRetirementCannotReopenOriginalFenceOrRetargetSuccessor() = runTest(timeout = 5.seconds) {
        withFenceFixture { f ->
            val originalEpoch = f.retainOriginal()
            f.fence.deny()
            f.fence.awaitSessionRevocation()
            assertFalse(f.original.isActive)

            val successorOwner = Any()
            val successorFence = LocalMasterRpcSessionFence().also { it.bind(successorOwner) }
            val successorEpoch = assertNotNull(successorFence.captureSessionEpoch(successorOwner))
            assertTrue(successorFence.retainApplicationSession(successorOwner, successorEpoch, f.successor, f.successorIdentity))

            f.fence.bind(f.owner)
            assertNull(f.fence.captureSessionEpoch(f.owner))
            assertFalse(f.fence.retainApplicationSession(f.owner, originalEpoch, f.successor, f.successorIdentity))
            f.fence.deny()
            f.fence.awaitSessionRevocation()
            assertFalse(f.original.isActive)
            assertTrue(f.successor.isActive)
            assertTrue(successorFence.retainApplicationSession(successorOwner, successorEpoch, f.successor, f.successorIdentity))

            successorFence.awaitSessionRevocation()
            assertFalse(f.successor.isActive)
        }
        // Fresh helper retention is not fresh native authentication, rollback or restart evidence.
    }
}

private class FenceFixture {
    val owner: Any = Any()
    val fence: LocalMasterRpcSessionFence = LocalMasterRpcSessionFence().also { it.bind(owner) }
    val originalIdentity = FrozenRpcSessionRevocationSupportingInputs.original
    val successorIdentity = FrozenRpcSessionRevocationSupportingInputs.successor

    // Syntax-only mapping of F-ORIGINAL/F-SUCCESSOR's shared symbolic SDK fingerprint.
    // This value is not evidence of a key, handshake, admission or physical connection.
    private val fingerprint = PeerFingerprint("p2f1-" + "a".repeat(52))
    val original = RpcApplicationSession(fingerprint, originalIdentity.actorId.value,
        originalIdentity.sessionId.value, 60_000.milliseconds)
    val successor = RpcApplicationSession(fingerprint, successorIdentity.actorId.value,
        successorIdentity.sessionId.value, 60_000.milliseconds)

    suspend fun retainOriginal(): LocalMasterRpcSessionEpoch {
        assertTrue(original.isActive)
        val epoch = assertNotNull(fence.captureSessionEpoch(owner))
        assertTrue(fence.retainApplicationSession(owner, epoch, original, originalIdentity))
        return epoch
    }
}

private class RetentionBarrier {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()

    suspend fun arriveAndAwaitRelease() {
        entered.complete(Unit)
        release.await()
    }
}

private suspend fun withFenceFixture(block: suspend (FenceFixture) -> Unit) {
    val fixture = FenceFixture()
    try {
        block(fixture)
    } finally {
        withContext(NonCancellable) {
            fixture.original.revoke()
            fixture.successor.revoke()
        }
    }
}
