package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Sidecars leave every signed SKU-v1 preimage and retained configuration record unchanged. */
public object LocalPricingProfile {
    public val COMMAND: CommandKey = CommandKey("local.catalog.pricing", 1)
    public val QUERY: ReadKey = ReadKey("local.catalog.pricing", 1)
    public const val COMPANY_HIGH_WATER: String = "local.catalog.price-high-water"
    public const val HISTORY_INDEX: String = "local.catalog.price-history-index"
    public const val HISTORY_HEAD: String = "local.catalog.price-history-head"
    public const val HISTORY_EVENT: String = "local.catalog.price-history-event"
    public const val POLICY_PUBLICATION: String = "local.catalog.price-policy-publication"
    public const val POLICY_CURRENT: String = "local.catalog.price-policy-current"
    public const val OVERRIDE_CURRENT: String = "local.catalog.branch-price-current"
    /** Immutable exact-version terms, independent of the greatest revision seen so far. */
    public const val VERSION_CLAIM: String = "local.catalog.price-version"
    public fun schema(kind: String): String = "$kind/v1"
    public fun key(kind: String, id: DomainId): AggregateKey = AggregateKey(kind, id)
    public val historyKinds: Set<String> = setOf(COMPANY_HIGH_WATER, HISTORY_INDEX, HISTORY_HEAD, HISTORY_EVENT)
    public val allKinds: Set<String> = historyKinds + setOf(POLICY_PUBLICATION, POLICY_CURRENT, OVERRIDE_CURRENT)
}

/**
 * One independently versioned company policy entry for the SKU/rule price context. The current
 * company-price revision remains a separate version domain. Its explicitly signed reference
 * quantity defines an absolute PUBLISHED-price deviation ceiling, not a basket-loss ceiling.
 * It is never supplied by the employee changing the price or inferred to make a grant pass.
 */
@Serializable
public data class LocalPricePolicyEntryV1(
    public val company: CompanyScope,
    public val entryId: DomainId,
    public val publicationId: DomainId,
    public val revision: Revision,
    public val skuId: DomainId,
    public val ruleId: DomainId,
    public val branchOverridesEnabled: Boolean,
    public val authorizationReferenceQuantity: Quantity,
    public val effectiveFrom: UtcInstant,
    public val effectiveUntil: UtcInstant?,
) {
    init {
        require(authorizationReferenceQuantity.scaled > 0)
        require(effectiveUntil == null || effectiveUntil > effectiveFrom)
    }
}

@Serializable
public data class LocalSignedPricePolicyEntryV1(
    public val definition: LocalPricePolicyEntryV1,
    public val signingKeyId: DomainId,
    public val signatureHex: String,
) { init { require(signatureHex.length == 128 && signatureHex.all { it in '0'..'9' || it in 'a'..'f' }) } }

@Serializable
public sealed interface LocalPricingRequestV1 {
    /** Receipt/staging does not replace active policy or imply an effective branch change. */
    @Serializable @SerialName("RECEIVE_POLICY")
    public data class ReceivePolicy(public val publication: LocalSignedPricePolicyEntryV1) : LocalPricingRequestV1

    /** Explicit current-writer activation. A future-dated received entry remains only received. */
    @Serializable @SerialName("ACTIVATE_POLICY")
    public data class ActivatePolicy(
        public val publicationId: DomainId,
        public val expectedPolicyStorageRevision: Revision?,
    ) : LocalPricingRequestV1

    /**
     * Immediate branch replacement, or inheritance/reset when unitPrice is null. Optional
     * expiry is explicit; future activation needs a separate staged command, not early removal
     * of a still-current price. Every stale relevant company/policy revision refuses mutation.
     */
    @Serializable @SerialName("CHANGE_BRANCH_PRICE")
    public data class ChangeBranchPrice(
        public val skuId: DomainId,
        public val ruleId: DomainId,
        public val expectedCompanyPriceRevision: Revision,
        public val expectedPolicyEntryRevision: Revision,
        public val expectedOverrideStorageRevision: Revision?,
        public val unitPrice: UnitPrice?,
        public val effectiveUntil: UtcInstant?,
        public val reasonCode: String,
    ) : LocalPricingRequestV1 {
        init {
            require(unitPrice == null || (unitPrice.currency == Currency.EGP && unitPrice.amount >= ExactDecimal.parse("0")))
            require(unitPrice != null || effectiveUntil == null)
            require(reasonCode.length in 1..96 && reasonCode.all { it.code in 0x21..0x7e && (it.isLetterOrDigit() || it in "._-") })
        }
    }

    /** One bounded, resumable publication step, never a caller-provided assertion of completeness. */
    @Serializable @SerialName("INDEX_RETAINED_COMPANY_HISTORY")
    public data class IndexRetainedCompanyHistory(public val expectedIndexStorageRevision: Revision?) : LocalPricingRequestV1
}

@Serializable
public sealed interface LocalPricingQueryV1 {
    @Serializable @SerialName("PREVIEW")
    public data class Preview(public val skuId: DomainId, public val channel: String, public val quantity: Quantity) : LocalPricingQueryV1 {
        init { require(channel in setOf("COUNTER", "DINE_IN", "TAKEAWAY", "DELIVERY") && quantity.scaled > 0) }
    }
    @Serializable @SerialName("CONTEXT")
    public data class Context(public val skuId: DomainId, public val ruleId: DomainId) : LocalPricingQueryV1
    @Serializable @SerialName("HISTORY")
    public data class History(public val skuId: DomainId, public val afterEventId: DomainId? = null, public val limit: Int = 20) : LocalPricingQueryV1 {
        init { require(limit in 1..20) }
    }
    @Serializable @SerialName("INDEX_STATUS")
    public data object IndexStatus : LocalPricingQueryV1
    @Serializable @SerialName("POLICY_PUBLICATION")
    public data class PolicyPublication(public val publicationId: DomainId) : LocalPricingQueryV1
    /** Historical company terms, separately permissioned from basic cashier price lookup. */
    @Serializable @SerialName("COMPANY_PUBLICATION")
    public data class CompanyPublication(public val publicationId: DomainId) : LocalPricingQueryV1
}

@Serializable
public data class LocalPricingResultV1(
    public val code: String,
    public val id: DomainId? = null,
    public val storageRevision: Revision? = null,
)

@Serializable
public data class LocalPricePolicyViewV1(
    public val entryId: DomainId, public val entryRevision: Revision, public val publicationId: DomainId,
    public val skuId: DomainId, public val ruleId: DomainId, public val branchOverridesEnabled: Boolean,
    public val authorizationReferenceQuantity: Quantity,
    public val effectiveFrom: UtcInstant, public val effectiveUntil: UtcInstant?,
    /** Includes RECEIVED_EXPIRED; receipt/activation status never implies central acceptance. */
    public val localStatus: String,
    public val activeStorageRevision: Revision?,
)

@Serializable
public data class LocalBranchPriceViewV1(
    public val eventId: DomainId, public val revision: Revision, public val storageRevision: Revision,
    public val basedOnCompanyPriceRevision: Revision, public val basedOnPolicyEntryRevision: Revision,
    public val unitPrice: UnitPrice?, public val effectiveFrom: UtcInstant, public val effectiveUntil: UtcInstant?,
    /** Actor/reason are confined to the independently authorized audit/history projection. */
    public val disposition: String,
)

@Serializable
public data class LocalPriceContextViewV1(
    public val skuId: DomainId, public val ruleId: DomainId, public val contextId: DomainId,
    public val companyPrice: LocalPriceV1?, public val highestCompanyPriceRevision: Revision?,
    public val policy: LocalPricePolicyViewV1?, public val branch: LocalBranchPriceViewV1?,
)

@Serializable
public data class LocalPricePreviewV1(
    public val skuId: DomainId, public val channel: String, public val quantity: Quantity,
    public val selected: LocalPriceContextViewV1?, public val effectiveUnitPrice: UnitPrice?,
    public val code: String,
)

@Serializable
public data class LocalPriceHistoryItemV1(
    public val eventId: DomainId, public val kind: String, public val skuId: DomainId, public val ruleId: DomainId?,
    public val companyPublicationId: DomainId?, public val policyPublicationId: DomainId?,
    public val branchUnitPrice: UnitPrice?, public val changedBy: DomainId, public val recordedAt: UtcInstant,
    public val reasonCode: String?,
)

@Serializable
public data class LocalCompanyPriceHistoryViewV1(
    public val skuId: DomainId, public val publicationId: DomainId,
    public val skuRevision: Revision, public val catalogRevision: Revision,
    public val prices: List<LocalPriceV1>,
    public val installedBy: DomainId, public val installedAt: UtcInstant,
)

@Serializable
public data class LocalPricingPageV1(
    public val context: LocalPriceContextViewV1? = null,
    public val preview: LocalPricePreviewV1? = null,
    public val policy: LocalPricePolicyViewV1? = null,
    public val history: List<LocalPriceHistoryItemV1> = emptyList(),
    public val nextEventId: DomainId? = null,
    public val indexStatus: String? = null,
    public val indexStorageRevision: Revision? = null,
    public val companyPublication: LocalCompanyPriceHistoryViewV1? = null,
)

public object LocalPricingCodec {
    public fun encode(request: LocalPricingRequestV1): StoredBytes = encodeValue(request, LocalPricingRequestV1.serializer())
    public fun decode(request: StoredBytes): LocalPricingRequestV1 = decodeValue(request, LocalPricingRequestV1.serializer())
    public fun query(request: LocalPricingQueryV1): StoredBytes = encodeValue(request, LocalPricingQueryV1.serializer())
    public fun decodeResult(result: StoredBytes): LocalPricingResultV1 = decodeValue(result, LocalPricingResultV1.serializer())
    public fun decodePage(result: StoredBytes): LocalPricingPageV1 = decodeValue(result, LocalPricingPageV1.serializer())
    public fun canonicalPolicy(definition: LocalPricePolicyEntryV1): ByteArray =
        ("ukkera.company-price-policy-entry/v1\n" + localConfigurationJson.encodeToString(LocalPricePolicyEntryV1.serializer(), definition)).encodeToByteArray()
}

internal fun priceContextId(company: CompanyScope, skuId: DomainId, ruleId: DomainId): DomainId = pricingIdentity(
    "local.price-context/v1", listOf(company.organizationId.value, company.companyId.value, skuId.value, ruleId.value))

internal fun pricingIdentity(domain: String, fields: List<String>): DomainId {
    val hex = OperationReservationBinding.frame(domain, fields).value.take(32)
    return DomainId("${hex.take(8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}")
}

internal fun pricingReject(code: String): ProposedOutcome.Reject = ProposedOutcome.Reject(code,
    encodeValue(LocalPricingResultV1(code), LocalPricingResultV1.serializer()))

internal fun pricingResult(code: String, id: DomainId?, storageRevision: Revision?): StoredBytes =
    encodeValue(LocalPricingResultV1(code, id, storageRevision), LocalPricingResultV1.serializer())
