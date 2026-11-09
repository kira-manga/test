package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.AggregateKeyPage
import com.ukkera.pos.database.ClaimKey
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.MasterPhysicalInteractiveAuthorizer
import com.ukkera.pos.database.PhysicalRecordingStartKind
import com.ukkera.pos.database.PhysicalRetainedStartContext
import com.ukkera.pos.database.PhysicalRetainedStartRequirement
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.database.StockReadEvidenceV1
import com.ukkera.pos.inventory.CollectionExposure
import com.ukkera.pos.inventory.ReservationStatus
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.inventory.store.LocalStockPaymentObservations
import com.ukkera.pos.inventory.store.LocalStockFailure
import com.ukkera.pos.inventory.store.LocalStockRejected
import com.ukkera.pos.inventory.store.LocalStockStoreProfile
import com.ukkera.pos.inventory.store.observePaymentReservation
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.AttemptStatus
import com.ukkera.pos.sales.CashSessionStatus
import com.ukkera.pos.sales.Tender
import com.ukkera.pos.sales.salesAuthorizationRequirement
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.UtcInstant
import kotlin.time.Instant

/**
 * Installed on the SAME v3 handler with its actual stock repository and tender source. This is
 * ONLY a read-only current cut for a genuine retained Start. No Start prepare/evaluate, planner,
 * AuthorizedAction, native IO, proposed effect or reconstructed retained evidence is created.
 * Every nested read uses the real kernel writer view; its trace includes absence and producers.
 */
internal class LocalCollectionInteractiveAuthorization(
    private val stock: LocalStockRepository,
    private val tenderSource: TransactionSalesTenderSource,
    private val tenderPolicies: HistoricalSalesTenderPolicySourceV1 = tenderSource as? HistoricalSalesTenderPolicySourceV1
        ?: UnavailableHistoricalSalesTenderPolicySourceV1,
) : MasterPhysicalInteractiveAuthorizer {
    override val command: CommandKey get() = LocalFinancialJournalProfileV3.COLLECTION_COMMAND
    override val domainProfile: String get() = LocalFinancialJournalProfileV3.PAYMENT_PROFILE

    override suspend fun prepareRetainedStartAuthorization(view: CommandReadView,
        context: PhysicalRetainedStartContext): PhysicalRetainedStartRequirement? {
        return try { prepareCurrent(commerceLoan(view, stock, tenderPolicies = tenderPolicies), context) }
        catch (_: NormalizedPaymentOriginCapacity) { null }
        catch (rejected: LocalStockRejected) {
            if (rejected.failure != LocalStockFailure.BOUND_EXCEEDED) throw rejected
            null
        }
    }

    private suspend fun prepareCurrent(view: CommandReadView,
        context: PhysicalRetainedStartContext): PhysicalRetainedStartRequirement? {
        val retained = readInteractiveRetailStart(view, context, SalesRecordingKind.PAYMENT_ATTESTATION) ?: return null
        val request = LocalCollectionCommandCodecV3.decode(retained.origin.normalized) as? LocalCollectionRequestV3.Start ?: return null
        val target = retained.slot.target
        if (request.target(context.original.operationId) != target) return null
        val cut = readCollection(view, request.collectionId)
        val collection = cut.collection.value
        val order = cut.order.order.value
        val cash = cut.cash.session.value
        val actor = context.original.originalActorId
        // A pending instruction is not reconstructed after an overtaking document/cash write.
        // This conservative fresh lane may hold unrelated same-cash activity before publication;
        // it does not globally invalidate disjoint actors/resources or hide the unresolved Start.
        if (!sameRetailStartPostimage(retained.origin, cut.collection.stored) ||
            !sameRetailStartPostimage(retained.origin, cut.cash.session.stored)) return null
        if (collection.id != target.documentId || cash.id != target.sessionId || collection.cashSessionId != cash.id ||
            collection.collectorId != actor || order.ownerId != actor || cash.custodianId != actor ||
            cash.status != CashSessionStatus.OPEN || collection.saleId != null || order.paidSaleId != null ||
            order.collectionId != collection.id || order.handoff != null || order.revision != collection.lockedOrderRevision) return null
        val attempt = collection.attempts.singleOrNull { it.id == target.attemptId } ?: return null
        if (attempt.status != AttemptStatus.AWAITING_ATTESTATION || attempt.latestObservation != null || attempt.payment != null ||
            attempt.originalActorId != actor || attempt.originalCashSessionId != cash.id ||
            attempt.originalOperationId != context.original.operationId || attempt.amount != request.amount ||
            attempt.amount != retained.slot.domain.amount || attempt.payerReference != request.payerReference ||
            tenderView(attempt.tender) != retained.slot.domain.tender ||
            collection.attempts.filter { it.status.unresolved() }.map { it.id } != listOf(attempt.id) ||
            attempt.id !in cash.pendingAttemptIds || attempt.amount.minor <= 0 ||
            attempt.amount.currency != collection.quote.currency || attempt.amount.minor > collection.remainingPayable.minor) return null
        if (view.claim(LocalSalesStoreProfile.unique("payment_record", attempt.id)) != null ||
            view.claim(CashNonSaleStoreProfile.cashSourceClaim(attempt.id)) != null) return null

        val tenderResources: Set<RecoveryResourceKey> = when (val tender = attempt.tender) {
            Tender.Cash -> {
                if (request.tender != LocalTenderSelectionV1.Cash) return null
                emptySet()
            }
            is Tender.External -> {
                val selected = request.tender as? LocalTenderSelectionV1.External ?: return null
                if (selected.tenderId != tender.tenderId || selected.expectedPolicyRevision != tender.policyRevision) return null
                val traced = RetailTenderRead(view, tender.tenderId)
                val current = try { tenderSource.current(traced, tender.tenderId, tender.policyRevision) }
                    finally { traced.close() }
                if (current == null || !traced.complete || current.scope != view.scope || current.tender != tender ||
                    current.recoveryResources != setOf(recordingAuthority(tender.tenderId))) return null
                current.recoveryResources
            }
        }
        val financialResources = collectionResources(cut) + tenderResources
        if (financialResources != retained.slot.domain.financialResources.toSet()) return null
        if (collection.stockRequirements.normalized) {
            val originalReads = StockReadEvidenceV1.read(retained.origin) ?: return null
            val originalStock = LocalStockPaymentObservations.reservation(originalReads, collection.reservationId)
            val aliases = linkedSetOf<RecoveryResourceKey>()
            // Real selected positions and COMPLETE competing membership, with all actual record
            // aliases. This reader has no planner, proposed stock write or source-accepting hook.
            val inventory = stock.observePaymentReservation(view, collection.reservationId) { resource ->
                aliases += resource
                if (aliases.size > 512) throw LocalStockRejected(LocalStockFailure.BOUND_EXCEEDED)
            }
            requireNormalizedPaymentDemand(collection, inventory)
            if (inventory.header != originalStock.header || inventory.allocations.associateBy { it.allocation.allocationId } !=
                originalStock.allocations.associateBy { it.allocation.allocationId } || !inventory.safeAt(context.observedAt)) return null
            // Start did NOT write a reservation postimage. Compare its genuine literal retained
            // observation instead; actual position metadata may only shorten the original bound.
            val deadline = earlierRetailDeadline(inventory.deadline, retained.result.mustCommitBefore)
            if (deadline != null && context.observedAt >= deadline) return null
            val resources = financialResources + aliases + retailJournalAuthorityResources(retained.requirement)
            if (resources.size > 512) return null
            return PhysicalRetainedStartRequirement(
                salesAuthorizationRequirement(SecurityAction.COLLECTION_ATTEST, view.scope, collection.id, collection.revision,
                    context.original.operationId, context.original.semanticDigest, "collection.instruction", actor, attempt.amount),
                context.requirement, resources, setOf(RecoveryResourceKind.STOCK), deadline)
        }
        val skuIds = collection.stockRequirements.allocations.map { it.position.pool.skuId }.toSet()
        if (skuIds.isEmpty()) return null
        // Actual complete selected-SKU/transitive active-reservation closure, including genuine
        // retained stock producers, claims, pages, positions and serial qualification. No plan.
        val inventory = observeBoundedStock { stock.observe(view, skuIds.sortedBy { it.value }, listOf(collection.reservationId)) }
            ?: return null
        val reservation = requireCollectionReservation(cut, inventory)
        if (inventory.scope != view.scope || reservation.status != ReservationStatus.RESERVED || reservation.holds.isNotEmpty() ||
            reservation.binding.exposure !in setOf(CollectionExposure.UNKNOWN, CollectionExposure.PART_PAID)) return null
        val reservationRow = view.aggregate(LocalStockStoreProfile.reservationKey(reservation.reservationId)) ?: return null
        if (!sameRetailStartPostimage(retained.origin, reservationRow)) return null
        val now = context.observedAt.value.toEpochMilliseconds()
        if (now < 0) return null
        var minimumDeadline: Long? = reservation.expiresAtEpochMillis
        if (minimumDeadline?.let { now >= it } == true) return null
        val allocated = reservation.allocations.groupBy { it.position }
        if (allocated.isEmpty()) return null
        for ((key, ownAllocations) in allocated) {
            val position = inventory.snapshot.position(key) ?: return null
            if (!position.isSellableAt(now)) return null
            val zero = Quantity(position.onHand.unitId, 0L, position.onHand.scale)
            val own = ownAllocations.fold(zero) { sum, item -> sum + item.quantity }
            val allRetained = inventory.snapshot.reservations.filter { it.retainsStockClaim }.flatMap { it.allocations }
                .filter { it.position == key }.fold(zero) { sum, item -> sum + item.quantity }
            if (own.scaled <= 0 || allRetained < own || allRetained > position.onHand) return null
            position.expiresAtEpochMillis?.let { deadline -> minimumDeadline = minimumDeadline?.let { minOf(it, deadline) } ?: deadline }
        }
        val currentDeadline = minimumDeadline?.let { UtcInstant(Instant.fromEpochMilliseconds(it)) }
        // Actual current minima are mandatory. The genuine original Start bound may additionally
        // shorten them; a later stock metadata change must never renew that original expiry.
        val deadline = earlierRetailDeadline(currentDeadline, retained.result.mustCommitBefore)
        if (deadline != null && context.observedAt >= deadline) return null
        val resources = financialResources + stock.resources(inventory) + retailJournalAuthorityResources(retained.requirement)
        if (resources.size > 512) return null
        return PhysicalRetainedStartRequirement(
            salesAuthorizationRequirement(SecurityAction.COLLECTION_ATTEST, view.scope, collection.id, collection.revision,
                context.original.operationId, context.original.semanticDigest, "collection.instruction", actor, attempt.amount),
            context.requirement, resources, setOf(RecoveryResourceKind.STOCK), deadline)
    }
}

/** Same current accepted-return/original-tender obligations; no arbitrary refund action TTL. */
internal class LocalReturnInteractiveAuthorization(private val stock: LocalStockRepository? = null,
    private val tenderPolicies: HistoricalSalesTenderPolicySourceV1 = UnavailableHistoricalSalesTenderPolicySourceV1) : MasterPhysicalInteractiveAuthorizer {
    override val command: CommandKey get() = LocalFinancialJournalProfileV3.RETURN_COMMAND
    override val domainProfile: String get() = LocalFinancialJournalProfileV3.REFUND_PROFILE

    override suspend fun prepareRetainedStartAuthorization(borrowed: CommandReadView,
        context: PhysicalRetainedStartContext): PhysicalRetainedStartRequirement? {
        val view = stock?.let { commerceLoan(borrowed, it, tenderPolicies = tenderPolicies) } ?: borrowed
        val retained = readInteractiveRetailStart(view, context, SalesRecordingKind.REFUND_ATTESTATION) ?: return null
        val request = LocalReturnCommandCodecV3.decode(retained.origin.normalized) as? LocalReturnRequestV3.Start ?: return null
        val target = retained.slot.target
        if (request.target(context.original.operationId) != target) return null
        // This existing source reader closes original sale/entitlement/claim/intake or CASE,
        // accepted return and accepting-operator work/custody. It manufactures no v1 intake.
        val cut = readAcceptedReturn(view, request.saleId, request.claimId, request.returnId)
        val cashCut = LocalSalesStorage.cash(view, request.sessionId)
        val accepted = cut.accepted.value
        val cash = cashCut.session.value
        val actor = context.original.originalActorId
        if (!sameRetailStartPostimage(retained.origin, cut.accepted.stored) ||
            !sameRetailStartPostimage(retained.origin, cashCut.session.stored)) return null
        if (accepted.id != target.documentId || accepted.saleId != request.saleId || accepted.claimId != request.claimId ||
            cash.id != target.sessionId || cash.custodianId != actor || cash.status != CashSessionStatus.OPEN ||
            accepted.due.minor <= 0 || accepted.id !in cut.work.value.returnIds) return null
        val attempt = accepted.attempts.singleOrNull { it.id == target.attemptId } ?: return null
        if (attempt.status != AttemptStatus.AWAITING_ATTESTATION || attempt.latestObservation != null || attempt.refund != null ||
            attempt.originalActorId != actor || attempt.originalCashSessionId != cash.id ||
            attempt.originalOperationId != context.original.operationId || attempt.originalPaymentId != request.originalPaymentId ||
            attempt.amount != request.amount || attempt.amount != retained.slot.domain.amount || attempt.amount.minor <= 0 ||
            attempt.amount.currency != accepted.originalPayable.currency || attempt.amount.minor > accepted.due.minor ||
            tenderView(attempt.tender) != retained.slot.domain.tender || attempt.id !in cash.pendingAttemptIds ||
            accepted.attempts.filter { it.status.unresolved() }.map { it.id } != listOf(attempt.id)) return null
        if (view.claim(LocalSalesStoreProfile.unique("refund_record", attempt.id)) != null ||
            view.claim(CashNonSaleStoreProfile.cashSourceClaim(attempt.id)) != null) return null
        val obligation = accepted.tenderObligations.singleOrNull { it.originalPaymentId == attempt.originalPaymentId } ?: return null
        if (obligation.tender != attempt.tender || obligation.amount.currency != attempt.amount.currency) return null
        val alreadyPaid = accepted.attempts.filter { it.originalPaymentId == attempt.originalPaymentId }
            .fold(Money(attempt.amount.currency, 0L)) { sum, item -> sum + (item.refund?.amount ?: Money(attempt.amount.currency, 0L)) }
        if (attempt.amount.minor > (obligation.amount - alreadyPaid).minor) return null
        // Refund Start ALREADY reserved this payout. Do not erroneously require fresh available
        // cash or rerun beginAttempt; the exact original postimage and current reservation cover it.
        if (attempt.tender == Tender.Cash && (cash.reservedCashOut.currency != attempt.amount.currency ||
                cash.reservedCashOut.minor < attempt.amount.minor || cash.expectedCash.minor < cash.reservedCashOut.minor)) return null
        val financialResources = returnCashResources(cut, cashCut)
        if (financialResources != retained.slot.domain.financialResources.toSet()) return null
        val resources = financialResources + retailJournalAuthorityResources(retained.requirement)
        if (resources.size > 512 || retained.result.mustCommitBefore != null) return null
        return PhysicalRetainedStartRequirement(
            salesAuthorizationRequirement(SecurityAction.REFUND_RECORD, view.scope, accepted.id, accepted.revision,
                context.original.operationId, context.original.semanticDigest, "refund.instruction", actor, attempt.amount),
            context.requirement, resources, emptySet(), businessDeadlineExclusive = null)
    }
}

private class InteractiveRetailRetained(
    val slot: LocalRetailJournalSlotV3,
    val requirement: LocalPhysicalJournalRequirementV1,
    val origin: RetainedCommandEvidence,
    val result: LocalFinancialResultV3.AttemptStarted,
)

private suspend fun readInteractiveRetailStart(view: CommandReadView, context: PhysicalRetainedStartContext,
    kind: SalesRecordingKind): InteractiveRetailRetained? {
    val original = context.original
    val start = context.committedStart
    if (original.scope != view.scope || start.scope != view.scope || original.key != retailJournalCommandV3(kind) ||
        original.operationId != start.operationId || original.originalActorId != start.originalActorId ||
        original.semanticDigest != start.semanticDigest || start.domainProfile != retailJournalProfileV3(kind) ||
        start.startKind != (if (kind == SalesRecordingKind.PAYMENT_ATTESTATION) PhysicalRecordingStartKind.PAYMENT else PhysicalRecordingStartKind.REFUND))
        return null
    val requirement = retailRequirementFromAdmissionV3(context.requirement)
    if (requirement.scope != view.scope || requirement.originalActorId != original.originalActorId ||
        requirement.intentOperationId != original.operationId || requirement.command != original.key) return null
    val slot = readRetailSlot(view, requirement.recordingOperationId)
    if (slot.value.target.kind != kind || slot.value.actorId != original.originalActorId ||
        slot.value.target.intentOperationId != original.operationId || slot.value.requirement().canonicalBytes != context.requirement) return null
    // readRetailSlot has already checked BOTH exact gen1 source rows, complete genuine Start,
    // all lineage/unique claims and both immutable future-operation fences in this SAME view.
    val origin = view.retainedCommand(original.operationId) ?: return null
    if (!origin.committed || origin.scope != view.scope || origin.command != original.key ||
        origin.originalActorId != original.originalActorId || origin.semanticDigest != original.semanticDigest ||
        origin.resultHash != start.resultDigest || origin.recordedAt != start.recordedAt || origin.sequence != start.sequence ||
        origin.journalHash != start.journalHash || origin.assignmentId != start.authority.assignmentId ||
        origin.authorityEpoch != start.authority.epoch) return null
    val result = LocalFinancialResultCodecV3.decode(origin.result) as? LocalFinancialResultV3.AttemptStarted ?: return null
    if (result.target != slot.value.target) return null
    for (phase in LocalJournalFactPhaseV3.entries) {
        val operationId = slot.value.target.factOperation(phase)
        if (view.aggregate(retailJournalFactKeyV3(phase, operationId)) != null ||
            view.aggregate(retailJournalApplicationKeyV3(operationId)) != null || view.retainedCommand(operationId) != null) return null
    }
    return InteractiveRetailRetained(slot.value, requirement, origin, result)
}

private fun sameRetailStartPostimage(origin: RetainedCommandEvidence, current: StoredAggregate): Boolean {
    val written = origin.aggregateWrites.singleOrNull { it.key == current.key } ?: return false
    return current.revision == written.storageGeneration && current.schema == written.schema &&
        current.payload == written.payload && current.digest == written.payloadDigest
}

private fun earlierRetailDeadline(first: UtcInstant?, second: UtcInstant?): UtcInstant? = when {
    first == null -> second
    second == null -> first
    first < second -> first
    else -> second
}

/**
 * Closed supported production tender profile: LocalConfigurationRepository.current actually
 * reads `local.payment.tender/<id>` schema `/v1`, decodes its active policy and returns precisely
 * that tender's authorization resource. Require that actual read in this loan, not a cached DTO.
 * The catalog adapter depends on sales-store, so no circular catalog import or duplicate codec
 * is introduced here. Unknown provider profiles/read paths are nonarming, not assumed complete.
 * Interpretation still belongs to the trusted installed production reader, not this trace.
 */
internal class RetailTenderRead(private val actual: CommandReadView, tenderId: DomainId) : CommandReadView {
    private val expected = AggregateKey("local.payment.tender", tenderId)
    private var row: StoredAggregate? = null
    private var unavailable = false
    private var closed = false
    override val scope: BranchScope get() = actual.scope
    val complete: Boolean get() = closed && !unavailable && row != null
    fun close() { closed = true }
    override suspend fun aggregate(key: AggregateKey): StoredAggregate? {
        check(!closed)
        if (key != expected) unavailable = true
        val observed = try { actual.aggregate(key) } catch (failure: Throwable) { unavailable = true; throw failure }
        if (observed == null || observed.key != expected || observed.schema != "local.payment.tender/v1" || observed.revision.value <= 0)
            unavailable = true
        if (row != null && (row?.revision != observed?.revision || row?.digest != observed?.digest)) unavailable = true
        row = observed
        return observed
    }
    override suspend fun claim(key: ClaimKey): DomainId? { check(!closed); unavailable = true; return actual.claim(key) }
    override suspend fun aggregateKeys(kind: String, after: DomainId?, limit: Int): AggregateKeyPage {
        check(!closed); unavailable = true; return actual.aggregateKeys(kind, after, limit)
    }
    override suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence? {
        check(!closed); unavailable = true; return actual.retainedCommand(operationId)
    }
}
