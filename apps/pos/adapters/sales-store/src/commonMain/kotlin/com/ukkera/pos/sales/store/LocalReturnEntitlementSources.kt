package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision

/** Literal images only. Neither a historical current view nor an executable business command. */
private class LocalReturnEntitlementImage(val generation: Revision, val schema: String,
    val payload: StoredBytes, val digest: PayloadDigest)

/** Complete CASE payload accounting, with existing typed source-reader bounds unchanged. */
internal class LocalReturnEntitlementProofBudget private constructor(
    private var bytes: Long, private val producers: MutableMap<OperationId, Long>,
    private val resourceValues: MutableSet<RecoveryResourceKey>,
) {
    constructor() : this(0, linkedMapOf(), linkedSetOf())
    fun copy(): LocalReturnEntitlementProofBudget = LocalReturnEntitlementProofBudget(bytes, producers.toMutableMap(), resourceValues.toMutableSet())
    fun canAdd(amount: Long): Boolean = amount >= 0 && bytes <= LocalReturnEntitlementProvenanceProfile.MAX_PROOF_BYTES - amount
    fun canAppendOriginal(amount: Long): Boolean = producers.size < LocalReturnEntitlementProvenanceProfile.MAX_PRODUCERS && canAdd(amount)
    private fun add(amount: Long) {
        requireLocalSalesSource(canAdd(amount))
        bytes += amount
    }
    fun resource(values: Set<RecoveryResourceKey>) {
        for (value in values) if (value !in resourceValues) {
            requireLocalSalesSource(resourceValues.size < LocalReturnEntitlementProvenanceProfile.MAX_RESOURCES)
            add(value.kind.name.length.toLong() + value.resourceId.value.length + 16L)
            resourceValues += value
        }
    }
    fun resources(): Set<RecoveryResourceKey> = resourceValues.toSet()
    fun original(value: RetainedCommandEvidence) {
        if (value.operationId in producers) return
        requireLocalSalesSource(producers.size < LocalReturnEntitlementProvenanceProfile.MAX_PRODUCERS)
        val size = value.normalized.size.toLong() + value.result.size +
            value.aggregateWrites.sumOf { it.payload.size.toLong() + it.key.kind.length + it.key.id.value.length + it.schema.length + 80L } +
            value.claimChanges.sumOf { it.key.namespace.length.toLong() + it.key.key.length + it.ownerId.value.length + 64L } +
            value.jobs.sumOf { it.body.size.toLong() + it.kind.length + 16L } + 2_048L
        add(size)
        producers[value.operationId] = size
    }
    suspend fun original(value: RetainedStreamedCommand) {
        val operation = value.identity.producer.operationId
        if (operation in producers) return
        requireLocalSalesSource(producers.size < LocalReturnEntitlementProvenanceProfile.MAX_PRODUCERS)
        val start = bytes
        add(2_048L)
        value.frames { frame ->
            add(when (frame) {
                is RetainedCommandFrameV2.Normalized -> frame.body.size.toLong() + 16L
                is RetainedCommandFrameV2.Result -> frame.body.size.toLong() + 16L
                is RetainedCommandFrameV2.Write -> frame.value.let {
                    it.payload.size.toLong() + it.key.kind.length + it.key.id.value.length + it.schema.length + 80L }
                is RetainedCommandFrameV2.Claim -> frame.value.let { it.key.namespace.length.toLong() + it.key.key.length + it.ownerId.value.length + 64L }
                is RetainedCommandFrameV2.Job -> frame.value.body.size.toLong() + frame.value.kind.length + 16L
                is RetainedCommandFrameV2.Source -> when (val source = frame.value) {
                    is StreamedSourceObservationV2.Aggregate -> source.payload.size.toLong() + 1_024L
                    is StreamedSourceObservationV2.Current -> source.payload.size.toLong() + 512L
                    is StreamedSourceObservationV2.Claim -> source.key.namespace.length.toLong() + source.key.key.length + (source.ownerId?.value?.length ?: 0) + 64L
                    is StreamedSourceObservationV2.StockItemAbsent -> source.key.kind.length.toLong() + source.key.id.value.length + 32L
                    is StreamedSourceObservationV2.CurrentAbsent -> source.key.kind.length.toLong() + source.key.id.value.length + 32L
                    is StreamedSourceObservationV2.EstateMember -> when (val record = source.record) {
                        is StockEstateRecordV2.Aggregate -> record.payload.size.toLong() + record.key.kind.length + record.key.id.value.length + record.schema.length + 80L
                        is StockEstateRecordV2.Claim -> record.key.namespace.length.toLong() + record.key.key.length + record.ownerId.value.length + 64L
                        is StockEstateRecordV2.Original -> record.operationId.value.length.toLong() + 128L
                    }
                    is StreamedSourceObservationV2.StockMember -> 4_096L // Fixed bounded identity/query/position-allocation-value coordinates; no payload is omitted.
                    is StreamedSourceObservationV2.StockRange -> 1_024L
                    is StreamedSourceObservationV2.EstateEnd -> 128L
                }
                is RetainedCommandFrameV2.Effect -> frame.value.payload.size.toLong() + frame.value.kind.length + frame.value.id.value.length + 16L
                is RetainedCommandFrameV2.Resource -> 16L
            })
            if (frame is RetainedCommandFrameV2.Resource) resource(setOf(frame.value))
        }
        producers[operation] = bytes - start
    }
}

internal suspend fun initialReturnEntitlementSourceCut(sale: LocalReturnSaleCut,
    row: StoredAggregate, original: RetainedStreamedCommand): LocalReturnEntitlementSourceCut {
    val proof = LocalReturnEntitlementProofBudget()
    requireLocalSalesSource(row.revision == Revision(1))
    proof.original(original)
    proof.resource(setOf(entitlementResource(sale.sale.value.id), entitlementResource(DomainId(original.identity.producer.operationId.value))) +
        priceSourcesResources(sale.sale.value.priceSourceReference) + entitlementReturnPolicyResources(sale))
    val producer = returnEntitlementProducer(original.identity.producer)
    return LocalReturnEntitlementSourceCut(producer, producer, null, null, 0, proof)
}

/** The locator selects a candidate, then every genuine AFTER -> BEFORE contribution is closed. */
internal suspend fun requireLinkedReturnEntitlementSource(view: CommandReadView, sale: LocalReturnSaleCut, current: StoredAggregate,
    locator: LocalSalesRow<LocalReturnEntitlementLocator>): LocalReturnEntitlementSourceCut {
    val profile = LocalReturnEntitlementProvenanceProfile
    requireLocalSalesSource(current.revision.value in 2L..(profile.MAX_ADVANCES.toLong() + 1L) &&
        locator.value.generation == current.revision && locator.value.entitlementDigest == current.digest && locator.value.saleId == sale.sale.value.id)
    val proof = LocalReturnEntitlementProofBudget()
    val dependencies = LocalReturnCaseDependencies(proof::original)
    var image = LocalReturnEntitlementImage(current.revision, current.schema, current.payload, current.digest)
    var producer = locator.value.producer
    var expectedContributionDigest: PayloadDigest? = locator.value.contributionDigest
    var successor: RetainedCommandEvidence? = null
    var latestContribution: LocalSalesRow<LocalReturnEntitlementContribution>? = null
    val visited = mutableSetOf<OperationId>()
    var advances = 0
    while (image.generation != Revision(1)) {
        requireLocalSalesSource(advances < profile.MAX_ADVANCES && visited.add(producer.operationId) &&
            producer.identity().command == LocalReturnCaseProfileV1.COMMAND && producer.caseId != null)
        val contribution = readReturnEntitlementContribution(view, producer.operationId)
        val value = contribution.value
        requireLocalSalesSource(contribution.stored.digest == expectedContributionDigest && value.saleId == sale.sale.value.id &&
            value.producer == producer && value.finalizeProducer == locator.value.finalizeProducer &&
            value.afterGeneration == image.generation && value.afterDigest == image.digest)
        val cut = readReturnCase(view, returnCaseRequired(producer.caseId), dependencies)
        val original = returnCaseEvidence(view, producer.operationId, dependencies)
        val request = LocalReturnCaseCommandCodecV1.decode(original.normalized) as? LocalReturnCaseRequestV1.Accept
            ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.INVALID)
        requireLocalSalesSource(cut.row.value.stage == LocalReturnCaseStageV1.ACCEPTED_DISPOSITION_PENDING &&
            cut.row.value.lastOperationId == producer.operationId && cut.row.value.acceptedReturnId == request.returnId &&
            request.caseId == producer.caseId && cut.binding?.value?.binding?.saleId == value.saleId &&
            original.scope == view.scope && returnEntitlementProducer(original, request.caseId) == producer &&
            original.nativeOriginalProvenance == null && cut.event.value.action == LocalReturnCaseActionV1.ACCEPTED &&
            cut.event.value.entitlementRevision == SalesStorageRevision.toDomain(image.generation) &&
            returnEntitlementWriteMatches(original, current.key, image.generation, image.schema, image.payload, image.digest))
        val claim = LocalSalesStorage.required(view, LocalSalesStoreProfile.key(LocalSalesStoreProfile.RETURN_CLAIM_KIND, request.caseId))
        requireLocalSalesSource(returnEntitlementWriteMatches(original, claim.key, claim.revision, claim.schema, claim.payload, claim.digest) &&
            view.claim(LocalReturnCaseProfileV1.source("claim", request.caseId)) == request.caseId &&
            view.claim(LocalSalesStoreProfile.unique("return_claim", request.caseId)) == value.saleId &&
            view.claim(LocalSalesStoreProfile.unique("accepted_return", request.returnId)) == value.saleId &&
            view.claim(LocalSalesStoreProfile.unique("return_claim_source", request.returnId)) == request.caseId &&
            view.claim(LocalSalesStoreProfile.unique("claim_acceptance", request.caseId)) == request.returnId)
        earlierReturnEntitlementProducer(original.operationId, original.assignmentId, original.authorityEpoch, original.sequence.value, successor)
        val locatorWrite = original.aggregateWrites.singleOrNull { it.key == locator.stored.key }
            ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.INVALID)
        val expectedLocator = returnEntitlementLocatorPayload(value, contribution.stored.digest)
        requireLocalSalesSource(locatorWrite.storageGeneration == SalesStorageRevision.toDomain(value.afterGeneration) &&
            locatorWrite.schema == locator.stored.schema && locatorWrite.payload == expectedLocator &&
            (advances != 0 || locator.stored.payload == expectedLocator && locator.stored.digest == locatorWrite.payloadDigest))
        val beforeKey = LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.ENTITLEMENT_BEFORE, DomainId(producer.operationId.value))
        val before = LocalSalesStorage.required(view, beforeKey)
        requireLocalSalesSource(before.revision == Revision(1) && before.digest == value.beforeDigest &&
            before.digest == cut.event.value.beforeEntitlementDigest && before.schema == LocalSalesStoreProfile.schema(beforeKey.kind) &&
            returnEntitlementWriteMatches(original, before.key, before.revision, before.schema, before.payload, before.digest))
        // The existing complete CASE producer validates exact cursor/tender allocation, claim,
        // immutable sale, result, all writes/claims and no jobs. No business reducer is replayed.
        proof.resource(returnCaseResources(cut) + dependencies.snapshot() + entitlementResource(request.returnId))
        if (latestContribution == null) latestContribution = contribution
        image = LocalReturnEntitlementImage(value.beforeGeneration, current.schema, before.payload, before.digest)
        producer = value.previousProducer
        expectedContributionDigest = value.previousContributionDigest
        successor = original
        advances += 1
    }
    requireLocalSalesSource(advances == current.revision.value.toInt() - 1 && producer == locator.value.finalizeProducer &&
        expectedContributionDigest == null)
    val prices = sale.priceSources?.value ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    val finalize = requireNormalizedReturnSaleSource(view, sale.sale.stored, sale.sale.value, prices)
    requireLocalSalesSource(returnEntitlementProducer(finalize.identity.producer) == producer)
    val initial = finalize.writeRow(current.key) ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    requireLocalSalesSource(initial.revision == image.generation && initial.schema == image.schema &&
        initial.payload == image.payload && initial.digest == image.digest)
    requireNormalizedReturnFinalizeEntitlement(view, initial, finalize)
    earlierReturnEntitlementProducer(finalize.identity.producer.operationId, finalize.identity.assignmentId,
        finalize.identity.authorityEpoch, finalize.identity.sequence.value, successor)
    proof.original(finalize)
    proof.resource(setOf(entitlementResource(sale.sale.value.id), entitlementResource(DomainId(finalize.identity.producer.operationId.value))) +
        priceSourcesResources(sale.sale.value.priceSourceReference) + entitlementReturnPolicyResources(sale))
    return LocalReturnEntitlementSourceCut(locator.value.producer, producer, locator, returnCaseRequired(latestContribution), advances, proof)
}

private fun earlierReturnEntitlementProducer(operationId: OperationId, assignmentId: DomainId,
    authorityEpoch: Revision, sequence: Long, successor: RetainedCommandEvidence?) {
    if (successor != null) requireLocalSalesSource(operationId != successor.operationId &&
        (assignmentId != successor.assignmentId || authorityEpoch != successor.authorityEpoch || sequence < successor.sequence.value))
    // Across authorities, the exact decreasing generation/literal bytes link establishes order;
    // no globally comparable sequence, wall-clock assertion or current employee grant is invented.
}
