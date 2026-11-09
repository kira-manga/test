package com.ukkera.pos.app

import com.ukkera.pos.security.CompanyBusinessPublicationAction
import com.ukkera.pos.security.CompanyBusinessPublicationReference
import com.ukkera.pos.security.SelfOwnedInstallationFailureV1
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/** UI choices only; the shared documentary publication model validates their business meaning. */
enum class LocalReturnRightsPublicationReason {
    CHANGE_OF_MIND, WRONG_SUPPLIED_ITEM, DEFECTIVE_OR_DAMAGED,
    MERCHANT_PREPARATION_ERROR, QUALITY_COMPLAINT, FAILED_DELIVERY, UNPERFORMED_SERVICE,
}
enum class LocalReturnRightsPublicationTreatment { RECEIPT_WINDOW, CATEGORY_EXCEPTION_REVIEW, EVIDENCE_OR_POLICY_REVIEW }
enum class LocalReturnRightsPublicationException {
    PERISHABLE_OR_PREPARED_FOOD, CORRECTLY_SUPPLIED_CUSTOMER_SPECIFIED_GOODS,
    VERIFIED_OPENED_HYGIENE_SENSITIVE_PACKAGING,
}

data class LocalReturnRightsPublicationRule(
    val categoryCode: String,
    val reason: LocalReturnRightsPublicationReason,
    val treatment: LocalReturnRightsPublicationTreatment,
    val receiptWindowDays: Int? = null,
    val coveredResolutionDays: Int? = null,
    val exceptionBasis: LocalReturnRightsPublicationException? = null,
)

/** Unsigned literal proposal. Company scope and signing authority come from the real native owner. */
class LocalReturnRightsPublicationDraft(
    val publicationId: DomainId,
    val policyId: DomainId,
    val companyRevision: Revision,
    val jurisdictionCode: String,
    val legalSourceUri: String,
    val legalSourceAccessDate: String,
    val continuingRights: LocalText,
    rules: List<LocalReturnRightsPublicationRule>,
) {
    private val ruleValues = rules.toList()
    val rules: List<LocalReturnRightsPublicationRule> get() = ruleValues.toList()
    init { require(ruleValues.size in 1..128) }
    override fun toString(): String = "LocalReturnRightsPublicationDraft([REDACTED])"
}

/** The exact retained native definition, not the currently active policy or a qualification decision. */
data class LocalReturnRightsPublicationPreview(val companyId: DomainId, val definition: LocalReturnRightsPublicationDraft)

class LocalReturnRightsPublicationReview(
    val action: CompanyBusinessPublicationAction,
    val preview: LocalReturnRightsPublicationPreview,
) {
    override fun toString(): String = "LocalReturnRightsPublicationReview([REDACTED])"
}

data class LocalReturnRightsPublicationCompleted(
    val originalActionId: DomainId,
    val preview: LocalReturnRightsPublicationPreview,
    val rootFingerprint: String,
)

data class LocalReturnRightsPublicationStatus(
    val references: List<CompanyBusinessPublicationReference>,
    /** Advisory branch hint only; neither head-signing nor Master activation authority. */
    val mayPrepare: Boolean,
    val masterObservedAt: UtcInstant,
) { init { require(references.size <= 256) } }

sealed interface LocalReturnRightsPublicationStatusResult {
    data class Available(val status: LocalReturnRightsPublicationStatus) : LocalReturnRightsPublicationStatusResult
    data class Unavailable(val problem: LocalServiceProblem,
        val nativeReason: SelfOwnedInstallationFailureV1? = null) : LocalReturnRightsPublicationStatusResult
}
sealed interface LocalReturnRightsPublicationResult {
    data class Review(val review: LocalReturnRightsPublicationReview) : LocalReturnRightsPublicationResult
    data class Completed(val publication: LocalReturnRightsPublicationCompleted) : LocalReturnRightsPublicationResult
    /** Approval/staging failures are not proof that the original native action did not complete. */
    data class Unavailable(val problem: LocalServiceProblem,
        val nativeReason: SelfOwnedInstallationFailureV1? = null) : LocalReturnRightsPublicationResult
}

/** Company-head signing and current employee branch activation are deliberately separate. */
interface LocalReturnRightsPublicationGateway {
    val configured: Boolean
    suspend fun status(): LocalReturnRightsPublicationStatusResult
    suspend fun prepare(draft: LocalReturnRightsPublicationDraft): LocalReturnRightsPublicationResult
    suspend fun review(originalActionId: DomainId): LocalReturnRightsPublicationResult
    /** Transfers the transient buffer on every outcome. Never journal or retain head credentials. */
    suspend fun approve(review: LocalReturnRightsPublicationReview, headLogin: String,
        headSecret: CharArray): LocalReturnRightsPublicationResult
    suspend fun completed(originalActionId: DomainId): LocalReturnRightsPublicationResult
    /** Only a genuine native completed output may stage; existing original CAS is never refreshed. */
    suspend fun stageActivation(originalActionId: DomainId): LocalStageResult
}

object UnavailableLocalReturnRightsPublication : LocalReturnRightsPublicationGateway {
    override val configured: Boolean = false
    override suspend fun status(): LocalReturnRightsPublicationStatusResult =
        LocalReturnRightsPublicationStatusResult.Unavailable(LocalServiceProblem.NOT_CONFIGURED)
    override suspend fun prepare(draft: LocalReturnRightsPublicationDraft): LocalReturnRightsPublicationResult = unavailable()
    override suspend fun review(originalActionId: DomainId): LocalReturnRightsPublicationResult = unavailable()
    override suspend fun approve(review: LocalReturnRightsPublicationReview, headLogin: String,
        headSecret: CharArray): LocalReturnRightsPublicationResult {
        headSecret.fill('\u0000')
        return unavailable()
    }
    override suspend fun completed(originalActionId: DomainId): LocalReturnRightsPublicationResult = unavailable()
    override suspend fun stageActivation(originalActionId: DomainId): LocalStageResult =
        LocalStageResult.NotStaged(LocalServiceProblem.NOT_CONFIGURED)
    private fun unavailable(): LocalReturnRightsPublicationResult =
        LocalReturnRightsPublicationResult.Unavailable(LocalServiceProblem.NOT_CONFIGURED)
}
