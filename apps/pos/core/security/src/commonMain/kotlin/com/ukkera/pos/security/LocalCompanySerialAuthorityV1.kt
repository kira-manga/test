package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A distinct, explicit routing grant for a newly founded ORIGINAL LOCAL company. */
public object CompanySerialAuthorityProfileV1 {
    public const val MAXIMUM_PUBLICATION_BYTES: Int = 4 * 1024
    public const val PURPOSE: String = "SOLE_ORIGINAL_LOCAL_COMPANY_SERIAL_EXACT_CODES_V1"
}

/**
 * Inert origin DATA. The installer issuer identifies the real founding source; its
 * installation delegation does not itself grant serial authority. The separate
 * completed, root-endorsed authority publication grants the original company-head
 * registry the sole exact-code routing namespace. No imported/central authority.
 */
@Serializable
public data class LocalCompanySerialIssuerOriginV1(
    public val company: CompanyScope,
    public val foundingActionId: DomainId,
    public val rootId: DomainId,
    public val rootKeyId: DomainId,
    public val rootDocumentDigest: PayloadDigest,
    public val rootFingerprint: String,
    public val headAuthorityId: DomainId,
    public val headEnrollmentId: DomainId,
    public val originalIssuerId: DomainId,
    public val originalIssuerKeyId: DomainId,
    public val originalIssuerDelegationDigest: PayloadDigest,
    public val originalScope: BranchScope,
    public val originalAllocationId: DomainId,
    public val originalAllocationDigest: PayloadDigest,
) {
    init {
        require(originalScope.company() == company)
        require(rootFingerprint.length == 64 && rootFingerprint.all { it in "0123456789abcdef" })
        val ids = identityIds()
        require(ids.distinct().size == ids.size)
    }

    internal fun identityIds(): List<DomainId> = listOf(company.organizationId, company.companyId, foundingActionId,
        rootId, rootKeyId, headAuthorityId, headEnrollmentId, originalIssuerId, originalIssuerKeyId,
        originalScope.branchId, originalAllocationId)

    override fun toString(): String = "LocalCompanySerialIssuerOriginV1([REDACTED])"
}

/** Exact immutable namespace coordinates, not native membership or activation. */
@Serializable
public data class LocalSerialAuthorityReferenceV1(
    public val authorityId: DomainId,
    public val publicationId: DomainId,
    public val contentDigest: PayloadDigest,
    public val issuerOriginDigest: PayloadDigest,
) {
    init { require(authorityId != publicationId) }
    override fun toString(): String = "LocalSerialAuthorityReferenceV1([REDACTED])"
}

/**
 * One permanent whole-exact-code namespace, issued only by the actual native
 * company-head owner. There is no authority revision, reset or reassignment.
 * Manufacturer codes are not prefixed, hashed, trimmed or case-normalized.
 * A branch allocation and a matching company ID are not this routing grant.
 */
@Serializable
public data class LocalPublishedSerialAuthorityV1(
    public val authorityId: DomainId,
    public val publicationId: DomainId,
    public val origin: LocalCompanySerialIssuerOriginV1,
    public val purpose: String = CompanySerialAuthorityProfileV1.PURPOSE,
) {
    public val company: CompanyScope get() = origin.company

    init {
        require(purpose == CompanySerialAuthorityProfileV1.PURPOSE)
        require(authorityId != publicationId && authorityId !in origin.identityIds() && publicationId !in origin.identityIds())
    }

    override fun toString(): String = "LocalPublishedSerialAuthorityV1([REDACTED])"
}

/**
 * Created by the real native reader after settled custody and complete original
 * readback. This reference cannot sign, activate or prove a later current cut.
 */
public class NativeOriginalLocalSerialAuthority internal constructor(
    public val definition: LocalPublishedSerialAuthorityV1,
    public val originalActionId: DomainId,
    public val signingKeyId: DomainId,
    public val reference: LocalSerialAuthorityReferenceV1,
    public val nativeLedgerGeneration: Long,
) {
    init {
        require(reference == LocalSerialAuthorityPublicationCodecV1.reference(definition) && nativeLedgerGeneration >= 0)
    }

    override fun toString(): String = "NativeOriginalLocalSerialAuthority([REDACTED])"
}

/** Native custody status only. A pending reservation is never an absence or grant. */
public enum class OriginalLocalSerialCustodyPhaseV1 { SETTLED, INTENT }

public class OriginalLocalSerialCustodyReferenceV1 internal constructor(
    public val authority: LocalSerialAuthorityReferenceV1,
    public val originalAuthorityActionId: DomainId,
    public val phase: OriginalLocalSerialCustodyPhaseV1,
    public val pendingOriginalActionId: DomainId?,
) {
    init { require((phase == OriginalLocalSerialCustodyPhaseV1.INTENT) == (pendingOriginalActionId != null)) }
    override fun toString(): String = "OriginalLocalSerialCustodyReferenceV1([REDACTED])"
}

/**
 * Native recovery control only, available from the genuine original history owner
 * before business trust or Room startup. It cannot prepare, approve, allocate,
 * expose a completed business snapshot or regenerate missing publication bytes.
 */
public class OriginalLocalSerialCustodyControlV1 internal constructor(
    private val controller: OriginalLocalSerialCustodyControllerV1,
) {
    /** Mandatory custody status, not proof that its companion is currently usable. */
    public suspend fun read(): LocalCompanyAuthorityResult<OriginalLocalSerialCustodyReferenceV1> =
        controller.readOriginalSerialCustody()

    /** Only the actually retained exact next cut can settle; no previous-cut fallback. */
    public suspend fun recover(originalActionId: DomainId): LocalCompanyAuthorityResult<OriginalLocalSerialCustodyReferenceV1> =
        controller.recoverOriginalSerialCustody(originalActionId)

    override fun toString(): String = "OriginalLocalSerialCustodyControlV1([REDACTED])"
}

internal interface OriginalLocalSerialCustodyControllerV1 {
    suspend fun readOriginalSerialCustody(): LocalCompanyAuthorityResult<OriginalLocalSerialCustodyReferenceV1>
    suspend fun recoverOriginalSerialCustody(originalActionId: DomainId): LocalCompanyAuthorityResult<OriginalLocalSerialCustodyReferenceV1>
}

internal interface SelfOwnedOriginalSerialCustodyOwnerV1 {
    suspend fun readOriginalSerialCustodyControl(): LocalCompanyAuthorityResult<OriginalLocalSerialCustodyControlV1>
}

/** No accepting fallback, alternate owner, source import or business-trust acquisition. */
public suspend fun SelfOwnedNativeHistoryCustody.readOriginalSerialCustodyControl():
    LocalCompanyAuthorityResult<OriginalLocalSerialCustodyControlV1> =
    (controller as? SelfOwnedOriginalSerialCustodyOwnerV1)?.readOriginalSerialCustodyControl()
        ?: LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE)

/** A closed new profile. Existing native catalog/policy/installation bytes are unchanged. */
public object LocalSerialAuthorityPublicationCodecV1 {
    public fun canonicalPublication(value: LocalPublishedSerialAuthorityV1): ByteArray = boundedSerialAuthorityBytes(
        CompanyBusinessPublicationKind.SERIAL_AUTHORITY_V1.domain + serialAuthorityJson.encodeToString(
            LocalPublishedSerialAuthorityV1.serializer(), value))

    public fun decodeCanonicalPublication(bytes: ByteArray): LocalPublishedSerialAuthorityV1 {
        require(bytes.size in 1..CompanySerialAuthorityProfileV1.MAXIMUM_PUBLICATION_BYTES)
        val prefix = CompanyBusinessPublicationKind.SERIAL_AUTHORITY_V1.domain.encodeToByteArray()
        try {
            require(bytes.size > prefix.size && prefix.indices.all { bytes[it] == prefix[it] })
            val value = serialAuthorityJson.decodeFromString(LocalPublishedSerialAuthorityV1.serializer(),
                bytes.decodeToString(startIndex = prefix.size, throwOnInvalidSequence = true))
            val canonical = canonicalPublication(value)
            try { require(canonical.contentEquals(bytes)) { "Noncanonical original serial authority" } }
            finally { canonical.fill(0) }
            return value
        } finally { prefix.fill(0) }
    }

    public fun originDigest(value: LocalCompanySerialIssuerOriginV1): PayloadDigest {
        val bytes = boundedSerialAuthorityBytes("ukkera.original-local-company-serial-issuer-origin/v1\n" +
            serialAuthorityJson.encodeToString(LocalCompanySerialIssuerOriginV1.serializer(), value))
        return try { sha256(bytes) } finally { bytes.fill(0) }
    }

    public fun reference(value: LocalPublishedSerialAuthorityV1): LocalSerialAuthorityReferenceV1 {
        val bytes = canonicalPublication(value)
        return try { LocalSerialAuthorityReferenceV1(value.authorityId, value.publicationId, sha256(bytes), originDigest(value.origin)) }
        finally { bytes.fill(0) }
    }
}

/** Not exported as a caller-selected namespace preparation path. */
internal fun makeNativeSerialAuthorityProposal(value: LocalPublishedSerialAuthorityV1): CompanyBusinessPublicationProposal {
    val bytes = LocalSerialAuthorityPublicationCodecV1.canonicalPublication(value)
    return try { CompanyBusinessPublicationProposal(value.company, CompanyBusinessPublicationKind.SERIAL_AUTHORITY_V1,
        value.publicationId, value.authorityId, bytes) }
    finally { bytes.fill(0) }
}

public fun CompanyBusinessPublicationAction.readNativeSerialAuthorityDefinition(): LocalPublishedSerialAuthorityV1 =
    proposal.readSerialAuthorityDefinition()

/** Content DATA only; the native output's actual ledger membership remains necessary. */
public fun CompanyBusinessPublicationOutput.readNativeSerialAuthorityDefinition(): LocalPublishedSerialAuthorityV1 =
    proposal.readSerialAuthorityDefinition()

internal fun CompanyBusinessPublicationProposal.readSerialAuthorityDefinition(): LocalPublishedSerialAuthorityV1 {
    require(kind == CompanyBusinessPublicationKind.SERIAL_AUTHORITY_V1)
    val bytes = copyCanonicalContent()
    return try {
        require(sha256(bytes) == contentDigest)
        LocalSerialAuthorityPublicationCodecV1.decodeCanonicalPublication(bytes).also {
            require(it.company == company && it.publicationId == publicationId && it.authorityId == entityId)
        }
    } finally { bytes.fill(0) }
}

private val serialAuthorityJson: Json = Json {
    encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false
}

private fun boundedSerialAuthorityBytes(value: String): ByteArray {
    require(value.length in 1..CompanySerialAuthorityProfileV1.MAXIMUM_PUBLICATION_BYTES)
    val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
    try { require(bytes.size in 1..CompanySerialAuthorityProfileV1.MAXIMUM_PUBLICATION_BYTES) }
    catch (failure: Throwable) { bytes.fill(0); throw failure }
    return bytes
}
