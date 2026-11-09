package com.ukkera.pos.database

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/**
 * A validated, immutable historical operation/journal pair from this local store.
 * Hashes establish internal consistency, not authenticity or complete history.
 * This does not authorize an action, prove a current resource frontier, or supply
 * a reusable proposal. A caller must separately bind its exact producer, schema,
 * scope and original operation and validate every current application dependency.
 * Overlapping handler write namespaces do not establish exclusive provenance.
 *
 * Evidence v1 did not store device identity, affected resources or a commit
 * deadline. None is reconstructed from today's authority, handler or policy.
 * A native-original v2 pair carries a DISTINCT historical/native/writer tag. It
 * retains an incurred original observation, not a currently authorized employee
 * command. Its old Start authentication/policy mirrors are not employee grants.
 */
public class RetainedCommandEvidence internal constructor(
    public val scope: BranchScope,
    public val operationId: OperationId,
    public val originalActorId: DomainId,
    public val command: CommandKey,
    public val assignmentId: DomainId,
    public val authorityEpoch: Revision,
    public val sequence: JournalSequence,
    public val recordedAt: UtcInstant,
    authenticationId: DomainId,
    policyRevision: Revision,
    public val semanticDigest: PayloadDigest,
    public val code: String,
    public val normalized: StoredBytes,
    public val result: StoredBytes,
    public val resultHash: PayloadDigest,
    public val previousJournalHash: PayloadDigest,
    public val journalHash: PayloadDigest,
    aggregateWrites: List<RetainedAggregateWrite>,
    claimChanges: List<RetainedClaimChange>,
    jobs: List<RetainedJobIntent>,
    public val nativeOriginalProvenance: NativeOriginalJournalProvenanceV1? = null,
) {
    // The unchanged SQL schema stores historical Start mirrors for native v2.
    // Only the kernel's exact-pair replay projection may read these storage values.
    internal val storedAuthenticationId: DomainId = authenticationId
    internal val storedPolicyRevision: Revision = policyRevision
    /** Employee-command provenance ONLY. Native intake did not authenticate this employee. */
    public val authenticationId: DomainId get() {
        check(nativeOriginalProvenance == null) { "Employee command provenance unavailable" }
        return storedAuthenticationId
    }
    /** Employee-command provenance ONLY; use nativeOriginalProvenance.historicalStart for v2. */
    public val policyRevision: Revision get() {
        check(nativeOriginalProvenance == null) { "Employee command provenance unavailable" }
        return storedPolicyRevision
    }

    init {
        nativeOriginalProvenance?.let {
            check(it.historicalStart.authenticationId == storedAuthenticationId &&
                it.historicalStart.policyRevision == storedPolicyRevision)
        }
    }

    public val committed: Boolean get() = code == "committed"
    private val writeValues = aggregateWrites.toList()
    private val claimValues = claimChanges.toList()
    private val jobValues = jobs.toList()
    public val aggregateWrites: List<RetainedAggregateWrite> get() = writeValues.toList()
    public val claimChanges: List<RetainedClaimChange> get() = claimValues.toList()
    /** Requested jobs in original order, excluding the store-owned upload ordinal 0. */
    public val jobs: List<RetainedJobIntent> get() = jobValues.toList()
}

/** Exact original AFTER value, not a live StoredAggregate or executable AggregateChange. */
public class RetainedAggregateWrite internal constructor(
    public val key: AggregateKey,
    public val storageGeneration: Revision,
    public val schema: String,
    public val payload: StoredBytes,
    /** Recomputed from the original payload; not a separate signature or stored authority. */
    public val payloadDigest: PayloadDigest,
)

public enum class RetainedClaimAction { ACQUIRE, RELEASE }

/** Original claim event, not proof that its owner still holds this claim. */
public class RetainedClaimChange internal constructor(
    public val action: RetainedClaimAction,
    public val key: ClaimKey,
    public val ownerId: DomainId,
)

/** Historical intent only; not delivery, execution, acknowledgement or permission to retry. */
public class RetainedJobIntent internal constructor(
    public val kind: String,
    public val body: StoredBytes,
)
