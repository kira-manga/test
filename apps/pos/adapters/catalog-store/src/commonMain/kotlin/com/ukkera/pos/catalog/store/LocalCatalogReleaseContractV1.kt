package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.security.CompanyBusinessPublicationKind
import com.ukkera.pos.security.CompanyBusinessPublicationProfileV1
import com.ukkera.pos.security.CompanyScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

/** Additive documentary release profile; existing SKU, cursor and policy profiles are unchanged. */
public object LocalCatalogReleaseProfileV1 {
    public val ACTIVATE: CommandKey = CommandKey("local.catalog.release.activate", 1)
    public val READ: ReadKey = ReadKey("local.catalog.release", 1)
    public const val ACTIVE: String = "catalog_release_active"
    public const val PUBLICATION: String = "catalog_release_publication"
    public const val MAX_MEMBERS: Int = 64
    public const val MAX_INPUT_BYTES: Int = 64 * 1024
    public const val MAX_RECORD_BYTES: Int = 64 * 1024
    public const val MAX_QUERY_BYTES: Int = 4 * 1024
    public const val MAX_PUBLICATION_BYTES: Int = CompanyBusinessPublicationProfileV1.MAXIMUM_CONTENT_BYTES
    public fun schema(kind: String): String = "$kind/v1"
}

/** Exact coordinates requested by a manifest, not proof of a completed or activated SKU. */
@Serializable
public data class LocalCatalogReleaseMemberV1(
    public val publicationId: DomainId,
    public val contentDigest: PayloadDigest,
    public val skuId: DomainId,
    public val skuRevision: Revision,
    public val productId: DomainId,
    public val productRevision: Revision,
) { override fun toString(): String = "LocalCatalogReleaseMemberV1([REDACTED])" }

/** A release's own coordinate; neither a SKU publication nor a branch catalog cursor. */
@Serializable
public data class LocalCatalogReleaseReferenceV1(
    public val catalogId: DomainId,
    public val publicationId: DomainId,
    public val revision: Revision,
    public val contentDigest: PayloadDigest,
) {
    init { require(catalogId != publicationId) }
    override fun toString(): String = "LocalCatalogReleaseReferenceV1([REDACTED])"
}

/**
 * Immutable ordered complete manifest. Independent revision/predecessor semantics are checked
 * against the complete authenticated original sequence, never inferred from another counter.
 * The owned collection and its detached reads cannot be changed through a supplied list.
 */
@Serializable(with = LocalPublishedCatalogReleaseSerializerV1::class)
public class LocalPublishedCatalogReleaseV1(
    public val company: CompanyScope,
    public val catalogId: DomainId,
    public val publicationId: DomainId,
    public val revision: Revision,
    public val previous: LocalCatalogReleaseReferenceV1?,
    members: List<LocalCatalogReleaseMemberV1>,
) {
    private val memberValues: List<LocalCatalogReleaseMemberV1> = members.also {
        require(it.size in 1..LocalCatalogReleaseProfileV1.MAX_MEMBERS)
    }.toList().also {
        require(it.size in 1..LocalCatalogReleaseProfileV1.MAX_MEMBERS)
        require(it.map { member -> member.skuId }.distinct().size == it.size)
        require(it.map { member -> member.publicationId }.distinct().size == it.size)
        require(it.none { member -> member.publicationId == publicationId || member.publicationId == catalogId })
    }

    public val members: List<LocalCatalogReleaseMemberV1> get() = memberValues.toList()

    init { require(catalogId != publicationId) }

    public operator fun component1(): CompanyScope = company
    public operator fun component2(): DomainId = catalogId
    public operator fun component3(): DomainId = publicationId
    public operator fun component4(): Revision = revision
    public operator fun component5(): LocalCatalogReleaseReferenceV1? = previous
    public operator fun component6(): List<LocalCatalogReleaseMemberV1> = members

    public fun copy(
        company: CompanyScope = this.company,
        catalogId: DomainId = this.catalogId,
        publicationId: DomainId = this.publicationId,
        revision: Revision = this.revision,
        previous: LocalCatalogReleaseReferenceV1? = this.previous,
        members: List<LocalCatalogReleaseMemberV1> = this.members,
    ): LocalPublishedCatalogReleaseV1 =
        LocalPublishedCatalogReleaseV1(company, catalogId, publicationId, revision, previous, members)

    override fun equals(other: Any?): Boolean = this === other || other is LocalPublishedCatalogReleaseV1 &&
        company == other.company && catalogId == other.catalogId && publicationId == other.publicationId &&
        revision == other.revision && previous == other.previous && memberValues == other.memberValues

    override fun hashCode(): Int = listOf(company, catalogId, publicationId, revision, previous, memberValues).hashCode()
    override fun toString(): String = "LocalPublishedCatalogReleaseV1([REDACTED])"
}

/** Signed-envelope syntax only. Native completion, signature trust and Master access are separate. */
@Serializable
public data class LocalSignedCatalogReleaseV1(
    public val definition: LocalPublishedCatalogReleaseV1,
    public val signingKeyId: DomainId,
    public val signatureHex: String,
) {
    init { require(signatureHex.length == 128 && signatureHex.all { it in '0'..'9' || it in 'a'..'f' }) }
    override fun toString(): String = "LocalSignedCatalogReleaseV1([REDACTED])"
}

@Serializable
public data class LocalActivateCatalogReleaseV1(
    public val publication: LocalSignedCatalogReleaseV1,
    public val expectedActiveStorageRevision: Revision?,
) {
    init { require(expectedActiveStorageRevision == null || expectedActiveStorageRevision.value >= 1) }
    override fun toString(): String = "LocalActivateCatalogReleaseV1([REDACTED])"
}

/** Direct protected identity read, without unadmitted enumeration or publication discovery. */
@Serializable
public data class LocalCatalogReleaseQueryV1(
    public val catalogId: DomainId,
    public val publicationId: DomainId? = null,
)

@Serializable
public data class LocalCatalogReleaseResultV1(
    public val code: String,
    public val catalogId: DomainId,
    public val publicationId: DomainId,
    public val storageRevision: Revision?,
) {
    init { require(storageRevision == null || storageRevision.value >= 1) }
    override fun toString(): String = "LocalCatalogReleaseResultV1([REDACTED])"
}

/**
 * Produced only after successful protected source admission. A null active storage revision
 * represents proved absence, never unavailable native evidence. A stale current membership can
 * withhold active content while retaining its observed storage revision and immutable original.
 */
@Serializable
public data class LocalCatalogReleaseReadReplyV1(
    public val catalogId: DomainId,
    public val activeStorageRevision: Revision?,
    public val active: LocalSignedCatalogReleaseV1?,
    public val publication: LocalSignedCatalogReleaseV1? = null,
) {
    init {
        require(activeStorageRevision == null || activeStorageRevision.value >= 1)
        require(active == null || activeStorageRevision != null && active.definition.catalogId == catalogId)
        require(publication == null || publication.definition.catalogId == catalogId)
    }
    override fun toString(): String = "LocalCatalogReleaseReadReplyV1([REDACTED])"
}

@Serializable
@SerialName("com.ukkera.pos.catalog.store.LocalPublishedCatalogReleaseV1")
private data class LocalPublishedCatalogReleaseWireV1(
    val company: CompanyScope,
    val catalogId: DomainId,
    val publicationId: DomainId,
    val revision: Revision,
    val previous: LocalCatalogReleaseReferenceV1?,
    val members: List<LocalCatalogReleaseMemberV1>,
)

internal object LocalPublishedCatalogReleaseSerializerV1 : KSerializer<LocalPublishedCatalogReleaseV1> {
    private val wire = LocalPublishedCatalogReleaseWireV1.serializer()
    override val descriptor: SerialDescriptor = wire.descriptor

    override fun serialize(encoder: Encoder, value: LocalPublishedCatalogReleaseV1) {
        wire.serialize(encoder, LocalPublishedCatalogReleaseWireV1(value.company, value.catalogId,
            value.publicationId, value.revision, value.previous, value.members))
    }

    override fun deserialize(decoder: Decoder): LocalPublishedCatalogReleaseV1 {
        val value = wire.deserialize(decoder)
        return LocalPublishedCatalogReleaseV1(value.company, value.catalogId, value.publicationId,
            value.revision, value.previous, value.members)
    }
}

/** Strict new-profile canonical bytes; no old SKU, native or saved-intent bytes are rewritten. */
public object LocalCatalogReleaseCodecV1 {
    public fun activate(operationId: OperationId, request: LocalActivateCatalogReleaseV1): MutationRequest =
        MutationRequest(operationId, LocalCatalogReleaseProfileV1.ACTIVATE, encode(request))

    public fun encode(request: LocalActivateCatalogReleaseV1): StoredBytes =
        catalogReleaseEncode(request, LocalActivateCatalogReleaseV1.serializer(), LocalCatalogReleaseProfileV1.MAX_INPUT_BYTES)

    public fun decode(bytes: StoredBytes): LocalActivateCatalogReleaseV1 =
        catalogReleaseDecode(bytes, LocalActivateCatalogReleaseV1.serializer(), LocalCatalogReleaseProfileV1.MAX_INPUT_BYTES)

    public fun query(request: LocalCatalogReleaseQueryV1): ReadRequest =
        ReadRequest(LocalCatalogReleaseProfileV1.READ,
            catalogReleaseEncode(request, LocalCatalogReleaseQueryV1.serializer(), LocalCatalogReleaseProfileV1.MAX_QUERY_BYTES))

    public fun decodeQuery(bytes: StoredBytes): LocalCatalogReleaseQueryV1 =
        catalogReleaseDecode(bytes, LocalCatalogReleaseQueryV1.serializer(), LocalCatalogReleaseProfileV1.MAX_QUERY_BYTES)

    public fun encodeResult(value: LocalCatalogReleaseResultV1): StoredBytes =
        catalogReleaseEncode(value, LocalCatalogReleaseResultV1.serializer())

    public fun decodeResult(bytes: StoredBytes): LocalCatalogReleaseResultV1 =
        catalogReleaseDecode(bytes, LocalCatalogReleaseResultV1.serializer())

    public fun encodeRead(value: LocalCatalogReleaseReadReplyV1): StoredBytes =
        catalogReleaseEncode(value, LocalCatalogReleaseReadReplyV1.serializer())

    public fun decodeRead(bytes: StoredBytes): LocalCatalogReleaseReadReplyV1 =
        catalogReleaseDecode(bytes, LocalCatalogReleaseReadReplyV1.serializer())

    public fun canonicalPublication(definition: LocalPublishedCatalogReleaseV1): ByteArray =
        catalogReleaseBytes(CompanyBusinessPublicationKind.CATALOG_RELEASE_V1.domain +
            catalogReleaseJson.encodeToString(LocalPublishedCatalogReleaseV1.serializer(), definition),
            LocalCatalogReleaseProfileV1.MAX_PUBLICATION_BYTES)

    public fun decodeCanonicalPublication(bytes: ByteArray): LocalPublishedCatalogReleaseV1 {
        require(bytes.size in 1..LocalCatalogReleaseProfileV1.MAX_PUBLICATION_BYTES)
        val content = bytes.copyOf()
        val prefix = CompanyBusinessPublicationKind.CATALOG_RELEASE_V1.domain.encodeToByteArray(throwOnInvalidSequence = true)
        try {
            require(content.size > prefix.size && prefix.indices.all { content[it] == prefix[it] })
            val definition = catalogReleaseJson.decodeFromString(LocalPublishedCatalogReleaseV1.serializer(),
                content.decodeToString(startIndex = prefix.size, throwOnInvalidSequence = true))
            val canonical = canonicalPublication(definition)
            try { require(canonical.contentEquals(content)) { "Noncanonical catalog release publication" } }
            finally { canonical.fill(0) }
            return definition
        } finally { content.fill(0); prefix.fill(0) }
    }

    public fun signedPublication(value: LocalSignedCatalogReleaseV1): String =
        catalogReleaseJson.encodeToString(LocalSignedCatalogReleaseV1.serializer(), value).let { text ->
            val bytes = catalogReleaseBytes(text, LocalCatalogReleaseProfileV1.MAX_INPUT_BYTES)
            try { bytes.decodeToString(throwOnInvalidSequence = true) } finally { bytes.fill(0) }
        }

    public fun decodeSignedPublication(bytes: ByteArray): LocalSignedCatalogReleaseV1 =
        catalogReleaseDecode(StoredBytes(bytes), LocalSignedCatalogReleaseV1.serializer(), LocalCatalogReleaseProfileV1.MAX_INPUT_BYTES)

    public fun decodeSignedPublication(value: String): LocalSignedCatalogReleaseV1 {
        val bytes = catalogReleaseBytes(value, LocalCatalogReleaseProfileV1.MAX_INPUT_BYTES)
        return try { decodeSignedPublication(bytes) } finally { bytes.fill(0) }
    }

    /** Exact-content cryptographic check through installed trust, not current native membership. */
    public fun verify(value: LocalSignedCatalogReleaseV1, signatures: CompanyPublicationSignatureVerifier): Boolean {
        val canonical = canonicalPublication(value.definition)
        return try { signatures.verify(value.definition.company, value.signingKeyId, canonical, value.signatureHex) }
        finally { canonical.fill(0) }
    }
}

internal val catalogReleaseJson: Json = Json {
    encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false
}

internal fun <T> catalogReleaseEncode(value: T, serializer: KSerializer<T>,
    maximum: Int = LocalCatalogReleaseProfileV1.MAX_RECORD_BYTES): StoredBytes {
    val bytes = catalogReleaseBytes(catalogReleaseJson.encodeToString(serializer, value), maximum)
    return try { StoredBytes(bytes) } finally { bytes.fill(0) }
}

internal fun <T> catalogReleaseDecode(bytes: StoredBytes, serializer: KSerializer<T>,
    maximum: Int = LocalCatalogReleaseProfileV1.MAX_RECORD_BYTES): T {
    require(maximum in 1..LocalCatalogReleaseProfileV1.MAX_RECORD_BYTES && bytes.size in 1..maximum)
    val content = bytes.copyBytes()
    try {
        val value = catalogReleaseJson.decodeFromString(serializer, content.decodeToString(throwOnInvalidSequence = true))
        val canonical = catalogReleaseBytes(catalogReleaseJson.encodeToString(serializer, value), maximum)
        try { require(canonical.contentEquals(content)) { "Noncanonical catalog release encoding" } }
        finally { canonical.fill(0) }
        return value
    } finally { content.fill(0) }
}

private fun catalogReleaseBytes(value: String, maximum: Int): ByteArray {
    require(maximum in 1..LocalCatalogReleaseProfileV1.MAX_RECORD_BYTES && value.length in 1..maximum)
    val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
    try { require(bytes.size in 1..maximum) { "Catalog release exceeds wire capacity" } }
    catch (failure: IllegalArgumentException) { bytes.fill(0); throw failure }
    return bytes
}
