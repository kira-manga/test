@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.ukkera.pos.app.gateway.independent.returnstockdisposition

import com.ukkera.pos.app.*
import com.ukkera.pos.database.DeferredReason
import com.ukkera.pos.database.LookupReply
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

/** Supplemental RSD-v1 client slices, not executions of the whole frozen cases.
 * Native sources/value/cash, current Master authority, LAN, race, restart and installed
 * applications remain unbound. U-DISPOSITION/U-TRACKING/U-ALLOCATION are not resolved here. */
class ReturnStockPreparationSupportingTest {
    private val input = ReturnStockFrozenInputs

    @Test
    fun rsd001_rsd003_rsd008_rsd014_quantityOnlyDiscoveryDoesNotPostOrOfferPayout() = runTest {
        val fixture = ReturnStockGatewayFixture()
        val original = input.coreOriginal()
        val page = assertIs<LocalReadResult.Available>(fixture.gateway.read(fixture.query())).page
        assertEquals(listOf(LocalReturnStockQueryV1(caseId = input.caseId, returnId = input.returnId)), fixture.endpoint.selectors)
        assertEquals(listOf(input.postingPermission), input.sessionView().policy.grants.map { it.permission })
        assertEquals(listOf(LocalAction.RETURN_STOCK_POST), page.forms.map { it.action })
        assertTrue(page.records.all { !it.selectable })
        assertEquals(setOf(LocalRecordKind.RETURN_STOCK, LocalRecordKind.RETURN_STOCK_ALLOCATION), page.records.map { it.reference.kind }.toSet())
        val allocationRecord = page.records.single { it.reference.kind == LocalRecordKind.RETURN_STOCK_ALLOCATION }
        assertQuantityCell(allocationRecord, "Originally issued quantity", "4")
        assertQuantityCell(allocationRecord, "Already restored across original returns", "1")
        assertQuantityCell(allocationRecord, "Original allocation remaining ceiling", "3")
        assertQuantityCell(page.records.single { it.reference.kind == LocalRecordKind.RETURN_STOCK },
            "Stock quantity still pending for this acceptance", "2")
        val form = page.forms.single()
        val scalarFields = form.fields.filterIsInstance<LocalFormField.Scalar>()
        assertEquals(LocalScalarValue.Decimal("2"), scalarFields.single { it.field == LocalField.ACCEPTED_QUANTITY }.initialValue)
        assertEquals(LocalScalarValue.Decimal("0"), scalarFields.single { it.field == LocalField.EXPECTED_POSTED_QUANTITY }.initialValue)
        assertTrue(scalarFields.single { it.field == LocalField.UNIT }.readOnly)
        val lines = form.fields.filterIsInstance<LocalFormField.Lines>().single()
        assertTrue(lines.initialLines.all { row -> row.values.single { it.field == LocalField.QUANTITY }.value == LocalScalarValue.Decimal("0") })
        assertTrue(lines.initialLines.all { row -> row.values.none { it.field == LocalField.CUSTODY } })
        assertFalse(lines.allowBlankLines)
        assertFalse(lines.allowDuplicateLines)
        val forbidden = setOf(LocalField.AMOUNT, LocalField.UNIT_PRICE, LocalField.ORIGINAL_OWNED_VALUE,
            LocalField.PAYMENT_METHOD, LocalField.PAYMENT_ATTESTED, LocalField.TENDERED_AMOUNT)
        assertTrue(form.fields.none { it.field in forbidden })
        val publicFields = input.json.parseToJsonElement(fixture.endpoint.reads.single().payload.copyBytes().decodeToString()).jsonObject.keys
        assertTrue(publicFields.containsAll(setOf("caseId", "returnId")))
        assertTrue(setOf("saleId", "caseId", "returnId", "afterAllocationId", "limit").containsAll(publicFields))
        // Allocation remainder 3 covers original4/prior1; this acceptance still awaits only2.
        assertEquals(input.core(3), original.allocations.single().remainingQuantity)
        assertEquals(input.core(2), original.remainingQuantity)
        assertEquals(original, fixture.endpoint.source)
        assertNull(fixture.journal.entry)
        assertTrue(fixture.endpoint.executed.isEmpty())
        assertTrue(fixture.endpoint.lookedUp.isEmpty())
    }

    @Test
    fun rsd001_rsd003_explicitPartialSelectionStagesExactlyOneUnitNotEitherCeiling() = runTest {
        val fixture = ReturnStockGatewayFixture()
        val form = stockForm(fixture)
        val staged = assertIs<LocalStageResult.Staged>(fixture.gateway.stage(fixture.intent(form)))
        val saved = assertNotNull(fixture.journal.entry)
        val request = LocalReturnStockCodecV1.decode(saved.request().payload)
        assertEquals(listOf(LocalReturnStockSelectionV1(input.allocation, input.core(1), LocalReturnStockCustodyV1.RELEASED)), request.selections)
        assertEquals(input.core(2), request.acceptedQuantity)
        assertEquals(input.core(0), request.expectedPostedQuantity)
        assertEquals(input.sale, request.saleId)
        assertEquals(input.caseId, request.caseId)
        assertEquals(input.returnId, request.returnId)
        assertEquals(input.issue, request.issueId)
        assertEquals(input.restoration, request.restorationId)
        assertEquals(LocalCashEvidenceV1(input.evidence, input.occurredAt), request.inspectionEvidence)
        assertEquals(LocalReturnStockProfileV1.COMMAND, saved.request().command)
        assertEquals(input.owner, saved.owner.actorId)
        assertEquals(input.operation, saved.operationId)
        assertNull(saved.request().actionApprovalId)
        assertEquals(listOf(null, input.sale), fixture.endpoint.selectors.map { it.saleId })
        assertTrue(staged.operation.summary.any { it.value.english.contains(input.allocation.value) && it.value.english.contains("RELEASED") })
        val keys = input.json.parseToJsonElement(saved.request().payload.copyBytes().decodeToString()).jsonObject.keys
        assertTrue(keys.none { key -> listOf("refund", "payment", "cost", "price", "cash").any { it in key.lowercase() } })
        assertEquals(input.coreOriginal(), fixture.endpoint.source)
        assertEquals(1, fixture.journal.stageCalls)
        assertTrue(fixture.endpoint.executed.isEmpty())
        assertTrue(fixture.endpoint.lookedUp.isEmpty())
    }

    @Test
    fun rsd003_rsd006_frozenInvalidEachAndMeasuredQuantitiesCannotBeStaged() = runTest {
        val variants = listOf(
            input.coreOriginal() to listOf("0", "-1", "3", "9999999999999999999999999999999999999999"),
            input.measureOriginal() to listOf("0.00", "-0.01", "0.26", "0.251"))
        for ((source, quantities) in variants) for (quantity in quantities) {
            val fixture = ReturnStockGatewayFixture()
            fixture.endpoint.source = source
            assertIs<LocalStageResult.NotStaged>(fixture.gateway.stage(fixture.intent(stockForm(fixture), quantity)), quantity)
            assertNoStageOrDispatch(fixture)
            assertEquals(source, fixture.endpoint.source, quantity)
        }
        val measured = ReturnStockGatewayFixture()
        measured.endpoint.source = input.measureOriginal()
        assertIs<LocalStageResult.Staged>(measured.gateway.stage(measured.intent(stockForm(measured), "0.25")))
        val request = LocalReturnStockCodecV1.decode(assertNotNull(measured.journal.entry).request().payload)
        assertEquals(input.measure(25), request.selections.single().quantity)
        assertEquals(input.measure(25), request.acceptedQuantity)
        assertTrue(measured.endpoint.executed.isEmpty())
    }

    @Test
    fun rsd001_rsd003_rsd006_missingInspectionOrStaleSourceAssertionsCannotBeStaged() = runTest {
        for (missing in listOf(LocalField.EVIDENCE_ID, LocalField.OCCURRED_AT)) {
            val fixture = ReturnStockGatewayFixture()
            val intent = fixture.intent(stockForm(fixture))
            assertIs<LocalStageResult.NotStaged>(fixture.gateway.stage(intent.withValues(intent.values.filterNot { it.field == missing })))
            assertNoStageOrDispatch(fixture)
        }
        val changes = listOf(
            LocalField.UNIT to LocalScalarValue.Identifier(input.kilogram),
            LocalField.QUANTITY_SCALE to LocalScalarValue.Integer(2),
            LocalField.EXPECTED_CASE_REVISION to LocalScalarValue.Integer(4),
            LocalField.EXPECTED_DISPOSITION_REVISION to LocalScalarValue.Integer(1),
            LocalField.STOCK_ISSUE_ID to LocalScalarValue.Identifier(input.id(8130)),
            LocalField.EXPECTED_ISSUE_REVISION to LocalScalarValue.Integer(3),
            LocalField.EXPECTED_POSTED_QUANTITY to LocalScalarValue.Decimal("1"),
            LocalField.SALE_ID to LocalScalarValue.Identifier(input.id(8131)))
        for ((field, value) in changes) {
            val fixture = ReturnStockGatewayFixture()
            assertIs<LocalStageResult.NotStaged>(fixture.gateway.stage(fixture.intent(stockForm(fixture), values = mapOf(field to value))), field.name)
            assertNoStageOrDispatch(fixture)
        }
        val fixture = ReturnStockGatewayFixture()
        assertIs<LocalStageResult.NotStaged>(fixture.gateway.stage(fixture.intent(stockForm(fixture), custody = null)))
        assertNoStageOrDispatch(fixture)
    }

    @Test
    fun rsd003_rsd008_missingMismatchedOrNonconservingPublicProofCannotOpenAnEditor() = runTest {
        val original = input.coreOriginal()
        val variants = listOf<LocalReturnStockReadResultV1>(
            LocalReturnStockReadResultV1.NotAvailable,
            original.copy(caseId = input.id(8132)), original.copy(returnId = input.id(8133)),
            original.copy(remainingQuantity = input.core(3)),
            original.copy(allocations = original.allocations.map { it.copy(remainingQuantity = input.core(4)) }),
            original.copy(allocations = original.allocations.map { it.copy(originalQuantity = input.measure(75)) }))
        for (source in variants) {
            val fixture = ReturnStockGatewayFixture()
            fixture.endpoint.source = source
            when (val result = fixture.gateway.read(fixture.query())) {
                is LocalReadResult.Available -> assertTrue(result.page.forms.isEmpty() && result.page.records.isEmpty())
                is LocalReadResult.Unavailable -> Unit
            }
            assertNoStageOrDispatch(fixture)
        }
        // This tests only admission of a public projection, not its authoritative history joins.
    }

    @Test
    fun rsd010_rsd013_changedOriginalInputConflictsAndRetryNeverRebuildsFromCurrentRows() = runTest {
        val fixture = ReturnStockGatewayFixture()
        val form = stockForm(fixture)
        val intent = fixture.intent(form)
        assertIs<LocalStageResult.Staged>(fixture.gateway.stage(intent))
        val original = assertNotNull(fixture.journal.entry)
        val changes = listOf(fixture.intent(form, quantity = "2"),
            fixture.intent(form, custody = LocalReturnStockCustodyV1.QUARANTINE),
            fixture.intent(form, values = mapOf(LocalField.EVIDENCE_ID to LocalScalarValue.Identifier(input.id(8134)))),
            fixture.intent(form, lines = listOf(fixture.line(input.id(8135)))))
        for (changed in changes) {
            assertIs<LocalStageResult.Uncertain>(fixture.gateway.stage(changed))
            assertEquals(original, fixture.journal.entry)
        }
        val readCount = fixture.endpoint.reads.size
        fixture.endpoint.source = input.partialOriginal().copy(caseRevision = Revision(4), issueRevision = Revision(3))
        fixture.endpoint.onLookup = { LookupReply.Deferred(it.operationId, DeferredReason.HISTORY_INCOMPLETE) }
        assertIs<LocalOperationResult.Unresolved>(fixture.gateway.lookup(input.operation))
        fixture.endpoint.onLookup = { LookupReply.AbsentAtCurrentWriter(it.operationId) }
        fixture.endpoint.onExecute = { MutationReply.Deferred(it.operationId, DeferredReason.UNKNOWN_COMMAND) }
        val reopened = fixture.gateway() // A new coordinator; no native process/disk restart is claimed.
        assertIs<LocalOperationResult.Unresolved>(reopened.submit(input.operation))
        assertEquals(readCount, fixture.endpoint.reads.size)
        assertEquals(original.request().payload, fixture.endpoint.executed.single().payload)
        assertEquals(original.operationId, fixture.endpoint.executed.single().operationId)
        assertTrue(fixture.endpoint.lookedUp.all { it == original.identity() })
        assertEquals(original.identity(), assertNotNull(fixture.journal.entry).identity())
        assertEquals(1, fixture.journal.stageCalls)
        assertEquals(0, fixture.journal.rememberCalls)
        assertEquals(0, fixture.journal.acknowledgeCalls)
    }

    private suspend fun stockForm(fixture: ReturnStockGatewayFixture) =
        assertIs<LocalReadResult.Available>(fixture.gateway.read(fixture.query())).page.forms.single()

    private fun assertQuantityCell(record: LocalRecord, label: String, expected: String) {
        val value = record.values.single { it.label.english == label }.value.english
        assertEquals(expected, Regex("[0-9]+(?:\\.[0-9]+)?").find(value)?.value)
        assertTrue(value.contains(input.each.value))
    }

    private fun assertNoStageOrDispatch(fixture: ReturnStockGatewayFixture) {
        assertNull(fixture.journal.entry)
        assertEquals(0, fixture.journal.stageCalls)
        assertTrue(fixture.endpoint.executed.isEmpty())
        assertTrue(fixture.endpoint.lookedUp.isEmpty())
    }
}
