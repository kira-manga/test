package com.ukkera.pos.security

import javax.crypto.Mac
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Actual native nonexporting HMAC-SHA256 operation under the DISTINCT configured pepper alias.
 * This is an internal operation helper, NOT a qualified DeviceProtectedPepper binding. Only the
 * future actual supervised-service composition may expose that stronger existing interface.
 * The current password hash/UTF-8/binding/work-factor profile is unchanged.
 */
internal class AndroidKeyStorePepperOperations internal constructor(
    private val material: AndroidKeyStoreOpenedMaterial,
) {
    suspend fun hmacSha256(reference: ProtectedPepperReference, message: ByteArray): PepperMacResult {
        currentCoroutineContext().ensureActive()
        if (reference != material.configuration.pepperReference) return unavailablePepper(ProtectedKeyFailure.KEY_UNAVAILABLE)
        if (message.size > MAX_MESSAGE_BYTES) return unavailablePepper(ProtectedKeyFailure.CORRUPT_PROTECTED_STATE)
        // The existing credential domain/binding/salt/derived-key message is well below this
        // independent 4 KiB native-operation cap. Empty HMAC input remains a valid primitive input.
        val input = message.copyOf()
        var tag: ByteArray? = null
        return try {
            val failure = AndroidKeyStoreNativeWork.run {
                val key = material.pepperKey()
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(key)
                val authenticated = mac.doFinal(input)
                tag = authenticated
                if (authenticated.size != 32) androidKeyStoreFail(AndroidKeyStoreFailure.PROVIDER_FAILURE)
            }
            if (failure != null) return unavailablePepper(failure.protectedFailure())
            currentCoroutineContext().ensureActive()
            val authenticated = tag ?: return unavailablePepper(ProtectedKeyFailure.PROVIDER_FAILURE)
            PepperMacResult.Authenticated(authenticated)
        } finally {
            input.fill(0)
            tag?.fill(0)
        }
    }

    override fun toString(): String = "AndroidKeyStorePepperOperations([REDACTED])"

    private companion object {
        const val MAX_MESSAGE_BYTES: Int = 4 * 1024
    }
}

private fun unavailablePepper(reason: ProtectedKeyFailure): PepperMacResult = PepperMacResult.Unavailable(reason)
