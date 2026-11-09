package com.ukkera.pos.sales

import com.ukkera.pos.pricing.PriceComponentKind
import com.ukkera.pos.pricing.PricingSnapshot
import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.pricing.StoredReturnCursor
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.AllocationWeight
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import com.ukkera.pos.types.allocateByLargestRemainder
import kotlinx.serialization.Serializable

/** Exact original food quote batch, not a recalculation or a retail stock capability. */
public class FoodPriceBatch(
    public val id: DomainId,
    public val sourceDigest: PayloadDigest,
    public val quote: PricingSnapshot,
)

/**
 * Each quoted portion appears once. Batches keep their original rounding/tax profile and
 * source digest; joining them NEVER recalculates their posted amounts. This first food
 * financial profile deliberately admits only the preparation profile's item net/tax.
 */
public class FoodFinancialQuote(batches: List<FoodPriceBatch>) {
    public val batches: List<FoodPriceBatch> = frozenList(batches, 32)
    public val payable: Money get() = batches.fold(Money(Currency.EGP, 0)) { total, batch -> total + batch.quote.payable }
    public val portionIds: List<DomainId> get() = batches.flatMap { it.quote.lines }.map { it.portionId }

    init {
        requireSales(this.batches.isNotEmpty() && this.batches.map { it.id }.distinct().size == this.batches.size,
            SalesRule.INVALID_EVIDENCE)
        requireSales(portionIds.size in 1..32 && portionIds.distinct().size == portionIds.size, SalesRule.PAYLOAD_TOO_LARGE)
        for (batch in this.batches) {
            val quote = batch.quote
            requireSales(batch.id == quote.snapshotId && quote.revision == Revision(0) && quote.currency == Currency.EGP &&
                quote.lines.size in 1..8 && quote.charges.isEmpty() && quote.discounts.isEmpty() &&
                quote.components.isNotEmpty() && quote.components.size <= 16 && quote.components.all {
                    it.portionId in quote.lines.map { line -> line.portionId } && it.chargeId == null && it.posted.minor >= 0
                }, SalesRule.UNSUPPORTED_STOCK_TIMING)
            requireSales(quote.components.fold(Money(Currency.EGP, 0)) { sum, component -> sum + component.posted } == quote.payable,
                SalesRule.INVALID_AMOUNT)
        }
        payable.requireNonnegative() // Checked aggregate overflow before any collection intent.
    }

    public fun batchFor(portionId: DomainId): FoodPriceBatch = batches.single { batch -> batch.quote.lines.any { it.portionId == portionId } }
    public fun originalCursor(portionId: DomainId): StoredReturnCursor = batchFor(portionId).quote.openReturnCursor(portionId)
    public fun componentAmounts(): Map<String, Money> = batches.flatMap { batch ->
        batch.quote.components.map { foodComponentKey(batch.id, it.key) to it.posted }
    }.toMap()
}

public fun foodComponentKey(batchId: DomainId, originalKey: String): String = "${batchId.value}/$originalKey"

/**
 * Informational whole-check projection from original posted prices. The trusted reader closes
 * every batch, portion and original producer first; this neither reprices nor authorizes
 * collection, cancellation, a waiver, a refund or any physical effect.
 */
public object FoodCurrentUnpaidPayable {
    /** Existing V2 semantics: only genuinely never-sent work has a known amount. */
    public fun project(
        originalQuotes: List<PricingSnapshot>,
        portions: List<FoodPreparationState>,
        originalQuotedTotal: Money,
        financialCollectionId: DomainId?,
        closedByOperationId: OperationId?,
    ): Money? = project(originalQuotes, portions, originalQuotedTotal, financialCollectionId,
        closedByOperationId, includePrepared = false)

    /** Open, uncollected ordinary work keeps its original charge after kitchen progress
     * or dine-in service. This is not a collection lock or a cancellation/waiver policy. */
    public fun projectIncludingPrepared(
        originalQuotes: List<PricingSnapshot>,
        portions: List<FoodPreparationState>,
        originalQuotedTotal: Money,
        financialCollectionId: DomainId?,
        closedByOperationId: OperationId?,
    ): Money? = project(originalQuotes, portions, originalQuotedTotal, financialCollectionId,
        closedByOperationId, includePrepared = true)

    private fun project(
        originalQuotes: List<PricingSnapshot>,
        portions: List<FoodPreparationState>,
        originalQuotedTotal: Money,
        financialCollectionId: DomainId?,
        closedByOperationId: OperationId?,
        includePrepared: Boolean,
    ): Money? {
        requireSales(originalQuotes.size <= 32 && portions.size <= 32, SalesRule.PAYLOAD_TOO_LARGE)
        requireSales(originalQuotedTotal.currency == Currency.EGP && originalQuotedTotal.minor >= 0,
            SalesRule.INVALID_AMOUNT)
        requireSales(originalQuotes.map { it.snapshotId }.distinct().size == originalQuotes.size &&
            portions.map { it.portionId }.distinct().size == portions.size, SalesRule.INVALID_EVIDENCE)
        val quotedIds = originalQuotes.flatMap { it.lines }.map { it.portionId }
        requireSales(quotedIds.size == portions.size && quotedIds.distinct().size == quotedIds.size &&
            quotedIds.toSet() == portions.map { it.portionId }.toSet(), SalesRule.INVALID_EVIDENCE)
        var total = originalQuotedTotal.zero()
        for (quote in originalQuotes) {
            requireSales(quote.currency == Currency.EGP && quote.payable.currency == Currency.EGP &&
                quote.payable.minor >= 0 && quote.revision == Revision(0) && quote.lines.size in 1..8,
                SalesRule.INVALID_EVIDENCE)
            total += quote.payable
        }
        requireSales(total == originalQuotedTotal, SalesRule.INVALID_AMOUNT)
        if (financialCollectionId != null || closedByOperationId != null || portions.any {
                if (includePrepared) !isOrdinaryUnpaid(it) else !isNeverSent(it)
            }) return null
        // The existing FOOD profile has only portion-owned item postings. A different charge
        // profile needs its own current obligation policy rather than an inferred allocation.
        if (originalQuotes.any { quote -> quote.charges.isNotEmpty() || quote.discounts.isNotEmpty() ||
            quote.components.isEmpty() || quote.components.size > 16 || quote.components.any {
                it.chargeId != null || it.portionId !in quote.lines.map { line -> line.portionId } ||
                    it.kind !in setOf(PriceComponentKind.ITEM_NET, PriceComponentKind.ITEM_TAX)
            }
        }) return null
        val activeIds = portions.filter { it.status != FoodPreparationStatus.CANCELLED_BEFORE_START }
            .map { it.portionId }.toSet()
        var current = originalQuotedTotal.zero()
        for (quote in originalQuotes) {
            var posted = originalQuotedTotal.zero()
            for (component in quote.components) {
                requireSales(component.posted.currency == Currency.EGP && component.posted.minor >= 0,
                    SalesRule.INVALID_AMOUNT)
                posted += component.posted
                if (component.portionId in activeIds) current += component.posted
            }
            requireSales(posted == quote.payable, SalesRule.INVALID_AMOUNT)
        }
        requireSales(current.minor in 0L..originalQuotedTotal.minor, SalesRule.INVALID_AMOUNT)
        return current
    }

    /** Only preparation eligibility, never proof of unpaid state, source custody or authority.
     * Sent cancellation/waste and paid pickup require their separate financial decisions. */
    public fun isOrdinaryUnpaid(preparation: FoodPreparationState): Boolean = isNeverSent(preparation) ||
        preparation.status in setOf(FoodPreparationStatus.QUEUED, FoodPreparationStatus.PREPARING, FoodPreparationStatus.READY) &&
            preparation.cancellationReasonId == null && preparation.cancellationRequestedAt == null &&
            preparation.fulfillment != FoodFulfillmentStatus.PICKED_UP && preparation.pickedUpAt == null &&
            preparation.reservationId != null && preparation.issueId != null &&
            preparation.issueRevision?.value?.let { it >= 1L } == true && preparation.sentAt != null &&
            preparation.tasks.isNotEmpty() && preparation.tasks.all {
                it.status in setOf(FoodStationStatus.QUEUED, FoodStationStatus.STARTED, FoodStationStatus.READY) &&
                    it.cancellationConfirmationId == null
            }

    /** Preparation evidence only; unpaid/open state and whole-source custody are separate. */
    public fun isNeverSent(preparation: FoodPreparationState): Boolean =
        preparation.status in setOf(FoodPreparationStatus.NOT_SENT, FoodPreparationStatus.CANCELLED_BEFORE_START) &&
            preparation.fulfillment == FoodFulfillmentStatus.NOT_HANDED_OVER && preparation.tasks.isEmpty() &&
            preparation.reservationId == null && preparation.issueId == null && preparation.issueRevision == null &&
            preparation.sentAt == null && preparation.servedAt == null && preparation.pickedUpAt == null &&
            (if (preparation.status == FoodPreparationStatus.NOT_SENT)
                preparation.cancellationReasonId == null && preparation.cancellationRequestedAt == null
            else preparation.cancellationReasonId != null && preparation.cancellationRequestedAt != null)
}

@Serializable
public enum class FoodStockOrigin { ISSUED_BEFORE_COLLECTION, RESERVED_FOR_COLLECTION }

/** Financial link only. The actual reservation/issue comes from the normalized inventory repository. */
@Serializable
public data class FoodFinancialPortion(
    public val portionId: DomainId,
    public val batchId: DomainId,
    public val recipeSnapshotId: DomainId,
    public val quantity: Quantity,
    public val reservationId: DomainId,
    public val stockOrigin: FoodStockOrigin,
    public val priorIssueId: DomainId? = null,
) {
    init { require((stockOrigin == FoodStockOrigin.ISSUED_BEFORE_COLLECTION) == (priorIssueId != null) && quantity.scaled > 0) }
}

/** Immutable acceptance fact. Opening collection/payment custody is never rewritten. */
@Serializable
public data class FoodCollectionResponsibilityV1(
    public val handoffId: DomainId,
    public val acceptanceOperationId: OperationId,
    public val fromActorId: DomainId,
    public val recipientId: DomainId,
    public val offeredAt: UtcInstant,
    public val acceptedAt: UtcInstant,
    public val priorCollectionRevision: Revision,
    /** Number of already resolved attempts at the acceptance boundary. */
    public val priorAttemptCount: Int,
)

public enum class FoodCollectionStatus { LOCKED_UNPAID, PART_PAID, OUTCOME_UNKNOWN, COVERED, FINALIZED_PAID, CANCELLED_REFUND_OBLIGATION }

/** Genuine food lock. It is intentionally NOT CollectionLock, OwnedOrder or RetailStockRequirements. */
public class FoodCollection internal constructor(
    public val id: DomainId,
    public val scope: BranchScope,
    public val orderId: DomainId,
    public val lockedCommercialRevision: Revision,
    public val originalCreatorId: DomainId,
    public val collectorId: DomainId,
    public val cashSessionId: DomainId,
    public val quote: FoodFinancialQuote,
    portions: List<FoodFinancialPortion>,
    public val revision: Revision,
    attempts: List<PaymentAttempt> = emptyList(),
    public val saleId: DomainId? = null,
    public val cancellationRefundId: DomainId? = null,
    responsibility: List<FoodCollectionResponsibilityV1> = emptyList(),
) {
    public val portions: List<FoodFinancialPortion> = frozenList(portions, 32)
    public val attempts: List<PaymentAttempt> = frozenList(attempts, 64)
    /** Finite literal profile, not a lifetime policy: overflow refuses before any effects. No pruning. */
    public val responsibility: List<FoodCollectionResponsibilityV1> = frozenList(responsibility, FoodCollections.MAX_RESPONSIBILITY_TRANSITIONS)
    public val currentOwnerId: DomainId get() = responsibility.lastOrNull()?.recipientId ?: collectorId
    public val collected: Money get() = attempts.fold(quote.payable.zero()) { sum, attempt -> sum + (attempt.payment?.amount ?: sum.zero()) }
    public val remaining: Money get() = if (cancellationRefundId != null) quote.payable.zero() else quote.payable - collected
    public val unresolved: Boolean get() = attempts.any { it.status.isUnresolved() }
    public val status: FoodCollectionStatus get() = when {
        cancellationRefundId != null -> FoodCollectionStatus.CANCELLED_REFUND_OBLIGATION
        saleId != null -> FoodCollectionStatus.FINALIZED_PAID
        unresolved -> FoodCollectionStatus.OUTCOME_UNKNOWN
        collected == quote.payable -> FoodCollectionStatus.COVERED
        collected.minor > 0L -> FoodCollectionStatus.PART_PAID
        else -> FoodCollectionStatus.LOCKED_UNPAID
    }
    internal fun next(attempts: List<PaymentAttempt> = this.attempts, saleId: DomainId? = this.saleId,
        cancellationRefundId: DomainId? = this.cancellationRefundId,
        responsibility: List<FoodCollectionResponsibilityV1> = this.responsibility): FoodCollection = FoodCollection(id, scope, orderId,
        lockedCommercialRevision, originalCreatorId, collectorId, cashSessionId, quote, portions, revision.next(), attempts,
        saleId, cancellationRefundId, responsibility)
}

/** Immutable financial document; no stock issue, kitchen completion, printing or fiscal acceptance is implied. */
public class FoodPaidSale internal constructor(
    public val id: DomainId,
    public val collection: FoodCollection,
    public val finalizationOperationId: OperationId,
    public val finalizedAt: UtcInstant,
    tenders: List<OriginalComponentTenderAllocation>,
) {
    public val scope: BranchScope get() = collection.scope
    public val orderId: DomainId get() = collection.orderId
    public val quote: FoodFinancialQuote get() = collection.quote
    public val payments: List<PaymentRecord> get() = collection.attempts.mapNotNull { it.payment }
    public val originalTenderAllocations: List<OriginalComponentTenderAllocation> = frozenList(tenders, 64)
}

/** Remaining original FOOD quantity and component/tender value. No retail PaidSale is manufactured. */
public class FoodReceiptEntitlement internal constructor(
    public val sale: FoodPaidSale,
    public val revision: Revision,
    cursors: List<StoredReturnCursor>,
    remainingTenders: List<OriginalComponentTenderAllocation>,
) {
    public val cursors: List<StoredReturnCursor> = frozenList(cursors, 32)
    public val remainingTenders: List<OriginalComponentTenderAllocation> = frozenList(remainingTenders, 64)
}

/**
 * Narrow original-tender obligation from an authorized, wholly UNSENT cancellation.
 * originalQuotedTotal != refund amount when only part of the cancelled check was collected.
 * This is neither an unrestricted credit balance nor a discretionary served-food remedy.
 */
public class FoodRefundObligation internal constructor(
    public val id: DomainId,
    public val originalCollection: FoodCollection,
    public val cancellationOperationId: OperationId,
    public val cancellationReasonId: DomainId,
    public val acceptingActorId: DomainId,
    public val acceptedAt: UtcInstant,
    public val revision: Revision,
    attempts: List<RefundAttempt> = emptyList(),
) {
    public val scope: BranchScope get() = originalCollection.scope
    public val orderId: DomainId get() = originalCollection.orderId
    public val saleId: DomainId? get() = originalCollection.saleId
    public val originalQuotedTotal: Money get() = originalCollection.quote.payable
    public val amount: Money get() = originalCollection.collected
    public val obligations: List<ReturnTenderObligation> get() = originalCollection.attempts.mapNotNull { it.payment }.map {
        ReturnTenderObligation(it.attemptId, it.tender, it.amount)
    }
    public val attempts: List<RefundAttempt> = frozenList(attempts, 64)
    public val refunded: Money get() = attempts.fold(amount.zero()) { sum, attempt -> sum + (attempt.refund?.amount ?: sum.zero()) }
    public val due: Money get() = amount - refunded
    public val unresolved: Boolean get() = attempts.any { it.status.isUnresolved() }
    public val status: RefundStatus get() = when {
        unresolved -> RefundStatus.OUTCOME_UNKNOWN
        due.minor == 0L -> RefundStatus.SETTLED
        refunded.minor > 0L -> RefundStatus.PART_REFUNDED
        else -> RefundStatus.DUE
    }
    internal fun next(attempts: List<RefundAttempt>): FoodRefundObligation = FoodRefundObligation(id, originalCollection,
        cancellationOperationId, cancellationReasonId, acceptingActorId, acceptedAt, revision.next(), attempts)
}

public class FoodCollectionCashChange internal constructor(
    public val collection: FoodCollection, public val session: CashSession,
    public val payment: PaymentRecord? = null, public val movement: CashMovement? = null,
)
public class FoodRefundCashChange internal constructor(
    public val refund: FoodRefundObligation, public val session: CashSession,
    public val record: RefundRecord? = null, public val movement: CashMovement? = null,
)
public class FoodFinalization internal constructor(
    public val collection: FoodCollection, public val sale: FoodPaidSale, public val entitlement: FoodReceiptEntitlement,
)
public class FoodUnsentCancellation internal constructor(
    public val collection: FoodCollection, public val refund: FoodRefundObligation,
    public val entitlement: FoodReceiptEntitlement?,
)

/**
 * Pure financial proposals. The trusted adapter MUST read the genuine food sources and commit
 * complete stock/CAS/unique/obligation/fact companions in the same authoritative transaction.
 * No method below issues stock, accepts a caller capacity proof, or claims a payment committed.
 */
public object FoodCollections {
    /** This source profile carries at most 64 accepted transfers; larger history needs a new qualified profile. */
    public const val MAX_RESPONSIBILITY_TRANSITIONS: Int = 64

    /**
     * Ordinary recipient acceptance only. The adapter must authenticate the exact pending offer,
     * current receiving permission and order/collection CAS, then commit both current owners and
     * immutable acceptance together. This neither moves cash nor resolves an in-flight payment.
     */
    public fun acceptSettledHandoff(command: SalesCommand, collection: FoodCollection,
        expectedCollectionRevision: Revision, expectedOrderRevision: Revision, handoffId: DomainId,
        fromActorId: DomainId, recipientId: DomainId, offeredAt: UtcInstant): FoodCollection {
        val actor = authorize(command, "order.handoff", collection.scope, collection.orderId, expectedOrderRevision)
        requireRevision(collection.revision, expectedCollectionRevision)
        requireSales(actor == recipientId && fromActorId == collection.currentOwnerId && fromActorId != recipientId,
            SalesRule.HANDOFF_CONFLICT)
        requireSales(!collection.unresolved, SalesRule.PAYMENT_UNRESOLVED)
        requireSales(offeredAt <= UtcInstant(command.recordedAt), SalesRule.INVALID_EVIDENCE)
        requireSales(collection.responsibility.size < MAX_RESPONSIBILITY_TRANSITIONS, SalesRule.PAYLOAD_TOO_LARGE)
        requireSales(collection.responsibility.none { it.handoffId == handoffId || it.acceptanceOperationId == command.operationId },
            SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        return collection.next(responsibility = collection.responsibility + FoodCollectionResponsibilityV1(handoffId,
            command.operationId, fromActorId, recipientId, offeredAt, UtcInstant(command.recordedAt), collection.revision,
            collection.attempts.size)).also(::validateFoodCollection)
    }

    public fun begin(command: SalesCommand, scope: BranchScope, orderId: DomainId, orderRevision: Revision,
        originalCreatorId: DomainId, ownerId: DomainId, session: CashSession, expectedSessionRevision: Revision,
        collectionId: DomainId, quote: FoodFinancialQuote, portions: List<FoodFinancialPortion>): FoodCollection {
        val actor = authorize(command, "payment.begin", scope, orderId, orderRevision, quote.payable)
        requireSales(actor == ownerId && session.scope == scope, SalesRule.OWNER_REQUIRED)
        requireRevision(session.revision, expectedSessionRevision); requireSessionOwner(session, actor)
        return FoodCollection(collectionId, scope, orderId, orderRevision, originalCreatorId, actor, session.id, quote,
            portions, Revision(0)).also(::validateFoodCollection)
    }

    public fun startAttempt(command: SalesCommand, collection: FoodCollection, expectedRevision: Revision,
        session: CashSession, expectedSessionRevision: Revision, attemptId: DomainId, tender: Tender,
        amount: Money, payerReference: String?): FoodCollectionCashChange {
        foodCollectionActor(command, "payment.record", collection, expectedRevision, session, expectedSessionRevision, amount)
        return foodStartedAttemptValue(collection, session, command.operationId, collection.currentOwnerId,
            attemptId, tender, amount, payerReference)
    }

    /** Apply one already retained immutable fact; recordedObservationOperationId is NOT this continuation's identity. */
    public fun applyObservation(command: SalesCommand, collection: FoodCollection, session: CashSession,
        attemptId: DomainId, recordedObservationOperationId: OperationId, outcome: CollectionOutcome): FoodCollectionCashChange {
        requireSales(recordedObservationOperationId != command.operationId, SalesRule.INVALID_EVIDENCE)
        val attempt = collection.attempts.singleOrNull { it.id == attemptId } ?: throw SalesRuleViolation(SalesRule.INVALID_STATE)
        val actor = foodCollectionActor(command, if (attempt.status == AttemptStatus.OUTCOME_UNKNOWN) "payment.resolve" else "payment.record",
            collection, collection.revision, session, session.revision, attempt.amount)
        return foodAppliedObservationValue(collection, session, attemptId, recordedObservationOperationId,
            command.operationId, actor, outcome)
    }

    /**
     * Independently authorized current COLLECTION_RESOLVE over a retained FOOD observation.
     * The adapter proves its whole original, fixed UNKNOWN/resolution IDs, digest and audit.
     * Original collector/session stay unchanged; this never starts/retries or touches stock.
     */
    public fun applySupervisedObservation(command: SalesCommand, collection: FoodCollection, session: CashSession,
        attemptId: DomainId, recordedObservationOperationId: OperationId, recordingActorId: DomainId,
        originalUnknownOperationId: OperationId?, originalUnknownBodyDigest: PayloadDigest?,
        outcome: CollectionOutcome): FoodCollectionCashChange {
        val attempt = collection.attempts.singleOrNull { it.id == attemptId } ?: throw SalesRuleViolation(SalesRule.INVALID_STATE)
        requireSales(attempt.tender == Tender.Cash && outcome !is CollectionOutcome.ExternalAttested, SalesRule.TENDER_MISMATCH)
        requireSales(collection.currentOwnerId == attempt.originalActorId && collection.scope == session.scope &&
            attempt.originalCashSessionId == session.id, SalesRule.OWNER_REQUIRED)
        requireSessionOwner(session, attempt.originalActorId)
        val accountingActor = authorizeSupervisedRecordedOutcome(command, SecurityAction.COLLECTION_RESOLVE,
            collection.scope, collection.id, collection.revision, attempt.originalActorId, attempt.amount)
        val link = foodRecordedResolutionLink(attempt, recordedObservationOperationId, recordingActorId,
            command.operationId, accountingActor, originalUnknownOperationId, originalUnknownBodyDigest,
            outcome is CollectionOutcome.Unknown)
        return foodAppliedObservationValue(collection, session, attemptId, recordedObservationOperationId,
            command.operationId, attempt.originalActorId, outcome, recordingActorId, link)
    }

    /** No inventory issue dependency: prior issues remain prior; protected unsent reservations remain held. */
    public fun finalize(command: SalesCommand, collection: FoodCollection, expectedRevision: Revision, saleId: DomainId): FoodFinalization {
        val actor = authorize(command, if (collection.quote.payable.minor == 0L) "sale.finalizeZero" else "sale.finalize",
            collection.scope, collection.id, expectedRevision, collection.quote.payable)
        requireRevision(collection.revision, expectedRevision)
        requireSales(actor == collection.currentOwnerId, SalesRule.OWNER_REQUIRED)
        requireSales(collection.saleId == null && collection.cancellationRefundId == null, SalesRule.IMMUTABLE_RECORD)
        requireSales(!collection.unresolved && collection.collected == collection.quote.payable, SalesRule.INSUFFICIENT_COVERAGE)
        val sale = FoodPaidSale(saleId, collection, command.operationId, UtcInstant(command.recordedAt), allocateFoodTenders(collection))
        val entitlement = FoodReceiptEntitlement(sale, Revision(0), collection.quote.portionIds.map(collection.quote::originalCursor), sale.originalTenderAllocations)
        return FoodFinalization(collection.next(saleId = saleId), sale, entitlement)
    }

    /**
     * Explicitly authorized whole-check cancellation before ANY portion is sent/supplied.
     * Actual original tenders, not the unpaid quoted balance, establish the refund liability.
     * The adapter must release the exact reservations with this preserved obligation atomically.
     */
    public fun cancelWhollyUnsent(command: SalesCommand, collection: FoodCollection, expectedRevision: Revision,
        portions: List<FoodPreparationState>, refundId: DomainId, reasonId: DomainId,
        entitlement: FoodReceiptEntitlement?): FoodUnsentCancellation {
        val actor = authorize(command, "payment.resolve", collection.scope, collection.id, expectedRevision, collection.collected)
        requireRevision(collection.revision, expectedRevision)
        requireSales(actor == collection.currentOwnerId, SalesRule.OWNER_REQUIRED)
        requireSales(collection.cancellationRefundId == null && !collection.unresolved, SalesRule.PAYMENT_UNRESOLVED)
        requireSales(portions.size == collection.portions.size && portions.map { it.portionId }.toSet() == collection.quote.portionIds.toSet() &&
            portions.all { it.status == FoodPreparationStatus.NOT_SENT && it.issueId == null && it.fulfillment == FoodFulfillmentStatus.NOT_HANDED_OVER } &&
            collection.portions.all { it.stockOrigin == FoodStockOrigin.RESERVED_FOR_COLLECTION }, SalesRule.INVALID_STATE)
        requireSales((entitlement == null) == (collection.saleId == null), SalesRule.INVALID_EVIDENCE)
        if (entitlement != null) requireSales(entitlement.sale.id == collection.saleId &&
            entitlement.sale.collection.id == collection.id && entitlement.revision == Revision(0) &&
            entitlement.cursors.all { it.consumedQuantity.scaled == 0L }, SalesRule.RETURN_ENTITLEMENT_EXHAUSTED)
        val refund = FoodRefundObligation(refundId, collection, command.operationId, reasonId, actor, UtcInstant(command.recordedAt), Revision(0))
        val consumed = entitlement?.let { before -> FoodReceiptEntitlement(before.sale, before.revision.next(),
            before.cursors.map { it.consume(it.remainingQuantity).remaining }, before.remainingTenders.map { row ->
                OriginalComponentTenderAllocation(row.componentKey, row.shares.map { it.copy(amount = it.amount.zero()) })
            }) }
        return FoodUnsentCancellation(collection.next(cancellationRefundId = refundId), refund, consumed)
    }
}

public object FoodRefunds {
    public fun start(command: SalesCommand, refund: FoodRefundObligation, expectedRevision: Revision, session: CashSession,
        expectedSessionRevision: Revision, attemptId: DomainId, originalPaymentId: DomainId, amount: Money): FoodRefundCashChange {
        val actor = foodRefundActor(command, "refund.record", refund, expectedRevision, session, expectedSessionRevision, amount)
        return foodRefundStartedValue(refund, session, command.operationId, actor, attemptId, originalPaymentId, amount)
    }

    public fun applyObservation(command: SalesCommand, refund: FoodRefundObligation, session: CashSession, attemptId: DomainId,
        recordedObservationOperationId: OperationId, outcome: RefundOutcome): FoodRefundCashChange {
        requireSales(recordedObservationOperationId != command.operationId, SalesRule.INVALID_EVIDENCE)
        val attempt = refund.attempts.singleOrNull { it.id == attemptId } ?: throw SalesRuleViolation(SalesRule.INVALID_STATE)
        val actor = foodRefundActor(command, if (attempt.status == AttemptStatus.OUTCOME_UNKNOWN) "refund.resolve" else "refund.record",
            refund, refund.revision, session, session.revision, attempt.amount)
        return foodRefundAppliedValue(refund, session, attemptId, recordedObservationOperationId,
            command.operationId, actor, outcome)
    }
}

/** Pure value shared by actual employee-authorized Start and literal retained comparison. */
internal fun foodStartedAttemptValue(collection: FoodCollection, session: CashSession, intentOperationId: OperationId,
    actorId: DomainId, attemptId: DomainId, tender: Tender, amount: Money, payerReference: String?): FoodCollectionCashChange {
    requireSales(collection.currentOwnerId == actorId && session.scope == collection.scope &&
    (collection.responsibility.isNotEmpty() || collection.cashSessionId == session.id), SalesRule.OWNER_REQUIRED)
    requireSessionOwner(session, actorId)
    amount.requirePositive(); sameCurrency(collection.quote.payable, amount); validatePayerReference(payerReference)
    requireSales(collection.saleId == null && collection.cancellationRefundId == null, SalesRule.IMMUTABLE_RECORD)
    requireSales(!collection.unresolved && amount.minor <= collection.remaining.minor, SalesRule.PAYMENT_UNRESOLVED)
    requireSales(collection.attempts.none { it.id == attemptId }, SalesRule.DUPLICATE_SOURCE_ALLOCATION)
    val attempt = PaymentAttempt(attemptId, intentOperationId, actorId, session.id,
        payerReference, tender, amount, AttemptStatus.AWAITING_ATTESTATION)
    return FoodCollectionCashChange(collection.next(attempts = collection.attempts + attempt),
        session.beginAttempt(attemptId, amount.zero()))
}

/** Pure exact retained observation accounting; does not authorize a command or take money. */
internal fun foodAppliedObservationValue(collection: FoodCollection, session: CashSession, attemptId: DomainId,
    recordedObservationOperationId: OperationId, applicationOperationId: OperationId, actor: DomainId,
    outcome: CollectionOutcome, recordingActorId: DomainId = actor,
    foodResolutionLink: FoodResolutionObservationLink? = null): FoodCollectionCashChange {
    requireSales(recordedObservationOperationId != applicationOperationId, SalesRule.INVALID_EVIDENCE)
    val attempt = collection.attempts.singleOrNull { it.id == attemptId } ?: throw SalesRuleViolation(SalesRule.INVALID_STATE)
    requireSales(collection.currentOwnerId == actor && session.scope == collection.scope &&
    (collection.responsibility.isNotEmpty() || collection.cashSessionId == session.id), SalesRule.OWNER_REQUIRED)
    requireSessionOwner(session, actor)
    requireSales(collection.saleId == null && collection.cancellationRefundId == null && attempt.status.isUnresolved() &&
        attempt.originalActorId == actor && attempt.originalCashSessionId == session.id && attemptId in session.pendingAttemptIds,
        SalesRule.PAYMENT_UNRESOLVED)
    requireSales(attempt.resolutionObservation == null && attempt.foodResolutionObservation == null &&
        attempt.payment == null, SalesRule.INVALID_EVIDENCE)
    if (foodResolutionLink == null) requireSales(recordingActorId == actor, SalesRule.INVALID_EVIDENCE)
    else {
        requireSales(attempt.status == AttemptStatus.OUTCOME_UNKNOWN && outcome !is CollectionOutcome.Unknown &&
            foodResolutionLink.resolutionOperationId == recordedObservationOperationId &&
            foodResolutionLink.resolutionRecorderId == recordingActorId &&
            foodResolutionLink.accountingOperationId == applicationOperationId, SalesRule.INVALID_EVIDENCE)
        requireStoredFoodResolutionLinkV1(foodResolutionLink, attempt.originalOperationId,
            recordedObservationOperationId, recordingActorId)
    }
    val evidence: CashierEvidence
    var payment: PaymentRecord? = null
    var movement: CashMovement? = null
    val status = when (outcome) {
        is CollectionOutcome.Unknown -> { evidence = outcome.evidence; AttemptStatus.OUTCOME_UNKNOWN }
        is CollectionOutcome.NotPaid -> { evidence = outcome.evidence; requireReason(outcome.reason); AttemptStatus.NOT_PAID }
        is CollectionOutcome.CashReceived -> {
            evidence = outcome.evidence
            requireSales(attempt.tender == Tender.Cash, SalesRule.TENDER_MISMATCH)
            sameCurrency(attempt.amount, outcome.received, outcome.change)
            outcome.received.requirePositive(); outcome.change.requireNonnegative()
            requireSales(outcome.received - outcome.change == attempt.amount, SalesRule.INVALID_AMOUNT)
            payment = foodPayment(collection, attempt, recordedObservationOperationId, evidence, outcome.received, outcome.change, null)
                .copy(foodResolutionObservation = foodResolutionLink)
            movement = CashMovement(attempt.id, applicationOperationId, session.id, actor, CashMovementKind.CUSTOMER_RECEIPT,
                attempt.amount, outcome.received, outcome.change, evidence, null)
            AttemptStatus.ATTESTED_PAID
        }
        is CollectionOutcome.ExternalAttested -> {
            evidence = outcome.evidence.attestation; validateExternal(attempt.tender, outcome.evidence)
            payment = foodPayment(collection, attempt, recordedObservationOperationId, evidence, null, null, outcome.evidence)
                .copy(foodResolutionObservation = foodResolutionLink)
            AttemptStatus.ATTESTED_PAID
        }
    }
    evidence.requireActor(recordingActorId)
    val updated = PaymentAttempt(attempt.id, attempt.originalOperationId, attempt.originalActorId, attempt.originalCashSessionId,
        attempt.payerReference, attempt.tender, attempt.amount, status, outcome, payment,
        foodResolutionObservation = foodResolutionLink)
    val after = collection.next(attempts = collection.attempts.map { if (it.id == attempt.id) updated else it })
    val cash = if (status == AttemptStatus.OUTCOME_UNKNOWN) session else session.finishRecordedAttempt(applicationOperationId, attempt.id,
        attempt.amount.zero(), movement, externalCollection = if (payment != null && attempt.tender is Tender.External) attempt.amount else attempt.amount.zero())
    return FoodCollectionCashChange(after, cash, payment, movement)
}

private fun foodCollectionActor(command: SalesCommand, action: String, collection: FoodCollection, revision: Revision,
    session: CashSession, sessionRevision: Revision, amount: Money): DomainId {
    requireRevision(collection.revision, revision); requireRevision(session.revision, sessionRevision)
    val actor = authorize(command, action, collection.scope, collection.id, revision, amount)
    requireSales(collection.currentOwnerId == actor && session.scope == collection.scope &&
        (collection.responsibility.isNotEmpty() || collection.cashSessionId == session.id), SalesRule.OWNER_REQUIRED)
    requireSessionOwner(session, actor)
    return actor
}
private fun foodRefundActor(command: SalesCommand, action: String, refund: FoodRefundObligation, revision: Revision,
    session: CashSession, sessionRevision: Revision, amount: Money): DomainId {
    requireRevision(refund.revision, revision); requireRevision(session.revision, sessionRevision)
    val actor = authorize(command, action, refund.scope, refund.id, revision, amount)
    requireSales(session.scope == refund.scope, SalesRule.WRONG_BRANCH); requireSessionOwner(session, actor)
    return actor
}
private fun foodPayment(collection: FoodCollection, attempt: PaymentAttempt, recordingId: OperationId, evidence: CashierEvidence,
    received: Money?, change: Money?, external: ExternalTenderEvidence?): PaymentRecord = PaymentRecord(attempt.id,
    attempt.originalOperationId, recordingId, collection.id, attempt.originalActorId, attempt.originalCashSessionId,
    attempt.payerReference, attempt.tender, attempt.amount, received, change, evidence, external)

internal fun allocateFoodTenders(collection: FoodCollection): List<OriginalComponentTenderAllocation> {
    val payments = collection.attempts.mapNotNull { it.payment }.sortedBy { it.attemptId.value }
    val remaining = payments.associate { it.attemptId.value to it.amount }.toMutableMap()
    val rows = collection.quote.componentAmounts().entries.sortedBy { it.key }.map { (key, amount) ->
        val shares = if (remaining.isEmpty()) { requireSales(amount.minor == 0L, SalesRule.INSUFFICIENT_COVERAGE); emptyList() }
        else allocateByLargestRemainder(amount, remaining.map { AllocationWeight(it.key, it.value.minor) }).map {
            val left = remaining.getValue(it.key) - it.amount
            requireSales(left.minor >= 0L, SalesRule.INVALID_AMOUNT); remaining[it.key] = left
            OriginalTenderShare(DomainId(it.key), it.amount)
        }
        OriginalComponentTenderAllocation(key, shares)
    }
    requireSales(remaining.values.all { it.minor == 0L }, SalesRule.INSUFFICIENT_COVERAGE)
    return rows
}

internal fun sameFoodQuote(first: FoodFinancialQuote, second: FoodFinancialQuote): Boolean = first.batches.size == second.batches.size &&
    first.batches.zip(second.batches).all { (a, b) -> a.id == b.id && a.sourceDigest == b.sourceDigest &&
        StoredPricingSnapshotCodec.encode(a.quote) == StoredPricingSnapshotCodec.encode(b.quote) }

internal fun validateFoodCollection(value: FoodCollection) {
    requireSales(value.portions.map { it.portionId } == value.quote.portionIds && value.portions.map { it.reservationId }.distinct().size == value.portions.size,
        SalesRule.STOCK_PROOF_MISMATCH)
    value.portions.forEach { portion ->
        val batch = value.quote.batchFor(portion.portionId)
        requireSales(batch.id == portion.batchId && batch.quote.lines.single { it.portionId == portion.portionId }.quantity == portion.quantity,
            SalesRule.INVALID_EVIDENCE)
    }
    requireSales(value.attempts.map { it.id }.distinct().size == value.attempts.size && value.attempts.count { it.status.isUnresolved() } <= 1,
        SalesRule.INVALID_STATE)
    var owner = value.collectorId
    var boundary = 0
    var priorRevision: Revision? = null
    var priorTime: UtcInstant? = null
    requireSales(value.responsibility.map { it.handoffId }.distinct().size == value.responsibility.size &&
        value.responsibility.map { it.acceptanceOperationId }.distinct().size == value.responsibility.size, SalesRule.INVALID_EVIDENCE)
    for (entry in value.responsibility) {
        requireSales(entry.fromActorId == owner && entry.recipientId != owner && entry.offeredAt <= entry.acceptedAt &&
            entry.priorAttemptCount in boundary..value.attempts.size && entry.priorCollectionRevision.value < value.revision.value &&
            (priorRevision == null || entry.priorCollectionRevision.value > priorRevision.value) &&
            (priorTime == null || entry.acceptedAt >= priorTime), SalesRule.INVALID_EVIDENCE)
        requireSales(value.attempts.take(entry.priorAttemptCount).none { it.status.isUnresolved() }, SalesRule.PAYMENT_UNRESOLVED)
        owner = entry.recipientId; boundary = entry.priorAttemptCount; priorRevision = entry.priorCollectionRevision; priorTime = entry.acceptedAt
    }
    value.attempts.forEachIndexed { index, attempt ->
        validateStoredPaymentAttempt(attempt)
        requireSales(attempt.resolutionObservation == null && attempt.payment?.resolutionObservation == null,
            SalesRule.INVALID_EVIDENCE)
        val accepted = value.responsibility.lastOrNull { it.priorAttemptCount <= index }
        val originalOwner = accepted?.recipientId ?: value.collectorId
        // Each attempt carries its OWN original session. Actual session ownership is checked
        // at start/application, not inferred from the immutable opening-session field.
        requireSales(attempt.originalActorId == originalOwner &&
            (accepted != null || attempt.originalCashSessionId == value.cashSessionId) &&
            (attempt.payment == null || attempt.payment.collectionId == value.id), SalesRule.INVALID_EVIDENCE)
        sameCurrency(value.quote.payable, attempt.amount)
    }
    requireSales(value.collected.minor <= value.quote.payable.minor && (value.saleId == null || !value.unresolved && value.collected == value.quote.payable) &&
        (value.cancellationRefundId == null || !value.unresolved), SalesRule.INVALID_AMOUNT)
}
