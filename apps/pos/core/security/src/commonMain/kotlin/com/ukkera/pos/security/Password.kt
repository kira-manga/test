package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

public enum class KeyProtectionStatus {
    QUALIFIED_SUPERVISED_SERVICE_CONTEXT,
    UNQUALIFIED_CONTEXT,
    UNSUPPORTED,
}

public enum class ProtectedKeyFailure {
    CONTEXT_NOT_QUALIFIED,
    KEY_LOCKED,
    KEY_UNAVAILABLE,
    CORRUPT_PROTECTED_STATE,
    PROVIDER_FAILURE,
}

public data class ProtectedPepperReference(public val keyId: DomainId)

public sealed interface PepperMacResult {
    public class Authenticated(tag: ByteArray) : PepperMacResult {
        init {
            require(tag.size == 32)
        }

        private val value: ByteArray = tag.copyOf()

        public fun copyTag(): ByteArray = value.copyOf()
        override fun toString(): String = "PepperMacResult.Authenticated([REDACTED])"
    }

    public data class Unavailable(public val reason: ProtectedKeyFailure) : PepperMacResult
}

/**
 * Reviewed OS-backed HMAC-SHA-256 operation; never exports a pepper/private key. Qualification
 * must cover the actual supervised service identity, ACLs, lock/restart and recovery behavior.
 * A config flag or synthetic test implementation is not evidence of OS qualification.
 */
public interface DeviceProtectedPepper {
    public val protectionStatus: KeyProtectionStatus
    public suspend fun hmacSha256(reference: ProtectedPepperReference, message: ByteArray): PepperMacResult
}

/** Explicitly unsupported contexts fail closed. No generated file key or in-memory substitute. */
public object UnavailableDeviceProtectedPepper : DeviceProtectedPepper {
    override val protectionStatus: KeyProtectionStatus = KeyProtectionStatus.UNSUPPORTED
    override suspend fun hmacSha256(reference: ProtectedPepperReference, message: ByteArray): PepperMacResult =
        PepperMacResult.Unavailable(ProtectedKeyFailure.CONTEXT_NOT_QUALIFIED)
}

public data class CredentialBinding(
    public val scope: BranchScope,
    public val actorId: DomainId,
    public val credentialRevision: Revision,
)

/** JCA PBKDF2-HMAC-SHA256 baseline; calibrate UP for each real supported service/device. */
public data class PasswordHashParameters(public val iterations: Int = 600_000) {
    init {
        require(iterations in 600_000..5_000_000) { "Unsupported password hash work factor" }
    }
}

/** Protected local storage only; never ordinary sync/audit/notification or a token-cache store. */
public class PasswordHashRecord(
    public val binding: CredentialBinding,
    public val parameters: PasswordHashParameters,
    public val pepperReference: ProtectedPepperReference,
    salt: ByteArray,
    verifier: ByteArray,
) {
    init {
        require(salt.size in 16..64 && verifier.size == 32)
    }

    private val saltValue: ByteArray = salt.copyOf()
    private val verifierValue: ByteArray = verifier.copyOf()
    public val algorithm: String = "PBKDF2-HMAC-SHA256/POST-HMAC-SHA256/v1"

    public fun copySalt(): ByteArray = saltValue.copyOf()
    public fun copyVerifier(): ByteArray = verifierValue.copyOf()
    override fun toString(): String = "PasswordHashRecord([REDACTED])"
}

public enum class PasswordFailure {
    INVALID_INPUT,
    WRONG_CREDENTIAL_BINDING,
    KEY_CONTEXT_NOT_QUALIFIED,
    KEY_UNAVAILABLE,
    PROVIDER_UNAVAILABLE,
    HASHER_BUSY,
}

public sealed interface PasswordHashingResult {
    public data class Created(public val record: PasswordHashRecord) : PasswordHashingResult
    public data class Unavailable(public val reason: PasswordFailure) : PasswordHashingResult
}

public sealed interface PasswordVerificationResult {
    public data class Match(public val needsRehash: Boolean) : PasswordVerificationResult
    public data object Mismatch : PasswordVerificationResult
    public data class Unavailable(public val reason: PasswordFailure) : PasswordVerificationResult
}

public interface LocalPasswordHasher {
    public suspend fun create(
        password: CharArray,
        binding: CredentialBinding,
        pepperReference: ProtectedPepperReference,
    ): PasswordHashingResult

    public suspend fun verify(
        password: CharArray,
        expectedBinding: CredentialBinding,
        record: PasswordHashRecord,
    ): PasswordVerificationResult
}

public object UnavailableLocalPasswordHasher : LocalPasswordHasher {
    override suspend fun create(
        password: CharArray,
        binding: CredentialBinding,
        pepperReference: ProtectedPepperReference,
    ): PasswordHashingResult = PasswordHashingResult.Unavailable(PasswordFailure.KEY_CONTEXT_NOT_QUALIFIED)

    override suspend fun verify(
        password: CharArray,
        expectedBinding: CredentialBinding,
        record: PasswordHashRecord,
    ): PasswordVerificationResult = PasswordVerificationResult.Unavailable(PasswordFailure.KEY_CONTEXT_NOT_QUALIFIED)
}

/** Shared UTF-16 input domain; reject malformed surrogate sequences, never replace/normalize them. */
internal fun validLocalPasswordInput(password: CharArray): Boolean {
    if (password.size !in 4..1024) return false
    var index = 0
    while (index < password.size) {
        val value = password[index++]
        if (value in '\uD800'..'\uDBFF') {
            if (index == password.size || password[index++] !in '\uDC00'..'\uDFFF') return false
        } else if (value in '\uDC00'..'\uDFFF') return false
    }
    return true
}
