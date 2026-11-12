package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Internal exact-original native primitives, constructed by the SAME target lifetime. This
 * implements NONE of DeviceProtectedLocalStorage, DeviceProtectedPepper, LocalPasswordHasher
 * or LocalAuthenticationEnvironment: native material alone cannot satisfy their service/Room
 * contracts. The public context exposes no such accepting adapter or raw material accessor.
 *
 * All AES/HMAC/PBKDF2/native key checks use the same admitted owned work, never the older
 * AndroidKeyStoreNativeWork/password pool. Copy only after admission; own inputs and transient
 * outputs until actual completion, including caller cancellation and undelivered discard.
 * Provider/JCA-internal copies cannot be guaranteed erased by this application.
 */
internal class AndroidSelfOwnedLocalSecurity(
    private val owner: AndroidSelfOwnedInstallationLifetime,
    private val environment: SelfOwnedOriginalEnvironment,
    private val material: SelfOwnedOriginalDataKeys,
    private val work: AndroidOwnedInstallationWork,
) {
    private val configuration = material.keyConfiguration()
    private val creationParameters = PasswordHashParameters()
    private val processId = work.platform.pid()
    private val processUid = work.platform.uid()
    private val bootId = originalBootId(configuration.installationId, environment.bootCount)
    private val issuerContextId = AndroidP256Primitives.newId()
    private val lastElapsedNanos = AtomicLong(work.platform.elapsedRealtimeNanos())
    private val retired = AtomicBoolean(false)

    init {
        work.requireActive(); environment.requireCurrent()
        if (processId <= 0 || processUid != environment.uid || issuerContextId == bootId || lastElapsedNanos.get() < 0L) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
    }

    /** Exact existing row AAD/profile. Does not create a row, connection or transaction. */
    suspend fun seal(reference: ProtectedStorageKeyReference, binding: ProtectedLocalBinding,
        plaintext: ByteArray): LocalSealResult {
        currentCoroutineContext().ensureActive()
        if (reference != configuration.storageReference) return LocalSealResult.Unavailable(ProtectedKeyFailure.KEY_UNAVAILABLE)
        if (binding.key.scope != configuration.scope || plaintext.size !in 1..binding.table.maximumPlaintextBytes) {
            return LocalSealResult.Unavailable(ProtectedKeyFailure.CORRUPT_PROTECTED_STATE)
        }
        val result = work.run {
            val input = plaintext.copyOf()
            var aad: ByteArray? = null
            try {
                val originalAad = binding.aad(reference); aad = originalAad
                ownedTask(clear = { input.fill(0); originalAad.fill(0) }, discard = { it: AndroidSelfOwnedCiphertext -> it.clear() }) {
                    sealNative(originalAad, input)
                }
            } catch (failure: Throwable) { input.fill(0); aad?.fill(0); throw failure }
        }
        return when (result) {
            is AndroidOwnedResult.Value -> try {
                currentCoroutineContext().ensureActive()
                LocalSealResult.Sealed(SealedLocalRecord(binding, reference, result.value.nonce, result.value.ciphertext))
            } finally { result.value.clear() }
            is AndroidOwnedResult.Unavailable -> LocalSealResult.Unavailable(protectedFailure(result.reason))
        }
    }

    /** Authenticate all ciphertext/tag against the caller's exact selected row before output. */
    suspend fun open(reference: ProtectedStorageKeyReference, expected: ProtectedLocalBinding,
        sealed: SealedLocalRecord): LocalOpenResult {
        currentCoroutineContext().ensureActive()
        if (reference != configuration.storageReference) return LocalOpenResult.Unavailable(ProtectedKeyFailure.KEY_UNAVAILABLE)
        if (expected.key.scope != configuration.scope || sealed.binding != expected || sealed.keyReference != reference ||
            sealed.formatVersion != ProtectedLocalStorageFormat.FORMAT_VERSION || sealed.algorithm != ProtectedLocalStorageFormat.ALGORITHM ||
            sealed.ciphertextAndTagSize !in (1 + ProtectedLocalStorageFormat.TAG_BYTES)..
                (expected.table.maximumPlaintextBytes + ProtectedLocalStorageFormat.TAG_BYTES)) {
            return LocalOpenResult.Unavailable(ProtectedKeyFailure.CORRUPT_PROTECTED_STATE)
        }
        val result = work.run {
            val nonce = sealed.copyNonce()
            var ciphertext: ByteArray? = null; var aad: ByteArray? = null
            try {
                val input = sealed.copyCiphertextAndTag(); ciphertext = input
                val originalAad = expected.aad(reference); aad = originalAad
                ownedTask(clear = { nonce.fill(0); input.fill(0); originalAad.fill(0) }, discard = { it: ByteArray -> it.fill(0) }) {
                    openNative(originalAad, nonce, input, expected.table.maximumPlaintextBytes)
                }
            } catch (failure: Throwable) { nonce.fill(0); ciphertext?.fill(0); aad?.fill(0); throw failure }
        }
        return when (result) {
            is AndroidOwnedResult.Value -> try {
                currentCoroutineContext().ensureActive()
                LocalOpenResult.Opened(result.value)
            } finally { result.value.fill(0) }
            is AndroidOwnedResult.Unavailable -> LocalOpenResult.Unavailable(protectedFailure(result.reason))
        }
    }

    /** DISTINCT original branch pepper, never P1's installer pepper or a caller-selected alias. */
    suspend fun hmacSha256(reference: ProtectedPepperReference, message: ByteArray): PepperMacResult {
        currentCoroutineContext().ensureActive()
        if (reference != configuration.pepperReference) return PepperMacResult.Unavailable(ProtectedKeyFailure.KEY_UNAVAILABLE)
        if (message.size > MAXIMUM_PEPPER_MESSAGE_BYTES) return PepperMacResult.Unavailable(ProtectedKeyFailure.CORRUPT_PROTECTED_STATE)
        return when (val result = work.run {
            val input = message.copyOf()
            ownedTask(clear = { input.fill(0) }, discard = { it: ByteArray -> it.fill(0) }) { pepperNative(input) }
        }) {
            is AndroidOwnedResult.Value -> try {
                currentCoroutineContext().ensureActive()
                PepperMacResult.Authenticated(result.value)
            } finally { result.value.fill(0) }
            is AndroidOwnedResult.Unavailable -> PepperMacResult.Unavailable(protectedFailure(result.reason))
        }
    }

    /**
     * Consumes the supplied password. Generates the existing 16-byte random-salt credential
     * format ONLY, not an employee/account/session. Its later durable store must still provide
     * exact account/throttle/authorization transaction semantics before an identity may issue.
     */
    suspend fun createPassword(password: CharArray, binding: CredentialBinding,
        reference: ProtectedPepperReference): PasswordHashingResult {
        try {
            currentCoroutineContext().ensureActive()
            if (!validLocalPasswordInput(password)) return PasswordHashingResult.Unavailable(PasswordFailure.INVALID_INPUT)
            if (binding.scope != configuration.scope) return PasswordHashingResult.Unavailable(PasswordFailure.WRONG_CREDENTIAL_BINDING)
            if (reference != configuration.pepperReference) return PasswordHashingResult.Unavailable(PasswordFailure.KEY_UNAVAILABLE)
            val result = work.run {
                val input = copyPasswordAfterAdmission(password)
                ownedTask(clear = { input.fill('\u0000') }, discard = { it: DerivedCredential -> it.clear() }) {
                    val salt = AndroidP256Primitives.randomBytes(16)
                    var verifier: ByteArray? = null
                    try {
                        val tag = derivePasswordNative(input, binding, creationParameters, salt); verifier = tag
                        DerivedCredential(salt, tag)
                    } catch (failure: Throwable) { salt.fill(0); verifier?.fill(0); throw failure }
                }
            }
            return when (result) {
                is AndroidOwnedResult.Value -> try {
                    currentCoroutineContext().ensureActive()
                    PasswordHashingResult.Created(PasswordHashRecord(binding, creationParameters, reference,
                        result.value.salt, result.value.verifier))
                } finally { result.value.clear() }
                is AndroidOwnedResult.Unavailable -> PasswordHashingResult.Unavailable(passwordFailure(result.reason))
            }
        } finally { password.fill('\u0000') }
    }

    /** Hash match alone never authenticates. Persistent throttle and current account checks remain REQUIRED. */
    suspend fun verifyPassword(password: CharArray, expectedBinding: CredentialBinding,
        record: PasswordHashRecord): PasswordVerificationResult {
        try {
            currentCoroutineContext().ensureActive()
            if (!validLocalPasswordInput(password)) return PasswordVerificationResult.Unavailable(PasswordFailure.INVALID_INPUT)
            if (expectedBinding.scope != configuration.scope || record.binding != expectedBinding) {
                return PasswordVerificationResult.Unavailable(PasswordFailure.WRONG_CREDENTIAL_BINDING)
            }
            if (record.pepperReference != configuration.pepperReference) return PasswordVerificationResult.Unavailable(PasswordFailure.KEY_UNAVAILABLE)
            val result = work.run {
                val input = copyPasswordAfterAdmission(password)
                var salt: ByteArray? = null; var expected: ByteArray? = null
                try {
                    val originalSalt = record.copySalt(); salt = originalSalt
                    val originalVerifier = record.copyVerifier(); expected = originalVerifier
                    ownedTask<PasswordVerificationResult>(clear = {
                        input.fill('\u0000'); originalSalt.fill(0); originalVerifier.fill(0)
                    }, discard = { }) {
                        val actual = derivePasswordNative(input, expectedBinding, record.parameters, originalSalt)
                        try {
                            if (AndroidP256Primitives.same(originalVerifier, actual)) {
                                PasswordVerificationResult.Match(record.parameters.iterations < creationParameters.iterations)
                            } else PasswordVerificationResult.Mismatch
                        } finally { actual.fill(0) }
                    }
                } catch (failure: Throwable) { input.fill('\u0000'); salt?.fill(0); expected?.fill(0); throw failure }
            }
            return when (result) {
                is AndroidOwnedResult.Value -> result.value
                is AndroidOwnedResult.Unavailable -> PasswordVerificationResult.Unavailable(passwordFailure(result.reason))
            }
        } finally { password.fill('\u0000') }
    }

    private fun sealNative(aad: ByteArray, plaintext: ByteArray): AndroidSelfOwnedCiphertext {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, material.storageKey()) // Native provider chooses the randomized IV.
        val nonce = cipher.iv ?: selfOwnedFail(SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN)
        var ciphertext: ByteArray? = null
        try {
            val parameters = cipher.parameters?.getParameterSpec(GCMParameterSpec::class.java)
                ?: selfOwnedFail(SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN)
            if (nonce.size != ProtectedLocalStorageFormat.NONCE_BYTES || parameters.tLen != ProtectedLocalStorageFormat.TAG_BYTES * 8 ||
                !parameters.iv.contentEquals(nonce)) selfOwnedFail(SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN)
            cipher.updateAAD(aad)
            val encrypted = cipher.doFinal(plaintext); ciphertext = encrypted
            if (encrypted.size != plaintext.size + ProtectedLocalStorageFormat.TAG_BYTES) selfOwnedFail(SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN)
            material.storageKey()
            return AndroidSelfOwnedCiphertext(nonce.copyOf(), encrypted.copyOf())
        } finally { nonce.fill(0); ciphertext?.fill(0) }
    }

    private fun openNative(aad: ByteArray, nonce: ByteArray, ciphertext: ByteArray, maximumPlaintext: Int): ByteArray {
        if (nonce.size != ProtectedLocalStorageFormat.NONCE_BYTES ||
            ciphertext.size !in (1 + ProtectedLocalStorageFormat.TAG_BYTES)..(maximumPlaintext + ProtectedLocalStorageFormat.TAG_BYTES)) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, material.storageKey(), GCMParameterSpec(ProtectedLocalStorageFormat.TAG_BYTES * 8, nonce))
        cipher.updateAAD(aad)
        val plaintext = cipher.doFinal(ciphertext) // One shot: no unauthenticated update() plaintext escapes.
        try {
            if (plaintext.size !in 1..maximumPlaintext || plaintext.size + ProtectedLocalStorageFormat.TAG_BYTES != ciphertext.size) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
            material.storageKey()
            return plaintext
        } catch (failure: Throwable) { plaintext.fill(0); throw failure }
    }

    private fun pepperNative(message: ByteArray): ByteArray {
        if (message.size > MAXIMUM_PEPPER_MESSAGE_BYTES) selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(material.pepperKey())
        val tag = mac.doFinal(message)
        try {
            if (tag.size != 32) selfOwnedFail(SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN)
            material.pepperKey()
            return tag
        } catch (failure: Throwable) { tag.fill(0); throw failure }
    }

    private fun derivePasswordNative(password: CharArray, binding: CredentialBinding,
        parameters: PasswordHashParameters, salt: ByteArray): ByteArray {
        if (!validLocalPasswordInput(password) || salt.size !in 16..64 || binding.scope != configuration.scope) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
        }
        val specification = PBEKeySpec(password, salt, parameters.iterations, 256)
        var derived: ByteArray? = null; var header: ByteArray? = null; var message: ByteArray? = null
        try {
            // Export of this temporary PASSWORD DERIVATION is the existing credential format,
            // not export of a native AndroidKeyStore AES/pepper/signing private key.
            val passwordKey = work.platform.passwordKeyFactory().generateSecret(specification).encoded
                ?: selfOwnedFail(SelfOwnedInstallationFailureV1.UNAVAILABLE)
            derived = passwordKey
            if (passwordKey.size != 32) selfOwnedFail(SelfOwnedInstallationFailureV1.UNAVAILABLE)
            val originalHeader = (
                "ukkera-local-credential-v1\u0000" + binding.scope.organizationId.value + "\u0000" +
                    binding.scope.companyId.value + "\u0000" + binding.scope.branchId.value + "\u0000" +
                    binding.actorId.value + "\u0000" + binding.credentialRevision.value + "\u0000" +
                    parameters.iterations + "\u0000"
                ).encodeToByteArray()
            header = originalHeader
            val originalMessage = originalHeader + salt + passwordKey; message = originalMessage
            work.requireActive()
            return pepperNative(originalMessage)
        } finally {
            specification.clearPassword(); derived?.fill(0); header?.fill(0); message?.fill(0)
        }
    }

    /** Bounded input-copy helper invoked only inside owned-work admission; no IO or dispatch. */
    private fun copyPasswordAfterAdmission(password: CharArray): CharArray {
        if (password.size !in 4..1024) selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
        val input = password.copyOf()
        password.fill('\u0000')
        if (!validLocalPasswordInput(input)) { input.fill('\u0000'); selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT) }
        return input
    }

    /** Private authored operations only, never caller-supplied trust/custody/provider callbacks. */
    private fun <T> ownedTask(clear: () -> Unit, discard: (T) -> Unit, body: () -> T): AndroidOwnedTask<T> {
        val disposeOutput = discard
        return object : AndroidOwnedTask<T> {
            override fun execute(): T = try {
                if (retired.get()) selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
                owner.requireDataNative()
                val output = body()
                try { owner.requireDataNative(); output }
                catch (failure: Throwable) { disposeOutput(output); throw failure }
            } catch (failure: Throwable) { owner.denyDataUse(); throw failure }
            override fun discard(value: T) { disposeOutput(value) }
            override fun clearInputs() { clear() }
        }
    }

    /**
     * Native clock/activation observation only. No Settings/Binder/key/file/Room/wall-clock
     * call belongs here. A sample does not authenticate data/history or fence an old writer.
     * It is deliberately NOT an accepting LocalAuthenticationEnvironment implementation.
     */
    fun observeNativeOnly(): AndroidSelfOwnedNativeClockSample? {
        if (retired.get() || owner.dataUseDeniedNoIo()) return null
        return try {
            work.requireActive() // Bounded PID/UID/OS-clock and denial checks, not IO-based custody.
            if (work.platform.pid() != processId || work.platform.uid() != processUid) return denyObservation()
            repeat(4) {
                if (retired.get() || owner.dataUseDeniedNoIo()) return null
                val previous = lastElapsedNanos.get()
                val sampled = work.platform.elapsedRealtimeNanos()
                if (sampled < 0L || sampled < previous) return denyObservation()
                if (lastElapsedNanos.compareAndSet(previous, sampled)) {
                    work.requireActive()
                    return if (retired.get() || owner.dataUseDeniedNoIo()) null else
                        AndroidSelfOwnedNativeClockSample(issuerContextId, MonotonicMoment(bootId, sampled / 1_000_000L))
                }
            }
            denyObservation() // No spin, invented high-water sample or fresh issuer fallback.
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { denyObservation() }
    }

    fun retireNoIo() { retired.set(true) }
    private fun denyObservation(): AndroidSelfOwnedNativeClockSample? { owner.denyDataUse(); return null }

    private class DerivedCredential(val salt: ByteArray, val verifier: ByteArray) {
        fun clear() { salt.fill(0); verifier.fill(0) }
    }

    override fun toString(): String = "AndroidSelfOwnedLocalSecurityV1([REDACTED])"

    companion object {
        private const val MAXIMUM_PEPPER_MESSAGE_BYTES = 4 * 1024

        /** Exact existing Android boot-count UUIDv8 domain, not attestation or anti-rollback. */
        private fun originalBootId(installationId: DomainId, bootCount: Int): DomainId {
            require(bootCount >= 0)
            val framed = "ukkera.android-boot-count/v1\u0000${installationId.value}\u0000$bootCount".encodeToByteArray()
            val digest = try { AndroidP256Primitives.hash(framed) } finally { framed.fill(0) }
            try {
                digest[6] = ((digest[6].toInt() and 0x0f) or 0x80).toByte()
                digest[8] = ((digest[8].toInt() and 0x3f) or 0x80).toByte()
                val bytes = ByteBuffer.wrap(digest)
                return DomainId(UUID(bytes.long, bytes.long).toString())
            } finally { digest.fill(0) }
        }

        private fun protectedFailure(reason: SelfOwnedInstallationFailureV1): ProtectedKeyFailure = when (reason) {
            SelfOwnedInstallationFailureV1.INVALID_INPUT, SelfOwnedInstallationFailureV1.STATE_UNCERTAIN,
            SelfOwnedInstallationFailureV1.CONFLICT -> ProtectedKeyFailure.CORRUPT_PROTECTED_STATE
            SelfOwnedInstallationFailureV1.USER_LOCKED -> ProtectedKeyFailure.KEY_LOCKED
            SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN -> ProtectedKeyFailure.KEY_UNAVAILABLE
            SelfOwnedInstallationFailureV1.NATIVE_RETIRED, SelfOwnedInstallationFailureV1.INVALID_APPLICATION,
            SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM -> ProtectedKeyFailure.CONTEXT_NOT_QUALIFIED
            else -> ProtectedKeyFailure.PROVIDER_FAILURE
        }

        private fun passwordFailure(reason: SelfOwnedInstallationFailureV1): PasswordFailure = when (reason) {
            SelfOwnedInstallationFailureV1.INVALID_INPUT -> PasswordFailure.INVALID_INPUT
            SelfOwnedInstallationFailureV1.CONFLICT -> PasswordFailure.WRONG_CREDENTIAL_BINDING
            SelfOwnedInstallationFailureV1.BUSY -> PasswordFailure.HASHER_BUSY
            SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN, SelfOwnedInstallationFailureV1.USER_LOCKED,
            SelfOwnedInstallationFailureV1.STATE_UNCERTAIN -> PasswordFailure.KEY_UNAVAILABLE
            SelfOwnedInstallationFailureV1.NATIVE_RETIRED, SelfOwnedInstallationFailureV1.INVALID_APPLICATION,
            SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM -> PasswordFailure.KEY_CONTEXT_NOT_QUALIFIED
            else -> PasswordFailure.PROVIDER_UNAVAILABLE
        }
    }
}

/** Pure native sample, not a qualified service/custody/history observation by possession. */
internal class AndroidSelfOwnedNativeClockSample(val issuerContextId: DomainId, val moment: MonotonicMoment) {
    override fun toString(): String = "AndroidSelfOwnedNativeClockSampleV1([REDACTED])"
}
