package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.ClaimKey
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.FutureOperationReservation
import com.ukkera.pos.database.OperationReservationBinding
import com.ukkera.pos.database.ReservedOperations
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.Serializable

/** Logical envelopes only. A real adapter must reserve physical pages/journal/metadata overhead too. */
public object SalesPhysicalRecordingBudgetV2 {
    public const val PROFILE: String = "sales.physical-observation/v2"
    public const val MAX_REQUEST_BYTES: Int = 8_192
    public const val MAX_FACT_BYTES: Int = 16_384
    public const val MAX_STATUS_BYTES: Int = 4_096
    public const val MAX_RESULT_BYTES: Int = 16_384
    public const val MAX_INGRESS_WRITES: Int = 2
    public const val MAX_INGRESS_APPLY_BYTES: Int = 65_536
    public const val MAX_FINANCIAL_WRITES: Int = 8
    public const val MAX_FINANCIAL_CLAIMS: Int = 4
    public const val MAX_FINANCIAL_ROW_BYTES: Int = 65_536
    public const val MAX_FINANCIAL_APPLY_BYTES: Int = 524_288
    public const val MAX_MASTER_JOURNAL_BYTES: Int = 786_432
    /** Original immutable intent/fact identity plus terminal/pending metadata; NOT a disk estimate. */
    public const val MAX_CLIENT_RECORD_BYTES: Int = 32_768
    public const val MAX_CLIENT_JOURNAL_BYTES: Int = 65_536
}

/** Constructor-trusted requirement, never decoded as a client's assertion of storage authority. */
public data class SalesPhysicalRecordingRequirementV2(
    public val scope: BranchScope, public val actorId: DomainId,
    public val reservationOperationId: OperationId, public val intentOperationId: OperationId,
    public val recordingOperationId: OperationId, public val attemptId: DomainId,
    public val documentId: DomainId, public val cashSessionId: DomainId, public val kind: SalesRecordingKind,
    public val amount: Money, public val tender: LocalTenderViewV1, public val resolving: Boolean,
    public val clientAllocationId: DomainId,
    public val originalSaleId: DomainId? = null, public val originalClaimId: DomainId? = null,
    public val originalPaymentId: DomainId? = null,
) {
    init {
        require(recordingOperationId != reservationOperationId && recordingOperationId != intentOperationId && amount.minor > 0L)
        require(if (kind == SalesRecordingKind.PAYMENT_ATTESTATION)
            originalSaleId == null && originalClaimId == null && originalPaymentId == null
        else originalSaleId != null && originalClaimId != null && originalPaymentId != null)
    }
    public val command: CommandKey get() = physicalCommandV2(kind)
    public val budgetProfile: String get() = SalesPhysicalRecordingBudgetV2.PROFILE
    public val targetBinding: PayloadDigest get() = physicalTargetBindingV2(kind, documentId, cashSessionId,
        attemptId, intentOperationId, originalSaleId, originalClaimId)
}

public data class SalesPhysicalSourceStampV2(
    public val key: AggregateKey, public val schema: String, public val revision: Revision, public val digest: PayloadDigest,
)

/**
 * Issued only by a qualified local runtime adapter. BOTH receipts must be protected,
 * immutable and bind this exact requirement/budget/lifetime. The client proof must
 * attest an actual preallocated durable physical-fact spool belonging to the paired
 * device, not its ordinary bounded intent journal, a Boolean or a free-space sample.
 * No default production implementation issues this object.
 */
public class SalesPhysicalRecordingBackingV2(
    public val requirement: SalesPhysicalRecordingRequirementV2,
    public val masterAllocationId: DomainId, public val clientDeviceId: DomainId, public val clientSpoolId: DomainId,
    public val masterSource: SalesPhysicalSourceStampV2, public val clientSource: SalesPhysicalSourceStampV2,
    public val masterPlatformProfile: String, public val clientPlatformProfile: String,
    recoveryResources: Set<RecoveryResourceKey>,
) {
    public val recoveryResources: Set<RecoveryResourceKey> = recoveryResources.toSet()
}

public fun interface TransactionSalesPhysicalRecordingCapacityV2 {
    /** Borrowed transaction only; no filesystem/network side effect or evictable permit is allowed here. */
    public suspend fun reserved(view: CommandReadView, requirement: SalesPhysicalRecordingRequirementV2): SalesPhysicalRecordingBackingV2?
}

public object UnavailableSalesPhysicalRecordingCapacityV2 : TransactionSalesPhysicalRecordingCapacityV2 {
    override public suspend fun reserved(view: CommandReadView, requirement: SalesPhysicalRecordingRequirementV2): SalesPhysicalRecordingBackingV2? = null
}

internal fun physicalCommandV2(kind: SalesRecordingKind): CommandKey = when (kind) {
    SalesRecordingKind.PAYMENT_ATTESTATION -> LocalSalesStoreProfile.COLLECTION_COMMAND_V2
    SalesRecordingKind.REFUND_ATTESTATION -> LocalSalesStoreProfile.RETURN_COMMAND_V2
}

internal fun physicalTargetBindingV2(kind: SalesRecordingKind, documentId: DomainId, cashSessionId: DomainId,
    attemptId: DomainId, intentOperationId: OperationId, saleId: DomainId? = null, claimId: DomainId? = null): PayloadDigest =
    OperationReservationBinding.frame("sales.financial-observation-target/v2",
        listOf(kind.name, documentId.value, cashSessionId.value, attemptId.value, intentOperationId.value,
            saleId?.value ?: "-", claimId?.value ?: "-"))

@Serializable
internal data class LocalPhysicalSourceV2(val kind: String, val id: DomainId, val schema: String,
    val revision: Revision, val digest: String) {
    fun key(): AggregateKey = AggregateKey(kind, id)
    companion object {
        fun from(value: SalesPhysicalSourceStampV2): LocalPhysicalSourceV2 =
            LocalPhysicalSourceV2(value.key.kind, value.key.id, value.schema, value.revision, value.digest.value)
    }
}

/** Immutable. Consuming a FACT does not release its original attempt or reclaim its capacity. */
@Serializable
internal data class LocalPhysicalRecordingSlotV2(
    val scope: LocalSalesScope, val id: DomainId, val revision: Revision,
    val actorId: DomainId, val reservationOperationId: OperationId, val intentOperationId: OperationId,
    val recordingOperationId: OperationId, val attemptId: DomainId, val documentId: DomainId, val cashSessionId: DomainId,
    val kind: SalesRecordingKind, val amount: Money, val tender: LocalTenderViewV1, val resolving: Boolean,
    val originalSaleId: DomainId?, val originalClaimId: DomainId?, val originalPaymentId: DomainId?,
    val budgetProfile: String, val masterAllocationId: DomainId, val clientAllocationId: DomainId,
    val clientDeviceId: DomainId, val clientSpoolId: DomainId,
    val masterSource: LocalPhysicalSourceV2, val clientSource: LocalPhysicalSourceV2,
    val masterPlatformProfile: String, val clientPlatformProfile: String,
    val authorityResources: List<RecoveryResourceKey>,
    /** Original financial dependencies for conservative application/reconciliation, not ingress authority. */
    val financialResources: List<RecoveryResourceKey>,
) {
    fun targetBinding(): PayloadDigest = physicalTargetBindingV2(kind, documentId, cashSessionId, attemptId,
        intentOperationId, originalSaleId, originalClaimId)
}

internal fun recordingAuthority(id: DomainId): RecoveryResourceKey = RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, id)
private fun physicalAuthorityIds(actor: DomainId, slot: DomainId, master: DomainId, client: DomainId,
    device: DomainId, spool: DomainId, masterSource: DomainId, clientSource: DomainId): Set<DomainId> =
    setOf(actor, slot, master, client, device, spool, masterSource, clientSource)

internal suspend fun readPhysicalSlotV2(view: CommandReadView, operationId: OperationId,
    requireActive: Boolean = true): LocalSalesRow<LocalPhysicalRecordingSlotV2> {
    val id = DomainId(operationId.value)
    val row = LocalSalesStorage.required(view, LocalSalesStoreProfile.key(LocalSalesStoreProfile.PHYSICAL_SLOT_KIND, id),
        LocalSalesStoreProfile.PHYSICAL_SLOT_SCHEMA)
    val slot = localSalesDecode(row.payload, LocalPhysicalRecordingSlotV2.serializer(), 16_384)
    check(slot.scope.value() == view.scope && slot.id == id && slot.recordingOperationId == operationId &&
        row.revision == Revision(1) && slot.revision == Revision(0) && slot.budgetProfile == SalesPhysicalRecordingBudgetV2.PROFILE &&
        slot.amount.minor > 0L && slot.recordingOperationId != slot.intentOperationId &&
        slot.recordingOperationId != slot.reservationOperationId && slot.authorityResources.size in 1..48 &&
        slot.financialResources.size in 1..48 && slot.financialResources.distinct().size == slot.financialResources.size &&
        slot.authorityResources.distinct().size == slot.authorityResources.size &&
        slot.authorityResources.containsAll(physicalAuthorityIds(slot.actorId, slot.id, slot.masterAllocationId,
            slot.clientAllocationId, slot.clientDeviceId, slot.clientSpoolId, slot.masterSource.id, slot.clientSource.id).map(::recordingAuthority)) &&
        (if (slot.kind == SalesRecordingKind.PAYMENT_ATTESTATION)
            slot.originalSaleId == null && slot.originalClaimId == null && slot.originalPaymentId == null
        else slot.originalSaleId != null && slot.originalClaimId != null && slot.originalPaymentId != null)) { "sales.physical.slot" }
    check(view.claim(physicalOperationClaim(id)) == slot.attemptId &&
        view.claim(physicalMasterAllocationClaim(slot.masterAllocationId)) == slot.attemptId &&
        view.claim(physicalClientAllocationClaim(slot.clientAllocationId)) == slot.attemptId &&
        (!requireActive || view.claim(physicalAttemptClaim(slot.attemptId)) == id)) { "sales.physical.claim" }
    val fence = LocalSalesStorage.required(view, ReservedOperations.key(operationId), ReservedOperations.SCHEMA)
    val expectedFence = ReservedOperations.create(FutureOperationReservation(operationId, slot.actorId, physicalCommandV2(slot.kind), slot.targetBinding()))
    check(fence.revision == Revision(1) && fence.payload == expectedFence.payload) { "sales.physical.fence" }
    val producer = view.retainedCommand(slot.reservationOperationId) ?: error("sales.physical.producer")
    requirePhysicalProducerV2(producer, slot)
    requireRetainedPhysicalWrite(producer, row)
    check(producer.aggregateWrites.any { it.key == fence.key && it.storageGeneration == fence.revision &&
        it.schema == fence.schema && it.payload == fence.payload }) { "sales.physical.producer" }
    return LocalSalesRow(row, slot)
}

/** Re-check immutable protection receipts for ingress/new reservations, not mutable financial balances. */
internal suspend fun requirePhysicalSourcesV2(view: CommandReadView, slot: LocalPhysicalRecordingSlotV2) {
    requirePhysicalSourceV2(view, slot.masterSource)
    requirePhysicalSourceV2(view, slot.clientSource)
}

private suspend fun requirePhysicalSourceV2(view: CommandReadView, source: LocalPhysicalSourceV2) {
    val row = LocalSalesStorage.required(view, source.key(), source.schema)
    check(source.revision.value > 0L && row.revision == source.revision && row.digest == PayloadDigest(source.digest)) {
        "sales.physical.protection_source"
    }
}

internal fun requireRetainedPhysicalWrite(evidence: RetainedCommandEvidence, row: StoredAggregate) {
    check(evidence.committed && evidence.aggregateWrites.singleOrNull { it.key == row.key }?.let {
        it.storageGeneration == row.revision && it.schema == row.schema && it.payload == row.payload && it.payloadDigest == row.digest
    } == true) { "sales.physical.retained_source" }
}

/** A later v2 evidence reservation cannot upgrade an original v1 physical intent. */
internal suspend fun requireOriginalPhysicalIntentV2(view: CommandReadView, kind: SalesRecordingKind,
    intentOperationId: OperationId, attemptId: DomainId, documentId: DomainId, sessionId: DomainId,
    actorId: DomainId, amount: Money, tender: LocalTenderViewV1, saleId: DomainId? = null,
    claimId: DomainId? = null, originalPaymentId: DomainId? = null) {
    val origin = view.retainedCommand(intentOperationId) ?: error("sales.physical.original_intent")
    check(origin.committed && origin.scope == view.scope && origin.originalActorId == actorId &&
        origin.command == physicalCommandV2(kind)) { "sales.physical.original_intent" }
    val originalRecording = when (kind) {
        SalesRecordingKind.PAYMENT_ATTESTATION ->
            (LocalCollectionCommandCodecV2.decode(origin.normalized) as? LocalCollectionRequestV2.StartAttempt)?.recordingOperationId
        SalesRecordingKind.REFUND_ATTESTATION ->
            (LocalReturnCommandCodecV2.decode(origin.normalized) as? LocalReturnRequestV2.StartRefund)?.recordingOperationId
    } ?: error("sales.physical.original_intent")
    val original = readPhysicalSlotV2(view, originalRecording, requireActive = false).value
    check(original.reservationOperationId == intentOperationId && original.intentOperationId == intentOperationId &&
        original.attemptId == attemptId && original.documentId == documentId && original.cashSessionId == sessionId &&
        original.actorId == actorId && original.amount == amount && original.tender == tender && original.kind == kind &&
        original.originalSaleId == saleId && original.originalClaimId == claimId && original.originalPaymentId == originalPaymentId) {
        "sales.physical.original_intent"
    }
}

private fun requirePhysicalProducerV2(evidence: RetainedCommandEvidence, slot: LocalPhysicalRecordingSlotV2) {
    check(evidence.committed && evidence.scope == slot.scope.value() && evidence.operationId == slot.reservationOperationId &&
        evidence.originalActorId == slot.actorId && evidence.command == physicalCommandV2(slot.kind)) { "sales.physical.producer" }
    val requested = when (slot.kind) {
        SalesRecordingKind.PAYMENT_ATTESTATION -> when (val request = LocalCollectionCommandCodecV2.decode(evidence.normalized)) {
            is LocalCollectionRequestV2.StartAttempt -> request.collectionId == slot.documentId && request.sessionId == slot.cashSessionId &&
                request.attemptId == slot.attemptId && request.recordingOperationId == slot.recordingOperationId &&
                request.clientAllocationId == slot.clientAllocationId && request.amount == slot.amount &&
                slot.intentOperationId == evidence.operationId && !slot.resolving
            is LocalCollectionRequestV2.ReserveRecording -> request.collectionId == slot.documentId && request.sessionId == slot.cashSessionId &&
                request.attemptId == slot.attemptId && request.recordingOperationId == slot.recordingOperationId &&
                request.clientAllocationId == slot.clientAllocationId
            else -> false
        }
        SalesRecordingKind.REFUND_ATTESTATION -> when (val request = LocalReturnCommandCodecV2.decode(evidence.normalized)) {
            is LocalReturnRequestV2.StartRefund -> request.returnId == slot.documentId && request.sessionId == slot.cashSessionId &&
                request.saleId == slot.originalSaleId && request.claimId == slot.originalClaimId && request.originalPaymentId == slot.originalPaymentId &&
                request.attemptId == slot.attemptId && request.recordingOperationId == slot.recordingOperationId &&
                request.clientAllocationId == slot.clientAllocationId && request.amount == slot.amount &&
                slot.intentOperationId == evidence.operationId && !slot.resolving
            is LocalReturnRequestV2.ReserveRefundRecording -> request.returnId == slot.documentId && request.sessionId == slot.cashSessionId &&
                request.saleId == slot.originalSaleId && request.claimId == slot.originalClaimId && request.attemptId == slot.attemptId &&
                request.recordingOperationId == slot.recordingOperationId && request.clientAllocationId == slot.clientAllocationId
            else -> false
        }
    }
    check(requested) { "sales.physical.producer_request" }
}

internal fun physicalOperationClaim(id: DomainId): ClaimKey = ClaimKey(LocalSalesStoreProfile.RECORDING_OPERATION_NAMESPACE, id.value)
internal fun physicalMasterAllocationClaim(id: DomainId): ClaimKey = ClaimKey(LocalSalesStoreProfile.RECORDING_ALLOCATION_NAMESPACE, id.value)
internal fun physicalClientAllocationClaim(id: DomainId): ClaimKey = ClaimKey(LocalSalesStoreProfile.CLIENT_RECORDING_ALLOCATION_NAMESPACE, id.value)
internal fun physicalAttemptClaim(id: DomainId): ClaimKey = ClaimKey(LocalSalesStoreProfile.RECORDING_ATTEMPT_NAMESPACE, id.value)
