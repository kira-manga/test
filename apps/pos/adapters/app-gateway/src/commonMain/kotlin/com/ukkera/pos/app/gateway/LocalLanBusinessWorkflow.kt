package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.database.LocalMasterLanBusinessRpcKeysV1
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.sales.store.LocalSalesStoreProfile

/** Display/dispatch hints only. Every route still needs its installed Master typed opt-in. */
internal val lanBusinessActions: Set<LocalAction> = cashActions + safeFloatActions + retailOrderActions + lanCollectionActions + foodCheckActions +
    foodCashFinancialActions + kitchenStationActions + setOf(
        LocalAction.WAREHOUSE_CONFIGURE, LocalAction.TENDER_CONFIGURE, LocalAction.SALES_ROUTE_CONFIGURE,
        LocalAction.SUPPLIER_CREATE, LocalAction.SUPPLIER_UPDATE, LocalAction.RECEIVING_APPROVE,
        LocalAction.CATALOG_ACTIVATE, LocalAction.WAREHOUSE_TRANSFER, LocalAction.FOOD_ASSIGN_TABLE,
        LocalAction.CASH_ADD_CLOSE_NOTE,
        LocalAction.PAYMENT_CLASSIFICATION_CORRECT, LocalAction.RETAIL_CUSTOMER_RECEIPT,
        LocalAction.SUPPLIER_INVOICE_CAPTURE, LocalAction.SUPPLIER_INVOICE_MATCH_RECOGNIZE,
    )

internal fun requireLanBusinessWorkflow(query: LocalWorkflowQuery) {
    if (query.workflow !in setOf(LocalWorkflow.CHECKOUT, LocalWorkflow.ORDERS, LocalWorkflow.CASH,
            LocalWorkflow.CATALOG, LocalWorkflow.INVENTORY, LocalWorkflow.PURCHASING, LocalWorkflow.REPORTS,
            LocalWorkflow.SETUP, LocalWorkflow.FOOD, LocalWorkflow.KITCHEN)) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    if (query.workflow == LocalWorkflow.KITCHEN) requireKitchenWorkflow(query)
}

internal fun requireLanBusinessIntent(intent: LocalCommandIntent) {
    if (intent.action !in lanBusinessActions || !supportsLocalForm(intent) || intent.arrivalEvidence != null)
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    when (intent.action) {
        in retailOrderActions -> requireRetailOrderIntent(intent)
        in lanCollectionActions -> requireLanCollectionIntent(intent)
        in kitchenStationActions -> requireKitchenIntent(intent)
        in foodCheckActions -> requireFoodCheckIntent(intent)
        in foodCashFinancialActions -> requireFoodCashFinancialIntent(intent)
        LocalAction.FOOD_ASSIGN_TABLE -> requireFoodTableAssignmentIntent(intent)
        LocalAction.PAYMENT_CLASSIFICATION_CORRECT -> requirePaymentCorrectionIntent(intent)
        LocalAction.RETAIL_CUSTOMER_RECEIPT -> requireCustomerReceiptIntent(intent)
        LocalAction.SUPPLIER_INVOICE_CAPTURE -> requireInvoiceCaptureIntent(intent)
        LocalAction.SUPPLIER_INVOICE_MATCH_RECOGNIZE -> requireInvoiceMatchingIntent(intent)
        else -> Unit // The finite action set above is further checked against its exact typed command.
    }
}

internal fun requireLanBusinessMutation(action: LocalAction, request: MutationRequest) {
    if (action !in lanBusinessActions || !LocalMasterLanBusinessRpcKeysV1.accepts(request.command) ||
        request.actionApprovalId != null || normalizeLocalRequest(request.command, request.payload) != request.payload ||
        !actionMatches(action, request)) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    when (action) {
        in cashActions -> if (request.command != LocalSalesStoreProfile.CASH_COMMAND)
            throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
        in retailOrderActions -> retailOrderRequest(action, request)
        in lanCollectionActions -> lanCollectionRequest(action, request)
        in kitchenStationActions -> kitchenStationRequest(action, request)
        in foodCheckActions -> foodCheckRequest(action, request)
        in foodCashFinancialActions -> foodCashFinancialRequest(action, request)
        LocalAction.FOOD_ASSIGN_TABLE -> foodTableAssignmentRequest(action, request)
        LocalAction.WAREHOUSE_TRANSFER -> normalizedStockTransferRequest(action, request)
        LocalAction.PAYMENT_CLASSIFICATION_CORRECT -> requirePaymentCorrectionMutation(action, request)
        LocalAction.RETAIL_CUSTOMER_RECEIPT -> customerReceiptRequest(action, request)
        LocalAction.SUPPLIER_INVOICE_CAPTURE -> invoiceCaptureRequest(action, request)
        LocalAction.SUPPLIER_INVOICE_MATCH_RECOGNIZE -> invoiceMatchingRequest(action, request)
        else -> Unit
    }
}

internal fun requireLanBusinessOriginal(entry: StoredClientIntentV1) {
    requireLanBusinessMutation(entry.localAction, entry.request())
    if (entry.localAction in retailOrderActions) savedRetailOrderRequest(entry)
    if (entry.localAction in lanCollectionActions) savedLanCollectionRequest(entry)
    if (entry.localAction in kitchenStationActions) savedKitchenStationRequest(entry)
    if (entry.localAction in foodCheckActions) savedFoodCheckRequest(entry)
    if (entry.localAction in foodCashFinancialActions) savedFoodCashFinancialRequest(entry)
    if (entry.localAction == LocalAction.FOOD_ASSIGN_TABLE) savedFoodTableAssignmentRequest(entry)
    if (entry.localAction == LocalAction.WAREHOUSE_TRANSFER) savedStockTransferRequest(entry)
}
