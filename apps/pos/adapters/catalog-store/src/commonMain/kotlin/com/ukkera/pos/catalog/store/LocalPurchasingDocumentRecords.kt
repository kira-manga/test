package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class PurchasingDocumentProducer(
    val operationId: OperationId, val actorId: DomainId, val commandName: String,
    val commandVersion: Int, val semanticDigest: PayloadDigest,
) {
    constructor(operationId: OperationId, actorId: DomainId, command: CommandKey, semanticDigest: PayloadDigest) :
        this(operationId, actorId, command.name, command.version, semanticDigest)
    val command: CommandKey get() = CommandKey(commandName, commandVersion)
}

@Serializable
internal data class PurchasingDocumentSource(
    val kind: String, val id: DomainId, val revision: Revision, val digest: PayloadDigest, val operationId: OperationId,
)

@Serializable
internal data class PurchasingDocumentSupplierSource(val revision: Revision, val digest: PayloadDigest, val actorId: DomainId)

@Serializable
internal data class PurchasingDocumentIntake(
    val request: LocalBeginPurchasingDocumentV1,
    val supplier: LocalSupplierV1,
    val supplierSource: PurchasingDocumentSupplierSource,
    val parentSource: PurchasingDocumentSource? = null,
    val correctionSource: PurchasingDocumentSource? = null,
)

@Serializable
internal sealed interface PurchasingDocumentBody {
    @Serializable @SerialName("INTAKE") data class Intake(val value: PurchasingDocumentIntake) : PurchasingDocumentBody
    @Serializable @SerialName("SLOT") data class Slot(
        val request: LocalWritePurchasingDocumentChunkV1, val digest: PayloadDigest,
    ) : PurchasingDocumentBody
    @Serializable @SerialName("CAPTURED") data class Captured(
        val intake: PurchasingDocumentIntake, val intakeSource: PurchasingDocumentSource,
        val beginActorId: DomainId, val beganAt: UtcInstant, val slotSources: List<PurchasingDocumentSource>,
        val duplicateCandidateIds: List<DomainId> = emptyList(), val moreDuplicateCandidates: Boolean = false,
    ) : PurchasingDocumentBody
    @Serializable @SerialName("ATTACHMENTS") data class Attachments(
        val originalSource: PurchasingDocumentSource, val previousSource: PurchasingDocumentSource?,
        val files: List<PurchasingDocumentSource>, val totalBytes: Int,
    ) : PurchasingDocumentBody
}

@Serializable
internal data class PurchasingDocumentRecord(
    val scope: BranchScope, val kind: String, val id: DomainId, val revision: Revision,
    val producer: PurchasingDocumentProducer, val recordedAt: UtcInstant, val policyRevision: Revision,
    val body: PurchasingDocumentBody,
    val profile: String = LocalPurchasingDocumentProfileV1.PROFILE,
    val hashFormat: String = LocalPurchasingDocumentProfileV1.HASH_FORMAT,
    val qualification: LocalPurchasingPolicyQualificationV1 = LocalPurchasingPolicyQualificationV1.UNQUALIFIED_SELECTORS,
) { override fun toString(): String = "PurchasingDocumentRecord([UNQUALIFIED, REDACTED])" }

internal data class PurchasingDocumentStored(
    val row: StoredAggregate, val value: PurchasingDocumentRecord, val original: RetainedCommandEvidence,
)

internal fun purchasingDocumentKey(kind: String, id: DomainId): AggregateKey = AggregateKey(kind, id)
internal fun purchasingDocumentClaim(kind: String, id: DomainId): ClaimKey =
    ClaimKey(LocalPurchasingDocumentProfileV1.IDENTITY_CLAIM, "$kind/${id.value}")
internal fun purchasingDocumentSource(value: PurchasingDocumentStored): PurchasingDocumentSource = PurchasingDocumentSource(
    value.row.key.kind, value.row.key.id, value.row.revision, value.row.digest, value.value.producer.operationId)
internal fun purchasingDocumentSlotId(id: DomainId, index: Int): DomainId {
    val bytes = "purchasing-document-slot/v1/${id.value}/$index".encodeToByteArray()
    val hex = try { sha256(bytes).value.take(32) } finally { bytes.fill(0) }
    return DomainId("${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20, 32)}")
}

internal fun purchasingDocumentReferencePrefix(scope: BranchScope, request: LocalBeginPurchasingDocumentV1): String {
    val value = purchasingDocumentEncode(listOf(LocalPurchasingDocumentProfileV1.REFERENCE_GROUP_FORMAT,
        scope.organizationId.value, scope.companyId.value, scope.branchId.value, request.supplierId.value,
        request.supplierReferenceOriginal, request.documentDate?.canonicalString().orEmpty()), kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()))
    val bytes = value.copyBytes()
    return try { sha256(bytes).value + "/" } finally { bytes.fill(0) }
}
internal fun purchasingDocumentReferenceClaim(scope: BranchScope, request: LocalBeginPurchasingDocumentV1): ClaimKey =
    ClaimKey(LocalPurchasingDocumentProfileV1.REFERENCE_CLAIM, purchasingDocumentReferencePrefix(scope, request) + request.documentId.value)
internal fun purchasingDocumentResource(id: DomainId): RecoveryResourceKey = RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, id)
internal fun purchasingDocumentAuthResource(id: DomainId): RecoveryResourceKey = RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, id)
internal val purchasingDocumentBarriers: Set<RecoveryResourceKind> = setOf(RecoveryResourceKind.AUTHORIZATION, RecoveryResourceKind.PURCHASE_CUSTODY)

internal fun purchasingDocumentChange(
    scope: BranchScope, producer: PurchasingDocumentProducer, now: UtcInstant, policyRevision: Revision,
    kind: String, id: DomainId, before: PurchasingDocumentStored?, body: PurchasingDocumentBody,
): AggregateChange = AggregateChange(purchasingDocumentKey(kind, id), before?.row?.revision,
    LocalPurchasingDocumentProfileV1.schema(kind), purchasingDocumentEncode(PurchasingDocumentRecord(scope, kind, id,
        (before?.row?.revision ?: Revision(0)).next(), producer, now, policyRevision, body),
        PurchasingDocumentRecord.serializer(), LocalPurchasingDocumentProfileV1.MAX_RECORD_BYTES))

internal fun purchasingDocumentChangeSource(change: AggregateChange, operationId: OperationId): PurchasingDocumentSource {
    val bytes = change.payload.copyBytes()
    return try { PurchasingDocumentSource(change.key.kind, change.key.id, (change.expectedRevision ?: Revision(0)).next(), sha256(bytes), operationId) }
    finally { bytes.fill(0) }
}

internal fun purchasingDocumentResult(value: LocalPurchasingDocumentResultV1): StoredBytes =
    purchasingDocumentEncode(value, LocalPurchasingDocumentResultV1.serializer(), LocalPurchasingDocumentProfileV1.MAX_OUTPUT_BYTES)

internal fun purchasingDocumentDecodeRecord(scope: BranchScope, row: StoredAggregate): PurchasingDocumentRecord {
    val value = purchasingDocumentDecode(row.payload, PurchasingDocumentRecord.serializer(), LocalPurchasingDocumentProfileV1.MAX_RECORD_BYTES)
    val bytes = row.payload.copyBytes()
    try { check(sha256(bytes) == row.digest) } finally { bytes.fill(0) }
    purchasingDocumentRequireRecord(value)
    check(value.scope == scope && value.id == row.key.id && value.kind == row.key.kind && value.revision == row.revision &&
        row.schema == LocalPurchasingDocumentProfileV1.schema(row.key.kind) &&
        purchasingDocumentEncode(value, PurchasingDocumentRecord.serializer(), LocalPurchasingDocumentProfileV1.MAX_RECORD_BYTES) == row.payload)
    return value
}

internal fun purchasingDocumentRequireRecord(value: PurchasingDocumentRecord) {
    check(value.kind in LocalPurchasingDocumentProfileV1.kinds && value.revision.value >= 1 &&
        value.profile == LocalPurchasingDocumentProfileV1.PROFILE && value.hashFormat == LocalPurchasingDocumentProfileV1.HASH_FORMAT &&
        value.qualification == LocalPurchasingPolicyQualificationV1.UNQUALIFIED_SELECTORS && value.producer.command in LocalPurchasingDocumentProfileV1.commands)
    when (val body = value.body) {
        is PurchasingDocumentBody.Intake -> {
            val intake = body.value; val request = intake.request
            check(value.kind == LocalPurchasingDocumentProfileV1.INTAKE && value.id == request.documentId && value.revision == Revision(1) &&
                value.producer.command == LocalPurchasingDocumentProfileV1.BEGIN && intake.supplier.id == request.supplierId && intake.supplier.active &&
                intake.supplierSource.revision == request.expectedSupplierRevision &&
                intake.parentSource?.id == request.parentOriginalId && intake.correctionSource?.id == request.correctionOf)
            intake.parentSource?.let { check(it.kind == LocalPurchasingDocumentProfileV1.ORIGINAL && it.revision == Revision(1)) }
            intake.correctionSource?.let { check(it.kind == LocalPurchasingDocumentProfileV1.ORIGINAL && it.revision == Revision(1)) }
        }
        is PurchasingDocumentBody.Slot -> {
            check(value.kind == LocalPurchasingDocumentProfileV1.SLOT && value.revision == Revision(1) && value.producer.command == LocalPurchasingDocumentProfileV1.CHUNK &&
                value.id == purchasingDocumentSlotId(body.request.documentId, body.request.chunkIndex))
            val bytes = purchasingDocumentHexBytes(body.request.chunkHex)
            try { check(sha256(bytes) == body.digest) } finally { bytes.fill(0) }
        }
        is PurchasingDocumentBody.Captured -> {
            val request = body.intake.request
            check(value.kind == (if (request.parentOriginalId == null) LocalPurchasingDocumentProfileV1.ORIGINAL else LocalPurchasingDocumentProfileV1.EVIDENCE) &&
                value.id == request.documentId && value.revision == Revision(1) && value.producer.command == LocalPurchasingDocumentProfileV1.COMPLETE &&
                value.producer.actorId == body.beginActorId && body.beganAt <= value.recordedAt &&
                body.intakeSource.kind == LocalPurchasingDocumentProfileV1.INTAKE && body.intakeSource.id == value.id && body.intakeSource.revision == Revision(1) &&
                body.slotSources.size == request.chunkDigests.size && body.slotSources.map { it.id }.distinct().size == body.slotSources.size &&
                body.duplicateCandidateIds.size <= LocalPurchasingDocumentProfileV1.MAX_DUPLICATE_CANDIDATES &&
                body.duplicateCandidateIds.distinct().size == body.duplicateCandidateIds.size && value.id !in body.duplicateCandidateIds)
            body.slotSources.forEachIndexed { index, source -> check(source.kind == LocalPurchasingDocumentProfileV1.SLOT &&
                source.id == purchasingDocumentSlotId(value.id, index) && source.revision == Revision(1)) }
            if (request.parentOriginalId != null) check(body.duplicateCandidateIds.isEmpty() && !body.moreDuplicateCandidates)
        }
        is PurchasingDocumentBody.Attachments -> check(value.kind == LocalPurchasingDocumentProfileV1.ATTACHMENTS &&
            value.producer.command == LocalPurchasingDocumentProfileV1.COMPLETE && body.originalSource.kind == LocalPurchasingDocumentProfileV1.ORIGINAL &&
            body.originalSource.id == value.id && body.originalSource.revision == Revision(1) &&
            body.files.size <= LocalPurchasingDocumentProfileV1.MAX_ATTACHMENT_COUNT && body.files.map { it.id }.distinct().size == body.files.size &&
            body.files.all { it.kind == LocalPurchasingDocumentProfileV1.EVIDENCE && it.revision == Revision(1) } &&
            body.totalBytes in 0..LocalPurchasingDocumentProfileV1.MAX_TOTAL_ATTACHMENT_BYTES &&
            (body.previousSource == null) == (body.files.isEmpty() && body.totalBytes == 0 && value.revision == Revision(1)))
    }
}

/** Closed AFTER shape. Actual native BEFORE membership/authority is joined independently. */
internal fun requirePurchasingDocumentProducer(evidence: RetainedCommandEvidence) {
    check(evidence.committed && evidence.nativeOriginalProvenance == null && evidence.jobs.isEmpty() &&
        evidence.command in LocalPurchasingDocumentProfileV1.commands &&
        purchasingDocumentNormalize(evidence.command, evidence.normalized) == evidence.normalized &&
        semanticDigest(evidence.scope, evidence.originalActorId, evidence.command, evidence.normalized) == evidence.semanticDigest)
    val records = evidence.aggregateWrites.associate { write ->
        val record = purchasingDocumentDecode(write.payload, PurchasingDocumentRecord.serializer(), LocalPurchasingDocumentProfileV1.MAX_RECORD_BYTES)
        purchasingDocumentRequireRecord(record)
        val bytes = write.payload.copyBytes()
        try { check(sha256(bytes) == write.payloadDigest) } finally { bytes.fill(0) }
        check(record.scope == evidence.scope && record.kind == write.key.kind && record.id == write.key.id && record.revision == write.storageGeneration &&
            write.schema == LocalPurchasingDocumentProfileV1.schema(record.kind) &&
            purchasingDocumentEncode(record, PurchasingDocumentRecord.serializer(), LocalPurchasingDocumentProfileV1.MAX_RECORD_BYTES) == write.payload &&
            record.producer == PurchasingDocumentProducer(evidence.operationId, evidence.originalActorId, evidence.command, evidence.semanticDigest) &&
            record.recordedAt == evidence.recordedAt && record.policyRevision == evidence.policyRevision)
        write.key to record
    }
    check(records.size == evidence.aggregateWrites.size)
    val claims = mutableListOf<ClaimChange>()
    val result = when (evidence.command) {
        LocalPurchasingDocumentProfileV1.BEGIN -> {
            val request = purchasingDocumentDecode(evidence.normalized, LocalBeginPurchasingDocumentV1.serializer())
            check(records.size == 1)
            val record = records[purchasingDocumentKey(LocalPurchasingDocumentProfileV1.INTAKE, request.documentId)] ?: error("Document Begin original lost")
            check((record.body as PurchasingDocumentBody.Intake).value.request == request)
            claims += ClaimChange.Acquire(purchasingDocumentClaim(record.kind, record.id), record.id)
            LocalPurchasingDocumentResultV1("purchasing.document.begun", request.documentId, evidence.operationId,
                LocalPurchasingDocumentStateV1.INCOMPLETE, Revision(1))
        }
        LocalPurchasingDocumentProfileV1.CHUNK -> {
            val request = purchasingDocumentDecode(evidence.normalized, LocalWritePurchasingDocumentChunkV1.serializer())
            check(records.size == 1)
            val record = records[purchasingDocumentKey(LocalPurchasingDocumentProfileV1.SLOT, purchasingDocumentSlotId(request.documentId, request.chunkIndex))]
                ?: error("Document slot original lost")
            check((record.body as PurchasingDocumentBody.Slot).request == request)
            claims += ClaimChange.Acquire(purchasingDocumentClaim(record.kind, record.id), record.id)
            LocalPurchasingDocumentResultV1("purchasing.document.chunk_stored", request.documentId, request.beginOperationId,
                LocalPurchasingDocumentStateV1.INCOMPLETE, Revision(1), chunkIndex = request.chunkIndex)
        }
        LocalPurchasingDocumentProfileV1.COMPLETE -> {
            val request = purchasingDocumentDecode(evidence.normalized, LocalCompletePurchasingDocumentV1.serializer())
            check(records.size == 2)
            val file = records.values.single { it.body is PurchasingDocumentBody.Captured }
            val body = file.body as PurchasingDocumentBody.Captured
            check(file.id == request.documentId && body.intakeSource.operationId == request.beginOperationId)
            val head = records.values.single { it.body is PurchasingDocumentBody.Attachments }
            val attachments = head.body as PurchasingDocumentBody.Attachments
            val fileWrite = evidence.aggregateWrites.single { it.key.id == file.id && it.key.kind == file.kind }
            val fileSource = PurchasingDocumentSource(file.kind, file.id, file.revision, fileWrite.payloadDigest, evidence.operationId)
            claims += ClaimChange.Acquire(purchasingDocumentClaim(file.kind, file.id), file.id)
            if (body.intake.request.parentOriginalId == null) {
                check(request.expectedParentAttachmentsRevision == null && head.id == file.id && attachments.originalSource == fileSource &&
                    attachments.previousSource == null && attachments.files.isEmpty() && attachments.totalBytes == 0)
                claims += ClaimChange.Acquire(purchasingDocumentClaim(head.kind, head.id), head.id)
                claims += ClaimChange.Acquire(purchasingDocumentReferenceClaim(evidence.scope, body.intake.request), file.id)
            } else {
                check(head.id == body.intake.request.parentOriginalId && attachments.previousSource?.kind == LocalPurchasingDocumentProfileV1.ATTACHMENTS &&
                    attachments.previousSource.id == head.id && attachments.previousSource.revision == request.expectedParentAttachmentsRevision &&
                    head.revision == requireNotNull(request.expectedParentAttachmentsRevision).next() && attachments.files.last() == fileSource)
            }
            LocalPurchasingDocumentResultV1(if (body.intake.request.parentOriginalId == null) "purchasing.document.captured" else "purchasing.document.attachment_captured",
                file.id, request.beginOperationId, LocalPurchasingDocumentStateV1.CAPTURED_AT_BRANCH, Revision(1), body.intake.request.byteLength,
                body.intake.request.exactBytesDigest, if (body.intake.request.parentOriginalId == null) null else head.revision,
                duplicateCandidateIds = body.duplicateCandidateIds, moreDuplicateCandidates = body.moreDuplicateCandidates)
        }
        else -> error("Unsupported document producer")
    }
    check(evidence.result == purchasingDocumentResult(result) && evidence.claimChanges.size == claims.size &&
        evidence.claimChanges.all { retained -> retained.action == RetainedClaimAction.ACQUIRE &&
            claims.singleOrNull { it.key == retained.key }?.ownerId == retained.ownerId })
}

/** Typed sources only. Historical observations use genuine native BEFORE pairings directly. */
internal abstract class PurchasingDocumentReader(val scope: BranchScope) {
    val resources: MutableSet<RecoveryResourceKey> = mutableSetOf(purchasingDocumentAuthResource(scope.branchId))
    private val cache: MutableMap<AggregateKey, PurchasingDocumentStored?> = mutableMapOf()
    protected abstract suspend fun selected(key: AggregateKey): Pair<StoredAggregate, RetainedCommandEvidence>?
    abstract suspend fun claim(key: ClaimKey): DomainId?
    protected abstract suspend fun referenceKeys(prefix: String): Pair<List<ClaimKey>, Boolean>
    protected abstract suspend fun supplierRow(id: DomainId): StoredAggregate?
    protected abstract suspend fun original(operationId: OperationId): RetainedCommandEvidence?

    suspend fun row(kind: String, id: DomainId): PurchasingDocumentStored? {
        val key = purchasingDocumentKey(kind, id)
        if (cache.containsKey(key)) return cache[key]
        resources += purchasingDocumentResource(id)
        val selected = selected(key)
        val owner = claim(purchasingDocumentClaim(kind, id))
        if (selected == null) { check(owner == null); cache[key] = null; return null }
        check(owner == id)
        val (row, original) = selected
        val value = purchasingDocumentDecodeRecord(scope, row)
        requirePurchasingDocumentProducer(original)
        check(original.operationId == value.producer.operationId)
        val write = original.aggregateWrites.singleOrNull { it.key == key } ?: error("Document producer row unavailable")
        check(write.storageGeneration == row.revision && write.schema == row.schema && write.payload == row.payload && write.payloadDigest == row.digest)
        resources += purchasingDocumentAuthResource(value.producer.actorId)
        return PurchasingDocumentStored(row, value, original).also { cache[key] = it }
    }

    suspend fun supplier(id: DomainId): PurchasingSupplierCut? {
        resources += RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT, id)
        resources += purchasingDocumentAuthResource(id)
        val row = supplierRow(id) ?: return null
        val bytes = row.payload.copyBytes()
        try { check(sha256(bytes) == row.digest) } finally { bytes.fill(0) }
        val value = decodeRecord(scope, row)
        val supplier = (value.body as? ConfigurationBody.Supplier)?.value ?: error("Document supplier source unavailable")
        check(supplier.id == id)
        resources += purchasingDocumentAuthResource(value.authorizedBy)
        return PurchasingSupplierCut(row, supplier, value.authorizedBy)
    }

    suspend fun file(id: DomainId): PurchasingDocumentStored? {
        val primary = row(LocalPurchasingDocumentProfileV1.ORIGINAL, id)
        val supplemental = row(LocalPurchasingDocumentProfileV1.EVIDENCE, id)
        check(primary == null || supplemental == null)
        return primary ?: supplemental
    }

    suspend fun primary(id: DomainId): PurchasingDocumentStored? = row(LocalPurchasingDocumentProfileV1.ORIGINAL, id)

    suspend fun requireSource(source: PurchasingDocumentSource): PurchasingDocumentStored {
        val value = row(source.kind, source.id) ?: error("Document source unavailable")
        check(purchasingDocumentSource(value) == source)
        return value
    }

    suspend fun bytes(intake: PurchasingDocumentStored, allowIncomplete: Boolean = false): Pair<ByteArray?, List<PurchasingDocumentStored>> {
        val body = intake.value.body as PurchasingDocumentBody.Intake
        val request = body.value.request
        val result = ByteArray(request.byteLength)
        val slots = mutableListOf<PurchasingDocumentStored>()
        var missing = false
        try {
            for (index in request.chunkDigests.indices) {
                val slot = row(LocalPurchasingDocumentProfileV1.SLOT, purchasingDocumentSlotId(request.documentId, index))
                if (slot == null) {
                    if (!allowIncomplete) error("Captured document slot unavailable")
                    missing = true
                    continue
                }
                val value = slot.value.body as PurchasingDocumentBody.Slot
                check(value.request.documentId == request.documentId && value.request.beginOperationId == intake.value.producer.operationId &&
                    value.request.chunkIndex == index && slot.value.producer.actorId == intake.value.producer.actorId &&
                    value.digest == request.chunkDigests[index])
                val chunk = purchasingDocumentHexBytes(value.request.chunkHex)
                try {
                    check(chunk.size == purchasingDocumentChunkLength(request.byteLength, index) && sha256(chunk) == value.digest)
                    chunk.copyInto(result, index * LocalPurchasingDocumentProfileV1.CHUNK_BYTES)
                } finally { chunk.fill(0) }
                slots += slot
            }
            if (missing) { result.fill(0); return null to slots }
            return result to slots
        } catch (failure: Throwable) { result.fill(0); throw failure }
    }

    suspend fun requireFile(file: PurchasingDocumentStored): PurchasingDocumentStored {
        val body = file.value.body as PurchasingDocumentBody.Captured
        val intake = requireSource(body.intakeSource)
        check((intake.value.body as PurchasingDocumentBody.Intake).value == body.intake && intake.value.producer.actorId == body.beginActorId &&
            intake.value.recordedAt == body.beganAt)
        val (bytes, slots) = bytes(intake)
        try { check(bytes != null && sha256(bytes) == body.intake.request.exactBytesDigest && purchasingDocumentMediaMatches(body.intake.request.media, bytes)) }
        finally { bytes?.fill(0) }
        check(slots.map(::purchasingDocumentSource) == body.slotSources)
        body.intake.parentSource?.let { source -> requireSource(source) }
        body.intake.correctionSource?.let { source -> requireSource(source) }
        return intake
    }

    suspend fun head(parent: PurchasingDocumentStored): PurchasingDocumentStored {
        check(parent.value.kind == LocalPurchasingDocumentProfileV1.ORIGINAL)
        val head = row(LocalPurchasingDocumentProfileV1.ATTACHMENTS, parent.value.id) ?: error("Original attachment head unavailable")
        var value = head.value
        var body = value.body as PurchasingDocumentBody.Attachments
        val parentSource = purchasingDocumentSource(parent)
        check(body.originalSource == parentSource)
        var iterations = 0
        while (body.previousSource != null) {
            check(++iterations <= LocalPurchasingDocumentProfileV1.MAX_ATTACHMENT_COUNT)
            val previousSource = requireNotNull(body.previousSource)
            val evidence = original(previousSource.operationId) ?: error("Attachment predecessor original unavailable")
            requirePurchasingDocumentProducer(evidence)
            val write = evidence.aggregateWrites.singleOrNull { it.key == purchasingDocumentKey(previousSource.kind, previousSource.id) }
                ?: error("Attachment predecessor row unavailable")
            check(write.storageGeneration == previousSource.revision && write.payloadDigest == previousSource.digest &&
                write.schema == LocalPurchasingDocumentProfileV1.schema(previousSource.kind))
            val previous = purchasingDocumentDecode(write.payload, PurchasingDocumentRecord.serializer(), LocalPurchasingDocumentProfileV1.MAX_RECORD_BYTES)
            purchasingDocumentRequireRecord(previous)
            check(previous.scope == scope && previous.producer.operationId == previousSource.operationId && previous.id == parent.value.id &&
                previous.revision.next() == value.revision)
            val previousBody = previous.body as PurchasingDocumentBody.Attachments
            check(previousBody.originalSource == parentSource && body.files.size == previousBody.files.size + 1 &&
                body.files.dropLast(1) == previousBody.files)
            val child = requireSource(body.files.last())
            val childBody = child.value.body as PurchasingDocumentBody.Captured
            check(childBody.intake.request.parentOriginalId == parent.value.id && childBody.intake.parentSource == parentSource &&
                child.value.producer == value.producer && body.totalBytes == previousBody.totalBytes + childBody.intake.request.byteLength)
            resources += purchasingDocumentAuthResource(previous.producer.actorId)
            value = previous; body = previousBody
        }
        check(value.producer == parent.value.producer && body.files.isEmpty() && body.totalBytes == 0 && value.revision == Revision(1))
        return head
    }

    suspend fun duplicates(request: LocalBeginPurchasingDocumentV1): Pair<List<DomainId>, Boolean> {
        val (keys, more) = referenceKeys(purchasingDocumentReferencePrefix(scope, request))
        val ids = mutableListOf<DomainId>()
        for (key in keys) {
            val id = claim(key) ?: error("Document duplicate locator unavailable")
            val file = primary(id) ?: error("Document duplicate original unavailable")
            val original = (file.value.body as PurchasingDocumentBody.Captured).intake.request
            check(original.parentOriginalId == null && purchasingDocumentReferenceClaim(scope, original) == key)
            if (id != request.documentId) ids += id
        }
        return ids.take(LocalPurchasingDocumentProfileV1.MAX_DUPLICATE_CANDIDATES) to
            (more || ids.size > LocalPurchasingDocumentProfileV1.MAX_DUPLICATE_CANDIDATES)
    }
}

internal class CurrentPurchasingDocumentReader(private val view: CommandReadView) : PurchasingDocumentReader(view.scope) {
    override suspend fun selected(key: AggregateKey): Pair<StoredAggregate, RetainedCommandEvidence>? {
        val row = view.aggregate(key) ?: return null
        val value = purchasingDocumentDecodeRecord(scope, row)
        return row to (view.retainedCommand(value.producer.operationId) ?: error("Document original unavailable"))
    }
    override suspend fun claim(key: ClaimKey): DomainId? = view.claim(key)
    override suspend fun supplierRow(id: DomainId): StoredAggregate? = view.aggregate(LocalConfigurationProfile.key(LocalConfigurationProfile.SUPPLIER, id))
    override suspend fun original(operationId: OperationId): RetainedCommandEvidence? = view.retainedCommand(operationId)
    override suspend fun referenceKeys(prefix: String): Pair<List<ClaimKey>, Boolean> {
        val page = view.claimKeys(LocalPurchasingDocumentProfileV1.REFERENCE_CLAIM, prefix, limit = LocalPurchasingDocumentProfileV1.MAX_DUPLICATE_CANDIDATES + 1)
        return page.entries.map { it.key } to (page.nextAfter != null)
    }
}

internal class HistoricalPurchasingDocumentReader(private val command: RuntimeHistoricalCommand) : PurchasingDocumentReader(command.scope) {
    override suspend fun selected(key: AggregateKey): Pair<StoredAggregate, RetainedCommandEvidence>? {
        val selected = command.aggregateSource(key) ?: return null
        return selected.row to ((selected.original as? RetainedOriginalV2.Legacy)?.evidence ?: error("Unsupported document historical original"))
    }
    override suspend fun claim(key: ClaimKey): DomainId? = command.claim(key)
    override suspend fun supplierRow(id: DomainId): StoredAggregate? = command.aggregateSource(LocalConfigurationProfile.key(LocalConfigurationProfile.SUPPLIER, id))?.row
    override suspend fun original(operationId: OperationId): RetainedCommandEvidence? =
        (command.earlierOriginal(operationId) as? RetainedOriginalV2.Legacy)?.evidence
    override suspend fun referenceKeys(prefix: String): Pair<List<ClaimKey>, Boolean> {
        val keys = command.claimKeys(LocalPurchasingDocumentProfileV1.REFERENCE_CLAIM, prefix).sortedBy { it.key }
        return keys.take(LocalPurchasingDocumentProfileV1.MAX_DUPLICATE_CANDIDATES + 1) to
            (keys.size > LocalPurchasingDocumentProfileV1.MAX_DUPLICATE_CANDIDATES + 1)
    }
}

/** Bounded file signatures classify captured bytes only; no authenticity or rendering claim. */
internal fun purchasingDocumentMediaMatches(media: PurchasingEvidenceMediaV1, bytes: ByteArray): Boolean = when (media) {
    PurchasingEvidenceMediaV1.JPEG -> bytes.size >= 4 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() &&
        bytes[bytes.lastIndex - 1] == 0xff.toByte() && bytes.last() == 0xd9.toByte()
    PurchasingEvidenceMediaV1.PNG -> {
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        bytes.size >= signature.size && signature.indices.all { bytes[it] == signature[it] }
    }
    PurchasingEvidenceMediaV1.PDF -> bytes.size >= 8 && byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2d).indices.all {
        bytes[it] == byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2d)[it]
    } && bytes[5] in byteArrayOf(0x31, 0x32) && bytes[6] == 0x2e.toByte() && bytes[7] in 0x30.toByte()..0x39.toByte()
}
