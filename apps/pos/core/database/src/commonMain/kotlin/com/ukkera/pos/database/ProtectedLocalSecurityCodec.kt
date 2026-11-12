package com.ukkera.pos.database

import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.CompanyScope
import com.ukkera.pos.security.CredentialBinding
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.LocalAccountSessionState
import com.ukkera.pos.security.LocalAccessIssuance
import com.ukkera.pos.security.LocalAccessTokenHash
import com.ukkera.pos.security.LocalDeviceAdmission
import com.ukkera.pos.security.LocalSessionPolicy
import com.ukkera.pos.security.LocalSessionRecord
import com.ukkera.pos.security.MonotonicMoment
import com.ukkera.pos.security.OwnerAccess
import com.ukkera.pos.security.PasswordHashParameters
import com.ukkera.pos.security.PasswordHashRecord
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionGrant
import com.ukkera.pos.security.PermissionLimits
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.PersistentThrottleState
import com.ukkera.pos.security.ProtectedLocalAccount
import com.ukkera.pos.security.ProtectedLocalBinding
import com.ukkera.pos.security.ProtectedLocalRecordKey
import com.ukkera.pos.security.ProtectedLocalTable
import com.ukkera.pos.security.ProtectedPepperReference
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.security.SessionStatus
import com.ukkera.pos.security.TenantScope
import com.ukkera.pos.security.ThrottleAttempt
import com.ukkera.pos.security.ThrottleDimension
import com.ukkera.pos.security.ThrottleKey
import com.ukkera.pos.security.ThrottlePolicy
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.MinorUnitsSerializer
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Private, versioned plaintext framing for the protected local store, never an RPC contract or
 * a plaintext-storage fallback. The caller must authenticate/decrypt with the expected binding
 * before decoding, and must seal encoded bytes before persistence. No result grants authority.
 *
 * Lists retain their meaning and order; sets have one sorted representation. Input and output
 * are bounded independently of item counts and of the financial command's 64-KiB profile.
 * Private temporary JSON contains credential verifier material: never log it. Array clearing
 * below is best effort, not a claim that immutable strings or runtime memory can be erased.
 */
internal object ProtectedLocalSecurityCodec {
    fun encodeAccount(value: ProtectedLocalAccount): ByteArray = protectedCodec {
        val wire = AccountWire.capture(value)
        wire.value() // Revalidate the captured snapshot, not a separately mutable source list.
        writeProtected(ProtectedLocalTable.ACCOUNT, AccountWire.serializer(), wire)
    }

    fun decodeAccount(binding: ProtectedLocalBinding, plaintext: ByteArray): ProtectedLocalAccount = protectedCodec {
        readProtected(binding, plaintext, ProtectedLocalTable.ACCOUNT, AccountWire.serializer(),
            decode = { it.value() },
            bind = {
                val state = it.state
                requirePayloadBinding(binding, state.scope, state.actorId, state.revision)
            },
            encode = ::encodeAccount)
    }

    fun encodePolicy(value: ActivatedLocalPolicy): ByteArray = protectedCodec {
        // Keep the historical wire exact; do not silently discard an explicitly versioned extension.
        require(!LocalEffectivePolicyCodec.requiresExtension(value))
        val wire = PolicyWire.capture(value)
        wire.requireByteBudget()
        wire.value()
        writeProtected(ProtectedLocalTable.EFFECTIVE_POLICY, PolicyWire.serializer(), wire)
    }

    fun decodePolicy(binding: ProtectedLocalBinding, plaintext: ByteArray): ActivatedLocalPolicy = protectedCodec {
        readProtected(binding, plaintext, ProtectedLocalTable.EFFECTIVE_POLICY, PolicyWire.serializer(),
            decode = { it.value() },
            bind = { requirePayloadBinding(binding, it.scope, it.actorId, it.revision) },
            encode = ::encodePolicy)
    }

    fun encodeAdmission(value: LocalDeviceAdmission): ByteArray = protectedCodec {
        val wire = AdmissionWire.capture(value)
        wire.value()
        writeProtected(ProtectedLocalTable.ADMISSION, AdmissionWire.serializer(), wire)
    }

    fun decodeAdmission(binding: ProtectedLocalBinding, plaintext: ByteArray): LocalDeviceAdmission = protectedCodec {
        readProtected(binding, plaintext, ProtectedLocalTable.ADMISSION, AdmissionWire.serializer(),
            decode = { it.value() },
            bind = { requirePayloadBinding(binding, it.scope, it.deviceId, it.revision) },
            encode = ::encodeAdmission)
    }

    fun encodeThrottle(value: PersistentThrottleState): ByteArray = protectedCodec {
        val wire = ThrottleWire.capture(value)
        wire.value()
        writeProtected(ProtectedLocalTable.THROTTLE, ThrottleWire.serializer(), wire)
    }

    fun decodeThrottle(binding: ProtectedLocalBinding, plaintext: ByteArray): PersistentThrottleState = protectedCodec {
        readProtected(binding, plaintext, ProtectedLocalTable.THROTTLE, ThrottleWire.serializer(),
            decode = { it.value() },
            bind = {
                requirePayloadBinding(binding, it.key.scope, it.key.actorId, it.revision)
                require((binding.key as? ProtectedLocalRecordKey.Throttle)?.key == it.key)
            },
            encode = ::encodeThrottle)
    }

    fun encodeIssuance(value: LocalAccessIssuance): ByteArray = protectedCodec {
        val wire = IssuanceWire.capture(value)
        wire.value()
        writeProtected(ProtectedLocalTable.SESSION_ISSUANCE, IssuanceWire.serializer(), wire)
    }

    fun decodeIssuance(binding: ProtectedLocalBinding, plaintext: ByteArray): LocalAccessIssuance = protectedCodec {
        readProtected(binding, plaintext, ProtectedLocalTable.SESSION_ISSUANCE, IssuanceWire.serializer(),
            decode = { it.value() },
            bind = {
                require(binding.key is ProtectedLocalRecordKey.SessionIssuance)
                requirePayloadBinding(binding, it.scope, it.sessionId, it.revision)
            },
            encode = ::encodeIssuance)
    }
}

private const val ACCOUNT_WIRE_SCHEMA = "ukkera.protected-local-account/v1"
private const val POLICY_WIRE_SCHEMA = "ukkera.activated-local-policy/v1"
private const val ADMISSION_WIRE_SCHEMA = "ukkera.local-device-admission/v1"
private const val THROTTLE_WIRE_SCHEMA = "ukkera.persistent-throttle/v1"
private const val ISSUANCE_WIRE_SCHEMA = "ukkera.local-access-issuance/v1"
private const val PASSWORD_WIRE_ALGORITHM = "PBKDF2-HMAC-SHA256/POST-HMAC-SHA256/v1"
private const val MAX_ACCOUNT_SESSIONS = 256
private const val MAX_POLICY_GRANTS = 1024
private const val MAX_GRANT_TARGETS = 256
private const val MAX_ADMISSION_CAPABILITIES = 64

private val protectedJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
    coerceInputValues = false
    allowSpecialFloatingPointValues = false
    allowStructuredMapKeys = false
    prettyPrint = false
}

private inline fun <T> protectedCodec(block: () -> T): T = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    // Parser diagnostics can include the complete plaintext, salt, verifier or private policy.
    throw IllegalStateException("Protected local record unavailable")
}

private fun requirePayloadBinding(binding: ProtectedLocalBinding, scope: BranchScope, id: DomainId, revision: Revision) {
    require(binding.key.scope == scope && binding.key.recordId == id && binding.revision == revision)
}

private fun <W> writeProtected(table: ProtectedLocalTable, serializer: KSerializer<W>, wire: W): ByteArray {
    val bytes = protectedJson.encodeToString(serializer, wire).encodeToByteArray(throwOnInvalidSequence = true)
    if (bytes.size !in 1..table.maximumPlaintextBytes) {
        bytes.fill(0)
        error("Protected plaintext exceeds its profile")
    }
    return bytes
}

private fun <W, V> readProtected(
    binding: ProtectedLocalBinding,
    plaintext: ByteArray,
    table: ProtectedLocalTable,
    serializer: KSerializer<W>,
    decode: (W) -> V,
    bind: (V) -> Unit,
    encode: (V) -> ByteArray,
): V {
    require(binding.table == table && binding.schemaVersion == 1)
    binding.requirePlaintextSize(plaintext.size)
    val snapshot = plaintext.copyOf()
    try {
        val wire = protectedJson.decodeFromString(serializer, snapshot.decodeToString(throwOnInvalidSequence = true))
        val value = decode(wire)
        bind(value)
        val canonical = encode(value)
        try {
            // A strict parser alone may accept duplicate keys, reordered fields, alternate
            // escapes or omitted defaults. None is an alternative encoding in this profile.
            require(canonical.contentEquals(snapshot))
        } finally {
            canonical.fill(0)
        }
        return value
    } finally {
        snapshot.fill(0)
    }
}

private fun <T> boundedSnapshot(values: Iterable<T>, maximum: Int): List<T> {
    val result = ArrayList<T>()
    for (value in values) {
        require(result.size < maximum)
        result += value
    }
    return result
}

private fun <T> checkedSet(values: List<T>, maximum: Int, minimum: Int = 0): Set<T> {
    require(values.size in minimum..maximum)
    val result = values.toSet()
    require(result.size == values.size) // Never normalize duplicate wire entries away.
    return result
}

/** Sole private issuance wire representation. It neither verifies a bearer nor mints a session. */
@Serializable
private class IssuanceWire(
    val schema: String,
    val scope: BranchScope,
    val actorId: DomainId,
    val sessionId: DomainId,
    val deviceId: DomainId,
    val generation: SessionGeneration,
    val credentialRevisionAtIssue: Revision,
    val admissionRevisionAtIssue: Revision,
    val issuerContextId: DomainId,
    val issuedAt: MonotonicMoment,
    @Serializable(with = MinorUnitsSerializer::class) val validForMillis: Long,
    val tokenHash: String,
    val revision: Revision,
    val authenticationPurpose: AuthenticationPurpose,
) {
    fun value(): LocalAccessIssuance {
        require(schema == ISSUANCE_WIRE_SCHEMA && revision == Revision(0) &&
            authenticationPurpose == AuthenticationPurpose.INTERACTIVE)
        return LocalAccessIssuance(scope, actorId, sessionId, deviceId, generation,
            credentialRevisionAtIssue, admissionRevisionAtIssue, issuerContextId, issuedAt, validForMillis,
            LocalAccessTokenHash.fromCanonicalSha256(tokenHash))
    }

    companion object {
        fun capture(value: LocalAccessIssuance): IssuanceWire = IssuanceWire(
            ISSUANCE_WIRE_SCHEMA, value.scope, value.actorId, value.sessionId, value.deviceId, value.generation,
            value.credentialRevisionAtIssue, value.admissionRevisionAtIssue, value.issuerContextId, value.issuedAt,
            value.validForMillis, value.tokenHash.canonicalSha256(), value.revision, value.authenticationPurpose,
        )
    }
}

@Serializable
private class AccountWire(
    val schema: String,
    val scope: BranchScope,
    val actorId: DomainId,
    val enabled: Boolean,
    val revision: Revision,
    val generation: SessionGeneration,
    val sessions: List<LocalSessionRecord>,
    val credentialRevision: Revision,
    val credential: CredentialWire?,
) {
    fun value(): ProtectedLocalAccount {
        require(schema == ACCOUNT_WIRE_SCHEMA && sessions.size <= MAX_ACCOUNT_SESSIONS)
        if (revision.value == Long.MAX_VALUE || generation.value == Long.MAX_VALUE) {
            require(revision.value == Long.MAX_VALUE && generation.value == Long.MAX_VALUE &&
                !enabled && sessions.all { it.status == SessionStatus.REVOKED })
        }
        val state = LocalAccountSessionState(scope, actorId, enabled, revision, generation, sessions)
        val record = credential?.value()
        require(record == null || record.binding == CredentialBinding(scope, actorId, credentialRevision))
        return ProtectedLocalAccount(state, credentialRevision, record)
    }

    companion object {
        fun capture(value: ProtectedLocalAccount): AccountWire {
            val state = value.state
            return AccountWire(ACCOUNT_WIRE_SCHEMA, state.scope, state.actorId, state.enabled,
                state.revision, state.generation, boundedSnapshot(state.sessions, MAX_ACCOUNT_SESSIONS),
                value.credentialRevision, value.credential?.let { CredentialWire.capture(it) })
        }
    }
}

@Serializable
private class CredentialWire(
    val algorithm: String,
    val scope: BranchScope,
    val actorId: DomainId,
    val credentialRevision: Revision,
    val iterations: Int,
    val pepperKeyId: DomainId,
    val saltHex: String,
    val verifierHex: String,
) {
    fun value(): PasswordHashRecord {
        require(algorithm == PASSWORD_WIRE_ALGORITHM)
        val salt = readHex(saltHex, 16, 64)
        try {
            val verifier = readHex(verifierHex, 32, 32)
            try {
                return PasswordHashRecord(CredentialBinding(scope, actorId, credentialRevision),
                    PasswordHashParameters(iterations), ProtectedPepperReference(pepperKeyId), salt, verifier)
            } finally { verifier.fill(0) }
        } finally { salt.fill(0) }
    }

    companion object {
        fun capture(value: PasswordHashRecord): CredentialWire {
            require(value.algorithm == PASSWORD_WIRE_ALGORITHM)
            val salt = value.copySalt()
            try {
                val verifier = value.copyVerifier()
                try {
                    return CredentialWire(value.algorithm, value.binding.scope, value.binding.actorId,
                        value.binding.credentialRevision, value.parameters.iterations, value.pepperReference.keyId,
                        writeHex(salt), writeHex(verifier))
                } finally { verifier.fill(0) }
            } finally { salt.fill(0) }
        }
    }
}

private fun writeHex(bytes: ByteArray): String {
    val digits = "0123456789abcdef"
    return buildString(bytes.size * 2) {
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            append(digits[value ushr 4]); append(digits[value and 15])
        }
    }
}

private fun readHex(text: String, minimumBytes: Int, maximumBytes: Int): ByteArray {
    require(text.length in minimumBytes * 2..maximumBytes * 2 && text.length % 2 == 0)
    require(text.all { it in '0'..'9' || it in 'a'..'f' })
    fun nibble(char: Char): Int = if (char <= '9') char.code - '0'.code else char.code - 'a'.code + 10
    return ByteArray(text.length / 2) { (nibble(text[it * 2]) * 16 + nibble(text[it * 2 + 1])).toByte() }
}

@Serializable
private class PolicyWire(
    val schema: String,
    val scope: BranchScope,
    val actorId: DomainId,
    val revision: Revision,
    val grants: List<GrantWire>,
    val sessionPolicy: SessionPolicyWire,
    val throttlePolicy: ThrottlePolicyWire,
) {
    fun value(): ActivatedLocalPolicy {
        require(schema == POLICY_WIRE_SCHEMA && grants.size <= MAX_POLICY_GRANTS)
        return ActivatedLocalPolicy(scope, actorId, revision, grants.map { it.value(scope) },
            sessionPolicy.value(), throttlePolicy.value())
    }

    fun requireByteBudget() {
        // Legal item counts can still exceed one MiB (1024 grants x 256 targets).
        // Count the exact framing plus each bounded grant before allocating that whole string.
        val empty = PolicyWire(schema, scope, actorId, revision, emptyList(), sessionPolicy, throttlePolicy)
        val framing = writeProtected(ProtectedLocalTable.EFFECTIVE_POLICY, PolicyWire.serializer(), empty)
        var size = framing.size.toLong()
        framing.fill(0)
        grants.forEachIndexed { index, grant ->
            val bytes = writeProtected(ProtectedLocalTable.EFFECTIVE_POLICY, GrantWire.serializer(), grant)
            size += bytes.size + (if (index == 0) 0 else 1)
            bytes.fill(0)
            require(size <= ProtectedLocalTable.EFFECTIVE_POLICY.maximumPlaintextBytes)
        }
    }

    companion object {
        fun capture(value: ActivatedLocalPolicy): PolicyWire = PolicyWire(POLICY_WIRE_SCHEMA,
            value.scope, value.actorId, value.revision,
            boundedSnapshot(value.grants, MAX_POLICY_GRANTS).map { GrantWire.capture(it) },
            SessionPolicyWire(value.sessionPolicy.maximumConcurrentSessions), ThrottlePolicyWire.capture(value.throttlePolicy))
    }
}

@Serializable
private class GrantWire(
    val permission: PermissionWire,
    val scope: GrantScopeWire,
    val effect: GrantEffect,
    val limits: LimitsWire,
    val ownerAccess: OwnerAccess,
    val namedSubjectId: DomainId?,
    val targetIds: List<DomainId>?,
    val delegationCeiling: LimitsWire?,
) {
    fun value(policyScope: BranchScope): PermissionGrant {
        val grantScope = scope.value()
        require(when (grantScope) {
            is BranchScope -> grantScope == policyScope
            is CompanyScope -> grantScope == policyScope.company()
        })
        return PermissionGrant(Permission(permission.action, permission.verb), grantScope, effect,
            limits.value(), ownerAccess, namedSubjectId,
            targetIds?.let { checkedSet(it, MAX_GRANT_TARGETS, minimum = 1) }, delegationCeiling?.value())
    }

    companion object {
        fun capture(value: PermissionGrant): GrantWire = GrantWire(
            PermissionWire(value.permission.action, value.permission.verb), GrantScopeWire.capture(value.scope),
            value.effect, LimitsWire.capture(value.limits), value.ownerAccess, value.namedSubjectId,
            value.targetIds?.let { boundedSnapshot(it, MAX_GRANT_TARGETS).sortedBy { id -> id.value } },
            value.delegationCeiling?.let { LimitsWire.capture(it) })
    }
}

@Serializable
private class PermissionWire(val action: SecurityAction, val verb: PermissionVerb)

/** An explicit null branchId retains CompanyScope; it is not silently retagged as local. */
@Serializable
private class GrantScopeWire(val organizationId: DomainId, val companyId: DomainId, val branchId: DomainId?) {
    fun value(): TenantScope = branchId?.let { BranchScope(organizationId, companyId, it) }
        ?: CompanyScope(organizationId, companyId)

    companion object {
        fun capture(value: TenantScope): GrantScopeWire = GrantScopeWire(value.organizationId, value.companyId,
            when (value) { is BranchScope -> value.branchId; is CompanyScope -> null })
    }
}

@Serializable
private class LimitsWire(val maximumAmount: Money?, val maximumPercentageBasisPoints: Int?) {
    fun value(): PermissionLimits = PermissionLimits(maximumAmount, maximumPercentageBasisPoints)
    companion object {
        fun capture(value: PermissionLimits): LimitsWire = LimitsWire(value.maximumAmount, value.maximumPercentageBasisPoints)
    }
}

@Serializable
private class SessionPolicyWire(val maximumConcurrentSessions: Int) {
    fun value(): LocalSessionPolicy = LocalSessionPolicy(maximumConcurrentSessions)
}

@Serializable
private class ThrottlePolicyWire(
    val freeFailures: Int,
    @Serializable(with = MinorUnitsSerializer::class) val initialDelayMillis: Long,
    @Serializable(with = MinorUnitsSerializer::class) val maximumDelayMillis: Long,
    @Serializable(with = MinorUnitsSerializer::class) val attemptTimeoutMillis: Long,
) {
    fun value(): ThrottlePolicy = ThrottlePolicy(freeFailures, initialDelayMillis, maximumDelayMillis, attemptTimeoutMillis)
    companion object {
        fun capture(value: ThrottlePolicy): ThrottlePolicyWire = ThrottlePolicyWire(value.freeFailures,
            value.initialDelayMillis, value.maximumDelayMillis, value.attemptTimeoutMillis)
    }
}

@Serializable
private class AdmissionWire(
    val schema: String,
    val scope: BranchScope,
    val deviceId: DomainId,
    val revision: Revision,
    val admitted: Boolean,
    val publicKeyIdentifier: String?,
    val publicFingerprint: String?,
    val capabilities: List<String>,
) {
    fun value(): LocalDeviceAdmission {
        require(schema == ADMISSION_WIRE_SCHEMA)
        return LocalDeviceAdmission(scope, deviceId, revision, admitted, publicKeyIdentifier, publicFingerprint,
            checkedSet(capabilities, MAX_ADMISSION_CAPABILITIES))
    }

    companion object {
        fun capture(value: LocalDeviceAdmission): AdmissionWire = AdmissionWire(ADMISSION_WIRE_SCHEMA,
            value.scope, value.deviceId, value.revision, value.admitted, value.publicKeyIdentifier, value.publicFingerprint,
            boundedSnapshot(value.capabilities, MAX_ADMISSION_CAPABILITIES).sorted())
    }
}

@Serializable
private class ThrottleWire(
    val schema: String,
    val scope: BranchScope,
    val actorId: DomainId,
    val dimension: DimensionWire,
    val revision: Revision,
    val failures: Int,
    val lastObservation: MonotonicMoment,
    @Serializable(with = MinorUnitsSerializer::class) val remainingDelayMillis: Long,
    val activeAttempt: AttemptWire?,
) {
    fun value(): PersistentThrottleState {
        require(schema == THROTTLE_WIRE_SCHEMA)
        return PersistentThrottleState(ThrottleKey(scope, actorId, dimension.value()), revision, failures,
            lastObservation, remainingDelayMillis, activeAttempt?.value())
    }

    companion object {
        fun capture(value: PersistentThrottleState): ThrottleWire = ThrottleWire(THROTTLE_WIRE_SCHEMA,
            value.key.scope, value.key.actorId, DimensionWire.capture(value.key.dimension), value.revision,
            value.failures, value.lastObservation, value.remainingDelayMillis,
            value.activeAttempt?.let { AttemptWire(it.attemptId, it.startedAt) })
    }
}

@Serializable
private class DimensionWire(val kind: String, val deviceId: DomainId?) {
    fun value(): ThrottleDimension = when (kind) {
        "account" -> { require(deviceId == null); ThrottleDimension.Account }
        "device" -> ThrottleDimension.Device(requireNotNull(deviceId))
        else -> error("Unknown throttle dimension")
    }
    companion object {
        fun capture(value: ThrottleDimension): DimensionWire = when (value) {
            ThrottleDimension.Account -> DimensionWire("account", null)
            is ThrottleDimension.Device -> DimensionWire("device", value.deviceId)
        }
    }
}

@Serializable
private class AttemptWire(val attemptId: DomainId, val startedAt: MonotonicMoment) {
    fun value(): ThrottleAttempt = ThrottleAttempt(attemptId, startedAt)
}
