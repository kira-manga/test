package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateChange
import com.ukkera.pos.database.ClaimChange
import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.PhysicalRecordingAction
import com.ukkera.pos.database.PreparedMutation
import com.ukkera.pos.database.ProposedOutcome
import com.ukkera.pos.database.StockReadEvidenceCapacityExceeded
import com.ukkera.pos.database.requireStockRelational
import com.ukkera.pos.inventory.store.LocalStockPaymentObservations
import com.ukkera.pos.inventory.store.observePaymentReservation
import com.ukkera.pos.inventory.CollectionExposure
import com.ukkera.pos.inventory.InventoryPlanner
import com.ukkera.pos.inventory.InventoryRejection
import com.ukkera.pos.inventory.ReservationSafetyResult
import com.ukkera.pos.inventory.ReservationStatus
import com.ukkera.pos.inventory.store.LocalStockContribution
import com.ukkera.pos.inventory.store.LocalStockFailure
import com.ukkera.pos.inventory.store.LocalStockRejected
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.AcceptedReturnChanged
import com.ukkera.pos.sales.CashSessionChanged
import com.ukkera.pos.sales.CollectionChanged
import com.ukkera.pos.sales.Collections
import com.ukkera.pos.sales.InventoryCommitKind
import com.ukkera.pos.sales.Returns
import com.ukkera.pos.sales.SalesCommand
import com.ukkera.pos.sales.SalesRule
import com.ukkera.pos.sales.SalesRuleViolation
import com.ukkera.pos.sales.SalesSnapshotLinks
import com.ukkera.pos.sales.SalesSnapshotFailure
import com.ukkera.pos.sales.SalesSnapshotRejectedException
import com.ukkera.pos.sales.SalesUniqueCheck
import com.ukkera.pos.sales.SalesUniqueKind
import com.ukkera.pos.sales.Tender
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/** No native source, slot or fence exists in this purely financial/stock proposal. */
private sealed interface RetailStartDraft {
    class Ready(val changes: List<AggregateChange>, val claims: List<ClaimChange>,
        val revisions: List<LocalSalesRevision>, val stock: LocalStockContribution? = null,
        val deadline: UtcInstant? = stock?.mustCommitBefore,
        val stockItems: List<LocalSalesStockRevisionV1> = stock?.let(::retailStockVersions).orEmpty()) : RetailStartDraft
    class Held(val collectionId: DomainId, val contribution: LocalStockContribution,
        val before: List<AggregateChange>, val reservationId: DomainId, val reservationRevision: Revision) : RetailStartDraft
    class Refused(val proposal: ProposedOutcome.Reject) : RetailStartDraft
}

internal suspend fun prepareRetailPaymentStart(request: LocalCollectionRequestV3.Start, view: CommandReadView,
    context: CommandContext, stock: LocalStockRepository, tenderSource: TransactionSalesTenderSource,
    participantsSource: TransactionRetailJournalParticipantsSource): PreparedMutation {
    val row = LocalSalesStorage.required(view, LocalSalesStoreProfile.key(LocalSalesStoreProfile.COLLECTION_KIND, request.collectionId))
    if (normalizedCollection(row, view.scope).stockRequirements.normalized)
        return prepareNormalizedRetailPaymentStart(request, view, context, stock, tenderSource, participantsSource, row)
    val cut = readCollection(view, request.collectionId)
    val collection = cut.collection.value
    val baseResources = collectionResources(cut)
    if (request.sessionId != collection.cashSessionId) return refusedRetailStart(context, SecurityAction.COLLECTION_ATTEST,
        collection.id, request.expectedCollectionRevision, collection.collectorId, request.amount, baseResources,
        retailRejected(SalesRule.OWNER_REQUIRED), true)
    val skuIds = collection.stockRequirements.allocations.map { it.position.pool.skuId }.toSet()
    val inventory = observeBoundedStock { stock.observe(view, skuIds.toList(), listOf(collection.reservationId)) }
        ?: return refusedRetailStart(context, SecurityAction.COLLECTION_ATTEST, collection.id, request.expectedCollectionRevision,
            collection.collectorId, request.amount, baseResources, retailUnavailable(LocalJournalUnavailableV3.CAPACITY_REQUIRED), true)
    val reservation = requireCollectionReservation(cut, inventory)
    val tenderSnapshot = (request.tender as? LocalTenderSelectionV1.External)?.let {
        tenderSource.current(view, it.tenderId, it.expectedPolicyRevision)
    }
    check(tenderSnapshot == null || tenderSnapshot.scope == view.scope) { "sales.tender.scope" }
    if (tenderSnapshot != null) {
        val selected = request.tender as LocalTenderSelectionV1.External
        check(tenderSnapshot.tender.tenderId == selected.tenderId &&
            tenderSnapshot.tender.policyRevision == selected.expectedPolicyRevision) { "sales.tender.source" }
    }
    val tender = when (request.tender) {
        LocalTenderSelectionV1.Cash -> Tender.Cash
        is LocalTenderSelectionV1.External -> tenderSnapshot?.tender
    }
    val resources = baseResources + tenderSnapshot?.recoveryResources.orEmpty()
    val target = request.target(context.operationId)
    val requirement = tender?.let { prepareRetailRequirement(view, context, participantsSource, target, request.amount,
        tenderView(it), request.expectedCollectionRevision, request.expectedSessionRevision,
        request.masterAllocationId, request.clientAllocationId, resources) }
    val duplicateAttempt = financialSourceAllocated(view, request.attemptId)
    val vacant = requirement?.let { retailJournalVacantV3(view, it) } == true
    val unique = LocalSalesStoreProfile.unique("payment_attempt", request.attemptId)
    val allResources = resources + stock.resources(inventory) + requirement?.let(::retailJournalAuthorityResources).orEmpty()

    fun draft(command: SalesCommand, now: UtcInstant): RetailStartDraft = retailStartDraft {
        val refusal = collectionAttemptAdmission(cut, context, request.expectedCollectionRevision, request.expectedSessionRevision)
        if (refusal != null) return@retailStartDraft refusedDraft(refusal)
        if (collection.unresolved) return@retailStartDraft refusedDraft(SalesRule.PAYMENT_UNRESOLVED)
        if (request.amount.currency != collection.quote.currency || request.amount.minor <= 0 ||
            request.amount.minor > collection.remainingPayable.minor) return@retailStartDraft refusedDraft(SalesRule.INVALID_AMOUNT)
        if (duplicateAttempt) return@retailStartDraft refusedDraft(SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        if (tender == null) return@retailStartDraft unavailableDraft(LocalJournalUnavailableV3.SOURCE_REQUIRED)
        if (!requiredStockVersions(inventory, request.items, skuIds) || reservation.revision != request.expectedReservationRevision) {
            return@retailStartDraft refusedDraft(SalesRule.REVISION_CONFLICT)
        }
        if (reservation.binding.exposure == CollectionExposure.PAID) return@retailStartDraft refusedDraft(SalesRule.STOCK_PROOF_MISMATCH)
        if (requirement == null) return@retailStartDraft unavailableDraft(LocalJournalUnavailableV3.JOURNAL_REQUIRED)
        if (!vacant) return@retailStartDraft refusedDraft(SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        if (!recordingRevisionHeadroom(collection.revision, cut.cash.session.value.revision, cut.cash.session.value.cashLedgerRevision)) {
            return@retailStartDraft unavailableDraft(LocalJournalUnavailableV3.REVISION_EXHAUSTED)
        }
        val safety = InventoryPlanner.revalidateReservation(inventory.snapshot, inventory.snapshot.revision, context.operationId,
            reservation.reservationId, request.expectedReservationRevision, now.value.toEpochMilliseconds())
        val before = collectionBefore(context, listOf("order" to cut.order.order.stored, "collection" to cut.collection.stored,
            "cash" to cut.cash.session.stored) + priceSourcesBefore(cut.order) + returnPolicyCaptureBefore(cut.order))
        when (safety) {
            is ReservationSafetyResult.Held -> {
                val contribution = stock.project(inventory, context, safety.plan, now)
                val held = contribution.plan.nextSnapshot.reservation(collection.reservationId) ?: error("sales.stock.reservation")
                check(held.status == ReservationStatus.HELD && held.binding.exposure != CollectionExposure.UNCOLLECTED && contribution.claims.isEmpty())
                RetailStartDraft.Held(collection.id, contribution, before, held.reservationId, held.revision)
            }
            is ReservationSafetyResult.Safe -> {
                val transition = Collections.startAttempt(command, collection, request.expectedCollectionRevision, cut.cash.session.value,
                    request.expectedSessionRevision, request.attemptId, tender, request.amount, safety, request.payerReference)
                val after = transition.value
                check(transition.command == command && transition.versions == collectionCashVersions(cut) &&
                    transition.unique == listOf(SalesUniqueCheck(SalesUniqueKind.PAYMENT_ATTEMPT, request.attemptId)) &&
                    transition.effects == listOf(CollectionChanged(after.collection), CashSessionChanged(after.session))) { "sales.transition.invalid" }
                val dependency = transition.inventoryDependencies.single()
                check(dependency.kind == InventoryCommitKind.REVALIDATE && dependency.plan === safety.plan &&
                    dependency.reservationId == collection.reservationId && dependency.issueId == null) { "sales.stock.plan_identity" }
                SalesSnapshotLinks.requireCollection(after.collection, cut.order.order.value, after.session)
                val collectionWrite = collectionWrite(after.collection, cut.collection.stored)
                val cashWrite = cashWrite(after.session, cut.cash.session.stored)
                if (listOf(collectionWrite, cashWrite).any { it.payload.size > SalesRecordingBudget.MAX_PENDING_DOCUMENT_BYTES }) {
                    return@retailStartDraft unavailableDraft(LocalJournalUnavailableV3.CAPACITY_REQUIRED)
                }
                val contribution = stock.project(inventory, context, safety.plan, now)
                check(contribution.claims.isEmpty()) { "sales.journal.start_stock_claims" }
                RetailStartDraft.Ready(listOf(collectionWrite, cashWrite) + before, listOf(ClaimChange.Acquire(unique, collection.id)),
                    listOf(LocalSalesRevision(LocalSalesStoreProfile.COLLECTION_KIND, collection.id, after.collection.revision),
                        LocalSalesRevision(CashNonSaleStoreProfile.SESSION_KIND, after.session.id, after.session.revision)), contribution)
            }
        }
    }
    return retailPreparedStart(context, SecurityAction.COLLECTION_ATTEST, collection.id, request.expectedCollectionRevision,
        collection.collectorId, "collection.attempt.start", request.amount, allResources, requirement, stock,
        setOf(RecoveryResourceKind.STOCK), ::draft)
}

/** Ordinary journal start on an exact qualified tender. UNKNOWN stock rows/claims/quantities remain untouched. */
private suspend fun prepareNormalizedRetailPaymentStart(request: LocalCollectionRequestV3.Start, view: CommandReadView,
    context: CommandContext, stock: LocalStockRepository, tenderSource: TransactionSalesTenderSource,
    participantsSource: TransactionRetailJournalParticipantsSource, initial: com.ukkera.pos.database.StoredAggregate): PreparedMutation {
    val original = normalizedCollection(initial, view.scope)
    val initialResources = setOf(orderResource(original.id), orderResource(original.orderId), orderResource(original.collectorId),
        cashResource(original.cashSessionId)) + priceSourcesResources(original.priceSourceReference)
    val capture = view.requireStockRelational().beginReadEvidence(context)
    fun capacityRefusal(): PreparedMutation = refusedRetailStart(context, SecurityAction.COLLECTION_ATTEST, original.id,
        request.expectedCollectionRevision, original.collectorId, request.amount, initialResources,
        retailUnavailable(LocalJournalUnavailableV3.CAPACITY_REQUIRED), true)
    try {
        // The full current collection/order/cash/link reads are captured before any native callback.
        val cut = readCollection(view, request.collectionId)
        val collection = cut.collection.value
        val base = collectionResources(cut)
        if (request.sessionId != collection.cashSessionId) {
            capture.discard()
            return refusedRetailStart(context, SecurityAction.COLLECTION_ATTEST, collection.id, request.expectedCollectionRevision,
                collection.collectorId, request.amount, base, retailRejected(SalesRule.OWNER_REQUIRED), true)
        }
        val tenderSnapshot = when (val selected = request.tender) {
            LocalTenderSelectionV1.Cash -> null
            is LocalTenderSelectionV1.External -> {
                val traced = RetailTenderRead(view, selected.tenderId)
                val current = try { tenderSource.current(traced, selected.tenderId, selected.expectedPolicyRevision) }
                    finally { traced.close() }
                if (current == null || !traced.complete || current.scope != view.scope ||
                    current.tender.tenderId != selected.tenderId || current.tender.policyRevision != selected.expectedPolicyRevision ||
                    current.recoveryResources != setOf(recordingAuthority(selected.tenderId))) {
                    capture.discard()
                    return refusedRetailStart(context, SecurityAction.COLLECTION_ATTEST, collection.id,
                        request.expectedCollectionRevision, collection.collectorId, request.amount, base,
                        retailUnavailable(LocalJournalUnavailableV3.SOURCE_REQUIRED), true)
                }
                current
            }
        }
        val tender = tenderSnapshot?.tender ?: Tender.Cash
        val financialResources = base + tenderSnapshot?.recoveryResources.orEmpty()
        val currentStock = stock.observePaymentReservation(view, collection.reservationId)
        requireNormalizedCollectionReservation(collection, currentStock.header) { each -> for (allocation in currentStock.allocations) each(allocation) }
        requireNormalizedPaymentDemand(collection, currentStock)
        val items = currentStock.items.map { LocalSalesStockRevisionV1(it.skuId, checkNotNull(it.storageRevision)) }
        val requirement = prepareRetailRequirement(view, context, participantsSource, request.target(context.operationId), request.amount,
            tenderView(tender), request.expectedCollectionRevision, request.expectedSessionRevision,
            request.masterAllocationId, request.clientAllocationId, financialResources)
        val duplicateAttempt = financialSourceAllocated(view, request.attemptId)
        val vacant = requirement?.let { retailJournalVacantV3(view, it) } == true
        val oldLink = LocalSalesStorage.required(view, commerceLinkKey(cut.collection.stored.key), commerceLinkSchema())
        val observed = capture.observations()
        if (requirement != null && !duplicateAttempt && vacant) {
            // Close the literal financial/stock source set BEFORE native availability/admission.
            // The effective-outcome interpreter repeats this over the exact sealed chunk bytes.
            val sources = NormalizedPaymentSources(observed)
            sources.collection(collection, cut.order.order.value, cut.cash.session.value)
            sources.tender(tender, commerceTenderPolicies(view))
            sources.startVacancy(request, requirement)
            sources.finish(stock = true)
            val literalStock = LocalStockPaymentObservations.reservation(observed, collection.reservationId)
            requireNormalizedPaymentDemand(collection, literalStock)
            check(literalStock.header == currentStock.header && literalStock.allocations == currentStock.allocations &&
                literalStock.items == currentStock.items && literalStock.deadline == currentStock.deadline &&
                literalStock.structurallySafe == currentStock.structurallySafe)
        }
        val resources = financialResources + LocalStockPaymentObservations.resources(observed) + requirement?.let(::retailJournalAuthorityResources).orEmpty()
        if (resources.size > 512) {
            capture.discard()
            return capacityRefusal()
        }
        val before = collectionBefore(context, listOf("order" to cut.order.order.stored, "collection" to cut.collection.stored,
            "cash" to cut.cash.session.stored) + priceSourcesBefore(cut.order) + returnPolicyCaptureBefore(cut.order))
        val evidence = capture.finish()
        fun draft(command: SalesCommand, now: UtcInstant): RetailStartDraft = retailStartDraft {
            collectionAttemptAdmission(cut, context, request.expectedCollectionRevision, request.expectedSessionRevision)?.let {
                return@retailStartDraft refusedDraft(it)
            }
            if (collection.unresolved) return@retailStartDraft refusedDraft(SalesRule.PAYMENT_UNRESOLVED)
            if (request.amount.currency != collection.quote.currency || request.amount.minor <= 0 ||
                request.amount.minor > collection.remainingPayable.minor) return@retailStartDraft refusedDraft(SalesRule.INVALID_AMOUNT)
            if (duplicateAttempt || !vacant && requirement != null) return@retailStartDraft refusedDraft(SalesRule.DUPLICATE_SOURCE_ALLOCATION)
            if (requirement == null) return@retailStartDraft unavailableDraft(LocalJournalUnavailableV3.JOURNAL_REQUIRED)
            if (request.items.sortedBy { it.skuId.value } != items || currentStock.header.revision != request.expectedReservationRevision)
                return@retailStartDraft refusedDraft(SalesRule.REVISION_CONFLICT)
            if (!currentStock.safeAt(now)) return@retailStartDraft refusedDraft(SalesRule.STOCK_PROOF_MISMATCH)
            if (!recordingRevisionHeadroom(collection.revision, cut.cash.session.value.revision, cut.cash.session.value.cashLedgerRevision) ||
                oldLink.revision.value == Long.MAX_VALUE) return@retailStartDraft unavailableDraft(LocalJournalUnavailableV3.REVISION_EXHAUSTED)
            val transition = Collections.startNormalizedAttempt(command, collection, request.expectedCollectionRevision, cut.cash.session.value,
                request.expectedSessionRevision, request.attemptId, request.amount, currentStock.header, request.payerReference, tender)
            val after = transition.value
            check(transition.command == command && transition.inventoryDependencies.isEmpty() && transition.versions == collectionCashVersions(cut) &&
                transition.unique == listOf(SalesUniqueCheck(SalesUniqueKind.PAYMENT_ATTEMPT, request.attemptId)) &&
                transition.effects == listOf(CollectionChanged(after.collection), CashSessionChanged(after.session)))
            SalesSnapshotLinks.requireCollection(after.collection, cut.order.order.value, after.session)
            val document = collectionWrite(after.collection, cut.collection.stored)
            val cash = cashWrite(after.session, cut.cash.session.stored)
            if (listOf(document, cash).any { it.payload.size > SalesRecordingBudget.MAX_PENDING_DOCUMENT_BYTES })
                return@retailStartDraft unavailableDraft(LocalJournalUnavailableV3.CAPACITY_REQUIRED)
            val link = normalizedPaymentLink(context, document, cut.collection.stored, oldLink)
            val changes = listOf(document, cash, link) + before + evidence
            // Conservative complete finite envelope, including the real native pair's maximum
            // rows and result, is checked BEFORE core may ask the native gate for availability.
            val physicalBytes = 2L * LocalPhysicalJournalFormatV1.MAX_HEADER_BYTES + LocalFinancialJournalProfileV3.MAX_SLOT_BYTES +
                retailFutureFencesV3(requirement).sumOf { it.payload.size.toLong() } + LocalFinancialJournalProfileV3.MAX_RESULT_BYTES
            if (changes.size + 5 > 128 || changes.any { it.payload.size > 65_536 } ||
                changes.sumOf { it.payload.size.toLong() } + physicalBytes > 1_048_576L)
                return@retailStartDraft unavailableDraft(LocalJournalUnavailableV3.CAPACITY_REQUIRED)
            RetailStartDraft.Ready(changes, listOf(ClaimChange.Acquire(LocalSalesStoreProfile.unique("payment_attempt", request.attemptId), collection.id)),
                listOf(LocalSalesRevision(LocalSalesStoreProfile.COLLECTION_KIND, collection.id, after.collection.revision),
                    LocalSalesRevision(CashNonSaleStoreProfile.SESSION_KIND, after.session.id, after.session.revision)),
                deadline = currentStock.deadline, stockItems = items)
        }
        return retailPreparedStart(context, SecurityAction.COLLECTION_ATTEST, collection.id, request.expectedCollectionRevision,
            collection.collectorId, "collection.attempt.start", request.amount, resources, requirement, null,
            setOf(RecoveryResourceKind.STOCK), ::draft)
    } catch (_: StockReadEvidenceCapacityExceeded) {
        capture.discard()
        return capacityRefusal()
    } catch (_: NormalizedPaymentOriginCapacity) {
        capture.discard()
        return capacityRefusal()
    } catch (rejected: LocalStockRejected) {
        if (rejected.failure != LocalStockFailure.BOUND_EXCEEDED) throw rejected
        capture.discard()
        return capacityRefusal()
    } catch (_: LocalSalesCapacityExceeded) {
        capture.discard()
        return capacityRefusal()
    } catch (_: LocalSalesWireCapacityExceeded) {
        capture.discard()
        return capacityRefusal()
    } catch (rejected: SalesSnapshotRejectedException) {
        if (rejected.failure != SalesSnapshotFailure.TOO_LARGE) throw rejected
        capture.discard()
        return capacityRefusal()
    }
}

internal suspend fun prepareRetailRefundStart(request: LocalReturnRequestV3.Start, view: CommandReadView,
    context: CommandContext, participantsSource: TransactionRetailJournalParticipantsSource): PreparedMutation {
    // Full original sale/claim/CASE qualification, entitlement, accepted-return and custody are read unchanged.
    val cut = readAcceptedReturn(view, request.saleId, request.claimId, request.returnId)
    val cash = LocalSalesStorage.cash(view, request.sessionId)
    val resources = returnCashResources(cut, cash)
    val tender = cut.accepted.value.tenderObligations.singleOrNull { it.originalPaymentId == request.originalPaymentId }?.tender
    val target = request.target(context.operationId)
    val requirement = tender?.let { prepareRetailRequirement(view, context, participantsSource, target, request.amount,
        tenderView(it), request.expectedReturnRevision, request.expectedSessionRevision,
        request.masterAllocationId, request.clientAllocationId, resources) }
    val duplicateAttempt = financialSourceAllocated(view, request.attemptId)
    val vacant = requirement?.let { retailJournalVacantV3(view, it) } == true
    val unique = LocalSalesStoreProfile.unique("refund_attempt", request.attemptId)
    fun draft(command: SalesCommand, now: UtcInstant): RetailStartDraft = retailStartDraft {
        if (duplicateAttempt) return@retailStartDraft refusedDraft(SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        if (tender == null) return@retailStartDraft refusedDraft(SalesRule.TENDER_MISMATCH)
        if (requirement == null) return@retailStartDraft unavailableDraft(LocalJournalUnavailableV3.JOURNAL_REQUIRED)
        if (!vacant) return@retailStartDraft refusedDraft(SalesRule.DUPLICATE_SOURCE_ALLOCATION)
        if (!recordingRevisionHeadroom(cut.accepted.value.revision, cash.session.value.revision, cash.session.value.cashLedgerRevision) ||
            cut.work.value.revision.value >= Long.MAX_VALUE - 1) return@retailStartDraft unavailableDraft(LocalJournalUnavailableV3.REVISION_EXHAUSTED)
        // This pure domain transition enforces revisions, owner, OPEN cash, actual original-tender
        // obligation minus genuine prior refunds, no unresolved payout, and available cash.
        val transition = Returns.startRefund(command, cut.accepted.value, request.expectedReturnRevision, cash.session.value,
            request.expectedSessionRevision, request.attemptId, request.originalPaymentId, request.amount)
        val after = transition.value
        check(transition.command == command && transition.inventoryDependencies.isEmpty() &&
            transition.versions == refundVersions(cut, cash) && transition.unique == listOf(SalesUniqueCheck(SalesUniqueKind.REFUND_ATTEMPT, request.attemptId)) &&
            transition.effects == listOf(AcceptedReturnChanged(after.acceptedReturn), CashSessionChanged(after.session))) { "sales.transition.invalid" }
        SalesSnapshotLinks.requireAcceptedReturn(after.acceptedReturn, cut.original.original.entitlement.value, cut.original.claim.value)
        val acceptedWrite = acceptedReturnWrite(after.acceptedReturn, cut.original.original.entitlement.value, cut.original.claim.value, cut.accepted.stored)
        val cashWrite = cashWrite(after.session, cash.session.stored)
        if (listOf(acceptedWrite, cashWrite).any { it.payload.size > SalesRecordingBudget.MAX_PENDING_DOCUMENT_BYTES }) {
            return@retailStartDraft unavailableDraft(LocalJournalUnavailableV3.CAPACITY_REQUIRED)
        }
        val before = retailRefundBeforeV3(context.operationId, LocalSalesScope.from(context.scope), cut, cash)
        RetailStartDraft.Ready(listOf(acceptedWrite, cashWrite) + before, listOf(ClaimChange.Acquire(unique, request.returnId)),
            listOf(LocalSalesRevision(LocalSalesStoreProfile.RETURN_KIND, request.returnId, after.acceptedReturn.revision),
                LocalSalesRevision(CashNonSaleStoreProfile.SESSION_KIND, request.sessionId, after.session.revision)))
    }
    return retailPreparedStart(context, SecurityAction.REFUND_RECORD, request.returnId, request.expectedReturnRevision,
        cash.session.value.custodianId, "refund.start", request.amount,
        resources + requirement?.let(::retailJournalAuthorityResources).orEmpty(), requirement, null, emptySet(), ::draft)
}

private fun retailPreparedStart(context: CommandContext, action: SecurityAction, targetId: DomainId,
    revision: Revision, ownerId: DomainId, reason: String, amount: Money, resources: Set<RecoveryResourceKey>,
    requirement: LocalPhysicalJournalRequirementV1?, stock: LocalStockRepository?, barriers: Set<RecoveryResourceKind>,
    draft: (SalesCommand, UtcInstant) -> RetailStartDraft): PreparedMutation = LocalJournalPrepared(context,
    action, targetId, revision, ownerId, reason, amount, resources, barriers,
    admission = { command, now ->
        // Real domain validation is repeated without ANY native or capacity callback. A Held
        // revalidation can commit only as NonArming; its allocation can never become a start.
        if (requirement == null || draft(command, now) is RetailStartDraft.Refused) null
        else retailAdmission(requirement, if (action == SecurityAction.COLLECTION_ATTEST)
            PhysicalRecordingAction.START_PAYMENT else PhysicalRecordingAction.START_REFUND)
    }) { command, now ->
    when (val value = draft(command, now)) {
        is RetailStartDraft.Refused -> value.proposal
        is RetailStartDraft.Held -> {
            val contribution = value.contribution
            val result = LocalFinancialResultV3.StockHeld(value.collectionId, value.reservationId, value.reservationRevision,
                retailEffectsDigestV3(contribution.changes + value.before, emptyList(), contribution.mustCommitBefore).value,
                contribution.mustCommitBefore, retailStockVersions(contribution))
            checkNotNull(stock).compose(context, contribution, LocalFinancialResultCodecV3.encode(result), value.before)
        }
        is RetailStartDraft.Ready -> {
            val bound = checkNotNull(requirement)
            val sources = retailJournalStartSourcesV3(context, bound)
                ?: return@LocalJournalPrepared retailUnavailable(LocalJournalUnavailableV3.JOURNAL_REQUIRED)
            val changes = value.changes + sources.changes
            val claims = value.claims + sources.claims
            val contribution = value.stock
            val result = LocalFinancialResultV3.AttemptStarted(sources.slot.target, amount, retailJournalDigestV3(bound.canonicalBytes).value,
                retailEffectsDigestV3(changes + contribution?.changes.orEmpty(), claims + contribution?.claims.orEmpty(),
                    value.deadline).value,
                value.deadline,
                value.revisions + LocalSalesRevision(LocalFinancialJournalProfileV3.SLOT_KIND, sources.slot.id, Revision(0)),
                value.stockItems)
            val bytes = LocalFinancialResultCodecV3.encode(result)
            if (contribution == null) {
                if (changes.sumOf { it.payload.size.toLong() } + bytes.size > 1_048_576L) {
                    retailUnavailable(LocalJournalUnavailableV3.CAPACITY_REQUIRED)
                } else ProposedOutcome.Apply(bytes, changes, claims, mustCommitBefore = value.deadline)
            } else checkNotNull(stock).compose(context, contribution, bytes, changes, claims)
        }
    }
}

private fun refusedRetailStart(context: CommandContext, action: SecurityAction, target: DomainId,
    revision: Revision, owner: DomainId, amount: Money, resources: Set<RecoveryResourceKey>, result: ProposedOutcome.Reject,
    stock: Boolean): PreparedMutation = LocalJournalPrepared(context, action, target, revision, owner,
    "financial.start.unavailable", amount, resources, if (stock) setOf(RecoveryResourceKind.STOCK) else emptySet()) { _, _ -> result }

internal fun retailStockVersions(contribution: LocalStockContribution): List<LocalSalesStockRevisionV1> =
    contribution.items.map { LocalSalesStockRevisionV1(it.skuId, checkNotNull(it.storageRevision)) }

private fun refusedDraft(rule: SalesRule): RetailStartDraft = RetailStartDraft.Refused(retailRejected(rule))
private fun unavailableDraft(reason: LocalJournalUnavailableV3): RetailStartDraft = RetailStartDraft.Refused(retailUnavailable(reason))
private inline fun retailStartDraft(block: () -> RetailStartDraft): RetailStartDraft = try {
    block()
} catch (violation: SalesRuleViolation) {
    refusedDraft(violation.rule)
} catch (_: InventoryRejection) {
    refusedDraft(SalesRule.STOCK_PROOF_MISMATCH)
} catch (rejected: LocalStockRejected) {
    if (rejected.failure == LocalStockFailure.BOUND_EXCEEDED) unavailableDraft(LocalJournalUnavailableV3.CAPACITY_REQUIRED) else throw rejected
} catch (_: LocalSalesCapacityExceeded) {
    unavailableDraft(LocalJournalUnavailableV3.CAPACITY_REQUIRED)
} catch (_: LocalSalesWireCapacityExceeded) {
    unavailableDraft(LocalJournalUnavailableV3.CAPACITY_REQUIRED)
} catch (rejected: SalesSnapshotRejectedException) {
    if (rejected.failure == SalesSnapshotFailure.TOO_LARGE) unavailableDraft(LocalJournalUnavailableV3.CAPACITY_REQUIRED) else throw rejected
}
