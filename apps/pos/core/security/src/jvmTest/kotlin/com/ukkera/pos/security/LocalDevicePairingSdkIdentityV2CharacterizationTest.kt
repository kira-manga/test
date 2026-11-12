package com.ukkera.pos.security

import com.ukkera.pos.security.independent.sdkassociationv1.captureOriginalIdentity
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Characterization of existing Android SDK identity bytes (independent frozen fixture inputs),
 * captured from the unchanged encoder on 12 November 2026 BEFORE the desktop variant was added.
 * Implementer-authored, not an independent oracle.
 */
class LocalDevicePairingSdkIdentityV2CharacterizationTest {
    @Test
    fun androidSdkIdentityBytesUnchanged() {
        assertEquals(EXPECTED, captureOriginalIdentity().descriptorDigest.value)
    }

    private companion object { const val EXPECTED = "407db738216bab739dbe4dfc73824ca57e811c337f43423c6329685777aaeec0" }
}
