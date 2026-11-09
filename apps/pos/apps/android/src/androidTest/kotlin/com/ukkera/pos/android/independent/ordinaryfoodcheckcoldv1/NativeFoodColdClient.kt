package com.ukkera.pos.android.independent.ordinaryfoodcheckcoldv1

import com.ukkera.pos.android.independent.nativefixture.*
import com.ukkera.pos.app.gateway.AndroidOriginalClientJournals
import com.ukkera.pos.database.*
import com.ukkera.pos.security.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Uses the exact acquired native protected endpoint; no endpoint/peer/authority/SPIs
 * are implemented or wrapped. Staff own separate current sessions on one admitted device. */
internal class NativeFoodColdClient(
    override val original: AndroidOriginalLocalClient,
    private val journals: AndroidOriginalClientJournals,
    val publications: LocalCompanyBusinessPublications?,
    private val plan: NativeFoodColdInputPlan,
    private val evidence: NativeFoodColdEvidence,
) : NativeLocalFixtureClient {
    private val sessions = linkedMapOf<FoodColdActor, LocalSessionTokens>()
    private var retired = false
    private var revocationUnconfirmed = false
    override var signIns = 0
        private set

    suspend fun signIn(actor: FoodColdActor, secret: CharArray): LocalSessionView {
        check(!retired && !revocationUnconfirmed && actor !in sessions && original.isCurrent() && original.peer.isCurrent())
        val reply = nativeFixtureSecret(secret) { original.client.signIn(original.peer.connectionId, plan.login(actor), it) }
        val tokens = (reply as? LocalAuthenticationResult.Authenticated)?.tokens
            ?: error("The actual separate ${actor.name} authentication was unavailable")
        sessions[actor] = tokens // Own returned secrets before any subsequent association assertion.
        signIns += 1
        val current = current(actor)
        evidence.event("FRESH_${actor.name}_AUTHENTICATION", JSONObject().put("actorId", current.actorId.value)
            .put("sessionId", current.sessionId.value).put("deviceId", current.deviceId.value)
            .put("policyRevision", current.policy.revision.value).put("serviceIncarnationId", original.serviceIncarnationId.value))
        return current
    }

    suspend fun current(actor: FoodColdActor): LocalSessionView {
        check(!retired && !revocationUnconfirmed && original.isCurrent() && original.peer.isCurrent())
        val owned = checkNotNull(sessions[actor])
        val view = (original.client.current(credentials(actor)) as? LocalSessionViewResult.Current)?.view
            ?: error("The real protected Current association for ${actor.name} is unavailable")
        val c = original.material.coordinates
        check(view.actorId == plan.actorId(actor, c) && view.scope == c.scope && view.deviceId == c.deviceId &&
            view.deviceId == original.peer.deviceId && view.login == plan.login(actor) &&
            view.sessionId == owned.view.sessionId && view.generation == owned.view.generation &&
            view.accessExpiresAt == owned.view.accessExpiresAt && view.workExpiresAt == owned.view.workExpiresAt)
        requireNativeFixturePolicy(plan.account(actor, c).policy, view.policy)
        return view // Observation only; execute/read independently reauthorize on the Master.
    }

    suspend fun read(actor: FoodColdActor, request: ReadRequest): ReadReply.Data {
        val before = current(actor)
        check(request.query in original.readKeys) { "Required production read profile is not installed" }
        val reply = original.client.read(credentials(actor), request) as? ReadReply.Data
            ?: error("The required protected native read is unavailable; no empty/legacy fallback")
        check(current(actor).sessionId == before.sessionId)
        return reply
    }

    suspend fun dispatch(actor: FoodColdActor, symbol: String, request: MutationRequest): MutationReply.Recorded {
        val before = current(actor)
        check(request.command in original.commandKeys)
        evidence.retainRequest(symbol, actor, request, before, original)
        evidence.dispatching(symbol)
        val reply = original.client.execute(credentials(actor), request)
        evidence.observeReply(symbol, reply)
        val result = reply as? MutationReply.Recorded ?: error("A genuine original $symbol did not settle")
        check(result.committed && !result.replayed && result.operationId == request.operationId &&
            result.originalActorId == before.actorId && result.authorityEpoch == plan.authorityEpoch && result.physicalStart == null)
        check(current(actor).sessionId == before.sessionId)
        return result
    }

    suspend fun recover(saved: FoodColdSavedOriginal) {
        val before = current(saved.actor)
        val c = original.material.coordinates
        check(saved.scope == before.scope && saved.identity.originalActorId == before.actorId && saved.deviceId == before.deviceId &&
            saved.installationId == c.installationId && saved.assignmentId == c.assignmentId &&
            saved.storageInstanceId == c.storageInstanceId && saved.result.epoch == original.receipt.core.authorityEpoch)
        val found = original.client.lookup(credentials(saved.actor), saved.identity) as? LookupReply.Found
            ?: error("Current-authorized exact original lookup is unavailable")
        saved.result.requireSame(found.result)
        check(found.result.replayed && current(saved.actor).sessionId == before.sessionId)
        evidence.event("EXACT_ORIGINAL_PROTECTED_LOOKUP", JSONObject().put("symbol", saved.symbol)
            .put("operationId", saved.request.operationId.value).put("actorId", saved.identity.originalActorId.value)
            .put("originalRecordedAt", found.result.recordedAt.canonicalString()))
    }

    suspend fun enroll(actor: FoodColdActor, password: CharArray, headSecret: CharArray) {
        check(actor != FoodColdActor.OWNER && sessions.isEmpty() && !retired && original.isCurrent())
        val requested = plan.account(actor, original.material.coordinates)
        val id = if (actor == FoodColdActor.ADMINISTRATOR) plan.administratorEnrollmentId else plan.kdsEnrollmentId
        evidence.bind("${actor.name}_FULL_ENROLLMENT_INPUT", JSONObject().put("requestId", id.value)
            .put("definition", foodColdAccount(requested)).put("credentialWasProvided", true))
        val action = nativeFixtureValue(original.prepareEmployeeEnrollment(id, requested, credentialWasProvided = true))
        val connection = nativeFixtureSecret(headSecret) {
            nativeFixtureValue(original.approveEmployeeEnrollment(action, plan.headLogin, it))
        }
        check(connection.belongsTo(original) && connection.isCurrent() && !connection.isLookupOnly &&
            connection.actorId == requested.actorId && connection.originalRequestId == id)
        val definition = checkNotNull(connection.originalDefinition(id, requested.login))
        check(definition.actorId == requested.actorId && definition.login == requested.login &&
            definition.admittedDeviceIds == requested.admittedDeviceIds && definition.tokenPolicy == requested.tokenPolicy)
        requireNativeFixturePolicy(requested.policy, definition.policy)
        val result = nativeFixtureSecret(password) { connection.provisionAccount(id, requested, it) }
            as? LocalCredentialAdministrationResult.Applied ?: error("Genuine ${actor.name} provisioning was not applied")
        val originalResult = connection.lookupAccountProvisioning(id, requested, true) as? LocalProvisioningStatus.Applied
            ?: error("Original staff provisioning could not be reobserved")
        check(originalResult.eventId == result.eventId)
        evidence.bind("${actor.name}_NATIVE_ENROLLMENT", JSONObject().put("originalActionId", connection.originalActionId.value)
            .put("originalRequestId", id.value).put("actualProvisioningEventId", result.eventId.value))
    }

    private fun credentials(actor: FoodColdActor) = LocalGatewayCredentials(original.peer.connectionId,
        checkNotNull(sessions[actor]).access)

    override suspend fun close(): Unit = withContext(NonCancellable) {
        var failure: Throwable? = null
        fun remember(next: Throwable) {
            if (failure == null) failure = next else if (failure !== next) checkNotNull(failure).addSuppressed(next)
        }
        // Stop new fixture calls first; do not let one failed logout skip other owned tokens.
        retired = true
        sessions.toMap().forEach { (actor, tokens) ->
            try {
                check(original.client.logout(LocalGatewayCredentials(original.peer.connectionId, tokens.access)) == LocalSignOutResult.Revoked)
                evidence.event("ACTUAL_${actor.name}_LOGOUT_REVOKED")
            } catch (caught: Throwable) { revocationUnconfirmed = true; remember(caught) }
            finally {
                try { tokens.destroy() } catch (caught: Throwable) { remember(caught) }
                sessions.remove(actor)
            }
        }
        try { journals.close() } catch (caught: Throwable) { remember(caught) }
        if (revocationUnconfirmed && failure == null) remember(IllegalStateException("Actual staff logout is unconfirmed"))
        failure?.let { throw it }
    }
}

/** Complete requested account DATA, not a native credential/policy-image encoder. */
internal fun foodColdAccount(account: LocalAccountProvisioning): JSONObject {
    val policy = account.policy
    check(policy.approvalPolicy == null) { "The fixed seed does not request a financial approval policy" }
    fun limits(value: PermissionLimits) = JSONObject()
        .put("maximumAmount", value.maximumAmount?.let { JSONObject().put("currency", it.currency.name).put("minor", it.minor) }
            ?: JSONObject.NULL)
        .put("maximumPercentageBasisPoints", value.maximumPercentageBasisPoints ?: JSONObject.NULL)
        .put("noMonetaryCeilingCurrency", value.noMonetaryCeiling?.currency?.name ?: JSONObject.NULL)
    fun scope(value: TenantScope) = JSONObject().put("organizationId", value.organizationId.value)
        .put("companyId", value.companyId.value).put("branchId", (value as? BranchScope)?.branchId?.value ?: JSONObject.NULL)
    return JSONObject().put("actorId", account.actorId.value).put("login", account.login.value)
        .put("tokenPolicy", JSONObject().put("accessLifetimeMillis", account.tokenPolicy.accessLifetimeMillis)
            .put("workLifetimeMillis", account.tokenPolicy.workLifetimeMillis))
        .put("admittedDeviceIds", org.json.JSONArray(account.admittedDeviceIds.map { it.value }.sorted()))
        .put("policy", JSONObject().put("scope", scope(policy.scope)).put("actorId", policy.actorId.value)
            .put("revision", policy.revision.value).put("sessionPolicy", JSONObject()
                .put("maximumConcurrentSessions", policy.sessionPolicy.maximumConcurrentSessions))
            .put("throttlePolicy", JSONObject().put("freeFailures", policy.throttlePolicy.freeFailures)
                .put("initialDelayMillis", policy.throttlePolicy.initialDelayMillis)
                .put("maximumDelayMillis", policy.throttlePolicy.maximumDelayMillis)
                .put("attemptTimeoutMillis", policy.throttlePolicy.attemptTimeoutMillis))
            .put("approvalPolicy", JSONObject.NULL)
            .put("grants", org.json.JSONArray(policy.grants.map { grant -> JSONObject()
                .put("action", grant.permission.action.name).put("verb", grant.permission.verb.name)
                .put("scope", scope(grant.scope)).put("effect", grant.effect.name)
                .put("namedSubjectId", grant.namedSubjectId?.value ?: JSONObject.NULL)
                .put("ownerAccess", grant.ownerAccess.name)
                .put("targetIds", grant.targetIds?.let { ids -> org.json.JSONArray(ids.map { it.value }.sorted()) } ?: JSONObject.NULL)
                .put("limits", limits(grant.limits))
                .put("delegationCeiling", grant.delegationCeiling?.let(::limits) ?: JSONObject.NULL) })))
}
