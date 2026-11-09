package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.inventory.PurchaseChargeTreatmentV1
import com.ukkera.pos.inventory.PurchaseReceiptCostBasisV1

/** Editors select real source identities; preparation re-reads and pins their current revisions. */
internal fun purchaseApprovalForm(access: GatewayAccess): LocalActionForm? {
    if (!access.installed.has(LocalConfigurationProfile.QUERY)) return null
    return form(LocalAction.PURCHASE_APPROVE, listOf(
        newIdField(LocalField.PURCHASE_ID),
        pagedReferenceField(LocalField.SUPPLIER_ID, LocalRecordKind.SUPPLIER),
        scalar(LocalField.EXTERNAL_REFERENCE, LocalInputKind.TEXT, maximum = 96),
        scalar(LocalField.BUSINESS_DATE, LocalInputKind.DATE),
        scalar(LocalField.EXPECTED_DELIVERY, LocalInputKind.DATE, required = false),
        scalar(LocalField.PAYMENT_TERMS, LocalInputKind.TEXT, maximum = 256),
        instantField(LocalField.VALID_FROM), instantField(LocalField.VALID_UNTIL, required = false),
        scalar(LocalField.REASON, LocalInputKind.TEXT, maximum = 128),
        LocalFormField.Lines(LocalField.LINES, fieldLabel(LocalField.LINES), listOf(
            purchasingRowKind(),
            pagedReferenceField(LocalField.SKU_ID, LocalRecordKind.SKU, required = false),
            pagedReferenceField(LocalField.WAREHOUSE_ID, LocalRecordKind.WAREHOUSE, required = false),
            idField(LocalField.CONVERSION_ID, required = false).copy(help = text(
                "Goods only. Blank explicitly uses the selected SKU's base unit; otherwise enter its published purchase conversion ID.",
                "للبضاعة فقط. الفراغ يعني استخدام الوحدة الأساسية للصنف؛ وإلا أدخل معرف تحويل الشراء المنشور للصنف.")),
        ) + purchasingGoodsAmounts() + listOf(
            choiceField(LocalField.RECEIPT_COST_BASIS, listOf(LocalChoice(PurchaseReceiptCostBasisV1.NET_GOODS_ONLY.name,
                text("Net goods only — tax/charges are not capitalized here", "صافي البضاعة فقط — لا تُرسمل الضريبة أو المصروفات هنا")))).copy(required = false),
            choiceField(LocalField.INSPECTION_REQUIRED, listOf(LocalChoice("YES", text("Inspection required", "الفحص مطلوب")),
                LocalChoice("NO", text("No required inspection", "الفحص غير مطلوب")))).copy(required = false),
            scalar(LocalField.REQUESTED_DELIVERY, LocalInputKind.DATE, required = false),
        ) + purchasingChargeFields(), maximumLines = 24)), text(
        "Approve an EGP purchase and receiving allocation, not a receipt, invoice or payment. Select at most 16 GOODS rows and 8 CHARGE rows. " +
            "For GOODS, supply item/warehouse, quantity, actual terms/tax/amounts, cost basis and inspection choice; leave charge fields blank. " +
            "For CHARGE, supply only code/evidence/treatment/net/tax and leave goods fields blank. Zero discounts/taxes must be explicit. " +
            "Browse authorized source pages or look up an exact ID; staging rejects changed or inactive selections. Review the complete saved command before submission.",
        "اعتماد شراء بالجنيه ومخصص استلام، وليس استلامًا أو فاتورة أو دفعًا. اختر بحد أقصى 16 سطر بضاعة و8 سطور مصروفات. " +
            "للبضاعة أدخل الصنف والمخزن والكمية والشروط والضريبة والمبالغ الفعلية وأساس التكلفة واختيار الفحص؛ واترك حقول المصروفات فارغة. " +
            "للمصروف أدخل فقط الرمز والإثبات والمعالجة والصافي والضريبة واترك حقول البضاعة فارغة. يجب إدخال الخصم والضريبة الصفرية صراحةً. " +
            "تصفح صفحات المصادر المصرح بها أو ابحث بالمعرف الدقيق؛ يُرفض الاختيار المتغير أو غير النشط عند الحفظ. راجع الطلب المحفوظ الكامل قبل الإرسال."))
}

/** Version-2 choice identifies an immutable PO line; it never supplies its billed amounts. */
internal const val supplierInvoiceOrderedLinePrefix: String = "ORDER_LINE:"
internal const val supplierInvoiceDocumentaryOrderLinePrefix: String = "DOCUMENTARY_ORDER_LINE:"

/** Capture has its own authority and wire version; legacy invoice forms stay unchanged. */
internal fun supplierInvoiceCaptureForm(order: LocalPurchasingDocumentViewV1? = null): LocalActionForm {
    val kinds = order?.lines.orEmpty().flatMap { line -> listOf(
        LocalChoice("$supplierInvoiceOrderedLinePrefix${line.allocationId.value}",
            text("Use PO item/units ${line.allocationId.value} · ${line.skuId.value}",
                "استخدام صنف ووحدات الأمر ${line.allocationId.value} · ${line.skuId.value}")),
        LocalChoice("$supplierInvoiceDocumentaryOrderLinePrefix${line.allocationId.value}",
            text("Actual documented goods linked to PO line ${line.allocationId.value}",
                "بضاعة موثقة فعلية مرتبطة بسطر الأمر ${line.allocationId.value}"))) } + listOf(
        LocalChoice("DOCUMENTARY_GOODS", text("Actual documented goods — no allocation selected", "البضاعة الفعلية بالمستند — دون تخصيص مختار")),
        LocalChoice("CHARGE", text("Separate charge", "مصروف منفصل")))
    val supplier = if (order == null) pagedReferenceField(LocalField.SUPPLIER_ID, LocalRecordKind.SUPPLIER)
        else idField(LocalField.SUPPLIER_ID, order.supplierId)
    val revision = order?.let { revisionField(LocalField.EXPECTED_ORDER_REVISION, it.revision) }
        ?: scalar(LocalField.EXPECTED_ORDER_REVISION, LocalInputKind.INTEGER, required = false, maximum = 19)
    val documentary = supplierInvoiceDocumentaryFields().map { field ->
        if (field.field == LocalField.ALLOCATION_ID) scalar(LocalField.SUPPLIER_ALLOCATION_REFERENCE,
            LocalInputKind.TEXT, required = false, maximum = 96)
        else field
    }
    return form(LocalAction.SUPPLIER_INVOICE_CAPTURE, listOf(
        newIdField(LocalField.INVOICE_ID), supplier,
        idField(LocalField.PURCHASE_ID, order?.id, required = false), revision,
        scalar(LocalField.SUPPLIER_ORDER_REFERENCE, LocalInputKind.TEXT, required = false, maximum = 96),
        scalar(LocalField.EXTERNAL_REFERENCE, LocalInputKind.TEXT, maximum = 96),
        scalar(LocalField.BUSINESS_DATE, LocalInputKind.DATE), scalar(LocalField.DUE_DATE, LocalInputKind.DATE),
        idField(LocalField.EVIDENCE_ID), scalar(LocalField.REASON, LocalInputKind.TEXT, maximum = 128),
        LocalFormField.Lines(LocalField.LINES, fieldLabel(LocalField.LINES), listOf(choiceField(LocalField.MODE, kinds)) +
            documentary + purchasingGoodsAmounts() + purchasingChargeFields(), maximumLines = 24)), text(
        "Save the supplier's actual claim without receiving, approving or paying. A purchase order and allocation are optional. " +
            "Literal supplier references are preserved as text, never used as internal IDs. If you explicitly link an internal order, enter its current revision too. " +
            "For documented goods enter the actual item/unit/conversion and billed amounts; never substitute PO terms. Choose a documented-goods PO link to retain changed goods or units against that original context. Use PO item/units only when the actual goods use that snapshot; billed amounts are always explicit. " +
            "Use up to 16 goods and 8 charge rows. Leave wrong-mode fields blank. Captured claims remain unmatched and cannot be paid or matched by the legacy invoice workflow.",
        "احفظ مطالبة المورد الفعلية دون استلام أو اعتماد أو دفع. أمر الشراء والتخصيص اختياريان. " +
            "تُحفظ مراجع المورد كنص ولا تتحول إلى معرفات داخلية. عند ربط أمر داخلي صراحةً أدخل مراجعته الحالية أيضًا. " +
            "للبضاعة الموثقة أدخل الصنف والوحدات والتحويل والمبالغ الفعلية؛ لا تستبدلها بشروط الأمر. اختر ربط البضاعة الموثقة بسطر الأمر لحفظ الصنف أو الوحدات المتغيرة مع السياق الأصلي. استخدم صنف ووحدات الأمر فقط عند مطابقتها للبضاعة الفعلية؛ وأدخل المبالغ المفوترة صراحةً دائمًا. " +
            "استخدم حتى 16 سطر بضاعة و8 مصروفات واترك حقول الأنواع الأخرى فارغة. تبقى المطالبة غير مطابقة ولا يمكن دفعها أو مطابقتها بمسار الفاتورة القديم."))
}

internal fun supplierInvoiceForm(order: LocalPurchasingDocumentViewV1): LocalActionForm? {
    if (order.lines.isEmpty()) return null
    val kinds = order.lines.map { line -> LocalChoice(
        "$supplierInvoiceOrderedLinePrefix${line.allocationId.value}",
        text("PO line ${line.allocationId.value} · ${line.skuId.value} · ${line.units.purchaseUnitId.value}",
            "سطر الأمر ${line.allocationId.value} · ${line.skuId.value} · ${line.units.purchaseUnitId.value}")) } + listOf(
        LocalChoice("DOCUMENTARY_GOODS", text("Different or unmatched documented goods", "بضاعة موثقة مختلفة أو غير مطابقة")),
        LocalChoice("CHARGE", text("Separate charge", "مصروف منفصل")))
    return form(LocalAction.SUPPLIER_INVOICE_RECORD, listOf(
        newIdField(LocalField.INVOICE_ID), idField(LocalField.PURCHASE_ID, order.id),
        idField(LocalField.SUPPLIER_ID, order.supplierId), revisionField(LocalField.EXPECTED_ORDER_REVISION, order.revision),
        scalar(LocalField.EXTERNAL_REFERENCE, LocalInputKind.TEXT, maximum = 96),
        scalar(LocalField.BUSINESS_DATE, LocalInputKind.DATE), scalar(LocalField.DUE_DATE, LocalInputKind.DATE),
        idField(LocalField.EVIDENCE_ID), scalar(LocalField.REASON, LocalInputKind.TEXT, maximum = 128),
        LocalFormField.Lines(LocalField.LINES, fieldLabel(LocalField.LINES), listOf(
            choiceField(LocalField.MODE, kinds),
        ) + supplierInvoiceDocumentaryFields() + purchasingGoodsAmounts() + purchasingChargeFields().map { field ->
            if (field.field == LocalField.EVIDENCE_ID) field.copy(help = text(
                "For separate charges: the charge evidence. For documented goods: the original dimensional-conversion evidence, only when applicable. Leave blank for a selected PO line.",
                "للمصروف المنفصل: إثبات المصروف. للبضاعة الموثقة: إثبات تحويل الأبعاد الأصلي عند انطباقه فقط. اتركه فارغًا لسطر أمر مختار."))
            else field
        }, maximumLines = 24)), text(
        "Record the supplier's actual EGP claim, including discrepancies and separate charges; this does not receive goods or pay anyone. " +
            "Enter up to 16 goods and 8 charge rows. Select an original PO line only when its item/unit/conversion agrees with the supplier document. " +
            "Otherwise choose documented goods and enter the actual allocation reference, SKU/product, purchase/base units, base precision and exact conversion snapshot. " +
            "These are documentary claims, not catalog edits, approved substitutions or receiving allocations. Never invent a missing identity or replace it with a matching PO line. " +
            "Enter the invoice's actual price, discount, tax and net/tax amounts, never substitute the order's values to make it match. " +
            "A document without a representable allocation reference still requires documentary intake; this editor does not manufacture one. " +
            "Selected PO lines leave documentary-snapshot fields blank. Charge rows use only code/evidence/treatment/net/tax; all other row fields must be blank.",
        "سجّل مطالبة المورد الفعلية بالجنيه، بما فيها الفروق والمصروفات المنفصلة؛ دون استلام بضاعة أو دفع. " +
            "أدخل حتى 16 سطر بضاعة و8 مصروفات. اختر سطر الأمر الأصلي فقط إذا اتفق صنفه ووحدته وتحويله مع مستند المورد. " +
            "وإلا اختر البضاعة الموثقة وأدخل مرجع التخصيص الفعلي والصنف والمنتج ووحدتي الشراء والأساس ودقة الكمية والتحويل الأصلي الدقيق. " +
            "هذه مطالبات مستندية وليست تعديلات كتالوج أو بدائل معتمدة أو مخصصات استلام. لا تختلق معرفًا مفقودًا أو تستبدله بسطر أمر مطابق. " +
            "أدخل سعر الفاتورة وخصمها وضريبتها وصافيها ومبلغ الضريبة الفعلي؛ لا تستبدلها بقيم الأمر لإجبار المطابقة. " +
            "يبقى المستند الذي يفتقد مرجع تخصيص قابلًا للتمثيل في مسار استقبال المستندات؛ لا ينشئ المحرر مرجعًا مختلقًا. " +
            "اترك حقول البيانات المستندية فارغة عند اختيار سطر أمر. سطر المصروف يستخدم الرمز والإثبات والمعالجة والصافي والضريبة فقط؛ وتبقى بقية حقول السطر فارغة."), version = 2)
}

/** Explicit document facts only. Missing references/conversion components are never guessed. */
private fun supplierInvoiceDocumentaryFields(): List<LocalFormField.Scalar> = listOf(
    idField(LocalField.ALLOCATION_ID, required = false).copy(label = text(
        "Documented allocation reference — not an approval", "مرجع التخصيص بالمستند — ليس اعتمادًا")),
    idField(LocalField.SKU_ID, required = false), idField(LocalField.PRODUCT_ID, required = false),
    idField(LocalField.UNIT, required = false).copy(label = text("Documented purchase-unit ID", "معرف وحدة الشراء بالمستند")),
    idField(LocalField.PURCHASE_BASE_UNIT, required = false),
    scalar(LocalField.PURCHASE_BASE_SCALE, LocalInputKind.INTEGER, required = false, maximum = 1),
    idField(LocalField.CONVERSION_ID, required = false),
    scalar(LocalField.PURCHASE_CONVERSION_REVISION, LocalInputKind.INTEGER, required = false, maximum = 19),
    scalar(LocalField.PURCHASE_CONVERSION_RATE, LocalInputKind.TEXT, required = false, maximum = 39,
        help = text("Explicit reduced positive ratio. Use 1/1 for the same unit; no conversion is inferred.",
            "نسبة موجبة مختزلة صريحة. استخدم 1/1 للوحدة نفسها؛ لا يُفترض أي تحويل.")),
)

internal fun supplierInvoiceMatchForm(invoice: LocalPurchasingDocumentViewV1, uncontestedInstalled: Boolean = false): LocalActionForm? {
    val progress = invoice.progressRevision ?: return null
    val lines = invoice.lines.filter { it.matchedQuantity < it.baseQuantity }.map { line -> LocalReferenceChoice(
        LocalRecordReference(LocalRecordKind.PURCHASE_LINE, line.lineId, parentId = invoice.id),
        literal("${line.skuId.value} · ${line.lineId.value} · ${quantityText(line.baseQuantity - line.matchedQuantity).english}")) }
    if (lines.isEmpty()) return null
    val mode = if (uncontestedInstalled) listOf(choiceField(LocalField.MODE, listOf(
        LocalChoice("EXACT_TERMS", text("Compatible original terms", "الشروط الأصلية المتوافقة")),
        LocalChoice("UNCONTESTED_PRICE_INCREASE", text("Original PO amount only — higher price stays disputed", "مبلغ الأمر الأصلي فقط — يبقى السعر الأعلى محل نزاع")),
    ), initial = "EXACT_TERMS")) else emptyList()
    return form(LocalAction.SUPPLIER_INVOICE_MATCH, mode + listOf(
        newIdField(LocalField.MATCH_ID), idField(LocalField.INVOICE_ID, invoice.id),
        revisionField(LocalField.EXPECTED_STORAGE_REVISION, invoice.revision),
        revisionField(LocalField.EXPECTED_PROGRESS_REVISION, progress),
        scalar(LocalField.INVOICE_LINE_ID, LocalInputKind.REFERENCE).copy(references = lines),
        idField(LocalField.RECEIPT_ID), idField(LocalField.RECEIPT_LINE_ID),
        scalar(LocalField.QUANTITY, LocalInputKind.DECIMAL, maximum = 32, help = text(
            "Accepted billable quantity in the invoice line's base unit, not cartons or rejected/quarantined-unaccepted goods.",
            "الكمية المقبولة القابلة للفوترة بوحدة سطر الفاتورة الأساسية؛ وليست كراتين أو بضاعة مرفوضة أو محجوزة غير مقبولة.")),
        scalar(LocalField.REASON, LocalInputKind.TEXT, maximum = 128)), text(
        (if (uncontestedInstalled) "Choose compatible terms or explicitly recognize only the original PO amount of accepted goods with a higher supplier price. " +
            "The latter requires identical item/units/discount/tax and exact arithmetic; it preserves the higher claim as unapproved dispute. " +
            "The saved review shows claimed, recognized and disputed amounts; neither mode accepts the uplift or permits payment. " else "") +
        "Select the original receipt and line. Saving first reads the authorized current match source and pins all invoice, progress, order, receipt and optional billing/payable revisions. " +
            "A changed selection or incompatible terms requires review, not silent substitution. Matching neither pays the supplier nor allocates charges. " +
            "The saved operation is retried unchanged; it is never reselected from newer stock.",
        (if (uncontestedInstalled) "اختر الشروط المتوافقة أو اعترف صراحةً بمبلغ أمر الشراء الأصلي فقط للبضاعة المقبولة ذات سعر المورد الأعلى. " +
            "يتطلب الاختيار الثاني تطابق الصنف والوحدات والخصم والضريبة والحساب الدقيق؛ ويحفظ المطالبة الأعلى كفرق غير معتمد محل نزاع. " +
            "يعرض الطلب المحفوظ المبالغ المطالب بها والمعترف بها والمتنازع عليها؛ ولا يعتمد أي اختيار الزيادة أو يسمح بالدفع. " else "") +
        "اختر إذن الاستلام وسطره الأصليين. يقرأ الحفظ مصدر المطابقة الحالي المصرح به ويثبت مراجعات الفاتورة والتقدم والأمر والاستلام والفوترة والمستحقات الاختيارية. " +
            "يتطلب تغير الاختيار أو اختلاف الشروط مراجعةً، لا استبدالًا ضمنيًا. المطابقة لا تدفع للمورد ولا توزع المصروفات. " +
            "تُعاد محاولة العملية المحفوظة دون تغيير، ولا يُعاد اختيارها من مخزون أحدث."), version = if (uncontestedInstalled) 2 else 1)
}

private fun purchasingRowKind(): LocalFormField.Scalar = choiceField(LocalField.MODE, listOf(
    LocalChoice("GOODS", text("Goods", "بضاعة")), LocalChoice("CHARGE", text("Separate charge", "مصروف منفصل"))))

private fun purchasingGoodsAmounts(): List<LocalFormField.Scalar> = listOf(
    scalar(LocalField.QUANTITY, LocalInputKind.DECIMAL, required = false, maximum = 32),
    scalar(LocalField.QUANTITY_SCALE, LocalInputKind.INTEGER, required = false, maximum = 1, help = text(
        "Goods only. Explicit purchase-quantity decimal precision, 0 through 6; no rounding is performed.",
        "للبضاعة فقط. دقة كمية الشراء العشرية الصريحة من 0 إلى 6؛ لا يُجرى تقريب.")),
    scalar(LocalField.SUPPLIER_ITEM_REFERENCE, LocalInputKind.TEXT, required = false, maximum = 96),
    scalar(LocalField.UNIT_PRICE, LocalInputKind.DECIMAL, required = false, maximum = 32),
    scalar(LocalField.DISCOUNT_PERCENT, LocalInputKind.DECIMAL, required = false, maximum = 32),
    idField(LocalField.TAX_SOURCE_ID, required = false),
    scalar(LocalField.TAX_REVISION, LocalInputKind.INTEGER, required = false, maximum = 19),
    scalar(LocalField.TAX_CATEGORY, LocalInputKind.TEXT, required = false, maximum = 64),
    scalar(LocalField.TAX_RATE, LocalInputKind.DECIMAL, required = false, maximum = 32),
    moneyField(LocalField.NET_AMOUNT), moneyField(LocalField.TAX_AMOUNT),
)

private fun purchasingChargeFields(): List<LocalFormField.Scalar> = listOf(
    scalar(LocalField.CHARGE_CODE, LocalInputKind.TEXT, required = false, maximum = 64),
    idField(LocalField.EVIDENCE_ID, required = false),
    choiceField(LocalField.COST_TREATMENT, listOf(
        LocalChoice(PurchaseChargeTreatmentV1.ACQUISITION_COST_PENDING_ALLOCATION.name,
            text("Acquisition cost pending explicit allocation", "تكلفة اقتناء تنتظر توزيعًا صريحًا")),
        LocalChoice(PurchaseChargeTreatmentV1.NON_INVENTORY.name, text("Non-inventory charge", "مصروف غير مخزني")),
    )).copy(required = false),
)
