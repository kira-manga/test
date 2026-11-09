package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import java.nio.file.Path
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*

/** Executor wiring only. Every caller names an unchanged frozen assertion's partial boundary. */
internal suspend fun authV1WithFixture(
    directory: Path,
    design: JsonObject,
    journal: AuthV1Journal,
    name: String,
    setup: AuthV1FixtureSetup = AuthV1FixtureSetup(),
    body: suspend (AuthV1Fixture) -> Unit,
) {
    val fixture = AuthV1Fixture(name, directory.resolve("$name.db"), design, journal, setup)
    try {
        fixture.start()
        body(fixture)
    } finally { fixture.dispose() }
}

/** Real simultaneous endpoint submissions, not a forced internal transaction/commit schedule. */
internal suspend fun <T> authV1Concurrent(vararg calls: suspend () -> T): List<T> = coroutineScope {
    require(calls.size == 2)
    val release = CompletableDeferred<Unit>()
    val ready = calls.map { CompletableDeferred<Unit>() }
    val pending = calls.mapIndexed { index, call ->
        async(Dispatchers.Default) {
            ready[index].complete(Unit)
            release.await()
            call()
        }
    }
    ready.awaitAll()
    release.complete(Unit)
    pending.awaitAll()
}

internal fun AuthV1Slice.authenticated(label: String, result: LocalAuthenticationResult): LocalSessionTokens {
    observe("${label}_result", result.authV1Code())
    expect("${label}_authenticated", result is LocalAuthenticationResult.Authenticated)
    return (result as LocalAuthenticationResult.Authenticated).tokens
}

internal fun AuthV1Slice.authenticationRefused(label: String, result: LocalAuthenticationResult) {
    observe("${label}_result", result.authV1Code())
    expect("${label}_authentication_refused", result is LocalAuthenticationResult.Denied &&
        result.reason == LocalAuthenticationDenial.AUTHENTICATION_REFUSED)
}

internal fun AuthV1Slice.applied(label: String, result: LocalCredentialAdministrationResult): DomainId {
    observe("${label}_result", result.authV1Code())
    expect("${label}_applied", result is LocalCredentialAdministrationResult.Applied)
    return (result as LocalCredentialAdministrationResult.Applied).eventId
}

internal suspend fun AuthV1Slice.current(
    f: AuthV1Fixture,
    tokens: LocalSessionTokens,
    who: String,
    connection: String,
    label: String,
): LocalSessionView {
    val result = f.endpoint.current(f.access(tokens, connection))
    observe("${label}_result", result.authV1Code())
    expect("${label}_current", result is LocalSessionViewResult.Current)
    val view = (result as LocalSessionViewResult.Current).view
    expect("${label}_same_actor_scope_device_session", view.actorId == f.actors.getValue(who) && view.scope == f.scope &&
        view.deviceId == f.device(connection) && view.sessionId == tokens.view.sessionId &&
        view.generation == tokens.view.generation && view.login == f.login(who))
    observe("${label}_view", view.authV1ViewJson())
    return view
}

internal suspend fun AuthV1Slice.revokedCredentials(
    f: AuthV1Fixture,
    tokens: LocalSessionTokens,
    connection: String,
    label: String,
) {
    val result = f.endpoint.current(f.access(tokens, connection))
    observe("${label}_old_access_current_result", result.authV1Code())
    expect("${label}_old_access_denied", result == LocalSessionViewResult.Denied)
    authenticationRefused("${label}_old_refresh", f.rotate(tokens, connection))
}

internal fun LocalSessionView.authV1ViewJson(): JsonObject = buildJsonObject {
    put("actor_id", actorId.value)
    put("scope", scope.authV1ScopeJson())
    put("device_id", deviceId.value)
    put("session_id", sessionId.value)
    put("generation", generation.value)
    put("policy_revision", policy.revision.value)
    put("maximum_concurrent_sessions", policy.sessionPolicy.maximumConcurrentSessions)
    put("access_expires_at", accessExpiresAt.authV1MomentJson())
    put("work_expires_at", workExpiresAt.authV1MomentJson())
    // Login, secrets, hashes and credential records are deliberately not emitted.
}

private fun grantFacts(grant: PermissionGrant): List<Any?> = listOf(
    grant.permission, grant.scope, grant.effect, grant.limits, grant.ownerAccess,
    grant.namedSubjectId, grant.targetIds, grant.delegationCeiling,
)

internal fun authV1SamePolicy(left: ActivatedLocalPolicy, right: ActivatedLocalPolicy): Boolean =
    left.scope == right.scope && left.actorId == right.actorId && left.revision == right.revision &&
        left.sessionPolicy == right.sessionPolicy && left.throttlePolicy == right.throttlePolicy &&
        left.approvalPolicy?.validForMillis == right.approvalPolicy?.validForMillis &&
        left.approvalPolicy?.namedSelfApprovalActions == right.approvalPolicy?.namedSelfApprovalActions &&
        left.grants.map(::grantFacts).groupingBy { it }.eachCount() ==
        right.grants.map(::grantFacts).groupingBy { it }.eachCount()

/** Compare observed protected facts; not a byte-for-byte password-verifier/storage audit. */
internal fun authV1SameObservedAccount(left: AuthV1AccountFacts, right: AuthV1AccountFacts): Boolean =
    left.json() == right.json() && authV1SamePolicy(left.policy, right.policy) &&
        left.configuration.scope == right.configuration.scope &&
        left.configuration.actorId == right.configuration.actorId &&
        left.configuration.login == right.configuration.login &&
        left.issuances.all { (id, issuance) ->
            val other = right.issuances[id]
            other != null && issuance.access?.tokenHash == other.access?.tokenHash &&
                issuance.refresh?.tokenHash == other.refresh?.tokenHash
        }

internal fun AuthV1AccountFacts.authV1Active(): List<LocalSessionRecord> = state.sessions.filter {
    it.status == SessionStatus.ACTIVE && it.authenticationPurpose == AuthenticationPurpose.INTERACTIVE
}

/**
 * Compare the returned identity/deadline metadata with a FRESH actual protected-store read.
 * No token codec, fake principal, seeded issuance, timing boundary or commit-fault oracle.
 */
internal fun AuthV1Slice.coherentIssuance(
    f: AuthV1Fixture,
    snapshot: AuthV1Snapshot,
    view: LocalSessionView,
    who: String,
    connection: String,
    label: String,
    requireActive: Boolean = true,
) {
    val account = snapshot.accounts.getValue(who)
    val session = account.state.sessions.singleOrNull { it.sessionId == view.sessionId }
    val issuance = account.issuances[view.sessionId]
    val access = issuance?.access
    val refresh = issuance?.refresh
    val admission = snapshot.admissions.getValue(connection)
    expect("${label}_protected_session_access_refresh_present", session != null && access != null && refresh != null)
    checkNotNull(session); checkNotNull(access); checkNotNull(refresh)
    expect("${label}_identity_matches_frozen_actor_and_scope", account.state.actorId == f.actors.getValue(who) &&
        account.state.scope == f.scope && view.actorId == account.state.actorId && view.scope == f.scope &&
        view.deviceId == f.device(connection) && session.actorId == view.actorId && session.scope == view.scope &&
        session.deviceId == view.deviceId && session.generation == view.generation &&
        session.authenticationPurpose == AuthenticationPurpose.INTERACTIVE)
    if (requireActive) expect("${label}_committed_session_active", account.state.enabled && session.status == SessionStatus.ACTIVE)
    expect("${label}_access_metadata_bound_to_session", access.actorId == session.actorId && access.scope == session.scope &&
        access.sessionId == session.sessionId && access.deviceId == session.deviceId && access.generation == session.generation &&
        access.credentialRevisionAtIssue == account.credentialRevision &&
        access.admissionRevisionAtIssue == admission.revision && admission.admitted)
    expect("${label}_refresh_metadata_bound_to_session", refresh.actorId == session.actorId && refresh.scope == session.scope &&
        refresh.sessionId == session.sessionId && refresh.deviceId == session.deviceId && refresh.generation == session.generation &&
        refresh.credentialRevisionAtIssue == account.credentialRevision &&
        refresh.admissionRevisionAtIssue == admission.revision &&
        refresh.configurationRevisionAtIssue == account.configuration.revision &&
        refresh.issuerContextId == access.issuerContextId)
    expect("${label}_lineage_origin_is_retained_same_actor", account.state.sessions.any {
        it.sessionId == refresh.originalSessionId && it.actorId == session.actorId && it.scope == session.scope
    })
    expect("${label}_returned_current_policy_matches_committed", authV1SamePolicy(view.policy, account.policy) &&
        view.policy.scope == f.scope && view.policy.actorId == f.actors.getValue(who) &&
        view.login == account.configuration.login && view.login == f.login(who))
    expect("${label}_expiry_metadata_matches_committed", view.accessExpiresAt.bootId == access.issuedAt.bootId &&
        view.accessExpiresAt.elapsedMillis == access.expiresAtElapsedMillis &&
        view.workExpiresAt.bootId == refresh.workStartedAt.bootId &&
        view.workExpiresAt.elapsedMillis == refresh.expiresAtElapsedMillis)
    expect("${label}_explicit_configured_duration_metadata", account.configuration.tokens.accessLifetimeMillis ==
        f.accessLifetimeMillis && account.configuration.tokens.workLifetimeMillis == f.workLifetimeMillis &&
        access.validForMillis <= f.accessLifetimeMillis && refresh.workLifetimeMillis == f.workLifetimeMillis)
    observe("${label}_boundary", "AFTER_ENDPOINT_RETURN_FRESH_PROTECTED_READ_NOT_CRASH_OR_EXPIRY_BOUNDARY")
}

/** Frozen I011 narrower invariant only; no expectation about an unresolved replay window. */
internal fun AuthV1Slice.singleRefreshLineage(
    f: AuthV1Fixture,
    snapshot: AuthV1Snapshot,
    origin: LocalRefreshIssuance,
    label: String,
) {
    val account = snapshot.accounts.getValue("A")
    val active = account.authV1Active()
    expect("${label}_at_most_one_active_lineage", active.size <= 1)
    expect("${label}_no_actor_scope_or_work_lifetime_expansion", active.all { session ->
        val refresh = account.issuances[session.sessionId]?.refresh
        refresh != null && refresh.actorId == f.actors.getValue("A") && refresh.scope == f.scope &&
            refresh.originalSessionId == origin.originalSessionId &&
            refresh.workStartedAt == origin.workStartedAt &&
            refresh.expiresAtElapsedMillis <= origin.expiresAtElapsedMillis
    })
    observe("${label}_active_count", JsonPrimitive(active.size))
    observe("replay_window_policy", "UNRESOLVED_NOT_ASSERTED")
}

internal fun AuthV1Slice.authEvent(
    snapshot: AuthV1Snapshot,
    eventId: DomainId,
    kind: LocalAuthEventKind,
    actorId: DomainId,
    operatorId: DomainId,
    label: String,
) {
    observe("${label}_event_id", eventId.value)
    expect("${label}_protected_audit_binds_target_and_operator", snapshot.events.singleOrNull { it.eventId == eventId }?.let {
        it.kind == kind && it.targetId == actorId && it.operatorId == operatorId
    } == true)
}
