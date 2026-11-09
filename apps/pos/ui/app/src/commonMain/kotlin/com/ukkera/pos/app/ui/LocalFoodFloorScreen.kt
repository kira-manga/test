package com.ukkera.pos.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ukkera.pos.app.*
import com.ukkera.pos.app.navigation.NavigationToken

/** Read-only table selection uses the existing controller and attachment lifetime. */
internal fun LazyListScope.localFoodFloorContent(body: LocalFoodUiBody,
    controller: LocalWorkflowController, token: NavigationToken, browse: Boolean,
    assignment: LocalFoodUiTableTarget? = null) {
    item("food-floor-scope") {
        Notice(LocalAppLanguage.current.text(if (assignment == null && controller.foodTableAssignmentsOnly) LocalText(
            "Browse configured floors and tables. Begin assignment from your own check's current table observation; selecting a table alone changes nothing. Enabled does not mean free or occupied.",
            "تصفح القاعات والطاولات المُعدّة. ابدأ التعيين من قراءة الطاولة الحالية لطلبك؛ اختيار الطاولة وحده لا يغير شيئًا. التفعيل لا يعني أن الطاولة شاغرة أو مشغولة.") else if (assignment == null) LocalText(
            "Configured floors and tables only. Enabled does not mean free or occupied. Selecting a table does not open anyone's bill. Assignment, moves, seats and bill split/merge are not available here. Pages use stable ID order; layout order is configuration metadata.",
            "إعدادات القاعات والطاولات فقط. التفعيل لا يعني أن الطاولة شاغرة أو مشغولة. اختيار الطاولة لا يفتح فاتورة أي موظف. المقاعد وتقسيم الفواتير ودمجها غير متاحة هنا. ترتيب الصفحات حسب المعرف الثابت؛ ترتيب المخطط من بيانات الإعداد.") else LocalText(
            "Choose an active destination for this check, then confirm the assignment separately. Browsing does not move the check. Table assignment does not transfer ownership, replay preparation or change stock/payments. Enabled is not an occupancy status.",
            "اختر وجهة مفعّلة لهذا الطلب، ثم أكّد التعيين بصورة منفصلة. التصفح لا ينقل الطلب. تعيين الطاولة لا ينقل الملكية ولا يكرر التحضير ولا يغير المخزون أو المدفوعات. التفعيل ليس حالة إشغال.")), caution = true)
        if (assignment != null) {
            OutlinedButton(onClick = { controller.selectFood(LocalFoodUiQuery.TableAssignment(assignment.orderId,
                assignment.expectedOrderStorageRevision), token) }, enabled = browse, modifier = Modifier.touchTarget()) {
                Text(LocalAppLanguage.current.text(LocalText("Review current assignment again", "مراجعة التعيين الحالي مجددًا")))
            }
            OutlinedButton(onClick = { controller.selectFood(LocalFoodUiQuery.Check(assignment.orderId), token) },
                enabled = browse, modifier = Modifier.touchTarget()) {
                Text(LocalAppLanguage.current.text(LocalText("Back to check", "العودة إلى الطلب")))
            }
        }
    }
    when (body) {
        is LocalFoodUiBody.Floors -> {
            if (body.floors.isEmpty()) item("food-floor-empty") {
                Notice(LocalAppLanguage.current.text(LocalText("No floors on this page.", "لا توجد قاعات في هذه الصفحة.")))
            }
            items(body.floors, key = { "food-floor-${it.id.value}" }) { floor ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FloorDefinition(floor)
                    OutlinedButton(onClick = { controller.selectFood(LocalFoodUiQuery.FloorTables(floor.id, assignment = assignment), token) },
                        enabled = browse && (assignment == null || floor.active), modifier = Modifier.touchTarget()) {
                        Text(LocalAppLanguage.current.text(if (assignment == null && controller.foodTableAssignmentsOnly)
                            LocalText("Browse tables in this floor", "تصفح طاولات هذه القاعة") else if (assignment == null)
                            LocalText("Open floor configuration and tables", "فتح إعداد القاعة وطاولاتها") else
                            LocalText("Choose a table in this floor", "اختيار طاولة في هذه القاعة")))
                    }
                }
            }
        }
        is LocalFoodUiBody.Tables -> {
            item("food-floor-definition") { FloorDefinition(body.floor) }
            if (body.tables.isEmpty()) item("food-table-empty") {
                Notice(LocalAppLanguage.current.text(LocalText("No tables on this page.", "لا توجد طاولات في هذه الصفحة.")))
            }
            items(body.tables, key = { "food-table-${it.id.value}" }) { table ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TableDefinition(table)
                    OutlinedButton(onClick = { controller.selectFood(LocalFoodUiQuery.TableDefinition(table.id, assignment), token) },
                        enabled = browse && (assignment == null || body.floor.active && table.active), modifier = Modifier.touchTarget()) {
                        Text(LocalAppLanguage.current.text(if (assignment == null)
                            LocalText("Open table definition", "فتح تعريف الطاولة") else
                            LocalText("Review destination table", "مراجعة الطاولة الوجهة")))
                    }
                }
            }
        }
        is LocalFoodUiBody.Table -> item("food-selected-table") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FloorDefinition(body.floor)
                TableDefinition(body.table)
                OutlinedButton(onClick = { controller.selectFood(LocalFoodUiQuery.FloorTables(body.floor.id, assignment = assignment), token) },
                    enabled = browse, modifier = Modifier.touchTarget()) {
                    Text(LocalAppLanguage.current.text(LocalText("Back to floor tables", "العودة إلى طاولات القاعة")))
                }
            }
        }
        else -> error("food.floor.body.invalid")
    }
}

@Composable
private fun FloorDefinition(floor: LocalFoodUiFloor) {
    val language = LocalAppLanguage.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(language.text(floor.names), style = MaterialTheme.typography.titleLarge)
        Text(language.text(LocalText("Floor ID: ${floor.id.value}", "معرف القاعة: ${floor.id.value}")))
        ConfigurationState(floor.storageRevision.value, floor.layoutOrder, floor.active)
    }
}

@Composable
private fun TableDefinition(table: LocalFoodUiTable) {
    val language = LocalAppLanguage.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(language.text(table.names), style = MaterialTheme.typography.titleMedium)
        Text(language.text(LocalText("Table ID: ${table.id.value}", "معرف الطاولة: ${table.id.value}")))
        ConfigurationState(table.storageRevision.value, table.layoutOrder, table.active)
    }
}

@Composable
private fun ConfigurationState(revision: Long, order: Int, active: Boolean) {
    val language = LocalAppLanguage.current
    Text(language.text(if (active) LocalText("Enabled configuration", "إعداد مُفعّل")
        else LocalText("Disabled configuration", "إعداد مُعطّل")), style = MaterialTheme.typography.labelLarge)
    Text(language.text(LocalText("Storage revision $revision · layout order $order",
        "مراجعة التخزين $revision · ترتيب المخطط $order")), style = MaterialTheme.typography.bodySmall)
}
