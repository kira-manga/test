package com.ukkera.pos.sales.store

import com.ukkera.pos.database.ClaimChange
import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.PreparedMutation
import com.ukkera.pos.sales.CashAssignmentReleased
import com.ukkera.pos.sales.CashCountRecorded
import com.ukkera.pos.sales.CashCountedClosing
import com.ukkera.pos.sales.CashSessionChanged
import com.ukkera.pos.sales.CountedCash
import com.ukkera.pos.sales.SafeTransferChanged
import com.ukkera.pos.sales.SalesAggregate
import com.ukkera.pos.sales.SalesRule
import com.ukkera.pos.sales.SalesUniqueCheck
import com.ukkera.pos.sales.SalesUniqueKind
import com.ukkera.pos.sales.SalesVersionCheck
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.Revision

internal suspend fun recordClosingCount(request: LocalCashRequestV1.RecordClosingCount,
    view: CommandReadView, context: CommandContext): PreparedMutation {
    val cut = LocalSalesStorage.cash(view, request.sessionId)
    val head = LocalCashCountStorage.head(view, cut.session.value)
    val id = request.evidence.evidenceId
    val position = head?.record?.value?.position?.next() ?: Revision(0)
    val recordKey = LocalSalesStoreProfile.key(LocalSalesStoreProfile.CLOSING_COUNT_KIND, id)
    val exists = view.aggregate(recordKey) != null || claimed(view, listOf(cashCountClaim(id), cashCountPositionClaim(request.sessionId, position)))
    return LocalSalesPrepared(context, SecurityAction.CASH_CLOSE, request.sessionId, request.expectedRevision,
        cut.session.value.custodianId, "cash.close.count", request.counted, cashResources(cut) + cashResource(id)) { command, now ->
        if (exists) return@LocalSalesPrepared salesRejected(SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        // A delivered counted custody cannot be recounted as if it were still in the drawer.
        if (head?.transfer != null) return@LocalSalesPrepared salesRejected(SalesRule.CUSTODY_UNRESOLVED)
        val transition = CashCountedClosing.record(command, cut.session.value, request.expectedRevision, head?.count,
            request.previousCountId, CountedCash(request.counted, request.evidence.domain(context.originalActorId)), request.reason)
        val record = LocalClosingCountRecordV1.capture(transition.value.record, now)
        head?.record?.value?.let { LocalCashCountStorage.requirePrevious(record, it) }
        check(record.position == position && transition.command == command && transition.inventoryDependencies.isEmpty() &&
            transition.unique.isEmpty() && transition.versions == listOf(SalesVersionCheck(SalesAggregate.CASH_SESSION,
                cut.session.value.id, cut.session.value.revision)) && transition.effects ==
            listOf(CashSessionChanged(transition.value.session), CashCountRecorded(transition.value.record))) { "sales.transition.invalid" }
        val index = LocalClosingCountIndexV1(LocalSalesScope.from(context.scope), request.sessionId,
            head?.index?.value?.revision?.next() ?: Revision(0), id, position, transition.value.session.revision)
        salesApplied(LocalSalesAction.CASH_COUNT_RECORDED, id,
            listOf(LocalSalesRevision(CashNonSaleStoreProfile.SESSION_KIND, request.sessionId, transition.value.session.revision),
                LocalSalesRevision(LocalSalesStoreProfile.CLOSING_COUNT_KIND, id, Revision(0)),
                LocalSalesRevision(LocalSalesStoreProfile.CLOSING_COUNT_INDEX_KIND, request.sessionId, index.revision)),
            listOf(cashWrite(transition.value.session, cut.session.stored), rootWrite(LocalSalesStoreProfile.CLOSING_COUNT_KIND,
                id, record, LocalClosingCountRecordV1.serializer(), null), rootWrite(LocalSalesStoreProfile.CLOSING_COUNT_INDEX_KIND,
                request.sessionId, index, LocalClosingCountIndexV1.serializer(), head?.index?.stored)),
            listOf(ClaimChange.Acquire(cashCountClaim(id), request.sessionId), ClaimChange.Acquire(cashCountPositionClaim(request.sessionId, position), id)) +
                if (head == null) listOf(ClaimChange.Acquire(cashCountHistoryClaim(request.sessionId), request.sessionId)) else emptyList(),
            request.counted)
    }
}

internal suspend fun deliverCountedClose(request: LocalCashRequestV1.DeliverCountedClose,
    view: CommandReadView, context: CommandContext): PreparedMutation {
    val cut = LocalSalesStorage.cash(view, request.sessionId)
    val head = LocalCashCountStorage.head(view, cut.session.value)
    val safe = LocalSalesStorage.safe(view, request.receivingCustodyId)
    val transferClaim = LocalSalesStoreProfile.unique("safe_transfer", request.transferId)
    val exists = view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.TRANSFER_KIND, request.transferId)) != null ||
        view.claim(transferClaim) != null || financialSourceAllocated(view, request.transferId)
    return LocalSalesPrepared(context, SecurityAction.CASH_TRANSFER, request.sessionId, request.expectedRevision,
        cut.session.value.custodianId, "cash.close.deliver", request.counted - request.retainedFloat,
        cashResources(cut) + setOf(cashResource(request.countId), cashResource(request.transferId),
            cashResource(safe.value.id), cashResource(safe.value.custodianId))) { command, _ ->
        if (head == null || !head.isCurrent(cut.session.value) || head.count.id != request.countId ||
            head.count.count.amount != request.counted) return@LocalSalesPrepared salesRejected(SalesRule.REVISION_CONFLICT)
        if (head.transfer != null) return@LocalSalesPrepared salesRejected(SalesRule.CUSTODY_UNRESOLVED)
        if (exists) return@LocalSalesPrepared salesRejected(SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        if (safe.value.revision != request.expectedSafeRevision) return@LocalSalesPrepared salesRejected(SalesRule.REVISION_CONFLICT)
        if (safe.value.received.currency != request.counted.currency || safe.value.custodianId == context.originalActorId)
            return@LocalSalesPrepared salesRejected(SalesRule.CUSTODY_UNRESOLVED)
        val transition = CashCountedClosing.deliver(command, cut.session.value, request.expectedRevision, head.count,
            request.retainedFloat, request.transferId, request.receivingCustodyId, request.evidence.domain(context.originalActorId))
        val after = transition.value.session
        val transfer = transition.value.transfer
        check(transition.command == command && transition.inventoryDependencies.isEmpty() &&
            transition.versions == listOf(SalesVersionCheck(SalesAggregate.CASH_SESSION, after.id, cut.session.value.revision)) &&
            transition.unique == listOf(SalesUniqueCheck(SalesUniqueKind.SAFE_TRANSFER, transfer.id)) &&
            transition.effects == listOf(CashSessionChanged(after), SafeTransferChanged(transfer))) { "sales.transition.invalid" }
        val index = head.index.value.copy(revision = head.index.value.revision.next(), sessionRevision = after.revision,
            transferId = transfer.id, retainedFloat = request.retainedFloat)
        salesApplied(LocalSalesAction.CASH_COUNTED_DELIVERY_SENT, transfer.id,
            listOf(LocalSalesRevision(CashNonSaleStoreProfile.SESSION_KIND, after.id, after.revision),
                LocalSalesRevision(LocalSalesStoreProfile.TRANSFER_KIND, transfer.id, transfer.revision),
                LocalSalesRevision(LocalSalesStoreProfile.CLOSING_COUNT_INDEX_KIND, after.id, index.revision)),
            listOf(cashWrite(after, cut.session.stored), transferWrite(transfer, null),
                rootWrite(LocalSalesStoreProfile.CLOSING_COUNT_INDEX_KIND, after.id, index, LocalClosingCountIndexV1.serializer(), head.index.stored)),
            listOf(ClaimChange.Acquire(transferClaim, after.id)), transfer.amount)
    }
}

internal suspend fun submitCountedClose(request: LocalCashRequestV1.SubmitCountedClose,
    view: CommandReadView, context: CommandContext): PreparedMutation {
    val cut = LocalSalesStorage.cash(view, request.sessionId)
    val head = LocalCashCountStorage.head(view, cut.session.value)
    val foodWork = foodClosingWork(view, cut.session.value.custodianId)
    val foodIds = (foodWork as? FoodClosingWork.Observed)?.blockingWorkIds.orEmpty()
    return LocalSalesPrepared(context, SecurityAction.CASH_CLOSE, request.sessionId, request.expectedRevision,
        cut.session.value.custodianId, "cash.close.submit-counted", request.counted,
        cashResources(cut) + foodClosingResources(foodWork, cut.session.value.custodianId) +
            setOf(cashResource(request.countId)) + listOfNotNull(head?.transfer?.value?.id?.let(::cashResource))) { command, _ ->
        if (foodWork == FoodClosingWork.CapacityRequired) return@LocalSalesPrepared salesUnavailable(LocalSalesUnavailable.CAPACITY_REQUIRED)
        if (foodIds.isNotEmpty() || cut.work.value.orderIds.isNotEmpty() || cut.work.value.returnIds.isNotEmpty())
            return@LocalSalesPrepared salesRejected(SalesRule.CLOSING_BLOCKED)
        if (head == null || !head.isCurrent(cut.session.value) || head.count.id != request.countId ||
            head.count.count.amount != request.counted || head.transfer?.value?.revision != request.expectedTransferRevision)
            return@LocalSalesPrepared salesRejected(SalesRule.REVISION_CONFLICT)
        if (head.transfer != null && head.index.value.retainedFloat != request.retainedFloat)
            return@LocalSalesPrepared salesRejected(SalesRule.CUSTODY_UNRESOLVED)
        val transfer = head.transfer?.value
        val transition = CashCountedClosing.submit(command, cut.session.value, request.expectedRevision, cut.assignments(),
            head.count, request.retainedFloat, transfer, request.varianceReason)
        val after = transition.value
        check(transition.command == command && transition.inventoryDependencies.isEmpty() && transition.unique.isEmpty() &&
            transition.versions == listOf(SalesVersionCheck(SalesAggregate.CASH_SESSION, after.id, cut.session.value.revision),
                SalesVersionCheck(SalesAggregate.DRAWER_ASSIGNMENT, cut.drawer.value.id, cut.drawer.value.revision),
                SalesVersionCheck(SalesAggregate.CUSTODIAN_ASSIGNMENT, cut.custodian.value.id, cut.custodian.value.revision)) +
                listOfNotNull(transfer?.let { SalesVersionCheck(SalesAggregate.SAFE_TRANSFER, it.id, it.revision) }) &&
            transition.effects == listOf(CashSessionChanged(after), CashAssignmentReleased(after.drawerId, after.custodianId, after.id, null))) {
            "sales.transition.invalid"
        }
        val drawer = cut.drawer.value.copy(revision = cut.drawer.value.revision.next(), activeSessionId = null)
        val custodian = cut.custodian.value.copy(revision = cut.custodian.value.revision.next(), activeSessionId = null)
        // No transfer insertion, sender debit or safe credit occurs here. Receipt was separate.
        salesApplied(LocalSalesAction.CASH_COUNTED_CLOSED, after.id,
            listOf(LocalSalesRevision(CashNonSaleStoreProfile.SESSION_KIND, after.id, after.revision),
                LocalSalesRevision(LocalSalesStoreProfile.DRAWER_KIND, drawer.id, drawer.revision),
                LocalSalesRevision(LocalSalesStoreProfile.CUSTODIAN_KIND, custodian.id, custodian.revision)),
            listOf(cashWrite(after, cut.session.stored), rootWrite(LocalSalesStoreProfile.DRAWER_KIND, drawer.id, drawer,
                LocalDrawerState.serializer(), cut.drawer.stored), rootWrite(LocalSalesStoreProfile.CUSTODIAN_KIND,
                custodian.id, custodian, LocalCustodianState.serializer(), cut.custodian.stored)),
            listOf(ClaimChange.Release(LocalSalesStoreProfile.activeDrawer(drawer.id), after.id),
                ClaimChange.Release(LocalSalesStoreProfile.activeCustodian(custodian.id), after.id)), request.counted)
    }
}
