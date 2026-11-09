package com.ukkera.pos.app

import com.ukkera.pos.security.PurchasingEvidenceMediaV1
import com.ukkera.pos.security.PurchasingEvidencePurposeV1
import com.ukkera.pos.types.BusinessDate
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision

/** Native bytes are copied into protected actor-owned custody before any Master submission. */
data class LocalPurchasingDocumentCapture(
    val documentId: DomainId,
    val supplierId: DomainId,
    val expectedSupplierRevision: Revision,
    val supplierReferenceOriginal: String,
    val documentDate: BusinessDate?,
    val purpose: PurchasingEvidencePurposeV1,
    val media: PurchasingEvidenceMediaV1,
    val originalFileName: String,
    val parentOriginalId: DomainId? = null,
    val expectedParentAttachmentsRevision: Revision? = null,
    val correctionOf: DomainId? = null,
    val correctionReason: String? = null,
) {
    init {
        require(expectedSupplierRevision.value >= 1)
        require((parentOriginalId == null) == (expectedParentAttachmentsRevision == null))
        require(expectedParentAttachmentsRevision == null || expectedParentAttachmentsRevision.value >= 1)
        require(parentOriginalId == null || correctionOf == null)
        require((correctionOf == null) == (correctionReason == null))
        require(documentId != parentOriginalId && documentId != correctionOf)
    }
    override fun toString(): String = "LocalPurchasingDocumentCapture([REDACTED])"
}

/** Saved is private source custody, not branch capture, receiving, a payable or acceptance. */
sealed interface LocalPurchasingDocumentSourceResult {
    data class Saved(val documentId: DomainId) : LocalPurchasingDocumentSourceResult
    data class Unavailable(val problem: LocalServiceProblem, val documentId: DomainId) : LocalPurchasingDocumentSourceResult
}

data class LocalPurchasingDocumentSourceSummary(
    val documentId: DomainId,
    val originalFileName: String,
    val originalByteLength: Int,
    val parentOriginalId: DomainId?,
)

sealed interface LocalPurchasingDocumentSourcesResult {
    data class Available(val sources: List<LocalPurchasingDocumentSourceSummary>) : LocalPurchasingDocumentSourcesResult
    data class Unavailable(val problem: LocalServiceProblem) : LocalPurchasingDocumentSourcesResult
}

sealed interface LocalPurchasingDocumentTransferResult {
    /** All exact steps and the terminal Master result are durable; still documentary only. */
    data class Captured(val documentId: DomainId, val completeOperationId: OperationId) : LocalPurchasingDocumentTransferResult
    /** A durably recorded refusal is not permission to overwrite the original saved input. */
    data class Rejected(val documentId: DomainId, val operationId: OperationId, val code: String) : LocalPurchasingDocumentTransferResult
    /** No inference about a lost step; resume only these same original identities and bytes. */
    data class Unresolved(val documentId: DomainId, val operationId: OperationId?, val problem: LocalServiceProblem) : LocalPurchasingDocumentTransferResult
}

sealed interface LocalPurchasingDocumentDownloadResult {
    /** Full original reconstructed and verified before a confirmed native write and close. */
    data class Saved(val documentId: DomainId) : LocalPurchasingDocumentDownloadResult
    data class Cancelled(val documentId: DomainId) : LocalPurchasingDocumentDownloadResult
    data class Unavailable(val documentId: DomainId, val problem: LocalServiceProblem) : LocalPurchasingDocumentDownloadResult
}
