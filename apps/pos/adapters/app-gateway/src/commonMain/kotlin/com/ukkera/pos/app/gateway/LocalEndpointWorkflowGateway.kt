package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.LocalAcknowledgementResult
import com.ukkera.pos.app.LocalBranchReportExportHistory
import com.ukkera.pos.app.LocalBranchReportExportLifetime
import com.ukkera.pos.app.LocalBranchReportExportProgress
import com.ukkera.pos.app.LocalBranchReportExportResult
import com.ukkera.pos.app.LocalBranchReportExportSelection
import com.ukkera.pos.app.LocalBranchReportFileDelivery
import com.ukkera.pos.app.LocalCommandIntent
import com.ukkera.pos.app.LocalFoodPreviewInput
import com.ukkera.pos.app.LocalFoodPricePreviewResult
import com.ukkera.pos.app.LocalOperationResult
import com.ukkera.pos.app.LocalPendingOperation
import com.ukkera.pos.app.LocalPendingPhase
import com.ukkera.pos.app.LocalPendingResult
import com.ukkera.pos.app.LocalReadResult
import com.ukkera.pos.app.LocalReferenceQuery
import com.ukkera.pos.app.LocalReferenceResult
import com.ukkera.pos.app.LocalRecordedDecision
import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.app.LocalStageResult
import com.ukkera.pos.app.LocalWorkflowGateway
import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.inventory.store.LocalStockRelationalProfileV2
import com.ukkera.pos.security.sha256
import com.ukkera.pos.security.LocalAuthenticationService
import com.ukkera.pos.app.LocalWorkflowQuery
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.DeferredReason
import com.ukkera.pos.database.LocalMasterClient
import com.ukkera.pos.database.LocalMasterClientResultAcceptance
import com.ukkera.pos.database.LocalMasterRpcWorkflowProfile
import com.ukkera.pos.database.LocalMasterApprovalClient
import com.ukkera.pos.database.LookupReply
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadFailure
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.sales.store.LocalPaymentCorrectionCodec
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptCodec
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptReadCodec
import com.ukkera.pos.sales.store.LocalReturnCaseProfileV1
import com.ukkera.pos.sales.store.LocalReturnStockProfileV1
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.LocalSessionView
import com.ukkera.pos.security.LocalSessionViewResult
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.sales.store.LocalBranchReportExportCodecV1
import com.ukkera.pos.sales.store.LocalBranchReportReadCodec
import com.ukkera.pos.sales.store.LocalFoodTableAssignmentProfileV1
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex

/** An owned credential snapshot bound to the SAME interactive session lifetime. */
public class LocalWorkflowCredentialLease internal constructor(
    public val credentials: LocalGatewayCredentials,
    private val currentLifetime: () -> Boolean,
    private val retireOriginalSession: () -> Unit,
    private val originalSession: LocalWorkflowSessionAssociation?,
) {
    public constructor(credentials: LocalGatewayCredentials, currentLifetime: () -> Boolean) :
        this(credentials, currentLifetime, {}, null)

    private val terminalObserved = MutableStateFlow(false)
    public fun isCurrent(): Boolean = currentLifetime()
    public fun destroy(): Unit = credentials.bearer.destroy()

    /** Denial or conflicting session metadata ends only the original installed attachment.
     * This negative signal precedes caller cancellation checks. Scoped operation refusals and
     * policy-only changes never substitute for a session denial. */
    internal suspend fun observeSession(endpoint: LocalAuthenticationService): LocalSessionViewResult {
        val observation = endpoint.current(credentials)
        val conflictingSession = observation is LocalSessionViewResult.Current &&
            originalSession?.matches(observation.view) == false
        if ((observation == LocalSessionViewResult.Denied || conflictingSession) &&
            terminalObserved.compareAndSet(false, true)) {
            try { retireOriginalSession() } catch (_: Exception) {
                // The installed owner fences its lifecycle in finally. Exact failed negative
                // holders remain in that owner's existing retry custody; this is not close.
            }
        }
        if (conflictingSession) throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
        return observation
    }

    override fun toString(): String = "LocalWorkflowCredentialLease([REDACTED])"
}

/** Immutable nonsecret association, copied before the journal snapshot destroys its tokens.
 * Policy revision and resource grants can change within the same original session. */
internal class LocalWorkflowSessionAssociation(row: StoredClientSessionV1) {
    private val identity = row.identity
    private val login = row.login
    private val sessionId = row.sessionId
    private val generation = row.generation
    private val accessExpiresAt = row.accessExpiresAt
    private val workExpiresAt = row.workExpiresAt

    fun matches(view: LocalSessionView): Boolean =
        identity == LocalClientIdentity(view.scope, view.actorId, view.deviceId) &&
            login == view.login && sessionId == view.sessionId && generation == view.generation &&
            accessExpiresAt == view.accessExpiresAt && workExpiresAt == view.workExpiresAt &&
            view.policy.actorId == identity.actorId && view.policy.scope == identity.scope

    override fun toString(): String = "LocalWorkflowSessionAssociation([REDACTED])"
}

/** The root must not return a login for a different actor or retain a lease after lock/close. */
public fun interface LocalWorkflowCredentialSource {
    public suspend fun acquire(): LocalWorkflowCredentialLease?
}

/** Actual composition registry hints only. This cannot install a handler or authorize a command. */
public class LocalWorkflowInstallation private constructor(commands: Set<CommandKey>, reads: Set<ReadKey>,
    internal val directoryProfile: LocalMasterRpcWorkflowProfile?) {
    /** Existing full-Master roots retain their original registry-key semantics. */
    public constructor(commands: Set<CommandKey>, reads: Set<ReadKey>) : this(commands, reads, null)

    private val commandKeys: Set<CommandKey> = commands.toSet()
    private val readKeys: Set<ReadKey> = reads.toSet()
    init { require(commandKeys.size <= 128 && readKeys.size <= 128) }
    internal val isRestricted: Boolean get() = directoryProfile != null
    internal val isSupplierOnly: Boolean get() = directoryProfile == LocalMasterRpcWorkflowProfile.SUPPLIERS
    internal val isWarehouseOnly: Boolean get() = directoryProfile == LocalMasterRpcWorkflowProfile.WAREHOUSES
    internal val isReportsOnly: Boolean get() = directoryProfile == LocalMasterRpcWorkflowProfile.REPORTS
    internal val isStockTransfersOnly: Boolean get() = directoryProfile == LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS
    internal val isKitchenOnly: Boolean get() = directoryProfile == LocalMasterRpcWorkflowProfile.KITCHEN
    internal val isFoodChecksOnly: Boolean get() = directoryProfile == LocalMasterRpcWorkflowProfile.FOOD_CHECKS
    internal val isFoodCashFinancialOnly: Boolean get() = directoryProfile == LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL
    internal val isFoodTableAssignmentsOnly: Boolean get() = directoryProfile == LocalMasterRpcWorkflowProfile.FOOD_TABLE_ASSIGNMENTS
    internal val isRetailOrdersOnly: Boolean get() = directoryProfile == LocalMasterRpcWorkflowProfile.RETAIL_ORDERS
    internal val isPaymentCorrectionsOnly: Boolean get() = directoryProfile == LocalMasterRpcWorkflowProfile.PAYMENT_CORRECTIONS
    internal val isCustomerReceiptsOnly: Boolean get() = directoryProfile == LocalMasterRpcWorkflowProfile.CUSTOMER_RECEIPTS
    internal val isInvoiceCaptureOnly: Boolean get() = directoryProfile == LocalMasterRpcWorkflowProfile.INVOICE_CAPTURE
    internal val isInvoiceMatchingOnly: Boolean get() = directoryProfile == LocalMasterRpcWorkflowProfile.INVOICE_MATCHING
    internal val isDirectoryOnly: Boolean get() = isSupplierOnly || isWarehouseOnly
    internal val directoryKind: String? get() = when (directoryProfile) {
        LocalMasterRpcWorkflowProfile.SUPPLIERS -> LocalConfigurationProfile.SUPPLIER
        LocalMasterRpcWorkflowProfile.WAREHOUSES -> LocalConfigurationProfile.WAREHOUSE
        LocalMasterRpcWorkflowProfile.REPORTS, LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS,
        LocalMasterRpcWorkflowProfile.KITCHEN, LocalMasterRpcWorkflowProfile.FOOD_CHECKS,
        LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL,
        LocalMasterRpcWorkflowProfile.FOOD_TABLE_ASSIGNMENTS, LocalMasterRpcWorkflowProfile.RETAIL_ORDERS,
        LocalMasterRpcWorkflowProfile.PAYMENT_CORRECTIONS, LocalMasterRpcWorkflowProfile.CUSTOMER_RECEIPTS,
        LocalMasterRpcWorkflowProfile.INVOICE_CAPTURE, LocalMasterRpcWorkflowProfile.INVOICE_MATCHING, null -> null
    }
    internal fun has(command: CommandKey): Boolean = command in commandKeys
    internal fun has(read: ReadKey): Boolean = read in readKeys
    internal fun allows(action: LocalAction): Boolean = when (directoryProfile) {
        LocalMasterRpcWorkflowProfile.SUPPLIERS -> action in supplierConfigurationActions
        LocalMasterRpcWorkflowProfile.WAREHOUSES -> action == LocalAction.WAREHOUSE_CONFIGURE
        LocalMasterRpcWorkflowProfile.REPORTS -> false // Export has its own typed native audit workflow, not a generic action form.
        LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS -> action == LocalAction.WAREHOUSE_TRANSFER
        LocalMasterRpcWorkflowProfile.KITCHEN -> action in kitchenStationActions
        LocalMasterRpcWorkflowProfile.FOOD_CHECKS -> action in foodCheckActions
        LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL -> action in foodCashFinancialActions
        LocalMasterRpcWorkflowProfile.FOOD_TABLE_ASSIGNMENTS -> action == LocalAction.FOOD_ASSIGN_TABLE
        LocalMasterRpcWorkflowProfile.RETAIL_ORDERS -> action in retailOrderActions
        LocalMasterRpcWorkflowProfile.PAYMENT_CORRECTIONS -> action == LocalAction.PAYMENT_CLASSIFICATION_CORRECT
        LocalMasterRpcWorkflowProfile.CUSTOMER_RECEIPTS -> action == LocalAction.RETAIL_CUSTOMER_RECEIPT
        LocalMasterRpcWorkflowProfile.INVOICE_CAPTURE -> action == LocalAction.SUPPLIER_INVOICE_CAPTURE
        LocalMasterRpcWorkflowProfile.INVOICE_MATCHING -> action == LocalAction.SUPPLIER_INVOICE_MATCH_RECOGNIZE
        null -> true
    }

    internal fun requireWorkflow(query: LocalWorkflowQuery) {
        if (!isRestricted) return
        if (isInvoiceCaptureOnly || isInvoiceMatchingOnly) {
            requireInvoiceReadWorkflow(query)
            return
        }
        if (isCustomerReceiptsOnly) {
            requireCustomerReceiptWorkflow(query)
            return
        }
        if (isPaymentCorrectionsOnly) {
            requirePaymentCorrectionWorkflow(query)
            return
        }
        if (isRetailOrdersOnly) {
            requireRetailOrderWorkflow(query)
            return
        }
        if (isKitchenOnly) {
            requireKitchenWorkflow(query)
            return
        }
        if (isFoodCashFinancialOnly) {
            requireFoodCashFinancialWorkflow(query)
            return
        }
        if (isFoodChecksOnly) {
            requireFoodCheckWorkflow(query)
            return
        }
        if (isFoodTableAssignmentsOnly) {
            requireFoodTableAssignmentWorkflow(query)
            return
        }
        if (isStockTransfersOnly) {
            requireStockTransferWorkflow(query)
            return
        }
        if (isReportsOnly) {
            if (query.workflow != LocalWorkflow.REPORTS || query.branchReport == null || query.search.isNotEmpty() ||
                query.reference != null || query.cursor != null || query.pricePreview != null || query.purchasing != null ||
                query.food != null || query.returns != null || query.cash != null || query.inventory != null ||
                query.openingAllocations != null) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
            return
        }
        val purchasing = query.purchasing
        val selected = when (directoryProfile) {
            LocalMasterRpcWorkflowProfile.SUPPLIERS -> query.workflow == LocalWorkflow.PURCHASING && purchasing != null &&
                purchasing.section == LocalPurchasingSection.SUPPLIERS && !purchasing.includeCosts
            LocalMasterRpcWorkflowProfile.WAREHOUSES -> query.workflow == LocalWorkflow.SETUP && purchasing == null
            LocalMasterRpcWorkflowProfile.REPORTS, LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS,
            LocalMasterRpcWorkflowProfile.KITCHEN, LocalMasterRpcWorkflowProfile.FOOD_CHECKS,
            LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL,
            LocalMasterRpcWorkflowProfile.FOOD_TABLE_ASSIGNMENTS, LocalMasterRpcWorkflowProfile.RETAIL_ORDERS,
            LocalMasterRpcWorkflowProfile.PAYMENT_CORRECTIONS, LocalMasterRpcWorkflowProfile.CUSTOMER_RECEIPTS,
            LocalMasterRpcWorkflowProfile.INVOICE_CAPTURE, LocalMasterRpcWorkflowProfile.INVOICE_MATCHING, null -> false
        }
        if (!selected || query.reference?.let { configurationKind(it.kind) != directoryKind || it.parentId != null } == true ||
            query.pricePreview != null || query.branchReport != null || query.food != null || query.returns != null ||
            query.cash != null || query.inventory != null || query.openingAllocations != null)
            throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
        if (query.reference != null && query.cursor != null) invalid()
        query.cursor?.let { cursor ->
            val parts = cursor.split('|')
            if (parts.size != 3 || parts[0] != "cfg") invalid()
            if (parts[1] != directoryKind) invalid()
        }
    }

    internal fun requireReferences(query: LocalReferenceQuery) {
        if (isInvoiceMatchingOnly) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
        if (isInvoiceCaptureOnly) {
            requireInvoiceCaptureReferences(query)
            return
        }
        if (isCustomerReceiptsOnly) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
        if (isPaymentCorrectionsOnly) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
        if (isRetailOrdersOnly) {
            if (query.source.kind != LocalRecordKind.SKU || query.source.excludedId != null)
                throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
            return
        }
        if (isKitchenOnly) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
        if (isFoodChecksOnly || isFoodCashFinancialOnly) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
        if (isFoodTableAssignmentsOnly) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
        if (isStockTransfersOnly && (query.source.kind != LocalRecordKind.WAREHOUSE || query.source.excludedId == null))
            throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
        if (isReportsOnly || isDirectoryOnly && configurationKind(query.source.kind) != directoryKind)
            throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    }

    internal fun requireIntent(intent: LocalCommandIntent) {
        if (!isRestricted) return
        if (isInvoiceCaptureOnly) {
            requireInvoiceCaptureIntent(intent)
            return
        }
        if (isInvoiceMatchingOnly) {
            requireInvoiceMatchingIntent(intent)
            return
        }
        if (isCustomerReceiptsOnly) {
            requireCustomerReceiptIntent(intent)
            return
        }
        if (isPaymentCorrectionsOnly) {
            requirePaymentCorrectionIntent(intent)
            return
        }
        if (isRetailOrdersOnly) {
            requireRetailOrderIntent(intent)
            return
        }
        if (isKitchenOnly) {
            requireKitchenIntent(intent)
            return
        }
        if (isFoodCashFinancialOnly) {
            requireFoodCashFinancialIntent(intent)
            return
        }
        if (isFoodChecksOnly) {
            requireFoodCheckIntent(intent)
            return
        }
        if (isFoodTableAssignmentsOnly) {
            requireFoodTableAssignmentIntent(intent)
            return
        }
        if (isStockTransfersOnly) {
            requireStockTransferIntent(intent)
            return
        }
        val formSupported = intent.formVersion == 1 || isSupplierOnly && intent.formVersion == 2 &&
            has(LocalSupplierProfileV2.COMMAND) && has(LocalSupplierProfileV2.QUERY)
        if (!allows(intent.action) || !formSupported || intent.arrivalEvidence != null ||
            intent.reference?.let { configurationKind(it.kind) != directoryKind || it.parentId != null } == true)
            throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    }

    internal fun requireRead(request: ReadRequest) {
        if (!has(request.query)) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
        if (isInvoiceCaptureOnly) {
            requireInvoiceCaptureRead(request)
            return
        }
        if (isInvoiceMatchingOnly) {
            requireInvoiceMatchingRead(request)
            return
        }
        if (isCustomerReceiptsOnly) {
            requireCustomerReceiptRead(request)
            return
        }
        if (isPaymentCorrectionsOnly) {
            requirePaymentCorrectionRead(request)
            return
        }
        if (isRetailOrdersOnly) {
            requireRetailOrderRead(request)
            return
        }
        if (isKitchenOnly) {
            requireKitchenRead(request)
            return
        }
        if (isFoodCashFinancialOnly) {
            requireFoodCashFinancialRead(request)
            return
        }
        if (isFoodChecksOnly) {
            requireFoodCheckRead(request)
            return
        }
        if (isFoodTableAssignmentsOnly) {
            requireFoodTableAssignmentRead(request)
            return
        }
        if (isStockTransfersOnly) {
            requireStockTransferRead(request)
            return
        }
        // Reports and export-audit metadata have separate closed query keys and existing typed
        // mappers. They are not directory variants and never acquire a configuration read key.
        if (isReportsOnly) return
        if (!isDirectoryOnly) return
        if (request.query == LocalSupplierProfileV2.QUERY) {
            if (!isSupplierOnly) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
            val query = try { LocalSupplierCodecV2.decodeQuery(request.payload) }
            catch (_: Exception) { invalid() }
            if (LocalSupplierCodecV2.query(query) != request.payload) invalid()
            return
        }
        val query = try { LocalConfigurationCodec.decodeQuery(request.payload) }
        catch (_: Exception) { invalid() }
        if (query.kind != directoryKind || query.barcode != null)
            throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
        if (LocalConfigurationCodec.query(query) != request.payload) invalid()
    }

    internal fun requireMutation(action: LocalAction, request: MutationRequest) {
        if (isReportsOnly) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
        if (!has(request.command)) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
        if (isInvoiceCaptureOnly) {
            invoiceCaptureRequest(action, request)
            return
        }
        if (isInvoiceMatchingOnly) {
            invoiceMatchingRequest(action, request)
            return
        }
        if (isCustomerReceiptsOnly) {
            customerReceiptRequest(action, request)
            return
        }
        if (isPaymentCorrectionsOnly) {
            requirePaymentCorrectionMutation(action, request)
            return
        }
        if (isRetailOrdersOnly) {
            retailOrderRequest(action, request)
            return
        }
        if (isKitchenOnly) {
            kitchenStationRequest(action, request)
            return
        }
        if (isFoodCashFinancialOnly) {
            foodCashFinancialRequest(action, request)
            return
        }
        if (isFoodChecksOnly) {
            foodCheckRequest(action, request)
            return
        }
        if (isFoodTableAssignmentsOnly) {
            foodTableAssignmentRequest(action, request)
            return
        }
        if (isStockTransfersOnly) {
            normalizedStockTransferRequest(action, request)
            return
        }
        directoryProfile?.let {
            if (isSupplierOnly && request.command == LocalSupplierProfileV2.COMMAND)
                supplierConfigurationRequestV2(action, request)
            else directoryConfigurationRequest(it, action, request)
        }
    }

    internal fun requireOriginal(entry: StoredClientIntentV1) {
        val profile = directoryProfile ?: return
        if (isInvoiceCaptureOnly) {
            invoiceCaptureRequest(entry.localAction, entry.request())
            savedSupplierInvoiceCaptureRequest(entry)
            return
        }
        if (isInvoiceMatchingOnly) {
            invoiceMatchingRequest(entry.localAction, entry.request())
            savedCapturedInvoiceMatchRequest(entry)
            return
        }
        if (isCustomerReceiptsOnly) {
            savedCustomerReceiptRequest(entry)
            return
        }
        if (isPaymentCorrectionsOnly) {
            savedPaymentCorrectionRequest(entry)
            return
        }
        if (isReportsOnly) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
        if (isRetailOrdersOnly) {
            savedRetailOrderRequest(entry)
            return
        }
        if (isKitchenOnly) {
            savedKitchenStationRequest(entry)
            return
        }
        if (isFoodCashFinancialOnly) {
            savedFoodCashFinancialRequest(entry)
            return
        }
        if (isFoodChecksOnly) {
            savedFoodCheckRequest(entry)
            return
        }
        if (isFoodTableAssignmentsOnly) {
            savedFoodTableAssignmentRequest(entry)
            return
        }
        if (isStockTransfersOnly) {
            savedStockTransferRequest(entry)
            return
        }
        // Shared configuration keys cannot qualify a different directory or an unrelated
        // retained command body, even when its original actor and operation match.
        if (isSupplierOnly && entry.isSupplierV2()) {
            if (!has(LocalSupplierProfileV2.COMMAND)) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
            savedSupplierConfigurationRequestV2(entry)
        } else savedDirectoryConfigurationRequest(profile, entry)
    }

    public companion object {
        /** Fixed bounded profile; shared configuration keys never install their other variants. */
        public fun directoryOnly(profile: LocalMasterRpcWorkflowProfile, supplierReferences: Boolean = false): LocalWorkflowInstallation = when (profile) {
            LocalMasterRpcWorkflowProfile.SUPPLIERS -> LocalWorkflowInstallation(
                if (supplierReferences) setOf(LocalConfigurationProfile.COMMAND, LocalSupplierProfileV2.COMMAND) else setOf(LocalConfigurationProfile.COMMAND),
                if (supplierReferences) setOf(LocalConfigurationProfile.QUERY, LocalSupplierProfileV2.QUERY) else setOf(LocalConfigurationProfile.QUERY), profile)
            LocalMasterRpcWorkflowProfile.WAREHOUSES -> LocalWorkflowInstallation(
                setOf(LocalConfigurationProfile.COMMAND), setOf(LocalConfigurationProfile.QUERY), profile)
            LocalMasterRpcWorkflowProfile.REPORTS -> LocalWorkflowInstallation(setOf(LocalBranchReportExportCodecV1.COMMAND),
                setOf(LocalBranchReportReadCodec.QUERY, LocalBranchReportExportCodecV1.QUERY), profile)
            LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS -> LocalWorkflowInstallation(
                setOf(LocalStockRelationalProfileV2.COMMAND), stockTransferReads, profile)
            LocalMasterRpcWorkflowProfile.KITCHEN -> LocalWorkflowInstallation(kitchenCommands, kitchenReads, profile)
            LocalMasterRpcWorkflowProfile.FOOD_CHECKS -> LocalWorkflowInstallation(foodCheckCommands, foodCheckReads, profile)
            LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL -> LocalWorkflowInstallation(foodCashFinancialCommands, foodCashFinancialReads, profile)
            LocalMasterRpcWorkflowProfile.FOOD_TABLE_ASSIGNMENTS -> LocalWorkflowInstallation(
                foodTableAssignmentCommands, foodTableAssignmentReads, profile)
            LocalMasterRpcWorkflowProfile.RETAIL_ORDERS -> LocalWorkflowInstallation(retailOrderCommands, retailOrderReads, profile)
            LocalMasterRpcWorkflowProfile.PAYMENT_CORRECTIONS -> LocalWorkflowInstallation(
                setOf(LocalPaymentCorrectionCodec.COMMAND), setOf(LocalPaymentCorrectionCodec.QUERY), profile)
            LocalMasterRpcWorkflowProfile.CUSTOMER_RECEIPTS -> LocalWorkflowInstallation(
                setOf(LocalRetailCustomerReceiptCodec.COMMAND), setOf(LocalRetailCustomerReceiptReadCodec.QUERY), profile)
            LocalMasterRpcWorkflowProfile.INVOICE_CAPTURE -> LocalWorkflowInstallation(
                invoiceCaptureCommands, invoiceCaptureReads, profile)
            LocalMasterRpcWorkflowProfile.INVOICE_MATCHING -> LocalWorkflowInstallation(
                invoiceMatchingCommands, invoiceMatchingReads, profile)
        }

        public fun supplierOnly(supplierReferences: Boolean = false): LocalWorkflowInstallation =
            directoryOnly(LocalMasterRpcWorkflowProfile.SUPPLIERS, supplierReferences)
    }
}

/**
 * Session client of the actually bound master client port and real durable client journal. It never
 * accepts a raw principal, arbitrary command, actor replacement, SQL, synthetic balance or script.
 * The kernel independently authorizes every request. Display hints do not supply authority.
 * This adapter does not create a transport, host a master or qualify a client-port implementation.
 */
public class LocalEndpointWorkflowGateway(
    private val endpoint: LocalMasterClient,
    private val journal: LocalClientIntentJournal,
    private val credentials: LocalWorkflowCredentialSource,
    private val installed: LocalWorkflowInstallation,
    /** A same-endpoint composition capability, not authority or remote qualification. */
    private val actionApprovals: LocalMasterApprovalClient? = if (installed.isRestricted) null else endpoint as? LocalMasterApprovalClient,
) : LocalWorkflowGateway {
    init {
        require(actionApprovals == null || actionApprovals === endpoint)
        require(!installed.isRestricted || actionApprovals == null)
    }
    private val approvalWorkflow = LocalActionApprovalWorkflow(actionApprovals, journal)
    private val documentDownloads = Mutex()

    override suspend fun downloadPurchasingDocument(documentId: DomainId, files: LocalPurchasingDocumentFiles,
        isCurrent: () -> Boolean): LocalPurchasingDocumentDownloadResult = documentDownloads.withLock {
        if (installed.isRestricted) return@withLock LocalPurchasingDocumentDownloadResult.Unavailable(documentId, LocalServiceProblem.NOT_IMPLEMENTED)
        var destination: LocalPurchasingDocumentFileDestination? = null
        val result = try {
            if (!files.bound) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
            if (!isCurrent()) throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
            val original = withAccess { readDocumentaryDownloadSource(it, documentId) }
            if (!isCurrent()) throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
            // No financial journal lock or credential is held across an external native dialog.
            when (val chosen = files.chooseDestination(original.originalFileName, original.media, isCurrent)) {
                LocalPurchasingDocumentFileDestinationSelection.Cancelled -> LocalPurchasingDocumentDownloadResult.Cancelled(documentId)
                is LocalPurchasingDocumentFileDestinationSelection.Unavailable ->
                    LocalPurchasingDocumentDownloadResult.Unavailable(documentId, documentaryFileProblem(chosen.problem))
                is LocalPurchasingDocumentFileDestinationSelection.Chosen -> {
                    destination = chosen.destination
                    withAccess { access ->
                        if (!isCurrent()) throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
                        if (readDocumentaryDownloadSource(access, documentId) != original)
                            throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
                        val bytes = reconstructDocumentaryDownload(access, original, isCurrent)
                        try {
                            revalidateDocumentaryDownload(access, original, bytes)
                            access.checkCurrent()
                            if (!isCurrent()) throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
                            when (val written = chosen.destination.write(bytes) { isCurrent() && access.lease.isCurrent() }) {
                                LocalPurchasingDocumentFileIoResult.Succeeded -> Unit
                                is LocalPurchasingDocumentFileIoResult.Unavailable -> throw GatewayFailure(documentaryFileProblem(written.problem))
                            }
                            access.checkCurrent()
                            if (!isCurrent()) throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
                            when (val closed = chosen.destination.close()) {
                                LocalPurchasingDocumentFileIoResult.Succeeded -> Unit
                                is LocalPurchasingDocumentFileIoResult.Unavailable -> throw GatewayFailure(documentaryFileProblem(closed.problem))
                            }
                            LocalPurchasingDocumentDownloadResult.Saved(documentId)
                        } finally { bytes.fill(0) }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                try {
                    val cleanup = destination?.abort()
                    if (destination != null && cleanup != LocalPurchasingDocumentFileIoResult.Succeeded)
                        cancelled.addSuppressed(IllegalStateException("Document output cleanup remains unresolved"))
                } catch (failure: Exception) {
                    if (failure !== cancelled) cancelled.addSuppressed(failure)
                }
            }
            // The runtime-owned destination registry retains failed cleanup across retirement.
            throw cancelled
        } catch (failure: GatewayFailure) { LocalPurchasingDocumentDownloadResult.Unavailable(documentId, failure.problem) }
        catch (_: Exception) { LocalPurchasingDocumentDownloadResult.Unavailable(documentId, LocalServiceProblem.STORAGE_UNCERTAIN) }
        if (result !is LocalPurchasingDocumentDownloadResult.Saved && destination != null) {
            val cleanup = withContext(NonCancellable) {
                try { destination?.abort() } catch (_: Exception) { null }
            }
            if (cleanup != LocalPurchasingDocumentFileIoResult.Succeeded)
                return@withLock LocalPurchasingDocumentDownloadResult.Unavailable(documentId, LocalServiceProblem.STORAGE_UNCERTAIN)
        }
        result
    }

    override suspend fun savePurchasingDocument(capture: LocalPurchasingDocumentCapture,
        originalBytes: ByteArray): LocalPurchasingDocumentSourceResult {
        if (installed.isRestricted) return LocalPurchasingDocumentSourceResult.Unavailable(LocalServiceProblem.NOT_IMPLEMENTED, capture.documentId)
        // Snapshot bounded caller-owned bytes before the first suspension, including mutex wait.
        val maximum = if (capture.parentOriginalId == null) LocalPurchasingDocumentProfileV1.MAX_ORIGINAL_BYTES
            else LocalPurchasingDocumentProfileV1.MAX_ATTACHMENT_BYTES
        if (originalBytes.size !in 1..maximum) return LocalPurchasingDocumentSourceResult.Unavailable(
            LocalServiceProblem.INVALID_INPUT, capture.documentId)
        val ownedBytes = originalBytes.copyOf()
        return try {
            journal.requests.withLock {
                try {
                    withAccess { access ->
                        val action = if (capture.parentOriginalId == null) LocalAction.PURCHASING_DOCUMENT_CAPTURE else LocalAction.PURCHASING_DOCUMENT_ATTACH
                        if (documentaryKeys.any { !installed.has(it) }) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
                        if (!access.allow(action, capture.documentId)) throw GatewayFailure(LocalServiceProblem.DENIED)
                        // A repeated UI identity cannot replace bytes or fabricate another step schedule.
                        val existing = required(journal.documentarySource(capture.documentId))
                        if (existing != null) {
                            existing.validate(journal.owner)
                            val bytes = existing.copyOriginalBytes()
                            val manifest = existing.begin
                            try {
                                if (!bytes.contentEquals(ownedBytes) || manifest.supplierId != capture.supplierId ||
                                    manifest.expectedSupplierRevision != capture.expectedSupplierRevision ||
                                    manifest.supplierReferenceOriginal != capture.supplierReferenceOriginal || manifest.documentDate != capture.documentDate ||
                                    manifest.purpose != capture.purpose || manifest.media != capture.media || manifest.originalFileName != capture.originalFileName ||
                                    manifest.parentOriginalId != capture.parentOriginalId || manifest.correctionOf != capture.correctionOf ||
                                    manifest.correctionReason != capture.correctionReason ||
                                    existing.complete.expectedParentAttachmentsRevision != capture.expectedParentAttachmentsRevision)
                                    throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
                            } finally { bytes.fill(0) }
                            return@withAccess LocalPurchasingDocumentSourceResult.Saved(capture.documentId)
                        }
                        val content = LocalPurchasingDocumentCodecV1.content(ownedBytes)
                        val begin = LocalBeginPurchasingDocumentV1(capture.documentId, capture.supplierId,
                            capture.expectedSupplierRevision, capture.supplierReferenceOriginal, capture.documentDate,
                            capture.purpose, capture.media, capture.originalFileName, content.byteLength,
                            content.exactBytesDigest, content.chunkDigests, capture.parentOriginalId, capture.correctionOf, capture.correctionReason)
                        val beginOperation = OperationId.random()
                        val source = journal.prepareDocumentarySource(beginOperation, begin,
                            content.chunkDigests.map { OperationId.random() }, OperationId.random(),
                            LocalCompletePurchasingDocumentV1(capture.documentId, beginOperation, capture.expectedParentAttachmentsRevision), ownedBytes)
                        access.checkCurrent()
                        when (journal.saveDocumentarySource(source)) {
                            JournalWrite.DURABLE -> LocalPurchasingDocumentSourceResult.Saved(capture.documentId)
                            JournalWrite.NO_CAPACITY -> LocalPurchasingDocumentSourceResult.Unavailable(LocalServiceProblem.RETRY_LATER, capture.documentId)
                            else -> LocalPurchasingDocumentSourceResult.Unavailable(LocalServiceProblem.STORAGE_UNCERTAIN, capture.documentId)
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: GatewayFailure) { LocalPurchasingDocumentSourceResult.Unavailable(failure.problem, capture.documentId) }
                catch (_: IllegalArgumentException) { LocalPurchasingDocumentSourceResult.Unavailable(LocalServiceProblem.INVALID_INPUT, capture.documentId) }
                catch (_: Exception) { LocalPurchasingDocumentSourceResult.Unavailable(LocalServiceProblem.STORAGE_UNCERTAIN, capture.documentId) }
            }

        } finally { ownedBytes.fill(0) }
    }

    override suspend fun purchasingDocumentSources(): LocalPurchasingDocumentSourcesResult = journal.requests.withLock {
        if (installed.isRestricted) return@withLock LocalPurchasingDocumentSourcesResult.Unavailable(LocalServiceProblem.NOT_IMPLEMENTED)
        try {
            withAccess { access ->
                val sources = required(journal.documentarySources())
                // No source content is returned through a different actor or a stale grant cache.
                LocalPurchasingDocumentSourcesResult.Available(sources.filter { source ->
                    access.allow(if (source.begin.parentOriginalId == null) LocalAction.PURCHASING_DOCUMENT_CAPTURE
                        else LocalAction.PURCHASING_DOCUMENT_ATTACH, source.documentId)
                }.map { source -> source.validate(journal.owner)
                    LocalPurchasingDocumentSourceSummary(source.documentId, source.begin.originalFileName,
                        source.begin.byteLength, source.begin.parentOriginalId) })
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: GatewayFailure) { LocalPurchasingDocumentSourcesResult.Unavailable(failure.problem) }
        catch (_: Exception) { LocalPurchasingDocumentSourcesResult.Unavailable(LocalServiceProblem.STORAGE_UNCERTAIN) }
    }

    override suspend fun transferPurchasingDocument(documentId: DomainId): LocalPurchasingDocumentTransferResult = journal.requests.withLock {
        if (installed.isRestricted) return@withLock LocalPurchasingDocumentTransferResult.Unresolved(documentId, null, LocalServiceProblem.NOT_IMPLEMENTED)
        var currentOperation: OperationId? = null
        try {
            withAccess { access ->
                val source = required(journal.documentarySource(documentId)) ?: throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
                source.validate(journal.owner)
                val action = if (source.begin.parentOriginalId == null) LocalAction.PURCHASING_DOCUMENT_CAPTURE else LocalAction.PURCHASING_DOCUMENT_ATTACH
                // A reviewed retry never erases or dispatches its refused predecessor again.
                for (previous in source.priorCompletionOperationIds) {
                    currentOperation = previous
                    archiveStaleDocumentaryCompletion(access, source, previous)
                }
                val requests = listOf(source.beginRequest()) + source.chunkOperationIds.indices.map(source::chunkRequest) + source.latestCompleteRequest()
                for (request in requests) {
                    currentOperation = request.operationId
                    access.checkCurrent()
                    var entry = required(journal.find(request.operationId))
                    if (entry == null) {
                        if (required(journal.pending()).isNotEmpty()) throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
                        if (!installed.has(request.command)) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
                        if (!access.allow(action, documentId)) throw GatewayFailure(LocalServiceProblem.DENIED)
                        val digest = request.payload.copyBytes().useWiped { sha256(it).value }
                        val intent = LocalCommandIntent(request.operationId, action, 1, null,
                            listOf(LocalScalarInput(LocalField.DOCUMENT_ID, LocalScalarValue.Identifier(documentId)),
                                LocalScalarInput(LocalField.PURCHASING_DOCUMENT_BODY_DIGEST, LocalScalarValue.Text(digest))))
                        val staged = StoredClientIntentV1.staged(journal.owner, intent, request,
                            text("Transfer documentary original", "نقل أصل مستندي"),
                            listOf(recordValue(text("Document", "المستند"), literal(documentId.value))))
                        when (journal.stage(staged)) {
                            JournalWrite.DURABLE -> Unit
                            JournalWrite.NO_CAPACITY -> throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
                            else -> throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
                        }
                        entry = staged
                    }
                    if (entry.owner != journal.owner || entry.localAction != action || entry.request().command != request.command ||
                        entry.request().payload != request.payload || entry.request().actionApprovalId != null)
                        throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
                    val reply = when (val found = access.lookup(entry)) {
                        is LookupReply.Found -> found.result
                        is LookupReply.AbsentAtCurrentWriter -> {
                            if (found.operationId != request.operationId || entry.recorded != null || entry.acknowledged)
                                throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
                            if (!access.canContinue(entry)) throw GatewayFailure(LocalServiceProblem.DENIED)
                            if (journal.markDispatched(request.operationId) != JournalWrite.DURABLE)
                                throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
                            access.checkCurrent()
                            when (val sent = access.execute(entry, request)) {
                                is MutationReply.Recorded -> sent
                                is MutationReply.Deferred -> throw GatewayFailure(
                                    if (sent.operationId == request.operationId) problem(sent.reason) else LocalServiceProblem.STORAGE_UNCERTAIN)
                                is MutationReply.IdentityConflict -> throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
                            }
                        }
                        is LookupReply.Deferred -> throw GatewayFailure(
                            if (found.operationId == request.operationId) problem(found.reason) else LocalServiceProblem.STORAGE_UNCERTAIN)
                        is LookupReply.IdentityConflict -> throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
                    }
                    access.checkCurrent()
                    val body = validateDocumentaryTransferResult(source, request, reply)
                    val recorded = StoredKernelResultV1.from(reply)
                    if (entry.recorded != null && entry.recorded != recorded ||
                        journal.remember(request.operationId, recorded) != JournalWrite.DURABLE)
                        throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
                    if (!reply.committed) return@withAccess LocalPurchasingDocumentTransferResult.Rejected(documentId, request.operationId, body.code)
                    // Technical transfer acknowledgements retain every immutable archived step/result.
                    // No money instruction, source deletion or Master evidence removal occurs here.
                    if (journal.acknowledge(request.operationId, recorded) != JournalWrite.DURABLE)
                        throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
                }
                LocalPurchasingDocumentTransferResult.Captured(documentId, source.latestCompleteOperationId)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: GatewayFailure) { LocalPurchasingDocumentTransferResult.Unresolved(documentId, currentOperation, failure.problem) }
        catch (_: Exception) { LocalPurchasingDocumentTransferResult.Unresolved(documentId, currentOperation, LocalServiceProblem.STORAGE_UNCERTAIN) }
    }

    override suspend fun retryPurchasingDocumentCompletion(documentId: DomainId,
        refusedCompleteOperationId: OperationId, newParentAttachmentsRevision: Revision): LocalPurchasingDocumentSourceResult =
        journal.requests.withLock {
            if (installed.isRestricted) return@withLock LocalPurchasingDocumentSourceResult.Unavailable(LocalServiceProblem.NOT_IMPLEMENTED, documentId)
            try {
                withAccess { access ->
                    if (!installed.has(LocalPurchasingDocumentProfileV1.COMPLETE)) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
                    if (!access.allow(LocalAction.PURCHASING_DOCUMENT_ATTACH, documentId)) throw GatewayFailure(LocalServiceProblem.DENIED)
                    val source = required(journal.documentarySource(documentId)) ?: throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
                    source.validate(journal.owner)
                    if (source.begin.parentOriginalId == null) throw GatewayFailure(LocalServiceProblem.INVALID_INPUT)
                    val retained = required(journal.find(refusedCompleteOperationId)) ?: throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
                    requireStaleDocumentaryCompletion(access, source, retained)
                    val latest = LocalPurchasingDocumentCodecV1.decodeComplete(source.latestCompleteRequest().payload)
                    val next = when {
                        source.latestCompleteOperationId == refusedCompleteOperationId -> {
                            // A bad submitted CAS may be ahead of the real parent. The Master,
                            // not local numeric ordering, decides whether this reviewed CAS is current.
                            if (newParentAttachmentsRevision.value < 1 ||
                                newParentAttachmentsRevision == latest.expectedParentAttachmentsRevision)
                                throw GatewayFailure(LocalServiceProblem.INVALID_INPUT)
                            if (source.completionAttemptCount >= MAX_CLIENT_DOCUMENTARY_COMPLETION_ATTEMPTS)
                                throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
                            source.prepareCompletionRetry(OperationId.random(), newParentAttachmentsRevision)
                        }
                        // Lost local save reply: recover the exact immediate child, not another attempt.
                        source.priorCompletionOperationIds.lastOrNull() == refusedCompleteOperationId &&
                            latest.expectedParentAttachmentsRevision == newParentAttachmentsRevision -> source
                        else -> throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
                    }
                    access.checkCurrent()
                    when (journal.appendDocumentaryCompletionRetry(next)) {
                        JournalWrite.DURABLE -> Unit
                        JournalWrite.NO_CAPACITY -> throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
                        else -> throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
                    }
                    archiveStaleDocumentaryCompletion(access, next, refusedCompleteOperationId)
                    // Private save only. Dispatch of the new immutable completion is a separate action.
                    LocalPurchasingDocumentSourceResult.Saved(documentId)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: GatewayFailure) { LocalPurchasingDocumentSourceResult.Unavailable(failure.problem, documentId) }
            catch (_: Exception) { LocalPurchasingDocumentSourceResult.Unavailable(LocalServiceProblem.STORAGE_UNCERTAIN, documentId) }
        }

    private suspend fun requireStaleDocumentaryCompletion(access: GatewayAccess,
        source: StoredClientDocumentarySourceV1, entry: StoredClientIntentV1): StoredKernelResultV1 {
        if (!source.hasClosedStaleCompletion(entry)) throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
        val found = access.lookup(entry)
        if (found is LookupReply.Deferred) throw GatewayFailure(
            if (found.operationId == entry.operationId) problem(found.reason) else LocalServiceProblem.STORAGE_UNCERTAIN)
        if (found !is LookupReply.Found) throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
        val value = validateDocumentaryTransferResult(source, entry.request(), found.result)
        if (found.result.committed || value.code != "purchasing.document.stale_revision")
            throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
        val result = StoredKernelResultV1.from(found.result)
        if (entry.recorded != result) throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
        return result
    }

    private suspend fun archiveStaleDocumentaryCompletion(access: GatewayAccess,
        source: StoredClientDocumentarySourceV1, operationId: OperationId) {
        val entry = required(journal.find(operationId)) ?: throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
        val result = requireStaleDocumentaryCompletion(access, source, entry)
        access.checkCurrent()
        if (journal.acknowledge(operationId, result) != JournalWrite.DURABLE)
            throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
    }

    // Never hold the financial journal's request mutex across a native dialog/file operation:
    // the original session's protected current/refresh monitor must remain able to invalidate it.
    private val reportExports = Mutex()

    override suspend fun exportBranchReport(selection: LocalBranchReportExportSelection, files: LocalBranchReportFileDelivery,
        lifetime: LocalBranchReportExportLifetime, progress: (LocalBranchReportExportProgress) -> Unit): LocalBranchReportExportResult = reportExports.withLock {
        if (installed.isRestricted && !installed.isReportsOnly)
            return@withLock LocalBranchReportExportResult.Unavailable(serviceProblem = LocalServiceProblem.NOT_IMPLEMENTED)
        try { withAccess { exportLocalBranchReport(it, selection, files, lifetime, progress) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: GatewayFailure) { LocalBranchReportExportResult.Unavailable(serviceProblem = failure.problem) }
        catch (_: Exception) { LocalBranchReportExportResult.Unavailable(serviceProblem = LocalServiceProblem.UNAVAILABLE) }
    }

    override suspend fun branchReportExports(files: LocalBranchReportFileDelivery, lifetime: LocalBranchReportExportLifetime,
        after: DomainId?): LocalBranchReportExportHistory = reportExports.withLock {
        if (installed.isRestricted && !installed.isReportsOnly)
            return@withLock LocalBranchReportExportHistory.Unavailable(serviceProblem = LocalServiceProblem.NOT_IMPLEMENTED)
        try { withAccess { listLocalBranchReportExports(it, files, lifetime, after) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: GatewayFailure) { LocalBranchReportExportHistory.Unavailable(serviceProblem = failure.problem) }
        catch (_: Exception) { LocalBranchReportExportHistory.Unavailable(serviceProblem = LocalServiceProblem.UNAVAILABLE) }
    }

    override suspend fun recoverBranchReportExport(exportId: DomainId, files: LocalBranchReportFileDelivery,
        lifetime: LocalBranchReportExportLifetime): LocalBranchReportExportResult = reportExports.withLock {
        if (installed.isRestricted && !installed.isReportsOnly)
            return@withLock LocalBranchReportExportResult.Unavailable(serviceProblem = LocalServiceProblem.NOT_IMPLEMENTED, exportId = exportId)
        try { withAccess { recoverLocalBranchReportExport(it, exportId, files, lifetime) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: GatewayFailure) { LocalBranchReportExportResult.Unavailable(serviceProblem = failure.problem, exportId = exportId) }
        catch (_: Exception) { LocalBranchReportExportResult.Unavailable(serviceProblem = LocalServiceProblem.UNAVAILABLE, exportId = exportId) }
    }

    override public suspend fun read(query: LocalWorkflowQuery): LocalReadResult = journal.requests.withLock {
        try { withAccess { access ->
            val page = if (installed.isCustomerReceiptsOnly) {
                installed.requireWorkflow(query)
                customerReceiptWorkflow(access, query)
            } else if (installed.isFoodCashFinancialOnly) {
                installed.requireWorkflow(query)
                foodCashFinancialWorkflow(access, query)
            } else if (installed.isFoodChecksOnly) {
                installed.requireWorkflow(query)
                foodCheckWorkflow(access, query)
            } else readLocalWorkflow(access, query)
            LocalReadResult.Available(page)
        } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: GatewayFailure) { LocalReadResult.Unavailable(failure.problem) }
        catch (_: Exception) { LocalReadResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
    }

    override suspend fun references(query: LocalReferenceQuery): LocalReferenceResult = journal.requests.withLock {
        try { withAccess { installed.requireReferences(query); LocalReferenceResult.Available(readLocalReferences(it, query)) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: GatewayFailure) { LocalReferenceResult.Unavailable(failure.problem) }
        catch (_: Exception) { LocalReferenceResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
    }

    override suspend fun previewFood(input: LocalFoodPreviewInput): LocalFoodPricePreviewResult {
        if (installed.isRestricted && !installed.isFoodChecksOnly)
            return LocalFoodPricePreviewResult.Unavailable(LocalServiceProblem.NOT_IMPLEMENTED)
        // Snapshot before waiting for another action. LocalInputLine has identity equality;
        // the structural comparison also rejects edits while the protected reads are awaited.
        val selection = try { input.snapshot() }
        catch (_: IllegalArgumentException) { return LocalFoodPricePreviewResult.Unavailable(LocalServiceProblem.INVALID_INPUT) }
        return journal.requests.withLock {
            try {
                if (!selection.sameSelection(input)) invalid()
                val result = withAccess { access ->
                    if (!selection.sameSelection(input)) invalid()
                    val preview = if (installed.isFoodChecksOnly) previewFoodCheckSelection(access, selection)
                        else previewLocalFoodSelection(access, selection)
                    access.checkCurrent()
                    if (!selection.sameSelection(input)) invalid()
                    LocalFoodPricePreviewResult.Available(preview)
                }
                if (!selection.sameSelection(input)) invalid()
                result
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: GatewayFailure) { LocalFoodPricePreviewResult.Unavailable(failure.problem, failure.field) }
            catch (_: Exception) { LocalFoodPricePreviewResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
        }
    }

    override suspend fun previewActionApproval(operationId: OperationId): LocalActionApprovalResult = journal.requests.withLock {
        approvalOperation(operationId) { access, entry -> approvalWorkflow.preview(access, entry) }
    }

    override suspend fun issueActionApproval(operationId: OperationId, confirmationDigest: PayloadDigest,
        approverLogin: String, approverPassword: CharArray): LocalActionApprovalResult {
        if (installed.isRestricted) return LocalActionApprovalResult.Unavailable(operationId, LocalServiceProblem.NOT_IMPLEMENTED)
        // Caller owns this array. Snapshot bounded input BEFORE mutex wait/credential acquisition,
        // never retain an immutable password String and never sign the requestor out.
        if (approverPassword.size !in 4..1024) return LocalActionApprovalResult.Unavailable(operationId, LocalServiceProblem.INVALID_INPUT)
        val login = try { LocalLoginIdentifier(approverLogin) }
        catch (_: IllegalArgumentException) { return LocalActionApprovalResult.Unavailable(operationId, LocalServiceProblem.INVALID_INPUT) }
        val ownedPassword = approverPassword.copyOf()
        return try {
            journal.requests.withLock {
                approvalOperation(operationId) { access, entry ->
                    approvalWorkflow.issue(access, entry, confirmationDigest, login, ownedPassword)
                }
            }
        } finally { ownedPassword.fill('\u0000') }
    }

    override suspend fun lookupActionApproval(operationId: OperationId): LocalActionApprovalResult = journal.requests.withLock {
        approvalOperation(operationId) { access, entry -> approvalWorkflow.lookup(access, entry) }
    }

    override suspend fun retireActionApproval(operationId: OperationId): LocalActionApprovalResult =
        approvalOperation(operationId) { access, entry -> approvalWorkflow.retire(access, entry) }

    override suspend fun beginActionApprovalReplacement(operationId: OperationId): LocalActionApprovalResult = journal.requests.withLock {
        approvalOperation(operationId) { access, entry -> approvalWorkflow.beginReplacement(access, entry) }
    }

    override public suspend fun stage(intent: LocalCommandIntent): LocalStageResult = journal.requests.withLock {
        var absenceEstablished = false
        var writeStarted = false
        try {
            withAccess { access ->
                val existing = required(journal.find(intent.operationId))
                if (existing != null) {
                    access.requireOriginal(existing)
                    if (existing.localAction != intent.action || existing.formVersion != intent.formVersion || existing.inputDigest != inputDigest(intent))
                        return@withAccess LocalStageResult.Uncertain(intent.operationId)
                    return@withAccess when (val recovered = recover(access, existing)) {
                        is LocalOperationResult.Pending -> LocalStageResult.Staged(recovered.operation)
                        is LocalOperationResult.Recorded -> LocalStageResult.Staged(pendingOriginal(existing.copy(
                            phase = LocalPendingPhase.RECORDED_UNACKNOWLEDGED.name,
                            recorded = requiredRecorded(access, existing)), submitAllowed = false))
                        is LocalOperationResult.Unresolved -> LocalStageResult.Uncertain(intent.operationId)
                    }
                }
                absenceEstablished = true
                installed.requireIntent(intent)
                // Do not let bypassing the Compose guard mint more work over this client's unknown
                // or unacknowledged result. Another session sharing the journal uses this same lock.
                val pending = required(journal.pending())
                pending.forEach(access::requireOriginal)
                if (pending.isNotEmpty()) return@withAccess LocalStageResult.NotStaged(LocalServiceProblem.RETRY_LATER)
                if (!supportsLocalForm(intent)) return@withAccess LocalStageResult.NotStaged(LocalServiceProblem.INVALID_INPUT)
                val mapped = if (installed.isFoodCashFinancialOnly) prepareFoodCashFinancialIntent(access, intent)
                    else if (installed.isFoodChecksOnly) prepareFoodCheckIntent(access, intent)
                    else prepareLocalIntent(access, intent)
                access.checkCurrent()
                installed.requireMutation(intent.action, mapped.request)
                val entry = StoredClientIntentV1.staged(journal.owner, intent, mapped.request, mapped.label, mapped.summary)
                writeStarted = true
                when (journal.stage(entry)) {
                    JournalWrite.DURABLE -> {
                        access.checkCurrent()
                        // No mutation was dispatched by stage, even when a subsequent lookup is unavailable.
                        LocalStageResult.Staged(pendingOriginal(entry, access.canContinue(entry)))
                    }
                    JournalWrite.NO_CAPACITY -> LocalStageResult.NotStaged(LocalServiceProblem.RETRY_LATER)
                    JournalWrite.IDENTITY_CONFLICT, JournalWrite.UNAVAILABLE -> LocalStageResult.Uncertain(intent.operationId)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: GatewayFailure) {
            if (absenceEstablished && !writeStarted) LocalStageResult.NotStaged(failure.problem, failure.field)
            else LocalStageResult.Uncertain(intent.operationId)
        } catch (_: IllegalArgumentException) {
            if (absenceEstablished && !writeStarted) LocalStageResult.NotStaged(LocalServiceProblem.INVALID_INPUT)
            else LocalStageResult.Uncertain(intent.operationId)
        } catch (_: Exception) { LocalStageResult.Uncertain(intent.operationId) }
    }

    override public suspend fun submit(operationId: OperationId): LocalOperationResult = journal.requests.withLock {
        operation(operationId) { access, entry ->
            // Dedicated native-payment entries are lookup-only here, even if their current result is
            // absent. In particular, generic submit can NEVER replay a physical Start.
            if (entry.localAction in physicalActions && !sqlFoodOriginalApply(entry)) return@operation recover(access, entry)
            // First recover the exact original identity; absence is not cancellation or new-ID
            // permission. Only the same immutable body may be continued, under fresh kernel auth.
            when (val lookup = access.lookup(entry)) {
                is LookupReply.Found -> publishRecorded(access, entry, lookup.result)
                is LookupReply.AbsentAtCurrentWriter -> {
                    if (lookup.operationId != operationId || entry.recorded != null || entry.acknowledged)
                        unresolved(operationId)
                    else {
                        // Issuance lookup can attach only the SAME original receipt. An absent
                        // or consumed approval never permits the base unapproved command to run.
                        val continuation = reconcileApproval(access, entry)
                        if (continuation.actionApproval != null &&
                            continuation.actionApproval.localPhase != LocalActionApprovalPhase.ATTACHED)
                            LocalOperationResult.Pending(pendingOriginal(continuation, false))
                        else if (!access.canContinue(continuation)) LocalOperationResult.Unresolved(operationId, LocalServiceProblem.DENIED)
                        else if (journal.markDispatched(operationId) != JournalWrite.DURABLE) unresolved(operationId)
                        else {
                            access.checkCurrent()
                            val original = continuation.request()
                            installed.requireMutation(continuation.localAction, original)
                            when (val reply = access.execute(continuation, original)) {
                                is MutationReply.Recorded -> {
                                    // Retain the committed observation even if the UI became locked while awaiting it.
                                    if (matches(continuation, reply)) {
                                        validateRecordedResult(continuation, reply)
                                        rememberRecorded(continuation, StoredKernelResultV1.from(reply))
                                    }
                                    access.checkCurrent()
                                    publishRecorded(access, continuation, reply)
                                }
                                is MutationReply.Deferred -> {
                                    access.checkCurrent()
                                    LocalOperationResult.Unresolved(operationId,
                                        if (reply.operationId == operationId) problem(reply.reason) else LocalServiceProblem.UNAVAILABLE)
                                }
                                is MutationReply.IdentityConflict -> unresolved(operationId)
                            }
                        }
                    }
                }
                is LookupReply.Deferred -> LocalOperationResult.Unresolved(operationId,
                    if (lookup.operationId == operationId) problem(lookup.reason) else LocalServiceProblem.UNAVAILABLE)
                is LookupReply.IdentityConflict -> unresolved(operationId)
            }
        }
    }

    override public suspend fun lookup(operationId: OperationId): LocalOperationResult = journal.requests.withLock {
        operation(operationId) { access, entry -> recover(access, entry) }
    }

    override public suspend fun pending(cursor: String?): LocalPendingResult = journal.requests.withLock {
        try {
            withAccess { access ->
                val originals = required(journal.pending())
                // Validate the complete retained set before accepting a caller frontier.
                // Unsupported or malformed history never becomes an empty successful page.
                originals.forEach(access::requireOriginal)
                val after = cursor?.let {
                    if (!it.startsWith("p1:")) throw GatewayFailure(LocalServiceProblem.INVALID_INPUT)
                    try { OperationId(it.removePrefix("p1:")) } catch (_: IllegalArgumentException) { throw GatewayFailure(LocalServiceProblem.INVALID_INPUT) }
                }
                val entries = originals.sortedBy { it.operationId.value }
                    .filter { after == null || it.operationId.value > after.value }
                val page = entries.take(16)
                val result = ArrayList<LocalPendingOperation>(page.size)
                for (entry in page) {
                    // Current session identity alone is not sufficient: explicit result-recovery
                    // denies and original-actor/digest matching remain the kernel's decision.
                    when (val lookup = access.lookup(entry)) {
                        is LookupReply.Found -> {
                            if (!matches(entry, lookup.result)) throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
                            validateRecordedResult(entry, lookup.result)
                            val stored = StoredKernelResultV1.from(lookup.result)
                            if (entry.recorded != null && entry.recorded != stored) throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
                            rememberRecorded(entry, stored)
                            result += pendingOriginal(entry.copy(phase = LocalPendingPhase.RECORDED_UNACKNOWLEDGED.name, recorded = stored), false)
                        }
                        is LookupReply.AbsentAtCurrentWriter -> {
                            if (lookup.operationId != entry.operationId || entry.recorded != null) throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
                            val continuation = reconcileApproval(access, entry)
                            result += pendingOriginal(continuation, access.canContinue(continuation))
                        }
                        is LookupReply.Deferred -> throw GatewayFailure(problem(lookup.reason))
                        is LookupReply.IdentityConflict -> throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
                    }
                }
                access.checkCurrent()
                LocalPendingResult.Available(result, if (entries.size > page.size) "p1:${page.last().operationId.value}" else null)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: GatewayFailure) { LocalPendingResult.Unavailable(failure.problem) }
        catch (_: Exception) { LocalPendingResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
    }

    override public suspend fun acknowledgeRecorded(operationId: OperationId): LocalAcknowledgementResult = journal.requests.withLock {
        try {
            withAccess { access ->
                val entry = required(journal.find(operationId)) ?: throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
                access.requireOriginal(entry)
                // Never archive an unobserved result, lost body, incomplete tail, or a caller's label.
                val retained = entry.recorded ?: throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
                val actual = requiredRecorded(access, entry)
                if (actual != retained) throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
                if (journal.acknowledge(operationId, actual) != JournalWrite.DURABLE) throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
                access.checkCurrent()
                LocalAcknowledgementResult.Acknowledged(operationId)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: GatewayFailure) { LocalAcknowledgementResult.Unresolved(operationId, failure.problem) }
        catch (_: Exception) { LocalAcknowledgementResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE) }
    }

    private suspend fun requiredRecorded(access: GatewayAccess, entry: StoredClientIntentV1): StoredKernelResultV1 {
        val lookup = access.lookup(entry)
        if (lookup is LookupReply.Deferred) throw GatewayFailure(problem(lookup.reason))
        if (lookup !is LookupReply.Found || !matches(entry, lookup.result)) throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
        validateRecordedResult(entry, lookup.result)
        return StoredKernelResultV1.from(lookup.result)
    }

    private suspend fun recover(access: GatewayAccess, entry: StoredClientIntentV1): LocalOperationResult = when (val lookup = access.lookup(entry)) {
        is LookupReply.Found -> publishRecorded(access, entry, lookup.result)
        is LookupReply.AbsentAtCurrentWriter -> if (lookup.operationId == entry.operationId && entry.recorded == null && !entry.acknowledged) {
            val continuation = reconcileApproval(access, entry)
            LocalOperationResult.Pending(pendingOriginal(continuation, access.canContinue(continuation)))
        } else unresolved(entry.operationId)
        is LookupReply.Deferred -> LocalOperationResult.Unresolved(entry.operationId,
            if (lookup.operationId == entry.operationId) problem(lookup.reason) else LocalServiceProblem.UNAVAILABLE)
        is LookupReply.IdentityConflict -> unresolved(entry.operationId)
    }

    private fun pendingOriginal(entry: StoredClientIntentV1, submitAllowed: Boolean): LocalPendingOperation =
        if (installed.isFoodCashFinancialOnly) foodCashFinancialPending(entry, submitAllowed) else pendingEntry(entry, submitAllowed)

    private fun sqlFoodOriginalApply(entry: StoredClientIntentV1): Boolean = installed.isFoodCashFinancialOnly &&
        entry.localAction == LocalAction.FOOD_PAYMENT_APPLY && entry.formVersion == 1 &&
        savedFoodCashFinancialRequest(entry) is LocalFoodCashFinancialOriginal.ApplyOriginal

    private suspend fun reconcileApproval(access: GatewayAccess, entry: StoredClientIntentV1): StoredClientIntentV1 =
        if (entry.actionApproval == null) entry else approvalWorkflow.reconcile(access, entry)

    private suspend fun publishRecorded(access: GatewayAccess, entry: StoredClientIntentV1, reply: MutationReply.Recorded): LocalOperationResult {
        if (!matches(entry, reply)) return unresolved(entry.operationId)
        validateRecordedResult(entry, reply)
        val stored = StoredKernelResultV1.from(reply)
        if (entry.recorded != null && entry.recorded != stored) return unresolved(entry.operationId)
        // A failed CLIENT receipt write cannot undo a committed master result. The durably
        // dispatched intent remains outstanding and this retired journal cannot acknowledge it.
        rememberRecorded(entry, stored)
        access.checkCurrent()
        val result = if (installed.isFoodCashFinancialOnly) foodCashFinancialRecordedUi(entry, reply)
            else if (installed.isFoodChecksOnly) foodCheckRecordedUi(entry, reply) else renderRecorded(entry, reply)
        return result.copy(originalAction = entry.localAction)
    }

    private suspend fun validateRecordedResult(entry: StoredClientIntentV1, reply: MutationReply.Recorded) {
        // Validate the exact typed outcome before retaining, exposing or acknowledging it.
        // Matching actor/operation identity alone cannot establish the reviewed destination/CAS.
        if (installed.isStockTransfersOnly) stockTransferRecorded(entry, reply)
        if (installed.isKitchenOnly) kitchenStationRecorded(entry, reply)
        if (installed.isFoodChecksOnly) foodCheckRecorded(entry, reply)
        if (installed.isFoodCashFinancialOnly) foodCashFinancialRecorded(entry, reply)
        if (installed.isFoodTableAssignmentsOnly && reply.sequence.value == 0L)
            throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
        if (installed.isRetailOrdersOnly) retailOrderRecorded(entry, reply)
        if (entry.localAction == LocalAction.RETAIL_CUSTOMER_RECEIPT ||
            CommandKey(entry.commandName, entry.commandVersion) == LocalRetailCustomerReceiptCodec.COMMAND)
            customerReceiptRecorded(entry, reply)
        if (entry.localAction == LocalAction.PAYMENT_CLASSIFICATION_CORRECT ||
            CommandKey(entry.commandName, entry.commandVersion) == LocalPaymentCorrectionCodec.COMMAND)
            validatePaymentCorrectionRecorded(entry, reply)
        directoryConfigurationProfile(entry.localAction)?.let {
            if (entry.isSupplierV2()) supplierConfigurationRecordedV2(entry, reply)
            else directoryConfigurationRecorded(it, entry, reply)
        }
        if (entry.localAction == LocalAction.FOOD_ASSIGN_TABLE ||
            CommandKey(entry.commandName, entry.commandVersion) == LocalFoodTableAssignmentProfileV1.COMMAND)
            tableAssignmentRecorded(entry, reply)
        if (entry.localAction in returnCaseActions ||
            CommandKey(entry.commandName, entry.commandVersion) == LocalReturnCaseProfileV1.COMMAND)
            validateReturnCaseRecorded(entry, reply)
        if (entry.localAction == LocalAction.RETURN_STOCK_POST ||
            CommandKey(entry.commandName, entry.commandVersion) == LocalReturnStockProfileV1.COMMAND)
            validateReturnStockRecorded(entry, reply)
        if (entry.localAction == LocalAction.SUPPLIER_INVOICE_CAPTURE ||
            CommandKey(entry.commandName, entry.commandVersion) == LocalPurchasingProfile.CAPTURE_INVOICE)
            supplierInvoiceCaptureRecorded(entry, reply)
        if (entry.localAction == LocalAction.SUPPLIER_INVOICE_MATCH_RECOGNIZE ||
            CommandKey(entry.commandName, entry.commandVersion) == LocalPurchasingProfile.MATCH_CAPTURED_INVOICE)
            capturedInvoiceMatchRecorded(entry, reply)
        if (entry.localAction in documentaryActions) {
            val source = required(journal.documentarySource(documentaryTarget(entry.request())))
                ?: throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
            if (!source.accepts(entry)) throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
            validateDocumentaryTransferResult(source, entry.request(), reply)
        }
    }

    /** Runs inside the official transport acceptance callback, before its terminal Receipt. */
    private suspend fun retainForTransportReceipt(access: GatewayAccess, entry: StoredClientIntentV1,
        reply: MutationReply.Recorded) {
        access.checkCurrent()
        if (!matches(entry, reply)) throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
        validateRecordedResult(entry, reply)
        val stored = StoredKernelResultV1.from(reply)
        if (entry.recorded != null && entry.recorded != stored)
            throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
        if (journal.remember(entry.operationId, stored) != JournalWrite.DURABLE)
            throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
        access.checkCurrent()
    }

    private suspend fun rememberRecorded(entry: StoredClientIntentV1, result: StoredKernelResultV1) {
        val retained = journal.remember(entry.operationId, result)
        // Restricted mutation result presentation/ACK requires a genuinely retained terminal result.
        // A failed receipt write leaves its original outstanding and cannot become success.
        if ((entry.localAction == LocalAction.WAREHOUSE_CONFIGURE || entry.localAction in supplierConfigurationActions || installed.isStockTransfersOnly ||
            entry.localAction == LocalAction.SUPPLIER_INVOICE_RECORD ||
            entry.localAction == LocalAction.SUPPLIER_INVOICE_CAPTURE ||
            entry.localAction == LocalAction.SUPPLIER_INVOICE_MATCH_RECOGNIZE ||
            installed.isKitchenOnly || installed.isFoodChecksOnly || installed.isFoodCashFinancialOnly ||
            installed.isFoodTableAssignmentsOnly || installed.isRetailOrdersOnly ||
            entry.localAction == LocalAction.RETAIL_CUSTOMER_RECEIPT ||
            entry.localAction == LocalAction.PAYMENT_CLASSIFICATION_CORRECT) &&
            retained != JournalWrite.DURABLE)
            throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
    }

    private fun matches(entry: StoredClientIntentV1, reply: MutationReply.Recorded): Boolean =
        reply.operationId == entry.operationId && reply.originalActorId == entry.owner.actorId

    private suspend fun operation(id: OperationId, body: suspend (GatewayAccess, StoredClientIntentV1) -> LocalOperationResult): LocalOperationResult = try {
        withAccess { access ->
            val entry = required(journal.find(id)) ?: return@withAccess unresolved(id)
            access.requireOriginal(entry)
            body(access, entry)
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: GatewayFailure) { LocalOperationResult.Unresolved(id, failure.problem) }
    catch (_: Exception) { unresolved(id) }

    private suspend fun approvalOperation(id: OperationId,
        body: suspend (GatewayAccess, StoredClientIntentV1) -> LocalActionApprovalResult): LocalActionApprovalResult = try {
        if (installed.isRestricted) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
        withAccess { access ->
            val entry = required(journal.find(id)) ?: throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
            body(access, entry)
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: GatewayFailure) { LocalActionApprovalResult.Unavailable(id, failure.problem) }
    // Caller login/confirmation validation is handled explicitly. A malformed retained/server
    // preview or receipt is uncertainty, not permission to replace the original ceremony.
    catch (_: IllegalArgumentException) { LocalActionApprovalResult.Unavailable(id, LocalServiceProblem.STORAGE_UNCERTAIN) }
    catch (_: Exception) { LocalActionApprovalResult.Unavailable(id, LocalServiceProblem.STORAGE_UNCERTAIN) }

    private suspend fun <T> withAccess(block: suspend (GatewayAccess) -> T): T {
        currentCoroutineContext().ensureActive()
        val lease = credentials.acquire() ?: throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
        try {
            if (!lease.isCurrent()) throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
            val current = lease.observeSession(endpoint)
            currentCoroutineContext().ensureActive()
            if (!lease.isCurrent()) throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
            val view = when (current) {
                is LocalSessionViewResult.Current -> current.view
                LocalSessionViewResult.Denied -> throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
                LocalSessionViewResult.Busy -> throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
                LocalSessionViewResult.Unavailable -> throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
                LocalSessionViewResult.StorageUncertain -> throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
            }
            if (view.actorId != journal.owner.actorId || view.scope != journal.owner.scope || view.deviceId != journal.owner.deviceId)
                throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
            val access = GatewayAccess(endpoint, lease, view, installed, actionApprovals != null, ::retainForTransportReceipt)
            val result = block(access)
            access.checkCurrent()
            return result
        } finally { lease.destroy() }
    }
}

internal class GatewayAccess(
    private val endpoint: LocalMasterClient,
    val lease: LocalWorkflowCredentialLease,
    val view: LocalSessionView,
    val installed: LocalWorkflowInstallation,
    val actionApprovalsBound: Boolean = false,
    private val retainForReceipt: (suspend (GatewayAccess, StoredClientIntentV1, MutationReply.Recorded) -> Unit)? = null,
) {
    suspend fun checkCurrent() {
        currentCoroutineContext().ensureActive()
        if (!lease.isCurrent()) throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
    }
    suspend fun read(request: ReadRequest): ReadReply.Data {
        checkCurrent()
        installed.requireRead(request)
        val reply = endpoint.read(lease.credentials, request)
        checkCurrent()
        return when (reply) {
            is ReadReply.Data -> reply
            is ReadReply.Unavailable -> throw GatewayFailure(problem(reply.reason))
        }
    }
    suspend fun lookup(entry: StoredClientIntentV1): LookupReply {
        checkCurrent()
        requireOriginal(entry)
        val retention = retainForReceipt
        val result = if (endpoint is LocalMasterClientResultAcceptance && retention != null)
            endpoint.lookupAccepting(lease.credentials, entry.identity()) { reply -> retention(this, entry, reply) }
        else endpoint.lookup(lease.credentials, entry.identity())
        checkCurrent()
        return result
    }

    suspend fun execute(entry: StoredClientIntentV1, request: MutationRequest): MutationReply {
        checkCurrent()
        requireOriginal(entry)
        val retention = retainForReceipt
        val result = if (endpoint is LocalMasterClientResultAcceptance && retention != null)
            endpoint.executeAccepting(lease.credentials, request) { reply -> retention(this, entry, reply) }
        else endpoint.execute(lease.credentials, request)
        return result
    }

    fun requireOriginal(entry: StoredClientIntentV1) {
        if (entry.localAction == LocalAction.SUPPLIER_INVOICE_MATCH_RECOGNIZE ||
            CommandKey(entry.commandName, entry.commandVersion) == LocalPurchasingProfile.MATCH_CAPTURED_INVOICE) {
            if (entry.owner != LocalClientIdentity(view.scope, view.actorId, view.deviceId))
                throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
            savedCapturedInvoiceMatchRequest(entry)
        }
        if (entry.localAction == LocalAction.SUPPLIER_INVOICE_CAPTURE ||
            CommandKey(entry.commandName, entry.commandVersion) == LocalPurchasingProfile.CAPTURE_INVOICE) {
            if (entry.owner != LocalClientIdentity(view.scope, view.actorId, view.deviceId))
                throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
            savedSupplierInvoiceCaptureRequest(entry)
        }
        if (entry.localAction == LocalAction.RETAIL_CUSTOMER_RECEIPT ||
            CommandKey(entry.commandName, entry.commandVersion) == LocalRetailCustomerReceiptCodec.COMMAND) {
            if (entry.owner != LocalClientIdentity(view.scope, view.actorId, view.deviceId))
                throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
            savedCustomerReceiptRequest(entry)
        }
        if (entry.localAction == LocalAction.PAYMENT_CLASSIFICATION_CORRECT ||
            CommandKey(entry.commandName, entry.commandVersion) == LocalPaymentCorrectionCodec.COMMAND) {
            if (entry.owner != LocalClientIdentity(view.scope, view.actorId, view.deviceId))
                throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
            savedPaymentCorrectionRequest(entry)
        }
        if (entry.isSupplierV2() || entry.localAction in supplierConfigurationActions && entry.formVersion == 2) {
            if (entry.owner != LocalClientIdentity(view.scope, view.actorId, view.deviceId))
                throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
            savedSupplierConfigurationRequestV2(entry)
        }
        if (!installed.isRestricted && entry.localAction != LocalAction.WAREHOUSE_CONFIGURE) return
        if (entry.owner != LocalClientIdentity(view.scope, view.actorId, view.deviceId))
            throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
        if (installed.isRestricted) installed.requireOriginal(entry)
        else savedDirectoryConfigurationRequest(LocalMasterRpcWorkflowProfile.WAREHOUSES, entry)
    }

    /** Narrow audit transport used only by the typed begin/finish/lookup export adapter. */
    suspend fun reportExportCommand(request: MutationRequest,
        acceptRecorded: (suspend (MutationReply.Recorded) -> Unit)? = null): MutationReply {
        checkCurrent()
        require(request.command == LocalBranchReportExportCodecV1.COMMAND && request.actionApprovalId == null)
        if (!installed.has(request.command)) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
        val result = if (endpoint is LocalMasterClientResultAcceptance && acceptRecorded != null)
            endpoint.executeAccepting(lease.credentials, request, acceptRecorded)
        else endpoint.execute(lease.credentials, request)
        checkCurrent()
        return result
    }

    suspend fun reportExportOperation(identity: OperationIdentity,
        acceptRecorded: (suspend (MutationReply.Recorded) -> Unit)? = null): LookupReply {
        checkCurrent()
        if (!installed.has(LocalBranchReportExportCodecV1.COMMAND)) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
        val result = if (endpoint is LocalMasterClientResultAcceptance && acceptRecorded != null)
            endpoint.lookupAccepting(lease.credentials, identity, acceptRecorded)
        else endpoint.lookup(lease.credentials, identity)
        checkCurrent()
        return result
    }

    /** Conservative display/continuation hint only; never substitutes for kernel limits/ownership. */
    fun hasHint(permission: Permission, target: DomainId? = null): Boolean {
        val grants = view.policy.grants.filter { it.permission == permission &&
            (it.scope == view.scope || it.scope == view.scope.company()) &&
            (it.namedSubjectId == null || it.namedSubjectId == view.actorId) &&
            (it.targetIds == null || target != null && target in it.targetIds.orEmpty()) }
        return grants.none { it.effect == GrantEffect.DENY } && grants.any {
            it.effect == GrantEffect.ALLOW &&
                (permission.action != SecurityAction.KITCHEN_TASK || it.targetIds != null)
        }
    }

    suspend fun canContinue(entry: StoredClientIntentV1): Boolean {
        requireOriginal(entry)
        val sqlOriginalApply = installed.isFoodCashFinancialOnly && entry.localAction == LocalAction.FOOD_PAYMENT_APPLY &&
            entry.formVersion == 1 && savedFoodCashFinancialRequest(entry) is LocalFoodCashFinancialOriginal.ApplyOriginal
        return (entry.localAction !in physicalActions || sqlOriginalApply) && entry.recorded == null && !entry.acknowledged &&
            installed.has(CommandKey(entry.commandName, entry.commandVersion)) && when {
                installed.isFoodCashFinancialOnly -> foodCashFinancialContinuationHint(this, entry)
                installed.isFoodChecksOnly -> foodCheckContinuationHint(this, entry)
                else -> continuationHint(this, entry)
            }
    }
}

internal class GatewayFailure(val problem: LocalServiceProblem, val field: com.ukkera.pos.app.LocalField? = null) : Exception()
internal fun invalid(field: com.ukkera.pos.app.LocalField? = null): Nothing = throw GatewayFailure(LocalServiceProblem.INVALID_INPUT, field)
internal fun unavailable(): Nothing = throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
internal fun <T> required(result: JournalRead<T>): T = when (result) {
    is JournalRead.Value -> result.value
    JournalRead.Unavailable -> throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
}
private fun unresolved(id: OperationId): LocalOperationResult = LocalOperationResult.Unresolved(id, LocalServiceProblem.UNAVAILABLE)
internal fun pendingEntry(entry: StoredClientIntentV1, submitAllowed: Boolean): LocalPendingOperation = LocalPendingOperation(
    operationId = entry.operationId, action = entry.localAction, label = entry.label.ui(), summary = entry.summary.map { it.ui() },
    submitAllowed = submitAllowed && entry.recorded == null &&
        (entry.actionApproval == null || entry.actionApproval.localPhase == LocalActionApprovalPhase.ATTACHED), phase = entry.localPhase,
    recordedDecision = entry.recorded?.let { physicalRecordedDecision(entry, it) },
    actionApproval = entry.actionApproval?.ui(entry),
)
internal fun problem(reason: ReadFailure): LocalServiceProblem = when (reason) {
    ReadFailure.AUTHORIZATION_DENIED, ReadFailure.WRONG_SCOPE -> LocalServiceProblem.DENIED
    ReadFailure.UNKNOWN_QUERY -> LocalServiceProblem.NOT_IMPLEMENTED
    ReadFailure.MALFORMED_REQUEST -> LocalServiceProblem.INVALID_INPUT
    ReadFailure.SERVICE_BUSY -> LocalServiceProblem.RETRY_LATER
    ReadFailure.WRITER_NOT_QUALIFIED -> LocalServiceProblem.UNAVAILABLE
    ReadFailure.STORAGE_UNCERTAIN -> LocalServiceProblem.STORAGE_UNCERTAIN
}
internal fun problem(reason: DeferredReason): LocalServiceProblem = when (reason) {
    DeferredReason.AUTHORIZATION_DENIED, DeferredReason.WRONG_SCOPE -> LocalServiceProblem.DENIED
    DeferredReason.UNKNOWN_COMMAND -> LocalServiceProblem.NOT_IMPLEMENTED
    DeferredReason.MALFORMED_REQUEST -> LocalServiceProblem.INVALID_INPUT
    DeferredReason.SERVICE_BUSY -> LocalServiceProblem.RETRY_LATER
    DeferredReason.WRITER_NOT_QUALIFIED, DeferredReason.HISTORY_INCOMPLETE, DeferredReason.RESOURCE_RECONCILIATION_REQUIRED,
    DeferredReason.RESERVED_OPERATION_NOT_RECORDED, DeferredReason.PRECONDITION_EXPIRED,
    DeferredReason.PHYSICAL_RECORDING_HELD, DeferredReason.CURRENT_SOURCE_UNAVAILABLE -> LocalServiceProblem.UNAVAILABLE
    DeferredReason.STORAGE_UNCERTAIN -> LocalServiceProblem.STORAGE_UNCERTAIN
}
