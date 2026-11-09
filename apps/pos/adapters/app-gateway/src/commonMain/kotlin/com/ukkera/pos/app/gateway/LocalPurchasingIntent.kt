package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.inventory.*
import com.ukkera.pos.types.*

/** Fresh preparation only. A retained request is recovered/submitted without re-entering this mapper. */
internal suspend fun preparePurchasing(access: GatewayAccess, intent: LocalCommandIntent): MutationRequest {
    val input = InputValues(intent.values)
    val request = when (intent.action) {
        LocalAction.PURCHASE_APPROVE -> preparePurchaseOrder(access, intent, input)
        LocalAction.SUPPLIER_INVOICE_RECORD -> prepareSupplierInvoice(access, intent, input)
        LocalAction.SUPPLIER_INVOICE_CAPTURE -> prepareSupplierInvoiceCapture(access, intent, input)
        LocalAction.SUPPLIER_INVOICE_MATCH -> prepareSupplierMatch(access, intent, input)
        LocalAction.SUPPLIER_CREDIT_RECORD -> prepareSupplierCredit(access, intent, input)
        LocalAction.SUPPLIER_ADVANCE_ALLOCATE -> prepareSupplierAdvanceAllocation(access, intent, input)
        LocalAction.SUPPLIER_OPENING_RECORD -> prepareOpeningSupplierObligation(access, intent, input)
        else -> invalid()
    }
    input.finish()
    // Enforce the actual production command bound before creating a retained journal entry.
    return MutationRequest(intent.operationId, request.command,
        normalizeLocalRequest(request.command, request.payload))
}

private suspend fun preparePurchaseOrder(access: GatewayAccess, intent: LocalCommandIntent, input: InputValues): MutationRequest {
    val id = input.id(LocalField.PURCHASE_ID)
    if (intent.reference != null) invalid(LocalField.PURCHASE_ID)
    access.requireAction(intent.action, id)
    val supplier = input.reference(LocalField.SUPPLIER_ID, LocalRecordKind.SUPPLIER)
    requirePurchasingSelection(supplier, LocalField.SUPPLIER_ID)
    val supplierRow = purchasingSupplier(access, supplier.id, supplier.revision)
    val lines = mutableListOf<PurchaseOrderLineV1>()
    val charges = mutableListOf<PurchaseChargeV1>()
    purchasingRows(intent).forEach { row ->
        val line = InputValues(row.values)
        when (line.choice(LocalField.MODE)) {
            "GOODS" -> {
                val sku = line.reference(LocalField.SKU_ID, LocalRecordKind.SKU)
                requirePurchasingSelection(sku, LocalField.SKU_ID)
                val (detail, item) = skuDetail(access, sku.id)
                if (item.revision != sku.revision || detail.skuRevision != sku.revision || !detail.active ||
                    item.active != true || !detail.canPurchase || detail.tracking == LocalTrackingV1.NONE) invalid(LocalField.SKU_ID)
                val warehouse = line.reference(LocalField.WAREHOUSE_ID, LocalRecordKind.WAREHOUSE)
                requirePurchasingSelection(warehouse, LocalField.WAREHOUSE_ID)
                val destination = configurationRead(access,
                    LocalConfigurationQueryV1(LocalConfigurationProfile.WAREHOUSE, id = warehouse.id)).page
                val destinationRow = destination.items.singleOrNull() ?: invalid(LocalField.WAREHOUSE_ID)
                val destinationDetail = destination.detail as? LocalConfigurationDetailV1.Warehouse ?: invalid(LocalField.WAREHOUSE_ID)
                if (destinationRow.id != warehouse.id || destinationDetail.value.id != warehouse.id) unavailable()
                if (destinationRow.revision != warehouse.revision || destinationRow.active != true || !destinationDetail.value.active)
                    invalid(LocalField.WAREHOUSE_ID)
                val conversionId = line.optionalId(LocalField.CONVERSION_ID)
                val conversion = conversionId?.let { selected -> detail.conversions.singleOrNull { it.id == selected }
                    ?: invalid(LocalField.CONVERSION_ID) }
                val units = PurchaseUnitsV1(detail.productId, conversion?.sourceUnitId ?: detail.baseUnitId,
                    detail.baseUnitId, detail.quantityScale, conversion?.id, conversion?.revision,
                    conversion?.rate ?: UnitConversionRate.ratio(1, 1), conversion?.dimensionalEvidenceId)
                val goods = purchaseGoods(line, sku.id, units)
                if (detail.tracking == LocalTrackingV1.SERIAL) goods.baseQuantity().requireWholeUnits()
                val costBasis = enumChoice<PurchaseReceiptCostBasisV1>(line.choice(LocalField.RECEIPT_COST_BASIS), LocalField.RECEIPT_COST_BASIS)
                val inspection = when (line.choice(LocalField.INSPECTION_REQUIRED)) {
                    "YES" -> true
                    "NO" -> false
                    else -> invalid(LocalField.INSPECTION_REQUIRED)
                }
                lines += PurchaseOrderLineV1(row.lineId, warehouse.id, costBasis, inspection,
                    line.optionalDate(LocalField.REQUESTED_DELIVERY), goods)
            }
            "CHARGE" -> charges += purchaseCharge(row.lineId, line)
            else -> invalid(LocalField.MODE)
        }
        line.finish() // Wrong-kind inputs are rejected, never silently discarded.
    }
    val order = PurchaseOrderV1(id, supplier.id, input.text(LocalField.EXTERNAL_REFERENCE), input.date(LocalField.BUSINESS_DATE),
        input.optionalDate(LocalField.EXPECTED_DELIVERY), input.text(LocalField.PAYMENT_TERMS), input.instant(LocalField.VALID_FROM),
        input.optionalInstant(LocalField.VALID_UNTIL), lines, charges)
    return LocalPurchasingCommands.approve(intent.operationId,
        LocalApprovePurchaseOrderV1(order, supplierRow.storageRevision, input.text(LocalField.REASON)))
}

private suspend fun prepareSupplierInvoice(access: GatewayAccess, intent: LocalCommandIntent, input: InputValues): MutationRequest {
    if (intent.formVersion !in 1..2) invalid()
    val id = input.id(LocalField.INVOICE_ID)
    access.requireAction(intent.action, id)
    val orderId = input.id(LocalField.PURCHASE_ID)
    requirePurchasingDocumentSelection(intent.reference, LocalRecordKind.PURCHASE, orderId, LocalField.PURCHASE_ID)
    val expectedOrder = input.revision(LocalField.EXPECTED_ORDER_REVISION)
    val order = purchasingDocumentRead(access, LocalPurchasingSectionV1.ORDER, orderId)
    if (order.id != orderId || order.orderId != orderId) unavailable()
    if (order.revision != expectedOrder) invalid(LocalField.EXPECTED_ORDER_REVISION)
    val supplierId = input.id(LocalField.SUPPLIER_ID)
    if (supplierId != order.supplierId) invalid(LocalField.SUPPLIER_ID)
    val supplier = purchasingSupplier(access, supplierId)
    val lines = mutableListOf<PurchaseInvoiceLineV1>()
    val charges = mutableListOf<PurchaseChargeV1>()
    purchasingRows(intent).forEach { row ->
        val line = InputValues(row.values)
        val kind = line.choice(LocalField.MODE)
        when {
            kind == "CHARGE" -> charges += purchaseCharge(row.lineId, line)
            intent.formVersion == 1 && kind == "GOODS" -> {
                val selection = line.reference(LocalField.ALLOCATION_ID, LocalRecordKind.PURCHASE_LINE)
                if (selection.parentId != orderId || selection.revision != null) invalid(LocalField.ALLOCATION_ID)
                val original = order.lines.singleOrNull { it.allocationId == selection.id } ?: invalid(LocalField.ALLOCATION_ID)
                // Select only the immutable units/allocation. NEVER copy PO amounts over a supplier claim.
                lines += PurchaseInvoiceLineV1(row.lineId, original.allocationId, purchaseGoods(line, original.skuId, original.units))
            }
            intent.formVersion == 2 && kind.startsWith(supplierInvoiceOrderedLinePrefix) -> {
                val selected = try { DomainId(kind.removePrefix(supplierInvoiceOrderedLinePrefix)) }
                catch (_: IllegalArgumentException) { invalid(LocalField.MODE) }
                val original = order.lines.singleOrNull { it.allocationId == selected } ?: invalid(LocalField.MODE)
                lines += PurchaseInvoiceLineV1(row.lineId, original.allocationId, purchaseGoods(line, original.skuId, original.units))
            }
            intent.formVersion == 2 && kind == "DOCUMENTARY_GOODS" -> {
                // This reference is a supplier claim, not an approved order-line selection.
                // Preserve an actual unmatched reference; never generate an allocation or
                // silently use a PO/catalog snapshot to make the invoice match.
                val allocation = line.id(LocalField.ALLOCATION_ID)
                lines += PurchaseInvoiceLineV1(row.lineId, allocation, documentaryInvoiceGoods(line))
            }
            else -> invalid(LocalField.MODE)
        }
        line.finish()
    }
    val invoice = PurchaseInvoiceV1(id, orderId, supplierId, input.text(LocalField.EXTERNAL_REFERENCE),
        input.date(LocalField.BUSINESS_DATE), input.date(LocalField.DUE_DATE), input.id(LocalField.EVIDENCE_ID), lines, charges)
    return LocalPurchasingCommands.invoice(intent.operationId,
        LocalRecordSupplierInvoiceV1(invoice, expectedOrder, supplier.storageRevision, input.text(LocalField.REASON)))
}

private suspend fun prepareSupplierInvoiceCapture(access: GatewayAccess, intent: LocalCommandIntent, input: InputValues): MutationRequest {
    if (intent.formVersion != 1) invalid()
    val id = input.id(LocalField.INVOICE_ID)
    access.requireAction(intent.action, id)
    val orderId = input.optionalId(LocalField.PURCHASE_ID)
    val expectedOrder = input.optionalRevision(LocalField.EXPECTED_ORDER_REVISION)
    if ((orderId == null) != (expectedOrder == null)) invalid(LocalField.EXPECTED_ORDER_REVISION)
    if (intent.reference != null) requirePurchasingDocumentSelection(intent.reference, LocalRecordKind.PURCHASE,
        orderId ?: invalid(LocalField.PURCHASE_ID), LocalField.PURCHASE_ID)
    val order = orderId?.let { purchasingDocumentRead(access, LocalPurchasingSectionV1.ORDER, it) }
    if (order != null && (order.id != orderId || order.orderId != orderId)) unavailable()
    if (order != null && order.revision != expectedOrder) invalid(LocalField.EXPECTED_ORDER_REVISION)
    val supplier = if (intent.reference == null) {
        val selection = input.reference(LocalField.SUPPLIER_ID, LocalRecordKind.SUPPLIER)
        requirePurchasingSelection(selection, LocalField.SUPPLIER_ID)
        purchasingSupplier(access, selection.id, selection.revision)
    } else purchasingSupplier(access, input.id(LocalField.SUPPLIER_ID))
    if (order != null && order.supplierId != supplier.id) invalid(LocalField.SUPPLIER_ID)
    val lines = mutableListOf<PurchaseInvoiceCaptureLineV2>()
    val charges = mutableListOf<PurchaseChargeV1>()
    purchasingRows(intent).forEach { row ->
        val line = InputValues(row.values)
        val mode = line.choice(LocalField.MODE)
        when {
            mode == "CHARGE" -> charges += purchaseCharge(row.lineId, line)
            mode == "DOCUMENTARY_GOODS" -> {
                val reference = line.optionalText(LocalField.SUPPLIER_ALLOCATION_REFERENCE)
                lines += PurchaseInvoiceCaptureLineV2(lineId = row.lineId, goods = documentaryInvoiceGoods(line),
                    provenance = PurchaseInvoiceEntryProvenanceV2.DOCUMENTARY, supplierAllocationReference = reference)
            }
            mode.startsWith(supplierInvoiceDocumentaryOrderLinePrefix) -> {
                val selected = try { DomainId(mode.removePrefix(supplierInvoiceDocumentaryOrderLinePrefix)) }
                catch (_: IllegalArgumentException) { invalid(LocalField.MODE) }
                val original = order?.lines?.singleOrNull { it.allocationId == selected } ?: invalid(LocalField.MODE)
                val reference = line.optionalText(LocalField.SUPPLIER_ALLOCATION_REFERENCE)
                lines += PurchaseInvoiceCaptureLineV2(lineId = row.lineId, goods = documentaryInvoiceGoods(line),
                    provenance = PurchaseInvoiceEntryProvenanceV2.DOCUMENTARY,
                    internalAllocationId = original.allocationId, supplierAllocationReference = reference)
            }
            mode.startsWith(supplierInvoiceOrderedLinePrefix) -> {
                val selected = try { DomainId(mode.removePrefix(supplierInvoiceOrderedLinePrefix)) }
                catch (_: IllegalArgumentException) { invalid(LocalField.MODE) }
                val original = order?.lines?.singleOrNull { it.allocationId == selected } ?: invalid(LocalField.MODE)
                val reference = line.optionalText(LocalField.SUPPLIER_ALLOCATION_REFERENCE)
                lines += PurchaseInvoiceCaptureLineV2(lineId = row.lineId,
                    goods = purchaseGoods(line, original.skuId, original.units),
                    provenance = PurchaseInvoiceEntryProvenanceV2.SELECTED_ORDER_LINE,
                    internalAllocationId = original.allocationId, supplierAllocationReference = reference)
            }
            else -> invalid(LocalField.MODE)
        }
        line.finish()
    }
    val invoice = PurchaseInvoiceCaptureV2(id = id, supplierId = supplier.id,
        reference = input.text(LocalField.EXTERNAL_REFERENCE), invoiceDate = input.date(LocalField.BUSINESS_DATE),
        dueDate = input.date(LocalField.DUE_DATE), sourceEvidenceId = input.id(LocalField.EVIDENCE_ID),
        lines = lines, charges = charges,
        internalOrderLink = order?.let { PurchaseInvoiceOrderLinkV2(it.id, it.revision) },
        supplierOrderReference = input.optionalText(LocalField.SUPPLIER_ORDER_REFERENCE))
    return LocalPurchasingCommands.captureInvoice(intent.operationId,
        LocalCaptureSupplierInvoiceV2(invoice, supplier.storageRevision, input.text(LocalField.REASON)))
}

private fun documentaryInvoiceGoods(input: InputValues): PurchaseGoodsV1 {
    val sku = input.id(LocalField.SKU_ID)
    val product = input.id(LocalField.PRODUCT_ID)
    val purchaseUnit = input.id(LocalField.UNIT)
    val baseUnit = input.id(LocalField.PURCHASE_BASE_UNIT)
    val baseScale = input.integer(LocalField.PURCHASE_BASE_SCALE)
    if (baseScale !in 0L..6L) invalid(LocalField.PURCHASE_BASE_SCALE)
    val conversion = input.optionalId(LocalField.CONVERSION_ID)
    val conversionRevision = input.optionalRevision(LocalField.PURCHASE_CONVERSION_REVISION)
    val rate = try { UnitConversionRate.parseCanonical(input.text(LocalField.PURCHASE_CONVERSION_RATE)) }
    catch (_: IllegalArgumentException) { invalid(LocalField.PURCHASE_CONVERSION_RATE) }
    val dimensionalEvidence = input.optionalId(LocalField.EVIDENCE_ID)
    if ((conversion == null) != (conversionRevision == null)) invalid(LocalField.PURCHASE_CONVERSION_REVISION)
    if (purchaseUnit == baseUnit) {
        if (conversion != null || dimensionalEvidence != null) invalid(LocalField.CONVERSION_ID)
        if (rate != UnitConversionRate.ratio(1, 1)) invalid(LocalField.PURCHASE_CONVERSION_RATE)
    } else if (conversion == null) invalid(LocalField.CONVERSION_ID)
    val units = PurchaseUnitsV1(product, purchaseUnit, baseUnit, baseScale.toInt(), conversion,
        conversionRevision, rate, dimensionalEvidence)
    return purchaseGoods(input, sku, units)
}

private suspend fun prepareSupplierMatch(access: GatewayAccess, intent: LocalCommandIntent, input: InputValues): MutationRequest {
    if (intent.lineField != null || intent.lines.isNotEmpty()) invalid(LocalField.LINES)
    val matchId = input.id(LocalField.MATCH_ID)
    val invoiceId = input.id(LocalField.INVOICE_ID)
    requirePurchasingDocumentSelection(intent.reference, LocalRecordKind.PURCHASE_INVOICE, invoiceId, LocalField.INVOICE_ID)
    access.requireAction(intent.action, invoiceId)
    val invoiceRevision = input.revision(LocalField.EXPECTED_STORAGE_REVISION)
    val progressRevision = input.revision(LocalField.EXPECTED_PROGRESS_REVISION)
    val uncontested = when (intent.formVersion) {
        1 -> false // Saved/fresh original forms remain exact-terms-only and cannot supply a mode.
        2 -> when (input.choice(LocalField.MODE)) {
            "EXACT_TERMS" -> false
            "UNCONTESTED_PRICE_INCREASE" -> true
            else -> invalid(LocalField.MODE)
        }
        else -> invalid()
    }
    val line = input.reference(LocalField.INVOICE_LINE_ID, LocalRecordKind.PURCHASE_LINE)
    if (line.parentId != invoiceId || line.revision != null) invalid(LocalField.INVOICE_LINE_ID)
    val query = LocalPurchasingMatchSourceQueryV1(invoiceId, line.id, input.id(LocalField.RECEIPT_ID), input.id(LocalField.RECEIPT_LINE_ID))
    val enteredQuantity = input.decimal(LocalField.QUANTITY)
    var reviewed: PurchaseUncontestedAmountsV1? = null
    var quotedQuantity: Quantity? = null
    val source = if (!uncontested) {
        val reply = access.read(ReadRequest(LocalPurchasingProfile.READ_MATCH_SOURCE, LocalPurchasingCodec.matchSource(query)))
        LocalPurchasingCodec.matchSourceResult(reply.payload)
    } else {
        // Only the chosen immutable invoice supplies units. The actual source read quotes
        // both money streams and all live capacities/revisions; this is not an approval.
        val invoice = purchasingDocumentRead(access, LocalPurchasingSectionV1.INVOICE, invoiceId, costs = true)
        if (invoice.revision != invoiceRevision) invalid(LocalField.EXPECTED_STORAGE_REVISION)
        if (invoice.progressRevision != progressRevision) invalid(LocalField.EXPECTED_PROGRESS_REVISION)
        val original = invoice.lines.singleOrNull { it.lineId == line.id } ?: invalid(LocalField.INVOICE_LINE_ID)
        val quantity = Quantity.parse(original.baseQuantity.unitId, enteredQuantity, original.baseQuantity.scale).requirePositive()
        quotedQuantity = quantity
        val requested = LocalPurchasingUncontestedMatchSourceQueryV1(query, quantity)
        val reply = access.read(ReadRequest(LocalPurchasingProfile.READ_UNCONTESTED_MATCH_SOURCE, LocalPurchasingCodec.uncontestedMatchSource(requested)))
        val quote = LocalPurchasingCodec.uncontestedMatchSourceResult(reply.payload)
        if (quote.query != requested || quote.source.query != query) unavailable()
        reviewed = quote.amounts
        quote.source
    }
    if (source.query != query) unavailable()
    requirePurchasingAvailable(source.availability)
    if (source.sourcePresent != true || source.sourceId == null) invalid(LocalField.RECEIPT_LINE_ID)
    if (source.sourceAndTermsCompatible != true) invalid(LocalField.RECEIPT_LINE_ID)
    if (source.invoiceRevision != invoiceRevision) invalid(LocalField.EXPECTED_STORAGE_REVISION)
    if (source.progressRevision != progressRevision) invalid(LocalField.EXPECTED_PROGRESS_REVISION)
    val remaining = source.invoiceRemaining ?: unavailable()
    val orderRemaining = source.orderRemaining ?: unavailable()
    val receiptRemaining = source.acceptedReceiptRemaining ?: unavailable()
    if (listOf(remaining, orderRemaining, receiptRemaining).any {
        it.unitId != remaining.unitId || it.scale != remaining.scale || it.scaled < 0
    }) unavailable()
    val quantity = quotedQuantity ?: Quantity.parse(remaining.unitId, enteredQuantity, remaining.scale).requirePositive()
    if (quantity.unitId != remaining.unitId || quantity.scale != remaining.scale ||
        quantity > remaining || quantity > orderRemaining || quantity > receiptRemaining) invalid(LocalField.QUANTITY)
    val request = LocalMatchSupplierInvoiceV1(
        matchId, invoiceId, line.id, invoiceRevision, progressRevision,
        source.orderMatchRevision ?: unavailable(), source.orderBillingRevision ?: unavailable(), source.payableRevision,
        query.receiptId, query.receiptLineId, source.receiptRevision ?: unavailable(), source.receiptBillingRevision,
        quantity, input.text(LocalField.REASON))
    return if (!uncontested) LocalPurchasingCommands.match(intent.operationId, request)
        else LocalPurchasingCommands.matchUncontested(intent.operationId,
            LocalMatchUncontestedSupplierInvoiceV1(request, reviewed ?: invalid(LocalField.QUANTITY)))
}

private fun purchasingRows(intent: LocalCommandIntent): List<LocalInputLine> {
    if (intent.lineField != LocalField.LINES || intent.lines.size !in 1..24) invalid(LocalField.LINES)
    return intent.lines
}

private fun purchaseGoods(input: InputValues, skuId: DomainId, units: PurchaseUnitsV1): PurchaseGoodsV1 {
    val scale = input.integer(LocalField.QUANTITY_SCALE)
    if (scale !in 0L..6L) invalid(LocalField.QUANTITY_SCALE)
    return PurchaseGoodsV1(skuId, input.optionalText(LocalField.SUPPLIER_ITEM_REFERENCE).orEmpty(), units,
        Quantity.parse(units.purchaseUnitId, input.decimal(LocalField.QUANTITY), scale.toInt()).requirePositive(),
        UnitPrice.parse(Currency.EGP, input.decimal(LocalField.UNIT_PRICE)), PercentageRate.parse(input.decimal(LocalField.DISCOUNT_PERCENT)),
        PurchaseTaxV1(input.id(LocalField.TAX_SOURCE_ID), input.revision(LocalField.TAX_REVISION), input.text(LocalField.TAX_CATEGORY),
            PercentageRate.parse(input.decimal(LocalField.TAX_RATE))), input.money(LocalField.NET_AMOUNT), input.money(LocalField.TAX_AMOUNT))
}

private fun purchaseCharge(id: DomainId, input: InputValues): PurchaseChargeV1 = PurchaseChargeV1(id,
    input.text(LocalField.CHARGE_CODE), input.id(LocalField.EVIDENCE_ID),
    enumChoice<PurchaseChargeTreatmentV1>(input.choice(LocalField.COST_TREATMENT), LocalField.COST_TREATMENT),
    input.money(LocalField.NET_AMOUNT), input.money(LocalField.TAX_AMOUNT))

private fun requirePurchasingSelection(value: LocalRecordReference, field: LocalField) {
    if (value.revision == null || value.parentId != null) invalid(field)
}

internal fun requirePurchasingDocumentSelection(value: LocalRecordReference?, kind: LocalRecordKind, id: DomainId, field: LocalField) {
    if (value?.kind != kind || value.id != id || value.revision != null || value.parentId != null) invalid(field)
}
