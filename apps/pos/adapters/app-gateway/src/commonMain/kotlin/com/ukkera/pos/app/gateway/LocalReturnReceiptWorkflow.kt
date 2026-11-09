package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

internal suspend fun returnReceiptRead(access: GatewayAccess, saleId: DomainId, after: DomainId? = null,
    limit: Int = 16): Pair<ReadReply.Data, LocalReturnCaseReadResultV1.Receipt>? {
    val (reply, result) = returnCaseRead(access, LocalReturnCaseQueryV1.Receipt(saleId, after, limit))
    if (result == LocalReturnCaseReadResultV1.NotAvailable) return null
    val receipt = result as? LocalReturnCaseReadResultV1.Receipt ?: unavailable()
    val ids = receipt.lines.map { it.portionId }
    if (receipt.saleId != saleId || receipt.lines.size > limit || ids != ids.distinct().sortedBy { it.value } ||
        after != null && ids.any { it.value <= after.value } ||
        receipt.nextAfterPortionId != null && (ids.isEmpty() || receipt.nextAfterPortionId != ids.last()) ||
        receipt.lines.any { it.originalQuantity.scaled <= 0 || it.remainingQuantity.scaled < 0 ||
            it.originalQuantity.unitId != it.remainingQuantity.unitId || it.originalQuantity.scale != it.remainingQuantity.scale ||
            it.remainingQuantity > it.originalQuantity || it.originalRefundable.minor < 0 }) unavailable()
    return reply to receipt
}

/** Bounded public pages, one entitlement revision throughout; no unbounded aggregate fallback. */
internal suspend fun returnReceiptPortion(access: GatewayAccess, saleId: DomainId, portionId: DomainId,
    expected: Revision): LocalReturnReceiptLineV1? {
    var after: DomainId? = null
    var observed = 0
    repeat(256) {
        val receipt = returnReceiptRead(access, saleId, after)?.second ?: return null
        if (receipt.entitlementRevision != expected) returnRestart()
        observed += receipt.lines.size
        if (observed > 256) throw GatewayFailure(LocalServiceProblem.RETURN_CAPACITY_REQUIRED)
        receipt.lines.singleOrNull { it.portionId == portionId }?.let { return it }
        if (receipt.lines.lastOrNull()?.portionId?.value?.let { it > portionId.value } == true) return null
        after = receipt.nextAfterPortionId ?: return null
    }
    throw GatewayFailure(LocalServiceProblem.RETURN_CAPACITY_REQUIRED)
}

internal suspend fun returnReceiptWorkflow(access: GatewayAccess, query: LocalWorkflowQuery): LocalWorkflowPage {
    val ref = query.reference
    if (ref?.kind == LocalRecordKind.RETURN_RECEIPT_PORTION) {
        val saleId = ref.parentId ?: invalid()
        val expected = ref.revision ?: invalid()
        if (query.cursor != null) invalid()
        val portion = returnReceiptPortion(access, saleId, ref.id, expected) ?: return returnNotAvailable(query.workflow)
        return LocalWorkflowPage(query.workflow, listOf(LocalRecord(LocalRecordReference(LocalRecordKind.SALE, saleId),
            text("Original receipt", "الإيصال الأصلي")), returnReceiptLineRecord(saleId, expected, portion, selectable = false)),
            listOfNotNull(returnIntakeForm(access, saleId, portion)), text(
                "The selected original units prefill the claim; enter the quantity actually claimed. Even exhausted entitlement does not erase intake. The master separately assesses remaining rights and original paid allocations.",
                "تملأ الوحدات الأصلية المختارة بيانات المطالبة؛ أدخل الكمية المطالب بها فعليًا. استنفاد الاستحقاق لا يمحو تسجيل المطالبة. يقيّم الجهاز الرئيسي الحقوق المتبقية وتخصيصات الدفع الأصلية بشكل منفصل."))
    }
    if (ref != null && (ref.kind != LocalRecordKind.SALE || ref.parentId != null || ref.revision != null)) invalid()
    val id = ref?.id ?: query.search.takeIf { it.isNotBlank() }?.let(::searchedId)
    if (id == null) {
        if (query.cursor != null) invalid()
        return LocalWorkflowPage(query.workflow, emptyList(), emptyList(), text(
            "Enter an original sale ID to read its authorized local return projection. To retain a claim without a known receipt, use My remedy claims.",
            "أدخل معرف البيع الأصلي لقراءة بيانات المرتجع المحلية المصرح بها. لحفظ مطالبة دون إيصال معلوم، استخدم مطالبات المعالجة الخاصة بي."))
    }
    val prefix = "return-receipt:${id.value}"
    val cursor = returnPageCursor(query.cursor, prefix)
    val (reply, receipt) = returnReceiptRead(access, id, cursor?.after?.let(::searchedId)) ?: return returnNotAvailable(query.workflow)
    if (cursor != null && cursor.revision != receipt.entitlementRevision) returnRestart()
    return LocalWorkflowPage(query.workflow, listOf(LocalRecord(LocalRecordReference(LocalRecordKind.SALE, id),
        text("Original return receipt projection", "بيانات إيصال المرتجع الأصلي"), values = listOf(
            recordValue(fieldLabel(LocalField.EXPECTED_ENTITLEMENT_REVISION), literal(receipt.entitlementRevision.value.toString()))), selectable = false)) +
        receipt.lines.map { returnReceiptLineRecord(id, receipt.entitlementRevision, it) } + listOfNotNull(customerReceiptLink(access, id, documentary = true)),
        listOfNotNull(returnIntakeForm(access, id)), text(
            "Only original portions and remaining entitlement are disclosed, not another cashier's private orders or stock costs. Original refundable value is for the full original portion, NOT a quote for the quantity now claimed. Select a portion to record its actual claim. ${readCut(reply).english}",
            "تُعرض الأجزاء الأصلية والاستحقاق المتبقي فقط، دون طلبات كاشير آخر الخاصة أو تكاليف المخزون. القيمة الأصلية القابلة للرد تخص كامل الجزء الأصلي، وليست تسعيرًا للكمية المطالب بها الآن. اختر جزءًا لتسجيل المطالبة الفعلية. ${readCut(reply).arabic}"),
        receipt.nextAfterPortionId?.let { returnPageCursor(prefix, receipt.entitlementRevision, it.value) })
}

private fun returnReceiptLineRecord(saleId: DomainId, revision: Revision, line: LocalReturnReceiptLineV1,
    selectable: Boolean = true): LocalRecord = LocalRecord(
    LocalRecordReference(LocalRecordKind.RETURN_RECEIPT_PORTION, line.portionId, revision, saleId),
    text("Original sold portion", "جزء البيع الأصلي"), values = listOf(
        recordValue(text("Originally sold quantity", "الكمية المبيعة أصلًا"), quantityText(line.originalQuantity)),
        recordValue(text("Remaining entitlement quantity", "كمية الاستحقاق المتبقية"), quantityText(line.remainingQuantity)),
        recordValue(text("Full original refundable allocation — not remaining due", "تخصيص الرد الأصلي الكامل — ليس المستحق المتبقي"), moneyText(line.originalRefundable))),
    selectable = selectable)

internal suspend fun returnCaseFinance(access: GatewayAccess, query: LocalWorkflowQuery, caseId: DomainId,
    returnId: DomainId): LocalWorkflowPage {
    val original = returnCaseRead(access, caseId) ?: return returnNotAvailable(query.workflow)
    val binding = original.binding ?: unavailable()
    if (original.summary.acceptedReturnId != returnId || original.summary.stage != LocalReturnCaseStageV1.ACCEPTED_DISPOSITION_PENDING) unavailable()
    val reply = access.read(LocalReturnReadCodec.request(LocalReturnQueryV1.Accepted(binding.saleId, caseId, returnId)))
    val result = LocalReturnReadCodec.decode(reply.payload)
    if (result == LocalReturnReadResultV1.NotAvailable) return returnNotAvailable(query.workflow)
    val values = result as? LocalReturnReadResultV1.AcceptedReturns ?: unavailable()
    val value = values.returns.singleOrNull() ?: unavailable()
    if (values.nextAfter != null || value.returnId != returnId || value.saleId != binding.saleId || value.claimId != caseId ||
        value.portionId != binding.portionId || value.quantity != binding.quantity ||
        value.obligations.map { it.originalPaymentId }.distinct().size != value.obligations.size ||
        value.attempts.map { it.attemptId }.distinct().size != value.attempts.size) unavailable()
    requireAcceptedRefund(value)
    val prefix = "return-finance:${returnId.value}"
    val cursor = returnPageCursor(query.cursor, prefix)
    if (cursor != null && cursor.revision != value.revision) returnRestart()
    val total = value.obligations.size + value.attempts.size
    val offset = cursor?.after?.let { returnPageNumber(it, (total - 1).coerceAtLeast(0)) } ?: 0
    val rows = value.obligations.map(::returnObligationRecord) + value.attempts.map(::returnAttemptRecord)
    val page = rows.drop(offset).take(10)
    return LocalWorkflowPage(query.workflow, listOf(returnCaseSummaryRecord(original.summary), LocalRecord(
        LocalRecordReference(LocalRecordKind.RETURN_FINANCE, returnId, parentId = caseId), text("Recorded return obligation", "التزام المرتجع المسجل"),
        literal(value.status), listOf(
            recordValue(fieldLabel(LocalField.SALE_ID), literal(value.saleId.value)),
            recordValue(fieldLabel(LocalField.QUANTITY), quantityText(value.quantity)),
            recordValue(text("Original accepted payable", "مستحق المرتجع المقبول الأصلي"), moneyText(value.originalPayable)),
            recordValue(text("Actually recorded refunded", "المبلغ المردود المسجل فعليًا"), moneyText(value.refunded)),
            recordValue(text("Refund still due", "مبلغ الرد الذي ما زال مستحقًا"), moneyText(value.due)),
            recordValue(text("Requested disposition — not a stock posting", "التصرف المطلوب — ليس ترحيل مخزون"), literal(value.requestedDisposition)),
            recordValue(text("Original tender obligations / attempts", "التزامات وسائل الدفع الأصلية / المحاولات"),
                literal("${value.obligations.size} / ${value.attempts.size}"))), selectable = false)) + page, emptyList(), text(
            "Acceptance is not payment or stock release. Unknown/pending attempts retain their original identities; do not create another payout. This read does not initiate a physical refund. ${readCut(reply).english}",
            "القبول ليس دفعًا أو إتاحة مخزون. تحتفظ المحاولات المعلقة أو غير المحسومة بمعرفاتها الأصلية؛ لا تنشئ صرفًا آخر. هذه القراءة لا تبدأ ردًا فعليًا. ${readCut(reply).arabic}"),
        if (offset + page.size < total) returnPageCursor(prefix, value.revision, (offset + page.size).toString()) else null)
}

private fun returnObligationRecord(value: LocalRefundObligationViewV1): LocalRecord = LocalRecord(
    LocalRecordReference(LocalRecordKind.RETURN_OBLIGATION, value.originalPaymentId), text("Original tender obligation", "التزام وسيلة الدفع الأصلية"),
    literal(value.tender.kind), listOf(recordValue(fieldLabel(LocalField.AMOUNT), moneyText(value.amount))) +
        listOfNotNull(value.tender.tenderId?.let { recordValue(fieldLabel(LocalField.TENDER_ID), literal(it.value)) }), selectable = false)

private fun returnAttemptRecord(value: LocalRefundAttemptViewV1): LocalRecord = LocalRecord(
    LocalRecordReference(LocalRecordKind.PAYMENT, value.attemptId), text("Original refund attempt", "محاولة الرد الأصلية"), literal(value.status), buildList {
        add(recordValue(text("Original payment allocation", "تخصيص الدفعة الأصلية"), literal(value.originalPaymentId.value)))
        add(recordValue(fieldLabel(LocalField.AMOUNT), moneyText(value.amount)))
        add(recordValue(text("Tender kind", "نوع الدفع"), literal(value.tender.kind)))
        add(recordValue(text("Current actor's attempt", "محاولة المستخدم الحالي"), literal(value.ownAttempt.toString())))
        value.intentOperationId?.let { add(recordValue(text("Original intent operation", "عملية الطلب الأصلية"), literal(it.value))) }
        value.activeRecordingOperationId?.let { add(recordValue(text("Reserved observation operation", "عملية تسجيل الإفادة المحجوزة"), literal(it.value))) }
        value.completedRecordingOperationId?.let { add(recordValue(text("Completed observation operation", "عملية تسجيل الإفادة المكتملة"), literal(it.value))) }
        value.cashSessionId?.let { add(recordValue(fieldLabel(LocalField.SESSION_ID), literal(it.value))) }
        value.cashSessionRevision?.let { add(recordValue(fieldLabel(LocalField.EXPECTED_SESSION_REVISION), literal(it.value.toString()))) }
        value.activeRecordingProtocolVersion?.let { add(recordValue(text("Original observation protocol", "بروتوكول الإفادة الأصلي"), literal(it.toString()))) }
    }, selectable = false)
