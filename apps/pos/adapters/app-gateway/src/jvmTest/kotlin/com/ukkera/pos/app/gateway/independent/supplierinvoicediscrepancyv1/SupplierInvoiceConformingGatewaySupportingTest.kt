@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.gateway.independent.supplierinvoicediscrepancyv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * SIDC-08 and the frozen common original-result oracle, profile S only.
 *
 * The shared gateway, canonical command codec and client-entry codec are real.
 * Session, read, operation-result and journal replies are controlled. DURABLE
 * below is a journal seam outcome, not proof of a database/native commit.
 * No protected Master, Room, admitted RPC, genuine authentication, persisted
 * invoice/ledger observation, native restart or installed application is bound.
 *
 * Only BASE + CONFORMING is submitted. SIDC-01/02's absent allocation and no-PO
 * variants are not represented by these tests, and no allocation is manufactured
 * to turn their actual goods into a passing conforming case.
 */
class SupplierInvoiceConformingGatewaySupportingTest {
    @Test
    fun sidc08LegacyEntryPreservesTheExplicitlySelectedConformingTerms() = runTest(timeout = 5.seconds) {
        val f = SupplierInvoiceConformingFixture()
        val intent = f.intent(f.currentForm(), version = 1)
        val staged = assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))

        f.assertConformingRequest(assertNotNull(f.journal.entry).request(), intent)
        assertEquals(1, assertNotNull(f.journal.entry).formVersion)
        assertEquals(intent.operationId, staged.operation.operationId)
        assertEquals(LocalPendingPhase.STAGED, staged.operation.phase)
        assertNull(staged.operation.recordedDecision)
        assertFalse(staged.operation.summary.isEmpty())
        f.assertNoDispatch()
    }

    @Test
    fun sidc08VersionTwoEntryPreservesTheExplicitlySelectedConformingTerms() = runTest(timeout = 5.seconds) {
        val f = SupplierInvoiceConformingFixture()
        val form = f.currentForm()
        val intent = f.intent(form)
        val originalValues = intent.values
        val originalLines = intent.lines.single().values
        assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))

        f.assertConformingRequest(assertNotNull(f.journal.entry).request(), intent)
        assertEquals(form.version, assertNotNull(f.journal.entry).formVersion)
        assertEquals(originalValues, intent.values)
        assertEquals(originalLines, intent.lines.single().values)
        f.assertNoDispatch()
    }

    @Test
    fun sidc08CurrentFormDoesNotSilentlyChooseTheFirstPoLineOrItsAmounts() = runTest(timeout = 5.seconds) {
        val f = SupplierInvoiceConformingFixture()
        val form = f.currentForm()
        assertEquals(2, form.version)
        val lines = form.fields.filterIsInstance<LocalFormField.Lines>().single()
        val mode = lines.columns.single { it.field == LocalField.MODE }
        assertEquals(LocalInputKind.CHOICE, mode.kind)
        assertNull(mode.initialValue)
        assertTrue(lines.initialLines.isEmpty())
        assertTrue(mode.choices.any { it.code == "ORDER_LINE:" + f.orderLine.allocationId.value })
        for (field in listOf(LocalField.QUANTITY, LocalField.UNIT_PRICE, LocalField.NET_AMOUNT, LocalField.TAX_AMOUNT)) {
            assertNull(lines.columns.single { it.field == field }.initialValue)
        }

        val entered = f.intent(form)
        val omittedChoice = entered.lines.single().values.filterNot { it.field == LocalField.MODE }
        val noSelection = LocalCommandIntent(entered.operationId, entered.action, entered.formVersion, entered.reference,
            entered.values, entered.lineField, listOf(LocalInputLine(entered.lines.single().lineId, omittedChoice)))
        val rejected = assertIs<LocalStageResult.NotStaged>(f.gateway.stage(noSelection))
        assertEquals(LocalServiceProblem.INVALID_INPUT, rejected.problem)
        assertNull(f.journal.entry)
        assertEquals(omittedChoice, noSelection.lines.single().values)
        f.assertNoDispatch()
        // This proves explicit conforming selection only, not successful unallocated capture.
    }

    @Test
    fun sidc08UnsupportedFormVersionsCannotInventAConformingHistoryDefault() = runTest(timeout = 5.seconds) {
        for (version in listOf(3, 1000)) {
            val f = SupplierInvoiceConformingFixture()
            val intent = f.intent(f.currentForm(), version)
            val rejected = assertIs<LocalStageResult.NotStaged>(f.gateway.stage(intent))
            assertEquals(LocalServiceProblem.INVALID_INPUT, rejected.problem)
            assertNull(f.journal.entry)
            f.assertNoDispatch()
        }
    }

    @Test
    fun sidc08RetainedLegacyEntryKeepsItsVersionAndBodyWhenTheCurrentFormIsVersionTwo() = runTest(timeout = 5.seconds) {
        val f = SupplierInvoiceConformingFixture()
        val form = f.currentForm()
        val original = f.intent(form, version = 1)
        assertIs<LocalStageResult.Staged>(f.gateway.stage(original))
        val first = assertNotNull(f.journal.entry)
        val restored = f.journal.codecRoundTrip()
        val gateway = f.newGateway(restored)
        val readsBeforeRetry = f.endpoint.reads.size
        f.endpoint.rejectReads = true

        assertIs<LocalStageResult.Staged>(gateway.stage(original))
        assertEquals(readsBeforeRetry, f.endpoint.reads.size)
        assertEquals(first.request().payload, assertNotNull(restored.entry).request().payload)
        assertEquals(first.owner, assertNotNull(restored.entry).owner)
        assertEquals(1, assertNotNull(restored.entry).formVersion)
        assertEquals(0, restored.stageCalls)

        val differentVersion = f.intent(form, version = 2, operationId = original.operationId)
        assertIs<LocalStageResult.Uncertain>(gateway.stage(differentVersion))
        assertEquals(first.request().payload, assertNotNull(restored.entry).request().payload)
        assertEquals(1, assertNotNull(restored.entry).formVersion)
        assertTrue(f.endpoint.executions.isEmpty())
        assertTrue(f.endpoint.lookups.all { it == first.identity() })
        // Codec round trip is not a durable reopen or a schema-migration qualification.
    }

    @Test
    fun sidc08RecordedPresentationWaitsForTheClientResultRetentionOutcome() = runTest(timeout = 5.seconds) {
        val f = SupplierInvoiceConformingFixture()
        val intent = f.intent(f.currentForm())
        assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
        val releaseRetention = CompletableDeferred<Unit>()
        f.journal.retentionRelease = releaseRetention

        val submit = async { f.gateway.submit(intent.operationId) }
        runCurrent()
        assertTrue(f.journal.retentionEntered.isCompleted)
        assertFalse(submit.isCompleted)
        val awaitingRetention = assertNotNull(f.journal.entry)
        assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED, awaitingRetention.localPhase)
        assertNull(awaitingRetention.recorded)
        assertFalse(awaitingRetention.acknowledged)

        releaseRetention.complete(Unit)
        val returned = assertIs<LocalOperationResult.Recorded>(submit.await())
        val originalReply = assertNotNull(f.endpoint.found)
        f.assertOriginalResult(returned, originalReply, assertNotNull(f.journal.entry))
        assertEquals(1, f.endpoint.executions.size)
    }

    @Test
    fun sidc08FailedResultRetentionNeverBecomesRecordedOrAcknowledgedAndTheOriginalCanRecover() =
        runTest(timeout = 5.seconds) {
            for (version in listOf(1, 2)) for (failure in listOf(
                JournalWrite.NO_CAPACITY, JournalWrite.IDENTITY_CONFLICT, JournalWrite.UNAVAILABLE,
            )) {
                val f = SupplierInvoiceConformingFixture()
                val intent = f.intent(f.currentForm(), version)
                assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
                val originalPayload = assertNotNull(f.journal.entry).request().payload
                f.journal.retentionOutcome = failure

                val unresolved = assertIs<LocalOperationResult.Unresolved>(f.gateway.submit(intent.operationId))
                assertEquals(LocalServiceProblem.STORAGE_UNCERTAIN, unresolved.problem)
                val pending = assertNotNull(f.journal.entry)
                assertEquals(originalPayload, pending.request().payload)
                assertEquals(intent.operationId, pending.operationId)
                assertEquals(f.journal.owner.actorId, pending.owner.actorId)
                assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED, pending.localPhase)
                assertNull(pending.recorded)
                assertFalse(pending.acknowledged)
                assertIs<LocalAcknowledgementResult.Unresolved>(f.gateway.acknowledgeRecorded(intent.operationId))
                assertEquals(0, f.journal.acknowledgeCalls)

                f.journal.retentionOutcome = JournalWrite.DURABLE
                val recovered = assertIs<LocalOperationResult.Recorded>(f.gateway.lookup(intent.operationId))
                f.assertOriginalResult(recovered, assertNotNull(f.endpoint.found), assertNotNull(f.journal.entry))
                assertEquals(originalPayload, assertNotNull(f.journal.entry).request().payload)
                assertIs<LocalAcknowledgementResult.Acknowledged>(f.gateway.acknowledgeRecorded(intent.operationId))
                assertEquals(1, f.endpoint.executions.size)
            }
        }

    @Test
    fun sidc08LookupAlsoRequiresClientRetentionWithoutRedispatchingTheConformingClaim() = runTest(timeout = 5.seconds) {
        for (failure in listOf(JournalWrite.NO_CAPACITY, JournalWrite.IDENTITY_CONFLICT, JournalWrite.UNAVAILABLE)) {
            val f = SupplierInvoiceConformingFixture()
            val intent = f.intent(f.currentForm())
            assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
            f.endpoint.loseNextReply = true
            assertIs<LocalOperationResult.Unresolved>(f.gateway.submit(intent.operationId))
            val original = assertNotNull(f.journal.entry)
            f.journal.retentionOutcome = failure

            val unresolved = assertIs<LocalOperationResult.Unresolved>(f.gateway.lookup(intent.operationId))
            assertEquals(LocalServiceProblem.STORAGE_UNCERTAIN, unresolved.problem)
            assertEquals(original, f.journal.entry)
            assertIs<LocalAcknowledgementResult.Unresolved>(f.gateway.acknowledgeRecorded(intent.operationId))
            assertEquals(0, f.journal.acknowledgeCalls)
            assertEquals(1, f.endpoint.executions.size)

            f.journal.retentionOutcome = JournalWrite.DURABLE
            val recovered = assertIs<LocalOperationResult.Recorded>(f.gateway.lookup(intent.operationId))
            f.assertOriginalResult(recovered, assertNotNull(f.endpoint.found), assertNotNull(f.journal.entry))
            assertEquals(1, f.endpoint.executions.size)
        }
    }

    @Test
    fun sidc08LostReplyAndClientCodecRoundTripRecoverTheExactOriginalResult() = runTest(timeout = 5.seconds) {
        for (version in listOf(1, 2)) {
            val f = SupplierInvoiceConformingFixture()
            val intent = f.intent(f.currentForm(), version)
            assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
            f.endpoint.loseNextReply = true
            assertIs<LocalOperationResult.Unresolved>(f.gateway.submit(intent.operationId))
            val firstEntry = assertNotNull(f.journal.entry)
            val firstReply = assertNotNull(f.endpoint.found)
            val restored = f.journal.codecRoundTrip()
            val gateway = f.newGateway(restored)
            f.endpoint.rejectReads = true

            val recovered = assertIs<LocalOperationResult.Recorded>(gateway.submit(intent.operationId))
            f.assertOriginalResult(recovered, firstReply, assertNotNull(restored.entry))
            assertEquals(firstEntry.request().payload, assertNotNull(restored.entry).request().payload)
            assertEquals(firstEntry.formVersion, assertNotNull(restored.entry).formVersion)
            assertEquals(firstEntry.identity(), f.endpoint.lookups.last())
            assertEquals(1, f.endpoint.executions.size)
            assertIs<LocalAcknowledgementResult.Acknowledged>(gateway.acknowledgeRecorded(intent.operationId))
            assertTrue(assertNotNull(restored.entry).acknowledged)
            assertIs<LocalOperationResult.Recorded>(gateway.lookup(intent.operationId))
            assertEquals(1, f.endpoint.executions.size)
        }
    }

    @Test
    fun sidc08CurrentSessionRefusalPreservesTheUnresolvedConformingOriginal() = runTest(timeout = 5.seconds) {
        val f = SupplierInvoiceConformingFixture()
        val intent = f.intent(f.currentForm())
        assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
        val original = assertNotNull(f.journal.entry)
        f.endpoint.currentReply = LocalSessionViewResult.Denied

        val refused = assertIs<LocalOperationResult.Unresolved>(f.gateway.submit(intent.operationId))
        assertEquals(LocalServiceProblem.SESSION_ENDED, refused.problem)
        assertEquals(original, f.journal.entry)
        f.assertNoDispatch()

        f.endpoint.currentReply = null
        assertIs<LocalOperationResult.Recorded>(f.gateway.submit(intent.operationId))
        assertEquals(original.request().payload, assertNotNull(f.journal.entry).request().payload)
        assertEquals(original.owner, assertNotNull(f.journal.entry).owner)
        assertEquals(1, f.endpoint.executions.size)
        // A controlled current-session refusal is not a Master revocation/commit race.
    }

    @Test
    fun sidc08DifferentCurrentActorCannotAdoptOrReadTheClientOriginal() = runTest(timeout = 5.seconds) {
        val f = SupplierInvoiceConformingFixture()
        val intent = f.intent(f.currentForm())
        assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
        val original = assertNotNull(f.journal.entry)
        f.endpoint.currentReply = LocalSessionViewResult.Current(f.view(actor = DomainId.random()))

        val refused = assertIs<LocalOperationResult.Unresolved>(f.gateway.lookup(intent.operationId))
        assertEquals(LocalServiceProblem.SESSION_ENDED, refused.problem)
        assertEquals(original, f.journal.entry)
        assertEquals(0, f.journal.rememberCalls)
        f.assertNoDispatch()
    }
}

/** Loads the unchanged independent numeric and literal expectations, not application output. */
private object SupplierInvoiceConformingFreeze {
    private const val relativeDirectory = "qa/pos/designs/supplier-invoice-discrepancy-capture-v1"
    private val directory: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .map { it.resolve(relativeDirectory) }
        .firstOrNull { Files.isRegularFile(it.resolve("fixtures.json")) }
        ?: error("Frozen supplier invoice discrepancy fixtures are unavailable")
    val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val fixture: JsonObject = run {
        val bytes = Files.readAllBytes(directory.resolve("fixtures.json"))
        val manifest = json.parseToJsonElement(Files.readString(directory.resolve("source-manifest.json"))).jsonObject
        val expected = manifest.getValue("frozen_artifacts").jsonArray.single {
            it.jsonObject.getValue("path").jsonPrimitive.content == "fixtures.json"
        }.jsonObject.getValue("sha256").jsonPrimitive.content
        val observed = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        check(observed == expected) { "Frozen supplier invoice fixture digest changed" }
        json.parseToJsonElement(bytes.decodeToString()).jsonObject.also {
            check(it.getValue("version").jsonPrimitive.content == "1.0")
        }
    }
    val baseOrder: JsonObject = fixture.getValue("BASE").jsonObject.getValue("original_po").jsonObject
    val conforming: JsonObject = fixture.getValue("CONFORMING").jsonObject
    val line: JsonObject = conforming.getValue("lines").jsonArray.single().jsonObject
    val common: JsonObject = fixture.getValue("claim_common").jsonObject
    fun text(value: JsonObject, key: String): String = value.getValue(key).jsonPrimitive.content
    fun <T> body(value: T, serializer: KSerializer<T>): StoredBytes =
        StoredBytes(json.encodeToString(serializer, value).encodeToByteArray())
}

/** Public DTO coordinates only; these are not authoritative runtime fixture bindings. */
internal class SupplierInvoiceConformingFixture {
    private val freeze = SupplierInvoiceConformingFreeze
    val scope = BranchScope(DomainId.random(), DomainId.random(), DomainId.random())
    private val actor = DomainId.random()
    private val device = DomainId.random()
    private val session = DomainId.random()
    private val connection = DomainId.random()
    private val clock = DomainId.random()
    private val supplier = DomainId.random()
    private val order = DomainId.random()
    private val sku = DomainId.random()
    private val product = DomainId.random()
    private val purchaseUnit = DomainId.random()
    private val baseUnit = DomainId.random()
    private val conversion = DomainId.random()
    private val sourceEvidence = DomainId.random()
    private val taxSource = DomainId.random()
    private val lineId = DomainId.random()
    private val operationId = OperationId.random()
    private val recordedAt = UtcInstant.parseCanonical("2026-11-05T12:00:00Z")
    private val orderRevision = Revision(17)
    private val supplierDomainRevision = Revision(8)
    private val supplierStorageRevision = Revision(9)
    private val units = PurchaseUnitsV1(product, purchaseUnit, baseUnit, 0, conversion, Revision(11),
        UnitConversionRate.ratio(freeze.text(freeze.baseOrder.getValue("conversion").jsonObject, "numerator").toLong(),
            freeze.text(freeze.baseOrder.getValue("conversion").jsonObject, "denominator").toLong()))
    val orderLine = LocalPurchasingLineViewV1(lineId, DomainId.random(), sku, null, null, units,
        Quantity.parse(purchaseUnit, freeze.text(freeze.baseOrder, "purchase_quantity"), 0),
        Quantity.parse(baseUnit, freeze.text(freeze.baseOrder, "expected_base_quantity"), 0),
        Quantity(baseUnit, 0, 0), Quantity(baseUnit, 0, 0), Quantity(baseUnit, 0, 0), Quantity(baseUnit, 0, 0),
        null, null, null, null, null, null, null)
    private val orderView = LocalPurchasingDocumentViewV1(order, orderRevision, supplier, order, "ORDER_APPROVED",
        BusinessDate.parseCanonical(freeze.text(freeze.common, "supplier_document_date")), null, null, null,
        listOf(orderLine), emptyList(), null, null, null, null, null, null, null, null)
    val journal = SupplierInvoiceConformingJournal(LocalClientIdentity(scope, actor, device))
    val endpoint = SupplierInvoiceConformingEndpoint(this)
    private val installed = LocalWorkflowInstallation(setOf(LocalPurchasingProfile.RECORD_INVOICE),
        setOf(LocalPurchasingProfile.READ, LocalConfigurationProfile.QUERY))
    val gateway = newGateway()

    fun view(actor: DomainId = this.actor): LocalSessionView = LocalSessionView(actor, scope, device, session,
        SessionGeneration(1), LocalLoginIdentifier("Synthetic supplier invoice capture"),
        ActivatedLocalPolicy(scope, actor, Revision(23), listOf(PermissionGrant(
            checkNotNull(actionPermission(LocalAction.SUPPLIER_INVOICE_RECORD)), scope, GrantEffect.ALLOW,
            PermissionLimits(maximumAmount = Money(Currency.EGP, 100000)))),
            LocalSessionPolicy(), ThrottlePolicy()), MonotonicMoment(clock, 1), MonotonicMoment(clock, 2))

    fun newGateway(selectedJournal: SupplierInvoiceConformingJournal = journal): LocalEndpointWorkflowGateway =
        LocalEndpointWorkflowGateway(endpoint, selectedJournal, LocalWorkflowCredentialSource {
            LocalWorkflowCredentialLease(LocalGatewayCredentials(connection, OpaqueCredential(ByteArray(16)))) { true }
        }, installed)

    suspend fun currentForm(): LocalActionForm {
        val page = assertIs<LocalReadResult.Available>(gateway.read(LocalWorkflowQuery(LocalWorkflow.PURCHASING,
            reference = LocalRecordReference(LocalRecordKind.PURCHASE, order),
            purchasing = LocalPurchasingUiQuery(LocalPurchasingSection.ORDERS)))).page
        return page.forms.single { it.action == LocalAction.SUPPLIER_INVOICE_RECORD }
    }

    fun intent(form: LocalActionForm, version: Int = form.version, operationId: OperationId = this.operationId): LocalCommandIntent {
        val header = form.fields.filterIsInstance<LocalFormField.Scalar>().map { field ->
            LocalScalarInput(field.field, field.initialValue ?: when (field.field) {
                LocalField.EXTERNAL_REFERENCE -> LocalScalarValue.Text(freeze.text(freeze.conforming, "supplier_reference"))
                LocalField.BUSINESS_DATE, LocalField.DUE_DATE ->
                    LocalScalarValue.Date(freeze.text(freeze.common, "supplier_document_date"))
                LocalField.EVIDENCE_ID -> LocalScalarValue.Identifier(sourceEvidence)
                LocalField.REASON -> LocalScalarValue.Text("supplier.invoice.capture")
                else -> error("Unbound conforming header input: " + field.field)
            })
        }
        val selected = if (version == 1) listOf(
            LocalScalarInput(LocalField.MODE, LocalScalarValue.Choice("GOODS")),
            LocalScalarInput(LocalField.ALLOCATION_ID, LocalScalarValue.Reference(
                LocalRecordReference(LocalRecordKind.PURCHASE_LINE, orderLine.allocationId, parentId = order))),
        ) else listOf(LocalScalarInput(LocalField.MODE,
            LocalScalarValue.Choice("ORDER_LINE:" + orderLine.allocationId.value)))
        val goods = listOf(
            LocalScalarInput(LocalField.QUANTITY, LocalScalarValue.Decimal(freeze.text(freeze.line, "purchase_quantity"))),
            LocalScalarInput(LocalField.QUANTITY_SCALE, LocalScalarValue.Integer(0)),
            LocalScalarInput(LocalField.SUPPLIER_ITEM_REFERENCE, LocalScalarValue.Text(freeze.text(freeze.line, "supplier_item_reference"))),
            LocalScalarInput(LocalField.UNIT_PRICE, LocalScalarValue.Decimal(freeze.text(freeze.line, "unit_cost_egp"))),
            LocalScalarInput(LocalField.DISCOUNT_PERCENT, LocalScalarValue.Decimal("0")),
            LocalScalarInput(LocalField.TAX_SOURCE_ID, LocalScalarValue.Identifier(taxSource)),
            LocalScalarInput(LocalField.TAX_REVISION, LocalScalarValue.Integer(13)),
            LocalScalarInput(LocalField.TAX_CATEGORY, LocalScalarValue.Text("SYNTHETIC_ZERO")),
            LocalScalarInput(LocalField.TAX_RATE, LocalScalarValue.Decimal("0")),
            LocalScalarInput(LocalField.NET_AMOUNT, LocalScalarValue.Decimal(moneyDecimal(freeze.text(freeze.line, "expected_line_minor")))),
            LocalScalarInput(LocalField.TAX_AMOUNT, LocalScalarValue.Decimal(moneyDecimal(freeze.text(freeze.common, "purchase_tax_minor")))),
        )
        return LocalCommandIntent(operationId, form.action, version, LocalRecordReference(LocalRecordKind.PURCHASE, order),
            header, LocalField.LINES, listOf(LocalInputLine(lineId, selected + goods)))
    }

    fun purchasingReply(request: ReadRequest): ReadReply.Data {
        val query = freeze.json.decodeFromString(LocalPurchasingQueryV1.serializer(), request.payload.copyBytes().decodeToString())
        check(query.section == LocalPurchasingSectionV1.ORDER && query.id == order && !query.includeCosts)
        return ReadReply.Data(freeze.body(LocalPurchasingPageV1(query.section, listOf(orderView), null, false),
            LocalPurchasingPageV1.serializer()), Revision(31), recordedAt)
    }

    fun supplierReply(request: ReadRequest): ReadReply.Data {
        val query = LocalConfigurationCodec.decodeQuery(request.payload)
        check(query.kind == LocalConfigurationProfile.SUPPLIER && query.id == supplier)
        val names = LocalNamesV1("Synthetic supplier S1", "")
        val value = LocalConfigurationPageV1(LocalConfigurationProfile.SUPPLIER, listOf(
            LocalConfigurationListItemV1(supplier, supplierDomainRevision, supplierStorageRevision, names, true, null)),
            null, detail = LocalConfigurationDetailV1.Supplier(LocalSupplierV1(supplier, names, true)))
        return ReadReply.Data(freeze.body(value, LocalConfigurationPageV1.serializer()), Revision(31), recordedAt)
    }

    fun recorded(request: MutationRequest, replayed: Boolean = false): MutationReply.Recorded {
        val invoice = LocalPurchasingCodec.decodeInvoice(request.payload).invoice
        val body = LocalPurchasingResultV1("purchasing.invoice_recorded", invoice.id, Revision(37), Revision(41))
        return MutationReply.Recorded(request.operationId, journal.owner.actorId, true, body.code,
            freeze.body(body, LocalPurchasingResultV1.serializer()), Revision(29), JournalSequence(43), recordedAt, replayed)
    }

    fun assertConformingRequest(request: MutationRequest, intent: LocalCommandIntent) {
        assertEquals(intent.operationId, request.operationId)
        assertEquals(LocalPurchasingProfile.RECORD_INVOICE, request.command)
        assertNull(request.actionApprovalId)
        val capture = LocalPurchasingCodec.decodeInvoice(request.payload)
        val invoice = capture.invoice
        val expectedInvoiceId = assertIs<LocalScalarValue.Identifier>(intent.values.single {
            it.field == LocalField.INVOICE_ID
        }.value).value
        assertEquals(expectedInvoiceId, invoice.id)
        assertEquals(order, invoice.orderId)
        assertEquals(supplier, invoice.supplierId)
        assertEquals(orderRevision, capture.expectedOrderRevision)
        assertEquals(supplierStorageRevision, capture.expectedSupplierRevision)
        assertEquals(freeze.text(freeze.conforming, "supplier_reference"), invoice.reference)
        assertEquals(freeze.text(freeze.common, "supplier_document_date"), invoice.invoiceDate.canonicalString())
        assertEquals(sourceEvidence, invoice.sourceEvidenceId)
        assertTrue(invoice.charges.isEmpty())
        val line = invoice.lines.single()
        assertEquals(intent.lines.single().lineId, line.lineId)
        assertEquals(orderLine.allocationId, line.allocationId)
        assertEquals(orderLine.skuId, line.goods.skuId)
        assertEquals(orderLine.units, line.goods.units)
        assertEquals(freeze.text(freeze.line, "supplier_item_reference"), line.goods.supplierItemReference)
        assertEquals(freeze.text(freeze.line, "purchase_quantity").toLong(), line.goods.quantity.scaled)
        assertEquals(0, line.goods.quantity.scale)
        assertEquals(purchaseUnit, line.goods.quantity.unitId)
        assertEquals(ExactDecimal.parse(freeze.text(freeze.line, "unit_cost_egp")), line.goods.unitPrice.amount)
        assertEquals(freeze.text(freeze.line, "expected_base_quantity").toLong(), line.goods.baseQuantity().scaled)
        assertEquals(baseUnit, line.goods.baseQuantity().unitId)
        assertEquals(freeze.text(freeze.line, "expected_line_minor").toLong(), line.goods.net.minor)
        assertEquals(freeze.text(freeze.common, "purchase_tax_minor").toLong(), line.goods.taxAmount.minor)
        assertEquals(freeze.text(freeze.conforming, "expected_invoice_minor").toLong(), invoice.total().minor)
    }

    fun assertOriginalResult(returned: LocalOperationResult.Recorded, reply: MutationReply.Recorded, entry: StoredClientIntentV1) {
        val body = LocalPurchasingCodec.result(reply.result)
        assertEquals(reply.operationId, returned.operationId)
        assertEquals(LocalAction.SUPPLIER_INVOICE_RECORD, returned.originalAction)
        assertEquals(body.id, assertNotNull(returned.result).reference.id)
        assertEquals(LocalRecordKind.PURCHASE_INVOICE, assertNotNull(returned.result).reference.kind)
        assertEquals(body.revision?.value.toString(), assertNotNull(returned.result).values.single {
            it.label == fieldLabel(LocalField.EXPECTED_STORAGE_REVISION)
        }.value.english)
        assertEquals(body.progressRevision?.value.toString(), assertNotNull(returned.result).values.single {
            it.label == fieldLabel(LocalField.EXPECTED_PROGRESS_REVISION)
        }.value.english)
        assertEquals(StoredKernelResultV1.from(reply), entry.recorded)
        assertEquals(reply.originalActorId, entry.owner.actorId)
        assertEquals(LocalPendingPhase.RECORDED_UNACKNOWLEDGED, entry.localPhase)
    }

    fun assertNoDispatch() {
        assertTrue(endpoint.executions.isEmpty())
        assertTrue(endpoint.lookups.isEmpty())
    }

    private fun moneyDecimal(minor: String): String {
        val whole = minor.toLong()
        return (whole / 100).toString() + "." + (whole % 100).toString().padStart(2, '0')
    }
}

/** Controlled read/result port: never a protected writer, store or transport. */
internal class SupplierInvoiceConformingEndpoint(private val fixture: SupplierInvoiceConformingFixture) : LocalMasterClient {
    override val health: StateFlow<LocalMasterEndpointHealth> get() = error("No endpoint health fixture")
    var currentReply: LocalSessionViewResult? = null
    var rejectReads = false
    var loseNextReply = false
    var found: MutationReply.Recorded? = null
    val reads = mutableListOf<ReadRequest>()
    val lookups = mutableListOf<OperationIdentity>()
    val executions = mutableListOf<MutationRequest>()
    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult =
        currentReply ?: LocalSessionViewResult.Current(fixture.view())
    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        check(!rejectReads) { "Retained supplier invoice operation must not be rebuilt through fresh reads" }
        reads += request
        return when (request.query) {
            LocalPurchasingProfile.READ -> fixture.purchasingReply(request)
            LocalConfigurationProfile.QUERY -> fixture.supplierReply(request)
            else -> error("Unbound supplier invoice read")
        }
    }
    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        check(request.command == LocalPurchasingProfile.RECORD_INVOICE)
        executions += request
        val reply = fixture.recorded(request)
        found = reply
        if (loseNextReply) {
            loseNextReply = false
            return MutationReply.Deferred(request.operationId, DeferredReason.STORAGE_UNCERTAIN)
        }
        return reply
    }
    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply {
        lookups += identity
        return found?.let { reply ->
            check(reply.operationId == identity.operationId && reply.originalActorId == identity.originalActorId)
            LookupReply.Found(MutationReply.Recorded(reply.operationId, reply.originalActorId, reply.committed,
                reply.code, reply.result, reply.authorityEpoch, reply.sequence, reply.recordedAt, true))
        } ?: LookupReply.AbsentAtCurrentWriter(identity.operationId)
    }
    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult = error("No authentication fixture")
    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult = error("No refresh fixture")
    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult = error("No logout fixture")
}

/** Memory-only client seam with explicit retention outcomes and a real entry-codec round trip. */
internal class SupplierInvoiceConformingJournal(owner: LocalClientIdentity) : LocalClientIntentJournal(owner) {
    var entry: StoredClientIntentV1? = null
    var stageCalls = 0
    var rememberCalls = 0
    var acknowledgeCalls = 0
    var retentionOutcome = JournalWrite.DURABLE
    val retentionEntered = CompletableDeferred<Unit>()
    var retentionRelease: CompletableDeferred<Unit>? = null
    override suspend fun find(operationId: OperationId): JournalRead<StoredClientIntentV1?> =
        JournalRead.Value(entry?.takeIf { it.operationId == operationId })
    override suspend fun pending(): JournalRead<List<StoredClientIntentV1>> =
        JournalRead.Value(listOfNotNull(entry?.takeUnless { it.acknowledged }))
    override suspend fun stage(intent: StoredClientIntentV1): JournalWrite {
        stageCalls++
        intent.validate(owner)
        check(entry == null)
        entry = intent
        return JournalWrite.DURABLE
    }
    override suspend fun markDispatched(operationId: OperationId): JournalWrite {
        val old = entry?.takeIf { it.operationId == operationId } ?: return JournalWrite.UNAVAILABLE
        entry = old.copy(phase = LocalPendingPhase.DISPATCH_UNRESOLVED.name)
        return JournalWrite.DURABLE
    }
    override suspend fun remember(operationId: OperationId, result: StoredKernelResultV1): JournalWrite {
        rememberCalls++
        retentionEntered.complete(Unit)
        retentionRelease?.await()
        if (retentionOutcome != JournalWrite.DURABLE) return retentionOutcome
        val old = entry?.takeIf { it.operationId == operationId } ?: return JournalWrite.UNAVAILABLE
        if (old.recorded != null && old.recorded != result) return JournalWrite.IDENTITY_CONFLICT
        entry = old.copy(phase = LocalPendingPhase.RECORDED_UNACKNOWLEDGED.name, recorded = result)
        return JournalWrite.DURABLE
    }
    override suspend fun acknowledge(operationId: OperationId, result: StoredKernelResultV1): JournalWrite {
        acknowledgeCalls++
        val old = entry?.takeIf { it.operationId == operationId } ?: return JournalWrite.UNAVAILABLE
        if (old.recorded != result) return JournalWrite.IDENTITY_CONFLICT
        entry = old.copy(acknowledged = true)
        return JournalWrite.DURABLE
    }
    fun codecRoundTrip(): SupplierInvoiceConformingJournal = SupplierInvoiceConformingJournal(owner).also { restored ->
        entry?.let { original ->
            val bytes = journalJson.encodeToString(StoredClientIntentV1.serializer(), original)
            restored.entry = journalJson.decodeFromString(StoredClientIntentV1.serializer(), bytes).also { it.validate(owner) }
        }
    }
    override suspend fun close() = Unit
}
