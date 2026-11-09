package com.ukkera.pos.database.coldhistory

import com.ukkera.pos.database.RuntimeHistoryColdMemory
import com.ukkera.pos.database.RuntimeHistoryColdScanMemory
import com.ukkera.pos.database.RuntimeHistoryFormatFailureV2
import com.ukkera.pos.database.RuntimeHistoryFormatRejectedV2
import com.ukkera.pos.database.RuntimeHistoryReadItem
import com.ukkera.pos.database.RuntimeHistoryReadMetadata
import com.ukkera.pos.database.RuntimeHistoryRowCodecV2
import com.ukkera.pos.database.RuntimeHistoryRowViewV2
import com.ukkera.pos.database.RuntimeHistoryTableV2
import com.ukkera.pos.security.NativeHistoryPhysicalCellV2
import com.ukkera.pos.security.NativeHistoryPhysicalRowV2
import com.ukkera.pos.security.NativeHistoryReadPassV2
import com.ukkera.pos.security.NativeHistoryRowKindV2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Supporting inert ledger checks for frozen cold-history-bounded-workspace-v1,
 * CHB-I07/I08 only. These are not native/G513 acceptance, a numeric platform
 * resource qualification, or complete-history integration tests.
 *
 * Pass labels on synthetic CHUNK-shaped copied DATA exercise accounting only;
 * they do not bind a command/approval pass's native schema or an accepting read.
 * The inert ledger constructor opens no native session. Its close below means
 * arithmetic release, not proved native close, erasure or confidential teardown.
 *
 * UNBOUND: actual cancellation/storage faults, native custody/authority/lifecycle,
 * recursive scanner ownership, measured managed/native/queue/handle/disk bounds,
 * large security-history rolling behavior and owner/central acceptance.
 */
class RuntimeHistoryColdScanMemorySupportingTest {
    @Test
    fun supportingStreamedApprovalAuditAndFragmentInputsStopGrowingAfterEquivalentFirstInput() {
        val passes = listOf(
            NativeHistoryReadPassV2.APPROVAL_RELATIONS to NativeHistoryReadPassV2.APPROVAL_OPERATIONS,
            NativeHistoryReadPassV2.COMMANDS to NativeHistoryReadPassV2.COMMAND_AUDIT,
            NativeHistoryReadPassV2.COMMANDS to NativeHistoryReadPassV2.COMMAND_FRAGMENT,
        )
        for ((root, streamed) in passes) {
            val memory = RuntimeHistoryColdMemory()
            val ledger = RuntimeHistoryColdScanMemory(memory)
            try {
                withInput(root) { ledger.beforeInput(it) }
                ledger.beforeTraversalAllocation(ANNOUNCED_BYTES)
                val beforeFirst = memory.retainedBytes
                withInput(streamed) { ledger.beforeInput(it) }
                val afterFirst = memory.retainedBytes
                assertTrue(afterFirst > beforeFirst, "The current input must still be accounted for")
                // Sequential equal-sized DATA, not a retained list or a native history rung.
                for (rowId in 2L..STREAMED_CANDIDATE_COUNT.toLong()) {
                    withInput(streamed, rowId) { ledger.beforeInput(it) }
                    assertBudget(memory, afterFirst)
                }
            } finally { ledger.close() }
            assertBudget(memory, 0L)
        }
    }

    @Test
    fun supportingRootAndAnnouncedAllocationsShareTheUnchangedGlobalByteBudget() {
        assertEquals(GLOBAL_BYTE_LIMIT, RuntimeHistoryColdMemory.MAX_BYTES)
        val memory = RuntimeHistoryColdMemory()
        memory.reserve(OTHER_CALLER_BYTES)
        val ledger = RuntimeHistoryColdScanMemory(memory)
        var fillerBytes = 0L
        try {
            val beforeRoot = memory.retainedBytes
            withInput(NativeHistoryReadPassV2.LINEAGE) { ledger.beforeInput(it) }
            val afterRoot = memory.retainedBytes
            assertTrue(afterRoot > beforeRoot)
            ledger.beforeTraversalAllocation(ANNOUNCED_BYTES)
            // The existing doubled group allowance is accounting, not a heap measurement.
            assertBudget(memory, afterRoot + 2L * ANNOUNCED_BYTES)
            val remaining = GLOBAL_BYTE_LIMIT - memory.retainedBytes
            memory.reserve(remaining)
            fillerBytes = remaining
            assertBudget(memory, GLOBAL_BYTE_LIMIT)
            assertCapacityRefused(memory) { memory.reserve(1L) }
            assertCapacityRefused(memory) { ledger.retain(1L) }
            assertCapacityRefused(memory) { ledger.beforeTraversalAllocation(1L) }
        } finally {
            ledger.close()
            memory.release(fillerBytes)
        }
        assertBudget(memory, OTHER_CALLER_BYTES)
        memory.release(OTHER_CALLER_BYTES)
        assertBudget(memory, 0L)
    }

    @Test
    fun supportingLedgerCloseReleasesOnlyItsAccountingAndIsIdempotent() {
        val memory = RuntimeHistoryColdMemory()
        memory.reserve(OTHER_CALLER_BYTES)
        memory.reserveOwnedCommand(2)
        val ledger = RuntimeHistoryColdScanMemory(memory)
        try {
            ledger.retain(17L)
            // Transfer only an already-reserved inert event allowance; no event proof.
            memory.reserve(29L)
            ledger.retainEventReservation(29L)
            withInput(NativeHistoryReadPassV2.COMMANDS) { ledger.beforeInput(it) }
            ledger.beforeTraversalAllocation(ANNOUNCED_BYTES)
            withInput(NativeHistoryReadPassV2.COMMAND_FRAGMENT) { ledger.beforeInput(it) }
            assertTrue(memory.retainedBytes > OTHER_CALLER_BYTES + 17L + 29L)
            ledger.close()
            assertBudget(memory, OTHER_CALLER_BYTES, 1, 2)
            memory.reserve(5L)
            ledger.close()
            assertBudget(memory, OTHER_CALLER_BYTES + 5L, 1, 2)
            memory.release(5L)
        } finally { ledger.close() }
        assertBudget(memory, OTHER_CALLER_BYTES, 1, 2)
        memory.releaseOwnedCommand(2)
        memory.release(OTHER_CALLER_BYTES)
        assertBudget(memory, 0L)
    }

    @Test
    fun supportingCapacityRefusalLeavesAllCountersUnchangedAndAllowsAccountingReuse() {
        val memory = RuntimeHistoryColdMemory()
        memory.reserve(OTHER_CALLER_BYTES)
        memory.reserveOwnedCommand(2)
        val ledger = RuntimeHistoryColdScanMemory(memory)
        var fillerBytes = 0L
        try {
            ledger.retain(17L)
            val beforeRoot = memory.retainedBytes
            withInput(NativeHistoryReadPassV2.COMMANDS) { ledger.beforeInput(it) }
            val rootCharge = memory.retainedBytes - beforeRoot
            ledger.beforeTraversalAllocation(ANNOUNCED_BYTES)
            val beforeRefusal = memory.retainedBytes
            val remaining = GLOBAL_BYTE_LIMIT - beforeRefusal
            memory.reserve(remaining)
            fillerBytes = remaining
            assertBudget(memory, GLOBAL_BYTE_LIMIT, 1, 2)
            assertCapacityRefused(memory) { ledger.retain(1L) }
            assertCapacityRefused(memory) { ledger.beforeTraversalAllocation(1L) }
            withInput(NativeHistoryReadPassV2.COMMAND_AUDIT) { item ->
                assertCapacityRefused(memory) { ledger.beforeInput(item) }
            }
            assertCapacityRefused(memory) { ledger.beforeTraversalAllocation(GLOBAL_BYTE_LIMIT) }
            assertCapacityRefused(memory) { ledger.retain(Long.MAX_VALUE) }
            memory.release(fillerBytes)
            fillerBytes = 0L
            assertBudget(memory, beforeRefusal, 1, 2)
            // Successful retries must not inherit a failed retained/group/high-water update.
            ledger.retain(1L)
            ledger.beforeTraversalAllocation(1L)
            assertBudget(memory, beforeRefusal + 3L, 1, 2)
            withInput(NativeHistoryReadPassV2.COMMAND_AUDIT) { ledger.beforeInput(it) }
            assertBudget(memory, beforeRefusal + 3L + rootCharge, 1, 2)
            withInput(NativeHistoryReadPassV2.COMMAND_AUDIT, 2L) { ledger.beforeInput(it) }
            assertBudget(memory, beforeRefusal + 3L + rootCharge, 1, 2)
        } finally {
            ledger.close()
            memory.release(fillerBytes)
        }
        assertBudget(memory, OTHER_CALLER_BYTES, 1, 2)
        memory.releaseOwnedCommand(2)
        memory.release(OTHER_CALLER_BYTES)
        assertBudget(memory, 0L)
    }

    private fun assertBudget(memory: RuntimeHistoryColdMemory, bytes: Long, commands: Int = 0, versions: Int = 0) {
        assertEquals(bytes, memory.retainedBytes)
        assertEquals(commands, memory.retainedCommands)
        assertEquals(versions, memory.retainedVersions)
    }

    private fun assertCapacityRefused(memory: RuntimeHistoryColdMemory, transition: () -> Unit) {
        val bytes = memory.retainedBytes
        val commands = memory.retainedCommands
        val versions = memory.retainedVersions
        assertEquals(RuntimeHistoryFormatFailureV2.CAPACITY,
            assertFailsWith<RuntimeHistoryFormatRejectedV2> { transition() }.failure)
        assertBudget(memory, bytes, commands, versions)
    }

    private fun withInput(pass: NativeHistoryReadPassV2, rowId: Long = 1L,
        consume: (RuntimeHistoryReadItem.Row) -> Unit) {
        val physical = NativeHistoryPhysicalRowV2(RuntimeHistoryTableV2.CHUNK, rowId,
            listOf(NativeHistoryPhysicalCellV2.Integer(1L), NativeHistoryPhysicalCellV2.Integer(0L),
                NativeHistoryPhysicalCellV2.Text("0".repeat(64).encodeToByteArray()),
                NativeHistoryPhysicalCellV2.Blob(byteArrayOf(0, 1, 2))))
        try {
            // Bind equal encoded DATA sizes only; encoding grants no native authority.
            val encoded = RuntimeHistoryRowCodecV2.encode(physical)
            try {
                val metadata = RuntimeHistoryReadMetadata(pass, NativeHistoryRowKindV2.PHYSICAL,
                    RuntimeHistoryTableV2.CHUNK, rowId, emptyList())
                consume(RuntimeHistoryReadItem.Row(metadata, RuntimeHistoryRowViewV2(physical), encoded.size.toLong()))
            } finally { encoded.fill(0) }
        } finally { physical.clear() }
    }

    private companion object {
        // Existing source-bound byte ceiling, never a qualified platform resource profile.
        const val GLOBAL_BYTE_LIMIT = 64L * 1024 * 1024
        const val OTHER_CALLER_BYTES = 31L
        const val ANNOUNCED_BYTES = 4096L
        const val STREAMED_CANDIDATE_COUNT = 10_000
    }
}
