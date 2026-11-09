package com.ukkera.pos.security

import java.security.SecureRandom

/** Android JCA CSPRNG, not a synthetic status flag or a substitute for protected keys. */
public class AndroidLocalAuthenticationEntropy(private val random: SecureRandom = SecureRandom()) : LocalAuthenticationEntropy {
    override fun fill(bytes: ByteArray): Unit = random.nextBytes(bytes)
}
