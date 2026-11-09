package com.ukkera.pos.app.independent.documentaryclientv2

import com.ukkera.pos.app.LocalPurchasingDocumentCapture
import com.ukkera.pos.app.LocalPurchasingDocumentDraft
import com.ukkera.pos.app.LocalPurchasingDocumentFileSelection
import com.ukkera.pos.app.LocalPurchasingDocumentKind
import com.ukkera.pos.app.LocalPurchasingDocumentSelectedFile
import com.ukkera.pos.app.LocalPurchasingDocumentSourceSummary
import com.ukkera.pos.security.PurchasingEvidenceMediaV1
import com.ukkera.pos.security.PurchasingEvidencePurposeV1
import com.ukkera.pos.types.BusinessDate
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Unchanged DI2 v2 inputs, expanded for shared presentation tests only. */
internal object FrozenDocumentaryClientV2Inputs {
    private val fixture: JsonObject = checkNotNull(javaClass.getResourceAsStream(
        "/independent/documentaryclientv2/fixtures-v2.json",
    )).use { input -> Json.parseToJsonElement(input.readBytes().decodeToString()).jsonObject }.also {
        check(it.text("fixtureVersion") == "purchasing-documentary-intake-v2")
        check(it.text("applicationExecutionStatus") == "NOT_RUN")
    }

    // Presentation-only bindings for the named synthetic fixture. These are not native
    // identity, supplier, session, grant or history provisioning and prove no N-layer fact.
    val companyId = DomainId("20000000-0000-4000-8000-000000000001")
    val branchId = DomainId("20000000-0000-4000-8000-000000000002")
    val staffA = DomainId("20000000-0000-4000-8000-000000000003")
    val staffX = DomainId("20000000-0000-4000-8000-000000000004")
    val supplierId = DomainId("20000000-0000-4000-8000-000000000005")
    val supplierRevision = Revision(1)
    val parentRevision = Revision(1)

    private val metadata: JsonObject get() = fixture.getValue("metadata").jsonObject
    private val documents: JsonObject get() = fixture.getValue("documents").jsonObject
    private val files: JsonObject get() = fixture.getValue("files").jsonObject
    val supplierReference: String get() = metadata.text("supplierReferenceOriginal")
    val documentDate: String get() = metadata.text("documentDate")
    val originalFileName: String get() = metadata.text("originalFileName")
    val purposes: List<PurchasingEvidencePurposeV1> get() = fixture.getValue("profile").jsonObject
        .getValue("purposeVocabulary").jsonArray.map { PurchasingEvidencePurposeV1.valueOf(it.jsonPrimitive.content) }
    val duplicateVariants: JsonArray get() = fixture.getValue("duplicateFixture").jsonObject
        .getValue("otherCases").jsonArray

    fun textBoundary(name: String): String = fixture.getValue("textBoundaries").jsonObject.text(name)
    fun controlText(name: String): String = fixture.getValue("negativeInputs").jsonObject
        .getValue("CONTROL-TEXT").jsonObject.text(name)
    fun documentId(label: String): DomainId = DomainId(documents.getValue(label).jsonObject.text("documentId"))
    fun completeOperationId(label: String): OperationId = OperationId(documents.getValue(label).jsonObject.text("completeOperationId"))
    fun refreshedCompleteOperationId(label: String): OperationId {
        val ordinal = documents.getValue(label).jsonObject.getValue("ordinal").jsonPrimitive.int
        val step = fixture.getValue("operationIdentityRule").jsonObject.getValue("steps").jsonObject
            .getValue("newRefreshedRejectedComplete").jsonPrimitive.int
        return OperationId("10000000-0000-4000-8000-${(1000L * ordinal + step).toString().padStart(12, '0')}")
    }

    fun fileLabel(document: String): String = documents.getValue(document).jsonObject.text("file")
    fun fileLength(file: String): Int = files.getValue(file).jsonObject.getValue("byteLength").jsonPrimitive.int
    fun fileDigest(file: String): String = files.getValue(file).jsonObject.text("exactBytesDigest")
    fun media(file: String): PurchasingEvidenceMediaV1 = PurchasingEvidenceMediaV1.valueOf(files.getValue(file).jsonObject.text("media"))
    /** Frozen B(d,f) requires the original metadata stem with the file's actual media suffix. */
    fun fileName(file: String): String = originalFileName.substringBeforeLast('.') + when (media(file)) {
        PurchasingEvidenceMediaV1.PDF -> ".pdf"
        PurchasingEvidenceMediaV1.PNG -> ".png"
        PurchasingEvidenceMediaV1.JPEG -> ".jpg"
    }
    fun fileBytes(file: String): ByteArray {
        val source = files.getValue(file).jsonObject
        val representation = source.getValue("representation").jsonObject
        val bytes = when (representation.text("kind")) {
            "base64" -> Base64.getDecoder().decode(representation.text("value"))
            "base-plus-padding" -> {
                val base = fileBytes(representation.text("baseFile"))
                val prefix = hex(representation.text("prefixHex"))
                val repeated = hex(representation.text("repeatHex"))
                val count = representation.getValue("repeatCount").jsonPrimitive.int
                base + prefix + ByteArray(repeated.size * count) { repeated[it % repeated.size] } +
                    hex(representation.text("suffixHex"))
            }
            else -> error("Unbound frozen byte recipe")
        }
        check(bytes.size == source.getValue("byteLength").jsonPrimitive.int)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        check(digest == source.text("exactBytesDigest"))
        return bytes
    }

    fun selectedFile(file: String, name: String = fileName(file)): LocalPurchasingDocumentSelectedFile =
        LocalPurchasingDocumentSelectedFile(name, media(file), fileLength(file))

    fun selection(document: String): LocalPurchasingDocumentFileSelection.Selected {
        val file = fileLabel(document)
        val bytes = fileBytes(file)
        return try { LocalPurchasingDocumentFileSelection.Selected(bytes, fileName(file), media(file)) }
        finally { bytes.fill(0) }
    }

    fun sourceSummary(document: String): LocalPurchasingDocumentSourceSummary {
        val source = documents.getValue(document).jsonObject
        val file = fileLabel(document)
        return LocalPurchasingDocumentSourceSummary(documentId(document), fileName(file), fileLength(file),
            source.optionalText("parentOriginalId")?.let(::documentId))
    }

    fun draft(document: String): LocalPurchasingDocumentDraft {
        val source = documents.getValue(document).jsonObject
        val parent = source.optionalText("parentOriginalId")
        val correction = source.optionalText("correctionOf")
        val kind = when {
            parent != null -> LocalPurchasingDocumentKind.ATTACHMENT
            correction != null -> LocalPurchasingDocumentKind.CORRECTION
            else -> LocalPurchasingDocumentKind.PRIMARY
        }
        return LocalPurchasingDocumentDraft(kind, documentId(document).value, supplierId.value,
            supplierRevision.value.toString(), supplierReference, documentDate,
            if (document == "SMALL-CHILD") PurchasingEvidencePurposeV1.SUPPLIER_DELIVERY
                else PurchasingEvidencePurposeV1.valueOf(metadata.text("purpose")),
            parentOriginalId = parent?.let { documentId(it).value }.orEmpty(),
            parentAttachmentsRevision = if (parent == null) "" else parentRevision.value.toString(),
            correctionOf = correction?.let { documentId(it).value }.orEmpty(),
            correctionReason = source.optionalText("correctionReason").orEmpty())
    }

    /** Public input assembled from the fixture, not from the draft validator being tested. */
    fun captureInput(document: String): LocalPurchasingDocumentCapture {
        val source = documents.getValue(document).jsonObject
        val parent = source.optionalText("parentOriginalId")?.let(::documentId)
        val correction = source.optionalText("correctionOf")?.let(::documentId)
        val file = fileLabel(document)
        return LocalPurchasingDocumentCapture(documentId(document), supplierId, supplierRevision,
            supplierReference, BusinessDate.parseCanonical(documentDate),
            if (document == "SMALL-CHILD") PurchasingEvidencePurposeV1.SUPPLIER_DELIVERY
                else PurchasingEvidencePurposeV1.valueOf(metadata.text("purpose")),
            media(file), fileName(file), parent, parentRevision.takeIf { parent != null },
            correction, source.optionalText("correctionReason"))
    }

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

internal fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content
internal fun JsonObject.optionalText(name: String): String? = get(name)?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
