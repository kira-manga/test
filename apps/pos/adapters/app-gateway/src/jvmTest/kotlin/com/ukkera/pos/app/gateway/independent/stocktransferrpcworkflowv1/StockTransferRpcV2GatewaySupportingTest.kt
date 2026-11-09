package com.ukkera.pos.app.gateway.independent.stocktransferrpcworkflowv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.app.gateway.independent.warehouserpcworkflowv1.WarehouseRpcGate
import com.ukkera.pos.app.gateway.independent.warehouserpcworkflowv1.WarehouseRpcWriteCut
import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * Frozen stock-transfer-rpc-workflow-v2 gateway SUPPORT ONLY slices. Controlled
 * replies/order ports do not establish native authority, authenticated custody,
 * business effects, atomic commit, RPC lifetime, durable storage or restart.
 * Actual installed navigation, grant activation and whole production cases remain separate.
 */
class StockTransferRpcV2GatewaySupportingTest {
    private val input get() = FrozenStockTransferRpcV2

    @Test
    fun s02s05ExactSourceFormAndPickerUseOnlyTheFrozenActiveDestinations() = runBlocking {
        withStockTransferRpcV2Fixture { fixture ->
            val itemPage = assertIs<LocalReadResult.Available>(fixture.gateway.read(input.query())).page
            val sourceRecord = itemPage.records.single { it.reference == input.sourceReference }
            assertEquals(quantityText(input.quantity(input.sourceInput.stockTransferText("quantity"))),
                sourceRecord.displayValue("On hand"))
            val page = assertIs<LocalReadResult.Available>(fixture.gateway.read(input.query(input.sourceReference))).page
            assertEquals(listOf(input.sourceReference), page.records.map { it.reference })
            val form = page.forms.single()
            assertEquals(LocalAction.WAREHOUSE_TRANSFER, form.action)
            assertEquals(1, form.version)
            val fields = form.fields.filterIsInstance<LocalFormField.Scalar>().associateBy { it.field }
            for ((field, expected) in listOf(
                LocalField.POSITION_ID to LocalScalarValue.Reference(input.sourceReference),
                LocalField.WAREHOUSE_ID to LocalScalarValue.Identifier(input.source.warehouseId),
                LocalField.LOT_ID to LocalScalarValue.Identifier(checkNotNull(input.source.lotId)),
                LocalField.CUSTODY to LocalScalarValue.Choice(input.source.custody.name),
                LocalField.UNIT to LocalScalarValue.Identifier(input.unit),
                LocalField.QUANTITY_SCALE to LocalScalarValue.Integer(input.scale.toLong()),
                LocalField.EXPECTED_ITEM_STORAGE_REVISION to LocalScalarValue.Integer(input.revision.value),
            )) {
                assertEquals(expected, fields.getValue(field).initialValue)
                assertTrue(fields.getValue(field).readOnly)
            }
            val source = checkNotNull(fields.getValue(LocalField.DESTINATION_ID).referenceSource)
            assertEquals(LocalReferenceSource(LocalRecordKind.WAREHOUSE, input.source.warehouseId), source)
            val choices = assertIs<LocalReferenceResult.Available>(fixture.gateway.references(LocalReferenceQuery(source))).page
            assertEquals(input.destinationSet, choices.choices.map { it.reference.id })
            assertNull(choices.nextAfter)
            for (alias in listOf("W1", "W3", "W5", "W6")) {
                val exact = assertIs<LocalReferenceResult.Available>(fixture.gateway.references(
                    LocalReferenceQuery(source, exactId = input.id(alias)))).page
                assertTrue(exact.choices.isEmpty(), alias)
            }
            assertNoCostValues(itemPage.records + page.records)
            assertTrue(fixture.journal.entries.isEmpty())
            assertEquals(0, fixture.journal.stageCalls)
            assertTrue(fixture.master.mutations.isEmpty())
            // S02-WAREHOUSES/S05-DESTINATIONS: the shared picker filters a controlled B1 reply.
            // This is not evidence that an authenticated native Master filters foreign scope.
        }
    }

    @Test
    fun s05InvalidExactQuantityRevisionAndDestinationNeverReachIntentRetention() = runBlocking {
        val selected = setOf("INPUT-ZERO", "INPUT-NEGATIVE", "INPUT-PRECISION", "INPUT-SOURCEDEST",
            "INPUT-INACTIVEDEST", "INPUT-BRANCHDEST", "INPUT-COMPANYDEST", "INPUT-STALE")
        val variants = input.fixtures.getValue("F-ST-RPC-INPUT").jsonObject.getValue("variants").jsonArray
            .map { it.jsonObject }.filter { it.stockTransferText("id") in selected }
        assertEquals(selected, variants.map { it.stockTransferText("id") }.toSet())
        for (variant in variants) withStockTransferRpcV2Fixture { fixture ->
            val name = variant.stockTransferText("id")
            val change = variant["change"] as? JsonObject
            if (name == "INPUT-STALE") fixture.sourceRevision = Revision(8)
            val intent = input.intent(quantity = change?.get("quantity")?.jsonPrimitive?.content
                ?: input.original.stockTransferText("quantity"), destinationAlias =
                change?.get("destination")?.jsonPrimitive?.content ?: input.original.stockTransferText("destination"))
            val refusal = assertIs<LocalStageResult.NotStaged>(fixture.gateway.stage(intent), name)
            assertEquals(LocalServiceProblem.INVALID_INPUT, refusal.problem, name)
            assertTrue(fixture.journal.entries.isEmpty(), name)
            assertEquals(0, fixture.journal.stageCalls, name)
            assertEquals(0, fixture.journal.dispatchCalls, name)
            assertTrue(fixture.master.mutations.isEmpty(), name)
            assertTrue(fixture.master.lookups.isEmpty(), name)
            // S05-INPUT: no controlled save/dispatch; native business zero-effects are not observed.
        }
    }

    @Test
    fun s02s05MissingSkuAndUnavailableNormalizedReplyNeverBecomeZeroOrLegacy() = runBlocking {
        withStockTransferRpcV2Fixture { fixture ->
            val query = input.query(LocalRecordReference(LocalRecordKind.SKU, input.missingSku))
            val page = assertIs<LocalReadResult.Available>(fixture.gateway.read(query)).page
            assertTrue(page.records.isEmpty())
            assertTrue(page.forms.isEmpty())
            assertTrue(checkNotNull(page.notice).english.contains("not a zero balance"))
            assertTrue(checkNotNull(page.notice).arabic.isNotBlank())
            assertEquals(listOf(LocalStockRelationalQueriesV2.PROFILE, LocalStockStoreProfile.QUANTITIES),
                fixture.master.reads.map { it.query })
            assertTrue(fixture.master.mutations.isEmpty())
            assertTrue(fixture.journal.entries.isEmpty())
        }
        withStockTransferRpcV2Fixture { fixture ->
            fixture.normalized = false
            assertEquals(LocalServiceProblem.UNAVAILABLE,
                assertIs<LocalReadResult.Unavailable>(fixture.gateway.read(input.query())).problem)
            assertEquals(LocalServiceProblem.UNAVAILABLE,
                assertIs<LocalStageResult.NotStaged>(fixture.gateway.stage(input.intent())).problem)
            assertTrue(fixture.master.reads.all { it.query == LocalStockRelationalQueriesV2.PROFILE })
            assertTrue(fixture.master.mutations.isEmpty())
            assertTrue(fixture.journal.entries.isEmpty())
            assertEquals(0, fixture.journal.stageCalls)
        }
        // S02-MISSING/S05-MISSING and controlled absent-profile handling only. Genuine absent
        // normalized storage and intact legacy compatibility remain P04 obligations.
    }

    @Test
    fun s03s05ExactNormalizedOriginalIsSavedBeforeDispatchAndRecoveredWithoutFreshReads() = runBlocking {
        withStockTransferRpcV2Fixture { fixture ->
            val saveGate = WarehouseRpcGate()
            fixture.journal.stageGate = saveGate
            val saving = async { fixture.gateway.stage(input.intent()) }
            try {
                withTimeout(10_000) { saveGate.entered.await() }
                assertTrue(fixture.journal.entries.isEmpty())
                assertTrue(fixture.master.mutations.isEmpty())
                assertEquals(0, fixture.journal.dispatchCalls)
            } finally { saveGate.release() }
            val staged = assertIs<LocalStageResult.Staged>(saving.await()).operation
            assertEquals(input.operation, staged.operationId)
            assertTrue(staged.submitAllowed)
            val original = fixture.journal.entries.getValue(input.operation)
            input.assertOriginal(original)
            val identity = original.identity()
            val digest = original.inputDigest
            fixture.master.reads.clear()
            fixture.master.readReply = { error("Saved stock-transfer recovery must not perform a fresh preparatory read") }
            val dispatchGate = WarehouseRpcGate()
            fixture.master.executeGate = dispatchGate
            val submitting = async { fixture.gateway.submit(input.operation) }
            try {
                withTimeout(10_000) { dispatchGate.entered.await() }
                val dispatched = fixture.journal.entries.getValue(input.operation)
                input.assertOriginal(dispatched)
                assertEquals(identity, dispatched.identity())
                assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED.name, dispatched.phase)
                assertNull(dispatched.recorded)
                assertEquals(identity, fixture.master.lookups.single())
                val request = fixture.master.mutations.single()
                assertEquals(original.request().command, request.command)
                assertEquals(original.request().payload, request.payload)
                val savedIndex = fixture.events.indexOf("intent.retained")
                val dispatchedIndex = fixture.events.indexOf("dispatch.retained")
                val executeIndex = fixture.events.indexOf("execute.enter")
                assertTrue(savedIndex >= 0 && dispatchedIndex >= 0 && executeIndex >= 0)
                assertTrue(savedIndex < dispatchedIndex)
                assertTrue(dispatchedIndex < executeIndex)
            } finally { dispatchGate.release() }
            val result = assertIs<LocalOperationResult.Recorded>(submitting.await())
            assertEquals(LocalRecordedDecision.APPLIED, result.decision)
            assertEquals(LocalRecordReference(LocalRecordKind.TRANSFER, input.transferId), checkNotNull(result.result).reference)
            assertNoCostValues(listOf(checkNotNull(result.result)))
            val retained = fixture.journal.entries.getValue(input.operation)
            input.assertOriginal(retained)
            assertNotNull(retained.recorded)
            assertFalse(retained.acknowledged)
            assertEquals(identity, retained.identity())
            assertEquals(digest, retained.inputDigest)

            val mismatches = input.fixtures.getValue("F-ST-RPC-INTENT").jsonObject.getValue("mismatches").jsonArray
                .map { it.jsonObject }.associateBy { it.stockTransferText("id") }
            val changedQuantity = mismatches.getValue("INTENT-BODY").getValue("change").jsonObject.stockTransferText("quantity")
            val changedRevision = mismatches.getValue("INTENT-REVISION").getValue("change").jsonObject.stockTransferText("source_revision")
            assertIs<LocalStageResult.Uncertain>(fixture.gateway.stage(input.intent(quantity = changedQuantity)))
            assertIs<LocalStageResult.Uncertain>(fixture.gateway.stage(input.intent(revision = Revision(changedRevision.toLong()))))
            assertEquals(LocalServiceProblem.RETRY_LATER,
                assertIs<LocalStageResult.NotStaged>(fixture.gateway.stage(input.intent(input.otherOperation))).problem)
            assertIs<LocalOperationResult.Recorded>(fixture.gateway.submit(input.operation))
            assertTrue(assertIs<LocalOperationResult.Recorded>(fixture.gateway.lookup(input.operation)).replayed)
            input.assertOriginal(fixture.journal.entries.getValue(input.operation))
            assertEquals(digest, fixture.journal.entries.getValue(input.operation).inputDigest)
            assertFalse(fixture.journal.entries.containsKey(input.otherOperation))
            assertTrue(fixture.master.reads.isEmpty())
            assertTrue(fixture.master.lookups.all { it == identity })
            assertEquals(1, fixture.master.mutations.size)
            // S03-SAVEFIRST/ORIGINALRECOVERY/MISMATCH and S05-IMMUTABLE exact-body slices.
            // No native restart, original Client/Host lifetime or authenticated-custody pass.
        }
    }

    @Test
    fun s03s04FailedSaveOrTerminalRetentionCannotBecomeSuccessOrAcknowledgement() = runBlocking {
        withStockTransferRpcV2Fixture { fixture ->
            fixture.journal.stageCut = WarehouseRpcWriteCut.FAILED
            assertIs<LocalStageResult.Uncertain>(fixture.gateway.stage(input.intent()))
            assertTrue(fixture.journal.entries.isEmpty())
            assertIs<LocalOperationResult.Unresolved>(fixture.gateway.submit(input.operation))
            assertIs<LocalAcknowledgementResult.Unresolved>(fixture.gateway.acknowledgeRecorded(input.operation))
            assertTrue(fixture.master.mutations.isEmpty())
            assertTrue(fixture.master.lookups.isEmpty())
            assertEquals(0, fixture.journal.dispatchCalls)
            assertEquals(0, fixture.journal.ackCalls)
        }
        withStockTransferRpcV2Fixture { fixture ->
            assertIs<LocalStageResult.Staged>(fixture.gateway.stage(input.intent()))
            val identity = fixture.journal.entries.getValue(input.operation).identity()
            fixture.journal.rememberCut = WarehouseRpcWriteCut.FAILED
            assertEquals(LocalServiceProblem.STORAGE_UNCERTAIN,
                assertIs<LocalOperationResult.Unresolved>(fixture.gateway.submit(input.operation)).problem)
            val unresolved = fixture.journal.entries.getValue(input.operation)
            input.assertOriginal(unresolved)
            assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED.name, unresolved.phase)
            assertNull(unresolved.recorded)
            assertFalse(unresolved.acknowledged)
            assertIs<LocalAcknowledgementResult.Unresolved>(fixture.gateway.acknowledgeRecorded(input.operation))
            assertEquals(0, fixture.journal.ackCalls)
            fixture.journal.rememberCut = WarehouseRpcWriteCut.DURABLE
            assertIs<LocalOperationResult.Recorded>(fixture.gateway.lookup(input.operation))
            input.assertOriginal(fixture.journal.entries.getValue(input.operation))
            assertEquals(identity, fixture.journal.entries.getValue(input.operation).identity())
            assertEquals(1, fixture.master.mutations.size)
            assertTrue(fixture.master.lookups.all { it == identity })
        }
        // S03-SAVEFIRST/UNCERTAINTY and S04-RETENTION: controlled client write/answer cuts only.
    }

    @Test
    fun s03s04MismatchedTypedTerminalCarriersAreNeverRetainedPresentedOrAcknowledged() = runBlocking {
        val mismatches = listOf(
            input.recorded(payload = input.resultPayload(document = input.slot("mismatched-result-document"))),
            input.recorded(payload = input.resultPayload(item = input.missingSku)),
            input.recorded(payload = input.resultPayload(itemRevision = input.revision)),
            input.recorded(actor = input.id("E2")),
            input.recorded(operation = input.otherOperation),
        )
        for (mismatch in mismatches) withStockTransferRpcV2Fixture { fixture ->
            assertIs<LocalStageResult.Staged>(fixture.gateway.stage(input.intent()))
            val identity = fixture.journal.entries.getValue(input.operation).identity()
            fixture.master.executeReply = { request ->
                fixture.master.terminalCarriers[request.operationId] = mismatch
                mismatch
            }
            assertIs<LocalOperationResult.Unresolved>(fixture.gateway.submit(input.operation))
            assertIs<LocalOperationResult.Unresolved>(fixture.gateway.lookup(input.operation))
            assertIs<LocalAcknowledgementResult.Unresolved>(fixture.gateway.acknowledgeRecorded(input.operation))
            val unresolved = fixture.journal.entries.getValue(input.operation)
            input.assertOriginal(unresolved)
            assertEquals(identity, unresolved.identity())
            assertNull(unresolved.recorded)
            assertFalse(unresolved.acknowledged)
            assertEquals(0, fixture.journal.rememberCalls)
            assertEquals(0, fixture.journal.ackCalls)
            assertEquals(1, fixture.master.mutations.size)
        }
        // S03-MISMATCH/S04-MISMATCH/RETENTION typed/outer identity slices.
        // Matching visible coordinates still cannot prove actual authenticated native custody.
    }

    @Test
    fun s04OnlyKnownMatchingAckClearsItsMarkerAndUncertainAckResolvesTheSameOriginal() = runBlocking {
        for (cut in listOf(WarehouseRpcWriteCut.DURABLE, WarehouseRpcWriteCut.FAILED,
            WarehouseRpcWriteCut.RETAINED_REPLY_UNKNOWN)) withStockTransferRpcV2Fixture { fixture ->
            assertIs<LocalStageResult.Staged>(fixture.gateway.stage(input.intent()))
            assertIs<LocalAcknowledgementResult.Unresolved>(fixture.gateway.acknowledgeRecorded(input.operation))
            assertEquals(0, fixture.journal.ackCalls)
            assertIs<LocalOperationResult.Recorded>(fixture.gateway.submit(input.operation))
            val original = fixture.journal.entries.getValue(input.operation)
            val identity = original.identity()
            val retained = checkNotNull(original.recorded)
            // Separate inert pending evidence proves this marker is not a clear-all operation.
            val other = StoredClientIntentV1.staged(input.owner, input.intent(input.otherOperation), input.request(input.otherOperation),
                LocalText("Other retained transfer", "نقل آخر محفوظ"), listOf(recordValue(
                    LocalText("Pending operation", "العملية المعلقة"), literal(input.otherOperation.value))))
            fixture.journal.entries[input.otherOperation] = other
            assertIs<LocalAcknowledgementResult.Unresolved>(fixture.gateway.acknowledgeRecorded(input.otherOperation))
            fixture.journal.ackCut = cut
            val first = fixture.gateway.acknowledgeRecorded(input.operation)
            if (cut == WarehouseRpcWriteCut.DURABLE) assertIs<LocalAcknowledgementResult.Acknowledged>(first)
            else assertIs<LocalAcknowledgementResult.Unresolved>(first)
            if (cut == WarehouseRpcWriteCut.FAILED) {
                assertFalse(fixture.journal.entries.getValue(input.operation).acknowledged)
                assertTrue(fixture.journal.cleared.isEmpty())
            }
            input.assertOriginal(fixture.journal.entries.getValue(input.operation))
            assertEquals(retained, fixture.journal.entries.getValue(input.operation).recorded)
            assertEquals(other, fixture.journal.entries.getValue(input.otherOperation))
            fixture.journal.ackCut = WarehouseRpcWriteCut.DURABLE
            assertEquals(input.operation,
                assertIs<LocalAcknowledgementResult.Acknowledged>(fixture.gateway.acknowledgeRecorded(input.operation)).operationId)
            assertIs<LocalAcknowledgementResult.Acknowledged>(fixture.gateway.acknowledgeRecorded(input.operation))
            assertEquals(listOf(input.operation), fixture.journal.cleared)
            assertTrue(fixture.journal.entries.getValue(input.operation).acknowledged)
            assertEquals(identity, fixture.journal.entries.getValue(input.operation).identity())
            assertEquals(retained, fixture.journal.entries.getValue(input.operation).recorded)
            assertEquals(other, fixture.journal.entries.getValue(input.otherOperation))
            assertIs<LocalOperationResult.Recorded>(fixture.gateway.lookup(input.operation))
            assertEquals(1, fixture.master.mutations.size)
            // The unknown reply cut may retain a controlled ACK bit but returns no known ACK
            // presentation. Resolving it uses the same identity/result, never a new transfer.
        }
        // S04-MATCH/NONTERMINAL/ACKFAIL/ACKLOSS/DUPLICATE gateway slices, not native durability.
        // Notification ACK and installed displayed-result clearance are not exercised here.
    }

    @Test
    fun s06TransferQuantityRefreshAndSeparateCostReplyCannotAdoptTheOriginalIntent() = runBlocking {
        withStockTransferRpcV2Fixture { fixture ->
            assertIs<LocalStageResult.Staged>(fixture.gateway.stage(input.intent()))
            assertIs<LocalOperationResult.Recorded>(fixture.gateway.submit(input.operation))
            assertIs<LocalAcknowledgementResult.Acknowledged>(fixture.gateway.acknowledgeRecorded(input.operation))
            val original = fixture.journal.entries.getValue(input.operation)
            fixture.afterTransfer = true
            fixture.sourceRevision = input.revision.next()
            fixture.master.reads.clear()
            val quantityPage = assertIs<LocalReadResult.Available>(fixture.gateway.read(input.transferQuery())).page
            assertEquals(listOf(LocalStockRelationalQueriesV2.PROFILE, LocalStockStoreProfile.TRANSFER_DETAIL,
                LocalStockStoreProfile.QUANTITIES), fixture.master.reads.map { it.query })
            assertTrue(quantityPage.forms.isEmpty())
            val positions = quantityPage.records.filter { it.reference.kind == LocalRecordKind.STOCK_CUT }
            assertEquals(3, positions.size)
            val expectedRows = listOf(
                Triple(input.source.warehouseId, checkNotNull(input.source.lotId), input.expected.getValue("source_after").jsonObject.stockTransferText("quantity")),
                Triple(input.existingDestination.warehouseId, checkNotNull(input.existingDestination.lotId), input.destinationInput.stockTransferText("quantity")),
                Triple(input.destination.warehouseId, checkNotNull(input.destination.lotId), input.expected.getValue("moved").jsonObject.stockTransferText("quantity")),
            )
            for ((warehouse, lot, amount) in expectedRows) {
                val row = positions.single { it.displayValue(fieldLabel(LocalField.WAREHOUSE_ID)) == literal(warehouse.value) &&
                    it.displayValue(fieldLabel(LocalField.LOT_ID)) == literal(lot.value) }
                assertEquals(quantityText(input.quantity(amount)), row.displayValue("On hand"))
            }
            assertNoCostValues(quantityPage.records)
            assertEquals(original, fixture.journal.entries.getValue(input.operation))
            fixture.master.reads.clear()
            assertEquals(LocalServiceProblem.DENIED, assertIs<LocalReadResult.Unavailable>(
                fixture.gateway.read(input.transferQuery(LocalInventorySection.COSTS))).problem)
            assertEquals(listOf(LocalStockRelationalQueriesV2.PROFILE, LocalStockStoreProfile.TRANSFER_COSTS),
                fixture.master.reads.map { it.query })

            val costView = input.view(input.costActor, input.costDevice, input.grants(execute = false, costs = true))
            val costOwner = LocalClientIdentity(input.scope, input.costActor, input.costDevice)
            withStockTransferRpcV2Fixture(costOwner, costView) { costFixture ->
                costFixture.costReplyAllowed = true
                assertEquals(costOwner, costFixture.journal.owner)
                assertNotSame(fixture.journal, costFixture.journal)
                assertTrue(costFixture.journal.entries.isEmpty())
                val costs = assertIs<LocalReadResult.Available>(costFixture.gateway.read(input.transferQuery(LocalInventorySection.COSTS))).page
                assertEquals(listOf(LocalStockRelationalQueriesV2.PROFILE, LocalStockStoreProfile.TRANSFER_COSTS),
                    costFixture.master.reads.map { it.query })
                assertEquals(moneyText(Money(Currency.EGP, input.expected.getValue("moved").jsonObject.stockTransferText("carrying_value_minor").toLong())),
                    costs.records.single().displayValue("Value carried at transfer"))
                assertTrue(costs.forms.isEmpty())
                assertTrue(costFixture.journal.entries.isEmpty())
                assertTrue(costFixture.master.mutations.isEmpty())
                assertTrue(costFixture.master.lookups.isEmpty())
            }
            fixture.master.view = costView
            val lookupCount = fixture.master.lookups.size
            val ackCount = fixture.journal.ackCalls
            assertIs<LocalOperationResult.Unresolved>(fixture.gateway.lookup(input.operation))
            assertIs<LocalAcknowledgementResult.Unresolved>(fixture.gateway.acknowledgeRecorded(input.operation))
            assertEquals(lookupCount, fixture.master.lookups.size)
            assertEquals(ackCount, fixture.journal.ackCalls)
            assertEquals(original, fixture.journal.entries.getValue(input.operation))
            assertEquals(1, fixture.master.mutations.size)
            // S06-QUANTITY/COST/OTHERACTOR: separate scripted replies and visible owner binding.
            // No actual cost grant activation, revocation, sensitive cache or native custody pass.
        }
    }
}

private fun LocalRecord.displayValue(label: LocalText): LocalText = values.single { it.label == label }.value
private fun LocalRecord.displayValue(englishLabel: String): LocalText = values.single { it.label.english == englishLabel }.value

private fun assertNoCostValues(records: List<LocalRecord>) {
    val monetaryLabels = setOf("Value carried at transfer", "Linked cost adjustment", "Current reference value",
        "Valuation calculation", "Carrying value", "Original posted value movement", "Carrying-value movement")
    for (value in records.flatMap { it.values }) {
        assertFalse(value.label.english in monetaryLabels)
        assertFalse(value.value.english.contains(" EGP"))
        assertFalse(value.value.arabic.contains(" EGP"))
    }
}
