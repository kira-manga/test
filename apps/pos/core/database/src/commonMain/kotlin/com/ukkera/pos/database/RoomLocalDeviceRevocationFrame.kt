package com.ukkera.pos.database

import com.ukkera.pos.security.*
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * One actual transaction's DEVICE_ADMINISTRATION proof. Intentional self-revocation changes
 * the operator's admission/session as an EFFECT; it does not erase the captured authorization.
 * Exact predicted post-effect fingerprints are checked before the last synchronous peer/time
 * sample. No reauthentication through the intentionally revoked admission is attempted.
 */
internal class RoomLocalDeviceRevocationFrame(
    private val principal: LocalEmployeePrincipal,
    private val original: RoomLocalAuthorizationFacts,
    private val authorization: AuthorizedAction,
    private val source: RoomCurrentLocalAuthorizationSource,
    private val peer: RoomLocalAccessPeerBinding,
    private val context: CoroutineContext,
    private val targetBefore: LocalDeviceAdmission?,
    private val event: LocalAuthEvent,
    private val originalCommand: LocalAdministrationCommand? = null,
    private val originalObservation: RoomLocalAuthorizationObservation? = null,
) {
    private enum class Phase { NEW, INITIALIZING, BODY, PREPARING, PREPARED, FINISHED, CLOSED }
    private var phase = Phase.NEW
    private var failure: Exception? = null
    private var owner: RoomLocalSecurityTransaction? = null
    private var expectedOperator: RoomLocalAuthorizationFacts? = null
    private var finished = false
    private val targetAfter: LocalDeviceAdmission?
    private val expectedEventDigest: PayloadDigest
    private var deviceHistory: RuntimeDeviceRevocationCapture? = null
    private var additionalDeviceHistory: RuntimeDeviceRevocationCaptureV2? = null

    init {
        val requirement = authorization.requirement
        val binding = checkNotNull(requirement.binding)
        check(principal.authenticationPurpose == AuthenticationPurpose.INTERACTIVE &&
            principal.scope == peer.scope && principal.deviceId == peer.deviceId &&
            authorization.actorId == principal.actorId && authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE &&
            authorization.authenticationId == principal.sessionId && authorization.generation == principal.sessionGeneration &&
            authorization.policyRevision == original.policy.revision && authorization.approvalId == null &&
            requirement.scope == principal.scope &&
            requirement.permission == Permission(SecurityAction.DEVICE_ADMINISTRATION, PermissionVerb.ADMINISTER) &&
            requirement.limitUse == LimitUse.NotApplicable && requirement.owner == OwnerConstraint.Unrestricted &&
            binding.reasonCode == "local-device-revocation")
        check(event.scope == principal.scope && event.kind == LocalAuthEventKind.DEVICE_REVOKED &&
            event.operatorId == principal.actorId && event.targetId == binding.documentId &&
            event.eventId.value == binding.operationId?.value && event.contentDigest == binding.payloadDigest &&
            event.accountRevision == null && event.sessionId != null)
        targetAfter = targetBefore?.let { before ->
            check(before.scope == principal.scope && before.deviceId == event.targetId && before.admitted &&
                before.revision == binding.revision && before.revision.value < Long.MAX_VALUE && event.sessionId == principal.sessionId)
            LocalDeviceAdmission(before.scope, before.deviceId, before.revision.next(), false,
                before.publicKeyIdentifier, before.publicFingerprint, before.capabilities)
        }
        check((originalCommand == null) == (originalObservation == null))
        if (targetBefore == null) check(originalCommand == null) // Exact prior receipt: no capture.
        originalCommand?.let { command ->
            check(targetBefore != null && command.action == LocalAdministrationAction.REVOKE_DEVICE && command.sessionId == null &&
                command.requestId == event.eventId && command.targetId == event.targetId &&
                command.expectedRevision == targetBefore.revision && checkNotNull(originalObservation).moment == event.at &&
                localAdministrationRequestDigest(principal.scope, principal.actorId, command) == event.contentDigest)
        }
        expectedEventDigest = digestOwned(LocalAuthenticationCodec.encodeEvent(event))
    }

    /** Attached before this first suspension and before admission/account/audit mutation. */
    suspend fun initialize(transaction: RoomLocalSecurityTransaction): Unit = guarded {
        requirePhase(Phase.NEW)
        phase = Phase.INITIALIZING
        owner = transaction
        transaction.historyPurpose(RuntimeWritePurposeV2.DEVICE_ADMINISTRATION, principal.actorId, event.eventId, event.contentDigest)
        val account = transaction.readProtectedAccount(principal.scope, principal.actorId) ?: transaction.refuse()
        if (!original.sameDependencies(projectedFacts(account, original.admission))) transaction.refuse()
        val selected = if (targetBefore == null) emptySet() else account.state.sessions.filter {
            it.deviceId == targetBefore.deviceId && it.status == SessionStatus.ACTIVE
        }.map { it.sessionId }.toSet()
        val next = if (selected.isEmpty()) account.state else
            (LocalSessions.revokeSelected(account.state, selected) as? SessionTransition.Applied)?.state ?: transaction.refuse()
        val admission = targetAfter?.takeIf { it.deviceId == principal.deviceId } ?: original.admission
        // The verifier exists only in this protected local variable. Only hashes of the exact
        // predicted canonical account/policy/admission/issuance escape into the frame's facts.
        expectedOperator = projectedFacts(ProtectedLocalAccount(next, account.credentialRevision, account.credential), admission)
        // Preserve the separate V1 self-cohost lane and its genuine unchanged C1 seed.
        // V2 selects only a distinct revision-zero target declaration, never a native origin.
        // Re-admission and other unsupported profiles retain their existing history holds.
        val origin = targetBefore?.takeIf { originalCommand != null }?.let {
            transaction.originalCohostDeviceRevocationOrigin(principal, it)
        }
        if (origin != null) {
            transaction.requireDeviceRevocationWriteCount(0)
            val census = mutableListOf<RuntimeDeviceRevocationAccountV1>()
            val expected = linkedMapOf<com.ukkera.pos.types.DomainId, PayloadDigest>()
            val afterAdmission = checkNotNull(targetAfter)
            val predictedImages = mutableListOf(RuntimeDeviceRevocationImageV1.capture(
                ProtectedLocalRecordKey.Admission(principal.scope, origin.deviceId), afterAdmission.revision,
                ProtectedLocalSecurityCodec.encodeAdmission(afterAdmission)))
            // Independently enumerate the WHOLE account set before the original writer's
            // paged effect enumeration. Each verifier stays in this protected local only.
            transaction.forEachAccount(principal.scope) { before ->
                val record = RuntimeDeviceRevocationAccountV1.capture(before, origin.deviceId)
                val after = deviceRevocationAccountAfterV1(before, origin.deviceId)
                check(expected.put(record.actorId, digestOwned(ProtectedLocalSecurityCodec.encodeAccount(after))) == null)
                census += record
                if (record.affected) predictedImages += RuntimeDeviceRevocationImageV1.capture(
                    ProtectedLocalRecordKey.Account(principal.scope, record.actorId), after.state.revision,
                    ProtectedLocalSecurityCodec.encodeAccount(after))
            }
            predictedImages += RuntimeDeviceRevocationImageV1.capture(ProtectedLocalRecordKey.AuthEvent(principal.scope, event.eventId),
                com.ukkera.pos.types.Revision(0), LocalAuthenticationCodec.encodeEvent(event))
            val capture = RuntimeDeviceRevocationCapture.capture(principal, original, authorization,
                checkNotNull(originalCommand), checkNotNull(originalObservation), event, origin, census, predictedImages, expected)
            deviceHistory = capture
            transaction.beginHistoryDeviceRevocation(capture)
        } else if (targetBefore != null && originalCommand != null &&
            transaction.isAdditionalDeviceRevocationCaptureCandidate(principal, original.admission, targetBefore)) {
            transaction.requireDeviceRevocationWriteCount(0)
            val census = mutableListOf<RuntimeDeviceRevocationAccountV1>()
            val expected = linkedMapOf<com.ukkera.pos.types.DomainId, PayloadDigest>()
            val afterAdmission = checkNotNull(targetAfter)
            val predictedImages = mutableListOf(RuntimeDeviceRevocationImageV1.capture(
                ProtectedLocalRecordKey.Admission(principal.scope, targetBefore.deviceId), afterAdmission.revision,
                ProtectedLocalSecurityCodec.encodeAdmission(afterAdmission)))
            // Capture the WHOLE account BEFORE set independently of the writer's later
            // effect enumeration. All ACTIVE target sessions count, including noninteractive.
            transaction.forEachAccount(principal.scope) { before ->
                val record = RuntimeDeviceRevocationAccountV1.capture(before, targetBefore.deviceId)
                val after = deviceRevocationAccountAfterV1(before, targetBefore.deviceId)
                check(expected.put(record.actorId, digestOwned(ProtectedLocalSecurityCodec.encodeAccount(after))) == null)
                census += record
                if (record.affected) predictedImages += RuntimeDeviceRevocationImageV1.capture(
                    ProtectedLocalRecordKey.Account(principal.scope, record.actorId), after.state.revision,
                    ProtectedLocalSecurityCodec.encodeAccount(after))
            }
            predictedImages += RuntimeDeviceRevocationImageV1.capture(ProtectedLocalRecordKey.AuthEvent(principal.scope, event.eventId),
                com.ukkera.pos.types.Revision(0), LocalAuthenticationCodec.encodeEvent(event))
            val capture = RuntimeDeviceRevocationCaptureV2.capture(principal, original, authorization,
                originalCommand, checkNotNull(originalObservation), event, targetBefore, census, predictedImages, expected)
            additionalDeviceHistory = capture
            transaction.beginHistoryAdditionalDeviceRevocation(capture)
        }
        phase = Phase.BODY
    }

    suspend fun prepareNormalReturn(transaction: RoomLocalSecurityTransaction): Unit = guarded {
        requirePhase(Phase.BODY)
        if (transaction !== owner) transaction.refuse()
        check(deviceHistory == null || additionalDeviceHistory == null)
        phase = Phase.PREPARING
        val actual = transaction.readAuthorizationFacts(principal) ?: transaction.refuse()
        if (expectedOperator?.sameDependencies(actual) != true) transaction.refuse()
        targetAfter?.let { expected ->
            val current = transaction.readAdmission(principal.scope, expected.deviceId) ?: transaction.refuse()
            if (digestOwned(ProtectedLocalSecurityCodec.encodeAdmission(current)) !=
                digestOwned(ProtectedLocalSecurityCodec.encodeAdmission(expected))) transaction.refuse()
        }
        // Replays deliberately have no live target-revision condition: this is the ORIGINAL
        // immutable receipt, not permission to revoke a subsequently readmitted incarnation.
        val recorded = transaction.readAuthEvent(principal.scope, event.eventId) ?: transaction.refuse()
        if (digestOwned(LocalAuthenticationCodec.encodeEvent(recorded)) != expectedEventDigest) transaction.refuse()
        deviceHistory?.let { capture ->
            transaction.requireDeviceRevocationWriteCount(capture.expectedWrites)
            var accounts = 0
            transaction.forEachAccount(principal.scope) { current ->
                val expected = capture.expectedAccount(current.state.actorId) ?: transaction.refuse()
                if (digestOwned(ProtectedLocalSecurityCodec.encodeAccount(current)) != expected) transaction.refuse()
                accounts++
            }
            if (accounts != capture.accountCount) transaction.refuse()
        }
        additionalDeviceHistory?.let { capture ->
            transaction.requireDeviceRevocationWriteCount(capture.expectedWrites)
            var accounts = 0
            transaction.forEachAccount(principal.scope) { current ->
                val expected = capture.expectedAccount(current.state.actorId) ?: transaction.refuse()
                if (digestOwned(ProtectedLocalSecurityCodec.encodeAccount(current)) != expected) transaction.refuse()
                accounts++
            }
            if (accounts != capture.accountCount) transaction.refuse()
        }
        transaction.ensureAuthorizationHealthy()
        deviceHistory?.let(transaction::completeHistoryDeviceRevocation)
        additionalDeviceHistory?.let(transaction::completeHistoryAdditionalDeviceRevocation)
        phase = Phase.PREPARED
    }

    /** Store invokes this AFTER all suspendable health/connection/dependency work, before COMMIT. */
    fun finishNow(): Unit = guarded {
        requirePhase(Phase.PREPARED)
        if (!peer.isCurrent()) currentLocalAuthorizationDenied()
        val observed = source.observeCurrent(context)
        if (!eligible(observed)) currentLocalAuthorizationDenied()
        phase = Phase.FINISHED
        finished = true
    }

    /**
     * Normal inner COMMIT only, while the same outer writer loan is still held. The captured
     * transaction context's Room scope has completed; use that live outer caller's context here.
     */
    fun finishAfterCommit(callerContext: CoroutineContext): Boolean {
        callerContext.ensureActive()
        failure?.let { throw it }
        if (!finished || phase != Phase.CLOSED || !peer.isCurrent()) return false
        val observed = try { source.observeCurrent(callerContext) }
        catch (_: RoomCurrentLocalAuthorizationDenied) { return false }
        return eligible(observed)
    }

    fun close() {
        phase = Phase.CLOSED; owner = null; expectedOperator = null
        deviceHistory?.images?.clear(); deviceHistory = null
        additionalDeviceHistory?.images?.clear(); additionalDeviceHistory = null
    }

    private fun eligible(observed: RoomLocalAuthorizationObservation): Boolean = LocalAccessEligibility.matchesCurrentFacts(
        principal, original.issuance, original.account, original.credentialRevision, original.credentialPresent,
        original.admission, observed.issuerContextId, observed.moment)

    private fun projectedFacts(account: ProtectedLocalAccount, admission: LocalDeviceAdmission): RoomLocalAuthorizationFacts =
        RoomLocalAuthorizationFacts(account.state, account.credentialRevision, account.credential != null,
            original.policy, admission, original.issuance, listOf(
                digestOwned(ProtectedLocalSecurityCodec.encodeAccount(account)),
                digestOwned(LocalEffectivePolicyCodec.encodePolicy(original.policy)),
                digestOwned(ProtectedLocalSecurityCodec.encodeAdmission(admission)),
                digestOwned(ProtectedLocalSecurityCodec.encodeIssuance(original.issuance))))

    private fun requirePhase(expected: Phase) { context.ensureActive(); failure?.let { throw it }; check(phase == expected) }
    private inline fun <T> guarded(block: () -> T): T = try { failure?.let { throw it }; block() }
    catch (caught: Exception) { if (failure == null) failure = caught; throw caught }
}

private fun digestOwned(bytes: ByteArray): PayloadDigest = try { sha256(bytes) } finally { bytes.fill(0) }
