package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.MasterReadHandler
import com.ukkera.pos.database.PreparedRead
import com.ukkera.pos.database.ReadContext
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.CashSession
import com.ukkera.pos.sales.SalesSnapshotLinks
import com.ukkera.pos.security.ActionBinding
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.AuthorizedAction
import com.ukkera.pos.security.OwnerConstraint
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.PrincipalKind
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.BusinessDate
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public sealed interface LocalCashQueryV1 {
    @Serializable @SerialName("OWN_CASH") public data object OwnCash : LocalCashQueryV1
    @Serializable @SerialName("SESSION") public data class Session(public val sessionId: DomainId) : LocalCashQueryV1
    @Serializable @SerialName("DRAWERS") public data class Drawers(public val after: DomainId? = null, public val limit: Int = 25) : LocalCashQueryV1
    @Serializable @SerialName("SAFE") public data class Safe(public val custodyId: DomainId) : LocalCashQueryV1
    @Serializable @SerialName("SAFE_CHOICES") public data class SafeChoices(public val after: DomainId? = null, public val limit: Int = 25) : LocalCashQueryV1
    @Serializable @SerialName("SAFE_CHOICE") public data class SafeChoice(public val custodyId: DomainId) : LocalCashQueryV1
    @Serializable @SerialName("TRANSFER") public data class Transfer(public val transferId: DomainId) : LocalCashQueryV1
}

@Serializable
public data class LocalCashSessionViewV1(
    public val id: DomainId, public val drawerId: DomainId, public val custodianId: DomainId,
    public val businessDate: BusinessDate, public val revision: Revision, public val cashLedgerRevision: Revision,
    public val status: String, public val opening: Money, public val expectedCash: Money, public val reservedCashOut: Money,
    public val received: Money, public val change: Money, public val refunds: Money, public val payIns: Money, public val payOuts: Money,
    public val safeDrops: Money, public val externalCollections: Money, public val externalRefunds: Money,
    public val pendingAttemptIds: List<DomainId>, public val pendingTransferIds: List<DomainId>,
    public val closingExpected: Money?, public val counted: Money?, public val variance: Money?,
    public val retainedFloat: Money?, public val actualSafeDelivery: Money?, public val closingTransferId: DomainId?,
)

/** Does not disclose another cashier's identity, count, cash balance or work. */
@Serializable
public data class LocalDrawerChoiceV1(
    public val id: DomainId, public val label: String, public val currency: Currency, public val revision: Revision,
    public val assigned: Boolean, public val awaitingClosingReceipt: Boolean,
)

/** Destination metadata only, not its custodian identity, balance or a sending permission. */
@Serializable
public data class LocalSafeChoiceV1(public val id: DomainId, public val label: String, public val currency: Currency, public val revision: Revision)

@Serializable
public sealed interface LocalCashReadResultV1 {
    /** Missing or not currently visible; no negative-history/custody proof is implied. */
    @Serializable @SerialName("NOT_AVAILABLE") public data object NotAvailable : LocalCashReadResultV1
    /** No session/balance or implied permission to self-configure is manufactured. */
    @Serializable @SerialName("NOT_CONFIGURED")
    public data class NotConfigured(public val custodianId: DomainId) : LocalCashReadResultV1

    @Serializable @SerialName("OWN_CASH")
    public data class OwnCash(
        public val custodianId: DomainId, public val assignmentRevision: Revision, public val workRevision: Revision,
        public val session: LocalCashSessionViewV1?, public val unsettledOrderIds: List<DomainId>, public val unresolvedReturnIds: List<DomainId>,
    ) : LocalCashReadResultV1

    @Serializable @SerialName("SESSION")
    public data class Session(public val session: LocalCashSessionViewV1) : LocalCashReadResultV1

    @Serializable @SerialName("DRAWERS")
    public data class Drawers(public val drawers: List<LocalDrawerChoiceV1>, public val nextAfter: DomainId?) : LocalCashReadResultV1

    @Serializable @SerialName("SAFE")
    public data class Safe(
        public val custodyId: DomainId, public val custodianId: DomainId, public val label: String,
        public val revision: Revision, public val received: Money,
    ) : LocalCashReadResultV1

    @Serializable @SerialName("SAFE_CHOICES")
    public data class SafeChoices(public val safes: List<LocalSafeChoiceV1>, public val nextAfter: DomainId?) : LocalCashReadResultV1

    @Serializable @SerialName("TRANSFER")
    public data class Transfer(
        public val transferId: DomainId, public val revision: Revision, public val senderSessionId: DomainId,
        public val receivingCustodyId: DomainId, public val amount: Money, public val closingDisposition: Boolean,
        public val status: String, public val sentAt: UtcInstant, public val receivedAt: UtcInstant?,
        /** Fresh optimistic preconditions only; no foreign session balance is disclosed. */
        public val senderSessionRevision: Revision, public val receivingCustodyRevision: Revision,
    ) : LocalCashReadResultV1
}

public object LocalCashReadCodec {
    public val QUERY: ReadKey = ReadKey("sales.cash.read", 1)
    public fun request(query: LocalCashQueryV1): ReadRequest = ReadRequest(QUERY, encode(query))
    public fun encode(query: LocalCashQueryV1): StoredBytes {
        val limit = when (query) {
            is LocalCashQueryV1.Drawers -> query.limit
            is LocalCashQueryV1.SafeChoices -> query.limit
            else -> 1
        }
        require(limit in 1..25) { "sales.page.invalid" }
        return localSalesEncode(query, LocalCashQueryV1.serializer(), 2_048)
    }
    public fun decode(body: StoredBytes): LocalCashReadResultV1 = localSalesDecode(body, LocalCashReadResultV1.serializer())
}

public class LocalCashReadHandler : MasterReadHandler {
    override public val key: ReadKey get() = LocalCashReadCodec.QUERY
    override public fun normalize(input: StoredBytes): StoredBytes =
        LocalCashReadCodec.encode(localSalesDecode(input, LocalCashQueryV1.serializer(), 2_048))

    override public suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        check(view.scope == context.scope && context.key == key) { "sales.context.invalid" }
        val request = localSalesDecode(normalized, LocalCashQueryV1.serializer(), 2_048)
        val actor = context.actorId
        return when (request) {
            LocalCashQueryV1.OwnCash -> {
                val custodianRow = view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.CUSTODIAN_KIND, actor))
                if (custodianRow == null) {
                    check(view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.WORK_KIND, actor)) == null &&
                        view.claim(configuredClaim(LocalSalesStoreProfile.CUSTODIAN_KIND, actor)) == null &&
                        view.claim(LocalSalesStoreProfile.activeCustodian(actor)) == null) { "sales.custody.unavailable" }
                    return localSalesRead(context, SecurityAction.CASH_READ, actor, Revision(0), actor, "cash.read.not_configured",
                        LocalCashReadResultV1.NotConfigured(actor), LocalCashReadResultV1.serializer())
                }
                val custodian = LocalSalesStorage.custodian(view, actor)
                val work = LocalSalesStorage.work(view, actor)
                val session = custodian.value.activeSessionId?.let { LocalSalesStorage.cash(view, it).session.value }
                localSalesRead(context, SecurityAction.CASH_READ, actor, custodian.value.revision, actor,
                    "cash.read.own", LocalCashReadResultV1.OwnCash(actor, custodian.value.revision, work.value.revision,
                        session?.let(::cashView), work.value.orderIds, work.value.returnIds), LocalCashReadResultV1.serializer())
            }
            is LocalCashQueryV1.Session -> {
                if (!localSalesSourcePresent(view, CashNonSaleStoreProfile.sessionKey(request.sessionId),
                        listOf(LocalSalesStoreProfile.unique("cash_session", request.sessionId)))) return cashReadUnavailable(context, request.sessionId)
                val cut = LocalSalesStorage.cash(view, request.sessionId)
                if (cut.session.value.custodianId != actor) return cashReadUnavailable(context, request.sessionId)
                localSalesRead(context, SecurityAction.CASH_READ, request.sessionId, cut.session.value.revision, actor,
                    "cash.read.session", LocalCashReadResultV1.Session(cashView(cut.session.value)), LocalCashReadResultV1.serializer())
            }
            is LocalCashQueryV1.Drawers -> {
                if (!localCustodianSourcePresent(view, actor)) return cashReadUnavailable(context, actor)
                val custodian = LocalSalesStorage.custodian(view, actor)
                val page = view.aggregateKeys(LocalSalesStoreProfile.DRAWER_KIND, request.after, request.limit)
                val drawers = page.keys.map { item -> LocalSalesStorage.drawer(view, item.id).value }.map { item ->
                    LocalDrawerChoiceV1(item.id, item.label, item.currency, item.revision, item.activeSessionId != null, item.closingTransferId != null)
                }
                localSalesRead(context, SecurityAction.CASH_READ, actor, custodian.value.revision, actor, "cash.read.drawers",
                    LocalCashReadResultV1.Drawers(drawers, page.nextAfter), LocalCashReadResultV1.serializer())
            }
            is LocalCashQueryV1.Safe -> {
                if (!localSalesSourcePresent(view, LocalSalesStoreProfile.key(LocalSalesStoreProfile.SAFE_KIND, request.custodyId),
                        listOf(configuredClaim(LocalSalesStoreProfile.SAFE_KIND, request.custodyId)))) return cashReadUnavailable(context, request.custodyId)
                val safe = LocalSalesStorage.safe(view, request.custodyId).value
                if (safe.custodianId != actor) return cashReadUnavailable(context, request.custodyId)
                localSalesRead(context, SecurityAction.CASH_READ, safe.id, safe.revision, safe.custodianId, "cash.read.safe",
                    LocalCashReadResultV1.Safe(safe.id, safe.custodianId, safe.label, safe.revision, safe.received), LocalCashReadResultV1.serializer())
            }
            is LocalCashQueryV1.SafeChoices -> {
                val page = view.aggregateKeys(LocalSalesStoreProfile.SAFE_KIND, request.after, request.limit)
                val safes = page.keys.map { safeChoice(LocalSalesStorage.safe(view, it.id).value) }
                localSalesRead(context, SecurityAction.CASH_READ, actor, Revision(0), actor, "cash.read.safe_choices",
                    LocalCashReadResultV1.SafeChoices(safes, page.nextAfter), LocalCashReadResultV1.serializer())
            }
            is LocalCashQueryV1.SafeChoice -> {
                if (!localSalesSourcePresent(view, LocalSalesStoreProfile.key(LocalSalesStoreProfile.SAFE_KIND, request.custodyId),
                        listOf(configuredClaim(LocalSalesStoreProfile.SAFE_KIND, request.custodyId)))) return cashReadUnavailable(context, request.custodyId)
                val safe = LocalSalesStorage.safe(view, request.custodyId).value
                localSalesRead(context, SecurityAction.CASH_READ, safe.id, safe.revision, actor, "cash.read.safe_choice",
                    LocalCashReadResultV1.SafeChoices(listOf(safeChoice(safe)), null), LocalCashReadResultV1.serializer())
            }
            is LocalCashQueryV1.Transfer -> {
                if (!localSalesSourcePresent(view, LocalSalesStoreProfile.key(LocalSalesStoreProfile.TRANSFER_KIND, request.transferId),
                        listOf(LocalSalesStoreProfile.unique("safe_transfer", request.transferId),
                            com.ukkera.pos.database.ClaimKey(LocalSalesStoreProfile.SAFE_RECEIPT_NAMESPACE, request.transferId.value)))) {
                    return cashReadUnavailable(context, request.transferId)
                }
                val transfer = LocalSalesStorage.transfer(view, request.transferId).value
                val safe = LocalSalesStorage.safe(view, transfer.receivingCustodyId).value
                if (transfer.senderCustodianId != actor && safe.custodianId != actor) return cashReadUnavailable(context, request.transferId)
                val session = LocalSalesStorage.cash(view, transfer.senderSessionId).session.value
                requireLocalCashTransfer(view, transfer, session)
                check(safe.received.currency == transfer.amount.currency) { "sales.custody.unavailable" }
                check(view.claim(com.ukkera.pos.database.ClaimKey(LocalSalesStoreProfile.SAFE_RECEIPT_NAMESPACE, transfer.id.value)) ==
                    if (transfer.receipt == null) null else safe.id) { "sales.custody.unavailable" }
                localSalesRead(context, SecurityAction.CASH_READ, transfer.id, transfer.revision, actor, "cash.read.transfer",
                    LocalCashReadResultV1.Transfer(transfer.id, transfer.revision, transfer.senderSessionId, transfer.receivingCustodyId,
                        transfer.amount, transfer.closingDisposition, transfer.status.name, UtcInstant(transfer.sentEvidence.occurredAt),
                        transfer.receipt?.let { UtcInstant(it.occurredAt) }, session.revision, safe.revision), LocalCashReadResultV1.serializer())
            }
        }
    }
}

private fun safeChoice(value: LocalSafeState): LocalSafeChoiceV1 = LocalSafeChoiceV1(value.id, value.label, value.received.currency, value.revision)

private fun cashReadUnavailable(context: ReadContext, targetId: DomainId): PreparedRead =
    localSalesRead(context, SecurityAction.CASH_READ, targetId, Revision(0), context.actorId, "cash.read.unavailable",
        LocalCashReadResultV1.NotAvailable, LocalCashReadResultV1.serializer())

internal fun cashView(value: CashSession): LocalCashSessionViewV1 = LocalCashSessionViewV1(value.id, value.drawerId,
    value.custodianId, BusinessDate(value.businessDate), value.revision, value.cashLedgerRevision, value.status.name,
    value.opening.amount, value.expectedCash, value.reservedCashOut, value.totals.received, value.totals.change,
    value.totals.refunds, value.totals.payIns, value.totals.payOuts, value.totals.safeDrops, value.totals.externalCollections,
    value.totals.externalRefunds, value.pendingAttemptIds, value.pendingTransferIds, value.closingCut?.expected,
    value.closeReport?.count?.amount, value.closeReport?.variance, value.closeReport?.retainedFloat,
    value.closeReport?.actualSafeDelivery, value.closeReport?.safeTransferId)

internal fun <T> localSalesRead(
    context: ReadContext, action: SecurityAction, targetId: DomainId, revision: Revision, ownerId: DomainId,
    reason: String, value: T, serializer: kotlinx.serialization.KSerializer<T>,
): PreparedRead {
    val requirement = AuthorizationRequirement(Permission(action, PermissionVerb.PERFORM), context.scope,
        ActionBinding(targetId, revision, context.semanticDigest, null, reason),
        owner = if (action.ownerPrivate) OwnerConstraint.OwnerPrivate(ownerId) else OwnerConstraint.Unrestricted)
    // Materialize no public result until the kernel has checked every current grant.
    return object : PreparedRead() {
        override val requirements: List<AuthorizationRequirement> = listOf(requirement)
        override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes {
            check(authorizations.size == 1 && authorizations.single().principalKind == PrincipalKind.LOCAL_EMPLOYEE &&
                authorizations.single().isBoundTo(requirement, context.actorId)) { "sales.authorization.invalid" }
            return localSalesEncode(value, serializer)
        }
    }
}
