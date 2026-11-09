package com.ukkera.pos.app.gateway.independent.documentaryclientv2

import com.ukkera.pos.app.LocalAction
import com.ukkera.pos.app.LocalCommandIntent
import com.ukkera.pos.app.LocalField
import com.ukkera.pos.app.LocalPurchasingDocumentCapture
import com.ukkera.pos.app.LocalRecordValue
import com.ukkera.pos.app.LocalScalarInput
import com.ukkera.pos.app.LocalScalarValue
import com.ukkera.pos.app.LocalText
import com.ukkera.pos.app.gateway.LocalClientIdentity
import com.ukkera.pos.app.gateway.StoredClientDocumentarySourceV1
import com.ukkera.pos.app.gateway.StoredClientIntentV1
import com.ukkera.pos.catalog.store.LocalBeginPurchasingDocumentV1
import com.ukkera.pos.catalog.store.LocalCompletePurchasingDocumentV1
import com.ukkera.pos.catalog.store.LocalNamesV1
import com.ukkera.pos.catalog.store.LocalPurchasingDocumentCodecV1
import com.ukkera.pos.catalog.store.LocalPurchasingDocumentFileV1
import com.ukkera.pos.catalog.store.LocalPurchasingDocumentHistoryOriginV1
import com.ukkera.pos.catalog.store.LocalPurchasingDocumentResultV1
import com.ukkera.pos.catalog.store.LocalPurchasingDocumentStateV1
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.LocalSessionPolicy
import com.ukkera.pos.security.LocalSessionView
import com.ukkera.pos.security.MonotonicMoment
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionGrant
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.PurchasingEvidenceMediaV1
import com.ukkera.pos.security.PurchasingEvidencePurposeV1
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.security.ThrottlePolicy
import com.ukkera.pos.types.BusinessDate
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Unchanged frozen v2 byte recipes, digests, metadata and operation IDs.
 * Identity/revision/time/session values below are explicit controlled U bindings, not
 * genuinely provisioned suppliers, grants, native history or Master authority.
 */
internal object FrozenDocumentaryClientInputsV2 {
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false }
    val fixture: JsonObject = javaClass.getResourceAsStream("/independent/documentaryclientv2/fixtures-v2.json")
        .let { requireNotNull(it).use { stream -> json.parseToJsonElement(stream.readBytes().decodeToString()).jsonObject } }
    private val metadata = fixture.getValue("metadata").jsonObject

    private fun binding(ordinal: Int): DomainId = DomainId("20000000-0000-4000-8000-${ordinal.toString().padStart(12, '0')}")
    val scope = BranchScope(binding(1), binding(2), binding(3))
    val owner = LocalClientIdentity(scope, binding(10), binding(20))
    val otherOwner = owner.copy(actorId = binding(11))
    val foreignOwner = owner.copy(scope = BranchScope(binding(4), binding(5), binding(6)))
    val supplierId = binding(30)
    val supplierRevision = Revision(1)
    val parentRevision = Revision(1)
    val controlTime = UtcInstant.parseCanonical("2026-10-21T10:00:00Z")
    val wrongFullDigest: PayloadDigest = PayloadDigest(fixture.getValue("negativeInputs").jsonObject
        .getValue("FALSE-FULL-DIGEST").jsonObject.string("exactBytesDigest"))

    val commands = setOf(CommandKey("purchasing.document.begin", 1), CommandKey("purchasing.document.chunk", 1),
        CommandKey("purchasing.document.complete", 1))

    fun document(name: String): FrozenDocument {
        val value = fixture.getValue("documents").jsonObject.getValue(name).jsonObject
        val file = file(value.string("file"))
        val ordinal = value.integer("ordinal")
        val chunks = value["initialChunkOperationIds"]?.jsonArray?.map { OperationId(it.jsonPrimitive.content) }
            ?: List(value.integer("initialChunkOperationCount")) { operation(ordinal, 100 + it) }
        return FrozenDocument(DomainId(value.string("documentId")), OperationId(value.string("beginOperationId")),
            chunks, OperationId(value.string("completeOperationId")), file,
            value["parentOriginalId"]?.jsonPrimitive?.contentOrNull?.let { parent ->
                DomainId(fixture.getValue("documents").jsonObject.getValue(parent).jsonObject.string("documentId"))
            }, value["correctionOf"]?.jsonPrimitive?.contentOrNull?.let { correction ->
                DomainId(fixture.getValue("documents").jsonObject.getValue(correction).jsonObject.string("documentId"))
            }, value["correctionReason"]?.jsonPrimitive?.contentOrNull)
    }

    /** Frozen rule for unused ordinals in DI2-004/027; never a native result/origin input. */
    fun document(ordinal: Int, fileName: String, parent: DomainId? = null): FrozenDocument {
        val file = file(fileName)
        return FrozenDocument(DomainId("00000000-0000-4000-8000-${ordinal.toString().padStart(12, '0')}"),
            operation(ordinal, 1), List(file.slots.size) { operation(ordinal, 100 + it) }, operation(ordinal, 900), file, parent)
    }

    fun operation(ordinal: Int, step: Int): OperationId =
        OperationId("10000000-0000-4000-8000-${(ordinal * 1000 + step).toString().padStart(12, '0')}")

    fun file(name: String): FrozenFile {
        val value = fixture.getValue("files").jsonObject.getValue(name).jsonObject
        val representation = value.getValue("representation").jsonObject
        val bytes = when (representation.string("kind")) {
            "base64" -> Base64.getDecoder().decode(representation.string("value"))
            "base-plus-padding" -> {
                val repeat = unhex(representation.string("repeatHex")).single()
                file(representation.string("baseFile")).bytes + unhex(representation.string("prefixHex")) +
                    ByteArray(representation.integer("repeatCount")) { repeat } + unhex(representation.string("suffixHex"))
            }
            else -> error("Unsupported frozen fixture representation")
        }
        val slots = value.getValue("chunkRuns").jsonArray.flatMap { element ->
            val run = element.jsonObject
            List(run.integer("count")) { offset -> FrozenSlot(run.integer("firstIndex") + offset,
                run.integer("byteLength"), PayloadDigest(run.string("sha256"))) }
        }
        return FrozenFile(name, PurchasingEvidenceMediaV1.valueOf(value.string("media")), value.integer("byteLength"),
            PayloadDigest(value.string("exactBytesDigest")), slots, bytes)
    }

    fun begin(document: FrozenDocument,
        purpose: PurchasingEvidencePurposeV1 = PurchasingEvidencePurposeV1.valueOf(metadata.string("purpose")),
    ): LocalBeginPurchasingDocumentV1 = LocalBeginPurchasingDocumentV1(
        document.id, supplierId, supplierRevision, metadata.string("supplierReferenceOriginal"),
        BusinessDate.parseCanonical(metadata.string("documentDate")), purpose,
        document.file.media, fileName(document.file.media), document.file.byteLength, document.file.digest,
        document.file.slots.map { it.digest }, document.parent, document.correctionOf, document.correctionReason)

    // Frozen B(d,f) requires the actual media suffix; .jpg is the controlled JPEG binding.
    private fun fileName(media: PurchasingEvidenceMediaV1): String = when (media) {
        PurchasingEvidenceMediaV1.PDF -> metadata.string("originalFileName")
        PurchasingEvidenceMediaV1.PNG -> "supplier-0007-A.png"
        PurchasingEvidenceMediaV1.JPEG -> "supplier-0007-A.jpg"
    }

    fun source(document: FrozenDocument, clientOwner: LocalClientIdentity = owner,
        parentAttachmentsRevision: Revision = parentRevision,
        purpose: PurchasingEvidencePurposeV1 = PurchasingEvidencePurposeV1.valueOf(metadata.string("purpose")),
    ): StoredClientDocumentarySourceV1 =
        StoredClientDocumentarySourceV1.prepare(clientOwner, document.beginId, begin(document, purpose), document.chunkIds,
            document.completeId, LocalCompletePurchasingDocumentV1(document.id, document.beginId,
                if (document.parent == null) null else parentAttachmentsRevision), document.file.bytes)

    fun capture(source: StoredClientDocumentarySourceV1): LocalPurchasingDocumentCapture = source.begin.let { begin ->
        LocalPurchasingDocumentCapture(begin.documentId, begin.supplierId, begin.expectedSupplierRevision,
            begin.supplierReferenceOriginal, begin.documentDate, begin.purpose, begin.media, begin.originalFileName,
            begin.parentOriginalId, source.complete.expectedParentAttachmentsRevision, begin.correctionOf, begin.correctionReason)
    }

    fun steps(source: StoredClientDocumentarySourceV1): List<MutationRequest> =
        listOf(source.beginRequest()) + source.chunkOperationIds.indices.map(source::chunkRequest) + source.completeRequest()

    fun staged(source: StoredClientDocumentarySourceV1, request: MutationRequest): StoredClientIntentV1 {
        val action = if (source.begin.parentOriginalId == null) LocalAction.PURCHASING_DOCUMENT_CAPTURE else LocalAction.PURCHASING_DOCUMENT_ATTACH
        val intent = LocalCommandIntent(request.operationId, action, 1, null, listOf(
            LocalScalarInput(LocalField.DOCUMENT_ID, LocalScalarValue.Identifier(source.documentId)),
            LocalScalarInput(LocalField.PURCHASING_DOCUMENT_BODY_DIGEST,
                LocalScalarValue.Text(hash(request.payload.copyBytes())))))
        return StoredClientIntentV1.staged(source.owner, intent, request,
            LocalText("Documentary transfer", "نقل مستندي"),
            listOf(LocalRecordValue(LocalText("Document", "المستند"), LocalText(source.documentId.value, source.documentId.value))))
    }

    /** Frozen success codes/states; all envelope revisions/history are controlled receiver data. */
    fun acceptedBody(source: StoredClientDocumentarySourceV1, request: MutationRequest): LocalPurchasingDocumentResultV1 =
        when (request.command.name) {
            "purchasing.document.begin" -> LocalPurchasingDocumentResultV1("purchasing.document.begun", source.documentId,
                source.beginOperationId, LocalPurchasingDocumentStateV1.INCOMPLETE, Revision(1))
            "purchasing.document.chunk" -> LocalPurchasingDocumentResultV1("purchasing.document.chunk_stored", source.documentId,
                source.beginOperationId, LocalPurchasingDocumentStateV1.INCOMPLETE, Revision(1),
                chunkIndex = LocalPurchasingDocumentCodecV1.decodeChunk(request.payload).chunkIndex)
            "purchasing.document.complete" -> LocalPurchasingDocumentResultV1(
                if (source.begin.parentOriginalId == null) "purchasing.document.captured" else "purchasing.document.attachment_captured",
                source.documentId, source.beginOperationId, LocalPurchasingDocumentStateV1.CAPTURED_AT_BRANCH, Revision(1),
                source.begin.byteLength, source.begin.exactBytesDigest,
                LocalPurchasingDocumentCodecV1.decodeComplete(request.payload).expectedParentAttachmentsRevision?.next())
            else -> error("Not a frozen documentary command")
        }

    /** Well-formed receiver specimen only; these fields do NOT qualify native Master history. */
    fun fileProjection(document: FrozenDocument,
        purpose: PurchasingEvidencePurposeV1 = PurchasingEvidencePurposeV1.valueOf(metadata.string("purpose")),
    ): LocalPurchasingDocumentFileV1 {
        val begin = begin(document, purpose)
        return LocalPurchasingDocumentFileV1(document.id, scope, Revision(1), supplierId,
            LocalNamesV1("Synthetic supplier", "مورد اصطناعي"), begin.supplierReferenceOriginal, begin.documentDate,
            begin.purpose, begin.media, begin.originalFileName, document.file.byteLength, document.file.digest,
            beginOperationId = document.beginId, originalActorId = owner.actorId, beganAt = controlTime,
            capturedAt = controlTime, historyOrigin = LocalPurchasingDocumentHistoryOriginV1(document.completeId,
                binding(50), Revision(1), JournalSequence(1), document.file.digest),
            parentOriginalId = document.parent, correctionOf = document.correctionOf, correctionReason = document.correctionReason)
    }

    fun view(clientOwner: LocalClientIdentity = owner, grants: Set<Permission> = captureReadExport): LocalSessionView =
        LocalSessionView(clientOwner.actorId, clientOwner.scope, clientOwner.deviceId, binding(40), SessionGeneration(1),
            LocalLoginIdentifier("synthetic-documentary"), ActivatedLocalPolicy(clientOwner.scope, clientOwner.actorId,
                Revision(1), grants.map { PermissionGrant(it, clientOwner.scope, GrantEffect.ALLOW) },
                LocalSessionPolicy(1), ThrottlePolicy()), MonotonicMoment(binding(41), 60_000), MonotonicMoment(binding(41), 120_000))

    val read = Permission(SecurityAction.PURCHASING_DOCUMENT_READ, PermissionVerb.PERFORM)
    val export = Permission(SecurityAction.PURCHASING_DOCUMENT_READ, PermissionVerb.EXPORT)
    val captureReadExport = setOf(Permission(SecurityAction.PURCHASING_DOCUMENT_CAPTURE, PermissionVerb.PERFORM),
        Permission(SecurityAction.PURCHASING_DOCUMENT_ATTACH, PermissionVerb.PERFORM), read, export)

    fun <T> encode(value: T, serializer: KSerializer<T>): StoredBytes = StoredBytes(json.encodeToString(serializer, value).encodeToByteArray())
    fun hash(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    fun unhex(value: String): ByteArray = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonObject.integer(key: String): Int = getValue(key).jsonPrimitive.int
}

internal data class FrozenSlot(val index: Int, val byteLength: Int, val digest: PayloadDigest)
internal data class FrozenFile(val name: String, val media: PurchasingEvidenceMediaV1, val byteLength: Int,
    val digest: PayloadDigest, val slots: List<FrozenSlot>, val bytes: ByteArray) {
    fun slot(index: Int): ByteArray {
        val start = slots.take(index).sumOf { it.byteLength }
        return bytes.copyOfRange(start, start + slots[index].byteLength)
    }
}
internal data class FrozenDocument(val id: DomainId, val beginId: OperationId, val chunkIds: List<OperationId>,
    val completeId: OperationId, val file: FrozenFile, val parent: DomainId? = null,
    val correctionOf: DomainId? = null, val correctionReason: String? = null)
