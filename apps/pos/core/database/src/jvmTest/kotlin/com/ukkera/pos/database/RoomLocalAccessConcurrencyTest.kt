package com.ukkera.pos.database

import androidx.room3.useWriterConnection
import com.ukkera.pos.security.*
import com.ukkera.pos.types.Revision
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

/**
 * The Default scheduling scopes below START without Room context. withContext(Default) is
 * only a deterministic test scheduling control, never a way to strip an inherited loan.
 */
class RoomLocalAccessConcurrencyTest {
    @Test fun inheritedBareLoanChildrenActuallyShareWrapperWhichIsAnUnsupportedEntryLimitation() = runTest {
        withAccessFixture { f ->
            f.base.database.useWriterConnection { outer ->
                assertFalse(outer.inTransaction())
                coroutineScope {
                    val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
                    val first = async {
                        f.base.database.useWriterConnection { inner ->
                            assertSame(outer, inner); entered.complete(Unit); release.await()
                        }
                    }
                    try {
                        entered.await()
                        val second = async {
                            f.base.database.useWriterConnection { inner ->
                                assertSame(outer, inner); assertFalse(inner.inTransaction())
                            }
                        }
                        second.await(); assertFalse(first.isCompleted)
                    } finally { release.complete(Unit) }
                    first.await()
                }
            }
            // This characterizes bare-loan reuse ONLY. It does NOT reproduce a SAVEPOINT
            // rollback escape, claim rejection, or invoke verification from supported entry.
        }
    }

    @Test fun independentUnborrowedRequestsUseTwoRootCommitsWithoutSavepoints() = runTest {
        withContext(Dispatchers.Default) {
            val driver = AccessBoundaryDriver(); val f = LocalAccessVerifierFixture(driver)
            val held = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val secondCaptured = CompletableDeferred<Unit>(); val first = AtomicBoolean(true)
            try {
                f.base.seed(); driver.recording = true
                f.base.protection.afterOpen = {
                    driver.commitHookArmed.set(true)
                    if (first.compareAndSet(true, false)) { held.complete(Unit); release.await() }
                }
                f.peers.afterCapture = { if (f.peers.captures.get() == 2) secondCaptured.complete(Unit) }
                val one = async(start = CoroutineStart.UNDISPATCHED) { f.authenticate() }
                held.await()
                val two = async(start = CoroutineStart.UNDISPATCHED) { f.authenticate() }
                try {
                    assertTrue(secondCaptured.isCompleted); assertFalse(one.isCompleted); assertFalse(two.isCompleted)
                } finally { release.complete(Unit) }
                assertNotNull(one.await()); assertNotNull(two.await())
                assertEquals(2, driver.commitsObserved.get()); assertEquals(2, f.base.environment.calls)
                assertEquals(2, f.peers.finalChecks.get())
                assertTrue(driver.statements.any { it.startsWith("BEGIN IMMEDIATE") })
                assertFalse(driver.statements.any { it.contains("SAVEPOINT") }, "supported calls must not nest SQL transactions")
            } finally { release.complete(Unit); f.close() }
        }
    }

    @Test fun sameOwnerRevocationWaitsUntilVerificationFinalObservationsAndWriterRelease() = runTest {
        withContext(Dispatchers.Default) {
            withAccessFixture { f ->
                val held = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
                val revokerStarted = CompletableDeferred<Unit>(); val writerEntered = AtomicBoolean(false)
                val first = AtomicBoolean(true)
                f.base.protection.afterOpen = { if (first.compareAndSet(true, false)) { held.complete(Unit); release.await() } }
                f.peers.onCurrent = { assertFalse(writerEntered.get()) }
                f.base.environment.onObserve = { assertFalse(writerEntered.get()) }
                val verification = async(start = CoroutineStart.UNDISPATCHED) { f.authenticate() }
                held.await()
                val revoker = async(start = CoroutineStart.UNDISPATCHED) {
                    revokerStarted.complete(Unit)
                    f.revoke { writerEntered.set(true) }
                }
                try { assertTrue(revokerStarted.isCompleted); assertFalse(revoker.isCompleted); assertFalse(writerEntered.get()) }
                finally { release.complete(Unit) }
                val principal = assertNotNull(verification.await())
                revoker.await(); assertTrue(writerEntered.get())
                f.peers.onCurrent = null; f.base.environment.onObserve = null; f.base.protection.afterOpen = null
                val before = f.base.snapshot()
                assertNull(f.authenticate()); assertLocalAuthDenied(f.base.kernel().execute(principal, f.base.request()))
                assertEquals(before, f.base.snapshot())
                // Revocation MAY commit before the requesting coroutine resumes with its principal.
                // The asserted ordering ends at writer release, not at caller receipt.
            }
        }
    }

    @Test fun revocationFirstCommitsBeforeWaitingVerificationCanReadProtectedFacts() = runTest {
        withContext(Dispatchers.Default) {
            withAccessFixture { f ->
                val changed = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
                val captured = CompletableDeferred<Unit>()
                val revoker = async(start = CoroutineStart.UNDISPATCHED) {
                    f.base.protectedStore.transaction { transaction ->
                        val old = assertNotNull(transaction.readAccount(TEST_SCOPE, TEST_ACTOR))
                        val revoked = assertIs<SessionTransition.Applied>(LocalSessions.revoke(old, testId(9110)))
                        assertEquals(setOf(testId(9110)), revoked.revokedSessionIds)
                        assertEquals(old.revision.next(), revoked.state.revision)
                        assertEquals(old.generation.next(), revoked.state.generation)
                        assertTrue(transaction.replaceAccount(old.revision, revoked.state))
                        changed.complete(Unit); release.await()
                    }
                }
                changed.await(); val opens = f.base.protection.openCalls
                f.peers.afterCapture = { captured.complete(Unit) }
                val verification = async(start = CoroutineStart.UNDISPATCHED) { f.authenticate() }
                try {
                    assertTrue(captured.isCompleted); assertFalse(verification.isCompleted)
                    assertEquals(opens, f.base.protection.openCalls, "verification cannot read while revoker owns writer")
                } finally { release.complete(Unit) }
                revoker.await(); assertNull(verification.await())
                assertEquals(1, f.base.snapshot().getValue("protected_local_access_issuance").size)
            }
        }
    }

    @Test fun capacityGreaterThanOneFailsFastBeforeProviderOrRoomAndRecoversAllPermits() = runTest {
        withAccessFixture(capacity = 2) { f ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val opens = f.base.protection.openCalls; val before = f.base.snapshot()
            f.peers.afterCapture = {
                if (f.peers.captures.get() == 2) entered.complete(Unit)
                release.await()
            }
            val one = async(Dispatchers.Default) { f.authenticate() }
            val two = async(Dispatchers.Default) { f.authenticate() }
            try {
                entered.await()
                assertUnavailable { f.authenticate() }
                assertEquals(2, f.peers.captures.get()); assertEquals(opens, f.base.protection.openCalls)
                assertEquals(0, f.base.environment.calls)
            } finally { release.complete(Unit) }
            assertNotNull(one.await()); assertNotNull(two.await())
            f.peers.afterCapture = null
            assertNull(f.authenticate(localAccessWire(secret = ByteArray(32))))
            assertNotNull(f.authenticate()); assertEquals(before, f.base.snapshot())
            // A second full held wave is required: sequential success alone would not expose
            // a leak of one of two permits, or establish that the limiter was not over-released.
            val previousCaptures = f.peers.captures.get()
            val enteredAgain = CompletableDeferred<Unit>(); val releaseAgain = CompletableDeferred<Unit>()
            f.peers.afterCapture = {
                if (f.peers.captures.get() == previousCaptures + 2) enteredAgain.complete(Unit)
                releaseAgain.await()
            }
            val againOne = async(Dispatchers.Default) { f.authenticate() }
            val againTwo = async(Dispatchers.Default) { f.authenticate() }
            try {
                enteredAgain.await()
                assertEquals(previousCaptures + 2, f.peers.captures.get())
                assertUnavailable { f.authenticate() }
                assertEquals(previousCaptures + 2, f.peers.captures.get())
            } finally { releaseAgain.complete(Unit) }
            assertNotNull(againOne.await()); assertNotNull(againTwo.await())
            f.peers.afterCapture = null; assertEquals(before, f.base.snapshot())
        }
    }

    @Test fun cancelledRoomWaiterReleasesPermitWithoutBorrowingItsIndependentHoldersContext() = runTest {
        withContext(Dispatchers.Default) {
            withAccessFixture(capacity = 1) { f ->
                val held = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
                val captured = CompletableDeferred<Unit>()
                val holder = async(start = CoroutineStart.UNDISPATCHED) {
                    f.base.database.useWriterConnection { held.complete(Unit); release.await() }
                }
                held.await()
                f.peers.afterCapture = { captured.complete(Unit) }
                val cancellation = LocalStorageCancellationSentinel("synthetic waiting cancellation")
                val waiter = async(start = CoroutineStart.UNDISPATCHED) { f.authenticate() }
                try {
                    assertTrue(captured.isCompleted); assertFalse(waiter.isCompleted); assertUnavailable { f.authenticate() }
                    waiter.cancel(cancellation)
                    assertSame(cancellation, assertFailsWith<LocalStorageCancellationSentinel> { waiter.await() })
                    // No Room access is needed for this rejection, proving the verifier permit
                    // recovered even while the independently launched holder still owns the pool.
                    f.peers.available = false
                    assertNull(f.authenticate()); assertEquals(2, f.peers.captures.get())
                } finally { release.complete(Unit) }
                holder.await(); f.peers.available = true; f.peers.afterCapture = null
                assertNotNull(f.authenticate())
            }
        }
    }

    @Test fun verifierObservationAlsoRaisesKernelHighWaterOnTheSameSourceInstance() = runTest {
        withAccessFixture { f ->
            f.base.environment.elapsed = 1700
            val principal = assertNotNull(f.authenticate()); val before = f.base.snapshot()
            f.base.environment.elapsed = 1600
            assertLocalAuthDenied(f.base.kernel().execute(principal, f.base.request()))
            f.base.environment.elapsed = 1800
            assertNull(f.authenticate()); assertEquals(before, f.base.snapshot())
        }
    }

    @Test fun expiredAuthenticatedIssuanceStillObservesTimeAndRegressionCannotReviveAnotherSession() = runTest {
        withAccessFixture { f ->
            f.base.seedOtherActor(testId(9963), testId(9964), listOf(allowLocal(SecurityAction.INVENTORY_ADJUST)), duration = 10000)
            val before = f.base.snapshot()
            f.base.environment.elapsed = 2000
            assertNull(f.authenticate())
            f.base.environment.elapsed = 1999
            assertNull(f.authenticate(localAccessWire(testId(9964))))
            f.base.environment.elapsed = 2001
            assertNull(f.authenticate(localAccessWire(testId(9964))))
            assertEquals(before, f.base.snapshot())
        }
    }

    @Test fun observationalBoundaryDoesNotFenceRetirementBetweenPeerAndTimeChecks() = runTest {
        withAccessFixture { f ->
            f.base.environment.onObserve = { f.peers.current = false }
            assertNotNull(f.authenticate())
            assertEquals(1, f.peers.finalChecks.get()); assertFalse(f.peers.current)
            assertNull(f.authenticate())
            // Explicit limitation: the previous identity followed valid observations, NOT
            // simultaneous freshness or disclosure fencing. No transport/app is bound here.
        }
    }
}
