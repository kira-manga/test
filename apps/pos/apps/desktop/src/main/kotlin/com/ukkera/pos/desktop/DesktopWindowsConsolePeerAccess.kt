package com.ukkera.pos.desktop

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.ukkera.pos.security.SelfOwnedInstalledIdentityResultV2
import com.ukkera.pos.security.SelfOwnedWindowsPackagedAppIdentityV2
import com.ukkera.pos.security.SelfOwnedWindowsPackagedServiceIdentityV2
import com.ukkera.pos.security.WindowsInstalledApplicationIdentity

/** Only package-identity observation of the actual peer process/token; never control access. */
internal object DesktopWindowsConsolePeerAccess {
    const val QUERY_LIMITED = 0x00001000
    const val TOKEN_QUERY = 0x00000008
    internal enum class Step { SERVICE_IDENTITY, UI_IDENTITY, TOKEN_OPEN, TOKEN_CLOSE, ACL_QUERY, ACL_CONTROL, RULE_PARSE, ACL_PARSE, ACL_INITIALIZE, ACL_COPY, ACL_SET, ACL_READBACK }
    internal class Failure(val step: Step, val nativeCode: Int) : IllegalStateException("Console peer query is unavailable")
    private fun status(code: Int, step: Step) { if (code != 0) throw Failure(step, code) }
    private fun bool(result: Int, step: Step) { if (result == 0) throw Failure(step, Native.getLastError()) }
    private fun demand(value: Boolean, step: Step) { if (!value) throw Failure(step, 0) }

    internal fun queryRule(family: String): String {
        SelfOwnedWindowsPackagedAppIdentityV2(family, "$family!UkkeraPOS")
        return "D:(XA;;0x00001000;;;BU;(WIN://SYSAPPID Contains \"$family\"))"
    }

    internal fun tokenQueryRule(family: String): String {
        SelfOwnedWindowsPackagedAppIdentityV2(family, "$family!UkkeraPOS")
        return "D:(XA;;0x00000008;;;BU;(WIN://SYSAPPID Contains \"$family\"))"
    }

    fun grant(expected: SelfOwnedWindowsPackagedServiceIdentityV2) {
        demand(WindowsInstalledApplicationIdentity.capturePackagedService(expected) is SelfOwnedInstalledIdentityResultV2.Data, Step.SERVICE_IDENTITY)
        grantRule(queryRule(expected.packageFamilyName), tokenQueryRule(expected.packageFamilyName))
    }

    fun grantUi(family: String) {
        val ui = DesktopWindowsPackageIdentity.TEST.target()
        val service = DesktopWindowsServiceIdentity.TEST.target()
        demand(family == ui.packageFamilyName && family == service.packageFamilyName, Step.UI_IDENTITY)
        demand(WindowsInstalledApplicationIdentity.captureUi(ui) is SelfOwnedInstalledIdentityResultV2.Data, Step.UI_IDENTITY)
        // The reverse PID/package check is made by this exact SCM service SID, not any
        // other Windows user, LocalService process or unpackaged caller.
        grantRule(serviceQueryRule(service), serviceTokenQueryRule(service))
    }

    internal fun serviceQueryRule(expected: SelfOwnedWindowsPackagedServiceIdentityV2): String =
        "D:(XA;;0x00001000;;;${expected.serviceSid};(WIN://SYSAPPID Contains \"${expected.packageFamilyName}\"))"

    internal fun serviceTokenQueryRule(expected: SelfOwnedWindowsPackagedServiceIdentityV2): String =
        "D:(XA;;0x00000008;;;${expected.serviceSid};(WIN://SYSAPPID Contains \"${expected.packageFamilyName}\"))"

    @Synchronized
    private fun grantRule(processRule: String, tokenRule: String) {
        val process = Win.kernel.GetCurrentProcess() // No target PID supplied by a UI/caller.
        addRule(process, processRule)
        val opened = PointerByReference()
        // Change only this primary token object's DACL, never TokenDefaultDacl or privileges.
        bool(Win.advapi.OpenProcessToken(process, 0x00060008, opened), Step.TOKEN_OPEN)
        val token = checkNotNull(opened.value)
        try { addRule(token, tokenRule) }
        finally { bool(Win.kernel.CloseHandle(token), Step.TOKEN_CLOSE) }
    }

    private fun addRule(handle: Pointer, ruleSddl: String) {
        withDacl(handle) { original, control ->
            val parsed = PointerByReference()
            bool(Win.advapi.ConvertStringSecurityDescriptorToSecurityDescriptorW(
                WString(ruleSddl), 1, parsed, null), Step.RULE_PARSE)
            try {
                val present = IntByReference(); val defaulted = IntByReference(); val acl = PointerByReference()
                bool(Win.advapi.GetSecurityDescriptorDacl(parsed.value, present, acl, defaulted), Step.RULE_PARSE)
                demand(present.value != 0 && acl.value != null, Step.RULE_PARSE)
                val rule = entries(checkNotNull(acl.value)).single()
                if (original.any { it.contentEquals(rule) }) return@withDacl
                // Keep every original ACE and its ordering. Insert this explicit allow before
                // inherited entries; existing explicit deny rules remain ahead of it.
                val firstInherited = original.indexOfFirst { (it[1].toInt() and 0x10) != 0 }
                if (firstInherited >= 0) demand(original.drop(firstInherited).all { (it[1].toInt() and 0x10) != 0 }, Step.ACL_PARSE)
                val merged = original.toMutableList().apply { add(if (firstInherited < 0) size else firstInherited, rule) }
                val size = 8L + merged.sumOf { it.size.toLong() }
                demand(size <= 65535, Step.ACL_PARSE)
                Memory(size).use { result ->
                    bool(Win.advapi.InitializeAcl(result, size.toInt(), 4), Step.ACL_INITIALIZE)
                    for (entry in merged) Memory(entry.size.toLong()).use { bytes ->
                        bytes.write(0, entry, 0, entry.size)
                        bool(Win.advapi.AddAce(result, 4, -1, bytes, entry.size), Step.ACL_COPY)
                    }
                    // Owner, group, SACL and old DACL protection remain unchanged.
                    val information = 4 or (if (control and 0x1000 != 0) Int.MIN_VALUE else 0)
                    status(Win.advapi.SetSecurityInfo(handle, 6, information, null, null, result, null), Step.ACL_SET)
                }
                withDacl(handle) { actual, actualControl ->
                    demand(actual.size == merged.size && actual.zip(merged).all { (a, b) -> a.contentEquals(b) }, Step.ACL_READBACK)
                    demand((actualControl and 0x1000) == (control and 0x1000), Step.ACL_READBACK)
                }
            } finally { Win.kernel.LocalFree(parsed.value) }
        }
    }

    private inline fun <T> withDacl(process: Pointer, action: (List<ByteArray>, Int) -> T): T {
        val acl = PointerByReference(); val descriptor = PointerByReference()
        status(Win.advapi.GetSecurityInfo(process, 6, 4, null, null, acl, null, descriptor), Step.ACL_QUERY)
        try {
            val control = IntByReference(); val revision = IntByReference()
            bool(Win.advapi.GetSecurityDescriptorControl(descriptor.value, control, revision), Step.ACL_CONTROL)
            // No NULL/absent accepting DACL, synthesized process default or foreign source.
            demand(acl.value != null, Step.ACL_PARSE)
            return action(entries(acl.value), control.value and 0xffff)
        } finally { Win.kernel.LocalFree(descriptor.value) }
    }

    private fun entries(acl: Pointer): List<ByteArray> {
        val size = acl.getShort(2).toInt() and 0xffff
        val count = acl.getShort(4).toInt() and 0xffff
        demand(size in 8..65535 && count <= (size - 8) / 4, Step.ACL_PARSE)
        var bytes = 8
        return List(count) { index ->
            val entry = PointerByReference()
            bool(Win.advapi.GetAce(acl, index, entry), Step.ACL_PARSE)
            val length = entry.value.getShort(2).toInt() and 0xffff
            demand(length >= 4 && length % 4 == 0 && bytes + length <= size, Step.ACL_PARSE)
            bytes += length
            entry.value.getByteArray(0, length)
        }
    }
    private interface Kernel : StdCallLibrary {
        fun GetCurrentProcess(): Pointer
        fun LocalFree(memory: Pointer?): Pointer?
        fun CloseHandle(handle: Pointer): Int
    }
    private interface Advapi : StdCallLibrary {
        fun OpenProcessToken(process: Pointer, access: Int, token: PointerByReference): Int
        fun ConvertStringSecurityDescriptorToSecurityDescriptorW(value: WString, revision: Int, descriptor: PointerByReference, size: Pointer?): Int
        fun GetSecurityInfo(handle: Pointer, kind: Int, information: Int, owner: PointerByReference?, group: PointerByReference?,
            dacl: PointerByReference?, sacl: PointerByReference?, descriptor: PointerByReference): Int
        fun SetSecurityInfo(handle: Pointer, kind: Int, information: Int, owner: Pointer?, group: Pointer?, dacl: Pointer?, sacl: Pointer?): Int
        fun GetSecurityDescriptorControl(descriptor: Pointer, control: IntByReference, revision: IntByReference): Int
        fun GetSecurityDescriptorDacl(descriptor: Pointer, present: IntByReference, dacl: PointerByReference, defaulted: IntByReference): Int
        fun GetAce(acl: Pointer, index: Int, entry: PointerByReference): Int
        fun InitializeAcl(acl: Pointer, size: Int, revision: Int): Int
        fun AddAce(acl: Pointer, revision: Int, index: Int, entry: Pointer, size: Int): Int
    }
    private object Win {
        val kernel: Kernel by lazy { Native.load("kernel32", Kernel::class.java) }
        val advapi: Advapi by lazy { Native.load("advapi32", Advapi::class.java) }
    }
}
