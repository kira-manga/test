package com.ukkera.pos.android.independent.coldoriginalstartv1

import com.ukkera.pos.android.independent.nativefixture.*
import com.ukkera.pos.app.gateway.AndroidOriginalClientJournals
import com.ukkera.pos.database.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Direct observation of the actual same-device protected endpoint and actual native gate.
 * No LocalMasterClient/authority/SPI implementation, endpoint wrapper, SDK capability,
 * session, projection, accepting callback or replacement native owner is constructed here.
 * This is a backend baseline, not the high-level gateway or displayed production UI. */
internal class NativeColdOriginalClient(
    override val original: AndroidOriginalLocalClient,
    private val journals: AndroidOriginalClientJournals,
    val publications: LocalCompanyBusinessPublications?,
    private val plan: NativeColdOriginalFixturePlan,
    private val evidence: NativeColdOriginalEvidence,
) : NativeLocalFixtureClient {
    private val gate = original.physicalRecordingGate() as? LocalPhysicalJournalExecutionOwner
        ?: error("The actual registered original physical execution owner is missing")
    private val actions = checkNotNull(checkNotNull(original.physicalEndpoint(gate)).physicalActions) {
        "The genuine original endpoint physical companion is missing"
    }
    private var tokens: LocalSessionTokens? = null
    private var lifetime: PhysicalInteractiveRequestLifetime? = null
    private var instruction: LocalPhysicalPublishedInstruction? = null
    private var closed = false
    private var revocationUnconfirmed = false
    override var signIns: Int = 0
        private set

    suspend fun signInFresh(password: CharArray): LocalSessionView {
        check(!closed && tokens == null && !revocationUnconfirmed && original.isCurrent() && original.peer.isCurrent())
        signIns += 1
        val result = nativeFixtureSecret(password) {
            original.client.signIn(original.peer.connectionId, plan.employeeLogin, it)
        }
        val authenticated = result as? LocalAuthenticationResult.Authenticated
            ?: error("A genuine fresh founding-employee login is unavailable")
        // Own/destroy returned secrets even if the subsequent protected Current fails.
        tokens = authenticated.tokens
        val current = requireCurrent()
        val c = original.material.coordinates
        check(current.actorId == c.firstActorId && current.scope == c.scope && current.deviceId == c.deviceId &&
            current.login == plan.employeeLogin)
        requireNativeFixturePolicy(plan.setup.account(c).policy, current.policy)
        evidence.event("FRESH_FOUNDING_EMPLOYEE_AUTHENTICATED", JSONObject()
            .put("employeeId", current.actorId.value).put("deviceId", current.deviceId.value)
            .put("sessionId", current.sessionId.value).put("generation", current.generation.value)
            .put("policyRevision", current.policy.revision.value)
            .put("serviceIncarnationId", original.serviceIncarnationId.value))
        return current
    }

    suspend fun requireCurrent(): LocalSessionView {
        val actual = checkNotNull(tokens)
        check(!closed && !revocationUnconfirmed && original.isCurrent() && original.peer.isCurrent())
        val reply = original.client.current(credentials(actual)) as? LocalSessionViewResult.Current
            ?: error("The actual protected Current is unavailable")
        check(sameColdSession(actual.view, reply.view))
        return reply.view
    }

    /** Owned stale secret is used ONLY for a negative challenge, never for recovery. */
    fun copyAccessForNegativeChallenge(): OpaqueCredential = checkNotNull(tokens).access.useBytes(::OpaqueCredential)

    suspend fun requireStaleAccessDenied(stale: OpaqueCredential, saved: ColdSavedOriginal) {
        check(!closed && tokens == null && original.isCurrent())
        val credentials = LocalGatewayCredentials(original.peer.connectionId, stale)
        check(original.client.current(credentials) == LocalSessionViewResult.Denied) {
            "Stale access was not conclusively denied after the actual cold close"
        }
        val lookup = original.client.lookup(credentials, saved.identity) as? LookupReply.Deferred
            ?: error("A stale bearer disclosed an original result")
        check(lookup.reason == DeferredReason.AUTHORIZATION_DENIED && lookup.operationId == saved.request.operationId)
        evidence.event("STALE_BEARER_CURRENT_AND_ORIGINAL_LOOKUP_DENIED", JSONObject()
            .put("operationId", saved.request.operationId.value).put("privateResultReleased", false))
    }

    suspend fun read(request: ReadRequest): ReadReply.Data {
        val before = requireCurrent()
        val reply = original.client.read(credentials(checkNotNull(tokens)), request) as? ReadReply.Data
            ?: error("The required real protected projection is unavailable")
        check(sameColdSession(before, requireCurrent()))
        return reply
    }

    suspend fun dispatch(symbol: String, request: MutationRequest): MutationReply.Recorded {
        val current = requireCurrent()
        evidence.freezeRequest(symbol, request, current, original)
        evidence.markDispatch(symbol)
        val reply = original.client.execute(credentials(checkNotNull(tokens)), request)
        evidence.observeReply(symbol, reply)
        return requireFreshRecorded(request, current.actorId, reply)
    }

    /** Original Start body is durably retained before the actual call. Publication is adopted
     * immediately (no file/read/observer hop); protected C1 reads happen only AFTER adoption. */
    suspend fun startOriginal(request: MutationRequest): LocalPhysicalJournalRequirementV1 {
        check(lifetime == null && instruction == null && request.operationId == plan.startOperationId &&
            request.command == LocalFinancialJournalProfileV3.COLLECTION_COMMAND)
        val body = LocalCollectionCommandCodecV3.decode(request.payload) as? LocalCollectionRequestV3.Start
            ?: error("The original request is not genuine physical V3 Start data")
        check(LocalCollectionCommandCodecV3.encode(body) == request.payload)
        coldReadBoundary("start-current-session-entered")
        val current = requireCurrent()
        coldReadBoundary("start-current-session-returned")
        coldReadBoundary("start-original-input-retention-entered")
        evidence.freezeRequest("ORIGINAL_START", request, current, original)
        coldReadBoundary("start-original-input-retention-returned")
        val actualLifetime = actions.newRequestLifetime().also { lifetime = it }
        check(!actualLifetime.denied)
        evidence.markDispatch("ORIGINAL_START", "REAL_ENDPOINT_PHYSICAL_START")
        coldReadBoundary("real-original-start-entered")
        val started = actions.start(credentials(checkNotNull(tokens)), request, actualLifetime)
        coldReadBoundary("real-original-start-returned")
        evidence.observeReply("ORIGINAL_START", started.recordedOrDeferred)
        val recorded = requireFreshRecorded(request, current.actorId, started.recordedOrDeferred)
        val startedResult = LocalFinancialResultCodecV3.decode(recorded.result) as? LocalFinancialResultV3.AttemptStarted
            ?: error("Actual V3 Start did not produce an original cash attempt")
        val offer = checkNotNull(started.offer) { "The actual fresh Start supplied no instruction offer" }
        val published = actions.publishInstruction(credentials(checkNotNull(tokens)), offer, actualLifetime)
            as? PhysicalInstructionReply.Published ?: error("The real original instruction remained held")
        // Must be the exact gate and lifetime; neither projection decoding nor IDs can adopt.
        val adopted = gate.adoptPublished(published.instruction, actualLifetime)
            ?: error("The genuine publication could not be adopted by its actual native owner")
        instruction = adopted
        check(adopted.deliverable() && adopted.projection.kind == PhysicalRecordingStartKind.PAYMENT &&
            adopted.projection.originalOperationId == request.operationId &&
            adopted.projection.domainProfile == LocalFinancialJournalProfileV3.PAYMENT_PROFILE)
        val native = LocalPhysicalJournalRequirementV1.decode(adopted.projection.canonicalRequirement)
        LocalRetailPaymentJournalDomain.validateRequirement(native) // Pure check, never authority.
        check(native.canonicalBytes == adopted.projection.canonicalRequirement && native.scope == current.scope &&
            native.originalActorId == current.actorId && native.intentOperationId == request.operationId &&
            native.intentSemanticDigest.value == semanticDigest(current.scope, current.actorId, request.command, request.payload).value &&
            native.command == request.command && native.clientDeviceId == original.peer.deviceId &&
            native.masterDeviceId == original.material.coordinates.deviceId &&
            native.masterAllocationId == body.masterAllocationId && native.clientAllocationId == body.clientAllocationId &&
            native.recordingOperationId == body.recordingOperationId && native.resolutionOperationId == body.resolutionOperationId &&
            native.attemptId == body.attemptId && startedResult.target == expectedTarget())
        check(startedResult.amount == plan.payable && startedResult.requirementDigest == coldDigest(native.canonicalBytes).value)
        val certificate = checkNotNull(recorded.physicalStart)
        check(certificate.operationId == request.operationId && certificate.originalActorId == current.actorId &&
            certificate.scope == current.scope && certificate.command == request.command &&
            certificate.requirementBytes == native.canonicalBytes && certificate.authority.assignmentId == original.material.coordinates.assignmentId &&
            certificate.authority.deviceId == original.material.coordinates.deviceId &&
            certificate.authority.epoch == original.receipt.core.authorityEpoch)
        evidence.bind("ORIGINAL_NATIVE_REQUIREMENT", coldBase64(native.canonicalBytes))
        evidence.bind("ORIGINAL_NATIVE_REQUIREMENT_DIGEST", startedResult.requirementDigest)
        evidence.bind("ORIGINAL_START_CERTIFICATE", coldBase64(certificate.projection))
        evidence.event("GENUINE_NATIVE_CASH_PUBLICATION_ADOPTED", JSONObject()
            .put("originalOperationId", request.operationId.value).put("recordingOperationId", native.recordingOperationId.value)
            .put("originalActorId", native.originalActorId.value).put("originalDeviceId", native.clientDeviceId.value)
            .put("presentationId", "UNBOUND_PUBLIC_BACKEND_PROJECTION")
            .put("displayedProductionUi", false).put("instructionCountProjection", "UNBOUND"))
        return native
    }

    /** One real native capture, not a RecordOriginal execute or an accepting callback. */
    fun captureSuccessfulOriginal(requirement: LocalPhysicalJournalRequirementV1) {
        val actual = checkNotNull(instruction)
        check(!closed && actual.deliverable() && actual.projection.canonicalRequirement == requirement.canonicalBytes)
        val body = LocalCollectionCommandCodecV3.encode(LocalCollectionRequestV3.RecordOriginal(expectedTarget(), plan.paymentOutcome))
        val request = MutationRequest(plan.recordingOperationId, LocalFinancialJournalProfileV3.COLLECTION_COMMAND, body)
        evidence.freezeRequest("ORIGINAL_OUTCOME", request, checkNotNull(tokens).view, original)
        evidence.markDispatch("ORIGINAL_OUTCOME", "REAL_NATIVE_CAPTURE_THEN_INDEPENDENT_DURABLE_IMPORT")
        val result = actual.capturePayment(plan.paymentOutcome) as? LocalPhysicalOriginalCaptureResult.CapturedInMemory
            ?: error("Actual native original capture was not conclusively accepted into independent memory custody")
        check(result.operationId == plan.recordingOperationId && result.bodyDigest == coldDigest(body))
        check(checkNotNull(lifetime).denied && !actual.deliverable())
        evidence.bind("ORIGINAL_CAPTURE_BODY_DIGEST", result.bodyDigest.value)
        evidence.event("ORIGINAL_CAPTURE_ACCEPTED_IN_MEMORY_NOT_YET_C2", JSONObject()
            .put("recordingOperationId", result.operationId.value).put("durabilityClaimed", false))
    }

    /** The native capture worker imports the actual retained original; this method only reads.
     * A timeout/missing/uncertain projection holds C2, never fabricates application or absence. */
    suspend fun awaitDurableSuccessfulC2(): LocalFinancialReadResultV3.Fact = withTimeout(60_000) {
        while (true) {
            when (val fact = financialFact()) {
                is LocalFinancialReadResultV3.Fact -> {
                    requireOriginalFact(fact)
                    val expected = plan.checkpoint("C2-SUCCESS")
                    check(expected.getString("outcome") == "SUCCESS" && expected.getString("financial_application") == "PENDING" &&
                        plan.received.minor == expected.getString("observed_cash_received_minor").toLong() &&
                        plan.change.minor == expected.getString("observed_cash_change_minor").toLong() &&
                        plan.cashNet.minor == expected.getString("observed_cash_net_minor").toLong() &&
                        plan.received.minor - plan.change.minor == plan.cashNet.minor && plan.cashNet == plan.payable)
                    check(fact.summary.applicationState == LocalJournalApplicationStateV3.PENDING_RECONCILIATION &&
                        fact.summary.pendingReason == LocalJournalPendingReasonV3.AWAITING_FINANCIAL_APPLICATION &&
                        fact.summary.lastApplicationOperationId == null && fact.summary.appliedAction == null)
                    evidence.event("C2_SUCCESS_REAL_DURABLE_ORIGINAL_PENDING_APPLICATION", JSONObject()
                        .put("recordingOperationId", fact.summary.recordingOperationId.value)
                        .put("bodyDigest", fact.summary.bodyDigest).put("receivedMinor", plan.received.minor)
                        .put("changeMinor", plan.change.minor).put("financialApplication", "PENDING"))
                    return@withTimeout fact
                }
                LocalFinancialReadResultV3.NotAvailable -> delay(100)
                else -> error("The actual protected C2 Fact projection returned the wrong result kind")
            }
        }
        @Suppress("UNREACHABLE_CODE") error("Unreachable")
    }

    suspend fun financialFact(): LocalFinancialReadResultV3 = LocalFinancialReadCodecV3.decode(read(
        LocalFinancialReadCodecV3.request(LocalFinancialQueryV3.Fact(plan.recordingOperationId,
            LocalJournalFactPhaseV3.ORIGINAL))).payload)

    /** Observe the real native worker's already imported result; never execute RecordOriginal. */
    suspend fun observeDurableOriginalResult(fact: LocalFinancialReadResultV3.Fact) {
        requireOriginalFact(fact)
        val current = requireCurrent()
        val body = LocalCollectionCommandCodecV3.encode(LocalCollectionRequestV3.RecordOriginal(fact.summary.target,
            checkNotNull(fact.payment)))
        val identity = com.ukkera.pos.recovery.OperationIdentity(current.scope.branchId, fact.summary.recordingOperationId,
            fact.summary.originalActorId, 1, semanticDigest(current.scope, fact.summary.originalActorId,
                LocalFinancialJournalProfileV3.COLLECTION_COMMAND, body).value)
        val found = original.client.lookup(credentials(checkNotNull(tokens)), identity) as? LookupReply.Found
            ?: error("The genuine durable original fact has no recoverable imported result")
        check(found.result.committed && found.result.replayed && found.result.physicalStart == null &&
            found.result.operationId == plan.recordingOperationId && found.result.originalActorId == current.actorId)
        val result = LocalFinancialResultCodecV3.decode(found.result.result) as? LocalFinancialResultV3.Pending
            ?: error("The real original import did not preserve pending financial application")
        check(result.target == fact.summary.target && result.phase == LocalJournalFactPhaseV3.ORIGINAL &&
            result.reason == LocalJournalPendingReasonV3.AWAITING_FINANCIAL_APPLICATION)
        evidence.observeReply("ORIGINAL_OUTCOME", found.result)
        evidence.event("ORIGINAL_NATIVE_IMPORT_RESULT_OBSERVED_NOT_DISPATCHED", JSONObject()
            .put("operationId", found.result.operationId.value).put("sequence", found.result.sequence.value))
    }

    suspend fun recording(): LocalFinancialReadResultV3.AttemptRecording = LocalFinancialReadCodecV3.decode(read(
        LocalFinancialReadCodecV3.request(LocalFinancialQueryV3.AttemptRecording(SalesRecordingKind.PAYMENT_ATTESTATION,
            plan.collectionId, plan.cashSessionId, plan.attemptId, plan.startOperationId))).payload)
        as? LocalFinancialReadResultV3.AttemptRecording ?: error("Exact original attempt recording is unavailable")

    fun requireOriginalFact(fact: LocalFinancialReadResultV3.Fact) {
        val summary = fact.summary
        check(summary.target == expectedTarget() && summary.phase == LocalJournalFactPhaseV3.ORIGINAL &&
            summary.recordingOperationId == plan.recordingOperationId &&
            summary.originalActorId == original.material.coordinates.firstActorId &&
            summary.recordingActorId == original.material.coordinates.firstActorId && summary.originalBodyDigest == null &&
            summary.amount == plan.payable && summary.tender == LocalTenderViewV1("CASH") &&
            fact.payment == plan.paymentOutcome && fact.refund == null)
        val body = LocalCollectionCommandCodecV3.encode(LocalCollectionRequestV3.RecordOriginal(summary.target, checkNotNull(fact.payment)))
        check(summary.bodyDigest == coldDigest(body).value)
    }

    suspend fun recoverExact(saved: ColdSavedOriginal): MutationReply.Recorded {
        val current = requireCurrent()
        val c = original.material.coordinates
        check(saved.identity.originalActorId == current.actorId && saved.scope == current.scope && saved.deviceId == current.deviceId &&
            saved.assignmentId == c.assignmentId && saved.installationId == c.installationId &&
            saved.storageInstanceId == c.storageInstanceId && saved.authorityEpoch == original.receipt.core.authorityEpoch)
        check(saved.identity.semanticSha256 == semanticDigest(saved.scope, saved.identity.originalActorId,
            saved.request.command, saved.request.payload).value)
        val found = original.client.lookup(credentials(checkNotNull(tokens)), saved.identity) as? LookupReply.Found
            ?: error("Actual current-authorized exact original recovery is unavailable")
        saved.result.requireSame(found.result)
        check(found.result.replayed && found.result.physicalStart == null) {
            "Original lookup must not supply a replacement physical Start certificate"
        }
        check(sameColdSession(current, requireCurrent()))
        evidence.event("CURRENT_AUTHORIZED_EXACT_ORIGINAL_LOOKUP", JSONObject()
            .put("symbol", saved.symbol).put("operationId", saved.request.operationId.value)
            .put("originalActorId", found.result.originalActorId.value)
            .put("authorityEpoch", found.result.authorityEpoch.value).put("sequence", found.result.sequence.value)
            .put("newPhysicalStartCertificate", false))
        return found.result
    }

    private fun expectedTarget() = LocalJournalTargetV3(SalesRecordingKind.PAYMENT_ATTESTATION,
        plan.collectionId, plan.cashSessionId, plan.attemptId, plan.startOperationId,
        plan.recordingOperationId, plan.resolutionOperationId)

    private fun requireFreshRecorded(request: MutationRequest, actor: DomainId, reply: MutationReply): MutationReply.Recorded {
        val recorded = reply as? MutationReply.Recorded ?: error("The actual original mutation was not durably acknowledged")
        check(recorded.operationId == request.operationId && recorded.originalActorId == actor &&
            recorded.committed && !recorded.replayed && recorded.authorityEpoch == original.receipt.core.authorityEpoch)
        return recorded
    }
    private fun credentials(tokens: LocalSessionTokens) = LocalGatewayCredentials(original.peer.connectionId, tokens.access)

    /** Irreversible lifetime denial first; then actual logout/token destruction/journal close.
     * The shared owner subsequently requires real master quiesce/database/native closure. */
    override suspend fun close(): Unit = withContext(NonCancellable) {
        if (closed) return@withContext
        instruction?.deny()
        lifetime?.deny()
        var failure: Throwable? = null
        suspend fun attempt(block: suspend () -> Unit) {
            try { block() } catch (caught: Throwable) {
                if (failure == null) failure = caught
                else if (failure !== caught) checkNotNull(failure).addSuppressed(caught)
            }
        }
        val actual = tokens
        if (actual != null) attempt {
            revocationUnconfirmed = true
            try {
                check(original.client.logout(credentials(actual)) == LocalSignOutResult.Revoked) {
                    "Actual local session revocation is unconfirmed"
                }
                revocationUnconfirmed = false
            } finally { actual.destroy(); tokens = null }
        }
        attempt { journals.close() }
        failure?.let { throw it }
        check(!revocationUnconfirmed)
        instruction = null; lifetime = null; closed = true
    }
    override fun toString() = "NativeColdOriginalClient([REDACTED])"
}

private fun sameColdSession(a: LocalSessionView, b: LocalSessionView) =
    a.scope == b.scope && a.actorId == b.actorId && a.deviceId == b.deviceId && a.sessionId == b.sessionId &&
        a.generation == b.generation && a.login == b.login && a.policy.revision == b.policy.revision &&
        a.accessExpiresAt == b.accessExpiresAt && a.workExpiresAt == b.workExpiresAt
