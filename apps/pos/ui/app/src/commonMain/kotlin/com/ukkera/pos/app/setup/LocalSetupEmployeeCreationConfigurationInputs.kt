package com.ukkera.pos.app.setup

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.ukkera.pos.app.ui.Notice
import com.ukkera.pos.app.ui.ReviewField
import com.ukkera.pos.app.ui.SectionHeading
import com.ukkera.pos.app.ui.CreationAuthenticationProfileDetails
import com.ukkera.pos.app.ui.touchTarget
import com.ukkera.pos.security.LocalApprovalPolicy
import com.ukkera.pos.security.LocalEmployeeCreationAuthenticationProfileV1
import com.ukkera.pos.security.LocalEmployeeCreationConfigurationV1
import com.ukkera.pos.security.LocalSessionPolicy
import com.ukkera.pos.security.LocalTokenPolicy
import com.ukkera.pos.security.ThrottlePolicy
import com.ukkera.pos.types.DomainId

/** Unsaved choices for the COMPLETE replacement. No inherited profiles, role grants or permit. */
@Composable
internal fun LocalSetupEmployeeCreationConfigurationInputs(
    profiles: List<LocalEmployeeCreationAuthenticationProfileV1>, enabled: Boolean,
    changed: (List<LocalEmployeeCreationAuthenticationProfileV1>) -> Unit,
    editing: () -> Unit,
) {
    SectionHeading(t("Authentication choices for delegated employee creation", "خيارات المصادقة لإنشاء الموظفين بالتفويض"))
    Notice(t("Only the complete choices added here are delegated. Separate current grants and transfer ceilings remain required. Employee creation is not yet available in this release.",
        "تُفوَّض الخيارات الكاملة المضافة هنا فقط. تظل الصلاحيات الحالية وحدود التفويض المنفصلة مطلوبة. إنشاء الموظفين غير متاح بعد في هذا الإصدار."))
    ReviewField(t("Added choices", "الخيارات المضافة"), profiles.size.toString())
    profiles.forEachIndexed { index, profile ->
        CreationAuthenticationProfileDetails(index, profile)
        OutlinedButton(onClick = { changed(profiles.filterIndexed { at, _ -> at != index }) },
            enabled = enabled, modifier = Modifier.touchTarget()) {
            Text(t("Remove creation choice", "إزالة خيار الإنشاء") + " ${index + 1}")
        }
    }
    if (profiles.isEmpty()) Notice(t("No authentication configuration is delegated. An empty selection never permits arbitrary settings.",
        "لم يُفوَّض أي إعداد للمصادقة. لا يسمح الاختيار الفارغ بإعدادات غير محددة."))
    var adding by remember { mutableStateOf(false) }
    Consent(t("Add an explicit complete authentication choice", "إضافة خيار مصادقة صريح وكامل"), adding,
        enabled && profiles.size < LocalEmployeeCreationConfigurationV1.MAXIMUM_PROFILES) { adding = it; editing() }
    if (adding) CreationAuthenticationChoiceInput(enabled, editing) { profile ->
        // Canonical capture enforces catalogue size and rejects exact duplicate profiles.
        val proposed = profiles + profile
        LocalEmployeeCreationConfigurationV1.capture(proposed)
        changed(proposed)
        adding = false
    }
}

@Composable
private fun CreationAuthenticationChoiceInput(enabled: Boolean, editing: () -> Unit,
    add: (LocalEmployeeCreationAuthenticationProfileV1) -> Unit) {
    var recipient by remember { mutableStateOf("") }
    var sessions by remember { mutableStateOf("") }
    var freeFailures by remember { mutableStateOf("") }
    var initialDelay by remember { mutableStateOf("") }
    var maximumDelay by remember { mutableStateOf("") }
    var attemptTimeout by remember { mutableStateOf("") }
    var accessLifetime by remember { mutableStateOf("") }
    var workLifetime by remember { mutableStateOf("") }
    var approvalEnabled by remember { mutableStateOf(false) }
    var approvalLifetime by remember { mutableStateOf("") }
    var selfApprovals by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }

    SetupInput(recipient, { recipient = it; editing() }, t("Named recipient UUID; empty allows recipients within the separate grants", "معرّف UUID للمستفيد المسمّى؛ الفارغ يسمح بالمستفيدين ضمن الصلاحيات المنفصلة"), enabled, 36)
    SetupInput(sessions, { sessions = it; editing() }, t("Maximum concurrent sessions (1–16)", "أقصى جلسات متزامنة (1–16)"), enabled, 2)
    SetupInput(freeFailures, { freeFailures = it; editing() }, t("Free failed attempts (0–5)", "المحاولات الفاشلة قبل التأخير (0–5)"), enabled, 1)
    SetupInput(initialDelay, { initialDelay = it; editing() }, t("Initial delay in ms (1000–60000)", "التأخير الأولي بالمللي ثانية (1000–60000)"), enabled, 19)
    SetupInput(maximumDelay, { maximumDelay = it; editing() }, t("Maximum delay in ms (initial delay–86400000)", "أقصى تأخير بالمللي ثانية (التأخير الأولي–86400000)"), enabled, 19)
    SetupInput(attemptTimeout, { attemptTimeout = it; editing() }, t("Attempt timeout in ms (1000–300000)", "مهلة المحاولة بالمللي ثانية (1000–300000)"), enabled, 19)
    SetupInput(accessLifetime, { accessLifetime = it; editing() }, t("Access lifetime in ms", "مدة الوصول بالمللي ثانية"), enabled, 19)
    SetupInput(workLifetime, { workLifetime = it; editing() }, t("Work lifetime in ms, at least access lifetime", "مدة العمل بالمللي ثانية، لا تقل عن مدة الوصول"), enabled, 19)
    Consent(t("Include an explicit manager-approval policy", "تضمين سياسة صريحة لموافقة المدير"), approvalEnabled, enabled) {
        approvalEnabled = it; editing()
        if (!it) { approvalLifetime = ""; selfApprovals = "" }
    }
    if (approvalEnabled) {
        SetupInput(approvalLifetime, { approvalLifetime = it; editing() }, t("Approval lifetime in ms (1–600000)", "مدة الموافقة بالمللي ثانية (1–600000)"), enabled, 19)
        SetupLines(selfApprovals, { selfApprovals = it; editing() }, t("Exact self-approval action codes, one per line; empty means none", "رموز إجراءات الموافقة الذاتية الدقيقة، واحد في كل سطر؛ فارغ يعني لا شيء"), enabled, 8192)
        Notice(t("A self-approval exception requires the exact named recipient above and the company head's explicit review.",
            "يتطلب استثناء الموافقة الذاتية تحديد المستفيد المسمّى أعلاه ومراجعة رئيس الشركة الصريحة."))
    }
    if (invalid) Notice(t("Complete every field within its stated bounds. Name the recipient for self-approval. Duplicate choices are not added.",
        "أكمل جميع الحقول ضمن الحدود المعروضة. سمِّ المستفيد للموافقة الذاتية. لا تُضاف الخيارات المكررة."), caution = true)
    Button(onClick = {
        try {
            val named = if (recipient.isEmpty()) null else {
                require(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(recipient))
                DomainId(recipient)
            }
            val approval = if (approvalEnabled) LocalApprovalPolicy(creationUnsigned(approvalLifetime), actionCodes(selfApprovals)) else null
            val profile = LocalEmployeeCreationAuthenticationProfileV1(LocalSessionPolicy(creationInt(sessions)),
                ThrottlePolicy(creationInt(freeFailures), creationUnsigned(initialDelay), creationUnsigned(maximumDelay), creationUnsigned(attemptTimeout)),
                LocalTokenPolicy(creationUnsigned(accessLifetime), creationUnsigned(workLifetime)), approval, named)
            add(profile)
            invalid = false
        } catch (_: IllegalArgumentException) { invalid = true }
    }, enabled = enabled, modifier = Modifier.fillMaxWidth().touchTarget()) {
        Text(t("Add this complete authentication choice", "إضافة خيار المصادقة الكامل هذا"))
    }
}

private fun creationUnsigned(value: String): Long {
    require(value.isNotEmpty() && value.all { it in '0'..'9' })
    return value.toLong()
}

private fun creationInt(value: String): Int {
    val number = creationUnsigned(value)
    require(number <= Int.MAX_VALUE)
    return number.toInt()
}
