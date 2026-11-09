@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.gateway.independent.supplierinvoicecapturerpcv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.app.gateway.independent.supplierinvoicecapturev2.SupplierInvoiceCaptureFixture
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Frozen ICR v1 partial supporting bindings only; see README.md for the per-method limits. */
class SupplierInvoiceCaptureRpcGatewaySupportingTest {
    /** ICR-01/03/09, SCOPE-1/2/4: installed selection only, no admission or permission proof. */
    @Test
    fun icr01Icr03Icr09ProfileAdmitsOnlyCaptureAndItsReadFamilies() {
        val profile = LocalWorkflowInstallation.directoryOnly(LocalMasterRpcWorkflowProfile.INVOICE_CAPTURE)
        LocalAction.entries.forEach { assertEquals(it == LocalAction.SUPPLIER_INVOICE_CAPTURE, profile.allows(it), it.name) }
        assertTrue(profile.has(LocalPurchasingProfile.CAPTURE_INVOICE))
        listOf(LocalPurchasingProfile.APPROVE_ORDER, LocalPurchasingProfile.RECORD_INVOICE,
            LocalPurchasingProfile.MATCH_INVOICE, LocalPurchasingProfile.MATCH_UNCONTESTED_INVOICE,
            LocalPurchasingProfile.RECORD_CREDIT, LocalConfigurationProfile.COMMAND).forEach { assertFalse(profile.has(it)) }
        for (section in listOf(LocalPurchasingSectionV1.ORDER, LocalPurchasingSectionV1.INVOICE)) {
            val key = if (section == LocalPurchasingSectionV1.ORDER) LocalPurchasingProfile.READ else LocalPurchasingProfile.READ_INVOICES
            profile.requireRead(ReadRequest(key, LocalPurchasingCodec.query(LocalPurchasingQueryV1(section))))
        }
        for (kind in listOf(LocalConfigurationProfile.SUPPLIER, LocalConfigurationProfile.SKU))
            profile.requireRead(ReadRequest(LocalConfigurationProfile.QUERY, LocalConfigurationCodec.query(LocalConfigurationQueryV1(kind))))
        val excluded = listOf(
            ReadRequest(LocalPurchasingProfile.READ, LocalPurchasingCodec.query(LocalPurchasingQueryV1(LocalPurchasingSectionV1.INVOICE))),
            ReadRequest(LocalPurchasingProfile.READ, LocalPurchasingCodec.query(LocalPurchasingQueryV1(LocalPurchasingSectionV1.PAYABLE, includeCosts = true))),
            ReadRequest(LocalPurchasingProfile.READ_INVOICES, LocalPurchasingCodec.query(LocalPurchasingQueryV1(LocalPurchasingSectionV1.INVOICE, export = true))),
            ReadRequest(LocalConfigurationProfile.QUERY, LocalConfigurationCodec.query(LocalConfigurationQueryV1(LocalConfigurationProfile.WAREHOUSE))),
            ReadRequest(LocalConfigurationProfile.QUERY, LocalConfigurationCodec.query(LocalConfigurationQueryV1(LocalConfigurationProfile.BASIS))),
            ReadRequest(LocalConfigurationProfile.QUERY, LocalConfigurationCodec.query(LocalConfigurationQueryV1(LocalConfigurationProfile.SKU,
                barcode = IcrInputs.text(IcrInputs.fixtures.getValue("goods").jsonArray[1].jsonObject, "skuLiteral")))),
        )
        excluded.forEach { assertFails { profile.requireRead(it) } }
    }

    /** ICR-02/03, SCOPE-2: outgoing selectors only; no F0 page, catalog qualification or cost release. */
    @Test
    fun icr02Icr03GatewaySelectsInvoicePoAndSupplierSkuReads() = runTest {
        val f = InvoiceCaptureRpcGatewayFixture()
        val legacyId = DomainId(IcrInputs.text(IcrInputs.fixtures.getValue("F0").jsonObject
            .getValue("invoiceRecords").jsonArray.first().jsonObject, "id"))
        for (costs in listOf(false, true)) for (id in listOf(null, legacyId)) {
            assertIs<LocalReadResult.Unavailable>(f.gateway.read(LocalWorkflowQuery(LocalWorkflow.PURCHASING,
                reference = id?.let { LocalRecordReference(LocalRecordKind.PURCHASE_INVOICE, it) },
                purchasing = LocalPurchasingUiQuery(LocalPurchasingSection.INVOICES, includeCosts = costs))))
            val sent = f.endpoint.reads.last()
            assertEquals(LocalPurchasingProfile.READ_INVOICES, sent.query)
            val query = LocalPurchasingCodec.decodeQuery(sent.payload)
            assertEquals(LocalPurchasingSectionV1.INVOICE, query.section)
            assertEquals(id, query.id)
            assertEquals(costs, query.includeCosts)
            assertFalse(query.export)
        }
        f.gateway.read(LocalWorkflowQuery(LocalWorkflow.PURCHASING,
            reference = LocalRecordReference(LocalRecordKind.PURCHASE, f.order),
            purchasing = LocalPurchasingUiQuery(LocalPurchasingSection.ORDERS)))
        val po = f.endpoint.reads.last()
        assertEquals(LocalPurchasingProfile.READ, po.query)
        assertEquals(LocalPurchasingSectionV1.ORDER, LocalPurchasingCodec.decodeQuery(po.payload).section)
        assertEquals(f.order, LocalPurchasingCodec.decodeQuery(po.payload).id)
        for ((kind, id, storedKind) in listOf(
            Triple(LocalRecordKind.SUPPLIER, f.supplier, LocalConfigurationProfile.SUPPLIER),
            Triple(LocalRecordKind.SKU, IcrInputs.sku("SKU-A"), LocalConfigurationProfile.SKU),
            Triple(LocalRecordKind.SKU, IcrInputs.sku("SKU-B"), LocalConfigurationProfile.SKU))) {
            f.gateway.references(LocalReferenceQuery(LocalReferenceSource(kind), exactId = id))
            val sent = f.endpoint.reads.last()
            assertEquals(LocalConfigurationProfile.QUERY, sent.query)
            val query = LocalConfigurationCodec.decodeQuery(sent.payload)
            assertEquals(storedKind, query.kind)
            assertEquals(id, query.id)
            assertNull(query.barcode)
        }
        assertTrue(f.endpoint.executions.isEmpty())
        assertNull(f.journal.entry)
    }

    /** ICR-03/09, SCOPE-2/4: blocked client selectors/actions never reach the scripted port. */
    @Test
    fun icr03Icr09ExcludedWorkflowsAndActionsDoNotDispatch() = runTest {
        // Reuse the older fixture's connection mechanics only; none of its claim/PO numbers is an oracle here.
        val old = SupplierInvoiceCaptureFixture()
        val restricted = old.newGateway(selectedInstallation =
            LocalWorkflowInstallation.directoryOnly(LocalMasterRpcWorkflowProfile.INVOICE_CAPTURE))
        for (workflow in LocalWorkflow.entries.filter { it != LocalWorkflow.PURCHASING })
            assertIs<LocalReadResult.Unavailable>(restricted.read(LocalWorkflowQuery(workflow)))
        for (section in LocalPurchasingSection.entries.filter { it !in listOf(LocalPurchasingSection.INVOICES, LocalPurchasingSection.ORDERS) })
            assertIs<LocalReadResult.Unavailable>(restricted.read(LocalWorkflowQuery(LocalWorkflow.PURCHASING,
                purchasing = LocalPurchasingUiQuery(section))))
        assertIs<LocalReferenceResult.Unavailable>(restricted.references(LocalReferenceQuery(LocalReferenceSource(LocalRecordKind.WAREHOUSE))))
        assertTrue(old.endpoint.reads.isEmpty())
        val f = InvoiceCaptureRpcGatewayFixture()
        val original = f.intent()
        for (action in listOf(LocalAction.PURCHASE_APPROVE, LocalAction.SUPPLIER_CREATE, LocalAction.SUPPLIER_UPDATE,
            LocalAction.SUPPLIER_INVOICE_RECORD, LocalAction.SUPPLIER_INVOICE_MATCH, LocalAction.SUPPLIER_CREDIT_RECORD)) {
            val excluded = LocalCommandIntent(original.operationId, action, original.formVersion, original.reference,
                original.values, original.lineField, original.lines)
            assertIs<LocalStageResult.NotStaged>(f.gateway.stage(excluded))
        }
        assertTrue(f.endpoint.reads.isEmpty())
        assertTrue(f.endpoint.executions.isEmpty())
        assertTrue(f.endpoint.lookups.isEmpty())
        assertEquals(0, f.journal.stageCalls)
        assertNull(f.journal.entry)
    }

    /** ICR-07/10, SCOPE-3/4/6: C0/C1 entry-to-V2 body preservation and explicit dispatch only. */
    @Test
    fun icr07Icr10C0AndC1KeepFrozenActualClaimBeforeV2Dispatch() = runTest {
        for (key in listOf("C0", "C1")) {
            val f = InvoiceCaptureRpcGatewayFixture()
            val intent = f.intent(key)
            assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
            val saved = assertNotNull(f.journal.entry)
            assertEquals(IcrInputs.operation(key), saved.operationId)
            assertEquals(LocalClientIdentity(f.scope, f.actor, f.device), saved.owner)
            assertTrue(f.endpoint.executions.isEmpty())
            val request = saved.request()
            assertEquals(LocalPurchasingProfile.CAPTURE_INVOICE, request.command)
            assertNull(request.actionApprovalId)
            val claim = LocalPurchasingCodec.decodeCaptureInvoice(request.payload).invoice
            assertEquals(f.invoice, claim.id)
            assertEquals(f.supplier, claim.supplierId)
            assertEquals(IcrInputs.text(IcrInputs.claim, "invoiceReferenceLiteral"), claim.reference)
            assertEquals(IcrInputs.text(IcrInputs.claim, "supplierPoReferenceLiteral"), claim.supplierOrderReference)
            assertEquals(Money(Currency.EGP, IcrInputs.text(IcrInputs.claim, "totalMinor").toLong()), claim.total())
            assertEquals(IcrInputs.lines.size, claim.lines.size)
            for ((index, captured) in claim.lines.withIndex()) {
                val expected = IcrInputs.lines[index]
                val goods = captured.goods
                val scale = IcrInputs.quantityScale(IcrInputs.text(expected, "sku"))
                assertEquals(intent.lines[index].lineId, captured.lineId)
                assertEquals(IcrInputs.sku(IcrInputs.text(expected, "sku")), goods.skuId)
                assertEquals(IcrInputs.text(expected, "supplierItemReferenceLiteral"), goods.supplierItemReference)
                assertEquals(IcrInputs.unit(IcrInputs.text(expected, "purchaseUnit")), goods.units.purchaseUnitId)
                assertEquals(IcrInputs.unit(IcrInputs.text(expected, "baseUnit")), goods.units.baseUnitId)
                assertEquals(scale, goods.units.baseScale)
                assertEquals(Quantity.parse(goods.units.purchaseUnitId, IcrInputs.text(expected, "purchaseQuantity"), scale), goods.quantity)
                assertEquals(Quantity.parse(goods.units.baseUnitId, IcrInputs.text(expected, "baseQuantity"), scale), goods.baseQuantity())
                assertEquals(UnitConversionRate.parseCanonical(IcrInputs.rate(expected)), goods.units.rate)
                if (goods.units.purchaseUnitId != goods.units.baseUnitId)
                    assertEquals(Revision(IcrInputs.text(expected, "unitRevision").toLong()), goods.units.conversionRevision)
                assertEquals(UnitPrice.parse(Currency.EGP, IcrInputs.text(expected, "unitPrice")), goods.unitPrice)
                assertEquals(IcrInputs.text(expected, "netMinor").toLong(), goods.net.minor)
                assertEquals(IcrInputs.text(expected, "taxMinor").toLong(), goods.taxAmount.minor)
                assertEquals(PurchaseInvoiceEntryProvenanceV2.DOCUMENTARY, captured.provenance)
                assertEquals(f.allocation.takeIf { key == "C1" && index == 0 }, captured.internalAllocationId)
            }
            assertEquals(f.order.takeIf { key == "C1" }, claim.internalOrderLink?.orderId)
            assertEquals(IcrInputs.text(IcrInputs.order, "revision").toLong().takeIf { key == "C1" }, claim.internalOrderLink?.revision?.value)
            assertIs<LocalOperationResult.Recorded>(f.gateway.submit(intent.operationId))
            assertEquals(1, f.endpoint.executions.size)
            assertEquals(request.command, f.endpoint.executions.single().command)
            assertEquals(request.payload, f.endpoint.executions.single().payload)
            assertEquals(saved.payloadHex, assertNotNull(f.journal.entry).payloadHex)
        }
    }

    /** ICR-13/14/15, SCOPE-5/6: memory original nonreplacement and explicit source-free lookup. */
    @Test
    fun icr13Icr14Icr15LostResultLookupKeepsTheExactStagedOriginal() = runTest {
        val f = InvoiceCaptureRpcGatewayFixture()
        val intent = f.intent()
        assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
        f.endpoint.loseReply = true
        assertIs<LocalOperationResult.Unresolved>(f.gateway.submit(intent.operationId))
        val original = assertNotNull(f.journal.entry)
        assertNull(original.recorded)
        assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED, original.localPhase)
        f.endpoint.refuseReads = true
        val reads = f.endpoint.reads.size
        val lookups = f.endpoint.lookups.size
        val changed = LocalCommandIntent(intent.operationId, intent.action, intent.formVersion, intent.reference,
            intent.values.map { if (it.field == LocalField.EXTERNAL_REFERENCE)
                LocalScalarInput(it.field, LocalScalarValue.Text("INV-PENDING-0005")) else it },
            intent.lineField, intent.lines)
        val refused = f.gateway.stage(changed)
        assertTrue(refused is LocalStageResult.Uncertain || refused is LocalStageResult.NotStaged)
        assertEquals(original, f.journal.entry)
        assertEquals(lookups, f.endpoint.lookups.size)
        assertIs<LocalOperationResult.Recorded>(f.gateway.lookup(intent.operationId))
        assertEquals(listOf(original.identity()), f.endpoint.lookups.drop(lookups))
        assertEquals(reads, f.endpoint.reads.size)
        assertEquals(1, f.endpoint.executions.size)
        assertEquals(original.payloadHex, assertNotNull(f.journal.entry).payloadHex)
        assertEquals(original.semanticSha256, assertNotNull(f.journal.entry).semanticSha256)
        assertEquals(original.owner, assertNotNull(f.journal.entry).owner)
    }

    /** ICR-16, SCOPE-6: caller completion waits for controlled retention; no physical durability claim. */
    @Test
    fun icr16ClientResultWaitsForRetentionBeforeReturnAndAck() = runTest {
        val f = InvoiceCaptureRpcGatewayFixture()
        val intent = f.intent()
        assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
        val release = CompletableDeferred<Unit>()
        f.journal.retentionRelease = release
        val submit = async { f.gateway.submit(intent.operationId) }
        try {
            runCurrent()
            assertTrue(f.journal.retentionEntered.isCompleted)
            assertFalse(submit.isCompleted)
            assertNull(assertNotNull(f.journal.entry).recorded)
            assertEquals(0, f.journal.acknowledgeCalls)
            release.complete(Unit)
            assertIs<LocalOperationResult.Recorded>(submit.await())
            assertEquals(StoredKernelResultV1.from(assertNotNull(f.endpoint.found)), assertNotNull(f.journal.entry).recorded)
            assertIs<LocalAcknowledgementResult.Acknowledged>(f.gateway.acknowledgeRecorded(intent.operationId))
            assertTrue(assertNotNull(f.journal.entry).acknowledged)
            assertEquals(1, f.endpoint.executions.size)
        } finally {
            release.complete(Unit)
            submit.cancelAndJoin()
        }
    }

    /** ICR-14/16/17, SCOPE-6: failed memory retention blocks ACK and leaves the same lookup original. */
    @Test
    fun icr14Icr16Icr17FailedRetentionDoesNotLoseOriginalOrPermitAck() = runTest {
        val f = InvoiceCaptureRpcGatewayFixture()
        val intent = f.intent()
        assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
        val original = assertNotNull(f.journal.entry)
        f.journal.retentionOutcome = JournalWrite.UNAVAILABLE
        assertIs<LocalOperationResult.Unresolved>(f.gateway.submit(intent.operationId))
        assertNull(assertNotNull(f.journal.entry).recorded)
        assertFalse(assertNotNull(f.journal.entry).acknowledged)
        val lookups = f.endpoint.lookups.size
        assertIs<LocalAcknowledgementResult.Unresolved>(f.gateway.acknowledgeRecorded(intent.operationId))
        assertEquals(0, f.journal.acknowledgeCalls)
        assertEquals(lookups, f.endpoint.lookups.size)
        f.endpoint.refuseReads = true
        f.journal.retentionOutcome = JournalWrite.DURABLE
        assertIs<LocalOperationResult.Recorded>(f.gateway.lookup(intent.operationId))
        assertEquals(listOf(original.identity()), f.endpoint.lookups.drop(lookups))
        assertEquals(original.payloadHex, assertNotNull(f.journal.entry).payloadHex)
        assertEquals(original.owner, assertNotNull(f.journal.entry).owner)
        assertEquals(1, f.endpoint.executions.size)
    }
}
