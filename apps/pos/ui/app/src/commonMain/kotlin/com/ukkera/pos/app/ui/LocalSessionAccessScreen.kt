package com.ukkera.pos.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ukkera.pos.app.UkkeraRuntime
import com.ukkera.pos.app.LocalSessionWorkspaceMode
import com.ukkera.pos.app.LocalText
import com.ukkera.pos.app.navigation.NavigationAction
import com.ukkera.pos.app.navigation.NavigationToken

/** No business gateway, pending-result read or private editor is acquired by this status view. */
@Composable
internal fun LocalSessionAccessScreen(runtime: UkkeraRuntime, token: NavigationToken) {
    val presentation by runtime.presentation.collectAsState()
    val mode = presentation.display?.workspaceMode
    val language = LocalAppLanguage.current
    val heading = when (mode) {
        LocalSessionWorkspaceMode.AUTHENTICATION_ONLY -> copy(Copy.AUTHENTICATION_ONLY)
        LocalSessionWorkspaceMode.SUPPLIER_ONLY -> language.text(LocalText("Supplier-only local connection", "اتصال محلي للموردين فقط"))
        LocalSessionWorkspaceMode.INVOICE_CAPTURE_ONLY -> language.text(LocalText("Local supplier invoice capture connection", "اتصال محلي بحفظ فواتير الموردين"))
        LocalSessionWorkspaceMode.INVOICE_MATCHING_ONLY -> language.text(LocalText("Local captured-invoice matching connection", "اتصال محلي بمطابقة الفواتير المحفوظة"))
        LocalSessionWorkspaceMode.WAREHOUSE_ONLY -> language.text(LocalText("Warehouse-only local connection", "اتصال محلي للمخازن فقط"))
        LocalSessionWorkspaceMode.REPORTS_ONLY -> language.text(LocalText("Reports-only local connection", "اتصال محلي للتقارير فقط"))
        LocalSessionWorkspaceMode.STOCK_TRANSFERS_ONLY -> language.text(LocalText("Local stock-transfer connection", "اتصال محلي لنقل المخزون"))
        LocalSessionWorkspaceMode.KITCHEN_ONLY -> language.text(LocalText("Local kitchen station connection", "اتصال محلي بمحطات المطبخ"))
        LocalSessionWorkspaceMode.RETAIL_ORDERS_ONLY -> language.text(LocalText("Local retail order connection", "اتصال محلي بطلبات التجزئة"))
        LocalSessionWorkspaceMode.PAYMENT_CORRECTIONS_ONLY -> language.text(LocalText("Local historical payment correction connection", "اتصال محلي بتصحيح تصنيف المدفوعات التاريخية"))
        LocalSessionWorkspaceMode.CUSTOMER_RECEIPTS_ONLY -> language.text(LocalText("Local counter goods-receipt connection", "اتصال محلي باستلام البضاعة من المنضدة"))
        LocalSessionWorkspaceMode.FOOD_CHECKS_ONLY -> language.text(LocalText("Local unpaid food-check connection", "اتصال محلي بطلبات الطعام غير المدفوعة"))
        LocalSessionWorkspaceMode.FOOD_CASH_FINANCIAL_ONLY -> language.text(LocalText("Local food financial and original-recovery connection", "اتصال محلي بالإجراءات المالية للطعام واسترداد النتائج الأصلية"))
        LocalSessionWorkspaceMode.FOOD_TABLE_ASSIGNMENTS_ONLY -> language.text(LocalText("Local table-assignment connection", "اتصال محلي بتعيين الطاولات"))
        else -> copy(Copy.SESSION_ACCESS)
    }
    val body = when (mode) {
        LocalSessionWorkspaceMode.AUTHENTICATION_ONLY -> copy(Copy.AUTHENTICATION_ONLY_BODY)
        LocalSessionWorkspaceMode.SUPPLIER_ONLY -> language.text(LocalText(
            "This connection installs only the supplier directory and its saved original operations. Current Master permission is required separately for reads, supplier changes and original-result recovery. Receiving, invoices, stock, payments, administration and reports are not installed. This status view does not read or replace pending history.",
            "يوفر هذا الاتصال دليل الموردين وعملياته الأصلية المحفوظة فقط. يلزم إذن ساري من الجهاز الرئيسي لكل من القراءة وتعديل الموردين واستعادة النتيجة الأصلية على حدة. لا يوفر الاستلام أو الفواتير أو المخزون أو المدفوعات أو الإدارة أو التقارير. لا يقرأ عرض الحالة هذا سجل العمليات المعلقة ولا يستبدله."))
        LocalSessionWorkspaceMode.INVOICE_CAPTURE_ONLY -> language.text(LocalText(
            "This connection provides invoice claim capture, existing invoice and purchase-order context, supplier/SKU selection and recovery of saved capture results. Claims remain unmatched and unapproved. Reads, costs, capture and original-result recovery each require separate current Master permission. Receiving, approval, matching, payments and configuration changes are unavailable. Other saved work remains with its original owner for its compatible workflow; this status view does not read or replace pending history.",
            "يوفر هذا الاتصال حفظ مطالبة الفاتورة وسياق الفواتير وأوامر الشراء الموجودة واختيار المورد والأصناف واسترداد نتائج الحفظ الأصلية. تبقى المطالبات غير مطابقة وغير معتمدة. تتطلب القراءة والتكلفة والحفظ واسترداد النتيجة الأصلية صلاحيات حالية مستقلة من الجهاز الرئيسي. لا يتوفر الاستلام أو الاعتماد أو المطابقة أو المدفوعات أو تغيير الإعدادات. تبقى الأعمال الأخرى المحفوظة مع مالكها الأصلي لمسارها المتوافق؛ ولا يقرأ عرض الحالة هذا السجل المعلق ولا يستبدله."))
        LocalSessionWorkspaceMode.INVOICE_MATCHING_ONLY -> language.text(LocalText(
            "This connection provides captured-invoice matching, recognition of exact eligible accepted goods, invoice/PO reads and recovery of saved matching results. Each read, cost view, matching operation and original-result recovery requires its own current Master permission. Matching authority covers the full original supplier claim, not just the selected quantity. Original claims are unchanged; no stock or money moves. Capture, legacy matching, credit, settlement, configuration and directory discovery are unavailable. Other saved work remains with its original owner for its compatible workflow; this status view does not read or replace pending history.",
            "يوفر هذا الاتصال مطابقة الفواتير المحفوظة وإثبات مستحق البضاعة المقبولة المؤهلة الدقيقة وقراءة الفواتير وأوامر الشراء واسترداد نتائج المطابقة الأصلية. تتطلب كل قراءة وعرض تكلفة وعملية مطابقة واسترداد نتيجة أصلية إذنًا حاليًا خاصًا من الجهاز الرئيسي. تشمل صلاحية المطابقة مطالبة المورد الأصلية كاملة، لا الكمية المختارة فقط. تبقى المطالبات الأصلية كما هي؛ ولا يتحرك مخزون أو مال. لا يتوفر الحفظ أو المطابقة القديمة أو إشعارات الدائن أو التسوية أو الإعداد أو اكتشاف الدليل. تبقى الأعمال الأخرى المحفوظة مع مالكها الأصلي لمسارها المتوافق؛ ولا يقرأ عرض الحالة هذا السجل المعلق ولا يستبدله."))
        LocalSessionWorkspaceMode.WAREHOUSE_ONLY -> language.text(LocalText(
            "This connection provides the warehouse directory, creation and renaming, and recovery of saved original results. Reads, changes and result recovery each require current Master permission. Warehouse retirement requires a separate procedure. Saved operations remain with their original owner.",
            "يوفر هذا الاتصال دليل المخازن وإنشاءها وإعادة تسميتها واستعادة النتائج الأصلية المحفوظة. تتطلب القراءة والتعديل واستعادة النتيجة إذنًا ساريًا من الجهاز الرئيسي لكل منها. يتطلب إيقاف المخزن إجراءً منفصلًا. تبقى العمليات المحفوظة مع مالكها الأصلي."))
        LocalSessionWorkspaceMode.REPORTS_ONLY -> language.text(LocalText(
            "This connection provides existing current-document report pages and local PAGE or complete-current-section CSV export with original audit recovery. Each read, export and recovery requires its own current Master authority. Native file delivery must be available. It does not provide period totals, alerts, other business workflows or a Master-certified file.",
            "يوفر هذا الاتصال صفحات التقارير الموجودة للمستندات الحالية وتصدير CSV محليًا لصفحة أو لقسم حالي كامل مع استرداد سجل التدقيق الأصلي. تتطلب كل قراءة وتصدير واسترداد صلاحية سارية خاصة بها من الجهاز الرئيسي. يجب توفر تسليم الملفات الأصلي. لا يوفر إجماليات الفترات أو التنبيهات أو مسارات عمل تجارية أخرى أو ملفًا مصدقًا من الجهاز الرئيسي."))
        LocalSessionWorkspaceMode.STOCK_TRANSFERS_ONLY -> language.text(LocalText(
            "This connection provides same-branch transfers of available, safe, released company stock and recovery of saved original results. Quantity, cost, transfer and original-result recovery require separate current Master permissions. Saved requests keep their original actor, device, branch and exact operation.",
            "يوفر هذا الاتصال النقل داخل الفرع لمخزون الشركة المتاح والآمن والمفرج عنه واسترداد النتائج الأصلية المحفوظة. تتطلب قراءة الكميات والتكلفة والنقل واسترداد النتيجة الأصلية صلاحيات سارية مستقلة من الجهاز الرئيسي. تحتفظ الطلبات المحفوظة بالموظف والجهاز والفرع والعملية الأصلية الدقيقة."))
        LocalSessionWorkspaceMode.KITCHEN_ONLY -> language.text(LocalText(
            "This connection provides station discovery, protected task boards, Start and Ready review, and recovery of saved original results. Discovery, exact station access and original-result recovery require separate current Master permissions. Saved requests keep their original actor, device, branch, body and operation.",
            "يوفر هذا الاتصال اكتشاف المحطات ولوحات المهام المحمية ومراجعة بدء التحضير والجاهزية واسترداد النتائج الأصلية المحفوظة. يتطلب الاكتشاف والوصول إلى المحطة المحددة واسترداد النتيجة الأصلية صلاحيات حالية مستقلة من الجهاز الرئيسي. تحتفظ الطلبات المحفوظة بالموظف والجهاز والفرع والمحتوى والعملية الأصلية."))
        LocalSessionWorkspaceMode.FOOD_TABLE_ASSIGNMENTS_ONLY -> language.text(LocalText(
            "This connection provides your own check, its managed table binding, the protected floor/table directory and explicit assignment or movement. Read, review and save the exact assignment before submitting separately; recover and acknowledge its original result. Current Master check, directory, edit and recovery permissions remain separate. Saved requests keep their original actor, device, branch, body and operation.",
            "يوفر هذا الاتصال طلبك وربط طاولته المُدارة ودليل القاعات والطاولات المحمي والتعيين أو النقل الصريح. اقرأ التعيين الدقيق وراجعه واحفظه قبل الإرسال في خطوة منفصلة، ثم استعد نتيجته الأصلية وأكد استلامها. تبقى صلاحيات قراءة الطلب والدليل والتعديل والاسترداد الحالية من الجهاز الرئيسي مستقلة. تحتفظ الطلبات المحفوظة بالموظف والجهاز والفرع والمحتوى والعملية الأصلية."))
        LocalSessionWorkspaceMode.FOOD_CHECKS_ONLY -> language.text(LocalText(
            "This connection provides your own checks, empty unpaid drafts, quoted menu additions and recovery of saved original results. Each read, Draft, Add and original-result recovery requires its own current Master permission and source checks. Kitchen sending availability is separate. Saved requests retain their original actor, device, branch, body and operation.",
            "يوفر هذا الاتصال طلباتك والمسودات الفارغة غير المدفوعة وإضافات القائمة بعرض سعر واسترداد النتائج الأصلية المحفوظة. تتطلب كل قراءة ومسودة وإضافة واسترداد نتيجة أصلية الإذن الحالي وفحص المصادر الخاصين بها من الجهاز الرئيسي. توفر الإرسال للمطبخ مستقل. تحتفظ الطلبات المحفوظة بالموظف والجهاز والفرع والمحتوى والعملية الأصلية."))
        LocalSessionWorkspaceMode.FOOD_CASH_FINANCIAL_ONLY -> language.text(LocalText(
            "This explicitly selected new connection provides owned checks and collections, genuine payment-original and attempt reads, BeginCollection, ApplyOriginal, FinalizePaid and recovery of saved original results. Paid receipts require their separate scoped permission. Every read, monetary operation and result disclosure needs current Master authority. It does not upgrade FOOD_CHECKS, create drafts, Add, Send, refund, resolve UNKNOWN or provide native physical Start instructions. Saved work retains its original actor, branch, body and operation; unavailable history cannot be acknowledged or erased. This status view does not read or change pending history.",
            "يوفر هذا الاتصال الجديد المختار صراحةً الطلبات والتحصيلات المملوكة وقراءة واقعة الدفع الأصلية والمحاولة من مصادرها الفعلية وبدء التحصيل وتطبيق الواقعة الأصلية والإتمام المدفوع واسترداد نتائج العمليات الأصلية المحفوظة. تتطلب الإيصالات المدفوعة إذنها المحدد المستقل. تحتاج كل قراءة وإجراء مالي وإفصاح عن نتيجة إلى صلاحية حالية من الجهاز الرئيسي. لا يرقي FOOD_CHECKS ولا ينشئ مسودة أو يضيف أو يرسل للمطبخ أو يرد الأموال أو يحسم UNKNOWN أو يوفر تعليمات بدء فعلي من المصدر الأصلي. يحتفظ العمل المحفوظ بالموظف والفرع والمحتوى والعملية الأصلية؛ ولا يمكن تأكيد استلام سجل غير متاح أو مسحه. لا يقرأ عرض الحالة هذا السجل المعلق ولا يغيره."))
        LocalSessionWorkspaceMode.CUSTOMER_RECEIPTS_ONLY -> language.text(LocalText(
            "This connection records actual counter receipt of goods from a known original paid retail sale and recovers saved original observations. Returns provides separately authorized read-only evidence. Each read, record and recovery needs current Master permission; original collector ownership is enforced by the Master. Payment, printing, stock changes and return qualification are not installed.",
            "يسجل هذا الاتصال استلام البضاعة الفعلي من المنضدة لبيع تجزئة أصلي مدفوع معلوم ويسترد الملاحظات الأصلية المحفوظة. تعرض المرتجعات دليلًا للقراءة فقط بإذن مستقل. تتطلب كل قراءة وتسجيل واسترداد صلاحية حالية من الجهاز الرئيسي الذي يفرض ملكية المحصل الأصلي. لا يوفر الدفع أو الطباعة أو تعديل المخزون أو تأهيل المرتجعات."))
        LocalSessionWorkspaceMode.RETAIL_ORDERS_ONLY -> language.text(LocalText(
            "This connection provides SKU selection, your unlocked quoted orders, editing and recovery of saved original results. It does not collect payment or complete a sale. Catalog, order and original-result access require separate current Master permissions; saved requests keep their exact original identity.",
            "يوفر هذا الاتصال اختيار الأصناف وطلباتك غير المقفلة بعروض أسعارها وتحريرها واسترداد النتائج الأصلية المحفوظة. لا يحصّل المدفوعات ولا يُتم البيع. يتطلب الوصول إلى الكتالوج والطلبات والنتائج الأصلية صلاحيات حالية مستقلة من الجهاز الرئيسي؛ وتحافظ الطلبات المحفوظة على هويتها الأصلية الدقيقة."))
        LocalSessionWorkspaceMode.PAYMENT_CORRECTIONS_ONLY -> language.text(LocalText(
            "Enter payment-correction:SALE_UUID to review an original payment and its linked classifications. History reads, historical classification changes and original-result recovery require separate current Master permissions. Select the original payment before reviewing a correction. Saved requests keep their exact original identity; payment, receipt and submitted close remain unchanged.",
            "أدخل payment-correction:SALE_UUID لمراجعة دفع أصلي وتصنيفاته المرتبطة. تتطلب قراءة السجل وتعديل التصنيف التاريخي واسترداد النتيجة الأصلية صلاحيات حالية مستقلة من الجهاز الرئيسي. اختر الدفع الأصلي قبل مراجعة التصحيح. تحتفظ الطلبات المحفوظة بهويتها الأصلية الدقيقة؛ ويبقى الدفع والإيصال والإغلاق المقدم دون تغيير."))
        else -> copy(Copy.SESSION_ACCESS_BODY)
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionHeading(heading)
        Notice(body, caution = true,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Text(copy(Copy.SESSION_ACCESS_RECOVERY))
        OutlinedButton(onClick = { runtime.refreshLocalSession(token) },
            enabled = !presentation.refreshingSession, modifier = Modifier.touchTarget()) {
            Text(copy(Copy.RENEW_SESSION))
        }
        OutlinedButton(onClick = { runtime.navigate(NavigationAction.ExitWorkspace, token) },
            modifier = Modifier.touchTarget()) { Text(copy(Copy.SIGN_OUT)) }
    }
}
