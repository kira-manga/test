package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

internal fun returnCaseSummaryRecord(value: LocalReturnCaseSummaryV1): LocalRecord = LocalRecord(
    LocalRecordReference(LocalRecordKind.RETURN_CASE, value.caseId), text("Retained remedy case", "مطالبة معالجة محفوظة"),
    returnCaseStage(value.stage), buildList {
        add(recordValue(fieldLabel(LocalField.EXPECTED_CASE_REVISION), literal(value.revision.value.toString())))
        add(recordValue(text("Recorded at master", "وقت التسجيل بالجهاز الرئيسي"), literal(value.recordedAt.canonicalString())))
        value.saleId?.let { add(recordValue(fieldLabel(LocalField.SALE_ID), literal(it.value))) }
        value.portionId?.let { add(recordValue(fieldLabel(LocalField.PORTION_ID), literal(it.value))) }
        value.ordinaryAssessment?.let { add(recordValue(text("Requested ordinary remedy assessment", "تقييم المعالجة العادية المطلوبة"), literal(it.name))) }
        value.route?.let { add(recordValue(text("Required review route", "مسار المراجعة المطلوب"), literal(it))) }
        value.remedyDueAt?.let { add(recordValue(text("Verified remedy due at", "موعد المعالجة المتحقق منه"), literal(it.canonicalString()))) }
        value.acceptedReturnId?.let { add(recordValue(fieldLabel(LocalField.RETURN_ID), literal(it.value))) }
    })

internal fun returnCaseRecord(value: LocalReturnCaseViewV1): LocalRecord {
    val summary = returnCaseSummaryRecord(value.summary)
    return summary.copy(selectable = false, values = summary.values + listOf(
        recordValue(text("Original intake operation", "عملية تسجيل المطالبة الأصلية"), literal(value.originalOperationId.value)),
        recordValue(fieldLabel(LocalField.CONDITION), literal(value.facts.condition)),
        recordValue(fieldLabel(LocalField.REQUESTED_REMEDY), returnRemedyLabel(value.facts.requestedRemedy)),
        recordValue(fieldLabel(LocalField.CLAIMED_REASON), value.facts.claimedReason?.let(::returnClaimReasonLabel)
            ?: text("Unknown / not stated", "غير معلوم أو غير مذكور")),
        recordValue(fieldLabel(LocalField.CLAIM_TIME_ZONE), literal(value.facts.claimTimeZoneId))) +
        returnFactsValues(value.facts.asserted) + listOfNotNull(value.binding?.let { returnBindingValue(it) }))
}

internal fun returnDecisionRecord(value: LocalReturnCaseDecisionViewV1): LocalRecord = LocalRecord(
    LocalRecordReference(LocalRecordKind.RETURN_CASE_EVENT, DomainId(value.operationId.value)),
    text("Original ordinary-remedy assessment — not all legal rights", "تقييم المعالجة العادية الأصلي — ليس جميع الحقوق القانونية"),
    literal(value.outcome.name), returnDecisionValues(value), selectable = false)

internal fun returnCaseEventRecord(value: LocalReturnCaseEventViewV1): LocalRecord = LocalRecord(
    LocalRecordReference(LocalRecordKind.RETURN_CASE_EVENT, DomainId(value.operationId.value)),
    text("Case event ${value.ordinal + 1}", "حدث المطالبة ${value.ordinal + 1}"), literal(value.action.name), listOf(
        recordValue(text("Original event operation", "عملية الحدث الأصلية"), literal(value.operationId.value)),
        recordValue(text("Recorded at master", "وقت التسجيل بالجهاز الرئيسي"), literal(value.recordedAt.canonicalString()))) +
        listOfNotNull(value.binding?.let(::returnBindingValue)) + value.decision?.let(::returnDecisionValues).orEmpty(), selectable = false)

private fun returnDecisionValues(value: LocalReturnCaseDecisionViewV1): List<LocalRecordValue> = buildList {
    add(recordValue(fieldLabel(LocalField.DECISION_OPERATION_ID), literal(value.operationId.value)))
    add(recordValue(text("Reason / review route", "السبب / مسار المراجعة"), literal("${value.reasonCode} / ${value.route}")))
    value.policyId?.let { add(recordValue(fieldLabel(LocalField.POLICY_ID), literal(it.value))) }
    value.policyRevision?.let { add(recordValue(text("Original policy revision", "مراجعة السياسة الأصلية"), literal(it.value.toString()))) }
    value.salePromiseCaptureId?.let { add(recordValue(text("Original sale-promise capture", "سجل وعد البيع الأصلي"), literal(it.value))) }
    add(recordValue(text("Applicable-rights sources", "مصادر الحقوق السارية"), returnIdsText(value.applicableRightsIds)))
    add(recordValue(text("Verified customer-receipt sources", "مصادر استلام العميل المتحقق منها"), returnIdsText(value.customerReceiptIds)))
    add(recordValue(text("Verified actual customer receipt", "استلام العميل الفعلي المتحقق منه"), optionalReturnText(value.customerReceivedAt?.canonicalString())))
    add(recordValue(text("Verified receipt time zone", "المنطقة الزمنية للاستلام المتحقق منه"), optionalReturnText(value.receiptTimeZoneId)))
    add(recordValue(text("Verified remedy deadline", "موعد المعالجة المتحقق منه"), optionalReturnText(value.remedyDueAt?.canonicalString())))
    value.ordinaryInspection?.let { addAll(returnOrdinaryInspectionValues(it)) }
    value.ordinaryTiming?.let { timing ->
        add(recordValue(text("Ordinary receipt-window status — not all rights", "حالة فترة الاستلام العادية — ليست جميع الحقوق"), literal(timing.status.name)))
        add(recordValue(text("Original captured category", "الفئة الأصلية المحفوظة"), literal(timing.categoryCode)))
        add(recordValue(text("Protected periods (elapsed 24-hour days)", "الفترات المحمية (أيام منقضية من ٢٤ ساعة)"), text(
            "Captured commercial: ${timing.originalCommercialDays}\nApplicable ordinary: ${timing.applicableReceiptWindowDays}\nMore-favorable effective: ${timing.effectiveDays}",
            "التجارية المحفوظة: ${timing.originalCommercialDays}\nالعادية السارية: ${timing.applicableReceiptWindowDays}\nالفعلية الأكثر نفعًا: ${timing.effectiveDays}")))
        add(recordValue(text("Original full-portion receipt evidence", "إثبات استلام الجزء الأصلي كاملًا"), text(
            "Event: ${timing.receiptEventId.value}\nReceived (UTC): ${timing.receivedAt.canonicalString()}\nTime zone: ${timing.receiptTimeZoneId}",
            "الحدث: ${timing.receiptEventId.value}\nالاستلام (UTC): ${timing.receivedAt.canonicalString()}\nالمنطقة الزمنية: ${timing.receiptTimeZoneId}")))
        add(recordValue(text("Immutable Master-recorded submission", "تقديم المطالبة الثابت المسجل بالجهاز الرئيسي"), text(
            "Operation: ${timing.submissionOperationId.value}\nSubmitted (UTC): ${timing.submittedAt.canonicalString()}",
            "العملية: ${timing.submissionOperationId.value}\nالتقديم (UTC): ${timing.submittedAt.canonicalString()}")))
        add(recordValue(text("Ordinary eligibility expiry (inclusive) — not a resolution deadline", "انتهاء الأهلية العادية (يشمل اللحظة نفسها) — ليس موعد معالجة"),
            timing.expiresAt?.let { literal(it.canonicalString()) } ?: text("Unavailable — no expiry inferred", "غير متاح — لا يُستنتج انتهاء")))
        add(recordValue(text("Ordinary qualification limits", "حدود التأهيل العادي"), text(
            "Only the ordinary change-of-mind refund assessment. Other or continuing rights remain separate. Qualification does not consume entitlement, accept a return, restore stock or pay money. Later processing cannot invalidate a timely original submission.",
            "تقييم طلب الرد العادي لتغيير الرأي فقط. تبقى الحقوق الأخرى أو المستمرة منفصلة. لا يستهلك التأهيل الاستحقاق أو يقبل مرتجعًا أو يعيد المخزون أو يدفع المال. لا تُبطل المعالجة اللاحقة تقديم المطالبة الأصلي في موعده.")))
    }
    value.originalCommercialTiming?.let { timing ->
        add(recordValue(text("Original commercial elapsed timing — documentary only", "التوقيت التجاري الأصلي المنقضي — توثيق فقط"),
            literal(timing.status.name)))
        add(recordValue(text("Captured original commercial period (elapsed 24-hour days)", "الفترة التجارية الأصلية المحفوظة (أيام منقضية من ٢٤ ساعة)"),
            literal(timing.days.toString())))
        add(recordValue(text("Original customer-receipt event", "حدث استلام العميل الأصلي"), literal(timing.receiptEventId.value)))
        add(recordValue(text("Original customer receipt (UTC)", "وقت استلام العميل الأصلي (UTC)"), literal(timing.receivedAt.canonicalString())))
        add(recordValue(text("Original receipt time zone", "المنطقة الزمنية للاستلام الأصلي"), literal(timing.receiptTimeZoneId)))
        add(recordValue(text("Immutable submission operation", "عملية تقديم المطالبة الثابتة"), literal(timing.submissionOperationId.value)))
        add(recordValue(text("Original Master-recorded submission (UTC)", "وقت تقديم المطالبة الأصلي المسجل بالجهاز الرئيسي (UTC)"),
            literal(timing.submittedAt.canonicalString())))
        add(recordValue(text("Original commercial expiry (inclusive)", "انتهاء الفترة التجارية الأصلية (يشمل اللحظة نفسها)"),
            timing.expiresAt?.let { literal(it.canonicalString()) } ?: text("Unavailable — no deadline inferred", "غير متاح — لا يُستنتج موعد")))
        add(recordValue(text("Timing observation limits", "حدود ملاحظة التوقيت"), text(
            "Not legal eligibility, a remedy deadline or independent proof of clock accuracy. It does not accept a return or authorize refund or stock effects.",
            "ليست أهلية قانونية أو موعد معالجة أو إثباتًا مستقلًا لدقة الساعة. لا تقبل مرتجعًا ولا تمنح صلاحية رد المال أو تغيير المخزون.")))
    }
}

private fun returnFactsValues(value: LocalReturnClaimFactsV1): List<LocalRecordValue> = listOf(
    recordValue(text("Originally asserted sale", "البيع المذكور في الإفادة الأصلية"), optionalReturnText(value.saleId?.value)),
    recordValue(text("Originally asserted portion", "الجزء المذكور في الإفادة الأصلية"), optionalReturnText(value.portionId?.value)),
    recordValue(text("Originally asserted quantity", "الكمية المذكورة في الإفادة الأصلية"), value.quantity?.let(::quantityText) ?: optionalReturnText(null)),
    recordValue(fieldLabel(LocalField.ASSERTED_RECEIVED_AT), optionalReturnText(value.assertedReceivedAt?.canonicalString())),
    recordValue(fieldLabel(LocalField.CLAIMED_AT), literal(value.claimedAt.canonicalString())),
    recordValue(fieldLabel(LocalField.REASON), literal(value.reason)),
    recordValue(fieldLabel(LocalField.EVIDENCE_ID), literal(value.evidence.evidenceId.value)),
    recordValue(fieldLabel(LocalField.OCCURRED_AT), literal(value.evidence.occurredAt.canonicalString())))

private fun returnBindingValue(value: LocalReturnCaseReceiptBindingV1): LocalRecordValue = recordValue(
    text("Appended receipt evidence — not delivery proof", "إثبات الإيصال المضاف — ليس إثبات تسليم"), text(
        "Sale ${value.saleId.value}\nPortion ${value.portionId.value}\nQuantity ${quantityText(value.quantity).english}\nEvidence ${value.evidence.evidenceId.value}\nOccurred ${value.evidence.occurredAt.canonicalString()}",
        "البيع ${value.saleId.value}\nالجزء ${value.portionId.value}\nالكمية ${quantityText(value.quantity).arabic}\nالإثبات ${value.evidence.evidenceId.value}\nوقت الحدث ${value.evidence.occurredAt.canonicalString()}"))

private fun returnIdsText(ids: List<DomainId>): LocalText = if (ids.isEmpty()) optionalReturnText(null) else literal(ids.joinToString("\n") { it.value })
private fun optionalReturnText(value: String?): LocalText = value?.let(::literal) ?: text("Not supplied / not established", "غير مقدم أو غير متحقق منه")
private fun returnCaseStage(value: LocalReturnCaseStageV1): LocalText = when (value) {
    LocalReturnCaseStageV1.PENDING -> text("Claim pending — not erased or paid", "مطالبة معلقة — لم تُمحَ ولم تُدفع")
    LocalReturnCaseStageV1.BOUND_UNACCEPTED -> text("Qualified refund claim — not yet accepted", "مطالبة رد مؤهلة — لم تُقبل بعد")
    LocalReturnCaseStageV1.ACCEPTED_DISPOSITION_PENDING -> text("Accepted — stock disposition remains separate", "مقبولة — التصرف بالمخزون منفصل")
}

/** The complete normalized request, including unknowns, not just the editable field subset. */
internal fun returnCaseIntentSummary(request: MutationRequest): List<LocalRecordValue> = buildList {
    val value = LocalReturnCaseCommandCodecV1.decode(request.payload)
    add(recordValue(fieldLabel(LocalField.CASE_ID), literal(returnCaseId(value).value)))
    when (value) {
        is LocalReturnCaseRequestV1.Record -> {
            addAll(returnFactsValues(value.facts.asserted))
            add(recordValue(fieldLabel(LocalField.CONDITION), literal(value.facts.condition)))
            add(recordValue(fieldLabel(LocalField.REQUESTED_REMEDY), returnRemedyLabel(value.facts.requestedRemedy)))
            add(recordValue(fieldLabel(LocalField.CLAIMED_REASON), value.facts.claimedReason?.let(::returnClaimReasonLabel)
                ?: text("Unknown / not stated", "غير معلوم أو غير مذكور")))
            add(recordValue(fieldLabel(LocalField.CLAIM_TIME_ZONE), literal(value.facts.claimTimeZoneId)))
        }
        is LocalReturnCaseRequestV1.AppendReceiptEvidence -> {
            add(returnRevisionValue(LocalField.EXPECTED_CASE_REVISION, value.expectedCaseRevision))
            add(returnBindingValue(value.binding))
        }
        is LocalReturnCaseRequestV1.Qualify -> {
            add(returnRevisionValue(LocalField.EXPECTED_CASE_REVISION, value.expectedCaseRevision))
            value.expectedEntitlementRevision?.let { add(returnRevisionValue(LocalField.EXPECTED_ENTITLEMENT_REVISION, it)) }
                ?: add(recordValue(fieldLabel(LocalField.EXPECTED_ENTITLEMENT_REVISION), text(
                    "Not observed — request pending assessment only; the master must verify the original.",
                    "لم تتم ملاحظته — طلب تقييم معلق فقط؛ يجب أن يتحقق الجهاز الرئيسي من الأصل.")))
            value.ordinaryInspection?.let { inspection ->
                addAll(returnOrdinaryInspectionValues(inspection))
                add(recordValue(text("Inspection and qualification limits", "حدود الفحص والتأهيل"), text(
                    "Employee attestation only. The Master independently verifies original sources, current rights and immutable submission time. This does not accept a return, restore stock or pay money. Keep this original request and operation if rejected; do not remove the inspection option to retry.",
                    "إفادة موظف فقط. يتحقق الجهاز الرئيسي مستقلًا من المصادر الأصلية والحقوق السارية ووقت تقديم المطالبة الثابت. لا يقبل هذا مرتجعًا أو يعيد المخزون أو يدفع المال. احتفظ بالطلب والعملية الأصليين إذا رُفضا؛ لا تحذف خيار الفحص لإعادة المحاولة.")))
            }
            if (value.recordOriginalCommercialTiming) add(recordValue(
                fieldLabel(LocalField.RECORD_ORIGINAL_COMMERCIAL_TIMING), text(
                    "Qualification also requests a documentary timing observation from the exact captured period, actual customer receipt and original Master-recorded submission. This is not legal eligibility, a remedy deadline, independent clock proof or refund/stock authority. Missing or unsupported timing creates no deadline. Keep this original request and operation if an older Master rejects the option; do not remove it to retry.",
                    "يطلب التقييم أيضًا ملاحظة توقيت توثيقية من الفترة الأصلية المحفوظة واستلام العميل الفعلي وتقديم المطالبة الأصلي المسجل بالجهاز الرئيسي. ليست أهلية قانونية أو موعد معالجة أو إثباتًا مستقلًا للساعة أو صلاحية لرد المال أو تغيير المخزون. لا ينشئ التوقيت الناقص أو غير المدعوم موعدًا. احتفظ بالطلب والعملية الأصليين إذا رفض جهاز رئيسي أقدم هذا الخيار؛ لا تحذفه لإعادة المحاولة.")))
        }
        is LocalReturnCaseRequestV1.Accept -> {
            add(returnRevisionValue(LocalField.EXPECTED_CASE_REVISION, value.expectedCaseRevision))
            add(returnRevisionValue(LocalField.EXPECTED_ENTITLEMENT_REVISION, value.expectedEntitlementRevision))
            add(returnRevisionValue(LocalField.EXPECTED_CLAIM_REVISION, value.expectedClaimRevision))
            add(recordValue(fieldLabel(LocalField.DECISION_OPERATION_ID), literal(value.decisionOperationId.value)))
            add(recordValue(fieldLabel(LocalField.RETURN_ID), literal(value.returnId.value)))
            add(recordValue(fieldLabel(LocalField.DISPOSITION), returnDispositionLabel(value.disposition)))
            add(recordValue(fieldLabel(LocalField.EVIDENCE_ID), literal(value.inspectionEvidence.evidenceId.value)))
            add(recordValue(fieldLabel(LocalField.OCCURRED_AT), literal(value.inspectionEvidence.occurredAt.canonicalString())))
        }
    }
}

private fun returnOrdinaryInspectionValues(value: LocalReturnOrdinaryInspectionV1): List<LocalRecordValue> = listOf(
    recordValue(fieldLabel(LocalField.ORDINARY_INSPECTION_CONDITION), text("Employee attests inspected unchanged condition", "يفيد الموظف بفحص الحالة وبقائها دون تغيير")),
    recordValue(fieldLabel(LocalField.ORDINARY_INSPECTION_EVIDENCE_ID), literal(value.evidence.evidenceId.value)),
    recordValue(fieldLabel(LocalField.ORDINARY_INSPECTION_OCCURRED_AT), literal(value.reportedOccurredAt)),
)

private fun returnRevisionValue(field: LocalField, revision: Revision): LocalRecordValue = recordValue(fieldLabel(field), literal(revision.value.toString()))

/** Strict original-result admission. Presentation alone cannot authorize retention or ACK. */
internal fun validateReturnCaseRecorded(entry: StoredClientIntentV1, reply: MutationReply.Recorded) {
    val (original, value) = decodeReturnCaseRecorded(entry, reply)
    val applied = value as? LocalReturnCaseResultV1.Applied ?: return
    val expectedCaseRevision = when (original) {
        is LocalReturnCaseRequestV1.Record -> Revision(0)
        is LocalReturnCaseRequestV1.AppendReceiptEvidence -> nextReturnRevision(original.expectedCaseRevision)
        is LocalReturnCaseRequestV1.Qualify -> nextReturnRevision(original.expectedCaseRevision)
        is LocalReturnCaseRequestV1.Accept -> nextReturnRevision(original.expectedCaseRevision)
    }
    if (applied.caseRevision != expectedCaseRevision) unavailable()
}

/** Non-authorizing shape projection; actual gateway publication first requires strict admission. */
internal fun returnCaseRecorded(entry: StoredClientIntentV1, reply: MutationReply.Recorded): Pair<LocalText, LocalRecord?> {
    val (_, value) = decodeReturnCaseRecorded(entry, reply)
    if (value is LocalReturnCaseResultV1.Refused) return text(
        "This case operation was durably refused: ${value.reason}. Previously recorded claims, obligations and evidence remain unchanged.",
        "رُفضت عملية المطالبة وسُجل الرفض: ${value.reason}. تظل المطالبات والالتزامات والأدلة السابقة دون تغيير.") to null
    val applied = value as? LocalReturnCaseResultV1.Applied ?: unavailable()
    val message = when (applied.action) {
        LocalReturnCaseActionV1.RECORDED -> text("Original claim recorded, including unknown receipt facts. No entitlement, stock or payment changed.",
            "سُجلت المطالبة الأصلية بما في ذلك بيانات الإيصال غير المعلومة. لم يتغير استحقاق أو مخزون أو دفع.")
        LocalReturnCaseActionV1.EVIDENCE_APPENDED -> text("Receipt evidence appended. Original customer assertions remain unchanged; no refund was paid.",
            "أُضيف إثبات الإيصال. تظل إفادات العميل الأصلية دون تغيير؛ ولم يُدفع مبلغ رد.")
        LocalReturnCaseActionV1.ASSESSMENT_PENDING -> text("Assessment recorded; the claim remains pending. Review the route and missing sources; do not infer denial of all rights.",
            "سُجل التقييم؛ وتظل المطالبة معلقة. راجع المسار والمصادر الناقصة؛ لا تستنتج رفض جميع الحقوق.")
        LocalReturnCaseActionV1.BOUND -> text("Original refund claim qualified and bound. Acceptance, stock disposition and payout have not occurred.",
            "تأهلت مطالبة الرد الأصلية وربطت. لم يحدث قبول أو تصرف بالمخزون أو صرف.")
        LocalReturnCaseActionV1.ACCEPTED -> text("Return accepted against original entitlement. The refund obligation is recorded, not paid; stock disposition is still separate.",
            "قُبل المرتجع مقابل الاستحقاق الأصلي. سُجل التزام الرد ولم يُدفع؛ ويظل التصرف بالمخزون منفصلًا.")
    }
    return message to LocalRecord(LocalRecordReference(LocalRecordKind.RETURN_CASE, applied.caseId), entry.label.ui(),
        returnCaseStage(applied.stage), buildList {
            add(returnRevisionValue(LocalField.EXPECTED_CASE_REVISION, applied.caseRevision))
            applied.claimRevision?.let { add(returnRevisionValue(LocalField.EXPECTED_CLAIM_REVISION, it)) }
            applied.entitlementRevision?.let { add(returnRevisionValue(LocalField.EXPECTED_ENTITLEMENT_REVISION, it)) }
            applied.returnId?.let { add(recordValue(fieldLabel(LocalField.RETURN_ID), literal(it.value))) }
            applied.originalPayable?.let { add(recordValue(text("Original accepted allocation — not paid", "التخصيص الأصلي المقبول — غير مدفوع"), moneyText(it))) }
        })
}

private fun decodeReturnCaseRecorded(entry: StoredClientIntentV1, reply: MutationReply.Recorded):
    Pair<LocalReturnCaseRequestV1, LocalReturnCaseResultV1> {
    val request = entry.request()
    if (!actionMatches(entry.localAction, request)) unavailable()
    val original = LocalReturnCaseCommandCodecV1.decode(request.payload)
    val value = LocalReturnCaseCommandCodecV1.decodeResult(reply.result)
    if (value is LocalReturnCaseResultV1.Refused) {
        if (reply.committed) unavailable()
        return original to value
    }
    val applied = value as? LocalReturnCaseResultV1.Applied ?: unavailable()
    if (!reply.committed || applied.caseId != returnCaseId(original) || applied.eventOperationId != reply.operationId ||
        applied.eventOperationId != entry.operationId || applied.action !in when (original) {
            is LocalReturnCaseRequestV1.Record -> setOf(LocalReturnCaseActionV1.RECORDED)
            is LocalReturnCaseRequestV1.AppendReceiptEvidence -> setOf(LocalReturnCaseActionV1.EVIDENCE_APPENDED)
            is LocalReturnCaseRequestV1.Qualify -> setOf(LocalReturnCaseActionV1.ASSESSMENT_PENDING, LocalReturnCaseActionV1.BOUND)
            is LocalReturnCaseRequestV1.Accept -> setOf(LocalReturnCaseActionV1.ACCEPTED)
        } || original is LocalReturnCaseRequestV1.Accept && applied.returnId != original.returnId) unavailable()
    if (applied.caseRevision.value >= LocalReturnCaseProfileV1.MAX_EVENTS ||
        applied.stage != when (applied.action) {
            LocalReturnCaseActionV1.RECORDED, LocalReturnCaseActionV1.EVIDENCE_APPENDED,
            LocalReturnCaseActionV1.ASSESSMENT_PENDING -> LocalReturnCaseStageV1.PENDING
            LocalReturnCaseActionV1.BOUND -> LocalReturnCaseStageV1.BOUND_UNACCEPTED
            LocalReturnCaseActionV1.ACCEPTED -> LocalReturnCaseStageV1.ACCEPTED_DISPOSITION_PENDING
        }) unavailable()
    when (original) {
        is LocalReturnCaseRequestV1.Record, is LocalReturnCaseRequestV1.AppendReceiptEvidence ->
            if (applied.claimRevision != null || applied.entitlementRevision != null ||
                applied.returnId != null || applied.originalPayable != null) unavailable()
        is LocalReturnCaseRequestV1.Qualify -> {
            if (applied.entitlementRevision != original.expectedEntitlementRevision ||
                applied.returnId != null || applied.originalPayable != null ||
                applied.claimRevision != (if (applied.action == LocalReturnCaseActionV1.BOUND) Revision(0) else null) ||
                applied.action == LocalReturnCaseActionV1.BOUND &&
                original.ordinaryInspection?.condition != LocalReturnInspectionConditionV1.UNCHANGED) unavailable()
        }
        is LocalReturnCaseRequestV1.Accept ->
            if (applied.entitlementRevision != nextReturnRevision(original.expectedEntitlementRevision) ||
                applied.claimRevision != nextReturnRevision(original.expectedClaimRevision) ||
                applied.originalPayable?.let { it.minor >= 0L } != true) unavailable()
    }
    if (original is LocalReturnCaseRequestV1.Qualify && original.expectedEntitlementRevision == null &&
        (applied.action != LocalReturnCaseActionV1.ASSESSMENT_PENDING || applied.stage != LocalReturnCaseStageV1.PENDING ||
            applied.entitlementRevision != null || applied.claimRevision != null || applied.returnId != null ||
            applied.originalPayable != null)) unavailable()
    return original to applied
}

private fun nextReturnRevision(value: Revision): Revision {
    if (value.value == Long.MAX_VALUE) unavailable()
    return Revision(value.value + 1L)
}
