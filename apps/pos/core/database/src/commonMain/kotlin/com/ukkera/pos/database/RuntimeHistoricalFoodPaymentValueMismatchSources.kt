package com.ukkera.pos.database

import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.LimitUse
import com.ukkera.pos.security.NativeHistoryOriginalResourceV2
import com.ukkera.pos.security.OwnerConstraint
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.SelfOwnedNativeHistoryWireV2
import com.ukkera.pos.types.Revision

/** Unqualified DATA from this ordinary Pending refusal's actual native BEFORE cut.
 * Its initial application must be paired with the actual preceding native raw original,
 * never an earlier successful Apply or today's application. The installed business
 * producer still proves exact instructed/captured value, lineage, result and no effects.
 * Missing observations remain unavailable; this type grants no current authority. */
public class RuntimeHistoricalFoodPaymentValueMismatchSources internal constructor(
    public val evidence: RetainedCommandEvidence,
    requirement: AuthorizationRequirement,
    resources: List<NativeHistoryOriginalResourceV2>,
    public val beforeApplication: RuntimeNativeLiteralAggregateSource,
    beforeAggregateSources: List<RuntimeNativeLiteralAggregateSource>,
    beforeClaimSources: List<RuntimeNativeLiteralClaimSource>,
    beforeAbsentAggregateKeys: Set<AggregateKey>,
    beforeAbsentClaimKeys: Set<ClaimKey>,
) {
    public val requirement: AuthorizationRequirement = requirement.copy()
    private val resourceValues = resources.map { it.copy() }
    private val aggregateValues = beforeAggregateSources.toList()
    private val claimValues = beforeClaimSources.toList()
    private val absentAggregateValues = beforeAbsentAggregateKeys.toSet()
    private val absentClaimValues = beforeAbsentClaimKeys.toSet()
    public val resources: List<NativeHistoryOriginalResourceV2> get() = resourceValues.toList()
    public val beforeAggregateSources: List<RuntimeNativeLiteralAggregateSource> get() = aggregateValues.toList()
    public val beforeClaimSources: List<RuntimeNativeLiteralClaimSource> get() = claimValues.toList()
    public val beforeAbsentAggregateKeys: Set<AggregateKey> get() = absentAggregateValues.toSet()
    public val beforeAbsentClaimKeys: Set<ClaimKey> get() = absentClaimValues.toSet()

    init {
        val profile = RuntimeHistoricalFoodPaymentRejectionProfile.VALUE_OR_TENDER_MISMATCH
        val binding = requireNotNull(this.requirement.binding)
        require(evidence.command == profile.command && profile.acceptsRejection(evidence.code) && !evidence.committed &&
            evidence.nativeOriginalProvenance == null && evidence.aggregateWrites.isEmpty() &&
            evidence.claimChanges.isEmpty() && evidence.jobs.isEmpty())
        require(this.requirement.scope == evidence.scope &&
            this.requirement.permission.action == SecurityAction.CASH_READ &&
            this.requirement.permission.verb == PermissionVerb.PERFORM &&
            binding.operationId == evidence.operationId && binding.payloadDigest == evidence.semanticDigest &&
            binding.reasonCode == profile.requirementReason && binding.revision == Revision(0) &&
            this.requirement.limitUse == LimitUse.NotApplicable &&
            this.requirement.owner == OwnerConstraint.OwnerPrivate(evidence.originalActorId))
        require(resourceValues.isNotEmpty() && resourceValues.size <= SelfOwnedNativeHistoryWireV2.MAX_RESOURCES &&
            resourceValues.all { it.selectorTag == 3 && it.preserveHold && it.originalEvidenceDigest == evidence.semanticDigest } &&
            resourceValues.map { it.kind to it.resourceId }.distinct().size == resourceValues.size)
        require(aggregateValues.size.toLong() + claimValues.size + absentAggregateValues.size + absentClaimValues.size <=
            RuntimeBusinessHistoryJoin.MAX_VERSIONS &&
            aggregateValues.map { it.row.key }.distinct().size == aggregateValues.size &&
            claimValues.map { it.key }.distinct().size == claimValues.size &&
            aggregateValues.none { it.row.key in absentAggregateValues } &&
            claimValues.none { it.key in absentClaimValues })
        require(beforeApplication.row.key == AggregateKey(APPLICATION_KIND, binding.documentId) &&
            beforeApplication.row.schema == APPLICATION_SCHEMA && beforeApplication.row.revision == Revision(1) &&
            aggregateValues.any { it === beforeApplication })
        val earlier = (beforeApplication.original as? RetainedOriginalV2.Legacy)?.evidence
        require(earlier != null && earlier.committed && earlier.nativeOriginalProvenance != null &&
            earlier.command == CommandKey("sales.food.payment", 1) && earlier.scope == evidence.scope &&
            earlier.assignmentId == evidence.assignmentId && earlier.authorityEpoch == evidence.authorityEpoch &&
            earlier.operationId != evidence.operationId &&
            earlier.sequence.value < evidence.sequence.value)
    }

    override fun toString(): String = "RuntimeHistoricalFoodPaymentValueMismatchSources([REDACTED])"

    internal companion object {
        const val APPLICATION_KIND: String = "sales.food.payment-application"
        const val APPLICATION_SCHEMA: String = "$APPLICATION_KIND/v1"
    }
}
