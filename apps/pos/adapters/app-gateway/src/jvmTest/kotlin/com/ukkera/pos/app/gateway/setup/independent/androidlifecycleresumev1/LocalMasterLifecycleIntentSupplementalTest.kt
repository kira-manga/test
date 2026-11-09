package com.ukkera.pos.app.gateway.setup.independent.androidlifecycleresumev1

import com.ukkera.pos.app.gateway.setup.LocalMasterLifecycleIntentRead
import com.ukkera.pos.app.gateway.setup.LocalMasterLifecycleIntentStorage
import com.ukkera.pos.app.gateway.setup.LocalMasterLifecycleIntentStore
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalMasterLifecycleAction
import com.ukkera.pos.security.LocalMasterLifecycleIntent
import com.ukkera.pos.security.LocalMasterLifecycleRequest
import com.ukkera.pos.security.SelfOwnedAllocationCoordinatesV1
import com.ukkera.pos.security.lifecycleCoordinatesDigest
import com.ukkera.pos.security.lifecycleRequestDigest
import com.ukkera.pos.types.DomainId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Supplemental ALR-S01..S04 expectations over the real production lifecycle-intent codec and
 * common store. Storage is an explicitly controlled port; this is not an Android file, fsync,
 * process-death or native-master result.
 */
internal class LocalMasterLifecycleIntentSupplementalTest {
    @Test
    fun frozenDigestsAndCanonicalBytesBindActionRequestAndEveryCoordinate() {
        val coordinates = coordinates()
        val request = request()
        assertEquals("e7d8d13b8c25557502d84e6d8dfead64f51ab6725154461cbe7bc0c98e03a174",
            lifecycleCoordinatesDigest(coordinates).value, "ALR-S01 coordinates digest")
        assertEquals("39cff46dc003253e5b2d73c68ebb102e17ee0265f769876dad889a11508c2121",
            lifecycleRequestDigest(coordinates, request).value, "ALR-S01 STOP request digest")

        val intent = LocalMasterLifecycleIntent.create(coordinates, request)
        assertEquals(EXPECTED_INTENT_TEXT, intent.encode().decodeToString(), "ALR-S01 canonical intent bytes")
        val decoded = LocalMasterLifecycleIntent.decode(intent.encode())
        assertTrue(decoded.matches(coordinates, request), "ALR-S01 round trip binding")
        assertEquals(request.requestId, decoded.request.requestId, "ALR-S01 request ID")
        assertEquals(LocalMasterLifecycleAction.STOP, decoded.request.action, "ALR-S01 action")
        assertEquals(coordinates.installationId, decoded.installationId, "ALR-S01 installation")

        val changedRequestId = LocalMasterLifecycleRequest(changed(2014), LocalMasterLifecycleAction.STOP)
        assertFalse(decoded.matches(coordinates, changedRequestId), "ALR-S01 request ID delta")
        assertFalse(decoded.matches(coordinates, LocalMasterLifecycleRequest(request.requestId,
            LocalMasterLifecycleAction.RESUME)), "ALR-S01 action delta")
        for (index in 0..11) {
            val changed = coordinates.withField(index, changed(40 + index))
            assertFalse(decoded.matches(changed, request), "ALR-S01 coordinate delta $index")
            assertFalse(lifecycleCoordinatesDigest(changed) == lifecycleCoordinatesDigest(coordinates),
                "ALR-S01 coordinate digest delta $index")
            assertFalse(lifecycleRequestDigest(changed, request) == lifecycleRequestDigest(coordinates, request),
                "ALR-S01 request digest delta $index")
        }
    }

    @Test
    fun malformedForeignPartialAndTamperedIntentBytesAreRefused() {
        val coordinates = coordinates()
        val request = request()
        val valid = LocalMasterLifecycleIntent.create(coordinates, request).encode()
        val cases = linkedMapOf<String, ByteArray>(
            "empty" to ByteArray(0),
            "oversize" to ByteArray(LocalMasterLifecycleIntent.MAXIMUM_BYTES + 1) { 'a'.code.toByte() },
            "foreign profile" to valid.copyOf().also { it[0] = 'X'.code.toByte() },
            "missing final LF" to valid.copyOf(valid.size - 1),
            "extra final LF" to (valid + '\n'.code.toByte()),
            "truncated prefix" to valid.copyOf(valid.size / 2),
            "unknown action" to replaceLine(valid, 1, "PAUSE"),
            "non-canonical request" to replaceLine(valid, 2, "not-a-uuid"),
            "duplicate coordinate" to replaceLine(valid, 6, line(valid, 5)),
            "tampered coordinates digest" to replaceLine(valid, 3, "a".repeat(64)),
            "tampered request digest" to replaceLine(valid, 4, "b".repeat(64)),
            "extra line" to (valid + "extra\n".encodeToByteArray()),
        )
        for ((name, bytes) in cases) {
            assertFailsWith<Throwable>("ALR-S02 $name") { LocalMasterLifecycleIntent.decode(bytes) }
        }
        for ((name, bytes) in cases) {
            val read = LocalMasterLifecycleIntentStore(ControlledIntentStorage(bytes)).read()
            assertEquals(LocalMasterLifecycleIntentRead.Unresolved, read, "ALR-S02 store $name")
        }
        val throwing = ControlledIntentStorage(null).also { it.readFailure = true }
        assertEquals(LocalMasterLifecycleIntentRead.Unresolved, LocalMasterLifecycleIntentStore(throwing).read(),
            "ALR-S02 throwing read is not absent")
        val absent = LocalMasterLifecycleIntentStore(ControlledIntentStorage(null)).read()
        assertEquals(LocalMasterLifecycleIntentRead.Absent, absent, "ALR-S02 verified absence")
    }

    @Test
    fun retainForcesExactBytesAndVerifiesReadbackBeforeReturning() {
        val storage = ControlledIntentStorage(null)
        val store = LocalMasterLifecycleIntentStore(storage)
        val intent = LocalMasterLifecycleIntent.create(coordinates(), request())
        val returned = store.retain(intent)
        assertTrue(returned.matches(coordinates(), request()), "ALR-S03 retained binding")
        assertEquals(EXPECTED_INTENT_TEXT, storage.bytes?.decodeToString(), "ALR-S03 exact forced bytes")
        assertEquals(1, storage.writes, "ALR-S03 one write")
        assertEquals(listOf("write", "read"), storage.events, "ALR-S03 readback follows write")
        val read = assertIs<LocalMasterLifecycleIntentRead.Retained>(store.read())
        assertTrue(read.intent.matches(coordinates(), request()), "ALR-S03 stored binding")
    }

    @Test
    fun newStoreReusesSameRetainedIdentityAfterControlledProcessLoss() {
        val storage = ControlledIntentStorage(null)
        val first = LocalMasterLifecycleIntentStore(storage)
        val retained = first.retain(LocalMasterLifecycleIntent.create(coordinates(), request()))
        val bytes = storage.bytes!!.copyOf()
        val reopened = LocalMasterLifecycleIntentStore(storage).read()
        val actual = assertIs<LocalMasterLifecycleIntentRead.Retained>(reopened).intent
        assertEquals(retained.request.requestId, actual.request.requestId, "ALR-S04 same request ID")
        assertEquals(retained.request.action, actual.request.action, "ALR-S04 same action")
        assertEquals(retained.coordinatesDigest, actual.coordinatesDigest, "ALR-S04 same coordinates digest")
        assertEquals(retained.requestDigest, actual.requestDigest, "ALR-S04 same request digest")
        assertTrue(actual.matches(coordinates(), request()), "ALR-S04 exact binding")
        assertTrue(bytes.contentEquals(storage.bytes), "ALR-S04 no rewrite on reopen")
    }

    @Test
    fun differentRetainedIntentIsNeverOverwritten() {
        val foreign = LocalMasterLifecycleIntent.create(coordinates(),
            LocalMasterLifecycleRequest(changed(2014), LocalMasterLifecycleAction.RESUME))
        val storage = ControlledIntentStorage(foreign.encode())
        val store = LocalMasterLifecycleIntentStore(storage)
        val original = storage.bytes!!.copyOf()
        assertFailsWith<Throwable>("ALR-S03 conflicting retain") {
            store.retain(LocalMasterLifecycleIntent.create(coordinates(), request()))
        }
        assertTrue(original.contentEquals(storage.bytes), "ALR-S03 conflicting retain changed stored bytes")
        assertTrue(assertIs<LocalMasterLifecycleIntentRead.Retained>(store.read()).intent.matches(
            coordinates(), LocalMasterLifecycleRequest(changed(2014), LocalMasterLifecycleAction.RESUME)),
            "ALR-S03 original retained intent survives")
    }

    @Test
    fun failedClearLeavesTheRecordAndPreventsFreshDispatch() {
        val storage = ControlledIntentStorage(null)
        val store = LocalMasterLifecycleIntentStore(storage)
        store.retain(LocalMasterLifecycleIntent.create(coordinates(), request()))
        storage.clearFailure = true
        assertFailsWith<Throwable>("ALR-S04 failed clear") { store.clear() }
        assertTrue(storage.bytes != null, "ALR-S04 failed clear removed bytes")
        assertIs<LocalMasterLifecycleIntentRead.Retained>(store.read(), "ALR-S04 failed clear readback")
    }

    @Test
    fun unresolvedReadThrowsThroughTheCoordinatorFacingApiInsteadOfMinting() {
        val storage = ControlledIntentStorage("foreign".encodeToByteArray())
        val store = LocalMasterLifecycleIntentStore(storage)
        assertEquals(LocalMasterLifecycleIntentRead.Unresolved, store.read(), "ALR-S04 unresolved")
        assertFalse(storage.events.any { it == "write" }, "ALR-S04 unresolved read never writes")
    }

    private fun request() = LocalMasterLifecycleRequest(changed(2013), LocalMasterLifecycleAction.STOP)

    private fun coordinates() = SelfOwnedAllocationCoordinatesV1(
        changed(2001),
        BranchScope(changed(2002), changed(2003), changed(2004)),
        changed(2005), changed(2006), changed(2007), changed(2008), changed(2009), changed(2010), changed(2011), changed(2012),
    )

    private fun changed(value: Int): DomainId =
        DomainId("00000000-0000-4000-8000-${value.toString().padStart(12, '0')}")

    private fun replaceLine(bytes: ByteArray, index: Int, value: String): ByteArray {
        val lines = bytes.decodeToString().split('\n').toMutableList()
        if (index >= lines.size) return bytes
        lines[index] = value
        return lines.joinToString("\n").encodeToByteArray()
    }

    private fun line(bytes: ByteArray, index: Int): String =
        bytes.decodeToString().split('\n')[index]

    private companion object {
        private const val EXPECTED_INTENT_TEXT =
            "ukkera.local-master-lifecycle-intent/v1\n" +
                "STOP\n" +
                "00000000-0000-4000-8000-000000002013\n" +
                "e7d8d13b8c25557502d84e6d8dfead64f51ab6725154461cbe7bc0c98e03a174\n" +
                "39cff46dc003253e5b2d73c68ebb102e17ee0265f769876dad889a11508c2121\n" +
                "00000000-0000-4000-8000-000000002001\n" +
                "00000000-0000-4000-8000-000000002002\n" +
                "00000000-0000-4000-8000-000000002003\n" +
                "00000000-0000-4000-8000-000000002004\n" +
                "00000000-0000-4000-8000-000000002005\n" +
                "00000000-0000-4000-8000-000000002006\n" +
                "00000000-0000-4000-8000-000000002007\n" +
                "00000000-0000-4000-8000-000000002008\n" +
                "00000000-0000-4000-8000-000000002009\n" +
                "00000000-0000-4000-8000-000000002010\n" +
                "00000000-0000-4000-8000-000000002011\n" +
                "00000000-0000-4000-8000-000000002012\n"
    }
}

private fun SelfOwnedAllocationCoordinatesV1.withField(index: Int, value: DomainId): SelfOwnedAllocationCoordinatesV1 {
    val all = listOf(allocationId, scope.organizationId, scope.companyId, scope.branchId, installationId,
        assignmentId, deviceId, storageInstanceId, storageKeyId, pepperKeyId, targetSigningKeyId, firstActorId)
    val changed = all.toMutableList().also { it[index] = value }
    return SelfOwnedAllocationCoordinatesV1(
        changed[0], BranchScope(changed[1], changed[2], changed[3]), changed[4], changed[5],
        changed[6], changed[7], changed[8], changed[9], changed[10], changed[11],
    )
}

private class ControlledIntentStorage(initial: ByteArray?) : LocalMasterLifecycleIntentStorage {
    var bytes: ByteArray? = initial?.copyOf()
    var readFailure: Boolean = false
    var clearFailure: Boolean = false
    val events = mutableListOf<String>()
    var writes: Int = 0

    override fun read(): ByteArray? {
        events += "read"
        if (readFailure) throw IllegalStateException("controlled intent read failure")
        return bytes?.copyOf()
    }

    // This predecessor fixture has no interrupted-write state or recovery capability.
    override fun readInterruptedWrite(): ByteArray? =
        throw UnsupportedOperationException("Interrupted-write recovery is outside this fixture")

    override fun commitRecovered(bytes: ByteArray): Unit =
        throw UnsupportedOperationException("Interrupted-write recovery is outside this fixture")

    override fun replaceAndForce(bytes: ByteArray) {
        events += "write"
        writes++
        val existing = this.bytes
        if (existing != null && !existing.contentEquals(bytes)) {
            throw IllegalStateException("controlled storage retains different bytes")
        }
        this.bytes = bytes.copyOf()
    }

    override fun clearAndForce() {
        events += "clear"
        if (clearFailure) throw IllegalStateException("controlled intent clear failure")
        bytes = null
    }
}
