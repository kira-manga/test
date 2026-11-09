package com.ukkera.pos.security

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

/** Apple Security CSPRNG; actual service/OS availability remains a runtime qualification gate. */
@OptIn(ExperimentalForeignApi::class)
public object IosLocalAuthenticationEntropy : LocalAuthenticationEntropy {
    override fun fill(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        try {
            bytes.usePinned { check(SecRandomCopyBytes(kSecRandomDefault, bytes.size.toULong(), it.addressOf(0)) == errSecSuccess) {
                "Local authentication entropy unavailable"
            } }
        } catch (failure: Throwable) { bytes.fill(0); throw failure }
    }
}
