package com.ukkera.pos.app

import com.ukkera.pos.security.CompanyBusinessPublicationAction
import com.ukkera.pos.security.CompanyBusinessPublicationReference
import com.ukkera.pos.security.SelfOwnedInstallationFailureV1
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/** Unsigned exact choices; company identity and signing authority come from the native owner. */
data class LocalPricePolicyPublicationDraft(
    val publicationId: DomainId,
    val entryId: DomainId,
    val revision: Revision,
    val skuId: DomainId,
    val ruleId: DomainId,
    val branchOverridesEnabled: Boolean,
    val authorizationReferenceQuantity: Quantity,
    val effectiveFrom: UtcInstant,
    val effectiveUntil: UtcInstant?,
) {
    init {
        require(authorizationReferenceQuantity.scaled > 0)
        require(effectiveUntil == null || effectiveUntil > effectiveFrom)
    }
}

data class LocalPricePolicyPublicationPreview(val companyId: DomainId, val definition: LocalPricePolicyPublicationDraft)
class LocalPricePolicyPublicationReview(val action: CompanyBusinessPublicationAction,
    val preview: LocalPricePolicyPublicationPreview) {
    override fun toString(): String = "LocalPricePolicyPublicationReview([REDACTED])"
}
data class LocalPricePolicyPublicationCompleted(val originalActionId: DomainId,
    val preview: LocalPricePolicyPublicationPreview, val rootFingerprint: String)
data class LocalPricePolicyPublicationStatus(val references: List<CompanyBusinessPublicationReference>,
    val mayPrepare: Boolean, val masterObservedAt: UtcInstant)

sealed interface LocalPricePolicyPublicationStatusResult {
    data class Available(val status: LocalPricePolicyPublicationStatus) : LocalPricePolicyPublicationStatusResult
    data class Unavailable(val problem: LocalServiceProblem,
        val nativeReason: SelfOwnedInstallationFailureV1? = null) : LocalPricePolicyPublicationStatusResult
}
sealed interface LocalPricePolicyPublicationResult {
    data class Review(val review: LocalPricePolicyPublicationReview) : LocalPricePolicyPublicationResult
    data class Completed(val publication: LocalPricePolicyPublicationCompleted) : LocalPricePolicyPublicationResult
    data class Unavailable(val problem: LocalServiceProblem,
        val nativeReason: SelfOwnedInstallationFailureV1? = null) : LocalPricePolicyPublicationResult
}

/** Signing, receipt/staging and effective branch activation are three distinct operations. */
interface LocalPricePolicyPublicationGateway {
    val configured: Boolean
    suspend fun status(): LocalPricePolicyPublicationStatusResult
    suspend fun prepare(draft: LocalPricePolicyPublicationDraft): LocalPricePolicyPublicationResult
    suspend fun review(originalActionId: DomainId): LocalPricePolicyPublicationResult
    /** Transfer the transient password buffer on every outcome; never persist head credentials. */
    suspend fun approve(review: LocalPricePolicyPublicationReview, headLogin: String,
        headSecret: CharArray): LocalPricePolicyPublicationResult
    suspend fun completed(originalActionId: DomainId): LocalPricePolicyPublicationResult
    /** Stage only the exact native completed publication, not an effective price change. */
    suspend fun stageReceipt(originalActionId: DomainId): LocalStageResult
}
object UnavailableLocalPricePolicyPublication : LocalPricePolicyPublicationGateway {
    override val configured: Boolean = false
    override suspend fun status(): LocalPricePolicyPublicationStatusResult =
        LocalPricePolicyPublicationStatusResult.Unavailable(LocalServiceProblem.NOT_CONFIGURED)
    override suspend fun prepare(draft: LocalPricePolicyPublicationDraft): LocalPricePolicyPublicationResult = unavailable()
    override suspend fun review(originalActionId: DomainId): LocalPricePolicyPublicationResult = unavailable()
    override suspend fun approve(review: LocalPricePolicyPublicationReview, headLogin: String,
        headSecret: CharArray): LocalPricePolicyPublicationResult {
        headSecret.fill('\u0000')
        return unavailable()
    }
    override suspend fun completed(originalActionId: DomainId): LocalPricePolicyPublicationResult = unavailable()
    override suspend fun stageReceipt(originalActionId: DomainId): LocalStageResult =
        LocalStageResult.NotStaged(LocalServiceProblem.NOT_CONFIGURED)
    private fun unavailable(): LocalPricePolicyPublicationResult =
        LocalPricePolicyPublicationResult.Unavailable(LocalServiceProblem.NOT_CONFIGURED)
}
