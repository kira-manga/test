package com.ukkera.pos.rpc.independent.factorylifecycle

import com.ukkera.pos.rpc.LocalRpcFactoryPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking

/**
 * Supporting RFLC-OWN-01/ACQUIRE-03/CLOSE-04 and the synthetic provider-failure PROFILE-02 cut.
 * Barriers are in the actual encrypted identity provider, not mock factories or injected roles.
 * Provider counters corroborate this fixture's calls only, never independent global owned-Kit,
 * native-close, LAN, admission, service or business-state observations. Whole cases remain separate.
 */
class LocalRpcFactoryLifecycleSupportingTest {
    @Test
    fun hostOriginalOwnerOutlivesCompletedAndCancelledShortCaller() = runBlocking {
        originalOutlivesShortCaller(FactoryRole.HOST)
    }

    @Test
    fun clientOriginalOwnerOutlivesCompletedAndCancelledShortCaller() = runBlocking {
        originalOutlivesShortCaller(FactoryRole.CLIENT)
    }

    @Test
    fun hostCancelledAcquisitionWaiterDoesNotReplaceOriginalPendingFactory() = runBlocking {
        cancelledAcquisitionWaiter(FactoryRole.HOST)
    }

    @Test
    fun clientCancelledAcquisitionWaiterDoesNotReplaceOriginalPendingFactory() = runBlocking {
        cancelledAcquisitionWaiter(FactoryRole.CLIENT)
    }

    @Test
    fun hostCancelledCloseCallerKeepsOriginalPendingAcquisitionCleanupOwned() = runBlocking {
        cancelledCloseCallerWhileAcquiring(FactoryRole.HOST)
    }

    @Test
    fun clientCancelledCloseCallerKeepsOriginalPendingAcquisitionCleanupOwned() = runBlocking {
        cancelledCloseCallerWhileAcquiring(FactoryRole.CLIENT)
    }

    @Test
    fun hostApplicationCancellationDrainsOriginalPendingAcquisition() = runBlocking {
        applicationCancellationWhileAcquiring(FactoryRole.HOST)
    }

    @Test
    fun clientApplicationCancellationDrainsOriginalPendingAcquisition() = runBlocking {
        applicationCancellationWhileAcquiring(FactoryRole.CLIENT)
    }

    @Test
    fun hostProviderFailureAfterDurableCommitRetainsUncertaintyWithoutRetry() = runBlocking {
        durableCommitCompletionFailure(FactoryRole.HOST)
    }

    @Test
    fun clientProviderFailureAfterDurableCommitRetainsUncertaintyWithoutRetry() = runBlocking {
        durableCommitCompletionFailure(FactoryRole.CLIENT)
    }

    private suspend fun originalOutlivesShortCaller(role: FactoryRole) {
        val fixture = ProtectedFactoryFixture.create()
        val shortJob = SupervisorJob(fixture.callerScope.coroutineContext[Job])
        val shortScope = CoroutineScope(Dispatchers.Default + shortJob)
        try {
            val owner = fixture.capture(role, FactoryReadbackInputs.arabic)
            val shortAcquire = shortScope.async { owner.acquire() }
            val s0 = observe { shortAcquire.await() }
            val original = ActualOriginal(s0)
            assertTrue(shortAcquire.isCompleted)
            assertFalse(shortAcquire.isCancelled)
            val originalProviderCounts = fixture.profile.counts()
            assertEquals(LocalRpcFactoryPhase.READY, owner.phase.value)
            original.assertUnchanged(observe { owner.acquire() })

            observe { shortJob.cancelAndJoin() }
            assertTrue(fixture.applicationJob.isActive)
            assertEquals(LocalRpcFactoryPhase.READY, owner.phase.value)
            original.assertUnchanged(observe { owner.acquire() })
            original.assertUnchanged(s0)
            assertEquals(originalProviderCounts, fixture.profile.counts())

            fixture.applicationJob.cancel()
            observe { fixture.applicationJob.join() }
            assertEquals(LocalRpcFactoryPhase.CLOSED, owner.phase.value)
            original.assertUnchanged(s0)
            assertFailsWith<IllegalStateException> { observe { owner.acquire() } }
            fixture.closeAndObserve(owner)
            assertEquals(originalProviderCounts, fixture.profile.counts())
        } finally {
            shortJob.cancel()
            fixture.finish()
        }
    }

    private suspend fun cancelledAcquisitionWaiter(role: FactoryRole) {
        val cut = ProtectedIdentityCommitCut(pause = true)
        val fixture = ProtectedFactoryFixture.create(cut)
        try {
            val owner = fixture.capture(role, FactoryReadbackInputs.english)
            val firstWaiter = fixture.callerScope.async { owner.acquire() }
            observe { cut.committed.await() }
            val originalProviderCounts = fixture.profile.counts()
            observe { firstWaiter.cancelAndJoin() }
            assertTrue(firstWaiter.isCancelled)
            assertTrue(fixture.applicationJob.isActive)
            assertEquals(LocalRpcFactoryPhase.ACQUIRING, owner.phase.value)

            val nextWaiter = fixture.callerScope.async(start = CoroutineStart.UNDISPATCHED) { owner.acquire() }
            assertFalse(nextWaiter.isCompleted)
            assertEquals(originalProviderCounts, fixture.profile.counts())
            cut.release()
            val s0 = observe { nextWaiter.await() }
            val original = ActualOriginal(s0)
            assertEquals(LocalRpcFactoryPhase.READY, owner.phase.value)
            original.assertUnchanged(observe { owner.acquire() })
            original.assertUnchanged(s0)
            assertEquals(originalProviderCounts, fixture.profile.counts(),
                "Cancelling one waiter must not restart the original factory through these providers")
            fixture.closeAndObserve(owner)
            original.assertUnchanged(s0)
            assertEquals(LocalRpcFactoryPhase.CLOSED, owner.phase.value)
        } finally {
            cut.release()
            fixture.finish()
        }
    }

    /** Pending acquisition is the cut; no claim of a barrier inside actual native SDK close. */
    private suspend fun cancelledCloseCallerWhileAcquiring(role: FactoryRole) {
        val cut = ProtectedIdentityCommitCut(pause = true)
        val fixture = ProtectedFactoryFixture.create(cut)
        try {
            val owner = fixture.capture(role, FactoryReadbackInputs.english)
            val acquiring = fixture.callerScope.async { owner.acquire() }
            observe { cut.committed.await() }
            val originalProviderCounts = fixture.profile.counts()
            val closeCaller = fixture.startCloseCaller(owner)
            awaitPhase(owner, LocalRpcFactoryPhase.CLOSING)
            assertFalse(closeCaller.isCompleted)
            closeCaller.cancel()
            assertTrue(closeCaller.isCancelled)
            assertEquals(LocalRpcFactoryPhase.CLOSING, owner.phase.value)
            assertFalse(closeCaller.isCompleted, "Caller cancellation cannot finish blocked original cleanup")

            val repeatedClose = fixture.startCloseCaller(owner)
            assertEquals(originalProviderCounts, fixture.profile.counts())
            cut.release()
            assertFailsWith<IllegalStateException> { observe { acquiring.await() } }
            observe { closeCaller.join() }
            observe { repeatedClose.await() }
            assertTrue(closeCaller.isCancelled)
            assertEquals(LocalRpcFactoryPhase.CLOSED, owner.phase.value)
            assertFailsWith<IllegalStateException> { observe { owner.acquire() } }
            assertEquals(originalProviderCounts, fixture.profile.counts())
        } finally {
            cut.release()
            fixture.finish()
        }
    }

    private suspend fun applicationCancellationWhileAcquiring(role: FactoryRole) {
        val cut = ProtectedIdentityCommitCut(pause = true)
        val fixture = ProtectedFactoryFixture.create(cut)
        try {
            val owner = fixture.capture(role, FactoryReadbackInputs.arabic)
            val acquiring = fixture.callerScope.async { owner.acquire() }
            observe { cut.committed.await() }
            val originalProviderCounts = fixture.profile.counts()
            fixture.applicationJob.cancel()
            awaitPhase(owner, LocalRpcFactoryPhase.CLOSING)
            assertFalse(fixture.applicationJob.isCompleted)
            assertFalse(acquiring.isCompleted)
            assertEquals(originalProviderCounts, fixture.profile.counts())

            cut.release()
            assertFailsWith<IllegalStateException> { observe { acquiring.await() } }
            observe { fixture.applicationJob.join() }
            assertEquals(LocalRpcFactoryPhase.CLOSED, owner.phase.value)
            assertFailsWith<IllegalStateException> { observe { owner.acquire() } }
            fixture.closeAndObserve(owner)
            assertEquals(originalProviderCounts, fixture.profile.counts())
        } finally {
            cut.release()
            fixture.finish()
        }
    }

    private suspend fun durableCommitCompletionFailure(role: FactoryRole) {
        val cut = ProtectedIdentityCommitCut(pause = false, failCompletion = true)
        val fixture = ProtectedFactoryFixture.create(cut)
        try {
            val owner = fixture.capture(role, FactoryReadbackInputs.english)
            val failure = assertFailsWith<Exception> { observe { owner.acquire() } }
            assertFalse(failure is CancellationException, "The selected cut must report actual provider failure")
            observe { cut.committed.await() }
            awaitPhase(owner, LocalRpcFactoryPhase.UNCERTAIN)
            val unresolvedCounts = fixture.profile.counts()
            val unresolvedProtectedEvidence = fixture.profile.protectedEvidence()
            assertTrue(unresolvedCounts.durableIdentityInserts > 0,
                "Provider failure after actual durable commit is not proof of no store effect")
            assertTrue(unresolvedProtectedEvidence.isNotEmpty())

            repeat(2) {
                assertFailsWith<IllegalStateException> { observe { owner.acquire() } }
                assertFailsWith<IllegalStateException> { fixture.closeAndObserve(owner) }
                assertEquals(LocalRpcFactoryPhase.UNCERTAIN, owner.phase.value)
                assertEquals(unresolvedCounts, fixture.profile.counts(),
                    "Retained uncertainty cannot allocate another factory through these providers")
                assertEquals(unresolvedProtectedEvidence, fixture.profile.protectedEvidence())
            }
            // No role was returned to the wrapper. Neither this failure nor failed repeated close
            // can establish exact native teardown. finish() retains this original encrypted fixture.
        } finally { fixture.finish() }
    }
}
