package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.MasterReadHandler
import com.ukkera.pos.database.PreparedRead
import com.ukkera.pos.database.ReadContext
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.security.ActionBinding
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.AuthorizedAction
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.PrincipalKind
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public sealed interface LocalReturnCaseQueryV1 {
    @Serializable @SerialName("RECEIPT")
    public data class Receipt(public val saleId: DomainId, public val afterPortionId: DomainId? = null, public val limit: Int = 16) : LocalReturnCaseQueryV1
    @Serializable @SerialName("OWN_WORK")
    public data class OwnWork(public val after: DomainId? = null, public val limit: Int = 10) : LocalReturnCaseQueryV1
    @Serializable @SerialName("CASE")
    public data class Case(public val caseId: DomainId) : LocalReturnCaseQueryV1
    @Serializable @SerialName("EVENTS")
    public data class Events(public val caseId: DomainId, public val afterOrdinal: Int = -1, public val limit: Int = 8) : LocalReturnCaseQueryV1
}

/** Legal-case projection only: no source producer employees, stock costs or hidden cash balances. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
public data class LocalReturnCaseDecisionViewV1(
    public val operationId: OperationId, public val outcome: LocalReturnOrdinaryAssessmentV1,
    public val reasonCode: String, public val route: String,
    public val policyId: DomainId?, public val policyRevision: Revision?,
    public val salePromiseCaptureId: DomainId?, public val applicableRightsIds: List<DomainId>,
    public val customerReceiptIds: List<DomainId>, public val customerReceivedAt: UtcInstant?,
    public val receiptTimeZoneId: String?, public val remedyDueAt: UtcInstant?,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    public val originalCommercialTiming: LocalReturnCommercialTimingV1? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    public val ordinaryTiming: LocalReturnOrdinaryTimingV1? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    public val ordinaryInspection: LocalReturnOrdinaryInspectionV1? = null,
) {
    init {
        require(originalCommercialTiming == null || outcome == LocalReturnOrdinaryAssessmentV1.PENDING)
    }
}

@Serializable
public data class LocalReturnCaseSummaryV1(
    public val caseId: DomainId, public val revision: Revision, public val stage: LocalReturnCaseStageV1,
    public val recordedAt: UtcInstant, public val saleId: DomainId?, public val portionId: DomainId?,
    public val decisionOperationId: OperationId?, public val ordinaryAssessment: LocalReturnOrdinaryAssessmentV1?,
    public val route: String?, public val remedyDueAt: UtcInstant?, public val acceptedReturnId: DomainId?,
)

@Serializable
public data class LocalReturnCaseViewV1(
    public val summary: LocalReturnCaseSummaryV1, public val originalOperationId: OperationId,
    public val facts: LocalReturnCaseFactsV1, public val binding: LocalReturnCaseReceiptBindingV1?,
    public val qualifiedClaimRevision: Revision?, public val decision: LocalReturnCaseDecisionViewV1?,
    public val eventCount: Int,
)

@Serializable
public data class LocalReturnCaseEventViewV1(
    public val ordinal: Int, public val operationId: OperationId, public val recordedAt: UtcInstant,
    public val action: LocalReturnCaseActionV1, public val binding: LocalReturnCaseReceiptBindingV1?,
    public val decision: LocalReturnCaseDecisionViewV1?,
)

@Serializable
public sealed interface LocalReturnCaseReadResultV1 {
    @Serializable @SerialName("NOT_AVAILABLE") public data object NotAvailable : LocalReturnCaseReadResultV1
    /** No row is silently skipped when even one complete projection exceeds this version's budget. */
    @Serializable @SerialName("CAPACITY_REQUIRED") public data object CapacityRequired : LocalReturnCaseReadResultV1
    @Serializable @SerialName("RECEIPT")
    public data class Receipt(public val saleId: DomainId, public val entitlementRevision: Revision,
        public val lines: List<LocalReturnReceiptLineV1>, public val nextAfterPortionId: DomainId?) : LocalReturnCaseReadResultV1
    @Serializable @SerialName("WORK")
    public data class Work(public val indexRevision: Revision?, public val cases: List<LocalReturnCaseSummaryV1>,
        public val nextAfter: DomainId?) : LocalReturnCaseReadResultV1
    @Serializable @SerialName("CASE")
    public data class Case(public val value: LocalReturnCaseViewV1) : LocalReturnCaseReadResultV1
    @Serializable @SerialName("EVENTS")
    public data class Events(public val caseId: DomainId, public val caseRevision: Revision,
        public val events: List<LocalReturnCaseEventViewV1>, public val nextAfterOrdinal: Int?) : LocalReturnCaseReadResultV1
}

public object LocalReturnCaseReadCodecV1 {
    public val QUERY: ReadKey = ReadKey("sales.return.case.read", 1)
    public fun request(query: LocalReturnCaseQueryV1): ReadRequest = ReadRequest(QUERY, encode(query))
    public fun encode(query: LocalReturnCaseQueryV1): StoredBytes {
        require(when (query) {
            is LocalReturnCaseQueryV1.Receipt -> query.limit in 1..16
            is LocalReturnCaseQueryV1.OwnWork -> query.limit in 1..10
            is LocalReturnCaseQueryV1.Events -> query.limit in 1..8 && query.afterOrdinal in -1 until LocalReturnCaseProfileV1.MAX_EVENTS
            is LocalReturnCaseQueryV1.Case -> true
        }) { "sales.return.case_page" }
        return localSalesEncode(query, LocalReturnCaseQueryV1.serializer(), 2_048)
    }
    public fun decode(body: StoredBytes): LocalReturnCaseReadResultV1 =
        localSalesDecode(body, LocalReturnCaseReadResultV1.serializer(), LocalReturnCaseProfileV1.MAX_READ_BYTES)
}

public class LocalReturnCaseReadHandler : MasterReadHandler {
    override public val key: ReadKey get() = LocalReturnCaseReadCodecV1.QUERY
    override public fun normalize(input: StoredBytes): StoredBytes =
        LocalReturnCaseReadCodecV1.encode(localSalesDecode(input, LocalReturnCaseQueryV1.serializer(), 2_048))

    override public suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        check(context.scope == view.scope && context.key == key) { "sales.context.invalid" }
        return when (val query = localSalesDecode(normalized, LocalReturnCaseQueryV1.serializer(), 2_048)) {
            is LocalReturnCaseQueryV1.Receipt -> {
                val source = findEntitlement(view, query.saleId)
                if (source == null) unavailableCaseRead(context, query.saleId, SecurityAction.RETURN_RECEIPT_LOOKUP)
                else {
                    val entitlement = source.entitlement.value
                    val available = entitlement.cursors.sortedBy { it.portionId.value }.filter {
                        query.afterPortionId == null || it.portionId.value > query.afterPortionId.value
                    }
                    val selected = available.take(query.limit).map { cursor -> LocalReturnReceiptLineV1(cursor.portionId,
                        cursor.originalQuantity, cursor.remainingQuantity,
                        cursor.originalComponents.fold(Money(entitlement.sale.quote.currency, 0)) { sum, item -> sum + item.amount }) }
                    protectedCaseRead(context, SecurityAction.RETURN_RECEIPT_LOOKUP, query.saleId, entitlement.revision, "return.case.read.receipt") {
                        boundedCasePage(selected.size) { size -> LocalReturnCaseReadResultV1.Receipt(query.saleId, entitlement.revision,
                            selected.take(size), if (size < available.size && size > 0) selected[size - 1].portionId else null) }
                    }
                }
            }
            is LocalReturnCaseQueryV1.OwnWork -> {
                val work = findReturnCaseWork(view, context.actorId)
                val available = work?.value?.entries.orEmpty().filter { query.after == null || it.caseId.value > query.after.value }
                val selected = available.take(query.limit).map { entry ->
                    val cut = readReturnCase(view, entry.caseId)
                    requireLocalSalesSource(cut.row.value.ownerId == context.actorId)
                    caseSummary(cut)
                }
                protectedCaseRead(context, SecurityAction.RETURN_CLAIM, context.actorId, work?.value?.revision ?: Revision(0), "return.case.read.work") {
                    boundedCasePage(selected.size) { size -> LocalReturnCaseReadResultV1.Work(work?.value?.revision,
                        selected.take(size), if (size < available.size && size > 0) selected[size - 1].caseId else null) }
                }
            }
            is LocalReturnCaseQueryV1.Case -> {
                val cut = findReturnCase(view, query.caseId)
                if (cut == null || cut.row.value.ownerId != context.actorId) unavailableCaseRead(context, query.caseId)
                else {
                    val state = cut.row.value
                    val claim = if (state.stage == LocalReturnCaseStageV1.PENDING) null else readReturnCaseClaim(view,
                        readEntitlement(view, checkNotNull(cut.binding?.value?.binding).saleId), query.caseId)
                    val value = LocalReturnCaseViewV1(caseSummary(cut), state.originalOperationId, state.facts, cut.binding?.value?.binding,
                        claim?.claim?.value?.revision,
                        cut.decision?.value?.decisionView(), state.events.size)
                    protectedCaseRead(context, SecurityAction.RETURN_CLAIM, query.caseId, state.revision, "return.case.read.case") {
                        boundedCaseValue(LocalReturnCaseReadResultV1.Case(value))
                    }
                }
            }
            is LocalReturnCaseQueryV1.Events -> {
                val cut = findReturnCase(view, query.caseId)
                if (cut == null || cut.row.value.ownerId != context.actorId) unavailableCaseRead(context, query.caseId)
                else {
                    val available = cut.row.value.events.withIndex().filter { it.index > query.afterOrdinal }
                    val selected = available.take(query.limit).map { item ->
                        val event = readReturnCaseEvent(view, item.value).value
                        requireLocalSalesSource(event.after.caseId == query.caseId && event.after.ownerId == context.actorId &&
                            event.after.revision.value == item.index.toLong() && event.after.events == cut.row.value.events.take(item.index + 1))
                        LocalReturnCaseEventViewV1(item.index, event.operationId, event.recordedAt, event.action, event.binding, event.decisionView())
                    }
                    protectedCaseRead(context, SecurityAction.RETURN_CLAIM, query.caseId, cut.row.value.revision, "return.case.read.events") {
                        boundedCasePage(selected.size) { size -> LocalReturnCaseReadResultV1.Events(query.caseId, cut.row.value.revision,
                            selected.take(size), if (size < available.size && size > 0) selected[size - 1].ordinal else null) }
                    }
                }
            }
        }
    }
}

private fun caseSummary(cut: LocalReturnCaseCut): LocalReturnCaseSummaryV1 {
    val state = cut.row.value
    val binding = cut.binding?.value?.binding
    val decision = cut.decision?.value?.assessment
    return LocalReturnCaseSummaryV1(state.caseId, state.revision, state.stage, state.recordedAt,
        binding?.saleId, binding?.portionId, state.decisionOperationId, decision?.outcome, decision?.route,
        decision?.remedyDueAt, state.acceptedReturnId)
}

private fun LocalReturnCaseEventV1.decisionView(): LocalReturnCaseDecisionViewV1? = assessment?.let {
    LocalReturnCaseDecisionViewV1(operationId, it.outcome, it.reasonCode, it.route, it.policyId, it.policyRevision,
        it.salePolicyCapture?.id, it.applicableRights.map { source -> source.id }, it.customerReceipts.map { source -> source.id },
        it.customerReceivedAt, it.receiptTimeZoneId, it.remedyDueAt, it.originalCommercialTiming,
        it.ordinaryTiming, ordinaryInspection)
}

private fun unavailableCaseRead(context: ReadContext, id: DomainId, action: SecurityAction = SecurityAction.RETURN_CLAIM): PreparedRead =
    protectedCaseRead(context, action, id, Revision(0), "return.case.read.unavailable") { encodeCaseRead(LocalReturnCaseReadResultV1.NotAvailable) }

private fun protectedCaseRead(context: ReadContext, action: SecurityAction, id: DomainId, revision: Revision, reason: String,
    projection: () -> StoredBytes): PreparedRead {
    val requirement = AuthorizationRequirement(Permission(action, PermissionVerb.PERFORM), context.scope,
        ActionBinding(id, revision, context.semanticDigest, null, reason))
    return object : PreparedRead() {
        override val requirements: List<AuthorizationRequirement> = listOf(requirement)
        override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes {
            check(authorizations.size == 1 && authorizations.single().principalKind == PrincipalKind.LOCAL_EMPLOYEE &&
                authorizations.single().isBoundTo(requirement, context.actorId)) { "sales.authorization.invalid" }
            return projection()
        }
    }
}

private fun encodeCaseRead(value: LocalReturnCaseReadResultV1): StoredBytes =
    localSalesEncode(value, LocalReturnCaseReadResultV1.serializer(), LocalReturnCaseProfileV1.MAX_READ_BYTES)

private fun boundedCaseValue(value: LocalReturnCaseReadResultV1): StoredBytes = try { encodeCaseRead(value) }
    catch (_: LocalSalesWireCapacityExceeded) { encodeCaseRead(LocalReturnCaseReadResultV1.CapacityRequired) }

private fun boundedCasePage(size: Int, value: (Int) -> LocalReturnCaseReadResultV1): StoredBytes {
    if (size == 0) return boundedCaseValue(value(0))
    for (count in size downTo 1) {
        try { return encodeCaseRead(value(count)) } catch (_: LocalSalesWireCapacityExceeded) { /* Explicit continuation, never skip a row. */ }
    }
    return encodeCaseRead(LocalReturnCaseReadResultV1.CapacityRequired)
}
