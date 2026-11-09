package com.ukkera.pos.app

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision

/** Bounded display text. Neither translation is a permission, trusted outcome, or secret. */
data class LocalText(val english: String, val arabic: String) {
    init { require(english.length <= 2048 && arabic.length <= 2048) }
}

/** Local obligations remain visible even when their actual service has not been installed. */
enum class LocalWorkflow {
    CHECKOUT, ORDERS, CASH, CATALOG, RECEIVING, INVENTORY, PURCHASING,
    KITCHEN, FULFILLMENT, EXCEPTIONS, REPORTS, PEOPLE, DEVICES, SETUP, FOOD, RETURNS;

    /** Shared read presentation only; each actual service still authorizes its own target. */
    val usesFoodQueries: Boolean get() = this == FOOD || this == FULFILLMENT || this == KITCHEN

    /** Read-only navigation mode only; the actual Master independently authorizes its target. */
    fun isManagerOrderReview(search: String): Boolean = this == ORDERS && search.startsWith("manager:")

    /** Explicit manager work never activates an owner's physical payment controls. */
    fun isManagerOrderContext(search: String): Boolean = this == ORDERS &&
        (search.startsWith("manager:") || search.startsWith("reassign:"))
}

/** A closed adapter dispatch table, not an arbitrary command name or server-selected script. */
enum class LocalAction(val workflow: LocalWorkflow) {
    ORDER_CREATE(LocalWorkflow.CHECKOUT), ORDER_ADD_LINE(LocalWorkflow.CHECKOUT),
    ORDER_CHANGE_LINE(LocalWorkflow.CHECKOUT), ORDER_REMOVE_LINE(LocalWorkflow.CHECKOUT), ORDER_REPLACE(LocalWorkflow.CHECKOUT),
    COLLECTION_PREPARE(LocalWorkflow.CHECKOUT), PAYMENT_ATTEST(LocalWorkflow.CHECKOUT),
    SALE_FINALIZE(LocalWorkflow.CHECKOUT),
    RETAIL_COLLECTION_BEGIN(LocalWorkflow.CHECKOUT), RETAIL_PAYMENT_START(LocalWorkflow.CHECKOUT),
    RETAIL_PAYMENT_APPLY(LocalWorkflow.CHECKOUT), RETAIL_PAYMENT_RESOLUTION(LocalWorkflow.CHECKOUT),
    RETAIL_REFUND_START(LocalWorkflow.ORDERS), RETAIL_REFUND_APPLY(LocalWorkflow.ORDERS),
    RETAIL_REFUND_RESOLUTION(LocalWorkflow.ORDERS), RETAIL_SUPERVISOR_REVIEW(LocalWorkflow.EXCEPTIONS),
    RETAIL_SUPERVISOR_APPLY(LocalWorkflow.EXCEPTIONS),
    ORDER_SUBMIT(LocalWorkflow.ORDERS), ORDER_CANCEL(LocalWorkflow.ORDERS),
    ORDER_OFFER_HANDOFF(LocalWorkflow.ORDERS), ORDER_CANCEL_HANDOFF(LocalWorkflow.ORDERS), ORDER_ACCEPT_HANDOFF(LocalWorkflow.ORDERS),
    ORDER_REASSIGN(LocalWorkflow.ORDERS),
    RETURN_CREATE(LocalWorkflow.ORDERS), RETURN_REFUND(LocalWorkflow.ORDERS), RETURN_DISPOSITION(LocalWorkflow.ORDERS),
    DRAWER_CONFIGURE(LocalWorkflow.CASH), CUSTODIAN_CONFIGURE(LocalWorkflow.CASH), SAFE_CONFIGURE(LocalWorkflow.CASH),
    CASH_OPEN(LocalWorkflow.CASH), CASH_BEGIN_CLOSE(LocalWorkflow.CASH), CASH_CANCEL_CLOSE(LocalWorkflow.CASH),
    CASH_SUBMIT_CLOSE(LocalWorkflow.CASH), CASH_REVIEW_CLOSE(LocalWorkflow.CASH),
    CASH_DROP_TO_SAFE(LocalWorkflow.CASH), CASH_RECEIVE_SAFE(LocalWorkflow.CASH), CASH_NON_SALE(LocalWorkflow.CASH),
    PRODUCT_CREATE(LocalWorkflow.CATALOG), PRODUCT_UPDATE(LocalWorkflow.CATALOG), VARIANT_SET(LocalWorkflow.CATALOG),
    RECIPE_SET(LocalWorkflow.CATALOG), PRICE_SET(LocalWorkflow.CATALOG), PRICE_RESET(LocalWorkflow.CATALOG), CATALOG_ACTIVATE(LocalWorkflow.CATALOG),
    RECEIPT_POST(LocalWorkflow.RECEIVING), INSPECTION_RELEASE(LocalWorkflow.RECEIVING),
    ARRIVAL_RECORD(LocalWorkflow.RECEIVING), ARRIVAL_QUALIFY(LocalWorkflow.RECEIVING),
    ARRIVAL_REJECT(LocalWorkflow.RECEIVING), ARRIVAL_DISPATCH(LocalWorkflow.RECEIVING),
    COUNT_CAPTURE(LocalWorkflow.INVENTORY), COUNT_POST(LocalWorkflow.INVENTORY), COUNT_APPROVE_SURPLUS(LocalWorkflow.INVENTORY), STOCK_ADJUST(LocalWorkflow.INVENTORY),
    STOCK_RECALL(LocalWorkflow.INVENTORY), STOCK_DISPOSITION(LocalWorkflow.INVENTORY), STOCK_OPENING(LocalWorkflow.INVENTORY),
    TRANSFER_SEND(LocalWorkflow.INVENTORY), TRANSFER_RECEIVE(LocalWorkflow.INVENTORY), WAREHOUSE_TRANSFER(LocalWorkflow.INVENTORY),
    SUPPLIER_CREATE(LocalWorkflow.PURCHASING), PURCHASE_CREATE(LocalWorkflow.PURCHASING),
    SUPPLIER_OPENING_RECORD(LocalWorkflow.PURCHASING),
    SUPPLIER_UPDATE(LocalWorkflow.PURCHASING), RECEIVING_APPROVE(LocalWorkflow.PURCHASING),
    PURCHASE_APPROVE(LocalWorkflow.PURCHASING), PURCHASE_CANCEL(LocalWorkflow.PURCHASING),
    KITCHEN_SEND(LocalWorkflow.KITCHEN), KITCHEN_START(LocalWorkflow.KITCHEN),
    KITCHEN_COMPLETE(LocalWorkflow.KITCHEN), KITCHEN_CANCEL(LocalWorkflow.KITCHEN), KITCHEN_WASTE(LocalWorkflow.KITCHEN),
    FULFILLMENT_ASSIGN(LocalWorkflow.FULFILLMENT), FULFILLMENT_DISPATCH(LocalWorkflow.FULFILLMENT),
    FULFILLMENT_DELIVER(LocalWorkflow.FULFILLMENT), FULFILLMENT_CANCEL(LocalWorkflow.FULFILLMENT),
    EXCEPTION_RECONCILE(LocalWorkflow.EXCEPTIONS), REPORT_CREATE(LocalWorkflow.REPORTS), REPORT_EXPORT(LocalWorkflow.REPORTS),
    EMPLOYEE_CREATE(LocalWorkflow.PEOPLE), EMPLOYEE_CHANGE_GRANTS(LocalWorkflow.PEOPLE), EMPLOYEE_DISABLE(LocalWorkflow.PEOPLE),
    DEVICE_PAIR(LocalWorkflow.DEVICES), DEVICE_REVOKE(LocalWorkflow.DEVICES),
    SETUP_UPDATE(LocalWorkflow.SETUP), ACTIVITY_CONFIGURE(LocalWorkflow.SETUP),
    WAREHOUSE_CONFIGURE(LocalWorkflow.SETUP), TENDER_CONFIGURE(LocalWorkflow.SETUP), SALES_ROUTE_CONFIGURE(LocalWorkflow.SETUP),
    BACKUP_CREATE(LocalWorkflow.SETUP), BACKUP_RESTORE(LocalWorkflow.SETUP),
    FOOD_CREATE(LocalWorkflow.FOOD), FOOD_ADD(LocalWorkflow.FOOD), FOOD_SEND(LocalWorkflow.FOOD),
    FOOD_STATION_START(LocalWorkflow.FOOD), FOOD_STATION_READY(LocalWorkflow.FOOD), FOOD_CONFIRM_UNSTARTED(LocalWorkflow.FOOD),
    FOOD_SERVE(LocalWorkflow.FOOD), FOOD_CANCEL_PORTION(LocalWorkflow.FOOD), FOOD_DISCARD_STARTED(LocalWorkflow.FOOD),
    FOOD_OFFER_HANDOFF(LocalWorkflow.FOOD), FOOD_ACCEPT_HANDOFF(LocalWorkflow.FOOD), FOOD_CANCEL_HANDOFF(LocalWorkflow.FOOD),
    SUPPLIER_INVOICE_RECORD(LocalWorkflow.PURCHASING), SUPPLIER_INVOICE_MATCH(LocalWorkflow.PURCHASING),
    RETURN_CASE_RECORD(LocalWorkflow.RETURNS), RETURN_CASE_RECEIPT(LocalWorkflow.RETURNS),
    RETURN_CASE_QUALIFY(LocalWorkflow.RETURNS), RETURN_CASE_ACCEPT(LocalWorkflow.RETURNS),
    FOOD_BEGIN_COLLECTION(LocalWorkflow.FOOD), FOOD_FINALIZE_PAID(LocalWorkflow.FOOD),
    FOOD_SEND_RESERVED(LocalWorkflow.FOOD), FOOD_PAID_PICKUP(LocalWorkflow.FOOD),
    FOOD_CANCEL_WHOLE_UNSENT(LocalWorkflow.FOOD), FOOD_CLOSE_RESOLVED(LocalWorkflow.FOOD),
    FOOD_RECORD_QUALITY_CLAIM(LocalWorkflow.FOOD),
    CASH_RECORD_COUNT(LocalWorkflow.CASH), CASH_DELIVER_COUNTED(LocalWorkflow.CASH), CASH_SUBMIT_COUNTED(LocalWorkflow.CASH),
    CASH_ADD_CLOSE_NOTE(LocalWorkflow.CASH), STOCK_INITIALIZE(LocalWorkflow.INVENTORY),
    FOOD_STATION_CONFIGURE(LocalWorkflow.CATALOG), FOOD_ROUTE_CONFIGURE(LocalWorkflow.CATALOG),
    FOOD_PAYMENT_START(LocalWorkflow.FOOD), FOOD_PAYMENT_APPLY(LocalWorkflow.FOOD),
    FOOD_PAYMENT_RESOLUTION(LocalWorkflow.FOOD), FOOD_PAYMENT_RESOLUTION_APPLY(LocalWorkflow.FOOD),
    FOOD_SUPERVISOR_REVIEW(LocalWorkflow.FOOD), FOOD_SUPERVISOR_APPLY(LocalWorkflow.FOOD),
    FOOD_REFUND_START(LocalWorkflow.FOOD), FOOD_REFUND_APPLY(LocalWorkflow.FOOD),
    FOOD_REFUND_RESOLUTION(LocalWorkflow.FOOD), FOOD_REFUND_RESOLUTION_APPLY(LocalWorkflow.FOOD),
    FOOD_EDIT_SERVICE_DETAILS(LocalWorkflow.FOOD),
    SUPPLIER_CREDIT_RECORD(LocalWorkflow.PURCHASING),
    SUPPLIER_CASH_START(LocalWorkflow.PURCHASING), SUPPLIER_CASH_APPLY(LocalWorkflow.PURCHASING),
    SUPPLIER_CASH_RESOLUTION(LocalWorkflow.PURCHASING), SUPPLIER_CASH_RESOLUTION_APPLY(LocalWorkflow.PURCHASING),
    SUPPLIER_ADVANCE_ALLOCATE(LocalWorkflow.PURCHASING),
    FOOD_CREATE_DRAFT(LocalWorkflow.FOOD),
    FLOOR_CONFIGURE(LocalWorkflow.FOOD), TABLE_CONFIGURE(LocalWorkflow.FOOD),
    RETURN_RIGHTS_ACTIVATE(LocalWorkflow.CATALOG),
    RETAIL_CUSTOMER_RECEIPT(LocalWorkflow.ORDERS),
    PRICE_POLICY_RECEIVE(LocalWorkflow.CATALOG), PRICE_POLICY_ACTIVATE(LocalWorkflow.CATALOG),
    PRICE_HISTORY_INDEX(LocalWorkflow.CATALOG),
    CATALOG_RELEASE_ACTIVATE(LocalWorkflow.CATALOG),
    SERIAL_REGISTRATION_ACTIVATE(LocalWorkflow.CATALOG),
    PURCHASING_DOCUMENT_CAPTURE(LocalWorkflow.PURCHASING), PURCHASING_DOCUMENT_ATTACH(LocalWorkflow.PURCHASING),
    FOOD_ASSIGN_TABLE(LocalWorkflow.FOOD),
    RETURN_STOCK_POST(LocalWorkflow.RETURNS),
    /** Historical tender explanation only: never a fresh collection or cash movement. */
    PAYMENT_CLASSIFICATION_CORRECT(LocalWorkflow.CHECKOUT),
    /** Capture-only claim entry; the original invoice-record action keeps its old authority. */
    SUPPLIER_INVOICE_CAPTURE(LocalWorkflow.PURCHASING),
    /** Explicit exact matching and payable recognition, separate from original claim capture. */
    SUPPLIER_INVOICE_MATCH_RECOGNIZE(LocalWorkflow.PURCHASING),
    /** Installed setup closure grammar. These never install, sign in, start RUN or grant authority. */
    SETUP_RESULT_ACKNOWLEDGE(LocalWorkflow.SETUP),
    SETUP_BUSINESS_GO_LIVE(LocalWorkflow.SETUP),
    SETUP_CONFIGURATION_RECORD(LocalWorkflow.SETUP),
    SETUP_CATALOG_IMPORT_ROW(LocalWorkflow.SETUP),
    SETUP_CATALOG_IMPORT_COMPLETE(LocalWorkflow.SETUP),
    SETUP_OPENING_STOCK_ROW(LocalWorkflow.SETUP),
    SETUP_OPENING_FLOAT_ROW(LocalWorkflow.SETUP),
    SETUP_OPENING_COMPLETE(LocalWorkflow.SETUP);
    /** Closed shared presentation locations, never an authorization grant. */
    fun canAppearIn(target: LocalWorkflow): Boolean = if (target == LocalWorkflow.KITCHEN)
        this == FOOD_STATION_START || this == FOOD_STATION_READY
    else target == workflow || when (this) {
        ORDER_CREATE, ORDER_REPLACE, COLLECTION_PREPARE, SALE_FINALIZE, RETAIL_COLLECTION_BEGIN,
        RETAIL_PAYMENT_START, RETAIL_PAYMENT_APPLY, RETAIL_PAYMENT_RESOLUTION,
        PAYMENT_CLASSIFICATION_CORRECT -> target == LocalWorkflow.ORDERS
        FOOD_SERVE, FOOD_PAID_PICKUP -> target == LocalWorkflow.FULFILLMENT
        RETAIL_CUSTOMER_RECEIPT -> target == LocalWorkflow.CHECKOUT || target == LocalWorkflow.RETURNS
        STOCK_OPENING -> target == LocalWorkflow.RECEIVING
        RECEIVING_APPROVE -> target == LocalWorkflow.INVENTORY
        else -> false
    }
}

/** Stable, bounded form fields. An adapter must reject fields outside its action's versioned spec. */
enum class LocalField {
    DOCUMENT_ID, ORDER_ID, LINE_ID, PORTION_ID, SALE_ID, RETURN_ID, COLLECTION_ID, PAYMENT_ID,
    SESSION_ID, DRAWER_ID, CUSTODIAN_ID, CUSTODY_ID, RECEIVING_CUSTODY_ID, TRANSFER_ID,
    PRODUCT_ID, VARIANT_ID, MODIFIER_IDS, SKU_ID, WAREHOUSE_ID, DESTINATION_ID, LOT_ID, SERIAL_ID, SERIAL_CODE, TRACKING, RECEIPT_ID,
    SUPPLIER_ID, PURCHASE_ID, ALLOCATION_ID, COUNT_ID, SOURCE_ID, EVIDENCE_ID, RESERVATION_ID,
    ARRIVAL_ID, ARRIVAL_EVENT_ID, CUSTODY_UNIT_ID, SUPPLIER_DELIVERY_REFERENCE, LOCAL_INTAKE_TAG, DOCUMENTARY_PO_ID,
    DISCREPANCY_REFERENCE, OWNERSHIP, OWNERSHIP_REFERENCE, OBSERVATION_REFERENCE, ATTACHMENT_ID,
    SPLIT_SOURCE_ID, SPLIT_CUSTODY_UNIT_ID, RECIPIENT_SUPPLIER_ID, INSPECTION_PASSED, REQUIRES_EXPIRY,
    BASIS_ID, COUNT_SURPLUS_BASIS_ID, PRICE_RULE_ID, INSPECTION_ID, SOURCE_EVENT_ID, DESTINATION_EVENT_ID, TENDER_ID,
    EMPLOYEE_ID, DEVICE_ID, POLICY_ID, TABLE_ID, FULFILLMENT_ID, EXCEPTION_ID, HANDOFF_ID, RECIPIENT_ID, SERVICE_CONTEXT_ID,
    PREVIOUS_OWNER_ID,
    EXPECTED_REVISION, EXPECTED_WORK_REVISION, EXPECTED_DRAWER_REVISION, EXPECTED_CUSTODIAN_REVISION,
    EXPECTED_SAFE_REVISION, EXPECTED_INVENTORY_REVISION, EXPECTED_SOURCE_REVISION, EXPECTED_CATALOG_REVISION,
    EXPECTED_BASIS_REVISION, EXPECTED_RECEIPT_REVISION, EXPECTED_COUNT_REVISION, EXPECTED_ITEM_STORAGE_REVISION, NEW_ITEM,
    EXPECTED_SURPLUS_AUTHORITY_REVISION, EXPECTED_COMPANY_PRICE_REVISION, EXPECTED_PRICE_POLICY_REVISION, EXPECTED_OVERRIDE_STORAGE_REVISION,
    EXPECTED_STORAGE_REVISION, EXPECTED_SESSION_REVISION, EXPECTED_ORDER_REVISION, EXPECTED_COLLECTION_REVISION,
    EXPECTED_ARRIVAL_REVISION, EXPECTED_SUPPLIER_STORAGE_REVISION, EXPECTED_WAREHOUSE_STORAGE_REVISION,
    EXPECTED_CATALOG_STORAGE_REVISION, EXPECTED_EVIDENCE_POLICY_REVISION, EXPECTED_ATTACHMENT_STORAGE_REVISION, EXPECTED_SERIAL_REGISTRATION_REVISION,
    EXPECTED_RESERVATION_REVISION, STOCK_ISSUE_ID, POSITION_ID, QUANTITY_SCALE, EXPIRY_AT,
    NAME_ENGLISH, NAME_ARABIC, LABEL, BARCODE, SKU_CODE, ACCOUNT, ACTIVITY, MODE, CHANNEL, STATUS,
    CURRENCY, UNIT, CONVERSION_ID, QUANTITY, ACCEPTED_QUANTITY, QUARANTINED_QUANTITY,
    REJECTED_HELD_QUANTITY, REFUSED_QUANTITY, COUNTED_QUANTITY, ORIGINAL_OWNED_VALUE, SURPLUS_VALUE,
    AMOUNT, UNIT_PRICE, OPENING_FLOAT, COUNTED_CASH, RETAINED_FLOAT, TENDERED_AMOUNT,
    BUSINESS_DATE, OCCURRED_AT, EXPIRY_DATE, FROM_DATE, THROUGH_DATE,
    DELIVERY_EVIDENCE_ID, DELIVERY_OCCURRED_AT, ACTIVE, REQUIRES_TERMINAL, REQUIRES_REFERENCE,
    VALID_FROM, VALID_UNTIL, MAXIMUM_QUANTITY, MAXIMUM_OWNED_VALUE, INSPECTION_REQUIRED,
    PAYMENT_METHOD, EXTERNAL_REFERENCE, PAYMENT_ATTESTED, REASON, VARIANCE_REASON, NOTES,
    DISPOSITION, CUSTODY, ROLE, PERMISSIONS, REPORT_KIND, BACKUP_REFERENCE, CONFIRMED, LINES,
    EXPECTED_FOOD_ORDER_STORAGE_REVISION, EXPECTED_FOOD_PORTION_DOMAIN_REVISION, EXPECTED_FOOD_RECIPE_REVISION,
    RECIPE_ID, CHOICE_ID, STATION_ID, TASK_ID, CONFIRMATION_ID, SERVICE_REFERENCE, SEAT_NOTE, ITEM_NOTE, REASON_ID,
    /** Dedicated typed-retail gateway binds its FULL immutable canonical request, not raw UI JSON. */
    RETAIL_BODY_DIGEST,
    INVOICE_ID, INVOICE_LINE_ID, RECEIPT_LINE_ID, MATCH_ID, EXPECTED_PROGRESS_REVISION,
    DISCOUNT_PERCENT, TAX_SOURCE_ID, TAX_REVISION, TAX_CATEGORY, TAX_RATE, NET_AMOUNT, TAX_AMOUNT,
    SUPPLIER_ITEM_REFERENCE, CHARGE_CODE, COST_TREATMENT, RECEIPT_COST_BASIS,
    EXPECTED_DELIVERY, REQUESTED_DELIVERY, PAYMENT_TERMS, DUE_DATE,
    CASE_ID, EXPECTED_CASE_REVISION, EXPECTED_ENTITLEMENT_REVISION, EXPECTED_CLAIM_REVISION,
    DECISION_OPERATION_ID, CONDITION, REQUESTED_REMEDY, CLAIM_TIME_ZONE, CLAIMED_AT, ASSERTED_RECEIVED_AT,
    CASH_COUNT_ID, PREVIOUS_CASH_COUNT_ID, EXPECTED_TRANSFER_REVISION,
    /** Dedicated food setup binds the exact immutable command, never raw unsigned company input. */
    FOOD_CONFIGURATION_BODY_DIGEST, EXPECTED_FOOD_RECIPE_STORAGE_REVISION,
    /** Exact existing FOOD payment original, not a physical recording or native instruction. */
    FOOD_PAYMENT_RECORDING_OPERATION_ID, FOOD_PAYMENT_ORIGINAL_BODY_DIGEST,
    CREDIT_ID, CREDIT_AGREED, EXPECTED_PAYABLE_REVISION, EXPECTED_CREDIT_REVISION, EXPECTED_REMAINING_DUE,
    EXPECTED_SETTLEMENT_REVISION, EXPECTED_ALLOCATED_PAYMENT, EXPECTED_RESERVED_CASH, EXPECTED_AVAILABLE_TO_ALLOCATE,
    ADVANCE_ID, EXPECTED_ADVANCE_REVISION, EXPECTED_ADVANCE_ALLOCATION_REVISION, EXPECTED_UNAPPLIED_ADVANCE,
    OPENING_OBLIGATION_ID, SOURCE_DOCUMENT_IDENTITY, SOURCE_DOCUMENT_TEXT,
    FOOD_SERVING_COUNT, LINE_SEAT_NOTE, FOOD_PORTION_IDS,
    FOOD_DELIVERY_RECIPIENT, FOOD_DELIVERY_CONTACT, FOOD_DELIVERY_ADDRESS,
    FLOOR_ID, LAYOUT_ORDER,
    EXPECTED_CUSTOMER_RECEIPT_REVISION, CUSTOMER_RECEIVED_AT, CUSTOMER_RECEIPT_TIME_ZONE,
    EXPECTED_PRICE_POLICY_STORAGE_REVISION, EXPECTED_PRICE_HISTORY_INDEX_STORAGE_REVISION,
    PURCHASING_DOCUMENT_BODY_DIGEST,
    ARRIVAL_SELECTION_DIGEST, ARRIVAL_SELECTION_DETAILS, ARRIVAL_RULE_ID, ARRIVAL_RETENTION,
    ARRIVAL_DOCUMENT_SELECTION, ARRIVAL_ORIGINAL_DOCUMENTS, EXPECTED_REQUESTER_POLICY_REVISION,
    EXPECTED_PRODUCT_REVISION,
    /** Optional documentary observation; absent/unchecked preserves the original qualification body. */
    RECORD_ORIGINAL_COMMERCIAL_TIMING,
    EXPECTED_FLOOR_STORAGE_REVISION, EXPECTED_TABLE_STORAGE_REVISION, EXPECTED_TABLE_ASSIGNMENT_STORAGE_REVISION,
    CLAIMED_REASON, ORDINARY_INSPECTION_CONDITION, ORDINARY_INSPECTION_EVIDENCE_ID, ORDINARY_INSPECTION_OCCURRED_AT,
    EXPECTED_DISPOSITION_REVISION, EXPECTED_ISSUE_REVISION, RESTORATION_ID, EXPECTED_POSTED_QUANTITY,
    RETURN_STOCK_AFTER_ALLOCATION_ID,
    PAYMENT_TERMINAL,
    /** Actual supplier-document unit snapshot; these inputs do not approve a conversion. */
    PURCHASE_BASE_UNIT, PURCHASE_BASE_SCALE, PURCHASE_CONVERSION_REVISION, PURCHASE_CONVERSION_RATE,
    /** Literal supplier references, distinct from validated internal purchase/allocation IDs. */
    SUPPLIER_ORDER_REFERENCE, SUPPLIER_ALLOCATION_REFERENCE,
    /** Optional literal directory code, distinct from supplier ID and invoice references. */
    SUPPLIER_REFERENCE,
    /** Dedicated setup closure binds one exact immutable production request, never raw UI JSON. */
    SETUP_BODY_DIGEST,
}

enum class LocalRecordKind {
    ORDER, ORDER_LINE, SALE, RETURN, COLLECTION, PAYMENT, CASH_SESSION, DRAWER, CUSTODIAN, SAFE,
    PRODUCT, VARIANT, SKU, CATALOG_VERSION, WAREHOUSE, LOT, STOCK_CUT, RECEIPT, INSPECTION, SUPPLIER, PURCHASE, COUNT,
    RECEIVING_BASIS, RECEIVING_ALLOCATION, RECEIPT_LINE, COUNT_SURPLUS_BASIS, PRICE_RULE, PRICE_HISTORY, PRICE_EVENT, TENDER, SALES_ROUTE,
    ARRIVAL, ARRIVAL_SLICE, ARRIVAL_EVENT, ARRIVAL_SOURCE, ARRIVAL_LIST,
    RESERVATION, TRANSFER, KITCHEN_TICKET, FULFILLMENT, EXCEPTION, EMPLOYEE, DEVICE, POLICY, BRANCH, REPORT, BACKUP,
    FOOD_ORDER, FOOD_PORTION, FOOD_STATION, FOOD_TASK,
    PURCHASE_INVOICE, SUPPLIER_PAYABLE, PURCHASE_LINE, PURCHASE_CHARGE, PURCHASE_MATCH,
    RETURN_CASE, RETURN_CASE_HISTORY, RETURN_CASE_EVENT, RETURN_RECEIPT_PORTION, RETURN_FINANCE, RETURN_OBLIGATION,
    FOOD_COLLECTION, FOOD_SALE, FOOD_REFUND, FOOD_QUALITY_CLAIM,
    CASH_COUNT, CASH_CLOSE_NOTE, STOCK_VALUE_POOL, FOOD_RECIPE, FOOD_ROUTE,
    SUPPLIER_CREDIT, SUPPLIER_ADVANCE, SUPPLIER_SETTLEMENT_ALLOCATION, OPENING_SUPPLIER_OBLIGATION,
    RETURN_RIGHTS_CURRENT, RETURN_RIGHTS_PUBLICATION,
    CUSTOMER_RECEIPT, CUSTOMER_RECEIPT_PORTION, CUSTOMER_RECEIPT_EVENT, CUSTOMER_RECEIPT_BATCH,
    PRICE_POLICY_PUBLICATION, PRICE_HISTORY_INDEX,
    CATALOG_RELEASE_PUBLICATION, SERIAL_REGISTRATION_PUBLICATION,
    PURCHASING_DOCUMENT, PURCHASING_DOCUMENT_ATTACHMENT, PURCHASING_DOCUMENT_DUPLICATE,
    ARRIVAL_V2, ARRIVAL_SLICE_V2, ARRIVAL_EVENT_V2, ARRIVAL_SOURCE_V2, ARRIVAL_LIST_V2,
    ARRIVAL_SUPPLIER_V2, ARRIVAL_WAREHOUSE_V2,
    RETURN_STOCK, RETURN_STOCK_ALLOCATION,
    PAYMENT_CORRECTION_SOURCE, PAYMENT_CORRECTION_HISTORY, PAYMENT_CORRECTION,
}

/** Read-only documentary content; no entry here is sale eligibility or an action. */
data class LocalReturnRightsUiRule(
    val categoryCode: String,
    val reasonCode: String,
    val values: List<LocalRecordValue>,
)

data class LocalReturnRightsUiPage(
    val selection: LocalRecordReference,
    val companyId: DomainId,
    val branchId: DomainId,
    val attribution: List<LocalRecordValue>,
    val continuingRights: LocalText,
    val rules: List<LocalReturnRightsUiRule>,
) {
    val historical: Boolean get() = selection.kind == LocalRecordKind.RETURN_RIGHTS_PUBLICATION
}

/** Read-only section/cost selectors, never an authorization grant or a command. */
enum class LocalPurchasingSection { SUPPLIERS, RECEIVING_ALLOWANCES, ORDERS, INVOICES, PAYABLES, ADVANCES, OPENING_OBLIGATIONS, DOCUMENTS }
data class LocalPurchasingUiQuery(
    val section: LocalPurchasingSection = LocalPurchasingSection.SUPPLIERS,
    val includeCosts: Boolean = false,
)

/** Cost access remains an explicit, separately authorized read, not a quantity-view field. */
enum class LocalInventorySection { QUANTITIES, COSTS, SETUP, OPENING }

/** A current, authorized approval page for the existing first-stock editor, not a bulk command. */
data class LocalOpeningAllocationQuery(
    val basisId: DomainId,
    val basisRevision: Revision,
    val skuId: DomainId,
    val skuRevision: Revision,
)

data class LocalOpeningAllocationRow(val record: LocalRecord, val initialLine: LocalInputLine) {
    val allocationId: DomainId get() = record.reference.id
    init {
        require(record.reference.kind == LocalRecordKind.RECEIVING_ALLOCATION && record.reference.parentId != null)
        require(initialLine.values.singleOrNull { it.field == LocalField.ALLOCATION_ID }?.value ==
            LocalScalarValue.Identifier(allocationId))
    }
}

/** Retain one physical-allocation page and only the bounded ID roster needed to finish the
 * whole SKU count. The roster contains no copied catalog, stock census, counts or values. */
data class LocalOpeningAllocationPage(
    val query: LocalOpeningAllocationQuery,
    val allocationIds: List<DomainId>,
    val offset: Int,
    val rows: List<LocalOpeningAllocationRow>,
    val nextCursor: String?,
) {
    init {
        require(allocationIds.size in 1..MAX_ALLOCATIONS &&
            allocationIds.zipWithNext().all { (a, b) -> a.value < b.value })
        require(offset in allocationIds.indices && rows.map { it.allocationId } == allocationIds.drop(offset).take(PAGE_SIZE))
        require(rows.all { row -> row.record.reference.parentId == query.basisId &&
            row.record.reference.revision == query.basisRevision &&
            (row.initialLine.values.singleOrNull { it.field == LocalField.SKU_ID }?.value as? LocalScalarValue.Reference)?.value ==
                LocalRecordReference(LocalRecordKind.SKU, query.skuId, query.skuRevision) })
        require(rows.map { it.initialLine.lineId }.distinct().size == rows.size)
        require(nextCursor == if (offset + rows.size < allocationIds.size) "opening-allocations:${offset + rows.size}" else null)
    }
    companion object {
        const val PAGE_SIZE: Int = 16
        const val MAX_ALLOCATIONS: Int = 128
        /** Mirrors the unchanged LocalGoodsReceiptRequest production bound, not a new grant. */
        const val MAX_POSITIVE_LINES: Int = 16
    }
}

enum class LocalReturnSection { CASES, RECEIPTS }
data class LocalReturnUiQuery(val section: LocalReturnSection = LocalReturnSection.CASES)

/** Revision is the owning service's domain revision, NEVER an inferred storage generation. */
data class LocalRecordReference(
    val kind: LocalRecordKind,
    val id: DomainId,
    val revision: Revision? = null,
    /** Owning SKU/document for a nested coordinate; not authorization or a storage revision. */
    val parentId: DomainId? = null,
)
data class LocalRecordValue(val label: LocalText, val value: LocalText)
/** Original format controls available editors only; it never grants financial authority. */
enum class LocalInvoiceEntryKind { ORDER_LINKED_V1, CAPTURE_ONLY_V2 }
data class LocalRecord(
    val reference: LocalRecordReference,
    val title: LocalText,
    val status: LocalText? = null,
    val values: List<LocalRecordValue> = emptyList(),
    /** Nested read-only lines do not invent a navigable service query for their IDs. */
    val selectable: Boolean = true,
    /** Actual purchasing read selector for supplier-panel prefill only; never source authority. */
    val supplierId: DomainId? = null,
    /** A capture-only claim must not activate the legacy supplier-payout editor. */
    val invoiceEntryKind: LocalInvoiceEntryKind? = null,
) { init { require(values.size <= 32) } }

enum class LocalInputKind { TEXT, MULTILINE, IDENTIFIER, INTEGER, DECIMAL, DATE, CHOICE, REFERENCE, MULTI_CHOICE, MULTI_REFERENCE, CHECKBOX }
data class LocalChoice(val code: String, val label: LocalText) {
    init { require(code.length in 1..96 && code.all { it.code in 0x21..0x7e }) }
}
data class LocalReferenceChoice(val reference: LocalRecordReference, val label: LocalText)

/** Exact values only: monetary and quantity input never crosses this port as Float/Double. */
sealed interface LocalScalarValue {
    data class Text(val value: String) : LocalScalarValue { init { require(value.length <= 4096) } }
    data class Identifier(val value: DomainId) : LocalScalarValue
    data class Integer(val value: Long) : LocalScalarValue
    data class Decimal(val canonical: String) : LocalScalarValue {
        init { require(canonical.length <= 96 && Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?").matches(canonical)) }
    }
    data class Date(val iso: String) : LocalScalarValue {
        init { require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(iso)) }
    }
    data class Choice(val code: String) : LocalScalarValue {
        init { require(code.length in 1..96 && code.all { it.code in 0x21..0x7e }) }
    }
    data class Reference(val value: LocalRecordReference) : LocalScalarValue
    class Choices(codes: List<String>) : LocalScalarValue {
        private val entries = codes.toList()
        val codes: List<String> get() = entries.toList()
        init {
            require(entries.size <= 32 && entries.distinct().size == entries.size)
            entries.forEach { Choice(it) }
        }
    }
    class References(values: List<LocalRecordReference>) : LocalScalarValue {
        private val entries = values.toList()
        val values: List<LocalRecordReference> get() = entries.toList()
        init { require(entries.size <= 32 && entries.distinct().size == entries.size) }
    }
    data class Flag(val value: Boolean) : LocalScalarValue
}

data class LocalScalarInput(val field: LocalField, val value: LocalScalarValue)
class LocalInputLine(val lineId: DomainId, values: List<LocalScalarInput>) {
    private val entries = values.toList()
    val values: List<LocalScalarInput> get() = entries.toList()
    init { require(entries.size <= 24 && entries.map { it.field }.distinct().size == entries.size) }
}

sealed interface LocalFormField {
    val field: LocalField
    val label: LocalText

    data class Scalar(
        override val field: LocalField,
        override val label: LocalText,
        val kind: LocalInputKind,
        val required: Boolean = true,
        val help: LocalText? = null,
        val maximumCharacters: Int = 256,
        val allowNegative: Boolean = false,
        val choices: List<LocalChoice> = emptyList(),
        val references: List<LocalReferenceChoice> = emptyList(),
        val initialValue: LocalScalarValue? = null,
        val readOnly: Boolean = false,
        /** Optional paged directory, not a preloaded or silently truncated choice list. */
        val referenceSource: LocalReferenceSource? = null,
    ) : LocalFormField {
        init {
            require(maximumCharacters in 1..4096 && choices.size <= 100 && references.size <= 100)
            require(choices.map { it.code }.distinct().size == choices.size)
            require(references.map { it.reference }.distinct().size == references.size)
            require(referenceSource == null || kind == LocalInputKind.REFERENCE)
        }
    }

    /** One bounded, immutable receipt/order/count proposal, not a loop of partial mutations. */
    data class Lines(
        override val field: LocalField,
        override val label: LocalText,
        val columns: List<Scalar>,
        val minimumLines: Int = 1,
        val maximumLines: Int = 64,
        val initialLines: List<LocalInputLine> = emptyList(),
        /** Receipt splits clone approved metadata but receive a fresh editor/source identity. */
        val allowBlankLines: Boolean = true,
        val allowDuplicateLines: Boolean = false,
    ) : LocalFormField {
        init {
            require(columns.size in 1..24 && columns.map { it.field }.distinct().size == columns.size)
            require(minimumLines in 0..maximumLines && maximumLines in 1..64)
            require(initialLines.size <= maximumLines && initialLines.map { it.lineId }.distinct().size == initialLines.size)
            require(initialLines.all { line -> line.values.all { value -> columns.any { it.field == value.field } } })
            require(allowBlankLines || initialLines.size >= minimumLines)
        }
    }
}

/** Returned only for a known, actually wired handler after current authorization of the read. */
data class LocalActionForm(
    val action: LocalAction,
    val version: Int,
    val title: LocalText,
    val fields: List<LocalFormField>,
    val explanation: LocalText? = null,
    val confirmation: LocalText? = null,
    /** Owning service's reserved financial-observation ID; never a new payout/collection ID. */
    val reservedOperationId: OperationId? = null,
    /** Complete-group review is ephemeral; only positive physical slices enter the old command. */
    val openingAllocations: LocalOpeningAllocationPage? = null,
    /** Complete inert v2 choices; explicit user selections are frozen into the staged intent. */
    val arrivalEvidence: LocalArrivalEvidenceOptionsV2? = null,
) {
    init {
        require(version in 1..1000 && fields.size <= 32 && fields.map { it.field }.distinct().size == fields.size)
        require(fields.count { it is LocalFormField.Lines } <= 1)
        require(openingAllocations == null || action == LocalAction.STOCK_OPENING)
        require(arrivalEvidence == null || version == 2 && action in localArrivalActionsV2 &&
            arrivalEvidence.rules.all { it.action == action })
        if (openingAllocations != null) {
            val counts = fields.filterIsInstance<LocalFormField.Lines>().single()
            require(counts.field == LocalField.LINES && counts.minimumLines == 0 && counts.initialLines.isEmpty() && !counts.allowBlankLines)
            require(fields.none { it.field == LocalField.CONFIRMED }) // Added only after the complete physical review.
        }
    }
}

/** Read-only price selection. It neither reserves a quote nor accepts a caller-calculated price. */
data class LocalPricePreviewQuery(val skuId: DomainId, val channel: String, val quantity: LocalScalarValue.Decimal) {
    init { require(channel in setOf("COUNTER", "DINE_IN", "TAKEAWAY", "DELIVERY")) }
}

/** Actual SKU unit metadata for the explicit preview controls; never stock or pricing authority. */
data class LocalPriceReadControls(val skuId: DomainId, val unitId: DomainId, val quantityScale: Int) {
    init { require(quantityScale in 0..6) }
}

data class LocalWorkflowQuery(
    val workflow: LocalWorkflow,
    val search: String = "",
    val reference: LocalRecordReference? = null,
    val cursor: String? = null,
    val pricePreview: LocalPricePreviewQuery? = null,
    val branchReport: LocalBranchReportQuery? = if (workflow == LocalWorkflow.REPORTS)
        LocalBranchReportQuery(LocalBranchReportSection.CASH_SESSIONS) else null,
    val food: LocalFoodUiQuery? = if (workflow.usesFoodQueries) workflow.defaultFoodQuery() else null,
    val purchasing: LocalPurchasingUiQuery? = if (workflow == LocalWorkflow.PURCHASING) LocalPurchasingUiQuery() else null,
    val returns: LocalReturnUiQuery? = if (workflow == LocalWorkflow.RETURNS) LocalReturnUiQuery() else null,
    val cash: LocalCashUiQuery? = if (workflow == LocalWorkflow.CASH) LocalCashUiQuery() else null,
    val inventory: LocalInventorySection? = if (workflow == LocalWorkflow.INVENTORY) LocalInventorySection.QUANTITIES else null,
    val openingAllocations: LocalOpeningAllocationQuery? = null,
) {
    init {
        require(search.length <= 256 && (cursor == null || cursor.length <= 512))
        require(pricePreview == null || workflow == LocalWorkflow.CATALOG && reference?.kind == LocalRecordKind.SKU &&
            reference?.id == pricePreview.skuId && cursor == null)
        require(branchReport == null || workflow == LocalWorkflow.REPORTS)
        require(food == null || food.canAppearIn(workflow))
        require(purchasing == null || workflow == LocalWorkflow.PURCHASING)
        require(returns == null || workflow == LocalWorkflow.RETURNS)
        require(cash == null || workflow == LocalWorkflow.CASH)
        require((inventory != null) == (workflow == LocalWorkflow.INVENTORY))
        require(workflow != LocalWorkflow.CASH || cash != null)
        require(openingAllocations == null || workflow == LocalWorkflow.INVENTORY && inventory == LocalInventorySection.OPENING &&
            reference != null && reference.kind == LocalRecordKind.SKU && reference.id == openingAllocations.skuId &&
            reference.parentId == openingAllocations.basisId)
        require(workflow != LocalWorkflow.PURCHASING || purchasing != null)
        require(!workflow.usesFoodQueries || food != null && search.isEmpty() && reference == null &&
            cursor == null && pricePreview == null && branchReport == null)
        require(workflow != LocalWorkflow.REPORTS || branchReport != null && search.isEmpty() &&
            reference == null && cursor == null && pricePreview == null)
    }
}

data class LocalWorkflowPage(
    val workflow: LocalWorkflow,
    val records: List<LocalRecord>,
    val forms: List<LocalActionForm>,
    val notice: LocalText? = null,
    val nextCursor: String? = null,
    val priceControls: LocalPriceReadControls? = null,
    val branchReport: LocalBranchReportPage? = null,
    val food: LocalFoodUiPage? = null,
    val openingAllocations: LocalOpeningAllocationPage? = null,
    val managerQuoteDetails: LocalManagerQuoteDetails? = null,
    val returnRights: LocalReturnRightsUiPage? = null,
) {
    init {
        require(records.size <= 100 && forms.size <= 32)
        require(records.map { it.reference.kind to it.reference.id }.distinct().size == records.size)
        require(forms.all { it.action.canAppearIn(workflow) } && forms.map { it.action }.distinct().size == forms.size)
        require(nextCursor == null || nextCursor.length <= 512)
        require(priceControls == null || workflow == LocalWorkflow.CATALOG)
        require(returnRights == null || workflow in setOf(LocalWorkflow.CATALOG, LocalWorkflow.SETUP) &&
            records.isEmpty() && forms.isEmpty() && nextCursor == null && priceControls == null &&
            returnRights.rules.size in 1..128 &&
            returnRights.rules.map { it.categoryCode to it.reasonCode }.distinct().size == returnRights.rules.size)
        require(food == null || food.query.canAppearIn(workflow) && records.isEmpty() && nextCursor == null &&
            priceControls == null && branchReport == null && (food.next == null || food.next.canAppearIn(workflow)))
        require(!workflow.usesFoodQueries || food != null)
        require(openingAllocations == null || workflow == LocalWorkflow.INVENTORY)
        require(branchReport == null || workflow == LocalWorkflow.REPORTS && records.isEmpty() &&
            forms.isEmpty() && nextCursor == null && priceControls == null)
        require(managerQuoteDetails == null || workflow == LocalWorkflow.ORDERS && forms.isEmpty() &&
            nextCursor == null && priceControls == null && food == null && branchReport == null &&
            openingAllocations == null && records.none { it.selectable } &&
            records.singleOrNull { it.reference.kind == LocalRecordKind.ORDER }?.reference ==
                LocalRecordReference(LocalRecordKind.ORDER, managerQuoteDetails.orderId, managerQuoteDetails.revision))
    }
}

enum class LocalServiceProblem {
    NOT_CONFIGURED, NOT_IMPLEMENTED, DENIED, SESSION_ENDED, UNAVAILABLE, STORAGE_UNCERTAIN,
    INVALID_INPUT, RETRY_LATER, REPORT_RESTART_REQUIRED,
    PURCHASING_CAPACITY_REQUIRED,
    RETURN_CAPACITY_REQUIRED, RETURN_RESTART_REQUIRED,
    FOOD_TARGET_UNAVAILABLE, FOOD_CAPACITY_REQUIRED, FOOD_NOT_PRECOLLECTION, FOOD_PAYMENT_UNRESOLVED,
    CASH_RESTART_REQUIRED,
}
sealed interface LocalReadResult {
    data class Available(val page: LocalWorkflowPage) : LocalReadResult
    data class Unavailable(val problem: LocalServiceProblem,
        val foodReason: LocalFoodUnavailable? = foodUnavailableReason(problem)) : LocalReadResult {
        init { require(foodReason == foodUnavailableReason(problem)) }
    }
}

/** UI identity is staged durably by the adapter BEFORE submit or any external collection. */
class LocalCommandIntent(
    val operationId: OperationId,
    val action: LocalAction,
    val formVersion: Int,
    val reference: LocalRecordReference?,
    values: List<LocalScalarInput>,
    val lineField: LocalField? = null,
    lines: List<LocalInputLine> = emptyList(),
    /** Original complete selection, not a digest used to reconstruct current sources at save. */
    val arrivalEvidence: LocalArrivalEvidenceInputV2? = null,
) {
    private val valueEntries = values.toList()
    private val lineEntries = lines.toList()
    val values: List<LocalScalarInput> get() = valueEntries.toList()
    val lines: List<LocalInputLine> get() = lineEntries.toList()
    init {
        require(formVersion in 1..1000 && values.size <= 32 && lines.size <= 64)
        require(values.map { it.field }.distinct().size == values.size)
        require(lines.map { it.lineId }.distinct().size == lines.size)
        require(lineField != null || lines.isEmpty())
        require(lineField == null || values.none { it.field == lineField })
        require(arrivalEvidence == null || formVersion == 2 && action in localArrivalActionsV2 &&
            arrivalEvidence.rule.action == action)
        // This bounds UI input material, not the handler's independently bounded canonical wire DTO.
        require(values.sumOf { it.value.inputBytes() } + lines.sumOf { line ->
            36 + line.values.sumOf { it.value.inputBytes() }
        } + (arrivalEvidence?.inputBytes() ?: 0) <= MAX_LOCAL_INPUT_BYTES)
    }
}

enum class LocalPendingPhase { STAGED, DISPATCH_UNRESOLVED, RECORDED_UNACKNOWLEDGED }

data class LocalPendingOperation(
    val operationId: OperationId,
    val action: LocalAction,
    val label: LocalText,
    /** Authorized display of the STORED immutable request, not a newly priced/current form. */
    val summary: List<LocalRecordValue> = emptyList(),
    /** Current continuation hint only. submit still reauthorizes the original actor and request.
     * A received no-admission retirement response never permits business submission. */
    val submitAllowed: Boolean = false,
    val phase: LocalPendingPhase = LocalPendingPhase.STAGED,
    /** Only a retained committed kernel outcome may supply terminal metadata. Lookup reauthorizes it. */
    val recordedDecision: LocalRecordedDecision? = null,
    /** Exact saved arrival approval ceremony; no current permission or authority is cached here. */
    val actionApproval: LocalActionApprovalState? = null,
) {
    init {
        require(summary.size <= 32)
        require((phase == LocalPendingPhase.RECORDED_UNACKNOWLEDGED) == (recordedDecision != null))
        require(phase != LocalPendingPhase.RECORDED_UNACKNOWLEDGED || !submitAllowed)
        require(actionApproval == null || action in localArrivalActionsV2 &&
            actionApproval.rule.action == action && actionApproval.review?.operationId?.let { it == operationId } != false)
        require(actionApproval == null || !submitAllowed || actionApproval.phase == LocalActionApprovalPhase.ATTACHED)
    }

    /** Intake belongs in explicit owner-private review, never the generic pending list. */
    fun listSummary(): List<LocalRecordValue> = if (action == LocalAction.FOOD_CREATE_DRAFT) emptyList() else summary
}
sealed interface LocalStageResult {
    data class Staged(val operation: LocalPendingOperation) : LocalStageResult
    /** Explicitly certifies no dispatch and no staged intent; ordinary transport errors cannot use this. */
    data class NotStaged(val problem: LocalServiceProblem, val field: LocalField? = null) : LocalStageResult
    /** Access loss never erases the potentially durable original or certifies no staging. */
    data class Uncertain(val operationId: OperationId, val accessProblem: LocalServiceProblem? = null) : LocalStageResult {
        init { require(accessProblem == null || accessProblem == LocalServiceProblem.DENIED || accessProblem == LocalServiceProblem.SESSION_ENDED) }
    }
}

/** RECORDED_ONLY is not a payment result: e.g. Start, raw fact, held stock or audited review. */
enum class LocalRecordedDecision { APPLIED, REJECTED, RECORDED_ONLY }

/** Original saved stock request/result binding, not a refund route or an authority grant. */
data class LocalReturnStockOperationDetails(
    val operationId: OperationId,
    val saleId: DomainId,
    val caseId: DomainId,
    val returnId: DomainId,
    val restorationId: DomainId,
    val expectedDispositionRevision: Revision,
    val dispositionRevision: Revision,
) {
    init {
        require(expectedDispositionRevision.value < Long.MAX_VALUE &&
            dispositionRevision.value == expectedDispositionRevision.value + 1)
        require(restorationId != returnId)
    }
}

sealed interface LocalOperationResult {
    /** The service must return this only after the OUTER durable result/effects/audit/outbox commit. */
    data class Recorded(
        val operationId: OperationId,
        val decision: LocalRecordedDecision,
        val message: LocalText,
        val result: LocalRecord? = null,
        val replayed: Boolean = false,
        val food: LocalFoodOperationDetails? = null,
        val returnStock: LocalReturnStockOperationDetails? = null,
        /** Action of the validated immutable original, not a caller selection or permission.
         * Required when recovering an original that is no longer in the pending list. */
        val originalAction: LocalAction? = null,
        /** Validated exact original/result binding for the explicitly selected SQL-only FOOD lane. */
        val foodCashFinancial: LocalFoodCashFinancialOperationDetails? = null,
    ) : LocalOperationResult {
        init {
            require(returnStock == null || returnStock.operationId == operationId &&
                decision == LocalRecordedDecision.APPLIED && food == null)
            require(foodCashFinancial == null || foodCashFinancial.operationId == operationId && food == null && returnStock == null)
        }
    }
    data class Pending(val operation: LocalPendingOperation) : LocalOperationResult
    /** Not a failed payment, durable rejection, safe new-ID permission, or authoritative absence. */
    data class Unresolved(val operationId: OperationId, val problem: LocalServiceProblem) : LocalOperationResult
}

sealed interface LocalPendingResult {
    data class Available(val operations: List<LocalPendingOperation>, val nextCursor: String? = null) : LocalPendingResult {
        init {
            require(operations.size <= 100 && operations.map { it.operationId }.distinct().size == operations.size)
            require(nextCursor == null || nextCursor.length in 1..512)
        }
    }
    data class Unavailable(val problem: LocalServiceProblem) : LocalPendingResult
}

/** Client presentation acknowledgement only; never deletes master/financial evidence. */
sealed interface LocalAcknowledgementResult {
    data class Acknowledged(val operationId: OperationId) : LocalAcknowledgementResult
    /** A lost acknowledgement does not make the already recorded business outcome unknown. */
    data class Unresolved(val operationId: OperationId, val problem: LocalServiceProblem) : LocalAcknowledgementResult
}

/**
 * Session-owned UI port, implemented over actual local services and durable client intent storage.
 * Every call rechecks the current principal/grants. Display fields never authorize a call.
 * stage is idempotent for the SAME ID + immutable action/body, rejects identity conflicts, and
 * returns Staged only after durable intent storage. It never performs the business mutation.
 * submit uses that stored body/original actor; it must not accept a replacement form or new actor.
 * A lost reply/cancelled caller retains the identity and queue. lookup/pending are authenticated
 * recovery reads, not permission to discard an unknown result or repeat collection.
 * No in-memory implementation or synthetic financial success is supplied by the UI.
 */
interface LocalWorkflowGateway {
    /** Source bytes are retained under the current original actor before any dispatch. */
    suspend fun savePurchasingDocument(capture: LocalPurchasingDocumentCapture, originalBytes: ByteArray): LocalPurchasingDocumentSourceResult =
        LocalPurchasingDocumentSourceResult.Unavailable(LocalServiceProblem.NOT_IMPLEMENTED, capture.documentId)
    suspend fun purchasingDocumentSources(): LocalPurchasingDocumentSourcesResult =
        LocalPurchasingDocumentSourcesResult.Unavailable(LocalServiceProblem.NOT_IMPLEMENTED)
    /** Explicitly resume sequential original Begin/chunk/Complete identities; never replace a capture. */
    suspend fun transferPurchasingDocument(documentId: DomainId): LocalPurchasingDocumentTransferResult =
        LocalPurchasingDocumentTransferResult.Unresolved(documentId, null, LocalServiceProblem.NOT_IMPLEMENTED)
    /** Explicit new child-completion CAS after the exact original was durably refused. */
    suspend fun retryPurchasingDocumentCompletion(documentId: DomainId, refusedCompleteOperationId: OperationId,
        newParentAttachmentsRevision: Revision): LocalPurchasingDocumentSourceResult =
        LocalPurchasingDocumentSourceResult.Unavailable(LocalServiceProblem.NOT_IMPLEMENTED, documentId)
    suspend fun downloadPurchasingDocument(documentId: DomainId, files: LocalPurchasingDocumentFiles,
        isCurrent: () -> Boolean): LocalPurchasingDocumentDownloadResult =
        LocalPurchasingDocumentDownloadResult.Unavailable(documentId, LocalServiceProblem.NOT_IMPLEMENTED)
    suspend fun read(query: LocalWorkflowQuery): LocalReadResult
    /** Protected read only. Saving still rereads current authoritative sources and permissions. */
    suspend fun previewFood(input: LocalFoodPreviewInput): LocalFoodPricePreviewResult =
        LocalFoodPricePreviewResult.Unavailable(LocalServiceProblem.NOT_IMPLEMENTED)
    suspend fun references(query: LocalReferenceQuery): LocalReferenceResult =
        LocalReferenceResult.Unavailable(LocalServiceProblem.NOT_IMPLEMENTED)
    suspend fun exportBranchReport(selection: LocalBranchReportExportSelection, files: LocalBranchReportFileDelivery,
        lifetime: LocalBranchReportExportLifetime, progress: (LocalBranchReportExportProgress) -> Unit): LocalBranchReportExportResult =
        LocalBranchReportExportResult.Unavailable(serviceProblem = LocalServiceProblem.NOT_IMPLEMENTED)
    suspend fun branchReportExports(files: LocalBranchReportFileDelivery, lifetime: LocalBranchReportExportLifetime,
        after: DomainId? = null): LocalBranchReportExportHistory =
        LocalBranchReportExportHistory.Unavailable(serviceProblem = LocalServiceProblem.NOT_IMPLEMENTED)
    /** Explicit original-identity reconciliation only; never re-reads/re-publishes a fresh CSV. */
    suspend fun recoverBranchReportExport(exportId: DomainId, files: LocalBranchReportFileDelivery,
        lifetime: LocalBranchReportExportLifetime): LocalBranchReportExportResult =
        LocalBranchReportExportResult.Unavailable(serviceProblem = LocalServiceProblem.NOT_IMPLEMENTED, exportId = exportId)
    suspend fun stage(intent: LocalCommandIntent): LocalStageResult
    /** Preview the exact saved body through the actual Master, then retain that display before confirmation. */
    suspend fun previewActionApproval(operationId: OperationId): LocalActionApprovalResult =
        LocalActionApprovalResult.Unavailable(operationId, LocalServiceProblem.NOT_IMPLEMENTED)
    /** Caller owns the password. Implementations copy before suspension and clear every owned copy.
     * Confirmed approval ID/digest must be durable before any issuance. Unknown issuance is lookup-only. */
    suspend fun issueActionApproval(operationId: OperationId, confirmationDigest: com.ukkera.pos.security.PayloadDigest,
        approverLogin: String, approverPassword: CharArray): LocalActionApprovalResult =
        LocalActionApprovalResult.Unavailable(operationId, LocalServiceProblem.NOT_IMPLEMENTED)
    /** Recover the original approval ID/digest. Observed absence never permits reissuance or renewal. */
    suspend fun lookupActionApproval(operationId: OperationId): LocalActionApprovalResult =
        LocalActionApprovalResult.Unavailable(operationId, LocalServiceProblem.NOT_IMPLEMENTED)
    /** Explicitly retire an issued original at the Master after durably staging a fresh
     * invocation. Unknown retirement is lookup-only. A received exact no-admission response
     * permits only a separate explicit retirement retry, never business submit or replacement. */
    suspend fun retireActionApproval(operationId: OperationId): LocalActionApprovalResult =
        LocalActionApprovalResult.Unavailable(operationId, LocalServiceProblem.NOT_IMPLEMENTED)
    /** Preserve the retired original and start a separate review; never issues or submits. */
    suspend fun beginActionApprovalReplacement(operationId: OperationId): LocalActionApprovalResult =
        LocalActionApprovalResult.Unavailable(operationId, LocalServiceProblem.NOT_IMPLEMENTED)
    suspend fun submit(operationId: OperationId): LocalOperationResult
    suspend fun lookup(operationId: OperationId): LocalOperationResult
    /** Cursor pages must not silently omit earlier unresolved entries or infer absence from loss. */
    suspend fun pending(cursor: String? = null): LocalPendingResult
    /**
     * Explicitly acknowledge a displayed terminal result. Reauthorize and match the original
     * actor/ID/body and actual committed kernel result before durable client-only archival.
     * Recorded results remain in pending until this succeeds, including after caller cancellation.
     */
    suspend fun acknowledgeRecorded(operationId: OperationId): LocalAcknowledgementResult =
        LocalAcknowledgementResult.Unresolved(operationId, LocalServiceProblem.NOT_IMPLEMENTED)
}

object UnavailableLocalWorkflows : LocalWorkflowGateway {
    override suspend fun read(query: LocalWorkflowQuery) = LocalReadResult.Unavailable(LocalServiceProblem.NOT_CONFIGURED)
    override suspend fun stage(intent: LocalCommandIntent) = LocalStageResult.NotStaged(LocalServiceProblem.NOT_CONFIGURED)
    override suspend fun submit(operationId: OperationId) = LocalOperationResult.Unresolved(operationId, LocalServiceProblem.NOT_CONFIGURED)
    override suspend fun lookup(operationId: OperationId) = LocalOperationResult.Unresolved(operationId, LocalServiceProblem.NOT_CONFIGURED)
    override suspend fun pending(cursor: String?) = LocalPendingResult.Unavailable(LocalServiceProblem.NOT_CONFIGURED)
}

internal const val MAX_LOCAL_INPUT_BYTES = 48 * 1024

private fun LocalScalarValue.inputBytes(): Int = when (this) {
    is LocalScalarValue.Text -> value.encodeToByteArray(throwOnInvalidSequence = true).size
    is LocalScalarValue.Identifier -> 36
    is LocalScalarValue.Integer -> 20
    is LocalScalarValue.Decimal -> canonical.length
    is LocalScalarValue.Date -> 10
    is LocalScalarValue.Choice -> code.length
    is LocalScalarValue.Reference -> 144
    is LocalScalarValue.Choices -> codes.sumOf { it.length }
    is LocalScalarValue.References -> values.size * 144
    is LocalScalarValue.Flag -> 1
}
