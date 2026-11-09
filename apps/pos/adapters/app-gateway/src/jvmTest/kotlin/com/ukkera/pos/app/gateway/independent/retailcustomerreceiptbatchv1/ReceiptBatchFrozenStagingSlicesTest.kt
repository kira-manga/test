package com.ukkera.pos.app.gateway.independent.retailcustomerreceiptbatchv1

import com.ukkera.pos.app.*
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptCodec
import com.ukkera.pos.types.DomainId
import java.math.BigDecimal
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Shared staging and journal-model slices; no production persistence or Master effects. */
class ReceiptBatchFrozenStagingSlicesTest {
    private val f = ReceiptBatchFrozen

    @Test fun rcbe001BothSelectionOrdersStageOneCompleteRequest() = runTest {
        listOf("ORDER-KG-FIRST" to f.i2.obj("input"), "ORDER-UNIT-FIRST" to f.i2.obj("alternateValidSelectionOrder")).forEach { (order, input) ->
            val id = f.variant("RCBE-001/" + order)
            val c = ReceiptBatchGateway()
            val link = c.page().records.single { it.reference.kind == LocalRecordKind.CUSTOMER_RECEIPT_BATCH }
            assertEquals(c.scenario.batchReference(), link.reference, id)
            val form = c.page(link.reference).forms.single()
            assertEquals(2, form.version, id)
            assertBatchForm(id, form, c.scenario.portions)
            val staged = assertIs<LocalStageResult.Staged>(c.gateway.stage(f.intent(input, link.reference)), id)
            assertEquals(f.expected.int("operationCount"), c.journal.entries.size, id)
            assertEquals(f.expected.int("requestCount"), c.journal.stageCalls, id)
            assertTrue(c.endpoint.executions.isEmpty(), id)
            assertFrozenRequest(id, c.journal.entries.values.single())
            val review = f.expected.obj("stagedReview")
            val rows = review.array("selectedRows").map { it.jsonObject }
            val originals = rows.map { ReceiptBatchOriginal(DomainId(it.text("portionId")), it.text("baseUnit"), it.int("quantityScale"), it.text("quantity")) }
            assertCompleteSummary(id, staged.operation.summary, originals, originals.associate { it.id to it.sold })
            assertSummaryRoots(id, staged.operation.summary, review)
        }
    }

    @Test fun rcbe003ConstructableRootMutationsInvokeGatewayAndDoNotStage() = runTest {
        val unavailableNames = setOf("EXTRA-ROOT", "FORGED-ACTOR", "FORGED-PRODUCER", "FORGED-POLICY")
        f.fixtures.array("rootMutations").map { it.jsonObject }.filter { it.text("variantId") !in unavailableNames }.forEach { mutation ->
            val id = f.variant("RCBE-003/" + mutation.text("variantId"))
            val c = ReceiptBatchGateway()
            val intent = f.intent(f.mutate(f.i2.obj("input"), mutation.obj("mutation")))
            c.assertNoStage(id, c.gateway.stage(intent))
        }
    }

    @Test fun rcbe004ConstructableLineMutationsInvokeGatewayAndDoNotStage() = runTest {
        val unavailableNames = setOf("EXTRA-LINE", "DUPLICATE-EQUAL", "DUPLICATE-UNEQUAL")
        f.fixtures.array("lineMutations").map { it.jsonObject }.filter { it.text("variantId") !in unavailableNames }.forEach { mutation ->
            val id = f.variant("RCBE-004/" + mutation.text("variantId"))
            val c = ReceiptBatchGateway()
            val intent = f.intent(f.mutate(f.i2.obj("input"), mutation.obj("mutation")))
            c.assertNoStage(id, c.gateway.stage(intent))
        }
    }

    @Test fun rcbe004DuplicateOriginalRowsAreConstructorRefusalsOnly() {
        f.fixtures.array("lineMutations").map { it.jsonObject }.filter { it.text("variantId").startsWith("DUPLICATE-") }.forEach { mutation ->
            val id = f.variant("RCBE-004/" + mutation.text("variantId"))
            val c = ReceiptBatchGateway()
            assertFailsWith<IllegalArgumentException>(id) {
                f.intent(f.mutate(f.i2.obj("input"), mutation.obj("mutation")))
            }
            assertEquals(0, c.journal.stageCalls, id)
            assertTrue(c.endpoint.executions.isEmpty(), id)
        }
    }

    @Test fun rcbe005ExactQuantitiesAndRefusalsUseTheGateway() = runTest {
        f.fixtures.array("quantityVariants").map { it.jsonObject }.forEach { variant ->
            val id = f.variant("RCBE-005/" + variant.text("variantId"))
            val c = ReceiptBatchGateway()
            val input = variant.obj("input")
            val result = c.gateway.stage(f.intent(input))
            val expected = variant.obj("expected")
            if (expected.int("stagedOperationCount") == 0) c.assertNoStage(id, result)
            else {
                val staged = assertIs<LocalStageResult.Staged>(result, id)
                assertEquals(1, c.journal.entries.size, id)
                assertTrue(c.endpoint.executions.isEmpty(), id)
                val portion = LocalRetailCustomerReceiptCodec.decode(c.journal.entries.values.single().request().payload).portions.single()
                val row = input.obj("logicalFields").array("LINES").single().jsonObject
                assertEquals(row.text("PORTION_ID"), portion.portionId.value, id)
                assertEquals(f.unitId(row.text("UNIT")), portion.quantity.unitId, id)
                assertEquals(row.text("QUANTITY_SCALE").toInt(), portion.quantity.scale, id)
                assertEquals(f.scaled(expected.text("preservedQuantity"), portion.quantity.scale), portion.quantity.scaled, id)
                val original = c.scenario.portions.single { it.id == portion.portionId }
                assertCompleteSummary(id, staged.operation.summary, listOf(original),
                    mapOf(original.id to expected.text("preservedQuantity")))
                val before = BigDecimal(c.scenario.cumulative.text(original.id.value))
                val entered = BigDecimal.valueOf(portion.quantity.scaled, portion.quantity.scale)
                assertEquals(BigDecimal(expected.text("independentProjectedCumulative")),
                    (before + entered).setScale(portion.quantity.scale), id)
                assertEquals(BigDecimal(expected.text("independentProjectedRemaining")),
                    (BigDecimal(original.sold) - before - entered).setScale(portion.quantity.scale), id)
            }
        }
    }

    @Test fun rcbe005NoncanonicalDecimalConstructorEvidenceIsSeparate() {
        val names = setOf("Q-NAN", "Q-INFINITY", "Q-EXPONENT", "Q-BLANK")
        f.fixtures.array("quantityVariants").map { it.jsonObject }.filter { it.text("variantId") in names }.forEach { variant ->
            val id = f.variant("RCBE-005/" + variant.text("variantId"))
            val text = variant.obj("input").obj("logicalFields").array("LINES").single().jsonObject.text("QUANTITY")
            assertFailsWith<IllegalArgumentException>(id) { LocalScalarValue.Decimal(text) }
        }
    }

    @Test fun rcbe006OneDirectOriginalStagesOneCommand() = runTest {
        val id = f.variant("RCBE-006/ONE")
        val scenario = f.directory("D32")
        val c = ReceiptBatchGateway(scenario)
        val input = f.directoryInput("D32", listOf(scenario.portions.first().id))
        assertIs<LocalStageResult.Staged>(c.gateway.stage(f.intent(input, null)), id)
        val body = LocalRetailCustomerReceiptCodec.decode(c.journal.entries.values.single().request().payload)
        assertEquals(listOf(scenario.portions.first().id), body.portions.map { it.portionId }, id)
        assertEquals(f.fixtures.obj("directoryFixtures").obj("D32").obj("recipe").text("directInputQuantityPerRow").toLong(),
            body.portions.single().quantity.scaled, id)
        assertEquals(1, c.journal.stageCalls, id)
        assertTrue(c.endpoint.executions.isEmpty(), id)
    }

    @Test fun rcbe006ThirtyTwoRowsSurviveCompleteReviewPendingAndStoredModelRoundTrip() = runTest {
        val id = f.variant("RCBE-006/THIRTY-TWO")
        val scenario = f.directory("D32")
        val recipe = f.fixtures.obj("directoryFixtures").obj("D32").obj("recipe")
        val c = ReceiptBatchGateway(scenario)
        val input = f.directoryInput("D32", scenario.portions.map { it.id }.reversed())
        val staged = assertIs<LocalStageResult.Staged>(c.gateway.stage(f.intent(input, null)), id)
        assertEquals(1, c.journal.stageCalls, id)
        assertEquals(1, c.journal.entries.size, id)
        val saved = c.journal.entries.values.single()
        val request = saved.request()
        assertEquals(f.expected.text("command"), request.command.name, id)
        assertEquals(f.expected.int("commandVersion"), request.command.version, id)
        val body = LocalRetailCustomerReceiptCodec.decode(request.payload)
        assertEquals(recipe.array("canonicalIds").map { it.jsonPrimitive.content }, body.portions.map { it.portionId.value }, id)
        assertEquals(recipe.int("originalCount"), body.portions.size, id)
        body.portions.forEach { row ->
            assertEquals(f.unitId(recipe.text("allBaseUnits")), row.quantity.unitId, id)
            assertEquals(recipe.int("allQuantityScales"), row.quantity.scale, id)
            assertEquals(f.scaled(recipe.text("directInputQuantityPerRow"), row.quantity.scale), row.quantity.scaled, id)
        }
        val quantities = scenario.portions.associate { it.id to recipe.text("directInputQuantityPerRow") }
        assertCompleteSummary(id, staged.operation.summary, scenario.portions, quantities)
        assertCompleteSummary(id, saved.summary.map { it.ui() }, scenario.portions, quantities)
        val roots = buildJsonObject {
            put("saleId", recipe.text("saleId")); put("expectedHeadRevision", recipe.text("observedHeadRevision"))
            put("receivedAt", recipe.text("explicitObservationTime")); put("receiptTimeZoneId", recipe.text("explicitObservationTimeZone"))
        }
        assertSummaryRoots(id, staged.operation.summary, roots)
        val restored = c.journal.modelRoundTrip()
        val rebound = restored.entries.values.single()
        assertEquals(saved, rebound, id)
        assertContentEquals(request.payload.copyBytes(), rebound.request().payload.copyBytes(), id)
        assertEquals(saved.identity(), rebound.identity(), id)
        val reopened = c.reopenedGateway(restored)
        val pending = assertIs<LocalPendingResult.Available>(reopened.pending(), id).operations.single()
        assertEquals(f.operation, pending.operationId, id)
        assertCompleteSummary(id, pending.summary, scenario.portions, quantities)
        val recovered = assertIs<LocalOperationResult.Pending>(reopened.lookup(f.operation), id)
        assertCompleteSummary(id, recovered.operation.summary, scenario.portions, quantities)
        assertTrue(c.endpoint.executions.isEmpty(), id)
        val editor = c.page(c.page().records.single { it.reference.kind == LocalRecordKind.CUSTOMER_RECEIPT_BATCH }.reference)
        assertEquals(f.fixtures.obj("capacityConstants").int("maxEditorOriginalsPerPage"),
            editor.forms.single().fields.filterIsInstance<LocalFormField.Lines>().single().initialLines.size, id)
    }

    @Test fun rcbe006ThirtyThreeAndZeroRowsInvokeGatewayAndRefuse() = runTest {
        listOf("THIRTY-THREE" to "D33", "ZERO" to "D32").forEach { (variant, fixture) ->
            val id = f.variant("RCBE-006/" + variant)
            val scenario = f.directory(fixture)
            val c = ReceiptBatchGateway(scenario)
            val rows = if (variant == "ZERO") emptyList() else scenario.portions.map { it.id }.reversed()
            c.assertNoStage(id, c.gateway.stage(f.intent(f.directoryInput(fixture, rows), null)))
        }
    }

    @Test fun rcbe006DuplicateAmongThirtyTwoIsConstructorEvidenceOnly() {
        val id = f.variant("RCBE-006/DUPLICATE-IN-THIRTY-TWO")
        val scenario = f.directory("D32")
        val ids = scenario.portions.map { it.id }
        assertFailsWith<IllegalArgumentException>(id) { f.intent(f.directoryInput("D32", ids.dropLast(1) + ids.first()), null) }
    }

    @Test fun rcbe007ExplicitTimeAndZoneRefusalsInvokeGateway() = runTest {
        f.fixtures.array("timeVariants").map { it.jsonObject }.filter { it.text("expected") == "NO_STAGE" }.forEach { variant ->
            val id = f.variant("RCBE-007/" + variant.text("variantId"))
            val c = ReceiptBatchGateway()
            c.assertNoStage(id, c.gateway.stage(f.intent(variant.obj("input"))))
        }
    }

    @Test fun rcbe013SingleVersionOneStagesAndRecoversItsOriginalBody() = runTest {
        val id = f.variant("RCBE-013/SINGLE-V1")
        val c = ReceiptBatchGateway()
        val single = f.fixtures.obj("S1").obj("logicalSinglePortion")
        val reference = c.scenario.singleReference(DomainId(single.text("portionId")))
        assertEquals(f.fixtures.obj("S1").int("applicationFormVersion"), c.page(reference).forms.single().version, id)
        assertIs<LocalStageResult.Staged>(c.gateway.stage(f.intent(f.singleInput(), reference)), id)
        val saved = c.journal.entries.values.single()
        val body = LocalRetailCustomerReceiptCodec.decode(saved.request().payload)
        assertEquals(f.fixtures.obj("S1").int("expectedCommandVersion"), saved.commandVersion, id)
        assertEquals(f.fixtures.obj("S1").int("expectedPortionCount"), body.portions.size, id)
        assertEquals(single.text("portionId"), body.portions.single().portionId.value, id)
        assertEquals(f.unitId(single.text("baseUnit")), body.portions.single().quantity.unitId, id)
        assertEquals(single.int("quantityScale"), body.portions.single().quantity.scale, id)
        assertEquals(f.scaled(single.text("quantity"), single.int("quantityScale")), body.portions.single().quantity.scaled, id)
        assertEquals(single.text("expectedHeadRevision").toLong(), body.expectedHeadRevision.value, id)
        assertEquals(f.instant(single.text("receivedAt")), body.receivedAt, id)
        assertEquals(single.text("receiptTimeZoneId"), body.receiptTimeZoneId, id)
        c.endpoint.head = f.postCommitHead(c.scenario)
        assertIs<LocalOperationResult.Pending>(c.reopenedGateway().lookup(f.operation), id)
        assertContentEquals(saved.request().payload.copyBytes(), c.journal.entries.values.single().request().payload.copyBytes(), id)
        assertTrue(c.endpoint.executions.isEmpty(), id)
    }

    @Test fun rcbe013BatchVersionTwoUsesOneVersionOneCommandAndTypedResult() = runTest {
        val id = f.variant("RCBE-013/BATCH-V2-COMMAND-V1")
        val c = ReceiptBatchGateway()
        val input = f.i2.obj("input")
        val form = c.page(c.scenario.batchReference()).forms.single()
        assertEquals(input.int("formVersion"), form.version, id)
        assertIs<LocalStageResult.Staged>(c.gateway.stage(f.intent(input)), id)
        val saved = c.journal.entries.values.single()
        assertEquals(input.int("formVersion"), saved.formVersion, id)
        assertEquals(f.expected.int("commandVersion"), saved.commandVersion, id)
        assertFrozenRequest(id, saved)
        assertEquals(f.expected.int("operationCount"), c.journal.entries.size, id)
        assertEquals(f.expected.int("requestCount"), c.journal.stageCalls, id)
        assertTrue(c.endpoint.executions.isEmpty(), id)
        // Consume the documented typed version1 payload through the actual gateway;
        // no absent serialized result-version field is invented.
        c.endpoint.executeReply = f.reply()
        val recorded = assertIs<LocalOperationResult.Recorded>(c.gateway.submit(f.operation), id)
        assertEquals(LocalRecordedDecision.RECORDED_ONLY, recorded.decision, id)
        assertEquals(f.operation, recorded.operationId, id)
        assertEquals(f.expected.int("requestCount"), c.endpoint.executions.size, id)
        val locator = requireNotNull(recorded.result).reference
        assertEquals(f.expected.obj("committedResult").text("operationId"), locator.id.value, id)
        assertEquals(f.expected.obj("committedResult").text("saleId"), locator.parentId?.value, id)
    }

    @Test fun rcbe013MixedLegacyAndBatchShapesInvokeGatewayAndRefuse() = runTest {
        val single = f.singleInput()
        val withLines = JsonObject(single.toMutableMap().also { root ->
            root["logicalFields"] = JsonObject(single.obj("logicalFields").toMutableMap().also {
                it["LINES"] = f.i2.obj("input").obj("logicalFields").getValue("LINES")
            })
        })
        val mixed = f.fixtures.array("rootMutations").map { it.jsonObject }.single { it.text("variantId") == "MIXED-SINGLE-BATCH" }
        listOf("SINGLE-WITH-LINES" to withLines,
            "BATCH-WITH-SINGLE-ROOT" to f.mutate(f.i2.obj("input"), mixed.obj("mutation"))).forEach { (variant, input) ->
            val id = f.variant("RCBE-013/" + variant)
            val c = ReceiptBatchGateway()
            val ref = if (input.int("formVersion") == 1) c.scenario.singleReference() else c.scenario.batchReference()
            c.assertNoStage(id, c.gateway.stage(f.intent(input, ref)))
        }
    }
}

internal fun assertSummaryRoots(id: String, summary: List<LocalRecordValue>, expected: JsonObject) {
    for (language in listOf(false, true)) {
        val values = summary.map { if (language) it.value.arabic else it.value.english }
        listOf("saleId", "expectedHeadRevision", "receivedAt", "receiptTimeZoneId").forEach {
            assertTrue(expected.text(it) in values, id + ": missing " + it)
        }
    }
}

internal fun assertBatchForm(id: String, form: LocalActionForm, originals: List<ReceiptBatchOriginal>) {
    val lines = form.fields.filterIsInstance<LocalFormField.Lines>().single()
    assertEquals(LocalField.LINES, lines.field, id)
    assertFalse(lines.allowBlankLines, id)
    assertFalse(lines.allowDuplicateLines, id)
    assertEquals(originals.map { it.id }, lines.initialLines.map { it.lineId }, id)
    listOf(LocalField.PORTION_ID, LocalField.UNIT, LocalField.QUANTITY_SCALE).forEach { field ->
        assertTrue(lines.columns.single { it.field == field }.readOnly, id)
    }
    assertNull(lines.columns.single { it.field == LocalField.QUANTITY }.initialValue, id)
    lines.initialLines.zip(originals).forEach { (line, original) ->
        assertFalse(line.values.any { it.field == LocalField.QUANTITY }, id)
        assertEquals(original.id, assertIs<LocalScalarValue.Identifier>(line.values.single { it.field == LocalField.PORTION_ID }.value, id).value, id)
        assertEquals(ReceiptBatchFrozen.unitId(original.unit), assertIs<LocalScalarValue.Identifier>(line.values.single { it.field == LocalField.UNIT }.value, id).value, id)
        assertEquals(original.scale.toLong(), assertIs<LocalScalarValue.Integer>(line.values.single { it.field == LocalField.QUANTITY_SCALE }.value, id).value, id)
    }
    val root = form.fields.filterIsInstance<LocalFormField.Scalar>()
    assertTrue(root.single { it.field == LocalField.SALE_ID }.readOnly, id)
    assertTrue(root.single { it.field == LocalField.EXPECTED_CUSTOMER_RECEIPT_REVISION }.readOnly, id)
    assertNull(root.single { it.field == LocalField.CUSTOMER_RECEIVED_AT }.initialValue, id)
    assertNull(root.single { it.field == LocalField.CUSTOMER_RECEIPT_TIME_ZONE }.initialValue, id)
}
