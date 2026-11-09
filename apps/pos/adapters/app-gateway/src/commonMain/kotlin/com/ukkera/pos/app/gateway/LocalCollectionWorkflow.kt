package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.CancellationException

/** Financial projections remain visible even when a separate stock refresh cannot succeed. */
internal suspend fun collectionWorkflow(access: GatewayAccess, query: LocalWorkflowQuery): LocalWorkflowPage {
    val reference = query.reference ?: invalid()
    if (reference.kind == LocalRecordKind.SALE) return paidSaleWorkflow(access, query, reference.id)
    if (reference.kind != LocalRecordKind.COLLECTION) invalid()
    val reply = access.read(LocalCollectionReadCodec.request(LocalCollectionQueryV1.Collection(reference.id)))
    val result = LocalCollectionReadCodec.decode(reply.payload)
    if (result == LocalCollectionReadResultV1.NotAvailable) return LocalWorkflowPage(query.workflow, emptyList(), emptyList(),
        text("No collection is available to this actor for that ID. This is not absence of payment.",
            "لا يوجد تحصيل متاح لهذا المستخدم لهذا المعرف. هذا لا يثبت عدم الدفع."))
    val collection = (result as? LocalCollectionReadResultV1.Collection)?.value ?: unavailable()
    if (collection.collectionId != reference.id) unavailable()
    val records = mutableListOf(collectionRecord(collection))
    val forms = mutableListOf<LocalActionForm>()
    val normalizedHoldPage = query.cursor?.startsWith("collection-stock-holds:") == true
    val onHoldPage = normalizedHoldPage || query.cursor?.startsWith("collection-holds:") == true
    val offset = if (onHoldPage) 0 else offsetCursor(query.cursor, "collection-attempts", collection.attempts.size)
    val attempts = if (onHoldPage) emptyList() else collection.attempts.drop(offset).take(16)
    records += attempts.map { attempt -> LocalRecord(LocalRecordReference(LocalRecordKind.PAYMENT, attempt.attemptId, parentId = collection.collectionId),
        text("Original collection attempt", "محاولة التحصيل الأصلية"), literal(attempt.status), buildList {
            add(recordValue(text("Original intent operation", "عملية الطلب الأصلية"), literal(attempt.intentOperationId.value)))
            add(recordValue(fieldLabel(LocalField.AMOUNT), moneyText(attempt.amount)))
            add(recordValue(text("Tender kind", "نوع الدفع"), literal(attempt.tender.kind)))
            attempt.tender.tenderId?.let { add(recordValue(fieldLabel(LocalField.TENDER_ID), literal(it.value))) }
            attempt.activeRecordingOperationId?.let { add(recordValue(text("Reserved observation operation", "عملية تسجيل الإفادة المحجوزة"), literal(it.value))) }
            attempt.completedRecordingOperationId?.let { add(recordValue(text("Completed observation operation", "عملية الإفادة المكتملة"), literal(it.value))) }
            attempt.cashReceived?.let { add(recordValue(text("Actually received cash", "النقدية المستلمة فعليًا"), moneyText(it))) }
            attempt.changeGiven?.let { add(recordValue(text("Actual change", "الباقي الفعلي"), moneyText(it))) }
        }, selectable = false) }
    var stock: LocalCollectionStockReadResultV2.StockCut? = null
    var normalizedStock: LocalCollectionStockReadResultV2.NormalizedCut? = null
    var holdsOffset = 0
    var stockProblem: LocalServiceProblem? = null
    if (collection.saleId == null) {
        try {
            val cursor = if (normalizedHoldPage) collectionHoldCursor(checkNotNull(query.cursor), collection.reservationId) else null
            when (val read = collectionStockRead(access, collection, cursor?.second)) {
                is LocalCollectionStockReadResultV2.NormalizedCut -> {
                    if (onHoldPage && !normalizedHoldPage || cursor != null && cursor.first != read.reservationRevision) unavailable()
                    normalizedStock = read
                }
                is LocalCollectionStockReadResultV2.StockCut -> {
                    if (normalizedHoldPage) unavailable()
                    if (onHoldPage) holdsOffset = offsetCursor(query.cursor, "collection-holds", read.holds.size)
                    stock = read
                }
                else -> stockProblem = LocalServiceProblem.UNAVAILABLE
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: GatewayFailure) {
            if (failure.problem == LocalServiceProblem.SESSION_ENDED) throw failure
            stockProblem = failure.problem
        } catch (_: Exception) {
            // A malformed/stale stock projection cannot hide an already-read monetary record.
            stockProblem = LocalServiceProblem.UNAVAILABLE
        }
    }
    val stockCut = stock
    val normalizedCut = normalizedStock
    if (stockCut != null) records += LocalRecord(LocalRecordReference(LocalRecordKind.RESERVATION, stockCut.reservationId, stockCut.reservationRevision),
        text("Collection stock reservation", "حجز مخزون التحصيل"), literal(stockCut.status), buildList {
            add(recordValue(text("Total stock holds", "إجمالي قيود المخزون"), literal(stockCut.holds.size.toString())))
            stockCut.holds.drop(holdsOffset).take(16).forEach { add(collectionHoldValue(it)) }
        }, selectable = false)
    if (normalizedCut != null) records += LocalRecord(LocalRecordReference(LocalRecordKind.RESERVATION,
        normalizedCut.reservationId, normalizedCut.reservationRevision), text("Collection stock reservation", "حجز مخزون التحصيل"), literal(normalizedCut.status), buildList {
            add(recordValue(text("Reserved allocations", "تخصيصات المخزون المحجوزة"), literal(normalizedCut.allocationCount.toString())))
            add(recordValue(text("Total stock holds", "إجمالي قيود المخزون"), literal(normalizedCut.holdCount.toString())))
            normalizedCut.holds.forEach { add(collectionHoldValue(it)) }
        }, selectable = false)
    if (covered(collection) &&
        stockCut != null && stockCut.items.size in 1..16 && stockCut.status == "RESERVED" && stockCut.holds.isEmpty() &&
        access.installed.has(LocalSalesStoreProfile.COLLECTION_COMMAND) && canFinalize(access, collection)) {
        forms += form(LocalAction.SALE_FINALIZE, listOf(idField(LocalField.COLLECTION_ID, collection.collectionId),
            revisionField(LocalField.EXPECTED_COLLECTION_REVISION, collection.collectionRevision),
            revisionField(LocalField.EXPECTED_ORDER_REVISION, collection.orderRevision),
            revisionField(LocalField.EXPECTED_SESSION_REVISION, collection.sessionRevision),
            revisionField(LocalField.EXPECTED_RESERVATION_REVISION, stockCut.reservationRevision),
            moneyField(LocalField.AMOUNT, collection.payable).copy(readOnly = true), newIdField(LocalField.SALE_ID), newIdField(LocalField.STOCK_ISSUE_ID),
            stockRevisionLines(stockCut.items)),
            text("Finalize only this already covered collection. No new payment is requested. The master rechecks stock, money and all exact revisions atomically; a recorded stock hold is NOT a finalized sale.",
                "ثبّت هذا التحصيل المغطى بالفعل فقط. لا يُطلب دفع جديد. يعيد الجهاز الرئيسي فحص المخزون والأموال وجميع المراجعات ذريًا؛ قيد المخزون المسجل ليس بيعًا مثبتًا."))
    }
    if (covered(collection) && normalizedCut != null && normalizedCut.status == "RESERVED" && normalizedCut.holdCount == 0L &&
        access.installed.has(LocalCollectionStockCodecV2.COMMAND) && canFinalize(access, collection)) {
        forms += form(LocalAction.SALE_FINALIZE, listOf(idField(LocalField.ORDER_ID, collection.orderId),
            idField(LocalField.COLLECTION_ID, collection.collectionId), revisionField(LocalField.EXPECTED_COLLECTION_REVISION, collection.collectionRevision),
            revisionField(LocalField.EXPECTED_ORDER_REVISION, collection.orderRevision),
            revisionField(LocalField.EXPECTED_RESERVATION_REVISION, normalizedCut.reservationRevision),
            moneyField(LocalField.AMOUNT, collection.payable).copy(readOnly = true), newIdField(LocalField.SALE_ID), newIdField(LocalField.STOCK_ISSUE_ID)),
            text("Finalize only the already covered collection against its original reservation. No new payment is requested. The master rechecks the complete allocation stream and commits the sale and stock together.",
                "ثبّت التحصيل المغطى بالفعل مقابل حجزه الأصلي فقط. لا يُطلب دفع جديد. يعيد الجهاز الرئيسي فحص جميع التخصيصات ويثبت البيع والمخزون معًا."), version = 2)
    }
    collection.saleId?.let { records += LocalRecord(LocalRecordReference(LocalRecordKind.SALE, it), text("Recorded paid sale", "البيع المدفوع المسجل")) }
    val next = when {
        !onHoldPage && offset + attempts.size < collection.attempts.size -> "collection-attempts:${offset + attempts.size}"
        stockCut != null && !onHoldPage && stockCut.holds.size > 16 -> "collection-holds:16"
        stockCut != null && onHoldPage && holdsOffset + 16 < stockCut.holds.size -> "collection-holds:${holdsOffset + 16}"
        normalizedCut?.nextAfterHold != null -> "collection-stock-holds:${normalizedCut.reservationId.value}:${normalizedCut.reservationRevision.value}:${normalizedCut.nextAfterHold}"
        else -> null
    }
    val unavailableStock = stockProblem?.let { " Stock refresh unavailable: ${it.name}." }.orEmpty()
    val unavailableStockArabic = stockProblem?.let { " تحديث المخزون غير متاح: ${it.name}." }.orEmpty()
    return LocalWorkflowPage(query.workflow, records, forms,
        text("Showing ${attempts.size} of ${collection.attempts.size} original attempts; up to 16 stock holds per page. This client cannot initiate physical payment/refund without qualified protected outcome capacity. Unresolved attempts keep their original identities; never create a new physical attempt to bypass recovery.$unavailableStock ${readCut(reply).english}",
            "تُعرض ${attempts.size} من ${collection.attempts.size} محاولات أصلية؛ وحتى 16 قيد مخزون في الصفحة. لا يستطيع هذا العميل بدء دفع أو رد فعلي دون سعة محمية مؤهلة لتسجيل النتيجة. تحتفظ المحاولات غير المحسومة بمعرفاتها الأصلية؛ لا تنشئ محاولة فعلية جديدة لتجاوز الاسترداد.$unavailableStockArabic ${readCut(reply).arabic}"), next)
}

/** Until protected client recording exists, do not strand a positive-payable order in a new lock. */
internal suspend fun collectionBeginForm(access: GatewayAccess, order: LocalOrderViewV1, profile: LocalOrderStockProfileV2?): LocalActionForm? {
    val normalized = when (profile) {
        LocalOrderStockProfileV2.NORMALIZED -> true
        LocalOrderStockProfileV2.LEGACY_BOUNDED -> false
        null -> return null
    }
    if (order.payable.minor != 0L || order.collectionId != null || order.saleId != null || order.handoffId != null ||
        !access.installed.has(if (normalized) LocalCollectionStockCodecV2.COMMAND else LocalSalesStoreProfile.COLLECTION_COMMAND) ||
        !access.allow(LocalAction.COLLECTION_PREPARE, order.orderId) ||
        !access.hasHint(Permission(SecurityAction.COLLECTION_FINALIZE_ZERO, PermissionVerb.PERFORM), order.orderId) ||
        !access.hasHint(Permission(SecurityAction.CASH_READ, PermissionVerb.PERFORM))) return null
    val own = LocalCashReadCodec.decode(access.read(LocalCashReadCodec.request(LocalCashQueryV1.OwnCash)).payload) as? LocalCashReadResultV1.OwnCash ?: return null
    val session = own.session ?: return null
    if (own.custodianId != access.view.actorId || session.custodianId != access.view.actorId || session.status != "OPEN") return null
    val fields = mutableListOf<LocalFormField>(idField(LocalField.ORDER_ID, order.orderId),
        revisionField(LocalField.EXPECTED_ORDER_REVISION, order.revision), idField(LocalField.SESSION_ID, session.id),
        revisionField(LocalField.EXPECTED_SESSION_REVISION, session.revision), moneyField(LocalField.AMOUNT, order.payable).copy(readOnly = true),
        newIdField(LocalField.COLLECTION_ID), newIdField(LocalField.RESERVATION_ID))
    if (!normalized) {
        val cut = LocalCollectionReadCodec.decode(access.read(LocalCollectionReadCodec.request(LocalCollectionQueryV1.OrderStockCut(order.orderId))).payload)
        as? LocalCollectionReadResultV1.OrderStockCut ?: return null
        if (cut.orderId != order.orderId || cut.orderRevision != order.revision || cut.quoteRevision != order.quoteRevision || cut.items.size !in 1..16) return null
        fields += stockRevisionLines(cut.items)
    }
    return form(LocalAction.COLLECTION_PREPARE, fields,
        text("This cut allows preparing a zero-payable order only, with separate zero-finalization authority still required. It reserves actual stock but does not finalize the order or collect money. Positive-payable preparation remains unavailable until protected physical-outcome recording is qualified.",
            "تسمح هذه النسخة بإعداد طلب مستحقه صفر فقط، مع بقاء صلاحية تثبيت البيع الصفري مطلوبة بشكل منفصل. تحجز مخزونًا فعليًا لكنها لا تثبت الطلب أو تحصّل مالًا. يظل إعداد الطلب المدفوع غير متاح حتى تأهيل التسجيل المحمي لنتيجة الحركة الفعلية."), version = if (normalized) 2 else 1)
}

private fun stockRevisionLines(items: List<LocalSalesStockRevisionV1>): LocalFormField.Lines = LocalFormField.Lines(LocalField.LINES,
    text("Pinned primary SKU storage revisions", "مراجعات تخزين الأصناف الأساسية المثبتة"), listOf(
        idField(LocalField.SKU_ID, readOnly = true), scalar(LocalField.EXPECTED_ITEM_STORAGE_REVISION, LocalInputKind.INTEGER, readOnly = true, maximum = 19)),
    minimumLines = items.size, maximumLines = items.size, initialLines = items.map { item -> LocalInputLine(item.skuId, listOf(
        LocalScalarInput(LocalField.SKU_ID, LocalScalarValue.Identifier(item.skuId)),
        LocalScalarInput(LocalField.EXPECTED_ITEM_STORAGE_REVISION, LocalScalarValue.Integer(item.storageRevision.value)))) }, allowBlankLines = false)

private fun canFinalize(access: GatewayAccess, collection: LocalCollectionViewV1): Boolean = access.hasHint(Permission(
    if (collection.payable.minor == 0L) SecurityAction.COLLECTION_FINALIZE_ZERO else SecurityAction.COLLECTION_FINALIZE,
    PermissionVerb.PERFORM), collection.orderId)

internal suspend fun prepareCollection(access: GatewayAccess, intent: LocalCommandIntent): MutationRequest {
    val normalized = intent.formVersion == 2
    if (normalized) {
        if (intent.lineField != null || intent.lines.isNotEmpty()) invalid(LocalField.LINES)
    } else if (intent.lineField != LocalField.LINES || intent.lines.size !in 1..16) invalid(LocalField.LINES)
    val input = InputValues(intent.values)
    val items = intent.lines.map { row -> val line = InputValues(row.values)
        LocalSalesStockRevisionV1(line.id(LocalField.SKU_ID), line.revision(LocalField.EXPECTED_ITEM_STORAGE_REVISION)).also { line.finish() }
    }
    val request = when (intent.action) {
        LocalAction.COLLECTION_PREPARE -> {
            val id = input.id(LocalField.ORDER_ID)
            access.requireAction(intent.action, id)
            if (!access.hasHint(Permission(SecurityAction.COLLECTION_FINALIZE_ZERO, PermissionVerb.PERFORM), id)) throw GatewayFailure(LocalServiceProblem.DENIED)
            val expected = input.revision(LocalField.EXPECTED_ORDER_REVISION)
            val cut = orderRead(access, LocalOrderQueryV1.Order(id))
            val order = cut.orders.singleOrNull()
                ?: invalid(LocalField.ORDER_ID)
            requireStockProfile(cut.stockProfiles[id], intent.formVersion)
            if (order.orderId != id || order.ownerId != access.view.actorId || order.revision != expected ||
                order.collectionId != null || order.saleId != null || order.handoffId != null) invalid(LocalField.ORDER_ID)
            val amount = input.money(LocalField.AMOUNT)
            if (amount != order.payable || amount.minor != 0L) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED, LocalField.AMOUNT)
            val session = input.id(LocalField.SESSION_ID)
            val sessionRevision = input.revision(LocalField.EXPECTED_SESSION_REVISION)
            val collectionId = input.id(LocalField.COLLECTION_ID)
            val reservationId = input.id(LocalField.RESERVATION_ID)
            if (normalized) LocalCollectionStockCodecV2.command(intent.operationId,
                LocalCollectionStockRequestV2.Begin(id, expected, session, sessionRevision, collectionId, reservationId))
            else MutationRequest(intent.operationId, LocalSalesStoreProfile.COLLECTION_COMMAND, LocalCollectionCommandCodec.encode(
                LocalCollectionRequestV1.Begin(id, expected, session, sessionRevision, collectionId, reservationId, items)))
        }
        LocalAction.SALE_FINALIZE -> {
            val id = input.id(LocalField.COLLECTION_ID)
            val collection = (LocalCollectionReadCodec.decode(access.read(LocalCollectionReadCodec.request(LocalCollectionQueryV1.Collection(id))).payload)
                as? LocalCollectionReadResultV1.Collection)?.value ?: invalid(LocalField.COLLECTION_ID)
            if (collection.collectionId != id || !canFinalize(access, collection)) throw GatewayFailure(LocalServiceProblem.DENIED)
            if (input.money(LocalField.AMOUNT) != collection.payable || !covered(collection)) invalid(LocalField.AMOUNT)
            val expectedCollection = input.revision(LocalField.EXPECTED_COLLECTION_REVISION)
            val expectedOrder = input.revision(LocalField.EXPECTED_ORDER_REVISION)
            val expectedReservation = input.revision(LocalField.EXPECTED_RESERVATION_REVISION)
            if (expectedCollection != collection.collectionRevision || expectedOrder != collection.orderRevision) invalid(LocalField.EXPECTED_COLLECTION_REVISION)
            val stock = collectionStockRead(access, collection)
            val sale = input.id(LocalField.SALE_ID)
            val issue = input.id(LocalField.STOCK_ISSUE_ID)
            if (normalized) {
                val orderId = input.id(LocalField.ORDER_ID)
                if (orderId != collection.orderId || stock !is LocalCollectionStockReadResultV2.NormalizedCut ||
                    stock.reservationRevision != expectedReservation || stock.status != "RESERVED" || stock.holdCount != 0L) invalid(LocalField.EXPECTED_RESERVATION_REVISION)
                LocalCollectionStockCodecV2.command(intent.operationId,
                    LocalCollectionStockRequestV2.Finalize(orderId, expectedOrder, id, expectedCollection, expectedReservation, sale, issue))
            } else {
                if (stock !is LocalCollectionStockReadResultV2.StockCut || stock.reservationRevision != expectedReservation ||
                    stock.status != "RESERVED" || stock.holds.isNotEmpty() || stock.items != items) invalid(LocalField.EXPECTED_RESERVATION_REVISION)
                MutationRequest(intent.operationId, LocalSalesStoreProfile.COLLECTION_COMMAND, LocalCollectionCommandCodec.encode(
                    LocalCollectionRequestV1.Finalize(id, expectedCollection, expectedOrder,
                        input.revision(LocalField.EXPECTED_SESSION_REVISION), expectedReservation, items, sale, issue)))
            }
        }
        else -> throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    }
    input.finish()
    return request
}

private fun covered(collection: LocalCollectionViewV1): Boolean = collection.status == "COVERED" && collection.saleId == null &&
    collection.collected == collection.payable && collection.remainingPayable.minor == 0L

/** Never derive an item-generation list from normalized reservation membership. */
private suspend fun collectionStockRead(access: GatewayAccess, collection: LocalCollectionViewV1,
    afterHold: String? = null): LocalCollectionStockReadResultV2 {
    val result = if (access.installed.has(LocalCollectionStockReadCodecV2.QUERY))
        LocalCollectionStockReadCodecV2.decode(access.read(LocalCollectionStockReadCodecV2.request(
            LocalCollectionStockQueryV2(collection.collectionId, afterHold, holdLimit = 16))).payload)
    else {
        if (afterHold != null) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
        // Old explicit bounded projection, display-compatible only; absent extended coordinates
        // stay absent. A NORMALIZED_RESERVATION is not converted to this type.
        val legacy = LocalCollectionReadCodec.decode(access.read(LocalCollectionReadCodec.request(
            LocalCollectionQueryV1.StockCut(collection.collectionId))).payload) as? LocalCollectionReadResultV1.StockCut
            ?: unavailable()
        LocalCollectionStockReadResultV2.StockCut(legacy.collectionId, legacy.reservationId, legacy.reservationRevision, legacy.status,
            legacy.items, legacy.holds.map { LocalCollectionStockHoldV2(it.reason, it.skuId, it.warehouseId, it.lotId, null, null, null) })
    }
    when (result) {
        is LocalCollectionStockReadResultV2.NormalizedCut -> if (result.collectionId != collection.collectionId || result.reservationId != collection.reservationId ||
            result.allocationCount < 0L || result.holdCount < result.holds.size || result.holds.size > 16) unavailable()
        is LocalCollectionStockReadResultV2.StockCut -> if (result.collectionId != collection.collectionId || result.reservationId != collection.reservationId) unavailable()
        else -> Unit
    }
    return result
}

private fun collectionHoldValue(hold: LocalCollectionStockHoldV2): LocalRecordValue = recordValue(literal(hold.reason),
    text(listOfNotNull(hold.skuId?.let { "SKU: ${it.value}" }, hold.warehouseId?.let { "Warehouse: ${it.value}" },
        hold.lotId?.let { "Lot: ${it.value}" }, hold.supplierOwnerId?.let { "Supplier owner: ${it.value}" },
        hold.custody?.let { "Custody: $it" }, hold.serialId?.let { "Serial: ${it.value}" }).joinToString(" · "),
        listOfNotNull(hold.skuId?.let { "الصنف: ${it.value}" }, hold.warehouseId?.let { "المخزن: ${it.value}" },
            hold.lotId?.let { "التشغيلة: ${it.value}" }, hold.supplierOwnerId?.let { "المورد المالك: ${it.value}" },
            hold.custody?.let { "العهدة: $it" }, hold.serialId?.let { "الرقم التسلسلي: ${it.value}" }).joinToString(" · ")))

private fun collectionHoldCursor(cursor: String, reservationId: DomainId): Pair<Revision, String> {
    val parts = cursor.removePrefix("collection-stock-holds:").split(':', limit = 3)
    if (parts.size != 3 || parts[0] != reservationId.value || parts[2].length !in 1..256) invalid()
    val revision = parts[1].toLongOrNull()?.takeIf { it >= 1L } ?: invalid()
    return Revision(revision) to parts[2]
}

private fun collectionRecord(value: LocalCollectionViewV1): LocalRecord = LocalRecord(LocalRecordReference(LocalRecordKind.COLLECTION,
    value.collectionId, value.collectionRevision), text("Original collection", "التحصيل الأصلي"), literal(value.status), listOf(
        recordValue(fieldLabel(LocalField.ORDER_ID), literal(value.orderId.value)), recordValue(fieldLabel(LocalField.SESSION_ID), literal(value.sessionId.value)),
        recordValue(fieldLabel(LocalField.RESERVATION_ID), literal(value.reservationId.value)), recordValue(text("Exact payable", "المستحق الدقيق"), moneyText(value.payable)),
        recordValue(text("Recorded collected", "المحصل المسجل"), moneyText(value.collected)), recordValue(text("Remaining payable", "المستحق المتبقي"), moneyText(value.remainingPayable)),
        recordValue(text("Attempts", "المحاولات"), literal(value.attempts.size.toString()))))

private suspend fun paidSaleWorkflow(access: GatewayAccess, query: LocalWorkflowQuery, id: DomainId): LocalWorkflowPage {
    val reply = access.read(LocalCollectionReadCodec.request(LocalCollectionQueryV1.PaidSale(id)))
    val result = LocalCollectionReadCodec.decode(reply.payload)
    if (result == LocalCollectionReadResultV1.NotAvailable) return LocalWorkflowPage(query.workflow, emptyList(), emptyList(),
        text("No recorded paid-sale financial preview is available to this actor for that ID. This is not absence of payment.",
            "لا توجد معاينة مالية للبيع المدفوع المسجل متاحة لهذا المستخدم لهذا المعرف. هذا لا يثبت عدم الدفع."))
    val sale = (result as? LocalCollectionReadResultV1.PaidSale)?.value ?: unavailable()
    if (sale.saleId != id || sale.lines.map { it.portionId }.distinct().size != sale.lines.size ||
        sale.components.map { it.key }.distinct().size != sale.components.size ||
        sale.tenders.map { it.originalPaymentId }.distinct().size != sale.tenders.size) unavailable()
    // Keep the existing cursor a portion offset. Components and tenders have their own
    // sequential sections, never a flattened offset that could skip old portion cursors.
    val cursor = query.cursor
    val family = when {
        cursor == null || cursor.startsWith("sale-portions:") -> "sale-portions"
        cursor?.startsWith("sale-components:") == true -> "sale-components"
        cursor?.startsWith("sale-tenders:") == true -> "sale-tenders"
        else -> invalid()
    }
    val total = when (family) {
        "sale-portions" -> sale.lines.size
        "sale-components" -> sale.components.size
        else -> sale.tenders.size
    }
    val offset = offsetCursor(cursor, family, total)
    val lines = if (family == "sale-portions") sale.lines.drop(offset).take(20) else emptyList()
    val components = if (family == "sale-components") sale.components.drop(offset).take(20) else emptyList()
    val tenders = if (family == "sale-tenders") sale.tenders.drop(offset).take(20) else emptyList()
    val shown = lines.size + components.size + tenders.size
    val section = when (family) {
        "sale-portions" -> text("original sold portions", "أجزاء البيع الأصلية")
        "sale-components" -> text("original posted components", "المكونات المالية الأصلية المثبتة")
        else -> text("original tenders", "وسائل الدفع الأصلية")
    }
    val next = when {
        offset + shown < total -> "$family:${offset + shown}"
        family == "sale-portions" && sale.components.isNotEmpty() -> "sale-components:0"
        family != "sale-tenders" && sale.tenders.isNotEmpty() -> "sale-tenders:0"
        else -> null
    }
    val header = LocalRecord(LocalRecordReference(LocalRecordKind.SALE, id),
        text("Recorded paid-sale financial preview", "معاينة مالية للبيع المدفوع المسجل"),
        text("Original recorded basis · read only", "الأساس الأصلي المسجل · للقراءة فقط"), buildList {
            add(recordValue(fieldLabel(LocalField.SALE_ID), literal(sale.saleId.value)))
            add(recordValue(text("Original before discount", "الأصل قبل الخصم"), moneyText(sale.beforeDiscount)))
            add(recordValue(text("Original advertised discount", "الخصم المعلن الأصلي"), moneyText(sale.discount)))
            add(recordValue(text("Original payable", "المستحق الأصلي"), moneyText(sale.payable)))
            add(recordValue(text("Original sold portions", "أجزاء البيع الأصلية"), literal(sale.lines.size.toString())))
            add(recordValue(text("Original posted components", "المكونات المالية الأصلية المثبتة"), literal(sale.components.size.toString())))
            add(recordValue(text("Original tenders", "وسائل الدفع الأصلية"), literal(sale.tenders.size.toString())))
            // Components have keys, not document IDs; keep at most 20 values in the real
            // SALE record rather than inventing identities for nested read-only content.
            components.forEach { add(paidSaleComponentValue(it)) }
        })
    val records = listOf(header) + lines.map { line ->
        LocalRecord(LocalRecordReference(LocalRecordKind.ORDER_LINE, line.portionId, parentId = id),
            text("Original sold portion", "جزء البيع الأصلي"), values = listOf(
                recordValue(fieldLabel(LocalField.PORTION_ID), literal(line.portionId.value)),
                recordValue(text("Original quantity", "الكمية الأصلية"), quantityText(line.quantity)),
                recordValue(text("Original payable", "المستحق الأصلي"), moneyText(line.payable))), selectable = false)
    } + tenders.map { paidSaleTenderRecord(it, id) } + listOfNotNull(customerReceiptLink(access, id), paymentCorrectionLink(access, id))
    return LocalWorkflowPage(query.workflow, records, emptyList(), text(
        "Read-only financial preview: showing $shown of $total ${section.english}; up to 20 entries per section page. Original means the recorded sale basis, not reconciled tender classifications or remaining return entitlement. Posted component amounts are already discounted; never subtract the advertised discount again. This is not a complete printed, legal or fiscal receipt, bank verification or proof of goods delivery. No print/reprint or tax/return action is supplied. Receipt-of-goods and payment-correction links are separately protected. ${readCut(reply).english}",
        "معاينة مالية للقراءة فقط: تُعرض $shown من $total من ${section.arabic}؛ وحتى 20 عنصرًا في كل صفحة قسم. الأصلي يعني أساس البيع المسجل، وليس تصنيفات الدفع بعد التسوية أو استحقاق المرتجع المتبقي. مبالغ المكونات المثبتة محسوم منها الخصم بالفعل؛ لا تطرح الخصم المعلن مرة أخرى. هذه ليست إيصالًا مطبوعًا أو قانونيًا أو ضريبيًا كاملًا، ولا تحققًا مصرفيًا أو إثبات تسليم البضاعة. لا تتوفر إجراءات طباعة أو إعادة طباعة أو ضرائب أو مرتجع. روابط استلام البضاعة وتصحيح الدفع محمية بشكل منفصل. ${readCut(reply).arabic}"), next)
}

private fun paidSaleComponentValue(component: LocalOrderComponentV1): LocalRecordValue {
    val kind = when (component.kind) {
        "ITEM_NET" -> text("Original item net", "صافي الصنف الأصلي")
        "ITEM_TAX" -> text("Original item tax", "ضريبة الصنف الأصلية")
        "CHARGE_NET" -> text("Original charge net", "صافي الرسم الأصلي")
        "CHARGE_TAX" -> text("Original charge tax", "ضريبة الرسم الأصلية")
        else -> unavailable()
    }
    val amount = moneyText(component.amount)
    val absent = text("Not present", "غير موجود")
    val portion = component.portionId?.let { literal(it.value) } ?: absent
    val charge = component.chargeId?.let { literal(it.value) } ?: absent
    return recordValue(text("${kind.english} (${component.kind}) · ${component.key}", "${kind.arabic} (${component.kind}) · ${component.key}"),
        text("Already-discounted posted amount: ${amount.english} · Tax category: ${component.taxCategory} · Portion ID: ${portion.english} · Charge ID: ${charge.english}",
            "المبلغ المثبت بعد الخصم بالفعل: ${amount.arabic} · الفئة الضريبية: ${component.taxCategory} · معرف الجزء: ${portion.arabic} · معرف الرسم: ${charge.arabic}"))
}

private fun paidSaleTenderRecord(payment: LocalRefundObligationViewV1, saleId: DomainId): LocalRecord {
    val tender = payment.tender
    val kind = when (tender.kind) {
        "CASH" -> text("Cash · CASH", "نقدي · CASH")
        "CARD" -> text("External card · CARD", "بطاقة خارجية · CARD")
        "EXTERNAL_WALLET" -> text("External wallet · EXTERNAL_WALLET", "محفظة خارجية · EXTERNAL_WALLET")
        "OTHER" -> text("Other external tender · OTHER", "وسيلة دفع خارجية أخرى · OTHER")
        else -> unavailable()
    }
    val absent = text("Not recorded", "غير مسجل")
    return LocalRecord(LocalRecordReference(LocalRecordKind.PAYMENT, payment.originalPaymentId, parentId = saleId),
        text("Original recorded payment", "الدفع الأصلي المسجل"), values = listOf(
            recordValue(text("Original payment ID", "معرف الدفع الأصلي"), literal(payment.originalPaymentId.value)),
            recordValue(text("Original tender type", "نوع وسيلة الدفع الأصلية"), kind),
            recordValue(text("Original tender ID", "معرف وسيلة الدفع الأصلية"), tender.tenderId?.let { literal(it.value) } ?: absent),
            recordValue(text("Original tender-policy revision", "مراجعة سياسة وسيلة الدفع الأصلية"), tender.policyRevision?.let { literal(it.value.toString()) } ?: absent),
            recordValue(text("Original payment amount", "مبلغ الدفع الأصلي"), moneyText(payment.amount)),
            recordValue(text("Original terminal requirement", "اشتراط جهاز دفع أصلًا"), if (tender.requiresTerminal) text("Yes", "نعم") else text("No", "لا")),
            recordValue(text("Original reference requirement", "اشتراط مرجع دفع أصلًا"), if (tender.requiresReference) text("Yes", "نعم") else text("No", "لا"))),
        selectable = false)
}
