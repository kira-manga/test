package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.LocalCountSurplusApprovalCodec
import com.ukkera.pos.catalog.store.LocalCountSurplusApprovalProfile
import com.ukkera.pos.catalog.store.LocalCountSurplusApprovalRequestV1
import com.ukkera.pos.catalog.store.LocalCountSurplusApprovalViewV1
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.inventory.StockCustody
import com.ukkera.pos.inventory.store.LocalStockCommands
import com.ukkera.pos.inventory.store.LocalStockCountView
import com.ukkera.pos.inventory.store.LocalStockQueries
import com.ukkera.pos.inventory.store.LocalStockStoreProfile
import com.ukkera.pos.types.DomainId

internal data class CountSurplusCut(val approval: LocalCountSurplusApprovalViewV1?, val reply: ReadReply.Data)

internal suspend fun countSurplusRead(access: GatewayAccess, id: DomainId): CountSurplusCut {
    val reply = access.read(ReadRequest(LocalCountSurplusApprovalProfile.QUERY, LocalCountSurplusApprovalCodec.query(id)))
    val value = LocalCountSurplusApprovalCodec.decodePage(reply.payload).approval
    if (value != null && value.approvalId != id) unavailable()
    return CountSurplusCut(value, reply)
}

internal fun countSurplusForm(access: GatewayAccess, count: LocalStockCountView): LocalActionForm? {
    if (count.counted != null || count.position.serialId != null || count.position.supplierId != null ||
        count.position.custody == StockCustody.REJECTED_HELD ||
        !access.installed.has(LocalCountSurplusApprovalProfile.COMMAND) || !access.allow(LocalAction.COUNT_APPROVE_SURPLUS, count.countId)) return null
    return form(LocalAction.COUNT_APPROVE_SURPLUS, listOf(
        newIdField(LocalField.COUNT_SURPLUS_BASIS_ID), idField(LocalField.COUNT_ID, count.countId),
        revisionField(LocalField.EXPECTED_COUNT_REVISION, count.storageRevision)) + quantityMetadata(count.capturedQuantity) + listOf(
        exactQuantityField(LocalField.COUNTED_QUANTITY), moneyField(LocalField.SURPLUS_VALUE),
        instantField(LocalField.VALID_FROM), instantField(LocalField.VALID_UNTIL, required = false), stockReasonField()),
        text("Direct monetary inventory authority only. Enter the total counted quantity and the explicitly approved value of FOUND units only, never a current average. This records a separate basis; stock changes only in a later exact count-post. It cannot register or resurrect a serial.",
            "صلاحية نقدية مباشرة للمخزون فقط. أدخل إجمالي الكمية المعدودة والقيمة المعتمدة صراحةً للوحدات المكتشفة فقط، لا متوسطًا حاليًا. يسجل هذا أساسًا منفصلًا؛ لا يتغير المخزون إلا بترحيل جرد دقيق لاحق. لا يسجل وحدة مسلسلة أو يعيد إحياءها."))
}

internal suspend fun countSurplusWorkflow(access: GatewayAccess, query: LocalWorkflowQuery, id: DomainId): LocalWorkflowPage {
    val cut = countSurplusRead(access, id)
    val approval = cut.approval ?: return LocalWorkflowPage(query.workflow, emptyList(), emptyList(),
        text("No count-value basis is available for that exact ID.", "لا يوجد أساس قيمة جرد متاح لهذا المعرف المحدد."))
    val record = LocalRecord(LocalRecordReference(LocalRecordKind.COUNT_SURPLUS_BASIS, id, approval.authorityRevision, approval.countId),
        text("Recorded count-surplus value basis", "أساس قيمة زيادة الجرد المسجل"),
        if (approval.consumed) text("Consumed by count posting", "مستهلك بترحيل الجرد") else text("Not consumed at this read cut", "غير مستهلك في مراجعة القراءة هذه"),
        listOf(
            recordValue(fieldLabel(LocalField.COUNT_SURPLUS_BASIS_ID), literal(approval.approvalId.value)),
            recordValue(fieldLabel(LocalField.COUNT_ID), literal(approval.countId.value)),
            recordValue(fieldLabel(LocalField.EXPECTED_COUNT_REVISION), literal(approval.countRevision.value.toString())),
            recordValue(fieldLabel(LocalField.EXPECTED_SURPLUS_AUTHORITY_REVISION), literal(approval.authorityRevision.value.toString())),
            recordValue(text("Captured quantity", "الكمية المثبتة"), quantityText(approval.capturedQuantity)),
            recordValue(fieldLabel(LocalField.COUNTED_QUANTITY), quantityText(approval.countedQuantity)),
            recordValue(fieldLabel(LocalField.SURPLUS_VALUE), moneyText(approval.surplusValue)),
            recordValue(text("Direct approving actor", "الموظف المعتمد مباشرةً"), literal(approval.approvedBy.value)),
            recordValue(text("Recorded at", "وقت التسجيل"), literal(approval.recordedAt.canonicalString())),
            recordValue(fieldLabel(LocalField.VALID_FROM), literal(approval.validFrom.canonicalString())),
            recordValue(fieldLabel(LocalField.VALID_UNTIL), approval.validUntil?.let { literal(it.canonicalString()) }
                ?: text("No end recorded", "لا توجد نهاية مسجلة"))))
    val countReply = access.read(LocalStockCommands.count(approval.countId))
    val count = LocalStockQueries.decodeCount(countReply.payload)
    if (count != null && count.countId != approval.countId) unavailable()
    val postForm = if (count != null && count.counted == null && !approval.consumed && count.storageRevision == approval.countRevision &&
        count.capturedQuantity == approval.capturedQuantity && count.position.serialId == null &&
        access.installed.has(LocalStockStoreProfile.COUNT_POST) && access.allow(LocalAction.COUNT_POST, count.countId)) {
        form(LocalAction.COUNT_POST, listOf(idField(LocalField.COUNT_ID, count.countId),
            revisionField(LocalField.EXPECTED_COUNT_REVISION, count.storageRevision),
            idField(LocalField.COUNT_SURPLUS_BASIS_ID, approval.approvalId),
            revisionField(LocalField.EXPECTED_SURPLUS_AUTHORITY_REVISION, approval.authorityRevision)) +
            quantityMetadata(count.capturedQuantity) + listOf(
            exactQuantityField(LocalField.COUNTED_QUANTITY).copy(
                initialValue = LocalScalarValue.Decimal(fixedDecimal(approval.countedQuantity.scaled, approval.countedQuantity.scale)), readOnly = true),
            stockReasonField()),
            text("Post exactly this approved count/basis. Authority revision is not the basis storage generation. The master checks current count, stock, validity, consumption and authorization again; an unconsumed historical read is not permission.",
                "ترحيل هذا الجرد وهذا الأساس المعتمد بدقة. مراجعة الصلاحية ليست مراجعة تخزين الأساس. يعيد الجهاز الرئيسي فحص الجرد والمخزون والصلاحية والاستهلاك والإذن؛ القراءة التاريخية غير المستهلكة ليست إذنًا."))
    } else null
    return LocalWorkflowPage(query.workflow, listOf(record,
        LocalRecord(LocalRecordReference(LocalRecordKind.COUNT, approval.countId), text("Open captured count", "فتح الجرد المثبت"))),
        listOfNotNull(postForm),
        text("Cost-protected basis projection. ${readCut(cut.reply).english} Count: ${readCut(countReply).english}",
            "عرض أساس محمي بصلاحية التكلفة. ${readCut(cut.reply).arabic} الجرد: ${readCut(countReply).arabic}"))
}

internal suspend fun prepareCountSurplus(access: GatewayAccess, intent: LocalCommandIntent): MutationRequest {
    if (intent.action != LocalAction.COUNT_APPROVE_SURPLUS || intent.lineField != null || intent.lines.isNotEmpty()) invalid()
    val input = InputValues(intent.values)
    val id = input.id(LocalField.COUNT_ID)
    access.requireAction(intent.action, id)
    val count = LocalStockQueries.decodeCount(access.read(LocalStockCommands.count(id)).payload) ?: invalid(LocalField.COUNT_ID)
    val expected = input.revision(LocalField.EXPECTED_COUNT_REVISION)
    if (count.countId != id || count.storageRevision != expected || count.counted != null) invalid(LocalField.EXPECTED_COUNT_REVISION)
    if (count.position.serialId != null || count.position.supplierId != null || count.position.custody == StockCustody.REJECTED_HELD) invalid(LocalField.COUNT_ID)
    val counted = input.exactQuantity(LocalField.COUNTED_QUANTITY, count.capturedQuantity)
    if (counted <= count.capturedQuantity) invalid(LocalField.COUNTED_QUANTITY)
    val request = LocalCountSurplusApprovalRequestV1(input.id(LocalField.COUNT_SURPLUS_BASIS_ID), id, expected, counted,
        input.money(LocalField.SURPLUS_VALUE), input.instant(LocalField.VALID_FROM), input.optionalInstant(LocalField.VALID_UNTIL),
        input.text(LocalField.REASON))
    input.finish()
    return MutationRequest(intent.operationId, LocalCountSurplusApprovalProfile.COMMAND, LocalCountSurplusApprovalCodec.encode(request))
}
