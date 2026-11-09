package com.ukkera.pos.rpc.independent.factorylifecycle

import com.ukkera.pos.rpc.LocalRpcFactoryPhase
import com.ukkera.pos.rpc.LocalRpcOriginalHostOwner
import com.ukkera.pos.rpc.LocalRpcOriginalHostSource
import com.ukkera.pos.security.LocalMasterSdkOriginalV1
import dev.p2pkit.rpc.RpcLocalIdentity
import dev.p2pkit.rpc.RpcPlatform
import dev.p2pkit.rpc.RpcTrustStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Frozen master-rpc-host-handoff v1.0.0 MHH-02-P1/P2, MHH-04-P1,
 * MHH-05-P1/P2 and MHH-06-P1 common-owner supporting slices. Current/lifetime
 * checks are negative contract-clause-4 slices, not genuine stale-O1/F1 variants.
 * Sources only suspend/refuse: no original, native capability, trust store or
 * returned official Host role is manufactured. Supplier counts and CLOSED prove
 * this common negative route only, never native custody/yield/actual factory close,
 * service retention/slot/loan, authority, persistence, networking or restart.
 */
class LocalRpcOriginalHostHandoffSupportingTest {
    @Test
    fun missingOriginalIsReadOnceAndCannotTriggerHostConstructionOrReplacement() = runBlocking {
        val fixture = OriginalHostHandoffFixture()
        try {
            assertEquals(LocalRpcFactoryPhase.NEW, fixture.owner.phase.value)
            assertFailsWith<ControlledMissingMasterOriginal> {
                observe { fixture.startAcquireCaller().await() }
            }
            fixture.awaitCommonClosed()
            assertRefused { fixture.startAcquireCaller().await() }
            fixture.assertReadRefusedWithoutHostEntry()
            observe { fixture.startCloseCaller().await() }
            fixture.assertReadRefusedWithoutHostEntry()
        } finally { fixture.finishOrRetain() }
    }

    @Test
    fun uncertainOriginalReadCannotUpgradeRepairOrReconstructAnOriginal() = runBlocking {
        val fixture = OriginalHostHandoffFixture(refusal = OriginalHostRefusal.UNCERTAIN)
        try {
            assertFailsWith<ControlledUncertainMasterOriginal> {
                observe { fixture.startAcquireCaller().await() }
            }
            fixture.awaitCommonClosed()
            assertRefused { fixture.startAcquireCaller().await() }
            fixture.assertReadRefusedWithoutHostEntry()
            // This is the explicit read-failure alternative of MHH-02-P2. No
            // legacy native record or candidate authenticated original is supplied.
        } finally { fixture.finishOrRetain() }
    }

    @Test
    fun noncurrentRegistrationRefusesBeforeAdmittingAnOriginalRead() = runBlocking {
        val fixture = OriginalHostHandoffFixture(current = false)
        try {
            repeat(2) { assertRefused { fixture.startAcquireCaller().await() } }
            assertEquals(0, fixture.source.readCalls.get())
            fixture.assertNoHostEntry()
            observe { fixture.startCloseCaller().await() }
            assertEquals(LocalRpcFactoryPhase.CLOSED, fixture.owner.phase.value)
            assertEquals(0, fixture.source.readCalls.get())
            fixture.assertNoHostEntry()
        } finally { fixture.finishOrRetain() }
    }

    @Test
    fun registrationLossWhileReadIsHeldDeniesLaterWaitersWithoutReplacingTheRead() = runBlocking {
        val fixture = OriginalHostHandoffFixture(holdRead = true)
        try {
            val first = fixture.startAcquireCaller()
            observe { fixture.source.readEntered.await() }
            fixture.assertPendingRead()

            fixture.source.withdrawCurrentRegistration()
            assertRefused { fixture.startAcquireCaller().await() }
            assertFalse(first.isCompleted)
            fixture.assertPendingRead()

            val closing = fixture.startCloseCaller()
            fixture.awaitPhase(LocalRpcFactoryPhase.CLOSING)
            assertFalse(closing.isCompleted)
            fixture.assertPendingRead(LocalRpcFactoryPhase.CLOSING)
            fixture.source.releaseRead()
            assertFailsWith<ControlledMissingMasterOriginal> { observe { first.await() } }
            observe { closing.await() }
            fixture.awaitCommonClosed()
            fixture.assertReadRefusedWithoutHostEntry()
            // No O1 returns, so this cannot establish revalidation after a
            // genuine positive yield or after official construction admission.
        } finally { fixture.finishOrRetain() }
    }

    @Test
    fun concurrentAndLaterWaitersShareOneHeldOriginalReadAndItsRefusal() = runBlocking {
        val fixture = OriginalHostHandoffFixture(holdRead = true)
        try {
            val first = fixture.startAcquireCaller()
            observe { fixture.source.readEntered.await() }
            val second = fixture.startAcquireCaller()
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            fixture.assertPendingRead()

            fixture.source.releaseRead()
            assertFailsWith<ControlledMissingMasterOriginal> { observe { first.await() } }
            assertFailsWith<ControlledMissingMasterOriginal> { observe { second.await() } }
            fixture.awaitCommonClosed()
            assertRefused { fixture.startAcquireCaller().await() }
            fixture.assertReadRefusedWithoutHostEntry()
        } finally { fixture.finishOrRetain() }
    }

    @Test
    fun cancellingOneWaiterKeepsTheAdmittedReadForTheRemainingWaiter() = runBlocking {
        val fixture = OriginalHostHandoffFixture(holdRead = true)
        try {
            val first = fixture.startAcquireCaller()
            observe { fixture.source.readEntered.await() }
            val second = fixture.startAcquireCaller()
            assertFalse(second.isCompleted)
            fixture.assertPendingRead()

            observe { first.cancelAndJoin() }
            assertTrue(first.isCancelled)
            assertFalse(second.isCompleted)
            assertTrue(fixture.applicationJob.isActive)
            fixture.assertPendingRead()

            fixture.source.releaseRead()
            assertFailsWith<ControlledMissingMasterOriginal> { observe { second.await() } }
            fixture.awaitCommonClosed()
            assertRefused { fixture.startAcquireCaller().await() }
            fixture.assertReadRefusedWithoutHostEntry()
        } finally { fixture.finishOrRetain() }
    }

    @Test
    fun cancellingTheOnlyWaiterDoesNotAbandonReadBeforeALaterWaiterArrives() = runBlocking {
        val fixture = OriginalHostHandoffFixture(holdRead = true)
        try {
            val abandoned = fixture.startAcquireCaller()
            observe { fixture.source.readEntered.await() }
            observe { abandoned.cancelAndJoin() }
            assertTrue(abandoned.isCancelled)
            assertTrue(fixture.applicationJob.isActive)
            fixture.assertPendingRead()

            val later = fixture.startAcquireCaller()
            assertFalse(later.isCompleted)
            fixture.assertPendingRead()
            fixture.source.releaseRead()
            assertFailsWith<ControlledMissingMasterOriginal> { observe { later.await() } }
            fixture.awaitCommonClosed()
            assertRefused { fixture.startAcquireCaller().await() }
            fixture.assertReadRefusedWithoutHostEntry()
        } finally { fixture.finishOrRetain() }
    }

    @Test
    fun closeAndCancelledCloseWaiterDrainTheSameHeldReadBeforeCommonCompletion() = runBlocking {
        val fixture = OriginalHostHandoffFixture(holdRead = true)
        try {
            val acquiring = fixture.startAcquireCaller()
            observe { fixture.source.readEntered.await() }
            val closing = fixture.startCloseCaller()
            fixture.awaitPhase(LocalRpcFactoryPhase.CLOSING)
            assertFalse(closing.isCompleted)
            fixture.assertPendingRead(LocalRpcFactoryPhase.CLOSING)
            assertRefused { fixture.startAcquireCaller().await() }

            closing.cancel()
            assertTrue(closing.isCancelled)
            assertFalse(closing.isCompleted, "caller cancellation cannot finish the held common drain")
            val repeated = fixture.startCloseCaller()
            assertFalse(repeated.isCompleted)
            fixture.assertPendingRead(LocalRpcFactoryPhase.CLOSING)

            fixture.source.releaseRead()
            assertFailsWith<ControlledMissingMasterOriginal> { observe { acquiring.await() } }
            observe { closing.join() }
            observe { repeated.await() }
            fixture.awaitCommonClosed()
            assertTrue(closing.isCancelled)
            assertRefused { fixture.startAcquireCaller().await() }
            observe { fixture.startCloseCaller().await() }
            fixture.assertReadRefusedWithoutHostEntry()
            // No F1 exists: observed completion is common missing-read drain,
            // not actual factory close or authoritative Master resource release.
        } finally { fixture.finishOrRetain() }
    }

    @Test
    fun retainedApplicationCancellationDrainsHeldReadAndDeniesNewPublication() = runBlocking {
        val fixture = OriginalHostHandoffFixture(holdRead = true)
        try {
            val acquiring = fixture.startAcquireCaller()
            observe { fixture.source.readEntered.await() }
            fixture.applicationJob.cancel()
            fixture.awaitPhase(LocalRpcFactoryPhase.CLOSING)
            assertFalse(fixture.applicationJob.isActive)
            assertFalse(fixture.applicationJob.isCompleted)
            assertFalse(acquiring.isCompleted)
            fixture.assertPendingRead(LocalRpcFactoryPhase.CLOSING)
            assertRefused { fixture.startAcquireCaller().await() }

            val repeatedClose = fixture.startCloseCaller()
            assertFalse(repeatedClose.isCompleted)
            fixture.source.releaseRead()
            assertFailsWith<ControlledMissingMasterOriginal> { observe { acquiring.await() } }
            observe { fixture.applicationJob.join() }
            observe { repeatedClose.await() }
            fixture.awaitCommonClosed()
            assertRefused { fixture.startAcquireCaller().await() }
            fixture.assertReadRefusedWithoutHostEntry()
        } finally { fixture.finishOrRetain() }
    }
}

private enum class OriginalHostRefusal { MISSING, UNCERTAIN }
private class ControlledMissingMasterOriginal : IllegalStateException("Controlled Master original is missing")
private class ControlledUncertainMasterOriginal : IllegalStateException("Controlled Master original read is uncertain")
private enum class OriginalHostSourceEvent { READ_ENTERED, READ_REFUSED, READ_CANCELLED, READ_FINISHED }

/** There is deliberately no original constructor, positive seed or native close callback. */
private class ControlledRefusingMasterOriginalSource(
    holdRead: Boolean,
    current: Boolean,
    private val refusal: OriginalHostRefusal,
) : LocalRpcOriginalHostSource {
    private val registrationCurrent = AtomicBoolean(current)
    private val readRelease = CompletableDeferred<Unit>().also { if (!holdRead) it.complete(Unit) }
    val readEntered = CompletableDeferred<Unit>()
    val readFinished = CompletableDeferred<Unit>()
    val readCalls = AtomicInteger()
    val readCancellations = AtomicInteger()
    val trustStoreEntries = AtomicInteger()
    val events = CopyOnWriteArrayList<OriginalHostSourceEvent>()

    override fun isCurrent(): Boolean = registrationCurrent.get()

    override suspend fun yieldOriginal(): LocalMasterSdkOriginalV1 {
        readCalls.incrementAndGet()
        events.add(OriginalHostSourceEvent.READ_ENTERED)
        readEntered.complete(Unit)
        try {
            readRelease.await()
            events.add(OriginalHostSourceEvent.READ_REFUSED)
            when (refusal) {
                OriginalHostRefusal.MISSING -> throw ControlledMissingMasterOriginal()
                OriginalHostRefusal.UNCERTAIN -> throw ControlledUncertainMasterOriginal()
            }
        } catch (failure: CancellationException) {
            readCancellations.incrementAndGet()
            events.add(OriginalHostSourceEvent.READ_CANCELLED)
            throw failure
        } finally {
            events.add(OriginalHostSourceEvent.READ_FINISHED)
            readFinished.complete(Unit)
        }
    }

    override suspend fun trustStore(): RpcTrustStore {
        trustStoreEntries.incrementAndGet()
        error("A refused original cannot request a same-Master trust store")
    }

    fun releaseRead() { readRelease.complete(Unit) }
    fun withdrawCurrentRegistration() { registrationCurrent.set(false) }
}

private class OriginalHostHandoffFixture(
    holdRead: Boolean = false,
    current: Boolean = true,
    refusal: OriginalHostRefusal = OriginalHostRefusal.MISSING,
) {
    val applicationJob = SupervisorJob()
    private val applicationScope = CoroutineScope(Dispatchers.Default + applicationJob)
    private val callersJob = SupervisorJob()
    private val callerScope = CoroutineScope(Dispatchers.Default + callersJob)
    val source = ControlledRefusingMasterOriginalSource(holdRead, current, refusal)
    private val platformSupplierEntries = AtomicInteger()
    private val publications = AtomicInteger()
    val owner = LocalRpcOriginalHostOwner(
        applicationScope = applicationScope,
        parent = LocalRpcOriginalHostOwner.retentionLifetime(applicationScope),
        source = source,
        platform = ::refusedPlatform,
        lan = FactoryReadbackInputs.neverStartedLan(),
    )

    private fun refusedPlatform(): RpcPlatform {
        platformSupplierEntries.incrementAndGet()
        error("A refused original cannot construct an official Host")
    }

    fun startAcquireCaller(): Deferred<RpcLocalIdentity> = callerScope.async(start = CoroutineStart.UNDISPATCHED) {
        owner.acquire().also { publications.incrementAndGet() }
    }

    fun startCloseCaller(): Deferred<Unit> = callerScope.async(start = CoroutineStart.UNDISPATCHED) {
        owner.closeOwned()
    }

    suspend fun awaitPhase(expected: LocalRpcFactoryPhase) {
        observe { owner.phase.first { it == expected } }
    }

    suspend fun awaitCommonClosed() {
        observe { startCloseCaller().await() }
        assertEquals(LocalRpcFactoryPhase.CLOSED, owner.phase.value)
        assertNoHostEntry()
    }

    fun assertPendingRead(expectedPhase: LocalRpcFactoryPhase = LocalRpcFactoryPhase.ACQUIRING) {
        assertEquals(expectedPhase, owner.phase.value)
        assertEquals(1, source.readCalls.get())
        assertTrue(source.readEntered.isCompleted)
        assertFalse(source.readFinished.isCompleted)
        assertEquals(0, source.readCancellations.get())
        assertEquals(listOf(OriginalHostSourceEvent.READ_ENTERED), source.events.toList())
        assertNoHostEntry()
    }

    fun assertReadRefusedWithoutHostEntry() {
        assertEquals(LocalRpcFactoryPhase.CLOSED, owner.phase.value)
        assertEquals(1, source.readCalls.get())
        assertTrue(source.readFinished.isCompleted)
        assertEquals(0, source.readCancellations.get())
        assertEquals(listOf(OriginalHostSourceEvent.READ_ENTERED, OriginalHostSourceEvent.READ_REFUSED,
            OriginalHostSourceEvent.READ_FINISHED), source.events.toList())
        assertNoHostEntry()
    }

    fun assertNoHostEntry() {
        assertNull(owner.original.value, "no genuine original is supplied or published by this negative source")
        assertEquals(0, source.trustStoreEntries.get())
        assertEquals(0, platformSupplierEntries.get(), "zero entries at the controlled Host platform supplier")
        assertEquals(0, publications.get(), "no waiter receives an identity result")
    }

    /** Drain only confirmed CLOSED or finished negative reads; never force a held read release. */
    suspend fun finishOrRetain() {
        withContext(NonCancellable) {
            // ACQUIRING may precede source entry: a zero read counter cannot
            // prove no acquisition was admitted. Retain all unconfirmed paths,
            // including NEW, instead of trying to establish no-admission here.
            val safeToDrain = owner.phase.value == LocalRpcFactoryPhase.CLOSED ||
                source.readFinished.isCompleted
            val noFactoryEntry = platformSupplierEntries.get() == 0 && source.trustStoreEntries.get() == 0
            if (safeToDrain && noFactoryEntry) {
                // Observe a separate retained caller. An observation timeout
                // must reach retention, not join an inline NonCancellable close.
                val close = runCatching {
                    val closing = startCloseCaller()
                    observe { closing.await() }
                }
                if (close.isSuccess && owner.phase.value == LocalRpcFactoryPhase.CLOSED) {
                    observe { applicationJob.cancelAndJoin() }
                    observe { callersJob.cancelAndJoin() }
                    return@withContext
                }
            }
            // Retain exact controlled owner/source/pending work. No barrier release,
            // factory/native close, replacement or successful teardown is inferred.
            retainedUnconfirmed.add(this@OriginalHostHandoffFixture)
        }
    }

    private companion object {
        val retainedUnconfirmed = CopyOnWriteArrayList<OriginalHostHandoffFixture>()
    }
}

private suspend fun assertRefused(block: suspend () -> RpcLocalIdentity) {
    val refusal = assertFails { observe { block() } }
    assertFalse(refusal is CancellationException, "a timeout/cancelled test waiter is not an observed refusal")
}
