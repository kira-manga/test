package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.MasterReadHandler
import com.ukkera.pos.database.PreparedRead
import com.ukkera.pos.database.ReadContext
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public sealed interface LocalFinancialQueryV2 {
    @Serializable @SerialName("OBSERVATION")
    public data class Observation(public val recordingOperationId: OperationId) : LocalFinancialQueryV2
    @Serializable @SerialName("ATTEMPT_RECORDING")
    public data class AttemptRecording(public val kind: SalesRecordingKind, public val documentId: DomainId,
        public val sessionId: DomainId, public val attemptId: DomainId, public val intentOperationId: OperationId) : LocalFinancialQueryV2
    /** Scans a bounded keyset page; an empty own page can still carry nextAfter. */
    @Serializable @SerialName("OWN_PENDING")
    public data class OwnPending(public val after: DomainId? = null, public val limit: Int = 25) : LocalFinancialQueryV2
}

@Serializable
public data class LocalObservationSummaryV2(
    public val recordingOperationId: OperationId, public val kind: SalesRecordingKind, public val documentId: DomainId,
    public val sessionId: DomainId, public val attemptId: DomainId, public val intentOperationId: OperationId,
    public val recordedAt: UtcInstant, public val applicationState: LocalObservationApplicationStateV2,
    public val pendingReason: LocalObservationPendingReasonV2?, public val lastApplicationOperationId: OperationId?,
    public val appliedAction: LocalSalesAction?,
)

@Serializable
public sealed interface LocalFinancialReadResultV2 {
    @Serializable @SerialName("NOT_AVAILABLE") public data object NotAvailable : LocalFinancialReadResultV2
    @Serializable @SerialName("RECORDING_SLOT")
    public data class RecordingSlot(
        public val recordingOperationId: OperationId, public val kind: SalesRecordingKind, public val documentId: DomainId,
        public val sessionId: DomainId, public val attemptId: DomainId, public val intentOperationId: OperationId,
        public val clientAllocationId: DomainId, public val clientDeviceId: DomainId, public val clientSpoolId: DomainId,
        /** null is not history absence; this route makes no such qualification. */
        public val retainedObservation: LocalObservationSummaryV2?,
    ) : LocalFinancialReadResultV2
    @Serializable @SerialName("OBSERVATION")
    public data class Observation(public val summary: LocalObservationSummaryV2,
        public val payment: LocalCollectionRequestV2.RecordOutcome?, public val refund: LocalReturnRequestV2.RecordRefundOutcome?) : LocalFinancialReadResultV2
    @Serializable @SerialName("OWN_PENDING")
    public data class OwnPending(public val observations: List<LocalObservationSummaryV2>, public val nextAfter: DomainId?) : LocalFinancialReadResultV2
}

public object LocalFinancialReadCodecV2 {
    public val QUERY: ReadKey = ReadKey("sales.financial-observation.read", 2)
    public fun request(query: LocalFinancialQueryV2): ReadRequest = ReadRequest(QUERY, encode(query))
    public fun encode(query: LocalFinancialQueryV2): StoredBytes {
        require(query !is LocalFinancialQueryV2.OwnPending || query.limit in 1..25) { "sales.page.invalid" }
        return localSalesEncode(query, LocalFinancialQueryV2.serializer(), 2_048)
    }
    public fun decode(body: StoredBytes): LocalFinancialReadResultV2 = localSalesDecode(body, LocalFinancialReadResultV2.serializer())
}

/** Own raw facts remain visible without reading possibly absent/corrupt financial balances. */
public class LocalHistoricalFinancialReadHandler : MasterReadHandler {
    override public val key: ReadKey get() = LocalFinancialReadCodecV2.QUERY
    override public fun normalize(input: StoredBytes): StoredBytes =
        LocalFinancialReadCodecV2.encode(localSalesDecode(input, LocalFinancialQueryV2.serializer(), 2_048))

    override public suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        check(context.scope == view.scope && context.key == key) { "sales.context.invalid" }
        val result: LocalFinancialReadResultV2 = when (val query = localSalesDecode(normalized, LocalFinancialQueryV2.serializer(), 2_048)) {
            is LocalFinancialQueryV2.Observation -> {
                val id = DomainId(query.recordingOperationId.value)
                if (view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.PHYSICAL_FACT_KIND, id)) == null) {
                    LocalFinancialReadResultV2.NotAvailable
                } else {
                    val fact = readPhysicalFactV2(view, query.recordingOperationId)
                    if (fact.slot.value.actorId != context.actorId) LocalFinancialReadResultV2.NotAvailable
                    else LocalFinancialReadResultV2.Observation(physicalSummaryV2(fact), fact.fact.value.payment, fact.fact.value.refund)
                }
            }
            is LocalFinancialQueryV2.AttemptRecording -> {
                val id = view.claim(physicalAttemptClaim(query.attemptId))
                if (id == null || view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.PHYSICAL_SLOT_KIND, id)) == null) {
                    LocalFinancialReadResultV2.NotAvailable
                } else {
                    val slot = readPhysicalSlotV2(view, OperationId(id.value)).value
                    if (slot.actorId != context.actorId || slot.kind != query.kind || slot.documentId != query.documentId ||
                        slot.cashSessionId != query.sessionId || slot.attemptId != query.attemptId || slot.intentOperationId != query.intentOperationId) {
                        LocalFinancialReadResultV2.NotAvailable
                    } else {
                        val retained = if (view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.PHYSICAL_FACT_KIND, id)) == null) {
                            check(view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.PHYSICAL_APPLICATION_KIND, id)) == null) {
                                "sales.physical.partial_fact"
                            }
                            null
                        } else physicalSummaryV2(readPhysicalFactV2(view, slot.recordingOperationId))
                        LocalFinancialReadResultV2.RecordingSlot(slot.recordingOperationId, slot.kind, slot.documentId, slot.cashSessionId,
                            slot.attemptId, slot.intentOperationId, slot.clientAllocationId, slot.clientDeviceId, slot.clientSpoolId, retained)
                    }
                }
            }
            is LocalFinancialQueryV2.OwnPending -> {
                val page = view.aggregateKeys(LocalSalesStoreProfile.PHYSICAL_APPLICATION_KIND, query.after, query.limit)
                val pending = page.keys.mapNotNull { key ->
                    val row = LocalSalesStorage.required(view, key, LocalSalesStoreProfile.PHYSICAL_APPLICATION_SCHEMA)
                    val value = localSalesDecode(row.payload, LocalPhysicalApplicationV2.serializer(), SalesPhysicalRecordingBudgetV2.MAX_STATUS_BYTES)
                    check(value.scope.value() == context.scope && value.recordingOperationId.value == key.id.value &&
                        value.revision == row.domainRevision()) { "sales.physical.pending_index" }
                    if (value.actorId == context.actorId && value.state == LocalObservationApplicationStateV2.PENDING_RECONCILIATION) {
                        physicalSummaryV2(readPhysicalFactV2(view, value.recordingOperationId))
                    } else null
                }
                LocalFinancialReadResultV2.OwnPending(pending, page.nextAfter)
            }
        }
        return localSalesRead(context, SecurityAction.CASH_READ, context.actorId, Revision(0), context.actorId,
            "financial.fact.read", result, LocalFinancialReadResultV2.serializer())
    }
}

private fun physicalSummaryV2(cut: LocalPhysicalFactCutV2): LocalObservationSummaryV2 = with(cut.slot.value) {
    LocalObservationSummaryV2(recordingOperationId, kind, documentId, cashSessionId, attemptId, intentOperationId, cut.fact.value.recordedAt,
        cut.application.value.state, cut.application.value.reason, cut.application.value.lastApplicationOperationId, cut.application.value.appliedAction)
}
