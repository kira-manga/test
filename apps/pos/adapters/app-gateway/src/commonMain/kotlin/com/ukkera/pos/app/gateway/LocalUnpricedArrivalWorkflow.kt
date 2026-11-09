package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.types.*

/** Real endpoint routes only. No cost-bearing catalog/PO read or valued-receipt fallback. */
internal val unpricedArrivalActions: Set<LocalAction> = setOf(LocalAction.ARRIVAL_RECORD, LocalAction.ARRIVAL_QUALIFY,
    LocalAction.ARRIVAL_REJECT, LocalAction.ARRIVAL_DISPATCH)
internal val unpricedArrivalKeys: Set<CommandKey> = setOf(LocalUnpricedArrivalProfileV1.RECORD, LocalUnpricedArrivalProfileV1.QUALIFY,
    LocalUnpricedArrivalProfileV1.REJECT, LocalUnpricedArrivalProfileV1.DISPATCH)
internal fun unpricedArrivalCommand(action: LocalAction): CommandKey = when (action) {
    LocalAction.ARRIVAL_RECORD -> LocalUnpricedArrivalProfileV1.RECORD
    LocalAction.ARRIVAL_QUALIFY -> LocalUnpricedArrivalProfileV1.QUALIFY
    LocalAction.ARRIVAL_REJECT -> LocalUnpricedArrivalProfileV1.REJECT
    LocalAction.ARRIVAL_DISPATCH -> LocalUnpricedArrivalProfileV1.DISPATCH
    else -> invalid()
}

internal fun arrivalNavigation(access: GatewayAccess, workflow: LocalWorkflow): List<LocalRecord> =
    if (workflow != LocalWorkflow.RECEIVING) emptyList() else listOf(
        LocalRecord(LocalRecordReference(LocalRecordKind.ARRIVAL_SOURCE, access.view.scope.branchId), actionLabel(LocalAction.ARRIVAL_RECORD),
            text("Separate unpriced custody; evidence-policy gate must be satisfied", "عهدة مستقلة غير مسعرة؛ يلزم استيفاء بوابة سياسة الإثبات")),
        LocalRecord(LocalRecordReference(LocalRecordKind.ARRIVAL_LIST, access.view.scope.branchId),
            text("Unpriced arrivals — physical history", "الوصول غير المسعر — السجل الفعلي")))

internal fun isArrivalRoute(query: LocalWorkflowQuery): Boolean = query.reference?.kind in setOf(LocalRecordKind.ARRIVAL,
    LocalRecordKind.ARRIVAL_SLICE, LocalRecordKind.ARRIVAL_SOURCE, LocalRecordKind.ARRIVAL_LIST) ||
    query.workflow == LocalWorkflow.RECEIVING && (query.reference?.kind in setOf(LocalRecordKind.SUPPLIER, LocalRecordKind.WAREHOUSE) ||
        query.reference == null && (query.search == "arrivals" || query.search.startsWith("arrival-new:") || query.search.startsWith("arrival-serial:")))

private val arrivalScopeNotice: LocalText get() = text(
    "Unpriced custody is nonsellable and financially unresolved, even after physical closure. It creates no owned value, invoice, payable or settlement. A documentary PO is UNALLOCATED, not reserved, fulfilled or billable. Required next LOCAL work: authoritative evidence policy and durable attachments, then genuine approved quick-purchase pricing, atomic adoption into the same stock, invoice/matching and separate settlement.",
    "العهدة غير المسعرة غير قابلة للبيع وغير محسومة ماليًا، حتى بعد الإغلاق الفعلي. لا تنشئ قيمة مملوكة أو فاتورة أو التزامًا أو تسوية. مرجع أمر الشراء توثيقي وغير مخصص، وليس حجزًا أو تنفيذًا أو استحقاق فوترة. العمل المحلي التالي لازم: سياسة إثبات معتمدة ومرفقات دائمة، ثم تسعير شراء سريع معتمد فعليًا وإدخال ذري في المخزون نفسه ومطابقة الفواتير وتسوية منفصلة.")
private val arrivalPolicyHeld: LocalText get() = text(
    "HELD — no authoritative effective local evidence policy is available. Absence is not optional evidence and does not mean every arrival needs a photo. No physical mutation form is offered; this workflow is incomplete until real configured policy and qualified durable attachments are connected.",
    "موقوف — لا تتوفر سياسة إثبات محلية معتمدة وسارية. غيابها لا يعني اختيارية الإثبات ولا أن كل وصول يحتاج صورة. لا يُعرض نموذج تغيير فعلي؛ سير العمل غير مكتمل حتى ربط سياسة فعلية مهيأة ومرفقات دائمة مؤهلة.")
private fun arrivalPolicyCurrent(policy: LocalArrivalPolicyViewV1?, at: UtcInstant): Boolean = policy != null && at >= policy.validFrom &&
    (policy.validUntil?.let { at < it } ?: true)
private fun arrivalNotice(policy: LocalArrivalPolicyViewV1?, read: ReadReply.Data): LocalText {
    val state = if (arrivalPolicyCurrent(policy, read.observedAt)) text(
        "An actual policy version is selected. Required attachments must already exist in the qualified durable local store; an observation reference is not a file or certificate.",
        "تم اختيار مراجعة سياسة فعلية. يجب أن توجد المرفقات المطلوبة مسبقًا في التخزين المحلي الدائم المؤهل؛ مرجع الملاحظة ليس ملفًا أو شهادة.") else arrivalPolicyHeld
    return text("${state.english} ${arrivalScopeNotice.english} ${readCut(read).english}",
        "${state.arabic} ${arrivalScopeNotice.arabic} ${readCut(read).arabic}")
}

private data class ArrivalSourceCut(val value: LocalArrivalSourcePageV1, val reply: ReadReply.Data)
private suspend fun arrivalSource(access: GatewayAccess, query: LocalArrivalSourceQueryV1): ArrivalSourceCut {
    val reply = access.read(LocalUnpricedArrivalCommandsV1.source(query))
    val page = LocalUnpricedArrivalCommandsV1.decodeSource(reply.payload)
    if (page.kind != query.kind) unavailable()
    if (page.availability == LocalArrivalAvailabilityV1.AVAILABLE) {
        val suppliers = page.suppliers ?: unavailable(); val items = page.items ?: unavailable()
        val warehouses = page.warehouses ?: unavailable(); val serials = page.serials ?: unavailable()
        val ids = when (query.kind) {
            LocalArrivalSourceKindV1.SUPPLIER -> { if (items.isNotEmpty() || warehouses.isNotEmpty() || serials.isNotEmpty()) unavailable(); suppliers.map { it.id } }
            LocalArrivalSourceKindV1.ITEM -> { if (suppliers.isNotEmpty() || warehouses.isNotEmpty() || serials.isNotEmpty()) unavailable(); items.map { it.selection.skuId } }
            LocalArrivalSourceKindV1.WAREHOUSE -> { if (suppliers.isNotEmpty() || items.isNotEmpty() || serials.isNotEmpty()) unavailable(); warehouses.map { it.selection.warehouseId } }
            LocalArrivalSourceKindV1.SERIAL_REGISTRATION -> { if (suppliers.isNotEmpty() || items.isNotEmpty() || warehouses.isNotEmpty()) unavailable(); serials.map { it.serialId } }
        }
        if (ids.size > (if (query.id == null) query.limit else 1) || ids.distinct().size != ids.size || ids != ids.sortedBy { it.value } ||
            query.id != null && (ids.any { it != query.id } || page.nextAfter != null) ||
            query.after?.let { after -> ids.any { it.value <= after.value } } == true || page.nextAfter != null && page.nextAfter != ids.lastOrNull()) unavailable()
        if (suppliers.any { it.storageRevision.value < 1 } || serials.any { it.registrationRevision.value < 1 }) unavailable()
    }
    return ArrivalSourceCut(page, reply)
}
private fun ArrivalSourceCut.requireAvailable(): LocalArrivalSourcePageV1 {
    if (value.availability != LocalArrivalAvailabilityV1.AVAILABLE) unavailable()
    return value
}
private fun arrivalCapacityPage(query: LocalWorkflowQuery, reply: ReadReply.Data): LocalWorkflowPage = LocalWorkflowPage(query.workflow,
    emptyList(), emptyList(), text(
        "Unpriced-arrival data cannot be fully hydrated at this cut. Quantities and revisions are UNAVAILABLE, not zero; no partial total or mutation form is shown. ${readCut(reply).english}",
        "لا يمكن تحميل بيانات الوصول غير المسعر كاملةً في هذه المراجعة. الكميات والمراجعات غير متاحة وليست صفرًا؛ لا يُعرض مجموع جزئي أو نموذج تغيير. ${readCut(reply).arabic}"))

internal suspend fun unpricedArrivalWorkflow(access: GatewayAccess, query: LocalWorkflowQuery): LocalWorkflowPage {
    val reference = query.reference
    if (reference?.kind in setOf(LocalRecordKind.ARRIVAL, LocalRecordKind.ARRIVAL_SLICE))
        return arrivalDetailWorkflow(access, query, checkNotNull(reference))
    if (reference?.kind == LocalRecordKind.ARRIVAL_LIST || reference == null && query.search == "arrivals") return arrivalListWorkflow(access, query)
    if (query.workflow != LocalWorkflow.RECEIVING) invalid()
    if (reference == null && query.search.startsWith("arrival-serial:")) {
        val id = searchedId(query.search.removePrefix("arrival-serial:"))
        val cut = arrivalSource(access, LocalArrivalSourceQueryV1(LocalArrivalSourceKindV1.SERIAL_REGISTRATION, id))
        if (cut.value.availability != LocalArrivalAvailabilityV1.AVAILABLE) return arrivalCapacityPage(query, cut.reply)
        return LocalWorkflowPage(query.workflow, cut.value.serials.orEmpty().map { serial -> LocalRecord(
            LocalRecordReference(LocalRecordKind.SKU, serial.skuId), text("Actual serial-registration source", "مصدر تسجيل الوحدة المسلسلة الفعلي"),
            values = listOf(recordValue(fieldLabel(LocalField.SERIAL_ID), literal(serial.serialId.value)),
                recordValue(fieldLabel(LocalField.SERIAL_CODE), literal(serial.serialCode)),
                recordValue(fieldLabel(LocalField.EXPECTED_SERIAL_REGISTRATION_REVISION), literal(serial.registrationRevision.value.toString())),
                recordValue(fieldLabel(LocalField.VALID_FROM), literal(serial.validFrom.canonicalString())),
                recordValue(fieldLabel(LocalField.VALID_UNTIL), serial.validUntil?.let { literal(it.canonicalString()) } ?: text("No end supplied", "لم يُحدد انتهاء"))), selectable = false)
        }, emptyList(), text("Only a real qualified source can supply a serial ID. No result means no registration authority; an exact observed code may remain unresolved custody. ${readCut(cut.reply).english}",
            "مصدر فعلي مؤهل وحده يوفر معرفًا مسلسلًا. غياب النتيجة يعني عدم توفر صلاحية تسجيل؛ يمكن حفظ الرمز المرصود الدقيق كعهدة غير مكتملة الهوية. ${readCut(cut.reply).arabic}"))
    }
    if (reference == null && query.search.startsWith("arrival-new:")) {
        val ids = query.search.removePrefix("arrival-new:").split('/')
        if (ids.size !in 2..3) invalid()
        return arrivalIntakeWorkflow(access, query, searchedId(ids[0]), searchedId(ids[1]), ids.getOrNull(2)?.let(::searchedId))
    }
    return when (reference?.kind) {
        LocalRecordKind.SUPPLIER -> arrivalWarehouseWorkflow(access, query, reference.id)
        LocalRecordKind.WAREHOUSE -> arrivalIntakeWorkflow(access, query, reference.parentId ?: invalid(LocalField.SUPPLIER_ID), reference.id)
        LocalRecordKind.ARRIVAL_SOURCE -> {
            if (reference.id != access.view.scope.branchId) invalid()
            val cut = arrivalSource(access, LocalArrivalSourceQueryV1(LocalArrivalSourceKindV1.SUPPLIER,
                after = parseCursor(query.cursor, "arrival-suppliers")))
            if (cut.value.availability != LocalArrivalAvailabilityV1.AVAILABLE) return arrivalCapacityPage(query, cut.reply)
            LocalWorkflowPage(query.workflow, cut.value.suppliers.orEmpty().map { supplier -> LocalRecord(
                LocalRecordReference(LocalRecordKind.SUPPLIER, supplier.id), arrivalNames(supplier.names),
                if (supplier.active) text("Known supplier", "مورد معروف") else text("Inactive — new intake blocked", "غير نشط — الإدخال الجديد موقوف"),
                listOf(recordValue(fieldLabel(LocalField.EXPECTED_SUPPLIER_STORAGE_REVISION), literal(supplier.storageRevision.value.toString()))))
            }, emptyList(), text("Select the actual supplier, then warehouse and counted SKU. No fictitious supplier/SKU is created. Exact source shortcut: arrival-new:SUPPLIER-ID/WAREHOUSE-ID[/SKU-ID]. ${arrivalScopeNotice.english}",
                "اختر المورد الفعلي ثم المخزن والصنف المعدود. لا يُنشأ مورد أو صنف وهمي. اختصار المصدر المحدد: arrival-new:SUPPLIER-ID/WAREHOUSE-ID[/SKU-ID]. ${arrivalScopeNotice.arabic}"),
                cursor("arrival-suppliers", cut.value.nextAfter))
        }
        else -> invalid()
    }
}

private suspend fun arrivalWarehouseWorkflow(access: GatewayAccess, query: LocalWorkflowQuery, supplierId: DomainId): LocalWorkflowPage {
    val source = arrivalSource(access, LocalArrivalSourceQueryV1(LocalArrivalSourceKindV1.SUPPLIER, supplierId))
    if (source.value.availability != LocalArrivalAvailabilityV1.AVAILABLE) return arrivalCapacityPage(query, source.reply)
    val supplier = source.value.suppliers?.singleOrNull() ?: return LocalWorkflowPage(query.workflow, emptyList(), emptyList(),
        text("Known supplier source is absent. No intake is possible.", "مصدر المورد المعروف غير موجود. لا يمكن إدخال الوصول."))
    val cut = arrivalSource(access, LocalArrivalSourceQueryV1(LocalArrivalSourceKindV1.WAREHOUSE,
        after = parseCursor(query.cursor, "arrival-warehouses.${supplierId.value}"), supplierId = supplierId))
    if (cut.value.availability != LocalArrivalAvailabilityV1.AVAILABLE) return arrivalCapacityPage(query, cut.reply)
    return LocalWorkflowPage(query.workflow, listOf(arrivalSupplierRecord(supplier)) + cut.value.warehouses.orEmpty().map { warehouse -> LocalRecord(
        LocalRecordReference(LocalRecordKind.WAREHOUSE, warehouse.selection.warehouseId, parentId = supplierId), arrivalNames(warehouse.names),
        if (warehouse.active) text("Actual branch warehouse", "مخزن الفرع الفعلي") else text("Inactive", "غير نشط"),
        listOf(recordValue(fieldLabel(LocalField.EXPECTED_WAREHOUSE_STORAGE_REVISION), literal(warehouse.selection.storageRevision.value.toString()))))
    }, emptyList(), arrivalNotice(cut.value.policy, cut.reply), cursor("arrival-warehouses.${supplierId.value}", cut.value.nextAfter))
}

private suspend fun arrivalIntakeWorkflow(access: GatewayAccess, query: LocalWorkflowQuery, supplierId: DomainId,
    warehouseId: DomainId, skuId: DomainId? = null): LocalWorkflowPage {
    val supplierCut = arrivalSource(access, LocalArrivalSourceQueryV1(LocalArrivalSourceKindV1.SUPPLIER, supplierId))
    if (supplierCut.value.availability != LocalArrivalAvailabilityV1.AVAILABLE) return arrivalCapacityPage(query, supplierCut.reply)
    val supplier = supplierCut.value.suppliers?.singleOrNull() ?: invalid(LocalField.SUPPLIER_ID)
    val warehouseCut = arrivalSource(access, LocalArrivalSourceQueryV1(LocalArrivalSourceKindV1.WAREHOUSE, warehouseId, supplierId = supplierId))
    if (warehouseCut.value.availability != LocalArrivalAvailabilityV1.AVAILABLE) return arrivalCapacityPage(query, warehouseCut.reply)
    val warehouse = warehouseCut.value.warehouses?.singleOrNull() ?: invalid(LocalField.WAREHOUSE_ID)
    val family = "arrival-items.${supplierId.value}.${warehouseId.value}"
    val cut = arrivalSource(access, LocalArrivalSourceQueryV1(LocalArrivalSourceKindV1.ITEM, skuId,
        after = if (skuId == null) parseCursor(query.cursor, family) else null, supplierId = supplierId))
    if (cut.value.availability != LocalArrivalAvailabilityV1.AVAILABLE) return arrivalCapacityPage(query, cut.reply)
    val items = cut.value.items.orEmpty()
    val eligible = items.filter { it.active && it.tracking != null }
    val policy = cut.value.policy
    val form = if (supplier.active && warehouse.active && eligible.isNotEmpty() && arrivalPolicyCurrent(policy, cut.reply.observedAt) &&
        access.installed.has(LocalUnpricedArrivalProfileV1.RECORD) && access.allow(LocalAction.ARRIVAL_RECORD))
        arrivalRecordForm(supplier, warehouse, eligible, checkNotNull(policy)) else null
    return LocalWorkflowPage(query.workflow, listOf(arrivalSupplierRecord(supplier), LocalRecord(
        LocalRecordReference(LocalRecordKind.WAREHOUSE, warehouseId, parentId = supplierId), arrivalNames(warehouse.names), values = listOf(
            recordValue(fieldLabel(LocalField.EXPECTED_WAREHOUSE_STORAGE_REVISION), literal(warehouse.selection.storageRevision.value.toString()))), selectable = false)) +
        items.map { item -> LocalRecord(LocalRecordReference(LocalRecordKind.SKU, item.selection.skuId, item.selection.skuRevision), arrivalNames(item.names),
            if (item.active && item.tracking != null) text("Known stocked SKU; valuation unresolved", "صنف مخزني معروف؛ التقييم غير محسوم")
            else text("Ineligible for this physical slice", "غير مؤهل لهذا الجزء الفعلي"), listOf(
                recordValue(fieldLabel(LocalField.SKU_CODE), literal(item.code)),
                recordValue(fieldLabel(LocalField.TRACKING), item.tracking?.let(::arrivalTrackingLabel) ?: text("Not stocked", "غير مخزني")),
                recordValue(fieldLabel(LocalField.UNIT), literal(item.selection.baseUnitId.value)),
                recordValue(fieldLabel(LocalField.QUANTITY_SCALE), literal(item.selection.quantityScale.toString())),
                recordValue(fieldLabel(LocalField.REQUIRES_EXPIRY), arrivalYesNo(item.requiresExpiry)),
                recordValue(text("Purchasable (not an approval)", "قابل للشراء (ليس اعتمادًا)"), arrivalYesNo(item.canPurchase))), selectable = false)
        }, listOfNotNull(form), arrivalNotice(policy, cut.reply), cursor(family, cut.value.nextAfter))
}

private fun arrivalNames(names: LocalArrivalNamesV1): LocalText = text(names.english, names.arabic)
private fun arrivalYesNo(value: Boolean): LocalText = if (value) text("Yes", "نعم") else text("No", "لا")
private fun arrivalSupplierRecord(supplier: LocalArrivalSupplierViewV1): LocalRecord = LocalRecord(LocalRecordReference(LocalRecordKind.SUPPLIER, supplier.id),
    arrivalNames(supplier.names), values = listOf(recordValue(fieldLabel(LocalField.EXPECTED_SUPPLIER_STORAGE_REVISION), literal(supplier.storageRevision.value.toString())),
        recordValue(fieldLabel(LocalField.ACTIVE), arrivalYesNo(supplier.active))), selectable = false)
private fun arrivalTrackingLabel(value: LocalArrivalTrackingV1): LocalText = when (value) {
    LocalArrivalTrackingV1.QUANTITY -> text("Quantity", "كمية")
    LocalArrivalTrackingV1.LOT -> text("Lot", "تشغيلة")
    LocalArrivalTrackingV1.SERIAL -> text("Individual serial unit", "وحدة مسلسلة منفردة")
}
private fun arrivalDisposition(value: LocalArrivalDispositionV1): LocalText = when (value) {
    LocalArrivalDispositionV1.UNINSPECTED_HELD -> text("Held — inspection unresolved", "محتفظ به — الفحص غير محسوم")
    LocalArrivalDispositionV1.PASSED_HELD -> text("Inspection passed — still unpriced held", "اجتاز الفحص — ما زال محتفظًا به وغير مسعر")
    LocalArrivalDispositionV1.REJECTED_HELD -> text("Rejected — still physically held", "مرفوض — ما زال في العهدة فعليًا")
    LocalArrivalDispositionV1.REFUSED -> text("Refused at door — not held", "مرفوض عند الباب — ليس في العهدة")
    LocalArrivalDispositionV1.DISPATCHED -> text("Physically dispatched — financial consequence unresolved", "أُرسل فعليًا — الأثر المالي غير محسوم")
}
private fun arrivalOwnership(value: LocalArrivalOwnershipV1): LocalText = when (value) {
    LocalArrivalOwnershipV1.UNRESOLVED -> text("Ownership unresolved", "الملكية غير محسومة")
    LocalArrivalOwnershipV1.SUPPLIER_ATTESTED -> text("Supplier ownership attested; not commercial settlement", "إفادة بملكية المورد؛ ليست تسوية تجارية")
    LocalArrivalOwnershipV1.COMPANY_ATTESTED -> text("Company ownership attested; no approved value", "إفادة بملكية الشركة؛ بلا قيمة معتمدة")
}

private fun arrivalEvidenceFields(policy: LocalArrivalPolicyViewV1, action: LocalArrivalActionV1): List<LocalFormField> = listOf(
    idField(LocalField.POLICY_ID, policy.policyId), revisionField(LocalField.EXPECTED_EVIDENCE_POLICY_REVISION, policy.revision),
    idField(LocalField.ATTACHMENT_ID, required = action in policy.attachmentRequiredFor, readOnly = false).copy(help = text(
        "An already durable qualified local attachment, not an arbitrary evidence ID. No upload is performed by this form.",
        "مرفق محلي مؤهل محفوظ دائمًا مسبقًا، وليس معرف إثبات عشوائيًا. لا يرفع هذا النموذج ملفًا.")),
    scalar(LocalField.EXPECTED_ATTACHMENT_STORAGE_REVISION, LocalInputKind.INTEGER, required = action in policy.attachmentRequiredFor, maximum = 19),
    scalar(LocalField.OBSERVATION_REFERENCE, LocalInputKind.TEXT, maximum = 160, help = text(
        "Actual physical observation reference; this text is NOT a durable attachment.", "مرجع الملاحظة الفعلية؛ هذا النص ليس مرفقًا دائمًا.")))

private fun arrivalRecordForm(supplier: LocalArrivalSupplierViewV1, warehouse: LocalArrivalWarehouseViewV1,
    items: List<LocalArrivalItemViewV1>, policy: LocalArrivalPolicyViewV1): LocalActionForm {
    val choices = items.map { LocalReferenceChoice(LocalRecordReference(LocalRecordKind.SKU, it.selection.skuId, it.selection.skuRevision), arrivalNames(it.names)) }
    val initial = items.map { item ->
        val selected = item.selection
        LocalInputLine(DomainId.random(), buildList {
            add(LocalScalarInput(LocalField.SOURCE_ID, LocalScalarValue.Identifier(DomainId.random())))
            add(LocalScalarInput(LocalField.CUSTODY_UNIT_ID, LocalScalarValue.Identifier(DomainId.random())))
            add(LocalScalarInput(LocalField.SKU_ID, LocalScalarValue.Reference(choices.single { it.reference.id == selected.skuId }.reference)))
            add(LocalScalarInput(LocalField.PRODUCT_ID, LocalScalarValue.Identifier(selected.productId)))
            add(LocalScalarInput(LocalField.UNIT, LocalScalarValue.Identifier(selected.baseUnitId)))
            add(LocalScalarInput(LocalField.QUANTITY_SCALE, LocalScalarValue.Integer(selected.quantityScale.toLong())))
            add(LocalScalarInput(LocalField.EXPECTED_CATALOG_REVISION, LocalScalarValue.Integer(selected.catalogRevision.value)))
            add(LocalScalarInput(LocalField.EXPECTED_CATALOG_STORAGE_REVISION, LocalScalarValue.Integer(selected.storageRevision.value)))
            selected.stockStorageRevision?.let { add(LocalScalarInput(LocalField.EXPECTED_ITEM_STORAGE_REVISION, LocalScalarValue.Integer(it.value))) }
            add(LocalScalarInput(LocalField.TRACKING, LocalScalarValue.Choice(checkNotNull(item.tracking).name)))
            add(LocalScalarInput(LocalField.REQUIRES_EXPIRY, LocalScalarValue.Flag(item.requiresExpiry)))
        })
    }
    val columns = listOf(
        idField(LocalField.SOURCE_ID, readOnly = true), idField(LocalField.CUSTODY_UNIT_ID, readOnly = true),
        scalar(LocalField.SKU_ID, LocalInputKind.REFERENCE, readOnly = true).copy(references = choices),
        idField(LocalField.PRODUCT_ID, readOnly = true), idField(LocalField.UNIT, readOnly = true),
        scalar(LocalField.QUANTITY_SCALE, LocalInputKind.INTEGER, readOnly = true, maximum = 1),
        scalar(LocalField.EXPECTED_CATALOG_REVISION, LocalInputKind.INTEGER, readOnly = true, maximum = 19),
        scalar(LocalField.EXPECTED_CATALOG_STORAGE_REVISION, LocalInputKind.INTEGER, readOnly = true, maximum = 19),
        scalar(LocalField.EXPECTED_ITEM_STORAGE_REVISION, LocalInputKind.INTEGER, required = false, readOnly = true, maximum = 19,
            help = text("Blank means verified absent owned-stock storage, not zero stock and not a request to create valued stock.",
                "الفراغ يعني غياب تخزين المخزون المملوك المتحقق منه، لا رصيدًا صفريًا ولا طلب إنشاء مخزون مقيم.")),
        choiceField(LocalField.TRACKING, LocalArrivalTrackingV1.entries.map { LocalChoice(it.name, arrivalTrackingLabel(it)) }).copy(readOnly = true),
        scalar(LocalField.REQUIRES_EXPIRY, LocalInputKind.CHECKBOX, required = false, readOnly = true),
        exactQuantityField(LocalField.QUANTITY), idField(LocalField.LOT_ID, required = false), instantField(LocalField.EXPIRY_AT, required = false),
        idField(LocalField.SERIAL_ID, required = false).copy(help = text("Only an actual qualified registration may supply this ID. Otherwise leave blank; the local custody-unit ID is not registration.",
            "مصدر تسجيل فعلي مؤهل وحده يوفر هذا المعرف. وإلا اتركه فارغًا؛ معرف وحدة العهدة المحلي ليس تسجيلًا.")),
        scalar(LocalField.SERIAL_CODE, LocalInputKind.TEXT, required = false, maximum = 128),
        scalar(LocalField.EXPECTED_SERIAL_REGISTRATION_REVISION, LocalInputKind.INTEGER, required = false, maximum = 19),
        choiceField(LocalField.DISPOSITION, LocalArrivalDispositionV1.entries.filter { it != LocalArrivalDispositionV1.DISPATCHED }.map { LocalChoice(it.name, arrivalDisposition(it)) }),
        choiceField(LocalField.OWNERSHIP, LocalArrivalOwnershipV1.entries.map { LocalChoice(it.name, arrivalOwnership(it)) }, LocalArrivalOwnershipV1.UNRESOLVED.name),
        scalar(LocalField.OWNERSHIP_REFERENCE, LocalInputKind.TEXT, required = false, maximum = 160))
    return form(LocalAction.ARRIVAL_RECORD, listOf(newIdField(LocalField.ARRIVAL_ID), idField(LocalField.SUPPLIER_ID, supplier.id),
        revisionField(LocalField.EXPECTED_SUPPLIER_STORAGE_REVISION, supplier.storageRevision), idField(LocalField.WAREHOUSE_ID, warehouse.selection.warehouseId),
        revisionField(LocalField.EXPECTED_WAREHOUSE_STORAGE_REVISION, warehouse.selection.storageRevision),
        scalar(LocalField.SUPPLIER_DELIVERY_REFERENCE, LocalInputKind.TEXT, required = false, maximum = 96),
        idField(LocalField.LOCAL_INTAKE_TAG, required = false).copy(help = text("Choose exactly one: supplier delivery reference OR the stable tag on this local intake. Changing a tag is not a retry.",
            "اختر واحدًا فقط: مرجع تسليم المورد أو الوسم الثابت لهذا الإدخال المحلي. تغيير الوسم ليس إعادة محاولة.")),
        idField(LocalField.DOCUMENTARY_PO_ID, required = false), scalar(LocalField.DISCREPANCY_REFERENCE, LocalInputKind.TEXT, required = false, maximum = 160)) +
        arrivalEvidenceFields(policy, LocalArrivalActionV1.RECORD) + stockReasonField() + LocalFormField.Lines(LocalField.LINES, fieldLabel(LocalField.LINES),
            columns, maximumLines = 16, initialLines = initial, allowBlankLines = false, allowDuplicateLines = true),
        text("Keep only actually observed rows and count BASE units. Each serial is one whole unit. Missing lot/expiry/serial facts remain explicitly unresolved; no sellable release or zero value is created. Wrong supplied known SKUs need the discrepancy reference, not a renamed ordered item. Duplicating a row creates new line/source/custody identities and clears quantity and serial identity/code/registration; review quantities yourself, with no automatic subtraction. Use arrival-serial:ID to inspect real registration. Supplier delivery references are separate from invoices. Save, submit and look up the SAME immutable operation.",
            "احتفظ فقط بالأسطر المرصودة فعليًا وعدّ الوحدات الأساسية. كل مسلسل وحدة كاملة. تبقى بيانات التشغيلة والصلاحية والمسلسل الناقصة غير محسومة صراحةً؛ لا تنشأ إتاحة للبيع أو قيمة صفرية. الصنف المعروف المورد بالخطأ يحتاج مرجع فرق، لا إعادة تسمية الصنف المطلوب. نسخ السطر يولد هويات سطر ومصدر وعهدة جديدة ويمسح الكمية ومعرف المسلسل ورمزه وتسجيله؛ راجع الكميات بنفسك دون طرح تلقائي. استخدم arrival-serial:ID لفحص التسجيل الفعلي. مرجع تسليم المورد منفصل عن الفاتورة. احفظ وأرسل واستعلم عن العملية الثابتة نفسها.")).copy(confirmation = text(
                "I reviewed actual custody, exact source selections and evidence. This does not approve value, release sellable stock or pay a supplier.",
                "راجعت العهدة الفعلية واختيارات المصدر الدقيقة والإثبات. لا يعتمد هذا قيمة ولا يتيح مخزونًا للبيع ولا يدفع موردًا."))
}

private data class ArrivalDetailCut(val value: LocalArrivalPageV1, val reply: ReadReply.Data)
private suspend fun arrivalRead(access: GatewayAccess, query: LocalArrivalQueryV1): ArrivalDetailCut {
    val reply = access.read(LocalUnpricedArrivalCommandsV1.read(query))
    val page = LocalUnpricedArrivalCommandsV1.decodePage(reply.payload)
    if (page.availability == LocalArrivalAvailabilityV1.AVAILABLE) {
        val headers = page.headers ?: unavailable()
        if (headers.size > (if (query.id == null) query.limit else 1) || headers.map { it.arrivalId }.distinct().size != headers.size ||
            headers != headers.sortedBy { it.arrivalId.value } || query.after?.let { after -> headers.any { it.arrivalId.value <= after.value } } == true ||
            query.id != null && (headers.any { it.arrivalId != query.id } || page.nextAfter != null) ||
            page.nextAfter != null && page.nextAfter != headers.lastOrNull()?.arrivalId || headers.any { it.arrivalRevision.value < 1 || it.heldSlices !in 0..16 }) unavailable()
        val detail = page.detail
        if (query.id == null && detail != null || query.id != null && (detail == null) != headers.isEmpty()) unavailable()
        if (detail != null) {
            if (detail.header != headers.single() || detail.slices.size !in 1..16 || detail.events.size > 64 ||
                detail.header.arrivalRevision.value != detail.events.size.toLong() + 1 ||
                detail.slices.map { it.custodyUnitId }.distinct().size != detail.slices.size || detail.slices.map { it.sourceId }.distinct().size != detail.slices.size ||
                detail.events.map { it.eventId }.distinct().size != detail.events.size || detail.slices.any { it.quantity.scaled <= 0 } ||
                detail.header.heldSlices != detail.slices.count { arrivalHeldUi(it.disposition) } ||
                detail.header.physicallyClosed != detail.slices.none { arrivalHeldUi(it.disposition) } ||
                (detail.policy != null) != (detail.policyStatus == LocalArrivalPolicyStatusV1.AVAILABLE)) unavailable()
        }
    }
    return ArrivalDetailCut(page, reply)
}
private fun arrivalHeldUi(disposition: LocalArrivalDispositionV1): Boolean = disposition in setOf(LocalArrivalDispositionV1.UNINSPECTED_HELD,
    LocalArrivalDispositionV1.PASSED_HELD, LocalArrivalDispositionV1.REJECTED_HELD)
private suspend fun arrivalListWorkflow(access: GatewayAccess, query: LocalWorkflowQuery): LocalWorkflowPage {
    val cut = arrivalRead(access, LocalArrivalQueryV1(after = parseCursor(query.cursor, "arrivals")))
    if (cut.value.availability != LocalArrivalAvailabilityV1.AVAILABLE) return arrivalCapacityPage(query, cut.reply)
    return LocalWorkflowPage(query.workflow, cut.value.headers.orEmpty().map(::arrivalHeaderRecord), emptyList(), text(
        "Bounded arrival header page, not a branch stock/balance report. Select a header for its complete retained physical partitions, or use arrival:ID. ${arrivalScopeNotice.english} ${readCut(cut.reply).english}",
        "صفحة رؤوس وصول محدودة، وليست تقرير مخزون أو أرصدة الفرع. اختر رأسًا لعرض تقسيماته الفعلية المحفوظة كاملةً، أو استخدم arrival:ID. ${arrivalScopeNotice.arabic} ${readCut(cut.reply).arabic}"), cursor("arrivals", cut.value.nextAfter))
}
private fun arrivalHeaderRecord(header: LocalArrivalHeaderV1): LocalRecord = LocalRecord(
    LocalRecordReference(LocalRecordKind.ARRIVAL, header.arrivalId, header.arrivalRevision), text("Unpriced physical arrival", "وصول فعلي غير مسعر"),
    if (header.physicallyClosed) text("Physically closed; NOT commercially/financially settled", "مغلق فعليًا؛ غير مسوى تجاريًا أو ماليًا")
    else text("Held; unpriced, nonsellable and financially unresolved", "في العهدة؛ غير مسعر وغير قابل للبيع وغير محسوم ماليًا"), buildList {
        add(recordValue(fieldLabel(LocalField.SUPPLIER_ID), literal(header.supplierId.value)))
        add(recordValue(fieldLabel(LocalField.EXPECTED_ARRIVAL_REVISION), literal(header.arrivalRevision.value.toString())))
        add(recordValue(text("Recorded at master", "وقت التسجيل لدى الجهاز الرئيسي"), literal(header.recordedAt.canonicalString())))
        add(recordValue(text("Currently held slices (not units)", "الأجزاء الحالية في العهدة (ليست عدد الوحدات)"), literal(header.heldSlices.toString())))
        header.supplierDeliveryReference?.let { add(recordValue(fieldLabel(LocalField.SUPPLIER_DELIVERY_REFERENCE), literal(it))) }
        header.localIntakeTag?.let { add(recordValue(fieldLabel(LocalField.LOCAL_INTAKE_TAG), literal(it.value))) }
        header.documentaryPoId?.let { add(recordValue(fieldLabel(LocalField.DOCUMENTARY_PO_ID), literal(it.value))) }
    })
private suspend fun arrivalDetailWorkflow(access: GatewayAccess, query: LocalWorkflowQuery, reference: LocalRecordReference): LocalWorkflowPage {
    val id = if (reference.kind == LocalRecordKind.ARRIVAL_SLICE) reference.parentId ?: invalid(LocalField.ARRIVAL_ID) else reference.id
    val cut = arrivalRead(access, LocalArrivalQueryV1(id))
    if (cut.value.availability != LocalArrivalAvailabilityV1.AVAILABLE) return arrivalCapacityPage(query, cut.reply)
    val detail = cut.value.detail ?: return LocalWorkflowPage(query.workflow, emptyList(), emptyList(),
        text("No arrival is present at this authorized read cut. No quantities, ownership or financial settlement are inferred.",
            "لا يوجد وصول في مراجعة القراءة المصرح بها. لا تُستنتج كميات أو ملكية أو تسوية مالية."))
    val selected = if (reference.kind == LocalRecordKind.ARRIVAL_SLICE) detail.slices.singleOrNull { it.custodyUnitId == reference.id }
        ?: invalid(LocalField.CUSTODY_UNIT_ID) else null
    val records = listOf(arrivalHeaderRecord(detail.header)) + detail.slices.map { slice -> LocalRecord(
        LocalRecordReference(LocalRecordKind.ARRIVAL_SLICE, slice.custodyUnitId, parentId = id), text("Retained physical partition", "جزء فعلي محفوظ"),
        arrivalDisposition(slice.disposition), buildList {
            add(recordValue(fieldLabel(LocalField.QUANTITY), quantityText(slice.quantity)))
            add(recordValue(fieldLabel(LocalField.SKU_ID), literal(slice.skuId.value)))
            add(recordValue(fieldLabel(LocalField.WAREHOUSE_ID), literal(slice.warehouseId.value)))
            add(recordValue(fieldLabel(LocalField.LINE_ID), literal(slice.lineId.value)))
            add(recordValue(fieldLabel(LocalField.SOURCE_ID), literal(slice.sourceId.value)))
            add(recordValue(text("Original physical source", "المصدر الفعلي الأصلي"), literal(slice.rootSourceId.value)))
            add(recordValue(text("Original custody-unit tag", "وسم وحدة العهدة الأصلي"), literal(slice.originalCustodyUnitId.value)))
            slice.parentCustodyUnitId?.let { add(recordValue(text("Parent partition", "الجزء الأصل"), literal(it.value))) }
            add(recordValue(fieldLabel(LocalField.OWNERSHIP), arrivalOwnership(slice.ownership)))
            add(recordValue(fieldLabel(LocalField.TRACKING), arrivalTrackingLabel(slice.tracking)))
            add(recordValue(fieldLabel(LocalField.LOT_ID), slice.lotId?.let { literal(it.value) } ?: text("Unobserved / not applicable", "غير مرصود / لا ينطبق")))
            add(recordValue(fieldLabel(LocalField.EXPIRY_AT), slice.expiresAt?.let { literal(it.canonicalString()) }
                ?: if (slice.requiresExpiry) text("Required for later release; UNRESOLVED", "لازم للإتاحة اللاحقة؛ غير محسوم") else text("Unobserved / not required by this SKU snapshot", "غير مرصود / لا تطلبه لقطة الصنف هذه")))
            if (slice.tracking == LocalArrivalTrackingV1.SERIAL) {
                add(recordValue(fieldLabel(LocalField.SERIAL_CODE), slice.serialCode?.let(::literal) ?: text("Identity code UNRESOLVED", "الرمز التعريفي غير محسوم")))
                add(recordValue(fieldLabel(LocalField.SERIAL_ID), slice.serialId?.let { literal(it.value) } ?: text("No qualified identity; custody tag is not registration", "لا يوجد معرف مؤهل؛ وسم العهدة ليس تسجيلًا")))
                slice.serialRegistrationRevision?.let { add(recordValue(text("Retained registration revision (not current release authority)", "مراجعة التسجيل المحفوظة (ليست صلاحية إتاحة حالية)"), literal(it.value.toString()))) }
            }
        }) } + detail.events.map { event -> LocalRecord(LocalRecordReference(LocalRecordKind.ARRIVAL_EVENT, event.eventId, parentId = id),
            text("Retained custody observation/departure", "ملاحظة أو مغادرة عهدة محفوظة"), actionLabel(when (event.action) {
                LocalArrivalActionV1.RECORD -> LocalAction.ARRIVAL_RECORD
                LocalArrivalActionV1.QUALIFY -> LocalAction.ARRIVAL_QUALIFY
                LocalArrivalActionV1.REJECT -> LocalAction.ARRIVAL_REJECT
                LocalArrivalActionV1.DISPATCH -> LocalAction.ARRIVAL_DISPATCH
            }), listOf(
                recordValue(text("Source custody unit", "وحدة العهدة المصدر"), literal(event.sourceCustodyUnitId.value)),
                recordValue(text("Affected custody unit", "وحدة العهدة المتأثرة"), literal(event.affectedCustodyUnitId.value)),
                recordValue(fieldLabel(LocalField.QUANTITY), quantityText(event.quantity)),
                recordValue(text("Recorded at master", "وقت التسجيل لدى الجهاز الرئيسي"), literal(event.recordedAt.canonicalString()))), selectable = false) }
    val actions = if (selected == null || !arrivalHeldUi(selected.disposition)) emptyList() else buildList {
        add(LocalAction.ARRIVAL_QUALIFY)
        if (selected.disposition != LocalArrivalDispositionV1.REJECTED_HELD) add(LocalAction.ARRIVAL_REJECT)
        if (selected.disposition == LocalArrivalDispositionV1.REJECTED_HELD) add(LocalAction.ARRIVAL_DISPATCH)
    }
    val forms = if (selected != null && arrivalPolicyCurrent(detail.policy, cut.reply.observedAt)) actions.filter {
        it.canAppearIn(query.workflow) && access.installed.has(unpricedArrivalCommand(it)) && access.allow(it, id)
    }.map { arrivalTransitionForm(it, detail.header, selected, checkNotNull(detail.policy)) } else emptyList()
    return LocalWorkflowPage(query.workflow, records, forms, arrivalNotice(detail.policy, cut.reply))
}

private fun arrivalTransitionForm(action: LocalAction, header: LocalArrivalHeaderV1, slice: LocalArrivalSliceViewV1,
    policy: LocalArrivalPolicyViewV1): LocalActionForm {
    val physicalAction = when (action) {
        LocalAction.ARRIVAL_QUALIFY -> LocalArrivalActionV1.QUALIFY
        LocalAction.ARRIVAL_REJECT -> LocalArrivalActionV1.REJECT
        LocalAction.ARRIVAL_DISPATCH -> LocalArrivalActionV1.DISPATCH
        else -> invalid()
    }
    val fields = listOf(newIdField(LocalField.ARRIVAL_EVENT_ID), idField(LocalField.ARRIVAL_ID, header.arrivalId),
        revisionField(LocalField.EXPECTED_ARRIVAL_REVISION, header.arrivalRevision), idField(LocalField.CUSTODY_UNIT_ID, slice.custodyUnitId)) +
        quantityMetadata(slice.quantity) + exactQuantityField(LocalField.QUANTITY) +
        (if (slice.tracking == LocalArrivalTrackingV1.SERIAL) emptyList() else listOf(newIdField(LocalField.SPLIT_SOURCE_ID), newIdField(LocalField.SPLIT_CUSTODY_UNIT_ID))) +
        arrivalEvidenceFields(policy, physicalAction) + stockReasonField() + when (action) {
            LocalAction.ARRIVAL_QUALIFY -> (if (slice.tracking == LocalArrivalTrackingV1.SERIAL) listOf(
                idField(LocalField.SERIAL_ID, slice.serialId, required = false, readOnly = slice.serialId != null),
                scalar(LocalField.SERIAL_CODE, LocalInputKind.TEXT, slice.serialCode?.let(LocalScalarValue::Text), required = false,
                    readOnly = slice.serialCode != null, maximum = 128),
                scalar(LocalField.EXPECTED_SERIAL_REGISTRATION_REVISION, LocalInputKind.INTEGER, slice.serialRegistrationRevision?.let { LocalScalarValue.Integer(it.value) },
                    required = false, readOnly = slice.serialId != null, maximum = 19)) else listOf(
                idField(LocalField.LOT_ID, slice.lotId, required = false, readOnly = slice.lotId != null),
                instantField(LocalField.EXPIRY_AT, slice.expiresAt, required = false).copy(readOnly = slice.expiresAt != null))) +
                scalar(LocalField.INSPECTION_PASSED, LocalInputKind.CHECKBOX, LocalScalarValue.Flag(false), required = false)
            LocalAction.ARRIVAL_DISPATCH -> listOf(idField(LocalField.RECIPIENT_SUPPLIER_ID, header.supplierId))
            else -> emptyList()
        }
    return form(action, fields, text(
        "Select an exact remaining quantity at the displayed revision. A strict partial quantity uses the reserved NEW source/unit IDs and leaves the remainder on its original IDs; a whole quantity keeps its identity. Serial units cannot split. Known facts cannot be changed. Qualification/inspection does not release or value stock. Dispatch records only actual departure of rejected held custody to the original supplier; no credit, loss amount, payment or settlement is implied.",
        "حدد كمية متبقية دقيقة في المراجعة المعروضة. الكمية الجزئية تستخدم معرفي المصدر والوحدة الجديدين المحجوزين وتبقي الباقي على معرفاته الأصلية؛ الكمية الكاملة تحتفظ بهويتها. لا تنقسم الوحدة المسلسلة. لا يمكن تغيير الحقائق المعروفة. التأهيل أو الفحص لا يتيح المخزون ولا يقيمه. الإرسال يسجل فقط المغادرة الفعلية للعهدة المرفوضة المحتفظ بها إلى المورد الأصلي؛ دون افتراض إشعار دائن أو مبلغ خسارة أو دفع أو تسوية.")).copy(confirmation = text(
            "I attest this actual physical observation/departure and exact quantity. Commercial and financial responsibility remains unresolved.",
            "أشهد بهذه الملاحظة أو المغادرة الفعلية والكمية الدقيقة. تبقى المسؤولية التجارية والمالية غير محسومة."))
}

internal suspend fun prepareUnpricedArrival(access: GatewayAccess, intent: LocalCommandIntent): MutationRequest {
    if (intent.action !in unpricedArrivalActions || !access.installed.has(unpricedArrivalCommand(intent.action)))
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    return if (intent.action == LocalAction.ARRIVAL_RECORD) prepareArrivalRecord(access, intent) else prepareArrivalTransition(access, intent)
}
private suspend fun prepareArrivalRecord(access: GatewayAccess, intent: LocalCommandIntent): MutationRequest {
    if (intent.lineField != LocalField.LINES || intent.lines.size !in 1..16) invalid(LocalField.LINES)
    val input = InputValues(intent.values)
    val id = input.id(LocalField.ARRIVAL_ID)
    access.requireAction(intent.action, id)
    val supplierId = input.id(LocalField.SUPPLIER_ID)
    val supplierRevision = input.revision(LocalField.EXPECTED_SUPPLIER_STORAGE_REVISION)
    val supplierCut = arrivalSource(access, LocalArrivalSourceQueryV1(LocalArrivalSourceKindV1.SUPPLIER, supplierId))
    val supplier = supplierCut.requireAvailable().suppliers?.singleOrNull() ?: invalid(LocalField.SUPPLIER_ID)
    if (!supplier.active || supplier.storageRevision != supplierRevision) invalid(LocalField.EXPECTED_SUPPLIER_STORAGE_REVISION)
    val warehouseId = input.id(LocalField.WAREHOUSE_ID)
    val warehouseRevision = input.revision(LocalField.EXPECTED_WAREHOUSE_STORAGE_REVISION)
    val warehouseCut = arrivalSource(access, LocalArrivalSourceQueryV1(LocalArrivalSourceKindV1.WAREHOUSE, warehouseId, supplierId = supplierId))
    val warehouse = warehouseCut.requireAvailable().warehouses?.singleOrNull() ?: invalid(LocalField.WAREHOUSE_ID)
    if (!warehouse.active || warehouse.selection.storageRevision != warehouseRevision) invalid(LocalField.EXPECTED_WAREHOUSE_STORAGE_REVISION)
    val evidence = arrivalEvidence(input, warehouseCut.value.policy, warehouseCut.reply.observedAt, LocalArrivalActionV1.RECORD)
    val items = linkedMapOf<DomainId, LocalArrivalItemSelectionV1>()
    val current = linkedMapOf<DomainId, LocalArrivalItemViewV1>()
    val lines = intent.lines.map { row ->
        val line = InputValues(row.values)
        val sku = line.reference(LocalField.SKU_ID, LocalRecordKind.SKU)
        if (sku.parentId != null) invalid(LocalField.SKU_ID)
        val actual = current[sku.id] ?: arrivalSource(access, LocalArrivalSourceQueryV1(LocalArrivalSourceKindV1.ITEM, sku.id, supplierId = supplierId))
            .requireAvailable().items?.singleOrNull()?.also { current[sku.id] = it } ?: invalid(LocalField.SKU_ID)
        if (!actual.active) invalid(LocalField.SKU_ID)
        val tracking = actual.tracking ?: invalid(LocalField.TRACKING)
        val scale = line.integer(LocalField.QUANTITY_SCALE)
        if (scale !in 0L..6L) invalid(LocalField.QUANTITY_SCALE)
        val expected = LocalArrivalItemSelectionV1(sku.id, line.id(LocalField.PRODUCT_ID), line.id(LocalField.UNIT),
            scale.toInt(),
            line.revision(LocalField.EXPECTED_CATALOG_REVISION), sku.revision ?: invalid(LocalField.SKU_ID),
            line.revision(LocalField.EXPECTED_CATALOG_STORAGE_REVISION), line.optionalRevision(LocalField.EXPECTED_ITEM_STORAGE_REVISION))
        if (expected != actual.selection || line.choice(LocalField.TRACKING) != tracking.name ||
            line.flag(LocalField.REQUIRES_EXPIRY) != actual.requiresExpiry) invalid(LocalField.EXPECTED_CATALOG_STORAGE_REVISION)
        if (sku.id in items && items[sku.id] != expected) invalid(LocalField.SKU_ID)
        items[sku.id] = expected
        val quantity = arrivalQuantityInput(expected.baseUnitId, line.decimal(LocalField.QUANTITY), expected.quantityScale)
        val serialId = line.optionalId(LocalField.SERIAL_ID); val serialCode = line.optionalText(LocalField.SERIAL_CODE)
        val registrationRevision = line.optionalRevision(LocalField.EXPECTED_SERIAL_REGISTRATION_REVISION)
        val lot = line.optionalId(LocalField.LOT_ID); val expiry = line.optionalInstant(LocalField.EXPIRY_AT)
        if (tracking == LocalArrivalTrackingV1.SERIAL) {
            if (quantity != Quantity.parse(quantity.unitId, "1", quantity.scale) || lot != null || expiry != null) invalid(LocalField.QUANTITY)
            checkArrivalSerialCode(serialCode)
            if (serialId != null) requireArrivalSerialSource(access, supplierId, sku.id, serialId, serialCode, registrationRevision)
            else if (registrationRevision != null) invalid(LocalField.EXPECTED_SERIAL_REGISTRATION_REVISION)
        } else if (serialId != null || serialCode != null || registrationRevision != null) invalid(LocalField.SERIAL_ID)
        val result = LocalArrivalLineV1(row.lineId, line.id(LocalField.SOURCE_ID), line.id(LocalField.CUSTODY_UNIT_ID), sku.id, warehouseId,
            quantity, lot, expiry, serialId, serialCode, registrationRevision,
            enumChoice<LocalArrivalDispositionV1>(line.choice(LocalField.DISPOSITION), LocalField.DISPOSITION),
            enumChoice<LocalArrivalOwnershipV1>(line.choice(LocalField.OWNERSHIP), LocalField.OWNERSHIP), line.optionalText(LocalField.OWNERSHIP_REFERENCE))
        line.finish() // Prices and all other unknown fields are refused, never ignored.
        result
    }
    val request = LocalRecordUnpricedArrivalV1(id, supplierId, supplierRevision, input.optionalText(LocalField.SUPPLIER_DELIVERY_REFERENCE),
        input.optionalId(LocalField.LOCAL_INTAKE_TAG), input.optionalId(LocalField.DOCUMENTARY_PO_ID), input.optionalText(LocalField.DISCREPANCY_REFERENCE),
        items.values.toList(), listOf(warehouse.selection), lines, evidence, input.text(LocalField.REASON))
    input.finish()
    return LocalUnpricedArrivalCommandsV1.record(intent.operationId, request)
}

private fun arrivalEvidence(input: InputValues, policy: LocalArrivalPolicyViewV1?, at: UtcInstant, action: LocalArrivalActionV1): LocalArrivalEvidenceSelectionV1 {
    if (!arrivalPolicyCurrent(policy, at)) throw GatewayFailure(LocalServiceProblem.NOT_CONFIGURED)
    val actual = checkNotNull(policy)
    val id = input.id(LocalField.POLICY_ID); val revision = input.revision(LocalField.EXPECTED_EVIDENCE_POLICY_REVISION)
    if (id != actual.policyId || revision != actual.revision) invalid(LocalField.EXPECTED_EVIDENCE_POLICY_REVISION)
    val attachment = input.optionalId(LocalField.ATTACHMENT_ID)
    val attachmentRevision = input.optionalRevision(LocalField.EXPECTED_ATTACHMENT_STORAGE_REVISION)
    if (action in actual.attachmentRequiredFor && attachment == null) invalid(LocalField.ATTACHMENT_ID)
    // Presence is not authenticity. The master re-reads the real attachment and policy in its transaction.
    return LocalArrivalEvidenceSelectionV1(input.text(LocalField.OBSERVATION_REFERENCE), id, revision, attachment, attachmentRevision)
}
private fun arrivalQuantityInput(unitId: DomainId, value: String, scale: Int): Quantity = try {
    Quantity.parse(unitId, value, scale)
} catch (_: ArithmeticException) { invalid(LocalField.QUANTITY) }
catch (_: IllegalArgumentException) { invalid(LocalField.QUANTITY) }

private fun checkArrivalSerialCode(code: String?) {
    if (code != null && (code.isEmpty() || code.length > 128 || code != code.trim() || code.any { it.code < 32 || it.code == 127 } ||
            code.encodeToByteArray(throwOnInvalidSequence = true).size > 256)) invalid(LocalField.SERIAL_CODE)
}
private suspend fun requireArrivalSerialSource(access: GatewayAccess, supplierId: DomainId, skuId: DomainId, serialId: DomainId,
    code: String?, revision: Revision?) {
    val cut = arrivalSource(access, LocalArrivalSourceQueryV1(LocalArrivalSourceKindV1.SERIAL_REGISTRATION, serialId, supplierId = supplierId))
    val serial = cut.requireAvailable().serials?.singleOrNull() ?: invalid(LocalField.SERIAL_ID)
    if (serial.skuId != skuId || serial.serialCode != code || serial.registrationRevision != revision || cut.reply.observedAt < serial.validFrom ||
        serial.validUntil?.let { cut.reply.observedAt >= it } == true) invalid(LocalField.EXPECTED_SERIAL_REGISTRATION_REVISION)
}

private suspend fun prepareArrivalTransition(access: GatewayAccess, intent: LocalCommandIntent): MutationRequest {
    if (intent.lines.isNotEmpty() || intent.lineField != null) invalid(LocalField.LINES)
    val input = InputValues(intent.values)
    val id = input.id(LocalField.ARRIVAL_ID)
    access.requireAction(intent.action, id)
    val revision = input.revision(LocalField.EXPECTED_ARRIVAL_REVISION)
    val cut = arrivalRead(access, LocalArrivalQueryV1(id))
    if (cut.value.availability != LocalArrivalAvailabilityV1.AVAILABLE) unavailable()
    val detail = cut.value.detail ?: invalid(LocalField.ARRIVAL_ID)
    if (detail.header.arrivalRevision != revision) invalid(LocalField.EXPECTED_ARRIVAL_REVISION)
    val custodyUnitId = input.id(LocalField.CUSTODY_UNIT_ID)
    val slice = detail.slices.singleOrNull { it.custodyUnitId == custodyUnitId } ?: invalid(LocalField.CUSTODY_UNIT_ID)
    if (!arrivalHeldUi(slice.disposition)) invalid(LocalField.DISPOSITION)
    val quantity = input.exactQuantity(LocalField.QUANTITY, slice.quantity)
    if (quantity.scaled <= 0 || quantity > slice.quantity) invalid(LocalField.QUANTITY)
    val split = if (slice.tracking == LocalArrivalTrackingV1.SERIAL) {
        if (quantity != slice.quantity) invalid(LocalField.QUANTITY)
        null
    } else {
        val reserved = LocalArrivalSplitV1(input.id(LocalField.SPLIT_SOURCE_ID), input.id(LocalField.SPLIT_CUSTODY_UNIT_ID))
        // Whole-source observations do not create a needless replacement identity.
        if (quantity < slice.quantity) reserved else null
    }
    val eventId = input.id(LocalField.ARRIVAL_EVENT_ID)
    val action = when (intent.action) {
        LocalAction.ARRIVAL_QUALIFY -> LocalArrivalActionV1.QUALIFY
        LocalAction.ARRIVAL_REJECT -> LocalArrivalActionV1.REJECT
        LocalAction.ARRIVAL_DISPATCH -> LocalArrivalActionV1.DISPATCH
        else -> invalid()
    }
    val evidence = arrivalEvidence(input, detail.policy, cut.reply.observedAt, action)
    val reason = input.text(LocalField.REASON)
    val request = when (intent.action) {
        LocalAction.ARRIVAL_QUALIFY -> {
            val lot: DomainId?; val expiry: UtcInstant?; val serialId: DomainId?; val serialCode: String?; val serialRevision: Revision?
            if (slice.tracking == LocalArrivalTrackingV1.SERIAL) {
                lot = null; expiry = null
                serialId = input.optionalId(LocalField.SERIAL_ID); serialCode = input.optionalText(LocalField.SERIAL_CODE)
                serialRevision = input.optionalRevision(LocalField.EXPECTED_SERIAL_REGISTRATION_REVISION)
                checkArrivalSerialCode(serialCode)
                if (slice.serialId != null && (serialId != slice.serialId || serialRevision != slice.serialRegistrationRevision) ||
                    slice.serialCode != null && serialCode != slice.serialCode) invalid(LocalField.SERIAL_ID)
                if (slice.serialId == null && serialId != null) requireArrivalSerialSource(access, detail.header.supplierId, slice.skuId, serialId, serialCode, serialRevision)
            } else {
                serialId = null; serialCode = null; serialRevision = null
                lot = input.optionalId(LocalField.LOT_ID); expiry = input.optionalInstant(LocalField.EXPIRY_AT)
                if (slice.lotId != null && lot != slice.lotId || slice.expiresAt != null && expiry != slice.expiresAt) invalid(LocalField.LOT_ID)
            }
            LocalUnpricedArrivalCommandsV1.qualify(intent.operationId, LocalQualifyUnpricedArrivalV1(eventId, id, revision, slice.custodyUnitId,
                quantity, split, lot, expiry, serialId, serialCode, serialRevision, input.flag(LocalField.INSPECTION_PASSED), evidence, reason))
        }
        LocalAction.ARRIVAL_REJECT -> LocalUnpricedArrivalCommandsV1.reject(intent.operationId,
            LocalRejectUnpricedArrivalV1(eventId, id, revision, slice.custodyUnitId, quantity, split, evidence, reason))
        LocalAction.ARRIVAL_DISPATCH -> {
            val supplier = input.id(LocalField.RECIPIENT_SUPPLIER_ID)
            if (supplier != detail.header.supplierId) invalid(LocalField.RECIPIENT_SUPPLIER_ID)
            LocalUnpricedArrivalCommandsV1.dispatch(intent.operationId,
                LocalDispatchUnpricedArrivalV1(eventId, id, revision, slice.custodyUnitId, quantity, split, supplier, evidence, reason))
        }
        else -> invalid()
    }
    input.finish()
    return request
}

private data class ArrivalIntentIdentity(val action: LocalArrivalActionV1, val arrivalId: DomainId, val eventId: DomainId?,
    val beforeRevision: Revision, val affectedUnit: DomainId?)
private fun arrivalIntentIdentity(request: MutationRequest): ArrivalIntentIdentity = when (request.command) {
    LocalUnpricedArrivalProfileV1.RECORD -> LocalUnpricedArrivalCommandsV1.decodeRecordRequest(request.payload).let {
        ArrivalIntentIdentity(LocalArrivalActionV1.RECORD, it.arrivalId, null, Revision(0), null) }
    LocalUnpricedArrivalProfileV1.QUALIFY -> LocalUnpricedArrivalCommandsV1.decodeQualifyRequest(request.payload).let {
        ArrivalIntentIdentity(LocalArrivalActionV1.QUALIFY, it.arrivalId, it.eventId, it.expectedArrivalRevision, it.split?.custodyUnitId ?: it.custodyUnitId) }
    LocalUnpricedArrivalProfileV1.REJECT -> LocalUnpricedArrivalCommandsV1.decodeRejectRequest(request.payload).let {
        ArrivalIntentIdentity(LocalArrivalActionV1.REJECT, it.arrivalId, it.eventId, it.expectedArrivalRevision, it.split?.custodyUnitId ?: it.custodyUnitId) }
    LocalUnpricedArrivalProfileV1.DISPATCH -> LocalUnpricedArrivalCommandsV1.decodeDispatchRequest(request.payload).let {
        ArrivalIntentIdentity(LocalArrivalActionV1.DISPATCH, it.arrivalId, it.eventId, it.expectedArrivalRevision, it.split?.custodyUnitId ?: it.custodyUnitId) }
    else -> unavailable()
}
internal fun unpricedArrivalTarget(request: MutationRequest): DomainId = arrivalIntentIdentity(request).arrivalId
internal fun renderUnpricedArrivalRecorded(entry: StoredClientIntentV1, reply: MutationReply.Recorded): Pair<LocalText, LocalRecord?> {
    val expected = arrivalIntentIdentity(entry.request())
    val value = LocalUnpricedArrivalCommandsV1.decodeResult(reply.result)
    val expectedCode = value.failure?.let { "arrival.${it.name.lowercase()}" } ?: "committed"
    if (reply.operationId != entry.operationId || reply.originalActorId != entry.owner.actorId || reply.code != expectedCode ||
        value.action != expected.action || value.arrivalId != expected.arrivalId || value.eventId != expected.eventId ||
        (value.failure == null) != reply.committed || value.financialStatus != LocalArrivalFinancialStatusV1.UNPRICED_UNRESOLVED ||
        reply.committed && (value.arrivalRevision != expected.beforeRevision.next() || value.affectedCustodyUnitId != expected.affectedUnit)) unavailable()
    val failure = value.failure
    if (failure != null) return (if (failure == LocalArrivalFailureV1.UNSUPPORTED_POLICY) text(
        "Recorded UNSUPPORTED_POLICY rejection: the authoritative evidence-policy source is unavailable. No custody was recorded or changed. Required policy/attachment integration remains incomplete.",
        "رفض مسجل UNSUPPORTED_POLICY: مصدر سياسة الإثبات المعتمد غير متاح. لم تُسجل عهدة ولم تتغير. يبقى ربط السياسة والمرفقات المطلوبة غير مكتمل.") else text(
        "Recorded unpriced-arrival rejection: ${failure.name}. No custody effects were applied.",
        "رفض وصول غير مسعر مسجل: ${failure.name}. لم تُنفذ آثار على العهدة.")) to null
    val message = if (value.action == LocalArrivalActionV1.DISPATCH) text(
        "Actual rejected-custody departure recorded. Financial responsibility remains unresolved; no credit, loss amount, payment or settlement was created.",
        "سُجلت المغادرة الفعلية للعهدة المرفوضة. تبقى المسؤولية المالية غير محسومة؛ لم يُنشأ إشعار دائن أو مبلغ خسارة أو دفع أو تسوية.") else text(
        "Unpriced custody observation recorded. Goods remain outside sellable/valued stock; no commercial approval, invoice, payable or payment was created.",
        "سُجلت ملاحظة عهدة غير مسعرة. تبقى البضاعة خارج المخزون المتاح للبيع أو المقيم؛ لم ينشأ اعتماد تجاري أو فاتورة أو التزام أو دفع.")
    return message to LocalRecord(LocalRecordReference(LocalRecordKind.ARRIVAL, value.arrivalId, value.arrivalRevision),
        text("Unpriced arrival — financially unresolved", "وصول غير مسعر — غير محسوم ماليًا"), values = buildList {
            add(recordValue(fieldLabel(LocalField.EXPECTED_ARRIVAL_REVISION), literal(checkNotNull(value.arrivalRevision).value.toString())))
            value.eventId?.let { add(recordValue(fieldLabel(LocalField.ARRIVAL_EVENT_ID), literal(it.value))) }
            value.affectedCustodyUnitId?.let { add(recordValue(fieldLabel(LocalField.CUSTODY_UNIT_ID), literal(it.value))) }
        })
}
