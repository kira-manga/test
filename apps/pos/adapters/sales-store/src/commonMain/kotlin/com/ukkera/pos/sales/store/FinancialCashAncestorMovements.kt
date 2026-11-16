package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.*
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.*

internal suspend fun requireFinancialCashAncestorNonSale(command: FinancialCashAncestor, request: LocalCashRequestV1.NonSale) {
    val read = FinancialCashAncestorRead(command)
    val cut = read.cash(request.sessionId)
    val before = cut.session.value
    read.requireUnusedFinancialSource(request.sourceId)
    check(request.kind != NonSaleCashKind.INVENTORY_SETTLEMENT && before.pendingAttemptIds.isEmpty() &&
        before.pendingTransferIds.isEmpty() && before.reservedCashOut.minor == 0L)
    val after = read.afterSession(request.sessionId)
    CashSessions.requireHistoricalNonSale(before, after, command.actorId, request.expectedRevision,
        request.kind.domain, request.amount, request.evidence.domain(command.actorId), request.reason)
    val zero = Money(request.amount.currency, 0)
    val movement = LocalCashMovementFact(LocalSalesScope.from(command.scope), request.sourceId, command.operationId,
        command.actorId, request.sessionId, request.kind.domain.name,
        if (request.kind == NonSaleCashKind.NON_SALE_PAY_IN) request.amount else -request.amount,
        zero, zero, request.evidence, request.reason, command.recordedAt)
    val outcome = salesApplied(LocalSalesAction.CASH_MOVEMENT_RECORDED, request.sourceId,
        listOf(LocalSalesRevision(CashNonSaleStoreProfile.SESSION_KIND, before.id, after.revision)),
        listOf(cashWrite(after, cut.session.stored), rootWrite(LocalSalesStoreProfile.MOVEMENT_KIND, request.sourceId,
            movement, LocalCashMovementFact.serializer(), null)),
        listOf(ClaimChange.Acquire(CashNonSaleStoreProfile.cashSourceClaim(request.sourceId), before.id)), after.expectedCash)
    command.requireRequirement(salesAuthorizationRequirement(SecurityAction.CASH_MOVE, command.scope, request.sessionId,
        request.expectedRevision, command.operationId, command.semanticDigest, request.kind.reasonCode,
        before.custodianId, request.amount))
    command.requireLegacyEffects(outcome as ProposedOutcome.Apply, cashResources(cut) + cashResource(request.sourceId) +
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, command.actorId))
}

internal suspend fun requireFinancialCashAncestorDrop(command: FinancialCashAncestor, request: LocalCashRequestV1.DropToSafe) {
    val read = FinancialCashAncestorRead(command)
    val cut = read.cash(request.sessionId)
    val before = cut.session.value
    val safe = read.safe(request.receivingCustodyId)
    val transferClaim = LocalSalesStoreProfile.unique("safe_transfer", request.transferId)
    val cashClaim = CashNonSaleStoreProfile.cashSourceClaim(request.transferId)
    check(command.claim(transferClaim) == null && command.claim(cashClaim) == null &&
        command.aggregateSource(LocalSalesStoreProfile.key(LocalSalesStoreProfile.TRANSFER_KIND, request.transferId)) == null)
    read.requireUnusedFinancialSource(request.transferId)
    check(safe.value.revision == request.expectedSafeRevision && safe.value.received.currency == request.amount.currency &&
        safe.value.custodianId != command.actorId && before.pendingAttemptIds.isEmpty())
    val after = read.afterSession(before.id)
    val transfer = read.afterTransfer(request.transferId)
    CashTransferOriginalHistory.requireDrop(before, after, transfer, command.actorId, request.expectedRevision,
        request.transferId, request.receivingCustodyId, request.amount, request.evidence.domain(command.actorId))
    SalesSnapshotLinks.requireSafeTransfer(transfer, after)
    val zero = Money(request.amount.currency, 0)
    val movement = LocalCashMovementFact(LocalSalesScope.from(command.scope), request.transferId, command.operationId,
        command.actorId, request.sessionId, CashMovementKind.SAFE_DROP.name, -request.amount, zero, zero,
        request.evidence, null, command.recordedAt)
    val outcome = salesApplied(LocalSalesAction.SAFE_TRANSFER_SENT, transfer.id,
        listOf(LocalSalesRevision(CashNonSaleStoreProfile.SESSION_KIND, after.id, after.revision),
            LocalSalesRevision(LocalSalesStoreProfile.TRANSFER_KIND, transfer.id, transfer.revision)),
        listOf(cashWrite(after, cut.session.stored), transferWrite(transfer, null), rootWrite(LocalSalesStoreProfile.MOVEMENT_KIND,
            transfer.id, movement, LocalCashMovementFact.serializer(), null)),
        listOf(ClaimChange.Acquire(transferClaim, after.id), ClaimChange.Acquire(cashClaim, after.id)), transfer.amount)
    command.requireRequirement(salesAuthorizationRequirement(SecurityAction.CASH_TRANSFER, command.scope, request.sessionId,
        request.expectedRevision, command.operationId, command.semanticDigest, "cash.safe.send", before.custodianId, request.amount))
    command.requireLegacyEffects(outcome as ProposedOutcome.Apply, cashResources(cut) + setOf(cashResource(safe.value.id),
        cashResource(safe.value.custodianId), cashResource(request.transferId), RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, command.actorId)))
}

internal suspend fun requireFinancialCashAncestorLegacyClose(command: FinancialCashAncestor, request: LocalCashRequestV1.SubmitClose) {
    val read = FinancialCashAncestorRead(command)
    val cut = read.cash(request.sessionId)
    val before = cut.session.value
    check(read.countHead(before) == null && read.requireEmptyFoodWork(before.custodianId) &&
        cut.work.value.orderIds.isEmpty() && cut.work.value.returnIds.isEmpty())
    val safe = request.receivingCustodyId?.let { read.safe(it) }
    request.transferId?.let { check(command.aggregateSource(LocalSalesStoreProfile.key(LocalSalesStoreProfile.TRANSFER_KIND, it)) == null &&
        command.claim(LocalSalesStoreProfile.unique("safe_transfer", it)) == null) }
    check(safe == null || safe.value.revision == request.expectedSafeRevision && safe.value.received.currency == request.counted.currency &&
        safe.value.custodianId != command.actorId)
    val after = read.afterSession(before.id)
    val transfer = request.transferId?.let { read.afterTransfer(it) }
    CashTransferOriginalHistory.requireLegacyClose(before, after, transfer, command.actorId, command.operationId,
        request.expectedRevision, cut.assignments(), CountedCash(request.counted, request.countEvidence.domain(command.actorId)),
        request.retainedFloat, request.transferId, request.receivingCustodyId, request.varianceReason,
        request.deliveryEvidence?.domain(command.actorId))
    val drawer = cut.drawer.value.copy(revision = cut.drawer.value.revision.next(), activeSessionId = null, closingTransferId = transfer?.id)
    val custodian = cut.custodian.value.copy(revision = cut.custodian.value.revision.next(), activeSessionId = null)
    val changes = listOf(cashWrite(after, cut.session.stored), rootWrite(LocalSalesStoreProfile.DRAWER_KIND, drawer.id, drawer,
        LocalDrawerState.serializer(), cut.drawer.stored), rootWrite(LocalSalesStoreProfile.CUSTODIAN_KIND, custodian.id, custodian,
        LocalCustodianState.serializer(), cut.custodian.stored)) + listOfNotNull(transfer?.let { transferWrite(it, null) })
    val claims = listOf(ClaimChange.Release(LocalSalesStoreProfile.activeDrawer(drawer.id), after.id),
        ClaimChange.Release(LocalSalesStoreProfile.activeCustodian(custodian.id), after.id)) +
        listOfNotNull(transfer?.let { ClaimChange.Acquire(LocalSalesStoreProfile.unique("safe_transfer", it.id), after.id) })
    val outcome = salesApplied(LocalSalesAction.CASH_CLOSED, after.id,
        listOf(LocalSalesRevision(CashNonSaleStoreProfile.SESSION_KIND, after.id, after.revision),
            LocalSalesRevision(LocalSalesStoreProfile.DRAWER_KIND, drawer.id, drawer.revision),
            LocalSalesRevision(LocalSalesStoreProfile.CUSTODIAN_KIND, custodian.id, custodian.revision)) +
            listOfNotNull(transfer?.let { LocalSalesRevision(LocalSalesStoreProfile.TRANSFER_KIND, it.id, it.revision) }),
        changes, claims, request.counted)
    command.requireRequirement(salesAuthorizationRequirement(SecurityAction.CASH_CLOSE, command.scope, request.sessionId,
        request.expectedRevision, command.operationId, command.semanticDigest, "cash.close.submit", before.custodianId, request.counted))
    val resources = cashResources(cut) + setOf(orderResource(before.custodianId),
        entitlementResource(foodClaimIndexId(FoodClaimIndexTargetV1.OWNER, before.custodianId)),
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, command.actorId)) +
        listOfNotNull(safe?.value?.id?.let(::cashResource), safe?.value?.custodianId?.let(::cashResource))
    command.requireLegacyEffects(outcome as ProposedOutcome.Apply, resources)
}
