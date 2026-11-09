package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.MinorUnitsSerializer
import com.ukkera.pos.types.Revision
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** New v5 protected records only. The five historical codecs and their canonical bytes are unchanged. */
internal object LocalAuthenticationCodec {
    fun encodeConfiguration(value: LocalAuthConfiguration): ByteArray = encode(ProtectedLocalTable.AUTH_CONFIGURATION,
        ConfigurationWire.serializer(), ConfigurationWire("ukkera.local-auth-configuration/v1", value.scope, value.actorId,
            value.revision, value.login.value, value.tokens.accessLifetimeMillis, value.tokens.workLifetimeMillis))
    fun decodeConfiguration(binding: ProtectedLocalBinding, bytes: ByteArray): LocalAuthConfiguration = decode(
        binding, bytes, ProtectedLocalTable.AUTH_CONFIGURATION, ConfigurationWire.serializer(), { wire ->
            require(wire.schema == "ukkera.local-auth-configuration/v1")
            LocalAuthConfiguration(wire.scope, wire.actorId, wire.revision, LocalLoginIdentifier(wire.login),
                LocalTokenPolicy(wire.accessMillis, wire.workMillis)).also {
                require(binding.key == ProtectedLocalRecordKey.AuthConfiguration(it.scope, it.actorId) && binding.revision == it.revision)
            }
        }, ::encodeConfiguration)

    fun encodeRefresh(value: LocalRefreshIssuance): ByteArray = encode(ProtectedLocalTable.REFRESH_ISSUANCE,
        RefreshWire.serializer(), RefreshWire("ukkera.local-refresh-issuance/v1", value.scope, value.actorId, value.sessionId,
            value.deviceId, value.generation, value.credentialRevisionAtIssue, value.admissionRevisionAtIssue,
            value.configurationRevisionAtIssue, value.issuerContextId, value.issuedAt, value.originalSessionId,
            value.workStartedAt, value.workLifetimeMillis, value.tokenHash.canonicalSha256(), value.revision))
    fun decodeRefresh(binding: ProtectedLocalBinding, bytes: ByteArray): LocalRefreshIssuance = decode(
        binding, bytes, ProtectedLocalTable.REFRESH_ISSUANCE, RefreshWire.serializer(), { wire ->
            require(wire.schema == "ukkera.local-refresh-issuance/v1" && wire.revision == Revision(0))
            LocalRefreshIssuance(wire.scope, wire.actorId, wire.sessionId, wire.deviceId, wire.generation,
                wire.credentialRevision, wire.admissionRevision, wire.configurationRevision, wire.issuerContextId,
                wire.issuedAt, wire.originalSessionId, wire.workStartedAt, wire.workLifetimeMillis,
                LocalRefreshTokenHash.fromCanonicalSha256(wire.tokenHash)).also {
                require(binding.key == ProtectedLocalRecordKey.RefreshIssuance(it.scope, it.sessionId) && binding.revision == it.revision)
            }
        }, ::encodeRefresh)

    fun encodeEvent(value: LocalAuthEvent): ByteArray = if (value.kind == LocalAuthEventKind.BRANCH_INSTALLED) {
        LocalBranchInstallationCodec.encodeEvent(value)
    } else if (value.kind == LocalAuthEventKind.MASTER_LIFECYCLE) {
        val audit = requireNotNull(value.lifecycle)
        encode(ProtectedLocalTable.AUTH_EVENT, LifecycleEventWire.serializer(),
            LifecycleEventWire(LIFECYCLE_PROFILE, value.scope, value.eventId, value.kind, value.targetId,
                value.operatorId, value.sessionId, value.accountRevision, value.at, value.contentDigest,
                value.revision, audit.action, audit.installationId, audit.coordinatesDigest,
                audit.requestDigest, audit.policyRevision, audit.deviceId, audit.sessionGeneration,
                audit.admissionRevision, audit.issuerContextId))
    } else encode(ProtectedLocalTable.AUTH_EVENT, EventWire.serializer(),
        EventWire(eventProfile(value.kind), value.scope, value.eventId, value.kind, value.targetId, value.operatorId,
            value.sessionId, value.accountRevision, value.at, value.contentDigest, value.revision))
    fun decodeEvent(binding: ProtectedLocalBinding, bytes: ByteArray): LocalAuthEvent =
        if (LocalBranchInstallationCodec.hasProfile(binding, bytes)) LocalBranchInstallationCodec.decodeEvent(binding, bytes)
        else if (eventSchema(bytes) == LIFECYCLE_PROFILE) decode(
            binding, bytes, ProtectedLocalTable.AUTH_EVENT, LifecycleEventWire.serializer(), { wire ->
                require(wire.schema == LIFECYCLE_PROFILE && wire.kind == LocalAuthEventKind.MASTER_LIFECYCLE &&
                    wire.revision == Revision(0))
                LocalAuthEvent(wire.scope, wire.eventId, wire.kind, wire.targetId, wire.operatorId, wire.sessionId,
                    wire.accountRevision, wire.at, wire.contentDigest, lifecycle = LocalMasterLifecycleAudit(
                        wire.action, wire.installationId, wire.coordinatesDigest, wire.requestDigest,
                        wire.policyRevision, wire.deviceId, wire.sessionGeneration, wire.admissionRevision,
                        wire.issuerContextId)).also {
                    require(binding.key == ProtectedLocalRecordKey.AuthEvent(it.scope, it.eventId) && binding.revision == it.revision)
                }
            }, ::encodeEvent)
        else decode(
        binding, bytes, ProtectedLocalTable.AUTH_EVENT, EventWire.serializer(), { wire ->
            require(wire.schema == eventProfile(wire.kind) && wire.revision == Revision(0))
            LocalAuthEvent(wire.scope, wire.eventId, wire.kind, wire.targetId, wire.operatorId, wire.sessionId,
                wire.accountRevision, wire.at, wire.contentDigest).also {
                require(binding.key == ProtectedLocalRecordKey.AuthEvent(it.scope, it.eventId) && binding.revision == it.revision)
            }
        }, ::encodeEvent)

    /** Existing events keep their exact bytes; new closed lanes use distinct profiles. */
    private fun eventProfile(kind: LocalAuthEventKind): String = when (kind) {
        LocalAuthEventKind.ADMINISTRATION_CANCELLED -> "ukkera.local-auth-event/v2"
        LocalAuthEventKind.ACTION_APPROVAL_RETIRED -> "ukkera.local-action-approval-retirement-event/v1"
        LocalAuthEventKind.SERIAL_PUBLICATION_REQUEST -> "ukkera.local-serial-publication-request-event/v1"
        LocalAuthEventKind.MASTER_LIFECYCLE -> LIFECYCLE_PROFILE
        else -> "ukkera.local-auth-event/v1"
    }

    private fun eventSchema(bytes: ByteArray): String = codec {
        bindingSize(bytes)
        peekJson.decodeFromString(EventPeek.serializer(), bytes.decodeToString(throwOnInvalidSequence = true)).schema
    }
    private fun bindingSize(bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size > ProtectedLocalTable.AUTH_EVENT.maximumPlaintextBytes) protectedLocalUnavailable()
    }

    fun encodeArchive(value: LocalSessionArchive): ByteArray = encode(ProtectedLocalTable.SESSION_ARCHIVE, ArchiveWire.serializer(),
        ArchiveWire("ukkera.local-session-archive/v1", value.session, value.archivedAt, value.revision))
    fun decodeArchive(binding: ProtectedLocalBinding, bytes: ByteArray): LocalSessionArchive = decode(
        binding, bytes, ProtectedLocalTable.SESSION_ARCHIVE, ArchiveWire.serializer(), { wire ->
            require(wire.schema == "ukkera.local-session-archive/v1" && wire.revision == Revision(0))
            LocalSessionArchive(wire.session, wire.archivedAt).also {
                require(binding.key == ProtectedLocalRecordKey.SessionArchive(it.session.scope, it.session.sessionId) && binding.revision == it.revision)
            }
        }, ::encodeArchive)
}

private const val LIFECYCLE_PROFILE = "ukkera.local-master-lifecycle-event/v1"
private val authenticationJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
    coerceInputValues = false
    allowSpecialFloatingPointValues = false
    allowStructuredMapKeys = false
    prettyPrint = false
}
private val peekJson = Json {
    encodeDefaults = false
    explicitNulls = false
    ignoreUnknownKeys = true
    isLenient = false
    coerceInputValues = false
    allowSpecialFloatingPointValues = false
    allowStructuredMapKeys = false
    prettyPrint = false
}
private inline fun <T> codec(block: () -> T): T = try { block() }
catch (cancelled: CancellationException) { throw cancelled }
catch (_: Exception) { protectedLocalUnavailable() }

private fun <W> encode(table: ProtectedLocalTable, serializer: KSerializer<W>, wire: W): ByteArray = codec {
    val bytes = authenticationJson.encodeToString(serializer, wire).encodeToByteArray(throwOnInvalidSequence = true)
    if (bytes.size !in 1..table.maximumPlaintextBytes) {
        bytes.fill(0)
        protectedLocalUnavailable()
    }
    bytes
}
private fun <W, V> decode(binding: ProtectedLocalBinding, bytes: ByteArray, table: ProtectedLocalTable,
    serializer: KSerializer<W>, value: (W) -> V, encode: (V) -> ByteArray): V = codec {
    require(binding.table == table && binding.schemaVersion == 1)
    binding.requirePlaintextSize(bytes.size)
    val snapshot = bytes.copyOf()
    try {
        val result = value(authenticationJson.decodeFromString(serializer, snapshot.decodeToString(throwOnInvalidSequence = true)))
        val canonical = encode(result)
        try { require(canonical.contentEquals(snapshot)) } finally { canonical.fill(0) }
        result
    } finally { snapshot.fill(0) }
}

@Serializable
private class ConfigurationWire(val schema: String, val scope: BranchScope, val actorId: DomainId, val revision: Revision,
    val login: String, @Serializable(with = MinorUnitsSerializer::class) val accessMillis: Long,
    @Serializable(with = MinorUnitsSerializer::class) val workMillis: Long)
@Serializable
private class RefreshWire(val schema: String, val scope: BranchScope, val actorId: DomainId, val sessionId: DomainId,
    val deviceId: DomainId, val generation: SessionGeneration, val credentialRevision: Revision,
    val admissionRevision: Revision, val configurationRevision: Revision, val issuerContextId: DomainId,
    val issuedAt: MonotonicMoment, val originalSessionId: DomainId, val workStartedAt: MonotonicMoment,
    @Serializable(with = MinorUnitsSerializer::class) val workLifetimeMillis: Long, val tokenHash: String, val revision: Revision)
@Serializable
private class EventWire(val schema: String, val scope: BranchScope, val eventId: DomainId, val kind: LocalAuthEventKind,
    val targetId: DomainId, val operatorId: DomainId?, val sessionId: DomainId?, val accountRevision: Revision?,
    val at: MonotonicMoment, val contentDigest: PayloadDigest?, val revision: Revision)
@Serializable
private class LifecycleEventWire(val schema: String, val scope: BranchScope, val eventId: DomainId,
    val kind: LocalAuthEventKind, val targetId: DomainId, val operatorId: DomainId?, val sessionId: DomainId?,
    val accountRevision: Revision?, val at: MonotonicMoment, val contentDigest: PayloadDigest?,
    val revision: Revision, val action: LocalMasterLifecycleAction, val installationId: DomainId,
    val coordinatesDigest: PayloadDigest, val requestDigest: PayloadDigest, val policyRevision: Revision,
    val deviceId: DomainId?, val sessionGeneration: SessionGeneration?, val admissionRevision: Revision?,
    val issuerContextId: DomainId?)
@Serializable
private class EventPeek(val schema: String)
@Serializable
private class ArchiveWire(val schema: String, val session: LocalSessionRecord, val archivedAt: MonotonicMoment, val revision: Revision)
