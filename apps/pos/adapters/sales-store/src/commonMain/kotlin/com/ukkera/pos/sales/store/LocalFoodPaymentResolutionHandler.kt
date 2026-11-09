package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*

/** Current independently authorized retain-only UNKNOWN resolver at Start's fixed operation ID. */
internal suspend fun prepareFoodPaymentResolution(request: LocalFoodPaymentRequest.RecordResolution,
    normalized: StoredBytes, view: CommandReadView, context: CommandContext,
    stock: LocalStockRepository): PreparedMutation {
    if (context.operationId != request.target.resolutionOperationId || request.resolverActorId != context.originalActorId ||
        view.aggregate(foodPaymentSlotKey(request.target.recordingOperationId)) == null ||
        view.aggregate(foodPaymentRawKey(request.target.recordingOperationId)) == null)
        return foodPaymentUnavailablePreparation(context, DomainId(request.target.recordingOperationId.value))
    val parent = readFoodPaymentRaw(view, request.target.recordingOperationId, stock)
    val slot = parent.slot.value
    if (slot.target != request.target) return foodPaymentUnavailablePreparation(context, parent.slot.stored.key.id)
    val requirement = slot.requirement()
    check(request.originalBodyDigest == parent.raw.value.bodyDigest &&
        LocalFoodCashJournalDomain.classifyOriginal(requirement, parent.raw.value.body()) == LocalPhysicalOriginalKind.UNKNOWN)
    LocalFoodPaymentResolutionDescriptor.create(requirement, parent.raw.value.body(), context.originalActorId, normalized)
    check(view.aggregate(foodPaymentResolutionKey(context.operationId)) == null &&
        view.aggregate(foodPaymentApplicationKey(context.operationId)) == null)
    val admission = foodPaymentAdmission(requirement, PhysicalRecordingAction.RETAIN_RESOLUTION)
    return FoodPaymentPrepared(context, SecurityAction.COLLECTION_RESOLVE, DomainId(slot.target.recordingOperationId.value),
        Revision(0), slot.originalActorId, "food.payment.retain_resolution", slot.domain.amount,
        slot.domain.financialResources.toSet() + foodPaymentAuthorityResources(requirement),
        setOf(RecoveryResourceKind.AUTHORIZATION, RecoveryResourceKind.CASH_CUSTODY, RecoveryResourceKind.ORDER_CUSTODY),
        allowsActionApproval = true, admission = { _, _ ->
            if (context.physicalParticipants?.let { it.authority == slot.domain.authority &&
                    it.clientDeviceId == requirement.clientDeviceId && it.originalActorId == context.originalActorId } == true) admission else null
        }) { _, at ->
        val native = context.physicalRecording
        if (native == null || !native.matches(admission) || native.sourceChanges.isNotEmpty())
            return@FoodPaymentPrepared foodPaymentReject("food.payment.journal_required")
        val raw = LocalFoodPaymentResolutionRaw(1, context.scope, slot.target, slot.originalActorId,
            context.originalActorId, normalized.copyBytes().decodeToString(throwOnInvalidSequence = true),
            sha256(normalized.copyBytes()), parent.raw.value.bodyDigest, context.semanticDigest, parent.raw.value.slotDigest, at)
        ProposedOutcome.Apply(LocalFoodPaymentCodec.result(LocalFoodPaymentResult.RawRetained(slot.target,
            phase = LocalFoodPaymentFactPhase.RESOLUTION)), listOf(foodPaymentResolutionWrite(raw),
            foodPaymentApplicationWrite(foodPaymentInitialApplication(raw), null)))
    }
}

/**
 * Review is audited, not a generic read. Kernel persists operation/result/journal/audit/upload
 * before disclosure; replay is under current result policy. Apply separately reauthorizes
 * current COLLECTION_RESOLVE with original-owner ANY_OWNER restrictions. No custody changes.
 */
public class LocalFoodPaymentSupervisorHandler(private val stock: LocalStockRepository,
    private val recipes: TransactionFoodStockSource) : MasterCommandHandler {
    override val key: CommandKey get() = LocalFoodPaymentSupervisorCodec.COMMAND
    override val writableKinds: Set<String> = setOf(LocalFoodPaymentProfile.APPLICATION, LocalFoodPaymentProfile.EVENT,
        LocalFoodFinancialProfileV1.COLLECTION, LocalFoodFinancialProfileV1.FRAME, CashNonSaleStoreProfile.SESSION_KIND,
        LocalSalesStoreProfile.MOVEMENT_KIND, StockReadEvidenceV1.KIND)
    override val claimNamespaces: Set<String> = setOf(LocalSalesStoreProfile.UNIQUE_NAMESPACE, CashNonSaleStoreProfile.CASH_SOURCE_NAMESPACE)
    override fun normalize(input: StoredBytes): StoredBytes = LocalFoodPaymentSupervisorCodec.normalize(input)
    override suspend fun prepare(normalized: StoredBytes, borrowed: CommandReadView, context: CommandContext): PreparedMutation {
        check(context.key == key && context.scope == borrowed.scope &&
            semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        return when (val request = LocalFoodPaymentSupervisorCodec.decode(normalized)) {
            is LocalFoodPaymentSupervisorRequest.Apply -> prepareFoodPaymentApplication(request.factOperationId,
                request.phase, borrowed, context, stock, recipes, supervised = true)
            is LocalFoodPaymentSupervisorRequest.Review -> {
                val view = FoodReadView(commerceLoan(borrowed, stock))
                if (view.aggregate(foodPaymentRawKey(request.originalRecordingOperationId)) == null)
                    return foodPaymentUnavailablePreparation(context, DomainId(request.originalRecordingOperationId.value))
                val original = readFoodPaymentRaw(view, request.originalRecordingOperationId, stock)
                val slot = original.slot.value
                val resolution = if (view.aggregate(foodPaymentResolutionKey(slot.target.resolutionOperationId)) == null) {
                    check(view.aggregate(foodPaymentApplicationKey(slot.target.resolutionOperationId)) == null)
                    null
                } else readFoodPaymentResolution(view, slot.target.resolutionOperationId, stock)
                return FoodPaymentPrepared(context, SecurityAction.COLLECTION_RESOLVE, original.slot.stored.key.id,
                    Revision(0), slot.originalActorId, "food.payment.supervisor.review", slot.domain.amount,
                    slot.domain.financialResources.toSet() + foodPaymentAuthorityResources(slot.requirement()),
                    allowsActionApproval = true) { _, _ -> ProposedOutcome.Reject("food.payment.supervisor.reviewed",
                    LocalFoodPaymentSupervisorCodec.review(LocalFoodPaymentSupervisorReview(foodPaymentOriginalSummary(original),
                        (LocalFoodPaymentCodec.decode(original.raw.value.body()) as LocalFoodPaymentRequest.RecordOriginal).outcome,
                        resolution?.let(::foodPaymentResolutionSummary), resolution?.let {
                            (LocalFoodPaymentCodec.decode(it.raw.value.body()) as LocalFoodPaymentRequest.RecordResolution).outcome
                        }))) }
            }
        }
    }
}
