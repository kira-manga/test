@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.rpc.independent.identityv1

import com.ukkera.pos.rpc.LocalRpcIdentityBinding
import com.ukkera.pos.security.LocalAuthenticatedPeer
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.security.VerifiedLocalIdentity
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Frozen unit mismatch/race slices; no internal SDK context, fake host or native admission. */
class LocalRpcIdentityRecheckSlicesTest {
    @Test
    fun rpcid003EightRepresentableOriginalAxesRemainImmutableAcrossRepeatedReplacementAttempts() = runTest {
        val original = IdentityFrozen.originalIdentity()
        val other = IdentityFrozen.scope(IdentityFrozen.identity.getValue("scope_other").jsonObject)
        val changes: List<Pair<String, (VerifiedLocalIdentity) -> VerifiedLocalIdentity>> = listOf(
            "organization" to { value -> value.copy(scope = value.scope.copy(organizationId = other.organizationId)) },
            "company" to { value -> value.copy(scope = value.scope.copy(companyId = other.companyId)) },
            "branch" to { value -> value.copy(scope = value.scope.copy(branchId = other.branchId)) },
            "employee" to { value -> value.copy(actorId = IdentityFrozen.id("EMP-B")) },
            "device" to { value -> value.copy(deviceId = IdentityFrozen.id("DEVICE-B")) },
            "session" to { value -> value.copy(sessionId = IdentityFrozen.id("SESSION-B")) },
            "generation" to { value -> value.copy(sessionGeneration = SessionGeneration(8)) },
            "admission_revision" to { value -> value.copy(admissionRevision = Revision(value.admissionRevision.value + 1)) },
        )
        for ((axis, change) in changes) identityFixture { f ->
            val binding = assertNotNull(f.bind())
            val captured = assertNotNull(f.scope(binding))
            f.verifier.identity = change(original)
            assertNull(f.scope(binding), axis)
            assertNull(f.scope(binding), "Repeated " + axis + " replacement")
            assertEquals(original.actorId.value, captured.session.principal)
            assertEquals(original.sessionId.value, captured.session.sessionId)
            f.verifier.assertOriginalLabel()
            f.a.assertIntact(); f.verifier.assertReleasedSourceCopies()
        }
        // Namespace has no public tuple field; mandatory typed axes cannot be removed.
        // Those exact rows, and mutable returned-description variants, remain unbound.
    }

    @Test
    fun rpcid009MissingEndedExpiredOrChangedPhysicalContextCannotAdoptOriginalLifetime() = runTest {
        val changes: List<(IdentityUnitFixture) -> Unit> = listOf(
            { f -> f.provenance.current = null },
            { f -> f.lifetime.end() },
            { f -> f.lifetime.expiry = IdentityFrozen.nativeExpiry; f.clock.second = IdentityFrozen.nativeExpiry },
            { f -> f.provenance.current = LocalAuthenticatedPeer(f.connection, IdentityFrozen.scopeA,
                IdentityFrozen.id("DEVICE-B"), f.physicalPeer.publicKeyIdentifier, f.physicalPeer.publicFingerprint, f.lifetime::current) },
            { f -> f.provenance.current = LocalAuthenticatedPeer(f.connection, IdentityFrozen.scopeA,
                IdentityFrozen.id("DEVICE-A"), f.physicalPeer.publicKeyIdentifier, IdentityFrozen.fingerprint("FP-B").value, f.lifetime::current) },
            { f ->
                f.lifetime.end()
                val next = ControlledPhysicalLifetime(f.clock)
                f.provenance.current = LocalAuthenticatedPeer(IdentityFrozen.id("CONNECTION-A2"), IdentityFrozen.scopeA,
                    IdentityFrozen.id("DEVICE-A"), f.physicalPeer.publicKeyIdentifier, f.physicalPeer.publicFingerprint, next::current)
            },
            { f -> f.provenance.current = LocalAuthenticatedPeer(IdentityFrozen.id("CONNECTION-A2"), IdentityFrozen.scopeA,
                IdentityFrozen.id("DEVICE-A"), f.physicalPeer.publicKeyIdentifier, f.physicalPeer.publicFingerprint, f.lifetime::current) },
        )
        for (change in changes) identityFixture { f ->
            val binding = assertNotNull(f.bind())
            assertIdentityScope(assertNotNull(f.scope(binding)), f)
            change(f)
            assertNull(f.scope(binding))
            assertNull(f.scope(binding))
            f.a.assertIntact(); f.verifier.assertReleasedSourceCopies()
        }
        // End/current=false share the public isCurrent observation; actual native continuity stays unbound.
    }

    @Test
    fun rpcid010LostContinuityDuringPrincipalVerificationSuppressesLateScopePublication() = runTest {
        for (authorization in listOf(false, true)) for (expiry in listOf(false, true)) identityFixture { f ->
            if (expiry) f.lifetime.expiry = IdentityFrozen.nativeExpiry
            val binding = if (authorization) assertNotNull(f.bind()) else null
            val barrier = IdentityBarrier()
            f.verifier.barrier = barrier
            val work = async { if (binding == null) f.bind() else f.scope(binding) }
            runCurrent()
            assertTrue(barrier.entered.isCompleted)
            if (expiry) f.clock.second = IdentityFrozen.nativeExpiry else {
                f.lifetime.end()
                if (authorization) {
                    val successor = ControlledPhysicalLifetime(f.clock)
                    f.provenance.current = LocalAuthenticatedPeer(IdentityFrozen.id("CONNECTION-A2"), IdentityFrozen.scopeA,
                        IdentityFrozen.id("DEVICE-A"), f.physicalPeer.publicKeyIdentifier, f.physicalPeer.publicFingerprint, successor::current)
                }
            }
            barrier.release.complete(Unit)
            assertNull(work.await())
            f.a.assertIntact(); f.verifier.assertReleasedSourceCopies()
        }
    }

    @Test
    fun rpcid011CancellationRemainsCancellationAfterLateSuccessNullOrOrdinaryErrorAtEitherSource() = runTest {
        for (authorization in listOf(false, true)) for (peerSource in listOf(false, true))
            for (lateOutcome in listOf("success", "null", "ordinary error")) identityFixture { f ->
                val existing = if (authorization) assertNotNull(f.bind()) else null
                val barrier = IdentityBarrier(finishAfterCancellation = true)
                if (peerSource) f.provenance.barrier = barrier else f.verifier.barrier = barrier
                var completedNormally = false
                var cancellationAtBindingCall = false
                val work = async {
                    try {
                        val result = if (existing == null) f.bind() else f.scope(existing)
                        completedNormally = true
                        result
                    } catch (cancelled: CancellationException) {
                        cancellationAtBindingCall = true
                        throw cancelled
                    }
                }
                runCurrent()
                assertTrue(barrier.entered.isCompleted)
                work.cancel()
                if (lateOutcome == "null") {
                    if (peerSource) f.provenance.current = null else f.verifier.identity = null
                } else if (lateOutcome == "ordinary error") {
                    val error = IllegalStateException("synthetic private diagnostic " + f.a.canaries().first())
                    if (peerSource) f.provenance.failure = error else f.verifier.failure = error
                }
                barrier.release.complete(Unit)
                runCurrent()
                work.join()
                assertTrue(cancellationAtBindingCall, "Binding call converted owner cancellation into normal completion")
                assertFalse(completedNormally, "Cancelled work returned normal refusal or acceptance")
                assertFailsWith<CancellationException> { work.await() }
                existing?.retire()
                if (existing != null) assertNull(f.scope(existing))
                f.a.assertIntact(); f.verifier.assertReleasedSourceCopies()
            }
        // Explicit retirement is separate from cancellation; no assumption of automatic retirement.
    }

    @Test
    fun rpcid012LocalPrincipalPeerAndCurrentLifetimeErrorsRefuseWithoutPublicDiagnostics() = runTest {
        for (authorization in listOf(false, true)) for (source in listOf("principal", "peer", "current lifetime"))
            identityFixture { f ->
                val binding = if (authorization) assertNotNull(f.bind()) else null
                val scope = binding?.let { assertNotNull(f.scope(it)) }
                val diagnostic = "synthetic-private-principal-stack-diagnostic"
                val failure = IllegalStateException(diagnostic + " " + f.a.canaries().first() + " " + f.sdkPeer.fingerprint!!.value)
                when (source) {
                    "principal" -> f.verifier.failure = failure
                    "peer" -> f.provenance.failure = failure
                    else -> f.lifetime.failure = failure
                }
                val result = if (binding == null) f.bind() else f.scope(binding)
                assertNull(result)
                assertIdentityRedacted(listOfNotNull(result?.toString(), binding?.toString(), scope?.toString(),
                    scope?.session?.toString(), f.credentials.toString(), f.physicalPeer.toString()), f,
                    listOf(diagnostic, f.sdkPeer.fingerprint!!.value))
                f.a.assertIntact(); f.verifier.assertReleasedSourceCopies()
            }
    }

    @Test
    fun rpcid013RetirementIsIrreversibleAcrossLateVerifierAndThirtyTwoIndependentCycles() = runTest {
        identityFixture { f ->
            val binding = assertNotNull(f.bind())
            val scope = assertNotNull(f.scope(binding))
            binding.retire()
            assertFalse(scope.session.isActive)
            f.verifier.identity = IdentityFrozen.originalIdentity()
            f.provenance.current = f.physicalPeer
            assertNull(f.scope(binding)); assertNull(f.scope(binding))
            f.a.assertIntact(); f.verifier.assertReleasedSourceCopies()
        }
        identityFixture { f ->
            val binding = assertNotNull(f.bind())
            val scope = assertNotNull(f.scope(binding))
            val barrier = IdentityBarrier()
            f.verifier.barrier = barrier
            val work = async { f.scope(binding) }
            runCurrent(); assertTrue(barrier.entered.isCompleted)
            binding.retire()
            barrier.release.complete(Unit)
            assertNull(work.await())
            assertFalse(scope.session.isActive)
            assertNull(f.scope(binding))
            f.a.assertIntact(); f.verifier.assertReleasedSourceCopies()
        }
        val retired = mutableListOf<Pair<IdentityUnitFixture, LocalRpcIdentityBinding>>()
        repeat(32) {
            identityFixture { f ->
                val binding = assertNotNull(f.bind())
                assertIdentityScope(assertNotNull(f.scope(binding)), f)
                binding.retire()
                assertNull(f.scope(binding))
                f.a.assertIntact(); f.verifier.assertReleasedSourceCopies()
                retired += f to binding
            }
        }
        for ((fixture, binding) in retired) assertNull(fixture.scope(binding))
        // Source-disposable cleanup is observable. Complete retained-owner release remains unbound.
    }
}
