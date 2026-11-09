@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.rpc.independent.physicalsession

import com.ukkera.pos.rpc.LocalRpcAssociatedPhysicalPeer
import com.ukkera.pos.rpc.LocalRpcAssociatedPhysicalPeerProvenance
import com.ukkera.pos.rpc.LocalRpcIdentityBinding
import com.ukkera.pos.rpc.independent.identityv1.CallerIdentitySecret
import com.ukkera.pos.rpc.independent.identityv1.ControlledIdentityVerifier
import com.ukkera.pos.rpc.independent.identityv1.IdentityBarrier
import com.ukkera.pos.rpc.independent.identityv1.IdentityClock
import com.ukkera.pos.rpc.independent.identityv1.IdentityFrozen
import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.security.TrustedPrincipalFactory
import com.ukkera.pos.security.VerifiedLocalIdentity
import com.ukkera.pos.types.Revision
import dev.p2pkit.core.PeerId
import dev.p2pkit.core.PeerIdentity
import dev.p2pkit.rpc.RpcAuthenticationContext
import dev.p2pkit.rpc.RpcPhysicalSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Frozen MRPA-S01..S03 supporting source-failure observations only.
 * Synthetic SDK capabilities are DATA, not authenticated Host callbacks. No qualified database
 * association, native receipt, admission history, resource authority or business store is supplied.
 * The tuple/DATA matrix, native preservation and private retained-copy release remain unobserved.
 */
class LocalRpcAssociatedPhysicalPeerFailureSupportingTest {
    @Test
    fun mrpaS01MissingAssociationRefusesSupportingPhysicalBinding() = runTest {
        associatedPeerFailureFixture { f ->
            val result = f.bind()
            assertNull(result)
            f.assertUnavailableSourceObservations()
        }
    }

    @Test
    fun mrpaS02OrdinaryAssociationSourceErrorFailsClosedWithoutPublicDiagnostic() = runTest {
        associatedPeerFailureFixture { f ->
            f.source.failure = IllegalStateException(
                AssociatedPeerFailureInputs.privateDiagnostic + " " + f.callerA.canaries().first(),
            )
            val result = f.bind()
            assertNull(result)
            f.assertUnavailableSourceObservations()
            f.assertPublicRepresentationsRedacted(
                listOf(result.toString(), f.credentials.toString(), f.context.toString(),
                    f.sdkA.capability.toString()),
                listOf(AssociatedPeerFailureInputs.privateDiagnostic),
            )
            // These are public in-memory representations; no transport serialization is exercised.
        }
    }

    @Test
    fun mrpaS03CallerCancellationWhileAssociationSourceIsPendingRemainsCancellation() = runTest {
        for (lateError in listOf(false, true)) associatedPeerFailureFixture { f ->
            val barrier = IdentityBarrier(finishAfterCancellation = true)
            f.source.barrier = barrier
            var returnedNormally = false
            var cancellationAtCall = false
            val work = async {
                try {
                    f.bind().also { returnedNormally = true }
                } catch (cancelled: CancellationException) {
                    cancellationAtCall = true
                    throw cancelled
                }
            }
            runCurrent()
            assertTrue(barrier.entered.isCompleted, "The controlled association source was not pending")
            work.cancel()
            if (lateError) f.source.failure = IllegalStateException(AssociatedPeerFailureInputs.privateDiagnostic)
            barrier.release.complete(Unit)
            runCurrent()
            work.join()
            assertTrue(cancellationAtCall, "The binding call converted cancellation to a normal result")
            assertFalse(returnedNormally)
            assertFailsWith<CancellationException> { work.await() }
            f.assertUnavailableSourceObservations()
            // No native writer is running. This says nothing about host-owned work or cleanup.
        }
    }

    @Test
    fun mrpaS03AssociationSourceReportedCancellationRemainsCancellation() = runTest {
        associatedPeerFailureFixture { f ->
            f.source.failure = CancellationException("Synthetic association-source cancellation")
            assertFailsWith<CancellationException> { f.bind() }
            f.assertUnavailableSourceObservations()
        }
    }
}

/** Frozen symbolic inputs bound to valid isolated DATA; neither converter authenticates a key. */
private object AssociatedPeerFailureInputs {
    val scope = BranchScope(IdentityFrozen.id("O_A"), IdentityFrozen.id("CO_A"), IdentityFrozen.id("B_A"))
    val peer = PeerIdentity(PeerId("PEER_A"), IdentityFrozen.fingerprint("SFP_A"))
    val originalIdentity = VerifiedLocalIdentity(
        IdentityFrozen.id("EMPLOYEE_A"), scope, IdentityFrozen.id("DEVICE_A"), IdentityFrozen.id("SESSION_A"),
        SessionGeneration(7), Revision(0), AuthenticationPurpose.INTERACTIVE,
    )
    val connection = IdentityFrozen.id("C_A")
    val lifetime = 60_000.milliseconds
    const val privateDiagnostic = "PRIVATE_DIAGNOSTIC_MARKER"
}

/** Controlled unavailable source only; it cannot publish an association or fabricate native evidence. */
private class ControlledUnavailableAssociatedPeerSource : LocalRpcAssociatedPhysicalPeerProvenance {
    val captures = mutableListOf<RpcPhysicalSession>()
    var barrier: IdentityBarrier? = null
    var failure: Throwable? = null

    override suspend fun capture(physicalSession: RpcPhysicalSession): LocalRpcAssociatedPhysicalPeer? {
        captures += physicalSession
        barrier?.pause()
        failure?.let { throw it }
        return null
    }
}

private class AssociatedPeerFailureFixture {
    val callerA = CallerIdentitySecret()
    val callerB = CallerIdentitySecret()
    val sdkA = SyntheticSdkPhysicalLifetime("H_A", AssociatedPeerFailureInputs.peer, generation = 7)
    // Reuse the existing public-constructor-only DATA strategy. No accessibility override or SDK injection.
    val context: RpcAuthenticationContext = RpcAuthenticationContext::class.java
        .getConstructor(PeerIdentity::class.java, RpcPhysicalSession::class.java)
        .newInstance(sdkA.peer, sdkA.capability)
    val credentials = LocalGatewayCredentials(AssociatedPeerFailureInputs.connection, callerA.bearer)
    val source = ControlledUnavailableAssociatedPeerSource()
    val verifier = ControlledIdentityVerifier(callerA, callerB, IdentityClock(second = 0),
        AssociatedPeerFailureInputs.originalIdentity, "CRED-A")
    val factory = TrustedPrincipalFactory(verifier)
    private val bindings = mutableListOf<LocalRpcIdentityBinding>()

    suspend fun bind(): LocalRpcIdentityBinding? = LocalRpcIdentityBinding.bindAssociated(
        context, credentials, factory, source, AssociatedPeerFailureInputs.lifetime,
    ).also { if (it != null) bindings += it }

    fun assertUnavailableSourceObservations() {
        assertTrue(source.captures.isNotEmpty(), "The controlled association source was not consulted")
        assertTrue(source.captures.all { it === sdkA.capability }, "A different synthetic lifetime was consulted")
        assertTrue(bindings.isEmpty(), "An accepting physical binding was published")
        callerA.assertIntact()
        callerB.assertIntact()
        // Private retained memory and all native/admission/business effects are outside this fixture.
    }

    fun assertPublicRepresentationsRedacted(representations: List<String>, diagnostics: List<String>) {
        val privateValues = callerA.canaries() + callerB.canaries() + diagnostics
        for (representation in representations) for (privateValue in privateValues)
            assertFalse(representation.contains(privateValue), "Public output exposed a synthetic private canary")
    }

    suspend fun close() = withContext(NonCancellable) {
        source.barrier?.release?.complete(Unit)
        try {
            for (binding in bindings) binding.retire()
        } finally {
            callerA.close()
            callerB.close()
        }
    }
}

private suspend fun associatedPeerFailureFixture(block: suspend (AssociatedPeerFailureFixture) -> Unit) {
    val fixture = AssociatedPeerFailureFixture()
    try {
        block(fixture)
    } finally {
        fixture.close()
    }
}
