package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.OwnerAccess
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

internal val customerReceiptRecordKinds = setOf(LocalRecordKind.CUSTOMER_RECEIPT,
    LocalRecordKind.CUSTOMER_RECEIPT_PORTION, LocalRecordKind.CUSTOMER_RECEIPT_EVENT,
    LocalRecordKind.CUSTOMER_RECEIPT_BATCH)

internal fun customerReceiptLink(access: GatewayAccess, saleId: DomainId, documentary: Boolean = false): LocalRecord? =
    if (access.installed.has(LocalRetailCustomerReceiptReadCodec.QUERY) &&
        if (documentary) access.hasHint(Permission(SecurityAction.RETURN_RECEIPT_LOOKUP, PermissionVerb.PERFORM), saleId)
        else access.allow(LocalAction.RETAIL_CUSTOMER_RECEIPT, saleId))
        LocalRecord(LocalRecordReference(LocalRecordKind.CUSTOMER_RECEIPT, saleId),
            text("Actual counter customer receipt", "استلام العميل الفعلي من المنضدة"),
            if (documentary) text("Read-only original observations for returns; payment is not receipt of goods",
                "ملاحظات أصلية للمرتجعات للقراءة فقط؛ الدفع ليس استلامًا للبضاعة")
            else text("Inspect original observations; payment is not receipt of goods", "عرض الملاحظات الأصلية؛ الدفع ليس استلامًا للبضاعة"))
    else null

/** Explicit documentary route, never a fallback after a denied RECORD read. */
private fun customerReceiptAccess(access: GatewayAccess, saleId: DomainId,
    documentary: Boolean): LocalRetailCustomerReceiptReadAccessV1 {
    if (documentary) {
        if (!access.hasHint(Permission(SecurityAction.RETURN_RECEIPT_LOOKUP, PermissionVerb.PERFORM), saleId))
            throw GatewayFailure(LocalServiceProblem.DENIED)
        return LocalRetailCustomerReceiptReadAccessV1.RETURN_LOOKUP
    }
    if (!access.allow(LocalAction.RETAIL_CUSTOMER_RECEIPT, saleId)) throw GatewayFailure(LocalServiceProblem.DENIED)
    return LocalRetailCustomerReceiptReadAccessV1.RECORD
}

internal fun isCustomerReceiptSearch(value: String): Boolean =
    value.startsWith("customer-receipt:") || value.startsWith("customer-receipt-event:")

private fun customerReceiptSearchReference(value: String): LocalRecordReference {
    if (value.startsWith("customer-receipt:")) return LocalRecordReference(LocalRecordKind.CUSTOMER_RECEIPT,
        searchedId(value.removePrefix("customer-receipt:")))
    if (!value.startsWith("customer-receipt-event:")) invalid()
    val coordinates = value.removePrefix("customer-receipt-event:").split(':')
    if (coordinates.size != 2) invalid()
    return LocalRecordReference(LocalRecordKind.CUSTOMER_RECEIPT_EVENT, searchedId(coordinates[1]), parentId = searchedId(coordinates[0]))
}

/** Fixed capability selectors only; the Master still authorizes each actual original sale. */
internal fun requireCustomerReceiptWorkflow(query: LocalWorkflowQuery) {
    if (query.workflow !in setOf(LocalWorkflow.CHECKOUT, LocalWorkflow.ORDERS, LocalWorkflow.RETURNS) ||
        query.pricePreview != null || query.branchReport != null || query.food != null || query.purchasing != null ||
        query.cash != null || query.inventory != null || query.openingAllocations != null ||
        (if (query.workflow == LocalWorkflow.RETURNS) query.returns?.section != LocalReturnSection.RECEIPTS else query.returns != null))
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    val selected = query.reference
    if (selected != null && query.search.isNotEmpty()) invalid()
    if (selected == null && query.search.isEmpty()) {
        if (query.cursor != null) invalid()
        return // Explicit known-sale entry, not a fabricated paid-source/observation listing.
    }
    if (selected == null && !isCustomerReceiptSearch(query.search))
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    val ref = selected ?: customerReceiptSearchReference(query.search)
    when (ref.kind) {
        LocalRecordKind.CUSTOMER_RECEIPT -> {
            if (ref.parentId != null || ref.revision != null) invalid()
            query.cursor?.let { cursor ->
                val parts = cursor.split(':')
                if (parts.size != 4 || parts[0] != "customer-receipt" || parts[1] != ref.id.value) invalid()
                val revision = parts[2].toLongOrNull()?.takeIf { it in 0L..LocalRetailCustomerReceiptProfileV1.MAX_EVENTS.toLong() } ?: invalid()
                val offset = parts[3].toIntOrNull()?.takeIf { it in 0..LocalRetailCustomerReceiptProfileV1.MAX_ORIGINAL_PORTIONS && it % 16 == 0 } ?: invalid()
                if (parts[2] != revision.toString() || parts[3] != offset.toString()) invalid()
            }
        }
        LocalRecordKind.CUSTOMER_RECEIPT_EVENT -> {
            if (ref.parentId == null || ref.revision != null || query.cursor != null) invalid()
        }
        LocalRecordKind.CUSTOMER_RECEIPT_PORTION, LocalRecordKind.CUSTOMER_RECEIPT_BATCH -> {
            val revision = ref.revision?.value ?: invalid()
            if (ref.parentId == null || revision !in 0L..LocalRetailCustomerReceiptProfileV1.MAX_EVENTS.toLong() ||
                query.cursor != null) invalid()
        }
        else -> invalid()
    }
}

internal fun requireCustomerReceiptIntent(intent: LocalCommandIntent) {
    if (intent.action != LocalAction.RETAIL_CUSTOMER_RECEIPT || intent.formVersion !in 1..2 || intent.arrivalEvidence != null)
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    val ref = intent.reference ?: invalid()
    val batch = intent.formVersion == 2
    val revision = ref.revision?.value ?: invalid()
    if (ref.kind != (if (batch) LocalRecordKind.CUSTOMER_RECEIPT_BATCH else LocalRecordKind.CUSTOMER_RECEIPT_PORTION) ||
        ref.parentId == null || revision !in 0L until LocalRetailCustomerReceiptProfileV1.MAX_EVENTS.toLong()) invalid()
    if (batch) {
        if (intent.lineField != LocalField.LINES || intent.lines.size !in 1..LocalRetailCustomerReceiptProfileV1.MAX_REQUEST_PORTIONS) invalid(LocalField.LINES)
    } else if (intent.lineField != null || intent.lines.isNotEmpty()) invalid(LocalField.LINES)
    val common = setOf(LocalField.SALE_ID, LocalField.EXPECTED_CUSTOMER_RECEIPT_REVISION,
        LocalField.CUSTOMER_RECEIVED_AT, LocalField.CUSTOMER_RECEIPT_TIME_ZONE)
    val portion = setOf(LocalField.PORTION_ID, LocalField.UNIT, LocalField.QUANTITY_SCALE, LocalField.QUANTITY)
    val allowed = if (batch) common else common + portion
    if (intent.values.any { it.field !in allowed } || intent.lines.any { row -> row.values.any { it.field !in portion } }) invalid()
    // These forms use exact scalar IDs, not another workflow's nested reference selectors.
    if ((intent.values + intent.lines.flatMap { it.values }).any {
            it.value is LocalScalarValue.Reference || it.value is LocalScalarValue.References
        }) invalid()
}

internal fun requireCustomerReceiptRead(request: ReadRequest) {
    if (request.query != LocalRetailCustomerReceiptReadCodec.QUERY) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    try {
        val query = LocalRetailCustomerReceiptReadCodec.decodeQuery(request.payload)
        // The two typed routes stay explicit; a denied RECORD never falls back to RETURN_LOOKUP.
        if (LocalRetailCustomerReceiptReadCodec.encode(query) != request.payload) invalid()
    } catch (failure: GatewayFailure) { throw failure }
    catch (_: Exception) { invalid() }
}

/** Pure original-format qualification; never fetch a current Head or change its observation. */
internal fun customerReceiptRequest(action: LocalAction, request: MutationRequest): LocalRetailCustomerReceiptRequestV1 {
    if (action != LocalAction.RETAIL_CUSTOMER_RECEIPT || request.command != LocalRetailCustomerReceiptCodec.COMMAND ||
        request.actionApprovalId != null) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    return try {
        val value = LocalRetailCustomerReceiptCodec.decode(request.payload)
        if (LocalRetailCustomerReceiptCodec.encode(value) != request.payload)
            throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
        value
    } catch (failure: GatewayFailure) { throw failure }
    catch (_: Exception) { throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN) }
}

internal fun savedCustomerReceiptRequest(entry: StoredClientIntentV1): LocalRetailCustomerReceiptRequestV1 {
    try {
        entry.validate(entry.owner)
        if (entry.localAction != LocalAction.RETAIL_CUSTOMER_RECEIPT || entry.formVersion !in 1..2 || entry.actionApproval != null)
            throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
        val request = customerReceiptRequest(entry.localAction, entry.request())
        // Retained v1 commands already permit multiple portions. Presentation form metadata
        // must not reinterpret an authenticated original or impose today's editor shape.
        entry.recorded?.let { retained ->
            val payload = decodeHex(retained.payloadHex, 65_536).useWiped { StoredBytes(it) }
            customerReceiptOutcome(entry.operationId, request, retained.committed, retained.code, retained.recordedAt, payload)
        }
        return request
    } catch (_: Exception) { throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN) }
}

/** Display-only ownership hint; the Master still independently enforces the current grant. */
private fun customerReceiptOwnerHint(access: GatewayAccess, saleId: DomainId, collector: DomainId): Boolean {
    if (!access.allow(LocalAction.RETAIL_CUSTOMER_RECEIPT, saleId)) return false
    return access.view.policy.grants.any { grant ->
        grant.permission == Permission(SecurityAction.CUSTOMER_RECEIPT_RECORD, PermissionVerb.PERFORM) &&
            grant.effect == GrantEffect.ALLOW && (grant.scope == access.view.scope || grant.scope == access.view.scope.company()) &&
            (grant.namedSubjectId == null || grant.namedSubjectId == access.view.actorId) &&
            (grant.targetIds == null || saleId in grant.targetIds.orEmpty()) &&
            (collector == access.view.actorId || grant.ownerAccess == OwnerAccess.ANY_OWNER)
    }
}

private data class CustomerReceiptCut(val original: LocalRetailCustomerReceiptOriginalV1,
    val revision: Revision, val recorded: List<LocalRetailCustomerReceiptPortionV1>,
    val eventCount: Int, val latest: com.ukkera.pos.types.OperationId?)

private suspend fun customerReceiptHead(access: GatewayAccess, saleId: DomainId, documentary: Boolean = false): CustomerReceiptCut? {
    val reply = access.read(LocalRetailCustomerReceiptReadCodec.request(
        LocalRetailCustomerReceiptQueryV1.Head(saleId, customerReceiptAccess(access, saleId, documentary))))
    val cut = when (val value = LocalRetailCustomerReceiptReadCodec.decode(reply.payload)) {
        is LocalRetailCustomerReceiptReadResultV1.NotRecorded -> CustomerReceiptCut(value.original, Revision(0), emptyList(), 0, null)
        is LocalRetailCustomerReceiptReadResultV1.Head -> CustomerReceiptCut(value.value.original, value.value.headRevision,
            value.value.recordedPortions, value.value.eventCount, value.value.latestEventOperationId)
        LocalRetailCustomerReceiptReadResultV1.NotAvailable -> return null
        LocalRetailCustomerReceiptReadResultV1.CapacityRequired -> throw GatewayFailure(LocalServiceProblem.RETURN_CAPACITY_REQUIRED)
        else -> unavailable()
    }
    val originals = cut.original.portions
    if (cut.original.saleId != saleId || originals.size !in 1..256 ||
        originals.map { it.portionId } != originals.map { it.portionId }.distinct().sortedBy { it.value } ||
        originals.any { it.quantity.scaled <= 0 } || cut.eventCount !in 0..64 ||
        cut.revision.value != cut.eventCount.toLong() ||
        (cut.eventCount == 0) != (cut.latest == null) ||
        cut.recorded.map { it.portionId } != cut.recorded.map { it.portionId }.distinct().sortedBy { it.value } ||
        cut.recorded.any { recorded ->
            val sold = originals.singleOrNull { it.portionId == recorded.portionId }?.quantity
            sold == null || recorded.quantity.scaled <= 0 || recorded.quantity.unitId != sold.unitId ||
                recorded.quantity.scale != sold.scale || recorded.quantity.scaled > sold.scaled
        } || cut.eventCount == 0 && cut.recorded.isNotEmpty() || cut.eventCount > 0 && cut.recorded.isEmpty()) unavailable()
    return cut
}

internal suspend fun customerReceiptWorkflow(access: GatewayAccess, query: LocalWorkflowQuery): LocalWorkflowPage {
    if (query.workflow !in setOf(LocalWorkflow.CHECKOUT, LocalWorkflow.ORDERS, LocalWorkflow.RETURNS)) invalid()
    if (access.installed.isCustomerReceiptsOnly) {
        requireCustomerReceiptWorkflow(query)
        if (query.reference == null && query.search.isEmpty()) return LocalWorkflowPage(query.workflow, emptyList(), emptyList(),
            text("Enter customer-receipt:SALE_UUID for an exact known paid sale, or customer-receipt-event:SALE_UUID:OPERATION_UUID for an original observation. This connection has no paid-sale listing. Returns is read-only original evidence; no stock, payment, kitchen or legal-return action is installed.",
                "أدخل customer-receipt:SALE_UUID لبيع مدفوع معروف بعينه، أو customer-receipt-event:SALE_UUID:OPERATION_UUID لملاحظة أصلية. لا يوفر هذا الاتصال قائمة المبيعات المدفوعة. تعرض المرتجعات دليلًا أصليًا للقراءة فقط؛ لا تتوفر إجراءات مخزون أو دفع أو مطبخ أو تأهيل قانوني للمرتجع."))
    }
    val ref = query.reference ?: customerReceiptSearchReference(query.search)
    if (ref.kind !in customerReceiptRecordKinds || query.food != null) invalid()
    val saleId = if (ref.kind == LocalRecordKind.CUSTOMER_RECEIPT) ref.id else ref.parentId ?: invalid()
    if (ref.kind == LocalRecordKind.CUSTOMER_RECEIPT && (ref.parentId != null || ref.revision != null)) invalid()
    if (ref.kind == LocalRecordKind.CUSTOMER_RECEIPT_EVENT) return customerReceiptEvent(access, query, saleId, ref)
    val cut = customerReceiptHead(access, saleId, query.workflow == LocalWorkflow.RETURNS) ?: return LocalWorkflowPage(query.workflow, emptyList(), emptyList(),
        text("Original counter receipt evidence is unavailable. No receipt date, empty head or completed delivery is inferred.",
            "دليل الاستلام الأصلي من المنضدة غير متاح. لا يُفترض تاريخ استلام أو سجل فارغ أو توصيل مكتمل."))
    val summary = LocalRecord(LocalRecordReference(LocalRecordKind.CUSTOMER_RECEIPT, saleId),
        text("Recorded customer receipts — separate from payment", "استلامات العميل المسجلة — منفصلة عن الدفع"),
        when (cut.eventCount) {
            0 -> text("No observation recorded by this producer", "لم تُسجل ملاحظة عبر هذا المسار")
            64 -> text("Observation limit reached. Existing evidence remains available; no further observation can be recorded.",
                "تم بلوغ حد الملاحظات. يظل الدليل السابق متاحًا؛ لا يمكن تسجيل ملاحظة إضافية.")
            else -> null
        },
        listOf(recordValue(fieldLabel(LocalField.EXPECTED_CUSTOMER_RECEIPT_REVISION), literal(cut.revision.value.toString())),
            recordValue(text("Observation count", "عدد الملاحظات"), literal(cut.eventCount.toString())),
            recordValue(text("Original paid Finalize, UTC", "تثبيت الدفع الأصلي بتوقيت UTC"), literal(cut.original.paidFinalizeAt.canonicalString()))),
        selectable = false)
    fun portionRecord(sold: LocalRetailCustomerReceiptPortionV1): LocalRecord {
        val recorded = cut.recorded.singleOrNull { it.portionId == sold.portionId }?.quantity
        return LocalRecord(LocalRecordReference(LocalRecordKind.CUSTOMER_RECEIPT_PORTION, sold.portionId, cut.revision, saleId),
            text("Original sold portion", "جزء البيع الأصلي"), values = listOf(
                recordValue(text("Original sold quantity", "كمية البيع الأصلية"), quantityText(sold.quantity)),
                recordValue(text("Recorded receipt quantity", "كمية الاستلام المسجلة"),
                    quantityText(recorded ?: Quantity(sold.quantity.unitId, 0, sold.quantity.scale)))),
            selectable = query.workflow != LocalWorkflow.RETURNS)
    }
    val explanation = text(
        "Record only actual customer counter receipt. Enter its observed time; never substitute payment time or current time on recovery. Cairo is explicitly confirmed. No stock, money or return entitlement changes. Separate dates stay separate; this is not legal qualification.",
        "سجل فقط استلام العميل الفعلي من المنضدة. أدخل الوقت المرصود؛ لا تستبدله بوقت الدفع أو الوقت الحالي عند الاستعادة. يجب تأكيد القاهرة صراحة. لا يتغير المخزون أو المال أو استحقاق المرتجع. تبقى التواريخ منفصلة؛ وهذا ليس تأهيلًا قانونيًا.")
    if (ref.kind == LocalRecordKind.CUSTOMER_RECEIPT_BATCH) {
        if (query.cursor != null || ref.revision != cut.revision) returnRestart()
        val start = cut.original.portions.indexOfFirst { it.portionId == ref.id }
        if (start < 0 || start % 16 != 0) invalid()
        val selected = cut.original.portions.drop(start).take(16)
        return LocalWorkflowPage(query.workflow, listOf(summary) + selected.map(::portionRecord),
            listOfNotNull(if (query.workflow != LocalWorkflow.RETURNS)
                customerReceiptBatchForm(access, saleId, cut, selected) else null), explanation)
    }
    if (ref.kind == LocalRecordKind.CUSTOMER_RECEIPT_PORTION) {
        if (query.cursor != null || ref.revision != cut.revision) returnRestart()
        val sold = cut.original.portions.singleOrNull { it.portionId == ref.id } ?: invalid()
        return LocalWorkflowPage(query.workflow, listOf(summary, portionRecord(sold)),
            listOfNotNull(if (query.workflow != LocalWorkflow.RETURNS) customerReceiptForm(access, saleId, cut, sold) else null), explanation)
    }
    val offset = offsetCursor(query.cursor, "customer-receipt:${saleId.value}:${cut.revision.value}", cut.original.portions.size)
    if (offset % 16 != 0) invalid()
    val selected = cut.original.portions.drop(offset).take(16)
    val latest = cut.latest?.let { LocalRecord(LocalRecordReference(LocalRecordKind.CUSTOMER_RECEIPT_EVENT,
        DomainId(it.value), parentId = saleId), text("Latest original receipt observation", "أحدث ملاحظة استلام أصلية")) }
    val batch = if (query.workflow != LocalWorkflow.RETURNS &&
        customerReceiptBatchForm(access, saleId, cut, selected) != null)
        LocalRecord(LocalRecordReference(LocalRecordKind.CUSTOMER_RECEIPT_BATCH, selected.first().portionId, cut.revision, saleId),
            text("Record chosen portions from this page together", "تسجيل الأجزاء المختارة من هذه الصفحة معًا"),
            text("One observation; remove goods not received. Other pages remain separate selections.",
                "ملاحظة واحدة؛ احذف البضاعة غير المستلمة. تبقى الصفحات الأخرى اختيارات منفصلة.")) else null
    return LocalWorkflowPage(query.workflow, listOf(summary) + selected.map(::portionRecord) + listOfNotNull(batch, latest), emptyList(), explanation,
        if (offset + selected.size < cut.original.portions.size)
            "customer-receipt:${saleId.value}:${cut.revision.value}:${offset + selected.size}" else null)
}

private fun customerReceiptForm(access: GatewayAccess, saleId: DomainId, cut: CustomerReceiptCut,
    portion: LocalRetailCustomerReceiptPortionV1): LocalActionForm? {
    val recorded = cut.recorded.singleOrNull { it.portionId == portion.portionId }?.quantity?.scaled ?: 0
    if (!access.installed.has(LocalRetailCustomerReceiptCodec.COMMAND) ||
        !customerReceiptOwnerHint(access, saleId, cut.original.originalCollectorId) || cut.eventCount >= 64 || recorded >= portion.quantity.scaled) return null
    return form(LocalAction.RETAIL_CUSTOMER_RECEIPT, listOf(idField(LocalField.SALE_ID, saleId),
        idField(LocalField.PORTION_ID, portion.portionId), revisionField(LocalField.EXPECTED_CUSTOMER_RECEIPT_REVISION, cut.revision),
        idField(LocalField.UNIT, portion.quantity.unitId),
        scalar(LocalField.QUANTITY_SCALE, LocalInputKind.INTEGER, LocalScalarValue.Integer(portion.quantity.scale.toLong()), readOnly = true),
        scalar(LocalField.QUANTITY, LocalInputKind.DECIMAL, maximum = 32),
        instantField(LocalField.CUSTOMER_RECEIVED_AT),
        choiceField(LocalField.CUSTOMER_RECEIPT_TIME_ZONE,
            listOf(LocalChoice("Africa/Cairo", text("Confirm Africa/Cairo", "تأكيد Africa/Cairo"))))),
        text("I attest these goods actually reached the customer at the stated time. Save once; recover that original operation after a lost response, never hand over again.",
            "أقر بوصول هذه البضاعة فعليًا إلى العميل في الوقت المذكور. احفظ مرة واحدة؛ استعد العملية الأصلية عند فقد الرد ولا تسلم البضاعة مجددًا."))
}

private fun customerReceiptBatchForm(access: GatewayAccess, saleId: DomainId, cut: CustomerReceiptCut,
    page: List<LocalRetailCustomerReceiptPortionV1>): LocalActionForm? {
    if (!access.installed.has(LocalRetailCustomerReceiptCodec.COMMAND) || cut.eventCount >= 64 ||
        !customerReceiptOwnerHint(access, saleId, cut.original.originalCollectorId)) return null
    val remaining = page.filter { sold ->
        (cut.recorded.singleOrNull { it.portionId == sold.portionId }?.quantity?.scaled ?: 0L) < sold.quantity.scaled
    }
    if (remaining.isEmpty()) return null
    val lines = LocalFormField.Lines(LocalField.LINES, text("Chosen portions on this page", "الأجزاء المختارة في هذه الصفحة"),
        listOf(idField(LocalField.PORTION_ID, readOnly = true), idField(LocalField.UNIT, readOnly = true),
            scalar(LocalField.QUANTITY_SCALE, LocalInputKind.INTEGER, readOnly = true),
            scalar(LocalField.QUANTITY, LocalInputKind.DECIMAL, maximum = 32)),
        maximumLines = 32, initialLines = remaining.map { sold -> LocalInputLine(sold.portionId, listOf(
            LocalScalarInput(LocalField.PORTION_ID, LocalScalarValue.Identifier(sold.portionId)),
            LocalScalarInput(LocalField.UNIT, LocalScalarValue.Identifier(sold.quantity.unitId)),
            LocalScalarInput(LocalField.QUANTITY_SCALE, LocalScalarValue.Integer(sold.quantity.scale.toLong())))) },
        allowBlankLines = false)
    return form(LocalAction.RETAIL_CUSTOMER_RECEIPT, listOf(idField(LocalField.SALE_ID, saleId),
        revisionField(LocalField.EXPECTED_CUSTOMER_RECEIPT_REVISION, cut.revision), lines,
        instantField(LocalField.CUSTOMER_RECEIVED_AT), choiceField(LocalField.CUSTOMER_RECEIPT_TIME_ZONE,
            listOf(LocalChoice("Africa/Cairo", text("Confirm Africa/Cairo", "تأكيد Africa/Cairo"))))),
        text("Only this page is selected. Remove goods not actually received and enter each chosen quantity. Attest one actual time; save one observation and recover its original operation after a lost response.",
            "هذه الصفحة فقط محددة. احذف البضاعة غير المستلمة فعليًا وأدخل كمية كل جزء مختار. أقر بوقت فعلي واحد؛ احفظ ملاحظة واحدة واستعد عمليتها الأصلية عند فقد الرد."), version = 2)
}

internal suspend fun prepareCustomerReceipt(access: GatewayAccess, intent: LocalCommandIntent): MutationRequest {
    if (intent.action != LocalAction.RETAIL_CUSTOMER_RECEIPT || intent.formVersion !in 1..2) invalid()
    val batch = intent.formVersion == 2
    if (batch) {
        if (intent.lineField != LocalField.LINES || intent.lines.size !in 1..32) invalid(LocalField.LINES)
    } else if (intent.lineField != null || intent.lines.isNotEmpty()) invalid(LocalField.LINES)
    val input = InputValues(intent.values)
    val saleId = input.id(LocalField.SALE_ID)
    access.requireAction(intent.action, saleId)
    val expected = input.revision(LocalField.EXPECTED_CUSTOMER_RECEIPT_REVISION)
    val cut = customerReceiptHead(access, saleId) ?: unavailable()
    if (cut.revision != expected) invalid(LocalField.EXPECTED_CUSTOMER_RECEIPT_REVISION)
    if (cut.eventCount >= 64) throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
    val ref = intent.reference
    val pageIds = if (batch && ref != null) {
        if (ref.kind != LocalRecordKind.CUSTOMER_RECEIPT_BATCH || ref.parentId != saleId || ref.revision != expected)
            invalid(LocalField.PORTION_ID)
        val start = cut.original.portions.indexOfFirst { it.portionId == ref.id }
        if (start < 0 || start % 16 != 0) invalid(LocalField.PORTION_ID)
        cut.original.portions.drop(start).take(16).map { it.portionId }.toSet()
    } else null
    fun portion(values: InputValues, rowId: DomainId? = null): LocalRetailCustomerReceiptPortionV1 {
        val portionId = values.id(LocalField.PORTION_ID)
        if (rowId != null && rowId != portionId || pageIds != null && portionId !in pageIds) invalid(LocalField.PORTION_ID)
        val original = cut.original.portions.singleOrNull { it.portionId == portionId } ?: invalid(LocalField.PORTION_ID)
        if (!batch && ref != null && (ref.kind != LocalRecordKind.CUSTOMER_RECEIPT_PORTION || ref.id != portionId ||
                ref.parentId != saleId || ref.revision != expected)) invalid(LocalField.PORTION_ID)
        val quantity = values.exactQuantity(LocalField.QUANTITY, original.quantity).requirePositive()
        val already = cut.recorded.singleOrNull { it.portionId == portionId }?.quantity?.scaled ?: 0L
        if (quantity.scaled > original.quantity.scaled - already) invalid(LocalField.QUANTITY)
        return LocalRetailCustomerReceiptPortionV1(portionId, quantity)
    }
    val portions = if (batch) intent.lines.map { row ->
        val values = InputValues(row.values)
        portion(values, row.lineId).also { values.finish() }
    }.also { rows ->
        if (rows.map { it.portionId }.distinct().size != rows.size) invalid(LocalField.LINES)
    }.sortedBy { it.portionId.value } else listOf(portion(input))
    val receivedAt = input.instant(LocalField.CUSTOMER_RECEIVED_AT)
    val zone = input.choice(LocalField.CUSTOMER_RECEIPT_TIME_ZONE)
    if (zone != "Africa/Cairo") invalid(LocalField.CUSTOMER_RECEIPT_TIME_ZONE)
    if (receivedAt < cut.original.paidFinalizeAt) invalid(LocalField.CUSTOMER_RECEIVED_AT)
    input.finish()
    return LocalRetailCustomerReceiptCodec.command(intent.operationId, LocalRetailCustomerReceiptRequestV1(saleId,
        expected, receivedAt, zone, LocalRetailCustomerReceiptObservationV1.CUSTOMER_RECEIVED_AT_COUNTER,
        portions))
}

internal fun customerReceiptIntentSummary(request: MutationRequest): List<LocalRecordValue> {
    val value = LocalRetailCustomerReceiptCodec.decode(request.payload)
    // Keep the compatible 32-value journal profile and every chosen quantity. Large
    // selections use paired display rows; the immutable command itself is unchanged.
    val portions = if (value.portions.size <= 28)
        value.portions.map { recordValue(literal(it.portionId.value), quantityText(it.quantity)) }
    else value.portions.chunked(2).map { pair ->
        recordValue(text("Original portion quantities", "كميات الأجزاء الأصلية"),
            literal(pair.joinToString("\n") { "${it.portionId.value}: ${quantityText(it.quantity).english}" }))
    }
    return listOf(recordValue(fieldLabel(LocalField.SALE_ID), literal(value.saleId.value)),
        recordValue(fieldLabel(LocalField.EXPECTED_CUSTOMER_RECEIPT_REVISION), literal(value.expectedHeadRevision.value.toString())),
        recordValue(fieldLabel(LocalField.CUSTOMER_RECEIVED_AT), literal(value.receivedAt.canonicalString())),
        recordValue(fieldLabel(LocalField.CUSTOMER_RECEIPT_TIME_ZONE), literal(value.receiptTimeZoneId))) +
        portions
}

private suspend fun customerReceiptEvent(access: GatewayAccess, query: LocalWorkflowQuery, saleId: DomainId,
    ref: LocalRecordReference): LocalWorkflowPage {
    if (query.cursor != null || ref.revision != null) invalid()
    val operation = com.ukkera.pos.types.OperationId(ref.id.value)
    val reply = access.read(LocalRetailCustomerReceiptReadCodec.request(
        LocalRetailCustomerReceiptQueryV1.Event(saleId, operation, customerReceiptAccess(access, saleId, query.workflow == LocalWorkflow.RETURNS))))
    val result = LocalRetailCustomerReceiptReadCodec.decode(reply.payload)
    if (result == LocalRetailCustomerReceiptReadResultV1.NotAvailable)
        return LocalWorkflowPage(query.workflow, emptyList(), emptyList(),
            text("Original observation evidence is unavailable; this is not proof no observation was recorded.",
                "دليل الملاحظة الأصلية غير متاح؛ وهذا لا يثبت عدم تسجيل ملاحظة."))
    if (result is LocalRetailCustomerReceiptReadResultV1.EventNotRecorded) {
        if (result.saleId != saleId || result.operationId != operation) unavailable()
        return LocalWorkflowPage(query.workflow, emptyList(), emptyList(),
            text("No counter observation is recorded under that original operation. No replacement or second handover is requested.",
                "لا توجد ملاحظة منضدة مسجلة للعملية الأصلية المحددة. لا يُطلب بديل أو تسليم ثانٍ."))
    }
    val event = (result as? LocalRetailCustomerReceiptReadResultV1.Event)?.value ?: unavailable()
    if (event.saleId != saleId || event.eventOperationId != operation || event.receiptTimeZoneId != "Africa/Cairo" ||
        event.receivedAt < event.paidFinalizeAt || event.receivedAt > event.recordedAt || event.headRevision.value !in 1..64 ||
        event.observation != LocalRetailCustomerReceiptObservationV1.CUSTOMER_RECEIVED_AT_COUNTER || event.portions.size !in 1..32 ||
        event.portions.map { it.portionId } != event.portions.map { it.portionId }.distinct().sortedBy { it.value } ||
        event.portions.any { it.quantity.scaled <= 0 }) unavailable()
    return LocalWorkflowPage(query.workflow, listOf(LocalRecord(ref, text("Original counter observation", "ملاحظة المنضدة الأصلية"),
        values = listOf(recordValue(fieldLabel(LocalField.CUSTOMER_RECEIVED_AT), literal(event.receivedAt.canonicalString())),
            recordValue(fieldLabel(LocalField.CUSTOMER_RECEIPT_TIME_ZONE), literal(event.receiptTimeZoneId)),
            recordValue(text("Master commit time, UTC", "وقت تسجيل الجهاز الرئيسي بتوقيت UTC"), literal(event.recordedAt.canonicalString())),
            recordValue(text("Observing employee", "الموظف الراصد"), literal(event.observingActorId.value))), selectable = false)) +
        event.portions.map { portion -> LocalRecord(LocalRecordReference(LocalRecordKind.CUSTOMER_RECEIPT_PORTION,
            portion.portionId, event.headRevision, saleId), text("Originally observed portion", "الجزء المرصود أصليًا"),
            values = listOf(recordValue(fieldLabel(LocalField.QUANTITY), quantityText(portion.quantity))), selectable = false) }, emptyList(),
        text("Immutable employee attestation, not a new stock or payment action or legal return decision.",
            "إفادة موظف ثابتة وليست حركة مخزون أو دفع جديدة أو قرار مرتجع قانونيًا."))
}

internal fun customerReceiptRecorded(entry: StoredClientIntentV1, reply: MutationReply.Recorded): LocalOperationResult.Recorded {
    val request = savedCustomerReceiptRequest(entry)
    if (reply.operationId != entry.operationId || reply.originalActorId != entry.owner.actorId || reply.physicalStart != null)
        throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
    val result = customerReceiptOutcome(entry.operationId, request, reply.committed, reply.code, reply.recordedAt, reply.result)
    if (result is LocalRetailCustomerReceiptResultV1.Refused) {
        return LocalOperationResult.Recorded(reply.operationId, LocalRecordedDecision.REJECTED,
            text("Counter receipt observation refused: ${result.reason}. Existing observations, stock, money and rights are unchanged.",
                "رُفضت ملاحظة استلام العميل: ${result.reason}. لم تتغير الملاحظات السابقة أو المخزون أو المال أو الحقوق."), replayed = reply.replayed)
    }
    val value = result as? LocalRetailCustomerReceiptResultV1.Applied ?: unavailable()
    return LocalOperationResult.Recorded(reply.operationId, LocalRecordedDecision.RECORDED_ONLY,
        text("Original customer receipt observation recorded. No second handover, stock issue, payment or return decision is required by recovery.",
            "سُجلت ملاحظة استلام العميل الأصلية. لا تتطلب الاستعادة تسليمًا ثانيًا أو صرف مخزون أو دفعًا أو قرار مرتجع."),
        LocalRecord(LocalRecordReference(LocalRecordKind.CUSTOMER_RECEIPT_EVENT, DomainId(value.eventOperationId.value), parentId = value.saleId),
            entry.label.ui(), values = listOf(recordValue(fieldLabel(LocalField.CUSTOMER_RECEIVED_AT), literal(value.receivedAt.canonicalString())),
                recordValue(fieldLabel(LocalField.CUSTOMER_RECEIPT_TIME_ZONE), literal(value.receiptTimeZoneId)))), reply.replayed)
}

private fun customerReceiptOutcome(operationId: OperationId, request: LocalRetailCustomerReceiptRequestV1,
    committed: Boolean, code: String, recordedAt: UtcInstant, payload: StoredBytes): LocalRetailCustomerReceiptResultV1 {
    val result = try { LocalRetailCustomerReceiptCodec.decodeResult(payload) }
    catch (_: Exception) { throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN) }
    val valid = when (result) {
        is LocalRetailCustomerReceiptResultV1.Refused -> !committed && code == "sales.customer_receipt.refused"
        is LocalRetailCustomerReceiptResultV1.Applied -> committed && code == "committed" && result.saleId == request.saleId &&
            result.eventOperationId == operationId && request.expectedHeadRevision.value < LocalRetailCustomerReceiptProfileV1.MAX_EVENTS &&
            result.headRevision.value == request.expectedHeadRevision.value + 1 && result.receivedAt == request.receivedAt &&
            result.receiptTimeZoneId == request.receiptTimeZoneId && result.recordedAt == recordedAt && result.receivedAt <= result.recordedAt
    }
    if (!valid) throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
    return result
}
