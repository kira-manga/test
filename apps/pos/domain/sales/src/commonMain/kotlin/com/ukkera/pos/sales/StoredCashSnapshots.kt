package com.ukkera.pos.sales

import com.ukkera.pos.types.BusinessDate
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.Serializable

@Serializable
internal data class StoredCashierEvidence(val evidenceId: DomainId, val actorId: DomainId, val occurredAt: UtcInstant) {
    fun restore(): CashierEvidence = CashierEvidence(evidenceId, actorId, occurredAt.value)

    companion object {
        fun from(value: CashierEvidence): StoredCashierEvidence = StoredCashierEvidence(value.evidenceId, value.actorId, UtcInstant(value.occurredAt))
    }
}

@Serializable
internal data class StoredCountedCash(val amount: Money, val evidence: StoredCashierEvidence) {
    fun restore(): CountedCash = CountedCash(amount, evidence.restore())

    companion object {
        fun from(value: CountedCash): StoredCountedCash = StoredCountedCash(value.amount, StoredCashierEvidence.from(value.evidence))
    }
}

@Serializable
internal data class StoredCashTotals(
    val received: Money, val change: Money, val refunds: Money, val payIns: Money, val payOuts: Money,
    val safeDrops: Money, val externalCollections: Money, val externalRefunds: Money,
) {
    fun restore(): CashTotals = CashTotals(received, change, refunds, payIns, payOuts, safeDrops, externalCollections, externalRefunds)

    companion object {
        fun from(value: CashTotals): StoredCashTotals = StoredCashTotals(value.received, value.change, value.refunds,
            value.payIns, value.payOuts, value.safeDrops, value.externalCollections, value.externalRefunds)
    }
}

@Serializable
internal data class StoredCashCut(
    val cashLedgerRevision: Revision, val expected: Money, val externalCollections: Money, val externalRefunds: Money,
) {
    fun restore(): CashClosingCut = CashClosingCut(cashLedgerRevision, expected, externalCollections, externalRefunds)

    companion object {
        fun from(value: CashClosingCut): StoredCashCut = StoredCashCut(value.cashLedgerRevision, value.expected,
            value.externalCollections, value.externalRefunds)
    }
}

@Serializable
internal data class StoredCashClose(
    val sessionId: DomainId, val cut: StoredCashCut, val count: StoredCountedCash, val variance: Money,
    val retainedFloat: Money, val actualSafeDelivery: Money, val safeTransferId: DomainId?,
    val varianceReason: String?, val submittedBy: DomainId, val operationId: OperationId,
) {
    fun restore(): CashCloseReport = CashCloseReport(sessionId, cut.restore(), count.restore(), variance,
        retainedFloat, actualSafeDelivery, safeTransferId, varianceReason, submittedBy, operationId)

    companion object {
        fun from(value: CashCloseReport): StoredCashClose = StoredCashClose(value.sessionId, StoredCashCut.from(value.cut),
            StoredCountedCash.from(value.count), value.variance, value.retainedFloat, value.actualSafeDelivery,
            value.safeTransferId, value.varianceReason, value.submittedBy, value.operationId)
    }
}

@Serializable
internal data class StoredCashReview(val reviewerId: DomainId, val evidence: StoredCashierEvidence, val reason: String) {
    // The original report has one stored representation, never a second mutable shadow.
    fun restore(report: CashCloseReport): CashReview = CashReview(reviewerId, evidence.restore(), reason, report)

    companion object {
        fun from(value: CashReview): StoredCashReview = StoredCashReview(value.reviewerId, StoredCashierEvidence.from(value.evidence), value.reason)
    }
}

@Serializable
internal data class StoredCashSession(
    val drawerId: DomainId,
    val custodianId: DomainId,
    val businessDate: BusinessDate,
    val opening: StoredCountedCash,
    val openingSourceId: DomainId,
    val totals: StoredCashTotals,
    val expectedCash: Money,
    val reservedCashOut: Money,
    val cashLedgerRevision: Revision,
    val status: String,
    val pendingAttemptIds: List<DomainId>,
    val pendingTransferIds: List<DomainId>,
    val closingCut: StoredCashCut?,
    val closeReport: StoredCashClose?,
    val review: StoredCashReview?,
) {
    fun restore(binding: SalesSnapshotBinding): CashSession {
        val report = closeReport?.restore()
        snapshotCheck(review == null || report != null)
        return CashSession(binding.id, binding.scope, drawerId, custodianId, businessDate.value, binding.revision,
            opening.restore(), openingSourceId, totals.restore(), expectedCash, reservedCashOut, cashLedgerRevision,
            enumValueOf<CashSessionStatus>(status), pendingAttemptIds, pendingTransferIds, closingCut?.restore(), report,
            review?.restore(checkNotNull(report)))
    }

    companion object {
        fun from(value: CashSession): StoredCashSession = StoredCashSession(value.drawerId, value.custodianId,
            BusinessDate(value.businessDate), StoredCountedCash.from(value.opening), value.openingSourceId,
            StoredCashTotals.from(value.totals), value.expectedCash, value.reservedCashOut, value.cashLedgerRevision,
            value.status.name, value.pendingAttemptIds, value.pendingTransferIds, value.closingCut?.let(StoredCashCut::from),
            value.closeReport?.let(StoredCashClose::from), value.review?.let(StoredCashReview::from))
    }
}

@Serializable
internal data class StoredSafeTransfer(
    val senderSessionId: DomainId, val senderDrawerId: DomainId, val senderCustodianId: DomainId,
    val receivingCustodyId: DomainId, val amount: Money, val sentEvidence: StoredCashierEvidence,
    val closingDisposition: Boolean, val status: String, val receipt: StoredCashierEvidence?,
) {
    fun restore(binding: SalesSnapshotBinding): SafeTransfer = SafeTransfer(binding.id, binding.scope, binding.revision,
        senderSessionId, senderDrawerId, senderCustodianId, receivingCustodyId, amount, sentEvidence.restore(),
        closingDisposition, enumValueOf<SafeTransferStatus>(status), receipt?.restore())

    companion object {
        fun from(value: SafeTransfer): StoredSafeTransfer = StoredSafeTransfer(value.senderSessionId, value.senderDrawerId,
            value.senderCustodianId, value.receivingCustodyId, value.amount, StoredCashierEvidence.from(value.sentEvidence),
            value.closingDisposition, value.status.name, value.receipt?.let(StoredCashierEvidence::from))
    }
}

internal fun validateStoredCash(session: CashSession) {
    snapshotCheck(session.cashLedgerRevision <= session.revision)
    snapshotCheck(session.totals.change.minor <= session.totals.received.minor)
    snapshotCheck(session.pendingAttemptIds.isNotEmpty() || session.reservedCashOut.minor == 0L)
    session.closingCut?.let { cut ->
        snapshotCheck(cut == CashClosingCut(session.cashLedgerRevision, session.expectedCash,
            session.totals.externalCollections, session.totals.externalRefunds))
    }
    session.closeReport?.let { report ->
        snapshotCheck(report.sessionId == session.id && report.cut == session.closingCut &&
            report.submittedBy == session.custodianId && report.count.evidence.actorId == session.custodianId)
        sameCurrency(session.expectedCash, report.count.amount, report.variance, report.retainedFloat, report.actualSafeDelivery)
        report.retainedFloat.requireNonnegative()
        report.actualSafeDelivery.requireNonnegative()
        snapshotCheck(report.variance == report.count.amount - report.cut.expected &&
            report.count.amount == report.retainedFloat + report.actualSafeDelivery)
        snapshotCheck((report.actualSafeDelivery.minor > 0L) == (report.safeTransferId != null))
        snapshotCheck(report.variance.minor == 0L || report.varianceReason != null)
        report.varianceReason?.let(::requireReason)
    }
    session.review?.let { review ->
        snapshotCheck(review.originalReport == session.closeReport && review.reviewerId != session.custodianId &&
            review.evidence.actorId == review.reviewerId)
        requireReason(review.reason)
    }
}

internal fun validateStoredTransfer(transfer: SafeTransfer) {
    transfer.amount.requirePositive()
    snapshotCheck(transfer.sentEvidence.actorId == transfer.senderCustodianId)
    snapshotCheck(transfer.receivingCustodyId != transfer.senderSessionId && transfer.receivingCustodyId != transfer.senderDrawerId)
    when (transfer.status) {
        SafeTransferStatus.AWAITING_RECEIPT -> snapshotCheck(transfer.revision == Revision(0) && transfer.receipt == null)
        SafeTransferStatus.RECEIVED -> snapshotCheck(transfer.revision == Revision(1) && transfer.receipt != null &&
            transfer.receipt.actorId != transfer.senderCustodianId)
    }
}
