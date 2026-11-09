package com.ukkera.pos.sales.store

import com.ukkera.pos.database.ClaimKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.MasterReadHandler
import com.ukkera.pos.database.PreparedRead
import com.ukkera.pos.database.ReadContext
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.CashSessionStatus
import com.ukkera.pos.sales.CashierEvidence
import com.ukkera.pos.sales.SafeTransferStatus
import com.ukkera.pos.sales.SalesSnapshotLinks
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Separate wire contract: CASH_REVIEW never broadens the owner-private cash read. */
@Serializable
public sealed interface LocalCashCloseQueryV1 {
    public val sessionId: DomainId
    @Serializable @SerialName("OWN_CLOSE")
    public data class Own(override val sessionId: DomainId) : LocalCashCloseQueryV1
    @Serializable @SerialName("REVIEW_CLOSE")
    public data class Review(override val sessionId: DomainId) : LocalCashCloseQueryV1
}

@Serializable
public data class LocalCashActorEvidenceV1(
    public val evidenceId: DomainId, public val actorId: DomainId, public val occurredAt: UtcInstant,
)

@Serializable
public data class LocalCashReviewViewV1(
    public val reviewerId: DomainId, public val evidence: LocalCashActorEvidenceV1, public val reason: String,
)

/** Only this close's custody link, not the receiving safe balance or the next shift. */
@Serializable
public data class LocalClosingTransferViewV1(
    public val id: DomainId, public val revision: Revision, public val receivingCustodyId: DomainId,
    public val status: String, public val delivery: LocalCashActorEvidenceV1, public val receipt: LocalCashActorEvidenceV1?,
)

@Serializable
public sealed interface LocalCashCloseReadResultV1 {
    /** Missing, active or outside this actor's projection are deliberately indistinguishable. */
    @Serializable @SerialName("NOT_AVAILABLE")
    public data object NotAvailable : LocalCashCloseReadResultV1
    @Serializable @SerialName("CLOSE")
    public data class Close(
        public val session: LocalCashSessionViewV1,
        public val countEvidence: LocalCashActorEvidenceV1,
        public val submittedBy: DomainId, public val operationId: OperationId, public val varianceReason: String?,
        public val closingLedgerRevision: Revision,
        public val transfer: LocalClosingTransferViewV1?, public val review: LocalCashReviewViewV1?,
    ) : LocalCashCloseReadResultV1
}

public object LocalCashCloseReadCodec {
    public val QUERY: ReadKey = ReadKey("sales.cash.close.read", 1)
    public fun request(query: LocalCashCloseQueryV1): ReadRequest = ReadRequest(QUERY, encode(query))
    public fun encode(query: LocalCashCloseQueryV1): StoredBytes = localSalesEncode(query, LocalCashCloseQueryV1.serializer(), 2_048)
    public fun decode(body: StoredBytes): LocalCashCloseReadResultV1 = localSalesDecode(body, LocalCashCloseReadResultV1.serializer())
}

/** Exact-ID only; no private active-session enumeration or foreign cursor leakage. */
public class LocalCashCloseReadHandler : MasterReadHandler {
    override public val key: ReadKey get() = LocalCashCloseReadCodec.QUERY
    override public fun normalize(input: StoredBytes): StoredBytes =
        LocalCashCloseReadCodec.encode(localSalesDecode(input, LocalCashCloseQueryV1.serializer(), 2_048))

    override public suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        check(view.scope == context.scope && context.key == key) { "sales.context.invalid" }
        val query = localSalesDecode(normalized, LocalCashCloseQueryV1.serializer(), 2_048)
        val action = if (query is LocalCashCloseQueryV1.Review) SecurityAction.CASH_REVIEW else SecurityAction.CASH_READ
        fun unavailable(): PreparedRead = localSalesRead(context, action, query.sessionId, Revision(0), context.actorId,
            "cash.close.read.unavailable", LocalCashCloseReadResultV1.NotAvailable, LocalCashCloseReadResultV1.serializer())
        if (!localSalesSourcePresent(view, CashNonSaleStoreProfile.sessionKey(query.sessionId),
                listOf(LocalSalesStoreProfile.unique("cash_session", query.sessionId)))) return unavailable()
        val cut = LocalSalesStorage.cash(view, query.sessionId)
        val session = cut.session.value
        if (session.status !in setOf(CashSessionStatus.SUBMITTED_CLOSED, CashSessionStatus.REVIEWED) ||
            query is LocalCashCloseQueryV1.Own && session.custodianId != context.actorId ||
            query is LocalCashCloseQueryV1.Review && session.custodianId == context.actorId) return unavailable()
        val report = checkNotNull(session.closeReport)
        check(report.sessionId == session.id && report.cut == session.closingCut &&
            report.submittedBy == session.custodianId && report.count.evidence.actorId == session.custodianId &&
            report.variance == report.count.amount - report.cut.expected &&
            report.count.amount == report.retainedFloat + report.actualSafeDelivery) { "sales.close.invalid" }
        session.review?.let {
            check(it.originalReport == report && it.reviewerId != session.custodianId &&
                it.evidence.actorId == it.reviewerId) { "sales.close.invalid" }
        }
        val transfer = report.safeTransferId?.let { id ->
            val value = LocalSalesStorage.transfer(view, id).value
            SalesSnapshotLinks.requireSafeTransfer(value, session)
            val safe = LocalSalesStorage.safe(view, value.receivingCustodyId).value
            check(value.closingDisposition && value.amount == report.actualSafeDelivery &&
                safe.received.currency == value.amount.currency && safe.custodianId != session.custodianId &&
                value.receipt?.actorId?.let { it == safe.custodianId } != false &&
                view.claim(ClaimKey(LocalSalesStoreProfile.SAFE_RECEIPT_NAMESPACE, id.value)) ==
                    if (value.receipt == null) null else safe.id) { "sales.custody.unavailable" }
            if (value.status == SafeTransferStatus.AWAITING_RECEIPT) {
                check(cut.drawer.value.activeSessionId == null && cut.drawer.value.closingTransferId == id) { "sales.custody.unavailable" }
            } else check(cut.drawer.value.closingTransferId != id) { "sales.custody.unavailable" }
            LocalClosingTransferViewV1(id, value.revision, value.receivingCustodyId, value.status.name,
                value.sentEvidence.closeView(), value.receipt?.closeView())
        }
        check((report.actualSafeDelivery.minor > 0L) == (transfer != null)) { "sales.custody.unavailable" }
        val result = LocalCashCloseReadResultV1.Close(cashView(session), report.count.evidence.closeView(),
            report.submittedBy, report.operationId, report.varianceReason, report.cut.cashLedgerRevision, transfer,
            session.review?.let { LocalCashReviewViewV1(it.reviewerId, it.evidence.closeView(), it.reason) })
        return localSalesRead(context, action, session.id, session.revision, session.custodianId,
            if (query is LocalCashCloseQueryV1.Review) "cash.close.read.review" else "cash.close.read.own",
            result, LocalCashCloseReadResultV1.serializer())
    }
}

private fun CashierEvidence.closeView(): LocalCashActorEvidenceV1 = LocalCashActorEvidenceV1(evidenceId, actorId, UtcInstant(occurredAt))
