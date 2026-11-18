package com.ukkera.pos.desktop

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.ukkera.pos.app.gateway.setup.ipc.DesktopRemoteSetupController
import com.ukkera.pos.app.gateway.setup.ipc.DesktopSetupIpcConnection
import com.ukkera.pos.app.gateway.setup.ipc.DesktopSetupUnixIpc
import com.ukkera.pos.security.SelfOwnedInstalledIdentityResultV2
import com.ukkera.pos.security.WindowsInstalledApplicationIdentity
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Explicit installation-runner check of the actual console transport. No company, account,
 * employee login or business operation is created. Fixed result fields contain no private setup.
 */
internal object DesktopMasterConsoleProbe {
    const val ARGUMENT = "--master-console-probe="
    const val USER_ARGUMENT = "--master-console-user-probe="

    fun run(output: File): Int = if (Platform.isWindows()) DesktopWindowsOrdinaryConsoleProcess.run(output)
        else runNative(output, ordinaryPrimary = false)

    fun runOrdinaryUser(output: File): Int = runNative(output, ordinaryPrimary = true)

    private fun runNative(output: File, ordinaryPrimary: Boolean): Int {
        var phase = "PLATFORM"
        var ordinaryIntegrity = "NOT_OBSERVED"
        var installedAgent = false
        var controller: DesktopRemoteSetupController? = null
        var retainedConnection: DesktopSetupIpcConnection? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var result = "FAILED phase=PLATFORM"
        var code = 1
        try {
            val connection: DesktopSetupIpcConnection = when {
                Platform.isWindows() -> {
                    phase = "PACKAGE"
                    val identity = DesktopWindowsPackageIdentity.TEST.target()
                    check(WindowsInstalledApplicationIdentity.captureUi(identity) is SelfOwnedInstalledIdentityResultV2.Data)
                    phase = "ORDINARY_TOKEN"
                    check(ordinaryPrimary)
                    DesktopWindowsConsoleProbeAccess.withOrdinaryPrimaryToken { token ->
                        ordinaryIntegrity = DesktopWindowsConsoleProbeAccess.integrityLevel(token)
                        // The native OS evaluates these conditions on a real reduced token.
                        phase = "ACCESS_CHECK"
                        DesktopWindowsConsoleProbeAccess.verifyPolicy(token, identity.packageFamilyName, packaged = true)
                        phase = "PIPE_CONNECT"
                        DesktopSetupWindowsPipe.connect(identity.packageFamilyName)
                    }
                }
                Platform.isMac() -> {
                    phase = "AGENT"
                    if (!DesktopMacServiceAgent.isInstalled()) {
                        installedAgent = true // An uncertain install still needs explicit cleanup.
                        DesktopMacServiceAgent.install().getOrThrow()
                    }
                    phase = "SOCKET_CONNECT"
                    runBlocking {
                        withTimeout(30_000) {
                            var connected: DesktopSetupIpcConnection? = null
                            while (connected == null) {
                                connected = runCatching {
                                    DesktopSetupUnixIpc.connect(DesktopMasterServiceHost.macConsoleDirectory())
                                }.getOrNull()
                                if (connected == null) delay(100)
                            }
                            checkNotNull(connected)
                        }
                    }
                }
                else -> error("Unsupported platform")
            }
            retainedConnection = connection
            phase = "UI_HANDSHAKE"
            runBlocking {
                withTimeout(15_000) {
                    controller = scope.async {
                        DesktopRemoteSetupController.connect(connection, DesktopSetupPairingDocuments(), scope)
                    }.await()
                    check(checkNotNull(controller).compositionProfile != null)
                    check(checkNotNull(controller).connected.value)
                }
            }
            result = if (Platform.isWindows())
                "DATA platform=windows console=true ordinaryToken=true primaryToken=true integrity=MEDIUM packageClaim=true foreignClaimDenied=true pipeInstanceDenied=true aclMutationDenied=true"
            else "DATA platform=macos console=true nativeAgent=true"
            code = 0
        } catch (failure: Exception) {
            val site = failure.stackTrace.firstOrNull { it.className.startsWith("com.ukkera.pos.desktop.") }
            val at = site?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" } ?: "unavailable"
            val connectFailure = failure as? DesktopSetupWindowsPipe.ConnectFailure
            val nativeCode = connectFailure?.nativeCode?.takeIf { it in 0..65535 }
            // After the reduced-token scope has reverted, one owner-token control can
            // distinguish endpoint availability from the observed restricted-token failure.
            // The original check still FAILS; this creates no employee or business session.
            val control = if (Platform.isWindows() && phase == "PIPE_CONNECT" && connectFailure != null) {
                try {
                    DesktopSetupWindowsPipe.connect(DesktopWindowsPackageIdentity.TEST.target().packageFamilyName).close()
                    "OPENED"
                } catch (refused: DesktopSetupWindowsPipe.ConnectFailure) {
                    "REFUSED_${refused.nativeCode.takeIf { it in 0..65535 } ?: -1}"
                } catch (_: Exception) { "UNAVAILABLE" }
            } else "NOT_RUN"
            val pipeIntegrity = if (connectFailure != null) runCatching {
                DesktopSetupWindowsPipe.integrityLabelForProbe(DesktopWindowsPackageIdentity.TEST.target().packageFamilyName)
            }.getOrDefault("UNAVAILABLE") else "NOT_OBSERVED"
            result = "FAILED phase=$phase at=$at" +
                (nativeCode?.let { " win32=$it control=$control tokenIntegrity=$ordinaryIntegrity pipeIntegrity=$pipeIntegrity" } ?: "")
        } finally {
            if (runCatching { controller?.close() ?: retainedConnection?.close() }.isFailure) {
                result = "FAILED phase=CONSOLE_CLOSE"
                code = 1
            }
            scope.cancel()
            if (installedAgent) {
                if (DesktopMacServiceAgent.uninstall().isFailure) {
                    result = "FAILED phase=AGENT_CLOSE"
                    code = 1
                }
            }
        }
        output.writeText(result + "\n")
        return code
    }
}

/** Native test/probe support only; never used to grant application console access. */
internal object DesktopWindowsConsoleProbeAccess {
    fun <T> withOrdinaryToken(action: (Pointer) -> T): T {
        check(Platform.isWindows())
        val existing = PointerByReference()
        if (Win.advapi.OpenThreadToken(Win.kernel.GetCurrentThread(), TOKEN_QUERY, 1, existing) != 0) {
            Win.kernel.CloseHandle(existing.value)
            error("An existing impersonation must not be replaced")
        }
        check(Native.getLastError() == ERROR_NO_TOKEN)
        val processToken = PointerByReference()
        check(Win.advapi.OpenProcessToken(Win.kernel.GetCurrentProcess(), TOKEN_QUERY or TOKEN_DUPLICATE, processToken) != 0)
        val source = checkNotNull(processToken.value)
        try {
            val adminSid = PointerByReference()
            check(Win.advapi.ConvertStringSidToSidW(WString("S-1-5-32-544"), adminSid) != 0)
            try {
                Memory(if (Native.POINTER_SIZE == 8) 16 else 8).use { disabled ->
                    disabled.clear()
                    disabled.setPointer(0, adminSid.value)
                    val restricted = PointerByReference()
                    check(Win.advapi.CreateRestrictedToken(source, DISABLE_MAX_PRIVILEGE or LUA_TOKEN,
                        1, disabled, 0, null, 0, null, restricted) != 0)
                    val token = checkNotNull(restricted.value)
                    try {
                        val impersonation = PointerByReference()
                        check(Win.advapi.DuplicateToken(token, SECURITY_IMPERSONATION, impersonation) != 0)
                        val actual = checkNotNull(impersonation.value)
                        try {
                            Memory(4).use { elevation ->
                                val used = IntByReference()
                                check(Win.advapi.GetTokenInformation(actual, TOKEN_ELEVATION, elevation, 4, used) != 0)
                                check(used.value == 4 && elevation.getInt(0) == 0)
                            }
                            val member = IntByReference()
                            check(Win.advapi.CheckTokenMembership(actual, adminSid.value, member) != 0 && member.value == 0)
                            check(Win.advapi.ImpersonateLoggedOnUser(actual) != 0)
                            try { return action(actual) }
                            finally { check(Win.advapi.RevertToSelf() != 0) }
                        } finally { check(Win.kernel.CloseHandle(actual) != 0) }
                    } finally { check(Win.kernel.CloseHandle(token) != 0) }
                }
            } finally { check(Win.kernel.LocalFree(adminSid.value) == null) }
        } finally { check(Win.kernel.CloseHandle(source) != 0) }
    }

    internal fun integrityName(rid: Int): String = when (rid) {
        0x0000 -> "UNTRUSTED"
        0x1000 -> "LOW"
        0x2000 -> "MEDIUM"
        0x2100 -> "MEDIUM_PLUS"
        0x3000 -> "HIGH"
        0x4000 -> "SYSTEM"
        else -> "OTHER"
    }

    internal fun integrityLevel(token: Pointer): String = Memory(256).use { buffer ->
        val used = IntByReference()
        check(Win.advapi.GetTokenInformation(token, 25, buffer, buffer.size().toInt(), used) != 0)
        val sid = buffer.getPointer(0)
        val count = sid.getByte(1).toInt() and 0xff
        check(count in 1..15)
        integrityName(sid.getInt(8L + 4L * (count - 1)))
    }

    /** No impersonation: the actual pipe call uses the ordinary primary process token. */
    fun <T> withOrdinaryPrimaryToken(action: (Pointer) -> T): T {
        val previous = PointerByReference()
        if (Win.advapi.OpenThreadToken(Win.kernel.GetCurrentThread(), TOKEN_QUERY, 1, previous) != 0) {
            Win.kernel.CloseHandle(previous.value); error("Unexpected impersonation")
        }
        check(Native.getLastError() == ERROR_NO_TOKEN)
        val reference = PointerByReference()
        check(Win.advapi.OpenProcessToken(Win.kernel.GetCurrentProcess(), TOKEN_QUERY or TOKEN_DUPLICATE, reference) != 0)
        val primary = checkNotNull(reference.value)
        try {
            Memory(4).use { data ->
                val used = IntByReference()
                check(Win.advapi.GetTokenInformation(primary, 8, data, 4, used) != 0 && data.getInt(0) == 1)
                check(Win.advapi.GetTokenInformation(primary, TOKEN_ELEVATION, data, 4, used) != 0 && data.getInt(0) == 0)
            }
            check(integrityLevel(primary) == "MEDIUM")
            val duplicate = PointerByReference()
            check(Win.advapi.DuplicateToken(primary, SECURITY_IMPERSONATION, duplicate) != 0)
            val query = checkNotNull(duplicate.value)
            try {
                val admin = PointerByReference()
                check(Win.advapi.ConvertStringSidToSidW(WString("S-1-5-32-544"), admin) != 0)
                try {
                    val member = IntByReference()
                    check(Win.advapi.CheckTokenMembership(query, admin.value, member) != 0 && member.value == 0)
                } finally { Win.kernel.LocalFree(admin.value) }
                return action(query) // AccessCheck takes this duplicate; thread token remains absent.
            } finally { Win.kernel.CloseHandle(query) }
        } finally { Win.kernel.CloseHandle(primary) }
    }

    fun verifyPolicy(token: Pointer, family: String, packaged: Boolean) {
        val descriptor = DesktopMasterConsoleAccess.descriptor(family)
        check(access(token, descriptor, DesktopMasterConsoleAccess.CLIENT_ACCESS) == packaged)
        if (packaged) {
            val previous = descriptor.replace("0x00100083", "0x00100003")
            check(previous != descriptor)
            check(access(token, previous, 0x00100003))
            check(!access(token, previous, DesktopMasterConsoleAccess.CLIENT_ACCESS))
        }
        check(!access(token, DesktopMasterConsoleAccess.descriptor("Other.POS.Test_n9kkbga0qmnm6"),
            DesktopMasterConsoleAccess.CLIENT_ACCESS))
        check(!access(token, descriptor, 0x00000004)) // FILE_CREATE_PIPE_INSTANCE
        check(!access(token, descriptor, 0x00020000)) // READ_CONTROL
        check(!access(token, descriptor, 0x00040000)) // WRITE_DAC
        check(!access(token, descriptor, 0x00080000)) // WRITE_OWNER
        check(!access(token, descriptor, 0x00010000)) // DELETE
    }

    private fun access(token: Pointer, dacl: String, desired: Int): Boolean {
        val sd = PointerByReference()
        // Match the real service ownership when evaluating OW. A UI user's token must not
        // become the owner of this test descriptor and accidentally pass through the OW ACE.
        check(Win.advapi.ConvertStringSecurityDescriptorToSecurityDescriptorW(WString("O:LSG:LS$dacl"), 1, sd, null) != 0)
        try {
            Memory(16).use { mapping ->
                mapping.setInt(0, 0x00120089)
                mapping.setInt(4, 0x00120116)
                mapping.setInt(8, 0x001200a0)
                mapping.setInt(12, 0x001f01ff)
                Memory(1024).use { privileges ->
                    val length = IntByReference(1024)
                    val granted = IntByReference()
                    val allowed = IntByReference()
                    check(Win.advapi.AccessCheck(sd.value, token, desired, mapping, privileges, length, granted, allowed) != 0)
                    return allowed.value != 0 && granted.value and desired == desired
                }
            }
        } finally { check(Win.kernel.LocalFree(sd.value) == null) }
    }

    private const val TOKEN_DUPLICATE = 0x0002
    private const val TOKEN_QUERY = 0x0008
    private const val TOKEN_ELEVATION = 20
    private const val DISABLE_MAX_PRIVILEGE = 1
    private const val LUA_TOKEN = 4
    private const val SECURITY_IMPERSONATION = 2
    private const val ERROR_NO_TOKEN = 1008

    @Suppress("FunctionName")
    private interface Kernel : StdCallLibrary {
        fun GetCurrentProcess(): Pointer
        fun GetCurrentThread(): Pointer
        fun CloseHandle(handle: Pointer?): Int
        fun LocalFree(memory: Pointer?): Pointer?
    }

    @Suppress("FunctionName")
    private interface Advapi : StdCallLibrary {
        fun OpenProcessToken(process: Pointer, access: Int, token: PointerByReference): Int
        fun OpenThreadToken(thread: Pointer, access: Int, asSelf: Int, token: PointerByReference): Int
        fun ConvertStringSidToSidW(sid: WString, result: PointerByReference): Int
        fun CreateRestrictedToken(source: Pointer, flags: Int, disabledCount: Int, disabled: Pointer?,
            removedCount: Int, removed: Pointer?, restrictedCount: Int, restricted: Pointer?, result: PointerByReference): Int
        fun DuplicateToken(token: Pointer, level: Int, result: PointerByReference): Int
        fun GetTokenInformation(token: Pointer, kind: Int, result: Pointer?, capacity: Int, used: IntByReference): Int
        fun CheckTokenMembership(token: Pointer?, sid: Pointer?, member: IntByReference): Int
        fun ImpersonateLoggedOnUser(token: Pointer): Int
        fun RevertToSelf(): Int
        fun ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl: WString, revision: Int,
            result: PointerByReference, size: Pointer?): Int
        fun AccessCheck(descriptor: Pointer?, token: Pointer, desired: Int, mapping: Pointer, privileges: Pointer,
            privilegeBytes: IntByReference, granted: IntByReference, allowed: IntByReference): Int
    }

    private object Win {
        val kernel: Kernel by lazy { Native.load("kernel32", Kernel::class.java) }
        val advapi: Advapi by lazy { Native.load("advapi32", Advapi::class.java) }
    }
}
