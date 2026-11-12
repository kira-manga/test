package com.ukkera.pos.app.setup

import androidx.compose.runtime.Composable
import com.ukkera.pos.app.ui.PolicyConfigurationDetails
import com.ukkera.pos.app.ui.ReviewField
import com.ukkera.pos.app.ui.ScopeDetails
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
    PolicyConfigurationDetails(policy, tokenPolicy)
}
