package com.ukkera.pos.app.gateway.independent.retailcustomerreceiptbatchv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.LocalClientIdentity
import com.ukkera.pos.database.ReadFailure
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Frozen shared page and input-boundary assertions over controlled original/head replies. */
class ReceiptBatchFrozenWorkflowSlicesTest {
    private val f = ReceiptBatchFrozen

    @Test fun rcbe008ProvedAbsenceShowsExplicitZeroRevisionAndCanStage() = runTest {
        val id = f.variant("RCBE-008/PROVED-NOT-RECORDED")
        val scenario = f.mainScenario("O0")
        val c = ReceiptBatchGateway(scenario)
        val head = c.page()
        val summary = head.records.single { it.reference.kind == LocalRecordKind.CUSTOMER_RECEIPT }
        assertFalse(summary.selectable, id)
        assertTrue(requireNotNull(summary.status).english.contains("No observation recorded"), id)
        assertTrue(summary.values.any { it.value.english == f.fixtures.obj("O0").text("expectedObservedRevision") }, id)
        val link = head.records.single { it.reference.kind == LocalRecordKind.CUSTOMER_RECEIPT_BATCH }
        assertEquals(scenario.batchReference(), link.reference, id)
        assertBatchForm(id, c.page(link.reference).forms.single(), scenario.portions)
        assertIs<LocalStageResult.Staged>(c.gateway.stage(f.intent(f.fixtures.obj("O0").obj("explicitInput"), link.reference)), id)
        val body = LocalRetailCustomerReceiptCodec.decode(c.journal.entries.values.single().request().payload)
        assertEquals(f.fixtures.obj("O0").text("expectedObservedRevision").toLong(), body.expectedHeadRevision.value, id)
        assertEquals(f.expected.array("canonicalPortionIds").map { it.jsonPrimitive.content }, body.portions.map { it.portionId.value }, id)
        body.portions.forEach { row -> assertExactQuantity(id, row.quantity, f.expected.obj("exactPortionQuantities").obj(row.portionId.value)) }
        assertEquals(scenario.original(), assertIs<LocalRetailCustomerReceiptReadResultV1.NotRecorded>(c.endpoint.head).original, id)
        assertTrue(c.endpoint.executions.isEmpty(), id)
    }

    @Test fun rcbe008UnavailableOriginalReplyCannotCreateAnEmptyHeadOrStage() = runTest {
        val id = f.variant("RCBE-008/ORIGINAL-MISSING")
        val c = ReceiptBatchGateway()
        c.endpoint.head = LocalRetailCustomerReceiptReadResultV1.NotAvailable
        val page = c.page()
        assertTrue(page.records.isEmpty(), id)
        assertTrue(page.forms.isEmpty(), id)
        assertTrue(requireNotNull(page.notice).english.contains("unavailable"), id)
        c.assertNoStage(id, c.gateway.stage(f.intent()))
    }

    @Test fun rcbe008CorruptReplyAndInconsistentHeadCannotStageOrBecomeZero() = runTest {
        listOf("ORIGINAL-CORRUPT", "INCONSISTENT-HEAD").forEach { variant ->
            val id = f.variant("RCBE-008/" + variant)
            val c = ReceiptBatchGateway()
            c.endpoint.rawHead = if (variant == "ORIGINAL-CORRUPT") StoredBytes("{".encodeToByteArray()) else {
                val encoded = f.wire.encodeToString(LocalRetailCustomerReceiptReadResultV1.serializer(), c.endpoint.head)
                val root = Json.parseToJsonElement(encoded).jsonObject
                StoredBytes(JsonObject(root.toMutableMap().also {
                    it["value"] = JsonObject(root.obj("value").toMutableMap().also { view -> view["eventCount"] = JsonPrimitive(0) })
                }).toString().encodeToByteArray())
            }
            assertIs<LocalReadResult.Unavailable>(c.gateway.read(LocalWorkflowQuery(LocalWorkflow.ORDERS,
                reference = c.scenario.headReference())), id)
            c.assertNoStage(id, c.gateway.stage(f.intent()))
        }
    }

    @Test fun rcbe009StaleOpenAndFreshHeadChangeBeforeStageKeepPinnedRevision() = runTest {
        val openId = f.variant("RCBE-009/STALE-OPEN")
        val open = ReceiptBatchGateway()
        val pinned = open.scenario.batchReference()
        open.endpoint.head = f.postCommitHead(open.scenario)
        assertIs<LocalReadResult.Unavailable>(open.gateway.read(LocalWorkflowQuery(LocalWorkflow.ORDERS, reference = pinned)), openId)
        assertEquals(f.fixtures.obj("staleVariants").text("pinnedRevision").toLong(), pinned.revision?.value, openId)
        val stageId = f.variant("RCBE-009/STALE-STAGE")
        val c = ReceiptBatchGateway()
        assertEquals(2, c.page(c.scenario.batchReference()).forms.single().version, stageId)
        val input = f.intent()
        c.endpoint.head = f.postCommitHead(c.scenario)
        c.assertNoStage(stageId, c.gateway.stage(input))
        assertEquals(f.fixtures.obj("staleVariants").text("pinnedRevision").toLong(),
            assertIs<LocalScalarValue.Integer>(input.values.single { it.field == LocalField.EXPECTED_CUSTOMER_RECEIPT_REVISION }.value).value, stageId)
    }

    @Test fun rcbe009FailedFreshReadAndMissingOriginalCannotReuseCachedEvidence() = runTest {
        listOf("FRESH-READ-FAILED", "ORIGINAL-MISSING").forEach { variant ->
            val id = f.variant("RCBE-009/" + variant)
            val c = ReceiptBatchGateway()
            c.page(c.scenario.batchReference())
            if (variant == "FRESH-READ-FAILED") c.endpoint.readFailure = ReadFailure.WRITER_NOT_QUALIFIED
            else c.endpoint.head = LocalRetailCustomerReceiptReadResultV1.NotAvailable
            c.assertNoStage(id, c.gateway.stage(f.intent()))
        }
    }

    @Test fun rcbe009FreshOriginalUnitAndScaleChangesRefuseInsteadOfRewritingInputs() = runTest {
        listOf("FRESH-UNIT-CHANGED", "FRESH-SCALE-CHANGED").forEach { variant ->
            val id = f.variant("RCBE-009/" + variant)
            val c = ReceiptBatchGateway()
            c.page(c.scenario.batchReference())
            val savedInput = f.intent()
            val old = assertIs<LocalRetailCustomerReceiptReadResultV1.Head>(c.endpoint.head).value
            val kg = f.main.obj("originalEvidence").array("portions").map { it.jsonObject }.single { it.text("baseUnit") == "kg" }.text("portionId")
            fun changed(row: LocalRetailCustomerReceiptPortionV1): LocalRetailCustomerReceiptPortionV1 = if (row.portionId.value != kg) row
                else row.copy(quantity = if (variant == "FRESH-UNIT-CHANGED") row.quantity.copy(unitId = f.unitId("unit"))
                    else row.quantity.copy(scale = 0))
            c.endpoint.head = LocalRetailCustomerReceiptReadResultV1.Head(old.copy(
                original = old.original.copy(portions = old.original.portions.map(::changed)),
                recordedPortions = old.recordedPortions.map(::changed)))
            c.assertNoStage(id, c.gateway.stage(savedInput))
            val originalKgInput = savedInput.lines.single { it.lineId.value == kg }
            assertEquals(f.unitId("kg"), assertIs<LocalScalarValue.Identifier>(originalKgInput.values.single { it.field == LocalField.UNIT }.value).value, id)
            assertEquals(3L, assertIs<LocalScalarValue.Integer>(originalKgInput.values.single { it.field == LocalField.QUANTITY_SCALE }.value).value, id)
        }
    }

    @Test fun rcbe010AllThreePagesPreserveReferencesReadonlyMetadataAndRemainingRows() = runTest {
        val c = ReceiptBatchGateway(f.paging())
        val expected = f.fixtures.obj("P33")
        val seen = mutableListOf<String>()
        var cursor: String? = null
        expected.array("expectedPages").forEachIndexed { index, element ->
            val id = f.variant("RCBE-010/" + listOf("PAGE-ONE", "PAGE-TWO", "PAGE-THREE")[index])
            val pageExpected = element.jsonObject
            val page = c.page(cursor = cursor)
            assertTrue(page.forms.isEmpty(), id)
            assertFalse(page.records.single { it.reference.kind == LocalRecordKind.CUSTOMER_RECEIPT }.selectable, id)
            val originals = page.records.filter { it.reference.kind == LocalRecordKind.CUSTOMER_RECEIPT_PORTION }
            assertEquals(pageExpected.array("originalIds").map { it.jsonPrimitive.content }, originals.map { it.reference.id.value }, id)
            assertEquals(expected.array("expectedPageSizes")[index].jsonPrimitive.int, originals.size, id)
            seen += originals.map { it.reference.id.value }
            val link = page.records.single { it.reference.kind == LocalRecordKind.CUSTOMER_RECEIPT_BATCH }
            val ref = pageExpected.obj("editorReference")
            assertEquals(ref.text("id"), link.reference.id.value, id)
            assertEquals(ref.text("parentId"), link.reference.parentId?.value, id)
            assertEquals(ref.text("revision").toLong(), link.reference.revision?.value, id)
            val editor = c.page(link.reference)
            assertEquals(pageExpected.array("originalIds").map { it.jsonPrimitive.content },
                editor.records.filter { it.reference.kind == LocalRecordKind.CUSTOMER_RECEIPT_PORTION }.map { it.reference.id.value }, id)
            val remaining = pageExpected.array("remainingPrefilledIds").map { it.jsonPrimitive.content }
            assertBatchForm(id, editor.forms.single(), remaining.map { value -> c.scenario.portions.single { it.id.value == value } })
            assertTrue(requireNotNull(editor.forms.single().explanation).english.contains("this page"), id)
            cursor = page.nextCursor
        }
        assertNull(cursor)
        assertEquals(expected.array("canonicalIds").map { it.jsonPrimitive.content }, seen)
        assertEquals(expected.int("expectedDistinctOriginalsSeen"), seen.distinct().size)
        assertEquals(0, c.journal.stageCalls)
        assertTrue(c.endpoint.executions.isEmpty())
    }

    @Test fun rcbe010FullyObservedPageSuppressesBothEditorsWithoutHidingOriginals() = runTest {
        val id = f.variant("RCBE-010/FULLY-OBSERVED-PAGE")
        val c = ReceiptBatchGateway(f.paging(fullyObserved = true))
        val head = c.page()
        assertEquals(f.fixtures.obj("P33").array("expectedPageSizes").first().jsonPrimitive.int,
            head.records.count { it.reference.kind == LocalRecordKind.CUSTOMER_RECEIPT_PORTION }, id)
        assertFalse(head.records.any { it.reference.kind == LocalRecordKind.CUSTOMER_RECEIPT_BATCH }, id)
        assertTrue(c.page(c.scenario.batchReference()).forms.isEmpty(), id)
        assertTrue(c.page(c.scenario.singleReference()).forms.isEmpty(), id)
        assertTrue(c.endpoint.executions.isEmpty(), id)
    }

    @Test fun rcbe011Event63OffersEditorsAndEvent64RefusesAnActualFreshStage() = runTest {
        listOf("EVENTS-63" to "E63", "EVENTS-64" to "E64").forEach { (variant, fixture) ->
            val id = f.variant("RCBE-011/" + variant)
            val scenario = f.eventCap(fixture)
            val c = ReceiptBatchGateway(scenario)
            val head = c.page()
            assertEquals(if (fixture == "E63") 1 else 0, head.records.count { it.reference.kind == LocalRecordKind.CUSTOMER_RECEIPT_BATCH }, id)
            assertEquals(if (fixture == "E63") 1 else 0, c.page(scenario.batchReference()).forms.size, id)
            assertEquals(if (fixture == "E63") 1 else 0, c.page(scenario.singleReference()).forms.size, id)
            assertEquals(1, head.records.count { it.reference.kind == LocalRecordKind.CUSTOMER_RECEIPT_PORTION }, id)
            val inspection = f.fixtures.obj("eventCapacityFixtures").obj(fixture).obj("authorizedInspection")
            listOf("latestEventOperationId", "explicitKnownHistoricalOperationId").forEach { coordinate ->
                val operation = inspection.text(coordinate)
                val reference = LocalRecordReference(LocalRecordKind.CUSTOMER_RECEIPT_EVENT, DomainId(operation), parentId = scenario.sale)
                val event = c.page(reference)
                assertEquals(operation, event.records.single { it.reference.kind == LocalRecordKind.CUSTOMER_RECEIPT_EVENT }.reference.id.value, id)
                val query = assertIs<LocalRetailCustomerReceiptQueryV1.Event>(c.endpoint.queries.last(), id)
                assertEquals(operation, query.operationId.value, id)
                assertEquals(LocalRetailCustomerReceiptReadAccessV1.RECORD, query.access, id)
            }
            if (fixture == "E64") {
                val input = f.fixtures.obj("eventCapacityFixtures").obj(fixture).obj("explicitInput")
                c.assertNoStage(id, c.gateway.stage(f.intent(input, scenario.batchReference())))
            }
        }
    }

    @Test fun rcbe011MissingCommandOrCurrentOwnerHintSuppressesEditors() = runTest {
        val noCommandId = f.variant("RCBE-011/NO-COMMAND-HINT")
        val noCommand = ReceiptBatchGateway(commandInstalled = false)
        assertFalse(noCommand.page().records.any { it.reference.kind == LocalRecordKind.CUSTOMER_RECEIPT_BATCH }, noCommandId)
        assertTrue(noCommand.page(noCommand.scenario.batchReference()).forms.isEmpty(), noCommandId)
        assertTrue(noCommand.page(noCommand.scenario.singleReference()).forms.isEmpty(), noCommandId)
        val noOwnerId = f.variant("RCBE-011/NO-CURRENT-OWNER-HINT")
        val other = DomainId(f.fixtures.obj("recoveryVariants").array("changedOriginalBodyCandidates").map { it.jsonObject }
            .single { it.text("variantId") == "REUSE-ID-CHANGED-ACTOR" }.text("changedTrustedActorId"))
        val journal = ReceiptBatchModelJournal(LocalClientIdentity(f.scope, other, f.device))
        val noOwner = ReceiptBatchGateway(journal = journal)
        noOwner.endpoint.actor = other
        assertFalse(noOwner.page().records.any { it.reference.kind == LocalRecordKind.CUSTOMER_RECEIPT_BATCH }, noOwnerId)
        assertTrue(noOwner.page(noOwner.scenario.batchReference()).forms.isEmpty(), noOwnerId)
        assertTrue(noOwner.page(noOwner.scenario.singleReference()).forms.isEmpty(), noOwnerId)
    }

    @Test fun rcbe012DocumentaryHeadAndEventReadsKeepReadonlyReturnLookupPurpose() = runTest {
        val id = f.variant("RCBE-012/RETURNS-DOCUMENTARY")
        val c = ReceiptBatchGateway(record = false, lookup = true)
        val page = c.page(workflow = LocalWorkflow.RETURNS)
        assertTrue(page.forms.isEmpty(), id)
        assertFalse(page.records.any { it.reference.kind == LocalRecordKind.CUSTOMER_RECEIPT_BATCH }, id)
        assertTrue(page.records.filter { it.reference.kind == LocalRecordKind.CUSTOMER_RECEIPT_PORTION }.none { it.selectable }, id)
        assertTrue(c.page(c.scenario.batchReference(), LocalWorkflow.RETURNS).forms.isEmpty(), id)
        assertTrue(c.page(c.scenario.singleReference(), LocalWorkflow.RETURNS).forms.isEmpty(), id)
        val operation = f.main.array("priorEvents").first().jsonObject.text("operationId")
        val ref = LocalRecordReference(LocalRecordKind.CUSTOMER_RECEIPT_EVENT, DomainId(operation), parentId = c.scenario.sale)
        assertTrue(c.page(ref, LocalWorkflow.RETURNS).forms.isEmpty(), id)
        assertTrue(c.endpoint.queries.all { query -> when (query) {
            is LocalRetailCustomerReceiptQueryV1.Head -> query.access == LocalRetailCustomerReceiptReadAccessV1.RETURN_LOOKUP
            is LocalRetailCustomerReceiptQueryV1.Event -> query.access == LocalRetailCustomerReceiptReadAccessV1.RETURN_LOOKUP
        } }, id)
        assertTrue(c.journal.entries.isEmpty(), id)
        assertTrue(c.endpoint.executions.isEmpty(), id)
    }

    @Test fun rcbe012OrdersAndCheckoutUseRecordPurpose() = runTest {
        listOf("ORDERS-RECORD" to LocalWorkflow.ORDERS, "CHECKOUT-RECORD" to LocalWorkflow.CHECKOUT).forEach { (variant, workflow) ->
            val id = f.variant("RCBE-012/" + variant)
            val c = ReceiptBatchGateway()
            assertEquals(1, c.page(workflow = workflow).records.count { it.reference.kind == LocalRecordKind.CUSTOMER_RECEIPT_BATCH }, id)
            assertEquals(LocalRetailCustomerReceiptReadAccessV1.RECORD,
                assertIs<LocalRetailCustomerReceiptQueryV1.Head>(c.endpoint.queries.single(), id).access, id)
        }
    }

    @Test fun rcbe012DeniedRecordDoesNotAutomaticallyRetryAllowedDocumentaryPurpose() = runTest {
        val id = f.variant("RCBE-012/DENIED-RECORD-LOOKUP-ALLOWED")
        val c = ReceiptBatchGateway()
        c.endpoint.denyRecordReads = true
        assertIs<LocalReadResult.Unavailable>(c.gateway.read(LocalWorkflowQuery(LocalWorkflow.ORDERS,
            reference = c.scenario.headReference())), id)
        assertEquals(1, c.endpoint.queries.size, id)
        assertEquals(LocalRetailCustomerReceiptReadAccessV1.RECORD,
            assertIs<LocalRetailCustomerReceiptQueryV1.Head>(c.endpoint.queries.single(), id).access, id)
        assertTrue(c.journal.entries.isEmpty(), id)
        assertTrue(c.endpoint.executions.isEmpty(), id)
        // A separately requested documentary read demonstrates that its port was available.
        assertTrue(c.page(workflow = LocalWorkflow.RETURNS).forms.isEmpty(), id)
        assertEquals(2, c.endpoint.queries.size, id)
        assertEquals(LocalRetailCustomerReceiptReadAccessV1.RETURN_LOOKUP,
            assertIs<LocalRetailCustomerReceiptQueryV1.Head>(c.endpoint.queries.last(), id).access, id)
    }

    @Test fun rcbe012ExplicitEventUsesItsExactOriginalIdAndSelectedPurpose() = runTest {
        val id = f.variant("RCBE-012/EXPLICIT-EVENT-PURPOSE")
        val operation = f.main.array("priorEvents").first().jsonObject.text("operationId")
        listOf(LocalWorkflow.ORDERS to LocalRetailCustomerReceiptReadAccessV1.RECORD,
            LocalWorkflow.RETURNS to LocalRetailCustomerReceiptReadAccessV1.RETURN_LOOKUP).forEach { (workflow, purpose) ->
            val c = ReceiptBatchGateway()
            val reference = LocalRecordReference(LocalRecordKind.CUSTOMER_RECEIPT_EVENT, DomainId(operation), parentId = c.scenario.sale)
            val page = c.page(reference, workflow)
            assertEquals(reference, page.records.single { it.reference.kind == LocalRecordKind.CUSTOMER_RECEIPT_EVENT }.reference, id)
            val query = assertIs<LocalRetailCustomerReceiptQueryV1.Event>(c.endpoint.queries.single(), id)
            assertEquals(operation, query.operationId.value, id)
            assertEquals(purpose, query.access, id)
            assertTrue(page.forms.isEmpty(), id)
        }
    }
}
