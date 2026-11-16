package com.ukkera.pos.rpc.independent.physicalsession

import dev.p2pkit.core.PeerId
import dev.p2pkit.core.PeerIdentity
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Self-authored, NOT independent. Fixed protected-handler supporting inputs v1.
 * Controlled SDK DATA/provenance only, no native admission or query authority. The
 * protected endpoint must freshly authenticate the borrowed original after its queue. */
class ProtectedHandlerCredentialLoanSupportingTest {
    @Test fun exactHandlerLoanCopiesCredentialDataWithoutAnotherVerifierRead() = runTest {
        physicalSessionFixture { f ->
            val binding = assertNotNull(f.bind()); val scope = assertNotNull(f.scope(binding))
            val before = f.verifier.observations.size
            val loan = assertNotNull(binding.retainedCredentialsForProtectedOriginalCheck(f.handlerData(scope, f.sdkA.capability)))
            try {
                assertEquals(before, f.verifier.observations.size)
                assertTrue(f.credentials.bearer.useBytes { a -> loan.bearer.useBytes { b -> a.contentEquals(b) } })
            } finally { loan.bearer.destroy() }
            f.callerA.assertIntact()
        }
    }

    @Test fun sameIdentifierSdkTwinMissingPhysicalAndSuccessorCannotBorrow() = runTest {
        physicalSessionFixture { f ->
            val binding = assertNotNull(f.bind()); val scope = assertNotNull(f.scope(binding))
            for (context in listOf(f.handlerData(), f.handlerData(scope),
                f.handlerData(sameIdentifierDifferentSdkSession(scope), f.sdkA.capability),
                f.handlerData(scope, f.sdkB.capability),
                f.handlerData(scope, f.sdkA.capability, PeerIdentity(PeerId("supporting-other-handler"), f.peer.fingerprint))))
                assertNull(binding.retainedCredentialsForProtectedOriginalCheck(context))
            f.callerA.assertIntact()
        }
    }

    @Test fun retiredBindingAndLostNativeLifetimeCannotBeReopenedByHandlerData() = runTest {
        for (retire in listOf(false, true)) physicalSessionFixture { f ->
            val binding = assertNotNull(f.bind()); val scope = assertNotNull(f.scope(binding))
            if (retire) binding.retire() else f.nativeLifetimeA.end()
            repeat(2) { assertNull(binding.retainedCredentialsForProtectedOriginalCheck(f.handlerData(scope, f.sdkA.capability))) }
            f.callerA.assertIntact()
        }
    }

    @Test fun aCredentialLoanCannotEstablishTheCurrentOriginalPrincipal() = runTest {
        physicalSessionFixture { f ->
            val binding = assertNotNull(f.bind()); val scope = assertNotNull(f.scope(binding))
            val context = f.handlerData(scope, f.sdkA.capability)
            f.verifier.identity = f.successorIdentity
            val loan = assertNotNull(binding.retainedCredentialsForProtectedOriginalCheck(context))
            try { assertNull(binding.currentOriginalPrincipal(context)) }
            finally { loan.bearer.destroy() }
            f.callerA.assertIntact()
        }
    }
}
