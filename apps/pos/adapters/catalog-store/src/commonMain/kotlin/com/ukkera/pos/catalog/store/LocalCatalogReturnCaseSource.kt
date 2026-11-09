package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.RetainedOriginalV2
import com.ukkera.pos.database.RuntimeHistoricalCommand
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.sales.SalesSnapshotCodec
import com.ukkera.pos.sales.store.HistoricalSalesReturnCaseSourceV1
import com.ukkera.pos.sales.store.LocalReturnCaseAssessmentV1
import com.ukkera.pos.sales.store.LocalReturnCaseFactualCutV1
import com.ukkera.pos.sales.store.LocalReturnCaseFactualReceiptEventV1
import com.ukkera.pos.sales.store.LocalReturnCaseFactualSourcesV1
import com.ukkera.pos.sales.store.LocalReturnCaseSourceReferenceV1
import com.ukkera.pos.sales.store.LocalReturnClaimReasonV1
import com.ukkera.pos.sales.store.LocalReturnCommercialTimingV1
import com.ukkera.pos.sales.store.LocalReturnCommercialWindowStatusV1
import com.ukkera.pos.sales.store.LocalReturnInspectionConditionV1
import com.ukkera.pos.sales.store.LocalReturnOrdinaryAssessmentV1
import com.ukkera.pos.sales.store.LocalReturnOrdinaryTimingV1
import com.ukkera.pos.sales.store.LocalReturnRequestedRemedyV1
import com.ukkera.pos.sales.store.LocalSalesReturnPromiseStateV2
import com.ukkera.pos.sales.store.LocalSalesReturnPublicationV2
import com.ukkera.pos.sales.store.PreparedSalesReturnCaseSourceV1
import com.ukkera.pos.sales.store.SalesReturnCaseQualificationRequestV1
import com.ukkera.pos.sales.store.TransactionSalesReturnCaseSourceV1
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/**
 * Genuine shared original-sale, signed-promise, rights and receipt-source preparation.
 * Ordinary classification requires typed original intake and a current-operation employee
 * inspection attestation, together with exact captured and applicable receipt-window terms.
 * Free text, signatures and elapsed time do not establish physical condition or resolve
 * unknown/defective claims or multi-delivery allocation. Those assessments stay PENDING.
 */
public class LocalCatalogReturnCaseSource(
    private val signatures: CompanyPublicationSignatureVerifier,
) : TransactionSalesReturnCaseSourceV1, HistoricalSalesReturnCaseSourceV1 {
    override public suspend fun prepare(view: CommandReadView,
        request: SalesReturnCaseQualificationRequestV1): PreparedSalesReturnCaseSourceV1? {
        check(view.scope == request.scope)
        val facts = LocalReturnCaseFactualSourcesV1.read(view, request.binding.saleId)
        val rights = CurrentReturnRightsReaderV1(view)
        return prepareSources(request, facts, rights,
            readRow = { view.aggregate(it) }, readOriginal = { view.retainedCommand(it) })
    }

    override public suspend fun prepare(command: RuntimeHistoricalCommand,
        request: SalesReturnCaseQualificationRequestV1): PreparedSalesReturnCaseSourceV1? {
        check(command.scope == request.scope)
        val facts = LocalReturnCaseFactualSourcesV1.readHistory(command, request.binding.saleId) ?: return null
        val rights = HistoricalReturnRightsReaderV1(command)
        return prepareSources(request, facts, rights,
            readRow = { key ->
                command.aggregateSource(key)?.let { source ->
                    val original = source.original as? RetainedOriginalV2.Legacy
                        ?: error("sales.return.catalog_history.source_profile")
                    check(command.earlierOriginal(original.evidence.operationId) === original)
                    source.row
                }
            }, readOriginal = { operationId ->
                (command.earlierOriginal(operationId) as? RetainedOriginalV2.Legacy)?.evidence
            })
    }

    private suspend fun prepareSources(request: SalesReturnCaseQualificationRequestV1,
        facts: LocalReturnCaseFactualCutV1, rights: ReturnRightsSourceReaderV1,
        readRow: suspend (AggregateKey) -> StoredAggregate?,
        readOriginal: suspend (OperationId) -> RetainedCommandEvidence?): PreparedSalesReturnCaseSourceV1? {
        check(facts.scope == request.scope && facts.originalSale.id == request.binding.saleId &&
            SalesSnapshotCodec.encodePaidSale(facts.originalSale) == SalesSnapshotCodec.encodePaidSale(request.originalSale))
        val capture = facts.returnPolicyCapture ?: return null // Never backfill an uncaptured sale.
        val portion = capture.value.input.portions.single { it.portionId == request.binding.portionId }
        val publication = capture.value.input.publications.single { it.publicationId == portion.publicationId }
        val sold = facts.originalCustomerReceipt.portions.single { it.portionId == portion.portionId }.quantity
        check(request.binding.quantity.unitId == sold.unitId && request.binding.quantity.scale == sold.scale &&
            request.binding.quantity.scaled in 1..sold.scaled)

        val promiseResources = requireCapturedPublication(request.scope, publication, readRow, readOriginal)
        val current = rights.active()
        rights.sourcePolicies().forEach { requireReturnRightsSource(authenticReturnRights(signatures, request.scope, it)) }
        val applicable = current?.publication?.publication?.let { source ->
            val producer = source.record.producer
            LocalReturnCaseSourceReferenceV1(source.row.key.kind, source.row.key.id, source.row.schema,
                source.row.revision, source.row.digest, producer.operationId, producer.actorId,
                producer.commandName, producer.commandVersion)
        }

        // Verify the complete census before selecting any references. More than eight
        // relevant events cannot be truncated into an apparent complete assessment.
        val relevant = facts.customerReceiptEvents.filter { event ->
            event.value.portions.any { it.portionId == request.binding.portionId }
        }
        val receipts = if (relevant.size <= 8) relevant.map { it.source } else emptyList()
        val promise = publication.promise
        val reason = when {
            promise == null -> "ORIGINAL_PROMISE_UNSPECIFIED"
            current == null -> "APPLICABLE_RIGHTS_REQUIRED"
            relevant.isEmpty() -> "CUSTOMER_RECEIPT_REQUIRED"
            relevant.size > 1 -> "RECEIPT_ALLOCATION_REVIEW_REQUIRED"
            else -> "RETURN_POLICY_APPLICABILITY_REQUIRED"
        }
        // A single event covering the entire original portion removes multi-delivery
        // allocation ambiguity. Documentary timing observes the captured commercial period;
        // ordinary classification also needs the matching current rule and typed inspection.
        val completeReceipt = relevant.singleOrNull()?.takeIf { event ->
            event.value.portions.singleOrNull { it.portionId == portion.portionId }?.quantity == sold
        }
        val timing = if (!request.recordOriginalCommercialTiming) null else
            promise?.changeOfMindReceiptWindowDays?.let { days -> completeReceipt?.let { event ->
                LocalReturnCommercialTimingV1.observe(days, event.source.id, event.value.receivedAt,
                    event.value.receiptTimeZoneId, request.intakeOperationId, request.intakeRecordedAt)
            } }
        val assessment = LocalReturnCaseAssessmentV1(LocalReturnOrdinaryAssessmentV1.PENDING,
            reason, "EVIDENCE_OR_POLICY_REVIEW", policyId = promise?.policyId,
            policyRevision = promise?.policyRevision, salePolicyCapture = capture.source,
            applicableRights = listOfNotNull(applicable), customerReceipts = receipts, originalCommercialTiming = timing)
        val ordinary = ordinaryAssessment(request, publication, current, completeReceipt, assessment)
        val references = (listOf(capture.source, publication.source) + listOfNotNull(applicable) + receipts).distinct()
        return CatalogPreparedReturnCaseSource(request.scope, facts.recoveryResources + promiseResources + rights.resources,
            references, assessment, ordinary)
    }

    /** Only verified immutable sources select category/rule/period; the caller supplies no policy basis. */
    private fun ordinaryAssessment(request: SalesReturnCaseQualificationRequestV1,
        publication: LocalSalesReturnPublicationV2, current: LoadedReturnRightsV1?,
        completeReceipt: LocalReturnCaseFactualReceiptEventV1?,
        pending: LocalReturnCaseAssessmentV1): CatalogOrdinaryReturnAssessment? {
        if (request.facts.claimedReason != LocalReturnClaimReasonV1.CHANGE_OF_MIND ||
            request.facts.requestedRemedy != LocalReturnRequestedRemedyV1.REFUND ||
            request.ordinaryInspection?.condition != LocalReturnInspectionConditionV1.UNCHANGED ||
            publication.state != LocalSalesReturnPromiseStateV2.SIGNED_PRESENT) return null
        val promise = publication.promise ?: return null
        val originalDays = promise.changeOfMindReceiptWindowDays ?: return null
        val rights = current ?: return null
        val rule = rights.publication.signed.definition.rules.singleOrNull {
            it.categoryCode == promise.categoryCode && it.reason == LocalReturnRightsReason.CHANGE_OF_MIND
        } ?: return null
        if (rule.treatment != LocalReturnRightsTreatment.RECEIPT_WINDOW) return null
        val applicableDays = rule.receiptWindowDays ?: return null
        val receipt = completeReceipt ?: return null
        val timing = LocalReturnOrdinaryTimingV1.observe(promise.categoryCode, originalDays, applicableDays,
            receipt.source.id, receipt.value.receivedAt, receipt.value.receiptTimeZoneId,
            request.intakeOperationId, request.intakeRecordedAt)
        val timed = pending.copy(customerReceivedAt = receipt.value.receivedAt,
            receiptTimeZoneId = receipt.value.receiptTimeZoneId, ordinaryTiming = timing)
        val assessment = when (timing.status) {
            LocalReturnCommercialWindowStatusV1.WITHIN -> timed.copy(
                outcome = LocalReturnOrdinaryAssessmentV1.ELIGIBLE, reasonCode = "ORDINARY_REFUND_ELIGIBLE",
                route = "ORIGINAL_REFUND", remedyDueAt = null, originalCommercialTiming = null)
            LocalReturnCommercialWindowStatusV1.OUTSIDE -> timed.copy(
                reasonCode = "ORDINARY_RECEIPT_WINDOW_OUTSIDE", route = "CONTINUING_RIGHTS_OR_COMMERCIAL_REVIEW")
            LocalReturnCommercialWindowStatusV1.UNAVAILABLE -> timed.copy(reasonCode = "ORDINARY_TIMING_UNAVAILABLE")
        }
        // This is only a chronology guard. Expiry is decided above from immutable RECORD
        // intake, so later inspection/qualification cannot make a timely submission late.
        return CatalogOrdinaryReturnAssessment(assessment, timed.copy(
            reasonCode = "AUTHORITATIVE_CHRONOLOGY_UNAVAILABLE"),
            maxOf(request.intakeRecordedAt, receipt.value.recordedAt, rights.active.record.recordedAt))
    }

    /** Exact captured immutable publication and its original installer, never today's SKU. */
    private suspend fun requireCapturedPublication(scope: BranchScope, captured: LocalSalesReturnPublicationV2,
        readRow: suspend (AggregateKey) -> StoredAggregate?,
        readOriginal: suspend (OperationId) -> RetainedCommandEvidence?): Set<RecoveryResourceKey> {
        val reference = captured.source
        val row = readRow(AggregateKey(reference.kind, reference.id))
            ?: error("sales.return.original_publication_unavailable")
        check(row.revision == reference.storageRevision && row.schema == reference.schema && row.digest == reference.digest &&
            sha256(row.payload.copyBytes()) == reference.digest)
        val record = decodeRecord(scope, row)
        val signed = record.signedSku()
        val sku = signed.definition
        check(sku.publicationId == reference.id && sku.skuId == captured.skuId && sku.skuRevision == captured.skuRevision &&
            signed.signingKeyId == captured.signingKeyId && returnPromiseDescriptor(sku.returnPromise) == captured.promise)
        check(signatures.verify(sku.company, signed.signingKeyId,
            LocalConfigurationCodec.canonicalPublication(sku), signed.signatureHex)) {
            "sales.return.original_publication_signature_unavailable"
        }
        val producer = readOriginal(reference.producerOperationId)
            ?: error("sales.return.original_publication_producer_unavailable")
        check(producer.originalActorId == reference.producerActorId && producer.command.name == reference.producerCommand &&
            producer.command.version == reference.producerVersion)
        val eventKey = LocalPricingProfile.key(LocalPricingProfile.HISTORY_EVENT, DomainId(producer.operationId.value))
        val event = readRow(eventKey) ?: error("sales.return.original_publication_event_unavailable")
        check(event.revision == Revision(1) && event.schema == LocalPricingProfile.schema(eventKey.kind))
        requireRetailPublicationProducer(producer, scope, row.key, row.revision, row.schema, row.payload, row.digest,
            event.payload, event.digest)
        return companySourceResources(record, emptyList()) + priceResource(eventKey.id)
    }
}

private data class CatalogOrdinaryReturnAssessment(
    val value: LocalReturnCaseAssessmentV1,
    val chronologyPending: LocalReturnCaseAssessmentV1,
    val notBefore: UtcInstant,
)

/** No borrowed transaction/native source or mutable caller collection survives preparation. */
private class CatalogPreparedReturnCaseSource(
    override val scope: BranchScope, recoveryResources: Set<RecoveryResourceKey>,
    sourceReferences: List<LocalReturnCaseSourceReferenceV1>, assessment: LocalReturnCaseAssessmentV1,
    ordinary: CatalogOrdinaryReturnAssessment?,
) : PreparedSalesReturnCaseSourceV1 {
    private val resources = recoveryResources.toSet()
    private val references = sourceReferences.toList()
    private val value = (ordinary?.value ?: assessment).detached()
    private val chronologyPending = ordinary?.chronologyPending?.detached()
    private val notBefore = ordinary?.notBefore
    override val recoveryResources: Set<RecoveryResourceKey> get() = resources.toSet()
    override val sourceReferences: List<LocalReturnCaseSourceReferenceV1> get() = references.toList()
    override fun assess(recordedAt: UtcInstant): LocalReturnCaseAssessmentV1 =
        (if (notBefore != null && recordedAt < notBefore) checkNotNull(chronologyPending) else value).detached()
}

private fun LocalReturnCaseAssessmentV1.detached(): LocalReturnCaseAssessmentV1 = copy(
    applicableRights = applicableRights.toList(), customerReceipts = customerReceipts.toList())
