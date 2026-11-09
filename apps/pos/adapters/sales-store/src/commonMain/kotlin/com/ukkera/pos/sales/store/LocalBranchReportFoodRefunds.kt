package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.sales.AttemptStatus
import com.ukkera.pos.sales.Tender
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money

/**
 * Original whole-unsent cancellation, consumed finalized rights when present, and genuine
 * retained CASH applications only. No current recipe/stock readiness, physical replay,
 * repricing, new sale, revenue netting or entitlement mutation is performed by this report.
 */
internal suspend fun foodRefundReportRow(view: CommandReadView, id: DomainId): LocalBranchReportRowV1 {
    val cut = foodRefundCut(view, id)
    val value = cut.value
    // The shared obligation reader requires permanent positive claims. Reports additionally
    // refuse orphan record/cash claims instead of showing their missing document as zero.
    for (attempt in value.originalCollection.attempts) {
        requireLocalSalesSource(view.claim(LocalSalesStoreProfile.unique("payment_record", attempt.id)) ==
            if (attempt.payment == null) null else value.originalCollection.id)
    }
    val paidBySession = mutableMapOf<DomainId, Money>()
    for (attempt in value.attempts) {
        requireLocalSalesSource(attempt.tender == Tender.Cash)
        val record = attempt.refund
        requireLocalSalesSource(view.claim(LocalSalesStoreProfile.unique("refund_record", attempt.id)) ==
            if (record == null) null else value.id)
        requireLocalSalesSource(view.claim(CashNonSaleStoreProfile.cashSourceClaim(attempt.id)) ==
            if (record == null) null else attempt.originalCashSessionId)
        val movementKey = LocalSalesStoreProfile.key(LocalSalesStoreProfile.MOVEMENT_KIND, attempt.id)
        val movement = view.aggregate(movementKey)
        if (record == null) {
            requireLocalSalesSource(movement == null)
            continue // Start/raw/UNKNOWN/NotPaid are never an applied monetary refund.
        }
        requireLocalSalesSource(record.tender == Tender.Cash && record.externalEvidence == null &&
            record.amount == attempt.amount && record.payingCashSessionId == attempt.originalCashSessionId &&
            record.payingActorId == attempt.originalActorId)
        val fact = view.retainedCommand(record.recordingOperationId)
            ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
        requireLocalSalesSource(fact.command == LocalFoodRefundProfile.COMMAND)
        val application = when (LocalFoodRefundCodec.decode(fact.normalized)) {
            is LocalFoodRefundRequest.RecordOriginal ->
                readFoodRefundRaw(view, record.recordingOperationId, commerceRepository(view)).application.value
            is LocalFoodRefundRequest.RecordResolution ->
                readFoodRefundResolution(view, record.recordingOperationId, commerceRepository(view)).application.value
            else -> throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.INVALID)
        }
        val target = application.target
        requireLocalSalesSource(application.state == LocalFoodRefundApplicationState.APPLIED &&
            application.action == LocalFoodRefundAction.CASH_PAID && application.originalActorId == attempt.originalActorId &&
            target.factOperation(application.phase) == record.recordingOperationId && target.refundId == value.id &&
            target.collectionId == value.originalCollection.id && target.orderId == value.orderId && target.saleId == value.saleId &&
            target.attemptId == attempt.id && target.sessionId == record.payingCashSessionId &&
            target.intentOperationId == attempt.originalOperationId && target.originalPaymentId == attempt.originalPaymentId)
        val applied = view.retainedCommand(checkNotNull(application.lastApplicationOperationId))
            ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
        // The application reader already proved its complete original/UNKNOWN/resolution
        // producer. Match the actual current immutable movement to that same atomic write.
        requireFoodRefundRetainedRow(applied, movement ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING))
        val cash = LocalSalesStorage.session(view, record.payingCashSessionId)
        val posted = applied.aggregateWrites.single { it.key == CashNonSaleStoreProfile.sessionKey(cash.value.id) }
        requireLocalSalesSource(cash.stored.revision >= posted.storageGeneration)
        paidBySession[cash.value.id] = (paidBySession[cash.value.id] ?: Money(Currency.EGP, 0)) + record.amount
    }
    for ((sessionId, actual) in paidBySession) {
        // Never add these refunds to a session's totals again. This is only a lower-bound
        // consistency check of already posted movements; other documents remain separate.
        requireLocalSalesSource(LocalSalesStorage.session(view, sessionId).value.totals.refunds.minor >= actual.minor)
    }
    val cashRefunds = value.attempts.mapNotNull { it.refund }.fold(Money(Currency.EGP, 0)) { sum, record -> sum + record.amount }
    fun amount(metric: LocalBranchReportMetricV1, money: Money): LocalBranchReportAmountV1 = LocalBranchReportAmountV1(metric, money)
    return LocalBranchReportRowV1(id, value.revision, relatedDocumentId = value.originalCollection.id,
        status = value.status.name,
        pendingCount = value.attempts.count { it.status == AttemptStatus.AWAITING_ATTESTATION || it.status == AttemptStatus.OUTCOME_UNKNOWN },
        amounts = listOf(
            amount(LocalBranchReportMetricV1.LOCKED_PAYABLE, value.originalQuotedTotal),
            amount(LocalBranchReportMetricV1.CANCELLED_COLLECTION_REFUND_LIABILITY, value.amount),
            amount(LocalBranchReportMetricV1.ACTUAL_REFUNDS, value.refunded),
            amount(LocalBranchReportMetricV1.REFUND_DUE, value.due),
            amount(LocalBranchReportMetricV1.CASH_REFUNDS, cashRefunds),
            amount(LocalBranchReportMetricV1.EXTERNAL_REFUNDS, Money(Currency.EGP, 0))),
        storageRevision = cut.refund.row.revision, originalPaidDocumentId = value.saleId)
}
