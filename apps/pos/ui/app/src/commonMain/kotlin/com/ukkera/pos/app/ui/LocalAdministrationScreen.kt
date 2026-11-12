package com.ukkera.pos.app.ui

import com.ukkera.pos.app.setup.LocalSetupEmployeePolicyTarget
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ukkera.pos.app.EntryViewModel
import com.ukkera.pos.app.LocalAdministrationBusy
import com.ukkera.pos.app.LocalAdministrationController
import com.ukkera.pos.app.LocalAdministrationPhase
import com.ukkera.pos.app.LocalAdministrationWorkspace
import com.ukkera.pos.app.LocalText
import com.ukkera.pos.app.UkkeraRuntime
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.app.navigation.NavigationToken
import com.ukkera.pos.security.LocalAccountSummary
import com.ukkera.pos.security.LocalAdministrationAction
import com.ukkera.pos.security.LocalAdministrationCommand
import com.ukkera.pos.security.LocalAdministrationDirectory
import com.ukkera.pos.security.LocalAdministrationView
import com.ukkera.pos.security.LocalDeviceAdmission
import com.ukkera.pos.security.LocalSessionRecord
import com.ukkera.pos.security.SessionStatus

/** Real protected administration, deliberately separate from the generic business-command UI. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocalAdministrationScreen(runtime: UkkeraRuntime, area: AppArea, model: EntryViewModel, token: NavigationToken,
    onEmployeePolicyRequest: ((LocalSetupEmployeePolicyTarget) -> Unit)? = null) {
    val runtimePresentation by runtime.presentation.collectAsState()
    val controller = model.localAdministration(runtime, token, runtimePresentation.localAttachment)
    if (controller == null) { LiveUnavailableScreen(area); return }
    val state by controller.state.collectAsState()
    val active = runtime.navigator.isActiveEntry(token)
    val directory = if (area == AppArea.PEOPLE) LocalAdministrationDirectory.ACCOUNTS else LocalAdministrationDirectory.DEVICES
    DisposableEffect(controller, active, directory) {
        if (active) controller.activate(directory)
        onDispose { controller.deactivate() }
    }
    if (!active) return
    if (!controller.displayCurrent(token)) { LiveUnavailableScreen(area); return }
    val view = state.view
    val detail = view?.accountDetail
    val grants = remember(detail) { detail?.policy?.grants.orEmpty() }
    var showGrants by remember(view?.snapshot) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        LazyColumn(Modifier.widthIn(max = 1000.dp).fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                SectionHeading(if (directory == LocalAdministrationDirectory.ACCOUNTS) adminText("Branch employees", "موظفو الفرع")
                    else adminText("Branch devices", "أجهزة الفرع"),
                    adminText("Current protected local records. Company identities and central services are separate.",
                        "السجلات المحلية المحمية الحالية. هويات الشركة والخدمات المركزية منفصلة."))
                Notice(adminText("Device pairing, grant publication and full archived audit history use separate workflows.",
                    "تستخدم إقران الأجهزة ونشر الصلاحيات وسجل التدقيق المؤرشف الكامل مسارات منفصلة."))
            }
            if (directory == LocalAdministrationDirectory.ACCOUNTS && controller.employeeCreationConfigured) item {
                LocalEmployeeCreationInputs(controller, state, token)
                LocalEmployeeCreationRecoveryCard(controller, state, token)
            }
            if (state.working) item {
                Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(when (state.busy) {
                        LocalAdministrationBusy.PREPARING -> adminText("Saving the exact request before confirmation…", "حفظ الطلب الدقيق قبل التأكيد…")
                        LocalAdministrationBusy.SUBMITTING -> adminText("Submitting the original request…", "إرسال الطلب الأصلي…")
                        LocalAdministrationBusy.CANCELLING -> adminText("Resolving or fencing the original request…", "حسم الطلب الأصلي أو منع تنفيذه…")
                        LocalAdministrationBusy.ACKNOWLEDGING -> adminText("Acknowledging only the terminal client record…", "الإقرار بسجل العميل المنتهي فقط…")
                        LocalAdministrationBusy.RECOVERING -> adminText("Recovering the original protected result…", "استعادة النتيجة المحمية الأصلية…")
                        else -> adminText("Reading the current local master…", "قراءة الجهاز الرئيسي المحلي الحالي…")
                    })
                }
            }
            item { AdministrationPending(state, controller, token) }
            if (area == AppArea.PEOPLE && detail != null && onEmployeePolicyRequest != null) item {
                Notice(adminText(
                    "Continue to local setup to author this employee’s complete policy. This locks the employee presentation. The named company head must review and approve; activation is a separate decision.",
                    "انتقل إلى الإعداد المحلي لتعريف سياسة هذا الموظف كاملةً. سيُقفل عرض الموظف. يجب أن يراجع رئيس الشركة المسمّى ويعتمد؛ والتفعيل قرار منفصل."))
                OutlinedButton(onClick = {
                    val target = controller.employeePolicyTarget(view, token) ?: return@OutlinedButton
                    runtime.lock(token) // Retire private presentation before handing over only selection DATA.
                    onEmployeePolicyRequest(target)
                }, enabled = state.canPrepare && !state.passwordEntryOpen,
                    modifier = Modifier.fillMaxWidth().touchTarget()) {
                    Text(adminText("Review this employee’s policy in local setup", "مراجعة سياسة هذا الموظف في الإعداد المحلي"))
                }
            }
            item {
                OutlinedTextField(state.targetInput, { controller.changeTarget(it, token) }, singleLine = true,
                    label = { Text(adminText("Exact employee / device UUID", "معرّف UUID الدقيق للموظف / الجهاز")) },
                    enabled = !state.working && !state.passwordEntryOpen && !state.creationOwnsInput, modifier = Modifier.fillMaxWidth(),
                    isError = state.invalidTarget, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { controller.selectExactTarget(token) }))
                if (state.invalidTarget) Text(adminText("Enter a complete canonical UUID.", "أدخل معرّف UUID قياسيًا كاملًا."),
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Text(adminText("A target-specific grant may allow this lookup without allowing directory enumeration. The master still authorizes the exact target.",
                    "قد تسمح صلاحية لهدف محدد بهذا البحث دون السماح بسرد الدليل. ما زال الجهاز الرئيسي يتحقق من صلاحية الهدف الدقيق."),
                    style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { controller.selectExactTarget(token) },
                        enabled = !state.working && !state.passwordEntryOpen && !state.creationOwnsInput && state.targetInput.isNotEmpty(), modifier = Modifier.touchTarget()) {
                        Text(adminText("Open exact target", "فتح الهدف الدقيق"))
                    }
                    OutlinedButton(onClick = { controller.refresh(token) }, enabled = !state.working && !state.passwordEntryOpen && !state.creationOwnsInput,
                        modifier = Modifier.touchTarget()) { Text(adminText("Refresh selection", "تحديث الاختيار")) }
                    OutlinedButton(onClick = { controller.refresh(token, firstPage = true) }, enabled = !state.working && !state.passwordEntryOpen && !state.creationOwnsInput,
                        modifier = Modifier.touchTarget()) { Text(adminText("Directory: first page", "الدليل: الصفحة الأولى")) }
                    if (view?.next != null) OutlinedButton(onClick = { controller.refresh(token, nextPage = true) },
                        enabled = !state.working && !state.passwordEntryOpen && !state.creationOwnsInput, modifier = Modifier.touchTarget()) {
                        Text(adminText("Next page in this snapshot", "الصفحة التالية من هذه اللقطة"))
                    }
                }
            }
            state.readProblem?.let { problem -> item { Notice(localProblem(problem), caution = true) } }
            if (state.restartRequired) item {
                Notice(adminText("The directory or your authorization changed. This page was discarded. Restart at the first page; different snapshots are never combined.",
                    "تغير الدليل أو تصريح الوصول. أُهملت هذه الصفحة. ابدأ من الصفحة الأولى؛ لا تُدمج لقطات مختلفة."), caution = true)
            }
            if (view != null) {
                item {
                    ScopeDetails(view.scope)
                    ReviewField(adminText("Snapshot identity", "هوية اللقطة"), view.snapshot.value)
                    Text(adminText("One bounded page is shown; advancing replaces this page, not the snapshot.",
                        "تُعرض صفحة محدودة واحدة؛ الانتقال يستبدل الصفحة وليس اللقطة."), style = MaterialTheme.typography.bodySmall)
                }
                if (view.accounts.isEmpty() && view.devices.isEmpty()) item {
                    Notice(adminText("No entries in this authorized page.", "لا توجد سجلات في هذه الصفحة المصرح بها."))
                }
                items(view.accounts, key = { "account/${it.actorId.value}" }) { account ->
                    AdministrationCard {
                        AccountSummary(account)
                        if (state.query.targetId == null) OutlinedButton(
                            onClick = { controller.select(account.actorId, view, token) }, enabled = !state.working,
                            modifier = Modifier.touchTarget()) { Text(adminText("Open employee", "فتح الموظف")) }
                    }
                }
                items(view.devices, key = { "device/${it.deviceId.value}" }) { device ->
                    AdministrationCard {
                        DeviceSummary(device, selected = state.query.targetId != null)
                        if (state.query.targetId == null) OutlinedButton(
                            onClick = { controller.select(device.deviceId, view, token) }, enabled = !state.working,
                            modifier = Modifier.touchTarget()) { Text(adminText("Open device", "فتح الجهاز")) }
                        else if (device.admitted) OutlinedButton(
                            onClick = { controller.prepare(LocalAdministrationAction.REVOKE_DEVICE, view, token) },
                            enabled = state.canPrepare, modifier = Modifier.touchTarget()) {
                            Text(adminText("Prepare device revocation", "تحضير إلغاء قبول الجهاز"))
                        }
                    }
                }
                if (detail != null) {
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { controller.prepare(LocalAdministrationAction.RESET_CREDENTIAL, view, token) },
                                enabled = state.canPrepare && detail.account.enabled, modifier = Modifier.touchTarget()) {
                                Text(if (detail.account.credentialPresent) adminText("Prepare password reset", "تحضير إعادة تعيين كلمة المرور")
                                    else adminText("Prepare credential activation", "تحضير تفعيل بيانات الدخول"))
                            }
                            OutlinedButton(onClick = { controller.prepare(LocalAdministrationAction.ACTIVATE_ACCOUNT, view, token) },
                                enabled = state.canPrepare && !detail.account.enabled && !detail.account.credentialPresent &&
                                    detail.account.actorId != controller.operatorId && detail.account.activeSessionCount == 0,
                                modifier = Modifier.touchTarget()) {
                                Text(adminText("Prepare first credential and account activation", "تحضير بيانات الدخول الأولى وتفعيل الحساب"))
                            }
                            OutlinedButton(onClick = { controller.prepare(LocalAdministrationAction.REACTIVATE_ACCOUNT, view, token) },
                                enabled = state.canPrepare && !detail.account.enabled && detail.account.credentialPresent &&
                                    detail.account.actorId != controller.operatorId && detail.account.activeSessionCount == 0,
                                modifier = Modifier.touchTarget()) {
                                Text(adminText("Prepare account reactivation", "تحضير إعادة تفعيل الحساب"))
                            }
                            OutlinedButton(onClick = { controller.prepare(LocalAdministrationAction.DISABLE_ACCOUNT, view, token) },
                                enabled = state.canPrepare && detail.account.enabled, modifier = Modifier.touchTarget()) {
                                Text(adminText("Prepare account disable", "تحضير تعطيل الحساب"))
                            }
                        }
                        Notice(adminText("Resetting an enabled account's credential also revokes its sessions. Disabling never reassigns drawers, orders, cash or responsibility. Re-enabling is not a password reset.",
                            "تؤدي إعادة تعيين بيانات دخول الحساب المفعّل إلى إلغاء جلساته. التعطيل لا ينقل الأدراج أو الطلبات أو النقد أو المسؤولية. إعادة التفعيل ليست إعادة تعيين كلمة المرور."))
                    }
                    item {
                        SectionHeading(adminText("Activated policy — read only", "السياسة المفعّلة — للقراءة فقط"))
                        ScopeDetails(detail.policy.scope)
                        ReviewField(adminText("Policy employee ID", "معرّف موظف السياسة"), detail.policy.actorId.value)
                        ReviewField(adminText("Activated policy revision", "مراجعة السياسة المفعّلة"), detail.policy.revision.value.toString())
                        ReviewField(adminText("Complete grant count", "عدد الصلاحيات الكامل"), grants.size.toString())
                        PolicyConfigurationDetails(detail.policy, detail.tokenPolicy)
                        Notice(adminText("These are the values observed at the last protected read. Refresh before preparing a policy replacement in local setup, and enter the exact employee ID and policy revision. Preparation and activation recheck the current policy; this view does not approve or activate a replacement.",
                            "هذه هي القيم المرصودة عند آخر قراءة محمية. حدّث العرض قبل تحضير سياسة بديلة في الإعداد المحلي، وأدخل معرّف الموظف ومراجعة السياسة بدقة. يعيد التحضير والتفعيل فحص السياسة الحالية؛ لا يعتمد هذا العرض سياسة بديلة ولا يفعّلها."))
                        AdministrationCheck(adminText("Show every activated grant and its exact scope/limits", "عرض كل صلاحية مفعّلة ونطاقها وحدودها الدقيقة"),
                            showGrants, enabled = !state.working) { showGrants = it }
                        if (showGrants && grants.isEmpty()) Notice(adminText("No activated grants.", "لا توجد صلاحيات مفعّلة."))
                    }
                    if (showGrants) itemsIndexed(grants, key = { index, _ -> "grant/$index" }) { index, grant -> GrantDetails(index, grant) }
                    item {
                        SectionHeading(adminText("Retained sessions", "الجلسات المحتفظ بها"))
                        Notice(adminText("ACTIVE is a retained registry status, not proof of a currently usable token, connection or service lifetime. Older archived sessions are not included in this bounded account view.",
                            "نشطة هي حالة محفوظة في السجل وليست دليلًا على صلاحية رمز وصول أو اتصال أو عمر خدمة حالي. لا يتضمن عرض الحساب المحدود الجلسات المؤرشفة الأقدم."))
                        if (detail.retainedSessions.isEmpty()) Text(adminText("No sessions retained in this account record.", "لا توجد جلسات محفوظة في سجل الحساب هذا."))
                    }
                    items(detail.retainedSessions, key = { "session/${it.sessionId.value}" }) { session ->
                        RetainedSession(session, view, state, controller, token)
                    }
                }
            }
        }
    }
    if (state.passwordEntryOpen && state.canSubmit) state.original?.let { original ->
        AdministrationPassword(original, dismiss = { controller.closePasswordEntry(token) }, submit = { controller.submit(token, it) })
    }
    LocalEmployeeCreationPassword(controller, state, token)
}

@Composable
private fun AccountSummary(account: LocalAccountSummary) {
    SectionHeading(account.login.value)
    ReviewField(adminText("Employee ID", "معرّف الموظف"), account.actorId.value)
    ReviewField(adminText("Account status", "حالة الحساب"), if (account.enabled) adminText("Enabled", "مفعّل") else adminText("Disabled", "معطّل"), code = false)
    ReviewField(adminText("Credential", "بيانات الدخول"), if (account.credentialPresent) adminText("Present — secret never displayed", "موجودة — لا يُعرض السر مطلقًا")
        else adminText("Absent — activation required", "غير موجودة — يلزم التفعيل"), code = false)
    ReviewField(adminText("Account revision", "مراجعة الحساب"), account.accountRevision.value.toString())
    ReviewField(adminText("Credential revision", "مراجعة بيانات الدخول"), account.credentialRevision.value.toString())
    ReviewField(adminText("Policy revision", "مراجعة السياسة"), account.policyRevision.value.toString())
    ReviewField(adminText("Authentication configuration revision", "مراجعة إعداد التحقق"), account.configurationRevision.value.toString())
    ReviewField(adminText("Retained ACTIVE session count (not token validity)", "عدد الجلسات المحفوظة النشطة (ليس صلاحية الرموز)"), account.activeSessionCount.toString())
}

@Composable
private fun DeviceSummary(device: LocalDeviceAdmission, selected: Boolean) {
    SectionHeading(adminText("Device", "الجهاز"))
    ReviewField(adminText("Device ID", "معرّف الجهاز"), device.deviceId.value)
    ReviewField(adminText("Admission revision", "مراجعة القبول"), device.revision.value.toString())
    ReviewField(adminText("Admission status", "حالة القبول"), if (device.admitted) adminText("Admitted", "مقبول") else adminText("Revoked / not admitted", "ملغى / غير مقبول"), code = false)
    if (selected) {
        ReviewField(adminText("Public key identifier", "معرّف المفتاح العام"), device.publicKeyIdentifier ?: adminText("Absent", "غير موجود"))
        ReviewField(adminText("Public fingerprint", "البصمة العامة"), device.publicFingerprint ?: adminText("Absent", "غير موجودة"))
        ReviewField(adminText("Declared capability codes", "رموز القدرات المعلنة"), device.capabilities.sorted().joinToString("\n").ifEmpty { adminText("None", "لا شيء") })
        Notice(adminText("Admission and declared capabilities do not prove a live connection or appoint a replacement master.",
            "القبول والقدرات المعلنة لا تثبت وجود اتصال حي ولا تعيّن جهازًا رئيسيًا بديلًا."))
    }
}

@Composable
private fun RetainedSession(session: LocalSessionRecord, view: LocalAdministrationView, state: LocalAdministrationWorkspace,
    controller: LocalAdministrationController, token: NavigationToken) {
    AdministrationCard {
        ReviewField(adminText("Session ID", "معرّف الجلسة"), session.sessionId.value)
        ReviewField(adminText("Device ID", "معرّف الجهاز"), session.deviceId.value)
        ReviewField(adminText("Original generation", "الجيل الأصلي"), session.generation.value.toString())
        ReviewField(adminText("Authentication purpose code", "رمز غرض التحقق"), session.authenticationPurpose.name)
        Text(if (session.status == SessionStatus.ACTIVE) adminText("Retained ACTIVE — current token validity not asserted", "نشطة في السجل — لا تأكيد لصلاحية الرمز الحالي")
            else adminText("Revoked", "ملغاة"))
        if (session.status == SessionStatus.ACTIVE) OutlinedButton(
            onClick = { controller.prepare(LocalAdministrationAction.REVOKE_SESSION, view, token, session.sessionId) },
            enabled = state.canPrepare, modifier = Modifier.touchTarget()) { Text(adminText("Prepare this session's revocation", "تحضير إلغاء هذه الجلسة")) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdministrationPending(state: LocalAdministrationWorkspace, controller: LocalAdministrationController, token: NavigationToken) {
    val command = state.displayedOriginal
    val pending = state.pending
    var reviewed by remember(command, pending, state.operationProblem) { mutableStateOf(false) }
    AdministrationCard {
        SectionHeading(adminText("Original administrative request", "الطلب الإداري الأصلي"))
        Text(adminText("New actions wait until your prior request has a known outcome and you acknowledge it. Leaving or signing out does not delete the saved request.",
            "تنتظر الإجراءات الجديدة حتى تُعرف نتيجة طلبك السابق وتقر بها. المغادرة أو تسجيل الخروج لا يحذف الطلب المحفوظ."))
        state.pendingProblem?.let { Notice(localProblem(it), caution = true) }
        state.operationProblem?.let { Notice(localProblem(it), caution = true) }
        if (state.originalDetailsRestricted) {
            state.original?.requestId?.let { ReviewField(adminText("Request ID", "معرّف الطلب"), it.value) }
            Notice(adminText("Access to the saved request details was denied. Recover the original request after authorization is restored.",
                "رُفض الوصول إلى تفاصيل الطلب المحفوظ. استعد الطلب الأصلي بعد استعادة التفويض."), caution = true)
        } else if (command == null) {
            if (state.pendingLoaded && state.pendingProblem == null) Text(adminText("No unacknowledged request for this employee on this client.", "لا يوجد طلب غير مقرّ به لهذا الموظف على هذا العميل."))
            else Notice(adminText("Recovery has not established a clear client journal. No new request is enabled.", "لم تثبت الاستعادة خلو سجل العميل. لا يُسمح بطلب جديد."), caution = true)
        } else {
            ReviewField(adminText("Original operator ID", "معرّف المنفّذ الأصلي"), controller.operatorId.value)
            AdministrationRequest(command)
            Text(when (pending?.phase) {
                LocalAdministrationPhase.PREPARED -> adminText("Last observed: prepared locally; not a completed administrative action.", "آخر حالة مرصودة: محضّر محليًا؛ ليس إجراءً إداريًا مكتملًا.")
                LocalAdministrationPhase.DISPATCHED -> adminText("Dispatch was retained. Outcome may be unknown; recover the original request.", "حُفظ الإرسال. قد تكون النتيجة غير معروفة؛ استعد الطلب الأصلي.")
                LocalAdministrationPhase.APPLIED -> adminText("Original action applied. This is historical, not a statement of today's target state.", "طُبق الإجراء الأصلي. هذه نتيجة تاريخية وليست وصفًا لحالة الهدف اليوم.")
                LocalAdministrationPhase.CANCELLED -> adminText("Original ID cancelled without applying its action; that ID is fenced against later execution.", "أُلغي المعرّف الأصلي دون تطبيق الإجراء؛ مُنع تنفيذ ذلك المعرّف لاحقًا.")
                null -> adminText("Preparation/result is unresolved. Keep this exact request identity.", "التحضير أو النتيجة غير محسومين. احتفظ بهوية الطلب الدقيقة هذه.")
            })
            pending?.receipt?.let { receipt ->
                ReviewField(adminText("Original receipt digest", "بصمة الإيصال الأصلي"), receipt.requestDigest.value)
                receipt.resultingAccountRevision?.let { revision -> ReviewField(adminText("Originally resulting account revision", "مراجعة الحساب الناتجة أصلًا"), revision.value.toString()) }
            }
            if (pending?.phase == LocalAdministrationPhase.APPLIED && pending.receipt == null) Notice(adminText(
                "The exact Applied response is retained. A fuller audit receipt has not been recovered; no resulting revision was inferred.",
                "حُفظ رد التطبيق الدقيق. لم يُستعد إيصال التدقيق الكامل؛ لم تُفترض مراجعة ناتجة."))
            if (pending?.cancellationRequested == true && !pending.terminal) Notice(adminText(
                "Cancellation was requested, not proved complete. Only lookup or the same cancellation may continue.",
                "طُلب الإلغاء ولم يُثبت اكتماله. لا يمكن إلا الاستعلام أو متابعة الإلغاء نفسه."), caution = true)
            if (command.targetId == controller.operatorId) Notice(adminText(
                "You selected your own account. Reset/disable or revoking your current session can end this screen before the result arrives. No cash or order ownership is transferred.",
                "اخترت حسابك. قد تنهي إعادة التعيين أو التعطيل أو إلغاء جلستك الحالية هذه الشاشة قبل وصول النتيجة. لا تنتقل ملكية النقد أو الطلبات."), caution = true)
            if (command.action.requiresNewPassword) Notice(adminText(
                "A dispatched password-bearing request is never resent with another password. If no outcome is known, resolve or cancel its original ID before preparing a different request.",
                "لا يُعاد إرسال طلب يتضمن كلمة مرور بكلمة مرور أخرى. إذا كانت النتيجة غير معروفة، احسم المعرّف الأصلي أو ألغِه قبل تحضير طلب مختلف."))
            AdministrationCheck(adminText("I reviewed this exact action, target, revisions and original ID", "راجعت هذا الإجراء والهدف والمراجعات والمعرّف الأصلي بدقة"),
                reviewed, enabled = !state.working) { reviewed = it }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { controller.recover(token) }, enabled = !state.working && !state.passwordEntryOpen && !state.creationOwnsInput,
                modifier = Modifier.touchTarget()) { Text(adminText("Recover / reload saved request", "استعادة / إعادة تحميل الطلب المحفوظ")) }
            if (command != null) {
                if (state.preparationUncertain) OutlinedButton(onClick = { controller.retryPreparation(token) }, enabled = !state.working,
                    modifier = Modifier.touchTarget()) { Text(adminText("Retry exact preparation only", "إعادة التحضير الدقيق فقط")) }
                if (state.canSubmit) Button(onClick = {
                    if (command.action.requiresNewPassword) controller.openPasswordEntry(token) else controller.submit(token)
                }, enabled = reviewed && !state.passwordEntryOpen, modifier = Modifier.touchTarget()) {
                    Text(if (command.action.requiresNewPassword) adminText("Enter new password and confirm", "إدخال كلمة المرور الجديدة والتأكيد")
                        else adminText("Confirm original action", "تأكيد الإجراء الأصلي"))
                }
                if (pending?.terminal != true) OutlinedButton(onClick = { controller.cancel(token) },
                    enabled = reviewed && !state.working && !state.passwordEntryOpen && !state.creationOwnsInput, modifier = Modifier.touchTarget()) {
                    Text(adminText("Cancel original ID — never undo applied work", "إلغاء المعرّف الأصلي — لا يتراجع عن عمل مطبّق"))
                }
                if (pending?.terminal == true) Button(onClick = { controller.acknowledge(token) },
                    enabled = reviewed && !state.working && state.operationProblem == null, modifier = Modifier.touchTarget()) {
                    Text(adminText("Acknowledge this outcome", "الإقرار بهذه النتيجة"))
                }
            }
        }
    }
}

@Composable
private fun AdministrationRequest(command: LocalAdministrationCommand) {
    ReviewField(adminText("Request ID", "معرّف الطلب"), command.requestId.value)
    ReviewField(adminText("Action", "الإجراء"), when (command.action) {
        LocalAdministrationAction.ACTIVATE_ACCOUNT -> adminText("Establish first credential and enable this account; require a new login", "تعيين بيانات الدخول الأولى وتفعيل هذا الحساب؛ يلزم تسجيل دخول جديد")
        LocalAdministrationAction.REACTIVATE_ACCOUNT -> adminText("Reactivate this branch account; require a new login", "إعادة تفعيل حساب الفرع هذا؛ يلزم تسجيل دخول جديد")
        LocalAdministrationAction.DISABLE_ACCOUNT -> adminText("Disable this branch account", "تعطيل حساب الفرع هذا")
        LocalAdministrationAction.REVOKE_SESSION -> adminText("Revoke the selected retained session", "إلغاء الجلسة المحفوظة المختارة")
        LocalAdministrationAction.RESET_CREDENTIAL -> adminText("Establish/reset credential and revoke account sessions", "تعيين بيانات الدخول أو إعادة تعيينها وإلغاء جلسات الحساب")
        LocalAdministrationAction.REVOKE_DEVICE -> adminText("Revoke this device's branch admission", "إلغاء قبول هذا الجهاز في الفرع")
    }, code = false)
    ReviewField(adminText("Exact target ID", "معرّف الهدف الدقيق"), command.targetId.value)
    ReviewField(adminText("Expected account / admission revision", "مراجعة الحساب / القبول المتوقعة"), command.expectedRevision.value.toString())
    command.sessionId?.let { ReviewField(adminText("Exact session ID", "معرّف الجلسة الدقيق"), it.value) }
    command.expectedCredentialRevision?.let { ReviewField(adminText("Expected credential revision", "مراجعة بيانات الدخول المتوقعة"), it.value.toString()) }
    command.activationRevisions?.let { expected ->
        ReviewField(adminText("Expected activated policy revision", "مراجعة السياسة المفعّلة المتوقعة"), expected.policy.value.toString())
        ReviewField(adminText("Expected authentication configuration revision", "مراجعة إعدادات المصادقة المتوقعة"), expected.configuration.value.toString())
        Notice(adminText("This activates only this disabled account with no existing credential. Its grants and revoked sessions are unchanged; the employee must sign in separately. The password is not retained for another dispatch.",
            "يُفعّل هذا الإجراء هذا الحساب المعطّل الذي لا يملك بيانات دخول فقط. لا تتغير صلاحياته أو جلساته الملغاة؛ وعلى الموظف تسجيل الدخول بشكل منفصل. لا تُحفظ كلمة المرور لإرسال آخر."))
    }
    command.reactivationRevisions?.let { expected ->
        ReviewField(adminText("Expected credential revision", "مراجعة بيانات الدخول المتوقعة"), expected.credential.value.toString())
        ReviewField(adminText("Expected activated policy revision", "مراجعة السياسة المفعّلة المتوقعة"), expected.policy.value.toString())
        ReviewField(adminText("Expected authentication configuration revision", "مراجعة إعدادات المصادقة المتوقعة"), expected.configuration.value.toString())
        Notice(adminText("This is a newer explicit resolution. Revoked sessions stay revoked; the employee must sign in again. Existing cash, orders, responsibility and unresolved results are preserved.",
            "هذا قرار صريح أحدث. تبقى الجلسات الملغاة ملغاة؛ وعلى الموظف تسجيل الدخول مجددًا. تُحفظ الأموال والطلبات والمسؤولية والنتائج غير المحسومة الموجودة."))
    }
}

@Composable
private fun AdministrationPassword(command: LocalAdministrationCommand, dismiss: () -> Unit, submit: (CharArray) -> Unit) {
    // Transient Compose strings, not saveable state. JVM/OS input buffers cannot be promised wiped.
    var password by remember(command.requestId) { mutableStateOf("") }
    var confirmation by remember(command.requestId) { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val first = remember(command.requestId) { FocusRequester() }
    val valid = password.length in 4..1024 && password == confirmation
    fun clear() { password = ""; confirmation = "" }
    fun confirm() {
        if (!valid) return
        val input = password.toCharArray()
        clear()
        try { submit(input) } finally { input.fill('\u0000') }
    }
    DisposableEffect(command.requestId) { onDispose { clear() } }
    LaunchedEffect(command.requestId) { first.requestFocus() }
    AlertDialog(onDismissRequest = { clear(); dismiss() }, title = { Text(adminText("Confirm credential reset / activation", "تأكيد إعادة تعيين بيانات الدخول / تفعيلها")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AdministrationRequest(command)
                Text(if (command.action == LocalAdministrationAction.ACTIVATE_ACCOUNT)
                    adminText("The transient password establishes this account's first credential and enables it atomically. No session or permission is created; a fresh employee login is required. A lost reply is recovered by the same original ID, never by entering another password for dispatch.",
                        "تُنشئ كلمة المرور المؤقتة بيانات الدخول الأولى لهذا الحساب وتُفعّله ذريًا. لا تُنشأ جلسة أو صلاحية؛ يلزم تسجيل دخول جديد للموظف. تُستعاد الاستجابة المفقودة بمعرّف الطلب الأصلي نفسه، وليس بإدخال كلمة مرور أخرى لإرسال جديد.")
                else adminText("The new password is transient and not saved for retry. This revokes the account's sessions; it does not enable a disabled account or grant permissions.",
                    "كلمة المرور الجديدة مؤقتة ولا تُحفظ لإعادة المحاولة. يُلغي هذا جلسات الحساب ولا يفعّل حسابًا معطّلًا ولا يمنح صلاحيات."))
                OutlinedTextField(password, { if (it.length <= 1024) password = it }, singleLine = true,
                    label = { Text(adminText("New password (4–1024 characters)", "كلمة المرور الجديدة (4–1024 محرفًا)")) },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().focusRequester(first),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }))
                OutlinedTextField(confirmation, { if (it.length <= 1024) confirmation = it }, singleLine = true,
                    label = { Text(adminText("Repeat new password", "تكرار كلمة المرور الجديدة")) },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { confirm() }))
                if (confirmation.isNotEmpty() && password != confirmation) Text(adminText("Passwords do not match.", "كلمتا المرور غير متطابقتين."),
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }, confirmButton = { Button(onClick = { confirm() }, enabled = valid, modifier = Modifier.touchTarget()) {
            Text(adminText("Confirm this reset once", "تأكيد إعادة التعيين هذه مرة واحدة"))
        } }, dismissButton = { TextButton(onClick = { clear(); dismiss() }, modifier = Modifier.touchTarget()) {
            Text(adminText("Close password entry — keep prepared request", "إغلاق إدخال كلمة المرور — الاحتفاظ بالطلب المحضّر"))
        } })
}

@Composable
private fun AdministrationCard(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun AdministrationCheck(label: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().touchTarget().toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = change),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Text(label, Modifier.weight(1f))
    }
}

@Composable
private fun adminText(english: String, arabic: String): String = LocalAppLanguage.current.text(LocalText(english, arabic))
