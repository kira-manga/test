package com.ukkera.pos.android.independent.nativefoodpaymentalreadyappliedv1

import com.ukkera.pos.android.independent.nativefixture.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** Genuine native composition and separately authenticated A/B on its admitted device.
 * B exists solely to observe the independent untouched drawer. Only A's actual existing
 * native employee intent journal stages R. No accepting authority/history service, invented
 * principal, raw RecordOriginal execute or recovery mutation is supplied. */
internal class NativeFoodAlreadyAppliedClient(
    override val original: AndroidOriginalLocalClient,
    private val journals: AndroidOriginalClientJournals,
    val publications: LocalCompanyBusinessPublications?,
    private val plan: NativeFoodAlreadyAppliedPlan,
    private val evidence: NativeFoodAlreadyAppliedEvidence,
) : NativeLocalFixtureClient {
    private val gate = original.physicalRecordingGate() as? LocalPhysicalJournalExecutionOwner
        ?: error("The genuine original native physical execution owner is required")
    private val actions = checkNotNull(checkNotNull(original.physicalEndpoint(gate)).physicalActions)
    private val sessions = linkedMapOf<FoodAlreadyAppliedActor, LocalSessionTokens>()
    private var lifetime: PhysicalInteractiveRequestLifetime? = null
    private var offer: PhysicalInstructionOffer? = null
    private var publication: PhysicalPublishedInstruction? = null
    private var instruction: LocalPhysicalPublishedInstruction? = null
    private var captureAttempted = false
    @Volatile private var closing = false
    @Volatile private var closed = false
    @Volatile private var revocationUnconfirmed = false
    private var closeFailure: Throwable? = null
    private val staging = NativeFoodAlreadyAppliedStaging(original, journals,
        LocalWorkflowCredentialSource { acquireLeaseA() }, plan, evidence)
    override var signIns = 0
        private set

    suspend fun enrollB(password: CharArray, headSecret: CharArray) {
        check(sessions.isEmpty() && !closing && !closed && original.isCurrent())
        val requested = plan.account(FoodAlreadyAppliedActor.B, original.material.coordinates)
        evidence.bind("B_ENROLLMENT_REQUEST", JSONObject().put("requestId", plan.enrollmentB.value)
            .put("actorId", requested.actorId.value).put("login", requested.login.value).put("credentialWasProvided", true))
        val action = nativeFixtureValue(original.prepareEmployeeEnrollment(plan.enrollmentB, requested, credentialWasProvided = true))
        val connection = nativeFixtureSecret(headSecret) {
            nativeFixtureValue(original.approveEmployeeEnrollment(action, plan.headLogin, it))
        }
        check(connection.belongsTo(original) && connection.isCurrent() && !connection.isLookupOnly &&
            connection.actorId == requested.actorId && connection.originalRequestId == plan.enrollmentB)
        val definition = checkNotNull(connection.originalDefinition(plan.enrollmentB, requested.login))
        check(definition.actorId == requested.actorId && definition.login == requested.login &&
            definition.admittedDeviceIds == requested.admittedDeviceIds && definition.tokenPolicy == requested.tokenPolicy)
        requireNativeFixturePolicy(requested.policy, definition.policy)
        val applied = nativeFixtureSecret(password) { connection.provisionAccount(plan.enrollmentB, requested, it) }
            as? LocalCredentialAdministrationResult.Applied ?: error("The real second drawer custodian was not provisioned")
        val retained = connection.lookupAccountProvisioning(plan.enrollmentB, requested, true) as? LocalProvisioningStatus.Applied
            ?: error("The genuine second account original cannot be reobserved")
        check(retained.eventId == applied.eventId)
        evidence.bind("B_GENUINE_ENROLLMENT", JSONObject().put("originalActionId", connection.originalActionId.value)
            .put("eventId", applied.eventId.value).put("actorId", requested.actorId.value))
    }

    /** Founding policies begin at zero. Reach the frozen activated revision seven using
     * seven real next-revision head approvals/sole-writer activations, before any sign-in. */
    suspend fun activateFixturePolicyA(headSecret: CharArray) {
        check(sessions.isEmpty() && !closing && !closed && original.isCurrent())
        for (revision in 1L..plan.activePolicyRevision.value) {
            val policy = plan.policy(FoodAlreadyAppliedActor.A, original.material.coordinates, Revision(revision))
            val requestId = plan.input(1_000 + revision.toInt())
            val action = nativeFixtureValue(original.prepareEmployeePolicy(requestId, policy))
            val approval = nativeFixtureSecret(headSecret) {
                nativeFixtureValue(original.approveEmployeePolicy(action, plan.headLogin, it))
            }
            check(approval.originalActionId == action.originalActionId && approval.requested.request.requestId == requestId &&
                approval.requested.request.targetId == original.material.coordinates.firstActorId)
            val applied = original.activateEmployeePolicy(approval, policy) as? LocalCredentialAdministrationResult.Applied
                ?: error("The actual next-revision policy activation did not settle")
            val retained = original.lookupEmployeePolicy(approval, policy) as? LocalProvisioningStatus.Applied
                ?: error("The genuine current-policy original is unavailable")
            check(retained.eventId == applied.eventId)
            evidence.event("GENUINE_PREREQUISITE_POLICY_ACTIVATED", JSONObject().put("actorId", policy.actorId.value)
                .put("policyRevision", revision).put("originalActionId", approval.originalActionId.value).put("eventId", applied.eventId.value))
        }
    }

    suspend fun signInFresh(actor: FoodAlreadyAppliedActor, secret: CharArray): LocalSessionView {
        check(!closed && !closing && !revocationUnconfirmed && actor !in sessions && original.isCurrent() && original.peer.isCurrent())
        val authenticated = nativeFixtureSecret(secret) { original.client.signIn(original.peer.connectionId, plan.login(actor), it) }
            as? LocalAuthenticationResult.Authenticated ?: error("A genuine separate current employee session is required")
        sessions[actor] = authenticated.tokens // Own actual secrets before any later assertion.
        signIns += 1
        val current = current(actor)
        evidence.event("FRESH_GENUINE_${actor.name}_SESSION", JSONObject().put("actorId", current.actorId.value)
            .put("deviceId", current.deviceId.value).put("authSessionId", current.sessionId.value)
            .put("generation", current.generation.value).put("policyRevision", current.policy.revision.value)
            .put("serviceIncarnationId", original.serviceIncarnationId.value))
        return current
    }
    suspend fun current(actor: FoodAlreadyAppliedActor = FoodAlreadyAppliedActor.A): LocalSessionView {
        check(!closed && !closing && !revocationUnconfirmed && original.isCurrent() && original.peer.isCurrent())
        val owned = checkNotNull(sessions[actor])
        val actual = (original.client.current(credentials(actor)) as? LocalSessionViewResult.Current)?.view
            ?: error("The actual protected current session is unavailable")
        val c = original.material.coordinates
        check(actual.scope == c.scope && actual.actorId == plan.actorId(actor, c) && actual.deviceId == c.deviceId &&
            actual.deviceId == original.peer.deviceId && actual.login == plan.login(actor) && sameFoodAlreadyAppliedSession(owned.view, actual))
        requireNativeFixturePolicy(plan.policy(actor, c, if (actor == FoodAlreadyAppliedActor.A) plan.activePolicyRevision else Revision(0)), actual.policy)
        return actual
    }
    suspend fun read(request: ReadRequest, actor: FoodAlreadyAppliedActor = FoodAlreadyAppliedActor.A): ReadReply.Data {
        val before = current(actor)
        check(request.query in original.readKeys)
        val reply = original.client.read(credentials(actor), request) as? ReadReply.Data
            ?: error("A genuine current-authorized native read is unavailable; no empty/zero fallback")
        check(sameFoodAlreadyAppliedSession(before, current(actor)))
        return reply
    }
    suspend fun dispatch(symbol: String, request: MutationRequest, actor: FoodAlreadyAppliedActor = FoodAlreadyAppliedActor.A): MutationReply.Recorded {
        check(symbol != NativeFoodAlreadyAppliedStaging.REJECTION && request.operationId != plan.rejectionOperationId &&
            request.command in original.commandKeys)
        check(request.command != LocalFoodPaymentProfile.COMMAND || LocalFoodPaymentCodec.decode(request.payload) is LocalFoodPaymentRequest.ApplyOriginal) {
            "Native Start/capture/import are not ordinary employee execute commands"
        }
        val before = current(actor)
        evidence.freezeRequest(symbol, request, before, original)
        evidence.markCall(symbol)
        val reply = original.client.execute(credentials(actor), request)
        evidence.observeReply(symbol, reply)
        val actual = requireFresh(request, before.actorId, reply)
        check(actual.physicalStart == null && sameFoodAlreadyAppliedSession(before, current(actor)))
        return actual
    }
    private suspend fun acquireLeaseA(): LocalWorkflowCredentialLease? {
        if (closed || closing || revocationUnconfirmed || !original.isCurrent() || !original.peer.isCurrent()) return null
        val tokens = sessions[FoodAlreadyAppliedActor.A] ?: return null
        check(sameFoodAlreadyAppliedSession(tokens.view, current()) && sessions[FoodAlreadyAppliedActor.A] === tokens)
        val copy = tokens.access.useBytes(::OpaqueCredential)
        return try {
            if (closed || closing || revocationUnconfirmed || sessions[FoodAlreadyAppliedActor.A] !== tokens) {
                copy.destroy(); null
            } else LocalWorkflowCredentialLease(LocalGatewayCredentials(original.peer.connectionId, copy)) {
                !closed && !closing && !revocationUnconfirmed && sessions[FoodAlreadyAppliedActor.A] === tokens &&
                    original.isCurrent() && original.peer.isCurrent()
            }
        } catch (failure: Throwable) { copy.destroy(); throw failure }
    }

    /** Own the actual published instruction immediately; no observer/read/disk hop between
     * native publication and adoption by the exact gate. Durable I precedes publication. */
    suspend fun startOriginal(request: MutationRequest): StoredBytes {
        check(lifetime == null && offer == null && publication == null && instruction == null && request.operationId == plan.startOperationId)
        val input = LocalFoodPaymentCodec.decode(request.payload) as? LocalFoodPaymentRequest.StartCash
            ?: error("Only the pre-pinned native FOOD CASH Start is selected")
        check(input.collectionId == plan.collectionId && input.sessionId == plan.cashSessionA && input.attemptId == plan.attemptId &&
            input.amount == plan.payable && input.recordingOperationId == plan.recordingOperationId &&
            input.resolutionOperationId == plan.resolutionOperationId && input.masterAllocationId == plan.masterAllocationId &&
            input.clientAllocationId == plan.clientAllocationId)
        val view = current()
        evidence.freezeRequest("I_START", request, view, original)
        val actualLifetime = actions.newRequestLifetime().also { lifetime = it }
        evidence.markCall("I_START", "GENUINE_NATIVE_PHYSICAL_START")
        val started = actions.start(credentials(FoodAlreadyAppliedActor.A), request, actualLifetime)
        offer = started.offer
        evidence.observeReply("I_START", started.recordedOrDeferred)
        val recorded = requireFresh(request, view.actorId, started.recordedOrDeferred)
        val result = LocalFoodPaymentCodec.result(recorded.result) as? LocalFoodPaymentResult.AttemptStarted
            ?: error("The genuine native Start did not create the expected attempt")
        val published = actions.publishInstruction(credentials(FoodAlreadyAppliedActor.A), checkNotNull(offer), actualLifetime)
            as? PhysicalInstructionReply.Published ?: error("The actual instruction publication is held")
        publication = published.instruction
        instruction = gate.adoptPublished(published.instruction, actualLifetime)
        val adopted = checkNotNull(instruction)
        check(adopted.deliverable() && adopted.projection.kind == PhysicalRecordingStartKind.PAYMENT &&
            adopted.projection.originalOperationId == plan.startOperationId && adopted.projection.domainProfile == LocalFoodPaymentProfile.DOMAIN)
        val projection = LocalFoodPaymentPhysicalProjection.parse(adopted.projection.canonicalRequirement)
        val requirement = projection.requirement
        val c = original.material.coordinates
        check(requirement.scope == view.scope && requirement.originalActorId == view.actorId &&
            requirement.intentOperationId == request.operationId && requirement.command == request.command &&
            requirement.intentSemanticDigest == semanticDigest(view.scope, view.actorId, request.command, request.payload) &&
            requirement.masterDeviceId == c.deviceId && requirement.clientDeviceId == original.peer.deviceId &&
            requirement.masterAllocationId == plan.masterAllocationId && requirement.clientAllocationId == plan.clientAllocationId &&
            projection.target == plan.target() && projection.amount == plan.payable && result.target == projection.target &&
            result.amount == plan.payable && result.requirementDigest == foodAlreadyAppliedDigest(requirement.canonicalBytes))
        val certificate = checkNotNull(recorded.physicalStart)
        check(certificate.scope == view.scope && certificate.originalActorId == view.actorId && certificate.operationId == request.operationId &&
            certificate.command == request.command && certificate.requirementBytes == requirement.canonicalBytes &&
            certificate.authority.assignmentId == c.assignmentId && certificate.authority.deviceId == c.deviceId &&
            certificate.authority.epoch == original.receipt.core.authorityEpoch)
        evidence.bind("ACTUAL_NATIVE_REQUIREMENT_DATA", foodAlreadyAppliedBase64(requirement.canonicalBytes))
        evidence.bind("ACTUAL_NATIVE_START_CERTIFICATE_DATA", foodAlreadyAppliedBase64(certificate.projection))
        evidence.event("GENUINE_NATIVE_INSTRUCTION_ADOPTED_AFTER_DURABLE_START", JSONObject().put("operationId", request.operationId.value)
            .put("sequence", recorded.sequence.value).put("recordingOperationId", plan.recordingOperationId.value))
        return requirement.canonicalBytes
    }

    suspend fun captureOnce(requirement: StoredBytes): LocalFoodCashOutcome.CashReceived {
        check(!captureAttempted && !closed && !closing)
        val actual = checkNotNull(instruction)
        check(actual.deliverable() && actual.projection.canonicalRequirement == requirement)
        val outcome = LocalFoodCashOutcome.CashReceived(plan.received, plan.change,
            LocalCashEvidenceV1(plan.outcomeEvidenceId, foodAlreadyAppliedNow()))
        val comparison = LocalFoodPaymentCodec.command(plan.recordingOperationId, LocalFoodPaymentRequest.RecordOriginal(plan.target(), outcome))
        evidence.freezeRequest("O_NATIVE_IMPORT", comparison, current(), original)
        evidence.markCall("O_NATIVE_IMPORT", "ONE_GENUINE_NATIVE_CAPTURE_AND_INDEPENDENT_DURABLE_IMPORT_NOT_EXECUTE")
        captureAttempted = true
        val captured = actual.captureFoodPayment(outcome) as? LocalPhysicalOriginalCaptureResult.CapturedInMemory
            ?: error("Native capture was rejected/uncertain; a second capture is forbidden")
        check(captured.operationId == comparison.operationId && captured.bodyDigest == foodAlreadyAppliedDigest(comparison.payload) &&
            checkNotNull(lifetime).denied && !actual.deliverable())
        evidence.event("ONE_ORIGINAL_CAPTURE_ACCEPTED_IN_NATIVE_MEMORY", JSONObject().put("durableOriginal", false)
            .put("financialAccounting", false).put("recordingOperationId", captured.operationId.value))
        return outcome
    }

    suspend fun awaitDurableOriginal(outcome: LocalFoodCashOutcome.CashReceived): LocalFoodPaymentReadResult.Original = withTimeout(60_000) {
        while (true) {
            val reply = read(LocalFoodPaymentReadCodec.request(LocalFoodPaymentQuery.Original(plan.recordingOperationId)))
            when (val value = LocalFoodPaymentReadCodec.decode(reply.payload)) {
                LocalFoodPaymentReadResult.NotAvailable -> delay(100)
                is LocalFoodPaymentReadResult.Original -> {
                    requireOriginal(value, outcome)
                    check(value.summary.applicationState == LocalFoodPaymentApplicationState.PENDING_RECONCILIATION &&
                        value.summary.pendingReason == LocalFoodPaymentPendingReason.AWAITING_FINANCIAL_APPLICATION &&
                        value.summary.lastApplicationOperationId == null && value.summary.appliedAction == null)
                    val before = current()
                    val comparison = LocalFoodPaymentCodec.command(plan.recordingOperationId, LocalFoodPaymentRequest.RecordOriginal(plan.target(), outcome))
                    val identity = OperationIdentity(before.scope.branchId, comparison.operationId, before.actorId, 1,
                        semanticDigest(before.scope, before.actorId, comparison.command, comparison.payload).value)
                    val found = original.client.lookup(credentials(FoodAlreadyAppliedActor.A), identity) as? LookupReply.Found
                        ?: error("The native worker's durable imported original cannot be recovered")
                    val recorded = found.result
                    check(recorded.committed && recorded.replayed && recorded.physicalStart == null &&
                        recorded.operationId == plan.recordingOperationId && recorded.originalActorId == before.actorId &&
                        recorded.authorityEpoch == original.receipt.core.authorityEpoch && recorded.recordedAt == value.summary.recordedAt)
                    val raw = LocalFoodPaymentCodec.result(recorded.result) as? LocalFoodPaymentResult.RawRetained
                        ?: error("The actual native raw original is not retained awaiting accounting")
                    check(raw.target == plan.target() && raw.phase == LocalFoodPaymentFactPhase.ORIGINAL &&
                        raw.reason == LocalFoodPaymentPendingReason.AWAITING_FINANCIAL_APPLICATION && sameFoodAlreadyAppliedSession(before, current()))
                    evidence.observeReply("O_NATIVE_IMPORT", recorded)
                    evidence.bind("ORIGINAL_DURABLE_PENDING_READ_DATA", foodAlreadyAppliedBase64(reply.payload))
                    return@withTimeout value
                }
                else -> error("Native Original disclosure is unavailable or has an unsupported result kind")
            }
        }
        @Suppress("UNREACHABLE_CODE") error("Unreachable")
    }
    suspend fun originalRead(): LocalFoodPaymentReadResult.Original =
        LocalFoodPaymentReadCodec.decode(read(LocalFoodPaymentReadCodec.request(LocalFoodPaymentQuery.Original(plan.recordingOperationId))).payload)
            as? LocalFoodPaymentReadResult.Original ?: error("The exact genuine Original read is unavailable")
    fun requireOriginal(value: LocalFoodPaymentReadResult.Original, outcome: LocalFoodCashOutcome.CashReceived) {
        val body = LocalFoodPaymentCodec.encode(LocalFoodPaymentRequest.RecordOriginal(plan.target(), outcome))
        check(value.summary.target == plan.target() && value.summary.originalActorId == original.material.coordinates.firstActorId &&
            value.summary.recordingActorId == original.material.coordinates.firstActorId && value.summary.amount == plan.payable &&
            value.summary.bodyDigest == foodAlreadyAppliedDigest(body) && value.outcome == outcome)
    }
    suspend fun stageSeparateOrdinary(pending: LocalFoodPaymentReadResult.Original) = staging.stage(pending, current())
    suspend fun applyEarlierOnce(pending: LocalFoodPaymentReadResult.Original): MutationReply.Recorded {
        check(pending.summary.applicationState == LocalFoodPaymentApplicationState.PENDING_RECONCILIATION)
        val outcome = pending.outcome as? LocalFoodCashOutcome.CashReceived ?: error("The original is not successful CASH")
        val reply = dispatch("A_EARLIER_APPLY", LocalFoodPaymentCodec.command(plan.earlierApplyOperationId,
            LocalFoodPaymentRequest.ApplyOriginal(plan.recordingOperationId)))
        val applied = LocalFoodPaymentCodec.result(reply.result) as? LocalFoodPaymentResult.Applied
            ?: error("The earlier genuine original application did not account CASH")
        check(applied.target == plan.target() && applied.phase == LocalFoodPaymentFactPhase.ORIGINAL &&
            applied.action == LocalFoodPaymentAction.CASH_PAID && applied.applicationOperationId == plan.earlierApplyOperationId)
        val after = originalRead()
        requireOriginal(after, outcome)
        requireSameFoodAlreadyAppliedOriginal(pending, after)
        check(after.summary.applicationState == LocalFoodPaymentApplicationState.APPLIED && after.summary.pendingReason == null &&
            after.summary.lastApplicationOperationId == reply.operationId && after.summary.appliedAction == LocalFoodPaymentAction.CASH_PAID)
        return reply
    }
    suspend fun submitSeparateOrdinary() = staging.submitAfterEarlierApply(current())
    suspend fun lookupOrdinary(saved: FoodAlreadyAppliedSavedOriginal): MutationReply.Recorded {
        val before = current()
        val c = original.material.coordinates
        check(saved.scope == before.scope && saved.identity.originalActorId == before.actorId && saved.deviceId == before.deviceId &&
            saved.installationId == c.installationId && saved.assignmentId == c.assignmentId && saved.storageInstanceId == c.storageInstanceId &&
            saved.result.epoch == original.receipt.core.authorityEpoch)
        val found = staging.lookup(saved, before)
        check(sameFoodAlreadyAppliedSession(before, current()))
        return found
    }
    private fun requireFresh(request: MutationRequest, actor: DomainId, reply: MutationReply): MutationReply.Recorded =
        (reply as? MutationReply.Recorded ?: error("The genuine original mutation did not settle durably")).also {
            check(it.committed && !it.replayed && it.operationId == request.operationId && it.originalActorId == actor &&
                it.authorityEpoch == original.receipt.core.authorityEpoch)
        }
    private fun credentials(actor: FoodAlreadyAppliedActor) = LocalGatewayCredentials(original.peer.connectionId, checkNotNull(sessions[actor]).access)

    override suspend fun close(): Unit = withContext(NonCancellable) {
        if (closed) return@withContext
        closing = true
        // Keep any uncertain earlier close unconfirmed, even if a later cleanup call
        // finishes. Every independent owner is visited after a denial/drain failure.
        var failure = closeFailure
        fun remember(next: Throwable) { failure?.let { if (it !== next) it.addSuppressed(next) } ?: run { failure = next } }
        suspend fun attempt(block: suspend () -> Unit) {
            try { block() } catch (caught: Throwable) { remember(caught) }
        }
        attempt { staging.retire() }
        instruction?.let { actual -> attempt { actual.deny() } }
        publication?.let { actual -> attempt { actual.deny() } }
        offer?.let { actual -> attempt { actual.deny() } }
        lifetime?.let { actual -> attempt { actual.deny() } }
        // Actual view close/worker drain, not caller cancellation, precedes logout/root closure.
        attempt { staging.awaitRetiredCalls() }
        sessions.toMap().forEach { (actor, tokens) ->
            var revoked = false
            try {
                check(original.client.logout(LocalGatewayCredentials(original.peer.connectionId, tokens.access)) == LocalSignOutResult.Revoked)
                revoked = true
            } catch (caught: Throwable) { revocationUnconfirmed = true; remember(caught) }
            if (revoked) attempt { evidence.event("ACTUAL_${actor.name}_LOGOUT_REVOKED") }
            attempt {
                tokens.destroy()
                sessions.remove(actor)
            }
        }
        attempt { journals.close() }
        attempt { check(!revocationUnconfirmed) { "Actual session revocation is unconfirmed" } }
        closeFailure = failure
        failure?.let { throw it }
        instruction = null; publication = null; offer = null; lifetime = null; closed = true
    }
    override fun toString() = "NativeFoodAlreadyAppliedClient([REDACTED])"
}

internal fun requireSameFoodAlreadyAppliedOriginal(before: LocalFoodPaymentReadResult.Original, after: LocalFoodPaymentReadResult.Original) {
    check(before.outcome == after.outcome && before.summary.target == after.summary.target &&
        before.summary.originalActorId == after.summary.originalActorId && before.summary.recordingActorId == after.summary.recordingActorId &&
        before.summary.bodyDigest == after.summary.bodyDigest && before.summary.recordedAt == after.summary.recordedAt &&
        before.summary.amount == after.summary.amount) { "The immutable native original/body/actor/cash-custody identity changed" }
}
private fun sameFoodAlreadyAppliedSession(a: LocalSessionView, b: LocalSessionView) = a.scope == b.scope && a.actorId == b.actorId &&
    a.deviceId == b.deviceId && a.sessionId == b.sessionId && a.generation == b.generation && a.login == b.login &&
    a.policy.revision == b.policy.revision && a.accessExpiresAt == b.accessExpiresAt && a.workExpiresAt == b.workExpiresAt
