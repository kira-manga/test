package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.MasterReadHandler
import com.ukkera.pos.database.PreparedRead
import com.ukkera.pos.database.ReadContext
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.CashSessionStatus
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A cursor is a locator, never authority; every page rechecks actor and exact session cut. */
@Serializable
public data class LocalCashCountQueryV1(
    public val sessionId: DomainId, public val review: Boolean = false,
    public val expectedSessionRevision: Revision? = null, public val beforeCountId: DomainId? = null,
    public val limit: Int = 10,
)

@Serializable
public data class LocalCashCountViewV1(
    public val id: DomainId, public val position: Revision, public val previousCountId: DomainId?,
    public val replacesCountId: DomainId?, public val cycleRevision: Revision, public val sessionRevision: Revision,
    public val ledgerRevision: Revision, public val expected: Money, public val counted: Money,
    public val evidence: LocalCashActorEvidenceV1, public val reason: String?,
    public val operationId: OperationId, public val recordedAt: UtcInstant,
)

@Serializable
public sealed interface LocalCashCountReadResultV1 {
    @Serializable @SerialName("NOT_AVAILABLE")
    public data object NotAvailable : LocalCashCountReadResultV1
    @Serializable @SerialName("CUT_CHANGED")
    public data object CutChanged : LocalCashCountReadResultV1
    @Serializable @SerialName("COUNTS")
    public data class Counts(
        public val session: LocalCashSessionViewV1,
        public val latest: LocalCashCountViewV1?, public val currentCountId: DomainId?,
        public val submittedCountId: DomainId?, public val transferId: DomainId?,
        public val transferRevision: Revision?, public val transferStatus: String?, public val retainedFloat: Money?,
        public val counts: List<LocalCashCountViewV1>, public val nextBeforeCountId: DomainId?,
    ) : LocalCashCountReadResultV1
}

public object LocalCashCountReadCodec {
    public val QUERY: ReadKey = ReadKey("sales.cash.count.read", 1)
    public fun request(query: LocalCashCountQueryV1): ReadRequest = ReadRequest(QUERY, encode(query))
    public fun encode(query: LocalCashCountQueryV1): StoredBytes {
        require(query.limit in 1..16 && (query.beforeCountId == null || query.expectedSessionRevision != null))
        return localSalesEncode(query, LocalCashCountQueryV1.serializer(), 2_048)
    }
    public fun decode(body: StoredBytes): LocalCashCountReadResultV1 = localSalesDecode(body, LocalCashCountReadResultV1.serializer())
}

/** Owner-private counts, or a separate closed-only/non-self reviewer projection. */
public class LocalCashCountReadHandler : MasterReadHandler {
    override public val key: ReadKey get() = LocalCashCountReadCodec.QUERY
    override public fun normalize(input: StoredBytes): StoredBytes =
        LocalCashCountReadCodec.encode(localSalesDecode(input, LocalCashCountQueryV1.serializer(), 2_048))

    override public suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        check(context.scope == view.scope && context.key == key) { "sales.context.invalid" }
        val query = localSalesDecode(normalized, LocalCashCountQueryV1.serializer(), 2_048)
        val permission = if (query.review) SecurityAction.CASH_REVIEW else SecurityAction.CASH_READ
        fun result(value: LocalCashCountReadResultV1, revision: Revision = Revision(0), owner: DomainId = context.actorId): PreparedRead =
            localSalesRead(context, permission, query.sessionId, revision, owner,
                "cash.count.read", value, LocalCashCountReadResultV1.serializer())
        if (!localSalesSourcePresent(view, CashNonSaleStoreProfile.sessionKey(query.sessionId),
                listOf(LocalSalesStoreProfile.unique("cash_session", query.sessionId))))
            return result(LocalCashCountReadResultV1.NotAvailable)
        val session = LocalSalesStorage.cash(view, query.sessionId).session.value
        if (query.review && (session.custodianId == context.actorId || session.status !in
                setOf(CashSessionStatus.SUBMITTED_CLOSED, CashSessionStatus.REVIEWED)) ||
            !query.review && session.custodianId != context.actorId) return result(LocalCashCountReadResultV1.NotAvailable)
        if (query.expectedSessionRevision != null && query.expectedSessionRevision != session.revision)
            return result(LocalCashCountReadResultV1.CutChanged, session.revision, session.custodianId)
        val head = LocalCashCountStorage.head(view, session)
        val rows = mutableListOf<LocalCashCountViewV1>()
        var newer: LocalClosingCountRecordV1? = null
        var at = head?.record?.value?.id
        if (query.beforeCountId != null) {
            if (head == null) return result(LocalCashCountReadResultV1.CutChanged, session.revision, session.custodianId)
            newer = LocalCashCountStorage.cursorRecord(view, session, query.beforeCountId)?.value
                ?: return result(LocalCashCountReadResultV1.NotAvailable, session.revision, session.custodianId)
            requireLocalSalesSource(newer.position <= head.record.value.position)
            at = newer.previousCountId
        }
        while (at != null && rows.size < query.limit) {
            val record = LocalCashCountStorage.record(view, session, at).value
            newer?.let { LocalCashCountStorage.requirePrevious(it, record) }
            rows += record.view()
            newer = record
            at = record.previousCountId
        }
        val current = head?.takeIf { it.isCurrent(session) }
        val report = session.closeReport
        val submitted = head?.takeIf { report != null && report.count == it.count.count && report.cut == it.count.cut }
        val transfer = current?.transfer?.value ?: submitted?.transfer?.value
        return result(LocalCashCountReadResultV1.Counts(cashView(session), head?.record?.value?.view(), current?.count?.id,
            submitted?.count?.id, transfer?.id, transfer?.revision, transfer?.status?.name,
            if (transfer != null) head?.index?.value?.retainedFloat else null, rows,
            if (at != null) rows.lastOrNull()?.id else null), session.revision, session.custodianId)
    }
}

private fun LocalClosingCountRecordV1.view(): LocalCashCountViewV1 = LocalCashCountViewV1(id, position, previousCountId,
    replacesCountId, cycleRevision, sessionRevision, ledgerRevision, expected, counted, evidence, reason, operationId, recordedAt)
