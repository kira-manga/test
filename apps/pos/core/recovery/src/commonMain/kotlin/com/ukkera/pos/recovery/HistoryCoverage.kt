package com.ukkera.pos.recovery

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import kotlinx.serialization.Serializable

@Serializable
public enum class AvailableHistorySource {
    VERIFIED_LOCAL_INDEX,
    VERIFIED_PROTECTED_ARCHIVE,
}

/** Coverage of operation identity/result history, not merely of reporting totals. */
@Serializable
public data class AvailableHistoryRange(
    public val range: SequenceRange,
    public val source: AvailableHistorySource,
    public val evidence: RecoveryEvidenceRef,
)

@Serializable
public sealed interface HistoryTail {
    @Serializable
    public data class SealedFinalCut(public val evidence: RecoveryEvidenceRef) : HistoryTail

    /** Usable only in the current writer's coordinated lookup transaction. */
    @Serializable
    public data class LiveWriterHead(
        public val transactionId: DomainId,
        public val evidence: RecoveryEvidenceRef,
    ) : HistoryTail

    /** A restored maximum sequence does not establish that a later tail never existed. */
    @Serializable
    public data object Unknown : HistoryTail
}

@Serializable
public data class EpochHistoryCoverage(
    public val authority: AuthorityIdentity,
    public val predecessorCheckpoint: RecoveryCheckpoint?,
    public val checkpoint: RecoveryCheckpoint,
    public val availableRanges: List<AvailableHistoryRange>,
    public val knownGaps: List<SequenceRange>,
    public val unavailableArchives: List<SequenceRange>,
    public val tail: HistoryTail,
) {
    init {
        require(availableRanges.size <= MAX_RECOVERY_ITEMS)
        require(knownGaps.size <= MAX_RECOVERY_ITEMS)
        require(unavailableArchives.size <= MAX_RECOVERY_ITEMS)
    }
}

/** Persist this shape with the restore/authority edge; never reconstruct it from MAX(seq). */
@Serializable
public data class HistoryCoverage(
    public val branchId: DomainId,
    public val epochs: List<EpochHistoryCoverage>,
    public val genesisAndChainEvidence: RecoveryEvidenceRef?,
) {
    init {
        require(epochs.size <= MAX_RECOVERY_ITEMS)
    }
}

/**
 * Transient, trusted-store observation; NOT a credential or a persistable negative result.
 * The adapter must coordinate this lookup with the same durable operation identity and
 * writer transaction protocol used by submissions. Never return cached NOT_RECORDED.
 */
public data class CoordinatedLookupObservation(
    public val branchId: DomainId,
    public val operationId: OperationId,
    public val transactionId: DomainId,
    public val currentAuthority: AuthorityIdentity,
    public val visibleCheckpoint: RecoveryCheckpoint,
    public val evidence: RecoveryEvidenceRef,
)

public data class HistoryChainAssertion(
    public val coverage: HistoryCoverage,
    public val observation: CoordinatedLookupObservation,
) : RecoveryEvidenceAssertion {
    override val branchId: DomainId get() = coverage.branchId
    override val evidence: List<RecoveryEvidenceRef> get() =
        listOfNotNull(coverage.genesisAndChainEvidence)
}

public data class HistoryRangeAssertion(
    public val checkpoint: RecoveryCheckpoint,
    public val available: AvailableHistoryRange,
) : RecoveryEvidenceAssertion {
    override val branchId: DomainId get() = checkpoint.position.branchId
    override val evidence: List<RecoveryEvidenceRef> get() = listOf(available.evidence)
}

public data class HistoryTailAssertion(
    public val epoch: EpochHistoryCoverage,
    public val observation: CoordinatedLookupObservation,
) : RecoveryEvidenceAssertion {
    override val branchId: DomainId get() = epoch.checkpoint.position.branchId
    override val evidence: List<RecoveryEvidenceRef> get() = when (val tail = epoch.tail) {
        is HistoryTail.SealedFinalCut -> listOf(tail.evidence)
        is HistoryTail.LiveWriterHead -> listOf(tail.evidence)
        HistoryTail.Unknown -> emptyList()
    }
}

public data class CoordinatedAbsenceAssertion(
    public val identity: OperationIdentity,
    public val observation: CoordinatedLookupObservation,
) : RecoveryEvidenceAssertion {
    override val branchId: DomainId get() = identity.branchId
    override val evidence: List<RecoveryEvidenceRef> get() = listOf(observation.evidence)
}

public enum class CoverageAssessment {
    VERIFIED_COMPLETE,
    HISTORY_INCOMPLETE,
    INVALID_CHAIN_OR_SCOPE,
}

/** Conservative proof checker, not a snapshot reader, backup tool or archive fetcher. */
public class HistoryCoveragePolicy(
    private val verifier: RecoveryEvidenceVerifier = UnqualifiedRecoveryEvidenceVerifier,
) {
    public fun assess(
        coverage: HistoryCoverage,
        observation: CoordinatedLookupObservation,
    ): CoverageAssessment {
        if (coverage.branchId != observation.branchId || coverage.epochs.isEmpty()) {
            return CoverageAssessment.INVALID_CHAIN_OR_SCOPE
        }
        if (coverage.epochs.last().authority != observation.currentAuthority ||
            coverage.epochs.last().checkpoint != observation.visibleCheckpoint
        ) {
            return CoverageAssessment.INVALID_CHAIN_OR_SCOPE
        }
        if (coverage.epochs.first().predecessorCheckpoint != null ||
            coverage.epochs.map { it.authority.assignmentId }.toSet().size != coverage.epochs.size
        ) {
            return CoverageAssessment.INVALID_CHAIN_OR_SCOPE
        }
        for ((index, epoch) in coverage.epochs.withIndex()) {
            if (epoch.checkpoint.position.branchId != coverage.branchId ||
                epoch.checkpoint.position.authority != epoch.authority
            ) {
                return CoverageAssessment.INVALID_CHAIN_OR_SCOPE
            }
            if (index > 0) {
                val previous = coverage.epochs[index - 1]
                if (epoch.predecessorCheckpoint != previous.checkpoint ||
                    previous.authority.epoch.value == Long.MAX_VALUE ||
                    epoch.authority.epoch.value != previous.authority.epoch.value + 1L
                ) {
                    return CoverageAssessment.INVALID_CHAIN_OR_SCOPE
                }
            }
            val cut = epoch.checkpoint.position.sequence.value
            val allRanges = epoch.availableRanges.map { it.range } + epoch.knownGaps + epoch.unavailableArchives
            if (allRanges.any { it.last.value > cut }) {
                return CoverageAssessment.INVALID_CHAIN_OR_SCOPE
            }
            val available = epoch.availableRanges.sortedBy { it.range.first.value }
            if (available.zipWithNext().any { (left, right) -> left.range.overlaps(right.range) }) {
                return CoverageAssessment.INVALID_CHAIN_OR_SCOPE
            }
            if (epoch.knownGaps.isNotEmpty() || epoch.unavailableArchives.isNotEmpty()) {
                return CoverageAssessment.HISTORY_INCOMPLETE
            }
            var coveredThrough = 0L
            for (range in available) {
                // No overflow: no next value is evaluated after MAX_VALUE.
                if (coveredThrough == Long.MAX_VALUE || range.range.first.value != coveredThrough + 1L) {
                    return CoverageAssessment.HISTORY_INCOMPLETE
                }
                if (!verifier.verifies(HistoryRangeAssertion(epoch.checkpoint, range))) {
                    return CoverageAssessment.HISTORY_INCOMPLETE
                }
                coveredThrough = range.range.last.value
            }
            if (coveredThrough != cut) return CoverageAssessment.HISTORY_INCOMPLETE
            when (val tail = epoch.tail) {
                HistoryTail.Unknown -> return CoverageAssessment.HISTORY_INCOMPLETE
                is HistoryTail.LiveWriterHead -> {
                    if (index != coverage.epochs.lastIndex || tail.transactionId != observation.transactionId) {
                        return CoverageAssessment.HISTORY_INCOMPLETE
                    }
                }
                is HistoryTail.SealedFinalCut -> Unit
            }
            if (!verifier.verifies(HistoryTailAssertion(epoch, observation))) {
                return CoverageAssessment.HISTORY_INCOMPLETE
            }
        }
        if (coverage.genesisAndChainEvidence == null ||
            !verifier.verifies(HistoryChainAssertion(coverage, observation))
        ) {
            return CoverageAssessment.HISTORY_INCOMPLETE
        }
        return CoverageAssessment.VERIFIED_COMPLETE
    }
}

@Serializable
public enum class KnownOperationState {
    COMMITTED,
    REJECTED,
    IN_PROGRESS,
}

/** Metadata only. The authorized operation store retains and projects the actual result. */
@Serializable
public data class KnownOperationMetadata(
    public val identity: OperationIdentity,
    public val state: KnownOperationState,
    public val origin: JournalPosition?,
    public val recordSha256: String,
    public val evidence: RecoveryEvidenceRef,
) {
    init {
        requireRecoveryHash(recordSha256)
        require(state == KnownOperationState.IN_PROGRESS || origin != null)
        require(origin == null || origin.branchId == identity.branchId)
    }
}

/**
 * Verify original identity, canonical result content, committed/known status and its
 * historically authorized origin. Quarantined or unauthenticated fragments are not
 * positive results merely because they contain an operation ID.
 */
public data class KnownOperationAssertion(
    public val record: KnownOperationMetadata,
) : RecoveryEvidenceAssertion {
    override val branchId: DomainId get() = record.identity.branchId
    override val evidence: List<RecoveryEvidenceRef> get() = listOf(record.evidence)
}

public enum class OperationOutcomeClassification {
    COMMITTED,
    REJECTED,
    IN_PROGRESS,
    NOT_RECORDED,
    HISTORY_INCOMPLETE,
    IDENTITY_CONFLICT,
}

/**
 * Internal-domain classification, NOT an unauthenticated operation.get endpoint.
 * The gateway must authorize the original actor or an explicitly audited recovery
 * delegate BEFORE loading any record/coverage and must perform a fresh coordinated
 * identity lookup. This class returns no result body, totals, documents or actor data.
 * A NOT_RECORDED decision only permits the SAME command through the transaction
 * protocol; it never says an external payment failed or grants mutation authority.
 */
public class OperationOutcomePolicy(
    private val verifier: RecoveryEvidenceVerifier = UnqualifiedRecoveryEvidenceVerifier,
) {
    public fun classify(
        identity: OperationIdentity,
        recorded: KnownOperationMetadata?,
        coverage: HistoryCoverage,
        observation: CoordinatedLookupObservation,
    ): OperationOutcomeClassification {
        if (identity.branchId != coverage.branchId || identity.branchId != observation.branchId ||
            identity.operationId != observation.operationId
        ) {
            return OperationOutcomeClassification.IDENTITY_CONFLICT
        }
        if (recorded != null) {
            if (recorded.identity != identity) return OperationOutcomeClassification.IDENTITY_CONFLICT
            if (!verifier.verifies(KnownOperationAssertion(recorded))) {
                return OperationOutcomeClassification.HISTORY_INCOMPLETE
            }
            return when (recorded.state) {
                KnownOperationState.COMMITTED -> OperationOutcomeClassification.COMMITTED
                KnownOperationState.REJECTED -> OperationOutcomeClassification.REJECTED
                KnownOperationState.IN_PROGRESS -> OperationOutcomeClassification.IN_PROGRESS
            }
        }
        if (!verifier.verifies(CoordinatedAbsenceAssertion(identity, observation))) {
            return OperationOutcomeClassification.HISTORY_INCOMPLETE
        }
        return if (HistoryCoveragePolicy(verifier).assess(coverage, observation) == CoverageAssessment.VERIFIED_COMPLETE) {
            OperationOutcomeClassification.NOT_RECORDED
        } else {
            OperationOutcomeClassification.HISTORY_INCOMPLETE
        }
    }
}
