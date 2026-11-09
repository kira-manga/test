package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.LocalAction
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

internal fun documentaryTarget(request: MutationRequest): DomainId = when (request.command) {
    LocalPurchasingDocumentProfileV1.BEGIN -> LocalPurchasingDocumentCodecV1.decodeBegin(request.payload).documentId
    LocalPurchasingDocumentProfileV1.CHUNK -> LocalPurchasingDocumentCodecV1.decodeChunk(request.payload).documentId
    LocalPurchasingDocumentProfileV1.COMPLETE -> LocalPurchasingDocumentCodecV1.decodeComplete(request.payload).documentId
    else -> error("Unsupported documentary step")
}

/** Decode only the closed result shape belonging to this exact original transfer step. */
internal fun validateDocumentaryStepResult(request: MutationRequest, actorId: DomainId,
    action: LocalAction, reply: MutationReply.Recorded): LocalPurchasingDocumentResultV1 {
    check(actionMatches(action, request))
    check(reply.operationId == request.operationId && reply.originalActorId == actorId && reply.physicalStart == null)
    val value = LocalPurchasingDocumentCodecV1.result(reply.result)
    val documentId = documentaryTarget(request)
    check(value.documentId == documentId)
    if (!reply.committed) {
        val allowed = when (request.command) {
            LocalPurchasingDocumentProfileV1.BEGIN -> setOf("identity_already_used", "supplier_required", "stale_revision",
                "original_required", "correction_source_required")
            LocalPurchasingDocumentProfileV1.CHUNK -> setOf("original_required", "begin_operation_mismatch", "original_actor_required",
                "chunk_out_of_range", "chunk_already_used", "chunk_length_mismatch", "chunk_integrity_mismatch")
            LocalPurchasingDocumentProfileV1.COMPLETE -> setOf("original_required", "begin_operation_mismatch", "original_actor_required",
                "stale_revision", "incomplete_original", "integrity_mismatch", "media_mismatch", "attachment_capacity")
            else -> error("Unsupported documentary step")
        }
        check(value.code in allowed.map { "purchasing.document.$it" } && reply.code == value.code)
        check(value == LocalPurchasingDocumentResultV1(value.code, documentId))
        return value
    }
    check(reply.code == "committed")
    val expected = when (request.command) {
        LocalPurchasingDocumentProfileV1.BEGIN -> LocalPurchasingDocumentResultV1("purchasing.document.begun", documentId,
            request.operationId, LocalPurchasingDocumentStateV1.INCOMPLETE, Revision(1))
        LocalPurchasingDocumentProfileV1.CHUNK -> LocalPurchasingDocumentCodecV1.decodeChunk(request.payload).let { chunk ->
            LocalPurchasingDocumentResultV1("purchasing.document.chunk_stored", documentId,
                chunk.beginOperationId, LocalPurchasingDocumentStateV1.INCOMPLETE, Revision(1), chunkIndex = chunk.chunkIndex)
        }
        LocalPurchasingDocumentProfileV1.COMPLETE -> LocalPurchasingDocumentCodecV1.decodeComplete(request.payload).let { completion ->
            val attachment = action == LocalAction.PURCHASING_DOCUMENT_ATTACH
            check(attachment == (completion.expectedParentAttachmentsRevision != null))
            check(requireNotNull(value.byteLength) in 1..(if (attachment) LocalPurchasingDocumentProfileV1.MAX_ATTACHMENT_BYTES
                else LocalPurchasingDocumentProfileV1.MAX_ORIGINAL_BYTES) && value.exactBytesDigest != null)
            check(value.duplicateCandidateIds.size <= LocalPurchasingDocumentProfileV1.MAX_DUPLICATE_CANDIDATES &&
                value.duplicateCandidateIds.distinct() == value.duplicateCandidateIds &&
                value.duplicateCandidateIds.sortedBy { it.value } == value.duplicateCandidateIds &&
                documentId !in value.duplicateCandidateIds)
            check(!attachment || value.duplicateCandidateIds.isEmpty() && !value.moreDuplicateCandidates)
            LocalPurchasingDocumentResultV1(if (attachment) "purchasing.document.attachment_captured" else "purchasing.document.captured",
                documentId, completion.beginOperationId, LocalPurchasingDocumentStateV1.CAPTURED_AT_BRANCH, Revision(1),
                value.byteLength, value.exactBytesDigest, completion.expectedParentAttachmentsRevision?.next(),
                duplicateCandidateIds = value.duplicateCandidateIds, moreDuplicateCandidates = value.moreDuplicateCandidates)
        }
        else -> error("Unsupported documentary step")
    }
    check(value == expected)
    return value
}

/** A successful completion must also match the complete original bytes in private custody. */
internal fun validateDocumentaryTransferResult(source: StoredClientDocumentarySourceV1,
    request: MutationRequest, reply: MutationReply.Recorded): LocalPurchasingDocumentResultV1 {
    source.validate(source.owner)
    val original = requireNotNull(source.request(request.operationId))
    check(original.operationId == request.operationId && original.command == request.command &&
        original.payload == request.payload && original.actionApprovalId == request.actionApprovalId)
    val action = if (source.begin.parentOriginalId == null) LocalAction.PURCHASING_DOCUMENT_CAPTURE else LocalAction.PURCHASING_DOCUMENT_ATTACH
    val value = validateDocumentaryStepResult(request, source.owner.actorId, action, reply)
    if (reply.committed && request.command == LocalPurchasingDocumentProfileV1.COMPLETE)
        check(value.byteLength == source.begin.byteLength && value.exactBytesDigest == source.begin.exactBytesDigest)
    return value
}
