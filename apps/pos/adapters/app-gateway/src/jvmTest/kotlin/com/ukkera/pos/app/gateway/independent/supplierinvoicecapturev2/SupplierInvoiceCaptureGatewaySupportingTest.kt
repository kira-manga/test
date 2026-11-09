package com.ukkera.pos.app.gateway.independent.supplierinvoicecapturev2

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.app.gateway.independent.supplierinvoicediscrepancyv1.SupplierInvoiceConformingFixture
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.inventory.PurchaseInvoiceEntryProvenanceV2
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
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
 * Frozen SIDC/SIRD input and policy contributions, profile S only.
 * Real shared gateway/codec over controlled DTO/session/journal ports. No durable
 * invoice/ledger read, Master authorization, admitted RPC or whole-case result.
 */
class SupplierInvoiceCaptureGatewaySupportingTest {
    @Test
    fun sidc02CaptureFormsKeepNoPoAndActualSnapshotInputsExplicit() = runTest(timeout = 5.seconds) {
        val f = SupplierInvoiceCaptureFixture()
        val listForm = f.currentForm()
        val orderForm = f.currentForm(fromOrder = true)
        assertEquals(LocalAction.SUPPLIER_INVOICE_CAPTURE, listForm.action)
        assertEquals(1, listForm.version)
        val header = listForm.fields.filterIsInstance<LocalFormField.Scalar>()
        val supplier = header.single { it.field == LocalField.SUPPLIER_ID }
        assertEquals(LocalInputKind.REFERENCE, supplier.kind)
        assertNotNull(supplier.referenceSource)
        for (field in listOf(LocalField.PURCHASE_ID, LocalField.EXPECTED_ORDER_REVISION,
            LocalField.SUPPLIER_ORDER_REFERENCE)) {
            val input = header.single { it.field == field }
            assertFalse(input.required)
            assertNull(input.initialValue)
        }
        assertEquals(LocalInputKind.TEXT, header.single { it.field == LocalField.SUPPLIER_ORDER_REFERENCE }.kind)
        val selectedHeader = orderForm.fields.filterIsInstance<LocalFormField.Scalar>()
        assertEquals(LocalScalarValue.Identifier(f.supplierId), selectedHeader.single { it.field == LocalField.SUPPLIER_ID }.initialValue)
        assertEquals(LocalScalarValue.Identifier(f.orderId), selectedHeader.single { it.field == LocalField.PURCHASE_ID }.initialValue)
        assertEquals(LocalScalarValue.Integer(f.orderRevision.value), selectedHeader.single {
            it.field == LocalField.EXPECTED_ORDER_REVISION
        }.initialValue)
        for (form in listOf(listForm, orderForm)) {
            val rows = form.fields.filterIsInstance<LocalFormField.Lines>().single()
            assertTrue(rows.initialLines.isEmpty())
            val mode = rows.columns.single { it.field == LocalField.MODE }
            assertNull(mode.initialValue)
            assertTrue(mode.choices.any { it.code == "DOCUMENTARY_GOODS" })
            assertTrue(rows.columns.none { it.field == LocalField.ALLOCATION_ID })
            val reference = rows.columns.single { it.field == LocalField.SUPPLIER_ALLOCATION_REFERENCE }
            assertEquals(LocalInputKind.TEXT, reference.kind)
            assertFalse(reference.required)
            assertNull(reference.referenceSource)
            val snapshots = listOf(LocalField.SKU_ID, LocalField.PRODUCT_ID, LocalField.UNIT,
                LocalField.PURCHASE_BASE_UNIT, LocalField.PURCHASE_BASE_SCALE, LocalField.CONVERSION_ID,
                LocalField.PURCHASE_CONVERSION_REVISION, LocalField.PURCHASE_CONVERSION_RATE)
            snapshots.forEach { field -> assertNull(rows.columns.single { it.field == field }.initialValue) }
            listOf(LocalField.QUANTITY, LocalField.UNIT_PRICE, LocalField.NET_AMOUNT, LocalField.TAX_AMOUNT)
                .forEach { field -> assertNull(rows.columns.single { it.field == field }.initialValue) }
        }
        val listModes = listForm.fields.filterIsInstance<LocalFormField.Lines>().single().columns
            .single { it.field == LocalField.MODE }.choices
        assertTrue(listModes.none { it.code.startsWith("ORDER_LINE:") || it.code.startsWith("DOCUMENTARY_ORDER_LINE:") })
        f.assertNoDispatch()
    }

    @Test
    fun sidc02NoPoMixedCaptureKeepsBothUnallocatedLinesAtExactlyEgp340() = runTest(timeout = 5.seconds) {
        val f = SupplierInvoiceCaptureFixture()
        val claim = FrozenSupplierInvoiceCaptureInputs.captureClaim("UNALLOCATED_MIXED")
        val intent = f.intent(f.currentForm(), claim)
        val originalHeaders = intent.values
        val originalRows = intent.lines.map { it.values }
        val staged = assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
        val request = assertNotNull(f.journal.entry).request()
        f.assertRequest(request, intent, claim)
        val invoice = LocalPurchasingCodec.decodeCaptureInvoice(request.payload).invoice
        assertNull(invoice.internalOrderLink)
        assertNull(invoice.supplierOrderReference)
        assertTrue(invoice.lines.all { it.internalAllocationId == null && it.supplierAllocationReference == null })
        assertNotEquals(f.originalGoods.skuId, invoice.lines.first().goods.skuId)
        assertNotEquals(invoice.lines.first().goods.units.baseUnitId, invoice.lines.last().goods.units.baseUnitId)
        assertEquals(originalHeaders, intent.values)
        assertEquals(originalRows, intent.lines.map { it.values })
        assertEquals(intent.operationId, staged.operation.operationId)
        assertEquals(LocalPendingPhase.STAGED, staged.operation.phase)
        assertNull(staged.operation.recordedDecision)
        assertTrue(staged.operation.summary.any { it.value.english.contains("340.00 EGP") })
        assertTrue(f.endpoint.reads.none { it.query == LocalPurchasingProfile.READ })
        f.assertOriginalOrderTerms()
        f.assertNoDispatch()
    }

    @Test
    fun sidc02ExplicitPoContextDoesNotAllocateOrSubstituteTheMixedClaim() = runTest(timeout = 5.seconds) {
        val f = SupplierInvoiceCaptureFixture()
        val claim = FrozenSupplierInvoiceCaptureInputs.captureClaim("UNALLOCATED_MIXED")
        val intent = f.intent(f.currentForm(fromOrder = true), claim)
        assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
        f.assertRequest(assertNotNull(f.journal.entry).request(), intent, claim)
        val invoice = LocalPurchasingCodec.decodeCaptureInvoice(assertNotNull(f.journal.entry).request().payload).invoice
        assertEquals(f.orderId, assertNotNull(invoice.internalOrderLink).orderId)
        assertTrue(invoice.lines.all { it.internalAllocationId == null })
        assertNotEquals(f.originalGoods.skuId, invoice.lines.first().goods.skuId)
        f.assertOriginalOrderTerms()
        f.assertNoDispatch()
    }

    @Test
    fun clarification11LiteralUnknownOrUuidLookingReferencesStaySeparateFromInternalLinks() = runTest(timeout = 5.seconds) {
        for (uuidLooking in listOf(false, true)) {
            val f = SupplierInvoiceCaptureFixture()
            val claim = FrozenSupplierInvoiceCaptureInputs.captureClaim("UNALLOCATED_MIXED")
            val orderText = if (uuidLooking) f.orderId.value else "SUP-PO-00999"
            val allocationTexts = if (uuidLooking) listOf(f.originalAllocationId.value, " 00000007-C/فاتورة ")
                else claim.lines.map { it.supplierItemReference }
            val intent = f.intent(f.currentForm(), claim, supplierOrderReference = orderText,
                supplierAllocationReferences = allocationTexts)
            assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
            f.assertRequest(assertNotNull(f.journal.entry).request(), intent, claim)
            val invoice = LocalPurchasingCodec.decodeCaptureInvoice(assertNotNull(f.journal.entry).request().payload).invoice
            assertEquals(orderText, invoice.supplierOrderReference)
            assertEquals(allocationTexts, invoice.lines.map { it.supplierAllocationReference })
            assertNull(invoice.internalOrderLink)
            assertTrue(invoice.lines.all { it.internalAllocationId == null })
            assertTrue(f.endpoint.reads.none { it.query == LocalPurchasingProfile.READ })
            f.assertNoDispatch()
        }
    }

    @Test
    fun sidc02ControlledInvoiceReadKeepsMixedRawReferencesAndHidesOnlyCosts() = runTest(timeout = 5.seconds) {
        val f = SupplierInvoiceCaptureFixture()
        val claim = FrozenSupplierInvoiceCaptureInputs.captureClaim("UNALLOCATED_MIXED")
        val orderText = f.orderId.value
        val allocationTexts = listOf(f.originalAllocationId.value, " 00000007-C/فاتورة ")
        val intent = f.intent(f.currentForm(), claim, supplierOrderReference = orderText,
            supplierAllocationReferences = allocationTexts)
        assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
        val originalRequest = assertNotNull(f.journal.entry).request()
        val returned = assertIs<LocalOperationResult.Recorded>(f.gateway.submit(intent.operationId))
        f.assertOriginalResult(returned, assertNotNull(f.endpoint.found), assertNotNull(f.journal.entry))
        val invoiceId = assertNotNull(returned.result).reference.id
        var shown: LocalWorkflowPage? = null
        for (costs in listOf(true, false)) {
            val page = f.capturedProjectionPage(invoiceId, costs)
            val query = FrozenSupplierInvoiceCaptureInputs.json.decodeFromString(LocalPurchasingQueryV1.serializer(),
                f.endpoint.reads.last().payload.copyBytes().decodeToString())
            assertEquals(LocalPurchasingProfile.READ_INVOICES, f.endpoint.reads.last().query)
            assertEquals(LocalPurchasingSectionV1.INVOICE, query.section)
            assertEquals(headerId(intent, LocalField.INVOICE_ID), query.id)
            assertEquals(costs, query.includeCosts)
            val decoded = LocalPurchasingCodec.invoicePage(f.endpoint.readReplies.last().payload)
            assertEquals(costs, decoded.includesCosts)
            val document = assertNotNull(decoded.documents).single()
            assertNull(document.legacy)
            f.assertCaptureProjection(assertNotNull(document.capture), intent, claim, costs)
            val invoice = page.records.single { it.reference.kind == LocalRecordKind.PURCHASE_INVOICE }
            assertEquals(invoiceId, invoice.reference.id)
            assertEquals(claim.reference, invoice.title.english)
            assertEquals("UNMATCHED_UNAPPROVED", assertNotNull(invoice.status).english)
            assertEquals(LocalInvoiceEntryKind.CAPTURE_ONLY_V2, invoice.invoiceEntryKind)
            assertEquals(orderText, captureReadText(invoice, fieldLabel(LocalField.SUPPLIER_ORDER_REFERENCE)))
            assertTrue(invoice.values.none { it.label == fieldLabel(LocalField.EXPECTED_ORDER_REVISION) })
            assertTrue(invoice.values.none { it.value.english == f.orderId.value &&
                it.label != fieldLabel(LocalField.SUPPLIER_ORDER_REFERENCE) })
            val lines = page.records.filter { it.reference.kind == LocalRecordKind.PURCHASE_LINE }
            assertEquals(intent.lines.map { it.lineId }, lines.map { it.reference.id })
            for (index in claim.lines.indices) {
                val line = lines[index]
                assertFalse(line.selectable)
                assertEquals(f.goods(claim.lines[index]).skuId.value, line.title.english)
                assertEquals(claim.lines[index].supplierItemReference,
                    captureReadText(line, fieldLabel(LocalField.SUPPLIER_ITEM_REFERENCE)))
                assertEquals(allocationTexts[index], captureReadText(line, fieldLabel(LocalField.SUPPLIER_ALLOCATION_REFERENCE)))
                assertEquals(claim.lines[index].rate, captureReadText(line, "Actual documented conversion"))
                assertTrue(line.values.none { it.label.english.startsWith("Selected original allocation") ||
                    it.label.english.startsWith("Original PO") })
                if (costs) assertEquals("${FrozenSupplierInvoiceCaptureInputs.moneyDecimal(claim.lines[index].minor)} EGP",
                    captureReadText(line, fieldLabel(LocalField.NET_AMOUNT)))
                else assertCaptureMoneyLabelsAbsent(line)
            }
            assertTrue(page.forms.isEmpty())
            if (costs) {
                assertEquals("${FrozenSupplierInvoiceCaptureInputs.moneyDecimal(claim.minor)} EGP",
                    captureReadText(invoice, "Original supplier claim — not an approval"))
                shown = page
            } else {
                assertTrue(invoice.values.none { it.label.english == "Original supplier claim — not an approval" })
                assertCaptureOperationalTextPreserved(assertNotNull(shown), page)
            }
        }
        val retainedRequest = assertNotNull(f.journal.entry).request()
        assertEquals(originalRequest.operationId, retainedRequest.operationId)
        assertEquals(originalRequest.command, retainedRequest.command)
        assertEquals(originalRequest.actionApprovalId, retainedRequest.actionApprovalId)
        assertEquals(originalRequest.payload, retainedRequest.payload)
        assertEquals(1, f.endpoint.executions.size)
        assertTrue(f.endpoint.reads.none { it.query == LocalPurchasingProfile.READ })
        // This endpoint projects controlled input; it is not an independent persisted invoice read.
        // The public V2 marker is bound here, not the ui/app controller or native payout gate.
    }

    @Test
    fun sidc02ControlledInvoiceSummaryKeepsClaimTotalWithoutInventingDetailRows() = runTest(timeout = 5.seconds) {
        val f = SupplierInvoiceCaptureFixture()
        val claim = FrozenSupplierInvoiceCaptureInputs.captureClaim("UNALLOCATED_MIXED")
        val intent = f.intent(f.currentForm(), claim, supplierOrderReference = "SUP-PO-00999")
        assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
        val returned = assertIs<LocalOperationResult.Recorded>(f.gateway.submit(intent.operationId))
        f.assertOriginalResult(returned, assertNotNull(f.endpoint.found), assertNotNull(f.journal.entry))
        val invoiceId = assertNotNull(returned.result).reference.id
        var shown: LocalWorkflowPage? = null
        for (costs in listOf(true, false)) {
            val page = f.captureDirectoryProjectionPage(costs)
            val query = FrozenSupplierInvoiceCaptureInputs.json.decodeFromString(LocalPurchasingQueryV1.serializer(),
                f.endpoint.reads.last().payload.copyBytes().decodeToString())
            assertEquals(LocalPurchasingProfile.READ_INVOICES, f.endpoint.reads.last().query)
            assertEquals(LocalPurchasingSectionV1.INVOICE, query.section)
            assertNull(query.id)
            assertEquals(costs, query.includeCosts)
            val decoded = LocalPurchasingCodec.invoicePage(f.endpoint.readReplies.last().payload)
            assertEquals(costs, decoded.includesCosts)
            val document = assertNotNull(decoded.documents).single()
            assertNull(document.legacy)
            val summary = assertNotNull(document.capture)
            assertEquals(headerId(intent, LocalField.INVOICE_ID), summary.id)
            assertEquals(invoiceId, summary.id)
            assertEquals(claim.reference, summary.reference)
            assertEquals("SUP-PO-00999", summary.supplierOrderReference)
            assertNull(summary.internalOrderLink)
            assertTrue(summary.lines.isEmpty())
            assertEquals(intent.operationId, summary.operationId)
            assertEquals(f.actorId, summary.originalActorId)
            val invoice = page.records.single()
            assertEquals(LocalRecordKind.PURCHASE_INVOICE, invoice.reference.kind)
            assertEquals(invoiceId, invoice.reference.id)
            assertEquals(LocalInvoiceEntryKind.CAPTURE_ONLY_V2, invoice.invoiceEntryKind)
            assertEquals(claim.reference, invoice.title.english)
            assertEquals("SUP-PO-00999", captureReadText(invoice, fieldLabel(LocalField.SUPPLIER_ORDER_REFERENCE)))
            if (costs) {
                assertEquals(Money(Currency.EGP, claim.minor), summary.supplierClaim)
                assertEquals(emptyList(), summary.charges)
                assertEquals("${FrozenSupplierInvoiceCaptureInputs.moneyDecimal(claim.minor)} EGP",
                    captureReadText(invoice, "Original supplier claim — not an approval"))
                shown = page
            } else {
                assertNull(summary.supplierClaim)
                assertNull(summary.charges)
                assertCaptureMoneyLabelsAbsent(invoice)
                assertCaptureOperationalTextPreserved(assertNotNull(shown), page)
            }
        }
        assertEquals(1, f.endpoint.executions.size)
        assertTrue(f.endpoint.reads.none { it.query == LocalPurchasingProfile.READ })
        // Summary handling is controlled projection support, not persisted-directory or cost authority.
    }

    @Test
    fun sidc01OtherUnitAndChangedConversionKeepExactActualTermsAndOriginalPoTotals() = runTest(timeout = 5.seconds) {
        for (variant in listOf("OTHER_UNIT", "CHANGED_CONVERSION")) {
            val f = SupplierInvoiceCaptureFixture()
            val claim = FrozenSupplierInvoiceCaptureInputs.captureClaim(variant)
            val intent = f.intent(f.currentForm(fromOrder = true), claim)
            assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
            f.assertRequest(assertNotNull(f.journal.entry).request(), intent, claim)
            val actual = LocalPurchasingCodec.decodeCaptureInvoice(assertNotNull(f.journal.entry).request().payload).invoice.lines.single()
            assertEquals(f.originalGoods.skuId, actual.goods.skuId)
            assertNotEquals(f.originalGoods.units.rate, actual.goods.units.rate)
            assertNull(actual.internalAllocationId)
            f.assertOriginalOrderTerms()
            f.assertNoDispatch()
        }
    }

    @Test
    fun sird01DocumentarySelectedContextRetainsBothDiscrepantSnapshots() = runTest(timeout = 5.seconds) {
        for (variant in listOf("DIFFERENT_ITEM_UNIT", "DIFFERENT_CONVERSION")) {
            val f = SupplierInvoiceCaptureFixture()
            val claim = FrozenSupplierInvoiceCaptureInputs.referencedClaim(variant)
            val form = f.currentForm(fromOrder = true)
            val choice = "DOCUMENTARY_ORDER_LINE:${f.originalAllocationId.value}"
            assertTrue(form.fields.filterIsInstance<LocalFormField.Lines>().single().columns
                .single { it.field == LocalField.MODE }.choices.any { it.code == choice })
            val intent = f.intent(form, claim, mode = choice, supplierOrderReference = "PO-000042",
                supplierAllocationReferences = listOf(f.originalAllocationId.value))
            assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
            f.assertRequest(assertNotNull(f.journal.entry).request(), intent, claim,
                internalAllocationId = f.originalAllocationId)
            val actual = LocalPurchasingCodec.decodeCaptureInvoice(assertNotNull(f.journal.entry).request().payload).invoice.lines.single()
            assertEquals(PurchaseInvoiceEntryProvenanceV2.DOCUMENTARY, actual.provenance)
            assertNotEquals(f.originalGoods.units.rate, actual.goods.units.rate)
            if (variant == "DIFFERENT_ITEM_UNIT") {
                assertNotEquals(f.originalGoods.skuId, actual.goods.skuId)
                assertNotEquals(f.originalGoods.units.purchaseUnitId, actual.goods.units.purchaseUnitId)
            } else assertEquals(f.originalGoods.skuId, actual.goods.skuId)
            f.assertOriginalOrderTerms()
            f.assertNoDispatch()
        }
        // DTO presence is controlled; real REF-A ownership/remaining allowance is not qualified here.
    }

    @Test
    fun sird01ControlledInvoiceReadSeparatesOriginalPoAndActualTermsWithOrWithoutCosts() = runTest(timeout = 5.seconds) {
        for (variant in listOf("DIFFERENT_ITEM_UNIT", "DIFFERENT_CONVERSION")) {
            val f = SupplierInvoiceCaptureFixture()
            val claim = FrozenSupplierInvoiceCaptureInputs.referencedClaim(variant)
            val intent = f.intent(f.currentForm(fromOrder = true), claim,
                mode = "DOCUMENTARY_ORDER_LINE:${f.originalAllocationId.value}",
                supplierOrderReference = "PO-000042", supplierAllocationReferences = listOf(f.originalAllocationId.value))
            assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
            val returned = assertIs<LocalOperationResult.Recorded>(f.gateway.submit(intent.operationId))
            f.assertOriginalResult(returned, assertNotNull(f.endpoint.found), assertNotNull(f.journal.entry))
            val invoiceId = assertNotNull(returned.result).reference.id
            var shown: LocalWorkflowPage? = null
            for (costs in listOf(true, false)) {
                val page = f.capturedProjectionPage(invoiceId, costs)
                assertEquals(LocalPurchasingProfile.READ_INVOICES, f.endpoint.reads.last().query)
                val decoded = LocalPurchasingCodec.invoicePage(f.endpoint.readReplies.last().payload)
                assertEquals(costs, decoded.includesCosts)
                val document = assertNotNull(decoded.documents).single()
                assertNull(document.legacy)
                f.assertCaptureProjection(assertNotNull(document.capture), intent, claim, costs,
                    internalAllocationId = f.originalAllocationId)
                val invoice = page.records.single { it.reference.kind == LocalRecordKind.PURCHASE_INVOICE }
                assertEquals(LocalInvoiceEntryKind.CAPTURE_ONLY_V2, invoice.invoiceEntryKind)
                assertEquals(f.orderId.value, captureReadText(invoice, "Selected original PO — context, not approval"))
                assertEquals(f.orderRevision.value.toString(), captureReadText(invoice, fieldLabel(LocalField.EXPECTED_ORDER_REVISION)))
                val line = page.records.single { it.reference.kind == LocalRecordKind.PURCHASE_LINE }
                assertEquals(intent.lines.single().lineId, line.reference.id)
                assertFalse(line.selectable)
                assertEquals(f.originalAllocationId.value, captureReadText(line, "Selected original allocation — context only"))
                assertEquals(f.originalAllocationId.value, captureReadText(line, fieldLabel(LocalField.SUPPLIER_ALLOCATION_REFERENCE)))
                assertEquals(claim.lines.single().rate, captureReadText(line, "Actual documented conversion"))
                assertEquals(FrozenSupplierInvoiceCaptureInputs.originalLine.rate, captureReadText(line, "Original PO conversion"))
                assertEquals(f.originalGoods.skuId.value, captureReadText(line, "Original PO item — comparison only"))
                assertTrue(page.forms.isEmpty())
                if (costs) {
                    assertEquals("${FrozenSupplierInvoiceCaptureInputs.moneyDecimal(claim.minor)} EGP",
                        captureReadText(line, fieldLabel(LocalField.NET_AMOUNT)))
                    assertEquals("${FrozenSupplierInvoiceCaptureInputs.moneyDecimal(FrozenSupplierInvoiceCaptureInputs.originalMinor)} EGP",
                        captureReadText(line, "Original PO net"))
                    shown = page
                } else {
                    assertCaptureMoneyLabelsAbsent(line)
                    assertCaptureOperationalTextPreserved(assertNotNull(shown), page)
                }
            }
            assertEquals(1, f.endpoint.executions.size)
            f.assertOriginalOrderTerms()
        }
        // Original and actual rows are controlled projections, not persistent-history qualification.
        // Empty discrepancy lists are supplied by the harness, not production discrepancy calculation.
    }

    @Test
    fun sidc03AndSird02InvalidNumericShapesCannotFallbackOrStagePartOfTheClaim() = runTest(timeout = 5.seconds) {
        for (variant in listOf("ZERO_DENOMINATOR", "ZERO_QUANTITY", "UNSUPPORTED_PRECISION")) {
            val f = SupplierInvoiceCaptureFixture()
            val claim = FrozenSupplierInvoiceCaptureInputs.captureClaim(
                if (variant == "UNSUPPORTED_PRECISION") "UNALLOCATED_MIXED" else "OTHER_UNIT")
            val entered = f.intent(f.currentForm(fromOrder = true), claim)
            val invalid = when (variant) {
                "ZERO_DENOMINATOR" -> replaceCaptureLineInput(entered, LocalField.PURCHASE_CONVERSION_RATE, LocalScalarValue.Text("6/0"))
                "ZERO_QUANTITY" -> replaceCaptureLineInput(entered, LocalField.QUANTITY, LocalScalarValue.Decimal("0"))
                else -> replaceCaptureLineInput(entered, LocalField.QUANTITY, LocalScalarValue.Decimal("2.5001"), index = 1)
            }
            val originalRows = invalid.lines.map { it.values }
            val rejected = assertIs<LocalStageResult.NotStaged>(f.gateway.stage(invalid))
            assertEquals(LocalServiceProblem.INVALID_INPUT, rejected.problem)
            assertEquals(originalRows, invalid.lines.map { it.values })
            assertNull(f.journal.entry)
            f.assertOriginalOrderTerms()
            f.assertNoDispatch()
        }
        val f = SupplierInvoiceCaptureFixture()
        val claim = FrozenSupplierInvoiceCaptureInputs.referencedClaim("DIFFERENT_ITEM_UNIT")
        val entered = f.intent(f.currentForm(fromOrder = true), claim,
            mode = "DOCUMENTARY_ORDER_LINE:${f.originalAllocationId.value}")
        val invalid = replaceCaptureLineInput(entered, LocalField.PURCHASE_CONVERSION_RATE, LocalScalarValue.Text("5/0"))
        assertEquals(LocalServiceProblem.INVALID_INPUT, assertIs<LocalStageResult.NotStaged>(f.gateway.stage(invalid)).problem)
        assertNull(f.journal.entry)
        f.assertNoDispatch()
        // Dimensional/product/company/branch qualification still requires the real protected boundary.
    }

    @Test
    fun sidc04CaptureOnlyPolicyIsDistinctFromPurchaseApprovalAndCachedFormAccess() = runTest(timeout = 5.seconds) {
        assertEquals(Permission(SecurityAction.PURCHASING_INVOICE_CAPTURE, PermissionVerb.PERFORM),
            actionPermission(LocalAction.SUPPLIER_INVOICE_CAPTURE))
        assertEquals(RequiredLimits.NONE, SecurityAction.PURCHASING_INVOICE_CAPTURE.requiredLimits)
        assertEquals(Permission(SecurityAction.PURCHASING_APPROVE, PermissionVerb.PERFORM),
            actionPermission(LocalAction.SUPPLIER_INVOICE_RECORD))
        val claim = FrozenSupplierInvoiceCaptureInputs.captureClaim("OTHER_UNIT")
        val allowed = SupplierInvoiceCaptureFixture()
        val page = allowed.currentPage(fromOrder = true)
        assertTrue(page.forms.none { it.action == LocalAction.SUPPLIER_INVOICE_RECORD })
        val intent = allowed.intent(page.forms.single { it.action == LocalAction.SUPPLIER_INVOICE_CAPTURE }, claim)
        assertIs<LocalStageResult.Staged>(allowed.gateway.stage(intent))
        allowed.assertRequest(assertNotNull(allowed.journal.entry).request(), intent, claim)
        allowed.assertNoDispatch()

        val denied = SupplierInvoiceCaptureFixture()
        val previouslyOpen = denied.intent(denied.currentForm(fromOrder = true), claim)
        denied.endpoint.currentReply = LocalSessionViewResult.Current(denied.view(capture = false, approve = true))
        assertTrue(denied.currentPage(fromOrder = true).forms.none { it.action == LocalAction.SUPPLIER_INVOICE_CAPTURE })
        assertEquals(LocalServiceProblem.DENIED, assertIs<LocalStageResult.NotStaged>(denied.gateway.stage(previouslyOpen)).problem)
        assertNull(denied.journal.entry)
        denied.assertNoDispatch()
        // A current-view hint is not proof of activated Master permission or a commit race.
    }

    @Test
    fun sird02MissingSelectedLineCannotBeSilentlyDroppedIntoUnallocatedCapture() = runTest(timeout = 5.seconds) {
        val f = SupplierInvoiceCaptureFixture()
        val claim = FrozenSupplierInvoiceCaptureInputs.referencedClaim("DIFFERENT_ITEM_UNIT")
        val intent = f.intent(f.currentForm(fromOrder = true), claim,
            mode = "DOCUMENTARY_ORDER_LINE:${DomainId.random().value}")
        val originalValues = intent.lines.single().values
        assertEquals(LocalServiceProblem.INVALID_INPUT, assertIs<LocalStageResult.NotStaged>(f.gateway.stage(intent)).problem)
        assertEquals(originalValues, intent.lines.single().values)
        assertNull(f.journal.entry)
        f.assertOriginalOrderTerms()
        f.assertNoDispatch()
        // This is absence in a controlled selected-PO DTO, not genuine target/scope enforcement.
    }

    @Test
    fun sidc08ExplicitConformingCaptureSelectsOnlyTheReviewedOriginalIdentityAndUnits() = runTest(timeout = 5.seconds) {
        val f = SupplierInvoiceCaptureFixture()
        val claim = FrozenSupplierInvoiceCaptureInputs.captureClaim("CONFORMING")
        val intent = f.intent(f.currentForm(fromOrder = true), claim,
            mode = "ORDER_LINE:${f.originalAllocationId.value}")
        assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
        f.assertRequest(assertNotNull(f.journal.entry).request(), intent, claim,
            provenance = PurchaseInvoiceEntryProvenanceV2.SELECTED_ORDER_LINE,
            internalAllocationId = f.originalAllocationId)
        f.assertOriginalOrderTerms()
        f.assertNoDispatch()
    }

    @Test
    fun sidc08LegacyConformingCommandBytesAndOriginalResultSemanticsRemainV1() = runTest(timeout = 5.seconds) {
        val f = SupplierInvoiceConformingFixture()
        val intent = f.intent(f.currentForm(), version = 1)
        assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
        val original = assertNotNull(f.journal.entry)
        val payload = original.request().payload
        f.assertConformingRequest(original.request(), intent)
        assertNotEquals(LocalPurchasingProfile.CAPTURE_INVOICE, original.request().command)
        assertEquals(1, original.request().command.version)
        assertEquals(payload, LocalPurchasingCodec.invoice(LocalPurchasingCodec.decodeInvoice(payload)))
        val legacyInvoice = FrozenSupplierInvoiceCaptureInputs.json.parseToJsonElement(payload.copyBytes().decodeToString())
            .jsonObject.getValue("invoice").jsonObject
        assertTrue(legacyInvoice.containsKey("orderId"))
        assertFalse(legacyInvoice.containsKey("internalOrderLink"))
        assertFalse(legacyInvoice.containsKey("supplierOrderReference"))
        val restored = f.journal.codecRoundTrip()
        f.endpoint.rejectReads = true
        val returned = assertIs<LocalOperationResult.Recorded>(f.newGateway(restored).submit(intent.operationId))
        f.assertOriginalResult(returned, assertNotNull(f.endpoint.found), assertNotNull(restored.entry))
        assertEquals(original.request().payload, assertNotNull(restored.entry).request().payload)
        assertEquals(original.formVersion, assertNotNull(restored.entry).formVersion)
        assertEquals(original.owner, assertNotNull(restored.entry).owner)
        assertEquals(1, f.endpoint.executions.size)
        // Reuses unchanged original frozen wiring; this is not a migration or database reopen.
    }

    @Test
    fun sidc08UnsupportedCaptureFormVersionsCannotInventAcceptingLegacyDefaults() = runTest(timeout = 5.seconds) {
        for (version in listOf(2, 1000)) {
            val f = SupplierInvoiceCaptureFixture()
            val entered = f.intent(f.currentForm(), FrozenSupplierInvoiceCaptureInputs.captureClaim("UNALLOCATED_MIXED"))
            val unsupported = LocalCommandIntent(entered.operationId, entered.action, version, entered.reference,
                entered.values, entered.lineField, entered.lines)
            assertEquals(LocalServiceProblem.INVALID_INPUT, assertIs<LocalStageResult.NotStaged>(f.gateway.stage(unsupported)).problem)
            assertNull(f.journal.entry)
            f.assertNoDispatch()
        }
    }
}

private fun captureReadText(record: LocalRecord, label: LocalText): String =
    record.values.single { it.label == label }.value.english

private fun captureReadText(record: LocalRecord, englishLabel: String): String =
    record.values.single { it.label.english == englishLabel }.value.english

private fun captureMoneyLabel(label: LocalText): Boolean = label in listOf(
    fieldLabel(LocalField.UNIT_PRICE), fieldLabel(LocalField.DISCOUNT_PERCENT), fieldLabel(LocalField.TAX_RATE),
    fieldLabel(LocalField.NET_AMOUNT), fieldLabel(LocalField.TAX_AMOUNT)) || label.english in listOf(
    "Original supplier claim — not an approval", "Original PO unit price", "Original PO net", "Original PO tax")

private fun assertCaptureMoneyLabelsAbsent(record: LocalRecord) {
    assertTrue(record.values.none { captureMoneyLabel(it.label) })
}

private fun assertCaptureOperationalTextPreserved(shown: LocalWorkflowPage, hidden: LocalWorkflowPage) {
    assertEquals(shown.records.map { it.reference }, hidden.records.map { it.reference })
    for (index in shown.records.indices) {
        val before = shown.records[index]
        val after = hidden.records[index]
        assertEquals(before.title, after.title)
        assertEquals(before.status, after.status)
        assertEquals(before.supplierId, after.supplierId)
        assertEquals(before.invoiceEntryKind, after.invoiceEntryKind)
        assertEquals(before.values.filterNot { captureMoneyLabel(it.label) }, after.values)
    }
}
