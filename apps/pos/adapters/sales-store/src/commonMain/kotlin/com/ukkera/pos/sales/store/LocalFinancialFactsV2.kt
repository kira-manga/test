package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateChange
import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.PreparedMutation
import com.ukkera.pos.database.ProposedOutcome
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.Serializable

/** Immutable raw fact. The one non-null observation is the exact original versioned command body. */
@Serializable
internal data class LocalPhysicalFactV2(
    val scope: LocalSalesScope, val recordingOperationId: OperationId, val actorId: DomainId,
    val recordedAt: UtcInstant, val semanticDigest: String, val slotDigest: String,
    val payment: LocalCollectionRequestV2.RecordOutcome?, val refund: LocalReturnRequestV2.RecordRefundOutcome?,
)

@Serializable
public enum class LocalObservationApplicationStateV2 { PENDING_RECONCILIATION, APPLIED }

@Serializable
internal data class LocalPhysicalApplicationV2(
    val scope: LocalSalesScope, val recordingOperationId: OperationId, val actorId: DomainId, val revision: Revision,
    val state: LocalObservationApplicationStateV2, val reason: LocalObservationPendingReasonV2?,
    val updatedAt: UtcInstant, val lastApplicationOperationId: OperationId?,
    val appliedAction: LocalSalesAction?, val financialRevisions: List<LocalSalesRevision>,
)

internal data class LocalPhysicalFactCutV2(
    val slot: LocalSalesRow<LocalPhysicalRecordingSlotV2>, val fact: LocalSalesRow<LocalPhysicalFactV2>,
    val application: LocalSalesRow<LocalPhysicalApplicationV2>,
)

internal suspend fun preparePaymentFactV2(request: LocalCollectionRequestV2.RecordOutcome,
    view: CommandReadView, context: CommandContext): PreparedMutation = preparePhysicalFactV2(request, null, view, context)

internal suspend fun prepareRefundFactV2(request: LocalReturnRequestV2.RecordRefundOutcome,
    view: CommandReadView, context: CommandContext): PreparedMutation = preparePhysicalFactV2(null, request, view, context)

private suspend fun preparePhysicalFactV2(payment: LocalCollectionRequestV2.RecordOutcome?,
    refund: LocalReturnRequestV2.RecordRefundOutcome?, view: CommandReadView, context: CommandContext): PreparedMutation {
    val id = DomainId(context.operationId.value)
    if (view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.PHYSICAL_SLOT_KIND, id)) == null) {
        return unavailablePhysicalFactV2(context)
    }
    val slot = readPhysicalSlotV2(view, context.operationId)
    val value = slot.value
    check(context.key == physicalCommandV2(value.kind)) { "sales.physical.command" }
    if (value.actorId != context.originalActorId || !matchesPhysicalTargetV2(value, payment, refund)) {
        return unavailablePhysicalFactV2(context)
    }
    // Exact protected receipt reads are recording AUTHORITY dependencies, not
    // mutable financial sources. Missing/revoked custody cannot be ignored.
    requirePhysicalSourcesV2(view, value)
    check(view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.PHYSICAL_FACT_KIND, id)) == null &&
        view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.PHYSICAL_APPLICATION_KIND, id)) == null) {
        "sales.physical.partial_fact"
    }
    return LocalSalesPrepared(context, physicalFactActionV2(value), value.id, value.revision, value.actorId,
        "financial.fact.retain", value.amount, value.authorityResources.toSet()) { _, now ->
        val fact = LocalPhysicalFactV2(LocalSalesScope.from(context.scope), context.operationId, context.originalActorId,
            now, context.semanticDigest.value, slot.stored.digest.value, payment, refund)
        val pending = LocalPhysicalApplicationV2(fact.scope, fact.recordingOperationId, fact.actorId, Revision(0),
            LocalObservationApplicationStateV2.PENDING_RECONCILIATION,
            LocalObservationPendingReasonV2.AWAITING_FINANCIAL_APPLICATION, now, null, null, emptyList())
        val result = LocalObservationResultCodecV2.encode(physicalPendingV2(value, pending.reason!!))
        val changes = listOf(physicalFactWriteV2(fact), physicalApplicationWriteV2(pending, null))
        check(changes.size == SalesPhysicalRecordingBudgetV2.MAX_INGRESS_WRITES &&
            changes.sumOf { it.payload.size } + result.size <= SalesPhysicalRecordingBudgetV2.MAX_INGRESS_APPLY_BYTES) {
            "sales.physical.ingress_budget"
        }
        // No cash/order/refund/stock read or write. In particular, no release of
        // physicalAttemptClaim: retention cannot make another attempt eligible.
        ProposedOutcome.Apply(result, changes)
    }
}

internal fun physicalFactActionV2(slot: LocalPhysicalRecordingSlotV2): SecurityAction = when (slot.kind) {
    SalesRecordingKind.PAYMENT_ATTESTATION -> if (slot.resolving) SecurityAction.COLLECTION_RESOLVE else SecurityAction.COLLECTION_ATTEST
    SalesRecordingKind.REFUND_ATTESTATION -> if (slot.resolving) SecurityAction.REFUND_RESOLVE else SecurityAction.REFUND_RECORD
}

internal fun unavailablePhysicalFactV2(context: CommandContext): PreparedMutation =
    LocalSalesPrepared(context, SecurityAction.CASH_READ, DomainId(context.operationId.value), Revision(0), context.originalActorId,
        "financial.fact.unavailable", resources = setOf(recordingAuthority(context.originalActorId),
            recordingAuthority(DomainId(context.operationId.value)))) { _, _ ->
        ProposedOutcome.Reject("sales.observation.unavailable", LocalObservationResultCodecV2.encode(LocalObservationResultV2.NotAvailable))
    }

internal fun matchesPhysicalTargetV2(slot: LocalPhysicalRecordingSlotV2, payment: LocalCollectionRequestV2.RecordOutcome?,
    refund: LocalReturnRequestV2.RecordRefundOutcome?): Boolean = when (slot.kind) {
    SalesRecordingKind.PAYMENT_ATTESTATION -> refund == null && payment != null &&
        payment.collectionId == slot.documentId && payment.sessionId == slot.cashSessionId &&
        payment.attemptId == slot.attemptId && payment.intentOperationId == slot.intentOperationId
    SalesRecordingKind.REFUND_ATTESTATION -> payment == null && refund != null &&
        refund.returnId == slot.documentId && refund.sessionId == slot.cashSessionId && refund.attemptId == slot.attemptId &&
        refund.intentOperationId == slot.intentOperationId && refund.saleId == slot.originalSaleId && refund.claimId == slot.originalClaimId
}

internal fun physicalPendingV2(slot: LocalPhysicalRecordingSlotV2, reason: LocalObservationPendingReasonV2): LocalObservationResultV2.Pending =
    LocalObservationResultV2.Pending(slot.recordingOperationId, slot.documentId, slot.attemptId, reason)

private fun physicalFactWriteV2(fact: LocalPhysicalFactV2): AggregateChange = AggregateChange(
    LocalSalesStoreProfile.key(LocalSalesStoreProfile.PHYSICAL_FACT_KIND, DomainId(fact.recordingOperationId.value)), null,
    LocalSalesStoreProfile.PHYSICAL_FACT_SCHEMA,
    localSalesEncode(fact, LocalPhysicalFactV2.serializer(), SalesPhysicalRecordingBudgetV2.MAX_FACT_BYTES))

internal fun physicalApplicationWriteV2(value: LocalPhysicalApplicationV2, previous: StoredAggregate?): AggregateChange {
    check(if (previous == null) value.revision == Revision(0) else value.revision == previous.domainRevision().next()) {
        "sales.physical.application_revision"
    }
    return AggregateChange(LocalSalesStoreProfile.key(LocalSalesStoreProfile.PHYSICAL_APPLICATION_KIND, DomainId(value.recordingOperationId.value)),
        previous?.revision, LocalSalesStoreProfile.PHYSICAL_APPLICATION_SCHEMA,
        localSalesEncode(value, LocalPhysicalApplicationV2.serializer(), SalesPhysicalRecordingBudgetV2.MAX_STATUS_BYTES))
}

internal suspend fun readPhysicalFactV2(view: CommandReadView, recordingOperationId: OperationId): LocalPhysicalFactCutV2 {
    val slot = readPhysicalSlotV2(view, recordingOperationId, requireActive = false)
    val id = DomainId(recordingOperationId.value)
    val row = LocalSalesStorage.required(view, LocalSalesStoreProfile.key(LocalSalesStoreProfile.PHYSICAL_FACT_KIND, id),
        LocalSalesStoreProfile.PHYSICAL_FACT_SCHEMA)
    val fact = localSalesDecode(row.payload, LocalPhysicalFactV2.serializer(), SalesPhysicalRecordingBudgetV2.MAX_FACT_BYTES)
    check(row.revision == Revision(1) && fact.scope.value() == view.scope && fact.recordingOperationId == recordingOperationId &&
        fact.actorId == slot.value.actorId && fact.slotDigest == slot.stored.digest.value &&
        matchesPhysicalTargetV2(slot.value, fact.payment, fact.refund)) { "sales.physical.fact" }
    val body = factBodyV2(fact)
    check(semanticDigest(view.scope, fact.actorId, physicalCommandV2(slot.value.kind), body).value == fact.semanticDigest) {
        "sales.physical.fact_identity"
    }
    val origin = view.retainedCommand(recordingOperationId) ?: error("sales.physical.fact_origin")
    requireRetainedPhysicalWrite(origin, row)
    check(origin.scope == view.scope && origin.originalActorId == fact.actorId && origin.command == physicalCommandV2(slot.value.kind) &&
        origin.claimChanges.isEmpty() && origin.jobs.isEmpty() && origin.aggregateWrites.map { it.key }.toSet() == setOf(row.key,
            LocalSalesStoreProfile.key(LocalSalesStoreProfile.PHYSICAL_APPLICATION_KIND, id)) &&
        origin.normalized == body && origin.semanticDigest.value == fact.semanticDigest && origin.recordedAt == fact.recordedAt &&
        LocalObservationResultCodecV2.decode(origin.result) == physicalPendingV2(slot.value,
            LocalObservationPendingReasonV2.AWAITING_FINANCIAL_APPLICATION)) { "sales.physical.fact_origin" }
    val applicationRow = LocalSalesStorage.required(view, LocalSalesStoreProfile.key(LocalSalesStoreProfile.PHYSICAL_APPLICATION_KIND, id),
        LocalSalesStoreProfile.PHYSICAL_APPLICATION_SCHEMA)
    val application = localSalesDecode(applicationRow.payload, LocalPhysicalApplicationV2.serializer(), SalesPhysicalRecordingBudgetV2.MAX_STATUS_BYTES)
    check(application.scope == fact.scope && application.recordingOperationId == recordingOperationId && application.actorId == fact.actorId &&
        application.revision == applicationRow.domainRevision() &&
        application.financialRevisions.size <= SalesPhysicalRecordingBudgetV2.MAX_FINANCIAL_WRITES &&
        (if (application.state == LocalObservationApplicationStateV2.PENDING_RECONCILIATION)
            application.reason != null && application.appliedAction == null && application.financialRevisions.isEmpty()
        else application.reason == null && application.appliedAction != null && application.lastApplicationOperationId != null)) {
        "sales.physical.application"
    }
    if (application.revision == Revision(0)) {
        check(application.lastApplicationOperationId == null && application.state == LocalObservationApplicationStateV2.PENDING_RECONCILIATION &&
            application.updatedAt == fact.recordedAt && application.reason == LocalObservationPendingReasonV2.AWAITING_FINANCIAL_APPLICATION)
        requireRetainedPhysicalWrite(origin, applicationRow)
    } else {
        val operationId = checkNotNull(application.lastApplicationOperationId) { "sales.physical.application_origin" }
        val update = view.retainedCommand(operationId) ?: error("sales.physical.application_origin")
        requireRetainedPhysicalWrite(update, applicationRow)
        check(update.scope == view.scope && update.originalActorId == fact.actorId && update.command == physicalCommandV2(slot.value.kind) &&
            update.normalized == applicationBodyV2(slot.value.kind, recordingOperationId) && update.recordedAt == application.updatedAt) {
            "sales.physical.application_origin"
        }
        val result = LocalObservationResultCodecV2.decode(update.result)
        check(if (application.state == LocalObservationApplicationStateV2.PENDING_RECONCILIATION)
            result == physicalPendingV2(slot.value, checkNotNull(application.reason)) && update.claimChanges.isEmpty() &&
                update.aggregateWrites.size == 1 && update.jobs.isEmpty()
        else result == LocalObservationResultV2.Applied(recordingOperationId, operationId, slot.value.documentId,
            slot.value.attemptId, checkNotNull(application.appliedAction), application.financialRevisions)) {
            "sales.physical.application_result"
        }
    }
    if (application.state == LocalObservationApplicationStateV2.PENDING_RECONCILIATION) {
        check(view.claim(physicalAttemptClaim(slot.value.attemptId)) == slot.value.id) { "sales.physical.pending_claim" }
    }
    return LocalPhysicalFactCutV2(slot, LocalSalesRow(row, fact), LocalSalesRow(applicationRow, application))
}

internal fun factBodyV2(fact: LocalPhysicalFactV2): StoredBytes = fact.payment?.let(LocalCollectionCommandCodecV2::encode)
    ?: LocalReturnCommandCodecV2.encode(checkNotNull(fact.refund))

internal fun applicationBodyV2(kind: SalesRecordingKind, recordingOperationId: OperationId): StoredBytes = when (kind) {
    SalesRecordingKind.PAYMENT_ATTESTATION -> LocalCollectionCommandCodecV2.encode(LocalCollectionRequestV2.ApplyObservation(recordingOperationId))
    SalesRecordingKind.REFUND_ATTESTATION -> LocalReturnCommandCodecV2.encode(LocalReturnRequestV2.ApplyObservation(recordingOperationId))
}
