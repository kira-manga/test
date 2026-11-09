package com.ukkera.pos.database

import com.ukkera.pos.database.FrozenRpcSessionRevocationSupportingInputs as Frozen
import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.security.SessionStatus
import com.ukkera.pos.types.Revision
import dev.p2pkit.rpc.RpcApplicationSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * L1 supporting slices from frozen master-rpc-session-revocation v1 and the original/current
 * public identity contracts. Real production selection and real official SDK session objects
 * are used with inert typed metadata and DTOs. Nothing authenticates a native principal, activates
 * a policy, commits a protected Room write, closes a physical connection or proves a whole case.
 */
class LocalMasterRpcSelectiveSessionInvalidationSupportingTest {
    /** MRSR-01 / IDENTITY-VALID-RESOURCE-DENIED: same-object continuity slice, no resource request. */
    @Test
    fun mrsr01NoneInvalidationKeepsExactOriginalSdkSessionReusable() = runTest(timeout = 5.seconds) {
        withSelectiveFixture(listOf(Frozen.original)) { f ->
            f.retainAll()
            val original = f.logins.single()

            f.fence.beforeProtectedWrite(LocalMasterRpcSessionInvalidation.None)
            assertTrue(original.sdk.isActive)
            val currentEpoch = assertNotNull(f.fence.captureSessionEpoch(f.owner))
            assertTrue(f.fence.retainApplicationSession(f.owner, currentEpoch, original.sdk, original.identity))
            assertTrue(original.sdk.isActive)
        }
        // None is the internal identity-preserving classification. No policy activation, self-read
        // response or denied cash/report/export assertion is represented by this supporting slice.
    }

    /** MRSR-01 invalid-original variants: the retained exact SDK object cannot adopt altered metadata. */
    @Test
    fun mrsr01ChangedOriginalMetadataCannotRebindRetainedSdkObject() = runTest(timeout = 5.seconds) {
        val variants = listOf(
            "EMPLOYEE-CHANGED" to Frozen.original.copy(actorId = Frozen.otherActor),
            "SESSION-CHANGED" to Frozen.original.copy(sessionId = Frozen.successorSession),
            "GENERATION-CHANGED" to Frozen.original.copy(generation = SessionGeneration(10)),
            "DEVICE-CHANGED" to Frozen.original.copy(deviceId = Frozen.otherDevice),
            "BRANCH-CHANGED" to Frozen.original.copy(scope = Frozen.otherBranch),
            "ADMISSION-CHANGED" to Frozen.original.copy(admissionRevision = Revision(8)),
            "PURPOSE-WRONG" to Frozen.original.copy(purpose = AuthenticationPurpose.SERVICE),
        )
        for ((variant, changed) in variants) {
            withSelectiveFixture(listOf(Frozen.original)) { f ->
                val epoch = f.retainAll()
                val original = f.logins.single()
                assertFalse(f.fence.retainApplicationSession(f.owner, epoch, original.sdk, changed), variant)
            }
        }
        // No verifier or authoritative original state is fabricated; these are metadata refusal slices.
    }

    /** MRSR-03 / SESSION-REVOKED and MRSR-07 successor isolation: unchanged same-actor login survives. */
    @Test
    fun mrsr03RevokingOriginalSessionPreservesUnchangedSameActorSiblingSdkObject() = runTest(timeout = 5.seconds) {
        withSelectiveFixture(listOf(Frozen.original, Frozen.successor)) { f ->
            f.retainAll()
            val original = f.logins[0]
            val sibling = f.logins[1]
            assertNotSame(original.sdk, sibling.sdk)
            assertEquals(original.sdk.peer, sibling.sdk.peer)
            val next = Frozen.account(listOf(
                Frozen.record(Frozen.original, SessionStatus.REVOKED),
                Frozen.record(Frozen.successor),
            ))

            f.fence.beforeProtectedWrite(LocalMasterRpcSessionInvalidation.Account(next, Frozen.credentialRevision))
            assertFalse(original.sdk.isActive)
            assertTrue(sibling.sdk.isActive)
            val currentEpoch = assertNotNull(f.fence.captureSessionEpoch(f.owner))
            assertTrue(f.fence.retainApplicationSession(f.owner, currentEpoch, sibling.sdk, sibling.identity))
            assertTrue(sibling.sdk.isActive)
        }
        // Aggregate generation 11 does not change the sibling's original session generation 10.
        // These DTOs prove neither concurrent native authentication nor a durable account change.
    }

    /** MRSR-03 / EMPLOYEE-DISABLED; MRSR-05's separately current B identity is not A's old binding. */
    @Test
    fun mrsr03DisabledActorRevokesItsOriginalAndSiblingSdkSessionsOnly() = runTest(timeout = 5.seconds) {
        withSelectiveFixture(listOf(Frozen.original, Frozen.successor, Frozen.otherActorIdentity)) { f ->
            f.retainAll()
            val next = Frozen.account(listOf(
                Frozen.record(Frozen.original, SessionStatus.REVOKED),
                Frozen.record(Frozen.successor, SessionStatus.REVOKED),
            ), enabled = false)

            f.fence.beforeProtectedWrite(LocalMasterRpcSessionInvalidation.Account(next, Frozen.credentialRevision))
            assertFalse(f.logins[0].sdk.isActive)
            assertFalse(f.logins[1].sdk.isActive)
            assertTrue(f.logins[2].sdk.isActive)
            val currentEpoch = assertNotNull(f.fence.captureSessionEpoch(f.owner))
            val otherActor = f.logins[2]
            assertTrue(f.fence.retainApplicationSession(f.owner, currentEpoch, otherActor.sdk, otherActor.identity))
        }
        // No B resource grant, handler/delivery adoption, account switch or private-view check occurs.
    }

    /** MRSR-03 / DEVICE-WITHDRAWN and MRSR-01 / ADMISSION-CHANGED: select exact device context. */
    @Test
    fun mrsr03WithdrawnOrChangedAdmissionRevokesOriginalDeviceSdkSessionsOnly() = runTest(timeout = 5.seconds) {
        val variants = listOf(
            "DEVICE-WITHDRAWN" to Frozen.admission(admitted = false),
            "ADMISSION-CHANGED" to Frozen.admission(admitted = true, revision = Revision(8)),
        )
        for ((variant, next) in variants) {
            withSelectiveFixture(listOf(Frozen.original, Frozen.successor, Frozen.otherDeviceIdentity)) { f ->
                f.retainAll()

                f.fence.beforeProtectedWrite(LocalMasterRpcSessionInvalidation.Admission(next))
                assertFalse(f.logins[0].sdk.isActive, variant)
                assertFalse(f.logins[1].sdk.isActive, variant)
                assertTrue(f.logins[2].sdk.isActive, variant)
                val currentEpoch = assertNotNull(f.fence.captureSessionEpoch(f.owner))
                val otherDevice = f.logins[2]
                assertTrue(f.fence.retainApplicationSession(f.owner, currentEpoch, otherDevice.sdk, otherDevice.identity), variant)
            }
        }
        // D2 is inert metadata, not proof of another admitted device or any physical close.
    }

    /** MRSR-03 / IN-PROGRESS-NEXT-GATE-AFTER-M: later typed invalidation after an earlier None call. */
    @Test
    fun mrsr03LaterTypedInvalidationRejectsCandidateHeldAtEarlierEpoch() = runTest(timeout = 5.seconds) {
        withSelectiveFixture(listOf(Frozen.original, Frozen.successor)) { f ->
            val original = f.logins[0]
            val candidate = f.logins[1]
            val initialEpoch = assertNotNull(f.fence.captureSessionEpoch(f.owner))
            assertTrue(f.fence.retainApplicationSession(f.owner, initialEpoch, original.sdk, original.identity))
            f.fence.beforeProtectedWrite(LocalMasterRpcSessionInvalidation.None)
            assertTrue(original.sdk.isActive)
            val earlierEpoch = assertNotNull(f.fence.captureSessionEpoch(f.owner))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val lateRetention = async(start = CoroutineStart.UNDISPATCHED) {
                entered.complete(Unit)
                release.await()
                f.fence.retainApplicationSession(f.owner, earlierEpoch, candidate.sdk, candidate.identity)
            }
            assertTrue(entered.isCompleted)
            val next = Frozen.account(listOf(
                Frozen.record(Frozen.original, SessionStatus.REVOKED),
                Frozen.record(Frozen.successor),
            ))

            f.fence.beforeProtectedWrite(LocalMasterRpcSessionInvalidation.Account(next, Frozen.credentialRevision))
            assertFalse(original.sdk.isActive)
            assertTrue(candidate.sdk.isActive)
            release.complete(Unit)
            assertFalse(lateRetention.await())
        }
        // Two direct helper calls are not paired Room mutations or an observed durable M marker.
        // The barrier precedes helper entry; no SDK transport admission/commit overlap is modeled.
    }
}

private class InertSdkLogin(val identity: LocalMasterRpcSessionIdentity, val sdk: RpcApplicationSession)

private class SelectiveFenceFixture(identities: List<LocalMasterRpcSessionIdentity>) {
    val owner: Any = Any()
    val fence = LocalMasterRpcSessionFence().also { it.bind(owner) }
    val logins = identities.map { InertSdkLogin(it, Frozen.applicationSession(it)) }

    suspend fun retainAll(): LocalMasterRpcSessionEpoch {
        val epoch = assertNotNull(fence.captureSessionEpoch(owner))
        for (login in logins) {
            assertTrue(login.sdk.isActive)
            assertTrue(fence.retainApplicationSession(owner, epoch, login.sdk, login.identity))
        }
        return epoch
    }
}

private suspend fun withSelectiveFixture(
    identities: List<LocalMasterRpcSessionIdentity>,
    block: suspend (SelectiveFenceFixture) -> Unit,
) {
    val fixture = SelectiveFenceFixture(identities)
    try {
        block(fixture)
    } finally {
        withContext(NonCancellable) {
            for (login in fixture.logins) login.sdk.revoke()
        }
    }
}
