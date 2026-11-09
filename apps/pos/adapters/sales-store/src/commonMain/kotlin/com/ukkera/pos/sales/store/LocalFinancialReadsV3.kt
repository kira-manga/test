package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.MasterReadHandler
import com.ukkera.pos.database.PreparedRead
import com.ukkera.pos.database.ReadContext
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public sealed interface LocalFinancialQueryV3 {
    /** The actual fact operation ID for the selected phase, not always the original ID. */
    @Serializable @SerialName("FACT")
    public data class Fact(public val recordingOperationId: OperationId,
        public val phase: LocalJournalFactPhaseV3) : LocalFinancialQueryV3
    @Serializable @SerialName("ATTEMPT_RECORDING")
    public data class AttemptRecording(public val kind: SalesRecordingKind, public val documentId: DomainId,
        public val sessionId: DomainId, public val attemptId: DomainId,
        public val intentOperationId: OperationId) : LocalFinancialQueryV3
    /** Original-custodian pending facts in one bounded storage-key page; an empty own page may still continue. */
    @Serializable @SerialName("OWN_PENDING")
    public data class OwnPending(public val after: DomainId? = null, public val limit: Int = 25) : LocalFinancialQueryV3
}

@Serializable
public data class LocalJournalFactSummaryV3(
    public val target: LocalJournalTargetV3, public val phase: LocalJournalFactPhaseV3,
    public val recordingOperationId: OperationId, public val originalActorId: DomainId,
    public val recordingActorId: DomainId, public val bodyDigest: String, public val originalBodyDigest: String?,
    public val recordedAt: UtcInstant, public val amount: Money, public val tender: LocalTenderViewV1,
    public val applicationState: LocalJournalApplicationStateV3, public val pendingReason: LocalJournalPendingReasonV3?,
    public val lastApplicationOperationId: OperationId?, public val appliedAction: LocalSalesAction?,
)

@Serializable
public sealed interface LocalFinancialReadResultV3 {
    @Serializable @SerialName("NOT_AVAILABLE") public data object NotAvailable : LocalFinancialReadResultV3
    /** Identities are historical linkage, NEVER a certificate, capacity promise or prompt permission. */
    @Serializable @SerialName("ATTEMPT_RECORDING")
    public data class AttemptRecording(public val target: LocalJournalTargetV3, public val requirementDigest: String,
        public val masterAllocationId: DomainId, public val clientAllocationId: DomainId,
        public val clientDeviceId: DomainId,
        /** Null means not present in this SQL cut, not proof of no native/human action. */
        public val original: LocalJournalFactSummaryV3?, public val resolution: LocalJournalFactSummaryV3?) : LocalFinancialReadResultV3
    /** One raw original OR resolution. Applied state is reported separately, never inferred from the outcome. */
    @Serializable @SerialName("FACT")
    public data class Fact(public val summary: LocalJournalFactSummaryV3,
        public val payment: LocalPaymentOutcomeV3?, public val refund: LocalRefundOutcomeV3?) : LocalFinancialReadResultV3
    @Serializable @SerialName("OWN_PENDING")
    public data class OwnPending(public val facts: List<LocalJournalFactSummaryV3>, public val nextAfter: DomainId?) : LocalFinancialReadResultV3
}

public object LocalFinancialReadCodecV3 {
    public val QUERY: ReadKey = ReadKey("sales.financial-observation.read", 3)
    public fun request(query: LocalFinancialQueryV3): ReadRequest = ReadRequest(QUERY, encode(query))
    public fun encode(query: LocalFinancialQueryV3): StoredBytes {
        require(query !is LocalFinancialQueryV3.OwnPending || query.limit in 1..25) { "sales.page.invalid" }
        return localSalesEncode(query, LocalFinancialQueryV3.serializer(), 2_048)
    }
    public fun decode(body: StoredBytes): LocalFinancialReadResultV3 =
        localSalesDecode(body, LocalFinancialReadResultV3.serializer())
}

/** Own historical raw facts, independent of live balances. Current scoped employee CASH_READ is mandatory. */
public class LocalJournalFinancialReadHandler(private val stock: LocalStockRepository? = null,
    private val tenderPolicies: HistoricalSalesTenderPolicySourceV1 = UnavailableHistoricalSalesTenderPolicySourceV1) : MasterReadHandler {
    override public val key: ReadKey get() = LocalFinancialReadCodecV3.QUERY
    override public fun normalize(input: StoredBytes): StoredBytes =
        LocalFinancialReadCodecV3.encode(localSalesDecode(input, LocalFinancialQueryV3.serializer(), 2_048))

    override public suspend fun prepare(normalized: StoredBytes, borrowed: CommandReadView, context: ReadContext): PreparedRead {
        val view = stock?.let { commerceLoan(borrowed, it, tenderPolicies) } ?: borrowed
        check(context.scope == view.scope && context.key == key) { "sales.journal.read_context" }
        val result: LocalFinancialReadResultV3 = when (val query = localSalesDecode(normalized, LocalFinancialQueryV3.serializer(), 2_048)) {
            is LocalFinancialQueryV3.Fact -> {
                val cut = retailOptionalFact(view, query.recordingOperationId, query.phase)
                if (cut == null || cut.slot.value.actorId != context.actorId) LocalFinancialReadResultV3.NotAvailable
                else LocalFinancialReadResultV3.Fact(retailFactSummary(cut),
                    if (cut.slot.value.target.kind == SalesRecordingKind.PAYMENT_ATTESTATION) retailPaymentObservation(cut.fact.value) else null,
                    if (cut.slot.value.target.kind == SalesRecordingKind.REFUND_ATTESTATION) retailRefundObservation(cut.fact.value) else null)
            }
            is LocalFinancialQueryV3.AttemptRecording -> {
                val slotId = view.claim(retailJournalAttemptClaimV3(query.attemptId))
                if (slotId == null) LocalFinancialReadResultV3.NotAvailable
                else {
                    // A surviving claim with a missing/broken slot is uncertainty, not an empty attempt.
                    val slot = readRetailSlot(view, OperationId(slotId.value)).value
                    val target = slot.target
                    if (slot.actorId != context.actorId || target.kind != query.kind || target.documentId != query.documentId ||
                        target.sessionId != query.sessionId || target.attemptId != query.attemptId || target.intentOperationId != query.intentOperationId) {
                        LocalFinancialReadResultV3.NotAvailable
                    } else {
                        val original = retailOptionalFact(view, target.recordingOperationId, LocalJournalFactPhaseV3.ORIGINAL)
                        val resolution = retailOptionalFact(view, target.resolutionOperationId, LocalJournalFactPhaseV3.RESOLUTION)
                        requireLocalSalesSource(resolution == null || original != null)
                        LocalFinancialReadResultV3.AttemptRecording(target, retailJournalDigestV3(slot.requirement().canonicalBytes).value,
                            slot.masterAllocationId, slot.clientAllocationId, slot.domain.clientOwner.deviceId,
                            original?.let(::retailFactSummary), resolution?.let(::retailFactSummary))
                    }
                }
            }
            is LocalFinancialQueryV3.OwnPending -> {
                val page = view.aggregateKeys(LocalFinancialJournalProfileV3.APPLICATION_KIND, query.after, query.limit)
                requireLocalSalesSource(page.keys.size <= query.limit && page.keys.map { it.id }.distinct().size == page.keys.size &&
                    page.keys.all { it.kind == LocalFinancialJournalProfileV3.APPLICATION_KIND &&
                        (query.after == null || it.id.value > query.after.value) } &&
                    page.keys == page.keys.sortedBy { it.id.value } &&
                    (page.nextAfter == null || page.keys.lastOrNull()?.id == page.nextAfter))
                val pending = page.keys.mapNotNull { key ->
                    val row = LocalSalesStorage.required(view, key, LocalFinancialJournalProfileV3.APPLICATION_SCHEMA)
                    val value = localSalesDecode(row.payload, LocalRetailJournalApplicationV3.serializer(), LocalFinancialJournalProfileV3.MAX_APPLICATION_BYTES)
                    requireLocalSalesSource(value.schemaVersion == 3 && value.scope.value() == context.scope &&
                        value.target.factOperation(value.phase).value == key.id.value && value.revision == row.domainRevision())
                    if (value.state == LocalJournalApplicationStateV3.PENDING_RECONCILIATION) {
                        // Application.actorId is the actual recorder, NOT original cash custody.
                        // Validate the complete current fact/slot/application lineage before using
                        // its original owner, exactly as Fact and AttemptRecording do. A raw row
                        // owner hint or an old application projection cannot authorize disclosure.
                        val cut = readRetailFact(view, value.target.factOperation(value.phase), value.phase)
                        val current = cut.application.stored
                        requireLocalSalesSource(current.key == row.key && current.schema == row.schema &&
                            current.revision == row.revision && current.digest == row.digest && current.payload == row.payload &&
                            cut.application.value == value)
                        if (cut.slot.value.actorId == context.actorId) retailFactSummary(cut) else null
                    } else null
                }
                LocalFinancialReadResultV3.OwnPending(pending, page.nextAfter)
            }
        }
        return localSalesRead(context, SecurityAction.CASH_READ, context.actorId, Revision(0), context.actorId,
            "financial.journal.read", result, LocalFinancialReadResultV3.serializer())
    }
}

private suspend fun retailOptionalFact(view: CommandReadView, operationId: OperationId,
    phase: LocalJournalFactPhaseV3): LocalRetailJournalFactCutV3? {
    if (view.aggregate(retailJournalFactKeyV3(phase, operationId)) != null) return readRetailFact(view, operationId, phase)
    // An application row without its immutable fact is never a normal absent read. A row for
    // the other phase is also not silently reinterpreted under this query's chosen phase.
    val other = if (phase == LocalJournalFactPhaseV3.ORIGINAL) LocalJournalFactPhaseV3.RESOLUTION else LocalJournalFactPhaseV3.ORIGINAL
    if (view.aggregate(retailJournalFactKeyV3(other, operationId)) != null) return null
    requireLocalSalesSource(view.aggregate(retailJournalApplicationKeyV3(operationId)) == null)
    return null
}

private fun retailFactSummary(cut: LocalRetailJournalFactCutV3): LocalJournalFactSummaryV3 = with(cut.fact.value) {
    LocalJournalFactSummaryV3(target, phase, target.factOperation(phase), originalActorId, recordingActorId, bodyDigest,
        originalBodyDigest, recordedAt, cut.slot.value.domain.amount, cut.slot.value.domain.tender,
        cut.application.value.state, cut.application.value.reason, cut.application.value.lastApplicationOperationId,
        cut.application.value.appliedAction)
}
