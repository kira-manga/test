package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.BranchUiGateway
import com.ukkera.pos.app.BranchUiSession
import com.ukkera.pos.app.LocalClientCleanupStatus
import com.ukkera.pos.app.LocalAdministrationUiGateway
import com.ukkera.pos.app.LocalCompanyCatalogGateway
import com.ukkera.pos.app.UnavailableLocalAdministration
import com.ukkera.pos.app.UnavailableLocalCompanyCatalog
import com.ukkera.pos.app.LocalFoodUiQuery
import com.ukkera.pos.app.LocalReadResult
import com.ukkera.pos.app.LocalRecordKind
import com.ukkera.pos.app.LocalRecordReference
import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.app.LocalSessionCheckResult
import com.ukkera.pos.app.LocalSessionEndResult
import com.ukkera.pos.app.LocalSessionLifecycle
import com.ukkera.pos.app.LocalSessionAttachmentKey
import com.ukkera.pos.app.LocalSessionDraftEpochs
import com.ukkera.pos.app.LocalSessionWorkspaceMode
import com.ukkera.pos.app.LocalWorkflow
import com.ukkera.pos.app.LocalWorkflowGateway
import com.ukkera.pos.app.UnavailableLocalWorkflows
import com.ukkera.pos.app.LocalWorkflowQuery
import com.ukkera.pos.app.LocalRetailPhysicalGateway
import com.ukkera.pos.app.LocalRetailPhysicalDenial
import com.ukkera.pos.app.LocalRetailPhysicalLifetime
import com.ukkera.pos.app.UnavailableLocalRetailPhysical
import com.ukkera.pos.app.SessionDisplayContext
import com.ukkera.pos.app.SignInFailure
import com.ukkera.pos.app.SignInResult
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.app.navigation.RouteRegistry
import com.ukkera.pos.app.navigation.SecurityRoot
import com.ukkera.pos.database.LocalMasterClient
import com.ukkera.pos.database.LocalMasterAdministrationClient
import com.ukkera.pos.database.LocalMasterEndpointHealth
import com.ukkera.pos.database.LocalMasterRpcWorkflowProfile
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.CompanyScope
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.LocalAuthenticatedPeer
import com.ukkera.pos.security.LocalAuthenticationDenial
import com.ukkera.pos.security.LocalAuthenticationResult
import com.ukkera.pos.security.LocalAuthenticationService
import com.ukkera.pos.security.LocalCompanyBusinessPublications
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.LocalSessionView
import com.ukkera.pos.security.LocalSessionViewResult
import com.ukkera.pos.security.LocalSignOutResult
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Real configured display metadata, or explicitly labeled IDs supplied by the root; not grants. */
public class LocalBranchDisplayLabels(public val scope: BranchScope, public val companyName: String, public val branchName: String) {
    init { require(companyName.length in 1..160 && branchName.length in 1..160) }
}

/**
 * The root binds one ACTUAL appointed-master client/incarnation to one current authenticated peer.
 * Publish null before maintenance, then a NEW instance after a qualified new incarnation. Never
 * construct this from a login body, guessed device/connection UUID or UI "hosting enabled" flag.
 * The lifetime closure must check the captured host status AND the same physical peer lifetime.
 * A client interface or health flow does not establish those facts or confer enrollment capability.
 */
public class LocalMasterClientConnection(
    public val endpoint: LocalMasterClient,
    public val peer: LocalAuthenticatedPeer,
    public val serviceIncarnationId: DomainId,
    public val installed: LocalWorkflowInstallation,
    override public val labels: LocalBranchDisplayLabels,
    public val retailPhysicalBinding: LocalRetailPhysicalBinding?,
    public val administrationClient: LocalMasterAdministrationClient?,
    public val companyPublications: LocalCompanyBusinessPublications?,
    private val currentLifetime: () -> Boolean,
) : LocalAuthenticationClientConnection {
    /** Older roots do not acquire company authority by adding an employee UI property. */
    public constructor(endpoint: LocalMasterClient, peer: LocalAuthenticatedPeer, serviceIncarnationId: DomainId,
        installed: LocalWorkflowInstallation, labels: LocalBranchDisplayLabels, retailPhysicalBinding: LocalRetailPhysicalBinding?,
        administrationClient: LocalMasterAdministrationClient?, currentLifetime: () -> Boolean) :
        this(endpoint, peer, serviceIncarnationId, installed, labels, retailPhysicalBinding, administrationClient, null, currentLifetime)
    /** Explicit absent administration for an older root; an interface cast never installs it. */
    public constructor(endpoint: LocalMasterClient, peer: LocalAuthenticatedPeer, serviceIncarnationId: DomainId,
        installed: LocalWorkflowInstallation, labels: LocalBranchDisplayLabels, retailPhysicalBinding: LocalRetailPhysicalBinding?,
        currentLifetime: () -> Boolean) : this(endpoint, peer, serviceIncarnationId, installed, labels, retailPhysicalBinding, null, null, currentLifetime)
    /** Preserve the old constructor/trailing-lambda form; absent native binding stays unavailable. */
    public constructor(endpoint: LocalMasterClient, peer: LocalAuthenticatedPeer, serviceIncarnationId: DomainId,
        installed: LocalWorkflowInstallation, labels: LocalBranchDisplayLabels, currentLifetime: () -> Boolean) :
        this(endpoint, peer, serviceIncarnationId, installed, labels, null, null, null, currentLifetime)

    init {
        require(labels.scope == peer.scope)
        require(retailPhysicalBinding == null || retailPhysicalBinding.endpoint === endpoint)
        require(administrationClient == null || administrationClient === endpoint)
        require(companyPublications == null || companyPublications.trust.company ==
            CompanyScope(peer.scope.organizationId, peer.scope.companyId))
    }
    override public val authentication: LocalAuthenticationService get() = endpoint
    override public val connectionId: DomainId get() = peer.connectionId
    override public val identity: LocalClientDeviceIdentity = LocalClientDeviceIdentity(peer.scope, peer.deviceId)
    override public val credentialBinding: LocalClientAuthenticationBinding =
        LocalClientAuthenticationBinding.MasterServiceIncarnation(serviceIncarnationId)

    override public fun isCurrent(): Boolean = try {
        endpoint.health.value == LocalMasterEndpointHealth.ACTIVE && peer.isCurrent() && currentLifetime()
    } catch (_: Exception) { false }
    override fun toString(): String = "LocalMasterClientConnection([REDACTED])"
}

/**
 * Root-owned already-open journals only. The root retains/releases their file locks independently
 * of a UI session, and must return the exact actor/device/scope journal without creating a missing
 * one. A failed lookup is unavailable, not permission to initialize empty pending evidence.
 */
public fun interface LocalClientIntentJournalSource {
    public suspend fun current(owner: LocalClientIdentity): LocalClientIntentJournal?
}

/**
 * Optional genuine native actor-custody acquisition after interactive authentication. The native
 * owner captures its actual original Client and an already explicit enrollment/reopen/recovery
 * selection, rechecks actual Master Current and qualifies the actor journal. Caller fields and a
 * missing file never authorize enrollment. The borrowed credential must not be retained; current
 * still selects only the same already-open journal, without initialization or history fallback.
 */
public interface LocalAuthenticatedClientIntentJournalSource : LocalClientIntentJournalSource {
    public suspend fun acquireAuthenticated(owner: LocalClientIdentity, credentials: LocalGatewayCredentials): LocalClientIntentJournal?
}

/**
 * Client of actual master authentication, not a session/principal simulator. The full constructor
 * installs real business composition; directoryOnly fixes one supported business profile
 * and authenticationOnly explicitly installs none.
 * Sign-in/refresh replies are persisted by a bounded client-owned worker even when Compose stops
 * waiting. Durable intent precedes dispatch; an unknown refresh is NEVER retried. On process
 * restart retained tokens provide revocation only: this adapter has no restore-from-token API.
 * Root owns the supplied journals/connections. close() never stops or opens a master store.
 */
public class LocalEndpointBranchGateway private constructor(
    private val connections: StateFlow<LocalAuthenticationClientConnection?>,
    private val credentials: LocalClientCredentialJournal,
    private val intents: LocalClientIntentJournalSource?,
    private val composition: Composition,
    dispatcher: CoroutineDispatcher,
    private val directoryProfile: LocalMasterRpcWorkflowProfile? = null,
) : BranchUiGateway {
    /** Existing roots retain their full endpoint/registry and exact actor intent-journal requirement. */
    public constructor(connections: StateFlow<LocalMasterClientConnection?>, credentials: LocalClientCredentialJournal,
        intents: LocalClientIntentJournalSource, dispatcher: CoroutineDispatcher = Dispatchers.Default) :
        this(connections, credentials, intents, Composition.FULL_BUSINESS, dispatcher)

    private enum class Composition { FULL_BUSINESS, DIRECTORY_ONLY, AUTHENTICATION_ONLY }

    private class BusinessAttachment(val endpoint: LocalMasterClient, val installed: LocalWorkflowInstallation,
        val journal: LocalClientIntentJournal, val fullConnection: LocalMasterClientConnection? = null)

    private val closed = MutableStateFlow(false)
    private val signingIn = MutableStateFlow(false)
    private val active = MutableStateFlow<EndpointSession?>(null)
    // Retain failed original denials after the interactive session pointer is removed.
    // Only a successful negative drain may release a session's attachment.
    private val physicalLifetimes = LocalRetailPhysicalLifetime()
    private val failedPhysicalDenials = MutableStateFlow<Set<EndpointSession>>(emptySet())
    // One successful caller cannot admit a successor while an overlapping exact
    // denial can still fail. This fences this client only, not other Master users.
    private val physicalDrains = MutableStateFlow(0)
    private val workJob = SupervisorJob()
    private val work = CoroutineScope(workJob + dispatcher)
    private val admission = Semaphore(16)
    override public val configured: Boolean get() = !closed.value && physicalDrains.value == 0 && failedPhysicalDenials.value.isEmpty()
    override public val signInSecretMaxLength: Int get() = when (composition) {
        Composition.FULL_BUSINESS -> 256
        Composition.DIRECTORY_ONLY, Composition.AUTHENTICATION_ONLY -> 1024
    }

    init {
        require((composition == Composition.DIRECTORY_ONLY) == (directoryProfile != null))
        work.launch {
            connections.collectLatest { connection ->
                active.value?.let { if (!it.bindingCurrent()) it.end(LocalSessionLifecycle.AUTHORITY_UNAVAILABLE) }
                val endpoint = when (connection) {
                    is LocalMasterClientConnection -> connection.endpoint
                    is LocalDirectoryClientConnection -> connection.endpoint
                    else -> null
                }
                endpoint?.health?.collect { health ->
                    if (health != LocalMasterEndpointHealth.ACTIVE)
                        active.value?.takeIf { it.connection === connection }?.end(LocalSessionLifecycle.AUTHORITY_UNAVAILABLE)
                }
            }
        }
        // No network/credential renewal here. This bounds local privacy exposure when a peer's
        // synchronous lifetime expires without a flow emission. UI current polling is separate.
        work.launch { while (true) {
            delay(1_000)
            active.value?.let { if (!it.bindingCurrent()) it.end(LocalSessionLifecycle.AUTHORITY_UNAVAILABLE) }
        } }
    }

    override public suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> = signIn(account, secret, false)

    override public suspend fun signIn(account: String, secret: CharArray, explicitDeviceMove: Boolean): SignInResult<BranchUiSession> {
        if (!signingIn.compareAndSet(false, true)) {
            secret.fill('\u0000')
            return SignInResult.Rejected(SignInFailure.RETRY_LATER)
        }
        var owned: CharArray? = null
        try {
            if (secret.size !in 4..signInSecretMaxLength) return SignInResult.Rejected(SignInFailure.DENIED)
            val password = secret.copyOf().also { owned = it }
            val result = ownedWork(
                unavailable = { SignInResult.Rejected(SignInFailure.UNAVAILABLE) },
                dispose = { result: SignInResult<BranchUiSession> -> if (result is SignInResult.Authenticated) result.session.close() },
                finished = { password.fill('\u0000'); signingIn.value = false },
            ) { credentials.requests.withLock { signInOwned(account, password, explicitDeviceMove) } }
            // The owned worker's result may have waited for this caller's dispatcher after its
            // connection/session retired. Close only the local capability; durable cleanup stays.
            if (result is SignInResult.Authenticated && !result.session.isCurrent()) {
                result.session.close()
                return SignInResult.Rejected(SignInFailure.UNAVAILABLE)
            }
            return result
        } finally {
            secret.fill('\u0000')
            // The worker owns its copy until its completion, NOT until this caller stops waiting.
            if (owned == null) signingIn.value = false
        }
    }

    private suspend fun signInOwned(account: String, password: CharArray, explicitMove: Boolean): SignInResult<BranchUiSession> {
        val rejected: (SignInFailure) -> SignInResult<BranchUiSession> = { SignInResult.Rejected(it) }
        val login = try { LocalLoginIdentifier(account) } catch (_: IllegalArgumentException) { return rejected(SignInFailure.DENIED) }
        if (!configured) return rejected(SignInFailure.UNAVAILABLE)
        if (active.value != null) return rejected(SignInFailure.RETRY_LATER)
        val connection = currentConnection() ?: return rejected(SignInFailure.UNAVAILABLE)
        return snapshot { before ->
            if (before.attempts.size + before.sessions.size >= MAX_CLIENT_AUTH_RECORDS) return@snapshot rejected(SignInFailure.RETRY_LATER)
            if (!explicitMove && (before.attempts.any { it.login == login } || before.sessions.any {
                    it.login == login && it.phase == StoredSessionPhaseV1.REFRESH_PENDING }))
                return@snapshot rejected(SignInFailure.AUTHENTICATION_UNRESOLVED)
            val attempt = StoredLoginAttemptV1(DomainId.random(), login, connection.credentialBinding, explicitMove)
            val pending = before.next(attempts = before.attempts + attempt)
            if (!durable(before, pending)) return@snapshot rejected(SignInFailure.STORAGE_UNCERTAIN)
            if (!connectionCurrent(connection)) return@snapshot rejected(SignInFailure.AUTHENTICATION_UNRESOLVED)
            when (val result = connection.authentication.signIn(connection.connectionId, login, password, explicitMove)) {
                is LocalAuthenticationResult.Denied -> {
                    val completed = pending.next(attempts = pending.attempts.filterNot { it.id == attempt.id })
                    if (!durable(pending, completed)) rejected(SignInFailure.STORAGE_UNCERTAIN)
                    else rejected(when (result.reason) {
                        LocalAuthenticationDenial.AUTHENTICATION_REFUSED -> SignInFailure.DENIED
                        LocalAuthenticationDenial.THROTTLED, LocalAuthenticationDenial.SERVICE_BUSY -> SignInFailure.RETRY_LATER
                        LocalAuthenticationDenial.EXPLICIT_DEVICE_MOVE_REQUIRED -> SignInFailure.DEVICE_MOVE_REQUIRED
                        LocalAuthenticationDenial.SESSION_LIMIT -> SignInFailure.SESSION_LIMIT
                    })
                }
                LocalAuthenticationResult.Unavailable -> rejected(SignInFailure.AUTHENTICATION_UNRESOLVED)
                LocalAuthenticationResult.StorageUncertain -> rejected(SignInFailure.STORAGE_UNCERTAIN)
                is LocalAuthenticationResult.Authenticated -> {
                    val tokens = result.tokens
                    var retained: StoredClientSessionV1? = null
                    var unclaimed: EndpointSession? = null
                    try {
                        if (!matches(tokens.view, connection, login)) return@snapshot rejected(SignInFailure.AUTHENTICATION_UNRESOLVED)
                        val row = StoredClientSessionV1.capture(DomainId.random(), connection.credentialBinding, tokens).also { retained = it }
                        row.validate(credentials.owner)
                        // A successful move fences prior refreshes for the SAME actor. It cannot
                        // prove that an older queued PASSWORD login has reached server capture;
                        // such unknown login attempts remain unresolved, even after this success.
                        val completed = pending.next(attempts = pending.attempts.filterNot { it.id == attempt.id },
                            sessions = pending.sessions.filterNot { explicitMove && it.identity == row.identity } + row)
                        val persisted = withContext(NonCancellable) { durable(pending, completed) }
                        if (!persisted) return@snapshot rejected(SignInFailure.STORAGE_UNCERTAIN)
                        currentCoroutineContext().ensureActive()
                        if (!connectionCurrent(connection)) return@snapshot rejected(SignInFailure.UNAVAILABLE)
                        val verified = currentView(connection, tokens.access)
                        if (verified !is LocalSessionViewResult.Current || !sameSession(row, verified.view))
                            return@snapshot rejected(SignInFailure.UNAVAILABLE)
                        val business = if (composition == Composition.AUTHENTICATION_ONLY) null else
                            businessAttachment(connection, row.identity, tokens.access) ?: return@snapshot rejected(SignInFailure.UNAVAILABLE)
                        val finalView = if (composition == Composition.DIRECTORY_ONLY) {
                            // Native acquisition may suspend. Do not publish its preceding Current
                            // or stale policy projection as a newly acquired interactive attachment.
                            val confirmed = currentView(connection, tokens.access)
                            if (confirmed !is LocalSessionViewResult.Current || !sameSession(row, confirmed.view))
                                return@snapshot rejected(SignInFailure.UNAVAILABLE)
                            confirmed.view
                        } else verified.view
                        if (!connectionCurrent(connection)) return@snapshot rejected(SignInFailure.UNAVAILABLE)
                        val display = display(finalView, connection.labels, business?.fullConnection?.administrationClient != null,
                            workspaceMode = workspaceMode(business))
                        // Master-confirmed authentication does not require an operational grant.
                        // An empty projection uses the session-only UI; every protected operation
                        // still requires the master's current authorization, including recovery.
                        val session = EndpointSession(connection, row.localId, row.identity, row.login, finalView.policy, display, business)
                        unclaimed = session
                        if (!active.compareAndSet(null, session)) return@snapshot rejected(SignInFailure.RETRY_LATER)
                        currentCoroutineContext().ensureActive()
                        if (!session.bindingCurrent()) return@snapshot rejected(SignInFailure.UNAVAILABLE)
                        unclaimed = null
                        SignInResult.Authenticated(session)
                    } finally {
                        // A failed original denial must not strand credential buffers.
                        try { unclaimed?.close() }
                        finally { try { retained?.destroy() } finally { tokens.destroy() } }
                    }
                }
            }
        }
    }

    private suspend fun businessAttachment(connection: LocalAuthenticationClientConnection, owner: LocalClientIdentity,
        access: OpaqueCredential): BusinessAttachment? {
        val source = intents ?: return null
        if (!connectionCurrent(connection)) return null
        if (directoryProfile in setOf(LocalMasterRpcWorkflowProfile.WAREHOUSES, LocalMasterRpcWorkflowProfile.REPORTS,
                LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS, LocalMasterRpcWorkflowProfile.KITCHEN,
                LocalMasterRpcWorkflowProfile.FOOD_CHECKS, LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL,
                LocalMasterRpcWorkflowProfile.FOOD_TABLE_ASSIGNMENTS,
                LocalMasterRpcWorkflowProfile.RETAIL_ORDERS, LocalMasterRpcWorkflowProfile.PAYMENT_CORRECTIONS,
                LocalMasterRpcWorkflowProfile.CUSTOMER_RECEIPTS, LocalMasterRpcWorkflowProfile.INVOICE_CAPTURE,
                LocalMasterRpcWorkflowProfile.INVOICE_MATCHING) &&
            source !is LocalAuthenticatedClientIntentJournalSource)
            return null
        val acquired = if (composition == Composition.DIRECTORY_ONLY && source is LocalAuthenticatedClientIntentJournalSource) {
            val borrowed = ownedCredential(connection, access)
            val journal = try { source.acquireAuthenticated(owner, borrowed) } finally { borrowed.bearer.destroy() }
            if (!connectionCurrent(connection) || journal == null || journal.owner != owner) return null
            journal
        } else null
        if (!connectionCurrent(connection)) return null
        val journal = source.current(owner) ?: return null
        if (!connectionCurrent(connection) || journal.owner != owner || acquired != null && journal !== acquired) return null
        return when (composition) {
            Composition.FULL_BUSINESS -> (connection as? LocalMasterClientConnection)?.let {
                BusinessAttachment(it.endpoint, it.installed, journal, it)
            }
            Composition.DIRECTORY_ONLY -> (connection as? LocalDirectoryClientConnection)?.takeIf { it.profile == directoryProfile }?.let {
                BusinessAttachment(it.endpoint, it.installed, journal)
            }
            Composition.AUTHENTICATION_ONLY -> null
        }
    }

    private fun workspaceMode(business: BusinessAttachment?): LocalSessionWorkspaceMode = when {
        business == null -> LocalSessionWorkspaceMode.AUTHENTICATION_ONLY
        directoryProfile == LocalMasterRpcWorkflowProfile.SUPPLIERS -> LocalSessionWorkspaceMode.SUPPLIER_ONLY
        directoryProfile == LocalMasterRpcWorkflowProfile.WAREHOUSES -> LocalSessionWorkspaceMode.WAREHOUSE_ONLY
        directoryProfile == LocalMasterRpcWorkflowProfile.REPORTS -> LocalSessionWorkspaceMode.REPORTS_ONLY
        directoryProfile == LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS -> LocalSessionWorkspaceMode.STOCK_TRANSFERS_ONLY
        directoryProfile == LocalMasterRpcWorkflowProfile.KITCHEN -> LocalSessionWorkspaceMode.KITCHEN_ONLY
        directoryProfile == LocalMasterRpcWorkflowProfile.FOOD_CHECKS -> LocalSessionWorkspaceMode.FOOD_CHECKS_ONLY
        directoryProfile == LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL -> LocalSessionWorkspaceMode.FOOD_CASH_FINANCIAL_ONLY
        directoryProfile == LocalMasterRpcWorkflowProfile.FOOD_TABLE_ASSIGNMENTS -> LocalSessionWorkspaceMode.FOOD_TABLE_ASSIGNMENTS_ONLY
        directoryProfile == LocalMasterRpcWorkflowProfile.RETAIL_ORDERS -> LocalSessionWorkspaceMode.RETAIL_ORDERS_ONLY
        directoryProfile == LocalMasterRpcWorkflowProfile.PAYMENT_CORRECTIONS -> LocalSessionWorkspaceMode.PAYMENT_CORRECTIONS_ONLY
        directoryProfile == LocalMasterRpcWorkflowProfile.CUSTOMER_RECEIPTS -> LocalSessionWorkspaceMode.CUSTOMER_RECEIPTS_ONLY
        directoryProfile == LocalMasterRpcWorkflowProfile.INVOICE_CAPTURE -> LocalSessionWorkspaceMode.INVOICE_CAPTURE_ONLY
        directoryProfile == LocalMasterRpcWorkflowProfile.INVOICE_MATCHING -> LocalSessionWorkspaceMode.INVOICE_MATCHING_ONLY
        else -> LocalSessionWorkspaceMode.POLICY_FILTERED
    }

    override public suspend fun cleanupStatus(): LocalClientCleanupStatus = ownedWork({ unavailableCleanup() }) {
        credentials.requests.withLock { snapshot { cleanup(it) } }
    }

    override public suspend fun retryCleanup(): LocalClientCleanupStatus = ownedWork({ unavailableCleanup() }) {
        credentials.requests.withLock {
            val ids = snapshot { it.sessions.filterNot { row -> active.value?.localId == row.localId }.map { it.localId } }
            for (id in ids) {
                if (closed.value) break
                revokeOwned(id)
            }
            snapshot { cleanup(it) }
        }
    }

    private fun cleanup(state: StoredClientCredentials): LocalClientCleanupStatus = LocalClientCleanupStatus(
        available = true,
        pendingRevocations = state.sessions.count { it.localId != active.value?.localId && !it.originalRevoked },
        authenticationUnresolved = state.attempts.isNotEmpty() || state.sessions.any { it.phase == StoredSessionPhaseV1.REFRESH_PENDING },
    )

    private suspend fun revokeOwned(localId: DomainId): LocalSessionEndResult = snapshot { before ->
        if (active.value?.localId == localId) return@snapshot LocalSessionEndResult.UNAVAILABLE
        val original = before.sessions.singleOrNull { it.localId == localId } ?: return@snapshot LocalSessionEndResult.REVOKED
        if (original.originalRevoked) return@snapshot LocalSessionEndResult.UNRESOLVED
        val connection = currentConnection() ?: return@snapshot LocalSessionEndResult.UNAVAILABLE
        val rotationUnknown = original.phase == StoredSessionPhaseV1.REFRESH_PENDING
        val row = if (rotationUnknown) original else original.phase(StoredSessionPhaseV1.REVOCATION_PENDING)
        val pending = before.next(sessions = before.sessions.map { if (it.localId == localId) row else it })
        if (!durable(before, pending)) return@snapshot LocalSessionEndResult.UNRESOLVED
        if (!connectionCurrent(connection)) return@snapshot LocalSessionEndResult.UNRESOLVED
        val credential = ownedCredential(connection, row.refresh)
        val reply = try { connection.authentication.logout(credential) } finally { credential.bearer.destroy() }
        if (reply != LocalSignOutResult.Revoked) return@snapshot LocalSessionEndResult.UNRESOLVED
        // Old-token Revoked is original-session-only. Never drop an unknown successor marker.
        val completed = pending.next(sessions = if (rotationUnknown)
            pending.sessions.map { if (it.localId == localId) it.phase(StoredSessionPhaseV1.REFRESH_PENDING, true) else it }
            else pending.sessions.filterNot { it.localId == localId })
        if (!withContext(NonCancellable) { durable(pending, completed) } || rotationUnknown) LocalSessionEndResult.UNRESOLVED
        else LocalSessionEndResult.REVOKED
    }

    /** The display and attachment are captured beside the checked credential, before delivery. */
    private class CheckedCredential(
        val lease: LocalWorkflowCredentialLease,
        val display: SessionDisplayContext,
        val attachment: LocalSessionAttachmentKey,
    )

    private suspend fun acquire(session: EndpointSession, refreshRequestedFor: LocalSessionAttachmentKey? = null,
        allowRefresh: Boolean = true, expectedAttachment: LocalSessionAttachmentKey? = null): CheckedCredential? =
        ownedWork(unavailable = { null }, dispose = { value: CheckedCredential? -> value?.lease?.destroy() }) {
            credentials.requests.withLock {
                try {
                    if (!session.interactive() || expectedAttachment != null && !session.attachmentCurrent(expectedAttachment))
                        return@withLock null
                    val checked = snapshot { state ->
                        val row = state.sessions.singleOrNull { it.localId == session.localId }
                        if (row == null || row.identity != session.identity || row.phase != StoredSessionPhaseV1.RETAINED ||
                            row.binding != session.connection.credentialBinding) {
                            session.end(LocalSessionLifecycle.REAUTHENTICATION_REQUIRED); return@snapshot null
                        }
                        // Concurrent explicit refresh requests for one attachment coalesce. A
                        // queued request cannot rotate the successor merely by arriving later.
                        val forceRefresh = refreshRequestedFor != null && refreshRequestedFor == session.attachmentKey
                        when (val view = if (forceRefresh) LocalSessionViewResult.Denied else currentView(session.connection, row.access)) {
                            is LocalSessionViewResult.Current -> {
                                if (!sameSession(row, view.view) || !session.acceptPolicy(view.view.policy)) {
                                    session.end(LocalSessionLifecycle.REAUTHENTICATION_REQUIRED); null
                                } else lease(session, row.access)
                            }
                            LocalSessionViewResult.Denied -> if (allowRefresh) refreshOwned(session, state, row)
                                else { session.end(LocalSessionLifecycle.REAUTHENTICATION_REQUIRED); null }
                            LocalSessionViewResult.StorageUncertain -> { session.end(LocalSessionLifecycle.STORAGE_UNCERTAIN); null }
                            LocalSessionViewResult.Unavailable, LocalSessionViewResult.Busy -> {
                                session.end(LocalSessionLifecycle.AUTHORITY_UNAVAILABLE); null
                            }
                        }
                    }
                    // A cached gateway is never upgraded implicitly. If its access check
                    // renewed credentials, only newly published adapters can use the result.
                    if (checked != null && expectedAttachment != null && checked.attachment != expectedAttachment) {
                        checked.lease.destroy()
                        null
                    } else checked
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { session.end(LocalSessionLifecycle.AUTHORITY_UNAVAILABLE); null }
            }
        }

    private suspend fun refreshOwned(session: EndpointSession, before: StoredClientCredentials, original: StoredClientSessionV1): CheckedCredential? {
        if (!session.interactive()) return null
        session.invalidateLeases()
        val pending = before.next(sessions = before.sessions.map {
            if (it.localId == original.localId) it.phase(StoredSessionPhaseV1.REFRESH_PENDING) else it
        })
        if (!durable(before, pending)) { session.end(LocalSessionLifecycle.STORAGE_UNCERTAIN); return null }
        if (!session.interactive()) return null // pending remains conservative; never automatically resend it
        val credential = ownedCredential(session.connection, original.refresh)
        val result = try { session.connection.authentication.refresh(credential) } finally { credential.bearer.destroy() }
        if (result !is LocalAuthenticationResult.Authenticated) {
            session.end(if (result == LocalAuthenticationResult.StorageUncertain) LocalSessionLifecycle.STORAGE_UNCERTAIN
                else LocalSessionLifecycle.REAUTHENTICATION_REQUIRED)
            return null // even old-token logout success cannot resolve this marker
        }
        var retained: StoredClientSessionV1? = null
        try {
            val view = result.tokens.view
            if (!matches(view, session.connection, original.login) || view.actorId != original.identity.actorId ||
                view.sessionId == original.sessionId || view.generation.value <= original.generation.value ||
                view.workExpiresAt != original.workExpiresAt) {
                session.end(LocalSessionLifecycle.REAUTHENTICATION_REQUIRED); return null
            }
            val row = StoredClientSessionV1.capture(original.localId, original.binding, result.tokens).also { retained = it }
            row.validate(credentials.owner)
            val completed = pending.next(sessions = pending.sessions.map { if (it.localId == original.localId) row else it })
            if (!withContext(NonCancellable) { durable(pending, completed) }) {
                session.end(LocalSessionLifecycle.STORAGE_UNCERTAIN); return null
            }
            if (!session.interactive()) {
                session.end(LocalSessionLifecycle.REAUTHENTICATION_REQUIRED); return null
            }
            when (val verified = currentView(session.connection, row.access)) {
                is LocalSessionViewResult.Current -> if (sameSession(row, verified.view) &&
                    session.acceptPolicy(verified.view.policy, renewed = true)) return lease(session, row.access)
                else -> Unit
            }
            session.end(LocalSessionLifecycle.REAUTHENTICATION_REQUIRED)
            return null
        } finally { retained?.destroy(); result.tokens.destroy() }
    }

    private fun lease(session: EndpointSession, access: OpaqueCredential): CheckedCredential? {
        val attachment = session.attachmentKey ?: return null
        if (!session.attachmentCurrent(attachment)) return null
        val fence = session.leaseFence.value
        val lease = LocalWorkflowCredentialLease(ownedCredential(session.connection, access)) {
            session.attachmentCurrent(attachment) && session.leaseFence.value === fence
        }
        return CheckedCredential(lease, session.display, attachment)
    }

    private suspend fun currentView(connection: LocalAuthenticationClientConnection, access: OpaqueCredential): LocalSessionViewResult {
        if (!connectionCurrent(connection)) return LocalSessionViewResult.Unavailable
        val credential = ownedCredential(connection, access)
        return try { connection.authentication.current(credential) } finally { credential.bearer.destroy() }
    }

    private fun currentConnection(): LocalAuthenticationClientConnection? = connections.value?.takeIf { connectionCurrent(it) }
    private fun connectionCurrent(value: LocalAuthenticationClientConnection): Boolean = !closed.value && connections.value === value &&
        value.identity == credentials.owner && (when (composition) {
            Composition.FULL_BUSINESS -> value is LocalMasterClientConnection
            Composition.DIRECTORY_ONLY -> value is LocalDirectoryClientConnection && value.profile == directoryProfile
            Composition.AUTHENTICATION_ONLY -> value is LocalAuthenticationOnlyConnection
        }) && value.isCurrent()

    private suspend fun <T> snapshot(block: suspend (StoredClientCredentials) -> T): T {
        val state = when (val read = credentials.read()) { is JournalRead.Value -> read.value; JournalRead.Unavailable -> error("client.credentials.unavailable") }
        return try { state.validate(credentials.owner); block(state) } finally { state.destroy() }
    }
    private suspend fun durable(previous: StoredClientCredentials, next: StoredClientCredentials): Boolean =
        credentials.replace(previous.revision, next) == JournalWrite.DURABLE

    /**
     * Bounded, service-independent client work. Cancelling a UI await cancels its result channel,
     * not accepted authentication/revocation work. Undelivered sessions/leases are always closed.
     */
    private suspend fun <T> ownedWork(unavailable: () -> T, dispose: (T) -> Unit = {}, finished: () -> Unit = {}, block: suspend () -> T): T {
        val replies = Channel<T>(1, onUndeliveredElement = dispose)
        var started = false
        var admitted = false
        try {
            currentCoroutineContext().ensureActive()
            if (closed.value || !admission.tryAcquire()) return unavailable()
            admitted = true
            val job = work.launch {
                val result = try { block() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { unavailable() }
                if (!replies.trySend(result).isSuccess) dispose(result)
            }
            started = true
            job.invokeOnCompletion { finished(); admission.release(); replies.close() }
            return replies.receive()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return unavailable() }
        finally {
            replies.cancel()
            if (!started) { if (admitted) admission.release(); finished() }
        }
    }

    /** Retires client work only; encrypted unresolved evidence and the branch service survive. */
    public fun close() {
        // Fence new delivery before draining; no racing authenticated reply may become
        // interactive while the terminal negative graph is still being traversed.
        closed.value = true
        var failure: Throwable? = null
        try { physicalLifetimes.deny() }
        catch (error: Throwable) { failure = error }
        finally {
            // An overlapping successful attachment close may have removed a callback
            // before another invocation failed. Keep the failed exact session separately.
            for (original in failedPhysicalDenials.value) try { original.denyOriginals() }
            catch (error: Throwable) {
                val previous = failure
                if (previous == null) failure = error
                else if (previous !== error) previous.addSuppressed(error)
            }
            // A failed negative attachment cannot leave the client worker or session active.
            // Keep denial before local retirement, and do not suppress a failed repeated close.
            try { active.value?.end(LocalSessionLifecycle.LOCKED) }
            catch (error: Throwable) {
                val previous = failure
                if (previous == null) failure = error
                else if (previous !== error) previous.addSuppressed(error)
            }
            finally {
                try { work.cancel() }
                catch (error: Throwable) {
                    val previous = failure
                    if (previous == null) failure = error
                    else if (previous !== error) previous.addSuppressed(error)
                }
            }
        }
        failure?.let { throw it }
    }

    /** Root waits for this client's owned buffers/IO before closing its journals; not master quiescence. */
    public suspend fun closeAndAwait() {
        try { close() }
        finally { withContext(NonCancellable) { workJob.join() } }
    }

    public companion object {
        /** One immutable supported business profile on the same exact original session engine. */
        public fun directoryOnly(connections: StateFlow<LocalDirectoryClientConnection?>,
            credentials: LocalClientCredentialJournal, intents: LocalClientIntentJournalSource,
            profile: LocalMasterRpcWorkflowProfile, dispatcher: CoroutineDispatcher = Dispatchers.Default): LocalEndpointBranchGateway =
            LocalEndpointBranchGateway(connections, credentials, intents, Composition.DIRECTORY_ONLY, dispatcher, profile)

        /** Same real session/workflow engine, bounded supplier profile and exact actor intents. */
        public fun supplierOnly(connections: StateFlow<LocalSupplierClientConnection?>,
            credentials: LocalClientCredentialJournal, intents: LocalClientIntentJournalSource,
            dispatcher: CoroutineDispatcher = Dispatchers.Default): LocalEndpointBranchGateway =
            directoryOnly(connections, credentials, intents, LocalMasterRpcWorkflowProfile.SUPPLIERS, dispatcher)

        /** Actual authentication and durable credentials, with no installed business/journal fiction. */
        public fun authenticationOnly(connections: StateFlow<LocalAuthenticationOnlyConnection?>,
            credentials: LocalClientCredentialJournal, dispatcher: CoroutineDispatcher = Dispatchers.Default): LocalEndpointBranchGateway =
            LocalEndpointBranchGateway(connections, credentials, null, Composition.AUTHENTICATION_ONLY, dispatcher)
    }

    private inner class EndpointSession(
        val connection: LocalAuthenticationClientConnection,
        val localId: DomainId,
        val identity: LocalClientIdentity,
        val login: LocalLoginIdentifier,
        initialPolicy: ActivatedLocalPolicy,
        initialDisplay: SessionDisplayContext,
        private val business: BusinessAttachment?,
    ) : BranchUiSession {
        // Preserve the full-business journal -> credential lock order. Authentication-only
        // checks serialize locally without inventing an actor financial-intent journal.
        private val checkRequests = business?.journal?.requests ?: Mutex()
        private val state = MutableStateFlow(LocalSessionLifecycle.ACTIVE)
        val leaseFence = MutableStateFlow(Any())
        private val physicalSession = LocalRetailPhysicalLifetime()
        private val physicalEpoch = MutableStateFlow(LocalRetailPhysicalLifetime())
        private var physicalAttachment = physicalSession.attach(physicalEpoch.value)
        private val gatewayPhysicalAttachment = checkNotNull(physicalLifetimes.attach(
            LocalRetailPhysicalDenial { denyOriginals() }))
        private val publishedAttachment = MutableStateFlow(Attachment(LocalSessionAttachmentKey(DomainId.random(), 1),
            initialPolicy.revision, LocalSessionPresentationPolicy.capture(initialPolicy),
            initialDisplay.copy(localDraftEpochs = LocalSessionDraftEpochs(1, 1))))
        // Private ownership linearizes a bound revocation against replacement. Public flow
        // notification follows actual negative cleanup; it is never the authorization fence.
        private val attachmentOwner = MutableStateFlow<LocalSessionAttachmentKey?>(publishedAttachment.value.key)
        private val attachmentState = MutableStateFlow<LocalSessionAttachmentKey?>(publishedAttachment.value.key)
        override val lifecycle: StateFlow<LocalSessionLifecycle> = state.asStateFlow()
        override val attachmentUpdates: StateFlow<LocalSessionAttachmentKey?> = attachmentState.asStateFlow()
        override val display: SessionDisplayContext get() = publishedAttachment.value.display
        override val workflows: LocalWorkflowGateway get() = publishedAttachment.value.workflows
        override val administration: LocalAdministrationUiGateway get() = publishedAttachment.value.administration
        override val companyCatalog: LocalCompanyCatalogGateway get() = publishedAttachment.value.companyCatalog
        override val retailPhysical: LocalRetailPhysicalGateway get() = publishedAttachment.value.retailPhysical

        /** Each adapter captures its own generation; retained references cannot follow refresh. */
        private inner class Attachment(val key: LocalSessionAttachmentKey, val policyRevision: Revision,
            val policy: LocalSessionPresentationPolicy, val display: SessionDisplayContext) {
            val retired = MutableStateFlow(false)
            private val source = LocalWorkflowCredentialSource {
                acquire(this@EndpointSession, expectedAttachment = key)?.lease
            }
            val workflows: LocalWorkflowGateway = business?.let { actual ->
                LocalEndpointWorkflowGateway(actual.endpoint, actual.journal, source, actual.installed)
            } ?: UnavailableLocalWorkflows
            val administration: LocalAdministrationUiGateway = business?.fullConnection?.let { full ->
                if (full.administrationClient == null) UnavailableLocalAdministration
                else LocalEndpointAdministrationGateway(full, credentials, identity, source,
                    retireOriginalSession = {
                        // Late administration cleanup belongs to the original adapter only.
                        if (attachmentOwner.compareAndSet(key, null)) end(LocalSessionLifecycle.REAUTHENTICATION_REQUIRED)
                    })
            } ?: UnavailableLocalAdministration
            val companyCatalog: LocalCompanyCatalogGateway = business?.let { actual ->
                actual.fullConnection?.companyPublications?.let { publications ->
                    LocalEndpointCompanyCatalogGateway(publications, actual.endpoint, actual.journal, source, actual.installed)
                }
            } ?: UnavailableLocalCompanyCatalog
            val retailPhysical: LocalRetailPhysicalGateway = business?.takeUnless {
                it.installed.isCustomerReceiptsOnly || it.installed.isFoodChecksOnly || it.installed.isFoodCashFinancialOnly
            }?.let { actual ->
                actual.fullConnection?.retailPhysicalBinding?.let { binding ->
                    LocalEndpointRetailPhysicalGateway(binding, actual.journal, source,
                        LocalRetailOriginalCredentialSource { acquireOriginal(key) }, actual.installed)
                }
            } ?: UnavailableLocalRetailPhysical
        }

        fun bindingCurrent(): Boolean = connectionCurrent(connection) && physicalDrains.value == 0 && failedPhysicalDenials.value.isEmpty()
        fun interactive(): Boolean = bindingCurrent() && active.value === this && state.value == LocalSessionLifecycle.ACTIVE &&
            attachmentOwner.value != null && !physicalSession.denied
        fun attachmentCurrent(key: LocalSessionAttachmentKey): Boolean {
            val current = publishedAttachment.value
            return interactive() && !current.retired.value && current.key == key && attachmentOwner.value == key && attachmentState.value == key
        }
        override fun isCurrent(): Boolean = attachmentState.value?.let(::attachmentCurrent) == true
        /** Original actions never follow an access refresh, even within this same UI session. */
        fun invalidateLeases() {
            publishedAttachment.value.retired.value = true
            leaseFence.value = Any()
            try {
                physicalEpoch.value.deny()
                physicalAttachment?.close()
            } catch (error: Throwable) {
                // Failed old-original denial cannot be skipped by a later policy refresh
                // merely because the old presentation attachment is already retired.
                retainFailedDenial()
                try { end(LocalSessionLifecycle.AUTHORITY_UNAVAILABLE) }
                catch (cleanupError: Throwable) { if (cleanupError !== error) error.addSuppressed(cleanupError) }
                throw error
            }
            val next = LocalRetailPhysicalLifetime()
            physicalAttachment = physicalSession.attach(next)
            physicalEpoch.value = next // May already be denied by end(); never a session grant.
        }

        /** Called under the credential worker lock, after protected Current on this live connection. */
        fun acceptPolicy(policy: ActivatedLocalPolicy, renewed: Boolean = false): Boolean {
            if (!interactive()) return false
            val previous = publishedAttachment.value
            if (policy.revision.value < previous.policyRevision.value) return false
            val inputs = LocalSessionPresentationPolicy.capture(policy)
            // A revision cannot denote two different policies; do not treat an inconsistent
            // projection as a harmless refresh or silently retain its earlier private fields.
            if (policy.revision == previous.policyRevision && inputs != previous.policy) return false
            if (!renewed && policy.revision == previous.policyRevision) return attachmentCurrent(previous.key)
            val epochs = inputs.nextEpochs(previous.policy, requireNotNull(previous.display.localDraftEpochs))
            val nextDisplay = display(policy, login, connection.labels, business?.fullConnection?.administrationClient != null,
                workspaceMode = workspaceMode(business))
                .copy(localDraftEpochs = epochs)
            // Protected Current can keep this employee/session valid while withdrawing every
            // operational area. Retire its old adapters; the UI shows session status only.
            // No recovery or business permission is added to the empty projection.
            if (!previous.retired.value) invalidateLeases()
            return replaceAttachment(policy.revision, inputs, nextDisplay)
        }

        /** Rotation, when needed, is already durable. Native connection ownership does not change. */
        private fun replaceAttachment(revision: Revision, policy: LocalSessionPresentationPolicy, display: SessionDisplayContext): Boolean {
            if (!interactive()) return false
            val previous = publishedAttachment.value
            if (!previous.retired.value || previous.key.generation == Long.MAX_VALUE) return false
            val next = Attachment(previous.key.copy(generation = previous.key.generation + 1), revision, policy, display)
            publishedAttachment.value = next
            // close() may run concurrently. Never overwrite its null terminal publication.
            if (!interactive() || !attachmentOwner.compareAndSet(previous.key, next.key) ||
                !interactive() || !attachmentState.compareAndSet(previous.key, next.key)) {
                next.retired.value = true
                return false
            }
            return attachmentCurrent(next.key)
        }
        private fun retainFailedDenial() {
            while (true) {
                val before = failedPhysicalDenials.value
                if (this in before || failedPhysicalDenials.compareAndSet(before, before + this)) return
            }
        }

        fun denyOriginals() {
            while (true) {
                val before = physicalDrains.value
                kotlin.check(before != Int.MAX_VALUE)
                if (physicalDrains.compareAndSet(before, before + 1)) break
            }
            try { drainOriginals() }
            finally {
                while (true) {
                    val before = physicalDrains.value
                    kotlin.check(before > 0)
                    if (physicalDrains.compareAndSet(before, before - 1)) break
                }
            }
        }

        private fun drainOriginals() {
            var failure: Throwable? = null
            try { physicalSession.deny() }
            catch (error: Throwable) { failure = error }
            try { physicalEpoch.value.deny() }
            catch (error: Throwable) {
                val previous = failure
                if (previous == null) failure = error
                else if (previous !== error) previous.addSuppressed(error)
            }
            failure?.let {
                retainFailedDenial()
                throw it
            }
        }
        fun end(reason: LocalSessionLifecycle) {
            attachmentOwner.value = null
            publishedAttachment.value.retired.value = true
            // The gateway retains this exact original if denial fails. Pointer retirement
            // alone is not confirmed native close and cannot lose its retry custody.
            try { gatewayPhysicalAttachment.close() }
            finally {
                // Negative attachment cleanup cannot leave the private session interactive.
                leaseFence.value = Any()
                state.compareAndSet(LocalSessionLifecycle.ACTIVE, reason)
                active.compareAndSet(this, null)
                attachmentState.value = null
            }
        }
        override fun close() { end(LocalSessionLifecycle.LOCKED) }

        override suspend fun current(): LocalSessionCheckResult = check(false)
        override suspend fun refresh(): LocalSessionCheckResult = check(true)
        private suspend fun acquireOriginal(key: LocalSessionAttachmentKey): LocalRetailOriginalCredential? {
            // Capture before the credential worker can suspend. No refresh-created epoch may be
            // inherited by this original request, including one queued behind another refresh.
            val epoch = physicalEpoch.value
            if (epoch.denied || physicalSession.denied || !attachmentCurrent(key)) return null
            val value = acquire(this, allowRefresh = false, expectedAttachment = key)?.lease ?: return null
            if (epoch !== physicalEpoch.value || epoch.denied || physicalSession.denied || !value.isCurrent()) {
                value.destroy(); return null
            }
            return LocalRetailOriginalCredential(value, epoch)
        }
        private suspend fun check(force: Boolean): LocalSessionCheckResult {
            val requested = attachmentKey
            return checkRequests.withLock {
                val checked = acquire(this, refreshRequestedFor = requested.takeIf { force })
                if (checked == null) LocalSessionCheckResult.Unavailable(when (state.value) {
                    LocalSessionLifecycle.STORAGE_UNCERTAIN -> LocalServiceProblem.STORAGE_UNCERTAIN
                    LocalSessionLifecycle.AUTHORITY_UNAVAILABLE -> LocalServiceProblem.UNAVAILABLE
                    else -> LocalServiceProblem.SESSION_ENDED
                }, requested) else try {
                    if (checked.lease.isCurrent()) LocalSessionCheckResult.Current(checked.display, checked.attachment)
                    else LocalSessionCheckResult.Unavailable(LocalServiceProblem.SESSION_ENDED, checked.attachment)
                } finally { checked.lease.destroy() }
            }
        }

        override suspend fun mayRestore(route: AppRoute): Boolean {
            if (business == null) return false
            val attachment = publishedAttachment.value
            val area = RouteRegistry.area(route)
            fun stillPermitted(): Boolean = attachmentCurrent(attachment.key) && area in attachment.display.allowedAreas
            if (!RouteRegistry.allows(SecurityRoot.BRANCH_WORKSPACE, route) || !RouteRegistry.restorable(route) ||
                !stillPermitted()) return false
            val checked = current() as? LocalSessionCheckResult.Current ?: return false
            // Current may adopt a newer policy without ending this employee's session. A
            // route checked before that suspension cannot inherit the successor attachment.
            if (checked.attachmentKey != attachment.key || !stillPermitted()) return false
            val kitchenOnly = attachment.display.workspaceMode == LocalSessionWorkspaceMode.KITCHEN_ONLY
            val foodChecksOnly = attachment.display.workspaceMode == LocalSessionWorkspaceMode.FOOD_CHECKS_ONLY
            val foodCashFinancialOnly = attachment.display.workspaceMode == LocalSessionWorkspaceMode.FOOD_CASH_FINANCIAL_ONLY
            val foodTableAssignmentsOnly = attachment.display.workspaceMode == LocalSessionWorkspaceMode.FOOD_TABLE_ASSIGNMENTS_ONLY
            if (kitchenOnly && (route is AppRoute.Order || route == AppRoute.Food || route is AppRoute.FoodOrder ||
                    route is AppRoute.FoodHandoff)) return false
            if (foodCashFinancialOnly && route != AppRoute.Orders && route != AppRoute.Food && route !is AppRoute.FoodOrder) return false
            if (foodChecksOnly && route != AppRoute.Orders && route != AppRoute.Food && route !is AppRoute.FoodOrder) return false
            if (foodTableAssignmentsOnly && route != AppRoute.Orders && route != AppRoute.Food && route !is AppRoute.FoodOrder) return false
            if (route is AppRoute.Order) {
                val result = attachment.workflows.read(LocalWorkflowQuery(LocalWorkflow.ORDERS, reference = LocalRecordReference(LocalRecordKind.ORDER, route.orderId)))
                return stillPermitted() && result is LocalReadResult.Available && result.page.records.any {
                    it.reference.kind == LocalRecordKind.ORDER && it.reference.id == route.orderId
                }
            }
            val foodQuery = when (route) {
                AppRoute.Food -> LocalFoodUiQuery.MyChecks()
                is AppRoute.FoodOrder -> LocalFoodUiQuery.Check(route.orderId)
                // Restore the actual authorized station LABEL. Tasks require their own later
                // current read; labels and tasks are never presented as one fabricated cut.
                is AppRoute.FoodStation -> LocalFoodUiQuery.StationLabel(route.stationId)
                is AppRoute.FoodHandoff -> LocalFoodUiQuery.IncomingKnownOrder(route.orderId)
                else -> null
            }
            if (foodQuery != null) {
                val workflow = if (kitchenOnly) LocalWorkflow.KITCHEN else LocalWorkflow.FOOD
                val result = attachment.workflows.read(LocalWorkflowQuery(workflow, food = foodQuery))
                val page = (result as? LocalReadResult.Available)?.page ?: return false
                val food = page.food ?: return false
                return stillPermitted() && page.workflow == workflow && food.query == foodQuery &&
                    food.companyId == attachment.display.companyId && food.branchId == attachment.display.branchId &&
                    food.actorId == attachment.display.actorId
            }
            return stillPermitted()
        }

        override suspend fun logout(): LocalSessionEndResult {
            close()
            return ownedWork({ LocalSessionEndResult.UNRESOLVED }) { credentials.requests.withLock { revokeOwned(localId) } }
        }
    }
}

private fun ownedCredential(connection: LocalAuthenticationClientConnection, bearer: OpaqueCredential): LocalGatewayCredentials =
    LocalGatewayCredentials(connection.connectionId, bearer.useBytes(::OpaqueCredential))
private fun unavailableCleanup(): LocalClientCleanupStatus = LocalClientCleanupStatus(available = false)
private fun matches(view: LocalSessionView, connection: LocalAuthenticationClientConnection, login: LocalLoginIdentifier): Boolean =
    view.scope == connection.identity.scope && view.deviceId == connection.identity.deviceId && view.login == login &&
        view.policy.scope == view.scope && view.policy.actorId == view.actorId && view.generation.value in 1 until Long.MAX_VALUE &&
        view.accessExpiresAt.bootId == view.workExpiresAt.bootId && view.accessExpiresAt.elapsedMillis <= view.workExpiresAt.elapsedMillis
private fun sameSession(row: StoredClientSessionV1, view: LocalSessionView): Boolean = row.identity == LocalClientIdentity(view.scope, view.actorId, view.deviceId) &&
    row.login == view.login && row.sessionId == view.sessionId && row.generation == view.generation &&
    row.accessExpiresAt == view.accessExpiresAt && row.workExpiresAt == view.workExpiresAt &&
    view.policy.actorId == row.identity.actorId && view.policy.scope == row.identity.scope

private fun display(view: LocalSessionView, labels: LocalBranchDisplayLabels, administrationAvailable: Boolean,
    workspaceMode: LocalSessionWorkspaceMode): SessionDisplayContext {
    return display(view.policy, view.login, labels, administrationAvailable, workspaceMode)
}

private fun display(policy: ActivatedLocalPolicy, login: LocalLoginIdentifier, labels: LocalBranchDisplayLabels,
    administrationAvailable: Boolean, workspaceMode: LocalSessionWorkspaceMode): SessionDisplayContext {
    fun hint(action: SecurityAction, verb: PermissionVerb = PermissionVerb.PERFORM): Boolean {
        val permission = Permission(action, verb)
        val grants = policy.grants.filter { it.permission == permission &&
            (it.scope == policy.scope || it.scope == policy.scope.company()) && (it.namedSubjectId == null || it.namedSubjectId == policy.actorId) }
        // Navigation is not target authorization. A target-limited denial must not hide an
        // entire area containing other permitted targets; every service still checks its binding.
        return grants.none { it.effect == GrantEffect.DENY && it.targetIds == null } && grants.any { it.effect == GrantEffect.ALLOW }
    }
    val areas: Set<AppArea> = when (workspaceMode) {
        LocalSessionWorkspaceMode.AUTHENTICATION_ONLY -> emptySet()
        LocalSessionWorkspaceMode.SUPPLIER_ONLY -> if (hint(SecurityAction.PURCHASING_READ) ||
            hint(SecurityAction.SUPPLIER_ADMINISTRATION, PermissionVerb.ADMINISTER) || hint(SecurityAction.OPERATION_RESULT_RECOVERY))
            setOf(AppArea.PURCHASING) else emptySet()
        LocalSessionWorkspaceMode.WAREHOUSE_ONLY -> if (hint(SecurityAction.CATALOG_READ) ||
            hint(SecurityAction.WAREHOUSE_CONFIGURE, PermissionVerb.ADMINISTER) || hint(SecurityAction.OPERATION_RESULT_RECOVERY))
            setOf(AppArea.SETUP) else emptySet()
        LocalSessionWorkspaceMode.REPORTS_ONLY -> if (hint(SecurityAction.BRANCH_REPORT) ||
            hint(SecurityAction.BRANCH_REPORT, PermissionVerb.EXPORT) || hint(SecurityAction.OPERATION_RESULT_RECOVERY))
            setOf(AppArea.REPORTS) else emptySet()
        LocalSessionWorkspaceMode.STOCK_TRANSFERS_ONLY -> if (hint(SecurityAction.INVENTORY_QUANTITY_READ) ||
            hint(SecurityAction.INVENTORY_COST_READ) || hint(SecurityAction.INVENTORY_ADJUST) ||
            hint(SecurityAction.OPERATION_RESULT_RECOVERY)) setOf(AppArea.STOCK) else emptySet()
        LocalSessionWorkspaceMode.KITCHEN_ONLY -> if (hint(SecurityAction.CATALOG_READ) ||
            hint(SecurityAction.KITCHEN_TASK) || hint(SecurityAction.OPERATION_RESULT_RECOVERY)) setOf(AppArea.ORDERS) else emptySet()
        LocalSessionWorkspaceMode.FOOD_CHECKS_ONLY -> if (hint(SecurityAction.CATALOG_READ) ||
            hint(SecurityAction.ORDER_READ) || hint(SecurityAction.ORDER_CREATE) || hint(SecurityAction.ORDER_EDIT) ||
            hint(SecurityAction.OPERATION_RESULT_RECOVERY)) setOf(AppArea.ORDERS) else emptySet()
        LocalSessionWorkspaceMode.FOOD_CASH_FINANCIAL_ONLY -> if (hint(SecurityAction.ORDER_READ) ||
            hint(SecurityAction.CASH_READ) || hint(SecurityAction.COLLECTION_BEGIN) ||
            hint(SecurityAction.COLLECTION_ATTEST) || hint(SecurityAction.COLLECTION_FINALIZE) ||
            hint(SecurityAction.COLLECTION_FINALIZE_ZERO) || hint(SecurityAction.RETURN_RECEIPT_LOOKUP) ||
            hint(SecurityAction.OPERATION_RESULT_RECOVERY)) setOf(AppArea.ORDERS) else emptySet()
        LocalSessionWorkspaceMode.FOOD_TABLE_ASSIGNMENTS_ONLY -> if (hint(SecurityAction.ORDER_READ) ||
            hint(SecurityAction.FLOOR_READ) || hint(SecurityAction.ORDER_EDIT) ||
            hint(SecurityAction.OPERATION_RESULT_RECOVERY)) setOf(AppArea.ORDERS) else emptySet()
        LocalSessionWorkspaceMode.RETAIL_ORDERS_ONLY -> buildSet {
            if (hint(SecurityAction.CATALOG_READ) || hint(SecurityAction.ORDER_CREATE) ||
                hint(SecurityAction.ORDER_EDIT) || hint(SecurityAction.OPERATION_RESULT_RECOVERY)) add(AppArea.CASHIER)
            if (hint(SecurityAction.ORDER_READ) || hint(SecurityAction.ORDER_EDIT) ||
                hint(SecurityAction.OPERATION_RESULT_RECOVERY)) add(AppArea.ORDERS)
        }
        LocalSessionWorkspaceMode.PAYMENT_CORRECTIONS_ONLY -> if (hint(SecurityAction.CASH_REVIEW) ||
            hint(SecurityAction.PAYMENT_CLASSIFICATION_CORRECT) || hint(SecurityAction.OPERATION_RESULT_RECOVERY))
            setOf(AppArea.CASHIER, AppArea.ORDERS) else emptySet()
        LocalSessionWorkspaceMode.CUSTOMER_RECEIPTS_ONLY -> buildSet {
            if (hint(SecurityAction.CUSTOMER_RECEIPT_RECORD) || hint(SecurityAction.OPERATION_RESULT_RECOVERY)) add(AppArea.CASHIER)
            if (hint(SecurityAction.CUSTOMER_RECEIPT_RECORD) || hint(SecurityAction.RETURN_RECEIPT_LOOKUP) ||
                hint(SecurityAction.OPERATION_RESULT_RECOVERY)) add(AppArea.ORDERS)
        }
        LocalSessionWorkspaceMode.INVOICE_CAPTURE_ONLY -> if (hint(SecurityAction.PURCHASING_READ) ||
            hint(SecurityAction.PURCHASING_INVOICE_CAPTURE) || hint(SecurityAction.OPERATION_RESULT_RECOVERY))
            setOf(AppArea.PURCHASING) else emptySet()
        LocalSessionWorkspaceMode.INVOICE_MATCHING_ONLY -> if (hint(SecurityAction.PURCHASING_READ) ||
            hint(SecurityAction.PURCHASING_INVOICE_MATCH_RECOGNIZE) || hint(SecurityAction.OPERATION_RESULT_RECOVERY))
            setOf(AppArea.PURCHASING) else emptySet()
        LocalSessionWorkspaceMode.POLICY_FILTERED -> buildSet {
        if (listOf(SecurityAction.CATALOG_READ, SecurityAction.ORDER_CREATE, SecurityAction.COLLECTION_BEGIN,
                SecurityAction.OPERATION_RESULT_RECOVERY).any { hint(it) }) add(AppArea.CASHIER)
        if (listOf(SecurityAction.ORDER_READ, SecurityAction.ORDER_HANDOFF, SecurityAction.ORDER_REASSIGN, SecurityAction.KITCHEN_TASK,
                SecurityAction.ORDER_CREATE, SecurityAction.CATALOG_READ, SecurityAction.RETURN_CLAIM,
                SecurityAction.RETURN_RECEIPT_LOOKUP, SecurityAction.RETURN_ACCEPT, SecurityAction.FLOOR_READ,
                SecurityAction.CUSTOMER_RECEIPT_RECORD).any { hint(it) } ||
            hint(SecurityAction.FLOOR_CONFIGURE, PermissionVerb.ADMINISTER)) add(AppArea.ORDERS)
        if (listOf(SecurityAction.CASH_READ, SecurityAction.CASH_OPEN, SecurityAction.CASH_REVIEW, SecurityAction.CASH_CONFIGURE).any { hint(it) }) add(AppArea.DRAWER)
        if (hint(SecurityAction.INVENTORY_QUANTITY_READ)) add(AppArea.STOCK)
        if (hint(SecurityAction.CATALOG_READ)) add(AppArea.CATALOG)
        if (hint(SecurityAction.PURCHASING_READ) || hint(SecurityAction.PURCHASING_DOCUMENT_READ) ||
            hint(SecurityAction.SUPPLIER_ADMINISTRATION, PermissionVerb.ADMINISTER)) add(AppArea.PURCHASING)
        if (hint(SecurityAction.BRANCH_REPORT)) add(AppArea.REPORTS)
        if (hint(SecurityAction.WAREHOUSE_CONFIGURE, PermissionVerb.ADMINISTER) || hint(SecurityAction.TENDER_CONFIGURE, PermissionVerb.ADMINISTER) ||
            hint(SecurityAction.CASH_CONFIGURE, PermissionVerb.ADMINISTER)) add(AppArea.SETUP)
        if (administrationAvailable && hint(SecurityAction.LOCAL_ACCOUNT_ADMINISTRATION, PermissionVerb.ADMINISTER)) add(AppArea.PEOPLE)
        if (administrationAvailable && hint(SecurityAction.DEVICE_ADMINISTRATION, PermissionVerb.ADMINISTER)) add(AppArea.DEVICES)
        }
    }
    return SessionDisplayContext(policy.actorId, policy.scope.companyId, policy.scope.branchId,
        login.value, labels.companyName, labels.branchName, areas,
        workspaceMode = workspaceMode)
}
