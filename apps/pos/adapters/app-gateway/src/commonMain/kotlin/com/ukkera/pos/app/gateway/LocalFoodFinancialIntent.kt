package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.inventory.store.LocalStockRelationalQueriesV2
import com.ukkera.pos.sales.FoodFulfillmentStatus
import com.ukkera.pos.sales.FoodPreparationStatus
import com.ukkera.pos.sales.FoodStockOrigin
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Quantity

internal val foodFinancialActions: Set<LocalAction> = setOf(LocalAction.FOOD_BEGIN_COLLECTION,
    LocalAction.FOOD_FINALIZE_PAID, LocalAction.FOOD_SEND_RESERVED, LocalAction.FOOD_PAID_PICKUP,
    LocalAction.FOOD_CANCEL_WHOLE_UNSENT, LocalAction.FOOD_CLOSE_RESOLVED, LocalAction.FOOD_RECORD_QUALITY_CLAIM)

/** Fresh preparation only. No physical Start/Observe/Apply variant is admitted through this UI. */
internal suspend fun prepareFoodFinancial(access: GatewayAccess, intent: LocalCommandIntent): MutationRequest {
    if (intent.reference != null || intent.lineField != null || intent.lines.isNotEmpty()) invalid()
    val input = InputValues(intent.values)
    val request = when (intent.action) {
        LocalAction.FOOD_RECORD_QUALITY_CLAIM -> {
            val claim = input.id(LocalField.CASE_ID)
            access.requireAction(intent.action, claim)
            val kind = enumChoice<LocalFoodClaimKindV1>(input.choice(LocalField.MODE), LocalField.MODE)
            val order = input.optionalId(LocalField.ORDER_ID)
            val sale = input.optionalId(LocalField.SALE_ID)
            val portion = input.optionalId(LocalField.PORTION_ID)
            val unit = input.optionalId(LocalField.UNIT)
            val scale = input.optionalInteger(LocalField.QUANTITY_SCALE)
            val quantity = input.optionalDecimal(LocalField.QUANTITY)
            val assertedQuantity = if (unit == null && scale == null && quantity == null) null else {
                if (unit == null) invalid(LocalField.UNIT)
                if (scale == null || scale !in 0L..6L) invalid(LocalField.QUANTITY_SCALE)
                Quantity.parse(unit, quantity ?: invalid(LocalField.QUANTITY), scale.toInt()).requirePositive()
            }
            // Legal intake never requires a valid/current receipt, stock, recipe or policy read.
            LocalFoodFinancialRequestV1.RecordQualityClaim(claim, LocalFoodClaimFactsV1(kind, order, sale, portion,
                assertedQuantity, input.optionalInstant(LocalField.ASSERTED_RECEIVED_AT), input.instant(LocalField.CLAIMED_AT),
                input.text(LocalField.REASON), LocalCashEvidenceV1(input.id(LocalField.EVIDENCE_ID), input.instant(LocalField.OCCURRED_AT))))
        }
        LocalAction.FOOD_BEGIN_COLLECTION -> {
            val orderId = input.id(LocalField.ORDER_ID)
            access.requireAction(intent.action, orderId)
            val expected = input.revision(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION)
            val cut = foodRead(access, LocalFoodQueryV1.Order(orderId))
            val detail = cut.result.detail ?: foodMissing()
            val order = detail.summary
            if (order.revision != expected) invalid(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION)
            if (order.service.channel == LocalSalesChannel.DELIVERY) throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
            if (cut.kitchenUnavailableForUnsentWork) throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
            if (order.workClosed || order.handoffId != null || order.financialCollectionId != null ||
                detail.chargeResolutionRequired) invalid(LocalField.ORDER_ID)
            if (input.money(LocalField.AMOUNT) != detail.originalQuotedTotal) invalid(LocalField.AMOUNT)
            LocalFoodFinancialRequestV1.BeginCollection(orderId, expected, input.id(LocalField.COLLECTION_ID),
                input.id(LocalField.SESSION_ID), input.revision(LocalField.EXPECTED_SESSION_REVISION))
        }
        else -> {
            if (intent.action !in foodFinancialActions) invalid()
            val collectionId = input.id(LocalField.COLLECTION_ID)
            val expectedCollection = input.revision(LocalField.EXPECTED_COLLECTION_REVISION)
            val orderId = input.id(LocalField.ORDER_ID)
            val expectedOrder = input.revision(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION)
            val collection = (foodFinancialRead(access, LocalFoodFinancialQueryV1.Collection(collectionId)).result
                as? LocalFoodFinancialReadResultV1.Collection)?.value ?: foodMissing()
            if (collection.collectionDomainRevision != expectedCollection) invalid(LocalField.EXPECTED_COLLECTION_REVISION)
            if (collection.orderStorageRevision != expectedOrder) invalid(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION)
            if (collection.orderId != orderId || collection.workClosed) invalid(LocalField.ORDER_ID)
            if (intent.action == LocalAction.FOOD_FINALIZE_PAID) {
                val action = if (collection.originalPayable.minor == 0L) SecurityAction.COLLECTION_FINALIZE_ZERO else SecurityAction.COLLECTION_FINALIZE
                if (!access.hasHint(Permission(action, PermissionVerb.PERFORM), collectionId)) throw GatewayFailure(LocalServiceProblem.DENIED)
            } else access.requireAction(intent.action,
                if (intent.action == LocalAction.FOOD_CANCEL_WHOLE_UNSENT) collectionId else orderId)
            when (intent.action) {
                LocalAction.FOOD_FINALIZE_PAID -> {
                    if (!foodFinancialCanFinalize(collection)) invalid(LocalField.COLLECTION_ID)
                    LocalFoodFinancialRequestV1.FinalizePaid(collectionId, expectedCollection, input.id(LocalField.SALE_ID))
                }
                LocalAction.FOOD_CANCEL_WHOLE_UNSENT -> {
                    if (!foodFinancialCanCancel(collection)) invalid(LocalField.COLLECTION_ID)
                    LocalFoodFinancialRequestV1.CancelWholeUnsent(collectionId, expectedCollection, expectedOrder,
                        input.id(LocalField.RETURN_ID), input.id(LocalField.REASON_ID))
                }
                LocalAction.FOOD_CLOSE_RESOLVED -> {
                    if (!foodFinancialCanClose(collection)) invalid(LocalField.COLLECTION_ID)
                    LocalFoodFinancialRequestV1.CloseResolvedWork(orderId, expectedOrder, collectionId, expectedCollection)
                }
                LocalAction.FOOD_SEND_RESERVED, LocalAction.FOOD_PAID_PICKUP -> {
                    val selected = if (intent.action == LocalAction.FOOD_SEND_RESERVED)
                        input.references(LocalField.PORTION_ID, LocalRecordKind.FOOD_PORTION)
                    else listOf(input.reference(LocalField.PORTION_ID, LocalRecordKind.FOOD_PORTION))
                    if (selected.size !in 1..8 || selected.map { it.id }.distinct().size != selected.size ||
                        selected.any { it.parentId != orderId || it.revision == null } || collection.cancellationRefundId != null)
                        invalid(LocalField.PORTION_ID)
                    val portions = selected.map { reference ->
                        val portion = collection.portions.singleOrNull { it.portionId == reference.id } ?: invalid(LocalField.PORTION_ID)
                        if (portion.preparationDomainRevision != reference.revision || portion.fulfillment != FoodFulfillmentStatus.NOT_HANDED_OVER)
                            invalid(LocalField.PORTION_ID)
                        portion
                    }
                    if (intent.action == LocalAction.FOOD_SEND_RESERVED) {
                        val cut = foodRead(access, LocalFoodQueryV1.Order(orderId))
                        val order = cut.result.detail?.summary ?: foodMissing()
                        if (order.revision != expectedOrder || order.financialCollectionId != collectionId ||
                            order.handoffId != null || order.workClosed) invalid(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION)
                        if (order.service.channel == LocalSalesChannel.DELIVERY) throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
                        if (cut.kitchenUnavailableForUnsentWork) throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
                        if (portions.any { it.preparationStatus != FoodPreparationStatus.NOT_SENT ||
                                it.stockOrigin != FoodStockOrigin.RESERVED_FOR_COLLECTION || it.reservationStatus != "RESERVED" })
                            invalid(LocalField.PORTION_ID)
                        LocalFoodFinancialRequestV1.SendReserved(orderId, expectedOrder, collectionId, expectedCollection, selected.map { it.id })
                    } else {
                        if (collection.status != "FINALIZED_PAID" || collection.saleId == null ||
                            portions.single().preparationStatus != FoodPreparationStatus.READY) invalid(LocalField.PORTION_ID)
                        val order = foodRead(access, LocalFoodQueryV1.Order(orderId)).result.detail?.summary ?: foodMissing()
                        if (order.revision != expectedOrder || order.financialCollectionId != collectionId ||
                            order.handoffId != null || order.workClosed) invalid(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION)
                        if (order.service.channel !in setOf(LocalSalesChannel.COUNTER, LocalSalesChannel.TAKEAWAY)) invalid(LocalField.CHANNEL)
                        LocalFoodFinancialRequestV1.PaidPickup(orderId, expectedOrder, collectionId,
                            selected.single().id, checkNotNull(selected.single().revision))
                    }
                }
                else -> invalid()
            }
        }
    }
    input.finish()
    return routeFreshFoodFinancial(access, intent, request)
}

/** Choose from the ACTUAL installed stock profile once, before journaling; never on retry. */
private suspend fun routeFreshFoodFinancial(access: GatewayAccess, intent: LocalCommandIntent,
    request: LocalFoodFinancialRequestV1): MutationRequest {
    if (request is LocalFoodFinancialRequestV1.RecordQualityClaim) {
        if (!access.installed.has(LocalFoodFinancialProfileV1.COMMAND)) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
        return LocalFoodFinancialCodecV1.command(intent.operationId, request)
    }
    if (!access.installed.has(LocalStockRelationalQueriesV2.PROFILE)) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    val normalized = LocalStockRelationalQueriesV2.normalized(access.read(LocalStockRelationalQueriesV2.profile()).payload)
    if (access.installed.isFoodCashFinancialOnly && !normalized)
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    val key = if (normalized) LocalFoodStockCodecV2.COMMAND else LocalFoodFinancialProfileV1.COMMAND
    if (!access.installed.has(key)) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    return if (normalized) LocalFoodStockCodecV2.command(intent.operationId, LocalFoodStockRequestV2.Financial(request))
        else LocalFoodFinancialCodecV1.command(intent.operationId, request)
}

/** Strict retained decoder. No new profile, revision, actor, amount or operation is substituted. */
internal fun savedFoodFinancial(request: MutationRequest): LocalFoodFinancialRequestV1 {
    val value = when (request.command) {
        LocalFoodFinancialProfileV1.COMMAND -> LocalFoodFinancialCodecV1.decode(request.payload)
        LocalFoodStockCodecV2.COMMAND -> (LocalFoodStockCodecV2.decode(request.payload) as? LocalFoodStockRequestV2.Financial)?.request ?: unavailable()
        else -> unavailable()
    }
    foodFinancialAction(value) // A physical wire draft has no admissible action here.
    return value
}

internal fun foodFinancialAction(value: LocalFoodFinancialRequestV1): LocalAction = when (value) {
    is LocalFoodFinancialRequestV1.BeginCollection -> LocalAction.FOOD_BEGIN_COLLECTION
    is LocalFoodFinancialRequestV1.FinalizePaid -> LocalAction.FOOD_FINALIZE_PAID
    is LocalFoodFinancialRequestV1.SendReserved -> LocalAction.FOOD_SEND_RESERVED
    is LocalFoodFinancialRequestV1.PaidPickup -> LocalAction.FOOD_PAID_PICKUP
    is LocalFoodFinancialRequestV1.CancelWholeUnsent -> LocalAction.FOOD_CANCEL_WHOLE_UNSENT
    is LocalFoodFinancialRequestV1.CloseResolvedWork -> LocalAction.FOOD_CLOSE_RESOLVED
    is LocalFoodFinancialRequestV1.RecordQualityClaim -> LocalAction.FOOD_RECORD_QUALITY_CLAIM
    else -> unavailable()
}

internal fun foodFinancialTarget(value: LocalFoodFinancialRequestV1): DomainId = when (value) {
    is LocalFoodFinancialRequestV1.BeginCollection -> value.orderId
    is LocalFoodFinancialRequestV1.FinalizePaid -> value.collectionId
    is LocalFoodFinancialRequestV1.SendReserved -> value.orderId
    is LocalFoodFinancialRequestV1.PaidPickup -> value.orderId
    is LocalFoodFinancialRequestV1.CancelWholeUnsent -> value.collectionId
    is LocalFoodFinancialRequestV1.CloseResolvedWork -> value.orderId
    is LocalFoodFinancialRequestV1.RecordQualityClaim -> value.claimId
    else -> unavailable()
}

internal fun foodFinancialSecurity(action: LocalAction): SecurityAction = when (action) {
    LocalAction.FOOD_BEGIN_COLLECTION -> SecurityAction.COLLECTION_BEGIN
    LocalAction.FOOD_FINALIZE_PAID -> SecurityAction.COLLECTION_FINALIZE // Fresh preparation selects ZERO from the actual quote.
    LocalAction.FOOD_CANCEL_WHOLE_UNSENT -> SecurityAction.COLLECTION_RESOLVE
    LocalAction.FOOD_SEND_RESERVED, LocalAction.FOOD_PAID_PICKUP, LocalAction.FOOD_CLOSE_RESOLVED -> SecurityAction.ORDER_EDIT
    LocalAction.FOOD_RECORD_QUALITY_CLAIM -> SecurityAction.RETURN_CLAIM
    else -> invalid()
}

internal fun foodFinancialActionLabel(action: LocalAction): LocalText = when (action) {
    LocalAction.FOOD_BEGIN_COLLECTION -> text("Lock food quote and reserve unsent ingredients", "قفل تسعير الطعام وحجز المكونات غير المرسلة")
    LocalAction.FOOD_FINALIZE_PAID -> text("Finalize actually covered food sale", "تثبيت بيع الطعام المغطى فعليًا")
    LocalAction.FOOD_SEND_RESERVED -> text("Send selected reserved portions", "إرسال الحصص المحجوزة المحددة")
    LocalAction.FOOD_PAID_PICKUP -> text("Record paid counter/takeaway pickup", "تسجيل استلام الطلب المدفوع من المنضدة أو للسفري")
    LocalAction.FOOD_CANCEL_WHOLE_UNSENT -> text("Cancel whole never-sent check — not payout", "إلغاء الطلب الكامل الذي لم يُرسل — ليس صرفًا")
    LocalAction.FOOD_CLOSE_RESOLVED -> text("Close resolved food responsibility", "إغلاق مسؤولية الطعام المحسومة")
    LocalAction.FOOD_RECORD_QUALITY_CLAIM -> text("Record unresolved quality/fault claim", "تسجيل مطالبة الجودة أو الخطأ غير المحسومة")
    else -> invalid()
}
