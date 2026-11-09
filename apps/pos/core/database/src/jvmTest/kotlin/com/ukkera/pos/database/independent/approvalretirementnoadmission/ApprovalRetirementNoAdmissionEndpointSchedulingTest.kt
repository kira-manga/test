package com.ukkera.pos.database.independent.approvalretirementnoadmission

import com.ukkera.pos.database.LocalActionApprovalNoAdmissionReason
import com.ukkera.pos.database.LocalActionApprovalRetireResult
import com.ukkera.pos.database.LocalMasterEndpointHealth
import com.ukkera.pos.database.MutationRequest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * ARNA-01/08/12 finite scheduler slices. Real prequeue/accepted work, but services
 * always refuse and no native/business interpretation or durable client exists.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalRetirementNoAdmissionEndpointSchedulingTest {
    @Test fun queueFullRefusesOnlyTheExactLiveSubmissionAndItNeverReachesTheServiceAfterDrain() = runTest {
        val fixture = ArnaRefusalOnlyEndpointFixture(StandardTestDispatcher(testScheduler))
        try {
            val before = fixture.rowCounts()
            assertTrue(before.isNotEmpty())
            assertTrue(before.values.all { it == 0L })
            val otherRequest = ArnaV1Inputs.request(otherBinding = true)
            val otherAttempt = ArnaV1Inputs.attempt("I3", 1, otherRequest, otherBinding = true)
            val queued = async(start = CoroutineStart.UNDISPATCHED) { fixture.retire(otherAttempt, otherRequest) }
            val request = ArnaV1Inputs.request()
            val attempt = ArnaV1Inputs.attempt(request = request)
            val bytes = request.payload.copyBytes()
            val refusal = assertIs<LocalActionApprovalRetireResult.NotAdmitted>(fixture.retire(attempt, request))
            assertSame(attempt, refusal.attempt)
            assertEquals(LocalActionApprovalNoAdmissionReason.QUEUE_FULL, refusal.reason)
            assertTrue(fixture.approvals.started.isEmpty())
            runCurrent()
            assertSame(LocalActionApprovalRetireResult.Denied, queued.await())
            assertEquals(listOf(otherAttempt.approvalId), fixture.approvals.completed.map { it.approvalId })
            assertTrue(fixture.approvals.started.none { it.request.operationId == attempt.operationId })
            assertEquals(LocalMasterEndpointHealth.ACTIVE, fixture.endpoint.health.value)
            assertEquals(before, fixture.rowCounts())
            assertContentEquals(bytes, request.payload.copyBytes())
            assertEquals(0, fixture.unrelatedServiceCalls())
            assertTrue(fixture.retirements.isEmpty())
        } finally { fixture.shutdown() }
    }

    @Test fun stoppedEndpointRefusesBeforeQueueWithSameLiveAttemptAndNoLaterServiceWork() = runTest {
        val fixture = ArnaRefusalOnlyEndpointFixture(StandardTestDispatcher(testScheduler))
        try {
            val before = fixture.rowCounts()
            fixture.endpoint.stopAccepting()
            val request = ArnaV1Inputs.request()
            val attempt = ArnaV1Inputs.attempt(request = request)
            val result = assertIs<LocalActionApprovalRetireResult.NotAdmitted>(fixture.retire(attempt, request))
            assertSame(attempt, result.attempt)
            assertEquals(LocalActionApprovalNoAdmissionReason.ENDPOINT_UNAVAILABLE, result.reason)
            runCurrent()
            assertEquals(LocalMasterEndpointHealth.STOPPING, fixture.endpoint.health.value)
            assertTrue(fixture.approvals.started.isEmpty())
            assertEquals(before, fixture.rowCounts())
            assertEquals(0, fixture.unrelatedServiceCalls())
        } finally { fixture.shutdown() }
    }

    @Test fun aQueuedAcceptedSubmissionAbandonedByStopIsGenericUnavailableNotRetroactiveNoAdmission() = runTest {
        val fixture = ArnaRefusalOnlyEndpointFixture(StandardTestDispatcher(testScheduler))
        try {
            val before = fixture.rowCounts()
            val request = ArnaV1Inputs.request()
            val attempt = ArnaV1Inputs.attempt(request = request)
            val queued = async(start = CoroutineStart.UNDISPATCHED) { fixture.retire(attempt, request) }
            assertTrue(!queued.isCompleted)
            fixture.endpoint.stopAccepting()
            runCurrent()
            assertSame(LocalActionApprovalRetireResult.Unavailable, queued.await())
            assertTrue(fixture.approvals.started.isEmpty())
            assertEquals(before, fixture.rowCounts())
            assertEquals(0, fixture.unrelatedServiceCalls())
        } finally { fixture.shutdown() }
    }

    @Test fun acceptedGenericDenialBusyAndUnavailableRemainTheirOwnResults() = runTest {
        for (reply in listOf(LocalActionApprovalRetireResult.Denied, LocalActionApprovalRetireResult.Busy,
            LocalActionApprovalRetireResult.Unavailable)) {
            val fixture = ArnaRefusalOnlyEndpointFixture(StandardTestDispatcher(testScheduler))
            try {
                val before = fixture.rowCounts()
                fixture.approvals.reply = reply
                val request = ArnaV1Inputs.request()
                val attempt = ArnaV1Inputs.attempt(request = request)
                val caller = async(start = CoroutineStart.UNDISPATCHED) { fixture.retire(attempt, request) }
                runCurrent()
                assertSame(reply, caller.await())
                assertEquals(1, fixture.approvals.started.size)
                assertEquals(1, fixture.approvals.completed.size)
                assertEquals(LocalMasterEndpointHealth.ACTIVE, fixture.endpoint.health.value)
                assertTrue(fixture.retirements.isEmpty())
                assertEquals(before, fixture.rowCounts())
                assertEquals(0, fixture.unrelatedServiceCalls())
            } finally { fixture.shutdown() }
        }
    }

    @Test fun acceptedServiceWorkFinishesAfterCallerCancellationWithoutASecondDispatch() = runTest {
        val fixture = ArnaRefusalOnlyEndpointFixture(StandardTestDispatcher(testScheduler))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            val before = fixture.rowCounts()
            fixture.approvals.beforeReturn = { entered.complete(Unit); release.await() }
            val request = ArnaV1Inputs.request()
            val attempt = ArnaV1Inputs.attempt(request = request)
            val caller = async(start = CoroutineStart.UNDISPATCHED) { fixture.retire(attempt, request) }
            runCurrent()
            assertTrue(entered.isCompleted)
            assertEquals(1, fixture.approvals.started.size)
            caller.cancel()
            runCurrent()
            assertTrue(caller.isCancelled)
            assertTrue(fixture.approvals.completed.isEmpty())
            assertEquals(LocalMasterEndpointHealth.ACTIVE, fixture.endpoint.health.value)
            release.complete(Unit)
            runCurrent()
            assertEquals(1, fixture.approvals.completed.size)
            assertEquals(attempt.approvalId, fixture.approvals.completed.single().approvalId)
            assertEquals(attempt.operationId, fixture.approvals.completed.single().request.operationId)
            assertEquals(before, fixture.rowCounts())
            assertTrue(fixture.retirements.isEmpty())
            assertEquals(0, fixture.unrelatedServiceCalls())
        } finally {
            release.complete(Unit)
            fixture.shutdown()
        }
    }

    @Test fun malformedBindingAndLegacyPrequeueRefusalCannotAcquireDedicatedEvidence() = runTest {
        val fixture = ArnaRefusalOnlyEndpointFixture(StandardTestDispatcher(testScheduler))
        try {
            val before = fixture.rowCounts()
            val request = ArnaV1Inputs.request()
            val attempt = ArnaV1Inputs.attempt(request = request)
            assertSame(LocalActionApprovalRetireResult.Denied, fixture.retire(attempt,
                MutationRequest(request.operationId, request.command, ArnaV1Inputs.commandBody(discountMinor = 1_001))))
            val otherRequest = ArnaV1Inputs.request(otherBinding = true)
            val queued = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.retire(ArnaV1Inputs.attempt("I3", 1, otherRequest, otherBinding = true), otherRequest)
            }
            assertSame(LocalActionApprovalRetireResult.Busy, fixture.retireLegacy(request))
            assertTrue(fixture.approvals.started.isEmpty())
            runCurrent()
            assertSame(LocalActionApprovalRetireResult.Denied, queued.await())
            assertEquals(listOf(otherRequest.operationId), fixture.approvals.started.map { it.request.operationId })
            assertEquals(before, fixture.rowCounts())
            assertEquals(0, fixture.unrelatedServiceCalls())
        } finally { fixture.shutdown() }
    }
}
