package com.ukkera.pos.security

import android.os.Build
import java.security.Provider
import javax.crypto.SecretKeyFactory
import org.bouncycastle.jcajce.provider.symmetric.PBEPBKDF2

/** The same UTF-8 PBKDF2-HMAC-SHA256/256 primitive on every supported Android version. */
internal object AndroidNativePasswordKeyDerivation {
    private const val ALGORITHM: String = "PBKDF2WithHmacSHA256"

    fun secretKeyFactory(): SecretKeyFactory =
        if (Build.VERSION.SDK_INT >= 26) SecretKeyFactory.getInstance(ALGORITHM)
        else LegacyPasswordKeyFactory()

    /**
     * Use the same official UTF-8/SHA256 SPI as the former private BC provider, without
     * constructing the full provider or registering its unrelated algorithms.
     * Each call owns a fresh factory/SPI; the descriptor is never installed globally.
     */
    private class LegacyPasswordKeyFactory : SecretKeyFactory(
        PBEPBKDF2.PBKDF2withSHA256(),
        LegacyProviderDescriptor,
        ALGORITHM,
    )

    // The double-version constructor is available on API 24. This provider is only the
    // required JCA descriptor: no service discovery, reflective class name or registration.
    @Suppress("DEPRECATION")
    private object LegacyProviderDescriptor : Provider(
        "UkkeraLegacyPbkdf2", 1.0, "Private official PBKDF2-HMAC-SHA256 adapter",
    )
}
