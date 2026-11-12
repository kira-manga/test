package com.ukkera.pos.app.gateway

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Frozen characterization of the paired-device actor allocation ledger format, authored on
 * 12 November 2026 BEFORE the ledger moved from Android-only code into the shared JCA source set.
 * Expected identifiers, lengths and SHA-256 digests were rendered by a separate script from the
 * byte layout (length-prefixed UTF-8 fields, big-endian integers), not by running this code.
 * NOT independent (same author, owner instruction). Android persisted bytes must not change.
 */
class PairedActorAllocationFrozenTest {
    private fun id(n: Int) = DomainId("00000000-0000-4000-8000-" + n.toString().padStart(12, '0'))
    private val binding = "0123456789abcdef".repeat(4)
    private val origin = PairedActorAllocationOrigin(BranchScope(id(11), id(12), id(13)), id(14), binding,
        reservedIds = listOf(id(11), id(12), id(13), id(14), id(15), id(16)))
    private val ledgerJournal = DomainId("4987d816-df10-38b9-b54e-79c6a205af50")
    private val ledgerKey = DomainId("bafad834-0a52-566d-541b-9c40932bd770")
    private val first = PairedDeviceActorAllocationRecord(id(21), id(22), 1,
        DomainId("9d40fba0-b62a-3f7f-40fb-740f58f1975a"), DomainId("ddd53293-7c59-c3aa-a9af-0f87cf3b3182"),
        "1140ba2da1af290de3480dc253c830252be6426f54ce03d3f98281ca497383ee")
    private val second = PairedDeviceActorAllocationRecord(id(23), id(24), 2,
        DomainId("81af161f-d9f0-117e-0b21-42d7fd36e9e9"), DomainId("4c39e741-b5db-1aa5-885e-7e6d6df07365"),
        "34047ea7e020ea31243407b05485983167445e59f30f8350b1976ac42dfcde54")

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun ledgerIdentifiersDeriveFromTheDeviceBindingOnly() {
        assertEquals(ledgerJournal, pairedActorAllocationJournalId(binding, "journal"))
        assertEquals(ledgerKey, pairedActorAllocationJournalId(binding, "key"))
    }

    @Test
    fun actorRecordsDeriveTheirJournalKeyAndBinding() {
        assertEquals(first, pairedActorRecord(origin, id(21), id(22), 1))
        assertEquals(second, pairedActorRecord(origin, id(23), id(24), 2))
    }

    @Test
    fun emptyAndTwoRecordLedgersEncodeToTheFrozenBytes() {
        val empty = encodePairedActorAllocationState(PairedDeviceActorAllocationState(0, emptyList()), origin, ledgerJournal, ledgerKey)
        assertEquals(373, empty.size)
        assertEquals("28d74b43e577e90f56807fc189b65bb6f41cf1677b032ca28666e2b188cb7e46", sha(empty))
        val two = encodePairedActorAllocationState(PairedDeviceActorAllocationState(2, listOf(first, second)), origin, ledgerJournal, ledgerKey)
        assertEquals(837, two.size)
        assertEquals("c07fb9e807648e23e07565ae6ef9053962d9174adace8c6a9284504b916accfe", sha(two))
    }

    @Test
    fun decodingReturnsTheExactStateAndRefusesTrailingOrForeignBytes() {
        val state = PairedDeviceActorAllocationState(2, listOf(first, second))
        val bytes = encodePairedActorAllocationState(state, origin, ledgerJournal, ledgerKey)
        assertEquals(state, decodePairedActorAllocationState(bytes, origin, ledgerJournal, ledgerKey))
        assertFailsWith<Exception> { decodePairedActorAllocationState(bytes + 0, origin, ledgerJournal, ledgerKey) }
        val foreign = PairedActorAllocationOrigin(origin.scope, origin.deviceId, "f".repeat(64), origin.reservedIds)
        assertFailsWith<Exception> { decodePairedActorAllocationState(bytes, foreign, ledgerJournal, ledgerKey) }
    }

    @Test
    fun invalidHistoriesAreRefused() {
        fun encode(state: PairedDeviceActorAllocationState) =
            encodePairedActorAllocationState(state, origin, ledgerJournal, ledgerKey)
        assertFailsWith<Exception> { encode(PairedDeviceActorAllocationState(1, listOf(first, second))) } // revision gap
        assertFailsWith<Exception> { encode(PairedDeviceActorAllocationState(1, listOf(second))) } // sequence gap
        assertFailsWith<Exception> { encode(PairedDeviceActorAllocationState(2, listOf(first, first.copy(sequence = 2)))) }
        assertFailsWith<Exception> { encode(PairedDeviceActorAllocationState(1, listOf(first.copy(bindingCommitment = "0".repeat(64))))) }
        // An allocation may not reuse a reserved device/scope identifier.
        assertFailsWith<Exception> { pairedActorRecord(origin, id(21), id(14), 1).let { encode(PairedDeviceActorAllocationState(1, listOf(it))) } }
        assertFailsWith<Exception> { encodePairedActorAllocationState(PairedDeviceActorAllocationState(0, emptyList()), origin, ledgerKey, ledgerJournal) }
    }
}
