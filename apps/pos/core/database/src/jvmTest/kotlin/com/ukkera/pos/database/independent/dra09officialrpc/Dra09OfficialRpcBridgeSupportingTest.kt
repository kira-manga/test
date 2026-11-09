package com.ukkera.pos.database.independent.dra09officialrpc

import com.ukkera.pos.database.LookupReply
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.LocalMasterRpcExecuteResponseV1
import com.ukkera.pos.database.LocalMasterRpcExecuteValueV1
import com.ukkera.pos.types.OperationId
import dev.p2pkit.rpc.RpcCallProgress
import dev.p2pkit.rpc.RpcCallStage
import dev.p2pkit.rpc.RpcConnectionState
import dev.p2pkit.rpc.RpcExecutionEvidence
import dev.p2pkit.rpc.RpcFailure
import dev.p2pkit.rpc.RpcFailureKind
import dev.p2pkit.rpc.RpcHostCallState
import dev.p2pkit.rpc.RpcResultAcceptance
import dev.p2pkit.rpc.RpcRetry
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Frozen independent DR-A09 observations. The official rc12 Host/Client wire and
 * `callWithAcceptance` run for real; POS association/approval objects are controlled
 * synthetic DATA built from those running SDK identities and are not native custody.
 * See `qa/pos/traceability/dra09-official-rpc-bridge-20261109/README.md`.
 */
class Dra09OfficialRpcBridgeSupportingTest {
    private class TestAcceptanceFailure : Exception("DR-A09 controlled acceptance failure")

    private val terminalStages = setOf(
        RpcCallStage.Succeeded, RpcCallStage.BusinessError, RpcCallStage.Failed,
        RpcCallStage.Cancelled, RpcCallStage.TimedOut,
    )

    @Test
    fun dra0901OfficialAcceptanceObservesExactHostAssociationBeforeReceipt() = runBlocking {
        withDra09Fixture { fixture ->
            var accepted: RpcResultAcceptance<String, String>? = null
            var progressAtAcceptance: RpcCallProgress? = null
            var retainedAtAcceptance = 0L
            val observation = dev.p2pkit.rpc.RpcCallObservation()
            val details = fixture.client.callWithAcceptance(
                procedure = fixture.probe,
                request = "probe-request",
                timeout = 10.seconds,
                retry = RpcRetry.RecoverOnly(maxAttempts = 1),
                observation = observation,
            ) { acceptance ->
                accepted = acceptance
                progressAtAcceptance = observation.progress.value
                retainedAtAcceptance = fixture.awaitRetainedPayload()
                assertFalse(Files.exists(fixture.marker))
                writeDurableMarker(fixture.marker, acceptance.details.requestId.value)
            }

            val reply = assertIs<dev.p2pkit.rpc.RpcReply.Success<String>>(details.reply)
            assertEquals("probe-request", reply.value)
            val acceptance = assertNotNull(accepted)
            val entry = fixture.host.requests.value.entries.single()
            assertEquals(fixture.probe.name, entry.procedure)
            assertEquals(fixture.probe.version, entry.version)
            assertEquals(RpcHostCallState.Succeeded, entry.state)
            assertEquals(entry.requestId, acceptance.details.requestId.value)
            assertEquals(fixture.host.localIdentity.fingerprint, acceptance.hostFingerprint)
            assertEquals(fixture.client.trust.fingerprints().single(), acceptance.hostFingerprint)
            assertTrue(acceptance.hostIncarnation.isNotBlank())
            assertEquals(fixture.host.requests.value.hostIncarnation, acceptance.hostIncarnation)
            assertEquals(RpcExecutionEvidence.HandlerFinished, acceptance.details.executionEvidence)

            val progress = assertNotNull(progressAtAcceptance)
            assertEquals(acceptance.details.requestId.value, progress.requestId)
            assertEquals(acceptance.hostFingerprint.value, progress.peerFingerprint)
            assertEquals(acceptance.hostIncarnation, progress.hostIncarnation)
            assertEquals(RpcExecutionEvidence.HandlerFinished, progress.executionEvidence)
            assertFalse(progress.stage in terminalStages, "Acceptance must run before a terminal client stage")
            assertTrue(retainedAtAcceptance > 0, "The Host result body must be retained while acceptance is suspended")
            assertEquals(acceptance.details.requestId.value, Files.readString(fixture.marker))

            withTimeout(5_000) {
                fixture.host.diagnostics.first { it.retainedPayloadBytes < retainedAtAcceptance }
            }
            assertEquals(1L, fixture.host.requests.value.totals.accepted)
            assertEquals(1L, fixture.host.diagnostics.value.completedCalls)
            assertEquals(1L, fixture.host.requests.value.totals.succeeded)
            assertEquals(1L, fixture.client.requestTotals.value.succeeded)
            assertTrue(fixture.host.diagnostics.value.retainedRecords >= 1)
        }
    }

    @Test
    fun dra0902BridgeAcceptedRecordIsDurableBeforeOfficialReceipt() = runBlocking {
        withDra09Fixture { fixture ->
            val operationId = OperationId.random()
            var accepted: MutationReply.Recorded? = null
            var retainedAtAcceptance = 0L
            val result = fixture.bridge.execute(
                fixture.credentials, fixture.request(operationId), fixture.actor,
            ) { recorded ->
                accepted = recorded
                retainedAtAcceptance = fixture.awaitRetainedPayload()
                assertFalse(Files.exists(fixture.marker))
                writeDurableMarker(fixture.marker, recorded.operationId.value)
            }

            val recorded = assertIs<MutationReply.Recorded>(result)
            assertEquals(operationId, recorded.operationId)
            assertEquals(fixture.actor, recorded.originalActorId)
            assertTrue(recorded.committed)
            assertEquals("committed", recorded.code)
            assertFalse(recorded.replayed)
            assertContentEquals(ByteArray(4096) { (it % 251).toByte() }, recorded.result.copyBytes())
            assertEquals(1, fixture.executeCount.get())
            assertEquals(0, fixture.lookupCount.get())
            assertEquals(operationId, assertNotNull(accepted).operationId)
            assertTrue(retainedAtAcceptance > 0)
            assertEquals(operationId.value, Files.readString(fixture.marker))
            assertTrue(fixture.bridge.isCurrent())

            val entry = fixture.host.requests.value.entries.single()
            assertEquals("pos.business.execute", entry.procedure)
            assertEquals(1, entry.version)
            assertEquals(RpcHostCallState.Succeeded, entry.state)
            assertTrue(entry.requestId.isNotBlank())
            assertTrue(fixture.host.requests.value.hostIncarnation.isNotBlank())

            withTimeout(5_000) {
                fixture.host.diagnostics.first { it.retainedPayloadBytes < retainedAtAcceptance }
            }
            assertEquals(1L, fixture.host.requests.value.totals.accepted)
            assertEquals(1L, fixture.host.diagnostics.value.completedCalls)
            assertEquals(1L, fixture.client.requestTotals.value.succeeded)
        }
    }

    @Test
    fun dra0903BridgeWithoutConsumerWithholdsReceiptAndReturnsValidatedData() = runBlocking {
        withDra09Fixture { fixture ->
            val operationId = OperationId.random()
            val result = fixture.bridge.execute(fixture.credentials, fixture.request(operationId), fixture.actor)

            val recorded = assertIs<MutationReply.Recorded>(result)
            assertEquals(operationId, recorded.operationId)
            assertEquals(fixture.actor, recorded.originalActorId)
            assertTrue(recorded.committed)
            assertFalse(recorded.replayed)
            assertContentEquals(ByteArray(4096) { (it % 251).toByte() }, recorded.result.copyBytes())
            assertEquals(1, fixture.executeCount.get())
            fixture.awaitCompleted(1)
            val retained = fixture.awaitRetainedPayload()
            assertEquals(RpcHostCallState.Succeeded, fixture.host.requests.value.entries.single().state)
            assertFalse(Files.exists(fixture.marker))

            delay(1_500)
            assertEquals(retained, fixture.host.diagnostics.value.retainedPayloadBytes,
                "A bridge call without a durable consumer must not send its Receipt")
        }
    }

    @Test
    fun dra0904BridgeAcceptanceFailureWithholdsReceiptAndKeepsOriginalRecoverable() = runBlocking {
        withDra09Fixture { fixture ->
            val operationId = OperationId.random()
            val failure = TestAcceptanceFailure()
            var retainedAtAcceptance = 0L
            val thrown = runCatching {
                fixture.bridge.execute(fixture.credentials, fixture.request(operationId), fixture.actor) {
                    retainedAtAcceptance = fixture.awaitRetainedPayload()
                    throw failure
                }
            }.exceptionOrNull()

            assertSame(failure, thrown)
            assertTrue(retainedAtAcceptance > 0)
            assertFalse(Files.exists(fixture.marker))
            assertEquals(1, fixture.executeCount.get())
            assertEquals(RpcHostCallState.Succeeded, fixture.host.requests.value.entries.single().state)
            delay(1_500)
            assertTrue(fixture.host.diagnostics.value.retainedPayloadBytes >= retainedAtAcceptance,
                "A thrown acceptance must leave the Host result body retained")

            var recoveredAccepted: MutationReply.Recorded? = null
            val recovered = fixture.bridge.lookup(
                fixture.credentials, fixture.identity(operationId), fixture.actor,
            ) { recorded ->
                recoveredAccepted = recorded
                writeDurableMarker(fixture.marker, recorded.operationId.value)
            }
            val found = assertIs<LookupReply.Found>(recovered)
            assertEquals(operationId, found.result.operationId)
            assertTrue(found.result.replayed)
            assertEquals(operationId, assertNotNull(recoveredAccepted).operationId)
            assertEquals(operationId.value, Files.readString(fixture.marker))
            assertEquals(1, fixture.executeCount.get(), "Lookup recovery must not execute the original again")
            assertEquals(1, fixture.lookupCount.get())
            assertEquals(2L, fixture.host.requests.value.totals.accepted)
            assertEquals(2L, fixture.host.diagnostics.value.completedCalls)
            val lookupEntry = fixture.host.requests.value.entries.single { it.procedure == "pos.business.lookup" }
            assertEquals(1, lookupEntry.version)
            assertEquals(RpcHostCallState.Succeeded, lookupEntry.state)
        }
    }

    @Test
    fun dra0905BridgeCallerCancellationWithholdsReceiptAndDoesNotBecomeSuccess() = runBlocking {
        withDra09Fixture { fixture ->
            val operationId = OperationId.random()
            val started = CompletableDeferred<Unit>()
            var retainedAtAcceptance = 0L
            coroutineScope {
                val call = async {
                    fixture.bridge.execute(fixture.credentials, fixture.request(operationId), fixture.actor) {
                        retainedAtAcceptance = fixture.awaitRetainedPayload()
                        started.complete(Unit)
                        awaitCancellation()
                    }
                }
                withTimeout(10_000) { started.await() }
                assertEquals(1, fixture.executeCount.get())
                call.cancel()
                val failure = runCatching { call.await() }.exceptionOrNull()
                assertIs<CancellationException>(failure)
            }

            assertTrue(retainedAtAcceptance > 0)
            assertFalse(Files.exists(fixture.marker))
            assertEquals(1, fixture.executeCount.get())
            assertEquals(RpcHostCallState.Succeeded, fixture.host.requests.value.entries.single().state)
            delay(1_500)
            assertTrue(fixture.host.diagnostics.value.retainedPayloadBytes >= retainedAtAcceptance,
                "Cancellation during acceptance must not send a Receipt")
            assertEquals(1L, fixture.client.requestTotals.value.cancelled)
        }
    }

    @Test
    fun dra0906BridgeCurrentnessLossBeforeCallSuppressesWireAdmission() = runBlocking {
        withDra09Fixture { fixture ->
            assertTrue(fixture.bridge.isCurrent())
            fixture.bridge.deny()
            assertFalse(fixture.bridge.isCurrent())
            val failure = runCatching {
                fixture.bridge.execute(fixture.credentials, fixture.request(OperationId.random()), fixture.actor)
            }.exceptionOrNull()
            assertIs<IllegalStateException>(failure)
            assertEquals(0L, fixture.host.requests.value.totals.accepted)
            assertTrue(fixture.host.requests.value.entries.isEmpty())
            assertFalse(Files.exists(fixture.marker))
        }

        withDra09Fixture { fixture ->
            fixture.client.trust.revoke(fixture.host.localIdentity.fingerprint)
            withTimeout(10_000) { fixture.client.state.first { it != RpcConnectionState.Ready } }
            assertFalse(fixture.bridge.isCurrent())
            val failure = runCatching {
                fixture.bridge.execute(fixture.credentials, fixture.request(OperationId.random()), fixture.actor)
            }.exceptionOrNull()
            assertIs<IllegalStateException>(failure)
            assertEquals(0L, fixture.host.requests.value.totals.accepted)
            assertTrue(fixture.host.requests.value.entries.isEmpty())
            assertFalse(Files.exists(fixture.marker))
        }

        withDra09Fixture { fixture ->
            var retainedAtAcceptance = 0L
            val failure = runCatching {
                fixture.bridge.execute(fixture.credentials, fixture.request(OperationId.random()), fixture.actor) {
                    retainedAtAcceptance = fixture.awaitRetainedPayload()
                    fixture.bridge.deny()
                }
            }.exceptionOrNull()
            assertIs<IllegalStateException>(failure)
            assertTrue(retainedAtAcceptance > 0)
            assertFalse(fixture.bridge.isCurrent())
            assertFalse(Files.exists(fixture.marker))
            assertEquals(1, fixture.executeCount.get())
            delay(1_500)
            assertTrue(fixture.host.diagnostics.value.retainedPayloadBytes >= retainedAtAcceptance,
                "Currentness loss during acceptance must withhold the Receipt")
        }
    }

    @Test
    fun dra0907BridgeTypedActorOrOperationMismatchWithholdsReceiptBeforeConsumer() = runBlocking {
        for (variant in listOf("actor", "operation")) {
            withDra09Fixture { fixture ->
                val operationId = OperationId.random()
                fixture.executeResponse.set { request ->
                    when (variant) {
                        "actor" -> LocalMasterRpcExecuteResponseV1(
                            requestingActorId = com.ukkera.pos.types.DomainId.random(),
                            value = LocalMasterRpcExecuteValueV1.Recorded(fixture.recorded(request.request.operationId)),
                        )
                        else -> LocalMasterRpcExecuteResponseV1(
                            requestingActorId = fixture.actor,
                            value = LocalMasterRpcExecuteValueV1.Recorded(fixture.recorded(OperationId.random())),
                        )
                    }
                }
                var consumerCalled = false
                val failure = runCatching {
                    fixture.bridge.execute(fixture.credentials, fixture.request(operationId), fixture.actor) {
                        consumerCalled = true
                        writeDurableMarker(fixture.marker, it.operationId.value)
                    }
                }.exceptionOrNull()

                assertIs<SerializationException>(failure)
                assertFalse(consumerCalled)
                assertFalse(Files.exists(fixture.marker))
                fixture.awaitCompleted(1)
                val retained = fixture.awaitRetainedPayload()
                assertEquals(1, fixture.executeCount.get())
                assertEquals(RpcHostCallState.Succeeded, fixture.host.requests.value.entries.single().state)
                delay(1_500)
                assertTrue(fixture.host.diagnostics.value.retainedPayloadBytes >= retained,
                    "A typed association failure must withhold the Receipt")
            }
        }
    }

    @Test
    fun dra0908FixedV1BridgeSelectionDoesNotReachV2OnlyHost() = runBlocking {
        withDra09Fixture(executeVersion = 2) { fixture ->
            var consumerCalled = false
            val failure = runCatching {
                fixture.bridge.execute(fixture.credentials, fixture.request(OperationId.random()), fixture.actor) {
                    consumerCalled = true
                    writeDurableMarker(fixture.marker, it.operationId.value)
                }
            }.exceptionOrNull()

            val rpcFailure = assertIs<RpcFailure>(failure)
            assertEquals(RpcFailureKind.UnknownProcedure, rpcFailure.kind)
            assertFalse(consumerCalled)
            assertFalse(Files.exists(fixture.marker))
            assertEquals(0, fixture.executeCount.get())
            assertEquals(0L, fixture.host.requests.value.totals.accepted)
            assertEquals(0L, fixture.host.diagnostics.value.completedCalls)
            assertEquals(1L, fixture.host.requests.value.totals.refusedAttempts)
            val refused = fixture.host.requests.value.entries.single()
            assertEquals("pos.business.execute", refused.procedure)
            assertEquals(1, refused.version)
            assertEquals(RpcHostCallState.Refused, refused.state)
            assertFalse(refused.admitted)
        }
    }
}
