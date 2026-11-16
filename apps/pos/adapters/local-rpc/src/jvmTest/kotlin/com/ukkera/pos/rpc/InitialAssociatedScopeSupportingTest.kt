package com.ukkera.pos.rpc

import com.ukkera.pos.rpc.independent.physicalsession.physicalSessionFixture
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds
import kotlin.test.*

/** Self-authored, NOT independent. Public-constructor synthetic SDK lifetime DATA only.
 * These negative controls cannot mint a private native association or prove native serving.
 * The positive associated path is exercised only by the genuine-process fixture. */
class InitialAssociatedScopeSupportingTest {
    @Test fun publicPhysicalBindingHasNoInitialAssociatedScope() = runTest {
        physicalSessionFixture { f ->
            val binding = assertNotNull(f.bind())
            assertNull(binding.takeInitialAssociatedScope(f.authenticationData(), "synthetic/resource"))
            assertNotNull(f.scope(binding))
            f.callerA.assertIntact()
        }
    }

    @Test fun syntheticTwinCannotGetInitialMetadataOrRetargetPublicBinding() = runTest {
        physicalSessionFixture { f ->
            val binding = assertNotNull(f.bind())
            assertNull(binding.takeInitialAssociatedScope(f.authenticationData(f.sdkB.capability), "synthetic/resource"))
            assertNull(f.scope(binding, f.authenticationData(f.sdkB.capability)))
            assertNotNull(f.scope(binding))
        }
    }

    @Test fun failedPrivateMetadataRequestDoesNotSkipNormalFreshIdentityCheck() = runTest {
        physicalSessionFixture { f ->
            val binding = assertNotNull(f.bind())
            assertNull(binding.takeInitialAssociatedScope(f.authenticationData(), "synthetic/resource"))
            f.verifier.identity = null
            assertNull(f.scope(binding))
            f.verifier.identity = f.originalIdentity
            assertNotNull(f.scope(binding))
        }
    }

    @Test fun releasedCandidateCannotMintMetadataAndOtherSessionRemainsCurrent() = runTest {
        physicalSessionFixture { f ->
            val first = assertNotNull(f.bind())
            val other = assertNotNull(f.bind())
            val firstScope = assertNotNull(f.scope(first))
            val otherScope = assertNotNull(f.scope(other))
            first.releaseUnpublishedApplicationSession()
            assertNull(first.takeInitialAssociatedScope(f.authenticationData(), "synthetic/resource"))
            assertFalse(firstScope.session.isActive)
            assertTrue(otherScope.session.isActive)
            assertNotNull(f.scope(other))
            assertTrue(f.sdkA.capability.isConnected)
        }
    }

    @Test fun insufficientAdmissionBudgetDoesNotRevokeAnExistingReplyScope() = runTest {
        physicalSessionFixture { f ->
            val binding = assertNotNull(f.bind(lifetime = 120.seconds))
            val current = assertNotNull(f.scope(binding))
            assertFalse(binding.canAdmitResource(121.seconds))
            assertTrue(current.session.isActive)
            assertNotNull(f.scope(binding))
            assertTrue(f.sdkA.capability.isConnected)
        }
    }

    @Test fun admissionMetadataCannotSubstituteForFreshAuthorityOrReviveRetirement() = runTest {
        physicalSessionFixture { f ->
            val binding = assertNotNull(f.bind(lifetime = 120.seconds))
            assertTrue(binding.canAdmitResource(60.seconds))
            f.verifier.identity = null
            assertTrue(binding.canAdmitResource(60.seconds)) // Inert scheduling, never a grant.
            assertNull(f.scope(binding))
            binding.releaseUnpublishedApplicationSession()
            assertFalse(binding.canAdmitResource(1.seconds))
            assertTrue(f.sdkA.capability.isConnected)
        }
    }
}
