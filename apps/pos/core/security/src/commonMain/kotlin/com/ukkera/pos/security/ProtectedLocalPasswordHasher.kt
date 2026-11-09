package com.ukkera.pos.security

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Semaphore

/** Native PBKDF2-HMAC-SHA256 only. The caller owns and clears every supplied buffer. */
internal fun interface LocalPasswordKeyDerivation {
    /** Write the entire 32-byte output before returning; never retain password/salt/output. */
    suspend fun deriveInto(password: CharArray, salt: ByteArray, iterations: Int, output: ByteArray)
}

/**
 * One credential workflow for every platform. Input/binding checks, work admission, the
 * compatible credential-v1 pepper message, rehash decisions and failure mapping live here.
 * Native adapters provide only PBKDF2, protected HMAC, entropy and constant-time comparison.
 * A hash match never grants a session: the master still owns throttle/account/policy checks.
 */
internal class ProtectedLocalPasswordHasher(
    private val pepper: DeviceProtectedPepper,
    private val parameters: PasswordHashParameters,
    private val entropy: LocalAuthenticationEntropy,
    private val keyDerivation: LocalPasswordKeyDerivation,
) : LocalPasswordHasher {
    private val workSlots = Semaphore(2)

    override suspend fun create(
        password: CharArray,
        binding: CredentialBinding,
        pepperReference: ProtectedPepperReference,
    ): PasswordHashingResult {
        precondition(password)?.let { return PasswordHashingResult.Unavailable(it) }
        val salt = ByteArray(16)
        return try {
            entropy.fill(salt)
            when (val result = derive(password, binding, parameters, pepperReference, salt)) {
                is Derivation.Tag -> try {
                    PasswordHashingResult.Created(PasswordHashRecord(binding, parameters, pepperReference, salt, result.bytes))
                } finally {
                    result.bytes.fill(0)
                }
                is Derivation.Unavailable -> PasswordHashingResult.Unavailable(result.reason)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PasswordHashingResult.Unavailable(PasswordFailure.PROVIDER_UNAVAILABLE)
        } finally {
            salt.fill(0)
        }
    }

    override suspend fun verify(
        password: CharArray,
        expectedBinding: CredentialBinding,
        record: PasswordHashRecord,
    ): PasswordVerificationResult {
        precondition(password)?.let { return PasswordVerificationResult.Unavailable(it) }
        if (record.binding != expectedBinding) {
            return PasswordVerificationResult.Unavailable(PasswordFailure.WRONG_CREDENTIAL_BINDING)
        }
        val salt = record.copySalt()
        val expected = record.copyVerifier()
        return try {
            when (val result = derive(password, expectedBinding, record.parameters, record.pepperReference, salt)) {
                is Derivation.Tag -> try {
                    if (equalLocalAccessDigests(expected, result.bytes)) {
                        PasswordVerificationResult.Match(record.parameters.iterations < parameters.iterations)
                    } else {
                        PasswordVerificationResult.Mismatch
                    }
                } finally {
                    result.bytes.fill(0)
                }
                is Derivation.Unavailable -> PasswordVerificationResult.Unavailable(result.reason)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PasswordVerificationResult.Unavailable(PasswordFailure.PROVIDER_UNAVAILABLE)
        } finally {
            salt.fill(0)
            expected.fill(0)
        }
    }

    private fun precondition(password: CharArray): PasswordFailure? = when {
        !validLocalPasswordInput(password) -> PasswordFailure.INVALID_INPUT
        pepper.protectionStatus != KeyProtectionStatus.QUALIFIED_SUPERVISED_SERVICE_CONTEXT ->
            PasswordFailure.KEY_CONTEXT_NOT_QUALIFIED
        else -> null
    }

    private suspend fun derive(
        password: CharArray,
        binding: CredentialBinding,
        parameters: PasswordHashParameters,
        reference: ProtectedPepperReference,
        salt: ByteArray,
    ): Derivation {
        if (!workSlots.tryAcquire()) return Derivation.Unavailable(PasswordFailure.HASHER_BUSY)
        return try {
            val derived = ByteArray(32)
            try {
                // Snapshot after bounded admission, before any dispatcher handoff. The output
                // is already owned here, including cancellation while the native call returns.
                val copy = password.copyOf()
                try {
                    keyDerivation.deriveInto(copy, salt, parameters.iterations, derived)
                } finally {
                    copy.fill('\u0000')
                }
                // Persisted credential-v1 bytes: do not normalize, reorder or change delimiters.
                val header = (
                    "ukkera-local-credential-v1\u0000" + binding.scope.organizationId.value + "\u0000" +
                        binding.scope.companyId.value + "\u0000" + binding.scope.branchId.value + "\u0000" +
                        binding.actorId.value + "\u0000" + binding.credentialRevision.value + "\u0000" +
                        parameters.iterations + "\u0000"
                    ).encodeToByteArray()
                try {
                    val message = header + salt + derived
                    try {
                        when (val result = pepper.hmacSha256(reference, message)) {
                            is PepperMacResult.Authenticated -> Derivation.Tag(result.copyTag())
                            is PepperMacResult.Unavailable -> Derivation.Unavailable(PasswordFailure.KEY_UNAVAILABLE)
                        }
                    } finally {
                        message.fill(0)
                    }
                } finally {
                    header.fill(0)
                }
            } finally {
                derived.fill(0)
            }
        } finally {
            workSlots.release()
        }
    }

    private sealed interface Derivation {
        class Tag(val bytes: ByteArray) : Derivation
        class Unavailable(val reason: PasswordFailure) : Derivation
    }
}
