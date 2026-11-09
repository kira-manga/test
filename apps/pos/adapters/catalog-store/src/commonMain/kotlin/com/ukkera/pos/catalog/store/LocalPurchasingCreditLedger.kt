@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault

/** Financial allocation only. Original invoice/match/PAYABLE and inventory values stay intact. */
@Serializable
internal data class PurchasingCreditAllocation(
    val invoiceSource: PurchasingSource, val progressSource: PurchasingSource, val payableSource: PurchasingSource,
    val previousCreditSource: PurchasingSource?,
    val recognizedBefore: Money, val appliedBefore: Money, val remainingBefore: Money, val remainingAfter: Money,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val settlementSource: PurchasingSettlementIndexSource? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val allocatedBefore: Money? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val reservedBefore: Money? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val availableBefore: Money? = null,
    /** Actual source format, not a permissive choice of progress counter rules. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val capturedInvoice: Boolean = false,
)

internal data class PurchasingCreditLedger(
    val index: PurchasingRow?, val entries: List<PurchasingRow>, val appliedTotal: Money,
)

internal data class PurchasingDue(val recognized: Money, val appliedCredit: Money, val allocatedPayment: Money, val remainingDue: Money)

/** One remaining-due calculation for the command, history, invoice read and source read. */
internal fun purchasingRemainingDue(recognized: Money, appliedCredit: Money, allocatedPayment: Money = purchaseZero()): PurchasingDue {
    check(recognized.currency == Currency.EGP && appliedCredit.currency == recognized.currency &&
        recognized.minor >= 0 && appliedCredit.minor in 0L..recognized.minor && allocatedPayment.currency == recognized.currency &&
        allocatedPayment.minor in 0L..(recognized - appliedCredit).minor)
    return PurchasingDue(recognized, appliedCredit, allocatedPayment, recognized - appliedCredit - allocatedPayment)
}

internal fun purchasingDue(cut: PurchasingInvoiceCut): PurchasingDue? =
    (cut.payable?.value?.body as? PurchasingBody.Payable)?.let { purchasingRemainingDue(it.net + it.tax, cut.credits.appliedTotal, cut.settlements.allocatedTotal) }

internal fun LocalRecordSupplierCreditV1.hasSettlementPins(): Boolean = expectedAllocatedPayment != null &&
    expectedReservedCash != null && expectedAvailableToAllocate != null

internal fun LocalRecordSupplierCreditV1.hasLegacySettlementAbsence(): Boolean = expectedSettlementRevision == null &&
    expectedAllocatedPayment == null && expectedReservedCash == null && expectedAvailableToAllocate == null

/** Omission is never upgraded to today's cut. Only actual index absence and zero funds fit. */
internal fun purchasingCreditSettlementCutMatches(request: LocalRecordSupplierCreditV1, cut: PurchasingInvoiceCut): Boolean =
    if (request.hasSettlementPins()) cut.settlements.index?.row?.revision == request.expectedSettlementRevision &&
        cut.settlements.allocatedTotal == request.expectedAllocatedPayment && cut.settlements.reservedTotal == request.expectedReservedCash &&
        purchasingAvailableToAllocate(cut) == request.expectedAvailableToAllocate
    else request.hasLegacySettlementAbsence() && cut.settlements.index == null &&
        cut.settlements.allocatedTotal == purchaseZero() && cut.settlements.reservedTotal == purchaseZero()

internal fun purchasingCreditSourceClaim(sourceEvidenceId: DomainId): ClaimKey =
    ClaimKey(LocalPurchasingProfile.CREDIT_SOURCE_CLAIM, "evidence/${sourceEvidenceId.value}")

/**
 * Reconstruct each immutable allocation at its original recognition cut. Later matches
 * increase gross recognition only: already posted credits are summed once by this ledger.
 * Earlier progress/payable/index digests come from the actual retained producer writes,
 * never a current row substituted for an older snapshot.
 */
internal suspend fun PurchasingReader.invoiceCredits(
    invoice: PurchasingRow, payable: PurchasingRow?, matches: List<PurchasingMatchFact>, settlements: PurchasingSettlementLedger,
): PurchasingCreditLedger {
    val value = purchasingInvoiceClaim(invoice)
    val index = row(LocalPurchasingProfile.INVOICE_CREDITS, value.id)
        ?: return PurchasingCreditLedger(null, emptyList(), purchaseZero())
    check(payable != null)
    val state = index.value.body as PurchasingBody.InvoiceCredits
    check(state.invoiceId == value.id && state.creditIds.size in 1..LocalPurchasingProfile.MAX_CREDITS &&
        state.creditIds.distinct().size == state.creditIds.size && index.row.revision.value == state.creditIds.size.toLong())
    val entries = mutableListOf<PurchasingRow>()
    var applied = purchaseZero()
    var lastRecognitionCount = 0L
    for ((position, creditId) in state.creditIds.withIndex()) {
        val entry = row(LocalPurchasingProfile.CREDIT, creditId) ?: error("Purchasing credit unavailable")
        val body = entry.value.body as PurchasingBody.Credit
        val request = body.request
        val allocation = body.allocation
        val recognitionCount = requireNotNull(request.expectedPayableRevision).value
        check(entry.row.revision == Revision(1) && request.creditId == creditId && request.invoiceId == value.id &&
            body.supplierId == value.supplierId && request.supplierId == value.supplierId &&
            body.normalizedReference == normalizedPurchaseReference(request.reference) &&
            request.agreed && request.kind == LocalSupplierAdjustmentKindV1.CREDIT && request.amount.minor > 0 &&
            request.expectedInvoiceRevision == invoice.row.revision && request.expectedProgressRevision.value == recognitionCount + purchasingProgressOffset(invoice) &&
            allocation.capturedInvoice == value.captured &&
            request.expectedCreditRevision == if (position == 0) null else Revision(position.toLong()))
        check(recognitionCount in 1..matches.size.toLong() && recognitionCount >= lastRecognitionCount)
        check(originalCreditIndex(entry, value.id) == PurchasingBody.InvoiceCredits(value.id, state.creditIds.take(position + 1)))
        val recognized = matches.take(recognitionCount.toInt()).fold(purchaseZero()) { total, event -> total + event.net + event.tax }
        val legacy = request.hasLegacySettlementAbsence()
        if (legacy) {
            check(allocation.settlementSource == null && allocation.allocatedBefore == null && allocation.reservedBefore == null && allocation.availableBefore == null)
            // A later first settlement carries the exact earlier credit-index prefix. Its
            // genuine retained basis proves that this legacy credit preceded ANY index.
            settlements.indexHistory.firstOrNull()?.let { first ->
                val original = originalCommand(first.record.producer.operationId)
                val event = (decodePurchasingRecord(original.aggregateWrites.single {
                    it.key == purchasingKey(LocalSupplierSettlementProfile.EVENT, first.body.lastEventId)
                }.payload).body as PurchasingBody.SettlementEvent).value
                val prefix = checkNotNull(event.basisBefore.creditSource)
                check(first.source.revision == Revision(1) && event.basisBefore.settlementSource == null &&
                    prefix.kind == LocalPurchasingProfile.INVOICE_CREDITS && prefix.id == value.id &&
                    prefix.revision.value >= position.toLong() + 1L)
                creditPrefix(invoice, prefix)
            }
            check(settlements.index == null || settlements.indexHistory.isNotEmpty())
        } else check(request.hasSettlementPins())
        val (allocated, reserved) = if (legacy) purchaseZero() to purchaseZero() else settlements.at(allocation.settlementSource)
        if (!legacy) check(allocation.settlementSource?.source?.revision == request.expectedSettlementRevision &&
            allocation.allocatedBefore == allocated && allocation.reservedBefore == reserved &&
            request.expectedAllocatedPayment == allocated && request.expectedReservedCash == reserved)
        val due = purchasingRemainingDue(recognized, applied, allocated)
        val available = due.remainingDue - reserved
        check(reserved.minor in 0L..due.remainingDue.minor && (legacy || allocation.availableBefore == available &&
            request.expectedAvailableToAllocate == available))
        check(request.expectedRemainingDue == due.remainingDue && request.amount.currency == due.remainingDue.currency &&
            request.amount.minor <= (due.remainingDue - reserved).minor)
        val matching = row(LocalPurchasingProfile.MATCH, matches[recognitionCount.toInt() - 1].request.matchId)
            ?: error("Purchasing recognition source unavailable")
        val previous = entries.lastOrNull()?.let { originalSource(it, LocalPurchasingProfile.INVOICE_CREDITS, value.id) }
        check(allocation == PurchasingCreditAllocation(PurchasingSource.from(invoice.row),
            originalSource(matching, LocalPurchasingProfile.PROGRESS, value.id),
            originalSource(matching, LocalPurchasingProfile.PAYABLE, value.id), previous,
            due.recognized, due.appliedCredit, due.remainingDue, due.remainingDue - request.amount,
            allocation.settlementSource, if (legacy) null else allocated, if (legacy) null else reserved, if (legacy) null else available,
            capturedInvoice = value.captured))
        applied += request.amount
        entries += entry
        lastRecognitionCount = recognitionCount
    }
    val last = entries.last()
    check(originalSource(last, LocalPurchasingProfile.INVOICE_CREDITS, value.id) == PurchasingSource.from(index.row))
    val gross = payable.value.body as PurchasingBody.Payable
    purchasingRemainingDue(gross.net + gross.tax, applied, settlements.allocatedTotal)
    return PurchasingCreditLedger(index, entries, applied)
}

internal fun purchasingCreditBody(request: LocalRecordSupplierCreditV1, cut: PurchasingInvoiceCut): PurchasingBody.Credit {
    val invoice = purchasingInvoiceClaim(cut.invoice)
    val due = purchasingDue(cut) ?: error("Purchasing recognized payable unavailable")
    check(purchasingCreditSettlementCutMatches(request, cut))
    val pinned = request.hasSettlementPins()
    return PurchasingBody.Credit(request, invoice.supplierId, normalizedPurchaseReference(request.reference),
        PurchasingCreditAllocation(PurchasingSource.from(cut.invoice.row), PurchasingSource.from(cut.progress.row),
            PurchasingSource.from(requireNotNull(cut.payable).row), cut.credits.index?.let { PurchasingSource.from(it.row) },
            due.recognized, due.appliedCredit, due.remainingDue, due.remainingDue - request.amount,
            if (pinned) purchasingSettlementIndexSource(cut.settlements.index) else null,
            if (pinned) cut.settlements.allocatedTotal else null, if (pinned) cut.settlements.reservedTotal else null,
            if (pinned) checkNotNull(purchasingAvailableToAllocate(cut)) else null, capturedInvoice = invoice.captured))
}

internal fun purchasingNextCredits(request: LocalRecordSupplierCreditV1, cut: PurchasingInvoiceCut): PurchasingBody.InvoiceCredits =
    PurchasingBody.InvoiceCredits(request.invoiceId,
        (cut.credits.index?.value?.body as? PurchasingBody.InvoiceCredits)?.creditIds.orEmpty() + request.creditId)

internal fun purchasingCreditClaims(scope: BranchScope, supplierId: DomainId, request: LocalRecordSupplierCreditV1,
    firstCredit: Boolean): List<ClaimChange> = listOf(
    purchasingAcquisition(LocalPurchasingProfile.CREDIT, request.creditId),
    ClaimChange.Acquire(purchasingReferenceClaim(scope, "credit", supplierId, request.reference), request.creditId),
    ClaimChange.Acquire(purchasingCreditSourceClaim(request.sourceEvidenceId), request.creditId),
) + if (firstCredit) listOf(purchasingAcquisition(LocalPurchasingProfile.INVOICE_CREDITS, request.invoiceId)) else emptyList()

internal fun purchasingCreditResult(id: DomainId, creditRevision: Revision): StoredBytes = purchasingEncode(
    LocalPurchasingResultV1("purchasing.credit_applied", id, Revision(1), creditRevision = creditRevision), LocalPurchasingResultV1.serializer())

internal fun PurchasingRow.creditView(): LocalPurchasingCreditViewV1 {
    val body = value.body as PurchasingBody.Credit
    val request = body.request
    val allocation = body.allocation
    return LocalPurchasingCreditViewV1(request.creditId, request.invoiceId, row.revision, request.reference, request.creditDate,
        request.sourceEvidenceId, request.amount, allocation.recognizedBefore, allocation.appliedBefore,
        allocation.remainingBefore, allocation.remainingAfter, requireNotNull(request.expectedPayableRevision),
        (request.expectedCreditRevision ?: Revision(0)).next())
}

/** Closed credit producer shape; full BEFORE-source semantics are joined separately by native history. */
internal fun requirePurchasingCreditProducer(
    evidence: RetainedCommandEvidence, record: (String, DomainId, Revision) -> PurchasingRecord,
    acquire: (String, DomainId) -> Unit, expectedClaims: MutableMap<ClaimKey, DomainId>,
): StoredBytes {
    val request = LocalPurchasingCodec.decodeCredit(evidence.normalized)
    val body = record(LocalPurchasingProfile.CREDIT, request.creditId, Revision(1)).body as PurchasingBody.Credit
    val allocation = body.allocation
    check(body.request == request && body.supplierId == request.supplierId &&
        body.normalizedReference == normalizedPurchaseReference(request.reference) &&
        request.agreed && request.kind == LocalSupplierAdjustmentKindV1.CREDIT && request.amount.minor > 0 &&
        request.expectedPayableRevision != null && request.expectedRemainingDue != null)
    val sources = listOf(allocation.invoiceSource, allocation.progressSource, allocation.payableSource)
    check(sources.map { it.kind to it.id } == listOf(LocalPurchasingProfile.INVOICE to request.invoiceId,
        LocalPurchasingProfile.PROGRESS to request.invoiceId, LocalPurchasingProfile.PAYABLE to request.invoiceId) &&
        allocation.invoiceSource.revision == request.expectedInvoiceRevision &&
        allocation.progressSource.revision == request.expectedProgressRevision &&
        allocation.payableSource.revision == request.expectedPayableRevision &&
        request.expectedProgressRevision.value == requireNotNull(request.expectedPayableRevision).value +
            if (allocation.capturedInvoice) 0L else 1L)
    sources.forEach { check(it.revision.value >= 1); PayloadDigest(it.digest) }
    val previous = allocation.previousCreditSource
    check((previous == null) == (request.expectedCreditRevision == null))
    if (previous != null) {
        check(previous.kind == LocalPurchasingProfile.INVOICE_CREDITS && previous.id == request.invoiceId &&
            previous.revision == request.expectedCreditRevision && previous.revision.value >= 1)
        PayloadDigest(previous.digest)
    }
    val settlement = allocation.settlementSource
    val legacy = request.hasLegacySettlementAbsence()
    check(if (legacy) settlement == null && allocation.allocatedBefore == null && allocation.reservedBefore == null && allocation.availableBefore == null
        else request.hasSettlementPins() && settlement?.source?.revision == request.expectedSettlementRevision &&
            allocation.allocatedBefore == request.expectedAllocatedPayment && allocation.reservedBefore == request.expectedReservedCash)
    settlement?.let { check(it.source.kind == LocalSupplierSettlementProfile.INVOICE_SETTLEMENTS && it.source.id == request.invoiceId &&
        it.source.revision.value >= 1L); PayloadDigest(it.source.digest) }
    val allocated = if (legacy) purchaseZero() else checkNotNull(allocation.allocatedBefore)
    val reserved = if (legacy) purchaseZero() else checkNotNull(allocation.reservedBefore)
    val due = purchasingRemainingDue(allocation.recognizedBefore, allocation.appliedBefore, allocated)
    val available = due.remainingDue - reserved
    check(reserved.minor in 0L..due.remainingDue.minor && (legacy || allocation.availableBefore == available && request.expectedAvailableToAllocate == available))
    check(request.expectedRemainingDue == due.remainingDue && allocation.remainingBefore == due.remainingDue &&
        request.amount.currency == due.remainingDue.currency && request.amount.minor <= available.minor &&
        allocation.remainingAfter == due.remainingDue - request.amount)
    val revision = (request.expectedCreditRevision ?: Revision(0)).next()
    val index = record(LocalPurchasingProfile.INVOICE_CREDITS, request.invoiceId, revision).body as PurchasingBody.InvoiceCredits
    check(index.invoiceId == request.invoiceId && index.creditIds.size in 1..LocalPurchasingProfile.MAX_CREDITS &&
        index.creditIds.size.toLong() == revision.value && index.creditIds.distinct().size == index.creditIds.size &&
        index.creditIds.last() == request.creditId)
    acquire(LocalPurchasingProfile.CREDIT, request.creditId)
    if (request.expectedCreditRevision == null) acquire(LocalPurchasingProfile.INVOICE_CREDITS, request.invoiceId)
    expectedClaims[purchasingReferenceClaim(evidence.scope, "credit", body.supplierId, request.reference)] = request.creditId
    expectedClaims[purchasingCreditSourceClaim(request.sourceEvidenceId)] = request.creditId
    return purchasingCreditResult(request.creditId, revision)
}
