package com.ukkera.pos.android.independent.sasacksetupv1

import com.ukkera.pos.database.LookupReply
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.database.setup.LocalSetupResultCodecV1
import com.ukkera.pos.database.setup.LocalSetupResultOutcomeV1
import com.ukkera.pos.database.setup.LocalSetupResultProfileV1
import com.ukkera.pos.database.setup.LocalSetupResultStatusV1
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.LocalAdministrationAction
import com.ukkera.pos.security.LocalAdministrationCommand
import com.ukkera.pos.security.LocalAdministrationDirectory
import com.ukkera.pos.security.LocalAdministrationQuery
import com.ukkera.pos.security.LocalAdministrationReadResult
import com.ukkera.pos.security.LocalCredentialAdministrationResult
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

internal const val SAS_ANDROID_EXECUTED = "ANDROID_MASTER_SEGMENT_EXECUTED_WHOLE_CASE_REMAINDERS_UNBOUND"
internal const val SAS_BLOCKED_READINESS = "BLOCKED_UNBOUND_GO_LIVE_PREREQUISITE_BINDING"
internal const val SAS_BLOCKED_FAULT = "BLOCKED_UNBOUND_PRODUCTION_FAULT_INJECTION"
internal const val SAS_BLOCKED_ENVIRONMENT = "BLOCKED_UNBOUND_SEPARATE_BRANCH_TRAINING_CLIENT_ENVIRONMENT"
internal const val SAS_BLOCKED_UPGRADE = "BLOCKED_UNBOUND_PRE_INCREMENT_UPGRADE_FIXTURE"

internal suspend fun runSelectedCase(case: String, fixture: SasFixture, evidence: SasEvidence): String = when (case) {
    "SAS-001" -> runSas001(fixture, evidence)
    "SAS-002" -> runSas002(fixture, evidence)
    "SAS-003" -> runSas003(fixture, evidence)
    "SAS-004" -> runSas004(fixture, evidence)
    "SAS-005" -> runSas005(fixture, evidence)
    "SAS-006" -> runSas006(fixture, evidence)
    "SAS-007" -> runSas007(fixture, evidence)
    "SAS-008" -> runSas008(fixture, evidence)
    "SAS-009" -> runSas009(fixture, evidence)
    "SAS-010" -> runSas010(fixture, evidence)
    "SAS-011" -> runSas011(fixture, evidence)
    "SAS-012" -> runSas012(fixture, evidence)
    "SAS-013" -> runSas013(fixture, evidence)
    "SAS-014" -> runSas014(fixture, evidence)
    "SAS-015" -> runSas015(fixture, evidence)
    "SAS-016" -> runSas016(fixture, evidence)
    "SAS-017" -> runSas017(fixture, evidence)
    "SAS-018" -> runSas018(fixture, evidence)
    "SAS-019" -> runSas019(fixture, evidence)
    "SAS-020" -> runSas020(fixture, evidence)
    "SAS-021" -> runSas021(fixture, evidence)
    "SAS-022" -> runSas022(fixture, evidence)
    else -> error("Unknown frozen SAS case $case")
}

/** SAS-001: installation, login, RUN, read, ACK and go-live are distinct. */
private suspend fun runSas001(fixture: SasFixture, evidence: SasEvidence): String {
    fixture.enroll(SasActor.UR)
    fixture.activate(SasActor.UR, Revision(1), setOf(SecurityAction.SETUP_RESULT_READ))
    val baseline = fixture.cut()
    fixture.reopen()
    val session = fixture.signIn(SasActor.UR)
    fixture.requirePolicy(SasActor.UR, fixture.plan.policy(SasActor.UR, fixture.coordinates,
        Revision(1), setOf(SecurityAction.SETUP_RESULT_READ)))
    repeat(2) { index ->
        val projection = fixture.read(SasActor.UR)
        fixture.anchor.requireProjection(projection)
        check(projection.acknowledgement == null && projection.goLive == null) {
            "A protected read created or disclosed an ACK/go-live fact"
        }
        evidence.event("SAS001_PROTECTED_READ", JSONObject().put("read", index + 1)
            .put("sessionId", session.sessionId.value).put("installationId", fixture.anchor.installationId.value)
            .put("nativeSettlement", projection.original.nativeSettlement)
            .put("prerequisiteBindings", projection.prerequisites.joinToString(",") { it.binding }))
    }
    fixture.restart()
    fixture.signIn(SasActor.UR)
    val afterRestart = fixture.read(SasActor.UR)
    fixture.anchor.requireProjection(afterRestart)
    check(afterRestart.acknowledgement == null && afterRestart.goLive == null)
    val after = fixture.cut()
    after.requireSameSetupBase(baseline)
    after.requireNoSetupFacts()
    evidence.event("SAS001_RESTARTED_RUNTIME_READ_ONLY", JSONObject()
        .put("setupRoots", after.count(com.ukkera.pos.security.NativeHistoryTableV2.BRANCH))
        .put("masterAssignments", after.count(com.ukkera.pos.security.NativeHistoryTableV2.ADMISSION))
        .put("ackAggregates", after.setupAggregates(LocalSetupResultProfileV1.ACKNOWLEDGEMENT_KIND).size)
        .put("goLiveAggregates", after.setupAggregates(LocalSetupResultProfileV1.GO_LIVE_KIND).size))
    fixture.reopen()
    evidence.unbound("SAS-001-EXACT-READ-PROJECTION", "U1: exact public read projection/status fields are undecided; only the fixed identities above are asserted")
    evidence.unbound("SAS-001-NATIVE-ENVELOPE-LIFETIME", "U3/X-1: native delivery-envelope lifetime/consumption is not asserted")
    platformRemainder("SAS-001", evidence)
    return SAS_ANDROID_EXECUTED
}

/** SAS-002: one explicit delivery ACK, durable retained result, no go-live. */
private suspend fun runSas002(fixture: SasFixture, evidence: SasEvidence): String {
    fixture.enroll(SasActor.UA)
    fixture.activate(SasActor.UA, Revision(1), setOf(SecurityAction.SETUP_RESULT_DELIVERY_ACKNOWLEDGE))
    val baseline = fixture.cut()
    fixture.reopen()
    fixture.signIn(SasActor.UA)
    fixture.requirePolicy(SasActor.UA, fixture.plan.policy(SasActor.UA, fixture.coordinates,
        Revision(1), setOf(SecurityAction.SETUP_RESULT_DELIVERY_ACKNOWLEDGE)))
    val operationId = OperationId.random()
    val request = LocalSetupResultCodecV1.acknowledge(operationId, fixture.anchor.installationId)
    val withheld = fixture.client.execute(SasActor.UA, request)
    val retry = recorded(fixture.client.execute(SasActor.UA, request))
    val first = recorded(withheld)
    requireAck(first, fixture, SasActor.UA, operationId)
    check(retry.replayed && retry.operationId == operationId && retry.originalActorId == fixture.plan.actorId(
        SasActor.UA, fixture.coordinates) && retry.result == first.result && retry.recordedAt == first.recordedAt) {
        "The same-operation retry did not return the original durable ACK result"
    }
    fixture.restart()
    fixture.signIn(SasActor.UA)
    val afterRestart = recorded(fixture.client.execute(SasActor.UA, request))
    check(afterRestart.replayed && afterRestart.result == first.result &&
        afterRestart.operationId == operationId && afterRestart.recordedAt == first.recordedAt)
    val after = fixture.cut()
    after.requireOneAcknowledgement(baseline, fixture.plan.actorId(SasActor.UA, fixture.coordinates), operationId)
    fixture.reopen()
    evidence.event("SAS002_ONE_DURABLE_ACK_ONLY", JSONObject()
        .put("ackAggregates", after.setupAggregates(LocalSetupResultProfileV1.ACKNOWLEDGEMENT_KIND).size)
        .put("goLiveAggregates", after.setupAggregates(LocalSetupResultProfileV1.GO_LIVE_KIND).size)
        .put("operationActor", fixture.plan.actorId(SasActor.UA, fixture.coordinates).value))
    evidence.unbound("SAS-002-EXACT-ACK-STATUS", "U1: exact ACK return/status/error representation is undecided")
    evidence.unbound("SAS-002-NATIVE-ENVELOPE-LIFETIME", "U3/X-1: native envelope retention/consumption is not asserted")
    platformRemainder("SAS-002", evidence)
    return SAS_ANDROID_EXECUTED
}

/** SAS-003: go-live is independent of a prior delivery ACK; success remains readiness-bound. */
private suspend fun runSas003(fixture: SasFixture, evidence: SasEvidence): String {
    fixture.enroll(SasActor.UG)
    fixture.activate(SasActor.UG, Revision(1), setOf(SecurityAction.SETUP_BUSINESS_GO_LIVE))
    val baseline = fixture.cut()
    fixture.reopen()
    fixture.signIn(SasActor.UG)
    fixture.requirePolicy(SasActor.UG, fixture.plan.policy(SasActor.UG, fixture.coordinates,
        Revision(1), setOf(SecurityAction.SETUP_BUSINESS_GO_LIVE)))
    val operationId = OperationId.random()
    val reply = fixture.goLive(SasActor.UG, operationId)
    val after = when (reply) {
        is MutationReply.Recorded -> {
            val outcome = LocalSetupResultCodecV1.decodeOutcome(reply.result)
            val live = checkNotNull(outcome.goLive)
            check(outcome.status == LocalSetupResultStatusV1.LIVE && outcome.acknowledgement == null &&
                live.actorId == fixture.plan.actorId(SasActor.UG, fixture.coordinates) &&
                live.operationId == operationId && live.prerequisites.all { it.settled })
            fixture.cut().also {
                it.requireOneGoLive(baseline, fixture.plan.actorId(SasActor.UG, fixture.coordinates), operationId)
                check(it.setupAggregates(LocalSetupResultProfileV1.ACKNOWLEDGEMENT_KIND).isEmpty())
            }
        }
        is MutationReply.Deferred -> {
            requireDeferred(reply, com.ukkera.pos.database.DeferredReason.CURRENT_SOURCE_UNAVAILABLE)
            evidence.unbound("SAS-003-GO-LIVE-COMMIT", "Actual original-linked required configuration/import/opening readiness is unbound; go-live stayed held with no effect")
            fixture.cut().also {
                it.requireSameSetupBase(baseline)
                it.requireNoSetupFacts()
            }
        }
        else -> error("Unexpected go-live reply: $reply")
    }
    evidence.event("SAS003_NO_IMPLICIT_ACK", JSONObject()
        .put("ackAggregates", after.setupAggregates(LocalSetupResultProfileV1.ACKNOWLEDGEMENT_KIND).size)
        .put("goLiveAggregates", after.setupAggregates(LocalSetupResultProfileV1.GO_LIVE_KIND).size)
        .put("centralRegistration", false).put("healthProof", false).put("employeeSignIn", false)
        .put("financialOrFiscalEffect", false).put("trainingPromotion", false).put("nativeQualification", false))
    evidence.unbound("SAS-003-PREREQUISITE-BINDING", "U2: exact native prerequisite probe/status representation is undecided")
    evidence.unbound("SAS-003-CENTRAL-NATIVE-NON-EFFECTS", "U9: central/native mechanisms are not present; only their negative non-effects are asserted")
    platformRemainder("SAS-003", evidence)
    return if (after.setupAggregates(LocalSetupResultProfileV1.GO_LIVE_KIND).isEmpty()) SAS_BLOCKED_READINESS
    else SAS_ANDROID_EXECUTED
}

/** SAS-004: an already-live original can receive ACK without changing go-live. */
private suspend fun runSas004(fixture: SasFixture, evidence: SasEvidence): String {
    fixture.enroll(SasActor.UA)
    fixture.enroll(SasActor.UG)
    fixture.enroll(SasActor.UR)
    fixture.activate(SasActor.UA, Revision(1), setOf(SecurityAction.SETUP_RESULT_DELIVERY_ACKNOWLEDGE))
    fixture.activate(SasActor.UG, Revision(1), setOf(SecurityAction.SETUP_BUSINESS_GO_LIVE))
    fixture.activate(SasActor.UR, Revision(1), setOf(SecurityAction.SETUP_RESULT_READ))
    val baseline = fixture.cut()
    fixture.reopen()
    fixture.signIn(SasActor.UG)
    val goLiveOperation = OperationId.random()
    val goLiveReply = fixture.goLive(SasActor.UG, goLiveOperation)
    if (goLiveReply !is MutationReply.Recorded) {
        check(goLiveReply is MutationReply.Deferred)
        evidence.unbound("SAS-004-ALREADY-LIVE-BASELINE", "S1L cannot be produced because actual original-linked go-live readiness is unbound; no ACK-on-live was simulated")
        platformRemainder("SAS-004", evidence)
        return SAS_BLOCKED_READINESS
    }
    val liveOutcome = LocalSetupResultCodecV1.decodeOutcome(goLiveReply.result)
    check(liveOutcome.status == LocalSetupResultStatusV1.LIVE)
    fixture.signIn(SasActor.UA)
    fixture.signIn(SasActor.UR)
    val ackOperation = OperationId.random()
    val ack = recorded(fixture.ack(SasActor.UA, ackOperation))
    requireAck(ack, fixture, SasActor.UA, ackOperation)
    val projection = fixture.read(SasActor.UR)
    fixture.anchor.requireLive(projection, fixture.plan.actorId(SasActor.UG, fixture.coordinates), goLiveOperation)
    fixture.anchor.requireAcknowledgement(projection, fixture.plan.actorId(SasActor.UA, fixture.coordinates), ackOperation)
    fixture.restart()
    fixture.signIn(SasActor.UR)
    val afterRestart = fixture.read(SasActor.UR)
    fixture.anchor.requireLive(afterRestart, fixture.plan.actorId(SasActor.UG, fixture.coordinates), goLiveOperation)
    fixture.anchor.requireAcknowledgement(afterRestart, fixture.plan.actorId(SasActor.UA, fixture.coordinates), ackOperation)
    val after = fixture.cut()
    after.requireSameSetupBase(baseline)
    after.requireSetupAggregateDelta(baseline, LocalSetupResultProfileV1.GO_LIVE_KIND, 1)
    after.requireSetupAggregateDelta(baseline, LocalSetupResultProfileV1.ACKNOWLEDGEMENT_KIND, 1)
    fixture.reopen()
    evidence.unbound("SAS-004-NATIVE-ENVELOPE-LIFETIME", "U3/X-1: native envelope lifetime is not asserted")
    platformRemainder("SAS-004", evidence)
    return SAS_ANDROID_EXECUTED
}

/** SAS-005: role, setup actor, administrator, central identity and UI/approval do not grant action authority. */
private suspend fun runSas005(fixture: SasFixture, evidence: SasEvidence): String {
    fixture.enroll(SasActor.UX)
    fixture.enroll(SasActor.UR)
    fixture.activate(SasActor.UR, Revision(1), setOf(SecurityAction.SETUP_RESULT_READ))
    val baseline = fixture.cut()
    fixture.reopen()
    fixture.signIn(SasActor.US)
    fixture.signIn(SasActor.UX)
    fixture.requirePolicy(SasActor.US, checkNotNull(fixture.baseCut).policy)
    fixture.requirePolicy(SasActor.UX, fixture.plan.account(SasActor.UX, fixture.coordinates).policy)
    requireDeferred(fixture.ack(SasActor.US, OperationId.random()),
        com.ukkera.pos.database.DeferredReason.AUTHORIZATION_DENIED)
    requireDeferred(fixture.goLive(SasActor.US, OperationId.random()),
        com.ukkera.pos.database.DeferredReason.AUTHORIZATION_DENIED)
    requireDeferred(fixture.ack(SasActor.UX, OperationId.random()),
        com.ukkera.pos.database.DeferredReason.AUTHORIZATION_DENIED)
    requireDeferred(fixture.goLive(SasActor.UX, OperationId.random()),
        com.ukkera.pos.database.DeferredReason.AUTHORIZATION_DENIED)
    val unrelatedApproval = DomainId.random()
    val approvedGoLive = MutationRequest(OperationId.random(), LocalSetupResultProfileV1.GO_LIVE,
        LocalSetupResultCodecV1.encodeRequest(fixture.anchor.installationId), unrelatedApproval)
    requireDeferred(fixture.client.execute(SasActor.US, approvedGoLive),
        com.ukkera.pos.database.DeferredReason.AUTHORIZATION_DENIED)
    fixture.signIn(SasActor.UR)
    val read = fixture.read(SasActor.UR)
    fixture.anchor.requireProjection(read)
    check(read.acknowledgement == null && read.goLive == null)
    val after = fixture.cut()
    after.requireSameSetupBase(baseline)
    after.requireNoSetupFacts()
    fixture.reopen()
    evidence.unbound("SAS-005-CENTRAL-IDENTITY", "UC central-administration session is unavailable in the local-only runtime; no local employee session was relabelled as UC")
    evidence.unbound("SAS-005-UI-FLAG", "The master request grammar carries no UI readiness/actor/permission flag; a supplied field only reaches the fail-closed codec (see SAS-018)")
    evidence.unbound("SAS-005-EXACT-DENIAL", "U1: exact denial representation is undecided")
    evidence.unbound("SAS-005-APPROVAL-POLICY", "U4: exact action-approval representation is undecided; the unrelated approval ID was refused with no effect")
    platformRemainder("SAS-005", evidence)
    return SAS_ANDROID_EXECUTED
}

/** SAS-006: only normal signed policy activation provisions the three exact current actions. */
private suspend fun runSas006(fixture: SasFixture, evidence: SasEvidence): String {
    listOf(SasActor.UA, SasActor.UG, SasActor.UR, SasActor.UX).forEach { fixture.enroll(it) }
    val beforeActivation = fixture.cut()
    fixture.reopen()
    fixture.signIn(SasActor.UA)
    fixture.signIn(SasActor.UG)
    requireDeferred(fixture.ack(SasActor.UA, OperationId.random()),
        com.ukkera.pos.database.DeferredReason.AUTHORIZATION_DENIED)
    requireDeferred(fixture.goLive(SasActor.UG, OperationId.random()),
        com.ukkera.pos.database.DeferredReason.AUTHORIZATION_DENIED)
    val afterDenials = fixture.cut()
    afterDenials.requireSameSetupBase(beforeActivation)
    afterDenials.requireNoSetupFacts()
    fixture.reopen()
    fixture.activate(SasActor.UA, Revision(1), setOf(SecurityAction.SETUP_RESULT_DELIVERY_ACKNOWLEDGE))
    fixture.activate(SasActor.UG, Revision(1), setOf(SecurityAction.SETUP_BUSINESS_GO_LIVE))
    fixture.activate(SasActor.UR, Revision(1), setOf(SecurityAction.SETUP_RESULT_READ))
    val baseline = fixture.cut()
    fixture.reopen()
    fixture.signIn(SasActor.UA)
    fixture.signIn(SasActor.UG)
    fixture.signIn(SasActor.UR)
    fixture.signIn(SasActor.UX)
    fixture.requirePolicy(SasActor.UA, fixture.plan.policy(SasActor.UA, fixture.coordinates,
        Revision(1), setOf(SecurityAction.SETUP_RESULT_DELIVERY_ACKNOWLEDGE)))
    fixture.requirePolicy(SasActor.UG, fixture.plan.policy(SasActor.UG, fixture.coordinates,
        Revision(1), setOf(SecurityAction.SETUP_BUSINESS_GO_LIVE)))
    fixture.requirePolicy(SasActor.UX, fixture.plan.account(SasActor.UX, fixture.coordinates).policy)
    val ackOperation = OperationId.random()
    requireAck(recorded(fixture.ack(SasActor.UA, ackOperation)), fixture, SasActor.UA, ackOperation)
    val goLiveOperation = OperationId.random()
    val goLiveReply = fixture.goLive(SasActor.UG, goLiveOperation)
    if (goLiveReply is MutationReply.Deferred) {
        requireDeferred(goLiveReply, com.ukkera.pos.database.DeferredReason.CURRENT_SOURCE_UNAVAILABLE)
        evidence.unbound("SAS-006-GO-LIVE-SUCCESS", "Actual original-linked required readiness is unbound; authority was accepted but go-live stayed held")
    } else {
        check(goLiveReply is MutationReply.Recorded)
        check(LocalSetupResultCodecV1.decodeOutcome(goLiveReply.result).status == LocalSetupResultStatusV1.LIVE)
    }
    fixture.signIn(SasActor.UR)
    val read = fixture.read(SasActor.UR)
    fixture.anchor.requireAcknowledgement(read, fixture.plan.actorId(SasActor.UA, fixture.coordinates), ackOperation)
    requireDeferred(fixture.ack(SasActor.UX, OperationId.random()),
        com.ukkera.pos.database.DeferredReason.AUTHORIZATION_DENIED)
    requireDeferred(fixture.goLive(SasActor.UX, OperationId.random()),
        com.ukkera.pos.database.DeferredReason.AUTHORIZATION_DENIED)
    val after = fixture.cut()
    after.requireSameSetupBase(baseline)
    after.requireSetupAggregateDelta(baseline, LocalSetupResultProfileV1.ACKNOWLEDGEMENT_KIND, 1)
    fixture.reopen()
    evidence.unbound("SAS-006-EXACT-ACTIVATION-STATUS", "U1/U4: exact policy action names/ordinals/package bytes/activation status are undecided; the real signed ceremony was used")
    platformRemainder("SAS-006", evidence)
    return if (goLiveReply is MutationReply.Deferred) SAS_BLOCKED_READINESS else SAS_ANDROID_EXECUTED
}

/** SAS-007: revocation serializes with commit; a cached session/token is not current authority. */
private suspend fun runSas007(fixture: SasFixture, evidence: SasEvidence): String {
    listOf(SasActor.UA, SasActor.UG, SasActor.UR).forEach { fixture.enroll(it) }
    fixture.activate(SasActor.UA, Revision(1), setOf(SecurityAction.SETUP_RESULT_DELIVERY_ACKNOWLEDGE))
    fixture.activate(SasActor.UG, Revision(1), setOf(SecurityAction.SETUP_BUSINESS_GO_LIVE))
    fixture.activate(SasActor.UR, Revision(1), setOf(SecurityAction.SETUP_RESULT_READ))
    val baseline = fixture.cut()
    fixture.reopen()
    fixture.signIn(SasActor.UA)
    fixture.signIn(SasActor.UG)
    fixture.signIn(SasActor.UR)
    fixture.activate(SasActor.UA, Revision(2), emptySet())
    fixture.activate(SasActor.UG, Revision(2), emptySet())
    fixture.requirePolicy(SasActor.UA, fixture.plan.policy(SasActor.UA, fixture.coordinates, Revision(2), emptySet()))
    fixture.requirePolicy(SasActor.UG, fixture.plan.policy(SasActor.UG, fixture.coordinates, Revision(2), emptySet()))
    fixture.requirePolicy(SasActor.UR, fixture.plan.policy(SasActor.UR, fixture.coordinates,
        Revision(1), setOf(SecurityAction.SETUP_RESULT_READ)))
    requireDeferred(fixture.ack(SasActor.UA, OperationId.random()),
        com.ukkera.pos.database.DeferredReason.AUTHORIZATION_DENIED)
    requireDeferred(fixture.goLive(SasActor.UG, OperationId.random()),
        com.ukkera.pos.database.DeferredReason.AUTHORIZATION_DENIED)
    val read = fixture.read(SasActor.UR)
    fixture.anchor.requireProjection(read)
    check(read.acknowledgement == null && read.goLive == null)
    val after = fixture.cut()
    after.requireSameSetupBase(baseline)
    after.requireNoSetupFacts()
    fixture.reopen()
    evidence.unbound("SAS-007-RACE-BARRIER", "No deterministic revocation/commit barrier is exposed by production; no race was simulated")
    evidence.unbound("SAS-007-CENTRAL-REVOCATION-INTENT", "Central revocation intent is unavailable in local-only operation; only local signed activation was exercised")
    evidence.unbound("SAS-007-EXACT-POLICY-STATUS", "U1/U4: exact activation/revocation status and representation are undecided")
    platformRemainder("SAS-007", evidence)
    return SAS_ANDROID_EXECUTED
}

/** SAS-008: disabled/revoked/wrong-scope/unadmitted participants are denied without effect. */
private suspend fun runSas008(fixture: SasFixture, evidence: SasEvidence): String {
    listOf(SasActor.UA, SasActor.UV, SasActor.UR).forEach { fixture.enroll(it) }
    fixture.activate(SasActor.UA, Revision(1), setOf(SecurityAction.SETUP_RESULT_DELIVERY_ACKNOWLEDGE))
    fixture.activate(SasActor.UV, Revision(1), setOf(SecurityAction.SETUP_RESULT_DELIVERY_ACKNOWLEDGE))
    fixture.activate(SasActor.UR, Revision(1), setOf(SecurityAction.SETUP_RESULT_READ))
    val baseline = fixture.cut()
    fixture.reopen()
    fixture.signIn(SasActor.US)
    fixture.signIn(SasActor.UA)
    fixture.signIn(SasActor.UV)
    val uvCredentials = fixture.client.credentials(SasActor.UV)
    val uvRevision = accountRevision(fixture, SasActor.US, fixture.plan.actorId(SasActor.UV, fixture.coordinates))
    val disabled = fixture.original.administration.administer(
        fixture.client.credentials(SasActor.US),
        LocalAdministrationCommand(DomainId.random(), LocalAdministrationAction.DISABLE_ACCOUNT,
            fixture.plan.actorId(SasActor.UV, fixture.coordinates), uvRevision),
    )
    check(disabled is LocalCredentialAdministrationResult.Applied) { "The actual account disable did not apply: $disabled" }
    check(fixture.client.currentOrDenied(SasActor.UV) is com.ukkera.pos.security.LocalSessionViewResult.Denied)
    requireDeferred(fixture.client.executeWith(uvCredentials,
        LocalSetupResultCodecV1.acknowledge(OperationId.random(), fixture.anchor.installationId)),
        com.ukkera.pos.database.DeferredReason.AUTHORIZATION_DENIED)
    fixture.client.destroyRetained(SasActor.UV)
    val stale = fixture.client.revokeRetain(SasActor.UA)
    requireDeferred(fixture.client.executeWith(stale,
        LocalSetupResultCodecV1.acknowledge(OperationId.random(), fixture.anchor.installationId)),
        com.ukkera.pos.database.DeferredReason.AUTHORIZATION_DENIED)
    fixture.client.destroyRetained(SasActor.UA)
    val after = fixture.cut()
    after.requireSameSetupBase(baseline)
    after.requireNoSetupFacts()
    fixture.reopen()
    evidence.unbound("SAS-008-UNADMITTED-RETIRED-DEVICE", "D3/D4 device admission variants require a separate admitted/retired device fixture not present in this single-device installation")
    evidence.unbound("SAS-008-CROSS-BRANCH", "D5/S2/T1 cross-branch/training variants require separate installations not present in this fixture")
    evidence.unbound("SAS-008-EXACT-DENIAL", "U1: exact denial codes and device/session revocation representations are undecided")
    platformRemainder("SAS-008", evidence)
    return SAS_ANDROID_EXECUTED
}

/** SAS-009: actions bind only to the exact saved original/scope/environment/current master. */
private suspend fun runSas009(fixture: SasFixture, evidence: SasEvidence): String {
    listOf(SasActor.UA, SasActor.UG).forEach { fixture.enroll(it) }
    fixture.activate(SasActor.UA, Revision(1), setOf(SecurityAction.SETUP_RESULT_DELIVERY_ACKNOWLEDGE))
    fixture.activate(SasActor.UG, Revision(1), setOf(SecurityAction.SETUP_BUSINESS_GO_LIVE))
    val baseline = fixture.cut()
    fixture.reopen()
    fixture.signIn(SasActor.UA)
    fixture.signIn(SasActor.UG)
    val other = DomainId.random()
    requireDeferred(fixture.ack(SasActor.UA, OperationId.random(), other),
        com.ukkera.pos.database.DeferredReason.CURRENT_SOURCE_UNAVAILABLE)
    requireDeferred(fixture.goLive(SasActor.UG, OperationId.random(), other),
        com.ukkera.pos.database.DeferredReason.CURRENT_SOURCE_UNAVAILABLE)
    val ackOperation = OperationId.random()
    requireAck(recorded(fixture.ack(SasActor.UA, ackOperation)), fixture, SasActor.UA, ackOperation)
    val goLiveOperation = OperationId.random()
    val goLiveReply = fixture.goLive(SasActor.UG, goLiveOperation)
    if (goLiveReply is MutationReply.Deferred) {
        requireDeferred(goLiveReply, com.ukkera.pos.database.DeferredReason.CURRENT_SOURCE_UNAVAILABLE)
        evidence.unbound("SAS-009-GO-LIVE-SUCCESS", "Actual original-linked readiness is unbound")
    }
    val after = fixture.cut()
    after.requireSameSetupBase(baseline)
    after.requireSetupAggregateDelta(baseline, LocalSetupResultProfileV1.ACKNOWLEDGEMENT_KIND, 1)
    check(after.setupAggregates(LocalSetupResultProfileV1.GO_LIVE_KIND).size ==
        baseline.setupAggregates(LocalSetupResultProfileV1.GO_LIVE_KIND).size + if (goLiveReply is MutationReply.Recorded) 1 else 0)
    fixture.reopen()
    evidence.unbound("SAS-009-S1C-S2-T1", "S1C/S2/T1 require separate installations/saved originals not present in this single-installation fixture; only a conflicting installation name was attempted")
    evidence.unbound("SAS-009-M2-M1-OLD", "M2/M1-old approved replacement mechanics are unavailable; no replacement master was simulated")
    evidence.unbound("SAS-009-EXACT-IDENTITY-COMPARISON", "U5: exact original identity/digest comparison is undecided")
    platformRemainder("SAS-009", evidence)
    return if (goLiveReply is MutationReply.Recorded) SAS_ANDROID_EXECUTED else SAS_BLOCKED_READINESS
}

/** SAS-010: one operation ID and same content returns the original result, including after restart. */
private suspend fun runSas010(fixture: SasFixture, evidence: SasEvidence): String {
    listOf(SasActor.UA, SasActor.UG).forEach { fixture.enroll(it) }
    fixture.activate(SasActor.UA, Revision(1), setOf(SecurityAction.SETUP_RESULT_DELIVERY_ACKNOWLEDGE))
    fixture.activate(SasActor.UG, Revision(1), setOf(SecurityAction.SETUP_BUSINESS_GO_LIVE))
    val baseline = fixture.cut()
    fixture.reopen()
    fixture.signIn(SasActor.UA)
    val ackOperation = OperationId.random()
    val ackRequest = LocalSetupResultCodecV1.acknowledge(ackOperation, fixture.anchor.installationId)
    val ackOriginal = recorded(fixture.client.execute(SasActor.UA, ackRequest))
    requireAck(ackOriginal, fixture, SasActor.UA, ackOperation)
    val ackConcurrent = coroutineScope {
        listOf(
            async { fixture.client.execute(SasActor.UA, ackRequest) },
            async { fixture.client.execute(SasActor.UA, ackRequest) },
        ).awaitAll()
    }
    check(ackConcurrent.all { it is MutationReply.Recorded && it.replayed && it.result == ackOriginal.result })
    fixture.restart()
    fixture.signIn(SasActor.UA)
    val ackRestart = recorded(fixture.client.execute(SasActor.UA, ackRequest))
    check(ackRestart.replayed && ackRestart.result == ackOriginal.result &&
        ackRestart.recordedAt == ackOriginal.recordedAt && ackRestart.originalActorId == ackOriginal.originalActorId)

    fixture.signIn(SasActor.UG)
    val goLiveOperation = OperationId.random()
    val goLiveRequest = LocalSetupResultCodecV1.goLive(goLiveOperation, fixture.anchor.installationId)
    val goLiveOriginal = recorded(fixture.client.execute(SasActor.UG, goLiveRequest))
    val goLiveConcurrent = coroutineScope {
        listOf(
            async { fixture.client.execute(SasActor.UG, goLiveRequest) },
            async { fixture.client.execute(SasActor.UG, goLiveRequest) },
        ).awaitAll()
    }
    check(goLiveConcurrent.all { it is MutationReply.Recorded && it.replayed && it.result == goLiveOriginal.result })
    fixture.restart()
    fixture.signIn(SasActor.UG)
    val goLiveRestart = recorded(fixture.client.execute(SasActor.UG, goLiveRequest))
    check(goLiveRestart.replayed && goLiveRestart.result == goLiveOriginal.result &&
        goLiveRestart.recordedAt == goLiveOriginal.recordedAt &&
        goLiveRestart.originalActorId == fixture.plan.actorId(SasActor.UG, fixture.coordinates))
    val after = fixture.cut()
    after.requireSameSetupBase(baseline)
    after.requireSetupAggregateDelta(baseline, LocalSetupResultProfileV1.ACKNOWLEDGEMENT_KIND, 1)
    after.requireSetupAggregateDelta(baseline, LocalSetupResultProfileV1.GO_LIVE_KIND, 1)
    fixture.reopen()
    evidence.unbound("SAS-010-EXACT-RETRY-STATUS", "U1/U7: exact replay status representation is undecided; durable result/actor identity is asserted")
    platformRemainder("SAS-010", evidence)
    return SAS_ANDROID_EXECUTED
}

/** SAS-011: changed content or actor under one operation ID conflicts and creates no new fact. */
private suspend fun runSas011(fixture: SasFixture, evidence: SasEvidence): String {
    listOf(SasActor.UA, SasActor.UB, SasActor.UG, SasActor.UH).forEach { fixture.enroll(it) }
    fixture.activate(SasActor.UA, Revision(1), setOf(SecurityAction.SETUP_RESULT_DELIVERY_ACKNOWLEDGE))
    fixture.activate(SasActor.UB, Revision(1), setOf(SecurityAction.SETUP_RESULT_DELIVERY_ACKNOWLEDGE))
    fixture.activate(SasActor.UG, Revision(1), setOf(SecurityAction.SETUP_BUSINESS_GO_LIVE))
    fixture.activate(SasActor.UH, Revision(1), setOf(SecurityAction.SETUP_BUSINESS_GO_LIVE))
    val baseline = fixture.cut()
    fixture.reopen()
    listOf(SasActor.UA, SasActor.UB, SasActor.UG, SasActor.UH).forEach { fixture.signIn(it) }
    val ackOperation = OperationId.random()
    val ackOriginal = recorded(fixture.ack(SasActor.UA, ackOperation))
    requireAck(ackOriginal, fixture, SasActor.UA, ackOperation)
    val ackChangedContent = fixture.ack(SasActor.UA, ackOperation, DomainId.random())
    check(ackChangedContent is MutationReply.IdentityConflict) {
        "Changed ACK content under one operation ID did not conflict: $ackChangedContent"
    }
    val ackChangedActor = fixture.ack(SasActor.UB, ackOperation)
    check(ackChangedActor is MutationReply.IdentityConflict || ackChangedActor is MutationReply.Deferred)
    val ackReplay = recorded(fixture.ack(SasActor.UA, ackOperation))
    check(ackReplay.replayed && ackReplay.result == ackOriginal.result && ackReplay.originalActorId ==
        fixture.plan.actorId(SasActor.UA, fixture.coordinates))

    val goLiveOperation = OperationId.random()
    val goLiveOriginal = recorded(fixture.goLive(SasActor.UG, goLiveOperation))
    val goLiveChangedContent = fixture.goLive(SasActor.UG, goLiveOperation, DomainId.random())
    check(goLiveChangedContent is MutationReply.IdentityConflict) {
        "Changed go-live content under one operation ID did not conflict: $goLiveChangedContent"
    }
    val goLiveChangedActor = fixture.goLive(SasActor.UH, goLiveOperation)
    check(goLiveChangedActor is MutationReply.IdentityConflict || goLiveChangedActor is MutationReply.Deferred)
    val goLiveReplay = recorded(fixture.goLive(SasActor.UG, goLiveOperation))
    check(goLiveReplay.replayed && goLiveReplay.result == goLiveOriginal.result &&
        goLiveReplay.originalActorId == fixture.plan.actorId(SasActor.UG, fixture.coordinates))

    val after = fixture.cut()
    after.requireSameSetupBase(baseline)
    after.requireSetupAggregateDelta(baseline, LocalSetupResultProfileV1.ACKNOWLEDGEMENT_KIND, 1)
    after.requireSetupAggregateDelta(baseline, LocalSetupResultProfileV1.GO_LIVE_KIND, 1)
    check(after.operations.none {
        it.operationId == ackOperation.value && it.actorId == fixture.plan.actorId(SasActor.UB, fixture.coordinates).value
    } && after.operations.none {
        it.operationId == goLiveOperation.value && it.actorId == fixture.plan.actorId(SasActor.UH, fixture.coordinates).value
    })
    fixture.reopen()
    evidence.unbound("SAS-011-EXACT-CONFLICT", "U1/U5: exact conflict code and digest canonicalization are undecided")
    platformRemainder("SAS-011", evidence)
    return SAS_ANDROID_EXECUTED
}

/** SAS-012: a new operation ID must not duplicate or replace the committed ACK/go-live. */
private suspend fun runSas012(fixture: SasFixture, evidence: SasEvidence): String {
    listOf(SasActor.UA, SasActor.UB, SasActor.UG, SasActor.UH).forEach { fixture.enroll(it) }
    fixture.activate(SasActor.UA, Revision(1), setOf(SecurityAction.SETUP_RESULT_DELIVERY_ACKNOWLEDGE))
    fixture.activate(SasActor.UB, Revision(1), setOf(SecurityAction.SETUP_RESULT_DELIVERY_ACKNOWLEDGE))
    fixture.activate(SasActor.UG, Revision(1), setOf(SecurityAction.SETUP_BUSINESS_GO_LIVE))
    fixture.activate(SasActor.UH, Revision(1), setOf(SecurityAction.SETUP_BUSINESS_GO_LIVE))
    val baseline = fixture.cut()
    fixture.reopen()
    listOf(SasActor.UA, SasActor.UB, SasActor.UG, SasActor.UH).forEach { fixture.signIn(it) }
    val ackOperation = OperationId.random()
    requireAck(recorded(fixture.ack(SasActor.UA, ackOperation)), fixture, SasActor.UA, ackOperation)
    requireDeferred(fixture.ack(SasActor.UB, OperationId.random()),
        com.ukkera.pos.database.DeferredReason.CURRENT_SOURCE_UNAVAILABLE)
    requireDeferred(fixture.ack(SasActor.UA, OperationId.random()),
        com.ukkera.pos.database.DeferredReason.CURRENT_SOURCE_UNAVAILABLE)
    val goLiveOperation = OperationId.random()
    requireRecordedGoLive(fixture, SasActor.UG, goLiveOperation)
    val duplicateGoLive = fixture.goLive(SasActor.UH, OperationId.random())
    check(duplicateGoLive is MutationReply.Deferred || duplicateGoLive is MutationReply.Recorded)
    val after = fixture.cut()
    after.requireSameSetupBase(baseline)
    after.requireSetupAggregateDelta(baseline, LocalSetupResultProfileV1.ACKNOWLEDGEMENT_KIND, 1)
    after.requireSetupAggregateDelta(baseline, LocalSetupResultProfileV1.GO_LIVE_KIND, 1)
    check(after.operations.count {
        it.command == LocalSetupResultProfileV1.ACKNOWLEDGE.name &&
            it.operationId == ackOperation.value && it.actorId == fixture.plan.actorId(SasActor.UA, fixture.coordinates).value
    } == 1)
    check(after.operations.count {
        it.command == LocalSetupResultProfileV1.GO_LIVE.name &&
            it.operationId == goLiveOperation.value && it.actorId == fixture.plan.actorId(SasActor.UG, fixture.coordinates).value
    } == 1)
    fixture.reopen()
    evidence.unbound("SAS-012-EXACT-NEW-ID-RESULT", "U7/U8: exact result for a repeated new ID is undecided; only no-duplicate/no-actor-replacement is asserted")
    platformRemainder("SAS-012", evidence)
    return SAS_ANDROID_EXECUTED
}

/** SAS-013: a lost reply and restart recover the original committed result without reinstall. */
private suspend fun runSas013(fixture: SasFixture, evidence: SasEvidence): String {
    listOf(SasActor.UA, SasActor.UG, SasActor.UR).forEach { fixture.enroll(it) }
    fixture.activate(SasActor.UA, Revision(1), setOf(SecurityAction.SETUP_RESULT_DELIVERY_ACKNOWLEDGE))
    fixture.activate(SasActor.UG, Revision(1), setOf(SecurityAction.SETUP_BUSINESS_GO_LIVE))
    fixture.activate(SasActor.UR, Revision(1), setOf(SecurityAction.SETUP_RESULT_READ))
    val baseline = fixture.cut()
    fixture.reopen()
    fixture.signIn(SasActor.UA)
    val ackOperation = OperationId.random()
    val ackRequest = LocalSetupResultCodecV1.acknowledge(ackOperation, fixture.anchor.installationId)
    val ackLost = recorded(fixture.client.execute(SasActor.UA, ackRequest))
    requireAck(ackLost, fixture, SasActor.UA, ackOperation)
    fixture.restart()
    fixture.signIn(SasActor.UA)
    val ackRecovered = recorded(fixture.client.execute(SasActor.UA, ackRequest))
    check(ackRecovered.replayed && ackRecovered.result == ackLost.result && ackRecovered.recordedAt == ackLost.recordedAt)

    fixture.signIn(SasActor.UG)
    val goLiveOperation = OperationId.random()
    val goLiveRequest = LocalSetupResultCodecV1.goLive(goLiveOperation, fixture.anchor.installationId)
    val goLiveLost = recorded(fixture.client.execute(SasActor.UG, goLiveRequest))
    fixture.restart()
    fixture.signIn(SasActor.UG)
    val goLiveRecovered = recorded(fixture.client.execute(SasActor.UG, goLiveRequest))
    check(goLiveRecovered.replayed && goLiveRecovered.result == goLiveLost.result &&
        goLiveRecovered.recordedAt == goLiveLost.recordedAt &&
        goLiveRecovered.originalActorId == fixture.plan.actorId(SasActor.UG, fixture.coordinates))

    fixture.signIn(SasActor.UR)
    val projection = fixture.read(SasActor.UR)
    fixture.anchor.requireAcknowledgement(projection, fixture.plan.actorId(SasActor.UA, fixture.coordinates), ackOperation)
    fixture.anchor.requireLive(projection, fixture.plan.actorId(SasActor.UG, fixture.coordinates), goLiveOperation)
    val after = fixture.cut()
    after.requireSameSetupBase(baseline)
    after.requireSetupAggregateDelta(baseline, LocalSetupResultProfileV1.ACKNOWLEDGEMENT_KIND, 1)
    after.requireSetupAggregateDelta(baseline, LocalSetupResultProfileV1.GO_LIVE_KIND, 1)
    check(after.count(com.ukkera.pos.security.NativeHistoryTableV2.BRANCH) == 1L)
    fixture.reopen()
    evidence.unbound("SAS-013-EXACT-RECOVERY-SURFACE", "U1/U3: exact recovery surface/status and native envelope lifetime are undecided")
    platformRemainder("SAS-013", evidence)
    return SAS_ANDROID_EXECUTED
}

/** SAS-014: no production fault-injection boundary is exposed, so this is not simulated. */
private suspend fun runSas014(fixture: SasFixture, evidence: SasEvidence): String {
    fixture.enroll(SasActor.UA)
    fixture.enroll(SasActor.UG)
    fixture.activate(SasActor.UA, Revision(1), setOf(SecurityAction.SETUP_RESULT_DELIVERY_ACKNOWLEDGE))
    fixture.activate(SasActor.UG, Revision(1), setOf(SecurityAction.SETUP_BUSINESS_GO_LIVE))
    val baseline = fixture.cut()
    baseline.requireNoSetupFacts()
    evidence.unbound("SAS-014-PRE-COMMIT-FAULT-PATHS", "No production fault-injection boundary (receive/auth/validation/policy/identity/fact/audit/journal/storage) is installed; in-memory or mock storage would not qualify")
    evidence.unbound("SAS-014-EXACT-STORAGE-STATUS", "U1/U6: exact storage error/status representation is undecided")
    platformRemainder("SAS-014", evidence)
    return SAS_BLOCKED_FAULT
}

/** SAS-015: actual commit/flush/fsync/power ambiguity is not available in this harness. */
private suspend fun runSas015(fixture: SasFixture, evidence: SasEvidence): String {
    fixture.enroll(SasActor.UA)
    fixture.enroll(SasActor.UG)
    fixture.activate(SasActor.UA, Revision(1), setOf(SecurityAction.SETUP_RESULT_DELIVERY_ACKNOWLEDGE))
    fixture.activate(SasActor.UG, Revision(1), setOf(SecurityAction.SETUP_BUSINESS_GO_LIVE))
    fixture.cut().requireNoSetupFacts()
    evidence.unbound("SAS-015-COMMIT-AMBIGUITY", "No production commit/flush/fsync/prepare-response/client-receive interruption or device power fault is exposed; all-or-none was not simulated")
    evidence.unbound("SAS-015-DEVICE-DURABILITY", "U6: device-specific durability is undecided; actual power interruption is L6 and remains unverified")
    platformRemainder("SAS-015", evidence)
    return SAS_BLOCKED_FAULT
}

/** SAS-016: protected read/result recovery require current authority; supervisor recovery is audited. */
private suspend fun runSas016(fixture: SasFixture, evidence: SasEvidence): String {
    listOf(SasActor.UA, SasActor.UV, SasActor.UR).forEach { fixture.enroll(it) }
    fixture.activate(SasActor.UA, Revision(1), setOf(SecurityAction.SETUP_RESULT_DELIVERY_ACKNOWLEDGE))
    fixture.activate(SasActor.UV, Revision(1), setOf(SecurityAction.SETUP_RESULT_DELIVERY_ACKNOWLEDGE,
        SecurityAction.SETUP_RESULT_READ))
    fixture.activate(SasActor.UR, Revision(1), setOf(SecurityAction.SETUP_RESULT_READ,
        SecurityAction.OPERATION_RESULT_RECOVERY))
    val baseline = fixture.cut()
    fixture.reopen()
    fixture.signIn(SasActor.UA)
    val operationId = OperationId.random()
    requireAck(recorded(fixture.ack(SasActor.UA, operationId)), fixture, SasActor.UA, operationId)
    fixture.activate(SasActor.UV, Revision(2), emptySet())
    fixture.signIn(SasActor.UR)
    fixture.signIn(SasActor.UV)
    requireReadDenied(fixture.client.read(SasActor.UA,
        LocalSetupResultCodecV1.read(fixture.anchor.installationId)))
    requireReadDenied(fixture.client.read(SasActor.UV,
        LocalSetupResultCodecV1.read(fixture.anchor.installationId)))
    val projection = fixture.read(SasActor.UR)
    fixture.anchor.requireAcknowledgement(projection, fixture.plan.actorId(SasActor.UA, fixture.coordinates), operationId)
    val payload = LocalSetupResultCodecV1.encodeRequest(fixture.anchor.installationId)
    val identity = OperationIdentity(fixture.coordinates.scope.branchId, operationId,
        fixture.plan.actorId(SasActor.UA, fixture.coordinates), 1,
        semanticDigest(fixture.coordinates.scope, fixture.plan.actorId(SasActor.UA, fixture.coordinates),
            LocalSetupResultProfileV1.ACKNOWLEDGE, payload).value)
    val found = fixture.original.client.lookup(fixture.client.credentials(SasActor.UR), identity)
    check(found is LookupReply.Found && found.result.operationId == operationId) {
        "The explicitly recovery-granted supervisor could not recover the original result: $found"
    }
    val after = fixture.cut()
    after.requireSetupAggregateDelta(baseline, LocalSetupResultProfileV1.ACKNOWLEDGEMENT_KIND, 1)
    check(after.audits.any {
        it.operationId == operationId.value && it.actorId == fixture.plan.actorId(SasActor.UR, fixture.coordinates).value
    }) { "The supervisor result recovery was not audited" }
    fixture.reopen()
    evidence.unbound("SAS-016-EXACT-PROJECTION", "U1/U10: exact protected-read projection fields are undecided")
    evidence.unbound("SAS-016-EXACT-SUPERVISOR-PERMISSION", "U11: exact supervisor recovery permission/scope/audit representation is undecided; an explicit scoped recovery grant plus audit was exercised")
    platformRemainder("SAS-016", evidence)
    return SAS_ANDROID_EXECUTED
}

/** SAS-017: the frozen S1P missing-prerequisite variants still require an independent source. */
private suspend fun runSas017(fixture: SasFixture, evidence: SasEvidence): String {
    listOf(SasActor.UG, SasActor.UR).forEach { fixture.enroll(it) }
    fixture.activate(SasActor.UG, Revision(1), setOf(SecurityAction.SETUP_BUSINESS_GO_LIVE))
    fixture.activate(SasActor.UR, Revision(1), setOf(SecurityAction.SETUP_RESULT_READ))
    val baseline = fixture.cut()
    fixture.reopen()
    fixture.signIn(SasActor.UR)
    val projection = fixture.read(SasActor.UR)
    check(projection.prerequisites.size == 4 && projection.prerequisites.map { it.code }.toSet() ==
        com.ukkera.pos.database.setup.LocalSetupPrerequisiteCodeV1.entries.toSet())
    check(projection.prerequisites.all { it.settled }) {
        "The frozen BASE-CUT did not expose the four settled production prerequisites"
    }
    // The frozen case requires missing/in-progress S1P variants. BASE-CUT now settles every
    // required source; producing an unsettled variant would need an unapproved injection or a
    // replacement readiness service, so the variant segment stays BLOCKED rather than passing
    // a settled go-live as SAS-017.
    val after = fixture.cut()
    after.requireSameSetupBase(baseline)
    after.requireNoSetupFacts()
    fixture.reopen()
    evidence.event("SAS017_BASE_CUT_PREREQUISITES_SETTLED", JSONObject()
        .put("bindings", projection.prerequisites.joinToString(",") { "${it.code.name}=${it.binding}" })
        .put("goLiveAttempted", false))
    evidence.unbound("SAS-017-MISSING-PREREQUISITE-VARIANTS", "The frozen S1P native-settlement/configuration/import/opening missing variants cannot be independently produced from the settled BASE-CUT; no accepting OPERATIONAL/RUN/boolean/mock readiness was supplied")
    evidence.unbound("SAS-017-OPTIONAL-STANDBY", "An absent optional standby is not represented by a production observation in this fixture")
    platformRemainder("SAS-017", evidence)
    return SAS_BLOCKED_READINESS
}

/** SAS-018: caller-supplied readiness/history/actor/permission/time/replacement is not authority. */
private suspend fun runSas018(fixture: SasFixture, evidence: SasEvidence): String {
    listOf(SasActor.UA, SasActor.UG, SasActor.UR).forEach { fixture.enroll(it) }
    fixture.activate(SasActor.UA, Revision(1), setOf(SecurityAction.SETUP_RESULT_DELIVERY_ACKNOWLEDGE))
    fixture.activate(SasActor.UG, Revision(1), setOf(SecurityAction.SETUP_BUSINESS_GO_LIVE))
    fixture.activate(SasActor.UR, Revision(1), setOf(SecurityAction.SETUP_RESULT_READ))
    val baseline = fixture.cut()
    fixture.reopen()
    fixture.signIn(SasActor.UA)
    fixture.signIn(SasActor.UG)
    fixture.signIn(SasActor.UR)
    val forgedAck = forgedRequest(OperationId.random(), LocalSetupResultProfileV1.ACKNOWLEDGE,
        fixture.anchor.installationId, "\"ready\":true,\"actorId\":\"${fixture.plan.actorId(SasActor.UG, fixture.coordinates).value}\"")
    requireDeferred(fixture.client.execute(SasActor.UA, forgedAck),
        com.ukkera.pos.database.DeferredReason.MALFORMED_REQUEST)
    val forgedGoLive = forgedRequest(OperationId.random(), LocalSetupResultProfileV1.GO_LIVE,
        fixture.anchor.installationId,
        "\"ready\":true,\"permission\":\"SETUP_BUSINESS_GO_LIVE\",\"auditTime\":\"2000-01-01T00:00:00Z\"," +
            "\"replacementInstallationId\":\"${DomainId.random().value}\"")
    requireDeferred(fixture.client.execute(SasActor.UG, forgedGoLive),
        com.ukkera.pos.database.DeferredReason.MALFORMED_REQUEST)
    val operationId = OperationId.random()
    val reply = fixture.goLive(SasActor.UG, operationId)
    val projection = fixture.read(SasActor.UR)
    when (reply) {
        is MutationReply.Deferred -> {
            requireDeferred(reply, com.ukkera.pos.database.DeferredReason.CURRENT_SOURCE_UNAVAILABLE)
            check(projection.prerequisites.any { !it.settled })
            evidence.event("SAS018_SUPPLIED_FIELDS_REFUSED", JSONObject()
                .put("forgedAck", "MALFORMED_REQUEST").put("forgedGoLive", "MALFORMED_REQUEST")
                .put("actualReadinessSettled", projection.prerequisites.all { it.settled }))
        }
        is MutationReply.Recorded -> {
            val outcome = LocalSetupResultCodecV1.decodeOutcome(reply.result)
            check(outcome.status == LocalSetupResultStatusV1.LIVE &&
                outcome.goLive?.actorId == fixture.plan.actorId(SasActor.UG, fixture.coordinates))
        }
        else -> error("Unexpected go-live reply: $reply")
    }
    val after = fixture.cut()
    after.requireSameSetupBase(baseline)
    if (reply is MutationReply.Deferred) after.requireNoSetupFacts() else
        after.requireSetupAggregateDelta(baseline, LocalSetupResultProfileV1.GO_LIVE_KIND, 1)
    fixture.reopen()
    evidence.unbound("SAS-018-MOCK-READINESS", "No mock readiness service is installed in the production composition; it cannot satisfy this case and was not injected")
    evidence.unbound("SAS-018-SETTLED-FORGERY", "With actual readiness unsettled, forged actor/permission/time/replacement fields were refused by the strict codec; the settled-path forgery remains unbound")
    platformRemainder("SAS-018", evidence)
    return if (reply is MutationReply.Recorded) SAS_ANDROID_EXECUTED else SAS_BLOCKED_READINESS
}

/** SAS-019: training/live isolation and the negative non-effects of go-live. */
private suspend fun runSas019(fixture: SasFixture, evidence: SasEvidence): String {
    listOf(SasActor.UA, SasActor.UG).forEach { fixture.enroll(it) }
    fixture.activate(SasActor.UA, Revision(1), setOf(SecurityAction.SETUP_RESULT_DELIVERY_ACKNOWLEDGE))
    fixture.activate(SasActor.UG, Revision(1), setOf(SecurityAction.SETUP_BUSINESS_GO_LIVE))
    val baseline = fixture.cut()
    fixture.reopen()
    fixture.signIn(SasActor.UA)
    fixture.signIn(SasActor.UG)
    val ackOperation = OperationId.random()
    requireAck(recorded(fixture.ack(SasActor.UA, ackOperation)), fixture, SasActor.UA, ackOperation)
    val goLiveOperation = OperationId.random()
    val goLive = fixture.goLive(SasActor.UG, goLiveOperation)
    val after = fixture.cut()
    after.requireSameSetupBase(baseline)
    after.requireSetupAggregateDelta(baseline, LocalSetupResultProfileV1.ACKNOWLEDGEMENT_KIND, 1)
    after.requireSetupAggregateDelta(baseline, LocalSetupResultProfileV1.GO_LIVE_KIND,
        if (goLive is MutationReply.Recorded) 1 else 0)
    val baselineKinds = baseline.aggregates.groupingBy { it.kind }.eachCount()
    for ((kind, count) in after.aggregates.groupingBy { it.kind }.eachCount()) {
        val expected = (baselineKinds[kind] ?: 0) + when (kind) {
            LocalSetupResultProfileV1.ACKNOWLEDGEMENT_KIND -> 1
            LocalSetupResultProfileV1.GO_LIVE_KIND -> if (goLive is MutationReply.Recorded) 1 else 0
            else -> 0
        }
        check(count == expected) { "A setup action created an unrelated business aggregate: $kind" }
    }
    for (table in listOf(com.ukkera.pos.security.NativeHistoryTableV2.STOCK_POSITION,
        com.ukkera.pos.security.NativeHistoryTableV2.STOCK_ALLOCATION,
        com.ukkera.pos.security.NativeHistoryTableV2.STOCK_RESERVATION,
        com.ukkera.pos.security.NativeHistoryTableV2.STOCK_VALUE)) {
        check(after.count(table) == baseline.count(table)) { "Go-live/ACK moved stock: $table" }
    }
    fixture.reopen()
    evidence.unbound("SAS-019-T1-TRAINING-STORE", "A separate durable training store (T1) is not present in this installation; no training sale/return/close was created and cross-store attempts were not simulated")
    evidence.unbound("SAS-019-CENTRAL-FISCAL", "U9: central registration/health/fiscal mechanisms are unavailable; only their negative non-effects are asserted")
    platformRemainder("SAS-019", evidence)
    return if (goLive is MutationReply.Recorded) SAS_ANDROID_EXECUTED else SAS_BLOCKED_READINESS
}

/** SAS-020: distinct branch/client/training/installation isolation is not available in this fixture. */
private suspend fun runSas020(fixture: SasFixture, evidence: SasEvidence): String {
    listOf(SasActor.UA, SasActor.UG).forEach { fixture.enroll(it) }
    fixture.activate(SasActor.UA, Revision(1), setOf(SecurityAction.SETUP_RESULT_DELIVERY_ACKNOWLEDGE))
    fixture.activate(SasActor.UG, Revision(1), setOf(SecurityAction.SETUP_BUSINESS_GO_LIVE))
    fixture.cut().requireNoSetupFacts()
    evidence.unbound("SAS-020-D2-CLIENT", "No separately admitted B1 client device (D2) is present; the original-master-only fixture cannot prove or disprove client finalization")
    evidence.unbound("SAS-020-S2-B2-D5", "No distinct B2 installation/device/account (S2/D5) is present in this app-private installation")
    evidence.unbound("SAS-020-T1-TRAINING", "No separate training store/namespace (T1) is present")
    evidence.unbound("SAS-020-CACHED-PROJECTION", "No cached cross-branch projection can be produced without a second branch; nothing was simulated")
    platformRemainder("SAS-020", evidence)
    return SAS_BLOCKED_ENVIRONMENT
}

/** SAS-021: the responsible go-live actor is the current local employee; no substitution. */
private suspend fun runSas021(fixture: SasFixture, evidence: SasEvidence): String {
    listOf(SasActor.UG, SasActor.UR).forEach { fixture.enroll(it) }
    fixture.activate(SasActor.UG, Revision(1), setOf(SecurityAction.SETUP_BUSINESS_GO_LIVE))
    fixture.activate(SasActor.UR, Revision(1), setOf(SecurityAction.SETUP_RESULT_READ))
    val baseline = fixture.cut()
    fixture.reopen()
    fixture.signIn(SasActor.US)
    fixture.signIn(SasActor.UG)
    requireDeferred(fixture.goLive(SasActor.US, OperationId.random()),
        com.ukkera.pos.database.DeferredReason.AUTHORIZATION_DENIED)
    val unrelatedApproval = MutationRequest(OperationId.random(), LocalSetupResultProfileV1.GO_LIVE,
        LocalSetupResultCodecV1.encodeRequest(fixture.anchor.installationId), DomainId.random())
    requireDeferred(fixture.client.execute(SasActor.UG, unrelatedApproval),
        com.ukkera.pos.database.DeferredReason.AUTHORIZATION_DENIED)
    fixture.signIn(SasActor.UR)
    val operationId = OperationId.random()
    val reply = fixture.goLive(SasActor.UG, operationId)
    if (reply is MutationReply.Deferred) {
        requireDeferred(reply, com.ukkera.pos.database.DeferredReason.CURRENT_SOURCE_UNAVAILABLE)
        val after = fixture.cut()
        after.requireSameSetupBase(baseline)
        after.requireNoSetupFacts()
        fixture.reopen()
        evidence.unbound("SAS-021-ACTUAL-GO-LIVE-ACTOR", "Actual original-linked readiness is unbound; the successful UG go-live actor/audit and substitution retry could not be exercised")
        evidence.unbound("SAS-021-CENTRAL-IDENTITY", "UC central-administration session is unavailable locally")
        evidence.unbound("SAS-021-EXACT-APPROVAL-AUDIT", "U1/U4: exact approval/audit representation is undecided")
        platformRemainder("SAS-021", evidence)
        return SAS_BLOCKED_READINESS
    }
    check(reply is MutationReply.Recorded)
    val outcome = LocalSetupResultCodecV1.decodeOutcome(reply.result)
    check(outcome.goLive?.actorId == fixture.plan.actorId(SasActor.UG, fixture.coordinates))
    val projection = fixture.read(SasActor.UR)
    fixture.anchor.requireLive(projection, fixture.plan.actorId(SasActor.UG, fixture.coordinates), operationId)
    fixture.restart()
    fixture.signIn(SasActor.US)
    val substitute = fixture.goLive(SasActor.US, operationId)
    check(substitute is MutationReply.IdentityConflict || substitute is MutationReply.Deferred)
    fixture.signIn(SasActor.UG)
    val retry = recorded(fixture.goLive(SasActor.UG, operationId))
    check(retry.replayed && retry.originalActorId == fixture.plan.actorId(SasActor.UG, fixture.coordinates))
    val after = fixture.cut()
    after.requireOneGoLive(baseline, fixture.plan.actorId(SasActor.UG, fixture.coordinates), operationId)
    check(after.setupAggregates(LocalSetupResultProfileV1.ACKNOWLEDGEMENT_KIND).isEmpty())
    fixture.reopen()
    platformRemainder("SAS-021", evidence)
    return SAS_ANDROID_EXECUTED
}

/** SAS-022: existing setup survives compatible reopen; old-build bytes and unknown profiles stay unbound. */
private suspend fun runSas022(fixture: SasFixture, evidence: SasEvidence): String {
    listOf(SasActor.UR, SasActor.UA).forEach { fixture.enroll(it) }
    fixture.activate(SasActor.UR, Revision(1), setOf(SecurityAction.SETUP_RESULT_READ))
    fixture.activate(SasActor.UA, Revision(1), setOf(SecurityAction.SETUP_RESULT_DELIVERY_ACKNOWLEDGE))
    val baseline = fixture.cut()
    fixture.reopen()
    fixture.signIn(SasActor.UR)
    val first = fixture.read(SasActor.UR)
    fixture.anchor.requireProjection(first)
    check(first.acknowledgement == null && first.goLive == null)
    fixture.restart()
    fixture.signIn(SasActor.UR)
    val second = fixture.read(SasActor.UR)
    fixture.anchor.requireProjection(second)
    check(second.acknowledgement == null && second.goLive == null)
    fixture.signIn(SasActor.UA)
    val forged = forgedRequest(OperationId.random(), LocalSetupResultProfileV1.ACKNOWLEDGE,
        fixture.anchor.installationId, "\"profile\":\"unknown/v99\",\"schemaVersion\":999")
    requireDeferred(fixture.client.execute(SasActor.UA, forged),
        com.ukkera.pos.database.DeferredReason.MALFORMED_REQUEST)
    val after = fixture.cut()
    after.requireSameSetupBase(baseline)
    after.requireNoSetupFacts()
    fixture.reopen()
    evidence.unbound("SAS-022-PRE-INCREMENT-DATA", "S1/S1A/S1L data created by the pre-increment build is not present in this installation; only compatible same-build reopen was exercised")
    evidence.unbound("SAS-022-UNKNOWN-PROFILE-STORAGE", "An unknown stored profile/version cannot be injected without direct DB rewriting; only an unknown request field was refused by the strict codec")
    evidence.unbound("SAS-022-BYTE-COMPARISON", "U12: exact schema/profile/canonical-byte representation and independent byte-level Android comparison are undecided")
    platformRemainder("SAS-022", evidence)
    return SAS_BLOCKED_UPGRADE
}

private fun recorded(reply: MutationReply): MutationReply.Recorded =
    reply as? MutationReply.Recorded ?: error("Expected a committed setup action, got ${reply::class.simpleName}")

private fun requireDeferred(reply: MutationReply, reason: com.ukkera.pos.database.DeferredReason): MutationReply.Deferred {
    val deferred = reply as? MutationReply.Deferred ?: error("Expected a held setup action, got ${reply::class.simpleName}")
    check(deferred.reason == reason) { "Expected $reason but the master held with ${deferred.reason}" }
    return deferred
}

private suspend fun requireRecordedGoLive(fixture: SasFixture, actor: SasActor, operationId: OperationId):
    MutationReply.Recorded {
    val recorded = recorded(fixture.goLive(actor, operationId))
    val outcome = LocalSetupResultCodecV1.decodeOutcome(recorded.result)
    check(recorded.committed && !recorded.replayed && recorded.operationId == operationId &&
        recorded.originalActorId == fixture.plan.actorId(actor, fixture.coordinates) &&
        outcome.status == LocalSetupResultStatusV1.LIVE && outcome.goLive?.actorId ==
        fixture.plan.actorId(actor, fixture.coordinates) && outcome.goLive?.prerequisites?.all { it.settled } == true) {
        "The go-live was not committed by the responsible current local actor with settled prerequisites"
    }
    return recorded
}

private fun requireAck(recorded: MutationReply.Recorded, fixture: SasFixture, actor: SasActor,
    operationId: OperationId) {
    check(recorded.committed && !recorded.replayed && recorded.operationId == operationId &&
        recorded.originalActorId == fixture.plan.actorId(actor, fixture.coordinates)) {
        "The ACK was not committed under the exact original actor/operation"
    }
    val outcome: LocalSetupResultOutcomeV1 = LocalSetupResultCodecV1.decodeOutcome(recorded.result)
    check(outcome.status == LocalSetupResultStatusV1.ACKNOWLEDGED && outcome.goLive == null) {
        "The ACK outcome did not identify exactly one acknowledgement"
    }
    val acknowledgement = checkNotNull(outcome.acknowledgement)
    check(acknowledgement.installationId == fixture.anchor.installationId &&
        acknowledgement.originalRequestId == fixture.anchor.originalRequestId &&
        acknowledgement.issuedAttemptId == fixture.anchor.issuedAttemptId &&
        acknowledgement.definitionDigest.value == fixture.anchor.definitionDigest &&
        acknowledgement.genesisDigest.value == fixture.anchor.genesisDigest &&
        acknowledgement.actorId == fixture.plan.actorId(actor, fixture.coordinates) &&
        acknowledgement.operationId == operationId &&
        acknowledgement.masterAuthorityEpoch.value == fixture.anchor.authorityEpoch) {
        "The ACK record is not bound to the saved original/actor/operation/current master"
    }
}

private fun requireReadDenied(reply: ReadReply) {
    val unavailable = reply as? ReadReply.Unavailable
        ?: error("A protected setup read was disclosed without the required current action grant")
    check(unavailable.reason == com.ukkera.pos.database.ReadFailure.AUTHORIZATION_DENIED) {
        "The protected read failed for a reason other than current authorization: ${unavailable.reason}"
    }
}

private suspend fun accountRevision(fixture: SasFixture, operator: SasActor, target: DomainId): Revision {
    val view = fixture.original.administration.readAdministration(
        fixture.client.credentials(operator),
        LocalAdministrationQuery(LocalAdministrationDirectory.ACCOUNTS, targetId = target),
    ) as? LocalAdministrationReadResult.Available
        ?: error("The actual protected account read is unavailable")
    val detail = checkNotNull(view.view.accountDetail) { "The actual target account detail is unavailable" }
    check(detail.account.actorId == target)
    return detail.account.accountRevision
}

private fun forgedRequest(operationId: OperationId, command: com.ukkera.pos.database.CommandKey,
    installationId: DomainId, extraFields: String): MutationRequest {
    val json = "{\"installationId\":\"${installationId.value}\",$extraFields}"
    return MutationRequest(operationId, command, StoredBytes(json.encodeToByteArray()))
}

private fun platformRemainder(case: String, evidence: SasEvidence) {
    evidence.unbound("$case-ALL-MASTER", "Windows, macOS, physical iPhone and physical iPad real-provider segments are not exercised by this Android selected-case run")
}
