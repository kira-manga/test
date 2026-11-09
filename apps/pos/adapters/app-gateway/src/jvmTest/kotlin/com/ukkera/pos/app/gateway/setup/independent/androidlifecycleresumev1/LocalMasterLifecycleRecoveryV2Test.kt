package com.ukkera.pos.app.gateway.setup.independent.androidlifecycleresumev1

import com.ukkera.pos.app.gateway.setup.LocalMasterLifecycleIntentRead
import com.ukkera.pos.app.gateway.setup.LocalMasterLifecycleIntentStorage
import com.ukkera.pos.app.gateway.setup.LocalMasterLifecycleIntentStore
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalMasterLifecycleAction
import com.ukkera.pos.security.LocalMasterLifecycleIntent
import com.ukkera.pos.security.LocalMasterLifecycleRequest
import com.ukkera.pos.security.SelfOwnedAllocationCoordinatesV1
import com.ukkera.pos.app.gateway.setup.LocalMasterLifecycleIntentRead.Retained
import com.ukkera.pos.types.DomainId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ALR2-S01..S03 over the real production lifecycle-intent store. The storage is an
 * explicitly controlled port that models the Android namespace states; it is not an actual
 * AtomicFile/fsync/process-death or native-master result.
 */
internal class LocalMasterLifecycleRecoveryV2Test {
    @Test
    fun ordinaryReadNeverPromotesACompleteInterruptedWrite() {
        val coordinates = coordinates()
        val request = request()
        val intent = LocalMasterLifecycleIntent.create(coordinates, request)
        val expectedBytes = intent.encode()
        val storage = ControlledRecoveryStorage(interrupted = expectedBytes)
        val store = LocalMasterLifecycleIntentStore(storage)

        assertEquals(LocalMasterLifecycleIntentRead.Unresolved, store.read(),
            "ALR2-S01 ordinary read of a .new remnant")
        assertEquals(listOf("read"), storage.events, "ALR2-S01 ordinary read events")
        assertNull(storage.canonical, "ALR2-S01 ordinary read promoted the remnant")
        assertContentEquals(expectedBytes, storage.interrupted, "ALR2-S01 ordinary read changed .new bytes")
        assertEquals(0, storage.commits, "ALR2-S01 ordinary read committed recovery")

        val recovered = assertIs<Retained>(store.settleInterruptedWrite(),
            "ALR2-S01 explicit complete .new recovery")
        assertEquals(intent.request.requestId, recovered.intent.request.requestId,
            "ALR2-S01 recovered request ID is the original")
        assertEquals(intent.request.action, recovered.intent.request.action,
            "ALR2-S01 recovered action is the original")
        assertTrue(recovered.intent.matches(coordinates, request),
            "ALR2-S01 recovered full-twelve binding")
        assertContentEquals(expectedBytes, storage.canonical, "ALR2-S01 promoted exact bytes")
        assertNull(storage.interrupted, "ALR2-S01 recovery left the .new remnant")
        assertEquals(1, storage.commits, "ALR2-S01 one exact recovery commit")
        assertEquals(0, storage.writes, "ALR2-S01 recovery minted no fresh retain/write")
        assertEquals(0, storage.clears, "ALR2-S01 recovery cleared nothing")

        val reopened = assertIs<Retained>(LocalMasterLifecycleIntentStore(storage).read(),
            "ALR2-S01 canonical read after recovery")
        assertEquals(recovered.intent.request.requestId, reopened.intent.request.requestId,
            "ALR2-S01 same request identity after reopen")
        assertTrue(reopened.intent.matches(coordinates, request),
            "ALR2-S01 exact binding after reopen")
    }

    @Test
    fun partialForeignAndUnreadableRemnantsStayUnresolvedAndPreserved() {
        val valid = LocalMasterLifecycleIntent.create(coordinates(), request()).encode()
        val cases = linkedMapOf<String, ControlledRecoveryStorage>(
            "partial" to ControlledRecoveryStorage(interrupted = valid.copyOf(valid.size / 2)),
            "foreign profile" to ControlledRecoveryStorage(
                interrupted = valid.copyOf().also { it[0] = 'X'.code.toByte() }),
            "tampered request digest" to ControlledRecoveryStorage(
                interrupted = replaceLine(valid, 4, "f".repeat(64))),
            "truncated canonical" to ControlledRecoveryStorage(interrupted = valid.copyOf(valid.size - 1)),
            "unreadable" to ControlledRecoveryStorage(interrupted = valid).also {
                it.interruptedReadFailure = true
            },
            "backup or mixed names" to ControlledRecoveryStorage(foreignOrBackupNames = true),
            "canonical plus new" to ControlledRecoveryStorage(canonical = valid, interrupted = valid),
        )
        for ((name, storage) in cases) {
            val originalInterrupted = storage.interrupted?.copyOf()
            val store = LocalMasterLifecycleIntentStore(storage)
            assertEquals(LocalMasterLifecycleIntentRead.Unresolved, store.read(),
                "ALR2-S02 $name ordinary read")
            assertEquals(LocalMasterLifecycleIntentRead.Unresolved, store.settleInterruptedWrite(),
                "ALR2-S02 $name explicit recovery")
            assertEquals(0, storage.commits, "ALR2-S02 $name recovery committed")
            assertEquals(0, storage.writes, "ALR2-S02 $name recovery wrote a fresh record")
            assertEquals(0, storage.clears, "ALR2-S02 $name recovery cleared evidence")
            if (name == "canonical plus new") {
                assertContentEquals(valid, storage.canonical, "ALR2-S02 $name canonical evidence preserved")
            } else {
                assertNull(storage.canonical, "ALR2-S02 $name invented a canonical record")
            }
            if (originalInterrupted != null) {
                assertContentEquals(originalInterrupted, storage.interrupted,
                    "ALR2-S02 $name .new bytes preserved")
            }
        }
    }

    @Test
    fun freshRetainCannotAdoptOrReplaceAnUnresolvedInterruptedNamespace() {
        val coordinates = coordinates()
        val request = request()
        val interrupted = LocalMasterLifecycleIntent.create(coordinates, request).encode()
        val storage = ControlledRecoveryStorage(interrupted = interrupted)
        val store = LocalMasterLifecycleIntentStore(storage)
        val fresh = LocalMasterLifecycleRequest(changed(2014), LocalMasterLifecycleAction.RESUME)

        assertFailsWith<Throwable>("ALR2-S01 a fresh retain must not adopt an unresolved .new namespace") {
            store.retain(LocalMasterLifecycleIntent.create(coordinates, fresh))
        }
        assertNull(storage.canonical, "ALR2-S01 fresh retain created a canonical record")
        assertContentEquals(interrupted, storage.interrupted, "ALR2-S01 fresh retain changed .new bytes")
        assertEquals(1, storage.writes, "ALR2-S01 the ordinary write path was reached and refused")
        assertEquals(0, storage.clears, "ALR2-S01 fresh retain cleared evidence")

        val recovered = assertIs<Retained>(store.settleInterruptedWrite(),
            "ALR2-S01 explicit recovery after failed fresh retain")
        assertEquals(request.requestId, recovered.intent.request.requestId,
            "ALR2-S01 original request reused, not the fresh ID")
    }

    @Test
    fun failedRecoveryCommitKeepsTheRemnantOriginalAndRetriesTheSameRequest() {
        val coordinates = coordinates()
        val request = request()
        val intent = LocalMasterLifecycleIntent.create(coordinates, request)
        val bytes = intent.encode()
        val storage = ControlledRecoveryStorage(interrupted = bytes).also { it.commitFailure = true }
        val store = LocalMasterLifecycleIntentStore(storage)

        assertEquals(LocalMasterLifecycleIntentRead.Unresolved, store.settleInterruptedWrite(),
            "ALR2-S01 failed recovery commit is unresolved")
        assertEquals(1, storage.commits, "ALR2-S01 recovery commit was attempted")
        assertEquals(0, storage.writes, "ALR2-S01 failed recovery wrote a fresh record")
        assertNull(storage.canonical, "ALR2-S01 failed recovery promoted a canonical file")
        assertContentEquals(bytes, storage.interrupted, "ALR2-S01 failed recovery preserved .new bytes")

        storage.commitFailure = false
        val recovered = assertIs<Retained>(store.settleInterruptedWrite(),
            "ALR2-S01 retry after controlled commit failure")
        assertEquals(request.requestId, recovered.intent.request.requestId,
            "ALR2-S01 retry reused the exact original request")
        assertTrue(recovered.intent.matches(coordinates, request),
            "ALR2-S01 retry exact original binding")
        assertEquals(0, storage.writes, "ALR2-S01 retry did not mint a fresh record")
    }

    @Test
    fun settleFallsBackToOrdinaryReadWithoutPromotingAnything() {
        val coordinates = coordinates()
        val request = request()
        val valid = LocalMasterLifecycleIntent.create(coordinates, request).encode()
        val canonical = ControlledRecoveryStorage(canonical = valid)
        val retained = assertIs<Retained>(LocalMasterLifecycleIntentStore(canonical).settleInterruptedWrite(),
            "ALR2-S02 canonical-only settlement")
        assertTrue(retained.intent.matches(coordinates, request), "ALR2-S02 canonical-only binding")
        assertEquals(0, canonical.commits, "ALR2-S02 canonical-only recovery commit")
        assertEquals(0, canonical.writes, "ALR2-S02 canonical-only write")

        val absent = ControlledRecoveryStorage()
        assertEquals(LocalMasterLifecycleIntentRead.Absent,
            LocalMasterLifecycleIntentStore(absent).settleInterruptedWrite(),
            "ALR2-S02 verified absence")
        assertEquals(0, absent.commits, "ALR2-S02 absence recovery commit")
    }

    @Test
    fun lifecycleAndStopBindingNamespacesRemainSeparateExactStores() {
        val coordinates = coordinates()
        val stop = LocalMasterLifecycleIntent.create(coordinates, request())
        val resume = LocalMasterLifecycleIntent.create(coordinates,
            LocalMasterLifecycleRequest(changed(2022), LocalMasterLifecycleAction.RESUME))
        val lifecycleStorage = ControlledRecoveryStorage()
        val stopStorage = ControlledRecoveryStorage()
        val lifecycle = LocalMasterLifecycleIntentStore(lifecycleStorage)
        val binding = LocalMasterLifecycleIntentStore(stopStorage)

        lifecycle.retain(resume)
        binding.retain(stop)
        assertTrue(assertIs<Retained>(lifecycle.read()).intent.matches(coordinates, resume.request),
            "ALR2-S03 lifecycle namespace retained its own request")
        assertTrue(assertIs<Retained>(binding.read()).intent.matches(coordinates, stop.request),
            "ALR2-S03 STOP binding namespace retained its own request")
        assertEquals(LocalMasterLifecycleAction.RESUME, assertIs<Retained>(lifecycle.read()).intent.request.action,
            "ALR2-S03 lifecycle action unaffected by binding")
        assertEquals(LocalMasterLifecycleAction.STOP, assertIs<Retained>(binding.read()).intent.request.action,
            "ALR2-S03 binding action unaffected by lifecycle")

        binding.clear()
        assertEquals(LocalMasterLifecycleIntentRead.Absent, binding.read(), "ALR2-S03 binding cleared")
        assertTrue(assertIs<Retained>(lifecycle.read()).intent.matches(coordinates, resume.request),
            "ALR2-S03 binding clear did not clear lifecycle identity")

        assertFailsWith<Throwable>("ALR2-S03 a different STOP binding cannot overwrite active bytes") {
            binding.retain(stop)
            binding.retain(LocalMasterLifecycleIntent.create(coordinates,
                LocalMasterLifecycleRequest(changed(2023), LocalMasterLifecycleAction.STOP)))
        }
        binding.clear()
        val replacement = LocalMasterLifecycleIntent.create(coordinates,
            LocalMasterLifecycleRequest(changed(2023), LocalMasterLifecycleAction.STOP))
        binding.retain(replacement)
        assertTrue(assertIs<Retained>(binding.read()).intent.matches(coordinates, replacement.request),
            "ALR2-S03 explicit clear then exact STOP replacement")
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
        lines[index] = value
        return lines.joinToString("\n").encodeToByteArray()
    }
}

/**
 * Controlled namespace model. Ordinary read fails closed for `.new`/foreign remnants; only
 * the explicit recovery read may return the complete `.new` payload. It is not an actual
 * Android AtomicFile implementation.
 */
private class ControlledRecoveryStorage(
    var canonical: ByteArray? = null,
    var interrupted: ByteArray? = null,
    var foreignOrBackupNames: Boolean = false,
) : LocalMasterLifecycleIntentStorage {
    var readFailure: Boolean = false
    var interruptedReadFailure: Boolean = false
    var commitFailure: Boolean = false
    var clearFailure: Boolean = false
    var writes: Int = 0
    var commits: Int = 0
    var clears: Int = 0
    val events = mutableListOf<String>()

    override fun read(): ByteArray? {
        events += "read"
        if (readFailure) throw IllegalStateException("controlled intent read failure")
        val retained = canonical
        if (retained != null) {
            if (interrupted != null || foreignOrBackupNames) {
                throw IllegalStateException("controlled namespace has an unresolved remnant")
            }
            return retained.copyOf()
        }
        if (interrupted != null || foreignOrBackupNames) {
            throw IllegalStateException("controlled namespace has an interrupted write")
        }
        return null
    }

    override fun readInterruptedWrite(): ByteArray? {
        events += "readInterruptedWrite"
        if (interruptedReadFailure) throw IllegalStateException("controlled interrupted read failure")
        if (foreignOrBackupNames) throw IllegalStateException("controlled .bak/foreign remnant")
        if (canonical != null && interrupted != null) {
            throw IllegalStateException("controlled canonical-plus-new namespace")
        }
        return interrupted?.copyOf()
    }

    override fun commitRecovered(bytes: ByteArray) {
        events += "commitRecovered"
        commits++
        if (commitFailure) throw IllegalStateException("controlled recovery commit failure")
        check(canonical == null && foreignOrBackupNames.not()) {
            "controlled recovery target is not empty"
        }
        val retained = checkNotNull(interrupted) { "controlled recovery has no interrupted bytes" }
        check(retained.contentEquals(bytes)) { "controlled recovery bytes differ" }
        canonical = bytes.copyOf()
        interrupted = null
    }

    override fun replaceAndForce(bytes: ByteArray) {
        events += "write"
        writes++
        if (interrupted != null || foreignOrBackupNames) {
            throw IllegalStateException("controlled namespace has an interrupted write")
        }
        val existing = canonical
        if (existing != null && !existing.contentEquals(bytes)) {
            throw IllegalStateException("controlled storage retains different bytes")
        }
        canonical = bytes.copyOf()
    }

    override fun clearAndForce() {
        events += "clear"
        clears++
        if (clearFailure) throw IllegalStateException("controlled clear failure")
        check(interrupted == null && foreignOrBackupNames.not()) {
            "controlled clear of an unresolved namespace"
        }
        canonical = null
    }
}
