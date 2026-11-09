package com.ukkera.pos.catalog.store

import com.ukkera.pos.sales.store.LocalSalesActivity
import com.ukkera.pos.security.CompanyBusinessPublicationKind
import com.ukkera.pos.security.CompanyBusinessPublicationProfileV1
import com.ukkera.pos.security.CompanyScope
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Company-owned activity content. Current branch activation and permissions are separate. */
@Serializable(with = LocalCompanyActivityProfileSerializerV1::class)
public class LocalPublishedCompanyActivityProfileV1(
    public val company: CompanyScope,
    public val publicationId: DomainId,
    public val profileId: DomainId,
    public val revision: Revision,
    activities: Set<LocalSalesActivity>,
) {
    private val activityValues: Set<LocalSalesActivity> = activities.toList().also(::requireCompanyActivitiesV1).toSet()

    /** A detached set on every read; callers never receive the model's backing collection. */
    public val activities: Set<LocalSalesActivity> get() = activityValues.toMutableSet()

    public operator fun component1(): CompanyScope = company
    public operator fun component2(): DomainId = publicationId
    public operator fun component3(): DomainId = profileId
    public operator fun component4(): Revision = revision
    public operator fun component5(): Set<LocalSalesActivity> = activities

    public fun copy(
        company: CompanyScope = this.company,
        publicationId: DomainId = this.publicationId,
        profileId: DomainId = this.profileId,
        revision: Revision = this.revision,
        activities: Set<LocalSalesActivity> = this.activities,
    ): LocalPublishedCompanyActivityProfileV1 =
        LocalPublishedCompanyActivityProfileV1(company, publicationId, profileId, revision, activities)

    override fun equals(other: Any?): Boolean = this === other || other is LocalPublishedCompanyActivityProfileV1 &&
        company == other.company && publicationId == other.publicationId && profileId == other.profileId &&
        revision == other.revision && activityValues == other.activityValues

    override fun hashCode(): Int {
        var result = company.hashCode()
        result = 31 * result + publicationId.hashCode()
        result = 31 * result + profileId.hashCode()
        result = 31 * result + revision.hashCode()
        return 31 * result + activityValues.hashCode()
    }

    override fun toString(): String = "LocalPublishedCompanyActivityProfileV1(company=$company, publicationId=$publicationId, " +
        "profileId=$profileId, revision=$revision, activities=$activityValues)"
}

/** Explicit signed-envelope syntax; authentication requires the installed company verifier. */
@Serializable
public data class LocalSignedCompanyActivityProfileV1(
    public val definition: LocalPublishedCompanyActivityProfileV1,
    public val signingKeyId: DomainId,
    public val signatureHex: String,
) {
    init { require(signatureHex.length == 128 && signatureHex.all { it in '0'..'9' || it in 'a'..'f' }) }
}

/** Frozen v1 identities reuse the sales enum without accepting future activities implicitly. */
private val companyActivitiesV1: Set<LocalSalesActivity> = setOf(
    LocalSalesActivity.RESTAURANT, LocalSalesActivity.CAFE,
    LocalSalesActivity.CLOTHING, LocalSalesActivity.SUPERMARKET,
)

private fun requireCompanyActivitiesV1(activities: List<LocalSalesActivity>) {
    require(activities.size <= companyActivitiesV1.size && activities.distinct().size == activities.size &&
        activities.all { it in companyActivitiesV1 })
}

/** A list keeps duplicate wire values visible until the immutable model can validate them. */
@Serializable
@SerialName("com.ukkera.pos.catalog.store.LocalPublishedCompanyActivityProfileV1")
private data class LocalCompanyActivityDefinitionWireV1(
    val company: CompanyScope,
    val publicationId: DomainId,
    val profileId: DomainId,
    val revision: Revision,
    val activities: List<LocalSalesActivity>,
)

internal object LocalCompanyActivityProfileSerializerV1 : KSerializer<LocalPublishedCompanyActivityProfileV1> {
    private val wireSerializer = LocalCompanyActivityDefinitionWireV1.serializer()
    override val descriptor: SerialDescriptor = wireSerializer.descriptor

    override fun serialize(encoder: Encoder, value: LocalPublishedCompanyActivityProfileV1) {
        wireSerializer.serialize(encoder, LocalCompanyActivityDefinitionWireV1(value.company, value.publicationId,
            value.profileId, value.revision, value.activities.sortedBy { it.name }))
    }

    override fun deserialize(decoder: Decoder): LocalPublishedCompanyActivityProfileV1 {
        val value = wireSerializer.deserialize(decoder)
        requireCompanyActivitiesV1(value.activities)
        require(value.activities == value.activities.sortedBy { it.name })
        return LocalPublishedCompanyActivityProfileV1(value.company, value.publicationId, value.profileId,
            value.revision, value.activities.toSet())
    }
}

/** Exact additive publication bytes, with independent bounds for content and signed envelopes. */
public object LocalCompanyActivityPublicationCodecV1 {
    public fun canonicalPublication(definition: LocalPublishedCompanyActivityProfileV1): ByteArray =
        strictBoundedActivityPublicationBytes(CompanyBusinessPublicationKind.COMPANY_ACTIVITY_PROFILE_V1.domain +
            localConfigurationJson.encodeToString(LocalPublishedCompanyActivityProfileV1.serializer(), definition))

    public fun decodeCanonicalPublication(bytes: ByteArray): LocalPublishedCompanyActivityProfileV1 {
        requireActivityPublicationSize(bytes)
        val content = bytes.copyOf()
        val prefix = CompanyBusinessPublicationKind.COMPANY_ACTIVITY_PROFILE_V1.domain.encodeToByteArray(throwOnInvalidSequence = true)
        try {
            require(content.size > prefix.size && prefix.indices.all { content[it] == prefix[it] })
            val definition = localConfigurationJson.decodeFromString(LocalPublishedCompanyActivityProfileV1.serializer(),
                content.decodeToString(startIndex = prefix.size, throwOnInvalidSequence = true))
            val canonical = canonicalPublication(definition)
            try { require(canonical.contentEquals(content)) } finally { canonical.fill(0) }
            return definition
        } finally { content.fill(0); prefix.fill(0) }
    }

    public fun signedPublication(value: LocalSignedCompanyActivityProfileV1): String {
        val canonical = signedPublicationBytes(value)
        return try { canonical.decodeToString(throwOnInvalidSequence = true) } finally { canonical.fill(0) }
    }

    public fun decodeSignedPublication(bytes: ByteArray): LocalSignedCompanyActivityProfileV1 {
        requireActivityPublicationSize(bytes)
        val content = bytes.copyOf()
        try {
            val value = localConfigurationJson.decodeFromString(LocalSignedCompanyActivityProfileV1.serializer(),
                content.decodeToString(throwOnInvalidSequence = true))
            val canonical = signedPublicationBytes(value)
            try { require(canonical.contentEquals(content)) } finally { canonical.fill(0) }
            return value
        } finally { content.fill(0) }
    }

    public fun decodeSignedPublication(value: String): LocalSignedCompanyActivityProfileV1 {
        val bytes = strictBoundedActivityPublicationBytes(value)
        return try { decodeSignedPublication(bytes) } finally { bytes.fill(0) }
    }

    /** Uses the existing exact-content verifier; this supplies no branch or employee authority. */
    public fun verify(value: LocalSignedCompanyActivityProfileV1, signatures: CompanyPublicationSignatureVerifier): Boolean {
        val canonical = canonicalPublication(value.definition)
        return try { signatures.verify(value.definition.company, value.signingKeyId, canonical, value.signatureHex) }
        finally { canonical.fill(0) }
    }

    private fun signedPublicationBytes(value: LocalSignedCompanyActivityProfileV1): ByteArray =
        strictBoundedActivityPublicationBytes(localConfigurationJson.encodeToString(LocalSignedCompanyActivityProfileV1.serializer(), value))
}

private fun requireActivityPublicationSize(bytes: ByteArray) {
    require(bytes.size in 1..CompanyBusinessPublicationProfileV1.MAXIMUM_CONTENT_BYTES)
}

private fun strictBoundedActivityPublicationBytes(value: String): ByteArray {
    require(value.length in 1..CompanyBusinessPublicationProfileV1.MAXIMUM_CONTENT_BYTES)
    val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
    try { requireActivityPublicationSize(bytes) } catch (failure: IllegalArgumentException) {
        bytes.fill(0)
        throw failure
    }
    return bytes
}
