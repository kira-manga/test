package com.ukkera.pos.desktop

import com.ukkera.pos.security.SelfOwnedWindowsPackagedAppIdentityV2
import java.security.MessageDigest

/**
 * Fixed MSIX identity this desktop build declares. It is expected DATA compared against the
 * operating system's actual package identity; it never substitutes for that native observation.
 */
class DesktopWindowsPackageIdentity private constructor(
    val packageName: String,
    val publisher: String,
    val applicationId: String,
) {
    val packageFamilyName: String get() = "${packageName}_${publisherId(publisher)}"

    fun target(): SelfOwnedWindowsPackagedAppIdentityV2 =
        SelfOwnedWindowsPackagedAppIdentityV2(packageFamilyName, "$packageFamilyName!$applicationId")

    companion object {
        /** Locally trusted self-signed test-signing identity (owner decision, 12 November 2026). */
        val TEST = DesktopWindowsPackageIdentity("Ukkera.POS.Test", "CN=Ukkera POS Test Signing", "UkkeraPOS")

        private const val ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz"

        /** Windows publisher ID: first 64 bits of SHA-256(UTF-16LE publisher), base32 in 13 symbols. */
        fun publisherId(publisher: String): String {
            require(publisher.length in 1..8192 && publisher.none { it.isISOControl() })
            val digest = MessageDigest.getInstance("SHA-256").digest(publisher.toByteArray(Charsets.UTF_16LE))
            var bits = 0L
            for (index in 0 until 8) bits = (bits shl 8) or (digest[index].toLong() and 0xff)
            // 64 bits plus one trailing zero bit form exactly 13 five-bit symbols.
            return buildString(13) {
                for (symbol in 0 until 13) {
                    val shift = 64 - 5 * (symbol + 1)
                    val value = if (shift >= 0) (bits ushr shift) and 31 else (bits shl -shift) and 31
                    append(ALPHABET[value.toInt()])
                }
            }
        }
    }
}
