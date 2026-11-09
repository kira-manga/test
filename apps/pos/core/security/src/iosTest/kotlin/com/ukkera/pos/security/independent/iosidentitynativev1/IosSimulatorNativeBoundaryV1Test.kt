package com.ukkera.pos.security.independent.iosidentitynativev1

import com.ukkera.pos.security.IosInstalledApplicationIdentity
import com.ukkera.pos.security.SelfOwnedInstalledIdentityRefusalV2
import com.ukkera.pos.security.SelfOwnedInstalledIdentityResultV2
import com.ukkera.pos.security.SelfOwnedIosActiveAppIdentityV2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSThread

/**
 * Frozen IOSID-13 native negative slice. This test runs only in the iOS test binary; it is not a
 * device-signing proof and cannot establish a positive installed identity. When executed on a
 * simulator it must never yield accepted identity DATA. If the main-thread context observation
 * can run, the simulator must be classified as an unsupported context.
 */
class IosSimulatorNativeBoundaryV1Test {
    @Test
    fun iosid13_simulatorObservationIsNeverAccepted() {
        val expected = SelfOwnedIosActiveAppIdentityV2(
            bundleIdentifier = "com.ukkera.pos.local",
            appIdGroup = "TEAM123456.com.ukkera.pos.local",
        )
        val simulatorSignals = NSProcessInfo.processInfo.environment.keys
            .map { it.toString() }
            .filter { it.startsWith("SIMULATOR_") }

        val captured = IosInstalledApplicationIdentity.captureMarker(expected)
        val capturedRefusal = requireRefusal(captured)
        assertTrue(
            capturedRefusal == SelfOwnedInstalledIdentityRefusalV2.REFUSE_UNSUPPORTED_CONTEXT ||
                capturedRefusal == SelfOwnedInstalledIdentityRefusalV2.REFUSE_NATIVE_OBSERVATION_UNAVAILABLE,
            "simulator observation must be refused as unavailable/unsupported, not accepted",
        )
        if (simulatorSignals.isNotEmpty() && NSThread.isMainThread) {
            assertEquals(
                SelfOwnedInstalledIdentityRefusalV2.REFUSE_UNSUPPORTED_CONTEXT,
                capturedRefusal,
                "simulator context must be classified as unsupported",
            )
        }

        val verified = IosInstalledApplicationIdentity.verifyActiveAppCodeSignature(expected)
        assertTrue(
            verified is SelfOwnedInstalledIdentityResultV2.Refused,
            "simulator must never produce native code-signature evidence",
        )
    }

    private fun requireRefusal(
        result: SelfOwnedInstalledIdentityResultV2<*>,
    ): SelfOwnedInstalledIdentityRefusalV2 {
        assertTrue(result is SelfOwnedInstalledIdentityResultV2.Refused, "expected refusal but was $result")
        return (result as SelfOwnedInstalledIdentityResultV2.Refused).code
    }
}
