package com.ukkera.pos.database.independent.kitchenrpcworkflowv1

import com.ukkera.pos.database.LocalMasterRpcSessionFence
import com.ukkera.pos.database.LocalMasterRpcSessionIdentity
import com.ukkera.pos.database.LocalMasterRpcSessionInvalidation
import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import dev.p2pkit.core.PeerFingerprint
import dev.p2pkit.rpc.RpcApplicationSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * KRPC-004 lifetime support only: the production resource-session census and
 * official SDK session objects run with inert identity metadata and an owner marker.
 * A deferred continuation samples the captured marker, without protected output.
 * These checks establish no employee grant, native Host/physical audience, DISPLAY
 * source, authoritative commit, retained business result or L3 case outcome.
 * SDK activity sampling occurs only through the pre-final current() seam; the
 * POS stateCurrent() marker is separate. SDK expiry/native outbound fencing is
 * not established by these controlled lifetime observations.
 */
class KitchenRpcRetainedSessionSupportingTest {
    @Test
    fun krpc004CaptureRequiresTheExactRetainedResourceEntry() = runTest(timeout = 5.seconds) {
        withKitchenFence { fixture ->
            val identity = KitchenSessionInputs.original
            val identityOnly = fixture.session(identity)
            val resource = fixture.session(identity)
            val unretainedSibling = fixture.session(identity)
            val epoch = assertNotNull(fixture.fence.captureSessionEpoch(fixture.owner))
            assertTrue(fixture.fence.retainApplicationSession(fixture.owner, epoch, identityOnly, identity))
            assertNull(fixture.fence.captureResourceSession(fixture.owner, identityOnly))
            assertNull(fixture.fence.captureResourceSession(fixture.owner, unretainedSibling))
            assertTrue(fixture.fence.retainResourceApplicationSession(fixture.owner, epoch, resource, identity))
            assertNull(fixture.fence.captureResourceSession(Any(), resource))
            val captured = assertNotNull(fixture.fence.captureResourceSession(fixture.owner, resource))
            assertEquals(identity, captured.identity)
            assertSame(resource, captured.session)
            assertTrue(captured.stateCurrent())
            assertNull(fixture.fence.captureResourceSession(fixture.owner, identityOnly))
            assertNull(fixture.fence.captureResourceSession(fixture.owner, unretainedSibling))
        }
    }

    @Test
    fun krpc004PolicyRetirementIsVisibleToTheHeldOriginalContinuation() = runTest(timeout = 5.seconds) {
        withKitchenFence { fixture ->
            val identity = KitchenSessionInputs.original
            val resource = fixture.session(identity)
            val identityOnly = fixture.session(identity)
            val other = fixture.session(KitchenSessionInputs.otherActor)
            val epoch = assertNotNull(fixture.fence.captureSessionEpoch(fixture.owner))
            assertTrue(fixture.fence.retainResourceApplicationSession(fixture.owner, epoch, resource, identity))
            assertTrue(fixture.fence.retainApplicationSession(fixture.owner, epoch, identityOnly, identity))
            assertTrue(fixture.fence.retainResourceApplicationSession(fixture.owner, epoch, other, KitchenSessionInputs.otherActor))
            val captured = assertNotNull(fixture.fence.captureResourceSession(fixture.owner, resource))
            val otherCaptured = assertNotNull(fixture.fence.captureResourceSession(fixture.owner, other))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val held = async(start = CoroutineStart.UNDISPATCHED) {
                entered.complete(Unit)
                release.await()
                captured.stateCurrent()
            }
            try {
                assertTrue(entered.isCompleted)
                fixture.fence.beforeProtectedWrite(LocalMasterRpcSessionInvalidation.Policy(identity.scope, identity.actorId))
                assertFalse(captured.stateCurrent())
                assertNull(fixture.fence.captureResourceSession(fixture.owner, resource))
                assertTrue(identityOnly.isActive)
                assertTrue(otherCaptured.stateCurrent())
                release.complete(Unit)
                assertFalse(held.await())
            } finally {
                release.complete(Unit)
                withContext(NonCancellable) { held.cancelAndJoin() }
            }
        }
    }

    @Test
    fun krpc004AReplacementSdkObjectCannotReviveTheCapturedOriginal() = runTest(timeout = 5.seconds) {
        withKitchenFence { fixture ->
            val identity = KitchenSessionInputs.original
            val original = fixture.session(identity)
            val epoch = assertNotNull(fixture.fence.captureSessionEpoch(fixture.owner))
            assertTrue(fixture.fence.retainResourceApplicationSession(fixture.owner, epoch, original, identity))
            val captured = assertNotNull(fixture.fence.captureResourceSession(fixture.owner, original))
            fixture.fence.beforeProtectedWrite(LocalMasterRpcSessionInvalidation.Policy(identity.scope, identity.actorId))
            val nextEpoch = assertNotNull(fixture.fence.captureSessionEpoch(fixture.owner))
            assertFalse(fixture.fence.retainResourceApplicationSession(fixture.owner, nextEpoch, original, identity))
            val replacement = fixture.session(identity)
            assertNull(fixture.fence.captureResourceSession(fixture.owner, replacement))
            assertTrue(fixture.fence.retainResourceApplicationSession(fixture.owner, nextEpoch, replacement, identity))
            val replacementCaptured = assertNotNull(fixture.fence.captureResourceSession(fixture.owner, replacement))
            assertTrue(replacementCaptured.stateCurrent())
            assertSame(replacement, replacementCaptured.session)
            assertFalse(captured.stateCurrent())
            assertSame(original, captured.session)
            assertNull(fixture.fence.captureResourceSession(fixture.owner, original))
        }
        // Fresh helper retention is not proof of reauthentication or restored station access.
    }

    @Test
    fun krpc004SdkRevocationAndFenceDrainRetireCapturedResources() = runTest(timeout = 5.seconds) {
        withKitchenFence { fixture ->
            val original = fixture.session(KitchenSessionInputs.original)
            val other = fixture.session(KitchenSessionInputs.otherActor)
            val epoch = assertNotNull(fixture.fence.captureSessionEpoch(fixture.owner))
            assertTrue(fixture.fence.retainResourceApplicationSession(fixture.owner, epoch, original, KitchenSessionInputs.original))
            assertTrue(fixture.fence.retainResourceApplicationSession(fixture.owner, epoch, other, KitchenSessionInputs.otherActor))
            val captured = assertNotNull(fixture.fence.captureResourceSession(fixture.owner, original))
            val otherCaptured = assertNotNull(fixture.fence.captureResourceSession(fixture.owner, other))
            original.revoke()
            // The SDK activity getter can sample its clock. Observe revocation before
            // the protected final boundary, then retain the irreversible POS marker.
            assertFalse(captured.current())
            assertFalse(captured.stateCurrent())
            assertNull(fixture.fence.captureResourceSession(fixture.owner, original))
            assertTrue(otherCaptured.stateCurrent())
            fixture.fence.awaitSessionRevocation()
            assertFalse(otherCaptured.stateCurrent())
            assertFalse(other.isActive)
            assertNull(fixture.fence.captureResourceSession(fixture.owner, other))
            fixture.fence.bind(fixture.owner)
            assertNull(fixture.fence.captureSessionEpoch(fixture.owner))
            assertFalse(captured.stateCurrent())
        }
    }
}

/** KBA F-AUTH identities plus auxiliary SDK-only session metadata; no credentials. */
private object KitchenSessionInputs {
    val scope = BranchScope(DomainId("10000000-0000-4000-8000-000000000001"),
        DomainId("10000000-0000-4000-8000-000000000002"), DomainId("10000000-0000-4000-8000-000000000003"))
    val original = LocalMasterRpcSessionIdentity(scope, DomainId("60000000-0000-4000-8000-000000000001"),
        DomainId("70000000-0000-4000-8000-000000000001"), DomainId("a0000000-0000-4000-8000-000000000001"),
        SessionGeneration(1), Revision(1), AuthenticationPurpose.INTERACTIVE)
    val otherActor = original.copy(actorId = DomainId("60000000-0000-4000-8000-000000000002"),
        sessionId = DomainId("a0000000-0000-4000-8000-000000000002"))
}

private class KitchenFenceFixture {
    val owner = Any()
    val fence = LocalMasterRpcSessionFence().also { it.bind(owner) }
    private val sessions = mutableListOf<RpcApplicationSession>()

    fun session(identity: LocalMasterRpcSessionIdentity): RpcApplicationSession = RpcApplicationSession(
        PeerFingerprint("p2f1-" + "a".repeat(52)), identity.actorId.value, identity.sessionId.value, 60.seconds,
    ).also { sessions += it }

    suspend fun dispose() = withContext(NonCancellable) {
        try { fence.awaitSessionRevocation() }
        finally { sessions.forEach { it.revoke() } }
    }
}

private suspend fun withKitchenFence(block: suspend (KitchenFenceFixture) -> Unit) {
    val fixture = KitchenFenceFixture()
    try { block(fixture) }
    finally { fixture.dispose() }
}
