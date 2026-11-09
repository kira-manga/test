package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateChange
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.AcceptedReturn
import com.ukkera.pos.sales.SalesSnapshotBinding
import com.ukkera.pos.sales.SalesSnapshotCodec
import com.ukkera.pos.sales.SalesSnapshotKind
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi

@Serializable
internal data class LocalReturnCaseStateV1(
    val scope: LocalSalesScope, val caseId: DomainId, val ownerId: DomainId,
    val revision: Revision, val originalOperationId: OperationId, val lastOperationId: OperationId,
    val recordedAt: UtcInstant, val facts: LocalReturnCaseFactsV1,
    val stage: LocalReturnCaseStageV1, val events: List<OperationId>,
    val bindingOperationId: OperationId? = null, val decisionOperationId: OperationId? = null,
    val acceptedReturnId: DomainId? = null,
)

@Serializable
internal data class LocalReturnCaseWorkEntryV1(val caseId: DomainId, val caseRevision: Revision, val stage: LocalReturnCaseStageV1)

@Serializable
internal data class LocalReturnCaseWorkV1(
    val scope: LocalSalesScope, val ownerId: DomainId, val revision: Revision,
    val lastOperationId: OperationId, val entries: List<LocalReturnCaseWorkEntryV1>,
)

/** One immutable append per committed case command; the current row is only its projection. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class LocalReturnCaseEventV1(
    val after: LocalReturnCaseStateV1, val action: LocalReturnCaseActionV1, val operationId: OperationId,
    val recordedAt: UtcInstant, val beforeCaseOperationId: OperationId?, val beforeCaseDigest: PayloadDigest?,
    val beforeIndexOperationId: OperationId?, val beforeIndexDigest: PayloadDigest?,
    val binding: LocalReturnCaseReceiptBindingV1? = null,
    val assessment: LocalReturnCaseAssessmentV1? = null, val assessedBindingDigest: PayloadDigest? = null,
    val expectedEntitlementRevision: Revision? = null,
    /** Actually observed before QUALIFY, or committed after ACCEPT; null for an unknown receipt. */
    val entitlementRevision: Revision? = null, val beforeEntitlementDigest: PayloadDigest? = null,
    val beforeClaimDigest: PayloadDigest? = null, val beforeCashWorkDigest: PayloadDigest? = null,
    val qualificationSources: List<LocalReturnCaseSourceReferenceV1> = emptyList(),
    val sourceRecoveryResources: List<RecoveryResourceKey> = emptyList(),
    val result: LocalReturnCaseResultV1.Applied,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val ordinaryInspection: LocalReturnOrdinaryInspectionV1? = null,
)

internal data class LocalReturnCaseCut(
    val row: LocalSalesRow<LocalReturnCaseStateV1>, val event: LocalSalesRow<LocalReturnCaseEventV1>,
    val work: LocalSalesRow<LocalReturnCaseWorkV1>,
    val binding: LocalSalesRow<LocalReturnCaseEventV1>?, val decision: LocalSalesRow<LocalReturnCaseEventV1>?,
    /** Exact dependencies consumed by this read, including other-case index predecessors. */
    val recoveryDependencies: Set<RecoveryResourceKey>,
)

/**
 * Invocation-local admission dependencies, never authority or a replacement for validation.
 * The bounded event readers populate this while checking their immediate retained producers;
 * no scan/requalification of unrelated owner-index entries or recursive history audit is added.
 * Owner/work, case and producer-operation identities are distinct exact keys, not aliases.
 */
internal class LocalReturnCaseDependencies(private val observeRetained: ((RetainedCommandEvidence) -> Unit)? = null) {
    private val resources = linkedSetOf<RecoveryResourceKey>()

    fun producer(operationId: OperationId, ownerId: DomainId, caseId: DomainId) {
        resources += entitlementResource(DomainId(operationId.value))
        resources += returnCaseResources(ownerId, caseId)
    }

    fun retained(evidence: RetainedCommandEvidence) { observeRetained?.invoke(evidence) }

    fun qualifiedEvent(event: LocalReturnCaseEventV1) {
        resources += returnCaseSourceResources(event.qualificationSources)
        resources += event.sourceRecoveryResources
    }

    fun snapshot(): Set<RecoveryResourceKey> = resources.toSet()
}

internal fun returnCaseFactsDigest(facts: LocalReturnCaseFactsV1): PayloadDigest =
    sha256(localSalesEncode(facts, LocalReturnCaseFactsV1.serializer(), LocalReturnCaseProfileV1.MAX_REQUEST_BYTES).copyBytes())

internal fun <T : Any> returnCaseRequired(value: T?): T =
    value ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.INVALID)

internal fun validateReturnCaseState(value: LocalReturnCaseStateV1) {
    requireLocalSalesSource(value.events.size in 1..LocalReturnCaseProfileV1.MAX_EVENTS &&
        value.events.distinct().size == value.events.size && value.events.first() == value.originalOperationId &&
        value.events.last() == value.lastOperationId && value.revision.value == value.events.size.toLong() - 1 &&
        (value.bindingOperationId == null || value.bindingOperationId in value.events) &&
        (value.decisionOperationId == null || value.decisionOperationId in value.events) &&
        (value.acceptedReturnId != null) == (value.stage == LocalReturnCaseStageV1.ACCEPTED_DISPOSITION_PENDING) &&
        (value.stage == LocalReturnCaseStageV1.PENDING || value.bindingOperationId != null && value.decisionOperationId != null))
    validateLocalSalesSource { returnCaseFactsDigest(value.facts) }
}

internal fun validateReturnCaseWork(value: LocalReturnCaseWorkV1) {
    requireLocalSalesSource(value.entries.size in 1..LocalReturnCaseProfileV1.MAX_OPEN_CASES &&
        value.entries.map { it.caseId }.distinct().size == value.entries.size &&
        value.entries == value.entries.sortedBy { it.caseId.value } &&
        value.entries.all { it.caseRevision.value in 0 until LocalReturnCaseProfileV1.MAX_EVENTS.toLong() } &&
        value.revision.value == value.entries.sumOf { it.caseRevision.value + 1 } - 1)
}

private suspend fun returnCaseEventRow(view: CommandReadView, operationId: OperationId): LocalSalesRow<LocalReturnCaseEventV1> {
    val row = LocalSalesStorage.required(view, LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.EVENT, DomainId(operationId.value)))
    val event = decodeLocalSalesSource(row.payload, LocalReturnCaseEventV1.serializer())
    requireLocalSalesSource(row.revision == Revision(1) && event.operationId == operationId &&
        row.digest == sha256(row.payload.copyBytes()) &&
        event.after.lastOperationId == operationId && event.after.scope.value() == view.scope &&
        event.sourceRecoveryResources.size <= 128 && event.sourceRecoveryResources.distinct().size == event.sourceRecoveryResources.size &&
        event.sourceRecoveryResources == event.sourceRecoveryResources.sortedWith(compareBy({ it.kind.name }, { it.resourceId.value })) &&
        view.claim(LocalReturnCaseProfileV1.source("event", DomainId(operationId.value))) == event.after.caseId)
    validateReturnCaseState(event.after)
    return LocalSalesRow(row, event)
}

internal suspend fun readReturnCaseEvent(view: CommandReadView, operationId: OperationId,
    dependencies: LocalReturnCaseDependencies = LocalReturnCaseDependencies()): LocalSalesRow<LocalReturnCaseEventV1> {
    val row = returnCaseEventRow(view, operationId)
    requireReturnCaseProducer(view, row.stored, row.value, dependencies)
    requireReturnCaseSources(view, row.value.qualificationSources)
    requireLocalSalesSource(row.value.assessment?.sources().orEmpty().all { it in row.value.qualificationSources } &&
        row.value.sourceRecoveryResources.containsAll(returnCaseSourceResources(row.value.qualificationSources)))
    dependencies.qualifiedEvent(row.value)
    return row
}

internal suspend fun findReturnCaseWork(view: CommandReadView, ownerId: DomainId,
    dependencies: LocalReturnCaseDependencies = LocalReturnCaseDependencies()): LocalSalesRow<LocalReturnCaseWorkV1>? {
    val key = LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.WORK, ownerId)
    val row = view.aggregate(key)
    val claim = view.claim(LocalReturnCaseProfileV1.source("work", ownerId))
    if (row == null) {
        requireLocalSalesSource(claim == null)
        return null
    }
    requireLocalSalesSource(claim == ownerId && row.schema == LocalSalesStoreProfile.schema(key.kind))
    val value = decodeLocalSalesSource(row.payload, LocalReturnCaseWorkV1.serializer())
    requireLocalSalesSource(value.scope.value() == view.scope && value.ownerId == ownerId && value.revision == row.domainRevision())
    validateReturnCaseWork(value)
    // Prove this index's exact retained transition without requalifying an unrelated case.
    // Each case selected for presentation/mutation is loaded through the complete reader below.
    val event = returnCaseEventRow(view, value.lastOperationId)
    requireReturnCaseWorkProducer(view, event.stored, event.value, dependencies)
    requireLocalSalesSource(event.value.after.ownerId == ownerId)
    val evidence = returnCaseEvidence(view, value.lastOperationId, dependencies)
    requireLocalSalesSource(evidence.aggregateWrites.singleOrNull { it.key == key }?.let {
        it.storageGeneration == row.revision && it.schema == row.schema && it.payload == row.payload && it.payloadDigest == row.digest
    } == true)
    return LocalSalesRow(row, value)
}

internal suspend fun findReturnCase(view: CommandReadView, caseId: DomainId,
    dependencies: LocalReturnCaseDependencies = LocalReturnCaseDependencies()): LocalReturnCaseCut? {
    val key = LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.CASE, caseId)
    val row = view.aggregate(key)
    val owner = view.claim(LocalReturnCaseProfileV1.source("case", caseId))
    if (row == null) {
        requireLocalSalesSource(owner == null && view.claim(LocalReturnCaseProfileV1.source("claim", caseId)) == null)
        return null
    }
    requireLocalSalesSource(row.schema == LocalSalesStoreProfile.schema(key.kind))
    val value = decodeLocalSalesSource(row.payload, LocalReturnCaseStateV1.serializer())
    requireLocalSalesSource(value.scope.value() == view.scope && value.caseId == caseId && value.ownerId == owner &&
        value.revision == row.domainRevision())
    validateReturnCaseState(value)
    requireLocalSalesSource(view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.RETURN_INTAKE_KIND, caseId)) == null &&
        view.claim(LocalSalesStoreProfile.unique("return_intake", caseId)) == null)
    val event = readReturnCaseEvent(view, value.lastOperationId, dependencies)
    requireLocalSalesSource(event.value.after == value)
    val first = if (value.originalOperationId == value.lastOperationId) event else readReturnCaseEvent(view, value.originalOperationId, dependencies)
    requireLocalSalesSource(first.value.action == LocalReturnCaseActionV1.RECORDED && first.value.after.caseId == caseId &&
        first.value.after.ownerId == value.ownerId && first.value.after.scope == value.scope &&
        first.value.after.facts == value.facts && first.value.after.recordedAt == value.recordedAt)
    val evidence = returnCaseEvidence(view, value.lastOperationId, dependencies)
    requireLocalSalesSource(evidence.aggregateWrites.singleOrNull { it.key == key }?.let {
        it.storageGeneration == row.revision && it.schema == row.schema && it.payload == row.payload && it.payloadDigest == row.digest
    } == true)
    val work = findReturnCaseWork(view, value.ownerId, dependencies) ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    requireLocalSalesSource(work.value.entries.singleOrNull { it.caseId == caseId } ==
        LocalReturnCaseWorkEntryV1(caseId, value.revision, value.stage))
    val binding = value.bindingOperationId?.let { if (it == value.lastOperationId) event else readReturnCaseEvent(view, it, dependencies) }
    requireLocalSalesSource(binding == null || binding.value.after.caseId == caseId && binding.value.binding != null)
    val decision = value.decisionOperationId?.let { if (it == value.lastOperationId) event else readReturnCaseEvent(view, it, dependencies) }
    requireLocalSalesSource(decision == null || decision.value.after.caseId == caseId && decision.value.assessment != null &&
        binding != null && decision.value.assessedBindingDigest == binding.stored.digest)
    if (value.stage != LocalReturnCaseStageV1.PENDING) requireLocalSalesSource(
        decision != null && decision.value.action == LocalReturnCaseActionV1.BOUND &&
            decision.value.assessment?.outcome == LocalReturnOrdinaryAssessmentV1.ELIGIBLE &&
            value.facts.requestedRemedy == LocalReturnRequestedRemedyV1.REFUND &&
            view.claim(LocalReturnCaseProfileV1.source("claim", caseId)) == caseId)
    else requireLocalSalesSource(view.claim(LocalReturnCaseProfileV1.source("claim", caseId)) == null &&
        view.claim(LocalSalesStoreProfile.unique("return_claim", caseId)) == null &&
        view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.RETURN_CLAIM_KIND, caseId)) == null)
    return LocalReturnCaseCut(LocalSalesRow(row, value), event, work, binding, decision, dependencies.snapshot())
}

internal suspend fun readReturnCase(view: CommandReadView, caseId: DomainId,
    dependencies: LocalReturnCaseDependencies = LocalReturnCaseDependencies()): LocalReturnCaseCut =
    findReturnCase(view, caseId, dependencies) ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)

internal suspend fun readReturnCaseClaim(view: CommandReadView, original: LocalEntitlementCut,
    caseId: DomainId): LocalClaimCut {
    val dependencies = LocalReturnCaseDependencies()
    val cut = readReturnCase(view, caseId, dependencies)
    val state = cut.row.value
    val binding = returnCaseRequired(cut.binding?.value?.binding)
    val assessment = returnCaseRequired(cut.decision?.value?.assessment)
    requireLocalSalesSource(state.stage != LocalReturnCaseStageV1.PENDING && binding.saleId == original.sale.value.id &&
        view.claim(LocalReturnCaseProfileV1.source("claim", caseId)) == caseId &&
        view.claim(LocalSalesStoreProfile.unique("return_claim", caseId)) == binding.saleId)
    requireReturnCasePolicyForSale(view, original, binding, assessment)
    val row = LocalSalesStorage.required(view, LocalSalesStoreProfile.key(LocalSalesStoreProfile.RETURN_CLAIM_KIND, caseId))
    val claim = SalesSnapshotCodec.decodeReturnClaim(row.text(),
        SalesSnapshotBinding(SalesSnapshotKind.RETURN_CLAIM, view.scope, caseId, row.domainRevision()), original.entitlement.value)
    requireLocalSalesSource(claim.claimantOperatorId == state.ownerId && claim.portionId == binding.portionId &&
        claim.quantity == binding.quantity && claim.acceptedReturnId == state.acceptedReturnId &&
        claim.evidence == returnCaseQualifiedClaimEvidence(state, assessment, cut.decision?.value?.ordinaryInspection))
    val producer = returnCaseEvidence(view,
        if (state.acceptedReturnId == null) returnCaseRequired(state.decisionOperationId) else state.lastOperationId, dependencies)
    requireLocalSalesSource(producer.aggregateWrites.singleOrNull { it.key == row.key }?.let {
        it.storageGeneration == row.revision && it.schema == row.schema && it.payload == row.payload && it.payloadDigest == row.digest
    } == true)
    return LocalClaimCut(original, null, LocalSalesRow(row, claim), returnCaseResources(cut) + dependencies.snapshot() + entitlementSourceResources(original))
}

/** Later v2 refund revisions may add attempts, never rewrite the original CASE acceptance allocation. */
internal suspend fun requireReturnCaseAcceptedOrigin(view: CommandReadView, original: LocalClaimCut,
    accepted: LocalSalesRow<AcceptedReturn>): Set<RecoveryResourceKey> {
    requireLocalSalesSource(original.intake == null)
    val dependencies = LocalReturnCaseDependencies()
    val cut = readReturnCase(view, original.claim.value.id, dependencies)
    requireLocalSalesSource(cut.row.value.stage == LocalReturnCaseStageV1.ACCEPTED_DISPOSITION_PENDING &&
        cut.row.value.acceptedReturnId == accepted.value.id)
    val producer = returnCaseEvidence(view, cut.row.value.lastOperationId, dependencies)
    val write = producer.aggregateWrites.singleOrNull { it.key == accepted.stored.key }
        ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.INVALID)
    requireLocalSalesSource(write.storageGeneration == Revision(1) && write.schema == accepted.stored.schema)
    val first = SalesSnapshotCodec.decodeAcceptedReturn(write.payload.copyBytes().decodeToString(throwOnInvalidSequence = true),
        SalesSnapshotBinding(SalesSnapshotKind.ACCEPTED_RETURN, view.scope, accepted.value.id, Revision(0)),
        original.original.entitlement.value, original.claim.value)
    val current = accepted.value
    requireLocalSalesSource(current.id == first.id && current.scope == first.scope && current.saleId == first.saleId &&
        current.claimId == first.claimId && current.portionId == first.portionId && current.quantity == first.quantity &&
        current.consumedQuantityBefore == first.consumedQuantityBefore && current.acceptingOperatorId == first.acceptingOperatorId &&
        current.evidence == first.evidence && current.requestedDisposition == first.requestedDisposition &&
        current.inspectionEvidence == first.inspectionEvidence && current.originalPayable == first.originalPayable &&
        current.components == first.components && current.tenderObligations == first.tenderObligations &&
        (current.revision != Revision(0) || accepted.stored.payload == write.payload && accepted.stored.digest == write.payloadDigest))
    return returnCaseResources(cut) + dependencies.snapshot()
}

internal suspend fun requireReturnCasePolicyForSale(view: CommandReadView, original: LocalReturnSaleEvidence,
    binding: LocalReturnCaseReceiptBindingV1, assessment: LocalReturnCaseAssessmentV1): LocalSalesReturnPortionPromiseV1? {
    val reference = assessment.salePolicyCapture ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    if (reference.kind == LocalSalesReturnPolicyCaptureCodecV2.KIND && reference.schema == LocalSalesReturnPolicyCaptureCodecV2.SCHEMA)
        return requireReturnPolicyV2PortionForSale(view, original, binding, assessment, reference)
    // Existing explicit v1 reader remains intact for its original profile. A new V2-bearing
    // sale may not discard its exact captured promise by selecting a legacy V1 alternative.
    requireLocalSalesSource(original.sale.value.returnPolicySourceReference == null &&
        reference.kind == LocalSalesReturnPolicyCaptureCodecV1.KIND && reference.schema == LocalSalesReturnPolicyCaptureCodecV1.SCHEMA)
    val captureRow = LocalSalesStorage.required(view, LocalReturnCaseProfileV1.key(reference.kind, reference.id), reference.schema)
    val capture = validateLocalSalesSource { LocalSalesReturnPolicyCaptureCodecV1.decode(captureRow.payload) }
    val sale = original.sale.value
    requireLocalSalesSource(capture.organizationId == view.scope.organizationId && capture.companyId == view.scope.companyId && capture.branchId == view.scope.branchId &&
        capture.id == reference.id && capture.originalQuoteOperationId == reference.producerOperationId &&
        capture.orderId == sale.orderId && capture.quoteRevision == sale.quote.revision &&
        capture.quoteDigest == sha256(StoredPricingSnapshotCodec.encode(sale.quote).encodeToByteArray(throwOnInvalidSequence = true)) &&
        captureRow.digest == reference.digest && binding.saleId == sale.id &&
        capture.portions.map { it.portionId }.toSet() == sale.quote.lines.map { it.portionId }.toSet())
    val portion = capture.portions.singleOrNull { it.portionId == binding.portionId }
        ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.INVALID)
    requireReturnCaseSources(view, listOf(portion.promiseSource))
    val prices = readPriceSources(view, sale.orderId, sale.quote, sale.priceSourceReference)
    if (prices != null) requireLocalSalesSource(prices.value.sources.singleOrNull { it.portionId == binding.portionId }?.skuId == portion.skuId)
    // Exact quote→collection→sale capture propagation is the configuration-backed source's
    // mandatory qualification contract. No legacy/current-policy fallback exists here.
    return portion
}

private suspend fun requireReturnPolicyV2PortionForSale(view: CommandReadView, original: LocalReturnSaleEvidence,
    binding: LocalReturnCaseReceiptBindingV1, assessment: LocalReturnCaseAssessmentV1,
    reference: LocalReturnCaseSourceReferenceV1): LocalSalesReturnPortionPromiseV1? {
    val sale = original.sale.value
    val saleReference = sale.returnPolicySourceReference ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    requireLocalSalesSource(saleReference.id == reference.id && saleReference.digest == reference.digest && binding.saleId == sale.id)
    requireReturnCaseSources(view, listOf(reference))
    val prices = readPriceSources(view, sale.orderId, sale.quote, sale.priceSourceReference)
        ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    val capture = readReturnPolicyCapture(view, sale.orderId, sale.quote, saleReference, prices.value.sources)
        ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    requireLocalSalesSource(capture.value.originalQuoteOperationId == reference.producerOperationId &&
        capture.stored.revision == reference.storageRevision && capture.stored.digest == reference.digest)
    val portion = capture.value.input.portions.singleOrNull { it.portionId == binding.portionId }
        ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.INVALID)
    val publication = capture.value.input.publications.single { it.publicationId == portion.publicationId }
    if (publication.state == LocalSalesReturnPromiseStateV2.SIGNED_ABSENT) {
        requireLocalSalesSource(assessment.outcome == LocalReturnOrdinaryAssessmentV1.PENDING && assessment.policyId == null)
        return null // Explicit signed absence cannot become an ordinary eligibility decision.
    }
    val promise = publication.promise ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.INVALID)
    requireLocalSalesSource(assessment.policyId == null || assessment.policyId == promise.policyId && assessment.policyRevision == promise.policyRevision)
    requireReturnCaseSources(view, listOf(publication.source))
    return LocalSalesReturnPortionPromiseV1(portion.portionId, publication.skuId, promise.categoryCode,
        promise.policyId, promise.policyRevision, publication.source)
}

/** Shared by CASE mutations and every later refund path that loads a CASE-origin claim. */
internal fun returnCaseResources(cut: LocalReturnCaseCut): Set<RecoveryResourceKey> =
    returnCaseResources(cut.row.value.ownerId, cut.row.value.caseId) +
        cut.row.value.events.map { entitlementResource(DomainId(it.value)) } +
        cut.recoveryDependencies +
        returnCaseSourceResources(cut.decision?.value?.qualificationSources.orEmpty()) +
        cut.decision?.value?.sourceRecoveryResources.orEmpty()

internal fun returnCaseResources(ownerId: DomainId, caseId: DomainId): Set<RecoveryResourceKey> =
    setOf(entitlementResource(caseId), orderResource(ownerId), entitlementResource(ownerId),
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, ownerId))

internal fun returnCaseWrite(value: LocalReturnCaseStateV1, previous: StoredAggregate?): AggregateChange {
    validateReturnCaseState(value)
    return rootWrite(LocalReturnCaseProfileV1.CASE, value.caseId, value, LocalReturnCaseStateV1.serializer(), previous)
}

internal fun returnCaseEventWrite(value: LocalReturnCaseEventV1): AggregateChange =
    rootWrite(LocalReturnCaseProfileV1.EVENT, DomainId(value.operationId.value), value, LocalReturnCaseEventV1.serializer(), null)

internal fun returnCaseWorkAfter(before: LocalSalesRow<LocalReturnCaseWorkV1>?, after: LocalReturnCaseStateV1): LocalReturnCaseWorkV1 {
    val entries = before?.value?.entries.orEmpty().filterNot { it.caseId == after.caseId } +
        LocalReturnCaseWorkEntryV1(after.caseId, after.revision, after.stage)
    if (entries.size > LocalReturnCaseProfileV1.MAX_OPEN_CASES) throw LocalSalesCapacityExceeded()
    return LocalReturnCaseWorkV1(after.scope, after.ownerId, before?.value?.revision?.next() ?: Revision(0),
        after.lastOperationId, entries.sortedBy { it.caseId.value }).also(::validateReturnCaseWork)
}

internal fun returnCaseWorkWrite(value: LocalReturnCaseWorkV1, previous: StoredAggregate?): AggregateChange =
    rootWrite(LocalReturnCaseProfileV1.WORK, value.ownerId, value, LocalReturnCaseWorkV1.serializer(), previous)

internal fun returnCaseWitness(kind: String, operationId: OperationId, before: StoredAggregate): AggregateChange =
    AggregateChange(LocalReturnCaseProfileV1.key(kind, DomainId(operationId.value)), null, LocalSalesStoreProfile.schema(kind), before.payload)
