package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.json.*

/** Finite ordinary command selection, not a business profile, grant or financial fact. */
public enum class RuntimeCashConfigurationSourceKind {
    CONFIGURE_DRAWER, CONFIGURE_CUSTODIAN, CONFIGURE_SAFE, OPEN, NON_SALE,
    BEGIN_CLOSING, CANCEL_CLOSING, RECORD_CLOSING_COUNT, DELIVER_COUNTED_CLOSE,
    SUBMIT_COUNTED_CLOSE, SUBMIT_CLOSE, REVIEW, DROP_TO_SAFE, RECEIVE_SAFE,
    CONFIGURE_SUPPLIER;

    internal val command: CommandKey get() = if (this == CONFIGURE_SUPPLIER)
        CommandKey("local.configuration.write", 1) else CommandKey("sales.cash.control", 1)

    internal companion object {
        fun select(original: RetainedCommandEvidence): RuntimeCashConfigurationSourceKind? {
            if (!original.committed || original.code != "committed" || original.nativeOriginalProvenance != null ||
                original.command !in setOf(CommandKey("sales.cash.control", 1), CommandKey("local.configuration.write", 1))) return null
            val bytes = original.normalized.copyBytes()
            val type = try {
                if (bytes.size !in 1..32_768) return null
                val body = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) as? JsonObject ?: return null
                val value = body["type"] as? JsonPrimitive ?: return null
                if (!value.isString) return null
                value.content
            } finally { bytes.fill(0) }
            // This selects inert native DATA only. The adapter must decode/reencode the
            // ENTIRE actual typed body and replay every original source/effect separately.
            return entries.singleOrNull { it.name == type && it.command == original.command }
        }
    }
}

/** Exact native BEFORE of a closed ordinary cash/configuration original. Core alone
 * constructs this on the actual unmutated native writer after authenticating the complete
 * original tail, purpose, capture, dependencies and all native mutations. Source pairings
 * remain UNQUALIFIED: this is not a historical CommandReadView, an ordinary command pass,
 * financial evidence, current authority or native custody. Every required ancestor must
 * complete its own installed typed business proof before these values are interpreted. */
public class RuntimeHistoricalCashConfigurationSources internal constructor(
    public val kind: RuntimeCashConfigurationSourceKind,
    public val original: RetainedOriginalV2.Legacy,
    requirement: AuthorizationRequirement,
    resources: List<NativeHistoryOriginalResourceV2>,
    aggregates: List<RuntimeNativeLiteralAggregateSource>,
    claims: List<RuntimeNativeLiteralClaimSource>,
    absentAggregates: Set<AggregateKey>,
    absentClaims: Set<ClaimKey>,
) {
    public val evidence: RetainedCommandEvidence get() = original.evidence
    public val requirement: AuthorizationRequirement = requirement.copy()
    private val resourceValues = resources.map { it.copy() }
    public val resources: List<NativeHistoryOriginalResourceV2> get() = resourceValues.toList()
    public val beforeAggregateSources: List<RuntimeNativeLiteralAggregateSource> = aggregates.toList()
    public val beforeClaimSources: List<RuntimeNativeLiteralClaimSource> = claims.toList()
    public val beforeAbsentAggregateKeys: Set<AggregateKey> = absentAggregates.toSet()
    public val beforeAbsentClaimKeys: Set<ClaimKey> = absentClaims.toSet()

    init {
        require(RuntimeCashConfigurationSourceKind.select(evidence) == kind && evidence.jobs.isEmpty() &&
            this.requirement.scope == evidence.scope && this.requirement.binding?.operationId == evidence.operationId &&
            this.requirement.binding?.payloadDigest == evidence.semanticDigest)
        require(resourceValues.isNotEmpty() && resourceValues.size <= SelfOwnedNativeHistoryWireV2.MAX_RESOURCES &&
            resourceValues.all { it.selectorTag == 3 && it.preserveHold && it.originalEvidenceDigest == evidence.semanticDigest } &&
            resourceValues.map { it.kind to it.resourceId }.distinct().size == resourceValues.size)
        require(aggregates.map { it.row.key }.distinct().size == aggregates.size &&
            claims.map { it.key }.distinct().size == claims.size &&
            aggregates.none { it.row.key in absentAggregates } && claims.none { it.key in absentClaims })
    }

    public val retainedDataBytes: Long = 2_048L + resourceValues.size * 256L +
        (beforeAggregateSources.size.toLong() + beforeClaimSources.size + beforeAbsentAggregateKeys.size + beforeAbsentClaimKeys.size) * 256L +
        beforeAggregateSources.sumOf { it.row.payload.size.toLong() } +
        (beforeAggregateSources.map { it.original } + beforeClaimSources.map { it.original }).distinctBy {
            when (it) { is RetainedOriginalV2.Legacy -> it.evidence.operationId
                is RetainedOriginalV2.Streamed -> it.evidence.identity.producer.operationId }
        }.sumOf {
            when (it) { is RetainedOriginalV2.Legacy -> it.evidence.let { value -> value.normalized.size.toLong() + value.result.size +
                value.aggregateWrites.sumOf { row -> row.payload.size.toLong() + 512L } + value.claimChanges.size * 256L }
                is RetainedOriginalV2.Streamed -> it.evidence.normalized.size.toLong() + it.evidence.result.size + 4_096L }
        }

    public fun aggregateSource(key: AggregateKey): RuntimeNativeLiteralAggregateSource? =
        beforeAggregateSources.singleOrNull { it.row.key == key }.also {
            check(it != null || key in beforeAbsentAggregateKeys) { "Ordinary ancestor aggregate was not observed" }
        }

    public fun claim(key: ClaimKey): DomainId? = beforeClaimSources.singleOrNull { it.key == key }.also {
        check(it != null || key in beforeAbsentClaimKeys) { "Ordinary ancestor claim was not observed" }
    }?.ownerId

    override fun toString(): String = "RuntimeHistoricalCashConfigurationSources([REDACTED])"
}
