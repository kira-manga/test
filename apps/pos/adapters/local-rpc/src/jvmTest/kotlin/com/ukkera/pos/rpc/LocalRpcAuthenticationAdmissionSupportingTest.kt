package com.ukkera.pos.rpc

import com.ukkera.pos.rpc.independent.physicalsession.physicalSessionFixture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.*

/** Self-authored, NOT independent. Synthetic SDK capability DATA tests the actual
 * scheduling coordinator only; no native association or employee authority is minted. */
class LocalRpcAuthenticationAdmissionSupportingTest {
    @Test fun secondActualConnectionDoesNotWaitForFirstNativeAdmission() = runTest {
        physicalSessionFixture { f ->
            val lanes = LocalRpcAuthenticationAdmission(2)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val first = async { lanes.withPhysical(f.sdkA.capability, { -1 }) {
                entered.complete(Unit); release.await(); 1
            } }
            entered.await()
            try {
                assertEquals(2, withTimeout(1_000) { lanes.withPhysical(f.sdkB.capability, { -1 }) { 2 } })
                assertFalse(first.isCompleted)
            } finally { release.complete(Unit) }
            assertEquals(1, first.await())
        }
    }

    @Test fun sameExactConnectionSerializesEvenWhenTheRequestWouldDiffer() = runTest {
        physicalSessionFixture { f ->
            val lanes = LocalRpcAuthenticationAdmission(2)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val secondEntered = CompletableDeferred<Unit>()
            val first = launch { lanes.withPhysical(f.sdkA.capability, {}) { entered.complete(Unit); release.await() } }
            entered.await()
            val second = launch { lanes.withPhysical(f.sdkA.capability, {}) { secondEntered.complete(Unit) } }
            runCurrent(); assertFalse(secondEntered.isCompleted)
            release.complete(Unit); first.join(); second.join(); assertTrue(secondEntered.isCompleted)
        }
    }

    @Test fun cancelledQueuedWaiterCannotOpenAReplacementLanePastItsHolder() = runTest {
        physicalSessionFixture { f ->
            val lanes = LocalRpcAuthenticationAdmission(1)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val first = launch { lanes.withPhysical(f.sdkA.capability, {}) { entered.complete(Unit); release.await() } }
            entered.await()
            val cancelled = launch { lanes.withPhysical(f.sdkA.capability, {}) { error("Cancelled waiter entered") } }
            runCurrent(); cancelled.cancelAndJoin()
            val nextEntered = CompletableDeferred<Unit>()
            val next = launch { lanes.withPhysical(f.sdkA.capability, {}) { nextEntered.complete(Unit) } }
            runCurrent(); assertFalse(nextEntered.isCompleted)
            release.complete(Unit); first.join(); next.join(); assertTrue(nextEntered.isCompleted)
        }
    }

    @Test fun capacityRefusesWithoutWorkAndIsReclaimedOnlyAfterActualRelease() = runTest {
        physicalSessionFixture { f ->
            val lanes = LocalRpcAuthenticationAdmission(1)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val first = launch { lanes.withPhysical(f.sdkA.capability, {}) { entered.complete(Unit); release.await() } }
            entered.await()
            assertEquals("REFUSED", lanes.withPhysical(f.sdkB.capability, { "REFUSED" }) { error("Capacity bypass") })
            release.complete(Unit); first.join()
            assertEquals("ADMITTED", lanes.withPhysical(f.sdkB.capability, { "REFUSED" }) { "ADMITTED" })
        }
    }

    @Test fun failedAdmissionPreservesFailureAndReleasesOnlyItsOwnLane() = runTest {
        physicalSessionFixture { f ->
            val lanes = LocalRpcAuthenticationAdmission(1)
            val original = IllegalStateException("Synthetic scheduling failure")
            val failure = assertFailsWith<IllegalStateException> {
                lanes.withPhysical(f.sdkA.capability, { Unit }) { throw original }
            }
            assertSame(original, failure)
            assertEquals(1, lanes.withPhysical(f.sdkA.capability, { 0 }) { 1 })
            assertEquals(2, lanes.withPhysical(f.sdkB.capability, { 0 }) { 2 })
        }
    }
}
