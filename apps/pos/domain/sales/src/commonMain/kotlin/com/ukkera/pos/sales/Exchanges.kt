package com.ukkera.pos.sales

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision

/** One slice of the offset, drawn from one original tender obligation of the accepted return. */
public data class ExchangeOffsetSlice(
    public val attemptId: DomainId,
    public val originalPaymentId: DomainId,
    public val amount: Money,
)

/** Immutable link between an accepted return and its same-branch replacement collection. */
public data class ExchangeOffsetRecorded(
    public val exchangeId: DomainId,
    public val returnId: DomainId,
    public val saleId: DomainId,
    public val collectionId: DomainId,
    public val actorId: DomainId,
    public val amount: Money,
    public val slices: List<ExchangeOffsetSlice>,
    public val evidence: CashierEvidence,
) : SalesEffect

public data class ExchangeOffsetApplied(
    public val acceptedReturn: AcceptedReturn,
    public val collection: CollectionLock,
    public val record: ExchangeOffsetRecorded,
)

/**
 * Exchanges (RET-05/06/07): the accepted return keeps its original quantity, tax allocation and
 * tender obligations; part of the refund obligation is applied as a restricted offset to the
 * linked replacement collection, and only the residual is actually collected or refunded.
 *
 * The offset must COMPLETE the replacement's coverage, so it is applied only after every real
 * residual collection succeeded (RET-05). An unavailable replacement or a failed residual
 * collection therefore leaves the accepted return and its full refund obligation untouched
 * (RET-07). There is no cash movement, wallet, reusable store credit or cross-branch use.
 */
public object Exchanges {
    public fun applyOffset(
        command: SalesCommand,
        acceptedReturn: AcceptedReturn,
        expectedReturnRevision: Revision,
        collection: CollectionLock,
        expectedCollectionRevision: Revision,
        exchangeId: DomainId,
        slices: List<ExchangeOffsetSlice>,
        evidence: CashierEvidence,
    ): SalesTransition<ExchangeOffsetApplied> {
        requireRevision(acceptedReturn.revision, expectedReturnRevision)
        requireRevision(collection.revision, expectedCollectionRevision)
        requireSales(slices.isNotEmpty() && slices.size <= 16, SalesRule.INVALID_AMOUNT)
        val amount = slices.fold(collection.quote.payable.zero()) { sum, slice ->
            sameCurrency(collection.quote.payable, slice.amount); sum + slice.amount.requirePositive()
        }
        // Current refund-recording authority over the obligation being settled.
        val actor = authorize(command, "refund.record", acceptedReturn.scope, acceptedReturn.id, expectedReturnRevision, amount)
        evidence.requireActor(actor)
        requireSales(collection.scope == acceptedReturn.scope, SalesRule.WRONG_BRANCH)
        requireSales(collection.collectorId == actor, SalesRule.OWNER_REQUIRED)
        requireSales(collection.saleId == null, SalesRule.IMMUTABLE_RECORD)
        requireSales(!collection.unresolved && !acceptedReturn.unresolved, SalesRule.PAYMENT_UNRESOLVED)
        sameCurrency(acceptedReturn.originalPayable, amount)
        requireSales(amount == collection.remainingPayable, SalesRule.INSUFFICIENT_COVERAGE)
        requireSales(amount.minor <= acceptedReturn.due.minor, SalesRule.RETURN_ENTITLEMENT_EXHAUSTED)
        val ids = slices.map { it.attemptId }
        requireSales(ids.distinct().size == ids.size && exchangeId !in ids &&
            ids.none { id -> collection.attempts.any { it.id == id } || acceptedReturn.attempts.any { it.id == id } } &&
            slices.map { it.originalPaymentId }.distinct().size == slices.size, SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        val refundAttempts = mutableListOf<RefundAttempt>()
        val paymentAttempts = mutableListOf<PaymentAttempt>()
        val records = mutableListOf<SalesEffect>()
        for (slice in slices.sortedBy { it.attemptId.value }) {
            val obligation = acceptedReturn.tenderObligations.singleOrNull { it.originalPaymentId == slice.originalPaymentId }
                ?: throw SalesRuleViolation(SalesRule.TENDER_MISMATCH)
            val settled = acceptedReturn.attempts.filter { it.originalPaymentId == slice.originalPaymentId }
                .fold(slice.amount.zero()) { total, item -> total + (item.refund?.amount ?: slice.amount.zero()) }
            requireSales(slice.amount.minor <= (obligation.amount - settled).minor, SalesRule.RETURN_ENTITLEMENT_EXHAUSTED)
            val tender = Tender.ExchangeOffset(exchangeId, acceptedReturn.id, obligation.tender.refundTender())
            val refund = RefundRecord(slice.attemptId, acceptedReturn.id, slice.originalPaymentId, command.operationId,
                command.operationId, actor, collection.cashSessionId, tender, slice.amount, evidence, null)
            refundAttempts += RefundAttempt(slice.attemptId, command.operationId, actor, collection.cashSessionId,
                slice.originalPaymentId, tender, slice.amount, AttemptStatus.ATTESTED_PAID, null, refund)
            val payment = PaymentRecord(slice.attemptId, command.operationId, command.operationId, collection.id, actor,
                collection.cashSessionId, null, tender, slice.amount, null, null, evidence, null)
            paymentAttempts += PaymentAttempt(slice.attemptId, command.operationId, actor, collection.cashSessionId, null,
                tender, slice.amount, AttemptStatus.ATTESTED_PAID, null, payment)
            records += refund; records += payment
        }
        val afterReturn = acceptedReturn.update(acceptedReturn.attempts + refundAttempts)
        val afterCollection = collection.update(attempts = collection.attempts + paymentAttempts)
        check(afterCollection.collected == afterCollection.quote.payable && !afterCollection.unresolved)
        val record = ExchangeOffsetRecorded(exchangeId, acceptedReturn.id, acceptedReturn.saleId, collection.id, actor,
            amount, slices.sortedBy { it.attemptId.value }, evidence)
        return SalesTransition(command, ExchangeOffsetApplied(afterReturn, afterCollection, record),
            listOf(SalesVersionCheck(SalesAggregate.ACCEPTED_RETURN, acceptedReturn.id, acceptedReturn.revision), collectionVersion(collection)),
            listOf(SalesUniqueCheck(SalesUniqueKind.EXCHANGE, exchangeId)) + ids.sortedBy { it.value }.flatMap {
                listOf(SalesUniqueCheck(SalesUniqueKind.REFUND_ATTEMPT, it), SalesUniqueCheck(SalesUniqueKind.REFUND_RECORD, it),
                    SalesUniqueCheck(SalesUniqueKind.PAYMENT_ATTEMPT, it), SalesUniqueCheck(SalesUniqueKind.PAYMENT_RECORD, it))
            },
            listOf(AcceptedReturnChanged(afterReturn), CollectionChanged(afterCollection)) + records + record)
    }
}
