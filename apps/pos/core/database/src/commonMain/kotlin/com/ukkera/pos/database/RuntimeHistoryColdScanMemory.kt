package com.ukkera.pos.database

import com.ukkera.pos.security.*

/** Scanner-owned accounting, shared with every earlier-original replay. Security
 * reducers may retain census/seeds and event snapshots after input loans end. Those
 * reservations therefore live until this scanner is cleared, not until its cursor
 * advances. Ordinary business captures have their own, separately retired owner.
 *
 * Security snapshots are deliberately conservative and cumulative for now. Large
 * security histories can still refuse capacity; this is not their rolling-window
 * implementation or a measurement of platform heap use. Independent LINEAGE,
 * COMMANDS and relationship traversal uses one high-water group, not total history.
 */
internal class RuntimeHistoryColdScanMemory(val memory: RuntimeHistoryColdMemory) {
    private var retained = 0L
    private var traversalGroup = 0L
    private var traversalHighWater = 0L
    private var closed = false

    /** Charge before decoding or copying values which may escape an input loan. The
     * allowance covers decoded strings, collection nodes, descriptors and overlapping
     * scalar snapshots, rather than counting only their encoded byte arrays. */
    fun retain(bytes: Long) {
        historyShapeV2(!closed)
        val next = historyAddV2(retained, bytes, RuntimeHistoryColdMemory.MAX_BYTES)
        memory.reserve(bytes)
        retained = next
    }

    fun beforeInput(item: RuntimeHistoryReadItem) {
        historyShapeV2(!closed)
        val m = item.metadata
        if (item is RuntimeHistoryReadItem.Boundary) return
        val encoded = when (item) {
            is RuntimeHistoryReadItem.Row -> item.encodedBytes
            is RuntimeHistoryReadItem.Plaintext -> item.bytes.size.toLong()
            is RuntimeHistoryReadItem.Boundary -> error("unreachable")
        }
        val allowance = VALUE_OVERHEAD + encoded * DECODED_MULTIPLIER
        when (m.pass) {
            NativeHistoryReadPassV2.CENSUS -> if (m.table in RETAINED_CENSUS) retain(allowance)
            NativeHistoryReadPassV2.EMPLOYEE_ENROLLMENTS,
            NativeHistoryReadPassV2.EMPLOYEE_ENROLLMENT_MUTATIONS,
            NativeHistoryReadPassV2.EMPLOYEE_ENROLLMENT_IMAGE -> retain(allowance)
            // Seed capsules allocate at the seed header, before chunks arrive.
            NativeHistoryReadPassV2.EMPLOYEE_ENROLLMENT_CHUNKS -> Unit
            // A native ACCOUNT seed/reset AFTER can outlive the complete event.
            NativeHistoryReadPassV2.EVENT_MUTATIONS ->
                if (item is RuntimeHistoryReadItem.Plaintext) retain(allowance)
            NativeHistoryReadPassV2.EVENTS, NativeHistoryReadPassV2.EVENT_CHUNKS -> Unit
            else -> {
                if (m.pass in GROUP_STARTS) traversalGroup = 0L
                beforeTraversalAllocation(allowance, retainInGroup = m.pass !in STREAMED_GROUP_INPUTS)
            }
        }
    }

    fun retainSeedCapsule(header: RuntimeHistoryEventHeaderV2) = retain(eventBytes(header))

    /** Also covers buffers allocated from a length announcement before their later
     * chunk rows arrive. Input-row accounting alone cannot cover that allocation. */
    fun beforeTraversalAllocation(bytes: Long, retainInGroup: Boolean = true) {
        historyShapeV2(!closed)
        val next = historyAddV2(traversalGroup, bytes, RuntimeHistoryColdMemory.MAX_BYTES)
        // Keep room for the prior group until the interpreter has actually
        // retired it, including a malformed overlapping group before refusal.
        val overlap = historyAddV2(next, next, RuntimeHistoryColdMemory.MAX_BYTES)
        if (overlap > traversalHighWater) {
            memory.reserve(overlap - traversalHighWater)
            traversalHighWater = overlap
        }
        if (retainInGroup) traversalGroup = next
    }

    /** Transfer already reserved event work to the scanner's retained security state.
     * No second reservation and no period with an uncharged retained snapshot. */
    fun retainEventReservation(bytes: Long) {
        historyShapeV2(!closed)
        retained = historyAddV2(retained, bytes, RuntimeHistoryColdMemory.MAX_BYTES)
    }

    fun close() {
        if (closed) return
        closed = true
        memory.release(retained + traversalHighWater)
        retained = 0L; traversalGroup = 0L; traversalHighWater = 0L
    }

    companion object {
        // A small encoded element may expand into boxed values and several map/list
        // entries. Keep the estimate wider than the scratch byte-buffer multiplier.
        private const val DECODED_MULTIPLIER = 64L
        private const val VALUE_OVERHEAD = 4096L
        // Bounded native-source list copies, fixed scanner tables and reducer headers.
        private const val BASE_BYTES = 512L * 1024
        private val RETAINED_CENSUS = setOf(RuntimeHistoryTableV2.BRANCH, RuntimeHistoryTableV2.HEAD,
            RuntimeHistoryTableV2.ACCOUNT, RuntimeHistoryTableV2.POLICY, RuntimeHistoryTableV2.CONFIGURATION,
            RuntimeHistoryTableV2.ADMISSION, RuntimeHistoryTableV2.THROTTLE, RuntimeHistoryTableV2.AUTH_EVENT)
        private val GROUP_STARTS = setOf(NativeHistoryReadPassV2.LINEAGE, NativeHistoryReadPassV2.COMMANDS,
            NativeHistoryReadPassV2.STOCK_AGGREGATES, NativeHistoryReadPassV2.STOCK_REVERSE,
            NativeHistoryReadPassV2.ACCOUNT_RELATIONS, NativeHistoryReadPassV2.ACCESS_RELATIONS,
            NativeHistoryReadPassV2.ARCHIVE_RELATIONS, NativeHistoryReadPassV2.APPROVAL_RELATIONS)
        // These exhaustive candidates/chunks are consumed and disposed, not retained
        // in a growing collection. Command normalized/result/frame and job metadata
        // have their fixed allowance before CommandScan construction.
        private val STREAMED_GROUP_INPUTS = setOf(NativeHistoryReadPassV2.LINEAGE_EVENT_CHUNKS,
            NativeHistoryReadPassV2.LINEAGE_IMAGE, NativeHistoryReadPassV2.LINEAGE_CURRENT,
            NativeHistoryReadPassV2.COMMAND_AUDIT, NativeHistoryReadPassV2.COMMAND_OUTBOX,
            NativeHistoryReadPassV2.COMMAND_FRAGMENT, NativeHistoryReadPassV2.STOCK_PROJECTION,
            NativeHistoryReadPassV2.STOCK_REVERSE_AGGREGATE, NativeHistoryReadPassV2.ACCOUNT_POLICY,
            NativeHistoryReadPassV2.ACCOUNT_CONFIGURATION, NativeHistoryReadPassV2.ACCOUNT_THROTTLES,
            NativeHistoryReadPassV2.ACCESS_ACCOUNTS, NativeHistoryReadPassV2.ACCESS_ADMISSIONS,
            NativeHistoryReadPassV2.ACCESS_CONFIGURATIONS, NativeHistoryReadPassV2.ARCHIVE_ACCESS,
            NativeHistoryReadPassV2.APPROVAL_OPERATIONS)

        fun eventBytes(header: RuntimeHistoryEventHeaderV2): Long =
            VALUE_OVERHEAD + DECODED_MULTIPLIER *
                (header.intentBytes.toLong() + header.dependencyBytes + header.factBytes) +
                header.changeCount.toLong() * (4L * RuntimeHistoryProfileV2.MAX_KEY_BYTES + 1024L) +
                // Approval/serial actor-facts snapshots can copy both bounded session
                // maps even when their capsule only names a single access issuance.
                2L * 256 * 1024

        fun open(session: SelfOwnedNativeHistoryReadSession,
            memory: RuntimeHistoryColdMemory): RuntimeHistoryColdScanMemory {
            val result = RuntimeHistoryColdScanMemory(memory)
            return try {
                result.retain(BASE_BYTES)
                // Native source objects are borrowed from this same session; only
                // the scanner's Entry/map/flag copies are added here, before creation.
                for (source in session.employeeEnrollments)
                    result.retain(4096L + 1024L * source.evidence.images.size)
                for (source in session.deviceAdmissions)
                    result.retain(4096L + 1024L * source.evidence.images.size)
                for (source in session.employeePolicyActivations)
                    result.retain(4096L + 1024L * source.evidence.images.size)
                result.retain(1024L * session.businessPublicationObservations.size)
                result
            } catch (failure: Throwable) { result.close(); throw failure }
        }
    }
}
