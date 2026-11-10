package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.ClaimChange
import com.ukkera.pos.database.ClaimKey
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.FutureOperationReservation
import com.ukkera.pos.database.ReservedOperations
import com.ukkera.pos.database.RetainedAggregateWrite
import com.ukkera.pos.database.RetainedClaimAction
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.inventory.CollectionExposure
import com.ukkera.pos.inventory.InventoryPostedEffectKind
import com.ukkera.pos.inventory.InventoryPostedRecords
import com.ukkera.pos.inventory.ReservationStatus
import com.ukkera.pos.inventory.StockMovementReason
import com.ukkera.pos.inventory.StockReservation
import com.ukkera.pos.inventory.StockSafety
import com.ukkera.pos.inventory.ValuationFact
import com.ukkera.pos.inventory.ValueIssuePurpose
import com.ukkera.pos.inventory.store.LocalStockCompositeVerifier
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.inventory.store.LocalStockStoreProfile
import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.sales.AttemptStatus
import com.ukkera.pos.sales.CashSession
import com.ukkera.pos.sales.CashSessionStatus
import com.ukkera.pos.sales.CollectionLock
import com.ukkera.pos.sales.OwnedOrder
import com.ukkera.pos.sales.PaidSale
import com.ukkera.pos.sales.PaymentAttempt
import com.ukkera.pos.sales.SalesSnapshotBinding
import com.ukkera.pos.sales.SalesSnapshotCodec
import com.ukkera.pos.sales.SalesSnapshotKind
import com.ukkera.pos.sales.SalesSnapshotLinks
import com.ukkera.pos.sales.Tender
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.AllocationWeight
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.allocateByLargestRemainder

/**
 * Closed production producer validator, not an accepting command-name allowlist. Checks the
 * COMPLETE original sales request/result, all original sales before/after values, reservation
 * and issue correspondence, every claim and requested job. The normalized stock repository
 * separately verifies the full posting manifest, original read references/dependency closure,
 * predecessor rows, pool/page lineage and collateral preservation in its borrowed transaction.
 * Neither half is a writer lease, current frontier, authorization or absence proof.
 */
public class LocalCollectionCompositeVerifier(
    private val tenderPolicies: HistoricalSalesTenderPolicySourceV1 = UnavailableHistoricalSalesTenderPolicySourceV1,
) : LocalStockCompositeVerifier {
    public companion object {
        /** Separate whole streamed profile; never hand a normalized original to the legacy parser. */
        public fun normalized(quotes: TransactionSalesStockQuoteSource,
            tenderPolicies: HistoricalSalesTenderPolicySourceV1 = UnavailableHistoricalSalesTenderPolicySourceV1): com.ukkera.pos.inventory.store.LocalStockRelationalCompositeVerifier =
            LocalCollectionStockProducer(quotes, tenderPolicies)
    }

    override public val commands: Set<CommandKey> = setOf(LocalSalesStoreProfile.COLLECTION_COMMAND, LocalSalesStoreProfile.COLLECTION_COMMAND_V2,
        LocalFinancialJournalProfileV3.COLLECTION_COMMAND)
    private val inventory = LocalStockRepository()

    override public fun requireComplete(evidence: RetainedCommandEvidence) {
        compositeCheck(evidence.committed && evidence.command in commands && evidence.jobs.isEmpty())
        compositeCheck(semanticDigest(evidence.scope, evidence.originalActorId, evidence.command, evidence.normalized) == evidence.semanticDigest)
        if (evidence.command == LocalFinancialJournalProfileV3.COLLECTION_COMMAND &&
            evidence.aggregateWrites.any { it.key.kind == com.ukkera.pos.database.StockReadEvidenceV1.KIND }) {
            requireNormalizedPaymentStartOriginal(evidence, tenderPolicies) // Literal exact financial/read shape; actual-view joins are separately mandatory.
            return
        }
        val records = inventory.inspectPosting(evidence) ?: error("sales.composite.inventory")
        val proof = CollectionProof(evidence, records, tenderPolicies)
        if (evidence.command == LocalFinancialJournalProfileV3.COLLECTION_COMMAND) {
            compositeCheck(LocalCollectionCommandCodecV3.normalize(evidence.normalized) == evidence.normalized)
            val request = LocalCollectionCommandCodecV3.decode(evidence.normalized) as? LocalCollectionRequestV3.Start
                ?: error("sales.composite.non_stock_producer")
            proof.startV3(request)
            return
        }
        if (evidence.command == LocalSalesStoreProfile.COLLECTION_COMMAND_V2) {
            compositeCheck(LocalCollectionCommandCodecV2.normalize(evidence.normalized) == evidence.normalized)
            val request = LocalCollectionCommandCodecV2.decode(evidence.normalized) as? LocalCollectionRequestV2.StartAttempt
                ?: error("sales.composite.non_stock_producer")
            proof.start(request.intentV1(), request)
            return
        }
        compositeCheck(LocalCollectionCommandCodec.normalize(evidence.normalized) == evidence.normalized)
        when (val request = LocalCollectionCommandCodec.decode(evidence.normalized)) {
            is LocalCollectionRequestV1.Begin -> proof.begin(request)
            is LocalCollectionRequestV1.StartAttempt -> proof.start(request)
            is LocalCollectionRequestV1.Finalize -> proof.finalize(request)
            is LocalCollectionRequestV1.RecordOutcome, is LocalCollectionRequestV1.ReserveRecording ->
                error("sales.composite.non_stock_producer")
        }
    }
}

private class CollectionProof(val evidence: RetainedCommandEvidence, val stock: InventoryPostedRecords,
    val tenderPolicies: HistoricalSalesTenderPolicySourceV1) {
    private val used = mutableSetOf<AggregateKey>()
    private val result: LocalSalesResultV1 by lazy { LocalSalesResultCodec.decode(evidence.result) }
    private val stockItems = evidence.aggregateWrites.filter { it.key.kind == LocalStockStoreProfile.ITEM }
        .map { LocalSalesStockRevisionV1(it.key.id, it.storageGeneration) }.sortedBy { it.skuId.value }
    private val afterStockKinds: Set<String> get() = evidence.aggregateWrites.filter { it.key.kind in LocalStockStoreProfile.inventoryKinds }.map { it.key.kind }.toSet()

    /** Explicit successor decoding. No v3 source/slot is passed through either old admission verifier. */
    fun startV3(request: LocalCollectionRequestV3.Start) {
        val before = beforeCollection(request.collectionId, request.expectedCollectionRevision)
        val order = beforeOrder(before.orderId, before.lockedOrderRevision)
        val cash = beforeCash(request.sessionId, request.expectedSessionRevision)
        eligible(before, order, cash)
        compositeCheck(before.cashSessionId == request.sessionId && !before.unresolved && before.attempts.none { it.id == request.attemptId } &&
            request.amount.currency == before.quote.currency && request.amount.minor > 0 && request.amount.minor <= before.remainingPayable.minor)
        requireItems(request.items, before)
        when (val value = LocalFinancialResultCodecV3.decode(evidence.result)) {
            is LocalFinancialResultV3.StockHeld -> {
                requireRetailHeldEvidenceV3(evidence)
                val reservation = safety(before, request.expectedReservationRevision, held = true)
                compositeCheck(reservation.binding.exposure != CollectionExposure.PAID && value.collectionId == before.id &&
                    value.reservationId == reservation.reservationId && value.reservationRevision == reservation.revision && value.stockItems == stockItems)
                requireClaims(emptyList())
            }
            is LocalFinancialResultV3.AttemptStarted -> {
                val reservation = safety(before, request.expectedReservationRevision, held = false)
                compositeCheck(reservation.binding.exposure != CollectionExposure.PAID)
                val slotRow = row(LocalFinancialJournalProfileV3.SLOT_KIND, DomainId(request.recordingOperationId.value))
                compositeCheck(slotRow.schema == LocalFinancialJournalProfileV3.SLOT_SCHEMA && slotRow.storageGeneration == Revision(1))
                val slot = localSalesDecode(slotRow.payload, LocalRetailJournalSlotV3.serializer(), LocalFinancialJournalProfileV3.MAX_SLOT_BYTES)
                // Lower-level validation closes every source/financial/before byte, permanent
                // claim and typed result; it never calls readRetailSlot/this composite again.
                val envelope = requireRetailStartEnvelopeV3(evidence, slot, tenderPolicies)
                used += envelope.gatedChanges.map { it.key }
                val after = afterCollection(before.id, before.revision.next())
                val afterCash = afterCash(cash.id, cash.revision.next())
                SalesSnapshotLinks.requireCollection(after, order, afterCash)
                compositeCheck(value.stockItems == stockItems && envelope.slot.target == request.target(evidence.operationId))
                requireClaims(envelope.lineageClaims + ClaimChange.Acquire(LocalSalesStoreProfile.unique("payment_attempt", request.attemptId), before.id))
            }
            else -> error("sales.composite.not_attempt_start")
        }
        // inspectPosting() binds the actual result/ALL retained writes, claims and jobs,
        // including collateral stock rows. Dependency-history validation remains in
        // the real stock loader; this closes only the result/stock-POSTING digest cycle.
        finish(setOf(LocalStockStoreProfile.POSTING, LocalStockStoreProfile.ITEM, LocalStockStoreProfile.POSITION, LocalStockStoreProfile.RESERVATION))
    }

    fun begin(request: LocalCollectionRequestV1.Begin) {
        val order = beforeOrder(request.orderId, request.expectedOrderRevision)
        val cash = beforeCash(request.sessionId, request.expectedSessionRevision)
        compositeCheck(order.ownerId == evidence.originalActorId && cash.custodianId == evidence.originalActorId && cash.status == CashSessionStatus.OPEN &&
            order.collectionId == null && order.paidSaleId == null && order.handoff == null)
        val afterOrder = afterOrder(order.id, order.revision.next())
        sameOrderCore(order, afterOrder)
        compositeCheck(afterOrder.collectionId == request.collectionId && afterOrder.paidSaleId == null && afterOrder.handoff == null)
        val collection = afterCollection(request.collectionId, Revision(0))
        SalesSnapshotLinks.requireCollection(collection, afterOrder, cash)
        compositeCheck(collection.attempts.isEmpty() && collection.reservationId == request.reservationId && collection.saleId == null &&
            collection.collectorId == evidence.originalActorId && collection.cashSessionId == request.sessionId)
        compositeCheck(stock.effectKinds == listOf(InventoryPostedEffectKind.RESERVATION) && stock.reservations.size == 1)
        val reservation = stock.reservations.single()
        reservationLink(reservation, collection)
        compositeCheck(reservation.revision == Revision(1) && reservation.status == ReservationStatus.RESERVED && reservation.holds.isEmpty() &&
            reservation.binding.exposure == CollectionExposure.UNKNOWN &&
            reservation.expiresAtEpochMillis == request.reservationExpiresAt?.value?.toEpochMilliseconds())
        request.reservationExpiresAt?.let { compositeCheck(it > evidence.recordedAt) }
        requireItems(request.items, collection)
        requireResult(LocalSalesResultV1.Applied(LocalSalesAction.COLLECTION_LOCKED, collection.id,
            listOf(LocalSalesRevision(LocalSalesStoreProfile.ORDER_KIND, order.id, afterOrder.revision),
                LocalSalesRevision(LocalSalesStoreProfile.COLLECTION_KIND, collection.id, collection.revision)), collection.quote.payable, stockItems))
        requireClaims(listOf(ClaimChange.Acquire(LocalSalesStoreProfile.unique("collection", collection.id), order.id),
            ClaimChange.Acquire(LocalStockStoreProfile.reservationClaim(reservation.reservationId), reservation.reservationId)) +
            reservation.allocations.map { ClaimChange.Acquire(LocalStockStoreProfile.allocationClaim(it.allocationId), reservation.reservationId) })
        finish(setOf(LocalStockStoreProfile.POSTING, LocalStockStoreProfile.ITEM, LocalStockStoreProfile.RESERVATION))
    }

    fun start(request: LocalCollectionRequestV1.StartAttempt, physicalRequest: LocalCollectionRequestV2.StartAttempt? = null) {
        val before = beforeCollection(request.collectionId, request.expectedCollectionRevision)
        val order = beforeOrder(before.orderId, before.lockedOrderRevision)
        val cash = beforeCash(before.cashSessionId, request.expectedSessionRevision)
        compositeCheck(physicalRequest == null || physicalRequest.sessionId == cash.id)
        eligible(before, order, cash)
        compositeCheck(!before.unresolved && request.amount.currency == before.quote.currency && request.amount.minor > 0 &&
            request.amount.minor <= before.remainingPayable.minor && before.attempts.none { it.id == request.attemptId })
        requireItems(request.items, before)
        val held = result is LocalSalesResultV1.StockResolutionRequired
        val reservation = safety(before, request.expectedReservationRevision, held)
        compositeCheck(reservation.binding.exposure != CollectionExposure.PAID)
        if (held) {
            requireHeld(before, reservation)
            requireClaims(emptyList())
        } else {
            val after = afterCollection(before.id, before.revision.next())
            sameCollectionCore(before, after)
            compositeCheck(after.saleId == null && after.attempts.size == before.attempts.size + 1 &&
                before.attempts.indices.all { sameAttempt(before.attempts[it], after.attempts[it]) })
            val attempt = after.attempts.last()
            compositeCheck(attempt.id == request.attemptId && attempt.originalOperationId == evidence.operationId &&
                attempt.originalActorId == evidence.originalActorId && attempt.originalCashSessionId == cash.id &&
                attempt.amount == request.amount && attempt.payerReference == request.payerReference && attempt.status == AttemptStatus.AWAITING_ATTESTATION &&
                attempt.latestObservation == null && attempt.payment == null)
            when (val selected = request.tender) {
                LocalTenderSelectionV1.Cash -> compositeCheck(attempt.tender == Tender.Cash)
                is LocalTenderSelectionV1.External -> {
                    val tender = attempt.tender as? Tender.External ?: error("sales.composite.tender")
                    compositeCheck(tender.tenderId == selected.tenderId && tender.policyRevision == selected.expectedPolicyRevision)
                }
            }
            val afterCash = afterCash(cash.id, cash.revision.next())
            cashIntentOnly(cash, afterCash, attempt.id)
            SalesSnapshotLinks.requireCollection(after, order, afterCash)
            val legacySlot = if (physicalRequest == null) recordingSlot(request, before) else null
            val physicalSlot = physicalRequest?.let { recordingSlotV2(it, before, cash, attempt) }
            val slotId = legacySlot?.id ?: checkNotNull(physicalSlot).id
            val slotRevision = legacySlot?.revision ?: checkNotNull(physicalSlot).revision
            val slotKind = if (physicalSlot == null) LocalSalesStoreProfile.RECORDING_SLOT_KIND else LocalSalesStoreProfile.PHYSICAL_SLOT_KIND
            val masterAllocationId = legacySlot?.allocationId ?: checkNotNull(physicalSlot).masterAllocationId
            compositeCheck(row(LocalSalesStoreProfile.COLLECTION_KIND, before.id).payload.size <= SalesRecordingBudget.MAX_PENDING_DOCUMENT_BYTES &&
                row(CashNonSaleStoreProfile.SESSION_KIND, cash.id).payload.size <= SalesRecordingBudget.MAX_PENDING_DOCUMENT_BYTES)
            requireResult(LocalSalesResultV1.Applied(LocalSalesAction.PAYMENT_ATTEMPT_STARTED, before.id,
                listOf(LocalSalesRevision(LocalSalesStoreProfile.COLLECTION_KIND, before.id, after.revision),
                    LocalSalesRevision(CashNonSaleStoreProfile.SESSION_KIND, cash.id, afterCash.revision),
                    LocalSalesRevision(slotKind, slotId, slotRevision)), request.amount, stockItems))
            requireClaims(listOf(ClaimChange.Acquire(LocalSalesStoreProfile.unique("payment_attempt", attempt.id), before.id),
                ClaimChange.Acquire(ClaimKey(LocalSalesStoreProfile.RECORDING_OPERATION_NAMESPACE, slotId.value), attempt.id),
                ClaimChange.Acquire(ClaimKey(LocalSalesStoreProfile.RECORDING_ALLOCATION_NAMESPACE, masterAllocationId.value), attempt.id)) +
                listOfNotNull(physicalSlot?.let { ClaimChange.Acquire(physicalClientAllocationClaim(it.clientAllocationId), attempt.id) }) +
                ClaimChange.Acquire(ClaimKey(LocalSalesStoreProfile.RECORDING_ATTEMPT_NAMESPACE, attempt.id.value), slotId))
        }
        finish(setOf(LocalStockStoreProfile.POSTING, LocalStockStoreProfile.ITEM, LocalStockStoreProfile.POSITION, LocalStockStoreProfile.RESERVATION))
    }

    fun finalize(request: LocalCollectionRequestV1.Finalize) {
        val before = beforeCollection(request.collectionId, request.expectedCollectionRevision)
        val order = beforeOrder(before.orderId, request.expectedOrderRevision)
        val cash = beforeCash(before.cashSessionId, request.expectedSessionRevision)
        val workRow = beforeRow("work", LocalSalesStoreProfile.schema(LocalSalesStoreProfile.WORK_KIND))
        val work = localSalesDecode(workRow.payload, LocalWorkState.serializer())
        compositeCheck(work.scope.value() == evidence.scope && work.id == order.ownerId && order.id in work.orderIds &&
            listOf(work.orderIds, work.returnIds, work.pendingClaimIds).all { it.size <= LocalSalesStoreProfile.MAX_OPEN_WORK && it.distinct().size == it.size })
        eligible(before, order, cash)
        compositeCheck(!before.unresolved && before.collected == before.quote.payable)
        requireItems(request.items, before)
        if (result is LocalSalesResultV1.StockResolutionRequired) {
            val reservation = safety(before, request.expectedReservationRevision, held = true)
            requireHeld(before, reservation)
            requireClaims(emptyList())
            finish(setOf(LocalStockStoreProfile.POSTING, LocalStockStoreProfile.ITEM, LocalStockStoreProfile.POSITION, LocalStockStoreProfile.RESERVATION))
            return
        }
        val issue = requireIssue(before, request)
        val afterOrder = afterOrder(order.id, order.revision.next())
        val after = afterCollection(before.id, before.revision.next())
        sameOrderCore(order, afterOrder)
        sameCollectionCore(before, after)
        compositeCheck(afterOrder.collectionId == before.id && afterOrder.paidSaleId == request.saleId && afterOrder.handoff == null &&
            after.saleId == request.saleId && after.attempts.size == before.attempts.size &&
            before.attempts.indices.all { sameAttempt(before.attempts[it], after.attempts[it]) })
        SalesSnapshotLinks.requireCollection(after, afterOrder, cash)
        val saleRow = afterRow(LocalSalesStoreProfile.SALE_KIND, request.saleId, Revision(0))
        val sale = SalesSnapshotCodec.decodePaidSale(saleRow.text(), SalesSnapshotBinding(SalesSnapshotKind.PAID_SALE, evidence.scope, request.saleId, Revision(0)))
        compositeCheck(sale.orderId == order.id && sale.originalCreatorId == order.originalCreatorId && sale.collectorId == evidence.originalActorId &&
            sale.collectingSessionId == cash.id && sale.stockIssueId == issue.issueId && sameQuote(sale.quote, before.quote) &&
            sale.priceSourceReference == before.priceSourceReference && sale.returnPolicySourceReference == before.returnPolicySourceReference &&
            sale.payments == before.attempts.mapNotNull { it.payment })
        originalTenderMatrix(sale)
        val entitlementRow = afterRow(LocalSalesStoreProfile.ENTITLEMENT_KIND, request.saleId, Revision(0))
        val entitlement = SalesSnapshotCodec.decodeReceiptEntitlement(entitlementRow.text(),
            SalesSnapshotBinding(SalesSnapshotKind.RECEIPT_ENTITLEMENT, evidence.scope, request.saleId, Revision(0)))
        compositeCheck(SalesSnapshotCodec.encodePaidSale(entitlement.sale) == SalesSnapshotCodec.encodePaidSale(sale) &&
            entitlement.cursors.map { it.portionId } == sale.quote.lines.map { it.portionId } &&
            entitlement.cursors.all { it.consumedQuantity.scaled == 0L } &&
            entitlement.remainingComponentTenders.map { it.componentKey to it.shares } == sale.originalTenderAllocations.map { it.componentKey to it.shares })
        val afterWorkRow = afterRow(LocalSalesStoreProfile.WORK_KIND, work.id, work.revision.next())
        val afterWork = localSalesDecode(afterWorkRow.payload, LocalWorkState.serializer())
        compositeCheck(afterWork == work.copy(revision = work.revision.next(), orderIds = work.orderIds - order.id))
        requireResult(LocalSalesResultV1.Applied(LocalSalesAction.SALE_FINALIZED, request.saleId,
            listOf(LocalSalesRevision(LocalSalesStoreProfile.ORDER_KIND, order.id, afterOrder.revision),
                LocalSalesRevision(LocalSalesStoreProfile.COLLECTION_KIND, before.id, after.revision),
                LocalSalesRevision(LocalSalesStoreProfile.SALE_KIND, sale.id, Revision(0)),
                LocalSalesRevision(LocalSalesStoreProfile.ENTITLEMENT_KIND, sale.id, Revision(0)),
                LocalSalesRevision(LocalSalesStoreProfile.WORK_KIND, work.id, afterWork.revision)), sale.quote.payable, stockItems))
        requireClaims(listOf(ClaimChange.Acquire(LocalStockStoreProfile.sourceClaim(issue.issueId), DomainId(evidence.operationId.value)),
            ClaimChange.Acquire(LocalSalesStoreProfile.unique("sale", sale.id), order.id),
            ClaimChange.Acquire(LocalSalesStoreProfile.unique("sale_order", order.id), sale.id),
            ClaimChange.Acquire(LocalSalesStoreProfile.unique("return_entitlement", sale.id), sale.id)))
        finish(setOf(LocalStockStoreProfile.POSTING, LocalStockStoreProfile.ITEM, LocalStockStoreProfile.POSITION, LocalStockStoreProfile.RESERVATION,
            LocalStockStoreProfile.ISSUE, LocalStockStoreProfile.POOL, LocalStockStoreProfile.VALUE_PAGE))
    }

    private fun requireIssue(collection: CollectionLock, request: LocalCollectionRequestV1.Finalize): com.ukkera.pos.inventory.StockIssue {
        compositeCheck(stock.effectKinds.all { it in setOf(InventoryPostedEffectKind.SAFETY, InventoryPostedEffectKind.RESERVATION,
            InventoryPostedEffectKind.MOVEMENT, InventoryPostedEffectKind.VALUATION, InventoryPostedEffectKind.ISSUE) } &&
            stock.effectKinds.takeLast(2) == listOf(InventoryPostedEffectKind.RESERVATION, InventoryPostedEffectKind.ISSUE) && stock.issues.size == 1)
        requireExpiryOnly()
        val terminal = reservationHistory(collection, request.expectedReservationRevision).last()
        compositeCheck(terminal.status == ReservationStatus.ISSUED && terminal.holds.isEmpty())
        val issue = stock.issues.single()
        compositeCheck(issue.issueId == request.stockIssueId && issue.reservationId == collection.reservationId && issue.revision == Revision(1) &&
            issue.binding == terminal.binding && issue.purpose == ValueIssuePurpose.RETAIL && issue.kitchen == null &&
            issue.allocations.map { it.allocation }.associateBy { it.allocationId } == collection.stockRequirements.allocations.associateBy { it.allocationId } &&
            issue.allocations.all { it.restoredQuantity.scaled == 0L })
        val allocations = collection.stockRequirements.allocations
        compositeCheck(stock.movements.size == allocations.size && stock.newSourceIds == setOf(request.stockIssueId))
        val movements = stock.movements.associateBy { it.sourceAllocationId }
        compositeCheck(movements.size == allocations.size && allocations.all { allocation -> movements[allocation.allocationId]?.let {
            it.sourceId == issue.issueId && it.position == allocation.position && it.quantityDelta == -allocation.quantity && it.reason == StockMovementReason.ISSUE
        } == true })
        val pools = issue.allocations.groupBy { it.allocation.position.pool }
        compositeCheck(stock.valuations.size == pools.size && stock.valuations.map { it.pool }.toSet() == pools.keys)
        for (valuation in stock.valuations) {
            val fact = valuation.fact as? ValuationFact.Issue ?: error("sales.composite.value_issue")
            val expected = pools.getValue(valuation.pool)
            compositeCheck(fact.factId == issue.issueId && fact.purpose == ValueIssuePurpose.RETAIL && fact.slices.size == expected.size &&
                fact.slices.associate { it.allocationId to (it.quantity to it.originalValue) } ==
                expected.associate { it.allocation.allocationId to (it.allocation.quantity to it.originalValue) } &&
                valuation.quantityDelta == -expected.map { it.allocation.quantity }.reduce { first, next -> first + next } &&
                valuation.originalValueDelta == -expected.map { it.originalValue }.reduce { first, next -> first + next } &&
                valuation.linkedAdjustment?.changedSource == null)
        }
        return issue
    }

    private fun safety(collection: CollectionLock, revision: Revision, held: Boolean): StockReservation {
        compositeCheck(stock.effectKinds.isNotEmpty() && stock.effectKinds.all { it == InventoryPostedEffectKind.SAFETY || it == InventoryPostedEffectKind.RESERVATION } &&
            stock.effectKinds.last() == InventoryPostedEffectKind.RESERVATION)
        requireExpiryOnly()
        val history = reservationHistory(collection, revision)
        val terminal = history.last()
        compositeCheck(terminal.status == (if (held) ReservationStatus.HELD else ReservationStatus.RESERVED) &&
            (!held || terminal.holds.isNotEmpty()) && (held || history.size == 1 && terminal.holds.isEmpty()))
        return terminal
    }

    private fun reservationHistory(collection: CollectionLock, revision: Revision): List<StockReservation> {
        val history = stock.reservations.filter { it.reservationId == collection.reservationId }
        compositeCheck(history.isNotEmpty() && history.first().revision == revision.next())
        history.forEach { reservationLink(it, collection) }
        history.zipWithNext().forEach { (before, after) -> compositeCheck(after.revision == before.revision.next() &&
            before.binding == after.binding && before.allocations == after.allocations && before.expiresAtEpochMillis == after.expiresAtEpochMillis &&
            after.holds.containsAll(before.holds)) }
        compositeCheck(stock.reservations.last().reservationId == collection.reservationId)
        for (collateral in stock.reservations.filter { it.reservationId != collection.reservationId }) {
            compositeCheck(collateral.status == ReservationStatus.HELD && collateral.holds.isNotEmpty())
        }
        return history
    }

    private fun requireExpiryOnly() {
        compositeCheck(stock.safety.all { (before, after) -> before.safety == StockSafety.CLEAR && after.safety == StockSafety.EXPIRED &&
            before.expiresAtEpochMillis?.let { it <= evidence.recordedAt.value.toEpochMilliseconds() } == true &&
            before.key == after.key && before.onHand == after.onHand && before.expiresAtEpochMillis == after.expiresAtEpochMillis })
    }

    private fun requireHeld(collection: CollectionLock, reservation: StockReservation) = requireResult(
        LocalSalesResultV1.StockResolutionRequired(collection.id, reservation.reservationId, reservation.revision, stockItems))

    private fun requireItems(requested: List<LocalSalesStockRevisionV1>, collection: CollectionLock) {
        compositeCheck(requested.map { it.skuId }.toSet() == collection.stockRequirements.allocations.map { it.position.pool.skuId }.toSet())
        compositeCheck(requested.all { item -> stockItems.singleOrNull { it.skuId == item.skuId }?.storageRevision == item.storageRevision.next() })
    }

    private fun recordingSlot(request: LocalCollectionRequestV1.StartAttempt, collection: CollectionLock): LocalRecordingSlot {
        val id = DomainId(request.recordingOperationId.value)
        val stored = afterRow(LocalSalesStoreProfile.RECORDING_SLOT_KIND, id, Revision(0))
        val slot = localSalesDecode(stored.payload, LocalRecordingSlot.serializer(), 4_096)
        compositeCheck(slot.scope.value() == evidence.scope && slot.id == id && slot.revision == Revision(0) && slot.consumedAt == null &&
            slot.actorId == evidence.originalActorId && slot.reservationOperationId == evidence.operationId && slot.intentOperationId == evidence.operationId &&
            slot.recordingOperationId == request.recordingOperationId && slot.attemptId == request.attemptId && slot.documentId == collection.id &&
            slot.cashSessionId == collection.cashSessionId && slot.kind == SalesRecordingKind.PAYMENT_ATTESTATION &&
            slot.budgetProfile == SalesRecordingBudget.PROFILE && slot.sourceRevision.value > 0 &&
            slot.platformReservationProfile.isNotBlank() && slot.platformReservationProfile.length <= 96 &&
            slot.recordingOperationId != slot.intentOperationId)
        PayloadDigest(slot.sourceDigest)
        AggregateKey(slot.sourceKind, slot.sourceId)
        val fence = evidence.aggregateWrites.singleOrNull { it.key == ReservedOperations.key(request.recordingOperationId) }
            ?: error("sales.composite.operation_fence")
        used += fence.key
        val expected = ReservedOperations.create(FutureOperationReservation(request.recordingOperationId, evidence.originalActorId, evidence.command,
            salesRecordingTargetBinding(SalesRecordingKind.PAYMENT_ATTESTATION, collection.id, collection.cashSessionId, request.attemptId, evidence.operationId)))
        compositeCheck(fence.storageGeneration == Revision(1) && fence.schema == expected.schema && fence.payload == expected.payload)
        return slot
    }

    private fun recordingSlotV2(request: LocalCollectionRequestV2.StartAttempt, collection: CollectionLock,
        cash: CashSession, attempt: PaymentAttempt): LocalPhysicalRecordingSlotV2 {
        val id = DomainId(request.recordingOperationId.value)
        val stored = row(LocalSalesStoreProfile.PHYSICAL_SLOT_KIND, id)
        used += stored.key
        compositeCheck(stored.storageGeneration == Revision(1) && stored.schema == LocalSalesStoreProfile.PHYSICAL_SLOT_SCHEMA)
        val slot = localSalesDecode(stored.payload, LocalPhysicalRecordingSlotV2.serializer(), 16_384)
        compositeCheck(slot.scope.value() == evidence.scope && slot.id == id && slot.revision == Revision(0) &&
            slot.recordingOperationId == request.recordingOperationId && slot.reservationOperationId == evidence.operationId &&
            slot.intentOperationId == evidence.operationId && slot.actorId == evidence.originalActorId &&
            slot.attemptId == request.attemptId && slot.documentId == collection.id && slot.cashSessionId == cash.id &&
            slot.kind == SalesRecordingKind.PAYMENT_ATTESTATION && slot.amount == request.amount && slot.tender == tenderView(attempt.tender) &&
            !slot.resolving && slot.originalSaleId == null && slot.originalClaimId == null && slot.originalPaymentId == null &&
            slot.clientAllocationId == request.clientAllocationId && slot.budgetProfile == SalesPhysicalRecordingBudgetV2.PROFILE &&
            slot.recordingOperationId != slot.intentOperationId && slot.authorityResources.size in 1..48 &&
            slot.authorityResources.distinct().size == slot.authorityResources.size &&
            slot.authorityResources.containsAll(setOf(slot.actorId, slot.id, slot.masterAllocationId, slot.clientAllocationId,
                slot.clientDeviceId, slot.clientSpoolId, slot.masterSource.id, slot.clientSource.id).map(::recordingAuthority)) &&
            slot.financialResources.toSet() == setOf(orderResource(collection.id), orderResource(collection.orderId), orderResource(collection.collectorId),
                cashResource(cash.id), cashResource(cash.drawerId), cashResource(cash.custodianId)) + priceSourcesResources(collection.priceSourceReference) &&
            listOf(slot.masterPlatformProfile, slot.clientPlatformProfile).all { it.isNotBlank() && it.length <= 96 })
        listOf(slot.masterSource, slot.clientSource).forEach {
            compositeCheck(it.revision.value > 0L)
            PayloadDigest(it.digest)
            AggregateKey(it.kind, it.id)
        }
        val fence = evidence.aggregateWrites.singleOrNull { it.key == ReservedOperations.key(request.recordingOperationId) }
            ?: error("sales.composite.operation_fence")
        used += fence.key
        val expected = ReservedOperations.create(FutureOperationReservation(request.recordingOperationId, evidence.originalActorId,
            LocalSalesStoreProfile.COLLECTION_COMMAND_V2, slot.targetBinding()))
        compositeCheck(fence.storageGeneration == Revision(1) && fence.schema == expected.schema && fence.payload == expected.payload)
        return slot
    }

    private fun eligible(collection: CollectionLock, order: OwnedOrder, cash: CashSession) {
        SalesSnapshotLinks.requireCollection(collection, order, cash)
        compositeCheck(order.ownerId == evidence.originalActorId && collection.collectorId == evidence.originalActorId && cash.custodianId == evidence.originalActorId &&
            cash.status == CashSessionStatus.OPEN && collection.saleId == null && order.paidSaleId == null && order.handoff == null &&
            order.revision == collection.lockedOrderRevision)
    }

    private fun beforeRow(role: String, schema: String): RetainedAggregateWrite {
        val key = collectionBeforeKey(evidence.operationId, role)
        val value = evidence.aggregateWrites.singleOrNull { it.key == key } ?: error("sales.composite.before")
        compositeCheck(value.storageGeneration == Revision(1) && value.schema == schema)
        used += key
        return value
    }

    private fun beforeOrder(id: DomainId, revision: Revision): OwnedOrder = SalesSnapshotCodec.decodeOrder(
        beforeRow("order", LocalSalesStoreProfile.schema(LocalSalesStoreProfile.ORDER_KIND)).text(),
        SalesSnapshotBinding(SalesSnapshotKind.ORDER, evidence.scope, id, revision)).also { order ->
            order.priceSourceReference?.let { reference ->
                requirePriceSources(beforeRow("price_sources", LocalSalesStoreProfile.PRICE_SOURCES_SCHEMA).payload,
                    evidence.scope, id, order.quote, reference, order.stockRequirements.allocations)
            }
        }
    private fun beforeCash(id: DomainId, revision: Revision): CashSession = SalesSnapshotCodec.decodeCashSession(
        beforeRow("cash", CashNonSaleStoreProfile.SESSION_SCHEMA).text(), SalesSnapshotBinding(SalesSnapshotKind.CASH_SESSION, evidence.scope, id, revision))
    private fun beforeCollection(id: DomainId, revision: Revision): CollectionLock = SalesSnapshotCodec.decodeHistoricalCollection(
        beforeRow("collection", LocalSalesStoreProfile.schema(LocalSalesStoreProfile.COLLECTION_KIND)).text(),
        SalesSnapshotBinding(SalesSnapshotKind.COLLECTION, evidence.scope, id, revision))

    private fun afterRow(kind: String, id: DomainId, revision: Revision): RetainedAggregateWrite {
        val value = row(kind, id)
        val schema = if (kind == CashNonSaleStoreProfile.SESSION_KIND) CashNonSaleStoreProfile.SESSION_SCHEMA else LocalSalesStoreProfile.schema(kind)
        compositeCheck(value.storageGeneration == revision.next() && value.schema == schema)
        used += value.key
        return value
    }
    private fun row(kind: String, id: DomainId): RetainedAggregateWrite =
        evidence.aggregateWrites.singleOrNull { it.key == AggregateKey(kind, id) } ?: error("sales.composite.after")
    private fun afterOrder(id: DomainId, revision: Revision): OwnedOrder = SalesSnapshotCodec.decodeOrder(afterRow(LocalSalesStoreProfile.ORDER_KIND, id, revision).text(),
        SalesSnapshotBinding(SalesSnapshotKind.ORDER, evidence.scope, id, revision))
    private fun afterCash(id: DomainId, revision: Revision): CashSession = SalesSnapshotCodec.decodeCashSession(afterRow(CashNonSaleStoreProfile.SESSION_KIND, id, revision).text(),
        SalesSnapshotBinding(SalesSnapshotKind.CASH_SESSION, evidence.scope, id, revision))
    private fun afterCollection(id: DomainId, revision: Revision): CollectionLock = SalesSnapshotCodec.decodeHistoricalCollection(
        afterRow(LocalSalesStoreProfile.COLLECTION_KIND, id, revision).text(), SalesSnapshotBinding(SalesSnapshotKind.COLLECTION, evidence.scope, id, revision))

    private fun requireResult(expected: LocalSalesResultV1) { compositeCheck(LocalSalesResultCodec.encode(expected) == evidence.result) }
    private fun requireClaims(expected: List<ClaimChange>) {
        val actual = evidence.claimChanges.map { Triple(it.action, it.key, it.ownerId) }
        val claims = expected.map { Triple(if (it is ClaimChange.Acquire) RetainedClaimAction.ACQUIRE else RetainedClaimAction.RELEASE, it.key, it.ownerId) }
        compositeCheck(actual.size == claims.size && actual.toSet() == claims.toSet())
    }
    private fun finish(permittedStockKinds: Set<String>) {
        compositeCheck(afterStockKinds.all { it in permittedStockKinds } &&
            evidence.aggregateWrites.filter { it.key.kind !in LocalStockStoreProfile.inventoryKinds }.map { it.key }.toSet() == used &&
            evidence.aggregateWrites.size == used.size + evidence.aggregateWrites.count { it.key.kind in LocalStockStoreProfile.inventoryKinds })
    }
}

private fun reservationLink(reservation: StockReservation, collection: CollectionLock) {
    compositeCheck(reservation.reservationId == collection.reservationId && reservation.binding.orderId == collection.orderId &&
        reservation.binding.collectionId == collection.id && reservation.binding.quoteRevision == collection.quote.revision &&
        reservation.binding.originalActorId == collection.collectorId && reservation.binding.exposure != CollectionExposure.UNCOLLECTED &&
        reservation.allocations.associateBy { it.allocationId } == collection.stockRequirements.allocations.associateBy { it.allocationId })
}

private fun sameOrderCore(before: OwnedOrder, after: OwnedOrder) {
    compositeCheck(before.id == after.id && before.scope == after.scope && before.originalCreatorId == after.originalCreatorId &&
        before.ownerId == after.ownerId && sameQuote(before.quote, after.quote) && before.priceSourceReference == after.priceSourceReference &&
        before.returnPolicySourceReference == after.returnPolicySourceReference && before.stockRequirements.allocations == after.stockRequirements.allocations)
}
internal fun sameCollectionCore(before: CollectionLock, after: CollectionLock) {
    // V3 also compares normalized collections here. Preserve the exact representation
    // and ordered source lists; normalized demand is never a legacy allocation proof.
    val sameStock = when {
        before.stockRequirements.normalized != after.stockRequirements.normalized -> false
        before.stockRequirements.normalized -> before.stockRequirements.normalizedLines == after.stockRequirements.normalizedLines
        else -> before.stockRequirements.allocations == after.stockRequirements.allocations
    }
    compositeCheck(before.id == after.id && before.scope == after.scope && before.orderId == after.orderId &&
        before.lockedOrderRevision == after.lockedOrderRevision && before.originalCreatorId == after.originalCreatorId && before.collectorId == after.collectorId &&
        before.cashSessionId == after.cashSessionId && before.reservationId == after.reservationId && sameQuote(before.quote, after.quote) &&
        before.priceSourceReference == after.priceSourceReference && before.returnPolicySourceReference == after.returnPolicySourceReference &&
        sameStock)
}
private fun sameQuote(first: com.ukkera.pos.pricing.PricingSnapshot, second: com.ukkera.pos.pricing.PricingSnapshot): Boolean =
    StoredPricingSnapshotCodec.encode(first) == StoredPricingSnapshotCodec.encode(second)
internal fun sameAttempt(first: PaymentAttempt, second: PaymentAttempt): Boolean = first.id == second.id &&
    first.originalOperationId == second.originalOperationId && first.originalActorId == second.originalActorId && first.originalCashSessionId == second.originalCashSessionId &&
    first.payerReference == second.payerReference && first.tender == second.tender && first.amount == second.amount && first.status == second.status &&
    first.latestObservation == second.latestObservation && first.payment == second.payment

internal fun cashIntentOnly(before: CashSession, after: CashSession, attemptId: DomainId) {
    compositeCheck(after.id == before.id && after.scope == before.scope && after.drawerId == before.drawerId && after.custodianId == before.custodianId &&
        after.businessDate == before.businessDate && after.opening == before.opening && after.openingSourceId == before.openingSourceId &&
        after.totals == before.totals && after.expectedCash == before.expectedCash && after.reservedCashOut == before.reservedCashOut &&
        after.cashLedgerRevision == before.cashLedgerRevision && after.status == CashSessionStatus.OPEN && after.pendingAttemptIds == before.pendingAttemptIds + attemptId &&
        after.pendingTransferIds == before.pendingTransferIds && after.closingCut == before.closingCut && after.closeReport == before.closeReport && after.review == before.review)
}

/** Verify the ORIGINAL deterministic component/tender matrix, not merely its row/column totals. */
private fun originalTenderMatrix(sale: PaidSale) {
    val remaining = sale.payments.sortedBy { it.attemptId.value }.associate { it.attemptId.value to it.amount }.toMutableMap()
    val ordered = sale.quote.components.sortedBy { it.key }
    compositeCheck(sale.originalTenderAllocations.map { it.componentKey } == ordered.map { it.key })
    for ((index, component) in ordered.withIndex()) {
        val allocated = allocateByLargestRemainder(component.posted, remaining.map { AllocationWeight(it.key, it.value.minor) })
        compositeCheck(sale.originalTenderAllocations[index].shares.map { it.paymentId.value to it.amount } == allocated.map { it.key to it.amount })
        for (share in allocated) remaining[share.key] = remaining.getValue(share.key) - share.amount
    }
    compositeCheck(remaining.values.all { it.minor == 0L })
}

private fun RetainedAggregateWrite.text(): String = payload.copyBytes().decodeToString(throwOnInvalidSequence = true)
private fun compositeCheck(condition: Boolean) { check(condition) { "sales.composite.invalid" } }
