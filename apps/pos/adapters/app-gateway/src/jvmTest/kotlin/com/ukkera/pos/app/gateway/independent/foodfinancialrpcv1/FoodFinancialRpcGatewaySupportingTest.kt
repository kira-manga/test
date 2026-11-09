package com.ukkera.pos.app.gateway.independent.foodfinancialrpcv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockRelationalQueriesV2
import com.ukkera.pos.sales.store.*
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

/** Frozen FFRC v1 supporting gateway observations, with real client storage and controlled Master ports. */
class FoodFinancialRpcGatewaySupportingTest {
    private val input get() = FrozenFoodFinancialRpc

    @Test
    fun ffrc001StoredFinancialOriginalCannotUpgradeFoodChecksInstallation() = runBlocking {
        input.case("FFRC-001")
        withFoodFinancialGateway { fixture ->
            val old = LocalWorkflowInstallation.directoryOnly(LocalMasterRpcWorkflowProfile.FOOD_CHECKS)
            assertEquals(LocalMasterRpcWorkflowProfile.FOOD_CHECKS, old.directoryProfile)
            assertFalse(old.isFoodCashFinancialOnly)
            assertEquals(LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL, fixture.installed.directoryProfile)
            assertEquals(input.actions.toSet(), LocalAction.entries.filter { fixture.installed.allows(it) }.toSet())
            assertTrue(fixture.installed.has(LocalFoodStockCodecV2.COMMAND))
            assertTrue(fixture.installed.has(LocalFoodPaymentProfile.COMMAND))
            assertFalse(fixture.installed.has(LocalFoodFinancialProfileV1.COMMAND))
            val intent = input.intent(LocalAction.FOOD_BEGIN_COLLECTION)
            assertIs<LocalStageResult.Staged>(fixture.gateway().stage(intent))
            val original = fixture.find(intent.operationId)
            fixture.master.reads.clear()
            fixture.master.lookups.clear()
            val oldGateway = fixture.gateway(old)
            assertIs<LocalReadResult.Unavailable>(oldGateway.read(LocalWorkflowQuery(LocalWorkflow.FOOD,
                food = LocalFoodUiQuery.Collection(input.collection))))
            assertIs<LocalOperationResult.Unresolved>(oldGateway.lookup(intent.operationId))
            assertIs<LocalOperationResult.Unresolved>(oldGateway.submit(intent.operationId))
            assertIs<LocalPendingResult.Unavailable>(oldGateway.pending())
            assertTrue(fixture.master.reads.isEmpty())
            assertTrue(fixture.master.lookups.isEmpty())
            assertTrue(fixture.master.mutations.isEmpty())
            assertEquals(LocalMasterRpcWorkflowProfile.FOOD_CHECKS, old.directoryProfile)
            assertEquals(original, fixture.find(intent.operationId))
            assertEquals(1, fixture.journal.stages)
        }
        // Connection registration, conflicting request assertions and actual L1/L2 custody belong to R.
    }

    @Test
    fun ffrc003ProductionReadSelectorChoosesExactlyOneAdvertisedVersion() = runBlocking {
        input.case("FFRC-003")
        val keys = listOf(LocalFoodCheckKitchenCodecV1.QUERY, LocalFoodCheckKitchenCodecV2.QUERY,
            LocalFoodCheckKitchenCodecV3.QUERY)
        for (last in 3 downTo 1) withFoodFinancialGateway { fixture ->
            val installation = LocalWorkflowInstallation(emptySet(), keys.take(last).toSet())
            val cut = fixture.readAccess(installation) { foodRead(it, LocalFoodQueryV1.Order(input.order)) }
            assertEquals(listOf(keys[last - 1]), fixture.master.reads.map { it.query })
            if (last == 3) {
                assertEquals(57_013L, input.payable.minor)
                assertEquals(input.payable, cut.currentUnpaidPayable)
                assertEquals(LocalFoodUiCurrentUnpaidPayableProfile.ORDINARY_UNPAID_V3, cut.currentUnpaidPayableProfile)
            } else {
                assertNull(cut.currentUnpaidPayable)
                assertEquals(LocalFoodUiCurrentUnpaidPayableProfile.NEVER_SENT_ONLY, cut.currentUnpaidPayableProfile)
            }
            assertTrue(fixture.master.mutations.isEmpty())
            assertTrue(fixture.master.lookups.isEmpty())
            assertEquals(0, fixture.journal.stages)
        }
        // The reduced registries exercise the shared production selection seam, not admitted remote profiles.
    }

    @Test
    fun ffrc003ChosenVersionDenialLossMalformedSourceAndNullNeverFallback() = runBlocking {
        input.case("FFRC-003")
        for (variant in listOf("denied", "lost", "malformed", "source-unavailable", "valid-null")) {
            withFoodFinancialGateway { fixture ->
                fixture.master.readReply = { request ->
                    assertEquals(LocalFoodCheckKitchenCodecV3.QUERY, request.query, variant)
                    when (variant) {
                        "denied" -> ReadReply.Unavailable(ReadFailure.AUTHORIZATION_DENIED)
                        "lost" -> error("Controlled transport loss")
                        "malformed" -> input.data(input.bytes("{}"))
                        "source-unavailable" -> input.data(input.checkPayload(request.query, missingKitchen = true))
                        else -> input.data(input.checkPayload(request.query, current = null))
                    }
                }
                repeat(2) { // A rebuilt gateway keeps the explicit profile and the same selected read version.
                    val answer = fixture.gateway().read(LocalWorkflowQuery(LocalWorkflow.FOOD,
                        food = LocalFoodUiQuery.Check(input.order)))
                    if (variant == "valid-null") {
                        val body = assertIs<LocalFoodUiBody.Check>(assertIs<LocalReadResult.Available>(answer).page.food!!.body)
                        assertNull(body.currentUnpaidPayable)
                        assertEquals(input.payable, body.originalQuotedTotal)
                    } else assertIs<LocalReadResult.Unavailable>(answer, variant)
                }
                assertEquals(listOf(LocalFoodCheckKitchenCodecV3.QUERY, LocalFoodCheckKitchenCodecV3.QUERY),
                    fixture.master.reads.map { it.query }, variant)
                assertTrue(fixture.master.mutations.isEmpty(), variant)
                assertTrue(fixture.master.lookups.isEmpty(), variant)
                assertEquals(0, fixture.journal.stages, variant)
            }
        }
    }

    @Test
    fun ffrc023AllThreeForm1CommandsStageExactOriginalBeforeFirstSubmit() = runBlocking {
        input.case("FFRC-023")
        for (action in input.actions) withFoodFinancialGateway { fixture ->
            val intent = input.intent(action)
            val barrier = FoodFinancialWriteBarrier()
            fixture.journal.stageBarrier = barrier
            val gateway = fixture.gateway()
            val staging = async { gateway.stage(intent) }
            try {
                withTimeout(10_000) { barrier.entered.await() }
                assertNull(assertIs<JournalRead.Value<StoredClientIntentV1?>>(fixture.journal.find(intent.operationId)).value)
                assertTrue(fixture.master.mutations.isEmpty())
                assertEquals(0, fixture.journal.dispatches)
            } finally { barrier.release() }
            assertEquals(intent.operationId, assertIs<LocalStageResult.Staged>(staging.await()).operation.operationId)
            val original = fixture.find(intent.operationId)
            input.assertOriginal(original, intent)
            assertEquals(LocalPendingPhase.STAGED, original.localPhase)
            assertTrue(fixture.master.mutations.isEmpty())
            fixture.master.reads.clear()
            fixture.master.readReply = { error("Saved Stage/Submit must not refresh a source or quote") }
            assertIs<LocalStageResult.Staged>(gateway.stage(intent))
            assertEquals(1, fixture.journal.stages)
            assertEquals(original, fixture.find(intent.operationId))
            assertIs<LocalOperationResult.Unresolved>(gateway.submit(intent.operationId))
            val dispatched = fixture.find(intent.operationId)
            input.assertOriginal(dispatched, intent)
            assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED, dispatched.localPhase)
            assertEquals(original.identity(), fixture.master.lookups.last())
            val sent = fixture.master.mutations.single()
            assertEquals(original.operationId, sent.operationId)
            assertEquals(original.request().command, sent.command)
            assertEquals(original.request().payload, sent.payload)
            assertTrue(fixture.master.reads.isEmpty())
            assertTrue(fixture.events.indexOf("stage.durable") < fixture.events.indexOf("dispatch.durable"))
            assertTrue(fixture.events.indexOf("dispatch.durable") < fixture.events.indexOf("execute.enter"))
        }
    }

    @Test
    fun ffrc023FailedStageReturnsSameUncertainIdentityAndNeverDispatches() = runBlocking {
        input.case("FFRC-023")
        for (action in input.actions) withFoodFinancialGateway { fixture ->
            val intent = input.intent(action)
            fixture.journal.failStage = true
            val gateway = fixture.gateway()
            assertEquals(intent.operationId, assertIs<LocalStageResult.Uncertain>(gateway.stage(intent)).operationId)
            assertIs<LocalOperationResult.Unresolved>(gateway.lookup(intent.operationId))
            assertIs<LocalOperationResult.Unresolved>(gateway.submit(intent.operationId))
            assertTrue(fixture.master.mutations.isEmpty())
            assertTrue(fixture.master.lookups.isEmpty())
            assertEquals(0, fixture.journal.dispatches)
            assertEquals(0, fixture.journal.acknowledgements)
        }
        // The failure is injected before the real write; native IO-failure qualification is separate.
    }

    @Test
    fun ffrc012FreshApplyStageBindsReadRecordingOrderCollectionAmountAndDigest() = runBlocking {
        input.case("FFRC-012")
        input.case("FFRC-023")
        val valid = input.intent(LocalAction.FOOD_PAYMENT_APPLY)
        val mismatches = listOf(
            LocalField.FOOD_PAYMENT_RECORDING_OPERATION_ID to LocalScalarValue.Identifier(input.id("other-recording")),
            LocalField.ORDER_ID to LocalScalarValue.Identifier(input.id("other-order")),
            LocalField.COLLECTION_ID to LocalScalarValue.Identifier(input.id("other-collection")),
            LocalField.AMOUNT to LocalScalarValue.Decimal("570.14"),
            LocalField.FOOD_PAYMENT_ORIGINAL_BODY_DIGEST to LocalScalarValue.Text("f".repeat(64)))
        for ((field, value) in mismatches) withFoodFinancialGateway { fixture ->
            assertIs<LocalStageResult.NotStaged>(fixture.gateway().stage(valid.financialChange(field, value)), field.name)
            assertEquals(0, fixture.journal.stages, field.name)
            assertTrue(fixture.master.mutations.isEmpty(), field.name)
            assertTrue(fixture.master.lookups.isEmpty(), field.name)
        }
        withFoodFinancialGateway { fixture ->
            fixture.master.originalData = input.original.copy(summary = input.original.summary.copy(
                originalActorId = input.otherActor, recordingActorId = input.otherActor))
            assertIs<LocalStageResult.NotStaged>(fixture.gateway().stage(valid))
            assertEquals(0, fixture.journal.stages)
            assertTrue(fixture.master.mutations.isEmpty())
        }
        withFoodFinancialGateway { fixture ->
            fixture.master.originalData = LocalFoodPaymentReadResult.NotAvailable
            assertIs<LocalStageResult.NotStaged>(fixture.gateway().stage(valid))
            assertEquals(0, fixture.journal.stages)
            assertTrue(fixture.master.mutations.isEmpty())
        }
        // These are source-DATA equality checks; genuine native original qualification belongs to the Master provider.
    }

    @Test
    fun ffrc001And023NoNativeFormOrLegacyFinancialFallbackIsAdmitted() = runBlocking {
        input.case("FFRC-001")
        input.case("FFRC-023")
        for (action in input.actions) withFoodFinancialGateway { fixture ->
            val original = input.intent(action)
            val nativeShape = LocalCommandIntent(original.operationId, original.action, 2, null, original.values)
            assertIs<LocalStageResult.NotStaged>(fixture.gateway().stage(nativeShape))
            assertEquals(0, fixture.journal.stages)
            assertTrue(fixture.master.reads.isEmpty())
            assertTrue(fixture.master.mutations.isEmpty())
        }
        for (action in listOf(LocalAction.FOOD_BEGIN_COLLECTION, LocalAction.FOOD_FINALIZE_PAID)) {
            withFoodFinancialGateway { fixture ->
                fixture.master.normalizedStock = false
                assertIs<LocalStageResult.NotStaged>(fixture.gateway().stage(input.intent(action)))
                assertEquals(0, fixture.journal.stages)
                assertTrue(fixture.master.mutations.isEmpty())
                assertTrue(fixture.master.reads.any { it.query == LocalStockRelationalQueriesV2.PROFILE })
                assertFalse(fixture.installed.has(LocalFoodFinancialProfileV1.COMMAND))
            }
        }
    }
}
