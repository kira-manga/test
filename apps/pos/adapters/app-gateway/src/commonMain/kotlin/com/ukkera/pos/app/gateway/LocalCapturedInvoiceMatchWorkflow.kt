package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.types.*

/** Fresh source selection only. Retries use the durably retained command, not these reads. */
internal suspend fun prepareCapturedInvoiceMatch(access: GatewayAccess, intent: LocalCommandIntent): PreparedLocalIntent {
    if (intent.action != LocalAction.SUPPLIER_INVOICE_MATCH_RECOGNIZE || intent.formVersion != 1 ||
        intent.lineField != null || intent.lines.isNotEmpty()) invalid()
    val input = InputValues(intent.values)
    val invoiceId = input.id(LocalField.INVOICE_ID)
    requirePurchasingDocumentSelection(intent.reference, LocalRecordKind.PURCHASE_INVOICE, invoiceId, LocalField.INVOICE_ID)
    access.requireAction(intent.action, invoiceId)
    val invoiceRevision = input.revision(LocalField.EXPECTED_STORAGE_REVISION)
    val expectedProgress = input.optionalRevision(LocalField.EXPECTED_PROGRESS_REVISION)
    val line = input.reference(LocalField.INVOICE_LINE_ID, LocalRecordKind.PURCHASE_LINE)
    if (line.parentId != invoiceId || line.revision != null) invalid(LocalField.INVOICE_LINE_ID)
    val invoice = purchasingInvoiceRead(access, LocalPurchasingQueryV1(LocalPurchasingSectionV1.INVOICE,
        id = invoiceId, includeCosts = true)).page.documents?.singleOrNull()?.capture ?: invalid(LocalField.INVOICE_ID)
    if (invoice.revision != invoiceRevision) invalid(LocalField.EXPECTED_STORAGE_REVISION)
    val selected = invoice.lines.singleOrNull { it.lineId == line.id } ?: invalid(LocalField.INVOICE_LINE_ID)
    val quantity = Quantity.parse(selected.baseQuantity.unitId, input.decimal(LocalField.QUANTITY),
        selected.baseQuantity.scale).requirePositive()
    val matchId = input.id(LocalField.MATCH_ID)
    val originalId = input.id(LocalField.EVIDENCE_ID)
    if (originalId != invoice.sourceEvidenceId) invalid(LocalField.EVIDENCE_ID)
    val query = LocalCapturedSupplierInvoiceMatchSourceQueryV2(
        invoiceId = invoiceId, invoiceLineId = line.id, expectedInvoiceRevision = invoiceRevision,
        originalDocumentId = originalId, expectedOriginalRevision = input.revision(LocalField.EXPECTED_SOURCE_REVISION),
        orderId = input.id(LocalField.PURCHASE_ID), expectedOrderRevision = input.revision(LocalField.EXPECTED_ORDER_REVISION),
        allocationId = input.id(LocalField.ALLOCATION_ID), receiptId = input.id(LocalField.RECEIPT_ID),
        receiptLineId = input.id(LocalField.RECEIPT_LINE_ID), expectedReceiptRevision = input.revision(LocalField.EXPECTED_RECEIPT_REVISION),
        quantity = quantity,
    )
    val reason = input.text(LocalField.REASON)
    input.finish()
    val source = LocalPurchasingCodec.capturedMatchSourceResult(access.read(ReadRequest(
        LocalPurchasingProfile.READ_CAPTURED_MATCH_SOURCE, LocalPurchasingCodec.capturedMatchSource(query))).payload)
    validateCapturedMatchSource(source, query, invoice, expectedProgress)
    val request = LocalMatchCapturedSupplierInvoiceV2(
        matchId = matchId, invoiceId = invoiceId, invoiceLineId = line.id, expectedInvoiceRevision = invoiceRevision,
        originalDocumentId = originalId, expectedOriginalRevision = query.expectedOriginalRevision,
        orderId = query.orderId, expectedOrderRevision = query.expectedOrderRevision, allocationId = query.allocationId,
        receiptId = query.receiptId, receiptLineId = query.receiptLineId, expectedReceiptRevision = query.expectedReceiptRevision,
        expectedProgressRevision = source.progressRevision, expectedOrderMatchRevision = source.orderMatchRevision ?: unavailable(),
        expectedOrderBillingRevision = source.orderBillingRevision ?: unavailable(),
        expectedReceiptBillingRevision = source.receiptBillingRevision, expectedPayableRevision = source.payableRevision,
        quantity = quantity, reasonCode = reason,
    )
    val prepared = LocalPurchasingCommands.matchCapturedInvoice(intent.operationId, request)
    val normalized = MutationRequest(prepared.operationId, prepared.command, normalizeLocalRequest(prepared.command, prepared.payload))
    val review = capturedInvoiceMatchIdentitySummary(request) + summaryGroup(
        text("Current exact source quote — no money paid", "عرض المصدر الدقيق الحالي — دون دفع"), listOf(
            text("Full original supplier claim / authorization ceiling basis", "مطالبة المورد الأصلية كاملة / أساس حد الصلاحية") to moneyText(source.originalInvoiceClaim ?: unavailable()),
            text("This match: net", "صافي هذه المطابقة") to moneyText(source.incrementNet ?: unavailable()),
            text("This match: purchase tax", "ضريبة شراء هذه المطابقة") to moneyText(source.incrementTax ?: unavailable()),
            text("Already recognized before this operation", "المثبت سابقًا قبل هذه العملية") to
                (source.recognizedPayable?.let(::moneyText) ?: text("No existing payable", "لا يوجد مستحق مثبت سابقًا")),
        ))
    return PreparedLocalIntent(normalized, actionLabel(intent.action), review)
}

/** UI defense only; all source qualification and final authority remain on the Master. */
private fun validateCapturedMatchSource(source: LocalCapturedSupplierInvoiceMatchSourceV2,
    query: LocalCapturedSupplierInvoiceMatchSourceQueryV2, invoice: LocalPurchasingInvoiceCaptureViewV2,
    expectedProgress: Revision?) {
    if (source.query != query) throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
    when (source.availability) {
        LocalPurchasingReadAvailabilityV1.AVAILABLE -> {
            if (source.code == LocalCapturedSupplierInvoiceMatchCodesV2.SOURCE_READY) {
                if (source.sourcePresent != true || source.sourceAndTermsCompatible != true)
                    throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
            } else {
                if (source.code !in LocalCapturedSupplierInvoiceMatchCodesV2.REFUSALS ||
                    source.sourceAndTermsCompatible != false || source.sourcePresent == null ||
                    source.incrementNet != null || source.incrementTax != null)
                    throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
                capturedMatchSourceRefusal(source, query)
            }
        }
        LocalPurchasingReadAvailabilityV1.HYDRATION_CAPACITY_UNAVAILABLE ->
            if (source.code != "purchasing.capacity_unavailable") throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
        LocalPurchasingReadAvailabilityV1.SOURCE_UNAVAILABLE ->
            if (source.code != "purchasing.captured_match_source_unavailable") throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
        LocalPurchasingReadAvailabilityV1.UNSUPPORTED_INVOICE_CAPTURE -> throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
    }
    requirePurchasingAvailable(source.availability)
    if (source.invoiceRevision != query.expectedInvoiceRevision || source.originalRevision != query.expectedOriginalRevision ||
        source.orderRevision != query.expectedOrderRevision || source.receiptRevision != query.expectedReceiptRevision ||
        source.sourceId == null) unavailable()
    if (source.progressRevision != expectedProgress) invalid(LocalField.EXPECTED_PROGRESS_REVISION)
    val capacities = listOf(source.invoiceRemaining, source.orderRemaining, source.acceptedReceiptRemaining)
    if (capacities.any { it == null || it.unitId != query.quantity.unitId || it.scale != query.quantity.scale || it.scaled < 0 }) unavailable()
    if (capacities.any { query.quantity > checkNotNull(it) }) invalid(LocalField.QUANTITY)
    if (listOfNotNull(source.orderMatchRevision, source.orderBillingRevision, source.progressRevision,
            source.receiptBillingRevision, source.payableRevision).any { it.value < 1 } ||
        source.orderMatchRevision == null || source.orderBillingRevision == null) unavailable()
    val raw = source.originalInvoiceClaim ?: unavailable()
    val net = source.incrementNet ?: unavailable()
    val tax = source.incrementTax ?: unavailable()
    val before = source.recognizedPayable
    if ((before == null) != (source.payableRevision == null) ||
        (source.progressRevision == null) != (source.payableRevision == null)) unavailable()
    val increment = net + tax
    if (raw != invoice.supplierClaim || listOfNotNull(raw, net, tax, before).any { it.currency != Currency.EGP || it.minor < 0 } ||
        (before?.plus(increment) ?: increment).minor > raw.minor) unavailable()
}

/** A failed source selection never becomes a successful quote or a guess about the receipt. */
private fun capturedMatchSourceRefusal(source: LocalCapturedSupplierInvoiceMatchSourceV2,
    query: LocalCapturedSupplierInvoiceMatchSourceQueryV2): Nothing = when (source.code) {
    "purchasing.original_required", "purchasing.original_association_mismatch" -> invalid(LocalField.EVIDENCE_ID)
    "purchasing.original_revision_conflict" -> invalid(LocalField.EXPECTED_SOURCE_REVISION)
    "purchasing.order_required", "purchasing.order_association_conflict", "purchasing.multi_order_association_unsupported" -> invalid(LocalField.PURCHASE_ID)
    "purchasing.order_line_required", "purchasing.allocation_association_conflict" -> invalid(LocalField.ALLOCATION_ID)
    "purchasing.invoice_line_required", "purchasing.variance_requires_approval" -> invalid(LocalField.INVOICE_LINE_ID)
    "purchasing.receipt_required" -> invalid(LocalField.RECEIPT_ID)
    "purchasing.receipt_source_mismatch", "purchasing.accepted_receipt_required", "purchasing.receipt_cost_variance" -> invalid(LocalField.RECEIPT_LINE_ID)
    "purchasing.quantity_unit_mismatch", "purchasing.remaining_quantity_exceeded", "purchasing.whole_serial_required", "purchasing.rounding_variance" -> invalid(LocalField.QUANTITY)
    "purchasing.capacity_required", "purchasing.capacity_unavailable" -> throw GatewayFailure(LocalServiceProblem.PURCHASING_CAPACITY_REQUIRED)
    "purchasing.stale_revision" -> when {
        source.invoiceRevision != query.expectedInvoiceRevision -> invalid(LocalField.EXPECTED_STORAGE_REVISION)
        source.orderRevision != query.expectedOrderRevision -> invalid(LocalField.EXPECTED_ORDER_REVISION)
        source.receiptRevision != query.expectedReceiptRevision -> invalid(LocalField.EXPECTED_RECEIPT_REVISION)
        else -> throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
    }
    // An identity-use refusal cannot be established by this read: it has no proposed match ID.
    else -> throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
}

/** Exact input identities remain reviewable after navigation, loss of response and restart. */
internal fun capturedInvoiceMatchIdentitySummary(input: LocalMatchCapturedSupplierInvoiceV2): List<LocalRecordValue> = listOf(
    summaryGroup(text("Exact accepted-goods match and payable recognition", "مطابقة بضاعة مقبولة وإثبات مستحق"), listOf(
        summaryField(LocalField.MATCH_ID, input.matchId.value), summaryField(LocalField.INVOICE_ID, input.invoiceId.value),
        summaryField(LocalField.INVOICE_LINE_ID, input.invoiceLineId.value), summaryField(LocalField.EVIDENCE_ID, input.originalDocumentId.value),
        summaryField(LocalField.PURCHASE_ID, input.orderId.value), summaryField(LocalField.ALLOCATION_ID, input.allocationId.value),
        summaryField(LocalField.RECEIPT_ID, input.receiptId.value), summaryField(LocalField.RECEIPT_LINE_ID, input.receiptLineId.value),
        summaryField(LocalField.QUANTITY, quantityText(input.quantity).english), summaryField(LocalField.REASON, input.reasonCode),
    )),
    summaryGroup(text("Observed source revisions — absence stays explicit", "مراجعات المصادر المرصودة — يبقى الغياب صريحًا"), listOf(
        summaryField(LocalField.EXPECTED_STORAGE_REVISION, input.expectedInvoiceRevision.value.toString()),
        summaryField(LocalField.EXPECTED_SOURCE_REVISION, input.expectedOriginalRevision.value.toString()),
        summaryField(LocalField.EXPECTED_ORDER_REVISION, input.expectedOrderRevision.value.toString()),
        summaryField(LocalField.EXPECTED_RECEIPT_REVISION, input.expectedReceiptRevision.value.toString()),
        summaryField(LocalField.EXPECTED_PROGRESS_REVISION, input.expectedProgressRevision?.value?.toString()),
        text("PO match / billed heads", "مراجعتا مطابقة الأمر وفوترته") to literal("${input.expectedOrderMatchRevision.value} / ${input.expectedOrderBillingRevision.value}"),
        text("Receipt billed head", "مراجعة فوترة الاستلام") to literal(input.expectedReceiptBillingRevision?.value?.toString() ?: "absent / غير موجود"),
        summaryField(LocalField.EXPECTED_PAYABLE_REVISION, input.expectedPayableRevision?.value?.toString()),
    )),
)

internal suspend fun capturedInvoiceMatchForm(access: GatewayAccess, invoice: LocalPurchasingInvoiceCaptureViewV2,
    recognition: LocalCapturedSupplierInvoiceRecognitionV2?): LocalActionForm? {
    val lines = invoice.lines.filter { line ->
        val matched = recognition?.lines?.singleOrNull { it.lineId == line.lineId }?.matchedQuantity
        matched == null || matched < line.baseQuantity
    }.map { line -> LocalReferenceChoice(LocalRecordReference(LocalRecordKind.PURCHASE_LINE, line.lineId, parentId = invoice.id),
        literal("${line.skuId.value} · ${line.lineId.value}")) }
    if (lines.isEmpty()) return null
    val original = capturedMatchOriginal(access, invoice)
    val orderId = recognition?.orderId ?: invoice.internalOrderLink?.orderId
    val orderRevision = recognition?.orderRevision ?: invoice.internalOrderLink?.revision
    val progress = recognition?.progressRevision
    return form(LocalAction.SUPPLIER_INVOICE_MATCH_RECOGNIZE, listOf(
        newIdField(LocalField.MATCH_ID), idField(LocalField.INVOICE_ID, invoice.id),
        revisionField(LocalField.EXPECTED_STORAGE_REVISION, invoice.revision),
        scalar(LocalField.EXPECTED_PROGRESS_REVISION, LocalInputKind.INTEGER, progress?.let { LocalScalarValue.Integer(it.value) },
            required = progress != null, readOnly = true, maximum = 19),
        idField(LocalField.EVIDENCE_ID, original.documentId), revisionField(LocalField.EXPECTED_SOURCE_REVISION, original.storageRevision),
        scalar(LocalField.INVOICE_LINE_ID, LocalInputKind.REFERENCE).copy(references = lines),
        idField(LocalField.PURCHASE_ID, orderId),
        scalar(LocalField.EXPECTED_ORDER_REVISION, LocalInputKind.INTEGER, orderRevision?.let { LocalScalarValue.Integer(it.value) },
            readOnly = orderRevision != null, maximum = 19),
        idField(LocalField.ALLOCATION_ID), idField(LocalField.RECEIPT_ID), idField(LocalField.RECEIPT_LINE_ID),
        scalar(LocalField.EXPECTED_RECEIPT_REVISION, LocalInputKind.INTEGER, maximum = 19),
        scalar(LocalField.QUANTITY, LocalInputKind.DECIMAL, maximum = 32, help = text(
            "Exact accepted billable base quantity. Held, rejected or already billed goods are ineligible.",
            "الكمية الأساسية المقبولة القابلة للفوترة بدقة. لا تشمل المحجوز أو المرفوض أو المفوتر سابقًا.")),
        scalar(LocalField.REASON, LocalInputKind.TEXT, maximum = 128),
    ), text(
        "Select the actual approved PO allocation and accepted receipt. Saving reads and pins their current billing heads, then shows the exact amount for review. " +
            "Submission needs explicit match-and-recognize authority for the full original claim, not just this slice. It recognizes only exact eligible goods once; " +
            "it does not approve discrepancies, alter the original, receive stock or pay the supplier. Missing original evidence must be captured separately first.",
        "اختر تخصيص أمر الشراء المعتمد والاستلام المقبول الفعليين. يقرأ الحفظ مراجعات فوترة المصادر ويثبتها، ثم يعرض المبلغ الدقيق للمراجعة. " +
            "يتطلب التنفيذ صلاحية صريحة للمطابقة وإثبات المستحق لكامل المطالبة الأصلية، لا لهذا الجزء فقط. يُثبت البضاعة المؤهلة المتطابقة مرة واحدة؛ " +
            "ولا يعتمد فروقًا أو يغير الأصل أو يستلم مخزونًا أو يدفع للمورد. يجب حفظ الإثبات الأصلي المفقود منفصلًا أولًا."))
}

/** Metadata access is explicit; matching never borrows document export permission. */
private suspend fun capturedMatchOriginal(access: GatewayAccess, invoice: LocalPurchasingInvoiceCaptureViewV2): LocalPurchasingDocumentFileV1 {
    val response = LocalPurchasingDocumentCodecV1.readResult(access.read(LocalPurchasingDocumentCodecV1.query(
        LocalPurchasingDocumentQueryV1(documentId = invoice.sourceEvidenceId))).payload)
    if (response.items.isNotEmpty() || response.nextAfter != null || response.pending != null) unavailable()
    val original = response.detail?.original ?: throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
    if (original.documentId != invoice.sourceEvidenceId || original.scope != access.view.scope || original.parentOriginalId != null ||
        original.storageRevision != Revision(1) || original.state != LocalPurchasingDocumentStateV1.CAPTURED_AT_BRANCH ||
        original.qualification != LocalPurchasingPolicyQualificationV1.UNQUALIFIED_SELECTORS ||
        original.supplierId != invoice.supplierId || original.supplierReferenceOriginal != invoice.reference ||
        original.documentDate != invoice.date) unavailable()
    return original
}
