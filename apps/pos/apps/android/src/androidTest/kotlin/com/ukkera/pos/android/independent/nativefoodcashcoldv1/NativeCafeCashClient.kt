package com.ukkera.pos.android.independent.nativefoodcashcoldv1

import com.ukkera.pos.android.independent.nativefixture.*
import com.ukkera.pos.app.LocalCommandIntent
import com.ukkera.pos.app.gateway.AndroidOriginalClientJournals
import com.ukkera.pos.app.gateway.LocalWorkflowCredentialLease
import com.ukkera.pos.app.gateway.LocalWorkflowCredentialSource
import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Exact public native composition and one real local session. Four SQL steps use the
 * shared public workflow over the original native employee intent journal. No widened
 * endpoint/security implementation, borrowed gateway credential, extra login, accepting
 * verifier, native result or physical instruction is constructed. Physical I/O/A remains
 * direct-native; test-held requests/results remain comparison DATA, not native authority. */
internal class NativeCafeCashClient(
    override val original: AndroidOriginalLocalClient,
    private val journals: AndroidOriginalClientJournals,
    val publications: LocalCompanyBusinessPublications?,
    private val plan: NativeCafeCashPlan,
    private val evidence: NativeCafeCashEvidence,
) : NativeLocalFixtureClient {
    private val gate = original.physicalRecordingGate() as? LocalPhysicalJournalExecutionOwner
        ?: error("The actual original native physical execution owner is required")
    private val actions = checkNotNull(checkNotNull(original.physicalEndpoint(gate)).physicalActions)
    @Volatile private var tokens: LocalSessionTokens? = null
    private var lifetime: PhysicalInteractiveRequestLifetime? = null
    private var offer: PhysicalInstructionOffer? = null
    private var publication: PhysicalPublishedInstruction? = null
    private var instruction: LocalPhysicalPublishedInstruction? = null
    private var captureAttempted = false
    @Volatile private var closed = false
    @Volatile private var closing = false
    @Volatile private var revocationUnconfirmed = false
    private val staging = NativeCafeCashStaging(original, journals,
        LocalWorkflowCredentialSource { acquireSqlLease() }, evidence)
    override var signIns: Int = 0
        private set

    suspend fun signInFresh(password: CharArray): LocalSessionView {
        check(!closed && !closing && tokens == null && !revocationUnconfirmed && original.isCurrent() && original.peer.isCurrent())
        signIns += 1
        val authenticated = nativeFixtureSecret(password) {
            original.client.signIn(original.peer.connectionId, plan.employeeLogin, it)
        } as? LocalAuthenticationResult.Authenticated ?: error("Genuine fresh founding-cashier login was not obtained")
        tokens = authenticated.tokens // Actual secret ownership precedes every later assertion.
        val current = current()
        val c = original.material.coordinates
        check(current.actorId == c.firstActorId && current.scope == c.scope && current.deviceId == c.deviceId &&
            current.login == plan.employeeLogin)
        requireNativeFixturePolicy(plan.setup.account(c).policy, current.policy)
        evidence.event("FRESH_SINGLE_GENUINE_CASHIER_SESSION", JSONObject().put("actorId", current.actorId.value)
            .put("deviceId", current.deviceId.value).put("sessionId", current.sessionId.value)
            .put("generation", current.generation.value).put("policyRevision", current.policy.revision.value)
            .put("serviceIncarnationId", original.serviceIncarnationId.value))
        return current
    }
    suspend fun current(): LocalSessionView {
        val owned = checkNotNull(tokens)
        check(!closed && !closing && !revocationUnconfirmed && original.isCurrent() && original.peer.isCurrent())
        val actual = original.client.current(credentials(owned)) as? LocalSessionViewResult.Current
            ?: error("The actual current protected session is unavailable")
        check(sameCafeSession(owned.view, actual.view))
        return actual.view
    }
    suspend fun read(request: ReadRequest): ReadReply.Data {
        val before = current()
        val reply = original.client.read(credentials(checkNotNull(tokens)), request) as? ReadReply.Data
            ?: error("Required genuine current-authorized native read is unavailable")
        check(sameCafeSession(before, current()))
        return reply
    }
    suspend fun dispatch(symbol: String, request: MutationRequest): MutationReply.Recorded {
        check(symbol !in NativeCafeCashStaging.actions) { "Selected SQL originals must use their actual native-journal stage" }
        val current = current()
        evidence.freezeRequest(symbol, request, current, original)
        evidence.markCall(symbol)
        val reply = original.client.execute(credentials(checkNotNull(tokens)), request)
        evidence.observeReply(symbol, reply)
        return requireFresh(request, current.actorId, reply)
    }

    suspend fun dispatchStaged(symbol: String, intent: LocalCommandIntent, expected: MutationRequest): MutationReply.Recorded {
        val current = current()
        evidence.freezeRequest(symbol, expected, current, original, sqlStagingRequested = true)
        val reply = staging.stageAndSubmit(symbol, intent, expected, current)
        check(sameCafeSession(current, current()))
        return requireFresh(expected, current.actorId, reply)
    }
    fun requireFourSqlOriginals(saved: List<CafeSavedOriginal>, recovered: Boolean) = staging.requireFourOriginals(saved, recovered)

    /** Each shared-workflow call owns a new copy of THIS live session's actual access
     * bearer. No token is borrowed from BranchUiSession, persisted here or renewed. */
    private suspend fun acquireSqlLease(): LocalWorkflowCredentialLease? {
        if (closed || closing || revocationUnconfirmed || !original.isCurrent() || !original.peer.isCurrent()) return null
        val actual = tokens ?: return null
        val before = current()
        check(tokens === actual && sameCafeSession(actual.view, before))
        val copy = actual.access.useBytes(::OpaqueCredential)
        return try {
            if (closed || closing || revocationUnconfirmed || tokens !== actual || !original.isCurrent() || !original.peer.isCurrent()) {
                copy.destroy()
                null
            } else LocalWorkflowCredentialLease(LocalGatewayCredentials(original.peer.connectionId, copy)) {
                !closed && !closing && !revocationUnconfirmed && tokens === actual && original.isCurrent() && original.peer.isCurrent()
            }
        } catch (failure: Throwable) { copy.destroy(); throw failure }
    }

    /** Durable I precedes publication. Retain every owner immediately. There is deliberately
     * no disk/read/observer hop between actual publication and adoption by the exact gate. */
    suspend fun startOriginal(request: MutationRequest): StoredBytes {
        check(lifetime == null && offer == null && publication == null && instruction == null &&
            request.operationId == plan.startOperationId && request.command == LocalFoodPaymentProfile.COMMAND)
        val input = LocalFoodPaymentCodec.decode(request.payload) as? LocalFoodPaymentRequest.StartCash
            ?: error("Only genuine FOOD CASH Start is selected")
        check(LocalFoodPaymentCodec.encode(input) == request.payload && input.collectionId == plan.collectionId &&
            input.sessionId == plan.cashSessionId && input.attemptId == plan.attemptId && input.amount == plan.payable &&
            input.recordingOperationId == plan.recordingOperationId && input.resolutionOperationId == plan.resolutionOperationId &&
            input.masterAllocationId == plan.masterAllocationId && input.clientAllocationId == plan.clientAllocationId)
        val current = current()
        evidence.freezeRequest("I_START", request, current, original)
        val actualLifetime = actions.newRequestLifetime().also { lifetime = it }
        check(!actualLifetime.denied)
        evidence.markCall("I_START", "GENUINE_NATIVE_ENDPOINT_PHYSICAL_START")
        val started = actions.start(credentials(checkNotNull(tokens)), request, actualLifetime)
        offer = started.offer
        evidence.observeReply("I_START", started.recordedOrDeferred)
        val recorded = requireFresh(request, current.actorId, started.recordedOrDeferred)
        evidence.bind("I_ORIGINAL_NATIVE_RECORDED_AT", recorded.recordedAt.canonicalString())
        val startResult = LocalFoodPaymentCodec.result(recorded.result) as? LocalFoodPaymentResult.AttemptStarted
            ?: error("Actual original FOOD Start did not start the selected CASH attempt")
        val published = actions.publishInstruction(credentials(checkNotNull(tokens)), checkNotNull(offer), actualLifetime)
            as? PhysicalInstructionReply.Published ?: error("Actual original FOOD instruction publication is held")
        publication = published.instruction
        instruction = gate.adoptPublished(published.instruction, actualLifetime)
        val adopted = checkNotNull(instruction) { "The actual gate could not adopt its exact genuine publication" }
        check(adopted.deliverable() && adopted.projection.kind == PhysicalRecordingStartKind.PAYMENT &&
            adopted.projection.originalOperationId == request.operationId && adopted.projection.domainProfile == LocalFoodPaymentProfile.DOMAIN)
        val projection = LocalFoodPaymentPhysicalProjection.parse(adopted.projection.canonicalRequirement)
        val requirement = projection.requirement
        val c = original.material.coordinates
        check(requirement.canonicalBytes == adopted.projection.canonicalRequirement && requirement.scope == current.scope &&
            requirement.originalActorId == current.actorId && requirement.intentOperationId == request.operationId &&
            requirement.intentSemanticDigest == semanticDigest(current.scope, current.actorId, request.command, request.payload) &&
            requirement.command == request.command && requirement.domainProfile == LocalFoodPaymentProfile.DOMAIN &&
            requirement.masterDeviceId == c.deviceId && requirement.clientDeviceId == original.peer.deviceId &&
            requirement.masterAllocationId == plan.masterAllocationId && requirement.clientAllocationId == plan.clientAllocationId &&
            requirement.recordingOperationId == plan.recordingOperationId && requirement.resolutionOperationId == plan.resolutionOperationId &&
            requirement.attemptId == plan.attemptId && projection.target == plan.target() && projection.amount == plan.payable &&
            startResult.target == projection.target && startResult.amount == plan.payable &&
            startResult.requirementDigest == cafeDigest(requirement.canonicalBytes))
        val certificate = checkNotNull(recorded.physicalStart)
        check(certificate.scope == current.scope && certificate.originalActorId == current.actorId && certificate.operationId == request.operationId &&
            certificate.command == request.command && certificate.requirementBytes == requirement.canonicalBytes &&
            certificate.authority.assignmentId == c.assignmentId && certificate.authority.deviceId == c.deviceId &&
            certificate.authority.epoch == original.receipt.core.authorityEpoch)
        evidence.bind("ORIGINAL_NATIVE_REQUIREMENT_BYTES", cafeBase64(requirement.canonicalBytes))
        evidence.bind("ORIGINAL_NATIVE_REQUIREMENT_DIGEST", startResult.requirementDigest.value)
        evidence.bind("ORIGINAL_NATIVE_START_CERTIFICATE_DATA", cafeBase64(certificate.projection))
        evidence.event("GENUINE_FOOD_CASH_INSTRUCTION_ADOPTED_AFTER_DURABLE_I", JSONObject()
            .put("intentOperationId", requirement.intentOperationId.value).put("recordingOperationId", requirement.recordingOperationId.value)
            .put("originalActorId", requirement.originalActorId.value).put("originalDeviceId", requirement.clientDeviceId.value)
            .put("presentationId", "UNBOUND").put("instructionCount", "UNBOUND_NOT_INFERRED_FROM_TEST_CALLS"))
        return requirement.canonicalBytes
    }

    /** One synchronous real native copy. CapturedInMemory is NOT durable O or financial A.
     * The comparison RecordOriginal is never sent through execute, before or after reopen. */
    suspend fun captureOnce(requirementBytes: StoredBytes): LocalFoodCashOutcome.CashReceived {
        check(!captureAttempted)
        val actual = checkNotNull(instruction)
        check(!closed && actual.deliverable() && actual.projection.canonicalRequirement == requirementBytes)
        val current = current()
        val outcome = LocalFoodCashOutcome.CashReceived(plan.received, plan.change, LocalCashEvidenceV1(plan.outcomeEvidenceId, cafeNow()))
        val comparison = LocalFoodPaymentCodec.command(plan.recordingOperationId, LocalFoodPaymentRequest.RecordOriginal(plan.target(), outcome))
        evidence.freezeRequest("O_NATIVE_IMPORT", comparison, current, original)
        evidence.bind("O_ORIGINAL_NATIVE_ATTESTATION_AT", outcome.evidence.occurredAt.canonicalString())
        evidence.markCall("O_NATIVE_IMPORT", "ONE_NATIVE_CAPTURE_THEN_INDEPENDENT_DURABLE_IMPORT_NOT_EXECUTE")
        captureAttempted = true
        val captured = actual.captureFoodPayment(outcome) as? LocalPhysicalOriginalCaptureResult.CapturedInMemory
            ?: error("The genuine native capture is rejected/uncertain; no second observation is permitted")
        check(captured.operationId == comparison.operationId && captured.bodyDigest == cafeDigest(comparison.payload) &&
            checkNotNull(lifetime).denied && !actual.deliverable())
        evidence.event("ORIGINAL_O_ACCEPTED_IN_NATIVE_MEMORY_NOT_YET_DURABLE", JSONObject()
            .put("recordingOperationId", captured.operationId.value).put("bodyDigest", captured.bodyDigest.value)
            .put("durableOriginal", false).put("financialApplication", false))
        return outcome
    }

    /** Read/lookup the native worker's actual committed import, never manufacture or dispatch
     * it. Missing/uncertain data or a timeout holds verification and cannot trigger recapture. */
    suspend fun awaitDurableOriginal(outcome: LocalFoodCashOutcome.CashReceived): CafeOriginalRead = withTimeout(60_000) {
        while (true) {
            val reply = read(LocalFoodPaymentReadCodec.request(LocalFoodPaymentQuery.Original(plan.recordingOperationId)))
            when (val decoded = LocalFoodPaymentReadCodec.decode(reply.payload)) {
                LocalFoodPaymentReadResult.NotAvailable -> delay(100)
                is LocalFoodPaymentReadResult.Original -> {
                    requireOriginal(decoded, outcome)
                    val summary = decoded.summary
                    check(summary.applicationState == LocalFoodPaymentApplicationState.PENDING_RECONCILIATION &&
                        summary.pendingReason == LocalFoodPaymentPendingReason.AWAITING_FINANCIAL_APPLICATION &&
                        summary.lastApplicationOperationId == null && summary.appliedAction == null)
                    val current = current()
                    val comparison = LocalFoodPaymentCodec.command(plan.recordingOperationId, LocalFoodPaymentRequest.RecordOriginal(plan.target(), outcome))
                    val found = original.client.lookup(credentials(checkNotNull(tokens)), identity(current, comparison)) as? LookupReply.Found
                        ?: error("Native durable O has no genuine recoverable imported original result")
                    check(found.result.committed && found.result.replayed && found.result.physicalStart == null &&
                        found.result.operationId == plan.recordingOperationId && found.result.originalActorId == current.actorId &&
                        found.result.authorityEpoch == original.receipt.core.authorityEpoch && found.result.recordedAt == summary.recordedAt)
                    val imported = LocalFoodPaymentCodec.result(found.result.result) as? LocalFoodPaymentResult.RawRetained
                        ?: error("The actual native O import did not retain raw evidence awaiting application")
                    check(imported.target == plan.target() && imported.phase == LocalFoodPaymentFactPhase.ORIGINAL &&
                        imported.reason == LocalFoodPaymentPendingReason.AWAITING_FINANCIAL_APPLICATION && sameCafeSession(current, current()))
                    evidence.observeReply("O_NATIVE_IMPORT", found.result)
                    evidence.bind("O_ORIGINAL_NATIVE_ATTESTATION_AT", outcome.evidence.occurredAt.canonicalString())
                    evidence.bind("O_ORIGINAL_NATIVE_IMPORT_RECORDED_AT", summary.recordedAt.canonicalString())
                    evidence.bind("ORIGINAL_PENDING_NATIVE_READ_BYTES", cafeBase64(reply.payload))
                    evidence.event("ACTUAL_DURABLE_O_IMPORT_OBSERVED_BEFORE_A", JSONObject()
                        .put("operationId", found.result.operationId.value).put("sequence", found.result.sequence.value)
                        .put("attestationAt", outcome.evidence.occurredAt.canonicalString()).put("rawImportAt", summary.recordedAt.canonicalString())
                        .put("receivedMinor", outcome.received.minor).put("changeMinor", outcome.change.minor)
                        .put("originalState", "PENDING_RECONCILIATION").put("recordOriginalExecute", false))
                    return@withTimeout CafeOriginalRead(reply, decoded)
                }
                else -> error("Actual Original read is unavailable or has the wrong result kind")
            }
        }
        @Suppress("UNREACHABLE_CODE") error("Unreachable")
    }
    suspend fun originalRead(): CafeOriginalRead {
        val reply = read(LocalFoodPaymentReadCodec.request(LocalFoodPaymentQuery.Original(plan.recordingOperationId)))
        val decoded = LocalFoodPaymentReadCodec.decode(reply.payload) as? LocalFoodPaymentReadResult.Original
            ?: error("Exact original FOOD CASH read is unavailable")
        return CafeOriginalRead(reply, decoded)
    }
    fun requireOriginal(actual: LocalFoodPaymentReadResult.Original, outcome: LocalFoodCashOutcome.CashReceived) {
        val body = LocalFoodPaymentCodec.encode(LocalFoodPaymentRequest.RecordOriginal(plan.target(), outcome))
        check(actual.summary.target == plan.target() && actual.summary.originalActorId == original.material.coordinates.firstActorId &&
            actual.summary.recordingActorId == original.material.coordinates.firstActorId && actual.summary.bodyDigest == cafeDigest(body) &&
            actual.summary.amount == plan.payable && actual.outcome == outcome)
    }
    suspend fun applyOnce(pending: CafeOriginalRead): MutationReply.Recorded {
        val originalOutcome = pending.original.outcome as? LocalFoodCashOutcome.CashReceived ?: error("Original is not successful CASH")
        val reply = dispatch("A_APPLY", LocalFoodPaymentCodec.command(plan.applyOperationId,
            LocalFoodPaymentRequest.ApplyOriginal(plan.recordingOperationId)))
        val applied = LocalFoodPaymentCodec.result(reply.result) as? LocalFoodPaymentResult.Applied
            ?: error("Actual FOOD application did not commit CASH coverage")
        check(applied.target == plan.target() && applied.phase == LocalFoodPaymentFactPhase.ORIGINAL &&
            applied.action == LocalFoodPaymentAction.CASH_PAID && applied.applicationOperationId == plan.applyOperationId)
        val after = originalRead().original
        requireOriginal(after, originalOutcome)
        requireSameCafeImmutableOriginal(pending.original, after)
        check(after.summary.applicationState == LocalFoodPaymentApplicationState.APPLIED && after.summary.pendingReason == null &&
            after.summary.lastApplicationOperationId == reply.operationId && after.summary.appliedAction == LocalFoodPaymentAction.CASH_PAID)
        evidence.bind("A_ORIGINAL_NATIVE_COMMITTED_AT", reply.recordedAt.canonicalString())
        evidence.event("DURABLE_A_CASH_PAID_ONCE", JSONObject().put("operationId", reply.operationId.value)
            .put("recordingOperationId", plan.recordingOperationId.value).put("sequence", reply.sequence.value))
        return reply
    }
    suspend fun recover(saved: CafeSavedOriginal): MutationReply.Recorded {
        val current = current()
        val c = original.material.coordinates
        check(saved.scope == current.scope && saved.identity.originalActorId == current.actorId && saved.deviceId == current.deviceId &&
            saved.assignmentId == c.assignmentId && saved.installationId == c.installationId && saved.storageInstanceId == c.storageInstanceId &&
            saved.authorityEpoch == original.receipt.core.authorityEpoch &&
            saved.identity.semanticSha256 == semanticDigest(saved.scope, saved.identity.originalActorId, saved.request.command, saved.request.payload).value)
        val nativeSqlJournal = saved.symbol in NativeCafeCashStaging.actions
        val found = if (nativeSqlJournal) {
            staging.recover(saved, current)
        } else {
            (original.client.lookup(credentials(checkNotNull(tokens)), saved.identity) as? LookupReply.Found)?.result
                ?: error("Fresh current-authorized exact original lookup did not return the original result")
        }
        saved.result.requireSame(found)
        check(found.replayed && found.physicalStart == null && sameCafeSession(current, current()))
        evidence.event("CURRENT_AUTHORIZED_ORIGINAL_LOOKUP_ONLY", JSONObject().put("symbol", saved.symbol)
            .put("operationId", found.operationId.value).put("sequence", found.sequence.value)
            .put("originalRecordedAt", found.recordedAt.canonicalString()).put("newPhysicalInstruction", false)
            .put("requestJournalPath", if (nativeSqlJournal) "ACTUAL_EXISTING_NATIVE_EMPLOYEE_SQL_INTENT" else "PROTECTED_ORIGINAL_BACKEND_LOOKUP"))
        return found
    }
    private fun identity(view: LocalSessionView, request: MutationRequest) = OperationIdentity(view.scope.branchId,
        request.operationId, view.actorId, 1, semanticDigest(view.scope, view.actorId, request.command, request.payload).value)
    private fun requireFresh(request: MutationRequest, actor: DomainId, reply: MutationReply): MutationReply.Recorded {
        val recorded = reply as? MutationReply.Recorded ?: error("Actual original mutation did not durably acknowledge")
        check(recorded.committed && !recorded.replayed && recorded.operationId == request.operationId &&
            recorded.originalActorId == actor && recorded.authorityEpoch == original.receipt.core.authorityEpoch)
        return recorded
    }
    private fun credentials(actual: LocalSessionTokens) = LocalGatewayCredentials(original.peer.connectionId, actual.access)

    /** Denial precedes actual logout/destruction/journal close. Accepted original custody is
     * owned independently by the real native worker, not erased by this end-only denial. */
    override suspend fun close(): Unit = withContext(NonCancellable) {
        if (closed) return@withContext
        closing = true
        staging.retire()
        instruction?.deny(); publication?.deny(); offer?.deny(); lifetime?.deny()
        // Caller/lease completion alone cannot prove native journal-worker cleanup.
        // A failed exact-view drain holds this client's logout and root-journal closure.
        staging.awaitRetiredCalls()
        var failure: Throwable? = null
        suspend fun attempt(action: suspend () -> Unit) {
            try { action() } catch (caught: Throwable) {
                val prior = failure
                if (prior == null) failure = caught else if (prior !== caught) prior.addSuppressed(caught)
            }
        }
        tokens?.let { actual -> attempt {
            revocationUnconfirmed = true
            try {
                check(original.client.logout(credentials(actual)) == LocalSignOutResult.Revoked) { "Actual cashier logout is unconfirmed" }
                revocationUnconfirmed = false
            } finally { actual.destroy(); tokens = null }
        } }
        attempt { journals.close() }
        failure?.let { throw it }
        check(!revocationUnconfirmed)
        instruction = null; publication = null; offer = null; lifetime = null; closed = true
    }
    override fun toString(): String = "NativeCafeCashClient([REDACTED])"
}

internal data class CafeOriginalRead(val reply: ReadReply.Data, val original: LocalFoodPaymentReadResult.Original)
internal fun requireSameCafeImmutableOriginal(before: LocalFoodPaymentReadResult.Original, after: LocalFoodPaymentReadResult.Original) {
    check(before.outcome == after.outcome && before.summary.target == after.summary.target &&
        before.summary.originalActorId == after.summary.originalActorId && before.summary.recordingActorId == after.summary.recordingActorId &&
        before.summary.bodyDigest == after.summary.bodyDigest && before.summary.recordedAt == after.summary.recordedAt &&
        before.summary.amount == after.summary.amount) { "Native original O/body/attestation/import-time identity changed" }
}
private fun sameCafeSession(a: LocalSessionView, b: LocalSessionView): Boolean = a.scope == b.scope && a.actorId == b.actorId &&
    a.deviceId == b.deviceId && a.sessionId == b.sessionId && a.generation == b.generation && a.login == b.login &&
    a.policy.revision == b.policy.revision && a.accessExpiresAt == b.accessExpiresAt && a.workExpiresAt == b.workExpiresAt
