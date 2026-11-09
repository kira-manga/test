package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.LocalEnrollmentMode
import com.ukkera.pos.app.LocalEnrollmentResult
import com.ukkera.pos.app.LocalEnrollmentStatus
import com.ukkera.pos.app.LocalEnrollmentUiGateway
import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.app.LocalText
import com.ukkera.pos.database.LocalMasterEnrollmentClient
import com.ukkera.pos.security.LocalAccountProvisioning
import com.ukkera.pos.security.LocalCredentialAdministrationResult
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.LocalProvisioningStatus
import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Read-only requested-definition producer from explicit root setup, never a grant/permit factory. */
public fun interface LocalEnrollmentDefinitionSource {
    public suspend fun prepare(requestId: DomainId, login: LocalLoginIdentifier): LocalAccountProvisioning?
}

/**
 * Actual installed LOCAL setup offering. Its label/mode is not authority: the endpoint must still
 * authorize the entire original definition digest using its protected provisioning ceremony.
 * No default policy, actor, password, administrator or device admission is invented by this adapter.
 */
public class LocalEnrollmentOffering(
    public val owner: LocalClientDeviceIdentity,
    public val mode: LocalEnrollmentMode,
    public val context: LocalText,
    public val definitions: LocalEnrollmentDefinitionSource,
    private val currentLifetime: () -> Boolean,
) {
    init { require(mode != LocalEnrollmentMode.DISABLED) }
    public fun isCurrent(): Boolean = try { currentLifetime() } catch (_: Exception) { false }
    override fun toString(): String = "LocalEnrollmentOffering([REDACTED])"
}

/**
 * Separately supplied enrollment capability for this EXACT captured client connection. The root
 * must bind both ports to the same actual master/peer lifetime; this pair is not ceremony authority.
 * Nothing is inferred or cast from the general client port. Replace/withdraw this binding together
 * with its connection; an old pair cannot enroll through a newly published general connection.
 */
public class LocalMasterEnrollmentConnection(
    internal val connection: LocalMasterClientConnection,
    internal val client: LocalMasterEnrollmentClient,
) {
    override fun toString(): String = "LocalMasterEnrollmentConnection([REDACTED])"
}

/**
 * Concrete original-ID enrollment + authenticated lookup. The complete requested policy/devices,
 * actor/login and credential-presence fact are durably encrypted before dispatch; passwords are
 * only client-worker/endpoint owned arrays. Unknown/NotRecorded/denied lookup never permits a new
 * definition or fresh ID. This endpoint has NO employee display-name storage, so the UI omits it.
 */
public class LocalEndpointEnrollmentGateway private constructor(
    private val connections: StateFlow<LocalMasterClientConnection?>,
    private val credentials: LocalClientCredentialJournal,
    private val offerings: StateFlow<LocalEnrollmentOffering?>?,
    private val enrollmentConnections: StateFlow<LocalMasterEnrollmentConnection?>?,
    private val publications: StateFlow<LocalEnrollmentPublication?>?,
    dispatcher: CoroutineDispatcher,
) : LocalEnrollmentUiGateway {
    /** Keep the separately supplied legacy ports and their original behavior unchanged. */
    public constructor(connections: StateFlow<LocalMasterClientConnection?>,
        credentials: LocalClientCredentialJournal, offerings: StateFlow<LocalEnrollmentOffering?>,
        enrollmentConnections: StateFlow<LocalMasterEnrollmentConnection?>,
        dispatcher: CoroutineDispatcher = Dispatchers.Default) :
        this(connections, credentials, offerings, enrollmentConnections, null, dispatcher)

    /** Actual native roots publish requested data/port together, not two mutable flows. */
    internal constructor(connections: StateFlow<LocalMasterClientConnection?>,
        credentials: LocalClientCredentialJournal, publications: StateFlow<LocalEnrollmentPublication?>,
        dispatcher: CoroutineDispatcher = Dispatchers.Default) :
        this(connections, credentials, null, null, publications, dispatcher)

    init {
        check(if (publications == null) offerings != null && enrollmentConnections != null
            else offerings == null && enrollmentConnections == null)
    }
    private val closed = MutableStateFlow(false)
    private val busy = MutableStateFlow(false)
    private val workJob = SupervisorJob()
    private val work = CoroutineScope(workJob + dispatcher)

    override public suspend fun status(): LocalEnrollmentStatus = try {
        credentials.requests.withLock { snapshot { state ->
            val publication = currentAtomicPublication()
            val offering = if (publications == null) currentOffering() else publication?.offering
            val enrollment = if (publications == null) currentEnrollment() else publication?.enrollment
            LocalEnrollmentStatus(offering?.mode ?: LocalEnrollmentMode.DISABLED,
                available = offering != null && enrollment != null && state.enrollments.size < MAX_CLIENT_ENROLLMENTS,
                context = offering?.context,
                pendingRequestId = state.enrollments.singleOrNull { it.outcome == StoredEnrollmentOutcomeV1.PENDING }?.requestId,
                supportsDisplayName = false)
        } }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { LocalEnrollmentStatus(LocalEnrollmentMode.DISABLED, false, supportsDisplayName = false) }

    override public suspend fun enroll(requestId: DomainId, account: String, displayName: String, password: CharArray): LocalEnrollmentResult {
        if (displayName.isNotEmpty() || password.size !in 4..256) {
            password.fill('\u0000'); return LocalEnrollmentResult.Rejected(LocalServiceProblem.INVALID_INPUT)
        }
        val login = try { LocalLoginIdentifier(account) } catch (_: IllegalArgumentException) {
            password.fill('\u0000'); return LocalEnrollmentResult.Rejected(LocalServiceProblem.INVALID_INPUT)
        }
        val owned = password.copyOf()
        password.fill('\u0000')
        return ownedCall(owned) { credentials.requests.withLock {
            snapshot { before ->
                val existing = before.enrollments.singleOrNull { it.requestId == requestId }
                if (existing != null) {
                    if (existing.login != login.value) return@snapshot LocalEnrollmentResult.Unresolved
                    return@snapshot lookupOwned(before, existing) // NEVER resend a retained password operation
                }
                if (before.enrollments.any { it.outcome == StoredEnrollmentOutcomeV1.PENDING }) return@snapshot LocalEnrollmentResult.Unresolved
                if (before.enrollments.size >= MAX_CLIENT_ENROLLMENTS)
                    return@snapshot LocalEnrollmentResult.Rejected(LocalServiceProblem.RETRY_LATER)
                val publication = currentAtomicPublication()
                val enrollment = (if (publications == null) currentEnrollment() else publication?.enrollment)
                    ?: return@snapshot LocalEnrollmentResult.Rejected(LocalServiceProblem.UNAVAILABLE)
                val offering = (if (publications == null) currentOffering() else publication?.offering)
                    ?: return@snapshot LocalEnrollmentResult.Rejected(LocalServiceProblem.NOT_CONFIGURED)
                val definition = offering.definitions.prepare(requestId, login)
                    ?: return@snapshot LocalEnrollmentResult.Rejected(LocalServiceProblem.DENIED)
                if (definition.login != login || definition.policy.scope != credentials.owner.scope ||
                    credentials.owner.deviceId !in definition.admittedDeviceIds)
                    return@snapshot LocalEnrollmentResult.Rejected(LocalServiceProblem.INVALID_INPUT)
                val entry = try { StoredClientEnrollmentV1.capture(requestId, definition).also { it.validate(credentials.owner) } }
                catch (_: IllegalArgumentException) { return@snapshot LocalEnrollmentResult.Rejected(LocalServiceProblem.INVALID_INPUT) }
                if (!stillSelected(publication, offering, enrollment))
                    return@snapshot LocalEnrollmentResult.Rejected(LocalServiceProblem.UNAVAILABLE)
                val pending = before.next(enrollments = before.enrollments + entry)
                if (!durable(before, pending)) return@snapshot LocalEnrollmentResult.Unresolved
                if (!stillSelected(publication, offering, enrollment)) return@snapshot LocalEnrollmentResult.Unresolved
                when (val result = enrollment.client.provisionAccount(requestId, entry.definition(), owned)) {
                    is LocalCredentialAdministrationResult.Applied -> {
                        retainOutcome(pending, entry.copy(outcome = StoredEnrollmentOutcomeV1.APPLIED, eventId = result.eventId))
                        LocalEnrollmentResult.Created // a failed client receipt cannot undo known committed administration
                    }
                    LocalCredentialAdministrationResult.Denied -> {
                        if (retainOutcome(pending, entry.copy(outcome = StoredEnrollmentOutcomeV1.REJECTED)))
                            LocalEnrollmentResult.Rejected(LocalServiceProblem.DENIED) else LocalEnrollmentResult.Unresolved
                    }
                    LocalCredentialAdministrationResult.Busy, LocalCredentialAdministrationResult.Unavailable,
                    LocalCredentialAdministrationResult.StorageUncertain -> LocalEnrollmentResult.Unresolved
                }
            }
        } }
    }

    override public suspend fun lookup(requestId: DomainId): LocalEnrollmentResult = ownedCall(null) {
        credentials.requests.withLock { snapshot { state ->
            val entry = state.enrollments.singleOrNull { it.requestId == requestId } ?: return@snapshot LocalEnrollmentResult.Unresolved
            lookupOwned(state, entry)
        } }
    }

    private suspend fun lookupOwned(state: StoredClientCredentials, entry: StoredClientEnrollmentV1): LocalEnrollmentResult {
        if (entry.outcome == StoredEnrollmentOutcomeV1.REJECTED) return LocalEnrollmentResult.Rejected(LocalServiceProblem.DENIED)
        val enrollment = currentEnrollment() ?: return LocalEnrollmentResult.Unresolved
        return when (val result = enrollment.client.lookupAccountProvisioning(entry.requestId, entry.definition(), entry.credentialWasProvided)) {
            is LocalProvisioningStatus.Applied -> {
                if (entry.eventId != null && entry.eventId != result.eventId) LocalEnrollmentResult.Unresolved
                else {
                    if (entry.outcome != StoredEnrollmentOutcomeV1.APPLIED)
                        retainOutcome(state, entry.copy(outcome = StoredEnrollmentOutcomeV1.APPLIED, eventId = result.eventId))
                    LocalEnrollmentResult.Created
                }
            }
            LocalProvisioningStatus.NotRecorded, LocalProvisioningStatus.Denied, LocalProvisioningStatus.Busy,
            LocalProvisioningStatus.Unavailable, LocalProvisioningStatus.StorageUncertain -> LocalEnrollmentResult.Unresolved
        }
    }

    private suspend fun retainOutcome(state: StoredClientCredentials, entry: StoredClientEnrollmentV1): Boolean = withContext(NonCancellable) {
        try { durable(state, state.next(enrollments = state.enrollments.map { if (it.requestId == entry.requestId) entry else it })) }
        catch (_: Exception) { false }
    }
    private fun currentOffering(): LocalEnrollmentOffering? {
        if (publications != null) return currentAtomicPublication()?.offering
        return if (closed.value) null else offerings?.value?.takeIf(::offeringCurrent)
    }
    private fun currentEnrollment(): LocalMasterEnrollmentConnection? {
        if (publications != null) return currentAtomicPublication()?.enrollment
        if (closed.value) return null
        return enrollmentConnections?.value?.takeIf(::enrollmentCurrent)
    }
    private fun offeringCurrent(offering: LocalEnrollmentOffering): Boolean =
        offering.owner == credentials.owner && offering.isCurrent()
    private fun enrollmentCurrent(enrollment: LocalMasterEnrollmentConnection): Boolean {
        val connection = enrollment.connection
        return connections.value === connection && connection.peer.scope == credentials.owner.scope &&
            connection.peer.deviceId == credentials.owner.deviceId && connection.isCurrent()
    }
    private fun currentAtomicPublication(): LocalEnrollmentPublication? {
        if (closed.value) return null
        return publications?.value?.takeIf { offeringCurrent(it.offering) && enrollmentCurrent(it.enrollment) }
    }
    private fun stillSelected(publication: LocalEnrollmentPublication?, offering: LocalEnrollmentOffering,
        enrollment: LocalMasterEnrollmentConnection): Boolean = if (publications == null)
        currentOffering() === offering && currentEnrollment() === enrollment
    else publication != null && currentAtomicPublication() === publication
    private suspend fun <T> snapshot(block: suspend (StoredClientCredentials) -> T): T {
        val state = when (val read = credentials.read()) { is JournalRead.Value -> read.value; JournalRead.Unavailable -> error("client.enrollment.unavailable") }
        return try { state.validate(credentials.owner); block(state) } finally { state.destroy() }
    }
    private suspend fun durable(before: StoredClientCredentials, next: StoredClientCredentials): Boolean =
        credentials.replace(before.revision, next) == JournalWrite.DURABLE

    /** Cancellation of a screen only cancels delivery, not the already durably staged ceremony. */
    private suspend fun ownedCall(password: CharArray?, block: suspend () -> LocalEnrollmentResult): LocalEnrollmentResult {
        val replies = Channel<LocalEnrollmentResult>(1)
        var started = false
        var admitted = false
        try {
            currentCoroutineContext().ensureActive()
            if (closed.value || !busy.compareAndSet(false, true)) return LocalEnrollmentResult.Unresolved
            admitted = true
            val job = work.launch {
                val result = try { block() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LocalEnrollmentResult.Unresolved }
                replies.trySend(result)
            }
            started = true
            job.invokeOnCompletion { password?.fill('\u0000'); busy.value = false; replies.close() }
            return replies.receive()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return LocalEnrollmentResult.Unresolved }
        finally {
            replies.cancel()
            if (!started) { password?.fill('\u0000'); if (admitted) busy.value = false }
        }
    }

    public fun close() { if (closed.compareAndSet(false, true)) work.cancel() }

    /** Wait only for this client's buffers/IO before root-owned journal closure, not server completion. */
    public suspend fun closeAndAwait() {
        close()
        withContext(NonCancellable) { workJob.join() }
    }
}
