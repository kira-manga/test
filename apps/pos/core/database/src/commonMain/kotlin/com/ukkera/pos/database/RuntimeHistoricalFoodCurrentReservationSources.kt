package com.ukkera.pos.database

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId

/** Literal unissued reservation and EVERY current allocation/hold from the SAME fully
 * joined native scan. No historical CommandReadView or stock availability grant is created.
 * Its deterministic issue row and permanent issue claim were independently checked absent.
 * The installed FOOD owner must decode all members and close their exact native writers. */
public class RuntimeHistoricalFoodCurrentReservationSources internal constructor(
    private val owner: RuntimeHistoricalFoodCurrentState,
    public val scope: BranchScope,
    public val portionId: DomainId,
    public val reservationId: DomainId,
    public val unusedIssueId: DomainId,
    sources: List<RuntimeHistoricalAggregateSource>,
    claimOwners: Map<ClaimKey, DomainId?>,
) {
    private var sources = sources.toList()
    private var claimOwners = claimOwners.toMap()
    private var closed = false

    public suspend fun sources(): List<RuntimeHistoricalAggregateSource> {
        owner.requireReservationSources(this)
        return sources.toList()
    }

    public suspend fun claim(key: ClaimKey): DomainId? {
        owner.requireReservationSources(this)
        historyShapeV2(key in claimOwners)
        return claimOwners[key]
    }

    public suspend fun requireExactWriters(expected: List<RuntimeHistoricalFoodCurrentWriter>) {
        owner.requireReservationSources(this)
        historyShapeV2(!closed && expected.size == sources.size && expected.map { it.key }.distinct().size == expected.size)
        val byKey = sources.associateBy { it.row.key }
        historyShapeV2(byKey.size == sources.size)
        for (value in expected) {
            val source = byKey[value.key] ?: malformed()
            val original = (source.original as? RetainedOriginalV2.Streamed)?.evidence ?: malformed()
            historyShapeV2(source.row.key == value.key && source.row.revision == value.revision &&
                original.identity.scope == scope && original.identity.producer.operationId == value.operationId)
        }
        closed = true
    }

    internal fun requireClosed() { historyShapeV2(closed) }
    internal fun clear() { sources = emptyList(); claimOwners = emptyMap(); closed = false }
    override fun toString(): String = "RuntimeHistoricalFoodCurrentReservationSources([REDACTED])"

    private fun malformed(): Nothing = throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED)
}
