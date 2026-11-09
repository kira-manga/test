package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.inventory.store.LocalStockRelationalCommandsV2
import com.ukkera.pos.inventory.store.LocalStockRelationalCommand
import com.ukkera.pos.inventory.store.LocalStockRelationalProfileV2
import com.ukkera.pos.inventory.store.LocalStockCommands
import com.ukkera.pos.inventory.store.LocalUnpricedArrivalCommandsV1
import com.ukkera.pos.inventory.store.LocalUnpricedArrivalCommandsV2
import com.ukkera.pos.inventory.store.LocalStockStoreProfile
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.*

internal data class PreparedLocalIntent(val request: MutationRequest, val label: LocalText, val summary: List<LocalRecordValue>)

internal suspend fun readLocalWorkflow(access: GatewayAccess, query: LocalWorkflowQuery): LocalWorkflowPage {
    access.installed.requireWorkflow(query)
    return if (query.reference?.kind in returnRightsRecordKinds) returnRightsWorkflow(access, query)
    else if ((query.search.startsWith("manager:") || query.search.startsWith("reassign:")) && query.workflow != LocalWorkflow.ORDERS) invalid()
    else when (query.workflow) {
    LocalWorkflow.CASH -> cashWorkflow(access, query)
    LocalWorkflow.CHECKOUT, LocalWorkflow.ORDERS -> orderWorkflow(access, query)
    LocalWorkflow.INVENTORY, LocalWorkflow.RECEIVING -> stockWorkflow(access, query)
    LocalWorkflow.CATALOG -> if (query.pricePreview != null || query.reference?.kind in pricingRecordKinds)
        pricingWorkflow(access, query) else configurationWorkflow(access, query).let { page ->
            if (query.reference == null && query.search.isEmpty() && query.cursor == null)
                page.copy(records = page.records + priceIndexNavigation(access)) else page
        }
    LocalWorkflow.PURCHASING -> purchasingWorkflow(access, query)
    LocalWorkflow.SETUP -> configurationWorkflow(access, query)
    LocalWorkflow.REPORTS -> branchReportWorkflow(access, query)
    LocalWorkflow.FOOD -> foodWorkflow(access, query)
    LocalWorkflow.KITCHEN -> kitchenWorkflow(access, query)
    LocalWorkflow.FULFILLMENT -> fulfillmentWorkflow(access, query)
    LocalWorkflow.RETURNS -> returnCaseWorkflow(access, query)
    else -> throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    }
}

internal suspend fun prepareLocalIntent(access: GatewayAccess, intent: LocalCommandIntent): PreparedLocalIntent {
    // Profile selection precedes any preparatory domain read, including shared-key variants.
    access.installed.requireIntent(intent)
    // Company-signed content enters only through its native-completed publication gateway.
    // Generic branch forms cannot create unsigned content or mint company signing authority;
    // activation of an already received price policy remains a separate protected command.
    if (intent.action in setOf(LocalAction.CATALOG_ACTIVATE, LocalAction.CATALOG_RELEASE_ACTIVATE, LocalAction.SERIAL_REGISTRATION_ACTIVATE,
            LocalAction.RETURN_RIGHTS_ACTIVATE, LocalAction.PRICE_POLICY_RECEIVE) ||
        intent.action in foodConfigurationActions)
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    if (intent.action in physicalActions) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    if (!supportsLocalForm(intent)) invalid()
    if (intent.action == LocalAction.SUPPLIER_INVOICE_MATCH_RECOGNIZE) {
        val prepared = prepareCapturedInvoiceMatch(access, intent)
        if (!actionMatches(intent.action, prepared.request)) unavailable()
        if (access.installed.isRestricted) access.installed.requireMutation(intent.action, prepared.request)
        return prepared
    }
    val request = when (intent.action) {
        LocalAction.RETURN_STOCK_POST -> prepareReturnStock(access, intent)
        LocalAction.FOOD_ASSIGN_TABLE -> prepareFoodTableAssignment(access, intent)
        LocalAction.RETAIL_CUSTOMER_RECEIPT -> prepareCustomerReceipt(access, intent)
        in floorConfigurationActions -> prepareFoodFloor(access, intent)
        LocalAction.CASH_ADD_CLOSE_NOTE -> prepareCashCloseNote(access, intent)
        LocalAction.PAYMENT_CLASSIFICATION_CORRECT -> preparePaymentCorrection(access, intent)
        LocalAction.ORDER_REASSIGN -> prepareManagerReassignment(access, intent)
        in cashActions -> prepareCash(access, intent)
        in orderActions -> prepareOrder(access, intent)
        in collectionActions -> prepareCollection(access, intent)
        in pricingActions -> preparePricing(access, intent)
        LocalAction.COUNT_APPROVE_SURPLUS -> prepareCountSurplus(access, intent)
        in unpricedArrivalActions -> if (intent.formVersion == 2) prepareUnpricedArrivalV2(access, intent)
            else prepareUnpricedArrival(access, intent)
        in stockActions -> prepareStock(access, intent)
        in configurationActions -> prepareConfiguration(access, intent)
        in purchasingActions -> preparePurchasing(access, intent)
        in returnCaseActions -> prepareReturnCase(access, intent)
        in foodFinancialActions -> prepareFoodFinancial(access, intent)
        in foodActions -> prepareFood(access, intent)
        else -> throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    }
    if (!actionMatches(intent.action, request)) unavailable()
    if (access.installed.isRestricted) access.installed.requireMutation(intent.action, request)
    return PreparedLocalIntent(request, actionLabel(intent.action), when (intent.action) {
        LocalAction.RETURN_STOCK_POST -> returnStockIntentSummary(request)
        LocalAction.FOOD_ASSIGN_TABLE -> tableAssignmentIntentSummary(request)
        LocalAction.RETAIL_CUSTOMER_RECEIPT -> customerReceiptIntentSummary(request)
        LocalAction.CASH_ADD_CLOSE_NOTE -> cashCloseNoteSummary(request)
        LocalAction.PAYMENT_CLASSIFICATION_CORRECT -> paymentCorrectionSummary(request)
        in foodActions -> foodIntentSummary(intent,
            if (intent.action == LocalAction.FOOD_CREATE_DRAFT) savedFoodDraft(request) ?: unavailable() else null)
        in foodFinancialActions -> foodFinancialIntentSummary(request, intent)
        LocalAction.SUPPLIER_OPENING_RECORD -> openingSupplierObligationSummary(request)
        in purchasingActions -> purchasingIntentSummary(request)
        in returnCaseActions -> returnCaseIntentSummary(request)
        in pricingActions -> pricingIntentSummary(intent, request)
        LocalAction.CASH_SUBMIT_CLOSE -> cashCloseIntentSummary(request)
        LocalAction.CASH_RECORD_COUNT, LocalAction.CASH_DELIVER_COUNTED, LocalAction.CASH_SUBMIT_COUNTED -> cashCountIntentSummary(request)
        in unpricedArrivalActions -> if (request.command in unpricedArrivalKeysV2) unpricedArrivalIntentSummaryV2(request,
            InputValues(intent.values).id(LocalField.SUPPLIER_ID))
            else intentSummary(intent)
        else -> intentSummary(intent)
    })
}

internal val cashActions: Set<LocalAction> = setOf(LocalAction.DRAWER_CONFIGURE, LocalAction.CUSTODIAN_CONFIGURE,
    LocalAction.SAFE_CONFIGURE, LocalAction.CASH_OPEN, LocalAction.CASH_BEGIN_CLOSE, LocalAction.CASH_CANCEL_CLOSE,
    LocalAction.CASH_SUBMIT_CLOSE, LocalAction.CASH_REVIEW_CLOSE, LocalAction.CASH_DROP_TO_SAFE, LocalAction.CASH_RECEIVE_SAFE, LocalAction.CASH_NON_SALE,
    LocalAction.CASH_RECORD_COUNT, LocalAction.CASH_DELIVER_COUNTED, LocalAction.CASH_SUBMIT_COUNTED)
internal val orderActions: Set<LocalAction> = setOf(LocalAction.ORDER_CREATE, LocalAction.ORDER_REPLACE,
    LocalAction.ORDER_OFFER_HANDOFF, LocalAction.ORDER_CANCEL_HANDOFF, LocalAction.ORDER_ACCEPT_HANDOFF)
internal val collectionActions: Set<LocalAction> = setOf(LocalAction.COLLECTION_PREPARE, LocalAction.SALE_FINALIZE)
/** Explicit additive forms never rewrite saved versions or select another command on retry. */
internal fun supportsLocalForm(intent: LocalCommandIntent): Boolean = if (intent.action == LocalAction.ORDER_REASSIGN)
    intent.formVersion == 2
else intent.formVersion == 1 ||
    intent.formVersion == 2 && (intent.action in orderActions || intent.action in collectionActions ||
        intent.action in supplierConfigurationActions ||
        intent.action == LocalAction.FOOD_CREATE_DRAFT || intent.action == LocalAction.FOOD_ADD ||
        intent.action == LocalAction.CASH_SUBMIT_CLOSE || intent.action == LocalAction.SUPPLIER_INVOICE_RECORD ||
        intent.action == LocalAction.SUPPLIER_INVOICE_MATCH ||
        intent.action == LocalAction.SUPPLIER_CREDIT_RECORD || intent.action == LocalAction.RETAIL_CUSTOMER_RECEIPT ||
        intent.action in unpricedArrivalActions) ||
    intent.formVersion == 3 && intent.action == LocalAction.FOOD_ADD
internal val branchPriceActions: Set<LocalAction> = setOf(LocalAction.PRICE_SET, LocalAction.PRICE_RESET)
internal val pricingAdministrationActions: Set<LocalAction> = setOf(LocalAction.PRICE_POLICY_RECEIVE,
    LocalAction.PRICE_POLICY_ACTIVATE, LocalAction.PRICE_HISTORY_INDEX)
internal val pricingActions: Set<LocalAction> = branchPriceActions + pricingAdministrationActions
internal val stockActions: Set<LocalAction> = setOf(LocalAction.RECEIPT_POST, LocalAction.STOCK_OPENING, LocalAction.STOCK_INITIALIZE,
    LocalAction.INSPECTION_RELEASE, LocalAction.COUNT_CAPTURE, LocalAction.COUNT_POST, LocalAction.STOCK_RECALL, LocalAction.WAREHOUSE_TRANSFER)
internal val configurationActions: Set<LocalAction> = setOf(LocalAction.WAREHOUSE_CONFIGURE, LocalAction.TENDER_CONFIGURE,
    LocalAction.SUPPLIER_CREATE, LocalAction.SUPPLIER_UPDATE, LocalAction.RECEIVING_APPROVE, LocalAction.SALES_ROUTE_CONFIGURE,
    LocalAction.CATALOG_ACTIVATE, LocalAction.CATALOG_RELEASE_ACTIVATE, LocalAction.SERIAL_REGISTRATION_ACTIVATE, LocalAction.RETURN_RIGHTS_ACTIVATE)
internal val foodConfigurationActions: Set<LocalAction> = setOf(LocalAction.RECIPE_SET,
    LocalAction.FOOD_STATION_CONFIGURE, LocalAction.FOOD_ROUTE_CONFIGURE)
internal val purchasingActions: Set<LocalAction> = setOf(LocalAction.PURCHASE_APPROVE, LocalAction.SUPPLIER_INVOICE_CAPTURE,
    LocalAction.SUPPLIER_INVOICE_RECORD, LocalAction.SUPPLIER_INVOICE_MATCH, LocalAction.SUPPLIER_INVOICE_MATCH_RECOGNIZE,
    LocalAction.SUPPLIER_CREDIT_RECORD, LocalAction.SUPPLIER_ADVANCE_ALLOCATE, LocalAction.SUPPLIER_OPENING_RECORD)
internal val purchasingKeys: Set<CommandKey> = setOf(LocalPurchasingProfile.APPROVE_ORDER, LocalPurchasingProfile.CAPTURE_INVOICE,
    LocalPurchasingProfile.RECORD_INVOICE, LocalPurchasingProfile.MATCH_INVOICE, LocalPurchasingProfile.MATCH_UNCONTESTED_INVOICE,
    LocalPurchasingProfile.MATCH_CAPTURED_INVOICE,
    LocalPurchasingProfile.RECORD_CREDIT, LocalSupplierSettlementProfile.ALLOCATE_ADVANCE, LocalOpeningSupplierObligationProfile.COMMAND)
internal val returnCaseActions: Set<LocalAction> = setOf(LocalAction.RETURN_CASE_RECORD, LocalAction.RETURN_CASE_RECEIPT,
    LocalAction.RETURN_CASE_QUALIFY, LocalAction.RETURN_CASE_ACCEPT)

internal fun purchasingTarget(request: MutationRequest): DomainId = when (request.command) {
    LocalOpeningSupplierObligationProfile.COMMAND -> LocalOpeningSupplierObligationCodec.decodeRequest(request.payload).obligationId
    LocalPurchasingProfile.APPROVE_ORDER -> LocalPurchasingCodec.decodeApprove(request.payload).order.id
    LocalPurchasingProfile.RECORD_INVOICE -> LocalPurchasingCodec.decodeInvoice(request.payload).invoice.id
    LocalPurchasingProfile.CAPTURE_INVOICE -> LocalPurchasingCodec.decodeCaptureInvoice(request.payload).invoice.id
    LocalPurchasingProfile.MATCH_INVOICE -> LocalPurchasingCodec.decodeMatch(request.payload).invoiceId
    LocalPurchasingProfile.MATCH_UNCONTESTED_INVOICE -> LocalPurchasingCodec.decodeUncontestedMatch(request.payload).match.invoiceId
    LocalPurchasingProfile.MATCH_CAPTURED_INVOICE -> LocalPurchasingCodec.decodeCapturedMatch(request.payload).invoiceId
    LocalPurchasingProfile.RECORD_CREDIT -> LocalPurchasingCodec.decodeCredit(request.payload).invoiceId
    LocalSupplierSettlementProfile.ALLOCATE_ADVANCE -> LocalSupplierSettlementCodec.allocation(request.payload).invoice.invoiceId
    else -> unavailable()
}

internal val documentaryActions: Set<LocalAction> = setOf(LocalAction.PURCHASING_DOCUMENT_CAPTURE, LocalAction.PURCHASING_DOCUMENT_ATTACH)
internal val documentaryKeys: Set<CommandKey> = setOf(LocalPurchasingDocumentProfileV1.BEGIN, LocalPurchasingDocumentProfileV1.CHUNK, LocalPurchasingDocumentProfileV1.COMPLETE)

internal fun normalizeLocalRequest(key: CommandKey, body: StoredBytes): StoredBytes = when (key) {
    LocalReturnStockProfileV1.COMMAND -> LocalReturnStockCodecV1.normalize(body)
    LocalFoodTableAssignmentProfileV1.COMMAND -> LocalFoodTableAssignmentCodecV1.encode(LocalFoodTableAssignmentCodecV1.decode(body))
    LocalSerialRegistrationProfileV1.ACTIVATE -> LocalSerialRegistrationCodecV1.encode(LocalSerialRegistrationCodecV1.decode(body))
    in documentaryKeys -> LocalPurchasingDocumentCodecV1.normalize(key, body)
    LocalCatalogReleaseProfileV1.ACTIVATE -> LocalCatalogReleaseCodecV1.encode(LocalCatalogReleaseCodecV1.decode(body))
    LocalRetailCustomerReceiptCodec.COMMAND -> LocalRetailCustomerReceiptCodec.normalize(body)
    LocalReturnRightsActivationProfileV1.ACTIVATE -> LocalReturnRightsActivationCodecV1.normalizeActivate(body)
    LocalFoodFloorProfileV1.COMMAND -> LocalFoodFloorCodecV1.encode(LocalFoodFloorCodecV1.decode(body))
    LocalOpeningSupplierObligationProfile.COMMAND -> LocalOpeningSupplierObligationCodec.normalize(body)
    LocalSupplierCashProfile.COMMAND -> LocalSupplierCashCodec.normalize(body)
    LocalCashCloseNoteCodec.COMMAND -> LocalCashCloseNoteCodec.normalize(body)
    LocalPaymentCorrectionCodec.COMMAND -> LocalPaymentCorrectionCodec.normalize(body)
    LocalFoodPaymentProfile.COMMAND -> LocalFoodPaymentCodec.normalize(body)
    LocalFoodRefundProfile.COMMAND -> LocalFoodRefundCodec.normalize(body)
    LocalFoodPaymentSupervisorCodec.COMMAND -> LocalFoodPaymentSupervisorCodec.normalize(body)
    LocalFoodFinancialProfileV1.COMMAND -> LocalFoodFinancialCodecV1.normalize(body)
    LocalReturnCaseProfileV1.COMMAND -> LocalReturnCaseCommandCodecV1.normalize(body)
    in purchasingKeys -> LocalPurchasingCodec.normalize(key, body)
    LocalStockRelationalProfileV2.COMMAND -> LocalStockRelationalCommandsV2.encode(LocalStockRelationalCommandsV2.decode(body))
    in unpricedArrivalKeys -> LocalUnpricedArrivalCommandsV1.normalize(key, body)
    in unpricedArrivalKeysV2 -> LocalUnpricedArrivalCommandsV2.normalize(key, body)
    LocalSalesStoreProfile.CASH_COMMAND -> LocalCashCommandCodec.normalize(body)
    LocalSalesStoreProfile.ORDER_COMMAND -> LocalOrderCommandCodec.normalize(body)
    LocalSalesStoreProfile.COLLECTION_COMMAND -> LocalCollectionCommandCodec.normalize(body)
    LocalCollectionStockCodecV2.COMMAND -> LocalCollectionStockCodecV2.normalize(body)
    LocalSalesStoreProfile.RETURN_COMMAND -> LocalReturnCommandCodec.normalize(body)
    LocalFoodStockCodecV2.COMMAND -> LocalFoodStockCodecV2.normalize(body)
    LocalFinancialJournalProfileV3.COLLECTION_COMMAND -> LocalCollectionCommandCodecV3.normalize(body)
    LocalFinancialJournalProfileV3.RETURN_COMMAND -> LocalReturnCommandCodecV3.normalize(body)
    LocalRetailSupervisorCodecV1.COMMAND -> LocalRetailSupervisorCodecV1.normalize(body)
    LocalFoodProfileV1.COMMAND -> LocalFoodCodecV1.normalize(body)
    LocalFoodConfigurationProfileV1.COMMAND -> LocalFoodConfigurationCodecV1.encode(LocalFoodConfigurationCodecV1.decode(body))
    LocalConfigurationProfile.COMMAND -> LocalConfigurationCodec.encode(LocalConfigurationCodec.decode(body))
    LocalSupplierProfileV2.COMMAND -> LocalSupplierCodecV2.encode(LocalSupplierCodecV2.decode(body))
    LocalPricingProfile.COMMAND -> LocalPricingCodec.encode(LocalPricingCodec.decode(body))
    LocalCountSurplusApprovalProfile.COMMAND -> LocalCountSurplusApprovalCodec.encode(LocalCountSurplusApprovalCodec.decode(body))
    LocalStockStoreProfile.RECEIVE_PO, LocalStockStoreProfile.RECEIVE, LocalStockStoreProfile.OPENING, LocalStockStoreProfile.INSPECT,
    LocalStockStoreProfile.COUNT_CAPTURE, LocalStockStoreProfile.COUNT_POST, LocalStockStoreProfile.RECALL,
    LocalStockStoreProfile.TRANSFER, LocalStockStoreProfile.RECEIVE_SERIAL, LocalStockStoreProfile.OPENING_SERIAL,
    LocalStockStoreProfile.INSPECT_SERIAL, LocalStockStoreProfile.COUNT_CAPTURE_SERIAL, LocalStockStoreProfile.COUNT_POST_SERIAL,
    LocalStockStoreProfile.RECALL_SERIAL, LocalStockStoreProfile.TRANSFER_SERIAL, LocalStockStoreProfile.TRANSFER_OWNED -> LocalStockCommands.normalize(key, body)
    else -> throw IllegalArgumentException("client.command.unsupported")
}

internal fun actionMatches(action: LocalAction, request: MutationRequest): Boolean = when (request.command) {
    LocalReturnStockProfileV1.COMMAND -> request.actionApprovalId == null && action == LocalAction.RETURN_STOCK_POST &&
        LocalReturnStockCodecV1.normalize(request.payload) == request.payload
    LocalFoodTableAssignmentProfileV1.COMMAND -> request.actionApprovalId == null && action == LocalAction.FOOD_ASSIGN_TABLE &&
        LocalFoodTableAssignmentCodecV1.encode(LocalFoodTableAssignmentCodecV1.decode(request.payload)) == request.payload
    in unpricedArrivalKeysV2 -> action in unpricedArrivalActions &&
        unpricedArrivalCommandV2(action) == request.command &&
        LocalUnpricedArrivalCommandsV2.normalize(request.command, request.payload) == request.payload
    in documentaryKeys -> request.actionApprovalId == null && action in documentaryActions &&
        normalizeLocalRequest(request.command, request.payload) == request.payload &&
        (request.command != LocalPurchasingDocumentProfileV1.BEGIN || action ==
            if (LocalPurchasingDocumentCodecV1.decodeBegin(request.payload).parentOriginalId == null)
                LocalAction.PURCHASING_DOCUMENT_CAPTURE else LocalAction.PURCHASING_DOCUMENT_ATTACH)
    LocalSerialRegistrationProfileV1.ACTIVATE -> request.actionApprovalId == null && action == LocalAction.SERIAL_REGISTRATION_ACTIVATE &&
        LocalSerialRegistrationCodecV1.encode(LocalSerialRegistrationCodecV1.decode(request.payload)) == request.payload
    LocalCatalogReleaseProfileV1.ACTIVATE -> request.actionApprovalId == null && action == LocalAction.CATALOG_RELEASE_ACTIVATE &&
        LocalCatalogReleaseCodecV1.encode(LocalCatalogReleaseCodecV1.decode(request.payload)) == request.payload
    LocalRetailCustomerReceiptCodec.COMMAND -> request.actionApprovalId == null &&
        action == LocalAction.RETAIL_CUSTOMER_RECEIPT &&
        LocalRetailCustomerReceiptCodec.normalize(request.payload) == request.payload
    LocalReturnRightsActivationProfileV1.ACTIVATE -> request.actionApprovalId == null &&
        action == LocalAction.RETURN_RIGHTS_ACTIVATE &&
        LocalReturnRightsActivationCodecV1.normalizeActivate(request.payload) == request.payload
    LocalFoodFloorProfileV1.COMMAND -> request.actionApprovalId == null &&
        action == floorConfigurationAction(LocalFoodFloorCodecV1.decode(request.payload))
    LocalOpeningSupplierObligationProfile.COMMAND -> request.actionApprovalId == null &&
        action == LocalAction.SUPPLIER_OPENING_RECORD && LocalOpeningSupplierObligationCodec.normalize(request.payload) == request.payload
    LocalSupplierCashProfile.COMMAND -> action in supplierPhysicalActions &&
        normalizeLocalRequest(request.command, request.payload) == request.payload && supplierPhysicalRequestAction(request) == action
    LocalFoodRefundProfile.COMMAND -> action in foodRefundActions &&
        normalizeLocalRequest(request.command, request.payload) == request.payload && foodRefundRequestAction(request) == action
    LocalFoodPaymentProfile.COMMAND, LocalFoodPaymentSupervisorCodec.COMMAND -> action in foodPhysicalActions &&
        normalizeLocalRequest(request.command, request.payload) == request.payload && foodPhysicalRequestAction(request) == action
    LocalFoodConfigurationProfileV1.COMMAND -> request.actionApprovalId == null &&
        action == foodConfigurationAction(LocalFoodConfigurationCodecV1.decode(request.payload))
    LocalPaymentCorrectionCodec.COMMAND -> request.actionApprovalId == null &&
        action == LocalAction.PAYMENT_CLASSIFICATION_CORRECT && LocalPaymentCorrectionCodec.normalize(request.payload) == request.payload
    LocalCashCloseNoteCodec.COMMAND -> request.actionApprovalId == null && action == LocalAction.CASH_ADD_CLOSE_NOTE &&
        LocalCashCloseNoteCodec.normalize(request.payload) == request.payload
    LocalFoodFinancialProfileV1.COMMAND -> request.actionApprovalId == null &&
        action == foodFinancialAction(LocalFoodFinancialCodecV1.decode(request.payload))
    LocalReturnCaseProfileV1.COMMAND -> request.actionApprovalId == null &&
        LocalReturnCaseCommandCodecV1.normalize(request.payload) == request.payload &&
        returnCaseAction(LocalReturnCaseCommandCodecV1.decode(request.payload)) == action
    in purchasingKeys -> request.actionApprovalId == null &&
        LocalPurchasingCodec.normalize(request.command, request.payload) == request.payload && action == when (request.command) {
            LocalPurchasingProfile.APPROVE_ORDER -> LocalAction.PURCHASE_APPROVE
            LocalPurchasingProfile.RECORD_INVOICE -> LocalAction.SUPPLIER_INVOICE_RECORD
            LocalPurchasingProfile.CAPTURE_INVOICE -> LocalAction.SUPPLIER_INVOICE_CAPTURE
            LocalPurchasingProfile.MATCH_INVOICE, LocalPurchasingProfile.MATCH_UNCONTESTED_INVOICE -> LocalAction.SUPPLIER_INVOICE_MATCH
            LocalPurchasingProfile.MATCH_CAPTURED_INVOICE -> LocalAction.SUPPLIER_INVOICE_MATCH_RECOGNIZE
            LocalPurchasingProfile.RECORD_CREDIT -> LocalAction.SUPPLIER_CREDIT_RECORD
            LocalSupplierSettlementProfile.ALLOCATE_ADVANCE -> LocalAction.SUPPLIER_ADVANCE_ALLOCATE
            else -> unavailable()
        }
    LocalCollectionStockCodecV2.COMMAND -> request.actionApprovalId == null && action == when (LocalCollectionStockCodecV2.decode(request.payload)) {
        is LocalCollectionStockRequestV2.CreateOrder -> LocalAction.ORDER_CREATE
        is LocalCollectionStockRequestV2.ReplaceUnlockedOrder -> LocalAction.ORDER_REPLACE
        is LocalCollectionStockRequestV2.OfferHandoff -> LocalAction.ORDER_OFFER_HANDOFF
        is LocalCollectionStockRequestV2.CancelHandoff -> LocalAction.ORDER_CANCEL_HANDOFF
        is LocalCollectionStockRequestV2.AcceptHandoff -> LocalAction.ORDER_ACCEPT_HANDOFF
        is LocalCollectionStockRequestV2.ReassignOrder -> LocalAction.ORDER_REASSIGN
        is LocalCollectionStockRequestV2.Begin -> LocalAction.COLLECTION_PREPARE
        is LocalCollectionStockRequestV2.Finalize -> LocalAction.SALE_FINALIZE
    }
    LocalStockRelationalProfileV2.COMMAND -> request.actionApprovalId == null && when (val value = LocalStockRelationalCommandsV2.decode(request.payload)) {
        is LocalStockRelationalCommand.Receive -> value.operationId == request.operationId && action ==
            if (value.opening) LocalAction.STOCK_OPENING else LocalAction.RECEIPT_POST
        is LocalStockRelationalCommand.ReceivePo -> value.operationId == request.operationId && action == LocalAction.RECEIPT_POST
        is LocalStockRelationalCommand.Inspect -> value.operationId == request.operationId && action == LocalAction.INSPECTION_RELEASE
        is LocalStockRelationalCommand.CaptureCount -> value.operationId == request.operationId && action == LocalAction.COUNT_CAPTURE
        is LocalStockRelationalCommand.PostCount -> value.operationId == request.operationId && action == LocalAction.COUNT_POST
        is LocalStockRelationalCommand.Transfer -> value.operationId == request.operationId && action == LocalAction.WAREHOUSE_TRANSFER
        is LocalStockRelationalCommand.Recall -> action == LocalAction.STOCK_RECALL
        is LocalStockRelationalCommand.InitializeFresh -> value.operationId == request.operationId && action == LocalAction.STOCK_INITIALIZE
        is LocalStockRelationalCommand.Expire -> false
    }
    LocalFinancialJournalProfileV3.COLLECTION_COMMAND, LocalFinancialJournalProfileV3.RETURN_COMMAND,
    LocalRetailSupervisorCodecV1.COMMAND -> action in retailActions && retailRequestAction(request) == action
    in unpricedArrivalKeys -> request.actionApprovalId == null && action in unpricedArrivalActions && unpricedArrivalCommand(action) == request.command
    LocalFoodStockCodecV2.COMMAND -> request.actionApprovalId == null && when (val value = LocalFoodStockCodecV2.decode(request.payload)) {
        is LocalFoodStockRequestV2.ActivityProtected -> action == LocalAction.FOOD_CREATE_DRAFT
        is LocalFoodStockRequestV2.ActivityProtectedAdd -> action == LocalAction.FOOD_ADD
        is LocalFoodStockRequestV2.ActivityProtectedQuotedAdd -> action == LocalAction.FOOD_ADD
        is LocalFoodStockRequestV2.CreateDraft -> action == LocalAction.FOOD_CREATE_DRAFT
        is LocalFoodStockRequestV2.Preparation -> action == foodRequestAction(value.request)
        is LocalFoodStockRequestV2.Financial -> action == foodFinancialAction(value.request)
    }
    LocalFoodProfileV1.COMMAND -> request.actionApprovalId == null && action == foodRequestAction(LocalFoodCodecV1.decode(request.payload))
    LocalSalesStoreProfile.CASH_COMMAND -> action == when (LocalCashCommandCodec.decode(request.payload)) {
        is LocalCashRequestV1.ConfigureDrawer -> LocalAction.DRAWER_CONFIGURE
        is LocalCashRequestV1.ConfigureCustodian -> LocalAction.CUSTODIAN_CONFIGURE
        is LocalCashRequestV1.ConfigureSafe -> LocalAction.SAFE_CONFIGURE
        is LocalCashRequestV1.Open -> LocalAction.CASH_OPEN
        is LocalCashRequestV1.NonSale -> LocalAction.CASH_NON_SALE
        is LocalCashRequestV1.BeginClosing -> LocalAction.CASH_BEGIN_CLOSE
        is LocalCashRequestV1.CancelClosing -> LocalAction.CASH_CANCEL_CLOSE
        is LocalCashRequestV1.RecordClosingCount -> LocalAction.CASH_RECORD_COUNT
        is LocalCashRequestV1.DeliverCountedClose -> LocalAction.CASH_DELIVER_COUNTED
        is LocalCashRequestV1.SubmitCountedClose -> LocalAction.CASH_SUBMIT_COUNTED
        is LocalCashRequestV1.SubmitClose -> LocalAction.CASH_SUBMIT_CLOSE
        is LocalCashRequestV1.Review -> LocalAction.CASH_REVIEW_CLOSE
        is LocalCashRequestV1.DropToSafe -> LocalAction.CASH_DROP_TO_SAFE
        is LocalCashRequestV1.ReceiveSafe -> LocalAction.CASH_RECEIVE_SAFE
    }
    LocalSalesStoreProfile.ORDER_COMMAND -> action == when (LocalOrderCommandCodec.decode(request.payload)) {
        is LocalOrderRequestV1.Create -> LocalAction.ORDER_CREATE
        is LocalOrderRequestV1.ReplaceUnlocked -> LocalAction.ORDER_REPLACE
        is LocalOrderRequestV1.OfferHandoff -> LocalAction.ORDER_OFFER_HANDOFF
        is LocalOrderRequestV1.CancelHandoff -> LocalAction.ORDER_CANCEL_HANDOFF
        is LocalOrderRequestV1.AcceptHandoff -> LocalAction.ORDER_ACCEPT_HANDOFF
    }
    LocalSalesStoreProfile.COLLECTION_COMMAND -> when (LocalCollectionCommandCodec.decode(request.payload)) {
        is LocalCollectionRequestV1.Begin -> action == LocalAction.COLLECTION_PREPARE ||
            action == LocalAction.RETAIL_COLLECTION_BEGIN && request.actionApprovalId == null
        is LocalCollectionRequestV1.Finalize -> action == LocalAction.SALE_FINALIZE
        else -> false // No physical-money initiation without qualified client outcome capacity.
    }
    LocalSupplierProfileV2.COMMAND -> {
        val saved = LocalSupplierCodecV2.decode(request.payload)
        request.actionApprovalId == null && LocalSupplierCodecV2.encode(saved) == request.payload &&
            action == (if (saved.expectedStorageRevision == null) LocalAction.SUPPLIER_CREATE else LocalAction.SUPPLIER_UPDATE) &&
            saved.expectedStorageRevision?.value != 0L
    }
    LocalConfigurationProfile.COMMAND -> when (val saved = LocalConfigurationCodec.decode(request.payload)) {
        is LocalConfigurationRequestV1.InstallCompanySku -> action == LocalAction.CATALOG_ACTIVATE && request.actionApprovalId == null
        is LocalConfigurationRequestV1.ConfigureWarehouse -> action == LocalAction.WAREHOUSE_CONFIGURE
        is LocalConfigurationRequestV1.ConfigureTender -> action == LocalAction.TENDER_CONFIGURE
        is LocalConfigurationRequestV1.ConfigureSupplier -> request.actionApprovalId == null &&
            action == (if (saved.expectedStorageRevision == null) LocalAction.SUPPLIER_CREATE else LocalAction.SUPPLIER_UPDATE) &&
            saved.expectedStorageRevision?.value != 0L
        is LocalConfigurationRequestV1.ApproveReceiving -> action == LocalAction.RECEIVING_APPROVE
        is LocalConfigurationRequestV1.ConfigureSalesRoute -> action == LocalAction.SALES_ROUTE_CONFIGURE
    }
    LocalPricingProfile.COMMAND -> {
        val price = LocalPricingCodec.decode(request.payload)
        request.actionApprovalId == null && LocalPricingCodec.encode(price) == request.payload &&
            action == pricingRequestAction(price)
    }
    LocalCountSurplusApprovalProfile.COMMAND -> request.actionApprovalId == null && action == LocalAction.COUNT_APPROVE_SURPLUS
    LocalStockStoreProfile.RECEIVE_PO, LocalStockStoreProfile.RECEIVE, LocalStockStoreProfile.RECEIVE_SERIAL -> action == LocalAction.RECEIPT_POST
    LocalStockStoreProfile.OPENING, LocalStockStoreProfile.OPENING_SERIAL -> action == LocalAction.STOCK_OPENING
    LocalStockStoreProfile.INSPECT, LocalStockStoreProfile.INSPECT_SERIAL -> action == LocalAction.INSPECTION_RELEASE
    LocalStockStoreProfile.COUNT_CAPTURE, LocalStockStoreProfile.COUNT_CAPTURE_SERIAL -> action == LocalAction.COUNT_CAPTURE
    LocalStockStoreProfile.COUNT_POST, LocalStockStoreProfile.COUNT_POST_SERIAL -> action == LocalAction.COUNT_POST
    LocalStockStoreProfile.RECALL, LocalStockStoreProfile.RECALL_SERIAL -> action == LocalAction.STOCK_RECALL
    LocalStockStoreProfile.TRANSFER, LocalStockStoreProfile.TRANSFER_SERIAL, LocalStockStoreProfile.TRANSFER_OWNED -> action == LocalAction.WAREHOUSE_TRANSFER
    else -> false
}

internal fun actionPermission(action: LocalAction): Permission? {
    val security = when (action) {
        LocalAction.FOOD_ASSIGN_TABLE -> SecurityAction.ORDER_EDIT
        LocalAction.PURCHASING_DOCUMENT_CAPTURE -> SecurityAction.PURCHASING_DOCUMENT_CAPTURE
        LocalAction.PURCHASING_DOCUMENT_ATTACH -> SecurityAction.PURCHASING_DOCUMENT_ATTACH
        LocalAction.RETAIL_CUSTOMER_RECEIPT -> SecurityAction.CUSTOMER_RECEIPT_RECORD
        LocalAction.PAYMENT_CLASSIFICATION_CORRECT -> SecurityAction.PAYMENT_CLASSIFICATION_CORRECT
        in floorConfigurationActions -> SecurityAction.FLOOR_CONFIGURE
        LocalAction.SUPPLIER_CASH_START, LocalAction.SUPPLIER_CASH_APPLY -> SecurityAction.SUPPLIER_CASH_SETTLE
        LocalAction.SUPPLIER_CASH_RESOLUTION, LocalAction.SUPPLIER_CASH_RESOLUTION_APPLY -> SecurityAction.SUPPLIER_CASH_RESOLVE
        LocalAction.FOOD_REFUND_START, LocalAction.FOOD_REFUND_APPLY -> SecurityAction.REFUND_RECORD
        LocalAction.FOOD_REFUND_RESOLUTION, LocalAction.FOOD_REFUND_RESOLUTION_APPLY -> SecurityAction.REFUND_RESOLVE
        in foodResolutionActions -> SecurityAction.COLLECTION_RESOLVE
        in foodPhysicalActions -> SecurityAction.COLLECTION_ATTEST
        in foodFinancialActions -> foodFinancialSecurity(action)
        in foodActions -> foodSecurityAction(action)
        LocalAction.DRAWER_CONFIGURE, LocalAction.CUSTODIAN_CONFIGURE, LocalAction.SAFE_CONFIGURE -> SecurityAction.CASH_CONFIGURE
        LocalAction.CASH_OPEN -> SecurityAction.CASH_OPEN
        LocalAction.CASH_BEGIN_CLOSE, LocalAction.CASH_CANCEL_CLOSE, LocalAction.CASH_SUBMIT_CLOSE,
        LocalAction.CASH_RECORD_COUNT, LocalAction.CASH_SUBMIT_COUNTED -> SecurityAction.CASH_CLOSE
        LocalAction.CASH_REVIEW_CLOSE, LocalAction.CASH_ADD_CLOSE_NOTE -> SecurityAction.CASH_REVIEW
        LocalAction.CASH_DROP_TO_SAFE, LocalAction.CASH_DELIVER_COUNTED -> SecurityAction.CASH_TRANSFER
        LocalAction.CASH_RECEIVE_SAFE -> SecurityAction.CASH_RECEIVE
        LocalAction.CASH_NON_SALE -> SecurityAction.CASH_MOVE
        LocalAction.ORDER_CREATE -> SecurityAction.ORDER_CREATE
        LocalAction.ORDER_REPLACE -> SecurityAction.ORDER_EDIT
        LocalAction.ORDER_OFFER_HANDOFF, LocalAction.ORDER_CANCEL_HANDOFF, LocalAction.ORDER_ACCEPT_HANDOFF -> SecurityAction.ORDER_HANDOFF
        LocalAction.ORDER_REASSIGN -> SecurityAction.ORDER_REASSIGN
        LocalAction.COLLECTION_PREPARE -> SecurityAction.COLLECTION_BEGIN
        LocalAction.SALE_FINALIZE -> SecurityAction.COLLECTION_FINALIZE
        in stockActions, in unpricedArrivalActions, LocalAction.COUNT_APPROVE_SURPLUS,
        LocalAction.RETURN_STOCK_POST -> SecurityAction.INVENTORY_ADJUST
        in branchPriceActions -> SecurityAction.BRANCH_PRICE_EDIT
        in pricingAdministrationActions -> SecurityAction.CATALOG_ACTIVATE
        LocalAction.CATALOG_ACTIVATE, LocalAction.CATALOG_RELEASE_ACTIVATE, LocalAction.SERIAL_REGISTRATION_ACTIVATE, LocalAction.RETURN_RIGHTS_ACTIVATE,
        LocalAction.RECIPE_SET -> SecurityAction.CATALOG_ACTIVATE
        LocalAction.FOOD_STATION_CONFIGURE -> SecurityAction.KITCHEN_TASK
        LocalAction.FOOD_ROUTE_CONFIGURE -> SecurityAction.WAREHOUSE_CONFIGURE
        LocalAction.WAREHOUSE_CONFIGURE, LocalAction.SALES_ROUTE_CONFIGURE -> SecurityAction.WAREHOUSE_CONFIGURE
        LocalAction.TENDER_CONFIGURE -> SecurityAction.TENDER_CONFIGURE
        LocalAction.SUPPLIER_CREATE, LocalAction.SUPPLIER_UPDATE -> SecurityAction.SUPPLIER_ADMINISTRATION
        LocalAction.RECEIVING_APPROVE -> SecurityAction.PURCHASING_APPROVE
        LocalAction.SUPPLIER_INVOICE_CAPTURE -> SecurityAction.PURCHASING_INVOICE_CAPTURE
        LocalAction.SUPPLIER_INVOICE_MATCH_RECOGNIZE -> SecurityAction.PURCHASING_INVOICE_MATCH_RECOGNIZE
        in purchasingActions -> SecurityAction.PURCHASING_APPROVE
        LocalAction.RETURN_CASE_RECORD, LocalAction.RETURN_CASE_RECEIPT, LocalAction.RETURN_CASE_QUALIFY -> SecurityAction.RETURN_CLAIM
        LocalAction.RETURN_CASE_ACCEPT -> SecurityAction.RETURN_ACCEPT
        else -> return null
    }
    return Permission(security, if (action in configurationActions || action in foodConfigurationActions || action in floorConfigurationActions ||
        action in pricingAdministrationActions)
        PermissionVerb.ADMINISTER else PermissionVerb.PERFORM)
}

internal suspend fun continuationHint(access: GatewayAccess, entry: StoredClientIntentV1): Boolean {
    if (access.installed.isRetailOrdersOnly) {
        val saved = savedRetailOrderRequest(entry)
        // Both original formats retain their exact order target; no fresh profile or quote read.
        return access.allow(entry.localAction, saved.orderId)
    }
    if (access.installed.isStockTransfersOnly) {
        val saved = savedStockTransferRequest(entry)
        // Current adjustment is bound to the original transfer document, never a replacement
        // position/revision or a fresh normalized-profile read. Master authorization remains final.
        return access.allow(entry.localAction, saved.request.transferId)
    }
    directoryConfigurationProfile(entry.localAction)?.let { profile ->
        val target = if (entry.isSupplierV2()) savedSupplierConfigurationRequestV2(entry).supplier.id
        else when (val saved = savedDirectoryConfigurationRequest(profile, entry)) {
                is LocalConfigurationRequestV1.ConfigureSupplier -> saved.supplier.id
                is LocalConfigurationRequestV1.ConfigureWarehouse -> saved.warehouse.id
                else -> unavailable()
            }
        // Only the original directory target/CAS is eligible; a current detail read cannot
        // rebuild the request or supply a newer expected storage revision on continuation.
        return access.allow(entry.localAction, target)
    }
    if (entry.localAction == LocalAction.RETURN_STOCK_POST ||
        CommandKey(entry.commandName, entry.commandVersion) == LocalReturnStockProfileV1.COMMAND) {
        val saved = entry.request()
        if (saved.command != LocalReturnStockProfileV1.COMMAND || entry.formVersion != 1 || entry.actionApproval != null ||
            !actionMatches(entry.localAction, saved)) return false
        // Continue only the saved original acceptance, allocations and guards. No fresh page
        // or current disposition/issue revision can rebuild an unresolved stock request.
        return access.allow(entry.localAction, LocalReturnStockCodecV1.decode(saved.payload).returnId)
    }
    if (entry.localAction in documentaryActions) {
        val saved = entry.request()
        if (!actionMatches(entry.localAction, saved)) return false
        val id = when (saved.command) {
            LocalPurchasingDocumentProfileV1.BEGIN -> LocalPurchasingDocumentCodecV1.decodeBegin(saved.payload).documentId
            LocalPurchasingDocumentProfileV1.CHUNK -> LocalPurchasingDocumentCodecV1.decodeChunk(saved.payload).documentId
            LocalPurchasingDocumentProfileV1.COMPLETE -> LocalPurchasingDocumentCodecV1.decodeComplete(saved.payload).documentId
            else -> return false
        }
        return access.allow(entry.localAction, id)
    }
    if (entry.localAction == LocalAction.SERIAL_REGISTRATION_ACTIVATE) {
        val saved = entry.request()
        if (!actionMatches(entry.localAction, saved)) return false
        return access.allow(entry.localAction, LocalSerialRegistrationCodecV1.decode(saved.payload).publication.definition.registrationId)
    }
    if (entry.localAction == LocalAction.CATALOG_RELEASE_ACTIVATE) {
        val saved = entry.request()
        if (!actionMatches(entry.localAction, saved)) return false
        // Continue the exact original release/CAS; newer current sources cannot rebind it.
        return access.allow(entry.localAction, LocalCatalogReleaseCodecV1.decode(saved.payload).publication.definition.catalogId)
    }
    if (entry.localAction == LocalAction.RETURN_RIGHTS_ACTIVATE) return returnRightsContinuationHint(access, entry)
    if (entry.localAction in physicalActions) return false
    if (entry.localAction in foodConfigurationActions) {
        val saved = entry.request()
        if (saved.command != LocalFoodConfigurationProfileV1.COMMAND || !actionMatches(entry.localAction, saved)) return false
        // Exact SAVED recipe/station/route target. Current rows cannot rewrite retained publication bytes.
        return access.allow(entry.localAction, foodConfigurationTargetId(LocalFoodConfigurationCodecV1.decode(saved.payload)))
    }
    if (entry.localAction in floorConfigurationActions) {
        val saved = entry.request()
        if (saved.command != LocalFoodFloorProfileV1.COMMAND || !actionMatches(entry.localAction, saved)) return false
        return access.allow(entry.localAction, floorConfigurationTarget(LocalFoodFloorCodecV1.decode(saved.payload)))
    }
    if (entry.localAction == LocalAction.CATALOG_ACTIVATE) {
        val saved = entry.request()
        if (!actionMatches(entry.localAction, saved)) return false
        val publication = (LocalConfigurationCodec.decode(saved.payload) as LocalConfigurationRequestV1.InstallCompanySku).publication
        // Current branch ADMINISTER for this exact retained SKU; never fresh signing/repricing.
        return access.allow(LocalAction.CATALOG_ACTIVATE, publication.definition.skuId)
    }
    if (entry.localAction == LocalAction.STOCK_OPENING) {
        val saved = entry.request()
        if (!actionMatches(entry.localAction, saved)) return false
        // The real stock writers bind adjustment to this original receipt, not its approval
        // basis. Decode retained bytes only; never freshly route or rewrite a pending intent.
        val receiptId = when (saved.command) {
            LocalStockRelationalProfileV2.COMMAND -> {
                val opening = LocalStockRelationalCommandsV2.decode(saved.payload) as? LocalStockRelationalCommand.Receive
                    ?: return false
                if (!opening.opening || opening.operationId != saved.operationId) return false
                opening.request.receiptId
            }
            LocalStockStoreProfile.OPENING, LocalStockStoreProfile.OPENING_SERIAL ->
                LocalStockCommands.retainedOpeningReceiptId(saved.command, saved.payload)
            else -> return false
        }
        return access.allow(entry.localAction, receiptId)
    }
    if (entry.localAction == LocalAction.STOCK_INITIALIZE) {
        val saved = entry.request()
        return actionMatches(entry.localAction, saved) && access.allow(entry.localAction,
            (LocalStockRelationalCommandsV2.decode(saved.payload) as LocalStockRelationalCommand.InitializeFresh).initializationId)
    }
    if (entry.localAction == LocalAction.PAYMENT_CLASSIFICATION_CORRECT) {
        val saved = entry.request()
        // Decode only the retained request. Recovery never reparses the editor, rereads
        // current tender policy or creates a new classification operation.
        return actionMatches(entry.localAction, saved) && access.allow(entry.localAction,
            LocalPaymentCorrectionCodec.decode(saved.payload).saleId)
    }
    if (entry.localAction == LocalAction.CASH_ADD_CLOSE_NOTE) {
        val saved = entry.request()
        return actionMatches(entry.localAction, saved) &&
            access.allow(entry.localAction, LocalCashCloseNoteCodec.decode(saved.payload).sessionId)
    }
    if (entry.localAction in cashActions) {
        val saved = entry.request()
        return saved.command == LocalSalesStoreProfile.CASH_COMMAND && actionMatches(entry.localAction, saved) &&
            access.allow(entry.localAction, cashRequestTarget(LocalCashCommandCodec.decode(saved.payload)))
    }
    if (entry.localAction in returnCaseActions) return returnCaseContinuation(access, entry)
    if (entry.localAction == LocalAction.RETAIL_CUSTOMER_RECEIPT) {
        val saved = entry.request()
        return actionMatches(entry.localAction, saved) && access.allow(entry.localAction,
            LocalRetailCustomerReceiptCodec.decode(saved.payload).saleId)
    }
    if (entry.localAction in foodFinancialActions) {
        val saved = entry.request()
        if (!actionMatches(entry.localAction, saved)) return false
        val target = foodFinancialTarget(savedFoodFinancial(saved))
        // No fresh stock-profile, price or target-revision lookup can rewrite a retained intent.
        return if (entry.localAction == LocalAction.FOOD_FINALIZE_PAID)
            access.hasHint(Permission(SecurityAction.COLLECTION_FINALIZE, PermissionVerb.PERFORM), target) ||
                access.hasHint(Permission(SecurityAction.COLLECTION_FINALIZE_ZERO, PermissionVerb.PERFORM), target)
        else access.allow(entry.localAction, target)
    }
    if (entry.localAction in purchasingActions) {
        val saved = entry.request()
        return saved.command in purchasingKeys && actionMatches(entry.localAction, saved) &&
            access.allow(entry.localAction, purchasingTarget(saved))
    }
    if (entry.request().command == LocalCollectionStockCodecV2.COMMAND) {
        val request = entry.request()
        if (!actionMatches(entry.localAction, request)) return false
        val target = when (val saved = LocalCollectionStockCodecV2.decode(request.payload)) {
            is LocalCollectionStockRequestV2.CreateOrder -> saved.orderId
            is LocalCollectionStockRequestV2.ReplaceUnlockedOrder -> saved.orderId
            is LocalCollectionStockRequestV2.OfferHandoff -> saved.orderId
            is LocalCollectionStockRequestV2.CancelHandoff -> saved.orderId
            is LocalCollectionStockRequestV2.AcceptHandoff -> saved.orderId
            is LocalCollectionStockRequestV2.ReassignOrder -> saved.orderId
            is LocalCollectionStockRequestV2.Begin -> saved.orderId
            is LocalCollectionStockRequestV2.Finalize -> saved.orderId
        }
        return if (entry.localAction == LocalAction.SALE_FINALIZE)
            access.hasHint(Permission(SecurityAction.COLLECTION_FINALIZE, PermissionVerb.PERFORM), target) ||
                access.hasHint(Permission(SecurityAction.COLLECTION_FINALIZE_ZERO, PermissionVerb.PERFORM), target)
        else access.allow(entry.localAction, target)
    }
    if (entry.localAction in unpricedArrivalActions) {
        val saved = entry.request()
        if (!actionMatches(entry.localAction, saved)) return false
        return when (saved.command) {
            in unpricedArrivalKeys -> access.allow(entry.localAction, unpricedArrivalTarget(saved))
            in unpricedArrivalKeysV2 -> arrivalContinuationHintV2(access, entry)
            else -> false
        }
    }
    if (entry.localAction == LocalAction.FOOD_ASSIGN_TABLE) {
        val saved = entry.request()
        return entry.formVersion == 1 && actionMatches(entry.localAction, saved) && access.installed.has(LocalFoodTableAssignmentProfileV1.COMMAND) &&
            access.allow(entry.localAction, LocalFoodTableAssignmentCodecV1.decode(saved.payload).orderId)
    }
    if (entry.localAction in foodActions) {
        val saved = entry.request()
        if (saved.command !in setOf(LocalFoodProfileV1.COMMAND, LocalFoodStockCodecV2.COMMAND) || saved.actionApprovalId != null) return false
        if (entry.localAction == LocalAction.FOOD_CREATE_DRAFT) {
            val draft = savedFoodDraft(saved) ?: return false
            return access.installed.has(LocalFoodStockCodecV2.COMMAND) && access.allow(entry.localAction, draft.orderId)
        }
        val request = savedFoodPreparation(saved)
        if (foodRequestAction(request) != entry.localAction || entry.localAction == LocalAction.FOOD_ACCEPT_HANDOFF &&
            !access.installed.has(LocalFoodHandoffResponsibilityCodecV1.QUERY)) return false
        // Exact SAVED order/station target, never a branch-wide hint or a rebuilt menu request.
        return access.allow(entry.localAction, foodRequestTarget(request))
    }
    if (entry.localAction == LocalAction.SALE_FINALIZE) return access.hasHint(Permission(SecurityAction.COLLECTION_FINALIZE, PermissionVerb.PERFORM)) ||
        access.hasHint(Permission(SecurityAction.COLLECTION_FINALIZE_ZERO, PermissionVerb.PERFORM))
    if (entry.localAction == LocalAction.RECEIVING_APPROVE) {
        val request = LocalConfigurationCodec.decode(entry.request().payload) as? LocalConfigurationRequestV1.ApproveReceiving ?: return false
        val permission = if (request.approval.kind == com.ukkera.pos.catalog.store.LocalBasisKindV1.OPENING)
            SecurityAction.OPENING_INVENTORY_APPROVE else SecurityAction.PURCHASING_APPROVE
        return access.hasHint(Permission(permission, PermissionVerb.ADMINISTER), request.approval.id)
    }
    if (entry.localAction == LocalAction.COUNT_APPROVE_SURPLUS) {
        val request = LocalCountSurplusApprovalCodec.decode(entry.request().payload)
        return access.allow(entry.localAction, request.countId)
    }
    if (entry.localAction in pricingActions) {
        return pricingContinuationHint(access, entry)
    }
    return actionPermission(entry.localAction)?.let { access.hasHint(it) } == true
}

internal fun GatewayAccess.allow(action: LocalAction, target: DomainId? = null): Boolean =
    installed.allows(action) && actionPermission(action)?.let { hasHint(it, target) } == true

internal fun GatewayAccess.requireAction(action: LocalAction, target: DomainId? = null) {
    if (!allow(action, target)) throw GatewayFailure(LocalServiceProblem.DENIED)
}

internal fun renderRecorded(entry: StoredClientIntentV1, reply: MutationReply.Recorded): LocalOperationResult.Recorded {
    if (entry.localAction == LocalAction.RETURN_STOCK_POST ||
        CommandKey(entry.commandName, entry.commandVersion) == LocalReturnStockProfileV1.COMMAND)
        return returnStockRecorded(entry, reply)
    if (entry.localAction in documentaryActions) {
        val body = validateDocumentaryStepResult(entry.request(), entry.owner.actorId, entry.localAction, reply)
        return LocalOperationResult.Recorded(entry.operationId,
            if (reply.committed) LocalRecordedDecision.RECORDED_ONLY else LocalRecordedDecision.REJECTED,
            text("Documentary step: ${body.code}. No goods receipt, invoice or payment is created.",
                "خطوة مستندية: ${body.code}. لا ينشأ استلام بضاعة أو فاتورة أو دفع."), replayed = reply.replayed)
    }
    if (entry.localAction == LocalAction.RETAIL_CUSTOMER_RECEIPT ||
        CommandKey(entry.commandName, entry.commandVersion) == LocalRetailCustomerReceiptCodec.COMMAND)
        return customerReceiptRecorded(entry, reply)
    if (entry.localAction == LocalAction.RETURN_RIGHTS_ACTIVATE ||
        entry.commandName == LocalReturnRightsActivationProfileV1.ACTIVATE.name &&
        entry.commandVersion == LocalReturnRightsActivationProfileV1.ACTIVATE.version) return returnRightsRecorded(entry, reply)
    if (entry.localAction in floorConfigurationActions) return floorConfigurationRecorded(entry, reply)
    if (entry.localAction == LocalAction.FOOD_ASSIGN_TABLE ||
        CommandKey(entry.commandName, entry.commandVersion) == LocalFoodTableAssignmentProfileV1.COMMAND)
        return tableAssignmentRecorded(entry, reply)
    if (entry.localAction == LocalAction.STOCK_INITIALIZE) return stockInitializationRecorded(entry, reply)
    if (entry.localAction == LocalAction.CASH_ADD_CLOSE_NOTE) return cashCloseNoteRecorded(entry, reply)
    if (entry.localAction == LocalAction.PAYMENT_CLASSIFICATION_CORRECT) return paymentCorrectionRecorded(entry, reply)
    if (entry.localAction in cashActions) return cashRecorded(entry, reply)
    if (entry.localAction in physicalActions) return physicalRecordedUi(entry, reply)
    if (entry.localAction in foodFinancialActions) return foodFinancialRecorded(entry, reply)
    val decision = if (reply.committed) LocalRecordedDecision.APPLIED else LocalRecordedDecision.REJECTED
    var message = if (reply.committed) text("The local master recorded this operation. Review its result before continuing.",
        "سجل الجهاز الرئيسي المحلي هذه العملية. راجع نتيجتها قبل المتابعة.")
    else text("The local master durably rejected this operation. It was not applied.", "رفض الجهاز الرئيسي المحلي هذه العملية وسجل الرفض بشكل دائم. لم تُنفذ.")
    var result: LocalRecord? = null
    var food: LocalFoodOperationDetails? = null
    try {
        when (CommandKey(entry.commandName, entry.commandVersion)) {
            LocalSerialRegistrationProfileV1.ACTIVATE -> {
                val value = LocalSerialRegistrationCodecV1.decodeResult(reply.result)
                val saved = LocalSerialRegistrationCodecV1.decode(entry.request().payload).publication.definition
                if (value.registrationId != saved.registrationId || value.publicationId != saved.publicationId) unavailable()
                message = text("Serial-registration result recorded: ${value.code}. Activation is not stock receipt or current eligibility.",
                    "سُجلت نتيجة تسجيل المسلسل: ${value.code}. التفعيل ليس استلام مخزون أو أهلية حالية.")
                result = LocalRecord(LocalRecordReference(LocalRecordKind.SERIAL_REGISTRATION_PUBLICATION, saved.publicationId),
                    entry.label.ui(), literal(value.code), entry.summary.map { it.ui() }, selectable = false)
            }
            LocalCatalogReleaseProfileV1.ACTIVATE -> {
                val value = LocalCatalogReleaseCodecV1.decodeResult(reply.result)
                val saved = LocalCatalogReleaseCodecV1.decode(entry.request().payload).publication.definition
                if (value.catalogId != saved.catalogId || value.publicationId != saved.publicationId) unavailable()
                message = text("Catalog-release result recorded: ${value.code}. Signing, release activation and SKU/stock/purchasing changes remain separate.",
                    "سُجلت نتيجة إصدار الكتالوج: ${value.code}. يبقى التوقيع وتفعيل الإصدار وتغيير الأصناف أو المخزون أو الشراء منفصلة.")
                result = LocalRecord(LocalRecordReference(LocalRecordKind.CATALOG_RELEASE_PUBLICATION, saved.publicationId),
                    entry.label.ui(), literal(value.code), listOfNotNull(
                        recordValue(text("Stable catalog / independent release revision", "الكتالوج الثابت / مراجعة الإصدار المستقلة"),
                            literal("${saved.catalogId.value} / ${saved.revision.value}")),
                        recordValue(text("Exact release canonical-content SHA-256", "بصمة محتوى الإصدار الدقيق SHA-256"),
                            literal(localCatalogReleaseReference(saved).contentDigest.value)),
                        value.storageRevision?.let { recordValue(text("Recorded active storage revision", "مراجعة تخزين الإصدار النشط المسجلة"),
                            literal(it.value.toString())) }), selectable = false)
            }
            LocalReturnCaseProfileV1.COMMAND -> {
                val returned = returnCaseRecorded(entry, reply)
                message = returned.first
                result = returned.second
            }
            LocalOpeningSupplierObligationProfile.COMMAND -> {
                val opening = openingSupplierObligationRecorded(entry, reply)
                message = opening.first
                result = opening.second
            }
            in purchasingKeys -> {
                val purchasing = purchasingRecorded(entry, reply)
                message = purchasing.first
                result = purchasing.second
            }
            in unpricedArrivalKeys -> {
                val arrival = renderUnpricedArrivalRecorded(entry, reply)
                message = arrival.first
                result = arrival.second
            }
            in unpricedArrivalKeysV2 -> {
                val arrival = renderUnpricedArrivalRecordedV2(entry, reply)
                message = arrival.first
                result = arrival.second
            }
            LocalFoodProfileV1.COMMAND, LocalFoodStockCodecV2.COMMAND -> {
                val detail = foodRecorded(entry, reply)
                food = detail
                message = text("Food operation result recorded: ${detail.code}. Preparation is not payment or physical print acknowledgement.",
                    "سُجلت نتيجة عملية الطعام: ${detail.code}. التحضير ليس دفعًا أو تأكيدًا فعليًا للطباعة.")
            }
            LocalSalesStoreProfile.ORDER_COMMAND,
            LocalSalesStoreProfile.COLLECTION_COMMAND, LocalSalesStoreProfile.RETURN_COMMAND,
            LocalCollectionStockCodecV2.COMMAND -> when (val value = LocalSalesResultCodec.decode(reply.result)) {
                is LocalSalesResultV1.Applied -> {
                    val kind = when (entry.localAction) {
                        in orderActions -> LocalRecordKind.ORDER
                        LocalAction.ORDER_REASSIGN -> LocalRecordKind.ORDER
                        LocalAction.SALE_FINALIZE -> LocalRecordKind.SALE
                        else -> LocalRecordKind.COLLECTION
                    }
                    val revision = value.revisions.firstOrNull { it.id == value.documentId }?.revision
                    result = LocalRecord(LocalRecordReference(kind, value.documentId, revision), entry.label.ui(),
                        literal(value.action.name), buildList {
                            value.amount?.let { add(recordValue(text("Amount", "المبلغ"), moneyText(it))) }
                            value.revisions.take(24).forEach { add(recordValue(literal(it.kind), literal("${it.id.value} · ${it.revision.value}"))) }
                        }, selectable = entry.localAction != LocalAction.ORDER_REASSIGN)
                }
                is LocalSalesResultV1.Rejected -> message = text("Recorded rejection: ${value.rule.name}", "رفض مسجل: ${value.rule.name}")
                is LocalSalesResultV1.Unavailable -> message = text("Recorded refusal; required capability is unavailable: ${value.reason.name}",
                    "رفض مسجل؛ القدرة المطلوبة غير متاحة: ${value.reason.name}")
                is LocalSalesResultV1.StockResolutionRequired -> {
                    message = text("Stock-safety changes were recorded. The sale is NOT finalized; reconcile the covered collection before any further collection.",
                        "سُجلت تغييرات أمان المخزون. لم تُستكمل عملية البيع؛ عالج التحصيل المغطى قبل أي تحصيل إضافي.")
                    result = LocalRecord(LocalRecordReference(LocalRecordKind.COLLECTION, value.collectionId),
                        text("Collection requires stock resolution", "التحصيل يحتاج إلى معالجة المخزون"),
                        values = listOf(recordValue(fieldLabel(LocalField.RESERVATION_ID), literal(value.reservationId.value))))
                }
            }
            LocalFoodConfigurationProfileV1.COMMAND -> {
                val value = LocalConfigurationCodec.decodeResult(reply.result)
                val saved = LocalFoodConfigurationCodecV1.decode(entry.request().payload)
                message = text("Food configuration result recorded: ${value.code}. Signing, activation and kitchen output remain separate.",
                    "سُجلت نتيجة إعداد الطعام: ${value.code}. يظل التوقيع والتفعيل وإخراج المطبخ خطوات منفصلة.")
                value.id?.let { id ->
                    if (id != foodConfigurationTargetId(saved)) unavailable()
                    val reference = when (saved) {
                        is LocalFoodConfigurationRequestV1.InstallRecipe -> LocalRecordReference(LocalRecordKind.FOOD_RECIPE, id, saved.recipe.definition.revision)
                        is LocalFoodConfigurationRequestV1.Station -> LocalRecordReference(LocalRecordKind.FOOD_STATION, id)
                        is LocalFoodConfigurationRequestV1.Route -> LocalRecordReference(LocalRecordKind.FOOD_ROUTE, id)
                    }
                    result = LocalRecord(reference, entry.label.ui(), literal(value.code),
                        listOfNotNull(value.revision?.let { recordValue(text("Recorded configuration storage revision", "مراجعة تخزين الإعداد المسجلة"),
                            literal(it.value.toString())) }), selectable = false)
                }
            }
            LocalSupplierProfileV2.COMMAND -> {
                val value = supplierConfigurationRecordedV2(entry, reply)
                message = text("Supplier configuration result recorded: ${value.code}", "سُجلت نتيجة إعداد المورد: ${value.code}")
                value.id?.let { id ->
                    result = LocalRecord(LocalRecordReference(LocalRecordKind.SUPPLIER, id, value.revision), entry.label.ui(), literal(value.code),
                        listOf(recordValue(text("Original supplier directory reference", "مرجع المورد الأصلي في الدليل"),
                            supplierReferenceValue(value.reference))), selectable = false)
                }
            }
            LocalConfigurationProfile.COMMAND -> {
                val value = directoryConfigurationProfile(entry.localAction)?.let { directoryConfigurationRecorded(it, entry, reply) }
                    ?: LocalConfigurationCodec.decodeResult(reply.result)
                message = if (entry.localAction == LocalAction.WAREHOUSE_CONFIGURE && value.code == "warehouse.controlled_retirement_required") text(
                    "The Master recorded warehouse.controlled_retirement_required. The warehouse was not disabled; controlled retirement requires a separate stock and obligation review.",
                    "سجل الجهاز الرئيسي warehouse.controlled_retirement_required. لم يُعطل المخزن؛ ويتطلب إيقافه المضبوط مراجعة مستقلة للمخزون والالتزامات.")
                else text("Configuration result recorded: ${value.code}", "سُجلت نتيجة الإعداد: ${value.code}")
                value.id?.let { id -> result = LocalRecord(LocalRecordReference(configurationRecordKind(entry.localAction), id, value.revision),
                    entry.label.ui(), literal(value.code)) }
            }
            LocalPricingProfile.COMMAND -> {
                val pricing = pricingRecorded(entry, reply)
                message = pricing.first
                result = pricing.second
            }
            LocalCountSurplusApprovalProfile.COMMAND -> {
                val value = LocalCountSurplusApprovalCodec.decodeResult(reply.result)
                val request = LocalCountSurplusApprovalCodec.decode(entry.request().payload)
                message = text("Count-value result recorded: ${value.code}", "سُجلت نتيجة قيمة الجرد: ${value.code}")
                val authorityRevision = value.authorityRevision
                if (reply.committed && value.approvalId == request.approvalId && authorityRevision != null) result = LocalRecord(
                    LocalRecordReference(LocalRecordKind.COUNT_SURPLUS_BASIS, request.approvalId, authorityRevision, request.countId),
                    entry.label.ui(), literal(value.code), listOf(
                        recordValue(fieldLabel(LocalField.COUNT_SURPLUS_BASIS_ID), literal(request.approvalId.value)),
                        recordValue(fieldLabel(LocalField.COUNT_ID), literal(request.countId.value)),
                        recordValue(fieldLabel(LocalField.EXPECTED_SURPLUS_AUTHORITY_REVISION), literal(authorityRevision.value.toString()))))
            }
            else -> {
                val value = if (CommandKey(entry.commandName, entry.commandVersion) == LocalStockRelationalProfileV2.COMMAND)
                    LocalStockRelationalCommandsV2.decodeResult(entry.request().payload, reply.result)
                    else LocalStockCommands.decodeResult(reply.result)
                val failure = value.failure
                message = if (failure != null) text("Recorded stock rejection: ${failure.name}", "رفض مخزون مسجل: ${failure.name}")
                    else text("Stock result recorded: ${value.kind.name}", "سُجلت نتيجة المخزون: ${value.kind.name}")
                val documentField = when (entry.localAction) {
                    LocalAction.RECEIPT_POST, LocalAction.STOCK_OPENING -> LocalField.RECEIPT_ID
                    LocalAction.INSPECTION_RELEASE -> LocalField.INSPECTION_ID
                    LocalAction.COUNT_CAPTURE, LocalAction.COUNT_POST -> LocalField.COUNT_ID
                    LocalAction.WAREHOUSE_TRANSFER -> LocalField.TRANSFER_ID
                    LocalAction.STOCK_RECALL -> LocalField.LOT_ID
                    else -> LocalField.DOCUMENT_ID
                }
                result = LocalRecord(LocalRecordReference(when (entry.localAction) {
                    LocalAction.RECEIPT_POST, LocalAction.STOCK_OPENING -> LocalRecordKind.RECEIPT
                    LocalAction.INSPECTION_RELEASE -> LocalRecordKind.INSPECTION
                    LocalAction.COUNT_CAPTURE, LocalAction.COUNT_POST -> LocalRecordKind.COUNT
                    LocalAction.WAREHOUSE_TRANSFER -> LocalRecordKind.TRANSFER
                    LocalAction.STOCK_RECALL -> LocalRecordKind.LOT
                    else -> LocalRecordKind.SKU
                }, value.documentId), entry.label.ui(), literal(value.kind.name),
                    listOf(recordValue(fieldLabel(documentField), literal(value.documentId.value))) + value.items.take(16).map {
                    recordValue(literal(it.skuId.value), text("Item storage revision: ${it.storageRevision?.value ?: "NEW"}",
                        "مراجعة تخزين الصنف: ${it.storageRevision?.value ?: "جديد"}"))
                }, selectable = entry.localAction !in setOf(LocalAction.STOCK_RECALL, LocalAction.INSPECTION_RELEASE))
            }
        }
    } catch (_: Exception) {
        // Parsing failure cannot relabel a definite committed/rejected kernel result as an
        // unknown payment. Details remain unavailable; no guessed amount or balance is shown.
        message = text("The master recorded a ${if (reply.committed) "committed" else "rejected"} result, but this client cannot display its details. Do not repeat collection.",
            "سجل الجهاز الرئيسي نتيجة ${if (reply.committed) "مثبتة" else "مرفوضة"}، لكن هذا العميل لا يستطيع عرض تفاصيلها. لا تكرر التحصيل.")
        result = null
        food = null
    }
    return LocalOperationResult.Recorded(reply.operationId, decision, message, result, reply.replayed, food = food)
}

internal fun configurationRecordKind(action: LocalAction): LocalRecordKind = when (action) {
    LocalAction.CATALOG_ACTIVATE -> LocalRecordKind.SKU
    LocalAction.RETURN_RIGHTS_ACTIVATE -> LocalRecordKind.RETURN_RIGHTS_PUBLICATION
    LocalAction.WAREHOUSE_CONFIGURE -> LocalRecordKind.WAREHOUSE
    LocalAction.SUPPLIER_CREATE, LocalAction.SUPPLIER_UPDATE -> LocalRecordKind.SUPPLIER
    LocalAction.RECEIVING_APPROVE -> LocalRecordKind.RECEIVING_BASIS
    LocalAction.TENDER_CONFIGURE -> LocalRecordKind.TENDER
    LocalAction.SALES_ROUTE_CONFIGURE -> LocalRecordKind.SALES_ROUTE
    else -> LocalRecordKind.POLICY
}

internal fun text(english: String, arabic: String): LocalText = LocalText(english, arabic)
internal fun literal(value: String): LocalText = LocalText(value, value)
internal fun recordValue(label: LocalText, value: LocalText): LocalRecordValue = LocalRecordValue(label, value)
internal fun fixedDecimal(scaled: Long, scale: Int): String {
    val value = scaled.toString()
    val negative = value.startsWith('-')
    val digits = value.removePrefix("-").padStart(scale + 1, '0')
    return (if (negative) "-" else "") + if (scale == 0) digits else digits.dropLast(scale) + "." + digits.takeLast(scale)
}
internal fun moneyText(money: Money): LocalText = literal("${fixedDecimal(money.minor, money.currency.fractionDigits)} ${money.currency.name}")
internal fun quantityText(quantity: Quantity): LocalText = literal("${fixedDecimal(quantity.scaled, quantity.scale)} · ${quantity.unitId.value}")
internal fun readCut(reply: ReadReply.Data): LocalText = text(
    "Current read cut ${reply.branchRevision.value} · ${reply.observedAt.canonicalString()}. Other pages may observe a different cut.",
    "مراجعة القراءة الحالية ${reply.branchRevision.value} · ${reply.observedAt.canonicalString()}. قد تعرض الصفحات الأخرى مراجعة مختلفة.")

/** Every supplied field must be consumed by this action's closed typed mapping. */
internal class InputValues(values: List<LocalScalarInput>) {
    private val entries = values.associate { it.field to it.value }
    private val consumed = mutableSetOf<LocalField>()
    init { if (entries.size != values.size) invalid() }
    private fun take(field: LocalField, optional: Boolean = false): LocalScalarValue? {
        consumed += field
        return entries[field] ?: if (optional) null else invalid(field)
    }
    fun id(field: LocalField): DomainId = optionalId(field) ?: invalid(field)
    fun optionalId(field: LocalField): DomainId? = when (val value = take(field, true)) {
        null -> null
        is LocalScalarValue.Identifier -> value.value
        else -> invalid(field)
    }
    fun reference(field: LocalField, kind: LocalRecordKind): LocalRecordReference {
        val value = (take(field) as? LocalScalarValue.Reference)?.value ?: invalid(field)
        if (value.kind != kind) invalid(field)
        return value
    }
    fun references(field: LocalField, kind: LocalRecordKind): List<LocalRecordReference> {
        val values = (take(field) as? LocalScalarValue.References)?.values ?: invalid(field)
        if (values.any { it.kind != kind }) invalid(field)
        return values
    }
    fun optionalReferences(field: LocalField, kind: LocalRecordKind): List<LocalRecordReference> {
        val supplied = take(field, true) ?: return emptyList()
        val values = (supplied as? LocalScalarValue.References)?.values ?: invalid(field)
        if (values.any { it.kind != kind }) invalid(field)
        return values
    }
    fun optionalReference(field: LocalField, kind: LocalRecordKind): LocalRecordReference? {
        val value = take(field, true) ?: return null
        if (value !is LocalScalarValue.Reference || value.value.kind != kind) invalid(field)
        return value.value
    }
    fun integer(field: LocalField): Long = (take(field) as? LocalScalarValue.Integer)?.value ?: invalid(field)
    fun optionalInteger(field: LocalField): Long? = when (val value = take(field, true)) {
        null -> null
        is LocalScalarValue.Integer -> value.value
        else -> invalid(field)
    }
    fun revision(field: LocalField): Revision = Revision(integer(field))
    fun optionalRevision(field: LocalField): Revision? = when (val value = take(field, true)) {
        null -> null
        is LocalScalarValue.Integer -> Revision(value.value)
        else -> invalid(field)
    }
    fun text(field: LocalField): String = (take(field) as? LocalScalarValue.Text)?.value ?: invalid(field)
    fun optionalText(field: LocalField): String? = when (val value = take(field, true)) {
        null -> null
        is LocalScalarValue.Text -> value.value
        else -> invalid(field)
    }
    fun decimal(field: LocalField): String = (take(field) as? LocalScalarValue.Decimal)?.canonical ?: invalid(field)
    fun optionalDecimal(field: LocalField): String? = when (val value = take(field, true)) {
        null -> null
        is LocalScalarValue.Decimal -> value.canonical
        else -> invalid(field)
    }
    fun money(field: LocalField): Money = moneyValue(field, decimal(field))
    fun optionalMoney(field: LocalField): Money? = when (val value = take(field, true)) {
        null -> null
        is LocalScalarValue.Decimal -> moneyValue(field, value.canonical)
        else -> invalid(field)
    }
    private fun moneyValue(field: LocalField, decimal: String): Money = try {
        Money(Currency.EGP, ExactDecimal.parse(decimal, 2).toScaledLongExact(2))
    } catch (_: ArithmeticException) {
        // Typed input conversion only; the stage boundary still decides whether a write may exist.
        invalid(field)
    }
    fun flag(field: LocalField): Boolean = (take(field) as? LocalScalarValue.Flag)?.value ?: invalid(field)
    fun optionalFlag(field: LocalField): Boolean? = when (val value = take(field, true)) {
        null -> null
        is LocalScalarValue.Flag -> value.value
        else -> invalid(field)
    }
    fun choice(field: LocalField): String = (take(field) as? LocalScalarValue.Choice)?.code ?: invalid(field)
    fun optionalChoice(field: LocalField): String? = when (val value = take(field, true)) {
        null -> null
        is LocalScalarValue.Choice -> value.code
        else -> invalid(field)
    }
    fun date(field: LocalField): BusinessDate = BusinessDate.parseCanonical((take(field) as? LocalScalarValue.Date)?.iso ?: invalid(field))
    fun optionalDate(field: LocalField): BusinessDate? = when (val value = take(field, true)) {
        null -> null
        is LocalScalarValue.Date -> BusinessDate.parseCanonical(value.iso)
        else -> invalid(field)
    }
    fun instant(field: LocalField): UtcInstant = UtcInstant.parseCanonical(text(field))
    fun optionalInstant(field: LocalField): UtcInstant? = optionalText(field)?.let(UtcInstant::parseCanonical)
    fun finish() { if (entries.keys.any { it !in consumed }) invalid(entries.keys.first { it !in consumed }) }
}

internal fun scalar(field: LocalField, kind: LocalInputKind, value: LocalScalarValue? = null,
                    required: Boolean = true, readOnly: Boolean = false, maximum: Int = 256, help: LocalText? = null): LocalFormField.Scalar =
    LocalFormField.Scalar(field, fieldLabel(field), kind, required, help, maximumCharacters = maximum, initialValue = value, readOnly = readOnly)
internal fun idField(field: LocalField, id: DomainId? = null, required: Boolean = true, readOnly: Boolean = id != null): LocalFormField.Scalar =
    scalar(field, LocalInputKind.IDENTIFIER, id?.let(LocalScalarValue::Identifier), required, readOnly, maximum = 36)
internal fun newIdField(field: LocalField): LocalFormField.Scalar = idField(field, DomainId.random())
internal fun revisionField(field: LocalField, revision: Revision, readOnly: Boolean = true): LocalFormField.Scalar =
    scalar(field, LocalInputKind.INTEGER, LocalScalarValue.Integer(revision.value), readOnly = readOnly, maximum = 19)
internal fun moneyField(field: LocalField, value: Money? = null, required: Boolean = true): LocalFormField.Scalar =
    scalar(field, LocalInputKind.DECIMAL, value?.let { LocalScalarValue.Decimal(fixedDecimal(it.minor, 2)) }, required,
        maximum = 24, help = text("Exact EGP amount; no rounding or group separators.", "مبلغ دقيق بالجنيه المصري؛ دون تقريب أو فواصل تجميع."))
internal fun instantField(field: LocalField, value: UtcInstant? = null, required: Boolean = true): LocalFormField.Scalar =
    scalar(field, LocalInputKind.TEXT, value?.let { LocalScalarValue.Text(it.canonicalString()) }, required, maximum = 64,
        help = text("Canonical UTC timestamp ending in Z; an attestation, not master time.", "وقت UTC بصيغة معيارية ينتهي بـ Z؛ إفادة وليست توقيت الجهاز الرئيسي."))
internal fun choiceField(field: LocalField, choices: List<LocalChoice>, initial: String? = null): LocalFormField.Scalar =
    scalar(field, LocalInputKind.CHOICE, initial?.let(LocalScalarValue::Choice)).copy(choices = choices)
internal fun form(action: LocalAction, fields: List<LocalFormField>, explanation: LocalText? = null, version: Int = 1): LocalActionForm =
    LocalActionForm(action, version, actionLabel(action), fields, explanation,
        confirmation = text("I reviewed the exact identifiers, amounts and evidence. Save this immutable intent for separate submission.",
            "راجعت المعرفات والمبالغ والأدلة الدقيقة. احفظ هذا الطلب الثابت لإرساله في خطوة منفصلة."))

private fun intentSummary(intent: LocalCommandIntent): List<LocalRecordValue> = buildList {
    fun display(value: LocalScalarValue): String = when (value) {
        is LocalScalarValue.Text -> value.value
        is LocalScalarValue.Identifier -> value.value.value
        is LocalScalarValue.Integer -> value.value.toString()
        is LocalScalarValue.Decimal -> value.canonical
        is LocalScalarValue.Date -> value.iso
        is LocalScalarValue.Choice -> value.code
        is LocalScalarValue.Reference -> "${value.value.kind.name} · ${value.value.id.value} · ${value.value.revision?.value ?: "—"}" +
            (value.value.parentId?.let { " · parent ${it.value}" } ?: "")
        is LocalScalarValue.Choices -> value.codes.joinToString(", ")
        is LocalScalarValue.References -> value.values.joinToString("; ") { "${it.kind.name} · ${it.id.value} · ${it.revision?.value ?: "—"}" +
            (it.parentId?.let { parent -> " · parent ${parent.value}" } ?: "") }
        is LocalScalarValue.Flag -> value.value.toString()
    }
    fun displayInput(input: LocalScalarInput): String = display(input.value).let {
        if (input.field in setOf(LocalField.SERIAL_ID, LocalField.SERIAL_CODE)) "\u2066$it\u2069" else it
    }
    intent.values.forEach { add(recordValue(fieldLabel(it.field), literal(displayInput(it)))) }
    intent.lines.forEachIndexed { index, line ->
        val english = (listOf("Line ID: ${line.lineId.value}") + line.values.map { "${fieldLabel(it.field).english}: ${displayInput(it)}" }).joinToString("\n")
        val arabic = (listOf("معرف السطر: ${line.lineId.value}") + line.values.map { "${fieldLabel(it.field).arabic}: ${displayInput(it)}" }).joinToString("\n")
        add(recordValue(text("Line ${index + 1}", "السطر ${index + 1}"), text(english, arabic)))
    }
    require(size in 1..32) // Never silently truncate an immutable financial request review.
}
