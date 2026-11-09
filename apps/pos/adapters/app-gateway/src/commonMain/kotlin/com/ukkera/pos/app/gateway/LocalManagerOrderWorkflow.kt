package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.sales.store.LocalManagerOrderReviewCodec
import com.ukkera.pos.sales.store.LocalManagerOrderReviewQueryV1
import com.ukkera.pos.sales.store.LocalManagerOriginalQuoteCodec
import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction

/** Explicit read-only target review; never enters ordinary owner or payment forms. */
internal suspend fun managerOrderWorkflow(access: GatewayAccess, query: LocalWorkflowQuery): LocalWorkflowPage {
    if (query.workflow != LocalWorkflow.ORDERS || query.cursor != null) invalid()
    val target = searchedId(query.search.removePrefix("manager:"))
    query.reference?.let { if (it.kind != LocalRecordKind.ORDER || it.id != target) invalid() }
    val fullQuoteInstalled = access.installed.has(LocalManagerOriginalQuoteCodec.QUERY)
    if (!fullQuoteInstalled && !access.installed.has(LocalManagerOrderReviewCodec.QUERY))
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    // Only an early client refusal. The actual Master read uses CURRENT target-bound authority.
    if (!access.hasHint(Permission(SecurityAction.ORDER_REASSIGN, PermissionVerb.PERFORM), target))
        throw GatewayFailure(LocalServiceProblem.DENIED)
    val selector = LocalManagerOrderReviewQueryV1(target)
    val reply = access.read(if (fullQuoteInstalled) LocalManagerOriginalQuoteCodec.request(selector)
        else LocalManagerOrderReviewCodec.request(selector))
    val original = if (fullQuoteInstalled) LocalManagerOriginalQuoteCodec.decode(reply.payload, selector).orders.singleOrNull() else null
    val selected = (if (fullQuoteInstalled) original?.target
        else LocalManagerOrderReviewCodec.decode(reply.payload, selector).orders.singleOrNull())
        ?: return LocalWorkflowPage(LocalWorkflow.ORDERS, emptyList(), emptyList(), text(
            "No retail target is available for this authorized manager review. ${readCut(reply).english}",
            "لا يتوفر طلب تجزئة لهذه المراجعة المصرح بها للمدير. ${readCut(reply).arabic}"))
    val order = selected.order
    val quote = original?.let { StoredPricingSnapshotCodec.decode(it.quoteSnapshot) }
    val prices = order.lines.associateBy { it.portionId }
    if (order.selection.lines.size !in 1..256 || prices.size != order.lines.size ||
        order.selection.lines.map { it.portionId }.toSet().size != order.selection.lines.size ||
        prices.keys != order.selection.lines.map { it.portionId }.toSet() ||
        order.selection.lines.any { prices[it.portionId]?.quantity != it.quantity }) unavailable()
    val summary = orderRecord(order).let { it.copy(selectable = false, values = it.values + buildList {
        add(recordValue(text("Current owner", "المالك الحالي"), literal(order.ownerId.value)))
        original?.let { add(recordValue(text("Original creator", "المنشئ الأصلي"), literal(it.originalCreatorId.value))) }
        add(recordValue(text("Business revision", "مراجعة الطلب"), literal(order.revision.value.toString())))
        add(recordValue(text("Stock profile", "صيغة مخزون الطلب"), literal(selected.stockProfile.name)))
    }) }
    // Four portions per read-only card keep all 256 supported portions within the existing
    // page/record bounds, without truncation, continuation or owner-route navigation.
    val portions = order.selection.lines.chunked(4).map { group ->
        LocalRecord(LocalRecordReference(LocalRecordKind.ORDER_LINE, group.first().portionId),
            text("Recorded order portions", "أجزاء الطلب المسجلة"), values = group.flatMap { line -> buildList {
                add(recordValue(fieldLabel(LocalField.LINE_ID), literal(line.lineId.value)))
                add(recordValue(fieldLabel(LocalField.PORTION_ID), literal(line.portionId.value)))
                add(recordValue(fieldLabel(LocalField.SKU_ID), literal(line.itemId.value)))
                add(recordValue(fieldLabel(LocalField.QUANTITY), quantityText(line.quantity)))
                add(recordValue(text("Recorded payable", "المستحق المسجل"), moneyText(prices.getValue(line.portionId).payable)))
                line.variantId?.let { add(recordValue(fieldLabel(LocalField.VARIANT_ID), literal(it.value))) }
                if (line.modifierIds.isNotEmpty()) add(recordValue(fieldLabel(LocalField.MODIFIER_IDS),
                    literal(line.modifierIds.joinToString(", ") { it.value })))
            } }, selectable = false)
    }
    val storedComponents = quote?.components?.associateBy { it.key }
    val components = LocalManagerQuoteDetails(order.orderId, order.revision, order.components.map { component ->
        LocalManagerQuoteComponent(component.key, buildList {
            add(recordValue(text("Component key", "مفتاح المكوّن"), literal(component.key)))
            add(recordValue(text("Recorded component", "المكوّن المسجل"), when (component.kind) {
                "ITEM_NET" -> text("Item net", "صافي الصنف")
                "ITEM_TAX" -> text("Item tax", "ضريبة الصنف")
                "CHARGE_NET" -> text("Charge net", "صافي الرسم")
                "CHARGE_TAX" -> text("Charge tax", "ضريبة الرسم")
                else -> unavailable()
            }))
            component.portionId?.let { add(recordValue(fieldLabel(LocalField.PORTION_ID), literal(it.value))) }
            component.chargeId?.let { add(recordValue(text("Charge ID", "معرّف الرسم"), literal(it.value))) }
            add(recordValue(text("Recorded posted amount", "المبلغ المرحّل المسجل"), moneyText(component.amount)))
            add(recordValue(text("Recorded tax category", "فئة الضريبة المسجلة"), literal(component.taxCategory)))
            storedComponents?.get(component.key)?.let { stored ->
                stored.lineId?.let { add(recordValue(fieldLabel(LocalField.LINE_ID), literal(it.value))) }
                addAll(managerTaxValues(stored.tax))
                add(recordValue(text("Before-discount posting", "المبلغ المرحّل قبل الخصم"), moneyText(stored.postedBeforeDiscounts)))
                addAll(managerExactValues(text("Exact before-discount amount", "المبلغ الدقيق قبل الخصم"), stored.exactBeforeDiscounts))
                addAll(managerExactValues(text("Exact discounted amount", "المبلغ الدقيق بعد الخصم"), stored.exact))
                add(recordValue(text("Refund treatment", "معاملة الاسترداد"), managerRefundTreatment(stored.refundTreatment)))
            }
        })
    }, originalQuote = quote?.let(::managerOriginalQuoteDetails))
    return LocalWorkflowPage(LocalWorkflow.ORDERS, listOf(summary) + portions, emptyList(), text(
        "Read-only manager review, not transfer eligibility. Creator history, prior collectors/cash custody and unresolved-payment facts are not supplied; no order or money is reassigned. ${readCut(reply).english}",
        "مراجعة مدير للقراءة فقط وليست أهلية لنقل المسؤولية. لا تتضمن سجل المنشئ أو المحصلين السابقين أو عهدة النقد أو حقائق الدفع غير المحسوم؛ ولا تنقل الطلب أو المال. ${readCut(reply).arabic}"),
        managerQuoteDetails = components)
}
