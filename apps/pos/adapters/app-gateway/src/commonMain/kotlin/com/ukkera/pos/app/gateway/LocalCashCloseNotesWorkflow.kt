package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.sales.store.*

internal data class CashCloseNotesContent(
    val records: List<LocalRecord>,
    val forms: List<LocalActionForm>,
    val notice: LocalText,
    val nextCursor: String? = null,
)

/** Separately authorized, bounded history; never a mutation of the original cash report. */
internal suspend fun cashCloseNotes(access: GatewayAccess, close: LocalCashCloseReadResultV1.Close,
    review: Boolean, cursor: String?): CashCloseNotesContent {
    val session = close.session
    val family = "cash-close-notes:${session.id.value}:${if (review) "review" else "own"}"
    val before = parseCursor(cursor, family)
    if (!access.installed.has(LocalCashCloseNoteCodec.QUERY)) return CashCloseNotesContent(emptyList(), emptyList(), text(
        "Linked explanation history is not installed; no empty history was inferred.",
        "سجل التفسيرات المرتبطة غير مثبت؛ لم يُستنتج أن السجل فارغ."))
    val reply = access.read(LocalCashCloseNoteCodec.request(LocalCashCloseNoteQueryV1(session.id, review, before)))
    val page = when (val value = LocalCashCloseNoteCodec.page(reply.payload)) {
        LocalCashCloseNotesPageV1.NotAvailable -> return CashCloseNotesContent(emptyList(), emptyList(), text(
            "Linked explanations are unavailable for this close or cursor. Reload the original close; no history was inferred or changed.",
            "التفسيرات المرتبطة غير متاحة لهذا الإغلاق أو موضع القراءة. أعد تحميل الإغلاق الأصلي؛ لم يُستنتج سجل أو يُغير."))
        is LocalCashCloseNotesPageV1.Page -> value
    }
    if (page.sessionId != session.id || page.originalCloseOperationId != close.operationId || page.before != before) unavailable()
    if (page.sessionRevision != session.revision) throw GatewayFailure(LocalServiceProblem.CASH_RESTART_REQUIRED)
    if (page.totalNotes < 0L || (page.latestNoteId == null) != (page.totalNotes == 0L) || page.notes.size > 5 ||
        page.notes.map { it.id }.distinct().size != page.notes.size ||
        page.notes.any { it.sessionId != session.id || it.originalCloseOperationId != close.operationId ||
            it.evidence.actorId == session.custodianId || it.position.value >= page.totalNotes || it.id == before } ||
        before == null && (page.notes.firstOrNull()?.id != page.latestNoteId ||
            page.notes.firstOrNull()?.position?.value != (page.totalNotes - 1).takeIf { page.totalNotes > 0L }) ||
        before != null && page.totalNotes == 0L) unavailable()
    page.notes.zipWithNext().forEach { (newer, older) ->
        if (newer.previousNoteId != older.id || newer.position.value == 0L || older.position.value != newer.position.value - 1) unavailable()
    }
    val last = page.notes.lastOrNull()
    if (last?.previousNoteId != null) {
        if (page.nextBefore != last.id || page.notes.size != 5) unavailable()
    } else if (page.nextBefore != null) unavailable()
    val forms = if (review && access.installed.has(LocalCashCloseNoteCodec.COMMAND) &&
        access.allow(LocalAction.CASH_ADD_CLOSE_NOTE, session.id)) listOf(form(LocalAction.CASH_ADD_CLOSE_NOTE,
        listOf(idField(LocalField.SESSION_ID, session.id), revisionField(LocalField.EXPECTED_REVISION, session.revision)) +
            listOfNotNull(page.latestNoteId?.let { idField(LocalField.SOURCE_ID, it).copy(label = previousNoteLabel()) }) +
            listOf(newIdField(LocalField.EVIDENCE_ID), instantField(LocalField.OCCURRED_AT),
                scalar(LocalField.REASON, LocalInputKind.MULTILINE, maximum = 256)), text(
            "Append an explanation to this original close. Earlier notes and the count stay unchanged. This is not a tender correction, cash movement, balancing entry or employee debt. A concurrent note or review requires reloading before creating a new intent.",
            "أضف تفسيرًا إلى هذا الإغلاق الأصلي. تبقى التفسيرات السابقة والعد دون تغيير. هذا ليس تصحيح وسيلة دفع أو حركة نقدية أو قيد تسوية أو دين موظف. يتطلب تسجيل تفسير أو مراجعة متزامنة إعادة التحميل قبل إنشاء طلب جديد."))) else emptyList()
    return CashCloseNotesContent(page.notes.map(::cashCloseNoteRecord), forms, text(
        "Linked explanations: ${page.totalNotes} at this read; newest first, five per page. New explanations can arrive between pages; reload to see the latest. ${readCut(reply).english}",
        "التفسيرات المرتبطة: ${page.totalNotes} عند هذه القراءة؛ الأحدث أولًا، خمسة لكل صفحة. قد تصل تفسيرات بين الصفحات؛ أعد التحميل لرؤية الأحدث. ${readCut(reply).arabic}"),
        page.nextBefore?.let { "$family:${it.value}" })
}

internal suspend fun prepareCashCloseNote(access: GatewayAccess, intent: LocalCommandIntent): MutationRequest {
    if (intent.action != LocalAction.CASH_ADD_CLOSE_NOTE || intent.lines.isNotEmpty() || intent.lineField != null) invalid()
    val input = InputValues(intent.values)
    val value = LocalCashCloseNoteRequestV1(input.id(LocalField.SESSION_ID), input.revision(LocalField.EXPECTED_REVISION),
        input.optionalId(LocalField.SOURCE_ID), input.cashEvidence(), input.text(LocalField.REASON))
    input.finish()
    access.requireAction(intent.action, value.sessionId)
    return MutationRequest(intent.operationId, LocalCashCloseNoteCodec.COMMAND, LocalCashCloseNoteCodec.encode(value))
}

internal fun cashCloseNoteSummary(request: MutationRequest): List<LocalRecordValue> {
    if (request.command != LocalCashCloseNoteCodec.COMMAND) unavailable()
    val note = LocalCashCloseNoteCodec.decode(request.payload)
    return listOf(
        recordValue(fieldLabel(LocalField.SESSION_ID), literal(note.sessionId.value)),
        recordValue(fieldLabel(LocalField.EXPECTED_REVISION), literal(note.expectedSessionRevision.value.toString())),
        recordValue(previousNoteLabel(), note.expectedLatestNoteId?.let { literal(it.value) } ?: text("No previous explanation at this read", "لا يوجد تفسير سابق عند هذه القراءة")),
        recordValue(fieldLabel(LocalField.EVIDENCE_ID), literal(note.evidence.evidenceId.value)),
        recordValue(fieldLabel(LocalField.OCCURRED_AT), literal(note.evidence.occurredAt.canonicalString())),
        recordValue(fieldLabel(LocalField.REASON), literal(note.reason)),
        recordValue(text("Effect", "الأثر"), text("Append-only explanation; no count, tender or balance changes and no physical money movement.",
            "تفسير يُضاف فقط؛ دون تغيير العد أو وسيلة الدفع أو الأرصدة ودون حركة أموال فعلية.")),
    )
}

/** The definite kernel result survives a failed detail decoder; never replace the saved identity. */
internal fun cashCloseNoteRecorded(entry: StoredClientIntentV1, reply: MutationReply.Recorded): LocalOperationResult.Recorded {
    val decision = if (reply.committed) LocalRecordedDecision.APPLIED else LocalRecordedDecision.REJECTED
    var record: LocalRecord? = null
    val message = try {
        val saved = entry.request()
        if (reply.operationId != entry.operationId || !actionMatches(entry.localAction, saved) ||
            entry.localAction != LocalAction.CASH_ADD_CLOSE_NOTE) unavailable()
        val request = LocalCashCloseNoteCodec.decode(saved.payload)
        when (val result = LocalCashCloseNoteCodec.result(reply.result)) {
            is LocalCashCloseNoteResultV1.Recorded -> {
                val note = result.note
                if (!reply.committed || note.sessionId != request.sessionId || result.sessionRevision != request.expectedSessionRevision ||
                    note.previousNoteId != request.expectedLatestNoteId || note.evidence.evidenceId != request.evidence.evidenceId ||
                    note.evidence.occurredAt != request.evidence.occurredAt || note.evidence.actorId != entry.owner.actorId ||
                    note.operationId != entry.operationId || note.reason != request.reason) unavailable()
                // Follow the unchanged original close after explicit durable ACK, not a fake new session revision.
                record = cashCloseNoteRecord(note).copy(reference = LocalRecordReference(LocalRecordKind.CASH_SESSION,
                    note.sessionId, result.sessionRevision), selectable = true)
                text("Explanation appended to the original close. The original count, review, balances and later shifts were not changed. No monetary or tender correction was made.",
                    "أُضيف التفسير إلى الإغلاق الأصلي. لم يتغير العد الأصلي أو المراجعة أو الأرصدة أو الورديات اللاحقة. لم يُجرَ تصحيح مالي أو لوسيلة دفع.")
            }
            is LocalCashCloseNoteResultV1.Refused -> {
                if (reply.committed) unavailable()
                cashCloseNoteRefusal(result.reason)
            }
        }
    } catch (_: Exception) {
        record = null
        text("The master recorded a ${if (reply.committed) "committed" else "rejected"} close explanation, but its details cannot be displayed. Keep the original operation ID; do not submit a duplicate explanation.",
            "سجل الجهاز الرئيسي تفسير إغلاق ${if (reply.committed) "مثبتًا" else "مرفوضًا"}، لكن لا يمكن عرض تفاصيله. احتفظ بمعرف العملية الأصلي؛ لا تقدم تفسيرًا مكررًا.")
    }
    return LocalOperationResult.Recorded(reply.operationId, decision, message, record, reply.replayed)
}

private fun cashCloseNoteRecord(note: LocalCashCloseNoteViewV1): LocalRecord = LocalRecord(
    LocalRecordReference(LocalRecordKind.CASH_CLOSE_NOTE, note.id, note.position, note.sessionId),
    text("Linked close explanation", "تفسير مرتبط بالإغلاق"), text("Explanation only — no money movement", "تفسير فقط — دون حركة أموال"), buildList {
        add(recordValue(fieldLabel(LocalField.SESSION_ID), literal(note.sessionId.value)))
        add(recordValue(text("Original close operation", "عملية الإغلاق الأصلية"), literal(note.originalCloseOperationId.value)))
        add(recordValue(text("Explanation sequence (zero-based)", "تسلسل التفسير (يبدأ من الصفر)"), literal(note.position.value.toString())))
        add(recordValue(fieldLabel(LocalField.EVIDENCE_ID), literal(note.id.value)))
        add(recordValue(text("Author", "صاحب التفسير"), literal(note.evidence.actorId.value)))
        add(recordValue(fieldLabel(LocalField.OCCURRED_AT), literal(note.evidence.occurredAt.canonicalString())))
        add(recordValue(text("Master recorded at", "وقت التسجيل بالجهاز الرئيسي"), literal(note.recordedAt.canonicalString())))
        add(recordValue(text("Original explanation operation", "عملية التفسير الأصلية"), literal(note.operationId.value)))
        note.previousNoteId?.let { add(recordValue(previousNoteLabel(), literal(it.value))) }
        add(recordValue(fieldLabel(LocalField.REASON), literal(note.reason)))
    }, selectable = false)

private fun previousNoteLabel(): LocalText = text("Previous linked explanation", "التفسير المرتبط السابق")

private fun cashCloseNoteRefusal(reason: LocalCashCloseNoteRefusalV1): LocalText = when (reason) {
    LocalCashCloseNoteRefusalV1.SESSION_NOT_CLOSED -> text("Refused: the session is not submitted closed.", "رُفض: لم تُقدم الجلسة كإغلاق نهائي.")
    LocalCashCloseNoteRefusalV1.SELF_REVIEW -> text("Refused: the original cashier cannot review their own close.", "رُفض: لا يمكن للكاشير الأصلي مراجعة إغلاقه بنفسه.")
    LocalCashCloseNoteRefusalV1.STALE_REVIEW -> text("Refused: the close or latest explanation changed. Reload; this rejected operation is retained.", "رُفض: تغير الإغلاق أو أحدث تفسير. أعد التحميل؛ تظل العملية المرفوضة محفوظة.")
    LocalCashCloseNoteRefusalV1.DUPLICATE_NOTE -> text("Refused: that explanation identity is already used. Recover the original result before any further action.", "رُفض: استُخدم معرف التفسير بالفعل. استعد النتيجة الأصلية قبل أي إجراء آخر.")
    LocalCashCloseNoteRefusalV1.CAPACITY_REQUIRED -> text("Refused: explanation history needs capacity review. No earlier note was deleted.", "رُفض: يحتاج سجل التفسيرات إلى مراجعة السعة. لم يُحذف تفسير سابق.")
    LocalCashCloseNoteRefusalV1.SESSION_NOT_AVAILABLE -> text("Refused: the original cash session is unavailable.", "رُفض: الجلسة النقدية الأصلية غير متاحة.")
}
