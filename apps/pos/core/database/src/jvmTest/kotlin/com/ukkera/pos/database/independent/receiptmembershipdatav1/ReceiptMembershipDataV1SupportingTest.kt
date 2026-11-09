package com.ukkera.pos.database.independent.receiptmembershipdatav1

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.RuntimeHistoryColdMemory
import com.ukkera.pos.database.RuntimeHistoryFormatRejectedV2
import com.ukkera.pos.database.RuntimeRetailCustomerReceiptEventLocator
import com.ukkera.pos.database.RuntimeRetailCustomerReceiptMembershipBlockStore
import com.ukkera.pos.database.RuntimeRetailCustomerReceiptMembershipTree
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Partial bindings of frozen receipt-membership-data-v1, RMD-D01..D04. Exercise the
 * production DATA tree only; the controlled block store has no native encryption,
 * session, seed, root import, admission or Room authority. Locators bind independently
 * supplied comparison payloads, not canonical production receipt rows or originals.
 * Head/quantity/time/actor validation, actual source completeness, COMMITTED publication,
 * concurrency/revocation, restart and all business effects remain unbound here.
 */
class ReceiptMembershipDataV1SupportingTest {
    @Test
    fun rmdD01CompleteTargetLocatorsSurviveEveryFrozenGrowthAndInputOrder() = runTest {
        val expected = (1..3).map(::event).toSet()
        for (growth in listOf(0, 65, 1_000)) {
            for (order in listOf(listOf(1, 2, 3), listOf(3, 1, 2))) {
                withTree { tree, _ ->
                    tree.append(SALE, event(order.first()))
                    for (j in 1..growth) tree.append(unrelatedSale(j), unrelatedEvent(j))
                    for (i in order.drop(1)) tree.append(SALE, event(i))
                    assertEquals(3L + growth, tree.count)
                    assertMembers(tree, SALE, expected)
                    if (growth > 0) {
                        assertMembers(tree, unrelatedSale(1), setOf(unrelatedEvent(1)))
                        assertMembers(tree, unrelatedSale(growth), setOf(unrelatedEvent(growth)))
                    }
                    assertMembers(tree, EMPTY_SALE, emptySet())
                }
            }
        }
    }

    @Test
    fun rmdD02SameEventCannotIncrementItsSaleOrBeAssignedToAnotherSale() = runTest {
        for (destination in listOf(SALE, unrelatedSale(1))) {
            withTree { tree, _ ->
                for (i in 1..3) tree.append(SALE, event(i))
                tree.append(unrelatedSale(1), unrelatedEvent(1))
                assertFalse(tree.canAppend(destination, event(1)))
                assertEquals(4L, tree.count)
                assertMembers(tree, SALE, (1..3).map(::event).toSet())
                assertMembers(tree, unrelatedSale(1), setOf(unrelatedEvent(1)))
                assertFailsWith<RuntimeHistoryFormatRejectedV2> { tree.append(destination, event(1)) }
                assertEquals(4L, tree.count)
                // A failed strict DATA mutation denies this tree, not a shortened result.
                assertFails { tree.select(SALE) }
            }
        }
    }

    @Test
    fun rmdD02ChangedObservationFingerprintCannotReplaceAnEarlierEvent() = runTest {
        withTree { tree, _ ->
            for (i in 1..3) tree.append(SALE, event(i))
            val changed = event(2, receivedAdjustmentMillis = 100L)
            assertTrue(changed.digest != event(2).digest)
            assertFalse(tree.canAppend(SALE, changed))
            assertEquals(3L, tree.count)
            assertMembers(tree, SALE, (1..3).map(::event).toSet())
        }
    }

    @Test
    fun rmdD03SixtyFourMembersRemainExactWhenTheNextCandidateIsRefused() = runTest {
        withTree { tree, _ ->
            for (i in 1..64) {
                assertTrue(tree.canAppend(SALE, event(i)))
                tree.append(SALE, event(i))
            }
            val expected = (1..64).map(::event).toSet()
            assertEquals(64L, tree.count)
            assertMembers(tree, SALE, expected)
            assertFalse(tree.canAppend(SALE, event(65)))
            assertEquals(64L, tree.count)
            assertMembers(tree, SALE, expected)
            assertFailsWith<RuntimeHistoryFormatRejectedV2> { tree.append(SALE, event(65)) }
            assertEquals(64L, tree.count)
            assertFails { tree.select(SALE) }
        }
    }

    @Test
    fun rmdD04MissingBlockCannotReturnAnEmptyOrShortenedMembership() = runTest {
        assertUnavailableBlock(ControlledBlocks.Fault.MISSING)
    }

    @Test
    fun rmdD04CorruptPartialAndSubstitutedBlocksCannotReturnMembership() = runTest {
        for (fault in listOf(ControlledBlocks.Fault.CORRUPT, ControlledBlocks.Fault.PARTIAL,
            ControlledBlocks.Fault.SUBSTITUTED)) assertUnavailableBlock(fault)
    }

    @Test
    fun rmdD04AnEmptyDataTreeReturnsOnlyAnEmptyLocatorLoan() = runTest {
        withTree { tree, _ ->
            assertEquals(0L, tree.count)
            assertMembers(tree, EMPTY_SALE, emptySet())
            // No genuine original, Head or NotRecorded result is created by this DATA API.
            tree.append(SALE, event(1))
            assertMembers(tree, EMPTY_SALE, emptySet())
            assertMembers(tree, SALE, setOf(event(1)))
        }
    }

    private suspend fun assertUnavailableBlock(fault: ControlledBlocks.Fault) {
        withTree { tree, blocks ->
            for (i in 1..3) tree.append(SALE, event(i))
            tree.append(unrelatedSale(1), unrelatedEvent(1))
            assertMembers(tree, SALE, (1..3).map(::event).toSet())
            blocks.fault = fault
            assertFails { tree.select(SALE) }
            // A fixture lookup/assertion failure is not a production corruption refusal.
            assertEquals(fault, blocks.servedFault)
            assertEquals(4L, tree.count)
            blocks.fault = null
            // Restoring DATA access cannot reactivate a failed live tree.
            assertFails { tree.select(SALE) }
        }
    }

    private suspend fun assertMembers(tree: RuntimeRetailCustomerReceiptMembershipTree,
        sale: DomainId, expected: Set<RuntimeRetailCustomerReceiptEventLocator>) {
        val selected = tree.select(sale)
        try {
            assertEquals(expected.size, selected.entries.size)
            assertEquals(expected, selected.entries.toSet())
        } finally { selected.close() }
    }

    private suspend fun withTree(use: suspend (RuntimeRetailCustomerReceiptMembershipTree, ControlledBlocks) -> Unit) {
        val memory = RuntimeHistoryColdMemory()
        val blocks = ControlledBlocks()
        val tree = RuntimeRetailCustomerReceiptMembershipTree.create(blocks, memory)
        try { use(tree, blocks) }
        finally {
            tree.closeData()
            blocks.clear()
            // Arithmetic ownership cleanup only, not a measured platform-memory claim.
            assertEquals(0L, memory.retainedBytes)
        }
    }

    /** Inert blocks only. No native facade, source, original, permission or seed is built. */
    private class ControlledBlocks : RuntimeRetailCustomerReceiptMembershipBlockStore {
        enum class Fault { MISSING, CORRUPT, PARTIAL, SUBSTITUTED }
        var fault: Fault? = null
        var servedFault: Fault? = null
            private set
        private var next = 1L
        private val bytes = linkedMapOf<Long, ByteArray>()

        override suspend fun append(bytes: ByteArray): ByteArray {
            val coordinate = next++
            this.bytes[coordinate] = bytes.copyOf()
            return ByteBuffer.allocate(32).putLong(coordinate).array()
        }

        override suspend fun read(blockId: ByteArray): ByteArray {
            check(blockId.size == 32 && blockId.drop(8).all { it == 0.toByte() })
            val coordinate = ByteBuffer.wrap(blockId).long
            val original = bytes[coordinate] ?: throw IOException("Missing comparison DATA block")
            val selectedFault = fault
            val response = when (selectedFault) {
                null -> original.copyOf()
                Fault.MISSING -> {
                    servedFault = selectedFault
                    throw IOException("Missing comparison DATA block")
                }
                Fault.CORRUPT -> original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
                Fault.PARTIAL -> original.copyOf(original.size - 1)
                Fault.SUBSTITUTED -> bytes.entries.first {
                    it.key != coordinate && !it.value.contentEquals(original)
                }.value.copyOf()
            }
            servedFault = selectedFault
            return response
        }

        override suspend fun preflight() = Unit
        fun clear() { bytes.values.forEach { it.fill(0) }; bytes.clear() }
    }

    private companion object {
        val SALE = id(1, 1)
        val EMPTY_SALE = id(1, 2)
        val COLLECTOR = id(5, 1)
        val PORTION = id(6, 1)
        val FINALIZE = Instant.parse("2026-08-01T08:00:00.000Z")

        fun id(family: Int, ordinal: Int): DomainId = DomainId(
            "${family}0000000-0000-4000-8000-${ordinal.toString(16).padStart(12, '0')}")
        fun unrelatedSale(j: Int): DomainId = id(3, j)
        fun unrelatedEvent(j: Int): RuntimeRetailCustomerReceiptEventLocator = locator(id(4, j),
            "comparison-v1;U$j;Q$j;A=1;${unrelatedSale(j).value}")
        fun event(i: Int): RuntimeRetailCustomerReceiptEventLocator = event(i, 0L)
        fun event(i: Int, receivedAdjustmentMillis: Long): RuntimeRetailCustomerReceiptEventLocator {
            val received = FINALIZE.plusSeconds(i.toLong()).plusMillis(receivedAdjustmentMillis)
            val recorded = FINALIZE.plusSeconds(i.toLong()).plusMillis(100L)
            return locator(id(2, i), "comparison-v1;${SALE.value};E$i;${PORTION.value}=1;${COLLECTOR.value};" +
                "$received;$recorded;Africa/Cairo;CUSTOMER_RECEIVED_AT_COUNTER;${i - 1};$i")
        }
        fun locator(id: DomainId, comparison: String): RuntimeRetailCustomerReceiptEventLocator {
            val bytes = comparison.toByteArray(Charsets.UTF_8)
            val digest = try { MessageDigest.getInstance("SHA-256").digest(bytes) } finally { bytes.fill(0) }
            val value = try { PayloadDigest(digest.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }) }
            finally { digest.fill(0) }
            return RuntimeRetailCustomerReceiptEventLocator(AggregateKey("sales.customer-receipt.event", id),
                Revision(1), "sales.customer-receipt.event/v1", value)
        }
    }
}
