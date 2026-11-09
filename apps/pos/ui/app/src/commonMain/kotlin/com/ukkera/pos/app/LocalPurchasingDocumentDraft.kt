package com.ukkera.pos.app

import com.ukkera.pos.security.PurchasingEvidenceMediaV1
import com.ukkera.pos.security.PurchasingEvidencePurposeV1
import com.ukkera.pos.types.BusinessDate
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision

internal enum class LocalPurchasingDocumentKind { PRIMARY, CORRECTION, ATTACHMENT }

internal enum class LocalPurchasingDocumentField(val maximumLength: Int) {
    DOCUMENT_ID(36), SUPPLIER_ID(36), SUPPLIER_REVISION(19), SUPPLIER_REFERENCE(192),
    DOCUMENT_DATE(32), PARENT_ORIGINAL_ID(36), PARENT_ATTACHMENTS_REVISION(19),
    CORRECTION_OF(36), CORRECTION_REASON(256), ORIGINAL_FILE(0),
}

/** Metadata only. The selected original byte buffer remains owned by the entry controller. */
internal data class LocalPurchasingDocumentDraft(
    val kind: LocalPurchasingDocumentKind,
    val documentId: String = "",
    val supplierId: String = "",
    val supplierRevision: String = "",
    val supplierReferenceOriginal: String = "",
    val documentDate: String = "",
    val purpose: PurchasingEvidencePurposeV1? = null,
    val parentOriginalId: String = "",
    val parentAttachmentsRevision: String = "",
    val correctionOf: String = "",
    val correctionReason: String = "",
    /** After a save attempt, retries use these same entered values and chosen original. */
    val saveAttempted: Boolean = false,
) {
    fun edit(field: LocalPurchasingDocumentField, value: String): LocalPurchasingDocumentDraft = when (field) {
        LocalPurchasingDocumentField.DOCUMENT_ID -> copy(documentId = value)
        LocalPurchasingDocumentField.SUPPLIER_ID -> copy(supplierId = value)
        LocalPurchasingDocumentField.SUPPLIER_REVISION -> copy(supplierRevision = value)
        LocalPurchasingDocumentField.SUPPLIER_REFERENCE -> copy(supplierReferenceOriginal = value)
        LocalPurchasingDocumentField.DOCUMENT_DATE -> copy(documentDate = value)
        LocalPurchasingDocumentField.PARENT_ORIGINAL_ID -> copy(parentOriginalId = value)
        LocalPurchasingDocumentField.PARENT_ATTACHMENTS_REVISION -> copy(parentAttachmentsRevision = value)
        LocalPurchasingDocumentField.CORRECTION_OF -> copy(correctionOf = value)
        LocalPurchasingDocumentField.CORRECTION_REASON -> copy(correctionReason = value)
        LocalPurchasingDocumentField.ORIGINAL_FILE -> this
    }

    override fun toString(): String = "LocalPurchasingDocumentDraft([REDACTED])"
}

internal data class LocalPurchasingDocumentSelectedFile(
    val originalFileName: String,
    val media: PurchasingEvidenceMediaV1,
    val byteCount: Int,
) {
    override fun toString(): String = "LocalPurchasingDocumentSelectedFile([REDACTED])"
}

internal data class LocalPurchasingDocumentInputIssue(
    val field: LocalPurchasingDocumentField?,
    val message: LocalText,
)

internal data class LocalPurchasingDocumentCompletionRetryDraft(
    val documentId: DomainId,
    val refusedCompleteOperationId: OperationId,
    val parentAttachmentsRevision: String = "",
    val saveAttempted: Boolean = false,
) {
    override fun toString(): String = "LocalPurchasingDocumentCompletionRetryDraft([REDACTED])"
}

/** Entry-private presentation, never included in an attachment continuation. */
internal data class LocalPurchasingDocumentPresentation(
    val draft: LocalPurchasingDocumentDraft? = null,
    val selectedFile: LocalPurchasingDocumentSelectedFile? = null,
    val inputIssue: LocalPurchasingDocumentInputIssue? = null,
    val fileProblem: LocalPurchasingDocumentFileProblem? = null,
    val importCancelled: Boolean = false,
    val sources: LocalPurchasingDocumentSourcesResult? = null,
    val saveResult: LocalPurchasingDocumentSourceResult? = null,
    val transfers: Map<DomainId, LocalPurchasingDocumentTransferResult> = emptyMap(),
    val download: LocalPurchasingDocumentDownloadResult? = null,
    val workingDocumentId: DomainId? = null,
    val completionRetry: LocalPurchasingDocumentCompletionRetryDraft? = null,
    val completionSaveResult: LocalPurchasingDocumentSourceResult? = null,
    val completionOriginalRefusals: Map<DomainId, OperationId> = emptyMap(),
) {
    val hasDraft: Boolean get() = draft != null || completionRetry != null
}

internal const val LOCAL_PURCHASING_DOCUMENT_SOURCE_LIMIT: Int = 4
private const val MAX_LOCAL_DOCUMENT_ATTACHMENT_BYTES: Int = 256 * 1024

/** Refuses invalid entered metadata without trimming, normalizing, truncating or inventing it. */
internal fun LocalPurchasingDocumentDraft.capture(file: LocalPurchasingDocumentSelectedFile?): LocalPurchasingDocumentCaptureInput {
    fun invalid(field: LocalPurchasingDocumentField?, english: String, arabic: String) =
        LocalPurchasingDocumentCaptureInput.Invalid(LocalPurchasingDocumentInputIssue(field, LocalText(english, arabic)))
    fun identifier(value: String): DomainId? = try { DomainId(value) } catch (_: IllegalArgumentException) { null }

    val id = identifier(documentId) ?: return invalid(LocalPurchasingDocumentField.DOCUMENT_ID,
        "Enter an exact lowercase UUID for this document.", "أدخل معرف UUID دقيقًا بأحرف صغيرة لهذا المستند.")
    val supplier = identifier(supplierId) ?: return invalid(LocalPurchasingDocumentField.SUPPLIER_ID,
        "Enter the exact supplier ID.", "أدخل معرف المورد الدقيق.")
    val supplierVersion = parsePurchasingDocumentPositiveRevision(supplierRevision) ?: return invalid(LocalPurchasingDocumentField.SUPPLIER_REVISION,
        "Enter the current supplier revision as a positive whole number.", "أدخل مراجعة المورد الحالية كعدد صحيح موجب.")
    if (!validDocumentaryText(supplierReferenceOriginal, 192)) return invalid(LocalPurchasingDocumentField.SUPPLIER_REFERENCE,
        "Enter the original supplier reference, from 1 to 192 characters. Its spelling and spaces are retained.",
        "أدخل مرجع المورد الأصلي من حرف إلى 192 حرفًا. يُحفظ نصه ومسافاته كما أُدخلت.")
    val date = if (documentDate.isEmpty()) null else try { BusinessDate.parseCanonical(documentDate) }
    catch (_: IllegalArgumentException) { return invalid(LocalPurchasingDocumentField.DOCUMENT_DATE,
        "Use the exact document date in YYYY-MM-DD form, or leave it empty when unspecified.",
        "استخدم تاريخ المستند الدقيق بصيغة YYYY-MM-DD، أو اتركه فارغًا إذا كان غير محدد.") }
    val evidencePurpose = purpose ?: return invalid(null,
        "Choose the document's declared purpose.", "اختر الغرض المعلن للمستند.")
    val original = file ?: return invalid(LocalPurchasingDocumentField.ORIGINAL_FILE,
        "Choose the complete original JPEG, PNG or PDF file.", "اختر الملف الأصلي الكامل بصيغة JPEG أو PNG أو PDF.")
    val byteLimit = if (kind == LocalPurchasingDocumentKind.ATTACHMENT) MAX_LOCAL_DOCUMENT_ATTACHMENT_BYTES
        else MAX_LOCAL_PURCHASING_DOCUMENT_FILE_BYTES
    if (original.byteCount !in 1..byteLimit) return invalid(LocalPurchasingDocumentField.ORIGINAL_FILE,
        if (kind == LocalPurchasingDocumentKind.ATTACHMENT) "An attachment must be at most 256 KiB; choose a smaller original file."
        else "An original must be at most 512 KiB; choose a smaller original file.",
        if (kind == LocalPurchasingDocumentKind.ATTACHMENT) "يجب ألا يتجاوز المرفق 256 KiB؛ اختر ملفًا أصليًا أصغر."
        else "يجب ألا يتجاوز الأصل 512 KiB؛ اختر ملفًا أصليًا أصغر.")
    if (!validLocalPurchasingDocumentFileName(original.originalFileName)) return invalid(LocalPurchasingDocumentField.ORIGINAL_FILE,
        "The original filename is unavailable or invalid. Choose a file with a valid original filename.",
        "اسم الملف الأصلي غير متاح أو غير صالح. اختر ملفًا باسم أصلي صالح.")

    val parent = if (kind == LocalPurchasingDocumentKind.ATTACHMENT) identifier(parentOriginalId)
        ?: return invalid(LocalPurchasingDocumentField.PARENT_ORIGINAL_ID,
            "Enter the exact captured parent original ID.", "أدخل معرف الأصل الأب المحفوظ الدقيق.") else null
    val parentRevision = if (kind == LocalPurchasingDocumentKind.ATTACHMENT) parsePurchasingDocumentPositiveRevision(parentAttachmentsRevision)
        ?: return invalid(LocalPurchasingDocumentField.PARENT_ATTACHMENTS_REVISION,
            "Enter the parent's current attachments revision as a positive whole number.", "أدخل مراجعة مرفقات الأصل الأب الحالية كعدد صحيح موجب.") else null
    val corrected = if (kind == LocalPurchasingDocumentKind.CORRECTION) identifier(correctionOf)
        ?: return invalid(LocalPurchasingDocumentField.CORRECTION_OF,
            "Enter the exact original ID this new correction refers to.", "أدخل معرف الأصل الدقيق الذي يشير إليه هذا التصحيح الجديد.") else null
    if (parent == id || corrected == id) return invalid(LocalPurchasingDocumentField.DOCUMENT_ID,
        "Use a distinct new document ID; the parent or corrected original is retained.", "استخدم معرف مستند جديدًا مختلفًا؛ يُحتفظ بالأصل الأب أو المصحح.")
    val reason = if (kind == LocalPurchasingDocumentKind.CORRECTION) correctionReason else null
    if (reason != null && !validDocumentaryText(reason, 256)) return invalid(LocalPurchasingDocumentField.CORRECTION_REASON,
        "Enter a correction reason from 1 to 256 characters.", "أدخل سبب التصحيح من حرف إلى 256 حرفًا.")
    return LocalPurchasingDocumentCaptureInput.Valid(LocalPurchasingDocumentCapture(id, supplier, supplierVersion,
        supplierReferenceOriginal, date, evidencePurpose, original.media, original.originalFileName,
        parent, parentRevision, corrected, reason))
}

internal sealed interface LocalPurchasingDocumentCaptureInput {
    data class Valid(val capture: LocalPurchasingDocumentCapture) : LocalPurchasingDocumentCaptureInput
    data class Invalid(val issue: LocalPurchasingDocumentInputIssue) : LocalPurchasingDocumentCaptureInput
}

internal fun parsePurchasingDocumentPositiveRevision(value: String): Revision? =
    value.toLongOrNull()?.takeIf { it >= 1 && it.toString() == value }?.let(::Revision)

private fun validDocumentaryText(value: String, maximum: Int): Boolean {
    if (value.length !in 1..maximum || value.isBlank() || value.any { it.code < 0x20 || it.code == 0x7f }) return false
    return try { value.encodeToByteArray(throwOnInvalidSequence = true).fill(0); true }
    catch (_: IllegalArgumentException) { false }
}
