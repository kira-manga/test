package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.types.*

/** Stage one new v2 command. Submit/recovery continue to use the already saved canonical body. */
internal suspend fun prepareUnpricedArrivalV2(access: GatewayAccess, intent: LocalCommandIntent): MutationRequest {
    if (intent.formVersion != 2 || intent.action !in unpricedArrivalActions) invalid()
    if (!access.installed.has(unpricedArrivalCommandV2(intent.action))) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    if (intent.arrivalEvidence == null) invalid(LocalField.ARRIVAL_SELECTION_DIGEST)
    return if (intent.action == LocalAction.ARRIVAL_RECORD) prepareArrivalRecordV2(access, intent)
        else prepareArrivalTransitionV2(access, intent)
}

private fun ArrivalSourceCutV2.requireRequesterRevision(expected: Revision) {
    requireAvailable()
    if (value.requesterPolicyRevision != expected) invalid(LocalField.EXPECTED_REQUESTER_POLICY_REVISION)
}

/** Complete frozen selections are compared with fresh protected sources; nothing is filled in. */
private fun arrivalEvidenceInputV2(access: GatewayAccess, intent: LocalCommandIntent, input: InputValues,
    supplierId: DomainId): LocalArrivalEvidenceSelectionV2 {
    val selected = intent.arrivalEvidence ?: invalid(LocalField.ARRIVAL_SELECTION_DIGEST)
    if (input.text(LocalField.ARRIVAL_SELECTION_DIGEST) != selected.formSourceDigest.value ||
        input.text(LocalField.ARRIVAL_SELECTION_DETAILS) != selected.sourceDescription) invalid(LocalField.ARRIVAL_SELECTION_DIGEST)
    if (input.revision(LocalField.EXPECTED_REQUESTER_POLICY_REVISION) != selected.requesterPolicyRevision)
        invalid(LocalField.EXPECTED_REQUESTER_POLICY_REVISION)
    if (selected.policy.scope != access.view.scope || selected.catalog.scope != access.view.scope ||
        selected.documents.any { it.scope != access.view.scope || it.supplierId != supplierId }) invalid(LocalField.ARRIVAL_SELECTION_DIGEST)
    if (selected.rule.action != intent.action || !selected.rule.directOnly &&
        (selected.rule.approvalRoute == null || selected.rule.ruleDetails == null) ||
        input.choice(LocalField.ARRIVAL_RULE_ID) != selected.rule.ruleId.value) invalid(LocalField.ARRIVAL_RULE_ID)
    if (selected.retention != selected.rule.retention || input.choice(LocalField.ARRIVAL_RETENTION) != selected.retention.choiceCode)
        invalid(LocalField.ARRIVAL_RETENTION)
    val references = input.optionalReferences(LocalField.ARRIVAL_ORIGINAL_DOCUMENTS, LocalRecordKind.PURCHASING_DOCUMENT)
    if (references != selected.documents.map { it.reference }) invalid(LocalField.ARRIVAL_ORIGINAL_DOCUMENTS)
    val mode = input.choice(LocalField.ARRIVAL_DOCUMENT_SELECTION)
    if (mode != "NONE" && mode != "SELECTED" || (mode == "NONE") != references.isEmpty()) invalid(LocalField.ARRIVAL_DOCUMENT_SELECTION)
    if (selected.documents.size !in selected.rule.minimumDocuments..selected.rule.maximumDocuments ||
        selected.documents.any { it.media !in selected.rule.media || it.purpose !in selected.rule.purposes }) invalid(LocalField.ARRIVAL_ORIGINAL_DOCUMENTS)
    return selected.storeSelection(input.text(LocalField.OBSERVATION_REFERENCE))
}

private fun requireCurrentEvidenceV2(intent: LocalCommandIntent, evidence: LocalArrivalEvidenceSelectionV2,
    source: ArrivalSourceCutV2) {
    val selected = intent.arrivalEvidence ?: invalid(LocalField.ARRIVAL_SELECTION_DIGEST)
    source.requireRequesterRevision(selected.requesterPolicyRevision)
    val actual = source.value.purchasing ?: throw GatewayFailure(LocalServiceProblem.NOT_CONFIGURED)
    if (!arrivalPolicyCurrentV2(actual, source.reply.observedAt) || actual.policy != evidence.policy || actual.catalog != evidence.catalog)
        invalid(LocalField.ARRIVAL_SELECTION_DIGEST)
    val rule = actual.rules.singleOrNull { it.ruleId == evidence.ruleId } ?: invalid(LocalField.ARRIVAL_RULE_ID)
    if (!arrivalRuleMatchesV2(selected.rule, rule)) invalid(LocalField.ARRIVAL_RULE_ID)
    if (actual.documents != evidence.documents) invalid(LocalField.ARRIVAL_ORIGINAL_DOCUMENTS)
    if (rule.retention != evidence.retention) invalid(LocalField.ARRIVAL_RETENTION)
    // Exact metadata is still not original authority. The Master reloads bytes/history at Apply.
}

private fun arrivalItemInputV2(input: InputValues): LocalArrivalItemSelectionV2 {
    val sku = input.reference(LocalField.SKU_ID, LocalRecordKind.SKU)
    if (sku.parentId != null) invalid(LocalField.SKU_ID)
    val scale = input.integer(LocalField.QUANTITY_SCALE)
    if (scale !in 0L..6L) invalid(LocalField.QUANTITY_SCALE)
    return LocalArrivalItemSelectionV2(sku.id, input.id(LocalField.PRODUCT_ID), input.revision(LocalField.EXPECTED_PRODUCT_REVISION),
        input.id(LocalField.UNIT), scale.toInt(), input.revision(LocalField.EXPECTED_CATALOG_REVISION),
        sku.revision ?: invalid(LocalField.SKU_ID), input.revision(LocalField.EXPECTED_CATALOG_STORAGE_REVISION),
        input.optionalRevision(LocalField.EXPECTED_ITEM_STORAGE_REVISION))
}

private suspend fun prepareArrivalRecordV2(access: GatewayAccess, intent: LocalCommandIntent): MutationRequest {
    if (intent.lineField != LocalField.LINES || intent.lines.size !in 1..16) invalid(LocalField.LINES)
    val input = InputValues(intent.values)
    val id = input.id(LocalField.ARRIVAL_ID)
    access.requireArrivalRuleV2(checkNotNull(intent.arrivalEvidence).rule, id)
    val supplierId = input.id(LocalField.SUPPLIER_ID)
    val supplierRevision = input.revision(LocalField.EXPECTED_SUPPLIER_STORAGE_REVISION)
    val warehouseId = input.id(LocalField.WAREHOUSE_ID)
    val warehouseRevision = input.revision(LocalField.EXPECTED_WAREHOUSE_STORAGE_REVISION)
    intent.reference?.let { if (it.kind != LocalRecordKind.ARRIVAL_WAREHOUSE_V2 || it.id != warehouseId || it.parentId != supplierId)
        invalid(LocalField.WAREHOUSE_ID) }
    val evidence = arrivalEvidenceInputV2(access, intent, input, supplierId)
    val requester = checkNotNull(intent.arrivalEvidence).requesterPolicyRevision
    val supplierCut = arrivalSourceV2(access, LocalArrivalSourceQueryV2(LocalArrivalSourceKindV1.SUPPLIER, supplierId))
    supplierCut.requireRequesterRevision(requester)
    val supplier = supplierCut.value.suppliers?.singleOrNull() ?: invalid(LocalField.SUPPLIER_ID)
    if (!supplier.active || supplier.storageRevision != supplierRevision) invalid(LocalField.EXPECTED_SUPPLIER_STORAGE_REVISION)
    val warehouseCut = arrivalSourceV2(access, LocalArrivalSourceQueryV2(LocalArrivalSourceKindV1.WAREHOUSE, warehouseId, supplierId = supplierId))
    warehouseCut.requireRequesterRevision(requester)
    val warehouse = warehouseCut.value.warehouses?.singleOrNull() ?: invalid(LocalField.WAREHOUSE_ID)
    val expectedWarehouse = LocalArrivalWarehouseSelectionV2(warehouseId, warehouseRevision)
    if (!warehouse.active || warehouse.selection != expectedWarehouse) invalid(LocalField.EXPECTED_WAREHOUSE_STORAGE_REVISION)
    val items = linkedMapOf<DomainId, LocalArrivalItemSelectionV2>()
    val current = linkedMapOf<DomainId, LocalArrivalItemViewV2>()
    val lines = intent.lines.map { row ->
        val line = InputValues(row.values)
        val expected = arrivalItemInputV2(line)
        val actual = current[expected.skuId] ?: arrivalSourceV2(access,
            LocalArrivalSourceQueryV2(LocalArrivalSourceKindV1.ITEM, expected.skuId, supplierId = supplierId)).let { cut ->
                cut.requireRequesterRevision(requester)
                cut.value.items?.singleOrNull()?.also { current[expected.skuId] = it }
            } ?: invalid(LocalField.SKU_ID)
        val tracking = actual.tracking ?: invalid(LocalField.TRACKING)
        if (!actual.active || expected != actual.selection || line.choice(LocalField.TRACKING) != tracking.name ||
            line.flag(LocalField.REQUIRES_EXPIRY) != actual.requiresExpiry) invalid(LocalField.EXPECTED_CATALOG_STORAGE_REVISION)
        if (expected.skuId in items && items[expected.skuId] != expected) invalid(LocalField.SKU_ID)
        items[expected.skuId] = expected
        val quantity = arrivalQuantityInputV2(expected.baseUnitId, line.decimal(LocalField.QUANTITY), expected.quantityScale)
        val lot = line.optionalId(LocalField.LOT_ID); val expiry = line.optionalInstant(LocalField.EXPIRY_AT)
        val serialId = line.optionalId(LocalField.SERIAL_ID); val serialCode = line.optionalText(LocalField.SERIAL_CODE)
        val serialRevision = line.optionalRevision(LocalField.EXPECTED_SERIAL_REGISTRATION_REVISION)
        if (tracking == LocalArrivalTrackingV1.SERIAL) {
            requireWholeSerialQuantityV2(quantity, serialId != null || serialCode != null)
            if (lot != null || expiry != null) invalid(LocalField.LOT_ID)
            checkArrivalSerialCodeV2(serialCode)
            if (serialId != null) requireArrivalSerialSourceV2(access, supplierId, expected.skuId, serialId, serialCode, serialRevision,
                requester, LocalArrivalActionV1.RECORD)
            else if (serialRevision != null) invalid(LocalField.EXPECTED_SERIAL_REGISTRATION_REVISION)
        } else {
            if (serialId != null || serialCode != null || serialRevision != null) invalid(LocalField.SERIAL_ID)
            if (tracking == LocalArrivalTrackingV1.QUANTITY && (lot != null || expiry != null)) invalid(LocalField.LOT_ID)
        }
        val result = LocalArrivalLineV2(row.lineId, line.id(LocalField.SOURCE_ID), line.id(LocalField.CUSTODY_UNIT_ID),
            expected.skuId, warehouseId, quantity, lot, expiry, serialId, serialCode, serialRevision,
            enumChoice<LocalArrivalDispositionV1>(line.choice(LocalField.DISPOSITION), LocalField.DISPOSITION),
            enumChoice<LocalArrivalOwnershipV1>(line.choice(LocalField.OWNERSHIP), LocalField.OWNERSHIP), line.optionalText(LocalField.OWNERSHIP_REFERENCE))
        line.finish()
        result
    }
    val selected = arrivalSourceV2(access, LocalArrivalSourceQueryV2(LocalArrivalSourceKindV1.SUPPLIER, supplierId,
        supplierId = supplierId, skuIds = items.keys.toList(), documentIds = evidence.documents.map { it.documentId }))
    requireCurrentEvidenceV2(intent, evidence, selected)
    if (selected.value.suppliers?.singleOrNull() != supplier) invalid(LocalField.EXPECTED_SUPPLIER_STORAGE_REVISION)
    val request = LocalRecordUnpricedArrivalV2(id, supplierId, supplierRevision, requester,
        input.optionalText(LocalField.SUPPLIER_DELIVERY_REFERENCE), input.optionalId(LocalField.LOCAL_INTAKE_TAG),
        input.optionalId(LocalField.DOCUMENTARY_PO_ID), input.optionalText(LocalField.DISCREPANCY_REFERENCE),
        items.values.toList(), listOf(expectedWarehouse), lines, evidence, input.text(LocalField.REASON))
    input.finish() // Prices, grants and other unknown input are refused, never ignored.
    return LocalUnpricedArrivalCommandsV2.record(intent.operationId, request)
}

private suspend fun prepareArrivalTransitionV2(access: GatewayAccess, intent: LocalCommandIntent): MutationRequest {
    if (intent.lines.isNotEmpty() || intent.lineField != null) invalid(LocalField.LINES)
    val input = InputValues(intent.values)
    val id = input.id(LocalField.ARRIVAL_ID)
    access.requireArrivalRuleV2(checkNotNull(intent.arrivalEvidence).rule, id)
    val revision = input.revision(LocalField.EXPECTED_ARRIVAL_REVISION)
    val custodyUnitId = input.id(LocalField.CUSTODY_UNIT_ID)
    intent.reference?.let { if (it.kind != LocalRecordKind.ARRIVAL_SLICE_V2 || it.id != custodyUnitId || it.parentId != id)
        invalid(LocalField.CUSTODY_UNIT_ID) }
    val detailCut = arrivalReadV2(access, LocalArrivalQueryV1(id))
    if (detailCut.value.availability != LocalArrivalAvailabilityV1.AVAILABLE) unavailable()
    val detail = detailCut.value.detail ?: invalid(LocalField.ARRIVAL_ID)
    if (detail.header.arrivalRevision != revision) invalid(LocalField.EXPECTED_ARRIVAL_REVISION)
    val slice = detail.slices.singleOrNull { it.custodyUnitId == custodyUnitId } ?: invalid(LocalField.CUSTODY_UNIT_ID)
    if (intent.action == LocalAction.ARRIVAL_DISPATCH && slice.disposition != LocalArrivalDispositionV1.REJECTED_HELD ||
        intent.action != LocalAction.ARRIVAL_DISPATCH && slice.disposition !in setOf(LocalArrivalDispositionV1.UNINSPECTED_HELD,
            LocalArrivalDispositionV1.PASSED_HELD)) invalid(LocalField.DISPOSITION)
    val supplierId = input.id(LocalField.SUPPLIER_ID)
    if (supplierId != detail.header.supplierId) invalid(LocalField.SUPPLIER_ID)
    val supplierRevision = input.revision(LocalField.EXPECTED_SUPPLIER_STORAGE_REVISION)
    val expectedItem = arrivalItemInputV2(input)
    if (expectedItem.skuId != slice.skuId || expectedItem.baseUnitId != slice.quantity.unitId ||
        expectedItem.quantityScale != slice.quantity.scale) invalid(LocalField.SKU_ID)
    val expectedWarehouse = LocalArrivalWarehouseSelectionV2(input.id(LocalField.WAREHOUSE_ID),
        input.revision(LocalField.EXPECTED_WAREHOUSE_STORAGE_REVISION))
    if (expectedWarehouse.warehouseId != slice.warehouseId) invalid(LocalField.WAREHOUSE_ID)
    val quantity = arrivalQuantityInputV2(expectedItem.baseUnitId, input.decimal(LocalField.QUANTITY), expectedItem.quantityScale)
    if (quantity.scaled <= 0 || quantity > slice.quantity) invalid(LocalField.QUANTITY)
    val splittable = slice.tracking != LocalArrivalTrackingV1.SERIAL || slice.serialId == null && slice.serialCode == null
    if (slice.tracking == LocalArrivalTrackingV1.SERIAL) requireWholeSerialQuantityV2(quantity, !splittable)
    val split = if (splittable) {
        val reserved = LocalArrivalSplitV2(input.id(LocalField.SPLIT_SOURCE_ID), input.id(LocalField.SPLIT_CUSTODY_UNIT_ID))
        if (quantity < slice.quantity) reserved else null
    } else {
        if (quantity != slice.quantity) invalid(LocalField.QUANTITY)
        null
    }
    val eventId = input.id(LocalField.ARRIVAL_EVENT_ID)
    val evidence = arrivalEvidenceInputV2(access, intent, input, supplierId)
    val requester = checkNotNull(intent.arrivalEvidence).requesterPolicyRevision
    if (detailCut.value.requesterPolicyRevision != requester) invalid(LocalField.EXPECTED_REQUESTER_POLICY_REVISION)
    val action = arrivalPhysicalActionV2(intent.action)
    val source = arrivalSourceV2(access, LocalArrivalSourceQueryV2(LocalArrivalSourceKindV1.SUPPLIER, supplierId,
        supplierId = supplierId, action = action, skuIds = listOf(slice.skuId), documentIds = evidence.documents.map { it.documentId }))
    requireCurrentEvidenceV2(intent, evidence, source)
    val supplier = source.value.suppliers?.singleOrNull() ?: invalid(LocalField.SUPPLIER_ID)
    if (!supplier.active || supplier.storageRevision != supplierRevision) invalid(LocalField.EXPECTED_SUPPLIER_STORAGE_REVISION)
    val itemCut = arrivalSourceV2(access, LocalArrivalSourceQueryV2(LocalArrivalSourceKindV1.ITEM, slice.skuId, supplierId = supplierId, action = action))
    itemCut.requireRequesterRevision(requester)
    val item = itemCut.value.items?.singleOrNull() ?: invalid(LocalField.SKU_ID)
    if (!item.active || item.selection != expectedItem || item.tracking != slice.tracking) invalid(LocalField.EXPECTED_CATALOG_STORAGE_REVISION)
    val warehouseCut = arrivalSourceV2(access, LocalArrivalSourceQueryV2(LocalArrivalSourceKindV1.WAREHOUSE, slice.warehouseId, supplierId = supplierId, action = action))
    warehouseCut.requireRequesterRevision(requester)
    val warehouse = warehouseCut.value.warehouses?.singleOrNull() ?: invalid(LocalField.WAREHOUSE_ID)
    if (!warehouse.active || warehouse.selection != expectedWarehouse) invalid(LocalField.EXPECTED_WAREHOUSE_STORAGE_REVISION)
    val reason = input.text(LocalField.REASON)
    val request = when (intent.action) {
        LocalAction.ARRIVAL_QUALIFY -> {
            val lot: DomainId?; val expiry: UtcInstant?; val serialId: DomainId?; val serialCode: String?; val serialRevision: Revision?
            if (slice.tracking == LocalArrivalTrackingV1.SERIAL) {
                lot = null; expiry = null
                serialId = input.optionalId(LocalField.SERIAL_ID); serialCode = input.optionalText(LocalField.SERIAL_CODE)
                serialRevision = input.optionalRevision(LocalField.EXPECTED_SERIAL_REGISTRATION_REVISION)
                checkArrivalSerialCodeV2(serialCode)
                if (slice.serialId != null && (serialId != slice.serialId || serialRevision != slice.serialRegistrationRevision) ||
                    slice.serialCode != null && serialCode != slice.serialCode) invalid(LocalField.SERIAL_ID)
                requireWholeSerialQuantityV2(quantity, serialId != null || serialCode != null)
                if (slice.serialId == null && serialId != null) requireArrivalSerialSourceV2(access, supplierId, slice.skuId,
                    serialId, serialCode, serialRevision, requester, action)
                else if (serialId == null && serialRevision != null) invalid(LocalField.EXPECTED_SERIAL_REGISTRATION_REVISION)
            } else {
                serialId = null; serialCode = null; serialRevision = null
                if (slice.tracking == LocalArrivalTrackingV1.LOT) {
                    lot = input.optionalId(LocalField.LOT_ID); expiry = input.optionalInstant(LocalField.EXPIRY_AT)
                    if (slice.lotId != null && lot != slice.lotId || slice.expiresAt != null && expiry != slice.expiresAt) invalid(LocalField.LOT_ID)
                } else { lot = null; expiry = null }
            }
            val passed = input.flag(LocalField.INSPECTION_PASSED)
            val observed = slice.lotId == null && lot != null || slice.expiresAt == null && expiry != null ||
                slice.serialId == null && serialId != null || slice.serialCode == null && serialCode != null ||
                passed && slice.disposition == LocalArrivalDispositionV1.UNINSPECTED_HELD
            if (!observed) invalid(LocalField.OBSERVATION_REFERENCE)
            LocalUnpricedArrivalCommandsV2.qualify(intent.operationId, LocalQualifyUnpricedArrivalV2(eventId, id, revision,
                requester, supplierRevision, expectedItem, expectedWarehouse, custodyUnitId, quantity, split,
                lot, expiry, serialId, serialCode, serialRevision, passed, evidence, reason))
        }
        LocalAction.ARRIVAL_REJECT -> LocalUnpricedArrivalCommandsV2.reject(intent.operationId,
            LocalRejectUnpricedArrivalV2(eventId, id, revision, requester, supplierRevision, expectedItem, expectedWarehouse,
                custodyUnitId, quantity, split, evidence, reason))
        LocalAction.ARRIVAL_DISPATCH -> {
            val recipient = input.id(LocalField.RECIPIENT_SUPPLIER_ID)
            if (recipient != supplierId) invalid(LocalField.RECIPIENT_SUPPLIER_ID)
            LocalUnpricedArrivalCommandsV2.dispatch(intent.operationId, LocalDispatchUnpricedArrivalV2(eventId, id, revision,
                requester, supplierRevision, expectedItem, expectedWarehouse, custodyUnitId, quantity, split, recipient, evidence, reason))
        }
        else -> invalid()
    }
    input.finish()
    return request
}

private fun arrivalQuantityInputV2(unitId: DomainId, value: String, scale: Int): Quantity = try { Quantity.parse(unitId, value, scale) }
    catch (_: ArithmeticException) { invalid(LocalField.QUANTITY) }
    catch (_: IllegalArgumentException) { invalid(LocalField.QUANTITY) }

private fun requireWholeSerialQuantityV2(quantity: Quantity, known: Boolean) {
    val one = Quantity.parse(quantity.unitId, "1", quantity.scale)
    if (quantity.scaled <= 0 || quantity.scaled % one.scaled != 0L || known && quantity != one) invalid(LocalField.QUANTITY)
}

private fun checkArrivalSerialCodeV2(code: String?) {
    if (code != null && (code.isEmpty() || code.length > 128 || code != code.trim() || code.any { it.code < 32 || it.code == 127 } ||
        code.encodeToByteArray(throwOnInvalidSequence = true).size > 256)) invalid(LocalField.SERIAL_CODE)
}

private suspend fun requireArrivalSerialSourceV2(access: GatewayAccess, supplierId: DomainId, skuId: DomainId, serialId: DomainId,
    code: String?, revision: Revision?, requester: Revision, action: LocalArrivalActionV1) {
    val cut = arrivalSourceV2(access, LocalArrivalSourceQueryV2(LocalArrivalSourceKindV1.SERIAL_REGISTRATION, serialId,
        supplierId = supplierId, action = action))
    cut.requireRequesterRevision(requester)
    val serial = cut.value.serials?.singleOrNull() ?: invalid(LocalField.SERIAL_ID)
    if (serial.skuId != skuId || serial.serialCode != code || serial.registrationRevision != revision || cut.reply.observedAt < serial.validFrom ||
        serial.validUntil?.let { cut.reply.observedAt >= it } == true) invalid(LocalField.EXPECTED_SERIAL_REGISTRATION_REVISION)
}

private data class ArrivalIdentityV2(val action: LocalArrivalActionV1, val arrivalId: DomainId,
    val eventId: DomainId?, val before: Revision, val affected: DomainId?)

private fun arrivalIdentityV2(request: MutationRequest): ArrivalIdentityV2 = when (request.command) {
    LocalUnpricedArrivalProfileV2.RECORD -> LocalUnpricedArrivalCommandsV2.decodeRecordRequest(request.payload).let {
        ArrivalIdentityV2(LocalArrivalActionV1.RECORD, it.arrivalId, null, Revision(0), null) }
    LocalUnpricedArrivalProfileV2.QUALIFY -> LocalUnpricedArrivalCommandsV2.decodeQualifyRequest(request.payload).let {
        ArrivalIdentityV2(LocalArrivalActionV1.QUALIFY, it.arrivalId, it.eventId, it.expectedArrivalRevision, it.split?.custodyUnitId ?: it.custodyUnitId) }
    LocalUnpricedArrivalProfileV2.REJECT -> LocalUnpricedArrivalCommandsV2.decodeRejectRequest(request.payload).let {
        ArrivalIdentityV2(LocalArrivalActionV1.REJECT, it.arrivalId, it.eventId, it.expectedArrivalRevision, it.split?.custodyUnitId ?: it.custodyUnitId) }
    LocalUnpricedArrivalProfileV2.DISPATCH -> LocalUnpricedArrivalCommandsV2.decodeDispatchRequest(request.payload).let {
        ArrivalIdentityV2(LocalArrivalActionV1.DISPATCH, it.arrivalId, it.eventId, it.expectedArrivalRevision, it.split?.custodyUnitId ?: it.custodyUnitId) }
    else -> unavailable()
}

internal fun unpricedArrivalTargetV2(request: MutationRequest): DomainId = arrivalIdentityV2(request).arrivalId

internal fun renderUnpricedArrivalRecordedV2(entry: StoredClientIntentV1, reply: MutationReply.Recorded): Pair<LocalText, LocalRecord?> {
    val expected = arrivalIdentityV2(entry.request())
    val value = LocalUnpricedArrivalCommandsV2.decodeResult(reply.result)
    val expectedCode = value.failure?.let { "arrival.${it.name.lowercase()}" } ?: "committed"
    if (reply.operationId != entry.operationId || reply.originalActorId != entry.owner.actorId || reply.code != expectedCode ||
        entry.localAction != arrivalUiActionV2(expected.action) ||
        value.action != expected.action || value.arrivalId != expected.arrivalId || value.eventId != expected.eventId ||
        (value.failure == null) != reply.committed || value.financialStatus != LocalArrivalFinancialStatusV1.UNPRICED_UNRESOLVED ||
        reply.committed && (value.arrivalRevision != expected.before.next() || value.affectedCustodyUnitId != expected.affected)) unavailable()
    value.failure?.let { return text("Recorded v2 arrival refusal: ${it.name}. No custody mutation or financial effect was applied; retained originals are not discarded.",
        "رفض وصول v2 مسجل: ${it.name}. لم يُطبق تغيير عهدة أو أثر مالي؛ لا تُحذف الأصول المحفوظة.") to null }
    val message = if (value.action == LocalArrivalActionV1.DISPATCH) text(
        "Actual departure of rejected held custody recorded to the original supplier. Financial responsibility remains unresolved; no payment, credit or settlement was created.",
        "سُجلت المغادرة الفعلية للعهدة المرفوضة إلى المورد الأصلي. تبقى المسؤولية المالية غير محسومة؛ لم يُنشأ دفع أو إشعار دائن أو تسوية.") else text(
        "Unpriced physical custody observation recorded with immutable documentary use/retention. Goods remain outside sellable/valued stock; no invoice, payable or payment was created.",
        "سُجلت ملاحظة العهدة الفعلية غير المسعرة مع استخدام واحتفاظ توثيقيين ثابتين. تبقى البضاعة خارج المخزون القابل للبيع أو المقيم؛ لم تُنشأ فاتورة أو التزام أو دفع.")
    return message to LocalRecord(LocalRecordReference(LocalRecordKind.ARRIVAL_V2, value.arrivalId, value.arrivalRevision),
        text("Unpriced arrival v2 — financially unresolved", "وصول غير مسعر v2 — غير محسوم ماليًا"), values = buildList {
            add(recordValue(fieldLabel(LocalField.EXPECTED_ARRIVAL_REVISION), literal(checkNotNull(value.arrivalRevision).value.toString())))
            value.eventId?.let { add(recordValue(fieldLabel(LocalField.ARRIVAL_EVENT_ID), literal(it.value))) }
            value.affectedCustodyUnitId?.let { add(recordValue(fieldLabel(LocalField.CUSTODY_UNIT_ID), literal(it.value))) }
        })
}

/** Review comes from the complete immutable command, with no display/input truncation. */
internal fun unpricedArrivalIntentSummaryV2(request: MutationRequest, originalSupplierId: DomainId? = null): List<LocalRecordValue> = buildList {
    fun addValue(english: String, arabic: String, value: String) { add(recordValue(text(english, arabic), literal(value))) }
    fun source(value: LocalArrivalSourceSelectionV2): String =
        "${value.kind} / ${value.id.value} / storage ${value.storageRevision.value} / ${value.digest.value}"
    fun item(value: LocalArrivalItemSelectionV2): String = "SKU ${value.skuId.value} / revision ${value.skuRevision.value}; " +
        "product ${value.productId.value} / revision ${value.productRevision.value}; unit ${value.baseUnitId.value} / scale ${value.quantityScale}; " +
        "SKU catalog coordinate ${value.catalogRevision.value}; catalog storage ${value.storageRevision.value}; " +
        "owned-stock storage ${value.stockStorageRevision?.value?.toString() ?: "verified absent"}"
    fun evidence(value: LocalArrivalEvidenceSelectionV2, requester: Revision) {
        addValue("Original requester employee-policy revision", "مراجعة سياسة موظف الطلب الأصلي", requester.value.toString())
        val policy = value.policy
        addValue("Exact purchasing policy selection", "اختيار سياسة الشراء الدقيق",
            "Organization ${policy.scope.organizationId.value}; company ${policy.scope.companyId.value}; branch ${policy.scope.branchId.value}; policy ${policy.policyId.value}; " +
                "publication ${policy.publicationId.value} / revision ${policy.publicationRevision.value}; " +
                "valid ${policy.validFrom.canonicalString()} until ${policy.validUntil?.canonicalString() ?: "no supplied end"}\n" +
                "Active ${source(policy.activePointer)}\nPublication ${source(policy.publication)}")
        val catalog = value.catalog
        addValue("Exact complete catalog selection", "اختيار الكتالوج الكامل الدقيق",
            "Organization ${catalog.scope.organizationId.value}; company ${catalog.scope.companyId.value}; branch ${catalog.scope.branchId.value}; " +
                "catalog ${catalog.catalogId.value}; publication ${catalog.publicationId.value} / revision ${catalog.revision.value}\n" +
                "Active ${source(catalog.activePointer)}\nPublication ${source(catalog.publication)}")
        addValue("Explicit rule / retention / ordered-original decision", "قرار القاعدة والاحتفاظ والأصول المرتبة الصريح",
            "Rule ${value.ruleId.value}; retention class ${value.retention.classId.value} / revision ${value.retention.revision.value}; " +
                "${value.documents.size} selected originals (including an explicit zero)")
        addValue("Physical observation reference", "مرجع الملاحظة الفعلية", value.observationReference)
        value.documents.forEachIndexed { index, document -> addValue("Original ${index + 1} — exact operation order", "الأصل ${index + 1} — ترتيب العملية الدقيق",
            "Organization ${document.scope.organizationId.value}; company ${document.scope.companyId.value}; branch ${document.scope.branchId.value}; supplier ${document.supplierId.value}\n" +
                "Document ${document.documentId.value}; storage ${document.storageRevision.value}; ${document.byteLength} bytes; " +
                "${document.media} / ${document.purpose}; ${document.exactBytesDigest.value}" +
                (document.parentDocumentId?.let { "\nParent ${it.value}; head ${source(checkNotNull(document.parentHead))}" } ?: "\nNot supplemental")) }
    }
    if (request.command == LocalUnpricedArrivalProfileV2.RECORD) {
        val value = LocalUnpricedArrivalCommandsV2.decodeRecordRequest(request.payload)
        addValue("Arrival / supplier / source revision", "الوصول والمورد ومراجعة المصدر",
            "${value.arrivalId.value} / ${value.supplierId.value} / ${value.expectedSupplierRevision.value}")
        addValue("Original delivery / local tag / documentary links", "التسليم الأصلي والوسم المحلي والروابط التوثيقية",
            "Delivery ${value.supplierDeliveryReference ?: "not selected"}; tag ${value.localIntakeTag?.value ?: "not selected"}; " +
                "documentary PO ${value.documentaryPoId?.value ?: "none"}; discrepancy ${value.discrepancyReference ?: "none"}")
        evidence(value.evidence, value.expectedRequesterPolicyRevision)
        addValue("Physical reason", "السبب الفعلي", value.reasonCode)
        value.lines.forEachIndexed { index, line ->
            val selected = value.items.single { it.skuId == line.skuId }
            val warehouse = value.warehouses.single { it.warehouseId == line.warehouseId }
            addValue("Observed line ${index + 1}", "السطر المرصود ${index + 1}",
                "Line ${line.lineId.value}; source ${line.sourceId.value}; custody ${line.custodyUnitId.value}\n${item(selected)}\n" +
                    "Warehouse ${warehouse.warehouseId.value} / storage ${warehouse.storageRevision.value}; quantity ${quantityText(line.baseQuantity).english}\n" +
                    "Disposition ${line.disposition.name}; ownership ${line.ownership.name}; reference ${line.ownershipReference ?: "none"}\n" +
                    "Lot ${line.lotId?.value ?: "unobserved"}; expiry ${line.expiresAt?.canonicalString() ?: "unobserved"}; " +
                    "serial ${line.serialId?.value ?: "unresolved"}; exact code ${line.serialCode ?: "unobserved"}; registration ${line.serialRegistrationRevision?.value ?: "none"}")
        }
    } else {
        val identity = arrivalIdentityV2(request)
        addValue("Arrival / event / expected revision", "الوصول والحدث والمراجعة المتوقعة",
            "${identity.arrivalId.value} / ${checkNotNull(identity.eventId).value} / ${identity.before.value}")
        originalSupplierId?.let {
            // Captured from the original input already matched to the protected arrival header
            // during prepare. Transition bytes intentionally do not carry a supplier override.
            addValue("Exact original arrival supplier", "مورد الوصول الأصلي الدقيق", it.value)
        }
        fun physical(supplier: Revision, selected: LocalArrivalItemSelectionV2, warehouse: LocalArrivalWarehouseSelectionV2,
            custody: DomainId, quantity: Quantity, split: LocalArrivalSplitV2?, reason: String) {
            addValue("Exact current source selection", "اختيار المصدر الحالي الدقيق",
                "Original supplier storage ${supplier.value}; ${item(selected)}; warehouse ${warehouse.warehouseId.value} / storage ${warehouse.storageRevision.value}")
            addValue("Exact selected portion / split identities", "الجزء المختار وهويات التقسيم الدقيقة",
                "Custody ${custody.value}; quantity ${quantityText(quantity).english}; " +
                    (split?.let { "new source ${it.sourceId.value}; new custody ${it.custodyUnitId.value}" } ?: "whole portion retains identity"))
            addValue("Physical reason", "السبب الفعلي", reason)
        }
        when (request.command) {
            LocalUnpricedArrivalProfileV2.QUALIFY -> LocalUnpricedArrivalCommandsV2.decodeQualifyRequest(request.payload).let {
                evidence(it.evidence, it.expectedRequesterPolicyRevision)
                physical(it.expectedSupplierRevision, it.item, it.warehouse, it.custodyUnitId, it.quantity, it.split, it.reasonCode)
                addValue("Explicit new tracking / inspection observations", "ملاحظات التتبع والفحص الجديدة الصريحة",
                    "Lot ${it.lotId?.value ?: "unobserved"}; expiry ${it.expiresAt?.canonicalString() ?: "unobserved"}; " +
                        "serial ${it.serialId?.value ?: "unresolved"}; exact code ${it.serialCode ?: "unobserved"}; " +
                        "registration ${it.serialRegistrationRevision?.value ?: "none"}; inspection passed ${it.inspectionPassed}")
            }
            LocalUnpricedArrivalProfileV2.REJECT -> LocalUnpricedArrivalCommandsV2.decodeRejectRequest(request.payload).let {
                evidence(it.evidence, it.expectedRequesterPolicyRevision)
                physical(it.expectedSupplierRevision, it.item, it.warehouse, it.custodyUnitId, it.quantity, it.split, it.reasonCode)
            }
            LocalUnpricedArrivalProfileV2.DISPATCH -> LocalUnpricedArrivalCommandsV2.decodeDispatchRequest(request.payload).let {
                evidence(it.evidence, it.expectedRequesterPolicyRevision)
                physical(it.expectedSupplierRevision, it.item, it.warehouse, it.custodyUnitId, it.quantity, it.split, it.reasonCode)
                addValue("Exact original supplier recipient", "المورد الأصلي المستلم الدقيق", it.recipientSupplierId.value)
            }
            else -> unavailable()
        }
    }
    add(recordValue(text("Financial / stock meaning", "المعنى المالي والمخزني"), text(
        "UNPRICED_UNRESOLVED. No sellable stock, owned value, invoice, payable, payment, cash movement or PO allocation.",
        "غير مسعر وغير محسوم. لا مخزون قابل للبيع أو قيمة مملوكة أو فاتورة أو التزام أو دفع أو حركة نقدية أو تخصيص أمر شراء.")))
    require(size in 1..32)
}
