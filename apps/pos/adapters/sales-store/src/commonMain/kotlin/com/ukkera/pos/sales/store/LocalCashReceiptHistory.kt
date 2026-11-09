package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.SecurityAction

/** An exact original safe acknowledgement, never a new cash instruction or a second debit.
 * Native source lineage proves the actual sender, count and destination independently. */
internal suspend fun joinCashReceiptHistory(command: RuntimeHistoricalCommand,
    request: LocalCashRequestV1.ReceiveSafe): Boolean {
    val read = LocalCashHistoryRead(command)
    val transfer = read.transfer(request.transferId)
    val cut = read.cash(transfer.value.senderSessionId)
    val before = cut.session.value
    val safe = read.safe(transfer.value.receivingCustodyId)
    // Legacy combined closing has a different assignment-release sequence. Keep it held
    // until that original producer is supported, rather than treating it as a counted close.
    if (transfer.value.closingDisposition && before.status != CashSessionStatus.CLOSING) return false
    val head = if (transfer.value.closingDisposition) read.countHead(before) else null
    if (transfer.value.closingDisposition) {
        check(head != null && head.isCurrent(before) && head.transfer?.value?.id == request.transferId)
        CashCountedClosing.requirePendingDisposition(head.count, before, transfer.value)
    } else SalesSnapshotLinks.requireSafeTransfer(transfer.value, before)
    val claim = ClaimKey(LocalSalesStoreProfile.SAFE_RECEIPT_NAMESPACE, request.transferId.value)
    check(command.claim(claim) == null && safe.value.revision == request.expectedSafeRevision &&
        safe.value.received.currency == transfer.value.amount.currency)
    val afterTransfer = read.afterTransfer(request.transferId)
    // A counted receipt deliberately writes no sender-session image and releases no assignment.
    val after = if (transfer.value.closingDisposition) before else read.afterSession(before.id)
    CashSessions.requireHistoricalSafeReceipt(transfer.value, afterTransfer, before, after, command.actorId,
        request.expectedTransferRevision, request.expectedSessionRevision, safe.value.readSet(),
        request.evidence.domain(command.actorId), if (transfer.value.closingDisposition) cut.assignments() else null, head?.count)
    val afterSafe = safe.value.copy(revision = safe.value.revision.next(), received = safe.value.received + afterTransfer.amount)
    val changes = mutableListOf(transferWrite(afterTransfer, transfer.stored),
        rootWrite(LocalSalesStoreProfile.SAFE_KIND, afterSafe.id, afterSafe, LocalSafeState.serializer(), safe.stored))
    val revisions = mutableListOf(LocalSalesRevision(LocalSalesStoreProfile.TRANSFER_KIND, afterTransfer.id, afterTransfer.revision),
        LocalSalesRevision(LocalSalesStoreProfile.SAFE_KIND, afterSafe.id, afterSafe.revision))
    if (!transfer.value.closingDisposition) {
        changes += cashWrite(after, cut.session.stored)
        revisions += LocalSalesRevision(CashNonSaleStoreProfile.SESSION_KIND, after.id, after.revision)
    }
    val outcome = salesApplied(LocalSalesAction.SAFE_TRANSFER_RECEIVED, afterTransfer.id, revisions, changes,
        listOf(ClaimChange.Acquire(claim, afterSafe.id)), afterTransfer.amount) as ProposedOutcome.Apply
    command.requireRequirement(salesAuthorizationRequirement(SecurityAction.CASH_RECEIVE, command.scope, request.transferId,
        request.expectedTransferRevision, command.operationId, command.semanticDigest, "cash.safe.receive", safe.value.custodianId, transfer.value.amount))
    command.requireLegacyEffects(outcome, cashResources(cut) + setOf(cashResource(safe.value.id),
        cashResource(safe.value.custodianId), cashResource(request.transferId),
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, command.actorId)))
    return true
}
