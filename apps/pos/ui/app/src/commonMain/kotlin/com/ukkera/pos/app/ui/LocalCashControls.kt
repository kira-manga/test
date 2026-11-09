package com.ukkera.pos.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ukkera.pos.app.LocalCashSection
import com.ukkera.pos.app.LocalCashUiQuery
import com.ukkera.pos.app.LocalText

/** Read controls stay under the entry controller's actor, pending-result and leave guards. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocalCashControls(query: LocalCashUiQuery, enabled: Boolean,
    select: (LocalCashUiQuery) -> Unit, destinations: () -> Unit) {
    val language = LocalAppLanguage.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LocalCashSection.entries.forEach { section ->
                FilterChip(selected = query.section == section, enabled = enabled,
                    onClick = { select(query.copy(section = section, showCounts = false)) }, label = { Text(language.text(when (section) {
                        LocalCashSection.MY_CASH -> LocalText("My cash", "عهدتي النقدية")
                        LocalCashSection.OWN_SESSION -> LocalText("My session by ID", "جلستي بالمعرف")
                        LocalCashSection.CLOSED_REVIEW -> LocalText("Closed review", "مراجعة الإغلاق")
                        LocalCashSection.TRANSFER -> LocalText("Transfer by ID", "التحويل بالمعرف")
                        LocalCashSection.SAFE_BALANCE -> LocalText("My safe by ID", "خزنتي بالمعرف")
                    })) }, modifier = Modifier.touchTarget())
            }
        }
        if (query.section in setOf(LocalCashSection.MY_CASH, LocalCashSection.OWN_SESSION, LocalCashSection.CLOSED_REVIEW)) {
            FilterChip(selected = query.showCounts, enabled = enabled,
                onClick = { select(query.copy(showCounts = !query.showCounts)) },
                label = { Text(language.text(LocalText("Count and recount history", "سجل العد وإعادة العد"))) }, modifier = Modifier.touchTarget())
        }
        if (!query.showCounts && query.section in setOf(LocalCashSection.MY_CASH, LocalCashSection.OWN_SESSION)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = query.closeToSafe, enabled = enabled,
                    onClick = { select(query.copy(closeToSafe = !query.closeToSafe)) },
                    label = { Text(language.text(LocalText("Closing safe delivery", "تسليم خزنة عند الإغلاق"))) }, modifier = Modifier.touchTarget())
                OutlinedButton(onClick = destinations, enabled = enabled, modifier = Modifier.touchTarget()) {
                    Text(language.text(LocalText("Safe destinations", "وجهات الخزنة")))
                }
            }
        }
        Text(language.text(if (query.showCounts) LocalText(
            "Read-only original counts and reasoned recounts, newest first. A cancelled closing cycle remains history, not a current count. Refresh after any session change; reading never submits a close or acknowledges money.",
            "عرض فقط للعد الأصلي وإعادات العد بأسبابها، الأحدث أولًا. تبقى دورة الإغلاق الملغاة في السجل وليست عدًا حاليًا. حدّث بعد أي تغيير في الجلسة؛ القراءة لا تقدم إغلاقًا أو تؤكد استلام مال.") else when (query.section) {
            LocalCashSection.MY_CASH -> LocalText("Shows your current assignment. Record the count first, then actual delivery and separate receipt, then submit. Recounts retain the original evidence and need a reason. Closing never guesses money or a receiving acknowledgement.",
                "يعرض عهدتك الحالية. سجل العد أولًا ثم التسليم الفعلي والاستلام المنفصل ثم قدم الإغلاق. تحتفظ إعادة العد بالإثبات الأصلي وتحتاج إلى سبب. لا يفترض الإغلاق نقدية أو تأكيد استلام.")
            LocalCashSection.OWN_SESSION -> LocalText("Enter your original cash-session ID, including a submitted session after a new shift starts.",
                "أدخل معرف جلستك النقدية الأصلية، بما فيها الجلسة المقدمة بعد بدء وردية جديدة.")
            LocalCashSection.CLOSED_REVIEW -> LocalText("Enter the original submitted session ID. Requires review permission, not access to the cashier's active drawer. Self-review is not allowed.",
                "أدخل معرف الجلسة الأصلية المقدمة. يتطلب صلاحية مراجعة، وليس الوصول إلى درج الكاشير النشط. لا تُسمح المراجعة الذاتية.")
            LocalCashSection.TRANSFER -> LocalText("Enter the original transfer ID. Only its sender or receiving custodian may inspect this path. Record receipt only for an actual handover; never repeat it after a lost response.",
                "أدخل معرف التحويل الأصلي. يُتاح هذا المسار للمرسل أو أمين عهدة الاستلام فقط. سجل الاستلام عن تسليم فعلي فقط؛ لا تكرره بعد فقدان الرد.")
            LocalCashSection.SAFE_BALANCE -> LocalText("Enter your safe custody ID. This read never discloses another custodian's balance.",
                "أدخل معرف عهدة خزنتك. لا تكشف هذه القراءة رصيد أمين عهدة آخر.")
        }), style = MaterialTheme.typography.bodySmall)
    }
}
