package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.inventory.store.LocalStockRelationalQueriesV2
import com.ukkera.pos.sales.store.*
import kotlinx.serialization.KSerializer

/** Fixed connection hints only; shared keys do not admit their other bodies. */
internal val foodCashFinancialCommands: Set<CommandKey> = setOf(LocalFoodStockCodecV2.COMMAND,
    LocalFoodPaymentProfile.COMMAND)
internal val foodCashFinancialReads: Set<ReadKey> = setOf(LocalFoodProfileV1.QUERY,
    LocalFoodCheckKitchenCodecV1.QUERY, LocalFoodCheckKitchenCodecV2.QUERY, LocalFoodCheckKitchenCodecV3.QUERY,
    LocalStockRelationalQueriesV2.PROFILE, LocalFoodFinancialProfileV1.QUERY, LocalFoodPaymentReadCodec.QUERY)
internal val foodCashFinancialActions: Set<LocalAction> = setOf(LocalAction.FOOD_BEGIN_COLLECTION,
    LocalAction.FOOD_FINALIZE_PAID, LocalAction.FOOD_PAYMENT_APPLY)

internal fun requireFoodCashFinancialWorkflow(query: LocalWorkflowQuery) {
    if (query.workflow != LocalWorkflow.FOOD || query.food?.let(::foodCashFinancialQuery) != true ||
        query.search.isNotEmpty() || query.reference != null || query.cursor != null || query.pricePreview != null ||
        query.branchReport != null || query.purchasing != null || query.returns != null || query.cash != null ||
        query.inventory != null || query.openingAllocations != null)
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
}

private fun foodCashFinancialQuery(query: LocalFoodUiQuery): Boolean = query is LocalFoodUiQuery.MyChecks ||
    query is LocalFoodUiQuery.Check || query is LocalFoodUiQuery.Collection || query is LocalFoodUiQuery.Receipt ||
    query is LocalFoodUiQuery.PaymentOriginal || query is LocalFoodUiQuery.PaymentAttempt || query is LocalFoodUiQuery.OwnPendingPayments

/** Form1 is ordinary SQL financial work, not a native form2 payment instruction. */
internal fun requireFoodCashFinancialIntent(intent: LocalCommandIntent) {
    val fields = when (intent.action) {
        LocalAction.FOOD_BEGIN_COLLECTION -> setOf(LocalField.ORDER_ID, LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION,
            LocalField.COLLECTION_ID, LocalField.SESSION_ID, LocalField.EXPECTED_SESSION_REVISION, LocalField.AMOUNT)
        LocalAction.FOOD_FINALIZE_PAID -> setOf(LocalField.COLLECTION_ID, LocalField.EXPECTED_COLLECTION_REVISION,
            LocalField.ORDER_ID, LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION, LocalField.SALE_ID)
        LocalAction.FOOD_PAYMENT_APPLY -> setOf(LocalField.FOOD_PAYMENT_RECORDING_OPERATION_ID,
            LocalField.ORDER_ID, LocalField.COLLECTION_ID, LocalField.AMOUNT, LocalField.FOOD_PAYMENT_ORIGINAL_BODY_DIGEST)
        else -> throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    }
    if (intent.formVersion != 1 || intent.reference != null || intent.arrivalEvidence != null ||
        intent.lineField != null || intent.lines.isNotEmpty() || intent.values.size != fields.size ||
        intent.values.map { it.field }.toSet() != fields)
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
}

internal fun requireFoodCashFinancialRead(request: ReadRequest) {
    try {
        when (request.query) {
            LocalFoodProfileV1.QUERY -> {
                val query = LocalFoodReadCodecV1.decode(request.payload)
                if (query !is LocalFoodQueryV1.Own && query !is LocalFoodQueryV1.Order)
                    throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
                if (LocalFoodReadCodecV1.encode(query) != request.payload) invalid()
            }
            LocalFoodCheckKitchenCodecV1.QUERY, LocalFoodCheckKitchenCodecV2.QUERY, LocalFoodCheckKitchenCodecV3.QUERY -> {
                val query = LocalFoodReadCodecV1.decode(request.payload)
                if (query !is LocalFoodQueryV1.Order) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
                if (LocalFoodReadCodecV1.encode(query) != request.payload) invalid()
            }
            LocalStockRelationalQueriesV2.PROFILE ->
                if (request.payload != LocalStockRelationalQueriesV2.profile().payload) invalid()
            LocalFoodFinancialProfileV1.QUERY -> {
                val query = foodCashFinancialSelector(request.payload, LocalFoodFinancialQueryV1.serializer())
                if (query !is LocalFoodFinancialQueryV1.Collection && query !is LocalFoodFinancialQueryV1.Receipt)
                    throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
                if (LocalFoodFinancialReadCodecV1.encode(query) != request.payload) invalid()
            }
            LocalFoodPaymentReadCodec.QUERY -> {
                val query = foodCashFinancialSelector(request.payload, LocalFoodPaymentQuery.serializer())
                if (query !is LocalFoodPaymentQuery.AttemptRecording && query !is LocalFoodPaymentQuery.Original &&
                    query !is LocalFoodPaymentQuery.OwnPending) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
                if (LocalFoodPaymentReadCodec.encode(query) != request.payload) invalid()
            }
            else -> throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
        }
    } catch (failure: GatewayFailure) { throw failure }
    catch (_: Exception) { invalid() }
}

/** These selectors contain only flat canonical IDs and a bounded integer frontier. */
private fun <T> foodCashFinancialSelector(payload: StoredBytes, serializer: KSerializer<T>): T {
    require(payload.size <= 2_048)
    val text = payload.copyBytes().useWiped { it.decodeToString(throwOnInvalidSequence = true) }
    require(text.count { it == '{' } == 1 && text.count { it == '}' } == 1 && text.none { it == '[' || it == ']' })
    return journalJson.decodeFromString(serializer, text)
}
