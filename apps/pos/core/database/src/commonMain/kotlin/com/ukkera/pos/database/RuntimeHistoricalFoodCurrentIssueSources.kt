package com.ukkera.pos.database

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** SAME closed native scan's literal linked issue/reservation and their COMPLETE current
 * member ranges. All sources already passed the whole original producer, native mutation,
 * canonical/index and current-tip joins. The first issue is the actual native generation1
 * insert paired with its already joined original, not a replay or an inferred send receipt.
 * Only the installed FOOD current owner can borrow this inert source set while it is active. */
public class RuntimeHistoricalFoodCurrentIssueSources internal constructor(
    private val owner: RuntimeHistoricalFoodCurrentState,
    public val scope: BranchScope,
    public val portionId: DomainId,
    public val reservationId: DomainId,
    public val issueId: DomainId,
    sources: List<RuntimeHistoricalAggregateSource>,
    firstIssue: RuntimeHistoricalAggregateSource,
    claimOwners: Map<ClaimKey, DomainId?>,
) {
    private var sources = sources.toList()
    private var firstIssue: RuntimeHistoricalAggregateSource? = firstIssue
    private var claimOwners = claimOwners.toMap()
    private var closed = false

    public suspend fun sources(): List<RuntimeHistoricalAggregateSource> {
        owner.requireIssueSources(this)
        return sources.toList()
    }

    public suspend fun firstIssueSource(): RuntimeHistoricalAggregateSource {
        owner.requireIssueSources(this)
        return firstIssue ?: malformed()
    }

    /** Exact current permanent claims selected independently from the native lineages.
     * An unobserved key is never treated as absent or accepted as an identity source. */
    public suspend fun claim(key: ClaimKey): DomainId? {
        owner.requireIssueSources(this)
        historyShapeV2(key in claimOwners)
        return claimOwners[key]
    }

    /** Every strict stock decoder identity must agree with its literal native row and
     * already joined writer. The first issue is compared separately from current revisions. */
    public suspend fun requireExactWriters(expected: List<RuntimeHistoricalFoodCurrentWriter>,
        first: RuntimeHistoricalFoodCurrentWriter) {
        owner.requireIssueSources(this)
        historyShapeV2(!closed && expected.size == sources.size && expected.map { it.key }.distinct().size == expected.size)
        val byKey = sources.associateBy { it.row.key }
        historyShapeV2(byKey.size == sources.size)
        fun requireWriter(value: RuntimeHistoricalFoodCurrentWriter, source: RuntimeHistoricalAggregateSource) {
            val original = (source.original as? RetainedOriginalV2.Streamed)?.evidence ?: malformed()
            val row = source.row
            historyShapeV2(row.key == value.key && row.revision == value.revision &&
                original.identity.scope == scope && original.identity.producer.operationId == value.operationId)
        }
        for (value in expected) requireWriter(value, byKey[value.key] ?: malformed())
        val originalIssue = firstIssue ?: malformed()
        historyShapeV2(first.key == AggregateKey(StockRelationalKindsV2.ISSUE, issueId) && first.revision == Revision(1))
        requireWriter(first, originalIssue)
        closed = true
    }

    internal fun requireClosed() { historyShapeV2(closed) }
    internal fun clear() { sources = emptyList(); firstIssue = null; claimOwners = emptyMap(); closed = false }
    override fun toString(): String = "RuntimeHistoricalFoodCurrentIssueSources([REDACTED])"

    internal companion object {
        const val RESERVATION_CLAIM: String = "local.stock.reservation-id"
        const val ALLOCATION_CLAIM: String = "local.stock.allocation-id"
        const val SOURCE_CLAIM: String = "local.stock.source"
        private fun malformed(): Nothing = throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED)
    }
}
