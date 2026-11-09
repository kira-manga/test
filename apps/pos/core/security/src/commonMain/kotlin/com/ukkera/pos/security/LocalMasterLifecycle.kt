package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Explicit lifecycle action. It is not a business command, session or grant by itself. */
public enum class LocalMasterLifecycleAction { STOP, RESUME }

/**
 * The original installation is selected by the trusted owner that constructs the service.
 * A caller supplies only its retained request identity and the intended action; it cannot
 * target another original, supply coordinates or promote a setup/UI flag into authority.
 */
public class LocalMasterLifecycleRequest(
    public val requestId: DomainId,
    public val action: LocalMasterLifecycleAction,
) {
    override fun toString(): String = "LocalMasterLifecycleRequest([REDACTED])"
}

/**
 * Immutable protected lifecycle receipt facts stored beside the audit event. This is a
 * separately versioned event profile, not a mutable replacement of the protected auth row.
 */
public class LocalMasterLifecycleAudit(
    public val action: LocalMasterLifecycleAction,
    public val installationId: DomainId,
    public val coordinatesDigest: PayloadDigest,
    public val requestDigest: PayloadDigest,
    public val policyRevision: Revision,
    public val deviceId: DomainId?,
    public val sessionGeneration: SessionGeneration?,
    public val admissionRevision: Revision?,
    public val issuerContextId: DomainId?,
) {
    /** Source-compatible DATA constructor; history authorization still requires every field. */
    public constructor(action: LocalMasterLifecycleAction, installationId: DomainId,
        coordinatesDigest: PayloadDigest, requestDigest: PayloadDigest, policyRevision: Revision) :
        this(action, installationId, coordinatesDigest, requestDigest, policyRevision, null, null, null, null)

    init {
        require(requestDigest.value.length == 64 && coordinatesDigest.value.length == 64)
    }
    override fun toString(): String = "LocalMasterLifecycleAudit([REDACTED])"
}

/**
 * Pre-dispatch durable request record. It binds the exact action, request identity and complete
 * original coordinates before any authentication or lifecycle side effect. A stale/malformed
 * record is not a new intent and cannot authorize a different original or action.
 */
public class LocalMasterLifecycleIntent private constructor(
    public val request: LocalMasterLifecycleRequest,
    private val fields: List<String>,
    public val coordinatesDigest: PayloadDigest,
    public val requestDigest: PayloadDigest,
) {
    init {
        require(fields.size == 12 && fields.distinct().size == 12)
        require(coordinatesDigest.value.length == 64 && requestDigest.value.length == 64)
    }
    public val installationId: DomainId get() = DomainId(fields[4])
    public fun matches(coordinates: SelfOwnedAllocationCoordinatesV1, request: LocalMasterLifecycleRequest): Boolean =
        this.request.requestId == request.requestId && this.request.action == request.action &&
            fields == coordinateFields(coordinates) &&
            coordinatesDigest == lifecycleCoordinatesDigest(coordinates) &&
            requestDigest == lifecycleRequestDigest(coordinates, request)
    public fun encode(): ByteArray {
        val lines = buildList {
            add(FORMAT)
            add(request.action.name)
            add(request.requestId.value)
            add(coordinatesDigest.value)
            add(requestDigest.value)
            addAll(fields)
        }
        return lines.joinToString("\n", postfix = "\n").encodeToByteArray(throwOnInvalidSequence = true)
    }
    override fun toString(): String = "LocalMasterLifecycleIntent([REDACTED])"

    public companion object {
        public const val FORMAT: String = "ukkera.local-master-lifecycle-intent/v1"
        public const val MAXIMUM_BYTES: Int = 2048

        public fun create(coordinates: SelfOwnedAllocationCoordinatesV1,
            request: LocalMasterLifecycleRequest): LocalMasterLifecycleIntent {
            val fields = coordinateFields(coordinates)
            return LocalMasterLifecycleIntent(request, fields, lifecycleCoordinatesDigest(fields),
                lifecycleRequestDigest(request.action, request.requestId, fields))
        }

        /** Throws for any malformed, foreign or non-canonical byte sequence. */
        public fun decode(bytes: ByteArray): LocalMasterLifecycleIntent {
            require(bytes.size in 1..MAXIMUM_BYTES)
            val lines = bytes.decodeToString(throwOnInvalidSequence = true).split('\n')
            require(lines.size == 18 && lines[0] == FORMAT && lines[17].isEmpty())
            val action = LocalMasterLifecycleAction.valueOf(lines[1])
            val requestId = DomainId(lines[2])
            val coordinatesDigest = PayloadDigest(lines[3])
            val requestDigest = PayloadDigest(lines[4])
            val fields = lines.subList(5, 17)
            require(fields.distinct().size == 12)
            fields.forEach { DomainId(it) }
            require(coordinatesDigest == lifecycleCoordinatesDigest(fields))
            require(requestDigest == lifecycleRequestDigest(action, requestId, fields))
            return LocalMasterLifecycleIntent(LocalMasterLifecycleRequest(requestId, action), fields,
                coordinatesDigest, requestDigest)
        }
    }
}

/** Protected audit identity and exact original binding. No token, password or private row. */
public class LocalMasterLifecycleReceipt(
    public val eventId: DomainId,
    public val action: LocalMasterLifecycleAction,
    public val actorId: DomainId,
    public val sessionId: DomainId,
    public val installationId: DomainId,
    public val coordinatesDigest: PayloadDigest,
    public val requestDigest: PayloadDigest,
    public val accountRevision: Revision,
    public val policyRevision: Revision,
    public val committedAt: MonotonicMoment,
) {
    override fun toString(): String = "LocalMasterLifecycleReceipt([REDACTED])"
}

public enum class LocalMasterLifecycleDenial {
    AUTHENTICATION_REFUSED,
    NOT_AUTHORIZED,
    WRONG_STATE,
    IDENTITY_CONFLICT,
    SERVICE_BUSY,
}

public sealed interface LocalMasterLifecycleResult {
    public class Committed(public val receipt: LocalMasterLifecycleReceipt) : LocalMasterLifecycleResult
    public data class Denied(public val reason: LocalMasterLifecycleDenial) : LocalMasterLifecycleResult
    public data object Unavailable : LocalMasterLifecycleResult
    public data object StorageUncertain : LocalMasterLifecycleResult
    public data object Busy : LocalMasterLifecycleResult
}

public sealed interface LocalMasterLifecycleStatus {
    public class Committed(public val receipt: LocalMasterLifecycleReceipt) : LocalMasterLifecycleStatus
    public data object NotRecorded : LocalMasterLifecycleStatus
    public data class Denied(public val reason: LocalMasterLifecycleDenial) : LocalMasterLifecycleStatus
    public data object Unavailable : LocalMasterLifecycleStatus
    public data object StorageUncertain : LocalMasterLifecycleStatus
    public data object Busy : LocalMasterLifecycleStatus
}

/**
 * Narrow current-authority lifecycle ingress. The implementation must authenticate the current
 * employee, authorize the explicit appended lifecycle grant, and append the request/result to
 * the SAME protected transaction that revocation uses. It must never accept caller coordinates,
 * a UI flag, a cached grant or a plain setup command as authority.
 */
public interface LocalMasterLifecycleService {
    public suspend fun apply(credentials: LocalGatewayCredentials,
        request: LocalMasterLifecycleRequest): LocalMasterLifecycleResult
    public suspend fun lookup(credentials: LocalGatewayCredentials,
        request: LocalMasterLifecycleRequest): LocalMasterLifecycleStatus
}

/** Exact canonical digest of all twelve original coordinates. */
public fun lifecycleCoordinatesDigest(coordinates: SelfOwnedAllocationCoordinatesV1): PayloadDigest =
    lifecycleCoordinatesDigest(coordinateFields(coordinates))

/** Canonical action/request digest bound to this exact original. */
public fun lifecycleRequestDigest(coordinates: SelfOwnedAllocationCoordinatesV1,
    request: LocalMasterLifecycleRequest): PayloadDigest =
    lifecycleRequestDigest(request.action, request.requestId, coordinateFields(coordinates))

private fun lifecycleCoordinatesDigest(fields: List<String>): PayloadDigest {
    val text = buildString {
        append("ukkera.local-master-lifecycle-coordinates/v1")
        fields.forEach { append('\n').append(it) }
    }
    val bytes = text.encodeToByteArray(throwOnInvalidSequence = true)
    return try { sha256(bytes) } finally { bytes.fill(0) }
}

private fun lifecycleRequestDigest(action: LocalMasterLifecycleAction, requestId: DomainId,
    fields: List<String>): PayloadDigest {
    val text = buildString {
        append("ukkera.local-master-lifecycle-request/v1")
        append('\n').append(action.name)
        append('\n').append(requestId.value)
        fields.forEach { append('\n').append(it) }
    }
    val bytes = text.encodeToByteArray(throwOnInvalidSequence = true)
    return try { sha256(bytes) } finally { bytes.fill(0) }
}

private fun coordinateFields(value: SelfOwnedAllocationCoordinatesV1): List<String> = listOf(
    value.allocationId.value, value.scope.organizationId.value, value.scope.companyId.value,
    value.scope.branchId.value, value.installationId.value, value.assignmentId.value, value.deviceId.value,
    value.storageInstanceId.value, value.storageKeyId.value, value.pepperKeyId.value,
    value.targetSigningKeyId.value, value.firstActorId.value,
)
