package com.ukkera.pos.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import com.ukkera.pos.app.*
import com.ukkera.pos.app.navigation.NavigationToken
import kotlinx.coroutines.delay

/** Purchasing-only payout lane. No customer tender, collection due or supervisor aliases. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocalSupplierCashPanel(controller: LocalSupplierCashController, parent: LocalWorkspaceState, token: NavigationToken) {
    if (parent.workflow != LocalWorkflow.PURCHASING) return
    if (!parent.purchasingQuery.includeCosts) {
        Notice(retailText("Supplier payout and advance amounts require an explicit current cost read. Enable Include costs; no hidden amount or cash balance is inferred as zero.",
            "تتطلب مبالغ دفع المورد والدفعات المقدمة قراءة تكلفة صريحة وسارية. فعّل عرض التكلفة؛ لا يُفترض مبلغ مخفي أو رصيد نقدي صفرًا."))
        return
    }
    val state by controller.state.collectAsState()
    if (state.reference != parent.reference || state.purchasingQuery != parent.purchasingQuery || !controller.displayCurrent(token)) return
    val language = LocalAppLanguage.current
    val parentReady = !parent.working && parent.form == null && !parent.formFrozen
    val ready = parentReady && state.canPrepare
    LaunchedEffect(controller, token, state.instruction?.operationId) {
        if (state.instruction != null) while (true) {
            delay(200)
            controller.sampleInstruction(token) // Withdraw-only sampling, not authority.
        }
    }
    Surface(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Outline)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeading(retailText("Supplier CASH payout, paid advances and own evidence recovery", "دفع نقدية المورد والدفعات المقدمة واسترداد الدليل الخاص"))
            Notice(retailText("Gross PAYABLE recognition stays unchanged. Credits, earlier paid advances and today's actual payout are separate sources. Allocating an earlier advance is a separate nonphysical command under Advances, never another payout.",
                "يبقى الاعتراف الإجمالي بالمستحق دون تغيير. الخصومات والدفعات المقدمة السابقة وصرف اليوم الفعلي مصادر منفصلة. تخصيص دفعة سابقة أمر منفصل غير فعلي في الدفعات المقدمة، وليس صرفًا جديدًا."))
            Notice(retailText("Prepared input, live instruction, in-memory capture, durable native retention and SQL financial application are distinct. Recovery never authorizes another physical payout. Each action needs current explicit rights and the actual original cash custody.",
                "المدخل المعد والتعليمات السارية والتسجيل في الذاكرة والحفظ الأصلي الدائم والتطبيق المالي خطوات مختلفة. لا يجيز الاسترداد دفعًا فعليًا جديدًا. لكل حركة صلاحيات صريحة سارية وعهدة نقدية أصلية فعلية."))
            if (state.working) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(retailText("Waiting for the original supplier request. Lock/end remains available; durable evidence is not erased.",
                    "بانتظار طلب المورد الأصلي. يظل القفل أو الإنهاء متاحًا؛ ولا تُمحى الأدلة الدائمة."), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            state.problem?.let { Notice(localProblem(it), caution = true) }
            if (state.inputProblem) Notice(retailText("Complete the exact selector/reason or choose and confirm the actual observation. Actual amount and currency have no default; signed/foreign/unsupported-precision evidence is retained literally, not rounded or posted as supported money.",
                "أكمل المحدد والسبب الدقيقين أو اختر وأكد الإفادة الفعلية. لا قيمة افتراضية للمبلغ أو العملة الفعليين؛ يُحفظ الدليل السالب أو الأجنبي أو غير المدعوم الدقة حرفيًا دون تقريب أو ترحيل كمال مدعوم."), caution = true)
            state.notice?.let { Notice(language.text(it), caution = true) }
            val live = state.instruction
            if (live != null || state.busy == LocalSupplierBusy.STARTING) {
                if (live != null && controller.instructionCurrent(token)) {
                    Text(retailText("ONE LIVE SUPPLIER CASH-OUT INSTRUCTION", "تعليمات صرف نقدية واحدة سارية للمورد"),
                        style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
                    SupplierInstructionDetails(live, live = true)
                    Notice(retailText("Pay only this exact instruction once. If any payout already happened, do NOT repeat it. Record the literal actual observation, even when amount, currency or tender does not match. Never replace actual evidence with the expected amount or NOT PAID.",
                        "ادفع وفق هذه التعليمات الدقيقة مرة واحدة فقط. إن حدث أي صرف فلا تكرره. سجل الإفادة الفعلية الحرفية حتى عند اختلاف المبلغ أو العملة أو الوسيلة. لا تستبدل الدليل الفعلي بالمبلغ المتوقع أو بعدم الدفع."), caution = true)
                    SupplierObservationEditor(state.observation, parentReady && !state.working, resolution = false,
                        choose = { controller.chooseObservation(it, token) },
                        change = { field, value -> controller.changeInput(field, value, token) },
                        confirm = { controller.confirmObservation(it, token) })
                    Button(onClick = { controller.capture(token) }, enabled = parentReady && !state.working && state.observation.confirmed,
                        modifier = Modifier.touchTarget()) { Text(retailText("Capture this actual ORIGINAL now", "تسجيل هذه الإفادة الأصلية الفعلية الآن")) }
                } else Notice(retailText("No live supplier instruction is available here. A missing response/display never proves that no human payout occurred.",
                    "لا توجد تعليمات مورد سارية هنا. لا يثبت غياب الرد أو العرض عدم حدوث صرف بشري."), caution = true)
                OutlinedButton(onClick = { controller.endInstruction(token) }, modifier = Modifier.touchTarget()) {
                    Text(retailText("End instruction / stop waiting — keep evidence", "إنهاء التعليمات أو الانتظار — مع حفظ الدليل"))
                }
            } else {
                state.capture?.let { capture ->
                    Notice(when (capture) {
                        is LocalRetailPhysicalCapture.CapturedInMemory -> retailText("Supplier observation is in process memory ONLY. Durable native retention, SQL intake and financial application are not confirmed by this display. Do not pay again.",
                            "إفادة المورد في ذاكرة العملية فقط. لا يؤكد هذا العرض الحفظ الأصلي الدائم أو الإدخال أو التطبيق المالي. لا تدفع مجددًا.")
                        LocalRetailPhysicalCapture.InvalidInput -> retailText("No valid capture body was accepted. The old physical outcome is still unresolved; do not infer NOT PAID or repeat payout.",
                            "لم تُقبل إفادة تسجيل صالحة. ما زالت نتيجة الصرف السابقة غير محسومة؛ لا تفترض عدم الدفع ولا تكرر الصرف.")
                        LocalRetailPhysicalCapture.Rejected -> retailText("Original capture was not granted. Preserve the existing IDs and evidence; no physical outcome is inferred.",
                            "لم يُمنح التسجيل الأصلي. احتفظ بالمعرفات والأدلة الموجودة؛ ولا تُستنتج نتيجة فعلية.")
                        LocalRetailPhysicalCapture.Uncertain -> retailText("Capture disposition is UNCERTAIN. The native owner retains any candidate; do not retry capture or start a replacement payout.",
                            "حالة التسجيل غير مؤكدة. يحتفظ المالك الأصلي بأي إفادة محتملة؛ لا تكرر التسجيل ولا تبدأ صرفًا بديلًا.")
                    }, caution = true, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
                }
                state.preview?.let { preview ->
                    Text(language.text(preview.title), style = MaterialTheme.typography.titleMedium)
                    IdentifierText(retailText("Immutable supplier operation ID", "معرف عملية المورد الثابت"), preview.operationId.value)
                    RetailValues(preview.summary)
                    Notice(retailText("Saving is not dispatch, native retention, accounting success or a payout instruction.",
                        "الحفظ ليس إرسالًا أو حفظًا أصليًا أو نجاحًا محاسبيًا أو تعليمات صرف."))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { controller.stage(token) }, enabled = parentReady && !state.working, modifier = Modifier.touchTarget()) {
                            Text(if (preview.saveAttempted) retailText("Retry saving SAME ID/body", "إعادة حفظ نفس المعرف والإفادة")
                                else retailText("Durably save exact supplier request", "حفظ طلب المورد الدقيق بشكل دائم"))
                        }
                        if (!preview.saveAttempted) OutlinedButton(onClick = { controller.discardPreview(token) }, enabled = !state.working,
                            modifier = Modifier.touchTarget()) { Text(retailText("Discard unsaved preview", "إهمال المعاينة غير المحفوظة")) }
                    }
                }
                state.selected?.let { value -> SupplierSavedCard(value, ready,
                    start = { controller.start(value.operationId, token) }, sql = { controller.continueSql(value.operationId, token) },
                    recover = { controller.recover(value.operationId, token) }) }
                state.progress?.let { value ->
                    SupplierProgressActions(value, controller, token, ready)
                    if (controller.canResolve(value)) {
                        SectionHeading(retailText("Own applied UNKNOWN — fixed resolution, OLD payout only", "نتيجة مجهولة مطبقة خاصة — تسوية ثابتة للصرف السابق فقط"))
                        value.reviewDisplay?.let { SupplierInstructionDetails(it, live = false) }
                        Notice(retailText("This authenticated historical DATA is not a live instruction, supervisor approval or custody takeover. Keep the original fixed resolution ID and record genuine old-action evidence under the original paying owner. Never pay again. Known contradictory/foreign originals stay held; they are not overwritten by this UNKNOWN-only lane.",
                            "هذه بيانات تاريخية موثوقة وليست تعليمات سارية أو موافقة مشرف أو استحواذًا على العهدة. احتفظ بمعرف التسوية الأصلي الثابت وسجل دليل الحركة السابقة الحقيقي تحت المالك الدافع الأصلي. لا تدفع مجددًا. تبقى الأصول المتناقضة أو الأجنبية معلقة ولا تُستبدل عبر هذا المسار للنتيجة المجهولة فقط."), caution = true)
                        SupplierObservationEditor(state.observation, ready, resolution = true,
                            choose = { controller.chooseObservation(it, token) },
                            change = { field, amount -> controller.changeInput(field, amount, token) },
                            confirm = { controller.confirmObservation(it, token) })
                        Button(onClick = { controller.resolve(token) }, enabled = ready && state.observation.confirmed, modifier = Modifier.touchTarget()) {
                            Text(retailText("Prepare fixed-ID old-payout resolution", "إعداد تسوية الصرف السابق بالمعرف الثابت"))
                        }
                    }
                    if (state.selected?.recorded == true && state.selected?.operationId == value.operationId) OutlinedButton(
                        onClick = { controller.acknowledge(token) }, enabled = ready, modifier = Modifier.touchTarget()) {
                        Text(retailText("Acknowledge client result only — keep evidence", "إقرار نتيجة العميل فقط — مع حفظ الدليل"))
                    }
                }
                if (state.preview == null && state.freshPayoutAllowed) {
                    SectionHeading(retailText("Prepare today's actual supplier payout — not invoice recognition", "إعداد صرف المورد الفعلي اليوم — ليس اعترافًا بالفاتورة"))
                    RetailInput(state.supplierId, retailText("Actual supplier ID", "معرف المورد الفعلي"), ready) { controller.changeInput(LocalSupplierInput.SUPPLIER_ID, it, token) }
                    RetailInput(state.invoiceId, retailText("Posted invoice ID; leave empty for an unapplied paid advance", "معرف فاتورة مرحّلة؛ اتركه فارغًا لدفعة مقدمة مدفوعة غير مخصصة"), ready) {
                        controller.changeInput(LocalSupplierInput.INVOICE_ID, it, token)
                    }
                    RetailInput(state.payout, retailText("Today's actual new payout (EGP) — no previous advance included", "الصرف الجديد الفعلي اليوم بالجنيه — دون تضمين دفعة سابقة"), ready, decimal = true) {
                        controller.changeInput(LocalSupplierInput.PAYOUT, it, token)
                    }
                    RetailInput(state.reason, retailText("Payout reason code (letters/digits/._-)", "رمز سبب الصرف — أحرف أو أرقام أو ._-"), ready, ltr = false) {
                        controller.changeInput(LocalSupplierInput.REASON, it, token)
                    }
                    Button(onClick = { controller.preparePayout(token) }, enabled = ready, modifier = Modifier.touchTarget()) {
                        Text(retailText("Read authentic sources and prepare — NO payout yet", "قراءة المصادر الموثوقة والإعداد — دون صرف بعد"))
                    }
                }
                if (!state.freshPayoutAllowed) Notice(retailText(
                    "This invoice is capture-only or its original format has not been read. No new supplier payout is available here. Existing original evidence and recovery remain accessible below.",
                    "هذه الفاتورة للحفظ فقط أو لم تُقرأ صيغتها الأصلية. لا يتاح صرف جديد للمورد هنا. تظل الأدلة الأصلية السابقة واستردادها متاحة أدناه."))
                SectionHeading(retailText("Own supplier pending and same-ID SQL recovery", "معلقات المورد الخاصة واسترداد قاعدة البيانات بنفس المعرف"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { controller.loadPending(token) }, enabled = ready, modifier = Modifier.touchTarget()) {
                        Text(retailText("Read own originals and saved supplier operations", "قراءة الأصول الخاصة وعمليات المورد المحفوظة"))
                    }
                    if (state.nextFactAfter != null) OutlinedButton(onClick = { controller.loadPending(token, next = true) }, enabled = ready,
                        modifier = Modifier.touchTarget()) { Text(retailText("Next same ORIGINAL-ID scan — replaces page", "التالي في مسح معرف الأصل نفسه — يستبدل الصفحة")) }
                }
                if (state.pendingLoaded) Text(retailText("The actual ORIGINAL-ID cursor is retained even for an empty filtered page. Resolutions keep their fixed linked IDs; no page absence grants another payout.",
                    "يُحفظ مؤشر معرف الأصل الفعلي حتى للصفحة المصفاة الفارغة. تحتفظ التسويات بمعرفاتها المرتبطة الثابتة؛ لا يجيز غياب الصفحة صرفًا آخر."))
                state.saved.filterNot { it.operationId == state.selected?.operationId }.forEach { value -> key(value.operationId.value) {
                    SupplierSavedCard(value, ready, start = { controller.start(value.operationId, token) },
                        sql = { controller.continueSql(value.operationId, token) }, recover = { controller.recover(value.operationId, token) })
                } }
                state.facts.forEach { value -> key(value.originalRecordingOperationId?.value ?: value.operationId.value) {
                    SupplierProgressActions(value, controller, token, ready)
                } }
                RetailInput(state.recoveryId, retailText("Existing supplier original recording ID or saved operation ID", "معرف إفادة مورد أصلية موجودة أو عملية محفوظة"), ready) {
                    controller.changeInput(LocalSupplierInput.RECOVERY_ID, it, token)
                }
                OutlinedButton(onClick = { controller.recoverInput(token) }, enabled = ready, modifier = Modifier.touchTarget()) {
                    Text(retailText("Read exact SQL state only — never rearm", "قراءة حالة قاعدة البيانات الدقيقة فقط — دون إعادة تسليح"))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SupplierSavedCard(value: LocalRetailSavedOperation, enabled: Boolean, start: () -> Unit, sql: () -> Unit, recover: () -> Unit) {
    var confirmation by remember(value.operationId) { mutableStateOf(false) }
    Text(LocalAppLanguage.current.text(value.label), style = MaterialTheme.typography.titleMedium)
    IdentifierText(retailText("Saved supplier operation ID", "معرف عملية المورد المحفوظة"), value.operationId.value)
    RetailValues(value.summary)
    Notice(when {
        value.recorded -> retailText("SQL result recorded. Recover the exact source; a Start result is NOT another payout instruction.", "سُجلت نتيجة قاعدة البيانات. استرد المصدر الدقيق؛ نتيجة البدء ليست تعليمات صرف أخرى.")
        value.dispatched -> retailText("Previously dispatched and unresolved until recovery. Physical Starts are NEVER resent here.", "أُرسلت سابقًا وتظل غير محسومة حتى الاسترداد. لا يُعاد إرسال البدء الفعلي هنا مطلقًا.")
        else -> retailText("Durably saved, not dispatched. Review the immutable source, amount and original IDs.", "حُفظت بشكل دائم ولم تُرسل. راجع المصدر والمبلغ والمعرفات الأصلية الثابتة.")
    }, caution = value.dispatched && !value.recorded)
    if (value.requiresNativeRetention && !value.recorded) Notice(retailText("Saved linked evidence is not native-retention success. Continue only this exact old-payout resolution ID/body; no new physical action.",
        "حفظ الدليل المرتبط ليس نجاحًا للحفظ الأصلي. تابع معرف وإفادة تسوية الصرف السابق الدقيقة فقط؛ دون حركة فعلية جديدة."), caution = true)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = recover, enabled = enabled, modifier = Modifier.touchTarget()) { Text(retailText("Recover same ID only", "استرداد نفس المعرف فقط")) }
        if (value.isPhysicalStart && !value.dispatched && !value.recorded) Button(onClick = { confirmation = true }, enabled = enabled && value.summary.isNotEmpty(),
            modifier = Modifier.touchTarget()) { Text(retailText("Start ONCE; await live supplier instruction", "ابدأ مرة واحدة وانتظر تعليمات المورد السارية")) }
        if (!value.isPhysicalStart && !value.recorded) Button(onClick = sql, enabled = enabled, modifier = Modifier.touchTarget()) {
            Text(if (value.requiresNativeRetention) retailText("Retain saved old-payout resolution", "حفظ تسوية الصرف السابق المحفوظة")
                else retailText("Submit saved supplier SQL-only Apply", "إرسال تطبيق المورد المحفوظ لقاعدة البيانات فقط"))
        }
    }
    if (confirmation) AlertDialog(onDismissRequest = { confirmation = false },
        title = { Text(retailText("Start this exact saved supplier payout once?", "بدء صرف المورد المحفوظ الدقيق مرة واحدة؟")) },
        text = { Text(retailText("This call alone is NOT a payout instruction. Wait for genuine live native instructions. Never repeat a human action because its result is missing.",
            "هذا الاستدعاء وحده ليس تعليمات صرف. انتظر التعليمات الأصلية السارية. لا تكرر حركة بشرية بسبب غياب نتيجتها.")) },
        confirmButton = { Button(onClick = { confirmation = false; start() }, enabled = enabled, modifier = Modifier.touchTarget()) { Text(retailText("Start exact saved request", "بدء الطلب المحفوظ الدقيق")) } },
        dismissButton = { TextButton(onClick = { confirmation = false }, modifier = Modifier.touchTarget()) { Text(retailText("Cancel", "إلغاء")) } })
}

@Composable
private fun SupplierInstructionDetails(value: LocalRetailInstructionDisplay, live: Boolean) {
    IdentifierText(retailText("Original supplier Start", "بدء المورد الأصلي"), value.operationId.value)
    IdentifierText(retailText("Actual supplier/invoice source", "مصدر المورد أو الفاتورة الفعلي"), value.documentId.value)
    IdentifierText(retailText("Original paying cash session — unchanged", "جلسة النقدية الدافعة الأصلية — دون تغيير"), value.cashSessionId.value)
    IdentifierText(retailText("Original observation ID", "معرف الإفادة الأصلي"), value.recordingOperationId.value)
    IdentifierText(retailText("Original pre-reserved resolution ID", "معرف التسوية الأصلي المحجوز مسبقًا"), value.resolutionOperationId.value)
    Text(retailText(if (live) "Today's actual instructed payout: ${retailMoney(value.amount)}" else "Historical instructed amount — NOT another payout: ${retailMoney(value.amount)}",
        if (live) "الصرف الفعلي الموجه اليوم: ${retailMoney(value.amount)}" else "مبلغ التعليمات التاريخي — ليس صرفًا آخر: ${retailMoney(value.amount)}"), style = MaterialTheme.typography.titleLarge)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SupplierProgressActions(value: LocalRetailPhysicalProgress, controller: LocalSupplierCashController, token: NavigationToken, enabled: Boolean) {
    RetailProgress(value)
    RetailValues(value.reviewValues)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (controller.canPrepareOwnApplication(value)) OutlinedButton(onClick = { controller.prepareApply(value, token) }, enabled = enabled,
            modifier = Modifier.touchTarget()) { Text(if (value.phase == LocalRetailObservationPhase.RESOLUTION)
                retailText("Prepare Apply of actual fixed resolution — SQL only", "إعداد تطبيق التسوية الثابتة الفعلية — قاعدة البيانات فقط")
            else retailText("Prepare Apply of actual original — SQL only", "إعداد تطبيق الأصل الفعلي — قاعدة البيانات فقط")) }
        if (value.originalRecordingOperationId != null) OutlinedButton(onClick = { controller.readOriginal(value, token) }, enabled = enabled,
            modifier = Modifier.touchTarget()) { Text(retailText("Read its exact own ORIGINAL source — no supervisor", "قراءة مصدره الأصلي الخاص الدقيق — دون مشرف")) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SupplierObservationEditor(draft: LocalSupplierObservationDraft, enabled: Boolean, resolution: Boolean,
    choose: (LocalSupplierObserved) -> Unit, change: (LocalSupplierInput, String) -> Unit, confirm: (Boolean) -> Unit) {
    Text(retailText("What actually happened to the OLD/one original payout? No default outcome, amount or currency.",
        "ماذا حدث فعليًا للصرف السابق أو الأصلي الواحد؟ لا نتيجة أو مبلغ أو عملة افتراضية."), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LocalSupplierObserved.entries.filterNot { resolution && it == LocalSupplierObserved.UNKNOWN }.forEach { choice ->
            FilterChip(selected = draft.selected == choice, onClick = { choose(choice) }, enabled = enabled, modifier = Modifier.touchTarget(), label = { Text(when (choice) {
                LocalSupplierObserved.UNKNOWN -> retailText("UNKNOWN", "غير معلوم")
                LocalSupplierObserved.NOT_PAID -> retailText("NOT PAID", "لم يُدفع")
                LocalSupplierObserved.CASH_PAID -> retailText("CASH actually paid — enter literal amount", "نقدية مدفوعة فعليًا — أدخل المبلغ الحرفي")
                LocalSupplierObserved.EXTERNAL_ATTESTED -> retailText("EXTERNAL actual attestation — stays held", "إفادة خارجية فعلية — تبقى معلقة")
            }) })
        }
    }
    when (draft.selected) {
        LocalSupplierObserved.NOT_PAID -> RetailInput(draft.nonpaymentReason, retailText("Actual non-payment reason code", "رمز سبب عدم الدفع الفعلي"), enabled, ltr = false) {
            change(LocalSupplierInput.NONPAYMENT_REASON, it)
        }
        LocalSupplierObserved.CASH_PAID, LocalSupplierObserved.EXTERNAL_ATTESTED -> {
            RetailInput(draft.actualAmount, retailText("Literal actual amount — signed decimal, preserve precision; NO default", "المبلغ الفعلي الحرفي — عشري يقبل السالب ويحفظ الدقة؛ دون قيمة افتراضية"), enabled) {
                change(LocalSupplierInput.ACTUAL_AMOUNT, it)
            }
            RetailInput(draft.actualCurrency, retailText("Exact observed currency identifier (EGP or foreign) — NO default", "معرف العملة المشاهد الدقيق — جنيه أو أجنبية؛ دون قيمة افتراضية"), enabled) {
                change(LocalSupplierInput.ACTUAL_CURRENCY, it)
            }
            if (draft.selected == LocalSupplierObserved.EXTERNAL_ATTESTED) RetailInput(draft.externalReference,
                retailText("Actual external reference, if present", "المرجع الخارجي الفعلي إن وجد"), enabled) { change(LocalSupplierInput.EXTERNAL_REFERENCE, it) }
        }
        else -> Unit
    }
    Notice(retailText("Literal currency identifier and decimal precision are retained. Only exact supported EGP matching the original positive CASH amount may apply money; foreign/negative/zero/excess/unsupported precision and external tender stay pending with reservations. No clipping, FX, guessed currency, expected-amount substitution or extra payout.",
        "يُحفظ معرف العملة الحرفي ودقة الكسور. لا يطبق المال إلا جنيه مدعوم مطابق تمامًا لمبلغ النقدية الأصلي الموجب؛ تبقى القيم الأجنبية أو السالبة أو الصفرية أو الزائدة أو غير المدعومة الدقة والوسائل الخارجية معلقة مع الحجوزات. دون قص أو تحويل عملة أو تخمينها أو استبدال المبلغ أو صرف إضافي."))
    ConfirmRow(if (resolution) retailText("This is genuine evidence about the OLD payout. I will not pay money again.", "هذا دليل حقيقي عن الصرف السابق. لن أدفع مالًا مجددًا.")
        else retailText("This is my literal actual observation of this one original payout, not permission to repeat it.", "هذه إفادتي الفعلية الحرفية عن هذا الصرف الأصلي الواحد، وليست إذنًا لتكراره."), draft.confirmed, enabled, confirm)
}
