package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** Exact deterministic first refusal of installed FOOD payout accounting. Literal source,
 * raw/native custody, every actual read budget and the attempted output encoding precede
 * a returned proof. No handler, authorization, physical capability or current row is made. */
internal class LocalFoodRefundApplicationFirstRefusal private constructor(
    private val original: RetainedCommandEvidence,
    private val read: () -> FoodFinancialBeforeReadBudget,
) {
    constructor(command: RuntimeHistoricalCommand) : this(
        (command.original as? RetainedOriginalV2.Legacy)?.evidence ?: error("food.refund.before_legacy_required"),
        { FoodFinancialBeforeReadBudget(command) })
    constructor(sources: RuntimeHistoricalFinancialSources, view: CommandReadView, stock: LocalStockRepository) :
        this(sources.evidence, { FoodFinancialBeforeReadBudget(sources, view, stock) })
    internal data class Proof(val proposal: ProposedOutcome.Reject, val requirement: AuthorizationRequirement,
        val resources: Set<RecoveryResourceKey>)

    suspend fun prove(): Proof? {
        check(!original.committed && original.nativeOriginalProvenance == null && original.command in foodRefundCommands &&
            original.aggregateWrites.isEmpty() && original.claimChanges.isEmpty() && original.jobs.isEmpty())
        val selected = foodRefundApplySelection(original.command, original.normalized)
        val phase = selected.phase
        val rawKey = if (phase == LocalFoodRefundFactPhase.ORIGINAL) foodRefundRawKey(selected.factOperationId)
            else foodRefundResolutionKey(selected.factOperationId)
        val first = read()
        val headerRow = first.unbudgetedAggregate(rawKey) ?: return null // new missing targets admission-defer; no known refusal
        val owner: DomainId
        val recorder: DomainId
        if (phase == LocalFoodRefundFactPhase.ORIGINAL) {
            val header = localSalesDecode(headerRow.payload, LocalFoodRefundRaw.serializer(), LocalFoodRefundProfile.MAX_RAW_BYTES)
            owner = header.originalActorId; recorder = header.recordingActorId
        } else {
            val header = localSalesDecode(headerRow.payload, LocalFoodRefundResolutionRaw.serializer(), LocalFoodRefundProfile.MAX_RAW_BYTES)
            owner = header.originalActorId; recorder = header.recordingActorId
        }
        if (!selected.supervised && (owner != original.originalActorId || recorder != original.originalActorId)) return null
        // This initial rich fact read is OUTSIDE the installed Pending-capacity catches.
        // Its failure cannot be relabeled as a recorded financial refusal.
        val fact = try { first.withOriginalProofs {
            if (phase == LocalFoodRefundFactPhase.ORIGINAL) FoodRefundApplicationFact(readFoodRefundBeforeRaw(first, selected.factOperationId))
            else readFoodRefundBeforeResolution(first, selected.factOperationId).let { FoodRefundApplicationFact(it.original, it) }
        } } catch (_: FoodBeforeApplicationCapacity) { return null }
        val slot = fact.slot
        check(slot.target.factOperation(phase) == selected.factOperationId && owner == slot.originalActorId && recorder == fact.recorderId &&
            original.operationId !in setOf(slot.target.intentOperationId, slot.target.recordingOperationId, slot.target.resolutionOperationId))
        val resolve = selected.supervised || phase == LocalFoodRefundFactPhase.RESOLUTION
        val base = slot.domain.financialResources.toSet() + foodRefundAuthorityResources(slot.requirement()) +
            recordingAuthority(fact.recorderId) + recordingAuthority(original.originalActorId)
        fun pending(reason: LocalFoodRefundPendingReason, resources: Set<RecoveryResourceKey> = base): Proof = Proof(
            foodRefundPending(slot.target, reason, phase), salesAuthorizationRequirement(
                if (resolve) SecurityAction.REFUND_RESOLVE else SecurityAction.RETURN_RECEIPT_LOOKUP,
                original.scope, DomainId(selected.factOperationId.value), fact.application.value.revision,
                original.operationId, original.semanticDigest, "food.refund.pending", slot.originalActorId,
                if (resolve) slot.domain.amount else null), resources)
        if (fact.application.value.state == LocalFoodRefundApplicationState.APPLIED) return Proof(
            ProposedOutcome.Reject("food.refund.already_applied", LocalFoodRefundCodec.result(LocalFoodRefundResult.AlreadyApplied(
                selected.factOperationId, checkNotNull(fact.application.value.lastApplicationOperationId), phase))),
            salesAuthorizationRequirement(if (resolve) SecurityAction.REFUND_RESOLVE else SecurityAction.RETURN_RECEIPT_LOOKUP,
                original.scope, DomainId(selected.factOperationId.value), fact.application.value.revision, original.operationId,
                original.semanticDigest, "food.refund.already_applied", slot.originalActorId, if (resolve) slot.domain.amount else null), base)
        if (phase == LocalFoodRefundFactPhase.RESOLUTION && (fact.original.application.value.state != LocalFoodRefundApplicationState.APPLIED ||
                fact.original.application.value.action != LocalFoodRefundAction.UNKNOWN))
            return pending(LocalFoodRefundPendingReason.ORIGINAL_APPLICATION_REQUIRED)
        val outcome = fact.outcome
        if (outcome is LocalFoodRefundOutcome.ExternalAttested || outcome is LocalFoodRefundOutcome.CashPaid && outcome.amount != slot.domain.amount)
            return pending(LocalFoodRefundPendingReason.VALUE_OR_TENDER_MISMATCH)
        val header = first.observeFinancial(LocalFoodFinancialProfileV1.REFUND, slot.target.refundId)
            ?: return pending(LocalFoodRefundPendingReason.SOURCE_UNAVAILABLE)
        val current = foodFinancialRefund(header)
        if (current.orderId != slot.target.orderId || current.originalCollection.id != slot.target.collectionId || current.saleId != slot.target.saleId)
            return pending(LocalFoodRefundPendingReason.CURRENT_CUSTODY_REQUIRES_RECONCILIATION)
        val financial = read()
        financial.beginFinancialEvidence()
        val producer = StreamedProducerIdentityV2(original.operationId, original.command, original.originalActorId, original.semanticDigest)
        try { return financial.withOriginalProofs {
            val cut = foodRefundBeforeCut(financial, slot.target.refundId)
            val cash = LocalFinancialCashBeforeRead(financial).cash(slot.target.sessionId)
            val attempt = cut.value.attempts.singleOrNull { it.id == slot.target.attemptId }
            val resources = base + foodRefundBasisResources(cut.value) + cashResources(cash)
            if (cash.session.value.status != CashSessionStatus.OPEN || cash.session.value.custodianId != slot.originalActorId)
                return@withOriginalProofs pending(LocalFoodRefundPendingReason.CURRENT_CUSTODY_REQUIRES_RECONCILIATION, resources)
            if (attempt == null || !foodRefundApplicationAttemptMatches(cut.value, cash.session.value, slot, phase,
                    if (phase == LocalFoodRefundFactPhase.RESOLUTION) fact.original.raw.value else null))
                return@withOriginalProofs pending(LocalFoodRefundPendingReason.ORIGINAL_ATTEMPT_MISMATCH, resources)
            if (financial.claim(LocalSalesStoreProfile.unique("refund_record", attempt.id)) != null ||
                financial.claim(CashNonSaleStoreProfile.cashSourceClaim(attempt.id)) != null ||
                financial.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.MOVEMENT_KIND, attempt.id)) != null)
                return@withOriginalProofs pending(LocalFoodRefundPendingReason.CURRENT_CUSTODY_REQUIRES_RECONCILIATION, resources)
            val cashInput = foodFinancialCashInput(cash)
            val sources = FoodRefundSources(financial.observations(producer))
            val literal = sources.refund(cut.inputs, cashInput)
            requireFoodRefundApplicationCut(literal, slot, phase, if (phase == LocalFoodRefundFactPhase.RESOLUTION) fact.original.raw.value else null)
            sources.applicationVacancy(attempt.id); sources.finish()
            val all = base + literal.financialResources
            if (all.size > 512) return@withOriginalProofs pending(LocalFoodRefundPendingReason.STORAGE_CAPACITY_REQUIRED, all)
            val evidence = financial.evidence(producer) // exact installed sealing/encoding before evaluate
            try {
                foodRefundBeforeOutput(original, selected, fact, cut, cash, cashInput, evidence)
                null // All reads and attempted exact output fit; a Pending result cannot fit.
            } catch (_: FoodCapacityExceeded) {
                lateCapacity(original, resolve, slot, cut.value, phase, all)
            } catch (_: LocalSalesWireCapacityExceeded) {
                lateCapacity(original, resolve, slot, cut.value, phase, all)
            } catch (_: LocalSalesCapacityExceeded) {
                lateCapacity(original, resolve, slot, cut.value, phase, all)
            }
        } } catch (_: FoodBeforeApplicationCapacity) { return pending(LocalFoodRefundPendingReason.STORAGE_CAPACITY_REQUIRED) }
          catch (_: FoodCapacityExceeded) { return pending(LocalFoodRefundPendingReason.STORAGE_CAPACITY_REQUIRED) }
          catch (_: StockReadEvidenceCapacityExceeded) { return pending(LocalFoodRefundPendingReason.STORAGE_CAPACITY_REQUIRED) }
          catch (_: LocalSalesSourceUnavailable) { return pending(LocalFoodRefundPendingReason.SOURCE_UNAVAILABLE) }
    }
    private fun lateCapacity(original: RetainedCommandEvidence, resolve: Boolean, slot: LocalFoodRefundSlot,
        refund: FoodRefundObligation, phase: LocalFoodRefundFactPhase, resources: Set<RecoveryResourceKey>): Proof = Proof(
        foodRefundPending(slot.target, LocalFoodRefundPendingReason.STORAGE_CAPACITY_REQUIRED, phase),
        salesAuthorizationRequirement(if (resolve) SecurityAction.REFUND_RESOLVE else SecurityAction.REFUND_RECORD,
            original.scope, refund.id, refund.revision, original.operationId, original.semanticDigest,
            "food.refund.apply", slot.originalActorId, slot.domain.amount), resources)
}

/** Exact installed output construction at the original recorded instant. Pure values only;
 * no AuthorizedAction, handler/evaluate, SQL write, native force or payout can be produced. */
private fun foodRefundBeforeOutput(original: RetainedCommandEvidence, selected: FoodRefundApplySelection,
    fact: FoodRefundApplicationFact, cut: FoodRefundCut, cash: LocalCashCut,
    cashInput: FoodFinancialCashInputV1, evidence: List<AggregateChange>): ProposedOutcome.Apply {
    val slot = fact.slot
    val phase = selected.phase
    val outcome = fact.outcome
    val observed = foodRefundOutcome(outcome, fact.recorderId)
    val changed = if (selected.supervised) FoodRefundCashHistory.supervisedImage(cut.value, cash.session.value,
        slot.target.attemptId, selected.factOperationId, original.operationId, slot.originalActorId, fact.recorderId,
        original.originalActorId, if (phase == LocalFoodRefundFactPhase.RESOLUTION) slot.target.recordingOperationId else null,
        if (phase == LocalFoodRefundFactPhase.RESOLUTION) fact.originalBodyDigest else null, observed)
    else FoodRefundCashHistory.ordinaryImage(cut.value, cash.session.value, slot.target.attemptId,
        selected.factOperationId, original.operationId, slot.originalActorId, observed)
    val revisions = listOf(LocalFoodStorageRevisionV1(LocalFoodFinancialProfileV1.REFUND, cut.value.id, cut.refund.row.revision.next())) +
        listOfNotNull(if (changed.session === cash.session.value) null else LocalFoodStorageRevisionV1(CashNonSaleStoreProfile.SESSION_KIND,
            changed.session.id, cash.session.stored.revision.next()))
    val application = fact.initialApplication().copy(revision = Revision(1), state = LocalFoodRefundApplicationState.APPLIED,
        reason = null, updatedAt = original.recordedAt, lastApplicationOperationId = original.operationId,
        action = foodRefundAction(outcome), financialRevisions = revisions)
    val result = LocalFoodRefundResult.Applied(slot.target, checkNotNull(application.action), original.operationId,
        revisions + LocalFoodStorageRevisionV1(LocalFoodRefundProfile.APPLICATION, DomainId(selected.factOperationId.value), Revision(2)), phase)
    val extra = listOfNotNull(if (changed.session === cash.session.value) null else foodPaymentCashWrite(changed.session, cashInput.storageRevision),
        changed.movement?.let { movement -> if (selected.supervised) foodRefundSupervisedMovementLiteralWrite(original.scope,
            original.operationId, original.originalActorId, original.semanticDigest, original.normalized,
            slot, fact.original.raw.value, fact.resolution?.raw?.value, movement, original.recordedAt)
            else cashMovementLiteralWrite(original.scope, original.operationId, original.originalActorId, movement, original.recordedAt) }) +
        foodRefundApplicationWrite(application, Revision(1))
    val claims = listOfNotNull(changed.record?.let { ClaimChange.Acquire(LocalSalesStoreProfile.unique("refund_record", it.attemptId), cut.value.id) },
        changed.movement?.let { ClaimChange.Acquire(CashNonSaleStoreProfile.cashSourceClaim(it.sourceId), cash.session.value.id) })
    val writes = FoodRefundWrites(original.scope, original.operationId, original.originalActorId, original.recordedAt, cut.inputs, cashInput)
    writes.refund(changed.refund)
    return writes.finish(result, extra, claims, evidence, slotDigest = fact.original.raw.value.slotDigest,
        originalBodyDigest = fact.originalBodyDigest, resolutionBodyDigest = fact.resolutionBodyDigest,
        originalUnknownApplicationOperationId = if (phase == LocalFoodRefundFactPhase.RESOLUTION)
            checkNotNull(fact.original.application.value.lastApplicationOperationId) else null)
}
