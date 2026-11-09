package com.ukkera.pos.rpc

import com.ukkera.pos.database.DeferredReason
import com.ukkera.pos.database.LocalMasterClient
import com.ukkera.pos.database.LocalMasterEndpointHealth
import com.ukkera.pos.database.LocalMasterRpcWorkflowProfile
import com.ukkera.pos.database.LocalMasterRpcBusinessAccess
import com.ukkera.pos.database.LocalMasterReportRpcKeysV1
import com.ukkera.pos.database.LocalMasterStockTransferRpcKeysV1
import com.ukkera.pos.database.LocalMasterKitchenRpcKeysV1
import com.ukkera.pos.database.LocalMasterRetailOrderRpcKeysV1
import com.ukkera.pos.database.LocalMasterPaymentCorrectionRpcKeysV1
import com.ukkera.pos.database.LocalMasterCustomerReceiptRpcKeysV1
import com.ukkera.pos.database.LocalMasterFoodCheckRpcKeysV1
import com.ukkera.pos.database.LocalMasterFoodCashFinancialRpcKeysV1
import com.ukkera.pos.database.LocalMasterFoodTableAssignmentRpcKeysV1
import com.ukkera.pos.database.LocalMasterInvoiceCaptureRpcKeysV1
import com.ukkera.pos.database.LocalMasterInvoiceMatchingRpcKeysV1
import com.ukkera.pos.database.LookupReply
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadFailure
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.LocalAuthenticationResult
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.LocalSessionViewResult
import com.ukkera.pos.security.LocalSignOutResult
import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Real fixed directory RPC on one retained selected-original Client. The immutable profile
 * selects fixed business routes; the Master independently checks every body and current grant.
 * This is not a full POS or physical capability.
 */
public class LocalRpcRemoteDirectoryClient internal constructor(
    private val owner: LocalRpcOriginalClientOwner,
    internal val authentication: LocalRpcRemoteAuthenticationService,
    public val profile: LocalMasterRpcWorkflowProfile,
) : LocalMasterClient {
    public val connectionId: DomainId get() = authentication.connectionId
    private val state = MutableStateFlow(LocalMasterEndpointHealth.ACTIVE)
    override public val health: StateFlow<LocalMasterEndpointHealth> = state.asStateFlow()
    public fun isCurrent(): Boolean = state.value == LocalMasterEndpointHealth.ACTIVE && owner.directoryClientCurrent(this)
    internal fun deny() { state.value = LocalMasterEndpointHealth.RETIRED }

    override public suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier,
        password: CharArray, explicitDeviceMove: Boolean): LocalAuthenticationResult =
        authentication.signIn(connectionId, login, password, explicitDeviceMove)
    override public suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult = authentication.refresh(credentials)
    override public suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult = authentication.current(credentials)
    override public suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult = authentication.logout(credentials)

    override public suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        currentCoroutineContext().ensureActive()
        // An unsupported key cannot escape a closed workflow adapter. This is a
        // capability check, not authorization: the Master still validates the complete request.
        if (profile == LocalMasterRpcWorkflowProfile.REPORTS && !LocalMasterReportRpcKeysV1.accepts(request.query) ||
            profile == LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS && !LocalMasterStockTransferRpcKeysV1.accepts(request.query) ||
            profile == LocalMasterRpcWorkflowProfile.KITCHEN && !LocalMasterKitchenRpcKeysV1.accepts(request.query) ||
            profile == LocalMasterRpcWorkflowProfile.RETAIL_ORDERS && !LocalMasterRetailOrderRpcKeysV1.accepts(request.query) ||
            profile == LocalMasterRpcWorkflowProfile.PAYMENT_CORRECTIONS && !LocalMasterPaymentCorrectionRpcKeysV1.accepts(request.query) ||
            profile == LocalMasterRpcWorkflowProfile.CUSTOMER_RECEIPTS && !LocalMasterCustomerReceiptRpcKeysV1.accepts(request.query) ||
            profile == LocalMasterRpcWorkflowProfile.FOOD_CHECKS && !LocalMasterFoodCheckRpcKeysV1.accepts(request.query) ||
            profile == LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL && !LocalMasterFoodCashFinancialRpcKeysV1.accepts(request.query) ||
            profile == LocalMasterRpcWorkflowProfile.FOOD_TABLE_ASSIGNMENTS && !LocalMasterFoodTableAssignmentRpcKeysV1.accepts(request.query) ||
            profile == LocalMasterRpcWorkflowProfile.INVOICE_CAPTURE && !LocalMasterInvoiceCaptureRpcKeysV1.accepts(request.query) ||
            profile == LocalMasterRpcWorkflowProfile.INVOICE_MATCHING && !LocalMasterInvoiceMatchingRpcKeysV1.accepts(request.query))
            return ReadReply.Unavailable(ReadFailure.UNKNOWN_QUERY)
        return owner.directoryExchange(this, credentials, LocalMasterRpcBusinessAccess.Read(request, profile),
            { ReadReply.Unavailable(ReadFailure.STORAGE_UNCERTAIN) },
            { ReadReply.Unavailable(ReadFailure.SERVICE_BUSY) }) { bridge, actual, actor -> bridge.read(actual, request, actor) }
    }

    override public suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        currentCoroutineContext().ensureActive()
        if (profile == LocalMasterRpcWorkflowProfile.REPORTS && !LocalMasterReportRpcKeysV1.accepts(request.command) ||
            profile == LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS && !LocalMasterStockTransferRpcKeysV1.accepts(request.command) ||
            profile == LocalMasterRpcWorkflowProfile.KITCHEN && !LocalMasterKitchenRpcKeysV1.accepts(request.command) ||
            profile == LocalMasterRpcWorkflowProfile.RETAIL_ORDERS && !LocalMasterRetailOrderRpcKeysV1.accepts(request.command) ||
            profile == LocalMasterRpcWorkflowProfile.PAYMENT_CORRECTIONS && !LocalMasterPaymentCorrectionRpcKeysV1.accepts(request.command) ||
            profile == LocalMasterRpcWorkflowProfile.CUSTOMER_RECEIPTS && !LocalMasterCustomerReceiptRpcKeysV1.accepts(request.command) ||
            profile == LocalMasterRpcWorkflowProfile.FOOD_CHECKS && !LocalMasterFoodCheckRpcKeysV1.accepts(request.command) ||
            profile == LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL && !LocalMasterFoodCashFinancialRpcKeysV1.accepts(request.command) ||
            profile == LocalMasterRpcWorkflowProfile.FOOD_TABLE_ASSIGNMENTS && !LocalMasterFoodTableAssignmentRpcKeysV1.accepts(request.command) ||
            profile == LocalMasterRpcWorkflowProfile.INVOICE_CAPTURE && !LocalMasterInvoiceCaptureRpcKeysV1.accepts(request.command) ||
            profile == LocalMasterRpcWorkflowProfile.INVOICE_MATCHING && !LocalMasterInvoiceMatchingRpcKeysV1.accepts(request.command))
            return MutationReply.Deferred(request.operationId, DeferredReason.UNKNOWN_COMMAND)
        return owner.directoryExchange(this, credentials, LocalMasterRpcBusinessAccess.Execute(request, profile),
            { MutationReply.Deferred(request.operationId, DeferredReason.STORAGE_UNCERTAIN) },
            { MutationReply.Deferred(request.operationId, DeferredReason.SERVICE_BUSY) }) { bridge, actual, actor -> bridge.execute(actual, request, actor) }
    }

    override public suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply =
        owner.directoryExchange(this, credentials, LocalMasterRpcBusinessAccess.Lookup(identity, profile),
            { LookupReply.Deferred(identity.operationId, DeferredReason.STORAGE_UNCERTAIN) },
            { LookupReply.Deferred(identity.operationId, DeferredReason.SERVICE_BUSY) }) { bridge, actual, actor -> bridge.lookup(actual, identity, actor) }

    override fun toString(): String = "LocalRpcRemoteDirectoryClient([REDACTED])"
}

/** Source compatibility; supplier callers still use the single profile-fixed directory implementation. */
public typealias LocalRpcRemoteSupplierClient = LocalRpcRemoteDirectoryClient
