package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateChange
import com.ukkera.pos.database.ClaimChange
import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.MasterCommandHandler
import com.ukkera.pos.database.PreparedMutation
import com.ukkera.pos.database.ReservedOperations
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.sales.AcceptedReturnChanged
import com.ukkera.pos.sales.AttemptStatus
import com.ukkera.pos.sales.CashMovement
import com.ukkera.pos.sales.CashSessionChanged
import com.ukkera.pos.sales.CashSessionStatus
import com.ukkera.pos.sales.ExternalTenderEvidence
import com.ukkera.pos.sales.ReceiptEntitlementChanged
import com.ukkera.pos.sales.RefundAttempt
import com.ukkera.pos.sales.RefundObservationRecorded
import com.ukkera.pos.sales.RefundOutcome
import com.ukkera.pos.sales.ReturnClaimChanged
import com.ukkera.pos.sales.ReturnDisposition
import com.ukkera.pos.sales.Returns
import com.ukkera.pos.sales.SalesAggregate
import com.ukkera.pos.sales.SalesRule
import com.ukkera.pos.sales.SalesSnapshotLinks
import com.ukkera.pos.sales.SalesUniqueCheck
import com.ukkera.pos.sales.SalesUniqueKind
import com.ukkera.pos.sales.SalesVersionCheck
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.PayloadDigest

/**
 * Local legal intake, original entitlement consumption and actual refund observations.
 * Approval/disposition never restores stock or executes a bank/payment operation.
 */
public class LocalReturnHandler(
    private val policySource: TransactionSalesReturnPolicySource,
    private val recordingCapacity: TransactionSalesRecordingCapacity,
) : MasterCommandHandler {
    override public val key: CommandKey get() = LocalSalesStoreProfile.RETURN_COMMAND
    override public val writableKinds: Set<String> = setOf(LocalSalesStoreProfile.RETURN_INTAKE_KIND,
        LocalSalesStoreProfile.RETURN_CLAIM_KIND, LocalSalesStoreProfile.ENTITLEMENT_KIND, LocalSalesStoreProfile.RETURN_KIND,
        LocalSalesStoreProfile.WORK_KIND, CashNonSaleStoreProfile.SESSION_KIND, LocalSalesStoreProfile.MOVEMENT_KIND,
        LocalSalesStoreProfile.RECORDING_SLOT_KIND, ReservedOperations.KIND)
    override public val claimNamespaces: Set<String> = setOf(LocalSalesStoreProfile.UNIQUE_NAMESPACE,
        CashNonSaleStoreProfile.CASH_SOURCE_NAMESPACE, LocalSalesStoreProfile.RECORDING_OPERATION_NAMESPACE,
        LocalSalesStoreProfile.RECORDING_ALLOCATION_NAMESPACE, LocalSalesStoreProfile.RECORDING_ATTEMPT_NAMESPACE)
    override public fun normalize(input: StoredBytes): StoredBytes = LocalReturnCommandCodec.normalize(input)
    override public fun reservedOperationBinding(normalized: StoredBytes): PayloadDigest? =
        (LocalReturnCommandCodec.decode(normalized) as? LocalReturnRequestV1.RecordRefundOutcome)?.let {
            salesRecordingTargetBinding(SalesRecordingKind.REFUND_ATTESTATION, it.returnId, it.sessionId, it.attemptId, it.intentOperationId)
        }

    override public suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        check(context.scope == view.scope && context.key == key &&
            semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest) { "sales.context.invalid" }
        return when (val request = LocalReturnCommandCodec.decode(normalized)) {
            is LocalReturnRequestV1.RecordClaim -> intake(request, view, context)
            is LocalReturnRequestV1.BindClaim -> bind(request, view, context)
            is LocalReturnRequestV1.Accept -> acceptReturn(request, view, context)
            is LocalReturnRequestV1.StartRefund -> startRefund(request, view, context)
            is LocalReturnRequestV1.ReserveRefundRecording -> reserveRecording(request, view, context)
            is LocalReturnRequestV1.RecordRefundOutcome -> refundOutcome(request, view, context)
        }
    }

    private suspend fun intake(request: LocalReturnRequestV1.RecordClaim, view: CommandReadView, context: CommandContext): PreparedMutation {
        val work = LocalSalesStorage.work(view, context.originalActorId)
        val claim = LocalSalesStoreProfile.unique("return_intake", request.claimId)
        val exists = view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.RETURN_INTAKE_KIND, request.claimId)) != null ||
            view.claim(claim) != null ||
            view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.RETURN_CLAIM_KIND, request.claimId)) != null ||
            view.claim(LocalSalesStoreProfile.unique("return_claim", request.claimId)) != null ||
            view.aggregate(LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.CASE, request.claimId)) != null ||
            view.claim(LocalReturnCaseProfileV1.source("case", request.claimId)) != null ||
            view.claim(LocalReturnCaseProfileV1.source("claim", request.claimId)) != null
        return LocalSalesPrepared(context, SecurityAction.RETURN_CLAIM, request.claimId, Revision(0), context.originalActorId,
            "return.claim.intake", resources = setOf(entitlementResource(request.claimId), orderResource(work.value.id))) { _, now ->
            if (exists) return@LocalSalesPrepared salesRejected(SalesRule.DUPLICATE_SOURCE_ALLOCATION)
            val value = LocalReturnIntake(LocalSalesScope.from(context.scope), request.claimId, Revision(0),
                context.originalActorId, context.operationId, now, request.facts)
            val (afterWork, workWrite) = changedWork(work, pendingClaimIds = work.value.pendingClaimIds + value.claimId)
            salesApplied(LocalSalesAction.RETURN_CLAIM_PENDING, value.claimId,
                listOf(LocalSalesRevision(LocalSalesStoreProfile.RETURN_INTAKE_KIND, value.claimId, value.revision),
                    LocalSalesRevision(LocalSalesStoreProfile.WORK_KIND, afterWork.id, afterWork.revision)),
                listOf(rootWrite(LocalSalesStoreProfile.RETURN_INTAKE_KIND, value.claimId, value, LocalReturnIntake.serializer(), null), workWrite),
                listOf(ClaimChange.Acquire(claim, value.originalActorId)))
        }
    }

    private suspend fun bind(request: LocalReturnRequestV1.BindClaim, view: CommandReadView, context: CommandContext): PreparedMutation {
        val intake = readIntake(view, request.claimId)
        val work = LocalSalesStorage.work(view, intake.value.originalActorId)
        val facts = intake.value.facts
        val original = facts.saleId?.let { findEntitlement(view, it) }
        val source = if (original == null) null else policySource.current(view, original.sale.value.id)
        check(source == null || source.scope == view.scope) { "sales.return.policy_scope" }
        val unique = LocalSalesStoreProfile.unique("return_claim", request.claimId)
        val exists = view.claim(unique) != null || view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.RETURN_CLAIM_KIND, request.claimId)) != null
        return LocalSalesPrepared(context, SecurityAction.RETURN_CLAIM, request.claimId, Revision(0), context.originalActorId,
            "return.claim.bind", resources = setOf(entitlementResource(request.claimId), orderResource(work.value.id)) +
                listOfNotNull(facts.saleId?.let(::entitlementResource)) + priceSourcesResources(original?.sale?.value?.priceSourceReference) +
                entitlementReturnPolicyResources(original) + entitlementSourceResources(original) +
                (source?.recoveryResources ?: emptySet())) { command, now ->
            if (context.originalActorId != intake.value.originalActorId) return@LocalSalesPrepared salesRejected(SalesRule.OWNER_REQUIRED)
            if (intake.value.revision != request.expectedIntakeRevision) return@LocalSalesPrepared salesRejected(SalesRule.REVISION_CONFLICT)
            if (intake.value.boundSaleId != null || exists) return@LocalSalesPrepared salesRejected(SalesRule.IMMUTABLE_RECORD)
            if (original == null || facts.portionId == null || facts.quantity == null || source == null) {
                return@LocalSalesPrepared salesUnavailable(LocalSalesUnavailable.SOURCE_REQUIRED)
            }
            val actorEvidence = facts.evidence.domain(intake.value.originalActorId)
            val evidence = source.evidence(facts, original.sale.value, actorEvidence, now)
                ?: return@LocalSalesPrepared salesUnavailable(LocalSalesUnavailable.SOURCE_REQUIRED)
            // A binding operation may qualify original facts, never amend them to make a claim eligible.
            check(evidence.claimedAt == facts.claimedAt.value && evidence.reason == facts.reason && evidence.evidence == actorEvidence) {
                "sales.return.policy_evidence"
            }
            val transition = Returns.recordClaim(command, original.entitlement.value, request.expectedEntitlementRevision,
                request.claimId, facts.portionId, facts.quantity, evidence)
            val claim = transition.value
            check(transition.command == command && transition.inventoryDependencies.isEmpty() &&
                transition.versions == listOf(entitlementVersion(original)) &&
                transition.unique == listOf(SalesUniqueCheck(SalesUniqueKind.RETURN_CLAIM, claim.id)) &&
                transition.effects == listOf(ReturnClaimChanged(claim))) { "sales.transition.invalid" }
            val afterIntake = intake.value.copy(revision = intake.value.revision.next(), boundSaleId = original.sale.value.id)
            val (afterWork, workWrite) = changedWork(work, pendingClaimIds = work.value.pendingClaimIds - request.claimId)
            salesApplied(LocalSalesAction.RETURN_CLAIMED, claim.id,
                listOf(LocalSalesRevision(LocalSalesStoreProfile.RETURN_INTAKE_KIND, claim.id, afterIntake.revision),
                    LocalSalesRevision(LocalSalesStoreProfile.RETURN_CLAIM_KIND, claim.id, claim.revision),
                    LocalSalesRevision(LocalSalesStoreProfile.WORK_KIND, afterWork.id, afterWork.revision)),
                listOf(rootWrite(LocalSalesStoreProfile.RETURN_INTAKE_KIND, claim.id, afterIntake, LocalReturnIntake.serializer(), intake.stored),
                    claimWrite(claim, null), workWrite), listOf(ClaimChange.Acquire(unique, claim.saleId)))
        }
    }

    private suspend fun acceptReturn(request: LocalReturnRequestV1.Accept, view: CommandReadView, context: CommandContext): PreparedMutation {
        val cut = readClaim(view, request.saleId, request.claimId)
        val work = LocalSalesStorage.work(view, context.originalActorId)
        val acceptsWork = acceptsNewWork(view, context.originalActorId)
        val entitlement = cut.original.entitlement.value
        val claim = cut.claim.value
        val cursor = entitlement.cursors.singleOrNull { it.portionId == claim.portionId }
        val validQuantity = cursor != null && claim.quantity.unitId == cursor.originalQuantity.unitId &&
            claim.quantity.scale == cursor.originalQuantity.scale && claim.quantity.scaled > 0 && claim.quantity <= cursor.remainingQuantity
        val amount = if (validQuantity) checkNotNull(cursor).consume(claim.quantity).payable else entitlement.sale.quote.payable
        val uniqueReturn = LocalSalesStoreProfile.unique("accepted_return", request.returnId)
        val uniqueClaim = LocalSalesStoreProfile.unique("claim_acceptance", request.claimId)
        val claimSource = LocalSalesStoreProfile.unique("return_claim_source", request.returnId)
        val exists = view.claim(uniqueReturn) != null || view.claim(uniqueClaim) != null || view.claim(claimSource) != null ||
            view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.RETURN_KIND, request.returnId)) != null
        return LocalSalesPrepared(context, SecurityAction.RETURN_ACCEPT, request.saleId, request.expectedEntitlementRevision,
            context.originalActorId, "return.accept", amount,
            setOf(entitlementResource(request.saleId), entitlementResource(request.claimId), entitlementResource(request.returnId), orderResource(work.value.id)) +
                priceSourcesResources(entitlement.sale.priceSourceReference) + cut.caseRecoveryResources +
                entitlementReturnPolicyResources(cut.original)) { command, _ ->
            // New case claims must consume their exact qualified decision and update their own work index.
            // The old command may replay its history, but may not bypass that new atomic contract.
            // A linked normalized entitlement also requires a contribution producer; v1 has none.
            if (cut.intake == null || cut.original.normalizedSource?.locator != null)
                return@LocalSalesPrepared salesRejected(SalesRule.IMMUTABLE_RECORD)
            if (exists) return@LocalSalesPrepared salesRejected(SalesRule.DUPLICATE_SOURCE_ALLOCATION)
            if (!acceptsWork) return@LocalSalesPrepared salesRejected(SalesRule.CLOSING_BLOCKED)
            if (!validQuantity) return@LocalSalesPrepared salesRejected(SalesRule.RETURN_ENTITLEMENT_EXHAUSTED)
            val transition = Returns.accept(command, entitlement, request.expectedEntitlementRevision, claim, request.expectedClaimRevision,
                request.returnId, ReturnDisposition.valueOf(request.disposition.name), request.inspectionEvidence.domain(context.originalActorId))
            val after = transition.value
            check(transition.command == command && transition.inventoryDependencies.isEmpty() &&
                transition.versions == listOf(entitlementVersion(cut.original), SalesVersionCheck(SalesAggregate.RETURN_CLAIM, claim.id, claim.revision)) &&
                transition.unique == listOf(SalesUniqueCheck(SalesUniqueKind.ACCEPTED_RETURN, after.acceptedReturn.id),
                    SalesUniqueCheck(SalesUniqueKind.CLAIM_ACCEPTANCE, claim.id)) &&
                transition.effects == listOf(ReceiptEntitlementChanged(after.entitlement), ReturnClaimChanged(after.claim), AcceptedReturnChanged(after.acceptedReturn))) {
                "sales.transition.invalid"
            }
            SalesSnapshotLinks.requireAcceptedReturn(after.acceptedReturn, after.entitlement, after.claim)
            val workChange = if (after.acceptedReturn.due.minor == 0L) null else changedWork(work, returnIds = work.value.returnIds + request.returnId)
            salesApplied(LocalSalesAction.RETURN_ACCEPTED, request.returnId,
                listOf(LocalSalesRevision(LocalSalesStoreProfile.ENTITLEMENT_KIND, request.saleId, after.entitlement.revision),
                    LocalSalesRevision(LocalSalesStoreProfile.RETURN_CLAIM_KIND, claim.id, after.claim.revision),
                    LocalSalesRevision(LocalSalesStoreProfile.RETURN_KIND, request.returnId, after.acceptedReturn.revision)) +
                    listOfNotNull(workChange?.first?.let { LocalSalesRevision(LocalSalesStoreProfile.WORK_KIND, it.id, it.revision) }),
                listOf(entitlementWrite(after.entitlement, cut.original.entitlement.stored), claimWrite(after.claim, cut.claim.stored),
                    acceptedReturnWrite(after.acceptedReturn, after.entitlement, after.claim, null)) + listOfNotNull(workChange?.second),
                listOf(ClaimChange.Acquire(uniqueReturn, request.saleId), ClaimChange.Acquire(uniqueClaim, request.returnId),
                    ClaimChange.Acquire(claimSource, request.claimId)), after.acceptedReturn.originalPayable)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    internal suspend fun startV2(request: LocalReturnRequestV2.StartRefund, view: CommandReadView, context: CommandContext,
        capacity: TransactionSalesPhysicalRecordingCapacityV2): PreparedMutation = startRefund(request.intentV1(), view, context)

    /** Original v1/v2 operation/body identity is preserved; only a new physical start is refused. */
    private suspend fun startRefund(request: LocalReturnRequestV1.StartRefund, view: CommandReadView, context: CommandContext): PreparedMutation {
        val cut = readAcceptedReturn(view, request.saleId, request.claimId, request.returnId)
        val cash = LocalSalesStorage.cash(view, request.sessionId)
        return legacyJournalRequired(context, SecurityAction.REFUND_RECORD, request.returnId,
            request.expectedReturnRevision, cash.session.value.custodianId, request.amount, returnCashResources(cut, cash))
    }

    @Suppress("UNUSED_PARAMETER")
    internal suspend fun reserveV2(request: LocalReturnRequestV2.ReserveRefundRecording, view: CommandReadView, context: CommandContext,
        capacity: TransactionSalesPhysicalRecordingCapacityV2): PreparedMutation = reserveRecording(
        LocalReturnRequestV1.ReserveRefundRecording(request.saleId, request.claimId, request.returnId, request.expectedReturnRevision,
            request.sessionId, request.expectedSessionRevision, request.attemptId, request.recordingOperationId), view, context)

    /** Recovery must use the exact old retained slot; it may not acquire another legacy allocation. */
    private suspend fun reserveRecording(request: LocalReturnRequestV1.ReserveRefundRecording, view: CommandReadView, context: CommandContext): PreparedMutation {
        val cut = readAcceptedReturn(view, request.saleId, request.claimId, request.returnId)
        val cash = LocalSalesStorage.cash(view, request.sessionId)
        val attempt = requireRefundAttempt(view, cut, cash, request.attemptId)
        return legacyJournalRequired(context,
            if (attempt.status == AttemptStatus.OUTCOME_UNKNOWN) SecurityAction.REFUND_RESOLVE else SecurityAction.REFUND_RECORD,
            request.returnId, request.expectedReturnRevision, attempt.originalActorId, attempt.amount, returnCashResources(cut, cash))
    }

    private suspend fun refundOutcome(request: LocalReturnRequestV1.RecordRefundOutcome, view: CommandReadView, context: CommandContext): PreparedMutation {
        val cut = readAcceptedReturn(view, request.saleId, request.claimId, request.returnId)
        val cash = LocalSalesStorage.cash(view, request.sessionId)
        val attempt = requireRefundAttempt(view, cut, cash, request.attemptId)
        check(request.intentOperationId == attempt.originalOperationId) { "sales.refund.intent" }
        requireLocalSalesSource(originalRefundRecordingProtocol(view, request.saleId, request.claimId, request.returnId, attempt) ==
            LocalSalesRecordingProtocol.LEGACY_V1)
        val slot = readRecordingSlot(view, context, SalesRecordingKind.REFUND_ATTESTATION, attempt.originalOperationId,
            request.attemptId, request.returnId, request.sessionId)
        requireLegacyRecordingProducer(view, slot, attempt.amount, request.saleId, request.claimId)
        val paidClaim = LocalSalesStoreProfile.unique("refund_record", request.attemptId)
        val cashClaim = CashNonSaleStoreProfile.cashSourceClaim(request.attemptId)
        check(view.claim(paidClaim) == null && view.claim(cashClaim) == null &&
            view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.MOVEMENT_KIND, request.attemptId)) == null) { "sales.refund.source" }
        val action = if (attempt.status == AttemptStatus.OUTCOME_UNKNOWN) SecurityAction.REFUND_RESOLVE else SecurityAction.REFUND_RECORD
        return LocalSalesPrepared(context, action, request.returnId, request.expectedReturnRevision, attempt.originalActorId,
            "refund.observe", attempt.amount, returnCashResources(cut, cash)) { command, now ->
            val outcome = request.outcome.domain(context.originalActorId)
            val transition = Returns.recordRefundOutcome(command, cut.accepted.value, request.expectedReturnRevision,
                cash.session.value, request.expectedSessionRevision, request.attemptId, outcome)
            val after = transition.value
            val updated = after.acceptedReturn.attempts.single { it.id == request.attemptId }
            val refund = updated.refund
            val movement = transition.effects.filterIsInstance<CashMovement>().singleOrNull()
            check(transition.command == command && transition.inventoryDependencies.isEmpty() && transition.versions == refundVersions(cut, cash) &&
                transition.unique == listOfNotNull(refund?.let { SalesUniqueCheck(SalesUniqueKind.REFUND_RECORD, it.attemptId) },
                    movement?.let { SalesUniqueCheck(SalesUniqueKind.CASH_SOURCE, it.sourceId) }) &&
                transition.effects == listOf(AcceptedReturnChanged(after.acceptedReturn)) +
                    listOfNotNull(if (cash.session.value === after.session) null else CashSessionChanged(after.session)) +
                    listOf(RefundObservationRecorded(request.attemptId, outcome)) + listOfNotNull(refund, movement)) { "sales.transition.invalid" }
            val changes = mutableListOf<AggregateChange>(acceptedReturnWrite(after.acceptedReturn, cut.original.original.entitlement.value,
                cut.original.claim.value, cut.accepted.stored), consumeRecordingSlot(slot, now))
            val revisions = mutableListOf(LocalSalesRevision(LocalSalesStoreProfile.RETURN_KIND, request.returnId, after.acceptedReturn.revision),
                LocalSalesRevision(LocalSalesStoreProfile.RECORDING_SLOT_KIND, slot.value.id, slot.value.revision.next()))
            if (after.session !== cash.session.value) {
                changes += cashWrite(after.session, cash.session.stored)
                revisions += LocalSalesRevision(CashNonSaleStoreProfile.SESSION_KIND, request.sessionId, after.session.revision)
            }
            if (movement != null) changes += movementWrite(context, movement, now)
            if (!after.acceptedReturn.unresolved && after.acceptedReturn.due.minor == 0L) {
                // The accepting operator retains the obligation; payout by another cashier is not reassignment.
                val (work, workWrite) = changedWork(cut.work, returnIds = cut.work.value.returnIds - request.returnId)
                changes += workWrite
                revisions += LocalSalesRevision(LocalSalesStoreProfile.WORK_KIND, work.id, work.revision)
            }
            val claims = listOf(releaseRecordingSlot(slot)) + listOfNotNull(refund?.let { ClaimChange.Acquire(paidClaim, request.returnId) },
                movement?.let { ClaimChange.Acquire(cashClaim, request.sessionId) })
            observedApplied(when (request.outcome) {
                is LocalRefundOutcomeV1.Unknown -> LocalSalesAction.REFUND_UNKNOWN
                is LocalRefundOutcomeV1.NotPaid -> LocalSalesAction.REFUND_NOT_PAID
                is LocalRefundOutcomeV1.CashPaid, is LocalRefundOutcomeV1.ExternalAttested -> LocalSalesAction.REFUND_RECORDED
            }, request.returnId, revisions, changes, claims, attempt.amount)
        }
    }
}

private fun entitlementVersion(cut: LocalEntitlementCut): SalesVersionCheck =
    SalesVersionCheck(SalesAggregate.RETURN_ENTITLEMENT, cut.entitlement.value.saleId, cut.entitlement.value.revision)

internal fun refundVersions(cut: LocalReturnCut, cash: LocalCashCut): List<SalesVersionCheck> = listOf(
    SalesVersionCheck(SalesAggregate.ACCEPTED_RETURN, cut.accepted.value.id, cut.accepted.value.revision),
    SalesVersionCheck(SalesAggregate.CASH_SESSION, cash.session.value.id, cash.session.value.revision))

internal fun returnCashResources(cut: LocalReturnCut, cash: LocalCashCut): Set<com.ukkera.pos.recovery.RecoveryResourceKey> =
    setOf(entitlementResource(cut.accepted.value.id), entitlementResource(cut.original.claim.value.id),
        entitlementResource(cut.original.original.entitlement.value.saleId), orderResource(cut.work.value.id),
        cashResource(cash.session.value.id), cashResource(cash.drawer.value.id), cashResource(cash.custodian.value.id)) +
        priceSourcesResources(cut.original.original.sale.value.priceSourceReference) + cut.original.caseRecoveryResources +
        entitlementReturnPolicyResources(cut.original.original)

internal fun AttemptStatus.unresolved(): Boolean = this == AttemptStatus.AWAITING_ATTESTATION || this == AttemptStatus.OUTCOME_UNKNOWN

private suspend fun requireRefundAttempt(view: CommandReadView, cut: LocalReturnCut, cash: LocalCashCut, attemptId: DomainId): RefundAttempt {
    val attempt = cut.accepted.value.attempts.singleOrNull { it.id == attemptId } ?: error("sales.refund.attempt")
    check(view.claim(LocalSalesStoreProfile.unique("refund_attempt", attemptId)) == cut.accepted.value.id &&
        attempt.originalCashSessionId == cash.session.value.id && attempt.originalActorId == cash.session.value.custodianId &&
        (attemptId in cash.session.value.pendingAttemptIds) == attempt.status.unresolved()) { "sales.refund.attempt" }
    return attempt
}

private fun LocalRefundOutcomeV1.domain(actor: DomainId): RefundOutcome = when (this) {
    is LocalRefundOutcomeV1.Unknown -> RefundOutcome.Unknown(evidence.domain(actor))
    is LocalRefundOutcomeV1.NotPaid -> RefundOutcome.NotPaid(evidence.domain(actor), reason)
    is LocalRefundOutcomeV1.CashPaid -> RefundOutcome.CashPaid(evidence.domain(actor))
    is LocalRefundOutcomeV1.ExternalAttested -> RefundOutcome.ExternalAttested(ExternalTenderEvidence(evidence.domain(actor), terminal, reference))
}
