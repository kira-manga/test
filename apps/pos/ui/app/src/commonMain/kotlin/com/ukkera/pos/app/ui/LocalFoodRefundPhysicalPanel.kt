package com.ukkera.pos.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ukkera.pos.app.*
import com.ukkera.pos.app.navigation.NavigationToken

/** Separate accepted FOOD liability and same-paying-cashier reads; no supervisor/retail alias. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FoodRefundPhysicalReadPanel(state: LocalRetailWorkspace, controller: LocalRetailPhysicalController,
    token: NavigationToken, enabled: Boolean) {
    SectionHeading(retailText("Current accepted FOOD liability", "التزام رد الطعام المقبول الحالي"))
    state.foodRefundId?.let { IdentifierText(retailText("Accepted refund", "الرد المقبول"), it.value) }
    OutlinedButton(onClick = { controller.loadFoodRefund(token) }, enabled = enabled, modifier = Modifier.touchTarget()) {
        Text(retailText("Read actual accepted dues and own existing attempts", "قراءة المستحقات المقبولة الفعلية والمحاولات الأصلية الخاصة"))
    }
    state.foodRefund?.let { context ->
        IdentifierText(retailText("Original FOOD order", "طلب الطعام الأصلي"), context.orderId.value)
        Text(retailText("Original quoted total (not refund value): ${retailMoney(context.originalQuotedTotal)}",
            "إجمالي التسعير الأصلي — ليس قيمة الرد: ${retailMoney(context.originalQuotedTotal)}"))
        Text(retailText("Actual collected tender: ${retailMoney(context.collected)} · Recorded refunds: ${retailMoney(context.refunded)} · Due: ${retailMoney(context.due)}",
            "وسيلة الدفع المحصلة فعليًا: ${retailMoney(context.collected)} · الرد المسجل: ${retailMoney(context.refunded)} · المستحق: ${retailMoney(context.due)}"))
        if (context.dues.isEmpty()) Notice(retailText("No actual-tender due is present in this complete read. This does not remove any native or audit evidence.",
            "لا يوجد مستحق بوسيلة دفع فعلية في هذه القراءة الكاملة. لا يُحذف أي دليل أصلي أو تدقيقي."))
        context.dues.forEach { due -> key(due.originalPaymentId.value) {
            IdentifierText(retailText("Authentic original payment", "الدفع الأصلي الموثوق"), due.originalPaymentId.value)
            Text("\u2066${retailMoney(due.amount)} · ${due.tender}\u2069", style = MaterialTheme.typography.titleMedium)
            if (due.unresolved) Notice(retailText("An earlier payout attempt is unresolved. Do not pay again; recover that original attempt.",
                "محاولة رد سابقة غير محسومة. لا تدفع مجددًا؛ استرد تلك المحاولة الأصلية."), caution = true)
            if (due.tender != "CASH") Notice(retailText("This first lane supports authentic original CASH only. Alternative tender authority remains unavailable.",
                "يدعم هذا المسار الأول النقدية الأصلية الموثوقة فقط. تظل سلطة وسيلة الدفع البديلة غير متاحة."), caution = true)
            OutlinedButton(onClick = { controller.prepareFoodRefund(due.originalPaymentId, token) },
                enabled = enabled && !due.unresolved && due.tender == "CASH", modifier = Modifier.touchTarget()) {
                Text(retailText("Prepare one refund of this actual due — not an instruction", "إعداد رد واحد لهذا المستحق الفعلي — ليس تعليمات حركة"))
            }
        } }
        context.ownRecoveries.forEach { recovery -> key(recovery.attemptId.value) {
            IdentifierText(retailText("Own existing payout attempt", "محاولة الرد الأصلية الخاصة"), recovery.attemptId.value)
            IdentifierText(retailText("Original paying session", "جلسة الدفع الأصلية"), recovery.cashSessionId.value)
            IdentifierText(retailText("Original observation ID", "معرف الإفادة الأصلية"), recovery.recordingOperationId.value)
            IdentifierText(retailText("Fixed resolution ID", "معرف التسوية الثابت"), recovery.resolutionOperationId.value)
            Text("\u2066${retailMoney(recovery.amount)} · ${recovery.status}\u2069")
            OutlinedButton(onClick = { controller.recoverFoodRefund(recovery, token) }, enabled = enabled, modifier = Modifier.touchTarget()) {
                Text(retailText("Read this exact attempt — never repeat payout/capture", "قراءة هذه المحاولة الدقيقة — دون تكرار الدفع أو التسجيل"))
            }
        } }
        if (context.dues.any { it.unresolved } && context.ownRecoveries.isEmpty()) Notice(retailText(
            "No genuine own native-slot selector is available here. Another payer's custody or older unsupported recording stays held; never manufacture IDs or start a replacement payout.",
            "لا يتاح هنا محدد أصلي موثوق خاص لخانة التخزين. تظل عهدة موظف آخر أو التسجيل القديم غير المدعوم معلقة؛ لا تختلق معرفات أو تبدأ ردًا بديلًا."), caution = true)
    }
    SectionHeading(retailText("Own FOOD payout pending — separate replacing pages", "معلقات رد الطعام الخاصة — صفحات مستقلة تستبدل المعروضة"))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { controller.loadPending(token) }, enabled = enabled, modifier = Modifier.touchTarget()) {
            Text(retailText("Read own ORIGINAL payout pending", "قراءة أصول الرد المعلقة الخاصة"))
        }
        OutlinedButton(onClick = { controller.loadPending(token, resolutions = true) }, enabled = enabled, modifier = Modifier.touchTarget()) {
            Text(retailText("Read own RESOLUTION payout pending", "قراءة تسويات الرد المعلقة الخاصة"))
        }
        if (state.nextFactAfter != null) OutlinedButton(onClick = { controller.loadPending(token, next = true) }, enabled = enabled, modifier = Modifier.touchTarget()) {
            Text(retailText("Next page of this same scan", "الصفحة التالية للمسح نفسه"))
        }
    }
    if (state.pendingLoaded) Text(retailText("These are own payout facts only. The actual underlying cursor is preserved even for an empty filtered page; absence is not permission to pay again.",
        "هذه إفادات رد خاصة فقط. يُحفظ مؤشر المسح الفعلي حتى للصفحة المصفاة الفارغة؛ لا يجيز الغياب الدفع مجددًا."))
    state.facts.forEach { value -> key(value.recordingOperationId?.value ?: value.operationId.value) {
        RetailProgress(value)
        RetailApplicationAction(value, controller, token, enabled)
        OutlinedButton(onClick = { controller.readFoodRefundOriginalOf(value, token) },
            enabled = enabled && controller.canPrepareOwnApplication(value), modifier = Modifier.touchTarget()) {
            Text(retailText("Read its own immutable ORIGINAL — no supervisor audit", "قراءة الأصل الثابت الخاص — دون تدقيق مشرف"))
        }
    } }
    SectionHeading(retailText("Same-ID recovery / same-paying-cashier original read", "استرداد بنفس المعرف وقراءة الأصل للموظف الدافع نفسه"))
    RetailInput(state.recoveryId, retailText("Existing FOOD payout fact or saved operation ID", "معرف إفادة رد طعام موجودة أو عملية محفوظة"), enabled) {
        controller.changeInput(LocalRetailInput.RECOVERY_ID, it, token)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { controller.recoverInput(token) }, enabled = enabled, modifier = Modifier.touchTarget()) {
            Text(retailText("Read own payout SQL progress only", "قراءة تقدم قاعدة البيانات للرد الخاص فقط"))
        }
        OutlinedButton(onClick = { controller.readFoodRefundOriginal(token) }, enabled = enabled, modifier = Modifier.touchTarget()) {
            Text(retailText("Read own immutable ORIGINAL for resolution", "قراءة الأصل الثابت الخاص للتسوية"))
        }
        if (state.foodRefundOriginalId != null) OutlinedButton(onClick = { controller.readFoodRefundOriginal(token, fresh = true) }, enabled = enabled,
            modifier = Modifier.touchTarget()) { Text(retailText("Fresh read of the same ORIGINAL", "قراءة جديدة للأصل نفسه")) }
    }
    state.progress?.takeIf { controller.canPrepareOwnApplication(it) }?.let { value ->
        OutlinedButton(onClick = { controller.readFoodRefundOriginalOf(value, token) }, enabled = enabled, modifier = Modifier.touchTarget()) {
            Text(retailText("Read this progress's original paying-cashier fact", "قراءة إفادة الموظف الدافع الأصلية لهذا التقدم"))
        }
    }
    state.foodRefundOriginal?.let { selected ->
        Notice(retailText("Authenticated own original-read DATA only: not a supervisor audit, instruction or takeover permission. Current paying custody and native retention are checked again on continuation.",
            "بيانات قراءة أصل خاص موثوقة فقط: ليست تدقيق مشرف أو تعليمات حركة أو صلاحية استحواذ. تُفحص عهدة الدفع الحالية والحفظ الأصلي مجددًا عند المتابعة."))
        RetailInstructionDetails(selected.display)
        RetailValues(selected.original.reviewValues)
        RetailApplicationAction(selected.original, controller, token, enabled)
        selected.resolution?.let { resolution ->
            RetailProgress(resolution)
            RetailValues(resolution.reviewValues)
            RetailApplicationAction(resolution, controller, token, enabled)
        }
        if (selected.original.originalUnknown && selected.resolution == null) {
            Notice(retailText("Retain actual evidence about the OLD payout using its already reserved resolution ID. Never pay again. Apply the original UNKNOWN before applying the resolution; wrong amount/tender stays held.",
                "احفظ الدليل الفعلي للرد السابق باستخدام معرف التسوية المحجوز بالفعل. لا تدفع مجددًا. طبق الأصل مجهول النتيجة قبل تطبيق التسوية؛ يظل اختلاف المبلغ أو الوسيلة معلقًا."), caution = true)
            RetailObservationEditor(selected.display, state.observation, enabled, resolution = true,
                choose = { controller.chooseObservation(it, token) }, change = { field, value -> controller.changeInput(field, value, token) },
                confirm = { controller.confirmObservation(it, token) })
            Button(onClick = { controller.resolve(token) }, enabled = enabled && state.observation.confirmed, modifier = Modifier.touchTarget()) {
                Text(retailText("Prepare same-cashier fixed-ID resolution — no new action", "إعداد تسوية الموظف نفسه بالمعرف الثابت — دون حركة جديدة"))
            }
        }
    }
}
