package com.ukkera.pos.database

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/**
 * Canonical subframe used ONLY by the distinct native-original retained journal producer.
 * Successful canonical decoding returns DATA, never an accepting verifier or custody capability.
 */
public object NativeOriginalJournalProvenanceCodecV1 {
    public const val TAG: String = "ukkera.native-original-provenance/v1"
    /** Draft format rejection ceiling; not measured SQL/native/runtime capacity. */
    public const val MAX_BYTES: Int = 4_096

    public fun encode(value: NativeOriginalJournalProvenanceV1): StoredBytes = try {
        StoredBytes(nativeOriginalFrameV1(listOf(
            nativeOriginalTextV1(TAG),
            nativeOriginalTextV1(value.scope.storageKey()),
            encodeHistoricalStart(value.historicalStart),
            encodeNativeOriginal(value.nativeOriginal),
            encodeCurrentWriter(value.currentWriter),
            encodeRuntimeParent(value.runtimeParent),
        ), MAX_BYTES))
    } catch (_: Exception) {
        // Scalar/parser causes can contain private input; none is retained or exposed.
        throw IllegalArgumentException("Invalid native original provenance")
    }

    public fun decode(encoded: StoredBytes): NativeOriginalJournalProvenanceV1 = try {
        requireNativeOriginalFormatDataV1(encoded.size in 1..MAX_BYTES)
        val fields = NativeOriginalFrameSliceV1(encoded.copyBytes()).fields(
            intArrayOf(TAG.length, 110, MAX_BYTES, MAX_BYTES, MAX_BYTES, MAX_BYTES),
        )
        requireNativeOriginalFormatDataV1(fields[0].text(TAG.length) == TAG)
        NativeOriginalJournalProvenanceV1(
            scope = fields[1].scope(),
            historicalStart = decodeHistoricalStart(fields[2]),
            nativeOriginal = decodeNativeOriginal(fields[3]),
            currentWriter = decodeCurrentWriter(fields[4]),
            runtimeParent = decodeRuntimeParent(fields[5]),
        )
    } catch (_: Exception) {
        throw IllegalArgumentException("Invalid native original provenance")
    }

    private fun encodeHistoricalStart(value: NativeOriginalJournalProvenanceV1.HistoricalStart): ByteArray =
        nativeOriginalFrameV1(nativeOriginalTextsV1(
            value.originalActorId.value, value.intentOperationId.value,
            value.command.name, value.command.version.toString(), value.intentSemanticDigest.value,
            value.authenticationId.value, value.policyRevision.value.toString(), value.assignmentId.value,
            value.masterDeviceId.value, value.epoch.value.toString(), value.sequence.value.toString(),
            value.journalHash.value, value.resultDigest.value, value.recordedAt.canonicalString(),
        ), MAX_BYTES)

    private fun decodeHistoricalStart(value: NativeOriginalFrameSliceV1): NativeOriginalJournalProvenanceV1.HistoricalStart {
        val fields = value.fields(intArrayOf(36, 36, 96, 4, 64, 36, 19, 36, 36, 19, 19, 64, 64, 30))
        return NativeOriginalJournalProvenanceV1.HistoricalStart(
            originalActorId = fields[0].id(),
            intentOperationId = fields[1].operation(),
            command = CommandKey(fields[2].text(96), fields[3].counter(1, 1000).toInt()),
            intentSemanticDigest = fields[4].digest(),
            authenticationId = fields[5].id(),
            policyRevision = fields[6].revision(),
            assignmentId = fields[7].id(),
            masterDeviceId = fields[8].id(),
            epoch = fields[9].revision(),
            sequence = fields[10].sequence(),
            journalHash = fields[11].digest(),
            resultDigest = fields[12].digest(),
            recordedAt = fields[13].time(),
        )
    }

    private fun encodeNativeOriginal(value: NativeOriginalJournalProvenanceV1.NativeOriginal): ByteArray =
        nativeOriginalFrameV1(nativeOriginalTextsV1(
            value.domainProfile, value.recordingOperationId.value, value.requirementDigest.value,
            value.certificateDigest.value, value.originalBodyDigest.value, value.originalSemanticDigest.value,
        ) + listOf(encodeSource(value.masterSource), encodeSource(value.clientSource)), MAX_BYTES)

    private fun decodeNativeOriginal(value: NativeOriginalFrameSliceV1): NativeOriginalJournalProvenanceV1.NativeOriginal {
        val fields = value.fields(intArrayOf(96, 36, 64, 64, 64, 64, MAX_BYTES, MAX_BYTES))
        return NativeOriginalJournalProvenanceV1.NativeOriginal(
            domainProfile = fields[0].text(96),
            recordingOperationId = fields[1].operation(),
            requirementDigest = fields[2].digest(),
            certificateDigest = fields[3].digest(),
            originalBodyDigest = fields[4].digest(),
            originalSemanticDigest = fields[5].digest(),
            masterSource = decodeSource(fields[6]),
            clientSource = decodeSource(fields[7]),
        )
    }

    private fun encodeSource(value: NativeOriginalJournalProvenanceV1.Source): ByteArray =
        nativeOriginalFrameV1(nativeOriginalTextsV1(
            value.role.name, value.installationId.value, value.deviceId.value, value.catalogueId.value,
            value.keyId.value, value.allocationId.value, value.nativeSlotIndex.toString(),
            value.sourcePayloadDigest.value, value.headerDigest.value, value.allocationDigest.value,
            value.nativeIdentityDigest.value,
        ), MAX_BYTES)

    private fun decodeSource(value: NativeOriginalFrameSliceV1): NativeOriginalJournalProvenanceV1.Source {
        val fields = value.fields(intArrayOf(6, 36, 36, 36, 36, 36, 3, 64, 64, 64, 64))
        val role = when (fields[0].text(6)) {
            "MASTER" -> NativeOriginalJournalProvenanceV1.SourceRole.MASTER
            "CLIENT" -> NativeOriginalJournalProvenanceV1.SourceRole.CLIENT
            else -> throw IllegalArgumentException("Invalid native original format data")
        }
        return NativeOriginalJournalProvenanceV1.Source(
            role = role,
            installationId = fields[1].id(),
            deviceId = fields[2].id(),
            catalogueId = fields[3].id(),
            keyId = fields[4].id(),
            allocationId = fields[5].id(),
            nativeSlotIndex = fields[6].counter(0, 127).toInt(),
            sourcePayloadDigest = fields[7].digest(),
            headerDigest = fields[8].digest(),
            allocationDigest = fields[9].digest(),
            nativeIdentityDigest = fields[10].digest(),
        )
    }

    private fun encodeCurrentWriter(value: NativeOriginalJournalProvenanceV1.CurrentWriter): ByteArray =
        nativeOriginalFrameV1(nativeOriginalTextsV1(
            value.installationId.value, value.masterDeviceId.value, value.storeIncarnationId.value,
            value.serviceIncarnationId.value, value.invocationId.value, value.assignmentId.value,
            value.epoch.value.toString(), value.runtimeProfile, value.runtimeBindingCommitment.value,
        ), MAX_BYTES)

    private fun decodeCurrentWriter(value: NativeOriginalFrameSliceV1): NativeOriginalJournalProvenanceV1.CurrentWriter {
        val fields = value.fields(intArrayOf(36, 36, 36, 36, 36, 36, 19, 96, 64))
        return NativeOriginalJournalProvenanceV1.CurrentWriter(
            installationId = fields[0].id(),
            masterDeviceId = fields[1].id(),
            storeIncarnationId = fields[2].id(),
            serviceIncarnationId = fields[3].id(),
            invocationId = fields[4].id(),
            assignmentId = fields[5].id(),
            epoch = fields[6].revision(),
            runtimeProfile = fields[7].text(96),
            runtimeBindingCommitment = fields[8].digest(),
        )
    }

    private fun encodeRuntimeParent(value: NativeOriginalJournalProvenanceV1.RuntimeParent): ByteArray =
        nativeOriginalFrameV1(nativeOriginalTextsV1(value.sequence.value.toString(), value.frameHash.value), MAX_BYTES)

    private fun decodeRuntimeParent(value: NativeOriginalFrameSliceV1): NativeOriginalJournalProvenanceV1.RuntimeParent {
        val fields = value.fields(intArrayOf(19, 64))
        return NativeOriginalJournalProvenanceV1.RuntimeParent(fields[0].sequence(), fields[1].digest())
    }
}

/** Only fixed call sites choose field counts/nesting. Unsigned lengths are checked before slicing. */
internal class NativeOriginalFrameSliceV1(
    private val bytes: ByteArray,
    private val start: Int = 0,
    private val end: Int = bytes.size,
) {
    init {
        requireNativeOriginalFormatDataV1(start >= 0 && start <= end && end <= bytes.size && bytes.size <= 16_384)
    }

    private val size: Int get() = end - start

    fun fields(maxima: IntArray): List<NativeOriginalFrameSliceV1> {
        requireNativeOriginalFormatDataV1(maxima.size in 1..18)
        val fields = ArrayList<NativeOriginalFrameSliceV1>(maxima.size)
        var cursor = start
        for (maximum in maxima) {
            requireNativeOriginalFormatDataV1(maximum in 1..16_384 && end - cursor >= 4)
            var length = 0L
            repeat(4) { length = (length shl 8) or (bytes[cursor++].toLong() and 0xffL) }
            requireNativeOriginalFormatDataV1(length in 1L..maximum.toLong() && length <= (end - cursor).toLong())
            val fieldEnd = cursor + length.toInt()
            fields += NativeOriginalFrameSliceV1(bytes, cursor, fieldEnd)
            cursor = fieldEnd
        }
        requireNativeOriginalFormatDataV1(cursor == end)
        return fields
    }

    /** All specified text scalars are canonical ASCII subsets of UTF-8; no replacement decoding. */
    fun text(maximum: Int): String {
        requireNativeOriginalFormatDataV1(maximum in 1..110 && size in 1..maximum)
        for (index in start until end) {
            requireNativeOriginalFormatDataV1((bytes[index].toInt() and 0xff) in 0x21..0x7e)
        }
        return bytes.decodeToString(start, end, throwOnInvalidSequence = true)
    }

    fun counter(minimum: Long = 0, maximum: Long = Long.MAX_VALUE): Long {
        val text = text(19)
        val value = text.toLongOrNull() ?: throw IllegalArgumentException("Invalid native original format data")
        requireNativeOriginalFormatDataV1(value >= minimum && value <= maximum && value.toString() == text)
        return value
    }

    fun id(): DomainId = DomainId(text(36))
    fun operation(): OperationId = OperationId(text(36))
    fun digest(): PayloadDigest = PayloadDigest(text(64))
    fun revision(): Revision = Revision(counter())
    fun sequence(): JournalSequence = JournalSequence(counter(minimum = 1))
    fun time(): UtcInstant = UtcInstant.parseCanonical(text(30))

    fun scope(): BranchScope {
        val value = text(110)
        requireNativeOriginalFormatDataV1(value.length == 110 && value[36] == '/' && value[73] == '/')
        return BranchScope(DomainId(value.substring(0, 36)), DomainId(value.substring(37, 73)),
            DomainId(value.substring(74, 110)))
    }

    fun stored(maximum: Int): StoredBytes {
        requireNativeOriginalFormatDataV1(maximum in 1..16_384 && size in 1..maximum)
        return StoredBytes(bytes.copyOfRange(start, end))
    }
}

internal fun nativeOriginalTextV1(value: String): ByteArray {
    requireNativeOriginalFormatDataV1(value.length in 1..110 && value.all { it.code in 0x21..0x7e })
    return value.encodeToByteArray()
}

private fun nativeOriginalTextsV1(vararg values: String): List<ByteArray> = values.map(::nativeOriginalTextV1)

/** Length-prefix encoding only; no journal registration, digest attestation or native allocation. */
private fun nativeOriginalFrameV1(values: List<ByteArray>, maximum: Int): ByteArray {
    requireNativeOriginalFormatDataV1(maximum in 1..16_384 && values.size in 1..18)
    requireNativeOriginalFormatDataV1(values.all { it.size in 1..maximum })
    val total = values.sumOf { 4L + it.size.toLong() }
    requireNativeOriginalFormatDataV1(total <= maximum.toLong())
    val result = ByteArray(total.toInt())
    var cursor = 0
    for (value in values) {
        for (shift in 24 downTo 0 step 8) result[cursor++] = (value.size ushr shift).toByte()
        value.copyInto(result, destinationOffset = cursor)
        cursor += value.size
    }
    return result
}
