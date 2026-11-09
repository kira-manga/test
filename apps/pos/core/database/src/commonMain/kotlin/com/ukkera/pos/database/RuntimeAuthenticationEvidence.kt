package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A retained format, not an RPC command, supplied authorization proof or session credential. */
@Serializable
internal enum class RuntimeAuthenticationActionV1 {
    SIGN_IN, REFRESH, LOGOUT;

    val purpose: RuntimeWritePurposeV2 get() = when (this) {
        SIGN_IN -> RuntimeWritePurposeV2.SESSION_ISSUE
        REFRESH -> RuntimeWritePurposeV2.SESSION_REFRESH
        LOGOUT -> RuntimeWritePurposeV2.SESSION_LOGOUT
    }
}

/** Hash of the actual canonical plaintext passed to protection on the SAME writer loan.
 * Native trigger images independently supply the mutation/table/key/order and encrypted row.
 * A digest in this list alone never proves that its row exists or that a transition is legal. */
@Serializable
internal data class RuntimeAuthenticationImageV1(
    val tableTag: Int,
    val recordId: DomainId,
    val dimension: String? = null,
    val revision: Revision,
    val plaintextDigest: PayloadDigest,
) {
    val table: RuntimeHistoryTableV2 get() = RuntimeHistoryTableV2.fromTag(tableTag)
    val mutation: RuntimeHistoryMutationKindV2 get() = when (table) {
        RuntimeHistoryTableV2.ACCOUNT, RuntimeHistoryTableV2.THROTTLE -> RuntimeHistoryMutationKindV2.UPDATE
        else -> RuntimeHistoryMutationKindV2.INSERT
    }

    fun validate() {
        require(table.hasAuthenticationImages)
        val keyDimension = dimension
        if (table == RuntimeHistoryTableV2.THROTTLE) require(keyDimension == "account" ||
            (keyDimension != null && keyDimension.startsWith("device:") && keyDimension.length == 43 &&
                runCatching { DomainId(keyDimension.substring(7)) }.isSuccess))
        else require(dimension == null)
        if (mutation == RuntimeHistoryMutationKindV2.INSERT) require(revision == Revision(0))
    }

    fun key(scope: BranchScope): ByteArray {
        validate()
        return runtimeSecurityKey(table, listOf(scope.storageKey(), recordId.value) + listOfNotNull(dimension))
    }

    fun matches(binding: ProtectedLocalBinding): Boolean =
        revision == binding.revision && recordId == binding.key.recordId && table.protectedTable == binding.key.table &&
            dimension == (binding.key as? ProtectedLocalRecordKey.Throttle)?.dimensionKey

    companion object {
        fun capture(binding: ProtectedLocalBinding, digest: PayloadDigest): RuntimeAuthenticationImageV1 {
            val table = RuntimeHistoryTableV2.entries.single { it.hasAuthenticationImages && it.protectedTable == binding.key.table }
            return RuntimeAuthenticationImageV1(table.tag, binding.key.recordId,
                (binding.key as? ProtectedLocalRecordKey.Throttle)?.dimensionKey, binding.revision, digest).also { it.validate() }
        }
        fun capture(key: ProtectedLocalRecordKey, revision: Revision, bytes: ByteArray): RuntimeAuthenticationImageV1 =
            try { capture(ProtectedLocalBinding(key, revision), sha256(bytes)) } finally { bytes.fill(0) }
    }
}

/**
 * Original invocation and actual dependencies; no password, verifier or token secret. Access
 * and refresh are strict existing issuance encodings (random-secret HASHES only). Images are
 * supplied by protected sealing, not by the authentication service's proposed result.
 * Maximum 256 archived sessions + three account updates + two throttle updates + three new
 * immutable records. The retained profile is deliberately bounded independently of commerce.
 */
@Serializable
internal data class RuntimeAuthenticationEvidenceV1(
    val schema: String = PROFILE,
    val scope: BranchScope,
    val actorId: DomainId,
    val deviceId: DomainId,
    val eventId: DomainId,
    val action: RuntimeAuthenticationActionV1,
    val issuerContextId: DomainId,
    val at: MonotonicMoment,
    val newSessionId: DomainId?,
    val previousSessionId: DomainId?,
    val explicitDeviceMove: Boolean,
    val dependencies: RuntimeThrottleDependenciesV1,
    val throttle: RuntimeThrottleEvidenceV1?,
    val denial: SessionTransitionDenial?,
    val access: String?,
    val refresh: String?,
    val images: List<RuntimeAuthenticationImageV1>,
) {
    val purpose: RuntimeWritePurposeV2 get() = action.purpose
    override fun toString(): String = "RuntimeAuthenticationEvidenceV1([REDACTED])"

    fun accessValue(): LocalAccessIssuance? = access?.let { encoded ->
        val bytes = encoded.encodeToByteArray(throwOnInvalidSequence = true)
        try { ProtectedLocalSecurityCodec.decodeIssuance(ProtectedLocalBinding(
            ProtectedLocalRecordKey.SessionIssuance(scope, requireNotNull(newSessionId)), Revision(0)), bytes) }
        finally { bytes.fill(0) }
    }
    fun refreshValue(): LocalRefreshIssuance? = refresh?.let { encoded ->
        val bytes = encoded.encodeToByteArray(throwOnInvalidSequence = true)
        try { LocalAuthenticationCodec.decodeRefresh(ProtectedLocalBinding(
            ProtectedLocalRecordKey.RefreshIssuance(scope, requireNotNull(newSessionId)), Revision(0)), bytes) }
        finally { bytes.fill(0) }
    }

    fun validate() {
        require(schema == PROFILE && dependencies.configuration != null && images.size <= MAX_IMAGES)
        images.forEach { it.validate() }
        require(!explicitDeviceMove || action == RuntimeAuthenticationActionV1.SIGN_IN)
        require((action == RuntimeAuthenticationActionV1.LOGOUT) == (newSessionId == null))
        require((action == RuntimeAuthenticationActionV1.SIGN_IN) == (previousSessionId == null))
        require((action == RuntimeAuthenticationActionV1.SIGN_IN) == (throttle != null))
        throttle?.let {
            it.validate()
            require(it.scope == scope && it.actorId == actorId && it.deviceId == deviceId &&
                it.outcome == CredentialAttemptOutcome.MATCH && it.denial == null && it.dependencies == dependencies &&
                it.at.bootId == at.bootId && it.at.elapsedMillis <= at.elapsedMillis)
            val started = requireNotNull(it.accountBefore.attemptStartedAt)
            require(started.bootId == at.bootId && at.elapsedMillis >= started.elapsedMillis &&
                at.elapsedMillis - started.elapsedMillis < it.policy.attemptTimeoutMillis)
        }
        val issued = action != RuntimeAuthenticationActionV1.LOGOUT && denial == null
        require((access != null) == issued && (refresh != null) == issued)
        if (action == RuntimeAuthenticationActionV1.LOGOUT) require(denial == null)
        require((access?.length ?: 0) <= 4096 && (refresh?.length ?: 0) <= 4096)
        val issuedAccess = accessValue()
        val issuedRefresh = refreshValue()
        issuedAccess?.let { require(it.actorId == actorId && it.deviceId == deviceId &&
            it.issuerContextId == issuerContextId && it.issuedAt == at) }
        issuedRefresh?.let { refresh ->
            val access = requireNotNull(issuedAccess)
            require(refresh.actorId == actorId && refresh.deviceId == deviceId &&
                refresh.issuerContextId == issuerContextId && refresh.issuedAt == at &&
                refresh.generation == access.generation &&
                refresh.credentialRevisionAtIssue == access.credentialRevisionAtIssue &&
                refresh.admissionRevisionAtIssue == access.admissionRevisionAtIssue &&
                access.expiresAtElapsedMillis <= refresh.expiresAtElapsedMillis)
        }
    }

    companion object {
        const val PROFILE = "ukkera.runtime-authentication-evidence/v1"
        const val MAX_IMAGES = 264
        fun encoded(bytes: ByteArray): String = try { bytes.decodeToString(throwOnInvalidSequence = true) } finally { bytes.fill(0) }
    }
}

/** Private same-transaction capture. It cannot escape as an enrollment or login capability. */
internal class RuntimeAuthenticationCapture(
    val scope: BranchScope,
    val actorId: DomainId,
    val deviceId: DomainId,
    val eventId: DomainId,
    val action: RuntimeAuthenticationActionV1,
    val at: LocalAuthenticationObservation,
    val newSessionId: DomainId?,
    val previousSessionId: DomainId?,
    val explicitDeviceMove: Boolean,
    val dependencies: RuntimeThrottleDependenciesV1,
) {
    val images = mutableListOf<RuntimeAuthenticationImageV1>()
}

internal object RuntimeAuthenticationEvidenceCodec {
    private const val MAX_BYTES = 192 * 1024
    private val json = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false }
    private val prefix = ("{\"schema\":\"" + RuntimeAuthenticationEvidenceV1.PROFILE + "\",").encodeToByteArray()
    fun hasProfile(bytes: ByteArray): Boolean = bytes.size in prefix.size..MAX_BYTES && prefix.indices.all { bytes[it] == prefix[it] }
    fun encode(value: RuntimeAuthenticationEvidenceV1): ByteArray {
        value.validate()
        return json.encodeToString(RuntimeAuthenticationEvidenceV1.serializer(), value).encodeToByteArray(throwOnInvalidSequence = true)
            .also { historyCapacityV2(it.size in 1..MAX_BYTES) }
    }
    fun decode(bytes: ByteArray): RuntimeAuthenticationEvidenceV1 {
        historyCapacityV2(bytes.size in 1..MAX_BYTES)
        val value = json.decodeFromString(RuntimeAuthenticationEvidenceV1.serializer(), bytes.decodeToString(throwOnInvalidSequence = true))
        val canonical = encode(value)
        try { historyShapeV2(canonical.contentEquals(bytes)) } finally { canonical.fill(0) }
        return value
    }
    fun dependencies(value: RuntimeAuthenticationEvidenceV1): ByteArray = json
        .encodeToString(RuntimeThrottleDependenciesV1.serializer(), value.dependencies).encodeToByteArray()
}

internal fun runtimeSecurityKey(table: RuntimeHistoryTableV2, parts: List<String>): ByteArray {
    val cells = parts.map { part ->
        val bytes = part.encodeToByteArray(throwOnInvalidSequence = true)
        try { NativeHistoryPhysicalCellV2.Text(bytes) } finally { bytes.fill(0) }
    }
    return try { RuntimeHistoryRowCodecV2.encodeKey(table, cells) } finally { cells.forEach { it.clear() } }
}
