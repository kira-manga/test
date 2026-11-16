@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.sales.store

import com.ukkera.pos.database.StoredBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Self-authored, NOT independent. Fixed owned-settlement supporting inputs v1.
 * Virtual time and immutable channel DATA only; no force, native receipt, current
 * employee authority or instruction is created. Genuine TCP/native execution is separate. */
class PhysicalRemoteStageSettlementSupportingTest {
    @Test fun pollingIntervalsCannotTurnAnOutstandingAnswerIntoKnownNativeFailure() = runTest {
        val actualAnswer = CompletableDeferred<StoredBytes>()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            awaitPhysicalRemoteStageSettlement(actualAnswer) { false }
        }
        advanceTimeBy(1_500); runCurrent()
        assertTrue(waiting.isActive); assertFalse(actualAnswer.isCompleted)
        val exact = StoredBytes(byteArrayOf(1, 2, 3))
        actualAnswer.complete(exact)
        assertSame(exact, waiting.await())
    }

    @Test fun actualDenialStopsDisclosureWithoutCancelingOrCompletingTheProducer() = runTest {
        var denied = false
        val actualAnswer = CompletableDeferred<StoredBytes>()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            awaitPhysicalRemoteStageSettlement(actualAnswer) { denied }
        }
        advanceTimeBy(750); denied = true
        advanceTimeBy(500); runCurrent()
        assertNull(waiting.await()); assertFalse(actualAnswer.isCompleted); assertFalse(actualAnswer.isCancelled)
        assertTrue(actualAnswer.complete(StoredBytes(byteArrayOf(4))))
    }

    @Test fun aWithdrawnOwnerCannotDiscloseAnAnswerThatSettlesAtTheSameCut() = runTest {
        var denied = false
        val actualAnswer = CompletableDeferred<StoredBytes>()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            awaitPhysicalRemoteStageSettlement(actualAnswer) { denied }
        }
        denied = true; actualAnswer.complete(StoredBytes(byteArrayOf(5)))
        assertNull(waiting.await())
    }

    @Test fun canceledWaiterDoesNotCancelTheRetainedAnswerOrItsLateSettlement() = runTest {
        val actualAnswer = CompletableDeferred<StoredBytes>()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            awaitPhysicalRemoteStageSettlement(actualAnswer) { false }
        }
        advanceTimeBy(750); waiting.cancelAndJoin()
        assertFalse(actualAnswer.isCancelled); assertFalse(actualAnswer.isCompleted)
        assertTrue(actualAnswer.complete(StoredBytes(byteArrayOf(6))))
    }
}
