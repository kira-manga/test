package com.ukkera.pos.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Frozen expectations, authored before the implementation (12 November 2026).
 * NOT independent (same author, explicit owner instruction). Vector 1 is the well-known
 * TrustedInstaller service SID; vector 2 was computed by a separate Python implementation of the
 * documented rule. A real Windows service token remains the authoritative observation.
 */
class DesktopWindowsServiceIdentityFrozenTest {
    @Test
    fun serviceSidMatchesWellKnownTrustedInstaller() {
        assertEquals("S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464",
            DesktopWindowsServiceIdentity.serviceSid("TrustedInstaller"))
    }

    @Test
    fun serviceSidIsCaseInsensitiveAndMatchesTestServiceVector() {
        val expected = "S-1-5-80-2515362173-3519557629-3421387735-3989192330-3799524828"
        assertEquals(expected, DesktopWindowsServiceIdentity.serviceSid("UkkeraPOSTestMaster"))
        assertEquals(expected, DesktopWindowsServiceIdentity.serviceSid("ukkeraposTESTmaster"))
    }

    @Test
    fun invalidServiceNamesAreRefused() {
        assertFailsWith<IllegalArgumentException> { DesktopWindowsServiceIdentity.serviceSid("") }
        assertFailsWith<IllegalArgumentException> { DesktopWindowsServiceIdentity.serviceSid("a/b") }
        assertFailsWith<IllegalArgumentException> { DesktopWindowsServiceIdentity.serviceSid("x".repeat(257)) }
    }

    @Test
    fun expectedPackagedServiceIdentityUsesLocalServiceAccount() {
        val target = DesktopWindowsServiceIdentity.TEST.target()
        assertEquals("Ukkera.POS.Test_n9kkbga0qmnm6", target.packageFamilyName)
        assertEquals("Ukkera.POS.Test_n9kkbga0qmnm6!UkkeraPOS", target.applicationUserModelId)
        assertEquals("S-1-5-80-2515362173-3519557629-3421387735-3989192330-3799524828", target.serviceSid)
        assertEquals("S-1-5-19", target.serviceAccountSid)
    }

    @Test
    fun manifestDeclaresThePackagedService() {
        val text = File(System.getProperty("ukkera.desktop.packaging.dir"), "windows/AppxManifest.xml.template").readText()
        assertTrue("Category=\"windows.service\"" in text)
        assertTrue("Name=\"UkkeraPOSTestMaster\"" in text)
        assertTrue("StartAccount=\"localService\"" in text)
        assertTrue("Arguments=\"--service\"" in text)
        assertTrue("\"packagedServices\"" in text)
    }
}
