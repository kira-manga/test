package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

internal suspend fun readDocumentaryDownloadSource(access: GatewayAccess, id: DomainId): LocalPurchasingDocumentFileV1 {
    if (!access.hasHint(Permission(SecurityAction.PURCHASING_DOCUMENT_READ, PermissionVerb.PERFORM), id) ||
        !access.hasHint(Permission(SecurityAction.PURCHASING_DOCUMENT_READ, PermissionVerb.EXPORT), id))
        throw GatewayFailure(LocalServiceProblem.DENIED)
    val response = LocalPurchasingDocumentCodecV1.readResult(access.read(
        LocalPurchasingDocumentCodecV1.query(LocalPurchasingDocumentQueryV1(documentId = id))).payload)
    check(response.items.isEmpty() && response.nextAfter == null && response.pending == null)
    val file = response.detail?.original ?: throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
    check(file.documentId == id && file.scope == access.view.scope && file.storageRevision == Revision(1) &&
        file.state == LocalPurchasingDocumentStateV1.CAPTURED_AT_BRANCH &&
        file.qualification == LocalPurchasingPolicyQualificationV1.UNQUALIFIED_SELECTORS &&
        file.hashFormat == LocalPurchasingDocumentProfileV1.HASH_FORMAT)
    check(file.byteLength in 1..(if (file.parentOriginalId == null) LocalPurchasingDocumentProfileV1.MAX_ORIGINAL_BYTES
        else LocalPurchasingDocumentProfileV1.MAX_ATTACHMENT_BYTES))
    check(file.documentId != file.parentOriginalId && file.documentId != file.correctionOf &&
        (file.parentOriginalId == null || file.correctionOf == null) &&
        (file.correctionOf == null) == (file.correctionReason == null))
    checkDocumentaryText(file.originalFileName, 192)
    checkDocumentaryText(file.supplierReferenceOriginal, 192)
    file.correctionReason?.let { checkDocumentaryText(it, 256) }
    check(file.historyOrigin.operationId != file.beginOperationId)
    return file
}

/** Validate every explicit slot and the full original before any native byte output. */
internal suspend fun reconstructDocumentaryDownload(access: GatewayAccess, file: LocalPurchasingDocumentFileV1,
    isCurrent: () -> Boolean): ByteArray {
    val bytes = ByteArray(file.byteLength)
    try {
        val count = (file.byteLength - 1) / LocalPurchasingDocumentProfileV1.CHUNK_BYTES + 1
        for (index in 0 until count) {
            if (!isCurrent()) throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
            val slot = LocalPurchasingDocumentCodecV1.chunkResult(access.read(LocalPurchasingDocumentCodecV1.chunkQuery(
                LocalPurchasingDocumentChunkQueryV1(file.documentId, index, forExport = true))).payload)
            check(slot.documentId == file.documentId && slot.byteLength == file.byteLength &&
                slot.exactBytesDigest == file.exactBytesDigest && slot.media == file.media &&
                slot.hashFormat == file.hashFormat && slot.chunkIndex == index)
            val start = index * LocalPurchasingDocumentProfileV1.CHUNK_BYTES
            val expectedLength = minOf(LocalPurchasingDocumentProfileV1.CHUNK_BYTES, bytes.size - start)
            LocalPurchasingDocumentCodecV1.decodeChunkHex(slot.chunkHex).useWiped { chunk ->
                check(slot.chunkByteLength == expectedLength && chunk.size == expectedLength && sha256(chunk) == slot.chunkDigest)
                chunk.copyInto(bytes, start)
            }
        }
        check(sha256(bytes) == file.exactBytesDigest)
        // A shared native-input snapshot validates the same byte signature, not a preview/URI.
        LocalPurchasingDocumentFileSelection.Selected(bytes, file.originalFileName, file.media).destroy()
        access.checkCurrent()
        if (!isCurrent()) throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
        return bytes
    } catch (failure: Throwable) { bytes.fill(0); throw failure }
}

/** Final protected read rechecks authoritative export grants after reconstruction/chooser waits. */
internal suspend fun revalidateDocumentaryDownload(access: GatewayAccess, file: LocalPurchasingDocumentFileV1,
    originalBytes: ByteArray) {
    check(readDocumentaryDownloadSource(access, file.documentId) == file)
    val slot = LocalPurchasingDocumentCodecV1.chunkResult(access.read(LocalPurchasingDocumentCodecV1.chunkQuery(
        LocalPurchasingDocumentChunkQueryV1(file.documentId, 0, forExport = true))).payload)
    check(slot.documentId == file.documentId && slot.byteLength == file.byteLength && slot.exactBytesDigest == file.exactBytesDigest &&
        slot.media == file.media && slot.hashFormat == file.hashFormat && slot.chunkIndex == 0)
    LocalPurchasingDocumentCodecV1.decodeChunkHex(slot.chunkHex).useWiped { bytes ->
        check(bytes.size == minOf(LocalPurchasingDocumentProfileV1.CHUNK_BYTES, file.byteLength) &&
            slot.chunkByteLength == bytes.size && sha256(bytes) == slot.chunkDigest &&
            bytes.indices.all { bytes[it] == originalBytes[it] })
    }
}

internal fun documentaryFileProblem(problem: LocalPurchasingDocumentFileProblem): LocalServiceProblem = when (problem) {
    LocalPurchasingDocumentFileProblem.NOT_BOUND -> LocalServiceProblem.NOT_IMPLEMENTED
    LocalPurchasingDocumentFileProblem.ACCESS_CHANGED -> LocalServiceProblem.SESSION_ENDED
    LocalPurchasingDocumentFileProblem.BUSY -> LocalServiceProblem.RETRY_LATER
    LocalPurchasingDocumentFileProblem.INVALID_FILE_NAME, LocalPurchasingDocumentFileProblem.UNSUPPORTED_MEDIA,
    LocalPurchasingDocumentFileProblem.TOO_LARGE, LocalPurchasingDocumentFileProblem.EMPTY_FILE -> LocalServiceProblem.INVALID_INPUT
    LocalPurchasingDocumentFileProblem.PERMISSION_DENIED -> LocalServiceProblem.DENIED
    LocalPurchasingDocumentFileProblem.ALREADY_USED -> LocalServiceProblem.UNAVAILABLE
    LocalPurchasingDocumentFileProblem.IO_FAILED, LocalPurchasingDocumentFileProblem.CLOSE_FAILED,
    LocalPurchasingDocumentFileProblem.OUTPUT_MISMATCH, LocalPurchasingDocumentFileProblem.CLEANUP_FAILED -> LocalServiceProblem.STORAGE_UNCERTAIN
}

private fun checkDocumentaryText(value: String, maximum: Int) {
    check(value.length in 1..maximum && value.isNotBlank() && value.none { it.code < 0x20 || it.code == 0x7f })
    value.encodeToByteArray(throwOnInvalidSequence = true).fill(0)
}
