package com.ukkera.pos.database

import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.AuthorizationRequirement
import kotlinx.serialization.Serializable

/** Closed application profile, not a grant inferred from an RPC connection. */
@Serializable
public enum class LocalMasterRpcWorkflowProfile {
    SUPPLIERS, WAREHOUSES, REPORTS, STOCK_TRANSFERS, KITCHEN, RETAIL_ORDERS, PAYMENT_CORRECTIONS, CUSTOMER_RECEIPTS, FOOD_CHECKS, FOOD_TABLE_ASSIGNMENTS,
    INVOICE_CAPTURE,
    INVOICE_MATCHING,
    FOOD_CASH_FINANCIAL,
    LAN_BUSINESS,
}

/** Inert exact-key routing. Shared keys use only installed canonical narrow classifiers;
 * this never prepares a request, observes storage or supplies authority. */
internal fun localMasterRpcHandlerProfile(profile: LocalMasterRpcWorkflowProfile, key: ReadKey,
    normalized: StoredBytes, handler: MasterReadHandler): LocalMasterRpcWorkflowProfile =
    if (profile != LocalMasterRpcWorkflowProfile.LAN_BUSINESS) profile else when (key) {
        ReadKey("local.configuration.read", 2) -> LocalMasterRpcWorkflowProfile.SUPPLIERS
        ReadKey("sales.payment-correction.read", 1) -> LocalMasterRpcWorkflowProfile.PAYMENT_CORRECTIONS
        ReadKey("sales.branch-report.read", 1), ReadKey("sales.branch-report-export.read", 1) -> LocalMasterRpcWorkflowProfile.REPORTS
        ReadKey("sales.customer-receipt.read", 1) -> LocalMasterRpcWorkflowProfile.CUSTOMER_RECEIPTS
        ReadKey("sales.food.table.assignment-read", 1), ReadKey("local.food.floor-read", 1) -> LocalMasterRpcWorkflowProfile.FOOD_TABLE_ASSIGNMENTS
        ReadKey("local.food.station-directory", 1), ReadKey("stock.normalized.station-profile", 2) -> LocalMasterRpcWorkflowProfile.KITCHEN
        ReadKey("sales.order.read", 1), ReadKey("sales.order.read", 2) ->
            if (handler.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.RETAIL_ORDERS, normalized)) LocalMasterRpcWorkflowProfile.RETAIL_ORDERS else profile
        ReadKey("sales.food.read", 1) -> listOf(LocalMasterRpcWorkflowProfile.KITCHEN, LocalMasterRpcWorkflowProfile.FOOD_CHECKS)
            .firstOrNull { handler.supportsRpcWorkflow(it, normalized) } ?: profile
        ReadKey("sales.food.payment.read", 1) ->
            if (handler.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL, normalized)) LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL else profile
        ReadKey("stock.items", 1), ReadKey("stock.quantities", 1), ReadKey("stock.quantity.position", 1),
        ReadKey("stock.costs", 1), ReadKey("stock.transfer", 1), ReadKey("stock.transfer.costs", 1),
        ReadKey("stock.normalized.history", 2) -> LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS
        ReadKey("stock.normalized.profile", 2) -> listOf(LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL,
            LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS).firstOrNull { handler.supportsRpcWorkflow(it, normalized) } ?: profile
        else -> profile
    }

internal fun localMasterRpcHandlerProfile(profile: LocalMasterRpcWorkflowProfile, key: CommandKey,
    normalized: StoredBytes, handler: MasterCommandHandler): LocalMasterRpcWorkflowProfile =
    if (profile != LocalMasterRpcWorkflowProfile.LAN_BUSINESS) profile else when (key) {
        CommandKey("local.configuration.write", 2) -> LocalMasterRpcWorkflowProfile.SUPPLIERS
        CommandKey("sales.payment-correction.write", 1) -> LocalMasterRpcWorkflowProfile.PAYMENT_CORRECTIONS
        CommandKey("sales.branch-report-export", 1) -> LocalMasterRpcWorkflowProfile.REPORTS
        CommandKey("sales.customer-receipt.write", 1) -> LocalMasterRpcWorkflowProfile.CUSTOMER_RECEIPTS
        CommandKey("sales.order.write", 1) -> LocalMasterRpcWorkflowProfile.RETAIL_ORDERS
        CommandKey("sales.food.table.assignment", 1) -> LocalMasterRpcWorkflowProfile.FOOD_TABLE_ASSIGNMENTS
        CommandKey("sales.food.write", 1) -> LocalMasterRpcWorkflowProfile.KITCHEN
        StockStreamingCommandsV2.COLLECTION ->
            if (handler.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.RETAIL_ORDERS, normalized)) LocalMasterRpcWorkflowProfile.RETAIL_ORDERS else profile
        StockStreamingCommandsV2.FOOD -> listOf(LocalMasterRpcWorkflowProfile.KITCHEN,
            LocalMasterRpcWorkflowProfile.FOOD_CHECKS, LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL)
            .firstOrNull { handler.supportsRpcWorkflow(it, normalized) } ?: profile
        CommandKey("sales.food.payment", 1) ->
            if (handler.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL, normalized)) LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL else profile
        StockStreamingCommandsV2.STOCK ->
            if (handler.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS, normalized)) LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS else profile
        else -> profile
    }

/**
 * Closed LAN business vocabulary. Membership is only inert route selection: each installed
 * typed handler must separately opt in to the canonical body and the Master rechecks its
 * current requirements. Physical recording, approvals and publications have separate ports.
 */
public object LocalMasterLanBusinessRpcKeysV1 {
    private val reads = setOf(
        ReadKey("local.configuration.read", 1),
        ReadKey("local.configuration.read", 2),
        ReadKey("sales.cash.read", 1),
        ReadKey("sales.cash.close.read", 1),
        ReadKey("sales.cash.count.read", 1),
        ReadKey("sales.cash.close-note.read", 1),
        ReadKey("sales.cash.safe-float.read", 1),
        ReadKey("sales.order.read", 1),
        ReadKey("sales.order.read", 2),
        ReadKey("sales.collection.read", 1),
        ReadKey("sales.collection.stock", 2),
        ReadKey("sales.financial-observation.read", 3),
        ReadKey("sales.return.read", 1),
        ReadKey("sales.food.refund.read", 1),
        ReadKey("purchasing.supplier-cash.read", 1),
        ReadKey("local.purchasing.supplier-cash-own", 1),
        ReadKey("local.purchasing.settlement-source", 1),
        ReadKey("sales.branch-report.read", 1),
        ReadKey("sales.branch-report-export.read", 1),
        ReadKey("sales.payment-correction.read", 1),
        ReadKey("sales.customer-receipt.read", 1),
        ReadKey("stock.items", 1),
        ReadKey("stock.quantities", 1),
        ReadKey("stock.quantity.position", 1),
        ReadKey("stock.costs", 1),
        ReadKey("stock.transfer", 1),
        ReadKey("stock.transfer.costs", 1),
        ReadKey("stock.normalized.profile", 2),
        ReadKey("stock.normalized.history", 2),
        ReadKey("local.food.station-directory", 1),
        ReadKey("stock.normalized.station-profile", 2),
        ReadKey("sales.food.read", 1),
        ReadKey("sales.food.check-kitchen.read", 1),
        ReadKey("sales.food.check-kitchen.read", 2),
        ReadKey("sales.food.check-kitchen.read", 3),
        ReadKey("local.food.menu", 1),
        ReadKey("sales.food.quotation.profile", 1),
        ReadKey("sales.food.selection-preview", 1),
        ReadKey("sales.food.financial.read", 1),
        ReadKey("sales.food.payment.read", 1),
        ReadKey("sales.food.table.assignment-read", 1),
        ReadKey("local.food.floor-read", 1),
        ReadKey("local.purchasing.invoice.read", 2),
        ReadKey("local.purchasing.read", 1),
        ReadKey("local.purchasing.invoice.match-recognize-source", 2),
        ReadKey("purchasing.document.read", 1),
    )
    private val commands = setOf(
        CommandKey("local.configuration.write", 1),
        CommandKey("local.configuration.write", 2),
        CommandKey("sales.cash.control", 1),
        CommandKey("sales.cash.close-note.write", 1),
        CommandKey("sales.cash.safe-float.write", 1),
        CommandKey("sales.order.write", 1),
        CommandKey("sales.branch-report-export", 1),
        CommandKey("sales.payment-correction.write", 1),
        CommandKey("sales.customer-receipt.write", 1),
        CommandKey("sales.food.write", 1),
        CommandKey("sales.collection.write", 3),
        CommandKey("sales.return.write", 3),
        CommandKey("sales.financial-supervisor", 1),
        CommandKey("sales.food.refund", 1),
        CommandKey("sales.food.refund-supervisor", 1),
        CommandKey("sales.food.payment-supervisor", 1),
        CommandKey("purchasing.supplier-cash", 1),
        CommandKey("purchasing.supplier-cash-supervisor", 1),
        CommandKey("sales.food.payment", 1),
        CommandKey("sales.food.table.assignment", 1),
        CommandKey("local.purchasing.invoice.record", 2),
        CommandKey("local.purchasing.invoice.match-recognize", 2),
        StockStreamingCommandsV2.STOCK,
        StockStreamingCommandsV2.COLLECTION,
        StockStreamingCommandsV2.FOOD,
    )
    public fun accepts(key: ReadKey): Boolean = key in reads
    public fun accepts(key: CommandKey): Boolean = key in commands
    public val readKeys: Set<ReadKey> get() = reads.toSet()
    public val commandKeys: Set<CommandKey> get() = commands.toSet()
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

/**
 * Typed disclosure from the SAME genuine original already authenticated by the kernel. This
 * additive port leaves existing result/source providers unchanged. The original and view are
 * confined to this call and its current transaction; neither may escape or be replaced by a
 * caller-supplied history, current owner or prepared mutation.
 *
 * A streamed provider must validate its complete typed producer through the original's actual
 * loan-bound reader. Traverse every relevant stream to EOF before returning requirements, with
 * bounded scalar accumulation rather than materializing history. An early matching AFTER row
 * is insufficient. Owners, targets and monetary facts must follow the immutable producer/source
 * semantics. Missing, inconsistent or unsupported evidence returns null; never prepare, evaluate
 * or reinvoke the original command. Returned nonempty requirements are checked against current
 * authority by the kernel, bound to this original operation and semantic digest.
 */
public interface LocalMasterRpcRetainedOriginalAccessProvider {
    public suspend fun rpcRetainedOriginalRequirements(
        profile: LocalMasterRpcWorkflowProfile,
        normalized: StoredBytes,
        original: RetainedOriginalV2,
        view: CommandReadView,
        context: CommandContext,
    ): List<AuthorizationRequirement>?
}

/** Separate recovery-only opt-in. This cannot admit Execute, acquire native custody,
 * publish an instruction or recreate a core Start. The ordinary typed original provider
 * must still prove the full original and current result-disclosure requirements. */
public interface LocalMasterRpcPhysicalRetainedResultAccessProvider {
    public fun supportsRpcPhysicalRetainedResult(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean
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
