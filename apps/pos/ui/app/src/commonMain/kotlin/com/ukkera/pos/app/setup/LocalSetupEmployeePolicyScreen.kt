package com.ukkera.pos.app.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ukkera.pos.app.ui.AppLanguage
import com.ukkera.pos.app.ui.GrantDetails
import com.ukkera.pos.app.ui.LocalAppLanguage
import com.ukkera.pos.app.ui.Notice
import com.ukkera.pos.app.ui.PosTheme
import com.ukkera.pos.app.ui.ReviewField
import com.ukkera.pos.app.ui.ScopeDetails
import com.ukkera.pos.app.ui.SectionHeading
import com.ukkera.pos.app.ui.touchTarget
import com.ukkera.pos.security.LocalEmployeePolicyActivationPhase
import com.ukkera.pos.types.DomainId

/** Complete policy DATA and explicit separate decisions; authority remains on the native Master. */
@Composable
internal fun LocalSetupEmployeePolicyScreen(controller: LocalSetupController, view: LocalSetupView,
    language: AppLanguage, onLanguageChange: (AppLanguage) -> Unit, modifier: Modifier, onBack: (() -> Unit)?) {
    val selected = view.employeePolicy
    val policy = selected?.requested?.nextPolicy ?: selected?.submittedPolicy
    val enabled = view.work == null && view.localClientConnected
    CompositionLocalProvider(LocalAppLanguage provides language, LocalLayoutDirection provides language.direction) {
        PosTheme {
            LazyColumn(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    LocalSetupCompositionNotice(controller)
                    onBack?.let { back -> OutlinedButton(onClick = { controller.lockPresentation(); back() },
                        modifier = Modifier.touchTarget()) { Text(t("Device choices", "خيارات الجهاز")) } }
                    OutlinedButton(onClick = controller::lockPresentation, modifier = Modifier.touchTarget()) {
                        Text(t("Lock setup", "قفل الإعداد"))
                    }
                    OutlinedButton(onClick = { onLanguageChange(language.toggle()) }, modifier = Modifier.touchTarget()) {
                        Text(if (language == AppLanguage.ENGLISH) "العربية" else "English")
                    }
                    SectionHeading(t("Complete local employee policy", "سياسة الموظف المحلي كاملةً"))
                    Notice(t("Prepare one existing employee's complete replacement, review it with the named company head, then choose activation separately. Selecting or approving a policy does not activate it.",
                        "حضّر سياسة بديلة كاملة لموظف موجود، وراجعها مع رئيس الشركة المسمّى، ثم اختر التفعيل بصورة منفصلة. لا يؤدي اختيار السياسة أو اعتمادها إلى تفعيلها."))
                    Notice(t("The Master checks the current employee, session, device and policy state. This changes no employee password, token lifetime or device admission.",
                        "يفحص الجهاز الرئيسي حالة الموظف والجلسة والجهاز والسياسة الحالية. لا يغيّر هذا كلمة مرور الموظف أو مدة رموز الدخول أو قبول الأجهزة."))
                    view.work?.let {
                        CircularProgressIndicator()
                        Text(workText(it), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    view.problem?.let { Notice(problemText(it), caution = true,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) }
                    if (selected == null) {
                        if (view.employeePolicies?.newDefinitionAvailable == true) Button(onClick = controller::beginEmployeePolicy,
                            enabled = enabled, modifier = Modifier.fillMaxWidth().touchTarget()) {
                            Text(t("Define a complete replacement policy", "تعريف سياسة بديلة كاملة"))
                        } else Notice(t("Inspect the retained unfinished original before preparing another policy.",
                            "افحص الأصل المحفوظ غير المكتمل قبل تحضير سياسة أخرى."), caution = true)
                        SectionHeading(t("Retained original policy requests", "طلبات السياسة الأصلية المحفوظة"))
                        Notice(t("These entries are original policy-change history, not a current employee directory. A new replacement requires an explicit existing employee ID and expected policy revision.",
                            "هذه السجلات تاريخ تغييرات السياسة الأصلية، وليست دليل الموظفين الحالي. تتطلب السياسة البديلة الجديدة معرّف موظف موجود ومراجعة سياسة متوقعة صراحةً."))
                        OutlinedButton(onClick = controller::openEmployeePolicies, enabled = enabled,
                            modifier = Modifier.fillMaxWidth().touchTarget()) { Text(t("Refresh retained originals", "تحديث الأصول المحفوظة")) }
                        OutlinedButton(onClick = controller::closeEmployeePolicies, enabled = view.work == null,
                            modifier = Modifier.touchTarget()) { Text(t("Return to local setup", "العودة إلى الإعداد المحلي")) }
                    } else {
                        ScopeDetails(selected.scope)
                        ReviewField(t("Original request", "الطلب الأصلي"), selected.originalRequestId.value)
                        selected.originalActionId?.let { ReviewField(t("Original policy approval action", "إجراء اعتماد السياسة الأصلي"), it.value) }
                        selected.actorId?.let { ReviewField(t("Exact employee ID", "معرّف الموظف الدقيق"), it.value) }
                        selected.nativePhase?.let { ReviewField(t("Retained original phase", "مرحلة الأصل المحفوظ"), policyNativePhaseText(it), code = false) }
                        Notice(policyStageText(selected.stage), caution = selected.stage in setOf(
                            LocalSetupEmployeePolicyStage.PREPARATION_UNRESOLVED, LocalSetupEmployeePolicyStage.APPROVAL_UNRESOLVED,
                            LocalSetupEmployeePolicyStage.ACTIVATION_UNRESOLVED, LocalSetupEmployeePolicyStage.ORIGINAL_UNRESOLVED))
                        selected.outcome?.takeIf { it != LocalSetupEmployeePolicyOutcome.APPLIED }?.let {
                            Notice(policyOutcomeText(it), caution = true)
                        }
                        selected.eventId?.let { ReviewField(t("Recorded original activation event", "حدث التفعيل الأصلي المسجّل"), it.value) }
                        if (selected.stage == LocalSetupEmployeePolicyStage.DEFINITION) {
                            LocalSetupEmployeePolicyInputs(selected, enabled, controller::prepareEmployeePolicy)
                            OutlinedButton(onClick = { controller.finishEmployeePolicy(selected.reviewId) }, enabled = enabled,
                                modifier = Modifier.touchTarget()) { Text(t("Cancel this unsubmitted editor", "إلغاء هذا المحرر غير المرسل")) }
                        }
                        if (policy != null) {
                            SectionHeading(if (selected.requested == null) t("Entered complete policy; native review unavailable", "السياسة المدخلة كاملةً؛ المراجعة الأصلية غير متاحة")
                                else t("Complete retained policy review", "مراجعة السياسة المحفوظة كاملةً"))
                            selected.companyName?.let { ReviewField(t("Company", "الشركة"), it, code = false) }
                            selected.headName?.let { ReviewField(t("Named company head", "رئيس الشركة المسمّى"), it, code = false) }
                            selected.headLogin?.let { ReviewField(t("Exact company-head login", "معرّف دخول رئيس الشركة الدقيق"), it) }
                            selected.rootFingerprint?.let { ReviewField(t("Company root fingerprint", "بصمة أصل الشركة"), it) }
                            selected.headAuthorityId?.let { ReviewField(t("Approving head authority ID", "معرّف سلطة الرئيس المعتمد"), it.value) }
                            selected.headEnrollmentId?.let { ReviewField(t("Approving head enrollment ID", "معرّف تسجيل الرئيس المعتمد"), it.value) }
                            selected.authorityDigest?.let { ReviewField(t("Original approval digest", "بصمة الاعتماد الأصلي"), it) }
                            selected.requested?.let {
                                ReviewField(t("Complete definition digest", "بصمة التعريف الكامل"), it.definitionDigest.value)
                                ReviewField(t("Exact activation request digest", "بصمة طلب التفعيل الدقيقة"), it.request.contentDigest.value)
                            }
                            ScopeDetails(policy.scope)
                            ReviewField(t("Policy employee ID", "معرّف موظف السياسة"), policy.actorId.value)
                            ReviewField(t("Expected predecessor revision", "مراجعة السياسة السابقة المتوقعة"), (policy.revision.value - 1L).toString())
                            ReviewField(t("Requested replacement revision", "مراجعة السياسة البديلة المطلوبة"), policy.revision.value.toString())
                            ReviewField(t("Complete grant count", "عدد الصلاحيات الكامل"), policy.grants.size.toString())
                            if (policy.grants.isEmpty()) Notice(t("No grants are included; no current grants or role are inherited.",
                                "لا توجد صلاحيات مضمنة؛ لا تُورَّث صلاحيات حالية أو دور."), caution = true)
                        }
                    }
                }
                if (selected == null) items(view.employeePolicies?.originals.orEmpty(), key = { it.originalActionId.value }) { original ->
                    ReviewField(t("Original request", "الطلب الأصلي"), original.originalRequestId.value)
                    ReviewField(t("Employee ID", "معرّف الموظف"), original.actorId.value)
                    ReviewField(t("Original phase", "المرحلة الأصلية"), policyNativePhaseText(original.phase), code = false)
                    OutlinedButton(onClick = { controller.inspectEmployeePolicy(original.originalRequestId) }, enabled = enabled,
                        modifier = Modifier.fillMaxWidth().touchTarget()) { Text(t("Inspect only this original", "فحص هذا الأصل فقط")) }
                }
                itemsIndexed(policy?.grants.orEmpty()) { index, grant -> GrantDetails(index, grant) }
                item {
                    if (policy != null) LocalSetupPolicyConfigurationReview(policy)
                    if (selected != null) {
                        when (selected.stage) {
                            LocalSetupEmployeePolicyStage.REVIEW -> if (selected.requested != null && selected.headLogin != null)
                                EmployeePolicyHeadApproval(selected, enabled, controller::approveEmployeePolicy)
                            LocalSetupEmployeePolicyStage.APPROVED -> if (selected.requested != null)
                                EmployeePolicyActivation(selected, enabled, controller::activateApprovedEmployeePolicy)
                            LocalSetupEmployeePolicyStage.APPLIED, LocalSetupEmployeePolicyStage.NOT_RECORDED ->
                                OutlinedButton(onClick = { controller.finishEmployeePolicy(selected.reviewId) }, enabled = enabled,
                                    modifier = Modifier.fillMaxWidth().touchTarget()) { Text(t("Keep this original history and return to setup", "حفظ تاريخ هذا الأصل والعودة إلى الإعداد")) }
                            else -> Unit
                        }
                        if (selected.stage != LocalSetupEmployeePolicyStage.DEFINITION) OutlinedButton(
                            onClick = { controller.lookupEmployeePolicy(selected.reviewId) }, enabled = enabled,
                            modifier = Modifier.fillMaxWidth().touchTarget()) {
                            Text(t("Inspect / look up only this original", "فحص / الاستعلام عن هذا الأصل فقط"))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmployeePolicyHeadApproval(review: LocalSetupEmployeePolicyView, enabled: Boolean,
    approve: (DomainId, CharArray) -> Unit) {
    var secret by remember(review.reviewId) { mutableStateOf("") }
    var consent by remember(review.reviewId) { mutableStateOf(false) }
    DisposableEffect(review.reviewId, enabled) { onDispose { secret = ""; consent = false } }
    SetupSecret(secret, { secret = it; consent = false }, t("Existing named company-head password", "كلمة مرور رئيس الشركة المسمّى الحالية"), enabled)
    Consent(t("I reviewed the exact employee, both revisions, every grant, limit, session, throttle and approval field; approve only this complete original without activation",
        "راجعت الموظف الدقيق والمراجعتين وجميع الصلاحيات والحدود وحقول الجلسة والتأخير والموافقة؛ اعتمد هذا الأصل الكامل فقط دون تفعيل"), consent, enabled) { consent = it }
    Button(onClick = {
        val head = secret.toCharArray()
        try { secret = ""; consent = false; approve(review.reviewId, head) }
        finally { head.fill('\u0000') }
    }, enabled = enabled && consent && secret.length in 12..256, modifier = Modifier.fillMaxWidth().touchTarget()) {
        Text(t("Approve this original policy only", "اعتماد هذه السياسة الأصلية فقط"))
    }
}

@Composable
private fun EmployeePolicyActivation(review: LocalSetupEmployeePolicyView, enabled: Boolean, activate: (DomainId) -> Unit) {
    var consent by remember(review.reviewId) { mutableStateOf(false) }
    DisposableEffect(review.reviewId, enabled) { onDispose { consent = false } }
    Consent(t("Activate the unchanged complete policy shown above for this exact employee and expected revision",
        "تفعيل السياسة الكاملة دون تغيير كما تظهر أعلاه لهذا الموظف الدقيق والمراجعة المتوقعة"), consent, enabled) { consent = it }
    Button(onClick = { consent = false; activate(review.reviewId) }, enabled = enabled && consent,
        modifier = Modifier.fillMaxWidth().touchTarget()) { Text(t("Activate this approved original", "تفعيل هذا الأصل المعتمد")) }
}

@Composable
private fun policyStageText(stage: LocalSetupEmployeePolicyStage): String = when (stage) {
    LocalSetupEmployeePolicyStage.DEFINITION -> t("Enter the complete replacement. Nothing has been prepared, approved or activated.", "أدخل السياسة البديلة كاملةً. لم يُحضّر شيء أو يُعتمد أو يُفعّل.")
    LocalSetupEmployeePolicyStage.PREPARATION_UNRESOLVED -> t("Preparation is unresolved. Keep this request and inspect only its original; do not prepare a replacement request.", "التحضير غير محسوم. احتفظ بهذا الطلب وافحص أصله فقط؛ لا تحضّر طلبًا بديلًا.")
    LocalSetupEmployeePolicyStage.REVIEW -> t("This exact complete original is prepared for named-head review. It has not been activated.", "هذا الأصل الكامل بعينه محضّر لمراجعة الرئيس المسمّى. لم يُفعّل.")
    LocalSetupEmployeePolicyStage.APPROVAL_UNRESOLVED -> t("Head approval is unresolved. Inspect this original without signing again or activating it.", "اعتماد الرئيس غير محسوم. افحص هذا الأصل دون توقيع جديد أو تفعيل.")
    LocalSetupEmployeePolicyStage.APPROVED -> t("The original has head approval. Activation requires a separate explicit confirmation below.", "حصل الأصل على اعتماد الرئيس. يتطلب التفعيل تأكيدًا صريحًا منفصلًا أدناه.")
    LocalSetupEmployeePolicyStage.ACTIVATION_UNRESOLVED -> t("The original activation outcome is unresolved. Use only original lookup; do not submit another activation.", "نتيجة التفعيل الأصلي غير محسومة. استخدم الاستعلام عن الأصل فقط؛ لا ترسل تفعيلًا آخر.")
    LocalSetupEmployeePolicyStage.APPLIED -> t("The Master recorded this original policy activation. A later policy can change current access; this is the original result.", "سجّل الجهاز الرئيسي تفعيل هذه السياسة الأصلية. قد تغيّر سياسة لاحقة الوصول الحالي؛ هذه نتيجة الأصل.")
    LocalSetupEmployeePolicyStage.NOT_RECORDED -> t("This rejected original has no recorded activation. Its original history remains retained.", "لا يوجد تفعيل مسجّل لهذا الأصل المرفوض. يظل تاريخه الأصلي محفوظًا.")
    LocalSetupEmployeePolicyStage.ORIGINAL_UNRESOLVED -> t("The exact original cannot currently be resolved. Retain its identity and use original inspection.", "لا يمكن حسم الأصل الدقيق حاليًا. احتفظ بهويته واستخدم فحص الأصل.")
}

@Composable
private fun policyOutcomeText(outcome: LocalSetupEmployeePolicyOutcome): String = when (outcome) {
    LocalSetupEmployeePolicyOutcome.APPLIED -> t("Original activation recorded.", "التفعيل الأصلي مسجّل.")
    LocalSetupEmployeePolicyOutcome.NOT_RECORDED -> t("No activation was recorded at this lookup. This observation alone does not cancel a still-running request.", "لم يُسجّل تفعيل عند هذا الاستعلام. لا تلغي هذه الملاحظة وحدها طلبًا ما زال قيد التنفيذ.")
    LocalSetupEmployeePolicyOutcome.DENIED -> t("The request or result read was refused. No successful activation is established.", "رُفض الطلب أو قراءة النتيجة. لم يثبت نجاح التفعيل.")
    LocalSetupEmployeePolicyOutcome.UNAVAILABLE -> t("The original operation or result read is unavailable. Keep this original for inspection.", "العملية الأصلية أو قراءة نتيجتها غير متاحة. احتفظ بهذا الأصل للفحص.")
    LocalSetupEmployeePolicyOutcome.STORAGE_UNCERTAIN -> t("The original storage or operation outcome is uncertain. Look up this same original.", "نتيجة التخزين أو العملية الأصلية غير مؤكدة. استعلم عن الأصل نفسه.")
    LocalSetupEmployeePolicyOutcome.BUSY -> t("The original operation or lookup is busy. Inspect this same original after the owned work settles.", "العملية الأصلية أو الاستعلام مشغول. افحص الأصل نفسه بعد انتهاء العمل الجاري.")
}

@Composable
private fun policyNativePhaseText(phase: LocalEmployeePolicyActivationPhase): String = when (phase) {
    LocalEmployeePolicyActivationPhase.PREPARED -> t("Prepared", "محضّر")
    LocalEmployeePolicyActivationPhase.SIGNING_INTENT -> t("Original signing unresolved", "التوقيع الأصلي غير محسوم")
    LocalEmployeePolicyActivationPhase.APPROVED -> t("Head approved", "معتمد من الرئيس")
    LocalEmployeePolicyActivationPhase.DISPATCH_INTENT -> t("Original activation dispatch retained", "إرسال التفعيل الأصلي محفوظ")
    LocalEmployeePolicyActivationPhase.CONSUMPTION_INTENT -> t("Original activation consumption retained", "استهلاك التفعيل الأصلي محفوظ")
    LocalEmployeePolicyActivationPhase.CONSUMED -> t("Original activation consumed", "التفعيل الأصلي مستهلك")
    LocalEmployeePolicyActivationPhase.REJECTED -> t("Original rejected", "الأصل مرفوض")
}
