package com.ukkera.pos.database

import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore

/**
 * Local human reauthentication only. Kernel preparation supplies the actual command requirement;
 * caller DTOs never supply grants, approver facts, a stock snapshot or recovery authority. KDF
 * work runs after the reservation's complete outer Room loan has returned. F35 clean ingress and
 * F36 process supervision remain mandatory platform composition responsibilities.
 */
internal class RoomLocalActionApprovalService(
    private val scope: BranchScope,
    private val store: RoomLocalSecurityStore,
    private val source: RoomCurrentLocalAuthorizationSource,
    private val peers: RoomLocalAccessPeerSource,
    private val principals: TrustedPrincipalFactory,
    private val passwords: LocalPasswordHasher,
    private val entropy: LocalAuthenticationEntropy,
    private val preparation: LocalApprovalCommandPreparation,
    maximumConcurrentAuthentication: Int,
) : LocalActionApprovalService {
    private val slots = Semaphore(maximumConcurrentAuthentication)
    init { check(source.belongsTo(store)); require(maximumConcurrentAuthentication in 1..64) }

    override suspend fun preview(requestor: LocalGatewayCredentials, request: MutationRequest,
        retiredApprovalId: DomainId?): LocalActionApprovalPreviewResult =
        bounded<LocalActionApprovalPreviewResult>(LocalActionApprovalPreviewResult.Unavailable,
            LocalActionApprovalPreviewResult.StorageUncertain, LocalActionApprovalPreviewResult.Busy,
            LocalActionApprovalPreviewResult.Denied) {
            if (request.actionApprovalId != null) return@bounded LocalActionApprovalPreviewResult.Denied
            val principal = principals.authenticateLocal(requestor) ?: return@bounded LocalActionApprovalPreviewResult.Denied
            val peer = capturePeer(requestor.connectionId) ?: return@bounded LocalActionApprovalPreviewResult.Denied
            writer {
                var frame: RoomLocalActionApprovalFrame? = null
                val result: LocalActionApprovalPreviewResult = store.transaction { transaction ->
                    val current = transaction.openActionApprovalFrame(principal, source, peer).also { frame = it }
                    val prepared = prepare(current, request, emptySet()) ?: return@transaction LocalActionApprovalPreviewResult.Denied
                    val allowed = current.requestAuthorization(prepared.requirement)
                        ?: return@transaction LocalActionApprovalPreviewResult.Denied
                    if (qualifyActionApprovalIssue(transaction, principal, request.command, prepared, allowed.policyRevision,
                            current.requestorAuthority, LocalActionApprovalCodec.issueRequestDigest(scope, principal.actorId, request),
                            retiredApprovalId) == null)
                        return@transaction LocalActionApprovalPreviewResult.Denied
                    LocalActionApprovalPreviewResult.Ready(LocalActionApprovalPreview(principal.actorId,
                        allowed.policyRevision, prepared.requirement,
                        LocalActionApprovalCodec.confirmationDigest(principal.actorId, allowed.policyRevision, prepared.requirement)))
                }
                if (frame?.finishAfterCommit(currentCoroutineContext()) == true) result else LocalActionApprovalPreviewResult.Unavailable
            }
        }

    override suspend fun issue(approvalId: DomainId, requestor: LocalGatewayCredentials, request: MutationRequest,
        confirmationDigest: PayloadDigest, approverLogin: LocalLoginIdentifier, approverPassword: CharArray,
        retiredApprovalId: DomainId?): LocalActionApprovalIssueResult =
        bounded<LocalActionApprovalIssueResult>(LocalActionApprovalIssueResult.Unavailable,
            LocalActionApprovalIssueResult.StorageUncertain, LocalActionApprovalIssueResult.Busy, approvalRefused()) {
            if (request.actionApprovalId != null || approverPassword.size !in 4..1024) return@bounded approvalRefused()
            val ownedPassword = approverPassword.copyOf()
            try {
                val principal = principals.authenticateLocal(requestor) ?: return@bounded approvalRefused()
                val peer = capturePeer(requestor.connectionId) ?: return@bounded approvalRefused()
                val submitted = LocalActionApprovalCodec.issueRequestDigest(scope, principal.actorId, request)
                val start = writer {
                    var frame: RoomLocalActionApprovalFrame? = null
                    val first = store.transaction { transaction ->
                        val current = transaction.openActionApprovalFrame(principal, source, peer).also { frame = it }
                        val retained = readVerifiedActionApproval(transaction, scope, approvalId)
                        if (retained != null) return@transaction ApprovalStart.Reply(replayed(current, retained, submitted, confirmationDigest))
                        // An unrelated retained authentication event with this ID is a collision,
                        // not an invitation to overwrite an audit or issue under another ID.
                        if (transaction.readAuthEvent(scope, approvalId) != null) return@transaction ApprovalStart.Reply(approvalRefused())
                        val configuration = transaction.resolveLogin(scope, approverLogin)
                            ?: return@transaction ApprovalStart.Reply(approvalRefused())
                        val prepared = prepare(current, request, approvalResources(configuration.actorId, approvalId))
                            ?: return@transaction ApprovalStart.Reply(approvalRefused())
                        val allowed = current.requestAuthorization(prepared.requirement)
                            ?: return@transaction ApprovalStart.Reply(approvalRefused())
                        val binding = qualifyActionApprovalIssue(transaction, principal, request.command, prepared, allowed.policyRevision,
                            current.requestorAuthority, submitted, retiredApprovalId)
                            ?: return@transaction ApprovalStart.Reply(approvalRefused())
                        if (LocalActionApprovalCodec.confirmationDigest(principal.actorId, allowed.policyRevision,
                                prepared.requirement) != confirmationDigest) return@transaction ApprovalStart.Reply(confirmationChanged())
                        val manager = transaction.readApprovalApprover(scope, configuration.actorId, peer.deviceId)
                            ?: return@transaction ApprovalStart.Reply(approvalRefused())
                        if (!manager.eligibleOn(peer) || manager.policy.approvalPolicy == null)
                            return@transaction ApprovalStart.Reply(approvalRefused())
                        current.expectApprover(manager)
                        ApprovalStart.Prepared(configuration, prepared, current.requestorFacts, manager, binding)
                    }
                    if (frame?.finishAfterCommit(currentCoroutineContext()) != true) return@writer ApprovalStart.Reply(LocalActionApprovalIssueResult.Unavailable)
                    if (first !is ApprovalStart.Prepared) return@writer first
                    val attemptId = freshAuthenticationId(entropy)
                    val throttle = RoomPersistentThrottleStore.capture(store, scope, first.configuration.actorId,
                        peer.deviceId, attemptId, first.configuration)
                        ?: return@writer ApprovalStart.Reply(approvalRefused())
                    when (val reserved = throttle.reserve(observe().moment)) {
                        is ThrottleReservation.Allowed -> ApprovalStart.Attempt(first, throttle, reserved.permit, attemptId)
                        is ThrottleReservation.Denied -> ApprovalStart.Reply(if (reserved.reason in setOf(
                                ThrottleDenial.DELAYED, ThrottleDenial.ATTEMPT_IN_PROGRESS)) {
                            LocalActionApprovalIssueResult.Denied(LocalActionApprovalDenial.THROTTLED)
                        } else LocalActionApprovalIssueResult.Unavailable)
                    }
                }
                if (start is ApprovalStart.Reply) return@bounded start.result
                val attempt = start as? ApprovalStart.Attempt ?: protectedLocalUnavailable()
                // Intentionally outside EVERY writer/transaction and its outer loan.
                val verified = attempt.throttle.verifyPassword(passwords, ownedPassword)
                if (verified !is PasswordVerificationResult.Match) {
                    val outcome = if (verified is PasswordVerificationResult.Mismatch) CredentialAttemptOutcome.MISMATCH
                        else CredentialAttemptOutcome.VERIFIER_UNAVAILABLE
                    return@bounded writer {
                        val completion = attempt.throttle.complete(attempt.permit, outcome, observe().moment)
                        if (completion !is ThrottleCompletion.Persisted || !peer.isCurrent()) return@writer LocalActionApprovalIssueResult.Unavailable
                        observe()
                        when {
                            outcome == CredentialAttemptOutcome.MISMATCH -> approvalRefused()
                            verified is PasswordVerificationResult.Unavailable && verified.reason == PasswordFailure.HASHER_BUSY -> LocalActionApprovalIssueResult.Busy
                            verified is PasswordVerificationResult.Unavailable && verified.reason == PasswordFailure.INVALID_INPUT -> approvalRefused()
                            else -> LocalActionApprovalIssueResult.Unavailable
                        }
                    }
                }
                writer {
                    var frame: RoomLocalActionApprovalFrame? = null
                    val completion = attempt.throttle.completeAndApply(attempt.permit, observe().moment) { transaction ->
                        val current = transaction.openActionApprovalFrame(principal, source, peer).also { frame = it }
                        transaction.historyApprovalAttempt()
                        current.expectAttempt(attempt.throttle)
                        val retained = readVerifiedActionApproval(transaction, scope, approvalId)
                        if (retained != null) return@completeAndApply replayed(current, retained, submitted, confirmationDigest)
                        if (transaction.readAuthEvent(scope, approvalId) != null) return@completeAndApply approvalRefused()
                        if (!attempt.before.requestor.sameDependencies(current.requestorFacts)) return@completeAndApply confirmationChanged()
                        val manager = transaction.readApprovalApprover(scope, attempt.before.configuration.actorId, peer.deviceId)
                            ?: return@completeAndApply approvalRefused()
                        if (!manager.eligibleOn(peer) || !attempt.before.approver.sameDependencies(manager)) return@completeAndApply approvalRefused()
                        current.expectApprover(manager)
                        val prepared = prepare(current, request, approvalResources(manager.account.actorId, approvalId))
                            ?: return@completeAndApply approvalRefused()
                        val requested = current.requestAuthorization(prepared.requirement) ?: return@completeAndApply approvalRefused()
                        val binding = qualifyActionApprovalIssue(transaction, principal, request.command, prepared, requested.policyRevision,
                            current.requestorAuthority, submitted, retiredApprovalId) ?: return@completeAndApply approvalRefused()
                        if (prepared.requirement != attempt.before.command.requirement ||
                            prepared.affectedResources != attempt.before.command.affectedResources ||
                            prepared.actionApprovalBusinessAttribution != attempt.before.command.actionApprovalBusinessAttribution ||
                            !binding.sameBinding(attempt.before.binding) ||
                            LocalActionApprovalCodec.confirmationDigest(principal.actorId, requested.policyRevision,
                                prepared.requirement) != confirmationDigest) return@completeAndApply confirmationChanged()
                        val policy = manager.policy.approvalPolicy ?: return@completeAndApply approvalRefused()
                        // Constructible only in this private actual-MATCH completion continuation.
                        // This identity is never inserted as a session or returned to the caller.
                        val approver = privilegedManager(manager, peer, attempt.attemptId)
                        val approving = AuthorizationEvaluator.evaluate(approver, manager.snapshot(approver),
                            prepared.requirement.forApprovalVerb(PermissionVerb.APPROVE)) as? AuthorizationDecision.Allowed
                            ?: return@completeAndApply approvalRefused()
                        val now = observe()
                        if (policy.validForMillis > Long.MAX_VALUE - now.moment.elapsedMillis) return@completeAndApply approvalRefused()
                        val issued = ActionApprovals.issue(approvalId, prepared.requirement, requested,
                            approving.authorization, policy.forActor(scope, manager.account.actorId, manager.policy.revision),
                            now.moment, policy.validForMillis) as? ApprovalIssueDecision.Issued
                            ?: return@completeAndApply approvalRefused()
                        val original = binding.originalRequestor
                        val value = ProtectedLocalActionApproval(issued.record, now.issuerContextId, original.sessionId,
                            original.generation, original.deviceId, original.credentialRevision,
                            original.admissionRevision, peer.deviceId, manager.credentialRevision,
                            manager.admission.revision, confirmationDigest, manager.account.revision,
                            manager.account.generation, submitted,
                            if (binding.replacementOrigin != null) current.requestorAuthority else null, binding.replacementOrigin)
                        val history = RuntimeActionApprovalIssuanceCapture(request.command, value,
                            RuntimeCommandAuthorizationSnapshotV1.capture(principal, current.requestorFacts, now),
                            RuntimeApprovalApproverSnapshotV1.capture(scope, peer.deviceId, manager))
                        transaction.beginHistoryApprovalIssuance(history)
                        transaction.insertActionApproval(value)
                        transaction.insertAuthEvent(LocalAuthEvent(scope, approvalId, LocalAuthEventKind.ACTION_APPROVAL_ISSUED,
                            approvalId, manager.account.actorId, principal.sessionId, manager.account.revision,
                            now.moment, approvalIssuanceDigest(value)))
                        transaction.completeHistoryApprovalIssuance(history)
                        current.expectIssued(value, binding, requested, approver, approving.authorization)
                        LocalActionApprovalIssueResult.Issued(value.receipt())
                    }
                    when (completion) {
                        is RoomThrottleApplication.Applied -> if (frame?.finishAfterCommit(currentCoroutineContext()) == true) completion.value else LocalActionApprovalIssueResult.Unavailable
                        RoomThrottleApplication.Denied -> LocalActionApprovalIssueResult.Unavailable
                    }
                }
            } finally { ownedPassword.fill('\u0000') }
        }

    override suspend fun lookup(approvalId: DomainId, requestor: LocalGatewayCredentials,
        confirmationDigest: PayloadDigest): LocalActionApprovalStatus = bounded<LocalActionApprovalStatus>(
        LocalActionApprovalStatus.Unavailable, LocalActionApprovalStatus.StorageUncertain,
        LocalActionApprovalStatus.Busy, LocalActionApprovalStatus.Denied) {
        val principal = principals.authenticateLocal(requestor) ?: return@bounded LocalActionApprovalStatus.Denied
        val peer = capturePeer(requestor.connectionId) ?: return@bounded LocalActionApprovalStatus.Denied
        writer {
            var frame: RoomLocalActionApprovalFrame? = null
            val result: LocalActionApprovalStatus = store.transaction { transaction ->
                val current = transaction.openActionApprovalFrame(principal, source, peer).also { frame = it }
                val value = readVerifiedActionApproval(transaction, scope, approvalId) ?: return@transaction LocalActionApprovalStatus.NotRecorded
                if (!mayRead(current, value, confirmationDigest)) return@transaction LocalActionApprovalStatus.Denied
                readVerifiedActionApprovalRetirement(transaction, value)?.let { retirement ->
                    return@transaction LocalActionApprovalStatus.Retired(value.receipt(), actionApprovalRetirementReceipt(value, retirement))
                }
                // Historical receipt even after expiry, consumption, policy replacement or a
                // fresh requestor login. Do NOT reprepare an already-posted business document.
                LocalActionApprovalStatus.Recorded(value.receipt())
            }
            if (frame?.finishAfterCommit(currentCoroutineContext()) == true) result else LocalActionApprovalStatus.Unavailable
        }
    }

    override suspend fun retire(approvalId: DomainId, requestor: LocalGatewayCredentials,
        request: MutationRequest, confirmationDigest: PayloadDigest): LocalActionApprovalRetireResult = bounded<LocalActionApprovalRetireResult>(
        LocalActionApprovalRetireResult.Unavailable, LocalActionApprovalRetireResult.StorageUncertain,
        LocalActionApprovalRetireResult.Busy, LocalActionApprovalRetireResult.Denied) {
        if (request.actionApprovalId != null) return@bounded LocalActionApprovalRetireResult.Denied
        val principal = principals.authenticateLocal(requestor) ?: return@bounded LocalActionApprovalRetireResult.Denied
        val peer = capturePeer(requestor.connectionId) ?: return@bounded LocalActionApprovalRetireResult.Denied
        val submitted = LocalActionApprovalCodec.issueRequestDigest(scope, principal.actorId, request)
        writer {
            var frame: RoomLocalActionApprovalFrame? = null
            val result: LocalActionApprovalRetireResult = store.transaction { transaction ->
                val current = transaction.openActionApprovalFrame(principal, source, peer).also { frame = it }
                // Neither observed approval absence nor an unqualified native tail permits retirement.
                transaction.requireActionApprovalRetirementHistory()
                val value = readVerifiedActionApproval(transaction, scope, approvalId)
                    ?: return@transaction LocalActionApprovalRetireResult.Denied
                if (!mayRead(current, value, confirmationDigest) || !value.hasApproverIncarnation ||
                    value.issueRequestDigest != submitted || value.record.requirement.binding?.operationId != request.operationId)
                    return@transaction LocalActionApprovalRetireResult.Denied
                readVerifiedActionApprovalRetirement(transaction, value)?.let { retained ->
                    return@transaction LocalActionApprovalRetireResult.Retired(value.receipt(), actionApprovalRetirementReceipt(value, retained))
                }
                if (value.record.consumedByOperationId != null) return@transaction LocalActionApprovalRetireResult.Consumed(value.receipt())
                val retirementId = actionApprovalRetirementId(scope, approvalId)
                // Actual kernel preparation proves BOTH retained business members absent at the
                // complete current native writer. Any terminal original, even Reject, wins.
                val prepared = prepare(current, request, setOf(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, approvalId),
                    RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, retirementId)))
                    ?: return@transaction LocalActionApprovalRetireResult.Denied
                if (prepared.requirement != value.record.requirement || current.requestAuthorization(prepared.requirement) == null)
                    return@transaction LocalActionApprovalRetireResult.Denied
                val observed = observe()
                val requester = RuntimeCommandAuthorizationSnapshotV1.capture(principal, current.requestorFacts, observed)
                val event = actionApprovalRetirementEvent(value, requester)
                val capture = RuntimeActionApprovalRetirementCapture(request.command, value, requester)
                transaction.beginHistoryApprovalRetirement(capture)
                transaction.insertAuthEvent(event)
                transaction.completeHistoryApprovalRetirement(capture)
                current.expectRetired(value, event)
                LocalActionApprovalRetireResult.Retired(value.receipt(), actionApprovalRetirementReceipt(value, event))
            }
            if (frame?.finishAfterCommit(currentCoroutineContext()) == true) result else LocalActionApprovalRetireResult.Unavailable
        }
    }

    private suspend fun prepare(frame: RoomLocalActionApprovalFrame, request: MutationRequest,
        extras: Set<RecoveryResourceKey>): PreparedLocalApprovalCommand? {
        val prepared = approvalViewOperation { preparation.prepare(frame.principal, request, frame.view, extras) } ?: return null
        if (!ordinaryApproval(prepared.requirement)) return null
        return prepared
    }

    private suspend fun replayed(frame: RoomLocalActionApprovalFrame, value: ProtectedLocalActionApproval,
        submitted: PayloadDigest, confirmed: PayloadDigest): LocalActionApprovalIssueResult {
        if (!mayRead(frame, value, confirmed)) return approvalRefused()
        if (!value.hasApproverIncarnation || value.issueRequestDigest != submitted) return confirmationChanged()
        // Historical same-ID issuance is never a way to make a retired grant usable again.
        if (readVerifiedActionApprovalRetirement(frame.transaction, value) != null)
            return approvalRefused()
        return LocalActionApprovalIssueResult.Issued(value.receipt())
    }

    private fun mayRead(frame: RoomLocalActionApprovalFrame, value: ProtectedLocalActionApproval, confirmed: PayloadDigest): Boolean =
        value.record.requestorId == frame.principal.actorId && value.confirmationDigest == confirmed &&
            ordinaryApproval(value.record.requirement) && frame.requestAuthorization(value.record.requirement) != null

    private fun approvalResources(approverId: DomainId, approvalId: DomainId): Set<RecoveryResourceKey> = setOf(
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, approverId),
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, approvalId))

    /** One private object-identity verifier; never an accepting production credential verifier. */
    private suspend fun privilegedManager(facts: RoomApprovalApproverFacts, peer: RoomLocalAccessPeerBinding,
        attemptId: DomainId): LocalEmployeePrincipal {
        val bytes = ByteArray(32)
        val marker = try { entropy.fill(bytes); OpaqueCredential(bytes) } finally { bytes.fill(0) }
        try {
            val local = LocalGatewayCredentials(peer.connectionId, marker)
            var unused = true
            val verifier = object : PrincipalVerifier by DenyAllPrincipalVerifier {
                override suspend fun verifyLocal(credentials: LocalGatewayCredentials): VerifiedLocalIdentity? {
                    currentCoroutineContext().ensureActive()
                    if (credentials !== local || !unused) return null
                    unused = false
                    return VerifiedLocalIdentity(facts.account.actorId, scope, peer.deviceId, attemptId,
                        facts.account.generation, facts.admission.revision, AuthenticationPurpose.PRIVILEGED_APPROVAL)
                }
            }
            return TrustedPrincipalFactory(verifier).authenticateLocal(local) ?: protectedLocalUnavailable()
        } finally { marker.destroy() }
    }

    private suspend fun capturePeer(id: DomainId): RoomLocalAccessPeerBinding? = peers.capture(id)?.takeIf {
        it.scope == scope && it.connectionId == id && it.isCurrent()
    }
    private suspend fun observe(): RoomLocalAuthorizationObservation = source.observeCurrent(currentCoroutineContext())
    private suspend fun <T> writer(block: suspend () -> T): T = localSecurityLane(store.database) { block() }
    private suspend fun <T> bounded(unavailable: T, uncertain: T, busy: T, denied: T, block: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        if (!slots.tryAcquire()) return busy
        return try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: RoomLocalStorageUncertain) { currentCoroutineContext().ensureActive(); uncertain }
        catch (_: RoomLocalAccessVerificationStorageUncertain) { currentCoroutineContext().ensureActive(); uncertain }
        catch (_: RoomLocalAccessVerificationBusy) { currentCoroutineContext().ensureActive(); busy }
        catch (_: RoomCurrentLocalAuthorizationDenied) { currentCoroutineContext().ensureActive(); denied }
        catch (_: Exception) { currentCoroutineContext().ensureActive(); unavailable }
        finally { slots.release() }
    }
}

private sealed interface ApprovalStart {
    class Reply(val result: LocalActionApprovalIssueResult) : ApprovalStart
    class Prepared(val configuration: LocalAuthConfiguration, val command: PreparedLocalApprovalCommand,
        val requestor: RoomLocalAuthorizationFacts, val approver: RoomApprovalApproverFacts,
        val binding: QualifiedActionApprovalIssueBinding) : ApprovalStart
    class Attempt(val before: Prepared, val throttle: RoomPersistentThrottleStore,
        val permit: ThrottlePermit, val attemptId: DomainId) : ApprovalStart
}

private fun approvalRefused(): LocalActionApprovalIssueResult = LocalActionApprovalIssueResult.Denied(LocalActionApprovalDenial.AUTHORIZATION_REFUSED)
private fun confirmationChanged(): LocalActionApprovalIssueResult = LocalActionApprovalIssueResult.Denied(LocalActionApprovalDenial.CONFIRMATION_CHANGED)
private fun AuthorizationRequirement.forApprovalVerb(verb: PermissionVerb): AuthorizationRequirement = copy(permission = permission.copy(verb = verb))
private fun ordinaryApproval(requirement: AuthorizationRequirement): Boolean = requirement.permission.verb == PermissionVerb.PERFORM &&
    requirement.permission.action !in setOf(SecurityAction.RECOVERY_COPY, SecurityAction.RECOVERY_ACTIVATION, SecurityAction.RECOVERY_RECONCILIATION)

private fun RoomApprovalApproverFacts.eligibleOn(peer: RoomLocalAccessPeerBinding): Boolean = account.enabled && credentialPresent &&
    account.revision.value < Long.MAX_VALUE && account.generation.value < Long.MAX_VALUE &&
    admission.admitted && peer.matchesAdmission(admission)

private fun RoomApprovalApproverFacts.snapshot(principal: LocalEmployeePrincipal): AuthorizationSnapshot =
    AuthorizationSnapshot(principal.actorId, principal.kind, principal.scope, principal.sessionId, principal.sessionGeneration,
        principal.deviceId, admission.revision, policy.revision, account.enabled, true, admission.admitted,
        AuthenticationPurpose.PRIVILEGED_APPROVAL, policy.grants)

private fun ProtectedLocalActionApproval.receipt(): LocalActionApprovalReceipt = LocalActionApprovalReceipt(record.approvalId,
    record.requestorId, record.approverId, record.requirement, confirmationDigest, record.issuedAt,
    MonotonicMoment(record.issuedAt.bootId, record.issuedAt.elapsedMillis + record.validForMillis), record.consumedByOperationId)

/** Authenticates canonical v2/v3 issuance provenance; historical v1 is never rewritten or consumed. */
internal suspend fun readVerifiedActionApproval(transaction: RoomLocalSecurityTransaction, scope: BranchScope,
    id: DomainId): ProtectedLocalActionApproval? {
    val value = transaction.readActionApproval(scope, id)
    val event = transaction.readAuthEvent(scope, id)
    if (value == null) {
        if (event?.kind == LocalAuthEventKind.ACTION_APPROVAL_ISSUED) transaction.refuse()
        return null
    }
    if (value.confirmationDigest != LocalActionApprovalCodec.confirmationDigest(value.record.requestorId,
            value.record.requestorPolicyRevision, value.record.requirement)) transaction.refuse()
    if (value.hasApproverIncarnation && (event == null || event.kind != LocalAuthEventKind.ACTION_APPROVAL_ISSUED ||
            event.targetId != id || event.operatorId != value.record.approverId || event.sessionId != value.issuingRequestorAuthority.sessionId ||
            event.accountRevision != value.approverAccountRevision || event.at != value.record.issuedAt ||
            event.contentDigest != approvalIssuanceDigest(value))) transaction.refuse()
    return value
}

private fun approvalIssuanceDigest(value: ProtectedLocalActionApproval): PayloadDigest {
    val issued = unconsumedActionApproval(value)
    val bytes = LocalActionApprovalCodec.encode(issued)
    return try { sha256(bytes) } finally { bytes.fill(0) }
}

/** Kernel-view failures retain the kernel's storage-uncertain distinction, never a business denial. */
private suspend inline fun <T> approvalViewOperation(block: () -> T): T = try { block() }
catch (cancelled: CancellationException) { throw cancelled }
catch (denied: RoomCurrentLocalAuthorizationDenied) { throw denied }
catch (_: Exception) { localStorageUncertain() }

/**
 * One concrete transaction-owned lifecycle. No arbitrary commit callback, credential material or
 * unverified approver snapshot escapes. The store calls its finalizer only on normal body return,
 * after paired throttle writes, audit/approval, all protected rereads and connection-health work.
 */
internal class RoomLocalActionApprovalFrame(
    val view: TransactionView,
    val principal: LocalEmployeePrincipal,
    private val source: RoomCurrentLocalAuthorizationSource,
    private val peer: RoomLocalAccessPeerBinding,
    private val context: CoroutineContext,
) {
    private var initialized = false
    private var prepared = false
    private var finished = false
    private var closed = false
    private var snapshot: AuthorizationSnapshot? = null
    private var requester: RoomLocalAuthorizationFacts? = null
    private var approver: RoomApprovalApproverFacts? = null
    private var attempt: RoomPersistentThrottleStore? = null
    private var issued: ProtectedLocalActionApproval? = null
    private var issueBinding: QualifiedActionApprovalIssueBinding? = null
    private var issueRequest: AuthorizedAction? = null
    private var issueApprover: LocalEmployeePrincipal? = null
    private var issueApproval: AuthorizedAction? = null
    private var retired: Pair<ProtectedLocalActionApproval, LocalAuthEvent>? = null
    internal lateinit var transaction: RoomLocalSecurityTransaction
        private set
    val requestorFacts: RoomLocalAuthorizationFacts get() = requester ?: protectedLocalUnavailable()
    val requestorAuthority: ActionApprovalRequestorAuthority get() = ActionApprovalRequestorAuthority(principal.sessionId,
        principal.sessionGeneration, principal.deviceId, requestorFacts.credentialRevision, requestorFacts.admission.revision)

    suspend fun initialize(transaction: RoomLocalSecurityTransaction) {
        context.ensureActive()
        if (initialized || closed || principal.scope != peer.scope || principal.deviceId != peer.deviceId ||
            principal.authenticationPurpose != AuthenticationPurpose.INTERACTIVE) currentLocalAuthorizationDenied()
        val current = transaction.readAuthorizationFacts(principal) ?: currentLocalAuthorizationDenied()
        if (!peer.matchesAdmission(current.admission) || !peer.isCurrent()) currentLocalAuthorizationDenied()
        val now = source.observeCurrent(context)
        if (!LocalAccessEligibility.matchesCurrentFacts(principal, current.issuance, current.account,
                current.credentialRevision, current.credentialPresent, current.admission, now.issuerContextId, now.moment))
            currentLocalAuthorizationDenied()
        // This service's protected reader already owns the same writer. Do not create a second
        // protected borrower merely to rebuild these facts or lose its typed SQL-error latch.
        snapshot = current.snapshot(principal)
        requester = current
        this.transaction = transaction
        initialized = true
    }

    fun requestAuthorization(requirement: AuthorizationRequirement): AuthorizedAction? {
        requireBody()
        if (!ordinaryApproval(requirement)) return null
        return (AuthorizationEvaluator.evaluate(principal, snapshot ?: protectedLocalUnavailable(),
            requirement.forApprovalVerb(PermissionVerb.REQUEST)) as? AuthorizationDecision.Allowed)?.authorization
    }
    fun expectApprover(value: RoomApprovalApproverFacts) {
        requireBody()
        if (approver != null || !value.eligibleOn(peer)) currentLocalAuthorizationDenied()
        approver = value
    }
    fun expectAttempt(value: RoomPersistentThrottleStore) { requireBody(); check(attempt == null); attempt = value }
    fun expectIssued(value: ProtectedLocalActionApproval, binding: QualifiedActionApprovalIssueBinding,
        requested: AuthorizedAction, manager: LocalEmployeePrincipal, approving: AuthorizedAction) {
        requireBody()
        val requirement = value.record.requirement
        check(issued == null && approver != null && issueBinding == null && value.issuingRequestorAuthority == requestorAuthority &&
            originalActionApprovalRequestor(value) == binding.originalRequestor && value.replacementOrigin == binding.replacementOrigin &&
            requested.isBoundTo(requirement.forApprovalVerb(PermissionVerb.REQUEST), principal.actorId) &&
            requested.authenticationId == principal.authenticationId && requested.generation == principal.generation &&
            manager.authenticationPurpose == AuthenticationPurpose.PRIVILEGED_APPROVAL &&
            approving.isBoundTo(requirement.forApprovalVerb(PermissionVerb.APPROVE), manager.actorId) &&
            manager.actorId == value.record.approverId && manager.deviceId == peer.deviceId)
        issued = value; issueBinding = binding; issueRequest = requested; issueApprover = manager; issueApproval = approving
    }
    fun expectRetired(value: ProtectedLocalActionApproval, event: LocalAuthEvent) {
        requireBody(); check(issued == null && retired == null && approver == null)
        requireActionApprovalRetirementEvent(value, event)
        retired = value to event
    }

    suspend fun prepareNormalReturn(transaction: RoomLocalSecurityTransaction) {
        requireBody()
        val latest = transaction.readAuthorizationFacts(principal) ?: currentLocalAuthorizationDenied()
        if (!requestorFacts.sameDependencies(latest)) currentLocalAuthorizationDenied()
        approver?.let { original ->
            val current = transaction.readApprovalApprover(principal.scope, original.account.actorId, peer.deviceId)
                ?: currentLocalAuthorizationDenied()
            if (!original.sameDependencies(current) || !current.eligibleOn(peer)) currentLocalAuthorizationDenied()
        }
        issued?.let { expected ->
            checkNotNull(issueBinding).requireUnchanged(transaction)
            val current = readVerifiedActionApproval(transaction, principal.scope, expected.record.approvalId) ?: transaction.refuse()
            val a = LocalActionApprovalCodec.encode(expected)
            try {
                val b = LocalActionApprovalCodec.encode(current)
                try { if (!a.contentEquals(b)) transaction.refuse() } finally { b.fill(0) }
            } finally { a.fill(0) }
        }
        retired?.let { (expected, event) ->
            val current = readVerifiedActionApproval(transaction, principal.scope, expected.record.approvalId) ?: transaction.refuse()
            if (runtimeApprovalDigest(current) != runtimeApprovalDigest(expected)) transaction.refuse()
            val tombstone = readVerifiedActionApprovalRetirement(transaction, current) ?: transaction.refuse()
            val a = LocalAuthenticationCodec.encodeEvent(event)
            try {
                val b = LocalAuthenticationCodec.encodeEvent(tombstone)
                try { if (!a.contentEquals(b)) transaction.refuse() } finally { b.fill(0) }
            } finally { a.fill(0) }
            if (requestAuthorization(expected.record.requirement) == null) currentLocalAuthorizationDenied()
        }
        transaction.ensureAuthorizationHealthy()
        approvalViewOperation { view.prepareNormalReturn() }
        prepared = true
    }

    fun finishNow() {
        context.ensureActive()
        check(initialized && prepared && !finished && !closed)
        try { view.finishNow() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (denied: RoomCurrentLocalAuthorizationDenied) { throw denied }
        catch (_: Exception) { localStorageUncertain() }
        // Exact peer callback precedes the last synchronous source observation. After that
        // observation only captured eligibility/timeout comparisons remain, then Room COMMIT.
        if (!peer.isCurrent()) currentLocalAuthorizationDenied()
        val now = source.observeCurrent(context)
        if (!eligible(now)) currentLocalAuthorizationDenied()
        finished = true
    }

    /** Called only after the real inner COMMIT, still under the sole outer serialized loan. */
    fun finishAfterCommit(callerContext: CoroutineContext): Boolean {
        callerContext.ensureActive()
        if (!finished || !closed || !peer.isCurrent()) return false
        val now = try { source.observeCurrent(callerContext) }
        catch (denied: RoomCurrentLocalAuthorizationDenied) { return false }
        return eligible(now)
    }

    private fun eligible(now: RoomLocalAuthorizationObservation): Boolean {
        val facts = requestorFacts
        if (!LocalAccessEligibility.matchesCurrentFacts(principal, facts.issuance, facts.account,
                facts.credentialRevision, facts.credentialPresent, facts.admission, now.issuerContextId, now.moment)) return false
        if (attempt?.attemptValidAt(now.moment) == false) return false
        val value = issued ?: return true
        val requirement = value.record.requirement
        val requested = AuthorizationEvaluator.evaluate(principal, snapshot ?: protectedLocalUnavailable(),
            requirement.forApprovalVerb(PermissionVerb.REQUEST)) as? AuthorizationDecision.Allowed ?: return false
        val manager = issueApprover ?: return false
        val approving = AuthorizationEvaluator.evaluate(manager, (approver ?: return false).snapshot(manager),
            requirement.forApprovalVerb(PermissionVerb.APPROVE)) as? AuthorizationDecision.Allowed ?: return false
        if (requested.authorization.policyRevision != issueRequest?.policyRevision ||
            approving.authorization.policyRevision != issueApproval?.policyRevision ||
            !checkNotNull(issueRequest).isBoundTo(requirement.forApprovalVerb(PermissionVerb.REQUEST), principal.actorId) ||
            !checkNotNull(issueApproval).isBoundTo(requirement.forApprovalVerb(PermissionVerb.APPROVE), manager.actorId)) return false
        return value.issuerContextId == now.issuerContextId && value.record.issuedAt.bootId == now.moment.bootId &&
            now.moment.elapsedMillis >= value.record.issuedAt.elapsedMillis &&
            now.moment.elapsedMillis - value.record.issuedAt.elapsedMillis < value.record.validForMillis
    }
    private fun requireBody() { context.ensureActive(); check(initialized && !prepared && !finished && !closed) }
    suspend fun close() {
        closed = true; view.close()
        // The post-COMMIT publication check still uses these captured issue facts. No bearer
        // or manager session was ever retained; only this frame's actual MATCH proof exists.
    }
}
