package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.inventory.StockSerialRegistrationStatus
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision

private const val PO_QUANTITY_MODE: String = "APPROVED_PO_QUANTITY_ONLY"

internal suspend fun receivingWorkflow(access: GatewayAccess, query: LocalWorkflowQuery): LocalWorkflowPage {
    if (isArrivalRouteV2(query)) return unpricedArrivalWorkflowV2(access, query)
    if (isArrivalRoute(query)) return unpricedArrivalWorkflow(access, query)
    val page = pricedReceivingWorkflow(access, query)
    // Real source/navigation entries alongside approved-PO and cost-authorized routes, not a
    // hidden zero-valued receipt. Selecting them obtains the dedicated cost-free endpoint read.
    return page.copy(records = arrivalNavigationV2(access, query.workflow) + arrivalNavigation(access, query.workflow) + page.records)
}

private suspend fun pricedReceivingWorkflow(access: GatewayAccess, query: LocalWorkflowQuery): LocalWorkflowPage {
    val reference = query.reference
    if (reference?.kind in setOf(LocalRecordKind.RECEIPT, LocalRecordKind.RECEIPT_LINE)) return receiptWorkflow(access, query, checkNotNull(reference))
    if (reference != null && reference.kind !in setOf(LocalRecordKind.RECEIVING_BASIS, LocalRecordKind.RECEIVING_ALLOCATION)) invalid()
    val basisId = reference?.let { if (it.kind == LocalRecordKind.RECEIVING_ALLOCATION) it.parentId ?: invalid(LocalField.BASIS_ID) else it.id }
    val sourceInstalled = access.installed.has(LocalPurchasingReceivingProfileV1.SOURCE)
    if (sourceInstalled) poReceivingWorkflow(access, query, basisId)?.let { return it }
    // Never fetch a cost-bearing basis as a fallback for a quantity-only clerk.
    // The server still enforces the actual read permission; this is only a conservative UI hint.
    if (access.hasHint(Permission(SecurityAction.INVENTORY_COST_READ, PermissionVerb.PERFORM), basisId))
        return valuedReceivingWorkflow(access, query)
    return LocalWorkflowPage(query.workflow, emptyList(), emptyList(),
        if (sourceInstalled) text(
            "No verified approved purchase order is available for this ID. Quantity-only owned receiving needs its retained approved PO; a generic allowance ceiling is not a price. Nothing was received. The separate unpriced-arrival route is shown on this page; its real evidence-policy/attachment gate may hold it. Genuine approved priced quick purchase and adoption remain mandatory next LOCAL work.",
            "لا يوجد أمر شراء معتمد متحقق منه لهذا المعرف. استلام المملوك بالكميات فقط يحتاج أمر الشراء المعتمد المحفوظ؛ حد السماح العام ليس سعرًا. لم يتم استلام شيء. يظهر مسار الوصول غير المسعر المنفصل هنا؛ وقد توقفه بوابة سياسة الإثبات والمرفقات الفعلية. يبقى الشراء السريع المسعر المعتمد والإدخال في المخزون عملًا محليًا تاليًا لازمًا.")
        else text(
            "The approved-PO quantity-only receive-source service is not installed. No prices were requested and nothing was received. Use the separate unpriced-arrival source route on this page; it remains held without actual configured evidence policy and durable attachments.",
            "خدمة مصدر الاستلام بالكميات فقط من أمر شراء معتمد غير مثبتة. لم تُطلب أسعار ولم يتم استلام شيء. استخدم مسار مصدر الوصول غير المسعر المنفصل في هذه الصفحة؛ ويبقى موقوفًا دون سياسة إثبات فعلية ومرفقات دائمة."))
}

private data class PoReceivingCut(val page: LocalPurchasingReceiveSourceV1, val reply: ReadReply.Data)

private suspend fun poReceivingRead(access: GatewayAccess, id: DomainId?, after: DomainId? = null): PoReceivingCut {
    val read = access.read(LocalPurchasingReceivingCodecV1.source(LocalPurchasingReceiveSourceQueryV1(id, after)))
    val page = LocalPurchasingReceivingCodecV1.decodeSource(read.payload)
    if (page.availability == LocalPurchasingReadAvailabilityV1.AVAILABLE) {
        val orders = page.orders ?: unavailable()
        if (orders.size > (if (id == null) 5 else 1) || orders.map { it.orderId }.distinct().size != orders.size ||
            id != null && page.nextAfter != null || orders.any { order ->
                id != null && order.orderId != id || order.orderStorageRevision.value < 1 || order.basisRevision != Revision(0) ||
                    order.allocations.size > 16 || order.allocations.map { it.allocationId }.distinct().size != order.allocations.size ||
                    id == null && order.allocations.isNotEmpty()
            }) unavailable()
    }
    return PoReceivingCut(page, read)
}

/** A null result means claim-checked absence of this PO, never unavailable hydration. */
private suspend fun poReceivingWorkflow(access: GatewayAccess, query: LocalWorkflowQuery, id: DomainId?): LocalWorkflowPage? {
    val cut = poReceivingRead(access, id, if (id == null) parseCursor(query.cursor, "receiving-po") else null)
    if (cut.page.availability != LocalPurchasingReadAvailabilityV1.AVAILABLE) return LocalWorkflowPage(query.workflow, emptyList(), emptyList(),
        text("The approved-PO receiving source cannot be fully hydrated at this read cut. Quantities and remaining allowance are unavailable, not zero; no receipt form or partial result is offered. ${readCut(cut.reply).english}",
            "لا يمكن تحميل مصدر استلام أمر الشراء المعتمد كاملًا في مراجعة القراءة هذه. الكميات والسماح المتبقي غير متاحين، وليسا صفرًا؛ لا يُعرض نموذج استلام أو نتيجة جزئية. ${readCut(cut.reply).arabic}"))
    val orders = checkNotNull(cut.page.orders)
    if (id == null) return LocalWorkflowPage(query.workflow, orders.filter { query.search.isBlank() || it.orderId.value.contains(query.search) }.map { order ->
        LocalRecord(LocalRecordReference(LocalRecordKind.RECEIVING_BASIS, order.orderId, order.basisRevision),
            text("Approved PO — quantity-only receiving", "أمر شراء معتمد — استلام بالكميات فقط"), values = listOf(
                recordValue(fieldLabel(LocalField.SUPPLIER_ID), literal(order.supplierId.value)),
                recordValue(fieldLabel(LocalField.EXPECTED_ORDER_REVISION), literal(order.orderStorageRevision.value.toString()))))
    }, emptyList(), text(
        "Select an actual approved PO, or use basis:PO-ID / receipt:ID. Quantities are loaded only for the selected PO. No costs, prices or invoice amounts are requested. Cost-authorized opening/manual allowances remain available by exact basis:ID. The separate unpriced-arrival route remains custody-only and is held without configured evidence authority; genuine priced quick purchase/adoption is still required next locally. ${readCut(cut.reply).english}",
        "اختر أمر شراء معتمدًا فعليًا، أو استخدم basis:PO-ID / receipt:ID. تُحمّل الكميات للأمر المحدد فقط. لا تُطلب تكاليف أو أسعار أو مبالغ فواتير. تبقى السماحات اليدوية والافتتاحية لمخول التكلفة متاحة عبر basis:ID المحدد. مسار الوصول غير المسعر المنفصل للعهدة فقط وموقوف دون صلاحية إثبات مهيأة؛ ويبقى الشراء السريع المسعر والإدخال في المخزون مطلوبين تاليًا محليًا. ${readCut(cut.reply).arabic}"),
        cursor("receiving-po", cut.page.nextAfter))
    val order = orders.singleOrNull() ?: return null
    val reference = query.reference
    val selected = if (reference?.kind == LocalRecordKind.RECEIVING_ALLOCATION)
        listOf(order.allocations.singleOrNull { it.allocationId == reference.id } ?: invalid(LocalField.ALLOCATION_ID)) else order.allocations
    val records = listOf(LocalRecord(LocalRecordReference(LocalRecordKind.RECEIVING_BASIS, order.orderId, order.basisRevision),
        text("Approved PO receiving source", "مصدر استلام أمر شراء معتمد"), values = buildList {
            add(recordValue(fieldLabel(LocalField.SUPPLIER_ID), literal(order.supplierId.value)))
            add(recordValue(fieldLabel(LocalField.EXPECTED_ORDER_REVISION), literal(order.orderStorageRevision.value.toString())))
            add(recordValue(fieldLabel(LocalField.EXPECTED_BASIS_REVISION), literal(order.basisRevision.value.toString())))
            add(recordValue(fieldLabel(LocalField.VALID_FROM), literal(order.validFrom.canonicalString())))
            order.validUntil?.let { add(recordValue(fieldLabel(LocalField.VALID_UNTIL), literal(it.canonicalString()))) }
        })) + selected.map { line -> LocalRecord(LocalRecordReference(LocalRecordKind.RECEIVING_ALLOCATION, line.allocationId, parentId = order.orderId),
        text("Approved physical allocation", "تخصيص فعلي معتمد"), values = buildList {
            add(recordValue(fieldLabel(LocalField.SKU_ID), literal(line.skuId.value)))
            add(recordValue(fieldLabel(LocalField.WAREHOUSE_ID), literal(line.warehouseId.value)))
            add(recordValue(text("Original PO purchase quantity", "كمية الشراء الأصلية بأمر الشراء"), quantityText(line.orderedPurchaseQuantity)))
            add(recordValue(text("Ordered base quantity", "الكمية الأساسية المطلوبة"), quantityText(line.orderedBaseQuantity)))
            add(recordValue(text("Committed quantity (includes quarantine)", "الكمية المستلمة على السماح (تشمل المحجوز للفحص)"), quantityText(line.committedQuantity)))
            add(recordValue(fieldLabel(LocalField.ACCEPTED_QUANTITY), quantityText(line.acceptedQuantity)))
            add(recordValue(fieldLabel(LocalField.QUARANTINED_QUANTITY), quantityText(line.pendingInspectionQuantity)))
            add(recordValue(text("Remaining owned-receipt quantity", "الكمية المتبقية لاستلام المملوك"), quantityText(line.remainingQuantity)))
            add(recordValue(text("Allowance storage selection", "اختيار مراجعة تخزين السماح"), line.allowanceStorageRevision?.let { literal(it.value.toString()) }
                ?: text("0 — verified absent", "0 — غياب متحقق منه")))
            add(recordValue(fieldLabel(LocalField.EXPECTED_ITEM_STORAGE_REVISION), line.itemStorageRevision?.let { literal(it.value.toString()) }
                ?: text("Verified absent; NEW requires your explicit selection", "غياب متحقق منه؛ يتطلب جديد اختيارك الصريح")))
            add(recordValue(fieldLabel(LocalField.INSPECTION_REQUIRED), if (line.inspectionRequired) text("Yes", "نعم") else text("No", "لا")))
            add(recordValue(fieldLabel(LocalField.TRACKING), line.tracking?.let { literal(it.name) } ?: text("Unavailable", "غير متاح")))
            add(recordValue(text("Expiry required", "تاريخ الصلاحية مطلوب"), when (line.requiresExpiry) {
                true -> text("Yes", "نعم"); false -> text("No", "لا"); null -> text("Unavailable", "غير متاح")
            }))
            add(recordValue(text("Current item / warehouse eligible", "أهلية الصنف والمخزن الحاليين"),
                if (line.currentItemMatches && line.currentWarehouseActive) text("Yes", "نعم") else text("No", "لا")))
            add(recordValue(text("Approved owned-receipt lineage compatible", "توافق تسلسل استلام المملوك المعتمد"),
                if (line.valuationLineageReady) text("Yes", "نعم") else text("No; do not adjust earlier receipts", "لا؛ لا تعدل الاستلامات السابقة")))
        }) }
    val eligible = selected.filter { it.currentItemMatches && it.currentWarehouseActive && it.skuRevision != null &&
        it.tracking != null && it.tracking != LocalTrackingV1.NONE && it.requiresExpiry != null }
    val inWindow = cut.reply.observedAt >= order.validFrom && (order.validUntil?.let { cut.reply.observedAt < it } != false)
    val form = if (inWindow && eligible.isNotEmpty() && LocalAction.RECEIPT_POST.canAppearIn(query.workflow) &&
        access.installed.has(LocalStockStoreProfile.RECEIVE_PO) && access.allow(LocalAction.RECEIPT_POST, order.orderId)) poReceivingForm(order, eligible) else null
    return LocalWorkflowPage(query.workflow, records, listOfNotNull(form), text(
        "Only current eligible allocations enter the form; all selected allocations remain visible. Enter actual BASE quantities and physical dispositions, not purchase prices or fractional cartons. The master derives original net-goods value from the verified retained PO and exact prior allowance; no invoice/payment or second quantity is created. No form means its current source, command, activity or permission requirements are unmet. ${readCut(cut.reply).english}",
        "يدخل النموذج التخصيصات المؤهلة حاليًا فقط؛ وتبقى كل التخصيصات المحددة ظاهرة. أدخل الكميات الأساسية الفعلية وحالات التصرف، لا أسعار الشراء أو كسور كراتين وهمية. يستخرج الجهاز الرئيسي صافي قيمة البضاعة من أمر الشراء المحفوظ المتحقق منه والسماح السابق الدقيق؛ لا تنشأ فاتورة أو دفعة أو كمية ثانية. غياب النموذج يعني عدم استيفاء متطلبات المصدر أو الأمر أو النشاط أو الصلاحية حاليًا. ${readCut(cut.reply).arabic}"))
}

private fun poReceivingForm(order: LocalPurchasingReceiveOrderV1, allocations: List<LocalPurchasingReceiveAllocationV1>): LocalActionForm {
    val choices = allocations.distinctBy { it.skuId }.map { line -> LocalReferenceChoice(
        LocalRecordReference(LocalRecordKind.SKU, line.skuId, checkNotNull(line.skuRevision)), literal(line.skuId.value)) }
    val initial = allocations.map { line -> LocalInputLine(DomainId.random(), buildList {
        add(LocalScalarInput(LocalField.ALLOCATION_ID, LocalScalarValue.Identifier(line.allocationId)))
        add(LocalScalarInput(LocalField.SKU_ID, LocalScalarValue.Reference(choices.single { it.reference.id == line.skuId }.reference)))
        add(LocalScalarInput(LocalField.WAREHOUSE_ID, LocalScalarValue.Identifier(line.warehouseId)))
        add(LocalScalarInput(LocalField.UNIT, LocalScalarValue.Identifier(line.orderedBaseQuantity.unitId)))
        add(LocalScalarInput(LocalField.QUANTITY_SCALE, LocalScalarValue.Integer(line.orderedBaseQuantity.scale.toLong())))
        add(LocalScalarInput(LocalField.TRACKING, LocalScalarValue.Choice(checkNotNull(line.tracking).name)))
        add(LocalScalarInput(LocalField.INSPECTION_REQUIRED, LocalScalarValue.Flag(line.inspectionRequired)))
        add(LocalScalarInput(LocalField.EXPECTED_SOURCE_REVISION, LocalScalarValue.Integer(line.allowanceStorageRevision?.value ?: 0L)))
        add(LocalScalarInput(LocalField.NEW_ITEM, LocalScalarValue.Flag(false)))
        line.itemStorageRevision?.let { add(LocalScalarInput(LocalField.EXPECTED_ITEM_STORAGE_REVISION, LocalScalarValue.Integer(it.value))) }
        if (line.tracking == LocalTrackingV1.SERIAL) {
            add(LocalScalarInput(LocalField.SERIAL_ID, LocalScalarValue.Identifier(DomainId.random())))
            add(LocalScalarInput(LocalField.QUANTITY, LocalScalarValue.Decimal("1")))
        }
    }) }
    val columns = listOf(
        idField(LocalField.ALLOCATION_ID, readOnly = true), scalar(LocalField.SKU_ID, LocalInputKind.REFERENCE, readOnly = true).copy(references = choices),
        idField(LocalField.WAREHOUSE_ID, readOnly = true), idField(LocalField.UNIT, readOnly = true),
        scalar(LocalField.QUANTITY_SCALE, LocalInputKind.INTEGER, readOnly = true, maximum = 1),
        choiceField(LocalField.TRACKING, LocalTrackingV1.entries.map { LocalChoice(it.name, literal(it.name)) }).copy(readOnly = true),
        scalar(LocalField.INSPECTION_REQUIRED, LocalInputKind.CHECKBOX, required = false, readOnly = true),
        scalar(LocalField.EXPECTED_SOURCE_REVISION, LocalInputKind.INTEGER, readOnly = true, maximum = 19,
            help = text("Exact allowance storage selection. 0 means verified absence, not an unavailable quantity read.", "اختيار دقيق لمراجعة تخزين السماح. 0 يعني غيابًا متحققًا منه، لا قراءة كميات غير متاحة.")),
        scalar(LocalField.NEW_ITEM, LocalInputKind.CHECKBOX, LocalScalarValue.Flag(false), required = false,
            help = text("Select NEW yourself only for verified absent item storage. Never inferred or refreshed for you.", "اختر جديد بنفسك فقط عند غياب تخزين الصنف المتحقق منه. لا يُستنتج أو يُحدّث نيابةً عنك.")),
        scalar(LocalField.EXPECTED_ITEM_STORAGE_REVISION, LocalInputKind.INTEGER, required = false, readOnly = true, maximum = 19),
        idField(LocalField.SERIAL_ID, required = false, readOnly = false).copy(help = text(
            "Serial only: generated local pending identity is NOT registration. Use an existing identity only from actual matching registration.",
            "للمسلسل فقط: المعرف المحلي المولد المعلق ليس تسجيلًا. استخدم معرفًا قائمًا فقط من تسجيل فعلي مطابق.")),
        scalar(LocalField.SERIAL_CODE, LocalInputKind.TEXT, required = false, maximum = 128, help = text(
            "Serial only: exact physical code; no trimming or case/digit conversion. Pending serials are not sellable.",
            "للمسلسل فقط: الرمز الفعلي الدقيق؛ دون حذف مسافات أو تغيير حالة الأحرف أو الأرقام. المسلسلات المعلقة غير قابلة للبيع.")),
        exactQuantityField(LocalField.QUANTITY), idField(LocalField.LOT_ID, required = false, readOnly = false), instantField(LocalField.EXPIRY_AT, required = false),
        choiceField(LocalField.DISPOSITION, LocalReceiptDisposition.entries.map { LocalChoice(it.name, receiptDisposition(it)) }))
    return form(LocalAction.RECEIPT_POST, listOf(
        choiceField(LocalField.MODE, listOf(LocalChoice(PO_QUANTITY_MODE, text("Approved PO — quantities only", "أمر شراء معتمد — كميات فقط"))), PO_QUANTITY_MODE).copy(readOnly = true),
        newIdField(LocalField.RECEIPT_ID), idField(LocalField.BASIS_ID, order.orderId),
        revisionField(LocalField.EXPECTED_ORDER_REVISION, order.orderStorageRevision), revisionField(LocalField.EXPECTED_BASIS_REVISION, order.basisRevision), stockReasonField(),
        LocalFormField.Lines(LocalField.LINES, fieldLabel(LocalField.LINES), columns, maximumLines = 16, initialLines = initial,
            allowBlankLines = false, allowDuplicateLines = true)), text(
        "Review each physical slice and remove unreceived rows. Splitting keeps allocation metadata but creates a NEW line/source identity; a serial split also replaces the local serial ID and clears its code. Line order is preserved. Each serial is one whole base unit. Enter required lot/expiry and actual disposition; inspection-required goods cannot be directly released. NEW must be explicitly selected for verified absent item storage. No monetary input is accepted. Original approved PO net value is derived only by the master. Save, submit and look up the SAME immutable operation; do not recreate it after an uncertain response.",
        "راجع كل جزء فعلي واحذف الأسطر غير المستلمة. يحتفظ التقسيم ببيانات التخصيص ويولد معرف سطر ومصدر جديدًا؛ وللمسلسل يستبدل معرفه المحلي ويمسح رمزه أيضًا. يُحفظ ترتيب الأسطر. كل مسلسل وحدة أساسية كاملة. أدخل التشغيلة والصلاحية المطلوبتين وحالة التصرف الفعلية؛ لا تُتاح مباشرةً بضاعة تتطلب الفحص. اختر جديد صراحةً عند غياب تخزين الصنف المتحقق منه. لا يُقبل إدخال نقدي. الجهاز الرئيسي وحده يستخرج صافي القيمة الأصلية المعتمدة. احفظ وأرسل واستعلم عن العملية الثابتة نفسها؛ لا تنشئها مجددًا بعد استجابة غير مؤكدة.")).copy(
        confirmation = text("I reviewed actual quantities, dispositions and exact source revisions. Save this value-free immutable intent for separate submission.",
            "راجعت الكميات الفعلية وحالات التصرف ومراجعات المصدر الدقيقة. احفظ هذا الطلب الثابت الخالي من القيم النقدية لإرساله منفصلًا."))
}

/** Explicit legacy cost-authorized route. Its caller-supplied valuation checks are unchanged. */
private suspend fun valuedReceivingWorkflow(access: GatewayAccess, query: LocalWorkflowQuery): LocalWorkflowPage {
    val reference = query.reference
    if (reference?.kind in setOf(LocalRecordKind.RECEIPT, LocalRecordKind.RECEIPT_LINE)) return receiptWorkflow(access, query, checkNotNull(reference))
    if (reference != null && reference.kind !in setOf(LocalRecordKind.RECEIVING_BASIS, LocalRecordKind.RECEIVING_ALLOCATION)) invalid()
    val basisId = reference?.let { if (it.kind == LocalRecordKind.RECEIVING_ALLOCATION) it.parentId ?: invalid(LocalField.BASIS_ID) else it.id }
    val cut = configurationRead(access, LocalConfigurationQueryV1(LocalConfigurationProfile.BASIS, id = basisId,
        after = if (basisId == null) parseCursor(query.cursor, "receiving-bases") else null, limit = 25))
    if (basisId == null) return LocalWorkflowPage(query.workflow, cut.page.items.filter { query.search.isBlank() || it.id.value.contains(query.search) }.map {
        LocalRecord(LocalRecordReference(LocalRecordKind.RECEIVING_BASIS, it.id, it.revision), text("Approved receiving allowance", "سماح استلام معتمد"), it.code?.let(::literal))
    }, emptyList(), text("Select an actual approved allowance, or use receipt:ID or basis:ID. This is not a purchase invoice or supplier settlement. ${readCut(cut.reply).english}",
        "اختر سماحًا معتمدًا فعليًا، أو استخدم receipt:ID أو basis:ID. هذا ليس فاتورة شراء أو تسوية مورد. ${readCut(cut.reply).arabic}"),
        cursor("receiving-bases", cut.page.nextAfter))
    val approval = (cut.page.detail as? LocalConfigurationDetailV1.ReceivingBasis)?.value
        ?: return LocalWorkflowPage(query.workflow, emptyList(), emptyList(), text("No receiving allowance is available for that ID.", "لا يوجد سماح استلام متاح لهذا المعرف."))
    val row = cut.page.items.singleOrNull() ?: unavailable()
    if (approval.id != basisId || row.id != basisId) unavailable()
    val offset = if (reference?.kind == LocalRecordKind.RECEIVING_ALLOCATION) 0 else offsetCursor(query.cursor, "basis-lines", approval.lines.size)
    val lines = if (reference?.kind == LocalRecordKind.RECEIVING_ALLOCATION)
        listOf(approval.lines.singleOrNull { it.allocationId == reference.id } ?: invalid(LocalField.ALLOCATION_ID))
        else approval.lines.drop(offset).take(16)
    val records = listOf(LocalRecord(LocalRecordReference(LocalRecordKind.RECEIVING_BASIS, basisId, row.revision), text("Approved receiving basis", "أساس استلام معتمد"),
        literal(approval.kind.name), buildList {
            approval.supplierId?.let { add(recordValue(fieldLabel(LocalField.SUPPLIER_ID), literal(it.value))) }
            add(recordValue(fieldLabel(LocalField.VALID_FROM), literal(approval.validFrom.canonicalString())))
            approval.validUntil?.let { add(recordValue(fieldLabel(LocalField.VALID_UNTIL), literal(it.canonicalString()))) }
            add(recordValue(text("Total approved allocations", "إجمالي التخصيصات المعتمدة"), literal(approval.lines.size.toString())))
        })) + lines.map { line -> LocalRecord(LocalRecordReference(LocalRecordKind.RECEIVING_ALLOCATION, line.allocationId, parentId = basisId),
            text("Approved allocation", "تخصيص معتمد"), values = listOf(
                recordValue(fieldLabel(LocalField.SKU_ID), literal(line.skuId.value)), recordValue(fieldLabel(LocalField.WAREHOUSE_ID), literal(line.warehouseId.value)),
                recordValue(fieldLabel(LocalField.MAXIMUM_QUANTITY), quantityText(line.maximumBaseQuantity)),
                recordValue(fieldLabel(LocalField.MAXIMUM_OWNED_VALUE), moneyText(line.maximumOwnedValue)),
                recordValue(fieldLabel(LocalField.INSPECTION_REQUIRED), if (line.inspectionRequired) text("Yes", "نعم") else text("No", "لا")))) }
    val action = if (approval.kind == LocalBasisKindV1.OPENING) LocalAction.STOCK_OPENING else LocalAction.RECEIPT_POST
    // First-opening slices for one SKU must be reviewed together, never taken from this
    // allowance's partial page or a single allocation. The dedicated Opening view groups them.
    val form = if (action != LocalAction.STOCK_OPENING && lines.isNotEmpty() && action.canAppearIn(query.workflow) && access.allow(action, basisId))
        receivingForm(access, approval, row.revision, lines, action) else null
    val openingNotice = if (approval.kind == LocalBasisKindV1.OPENING) text(
        "This opening approval has NOT posted stock. Use Inventory → Opening and select its SKU to review every counted slice together; this partial allowance page cannot post first opening. ",
        "لم يرحل هذا الاعتماد الافتتاحي مخزونًا. استخدم المخزون ← الافتتاحي واختر صنفه لمراجعة كل جزء معدود معًا؛ لا تستطيع صفحة السماح الجزئية هذه ترحيل الافتتاحي الأول. ") else literal("")
    return LocalWorkflowPage(query.workflow, records, listOfNotNull(form),
        text("${openingNotice.english}Showing ${lines.size} of ${approval.lines.size} approved allocations. A proposal includes only explicitly reviewed slices, not the whole basis. Ceilings are NOT remaining allowance; the master checks all prior use. Base units only; each serialized row is one identified whole unit, not a lot alias. ${readCut(cut.reply).english}",
            "${openingNotice.arabic}تُعرض ${lines.size} من ${approval.lines.size} تخصيصات معتمدة. يشمل المقترح الأجزاء المراجعة صراحةً فقط، لا الأساس كله. الحدود ليست الرصيد المتبقي؛ يتحقق الجهاز الرئيسي من الاستخدام السابق. وحدات أساسية فقط؛ كل سطر مسلسل وحدة كاملة محددة، لا اسم بديل للتشغيلة. ${readCut(cut.reply).arabic}"),
        if (reference?.kind != LocalRecordKind.RECEIVING_ALLOCATION && offset + lines.size < approval.lines.size) "basis-lines:${offset + lines.size}" else null)
}

internal suspend fun receivingForm(access: GatewayAccess, approval: LocalReceivingApprovalV1, revision: Revision,
                                  allocations: List<LocalApprovedStockLineV1>, action: LocalAction): LocalActionForm? {
    if (allocations.size !in 1..16) return null // Existing production receipt bound; opening review pages are separate.
    val skuChoices = mutableListOf<LocalReferenceChoice>()
    val details = mutableMapOf<DomainId, LocalConfigurationDetailV1.Sku>()
    val versions = mutableMapOf<DomainId, Revision?>()
    for (id in allocations.map { it.skuId }.distinct()) {
        val detail = skuDetail(access, id).first
        if (!detail.active || detail.tracking == LocalTrackingV1.NONE ||
            action != LocalAction.STOCK_OPENING && !detail.canPurchase) return null
        details[id] = detail
        versions[id] = stockQuantities(access, id, limit = 1).page.itemStorageRevision
        skuChoices += LocalReferenceChoice(LocalRecordReference(LocalRecordKind.SKU, id, detail.skuRevision), names(detail.names, id))
    }
    if (action == LocalAction.STOCK_OPENING && versions.values.any { it != null }) return null
    val serialized = details.values.any { it.tracking == LocalTrackingV1.SERIAL }
    val normalizedOpening = action == LocalAction.STOCK_OPENING && access.installed.has(LocalStockRelationalQueriesV2.PROFILE) &&
        LocalStockRelationalQueriesV2.normalized(access.read(LocalStockRelationalQueriesV2.profile()).payload)
    val command = when {
        normalizedOpening -> LocalStockRelationalProfileV2.COMMAND
        action == LocalAction.STOCK_OPENING && serialized -> LocalStockStoreProfile.OPENING_SERIAL
        action == LocalAction.STOCK_OPENING -> LocalStockStoreProfile.OPENING
        serialized -> LocalStockStoreProfile.RECEIVE_SERIAL
        else -> LocalStockStoreProfile.RECEIVE
    }
    if (!access.installed.has(command)) return null
    // A fresh draft may select an intended prospective target. Visibility is not a grant;
    // preparation checks the chosen receipt before staging, and the real writer checks again.
    val receiptField = newIdField(LocalField.RECEIPT_ID).let { field ->
        if (action == LocalAction.STOCK_OPENING) field.copy(readOnly = false, help = text(
            "Suggested new receipt ID, not a reservation or permission. Enter the exact intended ID before saving. Permission is checked for that chosen receipt; after saving, retain the same original identity.",
            "معرف إيصال جديد مقترح، وليس حجزًا أو إذنًا. أدخل المعرف المقصود الدقيق قبل الحفظ. تُفحص الصلاحية لهذا الإيصال المختار؛ وبعد الحفظ احتفظ بنفس الهوية الأصلية."))
        else field
    }
    val initial = allocations.map { allocation ->
        val detail = details.getValue(allocation.skuId)
        if (allocation.maximumBaseQuantity.unitId != detail.baseUnitId || allocation.maximumBaseQuantity.scale != detail.quantityScale) unavailable()
        LocalInputLine(DomainId.random(), buildList {
            add(LocalScalarInput(LocalField.ALLOCATION_ID, LocalScalarValue.Identifier(allocation.allocationId)))
            add(LocalScalarInput(LocalField.SKU_ID, LocalScalarValue.Reference(skuChoices.single { it.reference.id == allocation.skuId }.reference)))
            add(LocalScalarInput(LocalField.WAREHOUSE_ID, LocalScalarValue.Identifier(allocation.warehouseId)))
            add(LocalScalarInput(LocalField.UNIT, LocalScalarValue.Identifier(detail.baseUnitId)))
            add(LocalScalarInput(LocalField.QUANTITY_SCALE, LocalScalarValue.Integer(detail.quantityScale.toLong())))
            add(LocalScalarInput(LocalField.TRACKING, LocalScalarValue.Choice(detail.tracking.name)))
            if (detail.tracking == LocalTrackingV1.SERIAL) {
                // A fresh local physical identity, not company registration authority.
                add(LocalScalarInput(LocalField.SERIAL_ID, LocalScalarValue.Identifier(DomainId.random())))
                add(LocalScalarInput(LocalField.QUANTITY, LocalScalarValue.Decimal("1")))
            }
            add(LocalScalarInput(LocalField.INSPECTION_REQUIRED, LocalScalarValue.Flag(allocation.inspectionRequired)))
            add(LocalScalarInput(LocalField.NEW_ITEM, LocalScalarValue.Flag(false)))
            versions[allocation.skuId]?.let { add(LocalScalarInput(LocalField.EXPECTED_ITEM_STORAGE_REVISION, LocalScalarValue.Integer(it.value))) }
        })
    }
    val columns = listOf(
        idField(LocalField.ALLOCATION_ID, readOnly = true), scalar(LocalField.SKU_ID, LocalInputKind.REFERENCE, readOnly = true).copy(references = skuChoices),
        idField(LocalField.WAREHOUSE_ID, readOnly = true), idField(LocalField.UNIT, readOnly = true),
        scalar(LocalField.QUANTITY_SCALE, LocalInputKind.INTEGER, readOnly = true, maximum = 1),
        choiceField(LocalField.TRACKING, LocalTrackingV1.entries.map { LocalChoice(it.name, literal(it.name)) }).copy(readOnly = true),
        idField(LocalField.SERIAL_ID, required = false, readOnly = false).copy(help = text(
            "Serial SKU only. The generated local pending ID is NOT registration. Replace it only with the exact registered identity when already supplied by an authorized source.",
            "للصنف المسلسل فقط. المعرف المحلي المولد قيد الانتظار وليس تسجيلًا. استبدله فقط بالمعرف المسجل الدقيق إذا وفره مصدر مخول مسبقًا.")),
        scalar(LocalField.SERIAL_CODE, LocalInputKind.TEXT, required = false, maximum = 128, help = text(
            "Serial SKU only: exact physical code, without trimming or digit/case conversion; up to 128 characters / 256 UTF-8 bytes. Leave both serial fields empty for nonserial stock.",
            "للصنف المسلسل فقط: الرمز الفعلي الدقيق دون حذف مسافات أو تحويل أرقام أو حالة الأحرف؛ حتى 128 حرفًا / 256 بايت UTF-8. اترك حقلي المسلسل فارغين لغير المسلسل.")),
        scalar(LocalField.INSPECTION_REQUIRED, LocalInputKind.CHECKBOX, required = false, readOnly = true),
        scalar(LocalField.NEW_ITEM, LocalInputKind.CHECKBOX, LocalScalarValue.Flag(false), required = false,
            help = if (action == LocalAction.STOCK_OPENING) text(
                "Explicit first STOCK record for an existing approved catalog SKU, not a new product. Select only after the successful no-item-revision observation; the master still checks first entry. Never inferred for you.",
                "أول سجل مخزون صريح لصنف كتالوج قائم معتمد، لا منتج جديد. اختره فقط بعد ملاحظة ناجحة لعدم وجود مراجعة صنف مخزون؛ لا يزال الجهاز الرئيسي يتحقق من أول قيد. لا يُستنتج نيابةً عنك.")
            else text("Only explicitly select NEW when there is no item storage revision. A missing read never selects this for you.",
                "اختر جديد صراحةً فقط عند عدم وجود مراجعة تخزين للصنف. لا تحدد القراءة المفقودة هذا الخيار نيابةً عنك.")).let {
            if (action == LocalAction.STOCK_OPENING) it.copy(label = text("Explicit first stock record for existing SKU", "أول سجل مخزون صريح للصنف القائم")) else it
        },
        scalar(LocalField.EXPECTED_ITEM_STORAGE_REVISION, LocalInputKind.INTEGER, required = false, readOnly = true, maximum = 19),
        exactQuantityField(LocalField.QUANTITY), idField(LocalField.LOT_ID, required = false, readOnly = false), instantField(LocalField.EXPIRY_AT, required = false),
        choiceField(LocalField.DISPOSITION, (if (action == LocalAction.STOCK_OPENING)
            listOf(LocalReceiptDisposition.RELEASED, LocalReceiptDisposition.QUARANTINE) else LocalReceiptDisposition.entries)
            .map { LocalChoice(it.name, receiptDisposition(it)) }),
        moneyField(LocalField.ORIGINAL_OWNED_VALUE, required = false))
    val explanation = if (action == LocalAction.STOCK_OPENING) text(
        "Post COUNTED first stock for an existing approved catalog SKU, not a new purchase or product. Review all its counted warehouse/lot/serial slices and each exact owned value together. The approval's ceilings are not counts or remaining balances. Splitting keeps the allocation but creates a NEW line/source identity; a serial split also replaces the pending ID and clears its code. Base units only; one whole unit per exact serial ID/code. A required lot/expiry must be supplied from the physical stock and actual catalog. Released/quarantined slices require explicit owned value; inspection-required or unregistered serial stock must stay quarantined. Entering an ID/code does NOT register a serial. Explicit first-stock selection is mandatory and the master enforces no prior stock item. Saving/submit/lookup use one immutable original operation. This operation adds inventory/value, with no sale, revenue, supplier debt or cash movement; drawer float is separate.",
        "رحل المخزون الأول المعدود لصنف كتالوج قائم معتمد، لا شراءً أو منتجًا جديدًا. راجع كل أجزائه المعدودة حسب المخزن والتشغيلة والمسلسل والقيمة المملوكة الدقيقة لكل جزء معًا. حدود الاعتماد ليست كميات معدودة أو أرصدة متبقية. يحتفظ التقسيم بالتخصيص ويولد معرف سطر ومصدر جديدين؛ وللمسلسل يستبدل المعرف المعلق ويمسح رمزه أيضًا. وحدات أساسية فقط؛ وحدة كاملة لكل معرف ورمز مسلسل دقيقين. يجب توفير التشغيلة والصلاحية المطلوبتين من المخزون الفعلي والكتالوج الحقيقي. يتطلب المتاح والمحجور قيمة مملوكة صريحة؛ يجب حجر ما يتطلب الفحص أو المسلسل غير المسجل. إدخال المعرف والرمز لا يسجل المسلسل. اختيار أول سجل مخزون صراحةً إلزامي ويتحقق الجهاز الرئيسي من عدم وجود سجل سابق. يستخدم الحفظ والإرسال والبحث عملية أصلية ثابتة واحدة. يضيف الإجراء المخزون والقيمة بلا بيع أو إيراد أو دين مورد أو حركة نقدية؛ رصيد الدرج منفصل.")
    else text("Each row is one physical disposition slice. Splitting retains the approved allocation with a NEW line/source identity; for a serial, it also replaces the local ID and clears the code. Review quantity/value explicitly; nothing is subtracted. Released/quarantined goods require an explicit owned value; rejected-held/refused goods omit it. Each serial needs its own exact ID/code and one whole base unit. Entering an ID/code does NOT register it: pending serials are not sellable; release needs actual matching registration and inspection qualification at the master. NEW item is never inferred. This does not pay the supplier.",
        "كل سطر جزء فعلي بحالة تصرف واحدة. يحتفظ التقسيم بالتخصيص المعتمد مع معرف سطر ومصدر جديد؛ وللمسلسل يستبدل المعرف المحلي ويمسح الرمز أيضًا. راجع الكمية والقيمة صراحةً؛ لا يُطرح شيء. المتاح والمحجوز للفحص يتطلبان قيمة مملوكة صريحة؛ المرفوض المحتفظ به أو عند الباب بلا قيمة. يحتاج كل مسلسل إلى معرفه ورمزه الدقيقين ووحدة أساسية كاملة. إدخالهما لا يسجله: المعلق غير قابل للبيع؛ تتطلب الإتاحة تسجيلًا فعليًا مطابقًا وتأهيل الفحص لدى الجهاز الرئيسي. لا يُستنتج الصنف الجديد. هذا لا يدفع المورد.")
    val result = form(action, listOf(receiptField, idField(LocalField.BASIS_ID, approval.id),
        revisionField(LocalField.EXPECTED_BASIS_REVISION, revision), stockReasonField(),
        LocalFormField.Lines(LocalField.LINES, fieldLabel(LocalField.LINES), columns, maximumLines = 16, initialLines = initial,
            allowBlankLines = false, allowDuplicateLines = true)), explanation)
    return if (action == LocalAction.STOCK_OPENING) result.copy(
        title = text("Post counted first opening stock", "ترحيل المخزون الافتتاحي الأول المعدود"),
        confirmation = text("I reviewed every counted warehouse/lot/serial slice and exact owned value together, including omitted allocations. This is the first stock entry for the existing catalog SKU, not a purchase, sale or drawer float. Save one immutable original for separate submission; an uncertain reply must be resolved using that same operation.",
            "راجعت كل جزء معدود حسب المخزن والتشغيلة والمسلسل والقيمة المملوكة الدقيقة معًا، بما فيها التخصيصات المحذوفة. هذا أول قيد مخزون لصنف الكتالوج القائم، لا شراء أو بيع أو رصيد درج. احفظ أصلًا ثابتًا واحدًا لإرساله منفصلًا؛ يجب حسم الرد غير المؤكد بنفس العملية.")) else result
}

private suspend fun receiptWorkflow(access: GatewayAccess, query: LocalWorkflowQuery, reference: LocalRecordReference): LocalWorkflowPage {
    val id = if (reference.kind == LocalRecordKind.RECEIPT_LINE) reference.parentId ?: invalid(LocalField.RECEIPT_ID) else reference.id
    val read = access.read(LocalStockCommands.receipt(id))
    val receipt = LocalStockQueries.decodeReceipt(read.payload) ?: return LocalWorkflowPage(query.workflow, emptyList(), emptyList(),
        text("No receipt is available for that ID. No stock or supplier liability is inferred.", "لا يوجد استلام متاح لهذا المعرف. لا يُستنتج مخزون أو التزام تجاه المورد."))
    if (receipt.receiptId != id) unavailable()
    val opening = receipt.basisKind == LocalStockBasisKind.OPENING
    val selected = if (reference.kind == LocalRecordKind.RECEIPT_LINE) receipt.lines.singleOrNull { it.lineId == reference.id } ?: invalid(LocalField.LINE_ID) else null
    val lines = selected?.let(::listOf) ?: receipt.lines
    val records = listOf(LocalRecord(LocalRecordReference(LocalRecordKind.RECEIPT, id),
        if (opening) text("Posted counted opening stock", "مخزون افتتاحي معدود مرحل") else text("Posted physical receipt", "استلام فعلي مُرحّل"),
        literal(receipt.basisKind.name), buildList {
        add(recordValue(fieldLabel(LocalField.EXPECTED_RECEIPT_REVISION), literal(receipt.storageRevision.value.toString())))
        add(recordValue(fieldLabel(LocalField.BASIS_ID), literal(receipt.basisId.value)))
        add(recordValue(fieldLabel(LocalField.EXPECTED_BASIS_REVISION), literal(receipt.basisRevision.value.toString())))
        add(recordValue(text("Master receipt time", "وقت الاستلام لدى الجهاز الرئيسي"), literal(receipt.receivedAt.canonicalString())))
        add(recordValue(text("Values", "القيم"), if (receipt.costsIncluded) text("Authorized values included", "القيم المصرح بها مرفقة") else text("Not requested; not zero", "لم تُطلب؛ ليست صفرًا")))
    })) + lines.map { receiptLineRecord(id, it, receipt.costsIncluded, opening) }
    val forms = mutableListOf<LocalActionForm>()
    if (query.workflow == LocalWorkflow.RECEIVING && selected != null && selected.disposition == LocalReceiptDisposition.QUARANTINE &&
        selected.releasedFromQuarantine < selected.baseQuantity &&
        access.installed.has(if (selected.serialId != null) LocalStockStoreProfile.INSPECT_SERIAL else LocalStockStoreProfile.INSPECT) &&
        access.allow(LocalAction.INSPECTION_RELEASE, id)) {
        val itemRevision = stockQuantities(access, selected.skuId, limit = 1).page.itemStorageRevision ?: unavailable()
        forms += form(LocalAction.INSPECTION_RELEASE, listOf(newIdField(LocalField.INSPECTION_ID), idField(LocalField.RECEIPT_ID, id),
            idField(LocalField.LINE_ID, selected.lineId), idField(LocalField.SERIAL_ID, selected.serialId, required = false, readOnly = true),
            revisionField(LocalField.EXPECTED_RECEIPT_REVISION, receipt.storageRevision),
            revisionField(LocalField.EXPECTED_ITEM_STORAGE_REVISION, itemRevision)) + quantityMetadata(selected.baseQuantity) + listOf(
            exactQuantityField(LocalField.QUANTITY), newIdField(LocalField.EVIDENCE_ID), stockReasonField()),
            text("Release only this original quarantined receipt line after actual inspection. A serial release is one whole unit with the original serial ID; only the master can qualify current registration. Historical receipt registration is not current release authority. Original value/conversion lineage is retained; no new purchase or supplier settlement is created.",
                "أتح هذا السطر الأصلي المحجوز بعد الفحص الفعلي فقط. إتاحة المسلسل لوحدة كاملة بمعرفها الأصلي؛ الجهاز الرئيسي وحده يؤهل التسجيل الحالي. تسجيل الاستلام التاريخي ليس صلاحية إتاحة حالية. تُحفظ القيمة الأصلية وتسلسل التحويل؛ لا ينشأ شراء جديد أو تسوية مورد."))
    }
    val openingNotice = if (opening) text("Counted opening stock/value is not a purchase, sale, revenue or drawer float. Use Inventory → Opening for the separate authorized original-value read. ",
        "المخزون والقيمة الافتتاحيان المعدودان ليسا شراءً أو بيعًا أو إيرادًا أو رصيد درج. استخدم المخزون ← الافتتاحي لقراءة القيمة الأصلية المنفصلة المصرح بها. ") else literal("")
    return LocalWorkflowPage(query.workflow, records, forms, text("${openingNotice.english}Quantity-only receipt read. Select a quarantined line for its actual inspection-release preconditions. ${readCut(read).english}",
        "${openingNotice.arabic}قراءة استلام للكميات فقط. اختر سطرًا محجوزًا للفحص للحصول على شروط إتاحته الفعلية. ${readCut(read).arabic}"))
}

internal fun receiptLineRecord(receiptId: DomainId, line: LocalStockReceivedLine, costsIncluded: Boolean,
    opening: Boolean = false): LocalRecord = LocalRecord(
    LocalRecordReference(LocalRecordKind.RECEIPT_LINE, line.lineId, parentId = receiptId),
    if (opening) text("Counted opening stock slice", "جزء مخزون افتتاحي معدود") else text("Physical receipt slice", "جزء الاستلام الفعلي"),
    receiptDisposition(line.disposition), buildList {
        add(recordValue(fieldLabel(LocalField.SKU_ID), literal(line.skuId.value)))
        add(recordValue(fieldLabel(LocalField.WAREHOUSE_ID), literal(line.warehouseId.value)))
        add(recordValue(fieldLabel(LocalField.ALLOCATION_ID), literal(line.allocationId.value)))
        add(recordValue(fieldLabel(LocalField.SOURCE_ID), literal(line.sourceId.value)))
        add(recordValue(if (opening) text("Counted opening quantity", "الكمية الافتتاحية المعدودة")
            else text("Recorded purchase quantity", "كمية الشراء المسجلة"), quantityText(line.recordedQuantity)))
        add(recordValue(text("Exact base quantity", "الكمية الأساسية الدقيقة"), quantityText(line.baseQuantity)))
        add(recordValue(text("Released from quarantine", "المتاح بعد الفحص"), quantityText(line.releasedFromQuarantine)))
        line.lotId?.let { add(recordValue(fieldLabel(LocalField.LOT_ID), literal(it.value))) }
        line.serialId?.let { add(recordValue(fieldLabel(LocalField.SERIAL_ID), literal(it.value))) }
        line.serialCode?.let { add(recordValue(fieldLabel(LocalField.SERIAL_CODE), literal(it))) }
        line.initialSerialRegistrationStatus?.let { status -> add(recordValue(
            text("Historical registration at receipt (not current)", "التسجيل التاريخي عند الاستلام (ليس الحالي)"),
            when (status) {
                StockSerialRegistrationStatus.LOCAL_PENDING_REGISTRATION -> text("Pending at receipt; not proof of current registration", "كان معلقًا عند الاستلام؛ ليس دليل تسجيل حالي")
                StockSerialRegistrationStatus.REGISTERED -> text("Registered at receipt; not proof of current eligibility", "كان مسجلًا عند الاستلام؛ ليس دليل أهلية حالية")
            })) }
        line.expiresAt?.let { add(recordValue(fieldLabel(LocalField.EXPIRY_AT), literal(it.canonicalString()))) }
        line.conversion?.let { conversion ->
            add(recordValue(fieldLabel(LocalField.CONVERSION_ID), literal("${conversion.reference.id.value} · ${conversion.reference.revision.value}")))
            add(recordValue(text("Exact conversion snapshot", "لقطة التحويل الدقيقة"), literal("${conversion.sourceUnitId.value} → ${conversion.targetUnitId.value} · ${conversion.rate.canonicalString()}")))
            conversion.massVolumeEvidenceId?.let { add(recordValue(fieldLabel(LocalField.EVIDENCE_ID), literal(it.value))) }
        }
        if (costsIncluded) line.originalValue?.let { add(recordValue(fieldLabel(LocalField.ORIGINAL_OWNED_VALUE), moneyText(it))) }
    })

internal suspend fun prepareReceiving(access: GatewayAccess, intent: LocalCommandIntent): MutationRequest {
    if (intent.values.any { it.field == LocalField.MODE }) return preparePoReceiving(access, intent)
    val input = InputValues(intent.values)
    val request = when (intent.action) {
        LocalAction.INSPECTION_RELEASE -> {
            if (intent.lineField != null || intent.lines.isNotEmpty()) invalid(LocalField.LINES)
            val inspection = input.id(LocalField.INSPECTION_ID)
            val id = input.id(LocalField.RECEIPT_ID)
            access.requireAction(intent.action, id)
            val lineId = input.id(LocalField.LINE_ID)
            val receipt = LocalStockQueries.decodeReceipt(access.read(LocalStockCommands.receipt(id)).payload) ?: invalid(LocalField.RECEIPT_ID)
            val revision = input.revision(LocalField.EXPECTED_RECEIPT_REVISION)
            val itemRevision = input.revision(LocalField.EXPECTED_ITEM_STORAGE_REVISION)
            if (receipt.receiptId != id || receipt.storageRevision != revision) invalid(LocalField.EXPECTED_RECEIPT_REVISION)
            val line = receipt.lines.singleOrNull { it.lineId == lineId } ?: invalid(LocalField.LINE_ID)
            val serialId = input.optionalId(LocalField.SERIAL_ID)
            if (serialId != line.serialId) invalid(LocalField.SERIAL_ID)
            val quantity = input.exactQuantity(LocalField.QUANTITY, line.baseQuantity)
            if (serialId != null && quantity != Quantity.parse(line.baseQuantity.unitId, "1", line.baseQuantity.scale)) invalid(LocalField.QUANTITY)
            LocalStockCommands.inspect(intent.operationId, LocalInspectionReleaseRequest(inspection, id, lineId, revision,
                itemRevision, quantity, input.id(LocalField.EVIDENCE_ID), input.text(LocalField.REASON), serialId))
        }
        LocalAction.RECEIPT_POST, LocalAction.STOCK_OPENING -> {
            if (intent.lineField != LocalField.LINES || intent.lines.size !in 1..16) invalid(LocalField.LINES)
            val receiptId = input.id(LocalField.RECEIPT_ID)
            val basisId = input.id(LocalField.BASIS_ID)
            access.requireAction(intent.action, if (intent.action == LocalAction.STOCK_OPENING) receiptId else basisId)
            val revision = input.revision(LocalField.EXPECTED_BASIS_REVISION)
            val cut = configurationRead(access, LocalConfigurationQueryV1(LocalConfigurationProfile.BASIS, id = basisId))
            val basis = (cut.page.detail as? LocalConfigurationDetailV1.ReceivingBasis)?.value ?: invalid(LocalField.BASIS_ID)
            if (basis.id != basisId || cut.page.items.singleOrNull()?.revision != revision) invalid(LocalField.EXPECTED_BASIS_REVISION)
            if ((basis.kind == LocalBasisKindV1.OPENING) != (intent.action == LocalAction.STOCK_OPENING)) invalid(LocalField.BASIS_ID)
            // This existing UI field attests a complete physical count, including actual ZERO
            // for every omitted allocation. Its input digest is retained with the old request.
            // Zero IDs are the exact complement of the positive rows in this approved SKU group;
            // no zero line, new command/schema, stock reset or second receipt is synthesized.
            val hasCountReview = intent.action == LocalAction.STOCK_OPENING && intent.values.any { it.field == LocalField.CONFIRMED }
            val countReviewed = hasCountReview && input.flag(LocalField.CONFIRMED)
            if (hasCountReview && !countReviewed) invalid(LocalField.CONFIRMED)
            val items = mutableMapOf<DomainId, LocalStockItemVersion>()
            val details = mutableMapOf<DomainId, LocalConfigurationDetailV1.Sku>()
            val lines = intent.lines.map { row ->
                val line = InputValues(row.values)
                val allocation = basis.lines.singleOrNull { it.allocationId == line.id(LocalField.ALLOCATION_ID) } ?: invalid(LocalField.ALLOCATION_ID)
                val sku = line.reference(LocalField.SKU_ID, LocalRecordKind.SKU)
                val warehouse = line.id(LocalField.WAREHOUSE_ID)
                if (sku.id != allocation.skuId || warehouse != allocation.warehouseId || line.flag(LocalField.INSPECTION_REQUIRED) != allocation.inspectionRequired) invalid(LocalField.ALLOCATION_ID)
                val detail = details[sku.id] ?: skuDetail(access, sku.id).first.also { details[sku.id] = it }
                if (detail.skuRevision != sku.revision || !detail.active || detail.tracking == LocalTrackingV1.NONE ||
                    intent.action != LocalAction.STOCK_OPENING && !detail.canPurchase) invalid(LocalField.SKU_ID)
                if (line.choice(LocalField.TRACKING) != detail.tracking.name) invalid(LocalField.TRACKING)
                if (line.id(LocalField.UNIT) != detail.baseUnitId || line.integer(LocalField.QUANTITY_SCALE) != detail.quantityScale.toLong()) invalid(LocalField.UNIT)
                val isNew = line.flag(LocalField.NEW_ITEM)
                val itemRevision = line.optionalRevision(LocalField.EXPECTED_ITEM_STORAGE_REVISION)
                if (isNew && itemRevision != null || !isNew && (itemRevision == null || itemRevision.value <= 0) ||
                    intent.action == LocalAction.STOCK_OPENING && (!isNew || itemRevision != null)) invalid(LocalField.NEW_ITEM)
                val version = LocalStockItemVersion(sku.id, itemRevision)
                if (items.containsKey(sku.id) && items[sku.id] != version) invalid(LocalField.EXPECTED_ITEM_STORAGE_REVISION)
                items[sku.id] = version
                val quantity = Quantity.parse(detail.baseUnitId, line.decimal(LocalField.QUANTITY), detail.quantityScale)
                if (intent.action == LocalAction.STOCK_OPENING && quantity.scaled <= 0) invalid(LocalField.QUANTITY)
                val serialId = line.optionalId(LocalField.SERIAL_ID)
                val serialCode = line.optionalText(LocalField.SERIAL_CODE)
                if (detail.tracking == LocalTrackingV1.SERIAL) {
                    if (serialId == null) invalid(LocalField.SERIAL_ID)
                    if (serialCode == null || serialCode.isEmpty() || serialCode.length > 128 || serialCode != serialCode.trim() ||
                        serialCode.any { it.code < 32 || it.code == 127 } || serialCode.encodeToByteArray(throwOnInvalidSequence = true).size > 256)
                        invalid(LocalField.SERIAL_CODE)
                    if (quantity != Quantity.parse(detail.baseUnitId, "1", detail.quantityScale)) invalid(LocalField.QUANTITY)
                } else if (serialId != null || serialCode != null) invalid(LocalField.SERIAL_ID)
                val lot = line.optionalId(LocalField.LOT_ID)
                val expiry = line.optionalInstant(LocalField.EXPIRY_AT)
                val disposition = enumChoice<LocalReceiptDisposition>(line.choice(LocalField.DISPOSITION), LocalField.DISPOSITION)
                if (intent.action == LocalAction.STOCK_OPENING && disposition !in setOf(LocalReceiptDisposition.RELEASED,
                    LocalReceiptDisposition.QUARANTINE)) invalid(LocalField.DISPOSITION)
                val value = line.optionalMoney(LocalField.ORIGINAL_OWNED_VALUE)
                if ((disposition in setOf(LocalReceiptDisposition.RELEASED, LocalReceiptDisposition.QUARANTINE)) != (value != null)) invalid(LocalField.ORIGINAL_OWNED_VALUE)
                if (allocation.inspectionRequired && disposition == LocalReceiptDisposition.RELEASED) invalid(LocalField.DISPOSITION)
                line.finish()
                // Distinct namespace uses of this fresh immutable row ID; splitting never copies it.
                LocalGoodsReceiptLine(row.lineId, allocation.allocationId, row.lineId, sku.id, warehouse, quantity, null,
                    lot, expiry, disposition, value, serialId, serialCode)
            }
            if (intent.action == LocalAction.STOCK_OPENING && !countReviewed) {
                // Old fresh inputs that explicitly count every allocation remain valid. A
                // subset cannot silently claim that skipped physical stock was counted zero.
                for (skuId in items.keys) {
                    val counted = lines.filter { it.skuId == skuId }.map { it.allocationId }.toSet()
                    if (basis.lines.any { it.skuId == skuId && it.allocationId !in counted }) invalid(LocalField.CONFIRMED)
                }
            }
            val receipt = LocalGoodsReceiptRequest(receiptId, basisId, revision, items.values.toList(), lines, input.text(LocalField.REASON))
            if (intent.action == LocalAction.STOCK_OPENING) LocalStockCommands.opening(intent.operationId, receipt)
            else LocalStockCommands.receive(intent.operationId, receipt)
        }
        else -> throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    }
    input.finish()
    return routeFreshStockIntent(access, request)
}

/** Revalidate selected revisions; never refresh a saved source token or insert a client price. */
private suspend fun preparePoReceiving(access: GatewayAccess, intent: LocalCommandIntent): MutationRequest {
    val input = InputValues(intent.values)
    if (intent.action != LocalAction.RECEIPT_POST || input.choice(LocalField.MODE) != PO_QUANTITY_MODE) invalid(LocalField.MODE)
    if (intent.lineField != LocalField.LINES || intent.lines.size !in 1..16) invalid(LocalField.LINES)
    if (!access.installed.has(LocalStockStoreProfile.RECEIVE_PO)) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    val receiptId = input.id(LocalField.RECEIPT_ID)
    val orderId = input.id(LocalField.BASIS_ID)
    access.requireAction(intent.action, orderId)
    val expectedOrder = input.revision(LocalField.EXPECTED_ORDER_REVISION)
    val expectedBasis = input.revision(LocalField.EXPECTED_BASIS_REVISION)
    val source = poReceivingRead(access, orderId)
    if (source.page.availability != LocalPurchasingReadAvailabilityV1.AVAILABLE) unavailable()
    val order = source.page.orders?.singleOrNull() ?: invalid(LocalField.BASIS_ID)
    if (order.orderStorageRevision != expectedOrder) invalid(LocalField.EXPECTED_ORDER_REVISION)
    if (order.basisRevision != expectedBasis) invalid(LocalField.EXPECTED_BASIS_REVISION)
    val items = mutableMapOf<DomainId, LocalStockItemVersion>()
    val allowances = mutableMapOf<DomainId, LocalPoAllowanceVersionV1>()
    val taken = mutableMapOf<DomainId, Quantity>()
    val lines = intent.lines.map { row ->
        val line = InputValues(row.values)
        val allocation = order.allocations.singleOrNull { it.allocationId == line.id(LocalField.ALLOCATION_ID) } ?: invalid(LocalField.ALLOCATION_ID)
        val sku = line.reference(LocalField.SKU_ID, LocalRecordKind.SKU)
        val warehouse = line.id(LocalField.WAREHOUSE_ID)
        if (sku.id != allocation.skuId || warehouse != allocation.warehouseId ||
            line.flag(LocalField.INSPECTION_REQUIRED) != allocation.inspectionRequired) invalid(LocalField.ALLOCATION_ID)
        if (!allocation.currentItemMatches || !allocation.currentWarehouseActive || sku.revision != allocation.skuRevision) invalid(LocalField.SKU_ID)
        val tracking = allocation.tracking ?: invalid(LocalField.TRACKING)
        if (line.choice(LocalField.TRACKING) != tracking.name || tracking == LocalTrackingV1.NONE) invalid(LocalField.TRACKING)
        val unit = allocation.orderedBaseQuantity
        if (line.id(LocalField.UNIT) != unit.unitId || line.integer(LocalField.QUANTITY_SCALE) != unit.scale.toLong()) invalid(LocalField.UNIT)
        val expectedItem = line.optionalRevision(LocalField.EXPECTED_ITEM_STORAGE_REVISION)
        if (expectedItem != allocation.itemStorageRevision) invalid(LocalField.EXPECTED_ITEM_STORAGE_REVISION)
        val isNew = line.flag(LocalField.NEW_ITEM)
        if (isNew != (expectedItem == null) || expectedItem != null && expectedItem.value <= 0) invalid(LocalField.NEW_ITEM)
        val version = LocalStockItemVersion(sku.id, expectedItem)
        if (sku.id in items && items[sku.id] != version) invalid(LocalField.EXPECTED_ITEM_STORAGE_REVISION)
        items[sku.id] = version
        val sourceRevision = line.integer(LocalField.EXPECTED_SOURCE_REVISION)
        if (sourceRevision < 0) invalid(LocalField.EXPECTED_SOURCE_REVISION)
        val expectedAllowance = if (sourceRevision == 0L) null else Revision(sourceRevision)
        if (expectedAllowance != allocation.allowanceStorageRevision) invalid(LocalField.EXPECTED_SOURCE_REVISION)
        val quantity = Quantity.parse(unit.unitId, line.decimal(LocalField.QUANTITY), unit.scale)
        if (quantity.scaled <= 0) invalid(LocalField.QUANTITY)
        val serialId = line.optionalId(LocalField.SERIAL_ID)
        val serialCode = line.optionalText(LocalField.SERIAL_CODE)
        if (tracking == LocalTrackingV1.SERIAL) {
            if (serialId == null) invalid(LocalField.SERIAL_ID)
            if (serialCode == null || serialCode.isEmpty() || serialCode.length > 128 || serialCode != serialCode.trim() ||
                serialCode.any { it.code < 32 || it.code == 127 } || serialCode.encodeToByteArray(throwOnInvalidSequence = true).size > 256)
                invalid(LocalField.SERIAL_CODE)
            if (quantity != Quantity.parse(unit.unitId, "1", unit.scale)) invalid(LocalField.QUANTITY)
        } else if (serialId != null || serialCode != null) invalid(LocalField.SERIAL_ID)
        val lot = line.optionalId(LocalField.LOT_ID)
        val expiry = line.optionalInstant(LocalField.EXPIRY_AT)
        if (tracking == LocalTrackingV1.LOT && lot == null || expiry != null && lot == null) invalid(LocalField.LOT_ID)
        if (allocation.requiresExpiry == null) unavailable()
        if (allocation.requiresExpiry == true && (lot == null || expiry == null)) invalid(LocalField.EXPIRY_AT)
        val disposition = enumChoice<LocalReceiptDisposition>(line.choice(LocalField.DISPOSITION), LocalField.DISPOSITION)
        if (allocation.inspectionRequired && disposition == LocalReceiptDisposition.RELEASED) invalid(LocalField.DISPOSITION)
        if (disposition in setOf(LocalReceiptDisposition.RELEASED, LocalReceiptDisposition.QUARANTINE)) {
            if (!allocation.valuationLineageReady) invalid(LocalField.BASIS_ID)
            val prior = taken[allocation.allocationId] ?: unit.copy(scaled = 0)
            if (quantity > allocation.remainingQuantity - prior) invalid(LocalField.QUANTITY)
            taken[allocation.allocationId] = prior + quantity
            val allowance = LocalPoAllowanceVersionV1(allocation.allocationId, expectedAllowance)
            if (allocation.allocationId in allowances && allowances[allocation.allocationId] != allowance) invalid(LocalField.EXPECTED_SOURCE_REVISION)
            allowances[allocation.allocationId] = allowance
        }
        // Monetary fields, conversions and all unknown inputs are rejected, not silently ignored.
        line.finish()
        LocalPoReceiptLineV1(row.lineId, allocation.allocationId, row.lineId, sku.id, warehouse, quantity, lot, expiry, disposition, serialId, serialCode)
    }
    val request = LocalPoReceiptRequestV1(receiptId, orderId, expectedOrder, expectedBasis, items.values.toList(),
        allowances.values.toList(), lines, input.text(LocalField.REASON))
    input.finish()
    return routeFreshStockIntent(access, LocalStockCommands.receivePo(intent.operationId, request))
}

private fun receiptDisposition(disposition: LocalReceiptDisposition): LocalText = when (disposition) {
    LocalReceiptDisposition.RELEASED -> text("Accepted and released", "مقبول ومتاح")
    LocalReceiptDisposition.QUARANTINE -> text("Accepted, awaiting inspection", "مقبول ومحجوز للفحص")
    LocalReceiptDisposition.REJECTED_HELD -> text("Rejected, held for supplier", "مرفوض ومحتفظ به للمورد")
    LocalReceiptDisposition.REFUSED -> text("Refused at door", "مرفوض عند الباب")
}
