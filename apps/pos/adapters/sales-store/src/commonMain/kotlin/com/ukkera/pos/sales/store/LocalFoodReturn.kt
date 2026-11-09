package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.*

/** OPS6.1 narrow whole-unsent cancellation, not an invented prepared/served-food remedy policy. */
internal suspend fun foodCancelWholeUnsent(request: LocalFoodFinancialRequestV1.CancelWholeUnsent, view: CommandReadView,
    context: CommandContext, stock: LocalStockRepository, recipes: TransactionFoodRecipeSourceV1): PreparedMutation {
    val cut = foodFinancialCollectionCut(view, request.collectionId, recipes)
    val inventory = foodFinancialLegacyStock(view, stock, cut.portions, cut.value)
    val exists = foodFinancialRead(view, LocalFoodFinancialProfileV1.REFUND, request.refundId)
    val index = foodFinancialRead(view, LocalFoodFinancialProfileV1.REFUND_INDEX, cut.value.orderId)
    val entitlement = cut.value.saleId?.let { foodFinancialRead(view, LocalFoodFinancialProfileV1.ENTITLEMENT, it)
        ?: error("food.financial.entitlement_missing") }
    val paid = cut.value.saleId?.let { foodFinancialRead(view, LocalFoodFinancialProfileV1.SALE, it)
        ?: error("food.financial.paid_document_missing") }
    return FoodFinancialPrepared(context, SecurityAction.COLLECTION_RESOLVE, cut.value.id, request.expectedCollectionRevision,
        cut.value.currentOwnerId, "food.cancel_whole_unsent", cut.value.collected,
        foodFinancialResources(cut) + inventory.resources + entitlementResource(request.refundId) +
            entitlementResource(cut.value.orderId) + orderResource(request.reasonId) +
            orderResource(foodCancellationResolutionId(context.operationId))) { command, at ->
        foodFinancialOwner(context, cut.order, request.expectedOrderRevision)
        if (exists != null) return@FoodFinancialPrepared foodFinancialReject("food.financial.identity_used")
        if (paid != null && entitlement != null) requireFoodPaidSource(cut.value,
            foodFinancialSale(paid.envelope), foodFinancialEntitlement(entitlement.envelope))
        val transition = FoodCollections.cancelWhollyUnsent(command, cut.value, request.expectedCollectionRevision,
            cut.portions.map { (it.envelope.body as FoodBodyV1.Portion).preparation }, request.refundId, request.reasonId,
            entitlement?.let { foodFinancialEntitlement(it.envelope) })
        val resolution = CollectionResolutionReference(foodCancellationResolutionId(context.operationId), cut.value.orderId,
            cut.value.id, transition.refund.id)
        val plan = FoodInventoryPlanner.releaseWhollyUnsent(inventory.planning.snapshot, inventory.planning.snapshot.revision,
            context.operationId, cut.value.portions.map { it.reservationId }, resolution)
        val contribution = stock.project(inventory.planning, context, plan, at)
        val writes = FoodFinancialWrites(context, at, foodFinancialObservations(cut) + inventory.observed +
            listOfNotNull(index?.let(::foodObserved), paid?.let(::foodObserved), entitlement?.let(::foodObserved)))
        writes.put(LocalFoodFinancialProfileV1.COLLECTION, cut.value.id,
            FoodFinancialBodyV1.Collection(foodFinancialNewSnapshot { FoodFinancialFramesV2.encodeCollection(transition.collection) }))
        writes.put(LocalFoodFinancialProfileV1.REFUND, request.refundId,
            FoodFinancialBodyV1.Refund(foodFinancialNewSnapshot { FoodFinancialFramesV2.encodeRefund(transition.refund) }))
        val previousRefunds = (index?.envelope?.body as? FoodFinancialBodyV1.RefundIndex)?.refundIds.orEmpty()
        foodCapacity(previousRefunds.size < 32)
        writes.put(LocalFoodFinancialProfileV1.REFUND_INDEX, cut.value.orderId,
            FoodFinancialBodyV1.RefundIndex(cut.value.orderId, previousRefunds + request.refundId))
        transition.entitlement?.let { after -> writes.put(LocalFoodFinancialProfileV1.ENTITLEMENT, after.sale.id,
            FoodFinancialBodyV1.Entitlement(foodFinancialNewSnapshot { FoodFinancialFramesV2.encodeEntitlement(after) })) }
        for (row in cut.portions) {
            val body = row.envelope.body as FoodBodyV1.Portion
            val cancelled = FoodPreparation.requestCancellation(body.preparation, body.preparation.revision, request.reasonId, at)
            check(cancelled.issueId == null && cancelled.tasks.isEmpty())
            writes.putFood(LocalFoodProfileV1.PORTION, row.envelope.id, body.copy(preparation = cancelled))
        }
        writes.putFood(LocalFoodProfileV1.ORDER, cut.value.orderId, cut.order.value)
        // Liability is the ORIGINAL actually collected tender coverage, not the unpaid quote.
        // Releasing an unissued reservation neither restores ingredients nor pays a refund.
        writes.apply("food.unsent.cancelled_refund_obligation", request.refundId, transition.refund.amount,
            stock = stock, contribution = contribution, extraClaims = listOf(ClaimChange.Acquire(
                LocalFoodFinancialProfileV1.source("unsent-resolution", resolution.resolutionId), request.refundId)))
    }
}
internal fun foodCancellationResolutionId(operationId: OperationId): DomainId = foodId("financial-unsent-resolution", operationId.value)

internal fun requireFoodPaidSource(collection: FoodCollection, sale: FoodPaidSale, entitlement: FoodReceiptEntitlement) {
    check(collection.saleId == sale.id && FoodFinancialSnapshots.isHistoricalCollectionSource(sale.collection, collection) &&
        sale.collection.saleId == null && sale.collection.cancellationRefundId == null &&
        sale.collection.revision.value < collection.revision.value &&
        FoodFinancialFramesV2.encodeSale(entitlement.sale) == FoodFinancialFramesV2.encodeSale(sale))
}

/** Exact narrow cancellation lineage; ignoring revision/outcome markers alone is insufficient. */
internal fun requireFoodRefundSource(collection: FoodCollection, refund: FoodRefundObligation) {
    check(collection.cancellationRefundId == refund.id && collection.saleId == refund.originalCollection.saleId &&
        refund.originalCollection.cancellationRefundId == null && refund.originalCollection.revision.value < collection.revision.value &&
        FoodFinancialSnapshots.isHistoricalCollectionSource(refund.originalCollection, collection))
}

internal suspend fun foodCloseResolvedWork(request: LocalFoodFinancialRequestV1.CloseResolvedWork, view: CommandReadView,
    context: CommandContext, stock: LocalStockRepository, recipes: TransactionFoodRecipeSourceV1): PreparedMutation {
    val cut = foodFinancialCollectionCut(view, request.collectionId, recipes)
    val inventory = foodFinancialLegacyStock(view, stock, cut.portions, cut.value)
    val claims = foodFinancialRead(view, LocalFoodFinancialProfileV1.CLAIM_INDEX,
        foodClaimIndexId(FoodClaimIndexTargetV1.ORDER, cut.value.orderId))
    val refundIndex = foodFinancialRead(view, LocalFoodFinancialProfileV1.REFUND_INDEX, cut.value.orderId)
    val refundIds = (refundIndex?.envelope?.body as? FoodFinancialBodyV1.RefundIndex)?.refundIds.orEmpty()
    val refunds = refundIds.map { foodFinancialRead(view, LocalFoodFinancialProfileV1.REFUND, it) ?: error("food.financial.refund_missing") }
    check(refundIds == listOfNotNull(cut.value.cancellationRefundId))
    refunds.forEach { requireFoodRefundSource(cut.value, foodFinancialRefund(it.envelope)) }
    val paid = cut.value.saleId?.let { foodFinancialRead(view, LocalFoodFinancialProfileV1.SALE, it) ?: error("food.financial.paid_document_missing") }
    val entitlement = cut.value.saleId?.let { foodFinancialRead(view, LocalFoodFinancialProfileV1.ENTITLEMENT, it) ?: error("food.financial.entitlement_missing") }
    return FoodFinancialPrepared(context, SecurityAction.ORDER_EDIT, request.orderId, request.expectedOrderRevision, cut.order.value.ownerId,
        "food.close_resolved_work", resources = foodFinancialResources(cut) + inventory.resources + refundIds.map(::entitlementResource) +
            entitlementResource(request.orderId) + entitlementResource(foodClaimIndexId(FoodClaimIndexTargetV1.ORDER, request.orderId)) +
            (claims?.envelope?.body as? FoodFinancialBodyV1.ClaimIndex)?.claimIds.orEmpty().map(::entitlementResource)) { _, at ->
        foodFinancialOwner(context, cut.order, request.expectedOrderRevision)
        foodFinancialRule(cut.value.orderId == request.orderId && cut.value.revision == request.expectedCollectionRevision, SalesRule.REVISION_CONFLICT)
        if ((claims?.envelope?.body as? FoodFinancialBodyV1.ClaimIndex)?.claimIds?.isNotEmpty() == true ||
            cut.value.unresolved || refunds.any { foodFinancialRefund(it.envelope).let { r -> r.unresolved || r.due.minor != 0L } })
            return@FoodFinancialPrepared foodFinancialReject("food.financial.obligations_pending")
        if (paid != null && entitlement != null) requireFoodPaidSource(cut.value, foodFinancialSale(paid.envelope), foodFinancialEntitlement(entitlement.envelope))
        val cancelled = cut.value.cancellationRefundId
        val preparationComplete = if (cancelled != null) {
            cut.portions.all { (it.envelope.body as FoodBodyV1.Portion).preparation.let { p ->
                p.status == FoodPreparationStatus.CANCELLED_BEFORE_START && p.issueId == null && p.fulfillment == FoodFulfillmentStatus.NOT_HANDED_OVER
            } }
        } else cut.value.status == FoodCollectionStatus.FINALIZED_PAID && paid != null && cut.portions.all {
            (it.envelope.body as FoodBodyV1.Portion).preparation.fulfillment != FoodFulfillmentStatus.NOT_HANDED_OVER
        }
        if (!preparationComplete) return@FoodFinancialPrepared foodFinancialReject("food.financial.preparation_or_charge_obligation_pending")
        val writes = FoodFinancialWrites(context, at, foodFinancialObservations(cut) + inventory.observed + refunds.map(::foodObserved) +
            listOfNotNull(claims?.let(::foodObserved), refundIndex?.let(::foodObserved), paid?.let(::foodObserved), entitlement?.let(::foodObserved)))
        writes.putFood(LocalFoodProfileV1.ORDER, request.orderId, cut.order.value.copy(closedByOperationId = context.operationId))
        writes.putFood(LocalFoodProfileV1.OWNER_INDEX, cut.order.value.ownerId,
            FoodBodyV1.OwnerIndex(foodOpenOrderIds(cut.order.ownerIndex) - request.orderId))
        writes.apply("food.work.closed", request.orderId)
    }
}

/**
 * Intake does not read a receipt, policy, catalogue or stock. Assertions remain assertions;
 * indexing an asserted order is an unresolved-work link, NOT verification of that order/sale.
 * No rejection can erase an independently recordable quality/merchant-fault claim merely
 * because remedy eligibility, proof, receipt recovery or signed policy is still unresolved.
 */
internal suspend fun foodRecordQualityClaim(request: LocalFoodFinancialRequestV1.RecordQualityClaim, view: CommandReadView,
    context: CommandContext): PreparedMutation {
    val exists = foodFinancialRead(view, LocalFoodFinancialProfileV1.CLAIM, request.claimId)
    val ownerIndexId = foodClaimIndexId(FoodClaimIndexTargetV1.OWNER, context.originalActorId)
    val ownerIndex = foodFinancialRead(view, LocalFoodFinancialProfileV1.CLAIM_INDEX, ownerIndexId)
    val orderIndex = request.facts.assertedOrderId?.let { foodFinancialRead(view, LocalFoodFinancialProfileV1.CLAIM_INDEX,
        foodClaimIndexId(FoodClaimIndexTargetV1.ORDER, it)) }
    return FoodFinancialPrepared(context, SecurityAction.RETURN_CLAIM, request.claimId, Revision(0), context.originalActorId,
        "food.quality.claim", resources = setOf(entitlementResource(request.claimId), entitlementResource(ownerIndexId),
            orderResource(context.originalActorId)) +
            listOfNotNull(request.facts.assertedOrderId?.let { entitlementResource(foodClaimIndexId(FoodClaimIndexTargetV1.ORDER, it)) }) +
            listOfNotNull(request.facts.assertedOrderId?.let(::orderResource), request.facts.assertedSaleId?.let(::entitlementResource),
                request.facts.assertedPortionId?.let(::orderResource), request.facts.evidence.evidenceId.let(::orderResource)),
        recoveryHoldBarrierKinds = setOf(RecoveryResourceKind.AUTHORIZATION)) { _, at ->
        if (exists != null) return@FoodFinancialPrepared foodFinancialReject("food.financial.identity_used")
        foodFinancialRule(request.facts.claimedAt <= at && request.facts.evidence.occurredAt <= at, SalesRule.INVALID_EVIDENCE)
        val writes = FoodFinancialWrites(context, at, listOfNotNull(ownerIndex?.let(::foodObserved), orderIndex?.let(::foodObserved)))
        writes.put(LocalFoodFinancialProfileV1.CLAIM, request.claimId,
            FoodFinancialBodyV1.Claim(request.facts, context.originalActorId, at, context.originalActorId, linkedOrderId = null))
        fun append(row: FoodFinancialRow?, target: FoodClaimIndexTargetV1, id: DomainId) {
            val ids = (row?.envelope?.body as? FoodFinancialBodyV1.ClaimIndex)?.claimIds.orEmpty()
            foodCapacity(ids.size < 64)
            writes.put(LocalFoodFinancialProfileV1.CLAIM_INDEX, foodClaimIndexId(target, id),
                FoodFinancialBodyV1.ClaimIndex(target, id, ids + request.claimId))
        }
        append(ownerIndex, FoodClaimIndexTargetV1.OWNER, context.originalActorId)
        request.facts.assertedOrderId?.let { append(orderIndex, FoodClaimIndexTargetV1.ORDER, it) }
        writes.apply("food.quality_claim.recorded", request.claimId)
    }
}
