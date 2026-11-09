package com.ukkera.pos.app.setup

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.ukkera.pos.app.ui.GrantDetails
import com.ukkera.pos.app.ui.Notice
import com.ukkera.pos.app.ui.ReviewField
import com.ukkera.pos.app.ui.SectionHeading
import com.ukkera.pos.app.ui.touchTarget
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalApprovalPolicy
import com.ukkera.pos.security.LocalSessionPolicy
import com.ukkera.pos.security.PermissionGrant
import com.ukkera.pos.security.ThrottlePolicy
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Unsaved complete replacement selections. No current directory or inherited grants are inferred. */
@Composable
internal fun LocalSetupEmployeePolicyInputs(review: LocalSetupEmployeePolicyView, enabled: Boolean,
    prepare: (DomainId, ActivatedLocalPolicy) -> Unit) {
    var actorText by remember(review.reviewId) { mutableStateOf("") }
    var predecessorText by remember(review.reviewId) { mutableStateOf("") }
    SetupInput(actorText, { actorText = it }, t("Exact existing employee UUID", "معرّف UUID الدقيق للموظف الموجود"), enabled, 36)
    SetupInput(predecessorText, { predecessorText = it }, t("Expected existing policy revision", "مراجعة السياسة الموجودة المتوقعة"), enabled, 19)
    val actor = actorText.takeIf { policyTargetUuid.matches(it) }?.let(::DomainId)
    val predecessor = predecessorText.takeIf { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }
        ?.toLongOrNull()?.takeIf { it < Long.MAX_VALUE }
    Notice(t("Enter the existing employee and expected revision explicitly. These inputs are checked against the retained policy before preparation and again at activation. Changing either input clears the replacement selections below.",
        "أدخل الموظف الموجود والمراجعة المتوقعة صراحةً. تُفحص هذه القيم مقابل السياسة المحفوظة قبل التحضير وعند التفعيل مجددًا. يؤدي تغيير أي منهما إلى مسح اختيارات الاستبدال أدناه."))
    if (actor == null || predecessor == null) {
        Notice(t("Use a canonical lowercase employee UUID and a nonnegative, nonexhausted revision. A new policy cannot be inferred from this history list.",
            "استخدم معرّف UUID معياريًا بأحرف صغيرة ومراجعة غير سالبة وغير مستنفدة. لا يمكن استنتاج سياسة جديدة من قائمة السجل هذه."), caution = true)
        return
    }
    val successor = Revision(predecessor).next()
    ReviewField(t("Expected predecessor revision", "مراجعة السياسة السابقة المتوقعة"), predecessor.toString())
    ReviewField(t("Requested replacement revision", "مراجعة السياسة البديلة المطلوبة"), successor.value.toString())
    key(review.reviewId, actor, successor) {
        EmployeePolicySelections(review.scope, actor, successor, enabled) { prepare(review.reviewId, it) }
    }
}

@Composable
private fun EmployeePolicySelections(scope: BranchScope, actor: DomainId, revision: Revision, enabled: Boolean,
    prepare: (ActivatedLocalPolicy) -> Unit) {
    var grants by remember { mutableStateOf<List<PermissionGrant>>(emptyList()) }
    var sessions by remember { mutableStateOf("") }
    var freeFailures by remember { mutableStateOf("") }
    var initialDelay by remember { mutableStateOf("") }
    var maximumDelay by remember { mutableStateOf("") }
    var attemptTimeout by remember { mutableStateOf("") }
    var approvalEnabled by remember { mutableStateOf(false) }
    var approvalMillis by remember { mutableStateOf("") }
    var selfApprovals by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    var invalid by remember { mutableStateOf(false) }

    SectionHeading(t("Every replacement grant", "جميع صلاحيات السياسة البديلة"))
    Notice(t("This replaces the complete policy. Only the grants listed here are included; no existing grant or role is inherited. Every grant has its own scope, effect, ownership, targets and limits.",
        "يستبدل هذا السياسة كاملةً. تُضمَّن الصلاحيات المدرجة هنا فقط؛ لا تُورَّث صلاحية موجودة أو دور. لكل صلاحية نطاقها وأثرها وملكيتها وأهدافها وحدودها."))
    grants.forEachIndexed { index, grant ->
        GrantDetails(index, grant)
        OutlinedButton(onClick = { grants = grants.filterIndexed { i, _ -> i != index }; consent = false },
            enabled = enabled, modifier = Modifier.touchTarget()) {
            Text(t("Remove grant", "إزالة الصلاحية") + " ${index + 1}")
        }
    }
    LocalSetupGrantInput(scope, actor, enabled && grants.size < 1024) { grants = grants + it; consent = false }
    ReviewField(t("Complete grant count", "عدد الصلاحيات الكامل"), grants.size.toString())
    if (grants.isEmpty()) Notice(t("The replacement contains no grants. Approving and activating it removes business-area access under this policy.",
        "لا تحتوي السياسة البديلة على صلاحيات. يؤدي اعتمادها وتفعيلها إلى إزالة وصول أقسام الأعمال بموجب هذه السياسة."), caution = true)

    SectionHeading(t("Complete session and authentication policy", "سياسة الجلسة والتحقق كاملةً"))
    SetupInput(sessions, { sessions = it; consent = false }, t("Maximum concurrent sessions (1–16)", "أقصى جلسات متزامنة (1–16)"), enabled, 2)
    SetupInput(freeFailures, { freeFailures = it; consent = false }, t("Free failed attempts (0–5)", "المحاولات الفاشلة قبل التأخير (0–5)"), enabled, 1)
    SetupInput(initialDelay, { initialDelay = it; consent = false }, t("Initial delay in ms (1000–60000)", "التأخير الأولي بالمللي ثانية (1000–60000)"), enabled, 19)
    SetupInput(maximumDelay, { maximumDelay = it; consent = false }, t("Maximum delay in ms (initial delay–86400000)", "أقصى تأخير بالمللي ثانية (التأخير الأولي–86400000)"), enabled, 19)
    SetupInput(attemptTimeout, { attemptTimeout = it; consent = false }, t("Attempt timeout in ms (1000–300000)", "مهلة المحاولة بالمللي ثانية (1000–300000)"), enabled, 19)
    Consent(t("Include an explicit manager-approval policy", "تضمين سياسة صريحة لموافقة المدير"), approvalEnabled, enabled) {
        approvalEnabled = it; consent = false
        if (!it) { approvalMillis = ""; selfApprovals = "" }
    }
    if (approvalEnabled) {
        SetupInput(approvalMillis, { approvalMillis = it; consent = false }, t("Approval lifetime in ms (1–600000)", "مدة الموافقة بالمللي ثانية (1–600000)"), enabled, 19)
        SetupLines(selfApprovals, { selfApprovals = it; consent = false }, t("Exact self-approval action codes, one per line; empty means none", "رموز إجراءات الموافقة الذاتية الدقيقة، واحد في كل سطر؛ فارغ يعني لا شيء"), enabled, 8192)
    } else ReviewField(t("Manager approval policy", "سياسة موافقة المدير"), t("Absent; none inherited", "غير موجودة؛ دون وراثة"), code = false)
    if (invalid) Notice(t("Enter every session, throttle and approval field within its displayed bounds. Self-approval codes must be distinct existing local-employee action names.",
        "أدخل جميع حقول الجلسة والتأخير والموافقة ضمن الحدود المعروضة. يجب أن تكون رموز الموافقة الذاتية أسماء إجراءات الموظف المحلي الموجودة والمختلفة."), caution = true)
    Consent(t("Prepare exactly this complete replacement, including every grant and explicit empty selection", "تحضير هذه السياسة البديلة كاملةً بعينها، بجميع الصلاحيات والاختيارات الفارغة الصريحة"), consent, enabled) { consent = it }
    Button(onClick = {
        val policy = try {
            val approval = if (approvalEnabled) LocalApprovalPolicy(policyUnsigned(approvalMillis), actionCodes(selfApprovals)) else null
            ActivatedLocalPolicy(scope, actor, revision, grants, LocalSessionPolicy(policyUnsigned(sessions).toInt()),
                ThrottlePolicy(policyUnsigned(freeFailures).toInt(), policyUnsigned(initialDelay), policyUnsigned(maximumDelay),
                    policyUnsigned(attemptTimeout)), approval)
        } catch (_: Exception) { invalid = true; null }
        if (policy != null) { consent = false; invalid = false; prepare(policy) }
    }, enabled = enabled && consent, modifier = Modifier.fillMaxWidth().touchTarget()) {
        Text(t("Prepare and review this COMPLETE replacement", "تحضير هذه السياسة البديلة الكاملة ومراجعتها"))
    }
}

private fun policyUnsigned(value: String): Long {
    require(value.isNotEmpty() && value.all { it in '0'..'9' })
    return value.toLong()
}

private val policyTargetUuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
