package com.ukkera.pos.sales

import com.ukkera.pos.pricing.ReturnedPriceComponent
import com.ukkera.pos.pricing.StoredReturnCursor
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.AllocationWeight
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.allocateByLargestRemainder
import kotlin.time.Instant

/** Recorded policy/evidence, NOT a hardcoded assertion of Egypt statutory eligibility.
 * Ordinary CASE claims use the immutable Master intake time as claimedAt; original
 * customer assertions remain separately unchanged in their intake record. */
public data class ReturnClaimEvidence(
    public val policyId: DomainId,
    public val policyRevision: Revision,
    public val customerReceivedAt: Instant,
    public val claimedAt: Instant,
    public val remedyDueAt: Instant?,
    public val reason: String,
    public val evidence: CashierEvidence,
) {
    init {
        requireSales(claimedAt >= customerReceivedAt && (remedyDueAt == null || remedyDueAt >= claimedAt), SalesRule.INVALID_EVIDENCE)
        requireReason(reason)
    }
}

public class ReturnClaim internal constructor(
    public val id: DomainId,
    public val scope: BranchScope,
    public val saleId: DomainId,
    public val portionId: DomainId,
    public val quantity: Quantity,
    public val claimantOperatorId: DomainId,
    public val evidence: ReturnClaimEvidence,
    public val revision: Revision,
    public val acceptedReturnId: DomainId? = null,
)

public class ReceiptEntitlement internal constructor(
    public val sale: PaidSale,
    public val revision: Revision,
    cursors: List<StoredReturnCursor>,
    remainingComponentTenders: List<OriginalComponentTenderAllocation>,
) {
    public val saleId: DomainId get() = sale.id
    public val scope: BranchScope get() = sale.scope
    public val cursors: List<StoredReturnCursor> = frozenList(cursors, 256)
    public val remainingComponentTenders: List<OriginalComponentTenderAllocation> = frozenList(remainingComponentTenders)
}

public data class ReturnReceiptLine(
    public val portionId: DomainId,
    public val originalQuantity: Quantity,
    public val remainingQuantity: Quantity,
    public val originalRefundable: Money,
)

/** No active order ownership, employee identity, inventory costs or permission limits leak. */
public class ReturnReceiptProjection internal constructor(
    public val saleId: DomainId,
    public val sellingScope: BranchScope,
    public val revision: Revision,
    lines: List<ReturnReceiptLine>,
) {
    public val lines: List<ReturnReceiptLine> = frozenList(lines, 256)
}

/** Requested post-inspection disposition, NEVER proof of a posted stock restoration. */
public enum class ReturnDisposition { INSPECTED_SELLABLE, QUARANTINE, WASTE, SUPPLIER_RETURN }

public data class ReturnTenderObligation(public val originalPaymentId: DomainId, public val tender: Tender, public val amount: Money)
public enum class RefundStatus { DUE, PART_REFUNDED, OUTCOME_UNKNOWN, SETTLED }

public sealed interface RefundOutcome {
    public data class Unknown(public val evidence: CashierEvidence) : RefundOutcome
    public data class NotPaid(public val evidence: CashierEvidence, public val reason: String) : RefundOutcome
    /** Actual payout of the locked attempt amount, not return approval or an inferred payment. */
    public data class CashPaid(public val evidence: CashierEvidence) : RefundOutcome
    public data class ExternalAttested(public val evidence: ExternalTenderEvidence) : RefundOutcome
}

public data class RefundRecord internal constructor(
    public val attemptId: DomainId,
    public val returnId: DomainId,
    public val originalPaymentId: DomainId,
    public val originalOperationId: OperationId,
    public val recordingOperationId: OperationId,
    public val payingActorId: DomainId,
    public val payingCashSessionId: DomainId,
    public val tender: Tender,
    public val amount: Money,
    public val attestation: CashierEvidence,
    public val externalEvidence: ExternalTenderEvidence?,
    public val resolutionObservation: RetailResolutionObservationLink? = null,
) : SalesEffect

public class RefundAttempt internal constructor(
    public val id: DomainId,
    public val originalOperationId: OperationId,
    public val originalActorId: DomainId,
    public val originalCashSessionId: DomainId,
    public val originalPaymentId: DomainId,
    public val tender: Tender,
    public val amount: Money,
    public val status: AttemptStatus,
    public val latestObservation: RefundOutcome? = null,
    public val refund: RefundRecord? = null,
    public val resolutionObservation: RetailResolutionObservationLink? = null,
)

public class AcceptedReturn internal constructor(
    public val id: DomainId,
    public val scope: BranchScope,
    public val saleId: DomainId,
    public val claimId: DomainId,
    public val portionId: DomainId,
    public val quantity: Quantity,
    /** Starting original cumulative cursor, retained to validate the exact stored return slice. */
    public val consumedQuantityBefore: Quantity,
    public val acceptingOperatorId: DomainId,
    public val evidence: ReturnClaimEvidence,
    public val requestedDisposition: ReturnDisposition,
    public val inspectionEvidence: CashierEvidence,
    public val originalPayable: Money,
    components: List<ReturnedPriceComponent>,
    tenderObligations: List<ReturnTenderObligation>,
    public val revision: Revision,
    attempts: List<RefundAttempt> = emptyList(),
) {
    public val components: List<ReturnedPriceComponent> = frozenList(components)
    public val tenderObligations: List<ReturnTenderObligation> = frozenList(tenderObligations, 64)
    public val attempts: List<RefundAttempt> = frozenList(attempts, 64)
    public val refunded: Money get() = attempts.fold(originalPayable.zero()) { total, item -> total + (item.refund?.amount ?: total.zero()) }
    public val due: Money get() = originalPayable - refunded
    public val unresolved: Boolean get() = attempts.any { it.status == AttemptStatus.AWAITING_ATTESTATION || it.status == AttemptStatus.OUTCOME_UNKNOWN }
    public val refundStatus: RefundStatus get() = when {
        unresolved -> RefundStatus.OUTCOME_UNKNOWN
        due.minor == 0L -> RefundStatus.SETTLED
        refunded.minor > 0L -> RefundStatus.PART_REFUNDED
        else -> RefundStatus.DUE
    }

    internal fun update(attempts: List<RefundAttempt>): AcceptedReturn = AcceptedReturn(id, scope, saleId, claimId, portionId,
        quantity, consumedQuantityBefore, acceptingOperatorId, evidence, requestedDisposition, inspectionEvidence, originalPayable, components,
        tenderObligations, revision.next(), attempts)
}

public data class ReceiptEntitlementChanged(public val entitlement: ReceiptEntitlement) : SalesEffect
public data class ReturnClaimChanged(public val claim: ReturnClaim) : SalesEffect
public data class AcceptedReturnChanged(public val acceptedReturn: AcceptedReturn) : SalesEffect
public data class RefundObservationRecorded(public val attemptId: DomainId, public val outcome: RefundOutcome) : SalesEffect
public data class ReturnAccepted(
    public val entitlement: ReceiptEntitlement,
    public val claim: ReturnClaim,
    public val acceptedReturn: AcceptedReturn,
)
public data class RefundWithCash(public val acceptedReturn: AcceptedReturn, public val session: CashSession)

public object Returns {
    public fun receiptProjection(command: SalesCommand, entitlement: ReceiptEntitlement, expectedRevision: Revision): ReturnReceiptProjection {
        requireRevision(entitlement.revision, expectedRevision)
        authorize(command, "return.receipt", entitlement.scope, entitlement.saleId, expectedRevision)
        return ReturnReceiptProjection(entitlement.saleId, entitlement.scope, entitlement.revision,
            entitlement.cursors.map { cursor -> ReturnReceiptLine(cursor.portionId, cursor.originalQuantity, cursor.remainingQuantity,
                cursor.originalComponents.fold(entitlement.sale.quote.payable.zero()) { sum, item -> sum + item.amount }) })
    }

    /** Claim recording neither consumes entitlement nor needs cash/refund approval. */
    public fun recordClaim(
        command: SalesCommand,
        entitlement: ReceiptEntitlement,
        expectedEntitlementRevision: Revision,
        claimId: DomainId,
        portionId: DomainId,
        quantity: Quantity,
        evidence: ReturnClaimEvidence,
    ): SalesTransition<ReturnClaim> {
        requireRevision(entitlement.revision, expectedEntitlementRevision)
        val actor = authorize(command, "return.claim", entitlement.scope, claimId, Revision(0))
        val claim = originalClaimValue(entitlement, expectedEntitlementRevision, claimId, portionId, quantity, evidence, actor)
        return SalesTransition(command, claim, listOf(entitlementVersion(entitlement)), listOf(SalesUniqueCheck(SalesUniqueKind.RETURN_CLAIM, claimId)), listOf(ReturnClaimChanged(claim)))
    }

    /** Pure immutable original value for live production and complete historical comparison.
     * This supplies no command, authorization, transition or permission to persist a claim. */
    public fun originalClaimValue(entitlement: ReceiptEntitlement, expectedEntitlementRevision: Revision,
        claimId: DomainId, portionId: DomainId, quantity: Quantity, evidence: ReturnClaimEvidence,
        actorId: DomainId): ReturnClaim {
        requireRevision(entitlement.revision, expectedEntitlementRevision)
        evidence.evidence.requireActor(actorId)
        quantity.requirePositive()
        val cursor = entitlement.cursors.singleOrNull { it.portionId == portionId } ?: throw SalesRuleViolation(SalesRule.INVALID_STATE)
        requireSales(quantity.unitId == cursor.originalQuantity.unitId && quantity.scale == cursor.originalQuantity.scale,
            SalesRule.RETURN_ENTITLEMENT_EXHAUSTED)
        // Even disputed/exhausted claims may be recorded; acceptance checks actual remaining rights.
        return ReturnClaim(claimId, entitlement.scope, entitlement.saleId, portionId, quantity, actorId, evidence, Revision(0))
    }

    /**
     * Consume original quantity AND original tender value now, even if no refund is paid.
     * No current price/tax evaluation, stock restoration or spendable customer credit occurs.
     * Explicit-only/fixed fee exceptions, receiptless claims and exchanges are not admitted.
     */
    public fun accept(
        command: SalesCommand,
        entitlement: ReceiptEntitlement,
        expectedEntitlementRevision: Revision,
        claim: ReturnClaim,
        expectedClaimRevision: Revision,
        returnId: DomainId,
        disposition: ReturnDisposition,
        inspectionEvidence: CashierEvidence,
    ): SalesTransition<ReturnAccepted> {
        requireRevision(entitlement.revision, expectedEntitlementRevision)
        requireRevision(claim.revision, expectedClaimRevision)
        requireSales(claim.scope == entitlement.scope && claim.saleId == entitlement.saleId, SalesRule.WRONG_BRANCH)
        requireSales(claim.acceptedReturnId == null, SalesRule.IMMUTABLE_RECORD)
        val cursor = entitlement.cursors.singleOrNull { it.portionId == claim.portionId } ?: throw SalesRuleViolation(SalesRule.INVALID_STATE)
        requireSales(claim.quantity.unitId == cursor.originalQuantity.unitId && claim.quantity.scale == cursor.originalQuantity.scale &&
            claim.quantity.scaled > 0 && claim.quantity <= cursor.remainingQuantity, SalesRule.RETURN_ENTITLEMENT_EXHAUSTED)
        val slice = cursor.consume(claim.quantity)
        val actor = authorize(command, "return.accept", entitlement.scope, entitlement.saleId, expectedEntitlementRevision, slice.payable)
        val accepted = originalAcceptanceValue(entitlement, expectedEntitlementRevision, claim, expectedClaimRevision,
            returnId, disposition, inspectionEvidence, actor)
        return SalesTransition(command, accepted,
            listOf(entitlementVersion(entitlement), SalesVersionCheck(SalesAggregate.RETURN_CLAIM, claim.id, expectedClaimRevision)),
            listOf(SalesUniqueCheck(SalesUniqueKind.ACCEPTED_RETURN, returnId), SalesUniqueCheck(SalesUniqueKind.CLAIM_ACCEPTANCE, claim.id)),
            listOf(ReceiptEntitlementChanged(accepted.entitlement), ReturnClaimChanged(accepted.claim),
                AcceptedReturnChanged(accepted.acceptedReturn)))
    }

    /** Pure original allocation value. Historical callers still prove the genuine BEFORE,
     * original authority, whole producers and exact effects; no payout or stock effect exists. */
    public fun originalAcceptanceValue(entitlement: ReceiptEntitlement, expectedEntitlementRevision: Revision,
        claim: ReturnClaim, expectedClaimRevision: Revision, returnId: DomainId, disposition: ReturnDisposition,
        inspectionEvidence: CashierEvidence, actorId: DomainId): ReturnAccepted {
        requireRevision(entitlement.revision, expectedEntitlementRevision)
        requireRevision(claim.revision, expectedClaimRevision)
        requireSales(claim.scope == entitlement.scope && claim.saleId == entitlement.saleId, SalesRule.WRONG_BRANCH)
        requireSales(claim.acceptedReturnId == null, SalesRule.IMMUTABLE_RECORD)
        val cursor = entitlement.cursors.singleOrNull { it.portionId == claim.portionId } ?: throw SalesRuleViolation(SalesRule.INVALID_STATE)
        requireSales(claim.quantity.unitId == cursor.originalQuantity.unitId && claim.quantity.scale == cursor.originalQuantity.scale &&
            claim.quantity.scaled > 0 && claim.quantity <= cursor.remainingQuantity, SalesRule.RETURN_ENTITLEMENT_EXHAUSTED)
        val slice = cursor.consume(claim.quantity)
        inspectionEvidence.requireActor(actorId)
        val balances = entitlement.remainingComponentTenders.associateBy { it.componentKey }.toMutableMap()
        val tenderDue = mutableMapOf<DomainId, Money>()
        for (component in slice.components) {
            val row = balances[component.key] ?: throw SalesRuleViolation(SalesRule.RETURN_ENTITLEMENT_EXHAUSTED)
            val allocation = allocateByLargestRemainder(component.amount, row.shares.map { AllocationWeight(it.paymentId.value, it.amount.minor) })
            val allocatedByPayment = allocation.associateBy { it.key }
            val after = row.shares.map { share ->
                val used = allocatedByPayment.getValue(share.paymentId.value).amount
                requireSales(used.minor <= share.amount.minor, SalesRule.RETURN_ENTITLEMENT_EXHAUSTED)
                tenderDue[share.paymentId] = (tenderDue[share.paymentId] ?: used.zero()) + used
                OriginalTenderShare(share.paymentId, share.amount - used)
            }
            balances[component.key] = OriginalComponentTenderAllocation(component.key, after)
        }
        val obligations = tenderDue.filterValues { it.minor > 0L }.map { (paymentId, due) ->
            val originalPayment = entitlement.sale.payments.single { it.attemptId == paymentId }
            ReturnTenderObligation(paymentId, originalPayment.tender, due)
        }.sortedBy { it.originalPaymentId.value }
        requireSales(obligations.fold(slice.payable.zero()) { sum, item -> sum + item.amount } == slice.payable, SalesRule.RETURN_ENTITLEMENT_EXHAUSTED)
        val accepted = AcceptedReturn(returnId, entitlement.scope, entitlement.saleId, claim.id, claim.portionId,
            claim.quantity, cursor.consumedQuantity, actorId, claim.evidence, disposition, inspectionEvidence, slice.payable, slice.components, obligations, Revision(0))
        val afterClaim = ReturnClaim(claim.id, claim.scope, claim.saleId, claim.portionId, claim.quantity,
            claim.claimantOperatorId, claim.evidence, claim.revision.next(), returnId)
        val afterEntitlement = ReceiptEntitlement(entitlement.sale, entitlement.revision.next(),
            entitlement.cursors.map { if (it.portionId == cursor.portionId) slice.remaining else it },
            entitlement.remainingComponentTenders.map { balances.getValue(it.componentKey) })
        return ReturnAccepted(afterEntitlement, afterClaim, accepted)
    }

    /** Cash is reserved for this pending payout; other moves cannot spend it while unknown. */
    public fun startRefund(
        command: SalesCommand,
        acceptedReturn: AcceptedReturn,
        expectedReturnRevision: Revision,
        session: CashSession,
        expectedSessionRevision: Revision,
        attemptId: DomainId,
        originalPaymentId: DomainId,
        amount: Money,
    ): SalesTransition<RefundWithCash> {
        val actor = requireRefundActor(command, "refund.record", acceptedReturn, expectedReturnRevision, session, expectedSessionRevision, amount.requirePositive())
        sameCurrency(acceptedReturn.originalPayable, amount)
        requireSales(!acceptedReturn.unresolved, SalesRule.PAYMENT_UNRESOLVED)
        requireSales(acceptedReturn.attempts.none { it.id == attemptId }, SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        val obligation = acceptedReturn.tenderObligations.singleOrNull { it.originalPaymentId == originalPaymentId }
            ?: throw SalesRuleViolation(SalesRule.TENDER_MISMATCH)
        val alreadyPaid = acceptedReturn.attempts.filter { it.originalPaymentId == originalPaymentId }
            .fold(amount.zero()) { total, item -> total + (item.refund?.amount ?: amount.zero()) }
        requireSales(amount.minor <= (obligation.amount - alreadyPaid).minor, SalesRule.RETURN_ENTITLEMENT_EXHAUSTED)
        val attempt = RefundAttempt(attemptId, command.operationId, actor, session.id, originalPaymentId, obligation.tender, amount, AttemptStatus.AWAITING_ATTESTATION)
        val after = acceptedReturn.update(acceptedReturn.attempts + attempt)
        val cash = session.beginAttempt(attemptId, if (obligation.tender == Tender.Cash) amount else amount.zero())
        return refundTransition(command, acceptedReturn, after, session, cash, emptyList(), listOf(SalesUniqueCheck(SalesUniqueKind.REFUND_ATTEMPT, attemptId)))
    }

    public fun recordRefundOutcome(
        command: SalesCommand,
        acceptedReturn: AcceptedReturn,
        expectedReturnRevision: Revision,
        session: CashSession,
        expectedSessionRevision: Revision,
        attemptId: DomainId,
        outcome: RefundOutcome,
    ): SalesTransition<RefundWithCash> = recordRefundOutcomeFrom(command, acceptedReturn, expectedReturnRevision,
        session, expectedSessionRevision, attemptId, outcome, command.operationId)

    /** See Collections.applyRecordedOutcome: current accounting authority, original physical fact identity. */
    public fun applyRecordedRefundOutcome(command: SalesCommand, acceptedReturn: AcceptedReturn, session: CashSession,
        attemptId: DomainId, recordedObservationOperationId: OperationId, outcome: RefundOutcome): SalesTransition<RefundWithCash> {
        requireSales(recordedObservationOperationId != command.operationId, SalesRule.INVALID_EVIDENCE)
        return recordRefundOutcomeFrom(command, acceptedReturn, acceptedReturn.revision, session, session.revision,
            attemptId, outcome, recordedObservationOperationId)
    }

    /** Explicit current REFUND_RESOLVE over original immutable payout custody; no fresh payout. */
    public fun applySupervisedRecordedRefundOutcome(command: SalesCommand, acceptedReturn: AcceptedReturn, session: CashSession,
        attemptId: DomainId, recordedObservationOperationId: OperationId, recordingActorId: DomainId,
        originalUnknownOperationId: OperationId?, originalUnknownBodyDigest: PayloadDigest?,
        outcome: RefundOutcome): SalesTransition<RefundWithCash> {
        requireSales(recordedObservationOperationId != command.operationId, SalesRule.INVALID_EVIDENCE)
        return recordRefundOutcomeFrom(command, acceptedReturn, acceptedReturn.revision, session, session.revision,
            attemptId, outcome, recordedObservationOperationId, SupervisedRecordedObservation(recordingActorId,
                originalUnknownOperationId, originalUnknownBodyDigest))
    }

    private fun recordRefundOutcomeFrom(command: SalesCommand, acceptedReturn: AcceptedReturn, expectedReturnRevision: Revision,
        session: CashSession, expectedSessionRevision: Revision, attemptId: DomainId, outcome: RefundOutcome,
        observationOperationId: OperationId, supervised: SupervisedRecordedObservation? = null): SalesTransition<RefundWithCash> {
        val attempt = acceptedReturn.attempts.singleOrNull { it.id == attemptId } ?: throw SalesRuleViolation(SalesRule.INVALID_STATE)
        val action = if (attempt.status == AttemptStatus.OUTCOME_UNKNOWN) "refund.resolve" else "refund.record"
        val accountingActor = if (supervised == null)
            requireRefundActor(command, action, acceptedReturn, expectedReturnRevision, session, expectedSessionRevision, attempt.amount)
        else {
            requireRevision(acceptedReturn.revision, expectedReturnRevision); requireRevision(session.revision, expectedSessionRevision)
            requireSales(acceptedReturn.scope == session.scope, SalesRule.WRONG_BRANCH)
            requireSessionOwner(session, attempt.originalActorId)
            authorizeSupervisedRecordedOutcome(command, SecurityAction.REFUND_RESOLVE, acceptedReturn.scope,
                acceptedReturn.id, acceptedReturn.revision, attempt.originalActorId, attempt.amount)
        }
        val actor = if (supervised == null) accountingActor else attempt.originalActorId
        val recorder = supervised?.recorderId ?: accountingActor
        val link = supervised?.let { supervisedResolutionLink(it, command, attempt.originalOperationId,
            observationOperationId, attempt.status, actor, outcome is RefundOutcome.Unknown) }
        requireSales(attempt.status == AttemptStatus.AWAITING_ATTESTATION || attempt.status == AttemptStatus.OUTCOME_UNKNOWN, SalesRule.IMMUTABLE_RECORD)
        requireSales(attempt.originalActorId == actor && attempt.originalCashSessionId == session.id && attempt.id in session.pendingAttemptIds, SalesRule.OWNER_REQUIRED)
        val evidence: CashierEvidence
        var external: ExternalTenderEvidence? = null
        val status = when (outcome) {
            is RefundOutcome.Unknown -> { evidence = outcome.evidence; AttemptStatus.OUTCOME_UNKNOWN }
            is RefundOutcome.NotPaid -> { evidence = outcome.evidence; requireReason(outcome.reason); AttemptStatus.NOT_PAID }
            is RefundOutcome.CashPaid -> {
                requireSales(attempt.tender == Tender.Cash, SalesRule.TENDER_MISMATCH)
                evidence = outcome.evidence; AttemptStatus.ATTESTED_PAID
            }
            is RefundOutcome.ExternalAttested -> {
                validateExternal(attempt.tender, outcome.evidence)
                external = outcome.evidence; evidence = outcome.evidence.attestation; AttemptStatus.ATTESTED_PAID
            }
        }
        evidence.requireActor(recorder)
        val refund = if (status != AttemptStatus.ATTESTED_PAID) null else RefundRecord(attemptId, acceptedReturn.id, attempt.originalPaymentId,
            attempt.originalOperationId, observationOperationId, actor, session.id, attempt.tender, attempt.amount, evidence, external, link)
        val movement = if (refund != null && attempt.tender == Tender.Cash) CashMovement(attemptId, command.operationId, session.id, actor,
            CashMovementKind.CUSTOMER_REFUND, -attempt.amount, attempt.amount.zero(), attempt.amount.zero(), evidence, null) else null
        val updatedAttempt = RefundAttempt(attempt.id, attempt.originalOperationId, attempt.originalActorId, attempt.originalCashSessionId,
            attempt.originalPaymentId, attempt.tender, attempt.amount, status, outcome, refund, link)
        val after = acceptedReturn.update(acceptedReturn.attempts.map { if (it.id == attemptId) updatedAttempt else it })
        val cash = if (status == AttemptStatus.OUTCOME_UNKNOWN) session else session.finishAttempt(command, attemptId,
            if (attempt.tender == Tender.Cash) attempt.amount else attempt.amount.zero(), movement,
            externalRefund = if (refund != null && attempt.tender is Tender.External) attempt.amount else attempt.amount.zero())
        val effects = mutableListOf<SalesEffect>(RefundObservationRecorded(attemptId, outcome))
        if (refund != null) effects += refund
        if (movement != null) effects += movement
        val unique = if (refund == null) emptyList() else listOf(SalesUniqueCheck(SalesUniqueKind.REFUND_RECORD, attemptId)) +
            if (movement == null) emptyList() else listOf(SalesUniqueCheck(SalesUniqueKind.CASH_SOURCE, attemptId))
        return refundTransition(command, acceptedReturn, after, session, cash, effects, unique)
    }
}

/** Only sale.finalize creates this, under a durable unique sale-entitlement constraint. */
internal fun initialEntitlement(sale: PaidSale): ReceiptEntitlement = ReceiptEntitlement(sale, Revision(0),
    sale.quote.lines.map { sale.quote.openReturnCursor(it.portionId) }, sale.originalTenderAllocations)

private fun entitlementVersion(entitlement: ReceiptEntitlement): SalesVersionCheck =
    SalesVersionCheck(SalesAggregate.RETURN_ENTITLEMENT, entitlement.saleId, entitlement.revision)

private fun requireRefundActor(command: SalesCommand, action: String, acceptedReturn: AcceptedReturn, revision: Revision,
    session: CashSession, sessionRevision: Revision, amount: Money): DomainId {
    requireRevision(acceptedReturn.revision, revision)
    requireRevision(session.revision, sessionRevision)
    val actor = authorize(command, action, acceptedReturn.scope, acceptedReturn.id, revision, amount)
    requireSales(session.scope == acceptedReturn.scope, SalesRule.WRONG_BRANCH)
    requireSessionOwner(session, actor)
    return actor
}

private fun refundTransition(command: SalesCommand, before: AcceptedReturn, after: AcceptedReturn, beforeCash: CashSession,
    afterCash: CashSession, effects: List<SalesEffect>, unique: List<SalesUniqueCheck>): SalesTransition<RefundWithCash> =
    SalesTransition(command, RefundWithCash(after, afterCash),
        listOf(SalesVersionCheck(SalesAggregate.ACCEPTED_RETURN, before.id, before.revision), cashVersion(beforeCash)), unique,
        listOf(AcceptedReturnChanged(after)) + if (beforeCash === afterCash) effects else listOf(CashSessionChanged(afterCash)) + effects)
