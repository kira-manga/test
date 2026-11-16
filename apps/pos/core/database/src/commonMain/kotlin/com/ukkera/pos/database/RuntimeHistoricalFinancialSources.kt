package com.ukkera.pos.database

import com.ukkera.pos.security.AuthorizationRequirement

/** Exact native-observed financial BEFORE data, authenticated against the complete original
 * tail on the same unmutated writer loan. Construction is core-only. This is neither a
 * historical CommandReadView nor business qualification, authorization or native custody.
 * Installed typed validators must close the original and every required ancestral producer.
 * Unobserved keys are unavailable; only the explicit absence sets prove observed SQL absence.
 * No presence/absence here proves that a physical action did or did not occur. */
public class RuntimeHistoricalFinancialSources internal constructor(
    public val evidence: RetainedCommandEvidence,
    requirement: AuthorizationRequirement,
    aggregates: List<RuntimeNativeLiteralAggregateSource>,
    claims: List<RuntimeNativeLiteralClaimSource>,
    absentAggregates: Set<AggregateKey>,
    absentClaims: Set<ClaimKey>,
    public val mustCommitBefore: com.ukkera.pos.types.UtcInstant?,
) {
    public val requirement: AuthorizationRequirement = requirement.copy()
    public val beforeAggregateSources: List<RuntimeNativeLiteralAggregateSource> = aggregates.toList()
    public val beforeClaimSources: List<RuntimeNativeLiteralClaimSource> = claims.toList()
    public val beforeAbsentAggregateKeys: Set<AggregateKey> = absentAggregates.toSet()
    public val beforeAbsentClaimKeys: Set<ClaimKey> = absentClaims.toSet()

    init {
        require(evidence.command in commands && evidence.nativeOriginalProvenance == null &&
            requirement.scope == evidence.scope && requirement.binding?.operationId == evidence.operationId &&
            requirement.binding?.payloadDigest == evidence.semanticDigest)
        require(aggregates.map { it.row.key }.distinct().size == aggregates.size &&
            claims.map { it.key }.distinct().size == claims.size &&
            aggregates.none { it.row.key in absentAggregates } && claims.none { it.key in absentClaims })
    }

    /** Bounded retained DATA charge for callers that compose several original proofs. */
    public val retainedDataBytes: Long = 2_048L +
        (aggregates.size.toLong() + claims.size + absentAggregates.size + absentClaims.size) * 256L +
        aggregates.sumOf { it.row.payload.size.toLong() } +
        (aggregates.map { it.original } + claims.map { it.original }).distinctBy {
            when (it) {
                is RetainedOriginalV2.Legacy -> it.evidence.operationId
                is RetainedOriginalV2.Streamed -> it.evidence.identity.producer.operationId
            }
        }.sumOf {
            when (it) {
                is RetainedOriginalV2.Legacy -> it.evidence.let { value -> value.normalized.size.toLong() + value.result.size +
                    value.aggregateWrites.sumOf { row -> row.payload.size.toLong() + 512L } + value.claimChanges.size * 256L }
                is RetainedOriginalV2.Streamed -> it.evidence.normalized.size.toLong() + it.evidence.result.size + 4_096L
            }
        }

    /** Only an explicitly observed absence may return null. */
    public fun aggregateSource(key: AggregateKey): RuntimeNativeLiteralAggregateSource? =
        beforeAggregateSources.singleOrNull { it.row.key == key }.also {
            check(it != null || key in beforeAbsentAggregateKeys) { "Financial original aggregate was not observed" }
        }
    public fun claim(key: ClaimKey): com.ukkera.pos.types.DomainId? =
        beforeClaimSources.singleOrNull { it.key == key }.also {
            check(it != null || key in beforeAbsentClaimKeys) { "Financial original claim was not observed" }
        }?.ownerId

    override fun toString(): String = "RuntimeHistoricalFinancialSources([REDACTED])"

    internal companion object {
        val commands: Set<CommandKey> = setOf(CommandKey("sales.collection.write", 3),
            CommandKey("sales.return.write", 3), CommandKey("sales.financial-supervisor", 1),
            CommandKey("sales.food.payment", 1), CommandKey("sales.food.payment-supervisor", 1),
            CommandKey("sales.food.refund", 1), CommandKey("sales.food.refund-supervisor", 1),
            CommandKey("purchasing.supplier-cash", 1), CommandKey("purchasing.supplier-cash-supervisor", 1))
    }
}
