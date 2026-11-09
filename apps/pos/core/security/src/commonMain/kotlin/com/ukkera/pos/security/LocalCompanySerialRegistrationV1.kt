package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** An additive exact local-company publication, not a purchasing certificate or a stock effect. */
public object CompanySerialRegistrationProfileV1 {
    public const val MAXIMUM_CODE_CHARACTERS: Int = 128
    public const val MAXIMUM_CODE_BYTES: Int = 256
    public const val MAXIMUM_PUBLICATION_BYTES: Int = CompanyBusinessPublicationProfileV1.MAXIMUM_CONTENT_BYTES
}

/**
 * Coordinates read by the actual original native target owner. The installation allocation
 * binds this local registration to its branch; it is NOT a serial namespace. The
 * separately completed original sole issuer is required. Neither retaining this
 * reference nor copying its fields grants signing or activation.
 */
public class NativeLocalSerialRegistrationAllocation internal constructor(
    public val scope: BranchScope,
    public val allocationId: DomainId,
    public val allocationDigest: PayloadDigest,
    public val serialAuthority: NativeOriginalLocalSerialAuthority,
) {
    init {
        val origin = serialAuthority.definition.origin
        require(scope == origin.originalScope && allocationId == origin.originalAllocationId && allocationDigest == origin.originalAllocationDigest)
    }
    override fun toString(): String = "NativeLocalSerialRegistrationAllocation([REDACTED])"
}

/** Exact predecessor coordinates, not a native readback, signature or authority capability. */
@Serializable
public data class LocalSerialRegistrationReferenceV1(
    public val registrationId: DomainId,
    public val publicationId: DomainId,
    public val revision: Revision,
    public val contentDigest: PayloadDigest,
) {
    init { require(registrationId != publicationId && revision.value in 1 until Long.MAX_VALUE) }
    override fun toString(): String = "LocalSerialRegistrationReferenceV1([REDACTED])"
}

/**
 * Requested immutable identity and a qualification interval. Syntax alone reserves nothing.
 * The native issuer permanently binds registration, serial, exact code, SKU and allocation
 * across ALL retained phases. A qualification revision may pin a later publication of the
 * SAME SKU but requires the exact immediately preceding completed native registration.
 * A native-head signature is deliberately separate from current employee Master activation.
 */
@Serializable
public data class LocalPublishedSerialRegistrationV1(
    public val scope: BranchScope,
    public val allocationId: DomainId,
    public val allocationDigest: PayloadDigest,
    public val serialAuthority: LocalSerialAuthorityReferenceV1,
    public val publicationId: DomainId,
    public val registrationId: DomainId,
    public val revision: Revision,
    public val previous: LocalSerialRegistrationReferenceV1?,
    public val serialId: DomainId,
    public val serialCode: String,
    public val skuId: DomainId,
    public val skuPublicationId: DomainId,
    public val skuPublicationDigest: PayloadDigest,
    public val validFrom: UtcInstant,
    public val validUntil: UtcInstant?,
) {
    public val company: CompanyScope get() = scope.company()
    init {
        requireLocalSerialRegistrationCode(serialCode)
        require(revision.value in 1 until Long.MAX_VALUE)
        require((revision == Revision(1)) == (previous == null))
        previous?.let {
            require(it.registrationId == registrationId && it.revision.value < Long.MAX_VALUE - 1 &&
                revision == it.revision.next() && it.publicationId != publicationId)
        }
        require(validUntil == null || validUntil > validFrom)
        val own = listOf(publicationId, registrationId, serialId)
        require(own.distinct().size == own.size && own.none { it == allocationId || it == skuId || it == skuPublicationId ||
            it == serialAuthority.authorityId || it == serialAuthority.publicationId })
        require(previous?.publicationId !in own)
    }
    override fun toString(): String = "LocalPublishedSerialRegistrationV1([REDACTED])"
}

/** Signed-envelope syntax only. Real native completion/membership and branch access are separate. */
@Serializable
public data class LocalSignedSerialRegistrationV1(
    public val definition: LocalPublishedSerialRegistrationV1,
    public val signingKeyId: DomainId,
    public val signatureHex: String,
) {
    init {
        require(signatureHex.length == 128 && signatureHex.all { it in '0'..'9' || it in 'a'..'f' })
        require(signingKeyId !in listOf(definition.publicationId, definition.registrationId, definition.serialId))
    }
    override fun toString(): String = "LocalSignedSerialRegistrationV1([REDACTED])"
}

/** Strict new-profile bytes only; no existing SKU, policy, reservation or certificate is reencoded. */
public object LocalSerialRegistrationPublicationCodecV1 {
    public fun canonicalPublication(value: LocalPublishedSerialRegistrationV1): ByteArray =
        serialRegistrationPublicationBytes(CompanyBusinessPublicationKind.SERIAL_REGISTRATION_V1.domain +
            serialRegistrationPublicationJson.encodeToString(LocalPublishedSerialRegistrationV1.serializer(), value))

    public fun decodeCanonicalPublication(bytes: ByteArray): LocalPublishedSerialRegistrationV1 {
        require(bytes.size in 1..CompanySerialRegistrationProfileV1.MAXIMUM_PUBLICATION_BYTES)
        val prefix = CompanyBusinessPublicationKind.SERIAL_REGISTRATION_V1.domain.encodeToByteArray()
        try {
            require(bytes.size > prefix.size && prefix.indices.all { bytes[it] == prefix[it] })
            val value = serialRegistrationPublicationJson.decodeFromString(LocalPublishedSerialRegistrationV1.serializer(),
                bytes.decodeToString(startIndex = prefix.size, throwOnInvalidSequence = true))
            val canonical = canonicalPublication(value)
            try { require(canonical.contentEquals(bytes)) { "Noncanonical serial registration publication" } }
            finally { canonical.fill(0) }
            return value
        } finally { prefix.fill(0) }
    }

    public fun reference(value: LocalPublishedSerialRegistrationV1): LocalSerialRegistrationReferenceV1 {
        val canonical = canonicalPublication(value)
        return try { LocalSerialRegistrationReferenceV1(value.registrationId, value.publicationId, value.revision, sha256(canonical)) }
        finally { canonical.fill(0) }
    }
}

/** Proposal only. Native issuance re-reads the original allocation and reserves the exact identity. */
public fun makeNativeSerialRegistrationProposal(value: LocalPublishedSerialRegistrationV1): CompanyBusinessPublicationProposal {
    val canonical = LocalSerialRegistrationPublicationCodecV1.canonicalPublication(value)
    return try { CompanyBusinessPublicationProposal(value.company, CompanyBusinessPublicationKind.SERIAL_REGISTRATION_V1,
        value.publicationId, value.registrationId, canonical) }
    finally { canonical.fill(0) }
}

/** Decode the actual retained review, never reconstruct a second identity from a client draft. */
public fun CompanyBusinessPublicationAction.readNativeSerialRegistrationDefinition(): LocalPublishedSerialRegistrationV1 =
    proposal.readSerialRegistrationDefinition()

/** Content join only; returning this envelope does not activate registration or affect physical stock. */
public fun CompanyBusinessPublicationOutput.readNativeSerialRegistration(): LocalSignedSerialRegistrationV1 =
    LocalSignedSerialRegistrationV1(proposal.readSerialRegistrationDefinition(), signingKeyId, signatureHex)

internal fun CompanyBusinessPublicationProposal.readSerialRegistrationDefinition(): LocalPublishedSerialRegistrationV1 {
    require(kind == CompanyBusinessPublicationKind.SERIAL_REGISTRATION_V1)
    val content = copyCanonicalContent()
    return try {
        require(sha256(content) == contentDigest)
        LocalSerialRegistrationPublicationCodecV1.decodeCanonicalPublication(content).also {
            require(it.company == company && it.publicationId == publicationId && it.registrationId == entityId)
        }
    } finally { content.fill(0) }
}

internal fun sameSerialRegistrationIdentity(before: LocalPublishedSerialRegistrationV1,
    after: LocalPublishedSerialRegistrationV1): Boolean =
    before.scope == after.scope && before.allocationId == after.allocationId && before.allocationDigest == after.allocationDigest &&
        before.serialAuthority == after.serialAuthority &&
        before.registrationId == after.registrationId && before.serialId == after.serialId &&
        before.serialCode == after.serialCode && before.skuId == after.skuId

internal interface LocalCompanySerialRegistrationController : OriginalLocalSerialCustodyControllerV1 {
    suspend fun prepareOriginalSerialAuthority(): LocalCompanyAuthorityResult<CompanyBusinessPublicationAction>
    suspend fun readOriginalSerialAuthority(): LocalCompanyAuthorityResult<NativeOriginalLocalSerialAuthority>
    suspend fun originalSerialRegistrationAllocation(): LocalCompanyAuthorityResult<NativeLocalSerialRegistrationAllocation>
}

private val serialRegistrationPublicationJson: Json = Json {
    encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false
}

private fun serialRegistrationPublicationBytes(value: String): ByteArray {
    require(value.length in 1..CompanySerialRegistrationProfileV1.MAXIMUM_PUBLICATION_BYTES)
    val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
    try { require(bytes.size in 1..CompanySerialRegistrationProfileV1.MAXIMUM_PUBLICATION_BYTES) }
    catch (failure: IllegalArgumentException) { bytes.fill(0); throw failure }
    return bytes
}

private fun requireLocalSerialRegistrationCode(value: String) {
    require(value.isNotEmpty() && value.length <= CompanySerialRegistrationProfileV1.MAXIMUM_CODE_CHARACTERS &&
        value == value.trim() && value.none { it.code < 32 || it.code == 127 })
    val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
    try { require(bytes.size <= CompanySerialRegistrationProfileV1.MAXIMUM_CODE_BYTES) }
    finally { bytes.fill(0) }
}
