package com.ukkera.pos.sales

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlin.time.Instant

/** Separate historical explanation. It is not a PaymentRecord, refund or CashMovement. */
public data class PaymentClassificationCorrection internal constructor(
    public val id: DomainId,
    public val scope: BranchScope,
    public val saleId: DomainId,
    public val paymentAttemptId: DomainId,
    public val sessionId: DomainId,
    public val collectorId: DomainId,
    public val originalIntentOperationId: OperationId,
    public val originalRecordingOperationId: OperationId,
    public val originalCloseOperationId: OperationId,
    public val amount: Money,
    public val originalTender: Tender,
    public val previousClassification: Tender,
    public val correctedTender: Tender,
    public val position: Revision,
    public val previousCorrectionId: DomainId?,
    public val evidence: CashierEvidence,
    public val reason: String,
    public val terminal: String?,
    public val reference: String?,
    public val operationId: OperationId,
    public val recordedAt: Instant,
) {
    public val previousClassificationDelta: Money get() = Money(amount.currency, -amount.minor)
    public val correctedClassificationDelta: Money get() = amount
    public val physicalCashDelta: Money get() = Money(amount.currency, 0)
}

/** The adapter joins actual immutable sources and atomically appends a record/CAS head.
 * This reducer never mutates the sale, attestation, close, stock, cash or entitlement. */
public object PaymentClassificationCorrections {
    public fun sameClassification(first: Tender, second: Tender): Boolean = when {
        first == Tender.Cash -> second == Tender.Cash
        first is Tender.External && second is Tender.External -> first.tenderId == second.tenderId && first.kind == second.kind
        else -> false
    }

    public fun record(
        command: SalesCommand,
        sale: PaidSale,
        paymentAttemptId: DomainId,
        session: CashSession,
        expectedSessionRevision: Revision,
        expectedAmount: Money,
        correctedTender: Tender,
        position: Revision,
        previousCorrectionId: DomainId?,
        previousClassification: Tender,
        evidence: CashierEvidence,
        reason: String,
        terminal: String? = null,
        reference: String? = null,
    ): PaymentClassificationCorrection {
        val payment = sale.payments.singleOrNull { it.attemptId == paymentAttemptId }
            ?: throw SalesRuleViolation(SalesRule.PAYMENT_UNRESOLVED)
        val actor = authorize(command, "payment.classification.correct", sale.scope, sale.id,
            expectedSessionRevision, payment.amount)
        requireRevision(session.revision, expectedSessionRevision)
        requireSales(sale.scope == session.scope && sale.collectingSessionId == session.id &&
            sale.collectorId == session.custodianId && payment.collectionId == sale.payments.first().collectionId &&
            payment.collectorId == sale.collectorId && payment.cashSessionId == session.id &&
            payment.foodResolutionObservation == null, SalesRule.WRONG_BRANCH)
        // A genuine supervised retail resolution retains its original collector and
        // separately records the supervisor attestor. Intrinsic linkage is not source
        // authority: the adapter must also qualify the whole original resolution.
        payment.resolutionObservation?.let {
            requireStoredResolutionLinkV1(it, payment.originalOperationId, payment.recordingOperationId,
                payment.attestation.actorId)
        } ?: requireSales(payment.attestation.actorId == sale.collectorId, SalesRule.INVALID_EVIDENCE)
        requireSales(session.status in setOf(CashSessionStatus.SUBMITTED_CLOSED, CashSessionStatus.REVIEWED), SalesRule.IMMUTABLE_RECORD)
        val close = session.closeReport ?: throw SalesRuleViolation(SalesRule.INVALID_STATE)
        requireSales(close.sessionId == session.id && close.submittedBy == session.custodianId &&
            close.cut == session.closingCut && close.count.evidence.actorId == session.custodianId &&
            close.variance == close.count.amount - close.cut.expected, SalesRule.INVALID_STATE)
        requireSales(payment.amount.minor > 0 && payment.amount == expectedAmount, SalesRule.INVALID_AMOUNT)
        requireSales(position.value < Long.MAX_VALUE && (position.value == 0L) == (previousCorrectionId == null) &&
            previousCorrectionId != evidence.evidenceId, SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        if (previousCorrectionId == null) requireSales(previousClassification == payment.tender, SalesRule.TENDER_MISMATCH)
        requireSales(!sameClassification(previousClassification, correctedTender), SalesRule.TENDER_MISMATCH)
        evidence.requireActor(actor)
        requireReason(reason)
        when (correctedTender) {
            Tender.Cash -> requireSales(terminal == null && reference == null, SalesRule.INVALID_EVIDENCE)
            is Tender.External -> {
                ExternalTenderEvidence(evidence, terminal, reference)
                requireSales(!correctedTender.requiresTerminal || terminal != null, SalesRule.REFERENCE_REQUIRED)
                requireSales(!correctedTender.requiresReference || reference != null, SalesRule.REFERENCE_REQUIRED)
            }
        }
        return PaymentClassificationCorrection(evidence.evidenceId, sale.scope, sale.id, payment.attemptId,
            session.id, payment.collectorId, payment.originalOperationId, payment.recordingOperationId, close.operationId,
            payment.amount, payment.tender, previousClassification, correctedTender, position, previousCorrectionId,
            evidence, reason, terminal, reference, command.operationId, command.recordedAt)
    }
}
