package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Literal original allocation. No current entitlement, refund attempt or cashier work state. */
internal data class LocalReturnStockOriginalValue(
    val accepted: AcceptedReturn,
    val acceptedWrite: RetainedAggregateWrite,
)

/** Actually qualified immutable CASE/paid-sale sources, not an authority or synthetic history. */
internal data class LocalReturnStockAcceptanceCut(
    val caseRow: LocalSalesRow<LocalReturnCaseStateV1>,
    val caseEvent: LocalSalesRow<LocalReturnCaseEventV1>,
    val sale: LocalReturnSaleCut,
    val original: LocalReturnStockOriginalValue,
    val acceptance: RetainedCommandEvidence,
    val saleProducer: RetainedStreamedCommand,
    val resources: Set<RecoveryResourceKey>,
)

/** Pure literal decoder only. The caller must qualify the complete genuine CASE producer and
 * its original authorization separately; a consistent payload is not an acceptance certificate. */
internal fun returnStockAcceptedOriginal(scope: BranchScope, sale: PaidSale, caseId: DomainId,
    returnId: DomainId, acceptance: RetainedCommandEvidence): LocalReturnStockOriginalValue {
    requireLocalSalesSource(acceptance.scope == scope && acceptance.committed &&
        acceptance.command == LocalReturnCaseProfileV1.COMMAND && acceptance.nativeOriginalProvenance == null &&
        LocalReturnCaseCommandCodecV1.normalize(acceptance.normalized) == acceptance.normalized &&
        semanticDigest(scope, acceptance.originalActorId, acceptance.command, acceptance.normalized) == acceptance.semanticDigest)
    val request = LocalReturnCaseCommandCodecV1.decode(acceptance.normalized) as? LocalReturnCaseRequestV1.Accept
        ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.INVALID)
    requireLocalSalesSource(request.caseId == caseId && request.returnId == returnId && sale.scope == scope)
    fun write(kind: String, id: DomainId, generation: Revision): RetainedAggregateWrite {
        val key = LocalSalesStoreProfile.key(kind, id)
        val result = acceptance.aggregateWrites.singleOrNull { it.key == key }
            ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
        requireLocalSalesSource(result.storageGeneration == generation && result.schema == LocalSalesStoreProfile.schema(kind) &&
            sha256(result.payload.copyBytes()) == result.payloadDigest)
        return result
    }
    val rights = write(LocalSalesStoreProfile.ENTITLEMENT_KIND, sale.id, request.expectedEntitlementRevision.next().next())
    val entitlement = SalesSnapshotCodec.decodeReceiptEntitlement(rights.payload.copyBytes().decodeToString(throwOnInvalidSequence = true),
        SalesSnapshotBinding(SalesSnapshotKind.RECEIPT_ENTITLEMENT, scope, sale.id, request.expectedEntitlementRevision.next()))
    requireLocalSalesSource(SalesSnapshotCodec.encodePaidSale(entitlement.sale) == SalesSnapshotCodec.encodePaidSale(sale))
    val claimWrite = write(LocalSalesStoreProfile.RETURN_CLAIM_KIND, caseId, request.expectedClaimRevision.next().next())
    val claim = SalesSnapshotCodec.decodeReturnClaim(claimWrite.payload.copyBytes().decodeToString(throwOnInvalidSequence = true),
        SalesSnapshotBinding(SalesSnapshotKind.RETURN_CLAIM, scope, caseId, request.expectedClaimRevision.next()), entitlement)
    val acceptedWrite = write(LocalSalesStoreProfile.RETURN_KIND, returnId, Revision(1))
    val accepted = SalesSnapshotCodec.decodeAcceptedReturn(acceptedWrite.payload.copyBytes().decodeToString(throwOnInvalidSequence = true),
        SalesSnapshotBinding(SalesSnapshotKind.ACCEPTED_RETURN, scope, returnId, Revision(0)), entitlement, claim)
    requireLocalSalesSource(claim.acceptedReturnId == returnId && accepted.saleId == sale.id && accepted.claimId == caseId &&
        accepted.acceptingOperatorId == acceptance.originalActorId && accepted.requestedDisposition.name == request.disposition.name &&
        accepted.inspectionEvidence == request.inspectionEvidence.domain(acceptance.originalActorId) &&
        accepted.revision == Revision(0) && accepted.attempts.isEmpty() && accepted.refunded.minor == 0L && accepted.due == accepted.originalPayable)
    return LocalReturnStockOriginalValue(accepted, acceptedWrite)
}

/** Same actual loan, deliberately without a stock sink: capture only the qualified immutable
 * source rows afterward, rather than recapturing all earlier CASE policy/work observations.
 * readReturnCaseEvent closes the complete original producer using literal BEFORE/AFTER writes;
 * it does not require today's mutable CASE index, entitlement or refund/cash work. */
internal suspend fun readReturnStockAcceptance(view: CommandReadView, saleId: DomainId?,
    caseId: DomainId, returnId: DomainId): LocalReturnStockAcceptanceCut {
    val caseKey = LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.CASE, caseId)
    val caseStored = LocalSalesStorage.required(view, caseKey)
    val state = decodeLocalSalesSource(caseStored.payload, LocalReturnCaseStateV1.serializer())
    requireLocalSalesSource(state.scope.value() == view.scope && state.caseId == caseId && state.revision == caseStored.domainRevision() &&
        sha256(caseStored.payload.copyBytes()) == caseStored.digest)
    validateReturnCaseState(state)
    requireReturnStock(state.stage == LocalReturnCaseStageV1.ACCEPTED_DISPOSITION_PENDING && state.acceptedReturnId == returnId,
        "ACCEPTED_ORIGINAL_REQUIRED")
    val dependencies = LocalReturnCaseDependencies()
    val event = readReturnCaseEvent(view, state.lastOperationId, dependencies)
    requireLocalSalesSource(event.value.action == LocalReturnCaseActionV1.ACCEPTED && event.value.after == state)
    val first = readReturnCaseEvent(view, state.originalOperationId, dependencies)
    requireLocalSalesSource(first.value.action == LocalReturnCaseActionV1.RECORDED && first.value.after.caseId == caseId &&
        first.value.after.ownerId == state.ownerId && first.value.after.facts == state.facts && first.value.after.recordedAt == state.recordedAt)
    val binding = readReturnCaseEvent(view, returnCaseRequired(state.bindingOperationId), dependencies)
    requireLocalSalesSource(binding.value.after.caseId == caseId && binding.value.after.ownerId == state.ownerId &&
        binding.value.after.facts == state.facts && binding.value.binding != null)
    val actualSaleId = returnCaseRequired(binding.value.binding).saleId
    requireLocalSalesSource(saleId == null || saleId == actualSaleId)
    val acceptance = returnCaseEvidence(view, state.lastOperationId, dependencies)
    val caseWrite = acceptance.aggregateWrites.singleOrNull { it.key == caseKey }
        ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    requireLocalSalesSource(caseWrite.storageGeneration == caseStored.revision && caseWrite.schema == caseStored.schema &&
        caseWrite.payload == caseStored.payload && caseWrite.payloadDigest == caseStored.digest &&
        view.claim(LocalReturnCaseProfileV1.source("case", caseId)) == state.ownerId &&
        view.claim(LocalReturnCaseProfileV1.source("claim", caseId)) == caseId &&
        view.claim(LocalSalesStoreProfile.unique("return_claim", caseId)) == actualSaleId &&
        view.claim(LocalSalesStoreProfile.unique("accepted_return", returnId)) == actualSaleId &&
        view.claim(LocalSalesStoreProfile.unique("return_claim_source", returnId)) == caseId &&
        view.claim(LocalSalesStoreProfile.unique("claim_acceptance", caseId)) == returnId)
    val sale = readReturnSaleSource(view, actualSaleId)
    requireReturnStock(sale.priceSources?.value?.normalizedStock == true, "NORMALIZED_RETAIL_REQUIRED")
    val prices = returnCaseRequired(sale.priceSources)
    val saleProducer = requireNormalizedReturnSaleSource(view, sale.sale.stored, sale.sale.value, prices.value)
    val original = returnStockAcceptedOriginal(view.scope, sale.sale.value, caseId, returnId, acceptance)
    requireLocalSalesSource(original.accepted.acceptingOperatorId == state.ownerId)
    return LocalReturnStockAcceptanceCut(LocalSalesRow(caseStored, state), event, sale, original, acceptance, saleProducer,
        returnStockAcceptanceResources(state, actualSaleId, returnId))
}

/** Direct immutable CASE acceptance dependencies; no requalification of current terms or refund work. */
internal fun returnStockAcceptanceResources(state: LocalReturnCaseStateV1, saleId: DomainId, returnId: DomainId): Set<RecoveryResourceKey> =
    returnCaseResources(state.ownerId, state.caseId) + state.events.map { entitlementResource(DomainId(it.value)) } +
        entitlementResource(saleId) + entitlementResource(returnId)

/** Exactly qualified direct sources, never copied historic views or current grants. */
internal suspend fun captureReturnStockAcceptance(view: CommandReadView, sink: StreamedCommandSink,
    cut: LocalReturnStockAcceptanceCut) {
    val legacy = StreamedProducerIdentityV2(cut.acceptance.operationId, cut.acceptance.command,
        cut.acceptance.originalActorId, cut.acceptance.semanticDigest)
    for (row in listOf(cut.caseRow.stored, cut.caseEvent.stored))
        sink.source(StreamedSourceObservationV2.Aggregate(row.key, row.revision, row.schema, row.payload, row.digest, legacy))
    val sale = cut.sale.sale.stored
    sink.source(StreamedSourceObservationV2.Aggregate(sale.key, sale.revision, sale.schema, sale.payload, sale.digest,
        cut.saleProducer.identity.producer))
    val accepted = cut.original.accepted
    val claims = listOf(
        LocalReturnCaseProfileV1.source("case", accepted.claimId) to cut.caseRow.value.ownerId,
        LocalReturnCaseProfileV1.source("claim", accepted.claimId) to accepted.claimId,
        LocalSalesStoreProfile.unique("return_claim", accepted.claimId) to accepted.saleId,
        LocalSalesStoreProfile.unique("accepted_return", accepted.id) to accepted.saleId,
        LocalSalesStoreProfile.unique("return_claim_source", accepted.id) to accepted.claimId,
        LocalSalesStoreProfile.unique("claim_acceptance", accepted.claimId) to accepted.id,
        LocalReturnCaseProfileV1.source("event", DomainId(cut.acceptance.operationId.value)) to accepted.claimId,
        LocalSalesStoreProfile.unique("sale", accepted.saleId) to cut.sale.sale.value.orderId,
        LocalSalesStoreProfile.unique("sale_order", cut.sale.sale.value.orderId) to accepted.saleId,
        LocalSalesStoreProfile.unique("return_entitlement", accepted.saleId) to accepted.saleId,
    )
    for ((key, owner) in claims) {
        requireLocalSalesSource(view.claim(key) == owner)
        sink.source(StreamedSourceObservationV2.Claim(key, owner))
    }
}
