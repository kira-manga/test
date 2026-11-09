@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.rpc.independent.physicalsession

import com.ukkera.pos.rpc.LocalRpcIdentityBinding
import com.ukkera.pos.rpc.independent.identityv1.IdentityBarrier
import dev.p2pkit.rpc.RpcAuthorizationOperation
import dev.p2pkit.rpc.RpcAuthorizationScope
import dev.p2pkit.rpc.RpcPhysicalSessionClosure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Actual binding/SDK close wrapper with synthetic close outcomes; no native teardown confirmation. */
class LocalRpcPhysicalSessionRetirementSlicesTest {
    @Test
    fun phs010RetirementRefusesEveryEntryWhileExactSyntheticClosePending() = runTest {
        physicalSessionFixture { f ->
            val binding = assertNotNull(f.bind())
            val scope = assertNotNull(f.scope(binding))
            val close = IdentityBarrier()
            f.sdkA.closeBarrier = close
            val retirement = async { binding.retire() }
            runCurrent()
            assertTrue(close.entered.isCompleted)
            assertFalse(retirement.isCompleted)
            assertEquals(RpcPhysicalSessionClosure.Closing, binding.physicalClosure!!.value)
            assertRetiredPhysicalEntries(f, binding, scope)
            assertEquals(RpcPhysicalSessionClosure.Open, f.sdkB.capability.closure.value)
            f.callerA.assertIntact()
            f.verifier.assertReleasedSourceCopies()
            close.release.complete(Unit)
            retirement.await()
            assertEquals(RpcPhysicalSessionClosure.Closed, binding.physicalClosure!!.value)
            assertRetiredPhysicalEntries(f, binding, scope)
        }
        // Private retained erasure is not observable. Closing/Closed here wraps a synthetic action.
    }

    @Test
    fun phs010RetirementWinningLateAuthorizerRefusesBeforePhysicalAcknowledgement() = runTest {
        physicalSessionFixture { f ->
            val binding = assertNotNull(f.bind())
            val scope = assertNotNull(f.scope(binding))
            val verification = IdentityBarrier()
            f.verifier.barrier = verification
            val authorization = async { f.scope(binding) }
            runCurrent()
            assertTrue(verification.entered.isCompleted)
            val close = IdentityBarrier()
            f.sdkA.closeBarrier = close
            val retirement = async { binding.retire() }
            runCurrent()
            assertTrue(close.entered.isCompleted)
            assertFalse(scope.session.isActive)
            assertEquals(RpcPhysicalSessionClosure.Closing, binding.physicalClosure!!.value)
            verification.release.complete(Unit)
            assertNull(authorization.await())
            assertRetiredPhysicalEntries(f, binding, scope)
            f.verifier.assertReleasedSourceCopies()
            f.callerA.assertIntact()
            close.release.complete(Unit)
            retirement.await()
            assertEquals(RpcPhysicalSessionClosure.Closed, binding.physicalClosure!!.value)
        }
    }

    @Test
    fun phs011RepeatedConfirmedSyntheticCloseRetainsExactOriginalTarget() = runTest {
        physicalSessionFixture { f ->
            val bindingA = assertNotNull(f.bind())
            val scopeA = assertNotNull(f.scope(bindingA))
            f.verifier.identity = f.successorIdentity
            f.verifier.acceptedLabel = "CRED-B"
            val contextB = f.authenticationData(f.sdkB.capability)
            val bindingB = assertNotNull(f.bind(contextB, f.successorCredentials))
            val scopeB = assertNotNull(f.scope(bindingB, contextB))
            assertOriginalPhysicalScope(scopeB, f, f.successorIdentity)
            assertEquals(f.sdkA.capability.generation, f.sdkB.capability.generation)
            assertTrue(f.sdkA.capability.peer == f.sdkB.capability.peer)
            bindingA.retire()
            bindingA.retire()
            assertEquals(RpcPhysicalSessionClosure.Closed, bindingA.physicalClosure!!.value)
            assertEquals(RpcPhysicalSessionClosure.Open, bindingB.physicalClosure!!.value)
            assertFalse(scopeA.session.isActive)
            assertTrue(scopeB.session.isActive)
            assertTrue(f.sdkB.capability.isConnected)
            assertTrue(f.sdkA.closeTargets.isNotEmpty())
            assertTrue(f.sdkA.closeTargets.all { it === f.sdkA })
            assertTrue(f.sdkB.closeTargets.isEmpty(), "Original retirement targeted the successor")
            assertOriginalPhysicalScope(assertNotNull(f.scope(bindingB, contextB)), f, f.successorIdentity)
            assertNull(f.scope(bindingA))
            f.callerA.assertIntact()
            f.callerB.assertIntact()
            f.verifier.assertReleasedSourceCopies()
        }
        // No exact callback count is imposed; repeated observation of the same outcome is permitted.
    }

    @Test
    fun phs012RepeatedRetainedSyntheticFailureNeverConfirmsOrRetargets() = runTest {
        physicalSessionFixture { f ->
            val binding = assertNotNull(f.bind())
            val scope = assertNotNull(f.scope(binding))
            val failure = SyntheticRetainedCloseFailure("SYNTHETIC L1 retained exact-A close failure")
            f.sdkA.closeFailure = failure
            repeat(2) {
                assertTrue(observeRetirementFailure(binding) === failure,
                    "Retirement did not preserve the declared terminal failure")
                assertEquals(RpcPhysicalSessionClosure.Incomplete, binding.physicalClosure!!.value)
                assertRetiredPhysicalEntries(f, binding, scope)
            }
            assertTrue(f.sdkA.closeTargets.isNotEmpty())
            assertTrue(f.sdkA.closeTargets.all { it === f.sdkA })
            assertTrue(f.sdkB.closeTargets.isEmpty())
            assertEquals(RpcPhysicalSessionClosure.Open, f.sdkB.capability.closure.value)
            assertTrue(f.sdkB.capability.isConnected)
            f.callerA.assertIntact()
            f.verifier.assertReleasedSourceCopies()
        }
    }

    @Test
    fun phs012SuccessorOnlyAcknowledgementCannotConfirmPendingOriginal() = runTest {
        physicalSessionFixture { f ->
            val binding = assertNotNull(f.bind())
            val scope = assertNotNull(f.scope(binding))
            val close = IdentityBarrier()
            f.sdkA.closeBarrier = close
            val first = async { binding.retire() }
            runCurrent()
            assertTrue(close.entered.isCompleted)
            f.sdkB.capability.close()
            assertEquals(RpcPhysicalSessionClosure.Closed, f.sdkB.capability.closure.value)
            assertEquals(RpcPhysicalSessionClosure.Closing, binding.physicalClosure!!.value)
            assertRetiredPhysicalEntries(f, binding, scope)
            val repeated = async { binding.retire() }
            runCurrent()
            assertFalse(first.isCompleted)
            assertFalse(repeated.isCompleted)
            assertEquals(RpcPhysicalSessionClosure.Closing, binding.physicalClosure!!.value)
            assertTrue(f.sdkA.closeTargets.all { it === f.sdkA })
            close.release.complete(Unit)
            first.await()
            repeated.await()
            assertEquals(RpcPhysicalSessionClosure.Closed, binding.physicalClosure!!.value)
            assertRetiredPhysicalEntries(f, binding, scope)
            f.callerA.assertIntact()
            f.verifier.assertReleasedSourceCopies()
        }
        // B's synthetic acknowledgement is distinct from A's; no native close proof follows.
    }

    @Test
    fun phs013CallerCancellationPreservesEventualSyntheticCleanupOutcome() = runTest {
        for (terminalFailure in listOf(false, true)) physicalSessionFixture { f ->
            val binding = assertNotNull(f.bind())
            val scope = assertNotNull(f.scope(binding))
            val close = IdentityBarrier()
            val failure = if (terminalFailure)
                SyntheticRetainedCloseFailure("SYNTHETIC L1 retained exact-A cancellation close failure") else null
            f.sdkA.closeBarrier = close
            f.sdkA.closeFailure = failure
            var returnedNormally = false
            var cancellationAtCall = false
            var failureAtCall: Exception? = null
            val caller = launch {
                try {
                    binding.retire()
                    returnedNormally = true
                } catch (_: CancellationException) {
                    cancellationAtCall = true
                } catch (caught: Exception) {
                    // Preserve the observed call failure without cancelling the independent observer.
                    failureAtCall = caught
                }
            }
            runCurrent()
            assertTrue(close.entered.isCompleted)
            caller.cancel()
            runCurrent()
            assertEquals(RpcPhysicalSessionClosure.Closing, binding.physicalClosure!!.value)
            assertRetiredPhysicalEntries(f, binding, scope)
            val observer = async { observeRetirementFailure(binding) }
            runCurrent()
            assertFalse(observer.isCompleted)
            close.release.complete(Unit)
            caller.join()
            val observedFailure = observer.await()
            assertTrue(caller.isCancelled)
            assertFalse(returnedNormally)
            if (terminalFailure) {
                assertTrue(observedFailure === failure)
                assertEquals(RpcPhysicalSessionClosure.Incomplete, binding.physicalClosure!!.value)
                assertTrue(cancellationAtCall || failureAtCall === failure)
                assertTrue(observeRetirementFailure(binding) === failure)
            } else {
                assertNull(observedFailure)
                assertTrue(cancellationAtCall)
                assertNull(failureAtCall)
                assertEquals(RpcPhysicalSessionClosure.Closed, binding.physicalClosure!!.value)
                assertNull(observeRetirementFailure(binding))
            }
            assertRetiredPhysicalEntries(f, binding, scope)
            assertTrue(f.sdkA.closeTargets.isNotEmpty())
            assertTrue(f.sdkA.closeTargets.all { it === f.sdkA })
            assertTrue(f.sdkB.closeTargets.isEmpty())
            assertEquals(RpcPhysicalSessionClosure.Open, f.sdkB.capability.closure.value)
            f.callerA.assertIntact()
            f.verifier.assertReleasedSourceCopies()
        }
    }
}

private suspend fun observeRetirementFailure(binding: LocalRpcIdentityBinding): Exception? = try {
    binding.retire()
    null
} catch (failure: Exception) { failure }

private suspend fun assertRetiredPhysicalEntries(
    fixture: PhysicalSessionUnitFixture,
    binding: LocalRpcIdentityBinding,
    scope: RpcAuthorizationScope,
) {
    assertFalse(scope.session.isActive)
    assertNull(fixture.scope(binding))
    assertNull(binding.currentOriginalPrincipal(fixture.handlerData(scope, fixture.sdkA.capability)))
    for (operation in listOf(RpcAuthorizationOperation.Execute, RpcAuthorizationOperation.Success,
        RpcAuthorizationOperation.Status)) {
        assertNull(binding.currentOriginalPrincipal(fixture.authorizationData(scope, operation = operation)))
    }
}
