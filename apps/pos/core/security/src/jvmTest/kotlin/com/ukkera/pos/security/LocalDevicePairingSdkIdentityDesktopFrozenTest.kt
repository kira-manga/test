package com.ukkera.pos.security

import com.ukkera.pos.security.independent.sdkassociationv1.FrozenNativeSdkAssociationV1
import com.ukkera.pos.security.independent.sdkassociationv1.captureOriginalIdentity
import com.ukkera.pos.security.independent.sdkassociationv1.text
import dev.p2pkit.core.AppId
import dev.p2pkit.core.PeerFingerprint
import dev.p2pkit.core.PeerId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Frozen expectations for the additive desktop SDK identity variant, authored BEFORE the
 * implementation on 12 November 2026. NOT independent (same author, owner instruction).
 * SDK text values come from the independent frozen association fixture.
 */
class LocalDevicePairingSdkIdentityDesktopFrozenTest {
    private val windows = SelfOwnedWindowsPackagedAppIdentityV2("Ukkera.POS.Test_n9kkbga0qmnm6",
        "Ukkera.POS.Test_n9kkbga0qmnm6!UkkeraPOS")

    private fun desktop(installed: SelfOwnedInstalledIdentityTargetV2): LocalDevicePairingSdkIdentityV2 {
        val android = captureOriginalIdentity()
        val o = FrozenNativeSdkAssociationV1.original
        return LocalDevicePairingSdkIdentityV2.captureDesktop(android.originalAcquisitionId, android.originalPreparationId,
            android.target, installed, AppId(o.text("sdk_app_id")), PeerId(o.text("sdk_peer_id")),
            PeerFingerprint(o.text("sdk_fingerprint")), o.text("sdk_pairing_qr"))
    }

    @Test
    fun desktopIdentityRoundTripsAndCarriesInstalledIdentity() {
        val value = desktop(windows)
        val decoded = LocalDevicePairingSdkIdentityV2.decode(value.copyWireBytes())
        assertContentEquals(value.copyWireBytes(), decoded.copyWireBytes())
        assertEquals(windows, decoded.installedApplication)
        assertFailsWith<IllegalStateException> { decoded.nativeApplication }
    }

    @Test
    fun desktopAndAndroidVariantsAreDistinct() {
        val android = captureOriginalIdentity()
        assertNull(android.installedApplication)
        assertNotEquals(android.descriptorDigest, desktop(windows).descriptorDigest)
    }

    @Test
    fun onlyDesktopUiIdentitiesAreAccepted() {
        assertFailsWith<IllegalArgumentException> {
            desktop(SelfOwnedWindowsServiceIdentityV2("S-1-5-80-1-2-3-4-5", "S-1-5-19"))
        }
    }
}
