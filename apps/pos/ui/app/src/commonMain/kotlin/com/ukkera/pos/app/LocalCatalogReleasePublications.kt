package com.ukkera.pos.app

import com.ukkera.pos.security.CompanyBusinessPublicationAction
import com.ukkera.pos.security.CompanyBusinessPublicationReference
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.SelfOwnedInstallationFailureV1
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/** Exact completed member coordinates, not a SKU, product or branch-cursor substitute. */
data class LocalCatalogReleaseMemberInput(
    val publicationId: DomainId,
    val contentDigest: PayloadDigest,
    val skuId: DomainId,
    val skuRevision: Revision,
    val productId: DomainId,
    val productRevision: Revision,
)

data class LocalCatalogReleasePublicationReference(
    val catalogId: DomainId,
    val publicationId: DomainId,
    val revision: Revision,
    val contentDigest: PayloadDigest,
)

/** Owned unsigned choices. The observed native sequence is retained, never recomputed on retry. */
class LocalCatalogReleasePublicationDraft(
    val publicationId: DomainId,
    val catalogId: DomainId,
    val revision: Revision,
    val previous: LocalCatalogReleasePublicationReference?,
    members: List<LocalCatalogReleaseMemberInput>,
) {
    private val ownedMembers: List<LocalCatalogReleaseMemberInput>
    val members: List<LocalCatalogReleaseMemberInput> get() = ownedMembers.toList()
    init {
        require(catalogId != publicationId)
        require(members.size in 1..64)
        ownedMembers = members.toList()
        require(ownedMembers.size in 1..64)
        require(ownedMembers.map { it.skuId }.distinct().size == ownedMembers.size)
        require(ownedMembers.map { it.publicationId }.distinct().size == ownedMembers.size)
        require(ownedMembers.none { it.publicationId == catalogId || it.publicationId == publicationId })
        require(previous == null && revision == Revision(0) || previous != null &&
            previous.catalogId == catalogId && previous.revision.value != Long.MAX_VALUE &&
            revision == previous.revision.next() && previous.publicationId != publicationId)
        require(ownedMembers.groupBy { it.productId }.values.all { product ->
            product.map { it.productRevision }.distinct().size == 1
        })
    }
    fun snapshot(): LocalCatalogReleasePublicationDraft =
        LocalCatalogReleasePublicationDraft(publicationId, catalogId, revision, previous, ownedMembers)
    override fun equals(other: Any?): Boolean = other is LocalCatalogReleasePublicationDraft &&
        publicationId == other.publicationId && catalogId == other.catalogId && revision == other.revision &&
        previous == other.previous && ownedMembers == other.ownedMembers
    override fun hashCode(): Int = listOf(publicationId, catalogId, revision, previous, ownedMembers).hashCode()
    override fun toString(): String = "LocalCatalogReleasePublicationDraft([REDACTED])"
}

/** Names are exact native descriptive values; the coordinates remain the selected identity. */
data class LocalCatalogReleaseMemberSource(
    val member: LocalCatalogReleaseMemberInput,
    val skuNames: LocalText,
    val productNames: LocalText,
    val skuCode: String,
    val active: Boolean,
)

data class LocalCatalogReleaseAuthoringSource(
    val catalogId: DomainId,
    val nextRevision: Revision?,
    val previous: LocalCatalogReleasePublicationReference?,
    val activeStorageRevision: Revision?,
    val active: LocalCatalogReleasePublicationReference?,
    val members: List<LocalCatalogReleaseMemberSource>,
) {
    init {
        require(members.size <= 256 && members.map { it.member.skuId }.distinct().size == members.size)
        require(previous == null || previous.catalogId == catalogId)
        require(active == null || active.catalogId == catalogId && activeStorageRevision != null)
    }
}

data class LocalCatalogReleasePublicationPreview(val companyId: DomainId,
    val definition: LocalCatalogReleasePublicationDraft)
class LocalCatalogReleasePublicationReview(val action: CompanyBusinessPublicationAction,
    val preview: LocalCatalogReleasePublicationPreview) {
    override fun toString(): String = "LocalCatalogReleasePublicationReview([REDACTED])"
}
data class LocalCatalogReleasePublicationCompleted(val originalActionId: DomainId,
    val preview: LocalCatalogReleasePublicationPreview, val rootFingerprint: String)
data class LocalCatalogReleasePublicationStatus(val references: List<CompanyBusinessPublicationReference>,
    val source: LocalCatalogReleaseAuthoringSource?, val mayPrepare: Boolean, val masterObservedAt: UtcInstant)

sealed interface LocalCatalogReleasePublicationStatusResult {
    data class Available(val status: LocalCatalogReleasePublicationStatus) : LocalCatalogReleasePublicationStatusResult
    data class Unavailable(val problem: LocalServiceProblem,
        val nativeReason: SelfOwnedInstallationFailureV1? = null) : LocalCatalogReleasePublicationStatusResult
}
sealed interface LocalCatalogReleasePublicationResult {
    data class Review(val review: LocalCatalogReleasePublicationReview) : LocalCatalogReleasePublicationResult
    data class Completed(val publication: LocalCatalogReleasePublicationCompleted) : LocalCatalogReleasePublicationResult
    data class Unavailable(val problem: LocalServiceProblem,
        val nativeReason: SelfOwnedInstallationFailureV1? = null) : LocalCatalogReleasePublicationResult
}

/** Native head signing and the branch employee's immutable activation request are separate. */
interface LocalCatalogReleasePublicationGateway {
    val configured: Boolean
    suspend fun status(catalogId: DomainId? = null): LocalCatalogReleasePublicationStatusResult
    suspend fun prepare(draft: LocalCatalogReleasePublicationDraft): LocalCatalogReleasePublicationResult
    suspend fun review(originalActionId: DomainId): LocalCatalogReleasePublicationResult
    /** The transient password buffer transfers on every outcome, including cancellation. */
    suspend fun approve(review: LocalCatalogReleasePublicationReview, headLogin: String,
        headSecret: CharArray): LocalCatalogReleasePublicationResult
    suspend fun completed(originalActionId: DomainId): LocalCatalogReleasePublicationResult
    /** Stage only; the existing workflow must separately review and submit the saved request. */
    suspend fun stageActivation(originalActionId: DomainId): LocalStageResult
}

object UnavailableLocalCatalogReleasePublication : LocalCatalogReleasePublicationGateway {
    override val configured: Boolean = false
    override suspend fun status(catalogId: DomainId?): LocalCatalogReleasePublicationStatusResult =
        LocalCatalogReleasePublicationStatusResult.Unavailable(LocalServiceProblem.NOT_CONFIGURED)
    override suspend fun prepare(draft: LocalCatalogReleasePublicationDraft): LocalCatalogReleasePublicationResult = unavailable()
    override suspend fun review(originalActionId: DomainId): LocalCatalogReleasePublicationResult = unavailable()
    override suspend fun approve(review: LocalCatalogReleasePublicationReview, headLogin: String,
        headSecret: CharArray): LocalCatalogReleasePublicationResult {
        headSecret.fill('\u0000')
        return unavailable()
    }
    override suspend fun completed(originalActionId: DomainId): LocalCatalogReleasePublicationResult = unavailable()
    override suspend fun stageActivation(originalActionId: DomainId): LocalStageResult =
        LocalStageResult.NotStaged(LocalServiceProblem.NOT_CONFIGURED)
    private fun unavailable(): LocalCatalogReleasePublicationResult =
        LocalCatalogReleasePublicationResult.Unavailable(LocalServiceProblem.NOT_CONFIGURED)
}
