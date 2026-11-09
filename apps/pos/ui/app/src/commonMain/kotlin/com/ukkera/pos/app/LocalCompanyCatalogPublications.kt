package com.ukkera.pos.app

import com.ukkera.pos.catalog.CatalogAttribute
import com.ukkera.pos.catalog.CatalogStatus
import com.ukkera.pos.catalog.VariantAxis
import com.ukkera.pos.catalog.VariantValue
import com.ukkera.pos.catalog.CatalogUnit
import com.ukkera.pos.catalog.ProductUnitConversion
import com.ukkera.pos.catalog.UnitDimension
import com.ukkera.pos.security.CompanyBusinessPublicationAction
import com.ukkera.pos.security.CompanyBusinessPublicationReference
import com.ukkera.pos.security.SelfOwnedInstallationFailureV1
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.ExactDecimal
import com.ukkera.pos.types.PercentageRate
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitPrice
import com.ukkera.pos.types.UtcInstant
import kotlin.text.CharacterCodingException

enum class LocalCompanyPriceTaxInclusion { INCLUDED, EXCLUDED }
enum class LocalCompanyStockTracking { QUANTITY, LOT, SERIAL }
enum class LocalCompanySkuFulfillment { RETAIL, MADE_TO_ORDER }

/** Owner-entered commercial promise. Unspecified periods stay null; literal text supplies no rules. */
data class LocalCompanyReturnPromiseDraft(
    val policyId: DomainId, val policyRevision: Revision, val categoryCode: String,
    val englishTerms: String, val arabicTerms: String,
    val changeOfMindReceiptWindowDays: Int? = null,
    val defectiveReceiptWindowDays: Int? = null,
    val defectiveResolutionDays: Int? = null,
) {
    init {
        require(categoryCode.length in 1..128 && categoryCode.all { it.isLetterOrDigit() || it in "._-" })
        require(englishTerms.isNotBlank() || arabicTerms.isNotBlank())
        require(englishTerms.length <= 4_096 && arabicTerms.length <= 4_096)
        require((englishTerms + arabicTerms).none { (it.code < 0x20 || it.code in 0x7f..0x9f) && it != '\n' && it != '\t' })
        // Validate each literal independently; a surrogate pair cannot bridge languages.
        listOf(englishTerms, arabicTerms).forEach { it.encodeToByteArray(throwOnInvalidSequence = true).fill(0) }
        require(changeOfMindReceiptWindowDays == null || changeOfMindReceiptWindowDays >= 14)
        require((defectiveReceiptWindowDays == null) == (defectiveResolutionDays == null))
        require(defectiveReceiptWindowDays == null || defectiveReceiptWindowDays >= 30)
        require(defectiveResolutionDays == null || defectiveResolutionDays in 1..7)
    }
}

/** New capability drafts. They cannot reinterpret an old retail proposal or edit an existing SKU. */
data class LocalCompanyIngredientSkuDraft(val sku: LocalCompanySkuDraft) {
    init { require(sku.returnPromise == null && sku.canPurchase && sku.tracking in setOf(LocalCompanyStockTracking.QUANTITY, LocalCompanyStockTracking.LOT)) }
}
data class LocalCompanyFoodOutputSkuDraft(val sku: LocalCompanySkuDraft) {
    init { require(sku.returnPromise == null && !sku.canPurchase && sku.tracking == LocalCompanyStockTracking.QUANTITY && !sku.requiresExpiry) }
}

internal object LocalCompanyProductDraftProfile {
    const val MAX_AXIS_VALUES: Int = 128
    const val MAX_ALLOWED_VARIANT_COMBINATIONS: Int = 256
}

/** Explicit shared product metadata. Revisions are assigned from native completed history, not entered here. */
data class LocalCompanyProductDraft(
    val englishName: String, val arabicName: String,
    val categoryIds: List<DomainId> = emptyList(),
    val variantAxes: List<VariantAxis> = emptyList(),
    val attributes: List<CatalogAttribute> = emptyList(),
    val status: CatalogStatus = CatalogStatus.ACTIVE,
    /** Null retains the prior representation; a present matrix limits every SKU selection. */
    val allowedVariantCombinations: List<List<VariantValue>>? = null,
) {
    init {
        require(englishName.length <= 160 && arabicName.length <= 160 &&
            (englishName.isNotBlank() || arabicName.isNotBlank()))
        require((englishName + arabicName).none { it.code < 0x20 || it.code == 0x7f })
        listOf(englishName, arabicName).forEach { it.encodeToByteArray(throwOnInvalidSequence = true).fill(0) }
        require(categoryIds.size <= 32 && categoryIds.distinct().size == categoryIds.size)
        require(variantAxes.size <= 16 && variantAxes.map { it.id }.distinct().size == variantAxes.size &&
            variantAxes.map { it.code.normalizedKey }.distinct().size == variantAxes.size)
        require(variantAxes.all { axis -> axis.values.size in 1..LocalCompanyProductDraftProfile.MAX_AXIS_VALUES &&
            axis.values.map { it.normalizedKey }.distinct().size == axis.values.size })
        require(attributes.size <= 32 && attributes.map { it.key.normalizedKey }.distinct().size == attributes.size)
        allowedVariantCombinations?.let { rows ->
            require(rows.size in 1..LocalCompanyProductDraftProfile.MAX_ALLOWED_VARIANT_COMBINATIONS)
            require(rows.all { it.size <= 16 && it.map { value -> value.axisId }.distinct().size == it.size })
            require(rows.all { row ->
                row.map { it.axisId }.toSet() == variantAxes.map { it.id }.toSet() && row.all { value ->
                    variantAxes.single { it.id == value.axisId }.values.contains(value.value)
                }
            })
            require(rows.map { row -> row.sortedBy { it.axisId.value } }.distinct().size == rows.size)
        }
    }

    fun snapshot(): LocalCompanyProductDraft {
        require(categoryIds.size <= 32 && variantAxes.size <= 16 && attributes.size <= 32)
        require(variantAxes.all { it.values.size in 1..LocalCompanyProductDraftProfile.MAX_AXIS_VALUES })
        allowedVariantCombinations?.let { rows ->
            require(rows.size in 1..LocalCompanyProductDraftProfile.MAX_ALLOWED_VARIANT_COMBINATIONS && rows.all { it.size <= 16 })
        }
        return copy(categoryIds = categoryIds.toList(),
            variantAxes = variantAxes.map { VariantAxis(it.id, it.code, it.values.toList()) }, attributes = attributes.toList(),
            allowedVariantCombinations = allowedVariantCombinations?.map { it.toList() })
    }
}

data class LocalCompanyProductPreview(val revision: Revision, val definition: LocalCompanyProductDraft)

/** NEW stocked-item proposal. Existing published stock identities cannot be edited here. */
data class LocalCompanySkuDraft(
    val publicationId: DomainId,
    val skuId: DomainId,
    val productId: DomainId,
    val baseUnitId: DomainId,
    val priceRuleId: DomainId,
    val taxPolicyId: DomainId,
    val englishName: String,
    val arabicName: String,
    val code: String,
    val barcodes: List<String>,
    val unitPriceEgp: String,
    val taxCategory: String,
    val taxPercent: String,
    val taxInclusion: LocalCompanyPriceTaxInclusion,
    val canPurchase: Boolean,
    val quantityScale: Int = 0,
    val tracking: LocalCompanyStockTracking = LocalCompanyStockTracking.QUANTITY,
    val requiresExpiry: Boolean = false,
    val returnPromise: LocalCompanyReturnPromiseDraft? = null,
    val unitDefinitions: List<CatalogUnit>? = null,
    val conversions: List<ProductUnitConversion> = emptyList(),
    /** Null reuses the exact latest product body; a genuinely new simple product uses the SKU names. */
    val productDefinition: LocalCompanyProductDraft? = null,
    val variantValues: List<VariantValue> = emptyList(),
) {
    init {
        require(variantValues.size <= 16 && variantValues.map { it.axisId }.distinct().size == variantValues.size)
        productDefinition?.let { product ->
            require(variantValues.map { it.axisId }.toSet() == product.variantAxes.map { it.id }.toSet())
            require(variantValues.all { choice -> product.variantAxes.single { it.id == choice.axisId }.values.any {
                it.original == choice.value.original
            } })
            require(product.allowedVariantCombinations?.any { row ->
                row.associate { it.axisId to it.value } == variantValues.associate { it.axisId to it.value }
            } != false)
        }
        require(englishName.length <= 160 && arabicName.length <= 160 && code.length <= 96)
        require(barcodes.size <= 32 && barcodes.all { it.length <= 128 })
        require(unitPriceEgp.length <= 64 && taxCategory.length <= 128 && taxPercent.length <= 64)
        require(quantityScale in 0..6)
        require(tracking != LocalCompanyStockTracking.SERIAL || quantityScale == 0)
        require(!requiresExpiry || tracking == LocalCompanyStockTracking.LOT)
        require(conversions.size <= 32 && conversions.map { it.id }.distinct().size == conversions.size)
        require(conversions.all { it.productId == productId && it.targetUnitId == baseUnitId &&
            it.sourceUnitId != baseUnitId && it.revision == Revision(0) && it.massVolumeEvidenceId == null })
        if (unitDefinitions == null) require(conversions.isEmpty())
        else {
            require(unitDefinitions.size in 1..33 && unitDefinitions.map { it.id }.distinct().size == unitDefinitions.size &&
                unitDefinitions.map { it.code.normalizedKey }.distinct().size == unitDefinitions.size)
            val units = unitDefinitions.associateBy { it.id }
            val base = requireNotNull(units[baseUnitId])
            require(quantityScale <= base.maximumScale)
            require(tracking != LocalCompanyStockTracking.SERIAL || base.dimension == UnitDimension.COUNT)
            require(conversions.all { units[it.sourceUnitId]?.dimension == base.dimension })
        }
    }

    /** Deterministic publication input checks, before a UI freezes a retention request.
     * This does not establish native identity, catalog uniqueness or activation permission. */
    fun validatePublicationInput() {
        require(listOf(publicationId, skuId, productId, baseUnitId, priceRuleId, taxPolicyId).distinct().size == 6)
        require(englishName.isNotBlank() || arabicName.isNotBlank())
        require((englishName + arabicName).none { it.code < 0x20 || it.code == 0x7f })
        require(code.isNotEmpty() && code.all { it.code in 0x21..0x7e })
        require(barcodes.distinct().size == barcodes.size && barcodes.all {
            it.isNotEmpty() && it.all { c -> c.code in 0x21..0x7e }
        })
        require(taxCategory.isNotBlank() && taxCategory.none { it.code < 0x20 || it.code == 0x7f })
        try {
            listOf(englishName, arabicName, taxCategory).forEach {
                it.encodeToByteArray(throwOnInvalidSequence = true).fill(0)
            }
        } catch (malformed: CharacterCodingException) {
            throw IllegalArgumentException("Malformed publication text", malformed)
        }
        require(UnitPrice.parse(Currency.EGP, unitPriceEgp).amount >= ExactDecimal.parse("0"))
        PercentageRate.parse(taxPercent)
    }
}

/** Descriptive projection of the exact retained canonical content. No signature/grant is exposed. */
data class LocalCompanySkuPreview(
    val publicationId: DomainId,
    val skuId: DomainId,
    val productId: DomainId,
    val baseUnitId: DomainId,
    val catalogRevision: Revision,
    val skuRevision: Revision,
    val englishName: String,
    val arabicName: String,
    val code: String,
    val barcodes: List<String>,
    val unitPriceEgp: String,
    val taxCategory: String,
    val taxPercent: String,
    val taxInclusion: LocalCompanyPriceTaxInclusion,
    val taxPolicyId: DomainId,
    val priceRuleId: DomainId,
    val effectiveFrom: UtcInstant,
    val canPurchase: Boolean,
    val quantityScale: Int,
    val tracking: LocalCompanyStockTracking,
    val requiresExpiry: Boolean,
    val fulfillment: LocalCompanySkuFulfillment = LocalCompanySkuFulfillment.RETAIL,
    val canSell: Boolean = true,
    val returnPromise: LocalCompanyReturnPromiseDraft? = null,
    val unitDefinitions: List<CatalogUnit>? = null,
    val conversions: List<ProductUnitConversion> = emptyList(),
    val productDefinition: LocalCompanyProductPreview? = null,
    val variantValues: List<VariantValue>? = null,
)

/** Native action remains owner-bound. The adapter rederives/checks this projection before approval. */
class LocalCompanyCatalogReview(
    val action: CompanyBusinessPublicationAction,
    val preview: LocalCompanySkuPreview,
) {
    override fun toString(): String = "LocalCompanyCatalogReview([REDACTED])"
}

data class LocalCompanyCatalogCompleted(
    val originalActionId: DomainId,
    val preview: LocalCompanySkuPreview,
    val rootFingerprint: String,
)

data class LocalCompanyCatalogStatus(
    val references: List<CompanyBusinessPublicationReference>,
    /** Conservative branch activation hint, NOT a company-signing grant. */
    val mayPrepareNewItem: Boolean,
    val masterObservedAt: UtcInstant,
    val catalogCut: Revision?,
) { init { require(references.size <= 256) } }

sealed interface LocalCompanyCatalogStatusResult {
    data class Available(val status: LocalCompanyCatalogStatus) : LocalCompanyCatalogStatusResult
    data class Unavailable(val problem: LocalServiceProblem, val nativeReason: SelfOwnedInstallationFailureV1? = null) : LocalCompanyCatalogStatusResult
}

sealed interface LocalCompanyCatalogReviewResult {
    data class Review(val review: LocalCompanyCatalogReview) : LocalCompanyCatalogReviewResult
    data class Completed(val publication: LocalCompanyCatalogCompleted) : LocalCompanyCatalogReviewResult
    data class Unavailable(val problem: LocalServiceProblem, val nativeReason: SelfOwnedInstallationFailureV1? = null) : LocalCompanyCatalogReviewResult
}

sealed interface LocalCompanyCatalogApprovalResult {
    data class Completed(val publication: LocalCompanyCatalogCompleted) : LocalCompanyCatalogApprovalResult
    /** No failure is authoritative evidence that signing did not happen; retain/read original ID. */
    data class Unavailable(val problem: LocalServiceProblem, val nativeReason: SelfOwnedInstallationFailureV1? = null) : LocalCompanyCatalogApprovalResult
}

/** Company signing and employee branch activation are deliberately separate calls/authorities. */
interface LocalCompanyCatalogGateway {
    val configured: Boolean
    /** Same original native owner/session/journal; an absent implementation remains unavailable. */
    val foodConfiguration: LocalFoodConfigurationGateway get() = UnavailableLocalFoodConfiguration
    val returnRights: LocalReturnRightsPublicationGateway get() = UnavailableLocalReturnRightsPublication
    val pricePolicies: LocalPricePolicyPublicationGateway get() = UnavailableLocalPricePolicyPublication
    val releases: LocalCatalogReleasePublicationGateway get() = UnavailableLocalCatalogReleasePublication
    val serialRegistrations: LocalSerialRegistrationPublicationGateway get() = UnavailableLocalSerialRegistrationPublication
    suspend fun prepareIngredient(draft: LocalCompanyIngredientSkuDraft): LocalCompanyCatalogReviewResult =
        LocalCompanyCatalogReviewResult.Unavailable(LocalServiceProblem.NOT_IMPLEMENTED)
    suspend fun prepareFoodOutput(draft: LocalCompanyFoodOutputSkuDraft): LocalCompanyCatalogReviewResult =
        LocalCompanyCatalogReviewResult.Unavailable(LocalServiceProblem.NOT_IMPLEMENTED)
    suspend fun status(): LocalCompanyCatalogStatusResult
    suspend fun prepare(draft: LocalCompanySkuDraft): LocalCompanyCatalogReviewResult
    suspend fun review(originalActionId: DomainId): LocalCompanyCatalogReviewResult
    /** The password is transient and transfers on all outcomes; never store it in a draft/journal. */
    suspend fun approve(review: LocalCompanyCatalogReview, headLogin: String, headSecret: CharArray): LocalCompanyCatalogApprovalResult
    suspend fun completed(originalActionId: DomainId): LocalCompanyCatalogApprovalResult
    /** Native completed output only; immutable activation request is retained in the employee journal. */
    suspend fun stageActivation(originalActionId: DomainId): LocalStageResult
}

object UnavailableLocalCompanyCatalog : LocalCompanyCatalogGateway {
    override val configured: Boolean = false
    override suspend fun status(): LocalCompanyCatalogStatusResult = LocalCompanyCatalogStatusResult.Unavailable(LocalServiceProblem.NOT_CONFIGURED)
    override suspend fun prepare(draft: LocalCompanySkuDraft): LocalCompanyCatalogReviewResult =
        LocalCompanyCatalogReviewResult.Unavailable(LocalServiceProblem.NOT_CONFIGURED)
    override suspend fun review(originalActionId: DomainId): LocalCompanyCatalogReviewResult =
        LocalCompanyCatalogReviewResult.Unavailable(LocalServiceProblem.NOT_CONFIGURED)
    override suspend fun approve(review: LocalCompanyCatalogReview, headLogin: String, headSecret: CharArray): LocalCompanyCatalogApprovalResult {
        headSecret.fill('\u0000')
        return LocalCompanyCatalogApprovalResult.Unavailable(LocalServiceProblem.NOT_CONFIGURED)
    }
    override suspend fun completed(originalActionId: DomainId): LocalCompanyCatalogApprovalResult =
        LocalCompanyCatalogApprovalResult.Unavailable(LocalServiceProblem.NOT_CONFIGURED)
    override suspend fun stageActivation(originalActionId: DomainId): LocalStageResult = LocalStageResult.NotStaged(LocalServiceProblem.NOT_CONFIGURED)
}
