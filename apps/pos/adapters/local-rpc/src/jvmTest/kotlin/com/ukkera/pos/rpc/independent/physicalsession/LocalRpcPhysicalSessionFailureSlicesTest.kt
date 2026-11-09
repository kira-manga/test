@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.rpc.independent.physicalsession

import com.ukkera.pos.rpc.independent.identityv1.IdentityBarrier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Frozen PHS-14 L1 source/cancellation slices; no production/native error disclosure claim. */
class LocalRpcPhysicalSessionFailureSlicesTest {
    @Test
    fun phs014BindingAndAuthorizationCancellationRemainCancellationAfterLateSourceOutcomes() = runTest {
        for (authorization in listOf(false, true)) for (peerSource in listOf(false, true))
            for (lateOutcome in listOf("original", "null", "ordinary error")) physicalSessionFixture { f ->
                val binding = if (authorization) assertNotNull(f.bind()) else null
                val barrier = IdentityBarrier(finishAfterCancellation = true)
                if (peerSource) f.provenance.barrier = barrier else f.verifier.barrier = barrier
                var returnedNormally = false
                var cancellationAtCall = false
                val work = async {
                    try {
                        val value = if (binding == null) f.bind() else f.scope(binding)
                        returnedNormally = true
                        value
                    } catch (cancelled: CancellationException) {
                        cancellationAtCall = true
                        throw cancelled
                    }
                }
                runCurrent()
                assertTrue(barrier.entered.isCompleted)
                work.cancel()
                when (lateOutcome) {
                    "null" -> if (peerSource) f.provenance.originalPeer = null else f.verifier.identity = null
                    "ordinary error" -> {
                        val failure = IllegalStateException("SYNTHETIC L1 private source diagnostic " +
                            f.callerA.canaries().first())
                        if (peerSource) f.provenance.failure = failure else f.verifier.failure = failure
                    }
                }
                barrier.release.complete(Unit)
                runCurrent()
                work.join()
                assertTrue(cancellationAtCall, "The binding call converted cancellation to a normal result")
                assertFalse(returnedNormally)
                assertFailsWith<CancellationException> { work.await() }
                if (binding != null) {
                    binding.retire()
                    assertNull(f.scope(binding))
                }
                f.callerA.assertIntact()
                f.verifier.assertReleasedSourceCopies()
            }
        // Cancellation itself does not imply automatic retirement. Private retained memory is unobservable.
    }

    @Test
    fun phs014OrdinaryPrincipalProvenanceAndCurrentnessErrorsFailClosedAndRedacted() = runTest {
        for (authorization in listOf(false, true)) for (source in listOf("principal", "provenance", "currentness"))
            physicalSessionFixture { f ->
                val binding = if (authorization) assertNotNull(f.bind()) else null
                val scope = binding?.let { assertNotNull(f.scope(it)) }
                val diagnostic = "SYNTHETIC-L1-private-physical-source-diagnostic"
                val failure = IllegalStateException(diagnostic + " " + f.callerA.canaries().first())
                when (source) {
                    "principal" -> f.verifier.failure = failure
                    "provenance" -> f.provenance.failure = failure
                    else -> f.nativeLifetimeA.failure = failure
                }
                val result = if (binding == null) f.bind() else f.scope(binding)
                assertNull(result)
                assertPhysicalRepresentationsRedacted(f,
                    listOfNotNull(result?.toString(), binding?.toString(), scope?.toString(),
                        scope?.session?.toString(), f.authenticationData().toString(),
                        f.sdkA.capability.toString(), f.credentials.toString(), f.nativeA.toString()),
                    listOf(diagnostic))
                if (binding != null) {
                    binding.retire()
                    assertNull(f.scope(binding))
                }
                f.callerA.assertIntact()
                f.verifier.assertReleasedSourceCopies()
            }
        // Source-disposable copies and public representations are observed, not private retained storage.
    }
}
