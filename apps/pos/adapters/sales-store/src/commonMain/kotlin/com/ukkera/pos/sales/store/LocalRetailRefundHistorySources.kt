package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*
import kotlinx.serialization.KSerializer

/** Actual, already joined BEFORE rows grouped as inert values. This is not a historical
 * CommandReadView, source provider, authorization, policy assessment or accepted-return root. */
internal class RetailRefundHistorySources(val returned: LocalReturnCut, val cash: LocalCashCut) {
    val resources: Set<RecoveryResourceKey> get() = returnCashResources(returned, cash)
    val rows: List<Pair<String, StoredAggregate>> get() = retailRefundBeforeRowsV3(returned, cash)
}

/** Same source/current custody links as the real refund reader. Every present row must come
 * from its strictly earlier WHOLE native business join. In particular an ELIGIBLE DTO, legacy
 * claim, stale commerce link or missing original rights/delivery producer is not qualified here. */
internal suspend fun retailRefundHistorySources(command: RuntimeHistoricalCommand,
    target: LocalJournalTargetV3): RetailRefundHistorySources {
    check(target.kind == SalesRecordingKind.REFUND_ATTESTATION)
    val saleId = checkNotNull(target.originalSaleId)
    val claimId = checkNotNull(target.originalClaimId)
    val saleSource = refundHistoryRequired(command, LocalSalesStoreProfile.key(LocalSalesStoreProfile.SALE_KIND, saleId))
    val saleRow = saleSource.row
    check(saleRow.schema == LocalSalesStoreProfile.schema(saleRow.key.kind) && saleRow.revision == Revision(1))
    val sale = SalesSnapshotCodec.decodePaidSale(saleRow.text(),
        SalesSnapshotBinding(SalesSnapshotKind.PAID_SALE, command.scope, saleId, saleRow.domainRevision()))
    val entitlementSource = refundHistoryRequired(command, LocalSalesStoreProfile.key(LocalSalesStoreProfile.ENTITLEMENT_KIND, saleId))
    val entitlementRow = entitlementSource.row
    check(entitlementRow.schema == LocalSalesStoreProfile.schema(entitlementRow.key.kind))
    val entitlement = SalesSnapshotCodec.decodeReceiptEntitlement(entitlementRow.text(),
        SalesSnapshotBinding(SalesSnapshotKind.RECEIPT_ENTITLEMENT, command.scope, saleId, entitlementRow.domainRevision()))
    check(SalesSnapshotCodec.encodePaidSale(entitlement.sale) == SalesSnapshotCodec.encodePaidSale(sale) &&
        command.claim(LocalSalesStoreProfile.unique("sale", saleId)) == sale.orderId &&
        command.claim(LocalSalesStoreProfile.unique("sale_order", sale.orderId)) == saleId &&
        command.claim(LocalSalesStoreProfile.unique("return_entitlement", saleId)) == saleId)
    val prices = refundHistoryPrices(command, sale, saleSource)
    val returnPolicyCapture = refundHistoryReturnPolicyCapture(command, sale, prices?.value)
    val caseSources = LocalReturnCaseHistorySources(command)
    val linkedEntitlement = prices?.value?.normalizedStock == true &&
        (command.aggregate(LocalReturnEntitlementProvenanceProfile.locator(saleId)) != null ||
            command.claim(LocalReturnEntitlementProvenanceProfile.locatorClaim(saleId)) != null)
    val original = if (linkedEntitlement) {
        // A present new linkage is source-required. Never fall back to the old selected-CASE
        // current-writer profile or reset an advanced entitlement to its initial Finalize.
        val joined = findReturnCaseHistoryEntitlement(command, saleId, caseSources) ?: refundHistoryUnavailable()
        check(refundHistorySameRow(joined.sale.stored, saleRow) && refundHistorySameRow(joined.entitlement.stored, entitlementRow) && joined.normalizedSource != null)
        joined
    } else LocalEntitlementCut(LocalSalesRow(saleRow, sale), LocalSalesRow(entitlementRow, entitlement), returnPolicyCapture, prices)
    val claimSource = refundHistoryRequired(command, LocalSalesStoreProfile.key(LocalSalesStoreProfile.RETURN_CLAIM_KIND, claimId))
    val claimRow = claimSource.row
    check(claimRow.schema == LocalSalesStoreProfile.schema(claimRow.key.kind))
    val claim = SalesSnapshotCodec.decodeReturnClaim(claimRow.text(),
        SalesSnapshotBinding(SalesSnapshotKind.RETURN_CLAIM, command.scope, claimId, claimRow.domainRevision()), entitlement)
    val acceptedSource = refundHistoryRequired(command, LocalSalesStoreProfile.key(LocalSalesStoreProfile.RETURN_KIND, target.documentId))
    val acceptedRow = acceptedSource.row
    check(acceptedRow.schema == LocalSalesStoreProfile.schema(acceptedRow.key.kind))
    val accepted = SalesSnapshotCodec.decodeAcceptedReturn(acceptedRow.text(),
        SalesSnapshotBinding(SalesSnapshotKind.ACCEPTED_RETURN, command.scope, target.documentId, acceptedRow.domainRevision()), entitlement, claim)
    check(accepted.saleId == saleId && accepted.claimId == claimId && claim.acceptedReturnId == accepted.id &&
        command.claim(LocalSalesStoreProfile.unique("accepted_return", accepted.id)) == saleId &&
        command.claim(LocalSalesStoreProfile.unique("return_claim_source", accepted.id)) == claimId &&
        command.claim(LocalSalesStoreProfile.unique("claim_acceptance", claimId)) == accepted.id)
    val caseKey = LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.CASE, claimId)
    val casePresent = command.aggregate(caseKey) != null || command.claim(LocalReturnCaseProfileV1.source("case", claimId)) != null ||
        command.claim(LocalReturnCaseProfileV1.source("claim", claimId)) != null
    val claimed = if (casePresent) {
        check(command.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.RETURN_INTAKE_KIND, claimId)) == null &&
            command.claim(LocalSalesStoreProfile.unique("return_intake", claimId)) == null)
        if (linkedEntitlement) {
            val qualified = caseSources.readClaim(original, claimId)
            check(refundHistorySameRow(qualified.claim.stored, claimRow))
            qualified.copy(caseRecoveryResources = qualified.caseRecoveryResources + refundHistoryLinkedCaseOrigin(command,
                caseSources, qualified, LocalSalesRow(acceptedRow, accepted), claimSource, acceptedSource))
        } else {
            // Preserve the explicit pre-linkage selected-CASE/current-writer compatibility
            // profile. Its evidence is not an appendable chain or a broader rights fallback.
            val resources = RefundCaseHistory(command).accepted(original, LocalSalesRow(claimRow, claim), claimSource,
                LocalSalesRow(acceptedRow, accepted), prices?.value, entitlementSource, acceptedSource)
            LocalClaimCut(original, null, LocalSalesRow(claimRow, claim), resources)
        }
    } else {
        // No updated normalized v1-intake entitlement writer is supported or inferred here.
        check(prices?.value?.normalizedStock != true)
        val intake = refundHistoryRoot(command, LocalSalesStoreProfile.RETURN_INTAKE_KIND, claimId, LocalReturnIntake.serializer(), 8_192) { value, revision ->
            check(value.scope.value() == command.scope && value.claimId == claimId && value.revision == revision && revision == Revision(1) &&
                value.boundSaleId == saleId && value.facts.saleId == saleId && value.facts.portionId == claim.portionId &&
                value.facts.quantity == claim.quantity && value.originalActorId == claim.claimantOperatorId)
            localReason(value.facts.reason)
        }
        check(command.claim(LocalSalesStoreProfile.unique("return_intake", claimId)) == intake.value.originalActorId &&
            command.claim(LocalSalesStoreProfile.unique("return_claim", claimId)) == saleId)
        val claimantWork = refundHistoryWork(command, intake.value.originalActorId)
        check(claimId !in claimantWork.value.pendingClaimIds)
        // The actual legacy intake/binding/acceptance originals must already be joined by an
        // explicitly installed original qualification producer. No policy/date is backfilled.
        LocalClaimCut(original, intake, LocalSalesRow(claimRow, claim))
    }
    val work = refundHistoryWork(command, accepted.acceptingOperatorId)
    check((accepted.id in work.value.returnIds) == (accepted.due.minor != 0L || accepted.unresolved))
    val returned = LocalReturnCut(claimed, LocalSalesRow(acceptedRow, accepted), work)
    return RetailRefundHistorySources(returned, refundHistoryCash(command, target.sessionId))
}

/** The selected obligation retains its own immutable acceptance even after unrelated returns
 * advance the sale entitlement. Its liability writer is a genuine earlier whole financial join;
 * current source lookup and each earlier financial producer retain their real BEFORE lineage. */
private suspend fun refundHistoryLinkedCaseOrigin(command: RuntimeHistoricalCommand,
    sources: LocalReturnCaseHistorySources, original: LocalClaimCut, accepted: LocalSalesRow<AcceptedReturn>,
    claimSource: RuntimeHistoricalAggregateSource, acceptedSource: RuntimeHistoricalAggregateSource): Set<RecoveryResourceKey> {
    val dependencies = LocalReturnCaseDependencies()
    val cut = sources.readCase(original.claim.value.id, dependencies)
    check(cut.row.value.stage == LocalReturnCaseStageV1.ACCEPTED_DISPOSITION_PENDING &&
        cut.row.value.acceptedReturnId == accepted.value.id)
    val producer = sources.evidence(cut.row.value.lastOperationId, dependencies)
    val retained = command.earlierOriginal(producer.operationId)
    check(claimSource.original === retained)
    val write = producer.aggregateWrites.singleOrNull { it.key == accepted.stored.key } ?: refundHistoryUnavailable()
    check(write.storageGeneration == Revision(1) && write.schema == accepted.stored.schema)
    val initial = SalesSnapshotCodec.decodeAcceptedReturn(write.payload.text(),
        SalesSnapshotBinding(SalesSnapshotKind.ACCEPTED_RETURN, command.scope, accepted.value.id, Revision(0)),
        original.original.entitlement.value, original.claim.value)
    check(refundHistorySameAccepted(initial, accepted.value) &&
        (accepted.value.revision != Revision(0) || accepted.stored.payload == write.payload &&
            accepted.stored.digest == write.payloadDigest && acceptedSource.original === retained))
    for (attempt in accepted.value.attempts) check(
        command.claim(LocalSalesStoreProfile.unique("refund_attempt", attempt.id)) == accepted.value.id &&
            command.claim(LocalSalesStoreProfile.unique("refund_record", attempt.id)) ==
                (if (attempt.refund == null) null else accepted.value.id) &&
            command.claim(CashNonSaleStoreProfile.cashSourceClaim(attempt.id)) ==
                (if (attempt.refund != null && attempt.tender == Tender.Cash) attempt.originalCashSessionId else null))
    if (accepted.value.revision != Revision(0)) {
        val financial = (acceptedSource.original as? RetainedOriginalV2.Legacy)?.evidence ?: refundHistoryUnavailable()
        check(command.earlierOriginal(financial.operationId) === acceptedSource.original)
        refundHistoryClosedFinancialSource(acceptedSource, accepted.value)
    }
    return returnCaseResources(cut) + dependencies.snapshot()
}

private suspend fun refundHistoryCash(command: RuntimeHistoricalCommand, id: DomainId): LocalCashCut {
    val row = refundHistoryRequired(command, CashNonSaleStoreProfile.sessionKey(id)).row
    check(row.schema == CashNonSaleStoreProfile.SESSION_SCHEMA)
    val cash = SalesSnapshotCodec.decodeCashSession(row.text(),
        SalesSnapshotBinding(SalesSnapshotKind.CASH_SESSION, command.scope, id, row.domainRevision()))
    check(command.claim(LocalSalesStoreProfile.unique("cash_session", id)) == id &&
        command.claim(LocalSalesStoreProfile.unique("opening_source", cash.openingSourceId)) == id)
    val drawer = refundHistoryRoot(command, LocalSalesStoreProfile.DRAWER_KIND, cash.drawerId, LocalDrawerState.serializer()) { value, revision ->
        check(value.scope.value() == command.scope && value.id == cash.drawerId && value.revision == revision &&
            (value.activeSessionId == null || value.closingTransferId == null))
        localLabel(value.label)
    }
    val custodian = refundHistoryRoot(command, LocalSalesStoreProfile.CUSTODIAN_KIND, cash.custodianId, LocalCustodianState.serializer()) { value, revision ->
        check(value.scope.value() == command.scope && value.id == cash.custodianId && value.revision == revision)
    }
    check(command.claim(LocalSalesStoreProfile.activeDrawer(drawer.value.id)) == drawer.value.activeSessionId &&
        command.claim(LocalSalesStoreProfile.activeCustodian(custodian.value.id)) == custodian.value.activeSessionId &&
        drawer.value.currency == cash.expectedCash.currency)
    if (cash.status == CashSessionStatus.OPEN || cash.status == CashSessionStatus.CLOSING) check(
        drawer.value.activeSessionId == id && custodian.value.activeSessionId == id && drawer.value.closingTransferId == null)
    else check(drawer.value.activeSessionId != id && custodian.value.activeSessionId != id)
    return LocalCashCut(LocalSalesRow(row, cash), drawer, custodian, refundHistoryWork(command, cash.custodianId))
}

private suspend fun refundHistoryWork(command: RuntimeHistoricalCommand, id: DomainId): LocalSalesRow<LocalWorkState> =
    refundHistoryRoot(command, LocalSalesStoreProfile.WORK_KIND, id, LocalWorkState.serializer()) { value, revision ->
        check(value.scope.value() == command.scope && value.id == id && value.revision == revision &&
            listOf(value.orderIds, value.returnIds, value.pendingClaimIds).all { it.size <= LocalSalesStoreProfile.MAX_OPEN_WORK && it.distinct().size == it.size })
    }

private suspend fun refundHistoryPrices(command: RuntimeHistoricalCommand, sale: PaidSale,
    saleSource: RuntimeHistoricalAggregateSource): LocalSalesRow<LocalSalesPriceSourcesV2>? {
    val reference = sale.priceSourceReference ?: return null // Explicit historical profile only.
    val paired = refundHistoryRequired(command, LocalSalesStoreProfile.key(LocalSalesStoreProfile.PRICE_SOURCES_KIND, reference.id))
    check(paired.row.schema == LocalSalesStoreProfile.PRICE_SOURCES_SCHEMA && paired.row.revision == Revision(1))
    val prices = requirePriceSources(paired.row.payload, command.scope, sale.orderId, sale.quote, reference)
    val quote = command.earlierOriginal(prices.originalQuoteOperationId) ?: refundHistoryUnavailable()
    check(paired.original === quote)
    if (prices.normalizedStock) {
        check(quote is RetainedOriginalV2.Streamed)
        requireNormalizedPaymentQuoteRequest(quote.evidence, sale.orderId, prices.quoteRevision)
        requireCommerceWrite(quote.evidence, paired.row)
        val finalized = (saleSource.original as? RetainedOriginalV2.Streamed)?.evidence ?: refundHistoryUnavailable()
        check(finalized.identity.producer.command == StockStreamingCommandsV2.COLLECTION)
        val request = LocalCollectionStockCodecV2.decode(finalized.normalized) as? LocalCollectionStockRequestV2.Finalize
            ?: refundHistoryUnavailable()
        check(request.saleId == sale.id && request.orderId == sale.orderId && request.issueId == sale.stockIssueId)
        refundHistoryCommerceLink(command, saleSource)
    } else check(quote is RetainedOriginalV2.Legacy && quote.evidence.command == LocalSalesStoreProfile.ORDER_COMMAND)
    return LocalSalesRow(paired.row, prices)
}

/** Exact immutable V2 companion on the SAME whole Create OR Replace quote, not current policy. */
private suspend fun refundHistoryReturnPolicyCapture(command: RuntimeHistoricalCommand, sale: PaidSale,
    prices: LocalSalesPriceSourcesV2?): LocalSalesRow<LocalSalesReturnPolicyCaptureV2>? {
    val reference = sale.returnPolicySourceReference ?: return null
    val actualPrices = prices ?: refundHistoryUnavailable()
    check(actualPrices.normalizedStock)
    val paired = refundHistoryRequired(command, AggregateKey(LocalSalesReturnPolicyCaptureCodecV2.KIND, reference.id))
    check(paired.row.revision == Revision(1) && paired.row.schema == LocalSalesReturnPolicyCaptureCodecV2.SCHEMA)
    val capture = requireReturnPolicyCapture(paired.row.payload, command.scope, sale.orderId, sale.quote, reference, actualPrices.sources)
    check(capture.originalQuoteOperationId == actualPrices.originalQuoteOperationId && capture.id == actualPrices.id)
    val quote = command.earlierOriginal(capture.originalQuoteOperationId) as? RetainedOriginalV2.Streamed ?: refundHistoryUnavailable()
    check(paired.original === quote)
    requireNormalizedPaymentQuoteRequest(quote.evidence, sale.orderId, capture.quoteRevision)
    requireCommerceWrite(quote.evidence, paired.row)
    return LocalSalesRow(paired.row, capture)
}

private suspend fun refundHistoryCommerceLink(command: RuntimeHistoricalCommand, source: RuntimeHistoricalAggregateSource) {
    val paired = refundHistoryRequired(command, commerceLinkKey(source.row.key))
    check(paired.row.schema == commerceLinkSchema() && paired.original === source.original)
    val link = localSalesDecode(paired.row.payload, CommerceRowLinkV2.serializer())
    val producer = when (val original = source.original) {
        is RetainedOriginalV2.Legacy -> with(original.evidence) { StreamedProducerIdentityV2(operationId, this.command, originalActorId, semanticDigest) }
        is RetainedOriginalV2.Streamed -> original.evidence.identity.producer
    }
    check(link.scope == command.scope && link.kind == source.row.key.kind && link.id == source.row.key.id &&
        link.generation == source.row.revision && link.digest == source.row.digest &&
        producer == StreamedProducerIdentityV2(link.operationId, CommandKey(link.command, link.version), link.actorId, link.semanticDigest))
    // This is the immutable paid-sale link only. Advanced entitlement is joined separately
    // to its actual complete CASE acceptance, never to a stale Finalize projection.
    val actual = (source.original as? RetainedOriginalV2.Streamed)?.evidence ?: refundHistoryUnavailable()
    check(producer.command == StockStreamingCommandsV2.COLLECTION)
    requireCommerceWrite(actual, source.row)
    requireCommerceWrite(actual, paired.row)
}

/** CASE source linkage only, on already WHOLE-joined original producers. This does not install
 * an accepting rights/delivery source or turn an assessment DTO into qualification authority. */
private class RefundCaseHistory(private val command: RuntimeHistoricalCommand) {
    private val dependencies = LocalReturnCaseDependencies()
    private val fullEvents = mutableMapOf<OperationId, LocalSalesRow<LocalReturnCaseEventV1>>()
    private val visiting = mutableSetOf<OperationId>()

    private suspend fun producer(operation: OperationId, before: RetainedCommandEvidence? = null): RetainedOriginalV2.Legacy {
        val original = command.earlierOriginal(operation) as? RetainedOriginalV2.Legacy ?: refundHistoryUnavailable()
        val value = original.evidence
        check(value.committed && value.scope == command.scope && value.command == LocalReturnCaseProfileV1.COMMAND && value.jobs.isEmpty() &&
            LocalReturnCaseCommandCodecV1.normalize(value.normalized) == value.normalized &&
            (before == null || value.assignmentId == before.assignmentId && value.authorityEpoch == before.authorityEpoch && value.sequence.value < before.sequence.value))
        dependencies.producer(operation, value.originalActorId, LocalReturnCaseCommandCodecV1.decode(value.normalized).caseId())
        return original
    }

    private suspend fun event(operation: OperationId, indexOnly: Boolean = false): LocalSalesRow<LocalReturnCaseEventV1> {
        if (!indexOnly) fullEvents[operation]?.let { return it }
        check(visiting.size < LocalReturnCaseProfileV1.MAX_EVENTS && visiting.add(operation))
        try {
            val paired = refundHistoryRequired(command, LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.EVENT, DomainId(operation.value)))
            val original = producer(operation)
            check(paired.original === original && paired.row.revision == Revision(1) && paired.row.schema == LocalSalesStoreProfile.schema(paired.row.key.kind))
            val value = localSalesDecode(paired.row.payload, LocalReturnCaseEventV1.serializer())
            validateReturnCaseState(value.after)
            check(value.operationId == operation && value.after.lastOperationId == operation && value.after.scope.value() == command.scope &&
                LocalReturnCaseCommandCodecV1.decode(original.evidence.normalized).caseId() == value.after.caseId &&
                original.evidence.originalActorId == value.after.ownerId && value.recordedAt == original.evidence.recordedAt &&
                command.claim(LocalReturnCaseProfileV1.source("event", DomainId(operation.value))) == value.after.caseId &&
                value.sourceRecoveryResources.size <= 128 && value.sourceRecoveryResources.distinct().size == value.sourceRecoveryResources.size &&
                value.sourceRecoveryResources == value.sourceRecoveryResources.sortedWith(compareBy({ it.kind.name }, { it.resourceId.value })))
            val before = value.beforeCaseOperationId?.let { previous ->
                val p = producer(previous, original.evidence)
                val write = p.evidence.aggregateWrites.single { it.key == LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.CASE, value.after.caseId) }
                check(write.payloadDigest == value.beforeCaseDigest)
                localSalesDecode(write.payload, LocalReturnCaseStateV1.serializer())
            }
            value.beforeIndexOperationId?.let { producer(it, original.evidence) }
            if (!indexOnly) {
                when (val request = LocalReturnCaseCommandCodecV1.decode(original.evidence.normalized)) {
                    is LocalReturnCaseRequestV1.AppendReceiptEvidence -> before?.bindingOperationId?.let { producer(it, original.evidence) }
                    is LocalReturnCaseRequestV1.Qualify -> {
                        producer(checkNotNull(before?.bindingOperationId), original.evidence)
                        references(value.qualificationSources)
                        check(value.assessment?.sources().orEmpty().all { it in value.qualificationSources } &&
                            value.sourceRecoveryResources.containsAll(returnCaseSourceResources(value.qualificationSources)))
                    }
                    is LocalReturnCaseRequestV1.Accept -> {
                        producer(checkNotNull(before?.bindingOperationId), original.evidence)
                        event(request.decisionOperationId)
                    }
                    is LocalReturnCaseRequestV1.Record -> Unit
                }
                dependencies.qualifiedEvent(value)
            }
            return LocalSalesRow(paired.row, value).also { if (!indexOnly) fullEvents[operation] = it }
        } finally { visiting.remove(operation) }
    }

    private suspend fun references(values: List<LocalReturnCaseSourceReferenceV1>) {
        check(values.size <= 17 && values.distinct().size == values.size)
        for (reference in values) {
            val paired = refundHistoryRequired(command, AggregateKey(reference.kind, reference.id))
            if (reference.kind == LocalSalesReturnPolicyCaptureCodecV2.KIND && reference.schema == LocalSalesReturnPolicyCaptureCodecV2.SCHEMA) {
                val original = command.earlierOriginal(reference.producerOperationId) as? RetainedOriginalV2.Streamed
                    ?: refundHistoryUnavailable()
                val capture = LocalSalesReturnPolicyCaptureCodecV2.decode(paired.row.payload)
                check(paired.original === original && paired.row.revision == reference.storageRevision &&
                    paired.row.schema == reference.schema && paired.row.digest == reference.digest && capture.scope == command.scope &&
                    capture.id == reference.id && capture.originalQuoteOperationId == reference.producerOperationId &&
                    original.evidence.identity.producer.operationId == reference.producerOperationId &&
                    original.evidence.identity.producer.originalActorId == reference.producerActorId &&
                    original.evidence.identity.producer.command == CommandKey(reference.producerCommand, reference.producerVersion))
                requireNormalizedPaymentQuoteRequest(original.evidence, capture.orderId, capture.quoteRevision)
                requireCommerceWrite(original.evidence, paired.row)
                continue
            }
            // The explicit historical V1/publication/rights/customer-receipt reader is intact.
            // No arbitrary streamed source is accepted outside the closed V2 capture branch.
            val original = command.earlierOriginal(reference.producerOperationId) as? RetainedOriginalV2.Legacy
                ?: refundHistoryUnavailable()
            val identity = with(original.evidence) { StreamedProducerIdentityV2(operationId, this.command, originalActorId, semanticDigest) }
            check(paired.original === original && paired.row.revision == reference.storageRevision && paired.row.schema == reference.schema &&
                paired.row.digest == reference.digest && identity == StreamedProducerIdentityV2(reference.producerOperationId,
                    CommandKey(reference.producerCommand, reference.producerVersion), reference.producerActorId, identity.semanticDigest))
        }
    }

    suspend fun accepted(original: LocalEntitlementCut, claim: LocalSalesRow<ReturnClaim>, claimSource: RuntimeHistoricalAggregateSource,
        accepted: LocalSalesRow<AcceptedReturn>, prices: LocalSalesPriceSourcesV2?, entitlementSource: RuntimeHistoricalAggregateSource,
        acceptedSource: RuntimeHistoricalAggregateSource): Set<RecoveryResourceKey> {
        val caseId = claim.value.id
        val stateRow = refundHistoryRequired(command, LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.CASE, caseId))
        val state = localSalesDecode(stateRow.row.payload, LocalReturnCaseStateV1.serializer())
        validateReturnCaseState(state)
        check(stateRow.row.schema == LocalSalesStoreProfile.schema(stateRow.row.key.kind) && stateRow.row.domainRevision() == state.revision &&
            state.scope.value() == command.scope && state.caseId == caseId && state.stage == LocalReturnCaseStageV1.ACCEPTED_DISPOSITION_PENDING &&
            state.acceptedReturnId == accepted.value.id && state.ownerId == command.claim(LocalReturnCaseProfileV1.source("case", caseId)) &&
            command.claim(LocalReturnCaseProfileV1.source("claim", caseId)) == caseId &&
            command.claim(LocalSalesStoreProfile.unique("return_claim", caseId)) == original.sale.value.id)
        val last = event(state.lastOperationId)
        val lastOriginal = producer(state.lastOperationId)
        check(stateRow.original === lastOriginal && last.value.after == state && claimSource.original === lastOriginal)
        if (prices?.normalizedStock == true) {
            // Kernel aggregateSource/earlierOriginal supplies the actual already WHOLE-joined
            // producer. The selected CASE must be the exact current entitlement writer; a
            // later unsupported acceptance or stale commerce link remains a history hold.
            val request = LocalReturnCaseCommandCodecV1.decode(lastOriginal.evidence.normalized) as? LocalReturnCaseRequestV1.Accept
                ?: refundHistoryUnavailable()
            check(entitlementSource.original === lastOriginal && request.caseId == caseId && request.returnId == accepted.value.id &&
                request.expectedEntitlementRevision.next().next() == entitlementSource.row.revision &&
                last.value.entitlementRevision == original.entitlement.value.revision)
            val entitlementWrite = lastOriginal.evidence.aggregateWrites.singleOrNull { it.key == entitlementSource.row.key }
                ?: refundHistoryUnavailable()
            check(entitlementWrite.storageGeneration == entitlementSource.row.revision && entitlementWrite.schema == entitlementSource.row.schema &&
                entitlementWrite.payload == entitlementSource.row.payload && entitlementWrite.payloadDigest == entitlementSource.row.digest &&
                sha256(entitlementSource.row.payload.copyBytes()) == entitlementSource.row.digest)
            if (accepted.value.revision == Revision(0)) check(acceptedSource.original === lastOriginal)
            else refundHistoryClosedFinancialSource(acceptedSource, accepted.value)
        }
        val first = if (state.originalOperationId == state.lastOperationId) last else event(state.originalOperationId)
        check(first.value.action == LocalReturnCaseActionV1.RECORDED && first.value.after.caseId == caseId &&
            first.value.after.ownerId == state.ownerId && first.value.after.facts == state.facts && first.value.after.recordedAt == state.recordedAt)
        val work = refundHistoryRoot(command, LocalReturnCaseProfileV1.WORK, state.ownerId, LocalReturnCaseWorkV1.serializer()) { value, revision ->
            validateReturnCaseWork(value)
            check(value.scope == state.scope && value.ownerId == state.ownerId && value.revision == revision &&
                value.entries.singleOrNull { it.caseId == caseId } == LocalReturnCaseWorkEntryV1(caseId, state.revision, state.stage))
        }
        check(command.claim(LocalReturnCaseProfileV1.source("work", state.ownerId)) == state.ownerId)
        val workEvent = event(work.value.lastOperationId, indexOnly = true)
        check(workEvent.value.after.ownerId == state.ownerId &&
            refundHistoryRequired(command, work.stored.key).original === producer(work.value.lastOperationId))
        val binding = event(checkNotNull(state.bindingOperationId))
        val decision = event(checkNotNull(state.decisionOperationId))
        val selected = checkNotNull(binding.value.binding)
        val assessment = checkNotNull(decision.value.assessment)
        check(binding.value.after.caseId == caseId && decision.value.after.caseId == caseId && decision.value.action == LocalReturnCaseActionV1.BOUND &&
            decision.value.assessedBindingDigest == binding.stored.digest && assessment.outcome == LocalReturnOrdinaryAssessmentV1.ELIGIBLE &&
            state.facts.requestedRemedy == LocalReturnRequestedRemedyV1.REFUND && selected.saleId == original.sale.value.id &&
            claim.value.claimantOperatorId == state.ownerId && claim.value.portionId == selected.portionId && claim.value.quantity == selected.quantity &&
            claim.value.acceptedReturnId == state.acceptedReturnId &&
            claim.value.evidence == returnCaseQualifiedClaimEvidence(state, assessment, decision.value.ordinaryInspection))
        val reference = checkNotNull(assessment.salePolicyCapture)
        val sale = original.sale.value
        if (reference.kind == LocalSalesReturnPolicyCaptureCodecV2.KIND && reference.schema == LocalSalesReturnPolicyCaptureCodecV2.SCHEMA) {
            val capture = original.returnPolicyCapture ?: refundHistoryUnavailable()
            val saleReference = sale.returnPolicySourceReference ?: refundHistoryUnavailable()
            check(saleReference.id == reference.id && saleReference.digest == reference.digest &&
                capture.stored.revision == reference.storageRevision && capture.stored.digest == reference.digest &&
                capture.value.originalQuoteOperationId == reference.producerOperationId)
            references(listOf(reference))
            val portion = capture.value.input.portions.single { it.portionId == selected.portionId }
            val publication = capture.value.input.publications.single { it.publicationId == portion.publicationId }
            check(publication.state == LocalSalesReturnPromiseStateV2.SIGNED_PRESENT)
            val promise = publication.promise ?: refundHistoryUnavailable()
            check(assessment.policyId == promise.policyId && assessment.policyRevision == promise.policyRevision &&
                prices?.sources?.singleOrNull { it.portionId == selected.portionId }?.skuId == publication.skuId)
            references(listOf(publication.source))
        } else {
            check(sale.returnPolicySourceReference == null && reference.kind == LocalSalesReturnPolicyCaptureCodecV1.KIND &&
                reference.schema == LocalSalesReturnPolicyCaptureCodecV1.SCHEMA)
            val captureRow = refundHistoryRequired(command, AggregateKey(reference.kind, reference.id)).row
            val capture = LocalSalesReturnPolicyCaptureCodecV1.decode(captureRow.payload)
            check(capture.organizationId == command.scope.organizationId && capture.companyId == command.scope.companyId && capture.branchId == command.scope.branchId &&
                capture.id == reference.id && capture.originalQuoteOperationId == reference.producerOperationId && capture.orderId == sale.orderId &&
                capture.quoteRevision == sale.quote.revision && capture.quoteDigest == sha256(StoredPricingSnapshotCodec.encode(sale.quote).encodeToByteArray(throwOnInvalidSequence = true)) &&
                captureRow.digest == reference.digest && capture.portions.map { it.portionId }.toSet() == sale.quote.lines.map { it.portionId }.toSet())
            val portion = capture.portions.single { it.portionId == selected.portionId }
            references(listOf(portion.promiseSource))
            check(prices == null || prices.sources.singleOrNull { it.portionId == selected.portionId }?.skuId == portion.skuId)
        }
        val insert = lastOriginal.evidence.aggregateWrites.single { it.key == accepted.stored.key }
        check(insert.storageGeneration == Revision(1) && insert.schema == accepted.stored.schema)
        val initial = SalesSnapshotCodec.decodeAcceptedReturn(insert.payload.text(),
            SalesSnapshotBinding(SalesSnapshotKind.ACCEPTED_RETURN, command.scope, accepted.value.id, Revision(0)), original.entitlement.value, claim.value)
        check(refundHistorySameAccepted(initial, accepted.value) &&
            (accepted.value.revision != Revision(0) || accepted.stored.payload == insert.payload && accepted.stored.digest == insert.payloadDigest))
        val cut = LocalReturnCaseCut(LocalSalesRow(stateRow.row, state), last, work, binding, decision, dependencies.snapshot())
        return returnCaseResources(cut) + dependencies.snapshot() + entitlementReturnPolicyResources(original)
    }
}

/** Closed selectors on an actual earlier WHOLE business join; never a new business admission. */
private fun refundHistoryClosedFinancialSource(source: RuntimeHistoricalAggregateSource, accepted: AcceptedReturn) {
    val original = (source.original as? RetainedOriginalV2.Legacy)?.evidence ?: refundHistoryUnavailable()
    check(original.nativeOriginalProvenance == null)
    requireRetailRetainedRowV3(original, source.row)
    val result = LocalFinancialResultCodecV3.decode(original.result)
    val target = when (original.command) {
        LocalFinancialJournalProfileV3.RETURN_COMMAND -> when (val request = LocalReturnCommandCodecV3.decode(original.normalized)) {
            is LocalReturnRequestV3.Start -> {
                check(result is LocalFinancialResultV3.AttemptStarted && result.target == request.target(original.operationId))
                result.target
            }
            is LocalReturnRequestV3.ApplyObservation -> {
                check(result is LocalFinancialResultV3.Applied && result.applicationOperationId == original.operationId &&
                    request.phase == result.phase && request.recordingOperationId == result.target.factOperation(result.phase))
                result.target
            }
            is LocalReturnRequestV3.RecordOriginal, is LocalReturnRequestV3.RecordResolution -> refundHistoryUnavailable()
        }
        LocalRetailSupervisorCodecV1.COMMAND -> {
            val request = LocalRetailSupervisorCodecV1.decode(original.normalized) as? LocalRetailSupervisorRequestV1.Apply
                ?: refundHistoryUnavailable()
            check(result is LocalFinancialResultV3.Applied && result.applicationOperationId == original.operationId &&
                request.kind == SalesRecordingKind.REFUND_ATTESTATION && request.phase == result.phase &&
                request.recordingOperationId == result.target.factOperation(result.phase))
            result.target
        }
        else -> refundHistoryUnavailable()
    }
    check(target.kind == SalesRecordingKind.REFUND_ATTESTATION && target.documentId == accepted.id &&
        target.originalSaleId == accepted.saleId && target.originalClaimId == accepted.claimId &&
        accepted.attempts.any { it.id == target.attemptId && it.originalOperationId == target.intentOperationId })
}

private fun refundHistorySameRow(first: StoredAggregate, second: StoredAggregate): Boolean =
    first.key == second.key && first.revision == second.revision && first.schema == second.schema &&
        first.payload == second.payload && first.digest == second.digest

private fun refundHistorySameAccepted(first: AcceptedReturn, current: AcceptedReturn): Boolean =
    first.id == current.id && first.scope == current.scope && first.saleId == current.saleId && first.claimId == current.claimId &&
        first.portionId == current.portionId && first.quantity == current.quantity && first.consumedQuantityBefore == current.consumedQuantityBefore &&
        first.acceptingOperatorId == current.acceptingOperatorId && first.evidence == current.evidence && first.requestedDisposition == current.requestedDisposition &&
        first.inspectionEvidence == current.inspectionEvidence && first.originalPayable == current.originalPayable &&
        first.components == current.components && first.tenderObligations == current.tenderObligations

private suspend fun <T> refundHistoryRoot(command: RuntimeHistoricalCommand, kind: String, id: DomainId,
    serializer: KSerializer<T>, maximum: Int = LOCAL_SALES_MAX_BYTES, checkValue: (T, Revision) -> Unit): LocalSalesRow<T> {
    val row = refundHistoryRequired(command, LocalSalesStoreProfile.key(kind, id)).row
    check(row.schema == LocalSalesStoreProfile.schema(kind))
    val value = localSalesDecode(row.payload, serializer, maximum)
    checkValue(value, row.domainRevision())
    return LocalSalesRow(row, value)
}

internal suspend fun refundHistoryRequired(command: RuntimeHistoricalCommand, key: AggregateKey): RuntimeHistoricalAggregateSource =
    command.aggregateSource(key) ?: refundHistoryUnavailable()
private fun StoredBytes.text(): String = copyBytes().decodeToString(throwOnInvalidSequence = true)
internal fun refundHistoryUnavailable(): Nothing = error("Original accepted retail refund source unavailable")
