package com.ukkera.pos.app.gateway.setup

import com.ukkera.pos.app.setup.LocalSetupLanEndpoint
import com.ukkera.pos.app.BranchUiGateway
import com.ukkera.pos.security.LocalPairedDeviceCredentialJournalAction
import com.ukkera.pos.app.setup.LocalSetupLanInput
import com.ukkera.pos.app.setup.LocalSetupLanWorkflow
import com.ukkera.pos.database.LocalMasterRpcWorkflowProfile
import dev.p2pkit.rpc.RpcEndpoint
import dev.p2pkit.transport.lan.OrganizationLan

/** Uses the official LAN policy parser. Configuration DATA is not network or user authority. */
public class LocalSetupLanSettings private constructor(public val lan: OrganizationLan,
    public val credentialAction: LocalPairedDeviceCredentialJournalAction?,
    public val workflow: LocalSetupLanWorkflow,
    public val actorIntentAction: LocalPairedDeviceCredentialJournalAction?) {
    /** Exact immutable selection; semantically similar input cannot silently retarget a lifetime. */
    public fun sameSelection(other: LocalSetupLanSettings): Boolean =
        lan.allowedSubnets == other.lan.allowedSubnets && lan.interfaceName == other.lan.interfaceName &&
            lan.localAddress == other.lan.localAddress && lan.listenPort == other.lan.listenPort &&
            credentialAction == other.credentialAction && workflow == other.workflow &&
            actorIntentAction == other.actorIntentAction

    public companion object {
        public fun from(input: LocalSetupLanInput): LocalSetupLanSettings {
            require(input.workflow == LocalSetupLanWorkflow.AUTHENTICATION_ONLY ||
                input.credentialAction != null && input.actorIntentAction != null) {
                "A business RPC workflow requires explicit device and actor custody actions"
            }
            return LocalSetupLanSettings(
                OrganizationLan(input.allowedSubnets, input.interfaceName, input.localAddress, input.listenPort),
                input.credentialAction, input.workflow, input.actorIntentAction,
            )
        }
    }
}

/** Apply the same strict private-address/subnet policy to the explicitly selected Host. */
public fun LocalSetupLanSettings.validateEndpoint(endpoint: LocalSetupLanEndpoint): RpcEndpoint {
    require(lan.allows(endpoint.address)) { "The Host endpoint must be in the approved private LAN" }
    return RpcEndpoint(endpoint.address, endpoint.port)
}

/** Exact native owners retained before SDK work. No SDK role/security capability escapes. */
public interface LocalSetupLanHost {
    public suspend fun start(): LocalSetupLanEndpoint
    public suspend fun endpoint(): LocalSetupLanEndpoint
    public suspend fun close()
}

/** Native-owned real authentication gateway, retained independently of UI waiting. */
public interface LocalSetupLanAuthentication {
    public val gateway: BranchUiGateway
    public fun isCurrent(): Boolean
    public fun denyNewWork()
}

public interface LocalSetupLanClient {
    public suspend fun connect(endpoint: LocalSetupLanEndpoint)
    /** Same original owner only; absent explicit custody never creates a fallback. */
    public suspend fun authentication(): LocalSetupLanAuthentication? = null
    public suspend fun close()
}

/** The Master RPC workflow profile a selected business LAN workflow requests; null for authentication only. */
public fun LocalSetupLanWorkflow.masterRpcProfile(): LocalMasterRpcWorkflowProfile? = when (this) {
    LocalSetupLanWorkflow.AUTHENTICATION_ONLY -> null
    LocalSetupLanWorkflow.SUPPLIERS -> LocalMasterRpcWorkflowProfile.SUPPLIERS
    LocalSetupLanWorkflow.WAREHOUSES -> LocalMasterRpcWorkflowProfile.WAREHOUSES
    LocalSetupLanWorkflow.REPORTS -> LocalMasterRpcWorkflowProfile.REPORTS
    LocalSetupLanWorkflow.STOCK_TRANSFERS -> LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS
    LocalSetupLanWorkflow.KITCHEN -> LocalMasterRpcWorkflowProfile.KITCHEN
    LocalSetupLanWorkflow.RETAIL_ORDERS -> LocalMasterRpcWorkflowProfile.RETAIL_ORDERS
    LocalSetupLanWorkflow.PAYMENT_CORRECTIONS -> LocalMasterRpcWorkflowProfile.PAYMENT_CORRECTIONS
    LocalSetupLanWorkflow.CUSTOMER_RECEIPTS -> LocalMasterRpcWorkflowProfile.CUSTOMER_RECEIPTS
    LocalSetupLanWorkflow.FOOD_CHECKS -> LocalMasterRpcWorkflowProfile.FOOD_CHECKS
    LocalSetupLanWorkflow.FOOD_CASH_FINANCIAL -> LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL
    LocalSetupLanWorkflow.FOOD_TABLE_ASSIGNMENTS -> LocalMasterRpcWorkflowProfile.FOOD_TABLE_ASSIGNMENTS
    LocalSetupLanWorkflow.INVOICE_CAPTURE -> LocalMasterRpcWorkflowProfile.INVOICE_CAPTURE
    LocalSetupLanWorkflow.INVOICE_MATCHING -> LocalMasterRpcWorkflowProfile.INVOICE_MATCHING
}
