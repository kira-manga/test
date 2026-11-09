package com.ukkera.pos.database

import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.SelfOwnedNativeHistoryReadSession
import com.ukkera.pos.types.OperationId

internal enum class RuntimeHistoryColdRowOrder { AFTER_ROW_ID, CANONICAL_KEY }

/** One scoped hydrated value; advancing or closing releases the preceding value. */
internal interface RuntimeHistoryColdCursor<T> {
    suspend fun next(): T?
    suspend fun close()
}

/** Inert, independently matched native DATA. It cannot issue historical permissions. */
internal interface RuntimeHistoryColdBusinessStore {
    val session: SelfOwnedNativeHistoryReadSession
    fun reserveOwnedBytes(bytes: Long)
    fun releaseOwnedBytes(bytes: Long)
    fun reserveOwnedCommand(versions: Int)
    fun releaseOwnedCommand(versions: Int)
    val tipDelta: Long
    fun currentCount(table: RuntimeHistoryTableV2): Long
    suspend fun <T> withSeedBranch(use: suspend (RuntimeHistoryRowViewV2) -> T): T
    suspend fun <T> withVersion(sequence: Long, ordinal: Int,
        use: suspend (RuntimeBusinessHistoryJoin.Mutation) -> T): T
    suspend fun <T> withBefore(table: RuntimeHistoryTableV2, canonicalKey: ByteArray,
        beforeSequenceExclusive: Long, use: suspend (RuntimeBusinessHistoryJoin.Mutation?) -> T): T
    suspend fun openBeforeRows(table: RuntimeHistoryTableV2, beforeSequenceExclusive: Long,
        order: RuntimeHistoryColdRowOrder = RuntimeHistoryColdRowOrder.AFTER_ROW_ID):
        RuntimeHistoryColdCursor<RuntimeBusinessHistoryJoin.Mutation>
    suspend fun openEarlierVersions(table: RuntimeHistoryTableV2, canonicalKey: ByteArray?,
        beforeSequenceExclusive: Long): RuntimeHistoryColdCursor<RuntimeBusinessHistoryJoin.Mutation>
    suspend fun openStockRangeBefore(query: StockQueryV2, beforeSequenceExclusive: Long):
        RuntimeHistoryColdCursor<StockIndexRecordV2>
    suspend fun openEarlierOperations(beforeSequenceExclusive: Long): RuntimeHistoryColdCursor<OperationId>
    suspend fun markBusinessJoined(header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        producer: StreamedProducerIdentityV2)
    suspend fun requireBusinessClosure(expectedCommands: Long, expectedMutations: Long)
}

/** Actual rejoined objects remain registered with their original owner for this loan. */
internal interface RuntimeHistoryColdOriginalLoan {
    val session: SelfOwnedNativeHistoryReadSession
    val capture: RuntimeBusinessHistoryJoin.CommandCapture
    val original: RetainedOriginalV2
    suspend fun close()
}

/** Replays genuine seed, authorization and original/producer checks in the same session.
 * A stored joined marker or decoded capture cannot supply this capability. */
internal interface RuntimeHistoryColdOriginalRejoin {
    suspend fun byOperation(requestingJoin: RuntimeBusinessHistoryJoin,
        before: RuntimeBusinessHistoryJoin.CommandCapture, operationId: OperationId): RuntimeHistoryColdOriginalLoan?
    suspend fun bySequence(requestingJoin: RuntimeBusinessHistoryJoin,
        before: RuntimeBusinessHistoryJoin.CommandCapture, sequence: Long): RuntimeHistoryColdOriginalLoan?
    /** Only the same owner's closed current-FOOD census may borrow through its actual
     * native tip. This is not a fabricated later command or an admission capability. */
    suspend fun byCurrentFoodSequence(requestingJoin: RuntimeBusinessHistoryJoin,
        sequence: Long): RuntimeHistoryColdOriginalLoan?
}
