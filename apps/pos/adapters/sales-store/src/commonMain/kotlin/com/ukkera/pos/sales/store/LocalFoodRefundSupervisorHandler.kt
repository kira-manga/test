package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.*

/**
 * Review is audited, not a generic read. Kernel persists operation/result/journal/audit/upload
 * before disclosure; replay is under current result policy. Apply separately reauthorizes
 * current REFUND_RESOLVE with original-owner ANY_OWNER restrictions. No custody changes.
 */
public class LocalFoodRefundSupervisorHandler(private val stock: LocalStockRepository,
    private val originalSources: LocalFinancialOriginalSourceServices? = null) : MasterCommandHandler, LocalMasterRpcRetainedResultSourceAccessProvider {
    override val key: CommandKey get() = LocalFoodRefundSupervisorCodec.COMMAND
    override val writableKinds: Set<String> = setOf(LocalFoodRefundProfile.APPLICATION, LocalFoodRefundProfile.EVENT,
        LocalFoodFinancialProfileV1.REFUND, LocalFoodFinancialProfileV1.FRAME, CashNonSaleStoreProfile.SESSION_KIND,
        LocalSalesStoreProfile.MOVEMENT_KIND, StockReadEvidenceV1.KIND)
    override val claimNamespaces: Set<String> = setOf(LocalSalesStoreProfile.UNIQUE_NAMESPACE, CashNonSaleStoreProfile.CASH_SOURCE_NAMESPACE)
    override fun normalize(input: StoredBytes): StoredBytes = LocalFoodRefundSupervisorCodec.normalize(input)
    override fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean =
        profile == LocalMasterRpcWorkflowProfile.LAN_BUSINESS && normalize(normalized) == normalized
    override suspend fun rpcRetainedResultRequirements(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes,
        view: CommandReadView, context: CommandContext): List<AuthorizationRequirement>? {
        if (!supportsRpcWorkflow(profile, normalized) || context.key != key) return null
        return foodRefundRpcRetainedRequirements(normalized, FoodReadView(commerceLoan(view, stock)), context, originalSources)
    }

    override suspend fun prepare(normalized: StoredBytes, borrowed: CommandReadView, context: CommandContext): PreparedMutation {
        check(context.key == key && context.scope == borrowed.scope &&
            semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        return when (val request = LocalFoodRefundSupervisorCodec.decode(normalized)) {
            is LocalFoodRefundSupervisorRequest.Apply -> prepareFoodRefundApplication(request.factOperationId,
                request.phase, borrowed, context, stock, supervised = true)
            is LocalFoodRefundSupervisorRequest.Review -> {
                val view = FoodReadView(commerceLoan(borrowed, stock))
                if (view.aggregate(foodRefundRawKey(request.originalRecordingOperationId)) == null)
                    return foodRefundUnavailablePreparation(context, DomainId(request.originalRecordingOperationId.value))
                val original = readFoodRefundRaw(view, request.originalRecordingOperationId, stock)
                val slot = original.slot.value
                val resolution = if (view.aggregate(foodRefundResolutionKey(slot.target.resolutionOperationId)) == null) {
                    check(view.aggregate(foodRefundApplicationKey(slot.target.resolutionOperationId)) == null)
                    null
                } else readFoodRefundResolution(view, slot.target.resolutionOperationId, stock)
                return FoodRefundPrepared(context, SecurityAction.REFUND_RESOLVE, original.slot.stored.key.id,
                    Revision(0), slot.originalActorId, "food.refund.supervisor.review", slot.domain.amount,
                    slot.domain.financialResources.toSet() + foodRefundAuthorityResources(slot.requirement()),
                    allowsActionApproval = true) { _, _ -> ProposedOutcome.Reject("food.refund.supervisor.reviewed",
                    LocalFoodRefundSupervisorCodec.review(LocalFoodRefundSupervisorReview(foodRefundOriginalSummary(original),
                        (LocalFoodRefundCodec.decode(original.raw.value.body()) as LocalFoodRefundRequest.RecordOriginal).outcome,
                        resolution?.let(::foodRefundResolutionSummary), resolution?.let {
                            (LocalFoodRefundCodec.decode(it.raw.value.body()) as LocalFoodRefundRequest.RecordResolution).outcome
                        }))) }
            }
        }
    }
}
