package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Quantity

/** Fresh preparation reads the selected original page. Recovery never calls this to rebuild it. */
internal suspend fun prepareReturnStock(access: GatewayAccess, intent: LocalCommandIntent): MutationRequest {
    if (intent.action != LocalAction.RETURN_STOCK_POST || intent.formVersion != 1) invalid()
    if (intent.lineField != LocalField.LINES || intent.lines.size !in 1..RETURN_STOCK_PAGE_SIZE) invalid(LocalField.LINES)
    val input = InputValues(intent.values)
    val saleId = input.id(LocalField.SALE_ID)
    val caseId = input.id(LocalField.CASE_ID)
    val returnId = input.id(LocalField.RETURN_ID)
    val ref = intent.reference
    if (ref != null && ref != LocalRecordReference(LocalRecordKind.RETURN_STOCK, returnId, parentId = caseId)) invalid(LocalField.RETURN_ID)
    access.requireAction(intent.action, returnId)
    val caseRevision = input.revision(LocalField.EXPECTED_CASE_REVISION)
    val dispositionRevision = input.revision(LocalField.EXPECTED_DISPOSITION_REVISION)
    val issueId = input.id(LocalField.STOCK_ISSUE_ID)
    val issueRevision = input.revision(LocalField.EXPECTED_ISSUE_REVISION)
    val unitId = input.id(LocalField.UNIT)
    val scale = input.integer(LocalField.QUANTITY_SCALE).takeIf { it in 0L..6L }?.toInt()
        ?: invalid(LocalField.QUANTITY_SCALE)
    val accepted = returnStockInputQuantity(input, LocalField.ACCEPTED_QUANTITY, unitId, scale)
    val posted = returnStockInputQuantity(input, LocalField.EXPECTED_POSTED_QUANTITY, unitId, scale)
    val after = input.optionalId(LocalField.RETURN_STOCK_AFTER_ALLOCATION_ID)
    val restorationId = input.id(LocalField.RESTORATION_ID)
    val evidence = LocalCashEvidenceV1(input.id(LocalField.EVIDENCE_ID), input.instant(LocalField.OCCURRED_AT))
    input.finish()
    if (restorationId == returnId || restorationId == issueId) invalid(LocalField.RESTORATION_ID)
    if (dispositionRevision.value == Long.MAX_VALUE) invalid(LocalField.EXPECTED_DISPOSITION_REVISION)
    val cut = returnStockRead(access, caseId, returnId, saleId, after) ?: unavailable()
    val stock = cut.value
    if (stock.caseRevision != caseRevision) invalid(LocalField.EXPECTED_CASE_REVISION)
    if (stock.dispositionRevision != dispositionRevision) invalid(LocalField.EXPECTED_DISPOSITION_REVISION)
    if (stock.issueId != issueId) invalid(LocalField.STOCK_ISSUE_ID)
    if (stock.issueRevision != issueRevision) invalid(LocalField.EXPECTED_ISSUE_REVISION)
    if (stock.acceptedQuantity != accepted) invalid(LocalField.ACCEPTED_QUANTITY)
    if (stock.postedQuantity != posted) invalid(LocalField.EXPECTED_POSTED_QUANTITY)
    if (stock.requestedDisposition !in supportedReturnStockDispositions) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    if (stock.remainingQuantity.scaled <= 0L) invalid(LocalField.QUANTITY)
    val seen = mutableSetOf<DomainId>()
    var totalScaled = 0L
    val selections = intent.lines.mapNotNull { line ->
        val rowInput = InputValues(line.values)
        val allocationId = rowInput.id(LocalField.ALLOCATION_ID)
        if (!seen.add(allocationId)) invalid(LocalField.ALLOCATION_ID)
        val allocation = stock.allocations.singleOrNull { it.allocationId == allocationId } ?: invalid(LocalField.ALLOCATION_ID)
        val quantity = returnStockInputQuantity(rowInput, LocalField.QUANTITY, unitId, scale)
        val custody = rowInput.optionalChoice(LocalField.CUSTODY)?.let { code ->
            LocalReturnStockCustodyV1.entries.singleOrNull { it.name == code } ?: invalid(LocalField.CUSTODY)
        }
        rowInput.finish()
        if (quantity.scaled < 0L) invalid(LocalField.QUANTITY)
        if (quantity.scaled == 0L) return@mapNotNull null
        if (quantity.scaled > allocation.remainingQuantity.scaled ||
            quantity.scaled > stock.remainingQuantity.scaled - totalScaled) invalid(LocalField.QUANTITY)
        val destination = custody ?: invalid(LocalField.CUSTODY)
        if (stock.requestedDisposition == LocalReturnDispositionV1.QUARANTINE && destination != LocalReturnStockCustodyV1.QUARANTINE ||
            destination == LocalReturnStockCustodyV1.RELEASED && (allocation.currentSafety != "CLEAR" ||
                allocation.expiresAt?.let { cut.reply.observedAt >= it } == true)) invalid(LocalField.CUSTODY)
        // The source header and each selected allocation are independently conserving.
        // A positive subset may be smaller than either ceiling; no FEFO or proportional fill.
        totalScaled += quantity.scaled
        LocalReturnStockSelectionV1(allocationId, quantity, destination)
    }.sortedBy { it.allocationId.value }
    if (selections.isEmpty()) invalid(LocalField.QUANTITY)
    return LocalReturnStockCodecV1.request(intent.operationId, LocalReturnStockRequestV1(
        saleId, caseId, returnId, caseRevision, dispositionRevision, accepted, posted, issueId, issueRevision,
        restorationId, selections, evidence))
}

private fun returnStockInputQuantity(input: InputValues, field: LocalField, unitId: DomainId, scale: Int): Quantity = try {
    Quantity.parse(unitId, input.decimal(field), scale)
} catch (_: ArithmeticException) { invalid(field) }

/** Exact saved positive total with overflow/contract/acceptance ceiling refusal. */
internal fun returnStockSelectionTotal(value: LocalReturnStockRequestV1): Quantity {
    val accepted = value.acceptedQuantity
    val posted = value.expectedPostedQuantity
    if (accepted.scaled <= 0L || !sameReturnStockUnit(accepted, posted) || posted.scaled !in 0..accepted.scaled ||
        value.selections.size !in 1..LocalReturnStockProfileV1.MAX_SELECTIONS ||
        value.selections.map { it.allocationId }.distinct().size != value.selections.size) unavailable()
    val available = accepted.scaled - posted.scaled
    var total = 0L
    value.selections.forEach { selection ->
        if (!sameReturnStockUnit(accepted, selection.quantity) || selection.quantity.scaled <= 0L ||
            selection.quantity.scaled > available - total) unavailable()
        total += selection.quantity.scaled
    }
    return Quantity(accepted.unitId, total, accepted.scale)
}

/** Review derives from every exact canonical selection, not current rows or truncated UI input. */
internal fun returnStockIntentSummary(request: MutationRequest): List<LocalRecordValue> {
    if (request.command != LocalReturnStockProfileV1.COMMAND) unavailable()
    val saved = LocalReturnStockCodecV1.decode(request.payload)
    val total = returnStockSelectionTotal(saved)
    val remaining = saved.acceptedQuantity - saved.expectedPostedQuantity - total
    return buildList {
        add(recordValue(text("Immutable original sale / case / accepted return", "البيع الأصلي الثابت / المطالبة / المرتجع المقبول"),
            literal("${saved.saleId.value}\n${saved.caseId.value}\n${saved.returnId.value}")))
        add(recordValue(text("Exact expected CASE / disposition / issue revisions", "مراجعات المطالبة والتصرف وإذن الصرف المتوقعة الدقيقة"),
            literal("${saved.expectedCaseRevision.value} / ${saved.expectedDispositionRevision.value} / ${saved.expectedIssueRevision.value}")))
        add(recordValue(text("Original stock issue / this posting ID", "إذن صرف المخزون الأصلي / معرف الترحيل هذا"),
            literal("${saved.issueId.value}\n${saved.restorationId.value}")))
        add(recordValue(fieldLabel(LocalField.ACCEPTED_QUANTITY), quantityText(saved.acceptedQuantity)))
        add(recordValue(fieldLabel(LocalField.EXPECTED_POSTED_QUANTITY), quantityText(saved.expectedPostedQuantity)))
        add(recordValue(text("Explicit positive quantity in this posting", "الكمية الموجبة الصريحة في هذا الترحيل"), quantityText(total)))
        add(recordValue(text("Acceptance stock remaining if this exact posting commits", "مخزون القبول المتبقي إذا ثبت الترحيل الدقيق"), quantityText(remaining)))
        add(recordValue(text("Actual inspection evidence / attested UTC time", "إثبات الفحص الفعلي / وقت UTC المذكور"),
            literal("${saved.inspectionEvidence.evidenceId.value}\n${saved.inspectionEvidence.occurredAt.canonicalString()}")))
        saved.selections.chunked(2).forEachIndexed { index, selections ->
            add(recordValue(text("Original allocation selections ${index * 2 + 1}–${index * 2 + selections.size}",
                "التخصيصات الأصلية المختارة ${index * 2 + 1}–${index * 2 + selections.size}"), literal(selections.joinToString("\n") {
                    "${it.allocationId.value} · ${fixedDecimal(it.quantity.scaled, it.quantity.scale)} · ${it.quantity.unitId.value} · ${it.custody.name}"
                })))
        }
        add(recordValue(text("Separate stock-only operation", "عملية مخزون مستقلة فقط"), text(
            "No payout, new allocation, current cost calculation or automatic restock. Quarantine is not sellable. Recover the original saved operation after uncertainty.",
            "لا صرف أو تخصيص جديد أو حساب تكلفة حالية أو إعادة مخزون تلقائية. الحجر غير متاح للبيع. استعد العملية الأصلية المحفوظة عند عدم اليقين.")))
    }
}
