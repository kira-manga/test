package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId

internal suspend fun stockSetupWorkflow(access: GatewayAccess, query: LocalWorkflowQuery): LocalWorkflowPage {
    if (query.reference != null || query.cursor != null || query.search.isNotBlank()) invalid()
    val reply = access.read(LocalStockRelationalQueriesV2.profile())
    val active = LocalStockRelationalQueriesV2.normalized(reply.payload)
    val explanation = text(
        "Initialize normalized storage only for a master-proved empty stock estate. The master scans current rows, claims and retained history atomically. " +
            "This does not import stock, migrate existing data, reset balances or bypass recovery. Existing stock/history requires a separately implemented migration. " +
            "Normalized same-branch transfers are available for safe, released, unreserved company stock. Broader custody transfers and linked cost corrections remain unavailable; do not initialize to bypass a held workflow.",
        "تهيئة التخزين المنظم فقط عندما يثبت الجهاز الرئيسي خلو حالة المخزون كاملةً. يفحص السجلات والمطالبات والتاريخ المحفوظ ذريًا. " +
            "لا يستورد هذا مخزونًا ولا يرحّل بيانات قائمة ولا يعيد ضبط الأرصدة أو يتجاوز الاسترداد. المخزون أو التاريخ القائم يتطلب ترحيلًا منفصلًا لم يُنفذ بعد. " +
            "النقل المنظم داخل الفرع متاح لمخزون الشركة الآمن والمفرج عنه وغير المحجوز. نقل العهدة الأوسع وتصحيحات التكلفة المرتبطة غير متاحين؛ لا تستخدم التهيئة لتجاوز إجراء معلق.")
    val forms = if (!active && access.installed.has(LocalStockRelationalProfileV2.COMMAND) && access.allow(LocalAction.STOCK_INITIALIZE))
        listOf(form(LocalAction.STOCK_INITIALIZE, listOf(newIdField(LocalField.DOCUMENT_ID), stockReasonField()), explanation).copy(
            confirmation = text("I understand this is empty-stock initialization, not migration, opening quantities or a reset.",
                "أفهم أن هذا يهيئ مخزونًا فارغًا، وليس ترحيل بيانات أو كميات افتتاحية أو إعادة ضبط."))) else emptyList()
    return LocalWorkflowPage(query.workflow, listOf(LocalRecord(LocalRecordReference(LocalRecordKind.BRANCH, access.view.scope.branchId),
        text("Current stock storage profile", "نمط تخزين المخزون الحالي"),
        if (active) text("Normalized storage is active", "التخزين المنظم نشط")
        else text("Normalized storage is not active — emptiness is not established", "التخزين المنظم غير نشط — لم يثبت خلو المخزون"),
        selectable = false)), forms, text("${explanation.english} ${readCut(reply).english}", "${explanation.arabic} ${readCut(reply).arabic}"))
}

internal suspend fun prepareStockInitialization(access: GatewayAccess, intent: LocalCommandIntent): MutationRequest {
    if (intent.action != LocalAction.STOCK_INITIALIZE || intent.reference != null || intent.lines.isNotEmpty() || intent.lineField != null) invalid()
    val input = InputValues(intent.values)
    val id = input.id(LocalField.DOCUMENT_ID)
    access.requireAction(intent.action, id)
    val reason = input.text(LocalField.REASON)
    input.finish()
    if (LocalStockRelationalQueriesV2.normalized(access.read(LocalStockRelationalQueriesV2.profile()).payload))
        invalid(LocalField.DOCUMENT_ID)
    // Only a fresh intent reaches here. The complete authoritative estate check belongs to
    // the existing atomic handler; this Boolean is not proof that initialization may succeed.
    return LocalStockRelationalCommandsV2.initializeFresh(intent.operationId, id, reason)
}

internal fun stockInitializationRecorded(entry: StoredClientIntentV1, reply: MutationReply.Recorded): LocalOperationResult.Recorded {
    val request = entry.request()
    if (!actionMatches(entry.localAction, request)) unavailable()
    val value = LocalStockRelationalCommandsV2.decodeInitializationResult(request.payload, reply.result)
    if (value.freshEmpty != reply.committed) unavailable()
    val message = if (value.freshEmpty) text("The master initialized an empty stock estate. No quantities, costs or supplier obligations were created.",
        "هيأ الجهاز الرئيسي حالة مخزون فارغة. لم تُنشأ كميات أو تكاليف أو التزامات موردين.")
    else text("Existing stock or retained stock history requires migration. Initialization was durably rejected; nothing was reset or imported.",
        "المخزون القائم أو تاريخه المحفوظ يتطلب ترحيلًا. سُجل رفض التهيئة بشكل دائم؛ لم تتم إعادة ضبط أو استيراد شيء.")
    return LocalOperationResult.Recorded(entry.operationId, if (reply.committed) LocalRecordedDecision.APPLIED else LocalRecordedDecision.REJECTED,
        message, LocalRecord(LocalRecordReference(LocalRecordKind.BRANCH, entry.owner.scope.branchId), entry.label.ui(),
            values = listOf(recordValue(fieldLabel(LocalField.DOCUMENT_ID), literal(value.initializationId.value))), selectable = false), reply.replayed)
}

internal suspend fun stockCostWorkflow(access: GatewayAccess, query: LocalWorkflowQuery, skuId: DomainId): LocalWorkflowPage {
    val after = parseCursor(query.cursor, "stock-costs")
    val reply = access.read(LocalStockCommands.costs(skuId, after, 25))
    val page = LocalStockQueries.decodeCosts(reply.payload)
    if (page.skuId != skuId || page.rows.size > 25 || page.rows.map { it.poolId }.distinct().size != page.rows.size ||
        page.rows.zipWithNext().any { (a, b) -> a.poolId.value >= b.poolId.value } ||
        after != null && page.rows.any { it.poolId.value <= after.value } ||
        page.nextAfter != null && page.nextAfter != page.rows.lastOrNull()?.poolId ||
        page.itemStorageRevision == null && page.rows.isNotEmpty() ||
        page.itemStorageRevision?.value?.let { it <= 0 } == true ||
        page.rows.any { it.quantity.scaled < 0 || it.carryingValue.currency != Currency.EGP || it.carryingValue.minor < 0 ||
            it.storageRevision.value <= 0 || it.valuationRevision.value < 0 }) unavailable()
    val records = page.rows.map { row -> LocalRecord(LocalRecordReference(LocalRecordKind.STOCK_VALUE_POOL, row.poolId, parentId = skuId),
        text("Company-owned stock value", "قيمة مخزون مملوك للشركة"), values = listOf(
            recordValue(fieldLabel(LocalField.SKU_ID), literal(skuId.value)),
            recordValue(fieldLabel(LocalField.WAREHOUSE_ID), literal(row.warehouseId.value)),
            recordValue(text("Pool quantity", "كمية وعاء التقييم"), quantityText(row.quantity)),
            recordValue(text("Current carrying value", "القيمة الدفترية الحالية"), moneyText(row.carryingValue)),
            recordValue(text("Valuation revision", "مراجعة التقييم"), literal(row.valuationRevision.value.toString())),
            recordValue(text("Pool storage revision", "مراجعة تخزين وعاء التقييم"), literal(row.storageRevision.value.toString()))), selectable = false) }
    val notice = if (page.itemStorageRevision == null) text("No stock item is available at this authorized cost-read cut; no zero balance or zero cost is inferred.",
        "لا يوجد سجل صنف مخزون متاح في مراجعة قراءة التكلفة المصرح بها؛ لا يُستنتج رصيد أو تكلفة صفرية.")
    else text("Current company-owned values only, not supplier-owned custody, a sale price, profit or a cost correction. " +
        "Average cost is the exact carrying-value / quantity ratio; a zero quantity has no average. ${readCut(reply).english}",
        "القيم الحالية للمخزون المملوك للشركة فقط، وليست عهدة المورد أو سعر بيع أو ربحًا أو تصحيح تكلفة. " +
            "متوسط التكلفة هو نسبة القيمة الدفترية إلى الكمية بدقة؛ الكمية الصفرية ليس لها متوسط. ${readCut(reply).arabic}")
    return LocalWorkflowPage(query.workflow, records, emptyList(), notice, cursor("stock-costs", page.nextAfter))
}
