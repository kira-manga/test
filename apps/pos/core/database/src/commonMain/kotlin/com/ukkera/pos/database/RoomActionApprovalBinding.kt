package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ensureActive

/** Credential material never escapes the protected transaction through this projection. */
internal class RoomApprovalApproverFacts(
    val account: LocalAccountSessionState, val credentialRevision: Revision, val credentialPresent: Boolean,
    val policy: ActivatedLocalPolicy, val admission: LocalDeviceAdmission, fingerprints: List<PayloadDigest>,
) {
    private val fingerprints = fingerprints.toList()
    init { require(this.fingerprints.size == 3) }
    fun sameDependencies(other: RoomApprovalApproverFacts): Boolean = fingerprints == other.fingerprints
    internal fun runtimeApprovalDependencies(): RuntimeApprovalApproverDependenciesV1 =
        RuntimeApprovalApproverDependenciesV1(fingerprints[0], fingerprints[1], fingerprints[2])
}

/** One actual writer view owns preparation, optional CAS, final revalidation and closure. */
internal class RoomActionApprovalBinding(
    private val owner: RoomTransactionApprovalSource,
    private val transaction: RoomLocalSecurityTransaction,
    private val source: RoomCurrentLocalAuthorizationSource,
    private val entropy: LocalAuthenticationEntropy,
    private val principal: LocalEmployeePrincipal,
    private val context: CoroutineContext,
    private val view: CommandReadView,
    private val approvalId: DomainId,
    private val requirement: AuthorizationRequirement,
) {
    private enum class Phase { NEW, DENIED, READY, CONSUMED, PREPARED, FINISHED, CLOSED }
    private var phase = Phase.NEW
    private var inProgress = false
    private var failed = false
    private var denied = false
    private var cancellation: CancellationException? = null
    private var issued: ProtectedLocalActionApproval? = null
    private var consumed: ProtectedLocalActionApproval? = null
    private var original: Dependencies? = null
    private var finalFacts: Dependencies? = null
    private var permit: TransactionApprovalPermit? = null
    private var businessAttribution: CommandBusinessAttribution? = null
    private var didConsume = false
    private var historyCapture: RuntimeActionApprovalUseCapture? = null

    fun belongsTo(source: RoomTransactionApprovalSource, selected: LocalEmployeePrincipal): Boolean = owner === source && principal === selected

    suspend fun prepare(): TransactionApprovalDecision = guarded {
        requirePhase(Phase.NEW)
        source.current(principal, view) ?: return@guarded refusePreparation()
        val approval = readVerifiedActionApproval(transaction, principal.scope, approvalId) ?: return@guarded refusePreparation()
        if (readVerifiedActionApprovalRetirement(transaction, approval) != null) return@guarded refusePreparation()
        if (approval.record.consumedByOperationId != null || approval.confirmationDigest != LocalActionApprovalCodec.confirmationDigest(
                approval.record.requestorId, approval.record.requestorPolicyRevision, approval.record.requirement)) return@guarded refusePreparation()
        val facts = dependencies(approval) ?: return@guarded refusePreparation()
        val observed = source.observeCurrent(context)
        val decision = evaluate(approval, facts, observed) ?: return@guarded refusePreparation()
        // This is the unconsumed before cut only. After CAS the same permit retains this
        // immutable DATA; current authority/row/dependencies remain independently rechecked.
        businessAttribution = qualifyActionApprovalBusinessAttribution(transaction, approval) ?: return@guarded refusePreparation()
        issued = approval
        consumed = approval.consumed(decision.record)
        original = facts
        val selected = TransactionApprovalPermit(this, decision.authorization)
        permit = selected
        phase = Phase.READY
        historyCapture = RuntimeActionApprovalUseCapture(approval,
            RuntimeApprovalApproverSnapshotV1.capture(principal.scope, approval.approverDeviceId, facts.approver),
            observed.publicValue()).also(transaction::beginHistoryApprovalUse)
        TransactionApprovalDecision.Ready(selected)
    }

    suspend fun consume(selected: TransactionApprovalPermit): Boolean = guarded {
        requirePhase(Phase.READY)
        if (selected !== permit || selected.owner !== this || !selected.businessAttributionReleased) unavailable()
        val before = issued ?: unavailable()
        val latest = dependencies(before) ?: deny()
        if (!sameDependencies(original ?: unavailable(), latest) ||
            evaluate(before, latest, source.observeCurrent(context)) == null) deny()
        val next = consumed ?: unavailable()
        // Even a caller which catches/ignores this refusal must not finalize an earlier write.
        if (!transaction.consumeActionApproval(before, next)) deny()
        didConsume = true
        phase = Phase.CONSUMED
        val bytes = LocalActionApprovalCodec.encode(next)
        val digest = try { sha256(bytes) } finally { bytes.fill(0) }
        val observed = source.observeCurrent(context)
        val event = LocalAuthEvent(principal.scope, freshAuthenticationId(entropy),
            LocalAuthEventKind.ACTION_APPROVAL_CONSUMED, requirement.binding!!.documentId,
            principal.actorId, principal.sessionId, latest.requestor.account.revision,
            observed.moment, digest)
        transaction.insertAuthEvent(event)
        checkNotNull(historyCapture).also { it.consumed = next; it.event = event; it.consumedAt = observed.publicValue() }
        true
    }

    internal fun captureRuntimeApprovalUse(applied: Boolean): RuntimeActionApprovalUseCapture {
        requirePhase(if (applied) Phase.CONSUMED else Phase.READY)
        if (applied != didConsume) unavailable()
        return historyCapture ?: unavailable()
    }

    internal fun businessAttributionFor(selected: TransactionApprovalPermit, bound: AuthorizationRequirement,
        support: ActionApprovalBusinessAttributionSupport): CommandBusinessAttribution? {
        requirePhase(Phase.READY)
        if (selected !== permit || selected.owner !== this || bound != requirement ||
            !selected.authorization.isBoundTo(bound, principal.actorId)) unavailable()
        if (checkNotNull(issued).hasReplacementOrigin &&
            support != ActionApprovalBusinessAttributionSupport.PRESERVE_ORIGINAL_REQUESTER) return null
        return businessAttribution ?: unavailable()
    }

    suspend fun prepareNormalReturn(): Unit = guarded {
        requirePhase(Phase.DENIED, Phase.READY, Phase.CONSUMED)
        if (phase != Phase.DENIED) {
            val expected = (if (didConsume) consumed else issued) ?: unavailable()
            val latest = dependencies(expected) ?: deny()
            if (!sameDependencies(original ?: unavailable(), latest)) deny()
            finalFacts = latest
        }
        transaction.ensureAuthorizationHealthy()
        phase = Phase.PREPARED
    }

    /** Pure checks against the kernel's SAME last current-environment sample. No provider/SQL. */
    fun finishAt(observed: RoomLocalAuthorizationObservation) {
        try {
            begin()
            requirePhase(Phase.PREPARED)
            finalFacts?.let { if (evaluate(issued ?: unavailable(), it, observed) == null) deny() }
            phase = Phase.FINISHED
        } catch (cancelled: CancellationException) { cancellation = cancellation ?: cancelled; throw cancelled }
        catch (_: RoomCurrentLocalAuthorizationDenied) { deny() }
        catch (_: Exception) { unavailable() }
        finally { inProgress = false }
    }

    suspend fun close() {
        phase = Phase.CLOSED
        issued = null; consumed = null; original = null; finalFacts = null; permit = null
        businessAttribution = null
        historyCapture = null
        transaction.close()
    }

    private suspend fun dependencies(expected: ProtectedLocalActionApproval): Dependencies? {
        val current = readVerifiedActionApproval(transaction, principal.scope, approvalId) ?: return null
        if (readVerifiedActionApprovalRetirement(transaction, current) != null) return null
        val a = LocalActionApprovalCodec.encode(expected)
        try {
            val b = LocalActionApprovalCodec.encode(current)
            try { if (!a.contentEquals(b)) return null } finally { b.fill(0) }
        } finally { a.fill(0) }
        val requestor = transaction.readAuthorizationFacts(principal) ?: return null
        val approver = transaction.readApprovalApprover(principal.scope, expected.record.approverId, expected.approverDeviceId) ?: return null
        return Dependencies(requestor, approver)
    }

    private fun evaluate(value: ProtectedLocalActionApproval, facts: Dependencies,
        observed: RoomLocalAuthorizationObservation): ApprovalConsumeDecision.Consumed? {
        val requestor = facts.requestor
        val approver = facts.approver
        val record = value.record
        // A v1 approval has no account-incarnation witness. Preserve it as history, never
        // infer today's incarnation or let disable/reactivate resurrect its authority.
        if (!value.hasApproverIncarnation) return null
        val authority = value.issuingRequestorAuthority
        if (principal.authenticationPurpose != AuthenticationPurpose.INTERACTIVE || record.requirement != requirement ||
            principal.actorId != record.requestorId || principal.sessionId != authority.sessionId ||
            principal.sessionGeneration != authority.generation || principal.deviceId != authority.deviceId ||
            principal.admissionRevision != authority.admissionRevision || requestor.credentialRevision != authority.credentialRevision ||
            value.issuerContextId != observed.issuerContextId) return null
        if (!LocalAccessEligibility.matchesCurrentFacts(principal, requestor.issuance, requestor.account,
                requestor.credentialRevision, requestor.credentialPresent, requestor.admission, observed.issuerContextId, observed.moment)) return null
        if (!approver.account.enabled || !approver.credentialPresent || approver.account.revision.value == Long.MAX_VALUE ||
            approver.account.generation.value == Long.MAX_VALUE || approver.account.revision != value.approverAccountRevision ||
            approver.account.generation != value.approverGeneration || approver.credentialRevision != value.approverCredentialRevision ||
            !approver.admission.admitted || approver.admission.revision != value.approverAdmissionRevision) return null
        val approvalPolicy = approver.policy.approvalPolicy ?: return null
        if (approvalPolicy.validForMillis != record.validForMillis) return null
        val snapshot = requestor.snapshot(principal)
        val requested = AuthorizationEvaluator.evaluate(principal, snapshot,
            requirement.copy(permission = requirement.permission.copy(verb = PermissionVerb.REQUEST))) as? AuthorizationDecision.Allowed ?: return null
        return ActionApprovals.consume(record, requirement, requested.authorization,
            CurrentApproverState(principal.scope, approver.account.actorId, approver.account.enabled, approver.policy.revision),
            approvalPolicy.forActor(principal.scope, approver.account.actorId, approver.policy.revision), observed.moment)
            as? ApprovalConsumeDecision.Consumed
    }

    private fun sameDependencies(first: Dependencies, second: Dependencies): Boolean =
        first.requestor.sameDependencies(second.requestor) && first.approver.sameDependencies(second.approver)
    private class Dependencies(val requestor: RoomLocalAuthorizationFacts, val approver: RoomApprovalApproverFacts)
    private fun refusePreparation(): TransactionApprovalDecision { phase = Phase.DENIED; return TransactionApprovalDecision.Denied }
    private fun requirePhase(vararg expected: Phase) { context.ensureActive(); throwFailure(); if (phase !in expected) unavailable() }
    private fun begin() { context.ensureActive(); throwFailure(); if (inProgress || phase == Phase.CLOSED) unavailable(); inProgress = true }
    private fun throwFailure() { cancellation?.let { throw it }; if (denied) currentLocalAuthorizationDenied(); if (failed) protectedLocalUnavailable() }
    private fun deny(): Nothing { denied = true; throwFailure(); currentLocalAuthorizationDenied() }
    private fun unavailable(): Nothing { failed = true; throwFailure(); protectedLocalUnavailable() }
    private suspend inline fun <T> guarded(block: () -> T): T = try { begin(); block() }
    catch (cancelled: CancellationException) { cancellation = cancellation ?: cancelled; throw cancelled }
    catch (_: RoomCurrentLocalAuthorizationDenied) { deny() }
    catch (_: Exception) { unavailable() }
    finally { inProgress = false }
}

internal fun RoomLocalAuthorizationFacts.snapshot(principal: LocalEmployeePrincipal): AuthorizationSnapshot =
    AuthorizationSnapshot(principal.actorId, principal.kind, principal.scope, principal.sessionId, principal.sessionGeneration,
        principal.deviceId, admission.revision, policy.revision, account.enabled,
        account.sessions.any { it.sessionId == principal.sessionId && it.status == SessionStatus.ACTIVE },
        admission.admitted, issuance.authenticationPurpose, policy.grants)
