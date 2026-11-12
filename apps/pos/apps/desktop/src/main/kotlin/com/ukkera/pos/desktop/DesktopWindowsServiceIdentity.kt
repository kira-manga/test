package com.ukkera.pos.desktop

import com.ukkera.pos.security.SelfOwnedWindowsPackagedServiceIdentityV2
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Fixed packaged-service identity this desktop build declares. Expected DATA only: the running
 * service's process token is the authoritative observation.
 */
class DesktopWindowsServiceIdentity private constructor(
    val packageIdentity: DesktopWindowsPackageIdentity,
    val serviceName: String,
    val accountSid: String,
) {
    fun target(): SelfOwnedWindowsPackagedServiceIdentityV2 {
        val app = packageIdentity.target()
        return SelfOwnedWindowsPackagedServiceIdentityV2(app.packageFamilyName, app.applicationUserModelId,
            serviceSid(serviceName), accountSid)
    }

    companion object {
        /** LocalService account (S-1-5-19) running the test Master service. */
        val TEST = DesktopWindowsServiceIdentity(DesktopWindowsPackageIdentity.TEST, "UkkeraPOSTestMaster", "S-1-5-19")

        /** Documented per-service SID: S-1-5-80 plus SHA-1(UTF-16LE(uppercase name)) as five uint32. */
        fun serviceSid(name: String): String {
            require(name.length in 1..256 && name.none { it == '/' || it == '\\' || it.isISOControl() })
            val digest = MessageDigest.getInstance("SHA-1").digest(name.uppercase().toByteArray(Charsets.UTF_16LE))
            val words = ByteBuffer.wrap(digest).order(ByteOrder.LITTLE_ENDIAN)
            return "S-1-5-80-" + (0 until 5).joinToString("-") { (words.int.toLong() and 0xffffffffL).toString() }
        }
    }
}
