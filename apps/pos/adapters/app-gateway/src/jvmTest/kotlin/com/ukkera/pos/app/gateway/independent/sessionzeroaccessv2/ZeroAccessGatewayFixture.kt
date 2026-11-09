package com.ukkera.pos.app.gateway.independent.sessionzeroaccessv2

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
import com.ukkera.pos.app.gateway.independent.sessionpresentationv1.ControlledPolicyInput
import com.ukkera.pos.database.DeferredReason
import com.ukkera.pos.database.LocalMasterClient
import com.ukkera.pos.database.LocalMasterEndpointHealth
import com.ukkera.pos.database.LookupReply
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadFailure
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.inventory.store.LocalStockStoreProfile
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.LocalAuthenticatedPeer
import com.ukkera.pos.security.LocalAuthenticationDenial
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
import com.ukkera.pos.security.PermissionGrant
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.security.ThrottlePolicy
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
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
 * Actual gateway and JVM encrypted credential/intent readers with controlled LZA reply data.
 * No credential verification, admission, Master, SQLite, RPC or native key custody is supplied.
 * The four authored symbolic markers are encoded into syntax-valid controlled credential data;
 * observation returns a marker only after matching the bytes read by the production reader.
 * Frozen UTC cutoff inputs are translated to synthetic relative MonotonicMoment data, with
 * the controlled renewal time as zero. No platform clock/deadline provider is installed.
 * The intent journal has no compatible full F-PENDING seed: payment.record v1, its historical
 * session/cash-session references and physical evidence are unbound. Empty journal observations
 * never qualify that record or the frozen business-effect cut. Files are retained after close.
 */
internal class ZeroAccessGatewayFixture private constructor(
    internal val client: ControlledZeroAccessClient,
    internal val gateway: LocalEndpointBranchGateway,
    private val credentialCustody: JvmClientCredentialCustody,
    private val intentCustody: JvmClientJournalCustody,
    private var credentialJournal: JvmEncryptedClientCredentialJournal,
    private var intentJournal: JvmEncryptedClientIntentJournal,
    private val custodyLive: AtomicBoolean,
) {
    internal suspend fun signIn(): SignInResult<BranchUiSession> =
        gateway.signIn(client.login.value, charArrayOf('s', 'a', 'm', 'p', 'l', 'e'))

    internal suspend fun attachZeroAccessInput(): BranchUiSession =
        (signIn() as? SignInResult.Authenticated)?.session
            ?: error("Controlled F-ZERO could not attach; this is a harness prerequisite, not a genuine authentication result.")

    /** Production readers supply secret-free client-local identity and unresolved-state cuts. */
    internal suspend fun observeJournals(): ZeroAccessJournalObservation {
        val snapshot = when (val read = credentialJournal.read()) {
            is JournalRead.Value -> read.value
            JournalRead.Unavailable -> error("Encrypted LZA credential observation is unavailable.")
        }
        val credentials = try {
            snapshot.validate(credentialJournal.owner)
            snapshot.attempts.map { ZeroAccessAttemptObservation(it.id, it.login.value,
                it.serviceIncarnationId, it.explicitDeviceMove) } to snapshot.sessions.map {
                ZeroAccessSessionObservation(it.localId, it.identity, it.sessionId, it.generation.value,
                    it.phase, it.originalRevoked, it.accessExpiresAt, it.workExpiresAt,
                    client.observeCredential(it.access, "ULA1"), client.observeCredential(it.refresh, "ULR1"))
            }
        } finally { snapshot.destroy() }
        val pending = when (val read = intentJournal.pending()) {
            is JournalRead.Value -> read.value.map { it.operationId }
            JournalRead.Unavailable -> error("Encrypted LZA intent observation is unavailable.")
        }
        return ZeroAccessJournalObservation(credentials.first, credentials.second, pending)
    }

    /** Fresh production readers after actual client-reader close; not process/rollback evidence. */
    internal suspend fun retireAndReopenJournals() {
        gateway.closeAndAwait()
        credentialJournal.close()
        intentJournal.close()
        credentialJournal = (JvmEncryptedClientCredentialJournal.openExisting(credentialCustody)
            as? JvmClientCredentialJournalOpenResult.Opened)?.journal
            ?: error("The original encrypted LZA credential journal cannot be reopened.")
        intentJournal = (JvmEncryptedClientIntentJournal.openExisting(intentCustody)
            as? JvmClientJournalOpenResult.Opened)?.journal
            ?: error("The original encrypted LZA intent journal cannot be reopened.")
    }

    internal suspend fun close() = withContext(NonCancellable) {
        client.releaseHeldReplies()
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
        internal suspend fun create(freeze: FrozenLocalSessionZeroAccessV2, dispatcher: CoroutineDispatcher,
            signInReply: ZeroAccessAuthenticationReply = ZeroAccessAuthenticationReply.AUTHENTICATED,
        ): ZeroAccessGatewayFixture {
            val root = privateDirectory()
            val authDirectory = Files.createDirectory(root.resolve("credentials"), privateAttribute())
            val intentDirectory = Files.createDirectory(root.resolve("intents"), privateAttribute())
            val identity = freeze.employeeIdentity()
            val live = AtomicBoolean(true)
            val keys = KeyGenerator.getInstance("AES").apply { init(256) }
            val authView = JvmClientCredentialCustodyView(authDirectory,
                LocalClientDeviceIdentity(identity.scope, identity.deviceId), DomainId.random(),
                DomainId.random(), DomainId.random(), keys.generateKey(), live::get)
            val intentView = JvmClientJournalCustodyView(intentDirectory, identity, DomainId.random(),
                DomainId.random(), DomainId.random(), keys.generateKey(), live::get)
            val authCustody = JvmClientCredentialCustody { authView.takeIf { live.get() } }
            val intentCustody = JvmClientJournalCustody { intentView.takeIf { live.get() } }
            val auth = (JvmEncryptedClientCredentialJournal.createNew(authCustody)
                as? JvmClientCredentialJournalOpenResult.Opened)?.journal
                ?: error("LZA encrypted credential plumbing requires supported private POSIX custody and durable IO.")
            var intent: JvmEncryptedClientIntentJournal? = null
            var client: ControlledZeroAccessClient? = null
            try {
                val openedIntent = (JvmEncryptedClientIntentJournal.createNew(intentCustody)
                    as? JvmClientJournalOpenResult.Opened)?.journal
                    ?: error("LZA encrypted intent plumbing requires supported private POSIX custody and durable IO.")
                intent = openedIntent
                val controlled = ControlledZeroAccessClient(identity, freeze.renewalInput(), signInReply)
                client = controlled
                val peer = LocalAuthenticatedPeer(controlled.connectionId, identity.scope, identity.deviceId,
                    "controlled-lza-contract-input", null, live::get)
                val connection = LocalMasterClientConnection(controlled, peer, DomainId.random(),
                    LocalWorkflowInstallation(emptySet(), setOf(LocalStockStoreProfile.QUANTITIES)),
                    LocalBranchDisplayLabels(identity.scope, "LZA company sample", "LZA branch sample"), live::get)
                val source = LocalClientIntentJournalSource { requested -> openedIntent.takeIf { it.owner == requested } }
                val gateway = LocalEndpointBranchGateway(MutableStateFlow<LocalMasterClientConnection?>(connection),
                    auth, source, dispatcher)
                return ZeroAccessGatewayFixture(controlled, gateway, authCustody, intentCustody, auth, openedIntent, live)
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    client?.releaseHeldReplies()
                    try { auth.close() } finally { intent?.close(); live.set(false); client?.close() }
                }
                throw failure
            }
        }

        private fun privateAttribute() = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))

        private fun privateDirectory(): Path = Files.createTempDirectory("ukkera-lza-v2-journal-", privateAttribute())
            .toRealPath().also {
                check(Files.getFileStore(it).supportsFileAttributeView("posix")) {
                    "LZA JVM journal plumbing needs private POSIX permissions; no substitute persistence is supplied."
                }
            }
    }
}

internal data class ZeroAccessAttemptObservation(
    internal val clientLocalId: DomainId,
    internal val login: String,
    internal val serviceIncarnationId: DomainId,
    internal val explicitDeviceMove: Boolean,
)

internal data class ZeroAccessSessionObservation(
    internal val clientLocalId: DomainId,
    internal val identity: LocalClientIdentity,
    internal val sessionId: DomainId,
    internal val generation: Long,
    internal val phase: StoredSessionPhaseV1,
    internal val originalRevoked: Boolean,
    internal val accessExpiresAt: MonotonicMoment,
    internal val workExpiresAt: MonotonicMoment,
    internal val accessCredential: ZeroAccessCredentialObservation,
    internal val refreshCredential: ZeroAccessCredentialObservation,
)

internal data class ZeroAccessCredentialObservation(
    internal val issuedSessionId: DomainId,
    internal val prefix: String,
    internal val symbolicLabel: String,
)

internal data class ZeroAccessJournalObservation(
    internal val attempts: List<ZeroAccessAttemptObservation>,
    internal val sessions: List<ZeroAccessSessionObservation>,
    internal val pendingOperationIds: List<OperationId>,
)

internal enum class ZeroAccessAuthenticationReply { AUTHENTICATED, AUTHENTICATION_REJECTED, UNAVAILABLE, STORAGE_UNCERTAIN }

internal class HeldZeroAccessReply<T>(internal val input: T) {
    internal val captured = CompletableDeferred<Unit>()
    private val released = CompletableDeferred<Unit>()
    internal fun release() { released.complete(Unit) }
    internal suspend fun awaitRelease() { captured.complete(Unit); released.await() }
}

internal data class ZeroAccessAuthenticationInput(
    internal val reply: ZeroAccessAuthenticationReply,
    internal val view: LocalSessionView,
)

/**
 * Scheduled semantic reply data only. It contains no verifier, authorization/policy engine,
 * business records or real transport. Syntax-valid token locators correlate calls; they prove
 * no bearer authenticity. Lifecycle methods expose no fixed F-REQUESTS request-ID argument.
 */
internal class ControlledZeroAccessClient(
    private val owner: LocalClientIdentity,
    private val renewal: FrozenZeroAccessRenewalInput,
    private val signInReply: ZeroAccessAuthenticationReply,
) : LocalMasterClient {
    internal val connectionId: DomainId = DomainId.random()
    internal val login: LocalLoginIdentifier = LocalLoginIdentifier(owner.actorId.value)
    internal val signInCalls = AtomicInteger()
    internal val refreshCalls = AtomicInteger()
    internal val currentCalls = AtomicInteger()
    internal val refreshCredentials = CopyOnWriteArrayList<ZeroAccessCredentialObservation>()
    internal val currentCredentials = CopyOnWriteArrayList<ZeroAccessCredentialObservation>()
    internal val logoutLocators = CopyOnWriteArrayList<DomainId>()
    internal val protectedCalls = CopyOnWriteArrayList<String>()
    private val healthState = MutableStateFlow(LocalMasterEndpointHealth.ACTIVE)
    override val health: StateFlow<LocalMasterEndpointHealth> get() = healthState
    private val bootId = DomainId.random()
    private var viewInput = view(renewal.policy, SessionGeneration(1), renewal.predecessorSessionId,
        MonotonicMoment(bootId, renewal.deadlineElapsedMillis(renewal.originalWorkDeadlineUtc)))
    private val currentReplies = Channel<HeldZeroAccessReply<LocalSessionView>>(Channel.UNLIMITED)
    private val refreshReplies = Channel<HeldZeroAccessReply<ZeroAccessAuthenticationInput>>(Channel.UNLIMITED)
    private val logoutReplies = Channel<HeldZeroAccessReply<LocalSignOutResult>>(Channel.UNLIMITED)
    private val held = CopyOnWriteArrayList<HeldZeroAccessReply<*>>()

    internal fun holdCurrent(policy: ControlledPolicyInput? = null): HeldZeroAccessReply<LocalSessionView> =
        HeldZeroAccessReply(policy?.let {
            view(it, viewInput.generation, viewInput.sessionId, viewInput.workExpiresAt)
        } ?: viewInput).also {
            held += it
            check(currentReplies.trySend(it).isSuccess)
        }

    internal fun holdFrozenSuccessorRenewal(): HeldZeroAccessReply<ZeroAccessAuthenticationInput> =
        holdRefresh(ZeroAccessAuthenticationReply.AUTHENTICATED,
            view(renewal.policy, viewInput.generation.next(), renewal.successorSessionId,
                MonotonicMoment(bootId, renewal.deadlineElapsedMillis(renewal.successorWorkDeadlineUtc))))

    internal fun holdRefreshFailure(reply: ZeroAccessAuthenticationReply): HeldZeroAccessReply<ZeroAccessAuthenticationInput> {
        check(reply != ZeroAccessAuthenticationReply.AUTHENTICATED)
        return holdRefresh(reply, viewInput)
    }

    private fun holdRefresh(reply: ZeroAccessAuthenticationReply, view: LocalSessionView): HeldZeroAccessReply<ZeroAccessAuthenticationInput> =
        HeldZeroAccessReply(ZeroAccessAuthenticationInput(reply, view)).also {
            held += it
            check(refreshReplies.trySend(it).isSuccess)
        }

    internal fun holdLogout(reply: LocalSignOutResult): HeldZeroAccessReply<LocalSignOutResult> = HeldZeroAccessReply(reply).also {
        held += it
        check(logoutReplies.trySend(it).isSuccess)
    }

    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult {
        signInCalls.incrementAndGet()
        check(connectionId == this.connectionId && login == this.login && !explicitDeviceMove)
        return authentication(ZeroAccessAuthenticationInput(signInReply, viewInput))
    }

    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult {
        refreshCalls.incrementAndGet()
        refreshCredentials += requireControlledCredential(credentials, "ULR1", labels(viewInput.sessionId).refresh)
        val reply = refreshReplies.tryReceive().getOrNull() ?: return LocalAuthenticationResult.Unavailable
        reply.awaitRelease()
        if (reply.input.reply == ZeroAccessAuthenticationReply.AUTHENTICATED) viewInput = reply.input.view
        return authentication(reply.input)
    }

    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult {
        currentCalls.incrementAndGet()
        currentCredentials += requireControlledCredential(credentials, "ULA1", labels(viewInput.sessionId).access)
        val reply = currentReplies.tryReceive().getOrNull()
        if (reply != null) { reply.awaitRelease(); viewInput = reply.input }
        return LocalSessionViewResult.Current(reply?.input ?: viewInput)
    }

    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult {
        logoutLocators += requireControlledCredential(credentials, "ULR1", labels(viewInput.sessionId).refresh).issuedSessionId
        val reply = logoutReplies.tryReceive().getOrNull() ?: return LocalSignOutResult.Unavailable
        reply.awaitRelease()
        return reply.input
    }

    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        protectedCalls += "execute:${request.operationId.value}"
        return MutationReply.Deferred(request.operationId, DeferredReason.WRITER_NOT_QUALIFIED)
    }

    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply {
        protectedCalls += "lookup:${identity.operationId.value}"
        return LookupReply.Deferred(identity.operationId, DeferredReason.WRITER_NOT_QUALIFIED)
    }

    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        protectedCalls += "read:${request.query.name}"
        return ReadReply.Unavailable(ReadFailure.WRITER_NOT_QUALIFIED)
    }

    internal fun releaseHeldReplies() { held.forEach { it.release() } }
    internal fun close() { releaseHeldReplies(); currentReplies.cancel(); refreshReplies.cancel(); logoutReplies.cancel() }

    private fun view(input: ControlledPolicyInput, generation: SessionGeneration, sessionId: DomainId,
        workExpiresAt: MonotonicMoment): LocalSessionView {
        val policy = ActivatedLocalPolicy(owner.scope, owner.actorId, input.revision, input.permissions.map {
            PermissionGrant(it, owner.scope, GrantEffect.ALLOW, namedSubjectId = owner.actorId)
        }, LocalSessionPolicy(maximumConcurrentSessions = 1), ThrottlePolicy())
        return LocalSessionView(owner.actorId, owner.scope, owner.deviceId, sessionId, generation, login,
            policy, MonotonicMoment(bootId, 10_000 + generation.value), workExpiresAt)
    }

    private fun authentication(input: ZeroAccessAuthenticationInput): LocalAuthenticationResult = when (input.reply) {
        ZeroAccessAuthenticationReply.AUTHENTICATED -> LocalAuthenticationResult.Authenticated(
            LocalSessionTokens(input.view,
                controlledToken("ULA1", input.view.sessionId, labels(input.view.sessionId).access),
                controlledToken("ULR1", input.view.sessionId, labels(input.view.sessionId).refresh)))
        ZeroAccessAuthenticationReply.AUTHENTICATION_REJECTED ->
            LocalAuthenticationResult.Denied(LocalAuthenticationDenial.AUTHENTICATION_REFUSED)
        ZeroAccessAuthenticationReply.UNAVAILABLE -> LocalAuthenticationResult.Unavailable
        ZeroAccessAuthenticationReply.STORAGE_UNCERTAIN -> LocalAuthenticationResult.StorageUncertain
    }

    private fun controlledToken(prefix: String, sessionId: DomainId, symbolicLabel: String): OpaqueCredential {
        val bytes = ByteArray(52)
        try {
            prefix.encodeToByteArray().copyInto(bytes)
            val id = UUID.fromString(sessionId.value)
            ByteBuffer.wrap(bytes, 4, 16).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits)
            val label = symbolicLabel.encodeToByteArray()
            check(label.size in 1..32)
            label.copyInto(bytes, 20)
            return OpaqueCredential(bytes)
        } finally { bytes.fill(0) }
    }

    /** Returns only an authored marker whose bytes match, never arbitrary bearer data. */
    internal fun observeCredential(credential: OpaqueCredential, prefix: String): ZeroAccessCredentialObservation =
        credential.useBytes { bytes ->
            check(bytes.size == 52 && prefix.length == 4 && prefix.indices.all {
                bytes[it] == prefix[it].code.toByte()
            })
            val locator = ByteBuffer.wrap(bytes, 4, 16)
            val sessionId = DomainId(UUID(locator.long, locator.long).toString())
            val candidates = if (prefix == "ULA1") listOf(renewal.predecessorCredentials.access,
                renewal.successorCredentials.access) else {
                check(prefix == "ULR1")
                listOf(renewal.predecessorCredentials.refresh, renewal.successorCredentials.refresh)
            }
            val matched = candidates.singleOrNull { candidate ->
                val label = candidate.encodeToByteArray()
                label.size in 1..32 && label.indices.all { bytes[20 + it] == label[it] } &&
                    (20 + label.size until 52).all { bytes[it] == 0.toByte() }
            } ?: error("The encrypted controlled LZA credential does not contain a frozen symbolic marker.")
            ZeroAccessCredentialObservation(sessionId, prefix, matched)
        }

    /** Controlled UTC projection only; raw stored cutoff equality is asserted separately. */
    internal fun controlledDeadlineUtc(moment: MonotonicMoment): String {
        check(moment.bootId == bootId)
        return renewal.controlledTimeUtc.plusMillis(moment.elapsedMillis).toString()
    }

    private fun labels(sessionId: DomainId): FrozenZeroAccessCredentialLabels = when (sessionId) {
        renewal.predecessorSessionId -> renewal.predecessorCredentials
        renewal.successorSessionId -> renewal.successorCredentials
        else -> error("No frozen controlled LZA credentials are assigned to this issued identity.")
    }

    private fun requireControlledCredential(credentials: LocalGatewayCredentials, prefix: String,
        symbolicLabel: String): ZeroAccessCredentialObservation {
        check(credentials.connectionId == connectionId)
        return observeCredential(credentials.bearer, prefix).also {
            check(it.issuedSessionId == viewInput.sessionId && it.symbolicLabel == symbolicLabel)
        }
    }
}
