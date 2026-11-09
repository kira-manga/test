package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.types.*

/** A terminal response must describe this exact immutable request before retention or ACK. */
internal fun capturedInvoiceMatchRecorded(entry: StoredClientIntentV1, reply: MutationReply.Recorded): LocalCapturedSupplierInvoiceMatchResultV2 {
    val input = savedCapturedInvoiceMatchRequest(entry)
    if (reply.operationId != entry.operationId || reply.originalActorId != entry.owner.actorId ||
        reply.physicalStart != null || reply.sequence.value <= 0L) capturedMatchUncertain()
    return capturedInvoiceMatchOutcome(input, reply.committed, reply.code, reply.result)
}

internal fun savedCapturedInvoiceMatchRequest(entry: StoredClientIntentV1): LocalMatchCapturedSupplierInvoiceV2 {
    try {
        val request = entry.request()
        if (entry.localAction != LocalAction.SUPPLIER_INVOICE_MATCH_RECOGNIZE ||
            request.command != LocalPurchasingProfile.MATCH_CAPTURED_INVOICE || entry.formVersion != 1 ||
            entry.actionApproval != null || request.actionApprovalId != null) capturedMatchUncertain()
        entry.validate(entry.owner)
        val input = LocalPurchasingCodec.decodeCapturedMatch(request.payload)
        entry.recorded?.let { result ->
            if (result.sequence.value <= 0L) capturedMatchUncertain()
            val bytes = decodeHex(result.payloadHex, 65_536).useWiped { StoredBytes(it) }
            capturedInvoiceMatchOutcome(input, result.committed, result.code, bytes)
        }
        return input
    } catch (failure: GatewayFailure) { throw failure }
    catch (_: Exception) { capturedMatchUncertain() }
}

private fun capturedInvoiceMatchOutcome(input: LocalMatchCapturedSupplierInvoiceV2, committed: Boolean,
    code: String, bytes: StoredBytes): LocalCapturedSupplierInvoiceMatchResultV2 {
    try {
        val value = LocalPurchasingCodec.capturedMatchResult(bytes)
        if (LocalPurchasingCodec.capturedMatchResult(value) != bytes || value.id != input.matchId ||
            value.invoiceId != input.invoiceId || value.invoiceRevision.value < 1 ||
            value.originalInvoiceClaim.currency != Currency.EGP || value.originalInvoiceClaim.minor < 0) capturedMatchUncertain()
        if (committed) {
            if (code != "committed" || value.code != LocalCapturedSupplierInvoiceMatchCodesV2.SUCCESS ||
                value.invoiceRevision != input.expectedInvoiceRevision || value.revision != Revision(1) ||
                value.progressRevision != (input.expectedProgressRevision ?: Revision(0)).next() ||
                value.payableRevision != (input.expectedPayableRevision ?: Revision(0)).next() ||
                value.quantity != input.quantity) capturedMatchUncertain()
            val net = value.net ?: capturedMatchUncertain()
            val tax = value.tax ?: capturedMatchUncertain()
            if (net.currency != Currency.EGP || tax.currency != Currency.EGP || net.minor < 0 || tax.minor < 0 ||
                (net + tax).minor > value.originalInvoiceClaim.minor) capturedMatchUncertain()
        } else if (value.code != code || code !in LocalCapturedSupplierInvoiceMatchCodesV2.REFUSALS ||
            value.revision != null || value.progressRevision != null || value.payableRevision != null ||
            value.quantity != null || value.net != null || value.tax != null) capturedMatchUncertain()
        return value
    } catch (failure: GatewayFailure) { throw failure }
    catch (_: Exception) { capturedMatchUncertain() }
}

private fun capturedMatchUncertain(): Nothing = throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)

internal fun capturedInvoiceMatchRecordedUi(entry: StoredClientIntentV1, reply: MutationReply.Recorded): Pair<LocalText, LocalRecord?> {
    val value = capturedInvoiceMatchRecorded(entry, reply)
    if (!reply.committed) return text(
        "Match and recognition durably refused: ${value.code}. This operation allocated no goods or payable and made no payment.",
        "رُفضت المطابقة وإثبات المستحق بشكل دائم: ${value.code}. لم تخصص هذه العملية بضاعة أو مستحقًا ولم تسجل دفعًا.") to null
    return text(
        "The selected accepted goods were matched and recognized once. The original claim is unchanged; any remaining claim still needs resolution. No stock or money moved.",
        "طُوبقت البضاعة المقبولة المختارة وأُثبت مستحقها مرة واحدة. بقيت المطالبة الأصلية كما هي؛ وأي جزء متبقٍ يحتاج معالجة. لم يتحرك مخزون أو مال.") to LocalRecord(
        LocalRecordReference(LocalRecordKind.PURCHASE_INVOICE, value.invoiceId), entry.label.ui(), literal(value.code), listOf(
            recordValue(fieldLabel(LocalField.MATCH_ID), literal(value.id.value)),
            recordValue(fieldLabel(LocalField.QUANTITY), quantityText(value.quantity ?: capturedMatchUncertain())),
            recordValue(text("This operation's recognized amount — not paid", "المستحق المثبت بهذه العملية — غير مدفوع"),
                moneyText((value.net ?: capturedMatchUncertain()) + (value.tax ?: capturedMatchUncertain()))),
            recordValue(text("Original supplier claim", "مطالبة المورد الأصلية"), moneyText(value.originalInvoiceClaim)),
            recordValue(fieldLabel(LocalField.EXPECTED_PROGRESS_REVISION), literal(value.progressRevision?.value?.toString() ?: capturedMatchUncertain())),
            recordValue(fieldLabel(LocalField.EXPECTED_PAYABLE_REVISION), literal(value.payableRevision?.value?.toString() ?: capturedMatchUncertain())),
        ), invoiceEntryKind = LocalInvoiceEntryKind.CAPTURE_ONLY_V2)
}

/** Current financial facts are separate from the immutable original capture projection. */
internal fun validateCapturedInvoiceRecognition(capture: LocalPurchasingInvoiceCaptureViewV2,
    recognition: LocalCapturedSupplierInvoiceRecognitionV2, costs: Boolean, detailed: Boolean) {
    val payableRevision = recognition.payableRevision ?: unavailable()
    if (recognition.invoiceId != capture.id || recognition.invoiceRevision != capture.revision ||
        recognition.originalDocumentId != capture.sourceEvidenceId || recognition.originalRevision != Revision(1) ||
        recognition.orderRevision.value < 1 || recognition.progressRevision.value < 1 ||
        payableRevision.value < 1 ||
        recognition.lines.size > 16 || recognition.lines.map { it.lineId }.distinct().size != recognition.lines.size ||
        recognition.status !in setOf("PARTIALLY_MATCHED_RECOGNIZED", "MATCHED_RECOGNIZED")) unavailable()
    capture.internalOrderLink?.let { if (it.orderId != recognition.orderId || it.revision != recognition.orderRevision) unavailable() }
    if (detailed && recognition.lines.map { it.lineId }.toSet() != capture.lines.map { it.lineId }.toSet()) unavailable()
    if (!detailed && recognition.lines.isNotEmpty()) unavailable()
    if (recognition.settlementEligible && recognition.status != "MATCHED_RECOGNIZED") unavailable()
    if (detailed) {
        val allGoodsMatched = recognition.lines.all { line ->
            line.matchedQuantity == capture.lines.single { it.lineId == line.lineId }.baseQuantity
        }
        // Status describes goods quantities, independently of cost visibility or
        // unresolved charges. Neither zero money nor completed goods grant settlement.
        if ((recognition.status == "MATCHED_RECOGNIZED") != allGoodsMatched) unavailable()
        if (costs && recognition.settlementEligible && capture.charges.orEmpty().isNotEmpty()) unavailable()
    }
    var recognized = Money(Currency.EGP, 0)
    for (line in recognition.lines) {
        val original = capture.lines.singleOrNull { it.lineId == line.lineId } ?: unavailable()
        if (line.matchedQuantity.unitId != original.baseQuantity.unitId || line.matchedQuantity.scale != original.baseQuantity.scale ||
            line.matchedQuantity.scaled < 0 || line.matchedQuantity > original.baseQuantity ||
            (line.matchedQuantity.scaled == 0L) != (line.allocationId == null) ||
            line.allocationId != null && original.internalAllocationId != null && line.allocationId != original.internalAllocationId) unavailable()
        if (costs) {
            val net = line.matchedNet ?: unavailable()
            val tax = line.matchedTax ?: unavailable()
            if (net.currency != Currency.EGP || tax.currency != Currency.EGP || net.minor < 0 || tax.minor < 0 ||
                net.minor > (original.claimedNet ?: unavailable()).minor || tax.minor > (original.claimedTax ?: unavailable()).minor ||
                line.matchedQuantity.scaled == 0L && (net.minor != 0L || tax.minor != 0L)) unavailable()
            recognized += net + tax
        } else if (line.matchedNet != null || line.matchedTax != null) unavailable()
    }
    val money = listOf(recognition.recognizedPayable, recognition.unmatchedClaim, recognition.remainingDue,
        recognition.appliedCredit, recognition.allocatedPayment, recognition.reservedSupplierCash, recognition.availableToAllocate)
    if (!costs) {
        if (money.any { it != null }) unavailable()
        return
    }
    if (money.any { it == null || it.currency != Currency.EGP || it.minor < 0 }) unavailable()
    val payable = recognition.recognizedPayable ?: unavailable()
    val unmatched = recognition.unmatchedClaim ?: unavailable()
    val due = recognition.remainingDue ?: unavailable()
    val credit = recognition.appliedCredit ?: unavailable()
    val paid = recognition.allocatedPayment ?: unavailable()
    val reserved = recognition.reservedSupplierCash ?: unavailable()
    val available = recognition.availableToAllocate ?: unavailable()
    if (detailed && recognized != payable || payable + unmatched != capture.supplierClaim ||
        due + credit + paid != payable || reserved + available != due ||
        recognition.settlementEligible && unmatched.minor != 0L) unavailable()
}

internal fun capturedInvoiceRecognitionRecord(value: LocalCapturedSupplierInvoiceRecognitionV2): LocalRecord = LocalRecord(
    LocalRecordReference(LocalRecordKind.PURCHASE_MATCH, value.invoiceId),
    text("Current goods matching and recognition", "مطابقة البضاعة وإثبات مستحقها الحاليان"), literal(value.status), buildList {
        add(recordValue(fieldLabel(LocalField.PURCHASE_ID), literal(value.orderId.value)))
        add(recordValue(fieldLabel(LocalField.EXPECTED_PROGRESS_REVISION), literal(value.progressRevision.value.toString())))
        value.recognizedPayable?.let { add(recordValue(text("Recognized payable", "المستحق المثبت"), moneyText(it))) }
        value.unmatchedClaim?.let { add(recordValue(text("Claim still unmatched / unrecognized", "مطالبة غير مطابقة / غير مثبتة بعد"), moneyText(it))) }
        value.remainingDue?.let { add(recordValue(text("Remaining unpaid obligation", "الالتزام المتبقي غير المدفوع"), moneyText(it))) }
        add(recordValue(text("Settlement eligibility — separate permission still required", "أهلية التسوية — تلزم صلاحية منفصلة"),
            literal(value.settlementEligible.toString())))
    }, selectable = false,
)
