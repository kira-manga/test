package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore

/** Protected management reads and original-ID account actions over the SAME live security store. */
internal class RoomLocalAdministrationService(
    private val scope: BranchScope,
    private val store: RoomLocalSecurityStore,
    private val source: RoomCurrentLocalAuthorizationSource,
    private val peers: RoomLocalAccessPeerSource,
    private val principals: TrustedPrincipalFactory,
    private val passwords: LocalPasswordHasher,
    private val pepper: ProtectedPepperReference,
    private val legacy: LocalCredentialAdministration,
) : LocalAdministrationService {
    private val slots = Semaphore(1)
    init { check(source.belongsTo(store)) }

    override suspend fun reviewEmployeeCreation(operator: LocalGatewayCredentials,
        original: LocalDelegatedEmployeeCreationV1): LocalEmployeeCreationReviewV1 =
        bounded(LocalEmployeeCreationReviewV1.Busy, LocalEmployeeCreationReviewV1.Denied,
            LocalEmployeeCreationReviewV1.Unavailable, LocalEmployeeCreationReviewV1.StorageUncertain) {
            val (principal, peer) = authenticate(operator)
            if (original.scope != scope || original.operatorId != principal.actorId) return@bounded LocalEmployeeCreationReviewV1.Denied
            authorizedRead(principal, peer, original.authorizationRequirement()) { transaction, _ ->
                reviewLocalEmployeeCreationInputs(transaction, principal, original)
            }
        }

    override suspend fun readAdministration(operator: LocalGatewayCredentials, query: LocalAdministrationQuery): LocalAdministrationReadResult =
        bounded(LocalAdministrationReadResult.Busy, LocalAdministrationReadResult.Denied,
            LocalAdministrationReadResult.Unavailable, LocalAdministrationReadResult.StorageUncertain) {
            val (principal, peer) = authenticate(operator)
            val action = if (query.directory == LocalAdministrationDirectory.ACCOUNTS)
                SecurityAction.LOCAL_ACCOUNT_ADMINISTRATION else SecurityAction.DEVICE_ADMINISTRATION
            val readDigest = digestText("ukkera.local-administration-query/v1", query.directory.name,
                query.targetId?.value.orEmpty(), query.cursor?.afterId?.value.orEmpty(),
                query.cursor?.snapshot?.value.orEmpty(), query.pageSize.toString())
            val requirement = AuthorizationRequirement(Permission(action, PermissionVerb.ADMINISTER), scope,
                ActionBinding(query.targetId ?: scope.branchId, Revision(0), readDigest, reasonCode = "local-administration-read"))
            authorizedRead(principal, peer, requirement) { transaction, permitted ->
                directory(transaction, principal, permitted.facts, query)
            }
        }

    override suspend fun lookupAdministration(operator: LocalGatewayCredentials, command: LocalAdministrationCommand): LocalAdministrationStatus =
        bounded(LocalAdministrationStatus.Busy, LocalAdministrationStatus.Denied,
            LocalAdministrationStatus.Unavailable, LocalAdministrationStatus.StorageUncertain) {
            val (principal, peer) = authenticate(operator)
            val digest = localAdministrationRequestDigest(scope, principal.actorId, command)
            authorizedRead(principal, peer, requirement(command, digest)) { transaction, _ ->
                val event = transaction.readAuthEvent(scope, command.requestId)
                when {
                    event == null -> LocalAdministrationStatus.NotObserved
                    matchesCancellation(event, principal, command, digest) -> LocalAdministrationStatus.Cancelled(receipt(event, command, digest))
                    !matches(event, principal, command, digest) -> LocalAdministrationStatus.IdentityConflict
                    else -> LocalAdministrationStatus.Applied(receipt(event, command, digest))
                }
            }
        }

    override suspend fun cancelAdministration(operator: LocalGatewayCredentials, command: LocalAdministrationCommand): LocalAdministrationStatus =
        bounded(LocalAdministrationStatus.Busy, LocalAdministrationStatus.Denied,
            LocalAdministrationStatus.Unavailable, LocalAdministrationStatus.StorageUncertain) {
            val (principal, peer) = authenticate(operator)
            val digest = localAdministrationRequestDigest(scope, principal.actorId, command)
            localSecurityLane(store.database) {
                var finalization: RoomLocalAdministrationObservationFrame? = null
                val result = store.transaction { transaction ->
                    val permitted = authorize(transaction, principal, peer, requirement(command, digest))
                    val prior = transaction.readAuthEvent(scope, command.requestId)
                    if (prior != null) {
                        val frame = readFrame(principal, peer, permitted)
                        finalization = frame
                        transaction.attachAdministrationObservationFrame(frame)
                        return@transaction when {
                            matches(prior, principal, command, digest) -> LocalAdministrationStatus.Applied(receipt(prior, command, digest))
                            matchesCancellation(prior, principal, command, digest) -> LocalAdministrationStatus.Cancelled(receipt(prior, command, digest))
                            else -> LocalAdministrationStatus.IdentityConflict
                        }
                    }
                    // This one immutable audit shares the execution request's key. Whichever
                    // transaction wins fences the other; no account/session/device effect is undone.
                    val event = LocalAuthEvent(scope, command.requestId, LocalAuthEventKind.ADMINISTRATION_CANCELLED,
                        command.targetId, principal.actorId, command.sessionId, null,
                        source.observeCurrent(currentCoroutineContext()).moment, digest)
                    val frame = RoomLocalAdministrationObservationFrame(principal, permitted.facts, permitted.authorization,
                        source, peer, currentCoroutineContext(), event,
                        originalCommand = command, originalObservation = permitted.observation)
                    finalization = frame
                    transaction.attachAdministrationObservationFrame(frame)
                    transaction.insertAuthEvent(event)
                    LocalAdministrationStatus.Cancelled(receipt(event, command, digest))
                }
                if (finalization?.finishAfterCommit(currentCoroutineContext()) != true) protectedLocalUnavailable()
                result
            }
        }

    override suspend fun administer(operator: LocalGatewayCredentials, command: LocalAdministrationCommand,
        newPassword: CharArray?): LocalCredentialAdministrationResult = bounded(LocalCredentialAdministrationResult.Busy,
        LocalCredentialAdministrationResult.Denied, LocalCredentialAdministrationResult.Unavailable,
        LocalCredentialAdministrationResult.StorageUncertain) {
        if (command.action.requiresNewPassword != (newPassword != null) ||
            (newPassword != null && newPassword.size !in 4..1024)) return@bounded LocalCredentialAdministrationResult.Denied
        if (command.action == LocalAdministrationAction.REVOKE_DEVICE)
            return@bounded legacy.revokeDevice(operator, command.requestId, command.targetId, command.expectedRevision)
        val owned = newPassword?.copyOf()
        try {
            val (principal, peer) = authenticate(operator)
            val digest = localAdministrationRequestDigest(scope, principal.actorId, command)
            val requirement = requirement(command, digest)
            val password = if (owned == null) null else {
                val revision = checkNotNull(command.expectedCredentialRevision)
                if (revision.value == Long.MAX_VALUE) return@bounded LocalCredentialAdministrationResult.Denied
                // Authorize before expensive hashing, without keeping a SQLite writer during it.
                val mayStart = authorizedRead(principal, peer, requirement) { transaction, _ ->
                    if (transaction.readAuthEvent(scope, command.requestId) != null) false else {
                        val target = transaction.readProtectedAccount(scope, command.targetId)
                        target != null && target.state.revision == command.expectedRevision &&
                            target.credentialRevision == revision && if (command.action == LocalAdministrationAction.ACTIVATE_ACCOUNT) {
                                val expected = checkNotNull(command.activationRevisions)
                                !target.state.enabled && target.credential == null && target.state.actorId != principal.actorId &&
                                    transaction.readPolicy(scope, command.targetId)?.revision == expected.policy &&
                                    transaction.readAuthConfiguration(scope, command.targetId)?.revision == expected.configuration
                            } else target.state.enabled
                    }
                }
                // A prior password-bearing original is lookup-only; another secret is never assumed equal.
                if (!mayStart) return@bounded LocalCredentialAdministrationResult.Denied
                when (val hashed = passwords.create(owned, CredentialBinding(scope, command.targetId, revision.next()), pepper)) {
                    is PasswordHashingResult.Created -> hashed.record
                    is PasswordHashingResult.Unavailable -> return@bounded when (hashed.reason) {
                        PasswordFailure.HASHER_BUSY -> LocalCredentialAdministrationResult.Busy
                        PasswordFailure.INVALID_INPUT -> LocalCredentialAdministrationResult.Denied
                        else -> LocalCredentialAdministrationResult.Unavailable
                    }
                }
            }
            applyAccount(principal, peer, command, requirement, digest, password)
        } finally { owned?.fill('\u0000') }
    }

    private suspend fun applyAccount(principal: LocalEmployeePrincipal, peer: RoomLocalAccessPeerBinding,
        command: LocalAdministrationCommand, requirement: AuthorizationRequirement, digest: PayloadDigest,
        password: PasswordHashRecord?): LocalCredentialAdministrationResult = localSecurityLane(store.database) {
        var mutation: RoomLocalAccountAdministrationFrame? = null
        var recovered: RoomLocalAdministrationObservationFrame? = null
        val applied = store.transaction { transaction ->
            val permitted = authorize(transaction, principal, peer, requirement)
            val prior = transaction.readAuthEvent(scope, command.requestId)
            if (prior != null) {
                if (command.action.requiresNewPassword || !matches(prior, principal, command, digest))
                    return@transaction false
                // Recover the immutable original before testing today's target revision/state.
                val frame = readFrame(principal, peer, permitted)
                recovered = frame
                transaction.attachAdministrationObservationFrame(frame)
                return@transaction true
            }
            val before = transaction.readProtectedAccount(scope, command.targetId) ?: return@transaction false
            val state = before.state
            if (state.revision != command.expectedRevision) return@transaction false
            val archived = if (command.action == LocalAdministrationAction.REVOKE_SESSION &&
                state.sessions.none { it.sessionId == command.sessionId }) {
                transaction.readSessionArchive(scope, checkNotNull(command.sessionId))?.takeIf {
                    it.session.actorId == command.targetId && it.session.scope == scope
                } ?: return@transaction false
            } else null
            val reactivation = command.reactivationRevisions
            val activation = command.activationRevisions
            val targetPolicy = if (reactivation != null || activation != null)
                transaction.readPolicy(scope, command.targetId) ?: return@transaction false else null
            val targetConfiguration = if (reactivation != null || activation != null)
                transaction.readAuthConfiguration(scope, command.targetId) ?: return@transaction false else null
            if (activation != null && (command.targetId == principal.actorId || state.enabled || before.credential != null ||
                before.credentialRevision != command.expectedCredentialRevision || targetPolicy?.revision != activation.policy ||
                targetConfiguration?.revision != activation.configuration)) return@transaction false
            if (reactivation != null && (command.targetId == principal.actorId || before.credential == null ||
                before.credentialRevision != reactivation.credential || targetPolicy?.revision != reactivation.policy ||
                targetConfiguration?.revision != reactivation.configuration)) return@transaction false
            val next = when (command.action) {
                LocalAdministrationAction.ACTIVATE_ACCOUNT -> LocalSessions.reactivate(state)
                LocalAdministrationAction.REACTIVATE_ACCOUNT -> LocalSessions.reactivate(state)
                LocalAdministrationAction.DISABLE_ACCOUNT -> LocalSessions.disable(state)
                LocalAdministrationAction.REVOKE_SESSION -> LocalSessions.revoke(state, checkNotNull(command.sessionId))
                LocalAdministrationAction.RESET_CREDENTIAL -> {
                    if (password == null || !state.enabled || before.credentialRevision != command.expectedCredentialRevision ||
                        before.credentialRevision.value == Long.MAX_VALUE) return@transaction false
                    LocalSessions.revokeAll(state)
                }
                LocalAdministrationAction.REVOKE_DEVICE -> error("Device actions have their own existing protected finalizer")
            } as? SessionTransition.Applied ?: return@transaction false
            if (password != null && !next.state.enabled) return@transaction false
            val after = ProtectedLocalAccount(next.state,
                if (password == null) before.credentialRevision else before.credentialRevision.next(), password ?: before.credential)
            val retainedSessionId = command.sessionId?.takeIf {
                command.action == LocalAdministrationAction.REVOKE_SESSION && it != principal.sessionId && archived == null
            }
            // These actual immutable target records belong to this SAME writer. Missing or
            // unsupported issuance history never receives a manufactured restart capsule.
            val retainedAccess = retainedSessionId?.let { transaction.readIssuance(scope, it) }
            val retainedRefresh = retainedSessionId?.let { transaction.readRefresh(scope, it) }
            val event = LocalAuthEvent(scope, command.requestId, eventKind(command.action), command.targetId,
                principal.actorId, command.sessionId, next.state.revision, source.observeCurrent(currentCoroutineContext()).moment, digest)
            val frame = RoomLocalAccountAdministrationFrame(principal, permitted.facts, permitted.authorization, source, peer,
                currentCoroutineContext(), before, after, event, archived,
                originalCommand = command, originalObservation = permitted.observation,
                retainedSessionAccess = retainedAccess, retainedSessionRefresh = retainedRefresh,
                resolutionPolicy = targetPolicy, resolutionConfiguration = targetConfiguration)
            mutation = frame
            transaction.attachAccountAdministrationFrame(frame)
            if (activation != null) {
                if (transaction.activateCredential(LocalAccountReactivation(scope, command.targetId, command.expectedRevision,
                    checkNotNull(command.expectedCredentialRevision), activation.policy, activation.configuration),
                    checkNotNull(password)) == null) transaction.refuse()
            } else if (reactivation != null) {
                if (transaction.reactivateAccount(LocalAccountReactivation(scope, command.targetId, command.expectedRevision,
                    reactivation.credential, reactivation.policy, reactivation.configuration)) == null) transaction.refuse()
            } else if (password == null) {
                if (!transaction.replaceAccount(state.revision, next.state)) transaction.refuse()
            } else if (transaction.resetCredential(scope, command.targetId, state.revision, before.credentialRevision, password) == null)
                transaction.refuse()
            transaction.insertAuthEvent(event)
            true
        }
        if (applied && (mutation?.finishAfterCommit(currentCoroutineContext()) ?: recovered?.finishAfterCommit(currentCoroutineContext())) != true)
            protectedLocalUnavailable()
        if (applied) LocalCredentialAdministrationResult.Applied(command.requestId) else LocalCredentialAdministrationResult.Denied
    }

    private suspend fun directory(transaction: RoomLocalSecurityTransaction, principal: LocalEmployeePrincipal,
        facts: RoomLocalAuthorizationFacts, query: LocalAdministrationQuery): LocalAdministrationReadResult {
        val cursor = query.cursor
        val targetId = query.targetId
        val accounts = ArrayList<LocalAccountSummary>(query.pageSize + 1)
        val devices = ArrayList<LocalDeviceAdmission>(query.pageSize + 1)
        var detail: LocalAccountAdministrationDetail? = null
        var cursorSeen = cursor == null
        // Directory paging uses a full bounded census, not a prefix claimed complete. Exact
        // target reads instead bind only that record. Only the requested bounded view survives.
        val cut = StringBuilder()
        fun field(value: String) { cut.append(value.length).append(':').append(value) }
        listOf("ukkera.local-administration-snapshot/v1", scope.storageKey(), query.directory.name,
            query.targetId?.value.orEmpty(), principal.actorId.value, principal.deviceId.value, principal.sessionId.value,
            principal.sessionGeneration.value.toString(), facts.account.revision.value.toString(), facts.policy.revision.value.toString(),
            facts.admission.revision.value.toString(), facts.issuance.issuerContextId.value).forEach(::field)
        if (query.directory == LocalAdministrationDirectory.ACCOUNTS) {
            suspend fun visit(account: ProtectedLocalAccount) {
                val state = account.state
                val policy = transaction.readPolicy(scope, state.actorId) ?: transaction.refuse()
                val config = transaction.readAuthConfiguration(scope, state.actorId) ?: transaction.refuse()
                check(policy.scope == scope && policy.actorId == state.actorId && config.scope == scope && config.actorId == state.actorId)
                listOf(state.actorId.value, state.revision.value.toString(), account.credentialRevision.value.toString(),
                    policy.revision.value.toString(), config.revision.value.toString()).forEach(::field)
                if (cursor?.afterId == state.actorId) cursorSeen = true
                if ((query.targetId == null && (cursor == null || state.actorId.value > cursor.afterId.value)) ||
                    query.targetId == state.actorId) {
                    if (accounts.size <= query.pageSize) {
                        val summary = LocalAccountSummary(state.actorId, config.login, state.enabled, account.credential != null,
                            state.revision, account.credentialRevision, policy.revision, config.revision,
                            state.sessions.count { it.status == SessionStatus.ACTIVE })
                        accounts += summary
                        if (query.targetId == state.actorId) detail = LocalAccountAdministrationDetail(summary, policy, config.tokens, state.sessions)
                    }
                }
            }
            // Exact-target reads have no continuation and need only that target's coherent
            // cut. Do not decrypt every other employee's policy just to inspect one account.
            if (targetId == null) transaction.forEachAccount(scope) { visit(it) }
            else transaction.readProtectedAccount(scope, targetId)?.let { visit(it) }
        } else {
            val admissions = if (targetId == null) transaction.readAdmissions(scope)
                else listOfNotNull(transaction.readAdmission(scope, targetId))
            for (admission in admissions.sortedBy { it.deviceId.value }) {
                field(admission.deviceId.value); field(admission.revision.value.toString())
                if (cursor?.afterId == admission.deviceId) cursorSeen = true
                if ((query.targetId == null && (cursor == null || admission.deviceId.value > cursor.afterId.value)) ||
                    query.targetId == admission.deviceId) {
                    if (devices.size <= query.pageSize) devices += admission
                }
            }
        }
        val bytes = cut.toString().encodeToByteArray(throwOnInvalidSequence = true)
        val snapshot = try { sha256(bytes) } finally { bytes.fill(0); cut.clear() }
        if (!cursorSeen || (cursor != null && cursor.snapshot != snapshot)) return LocalAdministrationReadResult.StaleSnapshot
        if (query.targetId != null && accounts.isEmpty() && devices.isEmpty()) return LocalAdministrationReadResult.Denied
        val next = when {
            accounts.size > query.pageSize -> LocalAdministrationCursor(accounts[query.pageSize - 1].actorId, snapshot)
            devices.size > query.pageSize -> LocalAdministrationCursor(devices[query.pageSize - 1].deviceId, snapshot)
            else -> null
        }
        return LocalAdministrationReadResult.Available(LocalAdministrationView(scope, query.directory, snapshot,
            accounts.take(query.pageSize), devices.take(query.pageSize), detail, next))
    }

    private suspend fun <T> authorizedRead(principal: LocalEmployeePrincipal, peer: RoomLocalAccessPeerBinding,
        requirement: AuthorizationRequirement, read: suspend (RoomLocalSecurityTransaction, Permitted) -> T): T = localSecurityLane(store.database) {
        var finalization: RoomLocalAdministrationObservationFrame? = null
        val value = store.transaction { transaction ->
            val permitted = authorize(transaction, principal, peer, requirement)
            val frame = readFrame(principal, peer, permitted)
            finalization = frame
            transaction.attachAdministrationObservationFrame(frame)
            read(transaction, permitted)
        }
        if (finalization?.finishAfterCommit(currentCoroutineContext()) != true) protectedLocalUnavailable()
        value
    }

    private suspend fun readFrame(principal: LocalEmployeePrincipal, peer: RoomLocalAccessPeerBinding, permitted: Permitted) =
        RoomLocalAdministrationObservationFrame(principal, permitted.facts, permitted.authorization, source, peer, currentCoroutineContext())

    private suspend fun authenticate(operator: LocalGatewayCredentials): Pair<LocalEmployeePrincipal, RoomLocalAccessPeerBinding> {
        val principal = principals.authenticateLocal(operator) ?: currentLocalAuthorizationDenied()
        val peer = peers.capture(operator.connectionId)?.takeIf {
            it.scope == scope && it.connectionId == operator.connectionId && it.deviceId == principal.deviceId && it.isCurrent()
        } ?: currentLocalAuthorizationDenied()
        return principal to peer
    }

    private suspend fun authorize(transaction: RoomLocalSecurityTransaction, principal: LocalEmployeePrincipal,
        peer: RoomLocalAccessPeerBinding, requirement: AuthorizationRequirement): Permitted {
        if (principal.scope != scope || !peer.isCurrent()) currentLocalAuthorizationDenied()
        val facts = transaction.readAuthorizationFacts(principal) ?: currentLocalAuthorizationDenied()
        val now = source.observeCurrent(currentCoroutineContext())
        if (!peer.matchesAdmission(facts.admission) || !LocalAccessEligibility.matchesCurrentFacts(principal, facts.issuance,
                facts.account, facts.credentialRevision, facts.credentialPresent, facts.admission, now.issuerContextId, now.moment))
            currentLocalAuthorizationDenied()
        val snapshot = AuthorizationSnapshot(principal.actorId, principal.kind, principal.scope, principal.sessionId,
            principal.sessionGeneration, principal.deviceId, facts.admission.revision, facts.policy.revision, facts.account.enabled,
            true, facts.admission.admitted, facts.issuance.authenticationPurpose, facts.policy.grants)
        val allowed = AuthorizationEvaluator.evaluate(principal, snapshot, requirement) as? AuthorizationDecision.Allowed
            ?: currentLocalAuthorizationDenied()
        return Permitted(facts, allowed.authorization, now, snapshot)
    }

    private fun requirement(command: LocalAdministrationCommand, digest: PayloadDigest): AuthorizationRequirement {
        val device = command.action == LocalAdministrationAction.REVOKE_DEVICE
        return AuthorizationRequirement(Permission(if (device) SecurityAction.DEVICE_ADMINISTRATION else SecurityAction.LOCAL_ACCOUNT_ADMINISTRATION,
            PermissionVerb.ADMINISTER), scope, ActionBinding(command.targetId, command.expectedRevision, digest,
            OperationId(command.requestId.value), if (device) "local-device-revocation" else "local-credential-administration"))
    }

    private fun matches(event: LocalAuthEvent, principal: LocalEmployeePrincipal, command: LocalAdministrationCommand, digest: PayloadDigest): Boolean =
        event.scope == scope && event.eventId == command.requestId && event.targetId == command.targetId &&
            event.operatorId == principal.actorId && event.kind == eventKind(command.action) && event.contentDigest == digest &&
            (if (command.action == LocalAdministrationAction.REVOKE_DEVICE) event.accountRevision == null && event.sessionId != null
            else event.accountRevision != null && event.sessionId == command.sessionId)

    private fun receipt(event: LocalAuthEvent, command: LocalAdministrationCommand, digest: PayloadDigest): LocalAdministrationReceipt =
        LocalAdministrationReceipt(event.eventId, command.action, event.targetId, checkNotNull(event.operatorId), event.accountRevision, digest)

    private fun matchesCancellation(event: LocalAuthEvent, principal: LocalEmployeePrincipal,
        command: LocalAdministrationCommand, digest: PayloadDigest): Boolean =
        event.scope == scope && event.eventId == command.requestId && event.targetId == command.targetId &&
            event.operatorId == principal.actorId && event.kind == LocalAuthEventKind.ADMINISTRATION_CANCELLED &&
            event.accountRevision == null && event.sessionId == command.sessionId && event.contentDigest == digest

    private fun eventKind(action: LocalAdministrationAction): LocalAuthEventKind = when (action) {
        LocalAdministrationAction.ACTIVATE_ACCOUNT -> LocalAuthEventKind.ACCOUNT_ACTIVATED
        LocalAdministrationAction.REACTIVATE_ACCOUNT -> LocalAuthEventKind.ACCOUNT_REACTIVATED
        LocalAdministrationAction.DISABLE_ACCOUNT -> LocalAuthEventKind.ACCOUNT_DISABLED
        LocalAdministrationAction.REVOKE_SESSION -> LocalAuthEventKind.SESSION_REVOKED
        LocalAdministrationAction.RESET_CREDENTIAL -> LocalAuthEventKind.CREDENTIAL_RESET
        LocalAdministrationAction.REVOKE_DEVICE -> LocalAuthEventKind.DEVICE_REVOKED
    }

    private suspend fun <T> bounded(busy: T, denied: T, unavailable: T, uncertain: T, block: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        if (!slots.tryAcquire()) return busy
        return try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: RoomCurrentLocalAuthorizationDenied) { denied }
        catch (_: RoomLocalStorageUncertain) { currentCoroutineContext().ensureActive(); uncertain }
        catch (_: RoomLocalAccessVerificationStorageUncertain) { currentCoroutineContext().ensureActive(); uncertain }
        catch (_: RoomLocalAccessVerificationBusy) { busy }
        catch (_: Exception) { currentCoroutineContext().ensureActive(); unavailable }
        finally { slots.release() }
    }

    private class Permitted(val facts: RoomLocalAuthorizationFacts, val authorization: AuthorizedAction,
        val observation: RoomLocalAuthorizationObservation, val snapshot: AuthorizationSnapshot)
    private fun digestText(vararg fields: String): PayloadDigest {
        val bytes = fields.joinToString("") { "${it.length}:$it" }.encodeToByteArray(throwOnInvalidSequence = true)
        return try { sha256(bytes) } finally { bytes.fill(0) }
    }
}
