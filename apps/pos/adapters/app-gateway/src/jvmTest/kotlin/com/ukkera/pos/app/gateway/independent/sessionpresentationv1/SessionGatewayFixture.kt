package com.ukkera.pos.app.gateway.independent.sessionpresentationv1

import com.ukkera.pos.app.BranchUiSession
import com.ukkera.pos.app.SignInResult
import com.ukkera.pos.app.gateway.JournalRead
import com.ukkera.pos.app.gateway.JvmClientCredentialCustody
import com.ukkera.pos.app.gateway.JvmClientCredentialCustodyView
import com.ukkera.pos.app.gateway.JvmClientCredentialJournalOpenResult
import com.ukkera.pos.app.gateway.JvmClientJournalCustody
import com.ukkera.pos.app.gateway.JvmClientJournalCustodyView
import com.ukkera.pos.app.gateway.JvmClientJournalOpenResult
import com.ukkera.pos.app.gateway.JvmEncryptedClientCredentialJournal
import com.ukkera.pos.app.gateway.JvmEncryptedClientIntentJournal
import com.ukkera.pos.app.gateway.LocalBranchDisplayLabels
import com.ukkera.pos.app.gateway.LocalClientDeviceIdentity
import com.ukkera.pos.app.gateway.LocalClientIdentity
import com.ukkera.pos.app.gateway.LocalClientIntentJournalSource
import com.ukkera.pos.app.gateway.LocalEndpointBranchGateway
import com.ukkera.pos.app.gateway.LocalMasterClientConnection
import com.ukkera.pos.app.gateway.LocalWorkflowInstallation
import com.ukkera.pos.app.gateway.StoredSessionPhaseV1
import com.ukkera.pos.database.DeferredReason
import com.ukkera.pos.database.LocalMasterClient
import com.ukkera.pos.database.LocalMasterAdministrationClient
import com.ukkera.pos.database.LocalMasterEndpointHealth
import com.ukkera.pos.database.LookupReply
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadFailure
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.inventory.store.LocalStockStoreProfile
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.LocalAuthenticatedPeer
import com.ukkera.pos.security.LocalAuthenticationResult
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.LocalSessionPolicy
import com.ukkera.pos.security.LocalSessionTokens
import com.ukkera.pos.security.LocalSessionView
import com.ukkera.pos.security.LocalSessionViewResult
import com.ukkera.pos.security.LocalSignOutResult
import com.ukkera.pos.security.MonotonicMoment
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionGrant
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.security.ThrottlePolicy
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Controlled client-contract samples plus the real JVM encrypted journals. No sample below is
 * an admitted peer, provisioned employee, protected financial fixture or verified master authority.
 * Successful protected read/result constructors are deliberately not synthesized. No memory
 * journal is installed. Newly created encrypted files are retained after retirement.
 */
internal class SessionGatewayFixture private constructor(
    internal val client: ControlledSessionClient,
    internal val gateway: LocalEndpointBranchGateway,
    internal val skuSample: DomainId,
    private val credentialCustody: JvmClientCredentialCustody,
    private val intentCustody: JvmClientJournalCustody,
    private var credentialJournal: JvmEncryptedClientCredentialJournal,
    private var intentJournal: JvmEncryptedClientIntentJournal,
    private val custodyLive: AtomicBoolean,
) {
    internal suspend fun signIn(): BranchUiSession {
        val result = gateway.signIn("ACTOR-A", charArrayOf('s', 'a', 'm', 'p', 'l', 'e'))
        return (result as? SignInResult.Authenticated)?.session
            ?: error("Controlled session sample could not be attached; this is harness setup, not a frozen-case result.")
    }

    /** Owned production readers; only immutable, secret-free identity/phase observations escape. */
    internal suspend fun observeJournals(): SessionJournalObservation {
        val credentials = when (val read = credentialJournal.read()) {
            is JournalRead.Value -> read.value
            JournalRead.Unavailable -> error("The encrypted credential observation is unavailable.")
        }
        val retained = try {
            credentials.validate(credentialJournal.owner)
            credentials.revision to credentials.sessions.map { row ->
                RetainedSessionObservation(row.localId, row.identity, row.sessionId, row.phase, row.originalRevoked)
            }
        } finally { credentials.destroy() }
        val pending = when (val read = intentJournal.pending()) {
            is JournalRead.Value -> read.value.map { it.operationId }
            JournalRead.Unavailable -> error("The encrypted intent observation is unavailable.")
        }
        return SessionJournalObservation(retained.first, retained.second, pending)
    }

    /** New production readers after closing the original readers; no plaintext/file parsing. */
    internal suspend fun retireAndReopenJournals() {
        gateway.closeAndAwait()
        credentialJournal.close()
        intentJournal.close()
        credentialJournal = (JvmEncryptedClientCredentialJournal.openExisting(credentialCustody)
            as? JvmClientCredentialJournalOpenResult.Opened)?.journal
            ?: error("The real credential journal could not be reopened for an independent read.")
        intentJournal = (JvmEncryptedClientIntentJournal.openExisting(intentCustody)
            as? JvmClientJournalOpenResult.Opened)?.journal
            ?: error("The real intent journal could not be reopened for an independent read.")
        val credentials = when (val read = credentialJournal.read()) {
            is JournalRead.Value -> read.value
            JournalRead.Unavailable -> error("Independent encrypted credential read is unavailable.")
        }
        try { credentials.validate(credentialJournal.owner) } finally { credentials.destroy() }
        check(intentJournal.pending() is JournalRead.Value) { "Independent encrypted intent read is unavailable." }
        // These reads are harness/persistence prerequisites. The original payment tuple is absent;
        // no business-preservation assertion is claimed from an empty intent journal.
    }

    internal suspend fun close() = withContext(NonCancellable) {
        try { gateway.closeAndAwait() }
        finally {
            try { credentialJournal.close() }
            finally {
                try { intentJournal.close() }
                finally { custodyLive.set(false); client.close() }
            }
        }
    }

    internal companion object {
        internal suspend fun create(dispatcher: CoroutineDispatcher,
            initialPolicySample: ControlledPolicyInput = ControlledPolicyInput.quantityOnly(Revision(0)),
            installedReadSamples: Set<ReadKey> = setOf(LocalStockStoreProfile.QUANTITIES),
            // Optional frozen qualification inputs remain inert samples, never admitted identities.
            identitySample: LocalClientIdentity? = null,
            initialSessionSample: DomainId? = null,
            skuInputSample: DomainId? = null,
            // Test-only explicit negative administration port; existing fixtures stay unchanged.
            administrationSample: ((ControlledSessionClient) -> LocalMasterClient)? = null,
        ): SessionGatewayFixture {
            val root = privateDirectory()
            val authDirectory = Files.createDirectory(root.resolve("credentials"), privateAttribute())
            val intentDirectory = Files.createDirectory(root.resolve("intents"), privateAttribute())
            val scope = identitySample?.scope ?: BranchScope(DomainId.random(), DomainId.random(), DomainId.random())
            val device = identitySample?.deviceId ?: DomainId.random()
            val actor = identitySample?.actorId ?: DomainId.random()
            val identity = LocalClientIdentity(scope, actor, device)
            val deviceIdentity = LocalClientDeviceIdentity(scope, device)
            val live = AtomicBoolean(true)
            val keys = KeyGenerator.getInstance("AES").apply { init(256) }
            val authView = JvmClientCredentialCustodyView(authDirectory, deviceIdentity, DomainId.random(),
                DomainId.random(), DomainId.random(), keys.generateKey(), live::get)
            val intentView = JvmClientJournalCustodyView(intentDirectory, identity, DomainId.random(),
                DomainId.random(), DomainId.random(), keys.generateKey(), live::get)
            val authCustody = JvmClientCredentialCustody { authView.takeIf { live.get() } }
            val intentCustody = JvmClientJournalCustody { intentView.takeIf { live.get() } }
            val auth = (JvmEncryptedClientCredentialJournal.createNew(authCustody)
                as? JvmClientCredentialJournalOpenResult.Opened)?.journal
                ?: error("The JVM encrypted credential journal requires supported private POSIX custody and durable IO.")
            var intent: JvmEncryptedClientIntentJournal? = null
            try {
                val openedIntent = (JvmEncryptedClientIntentJournal.createNew(intentCustody)
                    as? JvmClientJournalOpenResult.Opened)?.journal
                    ?: error("The JVM encrypted intent journal requires supported private POSIX custody and durable IO.")
                intent = openedIntent
                val client = ControlledSessionClient(identity, initialPolicySample, initialSessionSample)
                val peer = LocalAuthenticatedPeer(client.connectionSample, scope, device,
                    "controlled-session-contract-sample", null, live::get)
                val endpoint = administrationSample?.invoke(client) ?: client
                val administration = if (administrationSample == null) null else
                    checkNotNull(endpoint as? LocalMasterAdministrationClient) { "Explicit administration sample is missing its read port" }
                val connection = LocalMasterClientConnection(endpoint, peer, DomainId.random(),
                    LocalWorkflowInstallation(emptySet(), installedReadSamples),
                    LocalBranchDisplayLabels(scope, "COMPANY-1 sample", "BRANCH-A sample"), null, administration, live::get)
                val connections = MutableStateFlow<LocalMasterClientConnection?>(connection)
                val source = LocalClientIntentJournalSource { requested -> openedIntent.takeIf { it.owner == requested } }
                val gateway = LocalEndpointBranchGateway(connections, auth, source, dispatcher)
                return SessionGatewayFixture(client, gateway, skuInputSample ?: DomainId.random(), authCustody, intentCustody,
                    auth, openedIntent, live)
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    try { auth.close() } finally { intent?.close(); live.set(false) }
                }
                throw failure
            }
        }

        private fun privateAttribute() = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))

        private fun privateDirectory(): Path = Files.createTempDirectory("ukkera-session-contract-", privateAttribute()).toRealPath().also {
            check(Files.getFileStore(it).supportsFileAttributeView("posix")) {
                "This JVM journal harness needs POSIX private file permissions; no replacement persistence is available."
            }
        }
    }
}

internal data class RetainedSessionObservation(
    internal val localId: DomainId,
    internal val identity: LocalClientIdentity,
    internal val sessionId: DomainId,
    internal val phase: StoredSessionPhaseV1,
    internal val originalRevoked: Boolean,
)

internal data class SessionJournalObservation(
    internal val credentialRevision: Long,
    internal val sessions: List<RetainedSessionObservation>,
    internal val pendingOperationIds: List<OperationId>,
)

internal enum class ControlledRefreshReply { SUCCESS, LOST_REPLY, UNRECOVERABLE }

/** Inert current-policy reply data, not local activation, delegation or master authorization. */
internal data class ControlledPolicyInput(
    internal val revision: Revision,
    internal val permissions: List<Permission>,
) {
    internal companion object {
        internal fun quantityOnly(revision: Revision): ControlledPolicyInput = ControlledPolicyInput(revision,
            listOf(Permission(SecurityAction.INVENTORY_QUANTITY_READ, PermissionVerb.PERFORM)))

        internal fun quantityAndReports(revision: Revision): ControlledPolicyInput = ControlledPolicyInput(revision,
            listOf(Permission(SecurityAction.INVENTORY_QUANTITY_READ, PermissionVerb.PERFORM),
                Permission(SecurityAction.INVENTORY_COST_READ, PermissionVerb.PERFORM),
                Permission(SecurityAction.BRANCH_REPORT, PermissionVerb.PERFORM),
                Permission(SecurityAction.BRANCH_REPORT, PermissionVerb.EXPORT)))

        internal fun reportsOnly(revision: Revision): ControlledPolicyInput = ControlledPolicyInput(revision,
            listOf(Permission(SecurityAction.INVENTORY_COST_READ, PermissionVerb.PERFORM),
                Permission(SecurityAction.BRANCH_REPORT, PermissionVerb.PERFORM),
                Permission(SecurityAction.BRANCH_REPORT, PermissionVerb.EXPORT)))
    }
}

/** A deterministic response cut; it does not certify a transaction or revocation at a master. */
internal class HeldRefresh internal constructor(
    internal val reply: ControlledRefreshReply,
    internal val policyAfterRotationSample: ControlledPolicyInput? = null,
) {
    internal val captured = CompletableDeferred<Unit>()
    private val released = CompletableDeferred<Unit>()
    internal fun release() { released.complete(Unit) }
    internal suspend fun awaitRelease() { released.await() }
}

internal data class ProtectedCallSample(
    internal val kind: String,
    internal val connectionId: DomainId,
    internal val sessionLocator: DomainId,
    internal val query: ReadKey? = null,
    internal val operationId: OperationId? = null,
)

/**
 * Observable transport stand-in. Syntactic sample token locators correlate calls only: bearer
 * authenticity, admission, policy enforcement, master lineage and RPC disclosure are unverified.
 * Only public refusal replies are returned for protected business calls.
 */
internal class ControlledSessionClient(private val owner: LocalClientIdentity,
    initialPolicySample: ControlledPolicyInput = ControlledPolicyInput.quantityOnly(Revision(0)),
    initialSessionSample: DomainId? = null,
) : LocalMasterClient {
    internal val connectionSample: DomainId = DomainId.random()
    internal val signInCalls = AtomicInteger()
    internal val refreshCalls = AtomicInteger()
    internal val currentCalls = AtomicInteger()
    internal val protectedCalls = CopyOnWriteArrayList<ProtectedCallSample>()
    private val healthState = MutableStateFlow(LocalMasterEndpointHealth.ACTIVE)
    override val health: StateFlow<LocalMasterEndpointHealth> get() = healthState
    private val plans = Channel<HeldRefresh>(Channel.UNLIMITED)
    private val readRefusals = Channel<ReadFailure>(Channel.UNLIMITED)
    private val bootSample = DomainId.random()
    private var policySample = policy(initialPolicySample)
    private var viewSample = view(SessionGeneration(1), initialSessionSample ?: DomainId.random())
    internal val currentSessionSample: DomainId get() = viewSample.sessionId

    internal fun holdRefresh(reply: ControlledRefreshReply,
        policyAfterRotationSample: ControlledPolicyInput? = null,
    ): HeldRefresh = HeldRefresh(reply, policyAfterRotationSample).also {
        check(plans.trySend(it).isSuccess)
    }

    /** Changes only controlled Current reply data; deliberately emits no hint or activation event. */
    internal fun replaceCurrentPolicyReplySample(input: ControlledPolicyInput) {
        policySample = policy(input)
        val before = viewSample
        viewSample = LocalSessionView(before.actorId, before.scope, before.deviceId, before.sessionId,
            before.generation, before.login, policySample, before.accessExpiresAt, before.workExpiresAt)
    }

    /** Explicit public refusal schedule. This stand-in contains no policy/authorization engine. */
    internal fun refuseNextRead(reason: ReadFailure) { check(readRefusals.trySend(reason).isSuccess) }

    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult {
        signInCalls.incrementAndGet()
        check(connectionId == connectionSample && login.value == "ACTOR-A")
        return tokens(viewSample)
    }

    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult {
        refreshCalls.incrementAndGet()
        check(credentials.connectionId == connectionSample && locator(credentials) == viewSample.sessionId)
        val plan = plans.tryReceive().getOrNull() ?: return LocalAuthenticationResult.Unavailable
        // The success/lost-reply samples rotate before their response cut. These are controlled
        // contract samples; no actual protected master renewal or session policy is exercised.
        if (plan.reply != ControlledRefreshReply.UNRECOVERABLE) {
            plan.policyAfterRotationSample?.let { policySample = policy(it) }
            viewSample = view(viewSample.generation.next())
        }
        plan.captured.complete(Unit)
        plan.awaitRelease()
        return if (plan.reply == ControlledRefreshReply.SUCCESS) tokens(viewSample)
            else LocalAuthenticationResult.Unavailable
    }

    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult {
        currentCalls.incrementAndGet()
        return if (credentials.connectionId == connectionSample && locator(credentials) == viewSample.sessionId)
            LocalSessionViewResult.Current(viewSample) else LocalSessionViewResult.Denied
    }

    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult = LocalSignOutResult.Unavailable

    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        protectedCalls += call("READ", credentials, query = request.query)
        // Initiation is observable without forging the database's internal successful-read type.
        return ReadReply.Unavailable(readRefusals.tryReceive().getOrNull() ?: ReadFailure.WRITER_NOT_QUALIFIED)
    }

    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        protectedCalls += call("EXECUTE", credentials, operationId = request.operationId)
        return MutationReply.Deferred(request.operationId, DeferredReason.WRITER_NOT_QUALIFIED)
    }

    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply {
        protectedCalls += call("LOOKUP", credentials, operationId = identity.operationId)
        return LookupReply.Deferred(identity.operationId, DeferredReason.WRITER_NOT_QUALIFIED)
    }

    internal fun close() { plans.cancel(); readRefusals.cancel() }

    private fun call(kind: String, credentials: LocalGatewayCredentials, query: ReadKey? = null,
        operationId: OperationId? = null): ProtectedCallSample =
        ProtectedCallSample(kind, credentials.connectionId, locator(credentials), query, operationId)

    private fun view(generation: SessionGeneration, sessionSample: DomainId = DomainId.random()): LocalSessionView = LocalSessionView(owner.actorId,
        owner.scope, owner.deviceId, sessionSample, generation, LocalLoginIdentifier("ACTOR-A"), policySample,
        MonotonicMoment(bootSample, 10_000 + generation.value), MonotonicMoment(bootSample, 60_000))

    private fun policy(input: ControlledPolicyInput): ActivatedLocalPolicy = ActivatedLocalPolicy(owner.scope,
        owner.actorId, input.revision, input.permissions.map {
            PermissionGrant(it, owner.scope, GrantEffect.ALLOW, namedSubjectId = owner.actorId)
        }, LocalSessionPolicy(maximumConcurrentSessions = 1), ThrottlePolicy())

    private fun tokens(view: LocalSessionView): LocalAuthenticationResult.Authenticated =
        LocalAuthenticationResult.Authenticated(LocalSessionTokens(view, sampleToken("ULA1", view.sessionId),
            sampleToken("ULR1", view.sessionId)))

    private fun sampleToken(prefix: String, sessionId: DomainId): OpaqueCredential {
        val bytes = ByteArray(52).also(SecureRandom()::nextBytes)
        try {
            prefix.encodeToByteArray().copyInto(bytes)
            val id = UUID.fromString(sessionId.value)
            ByteBuffer.wrap(bytes, 4, 16).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits)
            return OpaqueCredential(bytes)
        } finally { bytes.fill(0) }
    }

    /** Retain only the public session locator, never a bearer copy, prefix/body or secret digest. */
    private fun locator(credentials: LocalGatewayCredentials): DomainId = credentials.bearer.useBytes { bytes ->
        check(bytes.size == 52)
        val locator = ByteBuffer.wrap(bytes, 4, 16)
        DomainId(UUID(locator.long, locator.long).toString())
    }
}
