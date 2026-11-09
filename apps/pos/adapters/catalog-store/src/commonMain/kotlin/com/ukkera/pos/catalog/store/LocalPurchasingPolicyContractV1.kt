package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.security.LocalCompanyPolicyWireV1
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.PurchasingPolicyPublicationV1
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * New bounded policy-retention profile, not an arrival/evidence acceptance profile. The whole
 * original A0a envelope is losslessly retained. Oversized originals are refused, not rewritten.
 * Existing signing, SKU, arrival, saved-intent and database schema identities are unchanged.
 * Registries lacking these new kinds/commands are not qualified to open their retained effects.
 */
public object LocalPurchasingPolicyProfileV1 {
    public val ACTIVATE: CommandKey = CommandKey("local.purchasing.policy.activate", 1)
    public val READ: ReadKey = ReadKey("local.purchasing.policy.read", 1)
    public const val ACTIVE: String = "local.purchasing.policy-active"
    public const val PUBLICATION: String = "local.purchasing.policy-publication"
    public const val POLICY_CLAIM: String = "local.purchasing.policy-identity"
    public const val PUBLICATION_CLAIM: String = "local.purchasing.policy-publication"
    public const val VERSION_CLAIM: String = "local.purchasing.policy-version"
    public const val MAX_POLICY_WIRE_BYTES: Int = 14 * 1024
    public const val MAX_INPUT_BYTES: Int = 32 * 1024
    public const val MAX_QUERY_BYTES: Int = 4 * 1024
    public const val MAX_RECORD_BYTES: Int = 64 * 1024
    public fun schema(kind: String): String = "$kind/v1"
}

/** Coordinate only. It neither qualifies selectors nor replaces the complete original policy. */
@Serializable
public data class LocalPurchasingPolicyReferenceV1(
    public val policyId: DomainId,
    public val publicationId: DomainId,
    public val revision: Revision,
    public val bodyDigest: PayloadDigest,
) {
    init { require(revision.value >= 1) }
    override fun toString(): String = "LocalPurchasingPolicyReferenceV1([REDACTED])"
}

@Serializable
public data class LocalActivatePurchasingPolicyV1(
    public val expectedActiveStorageRevision: Revision?,
    /** Entire canonical original binary signed envelope, encoded losslessly as lowercase hex. */
    public val policyWireHex: String,
) {
    init {
        require(expectedActiveStorageRevision == null || expectedActiveStorageRevision.value >= 1)
        requirePurchasingPolicyHex(policyWireHex)
    }
    override fun toString(): String = "LocalActivatePurchasingPolicyV1([REDACTED])"
}

/** This profile has no selector-qualification transition or evidence/arrival acceptance path. */
@Serializable
public enum class LocalPurchasingPolicyQualificationV1 { UNQUALIFIED_SELECTORS }

@Serializable
public enum class LocalPurchasingPolicyFailureV1 { POLICY_UNAVAILABLE, STALE_REVISION, PUBLICATION_ALREADY_USED }

@Serializable
public data class LocalPurchasingPolicyResultV1(
    public val code: String,
    public val id: DomainId?,
    public val storageRevision: Revision?,
    public val policy: LocalPurchasingPolicyReferenceV1?,
    public val failure: LocalPurchasingPolicyFailureV1? = null,
    public val qualification: LocalPurchasingPolicyQualificationV1 = LocalPurchasingPolicyQualificationV1.UNQUALIFIED_SELECTORS,
) { override fun toString(): String = "LocalPurchasingPolicyResultV1([UNQUALIFIED, REDACTED])" }

@Serializable
public enum class LocalPurchasingPolicyReadKindV1 { ACTIVE_POLICY, POLICY_PUBLICATION }

/** Direct identity only; there is no discovery range or raw policy read before authorization. */
@Serializable
public data class LocalPurchasingPolicyQueryV1(
    public val kind: LocalPurchasingPolicyReadKindV1,
    public val id: DomainId? = null,
) {
    init { require((kind == LocalPurchasingPolicyReadKindV1.ACTIVE_POLICY) == (id == null)) }
}

/** Documentary active pointer/original bytes, not a promise of current validity or authority. */
@Serializable
public data class LocalPurchasingPolicyReadResultV1(
    public val policy: LocalPurchasingPolicyReferenceV1?,
    public val policyWireHex: String?,
    public val failure: LocalPurchasingPolicyFailureV1? = null,
    public val qualification: LocalPurchasingPolicyQualificationV1 = LocalPurchasingPolicyQualificationV1.UNQUALIFIED_SELECTORS,
) { override fun toString(): String = "LocalPurchasingPolicyReadResultV1([UNQUALIFIED, REDACTED])" }

public object LocalPurchasingPolicyCodecV1 {
    public fun activate(operationId: OperationId, request: LocalActivatePurchasingPolicyV1): MutationRequest =
        MutationRequest(operationId, LocalPurchasingPolicyProfileV1.ACTIVATE,
            purchasingPolicyEncode(request, LocalActivatePurchasingPolicyV1.serializer(), LocalPurchasingPolicyProfileV1.MAX_INPUT_BYTES))
    public fun query(request: LocalPurchasingPolicyQueryV1): ReadRequest =
        ReadRequest(LocalPurchasingPolicyProfileV1.READ,
            purchasingPolicyEncode(request, LocalPurchasingPolicyQueryV1.serializer(), LocalPurchasingPolicyProfileV1.MAX_QUERY_BYTES))
    public fun decodeResult(bytes: StoredBytes): LocalPurchasingPolicyResultV1 =
        purchasingPolicyDecode(bytes, LocalPurchasingPolicyResultV1.serializer())
    public fun decodeRead(bytes: StoredBytes): LocalPurchasingPolicyReadResultV1 =
        purchasingPolicyDecode(bytes, LocalPurchasingPolicyReadResultV1.serializer())
}

internal val purchasingPolicyJson: Json = Json {
    encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false
}

internal fun <T> purchasingPolicyEncode(value: T, serializer: KSerializer<T>, maximum: Int = LocalPurchasingPolicyProfileV1.MAX_RECORD_BYTES): StoredBytes {
    val bytes = purchasingPolicyJson.encodeToString(serializer, value).encodeToByteArray(throwOnInvalidSequence = true)
    require(bytes.size in 1..maximum) { "Local purchasing policy capacity" }
    return StoredBytes(bytes)
}

internal fun <T> purchasingPolicyDecode(bytes: StoredBytes, serializer: KSerializer<T>, maximum: Int = LocalPurchasingPolicyProfileV1.MAX_RECORD_BYTES): T {
    require(bytes.size in 1..maximum) { "Local purchasing policy capacity" }
    try { return purchasingPolicyJson.decodeFromString(serializer, bytes.copyBytes().decodeToString(throwOnInvalidSequence = true)) }
    catch (_: Exception) { throw IllegalArgumentException("Invalid local purchasing policy encoding") }
}

internal fun requirePurchasingPolicyHex(value: String) {
    require(value.length in 2..LocalPurchasingPolicyProfileV1.MAX_POLICY_WIRE_BYTES * 2 && value.length % 2 == 0 &&
        value.all { it in '0'..'9' || it in 'a'..'f' }) { "Invalid bounded purchasing policy bytes" }
}

internal fun purchasingPolicyWireBytes(value: String): ByteArray {
    requirePurchasingPolicyHex(value)
    return ByteArray(value.length / 2) { index ->
        ((value[index * 2].digitToInt(16) shl 4) or value[index * 2 + 1].digitToInt(16)).toByte()
    }
}

internal fun purchasingPolicyReference(policy: PurchasingPolicyPublicationV1): LocalPurchasingPolicyReferenceV1 {
    val bytes = LocalCompanyPolicyWireV1.encodePolicyBody(policy)
    return try { LocalPurchasingPolicyReferenceV1(policy.policyId, policy.publicationId, policy.revision, sha256(bytes)) }
    finally { bytes.fill(0) }
}
