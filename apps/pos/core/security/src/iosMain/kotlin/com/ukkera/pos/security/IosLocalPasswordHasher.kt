package com.ukkera.pos.security

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreCrypto.CCKeyDerivationPBKDF
import platform.CoreCrypto.kCCPBKDF2
import platform.CoreCrypto.kCCPRFHmacAlgSHA256
import platform.CoreCrypto.kCCSuccess

/**
 * Native CommonCrypto/entropy composition, using the same shared credential-v1 workflow as
 * JCA. No Keychain, service lifetime or device qualification is implied by this adapter.
 * Owned buffers are cleared; temporary runtime strings and OS crypto memory cannot be promised erased.
 */
public class IosLocalPasswordHasher(
    pepper: DeviceProtectedPepper,
    parameters: PasswordHashParameters = PasswordHashParameters(),
    entropy: LocalAuthenticationEntropy = IosLocalAuthenticationEntropy,
) : LocalPasswordHasher by ProtectedLocalPasswordHasher(pepper, parameters, entropy, IosPasswordKeyDerivation)

@OptIn(ExperimentalForeignApi::class)
private object IosPasswordKeyDerivation : LocalPasswordKeyDerivation {
    override suspend fun deriveInto(password: CharArray, salt: ByteArray, iterations: Int, output: ByteArray) {
        val bytes = password.concatToString().encodeToByteArray(throwOnInvalidSequence = true)
        try {
            withContext(Dispatchers.Default) {
                bytes.usePinned { pass -> salt.usePinned { saltBytes -> output.usePinned { derived ->
                    check(CCKeyDerivationPBKDF(kCCPBKDF2, pass.addressOf(0), bytes.size.toULong(),
                        saltBytes.addressOf(0).reinterpret(), salt.size.toULong(), kCCPRFHmacAlgSHA256,
                        iterations.toUInt(), derived.addressOf(0).reinterpret(), 32uL) == kCCSuccess) {
                        "Password derivation unavailable"
                    }
                } } }
            }
        } finally {
            bytes.fill(0)
        }
    }
}
