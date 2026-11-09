package com.ukkera.pos.database

import com.ukkera.pos.inventory.CollectionExposure
import com.ukkera.pos.inventory.InventoryPlan
import com.ukkera.pos.inventory.ReservationIssueResult
import com.ukkera.pos.inventory.ReservationPlan
import com.ukkera.pos.inventory.ReservationSafetyResult
import com.ukkera.pos.inventory.StockReservation
import com.ukkera.pos.inventory.store.InventoryRetailObservedFacts
import com.ukkera.pos.pricing.PricingSnapshot
import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.sales.AttemptStatus
import com.ukkera.pos.sales.BegunCollection
import com.ukkera.pos.sales.CashAssignmentOpened
import com.ukkera.pos.sales.CashMovement
import com.ukkera.pos.sales.CashMovementKind
import com.ukkera.pos.sales.CashSession
import com.ukkera.pos.sales.CashSessionChanged
import com.ukkera.pos.sales.CashSessionStatus
import com.ukkera.pos.sales.CashTotals
import com.ukkera.pos.sales.CollectionChanged
import com.ukkera.pos.sales.CollectionLock
import com.ukkera.pos.sales.CollectionObservationRecorded
import com.ukkera.pos.sales.CollectionOutcome
import com.ukkera.pos.sales.CollectionStatus
import com.ukkera.pos.sales.CollectionWithCash
import com.ukkera.pos.sales.FinalizedSale
import com.ukkera.pos.sales.InventoryCommitKind
import com.ukkera.pos.sales.OrderChanged
import com.ukkera.pos.sales.OwnedOrder
import com.ukkera.pos.sales.PaymentAttempt
import com.ukkera.pos.sales.PaymentRecord
import com.ukkera.pos.sales.ReceiptEntitlementChanged
import com.ukkera.pos.sales.RetailStockRequirements
import com.ukkera.pos.sales.SalePosted
import com.ukkera.pos.sales.SalesAggregate
import com.ukkera.pos.sales.SalesCommand
import com.ukkera.pos.sales.SalesEffect
import com.ukkera.pos.sales.SalesRule
import com.ukkera.pos.sales.SalesRuleViolation
import com.ukkera.pos.sales.SalesSnapshotKind
import com.ukkera.pos.sales.SalesSnapshotLinks
import com.ukkera.pos.sales.SalesTransition
import com.ukkera.pos.sales.SalesUniqueCheck
import com.ukkera.pos.sales.SalesUniqueKind
import com.ukkera.pos.sales.SalesVersionCheck
import com.ukkera.pos.sales.Tender
import com.ukkera.pos.sales.store.SalesStorageRevision
import com.ukkera.pos.security.AuthorizedAction
import com.ukkera.pos.security.PrincipalKind
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/**
 * TEST ONLY. These are borrowed current facts, not retained before-images or capabilities.
 * The fixture reads them in the same writer transaction as authorization and commit.
 * No global pending index, authenticated import, or production cash-custody claim is made.
 */
internal data class CheckoutLiveCash(
    val row: CheckoutRow,
    val session: CashSession,
    val drawerRow: CheckoutRow,
    val drawer: CheckoutDrawerAssignment,
    val custodianRow: CheckoutRow,
    val custodian: CheckoutCustodianAssignment,
) {
    fun reference(after: CashSession = session): CheckoutCashRef = CheckoutCashRef(
        CheckoutRef(after.id, after.revision), drawer.revision, custodian.revision,
    )
}

internal data class CheckoutLiveOrder(val row: CheckoutRow, val order: OwnedOrder)

internal data class CheckoutLiveSales(
    val order: CheckoutLiveOrder,
    val collectionRow: CheckoutRow,
    val collection: CollectionLock,
    val cash: CheckoutLiveCash,
) {
    fun reference(
        afterOrder: OwnedOrder = order.order,
        afterCollection: CollectionLock = collection,
        afterCash: CashSession = cash.session,
    ): CheckoutSalesRef = CheckoutSalesRef(
        CheckoutRef(afterOrder.id, afterOrder.revision), CheckoutRef(afterCollection.id, afterCollection.revision),
        cash.reference(afterCash),
    )
}

internal suspend fun readCheckoutOrder(view: CommandReadView, id: DomainId): CheckoutLiveOrder {
    val row = checkoutCurrentRow(view, CheckoutFixtureProfile.salesKey(SalesSnapshotKind.ORDER, id))
    val order = CheckoutRows.order(row, view.scope, id)
    checkoutExistingClaim(view, SalesUniqueKind.ORDER, id, id)
    return CheckoutLiveOrder(row, order)
}

internal suspend fun readCheckoutCash(view: CommandReadView, id: DomainId): CheckoutLiveCash {
    val row = checkoutCurrentRow(view, CheckoutFixtureProfile.salesKey(SalesSnapshotKind.CASH_SESSION, id))
    val session = CheckoutRows.cash(row, view.scope, id)
    val drawerRow = checkoutCurrentRow(view, CheckoutFixtureProfile.drawerKey(session.drawerId))
    val custodianRow = checkoutCurrentRow(view, CheckoutFixtureProfile.custodianKey(session.custodianId))
    val drawer = CheckoutRows.drawer(drawerRow, view.scope, session.drawerId)
    val custodian = CheckoutRows.custodian(custodianRow, view.scope, session.custodianId)
    checkoutExistingClaim(view, SalesUniqueKind.CASH_SESSION, session.id, session.id)
    checkoutExistingClaim(view, SalesUniqueKind.OPENING_FLOAT_SOURCE, session.openingSourceId, session.id)
    checkoutExistingClaim(view, SalesUniqueKind.ACTIVE_DRAWER, session.drawerId, session.id)
    checkoutExistingClaim(view, SalesUniqueKind.ACTIVE_CUSTODIAN, session.custodianId, session.id)
    return CheckoutLiveCash(row, session, drawerRow, drawer, custodianRow, custodian)
}

internal suspend fun readCheckoutSales(view: CommandReadView, reference: CheckoutSalesRef): CheckoutLiveSales {
    val order = readCheckoutOrder(view, reference.order.id)
    val cash = readCheckoutCash(view, reference.cash.session.id)
    val collectionRow = checkoutCurrentRow(view, CheckoutFixtureProfile.salesKey(SalesSnapshotKind.COLLECTION, reference.collection.id))
    val collection = CheckoutRows.collection(collectionRow, view.scope, reference.collection.id, order.order, cash.session)
    checkoutExistingClaim(view, SalesUniqueKind.COLLECTION, collection.id, order.order.id)
    // Target records only. Other collections may legitimately be pending in this session.
    for (attempt in collection.attempts) {
        checkoutExistingClaim(view, SalesUniqueKind.PAYMENT_ATTEMPT, attempt.id, collection.id)
        val paymentOwner = view.claim(CheckoutFixtureProfile.claim(SalesUniqueKind.PAYMENT_RECORD, attempt.id))
        val cashOwner = view.claim(CheckoutFixtureProfile.claim(SalesUniqueKind.CASH_SOURCE, attempt.id))
        check(paymentOwner == if (attempt.payment == null) null else collection.id) { "checkout.current.payment-claim" }
        check(cashOwner == if (attempt.payment != null && attempt.tender == Tender.Cash) cash.session.id else null) {
            "checkout.current.cash-source"
        }
    }
    return CheckoutLiveSales(order, collectionRow, collection, cash)
}

internal suspend fun checkoutCurrentRow(view: CommandReadView, key: AggregateKey): CheckoutRow =
    CheckoutRow.from(checkNotNull(view.aggregate(key)) { "checkout.current.row-missing" })

private suspend fun checkoutExistingClaim(view: CommandReadView, kind: SalesUniqueKind, id: DomainId, owner: DomainId) {
    check(view.claim(CheckoutFixtureProfile.claim(kind, id)) == owner) { "checkout.current.claim" }
}

internal fun checkoutRequire(condition: Boolean, rule: SalesRule) {
    if (!condition) throw SalesRuleViolation(rule)
}

internal fun requireCheckoutCashCurrent(current: CheckoutLiveCash, expected: CheckoutCashRef, actor: DomainId) {
    val cash = current.session
    checkoutRequire(cash.id == expected.session.id && cash.revision == expected.session.revision &&
        current.drawer.revision == expected.drawerAssignmentRevision &&
        current.custodian.revision == expected.custodianAssignmentRevision, SalesRule.REVISION_CONFLICT)
    checkoutRequire(cash.custodianId == actor, SalesRule.OWNER_REQUIRED)
    checkoutRequire(cash.status == CashSessionStatus.OPEN, SalesRule.INVALID_STATE)
    checkoutRequire(current.drawer.activeSessionId == cash.id && current.custodian.activeSessionId == cash.id &&
        current.drawer.unresolvedClosingTransferId == null, SalesRule.CUSTODY_UNRESOLVED)
}

internal fun requireCheckoutSalesCurrent(current: CheckoutLiveSales, expected: CheckoutSalesRef, actor: DomainId) {
    checkoutRequire(current.order.order.id == expected.order.id && current.order.order.revision == expected.order.revision &&
        current.collection.id == expected.collection.id && current.collection.revision == expected.collection.revision,
        SalesRule.REVISION_CONFLICT)
    checkoutRequire(current.order.order.ownerId == actor && current.collection.collectorId == actor, SalesRule.OWNER_REQUIRED)
    requireCheckoutCashCurrent(current.cash, expected.cash, actor)
    SalesSnapshotLinks.requireCollection(current.collection, current.order.order, current.cash.session)
    checkoutRequire(current.collection.saleId == null && current.order.order.paidSaleId == null, SalesRule.IMMUTABLE_RECORD)
}

internal fun requireCheckoutReservationCurrent(
    observed: InventoryRetailObservedFacts,
    expected: CheckoutReservationRef,
    current: CheckoutLiveSales,
): StockReservation {
    checkoutRequire(observed.snapshot.revision == expected.inventoryRevision, SalesRule.REVISION_CONFLICT)
    val reservation = observed.snapshot.reservation(expected.reservation.id)
        ?: throw SalesRuleViolation(SalesRule.STOCK_PROOF_MISMATCH)
    checkoutRequire(reservation.revision == expected.reservation.revision, SalesRule.REVISION_CONFLICT)
    val collection = current.collection
    checkoutRequire(reservation.retainsStockClaim && reservation.reservationId == collection.reservationId &&
        reservation.binding.orderId == current.order.order.id && reservation.binding.collectionId == collection.id &&
        reservation.binding.originalActorId == collection.collectorId && reservation.binding.quoteRevision == collection.quote.revision &&
        reservation.allocations.size == collection.stockRequirements.allocations.size &&
        reservation.allocations.associateBy { it.allocationId } == collection.stockRequirements.allocations.associateBy { it.allocationId },
        SalesRule.STOCK_PROOF_MISMATCH)
    return reservation
}

/** Shared before BOTH final branches. No proof/token is returned and no safety decision is made. */
internal fun requireCheckoutFinalizationCurrent(
    current: CheckoutLiveSales,
    request: CheckoutRequest.Finalize,
    observed: InventoryRetailObservedFacts,
    actor: DomainId,
) {
    requireCheckoutSalesCurrent(current, request.sales, actor)
    checkoutRequire(!current.collection.unresolved, SalesRule.PAYMENT_UNRESOLVED)
    checkoutRequire(current.collection.collected == current.collection.quote.payable, SalesRule.INSUFFICIENT_COVERAGE)
    val reservation = requireCheckoutReservationCurrent(observed, request.stock, current)
    // Issued.proof omits exposure; checking it only after the planner would bypass Held admission.
    checkoutRequire(reservation.binding.exposure != CollectionExposure.UNCOLLECTED, SalesRule.STOCK_PROOF_MISMATCH)
}

internal fun checkoutDerivedExposure(collection: CollectionLock): CollectionExposure = when (collection.status) {
    CollectionStatus.OUTCOME_UNKNOWN -> CollectionExposure.UNKNOWN
    CollectionStatus.PART_PAID -> CollectionExposure.PART_PAID
    CollectionStatus.COVERED -> CollectionExposure.PAID
    CollectionStatus.LOCKED_UNPAID -> CollectionExposure.UNKNOWN
    CollectionStatus.FINALIZED_PAID -> throw SalesRuleViolation(SalesRule.IMMUTABLE_RECORD)
}

internal data class CheckoutSalesChanges(val changes: List<AggregateChange>, val claims: List<ClaimChange>)

internal fun translateCheckoutOpen(
    command: SalesCommand,
    request: CheckoutRequest.Open,
    drawerRow: CheckoutRow,
    drawer: CheckoutDrawerAssignment,
    custodianRow: CheckoutRow,
    custodian: CheckoutCustodianAssignment,
    transition: SalesTransition<CashSession>,
): CheckoutSalesChanges {
    val after = transition.value
    val actor = command.authorization.actorId
    val zero = Money(request.opening.currency, 0)
    check(after.id == request.sessionId && after.scope == drawer.scope && after.drawerId == drawer.drawerId &&
        after.custodianId == actor && after.businessDate == request.businessDate.value && after.revision == Revision(0) &&
        after.opening.amount == request.opening && after.opening.evidence == request.evidence.toDomain(actor) &&
        after.openingSourceId == request.openingSourceId && after.expectedCash == request.opening &&
        after.totals == CashTotals(zero, zero, zero, zero, zero, zero, zero, zero) && after.reservedCashOut == zero &&
        after.cashLedgerRevision == Revision(0) && after.status == CashSessionStatus.OPEN &&
        after.pendingAttemptIds.isEmpty() && after.pendingTransferIds.isEmpty() && after.closingCut == null &&
        after.closeReport == null && after.review == null) { "checkout.open.value" }
    val unique = listOf(SalesUniqueCheck(SalesUniqueKind.CASH_SESSION, after.id),
        SalesUniqueCheck(SalesUniqueKind.OPENING_FLOAT_SOURCE, after.openingSourceId),
        SalesUniqueCheck(SalesUniqueKind.ACTIVE_DRAWER, after.drawerId),
        SalesUniqueCheck(SalesUniqueKind.ACTIVE_CUSTODIAN, actor))
    requireCheckoutTransition(command, transition,
        listOf(SalesVersionCheck(SalesAggregate.DRAWER_ASSIGNMENT, drawer.drawerId, drawer.revision),
            SalesVersionCheck(SalesAggregate.CUSTODIAN_ASSIGNMENT, custodian.custodianId, custodian.revision)), unique,
        listOf(CashSessionChanged(after), CashAssignmentOpened(after.drawerId, actor, after.id)))
    val afterDrawer = drawer.copy(revision = drawer.revision.next(), activeSessionId = after.id)
    val afterCustodian = custodian.copy(revision = custodian.revision.next(), activeSessionId = after.id)
    return CheckoutSalesChanges(listOf(
        checkoutInsert(SalesSnapshotKind.CASH_SESSION, after.id, CheckoutRows.encodeCash(after)),
        checkoutUpdate(drawerRow, afterDrawer.revision, CheckoutRows.encodeDrawer(afterDrawer)),
        checkoutUpdate(custodianRow, afterCustodian.revision, CheckoutRows.encodeCustodian(afterCustodian)),
    ), unique.map { checkoutAcquire(it.kind, it.sourceId, after.id) })
}

internal fun translateCheckoutCreate(
    command: SalesCommand,
    request: CheckoutRequest.Create,
    quote: PricingSnapshot,
    stock: RetailStockRequirements,
    transition: SalesTransition<OwnedOrder>,
): CheckoutSalesChanges {
    val after = transition.value
    check(after.id == request.orderId && after.scope == command.authorization.requirement.scope &&
        after.revision == Revision(0) && after.originalCreatorId == command.authorization.actorId &&
        after.ownerId == command.authorization.actorId && after.quote === quote && after.stockRequirements === stock &&
        after.handoff == null && after.collectionId == null && after.paidSaleId == null) { "checkout.create.value" }
    requireCheckoutTransition(command, transition, emptyList(), listOf(SalesUniqueCheck(SalesUniqueKind.ORDER, after.id)),
        listOf(OrderChanged(after)))
    return CheckoutSalesChanges(listOf(checkoutInsert(SalesSnapshotKind.ORDER, after.id, CheckoutRows.encodeOrder(after))),
        listOf(checkoutAcquire(SalesUniqueKind.ORDER, after.id, after.id)))
}

internal fun translateCheckoutBegin(
    command: SalesCommand,
    request: CheckoutRequest.Begin,
    before: CheckoutLiveOrder,
    cash: CheckoutLiveCash,
    reservation: ReservationPlan,
    transition: SalesTransition<BegunCollection>,
): CheckoutSalesChanges {
    val after = transition.value
    requireCheckoutOrderUpdate(before.order, after.order, request.collectionId, null)
    val collection = after.collection
    check(collection.id == request.collectionId && collection.scope == before.order.scope && collection.orderId == before.order.id &&
        collection.lockedOrderRevision == after.order.revision && collection.originalCreatorId == before.order.originalCreatorId &&
        collection.collectorId == command.authorization.actorId && collection.cashSessionId == cash.session.id &&
        sameCheckoutQuote(collection.quote, before.order.quote) &&
        collection.stockRequirements.allocations == before.order.stockRequirements.allocations &&
        collection.reservationId == request.reservationId && collection.revision == Revision(0) &&
        collection.attempts.isEmpty() && collection.saleId == null) { "checkout.begin.value" }
    requireCheckoutTransition(command, transition, listOf(orderVersion(before.order), cashVersion(cash.session)),
        listOf(SalesUniqueCheck(SalesUniqueKind.COLLECTION, collection.id)),
        listOf(OrderChanged(after.order), CollectionChanged(collection)), reservation.plan, InventoryCommitKind.RESERVE, request.reservationId)
    return CheckoutSalesChanges(listOf(
        checkoutUpdate(before.row, after.order.revision, CheckoutRows.encodeOrder(after.order)),
        checkoutInsert(SalesSnapshotKind.COLLECTION, collection.id, CheckoutRows.encodeCollection(collection)),
    ), listOf(checkoutAcquire(SalesUniqueKind.COLLECTION, collection.id, before.order.id)))
}

internal fun translateCheckoutAttempt(
    command: SalesCommand,
    request: CheckoutRequest.Attempt,
    before: CheckoutLiveSales,
    safety: ReservationSafetyResult.Safe,
    transition: SalesTransition<CollectionWithCash>,
): CheckoutSalesChanges {
    val after = transition.value
    requireCheckoutCollectionUpdate(before.collection, after.collection, null)
    check(after.collection.attempts.size == before.collection.attempts.size + 1 &&
        after.collection.attempts.dropLast(1) == before.collection.attempts) { "checkout.attempt.prefix" }
    val attempt = after.collection.attempts.last()
    check(attempt.id == request.attemptId && attempt.originalOperationId == command.operationId &&
        attempt.originalActorId == before.collection.collectorId && attempt.originalCashSessionId == before.cash.session.id &&
        attempt.payerReference == request.payerReference && attempt.tender == request.tender.toDomain() && attempt.amount == request.amount &&
        attempt.status == AttemptStatus.AWAITING_ATTESTATION && attempt.latestObservation == null && attempt.payment == null) {
        "checkout.attempt.value"
    }
    requireCheckoutCashUpdate(before.cash.session, after.session, before.cash.session.totals,
        before.cash.session.expectedCash, before.cash.session.cashLedgerRevision, before.cash.session.pendingAttemptIds + attempt.id)
    requireCheckoutTransition(command, transition, listOf(collectionVersion(before.collection), cashVersion(before.cash.session)),
        listOf(SalesUniqueCheck(SalesUniqueKind.PAYMENT_ATTEMPT, attempt.id)),
        listOf(CollectionChanged(after.collection), CashSessionChanged(after.session)),
        safety.plan, InventoryCommitKind.REVALIDATE, before.collection.reservationId)
    return CheckoutSalesChanges(listOf(
        checkoutUpdate(before.collectionRow, after.collection.revision, CheckoutRows.encodeCollection(after.collection)),
        checkoutUpdate(before.cash.row, after.session.revision, CheckoutRows.encodeCash(after.session)),
    ), listOf(checkoutAcquire(SalesUniqueKind.PAYMENT_ATTEMPT, attempt.id, before.collection.id)))
}

/**
 * Every effect is matched, including the journal-represented observation/payment/movement.
 * This live before/after arithmetic is NOT claimed by the retained after-only verifier.
 */
internal fun translateCheckoutOutcome(
    command: SalesCommand,
    request: CheckoutRequest.Outcome,
    before: CheckoutLiveSales,
    outcome: CollectionOutcome,
    transition: SalesTransition<CollectionWithCash>,
): CheckoutSalesChanges {
    val after = transition.value
    val index = before.collection.attempts.indexOfFirst { it.id == request.attemptId }
    check(index >= 0) { "checkout.outcome.attempt" }
    val oldAttempt = before.collection.attempts[index]
    requireCheckoutCollectionUpdate(before.collection, after.collection, null)
    check(after.collection.attempts.size == before.collection.attempts.size) { "checkout.outcome.attempt-count" }
    before.collection.attempts.indices.filter { it != index }.forEach {
        check(after.collection.attempts[it] === before.collection.attempts[it]) { "checkout.outcome.unrelated-attempt" }
    }
    val attempt = after.collection.attempts[index]
    requireCheckoutAttemptIdentity(oldAttempt, attempt)
    check(attempt.latestObservation == outcome) { "checkout.outcome.observation" }
    val expectedEffects = mutableListOf<SalesEffect>(CollectionChanged(after.collection))
    val unique = mutableListOf<SalesUniqueCheck>()
    val claims = mutableListOf<ClaimChange>()
    val changes = mutableListOf(checkoutUpdate(before.collectionRow, after.collection.revision, CheckoutRows.encodeCollection(after.collection)))
    val unknown = outcome is CollectionOutcome.Unknown
    if (unknown) {
        check(after.session === before.cash.session && attempt.status == AttemptStatus.OUTCOME_UNKNOWN && attempt.payment == null) {
            "checkout.outcome.unknown"
        }
    } else {
        expectedEffects += CashSessionChanged(after.session)
        changes += checkoutUpdate(before.cash.row, after.session.revision, CheckoutRows.encodeCash(after.session))
    }
    expectedEffects += CollectionObservationRecorded(attempt.id, outcome)
    var totals = before.cash.session.totals
    var expectedCash = before.cash.session.expectedCash
    var ledger = before.cash.session.cashLedgerRevision
    when (outcome) {
        is CollectionOutcome.Unknown -> Unit
        is CollectionOutcome.NotPaid -> check(attempt.status == AttemptStatus.NOT_PAID && attempt.payment == null) { "checkout.outcome.not-paid" }
        is CollectionOutcome.CashReceived -> {
            val payment = requireCheckoutPayment(command, before.collection, oldAttempt, attempt, outcome)
            expectedEffects += payment
            unique += SalesUniqueCheck(SalesUniqueKind.PAYMENT_RECORD, attempt.id)
            unique += SalesUniqueCheck(SalesUniqueKind.CASH_SOURCE, attempt.id)
            claims += checkoutAcquire(SalesUniqueKind.PAYMENT_RECORD, attempt.id, before.collection.id)
            claims += checkoutAcquire(SalesUniqueKind.CASH_SOURCE, attempt.id, before.cash.session.id)
            // Selecting this one effect does not discard anything: the COMPLETE list is matched below.
            val movement = transition.effects.singleOrNull { it is CashMovement } as? CashMovement
                ?: error("checkout.outcome.movement")
            check(movement.sourceId == attempt.id && movement.operationId == command.operationId &&
                movement.sessionId == before.cash.session.id && movement.custodianId == command.authorization.actorId &&
                movement.kind == CashMovementKind.CUSTOMER_RECEIPT && movement.delta == attempt.amount &&
                movement.cashReceived == outcome.received && movement.changeGiven == outcome.change &&
                movement.evidence == outcome.evidence && movement.reason == null &&
                movement.delta == outcome.received - outcome.change) { "checkout.outcome.cash-movement" }
            expectedEffects += movement
            totals = totals.copy(received = totals.received + outcome.received, change = totals.change + outcome.change)
            expectedCash += attempt.amount
            ledger = ledger.next()
        }
        is CollectionOutcome.ExternalAttested -> {
            expectedEffects += requireCheckoutPayment(command, before.collection, oldAttempt, attempt, outcome)
            unique += SalesUniqueCheck(SalesUniqueKind.PAYMENT_RECORD, attempt.id)
            claims += checkoutAcquire(SalesUniqueKind.PAYMENT_RECORD, attempt.id, before.collection.id)
            totals = totals.copy(externalCollections = totals.externalCollections + attempt.amount)
        }
    }
    if (!unknown) requireCheckoutCashUpdate(before.cash.session, after.session, totals, expectedCash, ledger,
        before.cash.session.pendingAttemptIds - attempt.id)
    requireCheckoutTransition(command, transition, listOf(collectionVersion(before.collection), cashVersion(before.cash.session)),
        unique, expectedEffects)
    return CheckoutSalesChanges(changes, claims)
}

internal fun translateCheckoutFinalized(
    command: SalesCommand,
    request: CheckoutRequest.Finalize,
    before: CheckoutLiveSales,
    issue: ReservationIssueResult.Issued,
    transition: SalesTransition<FinalizedSale>,
): CheckoutSalesChanges {
    val after = transition.value
    requireCheckoutOrderUpdate(before.order.order, after.order, before.collection.id, request.saleId)
    requireCheckoutCollectionUpdate(before.collection, after.collection, request.saleId)
    check(after.collection.attempts == before.collection.attempts) { "checkout.finalize.money-preserved" }
    val sale = after.sale
    check(sale.id == request.saleId && sale.scope == before.order.order.scope && sale.orderId == before.order.order.id &&
        sale.originalCreatorId == before.order.order.originalCreatorId && sale.collectorId == before.collection.collectorId &&
        sale.collectingSessionId == before.cash.session.id && sameCheckoutQuote(sale.quote, before.collection.quote) &&
        sale.stockIssueId == request.issueId && sale.stockIssueId == issue.proof.issueId &&
        sale.payments == before.collection.attempts.mapNotNull { it.payment }) { "checkout.finalize.sale" }
    val entitlement = after.entitlement
    check(entitlement.sale === sale && entitlement.revision == Revision(0) &&
        entitlement.cursors.all { it.consumedQuantity.scaled == 0L } &&
        entitlement.remainingComponentTenders.size == sale.originalTenderAllocations.size &&
        entitlement.remainingComponentTenders.zip(sale.originalTenderAllocations).all { (left, right) ->
            left.componentKey == right.componentKey && left.shares == right.shares
        }) { "checkout.finalize.entitlement" }
    requireCheckoutTransition(command, transition, listOf(orderVersion(before.order.order), collectionVersion(before.collection)),
        listOf(SalesUniqueCheck(SalesUniqueKind.SALE, sale.id), SalesUniqueCheck(SalesUniqueKind.SALE_ORDER, before.order.order.id),
            SalesUniqueCheck(SalesUniqueKind.RETURN_ENTITLEMENT, sale.id)),
        listOf(OrderChanged(after.order), CollectionChanged(after.collection), SalePosted(sale), ReceiptEntitlementChanged(entitlement)),
        issue.plan, InventoryCommitKind.RETAIL_ISSUE, before.collection.reservationId, request.issueId)
    return CheckoutSalesChanges(listOf(
        checkoutUpdate(before.order.row, after.order.revision, CheckoutRows.encodeOrder(after.order)),
        checkoutUpdate(before.collectionRow, after.collection.revision, CheckoutRows.encodeCollection(after.collection)),
        checkoutInsert(SalesSnapshotKind.PAID_SALE, sale.id, CheckoutRows.encodeSale(sale)),
        checkoutInsert(SalesSnapshotKind.RECEIPT_ENTITLEMENT, sale.id, CheckoutRows.encodeEntitlement(entitlement)),
    ), listOf(checkoutAcquire(SalesUniqueKind.SALE, sale.id, before.order.order.id),
        checkoutAcquire(SalesUniqueKind.SALE_ORDER, before.order.order.id, sale.id),
        checkoutAcquire(SalesUniqueKind.RETURN_ENTITLEMENT, sale.id, sale.id)))
}

private fun requireCheckoutTransition(
    command: SalesCommand,
    actual: SalesTransition<*>,
    versions: List<SalesVersionCheck>,
    unique: List<SalesUniqueCheck>,
    effects: List<SalesEffect>,
    plan: InventoryPlan? = null,
    kind: InventoryCommitKind? = null,
    reservationId: DomainId? = null,
    issueId: DomainId? = null,
) {
    // Exact list equality rejects every omitted, extra, duplicate or different effect/version/unique.
    check(actual.command === command && actual.versions == versions && actual.unique == unique && actual.effects == effects) {
        "checkout.transition.complete-effects"
    }
    if (plan == null) check(actual.inventoryDependencies.isEmpty()) { "checkout.transition.unexpected-inventory" }
    else {
        val dependency = actual.inventoryDependencies.singleOrNull() ?: error("checkout.transition.inventory-count")
        check(dependency.plan === plan && dependency.kind == kind && dependency.reservationId == reservationId &&
            dependency.issueId == issueId && dependency.operationId == command.operationId &&
            dependency.expectedInventoryRevision == plan.requiredInventoryRevision) { "checkout.transition.inventory-identity" }
    }
}

private fun requireCheckoutOrderUpdate(before: OwnedOrder, after: OwnedOrder, collectionId: DomainId?, saleId: DomainId?) {
    check(after.id == before.id && after.scope == before.scope && after.originalCreatorId == before.originalCreatorId &&
        after.ownerId == before.ownerId && after.revision == before.revision.next() && sameCheckoutQuote(after.quote, before.quote) &&
        after.stockRequirements.allocations == before.stockRequirements.allocations && after.handoff == before.handoff &&
        after.collectionId == collectionId && after.paidSaleId == saleId) { "checkout.order.update" }
}

private fun requireCheckoutCollectionUpdate(before: CollectionLock, after: CollectionLock, saleId: DomainId?) {
    check(after.id == before.id && after.scope == before.scope && after.orderId == before.orderId &&
        after.lockedOrderRevision == before.lockedOrderRevision && after.originalCreatorId == before.originalCreatorId &&
        after.collectorId == before.collectorId && after.cashSessionId == before.cashSessionId &&
        sameCheckoutQuote(after.quote, before.quote) && after.stockRequirements.allocations == before.stockRequirements.allocations &&
        after.reservationId == before.reservationId && after.revision == before.revision.next() && after.saleId == saleId) {
        "checkout.collection.update"
    }
}

private fun requireCheckoutAttemptIdentity(before: PaymentAttempt, after: PaymentAttempt) {
    check(after.id == before.id && after.originalOperationId == before.originalOperationId &&
        after.originalActorId == before.originalActorId && after.originalCashSessionId == before.originalCashSessionId &&
        after.payerReference == before.payerReference && after.tender == before.tender && after.amount == before.amount) {
        "checkout.payment.original-identity"
    }
}

private fun requireCheckoutPayment(
    command: SalesCommand,
    collection: CollectionLock,
    before: PaymentAttempt,
    after: PaymentAttempt,
    outcome: CollectionOutcome,
): PaymentRecord {
    val payment = checkNotNull(after.payment) { "checkout.payment.missing" }
    check(after.status == AttemptStatus.ATTESTED_PAID && payment.attemptId == before.id &&
        payment.originalOperationId == before.originalOperationId && payment.recordingOperationId == command.operationId &&
        payment.collectionId == collection.id && payment.collectorId == before.originalActorId &&
        payment.cashSessionId == before.originalCashSessionId && payment.payerReference == before.payerReference &&
        payment.tender == before.tender && payment.amount == before.amount && payment.attestation.actorId == command.authorization.actorId) {
        "checkout.payment.identity"
    }
    when (outcome) {
        is CollectionOutcome.CashReceived -> check(payment.tender == Tender.Cash && payment.cashReceived == outcome.received &&
            payment.changeGiven == outcome.change && payment.attestation == outcome.evidence && payment.externalEvidence == null &&
            payment.amount == outcome.received - outcome.change) { "checkout.payment.cash" }
        is CollectionOutcome.ExternalAttested -> check(payment.tender is Tender.External && payment.cashReceived == null &&
            payment.changeGiven == null && payment.attestation == outcome.evidence.attestation && payment.externalEvidence == outcome.evidence) {
            "checkout.payment.external"
        }
        is CollectionOutcome.Unknown, is CollectionOutcome.NotPaid -> error("checkout.payment.unpaid")
    }
    return payment
}

private fun requireCheckoutCashUpdate(
    before: CashSession,
    after: CashSession,
    totals: CashTotals,
    expectedCash: Money,
    ledgerRevision: Revision,
    pending: List<DomainId>,
) {
    check(after.id == before.id && after.scope == before.scope && after.drawerId == before.drawerId &&
        after.custodianId == before.custodianId && after.businessDate == before.businessDate && after.opening == before.opening &&
        after.openingSourceId == before.openingSourceId && after.revision == before.revision.next() &&
        before.status == CashSessionStatus.OPEN && after.status == before.status && after.totals == totals &&
        after.expectedCash == expectedCash && after.cashLedgerRevision == ledgerRevision && after.reservedCashOut == before.reservedCashOut &&
        after.pendingAttemptIds == pending && after.pendingTransferIds == before.pendingTransferIds &&
        after.closingCut == before.closingCut && after.closeReport == before.closeReport && after.review == before.review) {
        "checkout.cash.live-delta"
    }
}

private fun sameCheckoutQuote(first: PricingSnapshot, second: PricingSnapshot): Boolean =
    StoredPricingSnapshotCodec.encode(first) == StoredPricingSnapshotCodec.encode(second)

private fun orderVersion(order: OwnedOrder) = SalesVersionCheck(SalesAggregate.ORDER, order.id, order.revision)
private fun collectionVersion(collection: CollectionLock) = SalesVersionCheck(SalesAggregate.COLLECTION, collection.id, collection.revision)
private fun cashVersion(cash: CashSession) = SalesVersionCheck(SalesAggregate.CASH_SESSION, cash.id, cash.revision)

private fun checkoutAcquire(kind: SalesUniqueKind, id: DomainId, owner: DomainId): ClaimChange.Acquire =
    ClaimChange.Acquire(CheckoutFixtureProfile.claim(kind, id), owner)

private fun checkoutInsert(kind: SalesSnapshotKind, id: DomainId, bytes: StoredBytes): AggregateChange =
    AggregateChange(CheckoutFixtureProfile.salesKey(kind, id), null, CheckoutFixtureProfile.schema(kind), bytes)

private fun checkoutUpdate(before: CheckoutRow, afterRevision: Revision, bytes: StoredBytes): AggregateChange {
    check(SalesStorageRevision.toStorage(afterRevision) == before.storageGeneration.next()) { "checkout.storage.successor" }
    return AggregateChange(before.key, before.storageGeneration, before.schema, bytes)
}

internal class CheckoutCapacityExceeded : IllegalArgumentException("checkout.capacity")

/** Budget the WHOLE actual binary envelope, not just the inventory contribution's two payloads. */
internal fun checkoutApply(
    context: CommandContext,
    normalized: StoredBytes,
    authorization: AuthorizedAction,
    recordedAt: UtcInstant,
    result: StoredBytes,
    changes: List<AggregateChange>,
    claims: List<ClaimChange>,
    deadline: UtcInstant? = null,
): ProposedOutcome.Apply {
    check(authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE) { "checkout.actor.kind" }
    fun utf8(value: String) = value.encodeToByteArray(throwOnInvalidSequence = true).size.toLong()
    fun framed(fields: List<Long>): Long = fields.sumOf { 4L + it }
    if (changes.any { it.payload.size > 65_536 } || result.size > 65_536 || normalized.size > 65_536) throw CheckoutCapacityExceeded()
    val effectSizes = changes.map { change ->
        framed(listOf(utf8(change.key.kind), utf8(change.key.id.value),
            utf8((change.expectedRevision ?: Revision(0)).next().value.toString()), utf8(change.schema), change.payload.size.toLong()))
    } + claims.map { claim -> framed(listOf(utf8(if (claim is ClaimChange.Acquire) "claim.acquire" else "claim.release"),
        utf8(claim.key.namespace), utf8(claim.key.key), utf8(claim.ownerId.value))) }
    // Only sequence is unavailable to a handler. Its longest valid decimal spelling is a safe upper bound.
    val headers = listOf("ukkera.journal/v1", context.scope.storageKey(), TEST_AUTHORITY.assignmentId.value,
        TEST_AUTHORITY.epoch.value.toString(), Long.MAX_VALUE.toString(), context.operationId.value, context.originalActorId.value,
        context.key.name, context.key.version.toString(), authorization.authenticationId.value, authorization.policyRevision.value.toString(),
        context.semanticDigest.value, "committed", recordedAt.toString(), "0".repeat(64), "0".repeat(64))
    val total = framed(headers.map(::utf8) + listOf(normalized.size.toLong(), result.size.toLong(), framed(effectSizes), 0L))
    if (total > 1_048_576L) throw CheckoutCapacityExceeded()
    return ProposedOutcome.Apply(result, changes, claims, emptyList(), deadline)
}
