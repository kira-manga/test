package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** The original loan's factual calculation adapter. Core authenticated the entire native
 * cut but its row/writer pairs are UNQUALIFIED until this same request scope recursively
 * closes the installed whole producer. This cannot be passed to a live handler. */
internal class FinancialLegacyOriginalProgram(
    private val proof: LocalFinancialOriginalProofScope,
    private val catalog: LocalFinancialOriginalCatalogProof,
    private val sources: RuntimeHistoricalLegacySources,
) : BusinessOriginalProgram {
    override val evidence: RetainedCommandEvidence get() = sources.evidence
    override val authorizationGeneration: SessionGeneration get() = sources.authorizationGeneration
    private var requirementChecked = false
    private var effectsChecked = false

    override suspend fun aggregateSource(key: AggregateKey): BusinessOriginalAggregate? = sources.aggregateSource(key)?.let {
        proof.charge(it.row.payload.size.toLong() + 512L)
        proof.requireOriginal(it.original)
        BusinessOriginalAggregate(it.row, it.original)
    }
    override suspend fun aggregate(key: AggregateKey): StoredAggregate? = aggregateSource(key)?.row
    override suspend fun claim(key: ClaimKey): DomainId? = sources.claimSource(key)?.let {
        proof.charge(256L); proof.requireOriginal(it.original); it.ownerId
    }
    override suspend fun earlierOriginal(operationId: OperationId): RetainedOriginalV2? = sources.earlierOriginal(operationId)?.also {
        proof.requireOriginal(it)
    }
    override suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence? =
        (earlierOriginal(operationId) as? RetainedOriginalV2.Legacy)?.evidence

    override suspend fun aggregateKeys(kind: String, after: DomainId?, limit: Int): AggregateKeyPage {
        val page = sources.aggregateKeys(kind, after, limit)
        check(page.keys.size <= limit && page.keys.all { it.kind == kind && (after == null || it.id.value > after.value) } &&
            page.keys.map { it.id.value } == page.keys.map { it.id.value }.distinct().sorted() &&
            (page.nextAfter == null || page.keys.lastOrNull()?.id == page.nextAfter))
        // A membership page may not disclose a key backed by an unqualified writer.
        for (key in page.keys) check(aggregateSource(key) != null)
        return page
    }
    override suspend fun claimKeys(namespace: String, prefix: String, after: String?, limit: Int): ClaimKeyPage {
        val page = sources.claimKeys(namespace, prefix, after, limit)
        check(page.entries.size <= limit && page.entries.all { it.key.namespace == namespace && it.key.key.startsWith(prefix) &&
            (after == null || it.key.key > after) } &&
            page.entries.map { it.key.key } == page.entries.map { it.key.key }.distinct().sorted() &&
            (page.nextAfter == null || page.entries.lastOrNull()?.key?.key == page.nextAfter))
        for (entry in page.entries) check(claim(entry.key) == entry.ownerId)
        return page
    }
    override suspend fun forEachAggregate(kind: String, consume: suspend (StoredAggregate) -> Unit) {
        var cursor: DomainId? = null
        do {
            val page = aggregateKeys(kind, cursor, 100)
            for (key in page.keys) consume(checkNotNull(aggregate(key)))
            cursor = page.nextAfter
        } while (cursor != null)
    }
    override suspend fun completedNativeBusinessPublicationsBefore(): List<CompanyBusinessPublicationOutput> {
        val observed = sources.publicationObservation() ?: error("financial.original_publication_observation_required")
        val values = catalog.originalPublicationContents(observed)
        proof.charge(1_024L + values.sumOf { it.proposal.copyCanonicalContent().let { bytes ->
            try { bytes.size.toLong() } finally { bytes.fill(0) }
        } })
        return values
    }
    override suspend fun requireRequirement(expected: AuthorizationRequirement) {
        check(!requirementChecked && sources.requirement == expected)
        requirementChecked = true
    }
    override suspend fun requireLegacyReadEvidence(): StockReadObservations? {
        val observed = StockReadEvidenceV1.read(evidence) ?: return null
        check(observed.scope == scope)
        for (source in observed.sources) when (source) {
            is StreamedSourceObservationV2.Current -> {
                val row = aggregate(source.key) ?: error("financial.original_observed_row_required")
                check(row.revision == source.generation && row.schema == source.schema &&
                    row.payload == source.payload && row.digest == source.payloadHash)
            }
            is StreamedSourceObservationV2.CurrentAbsent -> check(aggregate(source.key) == null)
            is StreamedSourceObservationV2.StockItemAbsent -> check(aggregate(source.key) == null)
            is StreamedSourceObservationV2.Claim -> check(claim(source.key) == source.ownerId)
            else -> error("financial.original_read_profile_unsupported")
        }
        return observed
    }
    override suspend fun requireLegacyEffects(expected: ProposedOutcome.Apply, resources: Set<RecoveryResourceKey>) {
        check(requirementChecked && !effectsChecked && evidence.committed && evidence.code == "committed" &&
            evidence.nativeOriginalProvenance == null && evidence.result == expected.result &&
            evidence.resultHash == sha256(expected.result.copyBytes()) &&
            sources.mustCommitBefore == expected.mustCommitBefore &&
            evidence.aggregateWrites.size == expected.changes.size && evidence.claimChanges.size == expected.claims.size &&
            evidence.jobs.size == expected.jobs.size && sources.resources.size == resources.size && sources.resources.all { native ->
                native.selectorTag == 3 && native.preserveHold && native.originalEvidenceDigest == semanticDigest &&
                    resources.any { it.kind.name == native.kind?.name && it.resourceId == native.resourceId }
            })
        val writes = expected.changes.sortedWith(compareBy({ it.key.kind }, { it.key.id.value }))
        check(writes.map { it.key }.distinct().size == writes.size)
        for ((actual, wanted) in evidence.aggregateWrites.zip(writes)) {
            val before = aggregate(wanted.key)
            check(actual.key == wanted.key && wanted.expectedRevision == before?.revision &&
                actual.storageGeneration == (before?.revision?.next() ?: Revision(1)) &&
                actual.schema == wanted.schema && actual.payload == wanted.payload &&
                actual.payloadDigest == sha256(wanted.payload.copyBytes()))
        }
        val claims = expected.claims.sortedWith(compareBy({ it.key.namespace }, { it.key.key }))
        check(claims.map { it.key }.distinct().size == claims.size)
        for ((actual, wanted) in evidence.claimChanges.zip(claims)) check(actual.key == wanted.key && actual.ownerId == wanted.ownerId &&
            actual.action == when (wanted) { is ClaimChange.Acquire -> RetainedClaimAction.ACQUIRE
                is ClaimChange.Release -> RetainedClaimAction.RELEASE })
        for ((actual, wanted) in evidence.jobs.zip(expected.jobs)) check(actual.kind == wanted.kind && actual.body == wanted.body)
        effectsChecked = true
    }
    fun requireComplete() { check(requirementChecked && effectsChecked) }
}
