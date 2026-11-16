package com.ukkera.pos.rpc

import com.ukkera.pos.rpc.independent.physicalsession.physicalSessionFixture
import dev.p2pkit.rpc.RpcPhysicalSessionClosure
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Self-authored, NOT independent. Synthetic lifetime DATA using the unchanged frozen
 * unit fixture only; not native admission/teardown, actual transport or whole LAN evidence.
 * Expectations frozen separately before execution in unpublished cleanup support v1.0.0. */
class UnpublishedApplicationCleanupSupportingTest {
    @Test fun refusedPrivateCandidateLeavesPhysicalConnectionForFreshAuthentication() = runTest {
        physicalSessionFixture { f ->
            val candidate = assertNotNull(f.bind())
            val observedPrivateScope = assertNotNull(f.scope(candidate))
            f.verifier.identity = null
            assertNull(f.scope(candidate))
            candidate.releaseUnpublishedApplicationSession()
            assertFalse(observedPrivateScope.session.isActive)
            assertTrue(f.sdkA.capability.isConnected)
            assertEquals(RpcPhysicalSessionClosure.Open, f.sdkA.capability.closure.value)
            assertTrue(f.sdkA.closeTargets.isEmpty())
            f.verifier.identity = f.originalIdentity
            val fresh = assertNotNull(f.bind())
            assertNotNull(f.scope(fresh))
            assertNull(f.scope(candidate))
            f.callerA.assertIntact()
            f.verifier.assertReleasedSourceCopies()
        }
    }

    @Test fun candidateOnlyReleaseCannotRevokeAnotherApplicationSessionOrReviveItself() = runTest {
        physicalSessionFixture { f ->
            val candidate = assertNotNull(f.bind())
            val other = assertNotNull(f.bind())
            val candidateScope = assertNotNull(f.scope(candidate))
            val otherScope = assertNotNull(f.scope(other))
            assertNotSame(candidateScope.session, otherScope.session)
            repeat(2) { candidate.releaseUnpublishedApplicationSession() }
            assertFalse(candidateScope.session.isActive)
            assertTrue(otherScope.session.isActive)
            assertNotNull(f.scope(other))
            assertNull(f.scope(candidate))
            assertTrue(f.sdkA.capability.isConnected)
            assertTrue(f.sdkA.closeTargets.isEmpty())
            f.callerA.assertIntact()
            f.verifier.assertReleasedSourceCopies()
        }
    }

    @Test fun explicitPhysicalRetirementStillClosesExactLifetimeAfterCandidateRelease() = runTest {
        physicalSessionFixture { f ->
            val candidate = assertNotNull(f.bind())
            val privateScope = assertNotNull(f.scope(candidate))
            candidate.releaseUnpublishedApplicationSession()
            assertFalse(privateScope.session.isActive)
            assertTrue(f.sdkA.capability.isConnected)
            candidate.retire()
            assertEquals(RpcPhysicalSessionClosure.Closed, f.sdkA.capability.closure.value)
            assertFalse(f.sdkA.capability.isConnected)
            assertEquals(listOf(f.sdkA), f.sdkA.closeTargets)
            assertEquals(RpcPhysicalSessionClosure.Open, f.sdkB.capability.closure.value)
            assertNull(f.scope(candidate))
        }
    }
}
