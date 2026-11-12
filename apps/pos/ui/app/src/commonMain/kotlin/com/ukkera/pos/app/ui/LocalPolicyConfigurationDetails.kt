package com.ukkera.pos.app.ui

import androidx.compose.runtime.Composable
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.LocalTokenPolicy
import com.ukkera.pos.security.LocalSessionPolicy
import com.ukkera.pos.security.LocalApprovalPolicy
import com.ukkera.pos.security.ThrottlePolicy
import com.ukkera.pos.security.LocalEmployeeCreationAuthenticationProfileV1

/** Read-only scalar policy DATA shared by current employee discovery, enrollment and replacement. */
@Composable
internal fun PolicyConfigurationDetails(policy: ActivatedLocalPolicy, tokenPolicy: LocalTokenPolicy? = null) {
    SectionHeading(policyText("Complete local configuration", "الإعداد المحلي الكامل"))
    AuthenticationConfigurationDetails(policy.sessionPolicy, policy.throttlePolicy, policy.approvalPolicy, tokenPolicy)
    val catalogue = policy.employeeCreationConfiguration
    if (catalogue == null) {
        ReviewField(policyText("Delegated employee creation", "إنشاء الموظفين بالتفويض"),
            policyText("No authentication configurations delegated", "لم تُفوّض أي إعدادات للمصادقة"), code = false)
    } else {
        SectionHeading(policyText("Delegated employee authentication configurations", "إعدادات مصادقة الموظفين المفوّضة"))
        catalogue.copyProfiles().forEachIndexed { index, profile -> CreationAuthenticationProfileDetails(index, profile) }
    }
}

@Composable
internal fun CreationAuthenticationProfileDetails(index: Int, profile: LocalEmployeeCreationAuthenticationProfileV1) {
    SectionHeading(policyText("Creation configuration ${index + 1}", "إعداد الإنشاء ${index + 1}"))
    ReviewField(policyText("Recipient", "المستفيد"), profile.namedRecipientId?.value ?:
        policyText("Any recipient permitted by the separate grants", "أي مستفيد تسمح به الصلاحيات المنفصلة"))
    AuthenticationConfigurationDetails(profile.sessionPolicy, profile.throttlePolicy, profile.approvalPolicy, profile.tokenPolicy)
}

@Composable
private fun AuthenticationConfigurationDetails(sessionPolicy: LocalSessionPolicy, throttlePolicy: ThrottlePolicy,
    approval: LocalApprovalPolicy?, tokenPolicy: LocalTokenPolicy?) {
    ReviewField(policyText("Concurrent sessions", "الجلسات المتزامنة"), sessionPolicy.maximumConcurrentSessions.toString())
    tokenPolicy?.let {
        ReviewField(policyText("Access lifetime (ms)", "مدة الوصول بالمللي ثانية"), it.accessLifetimeMillis.toString())
        ReviewField(policyText("Work lifetime (ms)", "مدة العمل بالمللي ثانية"), it.workLifetimeMillis.toString())
    }
    ReviewField(policyText("Free authentication failures", "إخفاقات الدخول قبل التأخير"), throttlePolicy.freeFailures.toString())
    ReviewField(policyText("Initial delay (ms)", "التأخير الأولي بالمللي ثانية"), throttlePolicy.initialDelayMillis.toString())
    ReviewField(policyText("Maximum delay (ms)", "أقصى تأخير بالمللي ثانية"), throttlePolicy.maximumDelayMillis.toString())
    ReviewField(policyText("Attempt timeout (ms)", "مهلة المحاولة بالمللي ثانية"), throttlePolicy.attemptTimeoutMillis.toString())
    if (approval == null) ReviewField(policyText("Manager approval policy", "سياسة موافقة المدير"), policyText("Absent", "غير موجودة"), code = false)
    else {
        ReviewField(policyText("Approval validity (ms)", "صلاحية الموافقة بالمللي ثانية"), approval.validForMillis.toString())
        ReviewField(policyText("Named self-approval actions", "إجراءات الموافقة الذاتية المسمّاة"),
            approval.namedSelfApprovalActions.sortedBy { it.name }.joinToString("\n") { it.name }.ifEmpty { policyText("None", "لا شيء") })
    }
}

@Composable
private fun policyText(english: String, arabic: String): String =
    if (LocalAppLanguage.current == AppLanguage.ARABIC) arabic else english
