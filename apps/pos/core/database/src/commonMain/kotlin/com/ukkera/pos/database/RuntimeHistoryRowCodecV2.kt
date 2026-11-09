package com.ukkera.pos.database

import com.ukkera.pos.security.NativeHistoryCellMetadataV2
import com.ukkera.pos.security.NativeHistoryPhysicalCellV2
import com.ukkera.pos.security.NativeHistoryPhysicalRowV2
import com.ukkera.pos.security.NativeHistoryPhysicalRowWireV2
import com.ukkera.pos.security.PayloadDigest

/** Database adapters use the ONE native physical/key frame, never a second row encoding. */
internal object RuntimeHistoryRowCodecV2 {
    fun preflight(table: RuntimeHistoryTableV2, metadata: List<NativeHistoryCellMetadataV2>): Long {
        val columns = historyColumnsV2(table)
        historyShapeV2(columns.size == metadata.size)
        // 4-byte frame string length, ASCII domain, table tag, signed rowid, cell count.
        var bytes = 4L + "ukkera.runtime-history/physical-row/v2".length + 4 + 8 + 4
        for ((column, actual) in columns.zip(metadata)) {
            historyShapeV2(actual.name == column.name)
            if (actual.type == RuntimeSqliteTypeV2.NULL) {
                historyShapeV2(column.nullable && actual.byteCount == 0L && actual.integerValue == null)
                bytes = historyAddV2(bytes, 1, RuntimeHistoryProfileV2.MAX_ROW_BYTES.toLong())
            } else {
                historyShapeV2(actual.type == column.type)
                if (actual.type == RuntimeSqliteTypeV2.INTEGER) {
                    val integer = actual.integerValue
                    historyShapeV2(actual.byteCount == 0L && integer != null && integer in column.minimum..column.maximum)
                    bytes = historyAddV2(bytes, 9, RuntimeHistoryProfileV2.MAX_ROW_BYTES.toLong())
                } else {
                    historyShapeV2(actual.integerValue == null && actual.byteCount >= column.minimum)
                    historyCapacityV2(actual.byteCount <= column.maximum)
                    bytes = historyAddV2(bytes, 5 + actual.byteCount, RuntimeHistoryProfileV2.MAX_ROW_BYTES.toLong())
                }
            }
        }
        return bytes
    }
    fun encode(row: NativeHistoryPhysicalRowV2): ByteArray = NativeHistoryPhysicalRowWireV2.encode(row)
    fun decode(bytes: ByteArray): NativeHistoryPhysicalRowV2 = NativeHistoryPhysicalRowWireV2.decode(bytes)
    fun rowDigest(row: NativeHistoryPhysicalRowV2): PayloadDigest = NativeHistoryPhysicalRowWireV2.rowDigest(row)
    fun key(row: NativeHistoryPhysicalRowV2): ByteArray = NativeHistoryPhysicalRowWireV2.key(row)
    fun encodeKey(table: RuntimeHistoryTableV2, cells: List<NativeHistoryPhysicalCellV2>): ByteArray =
        NativeHistoryPhysicalRowWireV2.encodeKey(table, cells)
    fun requireKey(table: RuntimeHistoryTableV2, bytes: ByteArray): Unit = NativeHistoryPhysicalRowWireV2.requireKey(table, bytes)
}

/** One bounded original row, retaining exact storage classes and signed rowid. Not evidence. */
internal class RuntimeHistoryRowViewV2(val physical: NativeHistoryPhysicalRowV2) {
    val table: RuntimeHistoryTableV2 get() = physical.table
    val rowId: Long get() = physical.rowId
    private val columns = historyColumnsV2(table)
    private fun cell(name: String): NativeHistoryPhysicalCellV2 {
        val index = columns.indexOfFirst { it.name == name }
        historyShapeV2(index >= 0)
        return physical.cells[index]
    }
    fun number(name: String): Long = (cell(name) as? NativeHistoryPhysicalCellV2.Integer)?.value
        ?: throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED)
    fun int(name: String): Int = number(name).also { historyShapeV2(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) }.toInt()
    fun optionalNumber(name: String): Long? = if (cell(name) == NativeHistoryPhysicalCellV2.Null) null else number(name)
    fun optionalInt(name: String): Int? = optionalNumber(name)?.also {
        historyShapeV2(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
    }?.toInt()
    fun text(name: String): String {
        val bytes = (cell(name) as? NativeHistoryPhysicalCellV2.Text)?.bytes()
            ?: throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED)
        return try { historyUtf8V2(bytes) } finally { bytes.fill(0) }
    }
    fun optionalText(name: String): String? = if (cell(name) == NativeHistoryPhysicalCellV2.Null) null else text(name)
    fun bytes(name: String): ByteArray = (cell(name) as? NativeHistoryPhysicalCellV2.Blob)?.bytes()
        ?: throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED)
    private inline fun <T> ownedBytes(name: String, construct: (ByteArray) -> T): T {
        val owned = bytes(name)
        return try { construct(owned) } catch (failure: Throwable) { owned.fill(0); throw failure }
    }
    fun key(): ByteArray = RuntimeHistoryRowCodecV2.key(physical)
    fun digest(): PayloadDigest = RuntimeHistoryRowCodecV2.rowDigest(physical)
    fun clear(): Unit = physical.clear()
    private fun only(expected: RuntimeHistoryTableV2) { historyShapeV2(table == expected) }

    fun head(): RuntimeHistoryHeadRowV2 { only(RuntimeHistoryTableV2.HEAD)
        return RuntimeHistoryHeadRowV2(int("singleton"), text("scope"), text("profile"), text("storageId"),
            text("bindingDigest"), text("installationGenesisDigest"), number("sequence"), text("eventDigest"),
            number("commandEpoch"), number("commandSequence"), text("commandDigest"), text("roleCommitment"), text("recoveryCommitment"))
    }
    fun event(): RuntimeHistoryEventRowV2 { only(RuntimeHistoryTableV2.EVENT)
        return ownedBytes("header") { owned ->
            RuntimeHistoryEventRowV2(number("sequence"), text("transactionId"), int("parentKind"), number("parentSequence"),
                text("parentDigest"), text("eventDigest"), int("changeCount"), int("chunkCount"), number("payloadBytes"),
                number("encodedBytes"), owned)
        }
    }
    fun version(): RuntimeHistoryRowVersionRowV2 { only(RuntimeHistoryTableV2.ROW_VERSION)
        return ownedBytes("key") { owned ->
            RuntimeHistoryRowVersionRowV2(number("sequence"), int("ordinal"), int("tableTag"), owned, number("version"),
                optionalNumber("previousSequence"), optionalInt("previousOrdinal"), optionalNumber("previousVersion"), int("mutationKind"),
                optionalNumber("beforeRowId"), optionalText("beforeDigest"), optionalNumber("afterRowId"), optionalText("afterDigest"),
                int("afterKind"), optionalNumber("imageOffset"), optionalInt("imageBytes"))
        }
    }
    fun tip(): RuntimeHistoryRowTipRowV2 { only(RuntimeHistoryTableV2.ROW_TIP)
        return ownedBytes("key") { owned ->
            RuntimeHistoryRowTipRowV2(int("tableTag"), owned, number("version"), number("sequence"), int("ordinal"),
                int("tombstone"), optionalNumber("targetRowId"), optionalText("rowDigest"))
        }
    }
    fun chunk(): RuntimeHistoryChunkRowV2 { only(RuntimeHistoryTableV2.CHUNK)
        return ownedBytes("bytes") { owned ->
            RuntimeHistoryChunkRowV2(number("sequence"), int("ordinal"), text("digest"), owned)
        }
    }
    fun branch(): BranchRow { only(RuntimeHistoryTableV2.BRANCH)
        return BranchRow(text("scope"), text("assignmentId"), text("deviceId"), number("epoch"), number("revision"),
            number("sequence"), text("journalHash"), number("writable") == 1L, text("recoveryJson"))
    }
    fun operation(): OperationRow { only(RuntimeHistoryTableV2.OPERATION)
        return ownedBytes("result") { owned ->
            OperationRow(text("scope"), text("operationId"), text("actorId"), text("command"), int("commandVersion"),
                text("digest"), text("outcome"), owned, text("resultHash"), number("sequence"), number("epoch"),
                text("committedAt"), text("authenticationId"), number("policyRevision"))
        }
    }
    fun journal(): JournalRow { only(RuntimeHistoryTableV2.JOURNAL)
        return ownedBytes("envelope") { owned ->
            JournalRow(text("scope"), number("sequence"), text("operationId"), text("assignmentId"), number("epoch"),
                text("previousHash"), text("hash"), owned)
        }
    }
    fun aggregate(): AggregateRow { only(RuntimeHistoryTableV2.AGGREGATE)
        return ownedBytes("payload") { owned ->
            AggregateRow(text("scope"), text("kind"), text("id"), number("revision"), text("schema"), owned, text("payloadHash"))
        }
    }
    fun claim(): ClaimRow { only(RuntimeHistoryTableV2.CLAIM)
        return ClaimRow(text("scope"), text("namespace"), text("key"), text("ownerId"))
    }
    fun audit(): AuditRow { only(RuntimeHistoryTableV2.AUDIT)
        return AuditRow(text("id"), text("scope"), text("operationId"), text("kind"), text("actorId"), text("originalActorId"),
            number("policyRevision"), text("recordedAt"), text("digest"))
    }
    fun outbox(): OutboxRow { only(RuntimeHistoryTableV2.OUTBOX)
        return ownedBytes("body") { owned ->
            OutboxRow(text("scope"), text("operationId"), int("ordinal"), text("kind"), owned, text("bodyHash"))
        }
    }
    override fun toString(): String = "RuntimeHistoryRowViewV2(table=${table.tag}, [REDACTED])"
}
