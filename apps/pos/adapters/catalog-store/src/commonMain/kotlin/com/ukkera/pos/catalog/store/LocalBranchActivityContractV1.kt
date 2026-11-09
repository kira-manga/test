package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.LocalSalesActivity
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

/** Initial application only. These identities introduce no replacement or business effect. */
public object LocalBranchActivityProfileV1 {
    public val ACTIVATE_INITIAL: CommandKey = CommandKey("local.branch.activity.activate-initial", 1)
    public val READ_CURRENT: ReadKey = ReadKey("local.branch.activity.read-current", 1)
    public const val ACTIVE: String = "local.branch.activity-active"
    public const val PUBLICATION: String = "local.branch.activity-publication"
    public const val BRANCH_CLAIM: String = "local.branch.activity-identity"
    public const val PUBLICATION_CLAIM: String = "local.branch.activity-publication"
    public const val ACTIVATED: String = "branch.activity.initial_activated"
    public const val ALREADY_ACTIVATED: String = "branch.activity.initial_already_activated"
    public const val MAX_INPUT_BYTES: Int = 64 * 1024
    public const val MAX_QUERY_BYTES: Int = 1024
    public const val MAX_RECORD_BYTES: Int = 64 * 1024
    public fun schema(kind: String): String = "$kind/v1"
}

/** Exact canonical original, not a signature/trust/actor supplied separately by the caller. */
@Serializable
public data class LocalActivateInitialBranchActivityV1(public val signedPublication: String) {
    init { LocalCompanyActivityPublicationCodecV1.decodeSignedPublication(signedPublication) }
    override fun toString(): String = "LocalActivateInitialBranchActivityV1([REDACTED])"
}

/** Inert exact source coordinate. A reference alone is never branch activity authority. */
@Serializable
public data class LocalBranchActivityReferenceV1(
    public val company: CompanyScope,
    public val publicationId: DomainId,
    public val profileId: DomainId,
    public val companyRevision: Revision,
    public val bodyDigest: PayloadDigest,
    public val signingKeyId: DomainId,
) {
    override fun toString(): String = "LocalBranchActivityReferenceV1([REDACTED])"
}

/**
 * Applied projection retaining the exact signed original and actual employee attribution.
 * The derived definition is immutable; activity getters return detached snapshots. Company
 * revision (including zero) is independent of this initial local applied revision of one.
 */
@Serializable
public data class LocalAppliedBranchActivityV1(
    public val scope: BranchScope,
    public val reference: LocalBranchActivityReferenceV1,
    public val signedPublication: String,
    public val appliedRevision: Revision,
    public val operationId: OperationId,
    public val actorId: DomainId,
    public val permissionPolicyRevision: Revision,
    public val recordedAt: UtcInstant,
) {
    @Transient
    private val signed: LocalSignedCompanyActivityProfileV1 =
        LocalCompanyActivityPublicationCodecV1.decodeSignedPublication(signedPublication)

    init {
        require(appliedRevision == Revision(1) && reference.company == scope.company() &&
            branchActivityReference(signed) == reference)
    }

    public val definition: LocalPublishedCompanyActivityProfileV1 get() = signed.definition
    public val activities: Set<LocalSalesActivity> get() = signed.definition.activities
    override fun toString(): String = "LocalAppliedBranchActivityV1([REDACTED])"
}

/** Only a committed initial application or the closed authentic already-applied refusal. */
@Serializable
public data class LocalBranchActivityResultV1(
    public val code: String,
    public val candidate: LocalBranchActivityReferenceV1,
    public val applied: LocalAppliedBranchActivityV1,
) {
    init {
        require(code == LocalBranchActivityProfileV1.ACTIVATED || code == LocalBranchActivityProfileV1.ALREADY_ACTIVATED)
        require(candidate.company == applied.scope.company())
        require(code != LocalBranchActivityProfileV1.ACTIVATED || candidate == applied.reference)
    }
    override fun toString(): String = "LocalBranchActivityResultV1([REDACTED])"
}

@Serializable
public enum class LocalBranchActivityReadKindV1 { CURRENT }

/** No scope/actor, arbitrary publication selector, discovery or replacement option. */
@Serializable
public data class LocalBranchActivityQueryV1(public val kind: LocalBranchActivityReadKindV1) {
    init { require(kind == LocalBranchActivityReadKindV1.CURRENT) }
}

@Serializable
public enum class LocalBranchActivityReadStatusV1 { ABSENT, APPLIED, SOURCE_UNAVAILABLE }

/** Absent and a present explicit-empty profile are different; unavailable grants no state. */
@Serializable
public data class LocalBranchActivityReadResultV1(
    public val status: LocalBranchActivityReadStatusV1,
    public val applied: LocalAppliedBranchActivityV1?,
) {
    init {
        require(status in setOf(LocalBranchActivityReadStatusV1.ABSENT, LocalBranchActivityReadStatusV1.APPLIED,
            LocalBranchActivityReadStatusV1.SOURCE_UNAVAILABLE))
        require((status == LocalBranchActivityReadStatusV1.APPLIED) == (applied != null))
    }
    override fun toString(): String = "LocalBranchActivityReadResultV1([REDACTED])"
}

public object LocalBranchActivityCodecV1 {
    public fun activateInitial(operationId: OperationId, request: LocalActivateInitialBranchActivityV1): MutationRequest =
        MutationRequest(operationId, LocalBranchActivityProfileV1.ACTIVATE_INITIAL,
            branchActivityEncode(request, LocalActivateInitialBranchActivityV1.serializer(), LocalBranchActivityProfileV1.MAX_INPUT_BYTES))

    public fun readCurrent(): ReadRequest = ReadRequest(LocalBranchActivityProfileV1.READ_CURRENT,
        branchActivityEncode(LocalBranchActivityQueryV1(LocalBranchActivityReadKindV1.CURRENT),
            LocalBranchActivityQueryV1.serializer(), LocalBranchActivityProfileV1.MAX_QUERY_BYTES))

    public fun decodeResult(bytes: StoredBytes): LocalBranchActivityResultV1 =
        branchActivityDecode(bytes, LocalBranchActivityResultV1.serializer())

    public fun decodeRead(bytes: StoredBytes): LocalBranchActivityReadResultV1 =
        branchActivityDecode(bytes, LocalBranchActivityReadResultV1.serializer())
}

@OptIn(ExperimentalSerializationApi::class)
internal val branchActivityJson: Json = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
    coerceInputValues = false
}

internal fun <T> branchActivityEncode(
    value: T,
    serializer: KSerializer<T>,
    maximum: Int = LocalBranchActivityProfileV1.MAX_RECORD_BYTES,
): StoredBytes {
    val bytes = branchActivityJson.encodeToString(serializer, value).encodeToByteArray(throwOnInvalidSequence = true)
    try {
        require(bytes.size in 1..maximum) { "Local branch activity capacity" }
        return StoredBytes(bytes)
    } finally { bytes.fill(0) }
}

/** Strict exact representation, with no missing-field or noncanonical accepting defaults. */
internal fun <T> branchActivityDecode(
    bytes: StoredBytes,
    serializer: KSerializer<T>,
    maximum: Int = LocalBranchActivityProfileV1.MAX_RECORD_BYTES,
): T {
    require(bytes.size in 1..maximum) { "Local branch activity capacity" }
    val content = bytes.copyBytes()
    try {
        val value = branchActivityJson.decodeFromString(serializer, content.decodeToString(throwOnInvalidSequence = true))
        require(branchActivityEncode(value, serializer, maximum) == bytes) { "Noncanonical local branch activity encoding" }
        return value
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw IllegalArgumentException("Invalid local branch activity encoding")
    } finally { content.fill(0) }
}

internal fun branchActivityReference(signed: LocalSignedCompanyActivityProfileV1): LocalBranchActivityReferenceV1 {
    val definition = signed.definition
    val canonical = LocalCompanyActivityPublicationCodecV1.canonicalPublication(definition)
    return try {
        LocalBranchActivityReferenceV1(definition.company, definition.publicationId, definition.profileId,
            definition.revision, sha256(canonical), signed.signingKeyId)
    } finally { canonical.fill(0) }
}

internal fun branchActivityDigest(bytes: StoredBytes): PayloadDigest {
    val content = bytes.copyBytes()
    return try { sha256(content) } finally { content.fill(0) }
}
