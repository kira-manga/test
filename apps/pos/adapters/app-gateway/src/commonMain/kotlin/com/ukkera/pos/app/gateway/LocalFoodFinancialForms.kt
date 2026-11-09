package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.inventory.store.LocalStockRelationalQueriesV2
import com.ukkera.pos.sales.FoodFulfillmentStatus
import com.ukkera.pos.sales.FoodPreparationStatus
import com.ukkera.pos.sales.FoodStockOrigin
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId

internal fun foodFinancialNotice(): LocalText = text(
    "These financial commands lock the original quote, reserve unsent ingredients and finalize actual stored coverage. Cash collection has a separate original native instruction. Cancellation records the supported original-tender obligation; it does not pay a refund.",
    "تقفل هذه الأوامر المالية التسعير الأصلي وتحجز المكونات غير المرسلة وتثبت التغطية المحفوظة الفعلية. للتحصيل النقدي تعليمات أصلية مستقلة. يسجل الإلغاء الالتزام المدعوم بوسيلة الدفع الأصلية ولا يدفع رد مبلغ.")

internal fun foodBeginCollectionForm(access: GatewayAccess, body: LocalFoodUiBody.Check): LocalActionForm? {
    val order = body.check
    if (!foodFinancialRouteAvailable(access) || order.service.channel == "DELIVERY" || order.portionCount == 0 ||
        order.workClosed || order.handoffId != null ||
        order.collectionId != null || body.financial != null || body.chargeResolutionRequired ||
        body.kitchenUnavailableForUnsentWork ||
        !access.allow(LocalAction.FOOD_BEGIN_COLLECTION, order.orderId)) return null
    return form(LocalAction.FOOD_BEGIN_COLLECTION, foodOrderFields(order.orderId, order.orderStorageRevision) + listOf(
        newIdField(LocalField.COLLECTION_ID), idField(LocalField.SESSION_ID),
        scalar(LocalField.EXPECTED_SESSION_REVISION, LocalInputKind.INTEGER, maximum = 19,
            help = text("Exact current domain revision of your open cash session; never a guessed zero.",
                "مراجعة المجال الحالية الدقيقة لجلستك النقدية المفتوحة؛ ليست صفرًا مفترضًا.")),
        moneyField(LocalField.AMOUNT, body.originalQuotedTotal).copy(readOnly = true)),
        if (access.installed.isFoodCashFinancialOnly) foodCashFinancialNotice() else foodFinancialNotice())
}

internal fun foodFinancialCanFinalize(value: LocalFoodCollectionViewV1): Boolean = !value.workClosed &&
    value.status == "COVERED" && value.saleId == null && value.cancellationRefundId == null &&
    value.unpaidRemaining.minor == 0L && value.collected == value.originalPayable &&
    value.portions.none { it.chargeDecisionRequired }

internal fun foodFinancialCanCancel(value: LocalFoodCollectionViewV1): Boolean = !value.workClosed &&
    value.status != "OUTCOME_UNKNOWN" && value.cancellationRefundId == null && value.portions.isNotEmpty() &&
    value.portions.all { it.preparationStatus == FoodPreparationStatus.NOT_SENT &&
        it.fulfillment == FoodFulfillmentStatus.NOT_HANDED_OVER && it.stockOrigin == FoodStockOrigin.RESERVED_FOR_COLLECTION &&
        it.reservationStatus == "RESERVED" }

internal fun foodFinancialCanClose(value: LocalFoodCollectionViewV1): Boolean = !value.workClosed &&
    value.status in setOf("FINALIZED_PAID", "CANCELLED_REFUND_OBLIGATION") && value.refundLiabilityDue.minor == 0L &&
    value.unresolvedClaimIds.isEmpty() && value.portions.isNotEmpty() && value.portions.all {
        !it.chargeDecisionRequired && if (value.cancellationRefundId != null)
            it.preparationStatus == FoodPreparationStatus.CANCELLED_BEFORE_START && it.fulfillment == FoodFulfillmentStatus.NOT_HANDED_OVER &&
                it.stockOrigin == FoodStockOrigin.RESERVED_FOR_COLLECTION && it.reservationStatus == "RELEASED"
        else value.saleId != null && it.fulfillment != FoodFulfillmentStatus.NOT_HANDED_OVER
    }

private fun foodFinancialRouteAvailable(access: GatewayAccess): Boolean = access.installed.has(LocalStockRelationalQueriesV2.PROFILE) &&
    access.installed.has(LocalFoodFinancialProfileV1.QUERY) &&
    (access.installed.has(LocalFoodFinancialProfileV1.COMMAND) || access.installed.has(LocalFoodStockCodecV2.COMMAND))

internal fun foodFinancialCanFinalize(access: GatewayAccess, value: LocalFoodCollectionViewV1): Boolean =
    foodFinancialCanFinalize(value) && access.hasHint(Permission(
        if (value.originalPayable.minor == 0L) SecurityAction.COLLECTION_FINALIZE_ZERO else SecurityAction.COLLECTION_FINALIZE,
        PermissionVerb.PERFORM), value.collectionId)

/** Construct the one permitted finalization form without constructing other financial actions. */
internal fun foodFinalizePaidForm(access: GatewayAccess, value: LocalFoodCollectionViewV1): LocalActionForm? {
    if (!foodFinancialRouteAvailable(access) || !foodFinancialCanFinalize(access, value)) return null
    return form(LocalAction.FOOD_FINALIZE_PAID, listOf(idField(LocalField.COLLECTION_ID, value.collectionId),
        revisionField(LocalField.EXPECTED_COLLECTION_REVISION, value.collectionDomainRevision),
        idField(LocalField.ORDER_ID, value.orderId),
        revisionField(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION, value.orderStorageRevision),
        newIdField(LocalField.SALE_ID)),
        if (access.installed.isFoodCashFinancialOnly) foodCashFinancialNotice() else foodFinancialNotice())
}

internal fun foodFinancialForms(access: GatewayAccess, value: LocalFoodCollectionViewV1): List<LocalActionForm> {
    if (!foodFinancialRouteAvailable(access) || value.workClosed) return emptyList()
    val base = listOf(idField(LocalField.COLLECTION_ID, value.collectionId),
        revisionField(LocalField.EXPECTED_COLLECTION_REVISION, value.collectionDomainRevision),
        idField(LocalField.ORDER_ID, value.orderId),
        revisionField(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION, value.orderStorageRevision))
    return buildList {
        foodFinalizePaidForm(access, value)?.let(::add)
        if (foodFinancialCanCancel(value) && access.allow(LocalAction.FOOD_CANCEL_WHOLE_UNSENT, value.collectionId))
            add(form(LocalAction.FOOD_CANCEL_WHOLE_UNSENT, base + listOf(newIdField(LocalField.RETURN_ID), idField(LocalField.REASON_ID)),
                text("Whole never-sent check only. Unknown payments block cancellation. Service charges or unsupported adjustments require separate resolution; the master may refuse. This does not pay a refund or cancel sent/served food.",
                    "للطلب الكامل الذي لم يُرسل فقط. تمنع المدفوعات المجهولة الإلغاء. رسوم الخدمة أو التعديلات غير المدعومة تتطلب حسمًا منفصلًا؛ قد يرفض الجهاز الرئيسي. لا يُدفع رد مبلغ ولا يُلغى طعام أُرسل أو قُدّم.")))
        if (foodFinancialCanClose(value) && access.allow(LocalAction.FOOD_CLOSE_RESOLVED, value.orderId))
            add(form(LocalAction.FOOD_CLOSE_RESOLVED, base, text(
                "Retire work only after the master verifies financial, refund, claim, preparation and charge obligations. This does not close a drawer or erase history.",
                "إنهاء مسؤولية العمل فقط بعد تحقق الجهاز الرئيسي من التزامات المال والرد والمطالبات والتحضير والرسوم. لا يُغلق درجًا ولا يمحو التاريخ.")))
        if (value.cancellationRefundId == null) {
            fun portions(action: LocalAction, selected: List<LocalFoodFinancialPortionWorkV1>, multiple: Boolean) {
                if (selected.isEmpty() || !access.allow(action, value.orderId)) return
                val choices = selected.map { LocalReferenceChoice(LocalRecordReference(LocalRecordKind.FOOD_PORTION,
                    it.portionId, it.preparationDomainRevision, value.orderId), literal(it.portionId.value)) }
                add(form(action, base + scalar(LocalField.PORTION_ID,
                    if (multiple) LocalInputKind.MULTI_REFERENCE else LocalInputKind.REFERENCE).copy(references = choices),
                    if (multiple) text("Select 1–8 never-sent reserved portions. Sending issues their existing reserved ingredients once; it is not payment or printer acknowledgement.",
                        "اختر من 1 إلى 8 حصص محجوزة لم تُرسل. الإرسال يصرف مكوناتها المحجوزة مرة واحدة؛ ليس دفعًا ولا تأكيدًا من الطابعة.")
                    else text("Explicit paid counter/takeaway pickup only, after finalized stored coverage. Ready is not customer receipt. Dine-in uses its separate Serve action; the master checks the actual channel.",
                        "استلام طلب مدفوع من المنضدة أو للسفري فقط بعد تثبيت التغطية المحفوظة. الجاهزية ليست استلام العميل. الأكل في المكان يستخدم إجراء التقديم المنفصل؛ يتحقق الجهاز الرئيسي من القناة الفعلية.")))
            }
            portions(LocalAction.FOOD_SEND_RESERVED, value.portions.filter {
                it.preparationStatus == FoodPreparationStatus.NOT_SENT && it.fulfillment == FoodFulfillmentStatus.NOT_HANDED_OVER &&
                    it.stockOrigin == FoodStockOrigin.RESERVED_FOR_COLLECTION && it.reservationStatus == "RESERVED"
            }, multiple = true)
            if (value.status == "FINALIZED_PAID") portions(LocalAction.FOOD_PAID_PICKUP, value.portions.filter {
                it.preparationStatus == FoodPreparationStatus.READY && it.fulfillment == FoodFulfillmentStatus.NOT_HANDED_OVER
            }, multiple = false)
        }
    }
}

internal fun foodQualityClaimForm(access: GatewayAccess): LocalActionForm? {
    val id = DomainId.random()
    if (!access.installed.has(LocalFoodFinancialProfileV1.COMMAND) || !access.allow(LocalAction.FOOD_RECORD_QUALITY_CLAIM, id)) return null
    return form(LocalAction.FOOD_RECORD_QUALITY_CLAIM, listOf(idField(LocalField.CASE_ID, id),
        choiceField(LocalField.MODE, listOf(LocalChoice("QUALITY", text("Quality complaint", "شكوى جودة")),
            LocalChoice("MERCHANT_FAULT", text("Merchant fault", "خطأ التاجر")), LocalChoice("OTHER", text("Other claim", "مطالبة أخرى"))))
            .copy(label = text("Claim category", "فئة المطالبة")),
        idField(LocalField.ORDER_ID, required = false), idField(LocalField.SALE_ID, required = false),
        idField(LocalField.PORTION_ID, required = false), idField(LocalField.UNIT, required = false),
        scalar(LocalField.QUANTITY_SCALE, LocalInputKind.INTEGER, required = false, maximum = 1),
        scalar(LocalField.QUANTITY, LocalInputKind.DECIMAL, required = false, maximum = 24),
        instantField(LocalField.ASSERTED_RECEIVED_AT, required = false), instantField(LocalField.CLAIMED_AT),
        scalar(LocalField.REASON, LocalInputKind.MULTILINE, maximum = 256), newIdField(LocalField.EVIDENCE_ID),
        instantField(LocalField.OCCURRED_AT)), text(
        "Record the customer's actual assertions, including unknown receipt/order/quantity. If quantity is supplied, unit and scale are required. This is unresolved intake, not verified delivery, eligibility, stock restoration, waiver or a refund. The original assertions are not overwritten.",
        "سجل إفادات العميل الفعلية بما فيها الإيصال أو الطلب أو الكمية غير المعروفة. إذا حُددت كمية، تلزم الوحدة والدقة. هذه مطالبة غير محسومة وليست إثبات تسليم أو استحقاق أو إعادة مخزون أو إعفاء أو رد مبلغ. لا تُستبدل الإفادات الأصلية."))
}
