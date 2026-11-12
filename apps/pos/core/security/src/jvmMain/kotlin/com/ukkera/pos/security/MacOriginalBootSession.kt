package com.ukkera.pos.security

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.ptr.NativeLongByReference
import java.math.BigInteger
import java.nio.charset.StandardCharsets

/**
 * Native restart identity and elapsed time for an original macOS owner. Neither wall time nor
 * a process-generated identifier is used. The full kernel boot UUID binds prior key intents;
 * mach_continuous_time includes sleep and is used only for invocation-local expiry.
 * A failed observation permanently retires this observer. Retained identity is DATA, and fresh
 * observation is still required; this class supplies no namespace, key, history or Master grant.
 */
internal class MacOriginalBootSession private constructor(
    private val session: SelfOwnedNativeBootSessionV2.Mac,
    private val numerator: Long,
    private val denominator: Long,
    private var lastTicks: BigInteger,
) {
    private var retired = false

    /** Retained original DATA only; currentness requires a fresh elapsedMillis observation. */
    val identity: SelfOwnedNativeBootSessionV2.Mac get() = session

    @Synchronized
    fun elapsedMillis(): Long {
        check(!retired) { "The macOS boot observer is retired" }
        return try {
            check(readBootId() == session) { "The original macOS boot session changed" }
            val ticks = unsignedTicks()
            check(ticks >= lastTicks) { "Native continuous time moved backwards" }
            val millis = ticks.multiply(BigInteger.valueOf(numerator))
                .divide(BigInteger.valueOf(denominator).multiply(MILLION))
            check(millis.signum() >= 0 && millis.bitLength() < 64) { "Native elapsed time exceeds capacity" }
            check(readBootId() == session) { "The original macOS boot session changed during observation" }
            lastTicks = ticks
            millis.toLong()
        } catch (failure: Throwable) {
            retired = true
            throw failure
        }
    }

    override fun toString(): String = "MacOriginalBootSession([REDACTED])"

    companion object {
        private val MILLION = BigInteger.valueOf(1_000_000L)
        private val native: MacOriginalBootNative by lazy {
            check(Platform.isMac() && Native.POINTER_SIZE == 8) { "Original macOS boot observation requires 64-bit macOS" }
            Native.load("System", MacOriginalBootNative::class.java)
        }

        fun observe(): MacOriginalBootSession {
            val boot = readBootId()
            val ratio = Memory(8).use { bytes ->
                bytes.clear()
                check(native.mach_timebase_info(bytes) == 0) { "Native timebase observation failed" }
                val numerator = bytes.getInt(0).toLong() and 0xffff_ffffL
                val denominator = bytes.getInt(4).toLong() and 0xffff_ffffL
                check(numerator > 0 && denominator > 0) { "Invalid native timebase" }
                numerator to denominator
            }
            val ticks = unsignedTicks()
            check(readBootId() == boot) { "Native boot identity changed during observation" }
            return MacOriginalBootSession(boot, ratio.first, ratio.second, ticks)
                .also { it.elapsedMillis() }
        }

        private fun unsignedTicks(): BigInteger = BigInteger(java.lang.Long.toUnsignedString(native.mach_continuous_time()))

        private fun readBootId(): SelfOwnedNativeBootSessionV2.Mac = Memory(64).use { bytes ->
            bytes.clear()
            val count = NativeLongByReference(NativeLong(bytes.size()))
            check(native.sysctlbyname("kern.bootsessionuuid", bytes, count, null, NativeLong(0)) == 0) {
                "Native boot UUID is unavailable"
            }
            // UUID text and its terminator are exact. No truncation, prefix or integer projection.
            check(count.value.toLong() == 37L && bytes.getByte(36) == 0.toByte()) { "Invalid native boot UUID length" }
            val textBytes = bytes.getByteArray(0, 36)
            val text = try {
                check(textBytes.all { it.toInt() in 0x20..0x7e }) { "Invalid native boot UUID encoding" }
                String(textBytes, StandardCharsets.US_ASCII).lowercase(java.util.Locale.ROOT)
            } finally { textBytes.fill(0) }
            check(SelfOwnedInstalledIdentityGrammarV2.canonicalUuid(text)) { "Invalid native boot UUID" }
            SelfOwnedNativeBootSessionV2.Mac(text)
        }
    }
}

private interface MacOriginalBootNative : Library {
    fun sysctlbyname(name: String, value: Pointer, size: NativeLongByReference, newValue: Pointer?, newSize: NativeLong): Int
    fun mach_timebase_info(info: Pointer): Int
    fun mach_continuous_time(): Long
}
