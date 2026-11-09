package com.ukkera.pos.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.ukkera.pos.app.LocalReferencePickerState
import com.ukkera.pos.app.LocalText
import com.ukkera.pos.app.LocalWorkflowController
import com.ukkera.pos.app.navigation.NavigationToken

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocalReferencePickerDialog(picker: LocalReferencePickerState, working: Boolean,
    controller: LocalWorkflowController, token: NavigationToken) {
    val language = LocalAppLanguage.current
    fun t(english: String, arabic: String) = language.text(LocalText(english, arabic))
    var filter by remember(picker.field, picker.lineId, picker.requested) { mutableStateOf("") }
    val choices = picker.page?.choices.orEmpty().filter { choice -> filter.isBlank() ||
        choice.label.english.contains(filter, ignoreCase = true) || choice.label.arabic.contains(filter) ||
        choice.reference.id.value.contains(filter, ignoreCase = true) }
    AlertDialog(onDismissRequest = { controller.closeReferencePicker(token) },
        title = { Text(language.text(picker.label)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text(t("Active authorized records, one page at a time. Selection is checked again before saving; pages are not one fixed snapshot.",
                        "السجلات النشطة المصرح بها، صفحة واحدة في كل مرة. يُعاد التحقق من الاختيار قبل الحفظ؛ الصفحات ليست لقطة ثابتة واحدة."))
                    OutlinedTextField(picker.exactIdText, { controller.changeReferenceId(it, token) }, enabled = !working,
                        label = { Text(t("Exact record ID (optional)", "معرف السجل الدقيق (اختياري)")) }, singleLine = true,
                        isError = picker.invalidId, textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
                        supportingText = { if (picker.invalidId) Text(t("Enter a valid record ID.", "أدخل معرف سجل صالحًا.")) },
                        modifier = Modifier.fillMaxWidth())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { controller.referencePage(token, exact = true) },
                            enabled = !working, modifier = Modifier.touchTarget()) { Text(t("Look up ID", "بحث بالمعرف")) }
                        TextButton(onClick = { controller.referencePage(token) }, enabled = !working,
                            modifier = Modifier.touchTarget()) { Text(t("First page / refresh", "الصفحة الأولى / تحديث")) }
                        TextButton(onClick = { controller.referencePage(token, next = true) },
                            enabled = !working && picker.page?.nextAfter != null, modifier = Modifier.touchTarget()) {
                            Text(t("Next page", "الصفحة التالية"))
                        }
                    }
                    OutlinedTextField(filter, { if (it.length <= 256) filter = it }, enabled = !working,
                        label = { Text(t("Filter this page only", "تصفية هذه الصفحة فقط")) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                }
                if (working) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                picker.problem?.let { problem -> item { Notice(localProblem(problem), caution = true) } }
                if (!working && picker.page != null && choices.isEmpty()) item {
                    Text(t("No active matching records on this page. Check the next page or look up an exact ID.",
                        "لا توجد سجلات نشطة مطابقة في هذه الصفحة. تحقق من الصفحة التالية أو ابحث بالمعرف الدقيق."),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                items(choices, key = { it.reference.id.value }) { choice ->
                    OutlinedButton(onClick = { controller.chooseReference(choice, token) }, enabled = !working,
                        modifier = Modifier.fillMaxWidth().touchTarget()) {
                        Column {
                            Text(language.text(choice.label))
                            Text(choice.reference.id.value, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
                        }
                    }
                }
                picker.page?.notice?.let { notice -> item { Text(language.text(notice), style = MaterialTheme.typography.bodySmall) } }
            }
        },
        confirmButton = { TextButton(onClick = { controller.closeReferencePicker(token) }, modifier = Modifier.touchTarget()) {
            Text(t("Close", "إغلاق"))
        } })
}
