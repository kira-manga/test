package com.ukkera.pos.app.setup

import androidx.compose.runtime.Composable
import com.ukkera.pos.app.ui.ReviewField
import com.ukkera.pos.app.ui.ScopeDetails
import com.ukkera.pos.app.ui.SectionHeading
import com.ukkera.pos.security.LocalAccountProvisioning
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.LocalTokenPolicy

/** The same complete policy data presentation is used for first and later employees. */
@Composable
internal fun LocalSetupAccountIdentityReview(account: LocalAccountProvisioning) {
    val policy = account.policy
    ReviewField(t("Employee ID", "معرّف الموظف"), account.actorId.value)
    ReviewField(t("Employee login", "معرّف دخول الموظف"), account.login.value)
    ReviewField(t("Admitted employee devices", "أجهزة الموظف المقبولة"),
        account.admittedDeviceIds.sortedBy { it.value }.joinToString("\n") { it.value })
    ReviewField(t("Policy actor", "صاحب السياسة"), policy.actorId.value)
    ReviewField(t("Policy revision", "مراجعة السياسة"), policy.revision.value.toString())
    ScopeDetails(policy.scope)
    ReviewField(t("Complete grant count", "عدد الصلاحيات الكامل"), policy.grants.size.toString())
}

@Composable
internal fun LocalSetupAccountConfigurationReview(account: LocalAccountProvisioning) {
    LocalSetupPolicyConfigurationReview(account.policy, account.tokenPolicy)
}

/** Complete scalar policy review shared by account enrollment and policy replacement. */
@Composable
internal fun LocalSetupPolicyConfigurationReview(policy: ActivatedLocalPolicy, tokenPolicy: LocalTokenPolicy? = null) {
    SectionHeading(t("Complete local configuration", "الإعداد المحلي الكامل"))
    ReviewField(t("Concurrent sessions", "الجلسات المتزامنة"), policy.sessionPolicy.maximumConcurrentSessions.toString())
    tokenPolicy?.let {
        ReviewField(t("Access lifetime (ms)", "مدة الوصول بالمللي ثانية"), it.accessLifetimeMillis.toString())
        ReviewField(t("Work lifetime (ms)", "مدة العمل بالمللي ثانية"), it.workLifetimeMillis.toString())
    }
    ReviewField(t("Free authentication failures", "إخفاقات الدخول قبل التأخير"), policy.throttlePolicy.freeFailures.toString())
    ReviewField(t("Initial delay (ms)", "التأخير الأولي بالمللي ثانية"), policy.throttlePolicy.initialDelayMillis.toString())
    ReviewField(t("Maximum delay (ms)", "أقصى تأخير بالمللي ثانية"), policy.throttlePolicy.maximumDelayMillis.toString())
    ReviewField(t("Attempt timeout (ms)", "مهلة المحاولة بالمللي ثانية"), policy.throttlePolicy.attemptTimeoutMillis.toString())
    val approval = policy.approvalPolicy
    if (approval == null) ReviewField(t("Manager approval policy", "سياسة موافقة المدير"), t("Absent", "غير موجودة"), code = false)
    else {
        ReviewField(t("Approval validity (ms)", "صلاحية الموافقة بالمللي ثانية"), approval.validForMillis.toString())
        ReviewField(t("Named self-approval actions", "إجراءات الموافقة الذاتية المسمّاة"),
            approval.namedSelfApprovalActions.sortedBy { it.name }.joinToString("\n") { it.name }.ifEmpty { t("None", "لا شيء") })
    }
}
