package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.Revision

/** Shared live working-set accounting. Persisted DATA is not charged as live plaintext.
 * Reservations precede allocation and are released only after scoped buffers are cleared. */
internal class RuntimeHistoryColdMemory {
    private var owned = 0L
    private var commands = 0
    private var versions = 0
    val retainedBytes: Long get() = owned
    val retainedCommands: Int get() = commands
    val retainedVersions: Int get() = versions
    fun reserve(bytes: Long) { owned = historyAddV2(owned, bytes, MAX_BYTES) }
    fun release(bytes: Long) { historyShapeV2(bytes >= 0 && bytes <= owned); owned -= bytes }

    /** Every recursive business owner uses this same transcript budget. A borrowed
     * alias is not another capture; only its genuine owner reserves and releases it. */
    fun reserveOwnedCommand(versions: Int) {
        historyShapeV2(versions >= 0)
        historyCapacityV2(commands < MAX_COMMANDS && versions <= MAX_VERSIONS - this.versions)
        commands++
        this.versions += versions
    }

    fun releaseOwnedCommand(versions: Int) {
        historyShapeV2(commands > 0 && versions >= 0 && versions <= this.versions)
        commands--
        this.versions -= versions
    }

    companion object {
        const val MAX_BYTES = 64L * 1024 * 1024
        const val MAX_COMMANDS = 256
        const val MAX_VERSIONS = 4096
    }
}

/** Scratch-only metadata format. It deliberately decodes into inert database coordinates,
 * never NativeHistoryRowMetadata, a native source, a permission or a close token. */
internal object RuntimeHistoryColdMetadataCodec {
    const val MAX_BYTES = 16 * 1024
    private const val MAX_CELLS = 64

    fun encode(item: RuntimeHistoryReadItem, scope: BranchScope): ByteArray {
        val m = item.metadata
        historyCapacityV2(m.cells.size <= MAX_CELLS)
        val out = HistoryBinaryWriterV2(MAX_BYTES)
        return try {
            out.int(1); out.int(m.pass.ordinal); out.int(m.kind.ordinal)
            out.optionalInt(m.table?.tag); out.optionalLong(m.rowId)
            out.long(if (item is RuntimeHistoryReadItem.Row) item.encodedBytes else 0)
            out.int(m.cells.size)
            for (cell in m.cells) {
                out.text(cell.name, 128); out.int(cell.type.tag); out.long(cell.byteCount); out.optionalLong(cell.integerValue)
            }
            val binding = m.protectedBinding
            out.byte(if (binding == null) 0 else 1)
            if (binding != null) {
                val key = binding.key
                historyShapeV2(key.scope == scope)
                out.int(binding.table.ordinal); out.id(key.recordId); out.long(binding.revision.value)
                if (key is ProtectedLocalRecordKey.Throttle) {
                    when (val d = key.key.dimension) {
                        ThrottleDimension.Account -> out.byte(0)
                        is ThrottleDimension.Device -> { out.byte(1); out.id(d.deviceId) }
                    }
                }
            }
            out.finish()
        } finally { out.clear() }
    }

    class Decoded(val metadata: RuntimeHistoryReadMetadata, val encodedBytes: Long)

    fun decode(bytes: ByteArray, scope: BranchScope): Decoded {
        val input = HistoryBinaryReaderV2(bytes, MAX_BYTES)
        return try {
            historyShapeV2(input.int() == 1)
            val pass = NativeHistoryReadPassV2.entries.getOrNull(input.int()) ?: malformed()
            val kind = NativeHistoryRowKindV2.entries.getOrNull(input.int()) ?: malformed()
            val table = input.optionalInt()?.let(RuntimeHistoryTableV2::fromTag)
            val rowId = input.optionalLong()
            val encodedBytes = input.long()
            historyShapeV2(encodedBytes in 0..RuntimeHistoryProfileV2.MAX_ROW_BYTES.toLong())
            val count = input.int(); historyShapeV2(count in 0..MAX_CELLS)
            val cells = List(count) {
                val name = input.text(128)
                val tag = input.int()
                val type = RuntimeSqliteTypeV2.entries.singleOrNull { it.tag == tag } ?: malformed()
                val size = input.long(); historyShapeV2(size in 0..RuntimeHistoryProfileV2.MAX_ROW_BYTES.toLong())
                NativeHistoryCellMetadataV2(name, type, size, input.optionalLong())
            }
            val binding = when (input.byte()) {
                0 -> null
                1 -> {
                    val protectedTable = ProtectedLocalTable.entries.getOrNull(input.int()) ?: malformed()
                    val id = input.id(); val revision = Revision(input.long())
                    val key = when (protectedTable) {
                        ProtectedLocalTable.ACCOUNT -> ProtectedLocalRecordKey.Account(scope, id)
                        ProtectedLocalTable.EFFECTIVE_POLICY -> ProtectedLocalRecordKey.EffectivePolicy(scope, id)
                        ProtectedLocalTable.ADMISSION -> ProtectedLocalRecordKey.Admission(scope, id)
                        ProtectedLocalTable.THROTTLE -> {
                            val dimension = when (input.byte()) {
                                0 -> ThrottleDimension.Account
                                1 -> ThrottleDimension.Device(input.id())
                                else -> malformed()
                            }
                            ProtectedLocalRecordKey.Throttle(ThrottleKey(scope, id, dimension))
                        }
                        ProtectedLocalTable.SESSION_ISSUANCE -> ProtectedLocalRecordKey.SessionIssuance(scope, id)
                        ProtectedLocalTable.AUTH_CONFIGURATION -> ProtectedLocalRecordKey.AuthConfiguration(scope, id)
                        ProtectedLocalTable.REFRESH_ISSUANCE -> ProtectedLocalRecordKey.RefreshIssuance(scope, id)
                        ProtectedLocalTable.AUTH_EVENT -> ProtectedLocalRecordKey.AuthEvent(scope, id)
                        ProtectedLocalTable.SESSION_ARCHIVE -> ProtectedLocalRecordKey.SessionArchive(scope, id)
                        ProtectedLocalTable.ACTION_APPROVAL -> ProtectedLocalRecordKey.ActionApproval(scope, id)
                    }
                    ProtectedLocalBinding(key, revision)
                }
                else -> malformed()
            }
            input.end()
            when (kind) {
                NativeHistoryRowKindV2.GROUP_END, NativeHistoryRowKindV2.PASS_END ->
                    historyShapeV2(rowId == null && cells.isEmpty() && binding == null && encodedBytes == 0L)
                NativeHistoryRowKindV2.PHYSICAL -> historyShapeV2(table != null && rowId != null && binding == null && encodedBytes > 0)
                NativeHistoryRowKindV2.IMAGE -> historyShapeV2(table?.application == true && rowId != null && binding == null &&
                    cells.size == 1 && cells[0].name == "image" && cells[0].type == RuntimeSqliteTypeV2.BLOB &&
                    cells[0].byteCount == encodedBytes)
                NativeHistoryRowKindV2.PLAINTEXT -> historyShapeV2(table?.protectedTable == binding?.table && binding != null &&
                    rowId != null && encodedBytes == 0L && cells.size == 1 && cells[0].name == "plaintext" &&
                    cells[0].type == RuntimeSqliteTypeV2.BLOB)
            }
            Decoded(RuntimeHistoryReadMetadata(pass, kind, table, rowId, cells, binding), encodedBytes)
        } finally { input.clear() }
    }

    private fun malformed(): Nothing = throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED)
}
