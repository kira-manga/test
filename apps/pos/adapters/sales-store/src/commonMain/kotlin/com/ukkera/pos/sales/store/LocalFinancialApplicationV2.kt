package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateChange
import com.ukkera.pos.database.ClaimChange
import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.PreparedMutation
import com.ukkera.pos.database.ProposedOutcome
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.AttemptStatus
import com.ukkera.pos.sales.AcceptedReturnChanged
import com.ukkera.pos.sales.CashMovement
import com.ukkera.pos.sales.CashSessionChanged
import com.ukkera.pos.sales.CollectionChanged
import com.ukkera.pos.sales.CollectionObservationRecorded
import com.ukkera.pos.sales.CollectionOutcome
import com.ukkera.pos.sales.Collections
import com.ukkera.pos.sales.ExternalTenderEvidence
import com.ukkera.pos.sales.RefundOutcome
import com.ukkera.pos.sales.RefundObservationRecorded
import com.ukkera.pos.sales.Returns
import com.ukkera.pos.sales.SalesAggregate
import com.ukkera.pos.sales.SalesRuleViolation
import com.ukkera.pos.sales.SalesSnapshotFailure
import com.ukkera.pos.sales.SalesSnapshotLinks
import com.ukkera.pos.sales.SalesSnapshotRejectedException
import com.ukkera.pos.sales.SalesUniqueCheck
import com.ukkera.pos.sales.SalesUniqueKind
import com.ukkera.pos.sales.SalesVersionCheck
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

internal suspend fun preparePhysicalApplicationV2(kind: SalesRecordingKind, recordingOperationId: OperationId,
    view: CommandReadView, context: CommandContext): PreparedMutation {
    if (view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.PHYSICAL_FACT_KIND, DomainId(recordingOperationId.value))) == null) {
        return unavailablePhysicalFactV2(context)
    }
    val fact = readPhysicalFactV2(view, recordingOperationId)
    val slot = fact.slot.value
    if (slot.kind != kind || slot.actorId != context.originalActorId) return unavailablePhysicalFactV2(context)
    if (fact.application.value.state == LocalObservationApplicationStateV2.APPLIED) {
        return LocalSalesPrepared(context, SecurityAction.CASH_READ, slot.id, fact.application.value.revision,
            slot.actorId, "financial.fact.already_applied", resources = slot.authorityResources.toSet()) { _, _ ->
            ProposedOutcome.Reject("sales.observation.already_applied", LocalObservationResultCodecV2.encode(
                LocalObservationResultV2.AlreadyApplied(recordingOperationId, checkNotNull(fact.application.value.lastApplicationOperationId))))
        }
    }
    // The fact is already safely retained. Only the named SALES source exceptions
    // are translated; an arbitrary database/transaction/IO failure is never caught.
    return try {
        when (kind) {
            SalesRecordingKind.PAYMENT_ATTESTATION -> preparePaymentApplicationV2(fact, readCollection(view, slot.documentId), view, context)
            SalesRecordingKind.REFUND_ATTESTATION -> prepareRefundApplicationV2(fact,
                readAcceptedReturn(view, checkNotNull(slot.originalSaleId), checkNotNull(slot.originalClaimId), slot.documentId),
                LocalSalesStorage.cash(view, slot.cashSessionId), view, context)
        }
    } catch (unavailable: LocalSalesSourceUnavailable) {
        pendingPhysicalPreparationV2(context, fact, if (unavailable.failure == LocalSalesSourceFailure.MISSING)
            LocalObservationPendingReasonV2.FINANCIAL_SOURCE_MISSING else LocalObservationPendingReasonV2.FINANCIAL_SOURCE_INVALID)
    } catch (_: SalesSnapshotRejectedException) {
        pendingPhysicalPreparationV2(context, fact, LocalObservationPendingReasonV2.FINANCIAL_SOURCE_INVALID)
    }
}

private suspend fun preparePaymentApplicationV2(fact: LocalPhysicalFactCutV2, cut: LocalCollectionCut,
    view: CommandReadView, context: CommandContext): PreparedMutation {
    val slot = fact.slot.value
    val collection = cut.collection.value
    val session = cut.cash.session.value
    val attempt = collection.attempts.singleOrNull { it.id == slot.attemptId }
    val resources = physicalApplicationResourcesV2(fact) + collectionResources(cut)
    if (attempt == null || collection.cashSessionId != slot.cashSessionId || collection.collectorId != slot.actorId ||
        attempt.originalOperationId != slot.intentOperationId || attempt.originalActorId != slot.actorId ||
        attempt.originalCashSessionId != slot.cashSessionId || attempt.amount != slot.amount || tenderView(attempt.tender) != slot.tender ||
        attempt.status != physicalOriginalStatusV2(slot) || attempt.id !in session.pendingAttemptIds) {
        return pendingPhysicalPreparationV2(context, fact, LocalObservationPendingReasonV2.ORIGINAL_ATTEMPT_MISMATCH, resources)
    }
    val paymentClaim = LocalSalesStoreProfile.unique("payment_record", attempt.id)
    val cashClaim = CashNonSaleStoreProfile.cashSourceClaim(attempt.id)
    if (view.claim(paymentClaim) != null || view.claim(cashClaim) != null ||
        view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.MOVEMENT_KIND, attempt.id)) != null) {
        return pendingPhysicalPreparationV2(context, fact, LocalObservationPendingReasonV2.FINANCIAL_STATE_REQUIRES_RECONCILIATION, resources)
    }
    requireOriginalPhysicalIntentV2(view, slot.kind, slot.intentOperationId, slot.attemptId, slot.documentId,
        slot.cashSessionId, slot.actorId, slot.amount, slot.tender)
    val observed = checkNotNull(fact.fact.value.payment).outcome
    val action = if (attempt.status == AttemptStatus.OUTCOME_UNKNOWN) SecurityAction.COLLECTION_RESOLVE else SecurityAction.COLLECTION_ATTEST
    return LocalSalesPrepared(context, action, collection.id, collection.revision, slot.actorId,
        "financial.payment.apply", slot.amount, resources, physicalApplicationBarriersV2(slot.kind)) { command, now ->
        physicalFinancialProposalV2(context, fact, now) {
            val outcome = observed.historicalOutcome(slot.actorId)
            val transition = Collections.applyRecordedOutcome(command, collection, session, attempt.id,
                slot.recordingOperationId, outcome)
            val after = transition.value
            SalesSnapshotLinks.requireCollection(after.collection, cut.order.order.value, after.session)
            val payment = after.collection.attempts.single { it.id == attempt.id }.payment
            check(payment == null || payment.recordingOperationId == slot.recordingOperationId &&
                payment.originalOperationId == slot.intentOperationId && payment.amount == slot.amount) { "sales.physical.original_observation" }
            val movement = transition.effects.filterIsInstance<CashMovement>().singleOrNull()
            check(transition.command == command && transition.inventoryDependencies.isEmpty() && transition.versions == listOf(
                SalesVersionCheck(SalesAggregate.COLLECTION, collection.id, collection.revision),
                SalesVersionCheck(SalesAggregate.CASH_SESSION, session.id, session.revision)) &&
                transition.unique == listOfNotNull(payment?.let { SalesUniqueCheck(SalesUniqueKind.PAYMENT_RECORD, it.attemptId) },
                    movement?.let { SalesUniqueCheck(SalesUniqueKind.CASH_SOURCE, it.sourceId) }) &&
                transition.effects == listOf(CollectionChanged(after.collection)) +
                    listOfNotNull(if (after.session === session) null else CashSessionChanged(after.session)) +
                    listOf(CollectionObservationRecorded(attempt.id, outcome)) + listOfNotNull(payment, movement)) { "sales.physical.transition" }
            val changes = mutableListOf(collectionWrite(after.collection, cut.collection.stored))
            val revisions = mutableListOf(LocalSalesRevision(LocalSalesStoreProfile.COLLECTION_KIND, collection.id, after.collection.revision))
            if (after.session !== session) {
                changes += cashWrite(after.session, cut.cash.session.stored)
                revisions += LocalSalesRevision(CashNonSaleStoreProfile.SESSION_KIND, session.id, after.session.revision)
            }
            if (movement != null) changes += movementWrite(context, movement, now)
            val claims = listOf(ClaimChange.Release(physicalAttemptClaim(attempt.id), slot.id)) +
                listOfNotNull(payment?.let { ClaimChange.Acquire(paymentClaim, collection.id) },
                    movement?.let { ClaimChange.Acquire(cashClaim, session.id) })
            physicalAppliedV2(context, fact, now, when (observed) {
                is LocalPaymentOutcomeV2.Unknown -> LocalSalesAction.PAYMENT_UNKNOWN
                is LocalPaymentOutcomeV2.NotPaid -> LocalSalesAction.PAYMENT_NOT_PAID
                is LocalPaymentOutcomeV2.CashReceived, is LocalPaymentOutcomeV2.ExternalAttested -> LocalSalesAction.PAYMENT_RECORDED
            }, revisions, changes, claims)
        }
    }
}

private suspend fun prepareRefundApplicationV2(fact: LocalPhysicalFactCutV2, cut: LocalReturnCut, cash: LocalCashCut,
    view: CommandReadView, context: CommandContext): PreparedMutation {
    val slot = fact.slot.value
    val accepted = cut.accepted.value
    val session = cash.session.value
    val attempt = accepted.attempts.singleOrNull { it.id == slot.attemptId }
    val resources = physicalApplicationResourcesV2(fact) + setOf(entitlementResource(accepted.id), entitlementResource(accepted.claimId),
        entitlementResource(accepted.saleId), orderResource(cut.work.value.id), cashResource(session.id),
        cashResource(cash.drawer.value.id), cashResource(cash.custodian.value.id)) +
        priceSourcesResources(cut.original.original.sale.value.priceSourceReference) + cut.original.caseRecoveryResources
    if (attempt == null || accepted.saleId != slot.originalSaleId || accepted.claimId != slot.originalClaimId ||
        attempt.originalOperationId != slot.intentOperationId || attempt.originalActorId != slot.actorId || session.custodianId != slot.actorId ||
        attempt.originalCashSessionId != slot.cashSessionId || attempt.originalPaymentId != slot.originalPaymentId ||
        attempt.amount != slot.amount || tenderView(attempt.tender) != slot.tender || attempt.status != physicalOriginalStatusV2(slot) ||
        attempt.id !in session.pendingAttemptIds || view.claim(LocalSalesStoreProfile.unique("refund_attempt", attempt.id)) != accepted.id) {
        return pendingPhysicalPreparationV2(context, fact, LocalObservationPendingReasonV2.ORIGINAL_ATTEMPT_MISMATCH, resources)
    }
    val refundClaim = LocalSalesStoreProfile.unique("refund_record", attempt.id)
    val cashClaim = CashNonSaleStoreProfile.cashSourceClaim(attempt.id)
    if (view.claim(refundClaim) != null || view.claim(cashClaim) != null ||
        view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.MOVEMENT_KIND, attempt.id)) != null) {
        return pendingPhysicalPreparationV2(context, fact, LocalObservationPendingReasonV2.FINANCIAL_STATE_REQUIRES_RECONCILIATION, resources)
    }
    requireOriginalPhysicalIntentV2(view, slot.kind, slot.intentOperationId, slot.attemptId, slot.documentId,
        slot.cashSessionId, slot.actorId, slot.amount, slot.tender, slot.originalSaleId, slot.originalClaimId, slot.originalPaymentId)
    val observed = checkNotNull(fact.fact.value.refund).outcome
    val action = if (attempt.status == AttemptStatus.OUTCOME_UNKNOWN) SecurityAction.REFUND_RESOLVE else SecurityAction.REFUND_RECORD
    return LocalSalesPrepared(context, action, accepted.id, accepted.revision, slot.actorId,
        "financial.refund.apply", slot.amount, resources, physicalApplicationBarriersV2(slot.kind)) { command, now ->
        physicalFinancialProposalV2(context, fact, now) {
            val outcome = observed.historicalOutcome(slot.actorId)
            val transition = Returns.applyRecordedRefundOutcome(command, accepted, session, attempt.id,
                slot.recordingOperationId, outcome)
            val after = transition.value
            SalesSnapshotLinks.requireAcceptedReturn(after.acceptedReturn, cut.original.original.entitlement.value, cut.original.claim.value)
            val refund = after.acceptedReturn.attempts.single { it.id == attempt.id }.refund
            check(refund == null || refund.recordingOperationId == slot.recordingOperationId &&
                refund.originalOperationId == slot.intentOperationId && refund.amount == slot.amount) { "sales.physical.original_observation" }
            val movement = transition.effects.filterIsInstance<CashMovement>().singleOrNull()
            check(transition.command == command && transition.inventoryDependencies.isEmpty() && transition.versions == listOf(
                SalesVersionCheck(SalesAggregate.ACCEPTED_RETURN, accepted.id, accepted.revision),
                SalesVersionCheck(SalesAggregate.CASH_SESSION, session.id, session.revision)) &&
                transition.unique == listOfNotNull(refund?.let { SalesUniqueCheck(SalesUniqueKind.REFUND_RECORD, it.attemptId) },
                    movement?.let { SalesUniqueCheck(SalesUniqueKind.CASH_SOURCE, it.sourceId) }) &&
                transition.effects == listOf(AcceptedReturnChanged(after.acceptedReturn)) +
                    listOfNotNull(if (after.session === session) null else CashSessionChanged(after.session)) +
                    listOf(RefundObservationRecorded(attempt.id, outcome)) + listOfNotNull(refund, movement)) { "sales.physical.transition" }
            val changes = mutableListOf(acceptedReturnWrite(after.acceptedReturn, cut.original.original.entitlement.value,
                cut.original.claim.value, cut.accepted.stored))
            val revisions = mutableListOf(LocalSalesRevision(LocalSalesStoreProfile.RETURN_KIND, accepted.id, after.acceptedReturn.revision))
            if (after.session !== session) {
                changes += cashWrite(after.session, cash.session.stored)
                revisions += LocalSalesRevision(CashNonSaleStoreProfile.SESSION_KIND, session.id, after.session.revision)
            }
            if (movement != null) changes += movementWrite(context, movement, now)
            if (!after.acceptedReturn.unresolved && after.acceptedReturn.due.minor == 0L) {
                val (work, workWrite) = changedWork(cut.work, returnIds = cut.work.value.returnIds - accepted.id)
                changes += workWrite
                revisions += LocalSalesRevision(LocalSalesStoreProfile.WORK_KIND, work.id, work.revision)
            }
            val claims = listOf(ClaimChange.Release(physicalAttemptClaim(attempt.id), slot.id)) +
                listOfNotNull(refund?.let { ClaimChange.Acquire(refundClaim, accepted.id) }, movement?.let { ClaimChange.Acquire(cashClaim, session.id) })
            physicalAppliedV2(context, fact, now, when (observed) {
                is LocalRefundOutcomeV2.Unknown -> LocalSalesAction.REFUND_UNKNOWN
                is LocalRefundOutcomeV2.NotPaid -> LocalSalesAction.REFUND_NOT_PAID
                is LocalRefundOutcomeV2.CashPaid, is LocalRefundOutcomeV2.ExternalAttested -> LocalSalesAction.REFUND_RECORDED
            }, revisions, changes, claims)
        }
    }
}

private fun physicalOriginalStatusV2(slot: LocalPhysicalRecordingSlotV2): AttemptStatus =
    if (slot.resolving) AttemptStatus.OUTCOME_UNKNOWN else AttemptStatus.AWAITING_ATTESTATION

private fun physicalApplicationResourcesV2(fact: LocalPhysicalFactCutV2): Set<RecoveryResourceKey> =
    (fact.slot.value.authorityResources + fact.slot.value.financialResources).toSet()

/** No fresh operation ID/readmission permits replaying money already accounted for by reconciliation. */
private fun physicalApplicationBarriersV2(kind: SalesRecordingKind): Set<RecoveryResourceKind> =
    setOf(RecoveryResourceKind.CASH_CUSTODY, RecoveryResourceKind.ORDER_CUSTODY) +
        if (kind == SalesRecordingKind.REFUND_ATTESTATION) setOf(RecoveryResourceKind.REFUND_ENTITLEMENT) else emptySet()

private fun pendingPhysicalPreparationV2(context: CommandContext, fact: LocalPhysicalFactCutV2,
    reason: LocalObservationPendingReasonV2, resources: Set<RecoveryResourceKey> = physicalApplicationResourcesV2(fact)): PreparedMutation =
    LocalSalesPrepared(context, physicalFactActionV2(fact.slot.value), fact.slot.value.id, fact.slot.value.revision,
        fact.slot.value.actorId, "financial.fact.pending", fact.slot.value.amount, resources,
        physicalApplicationBarriersV2(fact.slot.value.kind)) { _, now -> pendingPhysicalProposalV2(context, fact, now, reason) }

private fun pendingPhysicalProposalV2(context: CommandContext, fact: LocalPhysicalFactCutV2, now: UtcInstant,
    reason: LocalObservationPendingReasonV2): ProposedOutcome {
    val result = LocalObservationResultCodecV2.encode(physicalPendingV2(fact.slot.value, reason))
    if (fact.application.value.revision.value >= Long.MAX_VALUE - 1) {
        return ProposedOutcome.Reject("sales.observation.pending", result) // Original durable fact and active claim remain.
    }
    val after = fact.application.value.copy(revision = fact.application.value.revision.next(), reason = reason,
        updatedAt = now, lastApplicationOperationId = context.operationId)
    return ProposedOutcome.Apply(result, listOf(physicalApplicationWriteV2(after, fact.application.stored)))
}

private inline fun physicalFinancialProposalV2(context: CommandContext, fact: LocalPhysicalFactCutV2,
    now: UtcInstant, proposal: () -> ProposedOutcome): ProposedOutcome = try {
    proposal()
} catch (_: SalesRuleViolation) {
    pendingPhysicalProposalV2(context, fact, now, LocalObservationPendingReasonV2.FINANCIAL_STATE_REQUIRES_RECONCILIATION)
} catch (_: ArithmeticException) {
    pendingPhysicalProposalV2(context, fact, now, LocalObservationPendingReasonV2.NUMERIC_CAPACITY_REQUIRED)
} catch (_: LocalSalesCapacityExceeded) {
    pendingPhysicalProposalV2(context, fact, now, LocalObservationPendingReasonV2.STORAGE_CAPACITY_REQUIRED)
} catch (_: LocalSalesWireCapacityExceeded) {
    pendingPhysicalProposalV2(context, fact, now, LocalObservationPendingReasonV2.STORAGE_CAPACITY_REQUIRED)
} catch (rejected: SalesSnapshotRejectedException) {
    if (rejected.failure == SalesSnapshotFailure.TOO_LARGE)
        pendingPhysicalProposalV2(context, fact, now, LocalObservationPendingReasonV2.STORAGE_CAPACITY_REQUIRED)
    else throw rejected
}

private fun physicalAppliedV2(context: CommandContext, fact: LocalPhysicalFactCutV2, now: UtcInstant,
    action: LocalSalesAction, revisions: List<LocalSalesRevision>, changes: List<AggregateChange>, claims: List<ClaimChange>): ProposedOutcome.Apply {
    val after = fact.application.value.copy(revision = fact.application.value.revision.next(), state = LocalObservationApplicationStateV2.APPLIED,
        reason = null, updatedAt = now, lastApplicationOperationId = context.operationId, appliedAction = action, financialRevisions = revisions)
    val allChanges = changes + physicalApplicationWriteV2(after, fact.application.stored)
    val result = LocalObservationResultCodecV2.encode(LocalObservationResultV2.Applied(fact.slot.value.recordingOperationId,
        context.operationId, fact.slot.value.documentId, fact.slot.value.attemptId, action, revisions))
    if (allChanges.size > SalesPhysicalRecordingBudgetV2.MAX_FINANCIAL_WRITES || claims.size > SalesPhysicalRecordingBudgetV2.MAX_FINANCIAL_CLAIMS ||
        allChanges.any { it.payload.size > SalesPhysicalRecordingBudgetV2.MAX_FINANCIAL_ROW_BYTES } ||
        allChanges.sumOf { it.payload.size.toLong() } + result.size > SalesPhysicalRecordingBudgetV2.MAX_FINANCIAL_APPLY_BYTES) {
        throw LocalSalesCapacityExceeded()
    }
    return ProposedOutcome.Apply(result, allChanges, claims)
}

private fun LocalPaymentOutcomeV2.historicalOutcome(actor: DomainId): CollectionOutcome = when (this) {
    is LocalPaymentOutcomeV2.Unknown -> CollectionOutcome.Unknown(evidence.domain(actor))
    is LocalPaymentOutcomeV2.NotPaid -> CollectionOutcome.NotPaid(evidence.domain(actor), reason)
    is LocalPaymentOutcomeV2.CashReceived -> CollectionOutcome.CashReceived(received, change, evidence.domain(actor))
    is LocalPaymentOutcomeV2.ExternalAttested -> CollectionOutcome.ExternalAttested(ExternalTenderEvidence(evidence.domain(actor), terminal, reference))
}

private fun LocalRefundOutcomeV2.historicalOutcome(actor: DomainId): RefundOutcome = when (this) {
    is LocalRefundOutcomeV2.Unknown -> RefundOutcome.Unknown(evidence.domain(actor))
    is LocalRefundOutcomeV2.NotPaid -> RefundOutcome.NotPaid(evidence.domain(actor), reason)
    is LocalRefundOutcomeV2.CashPaid -> RefundOutcome.CashPaid(evidence.domain(actor))
    is LocalRefundOutcomeV2.ExternalAttested -> RefundOutcome.ExternalAttested(ExternalTenderEvidence(evidence.domain(actor), terminal, reference))
}
