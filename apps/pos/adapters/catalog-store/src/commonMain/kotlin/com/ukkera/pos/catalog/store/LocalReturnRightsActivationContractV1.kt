package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.CompanyScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

/** Documentary retention only; these identities grant no sale or return qualification. */
public object LocalReturnRightsActivationProfileV1 {
    public val ACTIVATE: CommandKey = CommandKey("local.return-rights.activate", 1)
    public val READ: ReadKey = ReadKey("local.return-rights.read", 1)
    public const val ACTIVE: String = "local.return-rights.active"
    public const val PUBLICATION: String = "local.return-rights.publication"
    public const val POLICY_CLAIM: String = "local.return-rights.policy-identity"
    public const val PUBLICATION_CLAIM: String = "local.return-rights.publication"
    public const val VERSION_CLAIM: String = "local.return-rights.policy-version"
    public const val ACTIVATED: String = "return.rights.activated_documentary_only"
    public const val REVISION_CONFLICT: String = "return.rights.revision_conflict"
    public const val PUBLICATION_ALREADY_USED: String = "return.rights.publication_already_used"
    public const val POLICY_SEQUENCE_REQUIRED: String = "return.rights.policy_sequence_required"
    /** Reserved until an authentic original negative-trust producer exists. */
    public const val SIGNATURE_REQUIRED: String = "return.rights.signature_required"
    public const val MAX_INPUT_BYTES: Int = 64 * 1024
    public const val MAX_QUERY_BYTES: Int = 1024
    public const val MAX_RECORD_BYTES: Int = 64 * 1024
    public fun schema(kind: String): String = "$kind/v1"
}

/** The exact canonical signed envelope, never a separately caller-appointed trust source. */
@Serializable
public data class LocalActivateReturnRightsV1(
    public val expectedActiveRevision: Revision,
    public val signedPublication: String,
) {
    init { LocalReturnRightsPublicationCodec.decodeSignedPublication(signedPublication) }
    override fun toString(): String = "LocalActivateReturnRightsV1([REDACTED])"
}

/** Inert exact signed-definition coordinate, not applicability or activation authority. */
@Serializable
public data class LocalReturnRightsReferenceV1(
    public val company: CompanyScope,
    public val publicationId: DomainId,
    public val policyId: DomainId,
    public val companyRevision: Revision,
    public val bodyDigest: PayloadDigest,
    public val signingKeyId: DomainId,
) {
    override fun toString(): String = "LocalReturnRightsReferenceV1([REDACTED])"
}

/**
 * Immutable original branch activation attribution and exact documentary envelope. The
 * active revision is the revision AT THIS activation, not a claim to be current today.
 * Company revision, including zero, is independent of local pointer storage generation.
 */
@Serializable
public data class LocalActivatedReturnRightsPolicyV1(
    public val scope: BranchScope,
    public val reference: LocalReturnRightsReferenceV1,
    public val signedPublication: String,
    public val activeRevision: Revision,
    public val operationId: OperationId,
    public val actorId: DomainId,
    public val permissionPolicyRevision: Revision,
    public val recordedAt: UtcInstant,
) {
    @Transient
    private val signed: LocalSignedReturnRightsPolicy =
        LocalReturnRightsPublicationCodec.decodeSignedPublication(signedPublication)

    init {
        require(activeRevision.value >= 1 && reference.company == scope.company() &&
            returnRightsReference(signed) == reference)
    }

    public val definition: LocalPublishedReturnRightsPolicy get() = signed.definition
    override fun toString(): String = "LocalActivatedReturnRightsPolicyV1([DOCUMENTARY, REDACTED])"
}

/**
 * Minimal original operation receipt. It contains no policy terms or signed envelope;
 * separate current result-recovery authority never grants another activation or content read.
 */
@Serializable
public data class LocalReturnRightsActivationResultV1(
    public val code: String,
    public val policyId: DomainId,
    public val publicationId: DomainId,
    public val activeRevision: Revision?,
    public val reference: LocalReturnRightsReferenceV1,
) {
    init {
        require(code in setOf(LocalReturnRightsActivationProfileV1.ACTIVATED,
            LocalReturnRightsActivationProfileV1.REVISION_CONFLICT,
            LocalReturnRightsActivationProfileV1.PUBLICATION_ALREADY_USED,
            LocalReturnRightsActivationProfileV1.POLICY_SEQUENCE_REQUIRED,
            LocalReturnRightsActivationProfileV1.SIGNATURE_REQUIRED))
        require(policyId == reference.policyId && publicationId == reference.publicationId)
        require((code == LocalReturnRightsActivationProfileV1.ACTIVATED) == (activeRevision != null))
        require(activeRevision == null || activeRevision.value >= 1)
    }
    override fun toString(): String = "LocalReturnRightsActivationResultV1([DOCUMENTARY, REDACTED])"
}

@Serializable
public enum class LocalReturnRightsReadKindV1 { CURRENT, PUBLICATION }

/** Direct historical IDs are selectors only; every content projection remains protected. */
@Serializable
public data class LocalReturnRightsQueryV1(
    public val kind: LocalReturnRightsReadKindV1,
    public val id: DomainId?,
) {
    init { require((kind == LocalReturnRightsReadKindV1.CURRENT) == (id == null)) }
}

@Serializable
public enum class LocalReturnRightsReadStatusV1 { ABSENT, PUBLISHED, SOURCE_UNAVAILABLE }

@Serializable
public data class LocalReturnRightsReadResultV1(
    public val status: LocalReturnRightsReadStatusV1,
    public val activation: LocalActivatedReturnRightsPolicyV1?,
) {
    init { require((status == LocalReturnRightsReadStatusV1.PUBLISHED) == (activation != null)) }
    override fun toString(): String = "LocalReturnRightsReadResultV1([DOCUMENTARY, REDACTED])"
}

public object LocalReturnRightsActivationCodecV1 {
    /** Strict original client/journal input; syntax acceptance supplies no native trust. */
    public fun decodeActivate(bytes: StoredBytes): LocalActivateReturnRightsV1 =
        returnRightsDecode(bytes, LocalActivateReturnRightsV1.serializer(),
            LocalReturnRightsActivationProfileV1.MAX_INPUT_BYTES)

    /** Refuses alternate wire spellings rather than repairing a saved operation on retry. */
    public fun normalizeActivate(bytes: StoredBytes): StoredBytes =
        returnRightsEncode(decodeActivate(bytes), LocalActivateReturnRightsV1.serializer(),
            LocalReturnRightsActivationProfileV1.MAX_INPUT_BYTES)

    public fun activate(operationId: OperationId, request: LocalActivateReturnRightsV1): MutationRequest =
        MutationRequest(operationId, LocalReturnRightsActivationProfileV1.ACTIVATE,
            returnRightsEncode(request, LocalActivateReturnRightsV1.serializer(),
                LocalReturnRightsActivationProfileV1.MAX_INPUT_BYTES))

    public fun query(request: LocalReturnRightsQueryV1): ReadRequest =
        ReadRequest(LocalReturnRightsActivationProfileV1.READ,
            returnRightsEncode(request, LocalReturnRightsQueryV1.serializer(),
                LocalReturnRightsActivationProfileV1.MAX_QUERY_BYTES))

    public fun readCurrent(): ReadRequest = query(LocalReturnRightsQueryV1(LocalReturnRightsReadKindV1.CURRENT, null))
    public fun readPublication(id: DomainId): ReadRequest = query(LocalReturnRightsQueryV1(LocalReturnRightsReadKindV1.PUBLICATION, id))

    public fun decodeResult(bytes: StoredBytes): LocalReturnRightsActivationResultV1 =
        returnRightsDecode(bytes, LocalReturnRightsActivationResultV1.serializer())

    public fun decodeRead(bytes: StoredBytes): LocalReturnRightsReadResultV1 =
        returnRightsDecode(bytes, LocalReturnRightsReadResultV1.serializer())
}

@OptIn(ExperimentalSerializationApi::class)
internal val returnRightsActivationJson: Json = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
    coerceInputValues = false
}

internal fun <T> returnRightsEncode(
    value: T,
    serializer: KSerializer<T>,
    maximum: Int = LocalReturnRightsActivationProfileV1.MAX_RECORD_BYTES,
): StoredBytes {
    val bytes = returnRightsActivationJson.encodeToString(serializer, value).encodeToByteArray(throwOnInvalidSequence = true)
    try {
        require(bytes.size in 1..maximum) { "Local return rights capacity" }
        return StoredBytes(bytes)
    } finally { bytes.fill(0) }
}

/** Strict literal v1 representation; absent fields and alternate spellings are not defaults. */
internal fun <T> returnRightsDecode(
    bytes: StoredBytes,
    serializer: KSerializer<T>,
    maximum: Int = LocalReturnRightsActivationProfileV1.MAX_RECORD_BYTES,
): T {
    require(bytes.size in 1..maximum) { "Local return rights capacity" }
    val content = bytes.copyBytes()
    try {
        val value = returnRightsActivationJson.decodeFromString(serializer, content.decodeToString(throwOnInvalidSequence = true))
        require(returnRightsEncode(value, serializer, maximum) == bytes) { "Noncanonical local return rights encoding" }
        return value
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw IllegalArgumentException("Invalid local return rights encoding")
    } finally { content.fill(0) }
}

internal fun returnRightsReference(signed: LocalSignedReturnRightsPolicy): LocalReturnRightsReferenceV1 {
    val definition = signed.definition
    val canonical = LocalReturnRightsPublicationCodec.canonicalPublication(definition)
    return try {
        LocalReturnRightsReferenceV1(definition.company, definition.publicationId, definition.policyId,
            definition.revision, sha256(canonical), signed.signingKeyId)
    } finally { canonical.fill(0) }
}

internal fun returnRightsDigest(bytes: StoredBytes): PayloadDigest {
    val content = bytes.copyBytes()
    return try { sha256(content) } finally { content.fill(0) }
}
