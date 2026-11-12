package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Original-bound cancellation has no target account seed or target effects. Only the
 * unchanged, authenticated creator dependencies belong to its chronological replay. */
@Serializable
internal data class RuntimeEmployeeCreationCancellationDependenciesV1(
    val operator: RuntimeCommandAuthorizationDependenciesV1,
    val originalDigest: PayloadDigest,
) {
    override fun toString(): String = "RuntimeEmployeeCreationCancellationDependenciesV1([REDACTED])"
}

/** Distinct one-INSERT tombstone DATA. This profile cannot stand for ACCOUNT_CREATED or
 * ordinary administration cancellation. It remains an unaccepted cold-history family. */
@Serializable
internal data class RuntimeEmployeeCreationCancellationEvidenceV1(
    val schema: String = PROFILE,
    val originalHex: String,
    val authorization: RuntimeCommandAuthorizationSnapshotV1,
    val at: MonotonicMoment,
    val images: List<RuntimeAuthenticationImageV1>,
) {
    val purpose: RuntimeWritePurposeV2 get() = RuntimeWritePurposeV2.ACCOUNT_ADMINISTRATION

    fun original(): LocalDelegatedEmployeeCreationV1 {
        require(originalHex.length in 2..MAXIMUM_ORIGINAL_BYTES * 2 && originalHex.length % 2 == 0 &&
            originalHex.all { it in '0'..'9' || it in 'a'..'f' })
        val bytes = ByteArray(originalHex.length / 2) { index ->
            originalHex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return try { LocalDelegatedEmployeeCreationV1.decode(bytes) } finally { bytes.fill(0) }
    }

    fun event(): LocalAuthEvent {
        val request = original()
        return LocalAuthEvent(request.scope, request.requestId, LocalAuthEventKind.ACCOUNT_CREATION_CANCELLED,
            request.actorId, request.operatorId, authorization.sessionId, null, at, request.contentDigest)
    }

    fun protectedFinalDeclaration(): NativeHistoryProtectedFinalDeclaration {
        validateStructure()
        val request = original()
        return NativeHistoryProtectedFinalDeclaration(request.requestId, request.contentDigest, request.operatorId,
            authorization.sessionId, authorization.access.issuerContextId, authorization.access.issuedAt,
            at, authorization.access.expiresAtElapsedMillis)
    }

    /** Structural/canonical comparison only. Neither these bytes nor a matching image hash
     * prove original absence, historical creator authority, native final or committed close. */
    fun validateStructure() {
        require(schema == PROFILE)
        authorization.validate()
        val request = original()
        require(request.scope == authorization.scope && request.operatorId == authorization.actorId)
        val access = authorization.access
        require(at.bootId == access.observedAt.bootId && at.elapsedMillis >= access.observedAt.elapsedMillis &&
            at.elapsedMillis < access.expiresAtElapsedMillis)
        val image = images.single().also { it.validate() }
        require(image.table == RuntimeHistoryTableV2.AUTH_EVENT && image.recordId == request.requestId &&
            image.dimension == null && image.revision == Revision(0) && image.mutation == RuntimeHistoryMutationKindV2.INSERT)
        val bytes = LocalAuthenticationCodec.encodeEvent(event())
        try { require(image.plaintextDigest == sha256(bytes)) } finally { bytes.fill(0) }
    }

    override fun toString(): String = "RuntimeEmployeeCreationCancellationEvidenceV1([REDACTED])"

    companion object {
        const val PROFILE: String = "ukkera.runtime-employee-creation-cancellation-evidence/v1"
        const val MAX_IMAGES: Int = 1
        const val MAXIMUM_ORIGINAL_BYTES: Int = SelfOwnedNativeHistoryWireV2.MAX_CAPSULE_BYTES / 4
    }
}

/** Only the actual absent-prior writer supplies this capture. Prior-result reads never
 * create a new tombstone; no target census, credential, session or bearer is manufactured. */
internal class RuntimeEmployeeCreationCancellationCapture private constructor(
    private val originalHex: String,
    private val original: LocalDelegatedEmployeeCreationV1,
    private val authorization: RuntimeCommandAuthorizationSnapshotV1,
    private val at: MonotonicMoment,
) {
    val scope: BranchScope get() = original.scope
    val actorId: DomainId get() = original.operatorId
    val requestId: DomainId get() = original.requestId
    val purpose: RuntimeWritePurposeV2 get() = RuntimeWritePurposeV2.ACCOUNT_ADMINISTRATION
    val semanticDigest: PayloadDigest get() = original.contentDigest
    val images = mutableListOf<RuntimeAuthenticationImageV1>()

    fun evidence(): RuntimeEmployeeCreationCancellationEvidenceV1 = RuntimeEmployeeCreationCancellationEvidenceV1(
        originalHex = originalHex, authorization = authorization, at = at, images = images.toList())
        .also { it.validateStructure() }

    override fun toString(): String = "RuntimeEmployeeCreationCancellationCapture([REDACTED])"

    companion object {
        fun capture(principal: LocalEmployeePrincipal, facts: RoomLocalAuthorizationFacts,
            permitted: AuthorizedAction, original: LocalDelegatedEmployeeCreationV1,
            observed: RoomLocalAuthorizationObservation, event: LocalAuthEvent): RuntimeEmployeeCreationCancellationCapture {
            require(principal.authenticationPurpose == AuthenticationPurpose.INTERACTIVE &&
                principal.scope == original.scope && principal.actorId == original.operatorId &&
                permitted.isBoundTo(original.authorizationRequirement(), principal.actorId) &&
                permitted.approvalId == null && permitted.principalKind == PrincipalKind.LOCAL_EMPLOYEE &&
                permitted.authenticationId == principal.sessionId && permitted.generation == principal.sessionGeneration &&
                permitted.policyRevision == facts.policy.revision)
            val snapshot = RuntimeCommandAuthorizationSnapshotV1.capture(principal, facts, observed)
            val bytes = original.copyWireBytes()
            val hex = try {
                require(bytes.size in 1..RuntimeEmployeeCreationCancellationEvidenceV1.MAXIMUM_ORIGINAL_BYTES)
                val alphabet = "0123456789abcdef"
                buildString(bytes.size * 2) { for (byte in bytes) {
                    val value = byte.toInt() and 255
                    append(alphabet[value ushr 4]); append(alphabet[value and 15])
                } }
            } finally { bytes.fill(0) }
            val capture = RuntimeEmployeeCreationCancellationCapture(hex, original, snapshot, event.at)
            val expected = RuntimeEmployeeCreationCancellationEvidenceV1(originalHex = hex,
                authorization = snapshot, at = event.at, images = emptyList()).event()
            val actualBytes = LocalAuthenticationCodec.encodeEvent(event)
            try {
                val expectedBytes = LocalAuthenticationCodec.encodeEvent(expected)
                try { require(actualBytes.contentEquals(expectedBytes)) } finally { expectedBytes.fill(0) }
            } finally { actualBytes.fill(0) }
            return capture
        }
    }
}

internal object RuntimeEmployeeCreationCancellationWireV1 {
    private val json = Json {
        encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false; isLenient = false
        coerceInputValues = false; allowSpecialFloatingPointValues = false; allowStructuredMapKeys = false; prettyPrint = false
    }
    private val prefix = ("{\"schema\":\"" + RuntimeEmployeeCreationCancellationEvidenceV1.PROFILE + "\",").encodeToByteArray()
    fun hasProfile(bytes: ByteArray): Boolean = bytes.size in prefix.size..RuntimeHistoryProfileV2.MAX_CAPSULE_BYTES &&
        prefix.indices.all { bytes[it] == prefix[it] }
    fun encode(value: RuntimeEmployeeCreationCancellationEvidenceV1): ByteArray = guarded {
        value.validateStructure()
        val bytes = json.encodeToString(RuntimeEmployeeCreationCancellationEvidenceV1.serializer(), value)
            .encodeToByteArray(throwOnInvalidSequence = true)
        if (bytes.size !in 1..RuntimeHistoryProfileV2.MAX_CAPSULE_BYTES) { bytes.fill(0); historyCapacityV2(false) }
        bytes
    }
    fun decode(bytes: ByteArray): RuntimeEmployeeCreationCancellationEvidenceV1 = guarded {
        historyCapacityV2(bytes.size in 1..RuntimeHistoryProfileV2.MAX_CAPSULE_BYTES)
        val value = json.decodeFromString(RuntimeEmployeeCreationCancellationEvidenceV1.serializer(),
            bytes.decodeToString(throwOnInvalidSequence = true))
        val canonical = encode(value)
        try { historyShapeV2(canonical.contentEquals(bytes)) } finally { canonical.fill(0) }
        value
    }
    fun dependencies(value: RuntimeEmployeeCreationCancellationEvidenceV1): ByteArray = json.encodeToString(
        RuntimeEmployeeCreationCancellationDependenciesV1.serializer(), RuntimeEmployeeCreationCancellationDependenciesV1(
            value.authorization.dependencies, value.original().contentDigest)).encodeToByteArray()
    private inline fun <T> guarded(block: () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: RuntimeHistoryFormatRejectedV2) { throw failure }
    catch (_: Exception) { throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED) }
}
