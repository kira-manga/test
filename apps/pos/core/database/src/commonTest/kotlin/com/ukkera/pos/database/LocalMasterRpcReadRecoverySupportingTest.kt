package com.ukkera.pos.database

import dev.p2pkit.rpc.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Self-authored, NOT independent. Coordinator-only transport failure DATA and integer
 * results; these tests cannot mint a native identity, permission, result or writer. */
class LocalMasterRpcReadRecoverySupportingTest {
    private fun fault(kind: RpcFailureKind = RpcFailureKind.DeadlineExceeded,
        evidence: RpcExecutionEvidence = RpcExecutionEvidence.MayHaveExecuted) =
        RpcFailure(kind, RpcFailurePhase.AwaitingResponse, executionEvidence = evidence)

    @Test fun undeliveredTransportFaultStartsAFreshReadAttempt() = runTest {
        for (kind in listOf(RpcFailureKind.DeadlineExceeded, RpcFailureKind.UnknownOutcome))
            for (evidence in listOf(RpcExecutionEvidence.MayHaveExecuted, RpcExecutionEvidence.HandlerFinished)) {
                val attempts = mutableListOf<LocalMasterRpcReadAttempt>()
                val value = recoverUndeliveredRpcRead(current = {}) { attempt ->
                    attempts += attempt
                    if (attempts.size == 1) throw fault(kind, evidence)
                    42
                }
                assertEquals(42, value); assertEquals(2, attempts.size)
                assertNotSame(attempts[0], attempts[1]); assertFalse(attempts[0].delivered)
            }
    }

    @Test fun decodedDeliveryPreventsRetryAfterALaterTransportFailure() = runTest {
        val failure = fault(); var reads = 0
        assertSame(failure, assertFailsWith<RpcFailure> {
            recoverUndeliveredRpcRead(current = {}) { attempt -> reads++; attempt.observeDelivery(); throw failure }
        })
        assertEquals(1, reads)
    }

    @Test fun concreteDenialOverloadAndNotSentAreNotRetried() = runTest {
        for (failure in listOf(fault(RpcFailureKind.Unauthorized), fault(RpcFailureKind.Overloaded),
            fault(evidence = RpcExecutionEvidence.NotSent))) {
            var reads = 0
            assertSame(failure, assertFailsWith<RpcFailure> {
                recoverUndeliveredRpcRead(current = {}) { reads++; throw failure }
            })
            assertEquals(1, reads)
        }
    }

    @Test fun thirdUndeliveredFaultRemainsUnresolved() = runTest {
        val failures = List(3) { fault() }; var reads = 0
        assertSame(failures.last(), assertFailsWith<RpcFailure> {
            recoverUndeliveredRpcRead(current = {}) { throw failures[reads++] }
        })
        assertEquals(3, reads)
    }

    @Test fun lifetimeWithdrawalPreventsAnyReplacementRead() = runTest {
        var current = true; var reads = 0
        val withdrawn = CancellationException("synthetic lifetime ended")
        assertSame(withdrawn, assertFailsWith<CancellationException> {
            recoverUndeliveredRpcRead(current = { if (!current) throw withdrawn }) {
                reads++; current = false; throw fault()
            }
        })
        assertEquals(1, reads)
    }

    @Test fun cancellationDoesNotBecomeReadRecovery() = runTest {
        var reads = 0; val cancelled = CancellationException("synthetic caller cancelled")
        assertSame(cancelled, assertFailsWith<CancellationException> {
            recoverUndeliveredRpcRead(current = {}) { reads++; throw cancelled }
        })
        assertEquals(1, reads)
    }
}
