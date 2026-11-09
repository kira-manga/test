package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.RetainedOriginalV2
import com.ukkera.pos.database.RuntimeHistoricalCommand
import com.ukkera.pos.database.StockStreamingCommandsV2
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.security.sha256

/** Installed independently of the live transaction source; it supplies no historical grant. */
public fun interface HistoricalSalesReturnCaseSourceV1 {
    /**
     * Prepare only from this core-created active native BEFORE cut and its exact earlier
     * whole-joined originals. Return the same immutable references, complete resources and
     * pure assessment as the live source for that cut. Never retain command, construct a
     * CommandReadView/AuthorizedAction, invoke a live handler, or substitute current rows.
     * Null leaves only the existing exact no-source-equivalent PENDING profile available;
     * missing/damaged/unsupported observed evidence must remain unavailable, not be hidden
     * as absence. A prepared source mismatch never retries that unavailable profile.
     */
    public suspend fun prepare(command: RuntimeHistoricalCommand,
        request: SalesReturnCaseQualificationRequestV1): PreparedSalesReturnCaseSourceV1?
}

public object UnavailableHistoricalSalesReturnCaseSourceV1 : HistoricalSalesReturnCaseSourceV1 {
    override public suspend fun prepare(command: RuntimeHistoricalCommand,
        request: SalesReturnCaseQualificationRequestV1): PreparedSalesReturnCaseSourceV1? = null
}

/** Exact immutable source rows and their whole originals in this same native scan. */
internal suspend fun requireReturnCaseHistorySources(command: RuntimeHistoricalCommand,
    references: List<LocalReturnCaseSourceReferenceV1>) {
    check(references.size <= 17 && references.distinct().size == references.size)
    for (reference in references) {
        val key = AggregateKey(reference.kind, reference.id)
        val source = command.aggregateSource(key) ?: error("sales.return.case_history.qualification_source_missing")
        val row = source.row
        check(row.key == key && row.schema == reference.schema && row.revision == reference.storageRevision &&
            row.digest == reference.digest && sha256(row.payload.copyBytes()) == reference.digest &&
            source.original === command.earlierOriginal(reference.producerOperationId))
        val producerKey = CommandKey(reference.producerCommand, reference.producerVersion)
        if (reference.kind == LocalSalesReturnPolicyCaptureCodecV2.KIND &&
            reference.schema == LocalSalesReturnPolicyCaptureCodecV2.SCHEMA) {
            val original = (source.original as? RetainedOriginalV2.Streamed)?.evidence
                ?: error("sales.return.case_history.capture_original_unsupported")
            val capture = LocalSalesReturnPolicyCaptureCodecV2.decode(row.payload)
            check(capture.scope == command.scope && capture.id == reference.id &&
                capture.originalQuoteOperationId == reference.producerOperationId && original.identity.scope == command.scope &&
                original.identity.producer.operationId == reference.producerOperationId &&
                original.identity.producer.originalActorId == reference.producerActorId &&
                original.identity.producer.command == producerKey && producerKey == StockStreamingCommandsV2.COLLECTION)
            requireNormalizedPaymentQuoteRequest(original, capture.orderId, capture.quoteRevision)
            requireCommerceWrite(original, row)
        } else {
            // The live reference contract has only this explicit legacy branch outside V2
            // captures. No arbitrary streamed source or supplied retained reader enters it.
            val original = (source.original as? RetainedOriginalV2.Legacy)?.evidence
                ?: error("sales.return.case_history.qualification_original_unsupported")
            check(original.committed && original.scope == command.scope &&
                original.operationId == reference.producerOperationId && original.originalActorId == reference.producerActorId &&
                original.command == producerKey && semanticDigest(original.scope, original.originalActorId,
                    original.command, original.normalized) == original.semanticDigest)
            val write = original.aggregateWrites.singleOrNull { it.key == key }
                ?: error("sales.return.case_history.qualification_write_missing")
            check(write.storageGeneration == row.revision && write.schema == row.schema &&
                write.payload == row.payload && write.payloadDigest == reference.digest)
        }
    }
}

/** Select only a portion of the already validated whole normalized original capture. */
internal suspend fun requireReturnCaseHistoryPolicyForSale(command: RuntimeHistoricalCommand,
    original: LocalReturnSaleEvidence, binding: LocalReturnCaseReceiptBindingV1,
    assessment: LocalReturnCaseAssessmentV1): LocalSalesReturnPortionPromiseV1? {
    val reference = assessment.salePolicyCapture ?: error("sales.return.case_history.capture_missing")
    check(reference.kind == LocalSalesReturnPolicyCaptureCodecV2.KIND &&
        reference.schema == LocalSalesReturnPolicyCaptureCodecV2.SCHEMA)
    val sale = original.sale.value
    val saleReference = sale.returnPolicySourceReference ?: error("sales.return.case_history.sale_capture_missing")
    val capture = original.returnPolicyCapture ?: error("sales.return.case_history.capture_missing")
    check(binding.saleId == sale.id && sale.scope == command.scope && saleReference.id == reference.id &&
        saleReference.digest == reference.digest && capture.stored.key == AggregateKey(reference.kind, reference.id) &&
        capture.stored.schema == reference.schema && capture.stored.revision == reference.storageRevision &&
        capture.stored.digest == reference.digest && capture.value.originalQuoteOperationId == reference.producerOperationId)
    requireReturnCaseHistorySources(command, listOf(reference))
    val portion = capture.value.input.portions.singleOrNull { it.portionId == binding.portionId }
        ?: error("sales.return.case_history.capture_portion_missing")
    val publication = capture.value.input.publications.single { it.publicationId == portion.publicationId }
    if (publication.state == LocalSalesReturnPromiseStateV2.SIGNED_ABSENT) {
        check(assessment.outcome == LocalReturnOrdinaryAssessmentV1.PENDING && assessment.policyId == null)
        return null
    }
    val promise = publication.promise ?: error("sales.return.case_history.promise_missing")
    check(assessment.policyId == null || assessment.policyId == promise.policyId && assessment.policyRevision == promise.policyRevision)
    requireReturnCaseHistorySources(command, listOf(publication.source))
    return LocalSalesReturnPortionPromiseV1(portion.portionId, publication.skuId, promise.categoryCode,
        promise.policyId, promise.policyRevision, publication.source)
}
