package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

private fun purchasingDocumentReadRequirement(context: ReadContext, id: DomainId, revision: Revision,
    action: SecurityAction = SecurityAction.PURCHASING_DOCUMENT_READ, verb: PermissionVerb = PermissionVerb.PERFORM,
    owner: DomainId? = null): AuthorizationRequirement = AuthorizationRequirement(Permission(action, verb), context.scope,
    ActionBinding(id, revision, context.semanticDigest, null,
        if (action != SecurityAction.PURCHASING_DOCUMENT_READ) "document.pending" else if (context.key == LocalPurchasingDocumentProfileV1.BYTES) "document.bytes" else "document.read"),
    owner = owner?.let(OwnerConstraint::OwnerPrivate) ?: OwnerConstraint.Unrestricted)

private fun purchasingDocumentPreparedRead(context: ReadContext, required: List<AuthorizationRequirement>,
    privateOriginActor: DomainId? = null, project: () -> StoredBytes): PreparedRead = object : PreparedRead() {
    override val requirements: List<AuthorizationRequirement> = required.toList()
    override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes {
        check(authorizations.size == requirements.size && authorizations.indices.all { index ->
            authorizations[index].principalKind == PrincipalKind.LOCAL_EMPLOYEE &&
                authorizations[index].isBoundTo(requirements[index], context.actorId)
        })
        // Even ANY_OWNER is not a route to another employee's pending source or transfer identity.
        if (privateOriginActor != null && privateOriginActor != context.actorId) throw MasterReadAuthorizationDenied()
        return project()
    }
}

internal fun purchasingDocumentFileProjection(file: PurchasingDocumentStored): LocalPurchasingDocumentFileV1 {
    val captured = file.value.body as PurchasingDocumentBody.Captured
    val request = captured.intake.request
    val original = file.original
    return LocalPurchasingDocumentFileV1(file.value.id, file.value.scope, file.row.revision,
        request.supplierId, captured.intake.supplier.names, request.supplierReferenceOriginal, request.documentDate,
        request.purpose, request.media, request.originalFileName, request.byteLength, request.exactBytesDigest,
        beginOperationId = captured.intakeSource.operationId, originalActorId = captured.beginActorId, beganAt = captured.beganAt,
        capturedAt = original.recordedAt, historyOrigin = LocalPurchasingDocumentHistoryOriginV1(original.operationId, original.assignmentId,
            original.authorityEpoch, original.sequence, original.journalHash), parentOriginalId = request.parentOriginalId,
        correctionOf = request.correctionOf, correctionReason = request.correctionReason)
}

internal class PurchasingDocumentReadHandler : MasterReadHandler {
    override val key: ReadKey = LocalPurchasingDocumentProfileV1.READ
    override fun normalize(input: StoredBytes): StoredBytes = purchasingDocumentEncode(
        purchasingDocumentDecode(input, LocalPurchasingDocumentQueryV1.serializer()), LocalPurchasingDocumentQueryV1.serializer())

    override fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean {
        if (profile != LocalMasterRpcWorkflowProfile.INVOICE_MATCHING) return false
        val query = purchasingDocumentDecode(normalized, LocalPurchasingDocumentQueryV1.serializer())
        return query.documentId != null && query.pendingDocumentId == null && query.after == null && normalize(normalized) == normalized
    }

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        check(context.scope == view.scope && context.key == key && queryDigest(context.scope, context.actorId, key, normalized) == context.semanticDigest)
        val request = purchasingDocumentDecode(normalized, LocalPurchasingDocumentQueryV1.serializer())
        val reader = CurrentPurchasingDocumentReader(view)
        request.pendingDocumentId?.let { id ->
            val intake = reader.row(LocalPurchasingDocumentProfileV1.INTAKE, id)
            val captured = reader.file(id)
            check(captured == null || intake != null)
            captured?.let { reader.requireFile(it) }
            val present = mutableListOf<Int>()
            val declaration = (intake?.value?.body as? PurchasingDocumentBody.Intake)?.value?.request
            if (intake != null && declaration != null) for (index in declaration.chunkDigests.indices) {
                val slot = reader.row(LocalPurchasingDocumentProfileV1.SLOT, purchasingDocumentSlotId(id, index)) ?: continue
                val value = slot.value.body as PurchasingDocumentBody.Slot
                check(value.request.documentId == id && value.request.beginOperationId == intake.value.producer.operationId &&
                    slot.value.producer.actorId == intake.value.producer.actorId && value.digest == declaration.chunkDigests[index] &&
                    value.request.chunkIndex == index && value.request.chunkHex.length / 2 == purchasingDocumentChunkLength(declaration.byteLength, index))
                present += index
            }
            val pending = if (intake == null || declaration == null) null else LocalPurchasingDocumentPendingV1(declaration,
                intake.value.producer.operationId, intake.value.producer.actorId, intake.value.recordedAt, present,
                if (captured == null) LocalPurchasingDocumentStateV1.INCOMPLETE else LocalPurchasingDocumentStateV1.CAPTURED_AT_BRANCH)
            val requirement = purchasingDocumentReadRequirement(context, id, intake?.row?.revision ?: Revision(0),
                purchasingDocumentAction(intake), owner = intake?.value?.producer?.actorId ?: context.actorId)
            return purchasingDocumentPreparedRead(context, listOf(requirement), intake?.value?.producer?.actorId) {
                purchasingDocumentEncode(LocalPurchasingDocumentReadResultV1(pending = pending), LocalPurchasingDocumentReadResultV1.serializer(), LocalPurchasingDocumentProfileV1.MAX_OUTPUT_BYTES)
            }
        }
        request.documentId?.let { id ->
            val file = reader.file(id)
            val requirements = mutableListOf(purchasingDocumentReadRequirement(context, id, file?.row?.revision ?: Revision(0)))
            val detail = file?.let {
                reader.requireFile(it)
                val children = mutableListOf<LocalPurchasingDocumentFileV1>()
                var headRevision: Revision? = null
                if (it.value.kind == LocalPurchasingDocumentProfileV1.ORIGINAL) {
                    val head = reader.head(it)
                    headRevision = head.row.revision
                    val attachments = head.value.body as PurchasingDocumentBody.Attachments
                    var total = 0
                    for (source in attachments.files) {
                        val child = reader.requireSource(source)
                        reader.requireFile(child)
                        total += (child.value.body as PurchasingDocumentBody.Captured).intake.request.byteLength
                        requirements += purchasingDocumentReadRequirement(context, child.value.id, child.row.revision)
                        children += purchasingDocumentFileProjection(child)
                    }
                    check(total == attachments.totalBytes)
                }
                val declaration = (it.value.body as PurchasingDocumentBody.Captured).intake.request
                val duplicates = if (it.value.kind == LocalPurchasingDocumentProfileV1.ORIGINAL) reader.duplicates(declaration) else emptyList<DomainId>() to false
                LocalPurchasingDocumentIntakeViewV1(purchasingDocumentFileProjection(it), headRevision, children, duplicates.first, duplicates.second)
            }
            return purchasingDocumentPreparedRead(context, requirements) {
                purchasingDocumentEncode(LocalPurchasingDocumentReadResultV1(detail = detail), LocalPurchasingDocumentReadResultV1.serializer(), LocalPurchasingDocumentProfileV1.MAX_OUTPUT_BYTES)
            }
        }
        val page = view.aggregateKeys(LocalPurchasingDocumentProfileV1.ORIGINAL, request.after, LocalPurchasingDocumentProfileV1.PAGE_SIZE)
        val items = page.keys.map { key ->
            val file = reader.primary(key.id) ?: error("Document list original unavailable")
            reader.requireFile(file)
            purchasingDocumentFileProjection(file)
        }
        val requirements = listOf(purchasingDocumentReadRequirement(context, context.scope.branchId, Revision(0))) +
            items.map { purchasingDocumentReadRequirement(context, it.documentId, it.storageRevision) }
        return purchasingDocumentPreparedRead(context, requirements) {
            purchasingDocumentEncode(LocalPurchasingDocumentReadResultV1(items, page.nextAfter), LocalPurchasingDocumentReadResultV1.serializer(), LocalPurchasingDocumentProfileV1.MAX_OUTPUT_BYTES)
        }
    }
}

internal class PurchasingDocumentBytesReadHandler : MasterReadHandler {
    override val key: ReadKey = LocalPurchasingDocumentProfileV1.BYTES
    override fun normalize(input: StoredBytes): StoredBytes = purchasingDocumentEncode(
        purchasingDocumentDecode(input, LocalPurchasingDocumentChunkQueryV1.serializer()), LocalPurchasingDocumentChunkQueryV1.serializer())

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        check(context.scope == view.scope && context.key == key && queryDigest(context.scope, context.actorId, key, normalized) == context.semanticDigest)
        val request = purchasingDocumentDecode(normalized, LocalPurchasingDocumentChunkQueryV1.serializer())
        val reader = CurrentPurchasingDocumentReader(view)
        val file = reader.file(request.documentId)
        val required = mutableListOf(purchasingDocumentReadRequirement(context, request.documentId, file?.row?.revision ?: Revision(0)))
        if (request.forExport) required += purchasingDocumentReadRequirement(context, request.documentId,
            file?.row?.revision ?: Revision(0), verb = PermissionVerb.EXPORT)
        if (file == null) return purchasingDocumentPreparedRead(context, required) { throw MasterReadInvalidSelection() }
        val intake = reader.requireFile(file)
        val declaration = (intake.value.body as PurchasingDocumentBody.Intake).value.request
        if (request.chunkIndex !in declaration.chunkDigests.indices)
            return purchasingDocumentPreparedRead(context, required) { throw MasterReadInvalidSelection() }
        val slot = reader.row(LocalPurchasingDocumentProfileV1.SLOT, purchasingDocumentSlotId(request.documentId, request.chunkIndex))
            ?: error("Original document slot unavailable")
        val value = slot.value.body as PurchasingDocumentBody.Slot
        val result = LocalPurchasingDocumentChunkReadResultV1(file.value.id, declaration.byteLength, declaration.exactBytesDigest,
            declaration.media, LocalPurchasingDocumentProfileV1.HASH_FORMAT, request.chunkIndex,
            purchasingDocumentChunkLength(declaration.byteLength, request.chunkIndex), value.digest, value.request.chunkHex)
        return purchasingDocumentPreparedRead(context, required) {
            purchasingDocumentEncode(result, LocalPurchasingDocumentChunkReadResultV1.serializer(), LocalPurchasingDocumentProfileV1.MAX_OUTPUT_BYTES)
        }
    }
}
