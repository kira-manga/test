package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money

/** An entry to the existing protected approval/receiving services, not a second stock ledger. */
internal suspend fun openingStockWorkflow(access: GatewayAccess, query: LocalWorkflowQuery): LocalWorkflowPage {
    if (query.workflow != LocalWorkflow.INVENTORY || query.inventory != LocalInventorySection.OPENING) invalid()
    val reference = query.reference ?: when {
        query.search.startsWith("basis:") -> LocalRecordReference(LocalRecordKind.RECEIVING_BASIS, searchedId(query.search.removePrefix("basis:")))
        query.search.startsWith("receipt:") -> LocalRecordReference(LocalRecordKind.RECEIPT, searchedId(query.search.removePrefix("receipt:")))
        else -> null
    }
    if (reference?.kind in setOf(LocalRecordKind.RECEIPT, LocalRecordKind.RECEIPT_LINE))
        return openingReceiptWorkflow(access, query, checkNotNull(reference))
    // A real SKU without a basis parent is the explicit current-value navigation, not an opener.
    if (reference?.kind == LocalRecordKind.SKU && reference.parentId == null)
        return stockCostWorkflow(access, query, reference.id)
    if (reference == null) return openingBasisDirectory(access, query)
    val basisId = when (reference.kind) {
        LocalRecordKind.RECEIVING_BASIS -> reference.id.also { if (reference.parentId != null) invalid(LocalField.BASIS_ID) }
        LocalRecordKind.RECEIVING_ALLOCATION, LocalRecordKind.SKU -> reference.parentId ?: invalid(LocalField.BASIS_ID)
        else -> invalid()
    }
    val cut = openingBasisRead(access, basisId)
    val approval = (cut.page.detail as? LocalConfigurationDetailV1.ReceivingBasis)?.value
    if (approval == null || approval.kind != LocalBasisKindV1.OPENING) return LocalWorkflowPage(query.workflow, emptyList(), emptyList(),
        text("No actual opening approval is available for that exact basis ID at this authorized read cut. No stock, value or remaining allowance is inferred.",
            "لا يوجد اعتماد افتتاحي فعلي متاح لمعرف الأساس المحدد في مراجعة القراءة المصرح بها. لا يُستنتج مخزون أو قيمة أو سماح متبقٍ."))
    val row = cut.page.items.singleOrNull() ?: unavailable()
    val skuId = when (reference.kind) {
        LocalRecordKind.SKU -> reference.id
        LocalRecordKind.RECEIVING_ALLOCATION -> {
            if (query.cursor != null) invalid()
            approval.lines.singleOrNull { it.allocationId == reference.id }?.skuId ?: invalid(LocalField.ALLOCATION_ID)
        }
        else -> null
    }
    if (skuId != null) return openingSkuWorkflow(access, query, cut, approval, row, skuId)
    val groups = approval.lines.groupBy { it.skuId }.entries.sortedBy { it.key.value }
    val offset = offsetCursor(query.cursor, "opening-skus", groups.size)
    val visible = groups.drop(offset).take(16)
    return LocalWorkflowPage(query.workflow, listOf(openingBasisRecord(approval, row, selectable = false)) + visible.map { (sku, lines) ->
        LocalRecord(LocalRecordReference(LocalRecordKind.SKU, sku, parentId = basisId),
            text("Select existing catalog SKU for counted opening", "اختر صنف الكتالوج القائم للافتتاحي المعدود"), values = listOf(
                recordValue(fieldLabel(LocalField.SKU_ID), literal(sku.value)),
                recordValue(text("Approved allocation rows for this SKU", "سطور التخصيص المعتمدة لهذا الصنف"), literal(lines.size.toString())),
                recordValue(text("Sum of approved value ceilings — not remaining", "مجموع حدود القيمة المعتمدة — ليس المتبقي"),
                    moneyText(openingValueTotal(lines.map { it.maximumOwnedValue })))))
    }, emptyList(), text(
        "This is an approved allowance, NOT posted opening stock. Select one existing catalog SKU to review all of its counted location/lot/serial/value slices together before its first stock entry. Ceilings are not remaining balances; the master checks exact prior use. Later opening and bulk/resumable import remain required, not supplied by this bounded editor. ${readCut(cut.reply).english}",
        "هذا سماح معتمد، وليس مخزونًا افتتاحيًا مرحلًا. اختر صنف كتالوج قائمًا لمراجعة كل أجزائه المعدودة حسب الموقع والتشغيلة والمسلسل والقيمة معًا قبل أول قيد مخزون له. الحدود ليست أرصدة متبقية؛ يتحقق الجهاز الرئيسي من الاستخدام السابق الدقيق. يبقى الافتتاحي اللاحق والاستيراد الكبير القابل للاستئناف مطلوبين، ولا يوفرهما هذا المحرر المحدود. ${readCut(cut.reply).arabic}"),
        if (offset + visible.size < groups.size) "opening-skus:${offset + visible.size}" else null)
}

/** The existing basis directory has no kind discriminator. Inspect ONLY this bounded source
 * page's authorized exact details, and preserve its real next cursor even after filtering. */
private suspend fun openingBasisDirectory(access: GatewayAccess, query: LocalWorkflowQuery): LocalWorkflowPage {
    val approvalForm = if (access.installed.has(LocalConfigurationProfile.COMMAND)) receivingApprovalForm(access, openingOnly = true) else null
    if (!access.installed.has(LocalConfigurationProfile.QUERY) ||
        !access.hasHint(Permission(SecurityAction.PURCHASING_READ, PermissionVerb.PERFORM)))
        return LocalWorkflowPage(query.workflow, emptyList(), listOfNotNull(approvalForm), text(
            "Opening approval and posting are separate. Browsing existing bases needs the installed protected configuration source and purchasing-read permission; details additionally need cost permission. An approval records only ceilings, never counted stock. Use basis:ID or receipt:ID for an exact authorized lookup. No missing source is treated as empty stock.",
            "اعتماد الافتتاحي وترحيله خطوتان منفصلتان. يتطلب تصفح الأسس القائمة مصدر الإعداد المحمي المثبت وصلاحية قراءة المشتريات؛ وتتطلب التفاصيل صلاحية التكلفة أيضًا. يسجل الاعتماد حدودًا فقط، لا مخزونًا معدودًا. استخدم basis:ID أو receipt:ID للقراءة المحددة المصرح بها. لا يُعامل المصدر المفقود كمخزون فارغ."))
    val after = parseCursor(query.cursor, "opening-bases")
    val cut = openingBasisRead(access, after = after)
    val records = mutableListOf<LocalRecord>()
    for (source in cut.page.items) {
        // A conservative presentation hint cannot authorize a cost read or infer a hidden kind.
        if (!access.hasHint(Permission(SecurityAction.INVENTORY_COST_READ, PermissionVerb.PERFORM), source.id)) continue
        val detailCut = openingBasisRead(access, source.id)
        val approval = (detailCut.page.detail as? LocalConfigurationDetailV1.ReceivingBasis)?.value ?: unavailable()
        val row = detailCut.page.items.singleOrNull() ?: unavailable()
        if (approval.kind == LocalBasisKindV1.OPENING && (query.search.isBlank() || approval.id.value.contains(query.search)))
            records += openingBasisRecord(approval, row)
    }
    return LocalWorkflowPage(query.workflow, records, listOfNotNull(approvalForm), text(
        "Only opening approvals from this bounded source page with current cost-read hints are shown; every shown detail was actually authorized. Other types or hidden details are not inferred. Filtering can leave an empty page with Next. Approval is not posting, and ceilings are NOT remaining balances. Use basis:ID / receipt:ID for exact reads. Counted opening posts stock/value, not a purchase, sale, revenue or cash; drawer float is separate. ${readCut(cut.reply).english}",
        "تُعرض الاعتمادات الافتتاحية من صفحة المصدر المحدودة هذه فقط عندما تسمح مؤشرات قراءة التكلفة الحالية؛ وقد صُرح فعليًا بكل تفصيل معروض. لا تُستنتج الأنواع الأخرى أو التفاصيل المحجوبة. قد يترك الترشيح صفحة فارغة بها التالي. الاعتماد ليس ترحيلًا، والحدود ليست أرصدة متبقية. استخدم basis:ID أو receipt:ID للقراءات المحددة. يرحل الافتتاحي المعدود المخزون والقيمة، لا شراءً أو بيعًا أو إيرادًا أو نقدًا؛ رصيد الدرج منفصل. ${readCut(cut.reply).arabic}"),
        cursor("opening-bases", cut.page.nextAfter))
}

private suspend fun openingBasisRead(access: GatewayAccess, id: DomainId? = null, after: DomainId? = null): ConfigurationCut {
    val cut = configurationRead(access, LocalConfigurationQueryV1(LocalConfigurationProfile.BASIS, id = id, after = after, limit = 16))
    val page = cut.page
    if (page.items.zipWithNext().any { (a, b) -> a.id.value >= b.id.value } ||
        after != null && page.items.any { it.id.value <= after.value } ||
        page.nextAfter != null && page.nextAfter != page.items.lastOrNull()?.id ||
        id == null && page.detail != null || id != null && (after != null || page.nextAfter != null || page.items.size > 1 || page.items.any { it.id != id })) unavailable()
    if (id != null) {
        val detail = page.detail
        if (detail == null) { if (page.items.isNotEmpty()) unavailable() }
        else if (detail !is LocalConfigurationDetailV1.ReceivingBasis || detail.value.id != id ||
            page.items.singleOrNull()?.id != id) unavailable()
    }
    return cut
}

private fun openingBasisRecord(approval: LocalReceivingApprovalV1, row: LocalConfigurationListItemV1,
    selectable: Boolean = true): LocalRecord = LocalRecord(
    LocalRecordReference(LocalRecordKind.RECEIVING_BASIS, approval.id, row.revision),
    text("Opening allowance — approval only", "سماح افتتاحي — اعتماد فقط"),
    text("Approved ceilings; posted stock not inferred", "حدود معتمدة؛ لا يُستنتج مخزون مرحل"), buildList {
        add(recordValue(fieldLabel(LocalField.BASIS_ID), literal(approval.id.value)))
        add(recordValue(fieldLabel(LocalField.EXPECTED_BASIS_REVISION), literal(row.revision.value.toString())))
        add(recordValue(fieldLabel(LocalField.VALID_FROM), literal(approval.validFrom.canonicalString())))
        approval.validUntil?.let { add(recordValue(fieldLabel(LocalField.VALID_UNTIL), literal(it.canonicalString()))) }
        add(recordValue(text("Approved allocation rows", "سطور التخصيص المعتمدة"), literal(approval.lines.size.toString())))
        add(recordValue(text("Total approved value ceiling — not balance", "إجمالي حد القيمة المعتمد — ليس رصيدًا"),
            moneyText(openingValueTotal(approval.lines.map { it.maximumOwnedValue }))))
    }, selectable = selectable)

private suspend fun openingSkuWorkflow(access: GatewayAccess, query: LocalWorkflowQuery, basisCut: ConfigurationCut,
    approval: LocalReceivingApprovalV1, row: LocalConfigurationListItemV1, skuId: DomainId): LocalWorkflowPage {
    val allocations = approval.lines.filter { it.skuId == skuId }.sortedBy { it.allocationId.value }
    if (allocations.isEmpty()) invalid(LocalField.SKU_ID)
    val detail = skuDetail(access, skuId).first
    query.openingAllocations?.let { selected ->
        if (selected.basisId != approval.id || selected.skuId != skuId) invalid(LocalField.BASIS_ID)
        if (selected.basisRevision != row.revision) invalid(LocalField.EXPECTED_BASIS_REVISION)
        if (selected.skuRevision != detail.skuRevision) invalid(LocalField.SKU_ID)
    }
    // A successful real quantity cut is required even for a null item revision. Null is only
    // an eligibility observation; the existing atomic master's first-entry/CAS guard remains authority.
    val quantities = stockQuantities(access, skuId, limit = 1)
    val itemRevision = quantities.page.itemStorageRevision
    val offset = offsetCursor(query.cursor, "opening-allocations", allocations.size)
    val visible = allocations.drop(offset).take(LocalOpeningAllocationPage.PAGE_SIZE)
    val unitMatches = allocations.all { it.maximumBaseQuantity.unitId == detail.baseUnitId && it.maximumBaseQuantity.scale == detail.quantityScale }
    val inWindow = quantities.reply.observedAt >= approval.validFrom && (approval.validUntil?.let { quantities.reply.observedAt < it } != false)
    val eligible = detail.active && detail.tracking != LocalTrackingV1.NONE && unitMatches && itemRevision == null && inWindow
    // This is only one source page. It cannot become a partial first opening: the attached
    // complete ID roster and explicit zero/count decisions gate the whole editor before save.
    val template = if (eligible)
        receivingForm(access, approval, row.revision, visible, LocalAction.STOCK_OPENING) else null
    val allocationRecords = visible.map { line -> openingAllocationRecord(approval.id, row.revision, line) }
    val allocationPage = template?.let { source ->
        val initial = source.fields.filterIsInstance<LocalFormField.Lines>().single().initialLines
        if (initial.size != visible.size) unavailable()
        LocalOpeningAllocationPage(LocalOpeningAllocationQuery(approval.id, row.revision, skuId, detail.skuRevision),
            allocations.map { it.allocationId }, offset, allocationRecords.zip(initial) { record, line ->
                LocalOpeningAllocationRow(record, line)
            }, if (offset + visible.size < allocations.size) "opening-allocations:${offset + visible.size}" else null)
    }
    val post = if (query.openingAllocations == null && allocationPage != null)
        countedOpeningForm(checkNotNull(template), allocationPage) else null
    val sku = LocalRecord(LocalRecordReference(LocalRecordKind.SKU, skuId), names(detail.names, skuId),
        text("Select for current authorized carrying values", "اختر لقراءة القيم الدفترية الحالية المصرح بها"), listOf(
            recordValue(fieldLabel(LocalField.SKU_ID), literal(skuId.value)),
            recordValue(text("Current catalog SKU revision", "مراجعة صنف الكتالوج الحالية"), literal(detail.skuRevision.value.toString())),
            recordValue(fieldLabel(LocalField.UNIT), literal(detail.baseUnitId.value)),
            recordValue(fieldLabel(LocalField.QUANTITY_SCALE), literal(detail.quantityScale.toString())),
            recordValue(fieldLabel(LocalField.TRACKING), literal(detail.tracking.name)),
            recordValue(text("Expiry required by catalog", "تاريخ الصلاحية مطلوب بالكتالوج"), if (detail.requiresExpiry) text("Yes", "نعم") else text("No", "لا")),
            recordValue(fieldLabel(LocalField.EXPECTED_ITEM_STORAGE_REVISION), itemRevision?.let { literal(it.value.toString()) }
                ?: text("No stock item at this successful read; not a zero balance or permission", "لا سجل مخزون في هذه القراءة الناجحة؛ ليس رصيدًا صفريًا أو إذنًا"))))
    val reason = when {
        itemRevision != null -> text("This SKU already has stock storage. This first-entry opening editor is unavailable; do not initialize/reset stock to bypass it. Later opening remains required scope.",
            "لهذا الصنف تخزين مخزون قائم. محرر الافتتاحي لأول قيد غير متاح؛ لا تهيئ المخزون أو تصفره لتجاوزه. يبقى الافتتاحي اللاحق ضمن النطاق المطلوب.")
        !eligible -> text("The current catalog/unit/tracking or approval validity requirements are not met. No opening form is offered; no count or value is inferred.",
            "لم تُستوفَ متطلبات الكتالوج أو الوحدة أو التتبع أو صلاحية الاعتماد الحالية. لا يُعرض نموذج افتتاحي؛ لا تُستنتج كمية معدودة أو قيمة.")
        allocationPage == null -> text("The existing post command or current permission/source requirements are not met. No opening was posted.",
            "لم تُستوفَ متطلبات أمر الترحيل القائم أو الصلاحية أو المصدر الحالية. لم يُرحل افتتاحي.")
        else -> text("Count every one of this SKU's ${allocations.size} approved allocations across the pages. Enter all actual warehouse/lot/serial slices and exact owned values, or explicitly confirm an actual ZERO count for an allocation. Removing a slice never records zero. All positive slices must be saved together; more than 16 positive physical slices cannot be posted through the existing receiving service. Later opening and bulk/resumable import remain unfinished.",
            "عدّ جميع تخصيصات هذا الصنف المعتمدة وعددها ${allocations.size} عبر الصفحات. أدخل كل الأجزاء الفعلية حسب المخزن والتشغيلة والمسلسل وقيمها المملوكة الدقيقة، أو أكد صراحةً أن العد الفعلي للتخصيص صفر. حذف جزء لا يسجل صفرًا. يجب حفظ جميع الأجزاء ذات الكمية الموجبة معًا؛ لا يمكن ترحيل أكثر من 16 جزءًا فعليًا موجبًا عبر خدمة الاستلام الحالية. يبقى الافتتاحي اللاحق والاستيراد الكبير القابل للاستئناف غير مكتملين.")
    }
    return LocalWorkflowPage(query.workflow, listOf(openingBasisRecord(approval, row), sku) + allocationRecords,
        listOfNotNull(post), text("${reason.english} Ceilings are not remaining balances. This operation adds stock/value, not sales, revenue, supplier debt or drawer float. Source reads are separate current cuts; the master validates the saved original. ${readCut(basisCut.reply).english} ${readCut(quantities.reply).english}",
        "${reason.arabic} الحدود ليست أرصدة متبقية. يضيف هذا الإجراء المخزون والقيمة، لا المبيعات أو الإيراد أو دين المورد أو رصيد الدرج. قراءات المصادر مراجعات حالية منفصلة؛ يتحقق الجهاز الرئيسي من الأصل المحفوظ. ${readCut(basisCut.reply).arabic} ${readCut(quantities.reply).arabic}"),
        if (offset + visible.size < allocations.size) "opening-allocations:${offset + visible.size}" else null,
        openingAllocations = allocationPage.takeIf { query.openingAllocations != null })
}

private fun openingAllocationRecord(basisId: DomainId, revision: com.ukkera.pos.types.Revision,
    line: LocalApprovedStockLineV1): LocalRecord = LocalRecord(
    LocalRecordReference(LocalRecordKind.RECEIVING_ALLOCATION, line.allocationId, revision, parentId = basisId),
    text("Approved opening allocation — not a count", "تخصيص افتتاحي معتمد — ليس عدًا"), values = listOf(
        recordValue(fieldLabel(LocalField.WAREHOUSE_ID), literal(line.warehouseId.value)),
        recordValue(fieldLabel(LocalField.MAXIMUM_QUANTITY), quantityText(line.maximumBaseQuantity)),
        recordValue(fieldLabel(LocalField.MAXIMUM_OWNED_VALUE), moneyText(line.maximumOwnedValue)),
        recordValue(fieldLabel(LocalField.INSPECTION_REQUIRED), if (line.inspectionRequired) text("Yes", "نعم") else text("No", "لا"))),
    selectable = false)

private fun countedOpeningForm(template: LocalActionForm, source: LocalOpeningAllocationPage): LocalActionForm = template.copy(
    fields = template.fields.map { field -> if (field is LocalFormField.Lines) field.copy(
        label = text("Actual counted stock", "المخزون المعدود فعليًا"), minimumLines = 0, maximumLines = 64,
        initialLines = emptyList(), allowBlankLines = false) else field },
    explanation = text("Review every approved allocation across the pages. Count stock here to enter its actual location/lot/serial slices and owned values; confirm ZERO only after physically counting no stock for that allocation. Empty fields and removed rows are not zero counts. Split only for additional actual physical slices. Required inspection and pending serial registration keep stock quarantined. More than 16 positive slices cannot be posted; do not hide positive stock or use another receipt to finish this first entry. All-zero counts post nothing. Later opening and bulk import are not provided here.",
        "راجع كل تخصيص معتمد عبر الصفحات. اختر عد المخزون هنا لإدخال أجزائه الفعلية حسب الموقع والتشغيلة والمسلسل وقيمها المملوكة؛ أكد صفرًا فقط بعد العد الفعلي وعدم وجود مخزون لهذا التخصيص. الحقول الفارغة والسطور المحذوفة ليست عدًا صفريًا. قسّم فقط لأجزاء فعلية إضافية. يبقى المخزون المطلوب فحصه أو المعلق تسجيل مسلسله محجورًا. لا يمكن ترحيل أكثر من 16 جزءًا موجبًا؛ لا تخف مخزونًا موجبًا ولا تستخدم إيصالًا آخر لإكمال هذا القيد الأول. العد الصفري بالكامل لا يرحل شيئًا. لا يوفر هذا النموذج الافتتاحي اللاحق أو الاستيراد الكبير."),
    confirmation = text("I physically counted and reviewed every allocation for this SKU, all positive slices and exact owned values together. Every allocation without a positive slice was explicitly counted ZERO, not skipped or left unfinished. This is first opening stock, not a purchase, sale, supplier debt or drawer float. Save one original request and use that same request if its outcome is uncertain.",
        "عددت وراجعت فعليًا كل تخصيص لهذا الصنف وجميع أجزائه الموجبة وقيمها المملوكة الدقيقة معًا. كل تخصيص بلا جزء موجب عُدّ صفرًا صراحةً، ولم يُتجاوز أو يُترك غير مكتمل. هذا مخزون افتتاحي أول، لا شراء أو بيع أو دين مورد أو رصيد درج. احفظ طلبًا أصليًا واحدًا واستخدم الطلب نفسه إذا كانت نتيجته غير مؤكدة."),
    openingAllocations = source)

private suspend fun openingReceiptWorkflow(access: GatewayAccess, query: LocalWorkflowQuery, reference: LocalRecordReference): LocalWorkflowPage {
    val id = if (reference.kind == LocalRecordKind.RECEIPT_LINE) reference.parentId ?: invalid(LocalField.RECEIPT_ID)
        else reference.id.also { if (reference.parentId != null) invalid(LocalField.RECEIPT_ID) }
    val read = access.read(LocalStockCommands.receiptCosts(id))
    val receipt = LocalStockQueries.decodeReceipt(read.payload) ?: return LocalWorkflowPage(query.workflow, emptyList(), emptyList(),
        text("No posted opening document is available for that exact ID at this authorized value-read cut. Approval, stock and zero balances are not inferred.",
            "لا يوجد مستند افتتاحي مرحل متاح لهذا المعرف المحدد في مراجعة قراءة القيمة المصرح بها. لا يُستنتج اعتماد أو مخزون أو أرصدة صفرية."))
    if (receipt.receiptId != id || !receipt.costsIncluded || receipt.storageRevision.value <= 0 ||
        receipt.basisKind != LocalStockBasisKind.OPENING || receipt.supplierId != null ||
        receipt.lines.map { it.lineId }.distinct().size != receipt.lines.size || receipt.lines.map { it.sourceId }.distinct().size != receipt.lines.size ||
        receipt.lines.any { line -> line.disposition !in setOf(LocalReceiptDisposition.RELEASED, LocalReceiptDisposition.QUARANTINE) ||
            line.baseQuantity.scaled <= 0 || line.recordedQuantity.scaled <= 0 || line.originalValue == null ||
            line.releasedFromQuarantine.unitId != line.baseQuantity.unitId || line.releasedFromQuarantine.scale != line.baseQuantity.scale ||
            line.releasedFromQuarantine.scaled < 0 || line.releasedFromQuarantine > line.baseQuantity }) unavailable()
    val selected = if (reference.kind == LocalRecordKind.RECEIPT_LINE) {
        if (query.cursor != null) invalid()
        receipt.lines.singleOrNull { it.lineId == reference.id } ?: invalid(LocalField.LINE_ID)
    } else null
    val offset = if (selected == null) offsetCursor(query.cursor, "opening-receipt-lines", receipt.lines.size) else 0
    val lines = selected?.let(::listOf) ?: receipt.lines.drop(offset).take(16)
    val header = LocalRecord(LocalRecordReference(LocalRecordKind.RECEIPT, id), text("Posted counted opening stock", "مخزون افتتاحي معدود مرحل"),
        text("Recorded opening stock/value — not an approval", "مخزون وقيمة افتتاحيان مسجلان — ليس اعتمادًا"), listOf(
            recordValue(fieldLabel(LocalField.RECEIPT_ID), literal(id.value)),
            recordValue(fieldLabel(LocalField.EXPECTED_RECEIPT_REVISION), literal(receipt.storageRevision.value.toString())),
            recordValue(fieldLabel(LocalField.BASIS_ID), literal(receipt.basisId.value)),
            recordValue(fieldLabel(LocalField.EXPECTED_BASIS_REVISION), literal(receipt.basisRevision.value.toString())),
            recordValue(text("Original posting time at master", "وقت الترحيل الأصلي لدى الجهاز الرئيسي"), literal(receipt.receivedAt.canonicalString())),
            recordValue(text("Recorded by", "سُجل بواسطة"), literal(receipt.recordedBy.value)),
            recordValue(fieldLabel(LocalField.REASON), literal(receipt.reasonCode)),
            recordValue(text("Total original posted opening value — not current carrying value", "إجمالي القيمة الافتتاحية الأصلية المسجلة — ليس القيمة الدفترية الحالية"),
                moneyText(openingValueTotal(receipt.lines.map { checkNotNull(it.originalValue) })))), selectable = false)
    val basis = LocalRecord(LocalRecordReference(LocalRecordKind.RECEIVING_BASIS, receipt.basisId, receipt.basisRevision),
        text("Read the protected opening approval", "اقرأ الاعتماد الافتتاحي المحمي"))
    val values = lines.map { it.skuId }.distinct().map { sku -> LocalRecord(LocalRecordReference(LocalRecordKind.SKU, sku),
        text("Read current SKU carrying values", "اقرأ القيم الدفترية الحالية للصنف"), values = listOf(recordValue(fieldLabel(LocalField.SKU_ID), literal(sku.value)))) }
    return LocalWorkflowPage(query.workflow, listOf(header, basis) + lines.map { receiptLineRecord(id, it, costsIncluded = true, opening = true) } + values,
        emptyList(), text("Actual posted opening document and original values, not current availability or current average cost. This OPENING operation contributes no sale, collection, revenue, supplier payable or cash movement; branch sales/revenue totals were NOT requested and are not declared zero. Drawer float is separate. Select a SKU for the real current authorized value read. Quarantine and pending serial registration do not make stock sellable. ${readCut(read).english}",
            "مستند افتتاحي مرحل فعلي وقيمه الأصلية، لا الإتاحة أو متوسط التكلفة الحاليين. لا يساهم هذا الإجراء الافتتاحي في بيع أو تحصيل أو إيراد أو مستحق مورد أو حركة نقدية؛ لم تُطلب إجماليات المبيعات والإيراد للفرع ولا يُقال إنها صفر. رصيد الدرج منفصل. اختر صنفًا لقراءة قيمته الحالية الفعلية المصرح بها. الحجر وتسجيل المسلسل المعلق لا يجعلان المخزون قابلًا للبيع. ${readCut(read).arabic}"),
        if (selected == null && offset + lines.size < receipt.lines.size) "opening-receipt-lines:${offset + lines.size}" else null)
}

/** Checked display aggregation of actual approved/posted values, never a balance or authority. */
private fun openingValueTotal(values: List<Money>): Money {
    var minor = 0L
    for (value in values) {
        if (value.currency != Currency.EGP || value.minor < 0 || value.minor > Long.MAX_VALUE - minor) unavailable()
        minor += value.minor
    }
    return Money(Currency.EGP, minor)
}
