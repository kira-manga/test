package com.ukkera.pos.database

import com.ukkera.pos.security.*
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive

/** One protected read or exact no-effect cancellation audit; never an arbitrary write callback. */
internal class RoomLocalAdministrationObservationFrame(
    private val principal: LocalEmployeePrincipal,
    private val original: RoomLocalAuthorizationFacts,
    authorization: AuthorizedAction,
    private val source: RoomCurrentLocalAuthorizationSource,
    private val peer: RoomLocalAccessPeerBinding,
    private val context: CoroutineContext,
    private val cancellation: LocalAuthEvent? = null,
    originalCommand: LocalAdministrationCommand? = null,
    originalObservation: RoomLocalAuthorizationObservation? = null,
    originalCreation: LocalDelegatedEmployeeCreationV1? = null,
) {
    private enum class Phase { NEW, BODY, PREPARED, FINISHED, CLOSED }
    private var phase = Phase.NEW
    private var failure: Exception? = null
    private var owner: RoomLocalSecurityTransaction? = null
    private var finished = false
    private val cancellationDigest = cancellation?.let { digest(LocalAuthenticationCodec.encodeEvent(it)) }
    private val purpose = if (authorization.requirement.permission.action == SecurityAction.DEVICE_ADMINISTRATION)
        RuntimeWritePurposeV2.DEVICE_ADMINISTRATION else RuntimeWritePurposeV2.ACCOUNT_ADMINISTRATION
    private val cancellationHistory: RuntimeAdministrationCancellationCapture?
    private val creationCancellationHistory: RuntimeEmployeeCreationCancellationCapture?

    init {
        val requirement = authorization.requirement
        check(principal.authenticationPurpose == AuthenticationPurpose.INTERACTIVE &&
            principal.scope == peer.scope && principal.deviceId == peer.deviceId &&
            peer.matchesAdmission(original.admission) && authorization.actorId == principal.actorId &&
            authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE &&
            authorization.authenticationId == principal.sessionId && authorization.generation == principal.sessionGeneration &&
            authorization.policyRevision == original.policy.revision && authorization.approvalId == null &&
            requirement.scope == principal.scope && requirement.permission.action in setOf(
                SecurityAction.LOCAL_ACCOUNT_ADMINISTRATION, SecurityAction.DEVICE_ADMINISTRATION) &&
            requirement.permission.verb == PermissionVerb.ADMINISTER &&
            requirement.limitUse == LimitUse.NotApplicable && requirement.owner == OwnerConstraint.Unrestricted)
        cancellation?.let { event ->
            val binding = checkNotNull(requirement.binding)
            check(event.kind == (if (originalCreation == null) LocalAuthEventKind.ADMINISTRATION_CANCELLED
                else LocalAuthEventKind.ACCOUNT_CREATION_CANCELLED) && event.scope == principal.scope &&
                event.operatorId == principal.actorId && event.eventId.value == binding.operationId?.value &&
                event.targetId == binding.documentId && event.contentDigest == binding.payloadDigest && event.accountRevision == null)
        }
        // Only the explicit fresh cancellation carries the actual typed command/observation.
        // All generic reads and prior-result paths remain capture-free, not default cancels.
        check((cancellation != null) == (originalCommand != null || originalCreation != null) &&
            (cancellation != null) == (originalObservation != null) &&
            (originalCommand == null || originalCreation == null))
        cancellationHistory = cancellation?.takeIf { originalCommand != null }?.let {
            RuntimeAdministrationCancellationCapture.capture(principal, original, authorization,
                checkNotNull(originalCommand), checkNotNull(originalObservation), it)
        }
        creationCancellationHistory = cancellation?.takeIf { originalCreation != null }?.let {
            RuntimeEmployeeCreationCancellationCapture.capture(principal, original, authorization,
                checkNotNull(originalCreation), checkNotNull(originalObservation), it)
        }
    }

    suspend fun initialize(transaction: RoomLocalSecurityTransaction): Unit = guarded {
        requirePhase(Phase.NEW)
        owner = transaction
        transaction.requireAdministrationWriteCount(0)
        cancellation?.let {
            if (transaction.readAuthEvent(principal.scope, it.eventId) != null) transaction.refuse()
            transaction.historyPurpose(purpose, principal.actorId, it.eventId, it.contentDigest)
        }
        checkFacts(transaction)
        cancellationHistory?.let(transaction::beginHistoryAdministrationCancellation)
        creationCancellationHistory?.let(transaction::beginHistoryEmployeeCreationCancellation)
        phase = Phase.BODY
    }

    suspend fun prepareNormalReturn(transaction: RoomLocalSecurityTransaction): Unit = guarded {
        requirePhase(Phase.BODY)
        if (owner !== transaction) transaction.refuse()
        transaction.requireAdministrationWriteCount(if (cancellation == null) 0 else 1)
        cancellation?.let {
            val actual = transaction.readAuthEvent(principal.scope, it.eventId) ?: transaction.refuse()
            if (digest(LocalAuthenticationCodec.encodeEvent(actual)) != cancellationDigest) transaction.refuse()
        }
        checkFacts(transaction)
        transaction.ensureAuthorizationHealthy()
        cancellationHistory?.let(transaction::completeHistoryAdministrationCancellation)
        creationCancellationHistory?.let(transaction::completeHistoryEmployeeCreationCancellation)
        phase = Phase.PREPARED
    }

    fun finishNow(): Unit = guarded {
        requirePhase(Phase.PREPARED)
        if (!peer.isCurrent() || !eligible(source.observeCurrent(context))) currentLocalAuthorizationDenied()
        phase = Phase.FINISHED
        finished = true
    }

    fun finishAfterCommit(caller: CoroutineContext): Boolean {
        caller.ensureActive()
        failure?.let { throw it }
        if (!finished || phase != Phase.CLOSED || !peer.isCurrent()) return false
        val now = try { source.observeCurrent(caller) } catch (_: RoomCurrentLocalAuthorizationDenied) { return false }
        return eligible(now)
    }

    fun close() { owner = null; phase = Phase.CLOSED }

    private suspend fun checkFacts(transaction: RoomLocalSecurityTransaction) {
        val current = transaction.readAuthorizationFacts(principal) ?: transaction.refuse()
        if (!original.sameDependencies(current)) transaction.refuse()
    }
    private fun eligible(now: RoomLocalAuthorizationObservation): Boolean = LocalAccessEligibility.matchesCurrentFacts(
        principal, original.issuance, original.account, original.credentialRevision, original.credentialPresent,
        original.admission, now.issuerContextId, now.moment)
    private fun requirePhase(expected: Phase) { context.ensureActive(); failure?.let { throw it }; check(phase == expected) }
    private inline fun <T> guarded(block: () -> T): T = try { failure?.let { throw it }; block() }
    catch (caught: Exception) { if (failure == null) failure = caught; throw caught }
    private fun digest(bytes: ByteArray): PayloadDigest = try { sha256(bytes) } finally { bytes.fill(0) }
}
