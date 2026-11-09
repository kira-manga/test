package com.ukkera.pos.app.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
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
import com.ukkera.pos.app.ui.localProblem
import com.ukkera.pos.types.DomainId

/** Real setup owner only. Secrets are visible unsaved input, then transferred as scrubbed arrays. */
@Composable
internal fun LocalSetupEmployeeEnrollmentScreen(controller: LocalSetupController, view: LocalSetupView,
    language: AppLanguage, onLanguageChange: (AppLanguage) -> Unit, modifier: Modifier,
    onBack: (() -> Unit)?) {
    val selected = view.employeeEnrollment
    val requested = selected?.requested
    val enabled = view.work == null
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
                    SectionHeading(t("Local employee enrollment", "تسجيل موظف محلي"))
                    Notice(t("The company head approves one complete employee definition. Creating the account, opening its private work journal and employee sign-in are separate steps. Device admission remains separate.",
                        "يعتمد رئيس الشركة تعريف موظف كاملًا واحدًا. إنشاء الحساب وفتح سجل عمله الخاص وتسجيل دخول الموظف خطوات منفصلة. ويظل قبول الجهاز منفصلًا."))
                    if (!enabled) CircularProgressIndicator()
                    view.problem?.let { Notice(problemText(it), caution = true) }
                    if (selected == null) {
                        val originals = view.employeeEnrollments
                        if (originals?.newDefinitionAvailable == true) Button(onClick = controller::beginEmployeeEnrollment,
                            enabled = enabled, modifier = Modifier.fillMaxWidth().touchTarget()) {
                            Text(t("Define a new employee", "تعريف موظف جديد"))
                        }
                        else Notice(t("Inspect the unfinished original before starting another employee definition.",
                            "افحص الأصل غير المكتمل قبل بدء تعريف موظف آخر."), caution = true)
                        SectionHeading(t("Retained original enrollments", "طلبات التسجيل الأصلية المحفوظة"))
                        Notice(t("Select the exact original request. These entries are enrollment history, not employee sign-in or permission to recreate an account.",
                            "اختر الطلب الأصلي بعينه. هذه السجلات تاريخ التسجيل، وليست تسجيل دخول للموظف أو تصريحًا بإعادة إنشاء الحساب."))
                        OutlinedButton(onClick = controller::openEmployeeEnrollment, enabled = enabled,
                            modifier = Modifier.fillMaxWidth().touchTarget()) {
                            Text(t("Refresh retained originals", "تحديث الأصول المحفوظة"))
                        }
                    } else {
                        ScopeDetails(selected.scope)
                        ReviewField(t("Original request", "الطلب الأصلي"), selected.originalRequestId.value)
                        selected.originalActionId?.let { ReviewField(t("Original head-approval action", "إجراء اعتماد الرئيس الأصلي"), it.value) }
                        ReviewField(t("Employee ID", "معرّف الموظف"), selected.actorId.value)
                        ReviewField(t("Employee's admitted devices", "الأجهزة المقبولة للموظف"),
                            selected.admittedDeviceIds.sortedBy { it.value }.joinToString("\n") { it.value })
                        Notice(employeeStageText(selected.stage), caution = selected.stage in setOf(
                            LocalSetupEmployeeEnrollmentStage.APPROVAL_UNRESOLVED,
                            LocalSetupEmployeeEnrollmentStage.ENROLLMENT_UNRESOLVED,
                            LocalSetupEmployeeEnrollmentStage.JOURNAL_UNRESOLVED,
                            LocalSetupEmployeeEnrollmentStage.ORIGINAL_UNRESOLVED,
                            LocalSetupEmployeeEnrollmentStage.REJECTED))
                        selected.serviceProblem?.let { Notice(localProblem(it), caution = true) }
                        if (selected.stage == LocalSetupEmployeeEnrollmentStage.DEFINITION) {
                            Notice(t("No grants are preselected. Choose this employee's exact access and limits. This ceremony uses only the already admitted device shown above.",
                                "لا توجد صلاحيات محددة مسبقًا. اختر وصول الموظف وحدوده بدقة. يستخدم هذا التسجيل الجهاز المقبول بالفعل الظاهر أعلاه فقط."))
                            LocalSetupAccountDefinitionInputs(selected.scope, selected.actorId,
                                selected.admittedDeviceIds, enabled, controller::prepareEmployeeEnrollment)
                        }
                        if (requested != null) {
                            SectionHeading(t("Complete head approval review", "المراجعة الكاملة لاعتماد الرئيس"))
                            selected.companyName?.let { ReviewField(t("Company", "الشركة"), it, code = false) }
                            selected.headName?.let { ReviewField(t("Company head", "رئيس الشركة"), it, code = false) }
                            selected.headLogin?.let { ReviewField(t("Exact company-head login", "معرّف دخول رئيس الشركة الدقيق"), it) }
                            selected.rootFingerprint?.let { ReviewField(t("Company root fingerprint", "بصمة أصل الشركة"), it) }
                            ReviewField(t("Complete definition digest", "بصمة التعريف الكامل"), requested.definitionDigest.value)
                            ReviewField(t("Exact account request digest", "بصمة طلب الحساب الدقيقة"), requested.request.contentDigest.value)
                            LocalSetupAccountIdentityReview(requested.definition)
                        }
                    }
                }
                if (selected == null) items(view.employeeEnrollments?.originals.orEmpty(), key = { it.originalRequestId.value }) { original ->
                    ReviewField(t("Original request", "الطلب الأصلي"), original.originalRequestId.value)
                    ReviewField(t("Employee ID", "معرّف الموظف"), original.actorId.value)
                    OutlinedButton(onClick = { controller.inspectEmployeeEnrollment(original.originalRequestId) },
                        enabled = enabled, modifier = Modifier.fillMaxWidth().touchTarget()) {
                        Text(t("Inspect this original", "فحص هذا الأصل"))
                    }
                }
                itemsIndexed(requested?.definition?.policy?.grants.orEmpty()) { index, grant -> GrantDetails(index, grant) }
                item {
                    if (requested != null) {
                        LocalSetupAccountConfigurationReview(requested.definition)
                        SectionHeading(t("Existing device admissions in this definition", "قبول الأجهزة الموجودة ضمن هذا التعريف"))
                        requested.admissions.forEach { admission ->
                            ScopeDetails(admission.scope)
                            ReviewField(t("Device", "الجهاز"), admission.deviceId.value)
                            ReviewField(t("Admission revision", "مراجعة القبول"), admission.revision.value.toString())
                            ReviewField(t("Admitted", "مقبول"), if (admission.admitted) t("Yes", "نعم") else t("No", "لا"), code = false)
                            ReviewField(t("Public key identifier", "معرّف المفتاح العام"), admission.publicKeyIdentifier ?: t("Absent", "غير موجود"))
                            ReviewField(t("Public fingerprint", "البصمة العامة"), admission.publicFingerprint ?: t("Absent", "غير موجودة"))
                            ReviewField(t("Declared capability codes", "رموز القدرات المعلنة"), admission.capabilities.sorted().joinToString("\n").ifEmpty { t("None", "لا شيء") })
                        }
                        Notice(t("This employee definition does not pair another device, appoint a master or change the installation's device grants.",
                            "لا يقترن تعريف الموظف بجهاز آخر ولا يعيّن جهازًا رئيسيًا ولا يغيّر صلاحيات أجهزة التثبيت."))
                    }
                    if (selected != null) when (selected.stage) {
                        LocalSetupEmployeeEnrollmentStage.REVIEW -> EmployeeHeadApproval(selected, enabled, controller::approveEmployeeEnrollment)
                        LocalSetupEmployeeEnrollmentStage.APPROVED -> EmployeePasswordSubmission(selected, enabled, controller::enrollApprovedEmployee)
                        LocalSetupEmployeeEnrollmentStage.ACCOUNT_CREATED, LocalSetupEmployeeEnrollmentStage.JOURNAL_UNRESOLVED -> {
                            if (selected.firstJournalEnrollmentAvailable) Button(onClick = { controller.enrollEmployeeIntentJournal(selected.reviewId) },
                                enabled = enabled, modifier = Modifier.fillMaxWidth().touchTarget()) {
                                Text(t("Create this employee's private work journal once", "إنشاء سجل عمل الموظف الخاص مرة واحدة"))
                            }
                            OutlinedButton(onClick = { controller.reopenEmployeeIntentJournal(selected.reviewId) },
                                enabled = enabled, modifier = Modifier.fillMaxWidth().touchTarget()) {
                                Text(t("Reopen this employee's existing journal", "إعادة فتح سجل الموظف الموجود"))
                            }
                            OutlinedButton(onClick = { controller.recoverEmployeeIntentJournalWrites(selected.reviewId) },
                                enabled = enabled, modifier = Modifier.fillMaxWidth().touchTarget()) {
                                Text(t("Recover an interrupted write in this employee's journal", "استعادة كتابة منقطعة في سجل الموظف"))
                            }
                        }
                        LocalSetupEmployeeEnrollmentStage.REJECTED -> OutlinedButton(onClick = { controller.finishEmployeeEnrollment(selected.reviewId) },
                            enabled = enabled, modifier = Modifier.fillMaxWidth().touchTarget()) {
                            Text(t("Acknowledge this rejected original", "الإقرار بهذا الأصل المرفوض"))
                        }
                        LocalSetupEmployeeEnrollmentStage.JOURNAL_OPENED -> Button(onClick = { controller.finishEmployeeEnrollment(selected.reviewId) },
                            enabled = enabled, modifier = Modifier.fillMaxWidth().touchTarget()) {
                            Text(t("Finish enrollment; employee signs in separately", "إكمال التسجيل؛ يسجّل الموظف دخوله منفصلًا"))
                        }
                        else -> Unit
                    }
                    if (selected != null && selected.stage != LocalSetupEmployeeEnrollmentStage.DEFINITION) OutlinedButton(
                        onClick = { controller.inspectEmployeeEnrollment(selected.originalRequestId) }, enabled = enabled,
                        modifier = Modifier.fillMaxWidth().touchTarget()) {
                        Text(t("Inspect / recover only this original", "فحص / استعادة هذا الأصل فقط"))
                    }
                }
            }
        }
    }
}

@Composable
private fun EmployeeHeadApproval(review: LocalSetupEmployeeEnrollmentView, enabled: Boolean,
    approve: (DomainId, CharArray) -> Unit) {
    var secret by remember(review.reviewId) { mutableStateOf("") }
    var consent by remember(review.reviewId) { mutableStateOf(false) }
    DisposableEffect(review.reviewId, enabled) { onDispose { secret = ""; consent = false } }
    SetupSecret(secret, { secret = it; consent = false }, t("Existing company-head password", "كلمة مرور رئيس الشركة الحالية"), enabled)
    Consent(t("I reviewed the complete employee identity, every grant, limit, lifetime and admitted device; approve only this original definition",
        "راجعت هوية الموظف الكاملة وكل صلاحية وحد ومدة وجهاز مقبول؛ اعتماد هذا التعريف الأصلي فقط"), consent, enabled) { consent = it }
    Button(onClick = {
        val owned = secret.toCharArray()
        secret = ""; consent = false
        try { approve(review.reviewId, owned) } finally { owned.fill('\u0000') }
    }, enabled = enabled && consent && secret.length in 12..256, modifier = Modifier.fillMaxWidth().touchTarget()) {
        Text(t("Approve this exact employee definition", "اعتماد تعريف الموظف هذا بعينه"))
    }
}

@Composable
private fun EmployeePasswordSubmission(review: LocalSetupEmployeeEnrollmentView, enabled: Boolean,
    enroll: (DomainId, CharArray) -> Unit) {
    var password by remember(review.reviewId) { mutableStateOf("") }
    var confirmation by remember(review.reviewId) { mutableStateOf("") }
    var consent by remember(review.reviewId) { mutableStateOf(false) }
    DisposableEffect(review.reviewId, enabled) { onDispose { password = ""; confirmation = ""; consent = false } }
    SetupSecret(password, { password = it; consent = false }, t("New employee password", "كلمة مرور الموظف الجديدة"), enabled)
    SetupSecret(confirmation, { confirmation = it; consent = false }, t("Confirm employee password", "تأكيد كلمة مرور الموظف"), enabled)
    Consent(t("Create only the approved original account. An uncertain result will use original-result lookup, without a replacement password or request.",
        "إنشاء الحساب الأصلي المعتمد فقط. إذا كانت النتيجة غير محسومة فسيُفحص الأصل دون كلمة مرور أو طلب بديل."), consent, enabled) { consent = it }
    Button(onClick = {
        val owned = password.toCharArray()
        password = ""; confirmation = ""; consent = false
        try { enroll(review.reviewId, owned) } finally { owned.fill('\u0000') }
    }, enabled = enabled && consent && password.length in 4..256 && password == confirmation,
        modifier = Modifier.fillMaxWidth().touchTarget()) { Text(t("Create the approved original employee account", "إنشاء حساب الموظف الأصلي المعتمد")) }
}

@Composable
private fun employeeStageText(stage: LocalSetupEmployeeEnrollmentStage): String = when (stage) {
    LocalSetupEmployeeEnrollmentStage.DEFINITION -> t("Define the new employee using the retained original IDs.", "عرّف الموظف الجديد باستخدام المعرّفات الأصلية المحفوظة.")
    LocalSetupEmployeeEnrollmentStage.REVIEW -> t("The original complete definition is retained for company-head review.", "التعريف الأصلي الكامل محفوظ لمراجعة رئيس الشركة.")
    LocalSetupEmployeeEnrollmentStage.APPROVAL_UNRESOLVED -> t("Head approval is uncertain. Inspect the same original; do not submit another approval.", "اعتماد الرئيس غير محسوم. افحص الأصل نفسه؛ لا ترسل اعتمادًا آخر.")
    LocalSetupEmployeeEnrollmentStage.APPROVED -> t("The original head approval is verified. Supply the employee's separate password to create that account.", "تم التحقق من اعتماد الرئيس الأصلي. أدخل كلمة مرور الموظف المنفصلة لإنشاء الحساب.")
    LocalSetupEmployeeEnrollmentStage.ENROLLMENT_UNRESOLVED -> t("Account creation is uncertain. Lookup retains the original request and does not repeat the password submission.", "إنشاء الحساب غير محسوم. يحتفظ الفحص بالطلب الأصلي ولا يكرر إرسال كلمة المرور.")
    LocalSetupEmployeeEnrollmentStage.ACCOUNT_CREATED -> t("The original account is created. Open its own private work journal before separate employee sign-in.", "أُنشئ الحساب الأصلي. افتح سجل عمله الخاص قبل تسجيل دخول الموظف منفصلًا.")
    LocalSetupEmployeeEnrollmentStage.JOURNAL_UNRESOLVED -> t("Employee journal acquisition did not complete. Reopen or recover its existing journal; preserve its original files and keys.", "لم يكتمل فتح سجل الموظف. أعد فتح السجل الموجود أو استعد كتابته؛ واحفظ ملفاته ومفاتيحه الأصلية.")
    LocalSetupEmployeeEnrollmentStage.JOURNAL_OPENED -> t("This employee's private journal is open on the connected branch. Enrollment does not sign the employee in.", "سجل الموظف الخاص مفتوح على الفرع المتصل. التسجيل لا يسجّل دخول الموظف.")
    LocalSetupEmployeeEnrollmentStage.ORIGINAL_UNRESOLVED -> t("The exact original could not be verified. Preserve it and inspect again; do not create another account or request.", "تعذر التحقق من الأصل بعينه. احفظه وافحصه مجددًا؛ لا تنشئ حسابًا أو طلبًا آخر.")
    LocalSetupEmployeeEnrollmentStage.REJECTED -> t("The original enrollment was rejected. Keep the original identity while inspecting its retained outcome.", "رُفض التسجيل الأصلي. احتفظ بهويته الأصلية أثناء فحص نتيجته المحفوظة.")
}
