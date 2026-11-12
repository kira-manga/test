package com.ukkera.pos.desktop

import com.sun.jna.Platform
import com.ukkera.pos.security.SelfOwnedInstalledIdentityResultV2
import com.ukkera.pos.security.WindowsInstalledApplicationIdentity
import java.io.File

/**
 * Bounded packaging check: compares the running process's actual OS package identity with the
 * identity this build declares. Output is DATA for diagnostics; it opens no key, storage or service.
 */
internal object DesktopIdentityProbe {
    const val ARGUMENT = "--identity-probe="

    /** Exit code 0 only when the native observation matches the declared identity. */
    fun run(output: File): Int {
        val (code, line) = when {
            Platform.isWindows() -> windows()
            else -> 2 to "UNSUPPORTED_PLATFORM"
        }
        output.writeText(line + "\n")
        return code
    }

    private fun windows(): Pair<Int, String> {
        val expected = DesktopWindowsPackageIdentity.TEST.target()
        return when (val result = WindowsInstalledApplicationIdentity.captureUi(expected)) {
            is SelfOwnedInstalledIdentityResultV2.Data -> {
                val observed = result.value
                0 to "DATA pfn=${observed.target.packageFamilyName} aumid=${observed.target.applicationUserModelId} " +
                    "appContainer=${observed.isAppContainer}"
            }
            is SelfOwnedInstalledIdentityResultV2.Refused -> 1 to "REFUSED ${result.code}"
        }
    }
}
