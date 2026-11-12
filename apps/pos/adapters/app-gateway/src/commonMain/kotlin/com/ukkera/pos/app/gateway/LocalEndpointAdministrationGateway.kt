package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Management of the actually bound local master. Administrative intents live in the encrypted
 * client control journal, NOT a fabricated business MutationReply. Passwords never enter it.
 * The credential-journal mutex is held only for bounded local IO, never across an RPC/hash/read;
 * session refresh, revocation and private-view invalidation can continue during administration.
 */
public class LocalEndpointAdministrationGateway internal constructor(
    private val connection: LocalMasterClientConnection,
    private val journal: LocalClientCredentialJournal,
    private val owner: LocalClientIdentity,
    private val credentials: LocalWorkflowCredentialSource,
    /** Negative-only actual session retirement; never an authorization or currentness provider. */
    private val retireOriginalSession: () -> Unit,
) : LocalAdministrationUiGateway {
    public constructor(connection: LocalMasterClientConnection, journal: LocalClientCredentialJournal,
        owner: LocalClientIdentity, credentials: LocalWorkflowCredentialSource) :
        this(connection, journal, owner, credentials, {})

    private val requests = Mutex()
    private val retired = MutableStateFlow(false)
    private val endpoint = checkNotNull(connection.administrationClient)
    override val configured: Boolean = true
    init {
        require(journal.owner.scope == owner.scope && journal.owner.deviceId == owner.deviceId &&
            connection.peer.scope == owner.scope && connection.peer.deviceId == owner.deviceId)
    }

    override suspend fun read(query: LocalAdministrationQuery): LocalAdministrationReadResult = requests.withLock {
        try { withAccess { access ->
            requireGrant(access.view, query.directory)
            endpoint.readAdministration(access.lease.credentials, query).also { result ->
                if (result is LocalAdministrationReadResult.Available) validateView(result.view, query)
            }
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: GatewayFailure) { if (failure.problem in setOf(LocalServiceProblem.DENIED, LocalServiceProblem.SESSION_ENDED))
            LocalAdministrationReadResult.Denied else LocalAdministrationReadResult.Unavailable }
        catch (_: Exception) { LocalAdministrationReadResult.Unavailable }
    }

    override suspend fun pending(): LocalAdministrationPendingResult = requests.withLock {
        try { withAccess { access ->
            val entry = snapshot { it.administrations.singleOrNull { item -> item.owner == owner } }
            if (entry == null) {
                if (!hasGrant(access.view, LocalAdministrationDirectory.ACCOUNTS) && !hasGrant(access.view, LocalAdministrationDirectory.DEVICES))
                    throw GatewayFailure(LocalServiceProblem.DENIED)
                LocalAdministrationPendingResult.Available(null)
            } else {
                requireGrant(access.view, directory(entry.command))
                when (val result = recover(access, entry)) {
                    is LocalAdministrationResult.Known -> LocalAdministrationPendingResult.Available(result.operation)
                    is LocalAdministrationResult.Unresolved -> LocalAdministrationPendingResult.Unavailable(result.problem)
                    is LocalAdministrationResult.NotPrepared -> LocalAdministrationPendingResult.Unavailable(result.problem)
                }
            }
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: GatewayFailure) { LocalAdministrationPendingResult.Unavailable(failure.problem) }
        catch (_: Exception) { LocalAdministrationPendingResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
    }

    override suspend fun prepare(command: LocalAdministrationCommand): LocalAdministrationResult = requests.withLock {
        var writing = false
        try { withAccess { access ->
            requireGrant(access.view, directory(command))
            val existing = snapshot { it.administrations.singleOrNull { item -> item.owner == owner } }
            if (existing != null) {
                if (existing.command != command) return@withAccess LocalAdministrationResult.NotPrepared(LocalServiceProblem.RETRY_LATER)
                writing = true // This exact original may already have been dispatched.
                return@withAccess recover(access, existing)
            }
            val query = LocalAdministrationQuery(directory(command), targetId = command.targetId)
            val response = endpoint.readAdministration(access.lease.credentials, query)
            val view = (response as? LocalAdministrationReadResult.Available)?.view
                ?: return@withAccess LocalAdministrationResult.NotPrepared(readProblem(response))
            validateView(view, query)
            if (!matchesTarget(view, command)) return@withAccess LocalAdministrationResult.NotPrepared(LocalServiceProblem.INVALID_INPUT)
            access.checkCurrent()
            val entry = StoredClientAdministrationV1(owner = owner, command = command)
            entry.validate(journal.owner)
            val written = journal.requests.withLock { snapshotUnlocked { state ->
                if (state.administrations.any { it.owner == owner || it.command.requestId == command.requestId } ||
                    state.enrollments.any { it.requestId == command.requestId } || state.administrations.size >= MAX_CLIENT_ADMINISTRATIONS)
                    return@snapshotUnlocked false
                writing = true
                journal.replace(state.revision, state.next(administrations = state.administrations + entry)) == JournalWrite.DURABLE
            } }
            if (written) known(entry)
            else if (writing) LocalAdministrationResult.Unresolved(command.requestId, LocalServiceProblem.STORAGE_UNCERTAIN)
            else LocalAdministrationResult.NotPrepared(LocalServiceProblem.RETRY_LATER)
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: GatewayFailure) { if (writing) LocalAdministrationResult.Unresolved(command.requestId, failure.problem)
            else LocalAdministrationResult.NotPrepared(failure.problem) }
        catch (_: Exception) { if (writing) LocalAdministrationResult.Unresolved(command.requestId)
            else LocalAdministrationResult.NotPrepared(LocalServiceProblem.UNAVAILABLE) }
    }

    override suspend fun submit(requestId: DomainId, newPassword: CharArray?): LocalAdministrationResult {
        var owned: CharArray? = null
        try {
            if (newPassword != null && newPassword.size !in 4..1024)
                return LocalAdministrationResult.Unresolved(requestId, LocalServiceProblem.INVALID_INPUT)
            owned = newPassword?.copyOf()
            newPassword?.fill('\u0000')
            return operation(requestId) { access, original ->
                val observed = recover(access, original)
                val current = (observed as? LocalAdministrationResult.Known)?.operation ?: return@operation observed
                if (current.terminal || current.cancellationRequested) return@operation observed
                val secretRequired = original.command.action.requiresNewPassword
                // Lost password-bearing replies are lookup/cancel only. Never guess another entered
                // password equals the original, and never replay a password after a restart.
                if (secretRequired && current.phase != LocalAdministrationPhase.PREPARED) return@operation observed
                if (secretRequired != (owned != null)) return@operation LocalAdministrationResult.Unresolved(requestId, LocalServiceProblem.INVALID_INPUT)
                val dispatched = update(original.command.requestId) { before ->
                    check(before.command == original.command && !before.terminal && !before.cancellationRequested)
                    if (secretRequired) check(before.phase == StoredAdministrationPhase.PREPARED)
                    before.copy(phase = StoredAdministrationPhase.DISPATCHED)
                }
                access.checkCurrent()
                when (val result = endpoint.administer(access.lease.credentials, dispatched.command, owned)) {
                    is LocalCredentialAdministrationResult.Applied -> {
                        if (result.eventId != requestId) return@operation LocalAdministrationResult.Unresolved(requestId)
                        if (access.isOwnSessionRevocation(dispatched.command)) {
                            // This exact master's response is definite even though it deliberately
                            // invalidates the access used to obtain it. Deny the actual session
                            // synchronously BEFORE any journal suspension or private-view sample.
                            val observed = dispatched.copy(phase = StoredAdministrationPhase.APPLIED,
                                appliedEventId = result.eventId).also { it.validate(journal.owner) }
                            val definite = known(observed)
                            access.observedSelfRevocation = definite
                            retired.value = true
                            try { retireOriginalSession() } catch (_: Exception) {
                                // A cleanup failure cannot make this exact master observation
                                // uncertain. The actual installed sink invalidates the session
                                // fence/lifecycle in finally, even if a negative attachment fails.
                            }
                            withContext(NonCancellable) {
                                try { update(requestId) { before ->
                                    check(before.command == observed.command && before.phase != StoredAdministrationPhase.CANCELLED)
                                    before.copy(phase = StoredAdministrationPhase.APPLIED, appliedEventId = result.eventId)
                                } } catch (_: Exception) {
                                    // The durable DISPATCHED original still fences this identity.
                                    // Local retention is uncertain, not the observed master effect.
                                    // A new session recovers only this same actor/request via lookup.
                                }
                            }
                            return@operation definite
                        }
                        val applied = withContext(NonCancellable) { update(requestId) { before ->
                            check(before.phase != StoredAdministrationPhase.CANCELLED)
                            before.copy(phase = StoredAdministrationPhase.APPLIED, appliedEventId = result.eventId)
                        } }
                        // Ordinary administrative replies still require the final private
                        // lifetime read below. No resulting revision is invented here.
                        known(applied)
                    }
                    LocalCredentialAdministrationResult.Denied -> LocalAdministrationResult.Unresolved(requestId, LocalServiceProblem.DENIED)
                    LocalCredentialAdministrationResult.Busy -> LocalAdministrationResult.Unresolved(requestId, LocalServiceProblem.RETRY_LATER)
                    LocalCredentialAdministrationResult.StorageUncertain -> LocalAdministrationResult.Unresolved(requestId, LocalServiceProblem.STORAGE_UNCERTAIN)
                    LocalCredentialAdministrationResult.Unavailable -> LocalAdministrationResult.Unresolved(requestId)
                }
            }
        } finally { newPassword?.fill('\u0000'); owned?.fill('\u0000') }
    }

    override suspend fun lookup(requestId: DomainId): LocalAdministrationResult = operation(requestId, ::recover)

    override suspend fun cancel(requestId: DomainId): LocalAdministrationResult = operation(requestId) { access, original ->
        if (original.terminal) return@operation recover(access, original)
        val requested = update(requestId) { before ->
            check(before.command == original.command && !before.terminal)
            before.copy(phase = StoredAdministrationPhase.DISPATCHED, cancellationRequested = true)
        }
        access.checkCurrent()
        observe(requested, endpoint.cancelAdministration(access.lease.credentials, requested.command))
    }

    override suspend fun acknowledge(requestId: DomainId): Boolean = requests.withLock {
        try { withAccess { access ->
            val entry = find(requestId)
            requireGrant(access.view, directory(entry.command))
            if (!entry.terminal) return@withAccess false
            access.checkCurrent()
            journal.requests.withLock { snapshotUnlocked { state ->
                val current = state.administrations.singleOrNull { it.owner == owner && it.command.requestId == requestId }
                    ?: return@snapshotUnlocked false
                if (current != entry || !current.terminal) return@snapshotUnlocked false
                // Only an explicitly acknowledged terminal client record is retired. All
                // unrelated auth, enrollment, pending records and the master audit remain.
                journal.replace(state.revision, state.next(administrations = state.administrations.filterNot {
                    it.owner == owner && it.command.requestId == requestId
                })) == JournalWrite.DURABLE
            } }
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }

    private suspend fun recover(access: Access, entry: StoredClientAdministrationV1): LocalAdministrationResult {
        access.checkCurrent()
        return observe(entry, endpoint.lookupAdministration(access.lease.credentials, entry.command))
    }

    private suspend fun observe(entry: StoredClientAdministrationV1, status: LocalAdministrationStatus): LocalAdministrationResult = when (status) {
        is LocalAdministrationStatus.Applied -> known(remember(entry, status.receipt, cancelled = false))
        is LocalAdministrationStatus.Cancelled -> known(remember(entry, status.receipt, cancelled = true))
        LocalAdministrationStatus.NotObserved -> if (entry.terminal) LocalAdministrationResult.Unresolved(entry.command.requestId)
            else known(entry)
        LocalAdministrationStatus.Denied -> LocalAdministrationResult.Unresolved(entry.command.requestId, LocalServiceProblem.DENIED)
        LocalAdministrationStatus.Busy -> LocalAdministrationResult.Unresolved(entry.command.requestId, LocalServiceProblem.RETRY_LATER)
        LocalAdministrationStatus.StorageUncertain -> LocalAdministrationResult.Unresolved(entry.command.requestId, LocalServiceProblem.STORAGE_UNCERTAIN)
        LocalAdministrationStatus.IdentityConflict, LocalAdministrationStatus.Unavailable -> LocalAdministrationResult.Unresolved(entry.command.requestId)
    }

    private suspend fun remember(entry: StoredClientAdministrationV1, receipt: LocalAdministrationReceipt, cancelled: Boolean): StoredClientAdministrationV1 =
        withContext(NonCancellable) { update(entry.command.requestId) { before ->
            check(before.command == entry.command && (!before.terminal ||
                (before.phase == StoredAdministrationPhase.CANCELLED) == cancelled))
            check(before.receipt == null || before.receipt == receipt)
            before.copy(phase = if (cancelled) StoredAdministrationPhase.CANCELLED else StoredAdministrationPhase.APPLIED,
                appliedEventId = if (cancelled) null else receipt.requestId, receipt = receipt)
                .also { it.validate(journal.owner) }
        } }

    private suspend fun operation(id: DomainId, action: suspend (Access, StoredClientAdministrationV1) -> LocalAdministrationResult): LocalAdministrationResult =
        requests.withLock {
            try { withAccess { access ->
                val entry = find(id)
                requireGrant(access.view, directory(entry.command))
                action(access, entry)
            } } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: GatewayFailure) { LocalAdministrationResult.Unresolved(id, failure.problem) }
            catch (_: Exception) { LocalAdministrationResult.Unresolved(id) }
        }

    private suspend fun find(id: DomainId): StoredClientAdministrationV1 = snapshot {
        it.administrations.singleOrNull { entry -> entry.owner == owner && entry.command.requestId == id }
            ?: throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
    }
    private suspend fun update(id: DomainId, change: (StoredClientAdministrationV1) -> StoredClientAdministrationV1): StoredClientAdministrationV1 =
        journal.requests.withLock { snapshotUnlocked { state ->
            val before = state.administrations.singleOrNull { it.owner == owner && it.command.requestId == id }
                ?: throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
            val next = change(before).also {
                check(it.owner == before.owner && it.command == before.command)
                it.validate(journal.owner)
            }
            if (next != before && journal.replace(state.revision, state.next(administrations = state.administrations.map {
                    if (it.command.requestId == id) next else it
                })) != JournalWrite.DURABLE) throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
            next
        } }
    private suspend fun <T> snapshot(read: suspend (StoredClientCredentials) -> T): T = journal.requests.withLock { snapshotUnlocked(read) }
    private suspend fun <T> snapshotUnlocked(read: suspend (StoredClientCredentials) -> T): T {
        val state = when (val result = journal.read()) {
            is JournalRead.Value -> result.value
            JournalRead.Unavailable -> throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
        }
        return try { state.validate(journal.owner); read(state) } finally { state.destroy() }
    }

    private suspend fun <T> withAccess(body: suspend (Access) -> T): T {
        try {
            currentCoroutineContext().ensureActive()
            if (retired.value) throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
            val lease = credentials.acquire() ?: throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
            try {
                val initial = currentView(lease)
                val access = Access(lease, initial)
                val result = body(access)
                access.observedSelfRevocation?.let { definite ->
                    // Only the exact already-observed terminal metadata may cross this deliberately
                    // retired lifetime. No read, lookup, cancellation or later mutation is exempted.
                    check(result == definite && retired.value)
                    return result
                }
                val final = currentView(lease)
                if (final.sessionId != initial.sessionId || final.generation != initial.generation || final.policy.revision != initial.policy.revision)
                    throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
                access.checkCurrent()
                return result
            } finally { lease.destroy() }
        } catch (failure: GatewayFailure) {
            if (failure.problem == LocalServiceProblem.SESSION_ENDED && retired.compareAndSet(false, true)) {
                // Terminal observations must retire the exact attachment even when read/ACK
                // returns a compatible generic refusal. Ordinary target DENIED and temporary
                // unavailability are not terminal identity facts. The installed sink fences
                // only this original adapter; it cannot retire a successor attachment.
                try { retireOriginalSession() } catch (_: Exception) {
                    // The actual sink invalidates its session fence/lifecycle in finally.
                    // Failed negative holders retain their existing root cleanup custody;
                    // their failure cannot restore access or change this terminal fact.
                }
            }
            throw failure
        }
    }
    private suspend fun currentView(lease: LocalWorkflowCredentialLease): LocalSessionView {
        currentCoroutineContext().ensureActive()
        if (retired.value || !connection.isCurrent() || !lease.isCurrent()) throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
        val view = when (val result = lease.observeSession(connection.endpoint)) {
            is LocalSessionViewResult.Current -> result.view
            LocalSessionViewResult.Denied -> throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
            LocalSessionViewResult.Busy -> throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
            LocalSessionViewResult.StorageUncertain -> throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
            LocalSessionViewResult.Unavailable -> throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
        }
        if (retired.value || !connection.isCurrent() || !lease.isCurrent() || view.actorId != owner.actorId || view.deviceId != owner.deviceId ||
            view.scope != owner.scope) throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
        return view
    }
    private inner class Access(val lease: LocalWorkflowCredentialLease, val view: LocalSessionView) {
        var observedSelfRevocation: LocalAdministrationResult.Known? = null
        fun isOwnSessionRevocation(command: LocalAdministrationCommand): Boolean =
            command.action == LocalAdministrationAction.REVOKE_SESSION && command.targetId == owner.actorId &&
                command.sessionId == view.sessionId
        suspend fun checkCurrent() {
            currentCoroutineContext().ensureActive()
            if (retired.value || !connection.isCurrent() || !lease.isCurrent()) throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
        }
    }

    private fun directory(command: LocalAdministrationCommand): LocalAdministrationDirectory =
        if (command.action == LocalAdministrationAction.REVOKE_DEVICE) LocalAdministrationDirectory.DEVICES else LocalAdministrationDirectory.ACCOUNTS
    private fun hasGrant(view: LocalSessionView, directory: LocalAdministrationDirectory): Boolean {
        val permission = Permission(if (directory == LocalAdministrationDirectory.ACCOUNTS)
            SecurityAction.LOCAL_ACCOUNT_ADMINISTRATION else SecurityAction.DEVICE_ADMINISTRATION, PermissionVerb.ADMINISTER)
        val grants = view.policy.grants.filter { it.permission == permission && (it.scope == owner.scope || it.scope == owner.scope.company()) &&
            (it.namedSubjectId == null || it.namedSubjectId == owner.actorId) }
        // A denial for one named target must not hide all other management targets. This is
        // only an area preflight; the master evaluates the actual read/action binding afresh.
        return grants.any { it.effect == GrantEffect.ALLOW } && grants.none { it.effect == GrantEffect.DENY && it.targetIds == null }
    }
    private fun requireGrant(view: LocalSessionView, directory: LocalAdministrationDirectory) {
        if (!hasGrant(view, directory)) throw GatewayFailure(LocalServiceProblem.DENIED)
    }
    private fun known(entry: StoredClientAdministrationV1): LocalAdministrationResult.Known = LocalAdministrationResult.Known(
        LocalAdministrationPending(entry.command, LocalAdministrationPhase.valueOf(entry.phase.name), entry.cancellationRequested, entry.receipt))
    private fun validateView(view: LocalAdministrationView, query: LocalAdministrationQuery) {
        check(view.scope == owner.scope && view.directory == query.directory)
        val ids = if (query.directory == LocalAdministrationDirectory.ACCOUNTS) view.accounts.map { it.actorId }
            else view.devices.map { it.deviceId }
        check(ids.size <= query.pageSize && ids == ids.distinct().sortedBy { it.value })
        val cursor = query.cursor
        if (cursor != null) check(view.snapshot == cursor.snapshot && ids.all { it.value > cursor.afterId.value })
        if (query.targetId != null) check(ids == listOf(query.targetId) && view.next == null)
        view.next?.let { check(ids.isNotEmpty() && it.afterId == ids.last() && it.snapshot == view.snapshot) }
        if (query.directory == LocalAdministrationDirectory.ACCOUNTS) {
            check(view.devices.isEmpty() && (query.targetId != null) == (view.accountDetail != null))
            view.accountDetail?.let { check(it.account == view.accounts.single() && it.policy.scope == owner.scope &&
                it.policy.actorId == it.account.actorId && it.policy.revision == it.account.policyRevision &&
                it.retainedSessions.size <= 256 && it.retainedSessions.map { session -> session.sessionId }.distinct().size == it.retainedSessions.size &&
                it.retainedSessions.all { session -> session.scope == owner.scope && session.actorId == it.account.actorId } &&
                it.account.activeSessionCount == it.retainedSessions.count { session -> session.status == SessionStatus.ACTIVE }) }
        } else check(view.accounts.isEmpty() && view.accountDetail == null && view.devices.all { it.scope == owner.scope })
    }
    private fun matchesTarget(view: LocalAdministrationView, command: LocalAdministrationCommand): Boolean {
        if (command.action == LocalAdministrationAction.REVOKE_DEVICE) return view.devices.singleOrNull()?.let {
            it.deviceId == command.targetId && it.revision == command.expectedRevision && it.admitted
        } == true
        val detail = view.accountDetail ?: return false
        val account = detail.account
        if (account.actorId != command.targetId || account.accountRevision != command.expectedRevision) return false
        return when (command.action) {
            LocalAdministrationAction.ACTIVATE_ACCOUNT -> !account.enabled && !account.credentialPresent &&
                account.actorId != owner.actorId && detail.retainedSessions.none { it.status == SessionStatus.ACTIVE } &&
                command.expectedCredentialRevision == account.credentialRevision && command.activationRevisions?.let {
                    it.policy == account.policyRevision && it.configuration == account.configurationRevision } == true
            LocalAdministrationAction.REACTIVATE_ACCOUNT -> !account.enabled && account.credentialPresent &&
                account.actorId != owner.actorId && detail.retainedSessions.none { it.status == SessionStatus.ACTIVE } &&
                command.reactivationRevisions?.let { expected -> expected.credential == account.credentialRevision &&
                    expected.policy == account.policyRevision && expected.configuration == account.configurationRevision } == true
            LocalAdministrationAction.DISABLE_ACCOUNT -> account.enabled
            LocalAdministrationAction.RESET_CREDENTIAL -> account.enabled && account.credentialRevision == command.expectedCredentialRevision
            LocalAdministrationAction.REVOKE_SESSION -> detail.retainedSessions.any { it.sessionId == command.sessionId && it.status == SessionStatus.ACTIVE }
            LocalAdministrationAction.REVOKE_DEVICE -> false
        }
    }
    private fun readProblem(result: LocalAdministrationReadResult): LocalServiceProblem = when (result) {
        LocalAdministrationReadResult.Denied -> LocalServiceProblem.DENIED
        LocalAdministrationReadResult.Busy -> LocalServiceProblem.RETRY_LATER
        LocalAdministrationReadResult.StorageUncertain -> LocalServiceProblem.STORAGE_UNCERTAIN
        else -> LocalServiceProblem.UNAVAILABLE
    }
}
