package com.ukkera.pos.sales

import com.ukkera.pos.inventory.CollectionExposure
import com.ukkera.pos.inventory.InventoryScope
import com.ukkera.pos.inventory.ReservationIssueResult
import com.ukkera.pos.inventory.ReservationPlan
import com.ukkera.pos.inventory.ReservationSafetyResult
import com.ukkera.pos.inventory.ValueIssuePurpose
import com.ukkera.pos.pricing.PricingSnapshot
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.PrincipalKind
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.AllocationWeight
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.allocateByLargestRemainder

public enum class ExternalTenderKind { CARD, EXTERNAL_WALLET, OTHER }

/** Activated tender configuration snapshot. This contains no provider authorization API. */
public sealed interface Tender {
    public data object Cash : Tender
    public data class External(
        public val tenderId: DomainId,
        public val policyRevision: Revision,
        public val kind: ExternalTenderKind,
        public val requiresTerminal: Boolean,
        public val requiresReference: Boolean,
    ) : Tender
}

public data class ExternalTenderEvidence(
    public val attestation: CashierEvidence,
    public val terminal: String?,
    public val reference: String?,
) {
    init {
        requireSales(terminal == null || terminal.isNotBlank() && terminal.length <= 128, SalesRule.INVALID_EVIDENCE)
        requireSales(reference == null || reference.isNotBlank() && reference.length <= 128, SalesRule.INVALID_EVIDENCE)
    }
}

public sealed interface CollectionOutcome {
    public data class Unknown(public val evidence: CashierEvidence) : CollectionOutcome
    public data class NotPaid(public val evidence: CashierEvidence, public val reason: String) : CollectionOutcome
    public data class CashReceived(
        public val received: Money,
        public val change: Money,
        public val evidence: CashierEvidence,
    ) : CollectionOutcome
    /** Cashier's actual attestation, explicitly NOT independently verified bank success. */
    public data class ExternalAttested(public val evidence: ExternalTenderEvidence) : CollectionOutcome
}

/**
 * Immutable historical linkage for an explicit supervised resolution application. This value
 * is NOT authority, a grant, or proof a fact exists. The adapter verifies the exact SQL UNKNOWN,
 * reserved resolution, current command/audit and all effects before storing/reading it.
 * Original collector/payer and cash session stay on the attempt/record, never this recorder.
 */
public data class RetailResolutionObservationLink internal constructor(
    public val originalUnknownOperationId: OperationId,
    public val originalUnknownBodyDigest: PayloadDigest,
    public val resolutionOperationId: OperationId,
    public val resolutionRecorderId: DomainId,
    public val accountingOperationId: OperationId,
    public val accountingActorId: DomainId,
)

/** Only built on the explicit supervised reducer path; historical inputs are still just data. */
internal class SupervisedRecordedObservation(val recorderId: DomainId,
    val originalUnknownOperationId: OperationId?, val originalUnknownBodyDigest: PayloadDigest?)

public enum class AttemptStatus { AWAITING_ATTESTATION, OUTCOME_UNKNOWN, NOT_PAID, ATTESTED_PAID }
public enum class CollectionStatus { LOCKED_UNPAID, PART_PAID, OUTCOME_UNKNOWN, COVERED, FINALIZED_PAID }

public data class PaymentRecord internal constructor(
    public val attemptId: DomainId,
    public val originalOperationId: OperationId,
    public val recordingOperationId: OperationId,
    public val collectionId: DomainId,
    public val collectorId: DomainId,
    public val cashSessionId: DomainId,
    public val payerReference: String?,
    public val tender: Tender,
    public val amount: Money,
    public val cashReceived: Money?,
    public val changeGiven: Money?,
    public val attestation: CashierEvidence,
    public val externalEvidence: ExternalTenderEvidence?,
    public val resolutionObservation: RetailResolutionObservationLink? = null,
    /** Distinct inert FOOD linkage. A retail reader must reject it. */
    public val foodResolutionObservation: FoodResolutionObservationLink? = null,
) : SalesEffect

public class PaymentAttempt internal constructor(
    public val id: DomainId,
    public val originalOperationId: OperationId,
    public val originalActorId: DomainId,
    public val originalCashSessionId: DomainId,
    public val payerReference: String?,
    public val tender: Tender,
    public val amount: Money,
    public val status: AttemptStatus,
    public val latestObservation: CollectionOutcome? = null,
    public val payment: PaymentRecord? = null,
    public val resolutionObservation: RetailResolutionObservationLink? = null,
    /** Distinct inert FOOD linkage. A retail reader must reject it. */
    public val foodResolutionObservation: FoodResolutionObservationLink? = null,
)

public class CollectionLock internal constructor(
    public val id: DomainId,
    public val scope: BranchScope,
    public val orderId: DomainId,
    public val lockedOrderRevision: Revision,
    public val originalCreatorId: DomainId,
    public val collectorId: DomainId,
    public val cashSessionId: DomainId,
    public val quote: PricingSnapshot,
    public val stockRequirements: RetailStockRequirements,
    public val reservationId: DomainId,
    public val revision: Revision,
    attempts: List<PaymentAttempt> = emptyList(),
    public val saleId: DomainId? = null,
    public val priceSourceReference: SalesPriceSourceReference? = null,
    public val returnPolicySourceReference: SalesReturnPolicySourceReference? = null,
) {
    public val attempts: List<PaymentAttempt> = frozenList(attempts, 64)
    public val collected: Money get() = attempts.fold(quote.payable.zero()) { total, item -> total + (item.payment?.amount ?: total.zero()) }
    public val remainingPayable: Money get() = quote.payable - collected
    public val unresolved: Boolean get() = attempts.any { it.status == AttemptStatus.AWAITING_ATTESTATION || it.status == AttemptStatus.OUTCOME_UNKNOWN }
    public val status: CollectionStatus get() = when {
        saleId != null -> CollectionStatus.FINALIZED_PAID
        unresolved -> CollectionStatus.OUTCOME_UNKNOWN
        collected == quote.payable -> CollectionStatus.COVERED
        collected.minor > 0 -> CollectionStatus.PART_PAID
        else -> CollectionStatus.LOCKED_UNPAID
    }

    internal fun update(attempts: List<PaymentAttempt> = this.attempts, saleId: DomainId? = this.saleId): CollectionLock =
        CollectionLock(id, scope, orderId, lockedOrderRevision, originalCreatorId, collectorId, cashSessionId,
            quote, stockRequirements, reservationId, revision.next(), attempts, saleId, priceSourceReference, returnPolicySourceReference)
}

public data class CollectionChanged(public val collection: CollectionLock) : SalesEffect
/** Append observations, including unknown/not-paid; do not erase them when replacing a state row. */
public data class CollectionObservationRecorded(public val attemptId: DomainId, public val outcome: CollectionOutcome) : SalesEffect
public data class BegunCollection(public val order: OwnedOrder, public val collection: CollectionLock)
public data class CollectionWithCash(public val collection: CollectionLock, public val session: CashSession)

public data class OriginalTenderShare(public val paymentId: DomainId, public val amount: Money)
public class OriginalComponentTenderAllocation internal constructor(
    public val componentKey: String,
    shares: List<OriginalTenderShare>,
) {
    public val shares: List<OriginalTenderShare> = frozenList(shares, 64)
}

/** Financial paid document; neither fulfillment, a printed receipt nor fiscal acceptance. */
public class PaidSale internal constructor(
    public val id: DomainId,
    public val scope: BranchScope,
    public val orderId: DomainId,
    public val originalCreatorId: DomainId,
    public val collectorId: DomainId,
    public val collectingSessionId: DomainId,
    public val quote: PricingSnapshot,
    public val stockIssueId: DomainId,
    payments: List<PaymentRecord>,
    originalTenderAllocations: List<OriginalComponentTenderAllocation>,
    public val priceSourceReference: SalesPriceSourceReference? = null,
    public val returnPolicySourceReference: SalesReturnPolicySourceReference? = null,
) {
    public val payments: List<PaymentRecord> = frozenList(payments, 64)
    public val originalTenderAllocations: List<OriginalComponentTenderAllocation> = frozenList(originalTenderAllocations)
}

public data class SalePosted(public val sale: PaidSale) : SalesEffect
public data class FinalizedSale(
    public val order: OwnedOrder,
    public val collection: CollectionLock,
    public val sale: PaidSale,
    public val entitlement: ReceiptEntitlement,
)

public object Collections {
    /** Reserve the ENTIRE pinned retail allocation in the same transaction before requesting money. */
    public fun begin(
        command: SalesCommand,
        order: OwnedOrder,
        expectedOrderRevision: Revision,
        session: CashSession,
        expectedSessionRevision: Revision,
        collectionId: DomainId,
        reservationPlan: ReservationPlan,
    ): SalesTransition<BegunCollection> {
        val actor = requireOrderActor(command, "payment.begin", order, expectedOrderRevision, order.quote.payable)
        requireRevision(session.revision, expectedSessionRevision)
        requireSessionOwner(session, actor)
        requireSales(session.scope == order.scope, SalesRule.WRONG_BRANCH)
        requireSales(order.collectionId == null && order.paidSaleId == null, SalesRule.PAYABLE_LOCKED)
        requireSales(order.handoff == null, SalesRule.HANDOFF_CONFLICT)
        order.stockRequirements.validate(order.scope, order.quote)
        val reservation = reservationPlan.proof
        requireSales(reservation.scope == InventoryScope(order.scope.companyId, order.scope.branchId) &&
            reservation.orderId == order.id && reservation.quoteRevision == order.quote.revision &&
            reservation.collectionId == collectionId && reservation.originalActorId == actor &&
            reservation.operationId == command.operationId &&
            reservation.validatedAtEpochMillis == command.recordedAt.toEpochMilliseconds() &&
            reservation.exposure == CollectionExposure.UNKNOWN &&
            sameAllocations(reservation.allocations, order.stockRequirements.allocations), SalesRule.STOCK_PROOF_MISMATCH)
        val after = order.update(collectionId = collectionId)
        val collection = CollectionLock(collectionId, order.scope, order.id, after.revision, order.originalCreatorId,
            actor, session.id, order.quote, order.stockRequirements, reservation.reservationId, Revision(0),
            priceSourceReference = order.priceSourceReference, returnPolicySourceReference = order.returnPolicySourceReference)
        return SalesTransition(command, BegunCollection(after, collection), listOf(orderVersion(order), cashVersion(session)),
            listOf(SalesUniqueCheck(SalesUniqueKind.COLLECTION, collectionId)), listOf(OrderChanged(after), CollectionChanged(collection)),
            listOf(InventoryCommitDependency(InventoryCommitKind.RESERVE, reservationPlan.plan, reservation.reservationId)))
    }

    /**
     * Normalized per-ID reservation port for the same OwnedOrder/CollectionLock state machine.
     * This scalar is NOT an authority or legacy ReservationPlan. The calling transaction must
     * have reserved the whole immutable demand on its actual sink, and its retained producer
     * must prove every per-ID allocation. No physical tender right is created by this port.
     */
    public fun beginNormalized(
        command: SalesCommand, order: OwnedOrder, expectedOrderRevision: Revision,
        session: CashSession, expectedSessionRevision: Revision, collectionId: DomainId,
        reserved: com.ukkera.pos.inventory.InventoryReservationHeader,
    ): SalesTransition<BegunCollection> {
        val actor = requireOrderActor(command, "payment.begin", order, expectedOrderRevision, order.quote.payable)
        requireRevision(session.revision, expectedSessionRevision)
        requireSessionOwner(session, actor)
        requireSales(session.scope == order.scope, SalesRule.WRONG_BRANCH)
        requireSales(order.collectionId == null && order.paidSaleId == null, SalesRule.PAYABLE_LOCKED)
        requireSales(order.handoff == null, SalesRule.HANDOFF_CONFLICT)
        requireSales(order.stockRequirements.normalized, SalesRule.UNSUPPORTED_STOCK_TIMING)
        order.stockRequirements.validate(order.scope, order.quote)
        val reservationExpiry = reserved.expiresAtEpochMillis
        requireSales(reserved.revision == Revision(1) && reserved.status == com.ukkera.pos.inventory.ReservationStatus.RESERVED &&
            reserved.holdCount == 0L && reserved.allocationCount >= checkNotNull(order.stockRequirements.normalizedLines).size &&
            reserved.issueId == null && reserved.binding == com.ukkera.pos.inventory.CollectionBinding(order.id,
                order.quote.revision, collectionId, actor, CollectionExposure.UNKNOWN) &&
            (reservationExpiry == null || reservationExpiry > command.recordedAt.toEpochMilliseconds()),
            SalesRule.STOCK_PROOF_MISMATCH)
        val after = order.update(collectionId = collectionId)
        val collection = CollectionLock(collectionId, order.scope, order.id, after.revision, order.originalCreatorId,
            actor, session.id, order.quote, order.stockRequirements, reserved.id, Revision(0),
            priceSourceReference = order.priceSourceReference, returnPolicySourceReference = order.returnPolicySourceReference)
        return SalesTransition(command, BegunCollection(after, collection), listOf(orderVersion(order), cashVersion(session)),
            listOf(SalesUniqueCheck(SalesUniqueKind.COLLECTION, collectionId)), listOf(OrderChanged(after), CollectionChanged(collection)))
    }

    /**
     * Finalize against the actual same-sink issued header, never invented/capped allocations.
     * Whole-commerce verification binds issueId/purpose/cost/slices/originals to this reservation.
     * This deliberately does not add a start-attempt or physical-observation back door.
     */
    public fun finalizeNormalized(
        command: SalesCommand, order: OwnedOrder, expectedOrderRevision: Revision,
        collection: CollectionLock, expectedCollectionRevision: Revision, saleId: DomainId,
        issued: com.ukkera.pos.inventory.InventoryReservationHeader, issueId: DomainId,
    ): SalesTransition<FinalizedSale> {
        val action = if (collection.quote.payable.minor == 0L) "sale.finalizeZero" else "sale.finalize"
        val actor = requireOrderActor(command, action, order, expectedOrderRevision, collection.quote.payable)
        requireRevision(collection.revision, expectedCollectionRevision)
        requireSales(order.stockRequirements.normalized && collection.stockRequirements.normalized &&
            order.stockRequirements.sameSource(collection.stockRequirements), SalesRule.UNSUPPORTED_STOCK_TIMING)
        requireSales(collection.scope == order.scope && collection.orderId == order.id && collection.collectorId == actor &&
            order.collectionId == collection.id && sameQuote(order.quote, collection.quote) &&
            order.priceSourceReference == collection.priceSourceReference && order.returnPolicySourceReference == collection.returnPolicySourceReference &&
            collection.lockedOrderRevision == order.revision,
            SalesRule.PAYABLE_LOCKED)
        requireSales(collection.saleId == null && order.paidSaleId == null, SalesRule.IMMUTABLE_RECORD)
        requireSales(!collection.unresolved, SalesRule.PAYMENT_UNRESOLVED)
        requireSales(collection.collected == collection.quote.payable, SalesRule.INSUFFICIENT_COVERAGE)
        requireSales(issued.id == collection.reservationId && issued.status == com.ukkera.pos.inventory.ReservationStatus.ISSUED &&
            issued.issueId == issueId && issued.holdCount == 0L && issued.revision.value >= 2 &&
            issued.allocationCount >= checkNotNull(collection.stockRequirements.normalizedLines).size &&
            issued.binding == com.ukkera.pos.inventory.CollectionBinding(order.id, collection.quote.revision, collection.id,
                actor, CollectionExposure.UNKNOWN), SalesRule.STOCK_PROOF_MISMATCH)
        val payments = collection.attempts.mapNotNull { it.payment }
        val sale = PaidSale(saleId, order.scope, order.id, order.originalCreatorId, actor, collection.cashSessionId,
            collection.quote, issueId, payments, allocateOriginalTenders(collection.quote, payments), collection.priceSourceReference, collection.returnPolicySourceReference)
        val entitlement = initialEntitlement(sale)
        val afterOrder = order.update(paidSaleId = saleId)
        val afterCollection = collection.update(saleId = saleId)
        return SalesTransition(command, FinalizedSale(afterOrder, afterCollection, sale, entitlement),
            listOf(orderVersion(order), collectionVersion(collection)),
            listOf(SalesUniqueCheck(SalesUniqueKind.SALE, saleId), SalesUniqueCheck(SalesUniqueKind.SALE_ORDER, order.id),
                SalesUniqueCheck(SalesUniqueKind.RETURN_ENTITLEMENT, saleId)),
            listOf(OrderChanged(afterOrder), CollectionChanged(afterCollection), SalePosted(sale), ReceiptEntitlementChanged(entitlement)))
    }

    /**
     * Start on an actual normalized UNKNOWN-bound reservation. The adapter must separately
     * prove complete literal allocation/holder membership and current safety in the same writer.
     * This path preserves every stock row and claim; Finalize owns actual goods issue.
     */
    public fun startNormalizedAttempt(
        command: SalesCommand, collection: CollectionLock, expectedRevision: Revision,
        session: CashSession, expectedSessionRevision: Revision, attemptId: DomainId,
        amount: Money, reserved: com.ukkera.pos.inventory.InventoryReservationHeader,
        payerReference: String? = null, tender: Tender = Tender.Cash,
    ): SalesTransition<CollectionWithCash> {
        requireCollectionActor(command, "payment.record", collection, expectedRevision, session, expectedSessionRevision, amount.requirePositive())
        requireSales(collection.stockRequirements.normalized, SalesRule.UNSUPPORTED_STOCK_TIMING)
        collection.stockRequirements.validate(collection.scope, collection.quote)
        requireSales(!collection.unresolved, SalesRule.PAYMENT_UNRESOLVED)
        requireSales(collection.attempts.none { it.id == attemptId }, SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        sameCurrency(collection.quote.payable, amount)
        requireSales(amount.minor <= collection.remainingPayable.minor, SalesRule.INVALID_AMOUNT)
        requireSales(payerReference == null || payerReference.isNotBlank() && payerReference.length <= 128, SalesRule.INVALID_EVIDENCE)
        val reservationExpiry = reserved.expiresAtEpochMillis
        requireSales(reserved.id == collection.reservationId && reserved.status == com.ukkera.pos.inventory.ReservationStatus.RESERVED &&
            reserved.issueId == null && reserved.holdCount == 0L &&
            reserved.allocationCount >= checkNotNull(collection.stockRequirements.normalizedLines).size &&
            reserved.binding == com.ukkera.pos.inventory.CollectionBinding(collection.orderId, collection.quote.revision,
                collection.id, collection.collectorId, CollectionExposure.UNKNOWN) &&
            (reservationExpiry == null || command.recordedAt.toEpochMilliseconds() < reservationExpiry),
            SalesRule.STOCK_PROOF_MISMATCH)
        val attempt = PaymentAttempt(attemptId, command.operationId, collection.collectorId, collection.cashSessionId,
            payerReference, tender, amount, AttemptStatus.AWAITING_ATTESTATION)
        val after = collection.update(attempts = collection.attempts + attempt)
        val cash = session.beginAttempt(attemptId, amount.zero())
        return collectionTransition(command, collection, after, session, cash, emptyList(),
            listOf(SalesUniqueCheck(SalesUniqueKind.PAYMENT_ATTEMPT, attemptId)))
    }

    /** Persist this intent before physically collecting. Unknown attempts block all new attempts. */
    public fun startAttempt(
        command: SalesCommand,
        collection: CollectionLock,
        expectedRevision: Revision,
        session: CashSession,
        expectedSessionRevision: Revision,
        attemptId: DomainId,
        tender: Tender,
        amount: Money,
        safety: ReservationSafetyResult.Safe,
        payerReference: String? = null,
    ): SalesTransition<CollectionWithCash> {
        requireCollectionActor(command, "payment.record", collection, expectedRevision, session, expectedSessionRevision, amount.requirePositive())
        requireSales(!collection.unresolved, SalesRule.PAYMENT_UNRESOLVED)
        requireSales(collection.attempts.none { it.id == attemptId }, SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        sameCurrency(collection.quote.payable, amount)
        requireSales(amount.minor <= collection.remainingPayable.minor, SalesRule.INVALID_AMOUNT)
        requireSales(payerReference == null || payerReference.isNotBlank() && payerReference.length <= 128, SalesRule.INVALID_EVIDENCE)
        val proof = safety.proof
        requireSales(proof.scope == InventoryScope(collection.scope.companyId, collection.scope.branchId) &&
            proof.reservationId == collection.reservationId && proof.collectionId == collection.id &&
            proof.orderId == collection.orderId && proof.quoteRevision == collection.quote.revision &&
            proof.originalActorId == collection.collectorId && proof.operationId == command.operationId &&
            proof.validatedAtEpochMillis == command.recordedAt.toEpochMilliseconds() &&
            proof.exposure != CollectionExposure.UNCOLLECTED && proof.exposure != CollectionExposure.PAID &&
            sameAllocations(proof.allocations, collection.stockRequirements.allocations), SalesRule.STOCK_PROOF_MISMATCH)
        val attempt = PaymentAttempt(attemptId, command.operationId, collection.collectorId, collection.cashSessionId,
            payerReference, tender, amount, AttemptStatus.AWAITING_ATTESTATION)
        val after = collection.update(attempts = collection.attempts + attempt)
        val cash = session.beginAttempt(attemptId, amount.zero())
        return collectionTransition(command, collection, after, session, cash, emptyList(), listOf(SalesUniqueCheck(SalesUniqueKind.PAYMENT_ATTEMPT, attemptId)),
            listOf(InventoryCommitDependency(InventoryCommitKind.REVALIDATE, safety.plan, collection.reservationId)))
    }

    public fun recordOutcome(
        command: SalesCommand,
        collection: CollectionLock,
        expectedRevision: Revision,
        session: CashSession,
        expectedSessionRevision: Revision,
        attemptId: DomainId,
        outcome: CollectionOutcome,
    ): SalesTransition<CollectionWithCash> = recordOutcomeFrom(command, collection, expectedRevision, session,
        expectedSessionRevision, attemptId, outcome, command.operationId)

    /**
     * Financial application of an already durable immutable observation. The trusted
     * adapter must validate its original fact/slot/intent provenance. Authorization
     * binds THIS accounting command and the actual current revision; the payment
     * retains the original physical-observation ID, not a fabricated new attempt.
     */
    public fun applyRecordedOutcome(command: SalesCommand, collection: CollectionLock, session: CashSession,
        attemptId: DomainId, recordedObservationOperationId: OperationId, outcome: CollectionOutcome): SalesTransition<CollectionWithCash> {
        requireSales(recordedObservationOperationId != command.operationId, SalesRule.INVALID_EVIDENCE)
        return recordOutcomeFrom(command, collection, collection.revision, session, session.revision,
            attemptId, outcome, recordedObservationOperationId)
    }

    /**
     * Current COLLECTION_RESOLVE (ANY_OWNER when needed), not ordinary custody authorization.
     * This never starts an attempt. The installed adapter supplies the immutable fact and exact
     * original UNKNOWN linkage for resolution; a known original keeps its original recorder.
     */
    public fun applySupervisedRecordedOutcome(command: SalesCommand, collection: CollectionLock, session: CashSession,
        attemptId: DomainId, recordedObservationOperationId: OperationId, recordingActorId: DomainId,
        originalUnknownOperationId: OperationId?, originalUnknownBodyDigest: PayloadDigest?,
        outcome: CollectionOutcome): SalesTransition<CollectionWithCash> {
        requireSales(recordedObservationOperationId != command.operationId, SalesRule.INVALID_EVIDENCE)
        return recordOutcomeFrom(command, collection, collection.revision, session, session.revision, attemptId,
            outcome, recordedObservationOperationId, SupervisedRecordedObservation(recordingActorId,
                originalUnknownOperationId, originalUnknownBodyDigest))
    }

    private fun recordOutcomeFrom(command: SalesCommand, collection: CollectionLock, expectedRevision: Revision,
        session: CashSession, expectedSessionRevision: Revision, attemptId: DomainId, outcome: CollectionOutcome,
        observationOperationId: OperationId, supervised: SupervisedRecordedObservation? = null): SalesTransition<CollectionWithCash> {
        val attempt = collection.attempts.singleOrNull { it.id == attemptId } ?: throw SalesRuleViolation(SalesRule.INVALID_STATE)
        val action = if (attempt.status == AttemptStatus.OUTCOME_UNKNOWN) "payment.resolve" else "payment.record"
        val accountingActor = if (supervised == null)
            requireCollectionActor(command, action, collection, expectedRevision, session, expectedSessionRevision, attempt.amount)
        else {
            requireRevision(collection.revision, expectedRevision); requireRevision(session.revision, expectedSessionRevision)
            requireSales(collection.cashSessionId == session.id && collection.scope == session.scope, SalesRule.WRONG_BRANCH)
            requireSessionOwner(session, collection.collectorId)
            requireSales(collection.saleId == null, SalesRule.IMMUTABLE_RECORD)
            authorizeSupervisedRecordedOutcome(command, SecurityAction.COLLECTION_RESOLVE, collection.scope,
                collection.id, collection.revision, collection.collectorId, attempt.amount)
        }
        val actor = collection.collectorId
        val recorder = supervised?.recorderId ?: accountingActor
        val link = supervised?.let { supervisedResolutionLink(it, command, attempt.originalOperationId,
            observationOperationId, attempt.status, actor, outcome is CollectionOutcome.Unknown) }
        requireSales(attempt.status == AttemptStatus.AWAITING_ATTESTATION || attempt.status == AttemptStatus.OUTCOME_UNKNOWN, SalesRule.IMMUTABLE_RECORD)
        requireSales(attempt.originalActorId == actor && attempt.originalCashSessionId == session.id && attemptId in session.pendingAttemptIds, SalesRule.OWNER_REQUIRED)
        var payment: PaymentRecord? = null
        var movement: CashMovement? = null
        val evidence: CashierEvidence
        val status = when (outcome) {
            is CollectionOutcome.Unknown -> { evidence = outcome.evidence; AttemptStatus.OUTCOME_UNKNOWN }
            is CollectionOutcome.NotPaid -> { evidence = outcome.evidence; requireReason(outcome.reason); AttemptStatus.NOT_PAID }
            is CollectionOutcome.CashReceived -> {
                evidence = outcome.evidence
                requireSales(attempt.tender == Tender.Cash, SalesRule.TENDER_MISMATCH)
                sameCurrency(attempt.amount, outcome.received, outcome.change)
                outcome.received.requirePositive(); outcome.change.requireNonnegative()
                requireSales(outcome.received - outcome.change == attempt.amount, SalesRule.INVALID_AMOUNT)
                payment = paymentRecord(collection, attempt, evidence, outcome.received, outcome.change, null, observationOperationId, link)
                movement = CashMovement(attempt.id, command.operationId, session.id, actor, CashMovementKind.CUSTOMER_RECEIPT,
                    attempt.amount, outcome.received, outcome.change, evidence, null)
                AttemptStatus.ATTESTED_PAID
            }
            is CollectionOutcome.ExternalAttested -> {
                evidence = outcome.evidence.attestation
                validateExternal(attempt.tender, outcome.evidence)
                payment = paymentRecord(collection, attempt, evidence, null, null, outcome.evidence, observationOperationId, link)
                AttemptStatus.ATTESTED_PAID
            }
        }
        evidence.requireActor(recorder)
        val updatedAttempt = PaymentAttempt(attempt.id, attempt.originalOperationId, attempt.originalActorId,
            attempt.originalCashSessionId, attempt.payerReference, attempt.tender, attempt.amount, status, outcome, payment, link)
        val after = collection.update(attempts = collection.attempts.map { if (it.id == attemptId) updatedAttempt else it })
        val cash = if (status == AttemptStatus.OUTCOME_UNKNOWN) session else session.finishAttempt(command, attemptId, attempt.amount.zero(), movement,
            externalCollection = if (payment != null && attempt.tender is Tender.External) attempt.amount else attempt.amount.zero())
        val effects = mutableListOf<SalesEffect>(CollectionObservationRecorded(attemptId, outcome))
        if (payment != null) effects += payment
        if (movement != null) effects += movement
        val unique = if (payment == null) emptyList() else listOf(SalesUniqueCheck(SalesUniqueKind.PAYMENT_RECORD, attemptId)) +
            if (movement == null) emptyList() else listOf(SalesUniqueCheck(SalesUniqueKind.CASH_SOURCE, attemptId))
        return collectionTransition(command, collection, after, session, cash, effects, unique)
    }

    /** Retail only: inventory must have revalidated safety and planned one SALE issue now. */
    public fun finalizePaid(
        command: SalesCommand,
        order: OwnedOrder,
        expectedOrderRevision: Revision,
        collection: CollectionLock,
        expectedCollectionRevision: Revision,
        saleId: DomainId,
        issued: ReservationIssueResult.Issued,
    ): SalesTransition<FinalizedSale> {
        val action = if (collection.quote.payable.minor == 0L) "sale.finalizeZero" else "sale.finalize"
        val actor = requireOrderActor(command, action, order, expectedOrderRevision, collection.quote.payable)
        requireRevision(collection.revision, expectedCollectionRevision)
        requireSales(collection.scope == order.scope && collection.orderId == order.id && collection.collectorId == actor &&
            order.collectionId == collection.id && sameQuote(order.quote, collection.quote) &&
            order.priceSourceReference == collection.priceSourceReference && order.returnPolicySourceReference == collection.returnPolicySourceReference &&
            collection.lockedOrderRevision == order.revision, SalesRule.PAYABLE_LOCKED)
        requireSales(collection.saleId == null && order.paidSaleId == null, SalesRule.IMMUTABLE_RECORD)
        requireSales(!collection.unresolved, SalesRule.PAYMENT_UNRESOLVED)
        requireSales(collection.collected == collection.quote.payable, SalesRule.INSUFFICIENT_COVERAGE)
        val issue = issued.proof
        requireSales(issue.purpose == ValueIssuePurpose.RETAIL, SalesRule.UNSUPPORTED_STOCK_TIMING)
        requireSales(issue.scope == InventoryScope(order.scope.companyId, order.scope.branchId) &&
            issue.orderId == order.id && issue.collectionId == collection.id && issue.originalActorId == actor &&
            issue.quoteRevision == collection.quote.revision && issue.reservationId == collection.reservationId &&
            issue.operationId == command.operationId && issue.validatedAtEpochMillis == command.recordedAt.toEpochMilliseconds() &&
            sameAllocations(issue.costedAllocations.map { it.allocation }, collection.stockRequirements.allocations), SalesRule.STOCK_PROOF_MISMATCH)
        val payments = collection.attempts.mapNotNull { it.payment }
        val sale = PaidSale(saleId, order.scope, order.id, order.originalCreatorId, actor, collection.cashSessionId,
            collection.quote, issue.issueId, payments, allocateOriginalTenders(collection.quote, payments), collection.priceSourceReference, collection.returnPolicySourceReference)
        val entitlement = initialEntitlement(sale)
        val afterOrder = order.update(paidSaleId = saleId)
        val afterCollection = collection.update(saleId = saleId)
        return SalesTransition(command, FinalizedSale(afterOrder, afterCollection, sale, entitlement),
            listOf(orderVersion(order), collectionVersion(collection)),
            listOf(SalesUniqueCheck(SalesUniqueKind.SALE, saleId), SalesUniqueCheck(SalesUniqueKind.SALE_ORDER, order.id),
                SalesUniqueCheck(SalesUniqueKind.RETURN_ENTITLEMENT, saleId)),
            listOf(OrderChanged(afterOrder), CollectionChanged(afterCollection), SalePosted(sale), ReceiptEntitlementChanged(entitlement)),
            listOf(InventoryCommitDependency(InventoryCommitKind.RETAIL_ISSUE, issued.plan, issue.reservationId, issue.issueId)))
    }
}

internal fun validateExternal(tender: Tender, evidence: ExternalTenderEvidence) {
    requireSales(tender is Tender.External, SalesRule.TENDER_MISMATCH)
    tender as Tender.External
    requireSales(!tender.requiresTerminal || evidence.terminal != null, SalesRule.REFERENCE_REQUIRED)
    requireSales(!tender.requiresReference || evidence.reference != null, SalesRule.REFERENCE_REQUIRED)
}

private fun paymentRecord(collection: CollectionLock, attempt: PaymentAttempt, evidence: CashierEvidence,
    received: Money?, change: Money?, external: ExternalTenderEvidence?, observationOperationId: OperationId,
    resolutionObservation: RetailResolutionObservationLink? = null): PaymentRecord =
    PaymentRecord(attempt.id, attempt.originalOperationId, observationOperationId, collection.id, attempt.originalActorId,
        attempt.originalCashSessionId, attempt.payerReference, attempt.tender, attempt.amount, received, change, evidence, external, resolutionObservation)

private fun requireCollectionActor(command: SalesCommand, action: String, collection: CollectionLock, revision: Revision,
    session: CashSession, sessionRevision: Revision, amount: Money): DomainId {
    requireRevision(collection.revision, revision)
    requireRevision(session.revision, sessionRevision)
    val actor = authorize(command, action, collection.scope, collection.id, revision, amount)
    requireSales(collection.collectorId == actor && collection.cashSessionId == session.id, SalesRule.OWNER_REQUIRED)
    requireSales(session.scope == collection.scope, SalesRule.WRONG_BRANCH)
    requireSessionOwner(session, actor)
    requireSales(collection.saleId == null, SalesRule.IMMUTABLE_RECORD)
    return actor
}

internal fun collectionVersion(collection: CollectionLock): SalesVersionCheck = SalesVersionCheck(SalesAggregate.COLLECTION, collection.id, collection.revision)

private fun collectionTransition(command: SalesCommand, before: CollectionLock, after: CollectionLock,
    cashBefore: CashSession, cashAfter: CashSession, effects: List<SalesEffect>, unique: List<SalesUniqueCheck>,
    dependencies: List<InventoryCommitDependency> = emptyList()): SalesTransition<CollectionWithCash> =
    SalesTransition(command, CollectionWithCash(after, cashAfter), listOf(collectionVersion(before), cashVersion(cashBefore)), unique,
        listOf(CollectionChanged(after)) + if (cashAfter === cashBefore) effects else listOf(CashSessionChanged(cashAfter)) + effects, dependencies)

private fun sameAllocations(first: List<com.ukkera.pos.inventory.StockAllocation>, second: List<com.ukkera.pos.inventory.StockAllocation>): Boolean =
    first.size == second.size && first.associateBy { it.allocationId } == second.associateBy { it.allocationId }

private fun sameQuote(first: PricingSnapshot, second: PricingSnapshot): Boolean =
    first.snapshotId == second.snapshotId && first.revision == second.revision && first.profile == second.profile &&
        first.payable == second.payable && first.components == second.components && first.lines == second.lines

/** Immutable matrix: component rows and original tender columns each conserve exactly. */
private fun allocateOriginalTenders(quote: PricingSnapshot, payments: List<PaymentRecord>): List<OriginalComponentTenderAllocation> {
    requireSales(quote.components.size <= 1_024, SalesRule.PAYLOAD_TOO_LARGE)
    val remaining = payments.sortedBy { it.attemptId.value }.associate { it.attemptId.value to it.amount }.toMutableMap()
    val rows = quote.components.sortedBy { it.key }.map { component ->
        val shares = allocateByLargestRemainder(component.posted, remaining.map { AllocationWeight(it.key, it.value.minor) })
        OriginalComponentTenderAllocation(component.key, shares.map { share ->
            val available = remaining.getValue(share.key)
            requireSales(share.amount.minor <= available.minor, SalesRule.INVALID_AMOUNT)
            remaining[share.key] = available - share.amount
            OriginalTenderShare(DomainId(share.key), share.amount)
        })
    }
    requireSales(remaining.values.all { it.minor == 0L }, SalesRule.INSUFFICIENT_COVERAGE)
    return frozenList(rows)
}

/** Never rewrites the private-owner requirement to the current supervisor to evade ANY_OWNER. */
internal fun authorizeSupervisedRecordedOutcome(command: SalesCommand, action: SecurityAction, scope: BranchScope,
    targetId: DomainId, revision: Revision, custodyActorId: DomainId, amount: Money): DomainId {
    requireSales(action == SecurityAction.COLLECTION_RESOLVE || action == SecurityAction.REFUND_RESOLVE, SalesRule.PERMISSION_DENIED)
    val expected = salesAuthorizationRequirement(action, scope, targetId, revision, command.operationId,
        command.payloadDigest, command.reasonCode, custodyActorId, amount)
    requireSales(command.authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE &&
        command.authorization.isBoundTo(expected), SalesRule.PERMISSION_DENIED)
    return command.authorization.actorId
}

internal fun supervisedResolutionLink(source: SupervisedRecordedObservation, command: SalesCommand,
    intentOperationId: OperationId, observationOperationId: OperationId, priorStatus: AttemptStatus,
    custodyActorId: DomainId, unknown: Boolean): RetailResolutionObservationLink? {
    requireSales(observationOperationId != intentOperationId && observationOperationId != command.operationId, SalesRule.INVALID_EVIDENCE)
    if (priorStatus == AttemptStatus.AWAITING_ATTESTATION) {
        requireSales(source.recorderId == custodyActorId && source.originalUnknownOperationId == null &&
            source.originalUnknownBodyDigest == null, SalesRule.INVALID_EVIDENCE)
        return null // Original evidence belongs to the original physical actor, never a new supervisor.
    }
    requireSales(priorStatus == AttemptStatus.OUTCOME_UNKNOWN && !unknown && source.originalUnknownOperationId != null &&
        source.originalUnknownBodyDigest != null, SalesRule.INVALID_EVIDENCE)
    val original = checkNotNull(source.originalUnknownOperationId)
    requireSales(setOf(intentOperationId, original, observationOperationId, command.operationId).size == 4, SalesRule.INVALID_EVIDENCE)
    return RetailResolutionObservationLink(original, checkNotNull(source.originalUnknownBodyDigest), observationOperationId,
        source.recorderId, command.operationId, command.authorization.actorId)
}

/**
 * Closed literal history checks for the normalized route. These never create authorization,
 * accept a command, consult today's prices, or return a proposed transition. The adapter must
 * independently prove the actual retained sources and complete same-sink inventory effects.
 */
public object NormalizedRetailHistory {
    public fun requireCreated(after: OwnedOrder, actorId: DomainId) {
        requireSales(after.stockRequirements.normalized && after.priceSourceReference != null, SalesRule.STOCK_PROOF_MISMATCH)
        val literal = OwnedOrder(after.id, after.scope, actorId, actorId, Revision(0), after.quote, after.stockRequirements,
            priceSourceReference = after.priceSourceReference, returnPolicySourceReference = after.returnPolicySourceReference)
        requireSales(SalesSnapshotCodec.encodeOrder(after) == SalesSnapshotCodec.encodeOrder(literal) &&
            after.quote.snapshotId == after.id && after.quote.revision == Revision(0), SalesRule.INVALID_EVIDENCE)
    }
    public fun requireReplaced(before: OwnedOrder, after: OwnedOrder, actorId: DomainId) {
        requireNormalizedEditable(before)
        requireSales(before.ownerId == actorId && before.handoff == null && after.stockRequirements.normalized &&
            after.priceSourceReference != null && after.priceSourceReference != before.priceSourceReference &&
            after.quote.snapshotId == before.id && after.quote.revision == before.quote.revision.next(), SalesRule.INVALID_EVIDENCE)
        after.stockRequirements.validate(before.scope, after.quote)
        val literal = before.update(quote = after.quote, stockRequirements = after.stockRequirements,
            priceSourceReference = after.priceSourceReference, returnPolicySourceReference = after.returnPolicySourceReference)
        requireSales(SalesSnapshotCodec.encodeOrder(after) == SalesSnapshotCodec.encodeOrder(literal), SalesRule.INVALID_EVIDENCE)
    }
    public fun requireHandoffOffered(before: OwnedOrder, after: OwnedOrder, actorId: DomainId,
        handoffId: DomainId, recipientId: DomainId, evidence: CashierEvidence) {
        requireNormalizedEditable(before)
        requireSales(before.ownerId == actorId && before.handoff == null && recipientId != actorId, SalesRule.HANDOFF_CONFLICT)
        evidence.requireActor(actorId)
        val offer = HandoffOffer(handoffId, before.id, actorId, recipientId, before.revision.next(), evidence)
        requireSales(SalesSnapshotCodec.encodeOrder(after) == SalesSnapshotCodec.encodeOrder(before.update(handoff = offer)), SalesRule.INVALID_EVIDENCE)
    }
    public fun requireHandoffCancelled(before: OwnedOrder, after: OwnedOrder, actorId: DomainId, handoffId: DomainId) {
        requireNormalizedEditable(before)
        val offer = before.handoff
        requireSales(before.ownerId == actorId && offer != null && offer.id == handoffId && offer.orderId == before.id &&
            offer.previousOwnerId == actorId && offer.offeredAtRevision == before.revision, SalesRule.HANDOFF_CONFLICT)
        requireSales(SalesSnapshotCodec.encodeOrder(after) == SalesSnapshotCodec.encodeOrder(before.update(handoff = null)), SalesRule.INVALID_EVIDENCE)
    }
    public fun requireHandoffAccepted(before: OwnedOrder, after: OwnedOrder, actorId: DomainId,
        handoffId: DomainId, evidence: CashierEvidence) {
        requireNormalizedEditable(before)
        val offer = before.handoff
        requireSales(offer != null && offer.id == handoffId && offer.orderId == before.id && offer.intendedRecipientId == actorId &&
            actorId != before.ownerId && offer.previousOwnerId == before.ownerId && offer.offeredAtRevision == before.revision,
            SalesRule.HANDOFF_CONFLICT)
        evidence.requireActor(actorId)
        requireSales(SalesSnapshotCodec.encodeOrder(after) == SalesSnapshotCodec.encodeOrder(before.update(ownerId = actorId, handoff = null)),
            SalesRule.INVALID_EVIDENCE)
    }
    /** Literal business preservation only; the adapter must prove original recipient authority. */
    public fun requireReassigned(before: OwnedOrder, after: OwnedOrder, managerId: DomainId,
        previousOwnerId: DomainId, recipientId: DomainId, reasonCode: String, evidence: CashierEvidence) {
        requireNormalizedEditable(before)
        requireSales(before.ownerId == previousOwnerId && before.handoff == null && recipientId != previousOwnerId,
            SalesRule.HANDOFF_CONFLICT)
        requireReasonCode(reasonCode)
        evidence.requireActor(managerId)
        requireSales(SalesSnapshotCodec.encodeOrder(after) == SalesSnapshotCodec.encodeOrder(before.update(ownerId = recipientId)),
            SalesRule.INVALID_EVIDENCE)
    }
    private fun requireNormalizedEditable(order: OwnedOrder) {
        requireSales(order.stockRequirements.normalized && order.priceSourceReference != null && order.collectionId == null &&
            order.paidSaleId == null, SalesRule.INVALID_EVIDENCE)
    }
    public fun requireBegun(before: OwnedOrder, cash: CashSession, afterOrder: OwnedOrder, after: CollectionLock,
        actorId: DomainId, reservation: com.ukkera.pos.inventory.InventoryReservationHeader) {
        requireSales(before.ownerId == actorId && before.handoff == null && before.collectionId == null && before.paidSaleId == null &&
            before.stockRequirements.normalized && cash.scope == before.scope && cash.custodianId == actorId &&
            cash.status == CashSessionStatus.OPEN, SalesRule.INVALID_EVIDENCE)
        val order = before.update(collectionId = after.id)
        val collection = CollectionLock(after.id, before.scope, before.id, order.revision, before.originalCreatorId, actorId,
            cash.id, before.quote, before.stockRequirements, reservation.id, Revision(0), priceSourceReference = before.priceSourceReference, returnPolicySourceReference = before.returnPolicySourceReference)
        requireSales(SalesSnapshotCodec.encodeOrder(afterOrder) == SalesSnapshotCodec.encodeOrder(order) &&
            SalesSnapshotCodec.encodeCollection(after) == SalesSnapshotCodec.encodeCollection(collection), SalesRule.INVALID_EVIDENCE)
    }
    public fun requireFinalized(beforeOrder: OwnedOrder, before: CollectionLock, afterOrder: OwnedOrder, after: CollectionLock,
        sale: PaidSale, entitlement: ReceiptEntitlement, actorId: DomainId, issueId: DomainId) {
        requireSales(before.stockRequirements.normalized && beforeOrder.stockRequirements.sameSource(before.stockRequirements) &&
            beforeOrder.ownerId == actorId && before.collectorId == actorId && beforeOrder.collectionId == before.id &&
            beforeOrder.id == before.orderId && before.scope == beforeOrder.scope && before.lockedOrderRevision == beforeOrder.revision &&
            before.priceSourceReference == beforeOrder.priceSourceReference && before.returnPolicySourceReference == beforeOrder.returnPolicySourceReference &&
            sameQuote(beforeOrder.quote, before.quote) &&
            beforeOrder.handoff == null && beforeOrder.paidSaleId == null && before.saleId == null && !before.unresolved &&
            before.collected == before.quote.payable, SalesRule.INVALID_EVIDENCE)
        val payments = before.attempts.mapNotNull { it.payment }
        val literal = PaidSale(sale.id, beforeOrder.scope, beforeOrder.id, beforeOrder.originalCreatorId, actorId,
            before.cashSessionId, before.quote, issueId, payments, allocateOriginalTenders(before.quote, payments), before.priceSourceReference, before.returnPolicySourceReference)
        requireSales(SalesSnapshotCodec.encodeOrder(afterOrder) == SalesSnapshotCodec.encodeOrder(beforeOrder.update(paidSaleId = sale.id)) &&
            SalesSnapshotCodec.encodeCollection(after) == SalesSnapshotCodec.encodeCollection(before.update(saleId = sale.id)) &&
            SalesSnapshotCodec.encodePaidSale(sale) == SalesSnapshotCodec.encodePaidSale(literal) &&
            SalesSnapshotCodec.encodeReceiptEntitlement(entitlement) == SalesSnapshotCodec.encodeReceiptEntitlement(initialEntitlement(literal)),
            SalesRule.INVALID_EVIDENCE)
    }
}
