package com.ukkera.pos.app.gateway

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.LocalSessionTokens
import com.ukkera.pos.security.MonotonicMoment
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.sync.Mutex

/** Trusted custody coordinate, never an authenticated principal or a token. */
public data class LocalClientDeviceIdentity(public val scope: BranchScope, public val deviceId: DomainId)

/**
 * Separate encrypted client authentication/revocation storage. It is NOT the master session
 * registry, a financial journal, or physical-outcome capacity. Only the platform implementations
 * in this module can construct it; there is no in-memory success or plaintext implementation.
 * The platform root owns its lifetime independently of Compose and the branch master.
 */
public abstract class LocalClientCredentialJournal internal constructor(public val owner: LocalClientDeviceIdentity) {
    internal val requests: Mutex = Mutex()
    internal abstract suspend fun read(): JournalRead<StoredClientCredentials>
    internal abstract suspend fun replace(expectedRevision: Long, next: StoredClientCredentials): JournalWrite
    public abstract suspend fun close()
}

internal const val MAX_CLIENT_AUTH_RECORDS: Int = 32
internal const val MAX_CLIENT_ENROLLMENTS: Int = 32
internal const val MAX_CLIENT_CREDENTIAL_BYTES: Int = 4 * 1024 * 1024

/** A client-local dispatch identity, NOT a server authentication idempotency key. No password. */
internal data class StoredLoginAttemptV1(
    val id: DomainId,
    val login: LocalLoginIdentifier,
    val binding: LocalClientAuthenticationBinding,
    val explicitDeviceMove: Boolean,
) {
    constructor(id: DomainId, login: LocalLoginIdentifier, serviceIncarnationId: DomainId, explicitDeviceMove: Boolean) :
        this(id, login, LocalClientAuthenticationBinding.MasterServiceIncarnation(serviceIncarnationId), explicitDeviceMove)

    /** Historical profile only. A tagged remote correlation is never a Master incarnation. */
    val serviceIncarnationId: DomainId get() = requireNotNull(binding as? LocalClientAuthenticationBinding.MasterServiceIncarnation)
        .serviceIncarnationId

    override fun toString(): String = "StoredLoginAttemptV1([REDACTED])"
}

internal enum class StoredSessionPhaseV1 { RETAINED, REFRESH_PENDING, REVOCATION_PENDING }

/**
 * RETAINED means revocation material after a restart, NOT permission to restore a login. A live
 * interactive attachment exists only in memory after actual password sign-in. REFRESH_PENDING
 * is durable BEFORE refresh dispatch and is never retried: the old token cannot revoke an
 * unknown successor. originalRevoked does not resolve that successor ambiguity.
 */
internal class StoredClientSessionV1(
    val localId: DomainId,
    val identity: LocalClientIdentity,
    val login: LocalLoginIdentifier,
    val binding: LocalClientAuthenticationBinding,
    val sessionId: DomainId,
    val generation: SessionGeneration,
    val accessExpiresAt: MonotonicMoment,
    val workExpiresAt: MonotonicMoment,
    val phase: StoredSessionPhaseV1,
    val originalRevoked: Boolean,
    val access: OpaqueCredential,
    val refresh: OpaqueCredential,
) {
    constructor(localId: DomainId, identity: LocalClientIdentity, login: LocalLoginIdentifier,
        serviceIncarnationId: DomainId, sessionId: DomainId, generation: SessionGeneration,
        accessExpiresAt: MonotonicMoment, workExpiresAt: MonotonicMoment, phase: StoredSessionPhaseV1,
        originalRevoked: Boolean, access: OpaqueCredential, refresh: OpaqueCredential) :
        this(localId, identity, login, LocalClientAuthenticationBinding.MasterServiceIncarnation(serviceIncarnationId),
            sessionId, generation, accessExpiresAt, workExpiresAt, phase, originalRevoked, access, refresh)

    /** Historical profile only. A tagged remote correlation is never a Master incarnation. */
    val serviceIncarnationId: DomainId get() = requireNotNull(binding as? LocalClientAuthenticationBinding.MasterServiceIncarnation)
        .serviceIncarnationId

    fun validate(owner: LocalClientDeviceIdentity) {
        require(identity.scope == owner.scope && identity.deviceId == owner.deviceId)
        require(generation.value in 1 until Long.MAX_VALUE)
        require(accessExpiresAt.bootId == workExpiresAt.bootId && accessExpiresAt.elapsedMillis <= workExpiresAt.elapsedMillis)
        require(!originalRevoked || phase == StoredSessionPhaseV1.REFRESH_PENDING)
        access.useBytes { requireToken(it, "ULA1", sessionId) }
        refresh.useBytes { requireToken(it, "ULR1", sessionId) }
    }

    /** Shares owned secrets within one short-lived snapshot; do not outlive/destroy its owner. */
    fun phase(next: StoredSessionPhaseV1, revoked: Boolean = originalRevoked): StoredClientSessionV1 =
        StoredClientSessionV1(localId, identity, login, binding, sessionId, generation,
            accessExpiresAt, workExpiresAt, next, revoked, access, refresh)

    fun destroy() { access.destroy(); refresh.destroy() }
    override fun toString(): String = "StoredClientSessionV1([REDACTED])"

    companion object {
        fun capture(localId: DomainId, incarnation: DomainId, tokens: LocalSessionTokens): StoredClientSessionV1 =
            capture(localId, LocalClientAuthenticationBinding.MasterServiceIncarnation(incarnation), tokens)

        fun capture(localId: DomainId, binding: LocalClientAuthenticationBinding, tokens: LocalSessionTokens): StoredClientSessionV1 {
            val access = tokens.access.useBytes(::OpaqueCredential)
            try {
                val refresh = tokens.refresh.useBytes(::OpaqueCredential)
                val view = tokens.view
                return StoredClientSessionV1(localId, LocalClientIdentity(view.scope, view.actorId, view.deviceId),
                    view.login, binding, view.sessionId, view.generation, view.accessExpiresAt, view.workExpiresAt,
                    StoredSessionPhaseV1.RETAINED, false, access, refresh)
            } catch (failure: Throwable) { access.destroy(); throw failure }
        }
    }
}

private fun requireToken(bytes: ByteArray, prefix: String, sessionId: DomainId) {
    // Format checking only. Possession and a syntactically valid prefix do NOT authenticate.
    require(bytes.size == 52 && prefix.indices.all { bytes[it] == prefix[it].code.toByte() })
    val locator = sessionId.value.replace("-", "")
    require((0 until 16).all { index -> bytes[4 + index] ==
        ((locator[index * 2].digitToInt(16) shl 4) or locator[index * 2 + 1].digitToInt(16)).toByte() })
}

internal class StoredClientCredentials(
    val revision: Long = 0,
    val attempts: List<StoredLoginAttemptV1> = emptyList(),
    val sessions: List<StoredClientSessionV1> = emptyList(),
    val enrollments: List<StoredClientEnrollmentV1> = emptyList(),
    val administrations: List<StoredClientAdministrationV1> = emptyList(),
    /** V1/v2 stay byte-exact. Remote attachment bindings explicitly select tagged profile v3. */
    val formatVersion: Int = 1,
) {
    fun validate(owner: LocalClientDeviceIdentity) {
        require(revision >= 0 && attempts.size + sessions.size <= MAX_CLIENT_AUTH_RECORDS && enrollments.size <= MAX_CLIENT_ENROLLMENTS)
        require(attempts.map { it.id }.distinct().size == attempts.size)
        require(sessions.map { it.localId }.distinct().size == sessions.size && sessions.map { it.sessionId }.distinct().size == sessions.size)
        sessions.forEach { it.validate(owner) }
        require(enrollments.map { it.requestId }.distinct().size == enrollments.size)
        require(enrollments.count { it.outcome == StoredEnrollmentOutcomeV1.PENDING } <= 1)
        enrollments.forEach { it.validate(owner) }
        require(formatVersion in 1..3 && (formatVersion >= 2 || administrations.isEmpty()))
        require(formatVersion == 3 || attempts.all { it.binding is LocalClientAuthenticationBinding.MasterServiceIncarnation } &&
            sessions.all { it.binding is LocalClientAuthenticationBinding.MasterServiceIncarnation })
        require(administrations.size <= MAX_CLIENT_ADMINISTRATIONS)
        require(administrations.map { it.command.requestId }.distinct().size == administrations.size)
        require(administrations.map { it.owner.actorId }.distinct().size == administrations.size)
        require(administrations.none { admin -> enrollments.any { it.requestId == admin.command.requestId } })
        administrations.forEach { it.validate(owner) }
    }

    /** Secret references are shared, not copied; the caller must destroy the original snapshot. */
    fun next(
        attempts: List<StoredLoginAttemptV1> = this.attempts,
        sessions: List<StoredClientSessionV1> = this.sessions,
        enrollments: List<StoredClientEnrollmentV1> = this.enrollments,
        administrations: List<StoredClientAdministrationV1> = this.administrations,
    ): StoredClientCredentials {
        check(revision < Long.MAX_VALUE)
        return StoredClientCredentials(revision + 1, attempts, sessions, enrollments, administrations,
            when {
                formatVersion == 3 || attempts.any { it.binding is LocalClientAuthenticationBinding.RemoteOriginalAttachment } ||
                    sessions.any { it.binding is LocalClientAuthenticationBinding.RemoteOriginalAttachment } -> 3
                formatVersion == 2 || administrations.isNotEmpty() -> 2
                else -> 1
            })
    }

    fun destroy() { sessions.forEach { it.destroy() } }
    override fun toString(): String = "StoredClientCredentials([REDACTED])"
}

/** Fixed local credential-profile tags, not RPC fields or physical authentication identities. */
internal fun LocalClientAuthenticationBinding.credentialTagV3(): Byte = when (this) {
    is LocalClientAuthenticationBinding.MasterServiceIncarnation -> 1
    is LocalClientAuthenticationBinding.RemoteOriginalAttachment -> 2
}

internal fun LocalClientAuthenticationBinding.credentialIdV3(): DomainId = when (this) {
    is LocalClientAuthenticationBinding.MasterServiceIncarnation -> serviceIncarnationId
    is LocalClientAuthenticationBinding.RemoteOriginalAttachment -> connectionId
}

internal fun credentialBindingV3(tag: Byte, id: DomainId): LocalClientAuthenticationBinding = when (tag.toInt()) {
    1 -> LocalClientAuthenticationBinding.MasterServiceIncarnation(id)
    2 -> LocalClientAuthenticationBinding.RemoteOriginalAttachment(id)
    else -> error("client.credentials.binding")
}
