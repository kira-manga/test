package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.DomainId

/** Separate explicit manager action; the existing manager: route stays read-only. */
internal suspend fun managerReassignmentWorkflow(access: GatewayAccess, query: LocalWorkflowQuery): LocalWorkflowPage {
    if (query.workflow != LocalWorkflow.ORDERS || query.cursor != null) invalid()
    val target = searchedId(query.search.removePrefix("reassign:"))
    query.reference?.let { if (it.kind != LocalRecordKind.ORDER || it.id != target) invalid() }
    val cut = managerReassignmentRead(access, target)
    val order = cut.second?.order ?: return LocalWorkflowPage(LocalWorkflow.ORDERS, emptyList(), emptyList(), text(
        "No eligible unpaid retail order is available for this authorized reassignment. No responsibility has changed.",
        "لا يتوفر طلب تجزئة غير مدفوع مؤهل لهذا النقل المصرح به. لم تتغير المسؤولية."))
    val summary = orderRecord(order)
    val records = listOf(summary.copy(selectable = false, values = summary.values +
        recordValue(fieldLabel(LocalField.PREVIOUS_OWNER_ID), literal(order.ownerId.value))))
    val forms = if (access.allow(LocalAction.ORDER_REASSIGN, target)) listOf(form(LocalAction.ORDER_REASSIGN,
        listOf(idField(LocalField.ORDER_ID, order.orderId), revisionField(LocalField.EXPECTED_REVISION, order.revision),
            idField(LocalField.PREVIOUS_OWNER_ID, order.ownerId), idField(LocalField.RECIPIENT_ID),
            scalar(LocalField.REASON, LocalInputKind.TEXT, maximum = 128, help = text(
                "Explicit reassignment reason code: letters, digits, dot, underscore or hyphen. No spaces.",
                "رمز صريح لسبب نقل المسؤولية: حروف أو أرقام أو نقطة أو شرطة سفلية أو شرطة. دون مسافات.")),
            newIdField(LocalField.EVIDENCE_ID), instantField(LocalField.OCCURRED_AT)),
        text("The Master rechecks this exact order, manager permission and recipient before committing. Only current responsibility moves; creator, original prices and cash custody never change. Save and submit separately.",
            "يعيد الجهاز الرئيسي التحقق من الطلب نفسه وصلاحية المدير والموظف المستلم قبل الحفظ. تُنقل المسؤولية الحالية فقط؛ ولا يتغير المنشئ أو الأسعار الأصلية أو عهدة النقد. احفظ ثم أرسل بشكل منفصل."),
        version = 2)) else emptyList()
    return LocalWorkflowPage(LocalWorkflow.ORDERS, records, forms, text(
        "Explicit manager reassignment for an absent owner's unlocked unpaid retail order only. Active handoffs, collections, paid sales and unresolved payment require their existing resolution workflows. ${readCut(cut.first).english}",
        "نقل صريح بواسطة المدير لطلب تجزئة غير مقفل وغير مدفوع لمالك غائب فقط. تتطلب عروض التسليم والتحصيل والمبيعات المدفوعة والدفع غير المحسوم إجراءات التسوية الخاصة بها. ${readCut(cut.first).arabic}"))
}

private suspend fun managerReassignmentRead(access: GatewayAccess, target: DomainId):
    Pair<com.ukkera.pos.database.ReadReply.Data, LocalProfiledOrderV2?> {
    if (!access.installed.has(LocalManagerReassignmentCodec.QUERY) ||
        !access.installed.has(LocalCollectionStockCodecV2.COMMAND)) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    access.requireAction(LocalAction.ORDER_REASSIGN, target)
    val selector = LocalManagerOrderReviewQueryV1(target)
    val reply = access.read(LocalManagerReassignmentCodec.request(selector))
    val selected = LocalManagerReassignmentCodec.decode(reply.payload, selector).orders.singleOrNull()
    selected?.let {
        if (it.stockProfile != LocalOrderStockProfileV2.NORMALIZED || it.order.orderId != target ||
            it.order.collectionId != null || it.order.saleId != null || it.order.handoffId != null ||
            it.order.selection.activity !in setOf(LocalSalesActivity.CLOTHING, LocalSalesActivity.SUPERMARKET)) unavailable()
    }
    return reply to selected
}

/** Re-read current target before staging; never impersonate the old owner or reinterpret a retry. */
internal suspend fun prepareManagerReassignment(access: GatewayAccess, intent: LocalCommandIntent): MutationRequest {
    if (intent.action != LocalAction.ORDER_REASSIGN || intent.formVersion != 2 || intent.lineField != null ||
        intent.lines.isNotEmpty()) invalid()
    val input = InputValues(intent.values)
    val target = input.id(LocalField.ORDER_ID)
    val revision = input.revision(LocalField.EXPECTED_REVISION)
    val previousOwner = input.id(LocalField.PREVIOUS_OWNER_ID)
    val recipient = input.id(LocalField.RECIPIENT_ID)
    val reason = input.text(LocalField.REASON)
    if (reason.length !in 1..128 || reason.any { !it.isLetterOrDigit() && it !in "._-" }) invalid(LocalField.REASON)
    val evidence = input.cashEvidence()
    input.finish()
    val order = managerReassignmentRead(access, target).second?.order ?: invalid(LocalField.ORDER_ID)
    if (order.revision != revision) invalid(LocalField.EXPECTED_REVISION)
    if (order.ownerId != previousOwner) invalid(LocalField.PREVIOUS_OWNER_ID)
    if (recipient == previousOwner) invalid(LocalField.RECIPIENT_ID)
    return LocalCollectionStockCodecV2.command(intent.operationId,
        LocalCollectionStockRequestV2.ReassignOrder(target, revision, previousOwner, recipient, reason, evidence))
}
