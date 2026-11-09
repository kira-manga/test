@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.gateway.independent.foodcheckrpcprofilev1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.database.*
import com.ukkera.pos.sales.FoodPreparationStatus
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/**
 * Frozen Draft/old-form/unbound-input continuity and negative-result SUPPORT
 * slices through the real gateway with controlled ports. The Draft finite
 * refusal carrier is BUSINESS SHAPE ONLY from the separate native-profile
 * freeze: it has no actual producer, original/source qualification or native
 * admission. Its remembrance/ACK cuts prove controlled client ordering only.
 * No positive actual recorded revision is supplied or inferred. Whole GENUINE
 * FCRP-036/038/040–046 and positive FCRP-011/012 remain UNBOUND and NOT_RUN.
 * No SDK terminal Receipt, protected storage or fresh-process restart is tested.
 */
class FoodCheckRpcProfileOriginalsSupportingTest {
    private val frozen get() = FrozenFoodCheckRpcProfileInputs

    @Test
    fun fcrp010DraftSaveCompletesBeforeSeparateSubmitUsesOnlyThatSavedRequest() = runTest(timeout = 10.seconds) {
        withFoodCheckSupportFixture { f ->
            val save = FoodCheckSupportGate().also { f.journal.stageGate = it }
            val staged = async { f.gateway.stage(frozen.draftIntent()) }
            try {
                runCurrent()
                assertTrue(save.entered.isCompleted)
                assertFalse(staged.isCompleted)
                assertTrue(f.journal.entries.isEmpty())
                assertEquals(0, f.journal.dispatchCalls)
                assertTrue(f.master.lookups.isEmpty())
                assertTrue(f.master.mutations.isEmpty())
            } finally { save.release() }
            assertIs<LocalStageResult.Staged>(staged.await())
            val original = f.journal.entries.getValue(frozen.draftOperation)
            frozen.assertDraftOriginal(original)
            assertNull(original.recorded)
            assertFalse(original.acknowledged)

            // This is a supplied absence response, not proof that an actual writer has no record.
            f.master.lookupReply = { LookupReply.AbsentAtCurrentWriter(it.operationId) }
            f.master.readReply = { error("Saved submission must not rebuild from current sources") }
            val dispatch = FoodCheckSupportGate().also { f.journal.dispatchGate = it }
            val submitted = async { f.gateway.submit(frozen.draftOperation) }
            try {
                runCurrent()
                assertTrue(dispatch.entered.isCompleted)
                assertFalse(submitted.isCompleted)
                assertTrue(f.master.mutations.isEmpty())
                assertEquals(original, f.journal.entries.getValue(frozen.draftOperation))
                assertEquals(listOf(original.identity()), f.master.lookups)
            } finally { dispatch.release() }
            assertIs<LocalOperationResult.Unresolved>(submitted.await())
            val retained = f.journal.entries.getValue(frozen.draftOperation)
            frozen.assertDraftOriginal(retained)
            assertTrue(original.sameIntent(retained))
            assertEquals(original.semanticSha256, retained.semanticSha256)
            assertEquals(original.inputDigest, retained.inputDigest)
            assertEquals(original.identity(), retained.identity())
            assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED, retained.localPhase)
            assertNull(retained.recorded)
            val request = f.master.mutations.single()
            assertEquals(original.operationId, request.operationId)
            assertEquals(original.request().command, request.command)
            assertEquals(original.request().payload, request.payload)
            assertNull(request.actionApprovalId)
            assertTrue(f.events.indexOf("intent.retained") < f.events.indexOf("dispatch.retained"))
            assertTrue(f.events.indexOf("dispatch.retained") < f.events.indexOf("execute.enter"))
            assertEquals(0, f.journal.rememberCalls)
            assertEquals(0, f.journal.ackCalls)
            assertTrue(f.master.reads.isEmpty())
            // The supplied Execute stays deferred; no actual business result or effects are claimed.
        }
    }

    @Test
    fun fcrp010FailedOrUnknownSaveAndDispatchWritesNeverDispatchAReplacement() = runTest {
        for (write in listOf(FoodCheckSupportWrite.FAILED, FoodCheckSupportWrite.RETAINED_ANSWER_UNKNOWN)) {
            withFoodCheckSupportFixture { f ->
                f.journal.stageWrite = write
                assertIs<LocalStageResult.Uncertain>(f.gateway.stage(frozen.draftIntent()))
                assertTrue(f.master.mutations.isEmpty())
                assertTrue(f.master.lookups.isEmpty())
                assertEquals(0, f.journal.dispatchCalls)
                f.journal.entries[frozen.draftOperation]?.let { frozen.assertDraftOriginal(it) }
            }
            withFoodCheckSupportFixture { f ->
                assertIs<LocalStageResult.Staged>(f.gateway.stage(frozen.draftIntent()))
                val original = f.journal.entries.getValue(frozen.draftOperation)
                f.master.lookupReply = { LookupReply.AbsentAtCurrentWriter(it.operationId) }
                f.journal.dispatchWrite = write
                assertIs<LocalOperationResult.Unresolved>(f.gateway.submit(frozen.draftOperation))
                assertTrue(f.master.mutations.isEmpty())
                val retained = f.journal.entries.getValue(frozen.draftOperation)
                assertTrue(original.sameIntent(retained))
                frozen.assertDraftOriginal(retained)
                assertEquals(0, f.journal.rememberCalls)
                assertEquals(0, f.journal.ackCalls)
            }
        }
    }

    @Test
    fun fcrp013LegacyDraftWrongFormActionAndKeyOriginalsRemainInertAndPreserved() = runTest {
        val base = frozen.stagedDraft()
        val variants = listOf(frozen.stagedDraft(legacy = true), base.copy(formVersion = 1), base.copy(formVersion = 3),
            base.copy(action = LocalAction.FOOD_ADD.name, formVersion = 3),
            base.copy(commandVersion = 1), base.copy(commandName = LocalFoodProfileV1.COMMAND.name))
        for (original in variants) withFoodCheckSupportFixture { f ->
            f.journal.entries[original.operationId] = original
            assertIs<LocalOperationResult.Unresolved>(f.gateway.lookup(original.operationId))
            assertIs<LocalOperationResult.Unresolved>(f.gateway.submit(original.operationId))
            assertIs<LocalAcknowledgementResult.Unresolved>(f.gateway.acknowledgeRecorded(original.operationId))
            assertIs<LocalStageResult.Uncertain>(f.gateway.stage(frozen.draftIntent()))
            assertIs<LocalPendingResult.Unavailable>(f.gateway.pending(null))
            assertEquals(original, f.journal.entries.getValue(original.operationId))
            assertTrue(f.master.lookups.isEmpty())
            assertTrue(f.master.mutations.isEmpty())
            assertTrue(f.master.reads.isEmpty())
            assertEquals(0, f.journal.stageCalls)
            assertEquals(0, f.journal.dispatchCalls)
            assertEquals(0, f.journal.rememberCalls)
            assertEquals(0, f.journal.ackCalls)
        }
        // Canonical genuine retained Add1/Add2 originals are not supplied by these Draft variants.
    }

    @Test
    fun fcrp008UnboundAddTemplateNeverBecomesAnExecutableOrRewrittenOriginal() = runTest {
        val original = frozen.unresolvedAddTemplateEntry()
        val raw = original.request().payload.copyBytes().decodeToString()
        assertEquals(frozen.addTemplate.toString(), raw)
        assertTrue(raw.contains("\${order_revision_at_review}"))
        assertTrue(raw.contains("\${catalog_revision_at_review}"))
        assertTrue(raw.contains("\${recipe_revision_at_review}"))
        assertFails { LocalFoodStockCodecV2.normalize(original.request().payload) }
        withFoodCheckSupportFixture { f ->
            f.journal.entries[original.operationId] = original
            assertIs<LocalOperationResult.Unresolved>(f.gateway.lookup(original.operationId))
            assertIs<LocalOperationResult.Unresolved>(f.gateway.submit(original.operationId))
            assertIs<LocalAcknowledgementResult.Unresolved>(f.gateway.acknowledgeRecorded(original.operationId))
            assertIs<LocalStageResult.Uncertain>(f.gateway.stage(frozen.unboundAddIntent()))
            assertIs<LocalPendingResult.Unavailable>(f.gateway.pending(null))
            assertEquals(original, f.journal.entries.getValue(original.operationId))
            assertTrue(f.master.reads.isEmpty())
            assertTrue(f.master.lookups.isEmpty())
            assertTrue(f.master.mutations.isEmpty())
            assertEquals(0, f.journal.stageCalls)
            assertEquals(0, f.journal.dispatchCalls)
            assertEquals(0, f.journal.rememberCalls)
            assertEquals(0, f.journal.ackCalls)
        }
        // This deliberately invalid retained DATA is not a frozen executable Add3 binding.
    }

    @Test
    fun fcrp013MixedPendingSetIsUnavailableBeforeLookupOrFrontierFiltering() = runTest {
        withFoodCheckSupportFixture { f ->
            val compatible = frozen.stagedDraft()
            val incompatible = frozen.stagedDraft(legacy = true).copy(operationId = frozen.laterOperation)
            f.journal.entries[compatible.operationId] = compatible
            f.journal.entries[incompatible.operationId] = incompatible
            val before = f.journal.entries.toMap()
            assertIs<LocalPendingResult.Unavailable>(f.gateway.pending(null))
            assertIs<LocalPendingResult.Unavailable>(f.gateway.pending("p1:${frozen.laterOperation.value}"))
            assertEquals(before, f.journal.entries)
            assertTrue(f.master.lookups.isEmpty())
            assertTrue(f.master.reads.isEmpty())
            assertTrue(f.master.mutations.isEmpty())
            assertEquals(0, f.journal.stageCalls)
            assertEquals(0, f.journal.rememberCalls)
            assertEquals(0, f.journal.ackCalls)
        }
    }

    @Test
    fun fcrp006fcrp046DraftAdapterReconstructionKeepsOriginalWithoutReadsOrAutomaticDispatch() = runTest {
        withFoodCheckSupportFixture { f ->
            val intent = frozen.draftIntent()
            assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
            val original = f.journal.entries.getValue(frozen.draftOperation)
            f.connection = frozen.id("sessionB")
            f.master.readReply = { error("No rediscovery or replacement source is bound") }
            val reconstructed = f.reconstructGateway()
            assertIs<LocalOperationResult.Unresolved>(reconstructed.lookup(original.operationId))
            assertIs<LocalPendingResult.Unavailable>(reconstructed.pending(null))
            assertIs<LocalStageResult.Uncertain>(reconstructed.stage(intent))
            assertIs<LocalOperationResult.Unresolved>(reconstructed.submit(original.operationId))
            assertEquals(original, f.journal.entries.getValue(original.operationId))
            assertEquals(1, f.journal.stageCalls)
            assertEquals(0, f.journal.dispatchCalls)
            assertTrue(f.master.lookups.all { it == original.identity() })
            assertTrue(f.master.reads.isEmpty())
            assertTrue(f.master.mutations.isEmpty())
            // A new adapter/connection over a controlled map is not a fresh-process durable restart.
        }
    }

    @Test
    fun fcrp038ChangedDraftSemanticsFormActorOrBranchNeverRewritesTheSavedOriginal() = runTest {
        withFoodCheckSupportFixture { f ->
            val intent = frozen.draftIntent()
            assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
            val original = f.journal.entries.getValue(frozen.draftOperation)
            val changed = listOf(
                intent.withValues(intent.values.map { if (it.field == LocalField.ORDER_ID)
                    LocalScalarInput(it.field, LocalScalarValue.Identifier(frozen.id("otherOrder"))) else it }),
                intent.withValues(intent.values.map { if (it.field == LocalField.SERVICE_REFERENCE)
                    LocalScalarInput(it.field, LocalScalarValue.Text(frozen.delivery.string("contact"))) else it }),
                frozen.draftIntent(1), frozen.unboundAddIntent().let {
                    LocalCommandIntent(intent.operationId, it.action, it.formVersion, it.reference, it.values, it.lineField, it.lines)
                },
            )
            for (variant in changed) assertIs<LocalStageResult.Uncertain>(f.gateway.stage(variant))
            assertTrue(f.master.lookups.isEmpty())
            f.master.view = frozen.view(actorId = frozen.id("actorB"))
            assertIs<LocalOperationResult.Unresolved>(f.gateway.lookup(original.operationId))
            f.master.view = frozen.view(branchScope = frozen.scope.copy(branchId = frozen.id("otherBranch")))
            assertIs<LocalOperationResult.Unresolved>(f.gateway.submit(original.operationId))
            assertEquals(original, f.journal.entries.getValue(original.operationId))
            assertEquals(1, f.journal.stageCalls)
            assertEquals(0, f.journal.dispatchCalls)
            assertTrue(f.master.lookups.isEmpty())
            assertTrue(f.master.mutations.isEmpty())
            assertTrue(f.master.reads.isEmpty())
        }
        // These owner/current-view seams do not establish actual Master identity conflict admission.
    }

    @Test
    fun fcrp045DeferredAndSuppliedAbsentLookupStayNonterminalWithoutAckOrReplacement() = runTest {
        for (reason in listOf(DeferredReason.SERVICE_BUSY, DeferredReason.WRITER_NOT_QUALIFIED,
            DeferredReason.CURRENT_SOURCE_UNAVAILABLE, DeferredReason.HISTORY_INCOMPLETE,
            DeferredReason.AUTHORIZATION_DENIED, DeferredReason.STORAGE_UNCERTAIN)) withFoodCheckSupportFixture { f ->
            val original = frozen.stagedDraft()
            f.journal.entries[original.operationId] = original
            f.master.lookupReply = { LookupReply.Deferred(it.operationId, reason) }
            assertIs<LocalOperationResult.Unresolved>(f.gateway.lookup(original.operationId))
            assertIs<LocalOperationResult.Unresolved>(f.gateway.submit(original.operationId))
            assertIs<LocalPendingResult.Unavailable>(f.gateway.pending(null))
            assertIs<LocalAcknowledgementResult.Unresolved>(f.gateway.acknowledgeRecorded(original.operationId))
            assertEquals(original, f.journal.entries.getValue(original.operationId))
            assertTrue(f.master.mutations.isEmpty())
            assertEquals(0, f.journal.stageCalls)
            assertEquals(0, f.journal.dispatchCalls)
            assertEquals(0, f.journal.rememberCalls)
            assertEquals(0, f.journal.ackCalls)
        }
        withFoodCheckSupportFixture { f ->
            val original = frozen.stagedDraft()
            f.journal.entries[original.operationId] = original
            f.master.lookupReply = { LookupReply.AbsentAtCurrentWriter(it.operationId) }
            f.master.view = frozen.view(allowDraft = false)
            val pending = assertIs<LocalOperationResult.Pending>(f.gateway.lookup(original.operationId))
            assertFalse(pending.operation.submitAllowed)
            assertIs<LocalOperationResult.Unresolved>(f.gateway.submit(original.operationId))
            assertIs<LocalAcknowledgementResult.Unresolved>(f.gateway.acknowledgeRecorded(original.operationId))
            assertEquals(original, f.journal.entries.getValue(original.operationId))
            assertTrue(f.master.mutations.isEmpty())
            assertEquals(0, f.journal.dispatchCalls)
            assertEquals(0, f.journal.rememberCalls)
            assertEquals(0, f.journal.ackCalls)
        }
        // Actual in-progress/not-recorded wire mapping, writer races and custody remain UNBOUND.
    }

    @Test
    fun fcrp011fcrp042MissingOrUnprovenDraftRevisionCannotBecomeRememberedSuccess() = runTest {
        val original = frozen.stagedDraft()
        val invalidResults = listOf(
            LocalFoodResultV1("food.order.draft_created", frozen.order, null),
            LocalFoodResultV1("food.order.draft_created", frozen.order, Revision(0)),
            LocalFoodResultV1("food.order.draft_created", frozen.id("otherOrder"), null),
            LocalFoodResultV1("food.order.draft_created", null, null),
            LocalFoodResultV1("food.order.added", frozen.order, null),
            LocalFoodResultV1("food.order.draft_created", frozen.order, null, listOf(
                LocalFoodPortionRevisionV1(frozen.portions.first(), Revision(0), FoodPreparationStatus.NOT_SENT, null))),
        )
        for (body in invalidResults) withFoodCheckSupportFixture { f ->
            f.journal.entries[original.operationId] = original
            f.master.lookupReply = { LookupReply.Found(frozen.carrierData(original, true, "committed", body)) }
            assertIs<LocalOperationResult.Unresolved>(f.gateway.lookup(original.operationId))
            assertIs<LocalPendingResult.Unavailable>(f.gateway.pending(null))
            assertIs<LocalAcknowledgementResult.Unresolved>(f.gateway.acknowledgeRecorded(original.operationId))
            assertEquals(original, f.journal.entries.getValue(original.operationId))
            assertEquals(0, f.journal.rememberCalls)
            assertEquals(0, f.journal.ackCalls)
            assertTrue(f.master.mutations.isEmpty())
        }
        // Revision0 is an invalid/unproven result input, never an assumed ORDER generation.
        // No valid positive actual-revision input or revision+1 oracle is supplied.
    }

    @Test
    fun fcrp011ShapeOnlyFrozenFiniteDraftRefusalHasNoBusinessFollowup() = runTest {
        withFoodCheckSupportFixture { f ->
            val original = frozen.stagedDraft()
            val carrier = frozen.refusalCarrierData(original)
            f.journal.entries[original.operationId] = original
            f.master.lookupReply = { LookupReply.Found(carrier) }
            val result = assertIs<LocalOperationResult.Recorded>(f.gateway.lookup(original.operationId))
            assertEquals(LocalRecordedDecision.REJECTED, result.decision)
            val food = assertNotNull(result.food)
            assertEquals(frozen.draftRefusalCode, food.code)
            assertNull(food.orderId)
            assertNull(food.orderStorageRevision)
            assertTrue(food.portions.isEmpty())
            assertNull(food.followUp)
            val retained = f.journal.entries.getValue(original.operationId)
            assertTrue(original.sameIntent(retained))
            assertEquals(StoredKernelResultV1.from(carrier), retained.recorded)
            assertFalse(retained.acknowledged)
            assertEquals(0, f.journal.stageCalls)
            assertEquals(0, f.journal.dispatchCalls)
            assertEquals(0, f.journal.ackCalls)
            assertTrue(f.master.mutations.isEmpty())
            // The DTO is not an actually recorded rejection; B14 native qualification is absent.
        }
    }

    @Test
    fun fcrp041ShapeOnlyRefusalRemembranceMustCompleteBeforeApplicationAckCanEnter() = runTest(timeout = 10.seconds) {
        withFoodCheckSupportFixture { f ->
            val original = frozen.stagedDraft()
            val carrier = frozen.refusalCarrierData(original)
            f.journal.entries[original.operationId] = original
            f.master.lookupReply = { LookupReply.Found(carrier) }
            val remember = FoodCheckSupportGate().also { f.journal.rememberGate = it }
            val remembering = async { f.gateway.lookup(original.operationId) }
            val ackGate = FoodCheckSupportGate().also { f.journal.ackGate = it }
            runCurrent()
            assertTrue(remember.entered.isCompleted)
            assertFalse(remembering.isCompleted)
            assertNull(f.journal.entries.getValue(original.operationId).recorded)
            val ack = async { f.gateway.acknowledgeRecorded(original.operationId) }
            try {
                runCurrent()
                assertFalse(ack.isCompleted)
                assertEquals(0, f.journal.ackCalls)
                assertFalse(ackGate.entered.isCompleted)
                assertTrue(f.journal.retired.isEmpty())
                remember.release()
                runCurrent()
                assertIs<LocalOperationResult.Recorded>(remembering.await())
                assertEquals(StoredKernelResultV1.from(carrier), f.journal.entries.getValue(original.operationId).recorded)
                assertTrue(ackGate.entered.isCompleted)
                assertFalse(ack.isCompleted)
                assertFalse(f.journal.entries.getValue(original.operationId).acknowledged)
                assertTrue(f.journal.retired.isEmpty())
                assertTrue(f.events.indexOf("result.retained") < f.events.indexOf("ack.before-retention"))
            } finally { remember.release(); ackGate.release() }
            assertIs<LocalAcknowledgementResult.Acknowledged>(ack.await())
            assertEquals(listOf(original.operationId), f.journal.retired)
            assertTrue(original.sameIntent(f.journal.entries.getValue(original.operationId)))
            assertTrue(f.master.lookups.all { it == original.identity() })
            assertTrue(f.master.mutations.isEmpty())
            assertTrue(f.master.reads.isEmpty())
            // Controlled order only: no actual typed original, native flush or transport Receipt.
        }
    }

    @Test
    fun fcrp042ShapeOnlyForeignOrInconsistentFiniteRefusalCannotReachRemembranceOrAck() = runTest {
        val original = frozen.stagedDraft()
        val base = frozen.refusalCarrierData(original)
        val invalid = listOf(
            variant(base, operation = frozen.addOperation),
            variant(base, actor = frozen.id("actorB")),
            variant(base, code = "committed"),
            variant(base, committed = true, code = "committed"),
            variant(base, result = frozen.resultBytes(LocalFoodResultV1("food.order.draft_created", null, null))),
            variant(base, result = frozen.resultBytes(LocalFoodResultV1(frozen.draftRefusalCode, frozen.order, null))),
            variant(base, result = frozen.resultBytes(LocalFoodResultV1(frozen.draftRefusalCode, null, Revision(0)))),
            variant(base, result = frozen.resultBytes(LocalFoodResultV1(frozen.draftRefusalCode, null, null,
                listOf(LocalFoodPortionRevisionV1(frozen.portions.first(), Revision(0), FoodPreparationStatus.NOT_SENT, null))))),
            variant(base, sequence = JournalSequence(0)),
            variant(base, result = frozen.bytes("{")),
        )
        for (carrier in invalid) withFoodCheckSupportFixture { f ->
            f.journal.entries[original.operationId] = original
            f.master.lookupReply = { LookupReply.Found(carrier) }
            assertIs<LocalOperationResult.Unresolved>(f.gateway.lookup(original.operationId))
            assertIs<LocalPendingResult.Unavailable>(f.gateway.pending(null))
            assertIs<LocalAcknowledgementResult.Unresolved>(f.gateway.acknowledgeRecorded(original.operationId))
            assertEquals(original, f.journal.entries.getValue(original.operationId))
            assertEquals(0, f.journal.rememberCalls)
            assertEquals(0, f.journal.ackCalls)
            assertTrue(f.journal.retired.isEmpty())
            assertTrue(f.master.mutations.isEmpty())
        }
        // No native producer or positive business-revision baseline is supplied.
    }

    @Test
    fun fcrp043ShapeOnlyFailedAndCancelledRefusalRemembranceNeverEnablesAckOrForgetting() = runTest(timeout = 10.seconds) {
        withFoodCheckSupportFixture { f ->
            val original = frozen.stagedDraft()
            f.journal.entries[original.operationId] = original
            f.master.lookupReply = { LookupReply.Found(frozen.refusalCarrierData(original)) }
            f.journal.rememberWrite = FoodCheckSupportWrite.FAILED
            assertIs<LocalOperationResult.Unresolved>(f.gateway.lookup(original.operationId))
            assertIs<LocalAcknowledgementResult.Unresolved>(f.gateway.acknowledgeRecorded(original.operationId))
            assertEquals(original, f.journal.entries.getValue(original.operationId))
            assertEquals(1, f.journal.rememberCalls)
            assertEquals(0, f.journal.ackCalls)
            assertTrue(f.journal.retired.isEmpty())
            assertTrue(f.master.mutations.isEmpty())
        }
        withFoodCheckSupportFixture { f ->
            val original = frozen.stagedDraft()
            f.journal.entries[original.operationId] = original
            f.master.lookupReply = { LookupReply.Found(frozen.refusalCarrierData(original)) }
            val gate = FoodCheckSupportGate().also { f.journal.rememberGate = it }
            val remembering = async { f.gateway.lookup(original.operationId) }
            try {
                runCurrent()
                assertTrue(gate.entered.isCompleted)
                assertNull(f.journal.entries.getValue(original.operationId).recorded)
                remembering.cancelAndJoin()
                assertTrue(remembering.isCancelled)
            } finally { gate.release() }
            assertIs<LocalAcknowledgementResult.Unresolved>(f.gateway.acknowledgeRecorded(original.operationId))
            assertEquals(original, f.journal.entries.getValue(original.operationId))
            assertEquals(0, f.journal.ackCalls)
            assertTrue(f.journal.retired.isEmpty())
            assertTrue(f.master.mutations.isEmpty())
        }
        // Native flush faults/unknown retained-write outcomes and restart cuts remain unbound.
    }

    @Test
    fun fcrp044ShapeOnlyFailedAndRepeatedAckNeverMintAnotherBusinessOperation() = runTest {
        withFoodCheckSupportFixture { f ->
            val original = frozen.stagedDraft()
            val carrier = frozen.refusalCarrierData(original)
            f.journal.entries[original.operationId] = original
            f.master.lookupReply = { LookupReply.Found(carrier) }
            assertIs<LocalOperationResult.Recorded>(f.gateway.lookup(original.operationId))
            val remembered = f.journal.entries.getValue(original.operationId)
            f.journal.ackWrite = FoodCheckSupportWrite.FAILED
            assertIs<LocalAcknowledgementResult.Unresolved>(f.gateway.acknowledgeRecorded(original.operationId))
            assertEquals(remembered, f.journal.entries.getValue(original.operationId))
            assertTrue(f.journal.retired.isEmpty())
            f.journal.ackWrite = FoodCheckSupportWrite.COMPLETED
            repeat(2) { assertIs<LocalAcknowledgementResult.Acknowledged>(f.gateway.acknowledgeRecorded(original.operationId)) }
            assertEquals(listOf(original.operationId), f.journal.retired)
            val retired = f.journal.entries.getValue(original.operationId)
            assertTrue(original.sameIntent(retired))
            assertEquals(remembered.recorded, retired.recorded)
            assertTrue(retired.acknowledged)
            assertTrue(f.master.lookups.all { it == original.identity() })
            assertTrue(f.master.mutations.isEmpty())
            assertTrue(f.master.reads.isEmpty())
            assertEquals(0, f.journal.stageCalls)
            assertEquals(0, f.journal.dispatchCalls)
            // No actual lost RPC ACK or native retirement is simulated by these scripted writes.
        }
    }

    @Test
    fun fcrp040ShapeOnlyDeniedDisclosureDoesNotForgetTheExactRetainedRefusal() = runTest {
        withFoodCheckSupportFixture { f ->
            val original = frozen.stagedDraft()
            val carrier = frozen.refusalCarrierData(original)
            f.journal.entries[original.operationId] = original
            f.master.lookupReply = { LookupReply.Found(carrier) }
            assertIs<LocalOperationResult.Recorded>(f.gateway.lookup(original.operationId))
            val retained = f.journal.entries.getValue(original.operationId)
            f.master.lookupReply = { LookupReply.Deferred(it.operationId, DeferredReason.AUTHORIZATION_DENIED) }
            assertEquals(LocalServiceProblem.DENIED,
                assertIs<LocalOperationResult.Unresolved>(f.gateway.lookup(original.operationId)).problem)
            assertEquals(LocalServiceProblem.DENIED,
                assertIs<LocalAcknowledgementResult.Unresolved>(f.gateway.acknowledgeRecorded(original.operationId)).problem)
            assertIs<LocalPendingResult.Unavailable>(f.gateway.pending(null))
            assertEquals(retained, f.journal.entries.getValue(original.operationId))
            assertFalse(retained.acknowledged)
            assertEquals(0, f.journal.ackCalls)
            assertTrue(f.journal.retired.isEmpty())
            assertTrue(f.master.mutations.isEmpty())
            assertTrue(f.master.reads.isEmpty())
            // Denial is a supplied endpoint result, not an actual protected disclosure fence.
        }
    }

    private fun variant(base: MutationReply.Recorded, operation: OperationId = base.operationId,
        actor: DomainId = base.originalActorId, committed: Boolean = base.committed, code: String = base.code,
        result: StoredBytes = base.result, sequence: JournalSequence = base.sequence): MutationReply.Recorded =
        MutationReply.Recorded(operation, actor, committed, code, result, base.authorityEpoch, sequence,
            base.recordedAt, base.replayed, base.physicalStart)
}
