package com.ukkera.pos.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ukkera.pos.app.setup.Consent
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ukkera.pos.app.LocalAdministrationController
import com.ukkera.pos.app.LocalAdministrationWorkspace
import com.ukkera.pos.app.LocalEmployeeCreationObservation
import com.ukkera.pos.app.LocalEmployeeCreationPhase
import com.ukkera.pos.app.navigation.NavigationToken

/** The navigation entry owns the request and asynchronous work. This card owns no originals,
 * passwords or saved state, and cannot turn an observed absence into a fresh submission. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocalEmployeeCreationRecoveryCard(controller: LocalAdministrationController,
    state: LocalAdministrationWorkspace, token: NavigationToken) {
    val creation = state.creation
    val selected = creation.displayed
    val enabled = !state.working && !state.passwordEntryOpen && !creation.passwordEntryOpen && creation.target == null
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        SectionHeading(creationText("Employee-creation recovery", "استعادة طلب إنشاء موظف"))
        Text(creationText(
            "This device retains the exact original request. Looking it up does not sign in the employee, submit it again or replace its identity.",
            "يحتفظ هذا الجهاز بالطلب الأصلي الدقيق. لا يؤدي البحث عن نتيجته إلى تسجيل دخول الموظف أو إعادة إرساله أو استبدال هويته."),
            style = MaterialTheme.typography.bodySmall)
        creation.problem?.let { Notice(localProblem(it), caution = true) }
        when (creation.observation) {
            LocalEmployeeCreationObservation.NOT_OBSERVED -> Notice(creationText(
                "No result was observed at the protected read. Keep this original; this does not prove rollback or permit another submission.",
                "لم تُرصد نتيجة أثناء القراءة المحمية. احتفظ بهذا الطلب الأصلي؛ فهذا لا يثبت التراجع ولا يسمح بإرسال آخر."), caution = true)
            LocalEmployeeCreationObservation.PREPARED_REMOVED -> Notice(creationText(
                "The prepared request was removed locally before dispatch. No server cancellation was recorded by this action.",
                "أُزيل الطلب المحضّر محليًا قبل الإرسال. لم يسجّل هذا الإجراء إلغاءً على الجهاز الرئيسي."))
            LocalEmployeeCreationObservation.TERMINAL_ACKNOWLEDGED -> Notice(creationText(
                "The terminal local record was acknowledged. The Master's original and audit remain retained.",
                "تم الإقرار بالسجل المحلي المنتهي. يبقى الطلب الأصلي وتدقيقه محفوظين على الجهاز الرئيسي."))
            null -> Unit
        }
        if (selected != null) {
            val original = selected.original
            val requested = original.copyRequestedDefinition()
            ScopeDetails(original.scope)
            ReviewField(creationText("Original request", "الطلب الأصلي"), original.requestId.value)
            ReviewField(creationText("Original creator", "منشئ الطلب الأصلي"), original.operatorId.value)
            ReviewField(creationText("Employee ID", "معرّف الموظف"), original.actorId.value)
            ReviewField(creationText("Requested login", "معرّف الدخول المطلوب"), requested.definition.login.value)
            ReviewField(creationText("Original content digest", "بصمة المحتوى الأصلي"), original.contentDigest.value)
            ReviewField(creationText("Retained phase", "المرحلة المحفوظة"), creationPhase(selected.phase))
            ReviewField(creationText("Stop requested", "طُلب الإيقاف"),
                if (selected.stopRequested) creationText("Yes", "نعم") else creationText("No", "لا"))
            if (selected.phase == LocalEmployeeCreationPhase.PREPARED) {
                val definition = requested.definition
                ReviewField(creationText("All admitted employee devices", "جميع أجهزة الموظف المقبولة"),
                    definition.admittedDeviceIds.sortedBy { it.value }.joinToString("\n") { it.value })
                definition.policy.grants.forEachIndexed { index, grant -> GrantDetails(index, grant) }
                if (definition.policy.grants.isEmpty()) Notice(creationText("No business grants requested.", "لا توجد صلاحيات أعمال مطلوبة."), caution = true)
                PolicyConfigurationDetails(definition.policy, definition.tokenPolicy)
                ReviewField(creationText("Initial password requested", "تقديم كلمة مرور أولى"),
                    if (requested.credentialWasProvided) creationText("Yes; transient entry at confirmation", "نعم؛ إدخال مؤقت عند التأكيد")
                    else creationText("No initial password", "دون كلمة مرور أولى"))
                var confirmed by remember(selected) { mutableStateOf(false) }
                Consent(creationText("I reviewed this complete saved definition for first submission", "راجعت هذا التعريف المحفوظ الكامل للإرسال الأول"),
                    confirmed, enabled) { confirmed = it }
                Button(onClick = {
                    confirmed = false
                    if (requested.credentialWasProvided) controller.openCreationPasswordEntry(token)
                    else controller.submitCreation(token)
                }, enabled = enabled && creation.canSubmit && confirmed, modifier = Modifier.touchTarget()) {
                    Text(if (requested.credentialWasProvided) creationText("Enter initial password and confirm", "إدخال كلمة المرور الأولى والتأكيد")
                    else creationText("Submit this original once without a password", "إرسال هذا الطلب الأصلي مرة واحدة دون كلمة مرور"))
                }
            }
            when (selected.phase) {
                LocalEmployeeCreationPhase.PREPARED -> Notice(creationText(
                    "This request is prepared locally. Removing it here is different from cancelling dispatched work on the Master.",
                    "هذا الطلب محضّر محليًا. تختلف إزالته هنا عن إلغاء عمل أُرسل إلى الجهاز الرئيسي."))
                LocalEmployeeCreationPhase.DISPATCHED -> Notice(creationText(
                    "Dispatch was attempted. Keep the original even when the reply is missing. First request a durable stop, then explicitly request cancellation on the Master; an earlier applied result wins unchanged.",
                    "جرت محاولة الإرسال. احتفظ بالطلب الأصلي حتى عند فقد الرد. اطلب إيقافًا محفوظًا أولًا ثم اطلب صراحة الإلغاء على الجهاز الرئيسي؛ تبقى النتيجة السابقة المنفّذة كما هي."), caution = true)
                LocalEmployeeCreationPhase.APPLIED -> Notice(creationText(
                    "The retained original was applied. This recovery did not install a new password or create an employee session. The employee signs in separately.",
                    "نُفّذ الطلب الأصلي المحفوظ. لم تثبّت هذه الاستعادة كلمة مرور جديدة ولم تنشئ جلسة موظف. يسجّل الموظف دخوله منفصلًا."))
                LocalEmployeeCreationPhase.CANCELLED -> Notice(creationText(
                    "The Master retained a cancellation for this exact original. This is not an instruction to recreate the request.",
                    "حفظ الجهاز الرئيسي إلغاءً لهذا الطلب الأصلي الدقيق. هذه ليست تعليمات لإعادة إنشاء الطلب."))
                LocalEmployeeCreationPhase.IDENTITY_CONFLICT -> Notice(creationText(
                    "The original identity conflicts with retained history. Acknowledging this local record does not resolve or replace the Master's original.",
                    "تتعارض الهوية الأصلية مع السجل المحفوظ. لا يحل الإقرار بهذا السجل المحلي التعارض ولا يستبدل الطلب الأصلي على الجهاز الرئيسي."), caution = true)
            }
        } else if (creation.loaded && creation.original == null && creation.problem == null) {
            Text(creationText("No saved employee-creation request on this device.", "لا يوجد طلب محفوظ لإنشاء موظف على هذا الجهاز."))
        } else if (creation.original != null) {
            Notice(creationText(
                "An original is retained for recovery. Its private details are hidden until an authorized read succeeds. Missing local data does not establish the server outcome.",
                "يُحتفظ بطلب أصلي للاستعادة. تُخفى تفاصيله الخاصة حتى تنجح قراءة مصرّح بها. لا يثبت غياب البيانات المحلية نتيجة الجهاز الرئيسي."), caution = true)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { controller.reloadCreation(token) }, enabled = enabled,
                modifier = Modifier.touchTarget()) { Text(creationText("Reload saved request", "إعادة قراءة الطلب المحفوظ")) }
            if (creation.original != null && selected == null) OutlinedButton(
                onClick = { controller.retryCreationPreparation(token) }, enabled = enabled, modifier = Modifier.touchTarget()) {
                Text(creationText("Reconcile local preparation of this original", "تسوية التحضير المحلي لهذا الطلب الأصلي"))
            }
            if (creation.original != null) OutlinedButton(onClick = { controller.recoverCreation(token) }, enabled = enabled,
                modifier = Modifier.touchTarget()) { Text(creationText("Look up original result", "البحث عن نتيجة الطلب الأصلي")) }
            if (selected != null && !selected.terminal && !selected.stopRequested) OutlinedButton(
                onClick = { controller.stopCreation(token) }, enabled = enabled, modifier = Modifier.touchTarget()) {
                Text(if (selected.phase == LocalEmployeeCreationPhase.PREPARED)
                    creationText("Remove prepared request locally", "إزالة الطلب المحضّر محليًا")
                else creationText("Save stop request", "حفظ طلب الإيقاف"))
            }
            if (selected != null && selected.phase == LocalEmployeeCreationPhase.DISPATCHED && selected.stopRequested) OutlinedButton(
                onClick = { controller.cancelCreation(token) }, enabled = enabled, modifier = Modifier.touchTarget()) {
                Text(creationText("Request cancellation on Master", "طلب الإلغاء على الجهاز الرئيسي"))
            }
            if (selected?.terminal == true) OutlinedButton(onClick = { controller.acknowledgeCreation(token) },
                enabled = enabled, modifier = Modifier.touchTarget()) {
                Text(creationText("Acknowledge terminal local record", "الإقرار بالسجل المحلي المنتهي"))
            }
        }
    }
}

@Composable
private fun creationPhase(phase: LocalEmployeeCreationPhase): String = when (phase) {
    LocalEmployeeCreationPhase.PREPARED -> creationText("Prepared locally", "محضّر محليًا")
    LocalEmployeeCreationPhase.DISPATCHED -> creationText("Dispatch attempted; original retained", "جرت محاولة الإرسال؛ الطلب الأصلي محفوظ")
    LocalEmployeeCreationPhase.APPLIED -> creationText("Applied original", "طلب أصلي منفّذ")
    LocalEmployeeCreationPhase.IDENTITY_CONFLICT -> creationText("Original identity conflict", "تعارض الهوية الأصلية")
    LocalEmployeeCreationPhase.CANCELLED -> creationText("Cancellation retained by Master", "إلغاء محفوظ على الجهاز الرئيسي")
}

@Composable
private fun creationText(english: String, arabic: String): String = if (LocalAppLanguage.current == AppLanguage.ARABIC) arabic else english
