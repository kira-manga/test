package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.PurchasingEvidenceMediaV1
import com.ukkera.pos.security.PurchasingEvidencePurposeV1
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.BusinessDate
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Explicit local storage/transfer ceilings, never an activated receiving or retention rule. */
public object LocalPurchasingDocumentProfileV1 {
    public const val PROFILE: String = "purchasing-document-capture/v1"
    public const val HASH_FORMAT: String = "purchasing-document-exact-bytes/v1"
    public const val REFERENCE_GROUP_FORMAT: String = "purchasing-document-exact-reference/v1"
    public const val MAX_ORIGINAL_BYTES: Int = 512 * 1024
    public const val MAX_ATTACHMENT_BYTES: Int = 256 * 1024
    public const val MAX_ATTACHMENT_COUNT: Int = 4
    public const val MAX_TOTAL_ATTACHMENT_BYTES: Int = 1024 * 1024
    public const val CHUNK_BYTES: Int = 8192
    public const val MAX_INPUT_BYTES: Int = 32768
    public const val MAX_OUTPUT_BYTES: Int = 32768
    public const val MAX_RECORD_BYTES: Int = 65536
    public const val PAGE_SIZE: Int = 5
    public const val MAX_DUPLICATE_CANDIDATES: Int = 5
    public val BEGIN: CommandKey = CommandKey("purchasing.document.begin", 1)
    public val CHUNK: CommandKey = CommandKey("purchasing.document.chunk", 1)
    public val COMPLETE: CommandKey = CommandKey("purchasing.document.complete", 1)
    public val READ: ReadKey = ReadKey("purchasing.document.read", 1)
    public val BYTES: ReadKey = ReadKey("purchasing.document.bytes", 1)
    internal const val INTAKE: String = "purchasing.document.intake"
    internal const val SLOT: String = "purchasing.document.slot"
    internal const val ORIGINAL: String = "purchasing.document.original"
    internal const val EVIDENCE: String = "purchasing.document.evidence"
    internal const val ATTACHMENTS: String = "purchasing.document.attachments"
    internal const val IDENTITY_CLAIM: String = "purchasing.document.identity"
    internal const val REFERENCE_CLAIM: String = "purchasing.document.reference"
    internal val commands: Set<CommandKey> = setOf(BEGIN, CHUNK, COMPLETE)
    internal val kinds: Set<String> = setOf(INTAKE, SLOT, ORIGINAL, EVIDENCE, ATTACHMENTS)
    public fun schema(kind: String): String = "$kind/v1"
}

/** Caller saves the complete manifest and every command identity before the first submission. */
@Serializable
public data class LocalBeginPurchasingDocumentV1(
    public val documentId: DomainId,
    public val supplierId: DomainId,
    public val expectedSupplierRevision: Revision,
    public val supplierReferenceOriginal: String,
    public val documentDate: BusinessDate?,
    public val purpose: PurchasingEvidencePurposeV1,
    public val media: PurchasingEvidenceMediaV1,
    public val originalFileName: String,
    public val byteLength: Int,
    public val exactBytesDigest: PayloadDigest,
    public val chunkDigests: List<PayloadDigest>,
    public val parentOriginalId: DomainId? = null,
    public val correctionOf: DomainId? = null,
    public val correctionReason: String? = null,
) {
    init {
        require(expectedSupplierRevision.value >= 1)
        purchasingDocumentText(supplierReferenceOriginal, 192)
        purchasingDocumentText(originalFileName, 192)
        require(byteLength in 1..(if (parentOriginalId == null) LocalPurchasingDocumentProfileV1.MAX_ORIGINAL_BYTES
            else LocalPurchasingDocumentProfileV1.MAX_ATTACHMENT_BYTES))
        require(chunkDigests.size == purchasingDocumentChunkCount(byteLength))
        require(parentOriginalId == null || correctionOf == null)
        require((correctionOf == null) == (correctionReason == null))
        correctionReason?.let { purchasingDocumentText(it, 256) }
        require(parentOriginalId != documentId && correctionOf != documentId)
    }
    override fun toString(): String = "LocalBeginPurchasingDocumentV1([REDACTED])"
}

@Serializable
public data class LocalWritePurchasingDocumentChunkV1(
    public val documentId: DomainId,
    public val beginOperationId: OperationId,
    public val chunkIndex: Int,
    public val chunkHex: String,
) {
    init {
        require(chunkIndex in 0 until purchasingDocumentChunkCount(LocalPurchasingDocumentProfileV1.MAX_ORIGINAL_BYTES))
        purchasingDocumentHex(chunkHex, LocalPurchasingDocumentProfileV1.CHUNK_BYTES)
    }
    override fun toString(): String = "LocalWritePurchasingDocumentChunkV1([REDACTED])"
}

@Serializable
public data class LocalCompletePurchasingDocumentV1(
    public val documentId: DomainId,
    public val beginOperationId: OperationId,
    public val expectedParentAttachmentsRevision: Revision? = null,
) {
    init { require(expectedParentAttachmentsRevision == null || expectedParentAttachmentsRevision.value >= 1) }
}

@Serializable
public enum class LocalPurchasingDocumentStateV1 { INCOMPLETE, CAPTURED_AT_BRANCH }

@Serializable
public data class LocalPurchasingDocumentResultV1(
    public val code: String,
    public val documentId: DomainId,
    public val beginOperationId: OperationId? = null,
    public val state: LocalPurchasingDocumentStateV1? = null,
    public val storageRevision: Revision? = null,
    public val byteLength: Int? = null,
    public val exactBytesDigest: PayloadDigest? = null,
    public val parentAttachmentsRevision: Revision? = null,
    public val chunkIndex: Int? = null,
    public val duplicateCandidateIds: List<DomainId> = emptyList(),
    public val moreDuplicateCandidates: Boolean = false,
) { override fun toString(): String = "LocalPurchasingDocumentResultV1([REDACTED])" }

@Serializable
public data class LocalPurchasingDocumentQueryV1(
    public val documentId: DomainId? = null,
    public val after: DomainId? = null,
    public val pendingDocumentId: DomainId? = null,
) {
    init {
        require(documentId == null || after == null)
        require(pendingDocumentId == null || documentId == null && after == null)
    }
}

@Serializable
public data class LocalPurchasingDocumentChunkQueryV1(
    public val documentId: DomainId,
    public val chunkIndex: Int,
    public val forExport: Boolean = false,
) {
    init { require(chunkIndex in 0 until purchasingDocumentChunkCount(LocalPurchasingDocumentProfileV1.MAX_ORIGINAL_BYTES)) }
}

/** Literal original native journal origin, obtained from the actual retained completing command. */
@Serializable
public data class LocalPurchasingDocumentHistoryOriginV1(
    public val operationId: OperationId,
    public val assignmentId: DomainId,
    public val authorityEpoch: Revision,
    public val sequence: JournalSequence,
    public val journalHash: PayloadDigest,
)

@Serializable
public data class LocalPurchasingDocumentFileV1(
    public val documentId: DomainId,
    public val scope: BranchScope,
    public val storageRevision: Revision,
    public val supplierId: DomainId,
    public val supplierNames: LocalNamesV1,
    public val supplierReferenceOriginal: String,
    public val documentDate: BusinessDate?,
    public val purpose: PurchasingEvidencePurposeV1,
    public val media: PurchasingEvidenceMediaV1,
    public val originalFileName: String,
    public val byteLength: Int,
    public val exactBytesDigest: PayloadDigest,
    public val hashFormat: String = LocalPurchasingDocumentProfileV1.HASH_FORMAT,
    public val state: LocalPurchasingDocumentStateV1 = LocalPurchasingDocumentStateV1.CAPTURED_AT_BRANCH,
    public val qualification: LocalPurchasingPolicyQualificationV1 = LocalPurchasingPolicyQualificationV1.UNQUALIFIED_SELECTORS,
    public val beginOperationId: OperationId,
    public val originalActorId: DomainId,
    public val beganAt: UtcInstant,
    public val capturedAt: UtcInstant,
    public val historyOrigin: LocalPurchasingDocumentHistoryOriginV1,
    public val parentOriginalId: DomainId? = null,
    public val correctionOf: DomainId? = null,
    public val correctionReason: String? = null,
) { override fun toString(): String = "LocalPurchasingDocumentFileV1([UNQUALIFIED, REDACTED])" }

@Serializable
public data class LocalPurchasingDocumentIntakeViewV1(
    public val original: LocalPurchasingDocumentFileV1,
    public val attachmentsRevision: Revision? = null,
    public val attachments: List<LocalPurchasingDocumentFileV1> = emptyList(),
    public val duplicateCandidateIds: List<DomainId> = emptyList(),
    public val moreDuplicateCandidates: Boolean = false,
)

@Serializable
public data class LocalPurchasingDocumentPendingV1(
    public val request: LocalBeginPurchasingDocumentV1,
    public val beginOperationId: OperationId,
    public val originalActorId: DomainId,
    public val beganAt: UtcInstant,
    public val presentChunkIndices: List<Int>,
    public val state: LocalPurchasingDocumentStateV1,
)

@Serializable
public data class LocalPurchasingDocumentReadResultV1(
    public val items: List<LocalPurchasingDocumentFileV1> = emptyList(),
    public val nextAfter: DomainId? = null,
    public val detail: LocalPurchasingDocumentIntakeViewV1? = null,
    public val pending: LocalPurchasingDocumentPendingV1? = null,
) { override fun toString(): String = "LocalPurchasingDocumentReadResultV1([REDACTED])" }

@Serializable
public data class LocalPurchasingDocumentChunkReadResultV1(
    public val documentId: DomainId,
    public val byteLength: Int,
    public val exactBytesDigest: PayloadDigest,
    public val media: PurchasingEvidenceMediaV1,
    public val hashFormat: String,
    public val chunkIndex: Int,
    public val chunkByteLength: Int,
    public val chunkDigest: PayloadDigest,
    public val chunkHex: String,
) { override fun toString(): String = "LocalPurchasingDocumentChunkReadResultV1([REDACTED])" }

/** Public preparation aid only. These hashes confer no supplier authenticity or action approval. */
public data class LocalPurchasingDocumentContentV1(
    public val byteLength: Int,
    public val exactBytesDigest: PayloadDigest,
    public val chunkDigests: List<PayloadDigest>,
)

public object LocalPurchasingDocumentCodecV1 {
    public fun decodeBegin(bytes: StoredBytes): LocalBeginPurchasingDocumentV1 =
        purchasingDocumentDecode(bytes, LocalBeginPurchasingDocumentV1.serializer())
    public fun decodeChunk(bytes: StoredBytes): LocalWritePurchasingDocumentChunkV1 =
        purchasingDocumentDecode(bytes, LocalWritePurchasingDocumentChunkV1.serializer())
    public fun decodeComplete(bytes: StoredBytes): LocalCompletePurchasingDocumentV1 =
        purchasingDocumentDecode(bytes, LocalCompletePurchasingDocumentV1.serializer())
    public fun normalize(command: CommandKey, bytes: StoredBytes): StoredBytes = purchasingDocumentNormalize(command, bytes)
    public fun begin(operationId: OperationId, request: LocalBeginPurchasingDocumentV1): MutationRequest =
        MutationRequest(operationId, LocalPurchasingDocumentProfileV1.BEGIN, purchasingDocumentEncode(request, LocalBeginPurchasingDocumentV1.serializer()))
    public fun writeChunk(operationId: OperationId, request: LocalWritePurchasingDocumentChunkV1): MutationRequest =
        MutationRequest(operationId, LocalPurchasingDocumentProfileV1.CHUNK, purchasingDocumentEncode(request, LocalWritePurchasingDocumentChunkV1.serializer()))
    public fun complete(operationId: OperationId, request: LocalCompletePurchasingDocumentV1): MutationRequest =
        MutationRequest(operationId, LocalPurchasingDocumentProfileV1.COMPLETE, purchasingDocumentEncode(request, LocalCompletePurchasingDocumentV1.serializer()))
    public fun query(request: LocalPurchasingDocumentQueryV1): ReadRequest =
        ReadRequest(LocalPurchasingDocumentProfileV1.READ, purchasingDocumentEncode(request, LocalPurchasingDocumentQueryV1.serializer()))
    public fun decodeQuery(bytes: StoredBytes): LocalPurchasingDocumentQueryV1 =
        purchasingDocumentDecode(bytes, LocalPurchasingDocumentQueryV1.serializer())
    public fun chunkQuery(request: LocalPurchasingDocumentChunkQueryV1): ReadRequest =
        ReadRequest(LocalPurchasingDocumentProfileV1.BYTES, purchasingDocumentEncode(request, LocalPurchasingDocumentChunkQueryV1.serializer()))
    public fun result(bytes: StoredBytes): LocalPurchasingDocumentResultV1 =
        purchasingDocumentDecode(bytes, LocalPurchasingDocumentResultV1.serializer(), LocalPurchasingDocumentProfileV1.MAX_OUTPUT_BYTES)
    public fun readResult(bytes: StoredBytes): LocalPurchasingDocumentReadResultV1 =
        purchasingDocumentDecode(bytes, LocalPurchasingDocumentReadResultV1.serializer(), LocalPurchasingDocumentProfileV1.MAX_OUTPUT_BYTES)
    public fun chunkResult(bytes: StoredBytes): LocalPurchasingDocumentChunkReadResultV1 =
        purchasingDocumentDecode(bytes, LocalPurchasingDocumentChunkReadResultV1.serializer(), LocalPurchasingDocumentProfileV1.MAX_OUTPUT_BYTES)
    public fun content(bytes: ByteArray): LocalPurchasingDocumentContentV1 {
        require(bytes.size in 1..LocalPurchasingDocumentProfileV1.MAX_ORIGINAL_BYTES)
        val digests = (0 until purchasingDocumentChunkCount(bytes.size)).map { index ->
            val start = index * LocalPurchasingDocumentProfileV1.CHUNK_BYTES
            val slot = bytes.copyOfRange(start, minOf(bytes.size, start + LocalPurchasingDocumentProfileV1.CHUNK_BYTES))
            try { sha256(slot) } finally { slot.fill(0) }
        }
        return LocalPurchasingDocumentContentV1(bytes.size, sha256(bytes), digests)
    }
    public fun hex(bytes: ByteArray): String {
        require(bytes.size in 1..LocalPurchasingDocumentProfileV1.CHUNK_BYTES)
        val alphabet = "0123456789abcdef"
        return buildString(bytes.size * 2) { bytes.forEach { value ->
            val octet = value.toInt() and 255
            append(alphabet[octet ushr 4]); append(alphabet[octet and 15])
        } }
    }
    public fun decodeChunkHex(value: String): ByteArray = purchasingDocumentHexBytes(value)
}

internal val purchasingDocumentJson: Json = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false }
internal fun <T> purchasingDocumentEncode(value: T, serializer: KSerializer<T>, maximum: Int = LocalPurchasingDocumentProfileV1.MAX_INPUT_BYTES): StoredBytes {
    val bytes = purchasingDocumentJson.encodeToString(serializer, value).encodeToByteArray(throwOnInvalidSequence = true)
    try { require(bytes.size in 1..maximum); return StoredBytes(bytes) } finally { bytes.fill(0) }
}
internal fun <T> purchasingDocumentDecode(bytes: StoredBytes, serializer: KSerializer<T>, maximum: Int = LocalPurchasingDocumentProfileV1.MAX_INPUT_BYTES): T {
    require(bytes.size in 1..maximum)
    val value = bytes.copyBytes()
    try { return purchasingDocumentJson.decodeFromString(serializer, value.decodeToString(throwOnInvalidSequence = true)) }
    finally { value.fill(0) }
}
internal fun purchasingDocumentText(value: String, maximum: Int) {
    require(value.length in 1..maximum && value.isNotBlank() && value.none { it.code < 0x20 || it.code == 0x7f })
    value.encodeToByteArray(throwOnInvalidSequence = true).fill(0)
}
internal fun purchasingDocumentChunkCount(length: Int): Int = (length - 1) / LocalPurchasingDocumentProfileV1.CHUNK_BYTES + 1
internal fun purchasingDocumentChunkLength(length: Int, index: Int): Int =
    minOf(LocalPurchasingDocumentProfileV1.CHUNK_BYTES, length - index * LocalPurchasingDocumentProfileV1.CHUNK_BYTES)
internal fun purchasingDocumentHex(value: String, maximum: Int) {
    require(value.length in 2..maximum * 2 && value.length % 2 == 0 && value.all { it in '0'..'9' || it in 'a'..'f' })
}
internal fun purchasingDocumentHexBytes(value: String): ByteArray {
    purchasingDocumentHex(value, LocalPurchasingDocumentProfileV1.CHUNK_BYTES)
    return ByteArray(value.length / 2) { index -> ((value[index * 2].digitToInt(16) shl 4) or value[index * 2 + 1].digitToInt(16)).toByte() }
}
