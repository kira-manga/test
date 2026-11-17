package com.ukkera.pos.database

import com.ukkera.pos.security.MonotonicMoment
import com.ukkera.pos.types.DomainId
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.*
import kotlinx.coroutines.Job

/** Self-authored, NOT independent. Controlled clock DATA; no native/current authority claim. */
class LocalAuthorizationObservationOrderingSupportingTest {
    private val issuer = DomainId.random()
    private val boot = DomainId.random()
    private fun observation(elapsed: Long, selectedIssuer: DomainId = issuer, selectedBoot: DomainId = boot) =
        RoomLocalAuthorizationObservation(selectedIssuer, MonotonicMoment(selectedBoot, elapsed))

    @Test fun overlappingHealthyObservationsDoNotPermanentlyDisableCurrentAuthorization() {
        val firstSampled = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondSampled = CountDownLatch(1)
        val calls = AtomicInteger()
        val registry = PhysicalInstructionPublicationRegistry(RoomLocalAuthorizationEnvironment {
            val elapsed = 100L + calls.getAndIncrement()
            if (elapsed == 100L) {
                firstSampled.countDown()
                check(releaseFirst.await(5, TimeUnit.SECONDS))
            } else secondSampled.countDown()
            observation(elapsed)
        })
        val workers = Executors.newFixedThreadPool(2)
        try {
            val first = workers.submit(Callable { runCatching { registry.observeCurrent(EmptyCoroutineContext) } })
            assertTrue(firstSampled.await(5, TimeUnit.SECONDS))
            val second = workers.submit(Callable { runCatching { registry.observeCurrent(EmptyCoroutineContext) } })
            // An implementation may serialize sampling. Otherwise force the newer sample
            // to finish first. The timeout only releases the bounded test latch.
            if (secondSampled.await(250, TimeUnit.MILLISECONDS)) second.get(5, TimeUnit.SECONDS)
            releaseFirst.countDown()
            assertEquals(100L, first.get(5, TimeUnit.SECONDS).getOrThrow().moment.elapsedMillis)
            assertEquals(101L, second.get(5, TimeUnit.SECONDS).getOrThrow().moment.elapsedMillis)
            assertEquals(102L, registry.observeCurrent(EmptyCoroutineContext).moment.elapsedMillis)
        } finally {
            releaseFirst.countDown()
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun sequentialElapsedRegressionPermanentlyDeniesTheSource() {
        assertPermanentDenial(observation(99))
    }

    @Test fun actualIssuerOrBootChangeAndMissingObservationRemainPermanentDenials() {
        for (changed in listOf(null, observation(101, selectedIssuer = DomainId.random()),
            observation(101, selectedBoot = DomainId.random()))) assertPermanentDenial(changed)
    }

    @Test fun equalTimeIsHealthyAndDoesNotInventAnAdvance() {
        val registry = PhysicalInstructionPublicationRegistry(RoomLocalAuthorizationEnvironment { observation(100) })
        repeat(3) { assertEquals(100L, registry.observeCurrent(EmptyCoroutineContext).moment.elapsedMillis) }
    }

    @Test fun returnedAdvanceSurvivesCallerCancellation() {
        val job = Job()
        var selected = observation(100)
        var cancel = false
        val registry = PhysicalInstructionPublicationRegistry(RoomLocalAuthorizationEnvironment {
            if (cancel) job.cancel()
            selected
        })
        registry.observeCurrent(EmptyCoroutineContext)
        selected = observation(101); cancel = true
        assertFailsWith<CancellationException> { registry.observeCurrent(job) }
        selected = observation(100); cancel = false
        assertFailsWith<RoomCurrentLocalAuthorizationDenied> { registry.observeCurrent(EmptyCoroutineContext) }
    }

    @Test fun returnedDiscontinuitySurvivesCallerCancellation() {
        val job = Job()
        var lost = false
        val registry = PhysicalInstructionPublicationRegistry(RoomLocalAuthorizationEnvironment {
            if (lost) { job.cancel(); null } else observation(100)
        })
        registry.observeCurrent(EmptyCoroutineContext)
        lost = true
        assertFailsWith<CancellationException> { registry.observeCurrent(job) }
        lost = false
        assertFailsWith<RoomCurrentLocalAuthorizationDenied> { registry.observeCurrent(EmptyCoroutineContext) }
    }

    @Test fun thrownCancellationWithoutASampleDoesNotInventDiscontinuity() {
        var cancel = true
        val sentinel = CancellationException("controlled clock cancellation")
        val registry = PhysicalInstructionPublicationRegistry(RoomLocalAuthorizationEnvironment {
            if (cancel) throw sentinel
            observation(100)
        })
        assertSame(sentinel, assertFailsWith<CancellationException> { registry.observeCurrent(EmptyCoroutineContext) })
        cancel = false
        assertEquals(100L, registry.observeCurrent(EmptyCoroutineContext).moment.elapsedMillis)
    }

    private fun assertPermanentDenial(changed: RoomLocalAuthorizationObservation?) {
        var selected: RoomLocalAuthorizationObservation? = observation(100)
        val registry = PhysicalInstructionPublicationRegistry(RoomLocalAuthorizationEnvironment { selected })
        registry.observeCurrent(EmptyCoroutineContext)
        selected = changed
        assertFailsWith<RoomCurrentLocalAuthorizationDenied> { registry.observeCurrent(EmptyCoroutineContext) }
        selected = observation(102)
        assertFailsWith<RoomCurrentLocalAuthorizationDenied> { registry.observeCurrent(EmptyCoroutineContext) }
    }
}
