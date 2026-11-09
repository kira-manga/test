package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.types.*

/** The optional editor and fresh staging use the same actual cost-authorized source read. */
private suspend fun creditSource(access: GatewayAccess, query: LocalPurchasingCreditSourceQueryV1): LocalPurchasingCreditSourceV1 {
    val reply = access.read(ReadRequest(LocalPurchasingProfile.READ_CREDIT_SOURCE, LocalPurchasingCodec.creditSource(query)))
    val source = LocalPurchasingCodec.creditSourceResult(reply.payload)
    if (source.query != query) unavailable()
    requirePurchasingAvailable(source.availability)
    if (source.sourcePresent == true) {
        val invoiceRevision = source.invoiceRevision ?: unavailable()
        val progressRevision = source.progressRevision ?: unavailable()
        val claim = source.supplierClaim ?: unavailable()
        if (invoiceRevision.value <= 0L || progressRevision.value <= 0L || claim.currency != Currency.EGP || claim.minor < 0L ||
            listOf(source.payableRevision, source.creditRevision, source.settlementRevision).any { it != null && it.value <= 0L }) unavailable()
    }
    if (source.sourcePresent == true && source.payableRevision != null) {
        val recognized = source.recognizedPayable ?: unavailable()
        val credit = source.appliedCredit ?: unavailable()
        val due = source.remainingDue ?: unavailable()
        val allocated = source.allocatedPayment ?: unavailable()
        val reserved = source.reservedCash ?: unavailable()
        val available = source.availableToAllocate ?: unavailable()
        if (listOf(recognized, credit, due, allocated, reserved, available).any { it.currency != Currency.EGP || it.minor < 0L } ||
            credit.minor > recognized.minor || allocated.minor > (recognized - credit).minor ||
            recognized - credit - allocated != due || reserved.minor > due.minor || due - reserved != available ||
            source.creditRevision == null && credit.minor != 0L ||
            source.settlementRevision == null && (allocated.minor != 0L || reserved.minor != 0L)) unavailable()
    }
    return source
}

internal suspend fun supplierCreditForm(access: GatewayAccess, invoice: LocalPurchasingDocumentViewV1,
    section: LocalPurchasingSectionV1 = LocalPurchasingSectionV1.INVOICE): LocalActionForm? {
    val invoicePayable = invoice.payableRevision ?: return null
    val invoiceRemaining = invoice.remainingDue ?: return null
    if (invoiceRemaining.minor <= 0) return null
    val source = creditSource(access, LocalPurchasingCreditSourceQueryV1(invoice.id))
    if (source.sourcePresent != true) return null
    val invoiceRevision = source.invoiceRevision ?: return null
    val progressRevision = source.progressRevision ?: return null
    val payableRevision = source.payableRevision ?: return null
    val remainingDue = source.remainingDue ?: return null
    val allocated = source.allocatedPayment ?: unavailable()
    val reserved = source.reservedCash ?: unavailable()
    val available = source.availableToAllocate ?: unavailable()
    if (available.minor <= 0) return null
    // Old zero/absent document fields remain absent. Zero is admitted only by this actual
    // source read proving the relevant index absent, never by a display default or legacy edit.
    val creditMatches = if (source.creditRevision == null) invoice.appliedCredit == null && source.appliedCredit?.minor == 0L
        else source.appliedCredit == invoice.appliedCredit
    val settlementMatches = if (source.settlementRevision == null) invoice.settlementRevision == null &&
        invoice.allocatedPayment == null && invoice.reservedSupplierCash == null && invoice.availableToAllocate == null &&
        allocated.minor == 0L && reserved.minor == 0L && available == remainingDue
        else source.settlementRevision == invoice.settlementRevision && allocated == invoice.allocatedPayment &&
            reserved == invoice.reservedSupplierCash && available == invoice.availableToAllocate
    val displayedRevision = if (section == LocalPurchasingSectionV1.PAYABLE) payableRevision else invoiceRevision
    if (source.supplierId != invoice.supplierId || displayedRevision != invoice.revision || source.supplierClaim != invoice.supplierClaim ||
        source.progressRevision != invoice.progressRevision || source.payableRevision != invoicePayable ||
        source.creditRevision != invoice.creditRevision || source.recognizedPayable != invoice.matchedPayable ||
        !creditMatches || source.remainingDue != invoice.remainingDue || !settlementMatches)
        throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
    return form(LocalAction.SUPPLIER_CREDIT_RECORD, listOf(
        newIdField(LocalField.CREDIT_ID), idField(LocalField.INVOICE_ID, invoice.id), idField(LocalField.SUPPLIER_ID, invoice.supplierId),
        revisionField(LocalField.EXPECTED_STORAGE_REVISION, invoiceRevision),
        revisionField(LocalField.EXPECTED_PROGRESS_REVISION, progressRevision),
        revisionField(LocalField.EXPECTED_PAYABLE_REVISION, payableRevision),
        scalar(LocalField.EXPECTED_CREDIT_REVISION, LocalInputKind.INTEGER,
            source.creditRevision?.let { LocalScalarValue.Integer(it.value) }, required = false, readOnly = true, maximum = 19),
        moneyField(LocalField.EXPECTED_REMAINING_DUE, remainingDue).copy(readOnly = true),
        scalar(LocalField.EXPECTED_SETTLEMENT_REVISION, LocalInputKind.INTEGER,
            source.settlementRevision?.let { LocalScalarValue.Integer(it.value) }, required = false, readOnly = true, maximum = 19),
        moneyField(LocalField.EXPECTED_ALLOCATED_PAYMENT, allocated).copy(readOnly = true),
        moneyField(LocalField.EXPECTED_RESERVED_CASH, reserved).copy(readOnly = true),
        moneyField(LocalField.EXPECTED_AVAILABLE_TO_ALLOCATE, available).copy(readOnly = true),
        choiceField(LocalField.MODE, listOf(LocalChoice("CREDIT", text("Agreed invoice credit", "إشعار خصم فاتورة متفق عليه"))),
            initial = "CREDIT").copy(readOnly = true),
        scalar(LocalField.EXTERNAL_REFERENCE, LocalInputKind.TEXT, maximum = 96),
        scalar(LocalField.BUSINESS_DATE, LocalInputKind.DATE).copy(label = text("Supplier credit date", "تاريخ إشعار خصم المورد")),
        idField(LocalField.EVIDENCE_ID),
        moneyField(LocalField.AMOUNT).copy(allowNegative = true, help = text(
            "Positive agreed EGP credit, no greater than available allocation after existing payments and reserved supplier cash. The whole amount must fit.",
            "خصم موجب متفق عليه بالجنيه، لا يزيد عن المتاح للتخصيص بعد المدفوعات ونقدية المورد المحجوزة. يجب أن يتسع المتاح للمبلغ كاملًا.")),
        scalar(LocalField.CREDIT_AGREED, LocalInputKind.CHECKBOX, LocalScalarValue.Flag(false)),
        scalar(LocalField.REASON, LocalInputKind.TEXT, maximum = 128),
    ), text(
        "Allocate this agreed credit completely to the selected invoice. Its original reference and evidence can be used only once. " +
            "Remaining recognized payable is ${moneyText(remainingDue).english}; the full original invoice claim is ${moneyText(checkNotNull(source.supplierClaim)).english} and requires purchasing approval. " +
            "Existing allocated payments are ${moneyText(allocated).english}, reserved supplier cash ${moneyText(reserved).english}, available allocation ${moneyText(available).english}. A changed payable, credit, payment or reservation requires a fresh review. Debit, negative, zero and excess credits are unsupported; an excess must not be shortened to fit. " +
            "This records no cash refund, supplier payment, stock movement or historical cost change.",
        "خصّص هذا الخصم المتفق عليه بالكامل للفاتورة المختارة. لا يمكن استخدام مرجعه وإثباته الأصليين أكثر من مرة. " +
            "المستحق المعترف به المتبقي ${moneyText(remainingDue).arabic}؛ ومطالبة الفاتورة الأصلية الكاملة ${moneyText(checkNotNull(source.supplierClaim)).arabic} وتتطلب صلاحية اعتماد الشراء. " +
            "المدفوعات المخصصة ${moneyText(allocated).arabic}، والنقدية المحجوزة للمورد ${moneyText(reserved).arabic}، والمتاح للتخصيص ${moneyText(available).arabic}. يتطلب تغير المستحق أو الخصم أو الدفع أو الحجز مراجعة جديدة. لا تدعم هذه العملية الزيادة المدينة أو المبالغ السالبة أو الصفرية أو الخصم الزائد؛ ولا يُختصر المبلغ ليتناسب مع المستحق. " +
            "لا يُسجل رد نقدي أو دفع للمورد أو حركة مخزون أو تغيير تكلفة تاريخية."), version = 2)
}

/** Only fresh staging re-reads the source. Recovery/submission reuses this exact saved request. */
internal suspend fun prepareSupplierCredit(access: GatewayAccess, intent: LocalCommandIntent, input: InputValues): MutationRequest {
    if (intent.formVersion !in 1..2 || intent.lineField != null || intent.lines.isNotEmpty()) invalid(LocalField.LINES)
    val creditId = input.id(LocalField.CREDIT_ID)
    val invoiceId = input.id(LocalField.INVOICE_ID)
    val selected = intent.reference
    if (selected == null || selected.kind !in setOf(LocalRecordKind.PURCHASE_INVOICE, LocalRecordKind.SUPPLIER_PAYABLE) ||
        selected.id != invoiceId || selected.revision != null || selected.parentId != null) invalid(LocalField.INVOICE_ID)
    access.requireAction(intent.action, invoiceId)
    val supplierId = input.id(LocalField.SUPPLIER_ID)
    val expectedInvoice = input.revision(LocalField.EXPECTED_STORAGE_REVISION)
    val expectedProgress = input.revision(LocalField.EXPECTED_PROGRESS_REVISION)
    val expectedPayable = input.optionalRevision(LocalField.EXPECTED_PAYABLE_REVISION)
    val expectedCredit = input.optionalRevision(LocalField.EXPECTED_CREDIT_REVISION)
    val expectedDue = input.optionalMoney(LocalField.EXPECTED_REMAINING_DUE)
    val expectedSettlement = if (intent.formVersion == 2) input.optionalRevision(LocalField.EXPECTED_SETTLEMENT_REVISION) else null
    val expectedAllocated = if (intent.formVersion == 2) input.money(LocalField.EXPECTED_ALLOCATED_PAYMENT) else null
    val expectedReserved = if (intent.formVersion == 2) input.money(LocalField.EXPECTED_RESERVED_CASH) else null
    val expectedAvailable = if (intent.formVersion == 2) input.money(LocalField.EXPECTED_AVAILABLE_TO_ALLOCATE) else null
    val source = creditSource(access, LocalPurchasingCreditSourceQueryV1(invoiceId))
    if (source.sourcePresent != true) invalid(LocalField.INVOICE_ID)
    if (source.supplierId != supplierId) invalid(LocalField.SUPPLIER_ID)
    if (source.invoiceRevision != expectedInvoice) invalid(LocalField.EXPECTED_STORAGE_REVISION)
    if (source.progressRevision != expectedProgress) invalid(LocalField.EXPECTED_PROGRESS_REVISION)
    if (source.payableRevision != expectedPayable) invalid(LocalField.EXPECTED_PAYABLE_REVISION)
    if (source.creditRevision != expectedCredit) invalid(LocalField.EXPECTED_CREDIT_REVISION)
    if (source.remainingDue != expectedDue) invalid(LocalField.EXPECTED_REMAINING_DUE)
    if (intent.formVersion == 2) {
        if (source.settlementRevision != expectedSettlement) invalid(LocalField.EXPECTED_SETTLEMENT_REVISION)
        if (source.allocatedPayment != expectedAllocated) invalid(LocalField.EXPECTED_ALLOCATED_PAYMENT)
        if (source.reservedCash != expectedReserved) invalid(LocalField.EXPECTED_RESERVED_CASH)
        if (source.availableToAllocate != expectedAvailable) invalid(LocalField.EXPECTED_AVAILABLE_TO_ALLOCATE)
    } else if (source.settlementRevision != null || source.allocatedPayment?.minor != 0L || source.reservedCash?.minor != 0L)
        invalid(LocalField.EXPECTED_SETTLEMENT_REVISION) // Genuine legacy absence only; never fill omitted pins from today.
    val request = LocalRecordSupplierCreditV1(creditId, invoiceId, supplierId, expectedInvoice, expectedProgress, expectedPayable,
        expectedCredit, expectedDue, input.text(LocalField.EXTERNAL_REFERENCE), input.date(LocalField.BUSINESS_DATE),
        input.id(LocalField.EVIDENCE_ID), enumChoice<LocalSupplierAdjustmentKindV1>(input.choice(LocalField.MODE), LocalField.MODE),
        input.money(LocalField.AMOUNT), input.flag(LocalField.CREDIT_AGREED), input.text(LocalField.REASON),
        expectedSettlementRevision = expectedSettlement, expectedAllocatedPayment = expectedAllocated,
        expectedReservedCash = expectedReserved, expectedAvailableToAllocate = expectedAvailable)
    return LocalPurchasingCommands.credit(intent.operationId, request)
}

internal fun purchasingCreditSummary(input: LocalRecordSupplierCreditV1): List<LocalRecordValue> = listOf(
    creditGroup(text("Original agreed supplier correction", "تعديل المورد الأصلي المتفق عليه"), listOf(
        fieldLabel(LocalField.CREDIT_ID) to literal(input.creditId.value), fieldLabel(LocalField.INVOICE_ID) to literal(input.invoiceId.value),
        fieldLabel(LocalField.SUPPLIER_ID) to literal(input.supplierId.value), fieldLabel(LocalField.EXTERNAL_REFERENCE) to literal(input.reference),
        text("Supplier credit date", "تاريخ إشعار خصم المورد") to literal(input.creditDate.canonicalString()),
        fieldLabel(LocalField.EVIDENCE_ID) to literal(input.sourceEvidenceId.value), fieldLabel(LocalField.MODE) to literal(input.kind.name),
        fieldLabel(LocalField.AMOUNT) to moneyText(input.amount), fieldLabel(LocalField.CREDIT_AGREED) to literal(input.agreed.toString()),
        fieldLabel(LocalField.REASON) to literal(input.reasonCode))),
    creditGroup(text("Exact observed invoice allocation", "تخصيص الفاتورة المرصود الدقيق"), listOf(
        fieldLabel(LocalField.EXPECTED_STORAGE_REVISION) to literal(input.expectedInvoiceRevision.value.toString()),
        fieldLabel(LocalField.EXPECTED_PROGRESS_REVISION) to literal(input.expectedProgressRevision.value.toString()),
        fieldLabel(LocalField.EXPECTED_PAYABLE_REVISION) to creditOptional(input.expectedPayableRevision?.value?.toString()),
        fieldLabel(LocalField.EXPECTED_CREDIT_REVISION) to creditOptional(input.expectedCreditRevision?.value?.toString()),
        fieldLabel(LocalField.EXPECTED_REMAINING_DUE) to (input.expectedRemainingDue?.let(::moneyText) ?: creditOptional(null)),
        fieldLabel(LocalField.EXPECTED_SETTLEMENT_REVISION) to creditOptional(input.expectedSettlementRevision?.value?.toString()),
        fieldLabel(LocalField.EXPECTED_ALLOCATED_PAYMENT) to (input.expectedAllocatedPayment?.let(::moneyText) ?: creditOptional(null)),
        fieldLabel(LocalField.EXPECTED_RESERVED_CASH) to (input.expectedReservedCash?.let(::moneyText) ?: creditOptional(null)),
        fieldLabel(LocalField.EXPECTED_AVAILABLE_TO_ALLOCATE) to (input.expectedAvailableToAllocate?.let(::moneyText) ?: creditOptional(null)))),
)

internal fun purchasingCredit(value: LocalPurchasingCreditViewV1): LocalRecord = LocalRecord(
    LocalRecordReference(LocalRecordKind.SUPPLIER_CREDIT, value.id), literal(value.reference),
    text("Agreed credit allocated", "خُصص إشعار الخصم المتفق عليه"), buildList {
        add(recordValue(fieldLabel(LocalField.INVOICE_ID), literal(value.invoiceId.value)))
        add(recordValue(fieldLabel(LocalField.EVIDENCE_ID), literal(value.sourceEvidenceId.value)))
        add(recordValue(text("Supplier credit date", "تاريخ إشعار خصم المورد"), literal(value.creditDate.canonicalString())))
        add(recordValue(text("Credit allocated to invoice", "الخصم المخصص للفاتورة"), moneyText(value.amount)))
        add(recordValue(text("Gross recognition at posting", "الاعتراف الإجمالي عند الترحيل"), moneyText(value.recognizedBefore)))
        add(recordValue(text("Earlier allocated credits", "الخصومات المخصصة السابقة"), moneyText(value.appliedBefore)))
        add(recordValue(text("Remaining payable before credit", "المستحق المتبقي قبل الخصم"), moneyText(value.remainingBefore)))
        add(recordValue(text("Remaining payable after credit", "المستحق المتبقي بعد الخصم"), moneyText(value.remainingAfter)))
        add(recordValue(fieldLabel(LocalField.EXPECTED_PAYABLE_REVISION), literal(value.payableRevision.value.toString())))
        add(recordValue(fieldLabel(LocalField.EXPECTED_CREDIT_REVISION), literal(value.creditRevision.value.toString())))
    }, selectable = false)

private fun creditOptional(value: String?): LocalText = value?.let(::literal) ?: text("ABSENT", "غير موجود")
private fun creditGroup(label: LocalText, values: List<Pair<LocalText, LocalText>>): LocalRecordValue = recordValue(label,
    text(values.joinToString("\n") { "${it.first.english}: ${it.second.english}" },
        values.joinToString("\n") { "${it.first.arabic}: ${it.second.arabic}" }))
