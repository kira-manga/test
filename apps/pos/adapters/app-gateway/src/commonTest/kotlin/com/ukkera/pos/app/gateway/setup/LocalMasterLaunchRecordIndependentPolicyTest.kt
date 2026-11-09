package com.ukkera.pos.app.gateway.setup

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Wiring of unchanged LCR-004 Variant C and ALCR-027 stopped-role intent only.
 *
 * Fake storage models retention across common objects in one process. This is
 * not native authority, process-restart, fsync, power-loss or acceptance proof.
 * The full frozen cases remain unverified.
 */
public class LocalMasterLaunchRecordIndependentPolicyTest {
    @Test
    public fun lcr004_explicitStopSurvivesSameStorageReopen_commonPolicyOnly() {
        val storage = FakeOsPrivateLaunchStorage()
        val original = LocalMasterLaunchRecord(storage)

        original.retainStop()

        val reopened = LocalMasterLaunchRecord(storage)
        assertEquals(
            LocalMasterLaunchRecord.Read.Stopped,
            reopened.read(),
            "LCR-004 Variant C / ALCR-027: intentional stop remains stopped on reopen; common policy only.",
        )
    }

    private class FakeOsPrivateLaunchStorage : LocalMasterLaunchStorage {
        private var retained: ByteArray? = null

        override fun read(): ByteArray? = retained?.copyOf()

        override fun replaceAndForce(bytes: ByteArray): Unit {
            // No real OS write/force occurs; preserve only the supplied bytes.
            retained = bytes.copyOf()
        }
    }
}
