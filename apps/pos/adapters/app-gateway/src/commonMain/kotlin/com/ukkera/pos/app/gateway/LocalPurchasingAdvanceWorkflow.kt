package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.sales.store.LocalSupplierCashSource
import com.ukkera.pos.types.*

/** One actual cost-authorized source cut. No omitted pin is synthesized by this mapper. */
internal suspend fun supplierSettlementSource(access: GatewayAccess,
    query: LocalSupplierSettlementSourceQueryV1): LocalSupplierSettlementSourceV1 {
    val value = LocalSupplierSettlementCodec.source(access.read(ReadRequest(LocalSupplierSettlementProfile.READ_SOURCE,
        LocalSupplierSettlementCodec.source(query))).payload)
    if (value.query != query) unavailable()
    if (value.unavailableCode == "purchasing.capacity_unavailable") throw GatewayFailure(LocalServiceProblem.PURCHASING_CAPACITY_REQUIRED)
    if (value.unavailableCode != null) unavailable()
    val source = value.source ?: unavailable()
    if (source.supplierId != query.supplierId || value.designatedPostingBranch != access.view.scope.branchId) unavailable()
    when (source) {
        is LocalSupplierCashSource.Advance -> if (query.invoiceId != null || source.expectedSupplierRevision.value <= 0L ||
            listOf(value.allocatedPayment, value.appliedCredit, value.reservedCash, value.availableToAllocate).any { it != null }) unavailable()
        is LocalSupplierCashSource.Invoice -> {
            val allocated = value.allocatedPayment ?: unavailable()
            val credit = value.appliedCredit ?: unavailable()
            val reserved = value.reservedCash ?: unavailable()
            val available = value.availableToAllocate ?: unavailable()
            val creditRevision = source.expectedCreditRevision
            val settlementRevision = source.expectedSettlementRevision
            if (query.invoiceId != source.invoiceId || listOf(source.expectedSupplierRevision, source.expectedInvoiceRevision,
                    source.expectedProgressRevision, source.expectedPayableRevision).any { it.value <= 0L } ||
                creditRevision != null && creditRevision.value <= 0L || settlementRevision != null && settlementRevision.value <= 0L ||
                listOf(allocated, credit, reserved, available).any { it.currency != Currency.EGP || it.minor < 0L } ||
                reserved != source.expectedReservedCash || reserved.minor > source.expectedRemainingDue.minor ||
                source.expectedRemainingDue - reserved != available ||
                creditRevision == null && credit.minor != 0L || settlementRevision == null && (allocated.minor != 0L || reserved.minor != 0L)) unavailable()
        }
    }
    val cash = value.availableCash
    if (query.sessionId == null) {
        if (listOf(value.sessionRevision, value.drawerRevision, value.custodianRevision, value.workRevision).any { it != null } ||
            cash != null) unavailable()
    } else if (listOf(value.sessionRevision, value.drawerRevision, value.custodianRevision, value.workRevision).any {
            it == null || it.value <= 0L
        } || cash == null || cash.currency != Currency.EGP || cash.minor < 0L) unavailable()
    val advanceRevision = value.advanceRevision
    val advanceAllocationRevision = value.advanceAllocationRevision
    val unappliedAdvance = value.unappliedAdvance
    if (query.advanceId == null) {
        if (advanceRevision != null || advanceAllocationRevision != null || unappliedAdvance != null) unavailable()
    } else if (advanceRevision == null || advanceRevision.value <= 0L || advanceAllocationRevision == null ||
        advanceAllocationRevision.value <= 0L || unappliedAdvance == null ||
        unappliedAdvance.currency != Currency.EGP || unappliedAdvance.minor < 0L) unavailable()
    return value
}

internal suspend fun supplierAdvanceWorkflow(access: GatewayAccess, query: LocalWorkflowQuery): LocalWorkflowPage {
    val controls = query.purchasing ?: invalid()
    if (!controls.includeCosts) return LocalWorkflowPage(LocalWorkflow.PURCHASING, emptyList(), emptyList(), text(
        "Paid advances and their unapplied funds require an explicit current cost read; no amount was read or inferred as zero.",
        "تتطلب الدفعات المقدمة المدفوعة وأموالها غير المخصصة قراءة تكلفة صريحة وسارية؛ لم يُقرأ مبلغ أو يُفترض أنه صفر."))
    val reference = query.reference
    if (reference != null && (reference.kind != LocalRecordKind.SUPPLIER_ADVANCE || reference.parentId != null ||
        reference.revision != null || query.cursor != null)) invalid()
    val after = parseCursor(query.cursor, "supplier-advances")
    val requested = LocalSupplierAdvanceQueryV1(id = reference?.id, after = after)
    val reply = access.read(ReadRequest(LocalSupplierSettlementProfile.READ_ADVANCES, LocalSupplierSettlementCodec.advances(requested)))
    val page = LocalSupplierSettlementCodec.advances(reply.payload)
    if (page.unavailableCode == "purchasing.capacity_unavailable") throw GatewayFailure(LocalServiceProblem.PURCHASING_CAPACITY_REQUIRED)
    if (page.unavailableCode != null) unavailable()
    val values = page.advances ?: unavailable()
    if (values.size > requested.limit || values.map { it.id }.distinct().size != values.size ||
        values.zipWithNext().any { (a, b) -> a.id.value >= b.id.value } ||
        after != null && values.any { it.id.value <= after.value } ||
        reference != null && (values.any { it.id != reference.id } || page.nextAfter != null)) unavailable()
    page.nextAfter?.let { next -> if (after != null && next.value <= after.value || values.any { it.id.value > next.value }) unavailable() }
    values.forEach(::requireSupplierAdvanceView)
    val records = values.filter { query.search.isBlank() || it.id.value == query.search || it.supplierId.value == query.search }
        .map(::supplierAdvanceRecord)
    val selected = if (reference == null) null else values.singleOrNull()
    val forms = if (selected != null && selected.unapplied.minor > 0L && access.installed.has(LocalSupplierSettlementProfile.ALLOCATE_ADVANCE) &&
        access.installed.has(LocalSupplierSettlementProfile.READ_SOURCE) && access.allow(LocalAction.SUPPLIER_ADVANCE_ALLOCATE))
        listOf(supplierAdvanceAllocationForm(selected)) else emptyList()
    return LocalWorkflowPage(LocalWorkflow.PURCHASING, records, forms, text(
        "This advance has one original cash payout. Allocation to an invoice is an explicit nonphysical command, limited by the actual unapplied advance and invoice availability. Today's outflow never includes an earlier advance. ${readCut(reply).english}",
        "للدفعة المقدمة صرف نقدي أصلي واحد. تخصيصها لفاتورة أمر صريح غير فعلي، تحده الدفعة غير المخصصة والمتاح الفعلي للفاتورة. لا يشمل صرف اليوم دفعة سابقة. ${readCut(reply).arabic}"),
        cursor("supplier-advances", page.nextAfter)) // Preserve the real cursor, including filtered empty pages.
}

private fun requireSupplierAdvanceView(value: LocalSupplierAdvanceViewV1) {
    if (value.paymentId != value.id || value.revision != Revision(1) || value.allocationRevision.value != value.allocationIds.size.toLong() + 1L ||
        value.allocationIds.size > LocalSupplierSettlementProfile.MAX_ENTRIES || value.allocationIds.distinct().size != value.allocationIds.size ||
        listOf(value.amount, value.allocated, value.unapplied).any { it.currency != Currency.EGP || it.minor < 0L } || value.amount.minor <= 0L ||
        value.allocated.minor > value.amount.minor || value.amount - value.allocated != value.unapplied) unavailable()
}

private fun supplierAdvanceRecord(value: LocalSupplierAdvanceViewV1): LocalRecord = LocalRecord(
    LocalRecordReference(LocalRecordKind.SUPPLIER_ADVANCE, value.id), literal(value.id.value),
    if (value.unapplied.minor == 0L) text("Fully allocated paid advance", "دفعة مقدمة مدفوعة مخصصة بالكامل") else text("Paid advance — unapplied funds", "دفعة مقدمة مدفوعة — أموال غير مخصصة"),
    listOf(recordValue(fieldLabel(LocalField.SUPPLIER_ID), literal(value.supplierId.value)),
        recordValue(text("Original advance payout", "الصرف الأصلي للدفعة المقدمة"), moneyText(value.amount)),
        recordValue(text("Explicitly allocated", "المخصص صراحةً"), moneyText(value.allocated)),
        recordValue(text("Unapplied source funds", "أموال المصدر غير المخصصة"), moneyText(value.unapplied)),
        recordValue(fieldLabel(LocalField.EXPECTED_ADVANCE_REVISION), literal(value.revision.value.toString())),
        recordValue(fieldLabel(LocalField.EXPECTED_ADVANCE_ALLOCATION_REVISION), literal(value.allocationRevision.value.toString())),
        recordValue(text("Original payout recorded at", "وقت تسجيل الصرف الأصلي"), literal(value.recordedAt.canonicalString()))), supplierId = value.supplierId)

private fun supplierAdvanceAllocationForm(value: LocalSupplierAdvanceViewV1): LocalActionForm = form(LocalAction.SUPPLIER_ADVANCE_ALLOCATE, listOf(
    newIdField(LocalField.ALLOCATION_ID), idField(LocalField.ADVANCE_ID, value.id), idField(LocalField.SUPPLIER_ID, value.supplierId),
    revisionField(LocalField.EXPECTED_ADVANCE_REVISION, value.revision),
    revisionField(LocalField.EXPECTED_ADVANCE_ALLOCATION_REVISION, value.allocationRevision),
    moneyField(LocalField.EXPECTED_UNAPPLIED_ADVANCE, value.unapplied).copy(readOnly = true),
    idField(LocalField.INVOICE_ID), moneyField(LocalField.AMOUNT), scalar(LocalField.REASON, LocalInputKind.TEXT, maximum = 128)), text(
        "Select an actual locally posted invoice for this supplier. Save re-reads the exact invoice/advance cut, then shows all immutable pins for review before separate submission. Allocate the entire entered amount or refuse; no cash session or new payout is involved.",
        "اختر فاتورة مرحّلة فعليًا لهذا المورد محليًا. يعيد الحفظ قراءة الحالة الدقيقة للفاتورة والدفعة ثم يعرض قيمها الثابتة للمراجعة قبل إرسال منفصل. يخصص المبلغ المدخل كاملًا أو يرفض؛ دون جلسة نقدية أو صرف جديد."))

internal suspend fun prepareSupplierAdvanceAllocation(access: GatewayAccess, intent: LocalCommandIntent, input: InputValues): MutationRequest {
    if (intent.formVersion != 1 || intent.lineField != null || intent.lines.isNotEmpty()) invalid(LocalField.LINES)
    val allocationId = input.id(LocalField.ALLOCATION_ID)
    val advanceId = input.id(LocalField.ADVANCE_ID)
    val reference = intent.reference
    if (reference == null || reference.kind != LocalRecordKind.SUPPLIER_ADVANCE || reference.id != advanceId || reference.revision != null || reference.parentId != null)
        invalid(LocalField.ADVANCE_ID)
    val supplierId = input.id(LocalField.SUPPLIER_ID)
    val advanceRevision = input.revision(LocalField.EXPECTED_ADVANCE_REVISION)
    val allocationRevision = input.revision(LocalField.EXPECTED_ADVANCE_ALLOCATION_REVISION)
    val unapplied = input.money(LocalField.EXPECTED_UNAPPLIED_ADVANCE)
    val invoiceId = input.id(LocalField.INVOICE_ID)
    access.requireAction(intent.action, invoiceId)
    val observed = supplierSettlementSource(access, LocalSupplierSettlementSourceQueryV1(supplierId, invoiceId, advanceId = advanceId))
    val invoice = observed.source as? LocalSupplierCashSource.Invoice ?: invalid(LocalField.INVOICE_ID)
    if (observed.advanceRevision != advanceRevision) invalid(LocalField.EXPECTED_ADVANCE_REVISION)
    if (observed.advanceAllocationRevision != allocationRevision) invalid(LocalField.EXPECTED_ADVANCE_ALLOCATION_REVISION)
    if (observed.unappliedAdvance != unapplied) invalid(LocalField.EXPECTED_UNAPPLIED_ADVANCE)
    val amount = input.money(LocalField.AMOUNT)
    if (amount.currency != Currency.EGP || amount.minor <= 0L || amount.minor > unapplied.minor ||
        amount.minor > (observed.availableToAllocate ?: unavailable()).minor) invalid(LocalField.AMOUNT)
    return LocalSupplierSettlementCodec.allocate(intent.operationId, LocalAllocateSupplierAdvanceV1(allocationId, advanceId,
        advanceRevision, allocationRevision, unapplied, invoice, amount, input.text(LocalField.REASON)))
}

internal fun supplierAdvanceAllocationSummary(value: LocalAllocateSupplierAdvanceV1): List<LocalRecordValue> = buildList {
    val invoice = value.invoice
    add(recordValue(text("Nonphysical allocation — NO payout", "تخصيص غير فعلي — دون صرف"), text(
        "Uses an earlier paid advance exactly once; does not add to today's cash outflow or alter gross PAYABLE recognition.",
        "يستخدم دفعة مقدمة سبق دفعها مرة واحدة؛ دون زيادة صرف اليوم أو تغيير الاعتراف الإجمالي بالمستحق.")))
    add(recordValue(fieldLabel(LocalField.ALLOCATION_ID), literal(value.allocationId.value)))
    add(recordValue(fieldLabel(LocalField.ADVANCE_ID), literal(value.advanceId.value)))
    add(recordValue(fieldLabel(LocalField.SUPPLIER_ID), literal(invoice.supplierId.value)))
    add(recordValue(fieldLabel(LocalField.INVOICE_ID), literal(invoice.invoiceId.value)))
    add(recordValue(fieldLabel(LocalField.AMOUNT), moneyText(value.amount)))
    add(recordValue(fieldLabel(LocalField.EXPECTED_ADVANCE_REVISION), literal(value.expectedAdvanceRevision.value.toString())))
    add(recordValue(fieldLabel(LocalField.EXPECTED_ADVANCE_ALLOCATION_REVISION), literal(value.expectedAdvanceAllocationRevision.value.toString())))
    add(recordValue(fieldLabel(LocalField.EXPECTED_UNAPPLIED_ADVANCE), moneyText(value.expectedUnappliedAdvance)))
    add(recordValue(fieldLabel(LocalField.EXPECTED_SUPPLIER_STORAGE_REVISION), literal(invoice.expectedSupplierRevision.value.toString())))
    add(recordValue(fieldLabel(LocalField.EXPECTED_STORAGE_REVISION), literal(invoice.expectedInvoiceRevision.value.toString())))
    add(recordValue(fieldLabel(LocalField.EXPECTED_PROGRESS_REVISION), literal(invoice.expectedProgressRevision.value.toString())))
    add(recordValue(fieldLabel(LocalField.EXPECTED_PAYABLE_REVISION), literal(invoice.expectedPayableRevision.value.toString())))
    add(recordValue(fieldLabel(LocalField.EXPECTED_CREDIT_REVISION), literal(invoice.expectedCreditRevision?.value?.toString() ?: "ABSENT")))
    add(recordValue(fieldLabel(LocalField.EXPECTED_SETTLEMENT_REVISION), literal(invoice.expectedSettlementRevision?.value?.toString() ?: "ABSENT")))
    add(recordValue(fieldLabel(LocalField.EXPECTED_REMAINING_DUE), moneyText(invoice.expectedRemainingDue)))
    add(recordValue(fieldLabel(LocalField.EXPECTED_RESERVED_CASH), moneyText(invoice.expectedReservedCash)))
    add(recordValue(fieldLabel(LocalField.EXPECTED_AVAILABLE_TO_ALLOCATE), moneyText(invoice.expectedRemainingDue - invoice.expectedReservedCash)))
    add(recordValue(fieldLabel(LocalField.REASON), literal(value.reasonCode)))
}

internal fun supplierAdvanceRecorded(entry: StoredClientIntentV1, reply: MutationReply.Recorded): Pair<LocalText, LocalRecord?> {
    val request = LocalSupplierSettlementCodec.allocation(entry.request().payload)
    if (reply.operationId != entry.operationId || reply.originalActorId != entry.owner.actorId) unavailable()
    if (!reply.committed) {
        val rejection = LocalPurchasingCodec.result(reply.result)
        if (rejection.id != request.invoice.invoiceId || rejection.code != reply.code || rejection.revision != null ||
            rejection.progressRevision != null || rejection.creditRevision != null) unavailable()
        return text("Advance allocation durably refused: ${rejection.code}. No cash movement or allocation was posted.",
            "رُفض تخصيص الدفعة المقدمة بشكل دائم: ${rejection.code}. لم تُرحل حركة نقدية أو تخصيص.") to null
    }
    val value = LocalSupplierSettlementCodec.allocationResult(reply.result)
    if (value.allocationId != request.allocationId || value.invoiceId != request.invoice.invoiceId || value.advanceId != request.advanceId ||
        value.amount != request.amount || value.remainingDue != request.invoice.expectedRemainingDue - request.amount ||
        value.unappliedAdvance != request.expectedUnappliedAdvance - request.amount ||
        value.invoiceSettlementRevision != (request.invoice.expectedSettlementRevision ?: Revision(0)).next() ||
        value.advanceAllocationRevision != request.expectedAdvanceAllocationRevision.next()) unavailable()
    return text("Earlier paid advance allocated once to this invoice. No new supplier payout or cash movement occurred.",
        "خُصصت الدفعة المقدمة المدفوعة سابقًا مرة واحدة لهذه الفاتورة. لم يحدث صرف جديد للمورد أو حركة نقدية.") to LocalRecord(
        LocalRecordReference(LocalRecordKind.SUPPLIER_SETTLEMENT_ALLOCATION, value.allocationId), literal(value.allocationId.value),
        text("Nonphysical advance allocation posted", "رُحل تخصيص الدفعة المقدمة غير الفعلي"), listOf(
            recordValue(fieldLabel(LocalField.INVOICE_ID), literal(value.invoiceId.value)), recordValue(fieldLabel(LocalField.ADVANCE_ID), literal(value.advanceId.value)),
            recordValue(fieldLabel(LocalField.AMOUNT), moneyText(value.amount)), recordValue(text("Invoice due after allocation", "مستحق الفاتورة بعد التخصيص"), moneyText(value.remainingDue)),
            recordValue(text("Advance funds still unapplied", "أموال الدفعة التي لم تُخصص بعد"), moneyText(value.unappliedAdvance)),
            recordValue(fieldLabel(LocalField.EXPECTED_SETTLEMENT_REVISION), literal(value.invoiceSettlementRevision.value.toString())),
            recordValue(fieldLabel(LocalField.EXPECTED_ADVANCE_ALLOCATION_REVISION), literal(value.advanceAllocationRevision.value.toString()))), selectable = false)
}

internal fun supplierAllocationRecord(value: LocalSupplierAllocationViewV1): LocalRecord {
    if (listOf(value.amount, value.remainingBefore, value.remainingAfter).any { it.currency != Currency.EGP || it.minor < 0L } ||
        value.amount.minor <= 0L || value.amount.minor > value.remainingBefore.minor || value.remainingBefore - value.amount != value.remainingAfter) unavailable()
    return LocalRecord(LocalRecordReference(LocalRecordKind.SUPPLIER_SETTLEMENT_ALLOCATION, value.id), literal(value.id.value),
        if (value.advanceId == null) text("Original supplier payout allocated", "خُصص صرف المورد الأصلي") else text("Earlier advance explicitly allocated — no new payout", "خُصصت دفعة سابقة صراحةً — دون صرف جديد"),
        listOf(recordValue(text("Original payment source", "مصدر الدفع الأصلي"), literal(value.paymentId.value)),
            recordValue(fieldLabel(LocalField.ADVANCE_ID), literal(value.advanceId?.value ?: "ABSENT")), recordValue(fieldLabel(LocalField.AMOUNT), moneyText(value.amount)),
            recordValue(text("Recognized due before", "المستحق المعترف به قبل التخصيص"), moneyText(value.remainingBefore)),
            recordValue(text("Recognized due after", "المستحق المعترف به بعد التخصيص"), moneyText(value.remainingAfter)),
            recordValue(text("Allocation recorded at", "وقت تسجيل التخصيص"), literal(value.recordedAt.canonicalString()))), selectable = false)
}

/** All 64 allowed allocations fit alongside the existing 89-row invoice detail bound. */
internal fun supplierAllocationRecords(values: List<LocalSupplierAllocationViewV1>): List<LocalRecord> {
    if (values.size > LocalSupplierSettlementProfile.MAX_ENTRIES || values.map { it.id }.distinct().size != values.size) unavailable()
    return values.chunked(8).map { batch ->
        val records = batch.map(::supplierAllocationRecord)
        if (records.size == 1) records.single() else LocalRecord(records.first().reference,
            text("Supplier allocations — complete grouped read", "تخصيصات المورد — قراءة مجمعة كاملة"),
            text("Every original allocation/source is retained below; no payout is repeated", "كل تخصيص ومصدر أصلي محفوظ أدناه؛ دون تكرار صرف"),
            records.map { record -> recordValue(record.title, text(
                listOfNotNull(record.status?.english).plus(record.values.map { "${it.label.english}: ${it.value.english}" }).joinToString("\n"),
                listOfNotNull(record.status?.arabic).plus(record.values.map { "${it.label.arabic}: ${it.value.arabic}" }).joinToString("\n"))) }, selectable = false)
    }
}
