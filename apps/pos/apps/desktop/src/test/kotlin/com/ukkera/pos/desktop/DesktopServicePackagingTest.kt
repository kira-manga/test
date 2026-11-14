package com.ukkera.pos.desktop

import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Service-mode packaging checks (14 November 2026). NOT independent: written by the same author
 * as the packaging change, by explicit owner instruction. Real launchd/SCM behavior is verified
 * only on the owner's Mac and Windows machines (docs/pos/desktop/MANUAL-TESTING.md).
 */
class DesktopServicePackagingTest {
    private val packaging = File(System.getProperty("ukkera.desktop.packaging.dir"))

    @Test
    fun windowsMasterServiceStartsAutomaticallyUnderTheExpectedAccount() {
        val text = File(packaging, "windows/AppxManifest.xml.template").readText()
        assertTrue("StartupType=\"auto\"" in text)
        assertTrue("StartAccount=\"localService\"" in text)
        assertEquals("S-1-5-19", DesktopWindowsServiceIdentity.TEST.target().serviceAccountSid)
    }

    @Test
    fun launchAgentRendersServiceModeOfTheBundledLauncher() {
        val launcher = Path.of("/Applications/UkkeraPOS.app/Contents/MacOS/UkkeraPOS")
        val plist = DesktopMacServiceAgent.render(launcher, Path.of("/Users/a&b/Library/Logs/com.ukkera.pos.master"))
        assertTrue("<string>com.ukkera.pos.master</string>" in plist)
        assertTrue("<string>$launcher</string>\n    <string>--service</string>" in plist)
        assertTrue("<string>/Users/a&amp;b/Library/Logs/com.ukkera.pos.master/master.err.log</string>" in plist)
        assertTrue("<key>RunAtLoad</key>\n  <true/>" in plist)
        assertTrue("<key>Umask</key>\n  <integer>63</integer>" in plist)
        assertTrue('@' !in plist.substringAfter("<plist"))
    }

    @Test
    fun launcherMustBeAnInstalledAppBundle() {
        val ok = { _: Path -> true }
        assertEquals(Path.of("/Applications/UkkeraPOS.app/Contents/MacOS/UkkeraPOS"),
            DesktopMacServiceAgent.bundledLauncher("/Applications/UkkeraPOS.app/Contents/MacOS/UkkeraPOS", null, ok))
        listOf(
            "/usr/bin/java",
            "relative/UkkeraPOS.app/Contents/MacOS/UkkeraPOS",
            "/Volumes/UkkeraPOS/UkkeraPOS.app/Contents/MacOS/UkkeraPOS",
            "/private/var/folders/x/AppTranslocation/y/d/UkkeraPOS.app/Contents/MacOS/UkkeraPOS",
        ).forEach { path ->
            assertFailsWith<IllegalStateException>(path) { DesktopMacServiceAgent.bundledLauncher(path, null, ok) }
        }
        assertFailsWith<IllegalStateException> {
            DesktopMacServiceAgent.bundledLauncher("/Applications/UkkeraPOS.app/Contents/MacOS/UkkeraPOS", null) { false }
        }
    }

    @Test
    fun macServiceIdentityRequiresOwnerSigningInputs() {
        assertTrue(DesktopMacServiceIdentity.configured(null, "e".repeat(64)).isFailure)
        assertTrue(DesktopMacServiceIdentity.configured("ABCDE12345", null).isFailure)
        assertTrue(DesktopMacServiceIdentity.configured("bad", "e".repeat(64)).isFailure)
        val identity = DesktopMacServiceIdentity.configured("ABCDE12345", "e".repeat(64)).getOrThrow()
        assertEquals("com.ukkera.pos.local.desktop", identity.signingIdentifier)
    }
}
