package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.*

/**
 * Current read-only, one-shot instruction proof on the SAME refund handler/repositories.
 * No Start preparation/evaluation, grant, native capacity, stock decision or copied view.
 * SQL absence can deny, never prove no human/native action; the actual native owner
 * independently proves private original event custody before offering an instruction.
 */
internal class LocalFoodRefundInteractiveAuthorization(private val stock: LocalStockRepository) : MasterPhysicalInteractiveAuthorizer {
    override val command: CommandKey get() = LocalFoodRefundProfile.COMMAND
    override val domainProfile: String get() = LocalFoodRefundProfile.DOMAIN
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
            committed.command != command || committed.domainProfile != domainProfile || committed.startKind != PhysicalRecordingStartKind.REFUND ||
            committed.operationId != original.operationId || committed.originalActorId != original.originalActorId ||
            committed.semanticDigest != original.semanticDigest) return null
        val requirement = LocalFoodRefundPhysicalProjection.parse(context.requirement).requirement
        if (requirement.scope != view.scope || requirement.originalActorId != original.originalActorId ||
            requirement.intentOperationId != original.operationId || requirement.intentSemanticDigest != original.semanticDigest ||
            requirement.command != original.key || view.aggregate(foodRefundSlotKey(requirement.recordingOperationId)) == null) return null
        val slot = readFoodRefundSlot(view, requirement.recordingOperationId, stock)
        if (slot.value.originalActorId != original.originalActorId || slot.value.requirement().canonicalBytes != context.requirement) return null
        val start = view.retainedCommand(original.operationId) ?: return null
        if (start.nativeOriginalProvenance != null || !start.committed || start.scope != view.scope || start.command != command ||
            start.originalActorId != original.originalActorId || start.semanticDigest != original.semanticDigest ||
            start.resultHash != committed.resultDigest || start.recordedAt != committed.recordedAt || start.sequence != committed.sequence ||
            start.journalHash != committed.journalHash || start.assignmentId != committed.authority.assignmentId ||
            start.authorityEpoch != committed.authority.epoch || committed.authority != slot.value.domain.authority) return null
        val proof = LocalFoodRefundProducer.requireStart(start)
        if (proof.slot != slot.value) return null
        val request = LocalFoodRefundCodec.decode(start.normalized) as? LocalFoodRefundRequest.StartCash ?: return null
        val target = slot.value.target
        if (view.aggregate(foodRefundRawKey(target.recordingOperationId)) != null ||
            view.aggregate(foodRefundResolutionKey(target.resolutionOperationId)) != null) return null
        for (operation in listOf(target.recordingOperationId, target.resolutionOperationId))
            if (view.aggregate(foodRefundApplicationKey(operation)) != null || view.retainedCommand(operation) != null) return null
        val cut = foodRefundCut(view, target.refundId)
        val cash = LocalSalesStorage.cash(view, target.sessionId)
        if (request.target(cut.value, original.operationId) != target ||
            !sameFoodRefundStartPostimage(start, cut.refund.row) || !sameFoodRefundStartPostimage(start, cash.session.stored) ||
            !foodRefundApplicationAttemptMatches(cut.value, cash.session.value, slot.value, LocalFoodRefundFactPhase.ORIGINAL, null)) return null
        val attempt = cut.value.attempts.single { it.id == target.attemptId }
        if (view.claim(LocalSalesStoreProfile.unique("refund_record", attempt.id)) != null ||
            view.claim(CashNonSaleStoreProfile.cashSourceClaim(attempt.id)) != null ||
            view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.MOVEMENT_KIND, attempt.id)) != null) return null
        val financial = foodRefundBasisResources(cut.value) + cashResources(cash) +
            cash.work.value.pendingClaimIds.map(::entitlementResource)
        if (financial != slot.value.domain.financialResources.toSet()) return null
        val resources: Set<RecoveryResourceKey> = financial + foodRefundAuthorityResources(requirement)
        if (resources.size > 512) return null
        return PhysicalRetainedStartRequirement(salesAuthorizationRequirement(SecurityAction.REFUND_RECORD, view.scope,
            cut.value.id, cut.value.revision, original.operationId, original.semanticDigest, "food.refund.instruction",
            slot.value.originalActorId, attempt.amount), context.requirement, resources, foodRefundApplicationBarriers, null)
    }
}

private fun sameFoodRefundStartPostimage(original: RetainedCommandEvidence, row: StoredAggregate): Boolean =
    original.aggregateWrites.singleOrNull { it.key == row.key }?.let {
        it.storageGeneration == row.revision && it.schema == row.schema && it.payload == row.payload && it.payloadDigest == row.digest
    } == true
