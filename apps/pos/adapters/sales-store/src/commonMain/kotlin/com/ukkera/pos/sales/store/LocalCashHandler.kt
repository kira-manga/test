package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateChange
import com.ukkera.pos.database.ClaimChange
import com.ukkera.pos.database.ClaimKey
import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.MasterCommandHandler
import com.ukkera.pos.database.PreparedMutation
import com.ukkera.pos.database.ProposedOutcome
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.sales.CashAssignmentOpened
import com.ukkera.pos.sales.CashAssignmentReadSet
import com.ukkera.pos.sales.CashAssignmentReleased
import com.ukkera.pos.sales.CashMovement
import com.ukkera.pos.sales.CashSession
import com.ukkera.pos.sales.CashSessionChanged
import com.ukkera.pos.sales.CashSessions
import com.ukkera.pos.sales.ClosingCustodyReleased
import com.ukkera.pos.sales.CountedCash
import com.ukkera.pos.sales.SafeCustodyReceipt
import com.ukkera.pos.sales.SafeTransferChanged
import com.ukkera.pos.sales.SalesAggregate
import com.ukkera.pos.sales.SalesCommand
import com.ukkera.pos.sales.SalesRule
import com.ukkera.pos.sales.SalesSnapshotLinks
import com.ukkera.pos.sales.SalesTransition
import com.ukkera.pos.sales.SalesUniqueCheck
import com.ukkera.pos.sales.SalesUniqueKind
import com.ukkera.pos.sales.SalesVersionCheck
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision

/**
 * Real local cash lifecycle commands. Every dependency is read in the kernel's
 * writer transaction; no fixture seed, request snapshot or replacement auth source.
 */
public class LocalCashHandler : MasterCommandHandler {
    override public val key: CommandKey get() = LocalSalesStoreProfile.CASH_COMMAND
    override public val writableKinds: Set<String> = setOf(
        CashNonSaleStoreProfile.SESSION_KIND, LocalSalesStoreProfile.DRAWER_KIND, LocalSalesStoreProfile.CUSTODIAN_KIND,
        LocalSalesStoreProfile.WORK_KIND, LocalSalesStoreProfile.SAFE_KIND, LocalSalesStoreProfile.TRANSFER_KIND,
        LocalSalesStoreProfile.MOVEMENT_KIND, LocalSalesStoreProfile.CLOSING_COUNT_KIND, LocalSalesStoreProfile.CLOSING_COUNT_INDEX_KIND,
    )
    override public val claimNamespaces: Set<String> = setOf(
        LocalSalesStoreProfile.CONFIGURED_NAMESPACE, LocalSalesStoreProfile.ACTIVE_DRAWER_NAMESPACE,
        LocalSalesStoreProfile.ACTIVE_CUSTODIAN_NAMESPACE, LocalSalesStoreProfile.UNIQUE_NAMESPACE,
        LocalSalesStoreProfile.SAFE_RECEIPT_NAMESPACE, CashNonSaleStoreProfile.CASH_SOURCE_NAMESPACE,
    )

    override public fun normalize(input: StoredBytes): StoredBytes = LocalCashCommandCodec.normalize(input)

    override public suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        check(context.scope == view.scope && context.key == key &&
            semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest) { "sales.context.invalid" }
        return when (val request = LocalCashCommandCodec.decode(normalized)) {
            is LocalCashRequestV1.ConfigureDrawer -> configureDrawer(request, view, context)
            is LocalCashRequestV1.ConfigureCustodian -> configureCustodian(request, view, context)
            is LocalCashRequestV1.ConfigureSafe -> configureSafe(request, view, context)
            is LocalCashRequestV1.Open -> open(request, view, context)
            is LocalCashRequestV1.NonSale -> nonSale(request, view, context)
            is LocalCashRequestV1.BeginClosing -> beginClosing(request, view, context)
            is LocalCashRequestV1.CancelClosing -> cancelClosing(request, view, context)
            is LocalCashRequestV1.RecordClosingCount -> recordClosingCount(request, view, context)
            is LocalCashRequestV1.DeliverCountedClose -> deliverCountedClose(request, view, context)
            is LocalCashRequestV1.SubmitCountedClose -> submitCountedClose(request, view, context)
            is LocalCashRequestV1.SubmitClose -> submitClose(request, view, context)
            is LocalCashRequestV1.Review -> review(request, view, context)
            is LocalCashRequestV1.DropToSafe -> dropToSafe(request, view, context)
            is LocalCashRequestV1.ReceiveSafe -> receiveSafe(request, view, context)
        }
    }
}

private suspend fun configureDrawer(request: LocalCashRequestV1.ConfigureDrawer, view: CommandReadView, context: CommandContext): PreparedMutation {
    val kind = LocalSalesStoreProfile.DRAWER_KIND
    val claim = configuredClaim(kind, request.drawerId)
    val exists = view.aggregate(LocalSalesStoreProfile.key(kind, request.drawerId)) != null || view.claim(claim) != null ||
        view.claim(LocalSalesStoreProfile.activeDrawer(request.drawerId)) != null
    return LocalSalesPrepared(context, SecurityAction.CASH_CONFIGURE, request.drawerId, Revision(0), context.originalActorId,
        "cash.configure.drawer", resources = setOf(cashResource(request.drawerId))) { _, _ ->
        if (exists) return@LocalSalesPrepared salesRejected(SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        val state = LocalDrawerState(LocalSalesScope.from(context.scope), request.drawerId, Revision(0), request.label, request.currency)
        salesApplied(LocalSalesAction.DRAWER_CONFIGURED, state.id, listOf(LocalSalesRevision(kind, state.id, state.revision)),
            listOf(rootWrite(kind, state.id, state, LocalDrawerState.serializer(), null)), listOf(ClaimChange.Acquire(claim, state.id)))
    }
}

private suspend fun configureCustodian(request: LocalCashRequestV1.ConfigureCustodian, view: CommandReadView, context: CommandContext): PreparedMutation {
    val kind = LocalSalesStoreProfile.CUSTODIAN_KIND
    val claim = configuredClaim(kind, request.custodianId)
    val exists = view.aggregate(LocalSalesStoreProfile.key(kind, request.custodianId)) != null ||
        view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.WORK_KIND, request.custodianId)) != null ||
        view.claim(claim) != null || view.claim(LocalSalesStoreProfile.activeCustodian(request.custodianId)) != null
    return LocalSalesPrepared(context, SecurityAction.CASH_CONFIGURE, request.custodianId, Revision(0), context.originalActorId,
        "cash.configure.custodian", resources = setOf(cashResource(request.custodianId), orderResource(request.custodianId))) { _, _ ->
        if (exists) return@LocalSalesPrepared salesRejected(SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        val state = LocalCustodianState(LocalSalesScope.from(context.scope), request.custodianId, Revision(0))
        val work = LocalWorkState(state.scope, state.id, Revision(0))
        salesApplied(LocalSalesAction.CUSTODIAN_CONFIGURED, state.id,
            listOf(LocalSalesRevision(kind, state.id, state.revision), LocalSalesRevision(LocalSalesStoreProfile.WORK_KIND, work.id, work.revision)),
            listOf(rootWrite(kind, state.id, state, LocalCustodianState.serializer(), null),
                rootWrite(LocalSalesStoreProfile.WORK_KIND, work.id, work, LocalWorkState.serializer(), null)),
            listOf(ClaimChange.Acquire(claim, state.id)))
    }
}

private suspend fun configureSafe(request: LocalCashRequestV1.ConfigureSafe, view: CommandReadView, context: CommandContext): PreparedMutation {
    val kind = LocalSalesStoreProfile.SAFE_KIND
    val claim = configuredClaim(kind, request.custodyId)
    val exists = view.aggregate(LocalSalesStoreProfile.key(kind, request.custodyId)) != null || view.claim(claim) != null
    // A named operational custodian must already be explicitly provisioned.
    LocalSalesStorage.custodian(view, request.custodianId)
    return LocalSalesPrepared(context, SecurityAction.CASH_CONFIGURE, request.custodyId, Revision(0), context.originalActorId,
        "cash.configure.safe", resources = setOf(cashResource(request.custodyId), cashResource(request.custodianId))) { _, _ ->
        if (exists) return@LocalSalesPrepared salesRejected(SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        val state = LocalSafeState(LocalSalesScope.from(context.scope), request.custodyId, Revision(0), request.custodianId,
            request.label, Money(request.currency, 0))
        salesApplied(LocalSalesAction.SAFE_CONFIGURED, state.id, listOf(LocalSalesRevision(kind, state.id, state.revision)),
            listOf(rootWrite(kind, state.id, state, LocalSafeState.serializer(), null)), listOf(ClaimChange.Acquire(claim, state.id)))
    }
}

private suspend fun open(request: LocalCashRequestV1.Open, view: CommandReadView, context: CommandContext): PreparedMutation {
    // Employee enrollment is deliberately separate from cash-custodian configuration. An
    // authenticated but unconfigured employee is not a corrupt database. Prove a clean local
    // miss (including the custodian's claims/work companion), then let the SAME bound current
    // CASH_OPEN authorization run before publishing a refusal. Never seed a default custodian,
    // grant cash rights, or turn a surviving orphan/invalid protected row into this outcome.
    if (!localCustodianSourcePresent(view, context.originalActorId)) {
        return LocalSalesPrepared(context, SecurityAction.CASH_OPEN, request.drawerId, request.expectedDrawerRevision,
            context.originalActorId, "cash.open", request.opening,
            setOf(cashResource(request.drawerId), cashResource(context.originalActorId),
                orderResource(context.originalActorId), cashResource(request.sessionId), cashResource(request.openingSourceId))) { _, _ ->
            salesUnavailable(LocalSalesUnavailable.SOURCE_REQUIRED)
        }
    }
    val drawer = LocalSalesStorage.drawer(view, request.drawerId)
    val custodian = LocalSalesStorage.custodian(view, context.originalActorId)
    LocalSalesStorage.work(view, context.originalActorId)
    val unique = listOf(LocalSalesStoreProfile.unique("cash_session", request.sessionId),
        LocalSalesStoreProfile.unique("opening_source", request.openingSourceId))
    val exists = view.aggregate(CashNonSaleStoreProfile.sessionKey(request.sessionId)) != null || claimed(view, unique)
    return LocalSalesPrepared(context, SecurityAction.CASH_OPEN, drawer.value.id, request.expectedDrawerRevision,
        custodian.value.id, "cash.open", request.opening,
        setOf(cashResource(drawer.value.id), cashResource(custodian.value.id), cashResource(request.sessionId), cashResource(request.openingSourceId))) { command, _ ->
        if (exists) return@LocalSalesPrepared salesRejected(SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        if (drawer.value.currency != request.opening.currency) return@LocalSalesPrepared salesRejected(SalesRule.INVALID_AMOUNT)
        val assignments = CashAssignmentReadSet(context.scope, drawer.value.id, drawer.value.revision,
            drawer.value.activeSessionId, drawer.value.closingTransferId, custodian.value.id,
            custodian.value.revision, custodian.value.activeSessionId)
        val transition = CashSessions.open(command, assignments, request.expectedDrawerRevision, request.expectedCustodianRevision,
            request.sessionId, request.businessDate.value, CountedCash(request.opening, request.evidence.domain(context.originalActorId)), request.openingSourceId)
        val session = transition.value
        check(transition.command == command && transition.inventoryDependencies.isEmpty() &&
            transition.effects == listOf(CashSessionChanged(session), CashAssignmentOpened(drawer.value.id, custodian.value.id, session.id)) &&
            transition.versions == listOf(SalesVersionCheck(SalesAggregate.DRAWER_ASSIGNMENT, drawer.value.id, drawer.value.revision),
                SalesVersionCheck(SalesAggregate.CUSTODIAN_ASSIGNMENT, custodian.value.id, custodian.value.revision)) &&
            transition.unique == listOf(SalesUniqueCheck(SalesUniqueKind.CASH_SESSION, session.id),
                SalesUniqueCheck(SalesUniqueKind.OPENING_FLOAT_SOURCE, request.openingSourceId),
                SalesUniqueCheck(SalesUniqueKind.ACTIVE_DRAWER, drawer.value.id), SalesUniqueCheck(SalesUniqueKind.ACTIVE_CUSTODIAN, custodian.value.id)))
        val afterDrawer = drawer.value.copy(revision = drawer.value.revision.next(), activeSessionId = session.id)
        val afterCustodian = custodian.value.copy(revision = custodian.value.revision.next(), activeSessionId = session.id)
        salesApplied(LocalSalesAction.CASH_OPENED, session.id,
            listOf(LocalSalesRevision(CashNonSaleStoreProfile.SESSION_KIND, session.id, session.revision),
                LocalSalesRevision(LocalSalesStoreProfile.DRAWER_KIND, afterDrawer.id, afterDrawer.revision),
                LocalSalesRevision(LocalSalesStoreProfile.CUSTODIAN_KIND, afterCustodian.id, afterCustodian.revision)),
            listOf(cashWrite(session, null), rootWrite(LocalSalesStoreProfile.DRAWER_KIND, afterDrawer.id, afterDrawer, LocalDrawerState.serializer(), drawer.stored),
                rootWrite(LocalSalesStoreProfile.CUSTODIAN_KIND, afterCustodian.id, afterCustodian, LocalCustodianState.serializer(), custodian.stored)),
            listOf(ClaimChange.Acquire(unique[0], session.id), ClaimChange.Acquire(unique[1], session.id),
                ClaimChange.Acquire(LocalSalesStoreProfile.activeDrawer(afterDrawer.id), session.id),
                ClaimChange.Acquire(LocalSalesStoreProfile.activeCustodian(afterCustodian.id), session.id)), session.expectedCash)
    }
}

private suspend fun nonSale(request: LocalCashRequestV1.NonSale, view: CommandReadView, context: CommandContext): PreparedMutation {
    val cut = LocalSalesStorage.cash(view, request.sessionId)
    val source = CashNonSaleStoreProfile.cashSourceClaim(request.sourceId)
    val exists = financialSourceAllocated(view, request.sourceId)
    return LocalSalesPrepared(context, SecurityAction.CASH_MOVE, request.sessionId, request.expectedRevision,
        cut.session.value.custodianId, request.kind.reasonCode, request.amount, cashResources(cut) + cashResource(request.sourceId)) { command, now ->
        if (request.kind == NonSaleCashKind.INVENTORY_SETTLEMENT) return@LocalSalesPrepared salesUnavailable(LocalSalesUnavailable.SOURCE_REQUIRED)
        if (exists) return@LocalSalesPrepared salesRejected(SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        val before = cut.session.value
        if (before.pendingAttemptIds.isNotEmpty() || before.pendingTransferIds.isNotEmpty() || before.reservedCashOut.minor != 0L) {
            return@LocalSalesPrepared salesRejected(SalesRule.PAYMENT_UNRESOLVED)
        }
        val transition = CashSessions.recordNonSale(command, before, request.expectedRevision, request.sourceId, request.kind.domain,
            request.amount, request.evidence.domain(context.originalActorId), request.reason)
        val adapterRequest = CashNonSaleRequestV1(request.sessionId, request.expectedRevision, request.sourceId, request.kind,
            request.amount, request.evidence.evidenceId, request.evidence.occurredAt, request.reason)
        val movement = checkNotNull(validateNonSaleTransition(command, before, adapterRequest, transition.command, transition.value,
            transition.versions, transition.unique, transition.effects, transition.inventoryDependencies)) { "sales.transition.invalid" }
        salesApplied(LocalSalesAction.CASH_MOVEMENT_RECORDED, movement.sourceId,
            listOf(LocalSalesRevision(CashNonSaleStoreProfile.SESSION_KIND, before.id, transition.value.revision)),
            listOf(cashWrite(transition.value, cut.session.stored), movementWrite(context, movement, now)),
            listOf(ClaimChange.Acquire(source, before.id)), transition.value.expectedCash)
    }
}

private suspend fun beginClosing(request: LocalCashRequestV1.BeginClosing, view: CommandReadView, context: CommandContext): PreparedMutation {
    val cut = LocalSalesStorage.cash(view, request.sessionId)
    val foodWork = foodClosingWork(view, cut.session.value.custodianId)
    val foodIds = (foodWork as? FoodClosingWork.Observed)?.blockingWorkIds.orEmpty()
    return LocalSalesPrepared(context, SecurityAction.CASH_CLOSE, request.sessionId, request.expectedRevision,
        cut.session.value.custodianId, "cash.close.begin", resources = cashResources(cut) +
            foodClosingResources(foodWork, cut.session.value.custodianId)) { command, _ ->
        // No food data/effect is published before the exact CASH_CLOSE authorization above.
        if (foodWork == FoodClosingWork.CapacityRequired) return@LocalSalesPrepared salesUnavailable(LocalSalesUnavailable.CAPACITY_REQUIRED)
        if (foodIds.isNotEmpty()) return@LocalSalesPrepared salesRejected(SalesRule.CLOSING_BLOCKED)
        val transition = CashSessions.beginClosing(command, cut.session.value, request.expectedRevision, cut.work.value.readSet(), request.expectedWorkRevision)
        cashOnly(command, cut, transition, LocalSalesAction.CASH_CLOSING,
            listOf(SalesVersionCheck(SalesAggregate.CLOSING_WORK_INDEX, cut.work.value.id, cut.work.value.revision)))
    }
}

private suspend fun cancelClosing(request: LocalCashRequestV1.CancelClosing, view: CommandReadView, context: CommandContext): PreparedMutation {
    val cut = LocalSalesStorage.cash(view, request.sessionId)
    val count = LocalCashCountStorage.head(view, cut.session.value)
    return LocalSalesPrepared(context, SecurityAction.CASH_CLOSE, request.sessionId, request.expectedRevision,
        cut.session.value.custodianId, "cash.close.cancel", resources = cashResources(cut)) { command, _ ->
        if (count?.transfer != null) return@LocalSalesPrepared salesRejected(SalesRule.CUSTODY_UNRESOLVED)
        cashOnly(command, cut, CashSessions.cancelClosing(command, cut.session.value, request.expectedRevision), LocalSalesAction.CASH_CLOSING_CANCELLED)
    }
}

private suspend fun submitClose(request: LocalCashRequestV1.SubmitClose, view: CommandReadView, context: CommandContext): PreparedMutation {
    val cut = LocalSalesStorage.cash(view, request.sessionId)
    val count = LocalCashCountStorage.head(view, cut.session.value)
    val foodWork = foodClosingWork(view, cut.session.value.custodianId)
    val foodIds = (foodWork as? FoodClosingWork.Observed)?.blockingWorkIds.orEmpty()
    val safe = request.receivingCustodyId?.let { LocalSalesStorage.safe(view, it) }
    val transferExists = request.transferId?.let { id ->
        view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.TRANSFER_KIND, id)) != null ||
            view.claim(LocalSalesStoreProfile.unique("safe_transfer", id)) != null
    } ?: false
    return LocalSalesPrepared(context, SecurityAction.CASH_CLOSE, request.sessionId, request.expectedRevision,
        cut.session.value.custodianId, "cash.close.submit", request.counted,
        cashResources(cut) + foodClosingResources(foodWork, cut.session.value.custodianId) +
            listOfNotNull(safe?.value?.id?.let(::cashResource), safe?.value?.custodianId?.let(::cashResource))) { command, _ ->
        // Historical combined-close commands retain their bytes, not a way around a newly
        // recorded count or a delivery which must not be repeated under a second command.
        if (count?.transfer != null) return@LocalSalesPrepared salesRejected(SalesRule.CUSTODY_UNRESOLVED)
        if (count != null)
            return@LocalSalesPrepared salesRejected(SalesRule.REVISION_CONFLICT)
        if (foodWork == FoodClosingWork.CapacityRequired) return@LocalSalesPrepared salesUnavailable(LocalSalesUnavailable.CAPACITY_REQUIRED)
        if (foodIds.isNotEmpty()) return@LocalSalesPrepared salesRejected(SalesRule.CLOSING_BLOCKED)
        if (transferExists) return@LocalSalesPrepared salesRejected(SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        if (cut.work.value.orderIds.isNotEmpty() || cut.work.value.returnIds.isNotEmpty()) return@LocalSalesPrepared salesRejected(SalesRule.CLOSING_BLOCKED)
        if (safe != null && (safe.value.revision != request.expectedSafeRevision || safe.value.received.currency != request.counted.currency ||
                safe.value.custodianId == context.originalActorId)) return@LocalSalesPrepared salesRejected(SalesRule.CUSTODY_UNRESOLVED)
        val transition = CashSessions.submitClose(command, cut.session.value, request.expectedRevision, cut.assignments(),
            CountedCash(request.counted, request.countEvidence.domain(context.originalActorId)), request.retainedFloat,
            request.transferId, request.receivingCustodyId, request.varianceReason, request.deliveryEvidence?.domain(context.originalActorId))
        val after = transition.value.session
        val transfer = transition.value.transfer
        val expectedEffects = listOf(CashSessionChanged(after), CashAssignmentReleased(cut.drawer.value.id, cut.custodian.value.id, after.id, transfer?.id)) +
            listOfNotNull(transfer?.let(::SafeTransferChanged))
        check(transition.command == command && transition.effects == expectedEffects && transition.inventoryDependencies.isEmpty() &&
            transition.versions == listOf(sessionVersion(cut), SalesVersionCheck(SalesAggregate.DRAWER_ASSIGNMENT, cut.drawer.value.id, cut.drawer.value.revision),
                SalesVersionCheck(SalesAggregate.CUSTODIAN_ASSIGNMENT, cut.custodian.value.id, cut.custodian.value.revision)) &&
            transition.unique == listOfNotNull(transfer?.let { SalesUniqueCheck(SalesUniqueKind.SAFE_TRANSFER, it.id) }))
        val drawer = cut.drawer.value.copy(revision = cut.drawer.value.revision.next(), activeSessionId = null, closingTransferId = transfer?.id)
        val custodian = cut.custodian.value.copy(revision = cut.custodian.value.revision.next(), activeSessionId = null)
        val changes = listOf(cashWrite(after, cut.session.stored),
            rootWrite(LocalSalesStoreProfile.DRAWER_KIND, drawer.id, drawer, LocalDrawerState.serializer(), cut.drawer.stored),
            rootWrite(LocalSalesStoreProfile.CUSTODIAN_KIND, custodian.id, custodian, LocalCustodianState.serializer(), cut.custodian.stored)) +
            listOfNotNull(transfer?.let { transferWrite(it, null) })
        val claims = listOf(ClaimChange.Release(LocalSalesStoreProfile.activeDrawer(drawer.id), after.id),
            ClaimChange.Release(LocalSalesStoreProfile.activeCustodian(custodian.id), after.id)) +
            listOfNotNull(transfer?.let { ClaimChange.Acquire(LocalSalesStoreProfile.unique("safe_transfer", it.id), after.id) })
        salesApplied(LocalSalesAction.CASH_CLOSED, after.id,
            listOf(LocalSalesRevision(CashNonSaleStoreProfile.SESSION_KIND, after.id, after.revision),
                LocalSalesRevision(LocalSalesStoreProfile.DRAWER_KIND, drawer.id, drawer.revision),
                LocalSalesRevision(LocalSalesStoreProfile.CUSTODIAN_KIND, custodian.id, custodian.revision)) +
                listOfNotNull(transfer?.let { LocalSalesRevision(LocalSalesStoreProfile.TRANSFER_KIND, it.id, it.revision) }),
            changes, claims, request.counted)
    }
}

private suspend fun review(request: LocalCashRequestV1.Review, view: CommandReadView, context: CommandContext): PreparedMutation {
    val cut = LocalSalesStorage.cash(view, request.sessionId)
    return LocalSalesPrepared(context, SecurityAction.CASH_REVIEW, request.sessionId, request.expectedRevision,
        cut.session.value.custodianId, "cash.close.review", resources = cashResources(cut)) { command, _ ->
        cashOnly(command, cut, CashSessions.review(command, cut.session.value, request.expectedRevision,
            request.evidence.domain(context.originalActorId), request.reason), LocalSalesAction.CASH_REVIEWED)
    }
}

private suspend fun dropToSafe(request: LocalCashRequestV1.DropToSafe, view: CommandReadView, context: CommandContext): PreparedMutation {
    val cut = LocalSalesStorage.cash(view, request.sessionId)
    val safe = LocalSalesStorage.safe(view, request.receivingCustodyId)
    val transferClaim = LocalSalesStoreProfile.unique("safe_transfer", request.transferId)
    val cashClaim = CashNonSaleStoreProfile.cashSourceClaim(request.transferId)
    val exists = claimed(view, listOf(transferClaim, cashClaim)) ||
        view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.TRANSFER_KIND, request.transferId)) != null ||
        financialSourceAllocated(view, request.transferId)
    return LocalSalesPrepared(context, SecurityAction.CASH_TRANSFER, request.sessionId, request.expectedRevision,
        cut.session.value.custodianId, "cash.safe.send", request.amount,
        cashResources(cut) + setOf(cashResource(safe.value.id), cashResource(safe.value.custodianId), cashResource(request.transferId))) { command, now ->
        if (exists) return@LocalSalesPrepared salesRejected(SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        if (safe.value.revision != request.expectedSafeRevision) return@LocalSalesPrepared salesRejected(SalesRule.REVISION_CONFLICT)
        if (safe.value.received.currency != request.amount.currency || safe.value.custodianId == context.originalActorId) {
            return@LocalSalesPrepared salesRejected(SalesRule.CUSTODY_UNRESOLVED)
        }
        if (cut.session.value.pendingAttemptIds.isNotEmpty()) return@LocalSalesPrepared salesRejected(SalesRule.PAYMENT_UNRESOLVED)
        val transition = CashSessions.dropToSafe(command, cut.session.value, request.expectedRevision, request.transferId,
            request.receivingCustodyId, request.amount, request.evidence.domain(context.originalActorId))
        val after = transition.value.session
        val transfer = transition.value.transfer
        val movement = transition.effects.firstOrNull() as? CashMovement ?: error("sales.transition.invalid")
        check(transition.command == command && transition.inventoryDependencies.isEmpty() && transition.versions == listOf(sessionVersion(cut)) &&
            transition.unique == listOf(SalesUniqueCheck(SalesUniqueKind.SAFE_TRANSFER, transfer.id), SalesUniqueCheck(SalesUniqueKind.CASH_SOURCE, transfer.id)) &&
            transition.effects == listOf(movement, CashSessionChanged(after), SafeTransferChanged(transfer)) && movement.sourceId == transfer.id)
        SalesSnapshotLinks.requireSafeTransfer(transfer, after)
        salesApplied(LocalSalesAction.SAFE_TRANSFER_SENT, transfer.id,
            listOf(LocalSalesRevision(CashNonSaleStoreProfile.SESSION_KIND, after.id, after.revision),
                LocalSalesRevision(LocalSalesStoreProfile.TRANSFER_KIND, transfer.id, transfer.revision)),
            listOf(cashWrite(after, cut.session.stored), transferWrite(transfer, null), movementWrite(context, movement, now)),
            listOf(ClaimChange.Acquire(transferClaim, after.id), ClaimChange.Acquire(cashClaim, after.id)), transfer.amount)
    }
}

private suspend fun receiveSafe(request: LocalCashRequestV1.ReceiveSafe, view: CommandReadView, context: CommandContext): PreparedMutation {
    val transfer = LocalSalesStorage.transfer(view, request.transferId)
    val cut = LocalSalesStorage.cash(view, transfer.value.senderSessionId)
    val safe = LocalSalesStorage.safe(view, transfer.value.receivingCustodyId)
    requireLocalCashTransfer(view, transfer.value, cut.session.value)
    val pendingCountedClose = if (transfer.value.closingDisposition && cut.session.value.status == com.ukkera.pos.sales.CashSessionStatus.CLOSING)
        checkNotNull(LocalCashCountStorage.head(view, cut.session.value)) else null
    val claim = ClaimKey(LocalSalesStoreProfile.SAFE_RECEIPT_NAMESPACE, request.transferId.value)
    val exists = view.claim(claim) != null
    return LocalSalesPrepared(context, SecurityAction.CASH_RECEIVE, request.transferId, request.expectedTransferRevision,
        safe.value.custodianId, "cash.safe.receive", transfer.value.amount,
        cashResources(cut) + setOf(cashResource(safe.value.id), cashResource(safe.value.custodianId), cashResource(request.transferId))) { command, _ ->
        if (exists) return@LocalSalesPrepared salesRejected(SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        if (safe.value.revision != request.expectedSafeRevision) return@LocalSalesPrepared salesRejected(SalesRule.REVISION_CONFLICT)
        if (safe.value.received.currency != transfer.value.amount.currency) return@LocalSalesPrepared salesRejected(SalesRule.INVALID_AMOUNT)
        val evidence = request.evidence.domain(context.originalActorId)
        val transition = CashSessions.receiveSafeTransfer(command, transfer.value, request.expectedTransferRevision,
            cut.session.value, request.expectedSessionRevision, safe.value.readSet(), evidence,
            if (transfer.value.closingDisposition) cut.assignments() else null, pendingCountedClose?.count)
        val afterTransfer = transition.value.transfer
        val afterCash = transition.value.session
        val release = if (transfer.value.closingDisposition && pendingCountedClose == null) ClosingCustodyReleased(cut.drawer.value.id, transfer.value.id) else null
        val expectedEffects = listOf(SafeTransferChanged(afterTransfer), SafeCustodyReceipt(afterTransfer.id, safe.value.id, afterTransfer.amount, evidence)) +
            if (pendingCountedClose != null) emptyList() else if (release == null) listOf(CashSessionChanged(afterCash)) else listOf(release)
        check(transition.command == command && transition.inventoryDependencies.isEmpty() && transition.unique.isEmpty() &&
            transition.effects == expectedEffects && transition.versions == listOf(sessionVersion(cut),
                SalesVersionCheck(SalesAggregate.SAFE_TRANSFER, transfer.value.id, transfer.value.revision),
                SalesVersionCheck(SalesAggregate.SAFE_CUSTODY, safe.value.id, safe.value.revision)) +
                if (!transfer.value.closingDisposition) emptyList() else listOf(SalesVersionCheck(SalesAggregate.DRAWER_ASSIGNMENT, cut.drawer.value.id, cut.drawer.value.revision)))
        if (transfer.value.closingDisposition) check(afterCash === cut.session.value) { "sales.close.immutable" }
        val afterSafe = safe.value.copy(revision = safe.value.revision.next(), received = safe.value.received + afterTransfer.amount)
        val changes = mutableListOf(transferWrite(afterTransfer, transfer.stored),
            rootWrite(LocalSalesStoreProfile.SAFE_KIND, afterSafe.id, afterSafe, LocalSafeState.serializer(), safe.stored))
        val revisions = mutableListOf(LocalSalesRevision(LocalSalesStoreProfile.TRANSFER_KIND, afterTransfer.id, afterTransfer.revision),
            LocalSalesRevision(LocalSalesStoreProfile.SAFE_KIND, afterSafe.id, afterSafe.revision))
        if (!transfer.value.closingDisposition) {
            changes += cashWrite(afterCash, cut.session.stored)
            revisions += LocalSalesRevision(CashNonSaleStoreProfile.SESSION_KIND, afterCash.id, afterCash.revision)
        } else if (release != null) {
            val drawer = cut.drawer.value.copy(revision = cut.drawer.value.revision.next(), closingTransferId = null)
            changes += rootWrite(LocalSalesStoreProfile.DRAWER_KIND, drawer.id, drawer, LocalDrawerState.serializer(), cut.drawer.stored)
            revisions += LocalSalesRevision(LocalSalesStoreProfile.DRAWER_KIND, drawer.id, drawer.revision)
        }
        salesApplied(LocalSalesAction.SAFE_TRANSFER_RECEIVED, afterTransfer.id, revisions, changes,
            listOf(ClaimChange.Acquire(claim, afterSafe.id)), afterTransfer.amount)
    }
}

private fun cashOnly(
    command: SalesCommand, cut: LocalCashCut, transition: SalesTransition<CashSession>, action: LocalSalesAction,
    extraVersions: List<SalesVersionCheck> = emptyList(),
): ProposedOutcome {
    check(transition.command == command && transition.versions == listOf(sessionVersion(cut)) + extraVersions &&
        transition.unique.isEmpty() && transition.inventoryDependencies.isEmpty() && transition.effects == listOf(CashSessionChanged(transition.value))) {
        "sales.transition.invalid"
    }
    return salesApplied(action, transition.value.id,
        listOf(LocalSalesRevision(CashNonSaleStoreProfile.SESSION_KIND, transition.value.id, transition.value.revision)),
        listOf(cashWrite(transition.value, cut.session.stored)), amount = transition.value.expectedCash)
}

private fun sessionVersion(cut: LocalCashCut): SalesVersionCheck =
    SalesVersionCheck(SalesAggregate.CASH_SESSION, cut.session.value.id, cut.session.value.revision)

internal suspend fun claimed(view: CommandReadView, keys: List<ClaimKey>): Boolean {
    var any = false
    for (key in keys) if (view.claim(key) != null) any = true
    return any
}
