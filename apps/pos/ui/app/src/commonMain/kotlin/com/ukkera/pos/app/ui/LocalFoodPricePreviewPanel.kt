package com.ukkera.pos.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ukkera.pos.app.LocalFoodPricePreview
import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.app.LocalText

/** This panel cannot confirm, stage or dispatch a sale. It displays one exact protected read. */
@Composable
internal fun LocalFoodPricePreviewPanel(preview: LocalFoodPricePreview?, problem: LocalServiceProblem?,
    enabled: Boolean, read: () -> Unit) {
    val language = LocalAppLanguage.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = read, enabled = enabled, modifier = Modifier.touchTarget()) {
            Text(language.text(LocalText("Preview selected food prices", "معاينة أسعار أصناف الطعام المختارة")))
        }
        Text(language.text(LocalText(
            "Read-only current prices for the new selection, not the whole existing check. Preview does not save, reserve stock or take payment. Saving rechecks current prices and permissions.",
            "أسعار حالية للقراءة فقط للاختيار الجديد، وليست إجمالي الطلب القائم. المعاينة لا تحفظ ولا تحجز مخزونًا ولا تستلم دفعًا. يعيد الحفظ التحقق من الأسعار والصلاحيات الحالية.")),
            style = MaterialTheme.typography.bodySmall)
        problem?.let { Notice(localProblem(it), caution = true) }
        preview?.let { result ->
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                result.lines.forEach { line ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FoodIdentifier(language.text(LocalText("Commercial line", "السطر التجاري")), line.lineId.value)
                        Text("${language.text(LocalText("Quantity", "الكمية"))}: ${language.foodQuantity(line.quantity)}")
                        Text("${language.text(LocalText("Selected unit price", "سعر الوحدة المختار"))}: " +
                            "\u2066${language.digits(line.unitPrice.amount.canonicalString())}\u2069 ${line.unitPrice.currency.name}")
                        Text(language.text(if (line.taxIncluded) LocalText("Unit price includes tax", "سعر الوحدة شامل الضريبة")
                            else LocalText("Unit price excludes tax", "سعر الوحدة غير شامل الضريبة")))
                        Text("${language.text(LocalText("Line net", "صافي السطر"))}: ${language.foodMoney(line.net)}")
                        Text("${language.text(LocalText("Line tax", "ضريبة السطر"))}: ${language.foodMoney(line.tax)}")
                        Text("${language.text(LocalText("Line total", "إجمالي السطر"))}: ${language.foodMoney(line.payable)}")
                    }
                }
                Text("${language.text(LocalText("Before discount", "قبل الخصم"))}: ${language.foodMoney(result.beforeDiscount)}")
                Text("${language.text(LocalText("Discount", "الخصم"))}: ${language.foodMoney(result.discount)}")
                Text("${language.text(LocalText("Net total", "إجمالي الصافي"))}: ${language.foodMoney(result.net)}")
                Text("${language.text(LocalText("Included tax total", "إجمالي الضريبة المحتسبة"))}: ${language.foodMoney(result.tax)}")
                Text("${language.text(LocalText("Included charges total", "إجمالي الرسوم المحتسبة"))}: ${language.foodMoney(result.charges)}")
                Text("${language.text(LocalText("New selection payable", "المستحق للاختيار الجديد"))}: ${language.foodMoney(result.payable)}",
                    style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
