package com.ukkera.pos.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.ukkera.pos.app.LocalText
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PermissionGrant
import com.ukkera.pos.security.PermissionLimits
import com.ukkera.pos.security.TenantScope

/** Shared read-only identity/policy presentation; it grants no authority or implicit defaults. */
@Composable
internal fun GrantDetails(index: Int, grant: PermissionGrant) {
    Surface(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeading(identityText("Grant", "الصلاحية") + " " + (index + 1))
            ReviewField(identityText("Action code", "رمز الإجراء"), grant.permission.action.name)
            ReviewField(identityText("Verb", "نوع الإذن"), grant.permission.verb.name)
            ReviewField(identityText("Effect", "الأثر"), grant.effect.name)
            ScopeDetails(grant.scope)
            ReviewField(identityText("Owner access", "وصول المالك"), grant.ownerAccess.name)
            ReviewField(identityText("Named subject", "الشخص المسمّى"), grant.namedSubjectId?.value ?: identityText("Unspecified", "غير محدد"))
            ReviewField(identityText("Exact target IDs", "معرّفات الأهداف الدقيقة"),
                grant.targetIds?.sortedBy { it.value }?.joinToString("\n") { it.value } ?: identityText("Unspecified", "غير محددة"))
            LimitsDetails(grant.limits)
            Text(identityText("Delegation ceiling", "حدود التفويض"), style = MaterialTheme.typography.titleMedium)
            val ceiling = grant.delegationCeiling
            if (ceiling == null) Text(identityText("No ceiling record supplied", "لم يُقدّم سجل حدود"))
            else LimitsDetails(ceiling)
        }
    }
}

@Composable
private fun LimitsDetails(limits: PermissionLimits) {
    ReviewField(identityText("Maximum amount (minor units)", "أقصى مبلغ بوحداته الصغرى"),
        limits.maximumAmount?.let { it.minor.toString() + " " + it.currency.name } ?: identityText("Unspecified", "غير محدد"))
    ReviewField(identityText("Maximum amount", "أقصى مبلغ"),
        limits.maximumAmount?.let { it.currency.name + " " + identityFixedDecimal(it.minor, it.currency.fractionDigits) }
            ?: identityText("Unspecified", "غير محدد"))
    ReviewField(identityText("Maximum percentage (basis points)", "أقصى نسبة بنقاط الأساس"),
        limits.maximumPercentageBasisPoints?.toString() ?: identityText("Unspecified", "غير محددة"))
    ReviewField(identityText("Maximum percentage", "أقصى نسبة"),
        limits.maximumPercentageBasisPoints?.let { identityFixedDecimal(it.toLong(), 2) + "%" }
            ?: identityText("Unspecified", "غير محددة"))
    ReviewField(identityText("Explicit no monetary ceiling (currency)", "عدم وجود حد نقدي صريح مع العملة"),
        limits.noMonetaryCeiling?.currency?.name ?: identityText("Not declared", "غير معلن"))
}

/** Exact fixed-scale display; no floating point, rounding or policy-data conversion. */
private fun identityFixedDecimal(scaled: Long, scale: Int): String {
    val signed = scaled.toString()
    val digits = signed.removePrefix("-").padStart(scale + 1, '0')
    val sign = if (signed.startsWith('-')) "-" else ""
    return sign + if (scale == 0) digits else digits.dropLast(scale) + "." + digits.takeLast(scale)
}

@Composable
internal fun ScopeDetails(scope: TenantScope) {
    ReviewField(identityText("Organization scope", "نطاق المؤسسة"), scope.organizationId.value)
    ReviewField(identityText("Company scope", "نطاق الشركة"), scope.companyId.value)
    ReviewField(identityText("Branch scope", "نطاق الفرع"),
        if (scope is BranchScope) scope.branchId.value else identityText("Company scope", "نطاق الشركة"))
}

@Composable
internal fun ReviewField(label: String, value: String, code: Boolean = true) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        if (code) CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
        } else Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun identityText(english: String, arabic: String): String =
    LocalAppLanguage.current.text(LocalText(english, arabic))
