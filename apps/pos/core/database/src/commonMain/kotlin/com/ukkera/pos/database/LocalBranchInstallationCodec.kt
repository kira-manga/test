package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Separate <=4 KiB immutable AUTH_EVENT profile. No SQL/envelope version or old event wire
 * changes. Every old event continues through the original serializer and canonical decoder.
 * Declared fixed ASCII fields total at most 2341 canonical UTF-8 bytes (19-digit counters);
 * the real encoder still enforces the original 4096-byte bound before any insert.
 */
internal object LocalBranchInstallationCodec {
    private const val PROFILE = "ukkera.branch-installation-receipt/v1"
    private const val DEFINITION_DOMAIN = "ukkera.branch-installation-definition/v1"
    private const val DEFINITION_PROFILE = "fresh-live;master-schema=6;credential=required"
    private const val MAXIMUM_IDENTITY_BYTES = 4 * 1024
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        allowSpecialFloatingPointValues = false
        allowStructuredMapKeys = false
        prettyPrint = false
    }

    fun request(definition: LocalBranchInstallationDefinition): LocalBranchInstallationRequest = guarded {
        val bytes = encodeDefinition(definition)
        try { LocalBranchInstallationRequest(definition.identity, sha256(bytes)) }
        finally { bytes.fill(0) }
    }

    /**
     * The ORIGINAL six-field request preimage, not a new signed-document/receipt format.
     * Actor and the singleton admitted-device set are losslessly implied by the revalidated
     * definition invariants below. No digest, password or caller-selected authority is encoded.
     * The caller owns and must clear the returned buffer when it is no longer needed.
     */
    fun encodeDefinition(definition: LocalBranchInstallationDefinition): ByteArray = guarded {
        val identity = definition.identity
        val account = definition.firstAccount
        check(account.actorId == identity.firstActorId && account.policy.actorId == identity.firstActorId &&
            account.policy.scope == identity.scope && account.policy.revision == Revision(0))
        check(account.admittedDeviceIds == setOf(identity.masterDeviceId))
        check(definition.masterAdmission.scope == identity.scope &&
            definition.masterAdmission.deviceId == identity.masterDeviceId &&
            definition.masterAdmission.revision == Revision(0) && definition.masterAdmission.admitted)
        val configuration = LocalAuthConfiguration(identity.scope, identity.firstActorId, Revision(0), account.login, account.tokenPolicy)
        val owned = mutableListOf<ByteArray>()
        try {
            owned += DEFINITION_DOMAIN.encodeToByteArray()
            owned += DEFINITION_PROFILE.encodeToByteArray()
            owned += json.encodeToString(LocalBranchInstallationIdentity.serializer(), identity)
                .encodeToByteArray(throwOnInvalidSequence = true)
            check(owned[2].size in 1..MAXIMUM_IDENTITY_BYTES)
            owned += LocalEffectivePolicyCodec.encodePolicy(account.policy)
            owned += LocalAuthenticationCodec.encodeConfiguration(configuration)
            owned += ProtectedLocalSecurityCodec.encodeAdmission(definition.masterAdmission)
            val size = 4L + owned.sumOf { 4L + it.size }
            check(size in 1L..LocalBranchInstallationDefinitionSnapshot.MAXIMUM_BYTES.toLong())
            val bytes = ByteArray(size.toInt())
            try {
                var offset = 0
                fun integer(value: Int) {
                    bytes[offset++] = (value ushr 24).toByte(); bytes[offset++] = (value ushr 16).toByte()
                    bytes[offset++] = (value ushr 8).toByte(); bytes[offset++] = value.toByte()
                }
                integer(owned.size)
                for (field in owned) { integer(field.size); field.copyInto(bytes, offset); offset += field.size }
                bytes
            } catch (failure: Throwable) { bytes.fill(0); throw failure }
        } finally { owned.forEach { it.fill(0) } }
    }

    /** Fresh, unshared value for queued work. No caller-owned collection survives capture. */
    fun snapshotDefinition(definition: LocalBranchInstallationDefinition): LocalBranchInstallationDefinition = guarded {
        val bytes = encodeDefinition(definition)
        try { decodeDefinition(bytes) } finally { bytes.fill(0) }
    }

    /**
     * Decode only a bounded, caller-owned stable buffer. Validate field sizes BEFORE slicing
     * and JSON depth BEFORE recursive parsing, including the v2 policy's escaped baseV1.
     * Existing policy/configuration/admission codecs retain ALL versioned extension semantics.
     */
    fun decodeDefinition(bytes: ByteArray): LocalBranchInstallationDefinition = guarded {
        check(bytes.size in 1..LocalBranchInstallationDefinitionSnapshot.MAXIMUM_BYTES)
        val fields = mutableListOf<ByteArray>()
        try {
            var offset = 0
            fun integer(): Int {
                check(bytes.size - offset >= 4)
                var value = 0
                repeat(4) { value = (value shl 8) or (bytes[offset++].toInt() and 0xff) }
                return value
            }
            check(integer() == 6)
            val maximums = intArrayOf(DEFINITION_DOMAIN.length, DEFINITION_PROFILE.length,
                MAXIMUM_IDENTITY_BYTES, ProtectedLocalTable.EFFECTIVE_POLICY.maximumPlaintextBytes,
                ProtectedLocalTable.AUTH_CONFIGURATION.maximumPlaintextBytes, ProtectedLocalTable.ADMISSION.maximumPlaintextBytes)
            for (maximum in maximums) {
                val size = integer()
                check(size in 1..maximum && size <= bytes.size - offset)
                fields += bytes.copyOfRange(offset, offset + size)
                offset += size
            }
            check(offset == bytes.size)
            check(fields[0].decodeToString(throwOnInvalidSequence = true) == DEFINITION_DOMAIN &&
                fields[1].decodeToString(throwOnInvalidSequence = true) == DEFINITION_PROFILE)
            requireDefinitionJson(fields[2], MAXIMUM_IDENTITY_BYTES)
            requirePolicyJson(fields[3])
            requireDefinitionJson(fields[4], ProtectedLocalTable.AUTH_CONFIGURATION.maximumPlaintextBytes)
            requireDefinitionJson(fields[5], ProtectedLocalTable.ADMISSION.maximumPlaintextBytes)
            val identity = json.decodeFromString(LocalBranchInstallationIdentity.serializer(),
                fields[2].decodeToString(throwOnInvalidSequence = true))
            val policy = LocalEffectivePolicyCodec.decodePolicy(
                ProtectedLocalBinding(ProtectedLocalRecordKey.EffectivePolicy(identity.scope, identity.firstActorId), Revision(0)), fields[3])
            val configuration = LocalAuthenticationCodec.decodeConfiguration(
                ProtectedLocalBinding(ProtectedLocalRecordKey.AuthConfiguration(identity.scope, identity.firstActorId), Revision(0)), fields[4])
            val admission = ProtectedLocalSecurityCodec.decodeAdmission(
                ProtectedLocalBinding(ProtectedLocalRecordKey.Admission(identity.scope, identity.masterDeviceId), Revision(0)), fields[5])
            val account = LocalAccountProvisioning(identity.firstActorId, configuration.login, policy,
                configuration.tokens, setOf(identity.masterDeviceId))
            val definition = LocalBranchInstallationDefinition(identity, account, admission)
            val canonical = encodeDefinition(definition)
            try { check(canonical.contentEquals(bytes)) } finally { canonical.fill(0) }
            definition
        } finally { fields.forEach { it.fill(0) } }
    }

    private fun requirePolicyJson(bytes: ByteArray) {
        val maximum = ProtectedLocalTable.EFFECTIVE_POLICY.maximumPlaintextBytes
        requireDefinitionJson(bytes, maximum)
        // Outer depth is now bounded. A v2 base is JSON inside a string, so checking only the
        // outer object would miss malicious nesting subsequently parsed by the v1 decoder.
        val base = json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true))
            .jsonObject["baseV1"]?.jsonPrimitive ?: return
        check(base.isString && base.content.length <= maximum)
        val baseBytes = base.content.encodeToByteArray(throwOnInvalidSequence = true)
        try { requireDefinitionJson(baseBytes, maximum) } finally { baseBytes.fill(0) }
    }

    /** Fixed nonrecursive schemas need fewer than 16 levels; legitimate UTF-8/escapes remain valid. */
    private fun requireDefinitionJson(bytes: ByteArray, maximum: Int) {
        check(bytes.size in 1..maximum)
        val nesting = IntArray(16)
        var depth = 0
        var inString = false
        var escaped = false
        for (byte in bytes) {
            val character = byte.toInt() and 0xff
            if (inString) {
                check(character >= 0x20)
                if (escaped) escaped = false
                else when (character) {
                    0x5c -> escaped = true
                    0x22 -> inString = false
                }
            } else when (character) {
                0x22 -> inString = true
                0x7b, 0x5b -> { check(depth < nesting.size); nesting[depth++] = character }
                0x7d, 0x5d -> {
                    check(depth > 0)
                    check(nesting[--depth] == if (character == 0x7d) 0x7b else 0x5b)
                }
                else -> check(character < 0x80)
            }
        }
        check(!inString && !escaped && depth == 0)
        // Syntax, escapes, UTF-8, field sets/order and exact canonical bytes are checked by the
        // strict serializers and re-encoding, not normalized or accepted by this preflight.
    }

    fun event(record: LocalBranchInstallationRecord): LocalAuthEvent {
        val identity = record.request.identity
        return LocalAuthEvent(identity.scope, identity.installationId, LocalAuthEventKind.BRANCH_INSTALLED,
            identity.scope.branchId, identity.responsibleHumanId, null, Revision(0), record.installedAt, record.genesis, record)
    }

    fun hasProfile(binding: ProtectedLocalBinding, bytes: ByteArray): Boolean = guarded {
        check(binding.table == ProtectedLocalTable.AUTH_EVENT && binding.schemaVersion == 1)
        binding.requirePlaintextSize(bytes.size)
        json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject["schema"]
            ?.jsonPrimitive?.content == PROFILE
    }

    fun encodeEvent(value: LocalAuthEvent): ByteArray = guarded {
        check(value.kind == LocalAuthEventKind.BRANCH_INSTALLED && value.revision == Revision(0))
        val record = checkNotNull(value.installation)
        val wire = InstallationWire(PROFILE, LocalAuthEventKind.BRANCH_INSTALLED, record.request,
            record.issuerContextId, record.installedAt, record.accountDigest, record.policyDigest,
            record.configurationDigest, record.admissionDigest, record.accountThrottleDigest,
            record.deviceThrottleDigest, record.recoveryStateDigest, record.genesis, Revision(0))
        val bytes = json.encodeToString(InstallationWire.serializer(), wire).encodeToByteArray(throwOnInvalidSequence = true)
        if (bytes.size !in 1..ProtectedLocalTable.AUTH_EVENT.maximumPlaintextBytes) {
            bytes.fill(0)
            protectedLocalUnavailable()
        }
        bytes
    }

    fun decodeEvent(binding: ProtectedLocalBinding, bytes: ByteArray): LocalAuthEvent = guarded {
        check(binding.table == ProtectedLocalTable.AUTH_EVENT && binding.schemaVersion == 1 && binding.revision == Revision(0))
        binding.requirePlaintextSize(bytes.size)
        val snapshot = bytes.copyOf()
        try {
            val wire = json.decodeFromString(InstallationWire.serializer(), snapshot.decodeToString(throwOnInvalidSequence = true))
            check(wire.schema == PROFILE && wire.kind == LocalAuthEventKind.BRANCH_INSTALLED && wire.revision == Revision(0))
            val identity = wire.request.identity
            check(binding.key == ProtectedLocalRecordKey.AuthEvent(identity.scope, identity.installationId))
            val record = LocalBranchInstallationRecord(wire.request, wire.issuerContextId, wire.installedAt,
                wire.accountDigest, wire.policyDigest, wire.configurationDigest, wire.admissionDigest,
                wire.accountThrottleDigest, wire.deviceThrottleDigest, wire.recoveryStateDigest)
            check(record.genesis == wire.genesis)
            val event = event(record)
            val canonical = encodeEvent(event)
            try { check(canonical.contentEquals(snapshot)) } finally { canonical.fill(0) }
            event
        } finally { snapshot.fill(0) }
    }

    private inline fun <T> guarded(block: () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { protectedLocalUnavailable() }
}

@Serializable
private class InstallationWire(
    val schema: String,
    val kind: LocalAuthEventKind,
    val request: LocalBranchInstallationRequest,
    val issuerContextId: DomainId,
    val installedAt: MonotonicMoment,
    val accountDigest: PayloadDigest,
    val policyDigest: PayloadDigest,
    val configurationDigest: PayloadDigest,
    val admissionDigest: PayloadDigest,
    val accountThrottleDigest: PayloadDigest,
    val deviceThrottleDigest: PayloadDigest,
    val recoveryStateDigest: PayloadDigest,
    val genesis: PayloadDigest,
    val revision: Revision,
)

/** Complete canonical payload commitment, with best-effort clearing of the owned buffer. */
internal fun installationDigest(bytes: ByteArray): PayloadDigest = try { sha256(bytes) } finally { bytes.fill(0) }
