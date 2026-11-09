package com.ukkera.pos.app.gateway

import com.ukkera.pos.catalog.store.LocalBeginPurchasingDocumentV1
import com.ukkera.pos.catalog.store.LocalCompletePurchasingDocumentV1
import com.ukkera.pos.catalog.store.LocalPurchasingDocumentCodecV1
import com.ukkera.pos.catalog.store.LocalPurchasingDocumentProfileV1
import com.ukkera.pos.catalog.store.LocalPurchasingDocumentResultV1
import com.ukkera.pos.catalog.store.LocalWritePurchasingDocumentChunkV1
import com.ukkera.pos.app.LocalPendingPhase
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * Complete original client source and predetermined transfer requests. The literal chunk
 * bodies contain every original byte; no external source handle is needed after capture.
 * These records belong only to the original journal owner. They grant no Master authority,
 * evidence qualification, receiving, invoice, payment or retention-disposal permission.
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
internal data class StoredClientDocumentarySourceV1 private constructor(
    private val profile: String,
    val owner: LocalClientIdentity,
    val beginOperationId: OperationId,
    private val beginBody: String,
    val chunkOperationIds: List<OperationId>,
    private val chunkBodies: List<String>,
    val completeOperationId: OperationId,
    private val completeBody: String,
    /** Missing in original canonical sources; no earlier attempt or body is replaced. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    private val completionRetries: List<StoredClientDocumentaryCompletionRetryV1>? = null,
) {
    private val retries: List<StoredClientDocumentaryCompletionRetryV1> get() = completionRetries.orEmpty()
    val documentId: DomainId get() = begin.documentId
    val begin: LocalBeginPurchasingDocumentV1
        get() = decodeBody(beginBody, LocalBeginPurchasingDocumentV1.serializer())
    val complete: LocalCompletePurchasingDocumentV1
        get() = decodeBody(completeBody, LocalCompletePurchasingDocumentV1.serializer())
    val operationIds: List<OperationId>
        get() = listOf(beginOperationId) + chunkOperationIds + completionOperationIds
    val completionOperationIds: List<OperationId> get() = listOf(completeOperationId) + retries.map { it.operationId }
    val priorCompletionOperationIds: List<OperationId> get() = completionOperationIds.dropLast(1)
    val latestCompleteOperationId: OperationId get() = retries.lastOrNull()?.operationId ?: completeOperationId
    val completionAttemptCount: Int get() = 1 + retries.size

    fun beginRequest(): MutationRequest = request(beginOperationId, LocalPurchasingDocumentProfileV1.BEGIN, beginBody)
    fun chunkRequest(index: Int): MutationRequest {
        require(index in chunkOperationIds.indices && chunkOperationIds.size == chunkBodies.size)
        return request(chunkOperationIds[index], LocalPurchasingDocumentProfileV1.CHUNK, chunkBodies[index])
    }
    fun completeRequest(): MutationRequest = request(completeOperationId, LocalPurchasingDocumentProfileV1.COMPLETE, completeBody)
    fun latestCompleteRequest(): MutationRequest =
        request(latestCompleteOperationId, LocalPurchasingDocumentProfileV1.COMPLETE, retries.lastOrNull()?.body ?: completeBody)

    /** A reviewed new child parent-CAS attempt, never an edit to an existing operation body. */
    fun prepareCompletionRetry(newOperationId: OperationId, newParentAttachmentsRevision: Revision): StoredClientDocumentarySourceV1 {
        validate(owner)
        require(begin.parentOriginalId != null)
        require(completionAttemptCount < MAX_CLIENT_DOCUMENTARY_COMPLETION_ATTEMPTS)
        require(newOperationId !in operationIds)
        val previous = LocalPurchasingDocumentCodecV1.decodeComplete(latestCompleteRequest().payload)
        require(newParentAttachmentsRevision.value >= 1 &&
            newParentAttachmentsRevision != requireNotNull(previous.expectedParentAttachmentsRevision))
        val completion = previous.copy(expectedParentAttachmentsRevision = newParentAttachmentsRevision)
        return copy(completionRetries = retries + StoredClientDocumentaryCompletionRetryV1(newOperationId,
            body(LocalPurchasingDocumentCodecV1.complete(newOperationId, completion)))).also { it.validate(owner) }
    }

    /** Only one append is admissible; the original and complete retained prefix stay exact. */
    fun isOneCompletionAppendOf(original: StoredClientDocumentarySourceV1): Boolean =
        retries.size == original.retries.size + 1 && retries.dropLast(1) == original.retries &&
            copy(completionRetries = original.completionRetries) == original

    /** Selects only an operation already durably assigned to this exact source. */
    fun request(operationId: OperationId): MutationRequest? = when (operationId) {
        beginOperationId -> beginRequest()
        completeOperationId -> completeRequest()
        else -> retries.singleOrNull { it.operationId == operationId }?.let {
            request(operationId, LocalPurchasingDocumentProfileV1.COMPLETE, it.body)
        } ?: chunkOperationIds.indexOf(operationId).takeIf { it >= 0 }?.let(::chunkRequest)
    }

    fun accepts(intent: StoredClientIntentV1): Boolean =
        intent.owner == owner && request(intent.operationId)?.let { sameRequest(it, intent.request()) } == true

    /** The actual retained closed refusal is prerequisite evidence, never a caller assertion. */
    fun hasClosedStaleCompletion(intent: StoredClientIntentV1): Boolean {
        if (intent.operationId !in completionOperationIds || !accepts(intent) ||
            intent.localPhase != LocalPendingPhase.RECORDED_UNACKNOWLEDGED) return false
        val result = intent.recorded ?: return false
        if (result.operationId != intent.operationId || result.originalActorId != owner.actorId ||
            result.committed || result.code != DOCUMENTARY_STALE_COMPLETION_CODE) return false
        result.validate()
        val expected = LocalPurchasingDocumentResultV1(DOCUMENTARY_STALE_COMPLETION_CODE, documentId)
        return decodeHex(result.payloadHex, LocalPurchasingDocumentProfileV1.MAX_OUTPUT_BYTES).useWiped { bytes ->
            LocalPurchasingDocumentCodecV1.result(StoredBytes(bytes)) == expected &&
                journalJson.encodeToString(LocalPurchasingDocumentResultV1.serializer(), expected)
                    .encodeToByteArray(throwOnInvalidSequence = true).useWiped { it.contentEquals(bytes) }
        }
    }

    /** A fresh, validated complete-byte copy. Callers must wipe it after their protected use. */
    fun copyOriginalBytes(): ByteArray = validatedOriginalBytes()

    fun validate(expectedOwner: LocalClientIdentity) {
        require(owner == expectedOwner)
        validatedOriginalBytes().fill(0)
        journalJson.encodeToString(serializer(), this).encodeToByteArray(throwOnInvalidSequence = true).useWiped {
            require(it.size in 1..MAX_CLIENT_DOCUMENTARY_SOURCE_BYTES)
        }
    }

    override fun toString(): String = "StoredClientDocumentarySourceV1([REDACTED])"

    private fun validatedOriginalBytes(): ByteArray {
        require(profile == CLIENT_DOCUMENTARY_SOURCE_PROFILE_V1)
        val original = begin
        val completion = complete
        require(chunkOperationIds.size == original.chunkDigests.size && chunkBodies.size == chunkOperationIds.size)
        require(operationIds.toSet().size == operationIds.size)
        require(completion.documentId == original.documentId && completion.beginOperationId == beginOperationId)
        require((completion.expectedParentAttachmentsRevision != null) == (original.parentOriginalId != null))
        require(sameRequest(beginRequest(), LocalPurchasingDocumentCodecV1.begin(beginOperationId, original)))
        require(sameRequest(completeRequest(), LocalPurchasingDocumentCodecV1.complete(completeOperationId, completion)))
        require(completionRetries == null || retries.size in 1 until MAX_CLIENT_DOCUMENTARY_COMPLETION_ATTEMPTS)
        require(retries.isEmpty() || original.parentOriginalId != null)
        var previousCompletion = completion
        for (retry in retries) {
            val next = decodeBody(retry.body, LocalCompletePurchasingDocumentV1.serializer())
            require(next.documentId == original.documentId && next.beginOperationId == beginOperationId)
            val parentRevision = requireNotNull(next.expectedParentAttachmentsRevision)
            require(parentRevision.value >= 1 &&
                parentRevision != requireNotNull(previousCompletion.expectedParentAttachmentsRevision))
            require(sameRequest(requireNotNull(request(retry.operationId)), LocalPurchasingDocumentCodecV1.complete(retry.operationId, next)))
            previousCompletion = next
        }
        val bytes = ByteArray(original.byteLength)
        try {
            for (index in chunkBodies.indices) {
                val chunk = decodeBody(chunkBodies[index], LocalWritePurchasingDocumentChunkV1.serializer())
                require(chunk.documentId == original.documentId && chunk.beginOperationId == beginOperationId && chunk.chunkIndex == index)
                require(sameRequest(chunkRequest(index), LocalPurchasingDocumentCodecV1.writeChunk(chunkOperationIds[index], chunk)))
                LocalPurchasingDocumentCodecV1.decodeChunkHex(chunk.chunkHex).useWiped { slot ->
                    val start = index * LocalPurchasingDocumentProfileV1.CHUNK_BYTES
                    val length = minOf(LocalPurchasingDocumentProfileV1.CHUNK_BYTES, bytes.size - start)
                    require(slot.size == length && sha256(slot) == original.chunkDigests[index])
                    slot.copyInto(bytes, start)
                }
            }
            require(sha256(bytes) == original.exactBytesDigest)
            return bytes
        } catch (failure: Throwable) { bytes.fill(0); throw failure }
    }

    private fun <T> decodeBody(body: String, serializer: KSerializer<T>): T =
        body.encodeToByteArray(throwOnInvalidSequence = true).useWiped { bytes ->
            require(bytes.size in 1..LocalPurchasingDocumentProfileV1.MAX_INPUT_BYTES)
            journalJson.decodeFromString(serializer, bytes.decodeToString(throwOnInvalidSequence = true))
        }

    private fun request(operationId: OperationId, command: CommandKey, body: String): MutationRequest =
        body.encodeToByteArray(throwOnInvalidSequence = true).useWiped { bytes ->
            require(bytes.size in 1..LocalPurchasingDocumentProfileV1.MAX_INPUT_BYTES)
            MutationRequest(operationId, command, StoredBytes(bytes))
        }

    private fun sameRequest(first: MutationRequest, second: MutationRequest): Boolean =
        first.operationId == second.operationId && first.command == second.command &&
            first.payload == second.payload && first.actionApprovalId == second.actionApprovalId

    companion object {
        /** Preparation has no dispatch or durability result; only the native save can retain it. */
        fun prepare(owner: LocalClientIdentity, beginOperationId: OperationId, begin: LocalBeginPurchasingDocumentV1,
                    chunkOperationIds: List<OperationId>, completeOperationId: OperationId,
                    complete: LocalCompletePurchasingDocumentV1, originalBytes: ByteArray): StoredClientDocumentarySourceV1 {
            require(originalBytes.size == begin.byteLength && chunkOperationIds.size == begin.chunkDigests.size)
            require((listOf(beginOperationId) + chunkOperationIds + completeOperationId).toSet().size == chunkOperationIds.size + 2)
            val operations = chunkOperationIds.toList()
            return originalBytes.copyOf().useWiped { bytes ->
                val chunks = operations.indices.map { index ->
                    val start = index * LocalPurchasingDocumentProfileV1.CHUNK_BYTES
                    bytes.copyOfRange(start, minOf(bytes.size, start + LocalPurchasingDocumentProfileV1.CHUNK_BYTES)).useWiped { slot ->
                        body(LocalPurchasingDocumentCodecV1.writeChunk(operations[index], LocalWritePurchasingDocumentChunkV1(
                            begin.documentId, beginOperationId, index, LocalPurchasingDocumentCodecV1.hex(slot))))
                    }
                }
                StoredClientDocumentarySourceV1(CLIENT_DOCUMENTARY_SOURCE_PROFILE_V1, owner, beginOperationId,
                    body(LocalPurchasingDocumentCodecV1.begin(beginOperationId, begin)), operations, chunks,
                    completeOperationId, body(LocalPurchasingDocumentCodecV1.complete(completeOperationId, complete)))
                    .also { it.validate(owner) }
            }
        }

        private fun body(request: MutationRequest): String =
            request.payload.copyBytes().useWiped { it.decodeToString(throwOnInvalidSequence = true) }
    }
}

@Serializable
internal data class StoredClientDocumentaryCompletionRetryV1(val operationId: OperationId, val body: String) {
    override fun toString(): String = "StoredClientDocumentaryCompletionRetryV1([REDACTED])"
}

private const val CLIENT_DOCUMENTARY_SOURCE_PROFILE_V1 = "ukkera.client-documentary-source/v1"
private const val DOCUMENTARY_STALE_COMPLETION_CODE = "purchasing.document.stale_revision"
/** Temporary finite client custody ceiling; completing a transfer does not authorize purge. */
internal const val MAX_CLIENT_DOCUMENTARY_SOURCES = 4
internal const val MAX_CLIENT_DOCUMENTARY_COMPLETION_ATTEMPTS = 16
internal const val MAX_CLIENT_DOCUMENTARY_SOURCE_BYTES = LocalPurchasingDocumentProfileV1.MAX_ORIGINAL_BYTES * 2 + 64 * 1024
/** Keep the complete ordinary intent allowance and add separately bounded documentary space. */
internal const val MAX_CLIENT_INTENT_MANIFEST_BYTES =
    MAX_CLIENT_ENTRY_BYTES * MAX_CLIENT_PENDING + 4096 + MAX_CLIENT_DOCUMENTARY_SOURCE_BYTES * MAX_CLIENT_DOCUMENTARY_SOURCES
