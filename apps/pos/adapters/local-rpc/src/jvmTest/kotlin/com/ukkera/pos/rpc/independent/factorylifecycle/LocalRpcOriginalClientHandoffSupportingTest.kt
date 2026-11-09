package com.ukkera.pos.rpc.independent.factorylifecycle

import com.ukkera.pos.rpc.LocalRpcFactoryPhase
import com.ukkera.pos.rpc.LocalRpcOriginalClientOwner
import com.ukkera.pos.rpc.LocalRpcOriginalClientSource
import com.ukkera.pos.security.LocalDevicePairingSdkOriginal
import dev.p2pkit.rpc.RpcLocalIdentity
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Controlled original-source slices of frozen RFLC-PROFILE-02/ACQUIRE-03/CLOSE-04.
 * The sources only suspend or refuse; they never manufacture/return a native SDK original.
 * The genuine protected JVM fixture is supplied unchanged, but no Client factory should be
 * entered in these negative schedules. Source callbacks and supplier/provider observations
 * establish this common handoff's control flow only, not native closure/custody, genuine
 * original recovery, owned-Kit correspondence, or global network/admission/business zero effects.
 * Positive RFIR-NATIVE-02/RFIR-SDK-04 reconstruction and whole frozen cases remain separate.
 */
class LocalRpcOriginalClientHandoffSupportingTest {
    @Test
    fun unavailableOriginalIsRefusedWithoutClientConstructionOrFreshRetry() = runBlocking {
        val fixture = OriginalClientHandoffFixture.create()
        try {
            assertEquals(LocalRpcFactoryPhase.NEW, fixture.owner.phase.value)
            assertFailsWith<ControlledOriginalUnavailable> {
                observe { fixture.startAcquireCaller().await() }
            }
            fixture.awaitControlledCleanup()

            repeat(2) {
                assertFailsWith<IllegalStateException> { observe { fixture.owner.acquire() } }
                observe { fixture.startCloseCaller().await() }
                fixture.assertControlledCleanup()
            }
        } finally {
            fixture.finishOrRetain()
        }
    }

    @Test
    fun cancelledAcquireWaiterKeepsOriginalPendingReadWithoutReplacement() = runBlocking {
        val fixture = OriginalClientHandoffFixture.create(pauseRead = true)
        try {
            val abandonedWaiter = fixture.startAcquireCaller()
            observe { fixture.source.readEntered.await() }
            fixture.assertPendingRead()
            assertEquals(LocalRpcFactoryPhase.ACQUIRING, fixture.owner.phase.value)

            observe { abandonedWaiter.cancelAndJoin() }
            assertTrue(abandonedWaiter.isCancelled)
            assertTrue(fixture.factoryFixture.applicationJob.isActive)
            assertEquals(LocalRpcFactoryPhase.ACQUIRING, fixture.owner.phase.value)
            fixture.assertPendingRead()

            val nextWaiter = fixture.startAcquireCaller(CoroutineStart.UNDISPATCHED)
            assertFalse(nextWaiter.isCompleted)
            fixture.assertPendingRead()

            fixture.source.releaseRead()
            assertFailsWith<ControlledOriginalUnavailable> { observe { nextWaiter.await() } }
            fixture.awaitControlledCleanup()
            assertFailsWith<IllegalStateException> { observe { fixture.owner.acquire() } }
            observe { fixture.startCloseCaller().await() }
            fixture.assertControlledCleanup()
        } finally {
            fixture.finishOrRetain()
        }
    }

    @Test
    fun cancelledCloseCallerKeepsPendingExactSourceCleanupOwned() = runBlocking {
        val fixture = OriginalClientHandoffFixture.create(pauseClose = true)
        try {
            val acquiring = fixture.startAcquireCaller()
            observe { fixture.source.closeEntered.await() }
            assertFailsWith<ControlledOriginalUnavailable> { observe { acquiring.await() } }
            fixture.assertPendingClose()

            val cancelledClose = fixture.startCloseCaller()
            assertFalse(cancelledClose.isCompleted)
            cancelledClose.cancel()
            assertTrue(cancelledClose.isCancelled)
            assertFalse(cancelledClose.isCompleted,
                "Cancelling a waiter cannot complete the still-pending source cleanup")
            fixture.assertPendingClose()
            assertFailsWith<IllegalStateException> { observe { fixture.owner.acquire() } }

            val repeatedClose = fixture.startCloseCaller()
            assertFalse(repeatedClose.isCompleted)
            fixture.assertPendingClose()

            fixture.source.releaseClose()
            observe { cancelledClose.join() }
            observe { repeatedClose.await() }
            assertTrue(cancelledClose.isCancelled)
            fixture.awaitControlledCleanup()
            observe { fixture.startCloseCaller().await() }
            fixture.assertControlledCleanup()
        } finally {
            fixture.finishOrRetain()
        }
    }

    @Test
    fun applicationCancellationDrainsOriginalReadAndPendingSourceClose() = runBlocking {
        val fixture = OriginalClientHandoffFixture.create(pauseRead = true, pauseClose = true)
        try {
            val acquiring = fixture.startAcquireCaller()
            observe { fixture.source.readEntered.await() }
            fixture.assertPendingRead()

            fixture.factoryFixture.applicationJob.cancel()
            fixture.awaitPhase(LocalRpcFactoryPhase.CLOSING)
            assertFalse(fixture.factoryFixture.applicationJob.isActive)
            assertFalse(fixture.factoryFixture.applicationJob.isCompleted)
            assertFalse(acquiring.isCompleted)
            fixture.assertPendingRead()
            assertFailsWith<IllegalStateException> { observe { fixture.owner.acquire() } }

            fixture.source.releaseRead()
            assertFailsWith<ControlledOriginalUnavailable> { observe { acquiring.await() } }
            observe { fixture.source.closeEntered.await() }
            fixture.assertPendingClose()
            assertFalse(fixture.factoryFixture.applicationJob.isCompleted,
                "Application cancellation must drain the same pending source close")

            val repeatedClose = fixture.startCloseCaller()
            assertFalse(repeatedClose.isCompleted)
            fixture.assertPendingClose()

            fixture.source.releaseClose()
            observe { fixture.factoryFixture.applicationJob.join() }
            observe { repeatedClose.await() }
            fixture.awaitControlledCleanup()
            assertFailsWith<IllegalStateException> { observe { fixture.owner.acquire() } }
            fixture.assertControlledCleanup()
        } finally {
            fixture.finishOrRetain()
        }
    }

    @Test
    fun sourceCloseFailureRetainsUncertaintyWithoutReleaseOrClientSuccessor() = runBlocking {
        val fixture = OriginalClientHandoffFixture.create(failClose = true)
        try {
            assertFailsWith<ControlledOriginalUnavailable> {
                observe { fixture.startAcquireCaller().await() }
            }
            observe { fixture.source.closeFinished.await() }
            fixture.awaitPhase(LocalRpcFactoryPhase.UNCERTAIN)
            fixture.assertUncertainCleanup()

            repeat(2) {
                assertFailsWith<IllegalStateException> { observe { fixture.owner.acquire() } }
                assertFailsWith<ControlledSourceCloseUnavailable> {
                    observe { fixture.startCloseCaller().await() }
                }
                fixture.assertUncertainCleanup()
            }

            fixture.factoryFixture.applicationJob.cancel()
            observe { fixture.factoryFixture.applicationJob.join() }
            assertFailsWith<ControlledSourceCloseUnavailable> {
                observe { fixture.startCloseCaller().await() }
            }
            fixture.assertUncertainCleanup()
            // A completed application Job is not source cleanup confirmation. Retain this
            // owner/source and its protected files/key/context; do not call factoryFixture.finish().
        } finally {
            fixture.finishOrRetain()
        }
    }
}

private class ControlledOriginalUnavailable : IllegalStateException("Controlled original is unavailable")
private class ControlledSourceCloseUnavailable :
    IllegalStateException("Controlled original source close is unavailable")

private enum class OriginalSourceEvent {
    READ_ENTERED, ORIGINAL_UNAVAILABLE, READ_CANCELLED,
    CLOSE_ENTERED, CLOSE_RETURNED, CLOSE_FAILED, CLOSE_CANCELLED, HOLDER_RELEASED,
}

/** Negative callback seam only; there is deliberately no original constructor or positive seed. */
private class ControlledUnavailableOriginalSource(
    pauseRead: Boolean,
    pauseClose: Boolean,
    private val failClose: Boolean,
) : LocalRpcOriginalClientSource {
    val readEntered = CompletableDeferred<Unit>()
    val readFinished = CompletableDeferred<Unit>()
    val closeEntered = CompletableDeferred<Unit>()
    val closeFinished = CompletableDeferred<Unit>()
    val readCalls = AtomicInteger()
    val closeCalls = AtomicInteger()
    val readCancellations = AtomicInteger()
    val closeCancellations = AtomicInteger()
    val closeReturns = AtomicInteger()
    val closeFailures = AtomicInteger()
    val events = CopyOnWriteArrayList<OriginalSourceEvent>()
    private val readRelease = CompletableDeferred<Unit>().also { if (!pauseRead) it.complete(Unit) }
    private val closeRelease = CompletableDeferred<Unit>().also { if (!pauseClose) it.complete(Unit) }

    override suspend fun recoverOriginal(): LocalDevicePairingSdkOriginal {
        readCalls.incrementAndGet()
        events.add(OriginalSourceEvent.READ_ENTERED)
        readEntered.complete(Unit)
        try {
            readRelease.await()
            events.add(OriginalSourceEvent.ORIGINAL_UNAVAILABLE)
            throw ControlledOriginalUnavailable()
        } catch (failure: CancellationException) {
            readCancellations.incrementAndGet()
            events.add(OriginalSourceEvent.READ_CANCELLED)
            throw failure
        } finally {
            readFinished.complete(Unit)
        }
    }

    override suspend fun close() {
        closeCalls.incrementAndGet()
        events.add(OriginalSourceEvent.CLOSE_ENTERED)
        closeEntered.complete(Unit)
        try {
            closeRelease.await()
            if (failClose) {
                closeFailures.incrementAndGet()
                events.add(OriginalSourceEvent.CLOSE_FAILED)
                throw ControlledSourceCloseUnavailable()
            }
            closeReturns.incrementAndGet()
            events.add(OriginalSourceEvent.CLOSE_RETURNED)
        } catch (failure: CancellationException) {
            closeCancellations.incrementAndGet()
            events.add(OriginalSourceEvent.CLOSE_CANCELLED)
            throw failure
        } finally {
            closeFinished.complete(Unit)
        }
    }

    fun releaseRead() { readRelease.complete(Unit) }
    fun releaseClose() { closeRelease.complete(Unit) }
}

private class OriginalClientHandoffFixture private constructor(
    val factoryFixture: ProtectedFactoryFixture,
    val source: ControlledUnavailableOriginalSource,
) {
    private val originalProviderCounts = factoryFixture.profile.counts()
    private val originalProtectedEvidence = factoryFixture.profile.protectedEvidence()
    private val platformEntries = AtomicInteger()
    private val holderReleases = AtomicInteger()
    private val released = CompletableDeferred<Unit>()
    private val closeCallers = CopyOnWriteArrayList<Deferred<Unit>>()
    val owner = LocalRpcOriginalClientOwner(
        applicationScope = factoryFixture.applicationScope,
        parent = LocalRpcOriginalClientOwner.retentionLifetime(factoryFixture.applicationScope,
            factoryFixture.profile.trustStore),
        source = source,
        platform = { platformEntries.incrementAndGet(); factoryFixture.profile.platform },
        lan = FactoryReadbackInputs.neverStartedLan(),
        trustStore = factoryFixture.profile.trustStore,
        onConfirmedClose = {
            holderReleases.incrementAndGet()
            source.events.add(OriginalSourceEvent.HOLDER_RELEASED)
            released.complete(Unit)
        },
    )

    fun startAcquireCaller(start: CoroutineStart = CoroutineStart.DEFAULT): Deferred<RpcLocalIdentity> =
        factoryFixture.callerScope.async(start = start) { owner.acquire() }

    fun startCloseCaller(): Deferred<Unit> =
        factoryFixture.callerScope.async(start = CoroutineStart.UNDISPATCHED) { owner.close() }
            .also { closeCallers.add(it) }

    suspend fun awaitPhase(expected: LocalRpcFactoryPhase) {
        observe { owner.phase.first { it == expected } }
    }

    suspend fun awaitControlledCleanup() {
        observe { released.await() }
        awaitPhase(LocalRpcFactoryPhase.CLOSED)
        assertControlledCleanup()
    }

    fun assertPendingRead() {
        assertTrue(source.readEntered.isCompleted)
        assertFalse(source.readFinished.isCompleted)
        assertEquals(1, source.readCalls.get())
        assertEquals(0, source.closeCalls.get())
        assertEquals(0, source.readCancellations.get())
        assertEquals(0, holderReleases.get())
        assertFalse(released.isCompleted)
        assertEquals(listOf(OriginalSourceEvent.READ_ENTERED), source.events.toList())
        assertNoClientEntry()
    }

    fun assertPendingClose() {
        assertEquals(LocalRpcFactoryPhase.CLOSING, owner.phase.value)
        assertTrue(source.readFinished.isCompleted)
        assertTrue(source.closeEntered.isCompleted)
        assertFalse(source.closeFinished.isCompleted)
        assertEquals(1, source.readCalls.get())
        assertEquals(1, source.closeCalls.get())
        assertEquals(0, source.readCancellations.get())
        assertEquals(0, source.closeCancellations.get())
        assertEquals(0, source.closeReturns.get())
        assertEquals(0, source.closeFailures.get())
        assertEquals(0, holderReleases.get())
        assertFalse(released.isCompleted)
        assertEquals(listOf(OriginalSourceEvent.READ_ENTERED, OriginalSourceEvent.ORIGINAL_UNAVAILABLE,
            OriginalSourceEvent.CLOSE_ENTERED), source.events.toList())
        assertNoClientEntry()
    }

    fun assertControlledCleanup() {
        assertEquals(LocalRpcFactoryPhase.CLOSED, owner.phase.value)
        assertTrue(source.readFinished.isCompleted)
        assertTrue(source.closeFinished.isCompleted)
        assertEquals(1, source.readCalls.get())
        assertEquals(1, source.closeCalls.get())
        assertEquals(0, source.readCancellations.get())
        assertEquals(0, source.closeCancellations.get())
        assertEquals(1, source.closeReturns.get())
        assertEquals(0, source.closeFailures.get())
        assertEquals(1, holderReleases.get())
        assertTrue(released.isCompleted)
        assertEquals(listOf(OriginalSourceEvent.READ_ENTERED, OriginalSourceEvent.ORIGINAL_UNAVAILABLE,
            OriginalSourceEvent.CLOSE_ENTERED, OriginalSourceEvent.CLOSE_RETURNED,
            OriginalSourceEvent.HOLDER_RELEASED), source.events.toList())
        assertNoClientEntry()
    }

    fun assertUncertainCleanup() {
        assertEquals(LocalRpcFactoryPhase.UNCERTAIN, owner.phase.value)
        assertTrue(source.readFinished.isCompleted)
        assertTrue(source.closeFinished.isCompleted)
        assertEquals(1, source.readCalls.get())
        assertEquals(1, source.closeCalls.get())
        assertEquals(0, source.readCancellations.get())
        assertEquals(0, source.closeCancellations.get())
        assertEquals(0, source.closeReturns.get())
        assertEquals(1, source.closeFailures.get())
        assertEquals(0, holderReleases.get())
        assertFalse(released.isCompleted)
        assertEquals(listOf(OriginalSourceEvent.READ_ENTERED, OriginalSourceEvent.ORIGINAL_UNAVAILABLE,
            OriginalSourceEvent.CLOSE_ENTERED, OriginalSourceEvent.CLOSE_FAILED), source.events.toList())
        assertNoClientEntry()
    }

    private fun assertNoClientEntry() {
        assertNull(owner.original.value, "The controlled source supplies no genuine native original")
        assertEquals(0, platformEntries.get(), "Original refusal cannot reach the Client platform supplier")
        assertEquals(originalProviderCounts, factoryFixture.profile.counts(),
            "Only the supplied fixture's provider calls are observed; this is not a global effect oracle")
        assertEquals(originalProtectedEvidence, factoryFixture.profile.protectedEvidence())
    }

    /** Never let the existing fixture, which does not register this owner, erase unresolved work. */
    suspend fun finishOrRetain() {
        withContext(NonCancellable) {
            val controlledCleanupObserved = try {
                owner.phase.value == LocalRpcFactoryPhase.CLOSED && holderReleases.get() == 1 &&
                    released.isCompleted && source.closeFinished.isCompleted && source.closeReturns.get() == 1 &&
                    source.closeFailures.get() == 0 && platformEntries.get() == 0 &&
                    originalProviderCounts == factoryFixture.profile.counts() &&
                    originalProtectedEvidence == factoryFixture.profile.protectedEvidence()
            } catch (_: Exception) { false }
            if (controlledCleanupObserved) {
                factoryFixture.finish()
            } else {
                // Keep barriers, pending callers, exact source/owner, application context, key and
                // protected files. No forced barrier release or native-cleanup success is inferred.
                retainedUnconfirmed.add(this@OriginalClientHandoffFixture)
            }
        }
    }

    companion object {
        private val retainedUnconfirmed = CopyOnWriteArrayList<OriginalClientHandoffFixture>()

        fun create(pauseRead: Boolean = false, pauseClose: Boolean = false,
            failClose: Boolean = false): OriginalClientHandoffFixture =
            OriginalClientHandoffFixture(ProtectedFactoryFixture.create(),
                ControlledUnavailableOriginalSource(pauseRead, pauseClose, failClose))
    }
}
