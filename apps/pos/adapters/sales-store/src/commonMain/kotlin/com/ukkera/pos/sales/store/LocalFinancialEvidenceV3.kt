package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateChange
import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.ClaimChange
import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.PhysicalRecordingAction
import com.ukkera.pos.database.PhysicalRecordingOutcome
import com.ukkera.pos.database.PhysicalRecordingStartKind
import com.ukkera.pos.database.ProposedOutcome
import com.ukkera.pos.database.ReservedOperations
import com.ukkera.pos.database.RetainedClaimAction
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.database.StockReadEvidenceV1
import com.ukkera.pos.database.StreamedProducerIdentityV2
import com.ukkera.pos.database.StockRelationalKindsV2
import com.ukkera.pos.inventory.store.LocalStockPaymentObservations
import com.ukkera.pos.inventory.store.LocalStockStoreProfile
import com.ukkera.pos.sales.AcceptedReturn
import com.ukkera.pos.sales.AttemptStatus
import com.ukkera.pos.sales.CashSession
import com.ukkera.pos.sales.CashSessionStatus
import com.ukkera.pos.sales.CollectionLock
import com.ukkera.pos.sales.CollectionOutcome
import com.ukkera.pos.sales.OwnedOrder
import com.ukkera.pos.sales.PaymentAttempt
import com.ukkera.pos.sales.ReceiptEntitlement
import com.ukkera.pos.sales.RefundAttempt
import com.ukkera.pos.sales.RefundOutcome
import com.ukkera.pos.sales.ReturnClaim
import com.ukkera.pos.sales.SalesSnapshotBinding
import com.ukkera.pos.sales.SalesSnapshotCodec
import com.ukkera.pos.sales.SalesSnapshotKind
import com.ukkera.pos.sales.SalesSnapshotLinks
import com.ukkera.pos.sales.Tender
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.Serializable

/** Closed interpretation of the EFFECTIVE post-CAS proposal. No decoder creates a native permit. */
internal fun retailPhysicalOutcomeV3(normalized: StoredBytes, effectiveOutcome: ProposedOutcome,
    context: CommandContext,
    tenderPolicies: HistoricalSalesTenderPolicySourceV1 = UnavailableHistoricalSalesTenderPolicySourceV1): PhysicalRecordingOutcome? {
    val action = retailBodyActionV3(context.key, normalized) ?: return null // SQL-only application stays unclassified.
    // Kernel conflict/deadline rejection bytes are not necessarily a sales/v3 result. A reject
    // has no effects and is always non-arming; never decode one as a successful domain start.
    if (effectiveOutcome is ProposedOutcome.Reject) return PhysicalRecordingOutcome.NonArming
    val apply = effectiveOutcome as? ProposedOutcome.Apply ?: return null
    check(apply.jobs.isEmpty()) { "sales.journal.unexpected_job" }
    val shape = RetailOperationShapeV3(context.scope, context.originalActorId, context.operationId, context.key,
        context.semanticDigest, normalized, apply.result, apply.changes, apply.claims, apply.mustCommitBefore, null, tenderPolicies)
    shape.requireBinding()
    val result = LocalFinancialResultCodecV3.decode(apply.result)
    if (result is LocalFinancialResultV3.StockHeld) {
        check(action == PhysicalRecordingAction.START_PAYMENT)
        requireRetailHeldShapeV3(shape, result)
        return PhysicalRecordingOutcome.NonArming
    }
    val physical = context.physicalRecording ?: return null
    val requirement = retailRequirementFromAdmissionV3(physical.admission.requirementBytes)
    check(physical.matches(retailAdmission(requirement, action))) { "sales.journal.effective_admission" }
    if (result is LocalFinancialResultV3.AttemptStarted) {
        check(action == PhysicalRecordingAction.START_PAYMENT || action == PhysicalRecordingAction.START_REFUND)
        val start = requireRetailStartShapeV3(shape, result)
        check(start.slot.requirement().canonicalBytes == requirement.canonicalBytes &&
            context.physicalParticipants?.let { it.authority == start.slot.domain.authority &&
                it.clientDeviceId == requirement.clientDeviceId } == true)
        requireRetailExactChangesV3(start.gatedChanges.filter { it.key.kind == LocalPhysicalJournalFormatV1.SOURCE_KIND }, physical.sourceChanges)
        when (start.slot.target.kind) {
            SalesRecordingKind.PAYMENT_ATTESTATION -> requireRetailPaymentStartEffectsV3(shape, start)
            SalesRecordingKind.REFUND_ATTESTATION -> requireRetailRefundStartEffectsV3(shape, start)
        }
        return PhysicalRecordingOutcome.Start(if (start.slot.target.kind == SalesRecordingKind.PAYMENT_ATTESTATION)
            PhysicalRecordingStartKind.PAYMENT else PhysicalRecordingStartKind.REFUND, start.gatedChanges, start.lineageClaims)
    }
    if (result is LocalFinancialResultV3.Pending &&
        (action == PhysicalRecordingAction.RETAIN_ORIGINAL || action == PhysicalRecordingAction.RETAIN_RESOLUTION)) {
        check(physical.sourceChanges.isEmpty())
        val fact = requireRetailRawShapeV3(shape, requirement)
        return PhysicalRecordingOutcome.FactImported(retailJournalDigestV3(normalized), listOf(retailFactWriteV3(fact)))
    }
    // A generic Applied, already-applied, pending financial retry or arbitrary Apply is NOT a
    // physical start/import. Even a self-consistent result digest cannot change its subtype.
    return null
}

private fun retailBodyActionV3(key: CommandKey, body: StoredBytes): PhysicalRecordingAction? = when (key) {
    LocalFinancialJournalProfileV3.COLLECTION_COMMAND -> when (LocalCollectionCommandCodecV3.decode(body)) {
        is LocalCollectionRequestV3.Start -> PhysicalRecordingAction.START_PAYMENT
        is LocalCollectionRequestV3.RecordOriginal -> PhysicalRecordingAction.RETAIN_ORIGINAL
        is LocalCollectionRequestV3.RecordResolution -> PhysicalRecordingAction.RETAIN_RESOLUTION
        is LocalCollectionRequestV3.ApplyObservation -> null
    }
    LocalFinancialJournalProfileV3.RETURN_COMMAND -> when (LocalReturnCommandCodecV3.decode(body)) {
        is LocalReturnRequestV3.Start -> PhysicalRecordingAction.START_REFUND
        is LocalReturnRequestV3.RecordOriginal -> PhysicalRecordingAction.RETAIN_ORIGINAL
        is LocalReturnRequestV3.RecordResolution -> PhysicalRecordingAction.RETAIN_RESOLUTION
        is LocalReturnRequestV3.ApplyObservation -> null
    }
    else -> error("sales.journal.command")
}

/** Validation-only values. NEVER a RetainedCommandEvidence, authorization, source receipt or executable retry. */
private class RetailOperationShapeV3(
    val scope: BranchScope, val actorId: DomainId, val operationId: OperationId, val command: CommandKey,
    val digest: PayloadDigest, val body: StoredBytes, val result: StoredBytes, val changes: List<AggregateChange>,
    val claims: List<ClaimChange>, val deadline: UtcInstant?, val recordedAt: UtcInstant?,
    val tenderPolicies: HistoricalSalesTenderPolicySourceV1,
) {
    fun requireBinding() {
        check(semanticDigest(scope, actorId, command, body) == digest &&
            changes.map { it.key }.distinct().size == changes.size && claims.map { it.key }.distinct().size == claims.size)
        check(when (command) {
            LocalFinancialJournalProfileV3.COLLECTION_COMMAND -> LocalCollectionCommandCodecV3.normalize(body) == body
            LocalFinancialJournalProfileV3.RETURN_COMMAND -> LocalReturnCommandCodecV3.normalize(body) == body
            LocalRetailSupervisorCodecV1.COMMAND -> LocalRetailSupervisorCodecV1.normalize(body) == body
            else -> false
        })
    }
}

internal class LocalRetailStartEnvelopeV3(val slot: LocalRetailJournalSlotV3, val gatedChanges: List<AggregateChange>,
    val lineageClaims: List<ClaimChange>, val result: LocalFinancialResultV3.AttemptStarted)

private fun requireRetailStartShapeV3(shape: RetailOperationShapeV3,
    result: LocalFinancialResultV3.AttemptStarted): LocalRetailStartEnvelopeV3 {
    shape.requireBinding()
    val target: LocalJournalTargetV3
    val amount: Money
    val documentRevision: Revision
    val sessionRevision: Revision
    val masterId: DomainId
    val clientId: DomainId
    when (shape.command) {
        LocalFinancialJournalProfileV3.COLLECTION_COMMAND -> {
            val request = LocalCollectionCommandCodecV3.decode(shape.body) as? LocalCollectionRequestV3.Start ?: error("sales.journal.start_subtype")
            target = request.target(shape.operationId); amount = request.amount
            documentRevision = request.expectedCollectionRevision; sessionRevision = request.expectedSessionRevision
            masterId = request.masterAllocationId; clientId = request.clientAllocationId
        }
        LocalFinancialJournalProfileV3.RETURN_COMMAND -> {
            val request = LocalReturnCommandCodecV3.decode(shape.body) as? LocalReturnRequestV3.Start ?: error("sales.journal.start_subtype")
            target = request.target(shape.operationId); amount = request.amount
            documentRevision = request.expectedReturnRevision; sessionRevision = request.expectedSessionRevision
            masterId = request.masterAllocationId; clientId = request.clientAllocationId
        }
        else -> error("sales.journal.start_command")
    }
    val slotRow = retailEffectV3(shape.changes, retailJournalSlotKeyV3(target.recordingOperationId), LocalFinancialJournalProfileV3.SLOT_SCHEMA, null)
    val slot = localSalesDecode(slotRow.payload, LocalRetailJournalSlotV3.serializer(), LocalFinancialJournalProfileV3.MAX_SLOT_BYTES)
    check(slot.schemaVersion == 3 && slot.scope.value() == shape.scope && slot.actorId == shape.actorId && slot.target == target &&
        slot.intentSemanticDigest == shape.digest.value && slot.masterAllocationId == masterId && slot.clientAllocationId == clientId &&
        slot.domain.amount == amount && slot.domain.documentRevision == documentRevision && slot.domain.sessionRevision == sessionRevision &&
        retailSlotWriteV3(slot).payload == slotRow.payload)
    val requirement = slot.requirement()
    val sources = shape.changes.filter { it.key.kind == LocalPhysicalJournalFormatV1.SOURCE_KIND }
    val pair = requireRetailSourcePairV3(requirement, sources)
    check(slot.masterSource == retailSourceReferenceV3(pair.first) && slot.clientSource == retailSourceReferenceV3(pair.second))
    val gated = sources + retailSlotWriteV3(slot) + retailFutureFencesV3(requirement)
    requireRetailExactChangesV3(shape.changes.filter { it.key.kind in retailGatedKindsV3 }, gated)
    val claims = retailLineageClaimsV3(requirement)
    requireRetailClaimsV3(shape.claims, claims + ClaimChange.Acquire(LocalSalesStoreProfile.unique(
        if (target.kind == SalesRecordingKind.PAYMENT_ATTESTATION) "payment_attempt" else "refund_attempt", target.attemptId), target.documentId))
    val documentKind = if (target.kind == SalesRecordingKind.PAYMENT_ATTESTATION) LocalSalesStoreProfile.COLLECTION_KIND else LocalSalesStoreProfile.RETURN_KIND
    check(result.target == target && result.amount == amount && result.requirementDigest == retailJournalDigestV3(requirement.canonicalBytes).value &&
        result.effectsDigest == retailEffectsDigestV3(shape.changes, shape.claims, shape.deadline).value && result.mustCommitBefore == shape.deadline &&
        result.revisions == listOf(LocalSalesRevision(documentKind, target.documentId, documentRevision.next()),
            LocalSalesRevision(CashNonSaleStoreProfile.SESSION_KIND, target.sessionId, sessionRevision.next()),
            LocalSalesRevision(LocalFinancialJournalProfileV3.SLOT_KIND, slot.id, Revision(0))))
    if (target.kind == SalesRecordingKind.REFUND_ATTESTATION) check(result.stockItems.isEmpty() && shape.deadline == null)
    return LocalRetailStartEnvelopeV3(slot, gated, claims, result)
}

/** Complete typed envelope/business shape, without recursive stock inspection. The collection
 * composite caller additionally closes it against the GENUINE complete stock posting. */
internal fun requireRetailStartEnvelopeV3(origin: RetainedCommandEvidence,
    slot: LocalRetailJournalSlotV3,
    tenderPolicies: HistoricalSalesTenderPolicySourceV1 = UnavailableHistoricalSalesTenderPolicySourceV1): LocalRetailStartEnvelopeV3 {
    check(origin.nativeOriginalProvenance == null) // Native v2 is ORIGINAL only; never Start authority.
    requireRetailRetainedHeaderV3(origin)
    val result = LocalFinancialResultCodecV3.decode(origin.result) as? LocalFinancialResultV3.AttemptStarted
        ?: error("sales.journal.not_started")
    val shape = retailRetainedShapeV3(origin, result.mustCommitBefore, tenderPolicies)
    val start = requireRetailStartShapeV3(shape, result)
    check(start.slot == slot && origin.assignmentId == slot.domain.authority.assignmentId &&
        origin.authorityEpoch == slot.domain.authority.epoch &&
        (result.mustCommitBefore == null || origin.recordedAt < result.mustCommitBefore))
    when (slot.target.kind) {
        SalesRecordingKind.PAYMENT_ATTESTATION -> requireRetailPaymentStartEffectsV3(shape, start)
        SalesRecordingKind.REFUND_ATTESTATION -> requireRetailRefundStartEffectsV3(shape, start)
    }
    return start
}

internal fun requireRetailStartEvidenceV3(origin: RetainedCommandEvidence, slot: LocalRetailJournalSlotV3,
    tenderPolicies: HistoricalSalesTenderPolicySourceV1 = UnavailableHistoricalSalesTenderPolicySourceV1) {
    requireRetailStartEnvelopeV3(origin, slot, tenderPolicies)
    when (slot.target.kind) {
        // Genuine retained posting is required. No reconstructed evidence is passed to stock.
        SalesRecordingKind.PAYMENT_ATTESTATION -> if (StockReadEvidenceV1.read(origin) == null)
            LocalCollectionCompositeVerifier(tenderPolicies).requireComplete(origin) // Normalized literal proof was closed by the envelope above.
        SalesRecordingKind.REFUND_ATTESTATION -> Unit // Full non-stock shape was checked above.
    }
}

internal fun requireRetailHeldEvidenceV3(origin: RetainedCommandEvidence) {
    check(origin.nativeOriginalProvenance == null)
    requireRetailRetainedHeaderV3(origin)
    val held = LocalFinancialResultCodecV3.decode(origin.result) as? LocalFinancialResultV3.StockHeld ?: error("sales.journal.not_held")
    requireRetailHeldShapeV3(retailRetainedShapeV3(origin, held.mustCommitBefore), held)
}

internal fun requireRetailRetainedRowV3(origin: RetainedCommandEvidence, row: StoredAggregate) {
    requireRetailRetainedHeaderV3(origin)
    val written = origin.aggregateWrites.singleOrNull { it.key == row.key } ?: error("sales.journal.retained_row")
    requireLocalSalesSource(row.revision.value > 0 && row.schema == written.schema && row.revision == written.storageGeneration &&
        row.payload == written.payload && row.digest == written.payloadDigest && retailJournalDigestV3(row.payload) == row.digest)
}

private fun requireRetailRetainedHeaderV3(origin: RetainedCommandEvidence) {
    requireLocalSalesSource(origin.committed && origin.command in setOf(LocalFinancialJournalProfileV3.COLLECTION_COMMAND,
        LocalFinancialJournalProfileV3.RETURN_COMMAND, LocalRetailSupervisorCodecV1.COMMAND) && origin.jobs.isEmpty() &&
        semanticDigest(origin.scope, origin.originalActorId, origin.command, origin.normalized) == origin.semanticDigest &&
        retailJournalDigestV3(origin.result) == origin.resultHash &&
        origin.aggregateWrites.map { it.key }.distinct().size == origin.aggregateWrites.size &&
        origin.claimChanges.map { it.key }.distinct().size == origin.claimChanges.size &&
        origin.aggregateWrites.all { it.storageGeneration.value > 0 && retailJournalDigestV3(it.payload) == it.payloadDigest })
}

private fun retailRetainedShapeV3(origin: RetainedCommandEvidence, deadline: UtcInstant?,
    tenderPolicies: HistoricalSalesTenderPolicySourceV1 = UnavailableHistoricalSalesTenderPolicySourceV1): RetailOperationShapeV3 =
    RetailOperationShapeV3(origin.scope, origin.originalActorId, origin.operationId, origin.command, origin.semanticDigest,
        origin.normalized, origin.result, origin.aggregateWrites.map {
            // Validation of the original AFTER generation only. These values never leave this
            // verifier as a retry proposal; they cannot reconstitute kernel evidence or capacity.
            AggregateChange(it.key, if (it.storageGeneration == Revision(1)) null else Revision(it.storageGeneration.value - 1), it.schema, it.payload)
        }, origin.claimChanges.map { if (it.action == RetainedClaimAction.ACQUIRE) ClaimChange.Acquire(it.key, it.ownerId)
            else ClaimChange.Release(it.key, it.ownerId) }, deadline, origin.recordedAt, tenderPolicies)

private val retailGatedKindsV3: Set<String> = LocalFinancialJournalProfileV3.gatedKinds + ReservedOperations.KIND
private val retailStartStockKindsV3: Set<String> = setOf(LocalStockStoreProfile.POSTING, LocalStockStoreProfile.ITEM,
    LocalStockStoreProfile.POSITION, LocalStockStoreProfile.RESERVATION)

private fun retailEffectV3(changes: List<AggregateChange>, key: AggregateKey, schema: String, expected: Revision?): AggregateChange =
    (changes.singleOrNull { it.key == key } ?: error("sales.journal.effect_missing")).also {
        check(it.schema == schema && it.expectedRevision == expected) { "sales.journal.effect_binding" }
    }

private fun requireRetailClaimsV3(actual: List<ClaimChange>, expected: List<ClaimChange>) {
    check(actual.size == expected.size && actual.toSet() == expected.toSet()) { "sales.journal.claim_set" }
}

private class RetailCollectionCutV3(val collection: CollectionLock, val order: OwnedOrder, val cash: CashSession,
    val sourceKeys: Set<AggregateKey>, val returnPolicyCapture: LocalSalesReturnPolicyCaptureV2?)

private fun retailCollectionStartBeforeV3(shape: RetailOperationShapeV3, request: LocalCollectionRequestV3.Start): RetailCollectionCutV3 {
    val beforeKeys = mutableSetOf<AggregateKey>()
    fun before(role: String, schema: String): StoredBytes {
        val key = collectionBeforeKey(shape.operationId, role)
        beforeKeys += key
        return retailEffectV3(shape.changes, key, schema, null).payload
    }
    val collection = SalesSnapshotCodec.decodeHistoricalCollection(retailTextV3(before("collection", LocalSalesStoreProfile.schema(LocalSalesStoreProfile.COLLECTION_KIND))),
        SalesSnapshotBinding(SalesSnapshotKind.COLLECTION, shape.scope, request.collectionId, request.expectedCollectionRevision))
    val order = SalesSnapshotCodec.decodeOrder(retailTextV3(before("order", LocalSalesStoreProfile.schema(LocalSalesStoreProfile.ORDER_KIND))),
        SalesSnapshotBinding(SalesSnapshotKind.ORDER, shape.scope, collection.orderId, collection.lockedOrderRevision))
    val cash = SalesSnapshotCodec.decodeCashSession(retailTextV3(before("cash", CashNonSaleStoreProfile.SESSION_SCHEMA)),
        SalesSnapshotBinding(SalesSnapshotKind.CASH_SESSION, shape.scope, request.sessionId, request.expectedSessionRevision))
    val prices = order.priceSourceReference?.let { requirePriceSources(before("price_sources", LocalSalesStoreProfile.PRICE_SOURCES_SCHEMA),
        shape.scope, order.id, order.quote, it, if (order.stockRequirements.normalized) null else order.stockRequirements.allocations,
            order.stockRequirements.normalizedLines) }
    val returnPolicyCapture = order.returnPolicySourceReference?.let {
        requirePaymentReturnPolicyCapture(before("return_policy_capture", LocalSalesReturnPolicyCaptureCodecV2.SCHEMA),
            shape.scope, order, checkNotNull(prices))
    }
    requireRetailCollectionEligibleV3(shape.actorId, collection, order, cash)
    check(!collection.unresolved && collection.attempts.none { it.id == request.attemptId } && request.amount.minor > 0 &&
        request.amount.currency == collection.quote.currency && request.amount.minor <= collection.remainingPayable.minor)
    return RetailCollectionCutV3(collection, order, cash, beforeKeys, returnPolicyCapture)
}

private fun requireRetailHeldShapeV3(shape: RetailOperationShapeV3, result: LocalFinancialResultV3.StockHeld) {
    shape.requireBinding()
    check(shape.command == LocalFinancialJournalProfileV3.COLLECTION_COMMAND)
    val request = LocalCollectionCommandCodecV3.decode(shape.body) as? LocalCollectionRequestV3.Start ?: error("sales.journal.held_subtype")
    val cut = retailCollectionStartBeforeV3(shape, request)
    check(result.collectionId == cut.collection.id && result.reservationId == cut.collection.reservationId &&
        result.reservationRevision > request.expectedReservationRevision && shape.claims.isEmpty() &&
        shape.changes.none { it.key.kind in retailGatedKindsV3 } &&
        result.effectsDigest == retailEffectsDigestV3(shape.changes, shape.claims, shape.deadline).value && result.mustCommitBefore == shape.deadline)
    requireRetailStockShapeV3(shape, request, result.stockItems, cut.collection, cut.sourceKeys)
}

private fun requireRetailPaymentStartEffectsV3(shape: RetailOperationShapeV3, start: LocalRetailStartEnvelopeV3) {
    val request = LocalCollectionCommandCodecV3.decode(shape.body) as? LocalCollectionRequestV3.Start ?: error("sales.journal.payment_start")
    val cut = retailCollectionStartBeforeV3(shape, request)
    val before = cut.collection
    val documentKey = AggregateKey(LocalSalesStoreProfile.COLLECTION_KIND, before.id)
    val cashKey = CashNonSaleStoreProfile.sessionKey(cut.cash.id)
    val document = retailEffectV3(shape.changes, documentKey, LocalSalesStoreProfile.schema(documentKey.kind), before.revision.next())
    val cash = retailEffectV3(shape.changes, cashKey, CashNonSaleStoreProfile.SESSION_SCHEMA, cut.cash.revision.next())
    check(document.payload.size <= SalesRecordingBudget.MAX_PENDING_DOCUMENT_BYTES && cash.payload.size <= SalesRecordingBudget.MAX_PENDING_DOCUMENT_BYTES)
    val after = SalesSnapshotCodec.decodeHistoricalCollection(retailTextV3(document.payload),
        SalesSnapshotBinding(SalesSnapshotKind.COLLECTION, shape.scope, before.id, before.revision.next()))
    val afterCash = SalesSnapshotCodec.decodeCashSession(retailTextV3(cash.payload),
        SalesSnapshotBinding(SalesSnapshotKind.CASH_SESSION, shape.scope, cut.cash.id, cut.cash.revision.next()))
    sameCollectionCore(before, after)
    check(after.saleId == null && after.attempts.size == before.attempts.size + 1 &&
        before.attempts.indices.all { sameAttempt(before.attempts[it], after.attempts[it]) })
    val attempt = after.attempts.last()
    check(attempt.id == request.attemptId && attempt.originalOperationId == shape.operationId && attempt.originalActorId == shape.actorId &&
        attempt.originalCashSessionId == cut.cash.id && attempt.amount == request.amount && attempt.payerReference == request.payerReference &&
        attempt.status == AttemptStatus.AWAITING_ATTESTATION && attempt.latestObservation == null && attempt.payment == null &&
        tenderView(attempt.tender) == start.slot.domain.tender)
    when (val tender = request.tender) {
        LocalTenderSelectionV1.Cash -> check(attempt.tender == Tender.Cash)
        is LocalTenderSelectionV1.External -> check((attempt.tender as? Tender.External)?.let {
            it.tenderId == tender.tenderId && it.policyRevision == tender.expectedPolicyRevision } == true)
    }
    cashIntentOnly(cut.cash, afterCash, attempt.id)
    SalesSnapshotLinks.requireCollection(after, cut.order, afterCash)
    val minimum = setOf(orderResource(before.id), orderResource(cut.order.id), orderResource(before.collectorId),
        cashResource(cut.cash.id), cashResource(cut.cash.drawerId), cashResource(cut.cash.custodianId)) + priceSourcesResources(before.priceSourceReference) +
        returnPolicyCaptureResources(cut.returnPolicyCapture)
    check(start.slot.domain.financialResources.containsAll(minimum))
    if (attempt.tender == Tender.Cash) check(start.slot.domain.financialResources.toSet() == minimum)
    val businessKeys = cut.sourceKeys + setOf(documentKey, cashKey) + start.gatedChanges.map { it.key }
    if (before.stockRequirements.normalized) {
        requireRetailNormalizedStartEffectsV3(shape, request, start, cut, attempt.tender, document, businessKeys)
        val tenderResources = (attempt.tender as? Tender.External)?.let { setOf(recordingAuthority(it.tenderId)) }.orEmpty()
        check(start.slot.domain.financialResources.toSet() == minimum + tenderResources)
    } else requireRetailStockShapeV3(shape, request, start.result.stockItems, before, businessKeys)
}

/** Full literal SQL membership/EOF read proof; ordinary v1 writes preserve ALL normalized stock. */
private fun requireRetailNormalizedStartEffectsV3(shape: RetailOperationShapeV3, request: LocalCollectionRequestV3.Start,
    start: LocalRetailStartEnvelopeV3, cut: RetailCollectionCutV3, tender: Tender,
    document: AggregateChange, businessKeys: Set<AggregateKey>) {
    val producer = StreamedProducerIdentityV2(shape.operationId, shape.command, shape.actorId, shape.digest)
    val observed = StockReadEvidenceV1.decodeChanges(shape.scope, producer, shape.changes) ?: error("sales.stock.payment_read_required")
    val sources = NormalizedPaymentSources(observed)
    val collectionRow = sources.collection(cut.collection, cut.order, cut.cash)
    sources.tender(tender, shape.tenderPolicies)
    val stock = LocalStockPaymentObservations.reservation(observed, cut.collection.reservationId)
    requireNormalizedPaymentDemand(cut.collection, stock)
    val items = stock.items.map { LocalSalesStockRevisionV1(it.skuId, checkNotNull(it.storageRevision)) }
    check(stock.structurallySafe &&
        stock.header.revision == request.expectedReservationRevision && request.items.sortedBy { it.skuId.value } == items && start.result.stockItems == items &&
        shape.deadline == stock.deadline && (shape.recordedAt == null || stock.safeAt(shape.recordedAt)))
    sources.startVacancy(request, start.slot.requirement())
    val link = sources.advanceLink(collectionRow, producer, document, shape.changes)
    sources.finish(stock = true)
    val evidence = shape.changes.filter { it.key.kind == StockReadEvidenceV1.KIND }.map { it.key }.toSet()
    val keys = businessKeys + link + evidence
    check(shape.changes.size == keys.size && shape.changes.map { it.key }.toSet() == keys && shape.changes.size <= 128 &&
        shape.changes.none { it.key.kind in StockRelationalKindsV2.all || it.key.kind in LocalStockStoreProfile.inventoryKinds } &&
        shape.changes.all { it.payload.size <= 65_536 } && shape.changes.sumOf { it.payload.size.toLong() } + shape.result.size <= 1_048_576L)
}

/** The SAME trusted stock repository produced these rows. This checks the complete returned
 * projection, not invented stock evidence. Retained inspectPosting closes the sole POSTING
 * digest-cycle exception and verifies exact effects, expiry/collateral and source lineage. */
private fun requireRetailStockShapeV3(shape: RetailOperationShapeV3, request: LocalCollectionRequestV3.Start,
    resultItems: List<LocalSalesStockRevisionV1>, collection: CollectionLock, businessKeys: Set<AggregateKey>) {
    val stock = shape.changes.filter { it.key.kind in LocalStockStoreProfile.inventoryKinds }
    check(stock.isNotEmpty() && stock.all { it.key.kind in retailStartStockKindsV3 } &&
        shape.changes.filter { it.key.kind !in LocalStockStoreProfile.inventoryKinds }.map { it.key }.toSet() == businessKeys &&
        shape.changes.size == stock.size + businessKeys.size)
    val posting = stock.single { it.key.kind == LocalStockStoreProfile.POSTING }
    check(posting.key.id.value == shape.operationId.value && posting.expectedRevision == null &&
        stock.any { it.key == LocalStockStoreProfile.reservationKey(collection.reservationId) && it.expectedRevision != null })
    val items = stock.filter { it.key.kind == LocalStockStoreProfile.ITEM }.map {
        LocalSalesStockRevisionV1(it.key.id, checkNotNull(it.expectedRevision).next())
    }.sortedBy { it.skuId.value }
    check(resultItems == items && request.items.map { it.skuId }.toSet() ==
        collection.stockRequirements.allocations.map { it.position.pool.skuId }.toSet() && request.items.all { requested ->
            items.singleOrNull { it.skuId == requested.skuId }?.storageRevision == requested.storageRevision.next() })
}

private fun requireRetailCollectionEligibleV3(actor: DomainId, collection: CollectionLock, order: OwnedOrder, cash: CashSession) {
    SalesSnapshotLinks.requireCollection(collection, order, cash)
    check(collection.collectorId == actor && order.ownerId == actor && cash.custodianId == actor &&
        cash.status == CashSessionStatus.OPEN && collection.saleId == null && order.paidSaleId == null && order.handoff == null &&
        collection.cashSessionId == cash.id && collection.lockedOrderRevision == order.revision)
}

private fun retailTextV3(body: StoredBytes): String = body.copyBytes().decodeToString(throwOnInvalidSequence = true)

private class RetailRefundCutV3(val accepted: AcceptedReturn, val cash: CashSession,
    val entitlement: ReceiptEntitlement, val claim: ReturnClaim, val work: LocalWorkState,
    val sources: Map<String, LocalRetailBeforeV3>)

private fun retailSourceImagesV3(shape: RetailOperationShapeV3, images: List<LocalRetailBeforeV3>): Map<String, LocalRetailBeforeV3> {
    check(images.size in 1..8 && images.map { it.role }.distinct().size == images.size)
    for (image in images) {
        check(image.scope.value() == shape.scope && image.capturingOperationId == shape.operationId && image.storageRevision.value > 0 &&
            image.sourceKind !in retailGatedKindsV3 + setOf(LocalFinancialJournalProfileV3.APPLICATION_KIND,
                LocalFinancialJournalProfileV3.BEFORE_KIND, LocalSalesStoreProfile.COLLECTION_BEFORE_KIND) &&
            retailJournalDigestV3(image.body()).value == image.sourceDigest)
        AggregateKey(image.sourceKind, image.sourceId)
    }
    return images.associateBy { it.role }
}

private fun retailSourceImageV3(images: Map<String, LocalRetailBeforeV3>, role: String, kind: String,
    id: DomainId): LocalRetailBeforeV3 = (images[role] ?: error("sales.journal.before_missing")).also { image ->
    val schema = when (kind) {
        CashNonSaleStoreProfile.SESSION_KIND -> CashNonSaleStoreProfile.SESSION_SCHEMA
        LocalSalesStoreProfile.PRICE_SOURCES_KIND -> LocalSalesStoreProfile.PRICE_SOURCES_SCHEMA
        else -> LocalSalesStoreProfile.schema(kind)
    }
    check(image.sourceKind == kind && image.sourceId == id && image.sourceSchema == schema) { "sales.journal.before_source" }
}

private fun LocalRetailBeforeV3.domainRevisionV3(): Revision = SalesStorageRevision.toDomain(storageRevision)

private fun retailRefundBeforeCutV3(shape: RetailOperationShapeV3, target: LocalJournalTargetV3,
    values: List<LocalRetailBeforeV3>, custodyActorId: DomainId = shape.actorId): RetailRefundCutV3 {
    val images = retailSourceImagesV3(shape, values)
    check(images.keys == retailRefundBeforeRolesV3 && target.kind == SalesRecordingKind.REFUND_ATTESTATION)
    val saleId = checkNotNull(target.originalSaleId)
    val claimId = checkNotNull(target.originalClaimId)
    val saleImage = retailSourceImageV3(images, "sale", LocalSalesStoreProfile.SALE_KIND, saleId)
    check(saleImage.storageRevision == Revision(1))
    val sale = SalesSnapshotCodec.decodePaidSale(retailTextV3(saleImage.body()),
        SalesSnapshotBinding(SalesSnapshotKind.PAID_SALE, shape.scope, saleId, Revision(0)))
    val entitlementImage = retailSourceImageV3(images, "entitlement", LocalSalesStoreProfile.ENTITLEMENT_KIND, saleId)
    val entitlement = SalesSnapshotCodec.decodeReceiptEntitlement(retailTextV3(entitlementImage.body()),
        SalesSnapshotBinding(SalesSnapshotKind.RECEIPT_ENTITLEMENT, shape.scope, saleId, entitlementImage.domainRevisionV3()))
    check(SalesSnapshotCodec.encodePaidSale(sale) == SalesSnapshotCodec.encodePaidSale(entitlement.sale))
    val claimImage = retailSourceImageV3(images, "claim", LocalSalesStoreProfile.RETURN_CLAIM_KIND, claimId)
    val claim = SalesSnapshotCodec.decodeReturnClaim(retailTextV3(claimImage.body()),
        SalesSnapshotBinding(SalesSnapshotKind.RETURN_CLAIM, shape.scope, claimId, claimImage.domainRevisionV3()), entitlement)
    val acceptedImage = retailSourceImageV3(images, "accepted", LocalSalesStoreProfile.RETURN_KIND, target.documentId)
    val accepted = SalesSnapshotCodec.decodeAcceptedReturn(retailTextV3(acceptedImage.body()),
        SalesSnapshotBinding(SalesSnapshotKind.ACCEPTED_RETURN, shape.scope, target.documentId, acceptedImage.domainRevisionV3()), entitlement, claim)
    val cashImage = retailSourceImageV3(images, "cash", CashNonSaleStoreProfile.SESSION_KIND, target.sessionId)
    val cash = SalesSnapshotCodec.decodeCashSession(retailTextV3(cashImage.body()),
        SalesSnapshotBinding(SalesSnapshotKind.CASH_SESSION, shape.scope, target.sessionId, cashImage.domainRevisionV3()))
    check(accepted.saleId == saleId && accepted.claimId == claimId && cash.custodianId == custodyActorId && cash.status == CashSessionStatus.OPEN)
    val workImage = retailSourceImageV3(images, "work", LocalSalesStoreProfile.WORK_KIND, accepted.acceptingOperatorId)
    val work = localSalesDecode(workImage.body(), LocalWorkState.serializer())
    requireRetailWorkV3(work, shape.scope, workImage.sourceId, workImage.domainRevisionV3())
    check((accepted.id in work.returnIds) == (accepted.due.minor != 0L || accepted.unresolved))
    val drawerImage = retailSourceImageV3(images, "drawer", LocalSalesStoreProfile.DRAWER_KIND, cash.drawerId)
    val drawer = localSalesDecode(drawerImage.body(), LocalDrawerState.serializer())
    val custodianImage = retailSourceImageV3(images, "custodian", LocalSalesStoreProfile.CUSTODIAN_KIND, cash.custodianId)
    val custodian = localSalesDecode(custodianImage.body(), LocalCustodianState.serializer())
    check(drawer.scope.value() == shape.scope && drawer.id == cash.drawerId && drawer.revision == drawerImage.domainRevisionV3() &&
        drawer.currency == cash.expectedCash.currency && drawer.activeSessionId == cash.id && drawer.closingTransferId == null &&
        custodian.scope.value() == shape.scope && custodian.id == cash.custodianId && custodian.revision == custodianImage.domainRevisionV3() &&
        custodian.activeSessionId == cash.id)
    localLabel(drawer.label)
    return RetailRefundCutV3(accepted, cash, entitlement, claim, work, images)
}

private fun requireRetailRefundStartEffectsV3(shape: RetailOperationShapeV3, start: LocalRetailStartEnvelopeV3) {
    val request = LocalReturnCommandCodecV3.decode(shape.body) as? LocalReturnRequestV3.Start ?: error("sales.journal.refund_start")
    val beforeKeys = retailRefundBeforeRolesV3.map { retailBeforeKeyV3(shape.operationId, it) }.toSet()
    val images = retailRefundBeforeRolesV3.map { role ->
        val row = retailEffectV3(shape.changes, retailBeforeKeyV3(shape.operationId, role), "sales.journal.before/v3", null)
        localSalesDecode(row.payload, LocalRetailBeforeV3.serializer(), LocalFinancialJournalProfileV3.MAX_BEFORE_BYTES).also { check(it.role == role) }
    }
    val before = retailRefundBeforeCutV3(shape, start.slot.target, images)
    val accepted = before.accepted
    val cash = before.cash
    check(accepted.revision == request.expectedReturnRevision && cash.revision == request.expectedSessionRevision && !accepted.unresolved &&
        accepted.attempts.none { it.id == request.attemptId } && request.amount.currency == accepted.originalPayable.currency && request.amount.minor > 0L)
    val obligation = accepted.tenderObligations.single { it.originalPaymentId == request.originalPaymentId }
    val refunded = accepted.attempts.filter { it.originalPaymentId == request.originalPaymentId }
        .fold(request.amount.zero()) { sum, attempt -> sum + (attempt.refund?.amount ?: request.amount.zero()) }
    val cashOut = if (obligation.tender == Tender.Cash) request.amount else request.amount.zero()
    check(request.amount.minor <= (obligation.amount - refunded).minor && tenderView(obligation.tender) == start.slot.domain.tender &&
        cashOut.minor <= cash.availableCash.minor && request.attemptId !in cash.pendingAttemptIds)
    val acceptedKey = AggregateKey(LocalSalesStoreProfile.RETURN_KIND, accepted.id)
    val cashKey = CashNonSaleStoreProfile.sessionKey(cash.id)
    val document = retailEffectV3(shape.changes, acceptedKey, LocalSalesStoreProfile.schema(acceptedKey.kind), before.sources.getValue("accepted").storageRevision)
    val cashRow = retailEffectV3(shape.changes, cashKey, CashNonSaleStoreProfile.SESSION_SCHEMA, before.sources.getValue("cash").storageRevision)
    check(document.payload.size <= SalesRecordingBudget.MAX_PENDING_DOCUMENT_BYTES && cashRow.payload.size <= SalesRecordingBudget.MAX_PENDING_DOCUMENT_BYTES)
    val after = SalesSnapshotCodec.decodeAcceptedReturn(retailTextV3(document.payload),
        SalesSnapshotBinding(SalesSnapshotKind.ACCEPTED_RETURN, shape.scope, accepted.id, accepted.revision.next()), before.entitlement, before.claim)
    sameRetailReturnCoreV3(accepted, after)
    check(after.attempts.size == accepted.attempts.size + 1 && accepted.attempts.indices.all { sameRetailRefundAttemptV3(accepted.attempts[it], after.attempts[it]) })
    val attempt = after.attempts.last()
    check(attempt.id == request.attemptId && attempt.originalOperationId == shape.operationId && attempt.originalActorId == shape.actorId &&
        attempt.originalCashSessionId == request.sessionId && attempt.originalPaymentId == request.originalPaymentId &&
        attempt.amount == request.amount && attempt.tender == obligation.tender && attempt.status == AttemptStatus.AWAITING_ATTESTATION &&
        attempt.latestObservation == null && attempt.refund == null)
    val afterCash = SalesSnapshotCodec.decodeCashSession(retailTextV3(cashRow.payload),
        SalesSnapshotBinding(SalesSnapshotKind.CASH_SESSION, shape.scope, cash.id, cash.revision.next()))
    sameRetailCashCoreV3(cash, afterCash)
    check(afterCash.revision == cash.revision.next() && afterCash.totals == cash.totals && afterCash.expectedCash == cash.expectedCash &&
        afterCash.reservedCashOut == cash.reservedCashOut + cashOut && afterCash.cashLedgerRevision == cash.cashLedgerRevision &&
        afterCash.pendingAttemptIds == cash.pendingAttemptIds + request.attemptId)
    val minimum = setOf(entitlementResource(accepted.id), entitlementResource(accepted.saleId), entitlementResource(accepted.claimId),
        orderResource(before.work.id), cashResource(cash.id), cashResource(cash.drawerId), cashResource(cash.custodianId)) +
        priceSourcesResources(before.entitlement.sale.priceSourceReference)
    check(start.slot.domain.financialResources.containsAll(minimum) && shape.changes.size == 15 &&
        shape.changes.map { it.key }.toSet() == beforeKeys + setOf(acceptedKey, cashKey) + start.gatedChanges.map { it.key } &&
        shape.changes.sumOf { it.payload.size.toLong() } + shape.result.size <= 1_048_576L)
}

private fun sameRetailReturnCoreV3(before: AcceptedReturn, after: AcceptedReturn) {
    check(before.id == after.id && before.scope == after.scope && before.saleId == after.saleId && before.claimId == after.claimId &&
        before.portionId == after.portionId && before.quantity == after.quantity && before.consumedQuantityBefore == after.consumedQuantityBefore &&
        before.acceptingOperatorId == after.acceptingOperatorId && before.evidence == after.evidence &&
        before.requestedDisposition == after.requestedDisposition && before.inspectionEvidence == after.inspectionEvidence &&
        before.originalPayable == after.originalPayable && before.components == after.components && before.tenderObligations == after.tenderObligations)
}

private fun sameRetailRefundAttemptV3(before: RefundAttempt, after: RefundAttempt): Boolean =
    sameRetailRefundIdentityV3(before, after) && before.status == after.status && before.latestObservation == after.latestObservation && before.refund == after.refund

private fun sameRetailRefundIdentityV3(before: RefundAttempt, after: RefundAttempt): Boolean = before.id == after.id &&
    before.originalOperationId == after.originalOperationId && before.originalActorId == after.originalActorId &&
    before.originalCashSessionId == after.originalCashSessionId && before.originalPaymentId == after.originalPaymentId &&
    before.amount == after.amount && before.tender == after.tender

private fun sameRetailCashCoreV3(before: CashSession, after: CashSession) {
    check(before.id == after.id && before.scope == after.scope && before.drawerId == after.drawerId && before.custodianId == after.custodianId &&
        before.businessDate == after.businessDate && before.opening == after.opening && before.openingSourceId == after.openingSourceId &&
        before.status == CashSessionStatus.OPEN && after.status == CashSessionStatus.OPEN && before.pendingTransferIds == after.pendingTransferIds &&
        before.closingCut == after.closingCut && before.closeReport == after.closeReport && before.review == after.review)
}

private fun requireRetailWorkV3(work: LocalWorkState, scope: BranchScope, id: DomainId, revision: Revision) {
    check(work.scope.value() == scope && work.id == id && work.revision == revision &&
        listOf(work.orderIds, work.returnIds, work.pendingClaimIds).all { it.size <= LocalSalesStoreProfile.MAX_OPEN_WORK && it.distinct().size == it.size })
}

/** One immutable fact + initial bookkeeping; original and resolution are distinct wire bodies/IDs. */
private fun requireRetailRawShapeV3(shape: RetailOperationShapeV3,
    requirement: LocalPhysicalJournalRequirementV1): LocalRetailJournalFactV3 {
    shape.requireBinding()
    val phase: LocalJournalFactPhaseV3
    val target: LocalJournalTargetV3
    val parentDigest: String?
    when (shape.command) {
        LocalFinancialJournalProfileV3.COLLECTION_COMMAND -> when (val request = LocalCollectionCommandCodecV3.decode(shape.body)) {
            is LocalCollectionRequestV3.RecordOriginal -> { phase = LocalJournalFactPhaseV3.ORIGINAL; target = request.target; parentDigest = null }
            is LocalCollectionRequestV3.RecordResolution -> {
                check(request.resolverActorId == shape.actorId && request.outcome !is LocalPaymentOutcomeV3.Unknown)
                phase = LocalJournalFactPhaseV3.RESOLUTION; target = request.target; parentDigest = request.originalBodyDigest
            }
            else -> error("sales.journal.raw_subtype")
        }
        LocalFinancialJournalProfileV3.RETURN_COMMAND -> when (val request = LocalReturnCommandCodecV3.decode(shape.body)) {
            is LocalReturnRequestV3.RecordOriginal -> { phase = LocalJournalFactPhaseV3.ORIGINAL; target = request.target; parentDigest = null }
            is LocalReturnRequestV3.RecordResolution -> {
                check(request.resolverActorId == shape.actorId && request.outcome !is LocalRefundOutcomeV3.Unknown)
                phase = LocalJournalFactPhaseV3.RESOLUTION; target = request.target; parentDigest = request.originalBodyDigest
            }
            else -> error("sales.journal.raw_subtype")
        }
        else -> error("sales.journal.raw_command")
    }
    retailJournalDomain(target.kind).validateRequirement(requirement)
    check(target == retailDomainRequirementV3(requirement).target &&
        (phase != LocalJournalFactPhaseV3.ORIGINAL || requirement.originalActorId == shape.actorId) &&
        requirement.scope == shape.scope && requirement.command == shape.command && shape.operationId == target.factOperation(phase) &&
        shape.claims.isEmpty() && shape.deadline == null)
    val factRow = retailEffectV3(shape.changes, retailJournalFactKeyV3(phase, shape.operationId),
        if (phase == LocalJournalFactPhaseV3.ORIGINAL) LocalFinancialJournalProfileV3.ORIGINAL_SCHEMA else LocalFinancialJournalProfileV3.RESOLUTION_SCHEMA, null)
    val fact = localSalesDecode(factRow.payload, LocalRetailJournalFactV3.serializer(), LocalFinancialJournalProfileV3.MAX_FACT_BYTES)
    check(fact.schemaVersion == 3 && fact.scope.value() == shape.scope && fact.target == target && fact.phase == phase &&
        fact.originalActorId == requirement.originalActorId && fact.recordingActorId == shape.actorId && fact.body() == shape.body &&
        fact.bodyDigest == retailJournalDigestV3(shape.body).value && fact.semanticDigest == shape.digest.value &&
        fact.originalBodyDigest == parentDigest && (shape.recordedAt == null || fact.recordedAt == shape.recordedAt))
    PayloadDigest(fact.slotDigest)
    val initial = LocalRetailJournalApplicationV3(3, fact.scope, target, phase, shape.actorId, Revision(0),
        LocalJournalApplicationStateV3.PENDING_RECONCILIATION, LocalJournalPendingReasonV3.AWAITING_FINANCIAL_APPLICATION,
        fact.recordedAt, null, null, emptyList())
    requireRetailExactChangesV3(shape.changes, listOf(retailFactWriteV3(fact), retailApplicationWriteV3(initial, null)))
    check(LocalFinancialResultCodecV3.decode(shape.result) == retailPending(target, phase, LocalJournalPendingReasonV3.AWAITING_FINANCIAL_APPLICATION))
    if (phase == LocalJournalFactPhaseV3.ORIGINAL) retailJournalDomain(target.kind).classifyOriginal(requirement, shape.body)
    // Resolution's authentic SQL UNKNOWN parent is checked by readRetailFact/prepareRetailFact;
    // a descriptor or pending bookkeeping row is never used to fabricate that ancestor.
    return fact
}

internal fun requireRetailRawEvidenceV3(origin: RetainedCommandEvidence, fact: LocalRetailJournalFactV3,
    slot: LocalRetailJournalSlotV3) {
    requireRetailRetainedHeaderV3(origin)
    check(origin.nativeOriginalProvenance == null || fact.phase == LocalJournalFactPhaseV3.ORIGINAL)
    check(requireRetailRawShapeV3(retailRetainedShapeV3(origin, null), slot.requirement()) == fact &&
        fact.slotDigest == retailJournalDigestV3(retailSlotWriteV3(slot).payload).value)
}

/** Literal parent values read from real retained writes, never a manufactured current cut. */
internal class RetailApplicationParentV3(val fact: LocalRetailJournalFactV3, val application: LocalRetailJournalApplicationV3,
    val reference: LocalRetailParentApplicationV3)

internal fun requireRetailApplicationEvidenceV3(update: RetainedCommandEvidence, fact: LocalRetailJournalFactV3,
    application: LocalRetailJournalApplicationV3, slot: LocalRetailJournalSlotV3, parent: LocalRetailJournalFactCutV3?,
    tenderPolicies: HistoricalSalesTenderPolicySourceV1 = UnavailableHistoricalSalesTenderPolicySourceV1) =
    requireRetailApplicationOriginalV3(update, fact, application, slot, parent?.let {
        RetailApplicationParentV3(it.fact.value, it.application.value, checkNotNull(retailParentApplicationV3(it)))
    }, tenderPolicies)

/** Genuine retained command + exact nonrecursive source images. A generic Applied is insufficient. */
internal fun requireRetailApplicationOriginalV3(update: RetainedCommandEvidence, fact: LocalRetailJournalFactV3,
    application: LocalRetailJournalApplicationV3, slot: LocalRetailJournalSlotV3, parent: RetailApplicationParentV3?,
    tenderPolicies: HistoricalSalesTenderPolicySourceV1 = UnavailableHistoricalSalesTenderPolicySourceV1) {
    check(update.nativeOriginalProvenance == null) // No native tag for accounting or resolution.
    requireRetailRetainedHeaderV3(update)
    val target = fact.target
    val operation = target.factOperation(fact.phase)
    val shape = retailRetainedShapeV3(update, null, tenderPolicies)
    shape.requireBinding()
    val supervised = update.command == LocalRetailSupervisorCodecV1.COMMAND
    if (supervised) check(update.normalized == LocalRetailSupervisorCodecV1.encode(
        LocalRetailSupervisorRequestV1.Apply(target.kind, operation, fact.phase)))
    else check(update.command == retailJournalCommandV3(target.kind) && update.originalActorId == fact.recordingActorId &&
        update.originalActorId == slot.actorId && update.normalized == retailApplicationBody(target.kind, operation, fact.phase))
    check(update.scope == fact.scope.value() &&
        update.operationId !in setOf(target.intentOperationId, target.recordingOperationId, target.resolutionOperationId) &&
        application.lastApplicationOperationId == update.operationId && application.updatedAt == update.recordedAt &&
        application.target == target && application.phase == fact.phase && application.scope == fact.scope &&
        application.actorId == fact.recordingActorId && application.revision.value > 0)
    val applicationKey = retailJournalApplicationKeyV3(operation)
    val row = retailEffectV3(shape.changes, applicationKey, LocalFinancialJournalProfileV3.APPLICATION_SCHEMA, application.revision)
    val bound = if (application.state == LocalJournalApplicationStateV3.PENDING_RECONCILIATION)
        LocalFinancialJournalProfileV3.MAX_PENDING_APPLICATION_BYTES else LocalFinancialJournalProfileV3.MAX_APPLICATION_BYTES
    check(row.payload == localSalesEncode(application, LocalRetailJournalApplicationV3.serializer(), bound))
    if (application.state == LocalJournalApplicationStateV3.PENDING_RECONCILIATION) {
        check(application.reason != null && application.reason != LocalJournalPendingReasonV3.AWAITING_FINANCIAL_APPLICATION &&
            application.appliedAction == null && application.financialRevisions.isEmpty() && application.financialProof == null &&
            shape.changes.size == 1 && shape.claims.isEmpty() &&
            LocalFinancialResultCodecV3.decode(update.result) == retailPending(target, fact.phase, checkNotNull(application.reason)))
        return // A truthful pending bookkeeping update does not claim a financial transition.
    }
    val proof = checkNotNull(application.financialProof)
    check(application.reason == null && application.appliedAction != null && shape.changes.size <= LocalFinancialJournalProfileV3.MAX_FINANCIAL_WRITES &&
        shape.claims.size <= LocalFinancialJournalProfileV3.MAX_FINANCIAL_CLAIMS &&
        shape.changes.sumOf { it.payload.size.toLong() } + shape.result.size <= LocalFinancialJournalProfileV3.MAX_FINANCIAL_APPLY_BYTES &&
        shape.changes.none { it.key.kind in retailGatedKindsV3 } && shape.claims.none { it.key.namespace in LocalFinancialJournalProfileV3.gatedClaimNamespaces })
    if (fact.phase == LocalJournalFactPhaseV3.ORIGINAL) check(parent == null && proof.parentApplication == null)
    else {
        check(parent != null && parent.fact.target == target && parent.fact.phase == LocalJournalFactPhaseV3.ORIGINAL &&
            parent.fact.bodyDigest == fact.originalBodyDigest && parent.application.state == LocalJournalApplicationStateV3.APPLIED &&
            parent.application.appliedAction == (if (target.kind == SalesRecordingKind.PAYMENT_ATTESTATION)
                LocalSalesAction.PAYMENT_UNKNOWN else LocalSalesAction.REFUND_UNKNOWN) &&
            proof.parentApplication == parent?.reference)
    }
    when (target.kind) {
        SalesRecordingKind.PAYMENT_ATTESTATION -> requireRetailPaymentApplicationEffectsV3(shape, fact, application, slot, proof, parent)
        SalesRecordingKind.REFUND_ATTESTATION -> requireRetailRefundApplicationEffectsV3(shape, fact, application, slot, proof, parent)
    }
    check(LocalFinancialResultCodecV3.decode(shape.result) == LocalFinancialResultV3.Applied(target, fact.phase, shape.operationId,
        checkNotNull(application.appliedAction), application.financialRevisions))
}

private fun requireRetailPaymentApplicationEffectsV3(shape: RetailOperationShapeV3, fact: LocalRetailJournalFactV3,
    application: LocalRetailJournalApplicationV3, slot: LocalRetailJournalSlotV3, proof: LocalRetailApplicationProofV3,
    parent: RetailApplicationParentV3?) {
    val target = fact.target
    val images = retailSourceImagesV3(shape, proof.sources)
    val documentImage = retailSourceImageV3(images, "collection", LocalSalesStoreProfile.COLLECTION_KIND, target.documentId)
    val before = SalesSnapshotCodec.decodeHistoricalCollection(retailTextV3(documentImage.body()),
        SalesSnapshotBinding(SalesSnapshotKind.COLLECTION, shape.scope, target.documentId, documentImage.domainRevisionV3()))
    val orderImage = retailSourceImageV3(images, "order", LocalSalesStoreProfile.ORDER_KIND, before.orderId)
    val order = SalesSnapshotCodec.decodeOrder(retailTextV3(orderImage.body()),
        SalesSnapshotBinding(SalesSnapshotKind.ORDER, shape.scope, before.orderId, orderImage.domainRevisionV3()))
    val cashImage = retailSourceImageV3(images, "cash", CashNonSaleStoreProfile.SESSION_KIND, target.sessionId)
    val cash = SalesSnapshotCodec.decodeCashSession(retailTextV3(cashImage.body()),
        SalesSnapshotBinding(SalesSnapshotKind.CASH_SESSION, shape.scope, target.sessionId, cashImage.domainRevisionV3()))
    val priceReference = order.priceSourceReference
    val returnReference = order.returnPolicySourceReference
    check(images.keys == setOf("collection", "order", "cash") +
        (if (priceReference == null) emptySet() else setOf("price_sources")) +
        (if (returnReference == null) emptySet() else setOf("return_policy_capture")))
    val prices = priceReference?.let { reference ->
        val source = retailSourceImageV3(images, "price_sources", LocalSalesStoreProfile.PRICE_SOURCES_KIND, reference.id)
        check(source.storageRevision == Revision(1))
        requirePriceSources(source.body(), shape.scope, order.id, order.quote, reference,
            if (order.stockRequirements.normalized) null else order.stockRequirements.allocations, order.stockRequirements.normalizedLines)
    }
    returnReference?.let { reference ->
        val source = images.getValue("return_policy_capture")
        check(source.sourceKind == LocalSalesReturnPolicyCaptureCodecV2.KIND && source.sourceId == reference.id &&
            source.sourceSchema == LocalSalesReturnPolicyCaptureCodecV2.SCHEMA && source.storageRevision == Revision(1))
        requirePaymentReturnPolicyCapture(source.body(), shape.scope, order, checkNotNull(prices))
    }
    requireRetailCollectionEligibleV3(slot.actorId, before, order, cash)
    val attempt = before.attempts.single { it.id == target.attemptId }
    requireRetailAttemptBindingV3(attempt.id, attempt.originalOperationId, attempt.originalActorId, attempt.originalCashSessionId,
        attempt.amount, attempt.tender, attempt.status, fact.phase, slot, cash)
    check(attempt.payment == null && (if (parent == null) attempt.latestObservation == null
        else attempt.latestObservation == retailPaymentObservation(parent.fact).journalOutcome(parent.fact.recordingActorId)))
    val documentKey = AggregateKey(LocalSalesStoreProfile.COLLECTION_KIND, before.id)
    val document = retailEffectV3(shape.changes, documentKey, LocalSalesStoreProfile.schema(documentKey.kind), documentImage.storageRevision)
    val after = SalesSnapshotCodec.decodeHistoricalCollection(retailTextV3(document.payload),
        SalesSnapshotBinding(SalesSnapshotKind.COLLECTION, shape.scope, before.id, before.revision.next()))
    sameCollectionCore(before, after)
    check(after.saleId == before.saleId && after.attempts.size == before.attempts.size && before.attempts.indices.all {
        before.attempts[it].id == target.attemptId || (sameAttempt(before.attempts[it], after.attempts[it]) &&
            before.attempts[it].resolutionObservation == after.attempts[it].resolutionObservation) })
    val updated = after.attempts.single { it.id == target.attemptId }
    check(sameRetailPaymentIdentityV3(attempt, updated))
    val raw = retailPaymentObservation(fact)
    val outcome = raw.journalOutcome(fact.recordingActorId)
    val unknown = raw is LocalPaymentOutcomeV3.Unknown
    val paid = raw is LocalPaymentOutcomeV3.CashReceived || raw is LocalPaymentOutcomeV3.ExternalAttested
    check(updated.status == retailResultStatusV3(unknown, paid) && updated.latestObservation == outcome &&
        application.appliedAction == retailPaymentAction(raw))
    requireRetailResolutionObservationLinkV1(updated.resolutionObservation, shape, fact, parent)
    updated.payment?.let { check(it.resolutionObservation == updated.resolutionObservation) }
    val evidence = when (outcome) {
        is CollectionOutcome.Unknown -> outcome.evidence
        is CollectionOutcome.NotPaid -> outcome.evidence
        is CollectionOutcome.CashReceived -> outcome.evidence
        is CollectionOutcome.ExternalAttested -> outcome.evidence.attestation
    }
    val payment = updated.payment
    if (!paid) check(payment == null)
    else {
        check(payment != null && payment.attemptId == attempt.id && payment.originalOperationId == target.intentOperationId &&
            payment.recordingOperationId == target.factOperation(fact.phase) && payment.collectionId == target.documentId &&
            payment.collectorId == slot.actorId && payment.cashSessionId == target.sessionId && payment.payerReference == attempt.payerReference &&
            payment.tender == attempt.tender && payment.amount == slot.domain.amount && payment.attestation == evidence)
        if (raw is LocalPaymentOutcomeV3.CashReceived) {
            check(attempt.tender == Tender.Cash && raw.received.minor > 0 && raw.change.minor >= 0 && raw.received - raw.change == attempt.amount &&
                payment.cashReceived == raw.received && payment.changeGiven == raw.change && payment.externalEvidence == null)
        } else {
            check(raw is LocalPaymentOutcomeV3.ExternalAttested && payment.cashReceived == null && payment.changeGiven == null &&
                payment.externalEvidence == (outcome as CollectionOutcome.ExternalAttested).evidence)
            requireRetailExternalV3(attempt.tender, raw.terminal, raw.reference)
        }
    }
    val movement = if (raw is LocalPaymentOutcomeV3.CashReceived) RetailCashMovementProofV3(fact.scope, attempt.id, shape.operationId,
        shape.actorId, cash.id, "CUSTOMER_RECEIPT", attempt.amount, raw.received, raw.change, raw.evidence, null, checkNotNull(shape.recordedAt)) else null
    val cashEffect = requireRetailCashApplicationV3(shape, cash, cashImage.storageRevision, attempt.id, unknown, attempt.amount.zero(), movement,
        if (paid && attempt.tender is Tender.External) attempt.amount else attempt.amount.zero(), attempt.amount.zero())
    val revisions = listOf(LocalSalesRevision(LocalSalesStoreProfile.COLLECTION_KIND, before.id, after.revision)) + cashEffect.revisions
    val claims = listOfNotNull(if (paid) ClaimChange.Acquire(LocalSalesStoreProfile.unique("payment_record", attempt.id), before.id) else null) + cashEffect.claims
    val extra = if (before.stockRequirements.normalized) {
        // Start/fact ancestry qualifies the original tender. Accounting does not re-read
        // today's catalog, and an external attestation never creates a cash movement.
        val producer = StreamedProducerIdentityV2(shape.operationId, shape.command, shape.actorId, shape.digest)
        val observed = StockReadEvidenceV1.decodeChanges(shape.scope, producer, shape.changes) ?: error("sales.stock.payment_read_required")
        val sources = NormalizedPaymentSources(observed)
        val source = sources.collection(before, order, cash)
        sources.applicationVacancy(attempt.id)
        val link = sources.advanceLink(source, producer, document, shape.changes)
        sources.finish(stock = false) // Accounting NEVER tests today's stock availability.
        shape.changes.filter { it.key.kind == StockReadEvidenceV1.KIND }.map { it.key }.toSet() + link
    } else emptySet()
    requireRetailAppliedClosureV3(shape, application, fact, setOf(documentKey) + cashEffect.keys + extra, claims, revisions)
}

private fun requireRetailRefundApplicationEffectsV3(shape: RetailOperationShapeV3, fact: LocalRetailJournalFactV3,
    application: LocalRetailJournalApplicationV3, slot: LocalRetailJournalSlotV3, proof: LocalRetailApplicationProofV3,
    parent: RetailApplicationParentV3?) {
    val target = fact.target
    val cut = retailRefundBeforeCutV3(shape, target, proof.sources, slot.actorId)
    val before = cut.accepted
    val attempt = before.attempts.single { it.id == target.attemptId }
    requireRetailAttemptBindingV3(attempt.id, attempt.originalOperationId, attempt.originalActorId, attempt.originalCashSessionId,
        attempt.amount, attempt.tender, attempt.status, fact.phase, slot, cut.cash)
    check(attempt.originalPaymentId == target.originalPaymentId && attempt.refund == null &&
        (if (parent == null) attempt.latestObservation == null else attempt.latestObservation == retailRefundObservation(parent.fact).journalOutcome(parent.fact.recordingActorId)))
    val documentKey = AggregateKey(LocalSalesStoreProfile.RETURN_KIND, before.id)
    val document = retailEffectV3(shape.changes, documentKey, LocalSalesStoreProfile.schema(documentKey.kind), cut.sources.getValue("accepted").storageRevision)
    val after = SalesSnapshotCodec.decodeAcceptedReturn(retailTextV3(document.payload),
        SalesSnapshotBinding(SalesSnapshotKind.ACCEPTED_RETURN, shape.scope, before.id, before.revision.next()), cut.entitlement, cut.claim)
    sameRetailReturnCoreV3(before, after)
    check(after.attempts.size == before.attempts.size && before.attempts.indices.all {
        before.attempts[it].id == target.attemptId || (sameRetailRefundAttemptV3(before.attempts[it], after.attempts[it]) &&
            before.attempts[it].resolutionObservation == after.attempts[it].resolutionObservation) })
    val updated = after.attempts.single { it.id == target.attemptId }
    check(sameRetailRefundIdentityV3(attempt, updated))
    val raw = retailRefundObservation(fact)
    val outcome = raw.journalOutcome(fact.recordingActorId)
    val unknown = raw is LocalRefundOutcomeV3.Unknown
    val paid = raw is LocalRefundOutcomeV3.CashPaid || raw is LocalRefundOutcomeV3.ExternalAttested
    check(updated.status == retailResultStatusV3(unknown, paid) && updated.latestObservation == outcome &&
        application.appliedAction == retailRefundAction(raw))
    requireRetailResolutionObservationLinkV1(updated.resolutionObservation, shape, fact, parent)
    updated.refund?.let { check(it.resolutionObservation == updated.resolutionObservation) }
    val evidence = when (outcome) {
        is RefundOutcome.Unknown -> outcome.evidence
        is RefundOutcome.NotPaid -> outcome.evidence
        is RefundOutcome.CashPaid -> outcome.evidence
        is RefundOutcome.ExternalAttested -> outcome.evidence.attestation
    }
    val refund = updated.refund
    if (!paid) check(refund == null)
    else {
        check(refund != null && refund.attemptId == attempt.id && refund.returnId == before.id && refund.originalPaymentId == target.originalPaymentId &&
            refund.originalOperationId == target.intentOperationId && refund.recordingOperationId == target.factOperation(fact.phase) &&
            refund.payingActorId == slot.actorId && refund.payingCashSessionId == target.sessionId && refund.tender == attempt.tender &&
            refund.amount == attempt.amount && refund.attestation == evidence)
        if (raw is LocalRefundOutcomeV3.CashPaid) check(attempt.tender == Tender.Cash && refund.externalEvidence == null)
        else {
            check(raw is LocalRefundOutcomeV3.ExternalAttested && refund.externalEvidence == (outcome as RefundOutcome.ExternalAttested).evidence)
            requireRetailExternalV3(attempt.tender, raw.terminal, raw.reference)
        }
    }
    val movement = if (raw is LocalRefundOutcomeV3.CashPaid) RetailCashMovementProofV3(fact.scope, attempt.id, shape.operationId,
        shape.actorId, cut.cash.id, "CUSTOMER_REFUND", -attempt.amount, attempt.amount.zero(), attempt.amount.zero(), raw.evidence, null, checkNotNull(shape.recordedAt)) else null
    val cashEffect = requireRetailCashApplicationV3(shape, cut.cash, cut.sources.getValue("cash").storageRevision, attempt.id, unknown,
        if (attempt.tender == Tender.Cash) attempt.amount else attempt.amount.zero(), movement, attempt.amount.zero(),
        if (paid && attempt.tender is Tender.External) attempt.amount else attempt.amount.zero())
    val keys = mutableSetOf(documentKey)
    keys += cashEffect.keys
    val revisions = mutableListOf(LocalSalesRevision(LocalSalesStoreProfile.RETURN_KIND, before.id, after.revision))
    revisions += cashEffect.revisions
    if (!after.unresolved && after.due.minor == 0L) {
        val workKey = AggregateKey(LocalSalesStoreProfile.WORK_KIND, cut.work.id)
        val workRow = retailEffectV3(shape.changes, workKey, LocalSalesStoreProfile.schema(workKey.kind), cut.sources.getValue("work").storageRevision)
        val work = localSalesDecode(workRow.payload, LocalWorkState.serializer())
        check(work == cut.work.copy(revision = cut.work.revision.next(), returnIds = cut.work.returnIds - before.id))
        keys += workKey
        revisions += LocalSalesRevision(LocalSalesStoreProfile.WORK_KIND, work.id, work.revision)
    }
    val claims = listOfNotNull(if (paid) ClaimChange.Acquire(LocalSalesStoreProfile.unique("refund_record", attempt.id), before.id) else null) + cashEffect.claims
    requireRetailAppliedClosureV3(shape, application, fact, keys, claims, revisions)
}

private fun requireRetailResolutionObservationLinkV1(link: com.ukkera.pos.sales.RetailResolutionObservationLink?,
    shape: RetailOperationShapeV3, fact: LocalRetailJournalFactV3, parent: RetailApplicationParentV3?) {
    if (shape.command != LocalRetailSupervisorCodecV1.COMMAND || fact.phase == LocalJournalFactPhaseV3.ORIGINAL) {
        check(link == null); return
    }
    check(parent != null && link != null && link.originalUnknownOperationId == parent.fact.target.recordingOperationId &&
        link.originalUnknownBodyDigest.value == parent.fact.bodyDigest && link.resolutionOperationId == fact.target.resolutionOperationId &&
        link.resolutionRecorderId == fact.recordingActorId && link.accountingOperationId == shape.operationId &&
        link.accountingActorId == shape.actorId && fact.originalBodyDigest == parent.fact.bodyDigest)
}

private fun requireRetailAttemptBindingV3(id: DomainId, intent: OperationId, actor: DomainId, sessionId: DomainId,
    amount: Money, tender: Tender, status: AttemptStatus, phase: LocalJournalFactPhaseV3, slot: LocalRetailJournalSlotV3, cash: CashSession) {
    check(id == slot.target.attemptId && intent == slot.target.intentOperationId && actor == slot.actorId &&
        sessionId == slot.target.sessionId && cash.id == sessionId && cash.custodianId == actor && id in cash.pendingAttemptIds &&
        amount == slot.domain.amount && tenderView(tender) == slot.domain.tender &&
        status == (if (phase == LocalJournalFactPhaseV3.ORIGINAL) AttemptStatus.AWAITING_ATTESTATION else AttemptStatus.OUTCOME_UNKNOWN))
}

private fun sameRetailPaymentIdentityV3(before: PaymentAttempt, after: PaymentAttempt): Boolean = before.id == after.id &&
    before.originalOperationId == after.originalOperationId && before.originalActorId == after.originalActorId &&
    before.originalCashSessionId == after.originalCashSessionId && before.payerReference == after.payerReference &&
    before.tender == after.tender && before.amount == after.amount

private fun retailResultStatusV3(unknown: Boolean, paid: Boolean): AttemptStatus =
    if (unknown) AttemptStatus.OUTCOME_UNKNOWN else if (paid) AttemptStatus.ATTESTED_PAID else AttemptStatus.NOT_PAID

private fun requireRetailExternalV3(tender: Tender, terminal: String?, reference: String?) {
    check(tender is Tender.External && (!tender.requiresTerminal || terminal != null) && (!tender.requiresReference || reference != null) &&
        listOf(terminal, reference).all { it == null || it.isNotBlank() && it.length <= 128 })
}

/** Exact existing sales.cash-movement projection wire, not a fabricated domain CashMovement. */
@Serializable
internal data class RetailCashMovementProofV3(
    val scope: LocalSalesScope, val sourceId: DomainId, val operationId: OperationId, val originalActorId: DomainId,
    val sessionId: DomainId, val kind: String, val delta: Money, val cashReceived: Money, val changeGiven: Money,
    val evidence: LocalCashEvidenceV1, val reason: String?, val recordedAt: UtcInstant,
)

private class RetailCashApplicationEffectsV3(val keys: Set<AggregateKey>, val revisions: List<LocalSalesRevision>, val claims: List<ClaimChange>)

private fun requireRetailCashApplicationV3(shape: RetailOperationShapeV3, before: CashSession, storageRevision: Revision,
    attemptId: DomainId, unknown: Boolean, releasedReserve: Money, movement: RetailCashMovementProofV3?,
    externalCollection: Money, externalRefund: Money): RetailCashApplicationEffectsV3 {
    if (unknown) {
        check(movement == null && externalCollection.minor == 0L && externalRefund.minor == 0L)
        return RetailCashApplicationEffectsV3(emptySet(), emptyList(), emptyList())
    }
    val key = CashNonSaleStoreProfile.sessionKey(before.id)
    val row = retailEffectV3(shape.changes, key, CashNonSaleStoreProfile.SESSION_SCHEMA, storageRevision)
    val after = SalesSnapshotCodec.decodeCashSession(retailTextV3(row.payload),
        SalesSnapshotBinding(SalesSnapshotKind.CASH_SESSION, shape.scope, before.id, before.revision.next()))
    sameRetailCashCoreV3(before, after)
    var totals = before.totals.copy(externalCollections = before.totals.externalCollections + externalCollection,
        externalRefunds = before.totals.externalRefunds + externalRefund)
    if (movement != null) totals = when (movement.kind) {
        "CUSTOMER_RECEIPT" -> totals.copy(received = totals.received + movement.cashReceived, change = totals.change + movement.changeGiven)
        "CUSTOMER_REFUND" -> totals.copy(refunds = totals.refunds - movement.delta)
        else -> error("sales.journal.cash_kind")
    }
    check(after.totals == totals && after.expectedCash == before.expectedCash + (movement?.delta ?: before.expectedCash.zero()) &&
        after.reservedCashOut == before.reservedCashOut - releasedReserve && after.pendingAttemptIds == before.pendingAttemptIds - attemptId &&
        after.cashLedgerRevision == (if (movement == null) before.cashLedgerRevision else before.cashLedgerRevision.next()))
    val keys = mutableSetOf(key)
    val claims = mutableListOf<ClaimChange>()
    if (movement != null) {
        val movementKey = AggregateKey(LocalSalesStoreProfile.MOVEMENT_KIND, attemptId)
        val written = retailEffectV3(shape.changes, movementKey, LocalSalesStoreProfile.schema(movementKey.kind), null)
        check(written.payload == localSalesEncode(movement, RetailCashMovementProofV3.serializer()))
        keys += movementKey
        claims += ClaimChange.Acquire(CashNonSaleStoreProfile.cashSourceClaim(attemptId), before.id)
    }
    return RetailCashApplicationEffectsV3(keys, listOf(LocalSalesRevision(CashNonSaleStoreProfile.SESSION_KIND, before.id, after.revision)), claims)
}

private fun requireRetailAppliedClosureV3(shape: RetailOperationShapeV3, application: LocalRetailJournalApplicationV3,
    fact: LocalRetailJournalFactV3, financialKeys: Set<AggregateKey>, claims: List<ClaimChange>, revisions: List<LocalSalesRevision>) {
    val keys = financialKeys + retailJournalApplicationKeyV3(fact.target.factOperation(fact.phase))
    check(shape.changes.size == keys.size && shape.changes.map { it.key }.toSet() == keys && application.financialRevisions == revisions)
    requireRetailClaimsV3(shape.claims, claims)
}

/** File-local currency-preserving zero; the domain module's helper is internal. */
private fun Money.zero(): Money = Money(currency, 0L)
