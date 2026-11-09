package com.ukkera.pos.app

import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.security.LocalSetupCompositionMode
import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.flow.StateFlow

/** Platform composition-root inputs. None of the defaults connect to a server or accept a login. */
data class AppDependencies(
    val branchGateway: BranchUiGateway = UnavailableBranchGateway,
    val centralGateway: CentralUiGateway = UnavailableCentralGateway,
    val platform: PlatformCapabilities,
    val enrollmentGateway: LocalEnrollmentUiGateway = UnavailableLocalEnrollment,
    /** Owner-gated phase; no central sign-in call is made while this remains false. */
    val centralServicesEnabled: Boolean = false,
    /** Actual native binding only. Missing/unsupported delivery never becomes a bytes-as-file fallback. */
    val branchReportFiles: LocalBranchReportFileDelivery = UnavailableLocalBranchReportFiles,
    /** Actual user-selected native input/output; no URI-only or mock production fallback. */
    val documentFiles: LocalPurchasingDocumentFiles = UnavailableLocalPurchasingDocumentFiles,
    /** Presentation/ownership mode; never authority. LIVE keeps existing production behavior. */
    val compositionMode: LocalSetupCompositionMode = LocalSetupCompositionMode.LIVE,
)

data class PlatformCapabilities(
    val family: PlatformFamily,
    val liveBranchHostingQualified: Boolean = false,
    val physicalPeripheralsQualified: Boolean = false,
)

enum class PlatformFamily { ANDROID, IOS, DESKTOP }

/** Presentation only: an installed subset or absent business composition is not a grant. */
enum class LocalSessionWorkspaceMode {
    POLICY_FILTERED, AUTHENTICATION_ONLY, SUPPLIER_ONLY, WAREHOUSE_ONLY, REPORTS_ONLY, STOCK_TRANSFERS_ONLY, KITCHEN_ONLY, RETAIL_ORDERS_ONLY,
    PAYMENT_CORRECTIONS_ONLY, CUSTOMER_RECEIPTS_ONLY, FOOD_CHECKS_ONLY, FOOD_CASH_FINANCIAL_ONLY, FOOD_TABLE_ASSIGNMENTS_ONLY,
    INVOICE_CAPTURE_ONLY, INVOICE_MATCHING_ONLY,
}

/** Capture presentation and its status view only; current Master access remains separate. */
internal fun invoiceCaptureUiRoute(route: AppRoute): Boolean =
    route == AppRoute.Purchasing || route == AppRoute.SessionAccess

/** Matching has the same read/status routes, not capture or other purchasing actions. */
internal fun invoiceMatchingUiRoute(route: AppRoute): Boolean = invoiceCaptureUiRoute(route)

/** A display projection, never an authorization principal, bearer token, or durable financial owner. */
data class SessionDisplayContext(
    val actorId: DomainId,
    val companyId: DomainId,
    val branchId: DomainId?,
    val actorName: String,
    val companyName: String,
    val branchName: String?,
    val allowedAreas: Set<AppArea>,
    /** Ephemeral cleanup hints only; never grants or a persisted policy/credential. */
    val localDraftEpochs: LocalSessionDraftEpochs? = null,
    /** A status explanation, never a service capability, authority or connectivity guarantee. */
    val workspaceMode: LocalSessionWorkspaceMode = LocalSessionWorkspaceMode.POLICY_FILTERED,
) {
    init {
        require(actorName.length in 1..128 && companyName.length in 1..160)
        require(branchName == null || branchName.length in 1..160)
        require(workspaceMode != LocalSessionWorkspaceMode.AUTHENTICATION_ONLY || branchId != null && allowedAreas.isEmpty())
        require(workspaceMode != LocalSessionWorkspaceMode.SUPPLIER_ONLY || branchId != null && allowedAreas.all { it == AppArea.PURCHASING })
        require(workspaceMode != LocalSessionWorkspaceMode.INVOICE_CAPTURE_ONLY || branchId != null && allowedAreas.all { it == AppArea.PURCHASING })
        require(workspaceMode != LocalSessionWorkspaceMode.INVOICE_MATCHING_ONLY || branchId != null && allowedAreas.all { it == AppArea.PURCHASING })
        require(workspaceMode != LocalSessionWorkspaceMode.WAREHOUSE_ONLY || branchId != null && allowedAreas.all { it == AppArea.SETUP })
        require(workspaceMode != LocalSessionWorkspaceMode.REPORTS_ONLY || branchId != null && allowedAreas.all { it == AppArea.REPORTS })
        require(workspaceMode != LocalSessionWorkspaceMode.STOCK_TRANSFERS_ONLY || branchId != null && allowedAreas.all { it == AppArea.STOCK })
        require(workspaceMode != LocalSessionWorkspaceMode.KITCHEN_ONLY || branchId != null && allowedAreas.all { it == AppArea.ORDERS })
        require(workspaceMode != LocalSessionWorkspaceMode.RETAIL_ORDERS_ONLY || branchId != null &&
            allowedAreas.all { it == AppArea.CASHIER || it == AppArea.ORDERS })
        require(workspaceMode != LocalSessionWorkspaceMode.PAYMENT_CORRECTIONS_ONLY || branchId != null &&
            allowedAreas.all { it == AppArea.CASHIER || it == AppArea.ORDERS })
        require(workspaceMode != LocalSessionWorkspaceMode.CUSTOMER_RECEIPTS_ONLY || branchId != null &&
            allowedAreas.all { it == AppArea.CASHIER || it == AppArea.ORDERS })
        require(workspaceMode != LocalSessionWorkspaceMode.FOOD_CHECKS_ONLY || branchId != null &&
            allowedAreas.all { it == AppArea.ORDERS })
        require(workspaceMode != LocalSessionWorkspaceMode.FOOD_CASH_FINANCIAL_ONLY || branchId != null &&
            allowedAreas.all { it == AppArea.ORDERS })
        require(workspaceMode != LocalSessionWorkspaceMode.FOOD_TABLE_ASSIGNMENTS_ONLY || branchId != null &&
            allowedAreas.all { it == AppArea.ORDERS })
    }
}

/**
 * Monotone within one attachment owner. A changed epoch withdraws private input continuity,
 * including when the UI missed an intermediate attachment. Ordinary cashier input does not
 * depend on report/cost grants; all protected projections still require a fresh read. Missing
 * epochs cannot prove continuity across a changed display. These values never authorize work.
 */
data class LocalSessionDraftEpochs(val general: Long, val cashier: Long) {
    init { require(general > 0 && cashier > 0) }
}

sealed interface SignInResult<out T> {
    data class Authenticated<T>(val session: T) : SignInResult<T>
    data class Rejected(val reason: SignInFailure) : SignInResult<Nothing>
}

enum class SignInFailure {
    NOT_CONFIGURED, DENIED, UNAVAILABLE, RETRY_LATER, DEVICE_MOVE_REQUIRED, SESSION_LIMIT,
    AUTHENTICATION_UNRESOLVED, STORAGE_UNCERTAIN,
}

/**
 * Adapter owns credentials, current local policy and the actual master connection. Availability is
 * configuration, not proof of network reachability. The UI clears the supplied char array after use.
 */
interface BranchUiGateway {
    val configured: Boolean
    /** Entry/transport hint only; native authority and Master authorization are unchanged. */
    val signInSecretMaxLength: Int get() = LOCAL_CREDENTIAL_SECRET_MAX_LENGTH
    suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession>
    /** Explicit user consent to replace this account's other sessions, never inferred on retry. */
    suspend fun signIn(account: String, secret: CharArray, explicitDeviceMove: Boolean): SignInResult<BranchUiSession> =
        if (explicitDeviceMove) SignInResult.Rejected(SignInFailure.UNAVAILABLE) else signIn(account, secret)
    /** Counts only; no prior actor/login/credential data may be exposed on the locked screen. */
    suspend fun cleanupStatus(): LocalClientCleanupStatus = LocalClientCleanupStatus(available = false)
    suspend fun retryCleanup(): LocalClientCleanupStatus = LocalClientCleanupStatus(available = false)
}

data class LocalClientCleanupStatus(
    val available: Boolean,
    val pendingRevocations: Int = 0,
    val authenticationUnresolved: Boolean = false,
) {
    init { require(pendingRevocations in 0..64) }
    val clear: Boolean get() = available && pendingRevocations == 0 && !authenticationUnresolved
}

enum class LocalSessionLifecycle { ACTIVE, LOCKED, REAUTHENTICATION_REQUIRED, AUTHORITY_UNAVAILABLE, STORAGE_UNCERTAIN }

/** Presentation identity only: never a credential, permission, connection or native lifetime. */
data class LocalSessionAttachmentKey(val ownerId: DomainId, val generation: Long) {
    init { require(generation > 0) }
}

sealed interface LocalSessionCheckResult {
    /**
     * Captured with this result, not sampled again when the UI receives it. A Current projection
     * belongs to the exact checked attachment; a failure belongs to the attempted attachment.
     * Null is only for a fixed adapter without attachmentUpdates. A replacing adapter must bind
     * every result to its captured key, and report actual session retirement through its lifecycle.
     * This identity never grants permission or revives a retired attachment.
     */
    val attachmentKey: LocalSessionAttachmentKey?

    data class Current(
        val display: SessionDisplayContext,
        override val attachmentKey: LocalSessionAttachmentKey? = null,
    ) : LocalSessionCheckResult
    data class Unavailable(
        val problem: LocalServiceProblem,
        override val attachmentKey: LocalSessionAttachmentKey? = null,
    ) : LocalSessionCheckResult
}

interface CentralUiGateway {
    val configured: Boolean
    suspend fun signIn(account: String, secret: CharArray): SignInResult<CentralUiSession>
}

/**
 * UI connection capability returned only after real local authentication. Never serialize this.
 * Implementations must deny revoked sessions and reauthorize each read/command independently.
 * close() immediately invalidates the local interactive capability; remote cleanup may be queued.
 * It must not stop the master service or delete/relabel pending operation evidence.
 */
interface BranchUiSession {
    val display: SessionDisplayContext
    val workflows: LocalWorkflowGateway get() = UnavailableLocalWorkflows
    val administration: LocalAdministrationUiGateway get() = UnavailableLocalAdministration
    /** Explicit head-authorized company publication, separate from this employee's activation permission. */
    val companyCatalog: LocalCompanyCatalogGateway get() = UnavailableLocalCompanyCatalog
    /** Installed production setup ACK/checkpoints/go-live; absent providers stay explicitly unavailable. */
    val setupClosure: LocalSetupClosureUiGateway get() = UnavailableLocalSetupClosure
    /** Concrete same-endpoint/native-owner binding only; configuration is never an action grant. */
    val retailPhysical: LocalRetailPhysicalGateway get() = UnavailableLocalRetailPhysical
    /** Null only for an adapter without lifecycle monitoring; never a proof that it is current. */
    val lifecycle: StateFlow<LocalSessionLifecycle>? get() = null
    /**
     * A replacing adapter publishes a new generation only after a protected Current (and durable
     * rotated credentials if renewed), and, when replacing a native connection, confirmed old close and
     * genuinely fresh native connection. In-process token rotation does not stop the master.
     * It denies old adapters synchronously BEFORE replacement. A pending transition keeps the
     * previous key (not its authority); null means the interactive session ended. Generations
     * strictly increase within this owner; UI consumers may miss intermediate flow emissions.
     * Null flow means this adapter never replaces its captured connection/adapters in place.
     */
    val attachmentUpdates: StateFlow<LocalSessionAttachmentKey?>? get() = null
    val attachmentKey: LocalSessionAttachmentKey? get() = attachmentUpdates?.value
    /**
     * Synchronous sample of this exact local interactive capability and its captured connection.
     * Check again after asynchronous delivery, immediately before adopting its display. This is
     * not protected authorization or a promise of future lifetime; each operation still checks
     * current authority independently. Missing support is fail-closed, not an ACTIVE-flow guess.
     */
    fun isCurrent(): Boolean = false
    /** Actual protected current read; an adapter may perform one durably tracked refresh. */
    suspend fun current(): LocalSessionCheckResult = LocalSessionCheckResult.Unavailable(LocalServiceProblem.NOT_IMPLEMENTED)
    suspend fun refresh(): LocalSessionCheckResult = LocalSessionCheckResult.Unavailable(LocalServiceProblem.NOT_IMPLEMENTED)
    suspend fun mayRestore(route: AppRoute): Boolean
    fun close()
    /**
     * Revocation-only cleanup remains callable after close() invalidates interactive access.
     * The adapter owns durable revocation intent/credentials, including an unknown reply.
     * UI navigation/lock must never wait for this, nor interpret UNRESOLVED as revoked.
     */
    suspend fun logout(): LocalSessionEndResult = LocalSessionEndResult.UNAVAILABLE
}

enum class LocalSessionEndResult { REVOKED, UNRESOLVED, UNAVAILABLE }

enum class LocalEnrollmentMode { DISABLED, LOCAL_QUALIFICATION_ONLY, OWNER_AUTHORIZED_LOCAL }
data class LocalEnrollmentStatus(
    val mode: LocalEnrollmentMode,
    val available: Boolean,
    val context: LocalText? = null,
    /** Durable adapter-owned uncertain ceremony, discoverable again after a client restart. */
    val pendingRequestId: DomainId? = null,
    /** The endpoint may provision an identifier without a separate employee display-name field. */
    val supportsDisplayName: Boolean = true,
)
sealed interface LocalEnrollmentResult {
    /** Credential administration durably committed; this is NOT an authenticated session. */
    data object Created : LocalEnrollmentResult
    /** A definitive no-commit/rejected result; an unknown write must use Unresolved instead. */
    data class Rejected(val problem: LocalServiceProblem) : LocalEnrollmentResult
    data object Unresolved : LocalEnrollmentResult
}

/**
 * Explicitly injected local provisioning adapter. No empty-directory/default-admin fallback.
 * Current authority, branch/device binding, credential policy and replay identity belong to the
 * service. The adapter durably tracks the original request ID/definition and unresolved ceremony;
 * it must never repeat provisioning under a fresh ID after uncertainty. Passwords are transient
 * buffers and may not enter workflow records, drafts or logs. lookup must not require a password.
 */
interface LocalEnrollmentUiGateway {
    suspend fun status(): LocalEnrollmentStatus
    suspend fun enroll(requestId: DomainId, account: String, displayName: String, password: CharArray): LocalEnrollmentResult
    suspend fun lookup(requestId: DomainId): LocalEnrollmentResult
}

object UnavailableLocalEnrollment : LocalEnrollmentUiGateway {
    override suspend fun status() = LocalEnrollmentStatus(LocalEnrollmentMode.DISABLED, available = false)
    override suspend fun enroll(requestId: DomainId, account: String, displayName: String, password: CharArray) =
        LocalEnrollmentResult.Rejected(LocalServiceProblem.NOT_CONFIGURED)
    override suspend fun lookup(requestId: DomainId) = LocalEnrollmentResult.Unresolved
}

/** Central human context is intentionally not interchangeable with a branch-local session. */
interface CentralUiSession {
    val display: SessionDisplayContext
    suspend fun mayRestore(route: AppRoute): Boolean
    fun close()
}

private object UnavailableBranchGateway : BranchUiGateway {
    override val configured = false
    override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> =
        SignInResult.Rejected(SignInFailure.NOT_CONFIGURED)
}

private object UnavailableCentralGateway : CentralUiGateway {
    override val configured = false
    override suspend fun signIn(account: String, secret: CharArray): SignInResult<CentralUiSession> =
        SignInResult.Rejected(SignInFailure.NOT_CONFIGURED)
}
