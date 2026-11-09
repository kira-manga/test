package com.ukkera.pos.app.gateway.setup.independent.freshdevicepairingworkflow

import com.ukkera.pos.app.gateway.setup.LocalFreshDevicePairingIntents
import com.ukkera.pos.app.gateway.setup.LocalDeviceAdmissionIntents
import com.ukkera.pos.app.gateway.setup.LocalSetupIntentStorage
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalDevicePairingTargetPhase
import com.ukkera.pos.security.LocalDevicePairingTargetReference
import com.ukkera.pos.security.LocalDevicePairingAction
import com.ukkera.pos.security.SelfOwnedAllocationCoordinatesV1
import com.ukkera.pos.types.DomainId
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FDPW-004/008/010 application-journal subsets. Storage is controlled memory;
 * no native key, actual Kit, filesystem durability, picker or full workflow is verified. */
class LocalFreshDevicePairingIntentBoundaryTest {
    @Test fun validTextBoundariesAndDistinctUnicodeAreRetainedExactly() {
        val variants = listOf("x", "x".repeat(255), "é".repeat(127) + "a", "é", "e\u0301")
        for (text in variants) for (nameField in listOf(false, true)) {
            val target = Storage(); val journal = LocalFreshDevicePairingIntents(target, Storage())
            val appId = if (nameField) APP_ID else text
            val name = if (nameField) text else NAME
            val record = journal.beginTarget(INTENT, SCOPE, emptySet(), appId, name)
            assertEquals(appId, record.identity.appId)
            assertEquals(name, record.identity.deviceName)
            assertEquals(record, LocalFreshDevicePairingIntents(target, Storage()).target())
            assertFalse(record.preparationAttempted)
        }
    }

    @Test fun invalidInputIsRejectedBeforeAnyIntentWrite() {
        val texts = listOf("", "   ", "x".repeat(256), "é".repeat(128), "x\u0000y", "x\ny", "x\ry",
            "x\u007fy", "x\u0085y", "x\u061cy", "x\u200ey", "x\u202ey", "x\u2069y", "x\ud800y", "x\udc00y")
        for (text in texts) for (nameField in listOf(false, true)) {
            val storage = Storage(); val journal = LocalFreshDevicePairingIntents(storage, Storage())
            assertFails {
                journal.beginTarget(INTENT, SCOPE, emptySet(), if (nameField) APP_ID else text, if (nameField) text else NAME)
            }
            assertTrue(storage.entries.isEmpty())
            assertNull(journal.target())
        }
    }

    @Test fun capabilityBoundaryRecipesStayExactAndDescriptionsOnly() {
        val valid = listOf(emptySet(), (0..63).map { "c%02d".format(it) }.toSet(), setOf("A"), setOf("."),
            setOf("_"), setOf("-"), setOf("0"), setOf("A".repeat(64)), setOf("stock.Read", "stock.read"))
        for (codes in valid) {
            val storage = Storage(); val journal = LocalFreshDevicePairingIntents(storage, Storage())
            val record = journal.beginTarget(INTENT, SCOPE, codes, APP_ID, NAME)
            assertEquals(codes, record.identity.capabilities)
            assertFalse(record.preparationAttempted)
            assertFalse(record.sdkAttempted)
            assertNull(record.deviceId)
            assertNull(record.responseActionId)
            assertTrue(storage.entries.values.all { it.size <= LocalFreshDevicePairingIntents.MAXIMUM_BYTES })
        }
        val invalid = listOf((0..64).map { "c%02d".format(it) }.toSet(), setOf(""), setOf("A".repeat(65)),
            setOf("stock read"), setOf("stock/read"), setOf("é"), setOf("\u0000"))
        for (codes in invalid) {
            val storage = Storage(); val journal = LocalFreshDevicePairingIntents(storage, Storage())
            assertFails { journal.beginTarget(INTENT, SCOPE, codes, APP_ID, NAME) }
            assertTrue(storage.entries.isEmpty())
        }
    }

    @Test fun missingOrMismatchedAcknowledgementCannotReturnPreparedIntent() {
        for (failure in listOf(Ack.DROP, Ack.CHANGE, Ack.THROW)) {
            val storage = Storage(); val journal = LocalFreshDevicePairingIntents(storage, Storage())
            journal.beginTarget(INTENT, SCOPE, CAPABILITIES, APP_ID, NAME)
            storage.ack = failure
            assertFails { journal.markTargetPreparation(INTENT) }
            assertEquals(1, storage.entries.keys.count { it.endsWith(".intent.v1") })
            if (failure != Ack.CHANGE) assertFalse(requireNotNull(journal.target()).preparationAttempted)
            else assertFails { journal.target() }
        }
    }

    @Test fun retainedPreparationSurvivesRecreationWithoutReplacementOrReplay() {
        val storage = Storage(); val journal = LocalFreshDevicePairingIntents(storage, Storage())
        val begun = journal.beginTarget(INTENT, SCOPE, CAPABILITIES, APP_ID, NAME)
        journal.markTargetPreparation(INTENT)
        val restored = LocalFreshDevicePairingIntents(storage, Storage())
        assertEquals(begun.identity, requireNotNull(restored.target()).identity)
        assertTrue(requireNotNull(restored.target()).preparationAttempted)
        assertFails { restored.markTargetPreparation(INTENT) }
        assertFails { restored.beginTarget(ALTERNATE_INTENT, SCOPE, CAPABILITIES, APP_ID, NAME) }
        assertEquals(2, storage.entries.size)

        val original = LocalDevicePairingTargetReference(PREPARATION, DEVICE, KEY, LocalDevicePairingTargetPhase.KEY_READY)
        val retained = restored.retainTargetReference(INTENT, original)
        assertEquals(PREPARATION, retained.originalPreparationId)
        assertEquals(DEVICE, retained.deviceId)
        assertEquals(KEY, retained.keyId)
        assertEquals(retained, restored.retainTargetReference(INTENT, original))
        assertFails { restored.retainTargetReference(INTENT, original.copy(deviceId = ALTERNATE_DEVICE)) }
        assertEquals(retained, restored.target())
        assertFalse(retained.sdkAttempted)
        assertNull(retained.sdkDigest)
    }

    /** FDPW-004 accepted-mismatched acknowledgement: valid bytes are still the wrong binding. */
    @Test fun validAdapterRewriteCannotAcknowledgeDifferentReviewedInputs() {
        val storage = Storage().also { it.ack = Ack.REWRITE_INPUT }
        val journal = LocalFreshDevicePairingIntents(storage, Storage())
        assertFails { journal.beginTarget(INTENT, SCOPE, CAPABILITIES, APP_ID, NAME) }
        // The fault wrote syntactically valid metadata, so rejection is exact acknowledgement,
        // not merely a checksum/parser failure. This metadata cannot authorize native work.
        val retained = requireNotNull(journal.target())
        assertEquals("G" + NAME.drop(1), retained.identity.deviceName)
        assertFalse(retained.preparationAttempted)
        assertFails { journal.beginTarget(ALTERNATE_INTENT, SCOPE, CAPABILITIES, APP_ID, NAME) }
    }

    /** FDPW-007 installation mismatch, using synthetic typed DATA rather than native custody. */
    @Test fun masterActionMustMatchTheRetainedInstallationNotOnlyScopeAndDevice() {
        val data = FreshDevicePairingWorkflowData()
        val original = data.original()
        val context = LocalDeviceAdmissionIntents.Context(data.installationId, data.scope, data.masterDeviceId)
        val storage = Storage()
        val journal = LocalFreshDevicePairingIntents(Storage(), storage)
        val requestId = original.prepared.requested.request.requestId
        journal.beginMaster(context, requestId, data.identityT)
        val before = journal.markMasterPreparation(context, requestId)
        val record = original.prepared.record
        val coordinates = record.body.origin.coordinates
        val foreignCoordinates = SelfOwnedAllocationCoordinatesV1(coordinates.allocationId, coordinates.scope,
            ALTERNATE_INTENT, coordinates.assignmentId, coordinates.deviceId, coordinates.storageInstanceId,
            coordinates.storageKeyId, coordinates.pepperKeyId, coordinates.targetSigningKeyId, coordinates.firstActorId)
        val foreign = LocalDevicePairingAction(original.prepared.owner, record.copy(body = record.body.copy(
            origin = record.body.origin.copy(coordinates = foreignCoordinates))))
        assertFails { journal.retainMasterAction(context, requestId, foreign) }
        assertEquals(before, journal.master(context, requestId))
        assertTrue(storage.entries.keys.none { it.endsWith(".action.v1") })
        assertEquals(original.prepared.originalActionId,
            journal.retainMasterAction(context, requestId, original.prepared).actionId)
    }

    private enum class Ack { EXACT, DROP, CHANGE, THROW, REWRITE_INPUT }
    private class Storage : LocalSetupIntentStorage {
        val entries = linkedMapOf<String, ByteArray>()
        var ack = Ack.EXACT
        override fun names(): List<String>? = entries.keys.toList().takeIf { it.isNotEmpty() }
        override fun read(name: String): ByteArray = requireNotNull(entries[name]).copyOf()
        override fun writeExclusive(name: String, bytes: ByteArray, allowExactExisting: Boolean) {
            val existing = entries[name]
            if (existing != null) { check(allowExactExisting && existing.contentEquals(bytes)); return }
            when (ack) {
                Ack.EXACT -> entries[name] = bytes.copyOf()
                Ack.DROP -> Unit
                Ack.CHANGE -> entries[name] = bytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
                Ack.THROW -> error("Controlled acknowledgement unavailable")
                Ack.REWRITE_INPUT -> {
                    val fields = bytes.decodeToString().split('\n').dropLast(2).toMutableList()
                    fields[7] = "G" + fields[7].drop(1)
                    val body = fields.joinToString("\n", postfix = "\n").encodeToByteArray()
                    val hash = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
                    val rewritten = body + "sha256:$hash\n".encodeToByteArray()
                    check(rewritten.size == bytes.size)
                    rewritten.copyInto(bytes)
                    entries[name] = bytes.copyOf()
                }
            }
        }
        override fun verify() { check(entries.isNotEmpty()) }
    }
    private companion object {
        fun id(suffix: String): DomainId = DomainId("10000000-0000-4000-8000-0000000000$suffix")
        val SCOPE = BranchScope(id("01"), id("02"), id("03"))
        val DEVICE = id("09"); val KEY = id("10"); val PREPARATION = id("11")
        val INTENT = id("16"); val ALTERNATE_INTENT = id("30"); val ALTERNATE_DEVICE = id("18")
        const val APP_ID = "pos.fixture.local"
        const val NAME = "FDPW private target canary"
        val CAPABILITIES = setOf("stock.read", "orders.write")
    }
}
