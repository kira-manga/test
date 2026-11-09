package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Explicit current-supervisor route; no original employee substitution or physical instruction. */
@Serializable
public sealed interface LocalRetailSupervisorRequestV1 {
    @Serializable @SerialName("REVIEW")
    public data class Review(public val originalRecordingOperationId: OperationId) : LocalRetailSupervisorRequestV1
    @Serializable @SerialName("APPLY_RECORDED_OBSERVATION")
    public data class Apply(public val kind: SalesRecordingKind, public val recordingOperationId: OperationId,
        public val phase: LocalJournalFactPhaseV3) : LocalRetailSupervisorRequestV1
}

@Serializable
public data class LocalRetailSupervisorReviewV1(
    public val original: LocalJournalFactSummaryV3,
    public val resolution: LocalJournalFactSummaryV3?,
    public val payment: LocalPaymentOutcomeV3?,
    public val refund: LocalRefundOutcomeV3?,
    public val resolutionPayment: LocalPaymentOutcomeV3? = null,
    public val resolutionRefund: LocalRefundOutcomeV3? = null,
)

public object LocalRetailSupervisorCodecV1 {
    public val COMMAND: CommandKey = CommandKey("sales.financial-supervisor", 1)
    public fun encode(value: LocalRetailSupervisorRequestV1): StoredBytes =
        localSalesEncode(value, LocalRetailSupervisorRequestV1.serializer(), 2048)
    public fun decode(body: StoredBytes): LocalRetailSupervisorRequestV1 =
        localSalesDecode(body, LocalRetailSupervisorRequestV1.serializer(), 2048)
    public fun normalize(body: StoredBytes): StoredBytes = encode(decode(body))
    public fun decodeReview(body: StoredBytes): LocalRetailSupervisorReviewV1 =
        localSalesDecode(body, LocalRetailSupervisorReviewV1.serializer(), 16384)
    internal fun review(value: LocalRetailSupervisorReviewV1): StoredBytes =
        localSalesEncode(value, LocalRetailSupervisorReviewV1.serializer(), 16384)
}

/**
 * Review is deliberately an audited command, NOT an unaudited generic read. Its pure rejection
 * outcome has no domain effects; the normal kernel still commits operation/result/journal/audit/
 * upload before returning the sensitive projection. Replays remain under current result policy.
 * Apply is a separate explicitly tagged SQL-only operation over an already immutable fact.
 */
public class LocalRetailSupervisorHandler(private val stock: LocalStockRepository? = null,
    private val tenderPolicies: HistoricalSalesTenderPolicySourceV1 = UnavailableHistoricalSalesTenderPolicySourceV1) : MasterCommandHandler {
    override val key: CommandKey get() = LocalRetailSupervisorCodecV1.COMMAND
    override val writableKinds: Set<String> = setOf(LocalFinancialJournalProfileV3.APPLICATION_KIND,
        LocalSalesStoreProfile.COLLECTION_KIND, LocalSalesStoreProfile.RETURN_KIND,
        CashNonSaleStoreProfile.SESSION_KIND, LocalSalesStoreProfile.MOVEMENT_KIND, LocalSalesStoreProfile.WORK_KIND,
        StockReadEvidenceV1.KIND, COMMERCE_STOCK_LINK_KIND_V2)
    override val claimNamespaces: Set<String> = setOf(LocalSalesStoreProfile.UNIQUE_NAMESPACE, CashNonSaleStoreProfile.CASH_SOURCE_NAMESPACE)
    override fun normalize(input: StoredBytes): StoredBytes = LocalRetailSupervisorCodecV1.normalize(input)
    override suspend fun prepare(normalized: StoredBytes, borrowed: CommandReadView, context: CommandContext): PreparedMutation {
        val view = stock?.let { commerceLoan(borrowed, it, tenderPolicies) } ?: borrowed
        check(context.key == key && context.scope == view.scope &&
            semanticDigest(view.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        return when (val request = LocalRetailSupervisorCodecV1.decode(normalized)) {
            is LocalRetailSupervisorRequestV1.Apply -> prepareRetailApplication(request.kind, request.recordingOperationId,
                request.phase, view, context, supervised = true)
            is LocalRetailSupervisorRequestV1.Review -> {
                val original = readRetailFact(view, request.originalRecordingOperationId, LocalJournalFactPhaseV3.ORIGINAL)
                val slot = original.slot.value
                val target = slot.target
                val resolution = if (view.aggregate(retailJournalFactKeyV3(LocalJournalFactPhaseV3.RESOLUTION, target.resolutionOperationId)) == null) null
                    else readRetailFact(view, target.resolutionOperationId, LocalJournalFactPhaseV3.RESOLUTION)
                LocalJournalPrepared(context, retailFactAction(target.kind, LocalJournalFactPhaseV3.RESOLUTION),
                    slot.id, Revision(0), slot.actorId, "financial.supervisor.review", slot.domain.amount,
                    retailJournalAuthorityResources(slot.requirement())) { _, _ ->
                    ProposedOutcome.Reject("sales.supervisor.reviewed", LocalRetailSupervisorCodecV1.review(LocalRetailSupervisorReviewV1(
                        supervisorFactSummary(original), resolution?.let(::supervisorFactSummary),
                        if (target.kind == SalesRecordingKind.PAYMENT_ATTESTATION) retailPaymentObservation(original.fact.value) else null,
                        if (target.kind == SalesRecordingKind.REFUND_ATTESTATION) retailRefundObservation(original.fact.value) else null,
                        resolution?.takeIf { target.kind == SalesRecordingKind.PAYMENT_ATTESTATION }?.let { retailPaymentObservation(it.fact.value) },
                        resolution?.takeIf { target.kind == SalesRecordingKind.REFUND_ATTESTATION }?.let { retailRefundObservation(it.fact.value) })))
                }
            }
        }
    }
}

internal fun requireRetailSupervisedApplication(context: CommandContext, kind: SalesRecordingKind,
    operationId: OperationId, phase: LocalJournalFactPhaseV3) {
    check(context.key == LocalRetailSupervisorCodecV1.COMMAND && operationId != context.operationId)
    val body = LocalRetailSupervisorCodecV1.encode(LocalRetailSupervisorRequestV1.Apply(kind, operationId, phase))
    // Bind the COMPLETE typed normalized command, not a Boolean/actor-only supervisor bypass.
    check(semanticDigest(context.scope, context.originalActorId, context.key, body) == context.semanticDigest)
    // Current scoped COLLECTION_RESOLVE / REFUND_RESOLVE and original-owner ANY_OWNER limits
    // are evaluated by the real kernel. This private branch never forges that proof.
}

/** Phase-specific link check, never an authorization descriptor promoted into permission. */
internal fun requireRetailResolutionRecorder(context: CommandContext, target: LocalJournalTargetV3,
    original: LocalRetailJournalFactCutV3, normalized: StoredBytes) {
    check(context.key == retailJournalCommandV3(target.kind) && context.operationId == target.resolutionOperationId &&
        original.fact.value.phase == LocalJournalFactPhaseV3.ORIGINAL && original.fact.value.target == target)
    val requirement = original.slot.value.requirement()
    check(retailJournalDomain(target.kind).classifyOriginal(requirement, original.fact.value.body()) == LocalPhysicalOriginalKind.UNKNOWN)
    when (target.kind) {
        SalesRecordingKind.PAYMENT_ATTESTATION -> {
            val resolution = LocalCollectionCommandCodecV3.decode(normalized) as? LocalCollectionRequestV3.RecordResolution
                ?: error("sales.supervisor.resolution")
            check(resolution.target == target && resolution.resolverActorId == context.originalActorId &&
                resolution.originalBodyDigest == original.fact.value.bodyDigest && resolution.outcome !is LocalPaymentOutcomeV3.Unknown)
        }
        SalesRecordingKind.REFUND_ATTESTATION -> {
            val resolution = LocalReturnCommandCodecV3.decode(normalized) as? LocalReturnRequestV3.RecordResolution
                ?: error("sales.supervisor.resolution")
            check(resolution.target == target && resolution.resolverActorId == context.originalActorId &&
                resolution.originalBodyDigest == original.fact.value.bodyDigest && resolution.outcome !is LocalRefundOutcomeV3.Unknown)
        }
    }
}

private fun supervisorFactSummary(cut: LocalRetailJournalFactCutV3): LocalJournalFactSummaryV3 = with(cut.fact.value) {
    LocalJournalFactSummaryV3(target, phase, target.factOperation(phase), originalActorId, recordingActorId, bodyDigest,
        originalBodyDigest, recordedAt, cut.slot.value.domain.amount, cut.slot.value.domain.tender,
        cut.application.value.state, cut.application.value.reason, cut.application.value.lastApplicationOperationId,
        cut.application.value.appliedAction)
}
