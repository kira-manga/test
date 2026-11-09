package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/**
 * An immutable, redacted SHA-256 value for protected local access issuance records. Parsing a
 * canonical hash establishes syntax only, not its origin, secret entropy or authentication.
 * Explicit export is for the protected codec, never ordinary sync, logs or bearer transport.
 * Value equality is not a constant-time bearer-verification API.
 */
public class LocalAccessTokenHash private constructor(private val value: String) {
    public fun canonicalSha256(): String = value

    /**
     * Compare the established domain-separated hash using the platform fixed-size digest
     * comparison primitive. A match is NOT authentication: current protected identity, original
     * issuance, transport, service/time continuity and authorization still need verification.
     * Provider failure propagates; it must not silently become an incorrect-secret result.
     * Caller bytes are unchanged. Only owned byte buffers can be cleared, best effort; this
     * makes no total-erasure, password-verification or end-to-end constant-time guarantee.
     */
    public fun matchesSecret(secret: ByteArray): Boolean = localAccessSecretMatches(this, secret)

    override fun equals(other: Any?): Boolean = other is LocalAccessTokenHash && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = "LocalAccessTokenHash([REDACTED])"

    public companion object {
        public const val SECRET_BYTES: Int = 32
        public const val HASH_DOMAIN: String = "ukkera.local-access-token/sha256/v1"

        public fun fromCanonicalSha256(value: String): LocalAccessTokenHash {
            PayloadDigest(value) // Reuse the existing exact lowercase, 64-character SHA-256 syntax.
            return LocalAccessTokenHash(value)
        }

        /**
         * Fixed profile: SHA-256(UTF-8(HASH_DOMAIN) || 0x00 || secret). The future trusted issuer
         * must independently generate all 32 secret bytes with a qualified CSPRNG. Length alone
         * cannot prove that provenance. This helper neither generates a secret nor defines the
         * eventual opaque locator+secret wire format, verifies a bearer, or creates a principal.
         * Caller bytes are unchanged; clearing the owned buffer is best effort, not total erasure.
         */
        public fun hashSecret(secret: ByteArray): LocalAccessTokenHash {
            require(secret.size == SECRET_BYTES) { "Local access secret must contain exactly 32 bytes" }
            val domain = HASH_DOMAIN.encodeToByteArray()
            val input = ByteArray(domain.size + 1 + SECRET_BYTES)
            return try {
                domain.copyInto(input)
                // The newly allocated byte between the domain and secret remains exactly zero.
                secret.copyInto(input, domain.size + 1)
                fromCanonicalSha256(sha256(input).value)
            } finally {
                input.fill(0)
            }
        }
    }
}

/**
 * Inert immutable facts, NOT an authentication receipt, principal or permission. The initial
 * profile supports INTERACTIVE access only. Protected persistence must allow exactly one record
 * per (scope, sessionId), at revision zero: no replacement, extension, restamping or session reuse.
 * Existing sessions without an issuance stay unusable; these facts must never be backfilled by a
 * current-authorization read. Account/session revocation supplies status; no duplicate status is
 * stored here. Refresh, rotation, approvals and recovery authentication require separate profiles.
 *
 * The positive duration is explicit, not a default or activated lifetime policy. A later login
 * path must select it from qualified activated configuration. Credential/admission revisions are
 * the immutable ORIGINAL bindings; current grants and policy revision are read separately.
 * [issuerContextId] identifies a separately qualified live service incarnation, not a boot, key,
 * device, authority epoch or proof of rollback resistance. [issuedAt] is supplied time data, not
 * qualification of a clock or its suspend continuity.
 */
public class LocalAccessIssuance(
    public val scope: BranchScope,
    public val actorId: DomainId,
    public val sessionId: DomainId,
    public val deviceId: DomainId,
    public val generation: SessionGeneration,
    public val credentialRevisionAtIssue: Revision,
    public val admissionRevisionAtIssue: Revision,
    public val issuerContextId: DomainId,
    public val issuedAt: MonotonicMoment,
    public val validForMillis: Long,
    public val tokenHash: LocalAccessTokenHash,
) {
    public val revision: Revision = Revision(0)
    public val authenticationPurpose: AuthenticationPurpose = AuthenticationPurpose.INTERACTIVE
    public val expiresAtElapsedMillis: Long

    init {
        require(generation.value in 1..(Long.MAX_VALUE - 1)) { "Invalid local access issuance generation" }
        require(validForMillis > 0) { "Local access validity must be positive" }
        require(validForMillis <= Long.MAX_VALUE - issuedAt.elapsedMillis) {
            "Local access deadline is not representable"
        }
        expiresAtElapsedMillis = issuedAt.elapsedMillis + validForMillis
    }

    override fun toString(): String = "LocalAccessIssuance([REDACTED])"
}

/**
 * Pure comparison of supplied facts, NOT authentication, authorization or evidence of freshness.
 * The identity overload also accepts an UNTRUSTED candidate from an authentication adapter;
 * its type does not establish trust. [matchesCurrentFacts] does not inspect any bearer/hash,
 * issue authority, read storage, qualify transport/time, or track clock high-water.
 * In particular, credentialPresent is just a supplied fact, never password-verification proof.
 *
 * A trusted adapter must obtain every input from its same live protected transaction, enforce
 * borrower/environment lifetime and qualified time continuity, and supply the current activated
 * policy separately. Missing inputs deny there. A true result alone permits no operation or
 * disclosure. The predicate is deliberately stateless; source-wide discontinuity handling belongs
 * to the adapter, not to an expired or mismatching issuance presented by one caller.
 */
public object LocalAccessEligibility {
    public fun matchesCurrentFacts(
        principal: LocalEmployeePrincipal,
        issuance: LocalAccessIssuance,
        account: LocalAccountSessionState,
        credentialRevision: Revision,
        credentialPresent: Boolean,
        admission: LocalDeviceAdmission,
        currentIssuerContextId: DomainId,
        now: MonotonicMoment,
    ): Boolean = matchesCurrentFacts(
        VerifiedLocalIdentity(principal.actorId, principal.scope, principal.deviceId, principal.sessionId,
            principal.sessionGeneration, principal.admissionRevision, principal.authenticationPurpose),
        issuance, account, credentialRevision, credentialPresent, admission, currentIssuerContextId, now,
    )

    public fun matchesCurrentFacts(
        identity: VerifiedLocalIdentity,
        issuance: LocalAccessIssuance,
        account: LocalAccountSessionState,
        credentialRevision: Revision,
        credentialPresent: Boolean,
        admission: LocalDeviceAdmission,
        currentIssuerContextId: DomainId,
        now: MonotonicMoment,
    ): Boolean {
        if (identity.purpose != AuthenticationPurpose.INTERACTIVE) return false
        if (issuance.scope != identity.scope || account.scope != identity.scope || admission.scope != identity.scope) {
            return false
        }
        if (issuance.actorId != identity.actorId || account.actorId != identity.actorId ||
            issuance.sessionId != identity.sessionId || issuance.deviceId != identity.deviceId ||
            admission.deviceId != identity.deviceId || issuance.generation != identity.sessionGeneration
        ) return false
        if (!account.enabled || account.revision.value == Long.MAX_VALUE || account.generation.value == Long.MAX_VALUE ||
            !credentialPresent || credentialRevision != issuance.credentialRevisionAtIssue
        ) return false
        if (!admission.admitted || admission.revision != issuance.admissionRevisionAtIssue ||
            identity.admissionRevision != issuance.admissionRevisionAtIssue
        ) return false
        val session = account.sessions.singleOrNull { it.sessionId == issuance.sessionId } ?: return false
        if (session.scope != issuance.scope || session.actorId != issuance.actorId || session.deviceId != issuance.deviceId ||
            session.generation != issuance.generation || session.generation.value > account.generation.value ||
            session.status != SessionStatus.ACTIVE || session.authenticationPurpose != AuthenticationPurpose.INTERACTIVE
        ) return false
        return currentIssuerContextId == issuance.issuerContextId && now.bootId == issuance.issuedAt.bootId &&
            now.elapsedMillis >= issuance.issuedAt.elapsedMillis && now.elapsedMillis < issuance.expiresAtElapsedMillis
    }
}
