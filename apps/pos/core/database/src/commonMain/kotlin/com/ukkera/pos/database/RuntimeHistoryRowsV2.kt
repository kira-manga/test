package com.ukkera.pos.database

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.ukkera.pos.security.NativeHistoryPhysicalCellV2

/** V2 physical history records consumed by the real raw scanner; Room activation is held. */
internal enum class RuntimeHistoryParentKindV2(val tag: Int) {
    ORIGINAL_ALLOCATION(1), RECORDED_EVENT(2);
    companion object {
        fun fromTag(tag: Int): RuntimeHistoryParentKindV2 = entries.singleOrNull { it.tag == tag }
            ?: throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED)
    }
}
internal enum class RuntimeHistoryMutationKindV2(val tag: Int) {
    INSERT(1), UPDATE(2), DELETE(3);
    companion object {
        fun fromTag(tag: Int): RuntimeHistoryMutationKindV2 = entries.singleOrNull { it.tag == tag }
            ?: throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED)
    }
}
internal enum class RuntimeHistoryAfterKindV2(val tag: Int) { IMAGE(1), RETAINED_IMMUTABLE(2), TOMBSTONE(3) }

internal fun historyParentV2(kind: Int, parentSequence: Long, childSequence: Long) {
    historySequenceV2(childSequence)
    when (RuntimeHistoryParentKindV2.fromTag(kind)) {
        RuntimeHistoryParentKindV2.ORIGINAL_ALLOCATION -> historyShapeV2(parentSequence == 0L && childSequence == 1L)
        RuntimeHistoryParentKindV2.RECORDED_EVENT -> {
            historySequenceV2(parentSequence)
            historyShapeV2(parentSequence < RuntimeHistoryProfileV2.MAX_EVENTS && childSequence == parentSequence + 1)
        }
    }
    // Coordinate validity is not native authenticity, SQL submission or safe retirement.
}

/** A head always describes at least one recorded event. Absence is NOT an uninstalled grant. */
@Entity(tableName = "runtime_history_head_v2")
internal data class RuntimeHistoryHeadRowV2(
    @PrimaryKey val singleton: Int,
    val scope: String,
    val profile: String,
    val storageId: String,
    val bindingDigest: String,
    val installationGenesisDigest: String,
    val sequence: Long,
    val eventDigest: String,
    val commandEpoch: Long,
    val commandSequence: Long,
    val commandDigest: String,
    val roleCommitment: String,
    val recoveryCommitment: String,
) {
    init { requireShape() }
    fun requireShape() {
        historyShapeV2(singleton == 1 && profile == RuntimeHistoryProfileV2.PROFILE && commandEpoch >= 0 && commandSequence >= 0)
        historyScopeV2(scope); historyIdV2(storageId); historySequenceV2(sequence)
        listOf(bindingDigest, installationGenesisDigest, eventDigest, commandDigest, roleCommitment, recoveryCommitment).forEach(::historyDigestV2)
    }
}

@Entity(tableName = "runtime_history_event_v2", indices = [Index(value = ["transactionId"], unique = true)])
internal data class RuntimeHistoryEventRowV2(
    @PrimaryKey val sequence: Long,
    val transactionId: String,
    val parentKind: Int,
    val parentSequence: Long,
    val parentDigest: String,
    val eventDigest: String,
    val changeCount: Int,
    val chunkCount: Int,
    val payloadBytes: Long,
    val encodedBytes: Long,
    val header: ByteArray,
) {
    init { requireShape() }
    fun requireShape() {
        historyParentV2(parentKind, parentSequence, sequence); historyIdV2(transactionId)
        historyDigestV2(parentDigest); historyDigestV2(eventDigest)
        historyShapeV2(changeCount >= 0 && chunkCount > 0 && payloadBytes > 0 && encodedBytes > 0 && header.isNotEmpty())
        historyCapacityV2(changeCount <= RuntimeHistoryProfileV2.MAX_CHANGES && chunkCount <= RuntimeHistoryProfileV2.MAX_EVENT_CHUNKS &&
            payloadBytes <= RuntimeHistoryProfileV2.MAX_EVENT_BYTES && encodedBytes <= RuntimeHistoryProfileV2.MAX_EVENT_BYTES &&
            header.size <= RuntimeHistoryProfileV2.MAX_HEADER_BYTES)
        historyShapeV2(chunkCount.toLong() == (payloadBytes + RuntimeHistoryProfileV2.MAX_CHUNK_BYTES - 1) / RuntimeHistoryProfileV2.MAX_CHUNK_BYTES)
        val decoded = RuntimeHistoryCodecV2.decodeHeader(header)
        historyShapeV2(decoded.sequence == sequence && decoded.transactionId.value == transactionId && decoded.parentKind == parentKind &&
            decoded.parentSequence == parentSequence && decoded.parentDigest.value == parentDigest && decoded.changeCount == changeCount &&
            decoded.chunkCount == chunkCount && decoded.payloadBytes() == payloadBytes)
        historyShapeV2(encodedBytes >= header.size.toLong() + 4 + payloadBytes)
    }
    override fun toString(): String = "RuntimeHistoryEventRowV2([REDACTED])"
}

/** Every physical change is distinct, including repeat updates of one key within one event. */
@Entity(
    tableName = "runtime_history_row_version_v2", primaryKeys = ["sequence", "ordinal"],
    indices = [Index(value = ["tableTag", "key", "version"], unique = true)],
)
internal data class RuntimeHistoryRowVersionRowV2(
    val sequence: Long,
    val ordinal: Int,
    val tableTag: Int,
    val key: ByteArray,
    val version: Long,
    val previousSequence: Long?,
    val previousOrdinal: Int?,
    val previousVersion: Long?,
    val mutationKind: Int,
    val beforeRowId: Long?,
    val beforeDigest: String?,
    val afterRowId: Long?,
    val afterDigest: String?,
    val afterKind: Int,
    val imageOffset: Long?,
    val imageBytes: Int?,
) {
    init { requireShape() }
    fun requireShape() {
        historySequenceV2(sequence)
        historyShapeV2(ordinal >= 0 && version > 0)
        historyCapacityV2(ordinal < RuntimeHistoryProfileV2.MAX_CHANGES && version <= RuntimeHistoryProfileV2.MAX_MUTATIONS)
        val table = RuntimeHistoryTableV2.fromTag(tableTag)
        historyShapeV2(table.application) // Auxiliary history rows cannot become application effects.
        RuntimeHistoryRowCodecV2.requireKey(table, key)
        val mutation = RuntimeHistoryMutationKindV2.fromTag(mutationKind)
        if (version == 1L) {
            historyShapeV2(previousSequence == null && previousOrdinal == null && previousVersion == null && mutation == RuntimeHistoryMutationKindV2.INSERT)
        } else {
            historyShapeV2(previousSequence != null && previousOrdinal != null && previousVersion == version - 1)
            val previousEvent = requireNotNull(previousSequence)
            historySequenceV2(previousEvent)
            val previous = requireNotNull(previousOrdinal)
            historyShapeV2(previous >= 0 && previous < RuntimeHistoryProfileV2.MAX_CHANGES &&
                (previousEvent < sequence || (previousEvent == sequence && previous < ordinal)))
        }
        if (mutation == RuntimeHistoryMutationKindV2.INSERT) historyShapeV2(beforeRowId == null && beforeDigest == null)
        else historyShapeV2(beforeRowId != null && beforeDigest != null)
        beforeDigest?.let(::historyDigestV2)
        if (mutation == RuntimeHistoryMutationKindV2.DELETE) {
            historyShapeV2(!table.immutable && afterRowId == null && afterDigest == null &&
                afterKind == RuntimeHistoryAfterKindV2.TOMBSTONE.tag && imageOffset == null && imageBytes == null)
        } else {
            historyShapeV2(afterRowId != null && afterDigest != null)
            historyDigestV2(requireNotNull(afterDigest))
            if (table.immutable) {
                historyShapeV2(mutation == RuntimeHistoryMutationKindV2.INSERT && version == 1L &&
                    afterKind == RuntimeHistoryAfterKindV2.RETAINED_IMMUTABLE.tag && imageOffset == null && imageBytes == null)
            } else {
                historyShapeV2(afterKind == RuntimeHistoryAfterKindV2.IMAGE.tag && imageOffset != null && imageBytes != null)
                val offset = requireNotNull(imageOffset); val length = requireNotNull(imageBytes)
                historyShapeV2(offset >= 0 && length > 0)
                historyCapacityV2(length <= RuntimeHistoryProfileV2.MAX_ROW_BYTES)
                historyAddV2(offset, length.toLong(), RuntimeHistoryProfileV2.MAX_EVENT_BYTES)
            }
        }
        // All rowids above are signed Int64 values. Zero/negative rowids are not sentinels.
        // The scanner separately joins the actual predecessor, full image, current row and native cut.
    }
    override fun toString(): String = "RuntimeHistoryRowVersionRowV2([REDACTED])"
}

/** Continuous event payload, packed in 65536-byte chunks except its exact final remainder. */
@Entity(tableName = "runtime_history_chunk_v2", primaryKeys = ["sequence", "ordinal"])
internal data class RuntimeHistoryChunkRowV2(val sequence: Long, val ordinal: Int, val digest: String, val bytes: ByteArray) {
    init { requireShape() }
    fun requireShape() {
        historySequenceV2(sequence); historyShapeV2(ordinal >= 0 && bytes.isNotEmpty()); historyDigestV2(digest)
        historyCapacityV2(ordinal < RuntimeHistoryProfileV2.MAX_EVENT_CHUNKS && bytes.size <= RuntimeHistoryProfileV2.MAX_CHUNK_BYTES)
    }
    override fun toString(): String = "RuntimeHistoryChunkRowV2([REDACTED])"
}

/** Derived index only; scanner must cross-check the immutable stream and actual current row. */
@Entity(tableName = "runtime_history_row_tip_v2", primaryKeys = ["tableTag", "key"])
internal data class RuntimeHistoryRowTipRowV2(
    val tableTag: Int,
    val key: ByteArray,
    val version: Long,
    val sequence: Long,
    val ordinal: Int,
    val tombstone: Int,
    val targetRowId: Long?,
    val rowDigest: String?,
) {
    init { requireShape() }
    fun requireShape() {
        val table = RuntimeHistoryTableV2.fromTag(tableTag)
        historyShapeV2(table.application) // Auxiliary history rows cannot become application effects.
        RuntimeHistoryRowCodecV2.requireKey(table, key); historySequenceV2(sequence)
        historyShapeV2(version > 0 && ordinal >= 0 && tombstone in 0..1)
        historyCapacityV2(version <= RuntimeHistoryProfileV2.MAX_MUTATIONS && ordinal < RuntimeHistoryProfileV2.MAX_CHANGES)
        if (tombstone == 1) historyShapeV2(!table.immutable && targetRowId == null && rowDigest == null)
        else { historyShapeV2(targetRowId != null && rowDigest != null); historyDigestV2(requireNotNull(rowDigest)) }
    }
    override fun toString(): String = "RuntimeHistoryRowTipRowV2([REDACTED])"
}

/** Transaction-private-in-effect only: this table MUST be EMPTY at every durable boundary. */
@Entity(tableName = "runtime_history_write_frame_v2")
internal data class RuntimeHistoryWriteFrameRowV2(
    @PrimaryKey val singleton: Int,
    val frameId: String,
    val transactionId: String,
    val purposeTag: Int,
    val parentKind: Int,
    val parentSequence: Long,
    val parentDigest: String,
    val nextSequence: Long,
    val originalIntentDigest: String,
) {
    init { requireShape() }
    fun requireShape() {
        historyShapeV2(singleton == 1); historyIdV2(frameId); historyIdV2(transactionId)
        RuntimeWritePurposeV2.fromTag(purposeTag); historyParentV2(parentKind, parentSequence, nextSequence)
        historyDigestV2(parentDigest); historyDigestV2(originalIntentDigest)
        if (parentKind == RuntimeHistoryParentKindV2.ORIGINAL_ALLOCATION.tag) historyShapeV2(purposeTag == RuntimeWritePurposeV2.ORIGINAL_INSTALLATION.tag)
    }
}

/**
 * Trigger census, also EMPTY at every durable boundary. There is no DAO insert for this row.
 * TEXT keys are captured as exact raw UTF-8 BLOBs only AFTER original typeof=TEXT checks.
 * INTEGER keys are never stringified or cast. No custom SQL hash/JSON function is assumed.
 */
@Entity(tableName = "runtime_history_capture_v2")
internal data class RuntimeHistoryCaptureRowV2(
    @PrimaryKey val captureOrdinal: Long,
    val frameId: String,
    val tableTag: Int,
    val mutationKind: Int,
    val beforeRowId: Long?,
    val afterRowId: Long?,
    val beforeKey1: ByteArray?,
    val beforeKey2Text: ByteArray?,
    val beforeKey2Integer: Long?,
    val beforeKey3Text: ByteArray?,
    val beforeKey3Integer: Long?,
    val afterKey1: ByteArray?,
    val afterKey2Text: ByteArray?,
    val afterKey2Integer: Long?,
    val afterKey3Text: ByteArray?,
    val afterKey3Integer: Long?,
) {
    init { requireShape() }
    fun requireShape() {
        historyShapeV2(captureOrdinal > 0); historyCapacityV2(captureOrdinal <= RuntimeHistoryProfileV2.MAX_CHANGES)
        historyIdV2(frameId)
        val table = RuntimeHistoryTableV2.fromTag(tableTag)
        historyShapeV2(table.application) // Auxiliary history rows cannot become application effects.
        val kind = RuntimeHistoryMutationKindV2.fromTag(mutationKind)
        val before = captureKeyV2(table, beforeRowId, beforeKey1, beforeKey2Text, beforeKey2Integer, beforeKey3Text, beforeKey3Integer)
        try {
            val after = captureKeyV2(table, afterRowId, afterKey1, afterKey2Text, afterKey2Integer, afterKey3Text, afterKey3Integer)
            try {
                when (kind) {
                    RuntimeHistoryMutationKindV2.INSERT -> historyShapeV2(before == null && after != null)
                    RuntimeHistoryMutationKindV2.UPDATE -> historyShapeV2(before != null && after != null &&
                        before.contentEquals(after) && beforeRowId == afterRowId)
                    RuntimeHistoryMutationKindV2.DELETE -> historyShapeV2(before != null && after == null)
                }
                if (table.immutable) historyShapeV2(kind == RuntimeHistoryMutationKindV2.INSERT)
            } finally { after?.fill(0) }
        } finally { before?.fill(0) }
    }
    override fun toString(): String = "RuntimeHistoryCaptureRowV2([REDACTED])"
}

private fun captureKeyV2(
    table: RuntimeHistoryTableV2, rowId: Long?, key1: ByteArray?, key2Text: ByteArray?, key2Integer: Long?, key3Text: ByteArray?, key3Integer: Long?,
): ByteArray? {
    if (rowId == null) {
        historyShapeV2(key1 == null && key2Text == null && key2Integer == null && key3Text == null && key3Integer == null)
        return null
    }
    historyShapeV2(key1 != null)
    listOfNotNull(key1, key2Text, key3Text).forEach { historyCapacityV2(it.size <= RuntimeHistoryProfileV2.MAX_KEY_BYTES) }
    val keys = table.keyColumns
    val cells = mutableListOf<NativeHistoryPhysicalCellV2>(NativeHistoryPhysicalCellV2.Text(requireNotNull(key1)))
    try {
        for (index in 1..2) {
            val text = if (index == 1) key2Text else key3Text
            val integer = if (index == 1) key2Integer else key3Integer
            if (index >= keys.size) historyShapeV2(text == null && integer == null)
            else when (historyColumnsV2(table).single { it.name == keys[index] }.type) {
                RuntimeSqliteTypeV2.TEXT -> {
                    historyShapeV2(text != null && integer == null); cells += NativeHistoryPhysicalCellV2.Text(requireNotNull(text))
                }
                RuntimeSqliteTypeV2.INTEGER -> {
                    historyShapeV2(text == null && integer != null); cells += NativeHistoryPhysicalCellV2.Integer(requireNotNull(integer))
                }
                else -> throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED)
            }
        }
        return RuntimeHistoryRowCodecV2.encodeKey(table, cells)
    } finally { cells.forEach { if (it is NativeHistoryPhysicalCellV2.Text) it.clear() } }
}
