package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Counts are read from the master; a missing/failed read must never mean "not counted". */
internal suspend fun readCashCounts(access: GatewayAccess, id: DomainId, review: Boolean = false,
    expectedRevision: Revision? = null, before: DomainId? = null): LocalCashCountReadResultV1.Counts? {
    val permission = if (review) SecurityAction.CASH_REVIEW else SecurityAction.CASH_READ
    if (!access.hasHint(Permission(permission, PermissionVerb.PERFORM), id)) throw GatewayFailure(LocalServiceProblem.DENIED)
    val reply = access.read(LocalCashCountReadCodec.request(LocalCashCountQueryV1(id, review, expectedRevision, before)))
    val result = when (val value = LocalCashCountReadCodec.decode(reply.payload)) {
        LocalCashCountReadResultV1.NotAvailable -> return null
        LocalCashCountReadResultV1.CutChanged -> throw GatewayFailure(LocalServiceProblem.CASH_RESTART_REQUIRED)
        is LocalCashCountReadResultV1.Counts -> value
    }
    val session = result.session
    if (session.id != id || (session.custodianId == access.view.actorId) == review ||
        review && session.status !in setOf("SUBMITTED_CLOSED", "REVIEWED") ||
        expectedRevision != null && session.revision != expectedRevision || result.counts.size > 10 ||
        result.counts.map { it.id }.distinct().size != result.counts.size) unavailable()
    val latest = result.latest
    if (result.currentCountId != null && (session.status != "CLOSING" || result.currentCountId != latest?.id) ||
        result.submittedCountId != null && (session.status !in setOf("SUBMITTED_CLOSED", "REVIEWED") || result.submittedCountId != latest?.id) ||
        result.currentCountId != null && result.submittedCountId != null ||
        before == null && result.counts.firstOrNull() != latest ||
        result.nextBeforeCountId != null && result.nextBeforeCountId != result.counts.lastOrNull()?.id) unavailable()
    if (result.transferId == null) {
        if (result.transferRevision != null || result.transferStatus != null || result.retainedFloat != null) unavailable()
    } else {
        val retained = result.retainedFloat ?: unavailable()
        if (latest == null || result.transferRevision == null || result.transferStatus !in setOf("AWAITING_RECEIPT", "RECEIVED") ||
            result.currentCountId == null && result.submittedCountId == null || retained.currency != latest.counted.currency ||
            retained.minor < 0L || retained.minor >= latest.counted.minor) unavailable()
    }
    return result
}

/** The form sequence follows the actual retained phase; it never creates another handover. */
internal fun cashCountedClosingPage(access: GatewayAccess, counts: LocalCashCountReadResultV1.Counts,
    toSafe: Boolean, destinations: List<LocalSafeChoiceV1>): LocalWorkflowPage {
    val session = counts.session
    if (session.status != "CLOSING") unavailable()
    val current = counts.latest?.takeIf { it.id == counts.currentCountId }
    val records = mutableListOf(if (current != null) cashSessionRecord(session) else LocalRecord(
        LocalRecordReference(LocalRecordKind.CASH_SESSION, session.id, session.revision), text("Count the closing cash", "عد نقدية الإغلاق"),
        literal(session.status), listOf(recordValue(fieldLabel(LocalField.DRAWER_ID), literal(session.drawerId.value)),
            recordValue(fieldLabel(LocalField.BUSINESS_DATE), literal(session.businessDate.canonicalString())),
            recordValue(text("Expected total", "الإجمالي المتوقع"), text("Hidden here until a count is recorded", "مخفي هنا حتى تسجيل العد"))), selectable = false))
    current?.let { records += cashCountRecord(it, counts) }
    counts.transferId?.let { records += closingCountTransferRecord(counts, review = false) }
    val forms = mutableListOf<LocalActionForm>()
    val base = listOf(idField(LocalField.SESSION_ID, session.id), revisionField(LocalField.EXPECTED_REVISION, session.revision))
    fun offer(action: LocalAction, fields: List<LocalFormField>, explanation: LocalText) {
        if (access.installed.has(LocalSalesStoreProfile.CASH_COMMAND) && access.allow(action, session.id))
            forms += form(action, fields, explanation)
    }
    if (counts.transferId == null) {
        offer(LocalAction.CASH_CANCEL_CLOSE, base, text(
            "Cancel this closing phase before any actual closing delivery. Previous recorded counts remain immutable history; re-entering closing requires a new count.",
            "ألغِ مرحلة الإغلاق قبل أي تسليم فعلي لنقدية الإغلاق. تبقى الأعداد المسجلة سابقًا في السجل دون تعديل؛ وتحتاج العودة للإغلاق إلى عد جديد."))
        offer(LocalAction.CASH_RECORD_COUNT, base + listOfNotNull(current?.let { idField(LocalField.PREVIOUS_CASH_COUNT_ID, it.id) }) +
            listOf(moneyField(LocalField.COUNTED_CASH), newIdField(LocalField.EVIDENCE_ID), instantField(LocalField.OCCURRED_AT),
                scalar(LocalField.REASON, LocalInputKind.MULTILINE, required = current != null, maximum = 256)),
            if (current == null) text(
                "Record the actual cash count, not the expected balance. This stores an immutable observation; it does not move cash, submit the close or release the drawer.",
                "سجل العد النقدي الفعلي، لا الرصيد المتوقع. يُحفظ إثبات ثابت؛ لا ينقل نقدية أو يقدم الإغلاق أو يحرر الدرج.") else text(
                "Record a new actual count and the mandatory recount reason. The previous count, evidence and original operation remain unchanged. No cash movement or balance adjustment is made.",
                "سجل عدًا فعليًا جديدًا وسبب إعادة العد الإلزامي. يبقى العد السابق وإثباته وعمليته الأصلية دون تغيير. لا تحدث حركة نقدية أو تسوية رصيد."))
    }
    if (current != null) {
        val countedBase = base + listOf(idField(LocalField.CASH_COUNT_ID, current.id),
            moneyField(LocalField.COUNTED_CASH, current.counted).copy(readOnly = true))
        if (counts.transferId == null && toSafe) {
            val choices = destinations.filter { it.currency == current.counted.currency }.map {
                LocalReferenceChoice(LocalRecordReference(LocalRecordKind.SAFE, it.id, it.revision), literal(it.label))
            }
            if (current.counted.minor > 0L && choices.isNotEmpty()) offer(LocalAction.CASH_DELIVER_COUNTED, countedBase + listOf(
                moneyField(LocalField.RETAINED_FLOAT), newIdField(LocalField.TRANSFER_ID),
                scalar(LocalField.RECEIVING_CUSTODY_ID, LocalInputKind.REFERENCE).copy(references = choices),
                newIdField(LocalField.DELIVERY_EVIDENCE_ID), instantField(LocalField.DELIVERY_OCCURRED_AT)), text(
                    "Record only the actual closing handover from this retained count. Delivery equals counted cash minus retained float, not expected cash. Receipt is a separate action by the receiving custodian before final submission. Never repeat physical delivery after a lost response.",
                    "سجل التسليم الفعلي للإغلاق من هذا العد المحفوظ فقط. التسليم يساوي المعدود ناقص المتبقي، لا النقدية المتوقعة. الاستلام إجراء منفصل يقوم به المستلم قبل تقديم الإغلاق النهائي. لا تكرر التسليم الفعلي بعد فقدان الرد."))
        } else if (counts.transferId == null || counts.transferStatus == "RECEIVED") {
            val retained = counts.retainedFloat ?: current.counted
            offer(LocalAction.CASH_SUBMIT_COUNTED, countedBase + listOf(moneyField(LocalField.RETAINED_FLOAT, retained).copy(readOnly = true)) +
                listOfNotNull(counts.transferRevision?.let { revisionField(LocalField.EXPECTED_TRANSFER_REVISION, it) }) +
                scalar(LocalField.VARIANCE_REASON, LocalInputKind.MULTILINE, required = current.counted != current.expected, maximum = 256), text(
                    "Submit this exact recorded count and handled custody as the immutable close. Any variance needs a reason; it is not employee debt or a balancing expense. This action neither moves cash nor creates another safe transfer. The next cashier must independently count their opening float.",
                    "قدم هذا العد المسجل المحدد والعهدة المعالجة كإغلاق ثابت. يحتاج الفرق إلى سبب؛ وليس دين موظف أو مصروفًا لتسوية الرصيد. لا ينقل هذا الإجراء نقدية ولا ينشئ تحويل خزنة آخر. يجب على الكاشير التالي عد نقدية افتتاحه بصورة مستقلة."))
        }
    }
    val notice = when {
        current == null -> text("Record a count before choosing its disposition or final submission. Previous cancelled-cycle counts are available in history only.",
            "سجل العد قبل اختيار التصرف فيه أو تقديم الإغلاق النهائي. أعداد الدورات الملغاة السابقة متاحة في السجل فقط.")
        counts.transferStatus == "AWAITING_RECEIPT" -> text("Actual delivery is recorded; custody is awaiting the receiving owner's acknowledgement. Recount, cancellation and final submission are not offered. Keep the original transfer ID and do not move cash again.",
            "سُجل التسليم الفعلي؛ تنتظر العهدة تأكيد صاحب عهدة الاستلام. لا تتاح إعادة العد أو الإلغاء أو التقديم النهائي. احتفظ بمعرف التحويل الأصلي ولا تنقل النقدية مجددًا.")
        counts.transferStatus == "RECEIVED" -> text("Actual receipt is recorded. Submit the original counted close separately; the drawer is still assigned until that submission succeeds.",
            "سُجل الاستلام الفعلي. قدم إغلاق العد الأصلي منفصلًا؛ يظل الدرج معينًا حتى نجاح التقديم.")
        else -> text("The count is retained. Recount with a reason, retain all counted cash, or record an actual safe delivery. No new sale or balance correction is created.",
            "العد محفوظ. أعد العد مع سبب، أو احتفظ بكل النقدية المعدودة، أو سجل تسليمًا فعليًا للخزنة. لا يُنشأ بيع جديد أو تصحيح رصيد.")
    }
    return LocalWorkflowPage(LocalWorkflow.CASH, records, forms, notice)
}

internal suspend fun cashCountHistoryWorkflow(access: GatewayAccess, query: LocalWorkflowQuery, id: DomainId, review: Boolean): LocalWorkflowPage {
    var expected: Revision? = null
    var before: DomainId? = null
    val role = if (review) "review" else "own"
    query.cursor?.let {
        val parts = it.split(':')
        if (parts.size != 5 || parts[0] != "cash-counts" || parts[1] != id.value || parts[2] != role) invalid()
        val revision = parts[3].toLongOrNull() ?: invalid()
        if (revision < 0L || revision.toString() != parts[3]) invalid()
        expected = Revision(revision)
        before = searchedId(parts[4])
    }
    val counts = readCashCounts(access, id, review, expected, before) ?: return cashSessionUnavailable()
    val records = mutableListOf(LocalRecord(LocalRecordReference(LocalRecordKind.CASH_SESSION, id, counts.session.revision),
        text("Original cash count history", "سجل عد النقدية الأصلي"), literal(counts.session.status), listOf(
            recordValue(fieldLabel(LocalField.CUSTODIAN_ID), literal(counts.session.custodianId.value)),
            recordValue(text("Session revision for every page", "مراجعة الجلسة لكل الصفحات"), literal(counts.session.revision.value.toString())),
            recordValue(text("Latest count sequence (zero-based)", "تسلسل آخر عد (يبدأ من الصفر)"), literal(counts.latest?.position?.value?.toString() ?: "—"))), selectable = false))
    records += counts.counts.map { cashCountRecord(it, counts) }
    counts.transferId?.let { records += closingCountTransferRecord(counts, review) }
    return LocalWorkflowPage(LocalWorkflow.CASH, records, emptyList(), text(
        "Immutable original counts, newest first, at one session revision. Recounts never overwrite earlier amounts or reasons. A cancelled cycle is history, not the current count. This read neither submits nor acknowledges anything.",
        "أعداد أصلية ثابتة، الأحدث أولًا، عند مراجعة واحدة للجلسة. لا تستبدل إعادة العد المبالغ أو الأسباب السابقة. الدورة الملغاة سجل وليست العد الحالي. لا تقدم هذه القراءة أو تؤكد أي شيء."),
        counts.nextBeforeCountId?.let { "cash-counts:${id.value}:$role:${counts.session.revision.value}:${it.value}" })
}

private fun cashCountRecord(count: LocalCashCountViewV1, cut: LocalCashCountReadResultV1.Counts): LocalRecord = LocalRecord(
    LocalRecordReference(LocalRecordKind.CASH_COUNT, count.id), text("Recorded cash count", "عد النقدية المسجل"), when (count.id) {
        cut.currentCountId -> text("Current closing count", "عد الإغلاق الحالي")
        cut.submittedCountId -> text("Original submitted count", "العد الأصلي المقدم")
        else -> text("Immutable historical count", "عد تاريخي ثابت")
    }, buildList {
        add(recordValue(fieldLabel(LocalField.COUNTED_CASH), moneyText(count.counted)))
        add(recordValue(text("Expected at the original closing cut", "المتوقع عند حد الإغلاق الأصلي"), moneyText(count.expected)))
        add(recordValue(text("Count minus original expected", "العد ناقص المتوقع الأصلي"), moneyText(count.counted - count.expected)))
        add(recordValue(text("Ledger cut · count sequence · closing cycle", "حد السجل · تسلسل العد · دورة الإغلاق"),
            literal("${count.ledgerRevision.value} · ${count.position.value} · ${count.cycleRevision.value}")))
        add(recordValue(text("Counted by", "القائم بالعد"), literal(count.evidence.actorId.value)))
        add(recordValue(fieldLabel(LocalField.OCCURRED_AT), literal(count.evidence.occurredAt.canonicalString())))
        add(recordValue(text("Master recorded at", "وقت التسجيل بالجهاز الرئيسي"), literal(count.recordedAt.canonicalString())))
        add(recordValue(text("Original count operation", "عملية العد الأصلية"), literal(count.operationId.value)))
        count.previousCountId?.let { add(recordValue(text("Previous history record", "السجل التاريخي السابق"), literal(it.value))) }
        count.replacesCountId?.let { add(recordValue(fieldLabel(LocalField.PREVIOUS_CASH_COUNT_ID), literal(it.value))) }
        count.reason?.let { add(recordValue(fieldLabel(LocalField.REASON), literal(it))) }
    }, selectable = false)

private fun closingCountTransferRecord(counts: LocalCashCountReadResultV1.Counts, review: Boolean): LocalRecord {
    val id = counts.transferId ?: unavailable()
    val count = counts.latest ?: unavailable()
    val retained = counts.retainedFloat ?: unavailable()
    return LocalRecord(LocalRecordReference(LocalRecordKind.TRANSFER, id, counts.transferRevision),
        text("Original counted closing delivery", "التسليم الأصلي للعد النقدي عند الإغلاق"), literal(counts.transferStatus ?: unavailable()), listOf(
            recordValue(fieldLabel(LocalField.CASH_COUNT_ID), literal(count.id.value)),
            recordValue(fieldLabel(LocalField.COUNTED_CASH), moneyText(count.counted)),
            recordValue(fieldLabel(LocalField.RETAINED_FLOAT), moneyText(retained)),
            recordValue(fieldLabel(LocalField.AMOUNT), moneyText(count.counted - retained))), selectable = !review)
}
