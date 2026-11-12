package com.ukkera.pos.security

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Frozen expectations for the desktop Client target's immutable snapshot store and boot ledger,
 * authored BEFORE the implementation on 12 November 2026. NOT independent (same author, owner
 * instruction). Native owner/file-ID facts are platform observations outside this store.
 */
class DesktopTargetStoreFrozenTest {
    private val root: Path = Files.createTempDirectory("desktop-target-store-test")
    private val dir: Path = root.resolve(SelfOwnedCompositionNames.devicePairingTargetDirectory(null))
    private val opened = mutableListOf<DesktopTargetSnapshotStore>()
    private fun create() = DesktopTargetSnapshotStore.create(dir).also { opened += it }
    private fun open() = DesktopTargetSnapshotStore.openExisting(dir).also { opened += it }

    @AfterTest fun cleanup() { opened.forEach { runCatching { it.close() } }; root.toFile().deleteRecursively() }

    @Test
    fun createIsFirstOnlyAndOpenIsExistingOnly() {
        assertFails { open() }
        create().close()
        assertFails { create() }
        open()
    }

    @Test
    fun snapshotsAreWriteOnceAndBounded() {
        val store = create()
        store.forceNew("identity.v2", byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), store.read("identity.v2", 16))
        assertFails { store.forceNew("identity.v2", byteArrayOf(9)) }
        assertFails { store.read("identity.v2", 2) }
        assertNull(store.read("absent.v2", 16))
        assertEquals(setOf("identity.v2"), store.names())
        store.close()
        assertContentEquals(byteArrayOf(1, 2, 3), open().read("identity.v2", 16))
    }

    @Test
    fun invalidNamesAreRefused() {
        val store = create()
        listOf("", "../x", "a/b", "A.v2", "x.pending", "owner.lock", "a".repeat(97)).forEach { name ->
            assertFails { store.forceNew(name, byteArrayOf(1)) }
        }
    }

    @Test
    fun leftoverPendingWriteIsPreservedAndNeverRetried() {
        create().close()
        Files.write(dir.resolve("key-ready.v2.pending"), byteArrayOf(7))
        val store = open()
        assertEquals(setOf("key-ready.v2"), store.pendingNames())
        assertFails { store.forceNew("key-ready.v2", byteArrayOf(8)) }
        assertTrue(Files.exists(dir.resolve("key-ready.v2.pending")))
    }

    @Test
    fun secondOwnerIsRefusedWhileFirstHoldsTheLock() {
        create()
        assertFails { open() }
    }

    @Test
    fun closedStoreRefusesUse() {
        val store = create()
        store.close()
        assertFails { store.forceNew("identity.v2", byteArrayOf(1)) }
        assertFails { store.read("identity.v2", 4) }
    }

    @Test
    fun bootLedgerAssignsOrdinalsOnlyForNewSessions() {
        val store = create()
        val a = "6f1c2a3b-0000-4000-8000-0000000000aa"
        val b = "7f1c2a3b-0000-4000-8000-0000000000bb"
        assertEquals(TargetBoot.Desktop(0, a), DesktopTargetBootLedger.observe(store, a))
        assertEquals(TargetBoot.Desktop(0, a), DesktopTargetBootLedger.observe(store, a))
        assertEquals(TargetBoot.Desktop(1, b), DesktopTargetBootLedger.observe(store, b))
        // A previously recorded older session reappearing is not a new boot; refuse.
        assertFails { DesktopTargetBootLedger.observe(store, a) }
        assertEquals(TargetBoot.Desktop(1, b), DesktopTargetBootLedger.current(store))
    }

    @Test
    fun bootLedgerRefusesInvalidSessionAndGaps() {
        val store = create()
        assertFails { DesktopTargetBootLedger.observe(store, "not-a-uuid") }
        assertNull(DesktopTargetBootLedger.current(store))
        DesktopTargetBootLedger.observe(store, "6f1c2a3b-0000-4000-8000-0000000000aa")
        store.forceNew(DesktopTargetBootLedger.entryName(2), byteArrayOf(1))
        assertFails { DesktopTargetBootLedger.current(store) }
    }
}
