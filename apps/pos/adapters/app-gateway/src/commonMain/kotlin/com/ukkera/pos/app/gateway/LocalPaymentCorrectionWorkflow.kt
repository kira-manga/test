package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.sales.ExternalTenderKind
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

internal val paymentCorrectionRecordKinds: Set<LocalRecordKind> = setOf(
    LocalRecordKind.PAYMENT_CORRECTION_SOURCE, LocalRecordKind.PAYMENT_CORRECTION_HISTORY,
)

internal fun isPaymentCorrectionSearch(search: String): Boolean = search.startsWith("payment-correction:")

internal fun paymentCorrectionLink(access: GatewayAccess, saleId: DomainId): LocalRecord? =
    if (access.installed.has(LocalPaymentCorrectionCodec.QUERY)) LocalRecord(
        LocalRecordReference(LocalRecordKind.PAYMENT_CORRECTION_SOURCE, saleId),
        text("Review original payment and linked tender corrections", "مراجعة الدفع الأصلي وتصحيحات وسيلة الدفع المرتبطة"),
        text("Separate protected history — no money movement", "سجل محمي منفصل — دون حركة أموال"),
    ) else null

/** Closed profile input guards; these select capability, never employee authority. */
internal fun requirePaymentCorrectionWorkflow(query: LocalWorkflowQuery) {
    if (query.workflow !in setOf(LocalWorkflow.CHECKOUT, LocalWorkflow.ORDERS) ||
        query.pricePreview != null || query.branchReport != null || query.food != null || query.purchasing != null ||
        query.returns != null || query.cash != null || query.inventory != null || query.openingAllocations != null)
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    val selected = query.reference
    if (selected == null) {
        if (query.cursor != null || query.search.isNotEmpty() && !isPaymentCorrectionSearch(query.search)) invalid()
    } else {
        if (selected.kind !in paymentCorrectionRecordKinds || selected.revision != null || query.search.isNotEmpty() ||
            selected.kind == LocalRecordKind.PAYMENT_CORRECTION_SOURCE && (selected.parentId != null || query.cursor != null) ||
            selected.kind == LocalRecordKind.PAYMENT_CORRECTION_HISTORY && selected.parentId == null) invalid()
    }
}

internal fun requirePaymentCorrectionIntent(intent: LocalCommandIntent) {
    if (intent.action != LocalAction.PAYMENT_CLASSIFICATION_CORRECT || intent.formVersion != 1 ||
        intent.lineField != null || intent.lines.isNotEmpty() || intent.arrivalEvidence != null)
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    intent.reference?.let {
        if (it.kind != LocalRecordKind.PAYMENT_CORRECTION_HISTORY || it.parentId == null || it.revision != null) invalid()
    }
}

internal fun requirePaymentCorrectionRead(request: ReadRequest) {
    if (request.query != LocalPaymentCorrectionCodec.QUERY) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    try {
        val query = LocalPaymentCorrectionCodec.decodeQuery(request.payload)
        if (LocalPaymentCorrectionCodec.request(query).payload != request.payload) invalid()
    } catch (failure: GatewayFailure) { throw failure }
    catch (_: Exception) { invalid() }
}

internal fun requirePaymentCorrectionMutation(action: LocalAction, request: MutationRequest) {
    if (action != LocalAction.PAYMENT_CLASSIFICATION_CORRECT || request.command != LocalPaymentCorrectionCodec.COMMAND ||
        request.actionApprovalId != null) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    if (LocalPaymentCorrectionCodec.normalize(request.payload) != request.payload) invalid()
}

internal suspend fun preparePaymentCorrection(access: GatewayAccess, intent: LocalCommandIntent): MutationRequest {
    if (intent.action != LocalAction.PAYMENT_CLASSIFICATION_CORRECT || intent.formVersion != 1 ||
        intent.lines.isNotEmpty() || intent.lineField != null) invalid()
    val input = InputValues(intent.values)
    val saleId = input.id(LocalField.SALE_ID)
    val paymentId = input.id(LocalField.PAYMENT_ID)
    val sessionRevision = input.revision(LocalField.EXPECTED_SESSION_REVISION)
    val latest = input.optionalId(LocalField.SOURCE_ID)
    val amount = input.money(LocalField.AMOUNT)
    val tenderId = input.optionalId(LocalField.TENDER_ID)
    val policyRevision = input.optionalRevision(LocalField.EXPECTED_REVISION)
    val terminal = input.optionalText(LocalField.PAYMENT_TERMINAL)
    val reference = input.optionalText(LocalField.EXTERNAL_REFERENCE)
    val correctedTender = when (input.choice(LocalField.PAYMENT_METHOD)) {
        "CASH" -> {
            if (tenderId != null || policyRevision != null || terminal != null || reference != null) invalid(LocalField.PAYMENT_METHOD)
            LocalTenderSelectionV1.Cash
        }
        "EXTERNAL" -> LocalTenderSelectionV1.External(tenderId ?: invalid(LocalField.TENDER_ID),
            policyRevision ?: invalid(LocalField.EXPECTED_REVISION))
        else -> invalid(LocalField.PAYMENT_METHOD)
    }
    val request = LocalPaymentCorrectionRequestV1(saleId = saleId, paymentAttemptId = paymentId,
        expectedSessionRevision = sessionRevision, expectedLatestCorrectionId = latest,
        expectedAmount = amount, correctedTender = correctedTender, evidence = input.cashEvidence(),
        reason = input.text(LocalField.REASON), terminal = terminal, reference = reference)
    input.finish()
    intent.reference?.let {
        if (it != LocalRecordReference(LocalRecordKind.PAYMENT_CORRECTION_HISTORY, paymentId, parentId = saleId)) invalid()
    }
    access.requireAction(intent.action, request.saleId)
    return MutationRequest(intent.operationId, LocalPaymentCorrectionCodec.COMMAND, LocalPaymentCorrectionCodec.encode(request))
}

internal fun paymentCorrectionSummary(request: MutationRequest): List<LocalRecordValue> {
    if (request.command != LocalPaymentCorrectionCodec.COMMAND || request.actionApprovalId != null) unavailable()
    val value = LocalPaymentCorrectionCodec.decode(request.payload)
    return listOf(
        recordValue(fieldLabel(LocalField.SALE_ID), literal(value.saleId.value)),
        recordValue(fieldLabel(LocalField.PAYMENT_ID), literal(value.paymentAttemptId.value)),
        recordValue(fieldLabel(LocalField.EXPECTED_SESSION_REVISION), literal(value.expectedSessionRevision.value.toString())),
        recordValue(text("Previous linked correction", "التصحيح المرتبط السابق"),
            value.expectedLatestCorrectionId?.let { literal(it.value) } ?: text("No previous correction at this read", "لا يوجد تصحيح سابق عند هذه القراءة")),
        recordValue(text("Original payment amount — not a new collection", "مبلغ الدفع الأصلي — ليس تحصيلًا جديدًا"), moneyText(value.expectedAmount)),
        recordValue(text("Corrected historical classification", "التصنيف التاريخي المصحح"), when (val tender = value.correctedTender) {
            LocalTenderSelectionV1.Cash -> text("Cash", "نقدي")
            is LocalTenderSelectionV1.External -> literal("EXTERNAL · ${tender.tenderId.value} · ${tender.expectedPolicyRevision.value}")
        }),
        recordValue(fieldLabel(LocalField.EVIDENCE_ID), literal(value.evidence.evidenceId.value)),
        recordValue(fieldLabel(LocalField.OCCURRED_AT), literal(value.evidence.occurredAt.canonicalString())),
        recordValue(fieldLabel(LocalField.REASON), literal(value.reason)),
        recordValue(text("Effect", "الأثر"), correctionEffect()),
    ) + listOfNotNull(
        value.terminal?.let { recordValue(fieldLabel(LocalField.PAYMENT_TERMINAL), literal(it)) },
        value.reference?.let { recordValue(fieldLabel(LocalField.EXTERNAL_REFERENCE), literal(it)) },
    )
}

internal fun savedPaymentCorrectionRequest(entry: StoredClientIntentV1): LocalPaymentCorrectionRequestV1 {
    try {
        entry.validate(entry.owner)
        if (entry.localAction != LocalAction.PAYMENT_CLASSIFICATION_CORRECT || entry.formVersion != 1 ||
            entry.actionApproval != null) throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
        val request = entry.request()
        if (!actionMatches(entry.localAction, request)) throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
        val value = LocalPaymentCorrectionCodec.decode(request.payload)
        entry.recorded?.let { retained ->
            val body = decodeHex(retained.payloadHex, 65_536).useWiped { StoredBytes(it) }
            paymentCorrectionOutcome(entry, value, retained.committed, retained.code, retained.recordedAt, body)
        }
        return value
    } catch (_: Exception) { throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN) }
}

private fun correctionEffect(): LocalText = text(
    "Linked historical classification only. Original payment, receipt and close are unchanged. No new collection, refund, current-drawer cash or stock movement.",
    "تصنيف تاريخي مرتبط فقط. يبقى الدفع والإيصال والإغلاق الأصلي دون تغيير. لا تحصيل أو رد جديد ولا حركة نقدية في الدرج الحالي أو مخزون.",
)

/** The Master authenticates every original join; a missing source is never an empty ledger. */
internal suspend fun paymentCorrectionWorkflow(access: GatewayAccess, query: LocalWorkflowQuery): LocalWorkflowPage {
    if (query.workflow !in setOf(LocalWorkflow.CHECKOUT, LocalWorkflow.ORDERS)) invalid()
    if (access.installed.isPaymentCorrectionsOnly && query.reference == null && query.search.isEmpty())
        return LocalWorkflowPage(query.workflow, emptyList(), emptyList(), text(
            "Enter payment-correction:SALE_UUID to review an original payment and its immutable closed-session history. This connection cannot collect money or create a sale.",
            "أدخل payment-correction:SALE_UUID لمراجعة دفع أصلي وسجل جلسة الإغلاق الثابتة. لا يمكن لهذا الاتصال تحصيل المال أو إنشاء بيع."))
    val selected = query.reference ?: if (isPaymentCorrectionSearch(query.search))
        LocalRecordReference(LocalRecordKind.PAYMENT_CORRECTION_SOURCE,
            searchedId(query.search.removePrefix("payment-correction:"))) else invalid()
    if (selected.kind !in paymentCorrectionRecordKinds || selected.revision != null) invalid()
    if (!access.installed.has(LocalPaymentCorrectionCodec.QUERY)) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    return when (selected.kind) {
        LocalRecordKind.PAYMENT_CORRECTION_SOURCE -> {
            if (selected.parentId != null || query.cursor != null) invalid()
            val reply = access.read(LocalPaymentCorrectionCodec.request(LocalPaymentCorrectionQueryV1.Source(selected.id)))
            val result = LocalPaymentCorrectionCodec.read(reply.payload)
            if (result == LocalPaymentCorrectionReadResultV1.NotAvailable) return correctionUnavailable(query.workflow)
            val source = (result as? LocalPaymentCorrectionReadResultV1.Source)?.source ?: unavailable()
            if (source.saleId != selected.id || source.sessionId != source.originalClose.sessionId ||
                source.collectorId != source.originalClose.submittedBy || source.payments.size !in 1..64 ||
                source.payments.map { it.paymentAttemptId }.distinct().size != source.payments.size) unavailable()
            source.payments.forEach(::validateCorrectionPayment)
            LocalWorkflowPage(query.workflow, listOf(originalCloseRecord(source.originalClose)) + source.payments.map { payment ->
                originalPaymentRecord(source.saleId, payment)
            }, emptyList(), text(
                "Select an original payment to review linked classifications. The submitted close below remains unchanged. ${readCut(reply).english}",
                "اختر دفعًا أصليًا لمراجعة التصنيفات المرتبطة. يظل الإغلاق المقدم أدناه دون تغيير. ${readCut(reply).arabic}"))
        }
        LocalRecordKind.PAYMENT_CORRECTION_HISTORY -> {
            val saleId = selected.parentId ?: invalid()
            val family = "payment-corrections:${saleId.value}:${selected.id.value}"
            val cursor = correctionCursor(query.cursor, family)
            val before = cursor?.before
            val reply = access.read(LocalPaymentCorrectionCodec.request(LocalPaymentCorrectionQueryV1.History(saleId, selected.id, before)))
            val result = LocalPaymentCorrectionCodec.read(reply.payload)
            if (result == LocalPaymentCorrectionReadResultV1.NotAvailable) return correctionUnavailable(query.workflow)
            val page = (result as? LocalPaymentCorrectionReadResultV1.History)?.page ?: unavailable()
            if (page.saleId != saleId || page.payment.paymentAttemptId != selected.id || page.before != before ||
                page.originalClose.sessionId != page.sessionId || page.corrections.size > 5) unavailable()
            validateCorrectionPayment(page.payment)
            val corrections = page.corrections
            corrections.forEach {
                validateCorrectionTender(it.originalTender)
                validateCorrectionTender(it.previousClassification)
                validateCorrectionTenderEvidence(it.correctedTender, it.terminal, it.reference)
            }
            if (corrections.map { it.id }.distinct().size != corrections.size || corrections.any {
                it.saleId != saleId || it.paymentAttemptId != selected.id || it.sessionId != page.sessionId ||
                    it.originalCloseOperationId != page.originalClose.originalCloseOperationId ||
                    it.amount != page.payment.amount || it.originalTender != page.payment.originalTender ||
                    it.originalIntentOperationId != page.payment.originalIntentOperationId ||
                    it.originalRecordingOperationId != page.payment.originalRecordingOperationId ||
                    it.collectorId != page.originalClose.submittedBy ||
                    sameCorrectionClassification(it.previousClassification, it.correctedTender) || it.id == before || it.position.value >= page.payment.totalCorrections
            }) unavailable()
            if (before == null && (corrections.firstOrNull()?.id != page.payment.latestCorrectionId ||
                corrections.firstOrNull()?.position?.value != (page.payment.totalCorrections - 1).takeIf { it >= 0 } ||
                corrections.firstOrNull()?.let { it.correctedTender != page.payment.latestClassification } == true)) unavailable()
            if (cursor != null && corrections.firstOrNull()?.let { first ->
                first.id == cursor.expectedId && first.position == cursor.expectedPosition &&
                    first.correctedTender == cursor.expectedClassification
            } != true) unavailable()
            corrections.zipWithNext().forEach { (newer, older) ->
                if (newer.previousCorrectionId != older.id || newer.position.value != older.position.value + 1 ||
                    newer.previousClassification != older.correctedTender) unavailable()
            }
            val last = corrections.lastOrNull()
            if (last?.previousCorrectionId != null) {
                if (page.nextBefore != last.id || corrections.size != 5) unavailable()
            } else if (page.nextBefore != null || last?.let {
                it.previousClassification != page.payment.originalTender
            } == true) unavailable()
            val forms = if (before == null && access.installed.has(LocalPaymentCorrectionCodec.COMMAND) &&
                access.allow(LocalAction.PAYMENT_CLASSIFICATION_CORRECT, saleId)) listOf(paymentCorrectionForm(page)) else emptyList()
            LocalWorkflowPage(query.workflow,
                listOf(originalCloseRecord(page.originalClose), originalPaymentRecord(saleId, page.payment)) + corrections.map(::correctionRecord),
                forms, text("Linked classifications: ${page.payment.totalCorrections}; newest first. Reload before a new correction. ${correctionEffect().english} ${readCut(reply).english}",
                    "التصنيفات المرتبطة: ${page.payment.totalCorrections}؛ الأحدث أولًا. أعد التحميل قبل تصحيح جديد. ${correctionEffect().arabic} ${readCut(reply).arabic}"),
                page.nextBefore?.let { correctionNextCursor(family, last ?: unavailable()) })
        }
        else -> invalid()
    }
}

/** A local navigation cursor retains the immutable chain cut, not just an echoed row ID. */
private data class CorrectionCursor(val before: DomainId, val expectedId: DomainId,
    val expectedPosition: Revision, val expectedClassification: LocalTenderViewV1)

private fun correctionCursor(value: String?, family: String): CorrectionCursor? {
    if (value == null) return null
    val parts = value.split('|')
    if (parts.size != 9 || parts[0] != family) invalid()
    fun revision(value: String): Revision {
        val number = value.toLongOrNull() ?: invalid()
        if (number < 0 || number.toString() != value) invalid()
        return Revision(number)
    }
    fun flag(value: String): Boolean = when (value) { "0" -> false; "1" -> true; else -> invalid() }
    return try {
        CorrectionCursor(DomainId(parts[1]), DomainId(parts[2]), revision(parts[3]), LocalTenderViewV1(parts[4],
            parts[5].takeUnless { it == "-" }?.let(::DomainId),
            parts[6].takeUnless { it == "-" }?.let(::revision), flag(parts[7]), flag(parts[8])))
    } catch (_: IllegalArgumentException) { invalid() }
}

private fun correctionNextCursor(family: String, last: LocalPaymentCorrectionViewV1): String {
    val previous = last.previousCorrectionId ?: unavailable()
    if (last.position.value == 0L) unavailable()
    val tender = last.previousClassification
    return listOf(family, last.id.value, previous.value, (last.position.value - 1).toString(), tender.kind,
        tender.tenderId?.value ?: "-", tender.policyRevision?.value?.toString() ?: "-",
        if (tender.requiresTerminal) "1" else "0", if (tender.requiresReference) "1" else "0").joinToString("|")
}

private fun sameCorrectionClassification(first: LocalTenderViewV1, second: LocalTenderViewV1): Boolean =
    first.kind == second.kind && first.tenderId == second.tenderId

private fun validateCorrectionTender(tender: LocalTenderViewV1) {
    if (tender.kind == "CASH") {
        if (tender != LocalTenderViewV1("CASH")) unavailable()
    } else if (ExternalTenderKind.entries.none { it.name == tender.kind } ||
        tender.tenderId == null || tender.policyRevision == null) unavailable()
}

private fun validateCorrectionTenderEvidence(tender: LocalTenderViewV1, terminal: String?, reference: String?) {
    validateCorrectionTender(tender)
    if (listOfNotNull(terminal, reference).any { it.isBlank() || it.length > 128 } ||
        tender.kind == "CASH" && (terminal != null || reference != null) ||
        tender.requiresTerminal && terminal == null || tender.requiresReference && reference == null) unavailable()
}

private fun validateCorrectionPayment(payment: LocalPaymentCorrectionPaymentViewV1) {
    validateCorrectionTenderEvidence(payment.originalTender, payment.originalTerminal, payment.originalReference)
    validateCorrectionTender(payment.latestClassification)
    if (payment.amount.minor <= 0 || payment.totalCorrections < 0 ||
        (payment.totalCorrections == 0L) != (payment.latestCorrectionId == null) ||
        payment.totalCorrections == 0L && payment.latestClassification != payment.originalTender) unavailable()
}

private fun paymentCorrectionForm(page: LocalPaymentCorrectionsPageV1): LocalActionForm = form(
    LocalAction.PAYMENT_CLASSIFICATION_CORRECT,
    listOf(idField(LocalField.SALE_ID, page.saleId), idField(LocalField.PAYMENT_ID, page.payment.paymentAttemptId),
        revisionField(LocalField.EXPECTED_SESSION_REVISION, page.sessionRevision)) +
        listOfNotNull(page.payment.latestCorrectionId?.let { idField(LocalField.SOURCE_ID, it).copy(
            label = text("Previous linked correction", "التصحيح المرتبط السابق")) }) +
        listOf(moneyField(LocalField.AMOUNT, page.payment.amount).copy(readOnly = true),
            choiceField(LocalField.PAYMENT_METHOD, listOf(LocalChoice("CASH", text("Cash", "نقدي")),
                LocalChoice("EXTERNAL", text("Configured external tender", "وسيلة دفع خارجية مُعدّة")))),
            scalar(LocalField.TENDER_ID, LocalInputKind.IDENTIFIER, required = false),
            scalar(LocalField.EXPECTED_REVISION, LocalInputKind.INTEGER, required = false).copy(
                label = text("External tender policy revision", "مراجعة سياسة وسيلة الدفع الخارجية")),
            scalar(LocalField.PAYMENT_TERMINAL, LocalInputKind.TEXT, required = false, maximum = 128),
            scalar(LocalField.EXTERNAL_REFERENCE, LocalInputKind.TEXT, required = false, maximum = 128),
            newIdField(LocalField.EVIDENCE_ID), instantField(LocalField.OCCURRED_AT),
            scalar(LocalField.REASON, LocalInputKind.MULTILINE, maximum = 256)),
    text("Confirm the actual historical method, not a new charge. External classification requires the exact configured tender/policy and any required terminal/reference evidence; leave all external fields empty for Cash. ${correctionEffect().english}",
        "أكد الوسيلة التاريخية الفعلية، وليس تحصيلًا جديدًا. يتطلب التصنيف الخارجي وسيلة الدفع وسياساتها المُعدّة بدقة وأي إثبات جهاز أو مرجع مطلوب؛ اترك جميع الحقول الخارجية فارغة للنقدي. ${correctionEffect().arabic}"),
)

private fun originalPaymentRecord(saleId: DomainId, value: LocalPaymentCorrectionPaymentViewV1): LocalRecord = LocalRecord(
    LocalRecordReference(LocalRecordKind.PAYMENT_CORRECTION_HISTORY, value.paymentAttemptId, parentId = saleId),
    text("Original paid payment", "دفع أصلي مدفوع"), text("Immutable attestation; classification is separate", "إفادة ثابتة؛ التصنيف منفصل"), listOf(
        recordValue(fieldLabel(LocalField.AMOUNT), moneyText(value.amount)),
        recordValue(text("Original reported tender", "وسيلة الدفع المبلغ عنها أصلًا"), tenderText(value.originalTender)),
        recordValue(text("Current historical classification", "التصنيف التاريخي الحالي"), tenderText(value.latestClassification)),
        recordValue(text("Original intent operation", "عملية الطلب الأصلية"), literal(value.originalIntentOperationId.value)),
        recordValue(text("Original recording operation", "عملية التسجيل الأصلية"), literal(value.originalRecordingOperationId.value)),
        recordValue(text("Original attesting employee", "الموظف صاحب الإفادة الأصلية"), literal(value.originalAttestation.actorId.value)),
        recordValue(text("Original attestation timestamp", "وقت الإفادة الأصلية"), literal(value.originalAttestation.occurredAt.canonicalString())),
    ) + listOfNotNull(
        value.originalTerminal?.let { recordValue(fieldLabel(LocalField.PAYMENT_TERMINAL), literal(it)) },
        value.originalReference?.let { recordValue(fieldLabel(LocalField.EXTERNAL_REFERENCE), literal(it)) },
        value.payerReference?.let { recordValue(text("Original payer reference", "مرجع الدافع الأصلي"), literal(it)) },
    ),
)

private fun originalCloseRecord(close: LocalPaymentCorrectionCloseViewV1): LocalRecord = LocalRecord(
    LocalRecordReference(LocalRecordKind.CASH_SESSION, close.sessionId),
    text("Original submitted close — immutable", "الإغلاق المقدم الأصلي — ثابت"), values = listOf(
        recordValue(text("Original close operation", "عملية الإغلاق الأصلية"), literal(close.originalCloseOperationId.value)),
        recordValue(fieldLabel(LocalField.SESSION_ID), literal(close.sessionId.value)),
        recordValue(text("Original ledger cut", "قطع السجل الأصلي"), literal(close.ledgerRevision.value.toString())),
        recordValue(text("Original expected cash", "النقدية المتوقعة أصلًا"), moneyText(close.expectedCash)),
        recordValue(text("Original actual count", "العد الفعلي الأصلي"), moneyText(close.counted)),
        recordValue(text("Original variance", "الفرق الأصلي"), moneyText(close.variance)),
        recordValue(text("Original external collections", "التحصيلات الخارجية الأصلية"), moneyText(close.externalCollections)),
        recordValue(text("Original external refunds", "الردود الخارجية الأصلية"), moneyText(close.externalRefunds)),
        recordValue(text("Original retained float", "العهدة المحتفظ بها أصلًا"), moneyText(close.retainedFloat)),
        recordValue(text("Original actual safe delivery", "التسليم الفعلي الأصلي للخزنة"), moneyText(close.actualSafeDelivery)),
        recordValue(text("Original submitted by", "صاحب تقديم الإغلاق الأصلي"), literal(close.submittedBy.value)),
        recordValue(text("Original counted by", "صاحب العد الأصلي"), literal(close.countEvidence.actorId.value)),
        recordValue(text("Original counted at", "وقت العد الأصلي"), literal(close.countEvidence.occurredAt.canonicalString())),
    ), selectable = false,
)

private fun correctionRecord(value: LocalPaymentCorrectionViewV1): LocalRecord = LocalRecord(
    LocalRecordReference(LocalRecordKind.PAYMENT_CORRECTION, value.id, value.position, value.paymentAttemptId),
    text("Linked historical tender correction", "تصحيح تاريخي مرتبط لوسيلة الدفع"), correctionEffect(), listOf(
        recordValue(fieldLabel(LocalField.SALE_ID), literal(value.saleId.value)),
        recordValue(fieldLabel(LocalField.PAYMENT_ID), literal(value.paymentAttemptId.value)),
        recordValue(fieldLabel(LocalField.SESSION_ID), literal(value.sessionId.value)),
        recordValue(text("Original collector", "المحصل الأصلي"), literal(value.collectorId.value)),
        recordValue(text("Previous classification", "التصنيف السابق"), tenderText(value.previousClassification)),
        recordValue(text("Historical classification delta", "فرق التصنيف التاريخي"), moneyText(value.previousClassificationDelta)),
        recordValue(text("Corrected classification", "التصنيف المصحح"), tenderText(value.correctedTender)),
        recordValue(text("Linked classification delta", "فرق التصنيف المرتبط"), moneyText(value.correctedClassificationDelta)),
        recordValue(text("Physical cash delta", "فرق النقدية الفعلي"), moneyText(value.physicalCashDelta)),
        recordValue(text("Author", "صاحب التصحيح"), literal(value.evidence.actorId.value)),
        recordValue(fieldLabel(LocalField.OCCURRED_AT), literal(value.evidence.occurredAt.canonicalString())),
        recordValue(text("Master recorded at", "وقت التسجيل بالجهاز الرئيسي"), literal(value.recordedAt.canonicalString())),
        recordValue(fieldLabel(LocalField.REASON), literal(value.reason)),
        recordValue(text("Original correction operation", "عملية التصحيح الأصلية"), literal(value.operationId.value)),
        recordValue(text("Original close operation", "عملية الإغلاق الأصلية"), literal(value.originalCloseOperationId.value)),
    ), selectable = false,
)

private fun tenderText(value: LocalTenderViewV1): LocalText = literal(value.kind +
    (value.tenderId?.let { " · ${it.value}" } ?: "") + (value.policyRevision?.let { " · ${it.value}" } ?: ""))

private fun correctionUnavailable(workflow: LocalWorkflow): LocalWorkflowPage = LocalWorkflowPage(workflow,
    emptyList(), emptyList(), text("Original payment or linked correction history is unavailable to this actor. No payment absence or empty history was inferred.",
        "الدفع الأصلي أو سجل التصحيح المرتبط غير متاح لهذا المستخدم. لم يُستنتج غياب دفع أو سجل فارغ."))

/** Matching receipt identity is not enough: verify the exact typed documentary outcome. */
internal fun validatePaymentCorrectionRecorded(entry: StoredClientIntentV1,
    reply: MutationReply.Recorded): LocalPaymentCorrectionResultV1 {
    val original = savedPaymentCorrectionRequest(entry)
    if (reply.operationId != entry.operationId || reply.originalActorId != entry.owner.actorId || reply.physicalStart != null)
        throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
    return paymentCorrectionOutcome(entry, original, reply.committed, reply.code, reply.recordedAt, reply.result)
}

private fun paymentCorrectionOutcome(entry: StoredClientIntentV1, original: LocalPaymentCorrectionRequestV1,
    committed: Boolean, code: String, recordedAt: UtcInstant, payload: StoredBytes): LocalPaymentCorrectionResultV1 {
    val result = try { LocalPaymentCorrectionCodec.result(payload) }
    catch (_: Exception) { throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN) }
    val valid = when (result) {
        is LocalPaymentCorrectionResultV1.Recorded -> {
            val correction = result.correction
            try {
                validateCorrectionTender(correction.originalTender)
                validateCorrectionTender(correction.previousClassification)
                validateCorrectionTenderEvidence(correction.correctedTender, correction.terminal, correction.reference)
            } catch (_: GatewayFailure) {
                // A contradictory returned outcome cannot qualify the retained original.
                // Unlike an unavailable history read, it must preserve result uncertainty.
                throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
            }
            val tenderMatches = when (val tender = original.correctedTender) {
                LocalTenderSelectionV1.Cash -> correction.correctedTender == LocalTenderViewV1("CASH")
                is LocalTenderSelectionV1.External -> correction.correctedTender.kind != "CASH" &&
                    correction.correctedTender.tenderId == tender.tenderId &&
                    correction.correctedTender.policyRevision == tender.expectedPolicyRevision &&
                    (!correction.correctedTender.requiresTerminal || original.terminal != null) &&
                    (!correction.correctedTender.requiresReference || original.reference != null)
            }
            committed && code == "committed" && result.sessionRevision == original.expectedSessionRevision &&
                correction.saleId == original.saleId && correction.paymentAttemptId == original.paymentAttemptId &&
                correction.operationId == entry.operationId && correction.recordedAt == recordedAt && correction.previousCorrectionId == original.expectedLatestCorrectionId &&
                correction.amount == original.expectedAmount && correction.evidence.evidenceId == original.evidence.evidenceId &&
                correction.evidence.occurredAt == original.evidence.occurredAt && correction.evidence.actorId == entry.owner.actorId &&
                correction.reason == original.reason && correction.terminal == original.terminal && correction.reference == original.reference &&
                !sameCorrectionClassification(correction.previousClassification, correction.correctedTender) && tenderMatches
        }
        is LocalPaymentCorrectionResultV1.Refused -> !committed && code == "sales.payment_correction.refused"
    }
    if (!valid) throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
    return result
}

internal fun paymentCorrectionRecorded(entry: StoredClientIntentV1,
    reply: MutationReply.Recorded): LocalOperationResult.Recorded {
    val result = validatePaymentCorrectionRecorded(entry, reply)
    return when (result) {
        is LocalPaymentCorrectionResultV1.Recorded -> LocalOperationResult.Recorded(reply.operationId,
            LocalRecordedDecision.APPLIED, correctionEffect(), correctionRecord(result.correction).copy(
                reference = LocalRecordReference(LocalRecordKind.PAYMENT_CORRECTION_HISTORY,
                    result.correction.paymentAttemptId, parentId = result.correction.saleId), selectable = true), reply.replayed)
        is LocalPaymentCorrectionResultV1.Refused -> LocalOperationResult.Recorded(reply.operationId,
            LocalRecordedDecision.REJECTED, text("Historical correction refused: ${result.reason.name}. No payment, close, cash or stock was changed. Keep this original operation for recovery; reload before any new intent.",
                "رُفض التصحيح التاريخي: ${result.reason.name}. لم يتغير دفع أو إغلاق أو نقدية أو مخزون. احتفظ بهذه العملية الأصلية للاستعادة؛ أعد التحميل قبل طلب جديد."),
            replayed = reply.replayed)
    }
}
