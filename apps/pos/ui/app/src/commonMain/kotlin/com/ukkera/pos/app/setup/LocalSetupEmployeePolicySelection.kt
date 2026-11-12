package com.ukkera.pos.app.setup

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ukkera.pos.app.ui.Notice
import com.ukkera.pos.app.ui.ReviewField
import com.ukkera.pos.app.ui.ScopeDetails
import com.ukkera.pos.app.ui.touchTarget

/** An explicit unsaved navigation selection; opening requests never approves or activates. */
@Composable
internal fun LocalSetupEmployeePolicySelection(target: LocalSetupEmployeePolicyTarget?, enabled: Boolean,
    controller: LocalSetupController, clear: (() -> Unit)?) {
    if (target == null) return
    Notice(t("Selected from the employee directory. Review this installation and predecessor before defining a complete replacement. Existing unfinished originals must be resolved first.",
        "اختير من دليل الموظفين. راجع هذا التثبيت والمراجعة السابقة قبل تعريف استبدال كامل. يجب حسم الأصول غير المكتملة الموجودة أولًا."))
    ScopeDetails(target.scope)
    ReviewField(t("Selected employee ID", "معرّف الموظف المختار"), target.actorId.value)
    ReviewField(t("Selected policy predecessor", "مراجعة السياسة السابقة المختارة"), target.expectedPolicyRevision.value.toString())
    OutlinedButton(onClick = { controller.openEmployeePolicies(target); clear?.invoke() }, enabled = enabled,
        modifier = Modifier.fillMaxWidth().touchTarget()) {
        Text(t("Open policy requests for this selection", "فتح طلبات السياسة لهذا الاختيار"))
    }
    if (clear != null) OutlinedButton(onClick = clear, modifier = Modifier.fillMaxWidth().touchTarget()) {
        Text(t("Discard this selection", "تجاهل هذا الاختيار"))
    }
}
