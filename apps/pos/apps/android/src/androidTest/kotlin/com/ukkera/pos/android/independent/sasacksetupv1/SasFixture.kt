package com.ukkera.pos.android.independent.sasacksetupv1

import android.app.Application
import com.ukkera.pos.android.independent.aosfsetupv2.AosfAnchor
import com.ukkera.pos.android.independent.nativefixture.NativeLocalFixtureClient
import com.ukkera.pos.android.independent.nativefixture.NativeLocalFixtureOwner
import com.ukkera.pos.android.independent.nativefixture.nativeFixtureValue
import com.ukkera.pos.database.AndroidLocalEmployeeEnrollmentConnection
import com.ukkera.pos.app.gateway.AndroidOriginalClientJournals
import com.ukkera.pos.database.AndroidOriginalLocalClient
import com.ukkera.pos.database.LocalMasterEndpointHealth
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.setup.LocalSetupResultCodecV1
import com.ukkera.pos.database.setup.LocalSetupResultProjectionV1
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.LocalAccountProvisioning
import com.ukkera.pos.security.LocalAuthenticationResult
import com.ukkera.pos.security.LocalCredentialAdministrationResult
import com.ukkera.pos.security.LocalCompanyBusinessPublications
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.LocalProvisioningStatus
import com.ukkera.pos.security.LocalSessionTokens
import com.ukkera.pos.security.LocalSessionView
import com.ukkera.pos.security.LocalSessionViewResult
import com.ukkera.pos.security.LocalSignOutResult
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The exact production native client for one selected SAS case. Sessions are real native
 * sign-ins over the acquired original peer. Commands and reads are ordinary master requests;
 * this class supplies no actor, grant, readiness, time or result.
 */
internal class SasClient(
    override val original: AndroidOriginalLocalClient,
    private val journals: AndroidOriginalClientJournals,
    val publications: LocalCompanyBusinessPublications?,
    private val plan: SasFixturePlan,
    private val evidence: SasEvidence,
) : NativeLocalFixtureClient {
    private val sessions = linkedMapOf<SasActor, LocalSessionTokens>()
    private val revoked = mutableSetOf<SasActor>()
    override var signIns = 0
        private set
    private var closed = false
    private var revocationUnconfirmed = false

    val scope get() = original.material.coordinates.scope

    fun credentials(actor: SasActor): LocalGatewayCredentials =
        LocalGatewayCredentials(original.peer.connectionId, checkNotNull(sessions[actor]).access)

    fun session(actor: SasActor): LocalSessionTokens = checkNotNull(sessions[actor])

    suspend fun signIn(actor: SasActor, password: CharArray): LocalSessionView {
        check(!closed && !revocationUnconfirmed && original.isCurrent() && original.peer.isCurrent())
        check(actor !in sessions) { "A selected case does not silently replace an existing session" }
        val copy = password.copyOf()
        val reply = try {
            original.client.signIn(original.peer.connectionId, plan.login(actor), copy)
        } finally { copy.fill('\u0000') }
        val tokens = (reply as? LocalAuthenticationResult.Authenticated)?.tokens
            ?: error("Actual native sign-in as ${actor.name} was not authenticated: $reply")
        sessions[actor] = tokens
        signIns += 1
        val view = requireCurrent(actor)
        val coordinates = original.material.coordinates
        check(view.scope == coordinates.scope && view.actorId == plan.actorId(actor, coordinates) &&
            view.deviceId == coordinates.deviceId && view.login == plan.login(actor)) {
            "The actual session is not bound to the requested synthetic participant"
        }
        evidence.event("FRESH_NATIVE_SESSION", JSONObject().put("actor", actor.name)
            .put("actorId", view.actorId.value).put("sessionId", view.sessionId.value)
            .put("generation", view.generation.value).put("policyRevision", view.policy.revision.value)
            .put("serviceIncarnationId", original.serviceIncarnationId.value))
        return view
    }

    suspend fun requireCurrent(actor: SasActor): LocalSessionView {
        check(!closed && !revocationUnconfirmed && original.isCurrent() && original.peer.isCurrent())
        val view = (original.client.current(credentials(actor)) as? LocalSessionViewResult.Current)?.view
            ?: error("The actual current session for ${actor.name} is unavailable")
        check(view.actorId == plan.actorId(actor, original.material.coordinates))
        return view
    }

    suspend fun currentOrDenied(actor: SasActor): LocalSessionViewResult {
        check(!closed && !revocationUnconfirmed)
        return original.client.current(credentials(actor))
    }

    suspend fun execute(actor: SasActor, request: MutationRequest): MutationReply {
        check(!closed && !revocationUnconfirmed)
        check(request.command in original.commandKeys) { "The production master did not register the setup command" }
        val before = requireCurrent(actor)
        evidence.event("COMMAND_DISPATCH", JSONObject().put("actor", actor.name)
            .put("operationId", request.operationId.value).put("command", request.command.name))
        val reply = original.client.execute(credentials(actor), request)
        val after = requireCurrent(actor)
        check(sameSession(before, after)) { "A setup command silently replaced the current employee session" }
        return reply
    }

    /** Deliberate revoked/stale-custody probes only; the caller owns the retained credentials. */
    suspend fun executeWith(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        check(!closed && !revocationUnconfirmed)
        check(request.command in original.commandKeys)
        return original.client.execute(credentials, request)
    }

    suspend fun read(actor: SasActor, request: ReadRequest): ReadReply {
        check(!closed && !revocationUnconfirmed)
        check(request.query in original.readKeys) { "The production master did not register the setup read" }
        val before = requireCurrent(actor)
        val reply = original.client.read(credentials(actor), request)
        val after = requireCurrent(actor)
        check(sameSession(before, after)) { "A setup read silently replaced the current employee session" }
        return reply
    }

    suspend fun projection(actor: SasActor, installationId: DomainId): LocalSetupResultProjectionV1 {
        val reply = read(actor, LocalSetupResultCodecV1.read(installationId))
        val data = reply as? ReadReply.Data
            ?: error("The authorized saved-setup read was not disclosed: $reply")
        return LocalSetupResultCodecV1.decodeProjection(data.payload)
    }

    suspend fun logout(actor: SasActor) {
        val actual = checkNotNull(sessions[actor])
        val result = original.client.logout(credentials(actor))
        if (result != LocalSignOutResult.Revoked) {
            revocationUnconfirmed = true
            error("Actual native logout for ${actor.name} was not revoked: $result")
        }
        revoked += actor
        actual.destroy()
        sessions.remove(actor)
    }

    /** Revoke confirmation but retain the exact bearer solely for the case's stale-authority probe. */
    suspend fun revokeRetain(actor: SasActor): LocalGatewayCredentials {
        val result = original.client.logout(credentials(actor))
        check(result == LocalSignOutResult.Revoked) { "The actual native session was not revoked: $result" }
        revoked += actor
        evidence.event("SESSION_REVOKED_RETAINED_FOR_STALE_PROBE", JSONObject().put("actor", actor.name))
        return credentials(actor)
    }

    fun destroyRetained(actor: SasActor) {
        revoked.remove(actor)
        sessions.remove(actor)?.destroy()
    }

    override suspend fun close(): Unit = withContext(NonCancellable) {
        if (closed) return@withContext
        var failure: Throwable? = null
        for ((actor, tokens) in sessions.toMap()) {
            if (actor in revoked) {
                tokens.destroy()
                sessions.remove(actor)
                continue
            }
            try {
                val result = original.client.logout(LocalGatewayCredentials(original.peer.connectionId, tokens.access))
                if (result != LocalSignOutResult.Revoked) {
                    revocationUnconfirmed = true
                    failure = failure ?: IllegalStateException("Actual logout for ${actor.name} was not revoked: $result")
                }
            } catch (caught: Throwable) {
                revocationUnconfirmed = true
                if (failure == null) failure = caught else if (failure !== caught) failure.addSuppressed(caught)
            } finally {
                tokens.destroy()
                sessions.remove(actor)
                revoked.remove(actor)
            }
        }
        try { journals.close() } catch (caught: Throwable) {
            if (failure == null) failure = caught else if (failure !== caught) failure.addSuppressed(caught)
        }
        closed = true
        failure?.let { throw it }
    }

    override fun toString(): String = "SasClient([REDACTED])"
}

internal fun sameSession(a: LocalSessionView, b: LocalSessionView): Boolean =
    a.scope == b.scope && a.actorId == b.actorId && a.deviceId == b.deviceId &&
        a.sessionId == b.sessionId && a.generation == b.generation && a.login == b.login &&
        a.policy.revision == b.policy.revision && a.accessExpiresAt == b.accessExpiresAt &&
        a.workExpiresAt == b.workExpiresAt

/** Immutable anchor from the actual native installation receipt, never from requested data. */
internal data class SasAnchor(
    val organizationId: String,
    val companyId: String,
    val branchId: String,
    val installationId: DomainId,
    val assignmentId: DomainId,
    val deviceId: DomainId,
    val firstActorId: DomainId,
    val originalRequestId: DomainId,
    val issuedAttemptId: DomainId,
    val attemptSequence: Long,
    val definitionDigest: String,
    val genesisDigest: String,
    val receiptDigest: String,
    val authorityEpoch: Long,
    val serviceIncarnationId: String,
) {
    fun retained(): JSONObject = JSONObject()
        .put("organizationId", organizationId).put("companyId", companyId).put("branchId", branchId)
        .put("installationId", installationId.value).put("assignmentId", assignmentId.value)
        .put("deviceId", deviceId.value).put("firstActorId", firstActorId.value)
        .put("originalRequestId", originalRequestId.value).put("issuedAttemptId", issuedAttemptId.value)
        .put("definitionDigest", definitionDigest).put("genesisDigest", genesisDigest)
        .put("receiptDigest", receiptDigest).put("authorityEpoch", authorityEpoch)
        .put("serviceIncarnationId", serviceIncarnationId)

    fun requireProjection(projection: LocalSetupResultProjectionV1) {
        val original = projection.original
        check(original.organizationId.value == organizationId && original.companyId.value == companyId &&
            original.branchId.value == branchId && original.installationId == installationId &&
            original.assignmentId == assignmentId && original.deviceId == deviceId &&
            original.firstActorId == firstActorId && original.originalRequestId == originalRequestId &&
            original.issuedAttemptId == issuedAttemptId && original.definitionDigest.value == definitionDigest &&
            original.genesisDigest.value == genesisDigest && original.receiptDigest.value == receiptDigest &&
            original.installationAuthorityEpoch.value == authorityEpoch &&
            original.currentAuthorityEpoch.value == authorityEpoch) {
            "The protected saved-setup projection does not identify the actual original installation"
        }
    }

    fun requireAcknowledgement(projection: LocalSetupResultProjectionV1, actorId: DomainId,
        operationId: OperationId) {
        val acknowledgement = checkNotNull(projection.acknowledgement) {
            "The protected saved-setup projection did not disclose the committed acknowledgement"
        }
        check(acknowledgement.installationId == installationId &&
            acknowledgement.originalRequestId == originalRequestId &&
            acknowledgement.issuedAttemptId == issuedAttemptId &&
            acknowledgement.definitionDigest.value == definitionDigest &&
            acknowledgement.genesisDigest.value == genesisDigest &&
            acknowledgement.actorId == actorId &&
            acknowledgement.operationId == operationId &&
            acknowledgement.masterAuthorityEpoch.value == authorityEpoch) {
            "The saved acknowledgement is not bound to the original/actor/operation/epoch"
        }
    }

    fun requireLive(projection: LocalSetupResultProjectionV1, actorId: DomainId,
        operationId: OperationId) {
        val live = checkNotNull(projection.goLive) {
            "The protected saved-setup projection did not disclose the committed go-live"
        }
        check(live.installationId == installationId && live.originalRequestId == originalRequestId &&
            live.issuedAttemptId == issuedAttemptId && live.definitionDigest.value == definitionDigest &&
            live.genesisDigest.value == genesisDigest && live.actorId == actorId &&
            live.operationId == operationId && live.masterAuthorityEpoch.value == authorityEpoch &&
            live.prerequisites.all { it.settled }) {
            "The saved go-live is not bound to the original/actor/operation/epoch or is unsettled"
        }
    }

    companion object {
        fun bind(original: AndroidOriginalLocalClient): SasAnchor {
            val coordinates = original.material.coordinates
            val receipt = original.receipt
            val installed = receipt.core.allocation.coordinates
            check(coordinates.scope == installed.scope && coordinates.installationId == installed.installationId &&
                coordinates.assignmentId == installed.assignmentId && coordinates.deviceId == installed.deviceId &&
                coordinates.firstActorId == installed.firstActorId) {
                "The saved receipt and native material do not identify one installation"
            }
            check(original.material.originalNativeBindingDigest.isNotEmpty())
            return SasAnchor(
                organizationId = coordinates.scope.organizationId.value,
                companyId = coordinates.scope.companyId.value,
                branchId = coordinates.scope.branchId.value,
                installationId = coordinates.installationId,
                assignmentId = coordinates.assignmentId,
                deviceId = coordinates.deviceId,
                firstActorId = coordinates.firstActorId,
                originalRequestId = receipt.core.originalRequestId,
                issuedAttemptId = receipt.core.issuedAttemptId,
                attemptSequence = receipt.core.attemptSequence,
                definitionDigest = receipt.core.definitionDigest.value,
                genesisDigest = receipt.genesis.value,
                receiptDigest = receipt.digest.value,
                authorityEpoch = receipt.core.authorityEpoch.value,
                serviceIncarnationId = original.serviceIncarnationId.value,
            )
        }
    }
}

/**
 * The real signed-policy provisioning and selected-case driver. Enrolment uses the actual
 * company-head ceremony; policy activation uses the real native approval and sole-writer
 * activation. The fixture cannot supply an actor, grant, readiness or current time.
 */
internal class SasFixture(
    private val application: Application,
    private val owner: NativeLocalFixtureOwner<SasClient>,
    private var currentClient: SasClient,
    val plan: SasFixturePlan,
    val secrets: SasSecrets,
    val evidence: SasEvidence,
) {
    val anchor: SasAnchor = SasAnchor.bind(currentClient.original)
    var baseCut: SasBaseCut? = null
        internal set
    val coordinates get() = currentClient.original.material.coordinates
    val original get() = currentClient.original
    val client: SasClient get() = currentClient

    fun requireOriginalStillCurrent() {
        check(original.isCurrent() && original.peer.isCurrent()) { "The actual original master is no longer current" }
    }

    suspend fun requirePolicy(actor: SasActor, expected: ActivatedLocalPolicy) {
        val actual = currentClient.requireCurrent(actor).policy
        com.ukkera.pos.android.independent.nativefixture.requireNativeFixturePolicy(expected, actual)
    }

    suspend fun enroll(actor: SasActor) {
        check(actor != SasActor.US)
        val requestId = DomainId.random()
        val requested = plan.account(actor, coordinates)
        evidence.event("ENROLL_${actor.name}", JSONObject().put("requestId", requestId.value)
            .put("actorId", requested.actorId.value).put("login", requested.login.value))
        val action = nativeFixtureValue(original.prepareEmployeeEnrollment(requestId, requested,
            credentialWasProvided = true))
        val connection = withSasSecret(secrets.head) {
            nativeFixtureValue(original.approveEmployeeEnrollment(action, plan.headLogin, it))
        }
        requireConnection(connection, requested, requestId)
        val applied = withSasSecret(secrets[actor]) {
            connection.provisionAccount(requestId, requested, it)
        } as? LocalCredentialAdministrationResult.Applied
            ?: error("The actual synthetic participant account was not provisioned")
        val retained = connection.lookupAccountProvisioning(requestId, requested,
            credentialWasProvided = true) as? LocalProvisioningStatus.Applied
            ?: error("The actual participant provisioning cannot be reobserved")
        check(applied.eventId == retained.eventId)
        evidence.event("GENUINE_PARTICIPANT_ENROLLED", JSONObject().put("actor", actor.name)
            .put("actorId", requested.actorId.value).put("originalActionId", connection.originalActionId.value)
            .put("provisioningEventId", applied.eventId.value))
    }

    suspend fun activate(actor: SasActor, revision: Revision, actions: Set<SecurityAction>) {
        activatePolicy(actor, plan.policy(actor, coordinates, revision, actions), revision)
    }

    suspend fun activatePolicy(actor: SasActor, next: ActivatedLocalPolicy, revision: Revision) {
        val requestId = DomainId.random()
        evidence.event("ACTIVATE_${actor.name}_${revision.value}", JSONObject()
            .put("requestId", requestId.value).put("policyRevision", revision.value)
            .put("actions", next.grants.map { it.permission.action.name }.distinct().sorted().joinToString(",")))
        val action = nativeFixtureValue(original.prepareEmployeePolicy(requestId, next))
        val approval = withSasSecret(secrets.head) {
            nativeFixtureValue(original.approveEmployeePolicy(action, plan.headLogin, it))
        }
        check(approval.originalActionId == action.originalActionId &&
            approval.requested.request.requestId == requestId &&
            approval.requested.request.targetId == next.actorId) {
            "The actual signed policy approval is not bound to the requested participant"
        }
        val applied = original.activateEmployeePolicy(approval, next) as? LocalCredentialAdministrationResult.Applied
            ?: error("The actual next-revision policy activation did not settle")
        val retained = original.lookupEmployeePolicy(approval, next) as? LocalProvisioningStatus.Applied
            ?: error("The actual activated policy cannot be reobserved")
        check(retained.eventId == applied.eventId)
        evidence.event("SIGNED_POLICY_ACTIVATED", JSONObject().put("actor", actor.name)
            .put("actorId", next.actorId.value).put("policyRevision", revision.value)
            .put("originalActionId", approval.originalActionId.value)
            .put("activationEventId", applied.eventId.value)
            .put("policyFingerprint", AosfAnchor.policyFingerprint(next)))
    }

    suspend fun signIn(actor: SasActor): LocalSessionView = currentClient.signIn(actor, secrets[actor])

    suspend fun current(actor: SasActor): LocalSessionView = currentClient.requireCurrent(actor)

    suspend fun ack(actor: SasActor, operationId: OperationId, installationId: DomainId = anchor.installationId) =
        currentClient.execute(actor, LocalSetupResultCodecV1.acknowledge(operationId, installationId))

    suspend fun goLive(actor: SasActor, operationId: OperationId, installationId: DomainId = anchor.installationId) =
        currentClient.execute(actor, LocalSetupResultCodecV1.goLive(operationId, installationId))

    suspend fun read(actor: SasActor, installationId: DomainId = anchor.installationId): LocalSetupResultProjectionV1 =
        currentClient.projection(actor, installationId)

    /** Return independent persisted facts after a confirmed owner close (actual restart). */
    suspend fun cut(): SasPersistedFacts {
        currentClient.close()
        owner.closeOwned()
        return readSasPersistedFacts(application)
    }

    suspend fun reopen() {
        val next = owner.openClient()
        val rebound = SasAnchor.bind(next.original)
        check(rebound.copy(serviceIncarnationId = anchor.serviceIncarnationId) == anchor) {
            "The reopened runtime changed the saved original settlement anchor"
        }
        currentClient = next
        evidence.event("RUNTIME_OWNER_REACQUIRED", JSONObject()
            .put("serviceIncarnationId", next.original.serviceIncarnationId.value)
            .put("sameOriginalAnchor", true))
    }

    suspend fun restart() {
        cut()
        reopen()
    }

    suspend fun close() {
        currentClient.close()
        owner.closeOwned()
    }

    private suspend fun requireConnection(connection: AndroidLocalEmployeeEnrollmentConnection,
        requested: LocalAccountProvisioning, requestId: DomainId) {
        check(connection.belongsTo(original) && connection.actorId == requested.actorId &&
            connection.originalRequestId == requestId && !connection.isLookupOnly) {
            "The actual approved participant connection is not the requested original"
        }
        val definition = checkNotNull(connection.originalDefinition(requestId, requested.login))
        check(definition.actorId == requested.actorId && definition.login == requested.login &&
            definition.admittedDeviceIds == requested.admittedDeviceIds &&
            definition.tokenPolicy == requested.tokenPolicy)
        com.ukkera.pos.android.independent.nativefixture.requireNativeFixturePolicy(requested.policy,
            definition.policy)
    }
}
