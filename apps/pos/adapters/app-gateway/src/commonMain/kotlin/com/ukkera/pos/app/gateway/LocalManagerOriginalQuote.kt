package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.pricing.*
import com.ukkera.pos.types.ExactRational

/** Display immutable historical amounts; never calculate a replacement quote. */
internal fun managerExactValues(label: LocalText, amount: ExactRational, currency: String = ""): List<LocalRecordValue> {
    val ratio = amount.canonicalString()
    val display = ratio + currency
    return if (display.length <= 2048) listOf(recordValue(label, literal(display)))
    else listOf(
        recordValue(text("${label.english} — numerator", "${label.arabic} — البسط"), literal(ratio.substringBefore('/') + currency)),
        recordValue(text("${label.english} — denominator", "${label.arabic} — المقام"), literal(ratio.substringAfter('/'))),
    )
}

internal fun managerTaxValues(tax: TaxSnapshot): List<LocalRecordValue> = listOf(
    recordValue(text("Tax policy ID", "معرّف سياسة الضريبة"), literal(tax.policyId.value)),
    recordValue(text("Tax policy revision", "مراجعة سياسة الضريبة"), literal(tax.revision.value.toString())),
    recordValue(text("Tax category", "فئة الضريبة"), literal(tax.categoryCode)),
    recordValue(text("Tax percentage", "النسبة المئوية للضريبة"), literal(tax.rate.percent.canonicalString())),
)

internal fun managerRefundTreatment(treatment: ChargeRefundTreatment): LocalText = when (treatment) {
    ChargeRefundTreatment.PROPORTIONAL_WITH_ITEMS -> text("Proportional with items", "بالتناسب مع الأصناف")
    ChargeRefundTreatment.EXPLICIT_ONLY -> text("Separate authorized decision required", "يلزم قرار مصرح به منفصل")
    ChargeRefundTreatment.NEVER -> text("Not automatically refundable", "غير قابل للاسترداد تلقائيًا")
}

private fun inclusionText(inclusion: TaxInclusion): LocalText = when (inclusion) {
    TaxInclusion.INCLUSIVE -> text("Tax inclusive", "شامل الضريبة")
    TaxInclusion.EXCLUSIVE -> text("Tax exclusive", "غير شامل الضريبة")
}

private fun discountValue(value: DiscountValue): LocalRecordValue = when (value) {
    is DiscountValue.FixedPayable -> recordValue(text("Fixed payable reduction", "تخفيض ثابت للمستحق"), moneyText(value.amount))
    is DiscountValue.Percentage -> recordValue(text("Discount percentage", "النسبة المئوية للخصم"), literal(value.rate.percent.canonicalString()))
}

internal fun managerComponentKind(kind: PriceComponentKind): LocalText = when (kind) {
    PriceComponentKind.ITEM_NET -> text("Item net", "صافي الصنف")
    PriceComponentKind.ITEM_TAX -> text("Item tax", "ضريبة الصنف")
    PriceComponentKind.CHARGE_NET -> text("Charge net", "صافي الرسم")
    PriceComponentKind.CHARGE_TAX -> text("Charge tax", "ضريبة الرسم")
}

internal fun managerOriginalQuoteDetails(quote: PricingSnapshot): LocalManagerOriginalQuoteDetails {
    val currency = " ${quote.currency.name}"
    return LocalManagerOriginalQuoteDetails(listOf(
        recordValue(text("Original quote ID", "معرّف عرض السعر الأصلي"), literal(quote.snapshotId.value)),
        recordValue(text("Original quote revision", "مراجعة عرض السعر الأصلي"), literal(quote.revision.value.toString())),
        recordValue(text("Operational pricing profile", "نمط التسعير التشغيلي"), literal(quote.profile)),
        recordValue(text("Tax posting policy", "سياسة ترحيل الضريبة"), when (quote.taxPostingPolicy) {
            TaxPostingPolicy.OPERATIONAL_COMPONENT_ALLOCATION -> text("Operational component allocation", "توزيع تشغيلي على المكوّنات")
            TaxPostingPolicy.INDEPENDENT_LINE_HALF_UP -> text("Independent line half-up", "تقريب مستقل لكل سطر إلى الأعلى عند النصف")
            TaxPostingPolicy.COUPLED_FISCAL_ADAPTER -> text("Coupled fiscal adapter", "موائم ضريبي مترابط")
        }),
        recordValue(text("Promotion resolution", "حالة حسم العروض"), when (quote.promotionResolution) {
            PromotionResolution.NONE -> text("None", "لا يوجد")
            PromotionResolution.RESOLVED_CONCRETE_PORTIONS -> text("Resolved concrete portions", "أجزاء محددة محسومة")
            PromotionResolution.UNRESOLVED_CAMPAIGNS -> text("Unresolved campaigns", "حملات غير محسومة")
        }),
        recordValue(text("Recorded payable discount limit", "حد الخصم المسجل للمستحق"), moneyText(quote.discountLimits.maximumPayableReduction)),
        recordValue(text("Recorded percentage discount limit", "حد النسبة المئوية المسجل للخصم"), literal(quote.discountLimits.maximumPercentage.percent.canonicalString())),
    ) + managerExactValues(text("Exact original payable", "المستحق الأصلي الدقيق"), quote.exactBeforeDiscountPayable, currency) +
        managerExactValues(text("Exact discounted payable", "المستحق الدقيق بعد الخصم"), quote.exactPayable, currency), buildList {
        quote.lines.forEach { line ->
            add(LocalManagerQuoteSection("line:${line.portionId.value}", text("Original priced portion", "جزء مسعّر أصلي"), listOf(
                recordValue(fieldLabel(LocalField.LINE_ID), literal(line.lineId.value)),
                recordValue(fieldLabel(LocalField.PORTION_ID), literal(line.portionId.value)),
                recordValue(fieldLabel(LocalField.QUANTITY), quantityText(line.quantity)),
                recordValue(text("Original unit price", "سعر الوحدة الأصلي"), priceText(line.unitPrice)),
                recordValue(text("Price revision", "مراجعة السعر"), literal(line.priceRevision.value.toString())),
                recordValue(text("Tax inclusion", "شمول الضريبة"), inclusionText(line.inclusion)),
                recordValue(text("Recorded portion payable", "مستحق الجزء المسجل"), moneyText(line.payable)),
                recordValue(text("Portion payable discount limit", "حد خصم مستحق الجزء"), moneyText(line.discountLimits.maximumPayableReduction)),
                recordValue(text("Portion percentage discount limit", "حد نسبة خصم الجزء"), literal(line.discountLimits.maximumPercentage.percent.canonicalString())),
            ) + managerTaxValues(line.tax) +
                managerExactValues(text("Exact original item net", "صافي الصنف الأصلي الدقيق"), line.exactOriginalItemNet, currency) +
                managerExactValues(text("Exact discounted item net", "صافي الصنف الدقيق بعد الخصم"), line.exactItemNet, currency)))
        }
        quote.charges.forEach { charge ->
            add(LocalManagerQuoteSection("charge:${charge.chargeId.value}", text("Original charge definition", "تعريف الرسم الأصلي"), buildList {
                add(recordValue(text("Charge ID", "معرّف الرسم"), literal(charge.chargeId.value)))
                add(recordValue(text("Charge revision", "مراجعة الرسم"), literal(charge.revision.value.toString())))
                add(recordValue(text("Charge kind", "نوع الرسم"), when (charge.kind) {
                    ChargeKind.SERVICE -> text("Service", "خدمة")
                    ChargeKind.DELIVERY -> text("Delivery", "توصيل")
                    ChargeKind.PACKAGING -> text("Packaging", "تغليف")
                    ChargeKind.OTHER -> text("Other", "أخرى")
                }))
                add(recordValue(text("Tax inclusion", "شمول الضريبة"), inclusionText(charge.inclusion)))
                charge.fixedAmount?.let { add(recordValue(text("Original fixed amount", "المبلغ الثابت الأصلي"), priceText(it))) }
                charge.percentage?.let { add(recordValue(text("Original charge percentage", "نسبة الرسم الأصلية"), literal(it.percent.canonicalString()))) }
                add(recordValue(text("Refund treatment", "معاملة الاسترداد"), managerRefundTreatment(charge.refundTreatment)))
                addAll(managerTaxValues(charge.tax))
            }))
            charge.eligiblePortionIds.forEach { portion -> add(LocalManagerQuoteSection(
                "charge-portion:${charge.chargeId.value}:${portion.value}", text("Charge portion binding", "ربط الرسم بالجزء"), listOf(
                    recordValue(text("Charge ID", "معرّف الرسم"), literal(charge.chargeId.value)),
                    recordValue(fieldLabel(LocalField.PORTION_ID), literal(portion.value))))) }
        }
        quote.discounts.forEach { discount ->
            add(LocalManagerQuoteSection("discount:${discount.adjustmentId.value}", text("Original discount decision", "قرار الخصم الأصلي"), listOf(
                recordValue(text("Adjustment ID", "معرّف التعديل"), literal(discount.adjustmentId.value)),
                recordValue(text("Rule revision", "مراجعة القاعدة"), literal(discount.ruleRevision.value.toString())),
                recordValue(text("Discount stage", "مرحلة الخصم"), when (discount.stage) {
                    AdjustmentStage.RESOLVED_ITEM_PROMOTION -> text("Resolved item promotion", "عرض صنف محسوم")
                    AdjustmentStage.ORDER_OR_MANUAL_DISCOUNT -> text("Order or manual discount", "خصم طلب أو خصم يدوي")
                }),
                discountValue(discount.value),
                recordValue(text("Stacking rule", "قاعدة جمع الخصومات"), when (discount.stacking) {
                    StackingPermission.EXCLUSIVE -> text("Exclusive", "حصري")
                    StackingPermission.EXPLICITLY_ALLOWED -> text("Explicitly allowed", "مسموح به صراحةً")
                }),
                recordValue(text("Recorded payable limit", "حد المستحق المسجل"), moneyText(discount.limits.maximumPayableReduction)),
                recordValue(text("Recorded percentage limit", "حد النسبة المسجل"), literal(discount.limits.maximumPercentage.percent.canonicalString())),
                recordValue(text("Qualified attainable maximum", "أقصى تخفيض ممكن مثبت"), discount.maximumPayableReduction?.let(::moneyText)
                    ?: text("Unqualified; not a threshold", "غير مثبت؛ ليس حدًا للاستخدام")),
                recordValue(text("Capacity upper bound only", "حد سعة أعلى فقط"), moneyText(discount.capacityPayableReductionUpperBound)),
                recordValue(text("Posted payable reduction", "تخفيض المستحق المرحّل"), moneyText(discount.advertisedPayableReduction)),
                recordValue(text("Before adjustment payable", "المستحق قبل التعديل"), moneyText(discount.beforePayable)),
                recordValue(text("After adjustment payable", "المستحق بعد التعديل"), moneyText(discount.afterPayable)),
            ) + managerExactValues(text("Exact eligible payable", "المستحق المؤهل الدقيق"), discount.exactEligiblePayable, currency) +
                managerExactValues(text("Exact available reduction", "التخفيض المتاح الدقيق"), discount.exactAvailablePayableReduction, currency) +
                managerExactValues(text("Exact payable reduction", "التخفيض الدقيق للمستحق"), discount.exactPayableReduction, currency)))
            discount.eligiblePortions.forEach { portion -> add(LocalManagerQuoteSection(
                "discount-portion:${discount.adjustmentId.value}:${portion.portionId.value}", text("Discount portion binding", "ربط الخصم بالجزء"), listOf(
                    recordValue(text("Adjustment ID", "معرّف التعديل"), literal(discount.adjustmentId.value)),
                    recordValue(fieldLabel(LocalField.PORTION_ID), literal(portion.portionId.value)),
                    recordValue(text("Additional portion cap", "حد إضافي للجزء"), portion.maximumPayableReduction?.let(::moneyText)
                        ?: text("No additional cap supplied", "لم يُزوَّد حد إضافي"))))) }
            discount.allocations.forEach { allocation -> add(LocalManagerQuoteSection(
                "allocation:${discount.adjustmentId.value}:${allocation.key}", text("Original discount allocation", "توزيع الخصم الأصلي"), buildList {
                    add(recordValue(text("Adjustment ID", "معرّف التعديل"), literal(discount.adjustmentId.value)))
                    add(recordValue(text("Component key", "مفتاح المكوّن"), literal(allocation.key)))
                    add(recordValue(text("Component kind", "نوع المكوّن"), managerComponentKind(allocation.kind)))
                    allocation.portionId?.let { add(recordValue(fieldLabel(LocalField.PORTION_ID), literal(it.value))) }
                    allocation.chargeId?.let { add(recordValue(text("Charge ID", "معرّف الرسم"), literal(it.value))) }
                    addAll(managerExactValues(text("Exact before", "الدقيق قبل الخصم"), allocation.exactBefore, currency))
                    addAll(managerExactValues(text("Exact after", "الدقيق بعد الخصم"), allocation.exactAfter, currency))
                    addAll(managerExactValues(text("Exact reduction", "التخفيض الدقيق"), allocation.exactReduction, currency))
                    add(recordValue(text("Posted reduction", "التخفيض المرحّل"), moneyText(allocation.advertisedReduction)))
                    add(recordValue(text("Posting delta", "فرق الترحيل"), moneyText(allocation.postingDelta)))
                    addAll(managerExactValues(text("Rounding difference", "فرق التقريب"), allocation.roundingDifference, currency))
                })) }
        }
    })
}
