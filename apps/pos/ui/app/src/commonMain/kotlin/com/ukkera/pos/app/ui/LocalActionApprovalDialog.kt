package com.ukkera.pos.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ukkera.pos.app.*
import com.ukkera.pos.app.navigation.NavigationToken

@Composable
private fun approvalText(english: String, arabic: String): String =
    LocalAppLanguage.current.text(LocalText(english, arabic))

@Composable
private fun approvalPhaseText(phase: LocalActionApprovalPhase): String = when (phase) {
    LocalActionApprovalPhase.PREVIEW_REQUIRED -> approvalText("Review required", "المراجعة مطلوبة")
    LocalActionApprovalPhase.READY_TO_CONFIRM -> approvalText("Ready for explicit confirmation", "جاهزة للتأكيد الصريح")
    LocalActionApprovalPhase.ISSUE_UNRESOLVED -> approvalText("Issuance unresolved — lookup only", "الإصدار غير محسوم — تحقق فقط")
    LocalActionApprovalPhase.ATTACHED -> approvalText("Issued — separate business submission required", "صادرة — يلزم إرسال العملية التجارية منفصلًا")
    LocalActionApprovalPhase.CONSUMED -> approvalText("Consumed — recover the original business result", "مستخدمة — استرجع نتيجة العملية التجارية الأصلية")
    LocalActionApprovalPhase.REFUSED -> approvalText("Approval refused", "رُفضت الموافقة")
    LocalActionApprovalPhase.RETIRE_UNRESOLVED -> approvalText("Retirement unresolved — lookup only", "الإلغاء النهائي غير محسوم — تحقق فقط")
    LocalActionApprovalPhase.RETIRED -> approvalText("Permanently retired", "ملغاة نهائيًا")
    LocalActionApprovalPhase.RETIRE_RETRY_REQUIRED -> approvalText("Retirement request not accepted — explicit retry only", "لم يُقبل طلب الإلغاء النهائي — تكرار صريح فقط")
}

@Composable
private fun retirementNoAdmissionText(reason: LocalActionApprovalRetirementNoAdmissionReason): String = when (reason) {
    LocalActionApprovalRetirementNoAdmissionReason.QUEUE_FULL -> approvalText("Master endpoint queue full", "طابور الطلبات بالجهاز الرئيسي ممتلئ")
    LocalActionApprovalRetirementNoAdmissionReason.ENDPOINT_UNAVAILABLE -> approvalText("Master endpoint unavailable", "واجهة الجهاز الرئيسي غير متاحة")
}

/** Separate local authentication, never a manager sign-in or a replacement cashier session. */
@Composable
internal fun LocalActionApprovalDialog(presentation: LocalActionApprovalPresentation, working: Boolean,
    controller: LocalWorkflowController, token: NavigationToken) {
    if (!controller.actionApprovalDisplayCurrent(token)) return
    val approval = presentation.approval
    val review = approval?.review
    val lookupEnabled = !working || controller.canLookupActionApprovalDuringRetirement(token)
    if (presentation.passwordEntry && presentation.confirmed && review != null) {
        ActionApproverPassword(presentation, controller, token)
        return
    }
    AlertDialog(onDismissRequest = { if (!working) controller.closeActionApproval(token) },
        title = { Text(approvalText("Approval for the saved original", "موافقة على العملية الأصلية المحفوظة")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                IdentifierText(copy(Copy.OPERATION_ID), presentation.operationId.value)
                Text(approvalText(
                    "Approval does not submit the operation. The requester and drawer remain unchanged; the Master checks current permissions again at commit.",
                    "الموافقة لا ترسل العملية. يبقى مقدم الطلب والدرج دون تغيير؛ يتحقق الجهاز الرئيسي مجددًا من الصلاحيات الحالية عند الحفظ."))
                if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
                presentation.problem?.let { Notice(localProblem(it), caution = true) }
                approval?.let {
                    Text(approvalText("Status", "الحالة") + ": " + approvalPhaseText(it.phase))
                    it.approvalId?.let { id -> IdentifierText(approvalText("Original approval ID", "معرف الموافقة الأصلية"), id.value) }
                    it.approverId?.let { id -> IdentifierText(approvalText("Actual approver", "الموافق الفعلي"), id.value) }
                    it.issuedAt?.let { time -> IdentifierText(approvalText("Issuing boot / elapsed milliseconds", "إقلاع الإصدار / الملّي ثانية المنقضية"),
                        "${time.bootId.value} / ${time.elapsedMillis}") }
                    it.expiresAt?.let { time -> IdentifierText(approvalText("Expiry boot / elapsed milliseconds", "إقلاع الانتهاء / الملّي ثانية المنقضية"),
                        "${time.bootId.value} / ${time.elapsedMillis}") }
                    it.retirementAttempt?.let { attempt ->
                        IdentifierText(approvalText("Retirement invocation / sequence", "طلب الإلغاء النهائي / الرقم المتسلسل"),
                            "${attempt.attemptId.value} / ${attempt.sequence}")
                        if (it.phase == LocalActionApprovalPhase.RETIRE_RETRY_REQUIRED) {
                            attempt.noAdmissionReason?.let { reason -> Text(retirementNoAdmissionText(reason)) }
                            Notice(approvalText(
                                "This retirement request was not accepted by the Master endpoint and cannot run later. This does not establish approval usability, permanent retirement or business absence. Only status lookup or a separate explicit retirement retry is available; it does not allow business submission or replacement.",
                                "لم تقبل واجهة الجهاز الرئيسي طلب الإلغاء النهائي هذا ولا يمكن تنفيذه لاحقًا. ولا يثبت ذلك صلاحية الموافقة أو إلغاءها نهائيًا أو عدم حدوث العملية التجارية. المتاح فقط هو التحقق من الحالة أو طلب إلغاء نهائي جديد وصريح؛ ولا يسمح ذلك بإرسال العملية التجارية أو الاستبدال."), caution = true)
                            if (attempt.sequence == Long.MAX_VALUE) Notice(approvalText(
                                "No further retirement invocation can be allocated. Status lookup remains available.",
                                "لا يمكن تخصيص طلب إلغاء نهائي آخر. يظل التحقق من الحالة متاحًا."), caution = true)
                        }
                    }
                    it.retirement?.let { retired ->
                        IdentifierText(approvalText("Master retirement ID", "معرف الإلغاء الدائم بالجهاز الرئيسي"), retired.retirementId.value)
                        IdentifierText(approvalText("Retired original digest", "بصمة الموافقة الأصلية الملغاة"), retired.originalApprovalDigest.value)
                        Text(approvalText("This original approval is permanently retired. Replacement requires a new explicit review and authentication; it does not submit the business operation.",
                            "أُلغيت هذه الموافقة الأصلية نهائيًا. يتطلب الاستبدال مراجعة ومصادقة جديدتين وصريحتين؛ ولا يرسل العملية التجارية."))
                    }
                    it.retiredApprovals.forEach { previous ->
                        IdentifierText(approvalText("Previous retired approval / actual approver", "الموافقة الملغاة السابقة / الموافق الفعلي"),
                            "${previous.approvalId.value} / ${previous.approverId.value}")
                        IdentifierText(approvalText("Previous retirement ID", "معرف الإلغاء السابق"), previous.retirement.retirementId.value)
                        previous.retirementAttempt?.let { attempt -> IdentifierText(
                            approvalText("Previous retirement invocation / sequence", "طلب الإلغاء النهائي السابق / الرقم المتسلسل"),
                            "${attempt.attemptId.value} / ${attempt.sequence}") }
                    }
                    if (it.phase == LocalActionApprovalPhase.RETIRED && it.retiredApprovals.size >= 16)
                        Notice(approvalText(
                            "The 16 previous-approval limit has been reached. This retirement and all previous approvals remain saved; another replacement review is unavailable.",
                            "تم الوصول إلى حد ١٦ موافقة سابقة. يظل هذا الإلغاء وجميع الموافقات السابقة محفوظة؛ ولا تتوفر مراجعة استبدال أخرى."), caution = true)
                    it.refusal?.let { reason -> Text(approvalText("Approval refused", "رُفضت الموافقة") + ": " + reason.name) }
                }
                review?.let {
                    IdentifierText(approvalText("Original requester / policy revision", "مقدم الطلب الأصلي / مراجعة السياسة"),
                        "${it.requestorId.value} / ${it.requestorPolicyRevision.value}")
                    IdentifierText(approvalText("Exact action", "الإجراء الدقيق"), it.requirement.permission.action.name)
                    it.requirement.binding?.let { binding ->
                        IdentifierText(approvalText("Target / expected revision", "الهدف / المراجعة المتوقعة"),
                            "${binding.documentId.value} / ${binding.revision.value}")
                        IdentifierText(approvalText("Original content digest", "بصمة المحتوى الأصلي"), binding.payloadDigest.value)
                        IdentifierText(approvalText("Reason code", "رمز السبب"), binding.reasonCode)
                    }
                    IdentifierText(approvalText("Server confirmation digest", "بصمة التأكيد من الجهاز الرئيسي"), it.confirmationDigest.value)
                    ArrivalApprovalRule(it.rule)
                    RecordValues(it.summary)
                    if (presentation.canConfirm) ConfirmRow(approvalText(
                        "I reviewed this exact requester, action, target, rule and stored content.",
                        "راجعت مقدم الطلب والإجراء والهدف والقاعدة والمحتوى المحفوظ المحددين."),
                        presentation.confirmed, !working) { checked -> controller.confirmActionApproval(checked, token) }
                }
                if (presentation.lookupRequired) Notice(approvalText(
                    "The issuance or retirement outcome is unknown. Look up this original approval; do not authenticate again, repeat retirement or create another issuance.",
                    "نتيجة إصدار الموافقة أو إلغائها غير معروفة. تحقق من الموافقة الأصلية؛ لا تعِد المصادقة أو الإلغاء ولا تنشئ إصدارًا آخر."), caution = true)
            }
        },
        confirmButton = {
            if (presentation.confirmed) TextButton(onClick = { controller.openActionApproverPassword(token) }, enabled = !working,
                modifier = Modifier.touchTarget()) { Text(approvalText("Approver authentication", "مصادقة الموافق")) }
            else if (!presentation.lookupRequired && approval?.phase == LocalActionApprovalPhase.ATTACHED)
                Column {
                    TextButton(onClick = { controller.retireActionApproval(token) }, enabled = !working,
                        modifier = Modifier.touchTarget()) { Text(approvalText("Permanently retire this approval", "إلغاء هذه الموافقة نهائيًا")) }
                    TextButton(onClick = { controller.lookupActionApproval(presentation.operationId, token) }, enabled = lookupEnabled,
                        modifier = Modifier.touchTarget()) { Text(approvalText("Look up original approval", "التحقق من الموافقة الأصلية")) }
                }
            else if (!presentation.lookupRequired && approval?.phase == LocalActionApprovalPhase.RETIRE_RETRY_REQUIRED)
                Column {
                    TextButton(onClick = { controller.retryActionApprovalRetirement(token) },
                        enabled = !working && presentation.canRetryRetirement,
                        modifier = Modifier.touchTarget()) { Text(approvalText("Retry retirement", "تكرار طلب الإلغاء النهائي")) }
                    TextButton(onClick = { controller.lookupActionApproval(presentation.operationId, token) }, enabled = lookupEnabled,
                        modifier = Modifier.touchTarget()) { Text(approvalText("Look up original approval", "التحقق من الموافقة الأصلية")) }
                }
            else if (!presentation.lookupRequired && approval?.phase == LocalActionApprovalPhase.RETIRED)
                TextButton(onClick = { controller.beginActionApprovalReplacement(token) }, enabled = !working,
                    modifier = Modifier.touchTarget()) { Text(approvalText("Start a separate replacement review", "بدء مراجعة استبدال منفصلة")) }
            else if (!presentation.lookupRequired && (approval == null || approval.phase == LocalActionApprovalPhase.REFUSED ||
                    approval.phase == LocalActionApprovalPhase.PREVIEW_REQUIRED ||
                    approval.phase == LocalActionApprovalPhase.READY_TO_CONFIRM && presentation.problem != null))
                TextButton(onClick = { controller.refreshActionApproval(token) }, enabled = !working,
                    modifier = Modifier.touchTarget()) { Text(approvalText("Refresh approval review", "تحديث مراجعة الموافقة")) }
            else if (approval != null || presentation.lookupRequired) TextButton(
                onClick = { controller.lookupActionApproval(presentation.operationId, token) }, enabled = lookupEnabled,
                modifier = Modifier.touchTarget()) { Text(approvalText("Look up original approval", "التحقق من الموافقة الأصلية")) }
        },
        dismissButton = { TextButton(onClick = { controller.closeActionApproval(token) }, enabled = !working,
            modifier = Modifier.touchTarget()) { Text(copy(Copy.CLOSE)) } })
}

@Composable
private fun ArrivalApprovalRule(rule: LocalArrivalRuleOptionV2) {
    IdentifierText(approvalText("Selected rule", "القاعدة المختارة"), rule.ruleId.value)
    rule.ruleDetails?.let { details -> Text(details) }
    val route = rule.approvalRoute ?: return
    Text(approvalText("Approval mode", "نمط الموافقة") + ": " + route.mode.name)
    if (route.mode == LocalArrivalApprovalModeV2.NAMED_SELF) Text(approvalText(
        "Requester/approver equality is allowed only for the listed employees. A distinct eligible approver is still allowed.",
        "يُسمح بتطابق مقدم الطلب والموافق فقط للموظفين المذكورين. ويظل الموافق المؤهل المختلف مسموحًا."))
    fun selector(value: LocalArrivalActorSelectionV2): String = when (value.mode) {
        LocalArrivalActorSelectionModeV2.CURRENT_BRANCH_GRANTEES -> "CURRENT_BRANCH_GRANTEES"
        LocalArrivalActorSelectionModeV2.NAMED_EMPLOYEES -> value.employeeIds.joinToString { it.value }
    }
    route.performers?.let { IdentifierText(approvalText("Performers", "المنفذون"), selector(it)) }
    route.requesters?.let { IdentifierText(approvalText("Requesters", "مقدمو الطلبات"), selector(it)) }
    route.approvers?.let { IdentifierText(approvalText("Approvers", "الموافقون"), selector(it)) }
    if (route.namedEmployeeIds.isNotEmpty()) IdentifierText(approvalText("Named equality exception", "استثناء التطابق المحدد بالاسم"),
        route.namedEmployeeIds.joinToString { it.value })
    Text(approvalText("Document count", "عدد المستندات") + ": ${rule.minimumDocuments}–${rule.maximumDocuments}")
    Text(approvalText("Allowed media / purposes", "الوسائط / الأغراض المسموحة") +
        ": ${rule.media.joinToString { it.name }} / ${rule.purposes.joinToString { it.name }}")
    IdentifierText(approvalText("Retention class / revision", "فئة الاحتفاظ / المراجعة"), rule.retention.choiceCode)
}

@Composable
private fun ActionApproverPassword(presentation: LocalActionApprovalPresentation, controller: LocalWorkflowController,
    token: NavigationToken) {
    // Transient, not saveable. OS/IME and immutable String buffers cannot be promised wiped.
    var login by remember(presentation.operationId) { mutableStateOf("") }
    var password by remember(presentation.operationId) { mutableStateOf("") }
    fun clear() { login = ""; password = "" }
    DisposableEffect(presentation.operationId) { onDispose { clear() } }
    AlertDialog(onDismissRequest = { clear(); controller.closeActionApproverPassword(token) },
        title = { Text(approvalText("Approver's own local account", "الحساب المحلي الخاص بالموافق")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(approvalText("No session or drawer is switched. Credentials are used only for this exact approval.",
                    "لا تتغير الجلسة أو الدرج. تُستخدم بيانات الدخول فقط لهذه الموافقة المحددة."))
                OutlinedTextField(login, { if (it.length <= 128) login = it }, singleLine = true,
                    label = { Text(approvalText("Exact local login", "اسم الدخول المحلي الدقيق")) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { if (it.length <= 1024) password = it }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    label = { Text(approvalText("Password", "كلمة المرور")) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = {
            val exactLogin = login
            val input = password.toCharArray()
            clear()
            try { controller.issueActionApproval(exactLogin, input, token) } finally { input.fill('\u0000') }
        }, enabled = login.isNotEmpty() && password.length in 4..1024, modifier = Modifier.touchTarget()) {
            Text(approvalText("Issue this approval", "إصدار هذه الموافقة"))
        } },
        dismissButton = { TextButton(onClick = { clear(); controller.closeActionApproverPassword(token) }, modifier = Modifier.touchTarget()) {
            Text(copy(Copy.CLOSE))
        } })
}
