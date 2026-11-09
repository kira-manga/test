package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Authenticated local login index and activated token lifetime; never a cloud lookup. */
public class LocalAuthConfiguration(
    public val scope: BranchScope,
    public val actorId: DomainId,
    public val revision: Revision,
    public val login: LocalLoginIdentifier,
    public val tokens: LocalTokenPolicy,
) {
    public val loginKey: String get() = localLoginKey(scope, login)
    override fun toString(): String = "LocalAuthConfiguration([REDACTED])"
}

/** Lookup index only, not a credential or secrecy guarantee for low-entropy login identifiers. */
public fun localLoginKey(scope: BranchScope, login: LocalLoginIdentifier): String = sha256((
    "ukkera.local-login-index/sha256/v1\u0000${scope.organizationId.value}\u0000${scope.companyId.value}\u0000" +
        "${scope.branchId.value}\u0000${login.value}"
    ).encodeToByteArray(throwOnInvalidSequence = true)).value

/** Separate hash domain prevents an access secret being used as a refresh secret. */
public class LocalRefreshTokenHash private constructor(private val value: String) {
    public fun canonicalSha256(): String = value

    public fun matchesSecret(secret: ByteArray): Boolean {
        val actual = hashSecret(secret)
        val expectedBytes = decode(value)
        val actualBytes = decode(actual.value)
        return try {
            equalLocalAccessDigests(expectedBytes, actualBytes)
        } finally {
            expectedBytes.fill(0)
            actualBytes.fill(0)
        }
    }

    override fun equals(other: Any?): Boolean = other is LocalRefreshTokenHash && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = "LocalRefreshTokenHash([REDACTED])"

    public companion object {
        public const val HASH_DOMAIN: String = "ukkera.local-refresh-token/sha256/v1"
        public fun fromCanonicalSha256(value: String): LocalRefreshTokenHash {
            PayloadDigest(value)
            return LocalRefreshTokenHash(value)
        }
        public fun hashSecret(secret: ByteArray): LocalRefreshTokenHash {
            require(secret.size == 32)
            val domain = HASH_DOMAIN.encodeToByteArray()
            val input = ByteArray(domain.size + 1 + 32)
            return try {
                domain.copyInto(input)
                secret.copyInto(input, domain.size + 1)
                fromCanonicalSha256(sha256(input).value)
            } finally {
                input.fill(0)
            }
        }
        private fun decode(value: String): ByteArray = ByteArray(32) { index ->
            ((value[index * 2].digitToInt(16) shl 4) or value[index * 2 + 1].digitToInt(16)).toByte()
        }
    }
}

/**
 * One immutable refresh record per access/session identity. Rotation revokes the old session
 * and appends a new session+access+refresh record atomically. The original work origin/deadline
 * is copied, never renewed by a refresh. Reboot/service change requires LOCAL password login.
 */
public class LocalRefreshIssuance(
    public val scope: BranchScope,
    public val actorId: DomainId,
    public val sessionId: DomainId,
    public val deviceId: DomainId,
    public val generation: SessionGeneration,
    public val credentialRevisionAtIssue: Revision,
    public val admissionRevisionAtIssue: Revision,
    public val configurationRevisionAtIssue: Revision,
    public val issuerContextId: DomainId,
    public val issuedAt: MonotonicMoment,
    public val originalSessionId: DomainId,
    public val workStartedAt: MonotonicMoment,
    public val workLifetimeMillis: Long,
    public val tokenHash: LocalRefreshTokenHash,
) {
    public val revision: Revision = Revision(0)
    public val expiresAtElapsedMillis: Long
    init {
        require(generation.value in 1 until Long.MAX_VALUE)
        require(workLifetimeMillis > 0 && workLifetimeMillis <= Long.MAX_VALUE - workStartedAt.elapsedMillis)
        expiresAtElapsedMillis = workStartedAt.elapsedMillis + workLifetimeMillis
        require(issuedAt.bootId == workStartedAt.bootId && issuedAt.elapsedMillis >= workStartedAt.elapsedMillis &&
            issuedAt.elapsedMillis < expiresAtElapsedMillis)
    }
    override fun toString(): String = "LocalRefreshIssuance([REDACTED])"
}

/** Pure supplied-facts check. Only the protected transaction/physical peer establishes provenance. */
public object LocalRefreshEligibility {
    public fun matchesCurrentFacts(
        refresh: LocalRefreshIssuance,
        access: LocalAccessIssuance,
        account: ProtectedLocalAccount,
        admission: LocalDeviceAdmission,
        configuration: LocalAuthConfiguration,
        current: LocalAuthenticationObservation,
    ): Boolean {
        val state = account.state
        if (refresh.scope != access.scope || refresh.actorId != access.actorId || refresh.sessionId != access.sessionId ||
            refresh.deviceId != access.deviceId || refresh.generation != access.generation ||
            refresh.credentialRevisionAtIssue != access.credentialRevisionAtIssue ||
            refresh.admissionRevisionAtIssue != access.admissionRevisionAtIssue ||
            refresh.issuerContextId != access.issuerContextId || refresh.issuedAt != access.issuedAt) return false
        if (state.scope != refresh.scope || state.actorId != refresh.actorId || !state.enabled ||
            state.revision.value == Long.MAX_VALUE || state.generation.value == Long.MAX_VALUE || account.credential == null ||
            account.credentialRevision != refresh.credentialRevisionAtIssue) return false
        if (admission.scope != refresh.scope || admission.deviceId != refresh.deviceId || !admission.admitted ||
            admission.revision != refresh.admissionRevisionAtIssue) return false
        if (configuration.scope != refresh.scope || configuration.actorId != refresh.actorId ||
            configuration.revision != refresh.configurationRevisionAtIssue) return false
        val session = state.sessions.singleOrNull { it.sessionId == refresh.sessionId } ?: return false
        if (session.deviceId != refresh.deviceId || session.generation != refresh.generation ||
            session.status != SessionStatus.ACTIVE || session.authenticationPurpose != AuthenticationPurpose.INTERACTIVE) return false
        return current.issuerContextId == refresh.issuerContextId && current.moment.bootId == refresh.issuedAt.bootId &&
            current.moment.elapsedMillis >= refresh.issuedAt.elapsedMillis && current.moment.elapsedMillis < refresh.expiresAtElapsedMillis
    }
}

public enum class LocalAuthEventKind {
    ACCOUNT_PROVISIONED, DEVICE_ADMITTED, SIGNED_IN, REFRESHED, LOGGED_OUT, CREDENTIAL_RESET,
    SESSION_REVOKED, ACCOUNT_DISABLED, SESSIONS_ARCHIVED, RESTORE_QUARANTINED,
    POLICY_ACTIVATED, ACCOUNT_REACTIVATED, ACTION_APPROVAL_ISSUED, ACTION_APPROVAL_CONSUMED,
    DEVICE_REVOKED,
    BRANCH_INSTALLED,
    /** Version-two protected audit: fences an uncompleted administrative request, with no account effect. */
    ADMINISTRATION_CANCELLED,
    /** A separately versioned permanent tombstone for an issued, unused action approval. */
    ACTION_APPROVAL_RETIRED,
    /** Current protected private-publication access; full immutable request facts stay in history. */
    SERIAL_PUBLICATION_REQUEST,
}

/** Private durable authentication audit. No password, verifier, secret or bearer is retained. */
public class LocalAuthEvent(
    public val scope: BranchScope,
    public val eventId: DomainId,
    public val kind: LocalAuthEventKind,
    public val targetId: DomainId,
    public val operatorId: DomainId?,
    public val sessionId: DomainId?,
    public val accountRevision: Revision?,
    public val at: MonotonicMoment,
    public val contentDigest: PayloadDigest?,
    /** Only BRANCH_INSTALLED uses its separately versioned immutable installation profile. */
    public val installation: LocalBranchInstallationRecord? = null,
) {
    public val revision: Revision = Revision(0)
    init {
        require((kind == LocalAuthEventKind.BRANCH_INSTALLED) == (installation != null))
        if (kind == LocalAuthEventKind.ADMINISTRATION_CANCELLED)
            require(operatorId != null && contentDigest != null && accountRevision == null)
        if (kind == LocalAuthEventKind.ACTION_APPROVAL_RETIRED)
            require(operatorId != null && sessionId != null && contentDigest != null && accountRevision != null && eventId != targetId)
        if (kind == LocalAuthEventKind.SERIAL_PUBLICATION_REQUEST)
            require(operatorId != null && sessionId != null && contentDigest != null && accountRevision != null && eventId != targetId)
        installation?.let { record ->
            val identity = record.request.identity
            require(scope == identity.scope && eventId == identity.installationId && targetId == scope.branchId &&
                operatorId == identity.responsibleHumanId && sessionId == null && accountRevision == Revision(0) &&
                at == record.installedAt && contentDigest == record.genesis)
        }
    }
    override fun toString(): String = "LocalAuthEvent([REDACTED])"
}

/** Exact revoked historical identity, archived before removing it from the bounded active aggregate. */
public class LocalSessionArchive(public val session: LocalSessionRecord, public val archivedAt: MonotonicMoment) {
    public val revision: Revision = Revision(0)
    init { require(session.status == SessionStatus.REVOKED) }
    override fun toString(): String = "LocalSessionArchive([REDACTED])"
}
