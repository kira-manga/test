package com.ukkera.pos.app.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ukkera.pos.app.ui.Notice
import com.ukkera.pos.app.ui.GrantDetails
import com.ukkera.pos.app.ui.ReviewField
import com.ukkera.pos.app.ui.SectionHeading
import com.ukkera.pos.app.ui.touchTarget
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.LocalAccountProvisioning
import com.ukkera.pos.security.LocalApprovalPolicy
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.LocalSessionPolicy
import com.ukkera.pos.security.LocalTokenPolicy
import com.ukkera.pos.security.NoMonetaryCeiling
import com.ukkera.pos.security.OwnerAccess
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionGrant
import com.ukkera.pos.security.PermissionLimits
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.RequiredLimits
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.ThrottlePolicy
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision

/** Unsaved, explicit user selections. No automatic grants, account seed, token or native authority. */
@Composable
internal fun LocalSetupFirstAccountInputs(native: LocalSetupNativeView, headLogin: String, enabled: Boolean,
    prepare: (LocalSetupFirstAccountInput) -> Unit) {
    val scope = remember(native.installationId) { BranchScope(native.organizationId, native.companyId, native.branchId) }
    var epoch by remember(native.installationId) { mutableStateOf("0") }
    var invalidEpoch by remember(native.installationId) { mutableStateOf(false) }
    SectionHeading(t("Define the first LOCAL employee", "تعريف الموظف المحلي الأول"))
    Notice(t("No administrator permissions are preselected. This account belongs only to this branch. Company-head approval is separate; no central services or automatic employee login are started.",
        "لا توجد صلاحيات مدير محددة مسبقًا. الحساب تابع لهذا الفرع فقط. اعتماد رئيس الشركة منفصل؛ لن تبدأ خدمات مركزية أو جلسة موظف تلقائيًا."))
    ReviewField(t("Original company head login", "معرّف رئيس الشركة الأصلي"), headLogin)
    ReviewField(t("First employee ID", "معرّف الموظف الأول"), native.firstActorId.value)
    ReviewField(t("Only initially admitted device", "الجهاز الوحيد المقبول مبدئيًا"), native.deviceId.value)
    SetupInput(epoch, { epoch = it; invalidEpoch = false }, t("Original authority epoch (not replacement authority)", "حقبة السلطة الأصلية (ليست تصريح استبدال)"), enabled, 19)
    if (invalidEpoch) Notice(t("Enter the original nonnegative authority epoch.", "أدخل حقبة السلطة الأصلية غير السالبة."), caution = true)
    LocalSetupAccountDefinitionInputs(scope, native.firstActorId, setOf(native.deviceId), enabled) { account ->
        val authorityEpoch = try { Revision(epoch.toLong()) } catch (_: Exception) { invalidEpoch = true; null }
        if (authorityEpoch != null) prepare(LocalSetupFirstAccountInput(account, authorityEpoch))
    }
}

/** Shared complete employee definition editor. Selection data never grants native authority. */
@Composable
internal fun LocalSetupAccountDefinitionInputs(scope: BranchScope, actorId: DomainId,
    admittedDeviceIds: Set<DomainId>, enabled: Boolean, prepare: (LocalAccountProvisioning) -> Unit) {
    var login by remember(scope, actorId) { mutableStateOf("") }
    var sessions by remember(scope, actorId) { mutableStateOf("1") }
    var accessMillis by remember(scope, actorId) { mutableStateOf("") }
    var workMillis by remember(scope, actorId) { mutableStateOf("") }
    var freeFailures by remember(scope, actorId) { mutableStateOf("3") }
    var initialDelay by remember(scope, actorId) { mutableStateOf("1000") }
    var maximumDelay by remember(scope, actorId) { mutableStateOf("900000") }
    var attemptTimeout by remember(scope, actorId) { mutableStateOf("120000") }
    var approvalEnabled by remember(scope, actorId) { mutableStateOf(false) }
    var approvalMillis by remember(scope, actorId) { mutableStateOf("") }
    var selfApprovals by remember(scope, actorId) { mutableStateOf("") }
    var grants by remember(scope, actorId) { mutableStateOf<List<PermissionGrant>>(emptyList()) }
    var consent by remember(scope, actorId) { mutableStateOf(false) }
    var invalid by remember(scope, actorId) { mutableStateOf(false) }

    SetupInput(login, { login = it; consent = false }, t("Exact employee login", "معرّف دخول الموظف الدقيق"), enabled, 128)

    SectionHeading(t("Explicit permission grants", "الصلاحيات الصريحة"))
    Notice(t("Each grant must stand on its own. Limits are not combined across grants. Central-human and device/service-only actions cannot be granted to a local employee here.",
        "يجب أن تكون كل صلاحية مكتملة بذاتها. لا تُجمع الحدود بين الصلاحيات. لا يمكن منح إجراءات الهوية المركزية أو الجهاز والخدمة للموظف المحلي هنا."))
    LocalSetupPermissionSets(scope, actorId, grants, enabled) {
        grants = grants + it.filter { addition -> grants.none { old -> old.permission == addition.permission } }
        consent = false
    }
    grants.forEachIndexed { index, grant ->
        GrantDetails(index, grant)
        OutlinedButton(onClick = { grants = grants.filterIndexed { i, _ -> i != index }; consent = false },
            enabled = enabled, modifier = Modifier.touchTarget()) {
            Text(t("Remove grant", "إزالة الصلاحية") + " ${index + 1}")
        }
    }
    LocalSetupGrantInput(scope, actorId, enabled && grants.size < 1024) {
        grants = grants + it; consent = false
    }
    if (grants.isEmpty()) Notice(t("No grants selected: this employee will not have business-area access. Add the intended grants before review, or explicitly approve a no-access account.",
        "لم تُحدَّد صلاحيات: لن يتمكن الموظف من دخول أقسام الأعمال. أضف الصلاحيات المطلوبة قبل المراجعة أو اعتمد صراحة حسابًا بلا وصول."), caution = true)

    SectionHeading(t("Local session and authentication policy", "سياسة الجلسة والتحقق المحلي"))
    SetupInput(sessions, { sessions = it; consent = false }, t("Maximum concurrent sessions (1–16)", "أقصى جلسات متزامنة (1–16)"), enabled, 2)
    SetupInput(accessMillis, { accessMillis = it; consent = false }, t("Access lifetime in milliseconds", "مدة الوصول بالمللي ثانية"), enabled, 19)
    SetupInput(workMillis, { workMillis = it; consent = false }, t("Work lifetime in milliseconds (at least access lifetime)", "مدة العمل بالمللي ثانية (لا تقل عن مدة الوصول)"), enabled, 19)
    SetupInput(freeFailures, { freeFailures = it; consent = false }, t("Free failed attempts (0–5)", "المحاولات الفاشلة قبل التأخير (0–5)"), enabled, 1)
    SetupInput(initialDelay, { initialDelay = it; consent = false }, t("Initial delay in milliseconds", "التأخير الأولي بالمللي ثانية"), enabled, 19)
    SetupInput(maximumDelay, { maximumDelay = it; consent = false }, t("Maximum delay in milliseconds", "أقصى تأخير بالمللي ثانية"), enabled, 19)
    SetupInput(attemptTimeout, { attemptTimeout = it; consent = false }, t("Attempt timeout in milliseconds", "مهلة المحاولة بالمللي ثانية"), enabled, 19)
    Consent(t("Include an explicit manager-approval policy", "تضمين سياسة صريحة لموافقة المدير"), approvalEnabled, enabled) {
        approvalEnabled = it; consent = false
    }
    if (approvalEnabled) {
        SetupInput(approvalMillis, { approvalMillis = it; consent = false }, t("Approval lifetime in milliseconds", "مدة الموافقة بالمللي ثانية"), enabled, 19)
        SetupLines(selfApprovals, { selfApprovals = it; consent = false }, t("Named self-approval action codes, one per line; empty means none", "رموز إجراءات الموافقة الذاتية، واحد في كل سطر؛ فارغ يعني لا شيء"), enabled, 8192)
    }
    if (invalid) Notice(t("Check the complete login, durations, throttle and approval fields. The original native owner also checks every field before retaining anything.",
        "تحقق من المعرف والمدد وحدود التأخير وحقول الموافقة كاملة. يفحص المالك الأصلي كل حقل أيضًا قبل حفظ أي شيء."), caution = true)
    Consent(t("Review exactly these selections, with no hidden grants or additional admitted devices", "مراجعة هذه الاختيارات بدقة دون صلاحيات خفية أو أجهزة إضافية مقبولة"), consent, enabled) { consent = it }
    Button(onClick = {
        val input = try {
            val approval = if (approvalEnabled) LocalApprovalPolicy(approvalMillis.toLong(),
                actionCodes(selfApprovals)) else null
            val policy = ActivatedLocalPolicy(scope, actorId, Revision(0), grants,
                LocalSessionPolicy(sessions.toInt()), ThrottlePolicy(freeFailures.toInt(), initialDelay.toLong(),
                    maximumDelay.toLong(), attemptTimeout.toLong()), approval)
            LocalAccountProvisioning(actorId, LocalLoginIdentifier(login), policy,
                LocalTokenPolicy(accessMillis.toLong(), workMillis.toLong()), admittedDeviceIds)
        } catch (_: Exception) { invalid = true; null }
        if (input != null) { invalid = false; consent = false; prepare(input) }
    }, enabled = enabled && consent, modifier = Modifier.fillMaxWidth().touchTarget()) {
        Text(t("Review the COMPLETE account definition", "مراجعة تعريف الحساب الكامل"))
    }
}

@Composable
internal fun LocalSetupGrantInput(scope: BranchScope, actor: DomainId, enabled: Boolean, add: (PermissionGrant) -> Unit) {
    var action by remember { mutableStateOf(SecurityAction.ORDER_CREATE) }
    var verb by remember { mutableStateOf(PermissionVerb.PERFORM) }
    var effect by remember { mutableStateOf(GrantEffect.ALLOW) }
    var ownerAccess by remember { mutableStateOf(OwnerAccess.OWN_ONLY) }
    var companyScope by remember { mutableStateOf(false) }
    var named by remember { mutableStateOf(true) }
    var targets by remember { mutableStateOf("") }
    var limits by remember { mutableStateOf(SetupLimitsDraft()) }
    var hasCeiling by remember { mutableStateOf(false) }
    var ceiling by remember { mutableStateOf(SetupLimitsDraft()) }
    var invalid by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeading(t("Add one complete grant", "إضافة صلاحية كاملة واحدة"))
        SetupEnumPicker(t("Action code", "رمز الإجراء"), action, firstAccountActions, enabled) { action = it }
        SetupEnumPicker(t("Verb", "نوع الإذن"), verb, PermissionVerb.entries, enabled) { verb = it }
        SetupEnumPicker(t("Effect", "الأثر"), effect, GrantEffect.entries, enabled) { effect = it }
        SetupEnumPicker(t("Owner access", "وصول المالك"), ownerAccess, OwnerAccess.entries, enabled) { ownerAccess = it }
        Consent(t("Company scope instead of only this branch (account remains branch-local)", "نطاق الشركة بدل هذا الفرع فقط (يبقى الحساب محليًا للفرع)"), companyScope, enabled) { companyScope = it }
        Consent(t("Explicitly name only this employee", "تسمية هذا الموظف وحده صراحة"), named, enabled) { named = it }
        SetupLines(targets, { targets = it }, t("Exact target UUIDs, one per line; empty means no target restriction", "معرّفات الأهداف الدقيقة، واحد في كل سطر؛ فارغ يعني عدم تقييد الأهداف"), enabled, 9472)
        LimitsInputs(limits, enabled) { limits = it }
        Consent(t("Also impose a delegation ceiling", "فرض حد تفويض أيضًا"), hasCeiling, enabled) { hasCeiling = it }
        if (hasCeiling) LimitsInputs(ceiling, enabled) { ceiling = it }
        if (invalid) Notice(t("Check the grant. An allowed monetary action needs an explicit amount or no-ceiling declaration; proportional actions also need a percentage ceiling. Target IDs must be distinct canonical UUIDs.",
            "تحقق من الصلاحية. يحتاج الإجراء النقدي المسموح مبلغًا صريحًا أو إعلان عدم وجود حد؛ وتحتاج الإجراءات النسبية حد نسبة أيضًا. يجب أن تكون معرّفات الأهداف UUID معيارية ومختلفة."), caution = true)
        OutlinedButton(onClick = {
            val grant = try {
                val completeLimits = limits.value()
                val completeCeiling = if (hasCeiling) ceiling.value() else null
                if (effect == GrantEffect.ALLOW) {
                    requireUsefulLimits(action, completeLimits)
                    completeCeiling?.let { requireUsefulLimits(action, it) }
                }
                PermissionGrant(Permission(action, verb), if (companyScope) scope.company() else scope, effect,
                    completeLimits, ownerAccess, if (named) actor else null, targetIds(targets), completeCeiling)
            } catch (_: Exception) { invalid = true; null }
            if (grant != null) { invalid = false; add(grant) }
        }, enabled = enabled, modifier = Modifier.fillMaxWidth().touchTarget()) {
            Text(t("Add this grant to the review", "إضافة هذه الصلاحية إلى المراجعة"))
        }
    }
}

private data class SetupLimitsDraft(val amount: String = "", val percentage: String = "", val unlimited: Boolean = false) {
    fun value(): PermissionLimits {
        require(!unlimited || amount.isEmpty())
        return PermissionLimits(amount.takeIf { it.isNotEmpty() }?.let { Money(Currency.EGP, it.toLong()) },
            percentage.takeIf { it.isNotEmpty() }?.toInt(), if (unlimited) NoMonetaryCeiling(Currency.EGP) else null)
    }
}

@Composable
private fun LimitsInputs(value: SetupLimitsDraft, enabled: Boolean, change: (SetupLimitsDraft) -> Unit) {
    SetupInput(value.amount, { change(value.copy(amount = it)) }, t("Maximum EGP amount in piastres (integer)", "أقصى مبلغ بالجنيه بوحدة القرش (عدد صحيح)"), enabled, 19)
    SetupInput(value.percentage, { change(value.copy(percentage = it)) }, t("Maximum percentage in basis points (0–10000)", "أقصى نسبة بنقاط الأساس (0–10000)"), enabled, 5)
    Consent(t("Explicitly no monetary ceiling, in EGP only (amount must be empty)", "عدم وجود حد نقدي صراحة بالجنيه فقط (يجب ترك المبلغ فارغًا)"), value.unlimited, enabled) {
        change(value.copy(unlimited = it))
    }
}

private fun requireUsefulLimits(action: SecurityAction, limits: PermissionLimits) {
    if (action.requiredLimits != RequiredLimits.NONE)
        require(limits.maximumAmount != null || limits.noMonetaryCeiling != null)
    if (action.requiredLimits == RequiredLimits.AMOUNT_AND_PERCENTAGE) require(limits.maximumPercentageBasisPoints != null)
}

private fun targetIds(text: String): Set<DomainId>? {
    if (text.isEmpty()) return null
    val lines = text.split('\n')
    require(lines.size in 1..256 && lines.distinct().size == lines.size)
    return lines.map { require(firstAccountUuid.matches(it)); DomainId(it) }.toSet()
}

internal fun actionCodes(text: String): Set<SecurityAction> {
    if (text.isEmpty()) return emptySet()
    val actions = text.split('\n').map(SecurityAction::valueOf)
    require(actions.distinct().size == actions.size && actions.all { it in firstAccountActions })
    return actions.toSet()
}

@Composable
internal fun SetupLines(value: String, change: (String) -> Unit, label: String, enabled: Boolean, maximum: Int) {
    OutlinedTextField(value, { if (it.length <= maximum) change(it) }, label = { Text(label) },
        enabled = enabled, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun <T : Enum<T>> SetupEnumPicker(label: String, selected: T, options: List<T>, enabled: Boolean, select: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().touchTarget()) {
            Text("$label: ${selected.name}")
        }
        DropdownMenu(expanded && enabled, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            options.forEach { option -> DropdownMenuItem(text = { Text(option.name) },
                onClick = { select(option); expanded = false }) }
        }
    }
}

/** Secret strings exist only in this visible Compose form, never saved state or the review DTO. */
@Composable
internal fun FirstAccountSubmission(review: LocalSetupDefinitionReview, enabled: Boolean,
    submit: (DomainId, CharArray, CharArray) -> Unit) {
    var head by remember(review.reviewId) { mutableStateOf("") }
    var password by remember(review.reviewId) { mutableStateOf("") }
    var confirmation by remember(review.reviewId) { mutableStateOf("") }
    var consent by remember(review.reviewId) { mutableStateOf(false) }
    DisposableEffect(review.reviewId, enabled) { onDispose { head = ""; password = ""; confirmation = ""; consent = false } }
    SectionHeading(t("Approve and install this exact definition", "اعتماد هذا التعريف بعينه وتثبيته"))
    Notice(t("Submission durably retains this original definition before approval/SQL. It cannot be edited into a different request after submission. Unknown results require inspection of the same original, not a new account or request ID.",
        "يحفظ الإرسال هذا التعريف الأصلي دائمًا قبل الاعتماد وقاعدة البيانات. لا يمكن تغييره إلى طلب مختلف بعد الإرسال. تتطلب النتائج غير المحسومة فحص الأصل نفسه، لا حسابًا أو معرّف طلب جديدًا."), caution = true)
    SetupSecret(head, { head = it; consent = false }, t("Existing company-head password", "كلمة مرور رئيس الشركة الحالية"), enabled)
    SetupSecret(password, { password = it; consent = false }, t("New first-employee password", "كلمة مرور جديدة للموظف الأول"), enabled)
    SetupSecret(confirmation, { confirmation = it; consent = false }, t("Confirm employee password", "تأكيد كلمة مرور الموظف"), enabled)
    Consent(t("I reviewed ALL grants, limits, durations, identities and device admission above; install only this complete definition",
        "راجعت جميع الصلاحيات والحدود والمدد والهويات وقبول الجهاز أعلاه؛ ثبّت هذا التعريف الكامل وحده"), consent, enabled) { consent = it }
    Button(onClick = {
        val ownedHead = head.toCharArray()
        var ownedPassword: CharArray? = null
        try {
            val employee = password.toCharArray().also { ownedPassword = it }
            head = ""; password = ""; confirmation = ""; consent = false
            submit(review.reviewId, ownedHead, employee)
        } finally { ownedHead.fill('\u0000'); ownedPassword?.fill('\u0000') }
    }, enabled = enabled && consent && head.length in 12..256 && password.length in 4..256 && password == confirmation,
        modifier = Modifier.fillMaxWidth().touchTarget()) {
        Text(t("Approve and install the original first account", "اعتماد الحساب الأول الأصلي وتثبيته"))
    }
    Notice(t("Successful installation is not POS readiness, device qualification, a login or permission to publish an incomplete runtime.",
        "نجاح التثبيت ليس جاهزية نقطة البيع أو تأهيل الجهاز أو تسجيل الدخول أو تصريحًا بنشر بيئة تشغيل ناقصة."))
}

private val firstAccountUuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
// Mirrors the existing local-human realm boundary; this list is only form selection, never authorization.
private val firstAccountActions = SecurityAction.entries.filterNot { it in setOf(
    SecurityAction.COMPANY_STRUCTURE, SecurityAction.COMPANY_PEOPLE, SecurityAction.COMPANY_CATALOG,
    SecurityAction.COMPANY_POLICY, SecurityAction.COMPANY_REPORT, SecurityAction.DEVICE_STATUS,
    SecurityAction.MASTER_COMMIT, SecurityAction.CENTRAL_INGESTION,
) }
