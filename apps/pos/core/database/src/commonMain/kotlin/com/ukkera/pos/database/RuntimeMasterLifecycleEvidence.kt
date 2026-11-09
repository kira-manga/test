package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Exact runtime-history evidence for the protected MASTER_LIFECYCLE event. It is the history
 * producer's own copy of the immutable receipt facts, allowing the scanner to join the event
 * to the genuine historical session/account/policy cut before accepting the audit.
 */
@Serializable
internal data class RuntimeMasterLifecycleEvidenceV1(
    val schema: String = PROFILE,
    val scope: BranchScope,
    val eventId: DomainId,
    val actorId: DomainId,
    val sessionId: DomainId,
    val accountRevision: Revision,
    val action: LocalMasterLifecycleAction,
    val installationId: DomainId,
    val coordinatesDigest: PayloadDigest,
    val requestDigest: PayloadDigest,
    val policyRevision: Revision,
    val deviceId: DomainId,
    val sessionGeneration: SessionGeneration,
    val admissionRevision: Revision,
    val issuerContextId: DomainId,
    val at: MonotonicMoment,
) {
    fun validate() {
        require(schema == PROFILE && requestDigest.value.length == 64 && coordinatesDigest.value.length == 64)
        event()
    }
    fun event(): LocalAuthEvent = LocalAuthEvent(scope, eventId, LocalAuthEventKind.MASTER_LIFECYCLE,
        installationId, actorId, sessionId, accountRevision, at, requestDigest,
        lifecycle = LocalMasterLifecycleAudit(action, installationId, coordinatesDigest, requestDigest,
            policyRevision, deviceId, sessionGeneration, admissionRevision, issuerContextId))
    fun matchesEvent(value: LocalAuthEvent): Boolean {
        val expected = LocalAuthenticationCodec.encodeEvent(event())
        val actual = LocalAuthenticationCodec.encodeEvent(value)
        return try { actual.contentEquals(expected) } finally { expected.fill(0); actual.fill(0) }
    }
    override fun toString(): String = "RuntimeMasterLifecycleEvidenceV1([REDACTED])"

    companion object {
        const val PROFILE: String = "ukkera.runtime-master-lifecycle-evidence/v1"
        const val MAXIMUM_BYTES: Int = 64 * 1024
    }
}

internal object RuntimeMasterLifecycleEvidenceCodec {
    private val json = Json {
        encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false; isLenient = false
        coerceInputValues = false; allowSpecialFloatingPointValues = false
        allowStructuredMapKeys = false; prettyPrint = false
    }
    fun encode(value: RuntimeMasterLifecycleEvidenceV1): ByteArray {
        value.validate()
        return json.encodeToString(RuntimeMasterLifecycleEvidenceV1.serializer(), value)
            .encodeToByteArray(throwOnInvalidSequence = true)
            .also { require(it.size in 1..RuntimeMasterLifecycleEvidenceV1.MAXIMUM_BYTES) }
    }
    fun decode(bytes: ByteArray): RuntimeMasterLifecycleEvidenceV1 {
        require(bytes.size in 1..RuntimeMasterLifecycleEvidenceV1.MAXIMUM_BYTES)
        val value = json.decodeFromString(RuntimeMasterLifecycleEvidenceV1.serializer(),
            bytes.decodeToString(throwOnInvalidSequence = true))
        value.validate()
        val canonical = encode(value)
        try { require(canonical.contentEquals(bytes)) } finally { canonical.fill(0) }
        return value
    }
    fun hasProfile(bytes: ByteArray): Boolean = try {
        if (bytes.size !in 1..RuntimeMasterLifecycleEvidenceV1.MAXIMUM_BYTES) false
        else json.decodeFromString(RuntimeMasterLifecycleEvidenceV1.serializer(),
            bytes.decodeToString(throwOnInvalidSequence = true)).schema == RuntimeMasterLifecycleEvidenceV1.PROFILE
    } catch (_: Exception) { false }
}
