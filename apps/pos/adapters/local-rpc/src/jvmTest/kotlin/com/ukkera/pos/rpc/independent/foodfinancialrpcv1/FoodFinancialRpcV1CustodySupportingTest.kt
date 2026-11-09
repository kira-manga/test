@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.rpc.independent.foodfinancialrpcv1

import com.ukkera.pos.database.LocalMasterRpcOwnedRequestV1
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Real request/custody SUPPORT. Host accepted-callback accounting and business storage remain unbound. */
class FoodFinancialRpcV1CustodySupportingTest {
    @Test
    fun ffrc030ConcurrentClaimsHaveOneExactOwnerAndMatchingCoordinatesRemainIndependent() = runTest(timeout = 15.seconds) {
        for (kind in FoodFinancialCarrierKind.entries) {
            val fixture = FoodFinancialCarrierFixture()
            val label = "FFRC-030 claims " + kind.name
            try {
                val decoder = FoodFinancialDecoderFixture(fixture)
                val caller = FoodFinancialRpcV1Frozen.bearer()
                val first = decoder.decode(kind, caller)
                val peer = decoder.decode(kind, caller)
                assertNotSame(first, peer, label)
                val claimRelease = CountDownLatch(1)
                val ready = CountDownLatch(2)
                val finished = CountDownLatch(2)
                val results = List(2) { AtomicReference<Boolean>() }
                val failures = List(2) { AtomicReference<Throwable?>() }
                val threads = List(2) { index ->
                    Thread({
                        try {
                            ready.countDown()
                            check(claimRelease.await(5, TimeUnit.SECONDS)) { "Claim release was not delivered" }
                            results[index].set(first.claimForCallback())
                        } catch (failure: Throwable) { failures[index].set(failure) }
                        finally { finished.countDown() }
                    }, "food-financial-callback-claim").apply { isDaemon = true; start() }
                }
                try {
                    assertTrue(ready.await(5, TimeUnit.SECONDS), label + ": contenders did not enter")
                    claimRelease.countDown()
                    assertTrue(finished.await(5, TimeUnit.SECONDS), label + ": contenders did not finish")
                    failures.forEach { it.get()?.let { failure -> throw failure } }
                    assertEquals(1, results.count { it.get() == true }, label + ": exact winning claims")
                    assertEquals(1, results.count { it.get() == false }, label + ": exact refused claims")
                } finally {
                    claimRelease.countDown()
                    threads.forEach { it.join(1_000); assertFalse(it.isAlive, label + ": contender remains active") }
                }
                assertTrue(peer.claimForCallback(), label + ": separate matching-coordinate owner")
                val held = fixture.borrow(listOf(first, peer), listOf(caller, caller), label)
                assertFalse(first.claimForCallback(), label)
                decoder.advanceTo(FoodFinancialRpcV1Frozen.atExpiryMs)
                decoder.custody.reap()
                decoder.assertCensus(listOf(first, peer), label)
                assertFalse(first.isDestroyed || peer.isDestroyed, label + ": expiry seized an accepted owner")
                assertFoodBearer(caller, held.copy(0), label + ": winner held copy")
                assertFoodBearer(caller, held.copy(1), label + ": independent owner held copy")
                first.destroy()
                peer.destroy()
                val firstWait = fixture.wait(this, label + ": first cleanup") { first.awaitCleanup() }
                val peerWait = fixture.wait(this, label + ": peer cleanup") { peer.awaitCleanup() }
                firstWait.assertPending()
                peerWait.assertPending()
                held.release(1)
                held.awaitReturned(1)
                peerWait.awaitSuccess()
                firstWait.assertPending()
                assertFoodBearer(caller, held.copy(0), label + ": surviving exact borrow")
                held.release(0)
                held.awaitReturned(0)
                firstWait.awaitSuccess()
                decoder.custody.reap()
                decoder.assertCensus(emptyList(), label + ": completed owners")
                assertFoodCleared(foodRetainedBearer(first), label)
                assertFoodCleared(foodRetainedBearer(peer), label)
                assertFoodBearer(FoodFinancialRpcV1Frozen.bearer(), caller, label + ": caller preserved")
            } finally { fixture.close() }
        }
    }

    @Test
    fun ffrc030ExpiryBeforeAndAfterClaimNeverTransfersTheCallbackOwner() = runTest(timeout = 15.seconds) {
        for (kind in FoodFinancialCarrierKind.entries) for (claimFirst in listOf(false, true)) {
            val fixture = FoodFinancialCarrierFixture()
            val label = "FFRC-030 expiry " + kind.name + " claim-first=" + claimFirst
            try {
                val decoder = FoodFinancialDecoderFixture(fixture)
                val caller = FoodFinancialRpcV1Frozen.bearer()
                val request = decoder.decode(kind, caller)
                if (claimFirst) assertTrue(request.claimForCallback(), label)
                decoder.advanceTo(FoodFinancialRpcV1Frozen.atExpiryMs)
                decoder.custody.reap()
                if (claimFirst) {
                    assertFalse(request.isDestroyed || request.isCleanupComplete, label)
                    assertFalse(request.claimForCallback(), label)
                    decoder.assertCensus(listOf(request), label)
                    request.useBearer { assertFoodBearer(caller, it, label) }
                    request.destroy()
                    request.awaitCleanup()
                } else {
                    assertTrue(request.isDestroyed && request.isCleanupComplete, label)
                    assertFalse(request.claimForCallback(), label)
                    assertFailsWith<IllegalStateException>(label) { request.useBearer { error("Expired owner borrowed") } }
                    decoder.assertCensus(emptyList(), label)
                }
                assertFoodCleared(foodRetainedBearer(request), label)
                assertFoodBearer(FoodFinancialRpcV1Frozen.bearer(), caller, label + ": caller preserved")
            } finally { fixture.close() }
        }
    }

    @Test
    fun ffrc031All256ClaimedBorrowsOccupyCapacityUntilTheExactTerminalLoanReturns() = runTest(timeout = 30.seconds) {
        val fixture = FoodFinancialCarrierFixture()
        val label = "FFRC-031 exact mixed capacity"
        try {
            val decoder = FoodFinancialDecoderFixture(fixture)
            val kinds = FoodFinancialRpcV1Frozen.inventory()
            assertEquals(256, FoodFinancialRpcV1Frozen.capacity)
            assertEquals(85, kinds.count { it == FoodFinancialCarrierKind.Read })
            assertEquals(85, kinds.count { it == FoodFinancialCarrierKind.Execute })
            assertEquals(86, kinds.count { it == FoodFinancialCarrierKind.Lookup })
            val callers = kinds.map { FoodFinancialRpcV1Frozen.bearer() }
            val requests = kinds.mapIndexed { index, kind -> decoder.decode(kind, callers[index], index) }
            requests.forEach { assertTrue(it.claimForCallback(), label) }
            val held = fixture.borrow(requests, callers, label)
            decoder.assertCensus(requests, label + ": all exact held inputs")
            assertFailsWith<SerializationException>(label) {
                decoder.decode(FoodFinancialCarrierKind.Read, FoodFinancialRpcV1Frozen.bearer(), 256)
            }
            assertEquals(1, decoder.refused.size, label)
            decoder.refused.forEach { assertTrue(it.isCleanupComplete, label); assertFoodCleared(foodRetainedBearer(it), label) }
            val targetIndex = requests.lastIndex
            val target = requests[targetIndex]
            target.destroy()
            val cleanup = fixture.wait(this, label + ": exact terminal target") { target.awaitCleanup() }
            cleanup.assertPending()
            assertTrue(target.isDestroyed, label)
            assertFalse(target.isCleanupComplete, label)
            decoder.advanceTo(FoodFinancialRpcV1Frozen.atExpiryMs + 1)
            decoder.custody.reap()
            decoder.assertCensus(requests, label + ": old claimed inputs cannot be reaped")
            assertFailsWith<SerializationException>(label) {
                decoder.decode(FoodFinancialCarrierKind.Execute, FoodFinancialRpcV1Frozen.bearer(), 256)
            }
            requests.forEachIndexed { index, request ->
                assertFoodBearer(callers[index], held.copy(index), label + ": still-held disposable")
                assertFoodBearer(callers[index], foodRetainedBearer(request), label + ": still-retained input")
            }
            held.release(targetIndex)
            held.awaitReturned(targetIndex)
            cleanup.awaitSuccess()
            assertFoodCleared(foodRetainedBearer(target), label + ": cleaned target")
            decoder.custody.reap()
            decoder.assertCensus(requests.dropLast(1), label + ": exactly 255 remaining")
            val replacement = decoder.decode(FoodFinancialCarrierKind.Lookup, FoodFinancialRpcV1Frozen.bearer(), 256)
            decoder.assertCensus(requests.dropLast(1) + replacement, label + ": one replacement reaches 256")
            assertFailsWith<SerializationException>(label) {
                decoder.decode(FoodFinancialCarrierKind.Read, FoodFinancialRpcV1Frozen.bearer(), 257)
            }
            requests.dropLast(1).forEachIndexed { index, request ->
                assertFalse(request.isDestroyed || request.isCleanupComplete, label)
                assertFoodBearer(callers[index], held.copy(index), label + ": unaffected accepted borrow")
            }
            assertFoodBearer(FoodFinancialRpcV1Frozen.bearer(), foodRetainedBearer(replacement), label + ": replacement")
            callers.forEach { assertFoodBearer(FoodFinancialRpcV1Frozen.bearer(), it, label + ": caller preserved") }
        } finally { fixture.close() }
    }

    @Test
    fun ffrc031UnclaimedReapingUses29999And30000MillisecondsExactly() = runTest(timeout = 15.seconds) {
        for (kind in FoodFinancialCarrierKind.entries) {
            val fixture = FoodFinancialCarrierFixture()
            val label = "FFRC-031 unclaimed expiry " + kind.name
            try {
                val decoder = FoodFinancialDecoderFixture(fixture)
                val request = decoder.decode(kind, FoodFinancialRpcV1Frozen.bearer())
                decoder.advanceTo(FoodFinancialRpcV1Frozen.beforeExpiryMs)
                decoder.custody.reap()
                decoder.assertCensus(listOf(request), label + ": 29,999 ms")
                assertFalse(request.isDestroyed || request.isCleanupComplete, label)
                assertFoodBearer(FoodFinancialRpcV1Frozen.bearer(), foodRetainedBearer(request), label)
                decoder.advanceTo(FoodFinancialRpcV1Frozen.atExpiryMs)
                decoder.custody.reap()
                decoder.assertCensus(emptyList(), label + ": 30,000 ms")
                assertTrue(request.isDestroyed && request.isCleanupComplete, label)
                assertFalse(request.claimForCallback(), label)
                assertFoodCleared(foodRetainedBearer(request), label)
            } finally { fixture.close() }
        }
    }

    @Test
    fun ffrc031CompletedYoungCleanupReleasesRetentionBeforeTheAgeThreshold() = runTest(timeout = 15.seconds) {
        for (kind in FoodFinancialCarrierKind.entries) {
            val fixture = FoodFinancialCarrierFixture()
            val label = "FFRC-031 young completed " + kind.name
            try {
                val decoder = FoodFinancialDecoderFixture(fixture)
                val request = decoder.decode(kind, FoodFinancialRpcV1Frozen.bearer())
                decoder.assertCensus(listOf(request), label)
                request.destroy()
                request.awaitCleanup()
                decoder.custody.reap()
                decoder.assertCensus(emptyList(), label + ": completion at age zero")
                assertFoodCleared(foodRetainedBearer(request), label)
                val replacement = decoder.decode(kind, FoodFinancialRpcV1Frozen.bearer(), 1)
                decoder.assertCensus(listOf(replacement), label + ": replacement admitted without aging")
            } finally { fixture.close() }
        }
    }

    @Test
    fun ffrc032ShutdownOwnsAdmissionBeforeRetirementAndWaitsForEveryExactBorrow() = runTest(timeout = 30.seconds) {
        val fixture = FoodFinancialCarrierFixture()
        val label = "FFRC-032 exact retained shutdown"
        try {
            val decoder = FoodFinancialDecoderFixture(fixture)
            val claimed = FoodFinancialCarrierKind.entries.mapIndexed { index, kind ->
                decoder.decode(kind, FoodFinancialRpcV1Frozen.bearer(), index).also { assertTrue(it.claimForCallback(), label) }
            }
            val unclaimed = FoodFinancialCarrierKind.entries.mapIndexed { index, kind ->
                decoder.decode(kind, FoodFinancialRpcV1Frozen.bearer(), index + 3)
            }
            val held = fixture.borrow(claimed, claimed.map { FoodFinancialRpcV1Frozen.bearer() }, label)
            val before = ControlledFoodAdmission { decoder.decode(FoodFinancialCarrierKind.Read, FoodFinancialRpcV1Frozen.bearer(), 6) }
            val after = ControlledFoodAdmission { decoder.decode(FoodFinancialCarrierKind.Lookup, FoodFinancialRpcV1Frozen.bearer(), 7) }
            val accepted: LocalMasterRpcOwnedRequestV1
            try {
                before.release()
                accepted = before.awaitAccepted()
                decoder.retire()
                after.release()
                after.awaitRefused()
            } finally { before.close(); after.close() }
            val exactCensus = claimed + unclaimed + accepted
            decoder.assertCensus(exactCensus, label + ": admission on both sides of retirement")
            assertEquals(1, decoder.refused.size, label)
            decoder.refused.forEach { assertTrue(it.isCleanupComplete, label); assertFoodCleared(foodRetainedBearer(it), label) }
            // Controlled exact claim owners finish real request cleanup. This is not Host callback accounting.
            val owners = claimed.map { request ->
                async(start = CoroutineStart.UNDISPATCHED) {
                    withContext(NonCancellable) { request.destroy(); request.awaitCleanup() }
                }
            }
            var released = false
            val shutdownOwner = async(start = CoroutineStart.UNDISPATCHED) {
                withContext(NonCancellable) { decoder.custody.destroyAfterDrain(); released = true }
            }
            val cancelledWaiter = fixture.wait(this, label + ": cancelled shutdown waiter") { shutdownOwner.await() }
            val remainingWaiter = fixture.wait(this, label + ": surviving shutdown waiter") { shutdownOwner.await() }
            cancelledWaiter.assertPending()
            remainingWaiter.assertPending()
            cancelledWaiter.cancel()
            cancelledWaiter.awaitCancellation()
            remainingWaiter.assertPending()
            assertFalse(released, label + ": shutdown released while borrowed")
            decoder.assertCensus(exactCensus, label + ": exact census stays retained")
            (unclaimed + accepted).forEach { request ->
                assertTrue(request.isDestroyed && request.isCleanupComplete, label + ": unclaimed owner cleanup")
                assertFoodCleared(foodRetainedBearer(request), label)
            }
            claimed.forEachIndexed { index, request ->
                assertTrue(request.isDestroyed, label)
                assertFalse(request.isCleanupComplete, label)
                assertFoodBearer(FoodFinancialRpcV1Frozen.bearer(), held.copy(index), label + ": held disposable")
                assertFoodBearer(FoodFinancialRpcV1Frozen.bearer(), foodRetainedBearer(request), label + ": held retained")
            }
            held.release(claimed.lastIndex)
            held.awaitReturned(claimed.lastIndex)
            owners.last().await()
            runCurrent()
            remainingWaiter.assertPending()
            assertFalse(released, label + ": one exact return cannot drain other owners")
            held.releaseAll()
            held.awaitAllReturned()
            owners.forEach { it.await() }
            remainingWaiter.awaitSuccess()
            assertTrue(released, label)
            decoder.assertCensus(emptyList(), label + ": successful exact release")
            exactCensus.forEach { assertTrue(it.isCleanupComplete, label); assertFoodCleared(foodRetainedBearer(it), label) }
        } finally { fixture.close() }
    }

    @Test
    fun ffrc032FailedCleanupPortRetainsItsExactUnavailableCensus() = runTest(timeout = 15.seconds) {
        val fixture = FoodFinancialCarrierFixture()
        val label = "FFRC-032 declared cleanup failure"
        try {
            val decoder = FoodFinancialDecoderFixture(fixture)
            val request = decoder.decode(FoodFinancialCarrierKind.Execute, FoodFinancialRpcV1Frozen.bearer())
            assertTrue(request.claimForCallback(), label)
            val held = fixture.borrow(listOf(request), listOf(FoodFinancialRpcV1Frozen.bearer()), label)
            val declared = IllegalStateException("Declared supporting cleanup-source failure")
            decoder.cleanupFailure = declared
            decoder.retire()
            var reportedSuccess = false
            val observed = try { decoder.custody.destroyAfterDrain(); reportedSuccess = true; null }
                catch (failure: IllegalStateException) { failure }
            assertSame(declared, observed, label)
            assertFalse(reportedSuccess, label)
            decoder.assertCensus(listOf(request), label)
            assertFalse(request.isDestroyed || request.isCleanupComplete, label)
            assertFoodBearer(FoodFinancialRpcV1Frozen.bearer(), held.copy(), label + ": no forced clear")
            assertFailsWith<SerializationException>(label) {
                decoder.decode(FoodFinancialCarrierKind.Read, FoodFinancialRpcV1Frozen.bearer(), 1)
            }
            decoder.assertCensus(listOf(request), label + ": still retained and denied")
        } finally { fixture.close() }
    }
}

/** Deterministic admission schedule; timeouts guard missing barriers and never prove ordering. */
private class ControlledFoodAdmission(block: () -> LocalMasterRpcOwnedRequestV1) {
    private val release = CountDownLatch(1)
    private val finished = CountDownLatch(1)
    private val request = AtomicReference<LocalMasterRpcOwnedRequestV1>()
    private val failure = AtomicReference<Throwable?>()
    private val thread = Thread({
        try {
            check(release.await(5, TimeUnit.SECONDS)) { "Admission release was not delivered" }
            request.set(block())
        } catch (caught: Throwable) { failure.set(caught) }
        finally { finished.countDown() }
    }, "food-financial-decoder-admission").apply { isDaemon = true; start() }

    fun release() { release.countDown() }
    fun awaitAccepted(): LocalMasterRpcOwnedRequestV1 {
        assertTrue(finished.await(5, TimeUnit.SECONDS), "Accepted admission did not finish")
        failure.get()?.let { throw it }
        return checkNotNull(request.get()) { "Admission was not accepted" }
    }
    fun awaitRefused() {
        assertTrue(finished.await(5, TimeUnit.SECONDS), "Refused admission did not finish")
        assertTrue(failure.get() is SerializationException, "Retired admission was not refused")
        assertEquals(null, request.get(), "Retired admission unexpectedly returned an owned input")
    }
    fun close() { release(); thread.join(1_000); assertFalse(thread.isAlive, "Admission thread remains active") }
}
