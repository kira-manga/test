package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.Serializable

/** The literal supplier configuration used when this opening debt was recorded. */
@Serializable
internal data class OpeningSupplierObligationSupplierSourceV1(
    val storageRevision: Revision,
    val digest: PayloadDigest,
    val configuration: ConfigurationRecord,
)

@Serializable
internal data class OpeningSupplierObligationRecordV1(
    val scope: BranchScope,
    val revision: Revision,
    val request: LocalRecordOpeningSupplierObligationV1,
    val supplierSource: OpeningSupplierObligationSupplierSourceV1,
    val sourceDocumentMetadata: LocalOpeningSupplierDocumentaryMetadataV1,
    val operationId: OperationId,
    val actorId: DomainId,
    val recordedAt: UtcInstant,
    val policyRevision: Revision,
)

internal fun openingSupplierObligationIdClaim(id: DomainId): ClaimKey =
    ClaimKey(LocalOpeningSupplierObligationProfile.DOCUMENT_CLAIM, id.value)

internal fun openingSupplierObligationSourceClaim(id: DomainId): ClaimKey =
    ClaimKey(LocalOpeningSupplierObligationProfile.SOURCE_CLAIM, id.value)

/** Exact branch-local source identity; no normalization, alias or cross-branch claim. */
internal fun openingSupplierObligationSourceIdentityClaim(scope: BranchScope, identity: String): ClaimKey =
    ClaimKey(LocalOpeningSupplierObligationProfile.SOURCE_IDENTITY_CLAIM,
        sha256(("purchasing-opening-source-identity/v1/${scope.organizationId.value}/${scope.companyId.value}/${scope.branchId.value}/$identity")
            .encodeToByteArray(throwOnInvalidSequence = true)).value)

internal fun openingSupplierObligationReferenceClaim(scope: BranchScope, supplierId: DomainId, reference: String): ClaimKey {
    val normalized = com.ukkera.pos.inventory.normalizedPurchaseReference(reference)
    val hash = sha256(("purchasing-opening-reference/v1/${scope.companyId.value}/${scope.branchId.value}/${supplierId.value}/$normalized")
        .encodeToByteArray(throwOnInvalidSequence = true))
    return ClaimKey(LocalOpeningSupplierObligationProfile.REFERENCE_CLAIM, "${supplierId.value}/${hash.value}")
}

internal fun openingSupplierObligationClaims(scope: BranchScope, request: LocalRecordOpeningSupplierObligationV1): List<ClaimChange.Acquire> = listOf(
    ClaimChange.Acquire(openingSupplierObligationIdClaim(request.obligationId), request.obligationId),
    ClaimChange.Acquire(openingSupplierObligationSourceClaim(request.sourceEvidenceId), request.obligationId),
    ClaimChange.Acquire(openingSupplierObligationSourceIdentityClaim(scope, request.sourceDocument.sourceIdentity), request.obligationId),
    ClaimChange.Acquire(openingSupplierObligationReferenceClaim(scope, request.supplierId, request.sourceReference), request.obligationId),
)

internal fun openingSupplierObligationResult(code: String, id: DomainId): StoredBytes = LocalOpeningSupplierObligationCodec.result(
    LocalOpeningSupplierObligationResultV1(code, id, if (code == LocalOpeningSupplierObligationProfile.RECORDED) Revision(1) else null))

internal fun openingSupplierObligationRecordBytes(record: OpeningSupplierObligationRecordV1): StoredBytes =
    openingSupplierObligationEncode(record, OpeningSupplierObligationRecordV1.serializer(), LocalOpeningSupplierObligationProfile.MAX_RECORD_BYTES)

internal fun decodeOpeningSupplierObligationRecord(bytes: StoredBytes): OpeningSupplierObligationRecordV1 {
    val record = openingSupplierObligationDecode(bytes, OpeningSupplierObligationRecordV1.serializer(), LocalOpeningSupplierObligationProfile.MAX_RECORD_BYTES)
    check(openingSupplierObligationRecordBytes(record) == bytes && record.revision == Revision(1))
    check(record.sourceDocumentMetadata == openingSupplierObligationDocumentaryMetadata(record.scope, record.request,
        record.operationId, record.actorId, record.recordedAt))
    val supplier = record.supplierSource
    val configuration = supplier.configuration
    check(configuration.scope == record.scope && configuration.kind == LocalConfigurationProfile.SUPPLIER &&
        configuration.id == record.request.supplierId && supplier.storageRevision == record.request.expectedSupplierRevision &&
        configuration.revision.value < Long.MAX_VALUE && configuration.revision.next() == supplier.storageRevision)
    val value = (configuration.body as? ConfigurationBody.Supplier)?.value ?: error("Opening supplier source kind mismatch")
    check(value.id == record.request.supplierId && value.active &&
        sha256(encodeValue(configuration, ConfigurationRecord.serializer()).copyBytes()) == supplier.digest)
    return record
}

internal fun openingSupplierObligationDocumentaryMetadata(
    scope: BranchScope, request: LocalRecordOpeningSupplierObligationV1, operationId: OperationId, actorId: DomainId, capturedAt: UtcInstant,
): LocalOpeningSupplierDocumentaryMetadataV1 {
    val bytes = request.sourceDocument.text.encodeToByteArray(throwOnInvalidSequence = true)
    return LocalOpeningSupplierDocumentaryMetadataV1(scope, request.sourceEvidenceId, request.obligationId, request.supplierId,
        operationId, actorId, capturedAt, bytes.size, sha256(bytes).value, LocalOpeningSupplierObligationProfile.DOCUMENT_MEDIA_TYPE,
        LocalOpeningSupplierObligationProfile.DOCUMENT_PURPOSE, LocalOpeningSupplierObligationProfile.DOCUMENT_RETENTION,
        LocalOpeningSupplierObligationProfile.DOCUMENT_ACCESS_POLICY, LocalOpeningSupplierObligationProfile.DOCUMENT_UPLOAD_STATUS_AT_CAPTURE)
}

/**
 * Complete retained shape, independent of current rows. Source authenticity is separately
 * joined against the actual native BEFORE supplier configuration on reopening.
 */
internal fun openingSupplierObligationOriginalRequest(evidence: RetainedCommandEvidence): LocalRecordOpeningSupplierObligationV1 {
    check(evidence.command == LocalOpeningSupplierObligationProfile.COMMAND && evidence.nativeOriginalProvenance == null &&
        LocalOpeningSupplierObligationCodec.normalize(evidence.normalized) == evidence.normalized &&
        semanticDigest(evidence.scope, evidence.originalActorId, evidence.command, evidence.normalized) == evidence.semanticDigest &&
        evidence.resultHash == sha256(evidence.result.copyBytes()) && evidence.jobs.isEmpty())
    val request = LocalOpeningSupplierObligationCodec.decodeRequest(evidence.normalized)
    if (evidence.committed) {
        check(evidence.result == openingSupplierObligationResult(LocalOpeningSupplierObligationProfile.RECORDED, request.obligationId))
    } else {
        check(evidence.code in openingSupplierObligationRejections && evidence.aggregateWrites.isEmpty() && evidence.claimChanges.isEmpty() &&
            evidence.result == openingSupplierObligationResult(evidence.code, request.obligationId))
    }
    return request
}

internal fun requireOpeningSupplierObligationProducer(evidence: RetainedCommandEvidence): OpeningSupplierObligationRecordV1 {
    val request = openingSupplierObligationOriginalRequest(evidence)
    check(evidence.committed && evidence.aggregateWrites.size == 1)
    val write = evidence.aggregateWrites.single()
    val record = decodeOpeningSupplierObligationRecord(write.payload)
    check(write.key == LocalOpeningSupplierObligationProfile.key(request.obligationId) && write.storageGeneration == Revision(1) &&
        write.schema == LocalOpeningSupplierObligationProfile.SCHEMA && write.payloadDigest == sha256(write.payload.copyBytes()) &&
        record.scope == evidence.scope && record.request == request && record.operationId == evidence.operationId &&
        record.actorId == evidence.originalActorId && record.recordedAt == evidence.recordedAt && record.policyRevision == evidence.policyRevision)
    val claims = openingSupplierObligationClaims(evidence.scope, request)
    check(evidence.claimChanges.size == claims.size && evidence.claimChanges.zip(claims).all { (actual, expected) ->
        actual.action == RetainedClaimAction.ACQUIRE && actual.key == expected.key && actual.ownerId == expected.ownerId })
    return record
}

internal fun OpeningSupplierObligationRecordV1.view(): LocalOpeningSupplierObligationViewV1 {
    val supplier = (supplierSource.configuration.body as ConfigurationBody.Supplier).value
    return LocalOpeningSupplierObligationViewV1(request.obligationId, revision, request.supplierId, supplierSource.storageRevision,
        supplier.names, request.sourceEvidenceId, request.sourceReference, request.sourceDocument, sourceDocumentMetadata,
        request.openingDate, request.amount, request.paymentTerms, request.reasonCode, operationId, actorId, recordedAt, policyRevision)
}
