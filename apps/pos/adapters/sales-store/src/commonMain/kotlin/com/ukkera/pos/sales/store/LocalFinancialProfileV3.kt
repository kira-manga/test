package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.ClaimKey
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId

/** Successor identities only. A v1/v2 command, receipt, slot or fact is never upgraded. */
public object LocalFinancialJournalProfileV3 {
    public val COLLECTION_COMMAND: CommandKey = CommandKey("sales.collection.write", 3)
    public val RETURN_COMMAND: CommandKey = CommandKey("sales.return.write", 3)
    public const val PAYMENT_PROFILE: String = "sales.retail-payment/v3"
    public const val REFUND_PROFILE: String = "sales.retail-refund/v3"
    public const val SLOT_KIND: String = "sales.journal.slot"
    public const val SLOT_SCHEMA: String = "sales.journal.slot/v3"
    public const val ORIGINAL_KIND: String = "sales.journal.original"
    public const val ORIGINAL_SCHEMA: String = "sales.journal.original/v3"
    public const val RESOLUTION_KIND: String = "sales.journal.resolution"
    public const val RESOLUTION_SCHEMA: String = "sales.journal.resolution/v3"
    public const val APPLICATION_KIND: String = "sales.journal.application"
    public const val APPLICATION_SCHEMA: String = "sales.journal.application/v3"
    public const val BEFORE_KIND: String = "sales.journal.before"
    public const val OPERATION_NAMESPACE: String = "sales.journal.operation.v3"
    public const val ALLOCATION_NAMESPACE: String = "sales.journal.allocation.v3"
    public const val ATTEMPT_NAMESPACE: String = "sales.journal.attempt.v3"

    // These bound a retained fact/planning cut. None is a promise of future SQLite growth.
    public const val MAX_REQUEST_BYTES: Int = 8_192
    public const val MAX_RESULT_BYTES: Int = 16_384
    public const val MAX_DOMAIN_REQUIREMENT_BYTES: Int = 6_144
    public const val MAX_SLOT_BYTES: Int = 16_384
    public const val MAX_FACT_BYTES: Int = 24_576
    public const val MAX_PENDING_APPLICATION_BYTES: Int = 8_192
    public const val MAX_APPLICATION_BYTES: Int = 65_536
    public const val MAX_BEFORE_BYTES: Int = 65_536
    public const val MAX_FINANCIAL_RESOURCES: Int = 48
    public const val MAX_FINANCIAL_WRITES: Int = 6
    public const val MAX_FINANCIAL_CLAIMS: Int = 2
    public const val MAX_FINANCIAL_APPLY_BYTES: Int = 196_608

    // The single nonrecursive applied proof must fit one real core record. Oversized known
    // source cuts remain pending/STORAGE_CAPACITY_REQUIRED, not corrupt or silently pruned.
    // Closing that required-local capacity gap needs a separately reviewed chunked successor.

    /** Only effects requiring native acquisition. SQL application bookkeeping is NOT gated. */
    public val gatedKinds: Set<String> = setOf(LocalPhysicalJournalFormatV1.SOURCE_KIND,
        SLOT_KIND, ORIGINAL_KIND, RESOLUTION_KIND)
    public val gatedClaimNamespaces: Set<String> = setOf(OPERATION_NAMESPACE, ALLOCATION_NAMESPACE, ATTEMPT_NAMESPACE)
}

internal fun retailJournalCommandV3(kind: SalesRecordingKind): CommandKey = when (kind) {
    SalesRecordingKind.PAYMENT_ATTESTATION -> LocalFinancialJournalProfileV3.COLLECTION_COMMAND
    SalesRecordingKind.REFUND_ATTESTATION -> LocalFinancialJournalProfileV3.RETURN_COMMAND
}

internal fun retailJournalProfileV3(kind: SalesRecordingKind): String = when (kind) {
    SalesRecordingKind.PAYMENT_ATTESTATION -> LocalFinancialJournalProfileV3.PAYMENT_PROFILE
    SalesRecordingKind.REFUND_ATTESTATION -> LocalFinancialJournalProfileV3.REFUND_PROFILE
}

internal fun retailJournalSlotKeyV3(operationId: OperationId): AggregateKey =
    AggregateKey(LocalFinancialJournalProfileV3.SLOT_KIND, DomainId(operationId.value))
internal fun retailJournalFactKeyV3(phase: LocalJournalFactPhaseV3, operationId: OperationId): AggregateKey =
    AggregateKey(if (phase == LocalJournalFactPhaseV3.ORIGINAL) LocalFinancialJournalProfileV3.ORIGINAL_KIND
        else LocalFinancialJournalProfileV3.RESOLUTION_KIND, DomainId(operationId.value))
internal fun retailJournalApplicationKeyV3(operationId: OperationId): AggregateKey =
    AggregateKey(LocalFinancialJournalProfileV3.APPLICATION_KIND, DomainId(operationId.value))
internal fun retailJournalOperationClaimV3(operationId: OperationId): ClaimKey =
    ClaimKey(LocalFinancialJournalProfileV3.OPERATION_NAMESPACE, operationId.value)
internal fun retailJournalAllocationClaimV3(allocationId: DomainId): ClaimKey =
    ClaimKey(LocalFinancialJournalProfileV3.ALLOCATION_NAMESPACE, allocationId.value)
internal fun retailJournalAttemptClaimV3(attemptId: DomainId): ClaimKey =
    ClaimKey(LocalFinancialJournalProfileV3.ATTEMPT_NAMESPACE, attemptId.value)

internal fun retailJournalDigestV3(body: StoredBytes): PayloadDigest = sha256(body.copyBytes())

internal fun retailJournalTargetBindingV3(target: LocalJournalTargetV3): PayloadDigest = sha256(journalFrame(
    listOf("sales.retail-journal-target/v3", target.kind.name, target.documentId.value, target.sessionId.value,
        target.attemptId.value, target.intentOperationId.value, target.recordingOperationId.value,
        target.resolutionOperationId.value, target.originalSaleId?.value ?: "-", target.originalClaimId?.value ?: "-",
        target.originalPaymentId?.value ?: "-").map(::journalText)))

/** Phase is reserved before an outcome exists; the later parent digest is additionally validated. */
internal fun retailJournalFenceBindingV3(target: LocalJournalTargetV3, phase: LocalJournalFactPhaseV3): PayloadDigest =
    sha256(journalFrame(listOf("sales.retail-journal-future/v3", retailJournalTargetBindingV3(target).value,
        phase.name, target.factOperation(phase).value).map(::journalText)))
