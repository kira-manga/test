package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId

internal fun cashSelection(query: LocalWorkflowQuery): LocalRecordReference? {
    val section = query.cash?.section ?: invalid()
    val kind = when (section) {
        LocalCashSection.OWN_SESSION, LocalCashSection.CLOSED_REVIEW -> LocalRecordKind.CASH_SESSION
        LocalCashSection.TRANSFER -> LocalRecordKind.TRANSFER
        LocalCashSection.SAFE_BALANCE -> LocalRecordKind.SAFE
        LocalCashSection.MY_CASH -> null
    }
    query.reference?.let { if (kind != null && it.kind != kind) invalid(); return it }
    if (kind != null) return query.search.takeIf { it.isNotBlank() }?.let { LocalRecordReference(kind, searchedId(it)) }
    // Retain earlier exact lookup syntax; the normal UI now names the selections.
    return when {
        query.search.startsWith("session:") -> LocalRecordReference(LocalRecordKind.CASH_SESSION, searchedId(query.search.removePrefix("session:")))
        query.search.startsWith("transfer:") -> LocalRecordReference(LocalRecordKind.TRANSFER, searchedId(query.search.removePrefix("transfer:")))
        query.search.startsWith("safe:") -> LocalRecordReference(LocalRecordKind.SAFE, searchedId(query.search.removePrefix("safe:")))
        query.search.startsWith("destination:") -> LocalRecordReference(LocalRecordKind.SAFE, searchedId(query.search.removePrefix("destination:")))
        else -> null
    }
}

internal fun cashSessionUnavailable(): LocalWorkflowPage = LocalWorkflowPage(LocalWorkflow.CASH, emptyList(), emptyList(), text(
    "No session in this projection is available for that ID. No absent history, zero balance or completed review was inferred.",
    "لا توجد جلسة متاحة في هذا العرض لهذا المعرف. لم يُستنتج غياب سجل أو رصيد صفري أو مراجعة مكتملة."))

internal suspend fun cashClosedWorkflow(access: GatewayAccess, id: DomainId, review: Boolean, cursor: String? = null): LocalWorkflowPage {
    val permission = if (review) SecurityAction.CASH_REVIEW else SecurityAction.CASH_READ
    if (!access.hasHint(Permission(permission, PermissionVerb.PERFORM), id)) throw GatewayFailure(LocalServiceProblem.DENIED)
    val reply = access.read(LocalCashCloseReadCodec.request(if (review) LocalCashCloseQueryV1.Review(id) else LocalCashCloseQueryV1.Own(id)))
    val result = LocalCashCloseReadCodec.decode(reply.payload)
    if (result == LocalCashCloseReadResultV1.NotAvailable) return cashSessionUnavailable()
    val close = result as? LocalCashCloseReadResultV1.Close ?: unavailable()
    val session = close.session
    val expected = session.closingExpected ?: unavailable()
    val delivery = session.actualSafeDelivery ?: unavailable()
    if (session.id != id || session.status !in setOf("SUBMITTED_CLOSED", "REVIEWED") ||
        (session.custodianId == access.view.actorId) == review || session.counted == null || session.variance == null ||
        session.retainedFloat == null || session.closingTransferId != close.transfer?.id ||
        close.countEvidence.actorId != session.custodianId || close.submittedBy != session.custodianId ||
        (session.status == "REVIEWED") != (close.review != null)) unavailable()
    val base = cashSessionRecord(session)
    val records = mutableListOf(base.copy(title = text("Original cash closing report", "تقرير الإغلاق النقدي الأصلي"),
        selectable = false, values = base.values + buildList {
            add(recordValue(fieldLabel(LocalField.CUSTODIAN_ID), literal(session.custodianId.value)))
            add(recordValue(text("Original closing ledger cut", "حد سجل الإغلاق الأصلي"), literal(close.closingLedgerRevision.value.toString())))
            add(recordValue(text("Expected cash at the original cut", "النقدية المتوقعة عند الحد الأصلي"), moneyText(expected)))
            add(recordValue(text("Original count evidence — ID · actor · time", "إثبات العد الأصلي — المعرف · المستخدم · الوقت"), cashEvidenceText(close.countEvidence)))
            add(recordValue(text("Submitted by", "مقدّم الإغلاق"), literal(close.submittedBy.value)))
            add(recordValue(text("Original close operation", "عملية الإغلاق الأصلية"), literal(close.operationId.value)))
            close.varianceReason?.let { add(recordValue(fieldLabel(LocalField.VARIANCE_REASON), literal(it))) }
            close.review?.let {
                add(recordValue(text("Reviewer", "المراجع"), literal(it.reviewerId.value)))
                add(recordValue(text("Review evidence — ID · actor · time", "إثبات المراجعة — المعرف · المستخدم · الوقت"), cashEvidenceText(it.evidence)))
                add(recordValue(text("Review explanation", "تفسير المراجعة"), literal(it.reason)))
            }
        }))
    close.transfer?.let { transfer ->
        records += LocalRecord(LocalRecordReference(LocalRecordKind.TRANSFER, transfer.id, transfer.revision),
            text("Original closing safe delivery", "تسليم الخزنة الأصلي عند الإغلاق"), literal(transfer.status), buildList {
                add(recordValue(fieldLabel(LocalField.AMOUNT), moneyText(delivery)))
                add(recordValue(fieldLabel(LocalField.RECEIVING_CUSTODY_ID), literal(transfer.receivingCustodyId.value)))
                add(recordValue(text("Actual delivery evidence — ID · actor · time", "إثبات التسليم الفعلي — المعرف · المستخدم · الوقت"), cashEvidenceText(transfer.delivery)))
                transfer.receipt?.let { add(recordValue(text("Actual receipt evidence — ID · actor · time", "إثبات الاستلام الفعلي — المعرف · المستخدم · الوقت"), cashEvidenceText(it))) }
            }, selectable = !review) // A review grant is not a sender/receiver grant.
    }
    val forms = if (review && session.status == "SUBMITTED_CLOSED" && access.installed.has(LocalSalesStoreProfile.CASH_COMMAND) &&
        access.allow(LocalAction.CASH_REVIEW_CLOSE, id)) listOf(form(LocalAction.CASH_REVIEW_CLOSE,
        listOf(idField(LocalField.SESSION_ID, id), revisionField(LocalField.EXPECTED_REVISION, session.revision),
            newIdField(LocalField.EVIDENCE_ID), instantField(LocalField.OCCURRED_AT), scalar(LocalField.REASON, LocalInputKind.MULTILINE, maximum = 256)), text(
            "Append a review of this original immutable close. This does not change the count, variance, next shift, cash balance, debt or safe receipt. The original counter cannot review their own close.",
            "أضف مراجعة لهذا الإغلاق الأصلي الثابت. لا تغير المراجعة العد أو الفرق أو الوردية التالية أو الرصيد النقدي أو الدين أو استلام الخزنة. لا يستطيع صاحب العد الأصلي مراجعة إغلاقه بنفسه."))) else emptyList()
    val notes = cashCloseNotes(access, close, review, cursor)
    return LocalWorkflowPage(LocalWorkflow.CASH, records + notes.records, forms + notes.forms, text(
        "The original close remains separate from later review, explanations and safe receipt. No next-shift balance is disclosed. ${notes.notice.english} ${readCut(reply).english}",
        "يظل الإغلاق الأصلي منفصلًا عن المراجعة والتفسيرات اللاحقة واستلام الخزنة. لا يُعرض رصيد الوردية التالية. ${notes.notice.arabic} ${readCut(reply).arabic}"), notes.nextCursor)
}

private fun cashEvidenceText(evidence: LocalCashActorEvidenceV1): LocalText = literal(
    "${evidence.evidenceId.value} · ${evidence.actorId.value} · ${evidence.occurredAt.canonicalString()}")
