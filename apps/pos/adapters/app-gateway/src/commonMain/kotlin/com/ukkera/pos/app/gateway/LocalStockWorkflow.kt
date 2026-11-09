package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.LocalConfigurationProfile
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.inventory.StockCustody
import com.ukkera.pos.inventory.StockSafety
import com.ukkera.pos.inventory.StockSerialRegistrationStatus
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision

internal data class StockQuantityCut(val page: LocalStockQuantityPage, val reply: ReadReply.Data)

internal suspend fun stockQuantities(access: GatewayAccess, skuId: DomainId, positionId: DomainId? = null,
                                     after: DomainId? = null, limit: Int = 25): StockQuantityCut {
    val reply = access.read(if (positionId == null) LocalStockCommands.quantities(skuId, after, limit)
        else LocalStockCommands.quantityPosition(skuId, positionId))
    val page = LocalStockQueries.decodeQuantities(reply.payload)
    if (page.skuId != skuId || page.rows.size > (if (positionId == null) limit else 1) ||
        page.rows.any { it.position.skuId != skuId || positionId != null && it.positionId != positionId } ||
        positionId != null && page.nextAfter != null) unavailable()
    val itemRevision = page.itemStorageRevision
    if (itemRevision == null) {
        if (page.rows.isNotEmpty() || page.unitId != null || page.quantityScale != null) unavailable()
    } else if (page.unitId == null || page.quantityScale == null || itemRevision.value <= 0) unavailable()
    return StockQuantityCut(page, reply)
}

internal suspend fun stockWorkflow(access: GatewayAccess, query: LocalWorkflowQuery): LocalWorkflowPage {
    if (access.installed.isStockTransfersOnly) {
        requireStockTransferWorkflow(query)
        requireNormalizedTransferStorage(access)
    }
    if (query.inventory == LocalInventorySection.SETUP) return stockSetupWorkflow(access, query)
    if (query.inventory == LocalInventorySection.OPENING) return openingStockWorkflow(access, query)
    if (isArrivalRouteV2(query)) return unpricedArrivalWorkflowV2(access, query)
    if (isArrivalRoute(query)) return unpricedArrivalWorkflow(access, query)
    val selected = query.reference ?: when {
        query.search.startsWith("arrival:") -> LocalRecordReference(LocalRecordKind.ARRIVAL, searchedId(query.search.removePrefix("arrival:")))
        query.search.startsWith("receipt:") -> LocalRecordReference(LocalRecordKind.RECEIPT, searchedId(query.search.removePrefix("receipt:")))
        query.search.startsWith("basis:") -> LocalRecordReference(LocalRecordKind.RECEIVING_BASIS, searchedId(query.search.removePrefix("basis:")))
        query.search.startsWith("count:") -> LocalRecordReference(LocalRecordKind.COUNT, searchedId(query.search.removePrefix("count:")))
        query.search.startsWith("surplus:") -> LocalRecordReference(LocalRecordKind.COUNT_SURPLUS_BASIS, searchedId(query.search.removePrefix("surplus:")))
        query.search.startsWith("transfer:") -> LocalRecordReference(LocalRecordKind.TRANSFER, searchedId(query.search.removePrefix("transfer:")))
        query.search.startsWith("sku:") -> LocalRecordReference(LocalRecordKind.SKU, searchedId(query.search.removePrefix("sku:")))
        else -> null
    }
    if (selected?.kind == LocalRecordKind.ARRIVAL) return unpricedArrivalWorkflow(access, query.copy(reference = selected))
    if (selected?.kind in setOf(LocalRecordKind.RECEIPT, LocalRecordKind.RECEIPT_LINE, LocalRecordKind.RECEIVING_BASIS, LocalRecordKind.RECEIVING_ALLOCATION) ||
        selected == null && query.workflow == LocalWorkflow.RECEIVING) return receivingWorkflow(access, query.copy(reference = selected))
    if (query.workflow != LocalWorkflow.INVENTORY) invalid()
    return when (selected?.kind) {
        LocalRecordKind.COUNT -> countWorkflow(access, query, selected.id)
        LocalRecordKind.COUNT_SURPLUS_BASIS -> countSurplusWorkflow(access, query, selected.id)
        LocalRecordKind.TRANSFER -> stockTransferWorkflow(access, query, selected.id)
        LocalRecordKind.SKU -> if (query.inventory == LocalInventorySection.COSTS) stockCostWorkflow(access, query, selected.id)
            else stockSkuWorkflow(access, query, selected.id)
        LocalRecordKind.STOCK_CUT -> stockPositionWorkflow(access, query, selected)
        null -> {
            val read = access.read(LocalStockCommands.items(parseCursor(query.cursor, "stock-items"), 25))
            val page = LocalStockQueries.decodeItems(read.payload)
            LocalWorkflowPage(query.workflow, page.items.filter { query.search.isBlank() || it.skuId.value.contains(query.search) }.map { item ->
                LocalRecord(LocalRecordReference(LocalRecordKind.SKU, item.skuId), text("Stock item", "صنف مخزون"), values = listOf(
                    recordValue(fieldLabel(LocalField.SKU_ID), literal(item.skuId.value)),
                    recordValue(fieldLabel(LocalField.EXPECTED_ITEM_STORAGE_REVISION), literal(item.storageRevision.value.toString())),
                    recordValue(fieldLabel(LocalField.UNIT), literal(item.unitId.value)),
                    recordValue(fieldLabel(LocalField.QUANTITY_SCALE), literal(item.quantityScale.toString())),
                    recordValue(text("Positions", "مواضع المخزون"), literal(item.positionCount.toString()))))
            }, emptyList(), if (access.installed.isStockTransfersOnly) text(
                "Current authorized stock item page only. Select an item and exact position for a same-branch transfer, or use sku:ID or transfer:ID. Costs and value history require separate permission. ${readCut(read).english}",
                "صفحة أصناف المخزون المصرح بها حاليًا فقط. اختر صنفًا وموضعًا دقيقًا للنقل داخل الفرع، أو استخدم sku:ID أو transfer:ID. تتطلب التكاليف وتاريخ القيمة صلاحية منفصلة. ${readCut(read).arabic}")
            else text("Current authorized item page only. Use sku:ID, count:ID, surplus:ID, transfer:ID or basis:ID for an exact lookup. Surplus-value lookup requires cost permission; no costs or missing-item zero balances are inferred. ${readCut(read).english}",
                "صفحة الأصناف المصرح بها حاليًا فقط. استخدم sku:ID أو count:ID أو surplus:ID أو transfer:ID أو basis:ID للبحث المحدد. تتطلب قراءة قيمة الزيادة صلاحية التكلفة؛ لا تُستنتج تكاليف أو أرصدة صفرية للأصناف المفقودة. ${readCut(read).arabic}"),
                cursor("stock-items", page.nextAfter))
        }
        else -> invalid()
    }
}

private suspend fun stockSkuWorkflow(access: GatewayAccess, query: LocalWorkflowQuery, skuId: DomainId): LocalWorkflowPage {
    val cut = stockQuantities(access, skuId, after = parseCursor(query.cursor, "stock-positions"))
    val page = cut.page
    val revision = page.itemStorageRevision
    if (revision == null) return LocalWorkflowPage(query.workflow, emptyList(), emptyList(),
        text("No stock item is available for that SKU at this authorized read cut. This is not a zero balance or permission to create opening stock.",
            "لا يوجد سجل مخزون متاح لهذا الصنف في مراجعة القراءة المصرح بها. هذا ليس رصيدًا صفريًا أو إذنًا بإنشاء مخزون افتتاحي."))
    val header = LocalRecord(LocalRecordReference(LocalRecordKind.SKU, skuId), text("Stock positions by custody", "مواضع المخزون حسب العهدة"), values = listOf(
        recordValue(fieldLabel(LocalField.EXPECTED_ITEM_STORAGE_REVISION), literal(revision.value.toString())),
        recordValue(fieldLabel(LocalField.UNIT), literal(checkNotNull(page.unitId).value)),
        recordValue(fieldLabel(LocalField.QUANTITY_SCALE), literal(checkNotNull(page.quantityScale).toString()))))
    return LocalWorkflowPage(query.workflow, listOf(header) + page.rows.map(::stockPositionRecord), emptyList(),
        if (access.installed.isStockTransfersOnly) text(
            "Select an exact current position for a same-branch transfer. Cost is not part of this quantity projection. ${readCut(cut.reply).english}",
            "اختر موضعًا حاليًا دقيقًا للنقل داخل الفرع. التكلفة ليست جزءًا من عرض الكميات هذا. ${readCut(cut.reply).arabic}")
        else text("Select a position for an exact current read before counting, recall or same-branch transfer. Cost is not part of this quantity projection. ${readCut(cut.reply).english}",
            "اختر موضعًا لقراءة حالته الحالية الدقيقة قبل الجرد أو الاستدعاء أو النقل داخل الفرع. التكلفة ليست جزءًا من عرض الكميات هذا. ${readCut(cut.reply).arabic}"),
        cursor("stock-positions", page.nextAfter))
}

private suspend fun stockPositionWorkflow(access: GatewayAccess, query: LocalWorkflowQuery, reference: LocalRecordReference): LocalWorkflowPage {
    val skuId = reference.parentId ?: invalid(LocalField.POSITION_ID)
    val cut = stockQuantities(access, skuId, reference.id)
    val row = cut.page.rows.singleOrNull() ?: return LocalWorkflowPage(query.workflow, emptyList(), emptyList(),
        text("That exact stock position is not available at this read cut; no replacement position was selected.",
            "موضع المخزون المحدد غير متاح في مراجعة القراءة هذه؛ لم يتم اختيار موضع بديل."))
    if (query.search.trim().equals("history", ignoreCase = true) && access.installed.has(LocalStockRelationalQueriesV2.HISTORY)) {
        val after = query.cursor?.let { cursor ->
            if (!cursor.startsWith("stock-history:")) invalid()
            cursor.removePrefix("stock-history:").toLongOrNull()?.takeIf { it >= 0 } ?: invalid()
        } ?: 0L
        val reply = access.read(LocalStockRelationalQueriesV2.history(skuId, row.position.warehouseId, after))
        val history = LocalStockRelationalQueriesV2.decodeHistory(reply.payload)
        if (history.skuId != skuId || history.warehouseId != row.position.warehouseId) unavailable()
        return LocalWorkflowPage(query.workflow, history.rows.map { movement ->
            LocalRecord(LocalRecordReference(LocalRecordKind.STOCK_CUT, movement.sourceId, parentId = skuId), literal(movement.movement), values = listOf(
                recordValue(text("Source", "المصدر"), literal(movement.sourceId.value)),
                recordValue(text("Sequence", "التسلسل"), literal(movement.ordinal.toString())),
                recordValue(text("Quantity movement", "حركة الكمية"), quantityText(movement.quantityDelta)),
                recordValue(text("Original posted value movement", "حركة القيمة الأصلية المسجلة"), moneyText(movement.originalValueDelta)),
                recordValue(text("Carrying-value movement", "حركة القيمة الدفترية"), moneyText(movement.carryingValueDelta)),
                recordValue(text("Resulting carrying value", "القيمة الدفترية الناتجة"), moneyText(movement.resultingValue)),
                recordValue(text("Recorded at", "وقت التسجيل"), literal(movement.recordedAt.canonicalString()))), selectable = false)
        }, emptyList(), text("Complete verified SKU/warehouse value history, paged by original sequence. This is not a repriced sale or a new movement. ${readCut(reply).english}",
            "تاريخ قيمة الصنف والمخزن بعد التحقق الكامل، مقسم حسب التسلسل الأصلي. ليس إعادة تسعير لبيع أو حركة جديدة. ${readCut(reply).arabic}"),
            history.nextOrdinal?.let { "stock-history:$it" })
    }
    val itemRevision = cut.page.itemStorageRevision ?: unavailable()
    val normalized = access.installed.has(LocalStockRelationalQueriesV2.PROFILE) &&
        LocalStockRelationalQueriesV2.normalized(access.read(LocalStockRelationalQueriesV2.profile()).payload)
    val serialized = row.position.serialId != null
    val base = positionFields(row) + revisionField(LocalField.EXPECTED_ITEM_STORAGE_REVISION, itemRevision)
    val forms = mutableListOf<LocalActionForm>()
    if (access.installed.has(if (serialized) LocalStockStoreProfile.COUNT_CAPTURE_SERIAL else LocalStockStoreProfile.COUNT_CAPTURE) && access.allow(LocalAction.COUNT_CAPTURE, skuId))
        forms += form(LocalAction.COUNT_CAPTURE, listOf(newIdField(LocalField.COUNT_ID)) + base + stockReasonField(),
            text("Capture this position's real revision and quantity before counting. A later change requires a recount, not an amended saved count.",
                "ثبّت المراجعة والكمية الفعليتين لهذا الموضع قبل الجرد. أي تغيير لاحق يتطلب إعادة الجرد، لا تعديل جرد محفوظ."))
    if (row.position.lotId != null && access.installed.has(if (serialized) LocalStockStoreProfile.RECALL_SERIAL else LocalStockStoreProfile.RECALL) && access.allow(LocalAction.STOCK_RECALL, skuId))
        forms += form(LocalAction.STOCK_RECALL, base + stockReasonField(),
            text("Recall applies to this exact SKU/lot across its stock positions. It does not delete stock, undo collected money or finalize a sale.",
                "يطبق الاستدعاء على هذا الصنف وهذه التشغيلة عبر مواضع المخزون. لا يحذف المخزون أو يعكس أموالًا محصلة أو يثبت بيعًا."))
    val ownedReleased = row.position.supplierId == null && row.position.custody == StockCustody.RELEASED
    val transferKey = if (normalized) LocalStockRelationalProfileV2.COMMAND
        else if (row.position.supplierId == null) LocalStockStoreProfile.TRANSFER_OWNED
        else if (serialized) LocalStockStoreProfile.TRANSFER_SERIAL else LocalStockStoreProfile.TRANSFER
    val transferable = !normalized || ownedReleased && row.safety == StockSafety.CLEAR && !row.expiredAtRead &&
        row.available.scaled > 0 && (!serialized || row.serialRegistrationStatus == StockSerialRegistrationStatus.REGISTERED)
    val transferHint = if (access.installed.isStockTransfersOnly) stockTransferFormHint(access)
        else access.allow(LocalAction.WAREHOUSE_TRANSFER, skuId)
    if (transferable && access.installed.has(transferKey) && access.installed.has(LocalStockStoreProfile.TRANSFER_DETAIL) &&
        access.installed.has(LocalConfigurationProfile.QUERY) && transferHint) {
        val transferId = if (access.installed.isStockTransfersOnly)
            idField(LocalField.TRANSFER_ID, DomainId.random(), readOnly = false).copy(help = text(
                "Choose this new transfer's exact ID before saving. Current permission is checked for this document; the suggested ID grants nothing.",
                "اختر المعرف الدقيق لهذا النقل الجديد قبل الحفظ. تُفحص الصلاحية السارية لهذا المستند؛ ولا يمنح المعرف المقترح أي إذن."))
            else newIdField(LocalField.TRANSFER_ID)
        forms += form(LocalAction.WAREHOUSE_TRANSFER,
            listOf(transferId, newIdField(LocalField.SOURCE_EVENT_ID), newIdField(LocalField.DESTINATION_EVENT_ID)) + base + listOf(
                pagedReferenceField(LocalField.DESTINATION_ID, LocalRecordKind.WAREHOUSE, excludedId = row.position.warehouseId),
                exactQuantityField(LocalField.QUANTITY)) + stockReasonField(),
            text("One atomic same-branch warehouse move with original lot, ownership, custody and value lineage. Reserved or held stock cannot be moved. Browse active destination pages or look up an exact ID; the source warehouse cannot be selected.",
                "نقل ذري واحد بين مخازن الفرع مع الحفاظ على التشغيلة والملكية والعهدة وتسلسل القيمة. لا يمكن نقل المخزون المحجوز أو المقيد. تصفح صفحات الوجهات النشطة أو ابحث بالمعرف الدقيق؛ لا يمكن اختيار مخزن المصدر."))
    }
    val notice = if (normalized) text(
        "Transfers are available only for unreserved, safe, released company stock within this branch. Supplier-held/quarantined transfers, cross-branch moves and linked cost corrections are not available in this profile. Enter history for this SKU/warehouse's verified value history (cost permission required). ${readCut(cut.reply).english}",
        "النقل متاح فقط لمخزون الشركة الآمن والمفرج عنه وغير المحجوز داخل هذا الفرع. نقل عهدة المورد أو المخزون المحجور والنقل بين الفروع وتصحيحات التكلفة المترابطة غير متاحة في هذا النمط. أدخل history لعرض تاريخ قيمة الصنف والمخزن المتحقق منه (يتطلب صلاحية التكلفة). ${readCut(cut.reply).arabic}")
        else if (access.installed.has(LocalStockRelationalQueriesV2.HISTORY)) text(
            "Enter history in search on this position for its verified SKU/warehouse value history (cost permission required). ${readCut(cut.reply).english}",
            "أدخل history في البحث لهذا الموضع لعرض تاريخ قيمة الصنف والمخزن المتحقق منه (يتطلب صلاحية التكلفة). ${readCut(cut.reply).arabic}")
        else readCut(cut.reply)
    return LocalWorkflowPage(query.workflow, listOf(stockPositionRecord(row)), forms, notice)
}

/** Quantity and value remain separate protected reads, including on the ACK destination. */
private suspend fun stockTransferWorkflow(access: GatewayAccess, query: LocalWorkflowQuery, id: DomainId): LocalWorkflowPage {
    val costs = query.inventory == LocalInventorySection.COSTS
    val read = access.read(if (costs) LocalStockCommands.transferCosts(id) else LocalStockCommands.transferDetail(id))
    val transfer = LocalStockQueries.decodeTransfer(read.payload) ?: return LocalWorkflowPage(query.workflow, emptyList(), emptyList(),
        text("No transfer is available for that exact ID at this authorized read cut.", "لا يوجد نقل متاح لهذا المعرف المحدد في مراجعة القراءة المصرح بها."))
    if (transfer.transferId != id || transfer.costsIncluded != costs) unavailable()
    val record = LocalRecord(LocalRecordReference(LocalRecordKind.TRANSFER, id), text("Same-branch warehouse transfer", "نقل بين مخازن الفرع"),
        text("Recorded", "مسجل"), buildList {
            add(recordValue(fieldLabel(LocalField.TRANSFER_ID), literal(id.value)))
            addAll(stockCoordinateValues(transfer.source))
            add(recordValue(text("Destination warehouse", "المخزن الوجهة"), literal(transfer.destination.warehouseId.value)))
            add(recordValue(fieldLabel(LocalField.QUANTITY), quantityText(transfer.quantity)))
            add(recordValue(fieldLabel(LocalField.SOURCE_EVENT_ID), literal(transfer.sourceEventId.value)))
            add(recordValue(fieldLabel(LocalField.DESTINATION_EVENT_ID), literal(transfer.destinationEventId.value)))
            add(recordValue(text("Transferred by", "نُقل بواسطة"), literal(transfer.transferredBy.value)))
            add(recordValue(text("Recorded at", "وقت التسجيل"), literal(transfer.recordedAt.canonicalString())))
            add(recordValue(fieldLabel(LocalField.REASON), literal(transfer.reasonCode)))
            if (costs) {
                transfer.carriedValue?.let { add(recordValue(text("Value carried at transfer", "القيمة المنقولة وقت النقل"), moneyText(it))) }
                transfer.linkedCostDelta?.let { add(recordValue(text("Linked cost adjustment", "تعديل التكلفة المترابط"), moneyText(it))) }
                transfer.referenceCarriedValue?.let { add(recordValue(text("Current reference value", "القيمة المرجعية الحالية"), moneyText(it))) }
                transfer.valuationCalculationVersion?.let { add(recordValue(text("Valuation calculation", "حساب التقييم"), literal(it))) }
                if (transfer.source.supplierId != null) add(recordValue(text("Owned value", "القيمة المملوكة"),
                    text("Supplier custody; no company-owned value", "عهدة المورد؛ لا قيمة مملوكة للشركة")))
            }
        }, selectable = false)
    val serial = transfer.serial?.let { value -> LocalRecord(
        LocalRecordReference(LocalRecordKind.RECEIPT_LINE, value.originalReceiptLineId, parentId = value.originalReceiptId),
        text("Serial provenance and current custody", "مصدر الوحدة المسلسلة وعهدتها الحالية"), values = buildList {
            add(recordValue(fieldLabel(LocalField.SERIAL_ID), literal(value.serialId.value)))
            add(recordValue(fieldLabel(LocalField.SERIAL_CODE), literal(value.serialCode)))
            add(recordValue(text("Registration", "التسجيل"), serialStatusText(value.registrationStatus)))
            value.registrationId?.let { add(recordValue(text("Registration ID", "معرف التسجيل"), literal(it.value))) }
            add(recordValue(text("Original receipt", "إيصال الاستلام الأصلي"), literal(value.originalReceiptId.value)))
            add(recordValue(text("Original receipt line", "سطر الاستلام الأصلي"), literal(value.originalReceiptLineId.value)))
            add(recordValue(text("Original source", "المصدر الأصلي"), literal(value.originalSourceId.value)))
            add(recordValue(text("Original warehouse", "المخزن الأصلي"), literal(value.originalPosition.warehouseId.value)))
            add(recordValue(text("Originally received at", "وقت الاستلام الأصلي"), literal(value.originallyReceivedAt.canonicalString())))
            add(recordValue(text("Current on hand", "الموجود حاليًا"), quantityText(value.currentOnHand)))
            value.currentPosition?.let { addAll(stockCoordinateValues(it)) } ?: add(recordValue(text("Current custody", "العهدة الحالية"),
                text("Not currently on hand; original identity retained", "غير موجود حاليًا؛ الهوية الأصلية محفوظة")))
            value.currentSafety?.let { add(recordValue(text("Current safety", "السلامة الحالية"), literal(it.name))) }
            value.currentExpiresAt?.let { add(recordValue(fieldLabel(LocalField.EXPIRY_AT), literal(it.canonicalString()))) }
        }, selectable = false) }
    if (access.installed.isStockTransfersOnly && !costs) {
        // The committed document is not a current balance. Post-ACK quantity refresh uses the
        // same protected SKU read/navigation as item browsing, without rebuilding the command.
        val current = stockSkuWorkflow(access, query, transfer.source.skuId)
        val currentNotice = checkNotNull(current.notice)
        return LocalWorkflowPage(query.workflow, listOfNotNull(record, serial) + current.records, emptyList(), text(
            "The posted transfer is immutable. ${readCut(read).english} Current SKU quantities below come from a separate authorized read, not the balances frozen at transfer. Costs require a separate authorized value read. ${currentNotice.english}",
            "النقل المسجل غير قابل للتعديل. ${readCut(read).arabic} تأتي كميات الصنف الحالية أدناه من قراءة مصرح بها منفصلة، لا من الأرصدة المثبتة وقت النقل. تتطلب التكاليف قراءة قيمة مصرح بها منفصلة. ${currentNotice.arabic}"),
            current.nextCursor)
    }
    return LocalWorkflowPage(query.workflow, listOfNotNull(record, serial), emptyList(), text(
        "The posted transfer is immutable. Quantity view hides costs; use Costs with transfer:ID for a separately authorized value read. Current serial custody can differ from the original destination. ${readCut(read).english}",
        "النقل المسجل غير قابل للتعديل. يخفي عرض الكميات التكلفة؛ استخدم قسم التكاليف مع transfer:ID لقراءة القيمة بصلاحية منفصلة. قد تختلف عهدة الوحدة المسلسلة الحالية عن الوجهة الأصلية. ${readCut(read).arabic}"))
}

private suspend fun countWorkflow(access: GatewayAccess, query: LocalWorkflowQuery, countId: DomainId): LocalWorkflowPage {
    val read = access.read(LocalStockCommands.count(countId))
    val count = LocalStockQueries.decodeCount(read.payload) ?: return LocalWorkflowPage(query.workflow, emptyList(), emptyList(),
        text("No count document is available for that ID.", "لا يوجد مستند جرد متاح لهذا المعرف."))
    if (count.countId != countId) unavailable()
    val record = LocalRecord(LocalRecordReference(LocalRecordKind.COUNT, countId), text("Captured stock count", "جرد مخزون مثبت"),
        if (count.counted == null) text("Awaiting exact count", "بانتظار الجرد الدقيق") else text("Posted", "مُرحّل"), buildList {
            addAll(stockCoordinateValues(count.position))
            add(recordValue(fieldLabel(LocalField.EXPECTED_COUNT_REVISION), literal(count.storageRevision.value.toString())))
            add(recordValue(text("Captured item storage revision", "مراجعة تخزين الصنف عند التثبيت"), literal(count.capturedItemRevision.value.toString())))
            add(recordValue(text("Captured quantity", "الكمية المثبتة"), quantityText(count.capturedQuantity)))
            add(recordValue(text("Captured at", "وقت التثبيت"), literal(count.capturedAt.canonicalString())))
            count.counted?.let { add(recordValue(fieldLabel(LocalField.COUNTED_QUANTITY), quantityText(it))) }
            count.postedAt?.let { add(recordValue(text("Posted at", "وقت الترحيل"), literal(it.canonicalString()))) }
            count.surplusBasisId?.let { add(recordValue(text("Approved surplus basis", "أساس الزيادة المعتمد"), literal(it.value))) }
        })
    val forms = if (count.counted == null && access.installed.has(if (count.position.serialId != null) LocalStockStoreProfile.COUNT_POST_SERIAL else LocalStockStoreProfile.COUNT_POST) &&
        access.allow(LocalAction.COUNT_POST, countId)) listOf(
        form(LocalAction.COUNT_POST, listOf(idField(LocalField.COUNT_ID, countId), revisionField(LocalField.EXPECTED_COUNT_REVISION, count.storageRevision)) +
            quantityMetadata(count.capturedQuantity) + listOf(idField(LocalField.SERIAL_ID, count.position.serialId, required = false, readOnly = true),
                exactQuantityField(LocalField.COUNTED_QUANTITY), stockReasonField()),
            text("This form posts an exact no-surplus count. For a positive nonserial surplus, record its separate approved value basis, acknowledge it, then open that basis for exact posting (surplus:ID). A serial count is zero or one whole base unit, never a gain.",
                "يرحل هذا النموذج جردًا دقيقًا دون زيادة. للزيادة غير المسلسلة، سجل أساس قيمتها المعتمدة المنفصل ثم أكد النتيجة وافتح الأساس لترحيله بدقة (surplus:ID). جرد الوحدة المسلسلة صفر أو وحدة أساسية كاملة، ولا يسمح بزيادة."))) else emptyList()
    return LocalWorkflowPage(query.workflow, listOf(record), forms + listOfNotNull(countSurplusForm(access, count)), readCut(read))
}

internal fun stockCoordinateValues(position: LocalStockPositionRef): List<LocalRecordValue> = buildList {
    add(recordValue(fieldLabel(LocalField.SKU_ID), literal(position.skuId.value)))
    add(recordValue(fieldLabel(LocalField.WAREHOUSE_ID), literal(position.warehouseId.value)))
    add(recordValue(fieldLabel(LocalField.CUSTODY), literal(position.custody.name)))
    add(recordValue(text("Ownership", "الملكية"), position.supplierId?.let { text("Supplier ${it.value}", "المورد ${it.value}") }
        ?: text("Company owned", "مملوك للشركة")))
    position.lotId?.let { add(recordValue(fieldLabel(LocalField.LOT_ID), literal(it.value))) }
    position.serialId?.let { add(recordValue(fieldLabel(LocalField.SERIAL_ID), literal(it.value))) }
}

private fun stockPositionRecord(row: LocalStockQuantityRow): LocalRecord = LocalRecord(
    LocalRecordReference(LocalRecordKind.STOCK_CUT, row.positionId, parentId = row.position.skuId), text("Exact stock position", "موضع مخزون محدد"),
    literal(row.safety.name), stockCoordinateValues(row.position) + buildList {
        add(recordValue(text("On hand", "الموجود فعليًا"), quantityText(row.onHand)))
        add(recordValue(text("Reserved", "المحجوز"), quantityText(row.reserved)))
        add(recordValue(text("Held reservation claims", "مطالبات الحجز المقيدة"), row.heldQuantity?.let(::quantityText)
            ?: text("Not projected by legacy response; not zero", "غير معروض في الرد القديم؛ ليس صفرًا")))
        add(recordValue(text("Held physical shortage", "العجز الفعلي المحمي بقيد"), row.heldShortageQuantity?.let(::quantityText)
            ?: text("Not projected by legacy response; not zero", "غير معروض في الرد القديم؛ ليس صفرًا")))
        add(recordValue(text("Available", "المتاح"), quantityText(row.available)))
        row.serialCode?.let { add(recordValue(fieldLabel(LocalField.SERIAL_CODE), literal(it))) }
        row.serialRegistrationStatus?.let { add(recordValue(text("Registration at this stock read", "التسجيل في قراءة المخزون هذه"), serialStatusText(it))) }
        row.expiresAt?.let { add(recordValue(fieldLabel(LocalField.EXPIRY_AT), literal(it.canonicalString()))) }
        add(recordValue(text("Expired at read", "منتهي عند القراءة"), if (row.expiredAtRead) text("Yes", "نعم") else text("No", "لا")))
    })

private fun positionFields(row: LocalStockQuantityRow): List<LocalFormField> = listOf(
    scalar(LocalField.POSITION_ID, LocalInputKind.REFERENCE, LocalScalarValue.Reference(
        LocalRecordReference(LocalRecordKind.STOCK_CUT, row.positionId, parentId = row.position.skuId)), readOnly = true).copy(references = listOf(
            LocalReferenceChoice(LocalRecordReference(LocalRecordKind.STOCK_CUT, row.positionId, parentId = row.position.skuId), literal(row.positionId.value)))),
    idField(LocalField.WAREHOUSE_ID, row.position.warehouseId), idField(LocalField.LOT_ID, row.position.lotId, required = false, readOnly = true),
    idField(LocalField.SERIAL_ID, row.position.serialId, required = false, readOnly = true),
    idField(LocalField.SUPPLIER_ID, row.position.supplierId, required = false, readOnly = true),
    choiceField(LocalField.CUSTODY, StockCustody.entries.map { LocalChoice(it.name, literal(it.name)) }, row.position.custody.name).copy(readOnly = true),
) + quantityMetadata(row.onHand)

internal fun quantityMetadata(quantity: Quantity): List<LocalFormField> = listOf(idField(LocalField.UNIT, quantity.unitId),
    scalar(LocalField.QUANTITY_SCALE, LocalInputKind.INTEGER, LocalScalarValue.Integer(quantity.scale.toLong()), readOnly = true, maximum = 1))
internal fun exactQuantityField(field: LocalField): LocalFormField.Scalar = scalar(field, LocalInputKind.DECIMAL, maximum = 32,
    help = text("Exact quantity in the displayed unit and precision, without rounding.", "كمية دقيقة بالوحدة والدقة المعروضتين، دون تقريب."))
internal fun stockReasonField(): LocalFormField.Scalar = scalar(LocalField.REASON, LocalInputKind.TEXT, maximum = 96,
    help = text("Stable reason code: lowercase a-z, digits, dot, underscore or hyphen. Retain the original supporting evidence.",
        "رمز سبب ثابت: أحرف a-z صغيرة أو أرقام أو نقطة أو شرطة سفلية أو شرطة. احتفظ بالإثبات الأصلي الداعم."))

internal fun InputValues.exactQuantity(field: LocalField, actual: Quantity): Quantity {
    if (id(LocalField.UNIT) != actual.unitId || integer(LocalField.QUANTITY_SCALE) != actual.scale.toLong()) invalid(LocalField.UNIT)
    return try {
        Quantity.parse(actual.unitId, decimal(field), actual.scale)
    } catch (_: ArithmeticException) {
        // Keep range failure attached to this input, not an invented uncertain journal outcome.
        invalid(field)
    }
}

private suspend fun positionInput(access: GatewayAccess, input: InputValues): Pair<LocalStockQuantityRow, Revision> {
    val reference = input.reference(LocalField.POSITION_ID, LocalRecordKind.STOCK_CUT)
    val cut = stockQuantities(access, reference.parentId ?: invalid(LocalField.POSITION_ID), reference.id)
    val row = cut.page.rows.singleOrNull() ?: invalid(LocalField.POSITION_ID)
    val revision = input.revision(LocalField.EXPECTED_ITEM_STORAGE_REVISION)
    if (cut.page.itemStorageRevision != revision) invalid(LocalField.EXPECTED_ITEM_STORAGE_REVISION)
    if (input.id(LocalField.WAREHOUSE_ID) != row.position.warehouseId || input.optionalId(LocalField.LOT_ID) != row.position.lotId ||
        input.optionalId(LocalField.SERIAL_ID) != row.position.serialId ||
        input.optionalId(LocalField.SUPPLIER_ID) != row.position.supplierId || input.choice(LocalField.CUSTODY) != row.position.custody.name ||
        input.id(LocalField.UNIT) != row.onHand.unitId || input.integer(LocalField.QUANTITY_SCALE) != row.onHand.scale.toLong()) invalid(LocalField.POSITION_ID)
    return row to revision
}

internal suspend fun prepareStock(access: GatewayAccess, intent: LocalCommandIntent): MutationRequest {
    if (access.installed.isStockTransfersOnly) {
        requireStockTransferIntent(intent)
        requireNormalizedTransferStorage(access)
    }
    if (intent.action == LocalAction.STOCK_INITIALIZE) return prepareStockInitialization(access, intent)
    if (intent.action in setOf(LocalAction.RECEIPT_POST, LocalAction.STOCK_OPENING, LocalAction.INSPECTION_RELEASE))
        return prepareReceiving(access, intent)
    if (intent.lines.isNotEmpty() || intent.lineField != null) invalid(LocalField.LINES)
    val input = InputValues(intent.values)
    if (access.installed.isStockTransfersOnly &&
        input.reference(LocalField.POSITION_ID, LocalRecordKind.STOCK_CUT) != intent.reference) invalid(LocalField.POSITION_ID)
    val request = when (intent.action) {
        LocalAction.COUNT_POST -> {
            val id = input.id(LocalField.COUNT_ID)
            access.requireAction(intent.action, id)
            val count = LocalStockQueries.decodeCount(access.read(LocalStockCommands.count(id)).payload) ?: invalid(LocalField.COUNT_ID)
            val expected = input.revision(LocalField.EXPECTED_COUNT_REVISION)
            if (count.countId != id || count.storageRevision != expected || count.counted != null) invalid(LocalField.EXPECTED_COUNT_REVISION)
            val counted = input.exactQuantity(LocalField.COUNTED_QUANTITY, count.capturedQuantity)
            val serialId = input.optionalId(LocalField.SERIAL_ID)
            if (serialId != count.position.serialId) invalid(LocalField.SERIAL_ID)
            if (serialId != null && counted.scaled != 0L && counted != Quantity.parse(counted.unitId, "1", counted.scale)) invalid(LocalField.COUNTED_QUANTITY)
            val basisId = input.optionalId(LocalField.COUNT_SURPLUS_BASIS_ID)
            val basisRevision = input.optionalRevision(LocalField.EXPECTED_SURPLUS_AUTHORITY_REVISION)
            if (counted > count.capturedQuantity) {
                if (serialId != null || basisId == null || basisRevision == null) invalid(LocalField.COUNT_SURPLUS_BASIS_ID)
                val basis = countSurplusRead(access, basisId).approval ?: invalid(LocalField.COUNT_SURPLUS_BASIS_ID)
                if (basis.consumed || basis.countId != id || basis.countRevision != expected || basis.capturedQuantity != count.capturedQuantity ||
                    basis.countedQuantity != counted) invalid(LocalField.COUNT_SURPLUS_BASIS_ID)
                if (basis.authorityRevision != basisRevision) invalid(LocalField.EXPECTED_SURPLUS_AUTHORITY_REVISION)
            } else if (basisId != null || basisRevision != null) invalid(LocalField.COUNT_SURPLUS_BASIS_ID)
            LocalStockCommands.postCount(intent.operationId, LocalCountPostRequest(id, expected, counted, input.text(LocalField.REASON),
                basisId, basisRevision, serialId))
        }
        LocalAction.COUNT_CAPTURE, LocalAction.STOCK_RECALL, LocalAction.WAREHOUSE_TRANSFER -> {
            val (row, revision) = positionInput(access, input)
            access.requireAction(intent.action, if (access.installed.isStockTransfersOnly)
                input.id(LocalField.TRANSFER_ID) else row.position.skuId)
            when (intent.action) {
                LocalAction.COUNT_CAPTURE -> LocalStockCommands.captureCount(intent.operationId, LocalCountCaptureRequest(
                    input.id(LocalField.COUNT_ID), row.position, revision, input.text(LocalField.REASON)))
                LocalAction.STOCK_RECALL -> {
                    val recall = LocalLotRecallRequest(row.position.skuId, row.position.lotId ?: invalid(LocalField.LOT_ID), revision, input.text(LocalField.REASON))
                    if (row.position.serialId != null) LocalStockCommands.recallSerials(intent.operationId, recall)
                    else LocalStockCommands.recall(intent.operationId, recall)
                }
                else -> {
                    val destination = input.reference(LocalField.DESTINATION_ID, LocalRecordKind.WAREHOUSE)
                    if (destination.id == row.position.warehouseId) invalid(LocalField.DESTINATION_ID)
                    currentConfigurationReference(access, destination, LocalRecordKind.WAREHOUSE, LocalField.DESTINATION_ID)
                    val quantity = if (access.installed.isStockTransfersOnly) input.exactQuantity(LocalField.QUANTITY, row.onHand)
                        else Quantity.parse(row.onHand.unitId, input.decimal(LocalField.QUANTITY), row.onHand.scale)
                    if (access.installed.isStockTransfersOnly && (row.position.supplierId != null ||
                        row.position.custody != StockCustody.RELEASED || row.safety != StockSafety.CLEAR || row.expiredAtRead ||
                        row.position.serialId != null && row.serialRegistrationStatus != StockSerialRegistrationStatus.REGISTERED ||
                        quantity.scaled <= 0 || quantity > row.available)) invalid(LocalField.QUANTITY)
                    LocalStockCommands.transfer(intent.operationId, LocalWarehouseTransferRequest(input.id(LocalField.TRANSFER_ID),
                        input.id(LocalField.SOURCE_EVENT_ID), input.id(LocalField.DESTINATION_EVENT_ID), row.position, destination.id, revision,
                        quantity, input.text(LocalField.REASON)))
                }
            }
        }
        else -> throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    }
    input.finish()
    return routeFreshStockIntent(access, request)
}

internal fun serialStatusText(status: StockSerialRegistrationStatus): LocalText = when (status) {
    StockSerialRegistrationStatus.LOCAL_PENDING_REGISTRATION -> text("Local identity pending registration; not sellable", "معرف محلي بانتظار التسجيل؛ غير قابل للبيع")
    StockSerialRegistrationStatus.REGISTERED -> text("Registered; other custody/safety restrictions still apply", "مسجل؛ تظل قيود العهدة والسلامة الأخرى سارية")
}

/** Called only while constructing a NEW intent, before the client saves its immutable bytes.
 * Retry/outcome resolution always use StoredClientIntentV1.request() without this conversion. */
internal suspend fun routeFreshStockIntent(access: GatewayAccess, request: MutationRequest): MutationRequest {
    if (access.installed.isStockTransfersOnly) {
        requireNormalizedTransferStorage(access)
        val normalized = LocalStockRelationalCommandsV2.routeFreshIntent(request)
        normalizedStockTransferRequest(LocalAction.WAREHOUSE_TRANSFER, normalized)
        return normalized
    }
    if (!access.installed.has(LocalStockRelationalQueriesV2.PROFILE)) return request
    val active = LocalStockRelationalQueriesV2.normalized(access.read(LocalStockRelationalQueriesV2.profile()).payload)
    return if (active) LocalStockRelationalCommandsV2.routeFreshIntent(request) else request
}
