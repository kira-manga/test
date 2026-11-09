package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.*
import kotlinx.serialization.KSerializer

/** Ordinary original cash provisioning/opening/counted custody and NONPHYSICAL normalized commerce.
 * Existing normalized unpaid owner handoffs require their exact original authorization and
 * complete installed commerce/stock producer. No paid native original, financial attestation
 * or supervisor read is accepted here; no fake view is used. */
public class LocalNonphysicalHistoryProducer(private val stock: LocalStockRepository) : RuntimeHistoryBusinessProducer {
    override public val commands: Set<CommandKey> = setOf(LocalSalesStoreProfile.CASH_COMMAND, LocalCashCloseNoteCodec.COMMAND,
        StockStreamingCommandsV2.COLLECTION)
    override public suspend fun join(command: RuntimeHistoricalCommand): Boolean = when (command.command) {
        LocalSalesStoreProfile.CASH_COMMAND -> cash(command)
        LocalCashCloseNoteCodec.COMMAND -> joinCashCloseNoteHistory(command)
        StockStreamingCommandsV2.COLLECTION -> collection(command)
        else -> false
    }
    private suspend fun cash(command: RuntimeHistoricalCommand): Boolean {
        if (command.original !is RetainedOriginalV2.Legacy) return false
        val request = LocalCashCommandCodec.decode(command.normalized)
        check(LocalCashCommandCodec.normalize(command.normalized) == command.normalized)
        if (!(command.original as RetainedOriginalV2.Legacy).evidence.committed)
            return joinCashClosingRejectionHistory(command, request)
        val scope = LocalSalesScope.from(command.scope)
        val outcome: ProposedOutcome
        val target: DomainId
        val reason: String
        val resources: Set<RecoveryResourceKey>
        var revision = Revision(0)
        var amount: Money? = null
        var action = SecurityAction.CASH_CONFIGURE
        when (request) {
            is LocalCashRequestV1.ConfigureDrawer -> {
                target = request.drawerId; reason = "cash.configure.drawer"
                val kind = LocalSalesStoreProfile.DRAWER_KIND; val claim = configuredClaim(kind, target)
                check(command.aggregate(LocalSalesStoreProfile.key(kind, target)) == null && command.claim(claim) == null &&
                    command.claim(LocalSalesStoreProfile.activeDrawer(target)) == null)
                val value = LocalDrawerState(scope, target, Revision(0), request.label, request.currency)
                resources = setOf(cashResource(target))
                outcome = salesApplied(LocalSalesAction.DRAWER_CONFIGURED, target, listOf(LocalSalesRevision(kind, target, Revision(0))),
                    listOf(rootWrite(kind, target, value, LocalDrawerState.serializer(), null)), listOf(ClaimChange.Acquire(claim, target)))
            }
            is LocalCashRequestV1.ConfigureCustodian -> {
                target = request.custodianId; reason = "cash.configure.custodian"
                val kind = LocalSalesStoreProfile.CUSTODIAN_KIND; val claim = configuredClaim(kind, target)
                check(command.aggregate(LocalSalesStoreProfile.key(kind, target)) == null &&
                    command.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.WORK_KIND, target)) == null && command.claim(claim) == null &&
                    command.claim(LocalSalesStoreProfile.activeCustodian(target)) == null)
                val value = LocalCustodianState(scope, target, Revision(0)); val work = LocalWorkState(scope, target, Revision(0))
                resources = setOf(cashResource(target), orderResource(target))
                outcome = salesApplied(LocalSalesAction.CUSTODIAN_CONFIGURED, target, listOf(LocalSalesRevision(kind, target, Revision(0)),
                    LocalSalesRevision(LocalSalesStoreProfile.WORK_KIND, target, Revision(0))),
                    listOf(rootWrite(kind, target, value, LocalCustodianState.serializer(), null),
                        rootWrite(LocalSalesStoreProfile.WORK_KIND, target, work, LocalWorkState.serializer(), null)), listOf(ClaimChange.Acquire(claim, target)))
            }
            is LocalCashRequestV1.ConfigureSafe -> {
                target = request.custodyId; reason = "cash.configure.safe"
                val kind = LocalSalesStoreProfile.SAFE_KIND; val claim = configuredClaim(kind, target)
                check(command.aggregate(LocalSalesStoreProfile.key(kind, target)) == null && command.claim(claim) == null)
                custodian(command, request.custodianId)
                val value = LocalSafeState(scope, target, Revision(0), request.custodianId, request.label, Money(request.currency, 0))
                resources = setOf(cashResource(target), cashResource(request.custodianId))
                outcome = salesApplied(LocalSalesAction.SAFE_CONFIGURED, target, listOf(LocalSalesRevision(kind, target, Revision(0))),
                    listOf(rootWrite(kind, target, value, LocalSafeState.serializer(), null)), listOf(ClaimChange.Acquire(claim, target)))
            }
            is LocalCashRequestV1.Open -> {
                target = request.drawerId; reason = "cash.open"; revision = request.expectedDrawerRevision
                amount = request.opening; action = SecurityAction.CASH_OPEN
                val drawer = root(command, LocalSalesStoreProfile.DRAWER_KIND, target, LocalDrawerState.serializer()) { value, rev ->
                    check(value.scope == scope && value.id == target && value.revision == rev && value.activeSessionId == null && value.closingTransferId == null)
                    localLabel(value.label)
                }
                val custodian = custodian(command, command.actorId)
                work(command, command.actorId)
                check(drawer.value.revision == request.expectedDrawerRevision && custodian.value.revision == request.expectedCustodianRevision &&
                    drawer.value.currency == request.opening.currency && custodian.value.activeSessionId == null &&
                    command.claim(LocalSalesStoreProfile.activeDrawer(target)) == null &&
                    command.aggregate(CashNonSaleStoreProfile.sessionKey(request.sessionId)) == null)
                val unique = listOf(LocalSalesStoreProfile.unique("cash_session", request.sessionId),
                    LocalSalesStoreProfile.unique("opening_source", request.openingSourceId))
                check(unique.all { command.claim(it) == null })
                val retained = (command.original as RetainedOriginalV2.Legacy).evidence
                val row = retained.aggregateWrites.singleOrNull { it.key == CashNonSaleStoreProfile.sessionKey(request.sessionId) }
                    ?: error("Original cash opening unavailable")
                val raw = row.payload.copyBytes()
                val value = try { SalesSnapshotCodec.decodeCashSession(raw.decodeToString(throwOnInvalidSequence = true),
                    SalesSnapshotBinding(SalesSnapshotKind.CASH_SESSION, command.scope, request.sessionId, Revision(0))) } finally { raw.fill(0) }
                val zero = Money(request.opening.currency, 0)
                // Compare EVERY initial domain field. The decoded session is inert historical
                // data; it is not a reconstructed SalesCommand or an AuthorizedAction.
                check(value.id == request.sessionId && value.scope == command.scope && value.drawerId == target && value.custodianId == command.actorId &&
                    value.businessDate == request.businessDate.value && value.revision == Revision(0) &&
                    value.opening == CountedCash(request.opening, request.evidence.domain(command.actorId)) && value.openingSourceId == request.openingSourceId &&
                    value.totals == CashTotals(zero, zero, zero, zero, zero, zero, zero, zero) && value.expectedCash == request.opening &&
                    value.reservedCashOut == zero && value.cashLedgerRevision == Revision(0) && value.status == CashSessionStatus.OPEN &&
                    value.pendingAttemptIds.isEmpty() && value.pendingTransferIds.isEmpty() && value.closingCut == null && value.closeReport == null && value.review == null)
                val afterDrawer = drawer.value.copy(revision = drawer.value.revision.next(), activeSessionId = value.id)
                val afterCustodian = custodian.value.copy(revision = custodian.value.revision.next(), activeSessionId = value.id)
                resources = setOf(cashResource(target), cashResource(command.actorId), cashResource(value.id), cashResource(request.openingSourceId))
                outcome = salesApplied(LocalSalesAction.CASH_OPENED, value.id, listOf(LocalSalesRevision(CashNonSaleStoreProfile.SESSION_KIND, value.id, Revision(0)),
                    LocalSalesRevision(LocalSalesStoreProfile.DRAWER_KIND, target, afterDrawer.revision),
                    LocalSalesRevision(LocalSalesStoreProfile.CUSTODIAN_KIND, command.actorId, afterCustodian.revision)),
                    listOf(cashWrite(value, null), rootWrite(LocalSalesStoreProfile.DRAWER_KIND, target, afterDrawer, LocalDrawerState.serializer(), drawer.stored),
                        rootWrite(LocalSalesStoreProfile.CUSTODIAN_KIND, command.actorId, afterCustodian, LocalCustodianState.serializer(), custodian.stored)),
                    listOf(ClaimChange.Acquire(unique[0], value.id), ClaimChange.Acquire(unique[1], value.id),
                        ClaimChange.Acquire(LocalSalesStoreProfile.activeDrawer(target), value.id),
                        ClaimChange.Acquire(LocalSalesStoreProfile.activeCustodian(command.actorId), value.id)), value.expectedCash)
            }
            else -> return joinCashClosingHistory(command, request)
        }
        command.requireRequirement(salesAuthorizationRequirement(action, command.scope, target, revision,
            command.operationId, command.semanticDigest, reason, command.actorId, amount))
        command.requireLegacyEffects(outcome as? ProposedOutcome.Apply ?: error("Original cash effects unavailable"),
            resources + RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, command.actorId))
        return true
    }

    private suspend fun collection(command: RuntimeHistoricalCommand): Boolean {
        if (command.original is RetainedOriginalV2.Legacy) return joinOrderReassignmentRejectionHistory(command)
        if (command.original !is RetainedOriginalV2.Streamed) return false
        val request = LocalCollectionStockCodecV2.decode(command.normalized)
        check(LocalCollectionStockCodecV2.normalize(command.normalized) == command.normalized)
        val target: DomainId; val revision: Revision; val reason: String; val action: SecurityAction
        when (request) {
            is LocalCollectionStockRequestV2.CreateOrder -> { target = request.orderId; revision = Revision(0); reason = "order.create"; action = SecurityAction.ORDER_CREATE }
            is LocalCollectionStockRequestV2.ReplaceUnlockedOrder -> { target = request.orderId; revision = request.expectedOrderRevision; reason = "order.replace"; action = SecurityAction.ORDER_EDIT }
            is LocalCollectionStockRequestV2.OfferHandoff -> { target = request.orderId; revision = request.expectedOrderRevision; reason = "order.handoff.offer"; action = SecurityAction.ORDER_HANDOFF }
            is LocalCollectionStockRequestV2.CancelHandoff -> { target = request.orderId; revision = request.expectedOrderRevision; reason = "order.handoff.cancel"; action = SecurityAction.ORDER_HANDOFF }
            is LocalCollectionStockRequestV2.AcceptHandoff -> { target = request.orderId; revision = request.expectedOrderRevision; reason = "order.handoff.accept"; action = SecurityAction.ORDER_HANDOFF }
            is LocalCollectionStockRequestV2.ReassignOrder -> { target = request.orderId; revision = request.expectedOrderRevision; reason = request.reasonCode; action = SecurityAction.ORDER_REASSIGN }
            is LocalCollectionStockRequestV2.Begin -> { target = request.orderId; revision = request.expectedOrderRevision; reason = "collection.begin"; action = SecurityAction.COLLECTION_BEGIN }
            is LocalCollectionStockRequestV2.Finalize -> { target = request.orderId; revision = request.expectedOrderRevision; reason = "collection.finalize"; action = SecurityAction.COLLECTION_FINALIZE }
            else -> return false
        }
        val before = command.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.ORDER_KIND, target))
        val order = before?.let {
            check(it.schema == LocalSalesStoreProfile.schema(it.key.kind))
            SalesSnapshotCodec.decodeOrder(it.text(), SalesSnapshotBinding(SalesSnapshotKind.ORDER, command.scope, target, it.domainRevision()))
        }
        val amount = if (action == SecurityAction.COLLECTION_BEGIN || action == SecurityAction.COLLECTION_FINALIZE) order?.quote?.payable else null
        val actualAction = if (action == SecurityAction.COLLECTION_FINALIZE && amount?.minor == 0L) SecurityAction.COLLECTION_FINALIZE_ZERO else action
        val owner = if (request is LocalCollectionStockRequestV2.AcceptHandoff) command.actorId else order?.ownerId ?: command.actorId
        command.requireRequirement(salesAuthorizationRequirement(actualAction, command.scope, target, revision, command.operationId,
            command.semanticDigest, reason, owner, amount))
        // Historical receiving is independent of the original manager authorization.
        // This exact native producer must close it before business-only stock accounting.
        if (request is LocalCollectionStockRequestV2.ReassignOrder) {
            val receiving = command.localHandoffRecipientBefore(target, request.recipientId) ?: return false
            check(receiving.actorId == request.recipientId)
        }
        stock.requireHistoricalRelationalProducer(command)
        return true
    }
    private suspend fun custodian(command: RuntimeHistoricalCommand, id: DomainId): LocalSalesRow<LocalCustodianState> =
        root(command, LocalSalesStoreProfile.CUSTODIAN_KIND, id, LocalCustodianState.serializer()) { value, revision ->
            check(value.scope.value() == command.scope && value.id == id && value.revision == revision)
        }.also { check(command.claim(LocalSalesStoreProfile.activeCustodian(id)) == it.value.activeSessionId) }
    private suspend fun work(command: RuntimeHistoricalCommand, id: DomainId): LocalSalesRow<LocalWorkState> =
        root(command, LocalSalesStoreProfile.WORK_KIND, id, LocalWorkState.serializer()) { value, revision ->
            check(value.scope.value() == command.scope && value.id == id && value.revision == revision &&
                listOf(value.orderIds, value.returnIds, value.pendingClaimIds).all { it.size <= LocalSalesStoreProfile.MAX_OPEN_WORK && it.distinct().size == it.size })
        }
    private suspend fun <T> root(command: RuntimeHistoricalCommand, kind: String, id: DomainId, serializer: KSerializer<T>,
        validate: (T, Revision) -> Unit): LocalSalesRow<T> {
        val row = command.aggregate(LocalSalesStoreProfile.key(kind, id)) ?: error("Original cash source unavailable")
        check(row.schema == LocalSalesStoreProfile.schema(kind) && row.revision.value > 0)
        val value = decodeLocalSalesSource(row.payload, serializer)
        validate(value, row.domainRevision())
        val configured = if (kind == LocalSalesStoreProfile.WORK_KIND) LocalSalesStoreProfile.CUSTODIAN_KIND else kind
        check(command.claim(configuredClaim(configured, id)) == id)
        return LocalSalesRow(row, value)
    }
}
