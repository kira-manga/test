package com.ukkera.pos.app.gateway.independent.foodfinancialrpcv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.database.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

/** Production CLIENT journal persistence; all Master authority, source and reply inputs are controlled. */
class FoodFinancialRpcEncryptedJournalSupportingTest {
    private val input get() = FrozenFoodFinancialRpc

    @Test
    fun ffrc024FailedTerminalWriteThenReopenLookupDurablyRemembersBeforeAcknowledgement() = runBlocking {
        input.case("FFRC-024")
        for (action in input.actions) withFoodFinancialGateway { fixture ->
            val intent = input.intent(action)
            val reply = input.recorded(intent)
            val gateway = fixture.gateway()
            assertIs<LocalStageResult.Staged>(gateway.stage(intent))
            val original = fixture.find(intent.operationId)
            fixture.master.executeReply = { reply }
            fixture.journal.failRemember = true
            assertIs<LocalOperationResult.Unresolved>(gateway.submit(intent.operationId))
            val outstanding = fixture.find(intent.operationId)
            input.assertOriginal(outstanding, intent)
            assertTrue(original.sameIntent(outstanding))
            assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED, outstanding.localPhase)
            assertNull(outstanding.recorded)
            assertFalse(outstanding.acknowledged)
            assertIs<LocalAcknowledgementResult.Unresolved>(gateway.acknowledgeRecorded(intent.operationId))
            assertEquals(0, fixture.journal.acknowledgements)
            assertEquals(1, fixture.master.mutations.size)

            fixture.reopen()
            val recoveredGateway = fixture.gateway()
            val recoveredOriginal = fixture.find(intent.operationId)
            assertEquals(outstanding, recoveredOriginal)
            fixture.master.reads.clear()
            fixture.master.readReply = { error("Recovery must not refresh financial preparation") }
            fixture.master.lookupReply = { identity ->
                assertEquals(recoveredOriginal.identity(), identity)
                LookupReply.Found(reply)
            }
            val barrier = FoodFinancialWriteBarrier()
            fixture.journal.rememberBarrier = barrier
            val recovering = async { recoveredGateway.lookup(intent.operationId) }
            val acknowledgement = try {
                withTimeout(10_000) { barrier.entered.await() }
                assertNull(fixture.find(intent.operationId).recorded)
                assertFalse(recovering.isCompleted)
                val lookupCount = fixture.master.lookups.size
                val ack = async(start = CoroutineStart.UNDISPATCHED) {
                    recoveredGateway.acknowledgeRecorded(intent.operationId)
                }
                assertFalse(ack.isCompleted)
                assertEquals(lookupCount, fixture.master.lookups.size,
                    "ACK is held behind the exact journal request mutex, not merely delayed")
                assertEquals(0, fixture.journal.acknowledgements)
                ack
            } finally { barrier.release() }
            val result = assertIs<LocalOperationResult.Recorded>(recovering.await())
            assertEquals(intent.operationId, result.operationId)
            assertEquals(action, result.originalAction)
            assertEquals(input.actor, result.foodCashFinancial!!.originalActorId)
            assertEquals(intent.operationId,
                assertIs<LocalAcknowledgementResult.Acknowledged>(acknowledgement.await()).operationId)
            assertEquals(1, fixture.journal.acknowledgements)
            val resultDurable = fixture.events.lastIndexOf("result.durable")
            val ackEntered = fixture.events.lastIndexOf("ack.enter")
            val ackDurable = fixture.events.lastIndexOf("ack.durable")
            assertTrue(resultDurable >= 0 && resultDurable < ackEntered && ackEntered < ackDurable)
            val archived = fixture.find(intent.operationId)
            input.assertOriginal(archived, intent)
            assertTrue(archived.acknowledged)
            assertEquals(StoredKernelResultV1.from(reply), archived.recorded)
            assertTrue(fixture.master.reads.isEmpty())
            assertEquals(0, fixture.journal.stages)
            assertEquals(0, fixture.journal.dispatches)
            assertEquals(1, fixture.master.mutations.size)

            fixture.reopen()
            assertEquals(archived, fixture.find(intent.operationId), "Actual archived bytes survive another reopen")
            assertTrue(assertIs<JournalRead.Value<List<StoredClientIntentV1>>>(fixture.journal.pending()).value.isEmpty())
        }
        // This orders application journal acceptance/ACK only, not SDK transport Receipt or native source continuity.
    }

    @Test
    fun ffrc025LostFirstReplyThenRepeatedExactLookupNeverRestagesOrExecutes() = runBlocking {
        input.case("FFRC-025")
        for (action in input.actions) for (variant in listOf("found", "writer-absence", "source-deferred", "lost", "conflict")) {
            withFoodFinancialGateway { fixture ->
                val intent = input.intent(action)
                val reply = input.recorded(intent)
                val gateway = fixture.gateway()
                assertIs<LocalStageResult.Staged>(gateway.stage(intent))
                fixture.master.executeReply = { error("Controlled first reply loss") }
                assertIs<LocalOperationResult.Unresolved>(gateway.submit(intent.operationId))
                val original = fixture.find(intent.operationId)
                input.assertOriginal(original, intent)
                assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED, original.localPhase)
                assertNull(original.recorded)
                assertEquals(1, fixture.master.mutations.size)

                fixture.reopen()
                fixture.master.reads.clear()
                fixture.master.lookups.clear()
                fixture.master.readReply = { error("Lookup must not reread a quote, original or current revision") }
                fixture.master.lookupReply = { identity ->
                    assertEquals(original.identity(), identity)
                    when (variant) {
                        "found" -> LookupReply.Found(reply)
                        "writer-absence" -> LookupReply.AbsentAtCurrentWriter(intent.operationId)
                        "source-deferred" -> LookupReply.Deferred(intent.operationId, DeferredReason.CURRENT_SOURCE_UNAVAILABLE)
                        "lost" -> error("Controlled Lookup transport loss")
                        else -> LookupReply.IdentityConflict(intent.operationId)
                    }
                }
                repeat(2) {
                    val observed = fixture.gateway().lookup(intent.operationId)
                    when (variant) {
                        "found" -> assertEquals(intent.operationId, assertIs<LocalOperationResult.Recorded>(observed).operationId)
                        "writer-absence" -> {
                            val pending = assertIs<LocalOperationResult.Pending>(observed).operation
                            assertEquals(intent.operationId, pending.operationId)
                            assertEquals(action, pending.action)
                            assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED, pending.phase)
                            assertNull(pending.recordedDecision)
                        }
                        else -> assertEquals(intent.operationId, assertIs<LocalOperationResult.Unresolved>(observed).operationId)
                    }
                    val retained = fixture.find(intent.operationId)
                    input.assertOriginal(retained, intent)
                    assertTrue(original.sameIntent(retained), variant)
                    assertEquals(original.identity(), retained.identity(), variant)
                    assertFalse(retained.acknowledged)
                }
                assertEquals(listOf(original.identity(), original.identity()), fixture.master.lookups, variant)
                assertTrue(fixture.master.reads.isEmpty(), variant)
                assertEquals(0, fixture.journal.stages, variant)
                assertEquals(0, fixture.journal.dispatches, variant)
                assertEquals(0, fixture.journal.acknowledgements, variant)
                assertEquals(1, fixture.master.mutations.size, variant)
            }
        }
        // Writer-qualified absence is supplied by the controlled port; it is not proof of no physical collection.
    }

    @Test
    fun ffrc024WrongIdentityMalformedOutcomeAndRevisionInputsNeverBecomeRememberedOrAcknowledged() = runBlocking {
        input.case("FFRC-024")
        for (action in input.actions) {
            val intent = input.intent(action)
            for ((variant, invalid) in invalidReplies(intent)) withFoodFinancialGateway { fixture ->
                val gateway = fixture.gateway()
                assertIs<LocalStageResult.Staged>(gateway.stage(intent))
                val original = fixture.find(intent.operationId)
                fixture.master.lookupReply = { LookupReply.Found(invalid) }
                assertIs<LocalOperationResult.Unresolved>(gateway.lookup(intent.operationId), variant)
                assertIs<LocalAcknowledgementResult.Unresolved>(gateway.acknowledgeRecorded(intent.operationId), variant)
                assertEquals(original, fixture.find(intent.operationId), variant)
                assertEquals(0, fixture.journal.remembrances, variant)
                assertEquals(0, fixture.journal.acknowledgements, variant)
                assertTrue(fixture.master.mutations.isEmpty(), variant)
            }
        }
    }

    @Test
    fun ffrc013And026ReopenedCachedTerminalCannotSubstituteForCurrentSourceRecovery() = runBlocking {
        input.case("FFRC-013")
        input.case("FFRC-026")
        for (action in input.actions) withFoodFinancialGateway { fixture ->
            val intent = input.intent(action)
            val reply = input.recorded(intent)
            val gateway = fixture.gateway()
            assertIs<LocalStageResult.Staged>(gateway.stage(intent))
            fixture.master.lookupReply = { LookupReply.Found(reply) }
            assertIs<LocalOperationResult.Recorded>(gateway.lookup(intent.operationId))
            val remembered = fixture.find(intent.operationId)
            assertEquals(StoredKernelResultV1.from(reply), remembered.recorded)
            assertFalse(remembered.acknowledged)

            fixture.reopen()
            fixture.master.lookups.clear()
            fixture.master.reads.clear()
            fixture.master.readReply = { error("No source replacement through fresh preparation") }
            fixture.master.lookupReply = { LookupReply.Deferred(it.operationId, DeferredReason.CURRENT_SOURCE_UNAVAILABLE) }
            val recoveredGateway = fixture.gateway()
            assertIs<LocalOperationResult.Unresolved>(recoveredGateway.lookup(intent.operationId))
            assertIs<LocalPendingResult.Unavailable>(recoveredGateway.pending())
            assertIs<LocalAcknowledgementResult.Unresolved>(recoveredGateway.acknowledgeRecorded(intent.operationId))
            assertEquals(remembered, fixture.find(intent.operationId))
            assertEquals(0, fixture.journal.remembrances)
            assertEquals(0, fixture.journal.acknowledgements)
            assertEquals(0, fixture.journal.stages)
            assertTrue(fixture.master.reads.isEmpty())
            assertTrue(fixture.master.mutations.isEmpty())
            assertTrue(fixture.master.lookups.all { it == remembered.identity() })
        }
        // Only the Client is reopened here. Actual Master restart/storage/native source variants are separate checks.
    }

    @Test
    fun ffrc028ClientLeaseLossBeforeDispatchAndAfterReplyPreservesTheOriginal() = runBlocking {
        input.case("FFRC-028")
        val intent = input.intent(LocalAction.FOOD_FINALIZE_PAID)
        withFoodFinancialGateway { fixture ->
            val gateway = fixture.gateway()
            assertIs<LocalStageResult.Staged>(gateway.stage(intent))
            val original = fixture.find(intent.operationId)
            fixture.current = false
            assertIs<LocalOperationResult.Unresolved>(gateway.submit(intent.operationId))
            assertEquals(original, fixture.find(intent.operationId))
            assertTrue(fixture.master.lookups.isEmpty())
            assertTrue(fixture.master.mutations.isEmpty())
            assertEquals(0, fixture.journal.dispatches)
            assertEquals(0, fixture.journal.acknowledgements)
        }
        withFoodFinancialGateway { fixture ->
            val reply = input.recorded(intent)
            val gateway = fixture.gateway()
            assertIs<LocalStageResult.Staged>(gateway.stage(intent))
            fixture.master.executeReply = { fixture.current = false; reply }
            assertIs<LocalOperationResult.Unresolved>(gateway.submit(intent.operationId))
            val retained = fixture.find(intent.operationId)
            input.assertOriginal(retained, intent)
            assertEquals(StoredKernelResultV1.from(reply), retained.recorded)
            assertFalse(retained.acknowledged)
            assertEquals(1, fixture.journal.remembrances)
            assertIs<LocalAcknowledgementResult.Unresolved>(gateway.acknowledgeRecorded(intent.operationId))
            assertEquals(0, fixture.journal.acknowledgements)
            fixture.current = true
            fixture.master.lookupReply = { LookupReply.Found(reply) }
            fixture.master.readReply = { error("Current recovery must use only exact Lookup") }
            assertIs<LocalOperationResult.Recorded>(fixture.gateway().lookup(intent.operationId))
            assertIs<LocalAcknowledgementResult.Acknowledged>(fixture.gateway().acknowledgeRecorded(intent.operationId))
            assertEquals(1, fixture.master.mutations.size)
            assertEquals(1, fixture.journal.stages)
        }
        // Lease retirement is a Client schedule control, not an actual network partition or L1 native retirement.
    }

    @Test
    fun ffrc027SupportingClientIdentityAndRetiredLeaseCannotBorrowOriginalRecovery() = runBlocking {
        input.case("FFRC-027")
        withFoodFinancialGateway { fixture ->
            val intent = input.intent(LocalAction.FOOD_PAYMENT_APPLY)
            val gateway = fixture.gateway()
            assertIs<LocalStageResult.Staged>(gateway.stage(intent))
            val original = fixture.find(intent.operationId)
            fixture.master.view = input.view(input.otherActor)
            assertIs<LocalOperationResult.Unresolved>(gateway.lookup(intent.operationId))
            assertIs<LocalOperationResult.Unresolved>(gateway.submit(intent.operationId))
            assertIs<LocalPendingResult.Unavailable>(gateway.pending())
            assertIs<LocalAcknowledgementResult.Unresolved>(gateway.acknowledgeRecorded(intent.operationId))
            fixture.master.view = input.view()
            fixture.current = false
            assertIs<LocalOperationResult.Unresolved>(gateway.lookup(intent.operationId))
            assertIs<LocalOperationResult.Unresolved>(gateway.submit(intent.operationId))
            assertEquals(original, fixture.find(intent.operationId))
            assertTrue(fixture.master.lookups.isEmpty())
            assertTrue(fixture.master.mutations.isEmpty())
            assertEquals(0, fixture.journal.acknowledgements)
        }
        // Does NOT exercise genuine same-key L2, native borrows, physical closure or failed-close retry ownership.
    }

    private fun invalidReplies(intent: LocalCommandIntent): List<Pair<String, MutationReply.Recorded>> {
        val valid = input.recorded(intent)
        val variants = mutableListOf(
            "operation" to changed(valid, operationId = input.operation("wrong-result-operation")),
            "actor" to changed(valid, actorId = input.otherActor),
            "malformed" to changed(valid, payload = input.bytes("{}")),
            "sequence" to changed(valid, sequence = JournalSequence(0)),
            "committed-code" to changed(valid, code = "food.payment.not_available"))
        if (intent.action == LocalAction.FOOD_PAYMENT_APPLY) {
            val body = assertIs<LocalFoodPaymentResult.Applied>(LocalFoodPaymentCodec.result(valid.result))
            fun variant(name: String, value: LocalFoodPaymentResult) {
                variants += name to changed(valid, payload = LocalFoodPaymentCodec.result(value))
            }
            variant("recording", body.copy(target = body.target.copy(recordingOperationId = input.operation("wrong-recording"))))
            variant("application", body.copy(applicationOperationId = input.operation("wrong-application")))
            variant("resolution-phase", body.copy(phase = LocalFoodPaymentFactPhase.RESOLUTION))
            variant("application-revision", body.copy(revisions = body.revisions.map {
                if (it.kind == LocalFoodPaymentProfile.APPLICATION) it.copy(storageRevision = Revision(3)) else it
            }))
            variant("duplicate-revision", body.copy(revisions = body.revisions + body.revisions.first()))
        } else {
            val body = LocalFoodFinancialCodecV1.result(valid.result)
            fun variant(name: String, value: LocalFoodFinancialResultV1) {
                variants += name to changed(valid, payload = input.bytes(input.json.encodeToString(
                    LocalFoodFinancialResultV1.serializer(), value)))
            }
            variant("document", body.copy(documentId = input.id("wrong-result-document")))
            variant("missing-revisions", body.copy(revisions = emptyList()))
            variant("duplicate-revision", body.copy(revisions = body.revisions + body.revisions.first()))
            val exactKind = if (intent.action == LocalAction.FOOD_BEGIN_COLLECTION) LocalFoodProfileV1.ORDER
                else LocalFoodFinancialProfileV1.SALE
            variant("exact-storage-revision", body.copy(revisions = body.revisions.map {
                if (it.kind == exactKind) it.copy(storageRevision = it.storageRevision.next()) else it
            }))
        }
        return variants
    }

    private fun changed(value: MutationReply.Recorded, operationId: OperationId = value.operationId,
        actorId: DomainId = value.originalActorId, payload: StoredBytes = value.result,
        sequence: JournalSequence = value.sequence, code: String = value.code) = MutationReply.Recorded(
        operationId, actorId, value.committed, code, payload, value.authorityEpoch, sequence, value.recordedAt,
        value.replayed, value.physicalStart)
}
