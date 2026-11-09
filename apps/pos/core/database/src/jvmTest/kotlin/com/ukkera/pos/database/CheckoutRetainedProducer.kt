package com.ukkera.pos.database

import com.ukkera.pos.inventory.CollectionExposure
import com.ukkera.pos.inventory.InventoryPosting
import com.ukkera.pos.inventory.InventoryPostingBatch
import com.ukkera.pos.inventory.InventoryPostingBinding
import com.ukkera.pos.inventory.InventoryPostingEffect
import com.ukkera.pos.inventory.InventoryScope
import com.ukkera.pos.inventory.InventorySnapshot
import com.ukkera.pos.inventory.InventorySnapshotBinding
import com.ukkera.pos.inventory.InventorySnapshotCodec
import com.ukkera.pos.inventory.InventorySnapshotKind
import com.ukkera.pos.inventory.ReservationStatus
import com.ukkera.pos.inventory.StockAllocation
import com.ukkera.pos.inventory.StockIssue
import com.ukkera.pos.inventory.StockReservation
import com.ukkera.pos.inventory.ValuationFact
import com.ukkera.pos.inventory.ValueIssuePurpose
import com.ukkera.pos.inventory.store.InventoryRetailCompanion
import com.ukkera.pos.inventory.store.InventoryRetailCompanionBinding
import com.ukkera.pos.inventory.store.InventoryRetailCompanionCodec
import com.ukkera.pos.inventory.store.InventoryRetailObservedFacts
import com.ukkera.pos.inventory.store.InventoryRetailRepository
import com.ukkera.pos.inventory.store.InventoryRetailStoreProfile
import com.ukkera.pos.pricing.ChargeRefundTreatment
import com.ukkera.pos.pricing.DiscountLimits
import com.ukkera.pos.pricing.PriceComponentKind
import com.ukkera.pos.pricing.PricingProfiles
import com.ukkera.pos.pricing.PricingSnapshot
import com.ukkera.pos.pricing.PromotionResolution
import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.pricing.TaxInclusion
import com.ukkera.pos.pricing.TaxPostingPolicy
import com.ukkera.pos.pricing.TaxSnapshot
import com.ukkera.pos.sales.AttemptStatus
import com.ukkera.pos.sales.CashSession
import com.ukkera.pos.sales.CashSessionStatus
import com.ukkera.pos.sales.CashTotals
import com.ukkera.pos.sales.CollectionLock
import com.ukkera.pos.sales.CollectionOutcome
import com.ukkera.pos.sales.CollectionStatus
import com.ukkera.pos.sales.OriginalComponentTenderAllocation
import com.ukkera.pos.sales.OwnedOrder
import com.ukkera.pos.sales.PaidSale
import com.ukkera.pos.sales.PaymentAttempt
import com.ukkera.pos.sales.PaymentRecord
import com.ukkera.pos.sales.ReceiptEntitlement
import com.ukkera.pos.sales.SalesSnapshotKind
import com.ukkera.pos.sales.SalesUniqueKind
import com.ukkera.pos.sales.Tender
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.AllocationWeight
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.ExactRational
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.PercentageRate
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitPrice
import com.ukkera.pos.types.allocateByLargestRemainder
import kotlin.coroutines.cancellation.CancellationException
import kotlin.text.CharacterCodingException

/**
 * TEST ONLY: concrete command/envelope and intrinsic AFTER-fact correspondence for the six
 * closed checkout producers. This is NOT historical before/delta replay, proof of original
 * authorization/custody/coverage, a current frontier, or standalone inventory provenance approval.
 * In particular no predecessor graph, fake read view, planner, current price calculation or
 * reconstructed deadline is used. Retained v1 did not store all those facts.
 *
 * Accepting current inventory requires genuine A2 observe (full cut/spine/current source claims)
 * followed by requireCheckoutInventoryProducer. Historical collateral sales/cash AFTER rows
 * deliberately are not compared with today's mutable rows. Live dependencies remain separate.
 */
internal fun requireCheckoutAfterFacts(evidence: RetainedCommandEvidence): Unit = checkoutAfterGuard {
    CheckoutAfterEnvelope(evidence).requireFacts()
}

/** Closed accepting dispatch. The observed object must come from the real A2 borrowed read. */
internal fun requireCheckoutInventoryProducer(observed: InventoryRetailObservedFacts): Unit {
    val retained = observed.retained
    when (retained.command) {
        InventoryRetailStoreProfile.GENESIS_COMMAND, InventoryRetailStoreProfile.RECEIVE_COMMAND -> {
            InventoryRetailRepository.requireFixtureProducer(observed)
            return
        }
    }
    checkoutAfterGuard {
        when (retained.command) {
            CheckoutFixtureProfile.BEGIN, CheckoutFixtureProfile.ATTEMPT, CheckoutFixtureProfile.FINALIZE -> Unit
            CheckoutFixtureProfile.OUTCOME -> {
                val request = CheckoutFixtureCodec.decode(retained.command, retained.normalized) as? CheckoutRequest.Outcome
                    ?: checkoutAfterReject()
                checkoutAfterCheck(request.exposure is CheckoutExposureUpdate.Derive)
            }
            else -> checkoutAfterReject(CheckoutContractFailure.UNSUPPORTED)
        }
        requireCheckoutAfterFacts(retained)
        checkoutAfterCheck(retained.scope == observed.scope)
        // Only the selected inventory pair is current. Extra original sales/cash rows are history.
        val inventory = retained.aggregateWrites.filter {
            it.key.kind == InventoryRetailStoreProfile.CUT_KIND || it.key.kind == InventoryRetailStoreProfile.COMPANION_KIND
        }
        checkoutAfterCheck(inventory.size == 2)
        val cut = inventory.singleOrNull { it.key == InventoryRetailStoreProfile.cutKey(observed.scope.branchId) }
            ?: checkoutAfterReject()
        val companion = inventory.singleOrNull { it.key == InventoryRetailStoreProfile.companionKey(observed.scope.branchId) }
            ?: checkoutAfterReject()
        checkoutAfterCheck(cut.storageGeneration == observed.storageGeneration &&
            cut.schema == InventoryRetailStoreProfile.CUT_SCHEMA && cut.payload == observed.cutBytes &&
            cut.payloadDigest == observed.cutDigest && companion.storageGeneration == observed.storageGeneration &&
            companion.schema == InventoryRetailStoreProfile.COMPANION_SCHEMA && companion.payload == observed.companionBytes &&
            companion.payloadDigest == observed.companionDigest)
    }
}

/** Owns exactly one actual retained envelope; no caller-supplied expected-output/approval port. */
private class CheckoutAfterEnvelope(private val evidence: RetainedCommandEvidence) {
    private val scope = evidence.scope
    private val actor = evidence.originalActorId
    private val operation = evidence.operationId
    private val rows: Map<AggregateKey, CheckoutRow>
    private val request: CheckoutRequest
    private val result: CheckoutResult

    init {
        checkoutAfterCheck(evidence.committed && evidence.command in CheckoutFixtureProfile.COMMANDS)
        val writes = evidence.aggregateWrites
        checkoutAfterCheck(writes.size in 1..6 && evidence.claimChanges.size <= 4, CheckoutContractFailure.TOO_LARGE)
        checkoutAfterCheck(writes.map { it.key }.distinct().size == writes.size && evidence.jobs.isEmpty())
        checkoutAfterCheck(evidence.claimChanges.all { it.action == RetainedClaimAction.ACQUIRE } &&
            evidence.claimChanges.map { it.key }.distinct().size == evidence.claimChanges.size)
        writes.forEach {
            checkoutAfterCheck(it.storageGeneration.value > 0 && it.payload.size in 1..CheckoutFixtureProfile.MAX_ROW_BYTES &&
                sha256(it.payload.copyBytes()) == it.payloadDigest)
        }
        rows = writes.associate { it.key to CheckoutRow.from(it) }
        request = CheckoutFixtureCodec.decode(evidence.command, evidence.normalized)
        result = CheckoutFixtureCodec.decodeResult(evidence.command, evidence.result)
        checkoutAfterCheck(result !is CheckoutResult.Rejected && CheckoutFixtureCodec.encode(request) == evidence.normalized &&
            CheckoutFixtureCodec.encodeResult(result) == evidence.result &&
            semanticDigest(scope, actor, evidence.command, evidence.normalized) == evidence.semanticDigest &&
            sha256(evidence.result.copyBytes()) == evidence.resultHash)
        requireEnvelopeBudget()
    }

    fun requireFacts() {
        when (val input = request) {
            is CheckoutRequest.Open -> requireOpen(input, result as? CheckoutResult.Opened ?: checkoutAfterReject())
            is CheckoutRequest.Create -> requireCreate(input, result as? CheckoutResult.Created ?: checkoutAfterReject())
            is CheckoutRequest.Begin -> requireBegin(input, result as? CheckoutResult.Begun ?: checkoutAfterReject())
            is CheckoutRequest.Attempt -> requireAttempt(input, result as? CheckoutResult.Attempted ?: checkoutAfterReject())
            is CheckoutRequest.Outcome -> requireOutcome(input, result as? CheckoutResult.OutcomeRecorded ?: checkoutAfterReject())
            is CheckoutRequest.Finalize -> when (val output = result) {
                is CheckoutResult.Held -> requireHeld(input, output)
                is CheckoutResult.Issued -> requireIssued(input, output)
                else -> checkoutAfterReject()
            }
        }
    }

    private fun requireOpen(input: CheckoutRequest.Open, output: CheckoutResult.Opened) {
        requireWrites(salesKey(SalesSnapshotKind.CASH_SESSION, input.sessionId),
            CheckoutFixtureProfile.drawerKey(input.drawerId), CheckoutFixtureProfile.custodianKey(actor))
        requireClaims(claim(SalesUniqueKind.CASH_SESSION, input.sessionId, input.sessionId),
            claim(SalesUniqueKind.OPENING_FLOAT_SOURCE, input.openingSourceId, input.sessionId),
            claim(SalesUniqueKind.ACTIVE_DRAWER, input.drawerId, input.sessionId),
            claim(SalesUniqueKind.ACTIVE_CUSTODIAN, actor, input.sessionId))
        val cash = cash(input.sessionId)
        val zero = Money(input.opening.currency, 0)
        checkoutAfterCheck(cash.revision == Revision(0) && cash.drawerId == input.drawerId && cash.custodianId == actor &&
            cash.businessDate == input.businessDate.value && cash.opening.amount == input.opening &&
            cash.opening.evidence == input.evidence.toDomain(actor) && cash.openingSourceId == input.openingSourceId &&
            cash.totals == CashTotals(zero, zero, zero, zero, zero, zero, zero, zero) &&
            cash.expectedCash == input.opening && cash.reservedCashOut == zero && cash.cashLedgerRevision == Revision(0) &&
            cash.status == CashSessionStatus.OPEN && cash.pendingAttemptIds.isEmpty() && cash.pendingTransferIds.isEmpty() &&
            cash.closingCut == null && cash.closeReport == null && cash.review == null)
        val drawer = CheckoutRows.drawer(row(CheckoutFixtureProfile.drawerKey(input.drawerId)), scope, input.drawerId)
        val custodian = CheckoutRows.custodian(row(CheckoutFixtureProfile.custodianKey(actor)), scope, actor)
        checkoutAfterCheck(drawer.revision == input.expectedDrawerRevision.next() && drawer.activeSessionId == input.sessionId &&
            drawer.unresolvedClosingTransferId == null && custodian.revision == input.expectedCustodianRevision.next() &&
            custodian.activeSessionId == input.sessionId)
        checkoutAfterCheck(output.cash == CheckoutCashRef(CheckoutRef(input.sessionId, Revision(0)), drawer.revision, custodian.revision))
    }

    private fun requireCreate(input: CheckoutRequest.Create, output: CheckoutResult.Created) {
        requireWrites(salesKey(SalesSnapshotKind.ORDER, input.orderId))
        requireClaims(claim(SalesUniqueKind.ORDER, input.orderId, input.orderId))
        val order = order(input.orderId)
        checkoutAfterCheck(order.revision == Revision(0) && order.originalCreatorId == actor && order.ownerId == actor &&
            order.handoff == null && order.collectionId == null && order.paidSaleId == null)
        requireLiteralCheckoutQuote(input, order.quote)
        checkoutAfterCheck(sameCheckoutAfterAllocations(order.stockRequirements.allocations, input.lines.map { it.toAllocation(scope) }))
        checkoutAfterCheck(output.order == CheckoutRef(input.orderId, Revision(0)))
    }

    private fun requireBegin(input: CheckoutRequest.Begin, output: CheckoutResult.Begun) {
        requireWrites(*inventoryKeys(), salesKey(SalesSnapshotKind.ORDER, input.order.id),
            salesKey(SalesSnapshotKind.COLLECTION, input.collectionId))
        requireClaims(claim(SalesUniqueKind.COLLECTION, input.collectionId, input.order.id))
        val order = order(input.order.id)
        val collection = collection(input.collectionId)
        checkoutAfterCheck(order.revision == input.order.revision.next() && collection.revision == Revision(0) &&
            order.originalCreatorId == actor && order.ownerId == actor && order.handoff == null &&
            order.collectionId == input.collectionId && order.paidSaleId == null && collection.orderId == order.id &&
            collection.lockedOrderRevision == order.revision && collection.originalCreatorId == order.originalCreatorId &&
            collection.collectorId == actor && collection.cashSessionId == input.cash.session.id &&
            collection.reservationId == input.reservationId && collection.attempts.isEmpty() && collection.saleId == null)
        requireOrderCollectionFacts(order, collection)
        val inventory = inventory(input.expectedInventoryRevision)
        val reservation = inventory.reservation(input.reservationId)
        requireReservationCollectionFacts(reservation, collection)
        checkoutAfterCheck(reservation.revision == Revision(1) && reservation.status == ReservationStatus.RESERVED &&
            reservation.binding.exposure == CollectionExposure.UNKNOWN && reservation.holds.isEmpty() &&
            reservation.expiresAtEpochMillis == input.reservationExpiresAtEpochMillis)
        requireSingleReservationEffect(inventory, reservation)
        inventory.requirePhysicalSources(operation, null)
        checkoutAfterCheck(output.sales == CheckoutSalesRef(CheckoutRef(order.id, order.revision),
            CheckoutRef(collection.id, collection.revision), input.cash) && output.stock == inventory.reference(reservation))
    }

    private fun requireAttempt(input: CheckoutRequest.Attempt, output: CheckoutResult.Attempted) {
        requireWrites(*inventoryKeys(), salesKey(SalesSnapshotKind.COLLECTION, input.sales.collection.id),
            salesKey(SalesSnapshotKind.CASH_SESSION, input.sales.cash.session.id))
        requireClaims(claim(SalesUniqueKind.PAYMENT_ATTEMPT, input.attemptId, input.sales.collection.id))
        val collection = collectionAfter(input.sales)
        val attempt = collection.attempts.lastOrNull() ?: checkoutAfterReject()
        checkoutAfterCheck(attempt.id == input.attemptId && attempt.originalOperationId == operation &&
            attempt.originalActorId == actor && attempt.originalCashSessionId == input.sales.cash.session.id &&
            attempt.payerReference == input.payerReference && attempt.tender == input.tender.toDomain() &&
            attempt.amount == input.amount && attempt.status == AttemptStatus.AWAITING_ATTESTATION &&
            attempt.latestObservation == null && attempt.payment == null)
        checkoutAfterCheck(collection.attempts.dropLast(1).none { it.originalOperationId == operation } &&
            collection.attempts.none { it.payment?.recordingOperationId == operation })
        val cash = cash(input.sales.cash.session.id)
        requireCashAfter(cash, input.sales.cash.session.revision.next(), collection)
        val inventory = inventory(input.stock.inventoryRevision)
        val reservation = inventory.advancedReservation(input.stock.reservation)
        requireReservationCollectionFacts(reservation, collection)
        checkoutAfterCheck(reservation.status == ReservationStatus.RESERVED && reservation.holds.isEmpty() &&
            reservation.binding.exposure in setOf(CollectionExposure.UNKNOWN, CollectionExposure.PART_PAID))
        requireSafetyOnly(inventory, reservation, allowIntermediateTarget = false)
        inventory.requirePhysicalSources(operation, null)
        checkoutAfterCheck(output.attemptId == input.attemptId && output.sales == afterSales(input.sales, collection, cash.revision) &&
            output.stock == inventory.reference(reservation))
    }

    private fun requireOutcome(input: CheckoutRequest.Outcome, output: CheckoutResult.OutcomeRecorded) {
        val unknown = input.outcome is CheckoutOutcome.Unknown
        val derived = input.exposure as? CheckoutExposureUpdate.Derive
        val keys = mutableListOf(salesKey(SalesSnapshotKind.COLLECTION, input.sales.collection.id))
        if (!unknown) keys += salesKey(SalesSnapshotKind.CASH_SESSION, input.sales.cash.session.id)
        if (derived != null) keys += inventoryKeys()
        requireWrites(*keys.toTypedArray())
        val collection = collectionAfter(input.sales)
        val attempt = collection.attempts.lastOrNull() ?: checkoutAfterReject()
        val observation = input.outcome.toDomain(actor)
        checkoutAfterCheck(attempt.id == input.attemptId && attempt.originalActorId == actor &&
            attempt.originalCashSessionId == input.sales.cash.session.id && attempt.latestObservation == observation &&
            collection.attempts.none { it.originalOperationId == operation } &&
            collection.attempts.dropLast(1).none { it.payment?.recordingOperationId == operation })
        when (observation) {
            is CollectionOutcome.Unknown -> {
                checkoutAfterCheck(attempt.status == AttemptStatus.OUTCOME_UNKNOWN && attempt.payment == null)
                requireClaims()
            }
            is CollectionOutcome.NotPaid -> {
                checkoutAfterCheck(attempt.status == AttemptStatus.NOT_PAID && attempt.payment == null)
                requireClaims()
            }
            is CollectionOutcome.CashReceived -> {
                val payment = requirePaidObservation(collection, attempt)
                checkoutAfterCheck(payment.tender == Tender.Cash && payment.cashReceived == observation.received &&
                    payment.changeGiven == observation.change && payment.attestation == observation.evidence &&
                    payment.externalEvidence == null && observation.received - observation.change == payment.amount)
                requireClaims(claim(SalesUniqueKind.PAYMENT_RECORD, attempt.id, collection.id),
                    claim(SalesUniqueKind.CASH_SOURCE, attempt.id, collection.cashSessionId))
                // The original payment, normalized observation and cash AFTER retain every
                // CUSTOMER_RECEIPT field. They do not independently prove a before/after delta.
            }
            is CollectionOutcome.ExternalAttested -> {
                val payment = requirePaidObservation(collection, attempt)
                checkoutAfterCheck(payment.tender is Tender.External && payment.cashReceived == null && payment.changeGiven == null &&
                    payment.attestation == observation.evidence.attestation && payment.externalEvidence == observation.evidence)
                requireClaims(claim(SalesUniqueKind.PAYMENT_RECORD, attempt.id, collection.id))
            }
        }
        val cashRevision = if (unknown) input.sales.cash.session.revision else input.sales.cash.session.revision.next()
        if (!unknown) requireCashAfter(cash(input.sales.cash.session.id), cashRevision, collection)
        // Unknown deliberately has no cash AFTER; do not invent its pending index or a cash delta.
        val stock = if (derived == null) null else {
            val inventory = inventory(derived.stock.inventoryRevision)
            val reservation = inventory.advancedReservation(derived.stock.reservation)
            requireReservationCollectionFacts(reservation, collection)
            checkoutAfterCheck(reservation.retainsStockClaim && reservation.binding.exposure == derivedCheckoutAfterExposure(collection))
            requireSingleReservationEffect(inventory, reservation)
            inventory.requirePhysicalSources(operation, null)
            // Actual exposure publication occurs even if the enum stays the same. No safety/time
            // revalidation: a late attestation may truthfully change money after stock expiry.
            inventory.reference(reservation)
        }
        checkoutAfterCheck(output.attemptId == input.attemptId && output.sales == afterSales(input.sales, collection, cashRevision) &&
            output.stock == stock)
    }

    private fun requireHeld(input: CheckoutRequest.Finalize, output: CheckoutResult.Held) {
        requireWrites(*inventoryKeys())
        requireClaims()
        val inventory = inventory(input.stock.inventoryRevision)
        val reservation = inventory.advancedReservation(input.stock.reservation)
        requireFinalReservationIds(input, reservation)
        checkoutAfterCheck(reservation.status == ReservationStatus.HELD && reservation.holds.isNotEmpty() &&
            input.issueId !in inventory.snapshot.sourceIds && inventory.snapshot.issue(input.issueId) == null)
        requireSafetyOnly(inventory, reservation, allowIntermediateTarget = true)
        inventory.requirePhysicalSources(operation, null)
        checkoutAfterCheck(output.sales == input.sales && output.stock == inventory.reference(reservation))
        // These TWO AFTER rows cannot establish historical covered money, zero-payable attempts,
        // original quote or custody. Those were live admission checks before BOTH final branches.
        // The requested issue is absent in this inventory AFTER. No missing sale row or global
        // historical claim absence can be inferred from these inventory-only writes.
    }

    private fun requireIssued(input: CheckoutRequest.Finalize, output: CheckoutResult.Issued) {
        requireWrites(*inventoryKeys(), salesKey(SalesSnapshotKind.ORDER, input.sales.order.id),
            salesKey(SalesSnapshotKind.COLLECTION, input.sales.collection.id), salesKey(SalesSnapshotKind.PAID_SALE, input.saleId),
            salesKey(SalesSnapshotKind.RECEIPT_ENTITLEMENT, input.saleId))
        requireClaims(InventoryRetailStoreProfile.sourceClaim(input.issueId) to DomainId(operation.value),
            claim(SalesUniqueKind.SALE, input.saleId, input.sales.order.id),
            claim(SalesUniqueKind.SALE_ORDER, input.sales.order.id, input.saleId),
            claim(SalesUniqueKind.RETURN_ENTITLEMENT, input.saleId, input.saleId))
        val order = order(input.sales.order.id)
        val collection = collectionAfter(input.sales, finalizedSaleId = input.saleId)
        checkoutAfterCheck(order.revision == input.sales.order.revision.next() && order.ownerId == actor &&
            order.originalCreatorId == collection.originalCreatorId && order.handoff == null &&
            order.collectionId == collection.id && order.paidSaleId == input.saleId &&
            collection.status == CollectionStatus.FINALIZED_PAID && !collection.unresolved && collection.collected == collection.quote.payable)
        requireOrderCollectionFacts(order, collection)
        checkoutAfterCheck(collection.attempts.none { it.originalOperationId == operation || it.payment?.recordingOperationId == operation })
        val inventory = inventory(input.stock.inventoryRevision)
        val reservation = inventory.advancedReservation(input.stock.reservation)
        requireFinalReservationIds(input, reservation)
        requireReservationCollectionFacts(reservation, collection)
        checkoutAfterCheck(reservation.status == ReservationStatus.ISSUED && reservation.holds.isEmpty())
        val issue = inventory.snapshot.issue(input.issueId) ?: checkoutAfterReject()
        requireIssueAfter(inventory, reservation, issue, input.issueId)
        inventory.requirePhysicalSources(operation, input.issueId)
        val sale = CheckoutRows.sale(row(salesKey(SalesSnapshotKind.PAID_SALE, input.saleId)), scope, input.saleId)
        checkoutAfterCheck(sale.orderId == order.id && sale.originalCreatorId == order.originalCreatorId && sale.collectorId == actor &&
            sale.collectingSessionId == collection.cashSessionId && sameCheckoutAfterQuote(sale.quote, collection.quote) &&
            sale.stockIssueId == issue.issueId && sale.payments == collection.attempts.mapNotNull { it.payment })
        if (collection.quote.payable.minor == 0L) checkoutAfterCheck(collection.attempts.isEmpty() && sale.payments.isEmpty())
        requireOriginalCheckoutMatrix(sale)
        val entitlement = CheckoutRows.entitlement(row(salesKey(SalesSnapshotKind.RECEIPT_ENTITLEMENT, input.saleId)), scope, input.saleId)
        requireInitialCheckoutEntitlement(entitlement, sale)
        checkoutAfterCheck(output.issueId == input.issueId && output.saleId == input.saleId &&
            output.sales == CheckoutSalesRef(CheckoutRef(order.id, order.revision), CheckoutRef(collection.id, collection.revision), input.sales.cash) &&
            output.stock == inventory.reference(reservation))
        // Exact six writes/four claims exclude a cash mutation, another payment or CASH_SOURCE.
    }

    private fun requirePaidObservation(collection: CollectionLock, attempt: PaymentAttempt): PaymentRecord {
        val payment = attempt.payment ?: checkoutAfterReject()
        checkoutAfterCheck(attempt.status == AttemptStatus.ATTESTED_PAID && payment.attemptId == attempt.id &&
            payment.originalOperationId == attempt.originalOperationId && payment.recordingOperationId == operation &&
            payment.collectionId == collection.id && payment.collectorId == actor && payment.cashSessionId == collection.cashSessionId &&
            payment.payerReference == attempt.payerReference && payment.tender == attempt.tender && payment.amount == attempt.amount)
        return payment
    }

    private fun collectionAfter(input: CheckoutSalesRef, finalizedSaleId: DomainId? = null): CollectionLock {
        val collection = collection(input.collection.id)
        checkoutAfterCheck(collection.revision == input.collection.revision.next() && collection.orderId == input.order.id &&
            collection.lockedOrderRevision == input.order.revision && collection.collectorId == actor &&
            collection.originalCreatorId == actor && collection.cashSessionId == input.cash.session.id && collection.saleId == finalizedSaleId)
        // This closed fixture has no handoff/import producer. It does not infer a missing order row.
        return collection
    }

    private fun requireCashAfter(cash: CashSession, revision: Revision, collection: CollectionLock) {
        checkoutAfterCheck(cash.revision == revision && cash.id == collection.cashSessionId && cash.custodianId == actor &&
            cash.status == CashSessionStatus.OPEN && cash.opening.amount.currency == collection.quote.currency)
        var received = Money(collection.quote.currency, 0)
        var change = received
        var external = received
        var cashPayments = 0L
        for (attempt in collection.attempts) {
            val unresolved = attempt.status == AttemptStatus.AWAITING_ATTESTATION || attempt.status == AttemptStatus.OUTCOME_UNKNOWN
            checkoutAfterCheck((attempt.id in cash.pendingAttemptIds) == unresolved)
            val payment = attempt.payment ?: continue
            when (payment.tender) {
                Tender.Cash -> {
                    received += payment.cashReceived ?: checkoutAfterReject()
                    change += payment.changeGiven ?: checkoutAfterReject()
                    cashPayments++
                }
                is Tender.External -> external += payment.amount
            }
        }
        // These are subset/lower-bound AFTER facts, NOT global pending completeness, an empty-
        // session rule or cash delta replay. Other legitimate collections may remain pending.
        checkoutAfterCheck(cash.totals.received.currency == received.currency && cash.totals.received.minor >= received.minor &&
            cash.totals.change.minor >= change.minor && cash.totals.externalCollections.minor >= external.minor &&
            cash.cashLedgerRevision.value >= cashPayments)
    }

    private fun afterSales(input: CheckoutSalesRef, collection: CollectionLock, cashRevision: Revision): CheckoutSalesRef =
        CheckoutSalesRef(input.order, CheckoutRef(collection.id, collection.revision),
            input.cash.copy(session = input.cash.session.copy(revision = cashRevision)))

    private fun requireFinalReservationIds(input: CheckoutRequest.Finalize, reservation: StockReservation) {
        checkoutAfterCheck(reservation.reservationId == input.stock.reservation.id && reservation.binding.orderId == input.sales.order.id &&
            reservation.binding.collectionId == input.sales.collection.id && reservation.binding.originalActorId == actor &&
            reservation.binding.exposure != CollectionExposure.UNCOLLECTED)
    }

    private fun requireOrderCollectionFacts(order: OwnedOrder, collection: CollectionLock) {
        checkoutAfterCheck(collection.orderId == order.id && collection.originalCreatorId == order.originalCreatorId &&
            collection.collectorId == order.ownerId && order.collectionId == collection.id && order.paidSaleId == collection.saleId &&
            sameCheckoutAfterQuote(order.quote, collection.quote) &&
            sameCheckoutAfterAllocations(order.stockRequirements.allocations, collection.stockRequirements.allocations))
    }

    private fun requireReservationCollectionFacts(reservation: StockReservation, collection: CollectionLock) {
        checkoutAfterCheck(reservation.reservationId == collection.reservationId && reservation.binding.orderId == collection.orderId &&
            reservation.binding.collectionId == collection.id && reservation.binding.quoteRevision == collection.quote.revision &&
            reservation.binding.originalActorId == collection.collectorId &&
            sameCheckoutAfterAllocations(reservation.allocations, collection.stockRequirements.allocations))
    }

    private fun inventory(expectedBefore: Revision): CheckoutInventoryAfter {
        val cut = row(InventoryRetailStoreProfile.cutKey(scope.branchId))
        val companionRow = row(InventoryRetailStoreProfile.companionKey(scope.branchId))
        val afterRevision = expectedBefore.next()
        val generation = InventoryRetailStoreProfile.toStorageRevision(afterRevision)
        checkoutAfterCheck(cut.schema == InventoryRetailStoreProfile.CUT_SCHEMA &&
            companionRow.schema == InventoryRetailStoreProfile.COMPANION_SCHEMA &&
            cut.storageGeneration == generation && companionRow.storageGeneration == generation)
        val inventoryScope = InventoryScope(scope.companyId, scope.branchId)
        val snapshot = InventorySnapshotCodec.decode(CheckoutRows.text(cut),
            InventorySnapshotBinding(InventorySnapshotKind.BRANCH_INVENTORY, inventoryScope, scope.branchId, afterRevision))
        InventoryPosting.requireSupportedSnapshot(snapshot)
        checkoutAfterCheck(snapshot.reservations.size <= InventoryRetailStoreProfile.MAX_RESERVATIONS &&
            snapshot.issues.size <= InventoryRetailStoreProfile.MAX_ISSUES &&
            snapshot.sourceIds.size <= InventoryRetailStoreProfile.MAX_PHYSICAL_SOURCES &&
            snapshot.operationIds.size <= InventoryRetailStoreProfile.MAX_INVENTORY_OPERATIONS, CheckoutContractFailure.TOO_LARGE)
        val companion = InventoryRetailCompanionCodec.decode(companionRow.payload,
            InventoryRetailCompanionBinding(scope, scope.branchId, afterRevision))
        checkoutAfterCheck(companion.cutDigest == cut.payloadDigest && companion.producer.operationId == operation &&
            companion.producer.originalActorId == actor && companion.producer.command == evidence.command &&
            companion.producer.semanticDigest == evidence.semanticDigest && snapshot.operationIds.lastOrNull() == operation)
        val batch = companion.latestBatch ?: checkoutAfterReject()
        checkoutAfterCheck(batch.binding == InventoryPostingBinding(inventoryScope, operation, expectedBefore, afterRevision))
        // The public codecs check intrinsic facts and ordered issue groups. The real accepting
        // observe owns A2's cumulative receipt/lot spine and source claims; do NOT copy it here.
        return CheckoutInventoryAfter(snapshot, companion, batch).also { it.requireLatestAfterLinks() }
    }

    private fun inventoryKeys(): Array<AggregateKey> = arrayOf(InventoryRetailStoreProfile.cutKey(scope.branchId),
        InventoryRetailStoreProfile.companionKey(scope.branchId))

    private fun row(key: AggregateKey): CheckoutRow = rows[key] ?: checkoutAfterReject(CheckoutContractFailure.BINDING_MISMATCH)
    private fun salesKey(kind: SalesSnapshotKind, id: DomainId): AggregateKey = CheckoutFixtureProfile.salesKey(kind, id)
    private fun cash(id: DomainId): CashSession = CheckoutRows.cash(row(salesKey(SalesSnapshotKind.CASH_SESSION, id)), scope, id)
    private fun order(id: DomainId): OwnedOrder = CheckoutRows.order(row(salesKey(SalesSnapshotKind.ORDER, id)), scope, id)
    private fun collection(id: DomainId): CollectionLock =
        CheckoutRows.historicalCollection(row(salesKey(SalesSnapshotKind.COLLECTION, id)), scope, id)

    private fun claim(kind: SalesUniqueKind, id: DomainId, owner: DomainId): Pair<ClaimKey, DomainId> =
        CheckoutFixtureProfile.claim(kind, id) to owner

    private fun requireWrites(vararg keys: AggregateKey) {
        checkoutAfterCheck(keys.size == keys.distinct().size && rows.size == keys.size && rows.keys == keys.toSet())
    }

    private fun requireClaims(vararg expected: Pair<ClaimKey, DomainId>) {
        val actual = evidence.claimChanges
        checkoutAfterCheck(expected.map { it.first }.distinct().size == expected.size && actual.size == expected.size &&
            actual.all { it.action == RetainedClaimAction.ACQUIRE } &&
            actual.map { it.key to it.ownerId }.toSet() == expected.toSet())
    }

    private fun requireEnvelopeBudget() {
        // Six bounded rows/four claims/no requested jobs. Count actual UTF-8 metadata and binary
        // effect frames; 16 KiB conservatively covers the kernel's bounded header/scalar framing.
        var bytes = 16_384L + evidence.normalized.size + evidence.result.size
        for (row in rows.values) bytes += 4 + 5 * 4 + row.key.kind.encodeToByteArray().size + row.key.id.value.length +
            row.storageGeneration.value.toString().length + row.schema.encodeToByteArray().size + row.payload.size
        for (claim in evidence.claimChanges) bytes += 4 + 4 * 4 + "claim.acquire".length +
            claim.key.namespace.encodeToByteArray().size + claim.key.key.encodeToByteArray().size + claim.ownerId.value.length
        checkoutAfterCheck(bytes <= CheckoutFixtureProfile.MAX_PROPOSAL_BYTES, CheckoutContractFailure.TOO_LARGE)
    }
}

/** Only decoded original values, not a live observed object or an inventory proof. */
private class CheckoutInventoryAfter(
    val snapshot: InventorySnapshot,
    val companion: InventoryRetailCompanion,
    val batch: InventoryPostingBatch,
) {
    fun reservation(id: DomainId): StockReservation = snapshot.reservation(id) ?: checkoutAfterReject()

    fun reference(reservation: StockReservation): CheckoutReservationRef =
        CheckoutReservationRef(snapshot.revision, CheckoutRef(reservation.reservationId, reservation.revision))

    fun advancedReservation(expected: CheckoutRef): StockReservation {
        val reservation = reservation(expected.id)
        val records = batch.effects.filterIsInstance<InventoryPostingEffect.ReservationRecorded>()
            .filter { it.reservation.reservationId == expected.id }
        checkoutAfterCheck(records.isNotEmpty() && records.first().reservation.revision == expected.revision.next() &&
            sameCheckoutAfterReservation(records.last().reservation, reservation))
        // A1 validates intermediate advances. The final revision is NOT universally expected+1:
        // one expiry batch may record the target/collateral holds repeatedly before final Held.
        return reservation
    }

    fun requirePhysicalSources(operation: OperationId, issueId: DomainId?) {
        checkoutAfterCheck(companion.receipts.none { it.producingOperationId == operation })
        val issued = companion.issueSources.filter { it.producingOperationId == operation }.map { it.issueId }
        checkoutAfterCheck(issued == if (issueId == null) emptyList() else listOf(issueId))
        if (issueId != null) checkoutAfterCheck(snapshot.sourceIds.lastOrNull() == issueId)
    }

    fun requireLatestAfterLinks() {
        // Check only recorded AFTER objects. Earlier collateral records can be intermediate;
        // neither these nor original collateral sales AFTER rows must equal today's mutable rows.
        val reservations = batch.effects.filterIsInstance<InventoryPostingEffect.ReservationRecorded>()
        for (id in reservations.map { it.reservation.reservationId }.distinct()) {
            val last = reservations.last { it.reservation.reservationId == id }.reservation
            checkoutAfterCheck(sameCheckoutAfterReservation(last, reservation(id)))
        }
        for (effect in batch.effects) when (effect) {
            is InventoryPostingEffect.SafetyChanged -> checkoutAfterCheck(snapshot.position(effect.after.key) == effect.after)
            is InventoryPostingEffect.IssueRecorded -> checkoutAfterCheck(
                snapshot.issue(effect.issue.issueId)?.let { sameCheckoutAfterIssue(it, effect.issue) } == true)
            is InventoryPostingEffect.ReservationRecorded, is InventoryPostingEffect.Movement, is InventoryPostingEffect.Valuation -> Unit
        }
    }
}

private fun requireSingleReservationEffect(inventory: CheckoutInventoryAfter, reservation: StockReservation) {
    val effect = inventory.batch.effects.singleOrNull() as? InventoryPostingEffect.ReservationRecorded ?: checkoutAfterReject()
    checkoutAfterCheck(sameCheckoutAfterReservation(effect.reservation, reservation))
}

private fun requireSafetyOnly(inventory: CheckoutInventoryAfter, target: StockReservation, allowIntermediateTarget: Boolean) {
    var targetCount = 0
    for (effect in inventory.batch.effects) when (effect) {
        is InventoryPostingEffect.SafetyChanged -> Unit // Intrinsic codec permits only CLEAR -> EXPIRED.
        is InventoryPostingEffect.ReservationRecorded -> {
            if (effect.reservation.reservationId == target.reservationId) targetCount++
            else checkoutAfterCheck(effect.reservation.status == ReservationStatus.HELD)
            if (allowIntermediateTarget) checkoutAfterCheck(effect.reservation.status == ReservationStatus.HELD)
        }
        is InventoryPostingEffect.Movement, is InventoryPostingEffect.Valuation, is InventoryPostingEffect.IssueRecorded -> checkoutAfterReject()
    }
    checkoutAfterCheck(targetCount > 0 && (allowIntermediateTarget || targetCount == 1))
    val last = inventory.batch.effects.lastOrNull() as? InventoryPostingEffect.ReservationRecorded ?: checkoutAfterReject()
    checkoutAfterCheck(sameCheckoutAfterReservation(last.reservation, target))
}

private fun requireIssueAfter(inventory: CheckoutInventoryAfter, reservation: StockReservation, issue: StockIssue, issueId: DomainId) {
    checkoutAfterCheck(issue.issueId == issueId && issue.reservationId == reservation.reservationId && issue.binding == reservation.binding &&
        issue.purpose == ValueIssuePurpose.RETAIL && issue.revision == Revision(1) && issue.kitchen == null &&
        issue.allocations.all { it.restoredQuantity == it.allocation.quantity.copy(scaled = 0) } &&
        sameCheckoutAfterAllocations(issue.allocations.map { it.allocation }, reservation.allocations))
    val effects = inventory.batch.effects
    val postedIssues = effects.filterIsInstance<InventoryPostingEffect.IssueRecorded>()
    checkoutAfterCheck(postedIssues.size == 1 && sameCheckoutAfterIssue(postedIssues.single().issue, issue) &&
        effects.lastOrNull() is InventoryPostingEffect.IssueRecorded)
    val targetRecords = effects.filterIsInstance<InventoryPostingEffect.ReservationRecorded>()
        .filter { it.reservation.reservationId == reservation.reservationId }
    checkoutAfterCheck(targetRecords.size == 1 && sameCheckoutAfterReservation(targetRecords.single().reservation, reservation))
    for (effect in effects) when (effect) {
        is InventoryPostingEffect.Valuation -> {
            val fact = effect.fact as? ValuationFact.Issue ?: checkoutAfterReject()
            val value = inventory.snapshot.pool(effect.pool)?.value ?: checkoutAfterReject()
            val stored = value.facts.lastOrNull() as? ValuationFact.Issue ?: checkoutAfterReject()
            checkoutAfterCheck(fact.factId == issueId && stored.factId == fact.factId && stored.purpose == fact.purpose &&
                stored.slices == fact.slices && value.revision == effect.resultingPoolRevision)
        }
        is InventoryPostingEffect.Movement -> checkoutAfterCheck(effect.sourceId == issueId)
        is InventoryPostingEffect.ReservationRecorded -> if (effect.reservation.reservationId != reservation.reservationId) {
            checkoutAfterCheck(effect.reservation.status == ReservationStatus.HELD)
        }
        is InventoryPostingEffect.SafetyChanged, is InventoryPostingEffect.IssueRecorded -> Unit
    }
    // The A1 codec already checks the complete ordered grouping, exact valuation slices,
    // per-allocation movements and final ISSUED reservation + issue. No costs are replanned.
}

/** Literal fixture correspondence, not execution of today's pricing engine against old facts. */
private fun requireLiteralCheckoutQuote(input: CheckoutRequest.Create, quote: PricingSnapshot) {
    val prices = when (input.basket) {
        CheckoutBasket.ZERO -> listOf(0L to "0")
        CheckoutBasket.SIMPLE_100 -> listOf(100L to "1.00")
        CheckoutBasket.SPLIT_33_33_34 -> listOf(33L to "0.33", 33L to "0.33", 34L to "0.34")
    }
    val payable = Money(Currency.EGP, if (input.basket == CheckoutBasket.ZERO) 0L else 100L)
    val exactPayable = ExactRational.ratio(payable.minor, 100)
    val zero = Money(Currency.EGP, 0)
    val zeroExact = ExactRational.ratio(0, 1)
    val tax = TaxSnapshot(CheckoutFixtureProfile.TAX_ID, CheckoutFixtureProfile.TAX_REVISION,
        CheckoutFixtureProfile.TAX_CATEGORY, PercentageRate.parse("0"))
    val limits = DiscountLimits.none(Currency.EGP)
    checkoutAfterCheck(quote.snapshotId == input.quoteId && quote.revision == CheckoutFixtureProfile.QUOTE_REVISION &&
        quote.currency == Currency.EGP && quote.profile == PricingProfiles.LINEAR_PROTECTED_V2 &&
        quote.taxPostingPolicy == TaxPostingPolicy.OPERATIONAL_COMPONENT_ALLOCATION && quote.promotionResolution == PromotionResolution.NONE &&
        quote.discountLimits == limits && quote.charges.isEmpty() && quote.discounts.isEmpty() &&
        quote.beforeDiscountPayable == payable && quote.payable == payable &&
        quote.exactBeforeDiscountPayable == exactPayable && quote.exactPayable == exactPayable &&
        quote.lines.size == input.lines.size && quote.components.size == input.lines.size * 2)
    // Literal prices attach in request order; the protected quote orders its lines by portion ID.
    val expected = input.lines.zip(prices).sortedBy { it.first.portionId.value }
    for ((index, entry) in expected.withIndex()) {
        val line = entry.first
        val price = entry.second
        val amount = Money(Currency.EGP, price.first)
        val exact = ExactRational.ratio(price.first, 100)
        val stored = quote.lines[index]
        checkoutAfterCheck(stored.lineId == line.portionId && stored.portionId == line.portionId &&
            stored.priceRevision == CheckoutFixtureProfile.PRICE_REVISION && stored.quantity == line.quantity &&
            stored.unitPrice == UnitPrice.parse(Currency.EGP, price.second) && stored.inclusion == TaxInclusion.EXCLUSIVE &&
            stored.tax == tax && stored.discountLimits == limits && stored.exactOriginalItemNet == exact &&
            stored.exactItemNet == exact && stored.payable == amount)
        for (taxComponent in listOf(false, true)) {
            val component = quote.components[index * 2 + if (taxComponent) 1 else 0]
            val expectedAmount = if (taxComponent) zero else amount
            val expectedExact = if (taxComponent) zeroExact else exact
            checkoutAfterCheck(component.key == "p/${line.portionId.value}/item/${if (taxComponent) "tax" else "net"}" &&
                component.lineId == line.portionId && component.portionId == line.portionId && component.chargeId == null &&
                component.kind == if (taxComponent) PriceComponentKind.ITEM_TAX else PriceComponentKind.ITEM_NET)
            checkoutAfterCheck(component.tax == tax && component.refundTreatment == ChargeRefundTreatment.PROPORTIONAL_WITH_ITEMS &&
                component.exactBeforeDiscounts == expectedExact && component.exact == expectedExact &&
                component.postedBeforeDiscounts == expectedAmount && component.posted == expectedAmount)
        }
    }
}

/** Immutable original matrix using only recorded component amounts and original payment balances. */
private fun requireOriginalCheckoutMatrix(sale: PaidSale) {
    val remaining = sale.payments.sortedBy { it.attemptId.value }.associate { it.attemptId.value to it.amount }.toMutableMap()
    val components = sale.quote.components.sortedBy { it.key }
    checkoutAfterCheck(sale.originalTenderAllocations.size == components.size)
    for ((index, component) in components.withIndex()) {
        val row = sale.originalTenderAllocations[index]
        val shares = allocateByLargestRemainder(component.posted, remaining.map { AllocationWeight(it.key, it.value.minor) })
        checkoutAfterCheck(row.componentKey == component.key && row.shares.size == shares.size)
        for ((position, share) in shares.withIndex()) {
            val original = row.shares[position]
            checkoutAfterCheck(original.paymentId.value == share.key && original.amount == share.amount)
            val available = remaining.getValue(share.key)
            checkoutAfterCheck(share.amount.minor <= available.minor)
            remaining[share.key] = available - share.amount
        }
    }
    checkoutAfterCheck(remaining.values.all { it.minor == 0L })
    // Zero tax component rows and zero shares are retained; no tender/arrival-order tie sorting.
}

private fun requireInitialCheckoutEntitlement(entitlement: ReceiptEntitlement, sale: PaidSale) {
    checkoutAfterCheck(entitlement.revision == Revision(0) && CheckoutRows.encodeSale(entitlement.sale) == CheckoutRows.encodeSale(sale) &&
        sameCheckoutAfterMatrix(entitlement.remainingComponentTenders, sale.originalTenderAllocations) &&
        entitlement.cursors.map { it.portionId } == sale.quote.lines.map { it.portionId })
    for (cursor in entitlement.cursors) {
        val initial = sale.quote.openReturnCursor(cursor.portionId)
        checkoutAfterCheck(cursor.originalSnapshotId == initial.originalSnapshotId && cursor.revision == initial.revision &&
            cursor.profile == initial.profile && cursor.originalQuantity == initial.originalQuantity &&
            cursor.consumedQuantity == initial.consumedQuantity && cursor.originalComponents == initial.originalComponents &&
            cursor.remainingQuantity == initial.remainingQuantity && cursor.remainingComponents == initial.remainingComponents)
    }
}

private fun derivedCheckoutAfterExposure(collection: CollectionLock): CollectionExposure = when {
    collection.unresolved -> CollectionExposure.UNKNOWN
    collection.collected == collection.quote.payable -> CollectionExposure.PAID
    collection.collected.minor > 0 -> CollectionExposure.PART_PAID
    else -> CollectionExposure.UNKNOWN // Resolved zero-collected NotPaid is conservatively unknown, never UNCOLLECTED.
}

private fun sameCheckoutAfterQuote(left: PricingSnapshot, right: PricingSnapshot): Boolean =
    StoredPricingSnapshotCodec.encode(left) == StoredPricingSnapshotCodec.encode(right)

private fun sameCheckoutAfterAllocations(left: List<StockAllocation>, right: List<StockAllocation>): Boolean =
    left.size == right.size && left.associateBy { it.allocationId } == right.associateBy { it.allocationId }

private fun sameCheckoutAfterReservation(left: StockReservation, right: StockReservation): Boolean =
    left.reservationId == right.reservationId && left.binding == right.binding && left.allocations == right.allocations &&
        left.revision == right.revision && left.status == right.status && left.holds == right.holds &&
        left.expiresAtEpochMillis == right.expiresAtEpochMillis

private fun sameCheckoutAfterIssue(left: StockIssue, right: StockIssue): Boolean =
    left.issueId == right.issueId && left.reservationId == right.reservationId && left.binding == right.binding &&
        left.allocations == right.allocations && left.purpose == right.purpose && left.revision == right.revision &&
        left.kitchen == null && right.kitchen == null

private fun sameCheckoutAfterMatrix(left: List<OriginalComponentTenderAllocation>, right: List<OriginalComponentTenderAllocation>): Boolean =
    left.size == right.size && left.zip(right).all { (first, second) -> first.componentKey == second.componentKey && first.shares == second.shares }

private fun checkoutAfterCheck(condition: Boolean, failure: CheckoutContractFailure = CheckoutContractFailure.BINDING_MISMATCH) {
    if (!condition) checkoutAfterReject(failure)
}

private fun checkoutAfterReject(failure: CheckoutContractFailure = CheckoutContractFailure.BINDING_MISMATCH): Nothing =
    throw CheckoutContractRejected(failure)

private inline fun <T> checkoutAfterGuard(block: () -> T): T = try {
    block()
} catch (failure: CancellationException) {
    throw failure
} catch (failure: CheckoutContractRejected) {
    throw failure
} catch (_: CharacterCodingException) {
    checkoutAfterReject(CheckoutContractFailure.MALFORMED)
} catch (_: IllegalArgumentException) {
    checkoutAfterReject(CheckoutContractFailure.INCONSISTENT)
} catch (_: ArithmeticException) {
    checkoutAfterReject(CheckoutContractFailure.INCONSISTENT)
} catch (_: IllegalStateException) {
    checkoutAfterReject(CheckoutContractFailure.INCONSISTENT)
} catch (_: NoSuchElementException) {
    checkoutAfterReject(CheckoutContractFailure.INCONSISTENT)
}
