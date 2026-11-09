package com.ukkera.pos.app.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ukkera.pos.app.ui.Notice
import com.ukkera.pos.security.LocalPairedDeviceCredentialJournalAction
import com.ukkera.pos.app.ui.ReviewField
import com.ukkera.pos.app.ui.SectionHeading
import com.ukkera.pos.app.ui.touchTarget

/** Explicit transport/profile review only. The retained setup owner validates and owns attachment. */
@Composable
internal fun LocalSetupLanSection(controller: LocalSetupController, view: LocalSetupView) {
    if (view.locked) return
    val lan = view.lan
    val role = lan.role ?: when {
        view.freshDevicePairing?.role == LocalSetupFreshDevicePairingRole.FRESH_TARGET -> LocalSetupLanRole.CLIENT
        view.freshDevicePairing?.role == LocalSetupFreshDevicePairingRole.ORIGINAL_MASTER -> LocalSetupLanRole.HOST
        lan.clientAvailable -> LocalSetupLanRole.CLIENT
        lan.hostAvailable || view.phase == LocalSetupPhase.INSTALLATION_RETAINED -> LocalSetupLanRole.HOST
        else -> null
    }
    val isClient = role == LocalSetupLanRole.CLIENT
    val available = when (role) {
        LocalSetupLanRole.CLIENT -> lan.clientAvailable
        LocalSetupLanRole.HOST -> lan.hostAvailable
        null -> false
    }
    val idle = view.work == null && view.phase != LocalSetupPhase.CLOSING && view.phase != LocalSetupPhase.CLOSED
    val mayBegin = idle && view.problem != LocalSetupProblem.CLOSE_UNCONFIRMED &&
        lan.status == LocalSetupLanStatus.NOT_ATTACHED && available
    var subnets by remember(controller, role) { mutableStateOf("") }
    var interfaceName by remember(controller, role) { mutableStateOf("") }
    var localAddress by remember(controller, role) { mutableStateOf("") }
    var listenPort by remember(controller, role) { mutableStateOf("") }
    var hostAddress by remember(controller, role) { mutableStateOf("") }
    var hostPort by remember(controller, role) { mutableStateOf("") }
    var workflow by remember(controller, role) { mutableStateOf(LocalSetupLanWorkflow.AUTHENTICATION_ONLY) }
    var credentialAction by remember(controller, role) { mutableStateOf<LocalPairedDeviceCredentialJournalAction?>(null) }
    var actorIntentAction by remember(controller, role) { mutableStateOf<LocalPairedDeviceCredentialJournalAction?>(null) }
    var reviewAttempted by remember(controller, role, subnets, interfaceName, localAddress, listenPort, hostAddress, hostPort, workflow, credentialAction, actorIntentAction) {
        mutableStateOf(false)
    }
    var reviewed by remember(controller, role, subnets, interfaceName, localAddress, listenPort, hostAddress, hostPort, workflow, credentialAction, actorIntentAction) {
        mutableStateOf(false)
    }
    val parsedListenPort = lanPort(listenPort, 0)
    val parsedHostPort = lanPort(hostPort, 1)
    val businessWorkflow = workflow != LocalSetupLanWorkflow.AUTHENTICATION_ONLY
    val input = if (subnets.isNotEmpty() && interfaceName.isNotEmpty() && localAddress.isNotEmpty() && parsedListenPort != null)
        LocalSetupLanInput(subnets.split('\n'), interfaceName, localAddress, parsedListenPort,
            credentialAction = if (isClient) credentialAction else null,
            workflow = if (isClient) workflow else LocalSetupLanWorkflow.AUTHENTICATION_ONLY,
            actorIntentAction = if (isClient && businessWorkflow) actorIntentAction else null) else null
    val endpoint = if (hostAddress.isNotEmpty() && parsedHostPort != null)
        LocalSetupLanEndpoint(hostAddress, parsedHostPort) else null
    val custodyComplete = !isClient || !businessWorkflow ||
        credentialAction != null && actorIntentAction != null
    val complete = input != null && (!isClient || endpoint != null) && custodyComplete

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeading(t("Explicit branch LAN attachment", "اتصال صريح بالشبكة المحلية للفرع"))
        ReviewField(t("Transport role", "دور الاتصال"), when (role) {
            LocalSetupLanRole.CLIENT -> t("Client to the selected original Master", "عميل للجهاز الرئيسي الأصلي المختار")
            LocalSetupLanRole.HOST -> t("Host on the existing operational Master", "مضيف على الجهاز الرئيسي التشغيلي الموجود")
            null -> t("Original device role unavailable", "دور الجهاز الأصلي غير متاح")
        }, code = false)
        Notice(lanStatusText(lan.status), caution = lan.status in setOf(LocalSetupLanStatus.UNAVAILABLE, LocalSetupLanStatus.UNKNOWN),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        lan.endpoint?.let { observed ->
            ReviewField(if (isClient) t("Retained selected Host address", "عنوان المضيف المختار المحفوظ")
                else t("Observed listener address", "عنوان الاستماع المرصود"), observed.address)
            ReviewField(if (isClient) t("Retained selected Host port", "منفذ المضيف المختار المحفوظ")
                else t("Observed assigned listener port", "منفذ الاستماع المخصص المرصود"), observed.port.toString())
        }
        if (lan.authenticationAvailable) Notice(t(
            "Employee authentication is attached. Sign in through the application; business access still requires a qualified workflow profile and current Master permissions.",
            "تم وصل خدمة مصادقة الموظفين. سجّل الدخول من التطبيق؛ يظل الوصول إلى الأعمال مشروطًا بملف سير عمل متحقق منه وصلاحيات حالية من الجهاز الرئيسي."))
        Notice(t("LAN transport does not sign in an employee, admit a device, grant permissions or enable business operations. Pairing approval and protected access remain separate.",
            "لا يسجّل اتصال الشبكة المحلية دخول موظف، ولا يقبل جهازًا أو يمنح صلاحيات أو يتيح العمليات التجارية. يظل اعتماد الاقتران والوصول المحمي خطوات منفصلة."))
        if (lan.status == LocalSetupLanStatus.NOT_ATTACHED && !available) {
            Notice(when (role) {
                LocalSetupLanRole.CLIENT -> t(
                    "Client attachment requires this exact retained fresh target and its native-verified original Master approval.",
                    "يتطلب اتصال العميل هذا الهدف الجديد المحفوظ بعينه واعتماد جهازه الرئيسي الأصلي المتحقق منه أصليًا.")
                LocalSetupLanRole.HOST -> t(
                    "Host attachment requires the retained operational Master opened from its existing installation. A fresh installation's initial lifetime is unavailable for this action.",
                    "يتطلب اتصال المضيف الجهاز الرئيسي التشغيلي المحفوظ المفتوح من تثبيته الموجود. لا تتاح هذه الخطوة خلال دورة التشغيل الأولى لتثبيت جديد.")
                null -> t("Read this device's retained original setup before selecting a LAN attachment.",
                    "اقرأ الإعداد الأصلي المحفوظ لهذا الجهاز قبل اختيار اتصال بالشبكة المحلية.")
            }, caution = true)
        }
        if (lan.status == LocalSetupLanStatus.NOT_ATTACHED && available) {
            Notice(t("Enter the exact approved private CIDRs, physical interface and numeric private local address. No subnet, interface, address or port is selected for you. Entered text is submitted unchanged; invalid network values are refused.",
                "أدخل نطاقات CIDR الخاصة المعتمدة والواجهة الفعلية والعنوان المحلي الخاص الرقمي بدقة. لا يُختار لك نطاق أو واجهة أو عنوان أو منفذ. يُرسل النص المدخل كما هو وتُرفض قيم الشبكة غير الصحيحة."))
            OutlinedTextField(subnets, { subnets = it }, enabled = mayBegin, minLines = 2, maxLines = 6,
                label = { Text(t("Approved private CIDRs: one exact CIDR per line (1–64)", "نطاقات CIDR الخاصة المعتمدة: نطاق دقيق في كل سطر (1–64)")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth())
            LanTextInput(interfaceName, { interfaceName = it }, t("Exact physical interface name", "اسم الواجهة الفعلية الدقيق"), mayBegin)
            LanTextInput(localAddress, { localAddress = it }, t("Numeric private local address", "العنوان المحلي الخاص الرقمي"), mayBegin)
            LanPortInput(listenPort, { listenPort = it }, t("Local listener port (0–65535)", "منفذ الاستماع المحلي (0–65535)"), mayBegin,
                invalid = (listenPort.isNotEmpty() || reviewAttempted) && parsedListenPort == null,
                hint = t("Enter ASCII digits only, 0–65535. Explicit 0 requests an assigned port; an invalid value never becomes 0.",
                    "أدخل أرقام ASCII فقط من 0 إلى 65535. يطلب الصفر المدخل صراحةً منفذًا مخصصًا؛ ولا تتحول قيمة غير صحيحة إلى صفر."))
            if (isClient) {
                Notice(t("Enter the selected original Master's numeric Host endpoint in the approved LAN. Its address is transport data; it cannot replace the retained native Master approval.",
                    "أدخل نقطة اتصال المضيف الرقمية للجهاز الرئيسي الأصلي المختار ضمن الشبكة المحلية المعتمدة. عنوانه بيانات اتصال ولا يستبدل اعتماد الجهاز الرئيسي الأصلي المحفوظ."))
                LanTextInput(hostAddress, { hostAddress = it }, t("Selected Host numeric address", "العنوان الرقمي للمضيف المختار"), mayBegin)
                LanPortInput(hostPort, { hostPort = it }, t("Selected Host port (1–65535)", "منفذ المضيف المختار (1–65535)"), mayBegin,
                    invalid = (hostPort.isNotEmpty() || reviewAttempted) && parsedHostPort == null,
                    hint = t("Enter ASCII digits only, 1–65535. The selected Host port cannot be 0.",
                        "أدخل أرقام ASCII فقط من 1 إلى 65535. لا يمكن أن يكون منفذ المضيف المختار صفرًا."))
            }
            if (isClient) {
                SectionHeading(t("Explicit Client workflow", "سير عمل العميل المختار صراحةً"))
                for (choice in LocalSetupLanWorkflow.entries) OutlinedButton(onClick = { workflow = choice }, enabled = mayBegin,
                    modifier = Modifier.fillMaxWidth().touchTarget()) {
                    Text((if (workflow == choice) "✓ " else "") + lanWorkflowText(choice))
                }
                Notice(when (workflow) {
                    LocalSetupLanWorkflow.INVOICE_CAPTURE -> t(
                        "Supplier invoice capture only: retain the supplier's actual claim as unmatched and unapproved, with optional existing purchase-order context. Read, review and save before submitting separately; recover and acknowledge the same original result. Select independent device and employee custody actions and sign in freshly. Each read, capture and recovery requires current Master permission. Receiving, approval, matching, payments and configuration changes are unavailable on this connection.",
                        "حفظ فاتورة المورد فقط: احفظ مطالبة المورد الفعلية كغير مطابقة وغير معتمدة، مع سياق اختياري لأمر شراء موجود. اقرأ وراجع واحفظ قبل الإرسال في خطوة منفصلة؛ واستعد النتيجة الأصلية نفسها وأكد استلامها. اختر إجراءي حفظ مستقلين للجهاز والموظف وسجل الدخول مجددًا. تتطلب كل قراءة وحفظ واسترداد إذنًا حاليًا من الجهاز الرئيسي. لا يوفر هذا الاتصال الاستلام أو الاعتماد أو المطابقة أو المدفوعات أو تغيير الإعدادات.")
                    LocalSetupLanWorkflow.INVOICE_MATCHING -> t(
                        "Captured invoice matching only: select exact eligible accepted goods against the original invoice, approved purchase order and receipt. Review and save, then submit separately; recover and acknowledge the same original result. Select independent device and employee custody actions and sign in freshly. Each read, matching operation and recovery requires current Master permission; matching authority covers the full original claim. This does not alter the original, move stock or pay the supplier. Capture, legacy matching, credit, settlement, configuration and supplier/SKU discovery are unavailable.",
                        "مطابقة الفاتورة المحفوظة فقط: اختر بضاعة مقبولة مؤهلة دقيقة مقابل الفاتورة الأصلية وأمر الشراء المعتمد والاستلام. راجع واحفظ ثم أرسل في خطوة منفصلة؛ واستعد النتيجة الأصلية نفسها وأكد استلامها. اختر إجراءي حفظ مستقلين للجهاز والموظف وسجل الدخول مجددًا. تتطلب كل قراءة وعملية مطابقة واسترداد إذنًا حاليًا من الجهاز الرئيسي؛ وتشمل صلاحية المطابقة المطالبة الأصلية كاملة. لا يغير ذلك الأصل ولا ينقل مخزونًا ولا يدفع للمورد. لا يتوفر الحفظ أو المطابقة القديمة أو إشعارات الدائن أو التسوية أو الإعداد أو اكتشاف الموردين والأصناف.")
                    LocalSetupLanWorkflow.FOOD_TABLE_ASSIGNMENTS -> t(
                        "Table assignment: read your own check, its current managed binding and the protected floor/table directory. Select an active destination, review and save the exact assignment, then submit separately. Recover and acknowledge the original result before another change. Each step requires current Master permission; browsing changes nothing.",
                        "تعيين الطاولة: اقرأ طلبك وربطه المُدار الحالي ودليل القاعات والطاولات المحمي. اختر وجهة مفعّلة وراجع التعيين الدقيق واحفظه، ثم أرسله في خطوة منفصلة. استعد النتيجة الأصلية وأكد استلامها قبل تعديل آخر. تتطلب كل خطوة إذنًا حاليًا من الجهاز الرئيسي؛ التصفح لا يغير شيئًا.")
                    LocalSetupLanWorkflow.FOOD_CASH_FINANCIAL -> t(
                        "FOOD CASH financial and original recovery only: lock an original quote, apply an already retained original CASH fact and finalize actual stored coverage. Review and save before separate Submit; recover and acknowledge the same original result. Choose independent device and employee custody actions and a new connection with fresh sign-in. This does not upgrade an existing food-check connection. Cash capture, native instructions, Send, refunds and UNKNOWN resolution are unavailable. Every read and operation retains its separate current Master permissions.",
                        "مالية نقدية الطعام واسترداد الأصل فقط: اقفل تسعيرًا أصليًا وطبق إفادة نقدية أصلية محفوظة وثبت التغطية المخزنة الفعلية. راجع واحفظ قبل الإرسال في خطوة منفصلة؛ واستعد النتيجة الأصلية نفسها وأكد استلامها. اختر إجراءي حفظ مستقلين للجهاز والموظف واتصالًا جديدًا مع تسجيل دخول جديد. لا يرقّي ذلك اتصال طلبات الطعام الموجود. لا يتاح التحصيل الفعلي أو التعليمات أو إرسال الطعام أو رد المبالغ أو حسم النتائج المجهولة. تبقى لكل قراءة وعملية صلاحياتها الحالية المستقلة من الجهاز الرئيسي.")
                    LocalSetupLanWorkflow.FOOD_CHECKS -> t(
                        "Food checks: browse your own checks, open an empty unpaid draft and add quoted menu selections to an exact check. Review, Stage and Submit separately, then recover and acknowledge the saved original result. Select separate device and employee custody actions and sign in freshly. Draft/Add need current Master permission and the branch's restaurant or café activity. Kitchen availability is checked separately for sending.",
                        "طلبات الطعام: تصفح طلباتك وافتح مسودة فارغة غير مدفوعة وأضف اختيارات القائمة بعرض سعر إلى طلب محدد. راجع الطلب واحفظه وأرسله في خطوات منفصلة، ثم استعد نتيجته الأصلية المحفوظة وأكد استلامها. اختر إجراءي حفظ مستقلين للجهاز والموظف وسجل الدخول مجددًا. تتطلب المسودة والإضافة إذنًا حاليًا من الجهاز الرئيسي ونشاط المطعم أو المقهى المطبق بالفرع. يُتحقق من توفر المطبخ بصورة مستقلة قبل الإرسال للتحضير.")
                    LocalSetupLanWorkflow.CUSTOMER_RECEIPTS -> t(
                        "Counter goods receipt: enter customer-receipt:SALE_UUID for a known original paid retail sale. Record only actual observed quantities, UTC time and Cairo confirmation; separately Stage, Submit and recover the original request. Returns provides read-only original observations, not qualification or refunds. Select independent device and employee custody actions and sign in freshly. Current Master permission and original collector ownership remain required.",
                        "استلام البضاعة من المنضدة: أدخل customer-receipt:SALE_UUID لبيع تجزئة أصلي مدفوع معلوم. سجل فقط الكميات المرصودة فعليًا والوقت بتوقيت UTC وتأكيد القاهرة، ثم احفظ الطلب الأصلي وأرسله واستعده في خطوات منفصلة. تعرض المرتجعات الملاحظات الأصلية للقراءة فقط، دون تأهيل أو رد أموال. اختر إجراءي حفظ مستقلين للجهاز والموظف وسجل الدخول مجددًا. تبقى صلاحية الجهاز الرئيسي الحالية وملكية المحصل الأصلي مطلوبة.")
                    LocalSetupLanWorkflow.PAYMENT_CORRECTIONS -> t(
                        "Historical payment corrections: enter payment-correction:SALE_UUID, review the original payment and submitted close, then select the payment's linked history. Review its exact classification, reason and evidence before separately saving and submitting the original request. Select independent device and employee custody actions and sign in freshly. Current Master history, correction and original-result permissions remain required.",
                        "تصحيح تصنيف المدفوعات التاريخية: أدخل payment-correction:SALE_UUID وراجع الدفع الأصلي والإغلاق المقدم ثم اختر سجل الدفع المرتبط. راجع التصنيف والسبب والإثبات بدقة قبل حفظ الطلب الأصلي وإرساله في خطوتين منفصلتين. اختر إجراءي حفظ مستقلين للجهاز والموظف وسجل الدخول مجددًا. تبقى صلاحيات السجل والتصحيح والنتيجة الأصلية الحالية من الجهاز الرئيسي مطلوبة.")
                    LocalSetupLanWorkflow.RETAIL_ORDERS -> t(
                        "Retail orders: browse SKUs, create or edit your unlocked quote, then separately Stage and Submit the original request. Recover its original result. This does not collect money or complete a sale. Select separate device and employee custody actions and sign in freshly; current Master permissions remain required.",
                        "طلبات التجزئة: تصفح الأصناف وأنشئ عرض طلبك غير المقفل أو حرره، ثم احفظ الطلب الأصلي وأرسله في خطوتين منفصلتين واستعد نتيجته. لا يحصّل ذلك المال ولا يُتم البيع. اختر إجراءي حفظ مستقلين للجهاز والموظف وسجل الدخول مجددًا؛ وتظل صلاحيات الجهاز الرئيسي الحالية مطلوبة.")
                    LocalSetupLanWorkflow.KITCHEN -> t(
                        "Kitchen stations: view your permitted station board, review Start or Ready, then separately Stage and Submit the original request. Recover and acknowledge its original result. Select both device and employee custody actions, then sign in freshly. Discovery and exact station access require separate current Master permissions.",
                        "محطات المطبخ: اعرض لوحة المحطة المسموح لك بها وراجع بدء التحضير أو الجاهزية، ثم احفظ الطلب الأصلي وأرسله في خطوتين منفصلتين. استعد نتيجته الأصلية وأكد استلامها. اختر إجراءي حفظ الجهاز والموظف ثم سجل الدخول مجددًا. يتطلب الاكتشاف والوصول إلى المحطة المحددة صلاحيات حالية مستقلة من الجهاز الرئيسي.")
                    LocalSetupLanWorkflow.SUPPLIERS -> t(
                    "Suppliers only: bounded browse/detail, create/update and recovery of the same original result. An explicit custody action and genuine actor intent journal after fresh employee sign-in are required. Current Master permissions remain separate; other workflows stay unavailable.",
                    "الموردون فقط: تصفح وتفاصيل محدودة، وإنشاء وتحديث، واسترداد النتيجة الأصلية نفسها. يلزم إجراء حفظ صريح وسجل نوايا أصلي للموظف بعد تسجيل دخول جديد. تظل صلاحيات الجهاز الرئيسي الحالية مستقلة؛ ولا تتاح مسارات العمل الأخرى.")
                    LocalSetupLanWorkflow.WAREHOUSES -> t(
                        "Warehouses only: browse details, create or rename a warehouse, recover its original result and acknowledge it. Select both device and employee custody actions, then sign in freshly. Each operation requires its own current Master permission. Warehouse retirement requires a separate procedure.",
                        "المخازن فقط: تصفح التفاصيل وأنشئ مخزنًا أو أعد تسميته واستعد نتيجته الأصلية وأكد استلامها. اختر إجراءي حفظ الجهاز والموظف، ثم سجل الدخول مجددًا. تتطلب كل عملية إذنًا ساريًا خاصًا من الجهاز الرئيسي. يتطلب إيقاف المخزن إجراءً منفصلًا.")
                    LocalSetupLanWorkflow.REPORTS -> t(
                        "Reports only: browse existing current-document pages and explicitly export a PAGE or complete current section to this device's real local files, with original audit recovery. Select separate device and employee custody actions and sign in freshly. Current Master report/export permissions remain required. No period totals, alerts or other business workflows are installed.",
                        "التقارير فقط: تصفح الصفحات الموجودة للمستندات الحالية وصدّر صراحةً صفحة أو قسمًا حاليًا كاملًا إلى الملفات المحلية الأصلية لهذا الجهاز، مع استرداد سجل التدقيق الأصلي. اختر إجراءي حفظ مستقلين للجهاز والموظف وسجل الدخول مجددًا. تبقى صلاحيات التقارير والتصدير السارية من الجهاز الرئيسي مطلوبة. لا يوفر إجماليات الفترات أو التنبيهات أو مسارات العمل التجارية الأخرى.")
                    LocalSetupLanWorkflow.STOCK_TRANSFERS -> t(
                        "Stock transfers: select a current stock position and an active warehouse in this branch, enter quantity and reason, save the original request, then submit and recover its result. Select separate device and employee custody actions and sign in freshly. Quantity, cost, transfer and result recovery each need current Master permission.",
                        "نقل المخزون: اختر موضع مخزون حاليًا ومخزنًا نشطًا داخل هذا الفرع، وأدخل الكمية والسبب، واحفظ الطلب الأصلي ثم أرسله واستعد نتيجته. اختر إجراءي حفظ مستقلين للجهاز والموظف وسجل الدخول مجددًا. تتطلب قراءة الكمية والتكلفة والنقل واسترداد النتيجة إذنًا ساريًا لكل منها من الجهاز الرئيسي.")
                    LocalSetupLanWorkflow.AUTHENTICATION_ONLY -> t(
                    "Authentication only does not enable business RPC. With no credential custody action, this remains transport only.",
                    "لا يتيح اختيار المصادقة فقط عمليات الأعمال عبر RPC. ودون إجراء لحفظ بيانات الاعتماد، يبقى الاتصال للنقل فقط.")
                })
                SectionHeading(t("Device credential custody before Client transfer", "حفظ بيانات اعتماد الجهاز قبل نقل العميل"))
                val choices = listOf<LocalPairedDeviceCredentialJournalAction?>(null) + LocalPairedDeviceCredentialJournalAction.entries
                for (choice in choices) OutlinedButton(onClick = { credentialAction = choice }, enabled = mayBegin,
                    modifier = Modifier.fillMaxWidth().touchTarget()) {
                    Text((if (credentialAction == choice) "✓ " else "") + credentialActionText(choice))
                }
                Notice(t("Initialization is only for a pristine newly approved native device. Reopen never creates missing storage; recovery completes only original pending writes. No choice silently replaces credentials or history.",
                    "التهيئة لجهاز أصلي جديد ومعتمد لم يُستخدم فقط. لا تنشئ إعادة الفتح تخزينًا مفقودًا؛ ويكمل الاسترداد الكتابات الأصلية المعلقة فقط. لا يستبدل أي اختيار بيانات الاعتماد أو السجل تلقائيًا."))
                if (businessWorkflow) {
                    SectionHeading(t("Employee original-intent disposition", "إجراء سجل العمليات الأصلي للموظف"))
                    for (choice in LocalPairedDeviceCredentialJournalAction.entries) OutlinedButton(
                        onClick = { actorIntentAction = choice }, enabled = mayBegin,
                        modifier = Modifier.fillMaxWidth().touchTarget()) {
                        Text((if (actorIntentAction == choice) "✓ " else "") + actorIntentActionText(choice))
                    }
                    Notice(t(
                        "This is separate from device credentials. On a reopened device, explicitly enroll an employee with no recorded allocation, or reopen/recover that employee's existing original. Enrollment never replaces an existing allocation; missing or uncertain history is not pristine.",
                        "هذا الاختيار مستقل عن بيانات اعتماد الجهاز. على جهاز أُعيد فتحه، هيّئ صراحةً سجل موظف بلا تخصيص مسجل، أو أعد فتح أصل الموظف الموجود أو استرده. لا تستبدل التهيئة تخصيصًا موجودًا؛ والسجل المفقود أو غير المؤكد ليس سجلًا جديدًا."))
                }
            }
            if (reviewAttempted && !complete) Notice(if (!custodyComplete) t(
                "Select both device and employee custody actions before reviewing the business RPC workflow.",
                "اختر إجراءي حفظ الجهاز والموظف قبل مراجعة سير عمل الأعمال عبر RPC.") else t(
                "Enter every required LAN value and a valid numeric port before reviewing.",
                "أدخل كل قيم الشبكة المحلية المطلوبة ومنفذًا رقميًا صحيحًا قبل المراجعة."), caution = true,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
            OutlinedButton(onClick = { reviewAttempted = true; reviewed = complete }, enabled = mayBegin,
                modifier = Modifier.fillMaxWidth().touchTarget()) {
                Text(t("Review these exact LAN inputs", "مراجعة مدخلات الشبكة المحلية هذه بعينها"))
            }
            if (reviewed && complete) {
                SectionHeading(t("Review the exact transport selection", "مراجعة اختيار الاتصال الدقيق"))
                ReviewField(t("Approved CIDRs", "نطاقات CIDR المعتمدة"), subnets)
                ReviewField(t("Physical interface", "الواجهة الفعلية"), interfaceName)
                ReviewField(t("Numeric local address", "العنوان المحلي الرقمي"), localAddress)
                ReviewField(t("Requested listener port", "منفذ الاستماع المطلوب"), listenPort)
                if (isClient) {
                    ReviewField(t("Selected Host numeric address", "العنوان الرقمي للمضيف المختار"), hostAddress)
                    ReviewField(t("Selected Host port", "منفذ المضيف المختار"), hostPort)
                    ReviewField(t("Requested workflow", "سير العمل المطلوب"), lanWorkflowText(workflow), code = false)
                    ReviewField(t("Device credential disposition", "إجراء بيانات اعتماد الجهاز"),
                        credentialActionText(credentialAction), code = false)
                    if (businessWorkflow) ReviewField(
                        t("Employee original-intent disposition", "إجراء سجل العمليات الأصلي للموظف"),
                        actorIntentActionText(actorIntentAction), code = false)
                }
                Notice(if (isClient) t(
                    "Connect retains this exact selection and transfers the original target. Earlier target pairing actions stop. A failed or closed attachment cannot silently create another lifetime.",
                    "يحفظ الاتصال هذا الاختيار الدقيق وينقل الهدف الأصلي. تتوقف إجراءات اقتران الهدف السابقة. لا ينشئ اتصال فاشل أو مغلق دورة تشغيل أخرى تلقائيًا.") else t(
                    "Start retains this exact selection on the existing Master. Explicit listener port 0 is shown as an actual assigned port only after listening is confirmed.",
                    "يحفظ البدء هذا الاختيار الدقيق على الجهاز الرئيسي الموجود. لا يُعرض منفذ الاستماع صفر المدخل صراحةً بوصفه منفذًا مخصصًا فعليًا إلا بعد تأكيد الاستماع."))
                Button(onClick = {
                    val exactInput = input ?: return@Button
                    if (isClient) controller.connectLanClient(exactInput, endpoint ?: return@Button)
                    else controller.startLanHost(exactInput)
                }, enabled = mayBegin, modifier = Modifier.fillMaxWidth().touchTarget()) {
                    Text(if (isClient && workflow == LocalSetupLanWorkflow.SUPPLIERS)
                        t("Connect with this supplier-only selection", "الاتصال باختيار الموردين فقط هذا")
                        else if (isClient && workflow == LocalSetupLanWorkflow.WAREHOUSES)
                        t("Connect with this warehouse-only selection", "الاتصال باختيار المخازن فقط هذا")
                        else if (isClient && workflow == LocalSetupLanWorkflow.REPORTS)
                        t("Connect with this reports-only selection", "الاتصال باختيار التقارير فقط هذا")
                        else if (isClient && workflow == LocalSetupLanWorkflow.STOCK_TRANSFERS)
                        t("Connect with this stock-transfer selection", "الاتصال باختيار نقل المخزون هذا")
                        else if (isClient && workflow == LocalSetupLanWorkflow.KITCHEN)
                        t("Connect with this kitchen station selection", "الاتصال باختيار محطات المطبخ هذا")
                        else if (isClient && workflow == LocalSetupLanWorkflow.RETAIL_ORDERS)
                        t("Connect with this retail order selection", "الاتصال باختيار طلبات التجزئة هذا")
                        else if (isClient && workflow == LocalSetupLanWorkflow.PAYMENT_CORRECTIONS)
                        t("Connect with this historical payment correction selection", "الاتصال باختيار تصحيح تصنيف المدفوعات التاريخية هذا")
                        else if (isClient && workflow == LocalSetupLanWorkflow.CUSTOMER_RECEIPTS)
                        t("Connect with this counter goods-receipt selection", "الاتصال باختيار استلام البضاعة من المنضدة هذا")
                        else if (isClient && workflow == LocalSetupLanWorkflow.FOOD_CHECKS)
                        t("Connect with this food-check selection", "الاتصال باختيار طلبات الطعام هذا")
                        else if (isClient && workflow == LocalSetupLanWorkflow.FOOD_CASH_FINANCIAL)
                        t("Connect with this FOOD CASH financial selection", "الاتصال باختيار مالية نقدية الطعام هذا")
                        else if (isClient && workflow == LocalSetupLanWorkflow.FOOD_TABLE_ASSIGNMENTS)
                        t("Connect with this table-assignment selection", "الاتصال باختيار تعيين الطاولات هذا")
                        else if (isClient && workflow == LocalSetupLanWorkflow.INVOICE_CAPTURE)
                        t("Connect with this invoice-capture-only selection", "الاتصال باختيار حفظ الفواتير فقط هذا")
                        else if (isClient && workflow == LocalSetupLanWorkflow.INVOICE_MATCHING)
                        t("Connect with this invoice-matching-only selection", "الاتصال باختيار مطابقة الفواتير فقط هذا")
                        else if (isClient && credentialAction != null) t("Connect and enable employee sign-in", "الاتصال وإتاحة تسجيل دخول الموظفين")
                        else if (isClient) t("Connect to this selected original Master", "الاتصال بهذا الجهاز الرئيسي الأصلي المختار")
                        else t("Start LAN Host on this existing Master", "بدء مضيف الشبكة المحلية على هذا الجهاز الرئيسي الموجود"))
                }
            }
        }
        if (lan.status != LocalSetupLanStatus.NOT_ATTACHED) {
            OutlinedButton(onClick = controller::refreshLanAttachment,
                enabled = idle && (lan.status == LocalSetupLanStatus.LISTENING || lan.status == LocalSetupLanStatus.CONNECTED),
                modifier = Modifier.fillMaxWidth().touchTarget()) {
                Text(t("Refresh this same LAN attachment", "تحديث حالة اتصال الشبكة المحلية نفسه"))
            }
            OutlinedButton(onClick = controller::closeLanAttachment,
                enabled = idle && lan.status != LocalSetupLanStatus.CLOSING && lan.status != LocalSetupLanStatus.CLOSED,
                modifier = Modifier.fillMaxWidth().touchTarget()) {
                Text(t("Close only this LAN attachment", "إغلاق اتصال الشبكة المحلية هذا فقط"))
            }
        }
        Notice(t("Refresh only observes this retained attachment; it does not reconnect or select another endpoint. LAN close is terminal for this attachment. Locking or leaving setup keeps the authoritative Master running.",
            "يرصد التحديث هذا الاتصال المحفوظ فقط، ولا يعيد الاتصال أو يختار نقطة اتصال أخرى. إغلاق الشبكة المحلية نهائي لهذا الاتصال. يبقى الجهاز الرئيسي صاحب السلطة يعمل عند قفل الإعداد أو مغادرته."))
    }
}

@Composable
private fun LanTextInput(value: String, change: (String) -> Unit, label: String, enabled: Boolean) {
    OutlinedTextField(value, change, label = { Text(label) }, enabled = enabled, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.fillMaxWidth())
}

@Composable
private fun LanPortInput(value: String, change: (String) -> Unit, label: String, enabled: Boolean, invalid: Boolean, hint: String) {
    OutlinedTextField(value, change, label = { Text(label) }, enabled = enabled, singleLine = true, isError = invalid,
        supportingText = { Text(if (invalid) t("Invalid numeric port. ", "منفذ رقمي غير صحيح. ") + hint else hint) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth())
}

/** Presentation conversion only; network policy validation belongs to the retained owner. */
private fun lanPort(value: String, minimum: Int): Int? {
    if (value.isEmpty() || value.any { it !in '0'..'9' }) return null
    return value.toIntOrNull()?.takeIf { it in minimum..65535 }
}

@Composable
private fun lanStatusText(status: LocalSetupLanStatus): String = when (status) {
    LocalSetupLanStatus.NOT_ATTACHED -> t("No LAN attachment has been started.", "لم يبدأ اتصال بالشبكة المحلية.")
    LocalSetupLanStatus.STARTING -> t("Starting the LAN Host; listening is not yet confirmed.", "جارٍ بدء مضيف الشبكة المحلية؛ لم يُؤكَّد الاستماع بعد.")
    LocalSetupLanStatus.CONNECTING -> t("Connecting the selected LAN Client; connection is not yet confirmed.", "جارٍ وصل عميل الشبكة المحلية المختار؛ لم يُؤكَّد الاتصال بعد.")
    LocalSetupLanStatus.LISTENING -> t("The LAN Host is confirmed listening.", "تأكّد استماع مضيف الشبكة المحلية.")
    LocalSetupLanStatus.CONNECTED -> t("The LAN Client is confirmed connected to the selected original Master.", "تأكّد اتصال عميل الشبكة المحلية بالجهاز الرئيسي الأصلي المختار.")
    LocalSetupLanStatus.CLOSING -> t("Waiting for actual LAN attachment close.", "بانتظار الإغلاق الفعلي لاتصال الشبكة المحلية.")
    LocalSetupLanStatus.CLOSED -> t("This LAN attachment is closed. Its lifetime cannot be reused.", "أُغلق اتصال الشبكة المحلية هذا. لا يمكن إعادة استخدام دورة تشغيله.")
    LocalSetupLanStatus.UNAVAILABLE -> t("This LAN attachment is unavailable. No replacement is started.", "اتصال الشبكة المحلية هذا غير متاح. لا يبدأ اتصال بديل.")
    LocalSetupLanStatus.UNKNOWN -> t("LAN state or cleanup is unconfirmed. Retained ownership prevents a replacement attachment.", "حالة الشبكة المحلية أو تنظيفها غير مؤكدة. تمنع الملكية المحفوظة اتصالًا بديلًا.")
}

@Composable
private fun lanWorkflowText(workflow: LocalSetupLanWorkflow): String = when (workflow) {
    LocalSetupLanWorkflow.AUTHENTICATION_ONLY -> t("Authentication only — no business workflow", "المصادقة فقط — دون سير عمل تجاري")
    LocalSetupLanWorkflow.SUPPLIERS -> t("Suppliers only — original operations and recovery", "الموردون فقط — العمليات الأصلية والاسترداد")
    LocalSetupLanWorkflow.WAREHOUSES -> t("Warehouses only — original operations and recovery", "المخازن فقط — العمليات الأصلية والاسترداد")
    LocalSetupLanWorkflow.REPORTS -> t("Current-document reports and local CSV export only", "تقارير المستندات الحالية وتصدير CSV محلي فقط")
    LocalSetupLanWorkflow.STOCK_TRANSFERS -> t("Same-branch stock transfers and original recovery", "نقل المخزون داخل الفرع واسترداد العمليات الأصلية")
    LocalSetupLanWorkflow.KITCHEN -> t("Kitchen station board, Start/Ready and original recovery", "لوحة محطة المطبخ وبدء التحضير والجاهزية والاسترداد الأصلي")
    LocalSetupLanWorkflow.RETAIL_ORDERS -> t("Own unlocked retail orders and original recovery, not payment", "طلبات التجزئة غير المقفلة الخاصة بك والاسترداد الأصلي، دون دفع")
    LocalSetupLanWorkflow.PAYMENT_CORRECTIONS -> t("Historical payment classification and original recovery", "تصنيف المدفوعات التاريخية والاسترداد الأصلي")
    LocalSetupLanWorkflow.CUSTOMER_RECEIPTS -> t("Actual counter goods receipt and original recovery — no payment", "استلام البضاعة الفعلي من المنضدة والاسترداد الأصلي — دون دفع")
    LocalSetupLanWorkflow.FOOD_CASH_FINANCIAL -> t("FOOD CASH financial and original recovery — no cash capture", "مالية نقدية الطعام واسترداد الأصل — دون تحصيل فعلي")
    LocalSetupLanWorkflow.FOOD_CHECKS -> t("Own unpaid food checks, quoted Add and original recovery", "طلبات الطعام غير المدفوعة الخاصة بك والإضافة بعرض سعر والاسترداد الأصلي")
    LocalSetupLanWorkflow.FOOD_TABLE_ASSIGNMENTS -> t("Own check table assignment, movement and original recovery", "تعيين طاولة طلبك ونقله واسترداد العملية الأصلية")
    LocalSetupLanWorkflow.INVOICE_CAPTURE -> t("Supplier invoice capture only — unmatched claim and original recovery", "حفظ فاتورة المورد فقط — مطالبة غير مطابقة واسترداد الأصل")
    LocalSetupLanWorkflow.INVOICE_MATCHING -> t("Captured invoice matching and payable recognition — original recovery", "مطابقة الفاتورة المحفوظة وإثبات المستحق — استرداد الأصل")
}

@Composable
private fun credentialActionText(action: LocalPairedDeviceCredentialJournalAction?): String = when (action) {
    null -> t("Transport only — do not prepare authentication", "اتصال فقط — دون إعداد المصادقة")
    LocalPairedDeviceCredentialJournalAction.ENROLL -> t("Initialize this new device's credential journal", "تهيئة سجل بيانات الاعتماد لهذا الجهاز الجديد")
    LocalPairedDeviceCredentialJournalAction.REOPEN -> t("Reopen this device's existing credential journal", "إعادة فتح سجل بيانات الاعتماد الموجود لهذا الجهاز")
    LocalPairedDeviceCredentialJournalAction.RECOVER -> t("Recover this device's original pending credential writes", "استرداد كتابات بيانات الاعتماد الأصلية المعلقة لهذا الجهاز")
}

@Composable
private fun actorIntentActionText(action: LocalPairedDeviceCredentialJournalAction?): String = when (action) {
    null -> t("No employee custody action selected", "لم يُختر إجراء حفظ للموظف")
    LocalPairedDeviceCredentialJournalAction.ENROLL -> t("Enroll an employee without an original allocation", "تهيئة سجل موظف بلا تخصيص أصلي")
    LocalPairedDeviceCredentialJournalAction.REOPEN -> t("Reopen this employee's existing original intents", "إعادة فتح العمليات الأصلية الموجودة لهذا الموظف")
    LocalPairedDeviceCredentialJournalAction.RECOVER -> t("Recover this employee's original pending writes", "استرداد الكتابات الأصلية المعلقة لهذا الموظف")
}
