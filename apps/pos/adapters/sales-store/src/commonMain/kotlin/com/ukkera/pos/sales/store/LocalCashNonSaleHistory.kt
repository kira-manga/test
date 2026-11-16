package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.CashSessions
import com.ukkera.pos.sales.SalesRule
import com.ukkera.pos.sales.SalesRuleViolation
import com.ukkera.pos.sales.salesAuthorizationRequirement
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.Money

/** Exact ordinary NonSale originals only. Native BEFORE sources, original employee authority,
 * canonical body, first-refusal order and whole literal effects must all close. Stored totals,
 * a row shape or a remembered successful result cannot supply any missing source or authority. */
internal suspend fun joinCashNonSaleHistory(command: RuntimeHistoricalCommand,
    request: LocalCashRequestV1.NonSale): Boolean {
    val retained = (command.original as? RetainedOriginalV2.Legacy)?.evidence ?: return false
    if (command.command != LocalSalesStoreProfile.CASH_COMMAND ||
        command.authority != RuntimeHistoricalAuthority.EMPLOYEE_COMMAND || retained.nativeOriginalProvenance != null) return false
    check(LocalCashCommandCodec.encode(request) == command.normalized && retained.normalized == command.normalized &&
        semanticDigest(command.scope, command.actorId, command.command, command.normalized) == command.semanticDigest &&
        retained.scope == command.scope && retained.operationId == command.operationId && retained.command == command.command &&
        retained.originalActorId == command.actorId && retained.semanticDigest == command.semanticDigest &&
        retained.authenticationId == command.authenticationId && retained.policyRevision == command.policyRevision &&
        retained.recordedAt == command.recordedAt)
    val read = LocalCashHistoryRead(command)
    val cut = read.cash(request.sessionId)
    val before = cut.session.value
    // Match the live handler's preparation reads and short-circuit source allocation order.
    val exists = command.claim(LocalSalesStoreProfile.unique("payment_attempt", request.sourceId)) != null ||
        command.claim(LocalSalesStoreProfile.unique("refund_attempt", request.sourceId)) != null ||
        command.claim(CashNonSaleStoreProfile.cashSourceClaim(request.sourceId)) != null ||
        command.aggregateSource(LocalSalesStoreProfile.key(LocalSalesStoreProfile.MOVEMENT_KIND, request.sourceId)) != null
    val resources = cashResources(cut) + cashResource(request.sourceId) +
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, command.actorId)
    command.requireRequirement(salesAuthorizationRequirement(SecurityAction.CASH_MOVE, command.scope, request.sessionId,
        request.expectedRevision, command.operationId, command.semanticDigest, request.kind.reasonCode,
        before.custodianId, request.amount))
    val refusal = when {
        request.kind == NonSaleCashKind.INVENTORY_SETTLEMENT -> salesUnavailable(LocalSalesUnavailable.SOURCE_REQUIRED)
        exists -> salesRejected(SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        before.pendingAttemptIds.isNotEmpty() || before.pendingTransferIds.isNotEmpty() || before.reservedCashOut.minor != 0L ->
            salesRejected(SalesRule.PAYMENT_UNRESOLVED)
        else -> try {
            CashSessions.requireHistoricalNonSaleBefore(before, command.actorId, request.expectedRevision, request.kind.domain,
                request.amount, request.evidence.domain(command.actorId), request.reason)
            null
        } catch (violation: SalesRuleViolation) { salesRejected(violation.rule) }
    }
    if (!retained.committed) {
        if (refusal == null) return false
        check(retained.aggregateWrites.isEmpty() && retained.claimChanges.isEmpty() && retained.jobs.isEmpty())
        command.requireCashNonSaleRejection(refusal, resources)
        return true
    }
    // A claimed success cannot borrow any successful-looking AFTER balance over a refusal.
    check(refusal == null)
    val after = read.afterSession(request.sessionId)
    CashSessions.requireHistoricalNonSale(before, after, command.actorId, request.expectedRevision, request.kind.domain,
        request.amount, request.evidence.domain(command.actorId), request.reason)
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
    command.requireLegacyEffects(outcome as? ProposedOutcome.Apply ?: return false, resources)
    return true
}
