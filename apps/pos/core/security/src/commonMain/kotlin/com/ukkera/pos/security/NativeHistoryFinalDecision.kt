package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.UtcInstant

/** A kernel-captured deadline declaration, not a successful final sample or SQL fate.
 * The native owner binds it once to its SAME opaque frame and prepared original intent. */
public data class NativeHistoryFinalDeadlineDeclaration(
    public val originalRequestId: DomainId,
    public val originalOperationId: DomainId,
    public val originalRequestDigest: PayloadDigest,
    public val mustCommitBefore: UtcInstant,
) {
    init {
        require(originalRequestId == originalOperationId)
        require(mustCommitBefore.canonicalString().length in 20..30)
    }
    internal fun requireIntent(intent: NativeHistoryOriginalIntentV2) {
        // The shared persisted purpose is COMMAND_APPLY=2; COMMAND_REJECT=3 cannot
        // have a committed stock deadline. Do not infer this tag from enum position.
        require(intent.purposeTag == 2 && intent.originalActorId != null &&
            intent.originalRequestId == originalRequestId && intent.originalOperationId == originalOperationId &&
            intent.originalRequestDigest == originalRequestDigest)
    }
    override fun toString(): String = "NativeHistoryFinalDeadlineDeclaration([REDACTED])"
}

/** Authenticated retained DATA from the original kernel final decision and actual COMMITTED
 * native outer settlement. It proves the passed PRE-COMMIT wall decision, not that fsync or
 * response delivery occurred before the deadline. Parsed/caller-copied data grant no authority.
 * Only a decision in the original native snapshot is consumed by the complete startup scanner. */
public data class NativeHistoryFinalDeadlineDecision internal constructor(
    public val scope: BranchScope,
    public val originalAllocationDigest: PayloadDigest,
    public val child: NativeHistoryRecordedHeadV2,
    public val originalIntentDigest: PayloadDigest,
    public val declaration: NativeHistoryFinalDeadlineDeclaration,
    public val finalWall: UtcInstant,
) {
    init {
        require(child.historySequence > 1 && child.commandSequence > 0 &&
            finalWall.canonicalString().length in 20..30 && finalWall < declaration.mustCommitBefore)
    }
    override fun toString(): String = "NativeHistoryFinalDeadlineDecision([REDACTED])"
}

/** Finite append-only retention. Exhaustion is a pre-submission hold, never pruning or adoption
 * of a missing old decision. The current native record is actually encoded before reserving
 * the complete maximum pending capsule and the scoped final-decision body reservation. No witness is synthesized
 * for this capacity calculation, and no SQL has begun when the original owner performs it. */
internal object NativeHistoryFinalDecisionCapacity {
    const val MAX_DECISIONS: Int = 4096
    // Exact maximum wire framing: scope (3 UUIDs), allocation digest, complete child head,
    // intent digest, declaration (2 UUIDs/digest/time) and the actual finalWall string.
    const val MAX_DECLARATION_BYTES: Long = 2L * (4 + 36) + (4 + 64) + (4 + 30)
    const val MAX_DECISION_BYTES: Long = 3L * (4 + 36) + (4 + 64) +
        (3L * 8 + 5L * (4 + 64)) + (4 + 64) + MAX_DECLARATION_BYTES + (4 + 30)
    const val MAX_PROTECTED_DECLARATION_BYTES: Long = 6L * (4 + 36) + (4 + 64) + 3L * 8
    const val MAX_PROTECTED_DECISION_BYTES: Long = 3L * (4 + 36) + (4 + 64) +
        (3L * 8 + 5L * (4 + 64)) + (4 + 64) + MAX_PROTECTED_DECLARATION_BYTES + 2L * (4 + 36) + 8

    fun requireBeforeRoomSubmission(record: NativeHistoryAnchorRecordV2) {
        requireBeforeRoomSubmission(record, false)
    }

    fun requireBeforeApprovedRoomSubmission(record: NativeHistoryAnchorRecordV2) {
        require(record.parent != null)
        requireBeforeRoomSubmission(record, true)
    }

    private fun requireBeforeRoomSubmission(record: NativeHistoryAnchorRecordV2, approvedPair: Boolean) {
        if (record.parent == null) return // Original C1 uses its unchanged lifecycle reservation.
        val slots = if (approvedPair) 2 else 1
        require(record.pending == null && record.finalDeadlineDecisions.size + record.protectedFinalDecisions.size <= MAX_DECISIONS - slots &&
            record.generation <= Long.MAX_VALUE - 3L && record.denialGeneration < Long.MAX_VALUE)
        val encoded = SelfOwnedNativeHistoryWireV2.encodeRecord(record)
        try {
            // Reserve either supported final decision before SQL. Old readers keep their
            // exact bytes; a retained newer format also keeps every pending nullable flag.
            val formatGrowth = when (record.recordFormat) {
                SelfOwnedNativeHistoryWireV2.FORMAT -> SelfOwnedNativeHistoryWireV2.PROTECTED_FINAL_RECORD_DOMAIN.length.toLong() -
                    SelfOwnedNativeHistoryWireV2.CAPACITY_RECORD_DOMAIN.length + 8L
                SelfOwnedNativeHistoryWireV2.FINAL_DECISION_RECORD_FORMAT -> SelfOwnedNativeHistoryWireV2.PROTECTED_FINAL_RECORD_DOMAIN.length.toLong() -
                    SelfOwnedNativeHistoryWireV2.FINAL_DECISION_RECORD_DOMAIN.length + 4L
                else -> 0L
            }
            val pendingGrowth = 3L * 8 + 5L * (4 + 64) + 4L +
                SelfOwnedNativeHistoryWireV2.MAX_CAPSULE_BYTES +
                (if (record.recordFormat >= SelfOwnedNativeHistoryWireV2.BUSINESS_PUBLICATION_RECORD_FORMAT) 3L else 2L) +
                (if (approvedPair) MAX_DECLARATION_BYTES + MAX_PROTECTED_DECLARATION_BYTES
                    else maxOf(MAX_DECLARATION_BYTES, MAX_PROTECTED_DECLARATION_BYTES))
            val reserved = formatGrowth + pendingGrowth +
                (if (approvedPair) MAX_DECISION_BYTES + MAX_PROTECTED_DECISION_BYTES
                    else maxOf(MAX_DECISION_BYTES, MAX_PROTECTED_DECISION_BYTES))
            require(encoded.size.toLong() <= SelfOwnedNativeHistoryWireV2.MAX_ANCHOR_BYTES.toLong() - reserved)
        } finally { encoded.fill(0) }
    }
}
