package com.ukkera.pos.security

/** Fixed DATA namespaces. A partition never denotes a permission or native source selector. */
public enum class SelfOwnedNativeHistoryDataPartition(internal val tag: Int) {
    TRANSCRIPT(1), SEED(2), EVENT(3), MUTATION(4), VERSION(5), COMMAND(6), OBSERVATION(7), DERIVED(8),
}

/**
 * Disposable, source-owned DATA storage. Only the actual native read session can acquire it.
 * Payload interpretation belongs to its consumer; native sources and proofs cannot be
 * reconstructed by this API. Neither its path nor its SQL connection is exposed.
 */
public class SelfOwnedNativeHistoryWorkspace internal constructor(
    internal val controller: SelfOwnedNativeHistoryWorkspaceController,
    public val original: OriginalNativeHistorySnapshot,
) {
    public suspend fun append(partition: SelfOwnedNativeHistoryDataPartition, key: ByteArray,
        sequence: Long, ordinal: Int, payload: ByteArray): SelfOwnedNativeHistoryDataRecord =
        controller.append(partition, key, sequence, ordinal, payload)

    public suspend fun exact(partition: SelfOwnedNativeHistoryDataPartition, key: ByteArray,
        sequence: Long, ordinal: Int): SelfOwnedNativeHistoryDataRecord? =
        controller.exact(partition, key, sequence, ordinal)

    /** Greatest (sequence, ordinal) for this exact key with sequence strictly below the cut. */
    public suspend fun floor(partition: SelfOwnedNativeHistoryDataPartition, key: ByteArray,
        beforeSequenceExclusive: Long): SelfOwnedNativeHistoryDataRecord? =
        controller.floor(partition, key, beforeSequenceExclusive)

    /** Fixed (sequence, ordinal, keyed-key) order, with a high-water fixed at cursor open. */
    public suspend fun openCursor(partition: SelfOwnedNativeHistoryDataPartition,
        key: ByteArray? = null): SelfOwnedNativeHistoryDataCursor = controller.openCursor(partition, key)

    public suspend fun readPayloadChunk(record: SelfOwnedNativeHistoryDataRecord,
        chunkOrdinal: Int): ByteArray = controller.readPayloadChunk(record, chunkOrdinal)

    /** Reclaim only temporary DERIVED DATA for this exact key. A live matching or wildcard
     * DERIVED cursor refuses deletion; original capture partitions are never deletable. */
    public suspend fun discardDerived(key: ByteArray): Unit = controller.discardDerived(key)

    /** Actual watched resource close and owned scratch removal, not an isOpen observation. */
    public suspend fun close(): Unit = controller.close()

    override fun toString(): String = "SelfOwnedNativeHistoryWorkspaceV1([REDACTED])"

    public companion object {
        public const val MAX_KEY_BYTES: Int = 1024
        public const val MAX_PAYLOAD_BYTES: Int = NativeHistoryPhysicalReadProfileV2.MAX_ROW_ARRAY_BYTES
        public const val MAX_CHUNK_BYTES: Int = 65536
        public const val MAX_PAGE_RECORDS: Int = 64
    }
}

/** Immutable metadata for one authenticated DATA record, never a historical capability. */
public class SelfOwnedNativeHistoryDataRecord internal constructor(
    internal val controller: SelfOwnedNativeHistoryWorkspaceController,
    internal val recordId: Long,
    public val partition: SelfOwnedNativeHistoryDataPartition,
    public val sequence: Long,
    public val ordinal: Int,
    public val payloadBytes: Int,
    internal val keyToken: ByteArray,
    key: ByteArray,
) {
    private val key = key.copyOf()
    private var cleared = false
    public val chunkCount: Int get() = (payloadBytes + SelfOwnedNativeHistoryWorkspace.MAX_CHUNK_BYTES - 1) /
        SelfOwnedNativeHistoryWorkspace.MAX_CHUNK_BYTES
    public fun keyBytes(): ByteArray { check(!cleared); return key.copyOf() }
    /** Clears bounded metadata plaintext when the consumer is finished with this record. */
    public fun clear() { key.fill(0); cleared = true }
    internal fun matchesKey(other: ByteArray): Boolean = !cleared && key.contentEquals(other)
    override fun toString(): String = "SelfOwnedNativeHistoryDataRecordV1([REDACTED])"
}

/** Forward-only cursor over DATA, not over a selectable/reopenable original source. */
public class SelfOwnedNativeHistoryDataCursor internal constructor(
    internal val controller: SelfOwnedNativeHistoryWorkspaceController,
    internal val partition: SelfOwnedNativeHistoryDataPartition,
    internal val highWater: Long,
    internal val keyToken: ByteArray?,
    internal val exactKey: ByteArray?,
) {
    internal var lastSequence: Long = -1
    internal var lastOrdinal: Int = -1
    internal var lastKeyToken: ByteArray? = null
    internal var ended: Boolean = false
    internal var closed: Boolean = false
    /** At most 64 metadata records. Empty means this fixed snapshot reached EOF. */
    public suspend fun nextPage(): List<SelfOwnedNativeHistoryDataRecord> = controller.nextPage(this)
    public suspend fun close(): Unit = controller.closeCursor(this)
    internal fun clear() {
        exactKey?.fill(0); keyToken?.fill(0); lastKeyToken?.fill(0); lastKeyToken = null
    }
    override fun toString(): String = "SelfOwnedNativeHistoryDataCursorV1([REDACTED])"
}

/** Internal actual-resource seam. There is deliberately no accepting default implementation. */
internal interface SelfOwnedNativeHistoryWorkspaceController {
    suspend fun append(partition: SelfOwnedNativeHistoryDataPartition, key: ByteArray,
        sequence: Long, ordinal: Int, payload: ByteArray): SelfOwnedNativeHistoryDataRecord
    suspend fun exact(partition: SelfOwnedNativeHistoryDataPartition, key: ByteArray,
        sequence: Long, ordinal: Int): SelfOwnedNativeHistoryDataRecord?
    suspend fun floor(partition: SelfOwnedNativeHistoryDataPartition, key: ByteArray,
        beforeSequenceExclusive: Long): SelfOwnedNativeHistoryDataRecord?
    suspend fun openCursor(partition: SelfOwnedNativeHistoryDataPartition,
        key: ByteArray?): SelfOwnedNativeHistoryDataCursor
    suspend fun nextPage(cursor: SelfOwnedNativeHistoryDataCursor): List<SelfOwnedNativeHistoryDataRecord>
    suspend fun closeCursor(cursor: SelfOwnedNativeHistoryDataCursor)
    suspend fun readPayloadChunk(record: SelfOwnedNativeHistoryDataRecord, chunkOrdinal: Int): ByteArray
    suspend fun discardDerived(key: ByteArray)
    suspend fun close()
}
