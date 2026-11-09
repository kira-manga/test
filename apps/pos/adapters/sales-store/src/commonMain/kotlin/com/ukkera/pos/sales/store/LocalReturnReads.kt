package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.MasterReadHandler
import com.ukkera.pos.database.PreparedRead
import com.ukkera.pos.database.ReadContext
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.Tender
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi

@Serializable
public sealed interface LocalReturnQueryV1 {
    @Serializable @SerialName("RECEIPT") public data class Receipt(public val saleId: DomainId) : LocalReturnQueryV1
    @Serializable @SerialName("INTAKE") public data class Intake(public val claimId: DomainId) : LocalReturnQueryV1
    @Serializable @SerialName("PENDING_CLAIMS")
    public data class PendingClaims(public val after: DomainId? = null, public val limit: Int = 25) : LocalReturnQueryV1
    @Serializable @SerialName("ACCEPTED")
    public data class Accepted(public val saleId: DomainId, public val claimId: DomainId, public val returnId: DomainId) : LocalReturnQueryV1
    @Serializable @SerialName("OWN_UNSETTLED")
    public data class OwnUnsettled(public val after: DomainId? = null, public val limit: Int = 25) : LocalReturnQueryV1
}

/** Original receipt/remaining rights only: no old order ownership, staff identity or stock cost. */
@Serializable
public data class LocalReturnReceiptLineV1(
    public val portionId: DomainId, public val originalQuantity: Quantity, public val remainingQuantity: Quantity,
    public val originalRefundable: Money,
)

@Serializable
public data class LocalReturnClaimViewV1(
    public val claimId: DomainId, public val intakeRevision: Revision, public val recordedAt: UtcInstant,
    public val originalOperationId: OperationId, public val facts: LocalReturnClaimFactsV1,
    public val qualifiedClaimRevision: Revision?, public val policyId: DomainId?, public val policyRevision: Revision?,
    public val originalReceivedAt: UtcInstant?, public val remedyDueAt: UtcInstant?, public val acceptedReturnId: DomainId?,
)

@Serializable
public data class LocalTenderViewV1(
    public val kind: String, public val tenderId: DomainId? = null, public val policyRevision: Revision? = null,
    public val requiresTerminal: Boolean = false, public val requiresReference: Boolean = false,
)

@Serializable
public data class LocalRefundObligationViewV1(public val originalPaymentId: DomainId, public val tender: LocalTenderViewV1, public val amount: Money)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
public data class LocalRefundAttemptViewV1(
    public val attemptId: DomainId, public val originalPaymentId: DomainId, public val status: String,
    public val tender: LocalTenderViewV1, public val amount: Money, public val ownAttempt: Boolean,
    /** Only the original paying actor receives their exact recovery identities. */
    public val intentOperationId: OperationId?, public val activeRecordingOperationId: OperationId?,
    public val completedRecordingOperationId: OperationId?,
    public val cashSessionId: DomainId?, public val cashSessionRevision: Revision?,
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val activeRecordingProtocolVersion: Int? = null,
)

@Serializable
public data class LocalAcceptedReturnViewV1(
    public val returnId: DomainId, public val saleId: DomainId, public val claimId: DomainId, public val portionId: DomainId,
    public val quantity: Quantity, public val revision: Revision, public val requestedDisposition: String,
    public val originalPayable: Money, public val refunded: Money, public val due: Money, public val status: String,
    public val obligations: List<LocalRefundObligationViewV1>, public val attempts: List<LocalRefundAttemptViewV1>,
)

@Serializable
public sealed interface LocalReturnReadResultV1 {
    /** Missing/not visible source; no entitlement, deadline or zero liability is manufactured. */
    @Serializable @SerialName("NOT_AVAILABLE") public data object NotAvailable : LocalReturnReadResultV1
    @Serializable @SerialName("RECEIPT")
    public data class Receipt(public val saleId: DomainId, public val entitlementRevision: Revision,
        public val lines: List<LocalReturnReceiptLineV1>) : LocalReturnReadResultV1
    @Serializable @SerialName("CLAIMS")
    public data class Claims(public val claims: List<LocalReturnClaimViewV1>, public val nextAfter: DomainId?) : LocalReturnReadResultV1
    @Serializable @SerialName("RETURNS")
    public data class AcceptedReturns(public val returns: List<LocalAcceptedReturnViewV1>, public val nextAfter: DomainId?) : LocalReturnReadResultV1
}

public object LocalReturnReadCodec {
    public val QUERY: ReadKey = ReadKey("sales.return.read", 1)
    public fun request(query: LocalReturnQueryV1): ReadRequest = ReadRequest(QUERY, encode(query))
    public fun encode(query: LocalReturnQueryV1): StoredBytes {
        val limit = when (query) {
            is LocalReturnQueryV1.PendingClaims -> query.limit
            is LocalReturnQueryV1.OwnUnsettled -> query.limit
            else -> 1
        }
        require(limit in 1..25) { "sales.page.invalid" }
        return localSalesEncode(query, LocalReturnQueryV1.serializer(), 2_048)
    }
    public fun decode(body: StoredBytes): LocalReturnReadResultV1 = localSalesDecode(body, LocalReturnReadResultV1.serializer())
}

public class LocalReturnReadHandler : MasterReadHandler {
    override public val key: ReadKey get() = LocalReturnReadCodec.QUERY
    override public fun normalize(input: StoredBytes): StoredBytes =
        LocalReturnReadCodec.encode(localSalesDecode(input, LocalReturnQueryV1.serializer(), 2_048))

    override public suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        check(context.scope == view.scope && context.key == key) { "sales.context.invalid" }
        return when (val query = localSalesDecode(normalized, LocalReturnQueryV1.serializer(), 2_048)) {
            is LocalReturnQueryV1.Receipt -> {
                val original = findEntitlement(view, query.saleId)
                    ?: return returnReadUnavailable(context, query.saleId, SecurityAction.RETURN_RECEIPT_LOOKUP)
                val entitlement = original.entitlement.value
                val result = LocalReturnReadResultV1.Receipt(entitlement.saleId, entitlement.revision, entitlement.cursors.map { cursor ->
                    LocalReturnReceiptLineV1(cursor.portionId, cursor.originalQuantity, cursor.remainingQuantity,
                        cursor.originalComponents.fold(Money(entitlement.sale.quote.currency, 0)) { sum, component -> sum + component.amount })
                })
                localSalesRead(context, SecurityAction.RETURN_RECEIPT_LOOKUP, entitlement.saleId, entitlement.revision, context.actorId,
                    "return.read.receipt", result, LocalReturnReadResultV1.serializer())
            }
            is LocalReturnQueryV1.Intake -> {
                if (!localSalesSourcePresent(view, LocalSalesStoreProfile.key(LocalSalesStoreProfile.RETURN_INTAKE_KIND, query.claimId),
                        listOf(LocalSalesStoreProfile.unique("return_intake", query.claimId), LocalSalesStoreProfile.unique("return_claim", query.claimId)),
                        listOf(LocalSalesStoreProfile.key(LocalSalesStoreProfile.RETURN_CLAIM_KIND, query.claimId)))) {
                    return returnReadUnavailable(context, query.claimId, SecurityAction.RETURN_CLAIM)
                }
                val intake = readIntake(view, query.claimId)
                if (intake.value.originalActorId != context.actorId) return returnReadUnavailable(context, query.claimId, SecurityAction.RETURN_CLAIM)
                localSalesRead(context, SecurityAction.RETURN_CLAIM, query.claimId, intake.value.revision, context.actorId,
                    "return.read.intake", LocalReturnReadResultV1.Claims(listOf(claimView(view, intake)), null), LocalReturnReadResultV1.serializer())
            }
            is LocalReturnQueryV1.PendingClaims -> {
                if (!localCustodianSourcePresent(view, context.actorId)) return returnReadUnavailable(context, context.actorId, SecurityAction.RETURN_CLAIM)
                val work = LocalSalesStorage.work(view, context.actorId)
                val ids = work.value.pendingClaimIds.sortedBy { it.value }.filter { query.after == null || it.value > query.after.value }
                val selected = ids.take(query.limit)
                val claims = selected.map { id ->
                    val intake = readIntake(view, id)
                    check(intake.value.originalActorId == context.actorId && intake.value.boundSaleId == null) { "sales.claim.work" }
                    claimView(view, intake)
                }
                localSalesRead(context, SecurityAction.RETURN_CLAIM, work.value.id, work.value.revision, context.actorId,
                    "return.read.pending", LocalReturnReadResultV1.Claims(claims, if (selected.size < ids.size) selected.last() else null),
                    LocalReturnReadResultV1.serializer())
            }
            is LocalReturnQueryV1.Accepted -> {
                if (!localSalesSourcePresent(view, LocalSalesStoreProfile.key(LocalSalesStoreProfile.RETURN_KIND, query.returnId),
                        listOf(LocalSalesStoreProfile.unique("accepted_return", query.returnId), LocalSalesStoreProfile.unique("return_claim_source", query.returnId)))) {
                    return returnReadUnavailable(context, query.saleId, SecurityAction.RETURN_RECEIPT_LOOKUP)
                }
                val saleId = view.claim(LocalSalesStoreProfile.unique("accepted_return", query.returnId)) ?: error("sales.return.source")
                val claimId = view.claim(LocalSalesStoreProfile.unique("return_claim_source", query.returnId)) ?: error("sales.return.source")
                if (saleId != query.saleId || claimId != query.claimId) return returnReadUnavailable(context, query.saleId, SecurityAction.RETURN_RECEIPT_LOOKUP)
                val cut = readAcceptedReturn(view, query.saleId, query.claimId, query.returnId)
                localSalesRead(context, SecurityAction.RETURN_RECEIPT_LOOKUP, query.saleId, cut.original.original.entitlement.value.revision,
                    context.actorId, "return.read.accepted", LocalReturnReadResultV1.AcceptedReturns(listOf(returnView(view, cut, context.actorId)), null),
                    LocalReturnReadResultV1.serializer())
            }
            is LocalReturnQueryV1.OwnUnsettled -> {
                if (!localCustodianSourcePresent(view, context.actorId)) return returnReadUnavailable(context, context.actorId, SecurityAction.RETURN_RECEIPT_LOOKUP)
                val work = LocalSalesStorage.work(view, context.actorId)
                val ids = work.value.returnIds.sortedBy { it.value }.filter { query.after == null || it.value > query.after.value }
                val selected = ids.take(query.limit)
                val returns = selected.map { id ->
                    // Permanent source claims provide exact original links, never an amount/owner fallback.
                    val saleId = view.claim(LocalSalesStoreProfile.unique("accepted_return", id)) ?: error("sales.return.source")
                    // The accepted-return source index is retained independently for a bounded direct lookup.
                    val claimId = view.claim(LocalSalesStoreProfile.unique("return_claim_source", id)) ?: error("sales.return.source")
                    val cut = readAcceptedReturn(view, saleId, claimId, id)
                    check(cut.accepted.value.acceptingOperatorId == context.actorId) { "sales.return.work" }
                    returnView(view, cut, context.actorId)
                }
                localSalesRead(context, SecurityAction.RETURN_RECEIPT_LOOKUP, work.value.id, work.value.revision, context.actorId,
                    "return.read.own", LocalReturnReadResultV1.AcceptedReturns(returns, if (selected.size < ids.size) selected.last() else null),
                    LocalReturnReadResultV1.serializer())
            }
        }
    }
}

private fun returnReadUnavailable(context: ReadContext, targetId: DomainId, action: SecurityAction): PreparedRead =
    localSalesRead(context, action, targetId, Revision(0), context.actorId, "return.read.unavailable",
        LocalReturnReadResultV1.NotAvailable, LocalReturnReadResultV1.serializer())

private suspend fun claimView(view: CommandReadView, intake: LocalSalesRow<LocalReturnIntake>): LocalReturnClaimViewV1 {
    val value = intake.value
    val bound = value.boundSaleId?.let { readClaim(view, it, value.claimId).claim.value }
    return LocalReturnClaimViewV1(value.claimId, value.revision, value.recordedAt, value.originalOperationId, value.facts,
        bound?.revision, bound?.evidence?.policyId, bound?.evidence?.policyRevision,
        bound?.evidence?.customerReceivedAt?.let(::UtcInstant), bound?.evidence?.remedyDueAt?.let(::UtcInstant), bound?.acceptedReturnId)
}

private suspend fun returnView(view: CommandReadView, cut: LocalReturnCut, actorId: DomainId): LocalAcceptedReturnViewV1 {
    val value = cut.accepted.value
    val attempts = value.attempts.map { attempt ->
        val own = attempt.originalActorId == actorId
        val active = if (own && attempt.status.unresolved()) activeRecordingOperation(view, SalesRecordingKind.REFUND_ATTESTATION,
            value.id, attempt.id, actorId, attempt.originalCashSessionId, attempt.originalOperationId) else null
        val cash = if (own) LocalSalesStorage.cash(view, attempt.originalCashSessionId).session.value else null
        check(cash == null || cash.custodianId == actorId) { "sales.refund.session" }
        LocalRefundAttemptViewV1(attempt.id, attempt.originalPaymentId, attempt.status.name, tenderView(attempt.tender), attempt.amount,
            own, if (own) attempt.originalOperationId else null, active, if (own) attempt.refund?.recordingOperationId else null,
            cash?.id, cash?.revision, activeRecordingProtocolV2(view, active))
    }
    return LocalAcceptedReturnViewV1(value.id, value.saleId, value.claimId, value.portionId, value.quantity, value.revision,
        value.requestedDisposition.name, value.originalPayable, value.refunded, value.due, value.refundStatus.name,
        value.tenderObligations.map { LocalRefundObligationViewV1(it.originalPaymentId, tenderView(it.tender), it.amount) }, attempts)
}

internal fun tenderView(tender: Tender): LocalTenderViewV1 = when (tender) {
    Tender.Cash -> LocalTenderViewV1("CASH")
    is Tender.External -> LocalTenderViewV1(tender.kind.name, tender.tenderId, tender.policyRevision, tender.requiresTerminal, tender.requiresReference)
}
