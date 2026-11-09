@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.rpc.independent.identityv1

import com.ukkera.pos.security.LocalAuthenticatedPeer
import com.ukkera.pos.security.SessionGeneration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration

/** Observable caller/source-copy slices only; no reflection into retained owner memory. */
class LocalRpcIdentityCredentialSlicesTest {
    @Test
    fun rpcid014DestructiveSourceActionsNeverReceiveOrClearCallerOwnedCredentialMemory() = runTest {
        val destructive: (com.ukkera.pos.security.LocalGatewayCredentials) -> Unit = { credentials ->
            credentials.bearer.useBytes { it.fill(0) }
            credentials.bearer.destroy()
        }
        identityFixture { f ->
            f.verifier.sourceAction = destructive
            val binding = assertNotNull(f.bind())
            assertIdentityScope(assertNotNull(f.scope(binding)), f)
            f.a.assertIntact()
            binding.retire()
            f.a.assertIntact(); f.verifier.assertReleasedSourceCopies()
        }
        identityFixture { f ->
            f.verifier.sourceAction = destructive
            f.verifier.identity = null
            assertNull(f.bind())
            f.a.assertIntact(); f.verifier.assertReleasedSourceCopies()
        }
        identityFixture { f ->
            val binding = assertNotNull(f.bind())
            f.verifier.sourceAction = destructive
            f.verifier.failure = IllegalStateException("synthetic source failure")
            assertNull(f.scope(binding))
            f.a.assertIntact()
            binding.retire()
            f.a.assertIntact(); f.verifier.assertReleasedSourceCopies()
        }
    }

    @Test
    fun rpcid015CallerClearOrSuccessorSubstitutionCannotChangePrivateOriginalVerification() = runTest {
        for (substitute in listOf(false, true)) identityFixture { f ->
            val binding = assertNotNull(f.bind())
            if (substitute) f.a.substituteCaller(f.b) else f.a.clearCaller()
            assertFalse(identityCredentialUsable(f.a.bearer))
            if (substitute) assertTrue(f.a.bytes.contentEquals(f.b.bytes), "Caller substitution not established")
            else assertTrue(f.a.bytes.all { it == 0.toByte() }, "Caller clear not established")
            assertIdentityScope(assertNotNull(f.scope(binding)), f)
            f.verifier.assertOriginalLabel()
            f.verifier.identity = null
            assertNull(f.scope(binding), "Original revocation is not bypassed by caller mutation")
            f.verifier.assertOriginalLabel()
            binding.retire()
            assertNull(f.scope(binding))
            f.b.assertIntact(); f.verifier.assertReleasedSourceCopies()
        }
        // Retained owner release is not inferred from the handle refusal/source-copy cleanup.
    }

    @Test
    fun rpcid016SupportedPublicRepresentationsStayRedactedBeforeRefusalAndAfterRetirement() = runTest {
        identityFixture { f ->
            val binding = assertNotNull(f.bind())
            val scope = assertNotNull(f.scope(binding))
            fun representations() = listOf(binding.toString(), scope.toString(), scope.session.toString(),
                f.credentials.toString(), f.a.bearer.toString(), f.physicalPeer.toString())
            assertIdentityRedacted(representations(), f)
            f.verifier.identity = null
            assertNull(f.scope(binding))
            assertIdentityRedacted(representations(), f)
            binding.retire()
            assertFalse(scope.session.isActive)
            assertNull(f.scope(binding))
            assertIdentityRedacted(representations(), f)
            f.a.assertIntact(); f.verifier.assertReleasedSourceCopies()
        }
        // No application serialization/export API is exposed for this binding/retained bearer.
        // Do not invent a serializer, restore operation or private-memory observation.
    }

    @Test
    fun rpcid017ObservableDisposableCopiesReleaseAfterFailedCreationAndJoinedRetirementCuts() = runTest {
        for (failure in listOf("principal refusal", "peer mismatch", "invalid lifetime", "principal error", "peer error"))
            identityFixture { f ->
                when (failure) {
                    "principal refusal" -> f.verifier.identity = null
                    "peer mismatch" -> f.provenance.current = LocalAuthenticatedPeer(f.connection, IdentityFrozen.scopeA,
                        IdentityFrozen.id("DEVICE-B"), f.physicalPeer.publicKeyIdentifier, f.physicalPeer.publicFingerprint, f.lifetime::current)
                    "principal error" -> f.verifier.failure = IllegalStateException("synthetic private source error")
                    "peer error" -> f.provenance.failure = IllegalStateException("synthetic private peer error")
                }
                val result = f.bind(duration = if (failure == "invalid lifetime") Duration.ZERO else IdentityFrozen.sdkLifetime)
                assertNull(result)
                f.a.assertIntact(); f.verifier.assertReleasedSourceCopies()
            }
        identityFixture { f ->
            val barrier = IdentityBarrier(finishAfterCancellation = true)
            f.verifier.barrier = barrier
            var cancelledAtBindingCall = false
            var completedNormally = false
            val work = async {
                try { f.bind().also { completedNormally = true } }
                catch (cancelled: CancellationException) { cancelledAtBindingCall = true; throw cancelled }
            }
            runCurrent(); assertTrue(barrier.entered.isCompleted)
            work.cancel(); barrier.release.complete(Unit); runCurrent(); work.join()
            assertTrue(cancelledAtBindingCall)
            assertFalse(completedNormally)
            assertFailsWith<CancellationException> { work.await() }
            f.a.assertIntact(); f.verifier.assertReleasedSourceCopies()
        }
        for (inFlight in listOf(false, true)) identityFixture { f ->
            val binding = assertNotNull(f.bind())
            val scope = assertNotNull(f.scope(binding))
            val barrier = IdentityBarrier()
            if (inFlight) f.verifier.barrier = barrier
            val work = if (inFlight) async { f.scope(binding) } else null
            if (inFlight) { runCurrent(); assertTrue(barrier.entered.isCompleted) }
            binding.retire()
            barrier.release.complete(Unit)
            if (work != null) { assertNull(work.await()); work.join() }
            assertFalse(scope.session.isActive)
            assertNull(f.scope(binding))
            f.a.assertIntact(); f.verifier.assertReleasedSourceCopies()
        }
        // ALL retained-owned usability/release remains UNBOUND: no qualified public owner probe.
        // This partial disposable-copy observation is not a PASS for RPCID-017's full oracle.
    }

    @Test
    fun rpcid018RetiringAndMutatingADoesNotClearRetagOrAdoptIndependentBindingB() = runTest {
        val bIdentity = IdentityFrozen.originalIdentity().copy(actorId = IdentityFrozen.id("EMP-B"),
            deviceId = IdentityFrozen.id("DEVICE-B"), sessionId = IdentityFrozen.id("SESSION-B"), sessionGeneration = SessionGeneration(8))
        identityFixture { a ->
            val aBinding = assertNotNull(a.bind())
            val aScope = assertNotNull(a.scope(aBinding))
            identityFixture(IdentityUnitFixture(identity = bIdentity, deviceLabel = "DEVICE-B", connectionLabel = "CONNECTION-B",
                fingerprintLabel = "FP-B", credentialLabel = "CRED-B")) { b ->
                val bBinding = assertNotNull(b.bind())
                val bScope = assertNotNull(b.scope(bBinding))
                assertIdentityScope(bScope, b, bIdentity)
                assertNotSame(aScope.session, bScope.session)
                aBinding.retire(); a.a.clearCaller()
                assertNull(a.scope(aBinding))
                assertFalse(aScope.session.isActive)
                assertIdentityScope(assertNotNull(b.scope(bBinding)), b, bIdentity)
                b.verifier.assertOriginalLabel("CRED-B")
                b.b.assertIntact(); b.verifier.assertReleasedSourceCopies()
            }
        }
        // Private retained-copy release/native lifetime qualification remains separate.
    }
}
