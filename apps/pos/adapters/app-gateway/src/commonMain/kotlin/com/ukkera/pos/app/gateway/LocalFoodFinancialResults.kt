package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Review the canonical command, not arbitrary display fields or a newly rebuilt request. */
internal fun foodFinancialIntentSummary(request: MutationRequest, intent: LocalCommandIntent): List<LocalRecordValue> = buildList {
    val value = savedFoodFinancial(request)
    fun id(field: LocalField, id: DomainId) { add(foodFinancialId(field, id)) }
    fun revision(field: LocalField, revision: Revision) { add(recordValue(fieldLabel(field), literal(revision.value.toString()))) }
    fun order(target: DomainId, expected: Revision) { id(LocalField.ORDER_ID, target); revision(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION, expected) }
    fun collection(target: DomainId, expected: Revision) { id(LocalField.COLLECTION_ID, target); revision(LocalField.EXPECTED_COLLECTION_REVISION, expected) }
    when (value) {
        is LocalFoodFinancialRequestV1.BeginCollection -> {
            order(value.orderId, value.expectedOrderRevision)
            id(LocalField.COLLECTION_ID, value.collectionId)
            id(LocalField.SESSION_ID, value.sessionId)
            revision(LocalField.EXPECTED_SESSION_REVISION, value.expectedSessionRevision)
            // Fresh preparation already checked this amount against the exact expected order.
            // It is a reviewed quote, not an added payment field in the retained command.
            add(recordValue(text("Reviewed original quote — not money collected", "التسعير الأصلي المراجع — ليس مالًا محصلًا"),
                moneyText(InputValues(intent.values).money(LocalField.AMOUNT))))
        }
        is LocalFoodFinancialRequestV1.FinalizePaid -> {
            collection(value.collectionId, value.expectedCollectionRevision)
            id(LocalField.SALE_ID, value.saleId)
        }
        is LocalFoodFinancialRequestV1.SendReserved -> {
            order(value.orderId, value.expectedOrderRevision)
            collection(value.collectionId, value.expectedCollectionRevision)
            value.portionIds.forEach { id(LocalField.PORTION_ID, it) }
        }
        is LocalFoodFinancialRequestV1.PaidPickup -> {
            order(value.orderId, value.expectedOrderRevision)
            id(LocalField.COLLECTION_ID, value.collectionId)
            id(LocalField.PORTION_ID, value.portionId)
            revision(LocalField.EXPECTED_FOOD_PORTION_DOMAIN_REVISION, value.expectedPortionRevision)
        }
        is LocalFoodFinancialRequestV1.CancelWholeUnsent -> {
            collection(value.collectionId, value.expectedCollectionRevision)
            revision(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION, value.expectedOrderRevision)
            id(LocalField.RETURN_ID, value.refundId)
            id(LocalField.REASON_ID, value.reasonId)
        }
        is LocalFoodFinancialRequestV1.CloseResolvedWork -> {
            order(value.orderId, value.expectedOrderRevision)
            collection(value.collectionId, value.expectedCollectionRevision)
        }
        is LocalFoodFinancialRequestV1.RecordQualityClaim -> {
            id(LocalField.CASE_ID, value.claimId)
            addAll(foodQualityClaimValues(value.facts))
        }
        else -> unavailable()
    }
    add(recordValue(text("Retained command profile", "ملف الأمر المحفوظ"), literal("${request.command.name}/${request.command.version}")))
    add(recordValue(text("Nonphysical operation", "عملية دون حركة مال فعلية"), foodFinancialNotice()))
    if (size !in 1..32) invalid()
}

internal fun foodFinancialRecorded(entry: StoredClientIntentV1, reply: MutationReply.Recorded): LocalOperationResult.Recorded {
    val decision = if (reply.committed) LocalRecordedDecision.APPLIED else LocalRecordedDecision.REJECTED
    var record: LocalRecord? = null
    var message: LocalText
    try {
        if (reply.operationId != entry.operationId || !actionMatches(entry.localAction, entry.request())) unavailable()
        val request = savedFoodFinancial(entry.request())
        val result = LocalFoodFinancialCodecV1.result(reply.result)
        if (result.recordingOperationId != null || result.applicationOperationId != null || result.pendingReason != null ||
            result.code.isBlank() || result.code.length > 160) unavailable()
        if (!reply.committed) {
            if (result.documentId != null || result.revisions.isNotEmpty() || result.amount != null) unavailable()
            message = text("The food operation was durably rejected: ${result.code}. It was not applied. Existing money, stock and obligations remain; no payment or refund should be repeated.",
                "رُفضت عملية الطعام وسُجل الرفض: ${result.code}. لم تُنفذ. تظل الأموال والمخزون والالتزامات السابقة؛ لا تكرر دفعًا أو رد مبلغ.")
        } else {
            val (code, id, kind) = when (request) {
                is LocalFoodFinancialRequestV1.BeginCollection -> Triple("food.collection.locked", request.collectionId, LocalRecordKind.FOOD_COLLECTION)
                is LocalFoodFinancialRequestV1.FinalizePaid -> Triple("food.sale.finalized", request.saleId, LocalRecordKind.FOOD_SALE)
                is LocalFoodFinancialRequestV1.SendReserved -> Triple("food.reserved.sent", request.orderId, LocalRecordKind.FOOD_ORDER)
                is LocalFoodFinancialRequestV1.PaidPickup -> Triple("food.paid_pickup.recorded", request.orderId, LocalRecordKind.FOOD_ORDER)
                is LocalFoodFinancialRequestV1.CancelWholeUnsent -> Triple("food.unsent.cancelled_refund_obligation", request.refundId, LocalRecordKind.FOOD_REFUND)
                is LocalFoodFinancialRequestV1.CloseResolvedWork -> Triple("food.work.closed", request.orderId, LocalRecordKind.FOOD_ORDER)
                is LocalFoodFinancialRequestV1.RecordQualityClaim -> Triple("food.quality_claim.recorded", request.claimId, LocalRecordKind.FOOD_QUALITY_CLAIM)
                else -> unavailable()
            }
            val documentKind = when (kind) {
                LocalRecordKind.FOOD_COLLECTION -> LocalFoodFinancialProfileV1.COLLECTION
                LocalRecordKind.FOOD_SALE -> LocalFoodFinancialProfileV1.SALE
                LocalRecordKind.FOOD_REFUND -> LocalFoodFinancialProfileV1.REFUND
                LocalRecordKind.FOOD_QUALITY_CLAIM -> LocalFoodFinancialProfileV1.CLAIM
                else -> LocalFoodProfileV1.ORDER
            }
            if (result.code != code || result.documentId != id || result.revisions.isEmpty() ||
                result.revisions.map { it.kind to it.id }.distinct().size != result.revisions.size ||
                result.revisions.any { it.storageRevision.value < 1L } ||
                result.revisions.none { it.kind == documentKind && it.id == id }) unavailable()
            val hasAmount = request is LocalFoodFinancialRequestV1.BeginCollection || request is LocalFoodFinancialRequestV1.FinalizePaid ||
                request is LocalFoodFinancialRequestV1.CancelWholeUnsent
            if (hasAmount != (result.amount != null) || result.amount?.minor?.let { it < 0L } == true) unavailable()
            message = foodFinancialAppliedMessage(entry.localAction)
            record = LocalRecord(LocalRecordReference(kind, id), entry.label.ui(), literal(code), buildList {
                result.amount?.let { amount -> add(recordValue(if (request is LocalFoodFinancialRequestV1.CancelWholeUnsent)
                    text("Original-tender refund liability — not paid", "التزام الرد بوسيلة الدفع الأصلية — غير مدفوع")
                    else text("Original document amount — no new physical collection", "مبلغ المستند الأصلي — لا تحصيل فعلي جديد"), moneyText(amount))) }
                addAll(foodFinancialRecordedRevisions(result.revisions))
            }) // Storage generations never occupy the domain-only reference.revision field.
        }
    } catch (_: Exception) {
        // The outer kernel result remains definite even when detail decoding is unavailable.
        // Never turn a committed result into an unknown payment or suggest another collection.
        message = text("The master recorded a ${if (reply.committed) "committed" else "rejected"} food operation, but this client cannot display its details. Keep the original operation ID; do not repeat payment or refund.",
            "سجل الجهاز الرئيسي عملية طعام ${if (reply.committed) "مثبتة" else "مرفوضة"}، لكن هذا العميل لا يستطيع عرض تفاصيلها. احتفظ بمعرف العملية الأصلي؛ لا تكرر دفعًا أو رد مبلغ.")
        record = null
    }
    return LocalOperationResult.Recorded(reply.operationId, decision, message, record, reply.replayed)
}

/** Split long result metadata into bounded blocks, without dropping any recorded revision. */
private fun foodFinancialRecordedRevisions(revisions: List<LocalFoodStorageRevisionV1>): List<LocalRecordValue> = buildList {
    var block = ""
    fun flush() {
        if (block.isNotEmpty()) add(recordValue(text("Recorded storage generations", "مراجعات التخزين المسجلة"), literal(block)))
        block = ""
    }
    revisions.forEach {
        val line = "${it.kind} · \u2066${it.id.value}\u2069 · ${it.storageRevision.value}"
        if (line.length > 2048) unavailable()
        if (block.length + line.length + 1 > 2048) flush()
        block += (if (block.isEmpty()) "" else "\n") + line
    }
    flush()
    if (size > 31) unavailable()
}

private fun foodFinancialAppliedMessage(action: LocalAction): LocalText = when (action) {
    LocalAction.FOOD_BEGIN_COLLECTION -> text("Original food quote locked and unsent ingredients reserved. No money was collected and no ingredients were issued by this begin operation.",
        "قُفل تسعير الطعام الأصلي وحُجزت المكونات غير المرسلة. لم يُحصّل مال ولم تُصرف مكونات بواسطة عملية البدء هذه.")
    LocalAction.FOOD_FINALIZE_PAID -> text("The actually covered food sale and original entitlement are retained. Finalization is not another collection, ingredient issue, customer pickup or physical receipt delivery.",
        "حُفظ بيع الطعام المغطى فعليًا والاستحقاق الأصلي. التثبيت ليس تحصيلًا آخر أو صرف مكونات أو استلام عميل أو تسليم إيصال فعليًا.")
    LocalAction.FOOD_SEND_RESERVED -> text("Selected reserved ingredients issued once; preparation tasks retained. This is not payment, physical printing or proof that preparation started.",
        "صُرفت المكونات المحجوزة المحددة مرة واحدة وحُفظت مهام التحضير. ليس هذا دفعًا أو طباعة فعلية أو إثبات بدء التحضير.")
    LocalAction.FOOD_PAID_PICKUP -> text("Explicit pickup recorded against the original finalized paid coverage. No second collection or ingredient issue occurred.",
        "سُجل الاستلام الصريح مقابل التغطية المدفوعة المثبتة الأصلية. لم يحدث تحصيل أو صرف مكونات ثانٍ.")
    LocalAction.FOOD_CANCEL_WHOLE_UNSENT -> text("Whole never-sent check cancelled; its unissued reservations released and actual original-tender refund obligation retained. No refund was paid.",
        "أُلغي الطلب الكامل الذي لم يُرسل وأُلغيت حجوزاته غير المصروفة وحُفظ التزام الرد الفعلي بوسيلة الدفع الأصلية. لم يُدفع رد مبلغ.")
    LocalAction.FOOD_CLOSE_RESOLVED -> text("Resolved food responsibility closed. Paid/refund/claim history remains; no cash drawer was closed or money moved.",
        "أُغلقت مسؤولية الطعام المحسومة. يظل تاريخ البيع والرد والمطالبات محفوظًا؛ لم يُغلق درج نقد ولم يتحرك مال.")
    LocalAction.FOOD_RECORD_QUALITY_CLAIM -> text("Customer quality/fault assertions recorded unchanged as unresolved intake. This is not delivery proof, approved remedy, charge waiver, stock restoration or payout.",
        "سُجلت إفادات العميل بالجودة أو الخطأ دون تغيير كمطالبة غير محسومة. ليست إثبات تسليم أو معالجة معتمدة أو إعفاء رسوم أو إعادة مخزون أو صرفًا.")
    else -> unavailable()
}
