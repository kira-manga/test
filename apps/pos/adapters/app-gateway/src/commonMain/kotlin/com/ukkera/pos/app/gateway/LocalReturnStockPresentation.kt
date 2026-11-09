package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.Revision

/** The exact saved acceptance/selection/CAS must close before retention, publication or ACK. */
internal fun validateReturnStockRecorded(entry: StoredClientIntentV1, reply: MutationReply.Recorded) {
    decodeReturnStockRecorded(entry, reply)
}

private fun decodeReturnStockRecorded(entry: StoredClientIntentV1, reply: MutationReply.Recorded):
    Pair<LocalReturnStockRequestV1, LocalReturnStockResultV1> {
    val request = entry.request()
    if (entry.localAction != LocalAction.RETURN_STOCK_POST || entry.formVersion != 1 ||
        request.command != LocalReturnStockProfileV1.COMMAND || entry.actionApproval != null ||
        !actionMatches(entry.localAction, request) ||
        reply.operationId != entry.operationId || reply.originalActorId != entry.owner.actorId) unavailable()
    val original = LocalReturnStockCodecV1.decode(request.payload)
    val total = returnStockSelectionTotal(original)
    val value = LocalReturnStockResultCodecV1.decode(reply.result)
    when (value) {
        is LocalReturnStockResultV1.Rejected -> if (reply.committed || reply.code != "sales.return.stock.rejected" ||
            value.reason !in LocalReturnStockProfileV1.rejectionReasons) unavailable()
        is LocalReturnStockResultV1.Applied -> {
            if (original.expectedDispositionRevision.value == Long.MAX_VALUE) unavailable()
            val posted = original.expectedPostedQuantity + total
            val remaining = original.acceptedQuantity - posted
            if (!reply.committed || reply.code != "committed" || value.returnId != original.returnId ||
                value.restorationId != original.restorationId ||
                value.revision != Revision(original.expectedDispositionRevision.value + 1) ||
                value.postedQuantity != posted || value.remainingQuantity != remaining ||
                value.status != returnStockStatus(original.acceptedQuantity, posted) ||
                value.status == LocalReturnStockStatusV1.PENDING ||
                !returnStockConserves(original.acceptedQuantity, value.postedQuantity, value.remainingQuantity)) unavailable()
        }
    }
    return original to value
}

internal fun returnStockRecorded(entry: StoredClientIntentV1, reply: MutationReply.Recorded): LocalOperationResult.Recorded {
    val (original, value) = decodeReturnStockRecorded(entry, reply)
    if (value is LocalReturnStockResultV1.Rejected) return LocalOperationResult.Recorded(entry.operationId,
        LocalRecordedDecision.REJECTED, text(
            "Original stock posting durably rejected: ${value.reason}. No successful stock disposition or refund is claimed; preserve this original result.",
            "رُفض ترحيل المخزون الأصلي وسُجل الرفض: ${value.reason}. لا يُدعى نجاح تصرف بالمخزون أو رد مال؛ احتفظ بهذه النتيجة الأصلية."), replayed = reply.replayed)
    val applied = value as? LocalReturnStockResultV1.Applied ?: unavailable()
    val record = LocalRecord(LocalRecordReference(LocalRecordKind.RETURN_STOCK, original.returnId, parentId = original.caseId),
        text("Recorded original return-stock posting", "ترحيل مخزون المرتجع الأصلي المسجل"), literal(applied.status.name), listOf(
            recordValue(fieldLabel(LocalField.RETURN_ID), literal(original.returnId.value)),
            recordValue(fieldLabel(LocalField.RESTORATION_ID), literal(original.restorationId.value)),
            recordValue(text("Recorded disposition revision", "مراجعة التصرف المسجلة"), literal(applied.revision.value.toString())),
            recordValue(text("Cumulative quantity posted for this acceptance", "الكمية المرحلة تراكميًا لهذا القبول"), quantityText(applied.postedQuantity)),
            recordValue(text("Stock quantity still pending for this acceptance", "كمية المخزون المعلقة لهذا القبول"), quantityText(applied.remainingQuantity))))
    return LocalOperationResult.Recorded(entry.operationId, LocalRecordedDecision.APPLIED, text(
        "This exact original stock subset is committed: ${applied.status.name}. It does not pay a refund or change immutable CASE acceptance. Quarantined quantities are not sellable. Do not repeat this posting with another identity.",
        "ثُبتت مجموعة المخزون الأصلية الدقيقة: ${applied.status.name}. لا يصرف ذلك رد مال أو يغير قبول المطالبة الثابت. الكميات المحجورة غير متاحة للبيع. لا تكرر هذا الترحيل بمعرف آخر."), record, reply.replayed,
        returnStock = LocalReturnStockOperationDetails(entry.operationId, original.saleId, original.caseId, original.returnId,
            original.restorationId, original.expectedDispositionRevision, applied.revision))
}
