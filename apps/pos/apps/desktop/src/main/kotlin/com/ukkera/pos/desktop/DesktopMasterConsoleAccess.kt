package com.ukkera.pos.desktop

import com.ukkera.pos.security.SelfOwnedWindowsPackagedAppIdentityV2

/** OS access to the local console is separate from employee/head authorization inside it. */
internal object DesktopMasterConsoleAccess {
    // FILE_READ_DATA | FILE_WRITE_DATA | FILE_READ_ATTRIBUTES | SYNCHRONIZE.
    // CreateFileW also requests read-attributes; make that minimum contract explicit.
    // In particular, do not include
    // FILE_APPEND_DATA: on a pipe it is FILE_CREATE_PIPE_INSTANCE. GENERIC_WRITE includes it.
    const val CLIENT_ACCESS: Int = 0x00100083

    fun descriptor(packageFamily: String): String {
        // Use the existing identity grammar before interpolating an OS claim into SDDL.
        SelfOwnedWindowsPackagedAppIdentityV2(packageFamily, "$packageFamily!UkkeraPOS")
        // MSIX full-trust processes carry their identity in WIN://SYSAPPID, rather than
        // necessarily having an AppContainer SID. Both the Users membership and this exact
        // OS-issued package claim are required. No unconditional Users/Everyone ACE is added.
        // The native pipe additionally checks the peer's actual package on both ends.
        return "D:P(A;;GA;;;SY)(A;;GA;;;BA)(A;;GA;;;OW)" +
            "(XA;;0x00100083;;;BU;(WIN://SYSAPPID Contains \"$packageFamily\"))"
    }
}
