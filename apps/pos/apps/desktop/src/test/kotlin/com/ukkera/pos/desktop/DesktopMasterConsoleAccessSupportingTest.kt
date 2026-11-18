package com.ukkera.pos.desktop

import com.sun.jna.Platform
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Self-authored, NOT independent. Native positive packaged-token checks run in the MSIX probe. */
class DesktopMasterConsoleAccessSupportingTest {
    @Test
    fun malformedFamilyCannotInjectAnAccessCondition() {
        listOf("", "Ukkera", "Ukkera_test", "Ukkera.POS.Test_n9kkbga0qmnm6\"))", "x\n_n9kkbga0qmnm6").forEach {
            assertFailsWith<IllegalArgumentException> { DesktopMasterConsoleAccess.descriptor(it) }
        }
    }

    @Test
    fun windowsDeniesUnpackagedOrdinaryTokenAndOwnershipRights() {
        assumeTrue("Requires the real Windows access checker", Platform.isWindows())
        DesktopWindowsConsoleProbeAccess.withOrdinaryToken { token ->
            DesktopWindowsConsoleProbeAccess.verifyPolicy(token, DesktopWindowsPackageIdentity.TEST.target().packageFamilyName,
                packaged = false)
        }
    }
}
