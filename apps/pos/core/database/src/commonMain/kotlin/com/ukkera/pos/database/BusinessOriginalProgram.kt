package com.ukkera.pos.database

import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** Shared factual business calculation and exact proposal comparison. This cannot
 * authenticate, prepare a command, write SQL or mint a native owner. The installed
 * native entry still completes the actual opaque command; retained readers separately
 * qualify every earlier writer before returning DATA to this program. */
public interface BusinessOriginalProgram : BusinessFactRead {
    public val evidence: RetainedCommandEvidence
    override val scope: BranchScope get() = evidence.scope
    public val command: CommandKey get() = evidence.command
    public val operationId: OperationId get() = evidence.operationId
    public val actorId: DomainId get() = evidence.originalActorId
    public val authenticationId: DomainId get() = evidence.authenticationId
    /** Audit DATA from the actual captured business session, never a current grant. */
    public val authorizationGeneration: SessionGeneration
    public val policyRevision: Revision get() = evidence.policyRevision
    public val normalized: StoredBytes get() = evidence.normalized
    public val semanticDigest: PayloadDigest get() = evidence.semanticDigest
    public val recordedAt: UtcInstant get() = evidence.recordedAt
    public suspend fun aggregateSource(key: AggregateKey): BusinessOriginalAggregate?
    public suspend fun earlierOriginal(operationId: OperationId): RetainedOriginalV2?
    public suspend fun forEachAggregate(kind: String, consume: suspend (StoredAggregate) -> Unit)
    public suspend fun forEachClaimKey(namespace: String, prefix: String, consume: suspend (ClaimKey) -> Unit) {
        var cursor: String? = null
        do {
            val page = claimKeys(namespace, prefix, cursor, 100)
            for (entry in page.entries) consume(entry.key)
            cursor = page.nextAfter
        } while (cursor != null)
    }
    public suspend fun completedNativeBusinessPublicationsBefore(): List<CompanyBusinessPublicationOutput>
    public suspend fun requireRequirement(expected: AuthorizationRequirement)
    public suspend fun requireLegacyReadEvidence(): StockReadObservations?
    public suspend fun requireLegacyEffects(expected: ProposedOutcome.Apply, resources: Set<RecoveryResourceKey>)
    public suspend fun aggregates(kind: String): List<StoredAggregate> = buildListSuspending(kind)
    private suspend fun buildListSuspending(kind: String): List<StoredAggregate> {
        val rows = mutableListOf<StoredAggregate>()
        forEachAggregate(kind) { rows += it }
        return rows
    }
}

/** Inert literal/source pairing, not a source qualification or employee authority. */
public class BusinessOriginalAggregate(public val row: StoredAggregate, public val original: RetainedOriginalV2)

/** Uses the SAME active native command and registered checks. No historical command
 * view, current grant or native completion flag is reconstructed from retained DATA. */
public class NativeBusinessOriginalProgram(private val value: RuntimeHistoricalCommand) : BusinessOriginalProgram,
    BusinessFactRead by RuntimeHistoricalBusinessRead(value) {
    override val scope: BranchScope get() = value.scope
    override val authorizationGeneration: SessionGeneration get() = value.authorizationGeneration
    override val evidence: RetainedCommandEvidence = (value.original as? RetainedOriginalV2.Legacy)?.evidence
        ?: error("Ordinary original program required")
    override suspend fun aggregateSource(key: AggregateKey): BusinessOriginalAggregate? =
        value.aggregateSource(key)?.let { BusinessOriginalAggregate(it.row, it.original) }
    override suspend fun earlierOriginal(operationId: OperationId): RetainedOriginalV2? = value.earlierOriginal(operationId)
    override suspend fun forEachAggregate(kind: String, consume: suspend (StoredAggregate) -> Unit): Unit = value.forEachAggregate(kind, consume)
    override suspend fun aggregates(kind: String): List<StoredAggregate> = value.aggregates(kind)
    override suspend fun forEachClaimKey(namespace: String, prefix: String, consume: suspend (ClaimKey) -> Unit): Unit =
        value.forEachClaimKey(namespace, prefix, consume)
    override suspend fun completedNativeBusinessPublicationsBefore(): List<CompanyBusinessPublicationOutput> =
        value.completedNativeBusinessPublicationsBefore()
    override suspend fun requireRequirement(expected: AuthorizationRequirement): Unit = value.requireRequirement(expected)
    override suspend fun requireLegacyReadEvidence(): StockReadObservations? = value.requireLegacyReadEvidence()
    override suspend fun requireLegacyEffects(expected: ProposedOutcome.Apply, resources: Set<RecoveryResourceKey>): Unit =
        value.requireLegacyEffects(expected, resources)
}
