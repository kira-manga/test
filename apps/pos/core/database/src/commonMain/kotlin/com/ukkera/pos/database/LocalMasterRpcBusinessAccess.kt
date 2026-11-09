package com.ukkera.pos.database

import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.AuthorizationRequirement

/** Closed application profile, not a grant inferred from an RPC connection. */
public enum class LocalMasterRpcWorkflowProfile {
    SUPPLIERS, WAREHOUSES, REPORTS, STOCK_TRANSFERS, KITCHEN, RETAIL_ORDERS, PAYMENT_CORRECTIONS, CUSTOMER_RECEIPTS, FOOD_CHECKS, FOOD_TABLE_ASSIGNMENTS,
    INVOICE_CAPTURE,
    INVOICE_MATCHING,
    FOOD_CASH_FINANCIAL,
}

/** Exact captured-claim matching only; neither capture nor settlement is admitted here. */
public object LocalMasterInvoiceMatchingRpcKeysV1 {
    private val reads = setOf(
        ReadKey("local.purchasing.invoice.read", 2),
        ReadKey("local.purchasing.read", 1),
        ReadKey("local.purchasing.invoice.match-recognize-source", 2),
        ReadKey("purchasing.document.read", 1),
    )
    public fun accepts(key: ReadKey): Boolean = key in reads
    public fun accepts(key: CommandKey): Boolean = key == CommandKey("local.purchasing.invoice.match-recognize", 2)
}

/** Closed capture-only keys; installed handlers still classify exact document/query bodies. */
public object LocalMasterInvoiceCaptureRpcKeysV1 {
    private val reads = setOf(
        ReadKey("local.purchasing.invoice.read", 2),
        ReadKey("local.purchasing.read", 1),
        ReadKey("local.configuration.read", 1),
    )
    public fun accepts(key: ReadKey): Boolean = key in reads
    public fun accepts(key: CommandKey): Boolean = key == CommandKey("local.purchasing.invoice.record", 2)
}

/**
 * Inert key selection for the fixed current-document report routes, not body validation or
 * employee authority. The Master still normalizes with its installed exact typed handler and
 * checks every current requirement, including viewing and separate explicit EXPORT authority.
 */
public object LocalMasterReportRpcKeysV1 {
    private val documentRead = ReadKey("sales.branch-report.read", 1)
    private val exportRead = ReadKey("sales.branch-report-export.read", 1)
    private val exportCommand = CommandKey("sales.branch-report-export", 1)

    public fun accepts(key: ReadKey): Boolean = key == documentRead || key == exportRead
    public fun accepts(key: CommandKey): Boolean = key == exportCommand
}

/**
 * Closed keys for normalized same-branch transfers. Selection is inert: the installed Master
 * handler must still classify the exact Transfer body and warehouse-only configuration query.
 * Other normalized stock actions and every legacy transfer command remain outside this lane.
 */
public object LocalMasterStockTransferRpcKeysV1 {
    private val reads = setOf(
        ReadKey("stock.items", 1),
        ReadKey("stock.quantities", 1),
        ReadKey("stock.quantity.position", 1),
        ReadKey("stock.costs", 1),
        ReadKey("stock.transfer", 1),
        ReadKey("stock.transfer.costs", 1),
        ReadKey("stock.normalized.profile", 2),
        ReadKey("stock.normalized.history", 2),
        ReadKey("local.configuration.read", 1),
    )

    public fun accepts(key: ReadKey): Boolean = key in reads
    public fun accepts(key: CommandKey): Boolean = key == StockStreamingCommandsV2.STOCK
}

/** Closed station workflow keys; installed typed handlers still classify exact selectors/bodies. */
public object LocalMasterKitchenRpcKeysV1 {
    private val reads = setOf(
        ReadKey("local.food.station-directory", 1),
        ReadKey("sales.food.read", 1),
        ReadKey("stock.normalized.station-profile", 2),
    )
    public fun accepts(key: ReadKey): Boolean = key in reads
    public fun accepts(key: CommandKey): Boolean =
        key == CommandKey("sales.food.write", 1) || key == StockStreamingCommandsV2.FOOD
}

/** Closed own-retail-order keys; installed handlers still classify each exact selector/body. */
public object LocalMasterRetailOrderRpcKeysV1 {
    private val reads = setOf(ReadKey("local.configuration.read", 1),
        ReadKey("sales.order.read", 1), ReadKey("sales.order.read", 2))
    public fun accepts(key: ReadKey): Boolean = key in reads
    public fun accepts(key: CommandKey): Boolean =
        key == CommandKey("sales.order.write", 1) || key == StockStreamingCommandsV2.COLLECTION
}

/** Closed documentary correction keys; the installed handler still classifies the canonical body. */
public object LocalMasterPaymentCorrectionRpcKeysV1 {
    public fun accepts(key: ReadKey): Boolean = key == ReadKey("sales.payment-correction.read", 1)
    public fun accepts(key: CommandKey): Boolean = key == CommandKey("sales.payment-correction.write", 1)
}

/** Closed counter-observation keys; the installed handlers still select exact Head/Event/Record bodies. */
public object LocalMasterCustomerReceiptRpcKeysV1 {
    public fun accepts(key: ReadKey): Boolean = key == ReadKey("sales.customer-receipt.read", 1)
    public fun accepts(key: CommandKey): Boolean = key == CommandKey("sales.customer-receipt.write", 1)
}

/** Closed unpaid owner-check keys; installed handlers still admit only exact Draft/Add and own selectors. */
public object LocalMasterFoodCheckRpcKeysV1 {
    private val reads = setOf(
        ReadKey("sales.food.read", 1),
        ReadKey("sales.food.check-kitchen.read", 1),
        ReadKey("sales.food.check-kitchen.read", 2),
        ReadKey("sales.food.check-kitchen.read", 3),
        ReadKey("local.food.menu", 1),
        ReadKey("sales.food.quotation.profile", 1),
        ReadKey("sales.food.selection-preview", 1),
    )
    public fun accepts(key: ReadKey): Boolean = key in reads
    public fun accepts(key: CommandKey): Boolean = key == StockStreamingCommandsV2.FOOD
}

/**
 * Financial continuation only. Installed handlers must still select exact Begin/Finalize or
 * ApplyOriginal bodies; this key set never installs physical Start, raw capture or resolution.
 */
public object LocalMasterFoodCashFinancialRpcKeysV1 {
    private val reads = setOf(
        ReadKey("sales.food.read", 1),
        ReadKey("sales.food.check-kitchen.read", 1),
        ReadKey("sales.food.check-kitchen.read", 2),
        ReadKey("sales.food.check-kitchen.read", 3),
        ReadKey("stock.normalized.profile", 2),
        ReadKey("sales.food.financial.read", 1),
        ReadKey("sales.food.payment.read", 1),
    )
    public fun accepts(key: ReadKey): Boolean = key in reads
    public fun accepts(key: CommandKey): Boolean =
        key == StockStreamingCommandsV2.FOOD || key == CommandKey("sales.food.payment", 1)
}

/** Closed association-only keys; installed handlers still classify exact own/directory selectors. */
public object LocalMasterFoodTableAssignmentRpcKeysV1 {
    private val reads = setOf(
        ReadKey("sales.food.read", 1),
        ReadKey("sales.food.table.assignment-read", 1),
        ReadKey("local.food.floor-read", 1),
    )
    public fun accepts(key: ReadKey): Boolean = key in reads
    public fun accepts(key: CommandKey): Boolean = key == CommandKey("sales.food.table.assignment", 1)
}

/**
 * Optional installed typed handler for current access to an already retained result. The
 * kernel supplies the exact original context after checking its complete stored identity and
 * canonical body. Requirements describe disclosure only; this route cannot prepare, evaluate
 * or reinvoke the original mutation. Missing support denies the selected result disclosure.
 */
public interface LocalMasterRpcRetainedResultAccessProvider {
    public fun rpcRetainedResultRequirements(
        profile: LocalMasterRpcWorkflowProfile,
        normalized: StoredBytes,
        context: CommandContext,
    ): List<AuthorizationRequirement>
}

/**
 * Opt-in retained disclosure whose owner must come from the genuine immutable original source,
 * not from the canonical request or its actor. The kernel supplies its same transaction-bound
 * view only after checking the complete retained identity/body. This source-only method cannot
 * prepare/evaluate a mutation or retain the view. Null means no qualified source requirements;
 * it never proves absence or supplies substitute collector authority.
 */
public interface LocalMasterRpcRetainedResultSourceAccessProvider {
    public suspend fun rpcRetainedResultRequirements(
        profile: LocalMasterRpcWorkflowProfile,
        normalized: StoredBytes,
        view: CommandReadView,
        context: CommandContext,
    ): List<AuthorizationRequirement>?
}

/** Immutable request selection for fresh Master checks; contains no authority or bearer. */
public sealed interface LocalMasterRpcBusinessAccess {
    /** Fixed by the registered route, never decoded from request data. */
    public val profile: LocalMasterRpcWorkflowProfile

    public class Read(public val request: ReadRequest,
        override val profile: LocalMasterRpcWorkflowProfile = LocalMasterRpcWorkflowProfile.SUPPLIERS,
    ) : LocalMasterRpcBusinessAccess
    public class Execute(public val request: MutationRequest,
        override val profile: LocalMasterRpcWorkflowProfile = LocalMasterRpcWorkflowProfile.SUPPLIERS,
    ) : LocalMasterRpcBusinessAccess
    public class Lookup(public val identity: OperationIdentity,
        override val profile: LocalMasterRpcWorkflowProfile = LocalMasterRpcWorkflowProfile.SUPPLIERS,
    ) : LocalMasterRpcBusinessAccess
}

internal enum class LocalMasterRpcAccessResult { ALLOWED, DENIED, UNAVAILABLE }
