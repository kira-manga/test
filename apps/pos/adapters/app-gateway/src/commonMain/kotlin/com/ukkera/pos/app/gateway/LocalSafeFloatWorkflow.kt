package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

internal val safeFloatActions: Set<LocalAction> = setOf(LocalAction.CASH_ISSUE_SAFE_FLOAT,
    LocalAction.CASH_RECEIVE_SAFE_FLOAT, LocalAction.CASH_RECALL_SAFE_FLOAT)

internal data class SafeFloatContent(val records: List<LocalRecord>, val forms: List<LocalActionForm>)

private val noSafeFloat = SafeFloatContent(emptyList(), emptyList())

/** Safe custodian: availability, unacknowledged floats (recall) and a new counted issue. */
internal suspend fun safeFloatSafeContent(access: GatewayAccess, safeId: DomainId, safeRevision: Revision): SafeFloatContent {
    if (!access.installed.has(LocalSafeFloatCodec.QUERY)) return noSafeFloat
    val page = LocalSafeFloatCodec.page(access.read(LocalSafeFloatCodec.request(LocalSafeFloatQueryV1.Safe(safeId))).payload)
    val safe = when (page) {
        LocalSafeFloatPageV1.NotAvailable -> return noSafeFloat
        is LocalSafeFloatPageV1.Safe -> page
        is LocalSafeFloatPageV1.Session -> unavailable()
    }
    if (safe.safeId != safeId || safe.safeRevision != safeRevision ||
        safe.awaiting.any { it.safeId != safeId || it.status != LocalSafeFloatStatusV1.AWAITING_RECEIPT }) unavailable()
    val records = listOf(LocalRecord(LocalRecordReference(LocalRecordKind.SAFE, safeId, safe.safeRevision),
        text("Safe float ledger", "سجل عهدة الخزنة المصروفة"), text("Custody only — not revenue", "عهدة فقط — ليست إيرادًا"), listOf(
            recordValue(text("Recorded received", "المستلم المسجل"), moneyText(safe.received)),
            recordValue(text("Floats issued", "العهد المصروفة"), moneyText(safe.issued)),
            recordValue(text("Floats recalled", "العهد المستردة"), moneyText(safe.recalled)),
            recordValue(text("Available in safe", "المتاح في الخزنة"), moneyText(safe.available)),
            recordValue(text("Unacknowledged floats", "عهد غير مؤكدة الاستلام"), literal(safe.awaiting.size.toString())),
        ), selectable = false)) + safe.awaiting.map(::safeFloatRecord)
    val forms = mutableListOf<LocalActionForm>()
    if (access.installed.has(LocalSafeFloatCodec.COMMAND)) {
        if (access.allow(LocalAction.CASH_ISSUE_SAFE_FLOAT, safeId)) forms += form(LocalAction.CASH_ISSUE_SAFE_FLOAT, listOf(
            idField(LocalField.CUSTODY_ID, safeId), revisionField(LocalField.EXPECTED_SAFE_REVISION, safe.safeRevision),
            revisionField(LocalField.EXPECTED_REVISION, safe.ledgerRevision), idField(LocalField.SESSION_ID, null, readOnly = false),
            newIdField(LocalField.TRANSFER_ID), moneyField(LocalField.AMOUNT), newIdField(LocalField.EVIDENCE_ID), instantField(LocalField.OCCURRED_AT)),
            text("Record counted cash you are handing from this safe to another employee's OPEN drawer session (opening top-up or replenishment). It is not drawer cash until that cashier counts and acknowledges it; until then it stays your custody exception.",
                "سجل النقدية المعدودة التي تسلمها من هذه الخزنة إلى جلسة درج مفتوحة لموظف آخر (تعزيز افتتاحي أو تزويد). لا تصبح نقدية الدرج حتى يعدها الكاشير ويؤكد استلامها؛ وحتى ذلك تبقى استثناء عهدة عليك."))
        safe.awaiting.forEach { float ->
            if (access.allow(LocalAction.CASH_RECALL_SAFE_FLOAT, float.id)) forms += form(LocalAction.CASH_RECALL_SAFE_FLOAT, listOf(
                idField(LocalField.TRANSFER_ID, float.id), revisionField(LocalField.EXPECTED_SOURCE_REVISION, float.revision),
                revisionField(LocalField.EXPECTED_REVISION, safe.ledgerRevision), newIdField(LocalField.EVIDENCE_ID),
                instantField(LocalField.OCCURRED_AT), scalar(LocalField.REASON, LocalInputKind.MULTILINE, maximum = 256)),
                text("Recall only cash physically returned to the safe that the cashier never acknowledged. The original issue stays visible.",
                    "استرد فقط النقدية التي أعيدت فعليًا إلى الخزنة ولم يؤكد الكاشير استلامها. يبقى الصرف الأصلي ظاهرًا."))
        }
    }
    return SafeFloatContent(records, forms)
}

/** Drawer custodian: floats addressed to their own OPEN session, each with a counted receipt. */
internal suspend fun safeFloatSessionContent(access: GatewayAccess, sessionId: DomainId, sessionRevision: Revision): SafeFloatContent {
    if (!access.installed.has(LocalSafeFloatCodec.QUERY)) return noSafeFloat
    val page = LocalSafeFloatCodec.page(access.read(LocalSafeFloatCodec.request(LocalSafeFloatQueryV1.Session(sessionId))).payload)
    val session = when (page) {
        LocalSafeFloatPageV1.NotAvailable -> return noSafeFloat
        is LocalSafeFloatPageV1.Session -> page
        is LocalSafeFloatPageV1.Safe -> unavailable()
    }
    if (session.sessionId != sessionId || session.floats.any { it.sessionId != sessionId || it.receivingCustodianId != access.view.actorId }) unavailable()
    if (session.sessionRevision != sessionRevision) throw GatewayFailure(LocalServiceProblem.CASH_RESTART_REQUIRED)
    val forms = if (!access.installed.has(LocalSafeFloatCodec.COMMAND)) emptyList() else session.floats
        .filter { it.status == LocalSafeFloatStatusV1.AWAITING_RECEIPT && access.allow(LocalAction.CASH_RECEIVE_SAFE_FLOAT, it.id) }
        .map { float -> form(LocalAction.CASH_RECEIVE_SAFE_FLOAT, listOf(idField(LocalField.TRANSFER_ID, float.id),
            revisionField(LocalField.EXPECTED_SOURCE_REVISION, float.revision), revisionField(LocalField.EXPECTED_REVISION, sessionRevision),
            moneyField(LocalField.AMOUNT), newIdField(LocalField.EVIDENCE_ID), instantField(LocalField.OCCURRED_AT)),
            text("Count the cash actually handed to you and enter that count. Only an exact match is accepted into your drawer; a different count is refused and stays with the safe custodian.",
                "عد النقدية التي سُلمت إليك فعليًا وأدخل العدد. لا يُقبل في درجك إلا التطابق التام؛ ويُرفض أي عدد مختلف ويبقى على أمين الخزنة.")) }
    return SafeFloatContent(session.floats.map(::safeFloatRecord), forms)
}

private fun safeFloatRecord(float: LocalSafeFloatViewV1): LocalRecord = LocalRecord(
    LocalRecordReference(LocalRecordKind.SAFE_FLOAT, float.id, float.revision),
    text("Safe float to drawer", "عهدة من الخزنة إلى الدرج"), literal(float.status.name), buildList {
        add(recordValue(fieldLabel(LocalField.AMOUNT), moneyText(float.amount)))
        add(recordValue(fieldLabel(LocalField.CUSTODY_ID), literal(float.safeId.value)))
        add(recordValue(fieldLabel(LocalField.SESSION_ID), literal(float.sessionId.value)))
        add(recordValue(text("Issued by", "صرفها"), literal(float.safeCustodianId.value)))
        add(recordValue(text("Receiving cashier", "الكاشير المستلم"), literal(float.receivingCustodianId.value)))
        add(recordValue(text("Issued at", "صُرفت في"), literal(float.issue.occurredAt.canonicalString())))
        float.receipt?.let { add(recordValue(text("Counted receipt at", "استُلمت معدودة في"), literal(it.occurredAt.canonicalString()))) }
        float.recall?.let { add(recordValue(text("Recalled at", "استُردت في"), literal(it.occurredAt.canonicalString()))) }
        float.recallReason?.let { add(recordValue(fieldLabel(LocalField.REASON), literal(it))) }
    }, selectable = false)

internal fun safeFloatRequestTarget(request: LocalSafeFloatRequestV1): DomainId = when (request) {
    is LocalSafeFloatRequestV1.Issue -> request.safeId
    is LocalSafeFloatRequestV1.Receive -> request.floatId
    is LocalSafeFloatRequestV1.Recall -> request.floatId
}

internal fun safeFloatAction(request: LocalSafeFloatRequestV1): LocalAction = when (request) {
    is LocalSafeFloatRequestV1.Issue -> LocalAction.CASH_ISSUE_SAFE_FLOAT
    is LocalSafeFloatRequestV1.Receive -> LocalAction.CASH_RECEIVE_SAFE_FLOAT
    is LocalSafeFloatRequestV1.Recall -> LocalAction.CASH_RECALL_SAFE_FLOAT
}

internal suspend fun prepareSafeFloat(access: GatewayAccess, intent: LocalCommandIntent): MutationRequest {
    if (intent.action !in safeFloatActions || intent.lines.isNotEmpty() || intent.lineField != null) invalid()
    val input = InputValues(intent.values)
    val value: LocalSafeFloatRequestV1 = when (intent.action) {
        LocalAction.CASH_ISSUE_SAFE_FLOAT -> LocalSafeFloatRequestV1.Issue(input.id(LocalField.CUSTODY_ID),
            input.revision(LocalField.EXPECTED_SAFE_REVISION), input.revision(LocalField.EXPECTED_REVISION),
            input.id(LocalField.SESSION_ID), input.id(LocalField.TRANSFER_ID), input.money(LocalField.AMOUNT).also {
                if (it.minor <= 0L) invalid(LocalField.AMOUNT) }, input.cashEvidence())
        LocalAction.CASH_RECEIVE_SAFE_FLOAT -> LocalSafeFloatRequestV1.Receive(input.id(LocalField.TRANSFER_ID),
            input.revision(LocalField.EXPECTED_SOURCE_REVISION), input.revision(LocalField.EXPECTED_REVISION),
            input.money(LocalField.AMOUNT).also { if (it.minor < 0L) invalid(LocalField.AMOUNT) }, input.cashEvidence())
        LocalAction.CASH_RECALL_SAFE_FLOAT -> LocalSafeFloatRequestV1.Recall(input.id(LocalField.TRANSFER_ID),
            input.revision(LocalField.EXPECTED_SOURCE_REVISION), input.revision(LocalField.EXPECTED_REVISION), input.cashEvidence(),
            input.text(LocalField.REASON))
        else -> invalid()
    }
    input.finish()
    access.requireAction(intent.action, safeFloatRequestTarget(value))
    return MutationRequest(intent.operationId, LocalSafeFloatCodec.COMMAND, LocalSafeFloatCodec.encode(value))
}

internal fun safeFloatSummary(request: MutationRequest): List<LocalRecordValue> {
    if (request.command != LocalSafeFloatCodec.COMMAND) unavailable()
    val effect = recordValue(text("Effect", "الأثر"), text(
        "Custody transfer between the safe and a drawer only; not revenue, not a customer payment and never an instruction to repeat a physical hand-over after an uncertain reply.",
        "نقل عهدة بين الخزنة والدرج فقط؛ ليس إيرادًا ولا دفعة عميل ولا أمرًا بتكرار التسليم الفعلي بعد رد غير محسوم."))
    return when (val value = LocalSafeFloatCodec.decode(request.payload)) {
        is LocalSafeFloatRequestV1.Issue -> listOf(
            recordValue(fieldLabel(LocalField.CUSTODY_ID), literal(value.safeId.value)),
            recordValue(fieldLabel(LocalField.SESSION_ID), literal(value.sessionId.value)),
            recordValue(fieldLabel(LocalField.TRANSFER_ID), literal(value.floatId.value)),
            recordValue(fieldLabel(LocalField.AMOUNT), moneyText(value.amount)),
            recordValue(fieldLabel(LocalField.EVIDENCE_ID), literal(value.evidence.evidenceId.value)),
            recordValue(fieldLabel(LocalField.OCCURRED_AT), literal(value.evidence.occurredAt.canonicalString())), effect)
        is LocalSafeFloatRequestV1.Receive -> listOf(
            recordValue(fieldLabel(LocalField.TRANSFER_ID), literal(value.floatId.value)),
            recordValue(text("Counted", "المعدود"), moneyText(value.counted)),
            recordValue(fieldLabel(LocalField.EVIDENCE_ID), literal(value.evidence.evidenceId.value)),
            recordValue(fieldLabel(LocalField.OCCURRED_AT), literal(value.evidence.occurredAt.canonicalString())), effect)
        is LocalSafeFloatRequestV1.Recall -> listOf(
            recordValue(fieldLabel(LocalField.TRANSFER_ID), literal(value.floatId.value)),
            recordValue(fieldLabel(LocalField.EVIDENCE_ID), literal(value.evidence.evidenceId.value)),
            recordValue(fieldLabel(LocalField.OCCURRED_AT), literal(value.evidence.occurredAt.canonicalString())),
            recordValue(fieldLabel(LocalField.REASON), literal(value.reason)), effect)
    }
}

/** The definite kernel decision survives a failed detail decoder; never replace the saved identity. */
internal fun safeFloatRecorded(entry: StoredClientIntentV1, reply: MutationReply.Recorded): LocalOperationResult.Recorded {
    val decision = if (reply.committed) LocalRecordedDecision.APPLIED else LocalRecordedDecision.REJECTED
    var record: LocalRecord? = null
    val message = try {
        val saved = entry.request()
        if (reply.operationId != entry.operationId || !actionMatches(entry.localAction, saved) ||
            entry.localAction !in safeFloatActions) unavailable()
        val request = LocalSafeFloatCodec.decode(saved.payload)
        when (val result = LocalSafeFloatCodec.result(reply.result)) {
            is LocalSafeFloatResultV1.Recorded -> {
                val float = result.float
                if (!reply.committed || float.id != when (request) {
                        is LocalSafeFloatRequestV1.Issue -> request.floatId
                        is LocalSafeFloatRequestV1.Receive -> request.floatId
                        is LocalSafeFloatRequestV1.Recall -> request.floatId
                    }) unavailable()
                record = safeFloatRecord(float)
                when (request) {
                    is LocalSafeFloatRequestV1.Issue -> text("Float issue recorded. It stays your custody exception until the cashier records a counted receipt or you recall it.",
                        "سُجل صرف العهدة. تبقى استثناء عهدة عليك حتى يسجل الكاشير استلامًا معدودًا أو تستردها.")
                    is LocalSafeFloatRequestV1.Receive -> text("Counted float received into your drawer session.",
                        "استُلمت العهدة المعدودة في جلسة درجك.")
                    is LocalSafeFloatRequestV1.Recall -> text("Unacknowledged float recalled to the safe. The original issue stays visible.",
                        "استُردت العهدة غير المؤكدة إلى الخزنة. يبقى الصرف الأصلي ظاهرًا.")
                }
            }
            is LocalSafeFloatResultV1.Refused -> {
                if (reply.committed) unavailable()
                safeFloatRefusal(result.reason)
            }
        }
    } catch (_: Exception) {
        record = null
        text("The master recorded a ${if (reply.committed) "committed" else "rejected"} safe-float operation, but its details cannot be displayed. Keep the original operation ID; do not repeat the physical hand-over.",
            "سجل الجهاز الرئيسي عملية عهدة خزنة ${if (reply.committed) "مثبتة" else "مرفوضة"}، لكن لا يمكن عرض تفاصيلها. احتفظ بمعرف العملية الأصلي؛ لا تكرر التسليم الفعلي.")
    }
    return LocalOperationResult.Recorded(reply.operationId, decision, message, record, reply.replayed)
}

private fun safeFloatRefusal(reason: LocalSafeFloatRefusalV1): LocalText = when (reason) {
    LocalSafeFloatRefusalV1.NOT_AVAILABLE -> text("Refused: the safe, session or float is unavailable.", "رُفض: الخزنة أو الجلسة أو العهدة غير متاحة.")
    LocalSafeFloatRefusalV1.STALE -> text("Refused: the safe, session or float changed. Reload; this rejected operation is retained.", "رُفض: تغيرت الخزنة أو الجلسة أو العهدة. أعد التحميل؛ تظل العملية المرفوضة محفوظة.")
    LocalSafeFloatRefusalV1.DUPLICATE -> text("Refused: that float identity is already used.", "رُفض: معرف العهدة مستخدم بالفعل.")
    LocalSafeFloatRefusalV1.NOT_CUSTODIAN -> text("Refused: only the current custodian may record this step.", "رُفض: يسجل هذه الخطوة أمين العهدة الحالي فقط.")
    LocalSafeFloatRefusalV1.SAME_CUSTODIAN -> text("Refused: a safe custodian cannot issue a float to their own drawer.", "رُفض: لا يصرف أمين الخزنة عهدة إلى درجه.")
    LocalSafeFloatRefusalV1.SESSION_NOT_OPEN -> text("Refused: the receiving drawer session is not open.", "رُفض: جلسة الدرج المستلمة ليست مفتوحة.")
    LocalSafeFloatRefusalV1.INSUFFICIENT_SAFE_CASH -> text("Refused: the safe does not have that much recorded available cash.", "رُفض: لا يتوفر هذا المبلغ في رصيد الخزنة المسجل.")
    LocalSafeFloatRefusalV1.COUNT_MISMATCH -> text("Refused: your count differs from the issued amount. Nothing entered your drawer; resolve with the safe custodian.", "رُفض: عدك يختلف عن المبلغ المصروف. لم يدخل شيء إلى درجك؛ سوِّ الأمر مع أمين الخزنة.")
    LocalSafeFloatRefusalV1.ALREADY_SETTLED -> text("Refused: this float was already received or recalled.", "رُفض: استُلمت هذه العهدة أو استُردت بالفعل.")
    LocalSafeFloatRefusalV1.CURRENCY_MISMATCH -> text("Refused: currency does not match the safe or drawer.", "رُفض: العملة لا تطابق الخزنة أو الدرج.")
    LocalSafeFloatRefusalV1.CAPACITY_REQUIRED -> text("Refused: too many unacknowledged floats. Resolve existing ones first.", "رُفض: عدد العهد غير المؤكدة كبير. سوِّ الحالية أولًا.")
    LocalSafeFloatRefusalV1.NOT_AUTHORIZED -> text("Refused: current permission does not allow this.", "رُفض: الصلاحية الحالية لا تسمح بذلك.")
    LocalSafeFloatRefusalV1.INVALID -> text("Refused: the request is invalid.", "رُفض: الطلب غير صالح.")
}
