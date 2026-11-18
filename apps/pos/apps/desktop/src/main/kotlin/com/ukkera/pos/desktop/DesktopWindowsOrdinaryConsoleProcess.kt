package com.ukkera.pos.desktop

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.ukkera.pos.security.SelfOwnedInstalledIdentityResultV2
import com.ukkera.pos.security.WindowsInstalledApplicationIdentity
import java.io.File

/** Explicit package-runner probe only. The child has a real ordinary primary token. */
internal object DesktopWindowsOrdinaryConsoleProcess {
    fun run(output: File): Int {
        var phase = "PARENT_PACKAGE"
        var child: Pointer? = null
        var thread: Pointer? = null
        try {
            val expected = DesktopWindowsPackageIdentity.TEST.target()
            check(WindowsInstalledApplicationIdentity.captureUi(expected) is SelfOwnedInstalledIdentityResultV2.Data)
            val executable = ProcessHandle.current().info().command().orElseThrow()
            check(executable.endsWith(".exe", ignoreCase = true) && '\"' !in executable)
            val target = output.absolutePath
            check(target.none { it.isISOControl() } && '\"' !in target)
            phase = "PRIMARY_TOKEN"
            val original = PointerByReference()
            native(Win.advapi.OpenProcessToken(Win.kernel.GetCurrentProcess(), 0x008b, original))
            val source = checkNotNull(original.value)
            try {
                val admin = PointerByReference()
                native(Win.advapi.ConvertStringSidToSidW(WString("S-1-5-32-544"), admin))
                try {
                    Memory(16).use { disabled ->
                        disabled.clear(); disabled.setPointer(0, admin.value)
                        val result = PointerByReference()
                        native(Win.advapi.CreateRestrictedToken(source, 5, 1, disabled, 0, null, 0, null, result))
                        val token = checkNotNull(result.value)
                        try {
                            phase = "MEDIUM_INTEGRITY"
                            val medium = PointerByReference()
                            native(Win.advapi.ConvertStringSidToSidW(WString("S-1-16-8192"), medium))
                            try {
                                // TOKEN_MANDATORY_LABEL and its exact SID in one bounded buffer.
                                Memory(28).use { label ->
                                    label.clear(); label.write(16, medium.value.getByteArray(0, 12), 0, 12)
                                    label.setPointer(0, label.share(16)); label.setInt(8, 0x20)
                                    native(Win.advapi.SetTokenInformation(token, 25, label, 28))
                                }
                            } finally { Win.kernel.LocalFree(medium.value) }
                            phase = "ORDINARY_PROCESS_CREATE"
                            val command = "\"$executable\" ${DesktopMasterConsoleProbe.USER_ARGUMENT}\"$target\""
                            Memory((command.length + 1L) * 2).use { text ->
                                text.setWideString(0, command)
                                Memory(104).use { startup -> Memory(24).use { process ->
                                    startup.clear(); startup.setInt(0, 104); process.clear()
                                    // A restricted version of our own primary token needs no
                                    // replacement user or reusable credential. The OS still
                                    // has to establish the child's genuine package identity.
                                    native(Win.advapi.CreateProcessAsUserW(token, WString(executable), text,
                                        null, null, 0, 0x08000400, null, null, startup, process))
                                    child = process.getPointer(0); thread = process.getPointer(8)
                                } }
                            }
                        } finally { Win.kernel.CloseHandle(token) }
                    }
                } finally { Win.kernel.LocalFree(admin.value) }
            } finally { Win.kernel.CloseHandle(source) }
            phase = "ORDINARY_PROCESS_WAIT"
            val process = checkNotNull(child)
            if (Win.kernel.WaitForSingleObject(process, 90_000) != 0) {
                // Only this bounded no-business probe child; never a Master or journal owner.
                Win.kernel.TerminateProcess(process, 71)
                Win.kernel.WaitForSingleObject(process, 5_000)
                error("Probe child did not finish")
            }
            val exit = IntByReference()
            native(Win.kernel.GetExitCodeProcess(process, exit))
            check(output.isFile)
            return exit.value
        } catch (failure: Exception) {
            val code = (failure as? NativeFailure)?.code?.takeIf { it in 0..65535 }
            output.writeText("FAILED phase=$phase" + (code?.let { " win32=$it" } ?: "") + "\n")
            return 1
        } finally {
            thread?.let { Win.kernel.CloseHandle(it) }
            child?.let { Win.kernel.CloseHandle(it) }
        }
    }
    private class NativeFailure(val code: Int) : IllegalStateException()
    private fun native(ok: Int) { if (ok == 0) throw NativeFailure(Native.getLastError()) }

    private interface Kernel : StdCallLibrary {
        fun GetCurrentProcess(): Pointer
        fun CloseHandle(handle: Pointer?): Int
        fun LocalFree(memory: Pointer?): Pointer?
        fun WaitForSingleObject(handle: Pointer, millis: Int): Int
        fun GetExitCodeProcess(handle: Pointer, code: IntByReference): Int
        fun TerminateProcess(handle: Pointer, code: Int): Int
    }
    private interface Advapi : StdCallLibrary {
        fun OpenProcessToken(process: Pointer, access: Int, result: PointerByReference): Int
        fun ConvertStringSidToSidW(sid: WString, result: PointerByReference): Int
        fun CreateRestrictedToken(source: Pointer, flags: Int, disabledCount: Int, disabled: Pointer?,
            removedCount: Int, removed: Pointer?, restrictedCount: Int, restricted: Pointer?, result: PointerByReference): Int
        fun SetTokenInformation(token: Pointer, kind: Int, value: Pointer, size: Int): Int
        fun CreateProcessAsUserW(token: Pointer, application: WString, command: Pointer,
            processSecurity: Pointer?, threadSecurity: Pointer?, inherit: Int, flags: Int,
            environment: Pointer?, directory: WString?, startup: Pointer, process: Pointer): Int
    }
    private object Win {
        val kernel: Kernel by lazy { Native.load("kernel32", Kernel::class.java) }
        val advapi: Advapi by lazy { Native.load("advapi32", Advapi::class.java) }
    }
}
