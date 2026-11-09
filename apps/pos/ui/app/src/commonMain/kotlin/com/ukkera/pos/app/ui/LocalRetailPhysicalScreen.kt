package com.ukkera.pos.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.ukkera.pos.app.*
import com.ukkera.pos.app.navigation.NavigationToken
import kotlinx.coroutines.delay

@Composable
internal fun retailText(english: String, arabic: String): String = LocalAppLanguage.current.text(LocalText(english, arabic))

/** Only rendered inside the already-active Nav3 entry, never an outgoing animation snapshot. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocalRetailPhysicalScreen(controller: LocalRetailPhysicalController,
    parent: LocalWorkspaceState, token: NavigationToken, openCollection: () -> Unit) {
    val state by controller.state.collectAsState()
    if (parent.workflow !in retailWorkflows || state.workflow != parent.workflow ||
        state.reference != parent.reference || !controller.displayCurrent(token)) return
    val language = LocalAppLanguage.current
    val refundOnly = parent.workflow == LocalWorkflow.RETURNS
    val foodRefundLane = parent.workflow == LocalWorkflow.FOOD && state.foodRefundId != null
    val parentReady = !parent.working && parent.form == null && !parent.formFrozen
    val ready = parentReady && state.canPrepare
    LaunchedEffect(controller, token, state.instruction?.operationId) {
        if (state.instruction != null) while (true) {
            delay(200)
            controller.sampleInstruction(token) // Withdraw-only display sampling; never a grant.
        }
    }
    Surface(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Outline)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeading(when (parent.workflow) {
                LocalWorkflow.FOOD -> if (foodRefundLane) retailText("Accepted FOOD cash-refund payout and same-cashier recovery", "صرف رد نقدية الطعام المقبول والاسترداد للموظف نفسه")
                    else retailText("Food cash collection and linked-evidence reconciliation", "تحصيل نقدية الطعام وتسوية الأدلة المرتبطة")
                LocalWorkflow.RETURNS -> retailText("Accepted retail refund payout and same-ID reconciliation", "صرف رد التجزئة المقبول والتسوية بنفس المعرف")
                else -> retailText("Retail collection, refund and reconciliation", "تحصيل التجزئة والرد والتسوية")
            })
            if (foodRefundLane) Notice(retailText(
                "Pay only genuine accepted whole-unsent FOOD actual-CASH dues, never the unpaid quoted total. The current paying cashier/session is checked independently of the former order owner. Prepared/served remedies, alternative tender and foreign-custody takeover remain unavailable.",
                "ادفع فقط مستحقات الطعام النقدية الفعلية المقبولة لإلغاء الطلب الكامل غير المرسل، وليس باقي التسعير غير المدفوع. تُفحص جلسة وموظف الدفع الحاليان مستقلين عن مالك الطلب السابق. تظل معالجات الطعام المحضر أو المقدم والوسائل البديلة والاستحواذ على عهدة موظف آخر غير متاحة."))
            else if (parent.workflow == LocalWorkflow.FOOD) Notice(retailText(
                "Cash collection uses the genuine locked food check. An old UNKNOWN uses its distinct fixed-ID linked resolution, authorized native retention and separately audited review/accounting. Accepted FOOD refund liability has a separate payout page; do not collect again to resolve evidence.",
                "يستخدم التحصيل النقدي طلب الطعام المقفل الأصلي. تستخدم النتيجة المجهولة تسوية مرتبطة ذات معرف ثابت وحفظًا أصليًا مصرحًا به ومراجعة ومحاسبة مدققتين منفصلتين. لالتزام رد الطعام المقبول صفحة صرف مستقلة؛ لا تكرر التحصيل لتسوية الدليل."))
            if (refundOnly) Notice(retailText(
                "Payout-only: use genuine existing accepted retail obligations and original tender allocations. Current permission, source and paying-session custody are rechecked. This panel does not qualify or accept a return, restore stock, perform an exchange or offer FOOD/collection instructions; missing rights or receipt provenance stays unavailable.",
                "للصرف فقط: استخدم التزامات تجزئة مقبولة فعلًا وتخصيصات وسائل الدفع الأصلية. تُفحص الصلاحية الحالية والمصدر وعهدة الجلسة الدافعة مجددًا. لا يؤهّل هذا القسم مرتجعًا أو يقبله، ولا يعيد المخزون أو ينفذ استبدالًا أو يعرض تعليمات تحصيل أو طعام؛ يظل غياب الحقوق أو مصدر الإيصال غير متاح."))
            Notice(retailText("An instruction, a captured observation, native retention and SQL financial application are different steps. No replay or recovery read authorizes another physical action.",
                "التعليمات والإفادة المسجلة والحفظ الأصلي والتطبيق المالي في قاعدة البيانات خطوات مختلفة. لا يجيز الاسترداد أو إعادة عرض نتيجة تنفيذ حركة فعلية جديدة."))
            if (state.working) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(retailText("Waiting for the original request. Lock remains available; leaving never erases its durable evidence.",
                    "بانتظار الطلب الأصلي. يظل القفل متاحًا؛ المغادرة لا تمحو أدلته الدائمة."), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            state.problem?.let { Notice(localProblem(it), caution = true) }
            if (state.inputProblem) Notice(retailText("Choose an actual outcome, complete its exact fields and explicitly confirm. Amounts use at most two decimal places; no rounding is performed.",
                "اختر النتيجة الفعلية وأكمل حقولها الدقيقة ثم أكد صراحةً. للمبالغ منزلتان عشريتان كحد أقصى دون تقريب."), caution = true)
            state.notice?.let { Notice(language.text(it), caution = true) }
            val live = state.instruction
            if (live != null || state.busy == LocalRetailBusy.STARTING) {
                if (live != null && controller.instructionCurrent(token)) {
                    Text(if (live.kind == LocalRetailPhysicalKind.PAYMENT)
                        retailText("ONE LIVE COLLECTION INSTRUCTION", "تعليمات تحصيل واحدة سارية")
                    else retailText("ONE LIVE REFUND INSTRUCTION", "تعليمات رد واحدة سارية"),
                        style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
                    RetailInstructionDetails(live)
                    Notice(retailText("Perform only this displayed action once. If it already happened, do NOT repeat it. Record the actual evidence below. If these closed choices cannot express what happened, choose UNKNOWN; never invent a matching payment.",
                        "نفذ الحركة المعروضة مرة واحدة فقط. إذا حدثت بالفعل فلا تكررها. سجل الدليل الفعلي أدناه. إذا لم تعبّر الخيارات المحددة عما حدث فاختر غير معلوم؛ لا تختلق دفعة مطابقة."), caution = true)
                    RetailObservationEditor(live, state.observation, parentReady && !state.working, resolution = false,
                        choose = { controller.chooseObservation(it, token) },
                        change = { field, value -> controller.changeInput(field, value, token) },
                        confirm = { controller.confirmObservation(it, token) })
                    Button(onClick = { controller.capture(token) }, enabled = parentReady && !state.working && state.observation.confirmed,
                        modifier = Modifier.touchTarget()) { Text(retailText("Capture this original observation now", "تسجيل هذه الإفادة الأصلية الآن")) }
                } else Notice(retailText("No live instruction is available here. Waiting, expiry or a missing display does not mean that no human action occurred.",
                    "لا توجد تعليمات سارية هنا. الانتظار أو انتهاء الصلاحية أو غياب العرض لا يعني عدم حدوث حركة بشرية."), caution = true)
                OutlinedButton(onClick = { controller.endInstruction(token) }, modifier = Modifier.touchTarget()) {
                    Text(retailText("End instruction / stop waiting — preserve evidence", "إنهاء التعليمات أو الانتظار — مع حفظ الأدلة"))
                }
            } else {
                state.capture?.let { captured ->
                    Notice(when (captured) {
                        is LocalRetailPhysicalCapture.CapturedInMemory -> retailText(
                            "Observation captured in process memory ONLY. Durable native retention, SQL intake and financial application have NOT been confirmed by this display. Do not repeat the action.",
                            "حُفظت الإفادة في ذاكرة العملية فقط. لم يؤكد هذا العرض الحفظ الأصلي الدائم أو الإدخال لقاعدة البيانات أو التطبيق المالي. لا تكرر الحركة.")
                        LocalRetailPhysicalCapture.InvalidInput -> retailText("That capture accepted no valid body. The earlier physical outcome remains unresolved; do not infer NOT PAID or repeat the action.",
                            "لم يقبل ذلك التسجيل إفادة صالحة. تظل النتيجة الفعلية السابقة غير محسومة؛ لا تفترض عدم الدفع ولا تكرر الحركة.")
                        LocalRetailPhysicalCapture.Rejected -> retailText("Original capture was not granted. No outcome is inferred; preserve the original IDs and reconcile.",
                            "لم يُمنح تسجيل الإفادة الأصلية. لا تُستنتج أي نتيجة؛ احتفظ بالمعرفات الأصلية وسوِّ الحالة.")
                        LocalRetailPhysicalCapture.Uncertain -> retailText("Capture disposition is UNCERTAIN. Its native owner retains any candidate. Do not retry capture or start another physical action.",
                            "حالة التسجيل غير مؤكدة. يحتفظ المالك الأصلي بأي إفادة محتملة. لا تكرر التسجيل ولا تبدأ حركة فعلية أخرى.")
                    }, caution = true, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
                    state.operationId?.let { id ->
                        IdentifierText(retailText("Original observation operation", "عملية الإفادة الأصلية"), id.value)
                        OutlinedButton(onClick = { controller.recover(id, token) }, enabled = ready, modifier = Modifier.touchTarget()) {
                            Text(retailText("Read authenticated SQL progress only", "قراءة تقدم قاعدة البيانات المصرح به فقط"))
                        }
                    }
                }
                state.preview?.let { preview ->
                    Text(language.text(preview.title), style = MaterialTheme.typography.titleMedium)
                    IdentifierText(retailText("Immutable operation ID", "معرف العملية الثابت"), preview.operationId.value)
                    RetailValues(preview.summary)
                    Notice(retailText("Saving does not submit this command and does not instruct anyone to take or pay money.",
                        "الحفظ لا يرسل هذا الأمر ولا يوجه أحدًا لتحصيل أو دفع مال."))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { controller.stage(token) }, enabled = parentReady && !state.working, modifier = Modifier.touchTarget()) {
                            Text(if (preview.saveAttempted) retailText("Retry saving SAME ID and body", "إعادة حفظ نفس المعرف والإفادة")
                            else retailText("Durably save this exact request", "حفظ هذا الطلب الدقيق بشكل دائم"))
                        }
                        if (!preview.saveAttempted) OutlinedButton(onClick = { controller.discardPreview(token) }, enabled = !state.working,
                            modifier = Modifier.touchTarget()) { Text(retailText("Discard unsaved preview", "إهمال المعاينة غير المحفوظة")) }
                    }
                }
                val operations = listOfNotNull(state.selected) + state.saved.filterNot { it.operationId == state.selected?.operationId }
                operations.forEach { saved -> key(saved.operationId.value) {
                    if (refundOnly && saved.financialKind == null) Notice(retailText(
                        "Saved supervisor review: its financial kind is not asserted before the separate audit. It is never a payout instruction.",
                        "مراجعة مشرف محفوظة: لا يُفترض نوعها المالي قبل التدقيق المنفصل. ليست تعليمات صرف."))
                    RetailSavedCard(saved, ready, start = { controller.start(saved.operationId, token) },
                        sql = { controller.continueSql(saved.operationId, token) }, recover = { controller.recover(saved.operationId, token) })
                } }
                state.progress?.let { progress ->
                    RetailProgress(progress)
                    if (!refundOnly && progress.collectionId != null) OutlinedButton(onClick = openCollection, enabled = ready, modifier = Modifier.touchTarget()) {
                        Text(retailText("Open current collection / separate sale finalization", "فتح التحصيل الحالي وتثبيت البيع المنفصل"))
                    }
                    RetailApplicationAction(progress, controller, token, ready)
                    if (state.selected?.recorded == true && state.selected?.operationId == progress.operationId) OutlinedButton(
                        onClick = { controller.acknowledge(token) }, enabled = ready, modifier = Modifier.touchTarget()) {
                        Text(retailText("Acknowledge displayed result on this client only", "الإقرار بالنتيجة المعروضة على هذا العميل فقط"))
                    }
                }
                if (foodRefundLane) {
                    FoodRefundPhysicalReadPanel(state, controller, token, ready)
                } else {
                SectionHeading(retailText("Current authorized read controls", "عناصر القراءة المصرح بها حاليًا"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!refundOnly && parent.reference?.kind == LocalRecordKind.ORDER) OutlinedButton(onClick = { controller.beginCollection(token) }, enabled = ready,
                        modifier = Modifier.touchTarget()) { Text(retailText("Prepare positive collection / stock reservation", "إعداد التحصيل وحجز المخزون")) }
                    if (!refundOnly && (parent.reference?.kind in setOf(LocalRecordKind.COLLECTION, LocalRecordKind.FOOD_COLLECTION) ||
                        parent.workflow == LocalWorkflow.FOOD && parent.foodQuery is LocalFoodUiQuery.Collection || state.progress?.collectionId != null)) OutlinedButton(
                        onClick = { controller.loadPayment(token) }, enabled = ready, modifier = Modifier.touchTarget()) {
                        Text(retailText("Read remaining amount and current tenders", "قراءة المبلغ المتبقي ووسائل الدفع الحالية"))
                    }
                    if (parent.workflow != LocalWorkflow.FOOD) OutlinedButton(onClick = { controller.loadRefunds(token) }, enabled = ready, modifier = Modifier.touchTarget()) {
                        Text(retailText("Read own accepted-return dues", "قراءة مستحقات المرتجعات المقبولة الخاصة"))
                    }
                    if (refundOnly && parent.reference?.let { it.kind == LocalRecordKind.RETURN_FINANCE && it.parentId != null } == true) OutlinedButton(
                        onClick = { controller.loadAcceptedRefunds(token) }, enabled = ready, modifier = Modifier.touchTarget()) {
                        Text(retailText("Read this accepted return's actual payout dues", "قراءة مستحقات الصرف الفعلية لهذا المرتجع المقبول"))
                    }
                    OutlinedButton(onClick = { controller.loadPending(token) }, enabled = ready, modifier = Modifier.touchTarget()) {
                        Text(if (parent.workflow == LocalWorkflow.FOOD)
                            retailText("Read saved FOOD requests + own ORIGINAL pending (replaces page)", "قراءة طلبات الطعام المحفوظة والأصول المعلقة الخاصة — تستبدل الصفحة")
                        else if (refundOnly) retailText("Read refund requests + own REFUND SQL pending", "قراءة طلبات الرد وإفادات الرد الخاصة المعلقة")
                        else retailText("Read saved requests + own SQL pending (first page)", "قراءة الطلبات المحفوظة والمعلقات الخاصة — الصفحة الأولى"))
                    }
                    if (parent.workflow == LocalWorkflow.FOOD) OutlinedButton(
                        onClick = { controller.loadPending(token, resolutions = true) }, enabled = ready, modifier = Modifier.touchTarget()) {
                        Text(retailText("Read own FOOD RESOLUTION pending (replaces page)", "قراءة تسويات الطعام المعلقة الخاصة — تستبدل الصفحة"))
                    }
                    if (state.nextFactAfter != null) OutlinedButton(onClick = { controller.loadPending(token, true) }, enabled = ready,
                        modifier = Modifier.touchTarget()) {
                        Text(if (state.resolutionPendingPage)
                            retailText("Next own FOOD RESOLUTION page (replaces page)", "صفحة تسويات الطعام الخاصة التالية — تستبدل الصفحة")
                        else if (parent.workflow == LocalWorkflow.FOOD)
                            retailText("Next own FOOD ORIGINAL pending page", "الصفحة التالية لأصول الطعام المعلقة الخاصة")
                        else if (refundOnly) retailText("Next refund-filtered SQL scan page", "صفحة المسح التالية لإفادات الرد")
                        else retailText("Next own SQL pending page", "الصفحة التالية للمعلقات الخاصة"))
                    }
                }
                if (!refundOnly) state.payment?.let { context ->
                    IdentifierText(retailText("Current collection", "التحصيل الحالي"), context.collectionId.value)
                    Text(retailText("Remaining: ${retailMoney(context.remaining)}", "المتبقي: ${retailMoney(context.remaining)}"))
                    RetailInput(state.paymentAmount, retailText("This partial/split payment amount", "مبلغ هذه الدفعة الجزئية أو المقسمة"), ready, decimal = true) {
                        controller.changeInput(LocalRetailInput.AMOUNT, it, token)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = state.tenderId == null, onClick = { controller.chooseTender(null, token) }, enabled = ready,
                            label = { Text(retailText("Cash", "نقدي")) }, modifier = Modifier.touchTarget())
                        context.externalTenders.forEach { choice -> FilterChip(selected = state.tenderId == choice.id,
                            onClick = { controller.chooseTender(choice.id, token) }, enabled = ready,
                            label = { Text(language.text(choice.label)) }, modifier = Modifier.touchTarget()) }
                    }
                    context.tenderProblem?.let { Notice(localProblem(it), caution = true) }
                    if (context.nextAfter != null) OutlinedButton(onClick = { controller.loadPayment(token, true) }, enabled = ready,
                        modifier = Modifier.touchTarget()) { Text(retailText("Next current tender page (replaces choices)", "صفحة وسائل الدفع التالية — تستبدل الخيارات")) }
                    Button(onClick = { controller.preparePayment(token) }, enabled = ready && context.remaining.minor > 0L,
                        modifier = Modifier.touchTarget()) { Text(retailText("Prepare one payment request — no action yet", "إعداد طلب دفعة واحدة — لا حركة بعد")) }
                }
                if (state.duesLoaded) RetailRefundReadPage(state, controller, token, ready)
                if (state.pendingLoaded && parent.workflow == LocalWorkflow.FOOD) Text(if (state.resolutionPendingPage)
                    retailText("Current page: own linked FOOD resolutions only; originals use a separate replacing read.", "الصفحة الحالية لتسويات الطعام المرتبطة الخاصة فقط؛ تُقرأ الأصول منفصلًا مع استبدال الصفحة.")
                else retailText("Current page: own FOOD originals only; linked resolutions use a separate replacing read.", "الصفحة الحالية لأصول الطعام الخاصة فقط؛ تُقرأ التسويات المرتبطة منفصلًا مع استبدال الصفحة."))
                if (state.pendingLoaded && refundOnly) Text(retailText(
                    "Current SQL page: refund facts only, with the actual underlying scan cursor preserved. Saved untyped reviews remain separate from physical payout. An empty filtered page may still have a next page.",
                    "صفحة قاعدة البيانات الحالية لإفادات الرد فقط، مع حفظ مؤشر المسح الفعلي. المراجعات غير المحددة النوع منفصلة عن الصرف الفعلي. قد تكون الصفحة فارغة مع وجود صفحة تالية."))
                if (state.pendingLoaded && state.facts.isEmpty()) Text(retailText("No own pending SQL facts in this page. Native evidence may still be pending; check the next page if offered.",
                    "لا توجد إفادات معلقة خاصة في صفحة قاعدة البيانات هذه. قد تظل أدلة أصلية معلقة؛ افحص الصفحة التالية إن توفرت."))
                state.facts.forEach { fact ->
                    RetailProgress(fact)
                    RetailApplicationAction(fact, controller, token, ready)
                }
                SectionHeading(retailText("Same-ID recovery / separately audited supervisor review", "الاسترداد بنفس المعرف ومراجعة المشرف المدققة منفصلًا"))
                RetailInput(state.recoveryId, if (refundOnly)
                    retailText("Existing refund observation ID or saved refund/review operation", "معرف إفادة رد أصلية أو عملية رد أو مراجعة محفوظة")
                else retailText("Original observation ID (or saved operation ID for own recovery)",
                    "معرف الإفادة الأصلية — أو معرف عملية محفوظة للاسترداد الخاص"), ready) { controller.changeInput(LocalRetailInput.RECOVERY_ID, it, token) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { controller.recoverInput(token) }, enabled = ready, modifier = Modifier.touchTarget()) {
                        Text(retailText("Read own SQL result / fact only", "قراءة النتيجة أو الإفادة الخاصة فقط"))
                    }
                    OutlinedButton(onClick = { controller.reviewOriginal(token) }, enabled = ready, modifier = Modifier.touchTarget()) {
                        Text(if (parent.workflow == LocalWorkflow.FOOD)
                            retailText("Prepare audited FOOD supervisor review", "إعداد مراجعة مشرف للطعام مدققة")
                        else retailText("Prepare audited supervisor review", "إعداد مراجعة المشرف المدققة"))
                    }
                    if (state.reviewedOriginalId != null && state.reviewedSubject != null) OutlinedButton(onClick = { controller.reviewOriginal(token, true) }, enabled = ready,
                        modifier = Modifier.touchTarget()) {
                        Text(if (state.reviewedSubject == LocalPaymentSubject.FOOD)
                            retailText("Prepare FRESH audited FOOD review of original", "إعداد مراجعة طعام مدققة جديدة للأصل")
                        else retailText("Prepare FRESH audited review of original", "إعداد مراجعة مدققة جديدة للأصل"))
                    }
                }
                state.review?.let { review ->
                    Text(retailText("Historical audited review — NOT an instruction", "مراجعة مدققة تاريخية — ليست تعليمات حركة"), style = MaterialTheme.typography.titleMedium)
                    review.reviewDisplay?.let { RetailInstructionDetails(it) }
                    review.originalActorId?.let { IdentifierText(retailText("Immutable original custodian", "موظف العهدة الأصلي الثابت"), it.value) }
                    review.recordingActorId?.let { IdentifierText(retailText("Actual observation recorder", "مسجل الإفادة الفعلي"), it.value) }
                    RetailValues(review.reviewValues)
                    val usable = !refundOnly || review.financialKind == LocalRetailFinancialKind.REFUND &&
                        review.subject == LocalPaymentSubject.RETAIL && review.reviewDisplay?.kind == LocalRetailPhysicalKind.REFUND
                    if (!usable) Notice(retailText("The audited target is not a retail refund. It remains read-only in this payout-only panel; no collection, resolution or accounting is reinterpreted as a refund.",
                        "الهدف المدقق ليس رد تجزئة. يظل للقراءة فقط في قسم الصرف؛ لا يُعاد تفسير التحصيل أو التسوية أو المحاسبة كمرتجع."), caution = true)
                    if (usable && !review.originalApplied) OutlinedButton(onClick = { controller.supervisorApply(LocalRetailObservationPhase.ORIGINAL, token) }, enabled = ready,
                        modifier = Modifier.touchTarget()) { Text(retailText("Prepare supervised ORIGINAL application — SQL only", "إعداد تطبيق الأصل بإشراف — قاعدة البيانات فقط")) }
                    if (usable && review.resolutionRecorded && review.originalApplied) OutlinedButton(onClick = { controller.supervisorApply(LocalRetailObservationPhase.RESOLUTION, token) }, enabled = ready,
                        modifier = Modifier.touchTarget()) { Text(retailText("Prepare supervised RESOLUTION application — SQL only", "إعداد تطبيق التسوية بإشراف — قاعدة البيانات فقط")) }
                    val display = review.reviewDisplay
                    if (usable && review.originalUnknown && !review.resolutionRecorded && display != null) {
                        Notice(retailText("Resolve evidence about the OLD action, using its already reserved resolution ID. Do not perform another collection or refund. After recording resolution, request a fresh audited review before applying it.",
                            "سوِّ دليل الحركة السابقة باستخدام معرف التسوية المحجوز بالفعل. لا تنفذ تحصيلًا أو ردًا جديدًا. بعد تسجيل التسوية اطلب مراجعة مدققة جديدة قبل تطبيقها."), caution = true)
                        RetailObservationEditor(display, state.observation, ready, resolution = true,
                            choose = { controller.chooseObservation(it, token) }, change = { field, value -> controller.changeInput(field, value, token) },
                            confirm = { controller.confirmObservation(it, token) })
                        Button(onClick = { controller.resolve(token) }, enabled = ready && state.observation.confirmed, modifier = Modifier.touchTarget()) {
                            Text(retailText("Prepare this fixed-ID resolution — no new action", "إعداد هذه التسوية بالمعرف الثابت — دون حركة جديدة"))
                        }
                    }
                }
                }
            }
        }
    }
}

@Composable
internal fun RetailApplicationAction(value: LocalRetailPhysicalProgress, controller: LocalRetailPhysicalController,
    token: NavigationToken, enabled: Boolean) {
    if (controller.state.value.workflow == LocalWorkflow.RETURNS &&
        (value.financialKind != LocalRetailFinancialKind.REFUND || value.subject != LocalPaymentSubject.RETAIL)) return
    if (value.state !in setOf(LocalRetailDurableState.SQL_ORIGINAL_PENDING, LocalRetailDurableState.SQL_RESOLUTION_PENDING)) return
    if (value.recordingOperationId == null || value.phase == null) return
    val own = controller.canPrepareOwnApplication(value)
    if (value.subject == LocalPaymentSubject.FOOD && value.financialKind == LocalRetailFinancialKind.REFUND && !own) return
    if (!own && value.originalRecordingOperationId == null) return
    OutlinedButton(onClick = { controller.prepareApply(value, token) }, enabled = enabled, modifier = Modifier.touchTarget()) {
        Text(if (own) retailText("Prepare own recorded-observation application — SQL only", "إعداد تطبيق الإفادة الخاصة المسجلة — قاعدة البيانات فقط")
            else retailText("Prepare FRESH audited review before supervised application — SQL only",
                "إعداد مراجعة مدققة جديدة قبل التطبيق بإشراف — قاعدة البيانات فقط"))
    }
}

@Composable
private fun RetailRefundReadPage(state: LocalRetailWorkspace, controller: LocalRetailPhysicalController,
    token: NavigationToken, enabled: Boolean) {
    SectionHeading(if (state.duesForSelectedReturn)
        retailText("Selected accepted return — actual original-tender dues", "المرتجع المقبول المختار — مستحقات وسائل الدفع الأصلية")
    else retailText("Own accepted-return dues — current replacing page", "مستحقات المرتجعات المقبولة الخاصة — الصفحة الحالية"))
    Notice(retailText("Refunds use already accepted local returns and their original tender rights, less actual recorded refunds. This read neither accepts a return nor creates entitlement. A payout for an earlier sale uses the current paying drawer; it never reopens the sale's earlier closed session.",
        "تستخدم الردود مرتجعات محلية مقبولة بالفعل وحقوق وسائل دفعها الأصلية بعد خصم الردود المسجلة فعلًا. لا تقبل القراءة مرتجعًا ولا تنشئ استحقاقًا. يستخدم رد بيع سابق درج الموظف الدافع الحالي، ولا يعيد فتح جلسة البيع السابقة المغلقة."))
    if (state.dues.isEmpty()) Text(retailText("No positive recorded due in this successful source page. This is not an independent physical-outcome certificate or evidence about an unavailable receipt.",
        "لا يوجد مستحق موجب مسجل في صفحة المصدر المتاحة هذه. ليس هذا إثباتًا مستقلًا لنتيجة حركة فعلية أو دليلًا عن إيصال غير متاح."))
    state.dues.forEach { due -> key(due.returnId.value, due.originalPaymentId.value) {
        IdentifierText(retailText("Accepted return", "المرتجع المقبول"), due.returnId.value)
        IdentifierText(retailText("Original sale", "البيع الأصلي"), due.saleId.value)
        IdentifierText(retailText("Original payment", "الدفع الأصلي"), due.originalPaymentId.value)
        Text("\u2066${retailMoney(due.amount)} · ${due.tender}\u2069")
        if (due.unresolved) Notice(retailText("An earlier refund attempt is unresolved. All new payout preparation for this return stays blocked; reconcile that original attempt without paying again.",
            "محاولة رد سابقة غير محسومة. يظل إعداد أي صرف جديد لهذا المرتجع محظورًا؛ سوِّ المحاولة الأصلية دون دفع مجدد."), caution = true)
        OutlinedButton(onClick = { controller.prepareRefund(due.returnId, due.originalPaymentId, token) }, enabled = enabled && !due.unresolved,
            modifier = Modifier.touchTarget()) { Text(retailText("Prepare refund of this actual due", "إعداد رد هذا المستحق الفعلي")) }
    } }
    if (state.dues.any { it.unresolved } && state.refundRecoveries.isEmpty()) Notice(retailText(
        "This read exposes no own unresolved-attempt selector. Do not guess another payer's private IDs or interpret missing disclosure as permission to pay again.",
        "لا تعرض هذه القراءة معرف محاولة معلقة خاصة بالمستخدم الحالي. لا تخمّن معرفات دافع آخر الخاصة، ولا تعتبر غياب الإفصاح إذنًا للدفع مجددًا."), caution = true)
    if (state.refundRecoveries.isNotEmpty()) SectionHeading(retailText("Own existing attempts — read-only same-ID recovery", "المحاولات الأصلية الخاصة — استرداد للقراءة بنفس المعرف"))
    state.refundRecoveries.forEach { recovery -> key(recovery.returnId.value, recovery.attemptId.value) {
        IdentifierText(retailText("Existing refund attempt", "محاولة الرد الأصلية"), recovery.attemptId.value)
        IdentifierText(retailText("Original intent operation", "عملية الطلب الأصلية"), recovery.intentOperationId.value)
        IdentifierText(retailText("Existing attempt's paying session", "الجلسة الدافعة للمحاولة الأصلية"), recovery.cashSessionId.value)
        recovery.recordingOperationId?.let { IdentifierText(retailText("Original reserved observation", "الإفادة الأصلية المحجوزة"), it.value) }
        Text("\u2066${retailMoney(recovery.amount)} · ${recovery.tender} · ${recovery.status}\u2069")
        if (recovery.protocolVersion == 3 && recovery.recordingOperationId != null) OutlinedButton(
            onClick = { controller.recoverRefund(recovery, token) }, enabled = enabled, modifier = Modifier.touchTarget()) {
            Text(retailText("Read this existing V3 refund attempt's SQL progress", "قراءة تقدم قاعدة البيانات لمحاولة الرد الأصلية V3"))
        } else Notice(retailText("This attempt exposes legacy or no genuine V3 recovery selectors. Its protocol is not reinterpreted here. Keep the original IDs; no new payout, capture or fallback is authorized by this panel.",
            "تعرض المحاولة معرفات بروتوكول سابق أو لا تعرض معرفات استرداد أصلية V3. لا يُعاد تفسير بروتوكولها هنا. احتفظ بالمعرفات الأصلية؛ لا يجيز القسم صرفًا أو تسجيلًا جديدًا أو مسارًا بديلًا."), caution = true)
    } }
    if (!state.duesForSelectedReturn && state.nextDueAfter != null) OutlinedButton(onClick = { controller.loadRefunds(token, true) }, enabled = enabled,
        modifier = Modifier.touchTarget()) { Text(retailText("Next accepted return (replaces page)", "المرتجع المقبول التالي — يستبدل الصفحة")) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RetailSavedCard(value: LocalRetailSavedOperation, enabled: Boolean, start: () -> Unit, sql: () -> Unit, recover: () -> Unit) {
    var confirmation by remember(value.operationId) { mutableStateOf(false) }
    val language = LocalAppLanguage.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(language.text(value.label), style = MaterialTheme.typography.titleMedium)
        IdentifierText(retailText("Saved operation ID", "معرف العملية المحفوظة"), value.operationId.value)
        RetailValues(value.summary)
        Notice(when {
            value.recorded -> retailText("Result recorded. Read its exact state; a Start result is not an instruction or a payment.", "سُجلت النتيجة. اقرأ حالتها الدقيقة؛ نتيجة البدء ليست تعليمات أو دفعة.")
            value.dispatched -> retailText("Previously dispatched; unresolved until recovered. Physical Starts are NEVER resent here.", "أُرسلت سابقًا وتظل غير محسومة حتى الاسترداد. لا يُعاد إرسال البدء الفعلي هنا مطلقًا.")
            else -> retailText("Request durably saved; not dispatched. Review the immutable details before continuing.", "حُفظ الطلب بشكل دائم ولم يُرسل. راجع التفاصيل الثابتة قبل المتابعة.")
        }, caution = value.dispatched && !value.recorded)
        if (value.requiresNativeRetention && !value.recorded) Notice(retailText(
            "This saved linked-resolution input is not confirmation of native retention or accounting. Continue only its same immutable ID/body through authorized native retention; no new physical action.",
            "حفظ مدخل التسوية المرتبطة لا يؤكد الحفظ الأصلي أو المحاسبة. تابع نفس المعرف والإفادة الثابتين فقط عبر الحفظ الأصلي المُصرّح به؛ دون حركة فعلية جديدة."), caution = true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = recover, enabled = enabled, modifier = Modifier.touchTarget()) { Text(retailText("Recover same ID only", "استرداد نفس المعرف فقط")) }
            if (value.isPhysicalStart && !value.dispatched && !value.recorded) Button(onClick = { confirmation = true },
                enabled = enabled && value.summary.isNotEmpty(), modifier = Modifier.touchTarget()) { Text(retailText("Start ONCE; wait for live instruction", "ابدأ مرة واحدة وانتظر التعليمات السارية")) }
            if (!value.isPhysicalStart && !value.recorded) Button(onClick = sql, enabled = enabled, modifier = Modifier.touchTarget()) {
                Text(if (value.requiresNativeRetention)
                    retailText("Retain saved linked resolution — no new action", "حفظ التسوية المرتبطة المحفوظة — دون حركة جديدة")
                else retailText("Submit saved SQL-only command", "إرسال الأمر المحفوظ الخاص بقاعدة البيانات فقط"))
            }
        }
    }
    if (confirmation) AlertDialog(onDismissRequest = { confirmation = false },
        title = { Text(retailText("Start this exact saved attempt once?", "بدء هذه المحاولة المحفوظة الدقيقة مرة واحدة؟")) },
        text = { Text(retailText("This call alone is NOT an instruction to move money. Wait for the genuine live instruction; never repeat a human action because a response is missing.",
            "هذا الاستدعاء وحده ليس تعليمات لحركة مال. انتظر التعليمات الأصلية السارية؛ لا تكرر حركة بشرية بسبب غياب الرد.")) },
        confirmButton = { Button(onClick = { confirmation = false; start() }, enabled = enabled, modifier = Modifier.touchTarget()) {
            Text(retailText("Start exact saved request", "بدء الطلب المحفوظ الدقيق"))
        } }, dismissButton = { TextButton(onClick = { confirmation = false }, modifier = Modifier.touchTarget()) { Text(retailText("Cancel", "إلغاء")) } })
}

@Composable
internal fun RetailInstructionDetails(value: LocalRetailInstructionDisplay) {
    IdentifierText(retailText("Original Start operation", "عملية البدء الأصلية"), value.operationId.value)
    IdentifierText(retailText("Document / attempt target", "المستند المستهدف للمحاولة"), value.documentId.value)
    IdentifierText(retailText("Original cash session (unchanged)", "جلسة النقدية الأصلية دون تغيير"), value.cashSessionId.value)
    IdentifierText(retailText("Original observation ID", "معرف الإفادة الأصلية"), value.recordingOperationId.value)
    IdentifierText(retailText("Pre-reserved resolution ID", "معرف التسوية المحجوز مسبقًا"), value.resolutionOperationId.value)
    Text("\u2066${retailMoney(value.amount)} · ${value.tender}\u2069", style = MaterialTheme.typography.titleLarge)
    Text(retailText("External payments are cashier attestations, not bank confirmation.", "الدفعات الخارجية إفادات موظف وليست تأكيدًا مصرفيًا."))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RetailObservationEditor(display: LocalRetailInstructionDisplay, draft: LocalRetailObservationDraft,
    enabled: Boolean, resolution: Boolean, choose: (LocalRetailObserved) -> Unit,
    change: (LocalRetailInput, String) -> Unit, confirm: (Boolean) -> Unit) {
    Text(retailText("What actually happened? No default outcome is selected.", "ماذا حدث فعليًا؟ لم تُحدد نتيجة افتراضية."), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LocalRetailObserved.entries.filterNot { resolution && it == LocalRetailObserved.UNKNOWN ||
            display.kind == LocalRetailPhysicalKind.PAYMENT && it == LocalRetailObserved.CASH_PAID ||
            display.kind == LocalRetailPhysicalKind.REFUND && it == LocalRetailObserved.CASH_RECEIVED }.forEach { choice ->
            FilterChip(selected = draft.selected == choice, onClick = { choose(choice) }, enabled = enabled, modifier = Modifier.touchTarget(), label = { Text(when (choice) {
                LocalRetailObserved.UNKNOWN -> retailText("UNKNOWN", "غير معلوم")
                LocalRetailObserved.NOT_PAID -> retailText("NOT PAID", "لم يُدفع")
                LocalRetailObserved.CASH_RECEIVED -> retailText("Cash actually received", "نقدية مستلمة فعليًا")
                LocalRetailObserved.CASH_PAID -> if (display.subject == LocalPaymentSubject.FOOD)
                    retailText("Cash actually paid — enter the actual amount", "نقدية مدفوعة فعليًا — أدخل المبلغ الفعلي")
                    else retailText("Cash refund of ${retailMoney(display.amount)} actually paid", "رد نقدي بمبلغ ${retailMoney(display.amount)} مدفوع فعليًا")
                LocalRetailObserved.EXTERNAL_ATTESTED -> retailText("External attestation of ${retailMoney(display.amount)}", "إفادة دفع خارجي بمبلغ ${retailMoney(display.amount)}")
            }) })
        }
    }
    when (draft.selected) {
        LocalRetailObserved.CASH_RECEIVED -> {
            RetailInput(draft.received, retailText("Actually received (${display.amount.currency.name})", "المستلم فعليًا (${display.amount.currency.name})"), enabled, true) { change(LocalRetailInput.RECEIVED, it) }
            RetailInput(draft.change, retailText("Change actually given (${display.amount.currency.name})", "الباقي المعطى فعليًا (${display.amount.currency.name})"), enabled, true) { change(LocalRetailInput.CHANGE, it) }
        }
        LocalRetailObserved.NOT_PAID -> RetailInput(draft.reason, retailText("Reason for actual non-payment", "سبب عدم الدفع الفعلي"), enabled, ltr = false) { change(LocalRetailInput.REASON, it) }
        LocalRetailObserved.CASH_PAID -> if (display.subject == LocalPaymentSubject.FOOD) {
            RetailInput(draft.paid, retailText("Actually paid (${display.amount.currency.name}) — no default", "المدفوع فعليًا (${display.amount.currency.name}) — دون قيمة افتراضية"),
                enabled, decimal = true) { change(LocalRetailInput.PAID, it) }
        }
        LocalRetailObserved.EXTERNAL_ATTESTED -> {
            RetailInput(draft.terminal, if (display.requiresTerminal) retailText("Actual terminal (required by original policy)", "الطرفية الفعلية — مطلوبة بالسياسة الأصلية")
                else retailText("Actual terminal, if present", "الطرفية الفعلية إن وجدت"), enabled) { change(LocalRetailInput.TERMINAL, it) }
            RetailInput(draft.reference, if (display.requiresReference) retailText("Actual reference (required by original policy)", "المرجع الفعلي — مطلوب بالسياسة الأصلية")
                else retailText("Actual reference, if present", "المرجع الفعلي إن وجد"), enabled) { change(LocalRetailInput.REFERENCE, it) }
        }
        else -> Unit
    }
    if (display.subject == LocalPaymentSubject.FOOD && display.kind == LocalRetailPhysicalKind.REFUND) Notice(retailText(
        "Enter the amount actually paid, even when it differs from the instruction. A mismatch is retained and held for reconciliation, never changed to NOT PAID or the expected amount. External or unrepresentable evidence remains held; do not submit a false matching amount or pay again.",
        "أدخل المبلغ المدفوع فعلًا حتى إن اختلف عن التعليمات. يُحفظ الاختلاف ويُعلق للتسوية ولا يُحول إلى عدم دفع أو إلى المبلغ المتوقع. يظل الدليل الخارجي أو غير القابل للتمثيل معلقًا؛ لا ترسل مبلغًا مطابقًا غير صحيح ولا تدفع مجددًا."))
    else Notice(retailText("Cash collection preserves actual received/change amounts, including mismatches. Cash-refund and external-paid choices attest the EXACT displayed attempt amount. If a different amount cannot be represented, keep it UNKNOWN and reconcile; do not submit a false resolution. Tender or missing-reference mismatches may also remain pending.",
        "يحفظ التحصيل النقدي مبالغ الاستلام والباقي الفعلية بما فيها الاختلافات. يفيد خيارا الرد النقدي والدفع الخارجي بدفع مبلغ المحاولة المعروض بدقة. إذا تعذر تمثيل مبلغ مختلف فأبقِ النتيجة مجهولة وسوِّها؛ لا ترسل تسوية غير صحيحة. قد تظل اختلافات الوسيلة أو المرجع المفقود معلقة أيضًا."))
    ConfirmRow(if (resolution) retailText("I am recording evidence about the OLD action; I will not take or pay money again.", "أسجل دليل الحركة السابقة ولن أحصل أو أدفع مالًا مرة أخرى.")
        else retailText("This is my actual observation of this one original action, not an instruction to repeat it.", "هذه إفادتي الفعلية عن هذه الحركة الأصلية الواحدة وليست تعليمات لتكرارها."),
        draft.confirmed, enabled, confirm)
}

@Composable
internal fun RetailInput(value: String, label: String, enabled: Boolean, decimal: Boolean = false,
    ltr: Boolean = true, change: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = change, label = { Text(label) }, enabled = enabled, singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.let { if (ltr) it.copy(textDirection = TextDirection.Ltr) else it },
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Text), modifier = Modifier.fillMaxWidth())
}

@Composable
internal fun RetailValues(values: List<LocalRecordValue>) {
    val language = LocalAppLanguage.current
    values.forEach { value ->
        Text(language.text(value.label), style = MaterialTheme.typography.labelMedium)
        Text(if (value.value.english == value.value.arabic) "\u2066${value.value.english}\u2069" else language.text(value.value))
    }
}

@Composable
internal fun RetailProgress(value: LocalRetailPhysicalProgress) {
    val language = LocalAppLanguage.current
    Text(when (value.state) {
        LocalRetailDurableState.SAVED_NOT_DISPATCHED -> retailText("Saved; not dispatched", "محفوظ ولم يُرسل")
        LocalRetailDurableState.DISPATCH_UNRESOLVED -> retailText("Dispatch unresolved", "الإرسال غير محسوم")
        LocalRetailDurableState.START_RECORDED_NO_INSTRUCTION -> retailText("Start recorded — NO instruction here", "سُجل البدء — لا تعليمات هنا")
        LocalRetailDurableState.COLLECTION_PREPARED -> retailText("Collection prepared — NO money", "أُعدّ التحصيل — دون مال")
        LocalRetailDurableState.STOCK_HELD_NO_INSTRUCTION -> retailText("Stock held — NO instruction", "مخزون مقيد — دون تعليمات")
        LocalRetailDurableState.SQL_ORIGINAL_PENDING -> retailText("SQL original observation pending", "إفادة أصلية معلقة بقاعدة البيانات")
        LocalRetailDurableState.SQL_RESOLUTION_PENDING -> retailText("SQL resolution pending", "تسوية معلقة بقاعدة البيانات")
        LocalRetailDurableState.OBSERVATION_APPLIED_UNRESOLVED -> retailText("UNKNOWN applied — still UNRESOLVED", "طُبقت النتيجة المجهولة — ما زالت غير محسومة")
        LocalRetailDurableState.OBSERVATION_APPLIED_NO_MONEY -> retailText("NOT PAID applied — NO money movement", "طُبق عدم الدفع — دون حركة مالية")
        LocalRetailDurableState.OBSERVATION_ALREADY_APPLIED -> retailText("Already applied — inspect original outcome", "طُبقت سابقًا — افحص النتيجة الأصلية")
        LocalRetailDurableState.FINANCIALLY_APPLIED -> retailText("Recorded attestation financially applied", "طُبقت الإفادة المسجلة ماليًا")
        LocalRetailDurableState.REJECTED -> retailText("This SQL command was refused", "رُفض أمر قاعدة البيانات هذا")
        LocalRetailDurableState.SUPERVISOR_REVIEW_RECORDED -> retailText("Supervisor review durably audited", "سُجل تدقيق مراجعة المشرف بشكل دائم")
    }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    Notice(language.text(value.message), caution = value.state !in setOf(LocalRetailDurableState.FINANCIALLY_APPLIED,
        LocalRetailDurableState.SUPERVISOR_REVIEW_RECORDED, LocalRetailDurableState.COLLECTION_PREPARED))
    IdentifierText(retailText("Operation being viewed", "العملية المعروضة"), value.operationId.value)
    value.originalRecordingOperationId?.let { IdentifierText(retailText("Immutable original observation operation", "عملية الإفادة الأصلية الثابتة"), it.value) }
    value.recordingOperationId?.let { IdentifierText(retailText("Recorded observation operation", "عملية الإفادة المسجلة"), it.value) }
    value.resolutionOperationId?.let { IdentifierText(retailText("Reserved resolution operation", "عملية التسوية المحجوزة"), it.value) }
}
