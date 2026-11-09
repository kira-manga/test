package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateChange
import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.ClaimChange
import com.ukkera.pos.database.ClaimKey
import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.FutureOperationReservation
import com.ukkera.pos.database.OperationReservationBinding
import com.ukkera.pos.database.ProposedOutcome
import com.ukkera.pos.database.ReservedOperations
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.Serializable

@Serializable
public enum class SalesRecordingKind { PAYMENT_ATTESTATION, REFUND_ATTESTATION }

/**
 * Logical worst-case envelope for ONE non-stock observation, not a disk-space
 * estimate. The runtime separately qualifies page/WAL/audit/outbox overhead and
 * reserves protected storage. It must not expire/reclaim an in-flight unknown slot.
 */
public object SalesRecordingBudget {
    public const val PROFILE: String = "sales.financial-observation/v1"
    public const val MAX_REQUEST_BYTES: Int = 8_192
    public const val MAX_RESULT_BYTES: Int = 16_384
    public const val MAX_ROW_BYTES: Int = 65_536
    public const val MAX_WRITES: Int = 8
    public const val MAX_CLAIMS: Int = 4
    public const val MAX_APPLY_BYTES: Int = 524_288
    public const val MAX_JOURNAL_BYTES: Int = 786_432
    /** Leaves space for bounded observation/payment records, without pruning the source. */
    public const val MAX_PENDING_DOCUMENT_BYTES: Int = 32_768
}

/** The future observation ID is durable intent, never an instruction to collect twice. */
public data class SalesRecordingRequirement(
    public val scope: BranchScope,
    public val actorId: DomainId,
    public val reservationOperationId: OperationId,
    public val intentOperationId: OperationId,
    public val recordingOperationId: OperationId,
    public val attemptId: DomainId,
    public val documentId: DomainId,
    public val cashSessionId: DomainId,
    public val kind: SalesRecordingKind,
) {
    init {
        require(recordingOperationId != reservationOperationId && recordingOperationId != intentOperationId)
    }
    public val command: CommandKey get() = when (kind) {
        SalesRecordingKind.PAYMENT_ATTESTATION -> LocalSalesStoreProfile.COLLECTION_COMMAND
        SalesRecordingKind.REFUND_ATTESTATION -> LocalSalesStoreProfile.RETURN_COMMAND
    }
    public val budgetProfile: String get() = SalesRecordingBudget.PROFILE
    public val targetBinding: PayloadDigest get() = salesRecordingTargetBinding(kind, documentId, cashSessionId, attemptId, intentOperationId)
}

/** The not-yet-observed outcome and optimistic revisions are deliberately not reserved facts. */
public fun salesRecordingTargetBinding(kind: SalesRecordingKind, documentId: DomainId, cashSessionId: DomainId,
    attemptId: DomainId, intentOperationId: OperationId): PayloadDigest =
    OperationReservationBinding.frame("sales.financial-observation-target/v1",
        listOf(kind.name, documentId.value, cashSessionId.value, attemptId.value, intentOperationId.value))

/**
 * Issued only by the trusted qualified runtime reservation adapter. Its protected
 * row must bind the full requirement, budget and platform reservation profile.
 * The future operation fence is inserted with the sales intent in the SAME kernel
 * transaction, with separately qualified complete-history absence.
 * A row hash, free-space sample or caller-created instance is not that qualification.
 */
public class SalesRecordingBacking(
    public val allocationId: DomainId,
    public val requirement: SalesRecordingRequirement,
    public val sourceKey: AggregateKey,
    public val sourceSchema: String,
    public val sourceRevision: Revision,
    public val sourceDigest: PayloadDigest,
    public val platformReservationProfile: String,
)

public fun interface TransactionSalesRecordingCapacity {
    public suspend fun reserved(view: CommandReadView, requirement: SalesRecordingRequirement): SalesRecordingBacking?
}

public object UnavailableSalesRecordingCapacity : TransactionSalesRecordingCapacity {
    override public suspend fun reserved(view: CommandReadView, requirement: SalesRecordingRequirement): SalesRecordingBacking? = null
}

@Serializable
internal data class LocalRecordingSlot(
    val scope: LocalSalesScope, val id: DomainId, val revision: Revision,
    val actorId: DomainId, val reservationOperationId: OperationId, val intentOperationId: OperationId,
    val recordingOperationId: OperationId, val attemptId: DomainId, val documentId: DomainId, val cashSessionId: DomainId,
    val kind: SalesRecordingKind, val budgetProfile: String,
    val allocationId: DomainId, val sourceKind: String, val sourceId: DomainId, val sourceSchema: String,
    val sourceRevision: Revision, val sourceDigest: String, val platformReservationProfile: String,
    val consumedAt: UtcInstant? = null,
)

internal suspend fun readRecordingSlot(
    view: CommandReadView, context: CommandContext, kind: SalesRecordingKind, intentOperationId: OperationId,
    attemptId: DomainId, documentId: DomainId, cashSessionId: DomainId,
): LocalSalesRow<LocalRecordingSlot> {
    val id = DomainId(context.operationId.value)
    val row = LocalSalesStorage.required(view, LocalSalesStoreProfile.key(LocalSalesStoreProfile.RECORDING_SLOT_KIND, id))
    val slot = localSalesDecode(row.payload, LocalRecordingSlot.serializer(), 4_096)
    check(slot.scope.value() == view.scope && slot.id == id && slot.revision == row.domainRevision() && slot.revision == Revision(0) &&
        slot.consumedAt == null && slot.actorId == context.originalActorId && slot.recordingOperationId == context.operationId &&
        slot.intentOperationId == intentOperationId && slot.attemptId == attemptId && slot.documentId == documentId &&
        slot.cashSessionId == cashSessionId && slot.kind == kind && slot.budgetProfile == SalesRecordingBudget.PROFILE &&
        context.key == (if (kind == SalesRecordingKind.PAYMENT_ATTESTATION) LocalSalesStoreProfile.COLLECTION_COMMAND else LocalSalesStoreProfile.RETURN_COMMAND)) {
        "sales.recording.slot"
    }
    check(view.claim(recordingOperationClaim(id)) == attemptId && view.claim(recordingAllocationClaim(slot.allocationId)) == attemptId &&
        view.claim(recordingAttemptClaim(attemptId)) == id) {
        "sales.recording.slot"
    }
    val fence = LocalSalesStorage.required(view, ReservedOperations.key(context.operationId), ReservedOperations.SCHEMA)
    val expectedFence = ReservedOperations.create(FutureOperationReservation(context.operationId, context.originalActorId, context.key,
        salesRecordingTargetBinding(kind, documentId, cashSessionId, attemptId, intentOperationId)))
    check(fence.revision == Revision(1) && fence.payload == expectedFence.payload) { "sales.recording.operation_fence" }
    return LocalSalesRow(row, slot)
}

internal fun consumeRecordingSlot(slot: LocalSalesRow<LocalRecordingSlot>, now: UtcInstant): AggregateChange {
    val after = slot.value.copy(revision = slot.value.revision.next(), consumedAt = now)
    return rootWrite(LocalSalesStoreProfile.RECORDING_SLOT_KIND, after.id, after, LocalRecordingSlot.serializer(), slot.stored)
}

internal fun releaseRecordingSlot(slot: LocalSalesRow<LocalRecordingSlot>): ClaimChange =
    ClaimChange.Release(recordingAttemptClaim(slot.value.attemptId), slot.value.id)

internal fun observedApplied(
    action: LocalSalesAction, id: DomainId, revisions: List<LocalSalesRevision>, changes: List<AggregateChange>,
    claims: List<ClaimChange>, amount: Money,
): ProposedOutcome.Apply {
    val result = LocalSalesResultCodec.encode(LocalSalesResultV1.Applied(action, id, revisions, amount))
    requireObservationBudget(changes, claims, result)
    return ProposedOutcome.Apply(result, changes, claims)
}

internal fun requireObservationBudget(changes: List<AggregateChange>, claims: List<ClaimChange>, result: StoredBytes) {
    if (changes.size > SalesRecordingBudget.MAX_WRITES || claims.size > SalesRecordingBudget.MAX_CLAIMS ||
        result.size > SalesRecordingBudget.MAX_RESULT_BYTES || changes.any { it.payload.size > SalesRecordingBudget.MAX_ROW_BYTES } ||
        changes.sumOf { it.payload.size.toLong() } + result.size > SalesRecordingBudget.MAX_APPLY_BYTES) throw LocalSalesCapacityExceeded()
}

private fun recordingOperationClaim(id: DomainId): ClaimKey = ClaimKey(LocalSalesStoreProfile.RECORDING_OPERATION_NAMESPACE, id.value)
private fun recordingAllocationClaim(id: DomainId): ClaimKey = ClaimKey(LocalSalesStoreProfile.RECORDING_ALLOCATION_NAMESPACE, id.value)
private fun recordingAttemptClaim(id: DomainId): ClaimKey = ClaimKey(LocalSalesStoreProfile.RECORDING_ATTEMPT_NAMESPACE, id.value)

/** Narrow recovery pointer, never a suggestion to initiate another physical payment. */
internal suspend fun activeRecordingOperation(view: CommandReadView, kind: SalesRecordingKind, documentId: DomainId,
    attemptId: DomainId, actorId: DomainId, cashSessionId: DomainId, intentOperationId: OperationId): OperationId? {
    val retailSlotId = view.claim(retailJournalAttemptClaimV3(attemptId))
    if (retailSlotId != null) {
        // A permanent v3 claim selects the genuine immutable slot/whole Start/source/fences.
        // Missing/foreign/aliased retained state is uncertainty, never a guessed protocol or
        // another physical instruction. Original custody stays distinct from a supervisor.
        check(view.claim(recordingAttemptClaim(attemptId)) == null) { "sales.recording.pointer_alias" }
        val slot = readRetailSlot(view, OperationId(retailSlotId.value)).value
        val target = slot.target
        check(target.kind == kind && target.documentId == documentId && target.attemptId == attemptId &&
            slot.actorId == actorId && target.sessionId == cashSessionId && target.intentOperationId == intentOperationId &&
            target.recordingOperationId.value == retailSlotId.value) { "sales.recording.pointer" }
        return target.recordingOperationId
    }
    // A genuine v3 Start with a lost attempt claim may not fall through as absent/v1/v2.
    val original = view.retainedCommand(intentOperationId)
    if (original?.command == retailJournalCommandV3(kind)) {
        check(false) { "sales.recording.pointer_missing" }
    }
    val slotId = view.claim(recordingAttemptClaim(attemptId)) ?: return null
    if (view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.PHYSICAL_SLOT_KIND, slotId)) != null) {
        val slot = readPhysicalSlotV2(view, OperationId(slotId.value)).value
        check(slot.kind == kind && slot.documentId == documentId && slot.attemptId == attemptId && slot.actorId == actorId &&
            slot.cashSessionId == cashSessionId && slot.intentOperationId == intentOperationId) { "sales.recording.pointer" }
        return slot.recordingOperationId
    }
    val row = LocalSalesStorage.required(view, LocalSalesStoreProfile.key(LocalSalesStoreProfile.RECORDING_SLOT_KIND, slotId))
    val slot = localSalesDecode(row.payload, LocalRecordingSlot.serializer(), 4_096)
    check(slot.scope.value() == view.scope && slot.id == slotId && slot.revision == Revision(0) && row.revision == Revision(1) &&
        slot.consumedAt == null && slot.kind == kind && slot.documentId == documentId && slot.attemptId == attemptId &&
        slot.actorId == actorId && slot.cashSessionId == cashSessionId && slot.recordingOperationId.value == slotId.value &&
        slot.intentOperationId == intentOperationId && slot.budgetProfile == SalesRecordingBudget.PROFILE &&
        view.claim(recordingOperationClaim(slotId)) == attemptId && view.claim(recordingAllocationClaim(slot.allocationId)) == attemptId) {
        "sales.recording.pointer"
    }
    val command = if (kind == SalesRecordingKind.PAYMENT_ATTESTATION) LocalSalesStoreProfile.COLLECTION_COMMAND else LocalSalesStoreProfile.RETURN_COMMAND
    val fence = LocalSalesStorage.required(view, ReservedOperations.key(slot.recordingOperationId), ReservedOperations.SCHEMA)
    val expected = ReservedOperations.create(FutureOperationReservation(slot.recordingOperationId, actorId, command,
        salesRecordingTargetBinding(kind, documentId, cashSessionId, attemptId, intentOperationId)))
    check(fence.revision == Revision(1) && fence.payload == expected.payload) { "sales.recording.operation_fence" }
    return slot.recordingOperationId
}

/** Null preserves historical v1 bytes; protocol 2/3 is selected only by its actual retained slot. */
internal suspend fun activeRecordingProtocolV2(view: CommandReadView, operationId: OperationId?): Int? {
    if (operationId == null) return null
    if (view.aggregate(retailJournalSlotKeyV3(operationId)) != null) {
        readRetailSlot(view, operationId) // Full retained v3 lineage, not an asserted version.
        return 3
    }
    return if (view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.PHYSICAL_SLOT_KIND,
            DomainId(operationId.value))) != null) 2 else null
}
