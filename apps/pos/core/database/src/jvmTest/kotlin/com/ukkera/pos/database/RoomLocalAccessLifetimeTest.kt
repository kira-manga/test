package com.ukkera.pos.database

import com.ukkera.pos.security.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class RoomLocalAccessLifetimeTest {
    @Test fun finalSecurityObservationsFollowAllProviderWorkAndActualInnerCommit() = runTest {
        val driver = AccessBoundaryDriver(); val f = LocalAccessVerifierFixture(driver)
        val events = CopyOnWriteArrayList<String>()
        try {
            f.base.seed()
            f.peers.afterCapture = { events += "capture" }
            f.base.protection.afterOpen = { events += "open"; driver.commitHookArmed.set(true) }
            f.base.protection.onProtectionStatusRead = { events += "health" }
            driver.afterCommit = { events += "commit" }
            f.peers.onCurrent = { events += "peer" }
            f.base.environment.onObserve = { events += "time" }
            assertNotNull(f.authenticate())
            assertEquals(1, driver.commitsObserved.get()); assertEquals(4, events.count { it == "open" })
            assertTrue(events.any { it == "health" }, "absence must not pass an index-of ordering assertion")
            for (event in listOf("capture", "commit", "peer", "time")) assertEquals(1, events.count { it == event }, event)
            assertEquals("capture", events.first())
            assertTrue(events.lastIndexOf("health") < events.indexOf("commit"))
            assertTrue(events.lastIndexOf("open") < events.indexOf("commit"))
            assertTrue(events.indexOf("commit") < events.indexOf("peer"))
            assertEquals("time", events.last()); assertEquals("peer", events[events.lastIndex - 1])
            // Only security observations are recorded. Room cleanup may follow the clock.
        } finally { f.close() }
    }

    @Test fun timeExpiringDuringRealCommitIsRejectedByThePostCommitSample() = runTest {
        val driver = AccessBoundaryDriver(); val f = LocalAccessVerifierFixture(driver)
        try {
            f.base.seed(); val before = f.base.snapshot()
            f.base.protection.afterOpen = { driver.commitHookArmed.set(true) }
            driver.afterCommit = { f.base.environment.elapsed = 2000 }
            assertNull(f.authenticate()); assertEquals(1, driver.commitsObserved.get())
            assertEquals(1, f.peers.finalChecks.get()); assertEquals(1, f.base.environment.calls)
            assertEquals(before, f.base.snapshot())
        } finally { f.close() }
    }

    @Test fun retirementDuringCommitIsCheckedAgainstTheSameCapturedLifetime() = runTest {
        val driver = AccessBoundaryDriver(); val f = LocalAccessVerifierFixture(driver)
        try {
            f.base.seed()
            f.base.protection.afterOpen = { driver.commitHookArmed.set(true) }
            driver.afterCommit = { f.peers.lifetime = Any() }
            assertNull(f.authenticate()); assertEquals(1, driver.commitsObserved.get())
            assertEquals(1, f.peers.finalChecks.get()); assertEquals(0, f.base.environment.calls)
        } finally { f.close() }
    }

    @Test fun preAndPostCommitDriverErrorsNeverPublishIdentityAndPermitRecovers() = runTest {
        for (cut in CommitFailureCut.entries) {
            val driver = CommitFaultDriver(cut); val f = LocalAccessVerifierFixture(driver, capacity = 1)
            try {
                f.base.seed(); val before = f.base.snapshot(); val once = AtomicBoolean(true)
                f.base.protection.afterOpen = { if (once.compareAndSet(true, false)) driver.arm() }
                assertUnavailable { f.authenticate() }; driver.assertInjectedAtExpectedBoundary()
                assertEquals(0, f.peers.finalChecks.get()); assertEquals(0, f.base.environment.calls)
                assertEquals(before, f.base.snapshot())
                f.base.protection.afterOpen = null; assertNotNull(f.authenticate())
            } finally { f.close() }
        }
    }

    @Test fun postCommitThrownCancellationPropagatesExactIdentityBeforeAnyFinalObservation() = runTest {
        val driver = AccessBoundaryDriver(); val f = LocalAccessVerifierFixture(driver, capacity = 1)
        val cancellation = LocalStorageCancellationSentinel("synthetic after-COMMIT cancellation")
        try {
            f.base.seed(); val before = f.base.snapshot()
            f.base.protection.afterOpen = { driver.commitHookArmed.set(true) }
            driver.afterCommit = { throw cancellation }
            assertSame(cancellation, assertFailsWith<LocalStorageCancellationSentinel> { f.authenticate() })
            assertEquals(1, driver.commitsObserved.get()); assertEquals(0, f.base.environment.calls)
            assertEquals(0, f.peers.finalChecks.get()); assertEquals(before, f.base.snapshot())
            f.base.protection.afterOpen = null; driver.afterCommit = null
            assertNotNull(f.authenticate())
        } finally { f.close() }
    }

    @Test fun outerCleanupErrorsAfterSuccessfulChecksStillPreventIdentityAndReleasePermit() = runTest {
        for (variant in listOf("ordinary", "cancellation")) {
            val f = LocalAccessVerifierFixture()
            val path = f.base.path
            val before: Map<String, List<List<String>>>
            try {
                f.base.seed(); before = f.base.snapshot()
            } finally { f.close() }
            // Pinned Room can permanently retain the writer/close blocker after markRecycled
            // throws. Exercise the REAL fault in a bounded process, not a hanging JUnit finally.
            // No same-pool recovery, graceful shutdown, or production retirement is asserted.
            runCleanupProbe(path, variant)
            val reopened = LocalAuthorizationStoreFixture(path)
            try { assertEquals(before, reopened.snapshot()) } finally { reopened.close() }
        }
    }

    @Test fun providerHealthOpenAndPeerFailuresAreSanitizedWithoutAdvancingPasswordThrottle() = runTest {
        for (variant in listOf("health", "open", "capture", "current", "destroyed")) withAccessFixture(capacity = 1) { f ->
            val before = f.base.snapshot()
            when (variant) {
                "health" -> f.base.protection.status = KeyProtectionStatus.UNQUALIFIED_CONTEXT
                "open" -> f.base.protection.throwOnOpen = IllegalArgumentException("synthetic private provider detail")
                "capture" -> f.peers.afterCapture = { error("synthetic private capture detail") }
                "current" -> f.peers.onCurrent = { error("synthetic private current detail") }
            }
            if (variant == "destroyed") {
                val destroyed = OpaqueCredential(localAccessWire()).also { it.destroy() }
                assertUnavailable { f.factory.authenticateLocal(LocalGatewayCredentials(ACCESS_CONNECTION, destroyed)) }
            } else assertUnavailable { f.authenticate() }
            assertEquals(0, f.base.environment.calls); assertEquals(before, f.base.snapshot())
            f.base.protection.status = KeyProtectionStatus.QUALIFIED_SUPERVISED_SERVICE_CONTEXT
            f.base.protection.throwOnOpen = null; f.peers.afterCapture = null; f.peers.onCurrent = null
            assertNotNull(f.authenticate())
        }
    }

    @Test fun cancellationsFromEachProviderAreNotSanitizedOrTurnedIntoPermanentTimePoison() = runTest {
        for (variant in listOf("open", "capture", "current", "environment")) withAccessFixture(capacity = 1) { f ->
            val cancellation = LocalStorageCancellationSentinel("synthetic $variant cancellation")
            val before = f.base.snapshot()
            when (variant) {
                "open" -> f.base.protection.throwOnOpen = cancellation
                "capture" -> f.peers.afterCapture = { throw cancellation }
                "current" -> f.peers.onCurrent = { throw cancellation }
                "environment" -> f.base.environment.onObserve = { throw cancellation }
            }
            assertSame(cancellation, assertFailsWith<LocalStorageCancellationSentinel> { f.authenticate() })
            assertEquals(before, f.base.snapshot())
            f.base.protection.throwOnOpen = null; f.peers.afterCapture = null; f.peers.onCurrent = null
            f.base.environment.onObserve = null
            assertNotNull(f.authenticate())
        }
    }

    @Test fun normallyReturnedCurrentEvidenceIsAccountedBeforeCallerCancellation() = runTest {
        for (variant in listOf("higher", "regression", "null")) withAccessFixture(capacity = 1) { f ->
            assertNotNull(f.authenticate()); val before = f.base.snapshot()
            val cancellation = LocalStorageCancellationSentinel("synthetic returned observation cancellation")
            lateinit var caller: Job
            f.base.environment.onObserve = {
                when (variant) {
                    "higher" -> f.base.environment.elapsed = 1700
                    "regression" -> f.base.environment.elapsed = 1400
                    "null" -> f.base.environment.available = false
                }
                caller.cancel(cancellation)
            }
            val attempt = async {
                caller = currentCoroutineContext().job
                f.authenticate()
            }
            assertSame(cancellation, assertFailsWith<LocalStorageCancellationSentinel> { attempt.await() })
            f.base.environment.onObserve = null; f.base.environment.available = true; f.base.environment.elapsed = 1600
            assertNull(f.authenticate(), "normally returned evidence/latch must survive cancelled publication: $variant")
            f.base.environment.elapsed = 1800; assertNull(f.authenticate())
            assertEquals(before, f.base.snapshot())
        }
    }

    @Test fun peerFalseOrOrdinaryErrorCannotMaskConcurrentCallerCancellation() = runTest {
        for (variant in listOf("false", "error", "capture-null")) withAccessFixture(capacity = 1) { f ->
            val cancellation = LocalStorageCancellationSentinel("synthetic current-peer cancellation")
            lateinit var caller: Job
            if (variant == "capture-null") {
                f.peers.returnNullAfterCapture = true
                f.peers.afterCapture = { caller.cancel(cancellation) }
            } else f.peers.onCurrent = {
                caller.cancel(cancellation)
                if (variant == "error") error("synthetic private peer detail") else f.peers.current = false
            }
            val attempt = async { caller = currentCoroutineContext().job; f.authenticate() }
            assertSame(cancellation, assertFailsWith<LocalStorageCancellationSentinel> { attempt.await() })
            assertEquals(0, f.base.environment.calls)
            f.peers.afterCapture = null; f.peers.onCurrent = null; f.peers.current = true; f.peers.scope = TEST_SCOPE
            f.peers.returnNullAfterCapture = false
            assertNotNull(f.authenticate())
        }
    }
}

private fun runCleanupProbe(path: Path, variant: String) {
    val classpath = requireNotNull(System.getProperty("ukkera.test.runtime.classpath"))
    val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
    val log = path.resolveSibling("cleanup-$variant.log")
    val process = ProcessBuilder(java, "-Xmx128m", "-ea", "-cp", classpath,
        LocalAccessCleanupProbe::class.java.name, path.toString(), variant)
        .redirectErrorStream(true).redirectOutput(log.toFile()).start()
    try {
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Synthetic cleanup probe timed out: $variant")
        assertTrue(Files.size(log) <= 16_384, "Unexpected cleanup probe output size")
        val output = Files.readString(log)
        assertEquals(0, process.exitValue(), "Cleanup probe failed: ${output.take(4_000)}")
        val witness = "D055_CLEANUP_PROBE_PASSED:$variant${System.lineSeparator()}"
        assertEquals(witness, output, "Exactly one post-assertion witness is required")
        print(output) // Retained in the parent JUnit XML, not mistaken for another test case.
    } finally {
        if (process.isAlive) process.destroyForcibly()
        check(process.waitFor(5, TimeUnit.SECONDS)) { "Synthetic cleanup probe did not terminate" }
    }
}

/**
 * TEST ONLY. A real failing outer Room recycle must never publish a principal. The poisoned
 * pool may not close cooperatively; the parent owns a deadline and verifies the file after
 * this process has exited. Both success AND assertion-error paths deliberately avoid close().
 */
object LocalAccessCleanupProbe {
    @JvmStatic fun main(args: Array<String>) {
        try {
            require(args.size == 2 && args[1] in listOf("ordinary", "cancellation"))
            runBlocking {
                val driver = AccessBoundaryDriver()
                val f = LocalAccessVerifierFixture(driver, capacity = 1, path = Path.of(args[0]))
                val cancellation = LocalStorageCancellationSentinel("synthetic cleanup cancellation")
                if (args[1] == "cancellation") driver.cleanupThrowable = cancellation
                f.base.protection.afterOpen = { driver.commitHookArmed.set(true) }
                f.base.environment.onObserve = { driver.cleanupArmed.set(true) }
                if (args[1] == "cancellation") {
                    assertSame(cancellation, assertFailsWith<LocalStorageCancellationSentinel> { f.authenticate() })
                } else assertUnavailable { f.authenticate() }
                assertEquals(1, driver.commitsObserved.get())
                assertEquals(1, f.peers.finalChecks.get()); assertEquals(1, f.base.environment.calls)
                assertEquals(1, driver.cleanupFailures.get()); assertTrue(driver.cleanupWasOutsideTransaction.get())
                assertTrue(driver.cleanupReachedRecycle.get(), "failure must be actual outer markRecycled, not a body check")
                // Capacity=1: this early peer refusal must reacquire/release the SAME limiter,
                // without trying to reuse the poisoned Room pool. It is not storage recovery.
                f.base.environment.onObserve = null; f.peers.available = false
                assertNull(f.authenticate()); assertEquals(2, f.peers.captures.get())
                assertEquals(1, driver.commitsObserved.get())
                assertEquals(1, f.peers.finalChecks.get()); assertEquals(1, f.base.environment.calls)
            }
            println("D055_CLEANUP_PROBE_PASSED:${args[1]}")
            System.out.flush()
            Runtime.getRuntime().halt(0)
        } catch (failure: Throwable) {
            failure.printStackTrace(System.err)
            System.err.flush()
            Runtime.getRuntime().halt(1)
        }
    }
}
