package com.ukkera.pos.pricing

import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.ExactRational
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.PercentageRate
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitPrice
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Canonical local-storage envelope, not a wire capability, signature or durable commit. */
public object StoredPricingSnapshotCodec {
    public const val SCHEMA_VERSION: Int = 1
    public const val MAX_JSON_CHARACTERS: Int = 8 * 1_024 * 1_024
    public const val MAX_ENCODED_BYTES: Int = 8 * 1_024 * 1_024
    public const val MAX_STORED_RECORDS: Int = 16_384
    private val json = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false }

    public fun encode(snapshot: PricingSnapshot): String {
        checkStorageBudget(snapshot)
        validateStoredPricingSnapshot(snapshot)
        val encoded = json.encodeToString(StoredSnapshot.from(snapshot))
        require(encoded.length <= MAX_JSON_CHARACTERS) { "Stored pricing snapshot exceeds envelope bound" }
        require(encoded.encodeToByteArray().size <= MAX_ENCODED_BYTES) { "Stored pricing UTF-8 envelope exceeds bound" }
        return encoded
    }

    /** Validates stored historical amounts; never calls calculate or a current catalog. */
    public fun decode(encoded: String): PricingSnapshot {
        checkJsonEnvelope(encoded)
        val wire = json.decodeFromString<StoredSnapshot>(encoded)
        require(wire.schemaVersion == SCHEMA_VERSION) { "Unsupported stored pricing schema; explicit migration required" }
        require(json.encodeToString(wire) == encoded) { "Stored pricing JSON must be canonical; ambiguous/duplicate fields reject" }
        val snapshot = wire.snapshot()
        checkStorageBudget(snapshot)
        validateStoredPricingSnapshot(snapshot)
        return snapshot
    }

    private fun checkJsonEnvelope(encoded: String) {
        require(encoded.length <= MAX_JSON_CHARACTERS) { "Stored pricing snapshot exceeds envelope bound" }
        require(encoded.encodeToByteArray().size <= MAX_ENCODED_BYTES) { "Stored pricing UTF-8 envelope exceeds bound" }
        var quoted = false
        var escape = false
        val frames = mutableListOf<Pair<Char, Int>>()
        for (character in encoded) {
            if (quoted) {
                if (escape) escape = false
                else if (character == '\\') escape = true
                else if (character == '"') quoted = false
            } else when (character) {
                '"' -> quoted = true
                '[', '{' -> {
                    require(frames.size < 24) { "Stored pricing JSON nesting exceeds bound" }
                    frames += character to 0
                }
                ']', '}' -> {
                    require(frames.isNotEmpty() && frames.last().first == if (character == ']') '[' else '{') { "Invalid JSON nesting" }
                    frames.removeAt(frames.lastIndex)
                }
                ',' -> if (frames.isNotEmpty() && frames.last().first == '[') {
                    val next = frames.last().second + 1
                    require(next < PricingProfiles.MAX_COMPONENTS) { "Stored pricing array exceeds bound" }
                    frames[frames.lastIndex] = '[' to next
                }
            }
        }
        require(!quoted && frames.isEmpty()) { "Incomplete stored pricing JSON" }
    }

    /** Conservative pre-encode character budget prevents a giant intermediate JSON allocation. */
    private fun checkStorageBudget(snapshot: PricingSnapshot) {
        val records = snapshot.lines.size.toLong() + snapshot.charges.size + snapshot.components.size +
            snapshot.discounts.sumOf { it.allocations.size.toLong() + it.eligiblePortions.size + 1 }
        require(records <= MAX_STORED_RECORDS) { "Stored pricing record count exceeds bound" }
        var characters = 4_096L
        fun add(overhead: Int, vararg rationals: ExactRational) {
            characters += overhead + rationals.sumOf { it.canonicalString().length }
            require(characters <= MAX_JSON_CHARACTERS) { "Stored pricing conservative character budget exceeded" }
        }
        add(0, snapshot.exactBeforeDiscountPayable, snapshot.exactPayable)
        snapshot.lines.forEach { add(4_096, it.exactOriginalItemNet, it.exactItemNet) }
        snapshot.charges.forEach { add(2_048 + 40 * it.eligiblePortionIds.size) }
        snapshot.components.forEach { add(2_048, it.exactBeforeDiscounts, it.exact) }
        snapshot.discounts.forEach { discount ->
            add(2_048 + 256 * discount.eligiblePortions.size, discount.exactEligiblePayable,
                discount.exactAvailablePayableReduction, discount.exactPayableReduction)
            discount.allocations.forEach { add(1_024, it.exactBefore, it.exactAfter, it.exactReduction, it.roundingDifference) }
        }
    }
}

@Serializable
private data class StoredSnapshot(
    val schemaVersion: Int,
    val snapshotId: DomainId,
    val revision: Revision,
    val currency: Currency,
    val profile: String,
    val taxPostingPolicy: TaxPostingPolicy,
    val promotionResolution: PromotionResolution,
    val discountLimits: DiscountLimits,
    val beforeDiscountPayable: Money,
    val payable: Money,
    val exactBeforeDiscountPayable: ExactRational,
    val exactPayable: ExactRational,
    val lines: List<PricedLineSnapshot>,
    val charges: List<StoredCharge>,
    val components: List<PostedPriceComponent>,
    val discounts: List<StoredDiscount>,
) {
    fun snapshot(): PricingSnapshot = PricingSnapshot(snapshotId, revision, currency, profile, taxPostingPolicy,
        promotionResolution, discountLimits, beforeDiscountPayable, payable, exactBeforeDiscountPayable,
        exactPayable, lines, charges.map { it.snapshot() }, components, discounts.map { it.snapshot() })

    companion object {
        fun from(snapshot: PricingSnapshot): StoredSnapshot = StoredSnapshot(StoredPricingSnapshotCodec.SCHEMA_VERSION,
            snapshot.snapshotId, snapshot.revision, snapshot.currency, snapshot.profile, snapshot.taxPostingPolicy,
            snapshot.promotionResolution, snapshot.discountLimits, snapshot.beforeDiscountPayable, snapshot.payable,
            snapshot.exactBeforeDiscountPayable, snapshot.exactPayable, snapshot.lines,
            snapshot.charges.map { StoredCharge.from(it) }, snapshot.components, snapshot.discounts.map { StoredDiscount.from(it) })
    }
}

@Serializable
private data class StoredCharge(
    val chargeId: DomainId,
    val revision: Revision,
    val kind: ChargeKind,
    val tax: TaxSnapshot,
    val refundTreatment: ChargeRefundTreatment,
    val fixedAmount: UnitPrice?,
    val inclusion: TaxInclusion,
    val percentage: PercentageRate?,
    val eligiblePortionIds: List<DomainId>,
) {
    fun snapshot(): ChargeRuleSnapshot = ChargeRuleSnapshot(chargeId, revision, kind, tax, refundTreatment,
        fixedAmount, inclusion, percentage, eligiblePortionIds)
    companion object {
        fun from(source: ChargeRuleSnapshot): StoredCharge = StoredCharge(source.chargeId, source.revision, source.kind,
            source.tax, source.refundTreatment, source.fixedAmount, source.inclusion, source.percentage, source.eligiblePortionIds)
    }
}

@Serializable
private data class StoredDiscount(
    val adjustmentId: DomainId,
    val ruleRevision: Revision,
    val stage: AdjustmentStage,
    val value: DiscountValue,
    val limits: DiscountLimits,
    val stacking: StackingPermission,
    val eligiblePortions: List<EligibleDiscountPortion>,
    val maximumPayableReduction: Money?,
    val capacityPayableReductionUpperBound: Money,
    val exactEligiblePayable: ExactRational,
    val exactAvailablePayableReduction: ExactRational,
    val exactPayableReduction: ExactRational,
    val advertisedPayableReduction: Money,
    val beforePayable: Money,
    val afterPayable: Money,
    val allocations: List<DiscountComponentAllocation>,
) {
    fun snapshot(): AppliedDiscountSnapshot = AppliedDiscountSnapshot(adjustmentId, ruleRevision, stage, value, limits,
        stacking, eligiblePortions, maximumPayableReduction, capacityPayableReductionUpperBound,
        exactEligiblePayable, exactAvailablePayableReduction, exactPayableReduction, advertisedPayableReduction,
        beforePayable, afterPayable, allocations)
    companion object {
        fun from(source: AppliedDiscountSnapshot): StoredDiscount = StoredDiscount(source.adjustmentId, source.ruleRevision,
            source.stage, source.value, source.limits, source.stacking, source.eligiblePortions,
            source.maximumPayableReduction, source.capacityPayableReductionUpperBound, source.exactEligiblePayable,
            source.exactAvailablePayableReduction, source.exactPayableReduction, source.advertisedPayableReduction,
            source.beforePayable, source.afterPayable, source.allocations)
    }
}
