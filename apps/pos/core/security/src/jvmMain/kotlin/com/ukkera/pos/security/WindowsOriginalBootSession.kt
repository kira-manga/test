package com.ukkera.pos.security

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.util.UUID

/**
 * Genuine Windows boot-session DATA and invocation-local elapsed time. The full native boot
 * environment GUID is retained; neither a wall-clock boot estimate, integer counter nor a
 * process-generated identifier can substitute for it.
 *
 * This observer is not an installed-service, key, namespace, history or Master capability and
 * implements no accepting authentication environment. Its native owner must join the observed
 * identity to the exact original context and keep its existing custody/exclusion requirements.
 * A failed observation permanently retires this observer, never resets its elapsed baseline.
 *
 * Boot observation uses NT information class 90's 64-bit, 32-byte boot-environment layout.
 * This native ABI is version-sensitive: an unavailable query, non-success status, changed
 * length, invalid GUID or unsupported firmware value refuses; there is no Win32 wall-time,
 * registry-counter or random-ID fallback. Supported Windows builds still require native
 * qualification before this primitive can be composed into an original owner.
 */
internal class WindowsOriginalBootSession private constructor(
    private val session: SelfOwnedNativeBootSessionV2.Windows,
    private var lastElapsedMillis: Long,
) {
    private var retired = false

    /** Retained original DATA only; currentness requires a fresh elapsedMillis observation. */
    val identity: SelfOwnedNativeBootSessionV2.Windows get() = session

    @Synchronized
    fun elapsedMillis(): Long {
        check(!retired) { "The Windows boot observer is retired" }
        return try {
            check(readBootId() == session) { "The original Windows boot session changed" }
            val elapsed = readElapsedMillis()
            check(elapsed >= lastElapsedMillis) { "Native Windows elapsed time moved backwards" }
            check(readBootId() == session) { "The original Windows boot session changed during observation" }
            lastElapsedMillis = elapsed
            elapsed
        } catch (failure: Throwable) {
            retired = true
            throw failure
        }
    }

    override fun toString(): String = "WindowsOriginalBootSession([REDACTED])"

    companion object {
        private const val BOOT_ENVIRONMENT_INFORMATION = 90
        private const val BOOT_ENVIRONMENT_BYTES = 32
        private const val FIRMWARE_BIOS = 1
        private const val FIRMWARE_UEFI = 2

        private fun requireSupported() {
            check(Platform.isWindows() && Native.POINTER_SIZE == 8) {
                "Original Windows boot observation requires 64-bit Windows"
            }
        }

        private val nt: WindowsOriginalBootNt by lazy {
            requireSupported()
            Native.load("ntdll", WindowsOriginalBootNt::class.java)
        }
        private val kernel: WindowsOriginalBootKernel by lazy {
            requireSupported()
            Native.load("kernel32", WindowsOriginalBootKernel::class.java)
        }

        fun observe(): WindowsOriginalBootSession {
            requireSupported()
            val boot = readBootId()
            val elapsed = readElapsedMillis()
            check(readBootId() == boot) { "Native Windows boot identity changed during observation" }
            return WindowsOriginalBootSession(boot, elapsed).also { it.elapsedMillis() }
        }

        /** GetTickCount64 is an OS elapsed-millisecond observation, not wall time or JVM uptime. */
        private fun readElapsedMillis(): Long {
            val elapsed = kernel.GetTickCount64()
            // The native value is unsigned. Refuse its unrepresentable half rather than wrap.
            check(elapsed >= 0) { "Native Windows elapsed time exceeds capacity" }
            return elapsed
        }

        private fun readBootId(): SelfOwnedNativeBootSessionV2.Windows = Memory(BOOT_ENVIRONMENT_BYTES.toLong()).use { bytes ->
            bytes.clear()
            val used = IntByReference()
            val status = nt.NtQuerySystemInformation(BOOT_ENVIRONMENT_INFORMATION, bytes, BOOT_ENVIRONMENT_BYTES, used)
            check(status == 0 && used.value == BOOT_ENVIRONMENT_BYTES) { "Native Windows boot environment is unavailable" }
            val firmware = bytes.getInt(16)
            check(firmware == FIRMWARE_BIOS || firmware == FIRMWARE_UEFI) { "Native Windows firmware type is unsupported" }
            // GUID's first three fields use native little-endian integers; Data4 is byte-ordered.
            // Preserve every GUID bit instead of reducing it to an integer boot counter.
            val most = ((bytes.getInt(0).toLong() and 0xffff_ffffL) shl 32) or
                ((bytes.getShort(4).toLong() and 0xffffL) shl 16) or
                (bytes.getShort(6).toLong() and 0xffffL)
            var least = 0L
            for (offset in 8L..15L) least = (least shl 8) or (bytes.getByte(offset).toLong() and 0xffL)
            check(most != 0L || least != 0L) { "Native Windows boot identity is absent" }
            val boot = UUID(most, least).toString()
            SelfOwnedNativeBootSessionV2.Windows(boot)
        }
    }
}

private interface WindowsOriginalBootNt : StdCallLibrary {
    fun NtQuerySystemInformation(kind: Int, information: Pointer, size: Int, used: IntByReference): Int
}

private interface WindowsOriginalBootKernel : StdCallLibrary {
    fun GetTickCount64(): Long
}
