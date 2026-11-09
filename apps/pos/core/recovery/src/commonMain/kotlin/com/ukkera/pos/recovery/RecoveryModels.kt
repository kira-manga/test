package com.ukkera.pos.recovery

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.Serializable

/**
 * An appointment identity and its numeric authority generation are different facts.
 * [epoch] uses Revision's checked, decimal-string scalar; it is NOT a row revision.
 * Ordinary process restart preserves all three fields.
 */
@Serializable
public data class AuthorityIdentity(
    public val assignmentId: DomainId,
    public val deviceId: DomainId,
    public val epoch: Revision,
)

@Serializable
public data class JournalPosition(
    public val branchId: DomainId,
    public val authority: AuthorityIdentity,
    public val sequence: JournalSequence,
)

/** A reference to a coherent, verified manifest, not a claim that a backup was made. */
@Serializable
public data class RecoveryCheckpoint(
    public val checkpointId: DomainId,
    public val position: JournalPosition,
    public val manifestSha256: String,
) {
    init {
        requireRecoveryHash(manifestSha256)
    }
}

/** Sequence zero is the empty epoch; committed envelopes start at one. */
@Serializable
public data class SequenceRange(
    public val first: JournalSequence,
    public val last: JournalSequence,
) {
    init {
        require(first.value > 0 && last.value >= first.value) { "Invalid journal range" }
    }

    public fun overlaps(other: SequenceRange): Boolean =
        first.value <= other.last.value && other.first.value <= last.value
}

/** References protected evidence. Supplying a reference does not authenticate it. */
@Serializable
public data class RecoveryEvidenceRef(
    public val evidenceId: DomainId,
    public val contentSha256: String,
) {
    init {
        requireRecoveryHash(contentSha256)
    }
}

/** Routing epoch, bearer tokens and transport request IDs are deliberately absent. */
@Serializable
public data class OperationIdentity(
    public val branchId: DomainId,
    public val operationId: OperationId,
    public val originalActorId: DomainId,
    public val semanticDigestVersion: Int,
    public val semanticSha256: String,
) {
    init {
        require(semanticDigestVersion > 0) { "Digest version must be positive" }
        requireRecoveryHash(semanticSha256)
    }
}

/**
 * Exact, typed assertions requested by the reducers. These are NOT wire credentials.
 * An adapter must compare the entire assertion with authenticated protected evidence;
 * matching only evidence IDs/hashes, or accepting a caller's `verified` flag, is unsafe.
 */
public sealed interface RecoveryEvidenceAssertion {
    public val branchId: DomainId
    public val evidence: List<RecoveryEvidenceRef>
}

public enum class EvidenceVerification {
    VERIFIED,
    REFUSED,
    UNAVAILABLE,
}

/**
 * Trusted local adapter, bound to the same protected transaction snapshot as the reducer.
 * No network, dialogs or IO to an unqualified copied issuance database belongs here.
 * There is deliberately no production accepting implementation in this bounded module.
 */
public fun interface RecoveryEvidenceVerifier {
    public fun verify(assertion: RecoveryEvidenceAssertion): EvidenceVerification
}

public object UnqualifiedRecoveryEvidenceVerifier : RecoveryEvidenceVerifier {
    override fun verify(assertion: RecoveryEvidenceAssertion): EvidenceVerification =
        EvidenceVerification.UNAVAILABLE
}

public enum class RecoveryRefusal {
    WRONG_BRANCH,
    REVISION_CONFLICT,
    IDENTITY_CONFLICT,
    INVALID_STATE,
    INVALID_AUTHORITY_CHAIN,
    STALE_TARGET,
    EVIDENCE_NOT_VERIFIED,
    HISTORY_INCOMPLETE,
    RESOURCE_RECONCILIATION_REQUIRED,
    RESOURCE_FOOTPRINT_REQUIRED,
    RECONCILIATION_KIND_MISMATCH,
    OBLIGATIONS_NOT_ACKNOWLEDGED,
    RECONCILIATION_NOT_FOUND,
    CONFLICTING_LATE_EVIDENCE,
    POST_SEAL_ABORT_FORBIDDEN,
    OWNER_PHYSICAL_GATE_UNQUALIFIED,
    OLD_WRITER_NOT_EXCLUDED,
    COMPETING_SUCCESSOR_NOT_EXCLUDED,
    ISSUER_CUSTODY_UNCERTAIN,
    TARGET_NOT_EXCLUDED,
    CAPACITY_REQUIRES_PROTECTED_ARCHIVE,
}

public enum class TransitionDisposition {
    APPLIED,
    IDEMPOTENT,
    REFUSED,
}

/**
 * A proposed durable state, never a claim of commit. The store must compare-and-swap
 * the persisted revision and atomically commit state, result, audit and outbox before
 * returning success. Only REFUSED returns the unchanged input state.
 */
public data class RecoveryTransition<S>(
    public val state: S,
    public val disposition: TransitionDisposition,
    public val refusal: RecoveryRefusal? = null,
) {
    init {
        require((disposition == TransitionDisposition.REFUSED) == (refusal != null))
    }
}

internal fun requireRecoveryHash(value: String): Unit {
    require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) {
        "Expected a canonical lowercase SHA-256 digest"
    }
}

internal fun RecoveryEvidenceVerifier.verifies(assertion: RecoveryEvidenceAssertion): Boolean =
    verify(assertion) == EvidenceVerification.VERIFIED

internal fun <S> refused(state: S, reason: RecoveryRefusal): RecoveryTransition<S> =
    RecoveryTransition(state, TransitionDisposition.REFUSED, reason)

internal fun <S> applied(state: S): RecoveryTransition<S> =
    RecoveryTransition(state, TransitionDisposition.APPLIED)

internal fun <S> unchanged(state: S): RecoveryTransition<S> =
    RecoveryTransition(state, TransitionDisposition.IDEMPOTENT)

/** Bounds fail closed; they are not permission to evict financial history. */
internal const val MAX_RECOVERY_ITEMS: Int = 4096
internal const val MAX_EVIDENCE_REFS: Int = 64
