package com.ukkera.pos.android.independent.aosfsetupv2

import com.ukkera.pos.android.independent.nativefixture.nativeFixtureValue
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.database.setup.LocalSetupAcknowledgementRecordV1
import com.ukkera.pos.database.setup.LocalSetupGoLiveRecordV1
import com.ukkera.pos.database.setup.LocalSetupResultCodecV1
import com.ukkera.pos.database.setup.LocalSetupResultProjectionV1
import com.ukkera.pos.database.setup.LocalSetupResultStatusV1
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.LocalAccountProvisioning
import com.ukkera.pos.security.LocalCredentialAdministrationResult
import com.ukkera.pos.security.LocalProvisioningStatus
import com.ukkera.pos.security.LocalSessionTokens
import com.ukkera.pos.security.LocalSessionView
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import org.json.JSONObject

/**
 * Explicit setup-result authority through the actual original owner. The fixture only supplies
 * requested synthetic policy data and a caller-owned head secret; the named-head approval,
 * current master, employee session and protected read/ACK remain production observations.
 * No administrator grant is treated as an implicit read/ACK/go-live action.
 */
internal suspend fun activateAosfSetupPolicy(
    owner: AosfNativeOwner,
    plan: AosfFixturePlan,
    account: LocalAccountProvisioning,
    revision: Revision,
    actions: Set<SecurityAction>,
    headSecret: CharArray,
    evidence: AosfEvidence,
): ActivatedLocalPolicy {
    check(actions.isNotEmpty()) { "At least one explicit setup-result action must be authorized" }
    val next = plan.revisedPolicy(account, revision, actions)
    return activateAosfPolicy(owner, plan, account, next, actions, headSecret, evidence)
}

/**
 * Activates one exact next policy built from caller-selected grants. This is required for the
 * setup-checkpoint commands, whose production requirements use distinct ADMINISTER actions.
 */
internal suspend fun activateAosfCheckpointPolicy(
    owner: AosfNativeOwner,
    plan: AosfFixturePlan,
    account: LocalAccountProvisioning,
    revision: Revision,
    actions: Set<SecurityAction>,
    headSecret: CharArray,
    evidence: AosfEvidence,
): ActivatedLocalPolicy {
    check(actions.isNotEmpty()) { "At least one explicit setup action must be authorized" }
    val extra = actions.map { action ->
        val verb = if (action in AOSF_ADMINISTERED_SETUP_ACTIONS) PermissionVerb.ADMINISTER
        else PermissionVerb.PERFORM
        plan.permissionGrant(account, action, verb)
    }
    val next = plan.revisedPolicy(account, revision, extra)
    return activateAosfPolicy(owner, plan, account, next, actions, headSecret, evidence)
}

private val AOSF_ADMINISTERED_SETUP_ACTIONS = setOf(
    SecurityAction.SETUP_CONFIGURATION_SETTLE,
    SecurityAction.SETUP_CATALOG_IMPORT_SETTLE,
    SecurityAction.SETUP_OPENING_SETTLE,
    SecurityAction.BRANCH_ACTIVITY_ACTIVATE,
)

private suspend fun activateAosfPolicy(
    owner: AosfNativeOwner,
    plan: AosfFixturePlan,
    account: LocalAccountProvisioning,
    next: ActivatedLocalPolicy,
    reportedActions: Set<SecurityAction>,
    headSecret: CharArray,
    evidence: AosfEvidence,
): ActivatedLocalPolicy {
    val original = checkNotNull(owner.original) { "The actual original owner is not open" }
    val requestId = DomainId.random()
    val action = nativeFixtureValue(original.prepareEmployeePolicy(requestId, next))
    val approval = withAosfSecret(headSecret) {
        nativeFixtureValue(original.approveEmployeePolicy(action, plan.headLogin, it))
    }
    check(approval.originalActionId == action.originalActionId &&
        approval.requested.request.requestId == requestId &&
        approval.requested.request.targetId == account.actorId) {
        "The actual signed policy approval is not bound to the requested setup actor"
    }
    val applied = original.activateEmployeePolicy(approval, next)
        as? LocalCredentialAdministrationResult.Applied
        ?: error("The actual next-revision setup-action policy did not settle")
    val retained = original.lookupEmployeePolicy(approval, next) as? LocalProvisioningStatus.Applied
        ?: error("The actual activated setup-action policy cannot be reobserved")
    check(retained.eventId == applied.eventId)
    evidence.event("SIGNED_SETUP_ACTION_POLICY_ACTIVATED", JSONObject()
        .put("actorId", account.actorId.value)
        .put("policyRevision", next.revision.value)
        .put("actions", reportedActions.map { it.name }.sorted().joinToString(","))
        .put("originalActionId", approval.originalActionId.value)
        .put("activationEventId", applied.eventId.value)
        .put("policyFingerprint", AosfAnchor.policyFingerprint(next)))
    return next
}

/** Actual authenticated session under the current policy; never a fixture-created principal. */
internal suspend fun requireAosfSetupActor(
    sessions: AosfSessionLifetime,
    tokens: LocalSessionTokens,
    anchor: AosfAnchor,
    expected: ActivatedLocalPolicy,
    label: String,
    evidence: AosfEvidence,
): LocalSessionView {
    val view = sessions.requireCurrent(tokens, label)
    check(view.actorId == expected.actorId && view.scope.branchId.value == anchor.branchId &&
        view.deviceId.value == anchor.deviceId && view.policy.revision == expected.revision) {
        "The current employee session is not bound to the requested setup actor/policy"
    }
    com.ukkera.pos.android.independent.nativefixture.requireNativeFixturePolicy(expected, view.policy)
    evidence.event("CURRENT_SETUP_ACTOR_SESSION", JSONObject()
        .put("label", label)
        .put("actorId", view.actorId.value)
        .put("login", view.login.value)
        .put("sessionId", view.sessionId.value)
        .put("generation", view.generation.value)
        .put("policyRevision", view.policy.revision.value))
    return view
}

/** Raw authorized-read probe for accepted/denied stale-session observations. */
internal suspend fun probeAosfProtectedSetup(
    owner: AosfNativeOwner,
    credentials: com.ukkera.pos.security.LocalGatewayCredentials,
    anchor: AosfAnchor,
): ReadReply {
    val original = checkNotNull(owner.original) { "The actual original owner is not open" }
    return original.client.read(credentials, LocalSetupResultCodecV1.read(anchor.installation))
}

internal fun requireAosfReadDenied(reply: ReadReply, label: String) {
    check(reply is ReadReply.Unavailable &&
        reply.reason == com.ukkera.pos.database.ReadFailure.AUTHORIZATION_DENIED) {
        "$label protected setup read was not denied: $reply"
    }
}

/**
 * One authorized saved-setup read. The projection is decoded from the actual protected read
 * reply and bound to the receipt/definition anchor, the current actor and the committed ACK
 * (when one exists). A read never performs or replaces a delivery acknowledgement.
 */
internal suspend fun readAosfProtectedSetup(
    owner: AosfNativeOwner,
    sessions: AosfSessionLifetime,
    tokens: LocalSessionTokens,
    anchor: AosfAnchor,
    actorId: DomainId,
    policyRevision: Revision,
    acknowledgement: LocalSetupAcknowledgementRecordV1?,
    label: String,
    evidence: AosfEvidence,
    goLive: LocalSetupGoLiveRecordV1? = null,
): LocalSetupResultProjectionV1 {
    val original = checkNotNull(owner.original) { "The actual original owner is not open" }
    val reply = original.client.read(sessions.credentials(tokens),
        LocalSetupResultCodecV1.read(anchor.installation))
    val data = reply as? ReadReply.Data
        ?: error("The authorized protected saved-setup read was not disclosed: $reply")
    val projection = LocalSetupResultCodecV1.decodeProjection(data.payload)
    anchor.requireProjection(projection)
    if (goLive == null) {
        anchor.requireNoGoLive(projection)
    } else {
        anchor.requireGoLive(projection, goLive.actorId, goLive.operationId, goLive.policyRevision)
        check(projection.goLive == goLive) {
            "The protected read changed the committed go-live fact"
        }
    }
    if (acknowledgement == null) {
        check(projection.acknowledgement == null) {
            "A protected read disclosed an acknowledgement no actual ACK committed"
        }
    } else {
        anchor.requireAcknowledgement(projection, actorId, acknowledgement.operationId,
            acknowledgement.policyRevision)
        check(projection.acknowledgement == acknowledgement) {
            "Repeated protected reads changed the committed delivery acknowledgement"
        }
    }
    evidence.event("PROTECTED_SETUP_READ", JSONObject()
        .put("label", label)
        .put("installationId", anchor.installationId)
        .put("branchRevision", data.branchRevision.value)
        .put("observedAt", data.observedAt.toString())
        .put("originalActorId", projection.original.firstActorId.value)
        .put("acknowledgement", if (projection.acknowledgement == null) "ABSENT" else "PRESENT")
        .put("goLive", if (projection.goLive == null) "ABSENT" else "PRESENT")
        .put("prerequisiteBindings", projection.prerequisites.joinToString(",") {
            "${it.code.name}=${if (it.settled) "SETTLED" else "UNAVAILABLE"}:${it.binding}"
        }))
    check(projection.prerequisites.size == 4)
    return projection
}

/** A typed setup-result fact plus the exact durable recorded reply that produced it. */
internal data class AosfRecordedSetupAction<T>(
    val record: T,
    val recorded: MutationReply.Recorded,
)

/**
 * One real delivery ACK through the registered production command. The reply must be a fresh
 * durable commit bound to the original result, current actor and master authority epoch.
 */
internal suspend fun acknowledgeAosfDelivery(
    owner: AosfNativeOwner,
    sessions: AosfSessionLifetime,
    tokens: LocalSessionTokens,
    anchor: AosfAnchor,
    actorId: DomainId,
    policyRevision: Revision,
    evidence: AosfEvidence,
): LocalSetupAcknowledgementRecordV1 = acknowledgeAosfDeliveryRecorded(
    owner, sessions, tokens, anchor, actorId, policyRevision, evidence).record

internal suspend fun acknowledgeAosfDeliveryRecorded(
    owner: AosfNativeOwner,
    sessions: AosfSessionLifetime,
    tokens: LocalSessionTokens,
    anchor: AosfAnchor,
    actorId: DomainId,
    policyRevision: Revision,
    evidence: AosfEvidence,
): AosfRecordedSetupAction<LocalSetupAcknowledgementRecordV1> {
    val original = checkNotNull(owner.original) { "The actual original owner is not open" }
    val operationId = OperationId.random()
    val request = LocalSetupResultCodecV1.acknowledge(operationId, anchor.installation)
    val reply = original.client.execute(sessions.credentials(tokens), request)
    val recorded = reply as? MutationReply.Recorded
        ?: error("The explicit delivery acknowledgement was not durably recorded: $reply")
    check(!recorded.replayed && recorded.operationId == operationId)
    val outcome = LocalSetupResultCodecV1.decodeOutcome(recorded.result)
    check(outcome.status == LocalSetupResultStatusV1.ACKNOWLEDGED &&
        outcome.acknowledgement != null && outcome.goLive == null) {
        "The delivery acknowledgement result is not an ACK-only committed fact"
    }
    val acknowledgement = checkNotNull(outcome.acknowledgement)
    anchor.requireAcknowledgementRecord(acknowledgement, actorId, operationId, policyRevision)
    evidence.event("DELIVERY_ACK_COMMITTED", JSONObject()
        .put("operationId", operationId.value)
        .put("actorId", actorId.value)
        .put("policyRevision", policyRevision.value)
        .put("masterAuthorityEpoch", acknowledgement.masterAuthorityEpoch.value)
        .put("acknowledgedAt", acknowledgement.acknowledgedAt.toString())
        .put("code", outcome.code)
        .put("goLive", "ABSENT"))
    return AosfRecordedSetupAction(acknowledgement, recorded)
}

/**
 * One real responsible business go-live through the registered production command. The master
 * derives the actor/action/time/prerequisite observations; this fixture supplies only the named
 * installation and operation identity. ACK absence is required to stay absent.
 */
internal suspend fun goLiveAosfBusiness(
    owner: AosfNativeOwner,
    sessions: AosfSessionLifetime,
    tokens: LocalSessionTokens,
    anchor: AosfAnchor,
    actorId: DomainId,
    policyRevision: Revision,
    evidence: AosfEvidence,
): AosfRecordedSetupAction<LocalSetupGoLiveRecordV1> {
    val original = checkNotNull(owner.original) { "The actual original owner is not open" }
    val operationId = OperationId.random()
    val request = LocalSetupResultCodecV1.goLive(operationId, anchor.installation)
    val reply = original.client.execute(sessions.credentials(tokens), request)
    val recorded = reply as? MutationReply.Recorded
        ?: error("The explicit business go-live was not durably recorded: $reply")
    check(!recorded.replayed && recorded.operationId == operationId)
    val outcome = LocalSetupResultCodecV1.decodeOutcome(recorded.result)
    check(outcome.status == LocalSetupResultStatusV1.LIVE && outcome.goLive != null &&
        outcome.acknowledgement == null && outcome.goLive!!.prerequisites == outcome.prerequisites) {
        "The business go-live result is not a responsible-actor LIVE fact with its prerequisites"
    }
    val goLive = checkNotNull(outcome.goLive)
    anchor.requireGoLiveRecord(goLive, actorId, operationId, policyRevision)
    check(goLive.prerequisites.size == 4 && goLive.prerequisites.all { it.settled }) {
        "The committed go-live does not carry four genuinely settled prerequisites"
    }
    evidence.event("BUSINESS_GO_LIVE_COMMITTED", JSONObject()
        .put("operationId", operationId.value)
        .put("actorId", actorId.value)
        .put("policyRevision", policyRevision.value)
        .put("masterAuthorityEpoch", goLive.masterAuthorityEpoch.value)
        .put("occurredAt", goLive.occurredAt.toString())
        .put("code", outcome.code)
        .put("prerequisites", outcome.prerequisites.joinToString(",") {
            "${it.code.name}=${if (it.settled) "SETTLED" else "UNAVAILABLE"}:${it.binding}"
        })
        .put("acknowledgement", "ABSENT"))
    return AosfRecordedSetupAction(goLive, recorded)
}

/** Same operation/content after a real restart must return the exact durable original result. */
internal suspend fun replayAosfSetupAction(
    owner: AosfNativeOwner,
    sessions: AosfSessionLifetime,
    tokens: LocalSessionTokens,
    request: MutationRequest,
    original: MutationReply.Recorded,
    label: String,
    evidence: AosfEvidence,
): MutationReply.Recorded {
    val ownerOriginal = checkNotNull(owner.original) { "The actual original owner is not open" }
    val reply = ownerOriginal.client.execute(sessions.credentials(tokens), request)
    val replay = reply as? MutationReply.Recorded
        ?: error("$label same-operation retry was not a recorded durable result: $reply")
    check(replay.replayed && replay.operationId == original.operationId &&
        replay.originalActorId == original.originalActorId && replay.result == original.result &&
        replay.recordedAt == original.recordedAt && replay.authorityEpoch == original.authorityEpoch &&
        replay.sequence == original.sequence) {
        "$label same-operation retry did not return the exact durable original result"
    }
    evidence.event("DURABLE_SETUP_ACTION_REPLAY", JSONObject()
        .put("label", label).put("operationId", original.operationId.value)
        .put("actorId", original.originalActorId.value)
        .put("authorityEpoch", original.authorityEpoch.value)
        .put("sequence", original.sequence.value))
    return replay
}
