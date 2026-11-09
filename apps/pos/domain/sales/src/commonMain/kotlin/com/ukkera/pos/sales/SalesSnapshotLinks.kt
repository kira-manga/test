package com.ukkera.pos.sales

import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.types.AllocationWeight
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.allocateByLargestRemainder

/**
 * Cross-row consistency guards for a protected, authoritative read cut. Caller
 * completeness must be established by durable indexes/transactions, not by these
 * lists or client claims. These checks do not establish provenance or admit a
 * stale restore; recovery resource reconciliation remains mandatory.
 */
public object SalesSnapshotLinks {
    public fun requireCollection(collection: CollectionLock, order: OwnedOrder, session: CashSession): Unit = checkedSnapshot {
        validateStoredCollection(collection)
        validateStoredOrder(order)
        validateStoredCash(session)
        snapshotCheck(collection.scope == order.scope && collection.scope == session.scope && collection.orderId == order.id &&
            collection.originalCreatorId == order.originalCreatorId && collection.collectorId == order.ownerId &&
            collection.cashSessionId == session.id && collection.collectorId == session.custodianId && order.collectionId == collection.id &&
            order.handoff == null && order.paidSaleId == collection.saleId)
        snapshotCheck(StoredPricingSnapshotCodec.encode(order.quote) == StoredPricingSnapshotCodec.encode(collection.quote) &&
            order.priceSourceReference == collection.priceSourceReference &&
            order.returnPolicySourceReference == collection.returnPolicySourceReference)
        snapshotCheck(order.stockRequirements.sameSource(collection.stockRequirements))
        snapshotCheck(if (collection.saleId == null) order.revision == collection.lockedOrderRevision && session.status == CashSessionStatus.OPEN
            else order.revision == collection.lockedOrderRevision.next())
        for (attempt in collection.attempts) {
            snapshotCheck((attempt.id in session.pendingAttemptIds) == attempt.status.isUnresolved())
        }
    }

    public fun requireSafeTransfer(transfer: SafeTransfer, session: CashSession): Unit = checkedSnapshot {
        validateStoredTransfer(transfer)
        validateStoredCash(session)
        snapshotCheck(transfer.scope == session.scope && transfer.senderSessionId == session.id &&
            transfer.senderDrawerId == session.drawerId && transfer.senderCustodianId == session.custodianId)
        sameCurrency(session.expectedCash, transfer.amount)
        if (transfer.closingDisposition) {
            val report = session.closeReport
            snapshotCheck(report != null && report.safeTransferId == transfer.id && report.actualSafeDelivery == transfer.amount)
        } else {
            snapshotCheck((transfer.id in session.pendingTransferIds) == (transfer.status == SafeTransferStatus.AWAITING_RECEIPT))
            snapshotCheck(transfer.amount.minor <= session.totals.safeDrops.minor)
        }
    }

    /**
     * Supply the COMPLETE current pending-source index for this session. Other
     * settled attempts may be present; attempts for other paying sessions are not
     * counted. A missing/extra pending attempt or cash reservation rejects.
     */
    public fun requireCashSessionPending(
        session: CashSession,
        collections: List<CollectionLock>,
        acceptedReturns: List<AcceptedReturn>,
        pendingTransfers: List<SafeTransfer>,
    ): Unit = checkedSnapshot {
        snapshotCheck(collections.size <= 128 && acceptedReturns.size <= 128 && pendingTransfers.size <= 128, SalesSnapshotFailure.TOO_LARGE)
        validateStoredCash(session)
        snapshotCheck(collections.map { it.id }.distinct().size == collections.size &&
            acceptedReturns.map { it.id }.distinct().size == acceptedReturns.size && pendingTransfers.map { it.id }.distinct().size == pendingTransfers.size)
        val pending = mutableSetOf<DomainId>()
        var reserved = session.expectedCash.zero()
        for (collection in collections) {
            validateStoredCollection(collection)
            snapshotCheck(collection.scope == session.scope && collection.cashSessionId == session.id && collection.collectorId == session.custodianId)
            for (attempt in collection.attempts) {
                if (attempt.status.isUnresolved()) snapshotCheck(pending.add(attempt.id))
                else snapshotCheck(attempt.id !in session.pendingAttemptIds)
            }
        }
        for (accepted in acceptedReturns) {
            validateStoredReturn(accepted)
            snapshotCheck(accepted.scope == session.scope)
            for (attempt in accepted.attempts.filter { it.originalCashSessionId == session.id }) {
                snapshotCheck(attempt.originalActorId == session.custodianId)
                if (attempt.status.isUnresolved()) {
                    snapshotCheck(pending.add(attempt.id))
                    if (attempt.tender == Tender.Cash) reserved += attempt.amount
                } else snapshotCheck(attempt.id !in session.pendingAttemptIds)
            }
        }
        snapshotCheck(pending == session.pendingAttemptIds.toSet() && reserved == session.reservedCashOut)
        snapshotCheck(pendingTransfers.map { it.id }.toSet() == session.pendingTransferIds.toSet())
        var pendingSafeAmount = session.expectedCash.zero()
        for (transfer in pendingTransfers) {
            snapshotCheck(!transfer.closingDisposition && transfer.status == SafeTransferStatus.AWAITING_RECEIPT)
            requireSafeTransfer(transfer, session)
            pendingSafeAmount += transfer.amount
        }
        snapshotCheck(pendingSafeAmount.minor <= session.totals.safeDrops.minor)
    }

    public fun requireClaim(claim: ReturnClaim, entitlement: ReceiptEntitlement): Unit = checkedSnapshot {
        validateStoredEntitlement(entitlement)
        validateClaimLinks(claim, entitlement)
    }

    public fun requireAcceptedReturn(accepted: AcceptedReturn, entitlement: ReceiptEntitlement, claim: ReturnClaim): Unit = checkedSnapshot {
        validateStoredEntitlement(entitlement)
        validateAcceptedLinks(accepted, entitlement, claim)
    }

    /**
     * Optional bounded COMPLETE-history verification for protected restore/audit.
     * Replays only ORIGINAL cumulative quantities and posted tender allocations,
     * never current prices, money movements or authorizations. No gaps/overlaps,
     * duplicated claims or regenerated remaining rights are permitted. Histories
     * beyond 1,024 acceptances need a separately qualified normalized verifier.
     */
    public fun requireReceiptHistory(
        entitlement: ReceiptEntitlement,
        acceptedReturns: List<AcceptedReturn>,
        acceptedClaims: List<ReturnClaim>,
    ): Unit = checkedSnapshot {
        snapshotCheck(acceptedReturns.size <= 1_024 && acceptedClaims.size <= 1_024, SalesSnapshotFailure.TOO_LARGE)
        validateStoredEntitlement(entitlement)
        snapshotCheck(entitlement.revision.value == acceptedReturns.size.toLong())
        snapshotCheck(acceptedReturns.map { it.id }.distinct().size == acceptedReturns.size &&
            acceptedReturns.map { it.claimId }.distinct().size == acceptedReturns.size &&
            acceptedClaims.map { it.id }.distinct().size == acceptedClaims.size &&
            acceptedReturns.map { it.claimId }.toSet() == acceptedClaims.map { it.id }.toSet())
        val claims = acceptedClaims.associateBy { it.id }
        val cursors = entitlement.sale.quote.lines.associate { it.portionId to entitlement.sale.quote.openReturnCursor(it.portionId) }.toMutableMap()
        val balances = entitlement.sale.originalTenderAllocations.associateBy { it.componentKey }.toMutableMap()
        for (accepted in acceptedReturns.sortedWith(compareBy({ it.portionId.value }, { it.consumedQuantityBefore.scaled }))) {
            validateAcceptedLinks(accepted, entitlement, claims.getValue(accepted.claimId))
            val cursor = cursors.getValue(accepted.portionId)
            snapshotCheck(cursor.consumedQuantity == accepted.consumedQuantityBefore)
            val slice = cursor.consume(accepted.quantity)
            val tenderDue = mutableMapOf<DomainId, Money>()
            for (component in slice.components) {
                val row = balances.getValue(component.key)
                val used = allocateByLargestRemainder(component.amount, row.shares.map { AllocationWeight(it.paymentId.value, it.amount.minor) })
                    .associateBy { it.key }
                val remaining = row.shares.map { share ->
                    val amount = used.getValue(share.paymentId.value).amount
                    snapshotCheck(amount.minor <= share.amount.minor)
                    tenderDue[share.paymentId] = (tenderDue[share.paymentId] ?: amount.zero()) + amount
                    OriginalTenderShare(share.paymentId, share.amount - amount)
                }
                balances[component.key] = OriginalComponentTenderAllocation(component.key, remaining)
            }
            snapshotCheck(accepted.tenderObligations.associate { it.originalPaymentId to it.amount } == tenderDue.filterValues { it.minor > 0L })
            cursors[accepted.portionId] = slice.remaining
        }
        snapshotCheck(entitlement.cursors.all { cursors.getValue(it.portionId).consumedQuantity == it.consumedQuantity })
        snapshotCheck(entitlement.remainingComponentTenders.all { row ->
            row.shares.associate { it.paymentId to it.amount } == balances.getValue(row.componentKey).shares.associate { it.paymentId to it.amount }
        })
    }
}

private fun validateClaimLinks(claim: ReturnClaim, entitlement: ReceiptEntitlement) {
    validateStoredClaim(claim)
    snapshotCheck(claim.scope == entitlement.scope && claim.saleId == entitlement.saleId)
    val cursor = entitlement.cursors.singleOrNull { it.portionId == claim.portionId }
        ?: throw SalesSnapshotRejectedException(SalesSnapshotFailure.INCONSISTENT_STATE)
    snapshotCheck(claim.quantity.unitId == cursor.originalQuantity.unitId && claim.quantity.scale == cursor.originalQuantity.scale)
    // Larger/disputed/unpaid claims are valid records; only accepted claims consume rights.
    snapshotCheck(claim.acceptedReturnId == null || claim.quantity <= cursor.consumedQuantity)
}

private fun validateAcceptedLinks(accepted: AcceptedReturn, entitlement: ReceiptEntitlement, claim: ReturnClaim) {
    validateStoredReturn(accepted)
    validateClaimLinks(claim, entitlement)
    snapshotCheck(accepted.scope == entitlement.scope && accepted.saleId == entitlement.saleId &&
        accepted.claimId == claim.id && claim.acceptedReturnId == accepted.id && accepted.portionId == claim.portionId &&
        accepted.quantity == claim.quantity && accepted.evidence == claim.evidence)
    val cursor = restoreOriginalCursor(entitlement.sale.quote, accepted.portionId, accepted.consumedQuantityBefore)
    val slice = cursor.consume(accepted.quantity)
    snapshotCheck(slice.payable == accepted.originalPayable && slice.components == accepted.components)
    snapshotCheck(slice.remaining.consumedQuantity <= entitlement.cursors.single { it.portionId == accepted.portionId }.consumedQuantity)
    val rows = entitlement.sale.originalTenderAllocations.associateBy { it.componentKey }
    for (obligation in accepted.tenderObligations) {
        val originalPayment = entitlement.sale.payments.singleOrNull { it.attemptId == obligation.originalPaymentId }
            ?: throw SalesSnapshotRejectedException(SalesSnapshotFailure.INCONSISTENT_STATE)
        snapshotCheck(originalPayment.tender == obligation.tender)
        var componentCapacity = accepted.originalPayable.zero()
        for (component in accepted.components) {
            val share = rows.getValue(component.key).shares.single { it.paymentId == obligation.originalPaymentId }.amount
            componentCapacity += Money(share.currency, minOf(share.minor, component.amount.minor))
        }
        val stillAvailable = entitlement.remainingComponentTenders.fold(originalPayment.amount.zero()) { sum, row ->
            sum + row.shares.single { it.paymentId == obligation.originalPaymentId }.amount
        }
        snapshotCheck(obligation.amount.minor <= componentCapacity.minor && obligation.amount.minor <= (originalPayment.amount - stillAvailable).minor)
    }
}
