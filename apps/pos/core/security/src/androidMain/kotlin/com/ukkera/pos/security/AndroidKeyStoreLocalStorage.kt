package com.ukkera.pos.security

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Actual internal AndroidKeyStore AES-256-GCM operations, using the existing port's exact value
 * types and AAD. Deliberately NOT an accepting DeviceProtectedLocalStorage implementation: the
 * stronger supervised-service binding is absent. No application/RPC factory exposes this class.
 * A later genuinely custody-bound adapter must check its exact lifetime before/after delegation.
 */
internal class AndroidKeyStoreLocalStorageOperations internal constructor(
    private val material: AndroidKeyStoreOpenedMaterial,
) {
    suspend fun seal(reference: ProtectedStorageKeyReference, binding: ProtectedLocalBinding,
        plaintext: ByteArray): LocalSealResult {
        currentCoroutineContext().ensureActive()
        if (reference != material.configuration.storageReference) return unavailableSeal(ProtectedKeyFailure.KEY_UNAVAILABLE)
        if (binding.key.scope != material.configuration.scope || plaintext.size !in 1..binding.table.maximumPlaintextBytes) {
            return unavailableSeal(ProtectedKeyFailure.CORRUPT_PROTECTED_STATE)
        }
        // Snapshot owned input before the first dispatch; never retain the caller's mutable array.
        val aad = binding.aad(reference)
        val input = plaintext.copyOf()
        var nonce: ByteArray? = null
        var ciphertextAndTag: ByteArray? = null
        return try {
            val failure = AndroidKeyStoreNativeWork.run {
                val key = material.storageKey()
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                // AndroidKeyStore chooses the random IV. Supplying our own encryption IV would
                // defeat the requested randomized-encryption authorization. No retry/fallback.
                cipher.init(Cipher.ENCRYPT_MODE, key)
                val generatedNonce = cipher.iv ?: androidKeyStoreFail(AndroidKeyStoreFailure.PROVIDER_FAILURE)
                nonce = generatedNonce
                val parameters = cipher.parameters?.getParameterSpec(GCMParameterSpec::class.java)
                    ?: androidKeyStoreFail(AndroidKeyStoreFailure.PROVIDER_FAILURE)
                if (generatedNonce.size != ProtectedLocalStorageFormat.NONCE_BYTES ||
                    parameters.tLen != ProtectedLocalStorageFormat.TAG_BYTES * 8 ||
                    !parameters.iv.contentEquals(generatedNonce)) {
                    androidKeyStoreFail(AndroidKeyStoreFailure.PROVIDER_FAILURE)
                }
                cipher.updateAAD(aad)
                val encrypted = cipher.doFinal(input)
                ciphertextAndTag = encrypted
                if (encrypted.size != input.size + ProtectedLocalStorageFormat.TAG_BYTES) {
                    androidKeyStoreFail(AndroidKeyStoreFailure.PROVIDER_FAILURE)
                }
            }
            if (failure != null) return unavailableSeal(failure.protectedFailure())
            currentCoroutineContext().ensureActive()
            val iv = nonce ?: return unavailableSeal(ProtectedKeyFailure.PROVIDER_FAILURE)
            val encrypted = ciphertextAndTag ?: return unavailableSeal(ProtectedKeyFailure.PROVIDER_FAILURE)
            // Construct copies only AFTER the dispatched native operation and cancellation cut.
            LocalSealResult.Sealed(SealedLocalRecord(binding, reference, iv, encrypted))
        } finally {
            input.fill(0)
            aad.fill(0)
            nonce?.fill(0)
            ciphertextAndTag?.fill(0)
        }
    }

    suspend fun open(reference: ProtectedStorageKeyReference, expected: ProtectedLocalBinding,
        sealed: SealedLocalRecord): LocalOpenResult {
        currentCoroutineContext().ensureActive()
        // All metadata checks precede key lookup. Stored envelopes cannot select arbitrary keys.
        if (reference != material.configuration.storageReference) return unavailableOpen(ProtectedKeyFailure.KEY_UNAVAILABLE)
        if (expected.key.scope != material.configuration.scope || sealed.binding != expected ||
            sealed.keyReference != reference || sealed.formatVersion != ProtectedLocalStorageFormat.FORMAT_VERSION ||
            sealed.algorithm != ProtectedLocalStorageFormat.ALGORITHM ||
            sealed.ciphertextAndTagSize !in (1 + ProtectedLocalStorageFormat.TAG_BYTES)..
                (expected.table.maximumPlaintextBytes + ProtectedLocalStorageFormat.TAG_BYTES)) {
            return unavailableOpen(ProtectedKeyFailure.CORRUPT_PROTECTED_STATE)
        }
        val nonce = sealed.copyNonce()
        val encrypted = sealed.copyCiphertextAndTag()
        val aad = expected.aad(reference)
        var plaintext: ByteArray? = null
        return try {
            if (nonce.size != ProtectedLocalStorageFormat.NONCE_BYTES) return unavailableOpen(ProtectedKeyFailure.CORRUPT_PROTECTED_STATE)
            val failure = AndroidKeyStoreNativeWork.run {
                val key = material.storageKey()
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(ProtectedLocalStorageFormat.TAG_BYTES * 8, nonce))
                cipher.updateAAD(aad)
                // One-shot authenticated decryption: no update() plaintext can escape before the
                // entire ciphertext/tag is verified. Provider-owned temporaries cannot be wiped here.
                val opened = cipher.doFinal(encrypted)
                plaintext = opened
                if (opened.size !in 1..expected.table.maximumPlaintextBytes ||
                    opened.size + ProtectedLocalStorageFormat.TAG_BYTES != encrypted.size) {
                    androidKeyStoreFail(AndroidKeyStoreFailure.CORRUPT_CIPHERTEXT)
                }
            }
            if (failure != null) return unavailableOpen(failure.protectedFailure())
            currentCoroutineContext().ensureActive()
            val opened = plaintext ?: return unavailableOpen(ProtectedKeyFailure.PROVIDER_FAILURE)
            // Opened owns its own copy. Keeping the native result in an outer owned variable
            // makes prompt cancellation after withContext able to wipe undelivered plaintext.
            LocalOpenResult.Opened(opened)
        } finally {
            nonce.fill(0)
            encrypted.fill(0)
            aad.fill(0)
            plaintext?.fill(0)
        }
    }

    override fun toString(): String = "AndroidKeyStoreLocalStorageOperations([REDACTED])"
}

private fun unavailableSeal(reason: ProtectedKeyFailure): LocalSealResult = LocalSealResult.Unavailable(reason)
private fun unavailableOpen(reason: ProtectedKeyFailure): LocalOpenResult = LocalOpenResult.Unavailable(reason)
