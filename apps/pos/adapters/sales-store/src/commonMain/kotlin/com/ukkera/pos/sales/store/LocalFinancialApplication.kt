package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateChange
import com.ukkera.pos.database.ClaimChange
import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.PreparedMutation
import com.ukkera.pos.database.ProposedOutcome
import com.ukkera.pos.database.StockReadCapture
import com.ukkera.pos.database.StockReadEvidenceCapacityExceeded
import com.ukkera.pos.database.requireStockRelational
import com.ukkera.pos.inventory.store.LocalStockFailure
import com.ukkera.pos.inventory.store.LocalStockRejected
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.AcceptedReturnChanged
import com.ukkera.pos.sales.AttemptStatus
import com.ukkera.pos.sales.CashMovement
import com.ukkera.pos.sales.CashSessionChanged
import com.ukkera.pos.sales.CashSessionStatus
import com.ukkera.pos.sales.CollectionChanged
import com.ukkera.pos.sales.CollectionObservationRecorded
import com.ukkera.pos.sales.CollectionOutcome
import com.ukkera.pos.sales.Collections
import com.ukkera.pos.sales.ExternalTenderEvidence
import com.ukkera.pos.sales.RefundObservationRecorded
import com.ukkera.pos.sales.RefundOutcome
import com.ukkera.pos.sales.Returns
import com.ukkera.pos.sales.SalesAggregate
import com.ukkera.pos.sales.SalesRuleViolation
import com.ukkera.pos.sales.SalesSnapshotFailure
import com.ukkera.pos.sales.SalesSnapshotLinks
import com.ukkera.pos.sales.SalesSnapshotRejectedException
import com.ukkera.pos.sales.SalesUniqueCheck
import com.ukkera.pos.sales.SalesUniqueKind
import com.ukkera.pos.sales.SalesVersionCheck
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/** SQL-only continuation over an already retained exact fact. It can never arm or free a journal. */
internal suspend fun prepareRetailApplication(kind: SalesRecordingKind, operationId: OperationId,
    phase: LocalJournalFactPhaseV3, view: CommandReadView, context: CommandContext, supervised: Boolean = false): PreparedMutation {
    if (supervised) requireRetailSupervisedApplication(context, kind, operationId, phase)
    else check(context.key == retailJournalCommandV3(kind) && com.ukkera.pos.database.semanticDigest(context.scope,
        context.originalActorId, context.key, retailApplicationBody(kind, operationId, phase)) == context.semanticDigest)
    if (view.aggregate(retailJournalFactKeyV3(phase, operationId)) == null) return unavailableRetailFact(context)
    val fact = try { readRetailFact(view, operationId, phase) }
        catch (_: NormalizedPaymentOriginCapacity) { return capacityRetailApplicationPreparation(context, kind, operationId, phase) }
    val slot = fact.slot.value
    if (slot.target.kind != kind || !supervised &&
        (slot.actorId != context.originalActorId || fact.fact.value.recordingActorId != slot.actorId)) return unavailableRetailFact(context)
    if (fact.application.value.state == LocalJournalApplicationStateV3.APPLIED) {
        return LocalJournalPrepared(context, if (supervised) retailFactAction(kind, LocalJournalFactPhaseV3.RESOLUTION)
            else SecurityAction.CASH_READ, slot.id, fact.application.value.revision,
            slot.actorId, "financial.journal.already_applied", amount = if (supervised) slot.domain.amount else null, resources = retailJournalAuthorityResources(slot.requirement())) { _, _ ->
            ProposedOutcome.Reject("sales.journal.already_applied", LocalFinancialResultCodecV3.encode(LocalFinancialResultV3.AlreadyApplied(
                operationId, phase, checkNotNull(fact.application.value.lastApplicationOperationId))))
        }
    }
    val parent = if (phase == LocalJournalFactPhaseV3.RESOLUTION) {
        val original = try { readRetailFact(view, slot.target.recordingOperationId, LocalJournalFactPhaseV3.ORIGINAL) }
            catch (_: NormalizedPaymentOriginCapacity) {
                return pendingRetailPreparation(context, fact, LocalJournalPendingReasonV3.STORAGE_CAPACITY_REQUIRED)
            }
        if (original.application.value.state != LocalJournalApplicationStateV3.APPLIED ||
            original.application.value.appliedAction != (if (kind == SalesRecordingKind.PAYMENT_ATTESTATION)
                LocalSalesAction.PAYMENT_UNKNOWN else LocalSalesAction.REFUND_UNKNOWN)) {
            return pendingRetailPreparation(context, fact, LocalJournalPendingReasonV3.ORIGINAL_APPLICATION_REQUIRED)
        }
        original
    } else null
    // Only named domain-source failures become a pending accounting result. Database/IO/kernel
    // uncertainty is NOT caught or converted into not-paid, resource freedom or erased raw bytes.
    var capture: StockReadCapture? = null
    return try {
        when (kind) {
            SalesRecordingKind.PAYMENT_ATTESTATION -> {
                val row = LocalSalesStorage.required(view, LocalSalesStoreProfile.key(LocalSalesStoreProfile.COLLECTION_KIND, slot.target.documentId))
                if (normalizedCollection(row, view.scope).stockRequirements.normalized)
                    capture = view.requireStockRelational().beginReadEvidence(context)
                // Raw fact/Start/UNKNOWN ancestry was fully qualified BEFORE collection capture.
                // Accounting captures only actual money/order/custody/link sources, never stock.
                prepareRetailPaymentApplication(fact, parent, readCollection(view, slot.target.documentId), view, context, supervised, capture)
            }
            SalesRecordingKind.REFUND_ATTESTATION -> prepareRetailRefundApplication(fact, parent,
                readAcceptedReturn(view, checkNotNull(slot.target.originalSaleId), checkNotNull(slot.target.originalClaimId), slot.target.documentId),
                LocalSalesStorage.cash(view, slot.target.sessionId), view, context, supervised)
        }
    } catch (unavailable: LocalSalesSourceUnavailable) {
        capture?.discard()
        pendingRetailPreparation(context, fact, if (unavailable.failure == LocalSalesSourceFailure.MISSING)
            LocalJournalPendingReasonV3.FINANCIAL_SOURCE_MISSING else LocalJournalPendingReasonV3.FINANCIAL_SOURCE_INVALID)
    } catch (rejected: SalesSnapshotRejectedException) {
        capture?.discard()
        pendingRetailPreparation(context, fact, if (capture != null && rejected.failure == SalesSnapshotFailure.TOO_LARGE)
            LocalJournalPendingReasonV3.STORAGE_CAPACITY_REQUIRED else LocalJournalPendingReasonV3.FINANCIAL_SOURCE_INVALID)
    } catch (_: StockReadEvidenceCapacityExceeded) {
        capture?.discard()
        pendingRetailPreparation(context, fact, LocalJournalPendingReasonV3.STORAGE_CAPACITY_REQUIRED)
    } catch (_: NormalizedPaymentOriginCapacity) {
        capture?.discard()
        pendingRetailPreparation(context, fact, LocalJournalPendingReasonV3.STORAGE_CAPACITY_REQUIRED)
    } catch (rejected: LocalStockRejected) {
        if (rejected.failure != LocalStockFailure.BOUND_EXCEEDED) throw rejected
        capture?.discard()
        pendingRetailPreparation(context, fact, LocalJournalPendingReasonV3.STORAGE_CAPACITY_REQUIRED)
    }
}

private suspend fun prepareRetailPaymentApplication(fact: LocalRetailJournalFactCutV3, parent: LocalRetailJournalFactCutV3?,
    cut: LocalCollectionCut, view: CommandReadView, context: CommandContext, supervised: Boolean,
    capture: StockReadCapture? = null): PreparedMutation {
    val slot = fact.slot.value
    val target = slot.target
    val collection = cut.collection.value
    val session = cut.cash.session.value
    val phase = fact.fact.value.phase
    val attempt = collection.attempts.singleOrNull { it.id == target.attemptId }
    val resources = retailApplicationResources(fact) + collectionResources(cut)
    suspend fun pending(reason: LocalJournalPendingReasonV3): PreparedMutation {
        capture?.discard()
        return pendingRetailPreparation(context, fact, reason, resources)
    }
    if (attempt == null || collection.cashSessionId != target.sessionId || collection.collectorId != slot.actorId ||
        attempt.originalOperationId != target.intentOperationId || attempt.originalActorId != slot.actorId ||
        attempt.originalCashSessionId != target.sessionId || attempt.amount != slot.domain.amount || tenderView(attempt.tender) != slot.domain.tender ||
        attempt.status != retailPriorStatus(phase) || attempt.id !in session.pendingAttemptIds ||
        view.claim(LocalSalesStoreProfile.unique("payment_attempt", target.attemptId)) != target.documentId ||
        (if (parent == null) attempt.latestObservation != null else attempt.latestObservation != retailPaymentObservation(parent.fact.value).journalOutcome(parent.fact.value.recordingActorId))) {
        return pending(LocalJournalPendingReasonV3.ORIGINAL_ATTEMPT_MISMATCH)
    }
    val paymentClaim = LocalSalesStoreProfile.unique("payment_record", attempt.id)
    val cashClaim = CashNonSaleStoreProfile.cashSourceClaim(attempt.id)
    if (view.claim(paymentClaim) != null || view.claim(cashClaim) != null ||
        view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.MOVEMENT_KIND, attempt.id)) != null) {
        return pending(LocalJournalPendingReasonV3.FINANCIAL_STATE_REQUIRES_RECONCILIATION)
    }
    val observed = retailPaymentObservation(fact.fact.value)
    check(collection.stockRequirements.normalized == (capture != null))
    val oldLink = if (capture == null) null else {
        val order = cut.order.order.value
        if (session.status != CashSessionStatus.OPEN || session.custodianId != slot.actorId || order.ownerId != slot.actorId ||
            collection.saleId != null || order.paidSaleId != null || order.handoff != null ||
            order.collectionId != collection.id || order.revision != collection.lockedOrderRevision)
            return pending(LocalJournalPendingReasonV3.FINANCIAL_STATE_REQUIRES_RECONCILIATION)
        val link = LocalSalesStorage.required(view, commerceLinkKey(cut.collection.stored.key), commerceLinkSchema())
        val sources = NormalizedPaymentSources(capture.observations())
        sources.collection(collection, order, session)
        sources.applicationVacancy(attempt.id)
        sources.finish(stock = false)
        // Existing six-write envelope stays unchanged. Cash-paid has four business writes
        // plus the collection link: two evidence chunks therefore require capacity, not truncation.
        val businessWrites = 3 + (if (observed is LocalPaymentOutcomeV3.Unknown) 0 else 1) +
            (if (observed is LocalPaymentOutcomeV3.CashReceived) 1 else 0)
        if (businessWrites + capture.requiredChangeCount() > LocalFinancialJournalProfileV3.MAX_FINANCIAL_WRITES)
            return pending(LocalJournalPendingReasonV3.STORAGE_CAPACITY_REQUIRED)
        link
    }
    val readEvidence = capture?.finish().orEmpty()
    return LocalJournalPrepared(context, retailApplicationAction(context, target.kind, phase), collection.id, collection.revision, slot.actorId,
        "financial.journal.payment.apply", slot.domain.amount, resources, retailApplicationBarriers(target.kind)) { command, now ->
        retailFinancialProposal(context, fact, now, preserveRetainedPending = capture != null) {
            val outcome = observed.journalOutcome(fact.fact.value.recordingActorId)
            val observationOperationId = target.factOperation(phase)
            val transition = if (supervised) Collections.applySupervisedRecordedOutcome(command, collection, session,
                attempt.id, observationOperationId, fact.fact.value.recordingActorId, parent?.fact?.value?.target?.recordingOperationId,
                parent?.fact?.value?.bodyDigest?.let(::PayloadDigest), outcome)
            else Collections.applyRecordedOutcome(command, collection, session, attempt.id, observationOperationId, outcome)
            val after = transition.value
            SalesSnapshotLinks.requireCollection(after.collection, cut.order.order.value, after.session)
            val payment = after.collection.attempts.single { it.id == attempt.id }.payment
            check(payment == null || payment.recordingOperationId == observationOperationId &&
                payment.originalOperationId == target.intentOperationId && payment.amount == slot.domain.amount) { "sales.journal.original_payment" }
            val movement = transition.effects.filterIsInstance<CashMovement>().singleOrNull()
            check(transition.command == command && transition.inventoryDependencies.isEmpty() && transition.versions == listOf(
                SalesVersionCheck(SalesAggregate.COLLECTION, collection.id, collection.revision),
                SalesVersionCheck(SalesAggregate.CASH_SESSION, session.id, session.revision)) &&
                transition.unique == listOfNotNull(payment?.let { SalesUniqueCheck(SalesUniqueKind.PAYMENT_RECORD, it.attemptId) },
                    movement?.let { SalesUniqueCheck(SalesUniqueKind.CASH_SOURCE, it.sourceId) }) &&
                transition.effects == listOf(CollectionChanged(after.collection)) +
                    listOfNotNull(if (after.session === session) null else CashSessionChanged(after.session)) +
                    listOf(CollectionObservationRecorded(attempt.id, outcome)) + listOfNotNull(payment, movement)) { "sales.journal.transition" }
            val document = collectionWrite(after.collection, cut.collection.stored)
            val changes = mutableListOf(document)
            val revisions = mutableListOf(LocalSalesRevision(LocalSalesStoreProfile.COLLECTION_KIND, collection.id, after.collection.revision))
            if (after.session !== session) {
                changes += cashWrite(after.session, cut.cash.session.stored)
                revisions += LocalSalesRevision(CashNonSaleStoreProfile.SESSION_KIND, session.id, after.session.revision)
            }
            if (movement != null) changes += if (supervised) supervisedRetailMovementWrite(context, fact, movement, now)
                else movementWrite(context, movement, now)
            if (oldLink != null) {
                changes += normalizedPaymentLink(context, document, cut.collection.stored, oldLink)
                changes += readEvidence
            }
            // All native lineage claims are permanent; only genuine financial uniqueness is acquired.
            val claims = listOfNotNull(payment?.let { ClaimChange.Acquire(paymentClaim, collection.id) },
                movement?.let { ClaimChange.Acquire(cashClaim, session.id) })
            val proof = retailApplicationProofV3(context.operationId, LocalSalesScope.from(context.scope),
                listOf("collection" to cut.collection.stored, "order" to cut.order.order.stored, "cash" to cut.cash.session.stored) +
                    priceSourcesBefore(cut.order) + returnPolicyCaptureBefore(cut.order), retailParentApplicationV3(parent))
            retailApplied(context, fact, now, retailPaymentAction(observed), revisions, changes, claims, proof)
        }
    }
}

private suspend fun prepareRetailRefundApplication(fact: LocalRetailJournalFactCutV3, parent: LocalRetailJournalFactCutV3?,
    cut: LocalReturnCut, cash: LocalCashCut, view: CommandReadView, context: CommandContext, supervised: Boolean): PreparedMutation {
    val slot = fact.slot.value
    val target = slot.target
    val accepted = cut.accepted.value
    val session = cash.session.value
    val phase = fact.fact.value.phase
    val attempt = accepted.attempts.singleOrNull { it.id == target.attemptId }
    val resources = retailApplicationResources(fact) + returnCashResources(cut, cash)
    if (attempt == null || accepted.saleId != target.originalSaleId || accepted.claimId != target.originalClaimId ||
        attempt.originalOperationId != target.intentOperationId || attempt.originalActorId != slot.actorId || session.custodianId != slot.actorId ||
        attempt.originalCashSessionId != target.sessionId || attempt.originalPaymentId != target.originalPaymentId ||
        attempt.amount != slot.domain.amount || tenderView(attempt.tender) != slot.domain.tender || attempt.status != retailPriorStatus(phase) ||
        attempt.id !in session.pendingAttemptIds || view.claim(LocalSalesStoreProfile.unique("refund_attempt", attempt.id)) != accepted.id ||
        (if (parent == null) attempt.latestObservation != null else attempt.latestObservation != retailRefundObservation(parent.fact.value).journalOutcome(parent.fact.value.recordingActorId))) {
        return pendingRetailPreparation(context, fact, LocalJournalPendingReasonV3.ORIGINAL_ATTEMPT_MISMATCH, resources)
    }
    val refundClaim = LocalSalesStoreProfile.unique("refund_record", attempt.id)
    val cashClaim = CashNonSaleStoreProfile.cashSourceClaim(attempt.id)
    if (view.claim(refundClaim) != null || view.claim(cashClaim) != null ||
        view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.MOVEMENT_KIND, attempt.id)) != null) {
        return pendingRetailPreparation(context, fact, LocalJournalPendingReasonV3.FINANCIAL_STATE_REQUIRES_RECONCILIATION, resources)
    }
    val observed = retailRefundObservation(fact.fact.value)
    return LocalJournalPrepared(context, retailApplicationAction(context, target.kind, phase), accepted.id, accepted.revision, slot.actorId,
        "financial.journal.refund.apply", slot.domain.amount, resources, retailApplicationBarriers(target.kind)) { command, now ->
        retailFinancialProposal(context, fact, now) {
            val outcome = observed.journalOutcome(fact.fact.value.recordingActorId)
            val observationOperationId = target.factOperation(phase)
            val transition = if (supervised) Returns.applySupervisedRecordedRefundOutcome(command, accepted, session,
                attempt.id, observationOperationId, fact.fact.value.recordingActorId, parent?.fact?.value?.target?.recordingOperationId,
                parent?.fact?.value?.bodyDigest?.let(::PayloadDigest), outcome)
            else Returns.applyRecordedRefundOutcome(command, accepted, session, attempt.id, observationOperationId, outcome)
            val after = transition.value
            SalesSnapshotLinks.requireAcceptedReturn(after.acceptedReturn, cut.original.original.entitlement.value, cut.original.claim.value)
            val refund = after.acceptedReturn.attempts.single { it.id == attempt.id }.refund
            check(refund == null || refund.recordingOperationId == observationOperationId &&
                refund.originalOperationId == target.intentOperationId && refund.amount == slot.domain.amount) { "sales.journal.original_refund" }
            val movement = transition.effects.filterIsInstance<CashMovement>().singleOrNull()
            check(transition.command == command && transition.inventoryDependencies.isEmpty() && transition.versions == listOf(
                SalesVersionCheck(SalesAggregate.ACCEPTED_RETURN, accepted.id, accepted.revision),
                SalesVersionCheck(SalesAggregate.CASH_SESSION, session.id, session.revision)) &&
                transition.unique == listOfNotNull(refund?.let { SalesUniqueCheck(SalesUniqueKind.REFUND_RECORD, it.attemptId) },
                    movement?.let { SalesUniqueCheck(SalesUniqueKind.CASH_SOURCE, it.sourceId) }) &&
                transition.effects == listOf(AcceptedReturnChanged(after.acceptedReturn)) +
                    listOfNotNull(if (after.session === session) null else CashSessionChanged(after.session)) +
                    listOf(RefundObservationRecorded(attempt.id, outcome)) + listOfNotNull(refund, movement)) { "sales.journal.transition" }
            val changes = mutableListOf(acceptedReturnWrite(after.acceptedReturn, cut.original.original.entitlement.value,
                cut.original.claim.value, cut.accepted.stored))
            val revisions = mutableListOf(LocalSalesRevision(LocalSalesStoreProfile.RETURN_KIND, accepted.id, after.acceptedReturn.revision))
            if (after.session !== session) {
                changes += cashWrite(after.session, cash.session.stored)
                revisions += LocalSalesRevision(CashNonSaleStoreProfile.SESSION_KIND, session.id, after.session.revision)
            }
            if (movement != null) changes += if (supervised) supervisedRetailMovementWrite(context, fact, movement, now)
                else movementWrite(context, movement, now)
            if (!after.acceptedReturn.unresolved && after.acceptedReturn.due.minor == 0L) {
                val (work, write) = changedWork(cut.work, returnIds = cut.work.value.returnIds - accepted.id)
                changes += write
                revisions += LocalSalesRevision(LocalSalesStoreProfile.WORK_KIND, work.id, work.revision)
            }
            val claims = listOfNotNull(refund?.let { ClaimChange.Acquire(refundClaim, accepted.id) },
                movement?.let { ClaimChange.Acquire(cashClaim, session.id) })
            val proof = retailApplicationProofV3(context.operationId, LocalSalesScope.from(context.scope),
                retailRefundBeforeRowsV3(cut, cash), retailParentApplicationV3(parent))
            retailApplied(context, fact, now, retailRefundAction(observed), revisions, changes, claims, proof)
        }
    }
}

private fun retailPriorStatus(phase: LocalJournalFactPhaseV3): AttemptStatus =
    if (phase == LocalJournalFactPhaseV3.ORIGINAL) AttemptStatus.AWAITING_ATTESTATION else AttemptStatus.OUTCOME_UNKNOWN
private fun retailApplicationResources(fact: LocalRetailJournalFactCutV3): Set<RecoveryResourceKey> =
    fact.slot.value.domain.financialResources.toSet() + retailJournalAuthorityResources(fact.slot.value.requirement())
private fun retailApplicationBarriers(kind: SalesRecordingKind): Set<RecoveryResourceKind> =
    setOf(RecoveryResourceKind.CASH_CUSTODY, RecoveryResourceKind.ORDER_CUSTODY) +
        if (kind == SalesRecordingKind.REFUND_ATTESTATION) setOf(RecoveryResourceKind.REFUND_ENTITLEMENT) else emptySet()

private fun pendingRetailPreparation(context: CommandContext, fact: LocalRetailJournalFactCutV3,
    reason: LocalJournalPendingReasonV3, resources: Set<RecoveryResourceKey> = retailApplicationResources(fact)): PreparedMutation =
    LocalJournalPrepared(context, retailApplicationAction(context, fact.slot.value.target.kind, fact.fact.value.phase), fact.slot.value.id,
        Revision(0), fact.slot.value.actorId, "financial.journal.pending", fact.slot.value.domain.amount, resources,
        retailApplicationBarriers(fact.slot.value.target.kind)) { _, now -> pendingRetailProposal(context, fact, now, reason) }

private fun capacityRetailApplicationPreparation(context: CommandContext, kind: SalesRecordingKind,
    operationId: OperationId, phase: LocalJournalFactPhaseV3): PreparedMutation = LocalJournalPrepared(context,
    retailApplicationAction(context, kind, phase), com.ukkera.pos.types.DomainId(operationId.value), Revision(0),
    context.originalActorId, "financial.journal.capacity_required", resources = setOf(recordingAuthority(context.originalActorId),
        recordingAuthority(com.ukkera.pos.types.DomainId(operationId.value)))) { _, _ -> retailUnavailable(LocalJournalUnavailableV3.CAPACITY_REQUIRED) }

private fun retailApplicationAction(context: CommandContext, kind: SalesRecordingKind,
    phase: LocalJournalFactPhaseV3): SecurityAction = retailFactAction(kind,
        if (context.key == LocalRetailSupervisorCodecV1.COMMAND) LocalJournalFactPhaseV3.RESOLUTION else phase)

/** Same immutable cash-fact schema; accounting actor is current, custody/evidence stay distinct. */
private fun supervisedRetailMovementWrite(context: CommandContext, fact: LocalRetailJournalFactCutV3,
    movement: CashMovement, now: UtcInstant): AggregateChange {
    val target = fact.slot.value.target
    requireRetailSupervisedApplication(context, target.kind, target.factOperation(fact.fact.value.phase), fact.fact.value.phase)
    check(movement.operationId == context.operationId && movement.sourceId == target.attemptId &&
        movement.sessionId == target.sessionId && movement.custodianId == fact.slot.value.actorId &&
        movement.evidence.actorId == fact.fact.value.recordingActorId &&
        movement.kind.name == (if (target.kind == SalesRecordingKind.PAYMENT_ATTESTATION) "CUSTOMER_RECEIPT" else "CUSTOMER_REFUND"))
    val value = RetailCashMovementProofV3(LocalSalesScope.from(context.scope), movement.sourceId, context.operationId,
        context.originalActorId, movement.sessionId, movement.kind.name, movement.delta, movement.cashReceived,
        movement.changeGiven, LocalCashEvidenceV1(movement.evidence.evidenceId, UtcInstant(movement.evidence.occurredAt)),
        movement.reason, now)
    return rootWrite(LocalSalesStoreProfile.MOVEMENT_KIND, movement.sourceId, value, RetailCashMovementProofV3.serializer(), null)
}

private fun pendingRetailProposal(context: CommandContext, fact: LocalRetailJournalFactCutV3, now: UtcInstant,
    reason: LocalJournalPendingReasonV3): ProposedOutcome {
    val bytes = LocalFinancialResultCodecV3.encode(retailPending(fact.slot.value.target, fact.fact.value.phase, reason))
    if (fact.application.value.revision.value >= Long.MAX_VALUE - 1) return ProposedOutcome.Reject("sales.journal.pending", bytes)
    val after = fact.application.value.copy(revision = fact.application.value.revision.next(), reason = reason,
        updatedAt = now, lastApplicationOperationId = context.operationId)
    return ProposedOutcome.Apply(bytes, listOf(retailApplicationWriteV3(after, fact.application.stored)))
}

private inline fun retailFinancialProposal(context: CommandContext, fact: LocalRetailJournalFactCutV3,
    now: UtcInstant, preserveRetainedPending: Boolean = false, proposal: () -> ProposedOutcome): ProposedOutcome = try {
    proposal()
} catch (_: SalesRuleViolation) {
    retailFinancialPending(context, fact, now, LocalJournalPendingReasonV3.FINANCIAL_STATE_REQUIRES_RECONCILIATION, preserveRetainedPending)
} catch (_: ArithmeticException) {
    retailFinancialPending(context, fact, now, LocalJournalPendingReasonV3.NUMERIC_CAPACITY_REQUIRED, preserveRetainedPending)
} catch (_: LocalSalesCapacityExceeded) {
    retailFinancialPending(context, fact, now, LocalJournalPendingReasonV3.STORAGE_CAPACITY_REQUIRED, preserveRetainedPending)
} catch (_: LocalSalesWireCapacityExceeded) {
    retailFinancialPending(context, fact, now, LocalJournalPendingReasonV3.STORAGE_CAPACITY_REQUIRED, preserveRetainedPending)
} catch (rejected: SalesSnapshotRejectedException) {
    if (rejected.failure == SalesSnapshotFailure.TOO_LARGE)
        retailFinancialPending(context, fact, now, LocalJournalPendingReasonV3.STORAGE_CAPACITY_REQUIRED, preserveRetainedPending)
    else throw rejected
}

private fun retailFinancialPending(context: CommandContext, fact: LocalRetailJournalFactCutV3, now: UtcInstant,
    reason: LocalJournalPendingReasonV3, preserveRetainedPending: Boolean): ProposedOutcome =
    // Once sealed, an Apply may not omit the kernel's evidence. A named late refusal records
    // a truthful pending RESULT only; the genuine raw fact and prior PENDING application remain.
    if (preserveRetainedPending) ProposedOutcome.Reject("sales.journal.pending",
        LocalFinancialResultCodecV3.encode(retailPending(fact.slot.value.target, fact.fact.value.phase, reason)))
    else pendingRetailProposal(context, fact, now, reason)

private fun retailApplied(context: CommandContext, fact: LocalRetailJournalFactCutV3, now: UtcInstant,
    action: LocalSalesAction, revisions: List<LocalSalesRevision>, changes: List<AggregateChange>, claims: List<ClaimChange>,
    proof: LocalRetailApplicationProofV3): ProposedOutcome.Apply {
    val after = fact.application.value.copy(revision = fact.application.value.revision.next(), state = LocalJournalApplicationStateV3.APPLIED,
        reason = null, updatedAt = now, lastApplicationOperationId = context.operationId, appliedAction = action,
        financialRevisions = revisions, financialProof = proof)
    val allChanges = changes + retailApplicationWriteV3(after, fact.application.stored)
    // Logical revision Long.MAX_VALUE - 1 already occupies the last physical storage
    // generation. Refuse before the kernel would attempt an overflowing next generation.
    if (allChanges.any { it.expectedRevision?.value == Long.MAX_VALUE }) throw ArithmeticException("sales.journal.revision_capacity")
    val result = LocalFinancialResultCodecV3.encode(LocalFinancialResultV3.Applied(fact.slot.value.target, fact.fact.value.phase,
        context.operationId, action, revisions))
    check(allChanges.none { it.key.kind in LocalFinancialJournalProfileV3.gatedKinds || it.key.kind == com.ukkera.pos.database.ReservedOperations.KIND } &&
        claims.none { it.key.namespace in LocalFinancialJournalProfileV3.gatedClaimNamespaces }) { "sales.journal.application_gated_effect" }
    if (allChanges.size > LocalFinancialJournalProfileV3.MAX_FINANCIAL_WRITES || claims.size > LocalFinancialJournalProfileV3.MAX_FINANCIAL_CLAIMS ||
        allChanges.any { it.payload.size > SalesRecordingBudget.MAX_ROW_BYTES } ||
        allChanges.sumOf { it.payload.size.toLong() } + result.size > LocalFinancialJournalProfileV3.MAX_FINANCIAL_APPLY_BYTES) throw LocalSalesCapacityExceeded()
    return ProposedOutcome.Apply(result, allChanges, claims)
}

internal fun retailPaymentObservation(fact: LocalRetailJournalFactV3): LocalPaymentOutcomeV3 = when (val body = LocalCollectionCommandCodecV3.decode(fact.body())) {
    is LocalCollectionRequestV3.RecordOriginal -> body.outcome
    is LocalCollectionRequestV3.RecordResolution -> body.outcome
    else -> error("sales.journal.payment_fact_subtype")
}
internal fun retailRefundObservation(fact: LocalRetailJournalFactV3): LocalRefundOutcomeV3 = when (val body = LocalReturnCommandCodecV3.decode(fact.body())) {
    is LocalReturnRequestV3.RecordOriginal -> body.outcome
    is LocalReturnRequestV3.RecordResolution -> body.outcome
    else -> error("sales.journal.refund_fact_subtype")
}
internal fun retailPaymentAction(value: LocalPaymentOutcomeV3): LocalSalesAction = when (value) {
    is LocalPaymentOutcomeV3.Unknown -> LocalSalesAction.PAYMENT_UNKNOWN
    is LocalPaymentOutcomeV3.NotPaid -> LocalSalesAction.PAYMENT_NOT_PAID
    is LocalPaymentOutcomeV3.CashReceived, is LocalPaymentOutcomeV3.ExternalAttested -> LocalSalesAction.PAYMENT_RECORDED
}
internal fun retailRefundAction(value: LocalRefundOutcomeV3): LocalSalesAction = when (value) {
    is LocalRefundOutcomeV3.Unknown -> LocalSalesAction.REFUND_UNKNOWN
    is LocalRefundOutcomeV3.NotPaid -> LocalSalesAction.REFUND_NOT_PAID
    is LocalRefundOutcomeV3.CashPaid, is LocalRefundOutcomeV3.ExternalAttested -> LocalSalesAction.REFUND_RECORDED
}
internal fun LocalPaymentOutcomeV3.journalOutcome(actor: com.ukkera.pos.types.DomainId): CollectionOutcome = when (this) {
    is LocalPaymentOutcomeV3.Unknown -> CollectionOutcome.Unknown(evidence.domain(actor))
    is LocalPaymentOutcomeV3.NotPaid -> CollectionOutcome.NotPaid(evidence.domain(actor), reason)
    is LocalPaymentOutcomeV3.CashReceived -> CollectionOutcome.CashReceived(received, change, evidence.domain(actor))
    is LocalPaymentOutcomeV3.ExternalAttested -> CollectionOutcome.ExternalAttested(ExternalTenderEvidence(evidence.domain(actor), terminal, reference))
}
internal fun LocalRefundOutcomeV3.journalOutcome(actor: com.ukkera.pos.types.DomainId): RefundOutcome = when (this) {
    is LocalRefundOutcomeV3.Unknown -> RefundOutcome.Unknown(evidence.domain(actor))
    is LocalRefundOutcomeV3.NotPaid -> RefundOutcome.NotPaid(evidence.domain(actor), reason)
    is LocalRefundOutcomeV3.CashPaid -> RefundOutcome.CashPaid(evidence.domain(actor))
    is LocalRefundOutcomeV3.ExternalAttested -> RefundOutcome.ExternalAttested(ExternalTenderEvidence(evidence.domain(actor), terminal, reference))
}
