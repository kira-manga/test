package com.ukkera.pos.database

import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.types.DomainId

/** Actual native-selected state, not absence inferred from a failed or partial read.
 * A closed ORDER remains PRESENT; its installed business producer proves that body. */
public enum class RuntimeHistoricalFoodTableAssignmentBeforeOrderState { PRESENT, ABSENT }

/** Source-only projection of one CLOSED native table-assignment refusal on the actual
 * owner's borrowed frame after the entire immutable tail joins its original parent.
 * The captured requirement is original DATA, not a current employee grant. The ORDER
 * is the latest genuine row strictly before the refusal, independent of the requested
 * revision. Every literal row/claim retains its exact earlier original but remains
 * UNQUALIFIED until the installed typed producer independently closes that original
 * on this same loan. These bounded lists expose actual recorded observations only;
 * they prove neither complete directory membership nor a historical CommandReadView.
 * Failed, unsupported or partial proof cannot produce ABSENT. */
public class RuntimeHistoricalFoodTableAssignmentRejectedSources internal constructor(
    public val evidence: RetainedCommandEvidence,
    requirement: AuthorizationRequirement,
    public val orderKey: AggregateKey,
    public val beforeOrder: RuntimeNativeLiteralAggregateSource?,
    public val beforeOrderClaimOwnerId: DomainId?,
    public val beforeOrderState: RuntimeHistoricalFoodTableAssignmentBeforeOrderState,
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
        val profile = RuntimeHistoricalConfigurationRejectionProfile.FOOD_TABLE_ASSIGNMENT
        require(evidence.command == profile.command && evidence.code in profile.rejectionCodes && !evidence.committed &&
            evidence.nativeOriginalProvenance == null && evidence.aggregateWrites.isEmpty() &&
            evidence.claimChanges.isEmpty() && evidence.jobs.isEmpty())
        require(this.requirement.scope == evidence.scope &&
            this.requirement.binding?.operationId == evidence.operationId &&
            this.requirement.binding?.payloadDigest == evidence.semanticDigest)
        require(this.beforeAggregateSources.size <= RuntimeBusinessHistoryJoin.MAX_VERSIONS &&
            this.beforeClaimSources.size <= RuntimeBusinessHistoryJoin.MAX_VERSIONS &&
            this.beforeAbsentAggregateKeys.size <= RuntimeBusinessHistoryJoin.MAX_VERSIONS &&
            this.beforeAbsentClaimKeys.size <= RuntimeBusinessHistoryJoin.MAX_VERSIONS &&
            this.beforeAggregateSources.map { it.row.key }.distinct().size == this.beforeAggregateSources.size &&
            this.beforeClaimSources.map { it.key }.distinct().size == this.beforeClaimSources.size &&
            this.beforeAggregateSources.none { it.row.key in this.beforeAbsentAggregateKeys } &&
            this.beforeClaimSources.none { it.key in this.beforeAbsentClaimKeys })
        when (beforeOrderState) {
            RuntimeHistoricalFoodTableAssignmentBeforeOrderState.PRESENT -> require(beforeOrder?.row?.key == orderKey &&
                beforeOrderClaimOwnerId == orderKey.id && this.beforeAggregateSources.any { it === beforeOrder })
            RuntimeHistoricalFoodTableAssignmentBeforeOrderState.ABSENT -> require(beforeOrder == null &&
                beforeOrderClaimOwnerId == null && orderKey in this.beforeAbsentAggregateKeys)
        }
    }

    override fun toString(): String = "RuntimeHistoricalFoodTableAssignmentRejectedSources([REDACTED])"
}
