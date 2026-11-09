package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision

/** Immutable paid-sale evidence only. The current entitlement has a separate exact writer. */
internal suspend fun requireNormalizedReturnSaleSource(view: CommandReadView, row: StoredAggregate,
    sale: PaidSale, prices: LocalSalesPriceSourcesV2): RetainedStreamedCommand {
    requireLocalSalesSource(prices.normalizedStock && row.key == LocalSalesStoreProfile.key(LocalSalesStoreProfile.SALE_KIND, sale.id) &&
        row.revision == Revision(1) && row.schema == LocalSalesStoreProfile.schema(row.key.kind) && sale.scope == view.scope &&
        sale.orderId == prices.orderId && sale.priceSourceReference?.id == prices.id)
    val linkRow = LocalSalesStorage.required(view, commerceLinkKey(row.key), commerceLinkSchema())
    val link = decodeLocalSalesSource(linkRow.payload, CommerceRowLinkV2.serializer())
    requireLocalSalesSource(link.scope == view.scope && link.kind == row.key.kind && link.id == row.key.id &&
        link.generation == row.revision && link.digest == row.digest && sha256(linkRow.payload.copyBytes()) == linkRow.digest)
    val actual = (view.requireStockRelational().originals.read(link.operationId) as? RetainedOriginalV2.Streamed)?.evidence
        ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    requireLocalSalesSource(actual.identity.scope == view.scope && actual.identity.producer == StreamedProducerIdentityV2(
        link.operationId, CommandKey(link.command, link.version), link.actorId, link.semanticDigest) &&
        actual.identity.producer.command == StockStreamingCommandsV2.COLLECTION)
    val request = LocalCollectionStockCodecV2.decode(actual.normalized) as? LocalCollectionStockRequestV2.Finalize
        ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.INVALID)
    requireLocalSalesSource(request.saleId == sale.id && request.orderId == sale.orderId && request.issueId == sale.stockIssueId)
    commerceRepository(view).requireRelationalProducer(view, actual)
    requireCommerceWrite(actual, row)
    requireCommerceWrite(actual, linkRow)
    commerceRecordOriginal(view, row, actual)
    return actual
}

/**
 * No stale commerce link, current-row hash or selected acceptance ID grants remaining rights.
 * Initial rights must be the actual Finalize contribution. New advanced rights close their
 * genuine appended producer chain to that same immutable Finalize. The explicit pre-linkage
 * selected-CASE/current-writer reader remains supported; broader old history stays held.
 */
internal suspend fun requireNormalizedReturnEntitlementSource(view: CommandReadView, sale: LocalReturnSaleCut,
    row: StoredAggregate, selectedCaseId: DomainId?): LocalReturnEntitlementSourceCut? {
    val prices = sale.priceSources?.value ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    requireLocalSalesSource(prices.normalizedStock && row.key == LocalSalesStoreProfile.key(
        LocalSalesStoreProfile.ENTITLEMENT_KIND, sale.sale.value.id) && row.schema == LocalSalesStoreProfile.schema(row.key.kind))
    val locator = findReturnEntitlementLocator(view, sale.sale.value.id)
    if (row.revision == Revision(1)) {
        requireLocalSalesSource(locator == null)
        val original = requireNormalizedReturnSaleSource(view, sale.sale.stored, sale.sale.value, prices)
        requireNormalizedReturnFinalizeEntitlement(view, row, original)
        return initialReturnEntitlementSourceCut(sale, row, original)
    }
    if (locator != null) return requireLinkedReturnEntitlementSource(view, sale, row, locator)
    val caseId = selectedCaseId ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    val dependencies = LocalReturnCaseDependencies()
    val cut = readReturnCase(view, caseId, dependencies)
    val state = cut.row.value
    val returnId = state.acceptedReturnId ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.INVALID)
    requireLocalSalesSource(state.stage == LocalReturnCaseStageV1.ACCEPTED_DISPOSITION_PENDING &&
        cut.event.value.action == LocalReturnCaseActionV1.ACCEPTED && cut.binding?.value?.binding?.saleId == sale.sale.value.id &&
        cut.event.value.entitlementRevision == row.domainRevision())
    val original = returnCaseEvidence(view, state.lastOperationId, dependencies)
    val request = LocalReturnCaseCommandCodecV1.decode(original.normalized) as? LocalReturnCaseRequestV1.Accept
        ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.INVALID)
    requireLocalSalesSource(original.nativeOriginalProvenance == null &&
        original.aggregateWrites.none { it.key.kind in setOf(LocalReturnEntitlementProvenanceProfile.LOCATOR, LocalReturnEntitlementProvenanceProfile.CONTRIBUTION) } &&
        original.claimChanges.none { it.key.namespace == LocalReturnEntitlementProvenanceProfile.NAMESPACE } &&
        request.caseId == caseId && request.returnId == returnId &&
        request.expectedEntitlementRevision.next().next() == row.revision && normalizedReturnRowMatches(original, row))
    val claimRow = LocalSalesStorage.required(view, LocalSalesStoreProfile.key(LocalSalesStoreProfile.RETURN_CLAIM_KIND, caseId))
    requireLocalSalesSource(normalizedReturnRowMatches(original, claimRow) &&
        view.claim(LocalReturnCaseProfileV1.source("claim", caseId)) == caseId &&
        view.claim(LocalSalesStoreProfile.unique("return_claim", caseId)) == sale.sale.value.id &&
        view.claim(LocalSalesStoreProfile.unique("accepted_return", returnId)) == sale.sale.value.id &&
        view.claim(LocalSalesStoreProfile.unique("return_claim_source", returnId)) == caseId &&
        view.claim(LocalSalesStoreProfile.unique("claim_acceptance", caseId)) == returnId)
    return null // Retained compatibility evidence is not permission to backfill an appendable chain.
}

/** Both insert-only contributions belong to the same complete paid Finalize. */
internal suspend fun requireNormalizedReturnFinalizeEntitlement(view: CommandReadView, row: StoredAggregate,
    original: RetainedStreamedCommand) {
    requireLocalSalesSource(row.revision == Revision(1))
    requireCommerceWrite(original, row)
    val linkRow = LocalSalesStorage.required(view, commerceLinkKey(row.key), commerceLinkSchema())
    val link = decodeLocalSalesSource(linkRow.payload, CommerceRowLinkV2.serializer())
    requireLocalSalesSource(link.scope == view.scope && link.kind == row.key.kind && link.id == row.key.id &&
        link.generation == row.revision && link.digest == row.digest &&
        StreamedProducerIdentityV2(link.operationId, CommandKey(link.command, link.version), link.actorId, link.semanticDigest) == original.identity.producer)
    requireCommerceWrite(original, linkRow)
    commerceRecordOriginal(view, row, original)
}

/**
 * The immutable CASE allocation is verified separately by requireReturnCaseAcceptedOrigin.
 * A later normalized liability row must additionally close EVERY exact predecessor through
 * actual V3 refund Start/Apply writes back to the immutable CASE insert. An envelope-only
 * BEFORE image, generic result, native raw original or pending bookkeeping grants no liability.
 */
internal suspend fun requireNormalizedAcceptedReturnSource(view: CommandReadView, original: LocalClaimCut,
    accepted: LocalSalesRow<AcceptedReturn>) {
    if (original.original.priceSources?.value?.normalizedStock != true) return // Explicit legacy readers are unchanged.
    requireLocalSalesSource(original.intake == null)
    val value = accepted.value
    for (attempt in value.attempts) {
        requireLocalSalesSource(view.claim(LocalSalesStoreProfile.unique("refund_attempt", attempt.id)) == value.id)
        val paid = view.claim(LocalSalesStoreProfile.unique("refund_record", attempt.id))
        val cashSource = view.claim(CashNonSaleStoreProfile.cashSourceClaim(attempt.id))
        requireLocalSalesSource(paid == (if (attempt.refund == null) null else value.id) &&
            cashSource == (if (attempt.refund != null && attempt.tender == Tender.Cash) attempt.originalCashSessionId else null))
    }
    NormalizedRefundPredecessors(view, original, accepted).requireComplete()
}

private fun normalizedReturnRowMatches(original: RetainedCommandEvidence, row: StoredAggregate): Boolean =
    original.committed && original.nativeOriginalProvenance == null && sha256(row.payload.copyBytes()) == row.digest &&
        original.aggregateWrites.singleOrNull { it.key == row.key }?.let {
        it.storageGeneration == row.revision && it.schema == row.schema && it.payload == row.payload && it.payloadDigest == row.digest
    } == true

/** Inert literal images, never reconstructed historical views, originals or authorization. */
private class NormalizedRefundImage(val generation: Revision, val schema: String,
    val payload: StoredBytes, val digest: PayloadDigest)
private class NormalizedRefundStep(val original: RetainedCommandEvidence, val before: LocalRetailBeforeV3)

/** At most 64 attempts, each with one Start and at most ORIGINAL + RESOLUTION accounting.
 * Missing, unsupported or over-bound chains are explicitly unavailable, never partial proof. */
private class NormalizedRefundPredecessors(private val view: CommandReadView, private val claim: LocalClaimCut,
    private val current: LocalSalesRow<AcceptedReturn>) {
    private val key = current.stored.key
    private val slots = mutableMapOf<DomainId, LocalSalesRow<LocalRetailJournalSlotV3>>()
    private val facts = mutableMapOf<OperationId, LocalRetailJournalFactCutV3>()

    suspend fun requireComplete() {
        val dependencies = LocalReturnCaseDependencies()
        val case = readReturnCase(view, current.value.claimId, dependencies)
        requireLocalSalesSource(case.row.value.stage == LocalReturnCaseStageV1.ACCEPTED_DISPOSITION_PENDING &&
            case.row.value.acceptedReturnId == current.value.id && key == LocalSalesStoreProfile.key(
                LocalSalesStoreProfile.RETURN_KIND, current.value.id))
        val root = returnCaseEvidence(view, case.row.value.lastOperationId, dependencies)
        val request = LocalReturnCaseCommandCodecV1.decode(root.normalized) as? LocalReturnCaseRequestV1.Accept ?: unavailable()
        requireLocalSalesSource(root.nativeOriginalProvenance == null && request.caseId == current.value.claimId && request.returnId == current.value.id)
        var image = NormalizedRefundImage(current.stored.revision, current.stored.schema, current.stored.payload, current.stored.digest)
        var successor: RetainedCommandEvidence? = null
        val visited = mutableSetOf<OperationId>()
        var bytes = 0L
        while (true) {
            if (image.generation.value !in 1L..193L || bytes > 16_777_216L - image.payload.size) unavailable()
            bytes += image.payload.size
            requireLocalSalesSource(image.schema == LocalSalesStoreProfile.schema(key.kind) && sha256(image.payload.copyBytes()) == image.digest)
            if (image.generation == Revision(1)) {
                // Full current CASE validation and immutable allocation were required by the
                // caller. Exact original insert bytes, not merely equal allocation totals.
                requireLocalSalesSource(matches(root, image))
                earlier(root, successor)
                return
            }
            val value = SalesSnapshotCodec.decodeAcceptedReturn(image.payload.copyBytes().decodeToString(throwOnInvalidSequence = true),
                SalesSnapshotBinding(SalesSnapshotKind.ACCEPTED_RETURN, view.scope, current.value.id,
                    SalesStorageRevision.toDomain(image.generation)), claim.original.entitlement.value, claim.claim.value)
            val step = locate(image, value)
            requireLocalSalesSource(visited.add(step.original.operationId))
            earlier(step.original, successor)
            val before = step.before
            val payload = before.body()
            requireLocalSalesSource(before.role == "accepted" && before.scope.value() == view.scope &&
                before.capturingOperationId == step.original.operationId && before.sourceKind == key.kind && before.sourceId == key.id &&
                before.sourceSchema == image.schema && before.storageRevision.value > 0 && before.storageRevision.next() == image.generation &&
                sha256(payload.copyBytes()).value == before.sourceDigest)
            image = NormalizedRefundImage(before.storageRevision, before.sourceSchema, payload, PayloadDigest(before.sourceDigest))
            successor = step.original
        }
    }

    private suspend fun locate(image: NormalizedRefundImage, value: AcceptedReturn): NormalizedRefundStep {
        // The closed delta appends a Start, or updates the sole unresolved LAST attempt.
        // Earlier paid attempts cannot disappear: every literal predecessor is closed down
        // to the exact CASE insert, rather than accepting a self-consistent shortened list.
        val attempt = value.attempts.lastOrNull() ?: unavailable()
        requireLocalSalesSource(view.claim(LocalSalesStoreProfile.unique("refund_attempt", attempt.id)) == value.id)
        val locator = view.claim(retailJournalAttemptClaimV3(attempt.id)) ?: unavailable()
        val slot = slots[attempt.id] ?: readRetailSlot(view, OperationId(locator.value)).also { slots[attempt.id] = it }
        val target = slot.value.target
        requireLocalSalesSource(slot.value.id == locator && target.kind == SalesRecordingKind.REFUND_ATTESTATION && target.documentId == value.id &&
            target.originalSaleId == value.saleId && target.originalClaimId == value.claimId && target.attemptId == attempt.id &&
            target.intentOperationId == attempt.originalOperationId && target.sessionId == attempt.originalCashSessionId &&
            target.originalPaymentId == attempt.originalPaymentId && slot.value.actorId == attempt.originalActorId &&
            slot.value.domain.amount == attempt.amount && slot.value.domain.tender == tenderView(attempt.tender))
        val start = view.retainedCommand(target.intentOperationId) ?: unavailable()
        // readRetailSlot closes the real full Start, immutable sources and future fences.
        if (matches(start, image)) {
            requireLocalSalesSource(start.command == LocalFinancialJournalProfileV3.RETURN_COMMAND &&
                LocalReturnCommandCodecV3.decode(start.normalized) is LocalReturnRequestV3.Start)
            val row = LocalSalesStorage.required(view, retailBeforeKeyV3(start.operationId, "accepted"), "sales.journal.before/v3")
            requireLocalSalesSource(row.revision == Revision(1) && normalizedReturnRowMatches(start, row))
            return NormalizedRefundStep(start, decodeLocalSalesSource(row.payload, LocalRetailBeforeV3.serializer(),
                LocalFinancialJournalProfileV3.MAX_BEFORE_BYTES))
        }
        for (phase in LocalJournalFactPhaseV3.entries) {
            val operation = target.factOperation(phase)
            if (view.aggregate(retailJournalFactKeyV3(phase, operation)) == null) {
                requireLocalSalesSource(view.aggregate(retailJournalApplicationKeyV3(operation)) == null)
                continue
            }
            val fact = facts[operation] ?: readRetailFact(view, operation, phase).also { facts[operation] = it }
            requireLocalSalesSource(fact.slot.stored.payload == slot.stored.payload && fact.fact.value.target == target)
            val application = fact.application.value
            if (application.state != LocalJournalApplicationStateV3.APPLIED) continue
            val update = view.retainedCommand(application.lastApplicationOperationId ?: unavailable()) ?: unavailable()
            // This closes genuine raw/UNKNOWN ancestry and the full typed accounting delta,
            // but its BEFORE liability is not authentic until the next loop step completes.
            if (matches(update, image)) {
                requireLocalSalesSource(update.command in setOf(LocalFinancialJournalProfileV3.RETURN_COMMAND, LocalRetailSupervisorCodecV1.COMMAND) &&
                    normalizedReturnRowMatches(update, fact.application.stored))
                val before = application.financialProof?.sources?.singleOrNull { it.role == "accepted" } ?: unavailable()
                return NormalizedRefundStep(update, before)
            }
        }
        unavailable()
    }

    private fun matches(original: RetainedCommandEvidence, image: NormalizedRefundImage): Boolean =
        original.committed && original.nativeOriginalProvenance == null && original.scope == view.scope &&
            original.aggregateWrites.singleOrNull { it.key == key }?.let {
                it.storageGeneration == image.generation && it.schema == image.schema && it.payload == image.payload && it.payloadDigest == image.digest
            } == true

    private fun earlier(previous: RetainedCommandEvidence, successor: RetainedCommandEvidence?) {
        if (successor != null) requireLocalSalesSource(previous.operationId != successor.operationId &&
            (previous.assignmentId != successor.assignmentId || previous.authorityEpoch != successor.authorityEpoch ||
                previous.sequence.value < successor.sequence.value))
        // Cross-authority ordering is the exact literal AFTER->BEFORE generation/bytes link,
        // not a global sequence or an asserted wall clock. Generations strictly decrease.
    }

    private fun unavailable(): Nothing = throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
}
