package com.ukkera.pos.desktop

import com.ukkera.pos.security.SelfOwnedWindowsPackagedAppIdentityV2
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Frozen expectations, authored before the implementation (12 November 2026).
 * NOT independent: the same author designed these cases and the implementation, by explicit
 * owner instruction. Vector 1 is Microsoft's documented publisher ID; vector 2 was computed by a
 * separate Python implementation of the documented algorithm. The installed OS identity remains
 * the authoritative check on a real Windows runner.
 */
class DesktopWindowsPackageIdentityFrozenTest {
    @Test
    fun publisherIdMatchesMicrosoftDocumentedVector() {
        assertEquals("8wekyb3d8bbwe", DesktopWindowsPackageIdentity.publisherId(
            "CN=Microsoft Corporation, O=Microsoft Corporation, L=Redmond, S=Washington, C=US"))
    }

    @Test
    fun publisherIdMatchesSeparatelyComputedTestPublisher() {
        assertEquals("n9kkbga0qmnm6", DesktopWindowsPackageIdentity.publisherId("CN=Ukkera POS Test Signing"))
    }

    @Test
    fun expectedTestIdentityIsExactAndAcceptedBySharedGrammar() {
        val expected: SelfOwnedWindowsPackagedAppIdentityV2 = DesktopWindowsPackageIdentity.TEST.target()
        assertEquals("Ukkera.POS.Test_n9kkbga0qmnm6", expected.packageFamilyName)
        assertEquals("Ukkera.POS.Test_n9kkbga0qmnm6!UkkeraPOS", expected.applicationUserModelId)
    }

    @Test
    fun emptyOrNonAsciiPublisherIsRefused() {
        assertFailsWith<IllegalArgumentException> { DesktopWindowsPackageIdentity.publisherId("") }
        assertFailsWith<IllegalArgumentException> { DesktopWindowsPackageIdentity.publisherId("CN=\u0000") }
    }

    @Test
    fun manifestTemplateDeclaresTheSameIdentity() {
        val template = File(System.getProperty("ukkera.desktop.packaging.dir"), "windows/AppxManifest.xml.template")
        assertTrue(template.isFile, "missing $template")
        val text = template.readText()
        val identity = DesktopWindowsPackageIdentity.TEST
        assertTrue("Name=\"${identity.packageName}\"" in text)
        assertTrue("Publisher=\"${identity.publisher}\"" in text)
        assertTrue("<Application Id=\"${identity.applicationId}\"" in text)
        assertTrue("runFullTrust" in text)
    }
}
