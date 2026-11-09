package com.ukkera.pos.security

import java.security.SecureRandom

/** JCA CSPRNG; actual OS/provider/service availability still needs deployment qualification. */
public class JvmLocalAuthenticationEntropy(private val random: SecureRandom = SecureRandom()) : LocalAuthenticationEntropy {
    override fun fill(bytes: ByteArray): Unit = random.nextBytes(bytes)
}
