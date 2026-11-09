package com.ukkera.pos.app.gateway.independent.supplierinvoicecapturev2

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Controlled session/read/result coordinates and memory-only journal outcomes.
 * The gateway and command/entry codecs are production code; this is never a Master,
 * durable store, authenticated transport, authoritative fixture setup or native reopen.
 */
internal class SupplierInvoiceCaptureFixture {
    private val frozen = FrozenSupplierInvoiceCaptureInputs
    val scope = BranchScope(DomainId.random(), DomainId.random(), DomainId.random())
    val actorId = DomainId.random()
    private val deviceId = DomainId.random()
    private val sessionId = DomainId.random()
    private val connectionId = DomainId.random()
    private val clockId = DomainId.random()
    val supplierId = DomainId.random()
    val supplierDomainRevision = Revision(8)
    val supplierStorageRevision = Revision(9)
    val orderId = DomainId.random()
    val orderRevision = Revision(17)
    val originalAllocationId = DomainId.random()
    private val warehouseId = DomainId.random()
    private val originalLineId = DomainId.random()
    private val sourceEvidenceId = DomainId.random()
    private val taxSourceId = DomainId.random()
    val policyRevision = Revision(23)
    val recordedAt = UtcInstant.parseCanonical("2026-11-05T12:00:00Z")
    private val skuIds = listOf("A", "B", "C").associateWith { DomainId.random() }
    private val productIds = listOf("A", "B", "C").associateWith { DomainId.random() }
    private val unitIds = listOf("EA", "G", "CARTON", "TRAY", "KG").associateWith { DomainId.random() }
    private val conversionIds = mutableMapOf<String, DomainId>()
    val originalGoods = goods(frozen.originalLine)
    val journal = ControlledInvoiceCaptureJournal(LocalClientIdentity(scope, actorId, deviceId))
    val endpoint = ControlledInvoiceCaptureEndpoint(this)
    private val installed = LocalWorkflowInstallation(
        setOf(LocalPurchasingProfile.CAPTURE_INVOICE, LocalPurchasingProfile.RECORD_INVOICE),
        setOf(LocalPurchasingProfile.READ, LocalPurchasingProfile.READ_INVOICES, LocalConfigurationProfile.QUERY))
    val gateway = newGateway()

    fun view(actor: DomainId = actorId, capture: Boolean = true, approve: Boolean = false): LocalSessionView {
        val grants = buildList {
            if (capture) add(PermissionGrant(Permission(SecurityAction.PURCHASING_INVOICE_CAPTURE,
                PermissionVerb.PERFORM), scope, GrantEffect.ALLOW))
            if (approve) add(PermissionGrant(Permission(SecurityAction.PURCHASING_APPROVE,
                PermissionVerb.PERFORM), scope, GrantEffect.ALLOW,
                PermissionLimits(maximumAmount = Money(Currency.EGP, 100000))))
            add(PermissionGrant(Permission(SecurityAction.INVENTORY_COST_READ, PermissionVerb.PERFORM), scope, GrantEffect.ALLOW))
        }
        return LocalSessionView(actor, scope, deviceId, sessionId, SessionGeneration(1),
            LocalLoginIdentifier("Synthetic supplier invoice capture"),
            ActivatedLocalPolicy(scope, actor, policyRevision, grants, LocalSessionPolicy(), ThrottlePolicy()),
            MonotonicMoment(clockId, 1), MonotonicMoment(clockId, 2))
    }

    fun newGateway(selectedJournal: ControlledInvoiceCaptureJournal = journal,
        selectedInstallation: LocalWorkflowInstallation = installed): LocalEndpointWorkflowGateway =
        LocalEndpointWorkflowGateway(endpoint, selectedJournal, LocalWorkflowCredentialSource {
            LocalWorkflowCredentialLease(LocalGatewayCredentials(connectionId, OpaqueCredential(ByteArray(16)))) { true }
        }, selectedInstallation)

    suspend fun currentForm(fromOrder: Boolean = false): LocalActionForm =
        currentPage(fromOrder).forms.single { it.action == LocalAction.SUPPLIER_INVOICE_CAPTURE }

    suspend fun currentPage(fromOrder: Boolean = false): LocalWorkflowPage = assertIs<LocalReadResult.Available>(
        gateway.read(LocalWorkflowQuery(LocalWorkflow.PURCHASING,
            reference = if (fromOrder) LocalRecordReference(LocalRecordKind.PURCHASE, orderId) else null,
            purchasing = LocalPurchasingUiQuery(if (fromOrder) LocalPurchasingSection.ORDERS
                else LocalPurchasingSection.INVOICES)))).page

    suspend fun capturedProjectionPage(invoiceId: DomainId, costs: Boolean): LocalWorkflowPage =
        assertIs<LocalReadResult.Available>(gateway.read(LocalWorkflowQuery(LocalWorkflow.PURCHASING,
            reference = LocalRecordReference(LocalRecordKind.PURCHASE_INVOICE, invoiceId),
            purchasing = LocalPurchasingUiQuery(LocalPurchasingSection.INVOICES, includeCosts = costs)))).page

    suspend fun captureDirectoryProjectionPage(costs: Boolean): LocalWorkflowPage =
        assertIs<LocalReadResult.Available>(gateway.read(LocalWorkflowQuery(LocalWorkflow.PURCHASING,
            purchasing = LocalPurchasingUiQuery(LocalPurchasingSection.INVOICES, includeCosts = costs)))).page

    fun intent(form: LocalActionForm, claim: FrozenInvoiceCaptureClaim,
        mode: String = "DOCUMENTARY_GOODS", supplierOrderReference: String? = null,
        supplierAllocationReferences: List<String?> = List(claim.lines.size) { null },
        operationId: OperationId = OperationId.random()): LocalCommandIntent {
        require(supplierAllocationReferences.size == claim.lines.size)
        val headers = form.fields.filterIsInstance<LocalFormField.Scalar>().mapNotNull { field ->
            val value = when (field.field) {
                LocalField.INVOICE_ID -> assertNotNull(field.initialValue)
                LocalField.SUPPLIER_ID -> field.initialValue ?: LocalScalarValue.Reference(
                    LocalRecordReference(LocalRecordKind.SUPPLIER, supplierId, supplierDomainRevision))
                LocalField.PURCHASE_ID, LocalField.EXPECTED_ORDER_REVISION -> field.initialValue
                LocalField.SUPPLIER_ORDER_REFERENCE -> supplierOrderReference?.let { LocalScalarValue.Text(it) }
                LocalField.EXTERNAL_REFERENCE -> LocalScalarValue.Text(claim.reference)
                LocalField.BUSINESS_DATE, LocalField.DUE_DATE -> LocalScalarValue.Date(claim.date)
                LocalField.EVIDENCE_ID -> LocalScalarValue.Identifier(sourceEvidenceId)
                LocalField.REASON -> LocalScalarValue.Text("supplier.invoice.capture")
                else -> error("Unbound capture header: ${field.field}")
            }
            value?.let { LocalScalarInput(field.field, it) }
        }
        val rows = claim.lines.mapIndexed { index, expected ->
            val actual = goods(expected)
            val values = mutableListOf(LocalScalarInput(LocalField.MODE, LocalScalarValue.Choice(mode)))
            if (!mode.startsWith("ORDER_LINE:")) values += listOf(
                LocalScalarInput(LocalField.SKU_ID, LocalScalarValue.Identifier(actual.skuId)),
                LocalScalarInput(LocalField.PRODUCT_ID, LocalScalarValue.Identifier(actual.units.productId)),
                LocalScalarInput(LocalField.UNIT, LocalScalarValue.Identifier(actual.units.purchaseUnitId)),
                LocalScalarInput(LocalField.PURCHASE_BASE_UNIT, LocalScalarValue.Identifier(actual.units.baseUnitId)),
                LocalScalarInput(LocalField.PURCHASE_BASE_SCALE, LocalScalarValue.Integer(actual.units.baseScale.toLong())),
                LocalScalarInput(LocalField.CONVERSION_ID, LocalScalarValue.Identifier(assertNotNull(actual.units.conversionId))),
                LocalScalarInput(LocalField.PURCHASE_CONVERSION_REVISION,
                    LocalScalarValue.Integer(assertNotNull(actual.units.conversionRevision).value)),
                LocalScalarInput(LocalField.PURCHASE_CONVERSION_RATE, LocalScalarValue.Text(expected.rate)),
            )
            supplierAllocationReferences[index]?.let {
                values += LocalScalarInput(LocalField.SUPPLIER_ALLOCATION_REFERENCE, LocalScalarValue.Text(it))
            }
            values += listOf(
                LocalScalarInput(LocalField.QUANTITY, LocalScalarValue.Decimal(expected.purchaseQuantity)),
                LocalScalarInput(LocalField.QUANTITY_SCALE, LocalScalarValue.Integer(expected.purchaseScale.toLong())),
                LocalScalarInput(LocalField.SUPPLIER_ITEM_REFERENCE, LocalScalarValue.Text(expected.supplierItemReference)),
                LocalScalarInput(LocalField.UNIT_PRICE, LocalScalarValue.Decimal(expected.unitPrice)),
                LocalScalarInput(LocalField.DISCOUNT_PERCENT, LocalScalarValue.Decimal("0")),
                LocalScalarInput(LocalField.TAX_SOURCE_ID, LocalScalarValue.Identifier(taxSourceId)),
                LocalScalarInput(LocalField.TAX_REVISION, LocalScalarValue.Integer(13)),
                LocalScalarInput(LocalField.TAX_CATEGORY, LocalScalarValue.Text("SYNTHETIC_ZERO")),
                LocalScalarInput(LocalField.TAX_RATE, LocalScalarValue.Decimal("0")),
                LocalScalarInput(LocalField.NET_AMOUNT, LocalScalarValue.Decimal(frozen.moneyDecimal(expected.minor))),
                LocalScalarInput(LocalField.TAX_AMOUNT, LocalScalarValue.Decimal(frozen.moneyDecimal(frozen.purchaseTaxMinor))),
            )
            LocalInputLine(DomainId.random(), values)
        }
        val fromOrder = headers.any { it.field == LocalField.PURCHASE_ID }
        return LocalCommandIntent(operationId, form.action, form.version,
            if (fromOrder) LocalRecordReference(LocalRecordKind.PURCHASE, orderId) else null,
            headers, LocalField.LINES, rows)
    }

    fun goods(expected: FrozenInvoiceCaptureLine): PurchaseGoodsV1 {
        val conversion = conversionIds.getOrPut("${expected.item}/${expected.purchaseUnit}/${expected.rate}") { DomainId.random() }
        val units = PurchaseUnitsV1(productIds.getValue(expected.item), unitIds.getValue(expected.purchaseUnit),
            unitIds.getValue(expected.baseUnit), 0, conversion,
            if (expected == frozen.originalLine) Revision(11) else Revision(47),
            UnitConversionRate.parseCanonical(expected.rate))
        return PurchaseGoodsV1(skuIds.getValue(expected.item), expected.supplierItemReference, units,
            Quantity.parse(units.purchaseUnitId, expected.purchaseQuantity, expected.purchaseScale),
            UnitPrice.parse(Currency.EGP, expected.unitPrice), PercentageRate.parse("0"),
            PurchaseTaxV1(taxSourceId, Revision(13), "SYNTHETIC_ZERO", PercentageRate.parse("0")),
            Money(Currency.EGP, expected.minor), Money(Currency.EGP, frozen.purchaseTaxMinor))
    }

    fun assertRequest(request: MutationRequest, intent: LocalCommandIntent, claim: FrozenInvoiceCaptureClaim,
        provenance: PurchaseInvoiceEntryProvenanceV2 = PurchaseInvoiceEntryProvenanceV2.DOCUMENTARY,
        internalAllocationId: DomainId? = null) {
        assertEquals(intent.operationId, request.operationId)
        assertEquals(LocalPurchasingProfile.CAPTURE_INVOICE, request.command)
        assertNull(request.actionApprovalId)
        val command = LocalPurchasingCodec.decodeCaptureInvoice(request.payload)
        assertEquals(supplierStorageRevision, command.expectedSupplierRevision)
        assertEquals("supplier.invoice.capture", command.reasonCode)
        val invoice = command.invoice
        assertEquals(headerId(intent, LocalField.INVOICE_ID), invoice.id)
        assertEquals(supplierId, invoice.supplierId)
        assertEquals(sourceEvidenceId, invoice.sourceEvidenceId)
        assertEquals(claim.reference, invoice.reference)
        assertEquals(claim.date, invoice.invoiceDate.canonicalString())
        assertEquals(claim.date, invoice.dueDate.canonicalString())
        assertEquals(headerText(intent, LocalField.SUPPLIER_ORDER_REFERENCE), invoice.supplierOrderReference)
        if (intent.values.any { it.field == LocalField.PURCHASE_ID })
            assertEquals(PurchaseInvoiceOrderLinkV2(orderId, orderRevision), invoice.internalOrderLink)
        else assertNull(invoice.internalOrderLink)
        assertTrue(invoice.charges.isEmpty())
        assertEquals(claim.lines.size, invoice.lines.size)
        for (index in claim.lines.indices) {
            val line = invoice.lines[index]
            val expected = claim.lines[index]
            assertEquals(intent.lines[index].lineId, line.lineId)
            assertEquals(provenance, line.provenance)
            assertEquals(internalAllocationId, line.internalAllocationId)
            assertEquals(lineText(intent, index, LocalField.SUPPLIER_ALLOCATION_REFERENCE), line.supplierAllocationReference)
            assertEquals(goods(expected), line.goods)
            assertEquals(Quantity.parse(line.goods.units.baseUnitId, expected.baseQuantity, 0), line.goods.baseQuantity())
            assertEquals(Money(Currency.EGP, expected.minor), line.goods.total())
        }
        assertEquals(Money(Currency.EGP, claim.minor), invoice.total())
        assertEquals(request.payload, LocalPurchasingCodec.captureInvoice(command))
        assertEquals(request.payload, LocalPurchasingCodec.normalize(request.command, request.payload))
    }

    fun assertOriginalResult(returned: LocalOperationResult.Recorded, reply: MutationReply.Recorded,
        entry: StoredClientIntentV1) {
        val result = LocalPurchasingCodec.captureResult(reply.result)
        assertTrue(reply.committed)
        assertEquals("committed", reply.code)
        assertEquals(reply.operationId, returned.operationId)
        assertEquals(LocalAction.SUPPLIER_INVOICE_CAPTURE, returned.originalAction)
        assertEquals(reply.originalActorId, entry.owner.actorId)
        assertEquals(StoredKernelResultV1.from(reply), entry.recorded)
        assertEquals(LocalPendingPhase.RECORDED_UNACKNOWLEDGED, entry.localPhase)
        val record = assertNotNull(returned.result)
        assertEquals(LocalRecordKind.PURCHASE_INVOICE, record.reference.kind)
        assertEquals(result.id, record.reference.id)
        assertEquals(LocalInvoiceEntryKind.CAPTURE_ONLY_V2, record.invoiceEntryKind)
        assertEquals("UNMATCHED_UNAPPROVED", assertNotNull(record.status).english)
        assertEquals("1", record.values.single { it.label == fieldLabel(LocalField.EXPECTED_STORAGE_REVISION) }.value.english)
        assertTrue(record.values.none { it.label == fieldLabel(LocalField.EXPECTED_PROGRESS_REVISION) })
        assertEquals("purchasing.invoice_captured", result.code)
        assertEquals(Revision(1), result.revision)
    }

    fun assertCaptureProjection(value: LocalPurchasingInvoiceCaptureViewV2, intent: LocalCommandIntent,
        claim: FrozenInvoiceCaptureClaim, costs: Boolean, internalAllocationId: DomainId? = null) {
        assertEquals(headerId(intent, LocalField.INVOICE_ID), value.id)
        assertEquals(Revision(1), value.revision)
        assertEquals(supplierId, value.supplierId)
        assertEquals("UNMATCHED_UNAPPROVED", value.status)
        assertEquals(claim.reference, value.reference)
        assertEquals(claim.date, value.date.canonicalString())
        assertEquals(claim.date, value.dueDate.canonicalString())
        assertEquals(sourceEvidenceId, value.sourceEvidenceId)
        assertEquals(intent.operationId, value.operationId)
        assertEquals(actorId, value.originalActorId)
        assertEquals(recordedAt, value.recordedAt)
        assertEquals(policyRevision, value.policyRevision)
        assertEquals(headerText(intent, LocalField.SUPPLIER_ORDER_REFERENCE), value.supplierOrderReference)
        if (intent.values.any { it.field == LocalField.PURCHASE_ID })
            assertEquals(PurchaseInvoiceOrderLinkV2(orderId, orderRevision), value.internalOrderLink)
        else assertNull(value.internalOrderLink)
        assertEquals(claim.lines.size, value.lines.size)
        for (index in claim.lines.indices) {
            val expected = goods(claim.lines[index])
            val line = value.lines[index]
            assertEquals(intent.lines[index].lineId, line.lineId)
            assertEquals(PurchaseInvoiceEntryProvenanceV2.DOCUMENTARY, line.provenance)
            assertEquals(internalAllocationId, line.internalAllocationId)
            assertEquals(lineText(intent, index, LocalField.SUPPLIER_ALLOCATION_REFERENCE), line.supplierAllocationReference)
            assertEquals(expected.skuId, line.skuId)
            assertEquals(expected.supplierItemReference, line.supplierItemReference)
            assertEquals(expected.units, line.units)
            assertEquals(expected.quantity, line.originalQuantity)
            assertEquals(Quantity.parse(expected.units.baseUnitId, claim.lines[index].baseQuantity, 0), line.baseQuantity)
            if (costs) {
                assertEquals(expected.unitPrice, line.unitPrice)
                assertEquals(expected.discount, line.discount)
                assertEquals(expected.tax, line.purchaseTax)
                assertEquals(Money(Currency.EGP, claim.lines[index].minor), line.claimedNet)
                assertEquals(Money(Currency.EGP, frozen.purchaseTaxMinor), line.claimedTax)
            } else {
                assertNull(line.unitPrice)
                assertNull(line.discount)
                assertNull(line.purchaseTax)
                assertNull(line.claimedNet)
                assertNull(line.claimedTax)
            }
            if (internalAllocationId == null) assertNull(line.originalOrderLine)
            else {
                val original = assertNotNull(line.originalOrderLine)
                assertEquals(originalAllocationId, original.allocationId)
                assertEquals(originalGoods.skuId, original.skuId)
                assertEquals(originalGoods.units, original.units)
                assertEquals(originalGoods.quantity, original.quantity)
                assertEquals(Quantity.parse(originalGoods.units.baseUnitId, frozen.originalLine.baseQuantity, 0), original.baseQuantity)
                if (costs) {
                    assertEquals(originalGoods.unitPrice, original.unitPrice)
                    assertEquals(originalGoods.discount, original.discount)
                    assertEquals(originalGoods.tax, original.purchaseTax)
                    assertEquals(Money(Currency.EGP, frozen.originalMinor), original.net)
                    assertEquals(Money(Currency.EGP, frozen.purchaseTaxMinor), original.taxAmount)
                } else {
                    assertNull(original.unitPrice)
                    assertNull(original.discount)
                    assertNull(original.purchaseTax)
                    assertNull(original.net)
                    assertNull(original.taxAmount)
                }
            }
        }
        if (costs) {
            assertEquals(emptyList(), value.charges)
            assertEquals(Money(Currency.EGP, claim.minor), value.supplierClaim)
        } else {
            assertNull(value.charges)
            assertNull(value.supplierClaim)
        }
    }

    fun assertOriginalOrderTerms() {
        val original = frozen.originalLine
        assertEquals(Quantity.parse(originalGoods.units.purchaseUnitId, original.purchaseQuantity, 0), originalGoods.quantity)
        assertEquals(Quantity.parse(originalGoods.units.baseUnitId, original.baseQuantity, 0), originalGoods.baseQuantity())
        assertEquals(UnitConversionRate.parseCanonical(original.rate), originalGoods.units.rate)
        assertEquals(Money(Currency.EGP, frozen.originalMinor), originalGoods.total())
    }

    fun assertNoDispatch() {
        assertTrue(endpoint.executions.isEmpty())
        assertTrue(endpoint.lookups.isEmpty())
    }

    fun orderReply(request: ReadRequest): ReadReply.Data {
        val query = frozen.json.decodeFromString(LocalPurchasingQueryV1.serializer(), request.payload.copyBytes().decodeToString())
        check(query.section == LocalPurchasingSectionV1.ORDER && query.id == orderId)
        val document = orderDocument(query.includeCosts)
        return data(LocalPurchasingPageV1(query.section, listOf(document), null, query.includeCosts),
            LocalPurchasingPageV1.serializer())
    }

    fun supplierReply(request: ReadRequest): ReadReply.Data {
        val query = LocalConfigurationCodec.decodeQuery(request.payload)
        check(query.kind == LocalConfigurationProfile.SUPPLIER && query.id == supplierId)
        val names = LocalNamesV1("Synthetic supplier S1", "")
        return data(LocalConfigurationPageV1(LocalConfigurationProfile.SUPPLIER, listOf(
            LocalConfigurationListItemV1(supplierId, supplierDomainRevision, supplierStorageRevision, names, true, null)),
            null, detail = LocalConfigurationDetailV1.Supplier(LocalSupplierV1(supplierId, names, true))),
            LocalConfigurationPageV1.serializer())
    }

    fun invoiceReply(request: ReadRequest): ReadReply.Data {
        val query = frozen.json.decodeFromString(LocalPurchasingQueryV1.serializer(), request.payload.copyBytes().decodeToString())
        check(query.section == LocalPurchasingSectionV1.INVOICE)
        val command = endpoint.executions.singleOrNull()?.let { LocalPurchasingCodec.decodeCaptureInvoice(it.payload) }
        val document = command?.let {
            val detail = captureProjection(it.invoice, endpoint.executions.single().operationId, query.includeCosts)
            if (query.id == null) detail.copy(lines = emptyList()) else detail
        }
        if (query.id != null) check(document?.id == query.id)
        return data(LocalPurchasingInvoicePageV2(document?.let { listOf(LocalPurchasingInvoiceDocumentV2(capture = it)) }
            ?: emptyList(), null, query.includeCosts), LocalPurchasingInvoicePageV2.serializer())
    }

    fun recorded(request: MutationRequest): MutationReply.Recorded {
        val invoice = LocalPurchasingCodec.decodeCaptureInvoice(request.payload).invoice
        val value = endpoint.resultOverride ?: LocalInvoiceCaptureResultV2("purchasing.invoice_captured", invoice.id, Revision(1))
        return MutationReply.Recorded(request.operationId, actorId, true, "committed",
            endpoint.rawResultOverride ?: LocalPurchasingCodec.captureResult(value), Revision(29), JournalSequence(43), recordedAt, false)
    }

    private fun orderDocument(costs: Boolean): LocalPurchasingDocumentViewV1 {
        val zero = Quantity(originalGoods.units.baseUnitId, 0, 0)
        val line = LocalPurchasingLineViewV1(originalLineId, originalAllocationId, originalGoods.skuId, null,
            warehouseId, originalGoods.units, originalGoods.quantity, originalGoods.baseQuantity(), zero, zero, zero, zero,
            originalGoods.unitPrice.takeIf { costs }, originalGoods.discount.takeIf { costs }, originalGoods.tax.takeIf { costs },
            originalGoods.net.takeIf { costs }, originalGoods.taxAmount.takeIf { costs }, null, null)
        return LocalPurchasingDocumentViewV1(orderId, orderRevision, supplierId, orderId, "ORDER_APPROVED",
            BusinessDate.parseCanonical(frozen.supplierDate), null, null, null, listOf(line), emptyList(),
            "PO-000042".takeIf { costs }, null, null, emptyList<PurchaseChargeV1>().takeIf { costs },
            originalGoods.total().takeIf { costs }, null, null, null)
    }

    private fun captureProjection(invoice: PurchaseInvoiceCaptureV2, operationId: OperationId,
        costs: Boolean): LocalPurchasingInvoiceCaptureViewV2 {
        val lines = invoice.lines.map { line ->
            val actual = line.goods
            val original = line.internalAllocationId?.let {
                LocalPurchasingInvoiceOriginalLineV2(originalAllocationId, originalGoods.skuId, warehouseId,
                    originalGoods.units, originalGoods.quantity, originalGoods.baseQuantity(),
                    originalGoods.unitPrice.takeIf { costs }, originalGoods.discount.takeIf { costs },
                    originalGoods.tax.takeIf { costs }, originalGoods.net.takeIf { costs }, originalGoods.taxAmount.takeIf { costs })
            }
            LocalPurchasingInvoiceCaptureLineViewV2(line.lineId, line.provenance, line.internalAllocationId,
                line.supplierAllocationReference, actual.skuId, actual.supplierItemReference, actual.units,
                actual.quantity, actual.baseQuantity(), original, actual.unitPrice.takeIf { costs },
                actual.discount.takeIf { costs }, actual.tax.takeIf { costs }, actual.net.takeIf { costs },
                actual.taxAmount.takeIf { costs })
        }
        return LocalPurchasingInvoiceCaptureViewV2(invoice.id, Revision(1), supplierId, "UNMATCHED_UNAPPROVED",
            invoice.invoiceDate, invoice.dueDate, invoice.internalOrderLink, invoice.reference, invoice.supplierOrderReference,
            invoice.sourceEvidenceId, operationId, actorId, recordedAt, policyRevision, lines,
            emptyList(), invoice.charges.takeIf { costs }, invoice.total().takeIf { costs })
    }

    private fun <T> data(value: T, serializer: kotlinx.serialization.KSerializer<T>): ReadReply.Data =
        ReadReply.Data(frozen.body(value, serializer), Revision(31), recordedAt)
}

/** Controlled replies, with lost response occurring after creation of a controlled result only. */
internal class ControlledInvoiceCaptureEndpoint(private val fixture: SupplierInvoiceCaptureFixture) : LocalMasterClient {
    override val health: StateFlow<LocalMasterEndpointHealth> get() = error("No endpoint health fixture")
    var currentReply: LocalSessionViewResult? = null
    var rejectReads = false
    var loseNextReply = false
    var found: MutationReply.Recorded? = null
    var resultOverride: LocalInvoiceCaptureResultV2? = null
    var rawResultOverride: StoredBytes? = null
    val reads = mutableListOf<ReadRequest>()
    val readReplies = mutableListOf<ReadReply.Data>()
    val executions = mutableListOf<MutationRequest>()
    val lookups = mutableListOf<OperationIdentity>()
    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult =
        currentReply ?: LocalSessionViewResult.Current(fixture.view())
    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        check(!rejectReads) { "An original capture must not be rebuilt from a later read" }
        reads += request
        val reply = when (request.query) {
            LocalPurchasingProfile.READ -> fixture.orderReply(request)
            LocalPurchasingProfile.READ_INVOICES -> fixture.invoiceReply(request)
            LocalConfigurationProfile.QUERY -> fixture.supplierReply(request)
            else -> error("Unbound supplier invoice capture read")
        }
        readReplies += reply
        return reply
    }
    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        check(request.command == LocalPurchasingProfile.CAPTURE_INVOICE)
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

/** Memory-only journal seam. DURABLE is a controlled outcome, never a durable-storage claim. */
internal class ControlledInvoiceCaptureJournal(owner: LocalClientIdentity) : LocalClientIntentJournal(owner) {
    var entry: StoredClientIntentV1? = null
    var stageCalls = 0
    var rememberCalls = 0
    var acknowledgeCalls = 0
    var retentionOutcome = JournalWrite.DURABLE
    var dispatchOutcome = JournalWrite.DURABLE
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
        if (dispatchOutcome != JournalWrite.DURABLE) return dispatchOutcome
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
    fun codecRoundTrip(): ControlledInvoiceCaptureJournal = ControlledInvoiceCaptureJournal(owner).also { restored ->
        entry?.let { original ->
            val bytes = journalJson.encodeToString(StoredClientIntentV1.serializer(), original)
            restored.entry = journalJson.decodeFromString(StoredClientIntentV1.serializer(), bytes).also { it.validate(owner) }
        }
    }
    override suspend fun close() = Unit
}

internal fun headerId(intent: LocalCommandIntent, field: LocalField): DomainId =
    assertIs<LocalScalarValue.Identifier>(intent.values.single { it.field == field }.value).value

internal fun headerText(intent: LocalCommandIntent, field: LocalField): String? =
    intent.values.singleOrNull { it.field == field }?.let { assertIs<LocalScalarValue.Text>(it.value).value }

internal fun lineText(intent: LocalCommandIntent, index: Int, field: LocalField): String? =
    intent.lines[index].values.singleOrNull { it.field == field }?.let { assertIs<LocalScalarValue.Text>(it.value).value }

internal fun replaceCaptureLineInput(intent: LocalCommandIntent, field: LocalField, value: LocalScalarValue,
    index: Int = 0): LocalCommandIntent = LocalCommandIntent(intent.operationId, intent.action, intent.formVersion,
    intent.reference, intent.values, intent.lineField, intent.lines.mapIndexed { lineIndex, line ->
        if (lineIndex != index) line else {
            check(line.values.count { it.field == field } == 1)
            LocalInputLine(line.lineId, line.values.map { if (it.field == field) it.copy(value = value) else it })
        }
    })

internal fun replaceCaptureHeader(intent: LocalCommandIntent, field: LocalField, value: LocalScalarValue): LocalCommandIntent {
    check(intent.values.count { it.field == field } == 1)
    return LocalCommandIntent(intent.operationId, intent.action, intent.formVersion, intent.reference,
        intent.values.map { if (it.field == field) it.copy(value = value) else it }, intent.lineField, intent.lines)
}
