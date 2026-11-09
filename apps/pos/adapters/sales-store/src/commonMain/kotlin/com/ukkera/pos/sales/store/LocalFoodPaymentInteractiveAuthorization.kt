package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.*

/**
 * Read-only current instruction proof installed on the SAME FOOD handler/repositories.
 * No Start preparation/evaluation, native capacity, AuthorizedAction, proposed stock write
 * or copied historical/current view is manufactured. Absence below is only a SQL denial
 * probe; the actual native instruction owner still proves fresh private event custody.
 */
internal class LocalFoodPaymentInteractiveAuthorization(private val stock: LocalStockRepository,
    private val recipes: TransactionFoodStockSource) : MasterPhysicalInteractiveAuthorizer {
    override val command: CommandKey get() = LocalFoodPaymentProfile.COMMAND
    override val domainProfile: String get() = LocalFoodPaymentProfile.DOMAIN

    override suspend fun prepareRetainedStartAuthorization(view: CommandReadView,
        context: PhysicalRetainedStartContext): PhysicalRetainedStartRequirement? = try {
        current(FoodReadView(commerceLoan(view, stock)), context)
    } catch (_: FoodCapacityExceeded) { null }
      catch (_: LocalSalesWireCapacityExceeded) { null }
      catch (_: LocalSalesCapacityExceeded) { null }
      catch (failure: LocalStockRejected) {
        if (failure.failure != LocalStockFailure.BOUND_EXCEEDED) throw failure
        null
      }

    private suspend fun current(view: FoodReadView,
        context: PhysicalRetainedStartContext): PhysicalRetainedStartRequirement? {
        val original = context.original
        val committed = context.committedStart
        if (original.scope != view.scope || original.key != command || committed.scope != view.scope ||
            committed.command != command || committed.domainProfile != domainProfile ||
            committed.startKind != PhysicalRecordingStartKind.PAYMENT ||
            committed.operationId != original.operationId || committed.originalActorId != original.originalActorId ||
            committed.semanticDigest != original.semanticDigest) return null
        val requirement = LocalFoodPaymentPhysicalProjection.parse(context.requirement).requirement
        if (requirement.scope != view.scope || requirement.originalActorId != original.originalActorId ||
            requirement.intentOperationId != original.operationId || requirement.intentSemanticDigest != original.semanticDigest ||
            requirement.command != original.key) return null
        if (view.aggregate(foodPaymentSlotKey(requirement.recordingOperationId)) == null) return null
        // Whole committed Start + BOTH immutable gen1 native rows, every lineage/attempt
        // claim and both permanent future fences on this actual loan, before any instruction.
        val slot = readFoodPaymentSlot(view, requirement.recordingOperationId, stock)
        if (slot.value.originalActorId != original.originalActorId || slot.value.requirement().canonicalBytes != context.requirement)
            return null
        val start = view.retainedCommand(original.operationId) ?: return null
        if (start.nativeOriginalProvenance != null || !start.committed || start.scope != view.scope || start.command != command ||
            start.originalActorId != original.originalActorId || start.semanticDigest != original.semanticDigest ||
            start.resultHash != committed.resultDigest || start.recordedAt != committed.recordedAt || start.sequence != committed.sequence ||
            start.journalHash != committed.journalHash || start.assignmentId != committed.authority.assignmentId ||
            start.authorityEpoch != committed.authority.epoch || committed.authority != slot.value.domain.authority) return null
        val proof = LocalFoodPaymentProducer.requireStart(start)
        if (proof.slot != slot.value) return null
        val request = LocalFoodPaymentCodec.decode(start.normalized) as? LocalFoodPaymentRequest.StartCash ?: return null
        val target = slot.value.target
        if (request.target(target.orderId, original.operationId) != target) return null
        for (operation in listOf(target.recordingOperationId, target.resolutionOperationId)) {
            if (view.aggregate(foodPaymentRawKey(operation)) != null || view.aggregate(foodPaymentApplicationKey(operation)) != null ||
                view.retainedCommand(operation) != null) return null
        }

        val cut = foodPaymentCollectionCut(view, target.collectionId, recipes)
        val cash = LocalSalesStorage.cash(view, target.sessionId)
        val collection = cut.value
        val actor = original.originalActorId
        // An overtaking collection/cash write may not renew or reconstruct the one-shot Start.
        if (!sameFoodPaymentStartPostimage(start, cut.row.row) || !sameFoodPaymentStartPostimage(start, cash.session.stored)) return null
        if (collection.id != target.collectionId || collection.orderId != target.orderId || collection.cashSessionId != target.sessionId ||
            collection.currentOwnerId != actor || cash.session.value.id != target.sessionId ||
            cash.session.value.status != CashSessionStatus.OPEN ||
            !foodPaymentInitialPhysicalCustody(collection, cut.order.value, cut.portions.map { it.envelope }, cash.session.value)) return null
        // Recheck even a retained Start before any fresh instruction. No change to its
        // original fact, unresolved custody, retention or later financial application.
        if (foodKitchenExecutionRestriction(view, recipes, cut.order, cut.portions) != null) return null
        val attempt = collection.attempts.singleOrNull { it.id == target.attemptId } ?: return null
        if (attempt.status != AttemptStatus.AWAITING_ATTESTATION || attempt.latestObservation != null || attempt.payment != null ||
            attempt.originalActorId != actor || attempt.originalCashSessionId != target.sessionId ||
            attempt.originalOperationId != target.intentOperationId || attempt.amount != request.amount ||
            attempt.amount != slot.value.domain.amount || attempt.payerReference != request.payerReference || attempt.tender != Tender.Cash ||
            collection.attempts.filter { it.status.unresolved() }.map { it.id } != listOf(attempt.id) ||
            attempt.id !in cash.session.value.pendingAttemptIds) return null
        if (view.claim(LocalSalesStoreProfile.unique("payment_record", attempt.id)) != null ||
            view.claim(CashNonSaleStoreProfile.cashSourceClaim(attempt.id)) != null ||
            view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.MOVEMENT_KIND, attempt.id)) != null) return null
        val financial = foodFinancialResources(cut) + cashResources(cash)
        if (financial != slot.value.domain.financialResources.toSet()) return null
        val resources = linkedSetOf<RecoveryResourceKey>()
        resources += financial; resources += foodPaymentAuthorityResources(requirement)
        val actualReserved = linkedMapOf<DomainId, LocalStockPaymentCut>()
        val actualIssued = linkedMapOf<DomainId, LocalStockPaymentIssuedCut>()
        val observeResource: suspend (RecoveryResourceKey) -> Unit = { alias ->
            resources += alias
            if (resources.size > 512) throw LocalStockRejected(LocalStockFailure.BOUND_EXCEEDED)
        }
        // Current owner closes all reservation holders or all already-issued value slices.
        // No remaining-lot availability check can turn issued ingredients into a new issue.
        for (entry in collection.portions) when (entry.stockOrigin) {
            FoodStockOrigin.RESERVED_FOR_COLLECTION -> actualReserved[entry.reservationId] =
                stock.observePaymentReservation(view, entry.reservationId, observeResource)
            FoodStockOrigin.ISSUED_BEFORE_COLLECTION -> {
                val issued = stock.observePaymentIssuedReservation(view, entry.reservationId, observeResource)
                actualIssued[entry.reservationId] = issued
                val portion = cut.portions.single { it.envelope.id == entry.portionId }.envelope.body as FoodBodyV1.Portion
                requireFoodCurrentIssueHeader(portion, issued.issue)
                requireFoodFirstIssue(portion, LocalStockPaymentObservations.requireIssuedOriginal(view, stock, issued))
            }
        }
        if (actualReserved.keys != proof.stock.reserved.keys || actualIssued.keys != proof.stock.issued.keys) return null
        for ((id, current) in actualReserved) {
            val initial = proof.stock.reserved.getValue(id)
            if (current.header != initial.header || current.allocations.associateBy { it.allocation.allocationId } !=
                initial.allocations.associateBy { it.allocation.allocationId } || !current.safeAt(context.observedAt)) return null
        }
        for ((id, current) in actualIssued) {
            val initial = proof.stock.issued.getValue(id)
            if (!sameFoodPaymentIssuedOrigin(current, initial) || current.issue.revision < initial.issue.revision ||
                !current.issue.startedStationIds.containsAll(initial.issue.startedStationIds)) return null
        }
        // Genuine current lots may only shorten the immutable Start deadline, never renew it.
        val deadline = earlierFoodPaymentDeadline(foodPaymentDeadline(actualReserved.values), proof.result.mustCommitBefore)
        if (deadline != null && context.observedAt >= deadline || resources.size > 512) return null
        return PhysicalRetainedStartRequirement(
            salesAuthorizationRequirement(SecurityAction.COLLECTION_ATTEST, view.scope, collection.id, collection.revision,
                original.operationId, original.semanticDigest, "food.payment.instruction", actor, attempt.amount),
            context.requirement, resources, setOf(RecoveryResourceKind.STOCK, RecoveryResourceKind.AUTHORIZATION), deadline)
    }
}

private fun sameFoodPaymentStartPostimage(original: RetainedCommandEvidence, row: StoredAggregate): Boolean =
    original.aggregateWrites.singleOrNull { it.key == row.key }?.let {
        it.storageGeneration == row.revision && it.schema == row.schema && it.payload == row.payload && it.payloadDigest == row.digest
    } == true
