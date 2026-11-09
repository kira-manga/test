package com.ukkera.pos.rpc.independent.factorylifecycle

import com.ukkera.pos.rpc.LocalRpcFactoryPhase
import com.ukkera.pos.rpc.LocalRpcOriginalClientOwner
import com.ukkera.pos.rpc.LocalRpcSelectedMasterClientReadback
import com.ukkera.pos.rpc.LocalRpcSelectedMasterClientSource
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
 * Bounded supporting wiring of frozen CMST-v1, CMST-03/04 PUBLIC-FAILURE/PUBLIC-WAITERS.
 * The selected source only waits or refuses: no successful original, approved Master,
 * readback, selected trust store or native role is manufactured. Observations cover this
 * exact controlled source, holder callback, publication StateFlows and supplied platform
 * entry only. They establish neither native cleanup/custody nor SDK-provider/global G0
 * effects, selected trust, real reconstruction, networking or business authority.
 * CMST-01/02 and genuine native/application/installed variants remain NOT RUN.
 */
class LocalRpcSelectedMasterClientFailureSupportingTest {
    @Test
    fun cmst03_failedSelectedReadAwaitsConfirmedExactCloseWithoutRetryOrPublication() = runBlocking {
        val fixture = SelectedMasterClientFailureFixture.create(pauseClose = true)
        try {
            val acquiring = fixture.startAcquireCaller()
            val failure = assertFailsWith<ControlledSelectedMasterReadUnavailable> { observe { acquiring.await() } }
            assertEquals(fixture.source.readFailure.message, failure.message)
            observe { fixture.source.closeEntered.await() }
            fixture.assertPendingClose()

            val closing = fixture.startCloseCaller()
            assertFalse(closing.isCompleted)
            assertFailsWith<IllegalStateException> { observe { fixture.owner.acquire() } }
            fixture.assertPendingClose()

            fixture.source.releaseClose()
            observe { closing.await() }
            fixture.awaitConfirmedCleanup()
            repeat(2) {
                assertFailsWith<IllegalStateException> { observe { fixture.owner.acquire() } }
                observe { fixture.startCloseCaller().await() }
                fixture.assertConfirmedCleanup()
            }
        } finally {
            fixture.finishOrRetain()
        }
    }

    @Test
    fun cmst03_failedSelectedReadAndFailedExactCloseRetainTheSameUnresolvedHolder() = runBlocking {
        val fixture = SelectedMasterClientFailureFixture.create(pauseClose = true, failClose = true)
        try {
            val readFailure = assertFailsWith<ControlledSelectedMasterReadUnavailable> {
                observe { fixture.startAcquireCaller().await() }
            }
            assertEquals(fixture.source.readFailure.message, readFailure.message)
            observe { fixture.source.closeEntered.await() }
            fixture.assertPendingClose()
            val closing = fixture.startCloseCaller()
            assertFalse(closing.isCompleted)
            fixture.assertPendingClose()

            fixture.source.releaseClose()
            val closeFailure = assertFailsWith<ControlledSelectedMasterCloseUnavailable> { observe { closing.await() } }
            assertEquals(fixture.source.closeFailure.message, closeFailure.message)
            observe { fixture.source.closeFinished.await() }
            fixture.awaitPhase(LocalRpcFactoryPhase.UNCERTAIN)
            fixture.assertUncertainCleanup()
            repeat(2) {
                assertFailsWith<IllegalStateException> { observe { fixture.owner.acquire() } }
                val sameCloseFailure = assertFailsWith<ControlledSelectedMasterCloseUnavailable> {
                    observe { fixture.startCloseCaller().await() }
                }
                assertEquals(fixture.source.closeFailure.message, sameCloseFailure.message)
                fixture.assertUncertainCleanup()
            }
            // A failed callback is not exact cleanup confirmation. Keep the complete
            // unresolved fixture; do not cancel its lifetime or erase its protected state.
        } finally {
            fixture.finishOrRetain()
        }
    }

    @Test
    fun cmst04_cancelledOneOfTwoSelectedReadWaitersLeavesTheAdmittedReadOwned() = runBlocking {
        val fixture = SelectedMasterClientFailureFixture.create(pauseRead = true, pauseClose = true)
        try {
            val temporary = fixture.startAcquireCaller()
            observe { fixture.source.readEntered.await() }
            val remaining = fixture.startAcquireCaller(CoroutineStart.UNDISPATCHED)
            assertFalse(temporary.isCompleted)
            assertFalse(remaining.isCompleted)
            fixture.assertPendingRead()

            observe { temporary.cancelAndJoin() }
            assertTrue(temporary.isCancelled)
            assertFalse(remaining.isCompleted)
            assertTrue(fixture.factoryFixture.applicationJob.isActive)
            assertEquals(LocalRpcFactoryPhase.ACQUIRING, fixture.owner.phase.value)
            fixture.assertPendingRead()

            fixture.source.releaseRead()
            val failure = assertFailsWith<ControlledSelectedMasterReadUnavailable> { observe { remaining.await() } }
            assertEquals(fixture.source.readFailure.message, failure.message)
            observe { fixture.source.closeEntered.await() }
            fixture.assertPendingClose()
            val closing = fixture.startCloseCaller()
            assertFalse(closing.isCompleted)
            fixture.source.releaseClose()
            observe { closing.await() }
            fixture.awaitConfirmedCleanup()
            assertFailsWith<IllegalStateException> { observe { fixture.owner.acquire() } }
            fixture.assertConfirmedCleanup()
        } finally {
            fixture.finishOrRetain()
        }
    }

    @Test
    fun cmst04_retainedExplicitCloseDrainsTheSameLateFailedReadAndHeldSourceClose() = runBlocking {
        val fixture = SelectedMasterClientFailureFixture.create(pauseRead = true, pauseClose = true)
        try {
            val acquiring = fixture.startAcquireCaller()
            observe { fixture.source.readEntered.await() }
            fixture.assertPendingRead()
            val closing = fixture.startCloseCaller()
            fixture.awaitPhase(LocalRpcFactoryPhase.CLOSING)
            assertFalse(closing.isCompleted)
            assertFalse(acquiring.isCompleted)
            assertTrue(fixture.factoryFixture.applicationJob.isActive)
            assertFailsWith<IllegalStateException> { observe { fixture.owner.acquire() } }
            fixture.assertPendingRead()

            fixture.source.releaseRead()
            val failure = assertFailsWith<ControlledSelectedMasterReadUnavailable> { observe { acquiring.await() } }
            assertEquals(fixture.source.readFailure.message, failure.message)
            observe { fixture.source.closeEntered.await() }
            assertFalse(closing.isCompleted)
            fixture.assertPendingClose()
            fixture.source.releaseClose()
            observe { closing.await() }
            fixture.awaitConfirmedCleanup()
            assertFailsWith<IllegalStateException> { observe { fixture.owner.acquire() } }
            fixture.assertConfirmedCleanup()
        } finally {
            fixture.finishOrRetain()
        }
    }

    @Test
    fun cmst04_retainedParentCancellationDrainsTheSameLateFailedReadAndHeldSourceClose() = runBlocking {
        val fixture = SelectedMasterClientFailureFixture.create(pauseRead = true, pauseClose = true)
        try {
            val acquiring = fixture.startAcquireCaller()
            observe { fixture.source.readEntered.await() }
            fixture.assertPendingRead()
            fixture.factoryFixture.applicationJob.cancel()
            fixture.awaitPhase(LocalRpcFactoryPhase.CLOSING)
            assertFalse(fixture.factoryFixture.applicationJob.isActive)
            assertFalse(fixture.factoryFixture.applicationJob.isCompleted)
            assertFalse(acquiring.isCompleted)
            assertFailsWith<IllegalStateException> { observe { fixture.owner.acquire() } }
            fixture.assertPendingRead()

            fixture.source.releaseRead()
            val failure = assertFailsWith<ControlledSelectedMasterReadUnavailable> { observe { acquiring.await() } }
            assertEquals(fixture.source.readFailure.message, failure.message)
            observe { fixture.source.closeEntered.await() }
            fixture.assertPendingClose()
            assertFalse(fixture.factoryFixture.applicationJob.isCompleted,
                "Retained cancellation must still await the exact held source close")
            val closing = fixture.startCloseCaller()
            assertFalse(closing.isCompleted)
            fixture.source.releaseClose()
            observe { fixture.factoryFixture.applicationJob.join() }
            observe { closing.await() }
            fixture.awaitConfirmedCleanup()
            assertFailsWith<IllegalStateException> { observe { fixture.owner.acquire() } }
            fixture.assertConfirmedCleanup()
        } finally {
            fixture.finishOrRetain()
        }
    }
}

private class ControlledSelectedMasterReadUnavailable : IllegalStateException("Controlled selected original read is unavailable")
private class ControlledSelectedMasterCloseUnavailable : IllegalStateException("Controlled selected source close is unavailable")

private enum class SelectedMasterSourceEvent {
    READ_ENTERED, READ_FAILED, READ_CANCELLED, CLOSE_ENTERED, CLOSE_RETURNED,
    CLOSE_FAILED, CLOSE_CANCELLED, HOLDER_RELEASED,
}

/** Exact controlled PUBLIC-FAILURE source correlation only; never a positive/native seed. */
private class ControlledFailingSelectedMasterSource(
    pauseRead: Boolean,
    pauseClose: Boolean,
    private val failClose: Boolean,
) : LocalRpcSelectedMasterClientSource {
    val readFailure = ControlledSelectedMasterReadUnavailable()
    val closeFailure = ControlledSelectedMasterCloseUnavailable()
    val readEntered = CompletableDeferred<Unit>()
    val readFinished = CompletableDeferred<Unit>()
    val closeEntered = CompletableDeferred<Unit>()
    val closeFinished = CompletableDeferred<Unit>()
    val readCalls = AtomicInteger()
    val readFailures = AtomicInteger()
    val readCancellations = AtomicInteger()
    val closeCalls = AtomicInteger()
    val closeReturns = AtomicInteger()
    val closeFailures = AtomicInteger()
    val closeCancellations = AtomicInteger()
    val events = CopyOnWriteArrayList<SelectedMasterSourceEvent>()
    private val readRelease = CompletableDeferred<Unit>().also { if (!pauseRead) it.complete(Unit) }
    private val closeRelease = CompletableDeferred<Unit>().also { if (!pauseClose) it.complete(Unit) }

    override suspend fun recover(): LocalRpcSelectedMasterClientReadback {
        readCalls.incrementAndGet()
        events.add(SelectedMasterSourceEvent.READ_ENTERED)
        readEntered.complete(Unit)
        try {
            readRelease.await()
            readFailures.incrementAndGet()
            events.add(SelectedMasterSourceEvent.READ_FAILED)
            throw readFailure
        } catch (failure: CancellationException) {
            readCancellations.incrementAndGet()
            events.add(SelectedMasterSourceEvent.READ_CANCELLED)
            throw failure
        } finally {
            readFinished.complete(Unit)
        }
    }

    override suspend fun close() {
        closeCalls.incrementAndGet()
        events.add(SelectedMasterSourceEvent.CLOSE_ENTERED)
        closeEntered.complete(Unit)
        try {
            closeRelease.await()
            if (failClose) {
                closeFailures.incrementAndGet()
                events.add(SelectedMasterSourceEvent.CLOSE_FAILED)
                throw closeFailure
            }
            closeReturns.incrementAndGet()
            events.add(SelectedMasterSourceEvent.CLOSE_RETURNED)
        } catch (failure: CancellationException) {
            closeCancellations.incrementAndGet()
            events.add(SelectedMasterSourceEvent.CLOSE_CANCELLED)
            throw failure
        } finally {
            closeFinished.complete(Unit)
        }
    }

    fun releaseRead() { readRelease.complete(Unit) }
    fun releaseClose() { closeRelease.complete(Unit) }
}

private class SelectedMasterClientFailureFixture private constructor(
    val factoryFixture: ProtectedFactoryFixture,
    val source: ControlledFailingSelectedMasterSource,
) {
    private val platformEntries = AtomicInteger()
    private val holderReleases = AtomicInteger()
    private val released = CompletableDeferred<Unit>()
    private val acquireCallers = CopyOnWriteArrayList<Deferred<RpcLocalIdentity>>()
    private val closeCallers = CopyOnWriteArrayList<Deferred<Unit>>()
    val owner = LocalRpcOriginalClientOwner(
        applicationScope = factoryFixture.applicationScope,
        parent = LocalRpcOriginalClientOwner.retentionLifetime(factoryFixture.applicationScope),
        source = source,
        platform = { platformEntries.incrementAndGet(); factoryFixture.profile.platform },
        lan = FactoryReadbackInputs.neverStartedLan(),
        onConfirmedClose = {
            holderReleases.incrementAndGet()
            source.events.add(SelectedMasterSourceEvent.HOLDER_RELEASED)
            released.complete(Unit)
        },
    )

    fun startAcquireCaller(start: CoroutineStart = CoroutineStart.DEFAULT): Deferred<RpcLocalIdentity> =
        factoryFixture.callerScope.async(start = start) { owner.acquire() }.also { acquireCallers.add(it) }

    fun startCloseCaller(): Deferred<Unit> =
        factoryFixture.callerScope.async(start = CoroutineStart.UNDISPATCHED) { owner.close() }.also { closeCallers.add(it) }

    suspend fun awaitPhase(expected: LocalRpcFactoryPhase) { observe { owner.phase.first { it == expected } } }

    suspend fun awaitConfirmedCleanup() {
        observe { released.await() }
        awaitPhase(LocalRpcFactoryPhase.CLOSED)
        assertConfirmedCleanup()
    }

    fun assertPendingRead() {
        assertTrue(source.readEntered.isCompleted)
        assertFalse(source.readFinished.isCompleted)
        assertEquals(1, source.readCalls.get())
        assertEquals(0, source.readFailures.get())
        assertEquals(0, source.readCancellations.get())
        assertEquals(0, source.closeCalls.get())
        assertNoReleaseOrPublication()
        assertEquals(listOf(SelectedMasterSourceEvent.READ_ENTERED), source.events.toList())
    }

    fun assertPendingClose() {
        assertEquals(LocalRpcFactoryPhase.CLOSING, owner.phase.value)
        assertFailedRead()
        assertTrue(source.closeEntered.isCompleted)
        assertFalse(source.closeFinished.isCompleted)
        assertEquals(1, source.closeCalls.get())
        assertEquals(0, source.closeReturns.get())
        assertEquals(0, source.closeFailures.get())
        assertEquals(0, source.closeCancellations.get())
        assertNoReleaseOrPublication()
        assertEquals(listOf(SelectedMasterSourceEvent.READ_ENTERED, SelectedMasterSourceEvent.READ_FAILED,
            SelectedMasterSourceEvent.CLOSE_ENTERED), source.events.toList())
    }

    fun assertConfirmedCleanup() {
        assertEquals(LocalRpcFactoryPhase.CLOSED, owner.phase.value)
        assertFailedRead()
        assertTrue(source.closeEntered.isCompleted)
        assertTrue(source.closeFinished.isCompleted)
        assertEquals(1, source.closeCalls.get())
        assertEquals(1, source.closeReturns.get())
        assertEquals(0, source.closeFailures.get())
        assertEquals(0, source.closeCancellations.get())
        assertEquals(1, holderReleases.get())
        assertTrue(released.isCompleted)
        assertNoPublicationOrPlatformEntry()
        assertEquals(listOf(SelectedMasterSourceEvent.READ_ENTERED, SelectedMasterSourceEvent.READ_FAILED,
            SelectedMasterSourceEvent.CLOSE_ENTERED, SelectedMasterSourceEvent.CLOSE_RETURNED,
            SelectedMasterSourceEvent.HOLDER_RELEASED), source.events.toList())
    }

    fun assertUncertainCleanup() {
        assertEquals(LocalRpcFactoryPhase.UNCERTAIN, owner.phase.value)
        assertFailedRead()
        assertTrue(source.closeEntered.isCompleted)
        assertTrue(source.closeFinished.isCompleted)
        assertEquals(1, source.closeCalls.get())
        assertEquals(0, source.closeReturns.get())
        assertEquals(1, source.closeFailures.get())
        assertEquals(0, source.closeCancellations.get())
        assertNoReleaseOrPublication()
        assertEquals(listOf(SelectedMasterSourceEvent.READ_ENTERED, SelectedMasterSourceEvent.READ_FAILED,
            SelectedMasterSourceEvent.CLOSE_ENTERED, SelectedMasterSourceEvent.CLOSE_FAILED), source.events.toList())
    }

    private fun assertFailedRead() {
        assertTrue(source.readEntered.isCompleted)
        assertTrue(source.readFinished.isCompleted)
        assertEquals(1, source.readCalls.get())
        assertEquals(1, source.readFailures.get())
        assertEquals(0, source.readCancellations.get())
    }

    private fun assertNoReleaseOrPublication() {
        assertEquals(0, holderReleases.get())
        assertFalse(released.isCompleted)
        assertNoPublicationOrPlatformEntry()
    }

    private fun assertNoPublicationOrPlatformEntry() {
        assertNull(owner.original.value, "The refusing selected source supplies no original")
        assertNull(owner.approvedMaster.value, "No qualified approval is constructed or published")
        assertEquals(0, platformEntries.get(), "The supplied Client platform cannot be entered after read failure")
    }

    /** The underlying fixture does not register this owner; never erase unresolved ownership. */
    suspend fun finishOrRetain() {
        withContext(NonCancellable) {
            val controlledCleanupObserved = owner.phase.value == LocalRpcFactoryPhase.CLOSED &&
                holderReleases.get() == 1 && released.isCompleted && source.readFinished.isCompleted &&
                source.readCalls.get() == 1 && source.readFailures.get() == 1 && source.readCancellations.get() == 0 &&
                source.closeFinished.isCompleted && source.closeCalls.get() == 1 && source.closeReturns.get() == 1 &&
                source.closeFailures.get() == 0 && source.closeCancellations.get() == 0 && platformEntries.get() == 0 &&
                owner.original.value == null && owner.approvedMaster.value == null
            if (controlledCleanupObserved) {
                factoryFixture.finish()
            } else {
                // Preserve exact source/owner, barriers, all callers, retained application
                // context, protected files and key. Never force barriers or confirmed cleanup.
                retainedUnconfirmed.add(this@SelectedMasterClientFailureFixture)
            }
        }
    }

    companion object {
        private val retainedUnconfirmed = CopyOnWriteArrayList<SelectedMasterClientFailureFixture>()

        fun create(pauseRead: Boolean = false, pauseClose: Boolean = false,
            failClose: Boolean = false): SelectedMasterClientFailureFixture =
            SelectedMasterClientFailureFixture(ProtectedFactoryFixture.create(),
                ControlledFailingSelectedMasterSource(pauseRead, pauseClose, failClose))
    }
}
