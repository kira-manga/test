package com.ukkera.pos.database

import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.types.DomainId

/** Literal native retention is not the installed FOOD/catalog business-producer proof. */
public enum class RuntimeNativeLiteralBusinessProvenance { UNQUALIFIED }

/** Exact native AFTER row/original pairing selected strictly before one retained refusal.
 * The immutable native tail and literal write are authenticated, but the installed typed
 * producer has NOT been verified by this pairing. Consumers must complete that independent
 * business proof on the same borrowed loan before interpreting an owner or activity. */
public class RuntimeNativeLiteralAggregateSource internal constructor(
    public val row: StoredAggregate,
    public val original: RetainedOriginalV2,
    public val writerHistorySequence: Long,
) {
    public val businessProvenance: RuntimeNativeLiteralBusinessProvenance = RuntimeNativeLiteralBusinessProvenance.UNQUALIFIED
    init { require(writerHistorySequence > 0) }
    override fun toString(): String = "RuntimeNativeLiteralAggregateSource([REDACTED])"
}

/** Native literal claim acquisition, not an ownership grant or a typed business proof. */
public class RuntimeNativeLiteralClaimSource internal constructor(
    public val key: ClaimKey,
    public val ownerId: DomainId,
    public val original: RetainedOriginalV2,
    public val writerHistorySequence: Long,
) {
    public val businessProvenance: RuntimeNativeLiteralBusinessProvenance = RuntimeNativeLiteralBusinessProvenance.UNQUALIFIED
    init { require(writerHistorySequence > 0) }
    override fun toString(): String = "RuntimeNativeLiteralClaimSource([REDACTED])"
}

/** Native-selected row state, not an interpretation of an unavailable history reader.
 * A closed ORDER is still PRESENT: its immutable body supplies that fact. */
public enum class RuntimeHistoricalFoodCheckBeforeOrderState { PRESENT, ABSENT }

/** Source-only projection of one CLOSED native FOOD refusal. Construction is confined to
 * the actual owner's borrowed frame after the complete immutable tail has joined its native
 * original parent. The requirement is original captured DATA, never a current employee grant.
 * The ORDER is the latest actual row strictly before the rejection, independent of the
 * requested revision. A present row includes its literal earlier original and actual claim,
 * NOT an already completed business join. The bounded dependency lists cover only the
 * rejection's actual observations; they cannot act as a historical CommandReadView.
 * Failed, unsupported or partial proof cannot produce ABSENT. This pairing is used only on
 * the same borrowed read; its retained original cannot be carried to another writer loan. */
public class RuntimeHistoricalFoodCheckRejectedSources internal constructor(
    public val evidence: RetainedCommandEvidence,
    requirement: AuthorizationRequirement,
    public val orderKey: AggregateKey,
    public val beforeOrder: RuntimeNativeLiteralAggregateSource?,
    public val beforeOrderClaimOwnerId: DomainId?,
    public val beforeOrderState: RuntimeHistoricalFoodCheckBeforeOrderState,
    beforeAggregateSources: List<RuntimeNativeLiteralAggregateSource>,
    beforeClaimSources: List<RuntimeNativeLiteralClaimSource>,
    beforeAbsentAggregateKeys: Set<AggregateKey>,
    beforeAbsentClaimKeys: Set<ClaimKey>,
) {
    public val requirement: AuthorizationRequirement = requirement.copy()
    public val beforeAggregateSources: List<RuntimeNativeLiteralAggregateSource> = beforeAggregateSources.toList()
    public val beforeClaimSources: List<RuntimeNativeLiteralClaimSource> = beforeClaimSources.toList()
    public val beforeAbsentAggregateKeys: Set<AggregateKey> = beforeAbsentAggregateKeys.toSet()
    public val beforeAbsentClaimKeys: Set<ClaimKey> = beforeAbsentClaimKeys.toSet()

    init {
        require(evidence.command == StockStreamingCommandsV2.FOOD && !evidence.committed &&
            evidence.nativeOriginalProvenance == null && evidence.aggregateWrites.isEmpty() &&
            evidence.claimChanges.isEmpty() && evidence.jobs.isEmpty())
        require(this.requirement.scope == evidence.scope &&
            this.requirement.binding?.operationId == evidence.operationId &&
            this.requirement.binding?.payloadDigest == evidence.semanticDigest)
        require(this.beforeAggregateSources.size <= RuntimeBusinessHistoryJoin.MAX_VERSIONS &&
            this.beforeClaimSources.size <= RuntimeBusinessHistoryJoin.MAX_VERSIONS &&
            this.beforeAggregateSources.map { it.row.key }.distinct().size == this.beforeAggregateSources.size &&
            this.beforeClaimSources.map { it.key }.distinct().size == this.beforeClaimSources.size &&
            this.beforeAggregateSources.none { it.row.key in this.beforeAbsentAggregateKeys } &&
            this.beforeClaimSources.none { it.key in this.beforeAbsentClaimKeys })
        when (beforeOrderState) {
            RuntimeHistoricalFoodCheckBeforeOrderState.PRESENT -> require(beforeOrder?.row?.key == orderKey &&
                beforeOrderClaimOwnerId == orderKey.id && this.beforeAggregateSources.any { it === beforeOrder })
            RuntimeHistoricalFoodCheckBeforeOrderState.ABSENT -> require(beforeOrder == null && beforeOrderClaimOwnerId == null)
        }
    }

    override fun toString(): String = "RuntimeHistoricalFoodCheckRejectedSources([REDACTED])"
}
