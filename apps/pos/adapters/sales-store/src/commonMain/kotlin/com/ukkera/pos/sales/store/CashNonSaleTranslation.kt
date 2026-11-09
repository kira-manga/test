package com.ukkera.pos.sales.store

import com.ukkera.pos.sales.AcceptedReturnChanged
import com.ukkera.pos.sales.CashAssignmentOpened
import com.ukkera.pos.sales.CashAssignmentReleased
import com.ukkera.pos.sales.CashCountRecorded
import com.ukkera.pos.sales.CashMovement
import com.ukkera.pos.sales.CashSession
import com.ukkera.pos.sales.CashSessionChanged
import com.ukkera.pos.sales.CashSessionStatus
import com.ukkera.pos.sales.CashierEvidence
import com.ukkera.pos.sales.ClosingCustodyReleased
import com.ukkera.pos.sales.CollectionChanged
import com.ukkera.pos.sales.CollectionObservationRecorded
import com.ukkera.pos.sales.HandoffAccepted
import com.ukkera.pos.sales.HandoffCancelled
import com.ukkera.pos.sales.HandoffOffered
import com.ukkera.pos.sales.InventoryCommitDependency
import com.ukkera.pos.sales.OrderChanged
import com.ukkera.pos.sales.OrderReassigned
import com.ukkera.pos.sales.PaymentRecord
import com.ukkera.pos.sales.ReceiptEntitlementChanged
import com.ukkera.pos.sales.RefundObservationRecorded
import com.ukkera.pos.sales.RefundRecord
import com.ukkera.pos.sales.ReturnClaimChanged
import com.ukkera.pos.sales.SafeCustodyReceipt
import com.ukkera.pos.sales.SafeTransferChanged
import com.ukkera.pos.sales.SalePosted
import com.ukkera.pos.sales.SalesAggregate
import com.ukkera.pos.sales.SalesCommand
import com.ukkera.pos.sales.SalesEffect
import com.ukkera.pos.sales.SalesUniqueCheck
import com.ukkera.pos.sales.SalesUniqueKind
import com.ukkera.pos.sales.SalesVersionCheck
import com.ukkera.pos.types.Money

/**
 * Fail closed on every dependency/effect not implemented by this exact profile.
 * Parts are passed separately solely to exercise this guard with adversarial pure
 * test fixtures; production always supplies the actual immutable domain proposal.
 */
internal fun validateNonSaleTransition(
    expectedCommand: SalesCommand,
    before: CashSession,
    request: CashNonSaleRequestV1,
    actualCommand: SalesCommand,
    after: CashSession,
    versions: List<SalesVersionCheck>,
    unique: List<SalesUniqueCheck>,
    effects: List<SalesEffect>,
    inventoryDependencies: List<InventoryCommitDependency>,
): CashMovement? {
    if (actualCommand != expectedCommand || before.id != request.sessionId || before.revision != request.expectedDomainRevision ||
        versions != listOf(SalesVersionCheck(SalesAggregate.CASH_SESSION, before.id, before.revision)) ||
        unique != listOf(SalesUniqueCheck(SalesUniqueKind.CASH_SOURCE, request.sourceId)) ||
        inventoryDependencies.isNotEmpty() || effects.size != 2) return null
    var changed: CashSessionChanged? = null
    var moved: CashMovement? = null
    for (effect in effects) when (effect) {
        is CashSessionChanged -> {
            if (changed != null) return null
            changed = effect
        }
        is CashMovement -> {
            if (moved != null) return null
            moved = effect
        }
        // No else: a new domain effect requires a conscious adapter decision.
        is CashAssignmentOpened, is CashAssignmentReleased, is ClosingCustodyReleased, is CashCountRecorded,
        is SafeTransferChanged, is SafeCustodyReceipt,
        is OrderChanged, is OrderReassigned, is HandoffOffered, is HandoffCancelled, is HandoffAccepted,
        is CollectionChanged, is CollectionObservationRecorded, is PaymentRecord, is SalePosted,
        is ReceiptEntitlementChanged, is ReturnClaimChanged, is AcceptedReturnChanged,
        is RefundObservationRecorded, is RefundRecord -> return null
    }
    val movement = moved ?: return null
    if (changed?.session !== after) return null
    val increase = request.kind == NonSaleCashKind.NON_SALE_PAY_IN
    val delta = if (increase) request.amount else -request.amount
    val zero = Money(request.amount.currency, 0)
    if (movement.sourceId != request.sourceId || movement.operationId != expectedCommand.operationId ||
        movement.sessionId != before.id || movement.custodianId != before.custodianId ||
        movement.custodianId != expectedCommand.authorization.actorId || movement.kind != request.kind.domain ||
        movement.delta != delta || movement.cashReceived != zero || movement.changeGiven != zero ||
        movement.evidence != CashierEvidence(request.evidenceId, before.custodianId, request.occurredAt.value) ||
        movement.reason != request.reason) return null
    val expectedTotals = if (increase) before.totals.copy(payIns = before.totals.payIns + request.amount)
        else before.totals.copy(payOuts = before.totals.payOuts + request.amount)
    if (after.id != before.id || after.scope != before.scope || after.drawerId != before.drawerId ||
        after.custodianId != before.custodianId || after.businessDate != before.businessDate ||
        after.opening != before.opening || after.openingSourceId != before.openingSourceId ||
        before.status != CashSessionStatus.OPEN || after.status != before.status ||
        after.revision != before.revision.next() || after.cashLedgerRevision != before.cashLedgerRevision.next() ||
        after.expectedCash != before.expectedCash + delta || after.totals != expectedTotals ||
        after.reservedCashOut != before.reservedCashOut || after.pendingAttemptIds != before.pendingAttemptIds ||
        after.pendingTransferIds != before.pendingTransferIds || after.closingCut != before.closingCut ||
        after.closeReport != before.closeReport || after.review != before.review) return null
    return movement
}
