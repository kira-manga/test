package com.ukkera.pos.database

import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.recovery.RecoveryResourceSelector
import com.ukkera.pos.security.NativeHistoryOriginalIntentV2
import com.ukkera.pos.security.NativeHistoryOriginalResourceV2
import com.ukkera.pos.security.NativeHistoryResourceKindV2
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.SelfOwnedNativeHistoryWireV2
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId

/**
 * V2 header DATA. Complete A/D7/attempt references are checked against the original native cut.
 * Header DATA. Original request/actor/operation/resources reside in the bounded original
 * intent capsule, never a regenerated current command. The payload is ordered: packed mutable
 * row images, exact intent capsule, captured dependencies, captured decision facts. The last
 * two are bounded committed bytes, NOT an implemented authorization/eligibility codec here.
 * installationGenesisDigest refers to the preexisting installation/business genesis format,
 * not a digest of this containing header. First history event identity is its computed digest.
 * No timestamp purports to observe fsync or a final no-IO sample written back after sealing.
 */
internal data class RuntimeHistoryEventHeaderV2(
    val scope: String,
    val storageId: DomainId,
    val bindingDigest: PayloadDigest,
    val originalAllocationDigest: PayloadDigest,
    val definitionDigest: PayloadDigest,
    val installationAttemptId: DomainId,
    val installationAttemptSequence: Long,
    val installationGenesisDigest: PayloadDigest,
    val sequence: Long,
    val parentKind: Int,
    val parentSequence: Long,
    val parentDigest: PayloadDigest,
    val transactionId: DomainId,
    val assignmentId: DomainId,
    val purpose: RuntimeWritePurposeV2,
    val commandEpoch: Long,
    val commandSequence: Long,
    val commandDigest: PayloadDigest,
    val roleCommitment: PayloadDigest,
    val recoveryCommitment: PayloadDigest,
    val changeCount: Int,
    val chunkCount: Int,
    val imageBytes: Long,
    val intentBytes: Int,
    val intentDigest: PayloadDigest,
    val dependencyBytes: Int,
    val dependencyDigest: PayloadDigest,
    val factBytes: Int,
    val factDigest: PayloadDigest,
) {
    init { requireShape() }
    fun requireShape() {
        historyScopeV2(scope); historyParentV2(parentKind, parentSequence, sequence)
        historyShapeV2(installationAttemptSequence > 0)
        historyShapeV2(commandEpoch >= 0 && commandSequence >= 0 && changeCount >= 0 && chunkCount > 0 && imageBytes >= 0 &&
            intentBytes > 0 && dependencyBytes >= 0 && factBytes >= 0)
        historyCapacityV2(changeCount <= RuntimeHistoryProfileV2.MAX_CHANGES && chunkCount <= RuntimeHistoryProfileV2.MAX_EVENT_CHUNKS &&
            intentBytes <= RuntimeHistoryProfileV2.MAX_CAPSULE_BYTES && dependencyBytes <= RuntimeHistoryProfileV2.MAX_CAPSULE_BYTES &&
            factBytes <= RuntimeHistoryProfileV2.MAX_CAPSULE_BYTES)
        if (parentKind == RuntimeHistoryParentKindV2.ORIGINAL_ALLOCATION.tag) historyShapeV2(purpose == RuntimeWritePurposeV2.ORIGINAL_INSTALLATION)
        val payload = payloadBytes()
        historyShapeV2(chunkCount.toLong() == (payload + RuntimeHistoryProfileV2.MAX_CHUNK_BYTES - 1) / RuntimeHistoryProfileV2.MAX_CHUNK_BYTES)
    }
    fun payloadBytes(): Long {
        var bytes = historyAddV2(imageBytes, intentBytes.toLong(), RuntimeHistoryProfileV2.MAX_EVENT_BYTES)
        bytes = historyAddV2(bytes, dependencyBytes.toLong(), RuntimeHistoryProfileV2.MAX_EVENT_BYTES)
        return historyAddV2(bytes, factBytes.toLong(), RuntimeHistoryProfileV2.MAX_EVENT_BYTES)
    }
}

internal object RuntimeHistoryCodecV2 {
    private const val HEADER = "ukkera.runtime-history/event-header/v2"
    private const val MUTATION = "ukkera.runtime-history/mutation/v2"
    private const val CHUNK = "ukkera.runtime-history/chunk/v2"
    private const val MAX_MUTATION_BYTES = 2_048

    fun encodeHeader(header: RuntimeHistoryEventHeaderV2): ByteArray {
        header.requireShape()
        val out = HistoryBinaryWriterV2(RuntimeHistoryProfileV2.MAX_HEADER_BYTES)
        return try {
            out.text(HEADER, 64); out.int(RuntimeHistoryProfileV2.FORMAT); out.text(RuntimeHistoryProfileV2.PROFILE, 64)
            out.text(header.scope, 110); out.id(header.storageId); out.digest(header.bindingDigest)
            out.digest(header.originalAllocationDigest); out.digest(header.definitionDigest)
            out.id(header.installationAttemptId); out.long(header.installationAttemptSequence); out.digest(header.installationGenesisDigest)
            out.long(header.sequence); out.int(header.parentKind); out.long(header.parentSequence); out.digest(header.parentDigest)
            out.id(header.transactionId); out.id(header.assignmentId); out.int(header.purpose.tag)
            out.long(header.commandEpoch); out.long(header.commandSequence); out.digest(header.commandDigest)
            out.digest(header.roleCommitment); out.digest(header.recoveryCommitment)
            out.int(header.changeCount); out.int(header.chunkCount); out.long(header.imageBytes)
            out.int(header.intentBytes); out.digest(header.intentDigest)
            out.int(header.dependencyBytes); out.digest(header.dependencyDigest); out.int(header.factBytes); out.digest(header.factDigest)
            out.finish()
        } finally { out.clear() }
    }

    fun decodeHeader(bytes: ByteArray): RuntimeHistoryEventHeaderV2 {
        val input = HistoryBinaryReaderV2(bytes, RuntimeHistoryProfileV2.MAX_HEADER_BYTES)
        return try {
            historyShapeV2(input.text(64) == HEADER && input.int() == RuntimeHistoryProfileV2.FORMAT && input.text(64) == RuntimeHistoryProfileV2.PROFILE)
            val header = RuntimeHistoryEventHeaderV2(input.text(110), input.id(), input.digest(), input.digest(), input.digest(), input.id(), input.long(), input.digest(),
                input.long(), input.int(), input.long(), input.digest(), input.id(), input.id(), RuntimeWritePurposeV2.fromTag(input.int()),
                input.long(), input.long(), input.digest(), input.digest(), input.digest(), input.int(), input.int(), input.long(),
                input.int(), input.digest(), input.int(), input.digest(), input.int(), input.digest())
            input.end(); header
        } finally { input.clear() }
    }

    fun encodeMutation(row: RuntimeHistoryRowVersionRowV2): ByteArray {
        row.requireShape() // Recheck Room ByteArrays at each consumption, not only construction.
        val out = HistoryBinaryWriterV2(MAX_MUTATION_BYTES)
        return try {
            out.text(MUTATION, 64); out.long(row.sequence); out.int(row.ordinal); out.int(row.tableTag)
            out.bytes(row.key, RuntimeHistoryProfileV2.MAX_KEY_BYTES); out.long(row.version)
            out.optionalLong(row.previousSequence); out.optionalInt(row.previousOrdinal); out.optionalLong(row.previousVersion)
            out.int(row.mutationKind); out.optionalLong(row.beforeRowId); out.optionalDigest(row.beforeDigest)
            out.optionalLong(row.afterRowId); out.optionalDigest(row.afterDigest); out.int(row.afterKind)
            out.optionalLong(row.imageOffset); out.optionalInt(row.imageBytes)
            out.finish()
        } finally { out.clear() }
    }

    fun decodeMutation(bytes: ByteArray): RuntimeHistoryRowVersionRowV2 {
        val input = HistoryBinaryReaderV2(bytes, MAX_MUTATION_BYTES)
        var key: ByteArray? = null
        try {
            historyShapeV2(input.text(64) == MUTATION)
            val sequence = input.long(); val ordinal = input.int(); val table = input.int()
            val ownedKey = input.bytes(RuntimeHistoryProfileV2.MAX_KEY_BYTES); key = ownedKey
            val row = RuntimeHistoryRowVersionRowV2(sequence, ordinal, table, ownedKey,
                input.long(), input.optionalLong(), input.optionalInt(), input.optionalLong(), input.int(), input.optionalLong(), input.optionalDigest(),
                input.optionalLong(), input.optionalDigest(), input.int(), input.optionalLong(), input.optionalInt())
            input.end(); key = null // Transfer only after the complete fixed frame.
            return row
        } finally { key?.fill(0); input.clear() }
    }

    /** Domain-separated bounded record. This calls the existing platform SHA-256, not a new primitive. */
    fun chunkDigest(sequence: Long, ordinal: Int, bytes: ByteArray): PayloadDigest {
        val record = chunkRecord(sequence, ordinal, bytes)
        return try { sha256(record) } finally { record.fill(0) }
    }

    internal fun chunkRecord(sequence: Long, ordinal: Int, bytes: ByteArray): ByteArray {
        historySequenceV2(sequence); historyShapeV2(ordinal >= 0 && bytes.isNotEmpty())
        historyCapacityV2(ordinal < RuntimeHistoryProfileV2.MAX_EVENT_CHUNKS && bytes.size <= RuntimeHistoryProfileV2.MAX_CHUNK_BYTES)
        val out = HistoryBinaryWriterV2(RuntimeHistoryProfileV2.MAX_CHUNK_BYTES + 128)
        return try {
            out.text(CHUNK, 64); out.long(sequence); out.int(ordinal); out.bytes(bytes, RuntimeHistoryProfileV2.MAX_CHUNK_BYTES)
            out.finish()
        } finally { out.clear() }
    }

    fun originalIntentBytes(intent: NativeHistoryOriginalIntentV2): ByteArray {
        RuntimeWritePurposeV2.fromTag(intent.purposeTag)
        return SelfOwnedNativeHistoryWireV2.encodeIntent(intent)
    }

    /** Stable data commitments, never current role/recovery admission by themselves. */
    fun roleCommitment(branch: BranchRow): PayloadDigest {
        val out = HistoryBinaryWriterV2(512)
        try {
            out.text("ukkera.runtime-history/role/v2", 64); out.text(branch.scope, 110)
            out.id(DomainId(branch.assignmentId)); out.id(DomainId(branch.deviceId)); out.long(branch.epoch)
            out.byte(if (branch.writable) 1 else 0)
            val bytes = out.finish()
            return try { sha256(bytes) } finally { bytes.fill(0) }
        } finally { out.clear() }
    }
    fun recoveryCommitment(branch: BranchRow): PayloadDigest {
        val raw = branch.recoveryJson.encodeToByteArray(throwOnInvalidSequence = true)
        try {
            historyCapacityV2(raw.size <= RuntimeHistoryProfileV2.MAX_ROW_BYTES)
            val out = HistoryBinaryWriterV2(512)
            try {
                out.text("ukkera.runtime-history/recovery/v2", 64); out.text(branch.scope, 110)
                out.long(raw.size.toLong()); out.digest(sha256(raw))
                val bytes = out.finish()
                return try { sha256(bytes) } finally { bytes.fill(0) }
            } finally { out.clear() }
        } finally { raw.fill(0) }
    }

    /** Source-fixed cross-module vocabulary; exhaustive mapping notices a future recovery kind. */
    fun originalResource(selector: RecoveryResourceSelector, preserveHold: Boolean, originalEvidenceDigest: PayloadDigest): NativeHistoryOriginalResourceV2 =
        when (selector) {
            RecoveryResourceSelector.WholeBranch -> NativeHistoryOriginalResourceV2(1, null, null, preserveHold, originalEvidenceDigest)
            is RecoveryResourceSelector.Kind -> NativeHistoryOriginalResourceV2(2, resourceKind(selector.kind), null, preserveHold, originalEvidenceDigest)
            is RecoveryResourceSelector.Resource -> NativeHistoryOriginalResourceV2(3, resourceKind(selector.resource.kind), selector.resource.resourceId,
                preserveHold, originalEvidenceDigest)
        }
    private fun resourceKind(kind: RecoveryResourceKind): NativeHistoryResourceKindV2 = when (kind) {
        RecoveryResourceKind.STOCK -> NativeHistoryResourceKindV2.STOCK
        RecoveryResourceKind.REFUND_ENTITLEMENT -> NativeHistoryResourceKindV2.REFUND_ENTITLEMENT
        RecoveryResourceKind.DEPOSIT_ENTITLEMENT -> NativeHistoryResourceKindV2.DEPOSIT_ENTITLEMENT
        RecoveryResourceKind.EXCHANGE_OFFSET -> NativeHistoryResourceKindV2.EXCHANGE_OFFSET
        RecoveryResourceKind.CASH_CUSTODY -> NativeHistoryResourceKindV2.CASH_CUSTODY
        RecoveryResourceKind.PO_ALLOWANCE -> NativeHistoryResourceKindV2.PO_ALLOWANCE
        RecoveryResourceKind.SUPPLIER_SETTLEMENT -> NativeHistoryResourceKindV2.SUPPLIER_SETTLEMENT
        RecoveryResourceKind.KITCHEN_CUSTODY -> NativeHistoryResourceKindV2.KITCHEN_CUSTODY
        RecoveryResourceKind.ORDER_CUSTODY -> NativeHistoryResourceKindV2.ORDER_CUSTODY
        RecoveryResourceKind.PURCHASE_CUSTODY -> NativeHistoryResourceKindV2.PURCHASE_CUSTODY
        RecoveryResourceKind.AUTHORIZATION -> NativeHistoryResourceKindV2.AUTHORIZATION
    }
}

/** Data-only hash result. Its public-to-module constructor/finish is not full-history proof. */
internal data class RuntimeHistoryEventCommitmentV2(val sequence: Long, val digest: PayloadDigest, val encodedBytes: Long)

/**
 * Streaming V2 commitment; NEVER assembles a whole event. At most one <=64KiB payload chunk
 * (plus bounded framing copies) is hashed at a time. Event bytes are charged as 4+header.size,
 * each 4+mutationRecord.size, and each 4+chunkRecord.size. Thus metadata is not a free bypass
 * around the 2GiB Long ceiling. Prefix chain = H(domain, previous, record kind/index/digest);
 * finish binds total bytes/counts. This is NOT SHA256(concatenated multi-gigabyte event).
 * It does not establish native authenticity, full SQL census, plaintext validity or admission.
 */
internal class RuntimeHistoryEventStreamV2(private val header: RuntimeHistoryEventHeaderV2) {
    private var state: PayloadDigest
    private var encodedBytes: Long
    private var changes = 0
    private var chunks = 0
    private var imageBytes = 0L
    private var finished = false
    init {
        val initialHeader = RuntimeHistoryCodecV2.encodeHeader(header)
        try { state = sha256(initialHeader); encodedBytes = 4L + initialHeader.size }
        finally { initialHeader.fill(0) }
    }

    fun appendMutation(row: RuntimeHistoryRowVersionRowV2) {
        historyShapeV2(!finished && chunks == 0 && changes < header.changeCount && row.sequence == header.sequence && row.ordinal == changes)
        val record = RuntimeHistoryCodecV2.encodeMutation(row)
        try {
            val nextImageBytes = if (row.afterKind == RuntimeHistoryAfterKindV2.IMAGE.tag) {
                historyShapeV2(row.imageOffset == imageBytes)
                historyAddV2(imageBytes, requireNotNull(row.imageBytes).toLong(), header.imageBytes)
            } else imageBytes
            val nextBytes = historyAddV2(encodedBytes, 4L + record.size, RuntimeHistoryProfileV2.MAX_EVENT_BYTES)
            val nextState = chain(1, changes, sha256(record))
            imageBytes = nextImageBytes; encodedBytes = nextBytes; state = nextState; changes++
        } finally { record.fill(0) }
    }

    fun appendChunk(row: RuntimeHistoryChunkRowV2) {
        historyShapeV2(!finished && changes == header.changeCount && imageBytes == header.imageBytes &&
            chunks < header.chunkCount && row.sequence == header.sequence && row.ordinal == chunks)
        row.requireShape()
        val remaining = header.payloadBytes() - chunks.toLong() * RuntimeHistoryProfileV2.MAX_CHUNK_BYTES
        historyShapeV2(row.bytes.size.toLong() == minOf(remaining, RuntimeHistoryProfileV2.MAX_CHUNK_BYTES.toLong()))
        val record = RuntimeHistoryCodecV2.chunkRecord(row.sequence, row.ordinal, row.bytes)
        try {
            val digest = sha256(record)
            historyShapeV2(digest.value == row.digest)
            val nextBytes = historyAddV2(encodedBytes, 4L + record.size, RuntimeHistoryProfileV2.MAX_EVENT_BYTES)
            val nextState = chain(2, chunks, digest)
            encodedBytes = nextBytes; state = nextState; chunks++
        } finally { record.fill(0) }
    }

    fun finish(): RuntimeHistoryEventCommitmentV2 {
        historyShapeV2(!finished && changes == header.changeCount && chunks == header.chunkCount && imageBytes == header.imageBytes)
        val out = HistoryBinaryWriterV2(256)
        try {
            out.text("ukkera.runtime-history/event-finish/v2", 64); out.digest(state)
            out.long(encodedBytes); out.int(changes); out.int(chunks)
            val bytes = out.finish()
            val result = try { RuntimeHistoryEventCommitmentV2(header.sequence, sha256(bytes), encodedBytes) } finally { bytes.fill(0) }
            finished = true
            return result
        } finally { out.clear() }
    }

    private fun chain(kind: Int, ordinal: Int, digest: PayloadDigest): PayloadDigest {
        val out = HistoryBinaryWriterV2(256)
        try {
            out.text("ukkera.runtime-history/event-link/v2", 64); out.digest(state); out.int(kind); out.int(ordinal); out.digest(digest)
            val bytes = out.finish()
            return try { sha256(bytes) } finally { bytes.fill(0) }
        } finally { out.clear() }
    }
    fun clear() { finished = true } // No payload buffers survive an append or initialization.

}

/** Fixed big-endian integers and explicit length/presence framing, no varints/JSON/coercion. */
internal class HistoryBinaryWriterV2(private val maximum: Int) {
    init { historyShapeV2(maximum > 0); historyCapacityV2(maximum <= RuntimeHistoryProfileV2.MAX_ROW_BYTES) }
    private var buffer = ByteArray(minOf(maximum, 256))
    private var size = 0
    private fun reserve(count: Int) {
        historyShapeV2(count >= 0); historyCapacityV2(count <= maximum - size)
        val needed = size + count
        if (needed > buffer.size) {
            val old = buffer
            buffer = buffer.copyOf(maxOf(needed, minOf(maximum.toLong(), buffer.size.toLong() * 2).toInt()))
            old.fill(0)
        }
    }
    fun byte(value: Int) { historyShapeV2(value in 0..255); reserve(1); buffer[size++] = value.toByte() }
    fun int(value: Int) { reserve(4); for (shift in 24 downTo 0 step 8) buffer[size++] = (value ushr shift).toByte() }
    fun long(value: Long) { reserve(8); for (shift in 56 downTo 0 step 8) buffer[size++] = (value ushr shift).toByte() }
    fun bytes(value: ByteArray, limit: Int) {
        historyShapeV2(limit >= 0); historyCapacityV2(value.size <= limit && value.size <= maximum - size)
        historyCapacityV2(value.size <= maximum - size - 4)
        int(value.size); reserve(value.size); value.copyInto(buffer, size); size += value.size
    }
    fun text(value: String, limit: Int) {
        historyShapeV2(limit >= 0 && '\u0000' !in value); historyCapacityV2(value.length <= limit)
        val encoded = try { value.encodeToByteArray(throwOnInvalidSequence = true) } catch (_: Exception) {
            throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED)
        }
        try { bytes(encoded, limit) } finally { encoded.fill(0) }
    }
    fun id(value: DomainId) { text(value.value, 36) }
    fun digest(value: PayloadDigest) {
        reserve(32)
        for (index in 0 until 32) buffer[size++] = ((value.value[index * 2].digitToInt(16) shl 4) or value.value[index * 2 + 1].digitToInt(16)).toByte()
    }
    fun optionalInt(value: Int?) { byte(if (value == null) 0 else 1); value?.let { int(it) } }
    fun optionalLong(value: Long?) { byte(if (value == null) 0 else 1); value?.let { long(it) } }
    fun optionalDigest(value: String?) { byte(if (value == null) 0 else 1); value?.let { digest(PayloadDigest(it)) } }
    fun finish(): ByteArray = buffer.copyOf(size).also { clear() }
    fun clear() { buffer.fill(0); size = 0 }
}

internal class HistoryBinaryReaderV2(input: ByteArray, maximum: Int) {
    init { historyShapeV2(maximum >= 0); historyCapacityV2(input.size <= maximum && maximum <= RuntimeHistoryProfileV2.MAX_ROW_BYTES) }
    private val buffer = input.copyOf()
    private var at = 0
    private fun need(count: Int) { historyShapeV2(count >= 0 && count <= buffer.size - at) }
    fun byte(): Int { need(1); return buffer[at++].toInt() and 255 }
    fun int(): Int { need(4); var value = 0; repeat(4) { value = (value shl 8) or byte() }; return value }
    fun long(): Long { need(8); var value = 0L; repeat(8) { value = (value shl 8) or byte().toLong() }; return value }
    fun bytes(limit: Int): ByteArray {
        historyShapeV2(limit >= 0)
        val count = int(); historyShapeV2(count >= 0); historyCapacityV2(count <= limit); need(count)
        return buffer.copyOfRange(at, at + count).also { at += count }
    }
    fun text(limit: Int): String {
        val raw = bytes(limit)
        return try { historyUtf8V2(raw) } finally { raw.fill(0) }
    }
    fun id(): DomainId { val value = text(36); historyIdV2(value); return DomainId(value) }
    fun digest(): PayloadDigest {
        need(32)
        val text = buildString(64) { repeat(32) { val value = byte(); append("0123456789abcdef"[value ushr 4]); append("0123456789abcdef"[value and 15]) } }
        return PayloadDigest(text)
    }
    private fun present(): Boolean = when (byte()) { 0 -> false; 1 -> true; else -> throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED) }
    fun optionalInt(): Int? = if (present()) int() else null
    fun optionalLong(): Long? = if (present()) long() else null
    fun optionalDigest(): String? = if (present()) digest().value else null
    fun end() { historyShapeV2(at == buffer.size) }
    fun clear() { buffer.fill(0); at = buffer.size }
}
