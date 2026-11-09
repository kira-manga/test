package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision

/** Original literal images only. Core owns the active BEFORE cut and whole native joins. */
private class ReturnCaseHistoryEntitlementImage(val generation: Revision, val schema: String,
    val payload: StoredBytes, val digest: PayloadDigest)

/** Join every exact current entitlement contribution to its original Finalize. A locator,
 * a selected claim, equal totals or a stale initial commerce link cannot supply rights. */
internal suspend fun readReturnCaseHistoryLinkedEntitlement(command: RuntimeHistoricalCommand,
    sale: LocalReturnSaleCut, current: RuntimeHistoricalAggregateSource,
    finalized: RetainedOriginalV2.Streamed, sources: LocalReturnCaseHistorySources): LocalReturnEntitlementSourceCut {
    val profile = LocalReturnEntitlementProvenanceProfile
    val currentRow = current.row
    val locatorKey = profile.locator(sale.sale.value.id)
    val locatorSource = command.aggregateSource(locatorKey) ?: error("sales.return.case_history.entitlement_locator_missing")
    val locatorRow = locatorSource.row
    check(locatorRow.key == locatorKey && locatorRow.schema == LocalSalesStoreProfile.schema(locatorKey.kind) &&
        locatorRow.revision.value in 1L..profile.MAX_ADVANCES.toLong() && sha256(locatorRow.payload.copyBytes()) == locatorRow.digest &&
        command.claim(profile.locatorClaim(sale.sale.value.id)) == sale.sale.value.id)
    val locator = LocalSalesRow(locatorRow, decodeLocalSalesSource(locatorRow.payload, LocalReturnEntitlementLocator.serializer(), profile.MAX_BYTES))
    check(currentRow.revision.value in 2L..(profile.MAX_ADVANCES.toLong() + 1L) && locator.value.scope.value() == command.scope &&
        locator.value.saleId == sale.sale.value.id && locator.value.generation == currentRow.revision &&
        locator.value.generation == locatorRow.revision.next() && locator.value.entitlementDigest == currentRow.digest &&
        locatorSource.original === current.original &&
        current.original === command.earlierOriginal(locator.value.producer.operationId))
    val proof = LocalReturnEntitlementProofBudget()
    val dependencies = LocalReturnCaseDependencies(proof::original)
    var image = ReturnCaseHistoryEntitlementImage(currentRow.revision, currentRow.schema, currentRow.payload, currentRow.digest)
    var producer = locator.value.producer
    var expectedContributionDigest: PayloadDigest? = locator.value.contributionDigest
    var successor: RetainedCommandEvidence? = null
    var latestContribution: LocalSalesRow<LocalReturnEntitlementContribution>? = null
    val visited = mutableSetOf<OperationId>()
    var advances = 0
    while (image.generation != Revision(1)) {
        check(advances < profile.MAX_ADVANCES && visited.add(producer.operationId) &&
            producer.identity().command == LocalReturnCaseProfileV1.COMMAND && producer.caseId != null)
        val key = profile.contribution(producer.operationId)
        val contributionSource = command.aggregateSource(key) ?: error("sales.return.case_history.entitlement_contribution_missing")
        val row = contributionSource.row
        check(row.key == key && row.revision == Revision(1) && row.schema == LocalSalesStoreProfile.schema(key.kind) &&
            sha256(row.payload.copyBytes()) == row.digest)
        val contribution = LocalSalesRow(row, decodeLocalSalesSource(row.payload, LocalReturnEntitlementContribution.serializer(), profile.MAX_BYTES))
        val value = contribution.value
        check(value.scope.value() == command.scope && value.producer == producer && value.producer.caseId != null &&
            value.producer.identity().command == LocalReturnCaseProfileV1.COMMAND &&
            value.finalizeProducer.identity().command == StockStreamingCommandsV2.COLLECTION && value.finalizeProducer.caseId == null &&
            value.beforeGeneration.value in 1L..profile.MAX_ADVANCES.toLong() && value.beforeGeneration.next() == value.afterGeneration &&
            (value.beforeGeneration == Revision(1)) == (value.previousContributionDigest == null) &&
            (if (value.beforeGeneration == Revision(1)) value.previousProducer == value.finalizeProducer
                else value.previousProducer.identity().command == LocalReturnCaseProfileV1.COMMAND && value.previousProducer.caseId != null) &&
            command.claim(profile.contributionClaim(producer.operationId)) == sale.sale.value.id &&
            row.digest == expectedContributionDigest && value.saleId == sale.sale.value.id &&
            value.finalizeProducer == locator.value.finalizeProducer && value.afterGeneration == image.generation && value.afterDigest == image.digest)
        val cut = sources.readCase(returnCaseRequired(producer.caseId), dependencies)
        val original = sources.evidence(producer.operationId, dependencies)
        val request = LocalReturnCaseCommandCodecV1.decode(original.normalized) as? LocalReturnCaseRequestV1.Accept
            ?: error("sales.return.case_history.entitlement_acceptance_missing")
        check(contributionSource.original === command.earlierOriginal(original.operationId) &&
            returnEntitlementWriteMatches(original, row.key, row.revision, row.schema, row.payload, row.digest) &&
            cut.row.value.stage == LocalReturnCaseStageV1.ACCEPTED_DISPOSITION_PENDING &&
            cut.row.value.lastOperationId == producer.operationId && cut.row.value.acceptedReturnId == request.returnId &&
            request.caseId == producer.caseId && cut.binding?.value?.binding?.saleId == value.saleId &&
            returnEntitlementProducer(original, request.caseId) == producer && original.nativeOriginalProvenance == null &&
            cut.event.value.action == LocalReturnCaseActionV1.ACCEPTED && cut.event.value.entitlementRevision == SalesStorageRevision.toDomain(image.generation) &&
            returnEntitlementWriteMatches(original, currentRow.key, image.generation, image.schema, image.payload, image.digest))
        val claimKey = LocalSalesStoreProfile.key(LocalSalesStoreProfile.RETURN_CLAIM_KIND, request.caseId)
        val claimSource = command.aggregateSource(claimKey) ?: error("sales.return.case_history.entitlement_claim_missing")
        val claim = claimSource.row
        check(claimSource.original === command.earlierOriginal(original.operationId) &&
            returnEntitlementWriteMatches(original, claim.key, claim.revision, claim.schema, claim.payload, claim.digest) &&
            command.claim(LocalReturnCaseProfileV1.source("claim", request.caseId)) == request.caseId &&
            command.claim(LocalSalesStoreProfile.unique("return_claim", request.caseId)) == value.saleId &&
            command.claim(LocalSalesStoreProfile.unique("accepted_return", request.returnId)) == value.saleId &&
            command.claim(LocalSalesStoreProfile.unique("return_claim_source", request.returnId)) == request.caseId &&
            command.claim(LocalSalesStoreProfile.unique("claim_acceptance", request.caseId)) == request.returnId)
        requireReturnCaseHistoryEntitlementOrder(original.operationId, original.assignmentId, original.authorityEpoch, original.sequence.value, successor)
        val locatorWrite = original.aggregateWrites.singleOrNull { it.key == locatorKey }
            ?: error("sales.return.case_history.entitlement_locator_write_missing")
        val expectedLocator = returnEntitlementLocatorPayload(value, row.digest)
        check(locatorWrite.storageGeneration == SalesStorageRevision.toDomain(value.afterGeneration) &&
            locatorWrite.schema == locatorRow.schema && locatorWrite.payload == expectedLocator &&
            (advances != 0 || locatorRow.payload == expectedLocator && locatorRow.digest == locatorWrite.payloadDigest))
        val beforeKey = LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.ENTITLEMENT_BEFORE, DomainId(producer.operationId.value))
        val beforeSource = command.aggregateSource(beforeKey) ?: error("sales.return.case_history.entitlement_before_missing")
        val before = beforeSource.row
        check(beforeSource.original === command.earlierOriginal(original.operationId) && before.revision == Revision(1) &&
            before.digest == value.beforeDigest && before.digest == cut.event.value.beforeEntitlementDigest &&
            before.schema == LocalSalesStoreProfile.schema(beforeKey.kind) &&
            returnEntitlementWriteMatches(original, before.key, before.revision, before.schema, before.payload, before.digest))
        proof.resource(returnCaseResources(cut) + dependencies.snapshot() + entitlementResource(request.returnId))
        if (latestContribution == null) latestContribution = contribution
        image = ReturnCaseHistoryEntitlementImage(value.beforeGeneration, currentRow.schema, before.payload, before.digest)
        producer = value.previousProducer
        expectedContributionDigest = value.previousContributionDigest
        successor = original
        advances += 1
    }
    check(advances == currentRow.revision.value.toInt() - 1 && producer == locator.value.finalizeProducer && expectedContributionDigest == null &&
        returnEntitlementProducer(finalized.evidence.identity.producer) == producer)
    val initial = finalized.evidence.writeRow(currentRow.key) ?: error("sales.return.case_history.initial_entitlement_missing")
    check(initial.revision == image.generation && initial.schema == image.schema && initial.payload == image.payload && initial.digest == image.digest)
    val linkSource = command.aggregateSource(commerceLinkKey(currentRow.key)) ?: error("sales.return.case_history.initial_entitlement_link_missing")
    val linkRow = linkSource.row
    check(linkRow.revision == Revision(1) && linkRow.schema == commerceLinkSchema() && linkSource.original === finalized)
    val link = decodeLocalSalesSource(linkRow.payload, CommerceRowLinkV2.serializer())
    check(link.scope == command.scope && link.kind == initial.key.kind && link.id == initial.key.id &&
        link.generation == initial.revision && link.digest == initial.digest &&
        StreamedProducerIdentityV2(link.operationId, CommandKey(link.command, link.version), link.actorId, link.semanticDigest) == finalized.evidence.identity.producer)
    requireCommerceWrite(finalized.evidence, initial)
    requireCommerceWrite(finalized.evidence, linkRow)
    requireReturnCaseHistoryEntitlementOrder(finalized.evidence.identity.producer.operationId, finalized.evidence.identity.assignmentId,
        finalized.evidence.identity.authorityEpoch, finalized.evidence.identity.sequence.value, successor)
    proof.original(finalized.evidence)
    proof.resource(setOf(entitlementResource(sale.sale.value.id), entitlementResource(DomainId(finalized.evidence.identity.producer.operationId.value))) +
        priceSourcesResources(sale.sale.value.priceSourceReference) + entitlementReturnPolicyResources(sale))
    return LocalReturnEntitlementSourceCut(locator.value.producer, producer, locator, returnCaseRequired(latestContribution), advances, proof)
}

private fun requireReturnCaseHistoryEntitlementOrder(operationId: OperationId, assignmentId: DomainId,
    authorityEpoch: Revision, sequence: Long, successor: RetainedCommandEvidence?) {
    if (successor != null) check(operationId != successor.operationId &&
        (assignmentId != successor.assignmentId || authorityEpoch != successor.authorityEpoch || sequence < successor.sequence.value))
}

internal suspend fun prepareReturnCaseHistoryEntitlementAppend(command: RuntimeHistoricalCommand, original: LocalEntitlementCut,
    identity: LocalReturnCaseProductionIdentity, request: LocalReturnCaseRequestV1.Accept,
    sources: LocalReturnCaseHistorySources): LocalReturnEntitlementAppend? {
    if (original.priceSources?.value?.normalizedStock != true) return null
    val source = original.normalizedSource ?: error("sales.return.case_history.entitlement_append_source_missing")
    val key = LocalReturnEntitlementProvenanceProfile.contribution(identity.operationId)
    check(identity.scope == command.scope && command.aggregate(key) == null &&
        command.claim(LocalReturnEntitlementProvenanceProfile.contributionClaim(identity.operationId)) == null)
    val proof = source.appendProof()
    val cut = sources.readCase(request.caseId, LocalReturnCaseDependencies(proof::original))
    proof.resource(returnCaseResources(cut) + entitlementResource(request.returnId) + entitlementResource(DomainId(identity.operationId.value)))
    return LocalReturnEntitlementAppend(source, original, identity, request.caseId, LocalReturnCaseCommandCodecV1.encode(request), proof)
}
