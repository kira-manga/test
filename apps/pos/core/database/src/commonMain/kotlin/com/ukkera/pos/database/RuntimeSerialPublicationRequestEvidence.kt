package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Exact protected dependency fingerprints, not a transferable employee grant. */
@Serializable
internal data class RuntimeSerialPublicationRequestDependenciesV1(
    val authorization: RuntimeCommandAuthorizationDependenciesV1,
    val originRequestId: DomainId?,
    val originRequestDigest: PayloadDigest?,
) {
    override fun toString(): String = "RuntimeSerialPublicationRequestDependenciesV1([REDACTED])"
}

/**
 * One immutable private request/access audit. Full origin bytes stay in this bounded history
 * profile, not the small AUTH_EVENT plaintext. Only the actual protected writer and its
 * retained native final decision establish provenance; these values remain inert DATA.
 */
@Serializable
internal data class RuntimeSerialPublicationRequestEvidenceV1(
    val schema: String = PROFILE,
    val request: String,
    val requirement: RuntimeCommandRequirementV1,
    val authorization: RuntimeCommandAuthorizationSnapshotV1,
    val at: MonotonicMoment,
    val images: List<RuntimeApprovalImageV1>,
) {
    val purpose: RuntimeWritePurposeV2 get() =
        RuntimeWritePurposeV2.fromTag(NativeSerialPrivatePublicationRequestCodecV1.PURPOSE_TAG)
    val semanticDigest: PayloadDigest get() = NativeSerialPrivatePublicationRequestCodecV1.digest(requestValue())
    val dependencies: RuntimeSerialPublicationRequestDependenciesV1 get() {
        val value = requestValue()
        return RuntimeSerialPublicationRequestDependenciesV1(authorization.dependencies,
            value.originRequestId, value.originRequestDigest)
    }

    fun requestValue(): NativeSerialPrivatePublicationRequestV1 {
        val bytes = runtimeSerialRequestBytes(request)
        return try { NativeSerialPrivatePublicationRequestCodecV1.decode(bytes) }
        finally { bytes.fill(0) }
    }

    fun event(): LocalAuthEvent = runtimeSerialPublicationEvent(requestValue(), authorization, at)

    fun matchesEvent(value: LocalAuthEvent): Boolean {
        val expected = LocalAuthenticationCodec.encodeEvent(event())
        return try {
            val actual = LocalAuthenticationCodec.encodeEvent(value)
            try { actual.contentEquals(expected) && images.singleOrNull()?.plaintextDigest == sha256(actual) }
            finally { actual.fill(0) }
        } finally { expected.fill(0) }
    }

    fun protectedFinalDeclaration(): NativeHistoryProtectedFinalDeclaration {
        validate()
        val value = requestValue()
        val access = authorization.access
        return NativeHistoryProtectedFinalDeclaration(value.requestId,
            NativeSerialPrivatePublicationRequestCodecV1.digest(value), authorization.actorId,
            authorization.sessionId, access.issuerContextId, access.issuedAt, at,
            access.expiresAtElapsedMillis)
    }

    /** Native reconstructs the exact closed intent and matches an actual retained decision. */
    fun capsule(): NativeSerialPrivateRequestCapsuleV1 {
        val requestBytes = runtimeSerialRequestBytes(request)
        try {
            val dependencyBytes = RuntimeSerialPublicationRequestEvidenceCodec.dependencies(this)
            try {
                val factBytes = RuntimeSerialPublicationRequestEvidenceCodec.encode(this)
                return try { NativeSerialPrivateRequestCapsuleV1(requestBytes, dependencyBytes, factBytes) }
                finally { factBytes.fill(0) }
            } finally { dependencyBytes.fill(0) }
        } finally { requestBytes.fill(0) }
    }

    fun validate() {
        require(schema == PROFILE)
        val value = requestValue()
        authorization.validate()
        require(value.scope == authorization.scope && at == authorization.access.observedAt &&
            requirement.value() == runtimeSerialPublicationRequirement(value, authorization.actorId))
        require(images.size == MAX_IMAGES)
        val image = images.single().also { it.validate() }
        require(image.table == RuntimeHistoryTableV2.AUTH_EVENT && image.recordId == value.requestId &&
            image.mutation == RuntimeHistoryMutationKindV2.INSERT && image.revision == Revision(0))
        val eventBytes = LocalAuthenticationCodec.encodeEvent(runtimeSerialPublicationEvent(value, authorization, at))
        try { require(image.plaintextDigest == sha256(eventBytes)) }
        finally { eventBytes.fill(0) }
    }

    override fun toString(): String = "RuntimeSerialPublicationRequestEvidenceV1([REDACTED])"

    companion object {
        const val PROFILE: String = "ukkera.runtime-serial-private-publication-request-evidence/v1"
        const val MAX_IMAGES: Int = 1
    }
}

/** Memory-only capture attached to the actual writer before its one protected image. */
internal class RuntimeSerialPublicationRequestCapture private constructor(
    private val encodedRequest: String,
    private val requirement: RuntimeCommandRequirementV1,
    val authorization: RuntimeCommandAuthorizationSnapshotV1,
    private val at: MonotonicMoment,
    private val origin: RuntimeSerialPublicationRequestEvidenceV1?,
) {
    val request: NativeSerialPrivatePublicationRequestV1 get() = requestValue()
    val observation: RoomLocalAuthorizationObservation get() =
        RoomLocalAuthorizationObservation(authorization.access.issuerContextId, at)
    val scope: BranchScope get() = authorization.scope
    val actorId: DomainId get() = authorization.actorId
    val requestId: DomainId get() = requestValue().requestId
    val purpose: RuntimeWritePurposeV2 get() =
        RuntimeWritePurposeV2.fromTag(NativeSerialPrivatePublicationRequestCodecV1.PURPOSE_TAG)
    val semanticDigest: PayloadDigest get() = NativeSerialPrivatePublicationRequestCodecV1.digest(requestValue())
    val images: MutableList<RuntimeApprovalImageV1> = mutableListOf()

    private fun requestValue(): NativeSerialPrivatePublicationRequestV1 {
        val bytes = runtimeSerialRequestBytes(encodedRequest)
        return try { NativeSerialPrivatePublicationRequestCodecV1.decode(bytes) }
        finally { bytes.fill(0) }
    }

    fun evidence(): RuntimeSerialPublicationRequestEvidenceV1 = RuntimeSerialPublicationRequestEvidenceV1(
        request = encodedRequest, requirement = requirement, authorization = authorization, at = at,
        images = images.toList()).also { it.validate() }

    fun event(): LocalAuthEvent = runtimeSerialPublicationEvent(requestValue(), authorization, at)

    fun originCapsule(): NativeSerialPrivateRequestCapsuleV1 = (origin ?: evidence()).capsule()

    override fun toString(): String = "RuntimeSerialPublicationRequestCapture([REDACTED])"

    companion object {
        fun capture(
            principal: LocalEmployeePrincipal,
            request: NativeSerialPrivatePublicationRequestV1,
            authorization: RuntimeCommandAuthorizationSnapshotV1,
            event: LocalAuthEvent,
            origin: RuntimeSerialPublicationRequestEvidenceV1?,
            permitted: AuthorizedAction?,
            recovered: ResultRecoveryDecision.Allowed?,
        ): RuntimeSerialPublicationRequestCapture {
            authorization.validate()
            require(authorization.scope == principal.scope && authorization.actorId == principal.actorId &&
                authorization.sessionId == principal.sessionId && authorization.deviceId == principal.deviceId &&
                authorization.generation == principal.generation && authorization.principalKind == principal.kind &&
                authorization.authenticationPurpose == principal.authenticationPurpose &&
                authorization.admissionRevision == principal.admissionRevision && request.scope == principal.scope)
            requireRuntimeSerialPublicationOrigin(request, origin, authorization.actorId)
            val requirement = runtimeSerialPublicationRequirement(request, authorization.actorId)
            when (request.operation) {
                NativeSerialPrivatePublicationOperationV1.ORIGIN_PREPARE,
                NativeSerialPrivatePublicationOperationV1.PREPARE -> {
                    val allowed = requireNotNull(permitted)
                    require(recovered == null && allowed.isBoundTo(requirement, principal.actorId) &&
                        allowed.approvalId == null && allowed.principalKind == principal.kind &&
                        allowed.authenticationId == principal.sessionId && allowed.generation == principal.generation &&
                        allowed.policyRevision == authorization.policyRevision)
                }
                NativeSerialPrivatePublicationOperationV1.REVIEW,
                NativeSerialPrivatePublicationOperationV1.RECOVER,
                NativeSerialPrivatePublicationOperationV1.STATUS -> {
                    val allowed = requireNotNull(recovered)
                    require(permitted == null && allowed.access == ResultRecoveryAccess.ORIGINAL_ACTOR &&
                        allowed.scope == principal.scope && allowed.operationId.value == request.originRequestId?.value &&
                        allowed.originalActorId == principal.actorId && allowed.readingActorId == principal.actorId)
                }
            }
            val at = authorization.access.observedAt
            val expected = LocalAuthenticationCodec.encodeEvent(runtimeSerialPublicationEvent(request, authorization, at))
            try {
                val actual = LocalAuthenticationCodec.encodeEvent(event)
                try { require(actual.contentEquals(expected)) } finally { actual.fill(0) }
            } finally { expected.fill(0) }
            val bytes = NativeSerialPrivatePublicationRequestCodecV1.encode(request)
            val encoded = try { runtimeSerialRequestHex(bytes) } finally { bytes.fill(0) }
            return RuntimeSerialPublicationRequestCapture(encoded, RuntimeCommandRequirementV1.capture(requirement),
                authorization, at, origin)
        }
    }
}

/** Private origin affinity is stricter than a supervisor result projection. */
internal fun requireRuntimeSerialPublicationOrigin(request: NativeSerialPrivatePublicationRequestV1,
    origin: RuntimeSerialPublicationRequestEvidenceV1?, actorId: DomainId) {
    if (request.operation == NativeSerialPrivatePublicationOperationV1.ORIGIN_PREPARE) {
        require(origin == null)
        return
    }
    val retained = requireNotNull(origin).also { it.validate() }
    val original = retained.requestValue()
    require(original.operation == NativeSerialPrivatePublicationOperationV1.ORIGIN_PREPARE &&
        retained.authorization.actorId == actorId && original.scope == request.scope &&
        original.originalAllocationDigest == request.originalAllocationDigest && original.kind == request.kind &&
        original.publicationId == request.publicationId && original.entityId == request.entityId &&
        original.contentDigest == request.contentDigest && original.requestId == request.originRequestId &&
        NativeSerialPrivatePublicationRequestCodecV1.digest(original) == request.originRequestDigest)
}

/** Preparation retains the existing catalog grant; recovery uses the narrower original route. */
internal fun runtimeSerialPublicationRequirement(request: NativeSerialPrivatePublicationRequestV1,
    originalActor: DomainId): AuthorizationRequirement = when (request.operation) {
    NativeSerialPrivatePublicationOperationV1.ORIGIN_PREPARE,
    NativeSerialPrivatePublicationOperationV1.PREPARE -> AuthorizationRequirement(
        Permission(SecurityAction.CATALOG_ACTIVATE, PermissionVerb.ADMINISTER), request.scope,
        ActionBinding(request.entityId, Revision(0), NativeSerialPrivatePublicationRequestCodecV1.digest(request),
            OperationId(request.requestId.value), "local-serial-publication.prepare"))
    NativeSerialPrivatePublicationOperationV1.REVIEW,
    NativeSerialPrivatePublicationOperationV1.RECOVER,
    NativeSerialPrivatePublicationOperationV1.STATUS -> {
        val originRequestId = requireNotNull(request.originRequestId)
        val scope = request.scope
        val query = ("result-recovery-v1\n${scope.organizationId.value}\n${scope.companyId.value}\n" +
            "${scope.branchId.value}\n${originRequestId.value}\n${originalActor.value}").encodeToByteArray()
        val digest = try { sha256(query) } finally { query.fill(0) }
        AuthorizationRequirement(Permission(SecurityAction.OPERATION_RESULT_RECOVERY, PermissionVerb.PERFORM), scope,
            ActionBinding(originRequestId, Revision(0), digest, OperationId(originRequestId.value),
                "supervisor.result-recovery"))
    }
}

internal fun runtimeSerialPublicationEvent(request: NativeSerialPrivatePublicationRequestV1,
    authorization: RuntimeCommandAuthorizationSnapshotV1, at: MonotonicMoment): LocalAuthEvent = LocalAuthEvent(
    request.scope, request.requestId, LocalAuthEventKind.SERIAL_PUBLICATION_REQUEST, request.publicationId,
    authorization.actorId, authorization.sessionId, authorization.accountRevision, at,
    NativeSerialPrivatePublicationRequestCodecV1.digest(request))

internal object RuntimeSerialPublicationRequestEvidenceCodec {
    private const val MAX_BYTES: Int = 128 * 1024
    private val json = Json {
        encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false; isLenient = false
        coerceInputValues = false; allowSpecialFloatingPointValues = false; allowStructuredMapKeys = false; prettyPrint = false
    }
    private val prefix = ("{\"schema\":\"" + RuntimeSerialPublicationRequestEvidenceV1.PROFILE + "\",").encodeToByteArray()

    fun hasProfile(bytes: ByteArray): Boolean = bytes.size in prefix.size..MAX_BYTES &&
        prefix.indices.all { bytes[it] == prefix[it] }

    fun encode(value: RuntimeSerialPublicationRequestEvidenceV1): ByteArray = guarded {
        value.validate()
        val bytes = json.encodeToString(RuntimeSerialPublicationRequestEvidenceV1.serializer(), value)
            .encodeToByteArray(throwOnInvalidSequence = true)
        if (bytes.size !in 1..MAX_BYTES) { bytes.fill(0); historyCapacityV2(false) }
        bytes
    }

    fun decode(bytes: ByteArray): RuntimeSerialPublicationRequestEvidenceV1 = guarded {
        historyCapacityV2(bytes.size in 1..MAX_BYTES)
        val value = json.decodeFromString(RuntimeSerialPublicationRequestEvidenceV1.serializer(),
            bytes.decodeToString(throwOnInvalidSequence = true))
        val canonical = encode(value)
        try { historyShapeV2(canonical.contentEquals(bytes)) } finally { canonical.fill(0) }
        value
    }

    fun dependencies(value: RuntimeSerialPublicationRequestEvidenceV1): ByteArray = guarded {
        value.validate()
        json.encodeToString(RuntimeSerialPublicationRequestDependenciesV1.serializer(), value.dependencies)
            .encodeToByteArray(throwOnInvalidSequence = true)
    }

    private inline fun <T> guarded(block: () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: RuntimeHistoryFormatRejectedV2) { throw failure }
    catch (_: Exception) { throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED) }
}

private const val RUNTIME_SERIAL_MAX_REQUEST_BYTES: Int = NativeSerialPrivatePublicationRequestCodecV1.MAX_REQUEST_BYTES

private fun runtimeSerialRequestBytes(value: String): ByteArray {
    historyCapacityV2(value.length in 2..RUNTIME_SERIAL_MAX_REQUEST_BYTES * 2)
    historyShapeV2(value.length % 2 == 0 && value.all { it in '0'..'9' || it in 'a'..'f' })
    return ByteArray(value.length / 2) { index ->
        ((value[index * 2].digitToInt(16) shl 4) or value[index * 2 + 1].digitToInt(16)).toByte()
    }
}

private fun runtimeSerialRequestHex(value: ByteArray): String {
    historyCapacityV2(value.size in 1..RUNTIME_SERIAL_MAX_REQUEST_BYTES)
    val digits = "0123456789abcdef"
    return buildString(value.size * 2) {
        value.forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            append(digits[unsigned ushr 4]); append(digits[unsigned and 0xf])
        }
    }
}
