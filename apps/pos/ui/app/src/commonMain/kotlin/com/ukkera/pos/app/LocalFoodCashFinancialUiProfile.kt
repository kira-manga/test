package com.ukkera.pos.app

import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.ExactDecimal
import com.ukkera.pos.types.Money

/** A closed presentation subset, never a physical capability or Master authorization. */
internal val foodCashFinancialUiActions: Set<LocalAction> = setOf(LocalAction.FOOD_BEGIN_COLLECTION,
    LocalAction.FOOD_FINALIZE_PAID, LocalAction.FOOD_PAYMENT_APPLY)

internal fun foodCashFinancialUiRoute(route: AppRoute): Boolean = route == AppRoute.Orders || route == AppRoute.Food ||
    route is AppRoute.FoodOrder || route == AppRoute.SessionAccess

internal fun LocalFoodUiQuery.isFoodCashFinancialUiQuery(): Boolean = this is LocalFoodUiQuery.MyChecks ||
    this is LocalFoodUiQuery.Check || this is LocalFoodUiQuery.Collection || this is LocalFoodUiQuery.Receipt ||
    this is LocalFoodUiQuery.PaymentOriginal || this is LocalFoodUiQuery.PaymentAttempt || this is LocalFoodUiQuery.OwnPendingPayments

private fun foodCashFinancialFields(action: LocalAction): Set<LocalField> = when (action) {
    LocalAction.FOOD_BEGIN_COLLECTION -> setOf(LocalField.ORDER_ID, LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION,
        LocalField.COLLECTION_ID, LocalField.SESSION_ID, LocalField.EXPECTED_SESSION_REVISION, LocalField.AMOUNT)
    LocalAction.FOOD_FINALIZE_PAID -> setOf(LocalField.COLLECTION_ID, LocalField.EXPECTED_COLLECTION_REVISION,
        LocalField.ORDER_ID, LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION, LocalField.SALE_ID)
    LocalAction.FOOD_PAYMENT_APPLY -> setOf(LocalField.FOOD_PAYMENT_RECORDING_OPERATION_ID,
        LocalField.ORDER_ID, LocalField.COLLECTION_ID, LocalField.AMOUNT, LocalField.FOOD_PAYMENT_ORIGINAL_BODY_DIGEST)
    else -> emptySet()
}

private fun LocalScalarValue?.foodCashMoney(): Money? = try {
    val value = this as? LocalScalarValue.Decimal
    value?.let { Money(Currency.EGP, ExactDecimal.parse(it.canonical, 2).toScaledLongExact(2)) }
} catch (_: IllegalArgumentException) { null } catch (_: ArithmeticException) { null }

/** Fixed action/form/body coordinates. No reference pickers, native IDs or broader FOOD editors. */
internal fun LocalActionForm.isFoodCashFinancialUiForm(query: LocalFoodUiQuery, body: LocalFoodUiBody? = null): Boolean {
    if (action !in foodCashFinancialUiActions || version != 1 || reservedOperationId != null || openingAllocations != null ||
        arrivalEvidence != null || confirmation == null || fields.any { it !is LocalFormField.Scalar }) return false
    val scalars = fields.filterIsInstance<LocalFormField.Scalar>()
    if (scalars.size != foodCashFinancialFields(action).size || scalars.map { it.field }.toSet() != foodCashFinancialFields(action) ||
        scalars.any { !it.required || it.referenceSource != null || it.references.isNotEmpty() ||
            it.kind in setOf(LocalInputKind.REFERENCE, LocalInputKind.MULTI_REFERENCE) }) return false
    fun scalar(field: LocalField): LocalFormField.Scalar = scalars.single { it.field == field }
    fun id(field: LocalField): DomainId? = (scalar(field).initialValue as? LocalScalarValue.Identifier)?.value
    fun revision(field: LocalField): Long? = (scalar(field).initialValue as? LocalScalarValue.Integer)?.value
    val identifiers = scalars.filter { it.field in setOf(LocalField.ORDER_ID, LocalField.COLLECTION_ID,
        LocalField.SESSION_ID, LocalField.SALE_ID, LocalField.FOOD_PAYMENT_RECORDING_OPERATION_ID) }
    if (identifiers.any { it.kind != LocalInputKind.IDENTIFIER }) return false
    return when (action) {
        LocalAction.FOOD_BEGIN_COLLECTION -> {
            if (query !is LocalFoodUiQuery.Check || id(LocalField.ORDER_ID) != query.orderId ||
                revision(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION)?.let { it >= 1 } != true ||
                id(LocalField.COLLECTION_ID) == null || scalar(LocalField.AMOUNT).initialValue.foodCashMoney()?.minor?.let { it >= 0 } != true ||
                scalars.any { it.readOnly != (it.field !in setOf(LocalField.SESSION_ID, LocalField.EXPECTED_SESSION_REVISION)) } ||
                scalar(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION).kind != LocalInputKind.INTEGER ||
                scalar(LocalField.EXPECTED_SESSION_REVISION).kind != LocalInputKind.INTEGER ||
                scalar(LocalField.AMOUNT).kind != LocalInputKind.DECIMAL) return false
            if (body == null) true else {
                val check = body as? LocalFoodUiBody.Check ?: return false
                check.check.orderId == query.orderId && check.check.orderStorageRevision.value == revision(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION) &&
                    check.check.collectionId == null && check.financial == null && !check.check.workClosed && check.check.handoffId == null &&
                    check.check.service.channel != "DELIVERY" && check.check.portionCount > 0 && !check.chargeResolutionRequired &&
                    !check.kitchenUnavailableForUnsentWork && check.originalQuotedTotal == scalar(LocalField.AMOUNT).initialValue.foodCashMoney()
            }
        }
        LocalAction.FOOD_FINALIZE_PAID -> {
            if (query !is LocalFoodUiQuery.Collection || id(LocalField.COLLECTION_ID) != query.collectionId ||
                id(LocalField.ORDER_ID) == null || id(LocalField.SALE_ID) == null || scalars.any { !it.readOnly } ||
                scalar(LocalField.EXPECTED_COLLECTION_REVISION).kind != LocalInputKind.INTEGER ||
                scalar(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION).kind != LocalInputKind.INTEGER ||
                revision(LocalField.EXPECTED_COLLECTION_REVISION)?.let { it >= 0 } != true ||
                revision(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION)?.let { it >= 1 } != true) return false
            if (body == null) true else {
                val source = (body as? LocalFoodUiBody.Financial)?.cashFinancialSource as? LocalFoodCashFinancialReadSource.Collection ?: return false
                source.query == query && source.orderId == id(LocalField.ORDER_ID) &&
                    source.collectionDomainRevision.value == revision(LocalField.EXPECTED_COLLECTION_REVISION) &&
                    source.orderStorageRevision.value == revision(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION)
            }
        }
        LocalAction.FOOD_PAYMENT_APPLY -> {
            if (query !is LocalFoodUiQuery.PaymentOriginal || id(LocalField.FOOD_PAYMENT_RECORDING_OPERATION_ID)?.value != query.recordingOperationId.value ||
                id(LocalField.ORDER_ID) == null || id(LocalField.COLLECTION_ID) == null || scalars.any { !it.readOnly } ||
                scalar(LocalField.AMOUNT).kind != LocalInputKind.DECIMAL ||
                scalar(LocalField.AMOUNT).initialValue.foodCashMoney()?.minor?.let { it > 0 } != true ||
                scalar(LocalField.FOOD_PAYMENT_ORIGINAL_BODY_DIGEST).kind != LocalInputKind.TEXT) return false
            val digest = (scalar(LocalField.FOOD_PAYMENT_ORIGINAL_BODY_DIGEST).initialValue as? LocalScalarValue.Text)?.value ?: return false
            if (digest.length != 64 || digest.any { it !in '0'..'9' && it !in 'a'..'f' }) return false
            if (body == null) true else {
                val source = (body as? LocalFoodUiBody.Financial)?.cashFinancialSource as? LocalFoodCashFinancialReadSource.Original ?: return false
                source.query == query && source.applicationPending && source.orderId == id(LocalField.ORDER_ID) &&
                    source.collectionId == id(LocalField.COLLECTION_ID) && source.amount == scalar(LocalField.AMOUNT).initialValue.foodCashMoney() &&
                    source.bodyDigest.value == digest
            }
        }
        else -> false
    }
}

internal fun LocalWorkflowPage.matchesFoodCashFinancialUiPage(query: LocalFoodUiQuery): Boolean {
    val source = food ?: return false
    if (workflow != LocalWorkflow.FOOD || !query.isFoodCashFinancialUiQuery() || source.query != query ||
        source.next?.isFoodCashFinancialUiQuery() == false || records.isNotEmpty() || nextCursor != null || priceControls != null ||
        branchReport != null || openingAllocations != null || managerQuoteDetails != null || returnRights != null ||
        forms.any { !it.isFoodCashFinancialUiForm(query, source.body) }) return false
    return when (query) {
        is LocalFoodUiQuery.MyChecks -> source.body is LocalFoodUiBody.Checks && forms.isEmpty()
        is LocalFoodUiQuery.Check -> {
            val check = source.body as? LocalFoodUiBody.Check ?: return false
            val kitchen = check.kitchen ?: return false
            !check.tableAssignmentAvailable && check.check.orderStorageRevision.value >= 1 &&
                (kitchen.unsentPortionCount != 0 || kitchen.status == LocalFoodUiKitchenStatus.NO_UNSENT_WORK) &&
                forms.all { it.action == LocalAction.FOOD_BEGIN_COLLECTION }
        }
        else -> {
            val body = source.body as? LocalFoodUiBody.Financial ?: return false
            val coordinates = body.cashFinancialSource ?: return false
            if (coordinates.query != query || body.links.any { !it.query.isFoodCashFinancialUiQuery() }) return false
            when (query) {
                is LocalFoodUiQuery.Collection -> coordinates is LocalFoodCashFinancialReadSource.Collection &&
                    forms.all { it.action == LocalAction.FOOD_FINALIZE_PAID }
                is LocalFoodUiQuery.PaymentOriginal -> coordinates is LocalFoodCashFinancialReadSource.Original &&
                    coordinates.originalActorId == source.actorId && coordinates.recordingActorId == source.actorId &&
                    forms.all { it.action == LocalAction.FOOD_PAYMENT_APPLY }
                else -> coordinates is LocalFoodCashFinancialReadSource.ReadOnly && forms.isEmpty()
            }
        }
    }
}

internal fun LocalCommandIntent.isFoodCashFinancialUiIntent(): Boolean {
    if (action !in foodCashFinancialUiActions || formVersion != 1 || reference != null || arrivalEvidence != null ||
        lineField != null || lines.isNotEmpty() || values.map { it.field }.toSet() != foodCashFinancialFields(action) ||
        values.size != foodCashFinancialFields(action).size) return false
    fun id(field: LocalField): DomainId? = (values.singleOrNull { it.field == field }?.value as? LocalScalarValue.Identifier)?.value
    fun revision(field: LocalField): Long? = (values.singleOrNull { it.field == field }?.value as? LocalScalarValue.Integer)?.value
    if (id(LocalField.ORDER_ID) == null || id(LocalField.COLLECTION_ID) == null) return false
    return when (action) {
        LocalAction.FOOD_BEGIN_COLLECTION -> id(LocalField.SESSION_ID) != null &&
            revision(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION)?.let { it >= 1 } == true &&
            revision(LocalField.EXPECTED_SESSION_REVISION)?.let { it >= 0 } == true &&
            values.single { it.field == LocalField.AMOUNT }.value.foodCashMoney()?.minor?.let { it >= 0 } == true
        LocalAction.FOOD_FINALIZE_PAID -> id(LocalField.SALE_ID) != null &&
            revision(LocalField.EXPECTED_COLLECTION_REVISION)?.let { it >= 0 } == true &&
            revision(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION)?.let { it >= 1 } == true
        LocalAction.FOOD_PAYMENT_APPLY -> id(LocalField.FOOD_PAYMENT_RECORDING_OPERATION_ID)?.value?.let { it != operationId.value } == true &&
            values.single { it.field == LocalField.AMOUNT }.value.foodCashMoney()?.minor?.let { it > 0 } == true &&
            (values.single { it.field == LocalField.FOOD_PAYMENT_ORIGINAL_BODY_DIGEST }.value as? LocalScalarValue.Text)?.value?.let {
                it.length == 64 && it.all { character -> character in '0'..'9' || character in 'a'..'f' }
            } == true
        else -> false
    }
}

internal fun LocalPendingOperation.isFoodCashFinancialUiPending(): Boolean = action in foodCashFinancialUiActions && actionApproval == null &&
    (action == LocalAction.FOOD_PAYMENT_APPLY || recordedDecision != LocalRecordedDecision.RECORDED_ONLY)

internal fun LocalOperationResult.isFoodCashFinancialUiResult(action: LocalAction? = null, frozen: LocalCommandIntent? = null): Boolean {
    if (action != null && action !in foodCashFinancialUiActions || frozen?.isFoodCashFinancialUiIntent() == false ||
        frozen != null && action != null && frozen.action != action) return false
    if (this is LocalOperationResult.Pending) return operation.isFoodCashFinancialUiPending() && (action == null || operation.action == action)
    if (this is LocalOperationResult.Unresolved) return true
    val recorded = this as? LocalOperationResult.Recorded ?: return false
    val details = recorded.foodCashFinancial ?: return false
    if (recorded.food != null || recorded.returnStock != null || details.operationId != recorded.operationId ||
        recorded.originalAction != details.original.action || action != null && details.original.action != action) return false
    val original = details.original
    if (frozen != null) {
        if (frozen.operationId != recorded.operationId || frozen.action != original.action) return false
        fun id(field: LocalField): DomainId? = (frozen.values.singleOrNull { it.field == field }?.value as? LocalScalarValue.Identifier)?.value
        fun revision(field: LocalField): Long? = (frozen.values.singleOrNull { it.field == field }?.value as? LocalScalarValue.Integer)?.value
        when (original) {
            is LocalFoodCashFinancialOriginal.BeginCollection -> if (id(LocalField.ORDER_ID) != original.orderId ||
                id(LocalField.COLLECTION_ID) != original.collectionId || id(LocalField.SESSION_ID) != original.sessionId ||
                revision(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION) != original.expectedOrderStorageRevision.value ||
                revision(LocalField.EXPECTED_SESSION_REVISION) != original.expectedSessionDomainRevision.value || details.committed &&
                frozen.values.single { it.field == LocalField.AMOUNT }.value.foodCashMoney() != details.amount) return false
            is LocalFoodCashFinancialOriginal.FinalizePaid -> if (id(LocalField.COLLECTION_ID) != original.collectionId ||
                id(LocalField.SALE_ID) != original.saleId || revision(LocalField.EXPECTED_COLLECTION_REVISION) != original.expectedCollectionDomainRevision.value) return false
            is LocalFoodCashFinancialOriginal.ApplyOriginal -> if (id(LocalField.FOOD_PAYMENT_RECORDING_OPERATION_ID)?.value != original.recordingOperationId.value ||
                details.collectionId != null && id(LocalField.COLLECTION_ID) != details.collectionId) return false
        }
    }
    fun reference(kind: LocalRecordKind, id: DomainId): Boolean = recorded.result?.reference == LocalRecordReference(kind, id)
    if (original !is LocalFoodCashFinancialOriginal.ApplyOriginal) {
        if (details.application != null || details.applicationOperationId != null || details.pendingReason != null) return false
        if (!details.committed) return recorded.decision == LocalRecordedDecision.REJECTED && recorded.result == null &&
            details.collectionId == null && details.saleId == null && details.amount == null && details.followUp == null
        if (recorded.decision != LocalRecordedDecision.APPLIED || details.amount?.let { it.currency == Currency.EGP && it.minor >= 0 } != true) return false
        return when (original) {
            is LocalFoodCashFinancialOriginal.BeginCollection -> details.code == "food.collection.locked" && details.collectionId == original.collectionId &&
                details.saleId == null && reference(LocalRecordKind.FOOD_COLLECTION, original.collectionId) && details.followUp == LocalFoodUiQuery.Collection(original.collectionId)
            is LocalFoodCashFinancialOriginal.FinalizePaid -> details.code == "food.sale.finalized" && details.collectionId == original.collectionId &&
                details.saleId == original.saleId && reference(LocalRecordKind.FOOD_SALE, original.saleId) && details.followUp == LocalFoodUiQuery.Receipt(original.saleId)
            else -> false
        }
    }
    if (details.saleId != null || details.amount != null) return false
    return when (details.application) {
        LocalFoodCashFinancialApplication.CASH_PAID, LocalFoodCashFinancialApplication.NOT_PAID, LocalFoodCashFinancialApplication.UNKNOWN ->
            details.committed && details.code == "committed" && details.collectionId != null &&
                details.applicationOperationId == recorded.operationId && details.pendingReason == null &&
                recorded.decision == (if (details.application == LocalFoodCashFinancialApplication.CASH_PAID) LocalRecordedDecision.APPLIED else LocalRecordedDecision.RECORDED_ONLY) &&
                reference(LocalRecordKind.FOOD_COLLECTION, details.collectionId) && details.followUp == LocalFoodUiQuery.Collection(details.collectionId)
        LocalFoodCashFinancialApplication.PENDING_RECONCILIATION -> !details.committed && details.code == "food.payment.pending_reconciliation" &&
            recorded.decision == LocalRecordedDecision.RECORDED_ONLY && details.collectionId != null && details.pendingReason != null &&
            details.applicationOperationId == null && reference(LocalRecordKind.FOOD_COLLECTION, details.collectionId) &&
            details.followUp == LocalFoodUiQuery.PaymentOriginal(original.recordingOperationId)
        LocalFoodCashFinancialApplication.ALREADY_APPLIED -> !details.committed && details.code == "food.payment.already_applied" &&
            recorded.decision == LocalRecordedDecision.RECORDED_ONLY && recorded.result == null && details.collectionId == null &&
            details.pendingReason == null && details.applicationOperationId?.let { it != recorded.operationId && it != original.recordingOperationId } == true &&
            details.followUp == LocalFoodUiQuery.PaymentOriginal(original.recordingOperationId)
        null -> !details.committed && details.code == "food.payment.not_available" && recorded.decision == LocalRecordedDecision.REJECTED &&
            recorded.result == null && details.collectionId == null && details.applicationOperationId == null && details.pendingReason == null && details.followUp == null
    }
}

/** Incompatible retained input is inert, with its journal and original recovery identity preserved. */
internal fun LocalWorkspaceState.isFoodCashFinancialUiWorkspace(): Boolean = workflow == LocalWorkflow.FOOD && search.isEmpty() &&
    reference == null && foodQuery.isFoodCashFinancialUiQuery() &&
    form?.let { it.descriptor.isFoodCashFinancialUiForm(foodQuery) && it.foodRecipientSource == null } != false &&
    formInput?.let { it.action in foodCashFinancialUiActions && it.version == 1 && it.reservedOperationId == null &&
        it.openingQuery == null && it.foodRecipientSource == null && it.lineField == null && it.lines.isEmpty() &&
        it.scalars.keys == foodCashFinancialFields(it.action) && it.values.keys.all { field -> field in foodCashFinancialFields(it.action) } } != false &&
    page?.matchesFoodCashFinancialUiPage(foodQuery) != false && pending.all { it.isFoodCashFinancialUiPending() } &&
    operation?.isFoodCashFinancialUiResult() != false && referencePicker == null && openingCounts == null && priceDraft == null &&
    pricePreview == null && kitchenBoardOrigin == null && actionApproval == null && !purchasingDocuments.hasDraft && foodPricePreview == null
