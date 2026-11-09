package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.sales.store.*

/** Opening a check is explicit and independent of selecting, quoting or sending menu items. */
internal fun foodCreateDraftForm(access: GatewayAccess): LocalActionForm? {
    if (!access.installed.has(LocalFoodStockCodecV2.COMMAND) || !access.allow(LocalAction.FOOD_CREATE_DRAFT)) return null
    return form(LocalAction.FOOD_CREATE_DRAFT, listOf(
        idField(LocalField.ORDER_ID, readOnly = true),
        choiceField(LocalField.ACTIVITY, listOf(LocalChoice("RESTAURANT", text("Restaurant", "مطعم")),
            LocalChoice("CAFE", text("Café", "مقهى")))),
        choiceField(LocalField.CHANNEL, listOf(LocalChoice("DINE_IN", text("Dine-in", "داخل المكان")),
            LocalChoice("COUNTER", text("Counter", "الكاونتر")), LocalChoice("TAKEAWAY", text("Takeaway", "سفري")),
            LocalChoice("DELIVERY", text("Delivery", "توصيل")))),
        scalar(LocalField.SERVICE_REFERENCE, LocalInputKind.TEXT, required = false, maximum = 80,
            help = text("A label, not managed table occupancy or ownership.", "وصف فقط، وليس إشغالًا أو ملكية لطاولة مُدارة.")),
        scalar(LocalField.SEAT_NOTE, LocalInputKind.TEXT, required = false, maximum = 160),
        scalar(LocalField.FOOD_DELIVERY_RECIPIENT, LocalInputKind.TEXT, required = false, maximum = 160),
        scalar(LocalField.FOOD_DELIVERY_CONTACT, LocalInputKind.TEXT, required = false, maximum = 160),
        scalar(LocalField.FOOD_DELIVERY_ADDRESS, LocalInputKind.TEXT, required = false, maximum = 600)),
        text("Confirm to save an empty unpaid check. Add menu items separately; opening creates no stock, kitchen or money effects.",
            "أكد لحفظ طلب فارغ غير مدفوع. أضف الأصناف لاحقًا؛ لا ينشئ فتح الطلب آثارًا على المخزون أو المطبخ أو النقد."), version = 2)
}

internal fun prepareFoodDraft(access: GatewayAccess, intent: LocalCommandIntent): MutationRequest {
    if (!access.installed.has(LocalFoodStockCodecV2.COMMAND)) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    if (intent.action != LocalAction.FOOD_CREATE_DRAFT || intent.formVersion !in 1..2 || intent.reference != null ||
        intent.lineField != null || intent.lines.isNotEmpty()) invalid()
    val values = InputValues(intent.values)
    val id = values.id(LocalField.ORDER_ID)
    access.requireAction(intent.action, id)
    val activity = LocalSalesActivity.valueOf(values.choice(LocalField.ACTIVITY))
    val channel = LocalSalesChannel.valueOf(values.choice(LocalField.CHANNEL))
    if (channel == LocalSalesChannel.DELIVERY && intent.formVersion != 2) invalid(LocalField.CHANNEL)
    val reference = if (channel == LocalSalesChannel.DELIVERY)
        values.optionalText(LocalField.SERVICE_REFERENCE)?.takeIf { it.isNotBlank() } ?: id.value
    else values.text(LocalField.SERVICE_REFERENCE)
    val seatNote = values.optionalText(LocalField.SEAT_NOTE).orEmpty()
    val delivery = if (channel == LocalSalesChannel.DELIVERY) LocalFoodDeliveryDetailsV1(
        foodDraftDeliveryText(values, LocalField.FOOD_DELIVERY_RECIPIENT, 160),
        foodDraftDeliveryText(values, LocalField.FOOD_DELIVERY_CONTACT, 160),
        foodDraftDeliveryText(values, LocalField.FOOD_DELIVERY_ADDRESS, 600)) else null
    // Inapplicable delivery fields remain unconsumed, even when supplied as blank text.
    values.finish()
    val draft = LocalFoodDraftCreateV1(id, activity, LocalFoodServiceV1(channel, reference, seatNote), delivery)
    val request = if (intent.formVersion == 2) LocalFoodStockRequestV2.ActivityProtected(draft)
        else LocalFoodStockRequestV2.CreateDraft(draft)
    return LocalFoodStockCodecV2.command(intent.operationId, request)
}

private fun foodDraftDeliveryText(values: InputValues, field: LocalField, maximum: Int): String {
    val value = values.text(field)
    if (value.isBlank() || value.length > maximum || value.any { it.code < 0x20 || it.code == 0x7f }) invalid(field)
    var index = 0
    while (index < value.length) {
        val code = value[index++].code
        if (code in 0xd800..0xdbff) {
            if (index == value.length || value[index].code !in 0xdc00..0xdfff) invalid(field)
            index++
        } else if (code in 0xdc00..0xdfff) invalid(field)
    }
    return value
}

/** Decode the saved original only. Never recreate a draft from current labels or new identifiers. */
internal fun savedFoodDraft(request: MutationRequest): LocalFoodDraftCreateV1? {
    if (request.command != LocalFoodStockCodecV2.COMMAND || request.actionApprovalId != null) return null
    val decoded = LocalFoodStockCodecV2.decode(request.payload)
    if (LocalFoodStockCodecV2.encode(decoded) != request.payload) return null
    return when (decoded) {
        is LocalFoodStockRequestV2.ActivityProtected -> decoded.request
        is LocalFoodStockRequestV2.CreateDraft -> decoded.request
        else -> null
    }
}

internal fun foodDraftRecorded(entry: StoredClientIntentV1, reply: MutationReply.Recorded): LocalFoodOperationDetails {
    val request = savedFoodDraft(entry.request()) ?: unavailable()
    val result = LocalFoodCodecV1.result(reply.result)
    if ((!reply.committed && result.code != reply.code) || result.orderId != null && result.orderId != request.orderId ||
        result.portions.isNotEmpty()) unavailable()
    if (reply.committed && (reply.code != "committed" || result.code != LocalFoodDraftProfileV1.CREATED || result.orderId != request.orderId ||
            result.orderRevision == null)) unavailable()
    return LocalFoodOperationDetails(result.code, result.orderId, result.orderRevision, emptyList(),
        if (reply.committed) LocalFoodUiQuery.Check(request.orderId) else null)
}
