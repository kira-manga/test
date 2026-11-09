package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.inventory.StockSafety
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision

internal const val RETURN_STOCK_PAGE_SIZE: Int = 16

internal data class ReturnStockCut(
    val reply: ReadReply.Data,
    val value: LocalReturnStockReadResultV1.Original,
)

/** Separate authorized quantity-only read. Neither CASE acceptance nor payout initiates it. */
internal suspend fun returnStockWorkflow(access: GatewayAccess, query: LocalWorkflowQuery,
    caseId: DomainId, returnId: DomainId): LocalWorkflowPage {
    if (query.workflow != LocalWorkflow.RETURNS) invalid()
    val cursor = returnStockCursor(query.cursor, returnId)
    val cut = returnStockRead(access, caseId, returnId, after = cursor?.after)
        ?: return LocalWorkflowPage(query.workflow, emptyList(), emptyList(),
            text("Original return stock is unavailable to the current actor. No missing stock or completed disposition is inferred; payout remains separate.",
                "مخزون المرتجع الأصلي غير متاح للمستخدم الحالي. لا يُستنتج غياب مخزون أو اكتمال تصرف؛ ويظل الصرف منفصلًا."))
    val value = cut.value
    if (cursor != null && (cursor.caseRevision != value.caseRevision ||
        cursor.dispositionRevision != value.dispositionRevision || cursor.issueRevision != value.issueRevision)) returnRestart()
    val post = returnStockForm(access, value, cursor?.after)
    val reason = when {
        value.requestedDisposition !in supportedReturnStockDispositions -> text(
            "Requested waste or supplier-return posting is not available. No successful no-op or release is offered.",
            "ترحيل الهالك أو الإرجاع للمورد المطلوب غير متاح. لا يُعرض نجاح بلا أثر أو إتاحة للمخزون.")
        value.status == LocalReturnStockStatusV1.POSTED -> text(
            "This acceptance's stock quantity is fully posted. The immutable CASE remains accepted; refund obligations and payout are independent.",
            "رُحلت كمية مخزون هذا القبول بالكامل. تظل المطالبة الثابتة مقبولة؛ والتزامات رد المال والصرف مستقلان.")
        post == null -> text(
            "No posting form is available on this page under the current service/permission/source requirements. No stock posting is implied.",
            "لا يتاح نموذج ترحيل في هذه الصفحة وفق متطلبات الخدمة أو الصلاحية أو المصدر الحالية. لا يُفهم من ذلك ترحيل مخزون.")
        else -> text(
            "Every quantity starts at ZERO. Enter only physically inspected positive quantities and explicit custody on this original page; remove or leave zero all others. Save one immutable subset for separate submission.",
            "تبدأ كل كمية بصفر. أدخل فقط الكميات الموجبة المفحوصة فعليًا وعهدتها الصريحة في صفحة الأصول هذه؛ احذف بقية السطور أو اتركها صفرًا. احفظ مجموعة جزئية ثابتة لإرسالها منفصلًا.")
    }
    return LocalWorkflowPage(query.workflow,
        listOf(returnStockRecord(value)) + value.allocations.map(::returnStockAllocationRecord),
        listOfNotNull(post), text(
            "${reason.english} Allocation remaining quantities cover the original issue and prior returns, NOT only this acceptance. No costs are disclosed. Current safety and expiry are rechecked by the Master; quarantine is not sellable. This read does not restore stock or initiate a refund. ${readCut(cut.reply).english}",
            "${reason.arabic} تخص كميات التخصيصات المتبقية إذن الصرف الأصلي والمرتجعات السابقة، لا هذا القبول فقط. لا تُعرض تكاليف. يعيد الجهاز الرئيسي فحص الأمان والصلاحية؛ والحجر ليس مخزونًا متاحًا للبيع. لا تعيد هذه القراءة مخزونًا ولا تبدأ رد مال. ${readCut(cut.reply).arabic}"),
        value.nextAfterAllocationId?.let { returnStockCursor(value, it) })
}

internal val supportedReturnStockDispositions: Set<LocalReturnDispositionV1> =
    setOf(LocalReturnDispositionV1.INSPECTED_SELLABLE, LocalReturnDispositionV1.QUARANTINE)

/** Refuse malformed/source-mismatched quantities before they can become an editor or review. */
internal suspend fun returnStockRead(access: GatewayAccess, caseId: DomainId,
    returnId: DomainId, saleId: DomainId? = null, after: DomainId? = null): ReturnStockCut? {
    // The stock Master read completely qualifies immutable acceptance, CASE and original
    // sale/issue. Inventory-only actors need no separate private legal/financial CASE read.
    // Discovery derives the original sale; fresh preparation supplies its exact assertion.
    val reply = access.read(LocalReturnStockReadCodecV1.request(LocalReturnStockQueryV1(
        saleId, caseId, returnId, after, RETURN_STOCK_PAGE_SIZE)))
    val decoded = LocalReturnStockReadCodecV1.decode(reply.payload)
    if (decoded == LocalReturnStockReadResultV1.NotAvailable) return null
    val value = decoded as? LocalReturnStockReadResultV1.Original ?: unavailable()
    if (saleId != null && value.saleId != saleId || value.caseId != caseId || value.returnId != returnId ||
        value.caseRevision.value < 1 || value.issueRevision.value < 1 ||
        !returnStockConserves(value.acceptedQuantity, value.postedQuantity, value.remainingQuantity) ||
        value.status != returnStockStatus(value.acceptedQuantity, value.postedQuantity) ||
        (value.dispositionRevision.value == 0L) != (value.postedQuantity.scaled == 0L) ||
        (value.lastPostingOperationId == null) != (value.postedQuantity.scaled == 0L)) unavailable()
    val ids = value.allocations.map { it.allocationId }
    if (ids.size > RETURN_STOCK_PAGE_SIZE || ids != ids.distinct().sortedBy { it.value } ||
        after != null && ids.any { it.value <= after.value } ||
        after == null && ids.isEmpty() ||
        value.nextAfterAllocationId != null && (ids.isEmpty() || value.nextAfterAllocationId != ids.last()) ||
        value.allocations.any { row ->
            !sameReturnStockUnit(row.originalQuantity, value.acceptedQuantity) ||
                !returnStockConserves(row.originalQuantity, row.restoredQuantity, row.remainingQuantity) ||
                row.currentSafety !in StockSafety.entries.map { it.name }
        }) unavailable()
    // The page is an original-issue allocation view, not a per-acceptance remainder fold.
    // Summing page remainders and equating them to this acceptance would be incorrect.
    return ReturnStockCut(reply, value)
}

internal fun sameReturnStockUnit(a: Quantity, b: Quantity): Boolean = a.unitId == b.unitId && a.scale == b.scale

internal fun returnStockConserves(original: Quantity, posted: Quantity, remaining: Quantity): Boolean =
    original.scaled > 0 && sameReturnStockUnit(original, posted) && sameReturnStockUnit(original, remaining) &&
        posted.scaled in 0..original.scaled && remaining.scaled == original.scaled - posted.scaled

internal fun returnStockStatus(accepted: Quantity, posted: Quantity): LocalReturnStockStatusV1 = when {
    posted.scaled == 0L -> LocalReturnStockStatusV1.PENDING
    posted.scaled == accepted.scaled -> LocalReturnStockStatusV1.POSTED
    else -> LocalReturnStockStatusV1.PARTIALLY_POSTED
}

private fun returnStockRecord(value: LocalReturnStockReadResultV1.Original): LocalRecord = LocalRecord(
    LocalRecordReference(LocalRecordKind.RETURN_STOCK, value.returnId, parentId = value.caseId),
    text("Separate original return-stock disposition", "التصرف المستقل بمخزون المرتجع الأصلي"),
    literal(value.status.name), listOfNotNull(
        recordValue(fieldLabel(LocalField.SALE_ID), literal(value.saleId.value)),
        recordValue(fieldLabel(LocalField.CASE_ID), literal(value.caseId.value)),
        recordValue(fieldLabel(LocalField.RETURN_ID), literal(value.returnId.value)),
        recordValue(fieldLabel(LocalField.PORTION_ID), literal(value.portionId.value)),
        recordValue(fieldLabel(LocalField.EXPECTED_CASE_REVISION), literal(value.caseRevision.value.toString())),
        recordValue(fieldLabel(LocalField.DISPOSITION), returnDispositionLabel(value.requestedDisposition)),
        recordValue(fieldLabel(LocalField.ACCEPTED_QUANTITY), quantityText(value.acceptedQuantity)),
        recordValue(fieldLabel(LocalField.EXPECTED_POSTED_QUANTITY), quantityText(value.postedQuantity)),
        recordValue(text("Stock quantity still pending for this acceptance", "كمية المخزون المعلقة لهذا القبول"), quantityText(value.remainingQuantity)),
        recordValue(fieldLabel(LocalField.EXPECTED_DISPOSITION_REVISION), literal(value.dispositionRevision.value.toString())),
        recordValue(fieldLabel(LocalField.STOCK_ISSUE_ID), literal(value.issueId.value)),
        recordValue(fieldLabel(LocalField.EXPECTED_ISSUE_REVISION), literal(value.issueRevision.value.toString())),
        value.lastPostingOperationId?.let { recordValue(text("Last stock posting operation — not payout", "عملية ترحيل المخزون الأخيرة — ليست صرفًا"), literal(it.value)) }),
    selectable = false)

private fun returnStockAllocationRecord(row: LocalReturnStockAllocationViewV1): LocalRecord = LocalRecord(
    LocalRecordReference(LocalRecordKind.RETURN_STOCK_ALLOCATION, row.allocationId),
    text("Actual original allocation — quantity only", "التخصيص الأصلي الفعلي — كمية فقط"), literal(row.currentSafety),
    listOfNotNull(
        recordValue(fieldLabel(LocalField.ALLOCATION_ID), literal(row.allocationId.value)),
        recordValue(fieldLabel(LocalField.SKU_ID), literal(row.skuId.value)),
        recordValue(fieldLabel(LocalField.WAREHOUSE_ID), literal(row.warehouseId.value)),
        row.lotId?.let { recordValue(fieldLabel(LocalField.LOT_ID), literal(it.value)) },
        recordValue(text("Originally issued quantity", "الكمية المصروفة أصلًا"), quantityText(row.originalQuantity)),
        recordValue(text("Already restored across original returns", "الكمية المعادة مسبقًا عبر المرتجعات الأصلية"), quantityText(row.restoredQuantity)),
        recordValue(text("Original allocation remaining ceiling", "حد الكمية المتبقية للتخصيص الأصلي"), quantityText(row.remainingQuantity)),
        row.expiresAt?.let { recordValue(fieldLabel(LocalField.EXPIRY_AT), literal(it.canonicalString())) }), selectable = false)

private fun returnStockForm(access: GatewayAccess, value: LocalReturnStockReadResultV1.Original,
    after: DomainId?): LocalActionForm? {
    if (!access.installed.has(LocalReturnStockProfileV1.COMMAND) || !access.allow(LocalAction.RETURN_STOCK_POST, value.returnId) ||
        value.requestedDisposition !in supportedReturnStockDispositions || value.remainingQuantity.scaled <= 0 ||
        value.dispositionRevision.value == Long.MAX_VALUE) return null
    val available = value.allocations.filter { it.remainingQuantity.scaled > 0 }
    if (available.isEmpty()) return null
    val custodyChoices = when (value.requestedDisposition) {
        LocalReturnDispositionV1.INSPECTED_SELLABLE -> listOf(
            LocalChoice(LocalReturnStockCustodyV1.RELEASED.name, text("Explicit inspected release — only if safe and unexpired", "إتاحة صريحة بعد الفحص — للأمان والصلاحية فقط")),
            LocalChoice(LocalReturnStockCustodyV1.QUARANTINE.name, text("Explicit quarantine — not sellable", "حجر صريح — غير متاح للبيع")))
        LocalReturnDispositionV1.QUARANTINE -> listOf(
            LocalChoice(LocalReturnStockCustodyV1.QUARANTINE.name, text("Explicit quarantine — not sellable", "حجر صريح — غير متاح للبيع")))
        else -> return null
    }
    val quantity = value.acceptedQuantity
    val lines = LocalFormField.Lines(LocalField.LINES, text("Inspected original quantities on this page", "كميات الأصول المفحوصة في هذه الصفحة"),
        listOf(idField(LocalField.ALLOCATION_ID, readOnly = true),
            scalar(LocalField.QUANTITY, LocalInputKind.DECIMAL, maximum = 32,
                help = text("ZERO means not selected. Positive quantity uses the original base unit and precision.", "الصفر يعني عدم الاختيار. تستخدم الكمية الموجبة الوحدة الأساسية والدقة الأصليتين.")),
            choiceField(LocalField.CUSTODY, custodyChoices).copy(required = false,
                help = text("Required for every positive quantity; no automatic release or custody selection.", "مطلوب لكل كمية موجبة؛ لا توجد إتاحة أو عهدة مختارة تلقائيًا."))),
        maximumLines = RETURN_STOCK_PAGE_SIZE, initialLines = available.map { row -> LocalInputLine(row.allocationId, listOf(
            LocalScalarInput(LocalField.ALLOCATION_ID, LocalScalarValue.Identifier(row.allocationId)),
            LocalScalarInput(LocalField.QUANTITY, LocalScalarValue.Decimal(fixedDecimal(0L, quantity.scale))))) },
        allowBlankLines = false, allowDuplicateLines = false)
    return form(LocalAction.RETURN_STOCK_POST, listOf(
        idField(LocalField.SALE_ID, value.saleId), idField(LocalField.CASE_ID, value.caseId), idField(LocalField.RETURN_ID, value.returnId),
        revisionField(LocalField.EXPECTED_CASE_REVISION, value.caseRevision),
        revisionField(LocalField.EXPECTED_DISPOSITION_REVISION, value.dispositionRevision),
        idField(LocalField.STOCK_ISSUE_ID, value.issueId), revisionField(LocalField.EXPECTED_ISSUE_REVISION, value.issueRevision),
        idField(LocalField.UNIT, quantity.unitId),
        scalar(LocalField.QUANTITY_SCALE, LocalInputKind.INTEGER, LocalScalarValue.Integer(quantity.scale.toLong()), readOnly = true),
        scalar(LocalField.ACCEPTED_QUANTITY, LocalInputKind.DECIMAL, LocalScalarValue.Decimal(fixedDecimal(quantity.scaled, quantity.scale)), readOnly = true, maximum = 32),
        scalar(LocalField.EXPECTED_POSTED_QUANTITY, LocalInputKind.DECIMAL, LocalScalarValue.Decimal(fixedDecimal(value.postedQuantity.scaled, quantity.scale)), readOnly = true, maximum = 32),
        idField(LocalField.RETURN_STOCK_AFTER_ALLOCATION_ID, after, required = false, readOnly = true),
        newIdField(LocalField.RESTORATION_ID), lines, newIdField(LocalField.EVIDENCE_ID), instantField(LocalField.OCCURRED_AT)),
        text("I attest the selected original goods were physically inspected with the exact quantities and custody entered. This posts a positive stock subset only, without paying a refund or changing immutable acceptance. Safety/expiry and current authority are independently enforced by the Master. Recover this saved original operation after an uncertain response, never restock again with a new identity.",
            "أقر بفحص البضاعة الأصلية المختارة فعليًا وفق الكميات والعهدة الدقيقة المدخلة. يرحل هذا مجموعة مخزون جزئية موجبة فقط، دون دفع رد مال أو تغيير القبول الثابت. يفرض الجهاز الرئيسي مستقلًا الأمان والصلاحية والصلاحية الحالية. استعد العملية الأصلية المحفوظة بعد رد غير محسوم، ولا تعد المخزون مجددًا بمعرف جديد."))
}

private data class ReturnStockPageCursor(val caseRevision: Revision, val dispositionRevision: Revision,
    val issueRevision: Revision, val after: DomainId)

private fun returnStockCursor(cursor: String?, returnId: DomainId): ReturnStockPageCursor? {
    if (cursor == null) return null
    val prefix = "return-stock:${returnId.value}:"
    if (!cursor.startsWith(prefix)) invalid()
    val parts = cursor.removePrefix(prefix).split(':')
    if (parts.size != 4) invalid()
    fun revision(index: Int, minimum: Long): Revision = Revision(parts[index].toLongOrNull()
        ?.takeIf { it >= minimum && it.toString() == parts[index] } ?: invalid())
    return ReturnStockPageCursor(revision(0, 1), revision(1, 0), revision(2, 1), searchedId(parts[3]))
}

private fun returnStockCursor(value: LocalReturnStockReadResultV1.Original, after: DomainId): String =
    "return-stock:${value.returnId.value}:${value.caseRevision.value}:${value.dispositionRevision.value}:${value.issueRevision.value}:${after.value}"
