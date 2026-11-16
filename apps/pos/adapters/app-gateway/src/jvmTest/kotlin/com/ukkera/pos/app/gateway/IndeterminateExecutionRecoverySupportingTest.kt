package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.*
import dev.p2pkit.rpc.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Self-authored, NOT independent. Controlled transport/journal DATA using unchanged
 * receipt unit inputs. These checks qualify recovery ordering and once-only dispatch,
 * not a financial producer, native persistence, actual Receipt or TCP/platform behavior. */
class IndeterminateExecutionRecoverySupportingTest {
    private val values get() = RetailCustomerReceiptFrozenV1GatewayFixture

    private class Adapter(val f: ControlledRetailReceiptGateway, var fault: RpcFailure?) :
        LocalMasterClientResultAcceptance, LocalMasterClient by f.endpoint {
        var beforeFailure: () -> Unit = {}
        var beforeLookupFailure: () -> Unit = {}
        val lookupFaults = ArrayDeque<RpcFailure>()
        val accepted = mutableListOf<String>()
        override suspend fun executeAccepting(credentials: LocalGatewayCredentials, request: MutationRequest,
            accept: suspend (MutationReply.Recorded) -> Unit): MutationReply {
            fault?.takeIf { it.executionEvidence == RpcExecutionEvidence.NotSent }?.let { throw it }
            val result = f.endpoint.execute(credentials, request)
            if (result is MutationReply.Recorded) f.endpoint.found = RetailCustomerReceiptFrozenV1GatewayFixture.reply(replayed = true)
            beforeFailure()
            fault?.let { throw it }
            if (result is MutationReply.Recorded) {
                accept(result)
                check(f.journal.entries[request.operationId]?.recorded == StoredKernelResultV1.from(result))
                accepted += "execute"
            }
            return result
        }
        override suspend fun lookupAccepting(credentials: LocalGatewayCredentials, identity: OperationIdentity,
            accept: suspend (MutationReply.Recorded) -> Unit): LookupReply {
            val result = f.endpoint.lookup(credentials, identity)
            if (f.endpoint.executions.isNotEmpty() && lookupFaults.isNotEmpty()) {
                val failure = lookupFaults.removeFirst()
                beforeLookupFailure()
                throw failure
            }
            if (result is LookupReply.Found) {
                accept(result.result)
                check(f.journal.entries[identity.operationId]?.recorded == StoredKernelResultV1.from(result.result))
                accepted += "lookup"
            }
            return result
        }
        fun gateway() = LocalEndpointWorkflowGateway(this, f.journal, f.credentials, f.installed)
    }

    private fun uncertain(kind: RpcFailureKind = RpcFailureKind.DeadlineExceeded,
        evidence: RpcExecutionEvidence = RpcExecutionEvidence.MayHaveExecuted) =
        RpcFailure(kind, if (evidence == RpcExecutionEvidence.NotSent) RpcFailurePhase.Admission
            else RpcFailurePhase.AwaitingResponse, executionEvidence = evidence)

    @Test fun mayHaveExecutedRecoversExactOriginalDurablyWithoutSecondDispatch() = runTest {
        for (kind in listOf(RpcFailureKind.DeadlineExceeded, RpcFailureKind.UnknownOutcome)) {
            val f = ControlledRetailReceiptGateway()
            assertIs<LocalStageResult.Staged>(f.gateway.stage(values.intent()))
            val adapter = Adapter(f, uncertain(kind))
            assertIs<LocalOperationResult.Recorded>(adapter.gateway().submit(values.uiOperation))
            assertEquals(1, f.endpoint.executions.size)
            assertEquals(2, f.endpoint.lookups.size) // pre-dispatch absence, then original recovery
            val entry = assertNotNull(f.journal.entries[values.uiOperation])
            assertEquals(listOf(entry.identity(), entry.identity()), f.endpoint.lookups)
            assertEquals(StoredKernelResultV1.from(values.reply()), entry.recorded)
            assertEquals(listOf("lookup"), adapter.accepted)
            assertFalse(entry.acknowledged)
        }
    }

    @Test fun handlerFinishedWithoutDeliveredOutcomeRecoversOnlyTheSameOriginal() = runTest {
        for (kind in listOf(RpcFailureKind.DeadlineExceeded, RpcFailureKind.UnknownOutcome)) {
            val f = ControlledRetailReceiptGateway()
            assertIs<LocalStageResult.Staged>(f.gateway.stage(values.intent()))
            val adapter = Adapter(f, uncertain(kind, RpcExecutionEvidence.HandlerFinished))
            assertIs<LocalOperationResult.Recorded>(adapter.gateway().submit(values.uiOperation))
            assertEquals(1, f.endpoint.executions.size)
            val entry = assertNotNull(f.journal.entries[values.uiOperation])
            assertEquals(listOf(entry.identity(), entry.identity()), f.endpoint.lookups)
            assertEquals(StoredKernelResultV1.from(values.reply()), entry.recorded)
            assertEquals(listOf("lookup"), adapter.accepted)
            assertFalse(entry.acknowledged)
        }
    }

    @Test fun interruptedOriginalReadRetriesTheSameIdentityWithoutExecutingAgain() = runTest {
        for (evidence in listOf(RpcExecutionEvidence.MayHaveExecuted, RpcExecutionEvidence.HandlerFinished)) {
            val f = ControlledRetailReceiptGateway()
            assertIs<LocalStageResult.Staged>(f.gateway.stage(values.intent()))
            val adapter = Adapter(f, uncertain()).apply { lookupFaults.add(uncertain(evidence = evidence)) }
            assertIs<LocalOperationResult.Recorded>(adapter.gateway().submit(values.uiOperation))
            assertEquals(1, f.endpoint.executions.size)
            val entry = assertNotNull(f.journal.entries[values.uiOperation])
            assertEquals(List(3) { entry.identity() }, f.endpoint.lookups)
            assertEquals(StoredKernelResultV1.from(values.reply()), entry.recorded)
            assertEquals(listOf("lookup"), adapter.accepted)
            assertFalse(entry.acknowledged)
        }
    }

    @Test fun exhaustedOriginalReadsPreserveUnknownInputWithoutDuplicateEffects() = runTest {
        val f = ControlledRetailReceiptGateway()
        assertIs<LocalStageResult.Staged>(f.gateway.stage(values.intent()))
        val adapter = Adapter(f, uncertain()).apply { repeat(3) { lookupFaults.add(uncertain()) } }
        assertIs<LocalOperationResult.Unresolved>(adapter.gateway().submit(values.uiOperation))
        assertEquals(1, f.endpoint.executions.size)
        val entry = assertNotNull(f.journal.entries[values.uiOperation])
        assertEquals(List(4) { entry.identity() }, f.endpoint.lookups)
        assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED.name, entry.phase)
        assertNull(entry.recorded)
        assertTrue(adapter.accepted.isEmpty())
    }

    @Test fun revocationBetweenRecoveryReadsStopsFurtherReadsAndDisclosure() = runTest {
        val f = ControlledRetailReceiptGateway()
        assertIs<LocalStageResult.Staged>(f.gateway.stage(values.intent()))
        val adapter = Adapter(f, uncertain()).apply {
            lookupFaults.add(uncertain())
            beforeLookupFailure = { f.lifetimeCurrent = false }
        }
        assertIs<LocalOperationResult.Unresolved>(adapter.gateway().submit(values.uiOperation))
        assertEquals(1, f.endpoint.executions.size)
        assertEquals(2, f.endpoint.lookups.size)
        assertNull(assertNotNull(f.journal.entries[values.uiOperation]).recorded)
        assertTrue(adapter.accepted.isEmpty())
    }

    @Test fun absentOriginalKeepsDispatchedInputUnresolvedWithoutResubmission() = runTest {
        val f = ControlledRetailReceiptGateway()
        assertIs<LocalStageResult.Staged>(f.gateway.stage(values.intent()))
        f.endpoint.executionReply = MutationReply.Deferred(values.uiOperation, DeferredReason.SERVICE_BUSY)
        val adapter = Adapter(f, uncertain())
        assertIs<LocalOperationResult.Unresolved>(adapter.gateway().submit(values.uiOperation))
        assertEquals(1, f.endpoint.executions.size)
        assertEquals(2, f.endpoint.lookups.size)
        val entry = assertNotNull(f.journal.entries[values.uiOperation])
        assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED.name, entry.phase)
        assertNull(entry.recorded)
        assertFalse(entry.acknowledged)
        assertTrue(adapter.accepted.isEmpty())
    }

    @Test fun currentSessionLossRefusesRecoveryAndDoesNotDiscloseKnownResult() = runTest {
        val f = ControlledRetailReceiptGateway()
        assertIs<LocalStageResult.Staged>(f.gateway.stage(values.intent()))
        val adapter = Adapter(f, uncertain()).apply { beforeFailure = { f.lifetimeCurrent = false } }
        assertIs<LocalOperationResult.Unresolved>(adapter.gateway().submit(values.uiOperation))
        assertEquals(1, f.endpoint.executions.size)
        assertEquals(1, f.endpoint.lookups.size)
        assertNull(assertNotNull(f.journal.entries[values.uiOperation]).recorded)
        assertTrue(adapter.accepted.isEmpty())
    }

    @Test fun failedDurableAcceptanceSendsNoReceiptAndCannotBecomeKnownViaFallback() = runTest {
        val f = ControlledRetailReceiptGateway()
        assertIs<LocalStageResult.Staged>(f.gateway.stage(values.intent()))
        f.journal.rememberAvailable = false
        val adapter = Adapter(f, null)
        assertIs<LocalOperationResult.Unresolved>(adapter.gateway().submit(values.uiOperation))
        assertEquals(1, f.endpoint.executions.size)
        assertEquals(1, f.endpoint.lookups.size)
        assertTrue(adapter.accepted.isEmpty())
        assertNull(assertNotNull(f.journal.entries[values.uiOperation]).recorded)
    }

    @Test fun notSentFailureDoesNotDispatchAgainOrPretendToRecoverAnOriginal() = runTest {
        val f = ControlledRetailReceiptGateway()
        assertIs<LocalStageResult.Staged>(f.gateway.stage(values.intent()))
        val adapter = Adapter(f, uncertain(evidence = RpcExecutionEvidence.NotSent))
        assertIs<LocalOperationResult.Unresolved>(adapter.gateway().submit(values.uiOperation))
        assertTrue(f.endpoint.executions.isEmpty())
        assertEquals(1, f.endpoint.lookups.size)
        assertNull(assertNotNull(f.journal.entries[values.uiOperation]).recorded)
        assertTrue(adapter.accepted.isEmpty())
    }
}
