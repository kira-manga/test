package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.security.LocalCompanyPolicyFormatV1
import com.ukkera.pos.types.*

/** Additive routes and command identities. No old reference, form or saved request selects v2. */
internal val unpricedArrivalKeysV2: Set<CommandKey> = setOf(LocalUnpricedArrivalProfileV2.RECORD,
    LocalUnpricedArrivalProfileV2.QUALIFY, LocalUnpricedArrivalProfileV2.REJECT, LocalUnpricedArrivalProfileV2.DISPATCH)

internal fun unpricedArrivalCommandV2(action: LocalAction): CommandKey = when (action) {
    LocalAction.ARRIVAL_RECORD -> LocalUnpricedArrivalProfileV2.RECORD
    LocalAction.ARRIVAL_QUALIFY -> LocalUnpricedArrivalProfileV2.QUALIFY
    LocalAction.ARRIVAL_REJECT -> LocalUnpricedArrivalProfileV2.REJECT
    LocalAction.ARRIVAL_DISPATCH -> LocalUnpricedArrivalProfileV2.DISPATCH
    else -> invalid()
}

private val arrivalRecordKindsV2 = setOf(LocalRecordKind.ARRIVAL_V2, LocalRecordKind.ARRIVAL_SLICE_V2,
    LocalRecordKind.ARRIVAL_EVENT_V2, LocalRecordKind.ARRIVAL_SOURCE_V2, LocalRecordKind.ARRIVAL_LIST_V2,
    LocalRecordKind.ARRIVAL_SUPPLIER_V2, LocalRecordKind.ARRIVAL_WAREHOUSE_V2)

internal fun arrivalNavigationV2(access: GatewayAccess, workflow: LocalWorkflow): List<LocalRecord> =
    if (workflow != LocalWorkflow.RECEIVING) emptyList() else listOf(
        LocalRecord(LocalRecordReference(LocalRecordKind.ARRIVAL_SOURCE_V2, access.view.scope.branchId),
            text("Unpriced arrival v2 — explicit signed rule and originals", "وصول غير مسعر v2 — قاعدة موقعة وأصول مختارة صراحةً")),
        LocalRecord(LocalRecordReference(LocalRecordKind.ARRIVAL_LIST_V2, access.view.scope.branchId),
            text("Unpriced arrivals v2 — retained physical history", "الوصول غير المسعر v2 — السجل الفعلي المحفوظ")))

internal fun isArrivalRouteV2(query: LocalWorkflowQuery): Boolean = query.reference?.kind in arrivalRecordKindsV2 ||
    query.reference == null && query.workflow in setOf(LocalWorkflow.RECEIVING, LocalWorkflow.INVENTORY) &&
        (query.search == "arrivals2" || query.search.startsWith("arrival2:") ||
            query.search.startsWith("arrival2-new:") || query.search.startsWith("arrival2-serial:"))

private val arrivalScopeNoticeV2: LocalText get() = text(
    "Cost-free custody only: all goods remain UNPRICED_UNRESOLVED and outside sellable/valued stock. Record, qualification, rejection and dispatch create no invoice, payable, payment, cash movement or PO allocation. Direct execution cannot replace Separate/NamedSelf approval history. Current Master authority and genuine originals are checked again at execution.",
    "عهدة دون تكلفة فقط: تبقى كل البضاعة غير مسعرة وغير محسومة وخارج المخزون القابل للبيع أو المقيم. التسجيل والتأهيل والرفض والإرسال لا تنشئ فاتورة أو التزامًا أو دفعًا أو حركة نقدية أو تخصيص أمر شراء. التنفيذ المباشر لا يستبدل سجل اعتماد منفصل أو ذاتي مسمى. يُعاد فحص صلاحية الجهاز الرئيسي الحالية والأصول الفعلية عند التنفيذ.")

internal data class ArrivalSourceCutV2(val value: LocalArrivalSourcePageV2, val reply: ReadReply.Data) {
    fun requireAvailable(): LocalArrivalSourcePageV2 {
        if (value.availability != LocalArrivalAvailabilityV1.AVAILABLE) unavailable()
        return value
    }
}

/** Only the actual protected endpoint projection may supply these coordinates. */
internal suspend fun arrivalSourceV2(access: GatewayAccess, query: LocalArrivalSourceQueryV2): ArrivalSourceCutV2 {
    val reply = access.read(LocalUnpricedArrivalCommandsV2.source(query))
    val page = LocalUnpricedArrivalCommandsV2.decodeSource(reply.payload)
    if (page.kind != query.kind) unavailable()
    if (page.availability == LocalArrivalAvailabilityV1.AVAILABLE) {
        val suppliers = page.suppliers ?: unavailable(); val items = page.items ?: unavailable()
        val warehouses = page.warehouses ?: unavailable(); val serials = page.serials ?: unavailable()
        val ids = when (query.kind) {
            LocalArrivalSourceKindV1.SUPPLIER -> {
                if (items.isNotEmpty() || warehouses.isNotEmpty() || serials.isNotEmpty()) unavailable()
                suppliers.map { it.id }
            }
            LocalArrivalSourceKindV1.ITEM -> {
                if (suppliers.isNotEmpty() || warehouses.isNotEmpty() || serials.isNotEmpty()) unavailable()
                items.map { it.selection.skuId }
            }
            LocalArrivalSourceKindV1.WAREHOUSE -> {
                if (suppliers.isNotEmpty() || items.isNotEmpty() || serials.isNotEmpty()) unavailable()
                warehouses.map { it.selection.warehouseId }
            }
            LocalArrivalSourceKindV1.SERIAL_REGISTRATION -> {
                if (suppliers.isNotEmpty() || items.isNotEmpty() || warehouses.isNotEmpty()) unavailable()
                serials.map { it.serialId }
            }
        }
        if (ids.size > (if (query.id == null) query.limit else 1) || ids.distinct().size != ids.size ||
            ids != ids.sortedBy { it.value } || query.id != null && (ids.any { it != query.id } || page.nextAfter != null) ||
            query.after?.let { after -> ids.any { it.value <= after.value } } == true ||
            page.nextAfter != null && page.nextAfter != ids.lastOrNull()) unavailable()
        if (serials.any { it.registrationRevision.value < 1 }) unavailable()
        page.purchasing?.let { purchasing ->
            val supplierId = query.supplierId ?: query.id?.takeIf { query.kind == LocalArrivalSourceKindV1.SUPPLIER }
            if (supplierId == null || purchasing.policy.scope != access.view.scope || purchasing.catalog.scope != access.view.scope ||
                purchasing.rules.size > LocalCompanyPolicyFormatV1.MAX_RULES || purchasing.rules.any { it.action != query.action } ||
                purchasing.documents.map { it.documentId } != query.documentIds ||
                purchasing.documents.any { it.scope != access.view.scope || it.supplierId != supplierId }) unavailable()
        }
    }
    // In particular, zero is preserved as a real source generation, not changed to one.
    return ArrivalSourceCutV2(page, reply)
}

internal data class ArrivalDetailCutV2(val value: LocalArrivalPageV2, val reply: ReadReply.Data)

internal suspend fun arrivalReadV2(access: GatewayAccess, query: LocalArrivalQueryV1): ArrivalDetailCutV2 {
    val reply = access.read(LocalUnpricedArrivalCommandsV2.read(query))
    val page = LocalUnpricedArrivalCommandsV2.decodePage(reply.payload)
    if (page.availability == LocalArrivalAvailabilityV1.AVAILABLE) {
        val headers = page.headers ?: unavailable()
        if (headers.size > (if (query.id == null) query.limit else 1) || headers.map { it.arrivalId }.distinct().size != headers.size ||
            headers != headers.sortedBy { it.arrivalId.value } || query.after?.let { after -> headers.any { it.arrivalId.value <= after.value } } == true ||
            query.id != null && (headers.any { it.arrivalId != query.id } || page.nextAfter != null) ||
            page.nextAfter != null && page.nextAfter != headers.lastOrNull()?.arrivalId ||
            headers.any { it.arrivalRevision.value < 1 || it.heldSlices !in 0..16 }) unavailable()
        val detail = page.detail
        if (query.id == null && detail != null || query.id != null && (detail == null) != headers.isEmpty()) unavailable()
        if (detail != null) {
            if (detail.header != headers.single() || detail.slices.size !in 1..16 || detail.events.size > 64 ||
                detail.header.arrivalRevision.value != detail.events.size.toLong() + 1 ||
                detail.slices.map { it.custodyUnitId }.distinct().size != detail.slices.size ||
                detail.slices.map { it.sourceId }.distinct().size != detail.slices.size ||
                detail.events.map { it.eventId }.distinct().size != detail.events.size || detail.slices.any { it.quantity.scaled <= 0 } ||
                detail.header.heldSlices != detail.slices.count { arrivalHeldV2(it.disposition) } ||
                detail.header.physicallyClosed != detail.slices.none { arrivalHeldV2(it.disposition) } ||
                detail.purchasing.size > 3 ||
                detail.purchasing.any { it.policy.scope != access.view.scope || it.catalog.scope != access.view.scope ||
                    it.rules.size > LocalCompanyPolicyFormatV1.MAX_RULES ||
                    it.rules.any { rule -> rule.action == LocalArrivalActionV1.RECORD } ||
                    it.rules.map { rule -> rule.action }.distinct().size > 1 ||
                    it.documents.size > LocalCompanyPolicyFormatV1.MAX_EVIDENCE_MANIFESTS ||
                    it.documents.map { document -> document.documentId }.distinct().size != it.documents.size ||
                    it.documents.any { document -> document.scope != access.view.scope || document.supplierId != detail.header.supplierId } }) unavailable()
        }
    }
    return ArrivalDetailCutV2(page, reply)
}

internal fun arrivalHeldV2(disposition: LocalArrivalDispositionV1): Boolean = disposition in setOf(
    LocalArrivalDispositionV1.UNINSPECTED_HELD, LocalArrivalDispositionV1.PASSED_HELD, LocalArrivalDispositionV1.REJECTED_HELD)

internal fun arrivalPolicyCurrentV2(value: LocalArrivalPurchasingViewV2?, at: UtcInstant): Boolean = value != null &&
    at >= value.policy.validFrom && (value.policy.validUntil?.let { at < it } ?: true)

private fun arrivalCapacityPageV2(query: LocalWorkflowQuery, reply: ReadReply.Data): LocalWorkflowPage =
    LocalWorkflowPage(query.workflow, emptyList(), emptyList(), text(
        "The complete v2 custody/source cut is unavailable. No partial quantities, substituted revisions or mutation form are shown. ${readCut(reply).english}",
        "مراجعة العهدة والمصادر v2 الكاملة غير متاحة. لا تُعرض كميات جزئية أو مراجعات بديلة أو نموذج تغيير. ${readCut(reply).arabic}"))

private fun arrivalNoticeV2(value: LocalArrivalPurchasingViewV2?, reply: ReadReply.Data): LocalText {
    val state = if (arrivalPolicyCurrentV2(value, reply.observedAt)) text(
        "Explicitly choose one exact action rule, retention class/revision and zero or ordered originals. A displayed manifest or matching digest grants no authority; originals must already be durable and genuinely retrievable.",
        "اختر صراحةً قاعدة إجراء دقيقة واحدة وفئة احتفاظ ومراجعتها وصفرًا من الأصول أو أصولًا مرتبة. القائمة المعروضة أو تطابق البصمة لا يمنح صلاحية؛ يجب أن تكون الأصول محفوظة دائمًا وقابلة للاسترجاع فعليًا.")
    else text("HELD — current authenticated purchasing policy/catalog selections are unavailable. Absence is not optional evidence; no physical mutation form is offered.",
        "موقوف — اختيارات سياسة الشراء والكتالوج الحالية المعتمدة غير متاحة. الغياب ليس اختيارية إثبات؛ لا يُعرض نموذج تغيير فعلي.")
    return text("${state.english} ${arrivalScopeNoticeV2.english} ${readCut(reply).english}",
        "${state.arabic} ${arrivalScopeNoticeV2.arabic} ${readCut(reply).arabic}")
}

/** Existing search controls are explicit ID discovery, not signing or original-file capture. */
private fun arrivalDocumentIdsV2(search: String): List<DomainId> = if (search.startsWith("arrival2-documents:"))
    arrivalDocumentIdListV2(search.removePrefix("arrival2-documents:")) else emptyList()

private fun arrivalDocumentIdListV2(value: String): List<DomainId> {
    if (value.isEmpty()) return emptyList()
    val ids = value.split(',').map(::searchedId)
    if (ids.size > LocalCompanyPolicyFormatV1.MAX_EVIDENCE_MANIFESTS || ids.distinct().size != ids.size)
        invalid(LocalField.ARRIVAL_ORIGINAL_DOCUMENTS)
    return ids
}

internal suspend fun unpricedArrivalWorkflowV2(access: GatewayAccess, query: LocalWorkflowQuery): LocalWorkflowPage {
    val reference = query.reference
    if (reference?.kind in setOf(LocalRecordKind.ARRIVAL_V2, LocalRecordKind.ARRIVAL_SLICE_V2, LocalRecordKind.ARRIVAL_EVENT_V2))
        return arrivalDetailWorkflowV2(access, query, checkNotNull(reference))
    if (reference?.kind == LocalRecordKind.ARRIVAL_LIST_V2 || reference == null && query.search == "arrivals2") {
        if (reference != null && reference.id != access.view.scope.branchId) invalid()
        val cut = arrivalReadV2(access, LocalArrivalQueryV1(after = parseCursor(query.cursor, "arrivals2")))
        if (cut.value.availability != LocalArrivalAvailabilityV1.AVAILABLE) return arrivalCapacityPageV2(query, cut.reply)
        return LocalWorkflowPage(query.workflow, cut.value.headers.orEmpty().map(::arrivalHeaderRecordV2), emptyList(), text(
            "Complete v2 header page, not a stock/value report. Select a header or use arrival2:ID. Legacy arrivals remain in their v1 route. ${arrivalScopeNoticeV2.english} ${readCut(cut.reply).english}",
            "صفحة رؤوس v2 كاملة، وليست تقرير مخزون أو قيمة. اختر رأسًا أو استخدم arrival2:ID. تبقى الوصولات القديمة في مسار v1. ${arrivalScopeNoticeV2.arabic} ${readCut(cut.reply).arabic}"), cursor("arrivals2", cut.value.nextAfter))
    }
    if (reference == null && query.search.startsWith("arrival2:"))
        return arrivalDetailWorkflowV2(access, query, LocalRecordReference(LocalRecordKind.ARRIVAL_V2,
            searchedId(query.search.removePrefix("arrival2:"))))
    if (query.workflow != LocalWorkflow.RECEIVING) invalid()
    if (reference == null && query.search.startsWith("arrival2-serial:")) {
        val cut = arrivalSourceV2(access, LocalArrivalSourceQueryV2(LocalArrivalSourceKindV1.SERIAL_REGISTRATION,
            searchedId(query.search.removePrefix("arrival2-serial:"))))
        if (cut.value.availability != LocalArrivalAvailabilityV1.AVAILABLE) return arrivalCapacityPageV2(query, cut.reply)
        return LocalWorkflowPage(query.workflow, cut.value.serials.orEmpty().map { serial -> LocalRecord(
            LocalRecordReference(LocalRecordKind.SKU, serial.skuId), text("Actual serial-registration source", "مصدر التسجيل المسلسل الفعلي"),
            values = listOf(recordValue(fieldLabel(LocalField.SERIAL_ID), literal(serial.serialId.value)),
                recordValue(fieldLabel(LocalField.SERIAL_CODE), literal(serial.serialCode)),
                recordValue(fieldLabel(LocalField.EXPECTED_SERIAL_REGISTRATION_REVISION), literal(serial.registrationRevision.value.toString())),
                recordValue(fieldLabel(LocalField.VALID_FROM), literal(serial.validFrom.canonicalString())),
                recordValue(fieldLabel(LocalField.VALID_UNTIL), literal(serial.validUntil?.canonicalString() ?: "no supplied end"))), selectable = false)
        }, emptyList(), readCut(cut.reply))
    }
    if (reference == null && query.search.startsWith("arrival2-new:")) {
        val ids = query.search.removePrefix("arrival2-new:").split('/')
        if (ids.size !in 2..4) invalid()
        val sku = ids.getOrNull(2)?.takeUnless { it == "-" }?.let(::searchedId)
        return arrivalIntakeWorkflowV2(access, query, searchedId(ids[0]), searchedId(ids[1]), sku,
            ids.getOrNull(3)?.let(::arrivalDocumentIdListV2).orEmpty())
    }
    return when (reference?.kind) {
        LocalRecordKind.ARRIVAL_SUPPLIER_V2 -> arrivalWarehouseWorkflowV2(access, query, reference.id)
        LocalRecordKind.ARRIVAL_WAREHOUSE_V2 -> arrivalIntakeWorkflowV2(access, query,
            reference.parentId ?: invalid(LocalField.SUPPLIER_ID), reference.id, documentIds = arrivalDocumentIdsV2(query.search))
        LocalRecordKind.ARRIVAL_SOURCE_V2 -> {
            if (reference.id != access.view.scope.branchId) invalid()
            val cut = arrivalSourceV2(access, LocalArrivalSourceQueryV2(LocalArrivalSourceKindV1.SUPPLIER,
                after = parseCursor(query.cursor, "arrival2-suppliers")))
            if (cut.value.availability != LocalArrivalAvailabilityV1.AVAILABLE) return arrivalCapacityPageV2(query, cut.reply)
            LocalWorkflowPage(query.workflow, cut.value.suppliers.orEmpty().map { supplier -> LocalRecord(
                LocalRecordReference(LocalRecordKind.ARRIVAL_SUPPLIER_V2, supplier.id), arrivalNamesV2(supplier.names),
                if (supplier.active) text("Known supplier", "مورد معروف") else text("Inactive — new intake blocked", "غير نشط — الإدخال الجديد موقوف"),
                listOf(recordValue(fieldLabel(LocalField.EXPECTED_SUPPLIER_STORAGE_REVISION), literal(supplier.storageRevision.value.toString()))))
            }, emptyList(), text("Select the actual supplier, warehouse and observed known SKU. Exact v2 shortcut: arrival2-new:SUPPLIER/WAREHOUSE[/SKU-or--/DOCUMENT[,DOCUMENT]]. No invented identities or originals.",
                "اختر المورد والمخزن الفعليين والصنف المعروف المرصود. اختصار v2 المحدد: arrival2-new:SUPPLIER/WAREHOUSE[/SKU-or--/DOCUMENT[,DOCUMENT]]. دون اختلاق هويات أو أصول."),
                cursor("arrival2-suppliers", cut.value.nextAfter))
        }
        else -> invalid()
    }
}

private suspend fun arrivalWarehouseWorkflowV2(access: GatewayAccess, query: LocalWorkflowQuery, supplierId: DomainId): LocalWorkflowPage {
    val supplierCut = arrivalSourceV2(access, LocalArrivalSourceQueryV2(LocalArrivalSourceKindV1.SUPPLIER, supplierId))
    if (supplierCut.value.availability != LocalArrivalAvailabilityV1.AVAILABLE) return arrivalCapacityPageV2(query, supplierCut.reply)
    val supplier = supplierCut.value.suppliers?.singleOrNull() ?: return LocalWorkflowPage(query.workflow, emptyList(), emptyList(),
        text("The exact supplier source is absent; no replacement supplier is selected.", "مصدر المورد المحدد غائب؛ لا يُختار مورد بديل."))
    val family = "arrival2-warehouses.${supplierId.value}"
    val cut = arrivalSourceV2(access, LocalArrivalSourceQueryV2(LocalArrivalSourceKindV1.WAREHOUSE,
        after = parseCursor(query.cursor, family), supplierId = supplierId))
    if (cut.value.availability != LocalArrivalAvailabilityV1.AVAILABLE) return arrivalCapacityPageV2(query, cut.reply)
    return LocalWorkflowPage(query.workflow, listOf(arrivalSupplierRecordV2(supplier)) + cut.value.warehouses.orEmpty().map { warehouse ->
        arrivalWarehouseRecordV2(warehouse, supplierId, selectable = true)
    }, emptyList(), arrivalNoticeV2(cut.value.purchasing, cut.reply), cursor(family, cut.value.nextAfter))
}

private suspend fun arrivalIntakeWorkflowV2(access: GatewayAccess, query: LocalWorkflowQuery, supplierId: DomainId,
    warehouseId: DomainId, skuId: DomainId? = null, documentIds: List<DomainId> = emptyList()): LocalWorkflowPage {
    val supplierCut = arrivalSourceV2(access, LocalArrivalSourceQueryV2(LocalArrivalSourceKindV1.SUPPLIER, supplierId))
    if (supplierCut.value.availability != LocalArrivalAvailabilityV1.AVAILABLE) return arrivalCapacityPageV2(query, supplierCut.reply)
    val supplier = supplierCut.value.suppliers?.singleOrNull() ?: invalid(LocalField.SUPPLIER_ID)
    val warehouseCut = arrivalSourceV2(access, LocalArrivalSourceQueryV2(LocalArrivalSourceKindV1.WAREHOUSE, warehouseId, supplierId = supplierId))
    if (warehouseCut.value.availability != LocalArrivalAvailabilityV1.AVAILABLE) return arrivalCapacityPageV2(query, warehouseCut.reply)
    val warehouse = warehouseCut.value.warehouses?.singleOrNull() ?: invalid(LocalField.WAREHOUSE_ID)
    val family = "arrival2-items.${supplierId.value}.${warehouseId.value}"
    val itemCut = arrivalSourceV2(access, LocalArrivalSourceQueryV2(LocalArrivalSourceKindV1.ITEM, skuId,
        after = if (skuId == null) parseCursor(query.cursor, family) else null, supplierId = supplierId))
    if (itemCut.value.availability != LocalArrivalAvailabilityV1.AVAILABLE) return arrivalCapacityPageV2(query, itemCut.reply)
    val items = itemCut.value.items.orEmpty()
    val eligible = items.filter { it.active && it.tracking != null }
    val selectionCut = arrivalSourceV2(access, LocalArrivalSourceQueryV2(LocalArrivalSourceKindV1.SUPPLIER,
        supplierId, supplierId = supplierId, skuIds = eligible.map { it.selection.skuId }, documentIds = documentIds))
    if (selectionCut.value.availability != LocalArrivalAvailabilityV1.AVAILABLE) return arrivalCapacityPageV2(query, selectionCut.reply)
    if (selectionCut.value.suppliers?.singleOrNull() != supplier ||
        listOf(supplierCut.value.requesterPolicyRevision, warehouseCut.value.requesterPolicyRevision, itemCut.value.requesterPolicyRevision)
            .any { it != selectionCut.value.requesterPolicyRevision }) unavailable()
    val purchasing = selectionCut.value.purchasing
    val options = purchasing?.let { arrivalEvidenceOptionsV2(it, selectionCut.value.requesterPolicyRevision) }
    val form = if (supplier.active && warehouse.active && eligible.isNotEmpty() &&
        arrivalPolicyCurrentV2(purchasing, selectionCut.reply.observedAt) &&
        options?.rules?.any { access.arrivalRuleAvailableV2(it) } == true &&
        access.installed.has(LocalUnpricedArrivalProfileV2.RECORD))
        arrivalRecordFormV2(supplier, warehouse, eligible, checkNotNull(options)) else null
    return LocalWorkflowPage(query.workflow, listOf(arrivalSupplierRecordV2(supplier), arrivalWarehouseRecordV2(warehouse, supplierId, selectable = true)) +
        items.map(::arrivalItemRecordV2) + options?.let(::arrivalPurchasingRecordsV2).orEmpty(), listOfNotNull(form), text(
            "Select the displayed warehouse record to keep its explicit supplier/warehouse context, then enter arrival2-documents:DOCUMENT[,DOCUMENT] and Refresh before opening a form. Clear record selection to use a full exact SKU/document shortcut. An empty read list is not a zero-file decision: choose that explicitly in the form. ${arrivalNoticeV2(purchasing, selectionCut.reply).english}",
            "اختر سجل المخزن المعروض لحفظ سياق المورد والمخزن الصريح، ثم أدخل arrival2-documents:DOCUMENT[,DOCUMENT] وحدّث قبل فتح النموذج. امسح اختيار السجل لاستخدام اختصار الصنف والمستندات الدقيق كاملًا. قائمة القراءة الفارغة ليست قرار صفر ملفات؛ اختره صراحةً في النموذج. ${arrivalNoticeV2(purchasing, selectionCut.reply).arabic}"),
        cursor(family, itemCut.value.nextAfter))
}

private suspend fun arrivalDetailWorkflowV2(access: GatewayAccess, query: LocalWorkflowQuery, reference: LocalRecordReference): LocalWorkflowPage {
    val id = if (reference.kind == LocalRecordKind.ARRIVAL_V2) reference.id else reference.parentId ?: invalid(LocalField.ARRIVAL_ID)
    val cut = arrivalReadV2(access, LocalArrivalQueryV1(id))
    if (cut.value.availability != LocalArrivalAvailabilityV1.AVAILABLE) return arrivalCapacityPageV2(query, cut.reply)
    val detail = cut.value.detail ?: return LocalWorkflowPage(query.workflow, emptyList(), emptyList(),
        text("No v2 arrival is present at this protected cut. No v1 record or replacement identity is substituted.",
            "لا يوجد وصول v2 في هذه المراجعة المحمية. لا يُستبدل بسجل v1 أو هوية بديلة."))
    val selected = if (reference.kind == LocalRecordKind.ARRIVAL_SLICE_V2) detail.slices.singleOrNull { it.custodyUnitId == reference.id }
        ?: invalid(LocalField.CUSTODY_UNIT_ID) else null
    if (reference.kind == LocalRecordKind.ARRIVAL_EVENT_V2 && detail.events.none { it.eventId == reference.id }) invalid(LocalField.ARRIVAL_EVENT_ID)
    val records = listOf(arrivalHeaderRecordV2(detail.header)) + detail.slices.map { arrivalSliceRecordV2(it, id) } +
        detail.events.map { event -> LocalRecord(LocalRecordReference(LocalRecordKind.ARRIVAL_EVENT_V2, event.eventId, parentId = id),
            text("Retained v2 custody observation/departure", "ملاحظة أو مغادرة عهدة v2 محفوظة"), actionLabel(arrivalUiActionV2(event.action)),
            listOf(recordValue(text("Source custody unit", "وحدة العهدة المصدر"), literal(event.sourceCustodyUnitId.value)),
                recordValue(text("Affected custody unit", "وحدة العهدة المتأثرة"), literal(event.affectedCustodyUnitId.value)),
                recordValue(fieldLabel(LocalField.QUANTITY), quantityText(event.quantity)),
                recordValue(text("Recorded at master", "وقت التسجيل لدى الجهاز الرئيسي"), literal(event.recordedAt.canonicalString()))), selectable = false) }
    val actions = when (selected?.disposition) {
        LocalArrivalDispositionV1.UNINSPECTED_HELD, LocalArrivalDispositionV1.PASSED_HELD -> listOf(LocalAction.ARRIVAL_QUALIFY, LocalAction.ARRIVAL_REJECT)
        LocalArrivalDispositionV1.REJECTED_HELD -> listOf(LocalAction.ARRIVAL_DISPATCH)
        else -> emptyList()
    }.filter { it.canAppearIn(query.workflow) && access.installed.has(unpricedArrivalCommandV2(it)) && access.arrivalActionHintV2(it, id) }
    if (selected == null || actions.isEmpty()) return LocalWorkflowPage(query.workflow, records, emptyList(), text(
        "Select one held partition for a new authorized action. Rejected goods remain held until actual dispatch. New rule/catalog/original selections come from a separate protected current-source read; historical selections do not authorize new work. ${arrivalScopeNoticeV2.english} ${readCut(cut.reply).english}",
        "اختر جزءًا واحدًا في العهدة لإجراء جديد مصرح به. تبقى المرفوضات في العهدة حتى الإرسال الفعلي. تأتي اختيارات القاعدة والكتالوج والأصول الجديدة من قراءة مصادر حالية محمية منفصلة؛ الاختيارات التاريخية لا تصرح بعمل جديد. ${arrivalScopeNoticeV2.arabic} ${readCut(cut.reply).arabic}"))
    val forms = mutableListOf<LocalActionForm>()
    var displayedOptions: LocalArrivalEvidenceOptionsV2? = null
    var lastReply = cut.reply
    var lastPurchasing: LocalArrivalPurchasingViewV2? = null
    for (action in actions) {
        val physicalAction = arrivalPhysicalActionV2(action)
        val itemCut = arrivalSourceV2(access, LocalArrivalSourceQueryV2(LocalArrivalSourceKindV1.ITEM, selected.skuId,
            supplierId = detail.header.supplierId, action = physicalAction))
        if (itemCut.value.availability != LocalArrivalAvailabilityV1.AVAILABLE) return arrivalCapacityPageV2(query, itemCut.reply)
        val warehouseCut = arrivalSourceV2(access, LocalArrivalSourceQueryV2(LocalArrivalSourceKindV1.WAREHOUSE, selected.warehouseId,
            supplierId = detail.header.supplierId, action = physicalAction))
        if (warehouseCut.value.availability != LocalArrivalAvailabilityV1.AVAILABLE) return arrivalCapacityPageV2(query, warehouseCut.reply)
        val item = itemCut.value.items?.singleOrNull() ?: invalid(LocalField.SKU_ID)
        val warehouse = warehouseCut.value.warehouses?.singleOrNull() ?: invalid(LocalField.WAREHOUSE_ID)
        if (item.tracking != selected.tracking || item.selection.baseUnitId != selected.quantity.unitId ||
            item.selection.quantityScale != selected.quantity.scale) unavailable()
        val source = arrivalSourceV2(access, LocalArrivalSourceQueryV2(LocalArrivalSourceKindV1.SUPPLIER, detail.header.supplierId,
            supplierId = detail.header.supplierId, action = physicalAction, skuIds = listOf(selected.skuId),
            documentIds = arrivalDocumentIdsV2(query.search)))
        if (source.value.availability != LocalArrivalAvailabilityV1.AVAILABLE) return arrivalCapacityPageV2(query, source.reply)
        if (listOf(cut.value.requesterPolicyRevision, itemCut.value.requesterPolicyRevision, warehouseCut.value.requesterPolicyRevision)
                .any { it != source.value.requesterPolicyRevision }) unavailable()
        val supplier = source.value.suppliers?.singleOrNull() ?: invalid(LocalField.SUPPLIER_ID)
        val purchasing = source.value.purchasing
        if (supplier.active && item.active && warehouse.active && arrivalPolicyCurrentV2(purchasing, source.reply.observedAt)) {
            val options = arrivalEvidenceOptionsV2(checkNotNull(purchasing), source.value.requesterPolicyRevision)
            if (options.rules.any { access.arrivalRuleAvailableV2(it, id) }) {
                forms += arrivalTransitionFormV2(action, detail.header, selected, supplier, item, warehouse, options)
                if (displayedOptions == null) displayedOptions = options
            }
        }
        lastReply = source.reply; lastPurchasing = purchasing
    }
    return LocalWorkflowPage(query.workflow, records + displayedOptions?.let(::arrivalPurchasingRecordsV2).orEmpty(), forms, text(
        "On this partition, use arrival2-documents:DOCUMENT[,DOCUMENT] and Refresh before opening a form. Qualification requires a new explicit observation; splitting alone is not qualification. ${arrivalNoticeV2(lastPurchasing, lastReply).english}",
        "في هذا الجزء استخدم arrival2-documents:DOCUMENT[,DOCUMENT] وحدّث قبل فتح النموذج. التأهيل يتطلب ملاحظة جديدة صريحة؛ التقسيم وحده ليس تأهيلًا. ${arrivalNoticeV2(lastPurchasing, lastReply).arabic}"))
}

private fun arrivalNamesV2(names: LocalArrivalNamesV1): LocalText = text(names.english, names.arabic)
private fun arrivalYesNoV2(value: Boolean): LocalText = if (value) text("Yes", "نعم") else text("No", "لا")

private fun arrivalSupplierRecordV2(value: LocalArrivalSupplierViewV2): LocalRecord = LocalRecord(
    LocalRecordReference(LocalRecordKind.ARRIVAL_SUPPLIER_V2, value.id), arrivalNamesV2(value.names), values = listOf(
        recordValue(fieldLabel(LocalField.EXPECTED_SUPPLIER_STORAGE_REVISION), literal(value.storageRevision.value.toString())),
        recordValue(fieldLabel(LocalField.ACTIVE), arrivalYesNoV2(value.active))), selectable = false)

private fun arrivalWarehouseRecordV2(value: LocalArrivalWarehouseViewV2, supplierId: DomainId, selectable: Boolean = false): LocalRecord = LocalRecord(
    LocalRecordReference(LocalRecordKind.ARRIVAL_WAREHOUSE_V2, value.selection.warehouseId, parentId = supplierId), arrivalNamesV2(value.names),
    if (value.active) text("Actual branch warehouse", "مخزن الفرع الفعلي") else text("Inactive", "غير نشط"), listOf(
        recordValue(fieldLabel(LocalField.EXPECTED_WAREHOUSE_STORAGE_REVISION), literal(value.selection.storageRevision.value.toString()))), selectable = selectable)

private fun arrivalItemRecordV2(value: LocalArrivalItemViewV2): LocalRecord = LocalRecord(
    LocalRecordReference(LocalRecordKind.SKU, value.selection.skuId, value.selection.skuRevision), arrivalNamesV2(value.names),
    if (value.active && value.tracking != null) text("Known stocked SKU — unpriced custody only", "صنف مخزني معروف — عهدة غير مسعرة فقط")
    else text("Ineligible for new intake", "غير مؤهل لإدخال جديد"), listOf(
        recordValue(fieldLabel(LocalField.SKU_CODE), literal(value.code)),
        recordValue(fieldLabel(LocalField.PRODUCT_ID), literal(value.selection.productId.value)),
        recordValue(fieldLabel(LocalField.EXPECTED_PRODUCT_REVISION), literal(value.selection.productRevision.value.toString())),
        recordValue(fieldLabel(LocalField.TRACKING), literal(value.tracking?.name ?: "not stocked")),
        recordValue(fieldLabel(LocalField.UNIT), literal(value.selection.baseUnitId.value)),
        recordValue(fieldLabel(LocalField.QUANTITY_SCALE), literal(value.selection.quantityScale.toString())),
        recordValue(fieldLabel(LocalField.REQUIRES_EXPIRY), arrivalYesNoV2(value.requiresExpiry))), selectable = false)

private fun arrivalPurchasingRecordsV2(options: LocalArrivalEvidenceOptionsV2): List<LocalRecord> {
    fun source(value: LocalArrivalSourceCoordinateV2): LocalText = literal(
        "${value.kind} / ${value.id.value} / storage ${value.storageRevision.value} / ${value.digest.value}")
    val policy = options.policy; val catalog = options.catalog
    return listOf(LocalRecord(LocalRecordReference(LocalRecordKind.POLICY, policy.policyId),
        text("Current exact purchasing policy selection", "اختيار سياسة الشراء الحالية الدقيق"), values = listOf(
            recordValue(text("Organization / company / branch", "المؤسسة والشركة والفرع"),
                literal("${policy.scope.organizationId.value} / ${policy.scope.companyId.value} / ${policy.scope.branchId.value}")),
            recordValue(text("Publication / revision", "المنشور والمراجعة"), literal("${policy.publicationId.value} / ${policy.publicationRevision.value}")),
            recordValue(text("Policy validity", "صلاحية السياسة"),
                literal("${policy.validFrom.canonicalString()} / ${policy.validUntil?.canonicalString() ?: "no supplied end"}")),
            recordValue(text("Active pointer source", "مصدر المؤشر النشط"), source(policy.activePointer)),
            recordValue(text("Publication source", "مصدر المنشور"), source(policy.publication)),
            recordValue(fieldLabel(LocalField.EXPECTED_REQUESTER_POLICY_REVISION), literal(options.requesterPolicyRevision.value.toString()))), selectable = false),
        LocalRecord(LocalRecordReference(LocalRecordKind.CATALOG_VERSION, catalog.publicationId),
            text("Current complete catalog selection", "اختيار الكتالوج الحالي الكامل"), values = listOf(
                recordValue(text("Organization / company / branch", "المؤسسة والشركة والفرع"),
                    literal("${catalog.scope.organizationId.value} / ${catalog.scope.companyId.value} / ${catalog.scope.branchId.value}")),
                recordValue(text("Catalog / revision", "الكتالوج والمراجعة"), literal("${catalog.catalogId.value} / ${catalog.revision.value}")),
                recordValue(text("Active pointer source", "مصدر المؤشر النشط"), source(catalog.activePointer)),
                recordValue(text("Publication source", "مصدر المنشور"), source(catalog.publication))), selectable = false)) +
        options.documents.map { document -> LocalRecord(document.reference, text("Exact discovered original manifest", "قائمة أصل مكتشف دقيقة"),
            values = listOf(recordValue(fieldLabel(LocalField.ARRIVAL_ORIGINAL_DOCUMENTS), literal(arrivalOriginalDescriptionV2(document)))), selectable = false) }
}

private fun arrivalHeaderRecordV2(header: LocalArrivalHeaderV1): LocalRecord = LocalRecord(
    LocalRecordReference(LocalRecordKind.ARRIVAL_V2, header.arrivalId, header.arrivalRevision), text("Unpriced physical arrival v2", "وصول فعلي غير مسعر v2"),
    if (header.physicallyClosed) text("Physically closed — financially unresolved", "مغلق فعليًا — غير محسوم ماليًا")
    else text("Physically held — nonsellable and unpriced", "في العهدة فعليًا — غير قابل للبيع وغير مسعر"), buildList {
        add(recordValue(fieldLabel(LocalField.SUPPLIER_ID), literal(header.supplierId.value)))
        add(recordValue(fieldLabel(LocalField.EXPECTED_ARRIVAL_REVISION), literal(header.arrivalRevision.value.toString())))
        add(recordValue(text("Recorded at master", "وقت التسجيل لدى الجهاز الرئيسي"), literal(header.recordedAt.canonicalString())))
        add(recordValue(text("Held partitions (not units)", "الأجزاء في العهدة (ليست عدد الوحدات)"), literal(header.heldSlices.toString())))
        header.supplierDeliveryReference?.let { add(recordValue(fieldLabel(LocalField.SUPPLIER_DELIVERY_REFERENCE), literal(it))) }
        header.localIntakeTag?.let { add(recordValue(fieldLabel(LocalField.LOCAL_INTAKE_TAG), literal(it.value))) }
        header.documentaryPoId?.let { add(recordValue(fieldLabel(LocalField.DOCUMENTARY_PO_ID), literal(it.value))) }
    })

private fun arrivalSliceRecordV2(slice: LocalArrivalSliceViewV1, arrivalId: DomainId): LocalRecord = LocalRecord(
    LocalRecordReference(LocalRecordKind.ARRIVAL_SLICE_V2, slice.custodyUnitId, parentId = arrivalId),
    text("Retained physical partition v2", "جزء فعلي محفوظ v2"), literal(slice.disposition.name), buildList {
        add(recordValue(fieldLabel(LocalField.QUANTITY), quantityText(slice.quantity)))
        add(recordValue(fieldLabel(LocalField.SKU_ID), literal(slice.skuId.value)))
        add(recordValue(fieldLabel(LocalField.WAREHOUSE_ID), literal(slice.warehouseId.value)))
        add(recordValue(fieldLabel(LocalField.LINE_ID), literal(slice.lineId.value)))
        add(recordValue(fieldLabel(LocalField.SOURCE_ID), literal(slice.sourceId.value)))
        add(recordValue(text("Original source / custody unit", "المصدر ووحدة العهدة الأصليان"), literal("${slice.rootSourceId.value} / ${slice.originalCustodyUnitId.value}")))
        slice.parentCustodyUnitId?.let { add(recordValue(text("Parent partition", "الجزء الأصل"), literal(it.value))) }
        add(recordValue(fieldLabel(LocalField.TRACKING), literal(slice.tracking.name)))
        add(recordValue(fieldLabel(LocalField.OWNERSHIP), literal(slice.ownership.name)))
        add(recordValue(fieldLabel(LocalField.LOT_ID), literal(slice.lotId?.value ?: "unobserved / not applicable")))
        add(recordValue(fieldLabel(LocalField.EXPIRY_AT), literal(slice.expiresAt?.canonicalString() ?: "unobserved / unresolved when required")))
        if (slice.tracking == LocalArrivalTrackingV1.SERIAL) {
            add(recordValue(fieldLabel(LocalField.SERIAL_ID), literal(slice.serialId?.value ?: "unresolved; custody ID is not registration")))
            add(recordValue(fieldLabel(LocalField.SERIAL_CODE), literal(slice.serialCode ?: "unobserved")))
            slice.serialRegistrationRevision?.let { add(recordValue(fieldLabel(LocalField.EXPECTED_SERIAL_REGISTRATION_REVISION), literal(it.value.toString()))) }
        }
    })

internal fun arrivalRecordFormV2(supplier: LocalArrivalSupplierViewV2, warehouse: LocalArrivalWarehouseViewV2,
    items: List<LocalArrivalItemViewV2>, options: LocalArrivalEvidenceOptionsV2): LocalActionForm {
    val choices = items.map { LocalReferenceChoice(LocalRecordReference(LocalRecordKind.SKU, it.selection.skuId, it.selection.skuRevision), arrivalNamesV2(it.names)) }
    val columns = listOf(idField(LocalField.SOURCE_ID, readOnly = true), idField(LocalField.CUSTODY_UNIT_ID, readOnly = true),
        scalar(LocalField.SKU_ID, LocalInputKind.REFERENCE, readOnly = true).copy(references = choices), idField(LocalField.PRODUCT_ID, readOnly = true),
        scalar(LocalField.EXPECTED_PRODUCT_REVISION, LocalInputKind.INTEGER, readOnly = true, maximum = 19), idField(LocalField.UNIT, readOnly = true),
        scalar(LocalField.QUANTITY_SCALE, LocalInputKind.INTEGER, readOnly = true, maximum = 1),
        scalar(LocalField.EXPECTED_CATALOG_REVISION, LocalInputKind.INTEGER, readOnly = true, maximum = 19),
        scalar(LocalField.EXPECTED_CATALOG_STORAGE_REVISION, LocalInputKind.INTEGER, readOnly = true, maximum = 19),
        scalar(LocalField.EXPECTED_ITEM_STORAGE_REVISION, LocalInputKind.INTEGER, required = false, readOnly = true, maximum = 19),
        choiceField(LocalField.TRACKING, LocalArrivalTrackingV1.entries.map { LocalChoice(it.name, literal(it.name)) }).copy(readOnly = true),
        scalar(LocalField.REQUIRES_EXPIRY, LocalInputKind.CHECKBOX, required = false, readOnly = true), exactQuantityField(LocalField.QUANTITY),
        idField(LocalField.LOT_ID, required = false), instantField(LocalField.EXPIRY_AT, required = false), idField(LocalField.SERIAL_ID, required = false),
        scalar(LocalField.SERIAL_CODE, LocalInputKind.TEXT, required = false, maximum = 128),
        scalar(LocalField.EXPECTED_SERIAL_REGISTRATION_REVISION, LocalInputKind.INTEGER, required = false, maximum = 19),
        choiceField(LocalField.DISPOSITION, LocalArrivalDispositionV1.entries.filter { it != LocalArrivalDispositionV1.DISPATCHED }.map { LocalChoice(it.name, literal(it.name)) }),
        choiceField(LocalField.OWNERSHIP, LocalArrivalOwnershipV1.entries.map { LocalChoice(it.name, literal(it.name)) }, LocalArrivalOwnershipV1.UNRESOLVED.name),
        scalar(LocalField.OWNERSHIP_REFERENCE, LocalInputKind.TEXT, required = false, maximum = 160))
    val initial = items.map { item -> LocalInputLine(DomainId.random(), buildList {
        val selected = item.selection
        add(LocalScalarInput(LocalField.SOURCE_ID, LocalScalarValue.Identifier(DomainId.random())))
        add(LocalScalarInput(LocalField.CUSTODY_UNIT_ID, LocalScalarValue.Identifier(DomainId.random())))
        add(LocalScalarInput(LocalField.SKU_ID, LocalScalarValue.Reference(choices.single { it.reference.id == selected.skuId }.reference)))
        add(LocalScalarInput(LocalField.PRODUCT_ID, LocalScalarValue.Identifier(selected.productId)))
        add(LocalScalarInput(LocalField.EXPECTED_PRODUCT_REVISION, LocalScalarValue.Integer(selected.productRevision.value)))
        add(LocalScalarInput(LocalField.UNIT, LocalScalarValue.Identifier(selected.baseUnitId)))
        add(LocalScalarInput(LocalField.QUANTITY_SCALE, LocalScalarValue.Integer(selected.quantityScale.toLong())))
        add(LocalScalarInput(LocalField.EXPECTED_CATALOG_REVISION, LocalScalarValue.Integer(selected.catalogRevision.value)))
        add(LocalScalarInput(LocalField.EXPECTED_CATALOG_STORAGE_REVISION, LocalScalarValue.Integer(selected.storageRevision.value)))
        selected.stockStorageRevision?.let { add(LocalScalarInput(LocalField.EXPECTED_ITEM_STORAGE_REVISION, LocalScalarValue.Integer(it.value))) }
        add(LocalScalarInput(LocalField.TRACKING, LocalScalarValue.Choice(checkNotNull(item.tracking).name)))
        add(LocalScalarInput(LocalField.REQUIRES_EXPIRY, LocalScalarValue.Flag(item.requiresExpiry)))
    }) }
    return form(LocalAction.ARRIVAL_RECORD, listOf(newIdField(LocalField.ARRIVAL_ID), idField(LocalField.SUPPLIER_ID, supplier.id),
        revisionField(LocalField.EXPECTED_SUPPLIER_STORAGE_REVISION, supplier.storageRevision), idField(LocalField.WAREHOUSE_ID, warehouse.selection.warehouseId),
        revisionField(LocalField.EXPECTED_WAREHOUSE_STORAGE_REVISION, warehouse.selection.storageRevision),
        scalar(LocalField.SUPPLIER_DELIVERY_REFERENCE, LocalInputKind.TEXT, required = false, maximum = 96),
        idField(LocalField.LOCAL_INTAKE_TAG, required = false), idField(LocalField.DOCUMENTARY_PO_ID, required = false),
        scalar(LocalField.DISCREPANCY_REFERENCE, LocalInputKind.TEXT, required = false, maximum = 160)) + arrivalEvidenceFieldsV2(options) +
        stockReasonField() + LocalFormField.Lines(LocalField.LINES, fieldLabel(LocalField.LINES), columns, maximumLines = 16,
            initialLines = initial, allowBlankLines = false, allowDuplicateLines = true), text(
        "Keep only observed rows and enter exact BASE quantities. Choose supplier delivery reference OR a stable local intake tag. Choose each disposition explicitly: no inspection success is defaulted. Unknown serial portions use whole units; an identified/code-known serial is exactly one indivisible unit. Duplicating a row creates new line/source/custody IDs and clears quantity and serial observations. The full chosen policy/catalog/rule/retention/original manifests are saved unchanged with this operation; no valuation or payment is requested.",
        "احتفظ فقط بالأسطر المرصودة وأدخل كميات أساسية دقيقة. اختر مرجع تسليم المورد أو وسم إدخال محليًا ثابتًا. اختر حالة كل سطر صراحةً؛ لا يُفترض نجاح الفحص. أجزاء المسلسلات المجهولة بوحدات كاملة؛ والمسلسل ذو المعرف أو الرمز المعروف وحدة واحدة لا تنقسم. نسخ السطر يولد هويات سطر ومصدر وعهدة جديدة ويمسح الكمية وملاحظات المسلسل. تُحفظ اختيارات السياسة والكتالوج والقاعدة والاحتفاظ والأصول كاملةً دون تغيير مع العملية؛ لا يُطلب تقييم أو دفع."), version = 2).copy(arrivalEvidence = options)
}

internal fun arrivalTransitionFormV2(action: LocalAction, header: LocalArrivalHeaderV1, slice: LocalArrivalSliceViewV1,
    supplier: LocalArrivalSupplierViewV2, item: LocalArrivalItemViewV2, warehouse: LocalArrivalWarehouseViewV2,
    options: LocalArrivalEvidenceOptionsV2): LocalActionForm {
    val selected = item.selection
    val splittable = slice.tracking != LocalArrivalTrackingV1.SERIAL || slice.serialId == null && slice.serialCode == null
    val fields = listOf(newIdField(LocalField.ARRIVAL_EVENT_ID), idField(LocalField.ARRIVAL_ID, header.arrivalId),
        revisionField(LocalField.EXPECTED_ARRIVAL_REVISION, header.arrivalRevision), idField(LocalField.CUSTODY_UNIT_ID, slice.custodyUnitId),
        idField(LocalField.SUPPLIER_ID, supplier.id), revisionField(LocalField.EXPECTED_SUPPLIER_STORAGE_REVISION, supplier.storageRevision),
        scalar(LocalField.SKU_ID, LocalInputKind.REFERENCE, LocalScalarValue.Reference(LocalRecordReference(LocalRecordKind.SKU, selected.skuId, selected.skuRevision)),
            readOnly = true).copy(references = listOf(LocalReferenceChoice(LocalRecordReference(LocalRecordKind.SKU, selected.skuId, selected.skuRevision), arrivalNamesV2(item.names)))),
        idField(LocalField.PRODUCT_ID, selected.productId), revisionField(LocalField.EXPECTED_PRODUCT_REVISION, selected.productRevision),
        revisionField(LocalField.EXPECTED_CATALOG_REVISION, selected.catalogRevision), revisionField(LocalField.EXPECTED_CATALOG_STORAGE_REVISION, selected.storageRevision),
        scalar(LocalField.EXPECTED_ITEM_STORAGE_REVISION, LocalInputKind.INTEGER, selected.stockStorageRevision?.let { LocalScalarValue.Integer(it.value) },
            required = false, readOnly = true, maximum = 19),
        idField(LocalField.WAREHOUSE_ID, warehouse.selection.warehouseId), revisionField(LocalField.EXPECTED_WAREHOUSE_STORAGE_REVISION, warehouse.selection.storageRevision)) +
        quantityMetadata(slice.quantity) + exactQuantityField(LocalField.QUANTITY) +
        (if (splittable) listOf(newIdField(LocalField.SPLIT_SOURCE_ID), newIdField(LocalField.SPLIT_CUSTODY_UNIT_ID)) else emptyList()) +
        arrivalEvidenceFieldsV2(options) + stockReasonField() + when (action) {
            LocalAction.ARRIVAL_QUALIFY -> (if (slice.tracking == LocalArrivalTrackingV1.SERIAL) listOf(
                idField(LocalField.SERIAL_ID, slice.serialId, required = false, readOnly = slice.serialId != null),
                scalar(LocalField.SERIAL_CODE, LocalInputKind.TEXT, slice.serialCode?.let(LocalScalarValue::Text), required = false,
                    readOnly = slice.serialCode != null, maximum = 128),
                scalar(LocalField.EXPECTED_SERIAL_REGISTRATION_REVISION, LocalInputKind.INTEGER, slice.serialRegistrationRevision?.let { LocalScalarValue.Integer(it.value) },
                    required = false, readOnly = slice.serialId != null, maximum = 19)) else if (slice.tracking == LocalArrivalTrackingV1.LOT) listOf(
                idField(LocalField.LOT_ID, slice.lotId, required = false, readOnly = slice.lotId != null),
                instantField(LocalField.EXPIRY_AT, slice.expiresAt, required = false).copy(readOnly = slice.expiresAt != null)) else emptyList()) +
                scalar(LocalField.INSPECTION_PASSED, LocalInputKind.CHECKBOX, LocalScalarValue.Flag(false), required = false)
            LocalAction.ARRIVAL_DISPATCH -> listOf(idField(LocalField.RECIPIENT_SUPPLIER_ID, header.supplierId))
            LocalAction.ARRIVAL_REJECT -> emptyList()
            else -> invalid()
        }
    return form(action, fields, text(
        "Use one exact remaining quantity at this arrival revision. Strict partial quantities use the reserved NEW source/custody IDs and leave the remainder on its original identities; whole quantities retain identity. Known lot/expiry/serial facts cannot change. Qualify requires a new identification observation or newly passed inspection; splitting alone is not an observation, and rejected held goods cannot be rehabilitated here. Reject keeps goods physically held. Dispatch attests actual departure only to the original supplier. All quantities remain unpriced, nonsellable and financially unresolved.",
        "استخدم كمية متبقية دقيقة واحدة في مراجعة الوصول هذه. تستخدم الكمية الجزئية معرفي المصدر والعهدة الجديدين المحجوزين وتبقي الباقي على هوياته الأصلية؛ الكمية الكاملة تحتفظ بالهوية. لا تتغير بيانات التشغيلة والصلاحية والمسلسل المعروفة. التأهيل يتطلب ملاحظة هوية جديدة أو نجاح فحص جديد؛ التقسيم وحده ليس ملاحظةً، ولا تُعاد أهلية المرفوضات هنا. الرفض يبقي البضاعة في العهدة فعليًا. الإرسال يشهد بالمغادرة الفعلية إلى المورد الأصلي فقط. تبقى الكميات غير مسعرة وغير قابلة للبيع وغير محسومة ماليًا."), version = 2).copy(arrivalEvidence = options)
}
