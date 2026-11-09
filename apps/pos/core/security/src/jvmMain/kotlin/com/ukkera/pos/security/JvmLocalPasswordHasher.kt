package com.ukkera.pos.security

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Native JCA/entropy composition. Credential workflow and concurrency policy are shared. */
public class JvmLocalPasswordHasher(
    pepper: DeviceProtectedPepper,
    parameters: PasswordHashParameters = PasswordHashParameters(),
    random: SecureRandom = SecureRandom(),
) : LocalPasswordHasher by ProtectedLocalPasswordHasher(
    pepper, parameters, LocalAuthenticationEntropy(random::nextBytes), JvmPasswordKeyDerivation,
)

private object JvmPasswordKeyDerivation : LocalPasswordKeyDerivation {
    override suspend fun deriveInto(password: CharArray, salt: ByteArray, iterations: Int, output: ByteArray): Unit =
        withContext(Dispatchers.Default) {
            val specification = PBEKeySpec(password, salt, iterations, 256)
            try {
                val derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(specification).encoded ?: error("Password derivation unavailable")
                try {
                    check(derived.size == output.size) { "Password derivation unavailable" }
                    derived.copyInto(output)
                } finally {
                    derived.fill(0)
                }
            } finally {
                specification.clearPassword()
            }
        }
}
