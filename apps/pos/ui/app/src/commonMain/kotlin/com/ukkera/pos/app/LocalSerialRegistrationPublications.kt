package com.ukkera.pos.app

import com.ukkera.pos.security.CompanyBusinessPublicationAction
import com.ukkera.pos.security.CompanyBusinessPublicationReference
import com.ukkera.pos.security.LocalPublishedSerialAuthorityV1
import com.ukkera.pos.security.LocalPublishedSerialRegistrationV1
import com.ukkera.pos.security.LocalSerialRegistrationReferenceV1
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.SelfOwnedInstallationFailureV1
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/** User choices only. The gateway obtains the actual original allocation/issuer itself. */
data class LocalSerialRegistrationDraft(
    val publicationId: DomainId,
    val registrationId: DomainId,
    val revision: Revision,
    val previous: LocalSerialRegistrationReferenceV1?,
    val serialId: DomainId,
    val serialCode: String,
    val skuId: DomainId,
    val skuPublicationId: DomainId,
    val skuPublicationDigest: PayloadDigest,
    val validFrom: UtcInstant,
    val validUntil: UtcInstant?,
)

data class LocalSerialRegistrationAuthoringSource(
    val registrationId: DomainId,
    val skuId: DomainId,
    val skuPublicationId: DomainId,
    val skuPublicationDigest: PayloadDigest,
    val previous: LocalSerialRegistrationReferenceV1?,
    val previousDefinition: LocalPublishedSerialRegistrationV1?,
    val nextRevision: Revision?,
)

/** Retained activation is not proof of current time, SKU or stock eligibility. */
data class LocalSerialPublicationStatus(
    val references: List<CompanyBusinessPublicationReference>,
    val source: LocalSerialRegistrationAuthoringSource?,
)

sealed interface LocalSerialPublicationPreview {
    data class Authority(val definition: LocalPublishedSerialAuthorityV1) : LocalSerialPublicationPreview
    data class Registration(val definition: LocalPublishedSerialRegistrationV1) : LocalSerialPublicationPreview
}

class LocalSerialPublicationReview(val action: CompanyBusinessPublicationAction,
    val preview: LocalSerialPublicationPreview) {
    override fun toString(): String = "LocalSerialPublicationReview([REDACTED])"
}

data class LocalSerialPublicationCompleted(val originalActionId: DomainId,
    val preview: LocalSerialPublicationPreview)

sealed interface LocalSerialPublicationResult {
    data class Review(val review: LocalSerialPublicationReview) : LocalSerialPublicationResult
    data class Completed(val publication: LocalSerialPublicationCompleted) : LocalSerialPublicationResult
    data class Unavailable(val problem: LocalServiceProblem,
        val nativeReason: SelfOwnedInstallationFailureV1? = null,
        /** Only preparation may certify that no original exists and no native prepare was attempted. */
        val unsignedInputMayChange: Boolean = false) : LocalSerialPublicationResult
}

sealed interface LocalSerialPublicationStatusResult {
    data class Available(val status: LocalSerialPublicationStatus) : LocalSerialPublicationStatusResult
    data class Unavailable(val problem: LocalServiceProblem) : LocalSerialPublicationStatusResult
}

/** Native signing, saved activation and the actual protected business submit remain separate. */
interface LocalSerialRegistrationPublicationGateway {
    val configured: Boolean
    suspend fun status(registrationId: DomainId? = null, skuId: DomainId? = null): LocalSerialPublicationStatusResult
    suspend fun prepareAuthority(): LocalSerialPublicationResult
    suspend fun prepare(draft: LocalSerialRegistrationDraft): LocalSerialPublicationResult
    suspend fun review(originalActionId: DomainId): LocalSerialPublicationResult
    /** Always consumes/clears the transient buffer, including cancellation and refusal. */
    suspend fun approve(review: LocalSerialPublicationReview, login: String, secret: CharArray): LocalSerialPublicationResult
    suspend fun completed(originalActionId: DomainId): LocalSerialPublicationResult
    suspend fun stageActivation(originalActionId: DomainId): LocalStageResult
}

object UnavailableLocalSerialRegistrationPublication : LocalSerialRegistrationPublicationGateway {
    override val configured = false
    override suspend fun status(registrationId: DomainId?, skuId: DomainId?) =
        LocalSerialPublicationStatusResult.Unavailable(LocalServiceProblem.NOT_CONFIGURED)
    override suspend fun prepareAuthority() = unavailable()
    override suspend fun prepare(draft: LocalSerialRegistrationDraft) = unavailable()
    override suspend fun review(originalActionId: DomainId) = unavailable()
    override suspend fun approve(review: LocalSerialPublicationReview, login: String, secret: CharArray): LocalSerialPublicationResult {
        secret.fill('\u0000')
        return unavailable()
    }
    override suspend fun completed(originalActionId: DomainId) = unavailable()
    override suspend fun stageActivation(originalActionId: DomainId) = LocalStageResult.NotStaged(LocalServiceProblem.NOT_CONFIGURED)
    private fun unavailable() = LocalSerialPublicationResult.Unavailable(LocalServiceProblem.NOT_CONFIGURED)
}
