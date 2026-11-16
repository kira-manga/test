package com.ukkera.pos.rpc.independent.physicalsession

import dev.p2pkit.core.PeerId
import dev.p2pkit.core.PeerIdentity
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Self-authored, NOT independent. Supporting input v1 fixed before implementation:
 * actual retained SDK object versus same-ID twin; exact versus foreign physical callback;
 * native lifetime loss, retirement and changed employee authority. Uses the unchanged
 * controlled SDK DATA fixture, not native admission, protected authorization or TCP. */
class ProtectedOperationScopeSupportingTest {
    @Test fun exactRetainedSessionCanAssembleMetadataWithoutAttestingEmployeeAuthority() = runTest {
        physicalSessionFixture { f ->
            val binding = assertNotNull(f.bind())
            val base = assertNotNull(f.scope(binding))
            val resource = "supporting-operation-v1"
            val scoped = assertNotNull(binding.retainedOperationScope(f.authenticationData(), base, resource))
            assertSame(base.session, scoped.session)
            assertEquals(resource, scoped.resource)
            f.verifier.identity = f.successorIdentity
            val data = assertNotNull(binding.retainedOperationScope(f.authenticationData(), base, resource))
            assertNull(binding.currentOriginalPrincipal(f.authorizationData(data)))
            f.callerA.assertIntact()
        }
    }

    @Test fun copiedSdkObjectAndDifferentPhysicalCallbackCannotBorrowOriginal() = runTest {
        physicalSessionFixture { f ->
            val binding = assertNotNull(f.bind())
            val base = assertNotNull(f.scope(binding))
            assertNull(binding.retainedOperationScope(f.authenticationData(), sameIdentifierDifferentSdkSession(base), "supporting-operation-v1"))
            assertNull(binding.retainedOperationScope(f.authenticationData(physical = f.sdkB.capability), base, "supporting-operation-v1"))
            assertNull(binding.retainedOperationScope(f.authenticationData(peer = PeerIdentity(PeerId("supporting-foreign-peer"), f.peer.fingerprint)),
                base, "supporting-operation-v1"))
            assertNotNull(binding.retainedOperationScope(f.authenticationData(), base, "supporting-operation-v1"))
        }
    }

    @Test fun nativeLifetimeLossAndExplicitRetirementDenyFurtherMetadata() = runTest {
        for (retire in listOf(false, true)) physicalSessionFixture { f ->
            val binding = assertNotNull(f.bind())
            val base = assertNotNull(f.scope(binding))
            if (retire) binding.retire() else f.nativeLifetimeA.end()
            repeat(2) { assertNull(binding.retainedOperationScope(f.authenticationData(), base, "supporting-operation-v1")) }
        }
    }
}
