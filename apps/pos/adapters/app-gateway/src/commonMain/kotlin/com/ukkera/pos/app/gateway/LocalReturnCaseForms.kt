package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision

internal fun returnIntakeForm(access: GatewayAccess, saleId: DomainId? = null, portion: LocalReturnReceiptLineV1? = null): LocalActionForm? {
    if (!access.installed.has(LocalReturnCaseProfileV1.COMMAND) || !access.allow(LocalAction.RETURN_CASE_RECORD)) return null
    return form(LocalAction.RETURN_CASE_RECORD, listOf(newIdField(LocalField.CASE_ID),
        idField(LocalField.SALE_ID, saleId, required = saleId != null),
        idField(LocalField.PORTION_ID, portion?.portionId, required = portion != null)) +
        returnQuantityFields(portion?.originalQuantity, required = portion != null, includeQuantity = false) + listOf(
        instantField(LocalField.ASSERTED_RECEIVED_AT, required = false), instantField(LocalField.CLAIMED_AT),
        scalar(LocalField.CLAIM_TIME_ZONE, LocalInputKind.TEXT, maximum = 64,
            help = text("Enter the actual time-zone ID, for example Africa/Cairo. No delivery date is inferred from it.",
                "أدخل معرف المنطقة الزمنية الفعلي، مثل Africa/Cairo. لا يُستنتج منه تاريخ تسليم.")),
        scalar(LocalField.REASON, LocalInputKind.MULTILINE, maximum = 256),
        choiceField(LocalField.CLAIMED_REASON, LocalReturnClaimReasonV1.entries.map { LocalChoice(it.name, returnClaimReasonLabel(it)) })
            .copy(required = false, help = text(
                "Select only the customer's explicitly stated category. Leave unknown reasons unselected; the original narrative is retained separately. This does not establish eligibility or inspection.",
                "اختر فقط الفئة التي صرّح بها العميل. اترك السبب غير المعلوم دون اختيار؛ يُحفظ الوصف الأصلي منفصلًا. لا يثبت هذا الأهلية أو الفحص.")),
        scalar(LocalField.CONDITION, LocalInputKind.MULTILINE, maximum = 256),
        choiceField(LocalField.REQUESTED_REMEDY, LocalReturnRequestedRemedyV1.entries.map { LocalChoice(it.name, returnRemedyLabel(it)) }),
        newIdField(LocalField.EVIDENCE_ID), instantField(LocalField.OCCURRED_AT)), text(
            "Record the customer's actual original assertions and requested remedy. Receipt/portion/quantity may be unknown; supply unit and precision together with any quantity. Missing policy or receipt must not erase intake. No refund, stock release, acceptance or promised deadline is created here.",
            "سجل إفادات العميل الأصلية الفعلية والمعالجة المطلوبة. قد يكون الإيصال أو الجزء أو الكمية غير معلوم؛ أدخل الوحدة والدقة مع أي كمية. لا يمحو غياب السياسة أو الإيصال التسجيل. لا تنشأ هنا دفعة رد أو إتاحة مخزون أو قبول أو موعد معالجة موعود."))
}

internal fun returnReceiptEvidenceForm(access: GatewayAccess, value: LocalReturnCaseViewV1): LocalActionForm? {
    if (value.summary.stage != LocalReturnCaseStageV1.PENDING || !access.installed.has(LocalReturnCaseProfileV1.COMMAND) ||
        !access.allow(LocalAction.RETURN_CASE_RECEIPT, value.summary.caseId)) return null
    val binding = value.binding
    val facts = value.facts.asserted
    return form(LocalAction.RETURN_CASE_RECEIPT, returnCaseBase(value) + listOf(
        idField(LocalField.SALE_ID, binding?.saleId ?: facts.saleId),
        idField(LocalField.PORTION_ID, binding?.portionId ?: facts.portionId)) +
        returnQuantityFields(binding?.quantity ?: facts.quantity, required = true, includeQuantity = true) + listOf(
            newIdField(LocalField.EVIDENCE_ID), instantField(LocalField.OCCURRED_AT)), text(
            "Append documentary receipt evidence; do not rewrite the original claim, quantity or customer dates. An existing asserted or bound value cannot be replaced. This is not delivery proof, eligibility or payment.",
            "أضف إثباتًا توثيقيًا للإيصال؛ لا تعد كتابة المطالبة الأصلية أو الكمية أو تواريخ العميل. لا يمكن استبدال قيمة سبق ذكرها أو ربطها. هذا ليس إثبات تسليم أو أهلية أو دفعًا."))
}

internal fun returnCaseDecisionForm(access: GatewayAccess, value: LocalReturnCaseViewV1, entitlement: Revision?): LocalActionForm? {
    if (!access.installed.has(LocalReturnCaseProfileV1.COMMAND)) return null
    val base = returnCaseBase(value) + listOfNotNull(entitlement?.let { revisionField(LocalField.EXPECTED_ENTITLEMENT_REVISION, it) })
    return when (value.summary.stage) {
        LocalReturnCaseStageV1.PENDING -> if (!access.allow(LocalAction.RETURN_CASE_QUALIFY, value.summary.caseId)) null
            else if (entitlement == null) form(LocalAction.RETURN_CASE_QUALIFY, base, text(
                "Request a pending assessment without an observed entitlement revision. The master must independently establish the original receipt's absence; an unavailable receipt lookup proves nothing. A present receipt requires its actual revision. This cannot accept a return, decide eligibility or expiry, or create stock or money effects.",
                "اطلب تقييمًا معلقًا دون رقم إصدار استحقاق تمت ملاحظته. يجب أن يتحقق الجهاز الرئيسي بنفسه من غياب الإيصال الأصلي؛ عدم توفر البحث لا يثبت غيابه. يتطلب الإيصال الموجود رقم إصداره الفعلي. لا يقبل هذا مرتجعًا أو يقرر الأهلية أو انتهاء الحق أو ينشئ أثرًا على المخزون أو المال."))
            else form(LocalAction.RETURN_CASE_QUALIFY, base + ordinaryInspectionFields(value) + listOf(
                scalar(LocalField.RECORD_ORIGINAL_COMMERCIAL_TIMING, LocalInputKind.CHECKBOX,
                    LocalScalarValue.Flag(false), required = false, help = text(
                        "Optional: request a documentary timing observation only when exact original sources establish it. It uses the captured elapsed period, complete customer receipt and original Master-recorded submission. It is not legal eligibility, a remedy deadline or independent clock proof, and does not authorize refund or stock effects. If selected, keep the original request and operation even if an older Master rejects it; do not remove the option to retry.",
                        "اختياري: اطلب ملاحظة توقيت توثيقية فقط عندما تثبتها المصادر الأصلية الدقيقة. تستخدم الفترة المنقضية المحفوظة واستلام العميل الكامل وتقديم المطالبة الأصلي المسجل بالجهاز الرئيسي. ليست أهلية قانونية أو موعد معالجة أو إثباتًا مستقلًا للساعة، ولا تمنح صلاحية رد المال أو تغيير المخزون. إذا اخترتها، احتفظ بالطلب والعملية الأصليين حتى إذا رفضها جهاز رئيسي أقدم؛ لا تحذف الخيار لإعادة المحاولة."))), text(
                "Assess this original claim using the master's retained receipt, promised policy, applicable rights and actual customer-delivery evidence. An explicitly stated change-of-mind refund with genuine unchanged-condition inspection may qualify against protected ordinary windows. The original Master-recorded submission controls timing, not later processing. Qualification does not accept, pay or restock. Missing sources and unsupported remedies remain pending, not a denial of all rights.",
                "قيّم المطالبة الأصلية باستخدام إيصال الجهاز الرئيسي وسياسة البيع الموعودة والحقوق السارية وإثبات استلام العميل الفعلي. قد يتأهل طلب رد صريح لتغيير الرأي مع فحص فعلي يثبت بقاء الحالة دون تغيير وفق الفترات العادية المحمية. يتحكم وقت تقديم المطالبة الأصلي المسجل بالجهاز الرئيسي في التوقيت، لا المعالجة اللاحقة. لا يقبل التأهيل المرتجع أو يدفع أو يعيد المخزون. تظل المصادر الناقصة والمعالجات غير المدعومة معلقة، وليس ذلك رفضًا لجميع الحقوق."))
        LocalReturnCaseStageV1.BOUND_UNACCEPTED -> {
            if (entitlement == null) return null
            val binding = value.binding ?: unavailable()
            val decision = value.decision ?: unavailable()
            val claim = value.qualifiedClaimRevision ?: unavailable()
            if (value.facts.requestedRemedy != LocalReturnRequestedRemedyV1.REFUND ||
                value.facts.claimedReason != LocalReturnClaimReasonV1.CHANGE_OF_MIND ||
                decision.outcome != LocalReturnOrdinaryAssessmentV1.ELIGIBLE ||
                decision.ordinaryInspection?.condition != LocalReturnInspectionConditionV1.UNCHANGED ||
                decision.ordinaryTiming?.status != LocalReturnCommercialWindowStatusV1.WITHIN ||
                !access.allow(LocalAction.RETURN_CASE_ACCEPT, binding.saleId)) null
            else form(LocalAction.RETURN_CASE_ACCEPT, base + listOf(
                idField(LocalField.DECISION_OPERATION_ID, DomainId(decision.operationId.value)),
                revisionField(LocalField.EXPECTED_CLAIM_REVISION, claim), newIdField(LocalField.RETURN_ID),
                choiceField(LocalField.DISPOSITION, LocalReturnDispositionV1.entries.map { LocalChoice(it.name, returnDispositionLabel(it)) }),
                newIdField(LocalField.EVIDENCE_ID), instantField(LocalField.OCCURRED_AT)), text(
                "Accept only this qualified original quantity and decision. The master consumes remaining original entitlement atomically and computes the original refundable allocation; today's price is not used. Actual stock disposition and physical refund remain separate. Record genuine inspection evidence; do not hand out money from this form.",
                "اقبل فقط الكمية والقرار الأصليين المؤهلين. يستهلك الجهاز الرئيسي الاستحقاق الأصلي المتبقي ذريًا ويحسب تخصيص الرد الأصلي؛ لا يستخدم سعر اليوم. يظل التصرف الفعلي بالمخزون والرد الفعلي منفصلين. سجل إثبات فحص حقيقيًا؛ لا تصرف نقدية من هذا النموذج."))
        }
        LocalReturnCaseStageV1.ACCEPTED_DISPOSITION_PENDING -> null
    }
}

private fun ordinaryInspectionFields(value: LocalReturnCaseViewV1): List<LocalFormField> {
    if (value.facts.claimedReason != LocalReturnClaimReasonV1.CHANGE_OF_MIND ||
        value.facts.requestedRemedy != LocalReturnRequestedRemedyV1.REFUND) return emptyList()
    return listOf(
        choiceField(LocalField.ORDINARY_INSPECTION_CONDITION, LocalReturnInspectionConditionV1.entries.map {
            LocalChoice(it.name, text("Inspected — unchanged condition", "تم الفحص — الحالة دون تغيير"))
        }).copy(required = false, help = text(
            "Select only after you actually inspect the original goods and attest unchanged condition. Supply the inspection's evidence ID and reported timestamp with its UTC offset together. Leave all three fields blank if not performed. The Master records the authorized actor and operation independently. Retain the original request if an older Master rejects this option; never remove it to retry.",
            "اختر فقط بعد فحص البضائع الأصلية فعليًا والإفادة بأن حالتها لم تتغير. قدم معرف إثبات الفحص ووقته المذكور مع إزاحة UTC معًا. اترك الحقول الثلاثة فارغة إذا لم يُجرَ الفحص. يسجل الجهاز الرئيسي صاحب الصلاحية والعملية مستقلًا. احتفظ بالطلب الأصلي إذا رفض جهاز رئيسي أقدم هذا الخيار؛ لا تحذفه لإعادة المحاولة.")),
        idField(LocalField.ORDINARY_INSPECTION_EVIDENCE_ID, required = false),
        scalar(LocalField.ORDINARY_INSPECTION_OCCURRED_AT, LocalInputKind.TEXT, required = false, maximum = 64,
            help = text("ISO timestamp ending in Z or an explicit offset, for example +02:00. The exact reported text is retained separately from Master time.",
                "وقت ISO ينتهي بـ Z أو إزاحة صريحة مثل ‎+02:00. يُحفظ النص المذكور كما هو مستقلًا عن توقيت الجهاز الرئيسي.")),
    )
}

private fun returnCaseBase(value: LocalReturnCaseViewV1): List<LocalFormField> = listOf(
    idField(LocalField.CASE_ID, value.summary.caseId), revisionField(LocalField.EXPECTED_CASE_REVISION, value.summary.revision))

private fun returnQuantityFields(value: Quantity?, required: Boolean, includeQuantity: Boolean): List<LocalFormField> = listOf(
    idField(LocalField.UNIT, value?.unitId, required),
    scalar(LocalField.QUANTITY_SCALE, LocalInputKind.INTEGER, value?.let { LocalScalarValue.Integer(it.scale.toLong()) },
        required = required, readOnly = value != null, maximum = 1, help = text("Exact quantity precision, 0–6.", "دقة الكمية الدقيقة، من ٠ إلى ٦.")),
    scalar(LocalField.QUANTITY, LocalInputKind.DECIMAL,
        value?.takeIf { includeQuantity }?.let { LocalScalarValue.Decimal(fixedDecimal(it.scaled, it.scale)) },
        required = required, readOnly = includeQuantity && value != null, maximum = 32))

internal fun returnRemedyLabel(value: LocalReturnRequestedRemedyV1): LocalText = when (value) {
    LocalReturnRequestedRemedyV1.REFUND -> text("Refund", "رد المبلغ")
    LocalReturnRequestedRemedyV1.EXCHANGE -> text("Exchange — retain request, not a completed exchange", "استبدال — حفظ الطلب وليس استبدالًا مكتملًا")
    LocalReturnRequestedRemedyV1.REPAIR -> text("Repair — retain request, not a completed repair", "إصلاح — حفظ الطلب وليس إصلاحًا مكتملًا")
    LocalReturnRequestedRemedyV1.OTHER -> text("Other remedy — retain for review", "معالجة أخرى — تُحفظ للمراجعة")
}

internal fun returnClaimReasonLabel(value: LocalReturnClaimReasonV1): LocalText = when (value) {
    LocalReturnClaimReasonV1.CHANGE_OF_MIND -> text("Change of mind", "تغيير الرأي")
    LocalReturnClaimReasonV1.WRONG_SUPPLIED_ITEM -> text("Wrong item supplied", "تم تسليم صنف غير صحيح")
    LocalReturnClaimReasonV1.DEFECTIVE_OR_DAMAGED -> text("Defective or damaged", "معيب أو تالف")
    LocalReturnClaimReasonV1.MERCHANT_PREPARATION_ERROR -> text("Merchant preparation error", "خطأ في تجهيز التاجر")
    LocalReturnClaimReasonV1.QUALITY_COMPLAINT -> text("Quality complaint", "شكوى جودة")
    LocalReturnClaimReasonV1.FAILED_DELIVERY -> text("Failed delivery", "تعذر التسليم")
    LocalReturnClaimReasonV1.UNPERFORMED_SERVICE -> text("Service not performed", "لم تُنفذ الخدمة")
}

internal fun returnDispositionLabel(value: LocalReturnDispositionV1): LocalText = when (value) {
    LocalReturnDispositionV1.INSPECTED_SELLABLE -> text("Passed inspection — stock posting still separate", "اجتاز الفحص — ترحيل المخزون منفصل")
    LocalReturnDispositionV1.QUARANTINE -> text("Quarantine — not sellable", "حجز — غير قابل للبيع")
    LocalReturnDispositionV1.WASTE -> text("Waste — no sellable restoration", "هالك — لا يُعاد للمخزون القابل للبيع")
    LocalReturnDispositionV1.SUPPLIER_RETURN -> text("Supplier return — separate dispatch required", "مرتجع مورد — يتطلب إرسالًا منفصلًا")
}
