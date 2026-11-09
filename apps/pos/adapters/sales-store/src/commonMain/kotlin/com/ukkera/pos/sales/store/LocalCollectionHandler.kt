package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateChange
import com.ukkera.pos.database.ClaimChange
import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.MasterCommandHandler
import com.ukkera.pos.database.PreparedMutation
import com.ukkera.pos.database.ProposedOutcome
import com.ukkera.pos.database.ReservedOperations
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.inventory.CollectionBinding
import com.ukkera.pos.inventory.CollectionExposure
import com.ukkera.pos.inventory.InventoryPlanner
import com.ukkera.pos.inventory.InventoryRejection
import com.ukkera.pos.inventory.ReservationIssueResult
import com.ukkera.pos.inventory.ReservationSafetyResult
import com.ukkera.pos.inventory.ReservationStatus
import com.ukkera.pos.inventory.store.LocalStockContribution
import com.ukkera.pos.inventory.store.LocalStockFailure
import com.ukkera.pos.inventory.store.LocalStockRejected
import com.ukkera.pos.inventory.store.LocalStockReadCut
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.inventory.store.LocalStockStoreProfile
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.AttemptStatus
import com.ukkera.pos.sales.CashMovement
import com.ukkera.pos.sales.CashSessionChanged
import com.ukkera.pos.sales.CashSessionStatus
import com.ukkera.pos.sales.CollectionChanged
import com.ukkera.pos.sales.CollectionObservationRecorded
import com.ukkera.pos.sales.CollectionOutcome
import com.ukkera.pos.sales.Collections
import com.ukkera.pos.sales.ExternalTenderEvidence
import com.ukkera.pos.sales.InventoryCommitKind
import com.ukkera.pos.sales.OrderChanged
import com.ukkera.pos.sales.ReceiptEntitlementChanged
import com.ukkera.pos.sales.SalePosted
import com.ukkera.pos.sales.SalesAggregate
import com.ukkera.pos.sales.SalesRule
import com.ukkera.pos.sales.SalesSnapshotCodec
import com.ukkera.pos.sales.SalesSnapshotLinks
import com.ukkera.pos.sales.SalesUniqueCheck
import com.ukkera.pos.sales.SalesUniqueKind
import com.ukkera.pos.sales.SalesVersionCheck
import com.ukkera.pos.sales.Tender
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.Money

/**
 * Real order/reservation/money/paid-document composition. Only the exact live inventory plan
 * is translated. A Held result preserves the financial obligation, not a successful sale.
 * External collection remains a cashier attestation, never a provider authorization.
 */
public class LocalCollectionHandler(
    private val stock: LocalStockRepository,
    private val tenderSource: TransactionSalesTenderSource,
    private val recordingCapacity: TransactionSalesRecordingCapacity,
) : MasterCommandHandler {
    override public val key: CommandKey get() = LocalSalesStoreProfile.COLLECTION_COMMAND
    override public val writableKinds: Set<String> = (LocalStockStoreProfile.inventoryKinds - LocalStockStoreProfile.SERIAL_ORIGIN) + setOf(
        LocalSalesStoreProfile.ORDER_KIND, LocalSalesStoreProfile.COLLECTION_KIND, LocalSalesStoreProfile.WORK_KIND,
        CashNonSaleStoreProfile.SESSION_KIND, LocalSalesStoreProfile.MOVEMENT_KIND, LocalSalesStoreProfile.SALE_KIND,
        LocalSalesStoreProfile.ENTITLEMENT_KIND, LocalSalesStoreProfile.RECORDING_SLOT_KIND,
        LocalSalesStoreProfile.COLLECTION_BEFORE_KIND, ReservedOperations.KIND)
    override public val claimNamespaces: Set<String> = LocalStockStoreProfile.inventoryClaimNamespaces + setOf(
        LocalSalesStoreProfile.UNIQUE_NAMESPACE, CashNonSaleStoreProfile.CASH_SOURCE_NAMESPACE,
        LocalSalesStoreProfile.RECORDING_OPERATION_NAMESPACE, LocalSalesStoreProfile.RECORDING_ALLOCATION_NAMESPACE,
        LocalSalesStoreProfile.RECORDING_ATTEMPT_NAMESPACE)
    override public fun normalize(input: StoredBytes): StoredBytes = LocalCollectionCommandCodec.normalize(input)
    override public fun reservedOperationBinding(normalized: StoredBytes): PayloadDigest? =
        (LocalCollectionCommandCodec.decode(normalized) as? LocalCollectionRequestV1.RecordOutcome)?.let {
            salesRecordingTargetBinding(SalesRecordingKind.PAYMENT_ATTESTATION, it.collectionId, it.sessionId, it.attemptId, it.intentOperationId)
        }

    override public suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        check(context.scope == view.scope && context.key == key &&
            semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest) { "sales.context.invalid" }
        return when (val request = LocalCollectionCommandCodec.decode(normalized)) {
            is LocalCollectionRequestV1.Begin -> begin(request, view, context)
            is LocalCollectionRequestV1.StartAttempt -> start(request, view, context)
            is LocalCollectionRequestV1.ReserveRecording -> reserveRecording(request, view, context)
            is LocalCollectionRequestV1.RecordOutcome -> observeOutcome(request, view, context)
            is LocalCollectionRequestV1.Finalize -> finalize(request, view, context)
        }
    }

    private suspend fun begin(request: LocalCollectionRequestV1.Begin, view: CommandReadView, context: CommandContext): PreparedMutation {
        val order = readOrder(view, request.orderId)
        val cash = LocalSalesStorage.cash(view, request.sessionId)
        val skuIds = order.order.value.stockRequirements.allocations.map { it.position.pool.skuId }.toSet()
        val inventory = observeBoundedStock { stock.observe(view, skuIds.toList()) }
            ?: return stockCapacityPrepared(context, SecurityAction.COLLECTION_BEGIN, request.orderId, request.expectedOrderRevision,
                order.order.value.ownerId, "collection.begin", order.order.value.quote.payable,
                cashResources(cash) + orderResources(order) + orderResource(request.collectionId))
        val unique = LocalSalesStoreProfile.unique("collection", request.collectionId)
        val exists = view.claim(unique) != null || view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.COLLECTION_KIND, request.collectionId)) != null ||
            view.claim(LocalStockStoreProfile.reservationClaim(request.reservationId)) != null ||
            view.aggregate(LocalStockStoreProfile.reservationKey(request.reservationId)) != null
        val allocationsUsed = order.order.value.stockRequirements.allocations.any { view.claim(LocalStockStoreProfile.allocationClaim(it.allocationId)) != null }
        return LocalSalesPrepared(context, SecurityAction.COLLECTION_BEGIN, request.orderId, request.expectedOrderRevision,
            order.order.value.ownerId, "collection.begin", order.order.value.quote.payable,
            cashResources(cash) + stock.resources(inventory) + orderResources(order) + orderResource(request.collectionId),
            setOf(RecoveryResourceKind.STOCK)) { command, now -> stockProposal {
            if (exists || allocationsUsed) return@stockProposal salesRejected(SalesRule.DUPLICATE_SOURCE_ALLOCATION)
            if (!requiredStockVersions(inventory, request.items, skuIds)) return@stockProposal salesRejected(SalesRule.REVISION_CONFLICT)
            if (order.order.value.ownerId != cash.session.value.custodianId) return@stockProposal salesRejected(SalesRule.OWNER_REQUIRED)
            val reservation = InventoryPlanner.reserve(inventory.snapshot, inventory.snapshot.revision, context.operationId,
                request.reservationId, CollectionBinding(request.orderId, order.order.value.quote.revision, request.collectionId,
                    context.originalActorId, CollectionExposure.UNKNOWN), order.order.value.stockRequirements.allocations,
                now.value.toEpochMilliseconds(), request.reservationExpiresAt?.value?.toEpochMilliseconds())
            val transition = Collections.begin(command, order.order.value, request.expectedOrderRevision, cash.session.value,
                request.expectedSessionRevision, request.collectionId, reservation)
            val after = transition.value
            check(transition.command == command && transition.versions == listOf(orderVersion(order), cashVersion(cash)) &&
                transition.unique == listOf(SalesUniqueCheck(SalesUniqueKind.COLLECTION, request.collectionId)) &&
                transition.effects == listOf(OrderChanged(after.order), CollectionChanged(after.collection))) { "sales.transition.invalid" }
            val dependency = transition.inventoryDependencies.single()
            check(dependency.kind == InventoryCommitKind.RESERVE && dependency.plan === reservation.plan &&
                dependency.reservationId == request.reservationId && dependency.issueId == null) { "sales.stock.plan_identity" }
            SalesSnapshotLinks.requireCollection(after.collection, after.order, cash.session.value)
            val contribution = stock.project(inventory, context, reservation.plan, now)
            compose(context, contribution, LocalSalesResultV1.Applied(LocalSalesAction.COLLECTION_LOCKED, after.collection.id,
                listOf(LocalSalesRevision(LocalSalesStoreProfile.ORDER_KIND, after.order.id, after.order.revision),
                    LocalSalesRevision(LocalSalesStoreProfile.COLLECTION_KIND, after.collection.id, after.collection.revision)),
                after.collection.quote.payable, contribution.versions()),
                listOf(orderWrite(after.order, order.order.stored), collectionWrite(after.collection, null)) +
                    collectionBefore(context, listOf("order" to order.order.stored, "cash" to cash.session.stored) + priceSourcesBefore(order)),
                listOf(ClaimChange.Acquire(unique, request.orderId)))
        } }
    }

    @Suppress("UNUSED_PARAMETER")
    internal suspend fun startV2(request: LocalCollectionRequestV2.StartAttempt, view: CommandReadView, context: CommandContext,
        capacity: TransactionSalesPhysicalRecordingCapacityV2): PreparedMutation = start(request.intentV1(), view, context)

    /** Fresh legacy starts are retired; positive replay is recovered by the kernel before prepare. */
    private suspend fun start(request: LocalCollectionRequestV1.StartAttempt, view: CommandReadView, context: CommandContext): PreparedMutation {
        val cut = readCollection(view, request.collectionId)
        return legacyJournalRequired(context, SecurityAction.COLLECTION_ATTEST, request.collectionId,
            request.expectedCollectionRevision, cut.collection.value.collectorId, request.amount, collectionResources(cut))
    }

    @Suppress("UNUSED_PARAMETER")
    internal suspend fun reserveV2(request: LocalCollectionRequestV2.ReserveRecording, view: CommandReadView, context: CommandContext,
        capacity: TransactionSalesPhysicalRecordingCapacityV2): PreparedMutation = reserveRecording(
        LocalCollectionRequestV1.ReserveRecording(request.collectionId, request.expectedCollectionRevision, request.sessionId,
            request.expectedSessionRevision, request.attemptId, request.recordingOperationId), view, context)

    /** Do not give a legacy UNKNOWN a fresh allocation or relabel its bytes as journal-first. */
    private suspend fun reserveRecording(request: LocalCollectionRequestV1.ReserveRecording, view: CommandReadView, context: CommandContext): PreparedMutation {
        val cut = readCollection(view, request.collectionId)
        check(cut.cash.session.value.id == request.sessionId) { "sales.collection.session" }
        val attempt = cut.collection.value.attempts.singleOrNull { it.id == request.attemptId } ?: error("sales.payment.attempt")
        return legacyJournalRequired(context,
            if (attempt.status == AttemptStatus.OUTCOME_UNKNOWN) SecurityAction.COLLECTION_RESOLVE else SecurityAction.COLLECTION_ATTEST,
            request.collectionId, request.expectedCollectionRevision, attempt.originalActorId, attempt.amount, collectionResources(cut))
    }

    private suspend fun observeOutcome(request: LocalCollectionRequestV1.RecordOutcome, view: CommandReadView, context: CommandContext): PreparedMutation {
        val cut = readCollection(view, request.collectionId) // Deliberately no inventory read or stock recovery barrier.
        check(cut.cash.session.value.id == request.sessionId) { "sales.collection.session" }
        val attempt = cut.collection.value.attempts.singleOrNull { it.id == request.attemptId } ?: error("sales.payment.attempt")
        check(attempt.originalOperationId == request.intentOperationId) { "sales.payment.intent" }
        requireLocalSalesSource(originalPaymentRecordingProtocol(view, request.collectionId, attempt) == LocalSalesRecordingProtocol.LEGACY_V1)
        val slot = readRecordingSlot(view, context, SalesRecordingKind.PAYMENT_ATTESTATION, attempt.originalOperationId,
            attempt.id, request.collectionId, request.sessionId)
        requireLegacyRecordingProducer(view, slot, attempt.amount)
        val paymentClaim = LocalSalesStoreProfile.unique("payment_record", request.attemptId)
        val cashClaim = CashNonSaleStoreProfile.cashSourceClaim(request.attemptId)
        check(view.claim(paymentClaim) == null && view.claim(cashClaim) == null &&
            view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.MOVEMENT_KIND, request.attemptId)) == null) { "sales.payment.source" }
        val action = if (attempt.status == AttemptStatus.OUTCOME_UNKNOWN) SecurityAction.COLLECTION_RESOLVE else SecurityAction.COLLECTION_ATTEST
        return LocalSalesPrepared(context, action, request.collectionId, request.expectedCollectionRevision, attempt.originalActorId,
            "collection.observe", attempt.amount, collectionResources(cut)) { command, now ->
            val outcome = request.outcome.domain(context.originalActorId)
            val transition = Collections.recordOutcome(command, cut.collection.value, request.expectedCollectionRevision, cut.cash.session.value,
                request.expectedSessionRevision, request.attemptId, outcome)
            val after = transition.value
            val payment = after.collection.attempts.single { it.id == request.attemptId }.payment
            val movement = transition.effects.filterIsInstance<CashMovement>().singleOrNull()
            check(transition.command == command && transition.inventoryDependencies.isEmpty() && transition.versions == collectionCashVersions(cut) &&
                transition.unique == listOfNotNull(payment?.let { SalesUniqueCheck(SalesUniqueKind.PAYMENT_RECORD, it.attemptId) },
                    movement?.let { SalesUniqueCheck(SalesUniqueKind.CASH_SOURCE, it.sourceId) }) &&
                transition.effects == listOf(CollectionChanged(after.collection)) +
                    listOfNotNull(if (after.session === cut.cash.session.value) null else CashSessionChanged(after.session)) +
                    listOf(CollectionObservationRecorded(request.attemptId, outcome)) + listOfNotNull(payment, movement)) { "sales.transition.invalid" }
            SalesSnapshotLinks.requireCollection(after.collection, cut.order.order.value, after.session)
            val changes = mutableListOf<AggregateChange>(collectionWrite(after.collection, cut.collection.stored), consumeRecordingSlot(slot, now))
            val revisions = mutableListOf(LocalSalesRevision(LocalSalesStoreProfile.COLLECTION_KIND, request.collectionId, after.collection.revision),
                LocalSalesRevision(LocalSalesStoreProfile.RECORDING_SLOT_KIND, slot.value.id, slot.value.revision.next()))
            if (after.session !== cut.cash.session.value) {
                changes += cashWrite(after.session, cut.cash.session.stored)
                revisions += LocalSalesRevision(CashNonSaleStoreProfile.SESSION_KIND, after.session.id, after.session.revision)
            }
            if (movement != null) changes += movementWrite(context, movement, now)
            observedApplied(when (request.outcome) {
                is LocalPaymentOutcomeV1.Unknown -> LocalSalesAction.PAYMENT_UNKNOWN
                is LocalPaymentOutcomeV1.NotPaid -> LocalSalesAction.PAYMENT_NOT_PAID
                is LocalPaymentOutcomeV1.CashReceived, is LocalPaymentOutcomeV1.ExternalAttested -> LocalSalesAction.PAYMENT_RECORDED
            }, request.collectionId, revisions, changes, listOf(releaseRecordingSlot(slot)) +
                listOfNotNull(payment?.let { ClaimChange.Acquire(paymentClaim, request.collectionId) },
                    movement?.let { ClaimChange.Acquire(cashClaim, request.sessionId) }), attempt.amount)
        }
    }

    private suspend fun finalize(request: LocalCollectionRequestV1.Finalize, view: CommandReadView, context: CommandContext): PreparedMutation {
        val cut = readCollection(view, request.collectionId)
        val collection = cut.collection.value
        val order = cut.order.order.value
        val skuIds = collection.stockRequirements.allocations.map { it.position.pool.skuId }.toSet()
        val action = if (collection.quote.payable.minor == 0L) SecurityAction.COLLECTION_FINALIZE_ZERO else SecurityAction.COLLECTION_FINALIZE
        val inventory = observeBoundedStock { stock.observe(view, skuIds.toList(), listOf(collection.reservationId)) }
            ?: return stockCapacityPrepared(context, action, order.id, request.expectedOrderRevision,
                order.ownerId, "collection.finalize", collection.quote.payable, collectionResources(cut))
        val reservation = requireCollectionReservation(cut, inventory)
        val unique = listOf(LocalSalesStoreProfile.unique("sale", request.saleId), LocalSalesStoreProfile.unique("sale_order", order.id),
            LocalSalesStoreProfile.unique("return_entitlement", request.saleId))
        val exists = unique.any { view.claim(it) != null } ||
            view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.SALE_KIND, request.saleId)) != null ||
            view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.ENTITLEMENT_KIND, request.saleId)) != null ||
            view.claim(LocalStockStoreProfile.sourceClaim(request.stockIssueId)) != null ||
            view.aggregate(LocalStockStoreProfile.issueKey(request.stockIssueId)) != null
        return LocalSalesPrepared(context, action, order.id, request.expectedOrderRevision, order.ownerId, "collection.finalize",
            collection.quote.payable, collectionResources(cut) + stock.resources(inventory) + entitlementResource(request.saleId),
            setOf(RecoveryResourceKind.STOCK)) { command, now -> stockProposal {
            // Never commit collateral Held writes for an ineligible owner, stale cut or unresolved money.
            val admission = collectionAttemptAdmission(cut, context, request.expectedCollectionRevision, request.expectedSessionRevision)
            if (admission != null) return@stockProposal salesRejected(admission)
            if (order.revision != request.expectedOrderRevision) return@stockProposal salesRejected(SalesRule.REVISION_CONFLICT)
            if (collection.unresolved) return@stockProposal salesRejected(SalesRule.PAYMENT_UNRESOLVED)
            if (collection.collected != collection.quote.payable) return@stockProposal salesRejected(SalesRule.INSUFFICIENT_COVERAGE)
            if (exists) return@stockProposal salesRejected(SalesRule.DUPLICATE_SOURCE_ALLOCATION)
            if (!requiredStockVersions(inventory, request.items, skuIds) || reservation.revision != request.expectedReservationRevision) {
                return@stockProposal salesRejected(SalesRule.REVISION_CONFLICT)
            }
            val issued = InventoryPlanner.issueReservation(inventory.snapshot, inventory.snapshot.revision, context.operationId,
                collection.reservationId, request.expectedReservationRevision, request.stockIssueId, now.value.toEpochMilliseconds())
            val before = collectionBefore(context, listOf("order" to cut.order.order.stored, "collection" to cut.collection.stored,
                "cash" to cut.cash.session.stored, "work" to cut.order.work.stored) + priceSourcesBefore(cut.order))
            when (issued) {
                is ReservationIssueResult.Held -> held(context, cut, stock.project(inventory, context, issued.plan, now), before)
                is ReservationIssueResult.Issued -> {
                    val transition = Collections.finalizePaid(command, order, request.expectedOrderRevision, collection,
                        request.expectedCollectionRevision, request.saleId, issued)
                    val after = transition.value
                    check(transition.command == command && transition.versions == listOf(orderVersion(cut.order), collectionVersion(cut)) &&
                        transition.unique == listOf(SalesUniqueCheck(SalesUniqueKind.SALE, request.saleId), SalesUniqueCheck(SalesUniqueKind.SALE_ORDER, order.id),
                            SalesUniqueCheck(SalesUniqueKind.RETURN_ENTITLEMENT, request.saleId)) &&
                        transition.effects == listOf(OrderChanged(after.order), CollectionChanged(after.collection), SalePosted(after.sale),
                            ReceiptEntitlementChanged(after.entitlement))) { "sales.transition.invalid" }
                    val dependency = transition.inventoryDependencies.single()
                    check(dependency.kind == InventoryCommitKind.RETAIL_ISSUE && dependency.plan === issued.plan &&
                        dependency.reservationId == collection.reservationId && dependency.issueId == request.stockIssueId) { "sales.stock.plan_identity" }
                    SalesSnapshotLinks.requireCollection(after.collection, after.order, cut.cash.session.value)
                    val (work, workWrite) = changedWork(cut.order.work, orderIds = cut.order.work.value.orderIds - order.id)
                    val contribution = stock.project(inventory, context, issued.plan, now)
                    compose(context, contribution, LocalSalesResultV1.Applied(LocalSalesAction.SALE_FINALIZED, after.sale.id,
                        listOf(LocalSalesRevision(LocalSalesStoreProfile.ORDER_KIND, order.id, after.order.revision),
                            LocalSalesRevision(LocalSalesStoreProfile.COLLECTION_KIND, collection.id, after.collection.revision),
                            LocalSalesRevision(LocalSalesStoreProfile.SALE_KIND, after.sale.id, Revision(0)),
                            LocalSalesRevision(LocalSalesStoreProfile.ENTITLEMENT_KIND, after.sale.id, after.entitlement.revision),
                            LocalSalesRevision(LocalSalesStoreProfile.WORK_KIND, work.id, work.revision)), after.sale.quote.payable, contribution.versions()),
                        listOf(orderWrite(after.order, cut.order.order.stored), collectionWrite(after.collection, cut.collection.stored),
                            domainWrite(LocalSalesStoreProfile.SALE_KIND, after.sale.id, Revision(0), SalesSnapshotCodec.encodePaidSale(after.sale), null),
                            entitlementWrite(after.entitlement, null), workWrite) + before,
                        listOf(ClaimChange.Acquire(unique[0], order.id), ClaimChange.Acquire(unique[1], request.saleId), ClaimChange.Acquire(unique[2], request.saleId)))
                }
            }
        } }
    }

    private fun compose(context: CommandContext, contribution: LocalStockContribution, result: LocalSalesResultV1,
        changes: List<AggregateChange>, claims: List<ClaimChange>): ProposedOutcome.Apply =
        stock.compose(context, contribution, LocalSalesResultCodec.encode(result), changes, claims)

    private fun held(context: CommandContext, cut: LocalCollectionCut, contribution: LocalStockContribution,
        before: List<AggregateChange>): ProposedOutcome.Apply {
        val reservation = contribution.plan.nextSnapshot.reservation(cut.collection.value.reservationId) ?: error("sales.stock.reservation")
        check(reservation.status == ReservationStatus.HELD && reservation.binding.exposure != CollectionExposure.UNCOLLECTED) { "sales.stock.hold" }
        return compose(context, contribution, LocalSalesResultV1.StockResolutionRequired(cut.collection.value.id,
            reservation.reservationId, reservation.revision, contribution.versions()), before, emptyList())
    }
}

internal fun recordingRevisionHeadroom(document: Revision, cash: Revision, cashLedger: Revision): Boolean =
    document.value < Long.MAX_VALUE - 2 && cash.value < Long.MAX_VALUE - 2 && cashLedger.value < Long.MAX_VALUE

private fun collectionVersion(cut: LocalCollectionCut): SalesVersionCheck =
    SalesVersionCheck(SalesAggregate.COLLECTION, cut.collection.value.id, cut.collection.value.revision)
private fun cashVersion(cut: LocalCashCut): SalesVersionCheck = SalesVersionCheck(SalesAggregate.CASH_SESSION, cut.session.value.id, cut.session.value.revision)
internal fun collectionCashVersions(cut: LocalCollectionCut): List<SalesVersionCheck> = listOf(collectionVersion(cut), cashVersion(cut.cash))

internal fun collectionResources(cut: LocalCollectionCut): Set<com.ukkera.pos.recovery.RecoveryResourceKey> = setOf(
    orderResource(cut.collection.value.id), orderResource(cut.order.order.value.id), orderResource(cut.order.work.value.id),
    cashResource(cut.cash.session.value.id), cashResource(cut.cash.drawer.value.id), cashResource(cut.cash.custodian.value.id)) +
    priceSourcesResources(cut.order.order.value.priceSourceReference) + returnPolicyCaptureResources(cut.order.returnPolicyCapture?.value)

internal fun collectionAttemptAdmission(cut: LocalCollectionCut, context: CommandContext, collectionRevision: Revision, sessionRevision: Revision): SalesRule? {
    val collection = cut.collection.value
    val order = cut.order.order.value
    val cash = cut.cash.session.value
    if (collection.revision != collectionRevision || cash.revision != sessionRevision) return SalesRule.REVISION_CONFLICT
    if (order.ownerId != context.originalActorId || collection.collectorId != context.originalActorId || cash.custodianId != context.originalActorId ||
        collection.cashSessionId != cash.id) return SalesRule.OWNER_REQUIRED
    if (collection.saleId != null || order.paidSaleId != null) return SalesRule.IMMUTABLE_RECORD
    if (cash.status != CashSessionStatus.OPEN || order.collectionId != collection.id || order.handoff != null || order.revision != collection.lockedOrderRevision) {
        return SalesRule.PAYABLE_LOCKED
    }
    return null // readCollection already checked complete immutable quote/allocation/scope links.
}

private fun LocalStockContribution.versions(): List<LocalSalesStockRevisionV1> = items.map {
    LocalSalesStockRevisionV1(it.skuId, checkNotNull(it.storageRevision) { "sales.stock.persisted_revision" })
}

private inline fun stockProposal(proposal: () -> ProposedOutcome): ProposedOutcome = try {
    proposal()
} catch (_: InventoryRejection) {
    salesRejected(SalesRule.STOCK_PROOF_MISMATCH)
} catch (rejected: LocalStockRejected) {
    if (rejected.failure == LocalStockFailure.BOUND_EXCEEDED) salesUnavailable(LocalSalesUnavailable.CAPACITY_REQUIRED)
    else throw rejected // Missing provenance/corruption is not a durable negative-money assertion.
}

/** A normal bounded closure refusal is not poisoned storage; no partial stock proposal escapes. */
internal suspend fun observeBoundedStock(read: suspend () -> LocalStockReadCut): LocalStockReadCut? = try {
    read()
} catch (rejected: LocalStockRejected) {
    if (rejected.failure == LocalStockFailure.BOUND_EXCEEDED) null else throw rejected
}

private fun stockCapacityPrepared(context: CommandContext, action: SecurityAction, id: DomainId,
    revision: Revision, ownerId: DomainId, reason: String, amount: Money,
    resources: Set<com.ukkera.pos.recovery.RecoveryResourceKey>): PreparedMutation =
    LocalSalesPrepared(context, action, id, revision, ownerId, reason, amount, resources,
        setOf(RecoveryResourceKind.STOCK)) { _, _ -> salesUnavailable(LocalSalesUnavailable.CAPACITY_REQUIRED) }

private fun LocalPaymentOutcomeV1.domain(actor: DomainId): CollectionOutcome = when (this) {
    is LocalPaymentOutcomeV1.Unknown -> CollectionOutcome.Unknown(evidence.domain(actor))
    is LocalPaymentOutcomeV1.NotPaid -> CollectionOutcome.NotPaid(evidence.domain(actor), reason)
    is LocalPaymentOutcomeV1.CashReceived -> CollectionOutcome.CashReceived(received, change, evidence.domain(actor))
    is LocalPaymentOutcomeV1.ExternalAttested -> CollectionOutcome.ExternalAttested(ExternalTenderEvidence(evidence.domain(actor), terminal, reference))
}
