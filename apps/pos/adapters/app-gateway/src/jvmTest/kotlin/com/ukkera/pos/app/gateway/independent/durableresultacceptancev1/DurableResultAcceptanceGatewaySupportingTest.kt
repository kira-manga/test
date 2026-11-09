package com.ukkera.pos.app.gateway.independent.durableresultacceptancev1

import com.ukkera.pos.app.LocalAction
import com.ukkera.pos.app.LocalCommandIntent
import com.ukkera.pos.app.LocalOperationResult
import com.ukkera.pos.app.LocalPendingPhase
import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.app.LocalStageResult
import com.ukkera.pos.app.gateway.StoredKernelResultV1
import com.ukkera.pos.app.gateway.independent.foodfinancialrpcv1.FoodFinancialWriteBarrier
import com.ukkera.pos.app.gateway.independent.foodfinancialrpcv1.FrozenFoodFinancialRpc
import com.ukkera.pos.database.DeferredReason
import com.ukkera.pos.database.LookupReply
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.types.OperationId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Frozen independent DR-A observations for durable result acceptance before a transport Receipt.
 * The production gateway, production typed validation and the real JVM encrypted client journal
 * run over controlled Master DATA and a controlled accepting port. See
 * `qa/pos/traceability/durable-result-acceptance-20261109/README.md`.
 */
class DurableResultAcceptanceGatewaySupportingTest {
    private val input get() = FrozenFoodFinancialRpc

    @Test
    fun dra01RecordedResultIsDurablyRememberedBeforeMatchingReceipt() = runBlocking {
        withDurableAcceptanceFixture { fixture ->
            val (intent, reply) = fixture.stageBeginCollection()
            fixture.base.executeReply = { reply }

            val result = assertIs<LocalOperationResult.Recorded>(fixture.gateway().submit(intent.operationId))

            assertEquals(intent.operationId, result.operationId)
            assertEquals(LocalAction.FOOD_BEGIN_COLLECTION, result.originalAction)
            assertEquals(1, fixture.accepting.acceptanceCalls, "Acceptance must run exactly once")
            assertEquals(listOf(intent.operationId), fixture.accepting.offeredOperations)
            assertEquals(1, fixture.accepting.receiptPermitted)
            fixture.assertDurableBeforeReceipt()
            val entry = fixture.find(intent.operationId)
            assertEquals(StoredKernelResultV1.from(reply), entry.recorded)
            assertFalse(entry.acknowledged, "Durable result retention is separate from application ACK")
            assertEquals(1, fixture.base.mutations.size)
            fixture.reopen()
            assertEquals(entry, fixture.find(intent.operationId), "Durable result must survive journal reopen")
        }
    }

    @Test
    fun dra02JournalFailureWithholdsReceiptAndOriginalLookupRecoversWithoutReexecution() = runBlocking {
        withDurableAcceptanceFixture { fixture ->
            val (intent, reply) = fixture.stageBeginCollection()
            fixture.base.executeReply = { reply }
            fixture.journal.failRemember = true

            val submitted = assertIs<LocalOperationResult.Unresolved>(fixture.gateway().submit(intent.operationId))

            assertEquals(LocalServiceProblem.STORAGE_UNCERTAIN, submitted.problem)
            fixture.assertNoReceipt()
            assertEquals(1, fixture.accepting.receiptWithheld)
            assertFalse(fixture.events.contains("result.durable"), "A non-DURABLE journal write is not acceptance")
            val outstanding = fixture.find(intent.operationId)
            assertNull(outstanding.recorded)
            assertFalse(outstanding.acknowledged)
            assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED, outstanding.localPhase)
            assertEquals(1, fixture.base.mutations.size)

            fixture.reopen()
            fixture.base.lookupReply = { LookupReply.Found(reply) }
            val recovered = assertIs<LocalOperationResult.Recorded>(fixture.gateway().lookup(intent.operationId))

            assertEquals(intent.operationId, recovered.operationId)
            fixture.assertDurableBeforeReceipt()
            assertEquals(StoredKernelResultV1.from(reply), fixture.find(intent.operationId).recorded)
            assertEquals(1, fixture.base.mutations.size, "Lookup recovery must not execute the original again")
        }
    }

    @Test
    fun dra03InvalidTypedOrMismatchedResultWithholdsReceiptBeforeJournalWrite() = runBlocking {
        for (variant in listOf("malformed", "operation", "actor")) {
            withDurableAcceptanceFixture { fixture ->
                val (intent, valid) = fixture.stageBeginCollection()
                val invalid = when (variant) {
                    "malformed" -> recorded(valid, result = input.bytes("{}"))
                    "operation" -> recorded(valid, operationId = OperationId.random())
                    else -> recorded(valid, originalActorId = input.otherActor)
                }
                fixture.base.executeReply = { invalid }

                val result = assertIs<LocalOperationResult.Unresolved>(fixture.gateway().submit(intent.operationId))

                assertEquals(LocalServiceProblem.STORAGE_UNCERTAIN, result.problem, variant)
                fixture.assertNoReceipt()
                assertEquals(1, fixture.accepting.receiptWithheld, variant)
                assertFalse(fixture.events.contains("result.durable"), variant)
                val entry = fixture.find(intent.operationId)
                assertNull(entry.recorded, variant)
                assertFalse(entry.acknowledged, variant)
                assertEquals(1, fixture.base.mutations.size, variant)
            }
        }
    }

    @Test
    fun dra04CancellationDuringAcceptanceWithholdsReceiptAndKeepsOriginalPending() = runBlocking {
        withDurableAcceptanceFixture { fixture ->
            val (intent, reply) = fixture.stageBeginCollection()
            fixture.base.executeReply = { reply }
            val barrier = FoodFinancialWriteBarrier()
            fixture.journal.rememberBarrier = barrier

            val submission = async { fixture.gateway().submit(intent.operationId) }
            try {
                withTimeout(10_000) { barrier.entered.await() }
            } finally {
                try { withTimeout(10_000) { submission.cancelAndJoin() } } finally { barrier.release() }
            }

            assertTrue(fixture.events.contains("result.enter"))
            assertFalse(fixture.events.contains("result.durable"))
            fixture.assertNoReceipt()
            assertEquals(1, fixture.accepting.receiptWithheld)
            val outstanding = fixture.find(intent.operationId)
            assertNull(outstanding.recorded)
            assertFalse(outstanding.acknowledged)
            assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED, outstanding.localPhase)
            assertEquals(1, fixture.journal.dispatches)
            assertEquals(1, fixture.base.mutations.size)

            fixture.reopen()
            fixture.base.lookupReply = { LookupReply.AbsentAtCurrentWriter(it.operationId) }
            val pending = assertIs<LocalOperationResult.Pending>(fixture.gateway().lookup(intent.operationId))
            assertEquals(intent.operationId, pending.operation.operationId)
            assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED, pending.operation.phase)
            assertEquals(0, fixture.journal.dispatches, "Recovery lookup must not dispatch a replacement")
            assertEquals(1, fixture.base.mutations.size)
        }
    }

    @Test
    fun dra05CurrentLifetimeLossBeforeAcceptanceWithholdsReceiptAndPreservesOriginal() = runBlocking {
        withDurableAcceptanceFixture { fixture ->
            val (intent, reply) = fixture.stageBeginCollection()
            fixture.base.executeReply = { reply }
            val beforeAcceptance = CompletableDeferred<Unit>()
            val releaseAcceptance = CompletableDeferred<Unit>()
            fixture.accepting.beforeAcceptance = {
                beforeAcceptance.complete(Unit)
                releaseAcceptance.await()
            }

            val submission = async { fixture.gateway().submit(intent.operationId) }
            withTimeout(10_000) { beforeAcceptance.await() }
            fixture.current = false
            releaseAcceptance.complete(Unit)
            val result = assertIs<LocalOperationResult.Unresolved>(withTimeout(10_000) { submission.await() })

            assertEquals(LocalServiceProblem.SESSION_ENDED, result.problem)
            fixture.assertNoReceipt()
            assertFalse(fixture.events.contains("result.enter"), "No journal acceptance may start under lost current authority")
            val outstanding = fixture.find(intent.operationId)
            assertNull(outstanding.recorded)
            assertFalse(outstanding.acknowledged)
            assertEquals(1, fixture.base.mutations.size)

            fixture.current = true
            fixture.base.lookupReply = { LookupReply.Found(reply) }
            val recovered = assertIs<LocalOperationResult.Recorded>(fixture.gateway().lookup(intent.operationId))
            assertEquals(intent.operationId, recovered.operationId)
            assertEquals(StoredKernelResultV1.from(reply), fixture.find(intent.operationId).recorded)
            assertEquals(1, fixture.base.mutations.size)
        }
    }

    @Test
    fun dra06FoundLookupIsDurablyRememberedBeforeReceiptWithoutMutation() = runBlocking {
        withDurableAcceptanceFixture { fixture ->
            val (intent, reply) = fixture.stageBeginCollection()
            fixture.base.lookupReply = { LookupReply.Found(reply) }

            val result = assertIs<LocalOperationResult.Recorded>(fixture.gateway().lookup(intent.operationId))

            assertEquals(intent.operationId, result.operationId)
            assertEquals(1, fixture.accepting.acceptanceCalls)
            assertEquals(1, fixture.accepting.receiptPermitted)
            fixture.assertDurableBeforeReceipt()
            val entry = fixture.find(intent.operationId)
            assertEquals(StoredKernelResultV1.from(reply), entry.recorded)
            assertFalse(entry.acknowledged)
            assertTrue(fixture.base.mutations.isEmpty(), "Lookup must not execute any command")
            assertEquals(0, fixture.journal.dispatches)
            fixture.reopen()
            assertEquals(entry, fixture.find(intent.operationId))
        }
    }

    @Test
    fun dra07UnknownOrInvalidLookupNeverManufacturesAcceptanceOrReplacement() = runBlocking {
        for (variant in listOf("absent", "deferred", "conflict", "wrong-operation", "malformed")) {
            withDurableAcceptanceFixture { fixture ->
                val (intent, reply) = fixture.stageBeginCollection()
                fixture.base.lookupReply = { identity ->
                    when (variant) {
                        "absent" -> LookupReply.AbsentAtCurrentWriter(identity.operationId)
                        "deferred" -> LookupReply.Deferred(identity.operationId, DeferredReason.CURRENT_SOURCE_UNAVAILABLE)
                        "conflict" -> LookupReply.IdentityConflict(identity.operationId)
                        "wrong-operation" -> LookupReply.Found(recorded(reply, operationId = OperationId.random()))
                        else -> LookupReply.Found(recorded(reply, result = input.bytes("{}")))
                    }
                }

                repeat(2) {
                    val observed = fixture.gateway().lookup(intent.operationId)
                    if (variant == "absent") {
                        val pending = assertIs<LocalOperationResult.Pending>(observed, variant)
                        assertEquals(intent.operationId, pending.operation.operationId, variant)
                        assertEquals(LocalPendingPhase.STAGED, pending.operation.phase, variant)
                    } else assertEquals(intent.operationId, assertIs<LocalOperationResult.Unresolved>(observed, variant).operationId, variant)
                    val entry = fixture.find(intent.operationId)
                    assertNull(entry.recorded, variant)
                    assertFalse(entry.acknowledged, variant)
                }

                if (variant == "wrong-operation" || variant == "malformed") {
                    assertEquals(2, fixture.accepting.acceptanceCalls, variant)
                    assertEquals(2, fixture.accepting.receiptWithheld, variant)
                    assertFalse(fixture.events.contains("result.durable"), variant)
                } else {
                    assertEquals(0, fixture.accepting.acceptanceCalls, variant)
                    assertEquals(0, fixture.accepting.receiptPermitted, variant)
                }
                assertEquals(2, fixture.base.lookups.size, variant)
                assertTrue(fixture.base.mutations.isEmpty(), variant)
                assertEquals(0, fixture.journal.dispatches, variant)
                assertEquals(1, fixture.journal.stages, variant)
            }
        }
    }

    @Test
    fun dra08PlainLocalTransportRetainsAfterOrdinaryCallWithoutInventingAcceptance() = runBlocking {
        withDurableAcceptanceFixture { fixture ->
            val intent = input.intent(LocalAction.FOOD_BEGIN_COLLECTION)
            val gateway = fixture.gateway(fixture.base)
            assertIs<LocalStageResult.Staged>(gateway.stage(intent))
            val reply = input.recorded(intent)
            fixture.base.executeReply = { reply }

            val result = assertIs<LocalOperationResult.Recorded>(gateway.submit(intent.operationId))

            assertEquals(intent.operationId, result.operationId)
            assertEquals(0, fixture.accepting.acceptanceCalls, "A plain local endpoint has no acceptance callback")
            assertFalse(fixture.events.contains("accept.enter"))
            assertFalse(fixture.events.contains("receipt.permit"))
            assertEquals(StoredKernelResultV1.from(reply), fixture.find(intent.operationId).recorded)
            assertEquals(1, fixture.base.mutations.size)
        }
    }

    private suspend fun DurableAcceptanceFixture.stageBeginCollection(): Pair<LocalCommandIntent, MutationReply.Recorded> {
        val intent = input.intent(LocalAction.FOOD_BEGIN_COLLECTION)
        assertIs<LocalStageResult.Staged>(gateway().stage(intent))
        return intent to input.recorded(intent)
    }

    /** Controlled reply carrier: same frozen shape with one field intentionally replaced. */
    private fun recorded(
        source: MutationReply.Recorded,
        operationId: OperationId = source.operationId,
        originalActorId: com.ukkera.pos.types.DomainId = source.originalActorId,
        result: com.ukkera.pos.database.StoredBytes = source.result,
    ) = MutationReply.Recorded(operationId, originalActorId, source.committed, source.code, result,
        source.authorityEpoch, source.sequence, source.recordedAt, source.replayed)
}
