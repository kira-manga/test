package com.ukkera.pos.sales.store

import com.ukkera.pos.database.LocalMasterEndpoint
import com.ukkera.pos.database.LookupReply
import com.ukkera.pos.database.MasterCommandHandler
import com.ukkera.pos.database.MasterReadHandler
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.inventory.store.LocalStockCompositeVerifier
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.inventory.store.LocalStockAuthorityReader
import com.ukkera.pos.inventory.store.LocalStockRelationalCompositeVerifier
import com.ukkera.pos.database.StockStreamingCommandsV2
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId

/** The caller durably stages these exact bytes and ID BEFORE dispatch. No random/retry-ID policy lives here. */
public object LocalSalesCommands {
    /** Explicit new route only; callers retain the exact original ID/bytes for lookup/retry. */
    public fun normalizedStock(operationId: OperationId, request: LocalCollectionStockRequestV2): MutationRequest =
        LocalCollectionStockCodecV2.command(operationId, request)

    public fun cash(operationId: OperationId, request: LocalCashRequestV1, actionApprovalId: DomainId? = null): MutationRequest =
        MutationRequest(operationId, LocalSalesStoreProfile.CASH_COMMAND, LocalCashCommandCodec.encode(request), actionApprovalId)
    public fun order(operationId: OperationId, request: LocalOrderRequestV1, actionApprovalId: DomainId? = null): MutationRequest =
        MutationRequest(operationId, LocalSalesStoreProfile.ORDER_COMMAND, LocalOrderCommandCodec.encode(request), actionApprovalId)
    public fun collection(operationId: OperationId, request: LocalCollectionRequestV1, actionApprovalId: DomainId? = null): MutationRequest =
        MutationRequest(operationId, LocalSalesStoreProfile.COLLECTION_COMMAND, LocalCollectionCommandCodec.encode(request), actionApprovalId)
    public fun returns(operationId: OperationId, request: LocalReturnRequestV1, actionApprovalId: DomainId? = null): MutationRequest =
        MutationRequest(operationId, LocalSalesStoreProfile.RETURN_COMMAND, LocalReturnCommandCodec.encode(request), actionApprovalId)
    public fun collection(operationId: OperationId, request: LocalCollectionRequestV2, actionApprovalId: DomainId? = null): MutationRequest =
        MutationRequest(operationId, LocalSalesStoreProfile.COLLECTION_COMMAND_V2, LocalCollectionCommandCodecV2.encode(request), actionApprovalId)
    public fun returns(operationId: OperationId, request: LocalReturnRequestV2, actionApprovalId: DomainId? = null): MutationRequest =
        MutationRequest(operationId, LocalSalesStoreProfile.RETURN_COMMAND_V2, LocalReturnCommandCodecV2.encode(request), actionApprovalId)
    public fun returnCase(operationId: OperationId, request: LocalReturnCaseRequestV1, actionApprovalId: DomainId? = null): MutationRequest =
        LocalReturnCaseCommandCodecV1.command(operationId, request, actionApprovalId)

    public fun customerReceipt(operationId: OperationId, request: LocalRetailCustomerReceiptRequestV1): MutationRequest =
        LocalRetailCustomerReceiptCodec.command(operationId, request)

    public fun paymentCorrection(operationId: OperationId, request: LocalPaymentCorrectionRequestV1,
        actionApprovalId: DomainId? = null): MutationRequest =
        MutationRequest(operationId, LocalPaymentCorrectionCodec.COMMAND, LocalPaymentCorrectionCodec.encode(request), actionApprovalId)
}

/**
 * Trusted process wiring only. Install the returned stock verifier in the SAME repository used
 * by activated quote sources and collection commands. This creates no database, employees,
 * grants, catalog, runtime/capacity proof or fixture. Do not install legacy raw cash writers too.
 */
public object LocalSalesHandlers {
    public fun stockCompositeVerifier(
        tenderPolicies: HistoricalSalesTenderPolicySourceV1 = UnavailableHistoricalSalesTenderPolicySourceV1,
    ): LocalStockCompositeVerifier = LocalCollectionCompositeVerifier(tenderPolicies)

    /** Install these at construction of the SAME LocalStockRepository used below and by reads. */
    public fun normalizedStockCompositeVerifiers(quotes: TransactionSalesStockQuoteSource,
        recipes: TransactionFoodStockSource,
        tenderPolicies: HistoricalSalesTenderPolicySourceV1 = UnavailableHistoricalSalesTenderPolicySourceV1): List<LocalStockRelationalCompositeVerifier> = listOf(
        LocalCollectionCompositeVerifier.normalized(quotes, tenderPolicies), LocalFoodStockServices.producer(recipes), LocalReturnStockProducer())

    /** No gateway/registry activation, writer right or physical monetary admission is supplied. */
    public fun normalizedStockCommands(stock: LocalStockRepository, authority: LocalStockAuthorityReader,
        quotes: TransactionSalesStockQuoteSource, recipes: TransactionFoodStockSource,
        tenderPolicies: HistoricalSalesTenderPolicySourceV1 = UnavailableHistoricalSalesTenderPolicySourceV1,
        foodCheckRefusals: LocalFoodCheckRefusalSupport? = null): List<MasterCommandHandler> = listOf(
        LocalCollectionStockHandler(stock, authority, quotes, tenderPolicies), LocalFoodStockServices.handler(stock, authority, recipes, foodCheckRefusals),
        LocalReturnStockHandler(stock, authority))

    /** Recipient discovery and responsibility use the same original-stock proof as order reads. */
    public fun foodHandoffReads(stock: LocalStockRepository,
        tenderPolicies: HistoricalSalesTenderPolicySourceV1 = UnavailableHistoricalSalesTenderPolicySourceV1): List<MasterReadHandler> = listOf(
        LocalFoodHandoffDirectoryReadHandler(), LocalFoodHandoffResponsibilityReadHandler(LocalFoodHandoffPrecollection))
        .map { CommerceReadHandler(it, stock, tenderPolicies) }

    public fun commands(stock: LocalStockRepository, quoteSource: TransactionSalesQuoteSource,
        tenderSource: TransactionSalesTenderSource, returnPolicy: TransactionSalesReturnPolicySource,
        recordingCapacity: TransactionSalesRecordingCapacity,
        physicalRecordingCapacity: TransactionSalesPhysicalRecordingCapacityV2 = UnavailableSalesPhysicalRecordingCapacityV2,
        returnCaseSource: TransactionSalesReturnCaseSourceV1 = UnavailableSalesReturnCaseSourceV1,
        tenderPolicies: HistoricalSalesTenderPolicySourceV1 = UnavailableHistoricalSalesTenderPolicySourceV1): List<MasterCommandHandler> = listOf(
        LocalCashHandler(), LocalCashCloseNoteHandler(), LocalOrderHandler(quoteSource), LocalCollectionHandler(stock, tenderSource, recordingCapacity),
        CommerceCommandHandler(LocalReturnHandler(returnPolicy, recordingCapacity), stock, tenderPolicies),
        LocalHistoricalCollectionHandler(stock, tenderSource, physicalRecordingCapacity),
        CommerceCommandHandler(LocalHistoricalReturnHandler(returnPolicy, physicalRecordingCapacity), stock, tenderPolicies),
        CommerceCommandHandler(LocalReturnCaseHandler(returnCaseSource), stock, tenderPolicies),
        CommerceCommandHandler(LocalRetailCustomerReceiptHandler(), stock, tenderPolicies), LocalBranchReportExportHandler(),
        LocalPaymentCorrectionHandler(stock, tenderSource, tenderPolicies))

    public fun reads(stock: LocalStockRepository, foodRecipes: TransactionFoodRecipeSourceV1? = null,
        tenderPolicies: HistoricalSalesTenderPolicySourceV1 = UnavailableHistoricalSalesTenderPolicySourceV1): List<MasterReadHandler> =
        listOf(LocalCashReadHandler(), LocalCashCloseReadHandler(), LocalCashCountReadHandler(), LocalCashCloseNotesReadHandler(), LocalOrderReadHandler(), LocalOrderReadHandler.withStockProfile(), LocalManagerOrderReviewReadHandler(), LocalManagerOriginalQuoteReadHandler(), LocalManagerReassignmentReadHandler(), LocalCollectionReadHandler(stock), LocalReturnReadHandler(),
            LocalHistoricalFinancialReadHandler(), LocalCollectionStockReadHandler(stock), LocalReturnStockReadHandler(stock), LocalReturnCaseReadHandler(),
            LocalRetailCustomerReceiptReadHandler(), LocalBranchReportReadHandler(stock, foodRecipes), LocalBranchReportExportReadHandler(),
            LocalPaymentCorrectionReadHandler(stock, tenderPolicies))
            .map { CommerceReadHandler(it, stock, tenderPolicies) }
}

/**
 * Thin real supervised-ingress facade, not an alternate auth/cache/checkout engine. Deferred
 * responses and cancelled/lost callers retain the ORIGINAL operation identity for lookup.
 * Only a committed Recorded reply is an applied operation; a Held result is not sale success.
 */
public class LocalSalesService(private val endpoint: LocalMasterEndpoint) {
    public suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        require(request.command in setOf(LocalSalesStoreProfile.CASH_COMMAND, LocalSalesStoreProfile.ORDER_COMMAND,
            LocalSalesStoreProfile.COLLECTION_COMMAND, LocalSalesStoreProfile.RETURN_COMMAND,
            LocalSalesStoreProfile.COLLECTION_COMMAND_V2, LocalSalesStoreProfile.RETURN_COMMAND_V2,
            LocalReturnCaseProfileV1.COMMAND, LocalRetailCustomerReceiptCodec.COMMAND,
            LocalBranchReportExportCodecV1.COMMAND, LocalCashCloseNoteCodec.COMMAND, StockStreamingCommandsV2.COLLECTION,
            StockStreamingCommandsV2.RETURN, LocalPaymentCorrectionCodec.COMMAND)) { "sales.command.unsupported" }
        return endpoint.execute(credentials, request)
    }
    public suspend fun lookup(credentials: LocalGatewayCredentials, originalIdentity: OperationIdentity): LookupReply =
        endpoint.lookup(credentials, originalIdentity)
    public suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        require(request.query in setOf(LocalCashReadCodec.QUERY, LocalCashCloseReadCodec.QUERY, LocalCashCountReadCodec.QUERY, LocalCashCloseNoteCodec.QUERY, LocalOrderReadCodec.QUERY, LocalOrderReadCodecV2.QUERY, LocalManagerOrderReviewCodec.QUERY, LocalManagerOriginalQuoteCodec.QUERY, LocalManagerReassignmentCodec.QUERY,
            LocalCollectionReadCodec.QUERY, LocalReturnReadCodec.QUERY, LocalFinancialReadCodecV2.QUERY,
            LocalCollectionStockReadCodecV2.QUERY, LocalReturnStockProfileV1.QUERY, LocalReturnCaseReadCodecV1.QUERY, LocalRetailCustomerReceiptReadCodec.QUERY,
            LocalBranchReportReadCodec.QUERY, LocalBranchReportExportCodecV1.QUERY,
            LocalPaymentCorrectionCodec.QUERY)) { "sales.query.unsupported" }
        return endpoint.read(credentials, request)
    }
}
