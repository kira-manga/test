package com.ukkera.pos.app.gateway.independent.supplierinvoicediscrepancyv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.catalog.store.LocalPurchasingCodec
import com.ukkera.pos.catalog.store.LocalPurchasingProfile
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.inventory.PurchaseUnitsV1
import com.ukkera.pos.security.LocalSessionViewResult
import com.ukkera.pos.types.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * SIRD-01/03 input-preservation/retry contributions and SIRD-02(c)'s shape boundary,
 * profile S only, against additive freeze 1.0 and context clarification 1.1.
 *
 * Current v2 ORDER_LINE:<id> selects a line in the supplied PO read. In contrast,
 * DOCUMENTARY_GOODS ALLOCATION_ID carries a documentary claim, not a validated
 * authoritative link or receipt/billing allocation. Keeping those same ID bytes
 * does not qualify SIRD-02(a)/(b)'s target/scope rejection or an approved mapping.
 *
 * The reused fixture has controlled DTO coordinates and memory-only session,
 * read, result and journal seams. No Master/Room/RPC, actual-item/dimension
 * qualification, independent persisted read, business-effect cut or native reopen
 * is exercised. A lost reply here follows a controlled Recorded result, not a
 * proven durable business commit. SIDC absent-allocation/no-PO gaps remain intact.
 */
class SupplierInvoiceReferencedGatewaySupportingTest {
    @Test
    fun sird01VersionTwoDocumentaryEntryPreservesBothDiscrepantTermSnapshots() = runTest(timeout = 5.seconds) {
        for (variant in listOf("DIFFERENT_ITEM_UNIT", "DIFFERENT_CONVERSION")) {
            val f = SupplierInvoiceConformingFixture()
            val form = f.currentForm()
            assertEquals(2, form.version)
            val columns = form.fields.filterIsInstance<LocalFormField.Lines>().single().columns
            val mode = columns.single { it.field == LocalField.MODE }
            assertTrue(mode.choices.any { it.code == "ORDER_LINE:" + f.orderLine.allocationId.value })
            assertTrue(mode.choices.any { it.code == "DOCUMENTARY_GOODS" })
            assertNull(mode.initialValue)
            val documentaryReference = columns.single { it.field == LocalField.ALLOCATION_ID }
            assertEquals(LocalInputKind.IDENTIFIER, documentaryReference.kind)
            assertFalse(documentaryReference.required)
            assertNull(documentaryReference.referenceSource)
            assertNull(documentaryReference.initialValue)

            val claim = SupplierInvoiceReferencedClaim(f, variant)
            val intent = claim.intent(form)
            val originalValues = intent.values
            val originalLineValues = intent.lines.single().values
            assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))

            claim.assertRequest(assertNotNull(f.journal.entry).request(), intent)
            assertEquals(2, assertNotNull(f.journal.entry).formVersion)
            assertEquals(originalValues, intent.values)
            assertEquals(originalLineValues, intent.lines.single().values)
            claim.assertOriginalPoCoordinates()
            f.assertNoDispatch()
        }
    }

    @Test
    fun sird02ZeroDenominatorCannotFallbackToTheSelectedPoConversion() = runTest(timeout = 5.seconds) {
        val f = SupplierInvoiceConformingFixture()
        val claim = SupplierInvoiceReferencedClaim(f, "DIFFERENT_ITEM_UNIT")
        val entered = claim.intent(f.currentForm())
        val invalidRate = claim.rate.substringBefore('/') + "/0"
        val invalid = replaceReferencedLineInput(entered, LocalField.PURCHASE_CONVERSION_RATE,
            LocalScalarValue.Text(invalidRate))
        val originalValues = invalid.lines.single().values

        val rejected = assertIs<LocalStageResult.NotStaged>(f.gateway.stage(invalid))
        assertEquals(LocalServiceProblem.INVALID_INPUT, rejected.problem)
        assertEquals(LocalField.PURCHASE_CONVERSION_RATE, rejected.field)
        assertNull(f.journal.entry)
        assertEquals(originalValues, invalid.lines.single().values)
        assertEquals(LocalScalarValue.Text(invalidRate), invalid.lines.single().values.single {
            it.field == LocalField.PURCHASE_CONVERSION_RATE
        }.value)
        claim.assertOriginalPoCoordinates()
        f.assertNoDispatch()
        // This is exact numeric-shape rejection, not catalog/dimension qualification.
    }

    @Test
    fun sird03LostReplyAndClientCodecRoundTripKeepTheReferenceAndActualTerms() = runTest(timeout = 5.seconds) {
        val f = SupplierInvoiceConformingFixture()
        val claim = SupplierInvoiceReferencedClaim(f, "DIFFERENT_ITEM_UNIT")
        val intent = claim.intent(f.currentForm())
        assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
        f.endpoint.loseNextReply = true
        assertIs<LocalOperationResult.Unresolved>(f.gateway.submit(intent.operationId))
        val originalEntry = assertNotNull(f.journal.entry)
        val originalReply = assertNotNull(f.endpoint.found)
        val restored = f.journal.codecRoundTrip()
        val gateway = f.newGateway(restored)
        val originalReadCount = f.endpoint.reads.size
        f.endpoint.rejectReads = true

        val recovered = assertIs<LocalOperationResult.Recorded>(gateway.submit(intent.operationId))
        f.assertOriginalResult(recovered, originalReply, assertNotNull(restored.entry))
        claim.assertRequest(assertNotNull(restored.entry).request(), intent)
        assertEquals(originalEntry.request().payload, assertNotNull(restored.entry).request().payload)
        assertEquals(originalEntry.owner, assertNotNull(restored.entry).owner)
        assertEquals(originalEntry.formVersion, assertNotNull(restored.entry).formVersion)
        assertEquals(originalEntry.identity(), f.endpoint.lookups.last())
        assertEquals(originalReadCount, f.endpoint.reads.size)
        assertEquals(1, f.endpoint.executions.size)
        claim.assertRequest(f.endpoint.executions.single(), intent)
        claim.assertOriginalPoCoordinates()
        // No persisted invoice/PO reopen or authoritative duplicate-effect outcome is asserted.
    }

    @Test
    fun sird03ChangedDocumentaryClaimOrTermsCannotReplaceAnOutstandingClientOriginal() = runTest(timeout = 5.seconds) {
        val f = SupplierInvoiceConformingFixture()
        val claim = SupplierInvoiceReferencedClaim(f, "DIFFERENT_ITEM_UNIT")
        val intent = claim.intent(f.currentForm())
        assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
        f.endpoint.loseNextReply = true
        assertIs<LocalOperationResult.Unresolved>(f.gateway.submit(intent.operationId))
        val originalEntry = assertNotNull(f.journal.entry)
        val originalReply = assertNotNull(f.endpoint.found)
        val originalReadCount = f.endpoint.reads.size
        val originalLookupCount = f.endpoint.lookups.size
        val originalStageCount = f.journal.stageCalls
        f.endpoint.rejectReads = true

        // This is another documentary ID value, not an independently bound valid REF-B.
        val mutations = listOf(
            replaceReferencedLineInput(intent, LocalField.ALLOCATION_ID, LocalScalarValue.Identifier(DomainId.random())),
            replaceReferencedLineInput(intent, LocalField.QUANTITY, LocalScalarValue.Decimal("5")),
            replaceReferencedLineInput(intent, LocalField.PURCHASE_CONVERSION_RATE, LocalScalarValue.Text("6/1")),
        )
        for (mutation in mutations) {
            assertEquals(intent.operationId, mutation.operationId)
            assertIs<LocalStageResult.Uncertain>(f.gateway.stage(mutation))
            assertEquals(originalEntry, f.journal.entry)
            assertEquals(originalReadCount, f.endpoint.reads.size)
            assertEquals(originalLookupCount, f.endpoint.lookups.size)
            assertEquals(originalStageCount, f.journal.stageCalls)
            assertEquals(originalReply, f.endpoint.found)
            assertEquals(1, f.endpoint.executions.size)
            claim.assertRequest(assertNotNull(f.journal.entry).request(), intent)
        }

        val recovered = assertIs<LocalOperationResult.Recorded>(f.gateway.submit(intent.operationId))
        f.assertOriginalResult(recovered, originalReply, assertNotNull(f.journal.entry))
        claim.assertRequest(assertNotNull(f.journal.entry).request(), intent)
        assertEquals(1, f.endpoint.executions.size)
        // Uncertain is the gateway's nonreplacement guard, not a proven writer semantic conflict.
    }

    @Test
    fun sird03SessionRefusalCannotLookUpOrEraseTheUnresolvedActualClaim() = runTest(timeout = 5.seconds) {
        val f = SupplierInvoiceConformingFixture()
        val claim = SupplierInvoiceReferencedClaim(f, "DIFFERENT_ITEM_UNIT")
        val intent = claim.intent(f.currentForm())
        assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
        f.endpoint.loseNextReply = true
        assertIs<LocalOperationResult.Unresolved>(f.gateway.submit(intent.operationId))
        val originalEntry = assertNotNull(f.journal.entry)
        val originalReply = assertNotNull(f.endpoint.found)
        val originalLookupCount = f.endpoint.lookups.size
        val originalReadCount = f.endpoint.reads.size

        for (refusal in listOf(LocalSessionViewResult.Denied,
            LocalSessionViewResult.Current(f.view(actor = DomainId.random())))) {
            f.endpoint.currentReply = refusal
            val refused = assertIs<LocalOperationResult.Unresolved>(f.gateway.lookup(intent.operationId))
            assertEquals(LocalServiceProblem.SESSION_ENDED, refused.problem)
            assertEquals(originalEntry, f.journal.entry)
            assertEquals(originalReply, f.endpoint.found)
            assertEquals(originalLookupCount, f.endpoint.lookups.size)
            assertEquals(originalReadCount, f.endpoint.reads.size)
            assertEquals(1, f.endpoint.executions.size)
            assertEquals(0, f.journal.rememberCalls)
        }

        f.endpoint.currentReply = null
        val recovered = assertIs<LocalOperationResult.Recorded>(f.gateway.lookup(intent.operationId))
        f.assertOriginalResult(recovered, originalReply, assertNotNull(f.journal.entry))
        claim.assertRequest(assertNotNull(f.journal.entry).request(), intent)
        assertEquals(originalEntry.owner, assertNotNull(f.journal.entry).owner)
        assertEquals(1, f.endpoint.executions.size)
        // No real activated revocation race or separately granted recovery-reader path is bound.
    }
}

/** Expected literals/numbers are read only after checking the separately frozen artifacts. */
private object SupplierInvoiceReferencedFreeze {
    private const val relativeDirectory = "qa/pos/designs/supplier-invoice-referenced-discrepancy-v1"
    private val directory: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .map { it.resolve(relativeDirectory) }
        .firstOrNull { Files.isRegularFile(it.resolve("fixtures.json")) }
        ?: error("Frozen referenced supplier invoice fixtures are unavailable")
    private val json = Json { ignoreUnknownKeys = false }
    private val fixture: JsonObject = run {
        val clarification = json.parseToJsonElement(Files.readString(directory.resolve(
            "context-identity-clarification-v1.1.json"))).jsonObject
        check(text(clarification, "version") == "1.1")
        val clarificationArtifact = clarification.getValue("artifact").jsonObject
        verify(text(clarificationArtifact, "path"), text(clarificationArtifact, "sha256"))
        val preserved = clarification.getValue("preserved_additive_v1").jsonObject
        verify(text(preserved, "path"), text(preserved, "sha256"))
        val manifest = json.parseToJsonElement(Files.readString(directory.resolve("source-manifest.json"))).jsonObject
        manifest.getValue("frozen_artifacts").jsonArray.forEach { artifact ->
            verify(text(artifact.jsonObject, "path"), text(artifact.jsonObject, "sha256"))
        }
        json.parseToJsonElement(Files.readString(directory.resolve("fixtures.json"))).jsonObject.also {
            check(text(it, "version") == "1.0")
        }
    }
    val original: JsonObject = fixture.getValue("ORIGINAL").jsonObject
    fun claim(variant: String): JsonObject = fixture.getValue(variant).jsonObject
    fun text(value: JsonObject, key: String): String = value.getValue(key).jsonPrimitive.content
    private fun verify(path: String, expected: String) {
        val observed = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(directory.resolve(path)))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        check(observed == expected) { "Frozen referenced supplier invoice artifact digest changed: $path" }
    }
}

/** Synthetic public DTO coordinates; actual SKU/unit authority and relationships are not inferred. */
private class SupplierInvoiceReferencedClaim(
    private val fixture: SupplierInvoiceConformingFixture,
    variant: String,
) {
    private val freeze = SupplierInvoiceReferencedFreeze
    private val terms = freeze.claim(variant)
    private val originalUnits = fixture.orderLine.units
    private val differentItem = freeze.text(terms, "item") != freeze.text(freeze.original, "item")
    private val sku = if (differentItem) DomainId.random() else fixture.orderLine.skuId
    private val product = if (differentItem) DomainId.random() else originalUnits.productId
    private val purchaseUnit = if (freeze.text(terms, "purchase_unit") == freeze.text(freeze.original, "purchase_unit"))
        originalUnits.purchaseUnitId else DomainId.random()
    private val conversion = DomainId.random()
    private val conversionRevision = Revision(47)
    val rate: String = freeze.text(terms.getValue("conversion").jsonObject, "numerator") + "/" +
        freeze.text(terms.getValue("conversion").jsonObject, "denominator")

    fun intent(form: LocalActionForm): LocalCommandIntent {
        val template = fixture.intent(form)
        val header = template.values.map { value ->
            when (value.field) {
                LocalField.EXTERNAL_REFERENCE -> value.copy(value = LocalScalarValue.Text(freeze.text(terms, "supplier_reference")))
                LocalField.BUSINESS_DATE, LocalField.DUE_DATE ->
                    value.copy(value = LocalScalarValue.Date(freeze.text(terms, "supplier_document_date")))
                else -> value
            }
        }
        val line = template.lines.single()
        val amounts = line.values.map { value ->
            when (value.field) {
                LocalField.MODE -> value.copy(value = LocalScalarValue.Choice("DOCUMENTARY_GOODS"))
                LocalField.QUANTITY -> value.copy(value = LocalScalarValue.Decimal(freeze.text(terms, "purchase_quantity")))
                LocalField.SUPPLIER_ITEM_REFERENCE -> value.copy(value = LocalScalarValue.Text(freeze.text(terms, "supplier_item_reference")))
                LocalField.UNIT_PRICE -> value.copy(value = LocalScalarValue.Decimal(freeze.text(terms, "unit_cost_egp")))
                LocalField.NET_AMOUNT -> value.copy(value = LocalScalarValue.Decimal(moneyDecimal(freeze.text(terms, "expected_minor"))))
                else -> value
            }
        }
        val documented = listOf(
            LocalScalarInput(LocalField.ALLOCATION_ID, LocalScalarValue.Identifier(fixture.orderLine.allocationId)),
            LocalScalarInput(LocalField.SKU_ID, LocalScalarValue.Identifier(sku)),
            LocalScalarInput(LocalField.PRODUCT_ID, LocalScalarValue.Identifier(product)),
            LocalScalarInput(LocalField.UNIT, LocalScalarValue.Identifier(purchaseUnit)),
            LocalScalarInput(LocalField.PURCHASE_BASE_UNIT, LocalScalarValue.Identifier(originalUnits.baseUnitId)),
            LocalScalarInput(LocalField.PURCHASE_BASE_SCALE, LocalScalarValue.Integer(0)),
            LocalScalarInput(LocalField.CONVERSION_ID, LocalScalarValue.Identifier(conversion)),
            LocalScalarInput(LocalField.PURCHASE_CONVERSION_REVISION, LocalScalarValue.Integer(conversionRevision.value)),
            LocalScalarInput(LocalField.PURCHASE_CONVERSION_RATE, LocalScalarValue.Text(rate)),
        )
        return LocalCommandIntent(template.operationId, template.action, template.formVersion, template.reference,
            header, template.lineField, listOf(LocalInputLine(line.lineId, amounts + documented)))
    }

    fun assertRequest(request: MutationRequest, intent: LocalCommandIntent) {
        assertEquals(intent.operationId, request.operationId)
        assertEquals(LocalPurchasingProfile.RECORD_INVOICE, request.command)
        assertNull(request.actionApprovalId)
        val capture = LocalPurchasingCodec.decodeInvoice(request.payload)
        val invoice = capture.invoice
        fun headerId(field: LocalField): DomainId = assertIs<LocalScalarValue.Identifier>(
            intent.values.single { it.field == field }.value).value
        assertEquals(headerId(LocalField.INVOICE_ID), invoice.id)
        assertEquals(headerId(LocalField.PURCHASE_ID), invoice.orderId)
        assertEquals(headerId(LocalField.SUPPLIER_ID), invoice.supplierId)
        assertEquals(headerId(LocalField.EVIDENCE_ID), invoice.sourceEvidenceId)
        assertEquals(Revision(assertIs<LocalScalarValue.Integer>(intent.values.single {
            it.field == LocalField.EXPECTED_ORDER_REVISION
        }.value).value), capture.expectedOrderRevision)
        assertEquals(freeze.text(terms, "supplier_reference"), invoice.reference)
        assertEquals(freeze.text(terms, "supplier_document_date"), invoice.invoiceDate.canonicalString())
        assertEquals(freeze.text(terms, "supplier_document_date"), invoice.dueDate.canonicalString())
        assertTrue(invoice.charges.isEmpty())
        val line = invoice.lines.single()
        assertEquals(intent.lines.single().lineId, line.lineId)
        assertEquals(fixture.orderLine.allocationId, line.allocationId)
        assertEquals(sku, line.goods.skuId)
        assertEquals(PurchaseUnitsV1(product, purchaseUnit, originalUnits.baseUnitId, 0, conversion,
            conversionRevision, UnitConversionRate.parseCanonical(rate)), line.goods.units)
        assertEquals(freeze.text(terms, "supplier_item_reference"), line.goods.supplierItemReference)
        assertEquals(freeze.text(terms, "purchase_quantity").toLong(), line.goods.quantity.scaled)
        assertEquals(0, line.goods.quantity.scale)
        assertEquals(purchaseUnit, line.goods.quantity.unitId)
        assertEquals(ExactDecimal.parse(freeze.text(terms, "unit_cost_egp")), line.goods.unitPrice.amount)
        assertEquals(freeze.text(terms, "expected_base_quantity").toLong(), line.goods.baseQuantity().scaled)
        assertEquals(originalUnits.baseUnitId, line.goods.baseQuantity().unitId)
        assertEquals(0, line.goods.baseQuantity().scale)
        assertEquals(freeze.text(terms, "expected_minor").toLong(), line.goods.net.minor)
        assertEquals(freeze.text(terms, "expected_minor").toLong(), invoice.total().minor)
        assertEquals(0L, line.goods.taxAmount.minor)
        assertEquals(PercentageRate.parse("0"), line.goods.discount)
        assertEquals(PercentageRate.parse("0"), line.goods.tax.rate)
        assertNotEquals(originalUnits.rate, line.goods.units.rate)
        if (differentItem) {
            assertNotEquals(fixture.orderLine.skuId, line.goods.skuId)
            assertNotEquals(originalUnits.productId, line.goods.units.productId)
            assertNotEquals(originalUnits.purchaseUnitId, line.goods.units.purchaseUnitId)
        } else {
            assertEquals(fixture.orderLine.skuId, line.goods.skuId)
            assertEquals(originalUnits.productId, line.goods.units.productId)
            assertEquals(originalUnits.purchaseUnitId, line.goods.units.purchaseUnitId)
        }
    }

    fun assertOriginalPoCoordinates() {
        assertEquals(originalUnits, fixture.orderLine.units)
        assertEquals(freeze.text(freeze.original, "purchase_quantity").toLong(), fixture.orderLine.originalQuantity.scaled)
        assertEquals(freeze.text(freeze.original, "expected_base_quantity").toLong(), fixture.orderLine.baseQuantity.scaled)
        val originalRate = freeze.original.getValue("conversion").jsonObject
        assertEquals(UnitConversionRate.ratio(freeze.text(originalRate, "numerator").toLong(),
            freeze.text(originalRate, "denominator").toLong()), fixture.orderLine.units.rate)
        // These are immutable read DTOs, not independently persisted allowance/ledger observations.
    }

    private fun moneyDecimal(minor: String): String {
        val value = minor.toLong()
        return (value / 100).toString() + "." + (value % 100).toString().padStart(2, '0')
    }
}

private fun replaceReferencedLineInput(intent: LocalCommandIntent, field: LocalField, value: LocalScalarValue): LocalCommandIntent {
    val line = intent.lines.single()
    check(line.values.count { it.field == field } == 1)
    return LocalCommandIntent(intent.operationId, intent.action, intent.formVersion, intent.reference, intent.values,
        intent.lineField, listOf(LocalInputLine(line.lineId, line.values.map {
            if (it.field == field) it.copy(value = value) else it
        })))
}
