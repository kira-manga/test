package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.*

/** Closed counted-closing refusals from the original native BEFORE cut. No handler, current
 * CommandReadView, authorization, AFTER image or proposed business effects are recreated.
 * Preparation dependencies and first-refusal order remain those of the live cash handlers. */
internal suspend fun joinCashClosingRejectionHistory(command: RuntimeHistoricalCommand,
    request: LocalCashRequestV1): Boolean {
    val retained = (command.original as? RetainedOriginalV2.Legacy)?.evidence ?: return false
    if (command.command != LocalSalesStoreProfile.CASH_COMMAND ||
        command.authority != RuntimeHistoricalAuthority.EMPLOYEE_COMMAND || retained.committed ||
        retained.nativeOriginalProvenance != null) return false
    check(LocalCashCommandCodec.encode(request) == command.normalized &&
        semanticDigest(command.scope, command.actorId, command.command, command.normalized) == command.semanticDigest &&
        retained.scope == command.scope && retained.operationId == command.operationId && retained.command == command.command &&
        retained.originalActorId == command.actorId && retained.semanticDigest == command.semanticDigest &&
        retained.authenticationId == command.authenticationId && retained.policyRevision == command.policyRevision &&
        retained.normalized == command.normalized && retained.recordedAt == command.recordedAt &&
        retained.aggregateWrites.isEmpty() && retained.claimChanges.isEmpty() && retained.jobs.isEmpty())
    if (request is LocalCashRequestV1.ReceiveSafe) return joinCountedCashReceiptRejection(command, request)
    val sessionId = when (request) {
        is LocalCashRequestV1.BeginClosing -> request.sessionId
        is LocalCashRequestV1.CancelClosing -> request.sessionId
        is LocalCashRequestV1.RecordClosingCount -> request.sessionId
        is LocalCashRequestV1.DeliverCountedClose -> request.sessionId
        is LocalCashRequestV1.SubmitCountedClose -> request.sessionId
        is LocalCashRequestV1.SubmitClose -> request.sessionId
        is LocalCashRequestV1.Review -> request.sessionId
        else -> return false
    }
    val read = LocalCashHistoryRead(command)
    val cut = read.cash(sessionId)
    val before = cut.session.value
    val resources = cashResources(cut).toMutableSet()
    val expectedRevision: Revision
    val reason: String
    var action = SecurityAction.CASH_CLOSE
    var amount: Money? = null
    val refusal: ProposedOutcome.Reject?
    when (request) {
        is LocalCashRequestV1.BeginClosing -> {
            val food = cashClosingFoodHistory(command, before.custodianId) ?: return false
            resources += foodClosingResources(food, before.custodianId)
            expectedRevision = request.expectedRevision; reason = "cash.close.begin"
            refusal = when {
                food == FoodClosingWork.CapacityRequired -> salesUnavailable(LocalSalesUnavailable.CAPACITY_REQUIRED)
                food.blockingCashClosingWork() -> salesRejected(SalesRule.CLOSING_BLOCKED)
                else -> cashClosingDomainRefusal {
                    CashSessions.requireHistoricalBeginClosingBefore(before, command.actorId, request.expectedRevision,
                        cut.work.value.readSet(), request.expectedWorkRevision)
                }
            }
        }
        is LocalCashRequestV1.CancelClosing -> {
            val head = read.countHead(before)
            expectedRevision = request.expectedRevision; reason = "cash.close.cancel"
            refusal = if (head?.transfer != null) salesRejected(SalesRule.CUSTODY_UNRESOLVED)
                else cashClosingDomainRefusal {
                    CashSessions.requireHistoricalCancelClosingBefore(before, command.actorId, request.expectedRevision)
                }
        }
        is LocalCashRequestV1.RecordClosingCount -> {
            val head = read.countHead(before)
            val id = request.evidence.evidenceId
            val position = head?.record?.value?.position?.next() ?: Revision(0)
            val exists = read.countRecordAllocated(sessionId, id, position)
            expectedRevision = request.expectedRevision; reason = "cash.close.count"; amount = request.counted
            resources += cashResource(id)
            refusal = when {
                exists -> salesRejected(SalesRule.DUPLICATE_SOURCE_ALLOCATION)
                head?.transfer != null -> salesRejected(SalesRule.CUSTODY_UNRESOLVED)
                else -> cashClosingDomainRefusal {
                    CashCountedClosing.requireHistoricalRecordBefore(before, command.actorId, request.expectedRevision,
                        head?.count, request.previousCountId,
                        CountedCash(request.counted, request.evidence.domain(command.actorId)), request.reason)
                }
            }
        }
        is LocalCashRequestV1.DeliverCountedClose -> {
            val head = read.countHead(before)
            val safe = read.safe(request.receivingCustodyId)
            val exists = read.transferAllocated(request.transferId)
            expectedRevision = request.expectedRevision; reason = "cash.close.deliver"; action = SecurityAction.CASH_TRANSFER
            amount = request.counted - request.retainedFloat
            resources += setOf(cashResource(request.countId), cashResource(request.transferId),
                cashResource(safe.value.id), cashResource(safe.value.custodianId))
            refusal = when {
                head == null || !head.isCurrent(before) || head.count.id != request.countId ||
                    head.count.count.amount != request.counted -> salesRejected(SalesRule.REVISION_CONFLICT)
                head.transfer != null -> salesRejected(SalesRule.CUSTODY_UNRESOLVED)
                exists -> salesRejected(SalesRule.DUPLICATE_SOURCE_ALLOCATION)
                safe.value.revision != request.expectedSafeRevision -> salesRejected(SalesRule.REVISION_CONFLICT)
                safe.value.received.currency != request.counted.currency || safe.value.custodianId == command.actorId ->
                    salesRejected(SalesRule.CUSTODY_UNRESOLVED)
                else -> cashClosingDomainRefusal {
                    CashCountedClosing.requireHistoricalDeliveryBefore(before, command.actorId, request.expectedRevision,
                        head.count, request.retainedFloat, request.receivingCustodyId, request.evidence.domain(command.actorId))
                }
            }
        }
        is LocalCashRequestV1.SubmitCountedClose -> {
            val head = read.countHead(before)
            val food = cashClosingFoodHistory(command, before.custodianId) ?: return false
            resources += foodClosingResources(food, before.custodianId) + cashResource(request.countId)
            head?.transfer?.value?.let { resources += cashResource(it.id) }
            expectedRevision = request.expectedRevision; reason = "cash.close.submit-counted"; amount = request.counted
            refusal = when {
                food == FoodClosingWork.CapacityRequired -> salesUnavailable(LocalSalesUnavailable.CAPACITY_REQUIRED)
                food.blockingCashClosingWork() || cut.work.value.orderIds.isNotEmpty() || cut.work.value.returnIds.isNotEmpty() ->
                    salesRejected(SalesRule.CLOSING_BLOCKED)
                head == null || !head.isCurrent(before) || head.count.id != request.countId ||
                    head.count.count.amount != request.counted || head.transfer?.value?.revision != request.expectedTransferRevision ->
                    salesRejected(SalesRule.REVISION_CONFLICT)
                head.transfer != null && head.index.value.retainedFloat != request.retainedFloat -> salesRejected(SalesRule.CUSTODY_UNRESOLVED)
                else -> cashClosingDomainRefusal {
                    CashCountedClosing.requireHistoricalSubmitBefore(before, command.actorId, request.expectedRevision,
                        cut.assignments(), head.count, request.retainedFloat, head.transfer?.value, request.varianceReason)
                }
            }
        }
        is LocalCashRequestV1.SubmitClose -> {
            val head = read.countHead(before)
            val food = cashClosingFoodHistory(command, before.custodianId) ?: return false
            val safe = request.receivingCustodyId?.let { read.safe(it) }
            request.transferId?.let { read.transferExists(it) }
            resources += foodClosingResources(food, before.custodianId) +
                listOfNotNull(safe?.value?.id?.let(::cashResource), safe?.value?.custodianId?.let(::cashResource))
            expectedRevision = request.expectedRevision; reason = "cash.close.submit"; amount = request.counted
            // Preserve only the two explicit anti-bypass guards. Ordinary combined closing,
            // its legacy assignment release and later domain/capacity results stay held.
            refusal = when {
                head?.transfer != null -> salesRejected(SalesRule.CUSTODY_UNRESOLVED)
                head != null -> salesRejected(SalesRule.REVISION_CONFLICT)
                else -> null
            }
        }
        is LocalCashRequestV1.Review -> {
            expectedRevision = request.expectedRevision; reason = "cash.close.review"; action = SecurityAction.CASH_REVIEW
            refusal = cashClosingDomainRefusal {
                CashSessions.requireHistoricalReviewBefore(before, command.actorId, request.expectedRevision,
                    request.evidence.domain(command.actorId), request.reason)
            }
        }
        else -> return false
    }
    command.requireRequirement(salesAuthorizationRequirement(action, command.scope, sessionId, expectedRevision,
        command.operationId, command.semanticDigest, reason, before.custodianId, amount))
    command.requireCashClosingRejection(refusal ?: return false,
        resources + RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, command.actorId))
    return true
}

private fun FoodClosingWork.blockingCashClosingWork(): Boolean =
    (this as? FoodClosingWork.Observed)?.blockingWorkIds?.isNotEmpty() == true

/** Catch only the shared domain guards. Source/IO/overflow and absent comparisons are holds. */
private inline fun cashClosingDomainRefusal(requireBefore: () -> Unit): ProposedOutcome.Reject? = try {
    requireBefore()
    null
} catch (violation: SalesRuleViolation) {
    salesRejected(violation.rule)
}

private suspend fun joinCountedCashReceiptRejection(command: RuntimeHistoricalCommand,
    request: LocalCashRequestV1.ReceiveSafe): Boolean {
    val read = LocalCashHistoryRead(command)
    val transfer = read.transfer(request.transferId)
    if (!transfer.value.closingDisposition) return false
    val cut = read.cash(transfer.value.senderSessionId)
    val before = cut.session.value
    val safe = read.safe(transfer.value.receivingCustodyId)
    val head = read.countHead(before) ?: return false
    if (before.status == CashSessionStatus.CLOSING) {
        check(head.isCurrent(before) && head.transfer?.value?.id == request.transferId)
        CashCountedClosing.requirePendingDisposition(head.count, before, transfer.value)
    } else SalesSnapshotLinks.requireSafeTransfer(transfer.value, before)
    if (!read.countedTransferLineage(before, head, transfer.value)) return false
    val exists = command.claim(ClaimKey(LocalSalesStoreProfile.SAFE_RECEIPT_NAMESPACE, request.transferId.value)) != null
    command.requireRequirement(salesAuthorizationRequirement(SecurityAction.CASH_RECEIVE, command.scope, request.transferId,
        request.expectedTransferRevision, command.operationId, command.semanticDigest, "cash.safe.receive",
        safe.value.custodianId, transfer.value.amount))
    val refusal = when {
        exists -> salesRejected(SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        safe.value.revision != request.expectedSafeRevision -> salesRejected(SalesRule.REVISION_CONFLICT)
        safe.value.received.currency != transfer.value.amount.currency -> salesRejected(SalesRule.INVALID_AMOUNT)
        else -> cashClosingDomainRefusal {
            CashSessions.requireHistoricalSafeReceiptBefore(transfer.value, before, command.actorId,
                request.expectedTransferRevision, request.expectedSessionRevision, safe.value.readSet(),
                request.evidence.domain(command.actorId), cut.assignments(),
                if (before.status == CashSessionStatus.CLOSING) head.count else null)
        }
    } ?: return false
    command.requireCashClosingRejection(refusal, cashResources(cut) + setOf(cashResource(safe.value.id),
        cashResource(safe.value.custodianId), cashResource(request.transferId),
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, command.actorId)))
    return true
}
