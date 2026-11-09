package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** Install in the actual branch Master alongside its native original/history owner. */
public class LocalPurchasingDocumentServices {
    public val commands: List<MasterCommandHandler> = listOf(PurchasingDocumentBeginHandler(),
        PurchasingDocumentChunkHandler(), PurchasingDocumentCompleteHandler())
    public val reads: List<MasterReadHandler> = listOf(PurchasingDocumentReadHandler(), PurchasingDocumentBytesReadHandler())
    internal val history: RuntimeHistoryBusinessProducer = LocalPurchasingDocumentHistoryProducer()
}

internal fun purchasingDocumentNormalize(key: CommandKey, bytes: StoredBytes): StoredBytes = when (key) {
    LocalPurchasingDocumentProfileV1.BEGIN -> purchasingDocumentEncode(purchasingDocumentDecode(bytes, LocalBeginPurchasingDocumentV1.serializer()), LocalBeginPurchasingDocumentV1.serializer())
    LocalPurchasingDocumentProfileV1.CHUNK -> purchasingDocumentEncode(purchasingDocumentDecode(bytes, LocalWritePurchasingDocumentChunkV1.serializer()), LocalWritePurchasingDocumentChunkV1.serializer())
    LocalPurchasingDocumentProfileV1.COMPLETE -> purchasingDocumentEncode(purchasingDocumentDecode(bytes, LocalCompletePurchasingDocumentV1.serializer()), LocalCompletePurchasingDocumentV1.serializer())
    else -> error("Unsupported documentary command")
}

internal fun purchasingDocumentAction(intake: PurchasingDocumentStored?): SecurityAction =
    if ((intake?.value?.body as? PurchasingDocumentBody.Intake)?.value?.request?.parentOriginalId != null)
        SecurityAction.PURCHASING_DOCUMENT_ATTACH else SecurityAction.PURCHASING_DOCUMENT_CAPTURE

internal fun purchasingDocumentRequirement(scope: BranchScope, actorTarget: DomainId, revision: Revision,
    operationId: OperationId, digest: PayloadDigest, key: CommandKey, action: SecurityAction): AuthorizationRequirement {
    val reason = when (key) {
        LocalPurchasingDocumentProfileV1.BEGIN -> "document.begin"
        LocalPurchasingDocumentProfileV1.CHUNK -> "document.chunk"
        LocalPurchasingDocumentProfileV1.COMPLETE -> "document.complete"
        else -> error("Unsupported documentary requirement")
    }
    check(action in setOf(SecurityAction.PURCHASING_DOCUMENT_CAPTURE, SecurityAction.PURCHASING_DOCUMENT_ATTACH))
    return AuthorizationRequirement(Permission(action, PermissionVerb.PERFORM), scope,
        ActionBinding(actorTarget, revision, digest, operationId, reason))
}

internal fun purchasingDocumentReject(code: String, id: DomainId): ProposedOutcome.Reject =
    ProposedOutcome.Reject("purchasing.document.$code", purchasingDocumentResult(LocalPurchasingDocumentResultV1("purchasing.document.$code", id)))

private abstract class PurchasingDocumentHandler(final override val key: CommandKey) : MasterCommandHandler {
    override val writableKinds: Set<String> = LocalPurchasingDocumentProfileV1.kinds
    override val claimNamespaces: Set<String> = setOf(LocalPurchasingDocumentProfileV1.IDENTITY_CLAIM, LocalPurchasingDocumentProfileV1.REFERENCE_CLAIM)
    override fun normalize(input: StoredBytes): StoredBytes = purchasingDocumentNormalize(key, input)
    final override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        check(context.scope == view.scope && context.key == key &&
            semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        return prepareDocument(normalized, CurrentPurchasingDocumentReader(view), context)
    }
    protected abstract suspend fun prepareDocument(normalized: StoredBytes, reader: PurchasingDocumentReader, context: CommandContext): PreparedMutation
}

private fun purchasingDocumentPrepared(context: CommandContext, id: DomainId, revision: Revision,
    action: SecurityAction, reader: PurchasingDocumentReader,
    outcome: (PurchasingDocumentProducer, UtcInstant, Revision) -> ProposedOutcome): PreparedMutation = object : PreparedMutation() {
    override val requirement: AuthorizationRequirement = purchasingDocumentRequirement(context.scope, id, revision,
        context.operationId, context.semanticDigest, context.key, action)
    override val allowsActionApproval: Boolean = false
    override val affectedResources: Set<RecoveryResourceKey> = reader.resources + purchasingDocumentAuthResource(context.originalActorId)
    override val recoveryHoldBarrierKinds: Set<RecoveryResourceKind> = purchasingDocumentBarriers
    override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
        check(authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE && authorization.approvalId == null &&
            authorization.isBoundTo(requirement, context.originalActorId))
        return outcome(PurchasingDocumentProducer(context.operationId, context.originalActorId, context.key, context.semanticDigest),
            recordedAt, authorization.policyRevision)
    }
}

internal data class PurchasingDocumentBeginSources(
    val intake: PurchasingDocumentStored?, val file: PurchasingDocumentStored?, val orphanHead: PurchasingDocumentStored?,
    val supplier: PurchasingSupplierCut?, val parent: PurchasingDocumentStored?, val correction: PurchasingDocumentStored?,
)

internal suspend fun purchasingDocumentBeginSources(reader: PurchasingDocumentReader, request: LocalBeginPurchasingDocumentV1): PurchasingDocumentBeginSources {
    val intake = reader.row(LocalPurchasingDocumentProfileV1.INTAKE, request.documentId)
    val file = reader.file(request.documentId)
    val head = reader.row(LocalPurchasingDocumentProfileV1.ATTACHMENTS, request.documentId)
    check(file == null || intake != null)
    check(head == null || file?.value?.kind == LocalPurchasingDocumentProfileV1.ORIGINAL)
    file?.let { reader.requireFile(it) }
    val supplier = reader.supplier(request.supplierId)
    val parent = request.parentOriginalId?.let { reader.primary(it) }
    parent?.let { reader.requireFile(it) }
    val correction = request.correctionOf?.let { reader.primary(it) }
    correction?.let { reader.requireFile(it) }
    return PurchasingDocumentBeginSources(intake, file, head, supplier, parent, correction)
}

internal fun purchasingDocumentBeginOutcome(scope: BranchScope, request: LocalBeginPurchasingDocumentV1,
    sources: PurchasingDocumentBeginSources, producer: PurchasingDocumentProducer, now: UtcInstant, policyRevision: Revision): ProposedOutcome {
    val id = request.documentId
    if (sources.intake != null || sources.file != null || sources.orphanHead != null) return purchasingDocumentReject("identity_already_used", id)
    val supplier = sources.supplier
    if (supplier == null || !supplier.value.active) return purchasingDocumentReject("supplier_required", id)
    if (supplier.row.revision != request.expectedSupplierRevision) return purchasingDocumentReject("stale_revision", id)
    if (request.parentOriginalId != null && (sources.parent == null ||
            (sources.parent.value.body as PurchasingDocumentBody.Captured).intake.request.supplierId != request.supplierId))
        return purchasingDocumentReject("original_required", id)
    if (request.correctionOf != null && (sources.correction == null ||
            (sources.correction.value.body as PurchasingDocumentBody.Captured).intake.request.supplierId != request.supplierId))
        return purchasingDocumentReject("correction_source_required", id)
    val body = PurchasingDocumentBody.Intake(PurchasingDocumentIntake(request, supplier.value,
        PurchasingDocumentSupplierSource(supplier.row.revision, supplier.row.digest, supplier.actorId),
        sources.parent?.let(::purchasingDocumentSource), sources.correction?.let(::purchasingDocumentSource)))
    val change = purchasingDocumentChange(scope, producer, now, policyRevision,
        LocalPurchasingDocumentProfileV1.INTAKE, id, null, body)
    return ProposedOutcome.Apply(purchasingDocumentResult(LocalPurchasingDocumentResultV1("purchasing.document.begun", id,
        producer.operationId, LocalPurchasingDocumentStateV1.INCOMPLETE, Revision(1))), listOf(change),
        listOf(ClaimChange.Acquire(purchasingDocumentClaim(LocalPurchasingDocumentProfileV1.INTAKE, id), id)))
}

private class PurchasingDocumentBeginHandler : PurchasingDocumentHandler(LocalPurchasingDocumentProfileV1.BEGIN) {
    override suspend fun prepareDocument(normalized: StoredBytes, reader: PurchasingDocumentReader, context: CommandContext): PreparedMutation {
        val request = purchasingDocumentDecode(normalized, LocalBeginPurchasingDocumentV1.serializer())
        val sources = purchasingDocumentBeginSources(reader, request)
        val action = if (request.parentOriginalId == null) SecurityAction.PURCHASING_DOCUMENT_CAPTURE else SecurityAction.PURCHASING_DOCUMENT_ATTACH
        return purchasingDocumentPrepared(context, request.documentId, Revision(0), action, reader) { producer, now, policy ->
            purchasingDocumentBeginOutcome(context.scope, request, sources, producer, now, policy)
        }
    }
}

internal data class PurchasingDocumentChunkSources(val intake: PurchasingDocumentStored?, val slot: PurchasingDocumentStored?)
internal suspend fun purchasingDocumentChunkSources(reader: PurchasingDocumentReader, request: LocalWritePurchasingDocumentChunkV1): PurchasingDocumentChunkSources {
    val intake = reader.row(LocalPurchasingDocumentProfileV1.INTAKE, request.documentId)
    val captured = reader.file(request.documentId)
    check(captured == null || intake != null)
    captured?.let { reader.requireFile(it) }
    val slot = if (intake == null) null else reader.row(LocalPurchasingDocumentProfileV1.SLOT, purchasingDocumentSlotId(request.documentId, request.chunkIndex))
    return PurchasingDocumentChunkSources(intake, slot)
}

internal fun purchasingDocumentChunkOutcome(scope: BranchScope, request: LocalWritePurchasingDocumentChunkV1,
    sources: PurchasingDocumentChunkSources, producer: PurchasingDocumentProducer, now: UtcInstant, policyRevision: Revision): ProposedOutcome {
    val id = request.documentId
    val intake = sources.intake ?: return purchasingDocumentReject("original_required", id)
    if (intake.value.producer.operationId != request.beginOperationId) return purchasingDocumentReject("begin_operation_mismatch", id)
    if (intake.value.producer.actorId != producer.actorId) return purchasingDocumentReject("original_actor_required", id)
    val declared = (intake.value.body as PurchasingDocumentBody.Intake).value.request
    if (request.chunkIndex !in declared.chunkDigests.indices) return purchasingDocumentReject("chunk_out_of_range", id)
    if (sources.slot != null) return purchasingDocumentReject("chunk_already_used", id)
    if (request.chunkHex.length / 2 != purchasingDocumentChunkLength(declared.byteLength, request.chunkIndex))
        return purchasingDocumentReject("chunk_length_mismatch", id)
    val bytes = purchasingDocumentHexBytes(request.chunkHex)
    val digest = try { sha256(bytes) } finally { bytes.fill(0) }
    if (digest != declared.chunkDigests[request.chunkIndex]) return purchasingDocumentReject("chunk_integrity_mismatch", id)
    val slotId = purchasingDocumentSlotId(id, request.chunkIndex)
    val change = purchasingDocumentChange(scope, producer, now, policyRevision, LocalPurchasingDocumentProfileV1.SLOT,
        slotId, null, PurchasingDocumentBody.Slot(request, digest))
    return ProposedOutcome.Apply(purchasingDocumentResult(LocalPurchasingDocumentResultV1("purchasing.document.chunk_stored", id,
        request.beginOperationId, LocalPurchasingDocumentStateV1.INCOMPLETE, Revision(1), chunkIndex = request.chunkIndex)), listOf(change),
        listOf(ClaimChange.Acquire(purchasingDocumentClaim(LocalPurchasingDocumentProfileV1.SLOT, slotId), slotId)))
}

private class PurchasingDocumentChunkHandler : PurchasingDocumentHandler(LocalPurchasingDocumentProfileV1.CHUNK) {
    override suspend fun prepareDocument(normalized: StoredBytes, reader: PurchasingDocumentReader, context: CommandContext): PreparedMutation {
        val request = purchasingDocumentDecode(normalized, LocalWritePurchasingDocumentChunkV1.serializer())
        val sources = purchasingDocumentChunkSources(reader, request)
        return purchasingDocumentPrepared(context, request.documentId, sources.intake?.row?.revision ?: Revision(0),
            purchasingDocumentAction(sources.intake), reader) { producer, now, policy ->
            purchasingDocumentChunkOutcome(context.scope, request, sources, producer, now, policy)
        }
    }
}

internal data class PurchasingDocumentCompleteSources(
    val intake: PurchasingDocumentStored?, val file: PurchasingDocumentStored?, val supplier: PurchasingSupplierCut?,
    val slots: List<PurchasingDocumentStored>, val completeDigest: PayloadDigest?, val mediaMatches: Boolean,
    val parent: PurchasingDocumentStored?, val parentHead: PurchasingDocumentStored?,
    val duplicateCandidateIds: List<DomainId>, val moreDuplicateCandidates: Boolean,
)

internal suspend fun purchasingDocumentCompleteSources(reader: PurchasingDocumentReader, request: LocalCompletePurchasingDocumentV1): PurchasingDocumentCompleteSources {
    val intake = reader.row(LocalPurchasingDocumentProfileV1.INTAKE, request.documentId)
    val file = reader.file(request.documentId)
    check(file == null || intake != null)
    file?.let { reader.requireFile(it) }
    if (intake == null) return PurchasingDocumentCompleteSources(null, null, null, emptyList(), null, false, null, null, emptyList(), false)
    val body = (intake.value.body as PurchasingDocumentBody.Intake).value
    val supplier = reader.supplier(body.request.supplierId)
    val parent = body.parentSource?.let { reader.requireSource(it) }
    parent?.let { reader.requireFile(it) }
    val head = parent?.let { reader.head(it) }
    body.correctionSource?.let { reader.requireFile(reader.requireSource(it)) }
    val (bytes, slots) = reader.bytes(intake, allowIncomplete = true)
    var digest: PayloadDigest? = null
    var media = false
    try {
        if (bytes != null) { digest = sha256(bytes); media = purchasingDocumentMediaMatches(body.request.media, bytes) }
    } finally { bytes?.fill(0) }
    val duplicates = if (parent == null) reader.duplicates(body.request) else emptyList<DomainId>() to false
    return PurchasingDocumentCompleteSources(intake, file, supplier, slots, digest, media, parent, head, duplicates.first, duplicates.second)
}

internal fun purchasingDocumentCompleteOutcome(scope: BranchScope, request: LocalCompletePurchasingDocumentV1,
    sources: PurchasingDocumentCompleteSources, producer: PurchasingDocumentProducer, now: UtcInstant, policyRevision: Revision): ProposedOutcome {
    val id = request.documentId
    val intake = sources.intake ?: return purchasingDocumentReject("original_required", id)
    if (intake.value.producer.operationId != request.beginOperationId) return purchasingDocumentReject("begin_operation_mismatch", id)
    if (intake.value.producer.actorId != producer.actorId) return purchasingDocumentReject("original_actor_required", id)
    val declaration = (intake.value.body as PurchasingDocumentBody.Intake).value
    val supplemental = declaration.request.parentOriginalId != null
    if (sources.file != null || supplemental != (request.expectedParentAttachmentsRevision != null) ||
        sources.supplier?.value?.active != true || sources.supplier.row.revision != declaration.supplierSource.revision ||
        sources.supplier.row.digest != declaration.supplierSource.digest) return purchasingDocumentReject("stale_revision", id)
    if (sources.completeDigest == null || sources.slots.size != declaration.request.chunkDigests.size)
        return purchasingDocumentReject("incomplete_original", id)
    if (sources.completeDigest != declaration.request.exactBytesDigest) return purchasingDocumentReject("integrity_mismatch", id)
    if (!sources.mediaMatches) return purchasingDocumentReject("media_mismatch", id)
    val head = sources.parentHead
    if (supplemental) {
        check(sources.parent != null && head != null)
        if (head.row.revision != request.expectedParentAttachmentsRevision) return purchasingDocumentReject("stale_revision", id)
        val attachments = head.value.body as PurchasingDocumentBody.Attachments
        if (attachments.files.size >= LocalPurchasingDocumentProfileV1.MAX_ATTACHMENT_COUNT ||
            declaration.request.byteLength > LocalPurchasingDocumentProfileV1.MAX_TOTAL_ATTACHMENT_BYTES - attachments.totalBytes)
            return purchasingDocumentReject("attachment_capacity", id)
    }
    val kind = if (supplemental) LocalPurchasingDocumentProfileV1.EVIDENCE else LocalPurchasingDocumentProfileV1.ORIGINAL
    val captured = PurchasingDocumentBody.Captured(declaration, purchasingDocumentSource(intake), intake.value.producer.actorId,
        intake.value.recordedAt, sources.slots.map(::purchasingDocumentSource), sources.duplicateCandidateIds, sources.moreDuplicateCandidates)
    val original = purchasingDocumentChange(scope, producer, now, policyRevision, kind, id, null, captured)
    val originalSource = purchasingDocumentChangeSource(original, producer.operationId)
    val headId: DomainId
    val headBody: PurchasingDocumentBody.Attachments
    if (head == null) {
        headId = id; headBody = PurchasingDocumentBody.Attachments(originalSource, null, emptyList(), 0)
    } else {
        headId = requireNotNull(sources.parent).value.id
        val before = head.value.body as PurchasingDocumentBody.Attachments
        headBody = PurchasingDocumentBody.Attachments(purchasingDocumentSource(sources.parent), purchasingDocumentSource(head),
            before.files + originalSource, before.totalBytes + declaration.request.byteLength)
    }
    val headChange = purchasingDocumentChange(scope, producer, now, policyRevision, LocalPurchasingDocumentProfileV1.ATTACHMENTS, headId, head, headBody)
    val claims = mutableListOf<ClaimChange>(ClaimChange.Acquire(purchasingDocumentClaim(kind, id), id))
    if (!supplemental) {
        claims += ClaimChange.Acquire(purchasingDocumentClaim(LocalPurchasingDocumentProfileV1.ATTACHMENTS, id), id)
        claims += ClaimChange.Acquire(purchasingDocumentReferenceClaim(scope, declaration.request), id)
    }
    val result = LocalPurchasingDocumentResultV1(if (supplemental) "purchasing.document.attachment_captured" else "purchasing.document.captured",
        id, request.beginOperationId, LocalPurchasingDocumentStateV1.CAPTURED_AT_BRANCH, Revision(1), declaration.request.byteLength,
        declaration.request.exactBytesDigest, if (supplemental) requireNotNull(head).row.revision.next() else null,
        duplicateCandidateIds = sources.duplicateCandidateIds, moreDuplicateCandidates = sources.moreDuplicateCandidates)
    return ProposedOutcome.Apply(purchasingDocumentResult(result), listOf(original, headChange).sortedWith(compareBy<AggregateChange> { it.key.kind }.thenBy { it.key.id.value }),
        claims.sortedWith(compareBy<ClaimChange> { it.key.namespace }.thenBy { it.key.key }))
}

private class PurchasingDocumentCompleteHandler : PurchasingDocumentHandler(LocalPurchasingDocumentProfileV1.COMPLETE) {
    override suspend fun prepareDocument(normalized: StoredBytes, reader: PurchasingDocumentReader, context: CommandContext): PreparedMutation {
        val request = purchasingDocumentDecode(normalized, LocalCompletePurchasingDocumentV1.serializer())
        val sources = purchasingDocumentCompleteSources(reader, request)
        return purchasingDocumentPrepared(context, request.documentId, sources.intake?.row?.revision ?: Revision(0),
            purchasingDocumentAction(sources.intake), reader) { producer, now, policy ->
            purchasingDocumentCompleteOutcome(context.scope, request, sources, producer, now, policy)
        }
    }
}
