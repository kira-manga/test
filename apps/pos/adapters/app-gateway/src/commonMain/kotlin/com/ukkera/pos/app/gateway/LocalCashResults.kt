package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision

/** Exact request targets are also used for current permission hints during retained retry. */
internal fun cashRequestTarget(request: LocalCashRequestV1): DomainId = when (request) {
    is LocalCashRequestV1.ConfigureDrawer -> request.drawerId
    is LocalCashRequestV1.ConfigureCustodian -> request.custodianId
    is LocalCashRequestV1.ConfigureSafe -> request.custodyId
    is LocalCashRequestV1.Open -> request.drawerId
    is LocalCashRequestV1.NonSale -> request.sessionId
    is LocalCashRequestV1.BeginClosing -> request.sessionId
    is LocalCashRequestV1.CancelClosing -> request.sessionId
    is LocalCashRequestV1.RecordClosingCount -> request.sessionId
    is LocalCashRequestV1.DeliverCountedClose -> request.sessionId
    is LocalCashRequestV1.SubmitCountedClose -> request.sessionId
    is LocalCashRequestV1.SubmitClose -> request.sessionId
    is LocalCashRequestV1.Review -> request.sessionId
    is LocalCashRequestV1.DropToSafe -> request.sessionId
    is LocalCashRequestV1.ReceiveSafe -> request.transferId
}

/** Disposition is reviewed from the canonical saved command, not from UI labels or expected cash. */
internal fun cashCloseIntentSummary(request: MutationRequest): List<LocalRecordValue> = buildList {
    if (request.command != LocalSalesStoreProfile.CASH_COMMAND) unavailable()
    val close = LocalCashCommandCodec.decode(request.payload) as? LocalCashRequestV1.SubmitClose ?: unavailable()
    fun id(field: LocalField, value: DomainId) { add(recordValue(fieldLabel(field), literal(value.value))) }
    fun revision(field: LocalField, value: Revision) { add(recordValue(fieldLabel(field), literal(value.value.toString()))) }
    id(LocalField.SESSION_ID, close.sessionId)
    revision(LocalField.EXPECTED_REVISION, close.expectedRevision)
    add(recordValue(fieldLabel(LocalField.COUNTED_CASH), moneyText(close.counted)))
    add(recordValue(fieldLabel(LocalField.RETAINED_FLOAT), moneyText(close.retainedFloat)))
    add(recordValue(text("Actual closing safe delivery — count minus retained", "تسليم الخزنة الفعلي عند الإغلاق — المعدود ناقص المتبقي"),
        moneyText(close.counted - close.retainedFloat)))
    id(LocalField.EVIDENCE_ID, close.countEvidence.evidenceId)
    add(recordValue(fieldLabel(LocalField.OCCURRED_AT), literal(close.countEvidence.occurredAt.canonicalString())))
    close.varianceReason?.let { add(recordValue(fieldLabel(LocalField.VARIANCE_REASON), literal(it))) }
    close.transferId?.let { id(LocalField.TRANSFER_ID, it) }
    close.receivingCustodyId?.let { id(LocalField.RECEIVING_CUSTODY_ID, it) }
    close.expectedSafeRevision?.let { revision(LocalField.EXPECTED_SAFE_REVISION, it) }
    close.deliveryEvidence?.let {
        id(LocalField.DELIVERY_EVIDENCE_ID, it.evidenceId)
        add(recordValue(fieldLabel(LocalField.DELIVERY_OCCURRED_AT), literal(it.occurredAt.canonicalString())))
    }
    add(recordValue(text("Effect", "الأثر"), text("Immutable close and actual disposition; not a sale, expense, employee debt or receiving acknowledgement.",
        "إغلاق ثابت وتصرف فعلي في النقدية؛ ليس بيعًا أو مصروفًا أو دين موظف أو تأكيد استلام.")))
}

/** Canonical stored inputs, not a reconstruction of today's form or expected balance. */
internal fun cashCountIntentSummary(request: MutationRequest): List<LocalRecordValue> = buildList {
    if (request.command != LocalSalesStoreProfile.CASH_COMMAND) unavailable()
    fun id(field: LocalField, value: DomainId) { add(recordValue(fieldLabel(field), literal(value.value))) }
    fun revision(field: LocalField, value: Revision) { add(recordValue(fieldLabel(field), literal(value.value.toString()))) }
    fun session(sessionId: DomainId, expected: Revision) {
        id(LocalField.SESSION_ID, sessionId)
        revision(LocalField.EXPECTED_REVISION, expected)
    }
    fun count(countId: DomainId, amount: Money, retained: Money) {
        id(LocalField.CASH_COUNT_ID, countId)
        add(recordValue(fieldLabel(LocalField.COUNTED_CASH), moneyText(amount)))
        add(recordValue(fieldLabel(LocalField.RETAINED_FLOAT), moneyText(retained)))
        add(recordValue(text("Actual counted disposition — count minus retained", "التصرف الفعلي بالنقدية المعدودة — المعدود ناقص المتبقي"), moneyText(amount - retained)))
    }
    when (val value = LocalCashCommandCodec.decode(request.payload)) {
        is LocalCashRequestV1.RecordClosingCount -> {
            session(value.sessionId, value.expectedRevision)
            value.previousCountId?.let { id(LocalField.PREVIOUS_CASH_COUNT_ID, it) }
            id(LocalField.EVIDENCE_ID, value.evidence.evidenceId)
            add(recordValue(fieldLabel(LocalField.COUNTED_CASH), moneyText(value.counted)))
            add(recordValue(fieldLabel(LocalField.OCCURRED_AT), literal(value.evidence.occurredAt.canonicalString())))
            value.reason?.let { add(recordValue(fieldLabel(LocalField.REASON), literal(it))) }
            add(recordValue(text("Effect", "الأثر"), text("Immutable count/recount only. Original counts and cut remain; no cash movement, final close or balance correction.",
                "عد أو إعادة عد ثابتة فقط. تبقى الأعداد الأصلية وحد الإغلاق؛ دون حركة نقدية أو إغلاق نهائي أو تصحيح رصيد.")))
        }
        is LocalCashRequestV1.DeliverCountedClose -> {
            session(value.sessionId, value.expectedRevision)
            count(value.countId, value.counted, value.retainedFloat)
            id(LocalField.TRANSFER_ID, value.transferId)
            id(LocalField.RECEIVING_CUSTODY_ID, value.receivingCustodyId)
            revision(LocalField.EXPECTED_SAFE_REVISION, value.expectedSafeRevision)
            id(LocalField.DELIVERY_EVIDENCE_ID, value.evidence.evidenceId)
            add(recordValue(fieldLabel(LocalField.DELIVERY_OCCURRED_AT), literal(value.evidence.occurredAt.canonicalString())))
            add(recordValue(text("Effect", "الأثر"), text("Record the original actual delivery, not a new instruction to move cash. Separate receiving acknowledgement and final submission remain. No second sender debit or sale.",
                "تسجيل التسليم الفعلي الأصلي، لا أمر جديد بنقل النقدية. يظل تأكيد الاستلام والتقديم النهائي منفصلين. لا خصم ثانٍ من المرسل أو بيع.")))
        }
        is LocalCashRequestV1.SubmitCountedClose -> {
            session(value.sessionId, value.expectedRevision)
            count(value.countId, value.counted, value.retainedFloat)
            value.expectedTransferRevision?.let { revision(LocalField.EXPECTED_TRANSFER_REVISION, it) }
            value.varianceReason?.let { add(recordValue(fieldLabel(LocalField.VARIANCE_REASON), literal(it))) }
            add(recordValue(text("Effect", "الأثر"), text("Submit the exact retained count and handled disposition. A linked delivery must already be received; no new count, transfer, receiving credit or balancing entry is created.",
                "تقديم العد المحفوظ المحدد والتصرف المعالج. يجب أن يكون التسليم المرتبط مستلمًا بالفعل؛ لا يُنشأ عد أو تحويل أو قيد استلام أو قيد تسوية جديد.")))
        }
        else -> unavailable()
    }
}

private data class CashResultTarget(
    val action: LocalSalesAction, val documentId: DomainId, val kind: LocalRecordKind,
    val id: DomainId, val domainKind: String, val revision: Revision,
)

private fun cashResultTarget(request: LocalCashRequestV1): CashResultTarget {
    fun session(action: LocalSalesAction, id: DomainId, revision: Revision, document: DomainId = id): CashResultTarget =
        CashResultTarget(action, document, LocalRecordKind.CASH_SESSION, id, CashNonSaleStoreProfile.SESSION_KIND, revision)
    return when (request) {
        is LocalCashRequestV1.ConfigureDrawer -> CashResultTarget(LocalSalesAction.DRAWER_CONFIGURED, request.drawerId,
            LocalRecordKind.DRAWER, request.drawerId, LocalSalesStoreProfile.DRAWER_KIND, Revision(0))
        is LocalCashRequestV1.ConfigureCustodian -> CashResultTarget(LocalSalesAction.CUSTODIAN_CONFIGURED, request.custodianId,
            LocalRecordKind.CUSTODIAN, request.custodianId, LocalSalesStoreProfile.CUSTODIAN_KIND, Revision(0))
        is LocalCashRequestV1.ConfigureSafe -> CashResultTarget(LocalSalesAction.SAFE_CONFIGURED, request.custodyId,
            LocalRecordKind.SAFE, request.custodyId, LocalSalesStoreProfile.SAFE_KIND, Revision(0))
        is LocalCashRequestV1.Open -> session(LocalSalesAction.CASH_OPENED, request.sessionId, Revision(0))
        is LocalCashRequestV1.NonSale -> session(LocalSalesAction.CASH_MOVEMENT_RECORDED, request.sessionId, request.expectedRevision.next(), request.sourceId)
        is LocalCashRequestV1.BeginClosing -> session(LocalSalesAction.CASH_CLOSING, request.sessionId, request.expectedRevision.next())
        is LocalCashRequestV1.CancelClosing -> session(LocalSalesAction.CASH_CLOSING_CANCELLED, request.sessionId, request.expectedRevision.next())
        is LocalCashRequestV1.RecordClosingCount -> session(LocalSalesAction.CASH_COUNT_RECORDED, request.sessionId, request.expectedRevision.next(), request.evidence.evidenceId)
        is LocalCashRequestV1.DeliverCountedClose -> CashResultTarget(LocalSalesAction.CASH_COUNTED_DELIVERY_SENT, request.transferId,
            LocalRecordKind.TRANSFER, request.transferId, LocalSalesStoreProfile.TRANSFER_KIND, Revision(0))
        is LocalCashRequestV1.SubmitCountedClose -> session(LocalSalesAction.CASH_COUNTED_CLOSED, request.sessionId, request.expectedRevision.next())
        is LocalCashRequestV1.SubmitClose -> session(LocalSalesAction.CASH_CLOSED, request.sessionId, request.expectedRevision.next())
        is LocalCashRequestV1.Review -> session(LocalSalesAction.CASH_REVIEWED, request.sessionId, request.expectedRevision.next())
        is LocalCashRequestV1.DropToSafe -> CashResultTarget(LocalSalesAction.SAFE_TRANSFER_SENT, request.transferId,
            LocalRecordKind.TRANSFER, request.transferId, LocalSalesStoreProfile.TRANSFER_KIND, Revision(0))
        is LocalCashRequestV1.ReceiveSafe -> CashResultTarget(LocalSalesAction.SAFE_TRANSFER_RECEIVED, request.transferId,
            LocalRecordKind.TRANSFER, request.transferId, LocalSalesStoreProfile.TRANSFER_KIND, request.expectedTransferRevision.next())
    }
}

internal fun cashRecorded(entry: StoredClientIntentV1, reply: MutationReply.Recorded): LocalOperationResult.Recorded {
    val decision = if (reply.committed) LocalRecordedDecision.APPLIED else LocalRecordedDecision.REJECTED
    var message = text("Cash operation recorded; review the original result before continuing.", "سُجلت العملية النقدية؛ راجع النتيجة الأصلية قبل المتابعة.")
    var record: LocalRecord? = null
    try {
        val saved = entry.request()
        if (reply.operationId != entry.operationId || saved.command != LocalSalesStoreProfile.CASH_COMMAND || !actionMatches(entry.localAction, saved)) unavailable()
        val request = LocalCashCommandCodec.decode(saved.payload)
        when (val result = LocalSalesResultCodec.decode(reply.result)) {
            is LocalSalesResultV1.Applied -> {
                val target = cashResultTarget(request)
                if (!reply.committed || result.action != target.action || result.documentId != target.documentId ||
                    result.stockItems.isNotEmpty() || result.revisions.map { it.kind to it.id }.distinct().size != result.revisions.size ||
                    result.revisions.singleOrNull { it.kind == target.domainKind && it.id == target.id }?.revision != target.revision ||
                    result.amount?.minor?.let { it < 0L } == true) unavailable()
                when (request) {
                    is LocalCashRequestV1.Open -> if (result.amount != request.opening) unavailable()
                    is LocalCashRequestV1.SubmitClose -> if (result.amount != request.counted) unavailable()
                    is LocalCashRequestV1.RecordClosingCount -> if (result.amount != request.counted || result.revisions.size != 3 ||
                        result.revisions.singleOrNull { it.kind == LocalSalesStoreProfile.CLOSING_COUNT_KIND && it.id == request.evidence.evidenceId }?.revision != Revision(0) ||
                        result.revisions.none { it.kind == LocalSalesStoreProfile.CLOSING_COUNT_INDEX_KIND && it.id == request.sessionId }) unavailable()
                    is LocalCashRequestV1.DeliverCountedClose -> if (result.amount != request.counted - request.retainedFloat || result.revisions.size != 3 ||
                        result.revisions.singleOrNull { it.kind == CashNonSaleStoreProfile.SESSION_KIND && it.id == request.sessionId }?.revision != request.expectedRevision.next() ||
                        result.revisions.none { it.kind == LocalSalesStoreProfile.CLOSING_COUNT_INDEX_KIND && it.id == request.sessionId }) unavailable()
                    is LocalCashRequestV1.SubmitCountedClose -> if (result.amount != request.counted) unavailable()
                    is LocalCashRequestV1.DropToSafe -> if (result.amount != request.amount) unavailable()
                    is LocalCashRequestV1.ConfigureDrawer, is LocalCashRequestV1.ConfigureCustodian,
                    is LocalCashRequestV1.ConfigureSafe -> if (result.amount != null) unavailable()
                    else -> if (result.amount == null) unavailable()
                }
                message = when (request) {
                    is LocalCashRequestV1.RecordClosingCount -> text("Count recorded without moving cash or changing the frozen expected total. Earlier counts remain in history. After acknowledging this result, choose the disposition or a reasoned recount.",
                        "سُجل العد دون نقل نقدية أو تغيير الإجمالي المتوقع الثابت. تبقى الأعداد السابقة في السجل. بعد تأكيد عرض هذه النتيجة اختر التصرف في النقدية أو إعادة العد مع سبب.")
                    is LocalCashRequestV1.DeliverCountedClose -> text("Actual counted delivery recorded under the original transfer ID. Do not repeat it. The receiving custodian must separately acknowledge receipt before the original close is submitted.",
                        "سُجل التسليم الفعلي من العد بمعرف التحويل الأصلي. لا تكرره. يجب أن يؤكد المستلم الاستلام منفصلًا قبل تقديم الإغلاق الأصلي.")
                    is LocalCashRequestV1.SubmitCountedClose -> text("Original counted close submitted and drawer assignment released. The original count, variance and any previously received safe delivery remain unchanged; no new cash movement or balancing entry was created.",
                        "قُدم إغلاق العد الأصلي وحُرر تعيين الدرج. يبقى العد الأصلي والفرق وأي تسليم خزنة مستلم سابقًا دون تغيير؛ لم تُنشأ حركة نقدية أو قيد تسوية جديد.")
                    is LocalCashRequestV1.SubmitClose -> text("Original counted close and actual disposition recorded. Any closing transfer still needs its separate actual receiving acknowledgement. No balancing sale, expense or employee debt was created.",
                        "سُجل الإغلاق المعدود الأصلي والتصرف الفعلي في النقدية. يحتاج تحويل الإغلاق إلى تأكيد الاستلام الفعلي المنفصل. لم يُنشأ بيع أو مصروف أو دين موظف لتسوية الفرق.")
                    is LocalCashRequestV1.Review -> text("Review appended to the original close. Its count, variance, frozen ledger and any later shift remain unchanged; no physical cash movement was recorded by review.",
                        "أُضيفت المراجعة إلى الإغلاق الأصلي. يظل العد والفرق والسجل الثابت وأي وردية لاحقة دون تغيير؛ لم تسجل المراجعة حركة نقدية فعلية.")
                    is LocalCashRequestV1.ReceiveSafe -> text("Actual receipt of the original transfer recorded. This is not another sale, sender debit or instruction to move cash again.",
                        "سُجل الاستلام الفعلي للتحويل الأصلي. ليس هذا بيعًا آخر أو خصمًا جديدًا من المرسل أو أمرًا بنقل النقدية مجددًا.")
                    else -> message
                }
                record = LocalRecord(LocalRecordReference(target.kind, target.id, target.revision), entry.label.ui(), literal(result.action.name), buildList {
                    add(recordValue(text("Original result document", "مستند النتيجة الأصلي"), literal(result.documentId.value)))
                    result.amount?.let { amount -> add(recordValue(when (request) {
                        is LocalCashRequestV1.SubmitClose, is LocalCashRequestV1.RecordClosingCount,
                        is LocalCashRequestV1.SubmitCountedClose -> fieldLabel(LocalField.COUNTED_CASH)
                        is LocalCashRequestV1.DropToSafe, is LocalCashRequestV1.ReceiveSafe,
                        is LocalCashRequestV1.DeliverCountedClose -> fieldLabel(LocalField.AMOUNT)
                        else -> text("Recorded expected drawer cash", "النقدية المتوقعة المسجلة بالدرج")
                    }, moneyText(amount))) }
                    if (request is LocalCashRequestV1.SubmitClose) {
                        add(recordValue(fieldLabel(LocalField.RETAINED_FLOAT), moneyText(request.retainedFloat)))
                        add(recordValue(text("Actual closing safe delivery", "تسليم الخزنة الفعلي عند الإغلاق"), moneyText(request.counted - request.retainedFloat)))
                        request.transferId?.let { add(recordValue(fieldLabel(LocalField.TRANSFER_ID), literal(it.value))) }
                    }
                    if (request is LocalCashRequestV1.SubmitCountedClose) {
                        add(recordValue(fieldLabel(LocalField.CASH_COUNT_ID), literal(request.countId.value)))
                        add(recordValue(fieldLabel(LocalField.RETAINED_FLOAT), moneyText(request.retainedFloat)))
                        add(recordValue(text("Actual previously handled safe delivery", "تسليم الخزنة الفعلي المعالج سابقًا"), moneyText(request.counted - request.retainedFloat)))
                    }
                    // All returned domain revisions, including distinct kinds with equal IDs.
                    // A capacity failure preserves the definite result, never silently truncates it.
                    result.revisions.forEach { add(recordValue(literal(it.kind), literal("${it.id.value} · ${it.revision.value}"))) }
                })
            }
            is LocalSalesResultV1.Rejected -> {
                if (reply.committed) unavailable()
                message = text("Recorded cash rejection: ${result.rule.name}", "رفض نقدي مسجل: ${result.rule.name}")
            }
            is LocalSalesResultV1.Unavailable -> {
                if (reply.committed) unavailable()
                message = text("Recorded cash refusal: ${result.reason.name}", "رفض نقدي مسجل لعدم التوفر: ${result.reason.name}")
            }
            else -> unavailable()
        }
    } catch (_: Exception) {
        message = text("The master recorded a ${if (reply.committed) "committed" else "rejected"} cash operation, but its details cannot be displayed. Keep the original operation ID; do not repeat a physical cash movement.",
            "سجل الجهاز الرئيسي عملية نقدية ${if (reply.committed) "مثبتة" else "مرفوضة"}، لكن لا يمكن عرض تفاصيلها. احتفظ بمعرف العملية الأصلي؛ لا تكرر حركة نقدية فعلية.")
        record = null
    }
    return LocalOperationResult.Recorded(reply.operationId, decision, message, record, reply.replayed)
}
