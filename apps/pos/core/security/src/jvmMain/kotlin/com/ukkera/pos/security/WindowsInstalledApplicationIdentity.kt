package com.ukkera.pos.security

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary

/** Version and process-context observations accompany, but never replace, the identity tuple. */
public class WindowsPackagedIdentityObservation internal constructor(
    public val target: SelfOwnedWindowsPackagedAppIdentityV2,
    public val packageFullName: String,
    public val isAppContainer: Boolean,
    public val appContainerSid: String?,
) {
    override fun toString(): String = "WindowsPackagedIdentityObservation([REDACTED])"
}

/** Native service-token observations; the target remains DATA, never a supervised owner. */
public class WindowsServiceIdentityObservation internal constructor(
    public val target: SelfOwnedInstalledIdentityTargetV2,
    public val packageFullName: String?,
    public val isAppContainer: Boolean,
    public val appContainerSid: String?,
) {
    override fun toString(): String = "WindowsServiceIdentityObservation([REDACTED])"
}

/**
 * Native current-process observation of the V2 Windows packaged UI identity. Reads the process
 * token, never an impersonating thread token. An unpackaged JVM or missing app identity is
 * refused; executable names, environment variables and user SIDs are never identity substitutes.
 * The result remains DATA and grants no native owner, key, service or employee authority.
 */
public object WindowsInstalledApplicationIdentity {
    public fun captureUi(expected: SelfOwnedWindowsPackagedAppIdentityV2):
        SelfOwnedInstalledIdentityResultV2<WindowsPackagedIdentityObservation> {
        if (!Platform.isWindows()) return refused(SelfOwnedInstalledIdentityRefusalV2.REFUSE_PLATFORM_MISMATCH)
        return try {
            // The first full-name call must prove package identity before other fields are read.
            val fullName = text(WindowsIdentityNative.kernel::GetCurrentPackageFullName, 1024)
            val family = text(WindowsIdentityNative.kernel::GetCurrentPackageFamilyName, 65)
            val aumid = text(WindowsIdentityNative.kernel::GetCurrentApplicationUserModelId, 130)
            val actual = SelfOwnedWindowsPackagedAppIdentityV2(family, aumid)
            if (!SelfOwnedInstalledIdentityV2.matches(expected, actual)) return refused(
                SelfOwnedInstalledIdentityRefusalV2.REFUSE_IDENTITY_MISMATCH)
            val token = PointerByReference()
            check(WindowsIdentityNative.advapi.OpenProcessToken(WindowsIdentityNative.kernel.GetCurrentProcess(),
                TOKEN_QUERY, token) != 0) { "Current process token is unavailable" }
            val handle = checkNotNull(token.value)
            try {
                val isContainer = Memory(4).use { bytes ->
                    val used = IntByReference()
                    check(WindowsIdentityNative.advapi.GetTokenInformation(handle, TOKEN_IS_APP_CONTAINER,
                        bytes, 4, used) != 0 && used.value == 4)
                    when (bytes.getInt(0)) { 0 -> false; 1 -> true; else -> error("Invalid AppContainer observation") }
                }
                val sid = if (isContainer) appContainerSid(handle) else null
                SelfOwnedInstalledIdentityResultV2.Data(WindowsPackagedIdentityObservation(actual,
                    fullName, isContainer, sid))
            } finally {
                check(WindowsIdentityNative.kernel.CloseHandle(handle) != 0) { "Process token close failed" }
            }
        } catch (_: NoPackage) {
            refused(SelfOwnedInstalledIdentityRefusalV2.REFUSE_UNSUPPORTED_CONTEXT)
        } catch (_: IllegalArgumentException) {
            refused(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
        } catch (_: Exception) {
            refused(SelfOwnedInstalledIdentityRefusalV2.REFUSE_NATIVE_OBSERVATION_UNAVAILABLE)
        } catch (_: LinkageError) {
            refused(SelfOwnedInstalledIdentityRefusalV2.REFUSE_NATIVE_OBSERVATION_UNAVAILABLE)
        }
    }

    /** Exact process service SID and account; a packaged process cannot use this profile. */
    public fun captureService(expected: SelfOwnedWindowsServiceIdentityV2):
        SelfOwnedInstalledIdentityResultV2<WindowsServiceIdentityObservation> = captureServiceTarget(expected)

    /** Requires package AND service identities; neither can replace the other on failure. */
    public fun capturePackagedService(expected: SelfOwnedWindowsPackagedServiceIdentityV2):
        SelfOwnedInstalledIdentityResultV2<WindowsServiceIdentityObservation> = captureServiceTarget(expected)

    private fun captureServiceTarget(expected: SelfOwnedInstalledIdentityTargetV2):
        SelfOwnedInstalledIdentityResultV2<WindowsServiceIdentityObservation> {
        if (!Platform.isWindows()) return refused(SelfOwnedInstalledIdentityRefusalV2.REFUSE_PLATFORM_MISMATCH)
        return try {
            val packageSize = IntByReference()
            val packageFullName = when (val status = WindowsIdentityNative.kernel.GetCurrentPackageFullName(packageSize, null)) {
                NO_PACKAGE -> null
                INSUFFICIENT_BUFFER -> text(WindowsIdentityNative.kernel::GetCurrentPackageFullName, 1024)
                else -> error("Native package observation failed ($status)")
            }
            val token = PointerByReference()
            check(WindowsIdentityNative.advapi.OpenProcessToken(WindowsIdentityNative.kernel.GetCurrentProcess(),
                TOKEN_QUERY, token) != 0)
            val handle = checkNotNull(token.value)
            try {
                val account = tokenInformation(handle, TOKEN_USER) { bytes, capacity ->
                    sidString(bytes, capacity, checkNotNull(bytes.getPointer(0)))
                }
                val serviceIds = tokenInformation(handle, TOKEN_GROUPS) { bytes, capacity ->
                    val count = bytes.getInt(0)
                    val stride = if (Native.POINTER_SIZE == 8) 16 else 8
                    val start = if (Native.POINTER_SIZE == 8) 8 else 4
                    check(count in 0..4096 && start.toLong() + count.toLong() * stride <= capacity)
                    buildList {
                        repeat(count) { index ->
                            val offset = start.toLong() + index.toLong() * stride
                            val sid = sidString(bytes, capacity, checkNotNull(bytes.getPointer(offset)))
                            if (sid.startsWith("S-1-5-80-")) {
                                check(SelfOwnedInstalledIdentityGrammarV2.serviceSid(sid))
                                val attributes = bytes.getInt(offset + Native.POINTER_SIZE)
                                add(sid to attributes)
                            }
                        }
                    }
                }
                if (serviceIds.size != 1) return refused(SelfOwnedInstalledIdentityRefusalV2.REFUSE_UNSUPPORTED_CONTEXT)
                val (serviceSid, attributes) = serviceIds.single()
                check(attributes and 4 != 0 && attributes and 16 == 0) { "Service SID is not enabled" }
                val actual: SelfOwnedInstalledIdentityTargetV2 = if (packageFullName == null) {
                    SelfOwnedWindowsServiceIdentityV2(serviceSid, account)
                } else {
                    SelfOwnedWindowsPackagedServiceIdentityV2(
                        text(WindowsIdentityNative.kernel::GetCurrentPackageFamilyName, 65),
                        text(WindowsIdentityNative.kernel::GetCurrentApplicationUserModelId, 130),
                        serviceSid, account)
                }
                val mismatch = SelfOwnedInstalledIdentityV2.refusalFor(expected, actual)
                if (mismatch != null) return refused(mismatch)
                val isContainer = tokenInformation(handle, TOKEN_IS_APP_CONTAINER) { bytes, capacity ->
                    check(capacity == 4)
                    when (bytes.getInt(0)) { 0 -> false; 1 -> true; else -> error("Invalid AppContainer observation") }
                }
                SelfOwnedInstalledIdentityResultV2.Data(WindowsServiceIdentityObservation(actual,
                    packageFullName, isContainer, if (isContainer) appContainerSid(handle) else null))
            } finally { check(WindowsIdentityNative.kernel.CloseHandle(handle) != 0) }
        } catch (_: NoPackage) {
            refused(SelfOwnedInstalledIdentityRefusalV2.REFUSE_UNSUPPORTED_CONTEXT)
        } catch (_: IllegalArgumentException) {
            refused(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
        } catch (_: Exception) {
            refused(SelfOwnedInstalledIdentityRefusalV2.REFUSE_NATIVE_OBSERVATION_UNAVAILABLE)
        } catch (_: LinkageError) {
            refused(SelfOwnedInstalledIdentityRefusalV2.REFUSE_NATIVE_OBSERVATION_UNAVAILABLE)
        }
    }

    private inline fun <T> tokenInformation(token: Pointer, kind: Int, block: (Memory, Int) -> T): T {
        val needed = IntByReference()
        check(WindowsIdentityNative.advapi.GetTokenInformation(token, kind, null, 0, needed) == 0 &&
            needed.value in 4..65536)
        return Memory(needed.value.toLong()).use { bytes ->
            val capacity = needed.value
            check(WindowsIdentityNative.advapi.GetTokenInformation(token, kind, bytes, capacity, needed) != 0 &&
                needed.value in 4..capacity)
            block(bytes, needed.value)
        }
    }

    /** TokenUser/TokenGroups SID pointers must stay inside their owning native result buffer. */
    private fun sidString(buffer: Memory, capacity: Int, sid: Pointer): String {
        val offset = Pointer.nativeValue(sid) - Pointer.nativeValue(buffer)
        check(offset >= 0 && offset <= capacity.toLong() - 8)
        val count = sid.getByte(1).toInt() and 255
        check(count <= 15 && offset + 8 + count * 4 <= capacity)
        check(WindowsIdentityNative.advapi.IsValidSid(sid) != 0)
        val converted = PointerByReference()
        check(WindowsIdentityNative.advapi.ConvertSidToStringSidW(sid, converted) != 0)
        val string = checkNotNull(converted.value)
        return try { string.getWideString(0).also {
            check(SelfOwnedInstalledIdentityGrammarV2.accountSid(it))
        } } finally { check(WindowsIdentityNative.kernel.LocalFree(string) == null) }
    }

    private fun appContainerSid(token: Pointer): String {
        val needed = IntByReference()
        check(WindowsIdentityNative.advapi.GetTokenInformation(token, TOKEN_APP_CONTAINER_SID,
            null, 0, needed) == 0 && needed.value in Native.POINTER_SIZE..65536)
        return Memory(needed.value.toLong()).use { bytes ->
            val capacity = needed.value
            check(WindowsIdentityNative.advapi.GetTokenInformation(token, TOKEN_APP_CONTAINER_SID,
                bytes, capacity, needed) != 0 && needed.value in Native.POINTER_SIZE..capacity)
            val sid = checkNotNull(bytes.getPointer(0))
            check(WindowsIdentityNative.advapi.IsValidSid(sid) != 0)
            val converted = PointerByReference()
            check(WindowsIdentityNative.advapi.ConvertSidToStringSidW(sid, converted) != 0)
            val string = checkNotNull(converted.value)
            try {
                string.getWideString(0).also { value ->
                    check(value.startsWith("S-1-15-2-") && value.length <= 184 &&
                        value.all { it == 'S' || it == '-' || it in '0'..'9' })
                }
            } finally { check(WindowsIdentityNative.kernel.LocalFree(string) == null) }
        }
    }

    private fun text(call: (IntByReference, Pointer?) -> Int, maximum: Int): String {
        val count = IntByReference()
        when (val status = call(count, null)) {
            NO_PACKAGE, NO_APPLICATION -> throw NoPackage()
            INSUFFICIENT_BUFFER -> Unit
            else -> error("Native package identity size failed ($status)")
        }
        check(count.value in 2..maximum)
        val capacity = count.value
        return Memory(capacity.toLong() * 2).use { memory ->
            memory.clear()
            check(call(count, memory) == 0 && count.value in 2..capacity)
            val value = memory.getWideString(0)
            check(value.length == count.value - 1 && '\u0000' !in value)
            // Refuse malformed native UTF-16 rather than normalize a different identity.
            selfOwnedUtf16(value)
            value
        }
    }

    private fun refused(code: SelfOwnedInstalledIdentityRefusalV2): SelfOwnedInstalledIdentityResultV2.Refused =
        SelfOwnedInstalledIdentityResultV2.Refused(code)
    private class NoPackage : IllegalStateException()
    private const val TOKEN_QUERY = 0x0008
    private const val TOKEN_USER = 1
    private const val TOKEN_GROUPS = 2
    private const val TOKEN_IS_APP_CONTAINER = 29
    private const val TOKEN_APP_CONTAINER_SID = 31
    private const val INSUFFICIENT_BUFFER = 122
    private const val NO_PACKAGE = 15700
    private const val NO_APPLICATION = 15703
}

internal object WindowsIdentityNative {
    val kernel: WindowsIdentityKernel = Native.load("kernel32", WindowsIdentityKernel::class.java)
    val advapi: WindowsIdentityAdvapi = Native.load("advapi32", WindowsIdentityAdvapi::class.java)
}

internal interface WindowsIdentityKernel : StdCallLibrary {
    fun GetCurrentPackageFullName(length: IntByReference, buffer: Pointer?): Int
    fun GetCurrentPackageFamilyName(length: IntByReference, buffer: Pointer?): Int
    fun GetCurrentApplicationUserModelId(length: IntByReference, buffer: Pointer?): Int
    fun GetCurrentProcess(): Pointer
    fun CloseHandle(handle: Pointer): Int
    fun LocalFree(memory: Pointer): Pointer?
}

internal interface WindowsIdentityAdvapi : StdCallLibrary {
    fun OpenProcessToken(process: Pointer, access: Int, token: PointerByReference): Int
    fun GetTokenInformation(token: Pointer, kind: Int, buffer: Pointer?, capacity: Int,
        used: IntByReference): Int
    fun IsValidSid(sid: Pointer): Int
    fun ConvertSidToStringSidW(sid: Pointer, result: PointerByReference): Int
}
