package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*

/** Current same-paying-actor retain-only UNKNOWN resolver at Start's fixed operation ID. */
internal suspend fun prepareFoodRefundResolution(request: LocalFoodRefundRequest.RecordResolution,
    normalized: StoredBytes, view: CommandReadView, context: CommandContext,
    stock: LocalStockRepository): PreparedMutation {
    if (context.operationId != request.target.resolutionOperationId || request.resolverActorId != context.originalActorId ||
        view.aggregate(foodRefundSlotKey(request.target.recordingOperationId)) == null ||
        view.aggregate(foodRefundRawKey(request.target.recordingOperationId)) == null)
        return foodRefundUnavailablePreparation(context, DomainId(request.target.recordingOperationId.value))
    val parent = readFoodRefundRaw(view, request.target.recordingOperationId, stock)
    val slot = parent.slot.value
    if (slot.target != request.target || slot.originalActorId != context.originalActorId) return foodRefundUnavailablePreparation(context, parent.slot.stored.key.id)
    val requirement = slot.requirement()
    check(request.originalBodyDigest == parent.raw.value.bodyDigest &&
        LocalFoodRefundCashJournalDomain.classifyOriginal(requirement, parent.raw.value.body()) == LocalPhysicalOriginalKind.UNKNOWN)
    LocalFoodRefundResolutionDescriptor.create(requirement, parent.raw.value.body(), context.originalActorId, normalized)
    check(view.aggregate(foodRefundResolutionKey(context.operationId)) == null &&
        view.aggregate(foodRefundApplicationKey(context.operationId)) == null)
    val admission = foodRefundAdmission(requirement, PhysicalRecordingAction.RETAIN_RESOLUTION)
    return FoodRefundPrepared(context, SecurityAction.REFUND_RESOLVE, DomainId(slot.target.recordingOperationId.value),
        Revision(0), slot.originalActorId, "food.refund.retain_resolution", slot.domain.amount,
        slot.domain.financialResources.toSet() + foodRefundAuthorityResources(requirement),
        setOf(RecoveryResourceKind.AUTHORIZATION, RecoveryResourceKind.CASH_CUSTODY, RecoveryResourceKind.ORDER_CUSTODY),
        allowsActionApproval = true, admission = { _, _ ->
            if (context.physicalParticipants?.let { it.authority == slot.domain.authority &&
                    it.clientDeviceId == requirement.clientDeviceId && it.originalActorId == context.originalActorId } == true) admission else null
        }) { _, at ->
        val native = context.physicalRecording
        if (native == null || !native.matches(admission) || native.sourceChanges.isNotEmpty())
            return@FoodRefundPrepared foodRefundReject("food.refund.journal_required")
        val raw = LocalFoodRefundResolutionRaw(1, context.scope, slot.target, slot.originalActorId,
            context.originalActorId, normalized.copyBytes().decodeToString(throwOnInvalidSequence = true),
            sha256(normalized.copyBytes()), parent.raw.value.bodyDigest, context.semanticDigest, parent.raw.value.slotDigest, at)
        ProposedOutcome.Apply(LocalFoodRefundCodec.result(LocalFoodRefundResult.RawRetained(slot.target,
            phase = LocalFoodRefundFactPhase.RESOLUTION)), listOf(foodRefundResolutionWrite(raw),
            foodRefundApplicationWrite(foodRefundInitialApplication(raw), null)))
    }
}
