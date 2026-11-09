package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.*

/** Original counted closing transitions joined to real native BEFORE sources.
 * No current session, historical CommandReadView or reconstructed authorization is used.
 * Count, actual delivery, recipient acknowledgement and final submission remain separate;
 * an absent transfer can never stand in for a real retained delivery. */
internal suspend fun joinCashClosingHistory(command: RuntimeHistoricalCommand, request: LocalCashRequestV1): Boolean {
    if (request is LocalCashRequestV1.ReceiveSafe) return joinCashReceiptHistory(command, request)
    val sessionId = when (request) {
        is LocalCashRequestV1.BeginClosing -> request.sessionId
        is LocalCashRequestV1.CancelClosing -> request.sessionId
        is LocalCashRequestV1.RecordClosingCount -> request.sessionId
        is LocalCashRequestV1.DeliverCountedClose -> request.sessionId
        is LocalCashRequestV1.SubmitCountedClose -> request.sessionId
        is LocalCashRequestV1.Review -> request.sessionId
        else -> return false
    }
    val read = LocalCashHistoryRead(command)
    val cut = read.cash(sessionId)
    val before = cut.session.value
    val after = read.afterSession(sessionId)
    val resources = cashResources(cut).toMutableSet()
    val expectedRevision: Revision
    val reason: String
    var action = SecurityAction.CASH_CLOSE
    var amount: Money? = null
    val outcome: ProposedOutcome.Apply
    when (request) {
        is LocalCashRequestV1.BeginClosing -> {
            if (!read.requireEmptyFoodWork(before.custodianId)) return false
            resources += emptyFoodClosingResources(before.custodianId)
            expectedRevision = request.expectedRevision; reason = "cash.close.begin"
            CashSessions.requireHistoricalBeginClosing(before, after, command.actorId, request.expectedRevision,
                cut.work.value.readSet(), request.expectedWorkRevision)
            outcome = historicalCashOnly(cut, after, LocalSalesAction.CASH_CLOSING)
        }
        is LocalCashRequestV1.CancelClosing -> {
            val head = read.countHead(before)
            check(head?.index?.value?.transferId == null)
            expectedRevision = request.expectedRevision; reason = "cash.close.cancel"
            CashSessions.requireHistoricalCancelClosing(before, after, command.actorId, request.expectedRevision)
            outcome = historicalCashOnly(cut, after, LocalSalesAction.CASH_CLOSING_CANCELLED)
        }
        is LocalCashRequestV1.RecordClosingCount -> {
            val head = read.countHead(before)
            check(head?.index?.value?.transferId == null)
            val id = request.evidence.evidenceId
            val position = head?.record?.value?.position?.next() ?: Revision(0)
            val key = LocalSalesStoreProfile.key(LocalSalesStoreProfile.CLOSING_COUNT_KIND, id)
            check(command.aggregateSource(key) == null && command.claim(cashCountClaim(id)) == null &&
                command.claim(cashCountPositionClaim(sessionId, position)) == null)
            val record = read.afterRoot(key, LocalClosingCountRecordV1.serializer())
            check(record.recordedAt == command.recordedAt && record.position == position)
            CashCountedClosing.requireHistoricalRecord(before, after, record.value(), command.actorId, command.operationId,
                request.expectedRevision, head?.count, request.previousCountId,
                CountedCash(request.counted, request.evidence.domain(command.actorId)), request.reason)
            head?.record?.value?.let { LocalCashCountStorage.requirePrevious(record, it) }
            val index = LocalClosingCountIndexV1(LocalSalesScope.from(command.scope), sessionId,
                head?.index?.value?.revision?.next() ?: Revision(0), id, position, after.revision)
            expectedRevision = request.expectedRevision; reason = "cash.close.count"; amount = request.counted
            resources += cashResource(id)
            outcome = salesApplied(LocalSalesAction.CASH_COUNT_RECORDED, id,
                listOf(LocalSalesRevision(CashNonSaleStoreProfile.SESSION_KIND, sessionId, after.revision),
                    LocalSalesRevision(LocalSalesStoreProfile.CLOSING_COUNT_KIND, id, Revision(0)),
                    LocalSalesRevision(LocalSalesStoreProfile.CLOSING_COUNT_INDEX_KIND, sessionId, index.revision)),
                listOf(cashWrite(after, cut.session.stored),
                    rootWrite(LocalSalesStoreProfile.CLOSING_COUNT_KIND, id, record, LocalClosingCountRecordV1.serializer(), null),
                    rootWrite(LocalSalesStoreProfile.CLOSING_COUNT_INDEX_KIND, sessionId, index,
                        LocalClosingCountIndexV1.serializer(), head?.index?.stored)),
                listOf(ClaimChange.Acquire(cashCountClaim(id), sessionId),
                    ClaimChange.Acquire(cashCountPositionClaim(sessionId, position), id)) +
                    if (head == null) listOf(ClaimChange.Acquire(cashCountHistoryClaim(sessionId), sessionId)) else emptyList(),
                request.counted) as ProposedOutcome.Apply
        }
        is LocalCashRequestV1.DeliverCountedClose -> {
            val head = read.countHead(before) ?: error("cash.history.count_missing")
            val safe = read.safe(request.receivingCustodyId)
            val transferId = request.transferId
            check(head.isCurrent(before) && head.count.id == request.countId && head.count.count.amount == request.counted &&
                head.transfer == null && safe.value.revision == request.expectedSafeRevision &&
                safe.value.received.currency == request.counted.currency && safe.value.custodianId != command.actorId)
            check(command.aggregateSource(LocalSalesStoreProfile.key(LocalSalesStoreProfile.TRANSFER_KIND, transferId)) == null &&
                command.claim(LocalSalesStoreProfile.unique("safe_transfer", transferId)) == null)
            read.requireUnusedFinancialSource(transferId)
            val transfer = read.afterTransfer(transferId)
            CashCountedClosing.requireHistoricalDelivery(before, after, transfer, command.actorId, request.expectedRevision,
                head.count, request.retainedFloat, transferId, request.receivingCustodyId, request.evidence.domain(command.actorId))
            val index = head.index.value.copy(revision = head.index.value.revision.next(), sessionRevision = after.revision,
                transferId = transferId, retainedFloat = request.retainedFloat)
            expectedRevision = request.expectedRevision; reason = "cash.close.deliver"; action = SecurityAction.CASH_TRANSFER
            amount = request.counted - request.retainedFloat
            resources += setOf(cashResource(request.countId), cashResource(transferId), cashResource(safe.value.id), cashResource(safe.value.custodianId))
            outcome = salesApplied(LocalSalesAction.CASH_COUNTED_DELIVERY_SENT, transferId,
                listOf(LocalSalesRevision(CashNonSaleStoreProfile.SESSION_KIND, sessionId, after.revision),
                    LocalSalesRevision(LocalSalesStoreProfile.TRANSFER_KIND, transferId, transfer.revision),
                    LocalSalesRevision(LocalSalesStoreProfile.CLOSING_COUNT_INDEX_KIND, sessionId, index.revision)),
                listOf(cashWrite(after, cut.session.stored), transferWrite(transfer, null),
                    rootWrite(LocalSalesStoreProfile.CLOSING_COUNT_INDEX_KIND, sessionId, index, LocalClosingCountIndexV1.serializer(), head.index.stored)),
                listOf(ClaimChange.Acquire(LocalSalesStoreProfile.unique("safe_transfer", transferId), sessionId)), transfer.amount) as ProposedOutcome.Apply
        }
        is LocalCashRequestV1.SubmitCountedClose -> {
            if (!read.requireEmptyFoodWork(before.custodianId)) return false
            check(cut.work.value.orderIds.isEmpty() && cut.work.value.returnIds.isEmpty())
            val head = read.countHead(before) ?: error("cash.history.count_missing")
            val transfer = head.transfer?.value
            check(head.isCurrent(before) && head.count.id == request.countId && head.count.count.amount == request.counted &&
                transfer?.revision == request.expectedTransferRevision)
            if (transfer != null) check(head.index.value.retainedFloat == request.retainedFloat)
            CashCountedClosing.requireHistoricalSubmit(before, after, command.actorId, command.operationId,
                request.expectedRevision, cut.assignments(), head.count, request.retainedFloat, transfer, request.varianceReason)
            val drawer = cut.drawer.value.copy(revision = cut.drawer.value.revision.next(), activeSessionId = null)
            val custodian = cut.custodian.value.copy(revision = cut.custodian.value.revision.next(), activeSessionId = null)
            expectedRevision = request.expectedRevision; reason = "cash.close.submit-counted"; amount = request.counted
            resources += emptyFoodClosingResources(before.custodianId)
            resources += cashResource(request.countId)
            transfer?.let { resources += cashResource(it.id) }
            outcome = salesApplied(LocalSalesAction.CASH_COUNTED_CLOSED, sessionId,
                listOf(LocalSalesRevision(CashNonSaleStoreProfile.SESSION_KIND, sessionId, after.revision),
                    LocalSalesRevision(LocalSalesStoreProfile.DRAWER_KIND, drawer.id, drawer.revision),
                    LocalSalesRevision(LocalSalesStoreProfile.CUSTODIAN_KIND, custodian.id, custodian.revision)),
                listOf(cashWrite(after, cut.session.stored),
                    rootWrite(LocalSalesStoreProfile.DRAWER_KIND, drawer.id, drawer, LocalDrawerState.serializer(), cut.drawer.stored),
                    rootWrite(LocalSalesStoreProfile.CUSTODIAN_KIND, custodian.id, custodian, LocalCustodianState.serializer(), cut.custodian.stored)),
                listOf(ClaimChange.Release(LocalSalesStoreProfile.activeDrawer(drawer.id), sessionId),
                    ClaimChange.Release(LocalSalesStoreProfile.activeCustodian(custodian.id), sessionId)), request.counted) as ProposedOutcome.Apply
        }
        is LocalCashRequestV1.Review -> {
            expectedRevision = request.expectedRevision; reason = "cash.close.review"; action = SecurityAction.CASH_REVIEW
            CashSessions.requireHistoricalReview(before, after, command.actorId, request.expectedRevision,
                request.evidence.domain(command.actorId), request.reason)
            outcome = historicalCashOnly(cut, after, LocalSalesAction.CASH_REVIEWED)
        }
        else -> return false
    }
    command.requireRequirement(salesAuthorizationRequirement(action, command.scope, sessionId, expectedRevision,
        command.operationId, command.semanticDigest, reason, before.custodianId, amount))
    command.requireLegacyEffects(outcome, resources + RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, command.actorId))
    return true
}

private fun historicalCashOnly(cut: LocalCashCut, after: CashSession, action: LocalSalesAction): ProposedOutcome.Apply =
    salesApplied(action, after.id, listOf(LocalSalesRevision(CashNonSaleStoreProfile.SESSION_KIND, after.id, after.revision)),
        listOf(cashWrite(after, cut.session.stored)), amount = after.expectedCash) as ProposedOutcome.Apply

/** Fence both real index identities after their actual BEFORE contents have proved empty.
 * Empty contents do not imply that the indices themselves were absent. */
private fun emptyFoodClosingResources(ownerId: DomainId): Set<RecoveryResourceKey> = setOf(
    orderResource(ownerId), entitlementResource(foodClaimIndexId(FoodClaimIndexTargetV1.OWNER, ownerId)))
