package com.ukkera.pos.app.setup

import com.ukkera.pos.security.LocalPairedDeviceCredentialJournalAction

/** Explicit non-secret LAN configuration. No network value is inferred from discovery or a default. */
public class LocalSetupLanInput(
    allowedSubnets: List<String>,
    public val interfaceName: String,
    public val localAddress: String,
    public val listenPort: Int,
    /** Explicit native credential disposition before Client transfer; null means transport only. */
    public val credentialAction: LocalPairedDeviceCredentialJournalAction? = null,
    /** Business RPC access is opt-in and requires explicit independent native custody actions. */
    public val workflow: LocalSetupLanWorkflow = LocalSetupLanWorkflow.AUTHENTICATION_ONLY,
    /** New business profiles require an explicit actor choice; supplier defaults stay compatible. */
    public val actorIntentAction: LocalPairedDeviceCredentialJournalAction? =
        if (workflow in setOf(LocalSetupLanWorkflow.WAREHOUSES, LocalSetupLanWorkflow.REPORTS,
                LocalSetupLanWorkflow.STOCK_TRANSFERS, LocalSetupLanWorkflow.KITCHEN, LocalSetupLanWorkflow.RETAIL_ORDERS,
                LocalSetupLanWorkflow.PAYMENT_CORRECTIONS, LocalSetupLanWorkflow.CUSTOMER_RECEIPTS,
                LocalSetupLanWorkflow.FOOD_CHECKS, LocalSetupLanWorkflow.FOOD_CASH_FINANCIAL,
                LocalSetupLanWorkflow.FOOD_TABLE_ASSIGNMENTS,
                LocalSetupLanWorkflow.INVOICE_CAPTURE, LocalSetupLanWorkflow.INVOICE_MATCHING)) null else credentialAction,
) {
    private val subnets: List<String> = allowedSubnets.toList()
    public val allowedSubnets: List<String> get() = subnets.toList()
}

/** Numeric transport address DATA, never admission, employee identity or permission. */
public data class LocalSetupLanEndpoint(public val address: String, public val port: Int)

public enum class LocalSetupLanRole { HOST, CLIENT }
/** A requested attachment profile, not an employee permission or a qualified connection. */
public enum class LocalSetupLanWorkflow { AUTHENTICATION_ONLY, SUPPLIERS, WAREHOUSES, REPORTS, STOCK_TRANSFERS, KITCHEN, RETAIL_ORDERS, PAYMENT_CORRECTIONS, CUSTOMER_RECEIPTS, FOOD_CHECKS, FOOD_CASH_FINANCIAL, FOOD_TABLE_ASSIGNMENTS, INVOICE_CAPTURE, INVOICE_MATCHING }
public enum class LocalSetupLanStatus { NOT_ATTACHED, STARTING, CONNECTING, LISTENING, CONNECTED, CLOSING, CLOSED, UNAVAILABLE, UNKNOWN }

/** Actual explicit observations only; factory identity readiness is not connectivity. */
public data class LocalSetupLanView(
    public val role: LocalSetupLanRole? = null,
    public val status: LocalSetupLanStatus = LocalSetupLanStatus.NOT_ATTACHED,
    public val endpoint: LocalSetupLanEndpoint? = null,
    public val hostAvailable: Boolean = false,
    public val clientAvailable: Boolean = false,
    /** Actual root-owned authentication attachment; never an employee or business grant. */
    public val authenticationAvailable: Boolean = false,
)
